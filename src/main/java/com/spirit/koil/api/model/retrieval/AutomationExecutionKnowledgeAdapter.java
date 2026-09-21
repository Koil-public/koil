package com.spirit.koil.api.model.retrieval;

import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Persists compact, goal-conditioned execution evidence in Koil's shared
 * retrieval authority. This deliberately does not create a second memory
 * database: successful and failed tool experience is ordinary retrievable
 * knowledge with explicit historical trust.
 */
public final class AutomationExecutionKnowledgeAdapter {
    private final KoilRetrievalEngine engine;

    public AutomationExecutionKnowledgeAdapter(KoilRetrievalEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public CompletableFuture<Long> record(
            String objective,
            String sessionId,
            long observationEpoch,
            ModelToolCall call,
            ModelToolResult result,
            boolean objectiveCompleted,
            String trajectory
    ) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(result, "result");
        String goal = normalized(objective);
        if (goal.isEmpty() || call.toolId().isBlank()) return CompletableFuture.completedFuture(0L);

        boolean verified = result.completedAndValidated();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("kind", "tool_execution_experience");
        metadata.put("tool", call.toolId());
        metadata.put("status", result.status());
        metadata.put("validation", result.validationStatus());
        metadata.put("failureCode", result.failureCode());
        metadata.put("verified", Boolean.toString(verified));
        metadata.put("objectiveCompleted", Boolean.toString(objectiveCompleted));
        metadata.put("retryable", Boolean.toString(result.retryable()));
        metadata.put("observationEpoch", Long.toString(Math.max(0L, observationEpoch)));
        metadata.put("argumentShape", argumentShape(call));
        metadata.put("durationMillis", Long.toString(result.durationMillis()));
        if (trajectory != null && !trajectory.isBlank()) metadata.put("trajectory", trajectory);
        RecoveryMetadata recovery = recoveryMetadata(trajectory, verified && objectiveCompleted);
        if (!recovery.signature().isBlank()) metadata.put("recoverySignature", recovery.signature());
        if (!recovery.sequence().isBlank()) metadata.put("recoverySequence", recovery.sequence());
        String sourceId = "automation.execution:" + digest(executionKey(sessionId, observationEpoch, call, result));
        metadata.put("sourceId", sourceId);
        metadata.put("sourceKey", "tool_result");

        StringBuilder text = new StringBuilder(256)
                .append("Automation execution experience.\nObjective: ").append(goal)
                .append("\nTool: ").append(call.toolId())
                .append("\nArguments shape: ").append(argumentShape(call))
                .append("\nOutcome: ").append(result.status())
                .append("\nValidation: ").append(result.validationStatus())
                .append("\nVerified tool result: ").append(verified)
                .append("\nObjective complete after result: ").append(objectiveCompleted)
                .append("\nDuration ms: ").append(result.durationMillis());
        if (trajectory != null && !trajectory.isBlank()) text.append("\nSession trajectory: ").append(trajectory);
        if (!result.failureCode().isBlank()) text.append("\nFailure: ").append(result.failureCode());
        if (!result.changedTargets().isEmpty()) text.append("\nChanged targets: ").append(String.join(", ", result.changedTargets()));

        double confidence = verified ? 0.88D : result.retryable() ? 0.58D : 0.72D;
        double importance = objectiveCompleted && verified ? 0.82D : 0.62D;
        KnowledgeEntry entry = new KnowledgeEntry(
                1L,
                KnowledgeType.AUTOMATION,
                "automation-experience",
                text.toString(),
                "automation.execution",
                normalized(sessionId),
                System.currentTimeMillis(),
                importance,
                confidence,
                KnowledgeTrust.HISTORICAL_CONTEXT,
                Map.copyOf(metadata)
        );
        return this.engine.synchronizeSource(new KnowledgeSourceSnapshot(sourceId, digest(entry.text()), java.util.List.of(entry)))
                .thenCompose(ignored -> this.engine.sourceEntryId(sourceId, "tool_result"));
    }

