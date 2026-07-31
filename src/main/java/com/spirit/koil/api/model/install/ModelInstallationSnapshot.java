package com.spirit.koil.api.model.install;

import java.time.Instant;

public record ModelInstallationSnapshot(
        ModelInstallationState state,
        String catalogId,
        String detail,
        String currentFile,
        long completedBytes,
        long totalBytes,
        Instant updatedAt
) {
    public ModelInstallationSnapshot {
        state = state == null ? ModelInstallationState.IDLE : state;
        catalogId = catalogId == null ? "" : catalogId;
        detail = detail == null ? "" : detail;
        currentFile = currentFile == null ? "" : currentFile;
        completedBytes = Math.max(0L, completedBytes);
        totalBytes = Math.max(0L, totalBytes);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public static ModelInstallationSnapshot idle() {
        return new ModelInstallationSnapshot(
                ModelInstallationState.IDLE, "", "No model installation is running.", "", 0L, 0L, Instant.now()
        );
    }

    public double progress() {
        return this.totalBytes <= 0L ? 0.0D : Math.min(1.0D, this.completedBytes / (double) this.totalBytes);
    }
}
