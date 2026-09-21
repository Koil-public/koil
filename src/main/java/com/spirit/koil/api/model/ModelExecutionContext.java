package com.spirit.koil.api.model;

import com.spirit.koil.api.automation.capability.AutomationToolCoordinator;
import com.spirit.koil.api.model.retrieval.AutomationExecutionKnowledgeAdapter;
import com.spirit.koil.api.model.retrieval.EvidenceLearningKnowledgeAdapter;
import com.spirit.koil.api.model.reasoning.AgentState;
import com.spirit.koil.api.model.reasoning.AgentToolRoutingPolicy;
import com.spirit.koil.api.model.retrieval.KoilKnowledgeRuntime;
import com.spirit.koil.api.telemetry.TelemetryCapabilityState;
import com.spirit.koil.api.telemetry.TelemetrySpanKind;
import com.spirit.koil.api.telemetry.TelemetryStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session-scoped coordination point for execution intelligence. It owns no
 * independent tool authority: prediction, speculative reads, preparation,
 * recovery and experience learning all reconcile through the existing catalog,
 * AutomationToolCoordinator and KoilRetrievalEngine.
 */
final class ModelExecutionContext {
    private static final int EXPERIENCE_TOKEN_BUDGET = 320;
    private static final int MAX_CONCURRENT_SPECULATIONS = 3;

    private final UUID sessionId;
    private final String objective;
    private final AgentState agentState;
    private final AtomicLong observationEpoch = new AtomicLong(1L);
    private final List<String> recentTrajectory = new ArrayList<>();
    private final List<VerifiedToolEvidence> verifiedToolEvidence = new ArrayList<>();
    private final ToolDependencyGraph dependencyGraph = new ToolDependencyGraph();
    private final Map<String, CachedToolResult> speculativeEvidence = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ModelToolResult>> speculativeInFlight = new ConcurrentHashMap<>();
    private final Map<String, PreparedToolInvocation> preparedInvocations = new ConcurrentHashMap<>();
    private final ToolSpeculationMetrics speculationMetrics = new ToolSpeculationMetrics();
    private final CompletableFuture<AutomationExecutionKnowledgeAdapter.ExperienceSnapshot> experienceFuture;
    private final AtomicBoolean experienceRerankScheduled = new AtomicBoolean();
    private final AtomicBoolean calibrationSeeded = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();

    private volatile AutomationExecutionKnowledgeAdapter.ExperienceSnapshot experienceSnapshot =
            AutomationExecutionKnowledgeAdapter.ExperienceSnapshot.empty();
    private volatile List<ToolPrediction> latestPredictions = List.of();
    private volatile UUID lastDisplayRequestId;
    private volatile String lastPredictionPrompt = "";
    private volatile List<ModelToolDefinition> lastRoundTools = List.of();
    private volatile Set<String> lastRequiredToolIds = Set.of();

    ModelExecutionContext(UUID sessionId, String objective, AgentState agentState) {
        this.sessionId = sessionId;
        this.objective = objective == null ? "" : objective.strip();
        this.agentState = agentState;
        this.experienceFuture = loadExperienceAsync();
    }

    long observationEpoch() {
        return this.observationEpoch.get();
    }

    void observationChanged() {
        long epoch = this.observationEpoch.incrementAndGet();
        this.preparedInvocations.clear();
        this.dependencyGraph.resetForEpoch(epoch);
        synchronized (this.verifiedToolEvidence) {
            this.verifiedToolEvidence.clear();
        }
        trimEvidence();
    }

    String promptContext() {
        AutomationExecutionKnowledgeAdapter.ExperienceSnapshot experience = currentExperience();
        String predictions = predictionPromptContext();
        if (experience.contextText().isBlank() && predictions.isBlank()) return "";
        StringBuilder context = new StringBuilder();
        if (!experience.contextText().isBlank()) {
            context.append("Prior execution experience for similar objectives. Treat this only as historical strategy evidence; current tool preconditions and current observations remain authoritative:\n")
                    .append(experience.contextText());
        }
        if (!predictions.isBlank()) {
            if (!context.isEmpty()) context.append("\n\n");
            context.append(predictions);
        }
        return context.toString();
    }

