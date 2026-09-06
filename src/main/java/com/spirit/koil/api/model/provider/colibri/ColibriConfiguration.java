package com.spirit.koil.api.model.provider.colibri;

import com.spirit.koil.api.model.catalog.LocalModelSelection;
import com.spirit.koil.api.model.catalog.ModelRuntimeCompatibility;

import java.nio.file.Path;
import java.time.Duration;

public record ColibriConfiguration(
        boolean enabled,
        Path executable,
        Path modelDirectory,
        String modelId,
        String host,
        int port,
        String apiKey,
        int maximumQueueDepth,
        Duration queueTimeout,
        Duration startupTimeout,
        Duration requestTimeout,
        int kvSlots,
        int maximumRestartAttempts,
        Duration restartBackoff,
        boolean managedRuntime,
        String catalogId,
        String engineId
) {
    /** Compatibility constructor for configurations created before managed runtimes existed. */
    public ColibriConfiguration(
            boolean enabled,
            Path executable,
            Path modelDirectory,
            String modelId,
            String host,
            int port,
            String apiKey,
            int maximumQueueDepth,
            Duration queueTimeout,
            Duration startupTimeout,
            Duration requestTimeout,
            int kvSlots,
            int maximumRestartAttempts,
            Duration restartBackoff
    ) {
        this(enabled, executable, modelDirectory, modelId, host, port, apiKey, maximumQueueDepth,
                queueTimeout, startupTimeout, requestTimeout, kvSlots, maximumRestartAttempts,
                restartBackoff, false, "", "");
    }
    public ColibriConfiguration {
        modelId = modelId == null || modelId.isBlank() ? "glm-5.2-colibri" : modelId.trim();
        host = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
        port = Math.max(0, Math.min(65_535, port));
        apiKey = apiKey == null ? "" : apiKey;
        maximumQueueDepth = Math.max(1, Math.min(64, maximumQueueDepth));
        queueTimeout = positive(queueTimeout, Duration.ofMinutes(5));
        startupTimeout = positive(startupTimeout, Duration.ofMinutes(10));
        requestTimeout = positive(requestTimeout, Duration.ofMinutes(30));
        kvSlots = Math.max(1, Math.min(16, kvSlots));
        maximumRestartAttempts = Math.max(0, Math.min(5, maximumRestartAttempts));
        restartBackoff = positive(restartBackoff, Duration.ofSeconds(5));
        catalogId = catalogId == null ? "" : catalogId.trim();
        engineId = engineId == null ? "" : engineId.trim();
    }

    /**
     * Managed when Koil owns the runtime distribution: either explicitly requested
     * or because a manually typed executable path no longer exists on disk.
     */
    public boolean effectiveManaged() {
        return managedRuntime || executable == null || !java.nio.file.Files.isRegularFile(executable);
    }

    public ColibriConfiguration withExecutable(Path resolved) {
        return new ColibriConfiguration(
                enabled, resolved, modelDirectory, modelId, host, port, apiKey, maximumQueueDepth,
                queueTimeout, startupTimeout, requestTimeout, kvSlots, maximumRestartAttempts,
                restartBackoff, managedRuntime, catalogId, engineId
        );
    }

    public ColibriConfiguration withManagedModel(Path directory, String modelIdentifier, String catalogIdentifier, String engine) {
        return new ColibriConfiguration(
                true, executable, directory, modelIdentifier, host, port, apiKey, maximumQueueDepth,
                queueTimeout, startupTimeout, requestTimeout, kvSlots, maximumRestartAttempts,
                restartBackoff, true, catalogIdentifier, engine
        );
    }

    /** Builds a managed configuration from the exact persisted model installation. */
    public static ColibriConfiguration fromSelection(
            LocalModelSelection selection,
            ColibriConfiguration defaults,
            ModelRuntimeCompatibility compatibility
    ) {
        if (selection == null || !selection.complete() || !"colibri".equals(selection.providerId())) {
            throw new IllegalArgumentException("a complete Colibri selection is required");
        }
        ColibriConfiguration base = defaults == null ? disabled() : defaults;
        String engine = compatibility == null ? "" : compatibility.engineId();
        return new ColibriConfiguration(
                true,
                selection.runtimeExecutable(),
                selection.modelPath(),
                selection.modelId(),
                "127.0.0.1",
                0,
                base.apiKey(),
                base.maximumQueueDepth(),
                base.queueTimeout(),
                base.startupTimeout(),
                base.requestTimeout(),
                base.kvSlots(),
                base.maximumRestartAttempts(),
                base.restartBackoff(),
                true,
                selection.catalogId(),
                engine
        );
    }

    public static ColibriConfiguration disabled() {
        return new ColibriConfiguration(
                false,
                null,
                null,
                "glm-5.2-colibri",
                "127.0.0.1",
                0,
                "",
                8,
                Duration.ofMinutes(5),
                Duration.ofMinutes(10),
                Duration.ofMinutes(30),
                1,
                1,
                Duration.ofSeconds(5),
                false,
                "",
                ""
        );
    }

    public boolean localhostOnly() {
        return "127.0.0.1".equals(this.host) || "localhost".equalsIgnoreCase(this.host) || "::1".equals(this.host);
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
