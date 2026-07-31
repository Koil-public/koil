package com.spirit.koil.api.model.catalog;

import java.nio.file.Path;

public record LocalModelSelection(
        String catalogId,
        String providerId,
        String modelId,
        Path runtimeExecutable,
        Path modelFile,
        int contextTokens
) {
    public LocalModelSelection {
        catalogId = value(catalogId);
        providerId = value(providerId);
        modelId = value(modelId);
        contextTokens = Math.max(512, Math.min(131_072, contextTokens));
    }

    public static LocalModelSelection none() {
        return new LocalModelSelection("", "", "", null, null, 32_768);
    }

    public boolean complete() {
        return !this.catalogId.isBlank()
                && !this.providerId.isBlank()
                && !this.modelId.isBlank()
                && this.runtimeExecutable != null
                && this.modelFile != null;
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