    public CompletableFuture<RetrievalResult> relevant(String objective, String requestId, int tokenBudget) {
        String goal = normalized(objective);
        if (goal.isEmpty()) return CompletableFuture.completedFuture(new RetrievalResult(java.util.List.of(), java.util.List.of(), "", 0));
        KnowledgeFilter filter = new KnowledgeFilter(
                java.util.Set.of(KnowledgeType.AUTOMATION, KnowledgeType.AUTOMATION_FAILURE,
                        KnowledgeType.REPAIR_EPISODE, KnowledgeType.COUNTEREXAMPLE, KnowledgeType.WORKFLOW_RECIPE,
                        KnowledgeType.ENVIRONMENT_FACT, KnowledgeType.PERFORMANCE_OBSERVATION),
                java.util.Set.of(), java.util.Set.of(), java.util.Map.of(), 0L
        );
        return this.engine.retrieve(new KnowledgeQuery(
                goal, filter, 24, Math.max(96, tokenBudget), normalized(requestId), KnowledgeTrust.HISTORICAL_CONTEXT
        ));
    }

    public CompletableFuture<ExperienceSnapshot> relevantExperience(String objective, String requestId, int tokenBudget) {
        return relevant(objective, requestId, tokenBudget).thenApply(result -> {
            java.util.List<RetrievalCandidate> execution = result.selected().stream()
                    .filter(candidate -> "tool_execution_experience".equals(candidate.entry().metadata().get("kind")))
                    .toList();
            java.util.List<RetrievalCandidate> strategy = result.selected().stream()
                    .filter(candidate -> candidate.entry().type() == KnowledgeType.REPAIR_EPISODE
                            || candidate.entry().type() == KnowledgeType.COUNTEREXAMPLE
                            || candidate.entry().type() == KnowledgeType.WORKFLOW_RECIPE
                            || candidate.entry().type() == KnowledgeType.ENVIRONMENT_FACT)
                    .limit(5)
                    .toList();
            String executionHistory = execution.stream().limit(3)
                    .map(candidate -> candidate.entry().text())
                    .reduce((left, right) -> left + "\n\n" + right)
                    .orElse("");
            String learnedStrategies = strategySummary(strategy);
            String executionContext = executionHistory.isBlank() ? learnedStrategies
                    : learnedStrategies.isBlank() ? executionHistory : executionHistory + "\n\n" + learnedStrategies;
            return new ExperienceSnapshot(
                    executionContext,
                    execution.stream().map(AutomationExecutionKnowledgeAdapter::experience).toList(),
                    result.selected().stream()
                            .filter(candidate -> "tool_speculation_calibration".equals(candidate.entry().metadata().get("kind")))
                            .map(AutomationExecutionKnowledgeAdapter::calibration)
                            .toList(),
                    Math.min(result.contextTokens(), Math.max(0, executionContext.length() / 4))
            );
        });
    }

    private static String strategySummary(java.util.List<RetrievalCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return "";
        java.util.LinkedHashMap<String, StrategyAggregate> grouped = new java.util.LinkedHashMap<>();
        for (RetrievalCandidate candidate : candidates) {
            KnowledgeEntry entry = candidate.entry();
            String failure = entry.metadata().getOrDefault("failureSignature", "");
            String sequence = entry.metadata().getOrDefault("recoverySequence",
                    entry.metadata().getOrDefault("workflowSequence", ""));
            String outcome = entry.metadata().getOrDefault("outcome", "historical");
            String key = entry.type() + "\u0000" + failure + "\u0000" + sequence + "\u0000" + outcome;
            StrategyAggregate aggregate = grouped.computeIfAbsent(key, ignored ->
                    new StrategyAggregate(entry.type(), failure, sequence, outcome, entry.text()));
            aggregate.samples++;
            aggregate.score += candidate.fusedScore();
        }
        StringBuilder out = new StringBuilder();
        grouped.values().stream()
                .sorted(java.util.Comparator.comparingDouble((StrategyAggregate value) -> value.score + value.samples * 0.02D).reversed())
                .limit(5)
                .forEach(value -> {
                    if (!out.isEmpty()) out.append("\n");
                    out.append(switch (value.type) {
                        case REPAIR_EPISODE -> "Verified repair candidate";
                        case COUNTEREXAMPLE -> "Observed failed attempt";
                        case WORKFLOW_RECIPE -> "Successful workflow candidate";
                        case ENVIRONMENT_FACT -> "Environment observation";
                        default -> "Historical evidence";
                    });
                    if (!value.failure.isBlank()) out.append(" | failure=").append(value.failure);
                    if (!value.sequence.isBlank()) out.append(" | sequence=").append(value.sequence);
                    out.append(" | samples=").append(value.samples);
                    if (value.type == KnowledgeType.REPAIR_EPISODE)
                        out.append(" | use as one proven option, not the only solution");
                    if (value.type == KnowledgeType.COUNTEREXAMPLE)
                        out.append(" | negative evidence for the recorded context, not a universal prohibition");
                });
        return out.toString();
    }

