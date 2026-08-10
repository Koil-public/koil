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
    private static boolean worldTest;
    private static long startedAtMillis;
    private static long durationMillis;
    private static long automationProbeStartedAtMillis;
    private static PerformanceProfileMode activeMode = PerformanceProfileMode.AUTO;
    private static Screen returnScreen;
    private static boolean hidHud;
    private static boolean originalHudHidden;
    private static boolean automationProbeUsed;
    private static boolean automationProbeAttempted;
    private static boolean benchmarkFrameControlsChanged;
    private static int originalMaxFps;
    private static boolean originalVsync;
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
        worldTest = client.world != null && client.player != null;
        startedAtMillis = System.currentTimeMillis();
        durationMillis = worldTest ? WORLD_TEST_DURATION_MS : MENU_TEST_DURATION_MS;
        activeMode = mode == null ? PerformanceProfileMode.AUTO : mode;
        returnScreen = screenToRestore;
        automationProbeUsed = false;
        automationProbeAttempted = false;
        automationProbeStartedAtMillis = 0L;
        originalHudHidden = false;
        hidHud = false;
        unlockFrameControls(client);
        status = worldTest
                ? "World baseline running. HUD and optimizer UI are hidden for a clean static sample."
                : "Client/menu benchmark running with the temporary frame cap removed.";
        if (worldTest) {
            try {
                originalHudHidden = client.options.hudHidden;
                client.options.hudHidden = true;
                hidHud = true;
            } catch (Throwable ignored) {
            }
            client.setScreen(null);
        }
        return true;
    }

    public static void tick(MinecraftClient client) {
        if (!active || client == null) {
            return;
        }
        long elapsed = System.currentTimeMillis() - startedAtMillis;
        long remaining = Math.max(0L, (durationMillis - elapsed + 999L) / 1000L);
        if (worldTest && elapsed >= durationMillis / 2L && !automationProbeAttempted) {
            startMovementProbe();
        }
        if (elapsed < durationMillis) {
            if (worldTest) {
                if (automationProbeUsed) {
                    status = "Movement/chunk probe running... " + remaining + "s";
                } else if (automationProbeAttempted) {
                    status = "World movement probe unavailable; finishing static world sampling... " + remaining + "s";
                } else {
                    status = "Static world baseline running... " + remaining + "s";
                }
            } else {
                status = "Benchmarking client/menu frame pacing... " + remaining + "s";
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
        return Files.exists(Path.of("koil/automation/movement/navigation/move_relative.ktl"));
    }

    private static void startMovementProbe() {
        automationProbeAttempted = true;
        if (!automationProbeAvailable()) {
            return;
        }
        try {
            automationProbeStartedAtMillis = System.currentTimeMillis();
            AutomationRouter.handleInput(new AutomationRequest(
                    "movement/navigation/move_relative.ktl direction.id=forward count.value=4 unit.id=blocks",
                    true,
                    true
            ), "Koil Performance");
            automationProbeUsed = true;
        } catch (Throwable ignored) {
            automationProbeStartedAtMillis = 0L;
            automationProbeUsed = false;
        }
    }

    private static void unlockFrameControls(MinecraftClient client) {
        benchmarkFrameControlsChanged = false;
        try {
            originalMaxFps = client.options.getMaxFps().getValue();
            originalVsync = client.options.getEnableVsync().getValue();
            client.options.getEnableVsync().setValue(false);
            client.options.getMaxFps().setValue(260);
            benchmarkFrameControlsChanged = true;
        } catch (Throwable ignored) {
        }
    }

    private static void restoreFrameControls(MinecraftClient client) {
        if (!benchmarkFrameControlsChanged) {
            return;
        }
        try {
            client.options.getMaxFps().setValue(originalMaxFps);
            client.options.getEnableVsync().setValue(originalVsync);
        } catch (Throwable ignored) {
        }
        benchmarkFrameControlsChanged = false;
    }

    private static void finish(MinecraftClient client) {
        List<String> notes = new ArrayList<>();
        if (automationProbeUsed) {
            try {
                AutomationRouter.stopAutomation(true);
            } catch (Throwable ignored) {
            }
            notes.add("Automation movement probe requested: walk straight 4 blocks during the second half of the world benchmark.");
        } else if (worldTest) {
            notes.add("Automation movement probe skipped because the movement primitive was unavailable or could not start.");
        }
        if (hidHud) {
            notes.add("The in-world HUD and optimizer screen were hidden during sampling so UI rendering is not mixed into world measurements.");
            try {
                client.options.hudHidden = originalHudHidden;
            } catch (Throwable ignored) {
            }
        }
        if (benchmarkFrameControlsChanged) {
            notes.add("VSync and the FPS cap were temporarily disabled during sampling, then restored before recommendations were generated.");
        }
        restoreFrameControls(client);
        latestResult = PerformanceBenchmarkRunner.finishBenchmark(
                client,
                activeMode,
                startedAtMillis,
                notes,
                hidHud,
                automationProbeUsed,
                automationProbeStartedAtMillis
        );
        latestResultAtMillis = System.currentTimeMillis();
        status = latestResult.summary();
        Screen restore = returnScreen;
        active = false;
        worldTest = false;
        hidHud = false;
        automationProbeUsed = false;
        automationProbeAttempted = false;
        automationProbeStartedAtMillis = 0L;
        returnScreen = null;
        if (restore != null && client.currentScreen == null) {
            client.setScreen(restore);
        }
    }
}
