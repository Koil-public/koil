package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilRuntimeBackend;

import java.util.Locale;

/**
 * Maps llama.cpp's successfully probed native device identity to Koil's backend-neutral runtime
 * backend vocabulary. This is launch-time adapter evidence, not a claim derived from filenames.
 */
final class LlamaCppBackendEvidence {
    private LlamaCppBackendEvidence() {}

    static KoilRuntimeBackend resolve(
            KoilRuntimeBackend plannedBackend,
            LlamaCppComputeSettings computeSettings,
            String resolvedDevice,
            String resolvedDeviceDetail
    ) {
        if (isConcrete(plannedBackend)) return plannedBackend;
        if (computeSettings != null && computeSettings.mode() == LlamaCppComputeMode.CPU) {
            return KoilRuntimeBackend.CPU;
        }

        String evidence = ((resolvedDevice == null ? "" : resolvedDevice) + " "
                + (resolvedDeviceDetail == null ? "" : resolvedDeviceDetail))
                .strip().toLowerCase(Locale.ROOT);
        if (evidence.isBlank()) return KoilRuntimeBackend.UNKNOWN;

        if (evidence.contains("vulkan")) return KoilRuntimeBackend.VULKAN;
        if (evidence.contains("cuda") || evidence.contains("nvidia")) return KoilRuntimeBackend.CUDA;
        if (evidence.contains("metal") || evidence.contains("apple gpu")) return KoilRuntimeBackend.METAL;
        if (evidence.contains("rocm")) return KoilRuntimeBackend.ROCM;
        if (evidence.contains("hip")) return KoilRuntimeBackend.HIP;
        if (evidence.contains("sycl") || evidence.contains("level-zero") || evidence.contains("level zero")) {
            return KoilRuntimeBackend.SYCL;
        }
        if (evidence.contains("opencl")) return KoilRuntimeBackend.OPENCL;
        return KoilRuntimeBackend.UNKNOWN;
    }

    static String source(
            KoilRuntimeBackend plannedBackend,
            LlamaCppComputeSettings computeSettings,
            String resolvedDevice,
            String resolvedDeviceDetail
    ) {
        if (isConcrete(plannedBackend)) return "universal_plan";
        if (computeSettings != null && computeSettings.mode() == LlamaCppComputeMode.CPU) return "compute_mode";
        return resolve(plannedBackend, computeSettings, resolvedDevice, resolvedDeviceDetail) == KoilRuntimeBackend.UNKNOWN
                ? "unproven"
                : "llama_device_probe";
    }

    private static boolean isConcrete(KoilRuntimeBackend backend) {
        return backend != null && backend != KoilRuntimeBackend.UNKNOWN;
    }
}
