package com.spirit.koil.api.performance;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.client.option.ParticlesMode;
import net.minecraft.util.MetricsData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Minecraft-version-facing reader for performance data consumed by Koil's
 * diagnostics, optimizer, and video-settings surfaces.
 */
final class MinecraftPerformanceDataReader {
    private static final double NANOS_PER_MILLISECOND = 1_000_000.0D;

    private MinecraftPerformanceDataReader() {
    }

    static FrameMetrics frameMetrics(MinecraftClient client) {
        int currentFps = currentFps(client);
        if (client == null) {
            return FrameMetrics.fallback(currentFps);
        }
        try {
            MetricsData metrics = client.getMetricsData();
            long[] rawSamples = metrics.getSamples();
            if (rawSamples == null || rawSamples.length == 0) {
                return FrameMetrics.fallback(currentFps);
            }
            int start = Math.floorMod(metrics.getStartIndex(), rawSamples.length);
            int end = Math.floorMod(metrics.getCurrentIndex(), rawSamples.length);
            List<Double> frameTimes = new ArrayList<>(rawSamples.length);
            int index = start;
            int guard = 0;
            while (index != end && guard++ < rawSamples.length) {
                long sample = rawSamples[index];
                if (sample > 0L) {
                    frameTimes.add(sample / NANOS_PER_MILLISECOND);
                }
                index = (index + 1) % rawSamples.length;
            }
            int latestIndex = Math.floorMod(end - 1, rawSamples.length);
            double latestMs = rawSamples[latestIndex] > 0L
                    ? rawSamples[latestIndex] / NANOS_PER_MILLISECOND
                    : derivedFrameTime(currentFps);
            if (frameTimes.isEmpty()) {
                return new FrameMetrics(currentFps, latestMs, latestMs, currentFps, "fps-derived");
            }
            double maxMs = frameTimes.stream().mapToDouble(Double::doubleValue).max().orElse(latestMs);
            double onePercentLow = onePercentLowFps(frameTimes, currentFps);
            return new FrameMetrics(currentFps, latestMs, maxMs, onePercentLow, "minecraft-frame-metrics");
        } catch (Throwable ignored) {
            return FrameMetrics.fallback(currentFps);
        }
    }

    static OptionMetrics options(MinecraftClient client) {
        if (client == null || client.options == null) {
            return OptionMetrics.unavailable();
        }
        try {
            Integer renderDistance = client.options.getViewDistance().getValue();
            Integer simulationDistance = client.options.getSimulationDistance().getValue();
            Integer maxFps = client.options.getMaxFps().getValue();
            Double entityDistance = client.options.getEntityDistanceScaling().getValue();
            Integer mipmaps = client.options.getMipmapLevels().getValue();
            ParticlesMode particles = client.options.getParticles().getValue();
            GraphicsMode graphics = client.options.getGraphicsMode().getValue();
            CloudRenderMode clouds = client.options.getCloudRenderMode().getValue();
            Boolean smoothLighting = client.options.getAo().getValue();
            Integer biomeBlend = client.options.getBiomeBlendRadius().getValue();
            Boolean entityShadows = client.options.getEntityShadows().getValue();
            Boolean vsync = client.options.getEnableVsync().getValue();
            if (renderDistance == null
                    || simulationDistance == null
                    || maxFps == null
                    || entityDistance == null
                    || mipmaps == null
                    || particles == null
                    || graphics == null
                    || clouds == null
                    || smoothLighting == null
                    || biomeBlend == null
                    || entityShadows == null
                    || vsync == null) {
                return OptionMetrics.unavailable();
            }
            return new OptionMetrics(
                    renderDistance,
                    simulationDistance,
                    maxFps,
                    entityDistance,
                    mipmaps,
                    enumName(particles),
                    enumName(graphics),
                    enumName(clouds),
                    smoothLighting,
                    biomeBlend,
                    entityShadows,
                    vsync,
                    true
            );
        } catch (Throwable ignored) {
            return OptionMetrics.unavailable();
        }
    }

    private static int currentFps(MinecraftClient client) {
        try {
            return client == null ? 0 : Math.max(0, client.getCurrentFps());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static double derivedFrameTime(int fps) {
        return fps <= 0 ? 0.0D : 1_000.0D / fps;
    }

    private static double onePercentLowFps(List<Double> frameTimes, int fallbackFps) {
        if (frameTimes.isEmpty()) {
            return fallbackFps;
        }
        List<Double> sorted = frameTimes.stream()
                .filter(value -> value != null && value > 0.0D && Double.isFinite(value))
                .sorted(Comparator.naturalOrder())
                .toList();
        if (sorted.isEmpty()) {
            return fallbackFps;
        }
        int percentileIndex = Math.max(0, Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.99D) - 1));
        double slowFrameMs = sorted.get(percentileIndex);
        return slowFrameMs <= 0.0D ? fallbackFps : 1_000.0D / slowFrameMs;
    }

    private static String enumName(Enum<?> value) {
        return value == null ? "unavailable" : value.name().toLowerCase(java.util.Locale.ROOT);
    }

    record FrameMetrics(
            int currentFps,
            double latestFrameTimeMs,
            double maxFrameTimeMs,
            double onePercentLowFps,
            String source
    ) {
        private static FrameMetrics fallback(int fps) {
            double frameTime = derivedFrameTime(fps);
            return new FrameMetrics(fps, frameTime, frameTime, fps, "fps-derived");
        }
    }

    record OptionMetrics(
            int renderDistance,
            int simulationDistance,
            int maxFps,
            double entityDistanceScale,
            int mipmapLevels,
            String particlesMode,
            String graphicsMode,
            String cloudsMode,
            boolean smoothLighting,
            int biomeBlend,
            boolean entityShadows,
            boolean vsync,
            boolean available
    ) {
        private static OptionMetrics unavailable() {
            return new OptionMetrics(
                    0,
                    0,
                    0,
                    1.0D,
                    0,
                    "unavailable",
                    "unavailable",
                    "unavailable",
                    false,
                    0,
                    false,
                    false,
                    false
            );
        }
    }
}
