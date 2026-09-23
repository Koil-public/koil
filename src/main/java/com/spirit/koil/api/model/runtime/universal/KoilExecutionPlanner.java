package com.spirit.koil.api.model.runtime.universal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Architecture-neutral execution planner. It deliberately distinguishes a backend compiled into
 * a runtime from a hardware accelerator actually observed on the machine.
 */
public final class KoilExecutionPlanner {
    private KoilExecutionPlanner() {}

    public static KoilExecutionPlan plan(
            KoilExecutionAdapterDescriptor adapter,
            KoilModelProfile model,
            KoilHardwareProfile hardware,
            KoilComputeMode requestedMode
    ) {
        return plan(adapter, model, hardware, requestedMode, null);
    }

    public static KoilExecutionPlan plan(
            KoilExecutionAdapterDescriptor adapter,
            KoilModelProfile model,
            KoilHardwareProfile hardware,
            KoilComputeMode requestedMode,
            KoilMeasuredTuningProfile measured
    ) {
        if (adapter == null) throw new IllegalArgumentException("adapter is required");
        KoilComputeMode requested = requestedMode == null ? KoilComputeMode.AUTOMATIC : requestedMode;
        Set<KoilRuntimeBackend> compiled = hardware == null || hardware.runtimeInventory() == null
                ? Set.of() : hardware.runtimeInventory().compiledBackends();
        boolean observedAccelerator = hardware != null && hardware.acceleratorObserved();
        KoilRuntimeBackend observedBackend = observedAccelerator
                ? hardware.accelerators().get(0).backend() : KoilRuntimeBackend.UNKNOWN;
        boolean compiledForObserved = observedAccelerator && (compiled.contains(observedBackend)
                || compatibleAlias(compiled, observedBackend));

        KoilComputeMode effective = requested;
        KoilRuntimeBackend backend = KoilRuntimeBackend.CPU;
        KoilExecutionSettings settings = settingsForRequestedMode(requested);
        String reason;
        switch (requested) {
            case CPU -> reason = "explicit CPU placement requested";
            case GPU -> {
                if (compiledForObserved) {
                    backend = observedBackend;
                    reason = "explicit GPU placement requested and matching accelerator/backend evidence is available";
                } else {
                    backend = KoilRuntimeBackend.UNKNOWN;
                    reason = "GPU placement requested; native adapter validation is required before a concrete accelerator backend is claimed";
                }
            }
            case HYBRID -> {
                if (compiledForObserved) {
                    backend = observedBackend;
                    reason = "hybrid placement requested and matching accelerator/backend evidence is available";
                } else {
                    backend = KoilRuntimeBackend.UNKNOWN;
                    reason = "hybrid placement requested; native adapter validation is required before a concrete accelerator backend is claimed";
                }
            }
            case CUSTOM -> {
                backend = compiledForObserved ? observedBackend : KoilRuntimeBackend.CPU;
                reason = "custom placement retained for adapter-specific decisions; only proven backend evidence is exposed";
            }
            case AUTOMATIC -> {
                if (compiledForObserved) {
                    backend = observedBackend;
                    reason = "automatic planning found matching observed accelerator and compiled backend evidence";
                } else {
                    backend = KoilRuntimeBackend.UNKNOWN;
                    reason = "automatic planning is unresolved until native hardware/backend evidence is observed";
                }
            }
            default -> reason = "conservative CPU placement";
        }

        Map<String, String> decisions = new LinkedHashMap<>();
        decisions.put("requestedMode", requested.name().toLowerCase());
        decisions.put("effectiveMode", effective.name().toLowerCase());
        decisions.put("primaryBackend", backend.name().toLowerCase());
        decisions.put("acceleratorObserved", Boolean.toString(observedAccelerator));
        decisions.put("compiledBackends", compiled.stream().sorted().map(v -> v.name().toLowerCase()).reduce((a,b)->a+","+b).orElse(""));
        if (model != null) {
            decisions.put("architecture", model.architectureId());
            decisions.put("architectureRecognized", Boolean.toString(model.architectureRecognized()));
            decisions.put("mixtureOfExperts", Boolean.toString(model.expertCount() > 0 || model.activeExpertCount() > 0));
            decisions.put("contextTokens", Integer.toString(model.contextTokens()));
            appendStateGeometry(decisions, model.metadata());
        }
        if (hardware != null) {
            decisions.put("availableMemoryBytes", Long.toString(hardware.availableMemoryBytes()));
            decisions.put("installedMemoryBytes", Long.toString(hardware.installedMemoryBytes()));
        }

        boolean measuredCompatible = measured != null && measured.compatibleWith(adapter, model, hardware);
        if (measuredCompatible && requested == KoilComputeMode.AUTOMATIC) {
            decisions.put("tuningSource", measured.source());
            decisions.put("tuningIdentity", measured.identity());
            decisions.put("tuningScore", Double.toString(measured.score()));
            measured.decisions().forEach((key, value) -> decisions.put("tuned." + key, value));
            measured.metrics().forEach((key, value) -> decisions.put("metric." + key, Double.toString(value)));
            if (measured.primaryBackend() != KoilRuntimeBackend.UNKNOWN) {
                if (measured.primaryBackend() == KoilRuntimeBackend.CPU
                        || compiled.contains(measured.primaryBackend())
                        || compatibleAlias(compiled, measured.primaryBackend())) {
                    backend = measured.primaryBackend();
                }
            } else {
                String tunedPlacement = measured.decisions().getOrDefault("placement", "");
                if (("gpu".equals(tunedPlacement) || "hybrid".equals(tunedPlacement)) && compiledForObserved) {
                    backend = observedBackend;
                }
            }
            settings = settingsFromMeasured(measured, backend);
            decisions.put("primaryBackend", backend.name().toLowerCase());
            reason = "automatic planning reused measured " + measured.source() + " calibration";
        } else if (measured != null) {
            decisions.put("tuningRejected", measuredCompatible
                    ? "explicit compute mode overrides automatic measured calibration"
                    : "measured calibration is incompatible with current adapter/model/hardware identity");
        }

        KoilMemoryPressureSnapshot memory = KoilMemoryBudgetPlanner.capture(hardware);
        settings = withMemoryBudget(settings, memory.inferenceBudgetBytes());
        decisions.put("memoryPhase", memory.phase().name().toLowerCase());
        decisions.put("memoryPressure", memory.pressure().name().toLowerCase());
        decisions.put("memoryAvailableBytes", Long.toString(memory.availableBytes()));
        decisions.put("memorySafetyFloorBytes", Long.toString(memory.safetyFloorBytes()));
        decisions.put("memoryReservedHostBytes", Long.toString(memory.reservedHostBytes()));
        decisions.put("memoryBudgetBytes", Long.toString(memory.inferenceBudgetBytes()));
        decisions.put("memoryAvailableFraction", Double.toString(memory.availableFraction()));
        KoilOptionalResourcePolicy.appendDecisions(decisions, "optionalLaunch", memory);

        addTypedSettings(decisions, settings);
        return new KoilExecutionPlan(adapter, effective, backend,
                hardware == null ? "" : hardware.fingerprint(), reason, settings, decisions);
    }

