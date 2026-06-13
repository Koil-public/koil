package com.spirit.koil.api.util.file.media.video;

import java.io.File;
import java.io.IOException;

interface VideoBackend {
    String backendName();

    boolean isAvailable();

    VideoProbeResult probe(File file);

    byte[] extractThumbnail(File file, VideoMetadata metadata, int maxWidth, int maxHeight) throws IOException;

    byte[] extractFrame(File file, long targetMillis, int maxWidth, int maxHeight) throws IOException;
}
