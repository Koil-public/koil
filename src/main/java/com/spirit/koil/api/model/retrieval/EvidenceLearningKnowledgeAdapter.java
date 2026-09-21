package com.spirit.koil.api.model.retrieval;

import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Learns only from observed tool outcomes. It never stores model chain-of-thought and it never promotes
 * a proposed fix to a verified repair until current execution evidence proves the objective completed.
 * Every learned strategy is explicitly historical candidate evidence, never an instruction or exclusive fix.
 */
public final class EvidenceLearningKnowledgeAdapter {
    private static final int MAX_DETAIL = 720;
    private static final int MAX_OUTPUT = 1_100;

    private final KoilRetrievalEngine engine;

    public EvidenceLearningKnowledgeAdapter(KoilRetrievalEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public CompletableFuture<Void> recordExecution(
            String objective,
            String sessionId,
            String environmentFingerprint,
            ModelToolCall call,
            ModelToolResult result,
            boolean objectiveCompleted,
            String trajectory
    ) {
        if (call == null || result == null || call.toolId().isBlank()) return CompletableFuture.completedFuture(null);
        String goal = clean(objective);
        String session = clean(sessionId);
        String environment = clean(environmentFingerprint);
        String observedTrajectory = clean(trajectory);
        ArrayList<KnowledgeEntry> entries = new ArrayList<>();

        if (!result.completedAndValidated()) {
            entries.add(failedAttempt(goal, session, environment, call, result));
        }

        DerivedRecovery recovery = deriveRecovery(observedTrajectory);
        if (objectiveCompleted && result.completedAndValidated()) {
            if (!recovery.failureSignature().isBlank() && !recovery.recoveryTools().isEmpty()) {
                entries.add(verifiedRepair(goal, session, environment, result, observedTrajectory, recovery));
            }
            List<String> successfulTools = successfulTools(observedTrajectory);
            if (successfulTools.size() >= 2) {
                entries.add(workflowRecipe(goal, session, environment, result, successfulTools));
            }
        }

        KnowledgeEntry environmentFact = environmentFact(goal, session, environment, call, result);
        if (environmentFact != null) entries.add(environmentFact);
        KnowledgeEntry performance = performanceObservation(goal, session, environment, call, result);
        if (performance != null) entries.add(performance);

        if (entries.isEmpty()) return CompletableFuture.completedFuture(null);
        CompletableFuture<?>[] writes = entries.stream().map(this::rememberEpisode).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(writes);
    }

    private CompletableFuture<Long> rememberEpisode(KnowledgeEntry entry) {
        String sourceId = entry.metadata().get("sourceId");
        String sourceKey = entry.metadata().get("sourceKey");
        return this.engine.synchronizeSource(new KnowledgeSourceSnapshot(sourceId, digest(entry.text()), List.of(entry)))
                .thenCompose(ignored -> this.engine.sourceEntryId(sourceId, sourceKey));
    }

    private static KnowledgeEntry failedAttempt(String goal, String session, String environment,
                                                ModelToolCall call, ModelToolResult result) {
        String signature = failureSignature(call, result);
        String sourceId = "evidence.counterexample:" + digest(session + '\u0000' + result.callId() + '\u0000' + signature);
        Map<String, String> metadata = baseMetadata(sourceId, "attempt", environment);
        metadata.put("evidenceKind", "disconfirmed_attempt");
        metadata.put("outcome", "failed");
        metadata.put("failureSignature", signature);
        String observedFailure = salientFailure(result);
        if (!observedFailure.isBlank()) metadata.put("failureSummary", observedFailure);
        metadata.put("toolId", call.toolId());
        metadata.put("verification", "observed_tool_failure");
        metadata.put("retryable", Boolean.toString(result.retryable()));
        String text = "Observed unsuccessful attempt. This is negative evidence for this context, not a universal prohibition."
                + "\nObjective: " + value(goal)
                + "\nTool: " + call.toolId()
                + "\nFailure signature: " + signature
                + "\nStatus: " + result.status()
                + "\nValidation: " + result.validationStatus()
                + (result.detail().isBlank() ? "" : "\nObserved detail: " + compact(result.detail(), MAX_DETAIL))
                + (observedFailure.isBlank() ? "" : "\nObserved failure excerpt: " + observedFailure)
                + (result.output().toString().equals("{}") ? "" : "\nObserved tool output: " + compact(result.output().toString(), MAX_OUTPUT))
                + "\nEnvironment fingerprint: " + value(environment)
                + "\nUse: avoid blindly repeating this exact attempt; inspect current evidence because another resolution may still work.";
        return entry(KnowledgeType.COUNTEREXAMPLE, "repair-evidence", text, "execution.counterexample", session,
                0.72D, 0.92D, metadata);
    }

    private static KnowledgeEntry verifiedRepair(String goal, String session, String environment,
                                                 ModelToolResult finalResult, String trajectory,
                                                 DerivedRecovery recovery) {
        String sequence = String.join(" -> ", recovery.recoveryTools());
        String sourceId = "evidence.repair:" + digest(session + '\u0000' + finalResult.callId() + '\u0000'
                + recovery.failureSignature() + '\u0000' + sequence);
        Map<String, String> metadata = baseMetadata(sourceId, "repair", environment);
        metadata.put("evidenceKind", "verified_repair_candidate");
        metadata.put("outcome", "verified_success");
        metadata.put("failureSignature", recovery.failureSignature());
        metadata.put("recoverySequence", sequence);
        metadata.put("verification", "objective_completed_and_final_result_validated");
        metadata.put("proofStatus", finalResult.status());
        metadata.put("proofValidation", finalResult.validationStatus());
        String text = "Previously verified repair candidate. This is one proven path, not the only valid solution."
                + "\nObjective: " + value(goal)
                + "\nFailure signature: " + recovery.failureSignature()
                + "\nRecovery sequence: " + sequence
                + "\nProof: the objective completed after this observed sequence and the final tool result was completed+validated."
                + "\nEnvironment fingerprint: " + value(environment)
                + "\nObserved trajectory: " + compact(trajectory, 1_100)
                + "\nUse: compare current evidence and preconditions before reusing any step or argument.";
        return entry(KnowledgeType.REPAIR_EPISODE, "repair-evidence", text, "execution.repair", session,
                0.92D, 0.96D, metadata);
    }

    private static KnowledgeEntry workflowRecipe(String goal, String session, String environment,
                                                 ModelToolResult finalResult, List<String> tools) {
        String sequence = String.join(" -> ", tools);
        String sourceId = "evidence.workflow:" + digest(session + '\u0000' + finalResult.callId() + '\u0000' + sequence);
        Map<String, String> metadata = baseMetadata(sourceId, "workflow", environment);
        metadata.put("evidenceKind", "verified_workflow_candidate");
        metadata.put("workflowSequence", sequence);
        metadata.put("verification", "objective_completed_and_final_result_validated");
        metadata.put("outcome", "verified_success");
        String text = "Previously successful workflow candidate. This sequence is strategy evidence, not a mandatory plan."
                + "\nObjective: " + value(goal)
                + "\nObserved tool sequence: " + sequence
                + "\nProof: objective completed and final result was completed+validated."
                + "\nEnvironment fingerprint: " + value(environment)
                + "\nUse: re-check current prerequisites, arguments, and available tools before applying.";
        return entry(KnowledgeType.WORKFLOW_RECIPE, "workflow-evidence", text, "execution.workflow", session,
                0.80D, 0.93D, metadata);
    }

    private static KnowledgeEntry environmentFact(String goal, String session, String environment,
                                                  ModelToolCall call, ModelToolResult result) {
        if (!result.completedAndValidated() || !environmentBearing(call.toolId())) return null;
        String sourceId = "evidence.environment:" + digest(call.toolId() + '\u0000' + environment);
        Map<String, String> metadata = baseMetadata(sourceId, "environment", environment);
        metadata.put("evidenceKind", "tool_observed_environment");
        metadata.put("toolId", call.toolId());
        metadata.put("verification", "completed_tool_observation");
        String output = compact(result.output().toString(), MAX_OUTPUT);
        String text = "Tool-observed environment capability/state. Historical evidence; re-check if the environment changed."
                + "\nTool: " + call.toolId()
                + "\nObjective context: " + value(goal)
                + "\nEnvironment fingerprint: " + value(environment)
                + (output.isBlank() || "{}".equals(output) ? "" : "\nObserved output: " + output);
        return entry(KnowledgeType.ENVIRONMENT_FACT, "environment-evidence", text, "execution.environment", session,
                0.62D, 0.94D, metadata);
    }

    private static KnowledgeEntry performanceObservation(String goal, String session, String environment,
                                                         ModelToolCall call, ModelToolResult result) {
        if (!result.completedAndValidated() || result.durationMillis() < 250L) return null;
        String sourceId = "evidence.performance:" + digest(session + '\u0000' + result.callId());
        Map<String, String> metadata = baseMetadata(sourceId, "performance", environment);
        metadata.put("evidenceKind", "measured_tool_performance");
        metadata.put("toolId", call.toolId());
        metadata.put("durationMillis", Long.toString(result.durationMillis()));
        metadata.put("verification", "measured_completed_execution");
        String text = "Measured historical tool performance observation. Do not assume future latency is identical."
                + "\nTool: " + call.toolId()
                + "\nDuration ms: " + result.durationMillis()
                + "\nObjective context: " + value(goal)
                + "\nEnvironment fingerprint: " + value(environment);
        return entry(KnowledgeType.PERFORMANCE_OBSERVATION, "performance-evidence", text, "execution.performance", session,
                0.38D, 0.98D, metadata);
    }

    private static KnowledgeEntry entry(KnowledgeType type, String scope, String text, String source, String session,
                                        double importance, double confidence, Map<String, String> metadata) {
        return new KnowledgeEntry(1L, type, scope, text, source, session, System.currentTimeMillis(),
                importance, confidence, KnowledgeTrust.HISTORICAL_CONTEXT, Map.copyOf(metadata));
    }

    private static Map<String, String> baseMetadata(String sourceId, String sourceKey, String environment) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("sourceId", sourceId);
        metadata.put("sourceKey", sourceKey);
        metadata.put("origin", "verified_execution_evidence");
        metadata.put("candidateOnly", "true");
        metadata.put("environmentFingerprint", value(environment));
        metadata.put("modelAuthored", "false");
        return metadata;
    }

