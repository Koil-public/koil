package com.spirit.koil.api.automation.cli;

import net.minecraft.text.Text;

import java.util.List;

public final class AutomationChatHudState {
    private static Text header = Text.empty();
    private static Text prompt = Text.empty();
    private static Text active = Text.empty();
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

    public static synchronized List<Action> actions() {
        return actions;
    }

    public static synchronized long updatedAt() {
        return updatedAt;
    }

    public static synchronized String state() {
        return state;
    }

    public record Action(String id, String label, String command, String value, String kind) {
        public Action {
            id = id == null ? "" : id;
            label = label == null ? "" : label;
            command = command == null ? "" : command;
            value = value == null ? "" : value;
            kind = kind == null ? "" : kind;
        }
    }
}
