package com.spirit.koil.api.performance;

import net.minecraft.client.MinecraftClient;

import java.util.Locale;

public final class PerformanceHardwareAdvisor {
    private static PerformanceHardwareProfile cachedProfile;
    private static long cachedAtMillis;

    private PerformanceHardwareAdvisor() {
    }

    public static HardwareClass classify(MinecraftClient client) {
        PerformanceHardwareProfile profile = cachedProfile(client);
        String os = lower(profile.operatingSystem());
        String gpu = lower(profile.gpuVendor() + " " + profile.gpuRenderer());
        boolean mac = os.contains("mac");
        boolean integrated = containsAny(gpu, "intel", "uhd", "iris", "apple", "m1", "m2", "m3", "m4", "vega 3", "vega 6", "vega 8", "radeon graphics");
        boolean laptopLike = mac || lower(profile.batteryStatus()).contains("battery") || containsAny(gpu, "mobile", "laptop");
        int gpuTier = gpuTier(gpu, integrated);
        int cpuTier = profile.cpuThreads() >= 16 ? 3 : profile.cpuThreads() >= 8 ? 2 : profile.cpuThreads() >= 4 ? 1 : 0;
        int memoryTier = profile.systemMemoryMb() >= 24000 ? 3 : profile.systemMemoryMb() >= 12000 ? 2 : profile.systemMemoryMb() >= 7000 ? 1 : 0;
        int pixelCount = Math.max(1, profile.monitorWidth()) * Math.max(1, profile.monitorHeight());
        boolean highResolution = pixelCount >= 3_600_000 || profile.refreshRate() >= 144;
        int overallTier = Math.min(Math.min(gpuTier, cpuTier), memoryTier);
        return new HardwareClass(profile, mac, integrated, laptopLike, highResolution, gpuTier, cpuTier, memoryTier, overallTier);
    }

    private static PerformanceHardwareProfile cachedProfile(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (cachedProfile == null || now - cachedAtMillis > 10_000L) {
            cachedProfile = PerformanceHardwareScanner.scan(client);
            cachedAtMillis = now;
        }
        return cachedProfile;
    }

    public static String sodiumRenderAhead(HardwareClass hardware, PerformanceSnapshot snapshot) {
        if (snapshot != null && snapshot.maxFrameTimeMs() > 65.0D) {
            return "2";
        }
        if (hardware.gpuTier() <= 1 || hardware.integratedGpu()) {
            return "1";
        }
        if (hardware.gpuTier() >= 3 && hardware.cpuTier() >= 2) {
            return "3";
        }
        return "2";
    }

    public static String sodiumChunkBuilderThreads(HardwareClass hardware, PerformanceSnapshot snapshot) {
        if (hardware.cpuTier() <= 1) {
            return "1";
        }
        if (snapshot != null && (snapshot.chunkStress() > 0.55D || snapshot.maxFrameTimeMs() > 85.0D)) {
            return hardware.cpuTier() >= 3 ? "3" : "2";
        }
        return "0";
    }

    public static String sodiumLeavesQuality(HardwareClass hardware, PerformanceSnapshot snapshot) {
        if (severeRenderPressure(snapshot) || hardware.gpuTier() <= 1 || hardware.integratedGpu()) {
            return "FAST";
        }
        if (moderateRenderPressure(snapshot) || hardware.highResolutionDisplay()) {
            return "DEFAULT";
        }
        return "DEFAULT";
    }

    public static String sodiumWeatherQuality(HardwareClass hardware, PerformanceSnapshot snapshot) {
        if (severeRenderPressure(snapshot) || hardware.gpuTier() <= 1 || hardware.integratedGpu()) {
            return "FAST";
        }
        if (moderateRenderPressure(snapshot)) {
            return "DEFAULT";
        }
        return "DEFAULT";
    }

    public static String sodiumQuality(HardwareClass hardware, PerformanceSnapshot snapshot) {
        return sodiumLeavesQuality(hardware, snapshot);
    }

    private static boolean severeRenderPressure(PerformanceSnapshot snapshot) {
        return snapshot != null && (snapshot.averageFps() < 35.0D
                || snapshot.onePercentLowFps() < 24.0D
                || snapshot.maxFrameTimeMs() > 110.0D
                || snapshot.shaderPressure() > 0.70D);
    }

    private static boolean moderateRenderPressure(PerformanceSnapshot snapshot) {
        return snapshot != null && (snapshot.primaryBottleneck() == PerformanceBottleneck.GPU
                || snapshot.primaryBottleneck() == PerformanceBottleneck.SHADER_RENDER
                || snapshot.shaderPressure() > 0.45D
                || snapshot.maxFrameTimeMs() > 75.0D);
    }

    public static boolean reduceResolutionOnMac(HardwareClass hardware) {
        return hardware.macOs() && (hardware.integratedGpu() || hardware.highResolutionDisplay() || hardware.laptopLike());
    }

    public static String sodiumExtraCloudDistance(HardwareClass hardware, PerformanceSnapshot snapshot) {
        if (snapshot != null && (snapshot.shaderPressure() > 0.35D || snapshot.primaryBottleneck() == PerformanceBottleneck.SHADER_RENDER)) {
            return "32";
        }
        if (hardware.integratedGpu() || hardware.gpuTier() <= 1 || hardware.highResolutionDisplay()) {
            return "48";
        }
        if (hardware.gpuTier() >= 3) {
            return "96";
        }
        return "64";
    }

    public static String entityCullingTracingDistance(HardwareClass hardware, PerformanceSnapshot snapshot) {
        if (snapshot != null && (snapshot.entityCount() > 220 || snapshot.primaryBottleneck() == PerformanceBottleneck.ENTITY_TICK)) {
            return "80";
        }
        if (hardware.integratedGpu() || hardware.gpuTier() <= 1) {
            return "72";
        }
        if (hardware.gpuTier() >= 3 && hardware.cpuTier() >= 2) {
            return "128";
        }
        return "96";
    }

    public static String entityCullingHitboxLimit(HardwareClass hardware, PerformanceSnapshot snapshot) {
        if (snapshot != null && snapshot.entityCount() > 220) {
            return "32";
        }
        if (hardware.cpuTier() <= 1 || hardware.gpuTier() <= 1) {
            return "32";
        }
        return hardware.overallTier() >= 3 ? "64" : "40";
    }

    private static int gpuTier(String gpu, boolean integrated) {
        if (containsAny(gpu, "rtx 4090", "rtx 4080", "rtx 4070", "rtx 3090", "rtx 3080", "rx 7900", "rx 7800", "rx 6900", "rx 6800")) {
            return 3;
        }
        if (containsAny(gpu, "rtx 4060", "rtx 3070", "rtx 3060", "rtx 2080", "rtx 2070", "rx 7700", "rx 7600", "rx 6700", "rx 6600", "arc a7")) {
            return 2;
        }
        if (containsAny(gpu, "gtx 1660", "gtx 1650", "gtx 1060", "gtx 1050", "rx 580", "rx 570", "rx 560", "arc a3")) {
            return 1;
        }
        return integrated ? 1 : 2;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public record HardwareClass(
            PerformanceHardwareProfile profile,
            boolean macOs,
            boolean integratedGpu,
            boolean laptopLike,
            boolean highResolutionDisplay,
            int gpuTier,
            int cpuTier,
            int memoryTier,
            int overallTier
    ) {
    }
}
