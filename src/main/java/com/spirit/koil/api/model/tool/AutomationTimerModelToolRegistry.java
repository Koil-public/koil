package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ToolExecutionPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-local, non-blocking wall-clock timers for automation reasoning.
 *
 * <p>Starting a timer never parks the model thread. Remaining time is derived
 * from a monotonic deadline whenever status/list is called, so the model can
 * continue executing unrelated tools while a timer runs and inspect it later.</p>
 */
public final class AutomationTimerModelToolRegistry {
    public static final String START_TOOL_ID = "timer.start";
    public static final String STATUS_TOOL_ID = "timer.status";
    public static final String LIST_TOOL_ID = "timer.list";
    public static final String CANCEL_TOOL_ID = "timer.cancel";

    private static final long MAXIMUM_DURATION_MILLIS = Duration.ofDays(7).toMillis();
    private static final int MAXIMUM_TIMERS = 64;
    private static final ConcurrentHashMap<String, TimerState> TIMERS = new ConcurrentHashMap<>();
    private static final List<ModelToolDefinition> DEFINITIONS = definitions();

    private AutomationTimerModelToolRegistry() {}

    public static String version() { return "automation-timer-tools-v1"; }
    public static List<ModelToolDefinition> modelTools() { return DEFINITIONS; }
    public static boolean supports(String id) {
        return START_TOOL_ID.equals(id) || STATUS_TOOL_ID.equals(id)
                || LIST_TOOL_ID.equals(id) || CANCEL_TOOL_ID.equals(id);
    }

    public static ModelToolResult execute(ModelToolCall call) {
        if (call == null || !supports(call.toolId())) {
            return failed(call, "unknown_timer_tool", "Unknown timer capability.");
        }
        purgeExpiredHistory();
        try {
            return switch (call.toolId()) {
                case START_TOOL_ID -> start(call);
                case STATUS_TOOL_ID -> status(call);
                case LIST_TOOL_ID -> list(call);
                case CANCEL_TOOL_ID -> cancel(call);
                default -> failed(call, "unknown_timer_tool", "Unknown timer capability.");
            };
        } catch (IllegalArgumentException failure) {
            return failed(call, "invalid_timer_arguments", failure.getMessage());
        }
    }

    private static ModelToolResult start(ModelToolCall call) {
        JsonObject args = call.arguments() == null ? new JsonObject() : call.arguments();
        double amount = number(args, "amount", 0D);
        if (!(amount > 0D) || !Double.isFinite(amount)) {
            throw new IllegalArgumentException("amount must be a finite value greater than zero.");
        }
        String unit = string(args, "unit", "seconds").toLowerCase(Locale.ROOT);
        long millis = toMillis(amount, unit);
        if (millis <= 0L || millis > MAXIMUM_DURATION_MILLIS) {
            throw new IllegalArgumentException("Timer duration must be between 1 ms and 7 days.");
        }
        if (activeCount() >= MAXIMUM_TIMERS) {
            return failed(call, "timer_capacity", "Too many active timers are already running.");
        }

        long nowEpoch = System.currentTimeMillis();
        long nowNano = System.nanoTime();
        String id = "timer-" + UUID.randomUUID().toString().substring(0, 12);
        String label = string(args, "label", "");
        TimerState timer = new TimerState(id, label, nowEpoch, nowEpoch + millis, nowNano,
                nowNano + millis * 1_000_000L, millis, false, 0L);
        TIMERS.put(id, timer);
        return completed(call, timerOutput(timer),
                "Timer started without blocking model or automation execution.");
    }

    private static ModelToolResult status(ModelToolCall call) {
        JsonObject args = call.arguments() == null ? new JsonObject() : call.arguments();
        TimerState timer = resolve(string(args, "timerId", ""), string(args, "label", ""));
        if (timer == null) return failed(call, "timer_not_found", "No matching timer exists in this session.");
        return completed(call, timerOutput(timer), "Timer status read from its monotonic deadline.");
    }

    private static ModelToolResult list(ModelToolCall call) {
        JsonArray timers = new JsonArray();
        TIMERS.values().stream()
                .sorted(Comparator.comparingLong(TimerState::startedAtEpochMillis).reversed())
                .limit(32)
                .forEach(timer -> timers.add(timerOutput(timer)));
        JsonObject output = new JsonObject();
        output.add("timers", timers);
        output.addProperty("count", timers.size());
        output.addProperty("activeCount", activeCount());
        output.addProperty("nonBlocking", true);
        return completed(call, output, "Listed session timers.");
    }

