package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactInspection;

/** Tokenizer and chat-template facts retained without executing repository code. */
public record KoilTokenizerDescriptor(
        String family,
        boolean chatTemplatePresent,
        boolean toolTemplateEvidence,
        boolean reasoningTemplateEvidence,
        String bosTokenId,
        String eosTokenId,
        String evidence
) {
    public KoilTokenizerDescriptor {
        family = safe(family);
        bosTokenId = safe(bosTokenId);
        eosTokenId = safe(eosTokenId);
        evidence = safe(evidence);
    }

    public static KoilTokenizerDescriptor from(ModelArtifactInspection inspection) {
        if (inspection == null || !inspection.present()) {
            return new KoilTokenizerDescriptor("", false, false, false, "", "", "tokenizer metadata unavailable");
        }
        var metadata = inspection.metadata();
        boolean template = inspection.chatTemplate() != null && !inspection.chatTemplate().isBlank();
        return new KoilTokenizerDescriptor(
                inspection.tokenizerFamily(),
                template,
                inspection.declaredCapabilities().contains("template_tools"),
                inspection.declaredCapabilities().contains("template_reasoning"),
                first(metadata, "tokenizer.ggml.bos_token_id", "tokenizer.bos_token_id", "config.bos_token_id"),
                first(metadata, "tokenizer.ggml.eos_token_id", "tokenizer.eos_token_id", "config.eos_token_id"),
                template ? "chat template retained as inert metadata" : "no chat template retained"
        );
    }

    private static String first(java.util.Map<String, String> metadata, String... keys) {
        for (String key : keys) {
            String value = metadata.get(key);
            if (value != null && !value.isBlank()) return value.strip();
        }
        return "";
    }

    private static String safe(String value) { return value == null ? "" : value.strip(); }
}