    /**
     * Reconciles a planned configuration with placement that the native runtime has actually
     * demonstrated. Runtime evidence wins over earlier speculative/translated backend guesses,
     * but does not alter the user's requested compute mode.
     */
    public static KoilExecutionPlan reconcileRuntimeEvidence(
            KoilExecutionPlan plan,
            KoilHardwareProfile hardware,
            Map<String, String> diagnostics
    ) {
        if (plan == null || diagnostics == null || diagnostics.isEmpty()) return plan;
        String placement = diagnostics.getOrDefault("actualComputePlacement", "").strip().toLowerCase();
        if (placement.isBlank() || "pending".equals(placement)) return plan;

        KoilRuntimeBackend runtimeBackend = plan.primaryBackend();
        if ("cpu".equals(placement)) {
            runtimeBackend = KoilRuntimeBackend.CPU;
        } else if (placement.contains("gpu") || placement.contains("hybrid") || placement.contains("accelerator")) {
            String device = diagnostics.getOrDefault("resolvedComputeDevice", "");
            KoilRuntimeBackend observed = KoilHardwareProfiler.backendFromEvidence(device);
            if (observed == KoilRuntimeBackend.UNKNOWN && hardware != null && hardware.acceleratorObserved()) {
                observed = hardware.accelerators().get(0).backend();
            }
            if (observed != KoilRuntimeBackend.UNKNOWN) runtimeBackend = observed;
        }

        KoilExecutionSettings settings = reconcileSettings(plan.settings(), placement, diagnostics);
        Map<String, String> decisions = new LinkedHashMap<>(plan.decisions());
        decisions.put("runtimePlacement", placement);
        putIfPresent(decisions, "runtimeDevice", diagnostics.get("resolvedComputeDevice"));
        putIfPresent(decisions, "runtimeGpuLayers", runtimeGpuLayerDetail(diagnostics));
        putIfPresent(decisions, "runtimeContextTokens", diagnostics.get("activeContextTokens"));
        decisions.put("primaryBackend", runtimeBackend.name().toLowerCase());
        addTypedSettings(decisions, settings);
        String reason = plan.reason();
        if (!reason.contains("runtime-confirmed")) {
            reason = reason + (reason.isBlank() ? "" : "; ") + "runtime-confirmed placement=" + placement;
        }
        return new KoilExecutionPlan(plan.adapter(), plan.computeMode(), runtimeBackend,
                plan.hardwareFingerprint(), reason, settings, decisions);
    }



