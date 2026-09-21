package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.cache.ModelCacheBudget;
import com.spirit.koil.api.model.cache.ModelCacheProfile;
import com.spirit.koil.api.model.catalog.LocalModelSelection;
import com.spirit.koil.api.model.catalog.LocalModelCatalog;

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
        Duration requestTimeout,
        int kvSlots,
        ModelCacheProfile cacheProfile,
        Path slotSavePath,
        int cacheReuseTokens,
        String modelFingerprintHint,
        LlamaCppComputeSettings computeSettings
) {
    /** Compatibility constructor retained for callers created before Koil KV persistence. */
    public LlamaCppConfiguration(
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
        this(enabled, executable, modelFile, modelId, contextTokens, host, port, apiKey,
                startupTimeout, requestTimeout, 1, ModelCacheProfile.configured(), null, 256, "",
                LlamaCppComputeSettings.defaults());
    }

    /** Compatibility constructor retained for callers created before compute placement settings. */
    public LlamaCppConfiguration(
            boolean enabled,
            Path executable,
            Path modelFile,
            String modelId,
            int contextTokens,
            String host,
            int port,
            String apiKey,
            Duration startupTimeout,
            Duration requestTimeout,
            int kvSlots,
            ModelCacheProfile cacheProfile,
            Path slotSavePath,
            int cacheReuseTokens,
            String modelFingerprintHint
    ) {
        this(enabled, executable, modelFile, modelId, contextTokens, host, port, apiKey,
                startupTimeout, requestTimeout, kvSlots, cacheProfile, slotSavePath, cacheReuseTokens,
                modelFingerprintHint, LlamaCppComputeSettings.defaults());
    }

    public LlamaCppConfiguration {
        modelId = modelId == null || modelId.isBlank() ? "koil-local-model" : modelId.trim();
        contextTokens = Math.max(512, contextTokens);
        host = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
        port = Math.max(0, Math.min(65_535, port));
        apiKey = apiKey == null ? "" : apiKey;
        startupTimeout = positive(startupTimeout, Duration.ofMinutes(5));
        requestTimeout = positive(requestTimeout, Duration.ofMinutes(30));
        kvSlots = Math.max(1, Math.min(8, kvSlots));
        cacheProfile = cacheProfile == null ? ModelCacheProfile.configured() : cacheProfile;
        slotSavePath = slotSavePath == null
                ? Path.of("koil", "sys", "model", "cache", "llama", safeName(modelId))
                : slotSavePath.toAbsolutePath().normalize();
        cacheReuseTokens = Math.max(0, Math.min(8192, cacheReuseTokens));
        modelFingerprintHint = modelFingerprintHint == null ? "" : modelFingerprintHint.trim().toLowerCase(java.util.Locale.ROOT);
        if (!modelFingerprintHint.isBlank() && !modelFingerprintHint.matches("[0-9a-f]{64}")) modelFingerprintHint = "";
        computeSettings = computeSettings == null ? LlamaCppComputeSettings.defaults() : computeSettings;
    }

    public static LlamaCppConfiguration fromSelection(LocalModelSelection selection, String apiKey) {
        return fromSelection(selection, apiKey, 1);
    }

    public static LlamaCppConfiguration fromSelection(LocalModelSelection selection, String apiKey, int kvSlots) {
        if (selection == null || !selection.complete() || !"llama_cpp".equals(selection.providerId())) {
            return disabled();
        }
        ModelCacheProfile profile = ModelCacheProfile.configured();
        ModelCacheBudget budget = ModelCacheBudget.detect(profile);
        int reuse = switch (budget.profile()) {
            case LOW_MEMORY -> 128;
            case BALANCED -> 256;
            case AGGRESSIVE -> 512;
            case MAXIMUM -> 1024;
            case AUTO -> 256;
        };
        String modelSha256 = LocalModelCatalog.find(selection.catalogId())
                .flatMap(entry -> entry.artifacts().stream()
                        .filter(artifact -> selection.modelFile() != null
                                && selection.modelFile().getFileName() != null
                                && artifact.fileName().equals(selection.modelFile().getFileName().toString()))
                        .findFirst())
                .map(artifact -> artifact.sha256())
                .orElse("");
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
                Duration.ofMinutes(30),
                kvSlots,
                budget.profile(),
                null,
                reuse,
                modelSha256,
                LlamaCppComputeSettingsStore.load()
        );
    }

    public static LlamaCppConfiguration disabled() {
        return new LlamaCppConfiguration(
                false, null, null, "koil-local-model", 32_768, "127.0.0.1", 0, "",
                Duration.ofMinutes(5), Duration.ofMinutes(30), 1,
                ModelCacheProfile.LOW_MEMORY, null, 0, "", LlamaCppComputeSettings.defaults()
        );
    }

    public boolean localhostOnly() {
        return "127.0.0.1".equals(this.host) || "localhost".equalsIgnoreCase(this.host) || "::1".equals(this.host);
    }

    public ModelCacheBudget cacheBudget() {
        return ModelCacheBudget.detect(this.cacheProfile);
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    private static String safeName(String value) {
        String safe = value == null ? "model" : value.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        return safe.isBlank() ? "model" : safe;
    }
}
