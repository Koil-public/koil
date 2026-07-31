package com.spirit.koil.api.model;

import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.automation.AutomationRouter;
import com.spirit.koil.api.automation.capability.AutomationToolCoordinator;
import com.spirit.koil.api.chat.ChatHudPanelRegistry;
import com.spirit.koil.api.chat.LocalModelPromptChatBridge;
import com.spirit.koil.api.chat.ModelChatMessageBridge;
import com.spirit.koil.api.command.MinecraftCommandInspector;
import com.spirit.koil.api.minecraft.MinecraftNbtSuggestionService;
import com.spirit.koil.api.model.chat.ModelGenerationChatPanel;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;
import com.spirit.koil.api.model.format.RichChatModelOutputSanitizer;
import com.spirit.koil.api.model.format.RichChatModelFormattingContract;
import com.spirit.koil.api.model.hardware.HardwareCapabilityReport;
import com.spirit.koil.api.model.hardware.LocalModelHardwarePreflight;
import com.spirit.koil.api.model.catalog.LocalModelSelection;
import com.spirit.koil.api.model.catalog.LocalModelSelectionStore;
import com.spirit.koil.api.model.catalog.LocalModelCatalog;
import com.spirit.koil.api.model.catalog.LocalModelCatalogEntry;
import com.spirit.koil.api.model.install.LocalModelInstallationService;
import com.spirit.koil.api.model.provider.colibri.ColibriConfiguration;
import com.spirit.koil.api.model.provider.colibri.ColibriConfigurationStore;
import com.spirit.koil.api.model.provider.colibri.ColibriLocalModelProvider;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppConfiguration;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppLocalModelProvider;
import com.spirit.koil.api.model.voice.ModelVoiceService;
import com.spirit.koil.api.model.tool.LocalModelToolCatalog;
import com.spirit.koil.api.model.planning.AutomationThinkingPolicy;
import com.spirit.koil.api.model.planning.ValidatedAutomationPlan;
import com.spirit.koil.api.model.planning.ReviewedPlanAuthorization;
import com.spirit.koil.api.model.prompt.LocalModelAutomationPrompt;
import com.spirit.koil.api.model.tool.AutomationPlanModelToolRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalModelService {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static final Pattern MASKED_COMMAND_LINK = Pattern.compile(
            "\\[[^\\]\\r\\n]+]\\(/([^)\\r\\n]+)\\)"
    );
    private static final ModelConversationRegistry CONVERSATIONS = new ModelConversationRegistry(48, 64 * 1024);
    private static volatile LocalModelRuntimeManager runtime;
    private static volatile ColibriConfiguration configuration;
    private static volatile LocalModelSelection selection = LocalModelSelection.none();
    private static volatile CompletableFuture<HardwareCapabilityReport> hardwareScan;

    private LocalModelService() {
    }

    public static void initialize() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        configuration = ColibriConfigurationStore.loadOrCreate();
        selection = LocalModelSelectionStore.load();
        runtime = new LocalModelRuntimeManager(configuration.maximumQueueDepth());
        runtime.registerProvider(new ColibriLocalModelProvider(configuration));
        if (selection.complete() && "llama_cpp".equals(selection.providerId())) {
            runtime.registerProvider(new LlamaCppLocalModelProvider(
                    LlamaCppConfiguration.fromSelection(selection, configuration.apiKey())
            ));
            runtime.selectProvider("llama_cpp");
        } else {
            runtime.selectProvider("colibri");
        }
        ChatHudPanelRegistry.registerIfAbsent(new ModelGenerationChatPanel());
        CompletableFuture.runAsync(ModelVoiceService::voices);
    }

    public static boolean ask(String prompt) {
        return submitPrompt(prompt, RequestMode.ASK, true);
    }

    public static boolean automationPrompt(String prompt) {
        return automationPrompt(prompt, true);
    }

    public static boolean automationPromptFromObservedChat(String prompt) {
        return automationPrompt(prompt, false);
    }

    private static boolean automationPrompt(String prompt, boolean echoLocalPrompt) {
        if (!AutomationModeController.isAutomationMode()) {
            return false;
        }
        return submitPrompt(prompt, RequestMode.AUTOMATION, echoLocalPrompt);
    }

    public static void prepareAutomationMode() {
        initialize();
        MinecraftClient client = MinecraftClient.getInstance();
        runtime.prepareSelectedProvider().whenComplete((health, failure) -> {
            MinecraftClient current = MinecraftClient.getInstance();
            if (current == null) {
                return;
            }
            current.execute(() -> {
                if (failure != null || health == null || health.state() != ModelHealthState.READY) {
                    String detail = failure == null
                            ? health == null ? "local model unavailable" : health.detail()
                            : failure.getMessage();
                    AutomationModeController.unavailable(detail);
                    localError(current, "Automation mode could not start: " + detail);
                } else {
                    AutomationModeController.ready("local model ready");
                }
            });
        });
    }

    private static boolean submitPrompt(String prompt, RequestMode mode, boolean echoLocalPrompt) {
        initialize();
        String normalized = prompt == null ? "" : prompt.strip();
        MinecraftClient client = MinecraftClient.getInstance();
        if (normalized.isEmpty()) {
            localError(client, mode == RequestMode.ASK ? "Usage: /ask <prompt>" : "Automation prompt cannot be empty.");
            return false;
        }
        if (client == null || client.player == null || client.getNetworkHandler() == null) {
            localError(client, "A world or server connection is required before using /ask.");
            return false;
        }
        ModelVoiceService.stopSpeaking("new model prompt accepted");
        String conversationId = mode == RequestMode.ASK
                ? ModelConversationRegistry.GENERAL
                : ModelConversationRegistry.AUTOMATION;
        if (echoLocalPrompt) {
            LocalModelPromptChatBridge.addLocalPrompt(client, normalized);
        }
        ModelConversation conversation = CONVERSATIONS.conversation(conversationId);
        conversation.add(ModelMessage.user(normalized));
        UUID requestId = UUID.randomUUID();
        if (mode == RequestMode.AUTOMATION) {
            AutomationModeController.executing("model generation");
        }
        ModelGenerationHudState.begin(requestId, normalized, mode == RequestMode.AUTOMATION);
        GenerationSession session = new GenerationSession(requestId, normalized, mode, conversation);
        ModelGenerationHudState.bindCancellation(requestId, session.cancellation);
        session.submitGeneration();
        return true;
    }

    public static void resetGeneralConversation() {
        CONVERSATIONS.clear(ModelConversationRegistry.GENERAL);
    }

    public static void resetAutomationConversation() {
        CONVERSATIONS.clear(ModelConversationRegistry.AUTOMATION);
    }

    public static void resetAllConversations() {
        resetGeneralConversation();
        resetAutomationConversation();
    }

    public static CompletableFuture<ModelHealthSnapshot> startRuntime() {
        initialize();
        return runtime.prepareSelectedProvider();
    }

    public static CompletableFuture<Void> stopRuntime() {
        return CompletableFuture.runAsync(LocalModelService::shutdown);
    }

    public static CompletableFuture<ModelHealthSnapshot> restartRuntime() {
        CompletableFuture<ModelHealthSnapshot> result = new CompletableFuture<>();
        stopRuntime().whenComplete((ignored, stopFailure) -> {
            if (stopFailure != null) {
                result.completeExceptionally(stopFailure);
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            Runnable start = () -> startRuntime().whenComplete((health, startFailure) -> {
                if (startFailure != null) {
                    result.completeExceptionally(startFailure);
                } else {
                    result.complete(health);
                }
            });
            if (client == null) {
                start.run();
            } else {
                client.execute(start);
            }
        });
        return result;
    }

    public static boolean cancelActiveWork() {
        boolean cancelled = ModelGenerationHudState.cancelVisible();
        MinecraftClient client = MinecraftClient.getInstance();
        if (AutomationRouter.isTaskRunning() && client != null) {
            client.execute(() -> AutomationRouter.cancelCurrentTask("cancelled by /model cancel"));
            cancelled = true;
        }
        return cancelled;
    }

    public static ModelHealthSnapshot health() {
        initialize();
        return runtime.health();
    }

    public static int queueDepth() {
        initialize();
        return runtime.queueDepth();
    }

    public static int configuredContextWindowTokens() {
        initialize();
        return runtime.selectedMaximumContextTokens();
    }

    public static String selectedProviderId() {
        initialize();
        return runtime.selectedProviderId();
    }

    public static String configuredModelId() {
        initialize();
        return selection.complete() ? selection.modelId() : configuration.modelId();
    }

    public static String selectedCatalogId() {
        initialize();
        return selection.catalogId();
    }

    public static CompletableFuture<Boolean> selectInstalledCatalogModel(String catalogId) {
        initialize();
        var entry = LocalModelCatalog.find(catalogId).orElse(null);
        LocalModelInstallationService installer = LocalModelInstallationService.instance();
        if (entry == null || !installer.installed(entry) || !installer.selectInstalled(entry)) {
            return CompletableFuture.completedFuture(false);
        }
        return reloadConfiguration().thenApply(ignored -> true);
    }

    public static CompletableFuture<Void> reloadConfiguration() {
        return CompletableFuture.runAsync(() -> {
            shutdown();
            initialize();
        });
    }

    public static CompletableFuture<HardwareCapabilityReport> hardwareReport(boolean refresh) {
        initialize();
        CompletableFuture<HardwareCapabilityReport> current = hardwareScan;
        if (!refresh && current != null) {
            return current;
        }
        synchronized (LocalModelService.class) {
            current = hardwareScan;
            if (!refresh && current != null) {
                return current;
            }
            ColibriConfiguration snapshot = configuration;
            LocalModelSelection selected = selection;
            hardwareScan = CompletableFuture.supplyAsync(() -> selected.complete()
                    ? LocalModelHardwarePreflight.scan(selected)
                    : LocalModelHardwarePreflight.scan(snapshot));
            return hardwareScan;
        }
    }

    public static void shutdown() {
        LocalModelRuntimeManager active = runtime;
        if (active != null) {
            active.close();
        }
        runtime = null;
        selection = LocalModelSelection.none();
        hardwareScan = null;
        INITIALIZED.set(false);
    }

    private static void localError(MinecraftClient client, String message) {
        if (client != null && client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(Text.literal(message));
        }
    }

    private static String systemPrompt(
            RequestMode mode,
            boolean toolsAvailable,
            AutomationThinkingPolicy.Decision thinking
    ) {
        String languageContract = """
                Reply only in the language used by the latest user message. For English input, use English only; never switch languages unless explicitly asked.
                """;
        if (mode == RequestMode.ASK) {
            return LocalModelSystemPrompt.load() + """

                    /ask is conversational and has no tools. Never claim to run code, commands, KTL, automation, or Minecraft actions. Command links are suggestions only.
                    When asked for an enchanted-item command, use Minecraft 1.20.1 item SNBT after the item id, for example `[Give Knockback 5 Stick](/give @s minecraft:stick{Enchantments:[{id:"minecraft:knockback",lvl:5s}]} 1)`. Never invent `/forge`.
                    """ + RichChatModelFormattingContract.askPrompt() + languageContract;
        }
        if (!toolsAvailable) {
            return LocalModelSystemPrompt.load() + """
                    Automation Mode is active, but this message is conversational and has no relevant tools. Reply normally and do not claim to perform an action.
                    """ + RichChatModelFormattingContract.automationPrompt() + languageContract;
        }
        return LocalModelSystemPrompt.load()
                + LocalModelAutomationPrompt.rules(
                AutomationModeController.isYoloMode(),
                thinking != null && thinking.deepActive(),
                AutomationModeController.isPlanningModeEnabled()
        )
                + RichChatModelFormattingContract.automationPrompt()
                + languageContract;
    }

    private static double selectedModelParametersBillions() {
        LocalModelCatalogEntry entry = LocalModelCatalog.find(selectedCatalogId()).orElse(null);
        if (entry == null) {
            return 14.7D;
        }
        Matcher numeric = Pattern.compile("[0-9]+(?:\\.[0-9]+)?")
                .matcher(entry.parameterCount());
        try {
            return numeric.find() ? Double.parseDouble(numeric.group()) : 14.7D;
        } catch (NumberFormatException ignored) {
            return 14.7D;
        }
    }

    private static final class GenerationSession {
        private static final int MAXIMUM_IDENTICAL_CALLS = 2;
        private static final int MAXIMUM_IDENTICAL_RESPONSES = 2;

        private final UUID displayId;
        private final String prompt;
        private final RequestMode mode;
        private final ModelConversation conversation;
        private final List<ModelToolDefinition> tools;
        private final AutomationThinkingPolicy.Decision thinking;
        private final boolean forcedPlanning;
        private final long startedAtMillis = System.currentTimeMillis();
        private final Map<String, Integer> repeatedCalls = new LinkedHashMap<>();
        private final Map<String, Integer> repeatedResponses = new LinkedHashMap<>();
        private final Set<String> completedToolIds = new LinkedHashSet<>();
        private final Set<String> requiredToolIds;
        private final SessionCancellation cancellation = new SessionCancellation();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private int toolCallCount;
        private int toolResultsReceived;
        private int continuationCorrectionCount;
        private int askFormattingCorrectionCount;
        private int providerRoundCount;
        private boolean groundedAskCommandAttempted;
        private ValidatedAutomationPlan validatedPlan;
        private ReviewedPlanAuthorization planAuthorization;
        private PlanPhase planPhase = PlanPhase.NONE;
        private int planRevisionCount;

        private GenerationSession(UUID displayId, String prompt, RequestMode mode, ModelConversation conversation) {
            this.displayId = displayId;
            this.prompt = prompt;
            this.mode = mode;
            this.conversation = conversation;
            this.forcedPlanning = mode == RequestMode.AUTOMATION
                    && AutomationModeController.isPlanningModeEnabled();
            this.thinking = AutomationThinkingPolicy.evaluate(
                    prompt,
                    mode == RequestMode.AUTOMATION
                            && AutomationModeController.isDeepThinkingEnabled(),
                    this.forcedPlanning
            );
            this.tools = mode == RequestMode.AUTOMATION
                    ? LocalModelToolCatalog.toolsForPrompt(prompt, this.thinking.includePlanTool())
                    : List.of();
            this.requiredToolIds = mode == RequestMode.AUTOMATION
                    ? LocalModelToolCatalog.requiredToolIdsForPrompt(prompt)
                    : Set.of();
            if (mode == RequestMode.AUTOMATION) {
                AutomationModeController.setDeepThinkingActive(this.thinking.deepActive());
            }
        }

        private void submitGeneration() {
            if (this.cancellation.isCancellationRequested()) {
                fail("cancelled", this.cancellation.cancellationReason(), null);
                return;
            }
            if (System.currentTimeMillis() - this.startedAtMillis > 10L * 60L * 1000L) {
                fail("automation_budget_exceeded", "The model and automation request exceeded its ten-minute budget.", null);
                return;
            }
            this.providerRoundCount++;
            if (this.providerRoundCount > this.thinking.maximumProviderRounds()) {
                fail(
                        "model_reasoning_loop",
                        "The model exceeded the bounded planning-round budget without reaching a final result.",
                        null
                );
                return;
            }
            UUID providerRequestId = UUID.randomUUID();
            ModelVoiceService.StreamingSpeech streamingSpeech = ModelVoiceService.beginStreaming();
            ModelGenerationHudState.replaceText(this.displayId, "");
            String promptContract = systemPrompt(this.mode, !this.tools.isEmpty(), this.thinking);
            List<ModelMessage> requestMessages = this.conversation.snapshotWithin(
                    conversationMessageBudget(this.mode),
                    conversationCharacterBudget(this.mode)
            );
            int requestContextCharacters = requestMessages.stream()
                    .mapToInt(message -> message.content().length())
                    .sum();
            LocalModelRuntimeLog.write(
                    "request_context",
                    this.mode.name().toLowerCase(java.util.Locale.ROOT)
                            + " | system_chars=" + promptContract.length()
                            + " | history_chars=" + requestContextCharacters
                            + " | tools=" + this.tools.size()
            );
            StreamingModelRequest request = new StreamingModelRequest(
                    providerRequestId,
                    this.conversation.id(),
                    promptContract,
                    requestMessages,
                    this.tools,
                    1024,
                    configuration.requestTimeout(),
                    Map.of(
                            "cache_slot", this.mode == RequestMode.AUTOMATION && configuration.kvSlots() > 1 ? "1" : "0",
                            "mode", this.mode == RequestMode.ASK ? "ask" : "automation",
                            "tool_registry_version", this.mode == RequestMode.AUTOMATION
                                    ? LocalModelToolCatalog.version()
                                    : "",
                            "tool_count", Integer.toString(this.tools.size()),
                            "context_characters", Integer.toString(requestContextCharacters),
                            "system_prompt_characters", Integer.toString(promptContract.length())
                    )
            );
            ManagedModelRequest managed = runtime.submit(request, new StreamingModelObserver() {
                @Override
                public void onState(UUID id, ModelRequestState state, String detail) {
                    if (!state.terminal()) {
                        ModelGenerationHudState.state(displayId, state, detail);
                    }
                }

                @Override
                public void onTextDelta(UUID id, String delta) {
                    ModelGenerationHudState.append(displayId, delta);
                    streamingSpeech.accept(delta);
                }

                @Override
                public void onUsage(UUID id, ModelUsage usage) {
                    ModelGenerationHudState.usage(displayId, usage);
                }

                @Override
                public void onComplete(StreamingModelResponse response) {
                    streamingSpeech.finish();
                    handleResponse(response);
                }

                @Override
                public void onFailure(UUID id, String code, String detail, Throwable cause) {
                    streamingSpeech.finish();
                    fail(code, detail, cause);
                }
            });
            this.cancellation.bind(managed.cancellation());
        }

        private int conversationMessageBudget(RequestMode requestMode) {
            if (requestMode == RequestMode.AUTOMATION) {
                return 20;
            }
            return selectedModelParametersBillions() <= 3.5D ? 16 : 24;
        }

        private int conversationCharacterBudget(RequestMode requestMode) {
            double parameters = selectedModelParametersBillions();
            if (parameters <= 3.5D) {
                return requestMode == RequestMode.AUTOMATION ? 16 * 1024 : 12 * 1024;
            }
            if (parameters <= 8.0D) {
                return requestMode == RequestMode.AUTOMATION ? 24 * 1024 : 20 * 1024;
            }
            return 32 * 1024;
        }

        private void handleResponse(StreamingModelResponse response) {
            if (this.terminal.get()) {
                return;
            }
            String responseFingerprint = responseFingerprint(response);
            int repeatedResponse = this.repeatedResponses.merge(responseFingerprint, 1, Integer::sum);
            if (repeatedResponse > MAXIMUM_IDENTICAL_RESPONSES) {
                fail(
                        "model_reasoning_loop",
                        "The model repeated the same planning response without making progress.",
                        null
                );
                return;
            }
            LocalModelRuntimeLog.write(
                    "response_summary",
                    this.mode.name().toLowerCase(java.util.Locale.ROOT)
                            + " | text_chars=" + response.text().length()
                            + " | tools=" + response.toolCalls().size()
                            + " | finish=" + response.providerFinishReason()
            );
            if (this.mode != RequestMode.AUTOMATION) {
                validateAskCommandResponse(this.prompt, response.text()).whenComplete((validation, failure) -> {
                    if (this.terminal.get()) {
                        return;
                    }
                    if (failure != null) {
                        continueMalformedAskCommand(
                                response.text(),
                                "The active Minecraft command tree could not be checked: " + message(failure)
                        );
                    } else if (!validation.valid()) {
                        continueMalformedAskCommand(response.text(), validation.detail());
                    } else {
                        complete(response.text());
                    }
                });
                return;
            }
            if (response.toolCalls().isEmpty()) {
                if (!remainingRequiredToolIds().isEmpty()
                        || (this.toolResultsReceived > 0
                        && promisesUnexecutedAction(response.text()))) {
                    continueUnresolvedObjective(response.text());
                    return;
                }
                complete(response.text());
                return;
            }
            archiveVisibleSummary(response.text());
            List<ModelToolCall> planCalls = response.toolCalls().stream()
                    .filter(call -> AutomationPlanModelToolRegistry.supports(call.toolId()))
                    .toList();
            if (!planCalls.isEmpty()) {
                if (response.toolCalls().size() != 1 || planCalls.size() != 1) {
                    requestPlanRevision(
                            "A plan must be submitted alone before any plan step can run.",
                            response.text(),
                            response.toolCalls()
                    );
                } else {
                    validateAndReviewPlan(response.text(), planCalls.get(0));
                }
                return;
            }
            boolean requestsSideEffect = response.toolCalls().stream()
                    .anyMatch(call -> LocalModelToolCatalog.requiresFreshApproval(call.toolId()));
            if (this.forcedPlanning && this.validatedPlan == null && requestsSideEffect) {
                requestPlanRevision(
                        "Planning Mode requires automation.plan before any side-effecting action.",
                        response.text(),
                        response.toolCalls()
                );
                return;
            }
            if ((this.planPhase == PlanPhase.COMPLETED
                    || this.planPhase == PlanPhase.FAILED
                    || this.planPhase == PlanPhase.REJECTED
                    || this.planPhase == PlanPhase.REVISION_REQUIRED)
                    && requestsSideEffect) {
                requestPlanRevision(
                        "The approved plan is complete. Additional or changed side effects require a revised plan.",
                        response.text(),
                        response.toolCalls()
                );
                return;
            }
            boolean freshApprovalRequired = response.toolCalls().stream()
                    .anyMatch(call -> LocalModelToolCatalog.requiresFreshApproval(call.toolId()));
            if (freshApprovalRequired && !AutomationModeController.isYoloMode()) {
                requestToolBatchApproval(response.text(), response.toolCalls());
                return;
            }
            handleToolCalls(response.text(), response.toolCalls(), 0, false);
        }

        private void validateAndReviewPlan(String assistantText, ModelToolCall planCall) {
            if (this.toolCallCount >= this.thinking.maximumToolCalls()) {
                fail("tool_call_budget_exceeded", "The model exceeded the tool-call budget while planning.", null);
                return;
            }
            this.toolCallCount++;
            ModelGenerationHudState.toolCallCount(this.displayId, this.toolCallCount);
            this.conversation.add(ModelMessage.assistantToolCall(assistantText, planCall));
            ModelGenerationHudState.state(this.displayId, ModelRequestState.EXECUTING_TOOL, "validating plan");
            AutomationModeController.setPlanningActive(true);
            AutomationToolCoordinator.execute(this.displayId, planCall, false)
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            fail("plan_validation_failed", message(failure), failure);
                            return;
                        }
                        this.conversation.add(ModelMessage.toolResult(result));
                        if (!"completed".equals(result.status())) {
                            requestPlanRevision(
                                    result.detail().isBlank()
                                            ? "The proposed plan did not pass validation."
                                            : result.detail(),
                                    "",
                                    List.of()
                            );
                            return;
                        }
                        try {
                            this.validatedPlan = ValidatedAutomationPlan.from(result);
                        } catch (RuntimeException invalid) {
                            fail("invalid_plan_result", message(invalid), invalid);
                            return;
                        }
                        this.planPhase = PlanPhase.REVIEW;
                        ModelGenerationHudState.setPlan(
                                this.displayId,
                                this.validatedPlan.id(),
                                this.validatedPlan.hudSteps()
                        );
                        ModelGenerationHudState.appendEvent(
                                this.displayId,
                                ModelGenerationHudState.ActivityEventType.PLAN_STEP,
                                "Validated " + this.validatedPlan.steps().size()
                                        + " ordered step" + (this.validatedPlan.steps().size() == 1 ? "" : "s")
                                        + " as " + this.validatedPlan.id() + "."
                        );
                        requestPlanApproval();
                    });
        }

        private void requestPlanApproval() {
            ValidatedAutomationPlan plan = this.validatedPlan;
            if (plan == null) {
                fail("invalid_plan_state", "No validated plan is available for review.", null);
                return;
            }
            StringBuilder message = new StringBuilder()
                    .append("Plan ").append(plan.id()).append("\n")
                    .append(plan.objective()).append("\n\n");
            for (ValidatedAutomationPlan.Step step : plan.steps()) {
                message.append(step.index()).append(". ").append(step.toolId());
                if (!step.reason().isBlank()) {
                    message.append(" — ").append(step.reason());
                }
                message.append('\n');
            }
            message.append("\nApproval authorizes only these exact validated steps. "
                    + "Changed or additional side effects require another reviewed plan.");
            ModelGenerationHudState.state(this.displayId, ModelRequestState.EXECUTING_TOOL, "plan review");
            ModelGenerationHudState.requestApproval(
                            this.displayId,
                            "Review automation plan",
                            message.toString(),
                            "Approve Plan",
                            "Reject Plan"
                    )
                    .thenAccept(approved -> {
                        if (this.cancellation.isCancellationRequested()) {
                            fail("cancelled", this.cancellation.cancellationReason(), null);
                            return;
                        }
                        if (!approved) {
                            this.planPhase = PlanPhase.REJECTED;
                            for (ValidatedAutomationPlan.Step step : plan.steps()) {
                                ModelGenerationHudState.updatePlanStep(
                                        this.displayId,
                                        step.index(),
                                        ModelGenerationHudState.PlanStepStatus.REVISED,
                                        "not executed"
                                );
                            }
                            ModelGenerationHudState.markPlanRevised(this.displayId);
                            ModelGenerationHudState.appendEvent(
                                    this.displayId,
                                    ModelGenerationHudState.ActivityEventType.REPLAN,
                                    "The player rejected " + plan.id() + "; no plan step executed."
                            );
                            if (this.planRevisionCount >= 2) {
                                AutomationModeController.setPlanningActive(false);
                                complete("The plan was rejected. No action was executed.");
                                return;
                            }
                            this.planRevisionCount++;
                            this.validatedPlan = null;
                            this.conversation.add(ModelMessage.user(
                                    "The player rejected the proposed plan. No step executed. "
                                            + "You may submit one revised automation.plan with a changed, bounded approach, "
                                            + "or stop truthfully without action."
                            ));
                            submitGeneration();
                            return;
                        }
                        this.planPhase = PlanPhase.APPROVED;
                        this.planAuthorization = new ReviewedPlanAuthorization(plan);
                        this.planAuthorization.approve();
                        ModelGenerationHudState.appendEvent(
                                this.displayId,
                                ModelGenerationHudState.ActivityEventType.RESULT,
                                "The player approved only the exact steps in " + plan.id() + "."
                        );
                        executeApprovedPlanStep(0);
                    });
        }

        private void executeApprovedPlanStep(int position) {
            ValidatedAutomationPlan plan = this.validatedPlan;
            if (plan == null || this.planPhase != PlanPhase.APPROVED) {
                fail("invalid_plan_state", "Approved plan execution lost its validated plan.", null);
                return;
            }
            if (position >= plan.steps().size()) {
                this.planPhase = PlanPhase.COMPLETED;
                AutomationModeController.setPlanningActive(false);
                ModelGenerationHudState.appendEvent(
                        this.displayId,
                        ModelGenerationHudState.ActivityEventType.RESULT,
                        "Every step in " + plan.id() + " reached a structured result."
                );
                submitGeneration();
                return;
            }
            if (this.cancellation.isCancellationRequested()) {
                fail("cancelled", this.cancellation.cancellationReason(), null);
                return;
            }
            if (this.toolCallCount >= this.thinking.maximumToolCalls()) {
                fail("tool_call_budget_exceeded", "The approved plan exceeds the remaining tool-call budget.", null);
                return;
            }
            ValidatedAutomationPlan.Step step = plan.steps().get(position);
            ModelToolCall call = step.asToolCall(plan.id());
            if (this.planAuthorization == null
                    || !this.planAuthorization.authorizesExactStep(step.index(), call)) {
                fail(
                        "plan_step_deviation",
                        "The requested action did not exactly match the approved plan step.",
                        null
                );
                return;
            }
            this.toolCallCount++;
            ModelGenerationHudState.toolCallCount(this.displayId, this.toolCallCount);
            ModelGenerationHudState.toolProgress(
                    this.displayId,
                    step.index(),
                    plan.steps().size(),
                    step.toolId(),
                    toolActivityDetail(call)
            );
            ModelGenerationHudState.updatePlanStep(
                    this.displayId,
                    step.index(),
                    ModelGenerationHudState.PlanStepStatus.ACTIVE,
                    ""
            );
            ModelGenerationHudState.appendEvent(
                    this.displayId,
                    ModelGenerationHudState.ActivityEventType.TOOL_START,
                    "Step " + step.index() + "/" + plan.steps().size() + ": " + readableToolName(step.toolId())
            );
            this.conversation.add(ModelMessage.assistantToolCall("", call));
            ModelGenerationHudState.state(this.displayId, ModelRequestState.EXECUTING_TOOL, step.toolId());
            AutomationModeController.executing("tool: " + step.toolId());
            AutomationToolCoordinator.execute(this.displayId, call, true)
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            handleApprovedPlanFailure(step, null, message(failure));
                            return;
                        }
                        this.toolResultsReceived++;
                        this.conversation.add(ModelMessage.toolResult(result));
                        boolean successful = "completed".equals(result.status())
                                || "submitted".equals(result.status());
                        if (successful) {
                            this.completedToolIds.add(step.toolId());
                            ModelGenerationHudState.updatePlanStep(
                                    this.displayId,
                                    step.index(),
                                    ModelGenerationHudState.PlanStepStatus.COMPLETED,
                                    result.status()
                            );
                            ModelGenerationHudState.appendEvent(
                                    this.displayId,
                                    ModelGenerationHudState.ActivityEventType.RESULT,
                                    "Step " + step.index() + " " + result.status()
                                            + (result.detail().isBlank() ? "" : ": " + abbreviate(result.detail(), 300))
                            );
                            executeApprovedPlanStep(position + 1);
                        } else {
                            handleApprovedPlanFailure(step, result, result.detail());
                        }
                    });
        }

        private void handleApprovedPlanFailure(
                ValidatedAutomationPlan.Step step,
                ModelToolResult result,
                String detail
        ) {
            this.planPhase = PlanPhase.FAILED;
            String safeDetail = detail == null || detail.isBlank()
                    ? result == null ? "step failed" : result.status()
                    : detail;
            ModelGenerationHudState.updatePlanStep(
                    this.displayId,
                    step.index(),
                    ModelGenerationHudState.PlanStepStatus.FAILED,
                    safeDetail
            );
            ModelGenerationHudState.appendEvent(
                    this.displayId,
                    ModelGenerationHudState.ActivityEventType.FAILURE,
                    "Step " + step.index() + " failed: " + abbreviate(safeDetail, 300)
            );
            if (this.planRevisionCount >= 2) {
                AutomationModeController.setPlanningActive(false);
                complete("The approved plan stopped after a failed step. No unsupported replacement action was run.");
                return;
            }
            this.planRevisionCount++;
            ModelGenerationHudState.markPlanRevised(this.displayId);
            ModelGenerationHudState.appendEvent(
                    this.displayId,
                    ModelGenerationHudState.ActivityEventType.REPLAN,
                    "A changed side-effecting approach requires a newly validated and approved plan."
            );
            this.validatedPlan = null;
            this.planAuthorization = null;
            this.conversation.add(ModelMessage.user(
                    "The approved plan stopped at step " + step.index() + " with: "
                            + abbreviate(safeDetail, 500)
                            + ". Do not improvise another side effect. Submit a revised automation.plan "
                            + "with a changed supported approach, or stop with the exact limitation."
            ));
            submitGeneration();
        }

        private void requestPlanRevision(
                String reason,
                String assistantText,
                List<ModelToolCall> rejectedCalls
        ) {
            if (this.planRevisionCount >= 2) {
                AutomationModeController.setPlanningActive(false);
                complete("No action was executed because the requested work departed from the reviewed plan.");
                return;
            }
            this.planRevisionCount++;
            AutomationModeController.setPlanningActive(true);
            this.planPhase = PlanPhase.REVISION_REQUIRED;
            if (assistantText != null && !assistantText.isBlank()) {
                this.conversation.add(ModelMessage.assistant(assistantText));
            }
            if (rejectedCalls != null) {
                for (ModelToolCall call : rejectedCalls) {
                    this.conversation.add(ModelMessage.assistantToolCall("", call));
                    this.conversation.add(ModelMessage.toolResult(new ModelToolResult(
                            call.id(),
                            call.toolId(),
                            "rejected",
                            new com.google.gson.JsonObject(),
                            "plan_review_required",
                            reason
                    )));
                }
            }
            ModelGenerationHudState.markPlanRevised(this.displayId);
            ModelGenerationHudState.appendEvent(
                    this.displayId,
                    ModelGenerationHudState.ActivityEventType.REPLAN,
                    reason
            );
            this.validatedPlan = null;
            this.planAuthorization = null;
            this.conversation.add(ModelMessage.user(
                    reason + " Submit automation.plan alone with all intended ordered side effects. "
                            + "The revised plan must be reviewed before execution."
            ));
            ModelGenerationHudState.state(this.displayId, ModelRequestState.PREPARING_CONTEXT, "replanning");
            submitGeneration();
        }

        private void requestToolBatchApproval(String assistantText, List<ModelToolCall> calls) {
            int remainingBudget = this.thinking.maximumToolCalls() - this.toolCallCount;
            if (calls.size() > remainingBudget) {
                fail("tool_call_budget_exceeded", "The model requested more actions than the remaining tool-call budget.", null);
                return;
            }
            StringBuilder message = new StringBuilder()
                    .append(calls.size() == 1
                            ? "The model requested this action:\n"
                            : "The model requested these " + calls.size() + " actions:\n");
            for (ModelToolCall call : calls) {
                message.append("- ").append(call.toolId());
                String arguments = call.arguments().toString();
                if (!arguments.equals("{}")) {
                    message.append(" ").append(abbreviate(arguments, 220));
                }
                message.append('\n');
            }
            message.append("\nThis approval applies only to this request. Later actions ask again.");
            ModelGenerationHudState.state(this.displayId, ModelRequestState.EXECUTING_TOOL, "approval");
            ModelGenerationHudState.requestApproval(
                            this.displayId,
                            calls.size() == 1 ? "Automation action" : "Automation actions",
                            message.toString(),
                            calls.size() == 1 ? "Run Action" : "Run Actions",
                            "Deny"
                    )
                    .thenAccept(approved -> {
                        if (this.cancellation.isCancellationRequested()) {
                            fail("cancelled", this.cancellation.cancellationReason(), null);
                            return;
                        }
                        if (approved) {
                            handleToolCalls(assistantText, calls, 0, true);
                        } else {
                            recordRejectedToolBatch(assistantText, calls);
                            submitGeneration();
                        }
                    });
        }

        private void recordRejectedToolBatch(String assistantText, List<ModelToolCall> calls) {
            for (int index = 0; index < calls.size(); index++) {
                ModelToolCall call = calls.get(index);
                this.toolCallCount++;
                this.repeatedCalls.merge(call.toolId() + ":" + call.arguments(), 1, Integer::sum);
                this.conversation.add(ModelMessage.assistantToolCall(index == 0 ? assistantText : "", call));
                this.conversation.add(ModelMessage.toolResult(new ModelToolResult(
                        call.id(),
                        call.toolId(),
                        "rejected",
                        new com.google.gson.JsonObject(),
                        "user_declined_batch",
                        "The player declined the requested group of automation actions."
                )));
            }
            ModelGenerationHudState.toolCallCount(this.displayId, this.toolCallCount);
            ModelGenerationHudState.state(this.displayId, ModelRequestState.WAITING_FOR_TOOL_RESULT, "denied");
            ModelGenerationHudState.appendActivity(this.displayId, "-# Action denied by the player.");
        }

        private void handleToolCalls(
                String assistantText,
                List<ModelToolCall> calls,
                int index,
                boolean preapproved
        ) {
            if (calls == null || index >= calls.size()) {
                submitGeneration();
                return;
            }
            ModelToolCall call = calls.get(index);
            if (this.toolCallCount >= this.thinking.maximumToolCalls()) {
                fail("tool_call_budget_exceeded", "The model exceeded the tool-call budget.", null);
                return;
            }
            String signature = call.toolId() + ":" + call.arguments();
            int repeated = this.repeatedCalls.merge(signature, 1, Integer::sum);
            if (repeated > MAXIMUM_IDENTICAL_CALLS) {
                fail("repeated_tool_loop", "The model repeated the same automation tool call without making progress.", null);
                return;
            }
            this.toolCallCount++;
            ModelGenerationHudState.toolCallCount(this.displayId, this.toolCallCount);
            ModelGenerationHudState.toolProgress(
                    this.displayId,
                    index + 1,
                    calls.size(),
                    call.toolId(),
                    toolActivityDetail(call)
            );
            this.conversation.add(ModelMessage.assistantToolCall(index == 0 ? assistantText : "", call));
            ModelGenerationHudState.state(this.displayId, ModelRequestState.EXECUTING_TOOL, call.toolId());
            ModelGenerationHudState.appendEvent(
                    this.displayId,
                    ModelGenerationHudState.ActivityEventType.TOOL_START,
                    "Step " + (index + 1) + "/" + calls.size() + ": " + readableToolName(call.toolId())
            );
            AutomationModeController.executing("tool: " + call.toolId());
            LocalModelRuntimeLog.write(
                    "tool_start",
                    this.displayId + " | step=" + (index + 1) + "/" + calls.size() + " | " + call.toolId()
            );
            AutomationToolCoordinator.execute(this.displayId, call, preapproved).whenComplete((toolResult, failure) -> {
                if (failure != null) {
                    fail("tool_execution_failed", message(failure), failure);
                    return;
                }
                if (this.cancellation.isCancellationRequested()) {
                    fail("cancelled", this.cancellation.cancellationReason(), null);
                    return;
                }
                this.toolResultsReceived++;
                if ("completed".equals(toolResult.status()) || "submitted".equals(toolResult.status())) {
                    this.completedToolIds.add(call.toolId());
                }
                this.conversation.add(ModelMessage.toolResult(toolResult));
                String resultDetail = toolResult.status();
                if (!toolResult.failureCode().isBlank()) {
                    resultDetail += " | " + toolResult.failureCode();
                }
                if (!toolResult.detail().isBlank()) {
                    resultDetail += " | " + toolResult.detail();
                }
                ModelGenerationHudState.state(
                        this.displayId,
                        ModelRequestState.WAITING_FOR_TOOL_RESULT,
                        resultDetail
                );
                ModelGenerationHudState.appendEvent(
                        this.displayId,
                        "failed".equals(toolResult.status())
                                ? ModelGenerationHudState.ActivityEventType.FAILURE
                                : ModelGenerationHudState.ActivityEventType.RESULT,
                        toolResult.status()
                                + (toolResult.detail().isBlank()
                                ? ""
                                : " — " + abbreviate(toolResult.detail(), 360))
                );
                LocalModelRuntimeLog.write(
                        "tool_result",
                        this.displayId
                                + " | step=" + (index + 1) + "/" + calls.size()
                                + " | " + call.toolId()
                                + " | status=" + toolResult.status()
                                + (toolResult.failureCode().isBlank()
                                ? ""
                                : " | failure=" + toolResult.failureCode())
                );
                handleToolCalls("", calls, index + 1, preapproved);
            });
        }

        private void continueUnresolvedObjective(String assistantText) {
            if (this.continuationCorrectionCount >= this.thinking.maximumContinuationCorrections()) {
                LocalModelRuntimeLog.write(
                        "tool_continuation_failed",
                        this.displayId + " | model repeatedly described an action without calling a tool"
                );
                complete("I could not produce an executable action for the remaining objective. No additional action was run.");
                return;
            }
            this.continuationCorrectionCount++;
            archiveVisibleSummary(assistantText);
            this.conversation.add(ModelMessage.assistant(assistantText));
            this.conversation.add(ModelMessage.user("""
                    Continue the unresolved objective now. Do not promise a later action and do not ask for prose confirmation.
                    Call only the remaining explicitly requested capability or capabilities now. Do not call another knowledge lookup unless it is one of the remaining capability identifiers.
                    If no supplied tool can perform the remaining step, state that exact limitation without claiming it will be done.
                    Do not repeat an action whose structured result already says it completed or was submitted.
                    Remaining explicitly requested capabilities: %s
                    """.formatted(String.join(", ", remainingRequiredToolIds())).strip()));
            ModelGenerationHudState.state(
                    this.displayId,
                    ModelRequestState.PREPARING_CONTEXT,
                    "continuing unresolved actions"
            );
            LocalModelRuntimeLog.write(
                    "tool_continuation_retry",
                    this.displayId + " | attempt=" + this.continuationCorrectionCount
            );
            submitGeneration();
        }

        private void continueMalformedAskCommand(String assistantText, String validationDetail) {
            if (assistantText != null && !assistantText.isBlank()) {
                archiveVisibleSummary(assistantText);
            }
            if (!this.groundedAskCommandAttempted) {
                this.groundedAskCommandAttempted = true;
                var grounded = MinecraftNbtSuggestionService.groundedItemEnchantmentCommand(this.prompt);
                if (grounded.isPresent()) {
                    MinecraftNbtSuggestionService.GroundedCommand command = grounded.get();
                    ModelGenerationHudState.state(
                            this.displayId,
                            ModelRequestState.PREPARING_CONTEXT,
                            "checking grounded item NBT"
                    );
                    MinecraftCommandInspector.inspect(command.command()).whenComplete((inspection, failure) -> {
                        if (this.terminal.get()) {
                            return;
                        }
                        if (failure == null && inspection != null && inspection.executable()) {
                            ModelGenerationHudState.appendActivity(
                                    this.displayId,
                                    "-# Corrected with active item and enchantment registry data."
                            );
                            LocalModelRuntimeLog.write(
                                    "ask_command_grounded",
                                    this.displayId + " | " + command.command()
                            );
                            complete(command.maskedLink());
                            return;
                        }
                        String detail = failure != null
                                ? message(failure)
                                : inspection == null ? "No command inspection result."
                                : inspection.problem();
                        continueMalformedAskCommand(
                                "",
                                validationDetail + " | Grounded item-NBT candidate was rejected: " + detail
                        );
                    });
                    return;
                }
            }
            if (this.askFormattingCorrectionCount >= 2) {
                complete("""
                        I could not verify an exact Minecraft command suggestion for that request. Check the active in-game command suggestions before running a command.
                        """.strip());
                return;
            }
            this.askFormattingCorrectionCount++;
            if (assistantText != null && !assistantText.isBlank()) {
                this.conversation.add(ModelMessage.assistant(assistantText));
            }
            this.conversation.add(ModelMessage.user("""
                    Correct the previous answer before it is shown. The user explicitly asked for a Minecraft command.
                    Reply with the exact valid command using Koil's masked command syntax `[descriptive label](/command arguments)`.
                    Do not capitalize prose into a slash command, do not emit a bare `/Sentence`, and do not claim the command ran.
                    The previous command was checked against the active server command tree and rejected: %s
                    For item grants, use the active `/give` syntax; never invent a mod-loader command such as `/forge`.
                    For Minecraft 1.20.1 enchanted items, consult the item-NBT knowledge pattern. A valid example shape is `/give @s minecraft:stick{Enchantments:[{id:"minecraft:knockback",lvl:5s}]} 1`.
                    If you genuinely cannot determine exact syntax, say that it must be checked in the active command suggestions and do not invent a slash command.
                    """.formatted(abbreviate(validationDetail, 700)).strip()));
            ModelGenerationHudState.state(
                    this.displayId,
                    ModelRequestState.PREPARING_CONTEXT,
                    "correcting command formatting"
            );
            LocalModelRuntimeLog.write(
                    "ask_format_retry",
                    this.displayId + " | attempt=" + this.askFormattingCorrectionCount
            );
            submitGeneration();
        }

        private static CompletableFuture<AskCommandValidation> validateAskCommandResponse(
                String prompt,
                String response
        ) {
            if (!asksForMinecraftCommand(prompt)) {
                return CompletableFuture.completedFuture(AskCommandValidation.accepted());
            }
            String visible = response == null ? "" : response.strip();
            String normalized = visible.toLowerCase(java.util.Locale.ROOT);
            boolean explicitUncertainty = normalized.contains("cannot determine")
                    || normalized.contains("can't determine")
                    || normalized.contains("cannot verify")
                    || normalized.contains("check the active")
                    || normalized.contains("check in-game")
                    || normalized.contains("command suggestions");
            Matcher matcher = MASKED_COMMAND_LINK.matcher(visible);
            List<String> commands = new ArrayList<>();
            while (matcher.find()) {
                String command = matcher.group(1).strip();
                if (!command.isBlank()) {
                    commands.add(command);
                }
            }
            if (commands.isEmpty()) {
                return CompletableFuture.completedFuture(explicitUncertainty
                        ? AskCommandValidation.accepted()
                        : AskCommandValidation.rejected(
                        "No masked Minecraft command link was present."
                ));
            }
            List<CompletableFuture<MinecraftCommandInspector.Inspection>> inspections =
                    commands.stream().map(MinecraftCommandInspector::inspect).toList();
            return CompletableFuture.allOf(inspections.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> {
                        List<String> failures = new ArrayList<>();
                        for (CompletableFuture<MinecraftCommandInspector.Inspection> inspectionFuture : inspections) {
                            MinecraftCommandInspector.Inspection inspection = inspectionFuture.join();
                            if (inspection.executable()) {
                                continue;
                            }
                            StringBuilder detail = new StringBuilder("/")
                                    .append(inspection.normalizedCommand())
                                    .append(" — ")
                                    .append(inspection.problem());
                            if (!inspection.suggestions().isEmpty()) {
                                detail.append(" Suggestions: ")
                                        .append(String.join(", ", inspection.suggestions()));
                            }
                            failures.add(detail.toString());
                        }
                        return failures.isEmpty()
                                ? AskCommandValidation.accepted()
                                : AskCommandValidation.rejected(String.join(" | ", failures));
                    });
        }

        private Set<String> remainingRequiredToolIds() {
            if (this.requiredToolIds.isEmpty()) {
                return Set.of();
            }
            LinkedHashSet<String> remaining = new LinkedHashSet<>(this.requiredToolIds);
            remaining.removeAll(this.completedToolIds);
            return Set.copyOf(remaining);
        }

        private static boolean asksForMinecraftCommand(String prompt) {
            if (prompt == null || prompt.isBlank()) {
                return false;
            }
            String normalized = prompt.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("\\s+", " ")
                    .strip();
            return normalized.contains(" command")
                    || normalized.startsWith("command ")
                    || normalized.contains("slash command")
                    || normalized.contains("what do i type")
                    || normalized.contains("what should i type");
        }

        private static boolean promisesUnexecutedAction(String text) {
            if (text == null || text.isBlank()) {
                return false;
            }
            String normalized = text.toLowerCase(java.util.Locale.ROOT)
                    .replace('’', '\'')
                    .replaceAll("\\s+", " ");
            if (normalized.contains("cannot ")
                    || normalized.contains("can't ")
                    || normalized.contains("unable to ")
                    || normalized.contains("no suitable tool")) {
                return false;
            }
            boolean promise = normalized.contains("i will ")
                    || normalized.contains("i'll ")
                    || normalized.contains("let's proceed")
                    || normalized.contains("please confirm")
                    || normalized.contains("after that")
                    || normalized.contains("would you like")
                    || normalized.contains("whether you would like")
                    || normalized.contains("if you would like")
                    || normalized.contains("if you'd like")
                    || normalized.contains("tell me whether")
                    || normalized.contains("let me know if");
            boolean action = normalized.matches(".*\\b(set|give|remove|run|execute|move|walk|jump|open|create|edit|delete|try|perform|continue)\\b.*");
            return promise && action;
        }

        private void complete(String text) {
            RichChatModelOutputSanitizer.Result sanitized = RichChatModelOutputSanitizer.sanitize(text);
            if (sanitized.text().isBlank()) {
                fail("empty_response", "The model generated no visible text.", null);
                return;
            }
            if (!this.terminal.compareAndSet(false, true)) {
                return;
            }
            if (this.mode == RequestMode.AUTOMATION) {
                AutomationModeController.setDeepThinkingActive(false);
                AutomationModeController.setPlanningActive(false);
            }
            String visibleActivity = this.mode == RequestMode.AUTOMATION
                    ? ModelGenerationHudState.activity(this.displayId).strip()
                    : "";
            String finalized = visibleActivity.isBlank()
                    ? sanitized.text()
                    : "**Activity**\n" + visibleActivity + "\n\n**Result**\n" + sanitized.text();
            RichChatModelOutputSanitizer.Result finalSanitized =
                    RichChatModelOutputSanitizer.sanitize(finalized);
            this.conversation.add(ModelMessage.assistant(sanitized.text()));
            ModelGenerationHudState.replaceText(this.displayId, finalSanitized.text());
            ModelGenerationHudState.state(this.displayId, ModelRequestState.COMPLETED, "response added to chat");
            MinecraftClient current = MinecraftClient.getInstance();
            if (current != null) {
                current.execute(() -> {
                    ModelChatMessageBridge.addToChat(current, finalSanitized.text());
                    ModelGenerationHudState.dismiss(this.displayId);
                    if (this.mode == RequestMode.AUTOMATION && AutomationModeController.isAutomationMode()) {
                        AutomationModeController.ready("local model ready");
                    }
                });
            } 
        }

        private void archiveVisibleSummary(String text) {
            RichChatModelOutputSanitizer.Result summary = RichChatModelOutputSanitizer.sanitize(text);
            if (!summary.text().isBlank()) {
                ModelGenerationHudState.appendEvent(
                        this.displayId,
                        ModelGenerationHudState.ActivityEventType.THOUGHT_SUMMARY,
                        visiblePlanSummary(summary.text())
                );
            }
            ModelGenerationHudState.replaceText(this.displayId, "");
        }

        private static String visiblePlanSummary(String text) {
            String normalized = text == null
                    ? ""
                    : text.replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .replaceAll("\\s+", " ")
                    .strip();
            if (normalized.isBlank()) {
                return "";
            }
            String[] sentences = normalized.split("(?<=[.!?])\\s+");
            Pattern plannedAction = Pattern.compile(
                    "(?i).*\\b(?:will|going to|next|try|check|find|use|run|execute|inspect|verify|correct)\\b.*"
            );
            for (int index = sentences.length - 1; index >= 0; index--) {
                if (plannedAction.matcher(sentences[index]).matches()) {
                    return abbreviate(sentences[index], 260);
                }
            }
            return abbreviate(sentences[0], 260);
        }

        private static String readableToolName(String toolId) {
            if (toolId == null || toolId.isBlank()) {
                return "tool";
            }
            return toolId.replace('.', ' ').replace('_', ' ').strip();
        }

        private static String toolActivityDetail(ModelToolCall call) {
            if (call == null || call.arguments() == null) {
                return "";
            }
            for (String key : List.of("path", "root", "query", "value", "command", "item", "target")) {
                if (!call.arguments().has(key) || call.arguments().get(key).isJsonNull()) {
                    continue;
                }
                String value;
                try {
                    value = call.arguments().get(key).getAsString().strip();
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (!value.isBlank()) {
                    return key + " " + abbreviate(value, 96);
                }
            }
            return "";
        }

        private static String responseFingerprint(StreamingModelResponse response) {
            if (response == null) {
                return "null";
            }
            StringBuilder fingerprint = new StringBuilder(
                    abbreviate(response.text(), 1_024).toLowerCase(java.util.Locale.ROOT)
            );
            for (ModelToolCall call : response.toolCalls()) {
                fingerprint.append('|')
                        .append(call.toolId())
                        .append(':')
                        .append(call.arguments());
            }
            return fingerprint.toString();
        }

        private static String abbreviate(String value, int maximum) {
            String normalized = value == null
                    ? ""
                    : value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").strip();
            return normalized.length() <= maximum
                    ? normalized
                    : normalized.substring(0, Math.max(0, maximum - 1)) + "…";
        }

        private static String abbreviateRichText(String value, int maximum) {
            String normalized = value == null
                    ? ""
                    : value.replace("\r\n", "\n").replace('\r', '\n').strip();
            return normalized.length() <= maximum
                    ? normalized
                    : normalized.substring(0, Math.max(0, maximum - 1)) + "…";
        }

        private void fail(String code, String detail, Throwable cause) {
            if (!this.terminal.compareAndSet(false, true)) {
                return;
            }
            if (this.mode == RequestMode.AUTOMATION) {
                AutomationModeController.setDeepThinkingActive(false);
                AutomationModeController.setPlanningActive(false);
            }
            String safeCode = code == null || code.isBlank() ? "failed" : code;
            String safeDetail = detail == null || detail.isBlank() ? safeCode : detail;
            ModelRequestState state = "cancelled".equals(safeCode)
                    ? ModelRequestState.CANCELLED
                    : ModelRequestState.FAILED;
            ModelGenerationHudState.state(this.displayId, state, safeDetail);
            MinecraftClient current = MinecraftClient.getInstance();
            if (state == ModelRequestState.FAILED && current != null) {
                current.execute(() -> localError(current, "Local model request failed: " + safeDetail));
            }
            if (this.mode == RequestMode.AUTOMATION) {
                if (state == ModelRequestState.CANCELLED && AutomationModeController.isAutomationMode()) {
                    AutomationModeController.ready("request cancelled");
                } else {
                    AutomationModeController.unavailable(safeDetail);
                }
            }
        }

        private record AskCommandValidation(boolean valid, String detail) {
            private static AskCommandValidation accepted() {
                return new AskCommandValidation(true, "");
            }

            private static AskCommandValidation rejected(String detail) {
                return new AskCommandValidation(false, detail == null ? "" : detail);
            }
        }

        private enum PlanPhase {
            NONE,
            REVIEW,
            APPROVED,
            COMPLETED,
            FAILED,
            REJECTED,
            REVISION_REQUIRED
        }
    }

    private static final class SessionCancellation implements ModelCancellationHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<ModelCancellationHandle> active = new AtomicReference<>();
        private volatile String reason = "";

        private void bind(ModelCancellationHandle handle) {
            this.active.set(handle);
            if (handle != null && this.cancelled.get()) {
                handle.cancel(cancellationReason());
            }
        }

        @Override
        public boolean cancel(String reason) {
            if (!this.cancelled.compareAndSet(false, true)) {
                return false;
            }
            this.reason = reason == null || reason.isBlank() ? "cancelled" : reason;
            ModelCancellationHandle handle = this.active.get();
            if (handle != null) {
                handle.cancel(this.reason);
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && AutomationRouter.isTaskRunning()) {
                client.execute(() -> AutomationRouter.cancelCurrentTask(this.reason));
            }
            return true;
        }

        @Override
        public boolean isCancellationRequested() {
            return this.cancelled.get();
        }

        @Override
        public String cancellationReason() {
            return this.reason.isBlank() ? "cancelled" : this.reason;
        }
    }

    private static String message(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null && cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        if (cursor == null || cursor.getMessage() == null || cursor.getMessage().isBlank()) {
            return throwable == null ? "failed" : throwable.getClass().getSimpleName();
        }
        return cursor.getMessage();
    }

    private enum RequestMode {
        ASK,
        AUTOMATION
    }
}
