package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilExecutionSettings;

import java.util.ArrayList;
import java.util.List;

/** Converts Koil's compute policy into llama.cpp server arguments. */
final class LlamaCppComputeArguments {
    private LlamaCppComputeArguments() {
    }

    static List<String> forSettings(LlamaCppComputeSettings settings) {
        return forSettings(settings, null);
    }

    static List<String> forSettings(LlamaCppComputeSettings settings, LlamaCppMaxRuntimeProfile maxProfile) {
        return forSettings(settings, maxProfile, null);
    }

    static List<String> forSettings(
            LlamaCppComputeSettings settings,
            LlamaCppMaxRuntimeProfile maxProfile,
            KoilExecutionSettings executionSettings
    ) {
        LlamaCppComputeSettings safe = settings == null ? LlamaCppComputeSettings.defaults() : settings;
        List<String> arguments = new ArrayList<>();
        switch (safe.mode()) {
            case CPU -> {
                // n-gpu-layers=0 alone can still allow selected accelerator work
                // in some backends. --device none is llama.cpp's strict CPU path.
                arguments.add("--device");
                arguments.add("none");
                arguments.add("--n-gpu-layers");
                arguments.add("0");
            }
            case MAX -> {
                LlamaCppMaxRuntimeProfile profile = maxProfile == null
                        ? LlamaCppMaxRuntimeProfile.forcedFallback()
                        : maxProfile;
                arguments.addAll(profile.arguments(safe.device(), executionSettings));
            }
            case GPU -> {
                addDevice(arguments, safe.device());
                // Leave n_gpu_layers unset so llama.cpp's fit engine may choose the
                // maximum safe offload for the current device/context instead of
                // forcing an allocation that can starve an UMA graphics stack.
                arguments.add("--fit");
                arguments.add("on");
                arguments.add("--fit-target");
                arguments.add(Integer.toString(acceleratorHeadroomMiB()));
            }
            case HYBRID -> {
                addDevice(arguments, safe.device());
                arguments.add("--n-gpu-layers");
                arguments.add(Integer.toString(safe.hybridGpuLayers()));
                // Hybrid means a predictable model-layer split. Keep the KV cache on
                // host memory so a small layer count cannot unexpectedly allocate a
                // full-context KV cache on an UMA GPU (notably Steam Deck Vulkan).
                arguments.add("--no-kv-offload");
                arguments.add("--no-op-offload");

                // Vulkan compute buffers scale with batch/ubatch geometry as well as
                // model placement. On UMA handhelds a layer split that is otherwise
                // modest can still starve the compositor while llama.cpp allocates its
                // default 2048/512 buffers. Use a progressively smaller startup/runtime
                // geometry as exact GPU residency rises. MAX tuning can later measure
                // larger batches once a stable placement has been proven.
                int[] hybridBatch = hybridBatchGeometry(safe.hybridGpuLayers());
                if (hybridBatch[0] > 0) {
                    arguments.add("--batch-size");
                    arguments.add(Integer.toString(hybridBatch[0]));
                    arguments.add("--ubatch-size");
                    arguments.add(Integer.toString(hybridBatch[1]));
                }

                arguments.add("--fit");
                arguments.add("on");
                arguments.add("--fit-target");
                arguments.add(Integer.toString(acceleratorHeadroomMiB()));
            }
        }
        return List.copyOf(arguments);
    }


    static int[] hybridBatchGeometry(int gpuLayers) {
        if (!LlamaCppComputeStabilityStore.lowMemoryUmaClassHost()) {
            return new int[]{0, 0};
        }
        return LlamaCppComputeStabilityStore.conservativeHybridBatchGeometry(gpuLayers);
    }

    static int acceleratorHeadroomMiB() {
        long installed = totalPhysicalMemoryBytes();
        if (installed > 0L && installed <= 24L * 1024L * 1024L * 1024L) {
            // Integrated/UMA systems need more breathing room because Minecraft,
            // the compositor, llama.cpp host buffers and the GPU all share RAM.
            return 2048;
        }
        return 1536;
    }

    private static long totalPhysicalMemoryBytes() {
        try {
            java.lang.management.OperatingSystemMXBean bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
                return Math.max(0L, os.getTotalMemorySize());
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private static void addDevice(List<String> arguments, String device) {
        if (device == null || device.isBlank()) {
            return;
        }
        arguments.add("--device");
        arguments.add(device);
    }
}