    /**
     * Adds the execution adapter's authoritative pre-allocation launch-memory observation.
     * This is observation only: it must never rewrite the reusable launch-policy budget.
     */
    public static KoilExecutionPlan reconcileObservedLaunchMemoryEvidence(
            KoilExecutionPlan plan,
            KoilHardwareProfile hardware,
            Map<String, String> diagnostics
    ) {
        if (plan == null || diagnostics == null || diagnostics.isEmpty()) return plan;
        long available = parseLong(diagnostics.get("launchMemoryAvailableBytes"), 0L);
        if (available <= 0L) return plan;
        long installed = hardware == null ? 0L : hardware.installedMemoryBytes();
        KoilMemoryPressureSnapshot memory = KoilMemoryBudgetPlanner.from(
                installed, available, KoilMemoryPressureSnapshot.Phase.LAUNCH);
        Map<String, String> decisions = new LinkedHashMap<>(plan.decisions());
        decisions.put("observedLaunchMemorySource", "execution_adapter");
        decisions.put("observedLaunchMemoryPressure", memory.pressure().name().toLowerCase());
        decisions.put("observedLaunchMemoryAvailableBytes", Long.toString(memory.availableBytes()));
        decisions.put("observedLaunchMemorySafetyFloorBytes", Long.toString(memory.safetyFloorBytes()));
        decisions.put("observedLaunchMemoryReservedHostBytes", Long.toString(memory.reservedHostBytes()));
        decisions.put("observedLaunchMemoryBudgetBytes", Long.toString(memory.inferenceBudgetBytes()));
        decisions.put("observedLaunchMemoryAvailableFraction", Double.toString(memory.availableFraction()));
        putIfPresent(decisions, "observedLaunchMemoryProfile", diagnostics.get("launchMemoryProfile"));
        putIfPresent(decisions, "observedLaunchMemoryModelBytes", diagnostics.get("launchMemoryModelBytes"));
        return new KoilExecutionPlan(plan.adapter(), plan.computeMode(), plan.primaryBackend(),
                plan.hardwareFingerprint(), plan.reason(), plan.settings(), decisions);
    }

