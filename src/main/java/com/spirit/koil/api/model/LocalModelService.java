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
import com.spirit.koil.api.model.cache.ModelPromptCacheIdentity;
import com.spirit.koil.api.model.cache.ModelStartupWarmupBranch;
import com.spirit.koil.api.model.cache.SemanticReasoningMemoStore;
import com.spirit.koil.api.model.catalog.LocalModelSelectionStore;
import com.spirit.koil.api.model.catalog.LocalModelCatalog;
import com.spirit.koil.api.model.catalog.LocalModelCatalogEntry;
import com.spirit.koil.api.model.catalog.ModelRuntimeCompatibility;
import com.spirit.koil.api.model.catalog.LocalModelAutomationEligibility;
import com.spirit.koil.api.model.catalog.LocalModelToolCapabilityResolver;
import com.spirit.koil.api.model.catalog.LocalModelReliabilityStore;
import com.spirit.koil.api.model.install.LocalModelInstallationService;
import com.spirit.koil.api.model.provider.colibri.ColibriConfiguration;
import com.spirit.koil.api.model.provider.colibri.ColibriConfigurationStore;
import com.spirit.koil.api.model.provider.colibri.ColibriLocalModelProvider;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppConfiguration;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppComputeMode;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppComputeSettings;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppComputeSettingsStore;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppComputeStabilityStore;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppDeviceProbe;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppMaxRuntimeProfile;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppMaxTuningResult;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppMaxTuningStore;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppMaxActivationFallbackPolicy;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppUniversalTuningBridge;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppBenchmarkCandidateBridge;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppBenchmarkAdapter;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppBenchmarkStatePrecisionOverride;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppBenchmarkContextOverride;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppSafeBenchmarkContextSelection;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppLocalModelProvider;
import com.spirit.koil.api.model.runtime.universal.KoilUnifiedLocalModelProvider;
import com.spirit.koil.api.model.runtime.universal.KoilComputeMode;
import com.spirit.koil.api.model.runtime.universal.KoilBenchmarkCandidate;
import com.spirit.koil.api.model.runtime.universal.KoilBenchmarkHistoryStore;
import com.spirit.koil.api.model.runtime.universal.KoilBenchmarkSession;
import com.spirit.koil.api.model.runtime.universal.KoilBenchmarkResult;
import com.spirit.koil.api.model.runtime.universal.KoilBenchmarkWorkload;
import com.spirit.koil.api.model.runtime.universal.KoilBenchmarkTuningProfile;
import com.spirit.koil.api.model.runtime.universal.KoilCandidateScorer;
import com.spirit.koil.api.model.runtime.universal.KoilCandidateGenerator;
import com.spirit.koil.api.model.runtime.universal.KoilCandidateGenerationBudget;
import com.spirit.koil.api.model.runtime.universal.KoilMemoryBudgetPlanner;
import com.spirit.koil.api.model.runtime.universal.KoilMemoryPressureSnapshot;
import com.spirit.koil.api.model.runtime.universal.KoilExecutionAdapterDescriptor;
import com.spirit.koil.api.model.runtime.universal.KoilHardwareProfile;
import com.spirit.koil.api.model.runtime.universal.KoilHardwareProfiler;
import com.spirit.koil.api.model.runtime.universal.KoilModelProfile;
import com.spirit.koil.api.model.runtime.universal.KoilModelProfiler;
import com.spirit.koil.api.model.runtime.universal.KoilMeasuredTuningProfile;
import com.spirit.koil.api.model.runtime.universal.KoilRuntimeBackend;
import com.spirit.koil.api.model.runtime.universal.KoilTuningKey;
import com.spirit.koil.api.model.runtime.universal.KoilStatePrecisionEvidence;
import com.spirit.koil.api.model.runtime.universal.KoilStatePrecisionRegimePredictor;
import com.spirit.koil.api.model.runtime.universal.KoilUniversalTuningStore;
import com.spirit.koil.api.util.file.KoilInstancePaths;
import com.spirit.koil.api.model.voice.ModelVoiceService;
import com.spirit.koil.api.model.tool.LocalModelToolCatalog;
import com.spirit.koil.api.model.tool.ToolDiscoveryModelToolRegistry;
import com.spirit.koil.api.model.tool.AgentSkillModelToolRegistry;
import com.spirit.koil.api.model.planning.AutomationThinkingPolicy;
import com.spirit.koil.api.model.planning.AutomationToolCallLatencyPolicy;
import com.spirit.koil.api.model.planning.ConversationalReasoningPolicy;
import com.spirit.koil.api.model.planning.InformationToolCallLatencyPolicy;
import com.spirit.koil.api.model.planning.ModelInformationRetrievalPolicy;
import com.spirit.koil.api.model.planning.ValidatedAutomationPlan;
import com.spirit.koil.api.model.reasoning.AgentReasoningController;
import com.spirit.koil.api.model.reasoning.DirectResponseIntentGuard;
import com.spirit.koil.api.model.reasoning.AgentReasoningState;
import com.spirit.koil.api.model.reasoning.AgentState;
import com.spirit.koil.api.model.reasoning.AgentToolRoutingPolicy;
import com.spirit.koil.api.model.reasoning.AgentVerificationPolicy;
import com.spirit.koil.api.model.planning.ReviewedPlanAuthorization;
import com.spirit.koil.api.model.prompt.LocalModelAutomationPrompt;
import com.spirit.koil.api.model.tool.AutomationPlanModelToolRegistry;
import com.spirit.koil.api.model.tool.DeepThoughtReadOnlyToolCoordinator;
import com.spirit.koil.api.model.tool.ProjectValidationModelToolRegistry;
import com.spirit.koil.api.model.tool.MinecraftKnowledgeModelToolRegistry;
import com.spirit.koil.api.model.tool.InternetResearchModelToolRegistry;
import com.spirit.koil.api.model.deepthought.DeepThoughtInvestigationController;
import com.spirit.koil.api.model.deepthought.DeepThoughtSession;
import com.spirit.koil.api.model.deepthought.DeepThoughtSessionStore;
import com.spirit.koil.api.model.retrieval.KoilKnowledgeRuntime;
import com.spirit.koil.api.model.codeintelligence.CodeIntelligenceService;
import com.spirit.koil.api.model.skill.KoilSkillDefinition;
import com.spirit.koil.api.model.skill.KoilSkillMode;
import com.spirit.koil.api.model.skill.KoilSkillRegistry;
import com.spirit.koil.api.model.skill.KoilSkillSelection;
import com.spirit.koil.api.telemetry.TelemetryCapabilityState;
import com.spirit.koil.api.telemetry.TelemetrySpanKind;
import com.spirit.koil.api.telemetry.TelemetryStore;
import com.spirit.koil.api.telemetry.TelemetryText;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class LocalModelService {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static final Pattern MASKED_COMMAND_LINK = Pattern.compile(
        "\\[[^\\]\\r\\n]+]\\(/([^)\\r\\n]+)\\)"
    );
    /**
     * Final-answer command validation is intentionally gated on an explicit request for
     * command text. Capability discussions such as "test command discovery" or
     * "inspect command syntax" must never force an otherwise valid model answer back
     * through the slash-command repair loop.
     */
    private static final Pattern EXPLICIT_MINECRAFT_COMMAND_REQUEST = Pattern.compile(
        "(?is)(?:"
            + "\\b(?:what|which)\\s+(?:minecraft\\s+)?(?:slash\\s+)?command\\b"
            + "|\\b(?:give|show|tell|write|provide|send)\\s+(?:me\\s+)?(?:the\\s+|a\\s+)?(?:minecraft\\s+)?(?:slash\\s+)?command\\b"
            + "|^\\s*(?:minecraft\\s+)?(?:slash\\s+)?command\\s+to\\b"
            + "|\\b(?:i\\s+)?(?:need|want)\\s+(?:a\\s+|the\\s+)?(?:minecraft\\s+)?(?:slash\\s+)?command\\s+to\\b"
            + "|\\bwhat\\s+(?:do|should)\\s+i\\s+type\\b"
            + "|\\bhow\\s+do\\s+i\\b[^.!?\\n]{0,160}\\bcommand\\b"
            + ")"
    );
    private static final Pattern LEADING_FUNCTION_TAG = Pattern.compile("(?is)^\\s*<\\s*function\\b");
    private static final ModelConversationRegistry CONVERSATIONS = new ModelConversationRegistry(48, 64 * 1024);
    private static volatile LocalModelRuntimeManager runtime;
    private static volatile ColibriConfiguration configuration;
    private static volatile LocalModelSelection selection = LocalModelSelection.none();
    private static volatile CompletableFuture<HardwareCapabilityReport> hardwareScan;
    private static final Map<UUID, GenerationSession> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicReference<String> RESTORED_DEEP_THOUGHT_IDENTITY = new AtomicReference<>("");
    private static final Object LLAMA_COMPUTE_ACTIVATION_LOCK = new Object();
    private static volatile LlamaCppComputeActivation activeLlamaComputeActivation;
    private static final Object LLAMA_MAX_TUNING_LOCK = new Object();
    private static volatile CompletableFuture<LlamaCppMaxTuningResult> activeLlamaMaxTuning;
    private static volatile String llamaMaxTuningStatus = "idle";
    /** In-process handoff used to launch the just-measured universal winner before it is durable. */
    private static final AtomicReference<KoilMeasuredTuningProfile> PENDING_UNIVERSAL_TUNING = new AtomicReference<>();

    private record LlamaCppComputeActivation(
            LlamaCppComputeSettings settings,
            CompletableFuture<Void> future
    ) {
    }

    private LocalModelService() {
    }

    private static KoilComputeMode koilComputeModeForSelection(LocalModelSelection selected) {
        if (selected == null || !selected.complete() || !"llama_cpp".equals(selected.providerId())) {
            return KoilComputeMode.AUTOMATIC;
        }
        LlamaCppComputeMode mode = LlamaCppComputeSettingsStore.load().mode();
        return switch (mode) {
            case CPU -> KoilComputeMode.CPU;
            case GPU -> KoilComputeMode.GPU;
            case HYBRID -> KoilComputeMode.HYBRID;
            case MAX -> KoilComputeMode.AUTOMATIC;
        };
    }

    public static void initialize() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        configuration = ColibriConfigurationStore.loadOrCreate();
        ModelExperimentalFeatures.reload();
        if (ModelExperimentalFeatures.snapshot().persistentConversationHistory()) {
            ModelConversationPersistence.restore(CONVERSATIONS);
            importPersistedConversationKnowledge();
        }
        selection = LocalModelSelectionStore.load();
        runtime = new LocalModelRuntimeManager(configuration.maximumQueueDepth());
        LocalModelProvider executionAdapter;
        LlamaCppConfiguration selectedLlamaConfiguration = null;
        if (selection.complete() && "colibri".equals(selection.providerId())) {
            ModelRuntimeCompatibility compatibility = selectedRuntimeCompatibility(selection);
            ColibriConfiguration managed = ColibriConfiguration.fromSelection(
                    selection, configuration, compatibility);
            executionAdapter = new ColibriLocalModelProvider(managed, compatibility);
        } else if (selection.complete() && "llama_cpp".equals(selection.providerId())) {
            LlamaCppConfiguration llamaConfiguration = LlamaCppConfiguration.fromSelection(
                selection, configuration.apiKey(), configuration.kvSlots());
            selectedLlamaConfiguration = llamaConfiguration;
            executionAdapter = new LlamaCppLocalModelProvider(
                llamaConfiguration,
                startupWarmupBranches(llamaConfiguration)
            );
        } else {
            executionAdapter = new ColibriLocalModelProvider(configuration);
        }
        KoilModelProfile universalModelProfile = selection.complete()
                ? KoilModelProfiler.inspect(selection.modelPath())
                : null;
        KoilHardwareProfile universalHardwareProfile = KoilHardwareProfiler.capture(
                selection.runtimeId(), selection.runtimeExecutable());
        KoilComputeMode universalComputeMode = koilComputeModeForSelection(selection);
        KoilExecutionAdapterDescriptor universalAdapter = KoilExecutionAdapterDescriptor.fromLegacyProvider(executionAdapter);
        int universalEffectiveContextTokens = effectiveUniversalTuningContextTokens(
                selection, executionAdapter, universalHardwareProfile);
        String universalQuantization = LocalModelCatalog.find(selection.catalogId())
                .map(LocalModelCatalogEntry::quantization).orElse("");
        java.nio.file.Path universalTuningPath = KoilInstancePaths.modelRoot().resolve("universal-tuning.properties");
        String predictedStatePrecisionRegime = predictUniversalStatePrecisionRegime(
                selection, selectedLlamaConfiguration, universalModelProfile, universalHardwareProfile,
                universalEffectiveContextTokens);
        KoilTuningKey universalTuningQuery = KoilTuningKey.create(
                universalAdapter, universalModelProfile, universalHardwareProfile, selection.modelId(),
                universalQuantization, universalEffectiveContextTokens, predictedStatePrecisionRegime,
                KoilRuntimeBackend.UNKNOWN, selection.runtimeId());
        LocalModelRuntimeLog.write(
                "universal_tuning_precision_query",
                "context=" + universalTuningQuery.contextRegime()
                        + " | state_precision=" + universalTuningQuery.statePrecisionRegime());

        // benchmark protocol v2 fixed candidate precedence. Remove only the contaminated legacy
        // mirror. New measurements are owned by Koil under koil_universal_autotune. The native
        // llama MAX store remains an adapter-private compatibility/launch record.
        KoilUniversalTuningStore.removeMeasurementSource(universalTuningPath, "llama_cpp_max");

        // Compatibility evidence from the adapter-private MAX store is intentionally ephemeral.
        // It may provide exact context + precision launch geometry when Koil has no real universal
        // benchmark winner for that regime, but it must never be persisted into the universal
        // tuning database or masquerade as universal-owned measurement evidence.
        KoilUniversalTuningStore.removeMeasurementSource(universalTuningPath, "llama_cpp_max_compat");
        KoilMeasuredTuningProfile universalMeasuredTuning = KoilUniversalTuningStore
                .findBest(universalTuningPath, universalTuningQuery)
                .map(KoilUniversalTuningStore.StoredProfile::profile).orElse(null);
        if (universalMeasuredTuning == null
                && selection.complete() && "llama_cpp".equals(selection.providerId()) && selectedLlamaConfiguration != null) {
            LlamaCppConfiguration llamaConfiguration = selectedLlamaConfiguration;
            universalMeasuredTuning = LlamaCppMaxTuningStore.bestForConfiguration(
                    llamaConfiguration, llamaConfiguration.computeSettings().device(),
                    universalTuningQuery.contextRegime(), universalTuningQuery.statePrecisionRegime())
                    .map(tuned -> LlamaCppUniversalTuningBridge.translate(
                            tuned, universalModelProfile, universalHardwareProfile.fingerprint(), KoilRuntimeBackend.UNKNOWN))
                    .orElse(null);
            if (universalMeasuredTuning != null) {
                LocalModelRuntimeLog.write(
                        "universal_tuning_compatibility_fallback",
                        "context=" + universalTuningQuery.contextRegime()
                                + " | state_precision=" + universalTuningQuery.statePrecisionRegime()
                                + " | source=" + universalMeasuredTuning.source()
                                + " | persistence=ephemeral");
            }
        }
        KoilMeasuredTuningProfile pendingUniversalTuning = PENDING_UNIVERSAL_TUNING.get();
        if (pendingUniversalTuning != null
                && pendingUniversalTuning.compatibleWith(universalAdapter, universalModelProfile, universalHardwareProfile)
                && universalComputeMode == KoilComputeMode.AUTOMATIC) {
            universalMeasuredTuning = pendingUniversalTuning;
            LocalModelRuntimeLog.write(
                    "universal_tuning_pending_activation",
                    "source=" + pendingUniversalTuning.source()
                            + " | identity=" + pendingUniversalTuning.identity()
                            + " | candidate=" + pendingUniversalTuning.decisions().getOrDefault("candidateLabel", "")
                            + " | batch=" + pendingUniversalTuning.decisions().getOrDefault("batchSize", "")
                            + "/" + pendingUniversalTuning.decisions().getOrDefault("ubatchSize", ""));
        }

        runtime.registerProvider(new KoilUnifiedLocalModelProvider(
                executionAdapter,
                universalAdapter,
                universalModelProfile,
                universalHardwareProfile,
                universalComputeMode,
                universalMeasuredTuning));
        ChatHudPanelRegistry.registerIfAbsent(new ModelGenerationChatPanel());
        CompletableFuture.runAsync(ModelVoiceService::voices);
        CompletableFuture.runAsync(KoilKnowledgeRuntime::shared);
        CompletableFuture.runAsync(() -> CodeIntelligenceService.instance().warm());
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
        if (selection.complete() && "llama_cpp".equals(selection.providerId())) {
            LocalModelRuntimeManager startupRuntime = runtime;
            startupRuntime.prepareSelectedProvider().whenComplete((prepared, failure) -> {
                if (failure != null) {
                    LocalModelRuntimeLog.write("llama_proactive_start_failed", message(failure));
                } else if (prepared != null) {
                    LocalModelRuntimeLog.write(
                        "llama_proactive_start",
                        "state=" + prepared.state().name().toLowerCase(java.util.Locale.ROOT)
                            + " | detail=" + prepared.detail()
                    );
                }
            });
        }
    }

    public static boolean ask(String prompt) {
        return submitPrompt(prompt, RequestMode.ASK, true);
    }

    public static void refreshExperimentalFeatures() {
        ModelExperimentalFeatures.reload();
        if (ModelExperimentalFeatures.snapshot().persistentConversationHistory()) {
            ModelConversationPersistence.restore(CONVERSATIONS);
            importPersistedConversationKnowledge();
        }
    }

    private static void importPersistedConversationKnowledge() {
        if (!ModelExperimentalFeatures.snapshot().persistentKnowledge()) return;
        List<ModelConversationPersistence.FinalExchange> exchanges = ModelConversationPersistence.finalExchanges(CONVERSATIONS);
        if (exchanges.isEmpty()) return;
        CompletableFuture.runAsync(() -> KoilKnowledgeRuntime.shared().ifPresent(engine -> CompletableFuture.allOf(
                exchanges.stream().map(exchange -> engine.rememberConversation(
                        exchange.prompt(), exchange.response(), exchange.conversationId(), exchange.stableKey()
                )).toArray(CompletableFuture[]::new)
        ).whenComplete((ignored, failure) -> {
            if (failure != null) LocalModelRuntimeLog.write("conversation_knowledge_import_failed", message(failure));
        })));
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
        ModelConversationPersistence.save(CONVERSATIONS);
    }

    public static void resetAutomationConversation() {
        CONVERSATIONS.clear(ModelConversationRegistry.AUTOMATION);
        ModelConversationPersistence.save(CONVERSATIONS);
    }

    public static void resetAllConversations() {
        CONVERSATIONS.clearAll();
        ModelConversationPersistence.save(CONVERSATIONS);
    }

    /**
     * Returns the durable local-model chat transcript across normal /ask and
     * Automation conversations. Request context windows may be bounded, but the
     * transcript itself is retained until an explicit /model reset command.
     */
    public static List<ModelMessage> conversationTranscriptSnapshot() {
        initialize();
        List<ModelMessage> transcript = new ArrayList<>();
        transcript.addAll(CONVERSATIONS.conversation(ModelConversationRegistry.GENERAL).snapshot());
        transcript.addAll(CONVERSATIONS.conversation(ModelConversationRegistry.AUTOMATION).snapshot());
        transcript.sort(java.util.Comparator
                .comparing(ModelMessage::createdAt)
                .thenComparing(message -> message.id().toString()));
        return List.copyOf(transcript);
    }

    public static long conversationResetEpoch() {
        initialize();
        return CONVERSATIONS.resetEpoch();
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

    /**
     * Explicitly cancels a model/runtime installation operation. Keep this
     * separate from {@link #cancelActiveWork()} so world disconnects, screen
     * lifecycle changes, and automation stops cannot kill a Metal runtime build.
     */
    public static boolean cancelActiveRuntimeSetup() {
        return LocalModelInstallationService.instance().cancel();
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

    /**
     * Detects explicit provider control markup that cannot itself be presented as the final answer.
     * Natural-language prose is never guessed to be private reasoning. Only explicit <think> markup
     * is classified as reasoning, so small models are not forced back into another generation merely
     * because their visible answer sounds reflective.
     */
    public static boolean requiresFinalAnswerContinuation(String text) {
        ModelReasoningMarkupParser.Partition partition = ModelReasoningMarkupParser.partition(text);
        if (partition.reasoningOnly()) {
            return true;
        }
        return LEADING_FUNCTION_TAG.matcher(partition.visibleText()).find();
    }

    /** Selects the one read-only observation permitted in a grounded /ask round. */
    public static ModelToolCall selectGroundedAskToolCall(List<ModelToolCall> calls) {
        return calls == null || calls.isEmpty() ? null : calls.get(0);
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
        return AutomationModeController.isExperimentalCompactAgentEnabled()
            && currentAutomationEligibility().eligible();
    }

    public static ModelAgentCapabilityProfile selectedAgentProfile() {
        initialize();
        LocalModelCatalogEntry entry = LocalModelCatalog.find(selectedCatalogId()).orElse(null);
        ModelCapabilityDescriptor provider = runtime.selectedCapabilities();
        int estimate = entry == null ? 0 : entry.complexReasoningEstimatePercent();
        double parameters = selectedModelParametersBillions();
        boolean toolCalling = selectedToolGateEnabled(entry, provider);
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
            selectedToolProtocol(),
            staged
        );
    }

    public static String selectedProviderId() {
        initialize();
        return runtime.selectedProviderId();
    }

    /** Internal execution implementation. This is diagnostic/runtime detail, not a second Koil provider. */
    public static String selectedExecutionAdapterId() {
        initialize();
        return runtime.selectedExecutionAdapterId();
    }

    public static String selectedToolProtocol() {
        initialize();
        return runtime.selectedExecutionAdapter().toolProtocol();
    }

    public static LlamaCppComputeSettings llamaCppComputeSettings() {
        return LlamaCppComputeSettingsStore.load();
    }

    /**
     * Persists the compute policy and live-reloads llama.cpp when it is selected.
     * Repeated requests for the exact same in-flight compute activation attach to
     * the existing future instead of starting a second runtime build.
     */
    public static CompletableFuture<Void> configureLlamaCppCompute(LlamaCppComputeSettings settings) {
        LlamaCppComputeSettings safe = settings == null ? LlamaCppComputeSettings.defaults() : settings;
        synchronized (LLAMA_COMPUTE_ACTIVATION_LOCK) {
            LlamaCppComputeActivation active = activeLlamaComputeActivation;
            if (active != null && !active.future().isDone()) {
                if (active.settings().equals(safe)) {
                    return active.future();
                }
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Another llama.cpp compute activation is already in progress: "
                                + active.settings().mode().displayName()
                                + (active.settings().mode() == LlamaCppComputeMode.HYBRID
                                ? " with " + active.settings().hybridGpuLayers() + " GPU layers"
                                : "")
                                + ". Wait for it to finish or cancel the active model setup first."));
            }

            CompletableFuture<Void> future = configureLlamaCppComputeInternal(safe);
            LlamaCppComputeActivation activation = new LlamaCppComputeActivation(safe, future);
            activeLlamaComputeActivation = activation;
            future.whenComplete((ignored, failure) -> {
                synchronized (LLAMA_COMPUTE_ACTIVATION_LOCK) {
                    if (activeLlamaComputeActivation == activation) {
                        activeLlamaComputeActivation = null;
                    }
                }
            });
            return future;
        }
    }

    public static LlamaCppComputeSettings activeLlamaCppComputeSettings() {
        LlamaCppComputeActivation active = activeLlamaComputeActivation;
        return active != null && !active.future().isDone() ? active.settings() : null;
    }

    public static String llamaCppMaxTuningStatus() {
        return llamaMaxTuningStatus;
    }

    public static CompletableFuture<LlamaCppMaxTuningResult> activeLlamaCppMaxTuning() {
        CompletableFuture<LlamaCppMaxTuningResult> active = activeLlamaMaxTuning;
        return active != null && !active.isDone() ? active : null;
    }

    /**
     * Benchmarks real llama.cpp placements and runtime geometry on this exact
     * machine/model/runtime, persists the measured winner, then activates MAX
     * using that winner. The sweep is intentionally bounded and staged.
     */
    public static CompletableFuture<LlamaCppMaxTuningResult> tuneLlamaCppMaxPerformance() {
        return tuneLlamaCppMaxPerformance(0);
    }

    /**
     * Discovers the highest context that the normal startup memory policy can actually hold, then
     * locks that exact context for the entire MAX benchmark sweep.
     */
    public static CompletableFuture<LlamaCppMaxTuningResult> tuneLlamaCppMaxPerformanceSafestContext() {
        return tuneLlamaCppMaxPerformance(-1);
    }

    /**
     * Runs MAX autotuning at an optional exact benchmark context target. The target is still
     * passed through llama.cpp's normal startup memory-fit policy; if it cannot be held safely,
     * the sweep fails without persisting a winner for that context regime.
     */
    public static CompletableFuture<LlamaCppMaxTuningResult> tuneLlamaCppMaxPerformance(int requestedContextTokens) {
        initialize();
        synchronized (LLAMA_MAX_TUNING_LOCK) {
            CompletableFuture<LlamaCppMaxTuningResult> active = activeLlamaMaxTuning;
            if (active != null && !active.isDone()) return active;
            if (selection == null || !selection.complete() || !"llama_cpp".equals(selection.providerId())) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "MAX tuning requires a selected llama.cpp model."));
            }
            if (runtime == null || runtime.queueDepth() > 0 || !SESSIONS.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "MAX tuning requires an idle local-model runtime. Finish or cancel active model work first."));
            }

            int requestedContext = requestedContextTokens < 0
                    ? -1
                    : normalizeBenchmarkContextTarget(requestedContextTokens);
            if (requestedContext > 0 && selection.contextTokens() > 0 && requestedContext > selection.contextTokens()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "Requested MAX benchmark context " + requestedContext
                                + " exceeds the selected model context ceiling " + selection.contextTokens() + "."));
            }

            LlamaCppComputeSettings original = LlamaCppComputeSettingsStore.load();
            CompletableFuture<LlamaCppMaxTuningResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return runLlamaCppMaxTuning(original, requestedContext);
                } catch (Throwable failure) {
                    LlamaCppMaxTuningStore.clearActiveCandidate();
                    try {
                        LlamaCppComputeSettingsStore.save(original);
                        configureLlamaCppCompute(original).join();
                    } catch (Throwable rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                    if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
                    throw new IllegalStateException("MAX autotuning failed", failure);
                } finally {
                    LlamaCppMaxTuningStore.clearActiveCandidate();
                    LlamaCppBenchmarkContextOverride.clear();
                    LlamaCppBenchmarkStatePrecisionOverride.clear();
                }
            });
            activeLlamaMaxTuning = future;
            llamaMaxTuningStatus = "starting benchmark sweep";
            future.whenComplete((result, failure) -> {
                synchronized (LLAMA_MAX_TUNING_LOCK) {
                    if (activeLlamaMaxTuning == future) activeLlamaMaxTuning = null;
                }
                llamaMaxTuningStatus = failure == null && result != null && result.winner() != null
                        ? "tuned: " + result.winner().profile().summary()
                        : failure == null ? "idle" : "failed: " + message(failure);
            });
            return future;
        }
    }

    public static java.util.Optional<LlamaCppMaxTuningStore.TunedProfile> latestLlamaCppMaxTuning() {
        initialize();
        return LlamaCppMaxTuningStore.latestForModel(configuredModelId());
    }

    private static int effectiveUniversalTuningContextTokens(
            LocalModelSelection selected,
            LocalModelProvider executionAdapter,
            KoilHardwareProfile hardwareProfile
    ) {
        int configured = selected == null ? 0 : selected.contextTokens();
        if (executionAdapter instanceof LlamaCppLocalModelProvider llama) {
            try {
                String hardwareFingerprint = hardwareProfile == null ? "" : hardwareProfile.fingerprint();
                int planned = llama.plannedStartupContextTokens(hardwareFingerprint);
                if (planned > 0) {
                    LocalModelRuntimeLog.write(
                            "universal_tuning_context_predicted",
                            "configured=" + Math.max(0, configured)
                                    + " | planned=" + planned
                                    + " | regime=" + KoilTuningKey.contextRegime(planned)
                                    + " | hardware=" + hardwareFingerprint);
                    return planned;
                }
            } catch (Exception failure) {
                LocalModelRuntimeLog.write("universal_tuning_context_prediction_failed", message(failure));
            }
        }
        return Math.max(0, configured);
    }

    private static int activeBenchmarkContextTokens() {
        LocalModelRuntimeManager activeRuntime = runtime;
        if (activeRuntime == null) return selection == null ? 0 : selection.contextTokens();
        try {
            String active = activeRuntime.health().diagnostics().getOrDefault("activeContextTokens", "");
            int parsed = Integer.parseInt(active.strip());
            if (parsed > 0) return parsed;
        } catch (Exception ignored) {
        }
        int capability = activeRuntime.selectedMaximumContextTokens();
        if (capability > 0) return capability;
        return selection == null ? 0 : selection.contextTokens();
    }

    private static LlamaCppMaxTuningResult runLlamaCppMaxTuning(
            LlamaCppComputeSettings original,
            int requestedContextTokens
    ) {
        if (requestedContextTokens < 0) {
            requestedContextTokens = discoverSafestBenchmarkContext(original);
        }
        final int resolvedBenchmarkContextTokens = requestedContextTokens;

        int logicalThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
        int physicalThreads = logicalThreads;
        try {
            HardwareCapabilityReport report = hardwareReport(true).join();
            if (report != null && report.physicalCpuCount() > 0) {
                physicalThreads = Math.min(logicalThreads, Math.max(1, report.physicalCpuCount()));
            }
        } catch (Exception ignored) {
            if (logicalThreads >= 4 && logicalThreads % 2 == 0) physicalThreads = logicalThreads / 2;
        }

        int knownLayers = knownLlamaModelLayers();
        java.util.LinkedHashMap<String, LlamaCppMaxRuntimeProfile> firstPass = new java.util.LinkedHashMap<>();
        String candidateDevice = original.device() == null ? "" : original.device();
        KoilRuntimeBackend candidateBackend = resolveBenchmarkBackend(original, candidateDevice);
        int candidateContextTokens = resolvedBenchmarkContextTokens > 0
                ? resolvedBenchmarkContextTokens
                : activeBenchmarkContextTokens();
        if (resolvedBenchmarkContextTokens > 0) {
            LocalModelRuntimeLog.write(
                    "universal_benchmark_context_target",
                    "requested=" + resolvedBenchmarkContextTokens
                            + " | regime=" + KoilTuningKey.contextRegime(resolvedBenchmarkContextTokens)
                            + " | safety=normal_startup_memory_policy");
        }
        KoilMemoryPressureSnapshot candidateMemorySnapshot = benchmarkCandidateMemorySnapshot();
        KoilCandidateGenerationBudget candidateGenerationBudget = KoilCandidateGenerationBudget.from(candidateMemorySnapshot);
        LocalModelRuntimeLog.write(
                "universal_benchmark_candidate_budget",
                "pressure=" + candidateGenerationBudget.pressure().name().toLowerCase(java.util.Locale.ROOT)
                        + " | available_mib=" + (candidateGenerationBudget.availableBytes() / (1024L * 1024L))
                        + " | discretionary_mib=" + (candidateGenerationBudget.discretionaryBudgetBytes() / (1024L * 1024L))
                        + " | baseline=" + candidateGenerationBudget.baselineBatchSize() + "/"
                        + candidateGenerationBudget.baselineMicroBatchSize()
                        + " | large_batch=" + candidateGenerationBudget.allowLargeBatch()
                        + " | large_ubatch=" + candidateGenerationBudget.allowLargeMicroBatch());
        String benchmarkStatePrecisionRegime = benchmarkStatePrecisionRegime(
                candidateContextTokens, candidateBackend, candidateMemorySnapshot);
        LocalModelRuntimeLog.write(
                "universal_benchmark_state_precision",
                "context=" + KoilTuningKey.contextRegime(candidateContextTokens)
                        + " | state_precision=" + benchmarkStatePrecisionRegime
                        + " | policy=single_regime_sweep");
        KoilTuningKey benchmarkHistoryKey = universalBenchmarkHistoryKey(
                candidateBackend, KoilTuningKey.contextRegime(candidateContextTokens), benchmarkStatePrecisionRegime);
        java.nio.file.Path benchmarkHistoryPath = KoilInstancePaths.modelRoot()
                .resolve("universal-benchmark-history.properties");
        List<KoilBenchmarkHistoryStore.StoredSession> benchmarkHistory = benchmarkHistoryKey == null
                ? List.of()
                : KoilBenchmarkHistoryStore.recent(benchmarkHistoryPath, benchmarkHistoryKey, 12);
        if (!benchmarkHistory.isEmpty()) {
            int historicalResults = benchmarkHistory.stream()
                    .mapToInt(value -> value.session().results().size()).sum();
            LocalModelRuntimeLog.write(
                    "universal_benchmark_history_loaded",
                    "sessions=" + benchmarkHistory.size()
                            + " | results=" + historicalResults
                            + " | context=" + benchmarkHistoryKey.contextRegime()
                            + " | workload=" + KoilBenchmarkWorkload.Kind.INTERACTIVE.name().toLowerCase(java.util.Locale.ROOT)
                            + " | influence=max10pct");
        }
        // Koil owns the candidate policy. llama.cpp supplies only its adapter-specific
        // conservative hybrid batch geometry and later translates each policy to native flags.
        for (KoilBenchmarkCandidate candidate : KoilCandidateGenerator.baseline(
                candidateBackend,
                candidateDevice,
                knownLayers,
                original.hybridGpuLayers(),
                physicalThreads,
                logicalThreads,
                KoilTuningKey.contextRegime(candidateContextTokens),
                LlamaCppComputeStabilityStore::conservativeHybridBatchGeometry,
                candidateGenerationBudget)) {
            addMaxCandidate(firstPass, LlamaCppBenchmarkCandidateBridge.toRuntimeProfile(candidate));
        }

        LlamaCppBenchmarkAdapter benchmarkAdapter = new LlamaCppBenchmarkAdapter(
                profile -> {
                    // Candidate failures must roll back to a deterministic non-MAX runtime.
                    // If tuning began while MAX was active, MAX itself cannot be the rollback target.
                    LlamaCppComputeSettingsStore.save(original.withMode(LlamaCppComputeMode.CPU));
                    LlamaCppMaxTuningStore.setActiveCandidate(profile);
                    return configureLlamaCppBenchmarkCandidate(
                            original.withMode(LlamaCppComputeMode.MAX),
                            resolvedBenchmarkContextTokens,
                            benchmarkStatePrecisionRegime);
                },
                request -> {
                    LocalModelRuntimeManager activeRuntime = runtime;
                    if (activeRuntime == null) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "runtime disappeared during universal benchmark"));
                    }
                    return activeRuntime.benchmarkSelectedProvider(request);
                },
                () -> {
                    LocalModelRuntimeManager activeRuntime = runtime;
                    if (activeRuntime == null) throw new IllegalStateException("runtime disappeared during benchmark telemetry capture");
                    return new LlamaCppBenchmarkAdapter.RuntimeSnapshot(
                            activeRuntime.health().diagnostics(), activeBenchmarkContextTokens());
                }
        );

        List<MeasuredMaxCandidate> measured = new ArrayList<>();
        List<KoilBenchmarkResult> benchmarkEvidence = new ArrayList<>();
        java.time.Instant benchmarkStartedAt = java.time.Instant.now();
        int[] failed = {0};
        LlamaCppConfiguration stabilityConfiguration = currentLlamaCppConfiguration();
        String stabilityDevice = original.device();
        if (stabilityDevice == null || stabilityDevice.isBlank()) {
            try {
                LlamaCppDeviceProbe.ProbeResult stabilityProbe = probeCurrentLlamaRuntime(stabilityConfiguration);
                stabilityDevice = stabilityProbe.devices().stream()
                        .findFirst()
                        .map(LlamaCppDeviceProbe.Device::id)
                        .orElse("");
            } catch (Exception ignored) {
                stabilityDevice = "";
            }
        }
        long tuningAvailableMemory = LlamaCppComputeStabilityStore.currentAvailableMemoryBytes();
        int persistedUnsafeHybridLayers = LlamaCppComputeStabilityStore.blockedAtOrAbove(
                stabilityConfiguration.modelFile(), stabilityDevice, tuningAvailableMemory);
        int firstUnsafeHybridLayers = persistedUnsafeHybridLayers > 0
                ? persistedUnsafeHybridLayers
                : Integer.MAX_VALUE;
        LlamaCppComputeStabilityStore.Envelope persistedStability = LlamaCppComputeStabilityStore.envelope(
                stabilityConfiguration.modelFile(), stabilityDevice);
        if (persistedUnsafeHybridLayers > 0) {
            LocalModelRuntimeLog.write(
                    "llama_max_tune_stability_envelope",
                    "skipping hybrid candidates >= " + persistedUnsafeHybridLayers
                            + " under current memory headroom | "
                            + LlamaCppComputeStabilityStore.envelope(
                            stabilityConfiguration.modelFile(), stabilityDevice).summary());
        }
        for (LlamaCppMaxRuntimeProfile profile : firstPass.values()) {
            if (profile.placement() == LlamaCppMaxRuntimeProfile.Placement.HYBRID) {
                if (profile.gpuLayers() >= firstUnsafeHybridLayers) {
                    LocalModelRuntimeLog.write("llama_max_tune_candidate",
                            "skipped after learned pressure ceiling | " + profile.summary());
                    continue;
                }
                if (persistedStability.integrityInvalidLayers().contains(profile.gpuLayers())) {
                    LocalModelRuntimeLog.write("llama_max_tune_candidate",
                            "skipped exact layer split after prior integrity failure | " + profile.summary());
                    continue;
                }
            }
            boolean healthy = measureMaxCandidate(profile, original, benchmarkAdapter, measured, benchmarkEvidence, failed, benchmarkStatePrecisionRegime, candidateContextTokens);
            if (!healthy && profile.placement() == LlamaCppMaxRuntimeProfile.Placement.HYBRID) {
                long availableNow = LlamaCppComputeStabilityStore.currentAvailableMemoryBytes();
                int learnedPressure = LlamaCppComputeStabilityStore.blockedAtOrAbove(
                        stabilityConfiguration.modelFile(), stabilityDevice, availableNow);
                if (learnedPressure > 0) {
                    firstUnsafeHybridLayers = Math.min(firstUnsafeHybridLayers, learnedPressure);
                }
                persistedStability = LlamaCppComputeStabilityStore.envelope(
                        stabilityConfiguration.modelFile(), stabilityDevice);
            }
            if (knownLayers <= 1) knownLayers = knownLlamaModelLayers();
        }

        if (knownLayers > 1) {
            java.util.LinkedHashMap<String, LlamaCppMaxRuntimeProfile> discoveredHybrids = new java.util.LinkedHashMap<>();
            for (KoilBenchmarkCandidate candidate : KoilCandidateGenerator.baseline(
                    candidateBackend,
                    candidateDevice,
                    knownLayers,
                    original.hybridGpuLayers(),
                    physicalThreads,
                    logicalThreads,
                    KoilTuningKey.contextRegime(candidateContextTokens),
                    LlamaCppComputeStabilityStore::conservativeHybridBatchGeometry,
                    candidateGenerationBudget)) {
                if (candidate.settings().placement() == com.spirit.koil.api.model.runtime.universal.KoilPlacementPolicy.HYBRID) {
                    addMaxCandidate(discoveredHybrids, LlamaCppBenchmarkCandidateBridge.toRuntimeProfile(candidate));
                }
            }
            for (LlamaCppMaxRuntimeProfile profile : discoveredHybrids.values()) {
                if (profile.gpuLayers() >= firstUnsafeHybridLayers) continue;
                if (persistedStability.integrityInvalidLayers().contains(profile.gpuLayers())) continue;
                if (measured.stream().noneMatch(value -> sameMaxGeometry(value.profile(), profile))) {
                    boolean healthy = measureMaxCandidate(profile, original, benchmarkAdapter, measured, benchmarkEvidence, failed, benchmarkStatePrecisionRegime, candidateContextTokens);
                    if (!healthy) {
                        long availableNow = LlamaCppComputeStabilityStore.currentAvailableMemoryBytes();
                        int learnedPressure = LlamaCppComputeStabilityStore.blockedAtOrAbove(
                                stabilityConfiguration.modelFile(), stabilityDevice, availableNow);
                        if (learnedPressure > 0) {
                            firstUnsafeHybridLayers = Math.min(firstUnsafeHybridLayers, learnedPressure);
                        }
                        persistedStability = LlamaCppComputeStabilityStore.envelope(
                                stabilityConfiguration.modelFile(), stabilityDevice);
                    }
                }
            }
        }

        if (measured.isEmpty()) {
            LlamaCppMaxTuningStore.clearActiveCandidate();
            LlamaCppComputeSettingsStore.save(original);
            configureLlamaCppCompute(original).join();
            throw new IllegalStateException("MAX autotuning could not complete any valid benchmark candidate.");
        }

        List<LlamaCppMaxTuningResult.CandidateResult> scored = scoreMaxCandidates(measured, benchmarkHistory, maxScoringWorkload(candidateContextTokens));
        LlamaCppMaxTuningResult.CandidateResult provisional = scored.stream()
                .max(java.util.Comparator.comparingDouble(LlamaCppMaxTuningResult.CandidateResult::score))
                .orElseThrow();

        java.util.LinkedHashMap<String, LlamaCppMaxRuntimeProfile> refinements = new java.util.LinkedHashMap<>();
        LlamaCppMaxRuntimeProfile base = provisional.profile();
        KoilBenchmarkCandidate universalBase = LlamaCppBenchmarkCandidateBridge.fromRuntimeProfile(
                base, candidateBackend, candidateDevice, KoilTuningKey.contextRegime(candidateContextTokens));
        for (KoilBenchmarkCandidate candidate : KoilCandidateGenerator.refinements(
                universalBase, physicalThreads, logicalThreads, candidateGenerationBudget)) {
            addMaxCandidate(refinements, LlamaCppBenchmarkCandidateBridge.toRuntimeProfile(candidate));
        }

        for (LlamaCppMaxRuntimeProfile profile : refinements.values()) {
            if (measured.stream().noneMatch(value -> sameMaxGeometry(value.profile(), profile))) {
                measureMaxCandidate(profile, original, benchmarkAdapter, measured, benchmarkEvidence, failed, benchmarkStatePrecisionRegime, candidateContextTokens);
            }
        }

        scored = scoreMaxCandidates(measured, benchmarkHistory, maxScoringWorkload(candidateContextTokens));
        LlamaCppMaxTuningResult.CandidateResult winner = scored.stream()
                .max(java.util.Comparator.comparingDouble(LlamaCppMaxTuningResult.CandidateResult::score))
                .orElseThrow();

        LlamaCppConfiguration tuningConfiguration = currentLlamaCppConfiguration();
        LlamaCppDeviceProbe.ProbeResult probe = probeCurrentLlamaRuntime(tuningConfiguration);
        String resolvedDevice = runtime == null ? ""
                : runtime.health().diagnostics().getOrDefault("resolvedComputeDevice", "");
        LlamaCppDeviceProbe.Device device = probe.devices().stream()
                .filter(value -> !resolvedDevice.isBlank() && value.id().equalsIgnoreCase(resolvedDevice))
                .findFirst()
                .orElseGet(() -> probe.devices().stream().findFirst()
                        .orElse(new LlamaCppDeviceProbe.Device(resolvedDevice, "")));
        LlamaCppMaxTuningStore.TunedProfile saved = LlamaCppMaxTuningStore.save(
                tuningConfiguration,
                device.id(),
                device.detail(),
                KoilTuningKey.contextRegime(candidateContextTokens),
                benchmarkStatePrecisionRegime,
                winner.profile(),
                winner.benchmark(),
                winner.score(),
                measured.size()
        );

        LlamaCppMaxRuntimeProfile initiallySelectedWinnerProfile = winner.profile();
        MeasuredMaxCandidate universalWinner = measured.stream()
                .filter(value -> sameMaxGeometry(value.profile(), initiallySelectedWinnerProfile))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("universal winner measurement disappeared before activation"));
        PreparedUniversalWinner preparedUniversalWinner = prepareUniversalBenchmarkWinner(
                universalWinner.universalResult(), winner.score(), saved);

        // The final runtime restart must be driven by Koil's just-measured winner, not by the
        // compatibility profile that happened to be loaded when tuning began. Keep this handoff
        // process-local until activation succeeds so a failed candidate is never durable evidence.
        PENDING_UNIVERSAL_TUNING.set(preparedUniversalWinner.profile());
        LlamaCppMaxTuningStore.clearActiveCandidate();
        LlamaCppComputeSettingsStore.save(original);
        try {
            configureLlamaCppBenchmarkCandidate(
                    original.withMode(LlamaCppComputeMode.MAX),
                    resolvedBenchmarkContextTokens,
                    benchmarkStatePrecisionRegime).join();
        } catch (Exception finalActivationFailure) {
            PENDING_UNIVERSAL_TUNING.compareAndSet(preparedUniversalWinner.profile(), null);

            Exception activationFailure = finalActivationFailure;
            boolean winnerRecovered = false;

            // A model switch or transient UMA/backend transition can occasionally let llama.cpp
            // start successfully but with the accelerator candidate degraded all the way to CPU.
            // The compute transition has already restored the previous known-good runtime at this
            // point, so give the exact measured winner one bounded retry before abandoning it.
            // Hard-floor memory failures are not retried; they go directly to measured CPU fallback.
            if (winner.profile().placement() != LlamaCppMaxRuntimeProfile.Placement.CPU
                    && LlamaCppMaxActivationFallbackPolicy.isAcceleratorDegradedToCpuFailure(finalActivationFailure)) {
                if (LlamaCppMaxActivationFallbackPolicy.shouldRetryAccelerator(finalActivationFailure)) {
                    LocalModelRuntimeLog.write(
                            "llama_max_tune_activation_accelerator_retry",
                            "retrying measured winner after CPU degradation | winner=" + winner.profile().summary()
                                    + " | reason=" + message(finalActivationFailure));
                    PENDING_UNIVERSAL_TUNING.set(preparedUniversalWinner.profile());
                    LlamaCppComputeSettingsStore.save(original);
                    try {
                        configureLlamaCppBenchmarkCandidate(
                                original.withMode(LlamaCppComputeMode.MAX),
                                resolvedBenchmarkContextTokens,
                                benchmarkStatePrecisionRegime).join();
                        winnerRecovered = true;
                        LocalModelRuntimeLog.write(
                                "llama_max_tune_activation_accelerator_retry_recovered",
                                "activated measured accelerator winner on bounded retry | " + winner.profile().summary());
                    } catch (Exception retryFailure) {
                        PENDING_UNIVERSAL_TUNING.compareAndSet(preparedUniversalWinner.profile(), null);
                        retryFailure.addSuppressed(finalActivationFailure);
                        activationFailure = retryFailure;
                    }
                } else {
                    LocalModelRuntimeLog.write(
                            "llama_max_tune_activation_accelerator_retry_skipped",
                            "skipping accelerator retry because live UMA/host pressure is already critical | winner="
                                    + winner.profile().summary());
                }
            }

            if (!winnerRecovered) {
                PENDING_UNIVERSAL_TUNING.compareAndSet(preparedUniversalWinner.profile(), null);
                LlamaCppMaxTuningStore.remove(tuningConfiguration, device.id(), device.detail(), KoilTuningKey.contextRegime(candidateContextTokens), benchmarkStatePrecisionRegime);

                java.util.Optional<LlamaCppMaxTuningResult.CandidateResult> measuredCpuFallback =
                        LlamaCppMaxActivationFallbackPolicy.allowsMeasuredCpuFallback(activationFailure)
                                ? LlamaCppMaxActivationFallbackPolicy.bestMeasuredCpu(scored)
                                : java.util.Optional.empty();
                if (measuredCpuFallback.isPresent()
                        && !sameMaxGeometry(measuredCpuFallback.get().profile(), winner.profile())) {
                    LlamaCppMaxTuningResult.CandidateResult fallbackWinner = measuredCpuFallback.get();
                    LocalModelRuntimeLog.write(
                            "llama_max_tune_activation_cpu_fallback",
                            "winner=" + winner.profile().summary()
                                    + " | fallback=" + fallbackWinner.profile().summary()
                                    + " | reason=" + message(activationFailure));

                    LlamaCppMaxTuningStore.TunedProfile fallbackSaved = LlamaCppMaxTuningStore.save(
                            tuningConfiguration,
                            device.id(),
                            device.detail(),
                            KoilTuningKey.contextRegime(candidateContextTokens),
                            benchmarkStatePrecisionRegime,
                            fallbackWinner.profile(),
                            fallbackWinner.benchmark(),
                            fallbackWinner.score(),
                            measured.size()
                    );
                    MeasuredMaxCandidate fallbackMeasurement = measured.stream()
                            .filter(value -> sameMaxGeometry(value.profile(), fallbackWinner.profile()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "measured CPU activation fallback disappeared before activation"));
                    PreparedUniversalWinner fallbackPrepared = prepareUniversalBenchmarkWinner(
                            fallbackMeasurement.universalResult(), fallbackWinner.score(), fallbackSaved);

                    PENDING_UNIVERSAL_TUNING.set(fallbackPrepared.profile());
                    LlamaCppComputeSettingsStore.save(original);
                    try {
                        configureLlamaCppBenchmarkCandidate(
                                original.withMode(LlamaCppComputeMode.MAX),
                                resolvedBenchmarkContextTokens,
                                benchmarkStatePrecisionRegime).join();
                        winner = fallbackWinner;
                        saved = fallbackSaved;
                        preparedUniversalWinner = fallbackPrepared;
                        LocalModelRuntimeLog.write(
                                "llama_max_tune_activation_cpu_fallback_recovered",
                                "activated measured CPU fallback | " + fallbackWinner.profile().summary());
                    } catch (Exception fallbackActivationFailure) {
                        PENDING_UNIVERSAL_TUNING.compareAndSet(fallbackPrepared.profile(), null);
                        LlamaCppMaxTuningStore.remove(tuningConfiguration, device.id(), device.detail(), KoilTuningKey.contextRegime(candidateContextTokens), benchmarkStatePrecisionRegime);
                        LlamaCppComputeSettingsStore.save(original);
                        try {
                            configureLlamaCppCompute(original.withMode(LlamaCppComputeMode.CPU)).join();
                        } catch (Exception rollbackFailure) {
                            fallbackActivationFailure.addSuppressed(rollbackFailure);
                        }
                        IllegalStateException combined = new IllegalStateException(
                                "MAX tuning accelerator winner could not be activated safely, and the measured CPU fallback also failed: "
                                        + message(fallbackActivationFailure),
                                fallbackActivationFailure);
                        combined.addSuppressed(activationFailure);
                        throw combined;
                    }
                } else {
                    LlamaCppComputeSettingsStore.save(original);
                    try {
                        configureLlamaCppCompute(original).join();
                    } catch (Exception rollbackFailure) {
                        activationFailure.addSuppressed(rollbackFailure);
                    }
                    throw new IllegalStateException(
                            "MAX tuning found a winner but the universal winner could not be reactivated: "
                                    + message(activationFailure), activationFailure);
                }
            }
        }
        persistUniversalBenchmarkWinner(preparedUniversalWinner);
        persistUniversalBenchmarkHistory(
                preparedUniversalWinner,
                benchmarkEvidence,
                benchmarkStartedAt,
                candidateContextTokens);
        PENDING_UNIVERSAL_TUNING.compareAndSet(preparedUniversalWinner.profile(), null);

        LocalModelRuntimeLog.write(
                "llama_max_tuned",
                "winner=" + winner.profile().summary()
                        + " | prompt_tps=" + String.format(java.util.Locale.ROOT, "%.2f", winner.benchmark().promptTokensPerSecond())
                        + " | generation_tps=" + String.format(java.util.Locale.ROOT, "%.2f", winner.benchmark().generationTokensPerSecond())
                        + " | ttft_ms=" + String.format(java.util.Locale.ROOT, "%.2f", winner.benchmark().timeToFirstTokenMillis())
                        + " | score=" + String.format(java.util.Locale.ROOT, "%.4f", winner.score())
                        + " | tested=" + measured.size()
                        + " | failed=" + failed[0]
        );
        return new LlamaCppMaxTuningResult(saved, scored, failed[0]);
    }


    private static KoilMemoryPressureSnapshot benchmarkCandidateMemorySnapshot() {
        try {
            LocalModelSelection selected = selection;
            if (selected == null || !selected.complete()) return null;
            KoilHardwareProfile hardware = KoilHardwareProfiler.capture(selected.runtimeId(), selected.runtimeExecutable());
            long observedLaunchAvailable = 0L;
            LocalModelRuntimeManager activeRuntime = runtime;
            if (activeRuntime != null) {
                Map<String, String> diagnostics = activeRuntime.health().diagnostics();
                if (diagnostics != null) {
                    try {
                        observedLaunchAvailable = Long.parseLong(diagnostics.getOrDefault("launchMemoryAvailableBytes", "0"));
                    } catch (NumberFormatException ignored) {
                        observedLaunchAvailable = 0L;
                    }
                }
            }
            if (observedLaunchAvailable > 0L) {
                return KoilMemoryBudgetPlanner.from(
                        hardware.installedMemoryBytes(),
                        observedLaunchAvailable,
                        KoilMemoryPressureSnapshot.Phase.LAUNCH);
            }
            return KoilMemoryBudgetPlanner.capture(hardware);
        } catch (Exception failure) {
            LocalModelRuntimeLog.write("universal_benchmark_candidate_budget_failed", message(failure));
            return null;
        }
    }

    private static String predictUniversalStatePrecisionRegime(
            LocalModelSelection selected,
            LlamaCppConfiguration llamaConfiguration,
            KoilModelProfile model,
            KoilHardwareProfile hardware,
            int contextTokens
    ) {
        if (selected == null || !selected.complete() || !"llama_cpp".equals(selected.providerId())
                || llamaConfiguration == null || model == null) {
            return KoilStatePrecisionEvidence.LEGACY_FP16;
        }
        try {
            KoilRuntimeBackend backend = KoilRuntimeBackend.UNKNOWN;
            if (llamaConfiguration.computeSettings().mode() == LlamaCppComputeMode.CPU) {
                backend = KoilRuntimeBackend.CPU;
            } else {
                LlamaCppDeviceProbe.ProbeResult probe = probeCurrentLlamaRuntime(llamaConfiguration);
                for (LlamaCppDeviceProbe.Device device : probe.devices()) {
                    KoilRuntimeBackend observed = KoilHardwareProfiler.backendFromEvidence(
                            device.id() + " " + device.detail());
                    if (observed != KoilRuntimeBackend.UNKNOWN) {
                        backend = observed;
                        break;
                    }
                }
                if (backend == KoilRuntimeBackend.UNKNOWN && hardware != null && hardware.acceleratorObserved()) {
                    backend = hardware.accelerators().get(0).backend();
                }
            }

            KoilStatePrecisionRegimePredictor.Prediction prediction = KoilStatePrecisionRegimePredictor.predict(
                    model, hardware, Math.max(1, contextTokens),
                    LlamaCppComputeStabilityStore.currentAvailableMemoryBytes(), backend);
            LocalModelRuntimeLog.write(
                    "universal_tuning_precision_predicted",
                    "context=" + KoilTuningKey.contextRegime(contextTokens)
                            + " | state_precision=" + prediction.regime()
                            + " | backend=" + prediction.backend().name().toLowerCase(java.util.Locale.ROOT)
                            + " | pressure=" + prediction.memory().pressure().name().toLowerCase(java.util.Locale.ROOT)
                            + " | estimated_state_mib=" + (prediction.estimate().fullPrecisionBytes() / (1024L * 1024L))
                            + " | projected_k_savings_mib=" + (prediction.estimate().q8KeySavingsBytes() / (1024L * 1024L))
                            + " | reason=" + prediction.decision().reason());
            return prediction.regime();
        } catch (Exception failure) {
            LocalModelRuntimeLog.write(
                    "universal_tuning_precision_prediction_failed",
                    message(failure) + " | fallback=" + KoilStatePrecisionEvidence.LEGACY_FP16);
            return KoilStatePrecisionEvidence.LEGACY_FP16;
        }
    }

    private static KoilRuntimeBackend resolveBenchmarkBackend(
            LlamaCppComputeSettings computeSettings,
            String requestedDevice
    ) {
        if (computeSettings != null && computeSettings.mode() == LlamaCppComputeMode.CPU) {
            return KoilRuntimeBackend.CPU;
        }
        KoilRuntimeBackend backend = KoilHardwareProfiler.backendFromEvidence(requestedDevice);
        if (backend != KoilRuntimeBackend.UNKNOWN) return backend;
        try {
            LocalModelRuntimeManager activeRuntime = runtime;
            if (activeRuntime != null) {
                Map<String, String> diagnostics = activeRuntime.health().diagnostics();
                backend = KoilHardwareProfiler.backendFromEvidence(
                        diagnostics.getOrDefault("statePrecisionBackend", "") + " "
                                + diagnostics.getOrDefault("resolvedComputeDevice", "") + " "
                                + diagnostics.getOrDefault("resolvedComputeDeviceDetail", ""));
                if (backend != KoilRuntimeBackend.UNKNOWN) return backend;
            }
        } catch (Exception ignored) { }
        try {
            LlamaCppConfiguration configuration = currentLlamaCppConfiguration();
            LlamaCppDeviceProbe.ProbeResult probe = probeCurrentLlamaRuntime(configuration);
            for (LlamaCppDeviceProbe.Device device : probe.devices()) {
                backend = KoilHardwareProfiler.backendFromEvidence(device.id() + " " + device.detail());
                if (backend != KoilRuntimeBackend.UNKNOWN) return backend;
            }
        } catch (Exception ignored) { }
        return KoilRuntimeBackend.UNKNOWN;
    }

    private static String benchmarkStatePrecisionRegime(
            int contextTokens,
            KoilRuntimeBackend backend,
            KoilMemoryPressureSnapshot memorySnapshot
    ) {
        try {
            LocalModelSelection selected = selection;
            if (selected == null || !selected.complete() || !"llama_cpp".equals(selected.providerId())) {
                return KoilStatePrecisionEvidence.LEGACY_FP16;
            }
            KoilModelProfile model = KoilModelProfiler.inspect(selected.modelPath());
            KoilHardwareProfile hardware = KoilHardwareProfiler.capture(
                    selected.runtimeId(), selected.runtimeExecutable());
            long available = memorySnapshot == null
                    ? LlamaCppComputeStabilityStore.currentAvailableMemoryBytes()
                    : memorySnapshot.availableBytes();
            KoilStatePrecisionRegimePredictor.Prediction prediction = KoilStatePrecisionRegimePredictor.predict(
                    model, hardware, Math.max(1, contextTokens), available,
                    backend == null ? KoilRuntimeBackend.UNKNOWN : backend);
            return prediction.regime();
        } catch (Exception failure) {
            LocalModelRuntimeLog.write(
                    "universal_benchmark_state_precision_failed",
                    message(failure) + " | fallback=" + KoilStatePrecisionEvidence.LEGACY_FP16);
            return KoilStatePrecisionEvidence.LEGACY_FP16;
        }
    }

    private static KoilTuningKey universalBenchmarkHistoryKey(
            KoilRuntimeBackend backend,
            String contextRegime,
            String statePrecisionRegime
    ) {
        try {
            LocalModelSelection selected = selection;
            if (selected == null || !selected.complete() || !"llama_cpp".equals(selected.providerId())) return null;
            KoilModelProfile model = KoilModelProfiler.inspect(selected.modelPath());
            KoilHardwareProfile hardware = KoilHardwareProfiler.capture(selected.runtimeId(), selected.runtimeExecutable());
            KoilExecutionAdapterDescriptor adapter = new KoilExecutionAdapterDescriptor(
                    "llama_cpp", "openai_tool_calls", true, Map.of("bridge", "universal_autotune"));
            String quantization = LocalModelCatalog.find(selected.catalogId())
                    .map(LocalModelCatalogEntry::quantization).orElse("");
            return KoilTuningKey.createWithContextRegime(
                    adapter, model, hardware, selected.modelId(), quantization, contextRegime,
                    statePrecisionRegime, backend == null ? KoilRuntimeBackend.UNKNOWN : backend, selected.runtimeId());
        } catch (Exception failure) {
            LocalModelRuntimeLog.write("universal_benchmark_history_query_failed", message(failure));
            return null;
        }
    }

    private static PreparedUniversalWinner prepareUniversalBenchmarkWinner(
            KoilBenchmarkResult benchmarkWinner,
            double score,
            LlamaCppMaxTuningStore.TunedProfile nativeWinner
    ) {
        if (benchmarkWinner == null || nativeWinner == null) {
            throw new IllegalArgumentException("benchmark and native winners are required");
        }
        LocalModelSelection selected = selection;
        if (selected == null || !selected.complete() || !"llama_cpp".equals(selected.providerId())) {
            throw new IllegalStateException("universal benchmark winner requires the selected llama.cpp adapter");
        }
        KoilModelProfile model = KoilModelProfiler.inspect(selected.modelPath());
        KoilHardwareProfile hardware = KoilHardwareProfiler.capture(selected.runtimeId(), selected.runtimeExecutable());
        KoilExecutionAdapterDescriptor adapter = new KoilExecutionAdapterDescriptor(
                "llama_cpp", "openai_tool_calls", true, Map.of("bridge", "universal_autotune"));
        String quantization = LocalModelCatalog.find(selected.catalogId())
                .map(LocalModelCatalogEntry::quantization).orElse("");
        KoilCandidateScorer.ScoredResult scoredWinner = new KoilCandidateScorer.ScoredResult(benchmarkWinner, score);
        KoilMeasuredTuningProfile universalWinner = KoilBenchmarkTuningProfile.fromWinner(
                "universal:" + nativeWinner.identity(),
                scoredWinner,
                adapter,
                model,
                selected.modelId(),
                nativeWinner.runtimeIdentity(),
                hardware.fingerprint());
        String statePrecisionRegime = KoilStatePrecisionEvidence.fromResult(benchmarkWinner);
        KoilTuningKey key = KoilTuningKey.createWithContextRegime(
                adapter, model, hardware, selected.modelId(), quantization,
                benchmarkWinner.candidate().contextRegime(), statePrecisionRegime,
                universalWinner.primaryBackend(), selected.runtimeId());
        return new PreparedUniversalWinner(
                KoilInstancePaths.modelRoot().resolve("universal-tuning.properties"),
                key, universalWinner, benchmarkWinner, score, adapter.id());
    }

    private static void persistUniversalBenchmarkWinner(PreparedUniversalWinner prepared) {
        if (prepared == null) return;
        try {
            KoilUniversalTuningStore.saveWinner(prepared.storePath(), prepared.key(), prepared.profile());
            KoilBenchmarkResult benchmarkWinner = prepared.benchmarkResult();
            LocalModelRuntimeLog.write(
                    "universal_tuning_persisted",
                    "source=" + prepared.profile().source()
                            + " | protocol=" + prepared.profile().decisions().getOrDefault("benchmarkProtocol", "")
                            + " | adapter=" + prepared.profile().decisions().getOrDefault("benchmarkAdapter", prepared.adapterId())
                            + " | candidate=" + benchmarkWinner.candidate().label()
                            + " | actual=" + benchmarkWinner.actualPlacement() + "/" + benchmarkWinner.actualGpuLayers()
                            + " | context=" + benchmarkWinner.candidate().contextRegime()
                            + " | state_precision=" + prepared.key().statePrecisionRegime()
                            + " | score=" + String.format(java.util.Locale.ROOT, "%.4f", prepared.score()));
        } catch (Exception failure) {
            LocalModelRuntimeLog.write("universal_tuning_persist_failed", message(failure));
        }
    }


    private static void persistUniversalBenchmarkHistory(
            PreparedUniversalWinner prepared,
            List<KoilBenchmarkResult> evidence,
            java.time.Instant startedAt,
            int contextTokens
    ) {
        if (prepared == null || evidence == null || evidence.isEmpty()) return;
        try {
            List<KoilBenchmarkResult> snapshot = List.copyOf(evidence);
            List<KoilCandidateScorer.ScoredResult> scored = KoilCandidateScorer.score(snapshot);
            int failed = (int) snapshot.stream()
                    .filter(result -> result.outcome() != KoilBenchmarkResult.Outcome.SUCCESS)
                    .count();
            KoilBenchmarkSession session = new KoilBenchmarkSession(
                    "max-" + java.util.UUID.randomUUID(),
                    startedAt == null ? java.time.Instant.now() : startedAt,
                    java.time.Instant.now(),
                    snapshot,
                    scored,
                    failed);
            KoilBenchmarkWorkload workload = new KoilBenchmarkWorkload(
                    maxBenchmarkPrompt(contextTokens),
                    96,
                    Duration.ofMinutes(3),
                    "max-autotune");
            java.nio.file.Path historyPath = KoilInstancePaths.modelRoot().resolve("universal-benchmark-history.properties");
            KoilBenchmarkHistoryStore.save(historyPath, prepared.key(), session, workload, prepared.adapterId());
            LocalModelRuntimeLog.write(
                    "universal_benchmark_history_persisted",
                    "session=" + session.id()
                            + " | candidates=" + snapshot.size()
                            + " | successful=" + snapshot.stream().filter(KoilBenchmarkResult::validMeasurement).count()
                            + " | failed=" + failed
                            + " | context=" + prepared.key().contextRegime()
                            + " | adapter=" + prepared.adapterId());
        } catch (Exception failure) {
            LocalModelRuntimeLog.write("universal_benchmark_history_persist_failed", message(failure));
        }
    }

    private static boolean measureMaxCandidate(
            LlamaCppMaxRuntimeProfile profile,
            LlamaCppComputeSettings original,
            LlamaCppBenchmarkAdapter benchmarkAdapter,
            List<MeasuredMaxCandidate> measured,
            List<KoilBenchmarkResult> benchmarkEvidence,
            int[] failed,
            String expectedStatePrecisionRegime,
            int expectedContextTokens
    ) {
        llamaMaxTuningStatus = "benchmarking " + profile.label();
        LocalModelRuntimeLog.write("llama_max_tune_candidate", "starting | " + profile.summary());
        KoilBenchmarkCandidate candidate = null;
        try {
            LocalModelRuntimeManager beforeActivation = runtime;
            int contextTokens = expectedContextTokens > 0 ? expectedContextTokens : activeBenchmarkContextTokens();
            candidate = LlamaCppBenchmarkCandidateBridge.fromRuntimeProfile(
                    profile,
                    KoilHardwareProfiler.backendFromEvidence(original.device()),
                    original.device(),
                    KoilTuningKey.contextRegime(contextTokens)
            );
            KoilBenchmarkWorkload workload = new KoilBenchmarkWorkload(
                    maxBenchmarkPrompt(contextTokens),
                    96,
                    Duration.ofMinutes(3),
                    profile.label()
            );
            KoilBenchmarkResult universalResult = benchmarkAdapter.benchmark(candidate, workload).join();
            benchmarkEvidence.add(universalResult);
            int actualContextTokens = activeBenchmarkContextTokens();
            if (expectedContextTokens > 0 && actualContextTokens != expectedContextTokens) {
                throw new BenchmarkContextUnavailableException(
                        "Requested MAX benchmark context " + expectedContextTokens
                                + " restarted as " + actualContextTokens
                                + " tokens under the normal startup memory policy.");
            }
            if (expectedContextTokens > 0 && "context_regime_mismatch".equals(universalResult.failureType())) {
                throw new BenchmarkContextUnavailableException(
                        "Requested MAX benchmark context " + expectedContextTokens
                                + " could not be held safely by the runtime: " + universalResult.detail());
            }
            String observedStatePrecisionRegime = KoilStatePrecisionEvidence.fromResult(universalResult);
            String expectedPrecision = KoilStatePrecisionEvidence.normalizeRegime(expectedStatePrecisionRegime);
            if (!observedStatePrecisionRegime.equals(expectedPrecision)) {
                throw new IllegalStateException("universal benchmark state precision mismatch: expected="
                        + expectedPrecision + ", actual=" + observedStatePrecisionRegime);
            }
            if (!universalResult.validMeasurement()) {
                throw new IllegalStateException("universal benchmark validation rejected candidate: placement="
                        + universalResult.actualPlacement() + ", GPU layers=" + universalResult.actualGpuLayers()
                        + ", integrity=" + universalResult.integrityPassed()
                        + (universalResult.failureType().isBlank() ? "" : ", failure=" + universalResult.failureType()));
            }
            ModelPerformanceBenchmarkResult benchmark = LlamaCppBenchmarkAdapter.nativeTimings(universalResult);
            measured.add(new MeasuredMaxCandidate(profile, benchmark, universalResult));
            LocalModelRuntimeLog.write(
                    "llama_max_tune_candidate",
                    "completed | " + profile.summary()
                            + " | prompt_tps=" + String.format(java.util.Locale.ROOT, "%.2f", benchmark.promptTokensPerSecond())
                            + " | generation_tps=" + String.format(java.util.Locale.ROOT, "%.2f", benchmark.generationTokensPerSecond())
                            + " | ttft_ms=" + String.format(java.util.Locale.ROOT, "%.2f", benchmark.timeToFirstTokenMillis())
                            + " | actual=" + universalResult.actualPlacement()
                            + "/" + universalResult.actualGpuLayers()
                            + " | headroom=" + String.format(java.util.Locale.ROOT, "%.2f",
                            com.spirit.koil.api.model.runtime.universal.KoilBenchmarkResourceEvidence.headroomScore(universalResult))
            );
            return true;
        } catch (Exception failure) {
            if (failure instanceof BenchmarkContextUnavailableException unavailable) throw unavailable;
            if (failure.getCause() instanceof BenchmarkContextUnavailableException unavailable) throw unavailable;
            failed[0]++;
            boolean candidateAlreadyRecorded = false;
            if (candidate != null) {
                String failedGeometry = candidate.geometryKey();
                for (KoilBenchmarkResult existing : benchmarkEvidence) {
                    if (existing.candidate().geometryKey().equals(failedGeometry)) {
                        candidateAlreadyRecorded = true;
                        break;
                    }
                }
            }
            if (candidate != null && !candidateAlreadyRecorded) {
                benchmarkEvidence.add(new KoilBenchmarkResult(
                        candidate, KoilBenchmarkResult.Outcome.FAILED,
                        0, 0, null, null, null, null, null, null, null, null, null,
                        "", null, com.spirit.koil.api.model.runtime.universal.KoilStatePlacement.AUTOMATIC,
                        com.spirit.koil.api.model.runtime.universal.KoilOperatorPlacement.AUTOMATIC,
                        false, com.spirit.koil.api.model.runtime.universal.KoilBenchmarkFailureClassifier.id(failure),
                        message(failure), java.time.Instant.now(), "", Map.of(), Map.of()));
            }
            LocalModelRuntimeLog.write(
                    "llama_max_tune_candidate_failed",
                    profile.summary() + " | " + message(failure)
            );
            return false;
        }
    }

    private static CompletableFuture<Void> configureLlamaCppBenchmarkCandidate(
            LlamaCppComputeSettings settings,
            int requestedContextTokens,
            String statePrecisionRegime
    ) {
        if (requestedContextTokens > 0) {
            LlamaCppBenchmarkContextOverride.set(requestedContextTokens);
        }
        LlamaCppBenchmarkStatePrecisionOverride.set(statePrecisionRegime);
        try {
            return configureLlamaCppCompute(settings).whenComplete((ignored, failure) -> {
                LlamaCppBenchmarkContextOverride.clear();
                LlamaCppBenchmarkStatePrecisionOverride.clear();
            });
        } catch (RuntimeException failure) {
            LlamaCppBenchmarkContextOverride.clear();
            LlamaCppBenchmarkStatePrecisionOverride.clear();
            throw failure;
        }
    }

    private static int discoverSafestBenchmarkContext(LlamaCppComputeSettings original) {
        int modelCeiling = selection == null ? 0 : selection.contextTokens();
        int probeTarget = LlamaCppSafeBenchmarkContextSelection.probeTargetForModelCeiling(modelCeiling);
        if (probeTarget <= 0) {
            throw new IllegalStateException("Unable to determine a safe MAX benchmark context because the selected model has no usable context ceiling.");
        }

        LocalModelRuntimeLog.write(
                "universal_benchmark_context_discovery",
                "probing model ceiling under normal startup memory policy | model_ceiling=" + modelCeiling
                        + " | probe_target=" + probeTarget);

        LlamaCppBenchmarkContextOverride.set(probeTarget);
        LlamaCppBenchmarkStatePrecisionOverride.clear();
        try {
            // This is a discovery restart only. Do not force a precision regime yet: the normal
            // launch policy must be free to choose the safest representation for the attempted
            // context. The exact context discovered here is locked for the real sweep below.
            LlamaCppComputeSettingsStore.save(original);
            configureLlamaCppCompute(original.withMode(LlamaCppComputeMode.MAX)).join();
            int actualContext = activeBenchmarkContextTokens();
            int discovered = LlamaCppSafeBenchmarkContextSelection.acceptDiscoveredContext(
                    modelCeiling, probeTarget, actualContext);
            LocalModelRuntimeLog.write(
                    "universal_benchmark_context_discovered",
                    "model_ceiling=" + modelCeiling
                            + " | probe_target=" + probeTarget
                            + " | actual=" + actualContext
                            + " | selected=" + discovered
                            + " | regime=" + KoilTuningKey.contextRegime(discovered)
                            + " | safety=normal_startup_memory_policy");
            llamaMaxTuningStatus = "discovered safe context " + discovered + " tokens";
            return discovered;
        } finally {
            LlamaCppBenchmarkContextOverride.clear();
            LlamaCppBenchmarkStatePrecisionOverride.clear();
        }
    }

    private static int normalizeBenchmarkContextTarget(int requestedContextTokens) {
        if (requestedContextTokens <= 0) return 0;
        int[] supported = {2048, 4096, 8192, 16384, 32768, 65536, 128000, 131072};
        for (int candidate : supported) {
            if (requestedContextTokens == candidate) return candidate;
        }
        throw new IllegalArgumentException(
                "MAX benchmark context must be one of 2048, 4096, 8192, 16384, 32768, 65536, 128000, or 131072 tokens.");
    }

    private static final class BenchmarkContextUnavailableException extends IllegalStateException {
        private BenchmarkContextUnavailableException(String message) {
            super(message);
        }
    }

    private static void addMaxCandidate(
            java.util.LinkedHashMap<String, LlamaCppMaxRuntimeProfile> profiles,
            LlamaCppMaxRuntimeProfile profile
    ) {
        profiles.putIfAbsent(maxProfileKey(profile), profile);
    }

    private static String maxProfileKey(LlamaCppMaxRuntimeProfile profile) {
        return profile.placement() + ":" + profile.gpuLayers()
                + ":" + profile.generationThreads() + ":" + profile.batchThreads()
                + ":" + profile.poll() + ":" + profile.pollBatch()
                + ":" + profile.batchSize() + ":" + profile.ubatchSize();
    }

    private static boolean sameMaxGeometry(LlamaCppMaxRuntimeProfile left, LlamaCppMaxRuntimeProfile right) {
        return left != null && right != null && maxProfileKey(left).equals(maxProfileKey(right));
    }

    private static List<LlamaCppMaxTuningResult.CandidateResult> scoreMaxCandidates(
            List<MeasuredMaxCandidate> measured,
            List<KoilBenchmarkHistoryStore.StoredSession> history,
            KoilBenchmarkWorkload workload
    ) {
        List<KoilCandidateScorer.ScoredResult> universalScores = KoilCandidateScorer.score(
                measured.stream().map(MeasuredMaxCandidate::universalResult).toList(), history, workload);
        Map<String, MeasuredMaxCandidate> byGeometry = new LinkedHashMap<>();
        for (MeasuredMaxCandidate candidate : measured) {
            byGeometry.put(candidate.universalResult().candidate().geometryKey(), candidate);
        }
        List<LlamaCppMaxTuningResult.CandidateResult> scored = new ArrayList<>();
        for (KoilCandidateScorer.ScoredResult score : universalScores) {
            MeasuredMaxCandidate measuredCandidate = byGeometry.get(score.result().candidate().geometryKey());
            if (measuredCandidate == null) continue;
            LocalModelRuntimeLog.write(
                    "universal_benchmark_candidate_score",
                    "candidate=" + score.result().candidate().label()
                            + " | workload=" + score.workloadKind().name().toLowerCase(java.util.Locale.ROOT)
                            + " | score=" + String.format(java.util.Locale.ROOT, "%.4f", score.score())
                            + " | performance=" + String.format(java.util.Locale.ROOT, "%.4f", score.freshPerformanceScore())
                            + " | headroom=" + String.format(java.util.Locale.ROOT, "%.3f", score.resourceHeadroomScore())
                            + " | resource_penalty=" + String.format(java.util.Locale.ROOT, "%.3f", score.resourcePenalty())
                            + " | history_weight=" + String.format(java.util.Locale.ROOT, "%.3f", score.historyWeight()));
            scored.add(new LlamaCppMaxTuningResult.CandidateResult(
                    measuredCandidate.profile(), measuredCandidate.benchmark(), score.score()));
        }
        return List.copyOf(scored);
    }

    private static KoilBenchmarkWorkload maxScoringWorkload(int contextTokens) {
        return new KoilBenchmarkWorkload(
                maxBenchmarkPrompt(contextTokens),
                96,
                Duration.ofMinutes(3),
                "max-autotune",
                KoilBenchmarkWorkload.Kind.INTERACTIVE);
    }

    private static int knownLlamaModelLayers() {
        LocalModelRuntimeManager activeRuntime = runtime;
        if (activeRuntime == null) return -1;
        try {
            String actual = activeRuntime.health().diagnostics().getOrDefault("actualGpuLayers", "");
            int slash = actual.indexOf('/');
            if (slash < 0) return -1;
            return Integer.parseInt(actual.substring(slash + 1).trim());
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static LlamaCppConfiguration currentLlamaCppConfiguration() {
        initialize();
        LocalModelSelection selected = selection;
        if (selected == null || !selected.complete() || !"llama_cpp".equals(selected.providerId())) {
            throw new IllegalStateException("no llama.cpp model is selected");
        }
        return LlamaCppConfiguration.fromSelection(selected, configuration.apiKey(), configuration.kvSlots());
    }

    private static LlamaCppDeviceProbe.ProbeResult probeCurrentLlamaRuntime(LlamaCppConfiguration configuration) {
        try {
            return LlamaCppDeviceProbe.probe(configuration.executable(), Duration.ofSeconds(30));
        } catch (Exception failure) {
            LocalModelRuntimeLog.write("llama_max_tune_probe_failed", message(failure));
            return new LlamaCppDeviceProbe.ProbeResult(
                    LlamaCppDeviceProbe.Status.INCONCLUSIVE, -1, List.of(), message(failure), false);
        }
    }

    private static String maxBenchmarkPrompt(int contextTokens) {
        int safeContext = contextTokens <= 0 ? 2048 : contextTokens;
        int targetCharacters = Math.max(700, Math.min(6000, safeContext * 2));
        String seed = "Koil local inference throughput benchmark. "
                + "Read the following deterministic technical notes and continue with concise numbered observations. "
                + "The benchmark measures prompt evaluation and token generation without tools, retrieval, or network work. "
                + "CPU and accelerator placement may differ between candidates, but the text workload must remain identical. ";
        StringBuilder prompt = new StringBuilder(targetCharacters + 128);
        while (prompt.length() < targetCharacters) {
            prompt.append(seed);
        }
        prompt.append("\nContinue the analysis with compact factual text:");
        return prompt.toString();
    }

    private record PreparedUniversalWinner(
            java.nio.file.Path storePath,
            KoilTuningKey key,
            KoilMeasuredTuningProfile profile,
            KoilBenchmarkResult benchmarkResult,
            double score,
            String adapterId
    ) {
    }

    private record MeasuredMaxCandidate(
            LlamaCppMaxRuntimeProfile profile,
            ModelPerformanceBenchmarkResult benchmark,
            KoilBenchmarkResult universalResult
    ) {
    }

    private static CompletableFuture<Void> configureLlamaCppComputeInternal(LlamaCppComputeSettings safe) {
        initialize();
        LlamaCppComputeSettings previous = LlamaCppComputeSettingsStore.load();
        LocalModelSelection selected = selection;
        LocalModelInstallationService installer = LocalModelInstallationService.instance();
        if (selected.complete()
                && "llama_cpp".equals(selected.providerId())
                && safe.mode() != LlamaCppComputeMode.CPU
                && installer.snapshot().state().active()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "A model installation operation is active; change compute mode after it finishes."));
        }

        LlamaCppComputeSettingsStore.save(safe);
        LocalModelRuntimeLog.write(
                "llama_compute",
                "mode=" + safe.mode().name().toLowerCase(java.util.Locale.ROOT)
                        + " | hybrid_gpu_layers=" + safe.hybridGpuLayers()
                        + " | device=" + safe.deviceLabel()
        );
        if (!selected.complete() || !"llama_cpp".equals(selected.providerId())) {
            return CompletableFuture.completedFuture(null);
        }

        if (safe.mode() == LlamaCppComputeMode.CPU) {
            return reloadConfiguration().whenComplete((ignored, failure) -> {
                if (failure != null) {
                    LlamaCppComputeSettingsStore.save(previous);
                }
            });
        }

        // A selected executable may still point at the older CPU-only managed
        // archive. Stop it before a verified Vulkan runtime is published so
        // Windows file locks cannot block replacement. Device discovery is a
        // diagnostic preflight only: the real model startup and its offload
        // telemetry are the final authority for GPU/hybrid activation.
        CompletableFuture<Void> result = new CompletableFuture<>();
        CompletableFuture.runAsync(LocalModelService::shutdown).whenComplete((stopped, stopFailure) -> {
            if (stopFailure != null) {
                restoreLlamaCppCompute(previous, result, stopFailure, false);
                return;
            }
            installer.ensureSelectedRuntimeForCompute(selected, safe).whenComplete((refreshed, updateFailure) -> {
                if (updateFailure != null) {
                    restoreLlamaCppCompute(previous, result, updateFailure, false);
                    return;
                }

                LlamaCppDeviceProbe.ProbeResult probe;
                try {
                    probe = LlamaCppDeviceProbe.probe(refreshed.runtimeExecutable());
                } catch (Exception probeFailure) {
                    // --list-devices is useful evidence, but a parser/CLI/version
                    // difference must not veto a runtime that can actually load
                    // the model and offload layers. Preserve the failure so it is
                    // available if real startup verification also fails.
                    probe = new LlamaCppDeviceProbe.ProbeResult(
                            LlamaCppDeviceProbe.Status.FAILED,
                            -1,
                            List.of(),
                            message(probeFailure),
                            false
                    );
                    LocalModelRuntimeLog.write("llama_devices", probe.summary());
                }

                if (!safe.device().isBlank()
                        && !probe.devices().isEmpty()
                        && probe.devices().stream().noneMatch(device -> device.id().equalsIgnoreCase(safe.device()))) {
                    IllegalStateException mismatch = new IllegalStateException(
                            "Configured llama.cpp device " + safe.device()
                                    + " is unavailable. Available devices: "
                                    + probe.devices().stream().map(LlamaCppDeviceProbe.Device::id)
                                    .collect(java.util.stream.Collectors.joining(", ")));
                    restoreLlamaCppCompute(previous, result, mismatch, false);
                    return;
                }

                final LlamaCppDeviceProbe.ProbeResult activationProbe = probe;

                // Another command such as /model status may have reinitialized the
                // previous CPU runtime while the Metal runtime was being built.
                // Force a complete configuration reload here so verification always
                // uses the freshly published runtime executable and persisted selection.
                reloadConfiguration().whenComplete((reloaded, reloadFailure) -> {
                    if (reloadFailure != null) {
                        restoreLlamaCppCompute(previous, result,
                                new IllegalStateException(
                                        "The accelerator runtime was prepared, but Koil could not reload the selected llama.cpp runtime: "
                                                + message(reloadFailure),
                                        reloadFailure),
                                true);
                        return;
                    }
                    LocalModelRuntimeManager candidateRuntime = runtime;
                    if (candidateRuntime == null) {
                        restoreLlamaCppCompute(previous, result,
                                new IllegalStateException("llama.cpp runtime manager did not initialize after accelerator setup"),
                                false);
                        return;
                    }
                    verifyLlamaCppComputeActivation(candidateRuntime, safe, previous, activationProbe, result);
                });
            });
        });
        return result;
    }

    private static void verifyLlamaCppComputeActivation(
            LocalModelRuntimeManager candidateRuntime,
            LlamaCppComputeSettings requested,
            LlamaCppComputeSettings previous,
            LlamaCppDeviceProbe.ProbeResult probe,
            CompletableFuture<Void> result
    ) {
        candidateRuntime.prepareSelectedProvider().whenComplete((prepared, startupFailure) -> {
            if (startupFailure != null) {
                restoreLlamaCppCompute(previous, result,
                        new IllegalStateException(
                                "llama.cpp could not start with " + requested.mode().displayName() + ". "
                                        + "Device probe: " + probe.summary()
                                        + ". Startup: " + message(startupFailure),
                                startupFailure),
                        true);
                return;
            }
            if (prepared == null || prepared.state() != ModelHealthState.READY) {
                String detail = prepared == null ? "no health snapshot" : prepared.detail();
                restoreLlamaCppCompute(previous, result,
                        new IllegalStateException(
                                "llama.cpp could not become ready with " + requested.mode().displayName() + ". "
                                        + "Device probe: " + probe.summary()
                                        + ". Runtime: " + detail),
                        true);
                return;
            }

            // Output capture runs independently from HTTP readiness. Give the
            // bounded telemetry parser a short window to observe llama.cpp's
            // authoritative `offloaded N/M layers to GPU` line before deciding.
            CompletableFuture.supplyAsync(() -> awaitLlamaCppGpuLayers(candidateRuntime, Duration.ofSeconds(10)))
                    .whenComplete((gpuLayers, telemetryFailure) -> {
                        if (telemetryFailure != null) {
                            restoreLlamaCppCompute(previous, result,
                                    new IllegalStateException(
                                            "llama.cpp became ready, but Koil could not verify GPU offload. "
                                                    + "Device probe: " + probe.summary()
                                                    + ". Telemetry: " + message(telemetryFailure),
                                            telemetryFailure),
                                    true);
                            return;
                        }
                        ModelHealthSnapshot current = candidateRuntime.health();
                        String maxExpectedPlacement = requested.mode() == LlamaCppComputeMode.MAX
                                ? current.diagnostics().getOrDefault("maxExpectedPlacement", "gpu")
                                : "";
                        boolean maxProfileTuned = requested.mode() == LlamaCppComputeMode.MAX
                                && Boolean.parseBoolean(current.diagnostics().getOrDefault("maxProfileTuned", "false"));
                        boolean maxFlexibleFallback = requested.mode() == LlamaCppComputeMode.MAX && !maxProfileTuned;
                        boolean maxCpuProfile = requested.mode() == LlamaCppComputeMode.MAX
                                && "cpu".equalsIgnoreCase(maxExpectedPlacement);
                        if ((gpuLayers == null || gpuLayers <= 0) && !maxCpuProfile && !maxFlexibleFallback) {
                            String actual = current.diagnostics().getOrDefault("actualGpuLayers", "pending");
                            if (current.state() == ModelHealthState.FAILED) {
                                String nativeTail = current.diagnostics().getOrDefault("lastRuntimeOutput", "").strip();
                                String runtimeFailure = current.detail();
                                if (!nativeTail.isBlank() && !runtimeFailure.contains(nativeTail)) {
                                    runtimeFailure += " | native tail: " + nativeTail;
                                }
                                restoreLlamaCppCompute(previous, result,
                                        new IllegalStateException(
                                                "llama.cpp aborted before GPU offload verification completed for "
                                                        + requested.mode().displayName() + " (actualGpuLayers=" + actual + "). "
                                                        + "Runtime: " + runtimeFailure + ". Device probe: " + probe.summary()),
                                        true);
                                return;
                            }
                            String nativeTail = current.diagnostics().getOrDefault("lastRuntimeOutput", "").strip();
                            String resolvedDevice = current.diagnostics().getOrDefault("resolvedComputeDevice", "none");
                            restoreLlamaCppCompute(previous, result,
                                    new IllegalStateException(
                                            "llama.cpp started, but did not report any GPU-offloaded model layers "
                                                    + "for " + requested.mode().displayName() + " (actualGpuLayers=" + actual
                                                    + ", resolvedDevice=" + resolvedDevice + "). "
                                                    + (nativeTail.isBlank() ? "" : "Runtime tail: " + boundedDiagnostic(nativeTail, 2200) + ". ")
                                                    + "Koil restored the previous compute mode. Device probe: " + probe.summary()),
                                    true);
                            return;
                        }
                        String placement = current.diagnostics().getOrDefault("actualComputePlacement", "unknown");
                        String actualLayers = current.diagnostics().getOrDefault("actualGpuLayers",
                                gpuLayers == null ? "pending" : Integer.toString(gpuLayers));
                        if (requested.mode() == LlamaCppComputeMode.HYBRID && !"hybrid".equalsIgnoreCase(placement)) {
                            restoreLlamaCppCompute(previous, result,
                                    new IllegalStateException(
                                            "llama.cpp offloaded model layers, but the requested hybrid split did not leave model layers on both CPU and GPU "
                                                    + "(runtime placement=" + placement + ", GPU layers=" + actualLayers + "). "
                                                    + "Choose a smaller hybrid GPU-layer count. Koil restored the previous compute mode."),
                                    true);
                            return;
                        }
                        if (requested.mode() == LlamaCppComputeMode.HYBRID
                                && gpuLayers != null
                                && gpuLayers != requested.hybridGpuLayers()) {
                            restoreLlamaCppCompute(previous, result,
                                    new IllegalStateException(
                                            "llama.cpp did not honor the exact hybrid layer split: requested="
                                                    + requested.hybridGpuLayers() + ", actual=" + gpuLayers + ". "
                                                    + "Koil rejected the mismatched placement and restored the previous compute mode."),
                                    true);
                            return;
                        }
                        if (requested.mode() == LlamaCppComputeMode.MAX
                                && maxProfileTuned
                                && "hybrid".equalsIgnoreCase(maxExpectedPlacement)) {
                            int expectedMaxLayers = parseGpuLayerCount(
                                    current.diagnostics().getOrDefault("maxExpectedGpuLayers", "-1"));
                            if (expectedMaxLayers > 0 && gpuLayers != null && gpuLayers != expectedMaxLayers) {
                                restoreLlamaCppCompute(previous, result,
                                        new IllegalStateException(
                                                "MAX hybrid candidate layer mismatch: expected=" + expectedMaxLayers
                                                        + ", actual=" + gpuLayers + ". Koil will not benchmark a different placement."),
                                        true);
                                return;
                            }
                        }
                        if (requested.mode() == LlamaCppComputeMode.MAX && maxProfileTuned) {
                            LlamaCppMaxRuntimeProfile activeMaxProfile = LlamaCppMaxTuningStore.activeCandidate().orElse(null);
                            if (activeMaxProfile == null) {
                                String currentStatePrecisionRegime = KoilStatePrecisionEvidence.regime(
                                        current.diagnostics().getOrDefault("statePrecisionK", "fp16"),
                                        current.diagnostics().getOrDefault("statePrecisionV", "fp16"));
                                activeMaxProfile = LlamaCppMaxTuningStore.bestForConfiguration(
                                                currentLlamaCppConfiguration(), requested.device(),
                                                KoilTuningKey.contextRegime(activeBenchmarkContextTokens()),
                                                currentStatePrecisionRegime)
                                        .map(LlamaCppMaxTuningStore.TunedProfile::profile)
                                        .orElse(null);
                            }
                            if (activeMaxProfile != null
                                    && !activeMaxProfile.acceptsRuntimePlacement(placement, gpuLayers)) {
                                restoreLlamaCppCompute(previous, result,
                                        new IllegalStateException(
                                                "Max performance candidate expected " + activeMaxProfile.summary()
                                                        + ", but llama.cpp reported runtime placement=" + placement
                                                        + ", GPU layers=" + actualLayers + ". "
                                                        + "Koil rejected the mismatched candidate instead of benchmarking the wrong configuration."),
                                        true);
                                return;
                            }
                        }
                        int verifiedHybridLayers = -1;
                        if (requested.mode() == LlamaCppComputeMode.HYBRID) {
                            verifiedHybridLayers = requested.hybridGpuLayers();
                        } else if (requested.mode() == LlamaCppComputeMode.MAX
                                && maxProfileTuned
                                && "hybrid".equalsIgnoreCase(maxExpectedPlacement)) {
                            verifiedHybridLayers = parseGpuLayerCount(
                                    current.diagnostics().getOrDefault("maxExpectedGpuLayers", "-1"));
                        }
                        if (verifiedHybridLayers > 0) {
                            try {
                                LlamaCppConfiguration verifiedConfiguration = currentLlamaCppConfiguration();
                                String verifiedDevice = current.diagnostics()
                                        .getOrDefault("resolvedComputeDevice", requested.device());
                                LlamaCppComputeStabilityStore.recordStable(
                                        verifiedConfiguration.modelFile(),
                                        verifiedDevice,
                                        verifiedHybridLayers,
                                        LlamaCppComputeStabilityStore.currentAvailableMemoryBytes(),
                                        "runtime ready, exact placement verified, integrity canary passed");
                            } catch (Exception stabilityFailure) {
                                LocalModelRuntimeLog.write(
                                        "llama_compute_stability_record_failed",
                                        message(stabilityFailure));
                            }
                        }
                        LocalModelRuntimeLog.write(
                                "llama_compute_verified",
                                "requested=" + requested.mode().name().toLowerCase(java.util.Locale.ROOT)
                                        + " | actual_gpu_layers=" + actualLayers
                                        + " | placement=" + placement
                                        + " | probe=" + probe.status().name().toLowerCase(java.util.Locale.ROOT)
                        );
                        result.complete(null);
                    });
        });
    }

    private static int awaitLlamaCppGpuLayers(LocalModelRuntimeManager candidateRuntime, Duration timeout) {
        long waitNanos = timeout == null ? Duration.ofSeconds(3).toNanos() : Math.max(0L, timeout.toNanos());
        long deadline = System.nanoTime() + waitNanos;
        int parsed = parseGpuLayerCount(candidateRuntime.health().diagnostics().get("actualGpuLayers"));
        while (parsed < 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("GPU offload verification was interrupted", interrupted);
            }
            parsed = parseGpuLayerCount(candidateRuntime.health().diagnostics().get("actualGpuLayers"));
        }
        return parsed;
    }

    private static int parseGpuLayerCount(String value) {
        if (value == null || value.isBlank() || "pending".equalsIgnoreCase(value.trim())) return -1;
        Matcher matcher = Pattern.compile("^\\s*(\\d+)").matcher(value);
        if (!matcher.find()) return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String boundedDiagnostic(String value, int maxChars) {
        String text = value == null ? "" : value.strip();
        int limit = Math.max(256, maxChars);
        if (text.length() <= limit) return text;
        return "..." + text.substring(text.length() - limit + 3);
    }

    private static void restoreLlamaCppCompute(
            LlamaCppComputeSettings previous,
            CompletableFuture<Void> result,
            Throwable failure,
            boolean stopCandidateRuntime
    ) {
        CompletableFuture.runAsync(() -> {
            if (stopCandidateRuntime) {
                shutdown();
            }
            LlamaCppComputeSettingsStore.save(previous);
            initialize();
        }).whenComplete((ignored, rollbackFailure) -> {
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure);
            }
            result.completeExceptionally(failure);
        });
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
        boolean toolsEnabled = selectedToolGateEnabled(entry, runtime == null ? null : runtime.selectedCapabilities());
        return LocalModelAutomationEligibility.evaluate(entry, toolsEnabled);
    }

    private static boolean selectedToolGateEnabled(
            LocalModelCatalogEntry entry,
            ModelCapabilityDescriptor provider
    ) {
        return LocalModelToolCapabilityResolver.supportsTools(selection, entry, provider);
    }

    private static ModelRuntimeCompatibility selectedRuntimeCompatibility(LocalModelSelection selected) {
        if (selected == null) return null;
        return LocalModelCatalog.find(selected.catalogId()).stream()
                .flatMap(entry -> entry.runtimeCompatibility().stream())
                .filter(value -> value.runtimeId().equals(selected.runtimeId()))
                .filter(value -> selected.architectureId().isBlank()
                        || value.architectureId().equals(selected.architectureId()))
                .findFirst().orElse(null);
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
        ModelAssociativeMemory.close();
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

    private static List<ModelStartupWarmupBranch> startupWarmupBranches(LlamaCppConfiguration llamaConfiguration) {
        // Startup readiness must remain bounded. The exact direct /ask branch is
        // the highest-value first-turn seed and already contains Koil's persisted
        // identity plus the compact truthfulness/language/format contract. Do not
        // serially prefill normal, automation, or tool-heavy branches here: those
        // can cost minutes on constrained hardware and are not required to answer
        // the user's first ordinary factual question.
        String direct = LocalModelSystemPrompt.directConversationPrompt();
        String directStable = ModelPromptCacheIdentity.stablePrefix("ask", direct, List.of(), "");
        return List.of(new ModelStartupWarmupBranch(
            "ask-direct", "ask", direct, List.of(), "", 0, false, true, directStable));
    }

    private static String systemPrompt(
        RequestMode mode,
        boolean toolsAvailable,
        AutomationThinkingPolicy.Decision thinking
    ) {
        String languageContract = """
                Reply only in the language used by the latest user message. For English input, use English only; never switch languages unless explicitly asked.
                """;
        String capabilityDiscoveryContract = toolsAvailable ? """
                Capability discovery: the schemas initially supplied are a relevance-biased starting set, not necessarily Koil's complete capability inventory. When the objective is not clearly covered, when several approaches may exist, or before concluding that Koil lacks a capability, use the supplied tool/Skill discovery capabilities to search the live registries. Inspect promising candidates when needed, then use the newly exposed authoritative schema on a later round. Do not search reflexively when the current supplied capability already clearly solves the objective. Discovery never bypasses approval, preconditions, side-effect policy, or executor authority.
                """ : "";
        if (mode == RequestMode.ASK || mode == RequestMode.ASK_DEEP) {
            String askBoundary = toolsAvailable
                ? """
                    /ask is conversational, and every tool schema supplied for this turn is explicitly permitted for evidence gathering. Use those supplied tools when current, external, user-specific, workspace, Minecraft-state, Koil, or code evidence would materially improve the answer; do not refuse a supplied safe tool merely because the request came through /ask. The /ask hard boundary is capability-based: never create, edit, delete, move, rename, or otherwise manage files/workspaces; never execute Minecraft commands, gameplay actions, raw input, code/KTL, or shell/process commands; and never start, cancel, reconfigure, or control Automation. Read-only workspace/file inspection is allowed when its schema is supplied. Use the smallest relevant lookup, preserve exact identifiers/evidence, and never substitute a nearby valid target for a requested target that does not exist.
                    """
                : """
                    /ask is conversational. No tool schemas are supplied for this turn, so answer from learned knowledge and supplied context without claiming an external action or lookup. This turn being tool-free does not mean /ask globally forbids tools; later /ask turns may receive explicitly permitted safe tool schemas. Command links are suggestions only and are never execution.
                    """;
            return LocalModelSystemPrompt.load() + askBoundary + """
                    When asked for an enchanted-item command, use Minecraft 1.20.1 item SNBT after the item id, for example `[Give Knockback 5 Stick](/give @s minecraft:stick{Enchantments:[{id:"minecraft:knockback",lvl:5s}]} 1)`. Never invent `/forge`.
                    """ + RichChatModelFormattingContract.askPrompt() + capabilityDiscoveryContract + languageContract;
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
            + capabilityDiscoveryContract
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
        return LocalModelToolCatalog.readOnlyInformationTools();
    }

    private static List<ModelToolDefinition> readOnlyAskTools(
        String prompt,
        ConversationalReasoningPolicy.Decision decision
    ) {
        if (decision == null) return List.of();
        List<ModelToolDefinition> selected = LocalModelToolCatalog.informationToolsForPrompt(prompt);
        if (!selected.isEmpty()) return withCapabilityDiscovery(selected);
        List<ModelToolDefinition> minecraft = MinecraftKnowledgeModelToolRegistry.toolsForQuestion(prompt);
        return minecraft.isEmpty() ? List.of() : withCapabilityDiscovery(minecraft);
    }

    private static List<ModelToolDefinition> withCapabilityDiscovery(List<ModelToolDefinition> base) {
        LinkedHashMap<String, ModelToolDefinition> merged = new LinkedHashMap<>();
        if (base != null) for (ModelToolDefinition tool : base) merged.putIfAbsent(tool.id(), tool);
        for (ModelToolDefinition tool : ToolDiscoveryModelToolRegistry.modelTools()) merged.putIfAbsent(tool.id(), tool);
        AgentSkillModelToolRegistry.modelTools().stream()
                .filter(tool -> AgentSkillModelToolRegistry.SEARCH.equals(tool.id())
                        || AgentSkillModelToolRegistry.INSPECT.equals(tool.id()))
                .forEach(tool -> merged.putIfAbsent(tool.id(), tool));
        return List.copyOf(merged.values());
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

    private static TelemetrySpanKind telemetrySpanKind(ModelRequestState state) {
        if (state == null) return TelemetrySpanKind.OTHER;
        return switch (state) {
            case WAITING_FOR_RUNTIME -> TelemetrySpanKind.MODEL_STARTUP;
            case QUEUED -> TelemetrySpanKind.QUEUE_WAIT;
            case PREPARING_CONTEXT -> TelemetrySpanKind.PROMPT_INGESTION;
            case PREFILLING -> TelemetrySpanKind.PREFILL;
            case PLANNING, VALIDATING_PLAN, REPLANNING -> TelemetrySpanKind.PLANNING;
            case SELECTING_TOOL, EXECUTING_TOOL, WAITING_FOR_TOOL_RESULT, OBSERVING_RESULT -> TelemetrySpanKind.TOOL_DISPATCH;
            case WAITING_FOR_ACTION_APPROVAL, WAITING_FOR_PLAN_APPROVAL -> TelemetrySpanKind.APPROVAL;
            case VALIDATING -> TelemetrySpanKind.VALIDATION;
            case FINALIZING -> TelemetrySpanKind.OUTPUT;
            case GENERATING, THINKING -> TelemetrySpanKind.TOKEN_GENERATION;
            default -> TelemetrySpanKind.MODEL_CONTINUATION;
        };
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

    private record SkillExecutionMatch(String skillId, String path) {
        private SkillExecutionMatch {
            skillId = skillId == null ? "" : skillId;
            path = path == null ? "" : path;
        }
    }

    private static final class GenerationSession implements ModelFinalizationHandle, ModelDeepThoughtControl {
        private static final int MAXIMUM_IDENTICAL_CALLS = 2;
        private static final int MAXIMUM_IDENTICAL_RESPONSES = 2;
        private static final long FIRST_ROUND_PREFILL_BUDGET_MILLIS = 650L;

        private final UUID displayId;
        private volatile String prompt;
        private final RequestMode mode;
        private final ModelConversation conversation;
        private volatile List<ModelToolDefinition> tools;
        private volatile String proceduralGuidance = "";
        private volatile String prefetchedExpertContext = "";
        private volatile String prefetchedMemoryContext = "";
        private volatile SemanticReasoningMemoStore.Lookup prefetchedReasoningMemos = SemanticReasoningMemoStore.Lookup.empty();
        private final AutomationThinkingPolicy.Decision thinking;
        private final ConversationalReasoningPolicy.Decision conversationalThinking;
        private final ModelAgentCapabilityProfile capabilityProfile;
        private final AgentReasoningController.Decision agentReasoningDecision;
        private final AgentReasoningState agentReasoningState;
        private final AgentState agentState;
        private final DeepThoughtInvestigationController deepThought;
        private final boolean forcedPlanning;
        private final long startedAtMillis = System.currentTimeMillis();
        private final Map<String, Integer> repeatedCalls = new LinkedHashMap<>();
        private final Map<String, Integer> argumentBindingFailures = new LinkedHashMap<>();
        private final Map<String, Integer> repeatedResponses = new LinkedHashMap<>();
        private final Set<String> completedToolIds = new LinkedHashSet<>();
        /** Tool ids found through tool.search and eligible for schema expansion on later rounds. */
        private final Set<String> discoveredToolIds = ConcurrentHashMap.newKeySet();
        private final Set<String> requiredToolIds;
        private final ModelDurableTaskState durableState;
        private final ModelObjectiveLedger objectiveLedger;
        private final ModelExecutionContext executionContext;
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
        private int reasoningContinuationCount;
        private int reasoningConvergenceCorrectionCount;
        private String lastReasoningFingerprint = "";
        private int repeatedReasoningCount;
        private int askFormattingCorrectionCount;
        private int providerRoundCount;
        private int emptyResponseCorrections;
        private final List<ModelMessage> internalReasoningTurns = new ArrayList<>();
        /** Latest non-final model-authored prose retained only for continuation context. */
        private volatile String previousAssistantDraft = "";
        private volatile String pendingControlGuidance = "";
        private int groundedAskToolRounds;
        private int groundedAskCorrectionCount;
        private boolean groundedAskFinalizing;
        private int finalFormattingCorrectionCount;
        private int directIntentCorrectionCount;
        private int toolProtocolCorrectionCount;
        private boolean formattingCorrectionActive;
        private boolean groundedAskCommandAttempted;
        private boolean finalToolSummaryRequested;
        private ValidatedAutomationPlan validatedPlan;
        private ReviewedPlanAuthorization planAuthorization;
        private PlanPhase planPhase = PlanPhase.NONE;
        private int planRevisionCount;
        private int planSegmentCount;
        private boolean toolBudgetAdvisoryEmitted;
        private String lastReplanEvidenceFingerprint = "";
        private String automationPlanTelemetrySpanId = "";
        private String outputTelemetrySpanId = "";
        private boolean directToolDecisionSession;
        private boolean directInformationDecisionRound;
        private boolean forceNoNativeReasoningNextRound;
        private volatile long activeProviderRoundStartedAtMillis;
        private volatile long firstProviderTextAtMillis;
        private volatile String activeProviderTelemetrySpanId = "";
        private final Map<String, String> toolTelemetrySpans = new ConcurrentHashMap<>();
        private final Map<String, SkillExecutionMatch> skillExecutions = new ConcurrentHashMap<>();
        private final Map<String, SemanticReasoningMemoStore.Candidate> reasoningMemoCandidates = new ConcurrentHashMap<>();
        private volatile List<KoilSkillSelection> activeSkillSelections = List.of();

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
            int currentConversationCharacters = conversation.snapshotWithin(
                    conversationMessageBudget(mode),
                    conversationCharacterBudget(mode)
                ).stream()
                .mapToInt(message -> message.content().length())
                .sum();
            this.conversationalThinking = mode == RequestMode.AUTOMATION
                ? null
                : ConversationalReasoningPolicy.evaluate(
                prompt,
                currentConversationCharacters,
                this.capabilityProfile,
                mode == RequestMode.ASK_DEEP
            );
            this.tools = !this.capabilityProfile.canAutomate()
                ? List.of()
                : mode == RequestMode.AUTOMATION
                ? LocalModelToolCatalog.toolsForPrompt(prompt, this.thinking.includePlanTool())
                : mode == RequestMode.ASK_DEEP
                ? readOnlyDeepThoughtTools(prompt)
                : readOnlyAskTools(prompt, this.conversationalThinking);
            AgentReasoningController.Decision preliminaryReasoning = AgentReasoningController.evaluate(
                prompt,
                mode == RequestMode.AUTOMATION,
                mode == RequestMode.ASK_DEEP || mode == RequestMode.AUTOMATION && this.thinking.deepActive(),
                currentConversationCharacters,
                this.capabilityProfile,
                this.conversationalThinking,
                this.thinking,
                !this.tools.isEmpty(),
                this.tools.stream().anyMatch(tool -> tool.id().startsWith("minecraft."))
            );
            if (mode == RequestMode.ASK
                && this.capabilityProfile.canAutomate()
                && this.tools.isEmpty()
                && preliminaryReasoning.targets().contains(AgentReasoningController.Target.IDENTIFIER_RESOLUTION)) {
                this.tools = MinecraftKnowledgeModelToolRegistry.toolsForQuestion(prompt);
            }
            this.agentReasoningDecision = AgentReasoningController.evaluate(
                prompt,
                mode == RequestMode.AUTOMATION,
                mode == RequestMode.ASK_DEEP || mode == RequestMode.AUTOMATION && this.thinking.deepActive(),
                currentConversationCharacters,
                this.capabilityProfile,
                this.conversationalThinking,
                this.thinking,
                !this.tools.isEmpty(),
                this.tools.stream().anyMatch(tool -> tool.id().startsWith("minecraft."))
            );
            this.requiredToolIds = mode == RequestMode.AUTOMATION
                ? LocalModelToolCatalog.requiredToolIdsForPrompt(prompt)
                : Set.of();
            this.objectiveLedger = mode == RequestMode.AUTOMATION
                ? ModelObjectiveLedger.parse(prompt)
                : ModelObjectiveLedger.parse("");
            this.durableState = new ModelDurableTaskState(prompt, this.objectiveLedger.snapshot());
            this.agentState = new AgentState(
                displayId,
                prompt,
                this.agentReasoningDecision,
                this.objectiveLedger.snapshot()
            );
            this.agentReasoningState = new AgentReasoningState(this.agentReasoningDecision, this.agentState);
            this.executionContext = mode == RequestMode.AUTOMATION
                ? new ModelExecutionContext(displayId, prompt, this.agentState)
                : null;
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
        }

        private void submitGeneration() {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.isOnThread()) {
                CompletableFuture.runAsync(this::submitGenerationOffClient);
                return;
            }
            submitGenerationOffClient();
        }

        private void submitGenerationOffClient() {
            if (this.cancellation.isCancellationRequested()) {
                fail("cancelled", this.cancellation.cancellationReason(), null);
                return;
            }
            // Semantic memos are a tiny local index. Prime them before the first
            // provider round so an already-verified route can bypass repeated
            // model reasoning entirely when the current clause is an extremely
            // close match. Live arguments are still re-grounded when stale.
            if (this.providerRoundCount == 0) primeReasoningMemoHints();

            // Do not pay for a provider prefill just to rediscover an action that
            // the ordered objective ledger already resolved from the user's own
            // request. This is especially important on small/slow local models:
            // a multi-step request must be able to begin task 1 even if a model
            // prefill would take minutes. Normal approval, permission, argument,
            // verification and progress guards still apply through handleToolCalls.
            if (this.providerRoundCount == 0
                    && tryExecuteDeterministicOrderedTaskFrontier("initial ordered task frontier")) {
                return;
            }
            int nextProviderRound = this.providerRoundCount + 1;
            if (nextProviderRound > 1) {
                archiveVisibleProviderRound(nextProviderRound - 1);
            }
            resolveFirstRoundPrefillInputs();
            this.providerRoundCount++;
            int recommendedRounds = this.mode == RequestMode.AUTOMATION
                ? Math.max(1, this.thinking.maximumProviderRounds())
                : this.conversationalThinking.maximumProviderRounds();
            if (this.formattingCorrectionActive) recommendedRounds++;
            if (this.providerRoundCount == recommendedRounds + 1 && !this.finalizationRequested.get()) {
                com.google.gson.JsonObject signal = new com.google.gson.JsonObject();
                signal.addProperty("providerPass", this.providerRoundCount);
                signal.addProperty("recommendedPasses", recommendedRounds);
                signal.addProperty("enforced", false);
                ModelGenerationHudState.upsertEvent(
                    this.displayId,
                    ModelGenerationHudState.ActivityEventType.MODEL_DATA,
                    "reasoning-budget-" + this.displayId,
                    "Model passed the recommended reasoning depth; Koil is keeping generation active while progress continues.",
                    signal
                );
            }
            UUID providerRequestId = UUID.randomUUID();
            this.activeProviderRoundId.set(providerRequestId);
            String providerTelemetrySpanId = TelemetryStore.beginSpan(
                    this.displayId, TelemetryStore.rootSpan(this.displayId),
                    TelemetrySpanKind.MODEL_PASS, "model pass " + this.providerRoundCount,
                    Map.of("provider_request_id", providerRequestId.toString(),
                            "mode", this.mode.name().toLowerCase(java.util.Locale.ROOT),
                            "round", Integer.toString(this.providerRoundCount))
            );
            this.activeProviderTelemetrySpanId = providerTelemetrySpanId;
            TelemetryStore.capability(this.displayId, "model", "provider_round", TelemetryCapabilityState.ACTIVE,
                    "running", "Provider round " + this.providerRoundCount + " is active.");
            String promptAssemblySpanId = TelemetryStore.beginSpan(
                    this.displayId, providerTelemetrySpanId, TelemetrySpanKind.PROMPT_INGESTION, "prompt assembly");
            boolean directVerifiedResult = !this.formattingCorrectionActive
                && AutomationToolCallLatencyPolicy.useDirectVerifiedResultRound(
                this.directToolDecisionSession,
                this.toolResultsReceived,
                this.successfulActionToolOutputs,
                this.objectiveLedger.allCompleted(),
                this.validatedPlan != null
            );
            boolean completedAutomationFinalization = this.mode == RequestMode.AUTOMATION
                    && this.finalToolSummaryRequested
                    && this.objectiveLedger.allCompleted();
            List<ModelToolDefinition> requestTools = this.formattingCorrectionActive || directVerifiedResult
                    || completedAutomationFinalization
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
            requestTools = expandDiscoveredTools(requestTools);
            if (this.mode == RequestMode.AUTOMATION && !requestTools.isEmpty()
                    && !this.prefetchedReasoningMemos.suggestedToolIds().isEmpty()) {
                LinkedHashMap<String, ModelToolDefinition> memoAugmented = new LinkedHashMap<>();
                for (ModelToolDefinition definition : requestTools) memoAugmented.put(definition.id(), definition);
                for (String toolId : this.prefetchedReasoningMemos.suggestedToolIds()) {
                    LocalModelToolCatalog.definition(toolId).ifPresent(definition -> memoAugmented.putIfAbsent(toolId, definition));
                }
                requestTools = List.copyOf(memoAugmented.values());
            }
            // Tool/deep-thought rounds are internal work. Speak only direct chat
            // while streaming; completed tool/deep-thought answers use complete().
            boolean streamUserFacingText = requestTools.isEmpty() && !this.formattingCorrectionActive;
            boolean streamUserFacingVoice = streamUserFacingText && (
                this.mode == RequestMode.ASK
                    || this.mode == RequestMode.ASK_DEEP && (this.finalizationRequested.get()
                    || this.deepThought != null
                    && this.deepThought.session().phase == DeepThoughtSession.Phase.FINALIZE)
            );
            ModelVoiceService.StreamingSpeech streamingSpeech = streamUserFacingVoice
                && ModelVoiceService.settings().enabled()
                ? ModelVoiceService.beginPresentedStreaming(this.displayId)
                : null;
            Set<String> remainingRequired = remainingRequiredToolIds();
            AgentToolRoutingPolicy.Focus routingFocus = AgentToolRoutingPolicy.focus(
                this.agentState, requestTools, remainingRequired);
            requestTools = AgentToolRoutingPolicy.prioritize(this.agentState, requestTools, remainingRequired);
            if (this.mode == RequestMode.AUTOMATION && this.capabilityProfile.stagedExecution()) {
                LinkedHashSet<String> stagedPriority = new LinkedHashSet<>(routingFocus.toolIds());
                stagedPriority.addAll(remainingRequired);
                requestTools = limitToolsForProfile(requestTools, Set.copyOf(stagedPriority),
                    this.capabilityProfile.maximumRecommendedToolsPerRound());
                requestTools = AgentToolRoutingPolicy.prioritize(this.agentState, requestTools, remainingRequired);
            }
            // For an explicit ordered Automation objective, do not expose future
            // side-effecting actions in the same provider round. The durable
            // ledger already knows those future tasks and will expose them when
            // they become current. Keeping only the current consequential
            // capability plus read-only helpers both preserves ordering and
            // sharply reduces llama.cpp chat-template/prefill size on small
            // local models.
            if (this.mode == RequestMode.AUTOMATION
                    && this.objectiveLedger.totalTaskCount() > 1
                    && !remainingRequired.isEmpty()
                    && this.validatedPlan == null
                    && !this.forcedPlanning) {
                requestTools = requestTools.stream()
                        .filter(tool -> remainingRequired.contains(tool.id())
                                || (!tool.confirmationRequired() && tool.sideEffects().isEmpty()))
                        .toList();
                requestTools = AgentToolRoutingPolicy.prioritize(this.agentState, requestTools, remainingRequired);
            }

            // Search results are an explicit model request to widen its capability
            // view. Re-add discovered schemas after normal prompt/staging filters so
            // relevance optimization cannot make a discovered capability unusable.
            requestTools = expandDiscoveredTools(requestTools);

            if (this.mode == RequestMode.ASK
                && this.toolResultsReceived == 0
                && requestTools.stream().anyMatch(tool -> InternetResearchModelToolRegistry.SEARCH.equals(tool.id()))
                && requestTools.stream().anyMatch(tool -> InternetResearchModelToolRegistry.FETCH.equals(tool.id()))) {
                requestTools = requestTools.stream()
                    .filter(tool -> !InternetResearchModelToolRegistry.FETCH.equals(tool.id()))
                    .toList();
            }
            AutomationToolCallLatencyPolicy.Assessment latencyAssessment = this.mode == RequestMode.AUTOMATION
                ? AutomationToolCallLatencyPolicy.assess(
                this.prompt,
                this.thinking,
                remainingRequired,
                requestTools,
                AutomationModeController.isPlanningModeEnabled(),
                this.providerRoundCount == 1 && !this.finalizationRequested.get()
            )
                : null;
            AutomationToolCallLatencyPolicy.Decision latencyDecision = latencyAssessment == null
                ? null : latencyAssessment.decision();
            boolean directToolDecision = latencyDecision != null && latencyDecision.directToolDecision();
            if (this.mode == RequestMode.AUTOMATION && this.executionContext != null
                && !this.finalizationRequested.get()) {
                this.executionContext.speculateCandidates(
                    this.displayId,
                    this.prompt,
                    requestTools,
                    remainingRequired
                );
            }
            if (directToolDecision && this.executionContext != null && latencyAssessment != null
                && latencyAssessment.toolShape().requiredArguments() == 0
                && LocalModelToolCatalog.speculativeReadAllowed(latencyAssessment.requiredToolId())) {
                this.executionContext.speculate(
                    this.displayId,
                    new ModelToolCall(
                        "speculative-" + providerRequestId,
                        latencyAssessment.requiredToolId(),
                        new com.google.gson.JsonObject()
                    )
                );
            }
            if (directToolDecision) this.directToolDecisionSession = true;
            InformationToolCallLatencyPolicy.Decision informationLatencyDecision = this.mode == RequestMode.ASK
                ? InformationToolCallLatencyPolicy.evaluate(
                selectedModelParametersBillions(),
                this.conversationalThinking,
                requestTools,
                this.providerRoundCount == 1 && !this.finalizationRequested.get()
            )
                : this.mode == RequestMode.AUTOMATION
                ? InformationToolCallLatencyPolicy.evaluateAutomation(
                selectedModelParametersBillions(),
                this.thinking,
                requestTools,
                this.providerRoundCount == 1 && !this.finalizationRequested.get()
            )
                : null;
            boolean directInformationDecision = informationLatencyDecision != null
                && informationLatencyDecision.directToolDecision();
            boolean directConversationDecision = this.mode == RequestMode.ASK
                && requestTools.isEmpty()
                && this.conversationalThinking.depth() == ConversationalReasoningPolicy.Depth.DIRECT
                && !directInformationDecision;
            if (this.mode == RequestMode.AUTOMATION && directInformationDecision) {
                requestTools = requestTools.stream()
                    .filter(tool -> !tool.confirmationRequired() && tool.sideEffects().isEmpty())
                    .toList();
            }
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
                : directConversationDecision
                ? LocalModelSystemPrompt.directConversationPrompt()
                : systemPrompt(this.mode, !requestTools.isEmpty(), this.thinking);
            // /ask receives selected tools through the provider's structured tool
            // parameter, which the chat template encodes during prefill. Do not
            // duplicate those schemas with a second retrieval essay in the system
            // prompt. Deeper/Automation modes retain the procedural retrieval
            // contract because they may coordinate several evidence steps.
            String informationPolicy = this.mode == RequestMode.ASK
                    ? ""
                    : ModelInformationRetrievalPolicy.promptFor(requestTools);
            if (!informationPolicy.isBlank()) {
                promptContract += "\n\n" + informationPolicy;
            }
            if (!this.proceduralGuidance.isBlank()) {
                promptContract += "\n\nKoil procedural guidance (does not grant tools or permissions):\n" + this.proceduralGuidance;
            }
            String agentReasoningDirective = AgentReasoningController.promptDirective(this.agentReasoningDecision);
            if (!agentReasoningDirective.isBlank()) {
                promptContract += "\n\n" + agentReasoningDirective;
            }
            // Everything above this boundary is deliberately stable enough to
            // serve as Koil's reusable model/tool/expert prefix. Live world
            // state, task progress, retrieved memory, and finalization state
            // stay below it so they cannot invalidate the expensive prefix.
            String stablePromptContract = promptContract;
            String controlGuidance = consumeControlGuidance();
            if (!controlGuidance.isBlank()) {
                promptContract += "\n\nKoil continuation guidance (system-authored, not a user message):\n" + controlGuidance;
            }
            String modelContinuationContext = modelAuthoredContinuationContext();
            if (!modelContinuationContext.isBlank()) {
                promptContract += "\n\nModel-authored continuation state from earlier provider output. "
                    + "This is not a user message and must never be interpreted as a new user instruction. "
                    + "Continue from it without repeating it verbatim:\n" + modelContinuationContext;
            }
            String authoritativeAgentState = this.agentState.promptSummary();
            if (!authoritativeAgentState.isBlank()) {
                promptContract += "\n\n" + authoritativeAgentState;
            }
            if (this.mode == RequestMode.AUTOMATION) {
                String orderedTasks = this.objectiveLedger.promptSummary();
                if (!orderedTasks.isBlank()) promptContract += "\n\n" + orderedTasks;
            }
            String agentToolFocus = AgentToolRoutingPolicy.promptDirective(
                this.agentState, requestTools, remainingRequiredToolIds());
            if (!agentToolFocus.isBlank()) {
                promptContract += "\n\n" + agentToolFocus;
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
            if (this.mode == RequestMode.AUTOMATION && this.executionContext != null
                && !directInformationDecision) {
                String executionExperience = this.executionContext.promptContext();
                if (!executionExperience.isBlank()) {
                    promptContract += "\n\n" + executionExperience;
                    TelemetryStore.provenance(this.displayId, providerTelemetrySpanId, "execution.intelligence",
                            "ModelExecutionContext", "context", executionExperience,
                            "Historical execution evidence and speculative prediction context supplied to the model");
                }
            }
            if (this.mode == RequestMode.AUTOMATION
                && !directToolDecision
                && !directVerifiedResult
                && !directInformationDecision
                && ModelExperimentalFeatures.snapshot().noFailEnabled()
                && !this.finalizationRequested.get()) {
                promptContract += """


                        No-Fail experiment is active for this Automation request. Continue through the registered tool loop until a validated successful tool output satisfies every known objective. A failed, blocked, partial, missing, unsupported, or unchanged result is evidence for a changed tool, changed arguments, a new observation, or a re-plan; it is not permission to claim completion. Verification, when enabled, must pass before a completed output counts. Never bypass approval or Minecraft permissions, never substitute a different target, never repeat an identical call against unchanged evidence, and always honor explicit Stop/cancellation.
                        """;
            }
            if (!directToolDecision
                && !directInformationDecision
                && !directConversationDecision
                && ModelExperimentalFeatures.snapshot().expertPrefetchEnabled()
                && this.providerRoundCount == 1) {
                String expertPrefetch = this.prefetchedExpertContext;
                if (!expertPrefetch.isBlank()) {
                    promptContract += "\n\n" + expertPrefetch;
                    TelemetryStore.provenance(this.displayId, providerTelemetrySpanId, "minecraft.prefetch",
                            "ModelExpertPrefetch", "context", expertPrefetch,
                            "Prompt-aware live Minecraft state supplied before generation");
                }
            }
            if (!directToolDecision && !directVerifiedResult && !directInformationDecision
                && !directConversationDecision
                && ModelExperimentalFeatures.snapshot().persistentKnowledge()) {
                String memory = this.prefetchedMemoryContext;
                if (!memory.isBlank()) {
                    promptContract += "\n\nRetrieved historical knowledge (verify against current state and tool results):\n" + memory;
                    TelemetryStore.provenance(this.displayId, providerTelemetrySpanId, "associative.memory",
                            "ModelAssociativeMemory", "retrieval", memory,
                            "Historical context retrieved for this request");
                }
            }
            String reasoningMemoContext = this.prefetchedReasoningMemos.promptContext();
            if (!directVerifiedResult && !reasoningMemoContext.isBlank()) {
                promptContract += "\n\n" + reasoningMemoContext;
                TelemetryStore.provenance(this.displayId, providerTelemetrySpanId, "reasoning.memo",
                        "SemanticReasoningMemoStore", "verified_conclusion", reasoningMemoContext,
                        "Previously verified reasoning products reused without replaying private reasoning");
            }
            int requestMessageBudget = conversationMessageBudget(this.mode);
            int requestCharacterBudget = conversationCharacterBudget(this.mode);
            if (directConversationDecision && this.conversationalThinking != null
                    && !this.conversationalThinking.reviewContext()) {
                // Preserve chat continuity without paying to re-prefill a long,
                // unrelated transcript for a self-contained fact such as
                // "what is the capital of France?". The durable conversation is
                // untouched; only this provider window is compacted.
                requestMessageBudget = Math.min(requestMessageBudget, 4);
                requestCharacterBudget = Math.min(requestCharacterBudget, 4 * 1024);
            }
            List<ModelMessage> requestMessages = new ArrayList<>(directVerifiedResult
                ? this.conversation.snapshotWithin(3, 8 * 1024)
                : this.mode == RequestMode.AUTOMATION
                && directInformationDecision
                && informationLatencyDecision.freshConversationWindow()
                ? List.of()
                : directToolDecision
                && latencyDecision.freshConversationWindow()
                ? List.of()
                : this.conversation.snapshotWithin(requestMessageBudget, requestCharacterBudget));
            if (!this.dispatched.get()) requestMessages.add(ModelMessage.user(this.prompt));
            requestMessages = List.copyOf(requestMessages);
            List<ModelToolDefinition> effectiveRequestTools = this.finalizationRequested.get() ? List.of() : requestTools;
            ModelContextBudgetPlanner.ToolPlan toolPlan = ModelContextBudgetPlanner.fitTools(
                    promptContract, requestMessages, effectiveRequestTools, this.capabilityProfile.contextWindowTokens());
            effectiveRequestTools = toolPlan.tools();
            ModelContextBudgetPlanner.Plan contextPlan = ModelContextBudgetPlanner.plan(
                    this.conversation.id(), this.prompt, promptContract, requestMessages,
                    effectiveRequestTools, this.capabilityProfile.contextWindowTokens());
            requestMessages = contextPlan.messages();
            if (!contextPlan.manifest().isBlank()) {
                promptContract += "\n\n" + contextPlan.manifest();
            }
            int requestContextCharacters = requestMessages.stream()
                .mapToInt(message -> message.content().length())
                .sum();
            TelemetryStore.provenance(this.displayId, providerTelemetrySpanId, "system.prompt",
                    "LocalModelSystemPrompt", "prompt_ingestion", promptContract,
                    "Effective system/policy/context contract supplied to the provider");
            if (!this.proceduralGuidance.isBlank()) {
                TelemetryStore.provenance(this.displayId, providerTelemetrySpanId, "koil.skills",
                        "KoilSkillRegistry", "retrieval", this.proceduralGuidance,
                        "Selected procedural guidance supplied to the model");
            }
            String conversationContext = requestMessages.stream()
                    .map(message -> message.role().name().toLowerCase(java.util.Locale.ROOT) + ": " + message.content())
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (!conversationContext.isBlank()) {
                TelemetryStore.provenance(this.displayId, providerTelemetrySpanId, "conversation.context",
                        "ModelConversation", "context", conversationContext,
                        requestMessages.size() + " conversation message(s) supplied");
            }
            TelemetryStore.metric(this.displayId, providerTelemetrySpanId, "system_prompt_bytes",
                    promptContract.getBytes(StandardCharsets.UTF_8).length);
            TelemetryStore.metric(this.displayId, providerTelemetrySpanId, "system_prompt_tokens_estimated",
                    TelemetryStore.estimateTokens(promptContract));
            TelemetryStore.metric(this.displayId, providerTelemetrySpanId, "context_characters", requestContextCharacters);
            TelemetryStore.metric(this.displayId, providerTelemetrySpanId, "context_tokens_estimated",
                    TelemetryStore.estimateTokens(conversationContext));
            TelemetryStore.metric(this.displayId, providerTelemetrySpanId, "tool_definition_count", effectiveRequestTools.size());
            TelemetryStore.metric(this.displayId, providerTelemetrySpanId, "context_tool_tokens_before", toolPlan.estimatedTokensBefore());
            TelemetryStore.metric(this.displayId, providerTelemetrySpanId, "context_tool_tokens_after", toolPlan.estimatedTokensAfter());
            TelemetryStore.metric(this.displayId, providerTelemetrySpanId, "context_tool_schemas_dropped", toolPlan.droppedTools());
            TelemetryStore.metric(this.displayId, providerTelemetrySpanId, "context_preflight_tokens_before", contextPlan.estimatedTokensBefore());
            TelemetryStore.metric(this.displayId, providerTelemetrySpanId, "context_preflight_tokens_after", contextPlan.estimatedTokensAfter());
            TelemetryStore.metric(this.displayId, providerTelemetrySpanId, "context_preflight_compacted_messages", contextPlan.compactedMessages());
            String cacheMode = this.mode == RequestMode.AUTOMATION ? "automation"
                : this.mode == RequestMode.ASK_DEEP ? "ask_deep" : "ask";
            String cacheToolVersion = !effectiveRequestTools.isEmpty() ? LocalModelToolCatalog.version() : "";
            String cacheStableFingerprint = ModelPromptCacheIdentity.stablePrefix(
                cacheMode, stablePromptContract, effectiveRequestTools, cacheToolVersion);
            String cacheRequestFingerprint = ModelPromptCacheIdentity.fullRequest(
                promptContract, requestMessages, effectiveRequestTools);
            List<String> cacheSeedFingerprints = new ArrayList<>();
            cacheSeedFingerprints.add(cacheStableFingerprint);
            String identityPrompt = LocalModelSystemPrompt.identityPrompt();
            if (stablePromptContract.startsWith(identityPrompt)) {
                String corePrompt = LocalModelSystemPrompt.load();
                if (stablePromptContract.startsWith(corePrompt)) {
                    cacheSeedFingerprints.add(ModelPromptCacheIdentity.seedPrefix("core", corePrompt, List.of()));
                }
                cacheSeedFingerprints.add(ModelPromptCacheIdentity.seedPrefix("identity", identityPrompt, List.of()));
            }
            String cacheSeedFingerprintList = cacheSeedFingerprints.stream().distinct()
                .collect(java.util.stream.Collectors.joining(","));
            LocalModelRuntimeLog.write(
                "request_context",
                "request=" + providerRequestId + " display=" + this.displayId
                    + " kms=" + (ModelGenerationHudState.snapshot(this.displayId) == null ? "unknown"
                    : ModelGenerationHudState.snapshot(this.displayId).sessionNumber())
                    + " conversation=" + this.conversation.id() + " mode="
                    + this.mode.name().toLowerCase(java.util.Locale.ROOT)
                    + " | system_chars=" + promptContract.length()
                    + " | history_chars=" + requestContextCharacters
                    + " | tools=" + effectiveRequestTools.size()
                    + " | tools_dropped_for_context=" + toolPlan.droppedTools()
                    + " | latency_path=" + (directToolDecision ? "direct_tool"
                    : directVerifiedResult ? "direct_result"
                    : directInformationDecision ? "direct_information" : "full_agent")
            );
            int policyOutputTokens = this.mode == RequestMode.AUTOMATION
                ? directToolDecision
                ? latencyDecision.maximumOutputTokens()
                : directVerifiedResult
                ? 192
                : directInformationDecision
                ? informationLatencyDecision.maximumOutputTokens()
                : this.thinking.maximumOutputTokens(this.capabilityProfile.stagedExecution())
                : directInformationDecision
                ? informationLatencyDecision.maximumOutputTokens()
                : this.conversationalThinking.maximumOutputTokens();
            // Policy output tokens are advisory only. Never truncate a model's reasoning or answer
            // merely because it crossed a heuristic token budget. Provider EOS, explicit user
            // cancellation, context exhaustion, and action safety guards remain authoritative.
            int providerOutputTokens = StreamingModelRequest.UNBOUNDED_OUTPUT_TOKENS;
            StreamingModelRequest request = new StreamingModelRequest(
                providerRequestId,
                this.conversation.id(),
                promptContract,
                requestMessages,
                effectiveRequestTools,
                providerOutputTokens,
                configuration.requestTimeout(),
                Map.ofEntries(
                    Map.entry("cache_slot", this.mode == RequestMode.AUTOMATION && configuration.kvSlots() > 1 ? "1"
                        : this.mode == RequestMode.ASK_DEEP && configuration.kvSlots() > 2 ? "2" : "0"),
                    Map.entry("cache_session_key", this.conversation.id() + ":" + cacheMode),
                    Map.entry("cache_stable_fingerprint", cacheStableFingerprint),
                    Map.entry("cache_request_fingerprint", cacheRequestFingerprint),
                    Map.entry("cache_seed_fingerprints", cacheSeedFingerprintList),
                    Map.entry("mode", cacheMode),
                    Map.entry("tool_registry_version", cacheToolVersion),
                    Map.entry("tool_count", Integer.toString(effectiveRequestTools.size())),
                    Map.entry("context_characters", Integer.toString(requestContextCharacters)),
                    Map.entry("context_estimated_tokens", Integer.toString(contextPlan.estimatedTokensAfter())),
                    Map.entry("context_maximum_tokens", Integer.toString(contextPlan.maximumTokens())),
                    Map.entry("context_reserved_tokens", Integer.toString(contextPlan.reservedTokens())),
                    Map.entry("context_compacted_messages", Integer.toString(contextPlan.compactedMessages())),
                    Map.entry("system_prompt_characters", Integer.toString(promptContract.length())),
                    Map.entry("required_tool_ids", String.join(",", remainingRequired)),
                    Map.entry("stable_system_prompt_characters", Integer.toString(stablePromptContract.length())),
                    Map.entry("display_request_id", this.displayId.toString()),
                    Map.entry("reasoning_depth", this.mode == RequestMode.AUTOMATION
                        ? this.thinking.depth().name().toLowerCase(java.util.Locale.ROOT)
                        : this.conversationalThinking.depth().name().toLowerCase(java.util.Locale.ROOT)),
                    Map.entry("agent_reasoning_mode", this.agentReasoningDecision.mode().name().toLowerCase(java.util.Locale.ROOT)),
                    Map.entry("agent_reason_targets", this.agentReasoningDecision.targets().toString()),
                    Map.entry("allow_native_reasoning", Boolean.toString(
                        this.agentReasoningDecision.budget().nativeReasoningEnabled()
                            && !this.forceNoNativeReasoningNextRound)),
                    Map.entry("staged_execution", Boolean.toString(this.capabilityProfile.stagedExecution())),
                    Map.entry("latency_path", directToolDecision ? "direct_tool"
                        : directVerifiedResult ? "direct_result"
                        : directInformationDecision ? "direct_information" : "full_agent")
                )
            );
            TelemetryStore.annotate(this.displayId, providerTelemetrySpanId, "provider", selectedProviderId());
            TelemetryStore.annotate(this.displayId, providerTelemetrySpanId, "execution_adapter", selectedExecutionAdapterId());
            TelemetryStore.annotate(this.displayId, providerTelemetrySpanId, "model", configuredModelId());
            TelemetryStore.annotate(this.displayId, providerTelemetrySpanId, "maximum_output_tokens", request.maximumOutputTokens());
            TelemetryStore.annotate(this.displayId, providerTelemetrySpanId, "recommended_output_tokens", policyOutputTokens);
            TelemetryStore.annotate(this.displayId, providerTelemetrySpanId, "output_budget_mode",
                    request.unboundedOutput() ? "natural_eos" : "bounded_intermediate");
            TelemetryStore.annotate(this.displayId, providerTelemetrySpanId, "configured_timeout_ms_advisory", request.timeout().toMillis());
            TelemetryStore.annotate(this.displayId, providerTelemetrySpanId, "tool_count", effectiveRequestTools.size());
            TelemetryStore.annotate(this.displayId, providerTelemetrySpanId, "agent_reasoning_mode",
                    this.agentReasoningDecision.mode().name().toLowerCase(java.util.Locale.ROOT));
            TelemetryStore.annotate(this.displayId, providerTelemetrySpanId, "native_reasoning_enabled",
                    this.agentReasoningDecision.budget().nativeReasoningEnabled() && !this.forceNoNativeReasoningNextRound);
            TelemetryStore.annotate(this.displayId, providerTelemetrySpanId, "context_characters", requestContextCharacters);
            TelemetryStore.annotate(this.displayId, providerTelemetrySpanId, "system_prompt_characters", promptContract.length());
            TelemetryStore.annotate(this.displayId, providerTelemetrySpanId, "cache_mode", cacheMode);
            TelemetryStore.annotate(this.displayId, providerTelemetrySpanId, "cache_stable_fingerprint", cacheStableFingerprint);
            TelemetryStore.annotate(this.displayId, providerTelemetrySpanId, "cache_request_fingerprint", cacheRequestFingerprint);
            TelemetryStore.metric(this.displayId, promptAssemblySpanId, "assembled_system_prompt_bytes",
                    promptContract.getBytes(StandardCharsets.UTF_8).length);
            TelemetryStore.metric(this.displayId, promptAssemblySpanId, "assembled_context_characters", requestContextCharacters);
            TelemetryStore.finishSpan(this.displayId, promptAssemblySpanId, TelemetryCapabilityState.AVAILABLE,
                    "assembled", "Provider request context assembled.");
            this.activeProviderRoundStartedAtMillis = System.currentTimeMillis();
            this.firstProviderTextAtMillis = 0L;
            AtomicReference<String> providerPhaseSpan = new AtomicReference<>("");
            AtomicReference<ModelRequestState> providerPhaseState = new AtomicReference<>();
            if (this.mode == RequestMode.AUTOMATION) {
                scheduleProviderRoundHeartbeats(providerRequestId, this.activeProviderRoundStartedAtMillis);
            }
            LocalModelRuntimeLog.write(
                "provider_round_started",
                "request=" + providerRequestId + " display=" + this.displayId
                    + " | round=" + this.providerRoundCount
                    + " | recommended_rounds=" + recommendedRounds
                    + " | recommended_tool_calls=" + (this.mode == RequestMode.AUTOMATION ? this.thinking.maximumToolCalls() : 0)
                    + " | output_tokens=" + request.maximumOutputTokens()
                    + " | timeout_ms_advisory=" + request.timeout().toMillis()
            );
            AtomicBoolean providerReasoningObserved = new AtomicBoolean();
            Map<String, StringBuilder> providerExposedPreviews = new ConcurrentHashMap<>();
            AtomicLong lastReasoningSignalAtMillis = new AtomicLong();
            AtomicInteger lastReasoningSignalTokens = new AtomicInteger();
            ManagedModelRequest managed = runtime.submit(request, new StreamingModelObserver() {
                @Override
                public void onState(UUID id, ModelRequestState state, String detail) {
                    if (providerRequestId.equals(activeProviderRoundId.get()) && !state.terminal()) {
                        if (state != ModelRequestState.QUEUED && dispatched.compareAndSet(false, true)) {
                            conversation.add(ModelMessage.user(prompt));
                        }
                        ModelRequestState priorState = providerPhaseState.getAndSet(state);
                        if (priorState != state) {
                            String priorSpan = providerPhaseSpan.getAndSet("");
                            if (!priorSpan.isBlank()) {
                                TelemetryStore.finishSpan(displayId, priorSpan, TelemetryCapabilityState.AVAILABLE,
                                        "phase_complete", priorState == null ? "" : priorState.name().toLowerCase(java.util.Locale.ROOT));
                            }
                            String nextSpan = TelemetryStore.beginSpan(displayId, providerTelemetrySpanId,
                                    telemetrySpanKind(state), "model " + state.name().toLowerCase(java.util.Locale.ROOT));
                            providerPhaseSpan.set(nextSpan);
                            TelemetryStore.annotate(displayId, nextSpan, "detail", detail);
                        }
                        String presentedDetail = detail;
                        if (providerRoundCount > 1 && state == ModelRequestState.PREFILLING) {
                            presentedDetail = continuationPrefillDetail();
                        } else if (state == ModelRequestState.THINKING) {
                            presentedDetail = agentReasoningStatusDetail();
                        }
                        ModelGenerationHudState.state(displayId, state, presentedDetail);
                    }
                }

                @Override
                public void onTextDelta(UUID id, String delta) {
                    if (providerRequestId.equals(activeProviderRoundId.get())) {
                        if (firstProviderTextAtMillis == 0L && delta != null && !delta.isBlank()) {
                            firstProviderTextAtMillis = System.currentTimeMillis();
                            long firstTextElapsed = Math.max(0L, firstProviderTextAtMillis - activeProviderRoundStartedAtMillis);
                            LocalModelRuntimeLog.write(
                                "provider_first_text",
                                "request=" + providerRequestId + " display=" + displayId
                                    + " | elapsed_ms=" + firstTextElapsed
                            );
                            TelemetryStore.metric(displayId, providerTelemetrySpanId, "provider_first_text_ms", firstTextElapsed);
                            TelemetryStore.event(displayId, providerTelemetrySpanId, "first_text",
                                    TelemetryCapabilityState.ACTIVE, "First provider text received",
                                    Map.of("elapsed_ms", Long.toString(firstTextElapsed)),
                                    List.of(new TelemetryText(TelemetryText.Kind.DURATION, firstTextElapsed + " ms")));
                        }
                        // Every model-authored visible-text delta is observable in the popup,
                        // including tool/planning/repair rounds. A later Koil decision may classify
                        // the round as intermediate, but presentation must never hide generation
                        // that actually occurred. Intermediate round text is archived before the
                        // next provider round so the new stream can remain live without concatenating
                        // two distinct generations into one apparent final answer.
                        ModelGenerationHudState.append(displayId, delta);
                        // Voice remains gated by streamUserFacingVoice, so exposing internal-round
                        // text visually does not make Koil speak drafts or planning prose aloud.
                    }
                }


                @Override
                public void onReasoningDelta(UUID id, String delta) {
                    onExposedData(
                            id,
                            ModelExposedData.of(
                                    ModelExposedData.Kind.REASONING,
                                    delta,
                                    "legacy_reasoning_delta",
                                    selectedExecutionAdapterId()
                            )
                    );
                }

                @Override
                public void onExposedData(UUID id, ModelExposedData exposed) {
                    if (!providerRequestId.equals(activeProviderRoundId.get())
                            || exposed == null || !exposed.hasText()) {
                        return;
                    }
                    long elapsed = Math.max(0L, System.currentTimeMillis() - activeProviderRoundStartedAtMillis);
                    if (providerReasoningObserved.compareAndSet(false, true)) {
                        LocalModelRuntimeLog.write(
                                "provider_exposed_stream_started",
                                "request=" + providerRequestId + " display=" + displayId
                                        + " | tag=" + exposed.tag()
                                        + " | native_channel=" + exposed.nativeChannel()
                                        + " | elapsed_ms=" + elapsed
                        );
                        TelemetryStore.event(
                                displayId,
                                providerTelemetrySpanId,
                                "exposed_stream_started",
                                TelemetryCapabilityState.ACTIVE,
                                "Model-exposed " + exposed.tag() + " stream started",
                                Map.of(
                                        "elapsed_ms", Long.toString(elapsed),
                                        "tag", exposed.tag(),
                                        "native_channel", exposed.nativeChannel()
                                ),
                                List.of(new TelemetryText(TelemetryText.Kind.DURATION, elapsed + " ms"))
                        );
                    }

                    String eventKey = exposed.eventKey();
                    StringBuilder preview = providerExposedPreviews.computeIfAbsent(eventKey, ignored -> new StringBuilder());
                    String visibleExposed;
                    int exposedCharacters;
                    synchronized (preview) {
                        // Model-authored exposed channels are presentation content. Preserve every
                        // emitted character rather than reducing them to a compact status preview.
                        preview.append(exposed.text());
                        visibleExposed = preview.toString();
                        exposedCharacters = preview.length();
                    }

                    com.google.gson.JsonObject data = new com.google.gson.JsonObject();
                    data.addProperty("providerRequestId", providerRequestId.toString());
                    data.addProperty("elapsedMillis", elapsed);
                    data.addProperty("stream", "model_exposed");
                    data.addProperty("exposedTag", exposed.tag());
                    data.addProperty("exposedKind", exposed.kind().name().toLowerCase(java.util.Locale.ROOT));
                    data.addProperty("nativeChannel", exposed.nativeChannel());
                    data.addProperty("provider", exposed.provider());
                    data.addProperty("modelId", configuredModelId());
                    data.addProperty("rollingExposedCharacters", exposedCharacters);
                    exposed.metadata().forEach(data::addProperty);
                    ModelGenerationHudState.upsertEvent(
                            displayId,
                            ModelGenerationHudState.ActivityEventType.THOUGHT_SUMMARY,
                            "provider-exposed-" + providerRequestId + "-" + eventKey,
                            visibleExposed,
                            data
                    );

                    long now = System.currentTimeMillis();
                    long previousSignal = lastReasoningSignalAtMillis.get();
                    if (previousSignal == 0L || now - previousSignal >= 1500L) {
                        lastReasoningSignalAtMillis.set(now);
                        com.google.gson.JsonObject signal = new com.google.gson.JsonObject();
                        signal.addProperty("providerRequestId", providerRequestId.toString());
                        signal.addProperty("elapsedMillis", elapsed);
                        signal.addProperty("exposedTag", exposed.tag());
                        signal.addProperty("nativeChannel", exposed.nativeChannel());
                        signal.addProperty("rollingExposedCharacters", exposedCharacters);
                        signal.addProperty("source", "model_exposed_stream");
                        ModelGenerationHudState.upsertEvent(
                                displayId,
                                ModelGenerationHudState.ActivityEventType.MODEL_DATA,
                                "provider-signal-" + providerRequestId + "-" + eventKey,
                                exposed.tag() + " stream active · " + exposedCharacters + " characters · "
                                        + (elapsed / 1000L) + "s",
                                signal
                        );
                    }
                }

                @Override
                public void onTelemetry(UUID id, ModelRuntimeTelemetry telemetry) {
                    if (!providerRequestId.equals(activeProviderRoundId.get()) || telemetry == null) {
                        return;
                    }
                    String kind = telemetry.kind() == null || telemetry.kind().isBlank()
                            ? "runtime" : telemetry.kind();
                    Map<String, String> fields = telemetry.fields() == null ? Map.of() : telemetry.fields();
                    TelemetryStore.event(
                            displayId,
                            providerTelemetrySpanId,
                            "provider_" + kind,
                            TelemetryCapabilityState.ACTIVE,
                            telemetry.summary(),
                            fields,
                            List.of()
                    );
                    com.google.gson.JsonObject data = new com.google.gson.JsonObject();
                    data.addProperty("providerRequestId", providerRequestId.toString());
                    data.addProperty("telemetryKind", kind);
                    data.addProperty("classifiedAsThought", false);
                    data.addProperty("source", "provider_runtime_telemetry");
                    fields.forEach(data::addProperty);
                    String summary = telemetry.summary() == null || telemetry.summary().isBlank()
                            ? "Provider telemetry · " + kind
                            : telemetry.summary();
                    ModelGenerationHudState.upsertEvent(
                            displayId,
                            ModelGenerationHudState.ActivityEventType.MODEL_DATA,
                            "provider-telemetry-" + providerRequestId + "-" + kind,
                            summary,
                            data
                    );
                }

                @Override
                public void onUsage(UUID id, ModelUsage usage) {
                    if (providerRequestId.equals(activeProviderRoundId.get())) {
                        ModelGenerationHudState.usage(displayId, usage);
                        if (usage != null) {
                            TelemetryStore.metric(displayId, providerTelemetrySpanId, "prompt_tokens", usage.promptTokens());
                            TelemetryStore.metric(displayId, providerTelemetrySpanId, "completion_tokens", usage.completionTokens());
                            TelemetryStore.metric(displayId, providerTelemetrySpanId, "cached_prefix_tokens", usage.reusedPrefixTokens());
                            TelemetryStore.metric(displayId, providerTelemetrySpanId, "newly_evaluated_tokens",
                                    Math.max(0, usage.promptTokens() - usage.reusedPrefixTokens()));
                            TelemetryStore.metric(displayId, providerTelemetrySpanId, "queue_ms", usage.queueMillis());
                            TelemetryStore.metric(displayId, providerTelemetrySpanId, "time_to_first_token_ms", usage.timeToFirstTokenMillis());
                            ModelRequestState providerState = providerPhaseState.get();
                            boolean preVisibleInternalGeneration = firstProviderTextAtMillis <= 0L
                                    && usage.completionTokens() > 0
                                    && (providerState == ModelRequestState.THINKING
                                    || providerState == ModelRequestState.PREFILLING);
                            if (providerReasoningObserved.get() || preVisibleInternalGeneration) {
                                long now = System.currentTimeMillis();
                                long elapsed = Math.max(0L, now - activeProviderRoundStartedAtMillis);
                                int tokenDelta = usage.completionTokens() - lastReasoningSignalTokens.get();
                                long timeDelta = now - lastReasoningSignalAtMillis.get();
                                if (tokenDelta >= 16 || timeDelta >= 1500L || lastReasoningSignalAtMillis.get() == 0L) {
                                    lastReasoningSignalTokens.set(usage.completionTokens());
                                    lastReasoningSignalAtMillis.set(now);
                                    com.google.gson.JsonObject data = new com.google.gson.JsonObject();
                                    data.addProperty("completionTokens", usage.completionTokens());
                                    data.addProperty("promptTokens", usage.promptTokens());
                                    data.addProperty("cachedPrefixTokens", usage.reusedPrefixTokens());
                                    data.addProperty("tokensPerSecond", usage.tokensPerSecond());
                                    data.addProperty("elapsedMillis", elapsed);
                                    data.addProperty("reasoningStreamObserved", providerReasoningObserved.get());
                                    data.addProperty("visibleTextObserved", firstProviderTextAtMillis > 0L);
                                    data.addProperty("classifiedAsThought", false);
                                    data.addProperty("source", providerReasoningObserved.get()
                                            ? "reasoning_usage" : "pre_visible_generation_usage");
                                    String speed = usage.tokensPerSecond() > 0.0D
                                            ? String.format(java.util.Locale.ROOT, "%.1f tok/s", usage.tokensPerSecond())
                                            : "speed pending";
                                    String summary = providerReasoningObserved.get()
                                            ? "Reasoning telemetry · " + usage.completionTokens() + " completion tokens · "
                                                + speed + " · " + (elapsed / 1000L) + "s"
                                            : "Internal generation telemetry · " + usage.completionTokens() + " completion tokens · "
                                                + speed + " · " + (elapsed / 1000L) + "s";
                                    ModelGenerationHudState.upsertEvent(
                                            displayId,
                                            ModelGenerationHudState.ActivityEventType.MODEL_DATA,
                                            "provider-data-" + providerRequestId,
                                            summary,
                                            data
                                    );
                                }
                            }
                        }
                    }
                }

                @Override
                public void onComplete(StreamingModelResponse response) {
                    if (providerRequestId.equals(activeProviderRoundId.get())) {
                        if (streamingSpeech != null) {
                            // Do not finish speech at provider completion. The visual
                            // presentation may still be catching up. Final remaining
                            // speech is released only after the completed response is
                            // actually inserted into Minecraft chat.
                            streamedVoiceResponse.set(response == null ? "" : response.text());
                        }
                        long completedAt = System.currentTimeMillis();
                        String finalPhaseSpan = providerPhaseSpan.getAndSet("");
                        if (!finalPhaseSpan.isBlank()) {
                            TelemetryStore.finishSpan(displayId, finalPhaseSpan, TelemetryCapabilityState.AVAILABLE,
                                    "phase_complete", providerPhaseState.get() == null ? "" : providerPhaseState.get().name().toLowerCase(java.util.Locale.ROOT));
                        }
                        TelemetryStore.metric(displayId, providerTelemetrySpanId, "provider_round_ms",
                                Math.max(0L, completedAt - activeProviderRoundStartedAtMillis));
                        if (response != null) {
                            TelemetryStore.metric(displayId, providerTelemetrySpanId, "provider_tool_calls", response.toolCalls().size());
                            TelemetryStore.metric(displayId, providerTelemetrySpanId, "output_bytes",
                                    response.text().getBytes(StandardCharsets.UTF_8).length);
                            TelemetryStore.metric(displayId, providerTelemetrySpanId, "reasoning_bytes",
                                    response.reasoningText().getBytes(StandardCharsets.UTF_8).length);
                            TelemetryStore.provenance(displayId, providerTelemetrySpanId, "provider.output",
                                    selectedProviderId(), "model_output", response.text(),
                                    "Observable provider response before Koil post-processing");
                        }
                        TelemetryStore.finishSpan(displayId, providerTelemetrySpanId, TelemetryCapabilityState.AVAILABLE,
                                "completed", response == null ? "provider returned no response object" : response.providerFinishReason());
                        TelemetryStore.capability(displayId, "model", "provider_round", TelemetryCapabilityState.AVAILABLE,
                                "completed", "Provider round " + providerRoundCount + " completed.");
                        LocalModelRuntimeLog.write(
                            "provider_round_completed",
                            "request=" + providerRequestId + " display=" + displayId
                                + " | round=" + providerRoundCount
                                + " | elapsed_ms=" + Math.max(0L, completedAt - activeProviderRoundStartedAtMillis)
                                + " | first_text_ms=" + (firstProviderTextAtMillis <= 0L ? -1L
                                : Math.max(0L, firstProviderTextAtMillis - activeProviderRoundStartedAtMillis))
                                + " | tool_calls=" + (response == null ? 0 : response.toolCalls().size())
                        );
                        handleResponse(response);
                    } else if (streamingSpeech != null) {
                        ModelVoiceService.discardPresentedStreaming(displayId);
                    }
                }

                @Override
                public void onFailure(UUID id, String code, String detail, Throwable cause) {
                    if (streamingSpeech != null) {
                        ModelVoiceService.discardPresentedStreaming(displayId);
                    }
                    if (!providerRequestId.equals(activeProviderRoundId.get())) {
                        return;
                    }
                    String failedPhaseSpan = providerPhaseSpan.getAndSet("");
                    if (!failedPhaseSpan.isBlank()) {
                        TelemetryStore.finishSpan(displayId, failedPhaseSpan, TelemetryCapabilityState.FAILED, code, detail);
                    }
                    TelemetryStore.finishSpan(displayId, providerTelemetrySpanId,
                            "cancelled".equals(code) ? TelemetryCapabilityState.CANCELLED : TelemetryCapabilityState.FAILED,
                            code, detail);
                    TelemetryStore.capability(displayId, "model", "provider_round",
                            "cancelled".equals(code) ? TelemetryCapabilityState.CANCELLED : TelemetryCapabilityState.FAILED,
                            code, detail);
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

        /**
         * Builds the first provider packet concurrently under one small wall-clock
         * budget.  Prompt-aware tool routing, procedural guidance, live-state
         * prefetch and associative memory all begin together so none of them can
         * serially hold the model in "Starting" for seconds.  The deterministic
         * local results are always valid fallbacks; slow semantic enrichment is
         * optional and may be picked up by a later tool round instead.
         */
        private void resolveFirstRoundPrefillInputs() {
            if (this.providerRoundCount != 0) return;

            long started = System.nanoTime();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FIRST_ROUND_PREFILL_BUDGET_MILLIS);
            boolean directAsk = this.mode == RequestMode.ASK
                    && this.conversationalThinking != null
                    && this.conversationalThinking.depth() == ConversationalReasoningPolicy.Depth.DIRECT
                    && this.tools.isEmpty();
            boolean leanEvidenceAsk = this.mode == RequestMode.ASK
                    && this.conversationalThinking != null
                    && !this.conversationalThinking.reviewContext()
                    && !this.tools.isEmpty()
                    && this.tools.size() <= 2
                    && (this.conversationalThinking.depth() == ConversationalReasoningPolicy.Depth.DIRECT
                    || this.conversationalThinking.depth() == ConversationalReasoningPolicy.Depth.NORMAL);
            boolean leanAsk = directAsk || leanEvidenceAsk;

            CompletableFuture<List<ModelToolDefinition>> toolFuture = this.mode == RequestMode.ASK
                    && this.capabilityProfile.canAutomate()
                    && !this.tools.isEmpty()
                    ? LocalModelToolCatalog.resolveInformationToolsForPrompt(this.prompt, this.displayId.toString())
                    : CompletableFuture.completedFuture(this.tools);

            KoilSkillMode skillMode = switch (this.mode) {
                case ASK -> KoilSkillMode.ASK;
                case ASK_DEEP -> KoilSkillMode.DEEP_THOUGHT;
                case AUTOMATION -> KoilSkillMode.AUTOMATION;
            };
            // Skill activation is model-independent. Even the lean/direct path may
            // receive a locally matched Skill because this requires no native
            // function-calling protocol and no retrieval wait. Semantic Skill
            // expansion remains disabled on the latency-sensitive lean path.
            List<KoilSkillSelection> deterministicSkills = KoilSkillRegistry.resolve(this.prompt, skillMode);
            CompletableFuture<List<KoilSkillSelection>> skillFuture = leanAsk
                    ? CompletableFuture.completedFuture(deterministicSkills)
                    : KoilKnowledgeRuntime.shared()
                    .map(engine -> KoilSkillRegistry.resolve(engine, this.prompt, skillMode, this.displayId.toString()))
                    .orElseGet(() -> CompletableFuture.completedFuture(deterministicSkills));

            boolean richPrefill = !leanAsk;
            CompletableFuture<String> expertFuture = richPrefill
                    && ModelExperimentalFeatures.snapshot().expertPrefetchEnabled()
                    ? CompletableFuture.supplyAsync(() -> ModelExpertPrefetch.capture(this.prompt))
                    : CompletableFuture.completedFuture("");
            CompletableFuture<String> memoryFuture = richPrefill
                    && ModelExperimentalFeatures.snapshot().persistentKnowledge()
                    ? CompletableFuture.supplyAsync(() -> ModelAssociativeMemory.relevantContext(this.prompt))
                    : CompletableFuture.completedFuture("");
            CompletableFuture<SemanticReasoningMemoStore.Lookup> reasoningMemoFuture =
                    !this.prefetchedReasoningMemos.matches().isEmpty()
                    ? CompletableFuture.completedFuture(this.prefetchedReasoningMemos)
                    : CompletableFuture.supplyAsync(() -> SemanticReasoningMemoStore.lookupAll(reasoningMemoCues()));

            this.tools = awaitPrefill(toolFuture, this.tools, deadline);
            List<KoilSkillSelection> selectedSkills = awaitPrefill(skillFuture, deterministicSkills, deadline);
            this.proceduralGuidance = KoilSkillRegistry.compactGuidance(selectedSkills);
            recordSelectedSkills(selectedSkills);
            this.prefetchedExpertContext = awaitPrefill(expertFuture, "", deadline);
            this.prefetchedMemoryContext = awaitPrefill(memoryFuture, "", deadline);
            this.prefetchedReasoningMemos = awaitPrefill(
                    reasoningMemoFuture, this.prefetchedReasoningMemos, deadline);
            mergeReasoningMemoTools();

            long elapsedMillis = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            LocalModelRuntimeLog.write(
                    "prefill_packet_prepared",
                    "display=" + this.displayId
                            + " | mode=" + this.mode.name().toLowerCase(java.util.Locale.ROOT)
                            + " | elapsed_ms=" + elapsedMillis
                            + " | budget_ms=" + FIRST_ROUND_PREFILL_BUDGET_MILLIS
                            + " | tools=" + this.tools.size()
                            + " | guidance_chars=" + this.proceduralGuidance.length()
                            + " | expert_chars=" + this.prefetchedExpertContext.length()
                            + " | memory_chars=" + this.prefetchedMemoryContext.length()
                            + " | reasoning_memos=" + this.prefetchedReasoningMemos.matches().size()
                            + " | reasoning_memo_chars=" + this.prefetchedReasoningMemos.promptContext().length()
            );
        }

        private void recordSelectedSkills(List<KoilSkillSelection> selections) {
            if (selections == null || selections.isEmpty()) return;
            this.activeSkillSelections = List.copyOf(selections);
            String parent = TelemetryStore.rootSpan(this.displayId);
            int index = 0;
            for (KoilSkillSelection selection : selections) {
                if (selection == null || selection.definition() == null) continue;
                KoilSkillDefinition skill = selection.definition();
                com.google.gson.JsonObject data = new com.google.gson.JsonObject();
                data.addProperty("skillId", skill.id());
                data.addProperty("source", skill.source());
                data.addProperty("activation", selection.activation());
                data.addProperty("trust", skill.trust().name().toLowerCase(java.util.Locale.ROOT));
                data.addProperty("version", skill.version());
                data.addProperty("resourceRoot", skill.root());
                com.google.gson.JsonArray declaredTools = new com.google.gson.JsonArray();
                skill.declaredTools().forEach(declaredTools::add);
                data.add("declaredTools", declaredTools);
                ModelGenerationHudState.upsertEvent(
                        this.displayId,
                        ModelGenerationHudState.ActivityEventType.SKILL_START,
                        selectedSkillEventId(index, skill),
                        "Using " + skill.id() + " [" + selection.activation() + "]",
                        data
                );

                String spanId = TelemetryStore.beginSpan(
                        this.displayId, parent, TelemetrySpanKind.SKILL_SELECTION, "skill " + skill.id(),
                        Map.of(
                                "skill_id", skill.id(),
                                "source", skill.source(),
                                "activation", selection.activation(),
                                "trust", skill.trust().name().toLowerCase(java.util.Locale.ROOT)
                        )
                );
                TelemetryStore.metric(this.displayId, spanId, "guidance_chars", skill.compactGuidance().length());
                TelemetryStore.metric(this.displayId, spanId, "declared_tool_count", skill.declaredTools().size());
                TelemetryStore.provenance(
                        this.displayId, spanId, "skill.guidance", skill.source(), "skill_selection",
                        skill.compactGuidance(), "Selected " + skill.id() + " via " + selection.activation()
                );
                TelemetryStore.capability(
                        this.displayId, "skill", skill.id(), TelemetryCapabilityState.AVAILABLE,
                        "selected", "Skill guidance selected via " + selection.activation()
                );
                TelemetryStore.finishSpan(
                        this.displayId, spanId, TelemetryCapabilityState.AVAILABLE,
                        "selected", "Skill guidance added to the model prefill packet."
                );
                index++;
            }
        }

        private void finishSelectedSkillActivities(boolean successful, String detail) {
            List<KoilSkillSelection> selections = this.activeSkillSelections;
            if (selections == null || selections.isEmpty()) return;
            int index = 0;
            for (KoilSkillSelection selection : selections) {
                if (selection == null || selection.definition() == null) continue;
                KoilSkillDefinition skill = selection.definition();
                com.google.gson.JsonObject data = new com.google.gson.JsonObject();
                data.addProperty("skillId", skill.id());
                data.addProperty("source", skill.source());
                data.addProperty("activation", selection.activation());
                data.addProperty("status", successful ? "completed" : "stopped");
                if (detail != null && !detail.isBlank()) data.addProperty("detail", detail);
                String summary = successful
                        ? "Used " + skill.id()
                        : "Stopped " + skill.id() + (detail == null || detail.isBlank() ? "" : " — " + abbreviate(detail, 180));
                ModelGenerationHudState.upsertEvent(
                        this.displayId,
                        ModelGenerationHudState.ActivityEventType.SKILL_RESULT,
                        selectedSkillEventId(index, skill),
                        summary,
                        data
                );
                index++;
            }
        }

        private static String selectedSkillEventId(int index, KoilSkillDefinition skill) {
            return "selected-skill-" + index + "-" + Integer.toUnsignedString(skill.id().hashCode(), 36);
        }

        private String decorateSkillExecution(ModelToolCall call, com.google.gson.JsonObject data) {
            SkillExecutionMatch match = findSkillExecution(call);
            if (match == null) return "";
            this.skillExecutions.put(call.id(), match);
            if (data != null) {
                data.addProperty("skillId", match.skillId());
                data.addProperty("skillPath", match.path());
                data.addProperty("skillExecution", true);
            }
            return "Executing " + match.path();
        }

        private SkillExecutionMatch findSkillExecution(ModelToolCall call) {
            if (call == null || isSkillTool(call.toolId()) || !isSkillExecutionTool(call.toolId())
                    || call.arguments() == null) return null;
            List<String> values = new ArrayList<>();
            collectStringValues(call.arguments(), values);
            if (values.isEmpty()) return null;
            for (KoilSkillDefinition skill : KoilSkillRegistry.definitions()) {
                if (skill == null || skill.root() == null || skill.root().isBlank()) continue;
                String root = normalizePathish(skill.root());
                if (root.isBlank()) continue;
                for (String value : values) {
                    String path = extractSkillOwnedPath(value, root);
                    if (!path.isBlank()) return new SkillExecutionMatch(skill.id(), path);
                }
            }
            return null;
        }

        private static void collectStringValues(com.google.gson.JsonElement element, List<String> out) {
            if (element == null || element.isJsonNull()) return;
            if (element.isJsonPrimitive()) {
                com.google.gson.JsonPrimitive primitive = element.getAsJsonPrimitive();
                if (primitive.isString()) out.add(primitive.getAsString());
                return;
            }
            if (element.isJsonArray()) {
                for (com.google.gson.JsonElement child : element.getAsJsonArray()) collectStringValues(child, out);
                return;
            }
            if (element.isJsonObject()) {
                for (var entry : element.getAsJsonObject().entrySet()) collectStringValues(entry.getValue(), out);
            }
        }

        private static String extractSkillOwnedPath(String raw, String normalizedRoot) {
            String value = normalizePathish(raw);
            if (value.isBlank() || normalizedRoot.isBlank()) return "";
            int start = value.indexOf(normalizedRoot);
            if (start < 0) {
                int marker = normalizedRoot.indexOf("koil/sys/model/skills/");
                if (marker >= 0) {
                    String relativeRoot = normalizedRoot.substring(marker);
                    start = value.indexOf(relativeRoot);
                    if (start >= 0) normalizedRoot = relativeRoot;
                }
            }
            if (start < 0) return "";

            int end = value.length();
            String tail = value.substring(start);
            for (String extension : List.of(".py", ".sh", ".bash", ".zsh", ".js", ".mjs", ".cjs", ".ts",
                    ".java", ".kt", ".kts", ".ps1", ".rb", ".pl", ".jar", ".json", ".md")) {
                int found = tail.toLowerCase(java.util.Locale.ROOT).indexOf(extension);
                if (found >= 0) end = Math.min(end, start + found + extension.length());
            }
            if (end == value.length()) {
                for (int i = start + normalizedRoot.length(); i < value.length(); i++) {
                    char c = value.charAt(i);
                    if (c == '\n' || c == '\r' || c == '"' || c == '\'' || c == ';' || c == '|' || c == '&'
                            || c == '<' || c == '>') {
                        end = i;
                        break;
                    }
                }
            }
            String path = value.substring(start, Math.max(start, end)).strip();
            while (!path.isEmpty() && (path.endsWith(",") || path.endsWith(")") || path.endsWith("]"))) {
                path = path.substring(0, path.length() - 1).stripTrailing();
            }
            return path;
        }

        private static String normalizePathish(String value) {
            return value == null ? "" : value.replace('\\', '/').strip();
        }

        private static boolean isSkillExecutionTool(String toolId) {
            String id = toolId == null ? "" : toolId.strip().toLowerCase(java.util.Locale.ROOT);
            return "workspace.exec".equals(id)
                    || id.endsWith(".exec")
                    || id.endsWith(".execute")
                    || id.endsWith(".run")
                    || id.startsWith("development.");
        }

        private List<String> reasoningMemoCues() {
            List<String> cues = new ArrayList<>();
            cues.add(this.prompt);
            if (this.mode == RequestMode.AUTOMATION) {
                this.objectiveLedger.snapshot().stream()
                        .map(ModelObjectiveLedger.Objective::text)
                        .filter(text -> text != null && !text.isBlank())
                        .distinct()
                        .forEach(cues::add);
            }
            return List.copyOf(cues);
        }

        private void primeReasoningMemoHints() {
            if (!this.prefetchedReasoningMemos.matches().isEmpty()) return;
            this.prefetchedReasoningMemos = SemanticReasoningMemoStore.lookupAll(reasoningMemoCues());
            mergeReasoningMemoTools();
            if (!this.prefetchedReasoningMemos.matches().isEmpty()) {
                int reusableArguments = 0;
                int fastRoutes = 0;
                int strongestConfidence = 0;
                for (SemanticReasoningMemoStore.Match match : this.prefetchedReasoningMemos.matches()) {
                    if (match.argumentsReusable()) reusableArguments++;
                    if (match.fastRouteEligible()) fastRoutes++;
                    strongestConfidence = Math.max(strongestConfidence, match.confidence());
                }
                com.google.gson.JsonObject signal = new com.google.gson.JsonObject();
                signal.addProperty("matches", this.prefetchedReasoningMemos.matches().size());
                signal.addProperty("fastRoutes", fastRoutes);
                signal.addProperty("reusableArguments", reusableArguments);
                signal.addProperty("strongestConfidence", strongestConfidence);
                ModelGenerationHudState.upsertEvent(
                        this.displayId,
                        ModelGenerationHudState.ActivityEventType.MODEL_DATA,
                        "reasoning-memo-lookup-" + this.displayId,
                        "Recovered " + this.prefetchedReasoningMemos.matches().size()
                                + " verified reasoning conclusion"
                                + (this.prefetchedReasoningMemos.matches().size() == 1 ? "" : "s")
                                + "; " + fastRoutes + " can bypass repeated tool-selection reasoning.",
                        signal
                );
                LocalModelRuntimeLog.write(
                        "reasoning_memo_lookup",
                        this.displayId + " | matches=" + this.prefetchedReasoningMemos.matches().size()
                                + " | fast_routes=" + fastRoutes
                                + " | args_reusable=" + reusableArguments
                                + " | strongest_confidence=" + strongestConfidence
                );
            }
        }

        private void mergeReasoningMemoTools() {
            if (this.prefetchedReasoningMemos.suggestedToolIds().isEmpty()) return;
            LinkedHashMap<String, ModelToolDefinition> mergedTools = new LinkedHashMap<>();
            for (ModelToolDefinition definition : this.tools) mergedTools.put(definition.id(), definition);
            for (String toolId : this.prefetchedReasoningMemos.suggestedToolIds()) {
                LocalModelToolCatalog.definition(toolId).ifPresent(definition -> mergedTools.putIfAbsent(toolId, definition));
            }
            this.tools = List.copyOf(mergedTools.values());
        }

        private static <T> T awaitPrefill(CompletableFuture<T> future, T fallback, long deadlineNanos) {
            if (future == null) return fallback;
            long remainingNanos = deadlineNanos - System.nanoTime();
            try {
                if (remainingNanos <= 0L) return future.getNow(fallback);
                return future.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (Exception ignored) {
                try {
                    return future.getNow(fallback);
                } catch (RuntimeException ignoredCompletionFailure) {
                    return fallback;
                }
            }
        }

        private void scheduleProviderRoundHeartbeats(UUID providerRequestId, long roundStartedAtMillis) {
            scheduleProviderRoundHeartbeat(providerRequestId, roundStartedAtMillis, 15,
                "Generation session is still open; waiting for the first actionable output.");
            scheduleProviderLivenessSignal(providerRequestId, roundStartedAtMillis, 30L);
        }

        private void scheduleProviderLivenessSignal(
            UUID providerRequestId,
            long roundStartedAtMillis,
            long delaySeconds
        ) {
            CompletableFuture.runAsync(() -> {
                if (this.terminal.get()
                    || !providerRequestId.equals(this.activeProviderRoundId.get())
                    || this.activeProviderRoundStartedAtMillis != roundStartedAtMillis) {
                    return;
                }
                long elapsed = Math.max(0L, System.currentTimeMillis() - roundStartedAtMillis);
                com.google.gson.JsonObject data = new com.google.gson.JsonObject();
                data.addProperty("providerRequestId", providerRequestId.toString());
                data.addProperty("elapsedMillis", elapsed);
                data.addProperty("visibleTextObserved", this.firstProviderTextAtMillis > 0L);
                data.addProperty("automaticTimeout", false);
                ModelGenerationHudState.upsertEvent(
                    this.displayId,
                    ModelGenerationHudState.ActivityEventType.MODEL_DATA,
                    "provider-liveness-" + providerRequestId,
                    "Generation session open · " + (elapsed / 1000L)
                        + "s · no automatic thinking timeout; Stop remains available.",
                    data
                );
                LocalModelRuntimeLog.write(
                    "provider_round_liveness",
                    "request=" + providerRequestId + " display=" + this.displayId
                        + " | round=" + this.providerRoundCount
                        + " | elapsed_ms=" + elapsed
                        + " | first_text=" + (this.firstProviderTextAtMillis > 0L)
                );
                scheduleProviderLivenessSignal(providerRequestId, roundStartedAtMillis, delaySeconds);
            }, CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS));
        }

        private void scheduleProviderRoundHeartbeat(
            UUID providerRequestId,
            long roundStartedAtMillis,
            long delaySeconds,
            String summary
        ) {
            CompletableFuture.runAsync(() -> {
                if (this.terminal.get()
                    || !providerRequestId.equals(this.activeProviderRoundId.get())
                    || this.activeProviderRoundStartedAtMillis != roundStartedAtMillis) {
                    return;
                }
                long elapsed = Math.max(0L, System.currentTimeMillis() - roundStartedAtMillis);
                ModelGenerationHudState.appendEvent(
                    this.displayId,
                    ModelGenerationHudState.ActivityEventType.STATUS,
                    summary + " (" + (elapsed / 1000L) + "s)"
                );
                LocalModelRuntimeLog.write(
                    "provider_round_heartbeat",
                    "request=" + providerRequestId + " display=" + this.displayId
                        + " | round=" + this.providerRoundCount
                        + " | elapsed_ms=" + elapsed
                        + " | first_text=" + (this.firstProviderTextAtMillis > 0L)
                );
            }, CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS));
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
            queueControlGuidance(
                "Answer now from the useful work already completed. Do not perform more optional analysis. "
                    + "State limitations and unresolved uncertainty truthfully."
            );
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
            double parameters = selectedModelParametersBillions();
            if (parameters <= 3.5D) {
                return requestMode == RequestMode.AUTOMATION ? 16 * 1024 : 12 * 1024;
            }
            if (parameters <= 8.0D) {
                return requestMode == RequestMode.AUTOMATION ? 24 * 1024 : 20 * 1024;
            }
            return 32 * 1024;
        }

        private void archiveVisibleProviderRound(int completedRound) {
            ModelGenerationHudState.Snapshot snapshot = ModelGenerationHudState.snapshot(this.displayId);
            if (snapshot == null || snapshot.text() == null || snapshot.text().isBlank()) {
                return;
            }
            String priorText = snapshot.text();
            com.google.gson.JsonObject data = new com.google.gson.JsonObject();
            data.addProperty("providerRound", Math.max(1, completedRound));
            data.addProperty("stream", "model_draft_round");
            data.addProperty("exposedTag", "MODEL DRAFT");
            data.addProperty("nativeChannel", "visible_text");
            data.addProperty("intermediate", true);
            ModelGenerationHudState.upsertEvent(
                this.displayId,
                ModelGenerationHudState.ActivityEventType.THOUGHT_SUMMARY,
                "model-output-round-" + this.displayId + "-" + Math.max(1, completedRound),
                priorText,
                data
            );
            ModelGenerationHudState.replaceText(this.displayId, "");
        }

        private String agentReasoningStatusDetail() {
            return switch (this.agentReasoningDecision.mode()) {
                case INSTANT, DIRECT -> "preparing direct answer";
                case RESOLVE -> this.agentReasoningDecision.targets().contains(AgentReasoningController.Target.IDENTIFIER_RESOLUTION)
                    ? "resolving exact identifier" : "resolving ambiguity";
                case INVESTIGATE -> "investigating unresolved evidence";
                case PLAN -> "structuring bounded plan";
                case EXECUTE -> "choosing next concrete action";
                case VERIFY -> "verifying consequential result";
                case RECOVER -> "diagnosing observed failure";
            };
        }

        private String continuationPrefillDetail() {
            if (this.formattingCorrectionActive) {
                return "final response correction continuation";
            }
            if (this.toolResultsReceived > 0) {
                return "tool result continuation";
            }
            if (!this.internalReasoningTurns.isEmpty()) {
                return "model reasoning continuation";
            }
            if (!this.previousAssistantDraft.isBlank()) {
                return "model draft correction";
            }
            return "model continuation";
        }

        private void handleResponse(StreamingModelResponse response) {
            if (this.terminal.get()) {
                return;
            }
            response = normalizeReasoningResponse(response);
            if (response != null && response.reasoningOnly()) {
                continueModelReasoning(response.reasoningText());
                return;
            }
            if (response != null && (!response.text().isBlank() || !response.toolCalls().isEmpty())) {
                clearReasoningContinuationState();
                this.previousAssistantDraft = "";
            }
            if (response != null && response.text().isBlank() && response.toolCalls().isEmpty()) {
                if (noFailExecutionRequired()) {
                    this.emptyResponseCorrections++;
                    continueNoFail("empty_provider_response", "");
                } else if (this.emptyResponseCorrections++ < 2) {
                    queueControlGuidance(
                        "The provider returned no visible answer or tool call. Return one concise non-empty answer, "
                            + "or one valid supported tool call if this mode permits tools."
                    );
                    ModelGenerationHudState.state(this.displayId, ModelRequestState.RETRYING, "repairing empty response");
                    submitGeneration();
                } else {
                    fail("empty_response", "The provider repeatedly returned no visible answer or tool call.", null);
                }
                return;
            }
            if (response == null) {
                fail("empty_response", "The provider returned no response object.", null);
                return;
            }
            if (response.toolCalls().isEmpty() && containsRawToolProtocol(response.text())) {
                archiveVisibleSummary("");
                ModelGenerationHudState.replaceText(this.displayId, "");
                if (this.toolProtocolCorrectionCount++ < 2) {
                    queueControlGuidance(
                        "Your previous output printed native tool-call protocol as assistant text instead of emitting a structured registered tool call. "
                            + "Do not print <|tool_call_start|>, <|tool_call_end|>, <tool_call>, function syntax, or tool JSON as prose. "
                            + "Emit the intended supplied tool through the provider's structured tool-call channel now."
                    );
                    ModelGenerationHudState.state(
                        this.displayId, ModelRequestState.RETRYING, "recovering unparsed tool-call protocol"
                    );
                    submitGeneration();
                } else if (noFailExecutionRequired()) {
                    continueNoFail("unparsed_tool_protocol_repeated", "Choose a changed valid registered tool call; do not print tool syntax as prose.");
                } else {
                    fail(
                        "unparsed_tool_protocol",
                        "The model repeatedly emitted tool-call protocol as visible text instead of a structured tool call.",
                        null
                    );
                }
                return;
            }
            String responseFingerprint = responseFingerprint(response);
            int repeatedResponse = this.repeatedResponses.merge(responseFingerprint, 1, Integer::sum);
            if (repeatedResponse > MAXIMUM_IDENTICAL_RESPONSES) {
                com.google.gson.JsonObject signal = new com.google.gson.JsonObject();
                signal.addProperty("repeatCount", repeatedResponse);
                signal.addProperty("terminated", false);
                ModelGenerationHudState.upsertEvent(
                    this.displayId,
                    ModelGenerationHudState.ActivityEventType.MODEL_DATA,
                    "repeated-model-output-" + this.displayId,
                    "Repeated model output detected; Koil kept the session alive and left safety/action guards in control.",
                    signal
                );
                LocalModelRuntimeLog.write(
                    "repeated_model_output",
                    this.displayId + " | count=" + repeatedResponse + " | non_terminal=true"
                );
            }
            LocalModelRuntimeLog.write(
                "response_summary",
                this.mode.name().toLowerCase(java.util.Locale.ROOT)
                    + " | text_chars=" + response.text().length()
                    + " | reasoning_chars=" + response.reasoningText().length()
                    + " | tools=" + response.toolCalls().size()
                    + " | finish=" + response.providerFinishReason()
            );
            if (this.mode == RequestMode.ASK_DEEP) {
                handleDeepThoughtResponse(response);
                return;
            }
            if (this.mode == RequestMode.AUTOMATION
                && this.directInformationDecisionRound
                && response.toolCalls().isEmpty()
                && this.toolResultsReceived == 0
                && this.groundedAskCorrectionCount++ < 1) {
                archiveVisibleSummary(response.text());
                rememberAssistantDraft(response.text());
                queueControlGuidance(
                    "The compact read-only evidence round returned prose without evidence. Call exactly one supplied read-only tool now using the smallest valid arguments. Do not claim completion from memory."
                );
                ModelGenerationHudState.state(
                    this.displayId,
                    ModelRequestState.RETRYING,
                    "requesting one read-only evidence call"
                );
                submitGeneration();
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
                    rememberAssistantDraft(response.text());
                    queueControlGuidance(
                        "The compact evidence round returned prose without evidence. Call exactly one supplied read-only tool now, using the smallest valid arguments. Do not claim an action or answer from memory."
                    );
                    ModelGenerationHudState.state(
                        this.displayId,
                        ModelRequestState.RETRYING,
                        "requesting one read-only evidence call"
                    );
                    submitGeneration();
                    return;
                }
                final String validatedResponseText = response.text();
                DirectResponseIntentGuard.Validation intentValidation = this.mode == RequestMode.ASK
                    ? DirectResponseIntentGuard.validateDirectAnswer(this.prompt, validatedResponseText)
                    : DirectResponseIntentGuard.Validation.accept();
                if (!intentValidation.valid()) {
                    if (this.directIntentCorrectionCount++ < 1) {
                        archiveVisibleSummary(validatedResponseText);
                        rememberAssistantDraft(validatedResponseText);
                        queueControlGuidance(DirectResponseIntentGuard.correctionDirective(this.prompt));
                        this.forceNoNativeReasoningNextRound = true;
                        ModelGenerationHudState.state(
                            this.displayId, ModelRequestState.RETRYING, "correcting response intent drift"
                        );
                        submitGeneration();
                    } else {
                        fail(
                            "direct_response_intent_drift",
                            "The model repeatedly answered with unrequested runtime/system metadata instead of the user's request.",
                            null
                        );
                    }
                    return;
                }
                validateAskCommandResponse(this.prompt, validatedResponseText).whenComplete((validation, failure) -> {
                    if (this.terminal.get()) {
                        return;
                    }
                    if (failure != null) {
                        continueMalformedAskCommand(
                            validatedResponseText,
                            "The active Minecraft command tree could not be checked: " + message(failure)
                        );
                    } else if (!validation.valid()) {
                        continueMalformedAskCommand(validatedResponseText, validation.detail());
                    } else {
                        complete(validatedResponseText);
                    }
                });
                return;
            }
            if (response.toolCalls().isEmpty()) {
                if (this.mode == RequestMode.AUTOMATION
                    && (!this.objectiveLedger.allCompleted()
                    || (this.toolResultsReceived > 0 && promisesUnexecutedAction(response.text())))) {
                    continueUnresolvedObjective(response.text());
                    return;
                }
                complete(response.text());
                return;
            }
            archiveVisibleSummary(response.text());
            if (this.mode == RequestMode.AUTOMATION
                && !this.toolBudgetAdvisoryEmitted
                && this.toolCallCount + response.toolCalls().size() > Math.max(1, this.thinking.maximumToolCalls())) {
                this.toolBudgetAdvisoryEmitted = true;
                com.google.gson.JsonObject budget = new com.google.gson.JsonObject();
                budget.addProperty("recommendedToolCalls", Math.max(1, this.thinking.maximumToolCalls()));
                budget.addProperty("observedToolCalls", this.toolCallCount + response.toolCalls().size());
                budget.addProperty("enforced", false);
                budget.addProperty("orderedTasksTotal", this.objectiveLedger.totalTaskCount());
                ModelGenerationHudState.upsertEvent(
                    this.displayId,
                    ModelGenerationHudState.ActivityEventType.MODEL_DATA,
                    "automation-tool-budget-" + this.displayId,
                    "Automation passed its recommended tool-call depth; Koil is continuing because ordered tasks remain and execution is still making progress.",
                    budget
                );
            }

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
            if (this.finalToolSummaryRequested && this.objectiveLedger.allCompleted()
                    && !response.toolCalls().isEmpty()) {
                LocalModelRuntimeLog.write(
                    "completed_objective_tool_suppressed",
                    this.displayId + " | rejected=" + response.toolCalls().stream()
                            .map(ModelToolCall::toolId).collect(java.util.stream.Collectors.joining(","))
                );
                this.tools = List.of();
                queueControlGuidance(
                    "The requested automation objective is already complete according to verified structured tool results. "
                            + "Do not request or repeat any tool. Produce only the final user-facing response from the recorded result."
                );
                submitGeneration();
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
            if (calls.size() > 1) {
                ModelToolCall selected = selectGroundedAskToolCall(calls);
                LocalModelRuntimeLog.write(
                    "grounded_ask_tool_batch",
                    this.displayId + " | received=" + calls.size() + " | selected=" + selected.toolId()
                );
                calls = List.of(selected);
            }
            ModelToolCall proposedCall = selectGroundedAskToolCall(calls);
            boolean supplied = this.tools.stream().anyMatch(tool -> tool.id().equals(proposedCall.toolId()));
            if (!supplied || !DeepThoughtReadOnlyToolCoordinator.supports(proposedCall.toolId())) {
                fail("ask_tool_boundary", "Grounded /ask requested an unsupported or side-effecting tool: " + proposedCall.toolId(), null);
                return;
            }
            ModelToolCall call = resolveToolCallOrCorrect(proposedCall, "grounded ask");
            if (call == null) return;
            String signature = call.toolId() + ":" + call.arguments();
            if (this.repeatedCalls.merge(signature, 1, Integer::sum) > 1) {
                fail("repeated_tool_loop", "Grounded /ask repeated the identical read-only lookup without a new observation.", null);
                return;
            }
            AutomationProgressGuard.Decision groundedProgress = this.automationProgress.before(call);
            if (!groundedProgress.allowed()) {
                this.groundedAskFinalizing = true;
                queueControlGuidance(
                    "The same read-only lookup would repeat without new evidence (" + groundedProgress.reason() + "). "
                        + "Do not repeat it. Give the best honest answer from the evidence already returned, "
                        + "or use a different supplied read-only tool/arguments only when it resolves a specific remaining fact."
                );
                ModelGenerationHudState.state(
                    this.displayId, ModelRequestState.FINALIZING, "stopping repeated no-progress lookup"
                );
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
            eventData.add("argumentProvenance", argumentProvenance(call));
            String skillExecutionSummary = decorateSkillExecution(call, eventData);
            ModelGenerationHudState.appendEvent(this.displayId, new ModelExecutionEvent(
                this.displayId,
                this.displayId.toString(),
                "grounded-tool-" + call.id(),
                ModelExecutionEvent.Type.TOOL_STARTED,
                ModelRequestState.INSPECTING,
                ModelToolActivityPresentation.activity(call).state(),
                skillExecutionSummary.isBlank() ? groundedActivitySummary(call) : skillExecutionSummary,
                eventData,
                System.currentTimeMillis()
            ));
            beginToolTelemetry(call);
            DeepThoughtReadOnlyToolCoordinator.execute(this.displayId, call).whenComplete((result, failure) -> {
                if (this.terminal.get()) return;
                ModelToolResult resolved = failure == null ? result : new ModelToolResult(
                    call.id(), call.toolId(), "failed", new com.google.gson.JsonObject(),
                    "knowledge_lookup_failed", message(failure)
                );
                this.toolResultsReceived++;
                recordToolResult(resolved);
                AutomationProgressGuard.Observation groundedObservation = this.automationProgress.record(call, resolved);
                if (groundedObservation.newObservation() || groundedObservation.stateChanged()) {
                    this.repeatedResponses.clear();
                }
                boolean terminalEvidence = terminalGroundedEvidence(resolved);
                this.groundedAskFinalizing = terminalEvidence;
                String instruction = terminalEvidence
                    ? "The read-only source returned an authoritative terminal result. Do not retry, substitute, or invent a nearby target. Give one compact honest answer preserving the exact requested identifier and evidence."
                    : "Use the exact structured evidence above. Give the compact final answer now, or make one changed read-only lookup only if a specific unanswered fact is necessary. Never claim an action occurred.";
                queueControlGuidance(instruction);
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
            rememberAssistantDraft(response.text());
            boolean finalize = this.deepThought.acceptRoundSummary(visiblePlanSummary(response.text()));
            if (finalize) {
                queueControlGuidance(this.deepThought.instruction());
            } else {
                queueControlGuidance(
                    "Continue the evidence-driven investigation with only this next task: "
                        + this.deepThought.instruction()
                );
            }
            submitGeneration();
        }

        private void handleDeepThoughtToolCalls(String assistantText, List<ModelToolCall> calls, int index) {
            if (index >= calls.size()) {
                queueControlGuidance(
                    "Interpret the returned read-only evidence without claiming any side effect. "
                        + this.deepThought.instruction()
                );
                submitGeneration();
                return;
            }
            ModelToolCall proposedCall = calls.get(index);
            ModelToolCall call = resolveToolCallOrCorrect(proposedCall, "deep-thought evidence");
            if (call == null) return;
            AutomationProgressGuard.Decision evidenceProgress = this.automationProgress.before(call);
            if (!evidenceProgress.allowed()) {
                queueControlGuidance(
                    "The same evidence lookup would repeat without new information (" + evidenceProgress.reason() + "). "
                        + "Choose a changed read-only lookup that can resolve a specific open question, or preserve the uncertainty and continue/finalize without repeating the call. "
                        + this.deepThought.instruction()
                );
                ModelGenerationHudState.state(this.displayId, ModelRequestState.RETRYING, "avoiding repeated evidence lookup");
                submitGeneration();
                return;
            }
            this.conversation.add(ModelMessage.assistantToolCall(index == 0 ? assistantText : "", call));
            ModelGenerationHudState.state(this.displayId, ModelRequestState.INSPECTING, call.toolId());
            com.google.gson.JsonObject eventData = new com.google.gson.JsonObject();
            eventData.addProperty("toolId", call.toolId());
            eventData.add("arguments", call.arguments());
            eventData.add("argumentProvenance", argumentProvenance(call));
            String skillExecutionSummary = decorateSkillExecution(call, eventData);
            ModelGenerationHudState.appendEvent(this.displayId, new ModelExecutionEvent(
                    this.displayId,
                    this.displayId.toString(),
                    "deep-thought-tool-" + call.id(),
                    ModelExecutionEvent.Type.TOOL_STARTED,
                    ModelRequestState.INSPECTING,
                    ModelToolActivityPresentation.activity(call).state(),
                    skillExecutionSummary.isBlank() ? groundedActivitySummary(call) : skillExecutionSummary,
                    eventData,
                    System.currentTimeMillis()
            ));
            beginToolTelemetry(call);
            DeepThoughtReadOnlyToolCoordinator.execute(this.displayId, call).whenComplete((result, failure) -> {
                ModelToolResult resolved = failure == null ? result : new ModelToolResult(
                    call.id(), call.toolId(), "failed", new com.google.gson.JsonObject(),
                    "deep_thought_tool_failed", message(failure)
                );
                recordToolResult(resolved);
                AutomationProgressGuard.Observation evidenceObservation = this.automationProgress.record(call, resolved);
                if (evidenceObservation.newObservation() || evidenceObservation.stateChanged()) {
                    this.repeatedResponses.clear();
                }
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
            String skillExecutionSummary = decorateSkillExecution(planCall, eventData);
            ModelGenerationHudState.appendEvent(this.displayId, new ModelExecutionEvent(
                    this.displayId,
                    this.displayId.toString(),
                    "plan-tool-" + planCall.id(),
                    ModelExecutionEvent.Type.TOOL_STARTED,
                    ModelRequestState.VALIDATING_PLAN,
                    ModelActivityState.VALIDATING,
                    skillExecutionSummary.isBlank() ? "Validating the structured Automation plan" : skillExecutionSummary,
                    eventData,
                    System.currentTimeMillis()
            ));
            AutomationModeController.setPlanningActive(true);
            beginToolTelemetry(planCall);
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
                        ModelObjectiveLedger.PlanAlignment alignment = this.objectiveLedger.alignPlan(this.validatedPlan);
                        if (!alignment.valid()) {
                            this.validatedPlan = null;
                            requestPlanRevision(alignment.detail(), "", List.of());
                            return;
                        }
                        this.agentState.adoptPlan(this.validatedPlan);
                        this.durableState.plan(this.validatedPlan.id());
                        String planToolSpan = TelemetryStore.latestSpan(
                                this.displayId, TelemetrySpanKind.TOOL_INVOCATION, "call_id", planCall.id());
                        this.automationPlanTelemetrySpanId = TelemetryStore.beginSpan(
                                this.displayId, planToolSpan, TelemetrySpanKind.AUTOMATION_PLAN,
                                "automation plan " + this.validatedPlan.id(),
                                Map.of("plan_id", this.validatedPlan.id(),
                                        "segment_index", Integer.toString(this.planSegmentCount + 1),
                                        "step_count", Integer.toString(this.validatedPlan.steps().size())));
                        TelemetryStore.provenance(this.displayId, this.automationPlanTelemetrySpanId,
                                "automation.plan", "AutomationPlanModelToolRegistry", "planning",
                                result.output().toString(), "Validated structured automation plan");
                    } catch (RuntimeException invalid) {
                        fail("invalid_plan_result", message(invalid), invalid);
                        return;
                    }
                    this.planPhase = PlanPhase.REVIEW;
                    this.agentState.setPlanStatus(AgentState.PlanStatus.REVIEW, "validated and awaiting review");
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
                        this.agentState.setPlanStatus(AgentState.PlanStatus.REJECTED, "player rejected plan");
                        if (!this.automationPlanTelemetrySpanId.isBlank()) {
                            TelemetryStore.finishSpan(this.displayId, this.automationPlanTelemetrySpanId,
                                    TelemetryCapabilityState.CANCELLED, "plan_rejected", "Player rejected the reviewed plan.");
                            this.automationPlanTelemetrySpanId = "";
                        }
                        for (ValidatedAutomationPlan.Step step : plan.steps()) {
                            ModelGenerationHudState.updatePlanStep(
                                this.displayId,
                                step.index(),
                                ModelGenerationHudState.PlanStepStatus.REVISED,
                                "not executed"
                            );
                            this.agentState.markPlanStep(step.index(), AgentState.PlanStepStatus.REVISED, "not executed");
                        }
                        ModelGenerationHudState.markPlanRevised(this.displayId);
                        ModelGenerationHudState.appendEvent(
                            this.displayId,
                            ModelGenerationHudState.ActivityEventType.REPLAN,
                            "The player rejected " + plan.id() + "; no plan step executed."
                        );
                        if (this.planRevisionCount >= 2) {
                            AutomationModeController.setPlanningActive(false);
                            finishSystemStatus(
                                    ModelRequestState.CANCELLED,
                                    "Automation stopped because the reviewed plan was rejected. No action was executed."
                            );
                            return;
                        }
                        this.planRevisionCount++;
                        this.validatedPlan = null;
                        this.durableState.clearPlan();
                        this.agentState.clearPlan("rejected plan released for revision");
                        queueControlGuidance(
                            "The player rejected the proposed plan. No step executed. "
                                + "You may submit one revised automation.plan with a changed, bounded approach, "
                                + "or stop truthfully without action."
                        );
                        submitGeneration();
                        return;
                    }
                    this.planPhase = PlanPhase.APPROVED;
                    this.agentState.setPlanStatus(AgentState.PlanStatus.APPROVED, "player approved exact validated steps");
                    if (!this.automationPlanTelemetrySpanId.isBlank()) {
                        TelemetryStore.annotate(this.displayId, this.automationPlanTelemetrySpanId, "approval", "approved");
                        TelemetryStore.event(this.displayId, this.automationPlanTelemetrySpanId,
                                "plan_approved", "Player approved the exact validated steps.");
                    }
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
                this.planSegmentCount++;
                boolean parentComplete = this.objectiveLedger.allCompleted();
                this.agentState.setPlanStatus(
                    AgentState.PlanStatus.COMPLETED,
                    parentComplete ? "all approved steps and ordered tasks completed" : "plan segment completed; parent objective remains"
                );
                if (!this.automationPlanTelemetrySpanId.isBlank()) {
                    TelemetryStore.finishSpan(
                        this.displayId, this.automationPlanTelemetrySpanId, TelemetryCapabilityState.AVAILABLE,
                        parentComplete ? "completed" : "segment_completed",
                        parentComplete
                            ? "Every approved plan step reached a structured result and all ordered tasks are complete."
                            : "Every approved plan step reached a structured result; later ordered tasks remain."
                    );
                    this.automationPlanTelemetrySpanId = "";
                }
                AutomationModeController.setPlanningActive(false);
                ModelGenerationHudState.appendEvent(
                    this.displayId,
                    ModelGenerationHudState.ActivityEventType.RESULT,
                    "Every step in " + plan.id() + " reached a structured result."
                        + (parentComplete ? "" : " The parent objective still has ordered tasks remaining.")
                );
                if (parentComplete) {
                    this.planPhase = PlanPhase.COMPLETED;
                } else {
                    this.planRevisionCount = 0;
                    this.planPhase = PlanPhase.NONE;
                    this.validatedPlan = null;
                    this.durableState.clearPlan();
                    this.planAuthorization = null;
                    this.agentState.clearPlan("completed plan segment; continuing ordered task ledger");
                    queueControlGuidance(
                        "The approved plan segment completed successfully, but the parent objective is not complete. "
                            + "Continue from the ordered task ledger at task " + this.objectiveLedger.currentTaskIndex()
                            + "/" + this.objectiveLedger.totalTaskCount() + ": " + this.objectiveLedger.currentTaskText() + ". "
                            + "Do not repeat completed tasks. If another side-effecting plan is useful or Planning Mode requires it, submit the next bounded automation.plan segment only for the remaining ordered work."
                    );
                }
                submitGeneration();
                return;
            }
            if (this.cancellation.isCancellationRequested()) {
                fail("cancelled", this.cancellation.cancellationReason(), null);
                return;
            }
            ValidatedAutomationPlan.Step step = plan.steps().get(position);
            ModelToolCall proposedCall = step.asToolCall(plan.id());
            ToolArgumentResolver.ExecutionResolution planBinding = resolveToolArguments(proposedCall);
            if (!planBinding.executable()) {
                fail(
                    "plan_step_argument_binding_failed",
                    "The approved plan step no longer has grounded executable arguments: " + String.join("; ", planBinding.blockers()),
                    null
                );
                return;
            }
            ModelToolCall call = planBinding.call();
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
            this.agentState.setPlanStatus(AgentState.PlanStatus.EXECUTING, "executing approved plan");
            this.agentState.markPlanStep(step.index(), AgentState.PlanStepStatus.ACTIVE, "");
            com.google.gson.JsonObject toolEventData = new com.google.gson.JsonObject();
            toolEventData.addProperty("toolId", call.toolId());
            toolEventData.add("arguments", call.arguments());
            toolEventData.add("argumentProvenance", argumentProvenance(call));
            String skillExecutionSummary = decorateSkillExecution(call, toolEventData);
            ModelGenerationHudState.appendEvent(this.displayId, new ModelExecutionEvent(
                this.displayId,
                plan.id(),
                "tool-" + call.id(),
                ModelExecutionEvent.Type.TOOL_STARTED,
                ModelRequestState.EXECUTING_TOOL,
                ModelToolActivityPresentation.activity(call).state(),
                skillExecutionSummary.isBlank()
                        ? stepLabel(step.index(), plan.steps().size(), step.toolId())
                        : skillExecutionSummary,
                toolEventData,
                System.currentTimeMillis()
            ));
            this.conversation.add(ModelMessage.assistantToolCall("", call));
            ModelGenerationHudState.state(this.displayId, ModelRequestState.EXECUTING_TOOL, step.toolId());
            AutomationModeController.executing("tool: " + step.toolId());
            beginToolTelemetry(call);
            AutomationToolCoordinator.execute(this.displayId, call, true)
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        ModelToolResult failedResult = new ModelToolResult(
                                call.id(), call.toolId(), "failed", new com.google.gson.JsonObject(),
                                "tool_future_failed", message(failure));
                        this.toolResultsReceived++;
                        recordToolResult(failedResult);
                        handleApprovedPlanFailure(step, failedResult, failedResult.detail());
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
                        this.agentState.markPlanStep(step.index(), AgentState.PlanStepStatus.COMPLETED, result.status());
                        executeApprovedPlanStep(position + 1);
                    } else {
                        if (terminalToolFailure(result)) {
                            ModelGenerationHudState.updatePlanStep(
                                this.displayId,
                                step.index(),
                                ModelGenerationHudState.PlanStepStatus.BLOCKED,
                                result.detail()
                            );
                            this.agentState.markPlanStep(step.index(), AgentState.PlanStepStatus.BLOCKED, result.detail());
                            this.agentState.setPlanStatus(AgentState.PlanStatus.FAILED, result.detail());
                            AutomationModeController.setPlanningActive(false);
                            finishSystemStatus(
                                    ModelRequestState.FAILED,
                                    "Automation stopped after a terminal tool failure: "
                                            + abbreviate(result.detail().isBlank() ? result.failureCode() : result.detail(), 500)
                            );
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
            this.agentState.setPlanStatus(AgentState.PlanStatus.FAILED, detail);
            String safeDetail = detail == null || detail.isBlank()
                ? result == null ? "step failed" : result.status()
                : detail;
            String evidenceFingerprint = step.id() + "|" + resultProgressFingerprint(result);
            if (evidenceFingerprint.equals(this.lastReplanEvidenceFingerprint)) {
                AutomationModeController.setPlanningActive(false);
                finishSystemStatus(
                        ModelRequestState.FAILED,
                        "Automation stopped because the same failed plan step produced no new evidence. No replacement action was executed."
                );
                return;
            }
            this.lastReplanEvidenceFingerprint = evidenceFingerprint;
            ModelGenerationHudState.updatePlanStep(
                this.displayId,
                step.index(),
                ModelGenerationHudState.PlanStepStatus.FAILED,
                safeDetail
            );
            this.agentState.markPlanStep(step.index(), AgentState.PlanStepStatus.FAILED, safeDetail);
            if (this.validatedPlan != null) {
                for (ValidatedAutomationPlan.Step remaining : this.validatedPlan.steps()) {
                    if (remaining.index() > step.index()) {
                        ModelGenerationHudState.updatePlanStep(this.displayId, remaining.index(),
                            ModelGenerationHudState.PlanStepStatus.REVISED, "invalidated by changed observation");
                        this.agentState.markPlanStep(remaining.index(), AgentState.PlanStepStatus.REVISED, "invalidated by changed observation");
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
            this.durableState.clearPlan();
            this.agentState.clearPlan("failed plan invalidated for revision");
            this.planAuthorization = null;
            queueControlGuidance(
                "The approved plan stopped at step " + step.index() + " with: "
                    + abbreviate(safeDetail, 500)
                    + ". Do not improvise another side effect. Submit a revised automation.plan "
                    + "with a changed supported approach, or stop with the exact limitation."
            );
            submitGeneration();
        }

        private void requestPlanRevision(
            String reason,
            String assistantText,
            List<ModelToolCall> rejectedCalls
        ) {
            if (this.planRevisionCount >= 2) {
                AutomationModeController.setPlanningActive(false);
                finishSystemStatus(
                        ModelRequestState.FAILED,
                        "Automation stopped because the requested work departed from the reviewed plan."
                );
                return;
            }
            this.planRevisionCount++;
            AutomationModeController.setPlanningActive(true);
            this.planPhase = PlanPhase.REVISION_REQUIRED;
            this.agentState.setPlanStatus(AgentState.PlanStatus.REVISION_REQUIRED, reason);
            if (assistantText != null && !assistantText.isBlank()) {
                rememberAssistantDraft(assistantText);
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
            this.durableState.clearPlan();
            this.agentState.clearPlan("plan revision required: " + reason);
            this.planAuthorization = null;
            queueControlGuidance(
                reason + " Submit automation.plan alone with all intended ordered side effects. "
                    + "The revised plan must be reviewed before execution."
            );
            ModelGenerationHudState.state(this.displayId, ModelRequestState.REPLANNING, "replanning");
            submitGeneration();
        }

        private void requestToolBatchApproval(String assistantText, List<ModelToolCall> calls) {
            List<ModelToolCall> groundedCalls = resolveToolBatchOrCorrect(calls, "action approval");
            if (groundedCalls == null) return;
            calls = groundedCalls;
            final List<ModelToolCall> approvedCalls = calls;
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
                        handleToolCalls(assistantText, approvedCalls, 0, true);
                    } else {
                        recordRejectedToolBatch(assistantText, approvedCalls);
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
                if (tryExecuteDeterministicOrderedTaskFrontier("completed tool batch")) return;
                if (completeOrderedObjectiveWithoutProviderIfDone()) return;
                submitGeneration();
                return;
            }
            int parallelReadEnd = parallelReadOnlyWindowEnd(calls, index);
            if (parallelReadEnd - index >= 2) {
                handleParallelReadOnlyToolCalls(assistantText, calls, index, parallelReadEnd, preapproved);
                return;
            }

            ModelToolCall proposedCall = calls.get(index);
            ModelToolCall call = resolveToolCallOrCorrect(proposedCall, "automation execution");
            if (call == null) return;
            ModelObjectiveLedger.ExecutionGate taskGate = this.objectiveLedger.gate(call.toolId());
            if (!taskGate.allowed()) {
                this.conversation.add(ModelMessage.assistantToolCall(index == 0 ? assistantText : "", call));
                ModelToolResult outOfOrder = new ModelToolResult(
                    call.id(), call.toolId(), "rejected", new com.google.gson.JsonObject(),
                    "ordered_task_not_ready", taskGate.reason()
                );
                recordToolResult(outOfOrder);
                queueControlGuidance(
                    "The requested action was not executed because it belongs to a later ordered task. "
                        + "Complete task " + taskGate.currentTask() + "/" + this.objectiveLedger.totalTaskCount()
                        + " first: " + this.objectiveLedger.currentTaskText() + ". "
                        + "Do not skip, merge, or reorder explicit user tasks."
                );
                ModelGenerationHudState.state(this.displayId, ModelRequestState.RETRYING, "preserving ordered task sequence");
                submitGeneration();
                return;
            }

            // Approval must happen before the progress guard observes an action.
            // Otherwise a deterministic/fast-path call can be recorded as an
            // attempted occurrence when the coordinator merely returns
            // approval_required, and the later approved execution is then
            // misclassified as same_action_same_observation.
            //
            // Keep one approval surface: the existing formatted Automation
            // approval card owned by LocalModelService. The coordinator remains
            // the deny-by-default backstop for callers that bypass this path.
            if (!preapproved
                    && !AutomationModeController.isUnrestrictedMode()
                    && isActionTool(call.toolId())) {
                requestToolBatchApproval(index == 0 ? assistantText : "", List.of(call));
                return;
            }

            AutomationProgressGuard.Decision progressDecision = this.automationProgress.before(call);
            if (!progressDecision.allowed()) {
                if (noFailExecutionRequired()) {
                    this.conversation.add(ModelMessage.assistantToolCall(index == 0 ? assistantText : "", call));
                    ModelToolResult noProgressResult = new ModelToolResult(
                        call.id(), call.toolId(), "rejected", new com.google.gson.JsonObject(),
                        "no_progress", "Identical action rejected against unchanged observation: " + progressDecision.reason()
                    );
                    recordToolResult(noProgressResult);
                    String recoveryHint = this.executionContext == null ? "" : this.executionContext.recoveryHint(call, noProgressResult);
                    continueNoFail("identical_tool_call_without_new_observation", recoveryHint);
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
            toolEventData.add("argumentProvenance", argumentProvenance(call));
            String skillExecutionSummary = decorateSkillExecution(call, toolEventData);
            ModelGenerationHudState.appendEvent(this.displayId, new ModelExecutionEvent(
                this.displayId,
                this.validatedPlan == null ? this.displayId.toString() : this.validatedPlan.id(),
                "tool-" + call.id(),
                ModelExecutionEvent.Type.TOOL_STARTED,
                ModelRequestState.EXECUTING_TOOL,
                ModelToolActivityPresentation.activity(call).state(),
                skillExecutionSummary.isBlank()
                        ? stepLabel(index + 1, calls.size(), call.toolId())
                        : skillExecutionSummary,
                toolEventData,
                System.currentTimeMillis()
            ));
            AutomationModeController.executing("tool: " + call.toolId());
            LocalModelRuntimeLog.write(
                "tool_start",
                this.displayId + " | step=" + (index + 1) + "/" + calls.size() + " | " + call.toolId()
            );
            beginToolTelemetry(call);
            (this.executionContext == null
                    ? AutomationToolCoordinator.execute(this.displayId, call, preapproved)
                    : this.executionContext.execute(this.displayId, call, preapproved)).whenComplete((toolResult, failure) -> {
                if (failure != null) {
                    if (noFailExecutionRequired()) {
                        ModelToolResult failedResult = new ModelToolResult(
                            call.id(), call.toolId(), "failed", new com.google.gson.JsonObject(),
                            "tool_execution_failed", message(failure)
                        );
                        this.toolResultsReceived++;
                        recordToolResult(failedResult);
                        if (this.executionContext != null) {
                            this.executionContext.record(call, failedResult, this.objectiveLedger.allCompleted());
                        }
                        this.automationProgress.record(call, failedResult);
                        String recoveryHint = this.executionContext == null
                            ? "" : this.executionContext.recoveryHint(call, failedResult);
                        continueNoFail("tool_execution_failed", recoveryHint);
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
                if (this.executionContext != null && progress.stateChanged()) {
                    this.executionContext.observationChanged();
                }
                if (this.executionContext != null) {
                    this.executionContext.record(call, toolResult, this.objectiveLedger.allCompleted());
                }
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
                            finishSystemStatus(
                                    ModelRequestState.FAILED,
                                    "Automation stopped after a terminal tool failure: "
                                            + abbreviate(toolResult.detail().isBlank() ? toolResult.failureCode() : toolResult.detail(), 500)
                            );
                        }
                        return;
                    }
                    String recoveryHint = this.executionContext == null ? "" : this.executionContext.recoveryHint(call, toolResult);
                    queueControlGuidance(
                        "The previous tool did not produce validated completion. Status: "
                            + toolResult.status() + "; failure: " + toolResult.failureCode()
                            + "; detail: " + abbreviate(toolResult.detail(), 500)
                            + ". Inspect the observation and choose a changed retry, a supported replan, or report the limitation."
                            + (recoveryHint.isBlank() ? "" : "\n" + abbreviate(recoveryHint, 900))
                    );
                    ModelGenerationHudState.state(this.displayId, ModelRequestState.OBSERVING_RESULT, "result requires recovery");
                    submitGeneration();
                }
            });
        }

        private int parallelReadOnlyWindowEnd(List<ModelToolCall> calls, int start) {
            if (calls == null || start < 0 || start >= calls.size()) return start;
            int end = start;
            while (end < calls.size()) {
                ModelToolCall call = calls.get(end);
                ModelToolDefinition definition = definitionForCall(call);
                if (definition == null
                        || !definition.sideEffects().isEmpty()
                        || definition.confirmationRequired()
                        || LocalModelToolCatalog.requiresFreshApproval(call.toolId())) {
                    break;
                }
                end++;
            }
            return end;
        }

        private ModelToolDefinition definitionForCall(ModelToolCall call) {
            if (call == null || call.toolId().isBlank()) return null;
            return LocalModelToolCatalog.definition(call.toolId())
                    .orElseGet(() -> this.tools.stream()
                            .filter(tool -> tool.id().equals(call.toolId()))
                            .findFirst().orElse(null));
        }

        /**
         * Executes one dependency-free read frontier concurrently. Results are
         * reconciled in original model order only after the whole frontier has
         * settled, so conversation history and progress accounting remain
         * deterministic while I/O latency overlaps. Mutating calls are never
         * admitted here.
         */
        private void handleParallelReadOnlyToolCalls(
                String assistantText,
                List<ModelToolCall> calls,
                int start,
                int endExclusive,
                boolean preapproved
        ) {
            List<ModelToolCall> resolved = new ArrayList<>();
            for (int position = start; position < endExclusive; position++) {
                ModelToolCall call = resolveToolCallOrCorrect(calls.get(position), "parallel read execution");
                if (call == null) return;
                ModelObjectiveLedger.ExecutionGate taskGate = this.objectiveLedger.gate(call.toolId());
                if (!taskGate.allowed()) {
                    queueControlGuidance(
                            "A parallel read was not executed because it belongs to a later ordered task. "
                                    + "Complete task " + taskGate.currentTask() + "/" + this.objectiveLedger.totalTaskCount()
                                    + " first: " + this.objectiveLedger.currentTaskText() + "."
                    );
                    submitGeneration();
                    return;
                }
                AutomationProgressGuard.Decision progressDecision = this.automationProgress.before(call);
                if (!progressDecision.allowed()) {
                    queueControlGuidance(
                            "An identical read was suppressed because it would not create a new observation: "
                                    + progressDecision.reason() + ". Use the evidence already collected or change the lookup."
                    );
                    submitGeneration();
                    return;
                }
                resolved.add(call);
            }

            List<CompletableFuture<ModelToolResult>> futures = new ArrayList<>();
            for (int offset = 0; offset < resolved.size(); offset++) {
                int position = start + offset;
                ModelToolCall call = resolved.get(offset);
                this.toolCallCount++;
                ModelGenerationHudState.toolCallCount(this.displayId, this.toolCallCount);
                ModelGenerationHudState.toolProgress(
                        this.displayId, position + 1, calls.size(), call.toolId(), toolActivityDetail(call));
                this.conversation.add(ModelMessage.assistantToolCall(
                        position == start ? assistantText : "", call));
                com.google.gson.JsonObject toolEventData = new com.google.gson.JsonObject();
                toolEventData.addProperty("toolId", call.toolId());
                toolEventData.addProperty("parallel", true);
                toolEventData.add("arguments", call.arguments());
                toolEventData.add("argumentProvenance", argumentProvenance(call));
                String skillExecutionSummary = decorateSkillExecution(call, toolEventData);
                ModelGenerationHudState.appendEvent(this.displayId, new ModelExecutionEvent(
                        this.displayId,
                        this.validatedPlan == null ? this.displayId.toString() : this.validatedPlan.id(),
                        "tool-" + call.id(),
                        ModelExecutionEvent.Type.TOOL_STARTED,
                        ModelRequestState.EXECUTING_TOOL,
                        ModelToolActivityPresentation.activity(call).state(),
                        skillExecutionSummary.isBlank()
                                ? stepLabel(position + 1, calls.size(), call.toolId()) + " · parallel read"
                                : skillExecutionSummary + " · parallel",
                        toolEventData,
                        System.currentTimeMillis()
                ));
                beginToolTelemetry(call);
                CompletableFuture<ModelToolResult> future = (this.executionContext == null
                        ? AutomationToolCoordinator.execute(this.displayId, call, preapproved)
                        : this.executionContext.execute(this.displayId, call, preapproved))
                        .handle((result, failure) -> failure == null ? result : new ModelToolResult(
                                call.id(), call.toolId(), "failed", new com.google.gson.JsonObject(),
                                "tool_execution_failed", message(failure)
                        ));
                futures.add(future);
            }
            ModelGenerationHudState.state(
                    this.displayId, ModelRequestState.EXECUTING_TOOL,
                    "running " + resolved.size() + " independent reads in parallel");

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, batchFailure) -> {
                if (this.terminal.get()) return;
                boolean allSatisfied = true;
                ModelToolResult firstFailure = null;
                int firstFailureIndex = -1;
                for (int offset = 0; offset < resolved.size(); offset++) {
                    ModelToolCall call = resolved.get(offset);
                    ModelToolResult result = futures.get(offset).join();
                    this.toolResultsReceived++;
                    if (toolSatisfied(result)) {
                        this.completedToolIds.add(call.toolId());
                        recordSuccessfulToolOutput(call.toolId(), result);
                    } else {
                        allSatisfied = false;
                        if (firstFailure == null) {
                            firstFailure = result;
                            firstFailureIndex = offset;
                        }
                    }
                    recordToolResult(result);
                    AutomationProgressGuard.Observation progress = this.automationProgress.record(call, result);
                    if (this.executionContext != null && progress.stateChanged()) {
                        this.executionContext.observationChanged();
                    }
                    if (this.executionContext != null) {
                        this.executionContext.record(call, result, this.objectiveLedger.allCompleted());
                    }
                    if (progress.newObservation() || progress.stateChanged()) this.repeatedResponses.clear();
                    LocalModelRuntimeLog.write(
                            "tool_result_parallel",
                            this.displayId + " | step=" + (start + offset + 1) + "/" + calls.size()
                                    + " | " + call.toolId() + " | status=" + result.status());
                }

                if (allSatisfied) {
                    handleToolCalls("", calls, endExclusive, preapproved);
                    return;
                }
                String recoveryHint = "";
                if (firstFailure != null && firstFailureIndex >= 0 && this.executionContext != null) {
                    recoveryHint = this.executionContext.recoveryHint(resolved.get(firstFailureIndex), firstFailure);
                }
                queueControlGuidance(
                        "One or more independent read tools did not complete successfully. Inspect all returned evidence, "
                                + "then retry only the failed lookup with changed arguments or continue with the successful evidence."
                                + (recoveryHint.isBlank() ? "" : "\n" + abbreviate(recoveryHint, 900))
                );
                ModelGenerationHudState.state(this.displayId, ModelRequestState.OBSERVING_RESULT,
                        "parallel read frontier requires recovery");
                submitGeneration();
            });
        }

        private com.google.gson.JsonObject argumentProvenance(ModelToolCall call) {
            com.google.gson.JsonObject output = new com.google.gson.JsonObject();
            if (call == null) return output;
            for (AgentState.ToolArgumentState binding : this.agentState.snapshot().argumentBindings()) {
                if (!call.id().equals(binding.callId())) continue;
                com.google.gson.JsonObject item = new com.google.gson.JsonObject();
                item.addProperty("source", binding.source().name().toLowerCase(java.util.Locale.ROOT));
                item.addProperty("sourceId", binding.sourceId());
                item.addProperty("confidence", binding.confidence());
                output.add(binding.argument(), item);
            }
            return output;
        }

        private ToolArgumentResolver.ExecutionResolution resolveToolArguments(ModelToolCall call) {
            ModelToolDefinition definition = LocalModelToolCatalog.definition(call == null ? "" : call.toolId())
                .orElseGet(() -> this.tools.stream()
                    .filter(tool -> call != null && tool.id().equals(call.toolId()))
                    .findFirst().orElse(null));
            boolean consequential = definition != null
                && (!definition.sideEffects().isEmpty()
                    || definition.confirmationRequired()
                    || LocalModelToolCatalog.requiresFreshApproval(definition.id()));
            String groundingObjective = call != null && this.objectiveLedger.shouldGroundAgainstCurrentTask(call.toolId())
                    ? this.objectiveLedger.currentTaskText()
                    : this.prompt;
            return ToolArgumentResolver.bindForExecution(
                this.agentState, call, groundingObjective, consequential
            );
        }

        private ModelToolCall resolveToolCallOrCorrect(ModelToolCall call, String phase) {
            ModelToolCallTolerance.Repair repair = ModelToolCallTolerance.repair(call, this.tools);
            if (repair.changed()) {
                LocalModelRuntimeLog.write(
                    "tool_call_repaired",
                    this.displayId + " | " + String.join(",", repair.changes())
                );
                call = repair.call();
            }
            ToolArgumentResolver.ExecutionResolution resolution = resolveToolArguments(call);
            if (resolution.executable()) {
                String provenance = resolution.compactProvenance();
                if (!provenance.isBlank()) {
                    LocalModelRuntimeLog.write(
                        "tool_argument_binding",
                        this.displayId + " | " + resolution.call().toolId() + " | " + provenance
                    );
                }
                return resolution.call();
            }
            String detail = resolution.blockers().isEmpty()
                ? "Tool arguments could not be grounded."
                : String.join("; ", resolution.blockers());
            String fingerprint = (call == null ? "" : call.toolId() + ':' + call.arguments()) + '|' + detail;
            int repeats = this.argumentBindingFailures.merge(fingerprint, 1, Integer::sum);
            if (repeats > 1) {
                if (noFailExecutionRequired()) {
                    continueNoFail("tool_argument_resolution_stalled", detail);
                } else {
                    fail("tool_argument_resolution_stalled", detail, null);
                }
                return null;
            }
            if (call != null) {
                this.conversation.add(ModelMessage.assistantToolCall("", call));
                ModelToolResult bindingResult = new ModelToolResult(
                    call.id(), call.toolId(), "rejected", new com.google.gson.JsonObject(),
                    "argument_binding_required", detail
                );
                this.agentState.observeToolResult(bindingResult);
                this.conversation.add(ModelMessage.toolResult(bindingResult));
                this.agentReasoningState.observeExternalProgress((int) Math.min(
                    Integer.MAX_VALUE, this.agentState.snapshot().revision()
                ));
            }
            queueControlGuidance(
                "The proposed " + phase + " tool call was not executed because one or more arguments are unsupported by observable state. "
                    + detail
                    + ". Reissue the call with the smallest schema-valid arguments grounded in the user request, a locked AgentState decision/fact, prior validated tool evidence, or the active plan step. "
                    + "Do not guess identifiers, paths, URLs, workspaces, selectors, or targets."
            );
            ModelGenerationHudState.state(
                this.displayId, ModelRequestState.RETRYING, "resolving tool arguments"
            );
            submitGeneration();
            return null;
        }

        private List<ModelToolCall> resolveToolBatchOrCorrect(List<ModelToolCall> calls, String phase) {
            if (calls == null || calls.isEmpty()) return calls == null ? List.of() : calls;
            List<ModelToolCall> resolved = new ArrayList<>(calls.size());
            for (ModelToolCall call : calls) {
                ModelToolCall grounded = resolveToolCallOrCorrect(call, phase);
                if (grounded == null) return null;
                resolved.add(grounded);
            }
            return List.copyOf(resolved);
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
                rememberAssistantDraft(assistantText);
            }
            String safeReason = reason == null || reason.isBlank() ? "validated_success_not_reached" : reason;
            queueControlGuidance(
                "No-Fail remains active because " + safeReason.replace('_', ' ') + ". "
                    + "Do not finalize or claim success. Inspect the latest structured result and current state, then call a changed supported tool or changed arguments that can advance the original objective. "
                    + "An identical call against unchanged evidence is forbidden. Preserve the exact requested target and all approval/permission boundaries."
                    + (AutomationModeController.isVerificationEnabled()
                    ? " Verification is also active, so only passed objective evidence counts as success."
                    : "")
            );
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

        /**
         * Advances a concrete ordered Automation frontier without requiring another
         * model round when Koil can deterministically ground the next action from the
         * current task clause. This is intentionally limited to already-resolved
         * current-task capabilities. Unresolved/custom tasks still go back to the
         * model, and Planning Mode continues to require reviewed plan segments.
         */
        private boolean tryExecuteDeterministicOrderedTaskFrontier(String reason) {
            if (this.mode != RequestMode.AUTOMATION || this.terminal.get()) return false;
            if (!this.capabilityProfile.canAutomate()) return false;
            if (this.objectiveLedger.allCompleted()) return false;
            if (this.validatedPlan != null || this.planPhase == PlanPhase.REVIEW
                    || this.planPhase == PlanPhase.APPROVED) {
                return false;
            }
            if (this.forcedPlanning) return false;

            ModelObjectiveLedger.Objective objective = this.objectiveLedger.currentObjectives().stream()
                    .findFirst().orElse(null);
            if (objective == null) return false;

            String resolvedToolId = objective.toolId() == null ? "" : objective.toolId().strip();
            com.google.gson.JsonObject memoArguments = new com.google.gson.JsonObject();
            if (resolvedToolId.isBlank()) {
                SemanticReasoningMemoStore.Match memo = SemanticReasoningMemoStore.bestMatch(
                        this.prefetchedReasoningMemos, objective.text());
                if (memo == null) return false;
                resolvedToolId = memo.toolId();
                if (memo.argumentsReusable()) memoArguments = memo.arguments();
                LocalModelRuntimeLog.write(
                        "reasoning_memo_fast_route",
                        this.displayId + " | task=" + objective.taskIndex()
                                + " | tool=" + resolvedToolId
                                + " | args_reused=" + memo.argumentsReusable()
                                + " | score=" + memo.score()
                                + " | confidence=" + memo.confidence()
                                + " | verified=" + memo.successes()
                                + " | failures=" + memo.failures()
                );
                com.google.gson.JsonObject memoSignal = new com.google.gson.JsonObject();
                memoSignal.addProperty("task", objective.taskIndex());
                memoSignal.addProperty("tool", resolvedToolId);
                memoSignal.addProperty("score", memo.score());
                memoSignal.addProperty("confidence", memo.confidence());
                memoSignal.addProperty("argumentsReused", memo.argumentsReusable());
                ModelGenerationHudState.upsertEvent(
                        this.displayId,
                        ModelGenerationHudState.ActivityEventType.MODEL_DATA,
                        "reasoning-memo-fast-route-" + objective.taskIndex(),
                        memo.argumentsReusable()
                                ? "Reusing a verified tool route and still-valid canonical arguments for this task."
                                : "Reusing a verified tool route; arguments are being grounded again from current state.",
                        memoSignal
                );
            }

            String selectedToolId = resolvedToolId;
            boolean supplied = this.tools.stream().anyMatch(tool -> selectedToolId.equals(tool.id()))
                    || LocalModelToolCatalog.definition(selectedToolId).isPresent();
            if (!supplied) return false;

            ModelToolCall proposed = new ModelToolCall(
                    UUID.randomUUID().toString(), selectedToolId, memoArguments);
            ToolArgumentResolver.ExecutionResolution resolution = resolveToolArguments(proposed);
            if (!resolution.executable() || resolution.call() == null) return false;

            ModelToolCall call = resolution.call();
            ModelObjectiveLedger.ExecutionGate gate = this.objectiveLedger.gate(call.toolId());
            if (!gate.allowed()) return false;
            LocalModelRuntimeLog.write(
                    "ordered_task_auto_dispatch",
                    this.displayId + " | task=" + this.objectiveLedger.currentTaskIndex()
                            + "/" + this.objectiveLedger.totalTaskCount()
                            + " | tool=" + call.toolId() + " | reason=" + reason
            );
            ModelGenerationHudState.appendEvent(
                    this.displayId,
                    ModelGenerationHudState.ActivityEventType.MODEL_DATA,
                    "Continuing ordered task " + this.objectiveLedger.currentTaskIndex()
                            + "/" + this.objectiveLedger.totalTaskCount()
                            + " with " + ModelToolCallPresentation.toolName(call.toolId()) + "."
            );
            handleToolCalls("", List.of(call), 0, false);
            return true;
        }

        /**
         * Finishes a fully executed ordered action request without asking the
         * language model to summarize work Koil has already verified. This keeps
         * a successful long action chain from failing afterwards merely because
         * a final provider prefill stalls.
         */
        private boolean completeOrderedObjectiveWithoutProviderIfDone() {
            if (this.mode != RequestMode.AUTOMATION || this.terminal.get()) return false;
            if (!this.objectiveLedger.allCompleted() || this.objectiveLedger.totalTaskCount() <= 0) return false;
            if (this.validatedPlan != null || this.planPhase == PlanPhase.REVIEW
                    || this.planPhase == PlanPhase.APPROVED) {
                return false;
            }
            if (this.finalToolSummaryRequested) return false;
            this.finalToolSummaryRequested = true;
            int tasks = this.objectiveLedger.totalTaskCount();
            LocalModelRuntimeLog.write(
                    "ordered_task_objective_complete",
                    this.displayId + " | tasks=" + tasks + " | provider_rounds=" + this.providerRoundCount
                            + " | final_response=model_generated"
            );
            // All side effects are complete. The final user-facing answer must still
            // come from the model, never from a Java literal pretending to be model output.
            // Remove action schemas for this final round so the model cannot re-run a
            // completed objective while summarizing the verified structured results.
            this.tools = List.of();
            queueControlGuidance(
                    "All explicitly requested automation tasks are complete according to their structured tool results. "
                            + "Produce the final user-facing response naturally from those verified results. "
                            + "Do not call another tool, do not repeat an action, and do not claim anything not supported by the recorded results."
            );
            ModelGenerationHudState.state(this.displayId, ModelRequestState.PREPARING_CONTEXT, "preparing model-generated final response");
            submitGeneration();
            return true;
        }

        private void continueUnresolvedObjective(String assistantText) {
            if (tryExecuteDeterministicOrderedTaskFrontier("model returned prose with ordered tasks remaining")) return;
            if (this.continuationCorrectionCount >= Math.max(1, this.thinking.maximumContinuationCorrections())) {
                LocalModelRuntimeLog.write(
                    "tool_continuation_failed",
                    this.displayId + " | model repeatedly described an action without calling a tool"
                );
                fail(
                        "unresolved_objective",
                        "The model repeatedly failed to produce an executable action for the remaining objective. No additional action was run.",
                        null
                );
                return;
            }
            this.continuationCorrectionCount++;
            archiveVisibleSummary(assistantText);
            // This retry exists only to obtain the missing structured action. Feeding
            // the prose draft back verbatim encourages small models to continue or
            // echo their own answer instead of emitting the required tool call.
            this.previousAssistantDraft = "";
            queueControlGuidance("""
                    Continue the unresolved objective now. Do not promise a later action and do not ask for prose confirmation.
                    Call only the remaining explicitly requested capability or capabilities now. Do not call another knowledge lookup unless it is one of the remaining capability identifiers.
                    If no supplied tool can perform the remaining step, state that exact limitation without claiming it will be done.
                    Do not repeat an action whose structured result already says it completed or was submitted.
                    Current ordered task: %s
                    Remaining capabilities for this task only: %s
                    """.formatted(
                        this.objectiveLedger.currentTaskText(),
                        String.join(", ", remainingRequiredToolIds())
                    ).strip());
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

        private void continueModelReasoning(String reasoningText) {
            String reasoning = reasoningText == null ? "" : reasoningText.strip();
            if (reasoning.isBlank()) {
                queueControlGuidance("Continue from the current model state and produce either a visible answer or a valid supplied tool call.");
                submitGeneration();
                return;
            }
            AgentReasoningState.ContinuationDecision agentContinuation = this.agentReasoningState.observeReasoning(
                reasoning,
                this.toolResultsReceived
            );
            if (!agentContinuation.continueReasoning()) {
                rememberReasoningContinuation(reasoning);
                if (this.forceNoNativeReasoningNextRound && this.reasoningConvergenceCorrectionCount >= 1) {
                    fail(
                        "reasoning_convergence_failed",
                        "The model continued producing reasoning-only output after Koil required a concrete action or visible answer.",
                        null
                    );
                    return;
                }
                this.reasoningConvergenceCorrectionCount++;
                this.forceNoNativeReasoningNextRound = true;
                if (this.mode == RequestMode.ASK) {
                    this.groundedAskFinalizing = true;
                }
                queueControlGuidance(
                    "Koil's reasoning controller reached convergence (" + agentContinuation.reason() + "). "
                        + "Do not emit another private reasoning-only pass. "
                        + (this.mode == RequestMode.AUTOMATION
                            ? "If the objective is unresolved, choose the next concrete supplied tool/action now; otherwise report the verified result."
                            : "Produce the user-facing answer now from the established substance and evidence.")
                );
                ModelGenerationHudState.state(this.displayId, ModelRequestState.FINALIZING, "reasoning converged");
                com.google.gson.JsonObject convergence = new com.google.gson.JsonObject();
                convergence.addProperty("reason", agentContinuation.reason());
                convergence.addProperty("continuation", this.agentReasoningState.continuationCount());
                convergence.addProperty("mode", this.agentReasoningDecision.mode().name().toLowerCase(java.util.Locale.ROOT));
                ModelGenerationHudState.upsertEvent(
                    this.displayId,
                    ModelGenerationHudState.ActivityEventType.MODEL_DATA,
                    "agent-reasoning-convergence-" + this.displayId,
                    "Reasoning converged; Koil is requiring a concrete action or final answer instead of another unchanged thought pass.",
                    convergence
                );
                submitGeneration();
                return;
            }
            this.reasoningContinuationCount++;
            String reasoningFingerprint = abbreviate(reasoning, 1_024).toLowerCase(java.util.Locale.ROOT);
            if (reasoningFingerprint.equals(this.lastReasoningFingerprint)) {
                this.repeatedReasoningCount++;
            } else {
                this.lastReasoningFingerprint = reasoningFingerprint;
                this.repeatedReasoningCount = 1;
            }
            rememberReasoningContinuation(reasoning);
            String guidance = this.mode == RequestMode.AUTOMATION
                ? "Continue from your own prior reasoning without restarting or restating it. Reason only as much as needed to establish correctness or choose the next concrete action. "
                    + "Do not spend reasoning cycles comparing tone, formality, casualness, or equivalent wording unless the user explicitly requested a style choice. "
                    + "If a supplied tool can resolve a material factual uncertainty or perform the next required action, call it now with valid arguments instead of debating whether to use it. "
                    + "If the objective is already resolved, emit the user-facing result. There is no hard thinking cutoff, but every additional pass must add evidence, resolve a distinct uncertainty, or advance the objective."
                : "Continue from your own prior reasoning without restarting or restating it. Reason only as much as needed to establish that the answer is correct. "
                    + "Do not spend reasoning cycles comparing tone, formality, casualness, or equivalent wording unless the user explicitly requested a style choice. "
                    + "If a supplied safe evidence tool can directly resolve a material factual uncertainty, call it now instead of debating whether memory is right, then resume from the result. "
                    + "Once the substance is correct, emit the user-facing answer. There is no hard thinking cutoff, but every additional pass must add evidence or resolve a distinct uncertainty.";
            if (this.repeatedReasoningCount >= 2) {
                guidance += " The previous reasoning pattern repeated. Do not restate it or reconsider settled presentation choices. Change approach, resolve one new factual/substantive point, use an available evidence tool when it can settle the uncertainty, or answer now when the substance is established.";
            }
            queueControlGuidance(guidance);
            ModelGenerationHudState.state(this.displayId, ModelRequestState.THINKING, "continuing model reasoning");
            com.google.gson.JsonObject signal = new com.google.gson.JsonObject();
            signal.addProperty("continuation", this.reasoningContinuationCount);
            signal.addProperty("reasoningCharacters", reasoning.length());
            signal.addProperty("repeatCount", this.repeatedReasoningCount);
            signal.addProperty("limited", false);
            ModelGenerationHudState.upsertEvent(
                this.displayId,
                ModelGenerationHudState.ActivityEventType.MODEL_DATA,
                "reasoning-continuation-" + this.displayId,
                this.repeatedReasoningCount >= 2
                    ? "Repeated reasoning pattern detected; Koil is steering toward new progress without terminating thought."
                    : "Model reasoning continued into another pass; Koil preserved it as assistant-authored state rather than a user message.",
                signal
            );
            LocalModelRuntimeLog.write(
                "reasoning_continuation",
                this.displayId + " | pass=" + this.reasoningContinuationCount + " | chars=" + reasoning.length()
            );
            submitGeneration();
        }

        private void rememberReasoningContinuation(String reasoning) {
            String wrapped = "<think>\n" + reasoning.strip() + "\n</think>";
            this.internalReasoningTurns.add(ModelMessage.assistant(wrapped));
            // This is a carry-over memory bound, not a generation limit. Keep the newest
            // reasoning available to the next pass while preventing an indefinitely-thinking
            // small model from overflowing its context or growing the JVM heap without bound.
            int retainedCharacters = 0;
            for (int index = this.internalReasoningTurns.size() - 1; index >= 0; index--) {
                retainedCharacters += this.internalReasoningTurns.get(index).content().length();
                if (retainedCharacters <= 24 * 1024) continue;
                this.internalReasoningTurns.subList(0, index + 1).clear();
                break;
            }
        }

        private String modelAuthoredContinuationContext() {
            // Continuation context is a recovery aid, not a transcript mirror. Keep it
            // deliberately small so multi-tool/web sessions cannot crowd the next
            // provider request out of the model's context window.
            int characterBudget = Math.max(2 * 1024, Math.min(6 * 1024, conversationCharacterBudget(this.mode) / 4));
            List<String> sections = new ArrayList<>();
            int used = 0;

            String draft = this.previousAssistantDraft == null ? "" : this.previousAssistantDraft.strip();
            if (!draft.isBlank()) {
                int allowance = Math.max(256, Math.min(2 * 1024, characterBudget / 3));
                if (draft.length() > allowance) draft = draft.substring(draft.length() - allowance);
                String section = "Previous assistant draft (model-authored, non-final):\n" + draft;
                sections.add(section);
                used += section.length() + 2;
            }

            List<String> selectedReasoning = new ArrayList<>();
            for (int index = this.internalReasoningTurns.size() - 1; index >= 0; index--) {
                ModelMessage message = this.internalReasoningTurns.get(index);
                ModelReasoningMarkupParser.Partition partition = ModelReasoningMarkupParser.partition(message.content());
                String raw = partition.reasoningText().isBlank() ? message.content() : partition.reasoningText();
                raw = raw == null ? "" : raw.strip();
                if (raw.isBlank()) continue;
                int remaining = characterBudget - used;
                if (remaining <= 256) break;
                int length = raw.length() + 2;
                if (length > remaining) {
                    raw = raw.substring(Math.max(0, raw.length() - Math.max(256, remaining - 2)));
                    selectedReasoning.add(0, raw);
                    used = characterBudget;
                    break;
                }
                selectedReasoning.add(0, raw);
                used += length;
            }
            if (!selectedReasoning.isEmpty()) {
                sections.add("Previous private reasoning (model-authored scratchpad):\n" + String.join("\n\n", selectedReasoning));
            }
            return String.join("\n\n", sections).strip();
        }

        private void rememberAssistantDraft(String text) {
            String safe = text == null ? "" : text.strip();
            if (safe.isBlank()) return;
            int maximum = Math.max(1_024, Math.min(12 * 1024, conversationCharacterBudget(this.mode) / 2));
            if (safe.length() > maximum) safe = safe.substring(safe.length() - maximum);
            this.previousAssistantDraft = safe;
        }

        private void clearReasoningContinuationState() {
            this.internalReasoningTurns.clear();
            this.reasoningContinuationCount = 0;
            this.lastReasoningFingerprint = "";
            this.repeatedReasoningCount = 0;
            this.reasoningConvergenceCorrectionCount = 0;
        }

        private void queueControlGuidance(String guidance) {
            String safe = guidance == null ? "" : guidance.strip();
            if (safe.isBlank()) return;
            String existing = this.pendingControlGuidance;
            this.pendingControlGuidance = existing == null || existing.isBlank()
                ? safe
                : existing + "\n" + safe;
        }

        private String consumeControlGuidance() {
            String guidance = this.pendingControlGuidance;
            this.pendingControlGuidance = "";
            return guidance == null ? "" : guidance.strip();
        }

        private static StreamingModelResponse normalizeReasoningResponse(StreamingModelResponse response) {
            if (response == null) return null;
            ModelReasoningMarkupParser.Partition partition = ModelReasoningMarkupParser.partition(response.text());
            if (!partition.sawThinkMarkup()) return response;
            String combinedReasoning = response.reasoningText();
            if (!partition.reasoningText().isBlank()) {
                combinedReasoning = combinedReasoning.isBlank()
                    ? partition.reasoningText()
                    : combinedReasoning + "\n" + partition.reasoningText();
            }
            return new StreamingModelResponse(
                response.requestId(),
                partition.visibleText(),
                combinedReasoning,
                response.toolCalls(),
                response.usage(),
                response.providerFinishReason()
            );
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
                            // The resolver may establish exact command syntax, but it must
                            // not impersonate the assistant by publishing that Java-built
                            // string directly. Give the verified command back to the model
                            // as evidence and require the model to author the user-facing turn.
                            this.tools = List.of();
                            queueControlGuidance(
                                    "Koil verified this exact command against the active command tree: "
                                            + command.maskedLink()
                                            + ". Produce the final answer now using that exact validated command unchanged. "
                                            + "Do not invent another command and do not claim it was executed."
                            );
                            ModelGenerationHudState.state(
                                    this.displayId,
                                    ModelRequestState.PREPARING_CONTEXT,
                                    "preparing model-authored validated command response"
                            );
                            submitGeneration();
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
                fail(
                        "command_validation_failed",
                        "The model repeatedly failed to produce a Minecraft command that validates against the active command tree.",
                        null
                );
                return;
            }
            this.askFormattingCorrectionCount++;
            if (assistantText != null && !assistantText.isBlank()) {
                rememberAssistantDraft(assistantText);
            }
            queueControlGuidance("""
                    Correct the previous answer before it is shown. The user explicitly asked for a Minecraft command.
                    Reply with the exact valid command using Koil's masked command syntax `[descriptive label](/command arguments)`.
                    Do not capitalize prose into a slash command, do not emit a bare `/Sentence`, and do not claim the command ran.
                    The previous command was checked against the active server command tree and rejected: %s
                    For item grants, use the active `/give` syntax; never invent a mod-loader command such as `/forge`.
                    For Minecraft 1.20.1 enchanted items, consult the item-NBT knowledge pattern. A valid example shape is `/give @s minecraft:stick{Enchantments:[{id:"minecraft:knockback",lvl:5s}]} 1`.
                    If you genuinely cannot determine exact syntax, say that it must be checked in the active command suggestions and do not invent a slash command.
                    """.formatted(abbreviate(validationDetail, 700)).strip());
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

        private String beginToolTelemetry(ModelToolCall call) {
            if (call == null) return "";
            String existing = this.toolTelemetrySpans.get(call.id());
            if (existing != null && !existing.isBlank()) return existing;
            String memoCue = this.mode == RequestMode.AUTOMATION ? this.objectiveLedger.currentTaskText() : this.prompt;
            if (memoCue == null || memoCue.isBlank()) memoCue = this.prompt;
            ModelToolDefinition memoDefinition = LocalModelToolCatalog.definition(call.toolId())
                    .orElseGet(() -> this.tools.stream().filter(tool -> call.toolId().equals(tool.id())).findFirst().orElse(null));
            SemanticReasoningMemoStore.Candidate memoCandidate = SemanticReasoningMemoStore.candidate(
                    memoCue, call, memoDefinition);
            if (memoCandidate != null) this.reasoningMemoCandidates.put(call.id(), memoCandidate);
            ModelObjectiveLedger.CallBinding taskBinding = this.objectiveLedger.bindCall(call);
            if (taskBinding.bound()) {
                LocalModelRuntimeLog.write(
                        "ordered_task_bind",
                        this.displayId + " | task=" + taskBinding.taskIndex() + "/" + this.objectiveLedger.totalTaskCount()
                                + " | objective=" + taskBinding.objectiveId() + " | tool=" + taskBinding.toolId()
                                + " | call=" + call.id()
                );
            }
            String parent = this.automationPlanTelemetrySpanId != null && !this.automationPlanTelemetrySpanId.isBlank()
                    ? this.automationPlanTelemetrySpanId
                    : this.activeProviderTelemetrySpanId == null || this.activeProviderTelemetrySpanId.isBlank()
                    ? TelemetryStore.rootSpan(this.displayId) : this.activeProviderTelemetrySpanId;
            SkillExecutionMatch skillExecution = this.skillExecutions.get(call.id());
            if (skillExecution == null) {
                skillExecution = findSkillExecution(call);
                if (skillExecution != null) this.skillExecutions.put(call.id(), skillExecution);
            }
            boolean skillCall = isSkillTool(call.toolId()) || skillExecution != null;
            TelemetrySpanKind spanKind = skillCall ? TelemetrySpanKind.SKILL_INVOCATION : TelemetrySpanKind.TOOL_INVOCATION;
            String subsystem = skillCall ? "skill" : "tool";
            Map<String, String> spanAttributes = new LinkedHashMap<>();
            spanAttributes.put("call_id", call.id());
            spanAttributes.put("tool_id", call.toolId());
            spanAttributes.put("capability_kind", subsystem);
            if (skillExecution != null) {
                spanAttributes.put("skill_id", skillExecution.skillId());
                spanAttributes.put("skill_path", skillExecution.path());
                spanAttributes.put("executor_tool_id", call.toolId());
            }
            String spanId = TelemetryStore.beginSpan(this.displayId, parent, spanKind,
                    skillExecution != null ? "skill script " + skillExecution.path()
                            : (skillCall ? "skill " : "tool ") + call.toolId(),
                    spanAttributes);
            String prior = this.toolTelemetrySpans.putIfAbsent(call.id(), spanId);
            if (prior != null && !prior.isBlank()) return prior;
            TelemetryStore.provenance(this.displayId, spanId, skillCall ? "skill.arguments" : "tool.arguments", call.toolId(),
                    skillExecution != null ? "skill_script_dispatch" : skillCall ? "skill_dispatch" : "tool_dispatch",
                    call.arguments().toString(),
                    skillExecution != null
                            ? "Arguments supplied to executor " + call.toolId() + " for Skill-owned file " + skillExecution.path()
                            : "Arguments supplied to " + call.toolId());
            TelemetryStore.metric(this.displayId, spanId, "argument_bytes",
                    call.arguments().toString().getBytes(StandardCharsets.UTF_8).length);
            TelemetryStore.capability(this.displayId, subsystem,
                    skillExecution != null ? skillExecution.skillId() : call.toolId(),
                    TelemetryCapabilityState.ACTIVE,
                    "executing",
                    skillExecution != null
                            ? "Executing Skill-owned file " + skillExecution.path() + " through " + call.toolId() + "."
                            : (skillCall ? "Skill" : "Tool") + " invocation is active.");
            return spanId;
        }

        private void finishToolTelemetry(ModelToolResult result) {
            if (result == null) return;
            SkillExecutionMatch skillExecution = this.skillExecutions.get(result.callId());
            boolean skillResult = isSkillTool(result.toolId()) || skillExecution != null;
            String spanId = this.toolTelemetrySpans.remove(result.callId());
            if (spanId == null || spanId.isBlank()) {
                Map<String, String> recoveredAttributes = new LinkedHashMap<>();
                recoveredAttributes.put("call_id", result.callId());
                recoveredAttributes.put("tool_id", result.toolId());
                recoveredAttributes.put("recovered_span", "true");
                recoveredAttributes.put("capability_kind", skillResult ? "skill" : "tool");
                if (skillExecution != null) {
                    recoveredAttributes.put("skill_id", skillExecution.skillId());
                    recoveredAttributes.put("skill_path", skillExecution.path());
                    recoveredAttributes.put("executor_tool_id", result.toolId());
                }
                spanId = TelemetryStore.beginSpan(this.displayId,
                        this.activeProviderTelemetrySpanId == null || this.activeProviderTelemetrySpanId.isBlank()
                                ? TelemetryStore.rootSpan(this.displayId) : this.activeProviderTelemetrySpanId,
                        skillResult ? TelemetrySpanKind.SKILL_INVOCATION : TelemetrySpanKind.TOOL_INVOCATION,
                        skillExecution != null ? "skill script " + skillExecution.path()
                                : (skillResult ? "skill " : "tool ") + result.toolId(),
                        recoveredAttributes);
            }
            String serializationSpan = TelemetryStore.beginSpan(
                    this.displayId, spanId, TelemetrySpanKind.SERIALIZATION,
                    skillResult ? "skill result serialization" : "tool result serialization",
                    Map.of("call_id", result.callId(), "tool_id", result.toolId(),
                            "capability_kind", skillResult ? "skill" : "tool"));
            long serializationStartedAt = System.nanoTime();
            String payload = result.output().toString();
            long serializationNanos = Math.max(0L, System.nanoTime() - serializationStartedAt);
            TelemetryStore.metric(this.displayId, serializationSpan, "serialization_us", serializationNanos / 1_000L);
            TelemetryStore.metric(this.displayId, serializationSpan, "serialized_bytes", payload.getBytes(StandardCharsets.UTF_8).length);
            TelemetryStore.finishSpan(this.displayId, serializationSpan, TelemetryCapabilityState.AVAILABLE,
                    "serialized", (skillResult ? "Skill" : "Tool") + " result serialized for model continuation.");
            TelemetryStore.metric(this.displayId, spanId, "tool_result_bytes", payload.getBytes(StandardCharsets.UTF_8).length);
            TelemetryStore.metric(this.displayId, spanId, "tool_result_tokens_estimated", TelemetryStore.estimateTokens(payload));
            TelemetryStore.metric(this.displayId, spanId, "tool_duration_ms", result.durationMillis());
            TelemetryStore.provenance(this.displayId, spanId, skillResult ? "skill.result" : "tool.result", result.toolId(),
                    skillExecution != null ? "skill_script_result" : skillResult ? "skill_result" : "tool_result", payload,
                    skillExecution != null
                            ? result.status() + " Skill-owned file " + skillExecution.path()
                                + (result.detail().isBlank() ? "" : ": " + result.detail())
                            : result.status() + (result.detail().isBlank() ? "" : ": " + result.detail()));
            String failureCode = result.failureCode() == null ? "" : result.failureCode().toLowerCase(java.util.Locale.ROOT);
            TelemetryCapabilityState state = result.cancelled() ? TelemetryCapabilityState.CANCELLED
                    : "completed".equals(result.status()) ? TelemetryCapabilityState.AVAILABLE
                    : failureCode.contains("unknown_tool") || failureCode.contains("unsupported")
                    || failureCode.contains("not_implemented") ? TelemetryCapabilityState.NOT_IMPLEMENTED
                    : failureCode.contains("unavailable") || failureCode.contains("automation_disabled")
                    || failureCode.contains("world_unavailable") ? TelemetryCapabilityState.UNAVAILABLE
                    : "blocked".equals(result.status()) || "rejected".equals(result.status())
                    || failureCode.contains("blocked") || failureCode.contains("declined")
                    ? TelemetryCapabilityState.BLOCKED : TelemetryCapabilityState.FAILED;
            TelemetryStore.finishSpan(this.displayId, spanId, state, result.failureCode(), result.detail());
            TelemetryStore.capability(this.displayId, skillResult ? "skill" : "tool",
                    skillExecution != null ? skillExecution.skillId() : result.toolId(),
                    state, result.failureCode(),
                    skillExecution != null
                            ? (result.detail().isBlank() ? "Finished " + skillExecution.path() : result.detail())
                            : result.detail());
        }

        private List<ModelToolDefinition> expandDiscoveredTools(List<ModelToolDefinition> base) {
            if (this.discoveredToolIds.isEmpty()) return base;
            LinkedHashMap<String, ModelToolDefinition> merged = new LinkedHashMap<>();
            if (base != null) for (ModelToolDefinition tool : base) merged.putIfAbsent(tool.id(), tool);
            for (String toolId : this.discoveredToolIds) {
                LocalModelToolCatalog.definition(toolId).ifPresent(tool -> {
                    boolean allowed = this.mode == RequestMode.AUTOMATION
                            || (tool.sideEffects().isEmpty()
                            && !tool.confirmationRequired()
                            && DeepThoughtReadOnlyToolCoordinator.supports(tool.id()));
                    if (allowed) merged.putIfAbsent(tool.id(), tool);
                });
            }
            return List.copyOf(merged.values());
        }

        private void rememberDiscoveredTools(ModelToolResult result) {
            if (result == null || !ToolDiscoveryModelToolRegistry.SEARCH.equals(result.toolId())
                    || !"completed".equals(result.status()) || result.output() == null
                    || !result.output().has("tools") || !result.output().get("tools").isJsonArray()) return;
            for (com.google.gson.JsonElement element : result.output().getAsJsonArray("tools")) {
                if (!element.isJsonObject()) continue;
                com.google.gson.JsonObject candidate = element.getAsJsonObject();
                if (!candidate.has("id")) continue;
                String toolId = candidate.get("id").getAsString();
                LocalModelToolCatalog.definition(toolId).ifPresent(tool -> {
                    if (this.mode == RequestMode.AUTOMATION
                            || (tool.sideEffects().isEmpty()
                            && !tool.confirmationRequired()
                            && DeepThoughtReadOnlyToolCoordinator.supports(tool.id()))) {
                        this.discoveredToolIds.add(tool.id());
                    }
                });
            }
            if (this.mode != RequestMode.AUTOMATION) this.tools = expandDiscoveredTools(this.tools);
        }

        private void recordToolResult(ModelToolResult result) {
            this.forceNoNativeReasoningNextRound = false;
            rememberDiscoveredTools(result);
            int taskBefore = this.objectiveLedger.currentTaskIndex();
            ModelObjectiveLedger.RecordOutcome orderedTaskOutcome = this.objectiveLedger.record(result);
            this.agentState.observeToolResult(result, orderedTaskOutcome.matchedObjective());
            ModelToolDefinition verificationDefinition = LocalModelToolCatalog.definition(result.toolId())
                    .orElseGet(() -> this.tools.stream()
                            .filter(tool -> result.toolId().equals(tool.id()))
                            .findFirst().orElse(null));
            AgentVerificationPolicy.Observation verificationObservation = AgentVerificationPolicy.observe(
                    this.agentState, result, verificationDefinition, this.tools
            );
            if (!verificationObservation.claimId().isBlank()) {
                LocalModelRuntimeLog.write(
                        "agent_claim",
                        this.displayId + " | " + verificationObservation.claimId()
                                + " | consequential=" + verificationObservation.consequential()
                                + " | validated=" + verificationObservation.alreadyValidated()
                                + (verificationObservation.verifierTool().isBlank()
                                ? "" : " | verifier=" + verificationObservation.verifierTool())
                );
            }
            this.agentReasoningState.observeExternalProgress(this.toolResultsReceived);
            SkillExecutionMatch skillExecution = this.skillExecutions.get(result.callId());
            finishToolTelemetry(result);
            SemanticReasoningMemoStore.Candidate memoCandidate = this.reasoningMemoCandidates.remove(result.callId());
            if (memoCandidate != null) {
                CompletableFuture.runAsync(() -> SemanticReasoningMemoStore.observe(memoCandidate, result));
            }
            this.conversation.add(ModelMessage.toolResult(result));
            this.durableState.record(result, orderedTaskOutcome);
            int taskAfter = orderedTaskOutcome.taskAfter();
            if (orderedTaskOutcome.taskAdvanced() && taskBefore > 0) {
                this.automationProgress.nextTask();
                this.agentState.focusOrderedObjectives(this.objectiveLedger.currentObjectives());
                this.continuationCorrectionCount = 0;
                this.argumentBindingFailures.clear();
                this.repeatedResponses.clear();
                ModelGenerationHudState.appendEvent(
                    this.displayId,
                    ModelGenerationHudState.ActivityEventType.RESULT,
                    taskAfter > 0
                        ? "Ordered task " + taskBefore + " completed; advancing to task " + taskAfter + "/" + this.objectiveLedger.totalTaskCount() + "."
                        : "All " + this.objectiveLedger.totalTaskCount() + " ordered tasks are complete."
                );
            }
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
            ModelExecutionEvent.Type type = skillExecution != null
                ? ModelExecutionEvent.Type.TOOL_RESULT
                : result.toolId().startsWith("workspace.")
                ? result.output().has("diffHunks") ? ModelExecutionEvent.Type.DIFF_PRODUCED : ModelExecutionEvent.Type.FILE_READ
                : result.toolId().startsWith("development.") ? ModelExecutionEvent.Type.COMMAND_COMPLETED
                : ModelExecutionEvent.Type.TOOL_RESULT;
            com.google.gson.JsonObject evidence = result.output().deepCopy();
            evidence.addProperty("toolId", result.toolId());
            if (skillExecution != null) {
                evidence.addProperty("skillId", skillExecution.skillId());
                evidence.addProperty("skillPath", skillExecution.path());
                evidence.addProperty("skillExecution", true);
                evidence.addProperty("executorToolId", result.toolId());
            }
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
                skillExecution == null
                    ? result.toolId() + " — " + result.status()
                        + (result.detail().isBlank() ? "" : ": " + abbreviate(result.detail(), 260))
                    : ("completed".equals(result.status()) ? "Executed " : "Failed ")
                        + skillExecution.path()
                        + (result.detail().isBlank() ? "" : " — " + abbreviate(result.detail(), 260)),
                evidence, System.currentTimeMillis()
            ));
            if (skillExecution != null) this.skillExecutions.remove(result.callId());
        }

        private static boolean isSkillTool(String toolId) {
            String value = toolId == null ? "" : toolId.strip().toLowerCase(java.util.Locale.ROOT);
            return value.startsWith("skill.");
        }

        private static boolean asksForMinecraftCommand(String prompt) {
            if (prompt == null || prompt.isBlank()) return false;
            String normalized = prompt.toLowerCase(java.util.Locale.ROOT)
                    .replace('’', '\'')
                    .replaceAll("\\s+", " ")
                    .strip();
            return EXPLICIT_MINECRAFT_COMMAND_REQUEST.matcher(normalized).find();
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
            if (this.outputTelemetrySpanId.isBlank()) {
                this.outputTelemetrySpanId = TelemetryStore.beginSpan(this.displayId,
                        this.activeProviderTelemetrySpanId, TelemetrySpanKind.OUTPUT, "final output");
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
                    if (text != null && !text.isBlank()) rememberAssistantDraft(text);
                    queueControlGuidance(
                        "Correct only the final presentation in one compact response. Preserve every literal command argument, path, namespaced ID, and mathematical expression exactly. "
                            + String.join("; ", formatted.issues())
                            + ". Do not add a heading. Use $...$ or \\(...\\) inline and $$...$$ or \\[...\\] for block math. Commands must be validated masked suggestions and never code."
                    );
                    ModelGenerationHudState.state(this.displayId, ModelRequestState.PREPARING_CONTEXT, "formatting final response");
                    ModelVoiceService.discardPresentedStreaming(this.displayId);
                    submitGeneration();
                    return;
                }
                fail(
                        "final_format_validation_failed",
                        "The model repeatedly returned a final response that failed Koil's presentation validation.",
                        null
                );
                return;
            }
            RichChatModelOutputSanitizer.Result sanitized = new RichChatModelOutputSanitizer.Result(formatted.text(), formatted.changed());
            if (!this.terminal.compareAndSet(false, true)) {
                return;
            }
            finishSelectedSkillActivities(true, "");
            TelemetryStore.provenance(this.displayId, this.outputTelemetrySpanId, "koil.final_output",
                    "RichChatModelFinalFormatValidator", "output", sanitized.text(),
                    "Final user-facing response after Koil validation/sanitization");
            TelemetryStore.metric(this.displayId, this.outputTelemetrySpanId, "output_bytes",
                    sanitized.text().getBytes(StandardCharsets.UTF_8).length);
            TelemetryStore.metric(this.displayId, this.outputTelemetrySpanId, "output_tokens_estimated",
                    TelemetryStore.estimateTokens(sanitized.text()));
            TelemetryStore.finishSpan(this.displayId, this.outputTelemetrySpanId,
                    TelemetryCapabilityState.AVAILABLE, "completed", "Final response prepared.");
            this.outputTelemetrySpanId = "";
            if (this.executionContext != null) this.executionContext.finish();
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
            if (ModelExperimentalFeatures.snapshot().persistentKnowledge()) {
                String finalText = sanitized.text();
                CompletableFuture.runAsync(() -> ModelAssociativeMemory.remember(this.prompt, finalText, this.conversation.id()));
            }
            ModelGenerationHudState.replaceText(this.displayId, sanitized.text());
            ModelGenerationHudState.appendEvent(
                this.displayId,
                ModelGenerationHudState.ActivityEventType.RESULT,
                "Prepared the final user-facing response."
            );
            ModelGenerationHudState.state(this.displayId, ModelRequestState.COMPLETED, "response added to chat");
            MinecraftClient current = MinecraftClient.getInstance();
            if (current != null) {
                current.execute(() -> {
                    ModelChatMessageBridge.addToChat(
                        current,
                        sanitized.text(),
                        ModelActivityPresentation.capture(ModelGenerationHudState.snapshot(this.displayId))
                    );
                    if (voiceAlreadyStreamed) {
                        ModelVoiceService.finishPresentedStreaming(this.displayId, sanitized.text());
                    } else {
                        ModelVoiceService.speakFinalAnswer(sanitized.text());
                    }
                    ModelGenerationHudState.messagePresented(this.displayId);
                    if (this.deepThought != null) {
                        CompletableFuture.runAsync(this.deepThought::markFinalPresented);
                    }
                    if (this.mode == RequestMode.AUTOMATION && AutomationModeController.isAutomationMode()) {
                        AutomationModeController.ready("local model ready");
                    }
                });
            } else if (voiceAlreadyStreamed) {
                // No Minecraft client means there is no visible chat surface to
                // guarantee a remaining phrase is visible. Drop rather than speak
                // content the user cannot currently see.
                ModelVoiceService.discardPresentedStreaming(this.displayId);
            }
        }

        private void archiveVisibleSummary(String text) {
            RichChatModelOutputSanitizer.Result summary = RichChatModelOutputSanitizer.sanitize(text);
            if (!summary.text().isBlank()) {
                com.google.gson.JsonObject surfaced = new com.google.gson.JsonObject();
                surfaced.addProperty("source", "model_intermediate_output");
                surfaced.addProperty("classifiedAsThought", false);
                surfaced.addProperty("characterCount", summary.text().length());
                ModelGenerationHudState.upsertEvent(
                    this.displayId,
                    ModelGenerationHudState.ActivityEventType.MODEL_DATA,
                    "model-intermediate-" + this.providerRoundCount,
                    "Intermediate assistant turn retained for continuation · "
                        + summary.text().length() + " characters",
                    surfaced
                );
                if (this.deepThought != null && this.mode == RequestMode.AUTOMATION) {
                    this.deepThought.acceptRoundSummary(firstPersonVisibleSummary(visiblePlanSummary(summary.text())));
                }
            }
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
            for (String key : List.of(
                    "query", "path", "url", "target", "item", "command", "symbol", "name",
                    "file", "directory", "root", "pattern", "task", "objective", "repository", "value"
            )) {
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

        private static boolean containsRawToolProtocol(String text) {
            String value = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
            return value.contains("<|tool_call_start|>")
                    || value.contains("<|tool_call_end|>")
                    || value.contains("<tool_call>")
                    || value.contains("</tool_call>");
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

        /**
         * Ends a request with an explicitly system-authored status. This path is
         * intentionally separate from complete(String): it never inserts the text
         * into assistant conversation history, persistent model memory, voice output,
         * or the model chat bridge. Java may report runtime state, but it must never
         * impersonate a model-generated answer.
         */
        private void finishSystemStatus(ModelRequestState state, String detail) {
            if (!this.terminal.compareAndSet(false, true)) return;
            ModelRequestState safeState = state == null ? ModelRequestState.COMPLETED : state;
            finishSelectedSkillActivities(safeState == ModelRequestState.COMPLETED, detail);
            if (this.executionContext != null) this.executionContext.finish();
            SESSIONS.remove(this.displayId, this);
            AutomationModeController.setDeepThinkingActive(false);
            AutomationModeController.setPlanningActive(false);
            String safeDetail = detail == null || detail.isBlank() ? "Request ended." : detail.strip();
            ModelGenerationHudState.appendEvent(
                    this.displayId,
                    safeState == ModelRequestState.FAILED
                            ? ModelGenerationHudState.ActivityEventType.FAILURE
                            : ModelGenerationHudState.ActivityEventType.RESULT,
                    "System status: " + safeDetail
            );
            ModelGenerationHudState.state(this.displayId, safeState, safeDetail);
            MinecraftClient current = MinecraftClient.getInstance();
            if (current != null) {
                current.execute(() -> localError(current, "[Koil system] " + safeDetail));
            }
            if (AutomationModeController.isAutomationMode()) {
                AutomationModeController.ready("local model ready");
            }
        }

        private void fail(String code, String detail, Throwable cause) {
            if (!this.terminal.compareAndSet(false, true)) {
                return;
            }
            if (this.executionContext != null) this.executionContext.finish();
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
            finishSelectedSkillActivities(false, safeDetail);
            if (this.mode == RequestMode.AUTOMATION && majorModelProtocolFailure(safeCode)) {
                LocalModelReliabilityStore.recordProtocolFailure(configuredModelId(), safeCode, safeDetail);
            }
            ModelRequestState state = "cancelled".equals(safeCode)
                ? ModelRequestState.CANCELLED
                : ModelRequestState.FAILED;
            TelemetryCapabilityState terminalTelemetryState = state == ModelRequestState.CANCELLED
                    ? TelemetryCapabilityState.CANCELLED : TelemetryCapabilityState.FAILED;
            for (String toolSpan : new ArrayList<>(this.toolTelemetrySpans.values())) {
                TelemetryStore.finishSpan(this.displayId, toolSpan, terminalTelemetryState, safeCode, safeDetail);
            }
            this.toolTelemetrySpans.clear();
            if (!this.automationPlanTelemetrySpanId.isBlank()) {
                TelemetryStore.finishSpan(this.displayId, this.automationPlanTelemetrySpanId, terminalTelemetryState, safeCode, safeDetail);
                this.automationPlanTelemetrySpanId = "";
            }
            if (!this.outputTelemetrySpanId.isBlank()) {
                TelemetryStore.finishSpan(this.displayId, this.outputTelemetrySpanId, terminalTelemetryState, safeCode, safeDetail);
                this.outputTelemetrySpanId = "";
            }
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
                case "empty_response", "invalid_plan_result",
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
