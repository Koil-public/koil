package com.spirit.koil.api.console;

import java.util.ArrayList;
import java.util.List;

public final class ConsoleFormatter {
    private ConsoleFormatter() {
    }

    public static ConsoleStyledLine style(ConsoleRecord record) {
        List<ConsoleStyledSpan> spans = new ArrayList<>();
        append(spans, record.level().marker(), ConsoleTheme.levelColor(record.level()));
        append(spans, " ", ConsoleTheme.secondaryText());
        append(spans, record.timestamp(), ConsoleTheme.secondaryText());
        append(spans, " ", ConsoleTheme.secondaryText());
        append(spans, "[", ConsoleTheme.secondaryText());
        append(spans, record.thread(), ConsoleTheme.primaryText());
        if (!record.category().isBlank()) {
            append(spans, "/", ConsoleTheme.secondaryText());
            append(spans, record.category(), ConsoleTheme.levelColor(record.level()));
        }
        append(spans, "] ", ConsoleTheme.secondaryText());
        appendHighlightedMessage(spans, record.message());
        return new ConsoleStyledLine(record, spans, flatten(spans));
    }

    private static void appendHighlightedMessage(List<ConsoleStyledSpan> spans, String message) {
        for (ConsoleStyledSpan span : ConsoleSyntaxStyler.style("console.log", message)) {
            spans.add(span);
        }
    }

    private static void append(List<ConsoleStyledSpan> spans, String text, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        spans.add(new ConsoleStyledSpan(text, color));
    }

    private static String flatten(List<ConsoleStyledSpan> spans) {
        StringBuilder builder = new StringBuilder();
        for (ConsoleStyledSpan span : spans) {
            builder.append(span.text());
        }
        return builder.toString();
    }
}