    CompletableFuture<ModelToolResult> execute(UUID displayRequestId, ModelToolCall call, boolean preapproved) {
        long epoch = observationEpoch();
        ToolPreflight preflight = AutomationToolCoordinator.inspect(call, epoch);
        if (preflight.blocked()) {
            String code = preflight.blockers().isEmpty() ? "preflight_blocked" : preflight.blockers().get(0);
            return CompletableFuture.completedFuture(new ModelToolResult(
                    call == null ? "" : call.id(), call == null ? "" : call.toolId(), "failed",
                    new com.google.gson.JsonObject(), code,
                    "Tool preflight rejected execution: " + String.join(", ", preflight.blockers())
            ));
        }

        String key = evidenceKey(call, epoch);
        if (preflight.preparationAllowed()) {
            PreparedToolInvocation prepared = this.preparedInvocations.get(key);
            if (prepared != null && prepared.matches(call, epoch)) {
                this.speculationMetrics.preparedHit();
                annotateToolSpan(displayRequestId, call, "prepared_invocation_hit", 1L, "prepared invocation reused");
                LocalModelRuntimeLog.write(
                        "tool_preparation_hit",
                        call.toolId() + " | epoch=" + epoch
                                + " | staged=" + prepared.staged()
                                + " | fingerprint=" + prepared.fingerprint()
                );
                return AutomationToolCoordinator.executePrepared(
                        displayRequestId, call, prepared, epoch, preapproved
                );
            }
            java.util.Optional<PreparedToolInvocation> onDemand =
                    AutomationToolCoordinator.prepare(call, epoch);
            if (onDemand.isPresent()) {
                PreparedToolInvocation value = onDemand.get();
                this.preparedInvocations.put(key, value);
                this.speculationMetrics.prepared();
                annotateToolSpan(displayRequestId, call, "prepared_on_demand", 1L, "invocation prepared on demand");
                LocalModelRuntimeLog.write(
                        "tool_prepared_on_demand",
                        call.toolId() + " | epoch=" + epoch
                                + " | staged=" + value.staged()
                                + " | postconditions=" + value.postconditions().size()
                );
                return AutomationToolCoordinator.executePrepared(
                        displayRequestId, call, value, epoch, preapproved
                );
            }
        }

        if (preflight.speculativeReadAllowed()) {
            String speculativeKey = speculationKey(call, preflight.executionPolicy());
            CachedToolResult cached = this.speculativeEvidence.get(speculativeKey);
            long now = System.currentTimeMillis();
            if (cached != null && reusable(cached, call, preflight, now)) {
                this.speculationMetrics.cacheHit(call.toolId(), cached.result().durationMillis());
                annotateToolSpan(displayRequestId, call, "speculation_cache_hit", 1L, "speculative evidence reused");
                LocalModelRuntimeLog.write("tool_speculation_cache_hit", call.toolId() + " | epoch=" + epoch);
                return CompletableFuture.completedFuture(rebind(cached.result(), call));
            }
            if (cached != null) this.speculativeEvidence.remove(speculativeKey, cached);

            CompletableFuture<ModelToolResult> inFlight = this.speculativeInFlight.get(speculativeKey);
            if (inFlight != null) {
                this.speculationMetrics.joined(call.toolId());
                annotateToolSpan(displayRequestId, call, "speculation_join", 1L, "joined in-flight speculative work");
                LocalModelRuntimeLog.write("tool_speculation_join", call.toolId() + " | epoch=" + epoch);
                return inFlight.thenCompose(result -> {
                    if (observationEpoch() == epoch && result != null && result.completedAndValidated()) {
                        ToolPreflight current = AutomationToolCoordinator.inspect(call, epoch);
                        if (!current.blocked()) return CompletableFuture.completedFuture(rebind(result, call));
                    }
                    return AutomationToolCoordinator.execute(displayRequestId, call, preapproved);
                });
            }
        }

        return AutomationToolCoordinator.execute(displayRequestId, call, preapproved);
    }

