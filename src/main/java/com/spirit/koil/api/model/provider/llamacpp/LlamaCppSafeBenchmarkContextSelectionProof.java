package com.spirit.koil.api.model.provider.llamacpp;

public final class LlamaCppSafeBenchmarkContextSelectionProof {
    private LlamaCppSafeBenchmarkContextSelectionProof() {}

    public static void main(String[] args) {
        require(LlamaCppSafeBenchmarkContextSelection.probeTargetForModelCeiling(128000) == 128000, "128000 ceiling");
        require(LlamaCppSafeBenchmarkContextSelection.probeTargetForModelCeiling(131072) == 131072, "131072 ceiling");
        require(LlamaCppSafeBenchmarkContextSelection.probeTargetForModelCeiling(100000) == 65536, "100k ceiling selects 64k tier");
        require(LlamaCppSafeBenchmarkContextSelection.acceptDiscoveredContext(128000, 128000, 4096) == 4096, "128k clamp to 4k accepted");
        require(LlamaCppSafeBenchmarkContextSelection.acceptDiscoveredContext(128000, 128000, 8192) == 8192, "128k clamp to 8k accepted");
        boolean rejected = false;
        try {
            LlamaCppSafeBenchmarkContextSelection.acceptDiscoveredContext(128000, 128000, 131072);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        require(rejected, "context above model ceiling rejected");
        System.out.println("llama.cpp safe benchmark context selection proof passed");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
