package com.spirit.koil.api.model.catalog;

import java.nio.file.Path;

/**
 * Persisted exact model installation selection. The legacy constructor keeps
 * existing llama.cpp selections readable while directory/container runtimes
 * retain their own identity and installation shape.
 */
public record LocalModelSelection(
        String catalogId,
        String providerId,
        String runtimeId,
        String architectureId,
        String modelId,
        Path runtimeExecutable,
        Path installationRoot,
        Path modelPath,
        String tokenizerId,
        String runtimeProfileId,
        int contextTokens
) {
    public LocalModelSelection(
            String catalogId,
            String providerId,
            String modelId,
            Path runtimeExecutable,
            Path modelFile,
            int contextTokens
    ) {
        this(
                catalogId,
                providerId,
                "llama_cpp".equals(value(providerId)) ? "llama.cpp-b10173" : value(providerId),
                "legacy/unknown",
                modelId,
                runtimeExecutable,
                modelFile == null ? null : modelFile.toAbsolutePath().normalize().getParent(),
                modelFile,
                "legacy/embedded",
                "",
                contextTokens
        );
    }

    public LocalModelSelection {
        catalogId = value(catalogId);
        providerId = value(providerId);
        runtimeId = value(runtimeId);
        architectureId = value(architectureId);
        modelId = value(modelId);
        tokenizerId = value(tokenizerId);
        runtimeProfileId = value(runtimeProfileId);
        runtimeExecutable = normalize(runtimeExecutable);
        installationRoot = normalize(installationRoot);
        modelPath = normalize(modelPath);
        contextTokens = Math.max(512, Math.min(2_097_152, contextTokens));
    }

    public static LocalModelSelection none() {
        return new LocalModelSelection("", "", "", "", "", null, null, null, "", "", 32_768);
    }

    public boolean complete() {
        return !catalogId.isBlank()
                && !providerId.isBlank()
                && !runtimeId.isBlank()
                && !modelId.isBlank()
                && runtimeExecutable != null
                && modelPath != null;
    }

    /** Legacy accessor retained while llama.cpp-specific consumers migrate. */
    public Path modelFile() {
        return modelPath;
    }

    public boolean directoryModel() {
        return modelPath != null && java.nio.file.Files.isDirectory(modelPath);
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