    private static ModelToolResult cancel(ModelToolCall call) {
        JsonObject args = call.arguments() == null ? new JsonObject() : call.arguments();
        TimerState timer = resolve(string(args, "timerId", ""), string(args, "label", ""));
        if (timer == null) return failed(call, "timer_not_found", "No matching timer exists in this session.");
        if (timer.cancelled()) return completed(call, timerOutput(timer), "Timer was already cancelled.");
        TimerState cancelled = new TimerState(timer.id(), timer.label(), timer.startedAtEpochMillis(),
                timer.deadlineEpochMillis(), timer.startedAtNano(), timer.deadlineNano(), timer.durationMillis(),
                true, System.currentTimeMillis());
        TIMERS.put(cancelled.id(), cancelled);
        return completed(call, timerOutput(cancelled), "Timer cancelled.");
    }

    /** Remaining monotonic time for scheduler integration; -1 when unknown. */
    public static long remainingMillis(String timerId) {
        TimerState timer = resolve(timerId, "");
        if (timer == null || timer.cancelled()) return -1L;
        return Math.max(0L, (timer.deadlineNano() - System.nanoTime()) / 1_000_000L);
    }

    private static TimerState resolve(String id, String label) {
        if (id != null && !id.isBlank()) return TIMERS.get(id.strip());
        if (label == null || label.isBlank()) {
            return TIMERS.values().stream().max(Comparator.comparingLong(TimerState::startedAtEpochMillis)).orElse(null);
        }
        String wanted = label.strip();
        return TIMERS.values().stream()
                .filter(timer -> timer.label().equalsIgnoreCase(wanted))
                .max(Comparator.comparingLong(TimerState::startedAtEpochMillis))
                .orElse(null);
    }

    private static JsonObject timerOutput(TimerState timer) {
        long remaining = timer.cancelled() ? 0L : Math.max(0L, (timer.deadlineNano() - System.nanoTime()) / 1_000_000L);
        boolean expired = !timer.cancelled() && remaining <= 0L;
        JsonObject output = new JsonObject();
        output.addProperty("timerId", timer.id());
        if (!timer.label().isBlank()) output.addProperty("label", timer.label());
        output.addProperty("durationMs", timer.durationMillis());
        output.addProperty("remainingMs", remaining);
        output.addProperty("elapsedMs", Math.min(timer.durationMillis(), Math.max(0L, timer.durationMillis() - remaining)));
        output.addProperty("startedAtEpochMs", timer.startedAtEpochMillis());
        output.addProperty("deadlineEpochMs", timer.deadlineEpochMillis());
        output.addProperty("active", !timer.cancelled() && !expired);
        output.addProperty("expired", expired);
        output.addProperty("cancelled", timer.cancelled());
        output.addProperty("nonBlocking", true);
        return output;
    }

    private static long activeCount() {
        long now = System.nanoTime();
        return TIMERS.values().stream().filter(timer -> !timer.cancelled() && timer.deadlineNano() > now).count();
    }

    private static void purgeExpiredHistory() {
        long cutoff = System.currentTimeMillis() - Duration.ofHours(24).toMillis();
        TIMERS.entrySet().removeIf(entry -> {
            TimerState timer = entry.getValue();
            return timer.deadlineEpochMillis() < cutoff || timer.cancelledAtEpochMillis() > 0L && timer.cancelledAtEpochMillis() < cutoff;
        });
    }

