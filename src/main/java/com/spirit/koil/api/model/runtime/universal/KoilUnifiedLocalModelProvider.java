package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.LocalModelPerformanceBenchmarkProvider;
import com.spirit.koil.api.model.LocalModelProvider;
import com.spirit.koil.api.model.ModelCancellationHandle;
import com.spirit.koil.api.model.ModelCapabilityDescriptor;
import com.spirit.koil.api.model.ModelHealthSnapshot;
import com.spirit.koil.api.model.ModelHealthState;
import com.spirit.koil.api.model.ModelPerformanceBenchmarkRequest;
import com.spirit.koil.api.model.ModelPerformanceBenchmarkResult;
import com.spirit.koil.api.model.StreamingModelObserver;
import com.spirit.koil.api.model.StreamingModelRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The single Koil-facing inference provider. Concrete engines are internal execution adapters.
 * This bridge deliberately preserves existing mature adapters while removing them from the outer
 * runtime/provider contract.
 */
public final class KoilUnifiedLocalModelProvider implements LocalModelProvider, LocalModelPerformanceBenchmarkProvider {
    public static final String ID = "koil_universal";
    private static final long LIVE_MEMORY_SAMPLE_PERIOD_SECONDS = 5L;
    private static final AtomicInteger MEMORY_THREAD_SEQUENCE = new AtomicInteger();
    private static final ScheduledExecutorService MEMORY_SAMPLER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Koil-Memory-Sampler-" + MEMORY_THREAD_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private final LocalModelProvider executionAdapter;
    private final KoilExecutionAdapterDescriptor adapterDescriptor;
    private final AtomicReference<KoilExecutionPlan> policyPlan;
    private final AtomicReference<KoilExecutionPlan> executionPlan;
    private final KoilModelProfile modelProfile;
    private volatile KoilHardwareProfile hardwareProfile;
    private final KoilComputeMode requestedComputeMode;
    private final KoilMeasuredTuningProfile measuredTuningProfile;
    private final AtomicReference<KoilModelSession> activeSession = new AtomicReference<>();
    private final KoilMemoryPressureStabilizer memoryPressureStabilizer = new KoilMemoryPressureStabilizer();
    private final Object planRefreshLock = new Object();
    private final AtomicReference<ScheduledFuture<?>> memorySamplerTask = new AtomicReference<>();
    private final AtomicInteger automaticMemorySampleCount = new AtomicInteger();
    private volatile long lastAutomaticMemorySampleNanos;

    public KoilUnifiedLocalModelProvider(LocalModelProvider executionAdapter) {
        this(executionAdapter, KoilExecutionAdapterDescriptor.fromLegacyProvider(executionAdapter), null, null, KoilComputeMode.AUTOMATIC, null);
    }

    public KoilUnifiedLocalModelProvider(
            LocalModelProvider executionAdapter,
            KoilExecutionAdapterDescriptor adapterDescriptor
    ) {
        this(executionAdapter, adapterDescriptor, null, null, KoilComputeMode.AUTOMATIC, null);
    }

    public KoilUnifiedLocalModelProvider(
            LocalModelProvider executionAdapter,
            KoilExecutionAdapterDescriptor adapterDescriptor,
            KoilModelProfile modelProfile,
            KoilHardwareProfile hardwareProfile,
            KoilComputeMode computeMode
    ) {
        this(executionAdapter, adapterDescriptor, modelProfile, hardwareProfile, computeMode, null);
    }

    public KoilUnifiedLocalModelProvider(
            LocalModelProvider executionAdapter,
            KoilExecutionAdapterDescriptor adapterDescriptor,
            KoilModelProfile modelProfile,
            KoilHardwareProfile hardwareProfile,
            KoilComputeMode computeMode,
            KoilMeasuredTuningProfile measuredTuningProfile
    ) {
        this.executionAdapter = Objects.requireNonNull(executionAdapter, "executionAdapter");
        this.adapterDescriptor = Objects.requireNonNull(adapterDescriptor, "adapterDescriptor");
        if (!this.adapterDescriptor.id().equals(this.executionAdapter.id())) {
            throw new IllegalArgumentException("adapter descriptor id does not match provider id");
        }
        this.modelProfile = modelProfile;
        this.hardwareProfile = hardwareProfile;
        this.requestedComputeMode = computeMode == null ? KoilComputeMode.AUTOMATIC : computeMode;
        this.measuredTuningProfile = measuredTuningProfile;
        KoilExecutionPlan initialPolicy = KoilExecutionPlanner.plan(
                this.adapterDescriptor, modelProfile, hardwareProfile, this.requestedComputeMode, this.measuredTuningProfile);
        this.policyPlan = new AtomicReference<>(initialPolicy);
        this.executionPlan = new AtomicReference<>(initialPolicy);
        applyPlanToAdapter(initialPolicy);
        applyOptionalAdmissionsToAdapter(initialPolicy);
    }

