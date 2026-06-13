package com.spirit.koil.api.automation.cli;

import com.spirit.koil.api.console.ConsoleLevel;
import com.spirit.koil.api.console.ConsoleTheme;

import java.util.Locale;

public final class AutomationStateColors {
    private AutomationStateColors() {
    }

    public static int color(String state) {
        return switch (normalizeState(state)) {
            case "failed" -> ConsoleTheme.levelColor(ConsoleLevel.ERROR);
            case "blocked" -> ConsoleTheme.levelColor(ConsoleLevel.WARN);
            case "complete" -> ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
            case "moving", "running", "using" -> ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
            case "attacking" -> ConsoleTheme.levelColor(ConsoleLevel.ERROR);
            case "mining" -> ConsoleTheme.levelColor(ConsoleLevel.WARN);
            case "waiting", "thinking", "header" -> ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "idle" -> ConsoleTheme.secondaryText();
            default -> ConsoleTheme.levelColor(ConsoleLevel.INFO);
        };
    }

    public static String normalizeState(String state) {
        if (state == null || state.isBlank()) {
            return "idle";
        }
        String value = state.trim().toLowerCase(Locale.ROOT);
        String searchable = value
                .replace('[', ' ')
                .replace(']', ' ')
                .replace('_', ' ')
                .replace('-', ' ')
                .replace('.', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (searchable.isBlank()) {
            return "idle";
        }
        if ("idle".equals(searchable)) {
            return "idle";
        }
        if ("header".equals(searchable)) {
            return "header";
        }
        if (searchable.contains("fail") || searchable.contains("error") || searchable.contains("crash")) {
            return "failed";
        }
        if (searchable.contains("block") || searchable.contains("warn") || searchable.contains("missing")
                || searchable.contains("unresolved") || searchable.contains("denied")) {
            return "blocked";
        }
        if (searchable.contains("complete") || searchable.contains("success") || searchable.equals("done")
                || searchable.equals("ok")) {
            return "complete";
        }
        if (searchable.contains("attack") || searchable.contains("combat") || searchable.contains("kill")) {
            return "attacking";
        }
        if (searchable.contains("mine") || searchable.contains("dig") || searchable.contains("break block")) {
            return "mining";
        }
        if (searchable.contains("use") || searchable.contains("interact") || searchable.contains("right click")
                || searchable.contains("eat") || searchable.contains("consume")) {
            return "using";
        }
        if (searchable.contains("move") || searchable.contains("run") || searchable.contains("walk")
                || searchable.contains("path") || searchable.contains("navigate") || searchable.contains("route")
                || searchable.contains("steer") || searchable.contains("execute") || searchable.contains("primitive")) {
            return "running";
        }
        if (searchable.contains("wait") || searchable.contains("queue") || searchable.contains("input")) {
            return "waiting";
        }
        if (searchable.contains("think") || searchable.contains("plan") || searchable.contains("parse")
                || searchable.contains("resolve") || searchable.contains("pipeline") || searchable.contains("scan")
                || searchable.contains("search") || searchable.contains("prompt")) {
            return "thinking";
        }
        return "thinking";
    }
}
