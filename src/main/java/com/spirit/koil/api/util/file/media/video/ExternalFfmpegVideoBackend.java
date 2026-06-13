package com.spirit.koil.api.util.file.media.video;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.util.file.media.MediaPerformanceProfile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class ExternalFfmpegVideoBackend implements VideoBackend {
    private static final long TOOL_DETECT_TIMEOUT_MS = 5_000L;
    private static final long FAILED_DISCOVERY_RETRY_MS = 10_000L;
    private static final List<String> COMMON_BINARY_DIRECTORIES = List.of(
            "/usr/local/bin",
            "/opt/homebrew/bin",
            "/opt/local/bin",
            "/usr/local/opt/ffmpeg/bin",
            "/usr/bin",
            "/bin"
    );

    private volatile Boolean available;
    private volatile long availabilityCheckedAt;
    private volatile String ffmpegBinary;
    private volatile String ffprobeBinary;
    private volatile String unavailableReason = "ffmpeg backend has not been resolved yet.";

    @Override
    public String backendName() {
        return "ffmpeg";
    }

    @Override
    public boolean isAvailable() {
        Boolean cached = this.available;
        long now = System.currentTimeMillis();
        if (cached != null && (cached || now - this.availabilityCheckedAt < FAILED_DISCOVERY_RETRY_MS)) {
            return cached;
        }
        this.ffprobeBinary = resolveBinary("ffprobe", "KOIL_FFPROBE_PATH");
        this.ffmpegBinary = resolveBinary("ffmpeg", "KOIL_FFMPEG_PATH");
        boolean detected = this.ffprobeBinary != null && this.ffmpegBinary != null;
        this.unavailableReason = detected
                ? ""
                : "ffmpeg binaries were not resolved. Expected ffmpeg and ffprobe on PATH, KOIL_FFMPEG_PATH/KOIL_FFPROBE_PATH, or common install locations.";
        this.availabilityCheckedAt = now;
        this.available = detected;
        return detected;
    }

    @Override
    public VideoProbeResult probe(File file) {
        if (file == null || !file.isFile()) {
            return new VideoProbeResult(false, false, "Video file not found.", null);
        }
        if (!isAvailable()) {
            return new VideoProbeResult(true, false, unavailableMessage(), fallbackMetadata(file, "backend-missing"));
        }

        String ffprobePath;
        try {
            ffprobePath = requiredFfprobeBinary();
        } catch (IOException exception) {
            return new VideoProbeResult(true, false, unavailableMessage(), fallbackMetadata(file, "backend-missing"));
        }

        try {
            return runFullProbe(ffprobePath, file);
        } catch (Exception exception) {
            try {
                return runFallbackProbe(ffprobePath, file, exception);
            } catch (Exception fallbackException) {
                return new VideoProbeResult(true, false, "Video probe failed: " + fallbackException.getMessage(), fallbackMetadata(file, "probe-failed"));
            }
        }
    }

    private VideoProbeResult runFullProbe(String ffprobePath, File file) throws IOException {
        List<String> command = List.of(
                ffprobePath,
                "-v", "error",
                "-print_format", "json",
                "-show_entries", "format=duration,format_name:stream=index,codec_type,width,height,avg_frame_rate,r_frame_rate",
                file.getAbsolutePath()
        );
        ProcessResult result = runProcess(command, MediaPerformanceProfile.VIDEO_PROBE_TIMEOUT_MS);
        if (result.exitCode != 0) {
            return new VideoProbeResult(true, false, sanitizeMessage(result.stderr, "ffprobe failed to read this video."), fallbackMetadata(file, "probe-failed"));
        }
        return buildProbeResult(file, result.stdout, true, "metadata+thumbnail-ready", "Metadata ready. Thumbnail extraction can be requested.");
    }

    private VideoProbeResult runFallbackProbe(String ffprobePath, File file, Exception originalException) throws IOException {
        List<String> command = List.of(
                ffprobePath,
                "-v", "error",
                "-analyzeduration", "1000000",
                "-probesize", "1000000",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height,avg_frame_rate,r_frame_rate:format=duration,format_name",
                "-print_format", "json",
                file.getAbsolutePath()
        );
        ProcessResult result = runProcess(command, Math.max(5_000L, MediaPerformanceProfile.VIDEO_PROBE_TIMEOUT_MS / 2L));
        if (result.exitCode != 0) {
            throw new IOException(sanitizeMessage(result.stderr, originalException.getMessage()));
        }
        VideoProbeResult fallback = buildProbeResult(file, result.stdout, false, "metadata-limited+thumbnail-ready", "Metadata ready through fallback probe. Audio details may be unavailable.");
        if (fallback.metadata() != null && fallback.metadata().durationMillis() > 0L) {
            return fallback;
        }
        throw new IOException(originalException.getMessage());
    }

    private VideoProbeResult buildProbeResult(File file, String stdout, boolean detectAudio, String decoderStage, String successMessage) {
        JsonObject root = JsonParser.parseString(stdout).getAsJsonObject();
        JsonObject format = root.has("format") && root.get("format").isJsonObject() ? root.getAsJsonObject("format") : null;
        JsonArray streams = root.has("streams") && root.get("streams").isJsonArray() ? root.getAsJsonArray("streams") : null;

        JsonObject videoStream = null;
        boolean hasAudio = false;
        if (streams != null) {
            for (JsonElement element : streams) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject stream = element.getAsJsonObject();
                String codecType = readString(stream, "codec_type", "");
                if (videoStream == null && ("video".equals(codecType) || !detectAudio)) {
                    videoStream = stream;
                }
                if (detectAudio && "audio".equals(codecType)) {
                    hasAudio = true;
                }
            }
        }

        int width = videoStream == null ? -1 : readInt(videoStream, "width", -1);
        int height = videoStream == null ? -1 : readInt(videoStream, "height", -1);
        double frameRate = videoStream == null ? 0.0D : parseFrameRate(readString(videoStream, "avg_frame_rate",
                readString(videoStream, "r_frame_rate", "0/0")));
        long durationMillis = parseDurationMillis(format == null ? "" : readString(format, "duration", ""));
        String container = normalizeContainer(format == null ? "" : readString(format, "format_name", extensionOf(file.getName())));

        VideoMetadata metadata = new VideoMetadata(
                file.getName(),
                container,
                width,
                height,
                frameRate,
                durationMillis,
                hasAudio,
                decoderStage,
                backendName(),
                durationMillis > 0L
        );
        return new VideoProbeResult(true, false, successMessage, metadata);
    }

    @Override
    public byte[] extractThumbnail(File file, VideoMetadata metadata, int maxWidth, int maxHeight) throws IOException {
        return extractFrame(file, selectPosterTimestampMillis(metadata), maxWidth, maxHeight);
    }

    @Override
    public byte[] extractFrame(File file, long targetMillis, int maxWidth, int maxHeight) throws IOException {
        if (!isAvailable()) {
            throw new IOException("Optional ffmpeg backend is not available.");
        }
        int targetWidth = Math.max(1, Math.min(maxWidth, MediaPerformanceProfile.VIDEO_THUMBNAIL_MAX_WIDTH));
        int targetHeight = Math.max(1, Math.min(maxHeight, MediaPerformanceProfile.VIDEO_THUMBNAIL_MAX_HEIGHT));
        String seekSeconds = formatSeekSeconds(targetMillis);
        String scaleFilter = "scale=w=" + targetWidth + ":h=" + targetHeight + ":force_original_aspect_ratio=decrease:flags=fast_bilinear";

        List<String> command = new ArrayList<>();
        command.add(requiredFfmpegBinary());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");
        command.add("-threads");
        command.add("1");
        command.add("-ss");
        command.add(seekSeconds);
        command.add("-i");
        command.add(file.getAbsolutePath());
        command.add("-frames:v");
        command.add("1");
        command.add("-an");
        command.add("-sn");
        command.add("-dn");
        command.add("-vf");
        command.add(scaleFilter);
        command.add("-f");
        command.add("image2pipe");
        command.add("-vcodec");
        command.add("png");
        command.add("-");

        ProcessResult result = runProcess(command, MediaPerformanceProfile.VIDEO_THUMBNAIL_TIMEOUT_MS);
        if (result.exitCode != 0 || result.stdoutBytes.length == 0) {
            throw new IOException(sanitizeMessage(result.stderr, "ffmpeg failed to extract a thumbnail."));
        }
        return result.stdoutBytes;
    }

    Process startRawFrameStream(File file, long startMillis, int width, int height, double fps) throws IOException {
        String scaleFilter = "scale=w=" + width + ":h=" + height + ":force_original_aspect_ratio=decrease:flags=fast_bilinear,pad=" + width + ":" + height + ":(ow-iw)/2:(oh-ih)/2:black,fps=" + String.format(Locale.ROOT, "%.3f", fps);
        List<String> command = new ArrayList<>();
        command.add(requiredFfmpegBinary());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");
        command.add("-threads");
        command.add("1");
        command.add("-ss");
        command.add(formatSeekSeconds(startMillis));
        command.add("-i");
        command.add(file.getAbsolutePath());
        command.add("-an");
        command.add("-sn");
        command.add("-dn");
        command.add("-vf");
        command.add(scaleFilter);
        command.add("-pix_fmt");
        command.add("rgba");
        command.add("-f");
        command.add("rawvideo");
        command.add("-");
        return new ProcessBuilder(command).start();
    }

    Process startRawAudioStream(File file, long startMillis) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(requiredFfmpegBinary());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");
        command.add("-threads");
        command.add("1");
        command.add("-ss");
        command.add(formatSeekSeconds(startMillis));
        command.add("-i");
        command.add(file.getAbsolutePath());
        command.add("-vn");
        command.add("-sn");
        command.add("-dn");
        command.add("-ac");
        command.add(String.valueOf(MediaPerformanceProfile.VIDEO_AUDIO_CHANNELS));
        command.add("-ar");
        command.add(String.valueOf((int) MediaPerformanceProfile.VIDEO_AUDIO_SAMPLE_RATE));
        command.add("-f");
        command.add("s16le");
        command.add("-acodec");
        command.add("pcm_s16le");
        command.add("-");
        return new ProcessBuilder(command).start();
    }

    private static VideoMetadata fallbackMetadata(File file, String decoderStage) {
        return new VideoMetadata(
                file == null ? "unknown" : file.getName(),
                extensionOf(file == null ? "" : file.getName()),
                -1,
                -1,
                0.0D,
                -1L,
                false,
                decoderStage,
                "ffmpeg",
                false
        );
    }

    private String requiredFfmpegBinary() throws IOException {
        if (!isAvailable() || this.ffmpegBinary == null) {
            throw new IOException(unavailableMessage());
        }
        return this.ffmpegBinary;
    }

    private String requiredFfprobeBinary() throws IOException {
        if (!isAvailable() || this.ffprobeBinary == null) {
            throw new IOException(unavailableMessage());
        }
        return this.ffprobeBinary;
    }

    String unavailableMessage() {
        String reason = this.unavailableReason;
        if (reason == null || reason.isBlank()) {
            return "Optional ffmpeg backend is not available on this runtime.";
        }
        return "Optional ffmpeg backend is not available on this runtime. " + reason;
    }

    private static String resolveBinary(String binaryName, String environmentVariable) {
        String explicit = System.getenv(environmentVariable);
        String resolvedExplicit = resolveCandidate(explicit);
        if (resolvedExplicit != null) {
            return resolvedExplicit;
        }

        String path = System.getenv("PATH");
        if (path != null && !path.isBlank()) {
            String[] directories = path.split(File.pathSeparator);
            for (String directory : directories) {
                String resolved = resolveCandidate(new File(directory, binaryName).getAbsolutePath());
                if (resolved != null) {
                    return resolved;
                }
            }
        }

        for (String directory : COMMON_BINARY_DIRECTORIES) {
            String resolved = resolveCandidate(new File(directory, binaryName).getAbsolutePath());
            if (resolved != null) {
                return resolved;
            }
        }

        String resolvedWindowsExecutable = resolveCandidate(binaryName + ".exe");
        if (resolvedWindowsExecutable != null) {
            return resolvedWindowsExecutable;
        }

        return null;
    }

    private static String resolveCandidate(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        File file = new File(path);
        if (!file.isAbsolute() && !path.contains(File.separator)) {
            return null;
        }
        if (!file.isFile() || !file.canExecute()) {
            return null;
        }
        if (canRunVersionCommand(file.getAbsolutePath())) {
            return file.getAbsolutePath();
        }
        return file.getAbsolutePath();
    }

    private static boolean canRunVersionCommand(String binaryPath) {
        try {
            Process process = new ProcessBuilder(binaryPath, "-version").start();
            boolean finished = process.waitFor(TOOL_DETECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static ProcessResult runProcess(List<String> command, long timeoutMillis) throws IOException {
        Process process = new ProcessBuilder(command).start();
        StreamCollector stdoutCollector = new StreamCollector(process.getInputStream());
        StreamCollector stderrCollector = new StreamCollector(process.getErrorStream());
        stdoutCollector.start();
        stderrCollector.start();

        try {
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Timed out while running " + command.get(0));
            }
            stdoutCollector.join();
            stderrCollector.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted while running " + command.get(0), exception);
        }

        return new ProcessResult(
                process.exitValue(),
                new String(stdoutCollector.bytes, StandardCharsets.UTF_8),
                new String(stderrCollector.bytes, StandardCharsets.UTF_8),
                stdoutCollector.bytes
        );
    }

    private static long selectPosterTimestampMillis(VideoMetadata metadata) {
        if (metadata == null || metadata.durationMillis() <= 0L) {
            return 0L;
        }
        return Math.min(1_000L, Math.max(0L, metadata.durationMillis() / 4L));
    }

    private static String formatSeekSeconds(long millis) {
        return String.format(Locale.ROOT, "%.3f", Math.max(0L, millis) / 1000.0D);
    }

    private static String normalizeContainer(String rawContainer) {
        if (rawContainer == null || rawContainer.isBlank()) {
            return "unknown";
        }
        int comma = rawContainer.indexOf(',');
        String normalized = comma >= 0 ? rawContainer.substring(0, comma) : rawContainer;
        return normalized.trim().toLowerCase(Locale.ROOT);
    }

    private static long parseDurationMillis(String rawDuration) {
        if (rawDuration == null || rawDuration.isBlank() || "N/A".equalsIgnoreCase(rawDuration)) {
            return -1L;
        }
        try {
            return Math.max(0L, Math.round(Double.parseDouble(rawDuration) * 1000.0D));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static double parseFrameRate(String rawFrameRate) {
        if (rawFrameRate == null || rawFrameRate.isBlank() || "0/0".equals(rawFrameRate)) {
            return 0.0D;
        }
        int slashIndex = rawFrameRate.indexOf('/');
        if (slashIndex > 0 && slashIndex < rawFrameRate.length() - 1) {
            try {
                double numerator = Double.parseDouble(rawFrameRate.substring(0, slashIndex));
                double denominator = Double.parseDouble(rawFrameRate.substring(slashIndex + 1));
                return denominator == 0.0D ? 0.0D : numerator / denominator;
            } catch (NumberFormatException ignored) {
                return 0.0D;
            }
        }
        try {
            return Double.parseDouble(rawFrameRate);
        } catch (NumberFormatException ignored) {
            return 0.0D;
        }
    }

    private static int readInt(JsonObject object, String memberName, int fallback) {
        if (object == null || !object.has(memberName)) {
            return fallback;
        }
        try {
            return object.get(memberName).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String readString(JsonObject object, String memberName, String fallback) {
        if (object == null || !object.has(memberName)) {
            return fallback;
        }
        try {
            return object.get(memberName).getAsString();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String sanitizeMessage(String rawMessage, String fallback) {
        if (rawMessage == null) {
            return fallback;
        }
        String trimmed = rawMessage.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "unknown";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex >= fileName.length() - 1) {
            return "unknown";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private record ProcessResult(int exitCode, String stdout, String stderr, byte[] stdoutBytes) {
    }

    private static final class StreamCollector extends Thread {
        private final InputStream stream;
        private byte[] bytes = new byte[0];

        private StreamCollector(InputStream stream) {
            this.stream = stream;
            setName("koil-media-process-stream");
            setDaemon(true);
        }

        @Override
        public void run() {
            try (InputStream input = this.stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                this.bytes = output.toByteArray();
            } catch (IOException ignored) {
                this.bytes = new byte[0];
            }
        }
    }
}
