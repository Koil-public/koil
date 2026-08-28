package com.spirit.koil.api.automation.cli;

import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.automation.AutomationRuntimeStatus;
import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.chat.SlidingStatusText;
import com.spirit.koil.api.model.KoilLifetimeCounters;
import com.spirit.koil.api.model.chat.ModelActivityTreeGlyphs;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;
import com.spirit.koil.api.model.chat.ModelToolActivityPresentation;
import com.spirit.koil.api.model.chat.ModelToolCallPresentation;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

public final class AutomationChatHudState {
    private static final long TOOL_RESULT_VISIBILITY_MILLIS = 6_000L;
    private static Text header = Text.empty();
    private static Text prompt = Text.empty();
    private static Text active = Text.empty();
    private static Text tool = Text.empty();
    private static String activeToolCallId = "";
    private static String activeToolState = "idle";
    private static String activeToolDetail = "";
    private static boolean toolRunning;
    private static boolean toolOnlySession;
    private static boolean visible;
    private static long updatedAt;
    private static String state = "idle";
    private static List<Action> actions = List.of();

    private AutomationChatHudState() {
    }

    public static synchronized void show(Text text, String newState) {
        header = Text.empty();
        prompt = Text.empty();
        active = text == null ? Text.empty() : text;
        actions = List.of();
        visible = true;
        updatedAt = System.currentTimeMillis();
        state = newState == null || newState.isBlank() ? "idle" : newState;
        AutomationPresenceState.updateLocal(state, active.getString());
    }

    public static synchronized void showHeader(Text headerText, Text promptText) {
        showHeader(headerText, promptText, Text.empty(), "header", List.of());
    }

    public static synchronized void showHeader(Text headerText, Text promptText, Text activeText, String newState) {
        showHeader(headerText, promptText, activeText, newState, List.of());
    }

    public static synchronized void showHeader(Text headerText, Text promptText, Text activeText, String newState, List<Action> newActions) {
        header = headerText == null ? Text.empty() : headerText;
        prompt = promptText == null ? Text.empty() : promptText;
        active = activeText == null ? Text.empty() : activeText;
        actions = newActions == null ? List.of() : List.copyOf(newActions);
        visible = true;
        updatedAt = System.currentTimeMillis();
        state = newState == null || newState.isBlank() ? "header" : newState;
        toolOnlySession = false;
        String status = !prompt.getString().isBlank() ? prompt.getString() : active.getString();
        AutomationPresenceState.updateLocal(state, status);
    }

    public static synchronized void hide() {
        header = Text.empty();
        prompt = Text.empty();
        active = Text.empty();
        tool = Text.empty();
        activeToolCallId = "";
        activeToolState = "idle";
        activeToolDetail = "";
        toolRunning = false;
        toolOnlySession = false;
        actions = List.of();
        visible = false;
        state = "idle";
        AutomationPresenceState.updateLocal("idle", "");
    }

    public static synchronized boolean visible() {
        return visible;
    }

    public static synchronized Text header() {
        return header;
    }

    public static synchronized Text prompt() {
        return prompt;
    }

    public static synchronized Text active() {
        return active;
    }

    public static synchronized Text tool() {
        return tool;
    }

    /**
     * One live Executor-only status row. Combined model/executor projection is
     * owned by the top Automation panel and presence underline, not this popup.
     */
    public static synchronized Text executorStatusLine() {
        AutomationRuntimeStatus.Snapshot executor = AutomationRuntimeStatus.snapshot();
        String rawState;
        String rawDetail;
        if (executor.active()) {
            rawState = executor.state();
            rawDetail = executor.detail();
        } else if (toolRunning) {
            rawState = activeToolState;
            rawDetail = activeToolDetail;
        } else {
            return Text.empty();
        }
        String semanticState = AutomationStateColors.normalizeState(rawState);
        String label = titleCase(semanticState);
        String detail = conciseExecutorDetail(rawDetail, label);
        int color = AutomationStateColors.color(semanticState) & 0x00FFFFFF;
        Text line = Text.literal("@_: ").formatted(Formatting.DARK_GRAY)
                .append(SlidingStatusText.styled(label, semanticState, color));
        if (!detail.isBlank()) {
            line = line.copy().append(Text.literal(": " + detail).formatted(Formatting.GRAY));
        }
        return line;
    }

    public static synchronized String executorSemanticState() {
        AutomationRuntimeStatus.Snapshot executor = AutomationRuntimeStatus.snapshot();
        if (executor.active()) return AutomationStateColors.normalizeState(executor.state());
        return toolRunning || visibleOutsideAutomation()
                ? AutomationStateColors.normalizeState(activeToolState)
                : "idle";
    }

    /** Publishes real model-tool lifecycle data into the Automation surface. */
    public static synchronized void toolStarted(ModelToolCall call) {
        if (!AutomationModeController.isAutomationMode() && !toolOnlySession) {
            beginToolOnlySession();
        }
        ModelToolActivityPresentation.Activity activity = ModelToolActivityPresentation.activity(call);
        String summary = ModelToolCallPresentation.callSummary(call);
        activeToolCallId = call == null ? "" : call.id();
        activeToolState = activity.state().id();
        activeToolDetail = activity.detail();
        toolRunning = true;
        visible = true;
        state = "tool_active";
        tool = Text.literal(ModelActivityTreeGlyphs.BRANCH + " ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(summary).formatted(Formatting.GRAY))
                .append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal("ACTIVE").formatted(Formatting.BLUE));
        updatedAt = System.currentTimeMillis();
    }