    private static boolean environmentBearing(String toolId) {
        if (toolId == null) return false;
        return toolId.equals("system.resources")
                || toolId.equals("system.processes")
                || toolId.equals("package.inspect")
                || toolId.equals("database.inspect")
                || toolId.equals("git.status")
                || toolId.equals("git.branches")
                || toolId.equals("local_index.status")
                || toolId.equals("process.status")
                || toolId.equals("workspace.build")
                || toolId.equals("workspace.environment")
                || toolId.equals("workspace.roots");
    }

    private static DerivedRecovery deriveRecovery(String trajectory) {
        if (trajectory == null || trajectory.isBlank()) return new DerivedRecovery("", List.of());
        String main = trajectory;
        int dependency = main.indexOf(" | dependencies:");
        if (dependency >= 0) main = main.substring(0, dependency);
        String[] steps = main.split("\\s*->\\s*");
        int failure = -1;
        String signature = "";
        for (int index = 0; index < steps.length; index++) {
            String step = steps[index].strip();
            int equals = step.indexOf('=');
            if (equals <= 0) continue;
            String tool = step.substring(0, equals).strip().toLowerCase(Locale.ROOT);
            String state = step.substring(equals + 1).strip().toLowerCase(Locale.ROOT);
            int marker = state.indexOf("failure=");
            if (marker < 0 && !state.startsWith("failed") && !state.startsWith("stale")
                    && !state.startsWith("rejected") && !state.startsWith("partial") && !state.startsWith("timed_out")) continue;
            String code = marker >= 0 ? state.substring(marker + 8).replace(")", "").strip() : state;
            signature = tool + "|" + code;
            failure = index;
            break;
        }
        if (failure < 0) return new DerivedRecovery("", List.of());
        ArrayList<String> recovery = new ArrayList<>();
        for (int index = failure + 1; index < steps.length; index++) {
            String step = steps[index].strip();
            int equals = step.indexOf('=');
            if (equals <= 0) continue;
            String tool = step.substring(0, equals).strip();
            String state = step.substring(equals + 1).strip().toLowerCase(Locale.ROOT);
            if (!tool.isBlank() && (state.startsWith("completed") || state.startsWith("passed") || state.startsWith("success"))) {
                recovery.add(tool);
            }
        }
        return new DerivedRecovery(signature, List.copyOf(recovery));
    }

