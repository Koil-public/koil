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
import com.spirit.koil.api.model.chat.LocalModelControlChatFeedback;
import com.spirit.koil.api.model.chat.ModelToolCallPresentation;
import com.spirit.koil.api.model.chat.ModelToolActivityPresentation;
import com.spirit.koil.api.model.planning.AutomationProgressGuard;
import com.spirit.koil.api.model.planning.NoFailExecutionPolicy;
import com.spirit.koil.api.automation.cli.AutomationCliViewModel;
import com.spirit.koil.api.model.chat.ModelActivityPresentation;
import com.spirit.koil.api.model.format.RichChatModelOutputSanitizer;
import com.spirit.koil.api.model.format.RichChatModelFormattingContract;
import com.spirit.koil.api.model.format.RichChatModelFinalFormatValidator;
import com.spirit.koil.api.model.hardware.HardwareCapabilityReport;
import com.spirit.koil.api.model.hardware.LocalModelHardwarePreflight;
import com.spirit.koil.api.model.catalog.LocalModelSelection;
import com.spirit.koil.api.model.catalog.LocalModelSelectionStore;
import com.spirit.koil.api.model.catalog.LocalModelCatalog;
import com.spirit.koil.api.model.catalog.LocalModelCatalogEntry;
import com.spirit.koil.api.model.catalog.LocalModelAutomationEligibility;
import com.spirit.koil.api.model.catalog.LocalModelReliabilityStore;
import com.spirit.koil.api.model.install.LocalModelInstallationService;
import com.spirit.koil.api.model.provider.colibri.ColibriConfiguration;
import com.spirit.koil.api.model.provider.colibri.ColibriConfigurationStore;
import com.spirit.koil.api.model.provider.colibri.ColibriLocalModelProvider;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppConfiguration;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppLocalModelProvider;
import com.spirit.koil.api.model.voice.ModelVoiceService;
import com.spirit.koil.api.model.tool.LocalModelToolCatalog;
import com.spirit.koil.api.model.planning.AutomationThinkingPolicy;
import com.spirit.koil.api.model.planning.AutomationToolCallLatencyPolicy;
import com.spirit.koil.api.model.planning.ConversationalReasoningPolicy;
import com.spirit.koil.api.model.planning.InformationToolCallLatencyPolicy;
import com.spirit.koil.api.model.planning.ModelInformationRetrievalPolicy;
import com.spirit.koil.api.model.planning.ValidatedAutomationPlan;
import com.spirit.koil.api.model.planning.ReviewedPlanAuthorization;
import com.spirit.koil.api.model.prompt.LocalModelAutomationPrompt;
import com.spirit.koil.api.model.tool.AutomationPlanModelToolRegistry;
import com.spirit.koil.api.model.tool.DeepThoughtReadOnlyToolCoordinator;
import com.spirit.koil.api.model.tool.ModelWorkspaceToolRegistry;
import com.spirit.koil.api.model.tool.ProjectValidationModelToolRegistry;
import com.spirit.koil.api.model.tool.MinecraftKnowledgeModelToolRegistry;
import com.spirit.koil.api.model.tool.InternetResearchModelToolRegistry;
import com.spirit.koil.api.model.tool.KoilDocumentationModelToolRegistry;
import com.spirit.koil.api.model.deepthought.DeepThoughtInvestigationController;
import com.spirit.koil.api.model.deepthought.DeepThoughtSession;
import com.spirit.koil.api.model.deepthought.DeepThoughtSessionStore;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

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
    private static final Map<UUID, GenerationSession> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicReference<String> RESTORED_DEEP_THOUGHT_IDENTITY = new AtomicReference<>("");

    private LocalModelService() {
    }

    public static void initialize() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        configuration = ColibriConfigurationStore.loadOrCreate();
        ModelExperimentalFeatures.reload();
        if (ModelExperimentalFeatures.snapshot().persistentConversationHistory()) {
            ModelConversationPersistence.restore(CONVERSATIONS);
        }
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
        LocalModelCatalog.refreshRemote(false).whenComplete((result, failure) -> {
            if (failure != null) {
                LocalModelRuntimeLog.write("catalog_refresh_failed", failure.getMessage());
            } else if (result != null) {
                LocalModelRuntimeLog.write(
                    "catalog_refresh",
                    result.detail() + " | candidates=" + result.candidatesSeen()
                        + " | promoted=" + result.builtInModelsPromoted()
                        + " | added=" + result.newModelsAdded()
                );
            }
        });
        LocalModelAutomationEligibility.Evaluation eligibility = currentAutomationEligibility();
        if (AutomationModeController.isAutomationMode() && !eligibility.eligible()) {
            revokeIneligibleAutomation(eligibility, false);
        }
    }

    public static boolean ask(String prompt) {
        return submitPrompt(prompt, RequestMode.ASK, true);
    }

    public static void refreshExperimentalFeatures() {
        ModelExperimentalFeatures.reload();
        if (ModelExperimentalFeatures.snapshot().persistentConversationHistory()) {
            ModelConversationPersistence.restore(CONVERSATIONS);
        }
    }

    public static boolean askDeep(String prompt) {
        return submitPrompt(prompt, RequestMode.ASK_DEEP, true);
    }

    public static boolean resumeDeepThought(String sessionId) {
        initialize();
        DeepThoughtSession saved = DeepThoughtSessionStore.load(deepThoughtScope()).stream()
            .filter(session -> session.deepThoughtSessionId.equals(sessionId))
            .findFirst().orElse(null);
        if (saved == null) return false;
        UUID requestId = UUID.randomUUID();
        ModelGenerationHudState.begin(requestId, saved.originalQuestion, false);
        if (saved.lifecycle == DeepThoughtSession.Lifecycle.COMPLETED) {
            ModelGenerationHudState.replaceText(requestId, saved.finalConclusion);
            ModelGenerationHudState.state(requestId, ModelRequestState.COMPLETED, "restored Deep Thought result");
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && saved.finalConclusion != null && !saved.finalConclusion.isBlank()) {
                client.execute(() -> {
                    ModelChatMessageBridge.addToChat(client, saved.finalConclusion);
                    ModelGenerationHudState.messagePresented(requestId);
                    CompletableFuture.runAsync(() -> DeepThoughtSessionStore.markFinalPresented(deepThoughtScope(), saved));
                });
            }
            return true;
        }
        ModelConversation conversation = CONVERSATIONS.conversation(ModelConversationRegistry.GENERAL);
        GenerationSession session = new GenerationSession(requestId, saved.originalQuestion, RequestMode.ASK_DEEP, conversation, saved);
        SESSIONS.put(requestId, session);
        ModelGenerationHudState.bindCancellation(requestId, session.cancellation);
        if (saved.lifecycle == DeepThoughtSession.Lifecycle.PAUSED) session.deepThought.resume();
        session.submitGeneration();
        return true;
    }

    public static void restoreDeepThoughtForCurrentScope() {
        initialize();
        String scope = deepThoughtScope();
        DeepThoughtSession saved = DeepThoughtSessionStore.newestRestorable(scope);
        if (saved == null) return;
        String restoreIdentity = scope + ":" + saved.deepThoughtSessionId + ":" + saved.updatedAtMillis;
        if (restoreIdentity.equals(RESTORED_DEEP_THOUGHT_IDENTITY.getAndSet(restoreIdentity))) return;
        if (saved.lifecycle == DeepThoughtSession.Lifecycle.COMPLETED) {
            UUID requestId = UUID.randomUUID();
            ModelGenerationHudState.begin(requestId, saved.originalQuestion, false);
            ModelGenerationHudState.replaceText(requestId, saved.finalConclusion);
            ModelGenerationHudState.state(requestId, ModelRequestState.COMPLETED, "restored Deep Thought result");
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && saved.finalConclusion != null && !saved.finalConclusion.isBlank()) {
                client.execute(() -> {
                    ModelChatMessageBridge.addToChat(client, saved.finalConclusion);
                    ModelGenerationHudState.messagePresented(requestId);
                    CompletableFuture.runAsync(() -> DeepThoughtSessionStore.markFinalPresented(scope, saved));
                });
            }
            return;
        }
        if (!resumeDeepThought(saved.deepThoughtSessionId)) {
            RESTORED_DEEP_THOUGHT_IDENTITY.compareAndSet(restoreIdentity, "");
        }
    }

    public static void clearDeepThoughtLifecycleRestoreIdentity() {
        RESTORED_DEEP_THOUGHT_IDENTITY.set("");
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
        LocalModelAutomationEligibility.Evaluation eligibility = selectedAutomationEligibility();
        if (!eligibility.eligible() && !experimentalAutomationAllowed()) {
            revokeIneligibleAutomation(eligibility, true);
            return false;
        }
        return submitPrompt(prompt, RequestMode.AUTOMATION, echoLocalPrompt);
    }

    public static void prepareAutomationMode() {
        initialize();
        LocalModelAutomationEligibility.Evaluation eligibility = currentAutomationEligibility();
        if (!eligibility.eligible() && !experimentalAutomationAllowed()) {
            revokeIneligibleAutomation(eligibility, true);
            return;
        }
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
        String conversationId = mode != RequestMode.AUTOMATION
            ? ModelConversationRegistry.GENERAL
            : ModelConversationRegistry.AUTOMATION;
        if (echoLocalPrompt) {
            LocalModelPromptChatBridge.addLocalPrompt(client, normalized);
        }
        ModelConversation conversation = CONVERSATIONS.conversation(conversationId);
        UUID requestId = UUID.randomUUID();
        if (mode == RequestMode.AUTOMATION) {
            AutomationModeController.executing("model generation");
        }
        ModelGenerationHudState.begin(requestId, normalized, mode == RequestMode.AUTOMATION);
        GenerationSession session = new GenerationSession(requestId, normalized, mode, conversation);
        SESSIONS.put(requestId, session);
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

    public static boolean hasActiveWork() {
        return SESSIONS.values().stream().anyMatch(session -> !session.terminal.get());
    }

    public static List<QueuedPrompt> queuedPrompts() {
        return SESSIONS.values().stream()
            .filter(session -> !session.dispatched.get() && !session.terminal.get())
            .sorted(java.util.Comparator.comparingLong(session -> session.createdAtMillis))
            .map(session -> new QueuedPrompt(
                session.displayId, session.mode.name().toLowerCase(java.util.Locale.ROOT),
                session.prompt, session.promptRevision
            ))
            .toList();
    }

    public static boolean editQueuedPrompt(UUID displayId, long expectedRevision, String prompt) {
        GenerationSession session = displayId == null ? null : SESSIONS.get(displayId);
        return session != null && session.editQueuedPrompt(expectedRevision, prompt);
    }

    public record QueuedPrompt(UUID requestId, String mode, String prompt, long revision) {}

    public static int configuredContextWindowTokens() {
        initialize();
        return runtime.selectedMaximumContextTokens();
    }

    public static boolean experimentalAutomationAllowed() {
        initialize();
        LocalModelCatalogEntry entry = LocalModelCatalog.find(selectedCatalogId()).orElse(null);
        ModelCapabilityDescriptor provider = runtime.selectedCapabilities();
        return AutomationModeController.isExperimentalCompactAgentEnabled()
            && entry != null
            && entry.toolCalling()
            && !LocalModelReliabilityStore.quarantined(entry)
            && provider != null
            && provider.toolCalling();
    }

    public static ModelAgentCapabilityProfile selectedAgentProfile() {
        initialize();
        LocalModelCatalogEntry entry = LocalModelCatalog.find(selectedCatalogId()).orElse(null);
        ModelCapabilityDescriptor provider = runtime.selectedCapabilities();
        int estimate = entry == null ? 0 : entry.complexReasoningEstimatePercent();
        double parameters = selectedModelParametersBillions();
        boolean toolCalling = entry != null && entry.toolCalling() && provider != null && provider.toolCalling();
        boolean staged = parameters <= 4.0D || estimate <= 50;
        ModelAgentCapabilityProfile.ToolReliability reliability = !toolCalling
            ? ModelAgentCapabilityProfile.ToolReliability.NONE
            : estimate >= 65 && parameters > 3.5D
            ? ModelAgentCapabilityProfile.ToolReliability.RELIABLE
            : ModelAgentCapabilityProfile.ToolReliability.WEAK;
        return new ModelAgentCapabilityProfile(
            configuredModelId(), selectedProviderId(), reliability,
            estimate >= 55, estimate >= 70 && parameters >= 7.0D,
            staged ? 6 : 16,
            estimate >= 70 ? ModelAgentCapabilityProfile.PlanningReliability.RELIABLE
                : estimate >= 45 ? ModelAgentCapabilityProfile.PlanningReliability.NORMAL
                : ModelAgentCapabilityProfile.PlanningReliability.WEAK,
            provider == null ? 0 : provider.maximumContextTokens(),
            parameters >= 7.0D, true, estimate >= 75,
            staged ? 2 : 4,
            "colibri".equals(selectedProviderId()) ? "anthropic_tool_use" : "openai_tool_calls",
            staged
        );
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

    public static LocalModelAutomationEligibility.Evaluation selectedAutomationEligibility() {
        initialize();
        return currentAutomationEligibility();
    }

    private static LocalModelAutomationEligibility.Evaluation currentAutomationEligibility() {
        LocalModelCatalogEntry entry = LocalModelCatalog.find(selection.catalogId()).orElse(null);
        return LocalModelAutomationEligibility.evaluate(entry);
    }

    public static void revokeIneligibleAutomation(
        LocalModelAutomationEligibility.Evaluation eligibility,
        boolean showFeedback
    ) {
        if (eligibility == null || eligibility.eligible()) {
            return;
        }
        AutomationModeController.unavailable(eligibility.detail());
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.execute(() -> {
                if (AutomationRouter.isTaskRunning()) {
                    AutomationRouter.cancelCurrentTask("selected model is below the Automation complexity requirement");
                }
            });
        }
        if (showFeedback && client != null) {
            client.execute(() -> LocalModelControlChatFeedback.error(eligibility.detail()));
        }
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
        if (mode == RequestMode.ASK || mode == RequestMode.ASK_DEEP) {
            String askBoundary = toolsAvailable
                ? """
                    /ask is conversational. Every supplied tool is a read-only evidence source only: it may inspect Minecraft knowledge/state, read bounded workspace text, or research public internet sources. It cannot execute commands or gameplay, synthesize player input, mutate files/workspaces, or perform Automation. Use one relevant lookup at a time, continue chunked reads with the returned nextStartLine when needed, preserve exact identifiers/evidence, and never substitute a nearby valid target for a requested target that does not exist.
                    """
                : """
                    /ask is conversational and has no tools for this turn. Never claim to run code, commands, KTL, automation, or Minecraft actions. Command links are suggestions only.
                    """;
            return LocalModelSystemPrompt.load() + askBoundary + """
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
            AutomationModeController.isUnrestrictedMode(),
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

    private static List<ModelToolDefinition> readOnlyDeepThoughtTools(String prompt) {
        List<ModelToolDefinition> available = new ArrayList<>();
        available.addAll(com.spirit.koil.api.model.tool.MinecraftKnowledgeModelToolRegistry.modelTools());
        ModelWorkspaceToolRegistry.modelTools().stream()
            .filter(tool -> Set.of(
                "workspace.roots", "workspace.list", "workspace.stat", "workspace.read", "workspace.search"
            ).contains(tool.id()))
            .forEach(available::add);
        available.addAll(InternetResearchModelToolRegistry.modelTools());
        available.addAll(KoilDocumentationModelToolRegistry.modelTools());
        return List.copyOf(available);
    }

    private static List<ModelToolDefinition> readOnlyAskTools(
        String prompt,
        ConversationalReasoningPolicy.Decision decision
    ) {
        if (decision == null) return List.of();
        return LocalModelToolCatalog.informationToolsForPrompt(prompt);
    }

    private static String deepThoughtScope() {
        MinecraftClient client = MinecraftClient.getInstance();
        String identity = "global";
        if (client != null && client.getCurrentServerEntry() != null) {
            identity = "server:" + client.getCurrentServerEntry().address;
        } else if (client != null && client.isInSingleplayer()) {
            String worldName = client.getServer() == null
                ? "current"
                : client.getServer().getSaveProperties().getLevelName();
            identity = "singleplayer:" + worldName;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (Exception ignored) {
            return "global";
        }
    }

    public static List<DeepThoughtSession> persistedDeepThoughtSessions() {
        return DeepThoughtSessionStore.load(deepThoughtScope());
    }

    private static ModelRequestState deepThoughtRequestState(DeepThoughtSession.Phase phase) {
        return switch (phase) {
            case DEFINE, DECOMPOSE -> ModelRequestState.THINKING;
            case DISCOVER, COLLECT -> ModelRequestState.INSPECTING;
            case HYPOTHESIZE, CHALLENGE -> ModelRequestState.THINKING;
            case TEST, VERIFY -> ModelRequestState.VALIDATING;
            case RECONCILE -> ModelRequestState.OBSERVING_RESULT;
            case SCORE, DECIDE -> ModelRequestState.THINKING;
            case FINALIZE -> ModelRequestState.FINALIZING;
        };
    }

    private static final class GenerationSession implements ModelFinalizationHandle, ModelDeepThoughtControl {
        private static final int MAXIMUM_IDENTICAL_CALLS = 2;
        private static final int MAXIMUM_IDENTICAL_RESPONSES = 2;
        private static final int MAXIMUM_GROUNDED_ASK_TOOL_ROUNDS = 4;

        private final UUID displayId;
        private volatile String prompt;
        private final RequestMode mode;
        private final ModelConversation conversation;
        private final List<ModelToolDefinition> tools;
        private final AutomationThinkingPolicy.Decision thinking;
        private final ConversationalReasoningPolicy.Decision conversationalThinking;
        private final ModelAgentCapabilityProfile capabilityProfile;
        private final DeepThoughtInvestigationController deepThought;
        private final boolean forcedPlanning;
        private final long startedAtMillis = System.currentTimeMillis();
        private final Map<String, Integer> repeatedCalls = new LinkedHashMap<>();
        private final Map<String, Integer> repeatedResponses = new LinkedHashMap<>();
        private final Set<String> completedToolIds = new LinkedHashSet<>();
        private final Set<String> requiredToolIds;
        private final ModelDurableTaskState durableState;
        private final ModelObjectiveLedger objectiveLedger;
        private final SessionCancellation cancellation;
        private final AutomationProgressGuard automationProgress = new AutomationProgressGuard();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean finalizationRequested = new AtomicBoolean();
        private final AtomicBoolean dispatched = new AtomicBoolean();
        private final long createdAtMillis = System.currentTimeMillis();
        private volatile long promptRevision = 1L;
        private final AtomicReference<ModelCancellationHandle> activeProviderRound = new AtomicReference<>();
        private final AtomicReference<UUID> activeProviderRoundId = new AtomicReference<>();
        private final AtomicReference<String> streamedVoiceResponse = new AtomicReference<>("");
        private int toolCallCount;
        private int toolResultsReceived;
        private int successfulToolOutputs;
        private int successfulActionToolOutputs;
        private boolean actionToolAttempted;
        private int continuationCorrectionCount;
        private int askFormattingCorrectionCount;
        private int providerRoundCount;
        private int emptyResponseCorrections;
        private int groundedAskToolRounds;
        private int groundedAskCorrectionCount;
        private boolean groundedAskFinalizing;
        private int finalFormattingCorrectionCount;
        private boolean formattingCorrectionActive;
        private boolean groundedAskCommandAttempted;
        private ValidatedAutomationPlan validatedPlan;
        private ReviewedPlanAuthorization planAuthorization;
        private PlanPhase planPhase = PlanPhase.NONE;
        private int planRevisionCount;
        private String lastReplanEvidenceFingerprint = "";
        private boolean directToolDecisionSession;
        private boolean directInformationDecisionRound;

        private GenerationSession(UUID displayId, String prompt, RequestMode mode, ModelConversation conversation) {
            this(displayId, prompt, mode, conversation, null);
        }

        private GenerationSession(UUID displayId, String prompt, RequestMode mode, ModelConversation conversation,
            DeepThoughtSession restoredDeepThought) {
            this.displayId = displayId;
            this.cancellation = new SessionCancellation(displayId);
            this.prompt = prompt;
            this.mode = mode;
            this.conversation = conversation;
            this.capabilityProfile = selectedAgentProfile();
            this.forcedPlanning = mode == RequestMode.AUTOMATION
                && AutomationModeController.isPlanningModeEnabled();
            this.thinking = AutomationThinkingPolicy.evaluate(
                prompt,
                mode == RequestMode.AUTOMATION
                    && AutomationModeController.isDeepThinkingEnabled(),
                this.forcedPlanning
            );
            int currentConversationCharacters = conversation.snapshot().stream()
                .mapToInt(message -> message.content().length()).sum();
            this.conversationalThinking = mode == RequestMode.AUTOMATION
                ? null
                : ConversationalReasoningPolicy.evaluate(
                prompt,
                currentConversationCharacters,
                this.capabilityProfile,
                mode == RequestMode.ASK_DEEP
            );
            this.tools = mode == RequestMode.AUTOMATION
                ? LocalModelToolCatalog.toolsForPrompt(prompt, this.thinking.includePlanTool())
                : mode == RequestMode.ASK_DEEP
                ? readOnlyDeepThoughtTools(prompt)
                : readOnlyAskTools(prompt, this.conversationalThinking);
            this.requiredToolIds = mode == RequestMode.AUTOMATION
                ? LocalModelToolCatalog.requiredToolIdsForPrompt(prompt)
                : Set.of();
            this.durableState = new ModelDurableTaskState(prompt, this.requiredToolIds);
            this.objectiveLedger = mode == RequestMode.AUTOMATION
                ? ModelObjectiveLedger.parse(prompt)
                : ModelObjectiveLedger.parse("");
            if (mode == RequestMode.AUTOMATION) {
                AutomationModeController.setDeepThinkingActive(this.thinking.deepActive());
            }
            this.deepThought = mode == RequestMode.ASK_DEEP
                || mode == RequestMode.AUTOMATION && this.thinking.deepActive()
                ? new DeepThoughtInvestigationController(
                deepThoughtScope(),
                restoredDeepThought == null
                    ? new DeepThoughtSession(displayId.toString(), conversation.id(), prompt)
                    : restoredDeepThought
            )
                : null;
            if (this.deepThought != null) {
                ModelGenerationHudState.bindDeepThought(displayId, this);
            }
            ModelGenerationHudState.bindFinalization(displayId, this);
            ModelGenerationHudState.setAnswerNowVisible(
                displayId,
                this.conversationalThinking != null && this.conversationalThinking.answerNowAvailable()
            );
            if (mode == RequestMode.ASK) {
                String summary = !this.tools.isEmpty()
                    ? "I’m checking the smallest relevant read-only source before answering."
                    : this.conversationalThinking.depth() == ConversationalReasoningPolicy.Depth.DIRECT
                    ? "I’m preparing a brief direct response."
                    : this.conversationalThinking.reviewContext()
                    ? "I’m reviewing the request and the relevant conversation context."
                    : "I’m interpreting the request and preparing the most useful response.";
                ModelGenerationHudState.appendEvent(
                    displayId,
                    ModelGenerationHudState.ActivityEventType.THOUGHT_SUMMARY,
                    summary
                );
            }
        }

        private void submitGeneration() {
            if (this.cancellation.isCancellationRequested()) {
                fail("cancelled", this.cancellation.cancellationReason(), null);
                return;
            }
            this.providerRoundCount++;
            int maximumRounds = this.mode == RequestMode.AUTOMATION
                ? Integer.MAX_VALUE
                : this.conversationalThinking.maximumProviderRounds();
            if (this.mode == RequestMode.ASK && !this.tools.isEmpty()) {
                maximumRounds = Math.max(maximumRounds, MAXIMUM_GROUNDED_ASK_TOOL_ROUNDS + 2);
            }
            if (this.formattingCorrectionActive) maximumRounds++;
            if (this.mode != RequestMode.AUTOMATION
                && this.providerRoundCount > maximumRounds && !this.finalizationRequested.get()) {
                fail(
                    "model_reasoning_loop",
                    "The model exceeded the bounded planning-round budget without reaching a final result.",
                    null
                );
                return;
            }
            UUID providerRequestId = UUID.randomUUID();
            this.activeProviderRoundId.set(providerRequestId);
            boolean streamUserFacingVoice = this.mode == RequestMode.ASK
                || this.mode == RequestMode.AUTOMATION
                || this.mode == RequestMode.ASK_DEEP
                && (this.finalizationRequested.get()
                || this.deepThought != null
                && this.deepThought.session().phase == DeepThoughtSession.Phase.FINALIZE);
            ModelVoiceService.StreamingSpeech streamingSpeech = streamUserFacingVoice
                && ModelVoiceService.settings().enabled()
                ? ModelVoiceService.beginStreaming()
                : null;
            ModelGenerationHudState.replaceText(this.displayId, "");
            boolean directVerifiedResult = !this.formattingCorrectionActive
                && AutomationToolCallLatencyPolicy.useDirectVerifiedResultRound(
                this.directToolDecisionSession,
                this.toolResultsReceived,
                this.successfulActionToolOutputs,
                this.objectiveLedger.allCompleted(),
                this.validatedPlan != null
            );
            List<ModelToolDefinition> requestTools = this.formattingCorrectionActive || directVerifiedResult
                ? List.of()
                : this.mode == RequestMode.AUTOMATION
                ? LocalModelToolCatalog.toolsForRound(
                this.prompt,
                this.thinking.includePlanTool(),
                this.capabilityProfile.stagedExecution(),
                this.toolResultsReceived > 0
            )
                : this.mode == RequestMode.ASK_DEEP && !this.finalizationRequested.get()
                ? this.tools
                : this.mode == RequestMode.ASK && !this.groundedAskFinalizing
                ? this.tools
                : List.of();
            if (this.mode == RequestMode.AUTOMATION && this.capabilityProfile.stagedExecution()) {
                requestTools = limitToolsForProfile(requestTools, remainingRequiredToolIds(),
                    this.capabilityProfile.maximumRecommendedToolsPerRound());
            }
            if (this.mode == RequestMode.ASK
                && this.toolResultsReceived == 0
                && requestTools.stream().anyMatch(tool -> InternetResearchModelToolRegistry.SEARCH.equals(tool.id()))
                && requestTools.stream().anyMatch(tool -> InternetResearchModelToolRegistry.FETCH.equals(tool.id()))) {
                requestTools = requestTools.stream()
                    .filter(tool -> !InternetResearchModelToolRegistry.FETCH.equals(tool.id()))
                    .toList();
            }
            if (this.mode == RequestMode.ASK
                && this.capabilityProfile.stagedExecution()
                && this.toolResultsReceived == 0
                && requestTools.size() > 3) {
                requestTools = List.copyOf(requestTools.subList(0, 3));
            }
            AutomationToolCallLatencyPolicy.Decision latencyDecision = this.mode == RequestMode.AUTOMATION
                ? AutomationToolCallLatencyPolicy.evaluate(
                this.prompt,
                this.thinking,
                this.requiredToolIds,
                requestTools,
                AutomationModeController.isPlanningModeEnabled(),
                this.providerRoundCount == 1 && !this.finalizationRequested.get()
            )
                : null;
            boolean directToolDecision = latencyDecision != null && latencyDecision.directToolDecision();
            if (directToolDecision) this.directToolDecisionSession = true;
            InformationToolCallLatencyPolicy.Decision informationLatencyDecision = this.mode == RequestMode.ASK
                ? InformationToolCallLatencyPolicy.evaluate(
                selectedModelParametersBillions(),
                this.conversationalThinking,
                requestTools,
                this.providerRoundCount == 1 && !this.finalizationRequested.get()
            )
                : null;
            boolean directInformationDecision = informationLatencyDecision != null
                && informationLatencyDecision.directToolDecision();
            this.directInformationDecisionRound = directInformationDecision;
            String promptContract = directVerifiedResult
                ? LocalModelSystemPrompt.directAutomationResultPrompt()
                : directToolDecision
                ? LocalModelSystemPrompt.directAutomationToolPrompt()
                + "\n\n"
                + LocalModelAutomationPrompt.directActionRules(
                AutomationModeController.isUnrestrictedMode(),
                ModelExperimentalFeatures.snapshot().noFailEnabled(),
                AutomationModeController.isVerificationEnabled()
            )
                : directInformationDecision
                ? LocalModelSystemPrompt.directInformationToolPrompt()
                : this.mode == RequestMode.ASK
                && requestTools.isEmpty()
                && this.conversationalThinking.depth() == ConversationalReasoningPolicy.Depth.DIRECT
                ? LocalModelSystemPrompt.directConversationPrompt()
                : systemPrompt(this.mode, !requestTools.isEmpty(), this.thinking);
            String informationPolicy = ModelInformationRetrievalPolicy.promptFor(requestTools);
            if (!informationPolicy.isBlank()) {
                promptContract += "\n\n" + informationPolicy;
            }
            if (this.finalizationRequested.get()) {
                promptContract += "\nAnswer Now is active. Produce one complete user-facing answer from the verified work already available. Do not begin optional analysis or a new tool action.";
            }
            if (this.deepThought != null) {
                promptContract += "\n\nDeep Thought session " + this.deepThought.session().deepThoughtSessionId
                    + " is in phase " + this.deepThought.session().phase.name().toLowerCase(java.util.Locale.ROOT)
                    + ". Narrow task: " + this.deepThought.instruction()
                    + "\nNever expose private chain-of-thought. Return only an intentional concise reasoning artifact or a registered read-only tool call.";
                ModelGenerationHudState.state(this.displayId, deepThoughtRequestState(this.deepThought.session().phase),
                    this.deepThought.session().phase.name().toLowerCase(java.util.Locale.ROOT));
            }
            if (this.mode == RequestMode.AUTOMATION && (this.toolResultsReceived > 0 || this.validatedPlan != null)) {
                promptContract += "\n\n" + this.durableState.promptSummary();
            }
            if (this.mode == RequestMode.AUTOMATION
                && !directToolDecision
                && !directVerifiedResult
                && ModelExperimentalFeatures.snapshot().noFailEnabled()
                && !this.finalizationRequested.get()) {
                promptContract += """


                        No-Fail experiment is active for this Automation request. Continue through the registered tool loop until a validated successful tool output satisfies every known objective. A failed, blocked, partial, missing, unsupported, or unchanged result is evidence for a changed tool, changed arguments, a new observation, or a re-plan; it is not permission to claim completion. Verification, when enabled, must pass before a completed output counts. Never bypass approval or Minecraft permissions, never substitute a different target, never repeat an identical call against unchanged evidence, and always honor explicit Stop/cancellation.
                        """;
            }
            if (!directToolDecision
                && ModelExperimentalFeatures.snapshot().expertPrefetchEnabled()
                && this.providerRoundCount == 1) {
                promptContract += "\n\n" + ModelExpertPrefetch.capture();
            }
            if (!directToolDecision && !directVerifiedResult
                && ModelExperimentalFeatures.snapshot().persistentAssociativeMemory()) {
                String memory = ModelAssociativeMemory.relevantContext(this.prompt);
                if (!memory.isBlank()) {
                    promptContract += "\n\nRelevant associative memory (prior final exchanges; verify against current state):\n" + memory;
                }
            }
            List<ModelMessage> requestMessages = new ArrayList<>(directVerifiedResult
                ? this.conversation.snapshotWithin(3, 8 * 1024)
                : directInformationDecision && informationLatencyDecision.freshConversationWindow()
                ? List.of()
                : directToolDecision
                && latencyDecision.freshConversationWindow()
                ? List.of()
                : this.conversation.snapshotWithin(
                conversationMessageBudget(this.mode),
                conversationCharacterBudget(this.mode)
            ));
            if (!this.dispatched.get()) requestMessages.add(ModelMessage.user(this.prompt));
            requestMessages = List.copyOf(requestMessages);
            int requestContextCharacters = requestMessages.stream()
                .mapToInt(message -> message.content().length())
                .sum();
            LocalModelRuntimeLog.write(
                "request_context",
                this.mode.name().toLowerCase(java.util.Locale.ROOT)
                    + " | system_chars=" + promptContract.length()
                    + " | history_chars=" + requestContextCharacters
                    + " | tools=" + requestTools.size()
                    + " | latency_path=" + (directToolDecision ? "direct_tool"
                    : directVerifiedResult ? "direct_result"
                    : directInformationDecision ? "direct_information" : "full_agent")
            );
            StreamingModelRequest request = new StreamingModelRequest(
                providerRequestId,
                this.conversation.id(),
                promptContract,
                requestMessages,
                this.finalizationRequested.get() ? List.of() : requestTools,
                this.mode == RequestMode.AUTOMATION
                    ? directToolDecision
                    ? latencyDecision.maximumOutputTokens()
                    : directVerifiedResult
                    ? 192
                    : (this.capabilityProfile.stagedExecution() ? 768 : 1280)
                    : directInformationDecision
                    ? informationLatencyDecision.maximumOutputTokens()
                    : this.conversationalThinking.maximumOutputTokens(),
                configuration.requestTimeout(),
                Map.of(
                    "cache_slot", this.mode == RequestMode.AUTOMATION && configuration.kvSlots() > 1 ? "1" : "0",
                    "mode", this.mode == RequestMode.AUTOMATION ? "automation"
                        : this.mode == RequestMode.ASK_DEEP ? "ask_deep" : "ask",
                    "tool_registry_version", !requestTools.isEmpty()
                        ? LocalModelToolCatalog.version()
                        : "",
                    "tool_count", Integer.toString(requestTools.size()),
                    "context_characters", Integer.toString(requestContextCharacters),
                    "system_prompt_characters", Integer.toString(promptContract.length()),
                    "display_request_id", this.displayId.toString(),
                    "reasoning_depth", this.mode == RequestMode.AUTOMATION
                        ? this.thinking.depth().name().toLowerCase(java.util.Locale.ROOT)
                        : this.conversationalThinking.depth().name().toLowerCase(java.util.Locale.ROOT),
                    "staged_execution", Boolean.toString(this.capabilityProfile.stagedExecution()),
                    "latency_path", directToolDecision ? "direct_tool"
                        : directVerifiedResult ? "direct_result"
                        : directInformationDecision ? "direct_information" : "full_agent"
                )
            );
            ManagedModelRequest managed = runtime.submit(request, new StreamingModelObserver() {
                @Override
                public void onState(UUID id, ModelRequestState state, String detail) {
                    if (providerRequestId.equals(activeProviderRoundId.get()) && !state.terminal()) {
                        if (state != ModelRequestState.QUEUED && dispatched.compareAndSet(false, true)) {
                            conversation.add(ModelMessage.user(prompt));
                        }
                        ModelGenerationHudState.state(displayId, state, detail);
                    }
                }

                @Override
                public void onTextDelta(UUID id, String delta) {
                    if (providerRequestId.equals(activeProviderRoundId.get())) {
                        ModelGenerationHudState.append(displayId, delta);
                        if (streamingSpeech != null) {
                            streamingSpeech.accept(delta);
                        }
                    }
                }

                @Override
                public void onUsage(UUID id, ModelUsage usage) {
                    if (providerRequestId.equals(activeProviderRoundId.get())) {
                        ModelGenerationHudState.usage(displayId, usage);
                    }
                }

                @Override
                public void onComplete(StreamingModelResponse response) {
                    if (providerRequestId.equals(activeProviderRoundId.get())) {
                        if (streamingSpeech != null) {
                            streamingSpeech.finish();
                            streamedVoiceResponse.set(response == null ? "" : response.text());
                        }
                        handleResponse(response);
                    } else if (streamingSpeech != null) {
                        streamingSpeech.discard();
                    }
                }

                @Override
                public void onFailure(UUID id, String code, String detail, Throwable cause) {
                    if (streamingSpeech != null) {
                        streamingSpeech.discard();
                    }
                    if (!providerRequestId.equals(activeProviderRoundId.get())) {
                        return;
                    }
                    if (finalizationRequested.get() && !terminal.get()
                        && ("cancelled".equals(code) || "request_cancelled".equals(code))) {
                        submitGeneration();
                        return;
                    }
                    fail(code, detail, cause);
                }
            });
            this.activeProviderRound.set(managed.cancellation());
            this.cancellation.bind(managed.cancellation());
        }

        private synchronized boolean editQueuedPrompt(long expectedRevision, String replacement) {
            String normalized = replacement == null ? "" : replacement.strip();
            UUID providerId = this.activeProviderRoundId.get();
            if (this.dispatched.get() || this.terminal.get() || normalized.isBlank()
                || expectedRevision != this.promptRevision || providerId == null
                || !runtime.replaceQueuedPrompt(providerId, expectedRevision, normalized)) {
                return false;
            }
            this.prompt = normalized;
            this.promptRevision++;
            ModelGenerationHudState.replacePrompt(this.displayId, normalized);
            return true;
        }

        @Override
        public boolean requestAnswerNow() {
            if (this.mode == RequestMode.AUTOMATION || this.terminal.get()
                || !this.finalizationRequested.compareAndSet(false, true)) {
                return false;
            }
            ModelVoiceService.stopSpeaking("answer now requested");
            if (this.deepThought != null) this.deepThought.answerNow();
            ModelGenerationHudState.state(this.displayId, ModelRequestState.FINALIZING, "answer now requested");
            this.conversation.add(ModelMessage.user(
                "Answer now from the useful work already completed. Do not perform more optional analysis. "
                    + "State limitations and unresolved uncertainty truthfully."
            ));
            ModelCancellationHandle round = this.activeProviderRound.get();
            if (round != null) {
                round.cancel("answer now requested");
            }
            submitGeneration();
            return true;
        }

        @Override
        public boolean isFinalizationRequested() {
            return this.finalizationRequested.get();
        }

        @Override
        public boolean pause() {
            if (this.deepThought == null || this.terminal.get()
                || this.deepThought.session().lifecycle == DeepThoughtSession.Lifecycle.PAUSED) return false;
            this.deepThought.pause();
            ModelCancellationHandle round = this.activeProviderRound.get();
            this.activeProviderRoundId.set(null);
            if (round != null) round.cancel("deep thought paused");
            ModelGenerationHudState.state(this.displayId, ModelRequestState.PAUSED, "deep thought paused");
            return true;
        }

        @Override
        public boolean resume() {
            if (this.deepThought == null || this.terminal.get()
                || this.deepThought.session().lifecycle != DeepThoughtSession.Lifecycle.PAUSED) return false;
            this.deepThought.resume();
            submitGeneration();
            return true;
        }

        @Override
        public ModelDeepThoughtControl.Status status() {
            if (this.deepThought == null) return null;
            DeepThoughtSession session = this.deepThought.session();
            return new ModelDeepThoughtControl.Status(
                session.deepThoughtSessionId, session.phase.name().toLowerCase(java.util.Locale.ROOT),
                session.activeMillis, session.evidence.size(),
                (int) session.claims.stream().filter(claim -> "supported".equals(claim.state()) || "independently_verified".equals(claim.state())).count(),
                (int) session.claims.stream().filter(claim -> !"supported".equals(claim.state()) && !"independently_verified".equals(claim.state())).count(),
                session.hypotheses.size(),
                (int) session.contradictions.stream().filter(value -> !"resolved".equals(value.state())).count(),
                (int) session.tests.stream().filter(value -> "passed".equals(value.state())).count(),
                (int) session.tests.stream().filter(value -> "failed".equals(value.state())).count(),
                session.confidence, session.lastMeaningfulDiscovery,
                session.lifecycle == DeepThoughtSession.Lifecycle.PAUSED
            );
        }

        private int conversationMessageBudget(RequestMode requestMode) {
            if (ModelExperimentalFeatures.snapshot().gigatokenEnabled()) return 48;
            if (requestMode == RequestMode.AUTOMATION) {
                return 20;
            }
            return selectedModelParametersBillions() <= 3.5D ? 16 : 24;
        }

        private static List<ModelToolDefinition> limitToolsForProfile(
            List<ModelToolDefinition> tools,
            Set<String> required,
            int maximum
        ) {
            if (tools.size() <= maximum || maximum <= 0) return tools;
            LinkedHashMap<String, ModelToolDefinition> selected = new LinkedHashMap<>();
            for (ModelToolDefinition tool : tools) if (required.contains(tool.id())) selected.put(tool.id(), tool);
            for (String id : List.of(AutomationPlanModelToolRegistry.TOOL_ID, "workspace.read", "workspace.search",
                "minecraft.knowledge", "automation.cancel")) {
                tools.stream().filter(tool -> id.equals(tool.id())).findFirst().ifPresent(tool -> selected.put(tool.id(), tool));
            }
            for (ModelToolDefinition tool : tools) {
                if (selected.size() >= maximum) break;
                selected.putIfAbsent(tool.id(), tool);
            }
            return selected.values().stream().limit(maximum).toList();
        }

        private int conversationCharacterBudget(RequestMode requestMode) {
            if (ModelExperimentalFeatures.snapshot().gigatokenEnabled()) return 64 * 1024;
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
            if (response != null && response.text().isBlank() && response.toolCalls().isEmpty()) {
                if (noFailExecutionRequired()) {
                    this.emptyResponseCorrections++;
                    continueNoFail("empty_provider_response", "");
                } else if (this.emptyResponseCorrections++ < 2) {
                    this.conversation.add(ModelMessage.user(
                        "The provider returned no visible answer or tool call. Return one concise non-empty answer, "
                            + "or one valid supported tool call if this mode permits tools."
                    ));
                    ModelGenerationHudState.state(this.displayId, ModelRequestState.RETRYING, "repairing empty response");
                    submitGeneration();
                } else {
                    fail("empty_response", "The provider repeatedly returned no visible answer or tool call.", null);
                }
                return;
            }
            String responseFingerprint = responseFingerprint(response);
            int repeatedResponse = this.repeatedResponses.merge(responseFingerprint, 1, Integer::sum);
            if (repeatedResponse > MAXIMUM_IDENTICAL_RESPONSES) {
                if (noFailExecutionRequired()) {
                    this.repeatedResponses.clear();
                    continueNoFail("identical_model_response", "");
                } else {
                    fail(
                        "model_reasoning_loop",
                        "The model repeated the same planning response without making progress.",
                        null
                    );
                }
                return;
            }
            LocalModelRuntimeLog.write(
                "response_summary",
                this.mode.name().toLowerCase(java.util.Locale.ROOT)
                    + " | text_chars=" + response.text().length()
                    + " | tools=" + response.toolCalls().size()
                    + " | finish=" + response.providerFinishReason()
            );
            if (this.mode == RequestMode.ASK_DEEP) {
                handleDeepThoughtResponse(response);
                return;
            }
            if (this.mode != RequestMode.AUTOMATION) {
                if (!response.toolCalls().isEmpty()) {
                    handleGroundedAskToolCalls(response.text(), response.toolCalls());
                    return;
                }
                if (this.mode == RequestMode.ASK
                    && this.directInformationDecisionRound
                    && this.toolResultsReceived == 0
                    && this.groundedAskCorrectionCount++ < 1) {
                    archiveVisibleSummary(response.text());
                    this.conversation.add(ModelMessage.assistant(response.text()));
                    this.conversation.add(ModelMessage.user(
                        "The compact evidence round returned prose without evidence. Call exactly one supplied read-only tool now, using the smallest valid arguments. Do not claim an action or answer from memory."
                    ));
                    ModelGenerationHudState.state(
                        this.displayId,
                        ModelRequestState.RETRYING,
                        "requesting one read-only evidence call"
                    );
                    submitGeneration();
                    return;
                }
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
            if (freshApprovalRequired && !AutomationModeController.isUnrestrictedMode()) {
                requestToolBatchApproval(response.text(), response.toolCalls());
                return;
            }
            handleToolCalls(response.text(), response.toolCalls(), 0, false);
        }

        private void handleGroundedAskToolCalls(String assistantText, List<ModelToolCall> calls) {
            if (this.tools.isEmpty() || this.groundedAskFinalizing) {
                fail("ask_tool_boundary", "Normal /ask attempted a tool outside its read-only evidence boundary.", null);
                return;
            }
            if (calls == null || calls.isEmpty()) {
                submitGeneration();
                return;
            }
            if (calls.size() != 1) {
                if (this.groundedAskCorrectionCount++ >= 1) {
                    fail("ask_tool_batch", "Grounded /ask repeatedly requested multiple lookups instead of one staged decision.", null);
                    return;
                }
                archiveVisibleSummary(assistantText);
                this.conversation.add(ModelMessage.assistant(assistantText));
                this.conversation.add(ModelMessage.user(
                    "Use exactly one supplied read-only Minecraft lookup for the next decision. Do not batch calls and do not perform an action."
                ));
                ModelGenerationHudState.state(this.displayId, ModelRequestState.RETRYING, "staging one knowledge lookup");
                submitGeneration();
                return;
            }
            ModelToolCall call = calls.get(0);
            boolean supplied = this.tools.stream().anyMatch(tool -> tool.id().equals(call.toolId()));
            if (!supplied || !DeepThoughtReadOnlyToolCoordinator.supports(call.toolId())) {
                fail("ask_tool_boundary", "Grounded /ask requested an unsupported or side-effecting tool: " + call.toolId(), null);
                return;
            }
            String signature = call.toolId() + ":" + call.arguments();
            if (this.repeatedCalls.merge(signature, 1, Integer::sum) > 1) {
                fail("repeated_tool_loop", "Grounded /ask repeated the identical read-only lookup without a new observation.", null);
                return;
            }
            if (this.groundedAskToolRounds >= MAXIMUM_GROUNDED_ASK_TOOL_ROUNDS) {
                this.groundedAskFinalizing = true;
                this.conversation.add(ModelMessage.user(
                    "The read-only evidence budget is exhausted. Give one compact honest answer from the evidence already returned."
                ));
                submitGeneration();
                return;
            }
            this.groundedAskToolRounds++;
            this.toolCallCount++;
            ModelGenerationHudState.toolCallCount(this.displayId, this.toolCallCount);
            archiveVisibleSummary(assistantText);
            this.conversation.add(ModelMessage.assistantToolCall(assistantText, call));
            ModelGenerationHudState.toolProgress(this.displayId, 1, 1, call.toolId(), toolActivityDetail(call));
            ModelGenerationHudState.state(this.displayId, ModelRequestState.INSPECTING, call.toolId());
            com.google.gson.JsonObject eventData = new com.google.gson.JsonObject();
            eventData.addProperty("toolId", call.toolId());
            eventData.add("arguments", call.arguments());
            ModelGenerationHudState.appendEvent(this.displayId, new ModelExecutionEvent(
                this.displayId,
                this.displayId.toString(),
                "grounded-tool-" + call.id(),
                ModelExecutionEvent.Type.TOOL_STARTED,
                ModelRequestState.INSPECTING,
                ModelToolActivityPresentation.activity(call).state(),
                groundedActivitySummary(call),
                eventData,
                System.currentTimeMillis()
            ));
            DeepThoughtReadOnlyToolCoordinator.execute(call).whenComplete((result, failure) -> {
                if (this.terminal.get()) return;
                ModelToolResult resolved = failure == null ? result : new ModelToolResult(
                    call.id(), call.toolId(), "failed", new com.google.gson.JsonObject(),
                    "knowledge_lookup_failed", message(failure)
                );
                this.toolResultsReceived++;
                recordToolResult(resolved);
                boolean terminalEvidence = terminalGroundedEvidence(resolved);
                this.groundedAskFinalizing = terminalEvidence
                    || this.groundedAskToolRounds >= MAXIMUM_GROUNDED_ASK_TOOL_ROUNDS;
                String instruction = terminalEvidence
                    ? "The read-only source returned an authoritative terminal result. Do not retry, substitute, or invent a nearby target. Give one compact honest answer preserving the exact requested identifier and evidence."
                    : "Use the exact structured evidence above. Give the compact final answer now, or make one changed read-only lookup only if a specific unanswered fact is necessary. Never claim an action occurred.";
                this.conversation.add(ModelMessage.user(instruction));
                ModelGenerationHudState.state(this.displayId, ModelRequestState.OBSERVING_RESULT,
                    terminalEvidence ? "authoritative evidence received" : "grounded evidence received");
                submitGeneration();
            });
        }

        private static String groundedActivitySummary(ModelToolCall call) {
            if (MinecraftKnowledgeModelToolRegistry.COMMAND_TOOL_ID.equals(call.toolId())) {
                return "Checking active command syntax";
            }
            if (call.toolId().startsWith("internet.")) return "Researching public information";
            if (call.toolId().startsWith("workspace.")) return "Reading workspace evidence";
            if (call.toolId().startsWith("koil.")) return "Checking Koil documentation";
            String name = ModelToolCallPresentation.toolName(call.toolId());
            return "Inspecting " + Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }

        private static boolean terminalGroundedEvidence(ModelToolResult result) {
            if (result == null) return true;
            String status = result.status().toLowerCase(java.util.Locale.ROOT);
            String code = result.failureCode().toLowerCase(java.util.Locale.ROOT);
            String detail = result.detail().toLowerCase(java.util.Locale.ROOT);
            return !result.retryable() && (status.equals("unsupported")
                || code.contains("not_found") || detail.contains("not found")
                || code.contains("unsupported") || code.contains("permission")
                || code.contains("impossible") || code.startsWith("unknown_"));
        }

        private void handleDeepThoughtResponse(StreamingModelResponse response) {
            if (!response.toolCalls().isEmpty()) {
                archiveVisibleSummary(response.text());
                handleDeepThoughtToolCalls(response.text(), response.toolCalls(), 0);
                return;
            }
            if (this.finalizationRequested.get()
                || this.deepThought.session().phase == DeepThoughtSession.Phase.FINALIZE) {
                this.deepThought.complete(response.text());
                complete(response.text());
                return;
            }
            archiveVisibleSummary(response.text());
            this.conversation.add(ModelMessage.assistant(response.text()));
            boolean finalize = this.deepThought.acceptRoundSummary(visiblePlanSummary(response.text()));
            if (finalize) {
                this.conversation.add(ModelMessage.user(this.deepThought.instruction()));
            } else {
                this.conversation.add(ModelMessage.user(
                    "Continue the evidence-driven investigation with only this next bounded task: "
                        + this.deepThought.instruction()
                ));
            }
            submitGeneration();
        }

        private void handleDeepThoughtToolCalls(String assistantText, List<ModelToolCall> calls, int index) {
            if (index >= calls.size()) {
                this.conversation.add(ModelMessage.user(
                    "Interpret the returned read-only evidence without claiming any side effect. "
                        + this.deepThought.instruction()
                ));
                submitGeneration();
                return;
            }
            ModelToolCall call = calls.get(index);
            this.conversation.add(ModelMessage.assistantToolCall(index == 0 ? assistantText : "", call));
            ModelGenerationHudState.state(this.displayId, ModelRequestState.INSPECTING, call.toolId());
            com.google.gson.JsonObject eventData = new com.google.gson.JsonObject();
            eventData.addProperty("toolId", call.toolId());
            eventData.add("arguments", call.arguments());
            ModelGenerationHudState.appendEvent(this.displayId, new ModelExecutionEvent(
                    this.displayId,
                    this.displayId.toString(),
                    "deep-thought-tool-" + call.id(),
                    ModelExecutionEvent.Type.TOOL_STARTED,
                    ModelRequestState.INSPECTING,
                    ModelToolActivityPresentation.activity(call).state(),
                    groundedActivitySummary(call),
                    eventData,
                    System.currentTimeMillis()
            ));
            DeepThoughtReadOnlyToolCoordinator.execute(call).whenComplete((result, failure) -> {
                ModelToolResult resolved = failure == null ? result : new ModelToolResult(
                    call.id(), call.toolId(), "failed", new com.google.gson.JsonObject(),
                    "deep_thought_tool_failed", message(failure)
                );
                recordToolResult(resolved);
                handleDeepThoughtToolCalls("", calls, index + 1);
            });
        }

        private void validateAndReviewPlan(String assistantText, ModelToolCall planCall) {
            this.toolCallCount++;
            ModelGenerationHudState.toolCallCount(this.displayId, this.toolCallCount);
            this.conversation.add(ModelMessage.assistantToolCall(assistantText, planCall));
            ModelGenerationHudState.state(this.displayId, ModelRequestState.VALIDATING_PLAN, "validating plan");
            com.google.gson.JsonObject eventData = new com.google.gson.JsonObject();
            eventData.addProperty("toolId", planCall.toolId());
            eventData.add("arguments", planCall.arguments());
            ModelGenerationHudState.appendEvent(this.displayId, new ModelExecutionEvent(
                    this.displayId,
                    this.displayId.toString(),
                    "plan-tool-" + planCall.id(),
                    ModelExecutionEvent.Type.TOOL_STARTED,
                    ModelRequestState.VALIDATING_PLAN,
                    ModelActivityState.VALIDATING,
                    "Validating the structured Automation plan",
                    eventData,
                    System.currentTimeMillis()
            ));
            AutomationModeController.setPlanningActive(true);
            AutomationToolCoordinator.execute(this.displayId, planCall, false)
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        fail("plan_validation_failed", message(failure), failure);
                        return;
                    }
                    recordToolResult(result);
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
                        this.durableState.plan(this.validatedPlan.id());
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
                .append("§5Plan §f").append(plan.id()).append("§r | ").append(plan.objective()).append('\n');
            for (ValidatedAutomationPlan.Step step : plan.steps()) {
                ModelToolCall call = step.asToolCall(plan.id());
                message.append("- §fStep ").append(step.index()).append('/').append(plan.steps().size())
                    .append(": ").append(ModelToolCallPresentation.toolName(step.toolId())).append("§r\n");
                String arguments = ModelToolCallPresentation.arguments(call.arguments());
                if (!arguments.isBlank()) {
                    message.append("-# §7").append(arguments).append("§r\n");
                }
                if (!step.reason().isBlank()) {
                    message.append("-# §7Why: ").append(step.reason()).append("§r\n");
                }
            }
            message.append("-# §8Approval authorizes only these exact validated steps. "
                + "Changed or additional side effects require another reviewed plan.");
            ModelGenerationHudState.state(this.displayId, ModelRequestState.WAITING_FOR_PLAN_APPROVAL, "plan review");
            ModelGenerationHudState.appendEvent(this.displayId, new ModelExecutionEvent(
                this.displayId, plan.id(), "approval-" + UUID.randomUUID(),
                ModelExecutionEvent.Type.APPROVAL_REQUESTED, ModelRequestState.WAITING_FOR_PLAN_APPROVAL,
                "Review exact validated plan " + plan.id(), new com.google.gson.JsonObject(), System.currentTimeMillis()
            ));
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
                    ModelGenerationHudState.appendEvent(this.displayId, new ModelExecutionEvent(
                        this.displayId, plan.id(), "approval-" + UUID.randomUUID(),
                        ModelExecutionEvent.Type.APPROVAL_ACCEPTED, ModelRequestState.PLANNING,
                        "Approved exact steps in " + plan.id(), new com.google.gson.JsonObject(), System.currentTimeMillis()
                    ));
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
            this.actionToolAttempted |= isActionTool(step.toolId());
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
            com.google.gson.JsonObject toolEventData = new com.google.gson.JsonObject();
            toolEventData.addProperty("toolId", call.toolId());
            toolEventData.add("arguments", call.arguments());
            ModelGenerationHudState.appendEvent(this.displayId, new ModelExecutionEvent(
                this.displayId,
                plan.id(),
                "tool-" + call.id(),
                ModelExecutionEvent.Type.TOOL_STARTED,
                ModelRequestState.EXECUTING_TOOL,
                ModelToolActivityPresentation.activity(call).state(),
                stepLabel(step.index(), plan.steps().size(), step.toolId()),
                toolEventData,
                System.currentTimeMillis()
            ));
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
                    recordToolResult(result);
                    boolean successful = toolSatisfied(result);
                    if (successful) {
                        this.completedToolIds.add(step.toolId());
                        recordSuccessfulToolOutput(step.toolId(), result);
                        ModelGenerationHudState.updatePlanStep(
                            this.displayId,
                            step.index(),
                            ModelGenerationHudState.PlanStepStatus.COMPLETED,
                            result.status()
                        );
                        executeApprovedPlanStep(position + 1);
                    } else {
                        if (terminalToolFailure(result)) {
                            ModelGenerationHudState.updatePlanStep(
                                this.displayId,
                                step.index(),
                                ModelGenerationHudState.PlanStepStatus.BLOCKED,
                                result.detail()
                            );
                            AutomationModeController.setPlanningActive(false);
                            complete(terminalFailureMessage(result));
                            return;
                        }
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
            String evidenceFingerprint = step.id() + "|" + resultProgressFingerprint(result);
            if (evidenceFingerprint.equals(this.lastReplanEvidenceFingerprint)) {
                AutomationModeController.setPlanningActive(false);
                complete("The plan stopped because the same failure produced no new evidence. No replacement action was executed.");
                return;
            }
            this.lastReplanEvidenceFingerprint = evidenceFingerprint;
            ModelGenerationHudState.updatePlanStep(
                this.displayId,
                step.index(),
                ModelGenerationHudState.PlanStepStatus.FAILED,
                safeDetail
            );
            if (this.validatedPlan != null) {
                for (ValidatedAutomationPlan.Step remaining : this.validatedPlan.steps()) {
                    if (remaining.index() > step.index()) {
                        ModelGenerationHudState.updatePlanStep(this.displayId, remaining.index(),
                            ModelGenerationHudState.PlanStepStatus.REVISED, "invalidated by changed observation");
                    }
                }
            }
            ModelGenerationHudState.appendEvent(
                this.displayId,
                ModelGenerationHudState.ActivityEventType.FAILURE,
                "Step " + step.index() + " failed: " + abbreviate(safeDetail, 300)
            );
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
                    recordToolResult(new ModelToolResult(
                        call.id(),
                        call.toolId(),
                        "rejected",
                        new com.google.gson.JsonObject(),
                        "plan_review_required",
                        reason
                    ));
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
            ModelGenerationHudState.state(this.displayId, ModelRequestState.REPLANNING, "replanning");
            submitGeneration();
        }

        private void requestToolBatchApproval(String assistantText, List<ModelToolCall> calls) {
            StringBuilder message = new StringBuilder(calls.size() == 1
                ? "§fThe model is asking to run this action:§r\n"
                : "§fThe model is asking to run these " + calls.size() + " actions:§r\n");
            for (int index = 0; index < calls.size(); index++) {
                ModelToolCall call = calls.get(index);
                message.append("- §f").append(calls.size() == 1 ? "" : (index + 1) + ". ")
                    .append(ModelToolCallPresentation.toolName(call.toolId())).append("§r\n");
                String arguments = ModelToolCallPresentation.arguments(call.arguments());
                if (!arguments.isBlank()) {
                    message.append("-# §7").append(arguments).append("§r\n");
                }
            }
            message.append("-# §8This approval applies only to the actions shown here. Later actions ask again.§r");
            ModelGenerationHudState.state(this.displayId, ModelRequestState.WAITING_FOR_ACTION_APPROVAL, "approval");
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
                recordToolResult(new ModelToolResult(
                    call.id(),
                    call.toolId(),
                    "rejected",
                    new com.google.gson.JsonObject(),
                    "user_declined_batch",
                    "The player declined the requested group of automation actions."
                ));
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
            AutomationProgressGuard.Decision progressDecision = this.automationProgress.before(call);
            if (!progressDecision.allowed()) {
                if (noFailExecutionRequired()) {
                    this.conversation.add(ModelMessage.assistantToolCall(index == 0 ? assistantText : "", call));
                    recordToolResult(new ModelToolResult(
                        call.id(), call.toolId(), "rejected", new com.google.gson.JsonObject(),
                        "no_progress", "Identical action rejected against unchanged observation: " + progressDecision.reason()
                    ));
                    continueNoFail("identical_tool_call_without_new_observation", "");
                } else {
                    fail("repeated_tool_loop", "The same automation action produced no new observation: " + progressDecision.reason(), null);
                }
                return;
            }
            this.toolCallCount++;
            this.actionToolAttempted |= isActionTool(call.toolId());
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
            com.google.gson.JsonObject toolEventData = new com.google.gson.JsonObject();
            toolEventData.addProperty("toolId", call.toolId());
            toolEventData.add("arguments", call.arguments());
            ModelGenerationHudState.appendEvent(this.displayId, new ModelExecutionEvent(
                this.displayId,
                this.validatedPlan == null ? this.displayId.toString() : this.validatedPlan.id(),
                "tool-" + call.id(),
                ModelExecutionEvent.Type.TOOL_STARTED,
                ModelRequestState.EXECUTING_TOOL,
                ModelToolActivityPresentation.activity(call).state(),
                stepLabel(index + 1, calls.size(), call.toolId()),
                toolEventData,
                System.currentTimeMillis()
            ));
            AutomationModeController.executing("tool: " + call.toolId());
            LocalModelRuntimeLog.write(
                "tool_start",
                this.displayId + " | step=" + (index + 1) + "/" + calls.size() + " | " + call.toolId()
            );
            AutomationToolCoordinator.execute(this.displayId, call, preapproved).whenComplete((toolResult, failure) -> {
                if (failure != null) {
                    if (noFailExecutionRequired()) {
                        ModelToolResult failedResult = new ModelToolResult(
                            call.id(), call.toolId(), "failed", new com.google.gson.JsonObject(),
                            "tool_execution_failed", message(failure)
                        );
                        this.toolResultsReceived++;
                        recordToolResult(failedResult);
                        this.automationProgress.record(call, failedResult);
                        continueNoFail("tool_execution_failed", "");
                    } else {
                        fail("tool_execution_failed", message(failure), failure);
                    }
                    return;
                }
                if (this.cancellation.isCancellationRequested()) {
                    fail("cancelled", this.cancellation.cancellationReason(), null);
                    return;
                }
                this.toolResultsReceived++;
                if (toolSatisfied(toolResult)) {
                    this.completedToolIds.add(call.toolId());
                    recordSuccessfulToolOutput(call.toolId(), toolResult);
                }
                recordToolResult(toolResult);
                AutomationProgressGuard.Observation progress = this.automationProgress.record(call, toolResult);
                if (progress.newObservation() || progress.stateChanged()) {
                    this.repeatedResponses.clear();
                }
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
                if (toolSatisfied(toolResult)) {
                    handleToolCalls("", calls, index + 1, preapproved);
                } else {
                    if (terminalToolFailure(toolResult)) {
                        if (noFailExecutionRequired()) {
                            continueNoFail("terminal_tool_result_" + toolResult.failureCode(), "");
                        } else {
                            complete(terminalFailureMessage(toolResult));
                        }
                        return;
                    }
                    this.conversation.add(ModelMessage.user(
                        "The previous tool did not produce validated completion. Status: "
                            + toolResult.status() + "; failure: " + toolResult.failureCode()
                            + "; detail: " + abbreviate(toolResult.detail(), 500)
                            + ". Inspect the observation and choose a changed retry, a supported replan, or report the limitation."
                    ));
                    ModelGenerationHudState.state(this.displayId, ModelRequestState.OBSERVING_RESULT, "result requires recovery");
                    submitGeneration();
                }
            });
        }

        private boolean terminalToolFailure(ModelToolResult result) {
            if (result == null || result.retryable()) return false;
            String code = result.failureCode().toLowerCase(java.util.Locale.ROOT);
            return code.startsWith("unknown_")
                || code.contains("invalid_id")
                || code.contains("unsupported")
                || code.contains("permission");
        }

        private static boolean toolSatisfied(ModelToolResult result) {
            return result != null && (result.completedAndValidated()
                || "already_satisfied".equalsIgnoreCase(result.status()));
        }

        private void recordSuccessfulToolOutput(String toolId, ModelToolResult result) {
            if (!AutomationPlanModelToolRegistry.supports(toolId)
                && NoFailExecutionPolicy.accepts(
                result,
                AutomationModeController.isVerificationEnabled()
            )) {
                this.successfulToolOutputs++;
                if (isActionTool(toolId)) {
                    this.successfulActionToolOutputs++;
                }
            }
        }

        private static boolean isActionTool(String toolId) {
            if (toolId == null || toolId.isBlank() || AutomationPlanModelToolRegistry.supports(toolId)) {
                return false;
            }
            return LocalModelToolCatalog.automationModeTools().stream()
                .filter(definition -> toolId.equals(definition.id()))
                .findFirst()
                .map(definition -> definition.confirmationRequired() || !definition.sideEffects().isEmpty())
                .orElse(false);
        }

        private boolean noFailExecutionRequired() {
            return this.mode == RequestMode.AUTOMATION
                && ModelExperimentalFeatures.snapshot().noFailEnabled()
                && !this.finalizationRequested.get()
                && (this.toolCallCount > 0 || !this.requiredToolIds.isEmpty() || this.validatedPlan != null);
        }

        private NoFailExecutionPolicy.Decision noFailDecision() {
            return NoFailExecutionPolicy.evaluate(
                ModelExperimentalFeatures.snapshot().noFailEnabled() && !this.finalizationRequested.get(),
                this.mode == RequestMode.AUTOMATION,
                this.toolCallCount > 0 || !this.requiredToolIds.isEmpty() || this.validatedPlan != null,
                this.actionToolAttempted ? this.successfulActionToolOutputs : this.successfulToolOutputs,
                this.objectiveLedger.allCompleted()
            );
        }

        private void continueNoFail(String reason, String assistantText) {
            if (this.cancellation.isCancellationRequested()) {
                fail("cancelled", this.cancellation.cancellationReason(), null);
                return;
            }
            if (assistantText != null && !assistantText.isBlank()) {
                archiveVisibleSummary(assistantText);
                this.conversation.add(ModelMessage.assistant(assistantText));
            }
            String safeReason = reason == null || reason.isBlank() ? "validated_success_not_reached" : reason;
            this.conversation.add(ModelMessage.user(
                "No-Fail remains active because " + safeReason.replace('_', ' ') + ". "
                    + "Do not finalize or claim success. Inspect the latest structured result and current state, then call a changed supported tool or changed arguments that can advance the original objective. "
                    + "An identical call against unchanged evidence is forbidden. Preserve the exact requested target and all approval/permission boundaries."
                    + (AutomationModeController.isVerificationEnabled()
                    ? " Verification is also active, so only passed objective evidence counts as success."
                    : "")
            ));
            ModelGenerationHudState.state(this.displayId, ModelRequestState.REPLANNING, safeReason);
            AutomationCliViewModel.activeState("recovering", "", safeReason);
            LocalModelRuntimeLog.write("no_fail_continue", this.displayId + " | " + safeReason);
            submitGeneration();
        }

        private static String resultProgressFingerprint(ModelToolResult result) {
            if (result == null) return "exception";
            com.google.gson.JsonObject evidence = result.output();
            if (evidence.has("structuredResult") && evidence.get("structuredResult").isJsonObject()) {
                evidence = evidence.getAsJsonObject("structuredResult").deepCopy();
                if (evidence.has("metrics") && evidence.get("metrics").isJsonObject()) {
                    evidence.getAsJsonObject("metrics").remove("duration_ms");
                    evidence.getAsJsonObject("metrics").remove("attempts");
                }
            }
            return result.status() + '|' + result.failureCode() + '|' + evidence;
        }

        private String terminalFailureMessage(ModelToolResult result) {
            String target = result.output().has("result.requested_target_id")
                ? result.output().get("result.requested_target_id").getAsString()
                : "the exact requested target";
            if (result.failureCode().startsWith("unknown_")) {
                return "I stopped without substituting another target because " + target
                    + " does not exist in the active Minecraft registry.";
            }
            return "I stopped without running a replacement action. "
                + abbreviate(result.detail().isBlank() ? result.failureCode() : result.detail(), 500);
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
            return this.mode == RequestMode.AUTOMATION
                && ModelExperimentalFeatures.snapshot().noFailEnabled()
                ? this.objectiveLedger.incompleteToolIds()
                : this.objectiveLedger.pendingToolIds();
        }

        private void recordToolResult(ModelToolResult result) {
            this.conversation.add(ModelMessage.toolResult(result));
            this.durableState.record(result);
            this.objectiveLedger.record(result);
            if (this.deepThought != null) this.deepThought.recordToolResult(result);
            if (this.mode == RequestMode.AUTOMATION && AutomationModeController.isVerificationEnabled()) {
                boolean verified = "completed".equals(result.status())
                    && !"failed".equals(result.validationStatus());
                com.google.gson.JsonObject validation = new com.google.gson.JsonObject();
                validation.addProperty("toolId", result.toolId());
                validation.addProperty("status", verified ? "passed" : "failed");
                validation.addProperty("validationStatus", result.validationStatus());
                validation.addProperty("failureCode", result.failureCode());
                validation.addProperty("detail", result.detail());
                ModelGenerationHudState.state(this.displayId, ModelRequestState.VALIDATING, result.toolId());
                ModelGenerationHudState.appendEvent(this.displayId, new ModelExecutionEvent(
                    this.displayId,
                    this.validatedPlan == null ? this.displayId.toString() : this.validatedPlan.id(),
                    "validation-" + result.callId(),
                    verified ? ModelExecutionEvent.Type.VALIDATION_PASSED : ModelExecutionEvent.Type.VALIDATION_FAILED,
                    ModelRequestState.VALIDATING,
                    (verified ? "Verified " : "Verification failed for ")
                        + ModelToolCallPresentation.toolName(result.toolId()),
                    validation,
                    System.currentTimeMillis()
                ));
            }
            ModelExecutionEvent.Type type = result.toolId().startsWith("workspace.")
                ? result.output().has("diffHunks") ? ModelExecutionEvent.Type.DIFF_PRODUCED : ModelExecutionEvent.Type.FILE_READ
                : result.toolId().startsWith("development.") ? ModelExecutionEvent.Type.COMMAND_COMPLETED
                : ModelExecutionEvent.Type.TOOL_RESULT;
            com.google.gson.JsonObject evidence = result.output().deepCopy();
            evidence.addProperty("toolId", result.toolId());
            evidence.addProperty("status", result.status());
            evidence.addProperty("detail", result.detail());
            evidence.addProperty("failureCode", result.failureCode());
            evidence.addProperty("validationStatus", result.validationStatus());
            evidence.addProperty("retryable", result.retryable());
            ModelGenerationHudState.appendEvent(this.displayId, new ModelExecutionEvent(
                this.displayId,
                this.validatedPlan == null ? this.displayId.toString() : this.validatedPlan.id(),
                "event-" + UUID.randomUUID(), type,
                toolSatisfied(result) ? ModelRequestState.OBSERVING_RESULT : ModelRequestState.RETRYING,
                result.toolId() + " — " + result.status()
                    + (result.detail().isBlank() ? "" : ": " + abbreviate(result.detail(), 260)),
                evidence, System.currentTimeMillis()
            ));
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
            NoFailExecutionPolicy.Decision noFail = noFailDecision();
            if (!noFail.allowFinalization()) {
                continueNoFail(noFail.reason(), text);
                return;
            }
            boolean voiceAlreadyStreamed = text != null && text.equals(this.streamedVoiceResponse.getAndSet(""));
            RichChatModelFinalFormatValidator.Result formatted = RichChatModelFinalFormatValidator.validateAndRepair(text);
            if (formatted.text().isBlank()) {
                fail("empty_response", "The model generated no visible text.", null);
                return;
            }
            if (!formatted.valid()) {
                if (this.finalFormattingCorrectionCount++ < 1) {
                    this.formattingCorrectionActive = true;
                    if (text != null && !text.isBlank()) this.conversation.add(ModelMessage.assistant(text));
                    this.conversation.add(ModelMessage.user(
                        "Correct only the final presentation in one compact response. Preserve every literal command argument, path, namespaced ID, and mathematical expression exactly. "
                            + String.join("; ", formatted.issues())
                            + ". Do not add a heading. Use $...$ or \\(...\\) inline and $$...$$ or \\[...\\] for block math. Commands must be validated masked suggestions and never code."
                    ));
                    ModelGenerationHudState.state(this.displayId, ModelRequestState.PREPARING_CONTEXT, "formatting final response");
                    submitGeneration();
                    return;
                }
                text = "§cBlocked§r: The model repeatedly returned an unsafe or unsupported final format. No command or formula was altered.";
                formatted = RichChatModelFinalFormatValidator.validateAndRepair(text);
            }
            RichChatModelOutputSanitizer.Result sanitized = new RichChatModelOutputSanitizer.Result(formatted.text(), formatted.changed());
            if (!this.terminal.compareAndSet(false, true)) {
                return;
            }
            SESSIONS.remove(this.displayId, this);
            if (this.mode == RequestMode.AUTOMATION) {
                AutomationModeController.setDeepThinkingActive(false);
                AutomationModeController.setPlanningActive(false);
            }
            if (this.deepThought != null && this.deepThought.session().lifecycle != DeepThoughtSession.Lifecycle.COMPLETED) {
                this.deepThought.complete(sanitized.text());
            }
            this.conversation.add(ModelMessage.assistant(sanitized.text()));
            if (ModelExperimentalFeatures.snapshot().persistentConversationHistory()) {
                CompletableFuture.runAsync(() -> ModelConversationPersistence.save(CONVERSATIONS));
            }
            if (ModelExperimentalFeatures.snapshot().persistentAssociativeMemory()) {
                String finalText = sanitized.text();
                CompletableFuture.runAsync(() -> ModelAssociativeMemory.remember(this.prompt, finalText));
            }
            ModelGenerationHudState.replaceText(this.displayId, sanitized.text());
            ModelGenerationHudState.appendEvent(
                this.displayId,
                ModelGenerationHudState.ActivityEventType.RESULT,
                "Prepared the final user-facing response."
            );
            ModelGenerationHudState.state(this.displayId, ModelRequestState.COMPLETED, "response added to chat");
            if (!voiceAlreadyStreamed) {
                ModelVoiceService.speakFinalAnswer(sanitized.text());
            }
            MinecraftClient current = MinecraftClient.getInstance();
            if (current != null) {
                current.execute(() -> {
                    ModelChatMessageBridge.addToChat(
                        current,
                        sanitized.text(),
                        ModelActivityPresentation.capture(ModelGenerationHudState.snapshot(this.displayId))
                    );
                    ModelGenerationHudState.messagePresented(this.displayId);
                    if (this.deepThought != null) {
                        CompletableFuture.runAsync(this.deepThought::markFinalPresented);
                    }
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
                    firstPersonVisibleSummary(visiblePlanSummary(summary.text()))
                );
                if (this.deepThought != null && this.mode == RequestMode.AUTOMATION) {
                    this.deepThought.acceptRoundSummary(firstPersonVisibleSummary(visiblePlanSummary(summary.text())));
                }
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

        private static String firstPersonVisibleSummary(String summary) {
            String value = summary == null ? "" : summary.strip();
            if (value.isBlank()) return "";
            String lower = value.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("i ") || lower.startsWith("i’m ") || lower.startsWith("i'm ")
                || lower.startsWith("i’ll ") || lower.startsWith("i'll ")) {
                return value;
            }
            return "I’m considering: " + Character.toLowerCase(value.charAt(0)) + value.substring(1);
        }

        private static String readableToolName(String toolId) {
            if (toolId == null || toolId.isBlank()) {
                return "tool";
            }
            return toolId.replace('.', ' ').replace('_', ' ').strip();
        }

        private static String stepLabel(int index, int total, String toolId) {
            String name = readableToolName(toolId);
            return total > 1 ? "Step " + index + "/" + total + ": " + name : name;
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
            SESSIONS.remove(this.displayId, this);
            if (this.mode == RequestMode.AUTOMATION) {
                AutomationModeController.setDeepThinkingActive(false);
                AutomationModeController.setPlanningActive(false);
            }
            if (this.deepThought != null) {
                if ("cancelled".equals(code)) this.deepThought.cancel();
                else this.deepThought.pause();
            }
            String safeCode = code == null || code.isBlank() ? "failed" : code;
            String safeDetail = detail == null || detail.isBlank() ? safeCode : detail;
            if (this.mode == RequestMode.AUTOMATION && majorModelProtocolFailure(safeCode)) {
                LocalModelReliabilityStore.recordProtocolFailure(configuredModelId(), safeCode, safeDetail);
            }
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

        private static boolean majorModelProtocolFailure(String code) {
            return switch (code == null ? "" : code) {
                case "empty_response", "model_reasoning_loop", "invalid_plan_result",
                        "plan_validation_failed", "invalid_plan_state" -> true;
                default -> false;
            };
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
        private final UUID displayId;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<ModelCancellationHandle> active = new AtomicReference<>();
        private volatile String reason = "";

        private SessionCancellation(UUID displayId) {
            this.displayId = displayId;
        }

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
            ProjectValidationModelToolRegistry.cancel(this.displayId);
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
        ASK_DEEP,
        AUTOMATION
    }
}