    public static synchronized void toolFinished(ModelToolCall call, ModelToolResult result) {
        String callId = call == null ? "" : call.id();
        if (!activeToolCallId.isBlank() && !activeToolCallId.equals(callId)) return;
        toolRunning = false;
        activeToolState = result == null ? "failed" : result.status();
        activeToolDetail = "";
        String name = ModelToolCallPresentation.toolName(call == null ? "" : call.toolId());
        String status = result == null ? "FAILED" : result.status().replace('_', ' ').toUpperCase(java.util.Locale.ROOT);
        String detail = result == null ? "no result" : conciseResult(result);
        tool = Text.literal(ModelActivityTreeGlyphs.BRANCH + " ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(name).formatted(Formatting.GRAY))
                .append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(status).styled(style -> style.withColor(AutomationStateColors.color(status))))
                .append(detail.isBlank() ? Text.empty() : Text.literal(" | " + detail).formatted(Formatting.DARK_GRAY));
        updatedAt = System.currentTimeMillis();
        if (toolOnlySession) state = "tool_result";
    }

    public static synchronized boolean visibleOutsideAutomation() {
        if (toolRunning) return true;
        return toolOnlySession && !tool.getString().isBlank()
                && System.currentTimeMillis() - updatedAt <= TOOL_RESULT_VISIBILITY_MILLIS;
    }

    private static void beginToolOnlySession() {
        KoilLifetimeCounters.Snapshot counters = KoilLifetimeCounters.automationSessionStarted();
        String sessionId = String.format("kes-%05d", counters.kes());
        header = Text.literal("Executor").formatted(Formatting.GRAY)
                .append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal("session ").formatted(Formatting.GRAY))
                .append(Text.literal(sessionId).formatted(Formatting.WHITE));
        prompt = Text.empty();
        active = Text.empty();
        actions = List.of();
        toolOnlySession = true;
        visible = true;
        ModelGenerationHudState.refreshLifetimeCounters();
    }

    public static synchronized void clearTool() {
        tool = Text.empty();
        activeToolCallId = "";
        activeToolState = "idle";
        activeToolDetail = "";
        toolRunning = false;
    }

    private static String conciseResult(ModelToolResult result) {
        if (result.output() != null && result.output().has("structuredResult")
                && result.output().get("structuredResult").isJsonObject()) {
            var structured = result.output().getAsJsonObject("structuredResult");
            String reason = structured.has("reason") ? structured.get("reason").getAsString() : "";
            StringBuilder summary = new StringBuilder();
            if (structured.has("metrics") && structured.get("metrics").isJsonObject()) {
                var metrics = structured.getAsJsonObject("metrics");
                appendMetric(summary, metrics, "completed_amount", "done");
                appendMetric(summary, metrics, "remaining_amount", "remaining");
                appendMetric(summary, metrics, "distance_traveled", "traveled");
                appendMetric(summary, metrics, "distance_remaining", "remaining");
            }
            if (!reason.isBlank()) {
                if (!summary.isEmpty()) summary.append(" | ");
                summary.append(reason.replace('_', ' '));
            }
            return compact(summary.toString(), 112);
        }
        String detail = result.detail().isBlank() ? result.failureCode() : result.detail();
        return compact(detail, 112);
    }

    private static void appendMetric(StringBuilder summary, com.google.gson.JsonObject metrics, String key, String label) {
        if (!metrics.has(key) || metrics.get(key).isJsonNull()) return;
        if (!summary.isEmpty()) summary.append(" | ");
        summary.append(label).append(' ').append(metrics.get(key).getAsString());
    }

    private static String compact(String value, int maximum) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return clean.length() <= maximum ? clean : clean.substring(0, maximum - 1) + "…";
    }

    private static String conciseExecutorDetail(String value, String statusLabel) {
        String clean = compact(value == null ? "" : value.replace('_', ' '), 96);
        if (clean.isBlank() || clean.equalsIgnoreCase(statusLabel)) return "";
        return clean;
    }

    public static synchronized List<Action> actions() {
        return actions;
    }

    public static synchronized long updatedAt() {
        return updatedAt;
    }

    public static synchronized String state() {
        return state;
    }

    private static String titleCase(String value) {
        String normalized = value == null || value.isBlank() ? "idle" : value.replace('_', ' ').strip();
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    public record Action(String id, String label, String command, String value, String kind, String hoverDetails) {
        public Action(String id, String label, String command, String value, String kind) {
            this(id, label, command, value, kind, value);
        }

        public Action {
            id = id == null ? "" : id;
            label = label == null ? "" : label;
            command = command == null ? "" : command;
            value = value == null ? "" : value;
            kind = kind == null ? "" : kind;
            hoverDetails = hoverDetails == null ? "" : hoverDetails;
        }
    }
}
