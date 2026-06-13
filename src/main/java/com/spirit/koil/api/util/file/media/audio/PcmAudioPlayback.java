package com.spirit.koil.api.util.file.media.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.io.InputStream;

public final class PcmAudioPlayback implements AutoCloseable {
    private final AudioFormat format;
    private final int streamBufferBytes;

    private volatile SourceDataLine line;
    private volatile Process process;
    private volatile Thread playbackThread;
    private volatile boolean stopRequested;
    private volatile boolean completed;
    private volatile String failureReason;

    public PcmAudioPlayback(AudioFormat format, int streamBufferBytes) {
        this.format = format;
        this.streamBufferBytes = Math.max(1024, streamBufferBytes);
    }

    public synchronized void start(Process process, InputStream stream) throws IOException {
        stop();
        if (process == null || stream == null) {
            throw new IOException("Audio process stream is not available.");
        }

        SourceDataLine sourceLine;
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, this.format);
            sourceLine = (SourceDataLine) AudioSystem.getLine(info);
            sourceLine.open(this.format, this.streamBufferBytes);
        } catch (LineUnavailableException exception) {
            process.destroyForcibly();
            throw new IOException("Audio output line is not available: " + exception.getMessage(), exception);
        }

        this.stopRequested = false;
        this.completed = false;
        this.failureReason = null;
        this.process = process;
        this.line = sourceLine;
        this.line.start();
        applyNeutralGain(sourceLine);

        Thread thread = new Thread(() -> playbackLoop(stream), "koil-video-audio");
        thread.setDaemon(true);
        this.playbackThread = thread;
        thread.start();
    }

    public synchronized void stop() {
        this.stopRequested = true;

        Process activeProcess = this.process;
        if (activeProcess != null) {
            activeProcess.destroyForcibly();
        }

        Thread thread = this.playbackThread;
        if (thread != null && thread.isAlive() && thread != Thread.currentThread()) {
            try {
                thread.join(150L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        closeLine();
        this.process = null;
        this.playbackThread = null;
    }

    public boolean isRunning() {
        Thread thread = this.playbackThread;
        return thread != null && thread.isAlive() && !this.stopRequested;
    }

    public boolean completed() {
        return this.completed;
    }

    public String failureReason() {
        return this.failureReason;
    }

    public long playbackPositionMicros() {
        SourceDataLine activeLine = this.line;
        if (activeLine == null) {
            return 0L;
        }
        float frameRate = this.format.getFrameRate();
        if (frameRate <= 0.0F) {
            return 0L;
        }
        return Math.max(0L, Math.round((activeLine.getLongFramePosition() / frameRate) * 1_000_000.0D));
    }

    @Override
    public void close() {
        stop();
    }

    private void playbackLoop(InputStream stream) {
        try (InputStream input = stream) {
            byte[] buffer = new byte[this.streamBufferBytes];
            int read;
            while (!this.stopRequested && (read = input.read(buffer)) != -1) {
                SourceDataLine activeLine = this.line;
                if (activeLine == null) {
                    break;
                }
                int offset = 0;
                while (!this.stopRequested && offset < read) {
                    offset += activeLine.write(buffer, offset, read - offset);
                }
            }
            SourceDataLine activeLine = this.line;
            if (!this.stopRequested && activeLine != null) {
                activeLine.drain();
            }
        } catch (IOException exception) {
            if (!this.stopRequested) {
                this.failureReason = "Audio stream failed: " + exception.getMessage();
            }
        } finally {
            this.completed = true;
            closeLine();
            Process activeProcess = this.process;
            if (activeProcess != null) {
                activeProcess.destroyForcibly();
            }
            this.process = null;
            this.playbackThread = null;
        }
    }

    private void closeLine() {
        SourceDataLine activeLine = this.line;
        this.line = null;
        if (activeLine == null) {
            return;
        }
        try {
            activeLine.stop();
        } finally {
            activeLine.close();
        }
    }

    private static void applyNeutralGain(SourceDataLine line) {
        if (line != null && line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), 0.0F)));
        }
    }
}
