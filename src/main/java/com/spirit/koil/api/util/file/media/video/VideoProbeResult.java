package com.spirit.koil.api.util.file.media.video;

public record VideoProbeResult(
        boolean supportedContainer,
        boolean readyForPlayback,
        String message,
        VideoMetadata metadata
) {
}
