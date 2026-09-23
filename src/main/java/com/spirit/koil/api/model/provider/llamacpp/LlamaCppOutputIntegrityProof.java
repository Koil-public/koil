package com.spirit.koil.api.model.provider.llamacpp;

/** Dependency-free proof for accelerated-output corruption detection. */
public final class LlamaCppOutputIntegrityProof {
    private LlamaCppOutputIntegrityProof() {
    }

    public static void main(String[] args) {
        require(LlamaCppOutputIntegrity.canary("KOIL_RUNTIME_OK").healthy(),
                "ordinary ASCII canary was rejected");
        require(LlamaCppOutputIntegrity.finalOutput("Hello. This is normal generated text.").healthy(),
                "ordinary model output was rejected");
        require(!LlamaCppOutputIntegrity.canary("\uFFFD\uFFFD\uFFFD").healthy(),
                "replacement-glyph corruption was accepted");
        require(!LlamaCppOutputIntegrity.finalOutput("????????????????????????????????????????").healthy(),
                "repeated-glyph corruption was accepted");
        require(!LlamaCppOutputIntegrity.finalOutput("abcabcabcabcabcabcabcabcabcabcabcabcabcabc").healthy(),
                "repeated-pattern corruption was accepted");
        require(!LlamaCppOutputIntegrity.canary("\uE380\uE380\uE380 KOIL").healthy(),
                "private-use corruption was accepted");
        System.out.println("llama.cpp output integrity proof passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
