package com.spirit.koil.api.performance;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public final class PerformanceMonitor {
    private static final int SAMPLE_LIMIT = 240;
    private static final Deque<Sample> SAMPLES = new ArrayDeque<>();
    private static PerformanceSnapshot latestSnapshot;
    private static String latestWarning = "";
    private static long latestWarningAt;
    private static long lastGcCount;
    private static long lastGcTimeMs;

    private PerformanceMonitor() {
    }

    public static void tick(MinecraftClient client) {
        if (client == null) {
            return;
        }
        MinecraftPerformanceDataReader.FrameMetrics frameMetrics = MinecraftPerformanceDataReader.frameMetrics(client);
        double frameMs = frameMetrics.latestFrameTimeMs();
        int fps = frameMetrics.currentFps();
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / 1024L / 1024L;
        long max = Math.max(1L, runtime.maxMemory() / 1024L / 1024L);
        GcStats gc = gcDelta();
        int entityCount = countEntities(client);
        MinecraftPerformanceDataReader.OptionMetrics options = MinecraftPerformanceDataReader.options(client);
        int renderDistance = options.renderDistance();
        PerformanceRuntimeContext context = PerformanceRuntimeContextService.capture(client);
        double chunkStress = chunkStress(frameMs, renderDistance, context.worldType());
        boolean screenOpen = client.currentScreen != null;
        double uiFramePressure = screenOpen ? Math.min(1.0D, Math.max(0.0D, (frameMs - 18.0D) / 90.0D)) : 0.0D;
        double shaderPressure = !screenOpen && !"none detected".equals(context.shaderState()) ? Math.min(1.0D, (120.0D - Math.max(0.0D, fps)) / 90.0D) : 0.0D;
        double modLoadPressure = Math.min(1.0D, FabricLoader.getInstance().getAllMods().size() / 220.0D);
        double resourcePackPressure = Math.min(1.0D, context.resourcePackCount() / 12.0D);
        SAMPLES.addLast(new Sample(System.currentTimeMillis(), fps, frameMs, used, max, entityCount, gc.countDelta(), gc.timeDeltaMs(), round(chunkStress), round(shaderPressure), round(uiFramePressure), round(modLoadPressure), round(resourcePackPressure), context.worldType()));
        while (SAMPLES.size() > SAMPLE_LIMIT) {
            SAMPLES.removeFirst();
        }
        latestSnapshot = buildSnapshot(client);
        updateWarning(latestSnapshot);
    }

    public static PerformanceSnapshot latestSnapshot(MinecraftClient client) {
        if (latestSnapshot == null) {
            latestSnapshot = buildSnapshot(client);
        }
        return latestSnapshot;
    }

    public static PerformanceSnapshot freshSnapshot(MinecraftClient client) {
        latestSnapshot = buildSnapshot(client);
        updateWarning(latestSnapshot);
        return latestSnapshot;
    }

    public static PerformanceSnapshot snapshotSince(MinecraftClient client, long startMillis) {
        if (SAMPLES.isEmpty()) {
            return latestSnapshot(client);
        }
        Deque<Sample> filtered = new ArrayDeque<>();
        for (Sample sample : SAMPLES) {
            if (sample.capturedAtMillis() >= startMillis) {
                filtered.addLast(sample);
            }
        }
        if (filtered.isEmpty()) {
            return latestSnapshot(client);
        }
        return buildSnapshotFromSamples(client, filtered);
    }

    public static String latestWarning() {
        return latestWarning;
    }

    public static Deque<Sample> samples() {
        return new ArrayDeque<>(SAMPLES);
    }

    static PerformanceSnapshot buildSnapshot(MinecraftClient client) {
        return buildSnapshotFromSamples(client, SAMPLES);
    }

    private static PerformanceSnapshot buildSnapshotFromSamples(MinecraftClient client, Deque<Sample> samples) {
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / 1024L / 1024L;
        long max = Math.max(1L, runtime.maxMemory() / 1024L / 1024L);
        double memoryPressure = Math.min(1.0D, used / (double) max);
        MinecraftPerformanceDataReader.FrameMetrics frameMetrics = MinecraftPerformanceDataReader.frameMetrics(client);
        int fps = frameMetrics.currentFps();
        double averageFps = samples.stream().mapToInt(Sample::fps).average().orElse(fps);
        double onePercentLow = percentileLowFps(samples);
        double frameMs = samples.peekLast() == null ? frameMetrics.latestFrameTimeMs() : samples.peekLast().frameTimeMs();
        double maxFrameMs = Math.max(frameMetrics.maxFrameTimeMs(), samples.stream().mapToDouble(Sample::frameTimeMs).max().orElse(frameMs));
        MinecraftPerformanceDataReader.OptionMetrics options = MinecraftPerformanceDataReader.options(client);
        int renderDistance = options.renderDistance();
        int simulationDistance = options.simulationDistance();
        int maxFps = options.maxFps();
        double entityDistanceScale = options.entityDistanceScale();
        int mipmapLevels = options.mipmapLevels();
        String particlesMode = options.particlesMode();
        String graphicsMode = options.graphicsMode();
        String cloudsMode = options.cloudsMode();
        boolean smoothLighting = options.smoothLighting();
        int biomeBlend = options.biomeBlend();
        boolean entityShadows = options.entityShadows();
        boolean vsync = options.vsync();
        boolean shaderInstalled = FabricLoader.getInstance().isModLoaded("iris") || FabricLoader.getInstance().isModLoaded("oculus");
        int entityCount = countEntities(client);
        PerformanceRuntimeContext context = PerformanceRuntimeContextService.capture(client);
        double gcPressure = Math.min(1.0D, samples.stream().mapToLong(Sample::gcTimeMs).sum() / 250.0D);
        double chunkStress = chunkStress(maxFrameMs, renderDistance, context.worldType());
        boolean screenOpen = client != null && client.currentScreen != null;
        double shaderPressure = !screenOpen && (shaderInstalled || !"none detected".equals(context.shaderState())) ? Math.min(1.0D, (120.0D - Math.max(0.0D, averageFps)) / 90.0D) : 0.0D;
        double uiFramePressure = samples.stream().mapToDouble(Sample::uiFramePressure).max().orElse(screenOpen ? Math.min(1.0D, Math.max(0.0D, (frameMs - 18.0D) / 90.0D)) : 0.0D);
        double modLoadPressure = Math.min(1.0D, FabricLoader.getInstance().getAllMods().size() / 220.0D);
        double resourcePackPressure = Math.min(1.0D, context.resourcePackCount() / 12.0D);
        PerformanceBottleneck bottleneck = classify(fps, averageFps, maxFrameMs, memoryPressure, entityCount, gcPressure, chunkStress, shaderPressure, uiFramePressure, modLoadPressure);
        String likelyCause = likelyCause(bottleneck, context);
        return new PerformanceSnapshot(
                System.currentTimeMillis(),
                fps,
                round(averageFps),
                round(onePercentLow),
                round(frameMs),
                round(maxFrameMs),
                used,
                max,
                round(memoryPressure),
                entityCount,
                FabricLoader.getInstance().getAllMods().size(),
                context.resourcePackCount(),
                context.optimizationModConfigs().size(),
                renderDistance,
                simulationDistance,
                maxFps,
                round(entityDistanceScale),
                mipmapLevels,
                particlesMode,
                graphicsMode,
                cloudsMode,
                smoothLighting,
                biomeBlend,
                entityShadows,
                vsync,
                shaderInstalled,
                round(gcPressure),
                round(chunkStress),
                round(shaderPressure),
                round(uiFramePressure),
                round(modLoadPressure),
                round(resourcePackPressure),
                context.worldType(),
                likelyCause,
                bottleneck
        );
    }

    private static int countEntities(MinecraftClient client) {
        try {
            if (client == null || client.world == null) {
                return 0;
            }
            int count = 0;
            for (Entity ignored : client.world.getEntities()) {
                count++;
            }
            return count;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static double percentileLowFps(Deque<Sample> samples) {
        if (samples.isEmpty()) {
            return 0.0D;
        }
        double[] frameTimes = samples.stream()
                .mapToDouble(Sample::frameTimeMs)
                .filter(value -> value > 0.0D && Double.isFinite(value))
                .sorted()
                .toArray();
        if (frameTimes.length == 0) {
            return samples.peekLast() == null ? 0.0D : samples.peekLast().fps();
        }
        int index = Math.max(0, Math.min(frameTimes.length - 1, (int) Math.ceil(frameTimes.length * 0.99D) - 1));
        return 1_000.0D / frameTimes[index];
    }

    private static PerformanceBottleneck classify(int fps, double averageFps, double maxFrameMs, double memoryPressure, int entityCount, double gcPressure, double chunkStress, double shaderPressure, double uiFramePressure, double modLoadPressure) {
        if (memoryPressure > 0.90D || gcPressure > 0.70D) {
            return PerformanceBottleneck.MEMORY;
        }
        if (modLoadPressure > 0.90D && memoryPressure > 0.75D) {
            return PerformanceBottleneck.MOD_OVERLOAD;
        }
        if (chunkStress > 0.70D || maxFrameMs > 120.0D) {
            return PerformanceBottleneck.CHUNK_STORAGE;
        }
        if (entityCount > 180 && averageFps < 50.0D) {
            return PerformanceBottleneck.ENTITY_TICK;
        }
        if (shaderPressure > 0.45D && averageFps > 0.0D && averageFps < 60.0D) {
            return PerformanceBottleneck.SHADER_RENDER;
        }
        if (uiFramePressure > 0.70D && averageFps > 0.0D && averageFps < 60.0D) {
            return PerformanceBottleneck.GPU;
        }
        if (fps > 0 && averageFps < 45.0D) {
            return PerformanceBottleneck.GPU;
        }
        if (memoryPressure > 0.80D || maxFrameMs > 75.0D) {
            return PerformanceBottleneck.CPU;
        }
        return PerformanceBottleneck.HEALTHY;
    }

    private static double chunkStress(double maxFrameMs, int renderDistance, String worldType) {
        if (!"singleplayer".equals(worldType) && !"server".equals(worldType)) {
            return 0.0D;
        }
        double frameComponent = Math.min(1.0D, Math.max(0.0D, (maxFrameMs - 45.0D) / 120.0D));
        double distanceComponent = Math.min(1.0D, Math.max(0.0D, (renderDistance - 10.0D) / 18.0D));
        double worldComponent = "server".equals(worldType) ? 0.15D : 0.0D;
        return Math.min(1.0D, frameComponent + distanceComponent * 0.35D + worldComponent);
    }

    private static String likelyCause(PerformanceBottleneck bottleneck, PerformanceRuntimeContext context) {
        return switch (bottleneck) {
            case MEMORY -> "Memory pressure or garbage collection is the strongest signal.";
            case CHUNK_STORAGE -> "Frame spikes line up with chunk/render-distance pressure.";
            case SHADER_RENDER -> "Shader/render pipeline is probably the active limiter.";
            case ENTITY_TICK -> "Entity count and simulation work are likely causing pressure.";
            case MOD_OVERLOAD -> "Loaded mod count and memory pressure suggest modpack overload risk.";
            case GPU -> "Rendering workload is probably limiting frame rate.";
            case CPU -> "CPU-side simulation, tick, or game logic pressure is likely.";
            case HEALTHY -> "No clear limiter. Keep current settings unless the area changes.";
            default -> context.modpackImpactNotes().isEmpty() ? "Not enough data yet." : String.join(" ", context.modpackImpactNotes());
        };
    }

    private static GcStats gcDelta() {
        long count = 0L;
        long time = 0L;
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean bean : beans) {
            if (bean.getCollectionCount() > 0) {
                count += bean.getCollectionCount();
            }
            if (bean.getCollectionTime() > 0) {
                time += bean.getCollectionTime();
            }
        }
        long countDelta = lastGcCount == 0L ? 0L : Math.max(0L, count - lastGcCount);
        long timeDelta = lastGcTimeMs == 0L ? 0L : Math.max(0L, time - lastGcTimeMs);
        lastGcCount = count;
        lastGcTimeMs = time;
        return new GcStats(countDelta, timeDelta);
    }

    private static void updateWarning(PerformanceSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        String warning = buildWarning(snapshot);
        long now = System.currentTimeMillis();
        if (warning.isBlank()) {
            if (!latestWarning.isBlank() && now - latestWarningAt > 10_000L) {
                latestWarning = "";
                latestWarningAt = now;
            }
            return;
        }
        if (!warning.equals(latestWarning) || now - latestWarningAt > 30_000L) {
            latestWarning = warning;
            latestWarningAt = now;
        }
    }

    private static String buildWarning(PerformanceSnapshot snapshot) {
        Sample latest = SAMPLES.peekLast();
        double averageFps = recentAverageFps(120);
        double averageFrameMs = recentAverageFrameMs(120);
        double recentMaxFrameMs = recentMaxFrameMs(120);
        double maxFrameMs = Math.max(snapshot.maxFrameTimeMs(), recentMaxFrameMs);
        double latestFrameMs = latest == null ? 0.0D : latest.frameTimeMs();
        int fps = latest == null ? 0 : latest.fps();
        int entityCount = latest == null ? 0 : latest.entityCount();
        double chunkStress = latest == null ? 0.0D : latest.chunkStress();
        double shaderPressure = latest == null ? 0.0D : latest.shaderPressure();
        double uiFramePressure = latest == null ? 0.0D : latest.uiFramePressure();
        double modLoadPressure = latest == null ? 0.0D : latest.modLoadPressure();
        double resourcePackPressure = latest == null ? 0.0D : latest.resourcePackPressure();
        long recentGcTimeMs = recentGcTimeMs(120);
        long recentGcCount = recentGcCount(120);
        double memoryPressure = snapshot.memoryPressure();

        if (memoryPressure >= 0.95D) {
            return "Critical memory pressure (" + percent(memoryPressure) + ")";
        }
        if (memoryPressure > 0.90D && recentGcTimeMs > 200L) {
            return "High memory pressure with GC churn (" + percent(memoryPressure) + ")";
        }
        if (memoryPressure > 0.90D) {
            return "High memory pressure (" + percent(memoryPressure) + ")";
        }
        if (recentGcTimeMs > 700L || recentGcCount > 20L) {
            return "Garbage collection churn (" + recentGcTimeMs + " ms)";
        }
        if (maxFrameMs > 250.0D) {
            return "Severe frame hitching (" + whole(maxFrameMs) + " ms spike)";
        }
        if (maxFrameMs > 120.0D) {
            return "Heavy frame spikes (" + whole(maxFrameMs) + " ms spike)";
        }
        if (fps > 0 && averageFps > 0.0D && averageFps < 25.0D) {
            return "Sustained very low FPS (" + whole(averageFps) + " avg)";
        }
        if (fps > 0 && averageFps > 0.0D && fps < averageFps * 0.55D && latestFrameMs > 45.0D) {
            return "Sudden FPS drop (" + fps + " FPS)";
        }
        if (("singleplayer".equals(snapshot.worldType()) || "server".equals(snapshot.worldType())) && (chunkStress > 0.80D || snapshot.primaryBottleneck() == PerformanceBottleneck.CHUNK_STORAGE)) {
            return "Chunk/render distance stress (" + percent(chunkStress) + ")";
        }
        if (entityCount > 300 && averageFps < 60.0D) {
            return "Very high entity tick pressure (" + entityCount + " entities)";
        }
        if (entityCount > 180 && averageFps < 50.0D) {
            return "Entity simulation pressure (" + entityCount + " entities)";
        }
        if (shaderPressure > 0.65D || snapshot.primaryBottleneck() == PerformanceBottleneck.SHADER_RENDER) {
            return "Shader/render pressure (" + percent(shaderPressure) + ")";
        }
        if (uiFramePressure > 0.70D) {
            return "UI render pressure (" + percent(uiFramePressure) + ")";
        }
        if (modLoadPressure > 0.92D && memoryPressure > 0.70D) {
            return "Large modpack memory pressure (" + percent(modLoadPressure) + ")";
        }
        if (modLoadPressure > 0.92D) {
            return "Large modpack load pressure (" + percent(modLoadPressure) + ")";
        }
        if (resourcePackPressure > 0.90D && averageFps < 60.0D) {
            return "Resource pack stack pressure (" + percent(resourcePackPressure) + ")";
        }
        if (snapshot.primaryBottleneck() == PerformanceBottleneck.GPU && averageFps < 45.0D) {
            return "GPU render bottleneck (" + whole(averageFps) + " avg FPS)";
        }
        if (snapshot.primaryBottleneck() == PerformanceBottleneck.CPU && averageFrameMs > 35.0D) {
            return "CPU/tick bottleneck (" + whole(averageFrameMs) + " ms avg)";
        }
        if (snapshot.primaryBottleneck() == PerformanceBottleneck.MOD_OVERLOAD) {
            return "Modpack overload risk";
        }
        return "";
    }

    private static double recentAverageFps(int limit) {
        if (SAMPLES.isEmpty()) {
            return 0.0D;
        }
        int skip = Math.max(0, SAMPLES.size() - limit);
        int index = 0;
        int count = 0;
        long total = 0L;
        for (Sample sample : SAMPLES) {
            if (index++ < skip || sample.fps() <= 0) {
                continue;
            }
            total += sample.fps();
            count++;
        }
        return count == 0 ? 0.0D : total / (double) count;
    }

    private static double recentAverageFrameMs(int limit) {
        if (SAMPLES.isEmpty()) {
            return 0.0D;
        }
        int skip = Math.max(0, SAMPLES.size() - limit);
        int index = 0;
        int count = 0;
        double total = 0.0D;
        for (Sample sample : SAMPLES) {
            if (index++ < skip) {
                continue;
            }
            total += sample.frameTimeMs();
            count++;
        }
        return count == 0 ? 0.0D : total / count;
    }

    private static double recentMaxFrameMs(int limit) {
        if (SAMPLES.isEmpty()) {
            return 0.0D;
        }
        int skip = Math.max(0, SAMPLES.size() - limit);
        int index = 0;
        double max = 0.0D;
        for (Sample sample : SAMPLES) {
            if (index++ < skip) {
                continue;
            }
            max = Math.max(max, sample.frameTimeMs());
        }
        return max;
    }

    private static long recentGcTimeMs(int limit) {
        if (SAMPLES.isEmpty()) {
            return 0L;
        }
        int skip = Math.max(0, SAMPLES.size() - limit);
        int index = 0;
        long total = 0L;
        for (Sample sample : SAMPLES) {
            if (index++ < skip) {
                continue;
            }
            total += sample.gcTimeMs();
        }
        return total;
    }

    private static long recentGcCount(int limit) {
        if (SAMPLES.isEmpty()) {
            return 0L;
        }
        int skip = Math.max(0, SAMPLES.size() - limit);
        int index = 0;
        long total = 0L;
        for (Sample sample : SAMPLES) {
            if (index++ < skip) {
                continue;
            }
            total += sample.gcCount();
        }
        return total;
    }

    private static String percent(double value) {
        return Math.round(Math.max(0.0D, Math.min(1.0D, value)) * 100.0D) + "%";
    }

    private static String whole(double value) {
        return Long.toString(Math.round(value));
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    public record Sample(long capturedAtMillis, int fps, double frameTimeMs, long usedMemoryMb, long maxMemoryMb, int entityCount, long gcCount, long gcTimeMs, double chunkStress, double shaderPressure, double uiFramePressure, double modLoadPressure, double resourcePackPressure, String worldType) {
    }

    private record GcStats(long countDelta, long timeDeltaMs) {
    }
}
