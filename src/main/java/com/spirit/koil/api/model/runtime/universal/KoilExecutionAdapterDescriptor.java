package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.LocalModelPerformanceBenchmarkProvider;
import com.spirit.koil.api.model.LocalModelProvider;

import java.util.Locale;
import java.util.Map;

/** Describes an internal execution implementation hidden behind Koil's single provider boundary. */
public record KoilExecutionAdapterDescriptor(
        String id,
        String toolProtocol,
        boolean nativeBenchmarking,
        Map<String, String> metadata
) {
    public KoilExecutionAdapterDescriptor {
        id = normalize(id, "unknown");
        toolProtocol = normalize(toolProtocol, "openai_tool_calls");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static KoilExecutionAdapterDescriptor fromLegacyProvider(LocalModelProvider provider) {
        if (provider == null) {
            return new KoilExecutionAdapterDescriptor("unknown", "openai_tool_calls", false, Map.of());
        }
        String id = normalize(provider.id(), "unknown");
        String protocol = "colibri".equals(id) ? "anthropic_tool_use" : "openai_tool_calls";
        return new KoilExecutionAdapterDescriptor(
                id,
                protocol,
                provider instanceof LocalModelPerformanceBenchmarkProvider,
                Map.of("bridge", "legacy_local_model_provider")
        );
    }

    private static String normalize(String value, String fallback) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? fallback : normalized;
    }
}
