package com.spirit.koil.api.util.file.media;

import net.minecraft.util.Identifier;

public interface VisualPlaybackSession extends AutoCloseable {
    VisualPlaybackState state();

    void play();

    void pause();

    void stop();

    void seekTo(long targetMillis);

    void update(long nowMillis);

    Identifier currentFrameTexture();

    String failureReason();

    default String statusMessage() {
        return failureReason();
    }

    long positionMillis();

    long durationMillis();

    boolean canSeek();

    int frameWidth();

    int frameHeight();

    @Override
    void close();
}
