package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilTuningKey;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.model.LocalModelProvider;
import com.spirit.koil.api.model.LocalModelPerformanceBenchmarkProvider;
import com.spirit.koil.api.model.ModelPerformanceBenchmarkRequest;
import com.spirit.koil.api.model.ModelPerformanceBenchmarkResult;
import com.spirit.koil.api.model.LocalModelOwnedProcessRegistry;
import com.spirit.koil.api.model.LocalModelRuntimeLog;
import com.spirit.koil.api.model.ModelDebugMode;
import com.spirit.koil.api.model.catalog.LocalModelReliabilityStore;
import com.spirit.koil.api.model.cache.ModelStartupWarmupBranch;
import com.spirit.koil.api.model.ModelCancellationHandle;
import com.spirit.koil.api.model.ModelCapabilityDescriptor;
import com.spirit.koil.api.model.ModelHealthSnapshot;
import com.spirit.koil.api.model.ModelHealthState;
import com.spirit.koil.api.model.ModelMessage;
import com.spirit.koil.api.model.ModelRole;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelRequestState;
import com.spirit.koil.api.model.ModelExposedData;
import com.spirit.koil.api.model.ModelRuntimeTelemetry;
import com.spirit.koil.api.model.ModelUsage;
import com.spirit.koil.api.model.StreamingModelObserver;
import com.spirit.koil.api.model.StreamingModelRequest;
import com.spirit.koil.api.model.StreamingModelResponse;
import com.spirit.koil.api.model.runtime.universal.KoilExecutionPlan;
import com.spirit.koil.api.model.runtime.universal.KoilExecutionPlanConsumer;
import com.spirit.koil.api.model.runtime.universal.KoilOptionalResourceAdmission;
import com.spirit.koil.api.model.runtime.universal.KoilOptionalResourceConsumer;
import com.spirit.koil.api.model.runtime.universal.KoilOptionalResourceKind;
import com.spirit.koil.api.model.runtime.universal.KoilMemoryBudgetPlanner;
import com.spirit.koil.api.model.runtime.universal.KoilMemoryPressureSnapshot;
import com.spirit.koil.api.model.runtime.universal.KoilRuntimeBackend;
import com.spirit.koil.api.model.runtime.universal.KoilRuntimeMemoryObservationConsumer;
import com.spirit.koil.api.model.runtime.universal.KoilMemoryPressureStabilizer;
import com.spirit.koil.api.model.runtime.universal.KoilStatePrecision;
import com.spirit.koil.api.model.runtime.universal.KoilStatePrecisionDecision;
import com.spirit.koil.api.model.runtime.universal.KoilStateMemoryEstimate;
import com.spirit.koil.api.model.runtime.universal.KoilStatePrecisionPolicy;
import com.spirit.koil.api.model.runtime.universal.KoilStatePrecisionEvidence;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LlamaCppLocalModelProvider implements LocalModelProvider, LocalModelPerformanceBenchmarkProvider, KoilExecutionPlanConsumer, KoilOptionalResourceConsumer, KoilRuntimeMemoryObservationConsumer {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final long STARTUP_WARMUP_BUDGET_SECONDS = 12L;
    private static final long PREFILL_WATCHDOG_POLL_MILLIS = 2_000L;
    private static final int ACCELERATOR_CANARY_ATTEMPTS = 3;
    private static final long ACCELERATOR_CANARY_RETRY_BASE_MILLIS = 180L;
    private static final long ACCELERATOR_CANARY_BACKGROUND_DELAY_MILLIS = 2_500L;
    private static final int RUNTIME_OUTPUT_TAIL_LINES = 40;
    private static final int RUNTIME_OUTPUT_TAIL_CHARS = 6000;
    private static final Pattern GPU_LAYER_OFFLOAD = Pattern.compile(
            "(?i)offloaded\\s+(\\d+)\\s*/\\s*(\\d+)\\s+layers\\s+to\\s+(?:GPU|device|MTL\\d+|Metal\\d*|Vulkan\\d*|CUDA\\d*|ROCm\\d*|HIP\\d*|SYCL\\d*)"
    );
    private static final Pattern GPU_REPEATING_OFFLOAD = Pattern.compile(
            "(?i)offloading\\s+(\\d+)\\s+repeating\\s+layers\\s+to\\s+GPU"
    );
    private static final Pattern GPU_OUTPUT_OFFLOAD = Pattern.compile(
            "(?i)offloading\\s+output\\s+layer\\s+to\\s+GPU"
    );
    private static final Pattern MODEL_LAYER_COUNT = Pattern.compile(
            "(?i)(?:^|\\s)n_layer\\s*=\\s*(\\d+)(?:\\s|$)"
    );
    private static final Pattern CONTEXT_OVERFLOW_DETAIL = Pattern.compile(
            "(?i)request\\s*\\((\\d+)\\s+tokens\\)\\s+exceeds\\s+the\\s+available\\s+context\\s+size\\s*\\((\\d+)\\s+tokens\\)"
    );
    private static final int CONTEXT_RECOVERY_MAX_ATTEMPTS = 2;
    private static final Pattern GPU_MODEL_BUFFER = Pattern.compile(
            "(?i)(?:MTL\\d+|Metal\\d*|Vulkan\\d*|CUDA\\d*|ROCm\\d*|HIP\\d*|SYCL\\d*)[^\\n]*model\\s+buffer\\s+size"
    );
    private final Object processLock = new Object();
    private final Object startLock = new Object();
    private final Object runtimeOutputLock = new Object();
    private final Object healthLock = new Object();
    private final Deque<String> runtimeOutputTail = new ArrayDeque<>();
    private final LlamaCppConfiguration configuration;
    private final OkHttpClient http;
    private final ExecutorService lifecycle;
    private final ExecutorService requests;
    private final LlamaCppKvCacheManager cacheManager;
    private final List<ModelStartupWarmupBranch> startupWarmupBranches;
    private volatile CompletableFuture<ModelHealthSnapshot> startFuture;
    private volatile CompletableFuture<Void> outputCaptureFuture = CompletableFuture.completedFuture(null);
    private volatile String warmupState = "not_started";
    private volatile int warmupPrepared;
    private volatile int warmupTotal;
    private volatile Call startupWarmupCall;
    private volatile boolean warmupPreemptedByRequest;
    private volatile boolean warmupRevokedByMemory;
    /** Stable READY detail captured before optional warmup decorates health. */
    private volatile String readyRuntimeDetail = "";
    private volatile ModelHealthSnapshot health = ModelHealthSnapshot.stopped();
    private volatile Process process;
    private volatile boolean ownsProcess;
    private volatile boolean stopping;
    private volatile boolean closed;
    private volatile int selectedPort;
    private volatile int actualGpuLayers = -1;
    private volatile int actualModelLayers = -1;
    private volatile int observedRepeatingGpuLayers = -1;
    private volatile boolean observedGpuOutputLayer;
    private volatile boolean observedGpuModelBuffer;
    private volatile String computeIntegrityState = "not_checked";
    private volatile String computeIntegrityDetail = "";
    private volatile String computeSafetyState = "not_checked";
    private volatile String computeSafetyDetail = "";
    private volatile long computeSafetyStartAvailableBytes;
    private volatile String resolvedComputeDevice = "";
    private volatile String resolvedComputeDeviceDetail = "";
    private volatile LlamaCppMaxRuntimeProfile activeMaxProfile;
    private volatile String activeMaxProfileSource = "none";
    private volatile KoilExecutionPlan koilExecutionPlan;
    private volatile Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> koilLaunchOptionalAdmissions = Map.of();
    private volatile Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> koilLiveOptionalAdmissions = Map.of();
    private final AtomicBoolean optionalStateEvictionScheduled = new AtomicBoolean();
    private volatile boolean optionalStateEvictionRequested;
    private volatile String optionalStateRetentionState = "unobserved";
    private volatile String optionalStateRetentionDetail = "";
    private volatile int learnedResidentContextCeilingTokens;
    private volatile String learnedResidentContextReason = "unobserved";
    private volatile LlamaCppProcessMemoryAttribution.Snapshot residentPressureAttribution =
            LlamaCppProcessMemoryAttribution.Snapshot.unknown("not sampled");
    private final LlamaCppResidentPressureController residentPressureController = new LlamaCppResidentPressureController();
    private volatile LlamaCppResidentPressureController.Decision residentPressureDecision =
            LlamaCppResidentPressureController.Decision.normal();

    private volatile boolean activeMaxProfileTuned;
    private volatile LlamaCppStartupMemoryPolicy.Profile startupMemoryProfile;
    private volatile KoilStatePrecisionDecision activeStatePrecision = new KoilStatePrecisionDecision(
            KoilStatePrecision.FP16, KoilStatePrecision.FP16, false, 1.0, "default full-precision state");
    private volatile int activeRuntimeContextTokens;
    private volatile boolean startupMemoryRecoveryAttempted;
    private volatile int startupUnexpectedExitCode = Integer.MIN_VALUE;
    private volatile String startupUnexpectedExitTail = "";
    private volatile String lastTerminationProvenance = "none";
    private final LlamaCppProtocolProfile protocolProfile;
    private volatile LlamaCppRuntimeCapabilities runtimeCapabilities = LlamaCppRuntimeCapabilities.unknown();
    private volatile LlamaCppCompatibilityReport compatibilityReport;

    public LlamaCppLocalModelProvider(LlamaCppConfiguration configuration) {
        this(configuration, List.of());
    }

    public LlamaCppLocalModelProvider(
            LlamaCppConfiguration configuration,
            List<ModelStartupWarmupBranch> startupWarmupBranches
    ) {
        this(configuration, new OkHttpClient.Builder()
                .connectTimeout(5L, TimeUnit.SECONDS)
                .readTimeout(0L, TimeUnit.MILLISECONDS)
                .build(), startupWarmupBranches);
    }

    LlamaCppLocalModelProvider(LlamaCppConfiguration configuration, OkHttpClient http) {
        this(configuration, http, List.of());
    }

    LlamaCppLocalModelProvider(
            LlamaCppConfiguration configuration,
            OkHttpClient http,
            List<ModelStartupWarmupBranch> startupWarmupBranches
    ) {
        this.configuration = configuration == null ? LlamaCppConfiguration.disabled() : configuration;
        this.http = http;
        this.selectedPort = this.configuration.port();
        this.lifecycle = Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "koil-llama-lifecycle"));
        this.requests = Executors.newCachedThreadPool(runnable -> daemon(runnable, "koil-llama-request"));
        this.cacheManager = new LlamaCppKvCacheManager(this.configuration, this.http);
        this.protocolProfile = LlamaCppProtocolProfile.resolve(this.configuration.modelId(), this.configuration.modelFile());
        this.compatibilityReport = LlamaCppCompatibilityReport.negotiate(
                this.protocolProfile, this.runtimeCapabilities, this.configuration.contextTokens());
        this.startupWarmupBranches = startupWarmupBranches == null ? List.of() : List.copyOf(startupWarmupBranches);
        this.warmupTotal = this.startupWarmupBranches.size();
        if (this.configuration.computeSettings().mode() == LlamaCppComputeMode.CPU) {
            this.actualGpuLayers = 0;
        }
    }

    @Override
    public String id() {
        return "llama_cpp";
    }

    /**
     * Predicts the per-slot context that a fresh llama.cpp launch would use under the
     * current host-memory conditions. This is intentionally side-effect free and is used
     * only to key reusable universal tuning evidence before the native process starts.
     */
    public int plannedStartupContextTokens() {
        String hardwareFingerprint = this.koilExecutionPlan == null ? "" : this.koilExecutionPlan.hardwareFingerprint();
        return plannedStartupContextTokens(hardwareFingerprint);
    }

    /**
     * Predicts startup context using an explicit hardware identity. Universal tuning lookup runs
     * before the unified execution plan is injected into this adapter, so callers that already
     * own the hardware profile must provide its fingerprint here. This keeps resident low-water
     * evidence in the same identity domain during both pre-launch planning and native startup.
     */
    public int plannedStartupContextTokens(String hardwareFingerprint) {
        long available = LlamaCppComputeStabilityStore.currentAvailableMemoryBytes();
        long modelBytes = 0L;
        try {
            if (this.configuration.modelFile() != null) modelBytes = Files.size(this.configuration.modelFile());
        } catch (IOException ignored) {
        }
        int configured = configuredEffectiveContextTokens();
        LlamaCppStartupMemoryPolicy.Profile selected = LlamaCppStartupMemoryPolicy.select(
                configured, this.configuration.kvSlots(), modelBytes, available);
        LlamaCppRuntimeMemoryEnvelopeStore.Recommendation learned = learnedResidentContextRecommendation(
                selected.contextTokens(), selected.availableBytes(), hardwareFingerprint);
        return Math.min(selected.contextTokens(), learned.contextTokens());
    }

    @Override
    public ModelCapabilityDescriptor capabilities() {
        LlamaCppCompatibilityReport report = this.compatibilityReport;
        return new ModelCapabilityDescriptor(
                true,
                report.toolCalling(),
                true,
                true,
                true,
                report.contextTokens() > 0 ? report.contextTokens() : effectiveContextTokens(),
                report.protocolCapabilities()
        );
    }

    @Override
    public ModelHealthSnapshot health() {
        return this.health;
    }

    @Override
    public CompletableFuture<ModelHealthSnapshot> start() {
        if (this.closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("llama.cpp provider is closed"));
        }
        if (this.health.state() == ModelHealthState.READY) {
            return CompletableFuture.completedFuture(this.health);
        }
        synchronized (this.startLock) {
            if (this.startFuture != null && !this.startFuture.isDone()) return this.startFuture;
            this.startFuture = CompletableFuture.supplyAsync(this::startBlocking, this.lifecycle);
            return this.startFuture;
        }
    }

    private ModelHealthSnapshot startBlocking() {
        this.stopping = false;
        updateHealth(ModelHealthState.STARTING, "checking llama.cpp runtime", Map.of());
        String invalid = validateInstallation();
        if (!invalid.isBlank()) {
            return failHealth(invalid);
        }
        this.cacheManager.initialize();
        if (this.configuration.port() > 0 && compatibleRuntimeAvailable(this.configuration.port())) {
            this.selectedPort = this.configuration.port();
            this.ownsProcess = false;
            this.activeStatePrecision = new KoilStatePrecisionDecision(
                    KoilStatePrecision.AUTO, KoilStatePrecision.AUTO, false, 1.0,
                    "external runtime state precision is not controlled by Koil");
            return prepareRuntimeForUse("connected to existing llama.cpp runtime");
        }
        synchronized (this.processLock) {
            if (this.process != null && this.process.isAlive()) {
                return waitForReadiness();
            }
            try {
                resetRuntimeObservations();
                resolveAutomaticComputeDevice();
                this.selectedPort = this.configuration.port() == 0 ? selectLocalPort() : this.configuration.port();
                this.startupMemoryRecoveryAttempted = false;
                this.startupUnexpectedExitCode = Integer.MIN_VALUE;
                this.startupUnexpectedExitTail = "";
                prepareStartupMemoryProfile(false);
                launchOwnedRuntime();
            } catch (Exception exception) {
                return failHealth("failed to start llama.cpp: " + message(exception));
            }
        }
        return waitForReadiness();
    }

    private void launchOwnedRuntime() throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command(this.selectedPort));
        builder.redirectErrorStream(true);
        builder.environment().put("LLAMA_API_KEY", this.configuration.apiKey());
        Path parent = this.configuration.executable().toAbsolutePath().normalize().getParent();
        if (parent != null) builder.directory(parent.toFile());
        this.process = builder.start();
        this.ownsProcess = true;
        Process launched = this.process;
        LocalModelOwnedProcessRegistry.register(launched);
        captureOutput(launched);
        launched.onExit().thenRun(() -> handleProcessExit(launched));
        LocalModelRuntimeLog.write(
                "llama_startup",
                "started llama.cpp on port " + this.selectedPort
                        + " | context=" + effectiveContextTokens()
                        + " | slots=" + this.configuration.kvSlots()
                        + " | memory_profile=" + startupMemoryProfileReason());
    }

    private void prepareStartupMemoryProfile(boolean recovery) {
        long available = LlamaCppComputeStabilityStore.currentAvailableMemoryBytes();
        long modelBytes = 0L;
        try {
            if (this.configuration.modelFile() != null) modelBytes = Files.size(this.configuration.modelFile());
        } catch (IOException ignored) {
        }
        int configured = configuredEffectiveContextTokens();
        LlamaCppStartupMemoryPolicy.Profile profile = recovery
                ? LlamaCppStartupMemoryPolicy.recovery(
                        this.startupMemoryProfile, configured, this.configuration.kvSlots(), modelBytes, available)
                : LlamaCppStartupMemoryPolicy.select(
                        configured, this.configuration.kvSlots(), modelBytes, available);
        LlamaCppRuntimeMemoryEnvelopeStore.Recommendation learned = learnedResidentContextRecommendation(
                profile.contextTokens(), profile.availableBytes());
        if (learned.contextTokens() < profile.contextTokens()) {
            profile = new LlamaCppStartupMemoryPolicy.Profile(
                    learned.contextTokens(), profile.reason() + "+resident_low_water",
                    profile.availableBytes(), profile.modelBytes());
        }
        this.learnedResidentContextCeilingTokens = learned.learnedCapApplied() ? learned.contextTokens() : 0;
        this.learnedResidentContextReason = learned.reason();
        this.startupMemoryProfile = profile;
        this.activeRuntimeContextTokens = profile.contextTokens();
        prepareStatePrecisionProfile(profile);
        LocalModelRuntimeLog.write(
                "llama_startup_memory_profile",
                "profile=" + profile.reason()
                        + " | configured_context=" + configured
                        + " | active_context=" + profile.contextTokens()
                        + " | slots=" + this.configuration.kvSlots()
                        + " | available_mib=" + profile.availableMiB()
                        + " | model_mib=" + profile.modelMiB());
    }

    private String startupMemoryProfileReason() {
        return this.startupMemoryProfile == null ? "configured" : this.startupMemoryProfile.reason();
    }

    private void prepareStatePrecisionProfile(LlamaCppStartupMemoryPolicy.Profile profile) {
        long installedBytes = 0L;
        KoilExecutionPlan plan = this.koilExecutionPlan;
        KoilRuntimeBackend plannedBackend = plan == null ? KoilRuntimeBackend.UNKNOWN : plan.primaryBackend();
        KoilRuntimeBackend backend = LlamaCppBackendEvidence.resolve(
                plannedBackend,
                this.configuration.computeSettings(),
                this.resolvedComputeDevice,
                this.resolvedComputeDeviceDetail);
        String backendEvidenceSource = LlamaCppBackendEvidence.source(
                plannedBackend,
                this.configuration.computeSettings(),
                this.resolvedComputeDevice,
                this.resolvedComputeDeviceDetail);
        if (plan != null) {
            try {
                installedBytes = Long.parseLong(plan.decisions().getOrDefault("installedMemoryBytes", "0"));
            } catch (NumberFormatException ignored) {
                installedBytes = 0L;
            }
        }
        long availableBytes = profile == null ? 0L : Math.max(0L, profile.availableMiB()) * 1024L * 1024L;
        KoilMemoryPressureSnapshot memory = KoilMemoryBudgetPlanner.from(
                installedBytes, availableBytes, KoilMemoryPressureSnapshot.Phase.LAUNCH);
        KoilStateMemoryEstimate stateEstimate = KoilStateMemoryEstimate.fromPlanDecisions(
                plan == null ? Map.of() : plan.decisions(), effectiveContextTokens());
        KoilStatePrecisionDecision policyStatePrecision = KoilStatePrecisionPolicy.select(
                memory, effectiveContextTokens(), backend, stateEstimate);
        this.activeStatePrecision = LlamaCppBenchmarkStatePrecisionOverride.apply(policyStatePrecision);
        LocalModelRuntimeLog.write(
                "llama_state_precision",
                this.activeStatePrecision.summary()
                        + " | pressure=" + memory.pressure().name().toLowerCase(java.util.Locale.ROOT)
                        + " | context=" + effectiveContextTokens()
                        + " | backend=" + backend.name().toLowerCase(java.util.Locale.ROOT)
                        + " | backend_source=" + backendEvidenceSource
                        + " | reason=" + this.activeStatePrecision.reason());
    }

    private boolean universalPlanPrecisionMatchesLaunch() {
        return LlamaCppStatePrecisionCompatibility.matches(
                universalPlanStatePrecisionRegime(), this.activeStatePrecision);
    }

    private String universalPlanStatePrecisionRegime() {
        KoilExecutionPlan plan = this.koilExecutionPlan;
        if (plan == null || plan.decisions() == null) return "";
        String expected = plan.decisions().getOrDefault("tuned.statePrecisionRegime", "");
        if (expected == null || expected.isBlank()) return "";
        return KoilStatePrecisionEvidence.normalizeRegime(expected);
    }

    private String activeStatePrecisionRegime() {
        return LlamaCppStatePrecisionCompatibility.regime(this.activeStatePrecision);
    }

    private String statePrecisionSourceForDiagnostics() {
        if (this.activeStatePrecision == null) return "unobserved";
        boolean benchmarkOverrideStillActive = LlamaCppBenchmarkStatePrecisionOverride.current().isPresent();
        if (benchmarkOverrideStillActive) return "benchmark_override";
        return switch (this.activeMaxProfileSource) {
            case "universal_plan" -> "universal_plan";
            case "native_compat" -> "native_compat";
            case "native_store" -> "native_store";
            case "fit_fallback" -> "fit_fallback";
            default -> "launch_policy";
        };
    }

    private String statePrecisionReasonForDiagnostics() {
        if (this.activeStatePrecision == null) return "unobserved";
        String reason = this.activeStatePrecision.reason();
        if (reason == null) reason = "";
        if (!reason.startsWith("MAX benchmark precision override:")) return reason;
        if (LlamaCppBenchmarkStatePrecisionOverride.current().isPresent()) return reason;
        String regime = activeStatePrecisionRegime();
        return switch (this.activeMaxProfileSource) {
            case "universal_plan" -> "precision regime activated from universal calibration: " + regime;
            case "native_compat" -> "precision regime activated from exact native compatibility calibration: " + regime;
            case "native_store" -> "precision regime activated from native calibration: " + regime;
            default -> "precision regime retained from measured MAX activation: " + regime;
        };
    }

    private LlamaCppRuntimeMemoryEnvelopeStore.Recommendation learnedResidentContextRecommendation(
            int proposedContextTokens, long availableBytes
    ) {
        String hardwareFingerprint = this.koilExecutionPlan == null ? "" : this.koilExecutionPlan.hardwareFingerprint();
        return learnedResidentContextRecommendation(proposedContextTokens, availableBytes, hardwareFingerprint);
    }

    private LlamaCppRuntimeMemoryEnvelopeStore.Recommendation learnedResidentContextRecommendation(
            int proposedContextTokens, long availableBytes, String hardwareFingerprint
    ) {
        String precisionRegime = predictedStatePrecisionRegime(proposedContextTokens, availableBytes);
        return LlamaCppRuntimeMemoryEnvelopeStore.recommend(
                this.configuration.modelFile(), hardwareFingerprint == null ? "" : hardwareFingerprint, precisionRegime,
                proposedContextTokens, availableBytes);
    }

    private String predictedStatePrecisionRegime(int contextTokens, long availableBytes) {
        KoilExecutionPlan plan = this.koilExecutionPlan;
        long installedBytes = 0L;
        if (plan != null) {
            try {
                installedBytes = Long.parseLong(plan.decisions().getOrDefault("installedMemoryBytes", "0"));
            } catch (NumberFormatException ignored) {
                installedBytes = 0L;
            }
        }
        KoilRuntimeBackend plannedBackend = plan == null ? KoilRuntimeBackend.UNKNOWN : plan.primaryBackend();
        KoilRuntimeBackend backend = LlamaCppBackendEvidence.resolve(
                plannedBackend, this.configuration.computeSettings(),
                this.resolvedComputeDevice, this.resolvedComputeDeviceDetail);
        KoilMemoryPressureSnapshot memory = KoilMemoryBudgetPlanner.from(
                installedBytes, Math.max(0L, availableBytes), KoilMemoryPressureSnapshot.Phase.LAUNCH);
        KoilStateMemoryEstimate estimate = KoilStateMemoryEstimate.fromPlanDecisions(
                plan == null ? Map.of() : plan.decisions(), Math.max(1, contextTokens));
        KoilStatePrecisionDecision decision = KoilStatePrecisionPolicy.select(
                memory, Math.max(1, contextTokens), backend, estimate);
        return KoilStatePrecisionEvidence.regime(
                decision.keyPrecision().name(), decision.valuePrecision().name());
    }

    @Override
    public void observeKoilRuntimeMemory(KoilMemoryPressureStabilizer.Result observation) {
        if (observation == null || observation.effective() == null || this.startupMemoryProfile == null) return;
        int context = effectiveContextTokens();
        if (context <= 0) return;
        String hardwareFingerprint = this.koilExecutionPlan == null ? "" : this.koilExecutionPlan.hardwareFingerprint();
        LlamaCppRuntimeMemoryEnvelopeStore.record(
                this.configuration.modelFile(), hardwareFingerprint, activeStatePrecisionRegime(), context,
                this.startupMemoryProfile.availableBytes(), observation);
        this.residentPressureAttribution = LlamaCppProcessMemoryAttribution.sample(this.process, context, observation);
        LlamaCppResidentPressureController.Decision previousPressureDecision = this.residentPressureDecision;
        this.residentPressureDecision = this.residentPressureController.observe(this.residentPressureAttribution, observation);
        if (previousPressureDecision.mode() != this.residentPressureDecision.mode()) {
            LocalModelRuntimeLog.write(
                    "llama_resident_pressure_response",
                    "mode=" + this.residentPressureDecision.modeId()
                            + " | background_model_work=" + (this.residentPressureDecision.backgroundModelWorkAllowed() ? "allowed" : "blocked")
                            + " | model_degradation=" + (this.residentPressureDecision.modelDegradationAllowed() ? "eligible" : "frozen")
                            + " | user_inference=" + (this.residentPressureDecision.preserveTunedUserInference() ? "preserve_tuned" : "adaptive")
                            + " | reason=" + this.residentPressureDecision.reason()
            );
        }
        if (!this.residentPressureDecision.backgroundModelWorkAllowed()) {
            if ("preparing".equals(this.warmupState)) {
                this.warmupRevokedByMemory = true;
                Call warmup = this.startupWarmupCall;
                if (warmup != null) warmup.cancel();
                this.warmupState = "skipped_memory_pressure";
                publishWarmupHealthDetail();
            }
            if (!this.optionalStateEvictionRequested) {
                this.optionalStateEvictionRequested = true;
                scheduleOptionalStateEviction("resident pressure response: " + this.residentPressureDecision.modeId());
            }
        }
        LlamaCppRuntimeMemoryEnvelopeStore.Evidence evidence = LlamaCppRuntimeMemoryEnvelopeStore.evidence(
                this.configuration.modelFile(), hardwareFingerprint, activeStatePrecisionRegime(), context);
        if (evidence.residentUnsafe() && context > 2048) {
            LlamaCppRuntimeMemoryEnvelopeStore.Recommendation recommendation =
                    LlamaCppRuntimeMemoryEnvelopeStore.recommend(
                            this.configuration.modelFile(), hardwareFingerprint, activeStatePrecisionRegime(),
                            context, this.startupMemoryProfile.availableBytes());
            this.learnedResidentContextCeilingTokens = recommendation.contextTokens();
            this.learnedResidentContextReason = recommendation.reason();
        }
        publishRuntimeObservations();
    }

    private String validateInstallation() {
        if (!this.configuration.enabled()) {
            return "llama.cpp integration is disabled";
        }
        if (!this.configuration.localhostOnly()) {
            return "llama.cpp must bind to localhost";
        }
        if (this.configuration.apiKey().isBlank()) {
            return "llama.cpp local API key is missing";
        }
        if (this.configuration.executable() == null || !Files.isRegularFile(this.configuration.executable())) {
            return "llama.cpp server executable is missing";
        }
        if (!Files.isExecutable(this.configuration.executable())) {
            return "llama.cpp server executable is not executable";
        }
        if (this.configuration.modelFile() == null || !Files.isRegularFile(this.configuration.modelFile())) {
            return "selected GGUF model file is missing";
        }
        return "";
    }

    private ModelHealthSnapshot waitForReadiness() {
        long startedAt = System.nanoTime();
        boolean slowReported = false;
        while (!this.closed && !this.stopping) {
            Process current = this.process;
            if (current == null && this.startupUnexpectedExitCode != Integer.MIN_VALUE) {
                int exitCode = this.startupUnexpectedExitCode;
                String tail = this.startupUnexpectedExitTail;
                if (!this.startupMemoryRecoveryAttempted && (exitCode == 143 || exitCode == 137)) {
                    try {
                        this.startupMemoryRecoveryAttempted = true;
                        this.lastTerminationProvenance = "external_signal_during_startup";
                        prepareStartupMemoryProfile(true);
                        this.startupUnexpectedExitCode = Integer.MIN_VALUE;
                        this.startupUnexpectedExitTail = "";
                        synchronized (this.processLock) {
                            launchOwnedRuntime();
                        }
                        startedAt = System.nanoTime();
                        slowReported = false;
                        LocalModelRuntimeLog.write(
                                "llama_startup_recovery",
                                "retrying after signal exit " + exitCode
                                        + " | context=" + effectiveContextTokens()
                                        + " | profile=" + startupMemoryProfileReason());
                        continue;
                    } catch (Exception retryFailure) {
                        return failHealth("llama.cpp low-memory startup recovery failed: " + message(retryFailure));
                    }
                }
                String hint = compatibilityFailureHint(tail);
                return failHealth("llama.cpp exited before becoming ready (exit " + exitCode + ")"
                        + (exitSignal(exitCode).isBlank() ? "" : " (" + exitSignal(exitCode) + ")")
                        + (hint.isBlank() ? "" : " | " + hint)
                        + (tail.isBlank() ? "" : " | native tail: " + boundedRuntimeTail(tail, 1800)));
            }
            if (current == null && this.health.state() == ModelHealthState.FAILED) {
                return this.health;
            }
            if (current != null && !current.isAlive()) {
                int exitCode = current.exitValue();
                String tail = runtimeOutputTail();
                if (!this.startupMemoryRecoveryAttempted && (exitCode == 143 || exitCode == 137)) {
                    try {
                        this.startupMemoryRecoveryAttempted = true;
                        this.lastTerminationProvenance = "external_signal_during_startup:" + exitSignal(exitCode);
                        synchronized (this.processLock) {
                            if (this.process == current) {
                                this.process = null;
                                this.ownsProcess = false;
                            }
                            LocalModelOwnedProcessRegistry.unregister(current);
                            prepareStartupMemoryProfile(true);
                            this.startupUnexpectedExitCode = Integer.MIN_VALUE;
                            this.startupUnexpectedExitTail = "";
                            launchOwnedRuntime();
                        }
                        startedAt = System.nanoTime();
                        slowReported = false;
                        LocalModelRuntimeLog.write(
                                "llama_startup_recovery",
                                "retrying after signal exit " + exitCode
                                        + " | context=" + effectiveContextTokens()
                                        + " | profile=" + startupMemoryProfileReason());
                        continue;
                    } catch (Exception retryFailure) {
                        return failHealth("llama.cpp low-memory startup recovery failed: " + message(retryFailure));
                    }
                }
                String hint = compatibilityFailureHint(tail);
                return failHealth("llama.cpp exited before becoming ready (exit " + exitCode + ")"
                        + (exitSignal(exitCode).isBlank() ? "" : " (" + exitSignal(exitCode) + ")")
                        + (hint.isBlank() ? "" : " | " + hint)
                        + (tail.isBlank() ? "" : " | native tail: " + boundedRuntimeTail(tail, 1800)));
            }
            if (expectsAcceleratorPlacement()) {
                LlamaCppComputeSafetyPolicy.RuntimePressure pressure = LlamaCppComputeSafetyPolicy.runtimePressure();
                if (pressure.critical()) {
                    if (current != null && current.isAlive() && this.ownsProcess) {
                        current.destroyForcibly();
                    }
                    this.computeSafetyState = "pressure_abort";
                    this.computeSafetyDetail = pressure.summary();
                    int exactHybridLayers = exactHybridGpuLayers();
                    if (exactHybridLayers > 0 && usesConservativeHybridGeometry(exactHybridLayers)) {
                        LlamaCppComputeSettings effective = effectiveComputeSettings();
                        LlamaCppComputeStabilityStore.recordPressureUnsafe(
                                this.configuration.modelFile(),
                                effective.device(),
                                exactHybridLayers,
                                this.computeSafetyStartAvailableBytes,
                                pressure.availableBytes(),
                                "startup memory pressure abort");
                    }
                    LocalModelRuntimeLog.write("llama_compute_pressure_abort", pressure.summary()
                            + (exactHybridLayers > 0 ? " | requested_gpu_layers=" + exactHybridLayers : ""));
                    return failHealth("Koil stopped llama.cpp accelerator startup to protect system/UMA memory: "
                            + pressure.summary());
                }
            }
            if (compatibleRuntimeAvailable(this.selectedPort)) {
                return prepareRuntimeForUse("llama.cpp runtime ready");
            }
            if (!slowReported && System.nanoTime() - startedAt >= this.configuration.startupTimeout().toNanos()) {
                slowReported = true;
                updateHealth(ModelHealthState.STARTING, "llama.cpp is taking longer than expected; still waiting", diagnostics());
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return failHealth("llama.cpp startup was interrupted");
            }
        }
        return failHealth("llama.cpp startup cancelled");
    }

    private boolean expectsAcceleratorPlacement() {
        LlamaCppComputeSettings effective = effectiveComputeSettings();
        if (effective.mode() == LlamaCppComputeMode.CPU) return false;
        if (effective.mode() == LlamaCppComputeMode.MAX
                && this.activeMaxProfile != null
                && this.activeMaxProfile.placement() == LlamaCppMaxRuntimeProfile.Placement.CPU) {
            return false;
        }
        return true;
    }

    private ModelHealthSnapshot prepareRuntimeForUse(String runtimeDetail) {
        this.runtimeCapabilities = probeRuntimeCapabilities();
        this.compatibilityReport = LlamaCppCompatibilityReport.negotiate(
                this.protocolProfile, this.runtimeCapabilities, this.configuration.contextTokens());
        LocalModelRuntimeLog.write(
                "llama_compatibility",
                "model=" + this.configuration.modelId()
                        + " | family=" + this.protocolProfile.family()
                        + " | architecture=" + this.protocolProfile.architectureId()
                        + " | topology=" + this.protocolProfile.computeTopology()
                        + " | experts=" + this.protocolProfile.expertCount()
                        + " | active_experts=" + this.protocolProfile.activeExpertCount()
                        + " | tools=" + this.compatibilityReport.toolCalling()
                        + " | reasoning=" + this.compatibilityReport.reasoning()
                        + " | negotiation=" + this.compatibilityReport.status().name().toLowerCase(java.util.Locale.ROOT)
                        + " | gguf_inspected=" + this.protocolProfile.artifactInspected()
                        + " | runtime_probe=" + this.runtimeCapabilities.observed()
        );
        if (effectiveComputeSettings().mode() != LlamaCppComputeMode.CPU) {
            AcceleratorCanaryResult canary = acceleratorIntegrityCanary();
            LlamaCppOutputIntegrity.Assessment integrity = canary.assessment();
            if (!integrity.healthy() && canary.conclusive()) {
                this.computeIntegrityState = "failed";
                this.computeIntegrityDetail = integrity.detail();
                int exactHybridLayers = exactHybridGpuLayers();
                if (exactHybridLayers > 0 && usesConservativeHybridGeometry(exactHybridLayers)) {
                    LlamaCppComputeSettings effective = effectiveComputeSettings();
                    LlamaCppComputeStabilityStore.recordIntegrityUnsafe(
                            this.configuration.modelFile(),
                            effective.device(),
                            exactHybridLayers,
                            "accelerator integrity canary failed: " + integrity.detail());
                }
                LocalModelRuntimeLog.write(
                        "llama_compute_integrity_failed",
                        "mode=" + effectiveComputeSettings().mode().name().toLowerCase(java.util.Locale.ROOT)
                                + " | gpu_layers=" + actualGpuLayerDetail()
                                + " | attempts=" + canary.attempts()
                                + " | detail=" + integrity.detail()
                );
                return failHealth("llama.cpp accelerator integrity check failed before activation: " + integrity.detail());
            }
            if (!integrity.healthy()) {
                this.computeIntegrityState = "deferred";
                this.computeIntegrityDetail = canary.detail();
                LocalModelRuntimeLog.write(
                        "llama_compute_integrity_deferred",
                        "mode=" + effectiveComputeSettings().mode().name().toLowerCase(java.util.Locale.ROOT)
                                + " | gpu_layers=" + actualGpuLayerDetail()
                                + " | attempts=" + canary.attempts()
                                + " | detail=" + canary.detail()
                                + " | protection=streaming_integrity_gate"
                );
                scheduleDeferredAcceleratorIntegrityVerification(runtimeDetail);
            } else {
                markAcceleratorIntegrityPassed(integrity.detail());
            }
        } else {
            this.computeIntegrityState = "not_required";
            this.computeIntegrityDetail = "strict CPU path";
        }
        this.readyRuntimeDetail = runtimeDetail;
        if (this.startupWarmupBranches.isEmpty()) {
            this.warmupState = "disabled";
            return updateHealth(ModelHealthState.READY, runtimeDetail, diagnostics());
        }

        // Warmup is an optimization, never a readiness gate. Expose a healthy
        // llama.cpp runtime immediately and prepare the reusable prompt seed in
        // the background. The KV-slot lock serializes warmup with a first user
        // request if they race, but the UI/runtime no longer remains STARTING.
        this.warmupState = "preparing";
        this.warmupPrepared = 0;
        this.warmupPreemptedByRequest = false;
        this.warmupRevokedByMemory = false;
        ModelHealthSnapshot ready = updateHealth(
                ModelHealthState.READY,
                LlamaCppWarmupRuntimeDetail.detail(runtimeDetail, this.warmupState),
                diagnostics()
        );
        this.requests.execute(() -> warmStartupBranches(runtimeDetail));
        return ready;
    }

    private void warmStartupBranches(String runtimeDetail) {
        if (this.closed || this.stopping) return;
        if (!optionalCacheGrowthAllowed()) {
            skipOptionalStartupWarmup("cache_growth_not_admitted");
            return;
        }
        long started = System.nanoTime();
        List<String> failures = new ArrayList<>();
        for (ModelStartupWarmupBranch branch : this.startupWarmupBranches) {
            if (this.warmupRevokedByMemory || !optionalCacheGrowthAllowed()) {
                skipOptionalStartupWarmup("cache_growth_revoked");
                return;
            }
            if (this.closed || this.stopping || this.warmupPreemptedByRequest) {
                if (this.warmupPreemptedByRequest) {
                    this.warmupState = "preempted_by_request";
                    publishWarmupHealthDetail();
                }
                return;
            }
            try {
                primeStartupBranch(branch);
                this.warmupPrepared++;
            } catch (Exception exception) {
                if (this.warmupRevokedByMemory || !optionalCacheGrowthAllowed()) {
                    skipOptionalStartupWarmup("cache_growth_revoked_during_prefill");
                    return;
                }
                if (this.warmupPreemptedByRequest) {
                    this.warmupState = "preempted_by_request";
                    publishWarmupHealthDetail();
                    return;
                }
                String detail = branch.id() + ": " + message(exception);
                failures.add(detail);
                LocalModelRuntimeLog.write("llama_startup_warmup_failed", detail);
            }
        }
        this.warmupState = failures.isEmpty() ? "ready" : "ready_degraded";
        long elapsedMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        LocalModelRuntimeLog.write(
                "llama_startup_warmup_ready",
                "prepared=" + this.warmupPrepared + "/" + this.warmupTotal
                        + " | elapsed_ms=" + elapsedMs
                        + (failures.isEmpty() ? "" : " | optional_failures=" + failures.size())
        );
        if (!this.closed && !this.stopping && this.health.state() == ModelHealthState.READY) {
            publishWarmupHealthDetail();
        }
    }

    private boolean optionalCacheGrowthAllowed() {
        if (this.residentPressureDecision != null && !this.residentPressureDecision.backgroundModelWorkAllowed()) {
            return false;
        }
        return LlamaCppOptionalCacheAdmission.allowed(
                this.koilLaunchOptionalAdmissions, this.koilLiveOptionalAdmissions);
    }

    private long optionalCacheGrowthCeilingBytes() {
        return LlamaCppOptionalCacheAdmission.ceilingBytes(
                this.koilLaunchOptionalAdmissions, this.koilLiveOptionalAdmissions);
    }

    private void skipOptionalStartupWarmup(String reason) {
        this.warmupState = "skipped_memory_pressure";
        publishWarmupHealthDetail();
        LocalModelRuntimeLog.write(
                "llama_startup_warmup_skipped",
                "reason=" + reason + " | cache_growth_ceiling_bytes=" + optionalCacheGrowthCeilingBytes()
        );
    }

    private void publishWarmupHealthDetail() {
        if (this.closed || this.stopping) return;
        synchronized (this.healthLock) {
            ModelHealthSnapshot current = this.health;
            if (current.state() != ModelHealthState.READY) return;
            String base = this.readyRuntimeDetail == null || this.readyRuntimeDetail.isBlank()
                    ? current.detail()
                    : this.readyRuntimeDetail;
            this.health = new ModelHealthSnapshot(
                    ModelHealthState.READY,
                    LlamaCppWarmupRuntimeDetail.detail(base, this.warmupState),
                    current.queueDepth(),
                    Instant.now(),
                    diagnostics()
            );
        }
    }

    private AcceleratorCanaryResult acceleratorIntegrityCanary() {
        AcceleratorCanaryResult last = null;
        for (int attempt = 1; attempt <= ACCELERATOR_CANARY_ATTEMPTS; attempt++) {
            if (this.closed || this.stopping) {
                return AcceleratorCanaryResult.inconclusive(
                        LlamaCppOutputIntegrity.Assessment.failed("canary cancelled because provider is stopping"),
                        attempt - 1,
                        "provider is stopping");
            }
            AcceleratorCanaryResult current = acceleratorIntegrityCanaryAttempt(attempt);
            last = current;
            if (current.assessment().healthy() || current.conclusive()) return current;
            if (attempt < ACCELERATOR_CANARY_ATTEMPTS) {
                long delay = ACCELERATOR_CANARY_RETRY_BASE_MILLIS * attempt;
                LocalModelRuntimeLog.write(
                        "llama_compute_integrity_retry",
                        "attempt=" + attempt + "/" + ACCELERATOR_CANARY_ATTEMPTS
                                + " | retry_in_ms=" + delay
                                + " | detail=" + current.detail()
                );
                if (!sleepCanaryRetry(delay)) break;
            }
        }
        return last == null
                ? AcceleratorCanaryResult.inconclusive(
                        LlamaCppOutputIntegrity.Assessment.failed("canary did not run"), 0, "canary did not run")
                : last;
    }

    private AcceleratorCanaryResult acceleratorIntegrityCanaryAttempt(int attempt) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", this.configuration.modelId());
        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", "Runtime integrity check. Reply using only ordinary ASCII text with the phrase KOIL_RUNTIME_OK.");
        messages.add(user);
        payload.add("messages", messages);
        payload.addProperty("temperature", 0.0D);
        payload.addProperty("seed", 1337);
        payload.addProperty("max_tokens", 24);
        payload.addProperty("stream", false);
        payload.addProperty("cache_prompt", false);

        Request request = authenticated(new Request.Builder()
                .url(baseUrl(this.selectedPort) + "/v1/chat/completions")
                .post(RequestBody.create(payload.toString(), JSON))).build();
        Call call = this.http.newCall(request);
        call.timeout().timeout(25L, TimeUnit.SECONDS);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String detail = "canary HTTP " + response.code() + ": " + response.message();
                if (isTransientCanaryHttp(response.code())) {
                    return AcceleratorCanaryResult.inconclusive(
                            LlamaCppOutputIntegrity.Assessment.failed(detail), attempt, detail);
                }
                return AcceleratorCanaryResult.conclusive(
                        LlamaCppOutputIntegrity.Assessment.failed(detail), attempt);
            }
            ResponseBody body = response.body();
            if (body == null) {
                String detail = "canary returned an empty HTTP body";
                return AcceleratorCanaryResult.conclusive(
                        LlamaCppOutputIntegrity.Assessment.failed(detail), attempt);
            }
            JsonObject root = JsonParser.parseString(body.string()).getAsJsonObject();
            JsonArray choices = root.has("choices") && root.get("choices").isJsonArray()
                    ? root.getAsJsonArray("choices") : new JsonArray();
            if (choices.isEmpty() || !choices.get(0).isJsonObject()) {
                String detail = "canary response contained no completion choice";
                return AcceleratorCanaryResult.conclusive(
                        LlamaCppOutputIntegrity.Assessment.failed(detail), attempt);
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.has("message") && choice.get("message").isJsonObject()
                    ? choice.getAsJsonObject("message") : new JsonObject();
            String visible = string(message, "content", "");
            String reasoning = string(message, "reasoning_content", "");
            String combined = (visible + "\n" + reasoning).strip();
            return AcceleratorCanaryResult.conclusive(LlamaCppOutputIntegrity.canary(combined), attempt);
        } catch (Exception failure) {
            String detail = "canary transport/protocol failure: " + message(failure);
            if (isTransientCanaryFailure(failure)) {
                return AcceleratorCanaryResult.inconclusive(
                        LlamaCppOutputIntegrity.Assessment.failed(detail), attempt, detail);
            }
            return AcceleratorCanaryResult.conclusive(
                    LlamaCppOutputIntegrity.Assessment.failed(detail), attempt);
        }
    }

    private void scheduleDeferredAcceleratorIntegrityVerification(String runtimeDetail) {
        this.requests.execute(() -> {
            if (!sleepCanaryRetry(ACCELERATOR_CANARY_BACKGROUND_DELAY_MILLIS)) return;
            if (this.closed || this.stopping || this.health.state() == ModelHealthState.FAILED) return;
            AcceleratorCanaryResult retry = acceleratorIntegrityCanary();
            if (retry.assessment().healthy()) {
                markAcceleratorIntegrityPassed("deferred startup canary passed: " + retry.assessment().detail());
                if (!this.closed && !this.stopping && this.health.state() != ModelHealthState.FAILED) {
                    updateHealth(ModelHealthState.READY,
                            runtimeDetail + "; deferred accelerator integrity canary passed",
                            diagnostics());
                }
                return;
            }
            if (!retry.conclusive()) {
                this.computeIntegrityState = "deferred";
                this.computeIntegrityDetail = retry.detail();
                LocalModelRuntimeLog.write(
                        "llama_compute_integrity_deferred_retry",
                        "still inconclusive | attempts=" + retry.attempts() + " | detail=" + retry.detail());
                return;
            }
            this.computeIntegrityState = "degraded";
            this.computeIntegrityDetail = retry.assessment().detail();
            int exactHybridLayers = exactHybridGpuLayers();
            if (exactHybridLayers > 0 && usesConservativeHybridGeometry(exactHybridLayers)) {
                LlamaCppComputeSettings effective = effectiveComputeSettings();
                LlamaCppComputeStabilityStore.recordIntegrityUnsafe(
                        this.configuration.modelFile(),
                        effective.device(),
                        exactHybridLayers,
                        "deferred accelerator integrity canary failed: " + retry.assessment().detail());
            }
            LocalModelRuntimeLog.write(
                    "llama_compute_integrity_failed_deferred",
                    "mode=" + effectiveComputeSettings().mode().name().toLowerCase(java.util.Locale.ROOT)
                            + " | gpu_layers=" + actualGpuLayerDetail()
                            + " | detail=" + retry.assessment().detail());
            updateHealth(ModelHealthState.DEGRADED,
                    "llama.cpp deferred accelerator integrity canary failed: " + retry.assessment().detail(),
                    diagnostics());
        });
    }

    private void markAcceleratorIntegrityPassed(String detail) {
        this.computeIntegrityState = "passed";
        this.computeIntegrityDetail = detail == null || detail.isBlank() ? "healthy" : detail;
        int exactHybridLayers = exactHybridGpuLayers();
        if (exactHybridLayers > 0 && this.actualGpuLayers == exactHybridLayers) {
            LlamaCppComputeSettings effective = effectiveComputeSettings();
            LlamaCppComputeStabilityStore.recordStable(
                    this.configuration.modelFile(),
                    effective.device(),
                    exactHybridLayers,
                    LlamaCppComputeStabilityStore.currentAvailableMemoryBytes(),
                    "runtime ready, exact GPU layer telemetry matched, integrity canary passed");
        }
        LocalModelRuntimeLog.write(
                "llama_compute_integrity",
                "passed | mode=" + effectiveComputeSettings().mode().name().toLowerCase(java.util.Locale.ROOT)
                        + " | gpu_layers=" + actualGpuLayerDetail()
        );
    }

    private boolean sleepCanaryRetry(long millis) {
        if (millis <= 0L) return !this.closed && !this.stopping;
        try {
            Thread.sleep(millis);
            return !this.closed && !this.stopping;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isTransientCanaryHttp(int code) {
        return code == 408 || code == 425 || code == 429 || code == 500
                || code == 502 || code == 503 || code == 504;
    }

    private static boolean isTransientCanaryFailure(Exception failure) {
        if (failure == null) return false;
        String detail = message(failure).toLowerCase(java.util.Locale.ROOT);
        return detail.contains("canceled")
                || detail.contains("cancelled")
                || detail.contains("timeout")
                || detail.contains("timed out")
                || detail.contains("connection reset")
                || detail.contains("connection shutdown")
                || detail.contains("unexpected end of stream")
                || detail.contains("broken pipe")
                || detail.contains("refused");
    }

    private record AcceleratorCanaryResult(
            LlamaCppOutputIntegrity.Assessment assessment,
            boolean conclusive,
            int attempts,
            String detail
    ) {
        private AcceleratorCanaryResult {
            assessment = assessment == null
                    ? LlamaCppOutputIntegrity.Assessment.failed("missing canary assessment")
                    : assessment;
            attempts = Math.max(0, attempts);
            detail = detail == null || detail.isBlank() ? assessment.detail() : detail.strip();
        }

        static AcceleratorCanaryResult conclusive(LlamaCppOutputIntegrity.Assessment assessment, int attempts) {
            return new AcceleratorCanaryResult(assessment, true, attempts,
                    assessment == null ? "" : assessment.detail());
        }

        static AcceleratorCanaryResult inconclusive(
                LlamaCppOutputIntegrity.Assessment assessment,
                int attempts,
                String detail
        ) {
            return new AcceleratorCanaryResult(assessment, false, attempts, detail);
        }
    }

    private void primeStartupBranch(ModelStartupWarmupBranch branch) throws IOException {
        int slot = Math.max(0, Math.min(this.configuration.kvSlots() - 1, branch.preferredSlot()));
        String stableFingerprint = branch.stableFingerprint();
        StreamingModelRequest seedRequest = new StreamingModelRequest(
                UUID.randomUUID(),
                "__koil_startup__:" + branch.id(),
                branch.systemPrompt(),
                List.of(ModelMessage.user("")),
                branch.tools(),
                1,
                this.configuration.requestTimeout(),
                Map.ofEntries(
                        Map.entry("cache_slot", Integer.toString(slot)),
                        Map.entry("cache_session_key", "__koil_startup__:" + branch.id()),
                        Map.entry("cache_stable_fingerprint", stableFingerprint),
                        Map.entry("cache_request_fingerprint", branch.seedFingerprint()),
                        Map.entry("cache_seed", "true"),
                        Map.entry("cache_seed_branch", branch.id()),
                        Map.entry("cache_seed_fingerprint", branch.seedFingerprint()),
                        Map.entry("cache_seed_fingerprints", branch.seedFingerprint()),
                        Map.entry("mode", branch.mode()),
                        Map.entry("tool_registry_version", branch.toolRegistryVersion())
                )
        );
        LlamaCppKvCacheManager.Lease lease = this.cacheManager.acquire(seedRequest, this.selectedPort);
        boolean complete = false;
        try {
            if (!lease.restored()) {
                LlamaCppToolNameMap toolNames = LlamaCppToolNameMap.from(seedRequest);
                JsonObject payload = requestPayload(seedRequest, toolNames, lease.slot());
                payload.addProperty("max_tokens", 0);
                payload.addProperty("stream", false);
                payload.remove("stream_options");
                // Warmup is only a KV prefill. Keep it protocol-minimal so an
                // older llama.cpp build cannot fail the optimization because it
                // lacks optional stream/reasoning extensions.
                payload.remove("return_progress");
                payload.remove("return_tokens");
                payload.remove("timings_per_token");
                payload.remove("sse_ping_interval");
                payload.remove("reasoning_format");
                payload.remove("chat_template_kwargs");
                long started = System.nanoTime();
                Request request = authenticated(new Request.Builder()
                        .url(baseUrl(this.selectedPort) + "/v1/chat/completions")
                        .post(RequestBody.create(payload.toString(), JSON))).build();
                Call warmupCall = this.http.newCall(request);
                warmupCall.timeout().timeout(STARTUP_WARMUP_BUDGET_SECONDS, TimeUnit.SECONDS);
                this.startupWarmupCall = warmupCall;
                try (Response response = warmupCall.execute()) {
                    if (!response.isSuccessful()) throw httpFailure(response);
                    String body = response.body() == null ? "" : response.body().string();
                    int promptTokens = 0;
                    int cachedTokens = 0;
                    try {
                        JsonObject parsed = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
                        JsonObject usage = parsed.has("usage") && parsed.get("usage").isJsonObject()
                                ? parsed.getAsJsonObject("usage") : new JsonObject();
                        promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").getAsInt() : 0;
                        JsonObject details = usage.has("prompt_tokens_details") && usage.get("prompt_tokens_details").isJsonObject()
                                ? usage.getAsJsonObject("prompt_tokens_details") : new JsonObject();
                        cachedTokens = details.has("cached_tokens") ? details.get("cached_tokens").getAsInt() : 0;
                    } catch (Exception ignored) {
                    }
                    LocalModelRuntimeLog.write(
                            "llama_startup_warmup_prefill",
                            "branch=" + branch.id() + " | slot=" + lease.slot()
                                    + " | prompt_tokens=" + promptTokens
                                    + " | cached_tokens=" + cachedTokens
                                    + " | elapsed_ms=" + Math.max(0L, (System.nanoTime() - started) / 1_000_000L)
                    );
                } finally {
                    if (this.startupWarmupCall == warmupCall) this.startupWarmupCall = null;
                }
            } else {
                LocalModelRuntimeLog.write(
                        "llama_startup_warmup_restore",
                        "branch=" + branch.id() + " | slot=" + lease.slot() + " | source=persistent_seed"
                );
            }
            this.cacheManager.complete(lease, this.selectedPort, seedRequest);
            complete = true;
        } finally {
            if (!complete) this.cacheManager.fail(lease);
        }
    }

    private boolean compatibleRuntimeAvailable(int port) {
        if (port <= 0) {
            return false;
        }
        Request healthRequest = authenticated(new Request.Builder().url(baseUrl(port) + "/health").get()).build();
        try (Response response = this.http.newCall(healthRequest).execute()) {
            if (!response.isSuccessful()) {
                return false;
            }
        } catch (Exception exception) {
            return false;
        }
        Request modelsRequest = authenticated(new Request.Builder().url(baseUrl(port) + "/v1/models").get()).build();
        try (Response response = this.http.newCall(modelsRequest).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return false;
            }
            JsonElement parsed = JsonParser.parseString(response.body().string());
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("data")) {
                return false;
            }
            JsonArray data = parsed.getAsJsonObject().getAsJsonArray("data");
            boolean anyModel = false;
            for (JsonElement element : data) {
                if (!element.isJsonObject()) continue;
                anyModel = true;
                if (this.configuration.modelId().equals(string(element.getAsJsonObject(), "id", ""))) {
                    return true;
                }
            }
            // When Koil launched the process itself on the selected private port,
            // /health plus any served model is authoritative. Do not wait forever
            // merely because a llama.cpp build reports a canonical/model-file ID
            // instead of the --alias string Koil requested.
            if (this.ownsProcess && anyModel) {
                LocalModelRuntimeLog.write(
                        "llama_runtime_alias_relaxed",
                        "configured=" + this.configuration.modelId() + " | port=" + port
                );
                return true;
            }
        } catch (Exception exception) {
            return false;
        }
        return false;
    }

    @Override
    public CompletableFuture<ModelPerformanceBenchmarkResult> benchmark(ModelPerformanceBenchmarkRequest request) {
        ModelPerformanceBenchmarkRequest safe = request == null
                ? new ModelPerformanceBenchmarkRequest("Koil benchmark", 64, java.time.Duration.ofMinutes(2), "benchmark")
                : request;
        if (this.health.state() != ModelHealthState.READY) {
            return CompletableFuture.failedFuture(new IllegalStateException("llama.cpp must be ready before benchmarking"));
        }
        return CompletableFuture.supplyAsync(() -> benchmarkBlocking(safe), this.requests);
    }

    private ModelPerformanceBenchmarkResult benchmarkBlocking(ModelPerformanceBenchmarkRequest request) {
        long started = System.nanoTime();
        JsonObject payload = new JsonObject();
        payload.addProperty("prompt", request.prompt());
        payload.addProperty("n_predict", request.outputTokens());
        payload.addProperty("temperature", 0.0D);
        payload.addProperty("seed", 1337);
        payload.addProperty("ignore_eos", true);
        payload.addProperty("cache_prompt", false);
        payload.addProperty("stream", false);
        payload.addProperty("timings_per_token", false);

        Request httpRequest = authenticated(new Request.Builder()
                .url(baseUrl(this.selectedPort) + "/completion")
                .post(RequestBody.create(payload.toString(), JSON))).build();
        Call call = this.http.newCall(httpRequest);
        call.timeout().timeout(Math.max(1L, request.timeout().toMillis()), TimeUnit.MILLISECONDS);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) throw httpFailure(response);
            ResponseBody body = response.body();
            if (body == null) throw new IllegalStateException("llama.cpp benchmark returned an empty response");
            JsonObject root = JsonParser.parseString(body.string()).getAsJsonObject();
            String benchmarkText = string(root, "content", "");
            LlamaCppOutputIntegrity.Assessment benchmarkIntegrity = LlamaCppOutputIntegrity.finalOutput(benchmarkText);
            if (!benchmarkIntegrity.healthy()) {
                throw new IllegalStateException("llama.cpp benchmark output failed integrity validation: "
                        + benchmarkIntegrity.detail());
            }
            JsonObject timings = root.has("timings") && root.get("timings").isJsonObject()
                    ? root.getAsJsonObject("timings")
                    : new JsonObject();
            int promptTokens = intValue(timings, "prompt_n");
            int generatedTokens = intValue(timings, "predicted_n");
            double promptTps = doubleValue(timings, "prompt_per_second");
            double generationTps = doubleValue(timings, "predicted_per_second");
            double promptMillis = doubleValue(timings, "prompt_ms");
            double generationMillis = doubleValue(timings, "predicted_ms");
            double firstPredictionMillis = doubleValue(timings, "predicted_per_token_ms");
            double ttft = promptMillis > 0.0D
                    ? promptMillis + Math.max(0.0D, firstPredictionMillis)
                    : 0.0D;
            long wallMillis = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            ModelPerformanceBenchmarkResult result = new ModelPerformanceBenchmarkResult(
                    promptTokens, generatedTokens, promptTps, generationTps,
                    promptMillis, generationMillis, ttft, wallMillis, request.label()
            );
            if (!result.valid()) {
                throw new IllegalStateException("llama.cpp benchmark response did not contain valid native timings: " + timings);
            }
            LocalModelRuntimeLog.write(
                    "llama_benchmark",
                    "label=" + request.label()
                            + " | prompt_tps=" + String.format(java.util.Locale.ROOT, "%.2f", result.promptTokensPerSecond())
                            + " | generation_tps=" + String.format(java.util.Locale.ROOT, "%.2f", result.generationTokensPerSecond())
                            + " | ttft_ms=" + String.format(java.util.Locale.ROOT, "%.2f", result.timeToFirstTokenMillis())
                            + " | prompt_tokens=" + result.promptTokens()
                            + " | generated_tokens=" + result.generatedTokens()
            );
            return result;
        } catch (IOException failure) {
            throw new IllegalStateException("llama.cpp benchmark transport failed: " + message(failure), failure);
        }
    }

    private static int intValue(JsonObject object, String key) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsInt() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static double doubleValue(JsonObject object, String key) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsDouble() : 0.0D;
        } catch (Exception ignored) {
            return 0.0D;
        }
    }

    @Override
    public ModelCancellationHandle generate(StreamingModelRequest request, StreamingModelObserver observer) {
        cancelStartupWarmupForUserRequest();
        if (this.health.state() != ModelHealthState.READY) {
            observer.onFailure(request.id(), "runtime_not_ready", this.health.detail(), null);
            return CancelledHandle.INSTANCE;
        }
        CallCancellation cancellation = new CallCancellation();
        this.requests.execute(() -> generateBlocking(request, observer, cancellation));
        return cancellation;
    }


    private void cancelStartupWarmupForUserRequest() {
        this.warmupPreemptedByRequest = true;
        Call warmup = this.startupWarmupCall;
        if (warmup != null && !warmup.isCanceled()) {
            LocalModelRuntimeLog.write(
                    "llama_startup_warmup_preempted",
                    "User generation takes priority over optional prompt-cache warmup."
            );
            warmup.cancel();
        }
    }

    private void generateBlocking(
            StreamingModelRequest request,
            StreamingModelObserver observer,
            CallCancellation cancellation
    ) {
        long started = System.nanoTime();
        long firstToken = 0L;
        LlamaCppKvCacheManager.Lease cacheLease = null;
        LlamaCppPrefillLivenessPolicy.Budget prefillBudget;
        AtomicBoolean debugTelemetryDone = new AtomicBoolean(false);
        AtomicBoolean prefillWatchdogDone = new AtomicBoolean(false);
        AtomicBoolean providerOutputStarted = new AtomicBoolean(false);
        AtomicBoolean prefillStalled = new AtomicBoolean(false);
        AtomicBoolean promptProgressExpected = new AtomicBoolean(false);
        AtomicBoolean promptProgressSeen = new AtomicBoolean(false);
        AtomicBoolean promptPrefillComplete = new AtomicBoolean(false);
        AtomicLong lastPrefillProgressNanos = new AtomicLong(System.nanoTime());
        LlamaCppToolNameMap toolNames = LlamaCppToolNameMap.from(request);
        LlamaCppStreamingIntegrityGate integrityGate = new LlamaCppStreamingIntegrityGate(request.id(), observer);
        OpenAiChatStreamDecoder decoder = new OpenAiChatStreamDecoder(request.id(), new StreamingModelObserver() {
            @Override
            public void onTextDelta(UUID requestId, String delta) {
                integrityGate.text(delta);
            }

            @Override
            public void onReasoningDelta(UUID requestId, String delta) {
                integrityGate.reasoning(delta);
            }

            @Override
            public void onExposedData(UUID requestId, ModelExposedData exposed) {
                integrityGate.exposed(exposed == null ? null : exposed.withMetadata(Map.of(
                        "modelFamily", LlamaCppLocalModelProvider.this.protocolProfile.family(),
                        "architecture", LlamaCppLocalModelProvider.this.protocolProfile.architectureId(),
                        "reasoningAdapter", LlamaCppLocalModelProvider.this.protocolProfile.reasoningAdapter()
                )));
            }

            @Override
            public void onTelemetry(UUID requestId, ModelRuntimeTelemetry telemetry) {
                if (telemetry != null && "prompt_progress".equals(telemetry.kind())) {
                    promptProgressSeen.set(true);
                    lastPrefillProgressNanos.set(System.nanoTime());
                    int processed = intMetadata(telemetry.fields(), "promptProcessedTokens", 0);
                    int total = intMetadata(telemetry.fields(), "promptTotalTokens", 0);
                    if (total > 0 && processed >= total) {
                        promptPrefillComplete.set(true);
                    }
                }
                observer.onTelemetry(requestId, telemetry);
            }
        }, toolNames::toCanonical);
        observer.onState(request.id(), ModelRequestState.PREPARING_CONTEXT, "preparing");
        JsonObject payload;
        try {
            cacheLease = this.cacheManager.acquire(request, this.selectedPort);
            payload = requestPayload(request, toolNames, cacheLease.slot());
            if ("true".equalsIgnoreCase(request.metadata().getOrDefault("prefill_cache_bypass", "false"))) {
                payload.addProperty("cache_prompt", false);
            }
            prefillBudget = LlamaCppPrefillLivenessPolicy.forPayloadCharacters(payload.toString().length());
            observer.onTelemetry(request.id(), ModelRuntimeTelemetry.of(
                    "request_configuration",
                    "llama.cpp request · slot " + cacheLease.slot() + " · context " + effectiveContextTokens() + " tokens",
                    Map.ofEntries(
                            Map.entry("slotId", cacheLease.slot()),
                            Map.entry("contextTokens", effectiveContextTokens()),
                            Map.entry("estimatedPromptTokens", prefillBudget.estimatedPromptTokens()),
                            Map.entry("prefillInitialGraceSeconds", prefillBudget.initialGraceSeconds()),
                            Map.entry("prefillProgressStallSeconds", prefillBudget.progressStallSeconds()),
                            Map.entry("modelFamily", this.protocolProfile.family()),
                            Map.entry("architecture", this.protocolProfile.architectureId()),
                            Map.entry("reasoningAdapter", this.protocolProfile.reasoningAdapter()),
                            Map.entry("toolAdapter", this.protocolProfile.toolAdapter()),
                            Map.entry("toolsSupplied", request.tools().size()),
                            Map.entry("gpuLayers", actualGpuLayerDetail()),
                            Map.entry("computeTopology", this.protocolProfile.computeTopology().name().toLowerCase(java.util.Locale.ROOT))
                    )
            ));
        } catch (Exception exception) {
            this.cacheManager.fail(cacheLease);
            observer.onFailure(request.id(), "request_invalid", message(exception), exception);
            return;
        }
        observer.onState(request.id(), ModelRequestState.PREFILLING, "prefilling");
        try (Response response = executeGenerationRequest(
                payload, cancellation, request.id(), observer, promptProgressExpected)) {
            if (!response.isSuccessful()) {
                throw httpFailure(response);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new OpenAiChatStreamDecoder.ProtocolException("empty_response", "llama.cpp returned an empty response.", null);
            }
            if (promptProgressExpected.get()) {
                lastPrefillProgressNanos.set(System.nanoTime());
                CompletableFuture.runAsync(
                        () -> monitorPrefillLiveness(
                                request.id(), cancellation, providerOutputStarted, promptProgressSeen, promptPrefillComplete,
                                lastPrefillProgressNanos, prefillStalled, prefillWatchdogDone, prefillBudget),
                        this.requests
                );
            }
            if (ModelDebugMode.enabled() && cacheLease != null) {
                int telemetrySlot = cacheLease.slot();
                CompletableFuture.runAsync(
                        () -> pollDebugSlotTelemetry(request.id(), telemetrySlot, observer, cancellation, debugTelemetryDone),
                        this.requests
                );
            }
            boolean generatingAnnounced = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancellation.isCancellationRequested()) {
                        cancelled(request, observer, cancellation);
                        return;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    int beforeActivity = decoder.streamedOutputUnits();
                    int beforeTextUnits = decoder.streamedTextUnits();
                    decoder.accept(line.substring(5).trim());
                    if (!generatingAnnounced && decoder.streamedOutputUnits() > beforeActivity) {
                        generatingAnnounced = true;
                        providerOutputStarted.set(true);
                        prefillWatchdogDone.set(true);
                        firstToken = System.nanoTime();
                        observer.onState(
                                request.id(),
                                decoder.streamedReasoningUnits() > 0 && decoder.streamedTextUnits() == 0
                                        ? ModelRequestState.THINKING
                                        : ModelRequestState.GENERATING,
                                decoder.streamedReasoningUnits() > 0 && decoder.streamedTextUnits() == 0
                                        ? "reasoning"
                                        : "writing"
                        );
                    } else if (generatingAnnounced && beforeTextUnits == 0 && decoder.streamedTextUnits() > 0) {
                        observer.onState(request.id(), ModelRequestState.GENERATING, "writing");
                    }
                    if (firstToken != 0L && decoder.liveCompletionTokens() > 0) {
                        observer.onUsage(request.id(), liveUsage(decoder, started, firstToken));
                    }
                }
            }
            observer.onState(request.id(), ModelRequestState.FINALIZING, "finishing");
            decoder.finishTools();
            integrityGate.finish(!decoder.toolCalls().isEmpty());
            if (!decoder.toolCalls().isEmpty()) {
                for (var toolCall : decoder.toolCalls()) {
                    observer.onState(request.id(), ModelRequestState.SELECTING_TOOL, toolCall.toolId());
                    observer.onToolCall(request.id(), toolCall);
                }
            }
            long finished = System.nanoTime();
            long ttft = firstToken == 0L ? 0L : Math.max(0L, (firstToken - started) / 1_000_000L);
            ModelUsage partial = decoder.usage(ttft, 0.0D);
            double seconds = Math.max(0.001D, (finished - (firstToken == 0L ? started : firstToken)) / 1_000_000_000.0D);
            ModelUsage usage = new ModelUsage(
                    partial.promptTokens(),
                    partial.completionTokens(),
                    partial.reusedPrefixTokens(),
                    partial.queueMillis(),
                    partial.timeToFirstTokenMillis(),
                    partial.completionTokens() / seconds
            );
            observer.onUsage(request.id(), usage);
            int evaluatedPromptTokens = Math.max(0, usage.promptTokens() - usage.reusedPrefixTokens());
            LocalModelRuntimeLog.write(
                    "llama_request_metrics",
                    "request=" + request.id()
                            + " | prompt_tokens=" + usage.promptTokens()
                            + " | cached_tokens=" + usage.reusedPrefixTokens()
                            + " | evaluated_prompt_tokens=" + evaluatedPromptTokens
                            + " | completion_tokens=" + usage.completionTokens()
                            + " | ttft_ms=" + usage.timeToFirstTokenMillis()
                            + " | decode_tps=" + String.format(java.util.Locale.ROOT, "%.2f", usage.tokensPerSecond())
                            + " | reasoning_chars=" + decoder.reasoningText().length()
            );
            observer.onState(request.id(), ModelRequestState.COMPLETED, "completed");
            observer.onComplete(new StreamingModelResponse(
                    request.id(),
                    decoder.text(),
                    decoder.reasoningText(),
                    decoder.toolCalls(),
                    usage,
                    decoder.finishReason()
            ));
            this.cacheManager.complete(cacheLease, this.selectedPort, request);
            cacheLease = null;
            LocalModelRuntimeLog.write("llama_request", request.id() + " completed");
        } catch (LlamaCppOutputIntegrity.IntegrityException exception) {
            this.computeIntegrityState = "degraded";
            this.computeIntegrityDetail = exception.getMessage();
            updateHealth(ModelHealthState.DEGRADED,
                    "llama.cpp inference integrity guard rejected accelerator output: " + exception.getMessage(),
                    diagnostics());
            LocalModelRuntimeLog.write(
                    "llama_inference_integrity_failed",
                    "request=" + request.id() + " | mode="
                            + effectiveComputeSettings().mode().name().toLowerCase(java.util.Locale.ROOT)
                            + " | gpu_layers=" + actualGpuLayerDetail()
                            + " | detail=" + exception.getMessage()
            );
            observer.onState(request.id(), ModelRequestState.FAILED, "inference integrity check failed");
            observer.onFailure(request.id(), "inference_integrity_failed",
                    "llama.cpp returned output consistent with an unstable compute placement; Koil blocked the corrupted stream.",
                    exception);
        } catch (OpenAiChatStreamDecoder.ProtocolException exception) {
            ContextOverflow overflow = contextOverflow(exception);
            int contextRecoveryAttempt = intMetadata(request.metadata(), "context_recovery_attempt", 0);
            if (overflow != null
                    && contextRecoveryAttempt < CONTEXT_RECOVERY_MAX_ATTEMPTS
                    && !cancellation.isCancellationRequested()) {
                prefillWatchdogDone.set(true);
                debugTelemetryDone.set(true);
                this.cacheManager.fail(cacheLease);
                cacheLease = null;
                cancellation.call = null;
                int nextAttempt = contextRecoveryAttempt + 1;
                StreamingModelRequest compacted = contextRecoveryRequest(request, nextAttempt);
                LocalModelRuntimeLog.write(
                        "llama_context_recovery",
                        "request=" + request.id()
                                + " | attempt=" + nextAttempt
                                + " | submitted_tokens=" + overflow.submittedTokens()
                                + " | context_tokens=" + overflow.contextTokens()
                                + " | messages=" + request.messages().size() + "->" + compacted.messages().size()
                                + " | tools=" + request.tools().size() + "->" + compacted.tools().size()
                );
                observer.onState(request.id(), ModelRequestState.PREPARING_CONTEXT, "compacting context after overflow");
                observer.onTelemetry(request.id(), ModelRuntimeTelemetry.of(
                        "context_recovery",
                        "Context window filled during a tool continuation; compacting older model/tool history and retrying",
                        Map.of(
                                "attempt", nextAttempt,
                                "submittedTokens", overflow.submittedTokens(),
                                "contextTokens", overflow.contextTokens(),
                                "messagesBefore", request.messages().size(),
                                "messagesAfter", compacted.messages().size(),
                                "toolsBefore", request.tools().size(),
                                "toolsAfter", compacted.tools().size()
                        )
                ));
                generateBlocking(compacted, observer, cancellation);
                return;
            }
            observer.onState(request.id(), ModelRequestState.FAILED, exception.getMessage());
            observer.onFailure(request.id(), exception.code(), exception.getMessage(), exception);
        } catch (IOException exception) {
            if (prefillStalled.get() && !cancellation.isCancellationRequested()) {
                int recoveryAttempt = intMetadata(request.metadata(), "prefill_recovery_attempt", 0);
                if (recoveryAttempt < 1) {
                    int stalledSlot = cacheLease == null ? -1 : cacheLease.slot();
                    prefillWatchdogDone.set(true);
                    debugTelemetryDone.set(true);
                    this.cacheManager.fail(cacheLease);
                    cacheLease = null;
                    cancellation.call = null;
                    if (stalledSlot >= 0) {
                        this.cacheManager.recoverStalledPrefill(this.selectedPort, stalledSlot);
                    }
                    LocalModelRuntimeLog.write(
                            "llama_prefill_recovery",
                            "request=" + request.id() + " | attempt=1 | cache_bypass=true"
                    );
                    observer.onState(request.id(), ModelRequestState.PREPARING_CONTEXT, "recovering prompt prefill");
                    observer.onTelemetry(request.id(), ModelRuntimeTelemetry.of(
                            "prefill_recovery",
                            "Prompt prefill stopped making progress; retrying once with a clean cache path",
                            Map.of("attempt", 1, "cacheBypass", true)
                    ));
                    generateBlocking(prefillRecoveryRequest(request, recoveryAttempt + 1), observer, cancellation);
                    return;
                }
                observer.onState(request.id(), ModelRequestState.FAILED, "prompt prefill stalled");
                observer.onFailure(
                        request.id(),
                        "prefill_stalled",
                        "llama.cpp prompt prefill stopped making progress after a clean-cache retry.",
                        exception
                );
            } else if (cancellation.isCancellationRequested()) {
                cancelled(request, observer, cancellation);
            } else {
                observer.onState(request.id(), ModelRequestState.FAILED, message(exception));
                observer.onFailure(request.id(), "transport_failed", message(exception), exception);
            }
        } catch (Exception exception) {
            observer.onState(request.id(), ModelRequestState.FAILED, message(exception));
            observer.onFailure(request.id(), "generation_failed", message(exception), exception);
        } finally {
            prefillWatchdogDone.set(true);
            debugTelemetryDone.set(true);
            cancellation.call = null;
            this.cacheManager.fail(cacheLease);
        }
    }

    private void monitorPrefillLiveness(
            UUID requestId,
            CallCancellation cancellation,
            AtomicBoolean providerOutputStarted,
            AtomicBoolean promptProgressSeen,
            AtomicBoolean promptPrefillComplete,
            AtomicLong lastPrefillProgressNanos,
            AtomicBoolean prefillStalled,
            AtomicBoolean done,
            LlamaCppPrefillLivenessPolicy.Budget budget
    ) {
        while (!done.get() && !providerOutputStarted.get() && !promptPrefillComplete.get()
                && !cancellation.isCancellationRequested()) {
            try {
                Thread.sleep(PREFILL_WATCHDOG_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (done.get() || providerOutputStarted.get() || promptPrefillComplete.get()
                    || cancellation.isCancellationRequested()) return;
            long silentFor = System.nanoTime() - lastPrefillProgressNanos.get();
            boolean sawProgress = promptProgressSeen.get();
            if (!LlamaCppPrefillLivenessPolicy.stalled(silentFor, sawProgress, budget)) continue;

            if (prefillStalled.compareAndSet(false, true)) {
                long allowedSeconds = sawProgress ? budget.progressStallSeconds() : budget.initialGraceSeconds();
                LocalModelRuntimeLog.write(
                        "llama_prefill_stalled",
                        "request=" + requestId
                                + " | inactive_ms=" + (silentFor / 1_000_000L)
                                + " | progress_seen=" + sawProgress
                                + " | allowed_seconds=" + allowedSeconds
                                + " | estimated_prompt_tokens=" + budget.estimatedPromptTokens()
                );
                Call active = cancellation.call;
                if (active != null && !active.isCanceled()) active.cancel();
            }
            return;
        }
    }

    private static StreamingModelRequest prefillRecoveryRequest(StreamingModelRequest request, int attempt) {
        java.util.LinkedHashMap<String, String> metadata = new java.util.LinkedHashMap<>(request.metadata());
        metadata.put("prefill_recovery_attempt", Integer.toString(Math.max(1, attempt)));
        metadata.put("prefill_cache_bypass", "true");
        return new StreamingModelRequest(
                request.id(),
                request.conversationId(),
                request.systemPrompt(),
                request.messages(),
                request.tools(),
                request.maximumOutputTokens(),
                request.timeout(),
                metadata
        );
    }

    private static int intMetadata(Map<String, String> metadata, String key, int fallback) {
        if (metadata == null || key == null) return fallback;
        try {
            return Integer.parseInt(metadata.getOrDefault(key, Integer.toString(fallback)));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private void pollDebugSlotTelemetry(
            UUID requestId,
            int slotId,
            StreamingModelObserver observer,
            CallCancellation cancellation,
            AtomicBoolean done
    ) {
        boolean unavailableReported = false;
        while (!done.get() && !cancellation.isCancellationRequested() && ModelDebugMode.enabled()) {
            Request request = authenticated(new Request.Builder()
                    .url(baseUrl(this.selectedPort) + "/slots")
                    .get()).build();
            try (Response response = this.http.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    if (!unavailableReported) {
                        unavailableReported = true;
                        observer.onTelemetry(requestId, ModelRuntimeTelemetry.of(
                                "slot_state",
                                "llama.cpp slot telemetry unavailable",
                                Map.of("httpStatus", response.code(), "slotId", slotId)
                        ));
                    }
                } else {
                    JsonElement parsed = JsonParser.parseString(response.body().string());
                    if (parsed.isJsonArray()) {
                        JsonObject slot = null;
                        for (JsonElement element : parsed.getAsJsonArray()) {
                            if (!element.isJsonObject()) continue;
                            JsonObject candidate = element.getAsJsonObject();
                            if (intValue(candidate, "id") == slotId) {
                                slot = candidate;
                                break;
                            }
                        }
                        if (slot != null) emitDebugSlotTelemetry(requestId, slotId, slot, observer);
                    }
                }
            } catch (Exception exception) {
                if (!unavailableReported) {
                    unavailableReported = true;
                    observer.onTelemetry(requestId, ModelRuntimeTelemetry.of(
                            "slot_state",
                            "llama.cpp slot telemetry unavailable",
                            Map.of("slotId", slotId, "detail", message(exception))
                    ));
                }
            }
            if (done.get() || cancellation.isCancellationRequested()) return;
            try {
                Thread.sleep(750L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void emitDebugSlotTelemetry(
            UUID requestId,
            int slotId,
            JsonObject slot,
            StreamingModelObserver observer
    ) {
        JsonObject params = slot != null && slot.has("params") && slot.get("params").isJsonObject()
                ? slot.getAsJsonObject("params") : new JsonObject();
        JsonObject next = slot != null && slot.has("next_token") && slot.get("next_token").isJsonObject()
                ? slot.getAsJsonObject("next_token") : new JsonObject();
        int decoded = intValue(next, "n_decoded");
        int context = intValue(slot, "n_ctx");
        boolean processing = slot != null && slot.has("is_processing") && slot.get("is_processing").getAsBoolean();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("slotId", slotId);
        fields.put("taskId", intValue(slot, "id_task"));
        fields.put("contextTokens", context);
        fields.put("decodedTokens", decoded);
        fields.put("remainingPredictionTokens", intValue(next, "n_remain"));
        fields.put("processing", processing);
        fields.put("speculative", slot != null && slot.has("speculative") && slot.get("speculative").getAsBoolean());
        fields.put("temperature", doubleValue(params, "temperature"));
        fields.put("topK", intValue(params, "top_k"));
        fields.put("topP", doubleValue(params, "top_p"));
        fields.put("minP", doubleValue(params, "min_p"));
        fields.put("repeatPenalty", doubleValue(params, "repeat_penalty"));
        String reasoningFormat = string(params, "reasoning_format", "");
        if (!reasoningFormat.isBlank()) fields.put("reasoningFormat", reasoningFormat);
        String chatFormat = string(params, "chat_format", "");
        if (!chatFormat.isBlank()) fields.put("chatFormat", chatFormat);
        observer.onTelemetry(requestId, ModelRuntimeTelemetry.of(
                "slot_state",
                "Slot " + slotId + " · decoded " + decoded + " · context " + context
                        + " · " + (processing ? "processing" : "idle"),
                fields
        ));
    }

    private static ModelUsage liveUsage(
            OpenAiChatStreamDecoder decoder,
            long startedNanos,
            long firstTokenNanos
    ) {
        long now = System.nanoTime();
        long ttft = Math.max(0L, (firstTokenNanos - startedNanos) / 1_000_000L);
        double seconds = Math.max(0.001D, (now - firstTokenNanos) / 1_000_000_000.0D);
        ModelUsage reported = decoder.usage(ttft, 0.0D);
        int liveCompletion = decoder.liveCompletionTokens();
        return new ModelUsage(
                reported.promptTokens(),
                liveCompletion,
                reported.reusedPrefixTokens(),
                reported.queueMillis(),
                reported.timeToFirstTokenMillis(),
                reported.tokensPerSecond() > 0.0D
                        ? reported.tokensPerSecond()
                        : liveCompletion / seconds
        );
    }

    private void cancelled(
            StreamingModelRequest request,
            StreamingModelObserver observer,
            CallCancellation cancellation
    ) {
        observer.onState(request.id(), ModelRequestState.CANCELLED, cancellation.cancellationReason());
        observer.onFailure(request.id(), "cancelled", cancellation.cancellationReason(), null);
    }

    private JsonObject requestPayload(StreamingModelRequest request, LlamaCppToolNameMap toolNames, int slotId) {
        boolean requestAllowsReasoning = Boolean.parseBoolean(
                request.metadata().getOrDefault("allow_native_reasoning", "true")
        );
        return LlamaCppChatRequestCompiler.compile(
                request,
                toolNames,
                slotId,
                this.configuration.modelId(),
                this.protocolProfile,
                this.compatibilityReport.systemRole(),
                !request.tools().isEmpty(),
                !request.tools().isEmpty() && this.compatibilityReport.parallelToolCalling(),
                this.compatibilityReport.reasoning() && requestAllowsReasoning
        );
    }

    /** Compatibility hook retained for existing proofs. */
    static List<ModelMessage> templateMessages(List<ModelMessage> messages) {
        return LlamaCppChatRequestCompiler.project(
                messages,
                LlamaCppProtocolProfile.RolePolicy.STRICT_ALTERNATING
        );
    }

    private int configuredEffectiveContextTokens() {
        int configured = this.configuration.contextTokens();
        int artifact = this.protocolProfile.artifactContextTokens();
        int observed = this.runtimeCapabilities.contextTokens();
        int effective = configured;
        if (artifact > 0) effective = Math.min(effective, artifact);
        if (observed > 0) effective = Math.min(effective, observed);
        return Math.max(512, effective);
    }

    private int effectiveContextTokens() {
        int effective = configuredEffectiveContextTokens();
        if (this.activeRuntimeContextTokens > 0) effective = Math.min(effective, this.activeRuntimeContextTokens);
        return Math.max(512, effective);
    }

    private LlamaCppRuntimeCapabilities probeRuntimeCapabilities() {
        if (this.selectedPort <= 0) return LlamaCppRuntimeCapabilities.unknown();
        Request request = authenticated(new Request.Builder().url(baseUrl(this.selectedPort) + "/props").get()).build();
        try (Response response = this.http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return LlamaCppRuntimeCapabilities.unknown();
            JsonElement parsed = JsonParser.parseString(response.body().string());
            return parsed.isJsonObject()
                    ? LlamaCppRuntimeCapabilities.fromProps(parsed.getAsJsonObject(), this.configuration.modelId())
                    : LlamaCppRuntimeCapabilities.unknown();
        } catch (Exception ignored) {
            return LlamaCppRuntimeCapabilities.unknown();
        }
    }

    private Response executeGenerationRequest(
            JsonObject payload,
            CallCancellation cancellation,
            UUID requestId,
            StreamingModelObserver observer,
            AtomicBoolean promptProgressExpected
    ) throws IOException {
        JsonObject effectivePayload = JsonParser.parseString(payload.toString()).getAsJsonObject();
        Call call = generationCall(effectivePayload);
        cancellation.call = call;
        if (cancellation.isCancellationRequested()) call.cancel();
        Response response = call.execute();

        if (shouldRetryWithoutOptionalStreamExtensions(response, effectivePayload, cancellation)) {
            int rejectedStatus = response.code();
            response.close();

            effectivePayload = JsonParser.parseString(effectivePayload.toString()).getAsJsonObject();
            effectivePayload.remove("timings_per_token");
            effectivePayload.remove("return_progress");
            effectivePayload.remove("return_tokens");
            effectivePayload.remove("sse_ping_interval");
            LocalModelRuntimeLog.write(
                    "llama_stream_extension_fallback",
                    "request=" + requestId + " | http_status=" + rejectedStatus
                            + " | retrying_without_optional_stream_extensions=true"
            );
            observer.onTelemetry(requestId, ModelRuntimeTelemetry.of(
                    "stream_compatibility",
                    "llama.cpp rejected optional stream progress/telemetry fields; retrying without them",
                    Map.of("httpStatus", rejectedStatus, "fallback", "standard_chat_stream")
            ));

            call = generationCall(effectivePayload);
            cancellation.call = call;
            if (cancellation.isCancellationRequested()) call.cancel();
            response = call.execute();
        }

        if (shouldRetryWithoutOptionalReasoningNegotiation(response, effectivePayload, cancellation)) {
            int rejectedStatus = response.code();
            response.close();

            effectivePayload = JsonParser.parseString(effectivePayload.toString()).getAsJsonObject();
            effectivePayload.remove("reasoning_format");
            effectivePayload.remove("chat_template_kwargs");
            LocalModelRuntimeLog.write(
                    "llama_reasoning_negotiation_fallback",
                    "request=" + requestId + " | http_status=" + rejectedStatus
                            + " | retrying_without_optional_reasoning_negotiation=true"
            );
            observer.onTelemetry(requestId, ModelRuntimeTelemetry.of(
                    "reasoning_compatibility",
                    "llama.cpp rejected optional reasoning negotiation fields; retrying with the model template defaults",
                    Map.of("httpStatus", rejectedStatus, "fallback", "template_default_reasoning")
            ));

            call = generationCall(effectivePayload);
            cancellation.call = call;
            if (cancellation.isCancellationRequested()) call.cancel();
            response = call.execute();
        }
        if (promptProgressExpected != null) {
            promptProgressExpected.set(effectivePayload.has("return_progress"));
        }
        return response;
    }

    private Call generationCall(JsonObject payload) {
        Request httpRequest = authenticated(new Request.Builder()
                .url(baseUrl(this.selectedPort) + "/v1/chat/completions")
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(payload.toString(), JSON))).build();
        return this.http.newCall(httpRequest);
    }

    private boolean shouldRetryWithoutOptionalStreamExtensions(
            Response response,
            JsonObject payload,
            CallCancellation cancellation
    ) {
        if (response == null || response.isSuccessful() || cancellation.isCancellationRequested()) return false;
        int status = response.code();
        if (status != 400 && status != 422) return false;
        return payload.has("timings_per_token") || payload.has("return_progress")
                || payload.has("return_tokens") || payload.has("sse_ping_interval");
    }

    private boolean shouldRetryWithoutOptionalReasoningNegotiation(
            Response response,
            JsonObject payload,
            CallCancellation cancellation
    ) {
        if (response == null || response.isSuccessful() || cancellation.isCancellationRequested()) return false;
        int status = response.code();
        if (status != 400 && status != 422) return false;
        return payload.has("reasoning_format") || payload.has("chat_template_kwargs");
    }

    private OpenAiChatStreamDecoder.ProtocolException httpFailure(Response response) {
        String code = "http_" + response.code();
        String detail = "llama.cpp request failed with HTTP " + response.code();
        try {
            if (response.body() != null) {
                JsonElement parsed = JsonParser.parseString(response.body().string());
                if (parsed.isJsonObject()) {
                    JsonObject error = parsed.getAsJsonObject().has("error")
                            && parsed.getAsJsonObject().get("error").isJsonObject()
                            ? parsed.getAsJsonObject().getAsJsonObject("error")
                            : parsed.getAsJsonObject();
                    code = string(error, "type", code);
                    detail = string(error, "message", detail);
                }
            }
        } catch (Exception ignored) {
        }
        return new OpenAiChatStreamDecoder.ProtocolException(code, detail, null);
    }


    private static ContextOverflow contextOverflow(OpenAiChatStreamDecoder.ProtocolException exception) {
        if (exception == null || exception.getMessage() == null) return null;
        String detail = exception.getMessage();
        Matcher matcher = CONTEXT_OVERFLOW_DETAIL.matcher(detail);
        if (!matcher.find()) {
            String lower = detail.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("exceeds the available context size")
                    ? new ContextOverflow(0, 0)
                    : null;
        }
        try {
            return new ContextOverflow(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        } catch (NumberFormatException ignored) {
            return new ContextOverflow(0, 0);
        }
    }

    private StreamingModelRequest contextRecoveryRequest(StreamingModelRequest request, int attempt) {
        int messageLimit = attempt <= 1 ? 10 : 6;
        List<ModelMessage> messages = coherentContextTail(request.messages(), messageLimit);
        List<ModelToolDefinition> tools = request.tools();
        if (attempt > 1 && tools.size() > 18) {
            tools = compactContextTools(tools, messages, request.metadata().getOrDefault("required_tool_ids", ""), 18);
        }
        Map<String, String> metadata = new LinkedHashMap<>(request.metadata());
        metadata.put("context_recovery_attempt", Integer.toString(Math.max(1, attempt)));
        metadata.put("prefill_cache_bypass", "true");
        metadata.put("context_recovery_compacted", "true");
        return new StreamingModelRequest(
                request.id(),
                request.conversationId(),
                request.systemPrompt(),
                messages,
                tools,
                request.maximumOutputTokens(),
                request.timeout(),
                Map.copyOf(metadata)
        );
    }

    /**
     * Retains the newest coherent provider turns. Tool call/result pairs are kept
     * together whenever the cut falls inside a pair, while old verbose web/tool
     * evidence is dropped first. The durable Koil conversation is never mutated.
     */
    private static List<ModelMessage> coherentContextTail(List<ModelMessage> messages, int maximum) {
        if (messages == null || messages.size() <= maximum) {
            return messages == null ? List.of() : List.copyOf(messages);
        }
        int start = Math.max(0, messages.size() - Math.max(2, maximum));
        // Never begin with a tool result whose assistant tool call was discarded.
        while (start > 0 && messages.get(start).role() == ModelRole.TOOL) {
            start--;
        }
        // If backing up exposed a run of assistant tool calls, keep the complete run.
        while (start > 0 && isAssistantToolTurn(messages.get(start)) && isAssistantToolTurn(messages.get(start - 1))) {
            start--;
        }
        return List.copyOf(messages.subList(start, messages.size()));
    }

    private static boolean isAssistantToolTurn(ModelMessage message) {
        return message != null
                && message.role() == ModelRole.ASSISTANT
                && !message.toolCallId().isBlank()
                && message.metadata().containsKey("tool_name");
    }

    private static List<ModelToolDefinition> compactContextTools(
            List<ModelToolDefinition> tools,
            List<ModelMessage> retainedMessages,
            String requiredCsv,
            int maximum
    ) {
        if (tools == null || tools.size() <= maximum) return tools == null ? List.of() : List.copyOf(tools);
        java.util.LinkedHashSet<String> priority = new java.util.LinkedHashSet<>();
        if (requiredCsv != null && !requiredCsv.isBlank()) {
            for (String id : requiredCsv.split(",")) {
                String safe = id.strip();
                if (!safe.isBlank()) priority.add(safe);
            }
        }
        if (retainedMessages != null) {
            for (ModelMessage message : retainedMessages) {
                if (message == null) continue;
                String tool = message.metadata().getOrDefault("tool_name", "").strip();
                if (!tool.isBlank()) priority.add(tool);
            }
        }
        LinkedHashMap<String, ModelToolDefinition> selected = new LinkedHashMap<>();
        for (String id : priority) {
            for (ModelToolDefinition tool : tools) {
                if (tool.id().equals(id)) selected.putIfAbsent(id, tool);
            }
        }
        // Keep read-only discovery families available because a web/research round
        // may need one changed lookup after old evidence was compacted.
        for (ModelToolDefinition tool : tools) {
            if (selected.size() >= maximum) break;
            String id = tool.id();
            if (id.startsWith("internet.") || id.startsWith("browser.") || id.startsWith("content.")
                    || id.startsWith("dataset.") || id.startsWith("minecraft.") || id.startsWith("koil.")) {
                selected.putIfAbsent(id, tool);
            }
        }
        for (ModelToolDefinition tool : tools) {
            if (selected.size() >= maximum) break;
            selected.putIfAbsent(tool.id(), tool);
        }
        return List.copyOf(selected.values());
    }

    private record ContextOverflow(int submittedTokens, int contextTokens) {
    }

    @Override
    public CompletableFuture<Void> stop() {
        if (this.closed && this.health.state() == ModelHealthState.STOPPED) {
            return CompletableFuture.completedFuture(null);
        }
        this.lastTerminationProvenance = "koil_provider_stop";
        this.closed = true;
        this.stopping = true;
        return CompletableFuture.runAsync(this::stopBlocking, this.lifecycle);
    }

    private void stopBlocking() {
        updateHealth(ModelHealthState.STOPPING, "stopping llama.cpp runtime", Map.of());
        Process current;
        boolean owned;
        synchronized (this.processLock) {
            current = this.process;
            owned = this.ownsProcess;
            this.process = null;
            this.ownsProcess = false;
        }
        // Persist the latest active KV state only at a lifecycle boundary.
        // This keeps normal same-session requests off the disk-checkpoint path.
        if (this.selectedPort > 0) {
            try {
                this.cacheManager.flushAll(this.selectedPort);
            } catch (Exception exception) {
                LocalModelRuntimeLog.write("llama_cache_flush_failed", message(exception));
            }
        }
        if (owned && current != null && current.isAlive()) {
            current.destroy();
            try {
                if (!current.waitFor(5L, TimeUnit.SECONDS)) {
                    current.destroyForcibly();
                    current.waitFor(2L, TimeUnit.SECONDS);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
        LocalModelOwnedProcessRegistry.unregister(current);
        updateHealth(ModelHealthState.STOPPED, "llama.cpp runtime stopped", Map.of());
        this.requests.shutdownNow();
        this.lifecycle.shutdown();
        LocalModelRuntimeLog.write("llama_shutdown", owned ? "owned runtime stopped" : "provider disconnected");
    }

    private void handleProcessExit(Process exited) {
        boolean jvmShutdown = LocalModelOwnedProcessRegistry.isJvmShutdownInProgress();
        LocalModelOwnedProcessRegistry.unregister(exited);
        if (jvmShutdown || this.stopping || this.closed) {
            return;
        }
        synchronized (this.processLock) {
            if (this.process != exited) {
                return;
            }
            this.process = null;
            this.ownsProcess = false;
        }

        // Process.onExit can run before the stdout/stderr reader has consumed the
        // final assertion/backtrace lines. Give it a short bounded drain window so
        // crash diagnostics describe the native failure rather than only the exit code.
        CompletableFuture<Void> capture = this.outputCaptureFuture;
        if (capture != null && !capture.isDone()) {
            try {
                capture.get(750L, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
        }

        int exitCode = exited.exitValue();
        String signal = exitSignal(exitCode);
        String tail = runtimeOutputTail();
        if (this.health.state() == ModelHealthState.STARTING) {
            this.startupUnexpectedExitCode = exitCode;
            this.startupUnexpectedExitTail = tail;
            this.lastTerminationProvenance = signal.isBlank()
                    ? "unexpected_exit_during_startup"
                    : "external_signal_during_startup:" + signal;
            LocalModelRuntimeLog.write(
                    "llama_startup_exit",
                    "exit=" + exitCode + (signal.isBlank() ? "" : " | signal=" + signal)
                            + " | context=" + effectiveContextTokens()
                            + " | profile=" + startupMemoryProfileReason());
            return;
        }
        this.lastTerminationProvenance = signal.isBlank()
                ? "unexpected_runtime_exit"
                : "external_signal_after_ready:" + signal;
        Map<String, String> crashDiagnostics = new LinkedHashMap<>(diagnostics());
        crashDiagnostics.put("exitCode", Integer.toString(exitCode));
        if (!signal.isBlank()) crashDiagnostics.put("exitSignal", signal);
        if (!tail.isBlank()) crashDiagnostics.put("lastRuntimeOutput", tail);

        String compatibilityHint = compatibilityFailureHint(tail);
        String detail = "llama.cpp exited with code " + exitCode
                + (signal.isBlank() ? "" : " (" + signal + ")")
                + (compatibilityHint.isBlank() ? "" : " | " + compatibilityHint)
                + (tail.isBlank() ? "" : " | native tail: " + boundedRuntimeTail(tail, 1800));
        updateHealth(ModelHealthState.FAILED, detail, Map.copyOf(crashDiagnostics));
        LocalModelRuntimeLog.write("llama_crash", detail);
        LocalModelReliabilityStore.recordCrash(this.configuration.modelId(), detail);
    }


    private String compatibilityFailureHint(String runtimeTail) {
        String lower = runtimeTail == null ? "" : runtimeTail.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("unknown model architecture")
                || lower.contains("unsupported model architecture")
                || lower.contains("unknown architecture")) {
            return "runtime does not support GGUF architecture '" + this.protocolProfile.architectureId()
                    + "'; update the managed llama.cpp runtime";
        }
        if (lower.contains("failed to load model") || lower.contains("error loading model")) {
            return "GGUF load failed after compatibility inspection; architecture="
                    + this.protocolProfile.architectureId() + ", family=" + this.protocolProfile.family();
        }
        if (lower.contains("chat template") && (lower.contains("error") || lower.contains("failed"))) {
            return "embedded chat template failed; profile=" + this.protocolProfile.chatTemplateAdapter();
        }
        return "";
    }

    private void captureOutput(Process launched) {
        this.outputCaptureFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(launched.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    recordRuntimeLine(line);
                    observeRuntimeLine(line);
                    LocalModelRuntimeLog.write("llama_runtime", line);
                }
            } catch (IOException exception) {
                if (!this.stopping) {
                    LocalModelRuntimeLog.write("llama_log_error", message(exception));
                }
            }
        }, this.requests);
    }

    private void observeRuntimeLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }

        boolean changed = false;

        Matcher modelLayers = MODEL_LAYER_COUNT.matcher(line);
        if (modelLayers.find()) {
            try {
                int repeatingLayers = Math.max(0, Integer.parseInt(modelLayers.group(1)));
                int totalOffloadableLayers = repeatingLayers + 1;
                if (totalOffloadableLayers != this.actualModelLayers) {
                    this.actualModelLayers = totalOffloadableLayers;
                    changed = true;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if (GPU_OUTPUT_OFFLOAD.matcher(line).find() && !this.observedGpuOutputLayer) {
            this.observedGpuOutputLayer = true;
            changed = true;
        }

        Matcher repeating = GPU_REPEATING_OFFLOAD.matcher(line);
        if (repeating.find()) {
            try {
                int count = Math.max(0, Integer.parseInt(repeating.group(1)));
                if (count != this.observedRepeatingGpuLayers) {
                    this.observedRepeatingGpuLayers = count;
                    changed = true;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if (GPU_MODEL_BUFFER.matcher(line).find() && !this.observedGpuModelBuffer) {
            this.observedGpuModelBuffer = true;
            changed = true;
        }

        Matcher offload = GPU_LAYER_OFFLOAD.matcher(line);
        if (offload.find()) {
            try {
                int observedGpuLayers = Math.max(0, Integer.parseInt(offload.group(1)));
                int observedModelLayers = Math.max(observedGpuLayers, Integer.parseInt(offload.group(2)));
                if (observedGpuLayers != this.actualGpuLayers
                        || observedModelLayers != this.actualModelLayers) {
                    this.actualGpuLayers = observedGpuLayers;
                    this.actualModelLayers = observedModelLayers;
                    changed = true;
                }
            } catch (NumberFormatException ignored) {
            }
        } else {
            int inferredGpuLayers = inferredGpuLayersFromDetailedTelemetry();
            if (inferredGpuLayers >= 0 && inferredGpuLayers != this.actualGpuLayers) {
                this.actualGpuLayers = inferredGpuLayers;
                changed = true;
            }
        }

        if (changed) {
            publishRuntimeObservations();
        }
    }

    private int inferredGpuLayersFromDetailedTelemetry() {
        if (this.observedRepeatingGpuLayers < 0) {
            return -1;
        }
        int inferred = this.observedRepeatingGpuLayers + (this.observedGpuOutputLayer ? 1 : 0);
        if (inferred <= 0 && !this.observedGpuModelBuffer) {
            return -1;
        }
        return Math.max(0, inferred);
    }

    private void resetRuntimeObservations() {
        synchronized (this.runtimeOutputLock) {
            this.runtimeOutputTail.clear();
        }
        this.observedRepeatingGpuLayers = -1;
        this.observedGpuOutputLayer = false;
        this.observedGpuModelBuffer = false;
        this.computeIntegrityState = "not_checked";
        this.computeIntegrityDetail = "";
        this.computeSafetyState = "not_checked";
        this.computeSafetyDetail = "";
        this.computeSafetyStartAvailableBytes = 0L;
        this.resolvedComputeDevice = "";
        this.resolvedComputeDeviceDetail = "";
        this.activeMaxProfile = null;
        this.activeMaxProfileSource = "none";
        this.activeMaxProfileTuned = false;
        this.activeRuntimeContextTokens = 0;
        this.residentPressureAttribution = LlamaCppProcessMemoryAttribution.Snapshot.unknown("not sampled");
        this.residentPressureController.reset();
        this.residentPressureDecision = LlamaCppResidentPressureController.Decision.normal();
        this.startupMemoryProfile = null;
        this.activeStatePrecision = new KoilStatePrecisionDecision(
                KoilStatePrecision.FP16, KoilStatePrecision.FP16, false, 1.0,
                "default full-precision state");
        if (this.configuration.computeSettings().mode() == LlamaCppComputeMode.CPU) {
            this.actualGpuLayers = 0;
            this.actualModelLayers = -1;
        } else {
            this.actualGpuLayers = -1;
            this.actualModelLayers = -1;
        }
    }

    private void recordRuntimeLine(String line) {
        if (line == null || line.isBlank()) return;
        String normalized = line.replace('\r', ' ').replace('\n', ' ').strip();
        if (normalized.isEmpty()) return;
        synchronized (this.runtimeOutputLock) {
            this.runtimeOutputTail.addLast(normalized);
            while (this.runtimeOutputTail.size() > RUNTIME_OUTPUT_TAIL_LINES) {
                this.runtimeOutputTail.removeFirst();
            }
        }
    }

    private String runtimeOutputTail() {
        String joined;
        synchronized (this.runtimeOutputLock) {
            joined = String.join(" || ", this.runtimeOutputTail);
        }
        return boundedRuntimeTail(joined, RUNTIME_OUTPUT_TAIL_CHARS);
    }


    private void resolveAutomaticComputeDevice() {
        LlamaCppComputeSettings settings = this.configuration.computeSettings();
        if (settings.mode() == LlamaCppComputeMode.CPU) {
            this.resolvedComputeDevice = "";
            this.resolvedComputeDeviceDetail = "";
            return;
        }
        try {
            LlamaCppDeviceProbe.ProbeResult probe = LlamaCppDeviceProbe.probe(this.configuration.executable());
            if (!settings.device().isBlank()) {
                this.resolvedComputeDevice = settings.device();
                probe.devices().stream()
                        .filter(device -> device.id().equalsIgnoreCase(settings.device()))
                        .findFirst()
                        .ifPresent(device -> this.resolvedComputeDeviceDetail = device.detail());
                return;
            }
            if (probe.status() == LlamaCppDeviceProbe.Status.AVAILABLE && probe.devices().size() == 1) {
                this.resolvedComputeDevice = probe.devices().get(0).id();
                this.resolvedComputeDeviceDetail = probe.devices().get(0).detail();
                LocalModelRuntimeLog.write(
                        "llama_compute_device_resolved",
                        "automatic -> " + this.resolvedComputeDevice + " | " + probe.summary()
                );
            }
        } catch (Exception exception) {
            if (!settings.device().isBlank()) this.resolvedComputeDevice = settings.device();
            LocalModelRuntimeLog.write("llama_compute_device_resolve_failed", message(exception));
        }
    }

    private LlamaCppComputeSettings effectiveComputeSettings() {
        LlamaCppComputeSettings settings = this.configuration.computeSettings();
        if (settings.mode() == LlamaCppComputeMode.CPU
                || !settings.device().isBlank()
                || this.resolvedComputeDevice.isBlank()) {
            return settings;
        }
        return settings.withDevice(this.resolvedComputeDevice);
    }

    private static String boundedRuntimeTail(String value, int maxChars) {
        String text = value == null ? "" : value.strip();
        int limit = Math.max(128, maxChars);
        if (text.length() <= limit) return text;
        return "..." + text.substring(text.length() - limit + 3);
    }

    private static String exitSignal(int exitCode) {
        return switch (exitCode) {
            case 130 -> "SIGINT";
            case 134 -> "SIGABRT";
            case 137 -> "SIGKILL";
            case 139 -> "SIGSEGV";
            case 143 -> "SIGTERM";
            default -> "";
        };
    }

    private String actualComputePlacement() {
        if (this.actualGpuLayers < 0) {
            return "pending";
        }
        if (this.actualGpuLayers == 0) {
            return "cpu";
        }
        if (this.actualModelLayers > 0 && this.actualGpuLayers >= this.actualModelLayers) {
            return "gpu";
        }
        return "hybrid";
    }

    private String actualGpuLayerDetail() {
        if (this.actualGpuLayers < 0) {
            return "pending";
        }
        if (this.actualModelLayers > 0) {
            return this.actualGpuLayers + "/" + this.actualModelLayers;
        }
        return Integer.toString(this.actualGpuLayers);
    }


    @Override
    public void applyKoilExecutionPlan(KoilExecutionPlan plan) {
        this.koilExecutionPlan = plan;
    }

    @Override
    public void applyKoilOptionalResourceAdmissions(
            Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> launchAdmissions,
            Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> liveAdmissions
    ) {
        this.koilLaunchOptionalAdmissions = launchAdmissions == null ? Map.of() : Map.copyOf(launchAdmissions);
        this.koilLiveOptionalAdmissions = liveAdmissions == null ? Map.of() : Map.copyOf(liveAdmissions);
        if (!optionalCacheGrowthAllowed() && "preparing".equals(this.warmupState)) {
            this.warmupRevokedByMemory = true;
            Call warmup = this.startupWarmupCall;
            if (warmup != null) warmup.cancel();
            this.warmupState = "skipped_memory_pressure";
            publishWarmupHealthDetail();
            LocalModelRuntimeLog.write(
                    "llama_startup_warmup_revoked",
                    "cache_growth_ceiling_bytes=" + optionalCacheGrowthCeilingBytes()
            );
        }
        boolean evictOptionalState = LlamaCppOptionalStateRetention.shouldEvict(this.koilLiveOptionalAdmissions)
                || (this.residentPressureDecision != null && !this.residentPressureDecision.backgroundModelWorkAllowed());
        if (evictOptionalState && !this.optionalStateEvictionRequested) {
            this.optionalStateEvictionRequested = true;
            scheduleOptionalStateEviction(LlamaCppOptionalStateRetention.reason(this.koilLiveOptionalAdmissions));
        } else if (!evictOptionalState) {
            this.optionalStateEvictionRequested = false;
            if (!this.koilLiveOptionalAdmissions.isEmpty()) {
                this.optionalStateRetentionState = "admitted";
                this.optionalStateRetentionDetail = "live cache-growth policy permits optional seed retention";
            }
        }
        publishRuntimeObservations();
    }

    private void scheduleOptionalStateEviction(String reason) {
        if (this.closed || this.stopping || this.selectedPort <= 0) {
            this.optionalStateEvictionRequested = false;
            return;
        }
        if (!this.optionalStateEvictionScheduled.compareAndSet(false, true)) return;
        this.optionalStateRetentionState = "eviction_pending";
        this.optionalStateRetentionDetail = reason == null ? "memory pressure" : reason;
        try {
            this.requests.execute(() -> {
                try {
                    if (this.closed || this.stopping || this.selectedPort <= 0) return;
                    LlamaCppKvCacheManager.OptionalSeedEvictionResult first =
                            this.cacheManager.evictOptionalSeedSlots(this.selectedPort, this.optionalStateRetentionDetail);
                    LlamaCppKvCacheManager.OptionalSeedEvictionResult result = first;
                    if (first.busy() > 0 && !this.closed && !this.stopping) {
                        try {
                            Thread.sleep(180L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        if (!Thread.currentThread().isInterrupted()) {
                            LlamaCppKvCacheManager.OptionalSeedEvictionResult retry =
                                    this.cacheManager.evictOptionalSeedSlots(this.selectedPort, "bounded_retry_after_warmup_cancel");
                            result = new LlamaCppKvCacheManager.OptionalSeedEvictionResult(
                                    first.candidates() + retry.candidates(),
                                    first.evicted() + retry.evicted(),
                                    retry.busy(),
                                    first.failed() + retry.failed());
                        }
                    }
                    this.optionalStateRetentionState = result.failed() > 0
                            ? "eviction_partial"
                            : (result.evicted() > 0 ? "evicted_optional_seed"
                            : (result.busy() > 0 ? "eviction_deferred_busy" : "nothing_optional_to_evict"));
                    this.optionalStateRetentionDetail = "candidates=" + result.candidates()
                            + ",evicted=" + result.evicted()
                            + ",busy=" + result.busy()
                            + ",failed=" + result.failed();
                    publishRuntimeObservations();
                } finally {
                    this.optionalStateEvictionScheduled.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            this.optionalStateEvictionScheduled.set(false);
            this.optionalStateEvictionRequested = false;
            this.optionalStateRetentionState = "eviction_unavailable";
            this.optionalStateRetentionDetail = message(rejected);
        }
    }

    private List<String> command(int port) {
        List<String> command = new ArrayList<>();
        command.add(this.configuration.executable().toAbsolutePath().normalize().toString());
        command.add("--model");
        command.add(this.configuration.modelFile().toAbsolutePath().normalize().toString());
        command.add("--host");
        command.add("127.0.0.1");
        command.add("--port");
        command.add(Integer.toString(port));
        command.add("--alias");
        command.add(this.configuration.modelId());
        command.add("--ctx-size");
        long totalContextTokens = (long)effectiveContextTokens() * Math.max(1, this.configuration.kvSlots());
        command.add(Long.toString(Math.min(Integer.MAX_VALUE, totalContextTokens)));
        command.add("--parallel");
        command.add(Integer.toString(this.configuration.kvSlots()));
        LlamaCppComputeSettings effectiveCompute = effectiveComputeSettings();
        LlamaCppMaxRuntimeProfile maxProfile = null;
        this.activeMaxProfileTuned = false;
        this.activeMaxProfileSource = "none";
        boolean activeMaxBenchmarkCandidate = false;
        if (effectiveCompute.mode() == LlamaCppComputeMode.MAX) {
            // Autotuning candidates MUST outrank the reusable universal plan. Otherwise every
            // benchmark can silently execute the same previously-selected geometry and poison
            // the tuning database with mislabeled measurements.
            java.util.Optional<LlamaCppMaxRuntimeProfile> candidate = LlamaCppMaxTuningStore.activeCandidate();
            if (candidate.isPresent()) {
                maxProfile = candidate.get();
                this.activeMaxProfileSource = "benchmark_candidate";
                activeMaxBenchmarkCandidate = true;
            } else {
                // Outside an active benchmark, Koil's universal execution plan is authoritative
                // when it contains measured geometry. The llama.cpp store remains the fallback
                // measurement source, not the outer policy owner.
                if (universalPlanPrecisionMatchesLaunch()) {
                    maxProfile = LlamaCppUniversalTuningBridge.toRuntimeProfile(this.koilExecutionPlan);
                } else {
                    LocalModelRuntimeLog.write(
                            "llama_universal_tuning_precision_rejected",
                            "universal measured geometry does not match actual launch state precision"
                                    + " | expected=" + universalPlanStatePrecisionRegime()
                                    + " | actual=" + activeStatePrecisionRegime());
                }
                if (maxProfile != null) {
                    String tuningSource = this.koilExecutionPlan == null
                            ? ""
                            : this.koilExecutionPlan.decisions().getOrDefault("tuningSource", "");
                    this.activeMaxProfileSource = "llama_cpp_max_compat".equalsIgnoreCase(tuningSource)
                            ? "native_compat"
                            : "universal_plan";
                    this.activeMaxProfileTuned = true;
                } else if (LlamaCppStatePrecisionCompatibility.nativeStoreEligible(this.activeStatePrecision)) {
                    java.util.Optional<LlamaCppMaxTuningStore.TunedProfile> tuned = LlamaCppMaxTuningStore.find(
                            this.configuration, effectiveCompute.device(), this.resolvedComputeDeviceDetail,
                            KoilTuningKey.contextRegime(plannedStartupContextTokens()), activeStatePrecisionRegime());
                    if (tuned.isPresent()) {
                        maxProfile = tuned.get().profile();
                        this.activeMaxProfileSource = "native_store";
                        this.activeMaxProfileTuned = true;
                    }
                } else {
                    LocalModelRuntimeLog.write(
                            "llama_native_tuning_precision_skipped",
                            "native MAX store has no supported exact precision identity for "
                                    + activeStatePrecisionRegime());
                }
            }
            if (maxProfile == null) {
                maxProfile = LlamaCppMaxRuntimeProfile.forcedFallback();
                this.activeMaxProfileSource = "fit_fallback";
            }
            this.activeMaxProfile = maxProfile;
            if (maxProfile.placement() == LlamaCppMaxRuntimeProfile.Placement.CPU) {
                this.actualGpuLayers = 0;
            }
        }
        applyComputeSafetyPreflight(effectiveCompute, maxProfile);
        command.addAll(LlamaCppComputeArguments.forSettings(
                effectiveCompute, maxProfile,
                activeMaxBenchmarkCandidate || this.koilExecutionPlan == null
                        ? null : this.koilExecutionPlan.settings()));
        command.addAll(LlamaCppStatePrecisionArguments.forDecision(this.activeStatePrecision));
        if (effectiveCompute.mode() != LlamaCppComputeMode.CPU) {
            // Make model-placement telemetry deterministic even when the host
            // environment overrides llama.cpp's default logging verbosity.
            command.add("--log-verbosity");
            command.add("4");
        }
        command.add("--jinja");
        command.add("--cache-prompt");
        if (this.configuration.cacheReuseTokens() > 0) {
            command.add("--cache-reuse");
            command.add(Integer.toString(this.configuration.cacheReuseTokens()));
        }
        command.add("--slots");
        command.add("--slot-save-path");
        command.add(this.configuration.slotSavePath().toAbsolutePath().normalize().toString());
        // Avoid the shared RAM/idle-slot cache path: Koil keeps each active
        // conversation on an explicitly owned live slot and lets cache_prompt
        // reuse the true token prefix there. Disk snapshots are persistence-only
        // and never sit on the request-critical prefill path.
        command.add("--cache-ram");
        command.add("0");
        command.add("--no-cache-idle-slots");
        command.add("--no-kv-unified");
        return command;
    }

    private int exactHybridGpuLayers() {
        LlamaCppComputeSettings effective = effectiveComputeSettings();
        if (effective.mode() == LlamaCppComputeMode.HYBRID) {
            return effective.hybridGpuLayers();
        }
        if (effective.mode() == LlamaCppComputeMode.MAX
                && this.activeMaxProfile != null
                && this.activeMaxProfile.placement() == LlamaCppMaxRuntimeProfile.Placement.HYBRID) {
            return this.activeMaxProfile.gpuLayers();
        }
        return -1;
    }

    private boolean usesConservativeHybridGeometry(int gpuLayers) {
        LlamaCppComputeSettings effective = effectiveComputeSettings();
        if (effective.mode() == LlamaCppComputeMode.HYBRID) return true;
        if (effective.mode() != LlamaCppComputeMode.MAX
                || this.activeMaxProfile == null
                || this.activeMaxProfile.placement() != LlamaCppMaxRuntimeProfile.Placement.HYBRID) {
            return false;
        }
        int[] conservative = LlamaCppComputeStabilityStore.conservativeHybridBatchGeometry(gpuLayers);
        return this.activeMaxProfile.batchSize() <= conservative[0]
                && this.activeMaxProfile.ubatchSize() <= conservative[1];
    }

    private void applyComputeSafetyPreflight(
            LlamaCppComputeSettings effectiveCompute,
            LlamaCppMaxRuntimeProfile maxProfile
    ) {
        if (effectiveCompute == null || effectiveCompute.mode() == LlamaCppComputeMode.CPU) {
            this.computeSafetyState = "not_required";
            this.computeSafetyDetail = "strict CPU path";
            return;
        }

        int exactHybridLayers = -1;
        if (effectiveCompute.mode() == LlamaCppComputeMode.HYBRID) {
            exactHybridLayers = effectiveCompute.hybridGpuLayers();
        } else if (effectiveCompute.mode() == LlamaCppComputeMode.MAX
                && maxProfile != null
                && maxProfile.placement() == LlamaCppMaxRuntimeProfile.Placement.HYBRID) {
            exactHybridLayers = maxProfile.gpuLayers();
        }

        if (exactHybridLayers <= 0) {
            this.computeSafetyState = "fit_managed";
            this.computeSafetyDetail = "llama.cpp fit engine controls accelerator placement with reserved headroom";
            return;
        }

        LlamaCppComputeSettings effective = effectiveComputeSettings();
        LlamaCppComputeSafetyPolicy.Assessment assessment = LlamaCppComputeSafetyPolicy.assess(
                this.configuration.modelFile(), exactHybridLayers, effective.device());
        this.computeSafetyStartAvailableBytes = assessment.availableMemoryBytes();
        this.computeSafetyDetail = assessment.summary();
        if (!assessment.allowed()) {
            this.computeSafetyState = "rejected";
            throw new IllegalStateException(
                    "Unsafe hybrid GPU layer split rejected before llama.cpp startup: "
                            + assessment.summary());
        }
        this.computeSafetyState = "passed";
    }

    private Request.Builder authenticated(Request.Builder builder) {
        return builder.header("Authorization", "Bearer " + this.configuration.apiKey());
    }

    private String baseUrl(int port) {
        return "http://" + this.configuration.host() + ":" + port;
    }

    private Map<String, String> diagnostics() {
        return Map.ofEntries(
                Map.entry("provider", id()),
                Map.entry("model", this.configuration.modelId()),
                Map.entry("modelFamily", this.protocolProfile.family()),
                Map.entry("modelArchitecture", this.protocolProfile.architectureId()),
                Map.entry("computeTopology", this.protocolProfile.computeTopology().name().toLowerCase(java.util.Locale.ROOT)),
                Map.entry("expertCount", Integer.toString(this.protocolProfile.expertCount())),
                Map.entry("activeExpertCount", Integer.toString(this.protocolProfile.activeExpertCount())),
                Map.entry("tokenizerFamily", this.protocolProfile.tokenizerFamily()),
                Map.entry("chatTemplateAdapter", this.protocolProfile.chatTemplateAdapter()),
                Map.entry("reasoningAdapter", this.protocolProfile.reasoningAdapter()),
                Map.entry("toolAdapter", this.protocolProfile.toolAdapter()),
                Map.entry("systemPolicy", this.protocolProfile.systemPolicy().name().toLowerCase(java.util.Locale.ROOT)),
                Map.entry("rolePolicy", this.protocolProfile.rolePolicy().name().toLowerCase(java.util.Locale.ROOT)),
                Map.entry("stopPolicy", this.protocolProfile.stopPolicy().name().toLowerCase(java.util.Locale.ROOT)),
                Map.entry("artifactInspected", Boolean.toString(this.protocolProfile.artifactInspected())),
                Map.entry("artifactContextTokens", Integer.toString(this.protocolProfile.artifactContextTokens())),
                Map.entry("runtimeCapabilitiesObserved", Boolean.toString(this.runtimeCapabilities.observed())),
                Map.entry("runtimeContextTokens", Integer.toString(this.runtimeCapabilities.contextTokens())),
                Map.entry("runtimeProtocolCapabilities", String.join(",", this.runtimeCapabilities.capabilities())),
                Map.entry("runtimeBuildInfo", this.runtimeCapabilities.buildInfo()),
                Map.entry("runtimeModelPath", this.runtimeCapabilities.modelPath()),
                Map.entry("compatibilityStatus", this.compatibilityReport.status().name().toLowerCase(java.util.Locale.ROOT)),
                Map.entry("compatibilitySummary", this.compatibilityReport.summary()),
                Map.entry("compatibilityNotes", String.join(" | ", this.compatibilityReport.notes())),
                Map.entry("host", this.configuration.host()),
                Map.entry("port", Integer.toString(this.selectedPort)),
                Map.entry("ownedProcess", Boolean.toString(this.ownsProcess)),
                Map.entry("kvSlots", Integer.toString(this.configuration.kvSlots())),
                Map.entry("configuredContextTokens", Integer.toString(configuredEffectiveContextTokens())),
                Map.entry("activeContextTokens", Integer.toString(effectiveContextTokens())),
                Map.entry("startupMemoryProfile", startupMemoryProfileReason()),
                Map.entry("startupAvailableMiB", Long.toString(this.startupMemoryProfile == null ? 0L : this.startupMemoryProfile.availableMiB())),
                Map.entry("startupModelMiB", Long.toString(this.startupMemoryProfile == null ? 0L : this.startupMemoryProfile.modelMiB())),
                Map.entry("learnedResidentContextCeilingTokens", Integer.toString(this.learnedResidentContextCeilingTokens)),
                Map.entry("learnedResidentContextReason", this.learnedResidentContextReason == null ? "" : this.learnedResidentContextReason),
                Map.entry("residentPressureAttribution", this.residentPressureAttribution == null ? "unknown" : this.residentPressureAttribution.classification()),
                Map.entry("residentPressureAttributionDetail", this.residentPressureAttribution == null ? "" : this.residentPressureAttribution.detail()),
                Map.entry("residentNativeRssBytes", this.residentPressureAttribution == null ? "0" : Long.toString(this.residentPressureAttribution.nativeProcess().rssBytes())),
                Map.entry("residentNativePssBytes", this.residentPressureAttribution == null ? "0" : Long.toString(this.residentPressureAttribution.nativeProcess().pssBytes())),
                Map.entry("residentJvmRssBytes", this.residentPressureAttribution == null ? "0" : Long.toString(this.residentPressureAttribution.jvmProcess().rssBytes())),
                Map.entry("residentJvmPssBytes", this.residentPressureAttribution == null ? "0" : Long.toString(this.residentPressureAttribution.jvmProcess().pssBytes())),
                Map.entry("residentPressureResponse", this.residentPressureDecision == null ? "normal" : this.residentPressureDecision.modeId()),
                Map.entry("residentPressureBackgroundWork", this.residentPressureDecision == null || this.residentPressureDecision.backgroundModelWorkAllowed() ? "allowed" : "blocked"),
                Map.entry("residentPressureModelDegradation", this.residentPressureDecision != null && !this.residentPressureDecision.modelDegradationAllowed() ? "frozen" : "eligible"),
                Map.entry("residentPressureUserInference", this.residentPressureDecision == null || this.residentPressureDecision.preserveTunedUserInference() ? "preserve_tuned" : "adaptive"),
                Map.entry("residentPressureRecoverySamples", this.residentPressureDecision == null ? "0" : Integer.toString(this.residentPressureDecision.recoverySamples())),
                Map.entry("residentPressureResponseReason", this.residentPressureDecision == null ? "" : this.residentPressureDecision.reason()),
                // Adapter-neutral launch-memory evidence. These values describe the last native
                // pre-allocation observation and are intentionally separate from Koil's reusable
                // launch-policy budget and from post-load live memory.
                Map.entry("launchMemoryAvailableBytes", Long.toString(this.startupMemoryProfile == null ? 0L
                        : this.startupMemoryProfile.availableMiB() * 1024L * 1024L)),
                Map.entry("launchMemoryModelBytes", Long.toString(this.startupMemoryProfile == null ? 0L
                        : this.startupMemoryProfile.modelMiB() * 1024L * 1024L)),
                Map.entry("launchMemoryProfile", startupMemoryProfileReason()),
                Map.entry("startupRecoveryAttempted", Boolean.toString(this.startupMemoryRecoveryAttempted)),
                Map.entry("terminationProvenance", this.lastTerminationProvenance),
                Map.entry("computeMode", this.configuration.computeSettings().mode().name().toLowerCase(java.util.Locale.ROOT)),
                Map.entry("computeDevice", this.configuration.computeSettings().deviceLabel()),
                Map.entry("hybridGpuLayers", Integer.toString(this.configuration.computeSettings().hybridGpuLayers())),
                Map.entry("resolvedComputeDevice", this.resolvedComputeDevice.isBlank() ? "automatic" : this.resolvedComputeDevice),
                Map.entry("maxProfile", this.activeMaxProfile == null ? "none" : this.activeMaxProfile.summary()),
                Map.entry("maxProfileSource", this.activeMaxProfileSource),
                Map.entry("maxExpectedPlacement", this.activeMaxProfile == null ? "none" : this.activeMaxProfile.expectedPlacement()),
                Map.entry("maxExpectedGpuLayers", this.activeMaxProfile == null ? "-1" : Integer.toString(this.activeMaxProfile.gpuLayers())),
                Map.entry("maxProfileTuned", Boolean.toString(this.activeMaxProfileTuned)),
                Map.entry("actualComputePlacement", actualComputePlacement()),
                Map.entry("actualGpuLayers", actualGpuLayerDetail()),
                Map.entry("computeIntegrity", this.computeIntegrityState),
                Map.entry("computeIntegrityDetail", this.computeIntegrityDetail),
                Map.entry("computeSafety", this.computeSafetyState),
                Map.entry("computeSafetyDetail", this.computeSafetyDetail),
                Map.entry("computeSafetyStartAvailableMiB", Long.toString(
                        Math.max(0L, this.computeSafetyStartAvailableBytes) / (1024L * 1024L))),
                Map.entry("computeStability", LlamaCppComputeStabilityStore.envelope(
                        this.configuration.modelFile(), effectiveComputeSettings().device()).summary()),
                Map.entry("acceleratorHeadroomMiB", Integer.toString(LlamaCppComputeArguments.acceleratorHeadroomMiB())),
                Map.entry("cacheProfile", this.configuration.cacheProfile().name().toLowerCase(java.util.Locale.ROOT)),
                Map.entry("statePrecisionBackend", LlamaCppBackendEvidence.resolve(
                        this.koilExecutionPlan == null ? KoilRuntimeBackend.UNKNOWN : this.koilExecutionPlan.primaryBackend(),
                        this.configuration.computeSettings(), this.resolvedComputeDevice, this.resolvedComputeDeviceDetail)
                        .name().toLowerCase(java.util.Locale.ROOT)),
                Map.entry("statePrecisionBackendSource", LlamaCppBackendEvidence.source(
                        this.koilExecutionPlan == null ? KoilRuntimeBackend.UNKNOWN : this.koilExecutionPlan.primaryBackend(),
                        this.configuration.computeSettings(), this.resolvedComputeDevice, this.resolvedComputeDeviceDetail)),
                Map.entry("statePrecisionK", this.activeStatePrecision == null ? "unknown" : this.activeStatePrecision.keyPrecision().name().toLowerCase(java.util.Locale.ROOT)),
                Map.entry("statePrecisionV", this.activeStatePrecision == null ? "unknown" : this.activeStatePrecision.valuePrecision().name().toLowerCase(java.util.Locale.ROOT)),
                Map.entry("statePrecisionAdaptive", Boolean.toString(this.activeStatePrecision != null && this.activeStatePrecision.adaptive())),
                Map.entry("statePrecisionRelativeBytes", this.activeStatePrecision == null ? "1.0" : Double.toString(this.activeStatePrecision.estimatedRelativeBytes())),
                Map.entry("statePrecisionEstimatedFullBytes", this.activeStatePrecision == null ? "0" : Long.toString(this.activeStatePrecision.estimatedFullStateBytes())),
                Map.entry("statePrecisionEstimatedChosenBytes", this.activeStatePrecision == null ? "0" : Long.toString(this.activeStatePrecision.estimatedChosenStateBytes())),
                Map.entry("statePrecisionEstimatedSavingsBytes", this.activeStatePrecision == null ? "0" : Long.toString(this.activeStatePrecision.estimatedSavingsBytes())),
                Map.entry("statePrecisionEstimateConfidence", this.activeStatePrecision == null ? "unknown" : this.activeStatePrecision.estimateConfidence().name().toLowerCase(java.util.Locale.ROOT)),
                Map.entry("statePrecisionSource", statePrecisionSourceForDiagnostics()),
                Map.entry("statePrecisionReason", statePrecisionReasonForDiagnostics()),
                Map.entry("cacheReuseTokens", Integer.toString(this.configuration.cacheReuseTokens())),
                Map.entry("optionalCacheGrowthAllowed", Boolean.toString(optionalCacheGrowthAllowed())),
                Map.entry("optionalCacheGrowthCeilingBytes", Long.toString(optionalCacheGrowthCeilingBytes())),
                Map.entry("optionalStateRetention", this.optionalStateRetentionState),
                Map.entry("optionalStateRetentionDetail", this.optionalStateRetentionDetail),
                Map.entry("slotSavePath", this.configuration.slotSavePath().toString()),
                Map.entry("warmupState", this.warmupState),
                Map.entry("warmupPrepared", this.warmupPrepared + "/" + this.warmupTotal),
                Map.entry("lastRuntimeOutput", runtimeOutputTail())
        );
    }

    private void publishRuntimeObservations() {
        synchronized (this.healthLock) {
            ModelHealthSnapshot current = this.health;
            Map<String, String> merged = new LinkedHashMap<>(current.diagnostics());
            merged.putAll(diagnostics());
            this.health = new ModelHealthSnapshot(
                    current.state(),
                    current.detail(),
                    current.queueDepth(),
                    Instant.now(),
                    Map.copyOf(merged)
            );
        }
    }

    private ModelHealthSnapshot failHealth(String detail) {
        LocalModelRuntimeLog.write("llama_failure", detail);
        return updateHealth(ModelHealthState.FAILED, detail, diagnostics());
    }

    private ModelHealthSnapshot updateHealth(ModelHealthState state, String detail, Map<String, String> diagnostics) {
        synchronized (this.healthLock) {
            this.health = new ModelHealthSnapshot(state, detail, 0, Instant.now(), diagnostics);
            return this.health;
        }
    }

    private static int selectLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static String string(JsonObject root, String key, String fallback) {
        try {
            return root != null && root.has(key) && !root.get(key).isJsonNull()
                    ? root.get(key).getAsString()
                    : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String message(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String value = cursor.getMessage();
        return value == null || value.isBlank() ? cursor.getClass().getSimpleName() : value;
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static final class CallCancellation implements ModelCancellationHandle {
        private volatile Call call;
        private volatile boolean cancelled;
        private volatile String reason = "cancelled";

        @Override
        public boolean cancel(String reason) {
            if (this.cancelled) {
                return false;
            }
            this.reason = reason == null || reason.isBlank() ? "cancelled" : reason;
            this.cancelled = true;
            Call active = this.call;
            if (active != null) {
                active.cancel();
            }
            return true;
        }

        @Override
        public boolean isCancellationRequested() {
            return this.cancelled;
        }

        @Override
        public String cancellationReason() {
            return this.reason;
        }
    }

    private enum CancelledHandle implements ModelCancellationHandle {
        INSTANCE;

        @Override
        public boolean cancel(String reason) {
            return false;
        }

        @Override
        public boolean isCancellationRequested() {
            return true;
        }

        @Override
        public String cancellationReason() {
            return "runtime not ready";
        }
    }
}