    /**
     * Adds post-load memory observations without mutating the reusable launch budget. A resident
     * model may legitimately leave no additional discretionary budget while remaining healthy.
     */
    public static KoilExecutionPlan reconcileRuntimeMemoryEvidence(
            KoilExecutionPlan plan,
            KoilHardwareProfile hardware
    ) {
        KoilMemoryPressureSnapshot raw = KoilMemoryBudgetPlanner.captureRuntime(hardware);
        KoilMemoryPressureStabilizer stabilizer = new KoilMemoryPressureStabilizer();
        return reconcileRuntimeMemoryEvidence(plan, stabilizer.update(raw));
    }

    /** Adds stabilized post-load memory observations without mutating reusable launch policy. */
    public static KoilExecutionPlan reconcileRuntimeMemoryEvidence(
            KoilExecutionPlan plan,
            KoilMemoryPressureStabilizer.Result stabilized
    ) {
        if (plan == null) return null;
        KoilMemoryPressureSnapshot raw = stabilized == null ? null : stabilized.raw();
        KoilMemoryPressureSnapshot memory = stabilized == null ? null : stabilized.effective();
        if (memory == null) return plan;
        Map<String, String> decisions = new LinkedHashMap<>(plan.decisions());
        decisions.put("runtimeMemoryPhase", memory.phase().name().toLowerCase());
        decisions.put("runtimeMemoryPressure", memory.pressure().name().toLowerCase());
        decisions.put("runtimeMemoryRawPressure", raw == null ? "unknown" : raw.pressure().name().toLowerCase());
        decisions.put("runtimeMemoryTrend", stabilized.trend().name().toLowerCase());
        decisions.put("runtimeMemoryRecoverySamples", Integer.toString(stabilized.recoverySamples()));
        decisions.put("runtimeMemoryAvailableBytes", Long.toString(memory.availableBytes()));
        decisions.put("runtimeMemorySafetyFloorBytes", Long.toString(memory.safetyFloorBytes()));
        decisions.put("runtimeMemoryReservedHostBytes", Long.toString(memory.reservedHostBytes()));
        decisions.put("runtimeMemoryAdditionalBudgetBytes", Long.toString(memory.inferenceBudgetBytes()));
        decisions.put("runtimeMemoryAvailableFraction", Double.toString(memory.availableFraction()));
        KoilOptionalResourcePolicy.appendDecisions(decisions, "optionalLive", memory);
        return new KoilExecutionPlan(plan.adapter(), plan.computeMode(), plan.primaryBackend(),
                plan.hardwareFingerprint(), plan.reason(), plan.settings(), decisions);
    }

    private static KoilExecutionSettings settingsForRequestedMode(KoilComputeMode requested) {
        return switch (requested) {
            case CPU -> new KoilExecutionSettings(
                    KoilPlacementPolicy.CPU, "", 0, 0, 0, 0, 0, 0, 0,
                    KoilStatePlacement.HOST, KoilOperatorPlacement.HOST,
                    KoilFeatureMode.AUTO, true, 0L);
            case GPU -> new KoilExecutionSettings(
                    KoilPlacementPolicy.GPU, "", -1, 0, 0, 0, 0, 0, 0,
                    KoilStatePlacement.ACCELERATOR, KoilOperatorPlacement.ACCELERATOR,
                    KoilFeatureMode.AUTO, true, 0L);
            case HYBRID -> new KoilExecutionSettings(
                    KoilPlacementPolicy.HYBRID, "", -1, 0, 0, 0, 0, 0, 0,
                    KoilStatePlacement.HOST, KoilOperatorPlacement.HOST,
                    KoilFeatureMode.AUTO, true, 0L);
            default -> KoilExecutionSettings.automatic();
        };
    }