    /**
     * Ranks the prompt-aware round catalog, explicit objective tools and
     * structured execution history. Only calls with authoritative argument
     * provenance and an explicit speculative-read policy may run ahead.
     */
    void speculateCandidates(
            UUID displayRequestId,
            String prompt,
            List<ModelToolDefinition> roundTools,
            Set<String> requiredToolIds
    ) {
        if (roundTools == null || roundTools.isEmpty()) return;
        this.lastDisplayRequestId = displayRequestId;
        this.lastPredictionPrompt = prompt == null ? "" : prompt;
        this.lastRoundTools = List.copyOf(roundTools);
        this.lastRequiredToolIds = requiredToolIds == null ? Set.of() : Set.copyOf(requiredToolIds);
        rankAndDispatch(displayRequestId, prompt, roundTools, requiredToolIds, currentExperience());

        // Retrieval never blocks the provider. If historical evidence arrives
        // shortly afterward, rerank once and let the in-flight map collapse any
        // duplicate speculative call.
        if (!this.experienceFuture.isDone() && this.experienceRerankScheduled.compareAndSet(false, true)) {
            this.experienceFuture.thenAccept(experience -> {
                this.experienceSnapshot = experience == null
                        ? AutomationExecutionKnowledgeAdapter.ExperienceSnapshot.empty()
                        : experience;
                rankAndDispatch(displayRequestId, prompt, roundTools, requiredToolIds, this.experienceSnapshot);
            });
        }
    }

    List<ToolPrediction> latestPredictions() {
        return this.latestPredictions;
    }

    private void rankAndDispatch(
            UUID displayRequestId,
            String prompt,
            List<ModelToolDefinition> roundTools,
            Set<String> requiredToolIds,
            AutomationExecutionKnowledgeAdapter.ExperienceSnapshot experience
    ) {
        ToolTrajectoryPlanner.Plan trajectoryPlan = ToolTrajectoryPlanner.plan(
                experience == null ? List.of() : experience.experiences(),
                currentTrajectoryTools()
        );
        if (trajectoryPlan.available()) {
            this.dependencyGraph.seedTrajectory(trajectoryPlan.remainingTools(), observationEpoch());
            LocalModelRuntimeLog.write("tool_trajectory_seeded",
                    "samples=" + trajectoryPlan.samples()
                            + " | confidence=" + String.format(java.util.Locale.ROOT, "%.2f", trajectoryPlan.confidence())
                            + " | remaining=" + String.join("->", trajectoryPlan.remainingTools()));
        }
        AgentToolRoutingPolicy.Focus stateFocus = AgentToolRoutingPolicy.focus(
                this.agentState, roundTools, requiredToolIds);
        List<ModelToolDefinition> stateRankedTools = AgentToolRoutingPolicy.prioritize(
                this.agentState, roundTools, requiredToolIds);
        String stateObjective = AgentToolRoutingPolicy.predictionObjective(this.agentState, prompt);
        List<ToolPrediction> predictions = ToolIntentPredictor.predict(
                stateObjective,
                stateRankedTools,
                requiredToolIds,
                Set.copyOf(stateFocus.toolIds()),
                this.agentState,
                experience == null ? List.of() : experience.experiences(),
                verifiedEvidenceSnapshot(),
                trajectoryPlan
        );
        this.latestPredictions = predictions;
        this.speculationMetrics.predicted(predictions.size());
        this.speculationMetrics.argumentResolved(
                predictions.stream().filter(ToolPrediction::argumentsSafeForSpeculation).count()
        );
        if (!predictions.isEmpty()) {
            LocalModelRuntimeLog.write("tool_prediction_ranked", predictions.stream()
                    .map(p -> p.toolId() + "=" + String.format(java.util.Locale.ROOT, "%.2f", p.confidence()))
                    .reduce((a, b) -> a + "," + b).orElse(""));
        }

        for (ToolPrediction prediction : predictions) {
            if (this.speculativeInFlight.size() >= MAX_CONCURRENT_SPECULATIONS) break;
            if (!prediction.argumentsSafeForSpeculation()) continue;
            String predictionId = "predicted-" + sessionId + "-" + prediction.toolId() + "-"
                    + Integer.toHexString(prediction.predictedArguments().toString().hashCode());
            this.dependencyGraph.predicted(
                    predictionId, prediction.toolId(), observationEpoch(), prediction.sourceCallIds()
            );
            if (!prediction.sourceCallIds().isEmpty()) {
                this.speculationMetrics.chainedArgument();
                LocalModelRuntimeLog.write(
                        "tool_dependency_chained",
                        prediction.toolId() + " | sources=" + String.join(",", prediction.sourceCallIds())
                                + " | provenance=" + prediction.argumentProvenance()
                );
            }
            if (!this.dependencyGraph.dependenciesSatisfied(predictionId)) continue;
            ModelToolCall predictedCall = new ModelToolCall(
                    predictionId,
                    prediction.toolId(),
                    prediction.predictedArguments()
            );
            ToolPreflight preflight = AutomationToolCoordinator.inspect(predictedCall, observationEpoch());
            if (prediction.speculativeEligible()
                    && !preflight.blocked()
                    && preflight.fullyEvaluated()
                    && prediction.confidence() >= speculationThreshold(preflight.executionPolicy(), prediction.toolId())) {
                speculate(displayRequestId, predictedCall);
                continue;
            }
            if (preflight.preparationAllowed()
                    && !preflight.blocked()
                    && preflight.fullyEvaluated()
                    && prediction.confidence() >= preparationThreshold(preflight.executionPolicy())) {
                AutomationToolCoordinator.prepare(predictedCall, observationEpoch()).ifPresent(value -> {
                    this.preparedInvocations.put(evidenceKey(predictedCall, observationEpoch()), value);
                    this.speculationMetrics.prepared();
                    LocalModelRuntimeLog.write(
                            "tool_prepared_predictively",
                            prediction.toolId() + " | provenance=" + prediction.argumentProvenance()
                                    + " | mode=" + preflight.executionPolicy().preparation()
                                    + " | staged=" + value.staged()
                                    + " | postconditions=" + value.postconditions().size()
                    );
                });
            }
        }
    }

