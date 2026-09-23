package com.spirit.koil.api.model.install;

import java.net.URI;

/** Verified downloadable runtime distribution for one concrete platform. */
public record ManagedRuntimeArtifact(
        String runtimeId,
        String providerId,
        String version,
        String platformId,
        String fileName,
        URI downloadUri,
        long sizeBytes,
        String sha256,
        String archiveType,
        String executableName,
        String license,
        boolean sourceBuild,
        String sourceSubdirectory
) {
    public ManagedRuntimeArtifact {
        runtimeId = safe(runtimeId);
        providerId = safe(providerId);
        version = safe(version);
        platformId = safe(platformId);
        fileName = safe(fileName);
        sha256 = safe(sha256).toLowerCase(java.util.Locale.ROOT);
        archiveType = safe(archiveType);
        executableName = safe(executableName);
        license = safe(license);
        sourceSubdirectory = safe(sourceSubdirectory);
        sizeBytes = Math.max(0L, sizeBytes);
        if (runtimeId.isBlank() || providerId.isBlank() || version.isBlank()
                || platformId.isBlank() || fileName.isBlank() || downloadUri == null
                || sizeBytes <= 0L || sha256.length() != 64 || archiveType.isBlank()
                || executableName.isBlank()) {
            throw new IllegalArgumentException("managed runtime artifact is incomplete");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
