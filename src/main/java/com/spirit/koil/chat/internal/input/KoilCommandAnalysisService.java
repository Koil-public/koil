package com.spirit.koil.chat.internal.input;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedArgument;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Style;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class KoilCommandAnalysisService {
    public static final int VANILLA_INFO_COLOR = 0xFFAAAAAA;
    public static final int VANILLA_ERROR_COLOR = 0xFFFF5555;
    public static final int[] VANILLA_HIGHLIGHT_COLORS = new int[] {0xFF55FFFF, 0xFFFFFF55, 0xFF55FF55, 0xFFFF55FF, 0xFFFFAA00};

    private KoilCommandAnalysisService() {
    }

    public static Analysis analyzeDraft(MinecraftClient client, String text, boolean sequentialMultiline, boolean allowSuggestions) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("/")) {
            return null;
        }
        if (client == null || client.getNetworkHandler() == null || client.getNetworkHandler().getCommandDispatcher() == null) {
            return new Analysis(
                    new StatusLine("Command preview unavailable", VANILLA_INFO_COLOR, -1, -1, VANILLA_ERROR_COLOR, 0x44E06A6A),
                    List.of("Command dispatcher is not ready yet.")
            );
        }

        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] commandLines = normalized.split("\n", -1);
        for (int i = 0; i < commandLines.length; i++) {
            String line = commandLines[i] == null ? "" : commandLines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!line.startsWith("/")) {
                if (sequentialMultiline) {
                    return new Analysis(
                            new StatusLine("L" + (i + 1) + ": expected /command", VANILLA_INFO_COLOR, 0, Math.min(2, "L".length() + Integer.toString(i + 1).length()), VANILLA_ERROR_COLOR, 0x44E06A6A),
                            List.of("Each line in a multiline command draft must start with /.")
                    );
                }
                continue;
            }
            try {
                String commandText = commandText(line);
                ParseResults<CommandSource> parse = client.getNetworkHandler().getCommandDispatcher().parse(commandText, client.getNetworkHandler().getCommandSource());
                if (!parse.getExceptions().isEmpty() || parse.getReader().canRead()) {
                    return failureAnalysis(commandText, line, sequentialMultiline ? i + 1 : 0, parse, allowSuggestions && !sequentialMultiline);
                }
            } catch (Exception exception) {
                String message = exception.getMessage() == null ? "Command parse error" : exception.getMessage().replace('\n', ' ').replace('\r', ' ').trim();
                String label = sequentialMultiline ? "L" + (i + 1) + ": " : "";
                return new Analysis(
                        new StatusLine(label + message, VANILLA_INFO_COLOR, 0, Math.min(label.length() + Math.max(1, message.length()), (label + message).length()), VANILLA_ERROR_COLOR, 0x44E06A6A),
                        List.of("Koil could not inspect this command fully.")
                );
            }
        }
        return null;
    }

    public static List<StyledChunk> highlightLine(MinecraftClient client, String line) {
        if (line == null || line.isEmpty()) {
            return List.of();
        }
        if (client == null || client.getNetworkHandler() == null || client.getNetworkHandler().getCommandDispatcher() == null || !line.startsWith("/")) {
            return fallbackChunks(line);
        }
        try {
            ParseResults<CommandSource> parse = client.getNetworkHandler().getCommandDispatcher().parse(commandText(line), client.getNetworkHandler().getCommandSource());
            List<StyleSpan> spans = new ArrayList<>();
            int colorIndex = -1;
            if (parse.getContext() != null && parse.getContext().getLastChild() != null) {
                for (Object value : parse.getContext().getLastChild().getArguments().values()) {
                    if (!(value instanceof ParsedArgument<?, ?> argument) || argument.getRange() == null) {
                        continue;
                    }
                    colorIndex = (colorIndex + 1) % VANILLA_HIGHLIGHT_COLORS.length;
                    int start = Math.max(1, 1 + argument.getRange().getStart());
                    int end = Math.max(start, Math.min(line.length(), 1 + argument.getRange().getEnd()));
                    spans.add(new StyleSpan(start, end, Style.EMPTY, colorForToken(line.substring(start, end), colorIndex)));
                }
            }
            Analysis diagnostic = failureAnalysis(commandText(line), line, 0, parse, false);
            if (diagnostic != null && diagnostic.status() != null && diagnostic.status().highlightStart() >= 0) {
                spans.add(new StyleSpan(
                        Math.max(0, diagnostic.status().highlightStart()),
                        Math.max(Math.max(0, diagnostic.status().highlightStart()), diagnostic.status().highlightEnd()),
                        Style.EMPTY,
                        diagnostic.status().highlightColor()
                ));
            }
            return styledChunksFromSpans(line, spans);
        } catch (Exception ignored) {
            return fallbackChunks(line);
        }
    }

    private static Analysis failureAnalysis(String commandText, String commandLine, int lineNumber, ParseResults<CommandSource> parse, boolean allowSuggestions) {
        CommandSyntaxException exception = parse.getExceptions().isEmpty() ? null : parse.getExceptions().values().iterator().next();
        int cursor = parse.getReader().canRead()
                ? parse.getReader().getCursor()
                : (exception == null ? 0 : Math.max(0, exception.getCursor()));
        String label = lineNumber > 0 ? "L" + lineNumber + ": " : "";
        int tokenStart = tokenStart(commandLine, cursor);
        int tokenEnd = tokenEnd(commandLine, cursor);
        String token = tokenStart >= 0 && tokenStart < tokenEnd && tokenEnd <= commandLine.length()
                ? commandLine.substring(tokenStart, tokenEnd)
                : "";
        String summary = humanizeProblem(token, parse, exception);
        List<String> details = new ArrayList<>();
        if (!token.isBlank()) {
            details.add("Field: " + token);
        }
        details.add("At: " + Math.max(1, cursor + 1));
        List<String> suggestions = allowSuggestions ? suggestions(commandText, parse) : List.of();
        if (!suggestions.isEmpty()) {
            details.add("Try: " + String.join(", ", suggestions));
        } else if (looksLikeIncompleteCommand(exception, parse)) {
            details.add("Try: add the next required argument or value.");
        }

        String visible = label + summary;
        int tokenIndexInSummary = token.isBlank() ? -1 : summary.lastIndexOf(token);
        int highlightStart = Math.max(0, Math.min(visible.length(), label.length() + Math.max(0, tokenIndexInSummary)));
        int highlightEnd = token.isBlank() ? highlightStart : Math.min(visible.length(), highlightStart + token.length());
        StatusLine status;
        if (highlightEnd <= highlightStart) {
            highlightStart = Math.max(0, Math.min(commandLine.length(), tokenStart));
            highlightEnd = Math.max(highlightStart, Math.min(commandLine.length(), tokenEnd));
            String display = label + commandLine;
            status = new StatusLine(display, VANILLA_INFO_COLOR, label.length() + highlightStart, label.length() + highlightEnd, VANILLA_ERROR_COLOR, 0x44E06A6A);
        } else {
            status = new StatusLine(visible, VANILLA_INFO_COLOR, highlightStart, highlightEnd, VANILLA_ERROR_COLOR, 0x44E06A6A);
        }
        return new Analysis(status, List.copyOf(details));
    }

    private static String humanizeProblem(String token, ParseResults<CommandSource> parse, CommandSyntaxException exception) {
        String raw = exception == null || exception.getRawMessage() == null ? "" : exception.getRawMessage().getString();
        String lower = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (looksLikeIncompleteCommand(exception, parse)) {
            return token.isBlank() ? "Missing required argument" : "Missing or incomplete value near " + token;
        }
        if (lower.contains("unknown command")) {
            return token.isBlank() ? "Unknown command" : "Unknown command or argument " + token;
        }
        if (lower.contains("expected")) {
            return token.isBlank() ? "Missing required value" : "Invalid or missing value for " + token;
        }
        if (lower.contains("integer") || lower.contains("number") || lower.contains("double") || lower.contains("float")) {
            return token.isBlank() ? "Invalid number" : "Invalid numeric value " + token;
        }
        if (lower.contains("literal incorrect")) {
            return token.isBlank() ? "Unexpected argument" : "Unexpected argument " + token;
        }
        if (parse != null && parse.getReader().canRead()) {
            return token.isBlank() ? "Unexpected extra text" : "Unexpected extra text: " + token;
        }
        return token.isBlank() ? "Command syntax error" : "Command syntax error near " + token;
    }

    private static boolean looksLikeIncompleteCommand(CommandSyntaxException exception, ParseResults<CommandSource> parse) {
        String raw = exception == null || exception.getRawMessage() == null ? "" : exception.getRawMessage().getString();
        String lower = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        return lower.contains("unknown or incomplete command")
                || lower.contains("expected")
                || (parse != null && !parse.getReader().canRead() && !parse.getExceptions().isEmpty());
    }

    private static int tokenStart(String commandLine, int cursor) {
        int visibleCursor = Math.max(1, Math.min(commandLine.length(), cursor + 1));
        int start = visibleCursor;
        while (start > 1 && !Character.isWhitespace(commandLine.charAt(start - 1))) {
            start--;
        }
        return start;
    }

    private static int tokenEnd(String commandLine, int cursor) {
        int visibleCursor = Math.max(1, Math.min(commandLine.length(), cursor + 1));
        int end = visibleCursor;
        while (end < commandLine.length() && !Character.isWhitespace(commandLine.charAt(end))) {
            end++;
        }
        return end;
    }

    private static List<String> suggestions(String commandText, ParseResults<CommandSource> parse) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null || client.getNetworkHandler().getCommandDispatcher() == null || parse == null) {
            return List.of();
        }
        try {
            Suggestions suggestions = client.getNetworkHandler().getCommandDispatcher().getCompletionSuggestions(parse).join();
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            for (Suggestion suggestion : suggestions.getList()) {
                if (suggestion == null) {
                    continue;
                }
                String applied = suggestion.apply(commandText == null ? "" : commandText);
                if (applied == null || applied.isBlank()) {
                    continue;
                }
                ordered.add("/" + applied.trim());
                if (ordered.size() >= 3) {
                    break;
                }
            }
            return List.copyOf(ordered);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static List<StyledChunk> fallbackChunks(String line) {
        List<StyledChunk> chunks = new ArrayList<>();
        int index = 0;
        while (index < line.length()) {
            char c = line.charAt(index);
            if (Character.isWhitespace(c)) {
                int end = index + 1;
                while (end < line.length() && Character.isWhitespace(line.charAt(end))) {
                    end++;
                }
                chunks.add(new StyledChunk(line.substring(index, end), Style.EMPTY, VANILLA_INFO_COLOR));
                index = end;
                continue;
            }
            int end = index + 1;
            boolean quoted = c == '"' || c == '\'';
            if (quoted) {
                while (end < line.length() && line.charAt(end) != c) {
                    end++;
                }
                if (end < line.length()) {
                    end++;
                }
            } else {
                while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
                    end++;
                }
            }
            String token = line.substring(index, end);
            int color = index == 0 ? VANILLA_INFO_COLOR : colorForToken(token, index);
            chunks.add(new StyledChunk(token, Style.EMPTY, color));
            index = end;
        }
        return chunks;
    }

    private static int colorForToken(String token, int fallbackIndex) {
        if (token == null || token.isEmpty()) {
            return VANILLA_INFO_COLOR;
        }
        if (token.startsWith("@")) {
            return VANILLA_HIGHLIGHT_COLORS[0];
        }
        if (token.startsWith("\"") || token.startsWith("'")) {
            return VANILLA_HIGHLIGHT_COLORS[2];
        }
        if (token.matches("-?\\d+(?:\\.\\d+)?")) {
            return VANILLA_HIGHLIGHT_COLORS[4];
        }
        if (token.contains(":")) {
            return VANILLA_HIGHLIGHT_COLORS[2];
        }
        return VANILLA_HIGHLIGHT_COLORS[Math.floorMod(fallbackIndex, VANILLA_HIGHLIGHT_COLORS.length)];
    }

    private static List<StyledChunk> styledChunksFromSpans(String line, List<StyleSpan> spans) {
        if (line == null || line.isEmpty()) {
            return List.of();
        }
        List<StyleSpan> orderedSpans = new ArrayList<>(spans);
        orderedSpans.sort(Comparator.comparingInt(StyleSpan::start).thenComparingInt(span -> span.end() - span.start()));
        List<StyledChunk> chunks = new ArrayList<>();
        int cursor = 0;
        while (cursor < line.length()) {
            StyleSpan active = null;
            for (StyleSpan span : orderedSpans) {
                if (cursor >= span.start() && cursor < span.end()) {
                    active = span;
                }
            }
            int next = line.length();
            if (active != null) {
                next = active.end();
            } else {
                for (StyleSpan span : orderedSpans) {
                    if (span.start() > cursor) {
                        next = Math.min(next, span.start());
                    }
                }
            }
            if (next <= cursor) {
                next = cursor + 1;
            }
            String piece = line.substring(cursor, Math.min(line.length(), next));
            if (active == null) {
                chunks.add(new StyledChunk(piece, Style.EMPTY, VANILLA_INFO_COLOR));
            } else {
                chunks.add(new StyledChunk(piece, active.style(), active.color()));
            }
            cursor = next;
        }
        return chunks;
    }

    private static String commandText(String commandLine) {
        return commandLine != null && commandLine.startsWith("/") ? commandLine.substring(1) : (commandLine == null ? "" : commandLine);
    }

    public record Analysis(StatusLine status, List<String> details) {
    }

    public record StatusLine(String text, int color, int highlightStart, int highlightEnd, int highlightColor, int accentColor) {
    }

    public record StyledChunk(String text, Style style, int color) {
    }

    private record StyleSpan(int start, int end, Style style, int color) {
    }
}
