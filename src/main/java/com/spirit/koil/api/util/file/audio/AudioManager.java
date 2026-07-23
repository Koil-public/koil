package com.spirit.koil.api.util.file.audio;

import net.minecraft.client.sound.OggAudioStream;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static com.spirit.Main.SUBLOGGER;

public class AudioManager {
    private static final int OGG_BUFFER_SIZE = 4096;
    private static Clip currentClip;
    private static String currentClipFilePath;
    private static Thread oggPlaybackThread;
    private static long pausedPosition = 0;
    private static boolean isPlaying = false;
    private static byte[] currentOggData;
    private static AudioFormat currentOggFormat;
    private static String currentOggFilePath;
    private static String currentAudioFilePath;
    private static int currentOggPosition = 0;
    private static boolean currentOggLoop = false;
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
    private static final Set<String> RECOGNIZED_AUDIO_EXTENSIONS = Set.of(
            "wav", "ogg", "mp3", "flac", "aac", "m4a", "wma", "aiff", "aif", "opus"
    );
    private static volatile Boolean ffmpegAvailable;
    private static volatile long ffmpegAvailabilityCheckedAt;
    private static volatile String ffmpegBinary;
    private static volatile String ffprobeBinary;
    private static volatile String ffmpegUnavailableReason = "ffmpeg audio backend has not been resolved yet.";
    private static Thread externalPlaybackThread;
    private static Process externalPlaybackProcess;
    private static boolean externalPlaybackActive;
    private static boolean externalPaused;
    private static boolean externalLoop;
    private static float externalVolume = 1.0F;
    private static String externalAudioFilePath;
    private static long externalDurationMicros;
    private static long externalSeekStartMicros;
    private static long externalPlaybackStartNanos;

    public static void playAudio(File selectedFile, boolean loop, float volume) {
        String fileName = selectedFile.getName().toLowerCase();
        String selectedPath = selectedFile.getAbsolutePath();
        try {
            if (currentAudioFilePath != null && !selectedPath.equals(currentAudioFilePath)) {
                stopAllAudio();
            }
            if (fileName.endsWith(".wav")) {
                playWavFile(selectedFile, loop);
            } else if (fileName.endsWith(".ogg")) {
                playOggFile(selectedFile.getPath(), loop, volume);
            } else if (isRecognizedAudioFile(selectedFile)) {
                playExternalAudioFile(selectedFile, loop, volume, 0L);
            } else {
                SUBLOGGER.logW("Audio thread", "Unsupported audio file format: " + fileName);
            }
        } catch (Exception e) {
            SUBLOGGER.logE("Audio thread", "Error playing audio file: " + e.getMessage());
        }
    }

