package com.spirit.koil.api.model.catalog;

import java.net.URI;

public record ModelArtifact(
        String fileName,
        URI downloadUri,
        long sizeBytes,
        String sha256
) {
    public ModelArtifact {
        fileName = fileName == null ? "" : fileName.trim();
        if (fileName.isEmpty() || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IllegalArgumentException("model artifact file name must be a safe base name");
        }
        if (downloadUri == null || !"https".equalsIgnoreCase(downloadUri.getScheme())) {
            throw new IllegalArgumentException("model artifact must use HTTPS");
        }
        sizeBytes = Math.max(0L, sizeBytes);
        sha256 = sha256 == null ? "" : sha256.trim().toLowerCase(java.util.Locale.ROOT);
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("model artifact requires a SHA-256 digest");
        }
    }
}
