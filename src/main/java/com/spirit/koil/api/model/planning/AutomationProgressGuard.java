package com.spirit.koil.api.model.planning;

import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Allows repeated capabilities when the world observation changes, while
 * stopping exact no-progress loops. This is session-local and deliberately
 * has no elapsed-time or total-action budget.
 */
public final class AutomationProgressGuard {
    private static final int MAXIMUM_UNCHANGED_RETRIES = 1;
    private final Map<String, Attempt> attempts = new LinkedHashMap<>();

    public synchronized Decision before(ModelToolCall call) {
        String signature = signature(call);
        Attempt attempt = attempts.get(signature);
        if (attempt == null) return new Decision(true, signature, "first_attempt");
        if (attempt.objectiveReached) return new Decision(false, signature, "objective_already_reached");
        if (attempt.unchangedRetries >= MAXIMUM_UNCHANGED_RETRIES) {
            return new Decision(false, signature, "same_action_same_observation");
        }
        return new Decision(true, signature, attempt.stateChanged ? "progress_observed" : "one_recovery_retry");
    }

    public synchronized Observation record(ModelToolCall call, ModelToolResult result) {
        String signature = signature(call);
        String fingerprint = fingerprint(result);
        boolean stateChanged = bool(result, "stateChanged") || changedDelta(result);
        boolean objectiveReached = bool(result, "objectiveReached") || result != null && result.completedAndValidated();
        Attempt previous = attempts.get(signature);
        boolean newObservation = previous == null || !previous.fingerprint.equals(fingerprint);
        // stateChanged describes change within this one action. It does not
        // make two identical after-observations different from each other.
        int unchanged = previous == null || newObservation ? 0 : previous.unchangedRetries + 1;
        attempts.put(signature, new Attempt(fingerprint, stateChanged, objectiveReached, unchanged));
        return new Observation(signature, newObservation, stateChanged, objectiveReached, unchanged);
    }

    private static boolean bool(ModelToolResult result, String key) {
        JsonObject structured = structured(result);
        return structured != null && structured.has(key) && structured.get(key).getAsBoolean();
    }

    private static boolean changedDelta(ModelToolResult result) {
        JsonObject structured = structured(result);
        return structured != null && structured.has("delta") && structured.get("delta").isJsonObject()
                && !structured.getAsJsonObject("delta").entrySet().isEmpty();
    }

    private static JsonObject structured(ModelToolResult result) {
        if (result == null || result.output() == null || !result.output().has("structuredResult")
                || !result.output().get("structuredResult").isJsonObject()) return null;
        return result.output().getAsJsonObject("structuredResult");
    }

    private static String fingerprint(ModelToolResult result) {
        if (result == null) return "missing_result";
        JsonObject structured = structured(result);
        if (structured != null) {
            structured = structured.deepCopy();
            if (structured.has("metrics") && structured.get("metrics").isJsonObject()) {
                JsonObject metrics = structured.getAsJsonObject("metrics");
                metrics.remove("duration_ms");
                metrics.remove("attempts");
            }
        }
        return result.status() + '|' + result.failureCode() + '|'
                + (structured == null ? result.output().toString() : structured.toString());
    }

    private static String signature(ModelToolCall call) {
        return call == null ? "missing_call" : call.toolId() + ':' + call.arguments();
    }

    private record Attempt(String fingerprint, boolean stateChanged, boolean objectiveReached, int unchangedRetries) {}
    public record Decision(boolean allowed, String signature, String reason) {}
    public record Observation(String signature, boolean newObservation, boolean stateChanged,
                              boolean objectiveReached, int unchangedRetries) {}
}