    private static final class StrategyAggregate {
        private final KnowledgeType type;
        private final String failure;
        private final String sequence;
        private final String outcome;
        private final String example;
        private int samples;
        private double score;
        private StrategyAggregate(KnowledgeType type, String failure, String sequence, String outcome, String example) {
            this.type = type; this.failure = failure == null ? "" : failure; this.sequence = sequence == null ? "" : sequence;
            this.outcome = outcome == null ? "" : outcome; this.example = example == null ? "" : example;
        }
    }

    private static AutomationExecutionExperience experience(RetrievalCandidate candidate) {
        KnowledgeEntry entry = candidate.entry();
        Map<String, String> metadata = entry.metadata();
        return new AutomationExecutionExperience(
                entry.id(),
                metadata.get("tool"),
                metadata.get("status"),
                metadata.get("validation"),
                metadata.get("failureCode"),
                bool(metadata.get("verified")),
                bool(metadata.get("objectiveCompleted")),
                bool(metadata.get("retryable")),
                metadata.get("argumentShape"),
                metadata.get("trajectory"),
                metadata.get("recoverySignature"),
                metadata.get("recoverySequence"),
                number(metadata.get("durationMillis")),
                entry.timestampMillis(),
                candidate.fusedScore()
        );
    }

    private static boolean bool(String value) {
        return Boolean.parseBoolean(value == null ? "false" : value);
    }

