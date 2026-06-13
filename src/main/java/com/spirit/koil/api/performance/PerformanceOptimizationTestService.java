package com.spirit.koil.api.performance;

import com.spirit.koil.api.automation.AutomationRequest;
import com.spirit.koil.api.automation.AutomationRouter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PerformanceOptimizationTestService {
    private static final long WORLD_TEST_DURATION_MS = 9000L;
    private static final long MENU_TEST_DURATION_MS = 5000L;
    private static boolean active;
    private static long startedAtMillis;
    private static long durationMillis;
    private static PerformanceProfileMode activeMode = PerformanceProfileMode.AUTO;
    private static Screen returnScreen;
    private static boolean hidHud;
    private static boolean originalHudHidden;
    private static boolean automationProbeUsed;
    private static String status = "Idle";
    private static PerformanceBenchmarkResult latestResult;
    private static long latestResultAtMillis;

    private PerformanceOptimizationTestService() {
    }

    public static boolean start(MinecraftClient client, PerformanceProfileMode mode, Screen screenToRestore) {
        if (client == null || active) {
            return false;
        }
        active = true;
        startedAtMillis = System.currentTimeMillis();
        activeMode = mode == null ? PerformanceProfileMode.AUTO : mode;
        returnScreen = screenToRestore;
        boolean inWorld = client.world != null && client.player != null;
        durationMillis = inWorld ? WORLD_TEST_DURATION_MS : MENU_TEST_DURATION_MS;
        automationProbeUsed = false;
        originalHudHidden = false;
        hidHud = false;
        status = inWorld
                ? "World benchmark running. UI hidden so Koil can sample real render cost."
                : "Benchmark running in menu/client context.";
        if (inWorld) {
            try {
                originalHudHidden = client.options.hudHidden;
                client.options.hudHidden = true;
                hidHud = true;
            } catch (Throwable ignored) {
            }
            client.setScreen(null);
            if (automationProbeAvailable()) {
                automationProbeUsed = true;
                AutomationRouter.handleInput(new AutomationRequest("walk 100 blocks", false, false), "Koil Performance");
                status = "World benchmark running with automation movement probe.";
            }
        }
        return true;
    }

    public static void tick(MinecraftClient client) {
        if (!active || client == null) {
            return;
        }
        long elapsed = System.currentTimeMillis() - startedAtMillis;
        long remaining = Math.max(0L, (durationMillis - elapsed + 999L) / 1000L);
        if (elapsed < durationMillis) {
            if (client.world != null) {
                status = automationProbeUsed
                        ? "World benchmark running with automation movement probe... " + remaining + "s"
                        : "World benchmark running with UI hidden... " + remaining + "s";
            } else {
                status = "Benchmarking client/menu pressure... " + remaining + "s";
            }
            return;
        }
        finish(client);
    }

    public static boolean active() {
        return active;
    }

    public static String status() {
        return status;
    }

    public static PerformanceBenchmarkResult latestResult() {
        return latestResult;
    }

    public static long latestResultAtMillis() {
        return latestResultAtMillis;
    }

    public static boolean automationProbeAvailable() {
        return Files.exists(Path.of("koil/automation/movement/navigation/move_relative.ktl"))
                && Files.exists(Path.of("koil/automation/movement/language/movement-grammar.ktl"));
    }

    private static void finish(MinecraftClient client) {
        List<String> notes = new ArrayList<>();
        if (hidHud) {
            notes.add("In-world UI was hidden during sampling so charts reflect world/render cost instead of Koil screen rendering.");
        }
        if (automationProbeUsed) {
            notes.add("Automation movement probe requested: walk straight 4 blocks.");
        } else if (client.world != null && client.player != null) {
            notes.add("Automation movement probe skipped because required KTL movement files were not found.");
        }
        latestResult = PerformanceBenchmarkRunner.finishBenchmark(client, activeMode, startedAtMillis, notes, hidHud, automationProbeUsed);
        latestResultAtMillis = System.currentTimeMillis();
        status = latestResult.summary();
        if (hidHud) {
            try {
                client.options.hudHidden = originalHudHidden;
            } catch (Throwable ignored) {
            }
        }
        if (automationProbeUsed) {
            AutomationRouter.stopAutomation(true);
            status = "World benchmark stopped automation movement probe.";
        }
        Screen restore = returnScreen;
        active = false;
        hidHud = false;
        returnScreen = null;
        if (restore != null && client.currentScreen == null) {
            client.setScreen(restore);
        }
    }
}
