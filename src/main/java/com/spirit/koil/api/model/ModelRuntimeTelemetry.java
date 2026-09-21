package com.spirit.koil.api.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider-authored diagnostic telemetry that is distinct from model-visible text and reasoning.
 *
 * <p>This record must never be used to infer or fabricate private reasoning. Providers may publish
 * only directly observed runtime signals such as prompt progress, token/timing counters, sampling
 * settings, slot state, cache usage, or transport/runtime metadata.</p>
 */
public record ModelRuntimeTelemetry(
        String kind,
        String summary,
        Map<String, String> fields
) {
    public ModelRuntimeTelemetry {
        kind = normalize(kind, "runtime");
        summary = summary == null ? "" : summary.strip();
        fields = sanitize(fields);
    }

    public static ModelRuntimeTelemetry of(String kind, String summary, Map<String, ?> fields) {
        Map<String, String> converted = new LinkedHashMap<>();
        if (fields != null) {
            fields.forEach((key, value) -> {
                if (key == null || key.isBlank() || value == null) return;
                converted.put(key.strip(), String.valueOf(value));
            });
        }
        return new ModelRuntimeTelemetry(kind, summary, converted);
    }

    private static Map<String, String> sanitize(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) return Map.of();
        Map<String, String> sanitized = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) return;
            sanitized.put(key.strip(), value);
        });
        return Map.copyOf(sanitized);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.strip().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "_")
                .replaceAll("_{2,}", "_");
        return normalized.isBlank() ? fallback : normalized;
    }
}