    private static long number(String value) {
        try {
            return Math.max(0L, Long.parseLong(value == null ? "0" : value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public record ExperienceSnapshot(
            String contextText,
            java.util.List<AutomationExecutionExperience> experiences,
            java.util.List<AutomationToolCalibration> calibrations,
            int contextTokens
    ) {
        public ExperienceSnapshot {
            contextText = contextText == null ? "" : contextText;
            experiences = experiences == null ? java.util.List.of() : java.util.List.copyOf(experiences);
            calibrations = calibrations == null ? java.util.List.of() : java.util.List.copyOf(calibrations);
            contextTokens = Math.max(0, contextTokens);
        }

        public static ExperienceSnapshot empty() {
            return new ExperienceSnapshot("", java.util.List.of(), java.util.List.of(), 0);
        }
    }

    public CompletableFuture<Void> recordCalibration(
            String objective,
            String sessionId,
            java.util.List<AutomationToolCalibration> samples
    ) {
        if (samples == null || samples.isEmpty()) return CompletableFuture.completedFuture(null);
        String goal = normalized(objective);
        String sourceId = "automation.calibration:" + digest(normalized(sessionId) + '\u0000' + System.currentTimeMillis());
        java.util.List<KnowledgeEntry> entries = samples.stream()
                .filter(sample -> sample != null && !sample.toolId().isBlank() && sample.started() > 0L)
                .map(sample -> {
                    Map<String, String> metadata = new LinkedHashMap<>();
                    metadata.put("kind", "tool_speculation_calibration");
                    metadata.put("tool", sample.toolId());
                    metadata.put("started", Long.toString(sample.started()));
                    metadata.put("used", Long.toString(sample.used()));
                    metadata.put("discarded", Long.toString(sample.discarded()));
                    metadata.put("hiddenLatencyMillis", Long.toString(sample.hiddenLatencyMillis()));
                    metadata.put("sourceKey", "calibration:" + sample.toolId());
                    String text = "Tool speculation calibration. Objective: " + goal + "\nTool: " + sample.toolId()
                            + "\nStarted: " + sample.started() + "\nUsed: " + sample.used()
                            + "\nDiscarded: " + sample.discarded() + "\nHidden latency ms: " + sample.hiddenLatencyMillis();
                    return new KnowledgeEntry(1L, KnowledgeType.AUTOMATION, "automation-calibration", text,
                            "automation.calibration", normalized(sessionId), System.currentTimeMillis(),
                            0.46D, 0.90D, KnowledgeTrust.HISTORICAL_CONTEXT, Map.copyOf(metadata));
                }).toList();
        if (entries.isEmpty()) return CompletableFuture.completedFuture(null);
        return this.engine.synchronizeSource(new KnowledgeSourceSnapshot(sourceId, digest(entries.toString()), entries))
                .thenApply(ignored -> null);
    }

    private static AutomationToolCalibration calibration(RetrievalCandidate candidate) {
        Map<String, String> metadata = candidate.entry().metadata();
        return new AutomationToolCalibration(
                metadata.get("tool"), number(metadata.get("started")), number(metadata.get("used")),
                number(metadata.get("discarded")), number(metadata.get("hiddenLatencyMillis")),
                candidate.entry().timestampMillis(), candidate.fusedScore());
    }

    private static RecoveryMetadata recoveryMetadata(String trajectory, boolean verifiedObjective) {
        if (!verifiedObjective || trajectory == null || trajectory.isBlank()) return new RecoveryMetadata("", "");
        String main = trajectory;
        int dependencies = main.indexOf(" | dependencies:");
        if (dependencies >= 0) main = main.substring(0, dependencies);
        String[] steps = main.split("\\s*->\\s*");
        int failureIndex = -1;
        String signature = "";
        for (int i = 0; i < steps.length; i++) {
            String step = steps[i].strip();
            int equals = step.indexOf('=');
            if (equals <= 0) continue;
            String tool = step.substring(0, equals).strip();
            String state = step.substring(equals + 1).strip().toLowerCase(java.util.Locale.ROOT);
            int marker = state.indexOf("failure=");
            if (marker >= 0 || state.startsWith("failed") || state.startsWith("stale") || state.startsWith("rejected") || state.startsWith("partial")) {
                String failure = marker >= 0 ? state.substring(marker + 8).replace(")", "").strip() : state;
                signature = tool.toLowerCase(java.util.Locale.ROOT) + "|" + failure;
                failureIndex = i;
                break;
            }
        }
        if (failureIndex < 0 || failureIndex + 1 >= steps.length) return new RecoveryMetadata("", "");
        java.util.ArrayList<String> sequence = new java.util.ArrayList<>();
        for (int i = failureIndex + 1; i < steps.length; i++) {
            String step = steps[i].strip();
            int equals = step.indexOf('=');
            if (equals > 0) sequence.add(step.substring(0, equals).strip());
        }
        return sequence.isEmpty() ? new RecoveryMetadata("", "")
                : new RecoveryMetadata(signature, String.join(" -> ", sequence));
    }

    private record RecoveryMetadata(String signature, String sequence) {}

    private static String argumentShape(ModelToolCall call) {
        if (call.arguments() == null || call.arguments().entrySet().isEmpty()) return "none";
        return call.arguments().entrySet().stream()
                .map(entry -> entry.getKey() + ":" + (entry.getValue().isJsonObject() ? "object"
                        : entry.getValue().isJsonArray() ? "array"
                        : entry.getValue().isJsonPrimitive() ? "value" : "null"))
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip();
    }

    private static String executionKey(String sessionId, long observationEpoch, ModelToolCall call, ModelToolResult result) {
        String callId = normalized(call.id());
        if (callId.isEmpty()) callId = normalized(result.callId());
        if (callId.isEmpty()) callId = call.toolId() + '\u0000' + argumentShape(call) + '\u0000' + result.startedAtMillis();
        return normalized(sessionId) + '\u0000' + Math.max(0L, observationEpoch) + '\u0000' + callId;
    }

    private static String digest(String text) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
