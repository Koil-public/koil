package com.spirit.koil.api.automation;

public final class AutomationRuntimeStatus {
    private static volatile boolean planning;
    private static volatile boolean running;
    private static volatile String state = "idle";
    private static volatile String detail = "";

    private AutomationRuntimeStatus() {
    }

    public static void planning(String detailValue) {
        planning = true;
        running = false;
        state = "planning";
        detail = detailValue == null ? "" : detailValue;
    }

    public static void running(String detailValue) {
        planning = false;
        running = true;
        state = "running";
        detail = detailValue == null ? "" : detailValue;
    }

    public static void idle(String detailValue) {
        planning = false;
        running = false;
        state = "idle";
        detail = detailValue == null ? "" : detailValue;
    }

    public static void canceled(String detailValue) {
        planning = false;
        running = false;
        state = "canceled";
        detail = detailValue == null ? "" : detailValue;
    }

    public static void failed(String detailValue) {
        planning = false;
        running = false;
        state = "failed";
        detail = detailValue == null ? "" : detailValue;
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
}
