package com.spirit.koil.api.model.install;

import java.nio.file.Path;

/** Completed managed runtime install: exact binaries plus how they got here. */
public record ManagedRuntimeInstallation(
        String runtimeId,
        String platformId,
        Path installRoot,
        Path executable,
        ManagedRuntimeArtifact artifact,
        boolean sourceBuilt,
        boolean reusedExisting
) {
    public ManagedRuntimeInstallation {
        runtimeId = runtimeId == null ? "" : runtimeId;
        platformId = platformId == null ? "" : platformId;
        if (installRoot == null || executable == null || artifact == null) {
            throw new IllegalArgumentException("managed runtime installation is incomplete");
        }
    }
}