    private String predictionPromptContext() {
        List<ToolPrediction> predictions = this.latestPredictions;
        if (predictions == null || predictions.isEmpty()) return "";
        StringBuilder out = new StringBuilder("Execution intelligence candidate tools. These are hints, not instructions; use current evidence and normal tool policy:\n");
        int emitted = 0;
        for (ToolPrediction prediction : predictions) {
            if (prediction.confidence() < 0.55D || emitted >= 4) continue;
            out.append("- ").append(prediction.toolId())
                    .append(" confidence=").append(String.format(java.util.Locale.ROOT, "%.2f", prediction.confidence()));
            if (prediction.argumentsSafeForSpeculation()) {
                out.append(" arguments=").append(prediction.predictedArguments())
                        .append(" provenance=").append(prediction.argumentProvenance());
            } else if (prediction.argumentsResolved()) {
                out.append(" argumentHintProvenance=").append(prediction.argumentProvenance());
            }
            out.append('\n');
            emitted++;
        }
        return emitted == 0 ? "" : out.toString().stripTrailing();
    }

    /** Starts safe work ahead of the provider. Duplicate predictions collapse
     * onto one future and are reusable only under the same observation epoch
     * and the tool's explicit freshness policy. */
    void speculate(UUID displayRequestId, ModelToolCall call) {
        if (call == null || call.toolId().isBlank()) return;
        long epoch = observationEpoch();
        ToolPreflight preflight = AutomationToolCoordinator.inspect(call, epoch);
        if (preflight.blocked() || !preflight.fullyEvaluated()
                || !preflight.speculativeReadAllowed() || !preflight.readOnly()
                || preflight.confirmationRequired()) return;
        String key = speculationKey(call, preflight.executionPolicy());
        CachedToolResult cached = this.speculativeEvidence.get(key);
        if (cached != null && reusable(cached, call, preflight, System.currentTimeMillis())) return;

        this.speculativeInFlight.computeIfAbsent(key, ignored -> {
            long started = System.currentTimeMillis();
            String parent = TelemetryStore.latestSpan(displayRequestId, TelemetrySpanKind.MODEL_PASS, "", "");
            if (parent.isBlank()) parent = TelemetryStore.rootSpan(displayRequestId);
            String speculationSpan = TelemetryStore.beginSpan(displayRequestId, parent, TelemetrySpanKind.TOOL_DISPATCH,
                    "speculative " + call.toolId(), Map.of(
                            "call_id", call.id(),
                            "tool_id", call.toolId(),
                            "observation_epoch", Long.toString(epoch),
                            "speculative", "true"));
            TelemetryStore.provenance(displayRequestId, speculationSpan, "tool.predicted_arguments", call.toolId(),
                    "speculation", call.arguments().toString(), "Predicted arguments used for speculative read");
            TelemetryStore.capability(displayRequestId, "model", "speculation." + call.toolId(),
                    TelemetryCapabilityState.ACTIVE, "speculating", "Read-only tool work is running ahead of the provider.");
            this.speculationMetrics.started(call.toolId());
            LocalModelRuntimeLog.write("tool_speculation_started", call.toolId() + " | epoch=" + epoch);
            CompletableFuture<ModelToolResult> future = AutomationToolCoordinator
                    .executeSpeculative(displayRequestId, call, epoch);
            future.whenComplete((result, failure) -> {
                this.speculativeInFlight.remove(key);
                long latency = Math.max(0L, System.currentTimeMillis() - started);
                TelemetryStore.metric(displayRequestId, speculationSpan, "speculation_duration_ms", latency);
                if (failure == null && result != null && result.completedAndValidated() && observationEpoch() == epoch) {
                    this.speculativeEvidence.put(key, new CachedToolResult(
                            call,
                            preflight.executionPolicy(),
                            ToolEnvironmentFingerprint.capture(call, preflight.executionPolicy()),
                            result,
                            System.currentTimeMillis()
                    ));
                    trimEvidence();
                    this.speculationMetrics.ready(call.toolId());
                    TelemetryStore.metric(displayRequestId, speculationSpan, "speculation_ready", 1L);
                    TelemetryStore.finishSpan(displayRequestId, speculationSpan, TelemetryCapabilityState.AVAILABLE,
                            "ready", "Speculative evidence is reusable for the current observation epoch.");
                    TelemetryStore.capability(displayRequestId, "model", "speculation." + call.toolId(),
                            TelemetryCapabilityState.AVAILABLE, "ready", "Speculative evidence is cached and reusable.");
                    LocalModelRuntimeLog.write("tool_speculation_ready", call.toolId() + " | epoch=" + epoch
                            + " | latency_ms=" + latency);
                } else {
                    this.speculationMetrics.discarded(call.toolId());
                    String reason = failure != null ? concise(failure)
                            : result == null ? "no_result"
                            : observationEpoch() != epoch ? "stale_observation_epoch"
                            : result.failureCode().isBlank() ? result.status() : result.failureCode();
                    TelemetryCapabilityState state = failure instanceof java.util.concurrent.CancellationException
                            ? TelemetryCapabilityState.CANCELLED
                            : "stale_observation_epoch".equals(reason) ? TelemetryCapabilityState.DEGRADED
                            : TelemetryCapabilityState.FAILED;
                    TelemetryStore.metric(displayRequestId, speculationSpan, "speculation_discarded", 1L);
                    TelemetryStore.finishSpan(displayRequestId, speculationSpan, state, reason,
                            "Speculative result was not reusable.");
                    TelemetryStore.capability(displayRequestId, "model", "speculation." + call.toolId(),
                            state, reason, "Speculative work did not produce reusable evidence.");
                    LocalModelRuntimeLog.write("tool_speculation_discarded", call.toolId() + " | epoch=" + epoch);
                }
            });
            return future;
        });
    }

