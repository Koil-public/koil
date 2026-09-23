package com.spirit.koil.api.model.provider.llamacpp;

/** Deterministic proof for low-memory llama.cpp startup sizing. */
public final class LlamaCppStartupMemoryPolicyProof {
    private LlamaCppStartupMemoryPolicyProof() {}

    public static void main(String[] args) {
        long mib = 1024L * 1024L;
        var deck = LlamaCppStartupMemoryPolicy.select(32768, 1, 2100L * mib, 3700L * mib);
        require(deck.contextTokens() <= 8192, "Deck-sized headroom should reduce a 32k context");
        var smallModelLowMemory = LlamaCppStartupMemoryPolicy.select(16384, 1, 146L * mib, 3076L * mib);
        require(smallModelLowMemory.contextTokens() == 4096, "3 GiB available headroom should retain the conservative 4k cap");
        require("low_memory".equals(smallModelLowMemory.reason()),
                "small models under host pressure must not be mislabeled as large-model pressure");
        var roomy = LlamaCppStartupMemoryPolicy.select(32768, 1, 2100L * mib, 12L * 1024L * mib);
        require(roomy.contextTokens() == 32768, "roomy host should retain configured context");
        var parallel = LlamaCppStartupMemoryPolicy.select(32768, 4, 2100L * mib, 5200L * mib);
        require(parallel.contextTokens() < 32768, "parallel slots should reserve aggregate KV headroom");
        var retry = LlamaCppStartupMemoryPolicy.recovery(deck, 32768, 1, 2100L * mib, 3200L * mib);
        require(retry.contextTokens() < deck.contextTokens(), "SIGTERM recovery should reduce context again");
        System.out.println("llama.cpp startup memory policy proof passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
