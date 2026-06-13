package com.spirit.koil.api.util.file.media.video;

import com.spirit.koil.api.util.file.media.image.ImageTexture;

public record VideoPreviewSnapshot(
        VideoProbeResult probe,
        ImageTexture thumbnail,
        boolean loading,
        String statusMessage
) {
}
