package com.spirit.koil.api.automation.cli;

import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.automation.AutomationRuntimeStatus;
import com.spirit.koil.api.chat.SlidingStatusText;
import com.spirit.koil.api.model.chat.ModelActivityTreeGlyphs;
import com.spirit.koil.api.model.chat.ModelToolCallPresentation;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

public final class AutomationChatHudState {
    private static Text header = Text.empty();
    private static Text prompt = Text.empty();
    private static Text active = Text.empty();
    private static Text tool = Text.empty();
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
        String status = !prompt.getString().isBlank() ? prompt.getString() : active.getString();
        AutomationPresenceState.updateLocal(state, status);
    }

    public static synchronized void hide() {
        header = Text.empty();
        prompt = Text.empty();
        active = Text.empty();
        tool = Text.empty();
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
    public static Text executorStatusLine() {
        AutomationRuntimeStatus.Snapshot executor = AutomationRuntimeStatus.snapshot();
        if (!executor.active()) {
            return Text.empty();
        }
        String semanticState = AutomationStateColors.normalizeState(executor.state());
        String label = titleCase(semanticState);
        int color = AutomationStateColors.color(semanticState) & 0x00FFFFFF;
        return Text.literal("@_: ").formatted(Formatting.DARK_GRAY)
                .append(SlidingStatusText.styled(label, semanticState, color));
    }

    public static String executorSemanticState() {
        AutomationRuntimeStatus.Snapshot executor = AutomationRuntimeStatus.snapshot();
        return executor.active()
                ? AutomationStateColors.normalizeState(executor.state())
                : "idle";
    }

    /** Publishes real model-tool lifecycle data into the Automation surface. */
    public static synchronized void toolStarted(ModelToolCall call) {
        String summary = ModelToolCallPresentation.callSummary(call);
        tool = Text.literal(ModelActivityTreeGlyphs.BRANCH + " ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(summary).formatted(Formatting.GRAY))
                .append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal("ACTIVE").formatted(Formatting.BLUE));
        updatedAt = System.currentTimeMillis();
    }

    public static synchronized void toolFinished(ModelToolCall call, ModelToolResult result) {
        String name = ModelToolCallPresentation.toolName(call == null ? "" : call.toolId());
        String status = result == null ? "FAILED" : result.status().replace('_', ' ').toUpperCase(java.util.Locale.ROOT);
        String detail = result == null ? "no result" : conciseResult(result);
        tool = Text.literal(ModelActivityTreeGlyphs.BRANCH + " ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(name).formatted(Formatting.GRAY))
                .append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(status).styled(style -> style.withColor(AutomationStateColors.color(status))))
                .append(detail.isBlank() ? Text.empty() : Text.literal(" | " + detail).formatted(Formatting.DARK_GRAY));
        updatedAt = System.currentTimeMillis();
    }

    public static synchronized void clearTool() {
        tool = Text.empty();
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
