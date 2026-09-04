package com.spirit.koil.api.automation;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.spirit.client.gui.automation.AutomationWorkspaceScreen;
import com.spirit.koil.api.automation.cli.AutomationCliViewModel;
import com.spirit.koil.api.automation.feedback.AutomationFeedbackService;
import com.spirit.koil.api.automation.feedback.AutomationImprovementService;
import com.spirit.koil.api.automation.ktl.KtlCompilerService;
import com.spirit.koil.api.automation.runtime.AutomationExecutionResult;
import com.spirit.koil.api.automation.runtime.AutomationExecutionResults;
import com.spirit.koil.api.chat.RichChatCommandOutputBridge;
import com.spirit.koil.api.console.ConsoleLevel;
import com.spirit.koil.api.model.LocalModelService;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.Text;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

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
    }

    static LiteralArgumentBuilder<FabricClientCommandSource> automationModeCommand(String commandName) {
        return literal(commandName)
                .executes(context -> {
                    if (AutomationModeController.isAutomationMode()) {
                        stopAutomation(false);
                        return 1;
                    } else {
                        return enableAutomationMode() ? 1 : 0;
                    }
                })
                .then(literal("on").executes(context -> {
                    return enableAutomationMode() ? 1 : 0;
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
                        String experimental = snapshot.enabled()
                                ? " | experimental: " + experimentalStatus(snapshot)
                                : "";
                        client.inGameHud.getChatHud().addMessage(Text.literal(
                                "Automation mode: " + state + policy + thinking + planning + experimental
                        ));
                    }
                    return 1;
                }))
                .then(literal("unrestricted").executes(context -> {
                    return toggleUnrestrictedMode() ? 1 : 0;
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
                .then(literal("planning")
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
                .then(literal("experimental")
                        .executes(context -> {
                            reportExperimentalStatus();
                            return 1;
                        })
                        .then(literal("compact").executes(context -> {
                            return toggleExperimentalMode() ? 1 : 0;
                        }))
                        .then(literal("verification").executes(context -> {
                            setVerification(!AutomationModeController.isVerificationEnabled());
                            return 1;
                        }))
                        .then(literal("no-fail").executes(context -> togglePersistentExperiment(
                            com.spirit.koil.api.model.ModelExperimentalFeatures.Feature.NO_FAIL,
                            "No-Fail")))
                        .then(literal("persistent-history").executes(context -> togglePersistentExperiment(
                                com.spirit.koil.api.model.ModelExperimentalFeatures.Feature.PERSISTENT_CONVERSATION_HISTORY,
                                "Persistent conversation history")))
                        .then(literal("associative-memory").executes(context -> togglePersistentExperiment(
                                com.spirit.koil.api.model.ModelExperimentalFeatures.Feature.PERSISTENT_ASSOCIATIVE_MEMORY,
                                "Persistent associative memory")))
                        .then(literal("gigatoken").executes(context -> togglePersistentExperiment(
                                com.spirit.koil.api.model.ModelExperimentalFeatures.Feature.GIGATOKEN,
                                "gigaToken")))
                        .then(literal("expert-prefetch").executes(context -> togglePersistentExperiment(
                                com.spirit.koil.api.model.ModelExperimentalFeatures.Feature.EXPERT_PREFETCH,
                                "Expert prefetch")))
                        .then(literal("completion-mode").executes(context -> togglePersistentExperiment(
                                com.spirit.koil.api.model.ModelExperimentalFeatures.Feature.COMPLETION_MODE,
                                "Completion mode")))
                        .then(literal("status").executes(context -> {
                            reportExperimentalStatus();
                            return 1;
                        })))
                .then(literal("exit").executes(context -> {
                    stopAutomation(true);
                    return 1;
                }))
                .then(literal("chat").executes(context -> {
                    if (!enableAutomationMode()) {
                        return 0;
                    }
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
                }))
                .then(proofCommand(commandName));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> proofCommand(String commandName) {
        return literal("proof")
                .executes(context -> runProof(commandName, false, false))
                .then(literal("all").executes(context -> runProof(commandName, false, true)))
                .then(literal("cache").executes(context -> runProof(commandName, true, false)));
    }

    private static int runProof(String commandName, boolean cacheOnly, boolean explicitAll) {
        String suffix = cacheOnly ? " cache" : explicitAll ? " all" : "";
        AutomationCliViewModel.beginSession("/" + commandName + " proof" + suffix);
        return (cacheOnly ? AutomationProofSuite.runCacheOnly() : AutomationProofSuite.runAll()) ? 1 : 0;
    }

    private static boolean enableAutomationMode() {
        var eligibility = LocalModelService.selectedAutomationEligibility();
        if (!eligibility.eligible() && !LocalModelService.experimentalAutomationAllowed()) {
            LocalModelService.revokeIneligibleAutomation(eligibility, true);
            AutomationReporter.block("[block]", eligibility.detail());
            return false;
        }
        AutomationModeController.setAutomationMode(true);
        LocalModelService.prepareAutomationMode();
        AutomationReporter.pipeline("[mode]", "automation mode connecting");
        return true;
    }

    private static void setDeepThinking(boolean enabled) {
        if (!AutomationModeController.isAutomationMode()) {
            if (!enableAutomationMode()) {
                return;
            }
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
            if (!enableAutomationMode()) {
                return;
            }
        }
        AutomationModeController.setPlanningModeEnabled(enabled);
        AutomationReporter.pipeline(
                "[mode]",
                "planning mode " + (enabled ? "enabled" : "disabled")
                        + "; enabled requests require a reviewed exact-step plan before side effects"
        );
        reportModeSetting("Planning Mode", enabled);
    }

    private static void setVerification(boolean enabled) {
        if (!AutomationModeController.isAutomationMode() && !enableAutomationMode()) {
            return;
        }
        AutomationModeController.setVerificationEnabled(enabled);
        AutomationReporter.pipeline("[mode]", "result verification " + (enabled ? "enabled" : "disabled"));
        reportModeSetting("Automation verification", enabled);
    }

    private static void reportModeSetting(String label, boolean enabled) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(Text.literal(
                    label + ": " + (enabled ? "on" : "off")
            ));
        }
    }

    private static void reportExperimentalStatus() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.inGameHud == null) return;
        AutomationModeController.Snapshot snapshot = AutomationModeController.snapshot();
        client.inGameHud.getChatHud().addMessage(Text.literal(
                "Experimental: " + experimentalStatus(snapshot)
                        + " | use an /automate experimental child command to toggle"
        ));
    }

    private static int togglePersistentExperiment(
            com.spirit.koil.api.model.ModelExperimentalFeatures.Feature feature,
            String label
    ) {
        boolean enabled = com.spirit.koil.api.model.ModelExperimentalFeatures.toggle(feature);
        LocalModelService.refreshExperimentalFeatures();
        reportModeSetting(label, enabled);
        return 1;
    }

    private static String experimentalStatus(AutomationModeController.Snapshot snapshot) {
        if (snapshot == null || !snapshot.experimentalFeaturesEnabled()) return "off";
        return String.join(", ", snapshot.enabledExperimentalFeatures());
    }

    private static boolean toggleUnrestrictedMode() {
        boolean enable = !AutomationModeController.isUnrestrictedMode();
        if (enable && !AutomationModeController.isAutomationMode() && !enableAutomationMode()) {
            return false;
        }
        AutomationModeController.setUnrestrictedModeEnabled(enable);
        AutomationReporter.pipeline(
                "[mode]",
                enable
                        ? "Unrestricted mode enabled for this session: registered model capabilities skip Koil approval; Minecraft permissions remain unchanged"
                        : "Unrestricted mode disabled; standard Koil approvals are required"
        );
        reportModeSetting("Unrestricted", enable);
        return true;
    }

    private static boolean toggleExperimentalMode() {
        return setExperimentalMode(!AutomationModeController.isExperimentalCompactAgentEnabled());
    }

    private static boolean setExperimentalMode(boolean enabled) {
        AutomationModeController.setExperimentalCompactAgentEnabled(enabled);
        if (enabled && !AutomationModeController.isAutomationMode() && !enableAutomationMode()) {
            AutomationModeController.setExperimentalCompactAgentEnabled(false);
            return false;
        }
        if (!enabled && AutomationModeController.isAutomationMode()) {
            var eligibility = LocalModelService.selectedAutomationEligibility();
            if (!eligibility.eligible()) {
                LocalModelService.revokeIneligibleAutomation(eligibility, true);
            }
        }
        AutomationReporter.pipeline(
                "[mode]",
                "experimental compact agent " + (enabled ? "enabled" : "disabled")
        );
        reportModeSetting("Experimental compact agent", enabled);
        return true;
    }

    public static void toggleAutomationModeFromUi() {
        if (AutomationModeController.isAutomationMode()) {
            stopAutomation(false);
        } else {
            enableAutomationMode();
        }
    }

    public static void toggleAutomationUnrestrictedFromUi() {
        toggleUnrestrictedMode();
    }

    public static void toggleDeepThinkingFromUi() {
        setDeepThinking(!AutomationModeController.isDeepThinkingEnabled());
    }

    public static void togglePlanningModeFromUi() {
        setPlanningMode(!AutomationModeController.isPlanningModeEnabled());
    }

    public static void toggleExperimentalModeFromUi() {
        toggleExperimentalMode();
    }

    public static void toggleVerificationFromUi() {
        setVerification(!AutomationModeController.isVerificationEnabled());
    }

    public static void openCli() {
        openWorkspace("");
    }

    public static void closeCli() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        // The retired Automation console has no screen to close.
    }

    public static void openWorkspace(String traceId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        client.execute(() -> Objects.requireNonNull(client).setScreen(new AutomationWorkspaceScreen(client.currentScreen, traceId)));
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
            case "/automate stop" -> {
                stopAutomation(false);
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
                if (!enableAutomationMode()) {
                    return;
                }
                AutomationCliViewModel.beginSession(trimmed);
                AutomationReporter.pipeline("[mode]", "automation chat prompt opened");
                return;
            }
            case "/automate workspace" -> {
                openWorkspace("");
                return;
            }
            case "/automate improve" -> {
                AutomationCliViewModel.beginSession("/automate improve");
                AutomationImprovementService.improve();
                return;
            }
            case "/automate proof", "/automate proof all" -> {
                runProof("automate", false, trimmed.endsWith(" all"));
                return;
            }
            case "/automate proof cache" -> {
                runProof("automate", true, false);
                return;
            }
            case "/automate deep" -> {
                setDeepThinking(!AutomationModeController.isDeepThinkingEnabled());
                return;
            }
            case "/automate deep on" -> {
                setDeepThinking(true);
                return;
            }
            case "/automate deep off" -> {
                setDeepThinking(false);
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
            case "/automate plan on" -> {
                setPlanningMode(true);
                return;
            }
            case "/automate plan off" -> {
                setPlanningMode(false);
                return;
            }
            case "/automate plan status" -> {
                reportModeSetting("Planning Mode", AutomationModeController.isPlanningModeEnabled());
                return;
            }
            case "/automate planning" -> {
                setPlanningMode(!AutomationModeController.isPlanningModeEnabled());
                return;
            }
            case "/automate planning status" -> {
                reportModeSetting("Planning Mode", AutomationModeController.isPlanningModeEnabled());
                return;
            }
            case "/automate experimental" -> {
                reportExperimentalStatus();
                return;
            }
            case "/automate experimental status" -> {
                reportExperimentalStatus();
                return;
            }
            case "/automate experimental compact" -> {
                toggleExperimentalMode();
                return;
            }
            case "/automate experimental verification" -> {
                setVerification(!AutomationModeController.isVerificationEnabled());
                return;
            }
            case "/automate experimental no-fail" -> {
                togglePersistentExperiment(
                        com.spirit.koil.api.model.ModelExperimentalFeatures.Feature.NO_FAIL,
                        "No-Fail"
                );
                return;
            }
            case "/automate unrestricted" -> {
                toggleUnrestrictedMode();
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
        MinecraftClient client = MinecraftClient.getInstance();
        AutomationCompletionModeController.tick(client);
        if (client != null && client.player == null && (INTERPRETER.isActive() || !PENDING_REQUESTS.isEmpty())) {
            cancelCurrentTask("player connection closed");
            return;
        }
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
