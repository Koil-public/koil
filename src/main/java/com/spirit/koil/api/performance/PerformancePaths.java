package com.spirit.koil.api.performance;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public final class PerformancePaths {
    public static final Path ROOT = FabricLoader.getInstance().getGameDir().resolve("koil/sys/performance").toAbsolutePath().normalize();
    public static final Path HARDWARE_PROFILE = ROOT.resolve("hardware_profile.json");
    public static final Path PERFORMANCE_PROFILES = ROOT.resolve("performance_profiles.json");
    public static final Path BENCHMARK_HISTORY = ROOT.resolve("benchmark_history.json");
    public static final Path OPTIMIZATION_HISTORY = ROOT.resolve("optimization_history.json");
    public static final Path PERFORMANCE_REPORT = ROOT.resolve("performance_report.json");
    public static final Path DETECTED_BOTTLENECKS = ROOT.resolve("detected_bottlenecks.json");
    public static final Path LEARNING_OBSERVATIONS = ROOT.resolve("learning_observations.json");
    public static final Path PERFORMANCE_COLORS = ROOT.resolve("performance_colors.json");
    public static final Path BACKUPS = ROOT.resolve("backups");

    private PerformancePaths() {
    }
}
