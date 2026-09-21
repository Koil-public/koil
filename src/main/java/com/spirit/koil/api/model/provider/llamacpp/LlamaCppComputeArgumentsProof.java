package com.spirit.koil.api.model.provider.llamacpp;

import java.util.List;

/** Dependency-free proof for CPU, safe GPU-fit, and bounded hybrid placement. */
public final class LlamaCppComputeArgumentsProof {
    private LlamaCppComputeArgumentsProof() {
    }

    public static void main(String[] args) {
        List<String> cpu = LlamaCppComputeArguments.forSettings(
                new LlamaCppComputeSettings(LlamaCppComputeMode.CPU, 16, "Vulkan0"));
        require(cpu.equals(List.of("--device", "none", "--n-gpu-layers", "0")),
                "CPU mode did not fully disable accelerator placement");

        List<String> max = LlamaCppComputeArguments.forSettings(
                new LlamaCppComputeSettings(LlamaCppComputeMode.MAX, 16, "Vulkan0"));
        require(containsPair(max, "--device", "Vulkan0"), "MAX fallback lost accelerator device");
        require(containsPair(max, "--fit", "on"), "MAX fallback bypassed llama.cpp fit protection");
        require(!max.contains("all"), "MAX fallback still forces an OOM-prone all-layer allocation");
        require(containsPair(max, "--poll", "100"), "MAX fallback lost hot polling");

        require(LlamaCppComputeMode.parse("auto", LlamaCppComputeMode.CPU) == LlamaCppComputeMode.MAX,
                "Legacy auto persistence did not migrate to Max");
        require(LlamaCppComputeMode.parse("max", LlamaCppComputeMode.CPU) == LlamaCppComputeMode.MAX,
                "Max mode did not parse");

        List<String> gpu = LlamaCppComputeArguments.forSettings(
                new LlamaCppComputeSettings(LlamaCppComputeMode.GPU, 16, "Vulkan0"));
        require(containsPair(gpu, "--device", "Vulkan0"), "GPU mode lost requested device");
        require(containsPair(gpu, "--fit", "on"), "GPU mode does not use safe device fitting");
        require(!gpu.contains("--n-gpu-layers"),
                "GPU preferred mode should leave layer count unset so llama.cpp may fit safely");

        List<String> hybrid = LlamaCppComputeArguments.forSettings(
                new LlamaCppComputeSettings(LlamaCppComputeMode.HYBRID, 12, ""));
        require(containsPair(hybrid, "--n-gpu-layers", "12"),
                "Hybrid mode did not preserve an exact partial GPU layer count");
        require(hybrid.contains("--no-kv-offload"),
                "Hybrid mode can still unexpectedly offload the full KV cache");
        require(hybrid.contains("--no-op-offload"),
                "Hybrid mode can still move host tensor operations onto the accelerator unexpectedly");
        require(containsPair(hybrid, "--fit", "on"),
                "Hybrid mode lost accelerator headroom protection");

        System.out.println("llama.cpp compute argument proof passed.");
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
