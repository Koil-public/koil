package com.spirit.koil.api.util.file.media.video;

import com.spirit.koil.api.util.file.media.ManagedFrameTexture;
import com.spirit.koil.api.util.file.media.MediaPerformanceProfile;
import com.spirit.koil.api.util.file.media.VisualPlaybackState;
import com.spirit.koil.api.util.file.media.audio.PcmAudioPlayback;
import com.spirit.koil.api.util.file.media.image.ImageDecoder;
import net.minecraft.util.Identifier;

import javax.sound.sampled.AudioFormat;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class ExternalFfmpegVideoPlaybackSession implements VideoPlaybackSession {
    private final File file;
    private final VideoMetadata metadata;
    private final ExternalFfmpegVideoBackend backend;
    private final ManagedFrameTexture texture;
    private final PcmAudioPlayback audioPlayback;
    private final int frameWidth;
    private final int frameHeight;
    private final double playbackFps;
    private final long frameDurationMillis;
    private final Object decoderLock = new Object();

    private volatile VisualPlaybackState state = VisualPlaybackState.READY;
    private volatile String failureReason;
    private volatile String audioWarningReason;
    private volatile long positionMillis;
    private volatile long playbackAnchorSystemMillis;
    private volatile long playbackAnchorMediaMillis;
    private volatile boolean decoderStopRequested;
    private volatile boolean decoderCompleted;
    private volatile DecodedFrame queuedFrame;
    private volatile Process decoderProcess;
    private volatile Thread decoderThread;
    private volatile Thread stderrThread;
    private volatile String lastDecoderError = "";
    private volatile long decoderStartMillis;
    private volatile long decoderFrameIndex;
    private volatile Process audioProcess;

    ExternalFfmpegVideoPlaybackSession(File file, VideoMetadata metadata, ExternalFfmpegVideoBackend backend, int maxWidth, int maxHeight) throws IOException {
        this.file = file;
        this.metadata = metadata;
        this.backend = backend;
        int[] targetSize = resolveTargetFrameSize(metadata, maxWidth, maxHeight);
        this.frameWidth = targetSize[0];
        this.frameHeight = targetSize[1];
        this.playbackFps = resolvePlaybackFps(metadata == null ? 0.0D : metadata.frameRate());
        this.frameDurationMillis = Math.max(1L, Math.round(1000.0D / this.playbackFps));
        this.texture = new ManagedFrameTexture("koil", "video_player/" + file.getAbsolutePath() + "_" + this.frameWidth + "x" + this.frameHeight, this.frameWidth, this.frameHeight);
        this.audioPlayback = new PcmAudioPlayback(
                new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        MediaPerformanceProfile.VIDEO_AUDIO_SAMPLE_RATE,
                        MediaPerformanceProfile.VIDEO_AUDIO_SAMPLE_BYTES * 8,
                        MediaPerformanceProfile.VIDEO_AUDIO_CHANNELS,
                        MediaPerformanceProfile.VIDEO_AUDIO_CHANNELS * MediaPerformanceProfile.VIDEO_AUDIO_SAMPLE_BYTES,
                        MediaPerformanceProfile.VIDEO_AUDIO_SAMPLE_RATE,
                        false
                ),
                MediaPerformanceProfile.VIDEO_AUDIO_STREAM_BUFFER_BYTES
        );
        loadStillFrame(0L);
    }

    private static int[] resolveTargetFrameSize(VideoMetadata metadata, int maxWidth, int maxHeight) {
        int widthCap = Math.max(1, Math.min(maxWidth, MediaPerformanceProfile.VIDEO_PLAYBACK_MAX_WIDTH));
        int heightCap = Math.max(1, Math.min(maxHeight, MediaPerformanceProfile.VIDEO_PLAYBACK_MAX_HEIGHT));
        if (metadata == null || metadata.width() <= 0 || metadata.height() <= 0) {
            return new int[]{widthCap, heightCap};
        }
        float scale = Math.min(widthCap / (float) metadata.width(), heightCap / (float) metadata.height());
        if (scale <= 0.0F || Float.isNaN(scale) || Float.isInfinite(scale)) {
            scale = 1.0F;
        }
        int scaledWidth = Math.max(1, Math.min(widthCap, Math.round(metadata.width() * scale)));
        int scaledHeight = Math.max(1, Math.min(heightCap, Math.round(metadata.height() * scale)));
        return new int[]{scaledWidth, scaledHeight};
    }

    @Override
    public VideoMetadata metadata() {
        return this.metadata;
    }

    @Override
    public VisualPlaybackState state() {
        return this.state;
    }

    @Override
    public void play() {
        if (this.state == VisualPlaybackState.FAILED) {
            return;
        }
        if (durationMillis() > 0L && this.positionMillis >= durationMillis()) {
            this.positionMillis = 0L;
            loadStillFrame(0L);
        }
        startPlaybackAt(this.positionMillis);
    }

    @Override
    public void pause() {
        if (this.state != VisualPlaybackState.PLAYING) {
            return;
        }
        this.positionMillis = computeCurrentPosition(System.currentTimeMillis());
        stopPlaybackPipelines();
        this.state = VisualPlaybackState.PAUSED;
    }

    @Override
    public void stop() {
        stopPlaybackPipelines();
        this.positionMillis = 0L;
        loadStillFrame(0L);
        this.state = VisualPlaybackState.READY;
    }

    @Override
    public void seekTo(long targetMillis) {
        long clamped = Math.max(0L, Math.min(durationMillis(), targetMillis));
        boolean wasPlaying = this.state == VisualPlaybackState.PLAYING;
        stopPlaybackPipelines();
        this.positionMillis = clamped;
        if (wasPlaying) {
            startPlaybackAt(clamped);
        } else {
            this.state = VisualPlaybackState.PAUSED;
            loadStillFrame(clamped);
        }
    }

    @Override
    public void update(long nowMillis) {
        if (this.state == VisualPlaybackState.PLAYING) {
            this.positionMillis = computeCurrentPosition(nowMillis);
            String audioFailure = this.audioPlayback.failureReason();
            if (audioFailure != null && !audioFailure.isBlank()) {
                this.audioWarningReason = audioFailure;
            }
            if (this.positionMillis >= durationMillis() && this.decoderCompleted && this.queuedFrame == null) {
                this.positionMillis = durationMillis();
                this.state = VisualPlaybackState.ENDED;
                stopPlaybackPipelines();
            }
        }

        DecodedFrame frame = this.queuedFrame;
        if (frame != null && frame.timestampMillis <= positionMillis() + Math.max(24L, this.frameDurationMillis)) {
            this.texture.updateRgbaFrame(frame.rgbaBytes);
            synchronized (this.decoderLock) {
                if (this.queuedFrame == frame) {
                    this.queuedFrame = null;
                    this.decoderLock.notifyAll();
                }
            }
        }
    }

    @Override
    public Identifier currentFrameTexture() {
        return this.texture.textureId();
    }

    @Override
    public String failureReason() {
        return this.failureReason;
    }

    @Override
    public String statusMessage() {
        if (this.failureReason != null && !this.failureReason.isBlank()) {
            return this.failureReason;
        }
        if (this.audioWarningReason == null || this.audioWarningReason.isBlank()) {
            return "";
        }
        return switch (this.state) {
            case PLAYING -> "Playing (video only): " + this.audioWarningReason;
            case PAUSED -> "Paused (video only): " + this.audioWarningReason;
            case READY -> "Ready (video only): " + this.audioWarningReason;
            case ENDED -> "Ended (video only): " + this.audioWarningReason;
            default -> this.audioWarningReason;
        };
    }

    @Override
    public long positionMillis() {
        return this.state == VisualPlaybackState.PLAYING ? computeCurrentPosition(System.currentTimeMillis()) : this.positionMillis;
    }

    @Override
    public long durationMillis() {
        return Math.max(0L, this.metadata == null ? 0L : this.metadata.durationMillis());
    }

    @Override
    public boolean canSeek() {
        return durationMillis() > 0L;
    }

    @Override
    public int frameWidth() {
        return this.frameWidth;
    }

    @Override
    public int frameHeight() {
        return this.frameHeight;
    }

    @Override
    public void close() {
        stopPlaybackPipelines();
        this.audioPlayback.close();
        this.texture.close();
    }

    private void startPlaybackAt(long startMillis) {
        this.playbackAnchorMediaMillis = startMillis;
        this.playbackAnchorSystemMillis = System.currentTimeMillis();
        this.state = VisualPlaybackState.PLAYING;
        startDecoder(startMillis);
        startAudio(startMillis);
    }

    private void startDecoder(long startMillis) {
        synchronized (this.decoderLock) {
            stopDecoderLocked();
            this.decoderCompleted = false;
            this.decoderStopRequested = false;
            this.decoderStartMillis = startMillis;
            this.decoderFrameIndex = 0L;
            this.lastDecoderError = "";
            try {
                Process process = this.backend.startRawFrameStream(this.file, startMillis, this.frameWidth, this.frameHeight, this.playbackFps);
                this.decoderProcess = process;
                this.stderrThread = startErrorCollector(process.getErrorStream());
                this.decoderThread = new Thread(() -> decodeLoop(process), "koil-video-decode");
                this.decoderThread.setDaemon(true);
                this.decoderThread.start();
            } catch (IOException exception) {
                fail("Video decoder failed to start: " + exception.getMessage());
            }
        }
    }

    private void startAudio(long startMillis) {
        if (this.metadata == null || !this.metadata.hasAudio()) {
            this.audioWarningReason = null;
            return;
        }
        try {
            Process process = this.backend.startRawAudioStream(this.file, startMillis);
            this.audioProcess = process;
            this.audioPlayback.start(process, process.getInputStream());
            this.audioWarningReason = null;
        } catch (IOException exception) {
            this.audioWarningReason = "Container audio failed to start: " + exception.getMessage();
            Process process = this.audioProcess;
            if (process != null) {
                process.destroyForcibly();
            }
            this.audioProcess = null;
        }
    }

    private void stopPlaybackPipelines() {
        stopDecoder();
        stopAudio();
    }

    private void stopAudio() {
        this.audioPlayback.stop();
        Process process = this.audioProcess;
        if (process != null) {
            process.destroyForcibly();
        }
        this.audioProcess = null;
    }

    private void stopDecoder() {
        synchronized (this.decoderLock) {
            stopDecoderLocked();
        }
    }

    private void stopDecoderLocked() {
        this.decoderStopRequested = true;
        Process process = this.decoderProcess;
        if (process != null) {
            process.destroyForcibly();
        }
        Thread thread = this.decoderThread;
        if (thread != null && thread.isAlive() && thread != Thread.currentThread()) {
            try {
                thread.join(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        Thread errorThread = this.stderrThread;
        if (errorThread != null && errorThread.isAlive() && errorThread != Thread.currentThread()) {
            try {
                errorThread.join(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        this.decoderProcess = null;
        this.decoderThread = null;
        this.stderrThread = null;
        this.queuedFrame = null;
        this.decoderLock.notifyAll();
    }

    private void decodeLoop(Process process) {
        int frameSize = this.frameWidth * this.frameHeight * 4;
        try (InputStream inputStream = process.getInputStream()) {
            while (!this.decoderStopRequested) {
                byte[] bytes = readFully(inputStream, frameSize);
                if (bytes == null) {
                    break;
                }
                DecodedFrame frame = new DecodedFrame(this.decoderStartMillis + (this.decoderFrameIndex * this.frameDurationMillis), bytes);
                this.decoderFrameIndex++;
                synchronized (this.decoderLock) {
                    while (!this.decoderStopRequested && this.queuedFrame != null) {
                        this.decoderLock.wait(10L);
                    }
                    if (this.decoderStopRequested) {
                        break;
                    }
                    this.queuedFrame = frame;
                }
            }
            process.waitFor();
            if (!this.decoderStopRequested && process.exitValue() != 0 && !this.lastDecoderError.isBlank()) {
                fail(this.lastDecoderError);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            if (!this.decoderStopRequested) {
                fail("Video decode failed: " + exception.getMessage());
            }
        } finally {
            this.decoderCompleted = true;
            this.decoderProcess = null;
            this.decoderThread = null;
        }
    }

    private Thread startErrorCollector(InputStream errorStream) {
        Thread thread = new Thread(() -> {
            try (InputStream stream = errorStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                this.lastDecoderError = output.toString(StandardCharsets.UTF_8).trim();
            } catch (IOException ignored) {
                this.lastDecoderError = "";
            }
        }, "koil-video-decode-error");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private byte[] readFully(InputStream inputStream, int frameSize) throws IOException {
        byte[] bytes = new byte[frameSize];
        int offset = 0;
        while (offset < frameSize) {
            int read = inputStream.read(bytes, offset, frameSize - offset);
            if (read < 0) {
                return null;
            }
            offset += read;
        }
        return bytes;
    }

    private void loadStillFrame(long targetMillis) {
        try {
            byte[] stillFrame = this.backend.extractFrame(this.file, targetMillis, this.frameWidth, this.frameHeight);
            BufferedImage image = ImageDecoder.decodeBytes(stillFrame, this.file.getName() + "#frame");
            if (image != null) {
                this.texture.updateBufferedImage(image);
            }
        } catch (Exception exception) {
            this.failureReason = "Still-frame extraction failed: " + exception.getMessage();
        }
    }

    private long computeCurrentPosition(long nowMillis) {
        long computed = this.playbackAnchorMediaMillis + (nowMillis - this.playbackAnchorSystemMillis);
        if (this.metadata != null && this.metadata.hasAudio() && this.audioPlayback.isRunning()) {
            computed = this.playbackAnchorMediaMillis + Math.round(this.audioPlayback.playbackPositionMicros() / 1000.0D);
        }
        return Math.max(0L, Math.min(durationMillis(), computed));
    }

    private void fail(String message) {
        this.failureReason = message;
        this.state = VisualPlaybackState.FAILED;
        stopPlaybackPipelines();
    }

    private static double resolvePlaybackFps(double sourceFps) {
        if (sourceFps <= 0.0D) {
            return MediaPerformanceProfile.VIDEO_PLAYBACK_DEFAULT_FPS;
        }
        return Math.max(1.0D, Math.min(sourceFps, MediaPerformanceProfile.VIDEO_PLAYBACK_MAX_FPS));
    }

    private record DecodedFrame(long timestampMillis, byte[] rgbaBytes) {
    }
}
