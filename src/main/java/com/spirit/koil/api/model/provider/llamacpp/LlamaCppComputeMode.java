package com.spirit.koil.api.model.provider.llamacpp;

import java.util.Locale;

/**
 * Controls how llama.cpp places and schedules model work.
 *
 * CPU is a strict no-accelerator mode. MAX is Koil's measured performance policy:
 * when a machine/model/runtime-specific tuned profile exists it launches that
 * proven winner, otherwise it falls back to llama.cpp's bounded fit engine. GPU
 * requests the largest accelerator placement that preserves a memory margin rather
 * than forcing every layer. HYBRID deliberately keeps the KV cache and host-side
 * tensor work on CPU while offloading only a fixed, preflighted number of model layers.
 */
public enum LlamaCppComputeMode {
    CPU,
    MAX,
    GPU,
    HYBRID;

    public static LlamaCppComputeMode parse(String value, LlamaCppComputeMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? CPU : fallback;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        // Revision 17 persisted the early Max profile as "auto". Treat that old
        // wire value as MAX so upgrades preserve the user's selected policy.
        if ("AUTO".equals(normalized)) {
            return MAX;
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return fallback == null ? CPU : fallback;
        }
    }

    public String displayName() {
        return switch (this) {
            case CPU -> "CPU only";
            case MAX -> "MAX performance";
            case GPU -> "GPU preferred";
            case HYBRID -> "CPU + GPU hybrid";
        };
    }
}
