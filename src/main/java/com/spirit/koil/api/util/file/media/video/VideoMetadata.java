package com.spirit.koil.api.util.file.media.video;

public record VideoMetadata(
        String sourceName,
        String container,
        int width,
        int height,
        double frameRate,
        long durationMillis,
        boolean hasAudio,
        String decoderStage,
        String backend,
        boolean thumbnailCapable
) {
}
