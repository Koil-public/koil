package com.spirit.koil.api.console;

import com.spirit.client.gui.ide.EditorSyntaxHighlighter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConsoleSyntaxStyler {
    private static final Pattern FALLBACK_PATTERN = Pattern.compile(
            "(?<TIMESTAMP>\\b\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\b|\\b\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\b)|" +
                    "(?<STATUS>\\[(?:run\\s|ok\\s\\s|fail|block|warn|done|wait|info|dev\\s|mem\\s|cache|bind)\\])|" +
                    "(?<PATH>\\b[a-z_]+(?:\\.[a-z0-9_:-]+)+\\b)|" +
                    "(?<NAMESPACE>\\b[a-z0-9_]+:[a-z0-9_./-]+\\b)|" +
                    "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?\\b)|" +
                    "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')|" +
                    "(?<ARROW>->|=>|::)|" +
                    "(?<COMMAND>/[a-z0-9_:-]+)|" +
                    "(?<BRACE>[\\[\\]{}()])"
    );

    private ConsoleSyntaxStyler() {
    }

    public static List<ConsoleStyledSpan> style(String sourceName, String message) {
        try {
            List<ConsoleStyledSpan> spans = new ArrayList<>();
            for (EditorSyntaxHighlighter.StyledSpan span : EditorSyntaxHighlighter.highlight(sourceName, message)) {
                spans.add(new ConsoleStyledSpan(span.text(), span.color()));
            }
            return spans;
        } catch (Throwable ignored) {
            return fallback(message);
        }
    }

    private static List<ConsoleStyledSpan> fallback(String message) {
        List<ConsoleStyledSpan> spans = new ArrayList<>();
        Matcher matcher = FALLBACK_PATTERN.matcher(message);
        int index = 0;
        while (matcher.find()) {
            if (matcher.start() > index) {
                spans.add(new ConsoleStyledSpan(message.substring(index, matcher.start()), ConsoleTheme.secondaryText()));
            }
            spans.add(new ConsoleStyledSpan(matcher.group(), colorFor(matcher)));
            index = matcher.end();
        }
        if (index < message.length()) {
            spans.add(new ConsoleStyledSpan(message.substring(index), ConsoleTheme.secondaryText()));
        }
        if (spans.isEmpty()) {
            spans.add(new ConsoleStyledSpan(message, ConsoleTheme.secondaryText()));
        }
        return spans;
    }

    private static int colorFor(Matcher matcher) {
        if (matcher.group("TIMESTAMP") != null) {
            return ConsoleTheme.levelColor(ConsoleLevel.INFO);
        }
        if (matcher.group("STATUS") != null) {
            return ConsoleTheme.primaryText();
        }
        if (matcher.group("PATH") != null || matcher.group("NAMESPACE") != null) {
            return ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
        }
        if (matcher.group("NUMBER") != null) {
            return ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
        }
        if (matcher.group("STRING") != null) {
            return ConsoleTheme.levelColor(ConsoleLevel.WARN);
        }
        if (matcher.group("COMMAND") != null || matcher.group("ARROW") != null) {
            return ConsoleTheme.levelColor(ConsoleLevel.OTHER);
        }
        if (matcher.group("BRACE") != null) {
            return ConsoleTheme.primaryText();
        }
        return ConsoleTheme.secondaryText();
    }
}
