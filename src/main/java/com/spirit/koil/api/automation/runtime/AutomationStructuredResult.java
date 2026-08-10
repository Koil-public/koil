package com.spirit.koil.api.automation.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reusable model-first projection of a KTL execution result. KTL primitives
 * keep contributing evidence through namespaced state keys; this projection
 * preserves those exact values while exposing stable status/objective fields.
 */
public record AutomationStructuredResult(
        AutomationResultStatus status,
        String action,
        String reason,
        boolean objectiveReached,
        boolean stateChanged,
        boolean retrySameAction,
        boolean continueRecommended,
        boolean replanRecommended,
        Map<String, Object> requested,
        Map<String, Object> before,
        Map<String, Object> after,
        Map<String, Object> delta,
        Map<String, Object> metrics,
        List<Map<String, Object>> failures,
        List<Map<String, Object>> recoveries
) {
    public AutomationStructuredResult {
        status = status == null ? AutomationResultStatus.FAILED : status;
        action = clean(action);
        reason = clean(reason);
        requested = copy(requested);
        before = copy(before);
        after = copy(after);
        delta = copy(delta);
        metrics = copy(metrics);
        failures = failures == null ? List.of() : failures.stream().map(AutomationStructuredResult::copy).toList();
        recoveries = recoveries == null ? List.of() : recoveries.stream().map(AutomationStructuredResult::copy).toList();
    }

    public static AutomationStructuredResult from(AutomationExecutionResult result) {
        if (result == null) {
            return new AutomationStructuredResult(AutomationResultStatus.FAILED, "", "missing_result",
                    false, false, false, false, false,
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of());
        }
        Map<String, Object> state = result.state();
        Map<String, Object> requested = prefixed(state, "result.requested.");
        Map<String, Object> before = prefixed(state, "result.before.");
        Map<String, Object> after = prefixed(state, "result.after.");
        Map<String, Object> delta = prefixed(state, "result.delta.");
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : state.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("result.")
                    && !key.startsWith("result.requested.")
                    && !key.startsWith("result.before.")
                    && !key.startsWith("result.after.")
                    && !key.startsWith("result.delta.")
                    && !key.startsWith("result.recovery.")) {
                metrics.put(key.substring("result.".length()), entry.getValue());
            }
        }
        if (result.initialPosition() != null && result.finalPosition() != null) {
            double distance = distance(result.initialPosition(), result.finalPosition());
            metrics.putIfAbsent("distance_traveled", rounded(distance));
        }
        long duration = result.startedAt() == null || result.finishedAt() == null
                ? 0L
                : Math.max(0L, Duration.between(result.startedAt(), result.finishedAt()).toMillis());
        metrics.put("duration_ms", duration);
        boolean changed = booleanValue(state, "result.state_changed", inferChanged(delta, metrics));
        AutomationResultStatus status = AutomationResultStatus.from(result.status(), result.failureCode(), changed);
        boolean reached = booleanValue(state, "result.objective_reached", status.objectiveReachedByDefinition());
        if (status == AutomationResultStatus.SUCCESS && !reached) status = changed ? AutomationResultStatus.PARTIAL : AutomationResultStatus.BLOCKED;
        boolean retry = booleanValue(state, "result.retry_same_action",
                booleanValue(state, "result.retryable", status == AutomationResultStatus.FAILED));
        boolean replan = booleanValue(state, "result.replan_recommended",
                status == AutomationResultStatus.BLOCKED || status == AutomationResultStatus.PARTIAL);
        boolean continuing = booleanValue(state, "result.continue_recommended",
                status == AutomationResultStatus.PARTIAL);
        String reason = first(state, "result.reason", "result.failure_reason", "result.last_failure");
        if (reason.isBlank()) reason = result.failureCode();
        String action = first(state, "result.action_id", "result.action");
        if (action.isBlank()) action = result.templateId();
        return new AutomationStructuredResult(status, action, reason, reached, changed, retry, continuing,
                replan, requested, before, after, delta, metrics, indexedRows(state, "result.failure."),
                indexedRows(state, "result.recovery."));
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("status", status.name());
        json.addProperty("action", action);
        json.addProperty("reason", reason);
        json.addProperty("objectiveReached", objectiveReached);
        json.addProperty("stateChanged", stateChanged);
        json.addProperty("retrySameAction", retrySameAction);
        json.addProperty("continueRecommended", continueRecommended);
        json.addProperty("replanRecommended", replanRecommended);
        json.add("requested", object(requested));
        json.add("before", object(before));
        json.add("after", object(after));
        json.add("delta", object(delta));
        json.add("metrics", object(metrics));
        JsonArray failureRows = new JsonArray();
        failures.forEach(value -> failureRows.add(object(value)));
        json.add("failures", failureRows);
        JsonArray recoveryRows = new JsonArray();
        recoveries.forEach(value -> recoveryRows.add(object(value)));
        json.add("recoveries", recoveryRows);
        return json;
    }

    /** Deterministic evidence fingerprint used by the model progress guard. */
    public String progressFingerprint() {
        Map<String, Object> stableMetrics = new LinkedHashMap<>(metrics);
        stableMetrics.remove("duration_ms");
        stableMetrics.remove("attempts");
        return status.name() + '|' + objectiveReached + '|' + stateChanged + '|' + reason + '|'
                + after + '|' + delta + '|' + stableMetrics + '|' + failures + '|' + recoveries;
    }

    public String conciseSummary() {
        StringBuilder text = new StringBuilder(status.name());
        Object completed = firstValue(metrics, "completed_amount", "completed", "count", "transfer.moved_count");
        Object requestedAmount = firstValue(requested, "amount", "count");
        Object remaining = firstValue(metrics, "remaining_amount", "remaining", "distance_remaining");
        if (completed != null && requestedAmount != null) text.append(" | ").append(completed).append(" / ").append(requestedAmount);
        else if (metrics.containsKey("distance_traveled")) text.append(" | ").append(metrics.get("distance_traveled")).append(" blocks traveled");
        if (remaining != null) text.append(" | ").append(remaining).append(" remaining");
        if (!reason.isBlank()) text.append(" | ").append(reason.replace('_', ' '));
        return text.toString();
    }

    private static List<Map<String, Object>> indexedRows(Map<String, Object> state, String prefix) {
        Map<Integer, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : state.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) continue;
            String remainder = key.substring(prefix.length());
            int dot = remainder.indexOf('.');
            if (dot <= 0) continue;
            try {
                int index = Integer.parseInt(remainder.substring(0, dot));
                rows.computeIfAbsent(index, ignored -> new LinkedHashMap<>())
                        .put(remainder.substring(dot + 1), entry.getValue());
            } catch (NumberFormatException ignored) {
                // Aggregate recovery metrics remain in the raw result state.
            }
        }
        return rows.values().stream().map(Map::copyOf).toList();
    }

    private static Map<String, Object> prefixed(Map<String, Object> state, String prefix) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : state.entrySet()) {
            if (entry.getKey().startsWith(prefix)) values.put(entry.getKey().substring(prefix.length()), entry.getValue());
        }
        return values;
    }

    private static boolean inferChanged(Map<String, Object> delta, Map<String, Object> metrics) {
        if (!delta.isEmpty()) {
            return delta.values().stream().anyMatch(value -> value instanceof Number number
                    ? Math.abs(number.doubleValue()) > 1.0E-6D
                    : value != null && !value.toString().isBlank() && !value.toString().equals("false"));
        }
        Object distance = metrics.get("distance_traveled");
        return distance instanceof Number number && number.doubleValue() > 0.05D;
    }

    private static String first(Map<String, Object> state, String... keys) {
        Object value = firstValue(state, keys);
        return value == null ? "" : value.toString();
    }

    private static Object firstValue(Map<String, Object> state, String... keys) {
        for (String key : keys) {
            Object value = state.get(key);
            if (value != null && !value.toString().isBlank()) return value;
        }
        return null;
    }

    private static boolean booleanValue(Map<String, Object> state, String key, boolean fallback) {
        Object value = state.get(key);
        if (value instanceof Boolean bool) return bool;
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private static double distance(AutomationPositionSnapshot a, AutomationPositionSnapshot b) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double dz = b.z() - a.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double rounded(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private static JsonObject object(Map<String, Object> values) {
        JsonObject object = new JsonObject();
        values.forEach((key, value) -> add(object, key, value));
        return object;
    }

    private static void add(JsonObject object, String key, Object value) {
        if (value == null) object.add(key, com.google.gson.JsonNull.INSTANCE);
        else if (value instanceof Boolean bool) object.addProperty(key, bool);
        else if (value instanceof Number number) object.addProperty(key, number);
        else if (value instanceof JsonElement element) object.add(key, element.deepCopy());
        else object.addProperty(key, value.toString());
    }

    private static Map<String, Object> copy(Map<String, Object> value) {
        return value == null ? Map.of() : Map.copyOf(value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
