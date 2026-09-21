package com.spirit.koil.api.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Model-authored side-channel content that a provider/runtime explicitly exposes separately from
 * the user-facing assistant answer.
 *
 * <p>This is observational data. Koil never invents one of these channels from token timing,
 * sampler state, or other runtime telemetry. A channel exists only when the model output,
 * template markup, or provider protocol actually exposed it.</p>
 */
public record ModelExposedData(
        Kind kind,
        String text,
        String nativeChannel,
        String provider,
        Map<String, String> metadata
) {
    public ModelExposedData {
        kind = kind == null ? Kind.OTHER : kind;
        text = text == null ? "" : text;
        nativeChannel = normalize(nativeChannel, "unknown");
        provider = normalize(provider, "unknown");
        metadata = sanitize(metadata);
    }

    public static ModelExposedData of(Kind kind, String text, String nativeChannel, String provider) {
        return new ModelExposedData(kind, text, nativeChannel, provider, Map.of());
    }

    public String tag() {
        return this.kind.tag();
    }

    public String eventKey() {
        return this.kind.name().toLowerCase(Locale.ROOT) + "-" + this.nativeChannel;
    }

    public boolean hasText() {
        return !this.text.isEmpty();
    }

    public ModelExposedData withMetadata(Map<String, ?> extra) {
        if (extra == null || extra.isEmpty()) return this;
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(this.metadata);
        extra.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) return;
            merged.put(key.strip(), String.valueOf(value));
        });
        return new ModelExposedData(this.kind, this.text, this.nativeChannel, this.provider, merged);
    }

    public enum Kind {
        THOUGHT("THINKING"),
        REASONING("REASONING"),
        ANALYSIS("ANALYSIS"),
        SCRATCHPAD("SCRATCHPAD"),
        PLAN("PLAN"),
        REFLECTION("REFLECTION"),
        CRITIQUE("CRITIQUE"),
        COMMENTARY("COMMENTARY"),
        REASONING_SUMMARY("REASONING SUMMARY"),
        CONFIDENCE("CONFIDENCE"),
        REFUSAL("REFUSAL"),
        OTHER("EXPOSED");

        private final String tag;

        Kind(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return this.tag;
        }
    }

    private static Map<String, String> sanitize(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return Map.of();
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) return;
            copy.put(key.strip(), value);
        });
        return Map.copyOf(copy);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "_")
                .replaceAll("_{2,}", "_");
        return normalized.isBlank() ? fallback : normalized;
    }
}