    private static long toMillis(double amount, String unit) {
        double factor = switch (unit) {
            case "ms", "millisecond", "milliseconds" -> 1D;
            case "s", "sec", "secs", "second", "seconds" -> 1_000D;
            case "m", "min", "mins", "minute", "minutes" -> 60_000D;
            case "h", "hr", "hrs", "hour", "hours" -> 3_600_000D;
            case "d", "day", "days" -> 86_400_000D;
            default -> throw new IllegalArgumentException("unit must be milliseconds, seconds, minutes, hours, or days.");
        };
        double millis = amount * factor;
        if (!Double.isFinite(millis) || millis > Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(1L, Math.round(millis));
    }

    private static List<ModelToolDefinition> definitions() {
        List<ModelToolDefinition> tools = new ArrayList<>();
        JsonObject start = object();
        JsonObject startProps = props(start);
        startProps.add("amount", numberSchema());
        startProps.add("unit", enumSchema("milliseconds", "seconds", "minutes", "hours", "days"));
        startProps.add("label", stringSchema());
        require(start, "amount");
        tools.add(new ModelToolDefinition(START_TOOL_ID,
                "Start a real non-blocking timer. Returns immediately; the timer keeps running while the model uses other tools. Use timer.status later when remaining time matters.",
                start, List.of("automation_mode_enabled"), java.util.Set.of(), true,
                Duration.ofSeconds(2), false, false, java.util.Set.of("completed", "failed"),
                ToolExecutionPolicy.validateOnlyMutation(ToolExecutionPolicy.CostClass.CHEAP)));

        JsonObject status = selectorSchema();
        tools.add(new ModelToolDefinition(STATUS_TOOL_ID,
                "Read remaining/elapsed time for a timer without waiting. Omit timerId/label to inspect the most recently started timer.",
                status, List.of("automation_mode_enabled"), java.util.Set.of(), false, Duration.ofSeconds(2), false, false,
                java.util.Set.of("completed", "failed"), ToolExecutionPolicy.readOnly(ToolExecutionPolicy.FreshnessMode.LIVE, ToolExecutionPolicy.CostClass.CHEAP)));

        tools.add(new ModelToolDefinition(LIST_TOOL_ID,
                "List current session timers and whether each is active, expired, or cancelled.",
                object(), List.of("automation_mode_enabled"), java.util.Set.of(), false, Duration.ofSeconds(2), false, false,
                java.util.Set.of("completed", "failed"), ToolExecutionPolicy.readOnly(ToolExecutionPolicy.FreshnessMode.LIVE, ToolExecutionPolicy.CostClass.CHEAP)));

        tools.add(new ModelToolDefinition(CANCEL_TOOL_ID,
                "Cancel a running timer. Omit timerId/label to cancel the most recently started timer.",
                selectorSchema(), List.of("automation_mode_enabled"), java.util.Set.of(), true,
                Duration.ofSeconds(2), false, false, java.util.Set.of("completed", "failed"),
                ToolExecutionPolicy.validateOnlyMutation(ToolExecutionPolicy.CostClass.CHEAP)));
        return List.copyOf(tools);
    }

    private static JsonObject selectorSchema() {
        JsonObject schema = object();
        JsonObject properties = props(schema);
        properties.add("timerId", stringSchema());
        properties.add("label", stringSchema());
        return schema;
    }

    private static JsonObject object() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        schema.add("properties", new JsonObject());
        return schema;
    }
    private static JsonObject props(JsonObject schema) { return schema.getAsJsonObject("properties"); }
    private static JsonObject stringSchema() { JsonObject value = new JsonObject(); value.addProperty("type", "string"); return value; }
    private static JsonObject numberSchema() { JsonObject value = new JsonObject(); value.addProperty("type", "number"); value.addProperty("exclusiveMinimum", 0); return value; }
    private static JsonObject enumSchema(String... values) { JsonObject value = stringSchema(); JsonArray allowed = new JsonArray(); for (String item : values) allowed.add(item); value.add("enum", allowed); return value; }
    private static void require(JsonObject schema, String... names) { JsonArray required = new JsonArray(); for (String name : names) required.add(name); schema.add("required", required); }

    private static String string(JsonObject args, String key, String fallback) {
        return args != null && args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString().strip() : fallback;
    }
    private static double number(JsonObject args, String key, double fallback) {
        return args != null && args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsDouble() : fallback;
    }
    private static ModelToolResult completed(ModelToolCall call, JsonObject output, String detail) { return new ModelToolResult(call.id(), call.toolId(), "completed", output, "", detail); }
    private static ModelToolResult failed(ModelToolCall call, String code, String detail) { return new ModelToolResult(call == null ? "" : call.id(), call == null ? "" : call.toolId(), "failed", new JsonObject(), code, detail == null ? "" : detail); }

    private record TimerState(String id, String label, long startedAtEpochMillis, long deadlineEpochMillis,
                              long startedAtNano, long deadlineNano, long durationMillis,
                              boolean cancelled, long cancelledAtEpochMillis) {}
}