    private static KoilExecutionSettings settingsFromMeasured(
            KoilMeasuredTuningProfile measured,
            KoilRuntimeBackend backend
    ) {
        Map<String, String> values = measured.decisions();
        KoilPlacementPolicy placement = enumPlacement(values.get("placement"));
        int gpuLayers = parseInt(values.get("gpuLayers"), placement == KoilPlacementPolicy.CPU ? 0 : -1);
        int generationThreads = parseInt(values.get("generationThreads"), 0);
        int batchThreads = parseInt(values.get("batchThreads"), 0);
        int batchSize = parseInt(values.get("batchSize"), 0);
        int microBatchSize = parseInt(values.get("ubatchSize"), 0);
        int poll = parseInt(values.get("poll"), 0);
        int pollBatch = parseInt(values.get("pollBatch"), 0);
        String device = values.getOrDefault("device", "");

        KoilStatePlacement statePlacement = switch (placement) {
            case CPU, HYBRID -> KoilStatePlacement.HOST;
            case GPU -> KoilStatePlacement.ACCELERATOR;
            default -> KoilStatePlacement.AUTOMATIC;
        };
        KoilOperatorPlacement operatorPlacement = switch (placement) {
            case CPU, HYBRID -> KoilOperatorPlacement.HOST;
            case GPU -> KoilOperatorPlacement.ACCELERATOR;
            default -> KoilOperatorPlacement.AUTOMATIC;
        };
        if (backend == KoilRuntimeBackend.CPU) {
            placement = KoilPlacementPolicy.CPU;
            gpuLayers = 0;
            statePlacement = KoilStatePlacement.HOST;
            operatorPlacement = KoilOperatorPlacement.HOST;
        }
        return new KoilExecutionSettings(
                placement, device, gpuLayers,
                generationThreads, batchThreads, batchSize, microBatchSize,
                poll, pollBatch, statePlacement, operatorPlacement,
                KoilFeatureMode.AUTO, true, 0L);
    }

    private static KoilExecutionSettings reconcileSettings(
            KoilExecutionSettings current,
            String runtimePlacement,
            Map<String, String> diagnostics
    ) {
        KoilExecutionSettings safe = current == null ? KoilExecutionSettings.automatic() : current;
        KoilPlacementPolicy placement = enumPlacement(runtimePlacement);
        int gpuLayers = parseGpuLayerCount(runtimeGpuLayerDetail(diagnostics), safe.gpuLayers());
        String device = diagnostics.getOrDefault("resolvedComputeDevice", safe.device());
        if (placement == KoilPlacementPolicy.CPU) gpuLayers = 0;
        KoilStatePlacement statePlacement = safe.statePlacement();
        KoilOperatorPlacement operatorPlacement = safe.operatorPlacement();
        if (placement == KoilPlacementPolicy.CPU) {
            statePlacement = KoilStatePlacement.HOST;
            operatorPlacement = KoilOperatorPlacement.HOST;
        }
        return new KoilExecutionSettings(
                placement == KoilPlacementPolicy.AUTOMATIC ? safe.placement() : placement,
                device,
                gpuLayers,
                safe.generationThreads(), safe.batchThreads(), safe.batchSize(), safe.microBatchSize(),
                safe.pollPercent(), safe.batchPollMode(), statePlacement, operatorPlacement,
                safe.flashAttention(), safe.repack(), safe.memoryBudgetBytes());
    }


    private static KoilExecutionSettings withMemoryBudget(KoilExecutionSettings settings, long liveBudgetBytes) {
        KoilExecutionSettings safe = settings == null ? KoilExecutionSettings.automatic() : settings;
        long live = Math.max(0L, liveBudgetBytes);
        long requested = safe.memoryBudgetBytes();
        long effective = requested <= 0L ? live : (live <= 0L ? 0L : Math.min(requested, live));
        return new KoilExecutionSettings(
                safe.placement(), safe.device(), safe.gpuLayers(),
                safe.generationThreads(), safe.batchThreads(), safe.batchSize(), safe.microBatchSize(),
                safe.pollPercent(), safe.batchPollMode(), safe.statePlacement(), safe.operatorPlacement(),
                safe.flashAttention(), safe.repack(), effective);
    }