    public static boolean isRecognizedAudioFile(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String name = file.getName();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex >= name.length() - 1) {
            return false;
        }
        return RECOGNIZED_AUDIO_EXTENSIONS.contains(name.substring(dotIndex + 1).toLowerCase(Locale.ROOT));
    }

    private static void playWavFile(File selectedFile, boolean loop) throws Exception {
        currentClipFilePath = selectedFile.getAbsolutePath();
        currentAudioFilePath = currentClipFilePath;
        if (currentClip == null) {
            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(selectedFile);
            currentClip = AudioSystem.getClip();
            currentClip.open(audioInputStream);
            currentClip.start();
            if (loop) {
                currentClip.loop(Clip.LOOP_CONTINUOUSLY);
            }
            SUBLOGGER.logI("Audio thread", "WAV playback started: " + selectedFile.getName());
        } else if (pausedPosition > 0) {
            currentClip.setMicrosecondPosition(pausedPosition);
            currentClip.start();
            SUBLOGGER.logI("Audio thread", "WAV playback resumed: " + selectedFile.getName());
            pausedPosition = 0;
        } else {
            currentClip.setMicrosecondPosition(0);
            currentClip.start();
            if (loop) {
                currentClip.loop(Clip.LOOP_CONTINUOUSLY);
            }
            SUBLOGGER.logI("Audio thread", "WAV playback restarted: " + selectedFile.getName());
        }
    }

    public static synchronized void playOggFile(String filePath, boolean loop, float volume) {
        if (isPlaying) {
            return;
        }

        if (currentClip != null) {
            stopAllAudio();
        }
        stopExternalPlayback();

        if (!filePath.equals(currentOggFilePath) || currentOggData == null) {
            try {
                loadOggData(filePath);
            } catch (IOException e) {
                SUBLOGGER.logE("Audio thread", "Error loading OGG file: " + e.getMessage());
                return;
            }
        }

        if (currentOggData == null || currentOggData.length == 0 || currentOggFormat == null) {
            return;
        }

        if (currentOggPosition >= currentOggData.length) {
            currentOggPosition = 0;
        }

        currentOggLoop = loop;
        currentOggFilePath = new File(filePath).getAbsolutePath();
        currentAudioFilePath = currentOggFilePath;
        startOggPlayback(volume);
    }

    private static void startOggPlayback(float volume) {
        isPlaying = true;

        oggPlaybackThread = new Thread(() -> {
            try {
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, currentOggFormat);
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);

                line.open(currentOggFormat);
                line.start();

                if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl volumeControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                    float min = volumeControl.getMinimum();
                    float max = volumeControl.getMaximum();
                    float clampedVolume = Math.max(min, Math.min(max, volume));
                    volumeControl.setValue(clampedVolume);
                } else {
                    SUBLOGGER.logE("Audio thread", "Volume control not supported on this audio line.");
                }

                while (isPlaying) {
                    if (currentOggPosition >= currentOggData.length) {
                        if (currentOggLoop) {
                            currentOggPosition = 0;
                        } else {
                            break;
                        }
                    }

                    int remaining = currentOggData.length - currentOggPosition;
                    int chunkSize = Math.min(OGG_BUFFER_SIZE, remaining);
                    line.write(currentOggData, currentOggPosition, chunkSize);
                    currentOggPosition += chunkSize;
                }

                line.drain();
                line.stop();
                line.close();

            } catch (Exception e) {
                SUBLOGGER.logE("Audio thread", "Error playing OGG file: " + e.getMessage());
            } finally {
                isPlaying = false;
                oggPlaybackThread = null;
            }
        });

        oggPlaybackThread.start();
    }

    public static synchronized void stopOggPlayback() {
        isPlaying = false;
        if (oggPlaybackThread != null) {
            try {
                oggPlaybackThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            oggPlaybackThread = null;
        }
    }

    public static void pauseAudio() {
        if (currentClip != null && currentClip.isRunning()) {
            pausedPosition = currentClip.getMicrosecondPosition();
            currentClip.stop();
            SUBLOGGER.logI("Audio thread", "Audio playback paused.");
        } else if (isPlaying) {
            stopOggPlayback();
            SUBLOGGER.logI("Audio thread", "OGG playback paused.");
        } else if (externalPlaybackActive) {
            externalSeekStartMicros = getExternalPlaybackPositionMicros();
            pauseExternalPlayback();
            externalPaused = true;
            SUBLOGGER.logI("Audio thread", "External audio playback paused.");
        }
    }

    public static void stopAllAudio() {
        if (currentClip != null) {
            currentClip.stop();
            currentClip.close();
            currentClip = null;
            currentClipFilePath = null;
            pausedPosition = 0;
        }
        stopOggPlayback();
        stopExternalPlayback();
        currentOggPosition = 0;
        currentAudioFilePath = null;
    }

    public static float getClipProgress() {
        if (currentClip != null && currentClip.isOpen() && currentClip.getMicrosecondLength() > 0) {
            return (float) currentClip.getMicrosecondPosition() / currentClip.getMicrosecondLength();
        }
        return 0;
    }

    public static float getPlaybackProgress() {
        if (currentClip != null) {
            return getClipProgress();
        }
        if (currentOggData != null && currentOggData.length > 0) {
            return Math.min(1.0F, currentOggPosition / (float) currentOggData.length);
        }
        if (externalAudioFilePath != null && externalDurationMicros > 0) {
            return Math.min(1.0F, getExternalPlaybackPositionMicros() / (float) externalDurationMicros);
        }
        return 0;
    }

    public static float getPlaybackProgress(File file) {
        if (isCurrentClipFile(file) && currentClip != null && currentClip.isOpen() && currentClip.getMicrosecondLength() > 0) {
            return (float) currentClip.getMicrosecondPosition() / currentClip.getMicrosecondLength();
        }
        if (isCurrentOggFile(file) && currentOggData != null && currentOggData.length > 0) {
            return Math.min(1.0F, currentOggPosition / (float) currentOggData.length);
        }
        if (isCurrentExternalFile(file) && externalDurationMicros > 0) {
            return Math.min(1.0F, getExternalPlaybackPositionMicros() / (float) externalDurationMicros);
        }
        return 0.0F;
    }

    public static long getPlaybackPositionMicros() {
        if (currentClip != null && currentClip.isOpen()) {
            return currentClip.getMicrosecondPosition();
        }
        if (externalAudioFilePath != null) {
            return getExternalPlaybackPositionMicros();
        }
        return getOggMicrosForPosition(currentOggPosition);
    }

    public static long getPlaybackPositionMicros(File file) {
        if (isCurrentClipFile(file) && currentClip != null && currentClip.isOpen()) {
            return currentClip.getMicrosecondPosition();
        }
        if (isCurrentExternalFile(file)) {
            return getExternalPlaybackPositionMicros();
        }
        if (isCurrentOggFile(file)) {
            return getOggMicrosForPosition(currentOggPosition);
        }
        return 0L;
    }

    public static long getPlaybackLengthMicros() {
        if (currentClip != null && currentClip.isOpen()) {
            return currentClip.getMicrosecondLength();
        }
        if (currentOggData != null) {
            return getOggMicrosForPosition(currentOggData.length);
        }
        if (externalAudioFilePath != null) {
            return externalDurationMicros;
        }
        return 0;
    }

    public static long getPlaybackLengthMicros(File file) {
        if (isCurrentClipFile(file) && currentClip != null && currentClip.isOpen()) {
            return currentClip.getMicrosecondLength();
        }
        if (isCurrentOggFile(file) && currentOggData != null) {
            return getOggMicrosForPosition(currentOggData.length);
        }
        if (isCurrentExternalFile(file)) {
            return externalDurationMicros;
        }
        return 0L;
    }

    public static boolean canSeekPlayback() {
        return (currentClip != null && currentClip.isOpen()) || (currentOggData != null && currentOggData.length > 0) || externalAudioFilePath != null;
    }

    public static boolean canSeekPlayback(File file) {
        return (isCurrentClipFile(file) && currentClip != null && currentClip.isOpen())
                || (isCurrentOggFile(file) && currentOggData != null && currentOggData.length > 0)
                || (isCurrentExternalFile(file) && externalAudioFilePath != null);
    }

    public static synchronized void seekToProgress(float progress, float volume) {
        float clampedProgress = Math.max(0.0F, Math.min(1.0F, progress));

        if (currentClip != null && currentClip.isOpen()) {
            long newPosition = (long) (currentClip.getMicrosecondLength() * clampedProgress);
            boolean resumeAfterSeek = currentClip.isRunning();
            currentClip.setMicrosecondPosition(newPosition);
            pausedPosition = resumeAfterSeek ? 0 : newPosition;
            if (resumeAfterSeek) {
                currentClip.start();
            }
            return;
        }

        if (currentOggData == null || currentOggData.length == 0) {
            if (externalAudioFilePath != null && externalDurationMicros > 0) {
                long newPosition = (long) (externalDurationMicros * clampedProgress);
                File file = new File(externalAudioFilePath);
                boolean resumeAfterSeek = externalPlaybackActive;
                stopExternalPlayback(true);
                externalSeekStartMicros = newPosition;
                externalPaused = !resumeAfterSeek;
                if (resumeAfterSeek) {
                    playExternalAudioFile(file, externalLoop, volume, newPosition);
                }
            }
            return;
        }

        int frameSize = Math.max(1, currentOggFormat != null ? currentOggFormat.getFrameSize() : 1);
        int newPosition = (int) (currentOggData.length * clampedProgress);
        currentOggPosition = Math.max(0, Math.min(currentOggData.length, newPosition - (newPosition % frameSize)));

        if (isPlaying) {
            stopOggPlayback();
            startOggPlayback(volume);
        }
    }

    public static synchronized boolean seekToProgress(File file, float progress, float volume) {
        if (file == null || !isCurrentAudioFile(file)) {
            return false;
        }
        float clampedProgress = Math.max(0.0F, Math.min(1.0F, progress));

        if (isCurrentClipFile(file) && currentClip != null && currentClip.isOpen()) {
            long newPosition = (long) (currentClip.getMicrosecondLength() * clampedProgress);
            boolean resumeAfterSeek = currentClip.isRunning();
            currentClip.setMicrosecondPosition(newPosition);
            pausedPosition = resumeAfterSeek ? 0 : newPosition;
            if (resumeAfterSeek) {
                currentClip.start();
            }
            return true;
        }

        if (isCurrentExternalFile(file) && externalDurationMicros > 0) {
            long newPosition = (long) (externalDurationMicros * clampedProgress);
            File selected = new File(externalAudioFilePath);
            boolean resumeAfterSeek = externalPlaybackActive;
            stopExternalPlayback(true);
            externalSeekStartMicros = newPosition;
            externalPaused = !resumeAfterSeek;
            if (resumeAfterSeek) {
                playExternalAudioFile(selected, externalLoop, volume, newPosition);
            }
            return true;
        }

        if (isCurrentOggFile(file) && currentOggData != null && currentOggData.length > 0) {
            int frameSize = Math.max(1, currentOggFormat != null ? currentOggFormat.getFrameSize() : 1);
            int newPosition = (int) (currentOggData.length * clampedProgress);
            currentOggPosition = Math.max(0, Math.min(currentOggData.length, newPosition - (newPosition % frameSize)));

            if (isPlaying) {
                stopOggPlayback();
                startOggPlayback(volume);
            }
            return true;
        }
        return false;
    }

    public static Clip getCurrentClip() {
        return currentClip;
    }

    public static boolean hasActiveAudio() {
        return currentClip != null || currentAudioFilePath != null || externalAudioFilePath != null;
    }

    public static File currentAudioFile() {
        return currentAudioFilePath == null || currentAudioFilePath.isBlank() ? null : new File(currentAudioFilePath);
    }

    public static boolean isAudioPlaying() {
        return (currentClip != null && currentClip.isRunning()) || isPlaying || externalPlaybackActive;
    }

    public static boolean isCurrentAudioFile(File file) {
        return currentAudioFilePath != null && file != null && currentAudioFilePath.equals(file.getAbsolutePath());
    }

    private static boolean isCurrentClipFile(File file) {
        return matchesFile(currentClipFilePath, file);
    }

    private static boolean isCurrentOggFile(File file) {
        return matchesFile(currentOggFilePath, file);
    }

    private static boolean isCurrentExternalFile(File file) {
        return matchesFile(externalAudioFilePath, file);
    }

    private static boolean matchesFile(String path, File file) {
        return path != null && file != null && path.equals(file.getAbsolutePath());
    }

    private static synchronized void playExternalAudioFile(File selectedFile, boolean loop, float volume, long seekMicros) {
        if (!isExternalAudioBackendAvailable()) {
            currentAudioFilePath = null;
            externalAudioFilePath = null;
            externalPlaybackActive = false;
            SUBLOGGER.logW("Audio thread", "External audio playback unavailable: " + ffmpegUnavailableReason);
            return;
        }
        stopOggPlayback();
        if (currentClip != null) {
            currentClip.stop();
            currentClip.close();
            currentClip = null;
            currentClipFilePath = null;
        }
        String selectedPath = selectedFile.getAbsolutePath();
        boolean samePausedExternalFile = externalPaused && !externalPlaybackActive && selectedPath.equals(externalAudioFilePath);
        if (!samePausedExternalFile) {
            stopExternalPlayback();
        }

        externalAudioFilePath = selectedPath;
        currentAudioFilePath = externalAudioFilePath;
        externalLoop = loop;
        externalVolume = volume;
        externalDurationMicros = probeExternalDurationMicros(selectedFile);
        externalSeekStartMicros = seekMicros > 0L ? seekMicros : (samePausedExternalFile ? externalSeekStartMicros : 0L);
        externalPaused = false;
        startExternalPlayback(selectedFile);
    }

    private static synchronized void startExternalPlayback(File selectedFile) {
        String resolvedFfmpeg = ffmpegBinary;
        if (resolvedFfmpeg == null || resolvedFfmpeg.isBlank()) {
            SUBLOGGER.logW("Audio thread", "External audio playback unavailable: ffmpeg was not resolved.");
            return;
        }

        List<String> command = new ArrayList<>();
        command.add(resolvedFfmpeg);
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");
        command.add("-threads");
        command.add("1");
        command.add("-i");
        command.add(selectedFile.getAbsolutePath());
        if (externalSeekStartMicros > 0) {
            command.add("-ss");
            command.add(String.valueOf(externalSeekStartMicros / 1_000_000.0D));
        }
        command.add("-vn");
        command.add("-f");
        command.add("s16le");
        command.add("-acodec");
        command.add("pcm_s16le");
        command.add("-ac");
        command.add("2");
        command.add("-ar");
        command.add("44100");
        command.add("pipe:1");

        externalPlaybackThread = new Thread(() -> runExternalPlaybackLoop(command, selectedFile), "koil-audio-ffmpeg");
        externalPlaybackThread.setDaemon(true);
        externalPlaybackActive = true;
        externalPlaybackStartNanos = System.nanoTime();
        externalPlaybackThread.start();
    }

    private static void runExternalPlaybackLoop(List<String> command, File selectedFile) {
        do {
            Process playbackProcess = null;
            try {
                playbackProcess = new ProcessBuilder(command).start();
                externalPlaybackProcess = playbackProcess;
                drainProcessStream(playbackProcess.getErrorStream(), selectedFile.getName());
                AudioFormat format = new AudioFormat(44100.0F, 16, 2, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                try (InputStream stream = playbackProcess.getInputStream()) {
                    SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
                    line.open(format);
                    applyLineVolume(line, externalVolume);
                    line.start();
                    externalPlaybackActive = true;
                    externalPlaybackStartNanos = System.nanoTime();
                    byte[] buffer = new byte[16_384];
                    int read;
                    while (externalPlaybackActive && (read = stream.read(buffer)) >= 0) {
                        if (read > 0) {
                            line.write(buffer, 0, read);
                        }
                    }
                    line.drain();
                    line.stop();
                    line.close();
                }
                if (playbackProcess != null) {
                    try {
                        playbackProcess.waitFor(250L, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    playbackProcess.destroy();
                    if (externalPlaybackProcess == playbackProcess) {
                        externalPlaybackProcess = null;
                    }
                }
            } catch (Exception exception) {
                SUBLOGGER.logW("Audio thread", "External audio playback unavailable for " + selectedFile.getName() + ": " + exception.getMessage());
                break;
            } finally {
                if (playbackProcess != null && externalPlaybackProcess == playbackProcess && !playbackProcess.isAlive()) {
                    externalPlaybackProcess = null;
                }
            }
            if (externalPlaybackActive && externalLoop) {
                externalSeekStartMicros = 0L;
                externalPlaybackStartNanos = System.nanoTime();
            }
        } while (externalPlaybackActive && externalLoop);

        synchronized (AudioManager.class) {
            if (Thread.currentThread() != externalPlaybackThread) {
                return;
            }
            externalPlaybackActive = false;
            externalPlaybackThread = null;
            if (!externalLoop && !externalPaused) {
                externalSeekStartMicros = externalDurationMicros;
                externalPlaybackStartNanos = 0L;
            }
        }
    }

    private static void drainProcessStream(InputStream stream, String fileName) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        SUBLOGGER.logW("Audio thread", "ffmpeg audio " + fileName + ": " + line);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "koil-audio-ffmpeg-log");
        thread.setDaemon(true);
        thread.start();
    }

    private static void applyLineVolume(SourceDataLine line, float volume) {
        if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        FloatControl control = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
        float clamped = Math.max(0.0001F, Math.min(1.0F, volume));
        float gain = (float) (20.0D * Math.log10(clamped));
        control.setValue(Math.max(control.getMinimum(), Math.min(control.getMaximum(), gain)));
    }

    private static synchronized void stopExternalPlayback() {
        stopExternalPlayback(false);
    }

    private static synchronized void pauseExternalPlayback() {
        stopExternalPlayback(true);
    }

    private static synchronized void stopExternalPlayback(boolean preserveResumeState) {
        String resumePath = externalAudioFilePath;
        long resumeDuration = externalDurationMicros;
        long resumeSeek = externalSeekStartMicros;
        boolean resumeLoop = externalLoop;
        float resumeVolume = externalVolume;
        externalPlaybackActive = false;
        if (externalPlaybackProcess != null) {
            externalPlaybackProcess.destroy();
            externalPlaybackProcess = null;
        }
        if (externalPlaybackThread != null && externalPlaybackThread != Thread.currentThread()) {
            try {
                externalPlaybackThread.join(500L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        externalPlaybackThread = null;
        if (preserveResumeState) {
            externalAudioFilePath = resumePath;
            currentAudioFilePath = resumePath;
            externalDurationMicros = resumeDuration;
            externalSeekStartMicros = resumeSeek;
            externalLoop = resumeLoop;
            externalVolume = resumeVolume;
        } else {
            externalAudioFilePath = null;
            externalSeekStartMicros = 0L;
            externalDurationMicros = 0L;
            externalPaused = false;
        }
        externalPlaybackStartNanos = 0L;
    }

    private static long getExternalPlaybackPositionMicros() {
        if (externalPlaybackStartNanos <= 0L) {
            return externalSeekStartMicros;
        }
        long elapsedMicros = (System.nanoTime() - externalPlaybackStartNanos) / 1_000L;
        long position = externalSeekStartMicros + elapsedMicros;
        return externalDurationMicros > 0 ? Math.min(position, externalDurationMicros) : position;
    }

    private static long probeExternalDurationMicros(File selectedFile) {
        String resolvedFfprobe = ffprobeBinary;
        if ((resolvedFfprobe == null || resolvedFfprobe.isBlank()) && !isExternalAudioBackendAvailable()) {
            return 0L;
        }
        resolvedFfprobe = ffprobeBinary;
        if (resolvedFfprobe == null || resolvedFfprobe.isBlank()) {
            return 0L;
        }
        try {
            Process process = new ProcessBuilder(
                    resolvedFfprobe,
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    selectedFile.getAbsolutePath()
            ).redirectErrorStream(true).start();
            String value;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                value = reader.readLine();
            }
            process.waitFor();
            if (value != null && !value.isBlank()) {
                return (long) (Double.parseDouble(value.trim()) * 1_000_000L);
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private static boolean isExternalAudioBackendAvailable() {
        Boolean cached = ffmpegAvailable;
        long now = System.currentTimeMillis();
        if (cached != null && (cached || now - ffmpegAvailabilityCheckedAt < FAILED_DISCOVERY_RETRY_MS)) {
            return cached;
        }
        ffprobeBinary = resolveBinary("ffprobe", "KOIL_FFPROBE_PATH");
        ffmpegBinary = resolveBinary("ffmpeg", "KOIL_FFMPEG_PATH");
        boolean detected = ffprobeBinary != null && ffmpegBinary != null;
        ffmpegUnavailableReason = detected
                ? ""
                : "ffmpeg binaries were not resolved. Expected ffmpeg and ffprobe on PATH, KOIL_FFMPEG_PATH/KOIL_FFPROBE_PATH, or common install locations.";
        ffmpegAvailabilityCheckedAt = now;
        ffmpegAvailable = detected;
        return detected;
    }

    private static String resolveBinary(String executable, String environmentKey) {
        String explicitPath = System.getenv(environmentKey);
        if (isExecutableBinary(explicitPath, executable)) {
            return explicitPath;
        }

        String path = System.getenv("PATH");
        if (path != null) {
            String[] entries = path.split(File.pathSeparator);
            for (String entry : entries) {
                String candidate = new File(entry, executable).getAbsolutePath();
                if (isExecutableBinary(candidate, executable)) {
                    return candidate;
                }
            }
        }

        for (String directory : COMMON_BINARY_DIRECTORIES) {
            String candidate = new File(directory, executable).getAbsolutePath();
            if (isExecutableBinary(candidate, executable)) {
                return candidate;
            }
        }

        if (isExecutableBinary(executable, executable)) {
            return executable;
        }
        return null;
    }

    private static boolean isExecutableBinary(String path, String executable) {
        if (path == null || path.isBlank()) {
            return false;
        }
        File file = new File(path);
        if (path.contains(File.separator) && (!file.exists() || file.isDirectory())) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(path, "-version").start();
            boolean completed = process.waitFor(TOOL_DETECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void loadOggData(String filePath) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath); OggAudioStream oggStream = new OggAudioStream(fis); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            currentOggFormat = oggStream.getFormat();
            currentOggFilePath = filePath;
            currentOggPosition = 0;

            ByteBuffer buffer = oggStream.getBuffer(OGG_BUFFER_SIZE);
            while (buffer.hasRemaining()) {
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                output.write(data);
                buffer = oggStream.getBuffer(OGG_BUFFER_SIZE);
            }

            currentOggData = output.toByteArray();
        }
    }

    private static long getOggMicrosForPosition(int position) {
        if (currentOggFormat == null) {
            return 0;
        }

        float frameRate = currentOggFormat.getFrameRate();
        int frameSize = currentOggFormat.getFrameSize();
        if (frameRate <= 0 || frameSize <= 0) {
            return 0;
        }

        return (long) ((position / (frameRate * frameSize)) * 1_000_000L);
    }
}
