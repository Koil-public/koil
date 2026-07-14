package com.spirit.koil.api.automation;

import com.spirit.client.gui.console.ConsoleScreen;
import com.spirit.koil.api.automation.cli.AutomationCliViewModel;
import com.spirit.koil.api.automation.feedback.AutomationFeedbackService;
import com.spirit.koil.api.automation.feedback.AutomationImprovementService;
import com.spirit.koil.api.automation.ktl.KtlCompilerService;
import com.spirit.koil.api.console.ConsoleLevel;
import com.spirit.koil.chat.internal.RichChatCommandOutputBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

@Environment(EnvType.CLIENT)
public final class AutomationRouter {
    private static final AutomationInterpreter INTERPRETER = new AutomationInterpreter(KtlCompilerService.getInstance());
    private static final ExecutorService PLANNER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "koil-automation-planner");
        thread.setDaemon(true);
        return thread;
    });
    private static final ConcurrentLinkedQueue<PlannerOutcome> READY = new ConcurrentLinkedQueue<>();
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();
    private static volatile long latestRequestedSequence;

    private AutomationRouter() {
    }

    public static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("automate")
                .executes(context -> {
                    openCli();
                    AutomationModeController.setAutomationMode(true);
                    AutomationReporter.pipeline("[mode]", "automation mode enabled");
                    return 1;
                })
                .then(literal("on").executes(context -> {
                    openCli();
                    AutomationModeController.setAutomationMode(true);
                    AutomationReporter.pipeline("[mode]", "automation mode enabled");
                    return 1;
                }))
                .then(literal("off").executes(context -> {
                    stopAutomation(false);
                    return 1;
                }))
                .then(literal("exit").executes(context -> {
                    stopAutomation(true);
                    return 1;
                }))
                .then(literal("chat").executes(context -> {
                    AutomationModeController.setAutomationMode(true);
                    AutomationCliViewModel.beginSession("/automate chat");
                    AutomationReporter.pipeline("[mode]", "automation chat prompt opened");
                    return 1;
                }))
                .then(literal("improve").executes(context -> {
                    AutomationCliViewModel.beginSession("/automate improve");
                    AutomationImprovementService.improve();
                    return 1;
                }))
        ));


        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("feedback")
                .executes(context -> {
                    AutomationFeedbackService.handleConsoleInput("/feedback");
                    return 1;
                })
                .then(literal("good").executes(context -> {
                    AutomationFeedbackService.handleConsoleInput("/feedback good");
                    return 1;
                }))
                .then(literal("bad").executes(context -> {
                    AutomationFeedbackService.handleConsoleInput("/feedback bad");
                    return 1;
                }).then(argument("input", greedyString()).executes(context -> {
                    AutomationFeedbackService.handleConsoleInput("/feedback bad " + getString(context, "input"));
                    return 1;
                })))
                .then(literal("cancel").executes(context -> {
                    AutomationFeedbackService.handleConsoleInput("/feedback cancel");
                    return 1;
                }))
                .then(literal("file").then(argument("file", greedyString()).executes(context -> {
                    AutomationFeedbackService.handleConsoleInput("/feedback file " + getString(context, "file"));
                    return 1;
                })))
                .then(literal("files").executes(context -> {
                    AutomationFeedbackService.handleConsoleInput("/feedback files");
                    return 1;
                }))
                .then(literal("node").then(argument("node", greedyString()).executes(context -> {
                    AutomationFeedbackService.handleConsoleInput("/feedback node " + getString(context, "node"));
                    return 1;
                })))
                .then(literal("type").then(argument("failure", greedyString()).executes(context -> {
                    AutomationFeedbackService.handleConsoleInput("/feedback type " + getString(context, "failure"));
                    return 1;
                })))
                .then(argument("input", greedyString()).executes(context -> {
                    AutomationFeedbackService.handleConsoleInput("/feedback " + getString(context, "input"));
                    return 1;
                }))
        ));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("run")
                .then(argument("input", greedyString()).executes(context -> {
                    AutomationModeController.setAutomationMode(true);
                    String input = getString(context, "input");
                    handleInput(new AutomationRequest(input, true, input.trim().endsWith(".ktl") || input.trim().contains(".ktl ")));
                    return 1;
                }))
        ));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("proof")
                .executes(context -> {
                    AutomationCliViewModel.beginSession("/proof");
                    return AutomationProofSuite.runAll() ? 1 : 0;
                })
                .then(literal("all").executes(context -> {
                    AutomationCliViewModel.beginSession("/proof all");
                    return AutomationProofSuite.runAll() ? 1 : 0;
                }))
                .then(literal("cache").executes(context -> {
                    AutomationCliViewModel.beginSession("/proof cache");
                    return AutomationProofSuite.runCacheOnly() ? 1 : 0;
                }))
        ));
    }

    public static void openCli() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        client.send(() -> client.setScreen(new ConsoleScreen(client.currentScreen, com.spirit.koil.api.console.ConsoleChannel.CLI, true)));
    }

    public static void closeCli() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        client.send(() -> {
            if (client.currentScreen instanceof ConsoleScreen screen) {
                screen.close();
            }
        });
    }

    public static void handleConsoleInput(String input) {
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (AutomationFeedbackService.handleConsoleInput(trimmed)) {
            return;
        }
        if (handleExecuteIfRuntimeCommand(trimmed)) {
            return;
        }
        if (trimmed.equals("/automate") || trimmed.equals("/automate on")) {
            AutomationModeController.setAutomationMode(true);
            AutomationReporter.pipeline("[mode]", "automation mode enabled");
            return;
        }
        if (trimmed.equals("/automate off")) {
            stopAutomation(false);
            return;
        }
        if (trimmed.equals("/automate exit")) {
            stopAutomation(true);
            return;
        }
        if (trimmed.equals("/automate chat")) {
            AutomationModeController.setAutomationMode(true);
            AutomationCliViewModel.beginSession(trimmed);
            AutomationReporter.pipeline("[mode]", "automation chat prompt opened");
            return;
        }
        if (trimmed.equals("/automate improve")) {
            AutomationCliViewModel.beginSession("/automate improve");
            AutomationImprovementService.improve();
            return;
        }
        if (trimmed.startsWith("/run ")) {
            handleInput(new AutomationRequest(trimmed.substring(5).trim(), true, trimmed.substring(5).trim().endsWith(".ktl") || trimmed.substring(5).trim().contains(".ktl ")));
            return;
        }
        if (trimmed.startsWith("/")) {
            AutomationCliViewModel.beginSession(trimmed);
            sendRawCommand(trimmed.substring(1));
            return;
        }
        handleInput(new AutomationRequest(trimmed, false, false));
    }

    public static void handleInput(AutomationRequest request) {
        handleInput(request, "");
    }

    public static void handleInput(AutomationRequest request, String actorOverride) {
        AutomationCliViewModel.beginSession(request.rawInput(), actorOverride);
        long sequence = REQUEST_SEQUENCE.incrementAndGet();
        latestRequestedSequence = sequence;
        AutomationCliViewModel.plannerGraph("queued", "graph_cluster", "[run ]", "planner.cluster", "background planning");
        AutomationCliViewModel.plannerGraph("queued.input", "planner_input", "[info]", "planner.input", request.rawInput());
        AutomationCliViewModel.plannerGraph("queued.mode", "planner_event", "[info]", "planner.mode", request.directTemplate() ? "direct_template" : "phrase_search");
        AutomationCliViewModel.activeState("thinking", "", "planning");
        AutomationRuntimeStatus.planning(request.rawInput());
        AutomationReporter.run("[run ]", "planner = queued");
        PLANNER.submit(() -> {
            try {
                AutomationCliViewModel.plannerGraph("reload", "planner_cache", "[cache]", "planner.reload", "checking .ktl sources");
                KtlCompilerService.getInstance().reload();
                AutomationCliViewModel.plannerGraph("interpret", "planner_event", "[run ]", "planner.interpret", "building execution plan");
                READY.add(PlannerOutcome.success(sequence, KtlCompilerService.getInstance().interpret(request)));
            } catch (Exception exception) {
                READY.add(PlannerOutcome.failure(sequence, exception));
            }
        });
    }

    private static boolean handleExecuteIfRuntimeCommand(String input) {
        String trimmed = input == null ? "" : input.trim();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("/execute if ") && !lower.startsWith("execute if ")) {
            return false;
        }
        int runIndex = lower.indexOf(" run ");
        if (runIndex < 0) {
            return false;
        }
        String condition = lower.substring(lower.indexOf("if ") + 3, runIndex).trim().replace('_', ' ');
        if (!condition.equals("automation task running") && !condition.equals("task running") && !condition.equals("automation running")) {
            return false;
        }
        String command = trimmed.substring(runIndex + 5).trim();
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isBlank()) {
            return true;
        }
        if (isTaskRunning()) {
            AutomationReporter.run("[cmd ]", "execute.if.automation_task_running -> /" + command);
            sendRawCommand(command);
        } else {
            AutomationReporter.info("[info]", "execute.if.automation_task_running = false");
        }
        return true;
    }

    public static void sendRawCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getNetworkHandler() != null) {
            RichChatCommandOutputBridge.rememberOutgoingChatCommand(command);
            client.getNetworkHandler().sendChatCommand(command);
            AutomationReporter.row(ConsoleLevel.PLAIN, "[cmd ]", "/" + command);
        }
    }

    public static void sendChatMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatMessage(message);
        } else if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }

    public static void tick() {
        PlannerOutcome outcome;
        while ((outcome = READY.poll()) != null) {
            if (outcome.sequence != latestRequestedSequence) {
                continue;
            }
            if (outcome.failure != null) {
                AutomationCliViewModel.plannerGraph("failed", "planner_event", "[fail]", "planner.failed", outcome.failure.getMessage() == null ? outcome.failure.getClass().getSimpleName() : outcome.failure.getMessage());
                AutomationCliViewModel.activeState("failed", "", outcome.failure.getMessage() == null ? outcome.failure.getClass().getSimpleName() : outcome.failure.getMessage());
                String failureMessage = outcome.failure.getMessage() == null ? outcome.failure.getClass().getSimpleName() : outcome.failure.getMessage();
                AutomationRuntimeStatus.failed(failureMessage);
                AutomationReporter.fail("[fail]", failureMessage);
                AutomationCliViewModel.offerFeedbackPrompt("planning failed: " + failureMessage);
                continue;
            }
            AutomationCliViewModel.plannerGraph("ready", "planner_event", "[ok  ]", "planner.ready", outcome.result.selectedTemplateId());
            AutomationCliViewModel.activeState("running", "", outcome.result.selectedTemplateId());
            AutomationRuntimeStatus.running(outcome.result.selectedTemplateId());
            AutomationReporter.ok("[ok  ]", "planner = ready");
            INTERPRETER.executePrepared(outcome.result);
        }
        if (INTERPRETER.isActive()) {
            INTERPRETER.tick();
        }
    }

    public static boolean isTaskRunning() {
        return AutomationRuntimeStatus.isTaskRunning() || INTERPRETER.isActive();
    }

    public static void stopAutomation(boolean close) {
        boolean wasRunning = isTaskRunning();
        String reason = close ? "automation exit" : "automation off";
        latestRequestedSequence = REQUEST_SEQUENCE.incrementAndGet();
        READY.clear();
        INTERPRETER.cancel(reason);
        AutomationModeController.setAutomationMode(false);
        AutomationRuntimeStatus.canceled(reason);
        AutomationReporter.pipeline("[mode]", "automation mode disabled");
        if (wasRunning) {
            AutomationCliViewModel.offerFeedbackPrompt("task stopped by user: " + reason);
        }
        if (close) {
            closeCli();
        }
    }

    private static final class PlannerOutcome {
        private final long sequence;
        private final com.spirit.koil.api.automation.runtime.InterpretationResult result;
        private final Exception failure;

        private PlannerOutcome(long sequence, com.spirit.koil.api.automation.runtime.InterpretationResult result, Exception failure) {
            this.sequence = sequence;
            this.result = result;
            this.failure = failure;
        }

        private static PlannerOutcome success(long sequence, com.spirit.koil.api.automation.runtime.InterpretationResult result) {
            return new PlannerOutcome(sequence, result, null);
        }

        private static PlannerOutcome failure(long sequence, Exception failure) {
            return new PlannerOutcome(sequence, null, failure);
        }
    }
}
