package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.catalog.LocalModelSelection;

import java.nio.file.Path;
import java.time.Duration;

public record LlamaCppConfiguration(
        boolean enabled,
        Path executable,
        Path modelFile,
        String modelId,
        int contextTokens,
        String host,
        int port,
        String apiKey,
        Duration startupTimeout,
        Duration requestTimeout
) {
    public LlamaCppConfiguration {
        modelId = modelId == null || modelId.isBlank() ? "koil-local-model" : modelId.trim();
        contextTokens = Math.max(512, Math.min(131_072, contextTokens));
        host = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
        port = Math.max(0, Math.min(65_535, port));
        apiKey = apiKey == null ? "" : apiKey;
        startupTimeout = positive(startupTimeout, Duration.ofMinutes(5));
        requestTimeout = positive(requestTimeout, Duration.ofMinutes(30));
    }

    public static LlamaCppConfiguration fromSelection(LocalModelSelection selection, String apiKey) {
        if (selection == null || !selection.complete() || !"llama_cpp".equals(selection.providerId())) {
            return disabled();
        }
        return new LlamaCppConfiguration(
                true,
                selection.runtimeExecutable(),
                selection.modelFile(),
                selection.modelId(),
                selection.contextTokens(),
                "127.0.0.1",
                0,
                apiKey,
                Duration.ofMinutes(5),
                Duration.ofMinutes(30)
        );
    }

    public static LlamaCppConfiguration disabled() {
        return new LlamaCppConfiguration(
                false, null, null, "koil-local-model", 32_768, "127.0.0.1", 0, "",
                Duration.ofMinutes(5), Duration.ofMinutes(30)
        );
    }

    public boolean localhostOnly() {
        return "127.0.0.1".equals(this.host) || "localhost".equalsIgnoreCase(this.host) || "::1".equals(this.host);
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
