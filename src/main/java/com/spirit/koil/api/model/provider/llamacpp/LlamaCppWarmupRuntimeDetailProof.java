package com.spirit.koil.api.model.provider.llamacpp;

public final class LlamaCppWarmupRuntimeDetailProof {
    private LlamaCppWarmupRuntimeDetailProof() {}

    public static void main(String[] args) {
        String base = "llama.cpp runtime ready";
        require(LlamaCppWarmupRuntimeDetail.detail(base, "preparing").contains("warming in background"));
        require(LlamaCppWarmupRuntimeDetail.detail(base, "ready").contains("cache prepared"));
        require(LlamaCppWarmupRuntimeDetail.detail(base, "skipped_memory_pressure").contains("skipped for memory pressure"));
        require(!LlamaCppWarmupRuntimeDetail.detail(base, "skipped_memory_pressure").contains("warming in background"));
        require(LlamaCppWarmupRuntimeDetail.detail(base, "preempted_by_request").contains("preempted by user generation"));
        System.out.println("llama.cpp warmup runtime detail proof passed");
    }

    private static void require(boolean value) {
        if (!value) throw new AssertionError("warmup runtime detail proof failed");
    }
}
