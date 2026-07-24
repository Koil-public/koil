package com.spirit.koil.api.performance;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.spirit.client.gui.performance.PerformanceOptimizerScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.List;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

@Environment(EnvType.CLIENT)
public final class PerformanceCommandBridge {
    private PerformanceCommandBridge() {
    }

    public static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("optimize")
                .executes(context -> {
                    openScreen();
                    return 1;
                })
                .then(literal("benchmark").executes(context -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    boolean started = PerformanceOptimizationTestService.start(client, PerformanceProfileMode.AUTO, client == null ? null : client.currentScreen);
                    send(started ? "Benchmark started. In-world UI will hide during sampling." : "Benchmark is already running.");
                    return started ? 1 : 0;
                }))
                .then(literal("auto").executes(context -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    PerformanceHardwareScanner.scan(client);
                    boolean started = PerformanceOptimizationTestService.start(client, PerformanceProfileMode.AUTO, client == null ? null : new PerformanceOptimizerScreen(client.currentScreen));
                    send(started ? "Auto benchmark started. Review recommendations before applying supported changes." : "Benchmark is already running.");
                    return started ? 1 : 0;
                }))
                .then(literal("revert").executes(context -> {
                    boolean reverted = PerformanceConfigApplier.revertLastBackup(MinecraftClient.getInstance());
                    send(reverted ? "Reverted last Koil optimization backup. Restart or reload options if Minecraft does not update immediately." : "No Koil optimization backup was found.");
                    return reverted ? 1 : 0;
                }))
                .then(literal("profile").then(argument("mode", StringArgumentType.word()).executes(context -> {
                    PerformanceProfileMode mode = PerformanceProfileMode.fromCommand(getString(context, "mode"));
                    MinecraftClient client = MinecraftClient.getInstance();
                    PerformanceSnapshot snapshot = PerformanceMonitor.latestSnapshot(client);
                    List<PerformanceRecommendation> recommendations = PerformanceRecommendationEngine.recommend(client, mode, snapshot);
                    java.util.Map<String, Object> profile = PerformanceProfileManager.saveProfileSuggestion(client, mode, snapshot);
                    profile.put("recommendations", recommendations.stream().map(PerformanceRecommendation::toJsonMap).toList());
                    PerformanceJsonStore.write(PerformancePaths.PERFORMANCE_PROFILES, profile);
                    send("Selected optimizer profile: " + mode.label());
                    return 1;
                })))
                .then(literal("report").executes(context -> {
                    PerformanceReportService.writeReport(MinecraftClient.getInstance(), PerformanceProfileMode.AUTO, List.of());
                    send("Performance report written to " + PerformancePaths.PERFORMANCE_REPORT);
                    return 1;
                }))
        ));
    }

    private static void openScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.send(() -> client.setScreen(new PerformanceOptimizerScreen(client.currentScreen)));
        }
    }

    private static void send(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal("[Koil Performance] " + message), false);
        }
    }
}