    @Override
    public String id() {
        return ID;
    }

    public KoilExecutionAdapterDescriptor executionAdapter() {
        return this.adapterDescriptor;
    }

    public KoilExecutionPlan executionPlan() {
        return this.executionPlan.get();
    }

    /** Policy that will be supplied to the execution adapter on its next launch. */
    public KoilExecutionPlan launchPolicy() {
        return this.policyPlan.get();
    }

    public KoilModelSession activeSession() {
        return this.activeSession.get();
    }

    @Override
    public ModelCapabilityDescriptor capabilities() {
        return this.executionAdapter.capabilities();
    }

    @Override
    public ModelHealthSnapshot health() {
        ModelHealthSnapshot snapshot = this.executionAdapter.health();
        refreshPlanFromRuntime(snapshot);
        if (snapshot == null || !isRunnableState(snapshot.state())) stopLiveMemorySampling();
        return decorate(snapshot);
    }

    @Override
    public CompletableFuture<ModelHealthSnapshot> start() {
        stopLiveMemorySampling();
        this.memoryPressureStabilizer.reset();
        this.automaticMemorySampleCount.set(0);
        this.lastAutomaticMemorySampleNanos = 0L;
        // Rebuild launch policy immediately before process start so the memory budget represents
        // pre-launch headroom rather than post-load residency from the previous process.
        KoilExecutionPlan launch = KoilExecutionPlanner.plan(
                this.adapterDescriptor, this.modelProfile, this.hardwareProfile,
                this.requestedComputeMode, this.measuredTuningProfile);
        this.policyPlan.set(launch);
        applyPlanToAdapter(launch);
        applyOptionalAdmissionsToAdapter(launch);
        return this.executionAdapter.start().thenApply(snapshot -> {
            refreshPlanFromRuntime(snapshot);
            if (snapshot != null && snapshot.state() == ModelHealthState.READY) {
                this.activeSession.compareAndSet(null, new KoilModelSession(this.modelProfile, this.executionPlan.get()));
                KoilModelSession session = this.activeSession.get();
                if (session != null) session.updateExecutionPlan(this.executionPlan.get());
                startLiveMemorySampling();
            } else {
                stopLiveMemorySampling();
            }
            return decorate(snapshot);
        });
    }

    @Override
    public ModelCancellationHandle generate(StreamingModelRequest request, StreamingModelObserver observer) {
        return this.executionAdapter.generate(request, observer);
    }

    private void refreshPlanFromRuntime(ModelHealthSnapshot snapshot) {
        if (snapshot == null) return;
        synchronized (this.planRefreshLock) {
            Map<String, String> diagnostics = snapshot.diagnostics();
            this.hardwareProfile = KoilHardwareProfiler.reconcileRuntimeObservations(this.hardwareProfile, diagnostics);

            // Preserve the pre-launch policy and its memory budget. Runtime health is observation,
            // not reusable launch policy. A fresh policy is rebuilt only immediately before start().
            KoilExecutionPlan policy = this.policyPlan.get();
            KoilExecutionPlan observed = KoilExecutionPlanner.reconcileRuntimeEvidence(
                    policy, this.hardwareProfile, diagnostics);
            observed = KoilExecutionPlanner.reconcileObservedLaunchMemoryEvidence(
                    observed, this.hardwareProfile, diagnostics);
            KoilMemoryPressureSnapshot rawRuntimeMemory = KoilMemoryBudgetPlanner.captureRuntime(this.hardwareProfile);
            KoilMemoryPressureStabilizer.Result stabilizedMemory = this.memoryPressureStabilizer.update(rawRuntimeMemory);
            observed = KoilExecutionPlanner.reconcileRuntimeMemoryEvidence(observed, stabilizedMemory);
            this.executionPlan.set(observed);
            applyOptionalAdmissionsToAdapter(observed);

            // Feed only reusable launch policy back to the adapter. Runtime placement and resident
            // memory pressure must never poison the next launch configuration.
            applyPlanToAdapter(policy);
            KoilModelSession session = this.activeSession.get();
            if (session != null) session.updateExecutionPlan(observed);
        }
    }

