package com.spirit.koil.api.chat.input;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
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
                if (!isExecutableParse(parse)) {
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
        // Non-editor consumers (for example echoed command feedback) have no
        // active cursor, so they keep syntax colours without an error-red span.
        return highlightLine(client, line, -1);
    }

    public static List<StyledChunk> highlightLine(MinecraftClient client, String line, int activeCursor) {
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
            CommandContextBuilder<CommandSource> commandContext = parse.getContext();
            while (commandContext != null) {
                for (Object value : commandContext.getArguments().values()) {
                    if (!(value instanceof ParsedArgument<?, ?> argument) || argument.getRange() == null) {
                        continue;
                    }
                    colorIndex = (colorIndex + 1) % VANILLA_HIGHLIGHT_COLORS.length;
                    int start = Math.max(1, 1 + argument.getRange().getStart());
                    int end = Math.max(start, Math.min(line.length(), 1 + argument.getRange().getEnd()));
                    spans.add(new StyleSpan(start, end, Style.EMPTY, colorForToken(line.substring(start, end), colorIndex)));
                }
                commandContext = commandContext.getChild();
            }
            Analysis diagnostic = isExecutableParse(parse)
                    ? null
                    : failureAnalysis(commandText(line), line, 0, parse, false);
            TokenRange activeToken = activeToken(line, activeCursor);
            TokenRange failureToken = failureToken(line, parse);
            if (diagnostic != null
                    && diagnostic.status() != null
                    && parse.getReader().canRead()
                    && activeToken != null
                    && failureToken != null
                    && activeToken.overlaps(failureToken)) {
                spans.add(new StyleSpan(
                        activeToken.start(),
                        activeToken.end(),
                        Style.EMPTY,
                        diagnostic.status().highlightColor()
                ));
            }
            return styledChunksFromSpans(line, spans);
        } catch (Exception ignored) {
            return fallbackChunks(line);
        }
    }

    /** Returns a visible slice while retaining parse/style context from the
     * complete command. This keeps highlighting stable when the leading slash
     * has scrolled out of a one-line text field. */
    public static List<StyledChunk> highlightRange(MinecraftClient client, String line, int from, int to, int activeCursor) {
        if (line == null || line.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, Math.min(line.length(), from));
        int end = Math.max(start, Math.min(line.length(), to));
        if (start >= end) {
            return List.of();
        }
        List<StyledChunk> full = highlightLine(client, line, activeCursor);
        List<StyledChunk> sliced = new ArrayList<>();
        int offset = 0;
        for (StyledChunk chunk : full) {
            if (chunk == null || chunk.text() == null || chunk.text().isEmpty()) {
                continue;
            }
            int chunkStart = offset;
            int chunkEnd = offset + chunk.text().length();
            int visibleStart = Math.max(start, chunkStart);
            int visibleEnd = Math.min(end, chunkEnd);
            if (visibleStart < visibleEnd) {
                sliced.add(new StyledChunk(
                        chunk.text().substring(visibleStart - chunkStart, visibleEnd - chunkStart),
                        chunk.style(),
                        chunk.color()
                ));
            }
            offset = chunkEnd;
            if (offset >= end) {
                break;
            }
        }
        return List.copyOf(sliced);
    }

    private static Analysis failureAnalysis(String commandText, String commandLine, int lineNumber, ParseResults<CommandSource> parse, boolean allowSuggestions) {
        CommandSyntaxException exception = mostRelevantException(parse);
        int cursor = failureCursor(parse, exception);
        String label = lineNumber > 0 ? "L" + lineNumber + ": " : "";
        int tokenStart = tokenStart(commandLine, cursor);
        int tokenEnd = tokenEnd(commandLine, cursor);
        String token = tokenStart >= 0 && tokenStart < tokenEnd && tokenEnd <= commandLine.length()
                ? commandLine.substring(tokenStart, tokenEnd)
                : "";
        String summary = humanizeProblem(token, parse, exception);
        List<String> details = new ArrayList<>();
        String rawProblem = rawProblem(exception);
        if (!token.isBlank()) {
            details.add("Token: \"" + token + "\"");
        }
        details.add("Position: column " + Math.max(1, cursor + 2));
        if (!rawProblem.isBlank() && !summary.equalsIgnoreCase(rawProblem)) {
            details.add("Minecraft: " + rawProblem);
        }
        List<String> suggestions = allowSuggestions ? suggestions(commandText, parse) : List.of();
        if (!suggestions.isEmpty()) {
            details.add("Valid here: " + String.join(", ", suggestions));
        } else if (looksLikeIncompleteCommand(exception, parse)) {
            details.add("Next step: add the required argument or subcommand after the last valid token.");
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

    private static boolean isExecutableParse(ParseResults<CommandSource> parse) {
        if (parse == null || parse.getReader().canRead() || parse.getContext() == null) {
            return false;
        }
        CommandContextBuilder<CommandSource> context = parse.getContext();
        while (context.getChild() != null) {
            context = context.getChild();
        }
        return context.getCommand() != null;
    }

    private static String humanizeProblem(String token, ParseResults<CommandSource> parse, CommandSyntaxException exception) {
        String raw = rawProblem(exception);
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("unknown command")) {
            return token.isBlank() ? "Unknown command" : "Minecraft does not recognize \"" + token + "\" here";
        }
        if (lower.contains("literal incorrect") || lower.contains("incorrect argument")) {
            return token.isBlank() ? "Unexpected argument" : "\"" + token + "\" is not valid at this position";
        }
        if (lower.contains("integer") || lower.contains("number") || lower.contains("double") || lower.contains("float")) {
            return token.isBlank() ? "A numeric value is required" : "\"" + token + "\" is not a valid number";
        }
        if (looksLikeIncompleteCommand(exception, parse) && (parse == null || !parse.getReader().canRead())) {
            return token.isBlank() ? "Command is incomplete" : "Command is incomplete after \"" + token + "\"";
        }
        if (lower.contains("expected")) {
            return token.isBlank() ? "A required value is missing" : "Minecraft expected a different value at \"" + token + "\"";
        }
        if (parse != null && parse.getReader().canRead()) {
            return token.isBlank() ? "Unexpected text after the last valid argument" : "Unexpected text \"" + token + "\"";
        }
        return raw.isBlank() ? (token.isBlank() ? "Command syntax error" : "Command failed near \"" + token + "\"") : raw;
    }

    private static CommandSyntaxException mostRelevantException(ParseResults<CommandSource> parse) {
        if (parse == null || parse.getExceptions().isEmpty()) {
            return null;
        }
        return parse.getExceptions().values().stream()
                .max(Comparator.comparingInt(exception -> Math.max(-1, exception == null ? -1 : exception.getCursor())))
                .orElse(null);
    }

    private static int failureCursor(ParseResults<CommandSource> parse, CommandSyntaxException exception) {
        int readerCursor = parse == null || parse.getReader() == null ? 0 : Math.max(0, parse.getReader().getCursor());
        int exceptionCursor = exception == null ? -1 : exception.getCursor();
        return Math.max(readerCursor, exceptionCursor);
    }

    private static String rawProblem(CommandSyntaxException exception) {
        if (exception == null || exception.getRawMessage() == null) {
            return "";
        }
        String raw = exception.getRawMessage().getString();
        return raw == null ? "" : raw.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static TokenRange failureToken(String commandLine, ParseResults<CommandSource> parse) {
        if (commandLine == null || commandLine.isEmpty() || parse == null || !parse.getReader().canRead()) {
            return null;
        }
        int cursor = failureCursor(parse, mostRelevantException(parse));
        return new TokenRange(tokenStart(commandLine, cursor), tokenEnd(commandLine, cursor));
    }

    private static TokenRange activeToken(String line, int cursor) {
        if (line == null || line.length() <= 1 || cursor <= 1) {
            return null;
        }
        int safeCursor = Math.max(1, Math.min(line.length(), cursor));
        if (Character.isWhitespace(line.charAt(safeCursor - 1))) {
            return null;
        }
        int start = safeCursor - 1;
        while (start > 1 && !Character.isWhitespace(line.charAt(start - 1))) {
            start--;
        }
        int end = safeCursor;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
            end++;
        }
        return new TokenRange(start, end);
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

    private record TokenRange(int start, int end) {
        private boolean overlaps(TokenRange other) {
            return other != null && start < other.end && other.start < end;
        }
    }
}