    private static List<String> successfulTools(String trajectory) {
        if (trajectory == null || trajectory.isBlank()) return List.of();
        String main = trajectory;
        int dependency = main.indexOf(" | dependencies:");
        if (dependency >= 0) main = main.substring(0, dependency);
        ArrayList<String> tools = new ArrayList<>();
        for (String raw : main.split("\\s*->\\s*")) {
            String step = raw.strip();
            int equals = step.indexOf('=');
            if (equals <= 0) continue;
            String tool = step.substring(0, equals).strip();
            String state = step.substring(equals + 1).strip().toLowerCase(Locale.ROOT);
            if (!tool.isBlank() && (state.startsWith("completed") || state.startsWith("passed") || state.startsWith("success"))) tools.add(tool);
        }
        return List.copyOf(tools);
    }

    private static String salientFailure(ModelToolResult result) {
        if (result == null) return "";
        String source = clean(result.detail() + "\n" + result.output());
        if (source.isBlank() || "{}".equals(source)) return "";
        String[] markers = {"cannot find symbol", "error:", "exception", "failed", "failure", "exceeds", "timeout", "timed out", "not found", "invalid"};
        String lower = source.toLowerCase(Locale.ROOT);
        int best = -1;
        for (String marker : markers) {
            int at = lower.indexOf(marker);
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        if (best < 0) return compact(source, 360);
        int start = Math.max(0, best - 100);
        int end = Math.min(source.length(), best + 360);
        return compact(source.substring(start, end), 420);
    }

    private static String failureSignature(ModelToolCall call, ModelToolResult result) {
        String failure = clean(result.failureCode());
        if (failure.isBlank() || "not_required".equals(failure)) failure = clean(result.validationStatus());
        if (failure.isBlank() || "not_required".equals(failure)) failure = clean(result.status());
        return clean(call.toolId()) + "|" + failure;
    }

    private static String value(String value) { return value == null || value.isBlank() ? "unknown" : value.strip(); }
    private static String clean(String value) { return value == null ? "" : value.replaceAll("\\s+", " ").strip(); }
    private static String compact(String value, int max) {
        String clean = clean(value);
        return clean.length() <= max ? clean : clean.substring(0, Math.max(0, max - 1)).stripTrailing() + "…";
    }

    private static String digest(String text) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record DerivedRecovery(String failureSignature, List<String> recoveryTools) {}
}
