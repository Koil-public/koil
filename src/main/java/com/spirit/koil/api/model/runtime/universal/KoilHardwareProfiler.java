package com.spirit.koil.api.model.runtime.universal;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/** Portable hardware profiler. Provider/runtime-specific probes can append accelerator observations. */
public final class KoilHardwareProfiler {
    private KoilHardwareProfiler() {}

    public static KoilHardwareProfile capture(String runtimeId, Path runtimeExecutable) {
        return capture(runtimeId, runtimeExecutable, List.of());
    }

    public static KoilHardwareProfile capture(String runtimeId, Path runtimeExecutable, List<KoilAcceleratorProfile> accelerators) {
        String osName = System.getProperty("os.name", "unknown");
        String os = osName + " " + System.getProperty("os.version", "");
        String arch = System.getProperty("os.arch", "unknown");
        int logical = Runtime.getRuntime().availableProcessors();
        long total = Runtime.getRuntime().maxMemory();
        long free = Runtime.getRuntime().freeMemory();
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof OperatingSystemMXBean extended) {
                total = Math.max(0L, extended.getTotalMemorySize());
                free = Math.max(0L, extended.getFreeMemorySize());
            }
        } catch (Throwable ignored) {}
        KoilRuntimeArtifactInventory inventory = KoilRuntimeArtifactInventory.inspect(runtimeId, runtimeExecutable);
        String fingerprint = fingerprint(osName, arch, logical, total, Set.of());
        return new KoilHardwareProfile(fingerprint, os.trim(), arch, logical, 0, total, free,
                Set.of(), accelerators, inventory);
    }


    public static KoilHardwareProfile reconcileRuntimeObservations(
            KoilHardwareProfile base, Map<String, String> diagnostics) {
        if (base == null || diagnostics == null || diagnostics.isEmpty()) return base;
        String placement = diagnostics.getOrDefault("actualComputePlacement", "").toLowerCase(Locale.ROOT);
        if (!(placement.contains("gpu") || placement.contains("hybrid") || placement.contains("accelerator"))) {
            return base;
        }
        String device = diagnostics.getOrDefault("resolvedComputeDevice", "").strip();
        KoilRuntimeBackend backend = backendFrom(device);
        if (backend == KoilRuntimeBackend.UNKNOWN && base.runtimeInventory() != null) {
            List<KoilRuntimeBackend> candidates = base.runtimeInventory().compiledBackends().stream()
                    .filter(v -> v != KoilRuntimeBackend.CPU && v != KoilRuntimeBackend.UNKNOWN)
                    .toList();
            if (candidates.size() == 1) backend = candidates.get(0);
        }
        if (backend == KoilRuntimeBackend.UNKNOWN) return base;
        List<KoilAcceleratorProfile> accelerators = new ArrayList<>(base.accelerators());
        KoilRuntimeBackend observedBackend = backend;
        boolean already = accelerators.stream().anyMatch(a -> a.backend() == observedBackend
                && (device.isBlank() || a.id().equalsIgnoreCase(device)));
        if (!already) {
            boolean unified = backend == KoilRuntimeBackend.METAL
                    || diagnostics.getOrDefault("resolvedComputeDevice", "").toLowerCase(Locale.ROOT).contains("uma");
            accelerators.add(new KoilAcceleratorProfile(
                    device.isBlank() ? backend.name() : device, backend,
                    diagnostics.getOrDefault("resolvedComputeDevice", device), 0L, unified, unified,
                    "native runtime reported placement=" + placement));
        }
        // Hardware identity must remain stable across CPU/GPU policy changes and
        // late accelerator discovery. Runtime observations are transient planning
        // evidence, not part of the machine fingerprint.
        String fingerprint = base.fingerprint();
        return new KoilHardwareProfile(fingerprint, base.operatingSystem(), base.architecture(),
                base.logicalCpuCount(), base.physicalCpuCount(), base.installedMemoryBytes(),
                base.availableMemoryBytes(), base.cpuFeatures(), accelerators, base.runtimeInventory());
    }

    public static KoilRuntimeBackend backendFromEvidence(String value) {
        return backendFrom(value);
    }

    static KoilRuntimeBackend backendFrom(String value) {
        String v = safe(value).toLowerCase(Locale.ROOT);
        if (v.startsWith("mtl") || v.contains("metal")) return KoilRuntimeBackend.METAL;
        if (v.contains("vulkan")) return KoilRuntimeBackend.VULKAN;
        if (v.contains("cuda") || v.contains("nvidia")) return KoilRuntimeBackend.CUDA;
        if (v.contains("rocm")) return KoilRuntimeBackend.ROCM;
        if (v.contains("hip")) return KoilRuntimeBackend.HIP;
        if (v.contains("sycl")) return KoilRuntimeBackend.SYCL;
        if (v.contains("opencl")) return KoilRuntimeBackend.OPENCL;
        return KoilRuntimeBackend.UNKNOWN;
    }

    static String fingerprint(String os, String arch, int logical, long total, Set<KoilRuntimeBackend> backends) {
        String stable = safe(os).toLowerCase(Locale.ROOT) + "|" + safe(arch).toLowerCase(Locale.ROOT)
                + "|" + logical + "|" + total + "|" + backends.stream().sorted().toList();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(stable.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (Exception ignored) {
            return Integer.toUnsignedString(stable.hashCode(), 16);
        }
    }

    private static String safe(String value) { return value == null ? "" : value.strip(); }
}
