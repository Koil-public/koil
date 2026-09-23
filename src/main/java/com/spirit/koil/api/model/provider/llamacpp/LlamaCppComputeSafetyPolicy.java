package com.spirit.koil.api.model.provider.llamacpp;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Conservative pre-launch guard for exact hybrid GPU layer placement. */
final class LlamaCppComputeSafetyPolicy {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private LlamaCppComputeSafetyPolicy() {
    }

    static Assessment assess(Path modelFile, int requestedGpuLayers) {
        return assess(modelFile, requestedGpuLayers, "");
    }

    static Assessment assess(Path modelFile, int requestedGpuLayers, String device) {
        if (modelFile == null || requestedGpuLayers <= 0) return Assessment.allowed(-1, requestedGpuLayers, 0L, 0L, 0L);
        long available = availablePhysicalMemory();
        Assessment geometry = assessKnownGeometry(
                fileSize(modelFile),
                modelLayerCount(modelFile),
                requestedGpuLayers,
                available,
                totalPhysicalMemory());
        if (!geometry.allowed()) return geometry;

        if (LlamaCppComputeStabilityStore.exactIntegrityBlocked(modelFile, device, requestedGpuLayers)) {
            LlamaCppComputeStabilityStore.Envelope envelope = LlamaCppComputeStabilityStore.envelope(modelFile, device);
            int recommended = LlamaCppComputeStabilityStore.recommendedStableMaximum(modelFile, device, available);
            return Assessment.rejected(
                    geometry.modelLayers(),
                    requestedGpuLayers,
                    geometry.estimatedGpuBytes(),
                    geometry.availableMemoryBytes(),
                    geometry.reservedMemoryBytes(),
                    recommended,
                    "this exact layer split previously failed the accelerator inference-integrity canary: "
                            + envelope.summary());
        }

        int blockedAt = LlamaCppComputeStabilityStore.blockedAtOrAbove(modelFile, device, available);
        if (blockedAt > 0 && requestedGpuLayers >= blockedAt) {
            int recommended = LlamaCppComputeStabilityStore.recommendedStableMaximum(modelFile, device, available);
            LlamaCppComputeStabilityStore.Envelope envelope = LlamaCppComputeStabilityStore.envelope(modelFile, device);
            return Assessment.rejected(
                    geometry.modelLayers(),
                    requestedGpuLayers,
                    geometry.estimatedGpuBytes(),
                    geometry.availableMemoryBytes(),
                    geometry.reservedMemoryBytes(),
                    recommended,
                    "known startup-pressure boundary under similar memory headroom: " + envelope.summary());
        }
        return geometry;
    }

    static Assessment assessKnownGeometry(
            long modelBytes,
            int modelLayers,
            int requestedGpuLayers,
            long availableMemoryBytes,
            long totalMemoryBytes
    ) {
        if (requestedGpuLayers <= 0) return Assessment.allowed(modelLayers, requestedGpuLayers, 0L, availableMemoryBytes, 0L);

        // Keep the exact-hybrid preflight aligned with the headroom that is
        // actually passed to llama.cpp via --fit-target. r26 added a second
        // 4 GiB UMA reserve on top of the 2 GiB accelerator headroom, which
        // made any Steam Deck session with <4 GiB MemAvailable fail before
        // process startup regardless of how small the requested layer split was.
        //
        // For fixed HYBRID, KV and op offload are disabled, so the incremental
        // accelerator pressure is the requested model-layer residency itself.
        // Requiring that residency to still leave acceleratorHeadroomMiB()
        // available is conservative without making low-memory-but-healthy UMA
        // sessions impossible.
        long reserve = LlamaCppComputeArguments.acceleratorHeadroomMiB() * MIB;
        if (modelLayers <= 0 || modelBytes <= 0L || availableMemoryBytes <= 0L) {
            // Unknown geometry: do not invent a veto. Runtime fit/canary remain authoritative.
            return Assessment.allowed(modelLayers, requestedGpuLayers, 0L, availableMemoryBytes, reserve);
        }

        // GGUF size is a useful lower-order approximation of weight residency. Add
        // 35% for non-repeating tensors, allocator/scratch overhead and backend copies.
        double fraction = Math.min(1.0D, requestedGpuLayers / (double)Math.max(1, modelLayers));
        long estimatedGpuBytes = (long)Math.ceil(modelBytes * fraction * 1.35D) + 256L * MIB;
        long usable = Math.max(0L, availableMemoryBytes - reserve);
        if (estimatedGpuBytes > usable) {
            int safeLayers = Math.max(0, (int)Math.floor(
                    (usable <= 256L * MIB ? 0.0D : (usable - 256L * MIB) / (modelBytes * 1.35D)) * modelLayers));
            safeLayers = Math.min(Math.max(0, safeLayers), Math.max(0, modelLayers - 1));
            return Assessment.rejected(modelLayers, requestedGpuLayers, estimatedGpuBytes, availableMemoryBytes, reserve, safeLayers,
                    "requested hybrid split would consume reserved UMA/host memory headroom");
        }
        return Assessment.allowed(modelLayers, requestedGpuLayers, estimatedGpuBytes, availableMemoryBytes, reserve);
    }


