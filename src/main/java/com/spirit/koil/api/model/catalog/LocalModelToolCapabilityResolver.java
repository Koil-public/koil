package com.spirit.koil.api.model.catalog;

import com.spirit.koil.api.model.ModelCapabilityDescriptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single authoritative tool-capability decision for a selected local model.
 *
 * <p>Catalog, selected-runtime metadata, provider negotiation, and the installed
 * artifact are evidence sources, not independent permission gates. A credible
 * positive signal enables tools. Missing metadata never vetoes stronger
 * evidence from the loaded model or provider.</p>
 */
public final class LocalModelToolCapabilityResolver {
    private static final Map<Path, ArtifactEvidence> ARTIFACT_CACHE = new ConcurrentHashMap<>();

    private LocalModelToolCapabilityResolver() {
    }

    public static boolean supportsTools(
            LocalModelSelection selection,
            LocalModelCatalogEntry entry,
            ModelCapabilityDescriptor provider
    ) {
        if (provider != null && provider.toolCalling()) {
            return true;
        }

        if (selection != null && selection.complete() && entry != null) {
            boolean selectedCompatibilitySupportsTools = entry.runtimeCompatibility().stream().anyMatch(compatibility ->
                    selection.providerId().equals(compatibility.providerId())
                            && selection.runtimeId().equals(compatibility.runtimeId())
                            && (selection.architectureId().isBlank()
                            || selection.architectureId().equals(compatibility.architectureId()))
                            && compatibility.toolCalling());
            if (selectedCompatibilitySupportsTools) {
                return true;
            }
        }

        if (artifactSupportsTools(selection == null ? null : selection.modelPath())) {
            return true;
        }

        // Catalog metadata is intentionally the weakest fallback. It may be
        // stale or incomplete, but a positive declaration remains useful when
        // no stronger runtime/artifact evidence has been observed yet.
        return entry != null && entry.toolCalling();
    }

    private static boolean artifactSupportsTools(Path modelPath) {
        if (modelPath == null || !Files.isRegularFile(modelPath)) {
            return false;
        }
        Path normalized = modelPath.toAbsolutePath().normalize();
        try {
            long size = Files.size(normalized);
            FileTime modified = Files.getLastModifiedTime(normalized);
            ArtifactEvidence cached = ARTIFACT_CACHE.get(normalized);
            if (cached != null && cached.size() == size && cached.modifiedMillis() == modified.toMillis()) {
                return cached.supportsTools();
            }

            ModelArtifactInspection inspection = ModelArtifactInspector.inspect(normalized);
            boolean supports = inspection.present() && templateDeclaresTools(inspection.chatTemplate());
            ARTIFACT_CACHE.put(normalized, new ArtifactEvidence(size, modified.toMillis(), supports));
            return supports;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean templateDeclaresTools(String template) {
        if (template == null || template.isBlank()) {
            return false;
        }
        String lower = template.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "tool_calls",
                "tool_call",
                "tool_response",
                "tool_result",
                "available_tools",
                "<tool_call>",
                "</tool_call>",
                "<tool_response>",
                "</tool_response>",
                "function_call",
                "function_calls",
                "{% if tools",
                "{%if tools",
                "tools | tojson",
                "tools|tojson");
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private record ArtifactEvidence(long size, long modifiedMillis, boolean supportsTools) {
    }
}