    private void startLiveMemorySampling() {
        if (this.memorySamplerTask.get() != null) return;
        ScheduledFuture<?> task = MEMORY_SAMPLER.scheduleWithFixedDelay(
                this::sampleLiveMemorySafely,
                LIVE_MEMORY_SAMPLE_PERIOD_SECONDS,
                LIVE_MEMORY_SAMPLE_PERIOD_SECONDS,
                TimeUnit.SECONDS);
        if (!this.memorySamplerTask.compareAndSet(null, task)) task.cancel(false);
    }

    private void stopLiveMemorySampling() {
        ScheduledFuture<?> task = this.memorySamplerTask.getAndSet(null);
        if (task != null) task.cancel(false);
    }

    private void sampleLiveMemorySafely() {
        try {
            ModelHealthSnapshot adapterHealth = this.executionAdapter.health();
            if (adapterHealth == null || !isRunnableState(adapterHealth.state())) {
                stopLiveMemorySampling();
                return;
            }
            synchronized (this.planRefreshLock) {
                KoilExecutionPlan current = this.executionPlan.get();
                if (current == null) return;
                KoilMemoryPressureSnapshot raw = KoilMemoryBudgetPlanner.captureRuntime(this.hardwareProfile);
                KoilMemoryPressureStabilizer.Result stabilized = this.memoryPressureStabilizer.update(raw);
                KoilExecutionPlan updated = KoilExecutionPlanner.reconcileRuntimeMemoryEvidence(current, stabilized);
                this.executionPlan.set(updated);
                if (this.executionAdapter instanceof KoilRuntimeMemoryObservationConsumer consumer) {
                    consumer.observeKoilRuntimeMemory(stabilized);
                }
                this.automaticMemorySampleCount.incrementAndGet();
                this.lastAutomaticMemorySampleNanos = System.nanoTime();
                applyOptionalAdmissionsToAdapter(updated);
                KoilModelSession session = this.activeSession.get();
                if (session != null) session.updateExecutionPlan(updated);
            }
        } catch (Throwable ignored) {
            // Runtime memory observation must never make inference unavailable. A later sample or
            // explicit health refresh can recover the observability state.
        }
    }

    private static boolean isRunnableState(ModelHealthState state) {
        return state == ModelHealthState.READY || state == ModelHealthState.DEGRADED;
    }

    private void applyPlanToAdapter(KoilExecutionPlan plan) {
        if (this.executionAdapter instanceof KoilExecutionPlanConsumer consumer) {
            consumer.applyKoilExecutionPlan(plan);
        }
    }

    private void applyOptionalAdmissionsToAdapter(KoilExecutionPlan plan) {
        if (!(this.executionAdapter instanceof KoilOptionalResourceConsumer consumer) || plan == null) return;
        consumer.applyKoilOptionalResourceAdmissions(
                KoilOptionalResourcePolicy.fromPlan(plan, "optionalLaunch"),
                KoilOptionalResourcePolicy.fromPlan(plan, "optionalLive")
        );
    }

    @Override
    public CompletableFuture<Void> stop() {
        stopLiveMemorySampling();
        return this.executionAdapter.stop().whenComplete((ignored, failure) -> {
            this.memoryPressureStabilizer.reset();
            this.automaticMemorySampleCount.set(0);
            this.lastAutomaticMemorySampleNanos = 0L;
            KoilModelSession session = this.activeSession.getAndSet(null);
            if (session != null) session.close();
        });
    }

