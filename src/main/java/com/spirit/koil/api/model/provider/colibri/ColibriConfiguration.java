package com.spirit.koil.api.model.provider.colibri;

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
        Duration restartBackoff
) {
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
                Duration.ofSeconds(5)
        );
    }

    public boolean localhostOnly() {
        return "127.0.0.1".equals(this.host) || "localhost".equalsIgnoreCase(this.host) || "::1".equals(this.host);
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
