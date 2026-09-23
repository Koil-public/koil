package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilRuntimeBackend;

/** Dependency-light proof for pre-launch backend evidence used by adaptive state precision. */
public final class LlamaCppBackendEvidenceProof {
    private LlamaCppBackendEvidenceProof() {}

    public static void main(String[] args) {
        require(LlamaCppBackendEvidence.resolve(KoilRuntimeBackend.VULKAN, null, "", "")
                        == KoilRuntimeBackend.VULKAN,
                "a proven universal backend must win");

        LlamaCppComputeSettings automatic = new LlamaCppComputeSettings(LlamaCppComputeMode.MAX, 16, "");
        require(LlamaCppBackendEvidence.resolve(KoilRuntimeBackend.UNKNOWN, automatic,
                        "Vulkan0", "AMD RADV VANGOGH") == KoilRuntimeBackend.VULKAN,
                "a successful Vulkan device probe must establish Vulkan pre-launch evidence");
        require("llama_device_probe".equals(LlamaCppBackendEvidence.source(
                        KoilRuntimeBackend.UNKNOWN, automatic, "Vulkan0", "AMD RADV VANGOGH")),
                "device-probe evidence source must be explicit");

        require(LlamaCppBackendEvidence.resolve(KoilRuntimeBackend.UNKNOWN, automatic,
                        "CUDA0", "NVIDIA GPU") == KoilRuntimeBackend.CUDA,
                "CUDA device probe must map to CUDA");
        require(LlamaCppBackendEvidence.resolve(KoilRuntimeBackend.UNKNOWN, automatic,
                        "", "") == KoilRuntimeBackend.UNKNOWN,
                "missing evidence must stay unknown");

        System.out.println("llama.cpp backend evidence proof passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
