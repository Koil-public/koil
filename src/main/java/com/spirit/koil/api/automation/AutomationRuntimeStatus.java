package com.spirit.koil.api.automation;

public final class AutomationRuntimeStatus {
    private static volatile boolean planning;
    private static volatile boolean running;
    private static volatile String state = "idle";
    private static volatile String detail = "";
    private static volatile long updatedAtMillis = System.currentTimeMillis();

    private AutomationRuntimeStatus() {
    }

    public static void planning(String detailValue) {
        planning = true;
        running = false;
        state = "planning";
        detail = detailValue == null ? "" : detailValue;
        touch();
    }

    public static void running(String detailValue) {
        active("executing", detailValue);
    }

    public static void active(String stateValue, String detailValue) {
        planning = "planning".equals(stateValue) || "replanning".equals(stateValue);
        running = true;
        state = stateValue == null || stateValue.isBlank() ? "executing" : stateValue;
        detail = detailValue == null ? "" : detailValue;
        touch();
    }

    public static void idle(String detailValue) {
        planning = false;
        running = false;
        state = "idle";
        detail = detailValue == null ? "" : detailValue;
        touch();
    }

    public static void canceled(String detailValue) {
        planning = false;
        running = false;
        state = "cancelled";
        detail = detailValue == null ? "" : detailValue;
        touch();
    }

    public static void partial(String detailValue) {
        terminal("partial", detailValue);
    }

    public static void interrupted(String detailValue) {
        terminal("interrupted", detailValue);
    }

    public static void alreadySatisfied(String detailValue) {
        terminal("already_satisfied", detailValue);
    }

    public static void failed(String detailValue) {
        planning = false;
        running = false;
        state = "failed";
        detail = detailValue == null ? "" : detailValue;
        touch();
    }

    public static void completed(String detailValue) {
        planning = false;
        running = false;
        state = "complete";
        detail = detailValue == null ? "" : detailValue;
        touch();
    }

    public static void blocked(String detailValue) {
        terminal("blocked", detailValue);
    }

    private static void terminal(String stateValue, String detailValue) {
        planning = false;
        running = false;
        state = stateValue;
        detail = detailValue == null ? "" : detailValue;
        touch();
    }

    public static boolean isTaskRunning() {
        return planning || running;
    }

    public static boolean isExecutorRunning() {
        return running;
    }

    public static String state() {
        return state;
    }

    public static String detail() {
        return detail;
    }

    public static Snapshot snapshot() {
        return new Snapshot(state, detail, planning || running, updatedAtMillis);
    }

    private static void touch() {
        updatedAtMillis = System.currentTimeMillis();
    }

    public record Snapshot(String state, String detail, boolean active, long updatedAtMillis) {
        public Snapshot {
            state = state == null || state.isBlank() ? "idle" : state;
            detail = detail == null ? "" : detail;
        }

        public boolean visibleAt(long nowMillis) {
            return active || nowMillis - updatedAtMillis <= 8_000L;
        }
    }
}