    private void annotateToolSpan(UUID displayRequestId, ModelToolCall call, String metric, long value, String event) {
        if (displayRequestId == null || call == null) return;
        String span = TelemetryStore.latestSpan(displayRequestId, TelemetrySpanKind.TOOL_INVOCATION, "call_id", call.id());
        if (span.isBlank()) return;
        TelemetryStore.metric(displayRequestId, span, metric, value);
        TelemetryStore.event(displayRequestId, span, event, TelemetryCapabilityState.ACTIVE, Map.of("tool_id", call.toolId()));
    }

    void record(ModelToolCall call, ModelToolResult result, boolean objectiveCompleted) {
        if (call == null || result == null) return;
        synchronized (this.recentTrajectory) {
            String step = call.toolId() + "=" + result.status();
            if (!result.failureCode().isBlank()) step += "(failure=" + result.failureCode() + ")";
            this.recentTrajectory.add(step);
            if (this.recentTrajectory.size() > 12) this.recentTrajectory.remove(0);
        }
        this.dependencyGraph.observed(call.id(), call.toolId(), observationEpoch());
        VerifiedToolEvidence verified = VerifiedToolEvidence.from(call, result, observationEpoch());
        if (verified != null) {
            synchronized (this.verifiedToolEvidence) {
                this.verifiedToolEvidence.add(verified);
                if (this.verifiedToolEvidence.size() > 24) this.verifiedToolEvidence.remove(0);
            }
            rerankAfterVerifiedEvidence();
        }
        KoilKnowledgeRuntime.shared().ifPresent(engine -> {
            String trajectory = trajectorySummary();
            new AutomationExecutionKnowledgeAdapter(engine)
                    .record(this.objective, this.sessionId.toString(), observationEpoch(), call, result, objectiveCompleted, trajectory)
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) LocalModelRuntimeLog.write("execution_experience_record_failed", concise(failure));
                    });
            ToolPreflight learningPreflight = AutomationToolCoordinator.inspect(call, observationEpoch());
            String environment = learningPreflight == null || learningPreflight.executionPolicy() == null
                    ? "unknown"
                    : ToolEnvironmentFingerprint.capture(call, learningPreflight.executionPolicy());
            new EvidenceLearningKnowledgeAdapter(engine)
                    .recordExecution(this.objective, this.sessionId.toString(), environment, call, result, objectiveCompleted, trajectory)
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) LocalModelRuntimeLog.write("evidence_learning_record_failed", concise(failure));
                    });
        });
    }

    String recoveryHint(ModelToolCall call, ModelToolResult result) {
        String historical = ToolRecoveryAdvisor.hint(currentExperience().experiences(), call, result, trajectorySummary());
        AgentToolRoutingPolicy.Focus focus = AgentToolRoutingPolicy.focus(
                this.agentState, this.lastRoundTools, this.lastRequiredToolIds);
        if (focus.target().isBlank()) return historical;
        String state = "Authoritative recovery target: " + focus.target();
        if (!focus.toolIds().isEmpty()) state += ". Preferred available resolver(s): " + String.join(", ", focus.toolIds());
        return historical.isBlank() ? state : state + "\n" + historical;
    }

    String trajectorySummary() {
        synchronized (this.recentTrajectory) {
            String trajectory = String.join(" -> ", this.recentTrajectory);
            String dependencies = this.dependencyGraph.summary();
            return dependencies.isBlank() ? trajectory : trajectory + " | dependencies: " + dependencies;
        }
    }

    String speculationMetricsSummary() {
        return this.speculationMetrics.summary();
    }

    private List<VerifiedToolEvidence> verifiedEvidenceSnapshot() {
        synchronized (this.verifiedToolEvidence) {
            return List.copyOf(this.verifiedToolEvidence);
        }
    }

    private void rerankAfterVerifiedEvidence() {
        UUID display = this.lastDisplayRequestId;
        List<ModelToolDefinition> tools = this.lastRoundTools;
        if (display == null || tools == null || tools.isEmpty()) return;
        rankAndDispatch(display, this.lastPredictionPrompt, tools, this.lastRequiredToolIds, currentExperience());
    }

    private CompletableFuture<AutomationExecutionKnowledgeAdapter.ExperienceSnapshot> loadExperienceAsync() {
        return CompletableFuture.supplyAsync(KoilKnowledgeRuntime::shared)
                .thenCompose(optional -> optional
                        .map(engine -> new AutomationExecutionKnowledgeAdapter(engine)
                                .relevantExperience(this.objective, this.sessionId.toString(), EXPERIENCE_TOKEN_BUDGET))
                        .orElseGet(() -> CompletableFuture.completedFuture(
                                AutomationExecutionKnowledgeAdapter.ExperienceSnapshot.empty()
                        )))
                .exceptionally(failure -> {
                    LocalModelRuntimeLog.write("execution_experience_retrieval_failed", concise(failure));
                    return AutomationExecutionKnowledgeAdapter.ExperienceSnapshot.empty();
                })
                .thenApply(experience -> {
                    this.experienceSnapshot = experience == null
                            ? AutomationExecutionKnowledgeAdapter.ExperienceSnapshot.empty()
                            : experience;
                    seedCalibration(this.experienceSnapshot);
                    return this.experienceSnapshot;
                });
    }

    private AutomationExecutionKnowledgeAdapter.ExperienceSnapshot currentExperience() {
        AutomationExecutionKnowledgeAdapter.ExperienceSnapshot current = this.experienceSnapshot;
        if (this.experienceFuture.isDone()) {
            try {
                AutomationExecutionKnowledgeAdapter.ExperienceSnapshot completed = this.experienceFuture.getNow(current);
                if (completed != null) {
                    this.experienceSnapshot = completed;
                    seedCalibration(completed);
                    return completed;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return current;
    }

    private double speculationThreshold(ToolExecutionPolicy policy, String toolId) {
        double base = switch (policy.cost()) {
            case CHEAP -> 0.58D;
            case MODERATE -> 0.68D;
            case EXPENSIVE -> 0.88D;
        };
        if (policy.freshness() == ToolExecutionPolicy.FreshnessMode.REMOTE) base += 0.04D;
        return Math.min(0.97D, base + this.speculationMetrics.adaptivePenalty(toolId));
    }

    private static double preparationThreshold(ToolExecutionPolicy policy) {
        return switch (policy.cost()) {
            case CHEAP -> 0.55D;
            case MODERATE -> 0.62D;
            case EXPENSIVE -> 0.78D;
        };
    }

    private static long evidenceTtlMillis(ToolExecutionPolicy policy) {
        return switch (policy.freshness()) {
            case IMMUTABLE -> 60_000L;
            case REMOTE -> 5_000L;
            case WORKSPACE -> 750L;
            case CONNECTION -> 500L;
            case LIVE -> 125L;
            case SESSION -> 1_000L;
        };
    }

    private static boolean reusable(CachedToolResult cached, ModelToolCall call, ToolPreflight preflight, long now) {
        if (cached == null || preflight == null || call == null) return false;
        long ttl = evidenceTtlMillis(preflight.executionPolicy());
        if (now - cached.completedAtMillis() > ttl) return false;
        String current = ToolEnvironmentFingerprint.capture(call, preflight.executionPolicy());
        return current.equals(cached.environmentFingerprint());
    }

    private static String speculationKey(ModelToolCall call, ToolExecutionPolicy policy) {
        if (call == null) return "";
        return call.toolId() + "|" + call.arguments() + "|" + ToolEnvironmentFingerprint.capture(call, policy);
    }

    private static String evidenceKey(ModelToolCall call, long epoch) {
        if (call == null) return epoch + "|";
        return epoch + "|" + call.toolId() + "|" + call.arguments();
    }

    private void trimEvidence() {
        long now = System.currentTimeMillis();
        this.speculativeEvidence.entrySet().removeIf(entry -> {
            CachedToolResult cached = entry.getValue();
            if (cached == null) return true;
            long ttl = evidenceTtlMillis(cached.policy());
            return ttl <= 0L || now - cached.completedAtMillis() > ttl;
        });
        if (this.speculativeEvidence.size() > 64) {
            this.speculativeEvidence.entrySet().stream()
                    .sorted(java.util.Comparator.comparingLong(entry -> entry.getValue().completedAtMillis()))
                    .limit(this.speculativeEvidence.size() - 64L)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(this.speculativeEvidence::remove);
        }
    }

    private List<String> currentTrajectoryTools() {
        synchronized (this.recentTrajectory) {
            return this.recentTrajectory.stream()
                    .map(step -> {
                        int equals = step.indexOf('=');
                        return equals > 0 ? step.substring(0, equals).strip() : "";
                    })
                    .filter(value -> !value.isBlank())
                    .toList();
        }
    }

    private void seedCalibration(AutomationExecutionKnowledgeAdapter.ExperienceSnapshot snapshot) {
        if (snapshot == null || snapshot.calibrations().isEmpty() || !this.calibrationSeeded.compareAndSet(false, true)) return;
        this.speculationMetrics.seed(snapshot.calibrations());
        LocalModelRuntimeLog.write("tool_calibration_loaded", "samples=" + snapshot.calibrations().size());
    }

    void finish() {
        if (!this.finished.compareAndSet(false, true)) return;
        java.util.List<com.spirit.koil.api.model.retrieval.AutomationToolCalibration> samples =
                this.speculationMetrics.calibrationSamples();
        if (samples.isEmpty()) return;
        KoilKnowledgeRuntime.shared().ifPresent(engine ->
                new AutomationExecutionKnowledgeAdapter(engine)
                        .recordCalibration(this.objective, this.sessionId.toString(), samples)
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) LocalModelRuntimeLog.write("tool_calibration_persist_failed", concise(failure));
                            else LocalModelRuntimeLog.write("tool_calibration_persisted", "samples=" + samples.size());
                        }));
    }

    private static ModelToolResult rebind(ModelToolResult result, ModelToolCall call) {
        if (result == null || call == null || result.callId().equals(call.id())) return result;
        return new ModelToolResult(
                call.id(), call.toolId(), result.status(), result.output(), result.failureCode(), result.detail(),
                result.startedAtMillis(), result.completedAtMillis(), result.validationStatus(), result.changedTargets(),
                result.retryable(), result.cancelled(), result.approvalStatus()
        );
    }

    private record CachedToolResult(
            ModelToolCall call,
            ToolExecutionPolicy policy,
            String environmentFingerprint,
            ModelToolResult result,
            long completedAtMillis
    ) {}

    private static String concise(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
