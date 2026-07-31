package com.spirit.koil.api.automation;

import com.spirit.client.gui.console.ConsoleScreen;
import com.spirit.koil.api.automation.cli.AutomationCliViewModel;
import com.spirit.koil.api.automation.feedback.AutomationFeedbackService;
import com.spirit.koil.api.automation.feedback.AutomationImprovementService;
import com.spirit.koil.api.automation.ktl.KtlCompilerService;
import com.spirit.koil.api.automation.runtime.AutomationExecutionResult;
import com.spirit.koil.api.automation.runtime.AutomationExecutionResults;
import com.spirit.koil.api.console.ConsoleLevel;
import com.spirit.koil.api.chat.RichChatCommandOutputBridge;
import com.spirit.koil.api.model.LocalModelService;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.Text;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final Map<Long, AutomationRequest> PENDING_REQUESTS = new ConcurrentHashMap<>();
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();
    private static volatile long latestRequestedSequence;

    private AutomationRouter() {
    }

    public static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(automationModeCommand("automate")));

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

    private static LiteralArgumentBuilder<FabricClientCommandSource> automationModeCommand(String commandName) {
        return literal(commandName)
                .executes(context -> {
                    if (AutomationModeController.isAutomationMode()) {
                        stopAutomation(false);
                    } else {
                        enableAutomationMode();
                    }
                    return 1;
                })
                .then(literal("on").executes(context -> {
                    enableAutomationMode();
                    return 1;
                }))
                .then(literal("off").executes(context -> {
                    stopAutomation(false);
                    return 1;
                }))
                .then(literal("status").executes(context -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    AutomationModeController.Snapshot snapshot = AutomationModeController.snapshot();
                    if (client != null && client.inGameHud != null) {
                        String state = snapshot.enabled()
                                ? snapshot.state().name().toLowerCase(java.util.Locale.ROOT)
                                : "off";
                        String policy = snapshot.enabled()
                                ? " | approvals: " + snapshot.approvalPolicy().name().toLowerCase(java.util.Locale.ROOT)
                                : "";
                        String thinking = snapshot.enabled()
                                ? " | deep thinking: " + (snapshot.deepThinkingEnabled() ? "on" : "off")
                                : "";
                        String planning = snapshot.enabled()
                                ? " | planning: " + (snapshot.planningModeEnabled() ? "on" : "off")
                                + (snapshot.planningActive() ? " (active)" : "")
                                : "";
                        client.inGameHud.getChatHud().addMessage(Text.literal("Automation mode: " + state + policy + thinking + planning));
                    }
                    return 1;
                }))
                .then(literal("unrestricted").executes(context -> {
                    enableAutomationMode();
                    AutomationModeController.enableYoloMode();
                    AutomationReporter.pipeline(
                            "[mode]",
                            "Unrestricted mode enabled for this session: registered model capabilities skip Koil approval; Minecraft permissions remain unchanged"
                    );
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client != null && client.inGameHud != null) {
                        client.inGameHud.getChatHud().addMessage(Text.literal(
                                "Unrestricted mode is on for this session. Registered model capabilities skip Koil approval; player permissions still apply."
                        ));
                    }
                    return 1;
                }))
                .then(literal("deep")
                        .executes(context -> {
                            setDeepThinking(!AutomationModeController.isDeepThinkingEnabled());
                            return 1;
                        })
                        .then(literal("on").executes(context -> {
                            setDeepThinking(true);
                            return 1;
                        }))
                        .then(literal("off").executes(context -> {
                            setDeepThinking(false);
                            return 1;
                        }))
                        .then(literal("status").executes(context -> {
                            reportModeSetting("Deep Thought", AutomationModeController.isDeepThinkingEnabled());
                            return 1;
                        })))
                .then(literal("plan")
                        .executes(context -> {
                            setPlanningMode(!AutomationModeController.isPlanningModeEnabled());
                            return 1;
                        })
                        .then(literal("on").executes(context -> {
                            setPlanningMode(true);
                            return 1;
                        }))
                        .then(literal("off").executes(context -> {
                            setPlanningMode(false);
                            return 1;
                        }))
                        .then(literal("status").executes(context -> {
                            reportModeSetting("Planning Mode", AutomationModeController.isPlanningModeEnabled());
                            return 1;
                        })))
                .then(literal("exit").executes(context -> {
                    stopAutomation(true);
                    return 1;
                }))
                .then(literal("chat").executes(context -> {
                    enableAutomationMode();
                    AutomationCliViewModel.beginSession("/" + commandName + " chat");
                    AutomationReporter.pipeline("[mode]", "automation chat routing enabled");
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client != null) {
                        client.execute(() -> client.setScreen(new ChatScreen("")));
                    }
                    return 1;
                }))
                .then(literal("improve").executes(context -> {
                    AutomationCliViewModel.beginSession("/" + commandName + " improve");
                    AutomationImprovementService.improve();
                    return 1;
                }));
    }

    private static void enableAutomationMode() {
        AutomationModeController.setAutomationMode(true);
        LocalModelService.prepareAutomationMode();
        AutomationReporter.pipeline("[mode]", "automation mode connecting");
    }

    private static void setDeepThinking(boolean enabled) {
        if (!AutomationModeController.isAutomationMode()) {
            enableAutomationMode();
        }
        AutomationModeController.setDeepThinkingEnabled(enabled);
        AutomationReporter.pipeline(
                "[mode]",
                "deep thinking " + (enabled ? "enabled" : "disabled")
                        + "; complex requests use bounded planning while direct conversation stays lightweight"
        );
    }

    private static void setPlanningMode(boolean enabled) {
        if (!AutomationModeController.isAutomationMode()) {
            enableAutomationMode();
        }
        AutomationModeController.setPlanningModeEnabled(enabled);
        AutomationReporter.pipeline(
                "[mode]",
                "planning mode " + (enabled ? "enabled" : "disabled")
                        + "; enabled requests require a reviewed exact-step plan before side effects"
        );
        reportModeSetting("Planning Mode", enabled);
    }

    private static void reportModeSetting(String label, boolean enabled) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(Text.literal(
                    label + ": " + (enabled ? "on" : "off")
            ));
        }
    }

    public static void toggleAutomationModeFromUi() {
        if (AutomationModeController.isAutomationMode()) {
            stopAutomation(false);
        } else {
            enableAutomationMode();
        }
    }

    public static void enableAutomationYoloFromUi() {
        enableAutomationMode();
        AutomationModeController.enableYoloMode();
        AutomationReporter.pipeline(
                "[mode]",
                "Unrestricted mode enabled from chat controls; registered capabilities skip Koil approval"
        );
    }

    public static void toggleDeepThinkingFromUi() {
        setDeepThinking(!AutomationModeController.isDeepThinkingEnabled());
    }

    public static void togglePlanningModeFromUi() {
        setPlanningMode(!AutomationModeController.isPlanningModeEnabled());
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
        switch (trimmed) {
            case "/automate" -> {
                if (AutomationModeController.isAutomationMode()) {
                    stopAutomation(false);
                } else {
                    enableAutomationMode();
                }
                return;
            }
            case "/automate on" -> {
                enableAutomationMode();
                return;
            }
            case "/automate off" -> {
                stopAutomation(false);
                return;
            }
            case "/automate exit" -> {
                stopAutomation(true);
                return;
            }
            case "/automate chat" -> {
                enableAutomationMode();
                AutomationCliViewModel.beginSession(trimmed);
                AutomationReporter.pipeline("[mode]", "automation chat prompt opened");
                return;
            }
            case "/automate improve" -> {
                AutomationCliViewModel.beginSession("/automate improve");
                AutomationImprovementService.improve();
                return;
            }
            case "/automate deep" -> {
                setDeepThinking(!AutomationModeController.isDeepThinkingEnabled());
                return;
            }
            case "/automate deep status" -> {
                reportModeSetting("Deep Thought", AutomationModeController.isDeepThinkingEnabled());
                return;
            }
            case "/automate plan" -> {
                setPlanningMode(!AutomationModeController.isPlanningModeEnabled());
                return;
            }
            case "/automate plan status" -> {
                reportModeSetting("Planning Mode", AutomationModeController.isPlanningModeEnabled());
                return;
            }
            case "/automate unrestricted" -> {
                enableAutomationMode();
                AutomationModeController.enableYoloMode();
                AutomationReporter.pipeline(
                        "[mode]",
                        "Unrestricted mode enabled for this session: registered model capabilities skip Koil approval; Minecraft permissions remain unchanged"
                );
                return;
            }
        }
        if (trimmed.startsWith("/")) {
            AutomationCliViewModel.beginSession(trimmed);
            sendRawCommand(trimmed.substring(1));
            return;
        }
        if (!AutomationModeController.isAutomationMode()) {
            AutomationReporter.block("[block]", "Natural-language automation requires /automate.");
            return;
        }
        LocalModelService.automationPrompt(trimmed);
    }

    public static void handleInput(AutomationRequest request) {
        handleInput(request, "");
    }

    public static void handleInput(AutomationRequest request, String actorOverride) {
        if (request == null || !request.directTemplate()) {
            throw new IllegalArgumentException(
                    "Automation accepts typed KTL task invocations only; natural-language prompts must be routed through Automation Mode."
            );
        }
        AutomationCliViewModel.beginSession(request.rawInput(), actorOverride);
        long sequence = REQUEST_SEQUENCE.incrementAndGet();
        latestRequestedSequence = sequence;
        PENDING_REQUESTS.put(sequence, request);
        AutomationCliViewModel.plannerGraph("queued", "graph_cluster", "[run ]", "planner.cluster", "background planning");
        AutomationCliViewModel.plannerGraph("queued.input", "planner_input", "[info]", "planner.input", request.rawInput());
        AutomationCliViewModel.plannerGraph("queued.mode", "planner_event", "[info]", "planner.mode", "task_template");
        AutomationCliViewModel.activeState("thinking", "", "planning");
        AutomationRuntimeStatus.planning(request.rawInput());
        AutomationReporter.run("[run ]", "planner = queued");
        PLANNER.submit(() -> {
            try {
                AutomationCliViewModel.plannerGraph("reload", "planner_cache", "[cache]", "planner.reload", "checking .ktl sources");
                KtlCompilerService.getInstance().reload();
                AutomationCliViewModel.plannerGraph("interpret", "planner_event", "[run ]", "planner.interpret", "building execution plan");
                READY.add(PlannerOutcome.success(sequence, request, KtlCompilerService.getInstance().interpret(request)));
            } catch (Exception exception) {
                READY.add(PlannerOutcome.failure(sequence, request, exception));
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
            PENDING_REQUESTS.remove(outcome.sequence);
            if (outcome.sequence != latestRequestedSequence) {
                publishPlanningResult(outcome.request, "cancelled", "superseded", "automation request was superseded");
                continue;
            }
            if (outcome.failure != null) {
                AutomationCliViewModel.plannerGraph("failed", "planner_event", "[fail]", "planner.failed", outcome.failure.getMessage() == null ? outcome.failure.getClass().getSimpleName() : outcome.failure.getMessage());
                AutomationCliViewModel.activeState("failed", "", outcome.failure.getMessage() == null ? outcome.failure.getClass().getSimpleName() : outcome.failure.getMessage());
                String failureMessage = outcome.failure.getMessage() == null ? outcome.failure.getClass().getSimpleName() : outcome.failure.getMessage();
                AutomationRuntimeStatus.failed(failureMessage);
                AutomationReporter.fail("[fail]", failureMessage);
                AutomationCliViewModel.offerFeedbackPrompt("planning failed: " + failureMessage);
                publishPlanningResult(outcome.request, "failed", "planning_failed", failureMessage);
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
        PENDING_REQUESTS.values().forEach(request ->
                publishPlanningResult(request, "cancelled", "cancelled", reason));
        PENDING_REQUESTS.clear();
        INTERPRETER.cancel(reason);
        AutomationModeController.setAutomationMode(false);
        LocalModelService.cancelActiveWork();
        AutomationRuntimeStatus.canceled(reason);
        AutomationReporter.pipeline("[mode]", "automation mode disabled");
        if (wasRunning) {
            AutomationCliViewModel.offerFeedbackPrompt("task stopped by user: " + reason);
        }
        if (close) {
            closeCli();
        }
    }

    public static void cancelCurrentTask(String reason) {
        String detail = reason == null || reason.isBlank() ? "automation task cancelled" : reason;
        latestRequestedSequence = REQUEST_SEQUENCE.incrementAndGet();
        READY.clear();
        PENDING_REQUESTS.values().forEach(request ->
                publishPlanningResult(request, "cancelled", "cancelled", detail));
        PENDING_REQUESTS.clear();
        INTERPRETER.cancel(detail);
        AutomationRuntimeStatus.canceled(detail);
    }

    private static void publishPlanningResult(
            AutomationRequest request,
            String status,
            String failureCode,
            String detail
    ) {
        if (request == null) {
            return;
        }
        Instant now = Instant.now();
        AutomationExecutionResults.publish(new AutomationExecutionResult(
                request.executionId(),
                status,
                failureCode,
                detail,
                "",
                Map.of(),
                null,
                null,
                now,
                now
        ));
    }

    private static final class PlannerOutcome {
        private final long sequence;
        private final AutomationRequest request;
        private final com.spirit.koil.api.automation.runtime.InterpretationResult result;
        private final Exception failure;

        private PlannerOutcome(long sequence, AutomationRequest request, com.spirit.koil.api.automation.runtime.InterpretationResult result, Exception failure) {
            this.sequence = sequence;
            this.request = request;
            this.result = result;
            this.failure = failure;
        }

        private static PlannerOutcome success(long sequence, AutomationRequest request, com.spirit.koil.api.automation.runtime.InterpretationResult result) {
            return new PlannerOutcome(sequence, request, result, null);
        }

        private static PlannerOutcome failure(long sequence, AutomationRequest request, Exception failure) {
            return new PlannerOutcome(sequence, request, null, failure);
        }
    }
}
