package com.spirit.koil.api.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.retrieval.AutomationExecutionExperience;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Cheap deterministic first-stage predictor. It deliberately does not invoke a
 * second model. Prompt-aware catalog selection supplies candidates, explicit
 * tool policy supplies safety/cost, and structured historical execution
 * evidence reranks candidates. Current policy/preflight always wins over
 * history.
 */
final class ToolIntentPredictor {
    private static final int MAX_PREDICTIONS = 6;

    private ToolIntentPredictor() {}

    static List<ToolPrediction> predict(
            String objective,
            List<ModelToolDefinition> roundTools,
            Set<String> requiredToolIds,
            Set<String> statePriorityToolIds,
            com.spirit.koil.api.model.reasoning.AgentState agentState,
            List<AutomationExecutionExperience> experience,
            List<VerifiedToolEvidence> verifiedEvidence,
            ToolTrajectoryPlanner.Plan trajectoryPlan
    ) {
        if (roundTools == null || roundTools.isEmpty()) return List.of();
        String goal = normalize(objective);
        List<AutomationExecutionExperience> history = experience == null ? List.of() : experience;
        ArrayList<ToolPrediction> predictions = new ArrayList<>();

        for (ModelToolDefinition definition : roundTools) {
            if (definition == null || definition.id().isBlank()) continue;
            String id = definition.id();
            if (id.equals("automation.cancel") || id.startsWith("automation.plan")) continue;

            int requiredArguments = requiredArguments(definition.inputSchema());
            boolean safe = definition.sideEffects().isEmpty()
                    && !definition.confirmationRequired()
                    && definition.executionPolicy().allowsSpeculativeRead();
            double score = 0.34D;
            ArrayList<String> reasons = new ArrayList<>();
            reasons.add("prompt_catalog");

            if (requiredToolIds != null && requiredToolIds.contains(id)) {
                score += 0.34D;
                reasons.add("explicit_objective_tool");
            }
            if (statePriorityToolIds != null && statePriorityToolIds.contains(id)) {
                score += 0.28D;
                reasons.add("agent_state_target");
            }

            HistoricalSignal historical = historicalSignal(history, id);
            score += historical.adjustment();
            if (historical.verifiedSuccesses() > 0) reasons.add("similar_verified_experience");
            if (historical.failures() > historical.verifiedSuccesses()) reasons.add("historical_failure_penalty");

            if (trajectoryPlan != null && trajectoryPlan.available()) {
                int position = trajectoryPlan.position(id);
                if (position == 0) {
                    score += 0.16D * trajectoryPlan.confidence();
                    reasons.add("verified_trajectory_next");
                } else if (position > 0 && position < 4) {
                    score += (0.10D - position * 0.015D) * trajectoryPlan.confidence();
                    reasons.add("verified_trajectory_upcoming");
                }
            }

            if (goal.contains(simpleName(id))) {
                score += 0.08D;
                reasons.add("objective_name_match");
            }
            if (requiredArguments == 0) {
                score += 0.08D;
                reasons.add("zero_argument");
            } else {
                score -= Math.min(0.18D, requiredArguments * 0.045D);
            }

            ToolArgumentResolver.Resolution resolution = ToolArgumentResolver.resolve(id, objective, verifiedEvidence, agentState);
            if (requiredArguments > 0 && resolution.resolved()) {
                if (resolution.authoritative()) {
                    score += 0.16D * resolution.confidence();
                    reasons.add("arguments:" + resolution.provenance());
                } else {
                    score += 0.03D;
                    reasons.add("argument_hint_only:" + resolution.provenance());
                }
            }

            score -= switch (definition.executionPolicy().cost()) {
                case CHEAP -> 0.0D;
                case MODERATE -> 0.03D;
                case EXPENSIVE -> 0.10D;
            };
            if (!safe) score = Math.min(score, 0.39D);

            predictions.add(new ToolPrediction(
                    id,
                    score,
                    requiredArguments,
                    safe,
                    resolution.arguments(),
                    resolution.provenance(),
                    resolution.authoritative(),
                    resolution.sourceCallIds(),
                    reasons
            ));
        }

        return predictions.stream()
                .sorted(Comparator.comparingDouble(ToolPrediction::confidence).reversed()
                        .thenComparing(ToolPrediction::toolId))
                .limit(MAX_PREDICTIONS)
                .toList();
    }

    private static HistoricalSignal historicalSignal(List<AutomationExecutionExperience> history, String toolId) {
        int successes = 0;
        int failures = 0;
        double weightedSuccess = 0.0D;
        double weightedFailure = 0.0D;
        for (AutomationExecutionExperience item : history) {
            if (item == null || !toolId.equals(item.toolId())) continue;
            double relevance = Math.max(0.15D, Math.min(1.0D, item.retrievalScore()));
            if (item.verified() || item.objectiveCompleted()) {
                successes++;
                weightedSuccess += relevance * (item.objectiveCompleted() ? 1.0D : 0.75D);
            } else if (!item.failureCode().isBlank() || "failed".equals(item.status()) || "stale".equals(item.status())) {
                failures++;
                weightedFailure += relevance;
            }
        }
        double adjustment = Math.min(0.18D, weightedSuccess * 0.09D)
                - Math.min(0.14D, weightedFailure * 0.07D);
        return new HistoricalSignal(adjustment, successes, failures);
    }

    private static int requiredArguments(JsonObject schema) {
        if (schema == null) return 0;
        JsonElement required = schema.get("required");
        if (required == null || !required.isJsonArray()) return 0;
        JsonArray array = required.getAsJsonArray();
        return array.size();
    }

    private static String simpleName(String id) {
        int dot = id.lastIndexOf('.');
        return dot < 0 ? id : id.substring(dot + 1);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private record HistoricalSignal(double adjustment, int verifiedSuccesses, int failures) {}
}