    static long currentAvailableMemoryBytes() {
        return availablePhysicalMemory();
    }

    static RuntimePressure runtimePressure() {
        long total = totalPhysicalMemory();
        long available = availablePhysicalMemory();
        long floor = total > 0L && total <= 24L * GIB
                ? 1024L * MIB
                : 768L * MIB;
        boolean critical = available > 0L && available < floor;
        return new RuntimePressure(critical, available, floor);
    }

    private static int modelLayerCount(Path modelFile) {
        try {
            Map<String, String> metadata = GgufMetadataInspector.inspect(modelFile).metadata();
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(java.util.Locale.ROOT);
                if (!key.endsWith(".block_count") && !key.endsWith(".layer_count")) continue;
                int parsed = Integer.parseInt(entry.getValue().trim());
                if (parsed > 0) return parsed + 1; // output layer is separately offloadable in llama.cpp telemetry
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static long totalPhysicalMemory() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof OperatingSystemMXBean os) return Math.max(0L, os.getTotalMemorySize());
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private static long availablePhysicalMemory() {
        // Linux/SteamOS exposes MemAvailable, which is a better pressure signal
        // than raw MemFree because reclaimable page cache is included.
        try {
            Path meminfo = Path.of("/proc/meminfo");
            if (Files.isRegularFile(meminfo)) {
                for (String line : Files.readAllLines(meminfo)) {
                    if (!line.startsWith("MemAvailable:")) continue;
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        long kib = Long.parseLong(parts[1]);
                        if (kib > 0L) return kib * 1024L;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof OperatingSystemMXBean os) return Math.max(0L, os.getFreeMemorySize());
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    record RuntimePressure(boolean critical, long availableBytes, long hardFloorBytes) {
        String summary() {
            return "critical=" + critical
                    + " | available_mib=" + Math.max(0L, availableBytes) / MIB
                    + " | hard_floor_mib=" + Math.max(0L, hardFloorBytes) / MIB;
        }
    }

    record Assessment(
            boolean allowed,
            int modelLayers,
            int requestedGpuLayers,
            long estimatedGpuBytes,
            long availableMemoryBytes,
            long reservedMemoryBytes,
            int recommendedMaximumLayers,
            String detail
    ) {
        Assessment {
            detail = detail == null ? "" : detail.strip();
        }

        static Assessment allowed(int modelLayers, int requestedGpuLayers, long estimatedGpuBytes, long free, long reserve) {
            return new Assessment(true, modelLayers, requestedGpuLayers, estimatedGpuBytes, free, reserve, -1, "within reserved headroom");
        }

        static Assessment rejected(int modelLayers, int requestedGpuLayers, long estimatedGpuBytes, long free, long reserve, int recommended, String detail) {
            return new Assessment(false, modelLayers, requestedGpuLayers, estimatedGpuBytes, free, reserve, recommended, detail);
        }

        String summary() {
            return "allowed=" + allowed
                    + " | requested_gpu_layers=" + requestedGpuLayers
                    + " | model_layers=" + modelLayers
                    + " | estimated_gpu_mib=" + mib(estimatedGpuBytes)
                    + " | available_mib=" + mib(availableMemoryBytes)
                    + " | reserve_mib=" + mib(reservedMemoryBytes)
                    + (recommendedMaximumLayers >= 0 ? " | recommended_max_layers=" + recommendedMaximumLayers : "")
                    + " | " + detail;
        }

        private static long mib(long bytes) {
            return Math.max(0L, bytes) / MIB;
        }
    }
}