    @Override
    public CompletableFuture<ModelPerformanceBenchmarkResult> benchmark(ModelPerformanceBenchmarkRequest request) {
        if (!(this.executionAdapter instanceof LocalModelPerformanceBenchmarkProvider benchmarkProvider)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "active execution adapter does not expose native benchmark timings"));
        }
        return benchmarkProvider.benchmark(request);
    }

    private ModelHealthSnapshot decorate(ModelHealthSnapshot source) {
        if (source == null) return ModelHealthSnapshot.stopped();
        Map<String, String> diagnostics = new LinkedHashMap<>(source.diagnostics());
        diagnostics.put("runtimeProvider", ID);
        diagnostics.put("executionAdapter", this.adapterDescriptor.id());
        diagnostics.put("toolProtocol", this.adapterDescriptor.toolProtocol());
        KoilExecutionPlan plan = this.executionPlan.get();
        diagnostics.put("executionPlanReason", plan.reason());
        diagnostics.put("tuningSource", plan.decisions().getOrDefault("tuningSource", "none"));
        diagnostics.put("tuningIdentity", plan.decisions().getOrDefault("tuningIdentity", ""));
        diagnostics.put("tuningProtocol", plan.decisions().getOrDefault("tuned.benchmarkProtocol", ""));
        diagnostics.put("tuningAdapter", plan.decisions().getOrDefault("tuned.benchmarkAdapter", ""));
        diagnostics.put("tuningContextRegime", plan.decisions().getOrDefault("tuned.contextRegime", ""));
        diagnostics.put("tuningActualPlacement", plan.decisions().getOrDefault("tuned.actualPlacement", ""));
        diagnostics.put("tuningActualGpuLayers", plan.decisions().getOrDefault("tuned.actualGpuLayers", ""));
        diagnostics.put("computeMode", plan.computeMode().name().toLowerCase());
        diagnostics.put("primaryBackend", plan.primaryBackend().name().toLowerCase());
        diagnostics.put("hardwareFingerprint", plan.hardwareFingerprint());
        KoilExecutionSettings settings = plan.settings();
        diagnostics.put("planPlacement", settings.placement().name().toLowerCase());
        diagnostics.put("planGpuLayers", Integer.toString(settings.gpuLayers()));
        diagnostics.put("planThreads", settings.generationThreads() + "/" + settings.batchThreads());
        diagnostics.put("planBatch", settings.batchSize() + "/" + settings.microBatchSize());
        diagnostics.put("planPoll", settings.pollPercent() + "/" + settings.batchPollMode());
        diagnostics.put("planStatePlacement", settings.statePlacement().name().toLowerCase());
        diagnostics.put("planOperatorPlacement", settings.operatorPlacement().name().toLowerCase());
        diagnostics.put("planMemoryBudgetBytes", Long.toString(settings.memoryBudgetBytes()));
        KoilExecutionPlan launchPlan = this.policyPlan.get();
        diagnostics.put("memoryPressure", launchPlan.decisions().getOrDefault("memoryPressure", "unknown"));
        diagnostics.put("memoryAvailableBytes", launchPlan.decisions().getOrDefault("memoryAvailableBytes", "0"));
        diagnostics.put("memorySafetyFloorBytes", launchPlan.decisions().getOrDefault("memorySafetyFloorBytes", "0"));
        diagnostics.put("memoryReservedHostBytes", launchPlan.decisions().getOrDefault("memoryReservedHostBytes", "0"));
        diagnostics.put("memoryAvailableFraction", launchPlan.decisions().getOrDefault("memoryAvailableFraction", "0"));
        diagnostics.put("observedLaunchMemorySource", plan.decisions().getOrDefault("observedLaunchMemorySource", ""));
        diagnostics.put("observedLaunchMemoryPressure", plan.decisions().getOrDefault("observedLaunchMemoryPressure", "unknown"));
        diagnostics.put("observedLaunchMemoryAvailableBytes", plan.decisions().getOrDefault("observedLaunchMemoryAvailableBytes", "0"));
        diagnostics.put("observedLaunchMemorySafetyFloorBytes", plan.decisions().getOrDefault("observedLaunchMemorySafetyFloorBytes", "0"));
        diagnostics.put("observedLaunchMemoryReservedHostBytes", plan.decisions().getOrDefault("observedLaunchMemoryReservedHostBytes", "0"));
        diagnostics.put("observedLaunchMemoryBudgetBytes", plan.decisions().getOrDefault("observedLaunchMemoryBudgetBytes", "0"));
        diagnostics.put("observedLaunchMemoryAvailableFraction", plan.decisions().getOrDefault("observedLaunchMemoryAvailableFraction", "0"));
        diagnostics.put("observedLaunchMemoryProfile", plan.decisions().getOrDefault("observedLaunchMemoryProfile", ""));
        diagnostics.put("observedLaunchMemoryModelBytes", plan.decisions().getOrDefault("observedLaunchMemoryModelBytes", "0"));
        diagnostics.put("runtimeMemoryPressure", plan.decisions().getOrDefault("runtimeMemoryPressure", "unknown"));
        diagnostics.put("runtimeMemoryRawPressure", plan.decisions().getOrDefault("runtimeMemoryRawPressure",
                plan.decisions().getOrDefault("runtimeMemoryPressure", "unknown")));
        diagnostics.put("runtimeMemoryTrend", plan.decisions().getOrDefault("runtimeMemoryTrend", "unknown"));
        diagnostics.put("runtimeMemoryRecoverySamples", plan.decisions().getOrDefault("runtimeMemoryRecoverySamples", "0"));
        diagnostics.put("runtimeMemoryAvailableBytes", plan.decisions().getOrDefault("runtimeMemoryAvailableBytes", "0"));
        diagnostics.put("runtimeMemorySafetyFloorBytes", plan.decisions().getOrDefault("runtimeMemorySafetyFloorBytes", "0"));
        diagnostics.put("runtimeMemoryReservedHostBytes", plan.decisions().getOrDefault("runtimeMemoryReservedHostBytes", "0"));
        diagnostics.put("runtimeMemoryAdditionalBudgetBytes", plan.decisions().getOrDefault("runtimeMemoryAdditionalBudgetBytes", "0"));
        diagnostics.put("runtimeMemoryAvailableFraction", plan.decisions().getOrDefault("runtimeMemoryAvailableFraction", "0"));
        for (KoilOptionalResourceKind kind : KoilOptionalResourceKind.values()) {
            String key = kind.name().toLowerCase(java.util.Locale.ROOT);
            diagnostics.put("optionalLaunch." + key + ".allowed", plan.decisions().getOrDefault("optionalLaunch." + key + ".allowed", "false"));
            diagnostics.put("optionalLaunch." + key + ".ceilingBytes", plan.decisions().getOrDefault("optionalLaunch." + key + ".ceilingBytes", "0"));
            diagnostics.put("optionalLive." + key + ".allowed", plan.decisions().getOrDefault("optionalLive." + key + ".allowed", "false"));
            diagnostics.put("optionalLive." + key + ".ceilingBytes", plan.decisions().getOrDefault("optionalLive." + key + ".ceilingBytes", "0"));
        }
        int automaticSamples = this.automaticMemorySampleCount.get();
        diagnostics.put("runtimeMemoryAutomaticSamples", Integer.toString(automaticSamples));
        long lastSample = this.lastAutomaticMemorySampleNanos;
        long sampleAgeMs = lastSample <= 0L ? -1L : Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastSample));
        diagnostics.put("runtimeMemoryAutomaticSampleAgeMs", Long.toString(sampleAgeMs));
        diagnostics.put("architecture", this.modelProfile == null ? "unknown" : this.modelProfile.architectureId());
        diagnostics.put("runtimeBackends", this.hardwareProfile == null || this.hardwareProfile.runtimeInventory() == null
                ? "" : this.hardwareProfile.runtimeInventory().compiledBackends().stream().sorted()
                .map(v -> v.name().toLowerCase()).reduce((a, b) -> a + "," + b).orElse(""));
        KoilModelSession session = this.activeSession.get();
        if (session != null) {
            diagnostics.put("modelSessionId", session.id().toString());
            diagnostics.put("modelStateBytes", Long.toString(session.stateStore().estimatedBytes()));
        }
        return new ModelHealthSnapshot(
                source.state(), source.detail(), source.queueDepth(), source.updatedAt(), diagnostics);
    }
}
