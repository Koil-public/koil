package com.spirit.koil.api.model.cache;

import com.spirit.koil.api.model.ModelToolDefinition;

import java.util.List;

/**
 * One deterministic prompt branch Koil evaluates before exposing a local model
 * as ready. Warmup never asks the model to generate text; it only prepares KV.
 */
public record ModelStartupWarmupBranch(
        String id,
        String mode,
        String systemPrompt,
        List<ModelToolDefinition> tools,
        String toolRegistryVersion,
        int preferredSlot,
        boolean required,
        boolean keepHot,
        String seedFingerprint
) {
    public ModelStartupWarmupBranch {
        id = normalize(id, "startup");
        mode = normalize(mode, "ask");
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        tools = tools == null ? List.of() : List.copyOf(tools);
        toolRegistryVersion = toolRegistryVersion == null ? "" : toolRegistryVersion;
        preferredSlot = Math.max(0, preferredSlot);
        seedFingerprint = normalize(seedFingerprint, ModelPromptCacheIdentity.seedPrefix(id, systemPrompt, tools));
    }

    public String stableFingerprint() {
        return ModelPromptCacheIdentity.stablePrefix(mode, systemPrompt, tools, toolRegistryVersion);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
