package com.spirit.koil.api.model.planning;

import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Allows repeated capabilities when the world observation changes, while
 * stopping exact no-progress loops inside one ordered objective occurrence.
 *
 * <p>Progress is scoped by ordered-task occurrence, not merely by tool id and
 * arguments. This distinction is critical for explicit repetition such as
 * "jump, then jump" or "kill me, then kill me": completion of occurrence N
 * must never make occurrence N+1 look already satisfied just because both use
 * the same concrete call.</p>
 *
 * <p>The call's scope is captured in {@link #before(ModelToolCall)} and carried
 * until {@link #record(ModelToolCall, ModelToolResult)}. Therefore an async
 * result that arrives after the ordered ledger has advanced is still recorded
 * against the occurrence that launched it, rather than contaminating the new
 * task scope.</p>
 */
public final class AutomationProgressGuard {
    private static final int MAXIMUM_UNCHANGED_RETRIES = 1;

    private final Map<String, Attempt> attempts = new LinkedHashMap<>();
    private final Map<String, Long> callScopes = new LinkedHashMap<>();
    private long scope;

    public synchronized Decision before(ModelToolCall call) {
        long callScope = scope;
        if (call != null && call.id() != null && !call.id().isBlank()) {
            callScopes.put(call.id(), callScope);
        }
        String signature = signature(call, callScope);
        Attempt attempt = attempts.get(signature);
        if (attempt == null) return new Decision(true, signature, "first_attempt");
        if (attempt.objectiveReached) return new Decision(false, signature, "objective_already_reached");
        if (attempt.unchangedRetries >= MAXIMUM_UNCHANGED_RETRIES) {
            return new Decision(false, signature, "same_action_same_observation");
        }
        return new Decision(true, signature, attempt.stateChanged ? "progress_observed" : "one_recovery_retry");
    }

    /**
     * Starts a fresh no-progress scope after the ordered task ledger advances.
     * The same exact action can be legitimately requested again by a later
     * task. In-flight calls retain their captured old scope so a late record()
     * cannot poison this new occurrence.
     */
    public synchronized void nextTask() {
        scope++;
        attempts.clear();
        // Do not clear callScopes here. A just-completed async invocation may
        // record after the ledger advances; its captured scope is what keeps
        // that result attached to the occurrence that actually launched it.
        trimCallScopes();
    }

    public synchronized Observation record(ModelToolCall call, ModelToolResult result) {
        long callScope = scopeForRecord(call);
        String signature = signature(call, callScope);
        String fingerprint = fingerprint(result);
        boolean stateChanged = bool(result, "stateChanged") || changedDelta(result);
        boolean objectiveReached = bool(result, "objectiveReached");
        Attempt previous = attempts.get(signature);
        boolean newObservation = previous == null || !previous.fingerprint.equals(fingerprint);
        // stateChanged describes change within this one action. It does not
        // make two identical after-observations different from each other.
        int unchanged = previous == null || newObservation ? 0 : previous.unchangedRetries + 1;
        attempts.put(signature, new Attempt(fingerprint, stateChanged, objectiveReached, unchanged));
        return new Observation(signature, newObservation, stateChanged, objectiveReached, unchanged);
    }

    private long scopeForRecord(ModelToolCall call) {
        if (call == null || call.id() == null || call.id().isBlank()) return scope;
        Long captured = callScopes.remove(call.id());
        return captured == null ? scope : captured;
    }

    private void trimCallScopes() {
        // Defensive bound only. Normally entries are removed as their results
        // arrive. Keep the newest outstanding calls if a provider disappears.
        while (callScopes.size() > 256) {
            String first = callScopes.keySet().iterator().next();
            callScopes.remove(first);
        }
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

    private static String signature(ModelToolCall call, long scope) {
        return "occurrence=" + scope + '|'
                + (call == null ? "missing_call" : call.toolId() + ':' + call.arguments());
    }

    private record Attempt(String fingerprint, boolean stateChanged, boolean objectiveReached, int unchangedRetries) {}
    public record Decision(boolean allowed, String signature, String reason) {}
    public record Observation(String signature, boolean newObservation, boolean stateChanged,
                              boolean objectiveReached, int unchangedRetries) {}
}