    private static void addTypedSettings(Map<String, String> decisions, KoilExecutionSettings settings) {
        decisions.put("plan.placement", settings.placement().name().toLowerCase());
        decisions.put("plan.device", settings.device());
        decisions.put("plan.gpuLayers", Integer.toString(settings.gpuLayers()));
        decisions.put("plan.generationThreads", Integer.toString(settings.generationThreads()));
        decisions.put("plan.batchThreads", Integer.toString(settings.batchThreads()));
        decisions.put("plan.batchSize", Integer.toString(settings.batchSize()));
        decisions.put("plan.microBatchSize", Integer.toString(settings.microBatchSize()));
        decisions.put("plan.pollPercent", Integer.toString(settings.pollPercent()));
        decisions.put("plan.batchPollMode", Integer.toString(settings.batchPollMode()));
        decisions.put("plan.statePlacement", settings.statePlacement().name().toLowerCase());
        decisions.put("plan.operatorPlacement", settings.operatorPlacement().name().toLowerCase());
        decisions.put("plan.flashAttention", settings.flashAttention().name().toLowerCase());
        decisions.put("plan.repack", Boolean.toString(settings.repack()));
        decisions.put("plan.memoryBudgetBytes", Long.toString(settings.memoryBudgetBytes()));
    }

    private static KoilPlacementPolicy enumPlacement(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase();
        return switch (normalized) {
            case "cpu" -> KoilPlacementPolicy.CPU;
            case "gpu" -> KoilPlacementPolicy.GPU;
            case "hybrid" -> KoilPlacementPolicy.HYBRID;
            default -> KoilPlacementPolicy.AUTOMATIC;
        };
    }

    private static String runtimeGpuLayerDetail(Map<String, String> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return "";
        // Provider health publishes the verified native value as actualGpuLayers.
        // Do not consume the generic gpuLayers telemetry key here: request/startup telemetry
        // may use that name for unrelated values and can outlive the native health snapshot.
        String actual = diagnostics.getOrDefault("actualGpuLayers", "").strip();
        if (!actual.isBlank()) return actual;
        String expected = diagnostics.getOrDefault("maxExpectedGpuLayers", "").strip();
        return expected;
    }

    private static int parseGpuLayerCount(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        String clean = value.strip();
        int slash = clean.indexOf('/');
        if (slash >= 0) clean = clean.substring(0, slash);
        return parseInt(clean, fallback);
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value == null ? "" : value.strip()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value == null ? "" : value.strip()); }
        catch (Exception ignored) { return fallback; }
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value.strip());
    }


    private static void appendStateGeometry(Map<String, String> decisions, Map<String, String> metadata) {
        if (decisions == null || metadata == null || metadata.isEmpty()) return;
        metadata.forEach((key, value) -> {
            if (key == null || value == null || value.isBlank()) return;
            String normalized = key.toLowerCase(java.util.Locale.ROOT);
            if (normalized.endsWith(".embedding_length")
                    || normalized.endsWith(".block_count")
                    || normalized.endsWith(".layer_count")
                    || normalized.endsWith(".attention.head_count")
                    || normalized.endsWith(".attention.head_count_kv")
                    || normalized.endsWith(".attention.key_length")
                    || normalized.endsWith(".attention.value_length")) {
                decisions.put("modelStateMeta." + key, value);
            }
        });
    }

    private static boolean compatibleAlias(Set<KoilRuntimeBackend> compiled, KoilRuntimeBackend observed) {
        if (observed == KoilRuntimeBackend.HIP || observed == KoilRuntimeBackend.ROCM) {
            return compiled.contains(KoilRuntimeBackend.HIP) || compiled.contains(KoilRuntimeBackend.ROCM);
        }
        return false;
    }
}
