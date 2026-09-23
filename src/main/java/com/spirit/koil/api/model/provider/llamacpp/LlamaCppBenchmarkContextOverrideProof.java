package com.spirit.koil.api.model.provider.llamacpp;

/** Dependency-light proof for MAX benchmark context targeting. */
public final class LlamaCppBenchmarkContextOverrideProof {
    private LlamaCppBenchmarkContextOverrideProof() {}

    public static void main(String[] args) {
        LlamaCppBenchmarkContextOverride.clear();
        require(LlamaCppBenchmarkContextOverride.applyConfiguredContext(131072) == 131072,
                "no override must preserve configured context");

        LlamaCppBenchmarkContextOverride.set(128000);
        require(LlamaCppBenchmarkContextOverride.current().orElseThrow() == 128000,
                "128K model-tier override must preserve an exact 128000-token target");
        require(LlamaCppBenchmarkContextOverride.applyConfiguredContext(128000) == 128000,
                "128000 target must survive an exact 128000 model ceiling");
        LlamaCppBenchmarkContextOverride.clear();

        LlamaCppBenchmarkContextOverride.set(131072);
        require(LlamaCppBenchmarkContextOverride.current().orElseThrow() == 131072,
                "128K override must be observable");
        require(LlamaCppBenchmarkContextOverride.applyConfiguredContext(131072) == 131072,
                "128K target must survive a 128K model ceiling");
        require(LlamaCppBenchmarkContextOverride.applyConfiguredContext(65536) == 65536,
                "benchmark override must never exceed the model/configured ceiling");

        LlamaCppBenchmarkContextOverride.set(8192);
        require(LlamaCppBenchmarkContextOverride.applyConfiguredContext(131072) == 8192,
                "8K target must constrain the benchmark launch");

        LlamaCppBenchmarkContextOverride.clear();
        require(LlamaCppBenchmarkContextOverride.current().isEmpty(),
                "clearing must remove the process-local benchmark target");
        System.out.println("llama.cpp benchmark context override proof passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
