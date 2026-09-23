package com.spirit.koil.api.model.provider.llamacpp;

/** Formats READY-state runtime detail from the authoritative optional warmup lifecycle state. */
final class LlamaCppWarmupRuntimeDetail {
    private LlamaCppWarmupRuntimeDetail() {}

    static String detail(String runtimeDetail, String warmupState) {
        String base = runtimeDetail == null ? "" : runtimeDetail.strip();
        String state = warmupState == null ? "" : warmupState.strip().toLowerCase(java.util.Locale.ROOT);
        String suffix = switch (state) {
            case "preparing" -> "prompt cache warming in background";
            case "ready" -> "direct prompt cache prepared";
            case "ready_degraded" -> "direct prompt warmup completed with optional failures";
            case "skipped_memory_pressure" -> "direct prompt warmup skipped for memory pressure";
            case "preempted_by_request" -> "direct prompt warmup preempted by user generation";
            case "disabled", "not_started", "" -> "";
            default -> "direct prompt warmup state=" + state;
        };
        if (suffix.isBlank()) return base;
        if (base.isBlank()) return suffix;
        return base + "; " + suffix;
    }
}
