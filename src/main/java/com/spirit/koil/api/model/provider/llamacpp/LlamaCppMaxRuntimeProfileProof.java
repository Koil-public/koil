package com.spirit.koil.api.model.provider.llamacpp;

import java.util.List;

/** Dependency-free proof that tuned MAX launches bounded CPU, hybrid, or GPU-fit winners. */
public final class LlamaCppMaxRuntimeProfileProof {
    private LlamaCppMaxRuntimeProfileProof() {
    }

    public static void main(String[] args) {
        LlamaCppMaxRuntimeProfile cpu = new LlamaCppMaxRuntimeProfile(
                LlamaCppMaxRuntimeProfile.Placement.CPU, 0, 4, 8, 50, 1, 2048, 512, "cpu");
        require(cpu.expectedPlacement().equals("cpu"), "CPU winner placement changed");
        require(cpu.arguments("Vulkan0").subList(0, 4).equals(List.of(
                "--device", "none", "--n-gpu-layers", "0")),
                "CPU winner still attempted GPU offload");

        LlamaCppMaxRuntimeProfile hybrid = new LlamaCppMaxRuntimeProfile(
                LlamaCppMaxRuntimeProfile.Placement.HYBRID, 8, 4, 8, 50, 1, 2048, 512, "hybrid");
        require(hybrid.expectedPlacement().equals("hybrid"), "Hybrid winner placement changed");
        require(containsPair(hybrid.arguments("MTL0"), "--device", "MTL0"),
                "Hybrid winner lost explicit device");
        require(containsPair(hybrid.arguments("MTL0"), "--n-gpu-layers", "8"),
                "Hybrid winner lost exact layer split");
        require(hybrid.arguments("MTL0").contains("--no-kv-offload"),
                "Hybrid MAX winner can still offload the full KV cache");

        LlamaCppMaxRuntimeProfile gpu = LlamaCppMaxRuntimeProfile.forcedFallback();
        require(gpu.expectedPlacement().equals("gpu"), "GPU fallback placement changed");
        require(containsPair(gpu.arguments("Vulkan0"), "--fit", "on"),
                "GPU fallback no longer uses safe device fitting");
        require(!gpu.arguments("Vulkan0").contains("--n-gpu-layers"),
                "GPU fallback still forces an exact all-layer allocation");
        require(containsPair(gpu.arguments("Vulkan0"), "--poll", "100"),
                "GPU fallback lost hot polling");
        require(gpu.acceptsRuntimePlacement("hybrid", 14),
                "GPU-fit incorrectly rejects a valid fitted hybrid placement");
        require(gpu.acceptsRuntimePlacement("gpu", 15),
                "GPU-fit incorrectly rejects a full GPU placement");
        require(!gpu.acceptsRuntimePlacement("cpu", 0),
                "GPU-fit incorrectly accepts CPU-only execution");
        require(hybrid.acceptsRuntimePlacement("hybrid", 8),
                "Exact hybrid profile rejected its requested split");
        require(!hybrid.acceptsRuntimePlacement("hybrid", 7),
                "Exact hybrid profile accepted the wrong GPU-layer count");
        require(!hybrid.acceptsRuntimePlacement("gpu", 8),
                "Exact hybrid profile accepted a different placement class");

        System.out.println("llama.cpp MAX runtime profile proof passed.");
    }

    private static boolean containsPair(List<String> values, String key, String value) {
        for (int i = 0; i + 1 < values.size(); i++) {
            if (key.equals(values.get(i)) && value.equals(values.get(i + 1))) return true;
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
