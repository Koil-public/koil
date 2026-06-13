package com.spirit.koil.api.util.file.media.video;

import com.spirit.koil.api.util.file.media.VisualPlaybackState;
import net.minecraft.util.Identifier;

public final class UnsupportedVideoPlaybackSession implements VideoPlaybackSession {
    private final VideoMetadata metadata;
    private final String failureReason;

    public UnsupportedVideoPlaybackSession(VideoMetadata metadata, String failureReason) {
        this.metadata = metadata;
        this.failureReason = failureReason;
    }

    @Override
    public VideoMetadata metadata() {
        return metadata;
    }

    @Override
    public VisualPlaybackState state() {
        return VisualPlaybackState.FAILED;
    }

    @Override
    public void play() {
    }

    @Override
    public void pause() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void seekTo(long targetMillis) {
    }

    @Override
    public void update(long nowMillis) {
    }

    @Override
    public Identifier currentFrameTexture() {
        return null;
    }

    @Override
    public String failureReason() {
        return failureReason;
    }

    @Override
    public long positionMillis() {
        return 0L;
    }

    @Override
    public long durationMillis() {
        return metadata == null ? 0L : Math.max(0L, metadata.durationMillis());
    }

    @Override
    public boolean canSeek() {
        return false;
    }

    @Override
    public int frameWidth() {
        return metadata == null ? 0 : Math.max(0, metadata.width());
    }

    @Override
    public int frameHeight() {
        return metadata == null ? 0 : Math.max(0, metadata.height());
    }

    @Override
    public void close() {
    }
}
