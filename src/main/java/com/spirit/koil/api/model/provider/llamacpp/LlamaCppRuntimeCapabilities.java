package com.spirit.koil.api.model.provider.llamacpp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.catalog.LocalModelToolCapabilityResolver;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Best-effort observed llama-server capabilities for diagnostics and gating. */
record LlamaCppRuntimeCapabilities(
        boolean observed,
        String modelId,
        String modelPath,
        String chatTemplate,
        int contextTokens,
        Set<String> capabilities,
        String buildInfo,
        String evidence
) {
    LlamaCppRuntimeCapabilities {
        modelId = safe(modelId);
        modelPath = safe(modelPath);
        chatTemplate = safe(chatTemplate);
        contextTokens = Math.max(0, contextTokens);
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        buildInfo = safe(buildInfo);
        evidence = safe(evidence);
    }

    static LlamaCppRuntimeCapabilities unknown() {
        return new LlamaCppRuntimeCapabilities(false, "", "", "", 0, Set.of(), "", "runtime probe unavailable");
    }

    static LlamaCppRuntimeCapabilities fromProps(JsonObject props, String fallbackModelId) {
        if (props == null || props.entrySet().isEmpty()) return unknown();
        Set<String> capabilities = new LinkedHashSet<>();
        capabilities.add("openai_chat");

        String template = firstString(props, "chat_template", "chatTemplate");
        JsonObject templateCaps = object(props, "chat_template_caps");
        if (!templateCaps.entrySet().isEmpty()) capabilities.add("chat_template_caps_observed");
        for (Map.Entry<String, JsonElement> entry : templateCaps.entrySet()) {
            try {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsBoolean()) {
                    capabilities.add(entry.getKey());
                }
            } catch (Exception ignored) {
            }
        }
        if (capabilities.contains("supports_tool_calls") || capabilities.contains("supports_tools")) {
            capabilities.add("template_tools");
        }
        if (capabilities.contains("supports_reasoning")
                || capabilities.contains("supports_thinking")
                || capabilities.contains("supports_preserve_reasoning")) {
            capabilities.add("template_reasoning");
        }

        // Older llama-server builds may not expose chat_template_caps. Retain
        // a conservative fallback based on template syntax for those builds.
        if (!template.isBlank() && templateCaps.entrySet().isEmpty()) {
            String lower = template.toLowerCase(java.util.Locale.ROOT);
            if (LocalModelToolCapabilityResolver.templateDeclaresTools(template)) {
                capabilities.add("template_tools");
            }
            if (lower.contains("think") || lower.contains("reason")) capabilities.add("template_reasoning");
        }

        JsonObject modalities = object(props, "modalities");
        if (booleanValue(modalities, "vision")) capabilities.add("vision");
        if (booleanValue(modalities, "audio")) capabilities.add("audio");

        int context = firstInt(props, "n_ctx", "context_length", "n_ctx_train");
        if (context <= 0) {
            context = firstInt(object(props, "default_generation_settings"), "n_ctx", "context_length", "n_ctx_train");
        }
        String model = firstString(props, "model", "model_id", "name");
        if (model.isBlank()) model = fallbackModelId;
        String modelPath = firstString(props, "model_path", "modelPath");
        String buildInfo = firstString(props, "build_info", "buildInfo");
        return new LlamaCppRuntimeCapabilities(
                true,
                model,
                modelPath,
                template,
                context,
                capabilities,
                buildInfo,
                "llama-server /props"
        );
    }

    private static JsonObject object(JsonObject root, String key) {
        return root != null && root.has(key) && root.get(key).isJsonObject()
                ? root.getAsJsonObject(key)
                : new JsonObject();
    }

    private static boolean booleanValue(JsonObject root, String key) {
        try {
            return root != null && root.has(key) && root.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String firstString(JsonObject root, String... keys) {
        for (String key : keys) {
            JsonElement value = root.get(key);
            if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) {
                try { return value.getAsString(); } catch (Exception ignored) { }
            }
        }
        return "";
    }

    private static int firstInt(JsonObject root, String... keys) {
        for (String key : keys) {
            JsonElement value = root.get(key);
            if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) {
                try { return Math.max(0, value.getAsInt()); } catch (Exception ignored) { }
            }
        }
        return 0;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
