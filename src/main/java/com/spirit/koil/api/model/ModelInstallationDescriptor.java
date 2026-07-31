package com.spirit.koil.api.model;

import java.nio.file.Path;

public record ModelInstallationDescriptor(
        String providerId,
        String modelId,
        Path runtimeExecutable,
        Path modelDirectory,
        long expectedModelBytes
) {
    public ModelInstallationDescriptor {
        providerId = providerId == null ? "" : providerId.trim();
        modelId = modelId == null ? "" : modelId.trim();
        expectedModelBytes = Math.max(0L, expectedModelBytes);
    }
}
