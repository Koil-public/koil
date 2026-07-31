package com.spirit.koil.api.model.format;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RichChatModelOutputSanitizer {
    public static final int MAXIMUM_OUTPUT_CHARACTERS = 16_384;
    private static final Pattern MASKED_LINK = Pattern.compile("\\[([^\\]\\n]{1,256})]\\(([^)\\n]{1,2048})\\)");
    private static final String[] INLINE_MARKERS = {"***", "**", "__", "--", "||", "`", "*"};

    private RichChatModelOutputSanitizer() {
    }

    public static Result sanitize(String input) {
        String normalized = input == null ? "" : input.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder cleaned = new StringBuilder(Math.min(normalized.length(), MAXIMUM_OUTPUT_CHARACTERS));
        boolean changed = false;
        for (int index = 0; index < normalized.length() && cleaned.length() < MAXIMUM_OUTPUT_CHARACTERS; index++) {
            char value = normalized.charAt(index);
            if (value == '\n' || value == '\t' || !Character.isISOControl(value)) {
                cleaned.append(value);
            } else {
                changed = true;
            }
        }
        if (normalized.length() > MAXIMUM_OUTPUT_CHARACTERS) {
            changed = true;
        }
        String value = sanitizeMaskedLinks(cleaned.toString());
        changed |= !value.equals(cleaned.toString());
        String softWrapped = normalizeSoftLineBreaks(value);
        changed |= !softWrapped.equals(value);
        value = compactChatSpacing(softWrapped);
        changed |= !value.equals(softWrapped);
        String structural = unwrapStructuralFences(value);
        changed |= !structural.equals(value);
        value = structural;
        value = closeFence(value, "```");
        for (String marker : INLINE_MARKERS) {
            String corrected = neutralizeTrailingUnmatched(value, marker);
            changed |= !corrected.equals(value);
            value = corrected;
        }
        value = closeDelimited(value, "\\(", "\\)");
        value = closeDelimited(value, "\\[", "\\]");
        value = closeUnescapedDollar(value);
        return new Result(value.strip(), changed);
    }

    /**
     * Applies only transformations that are safe while a response is still
     * visible in the generation panel. Incomplete fences remain untouched;
     * complete table/command-link fences may already become live Rich Chat.
     */
    public static String normalizeStreamingPreview(String input) {
        return unwrapStructuralFences(compactChatSpacing(normalizeSoftLineBreaks(input)));
    }

    /**
     * Removes model-authored hard wraps from prose while retaining semantic
     * Rich Chat structure. Width wrapping remains the responsibility of the
     * shared chat/panel layout and therefore reacts to the player's chat width.
     */
    public static String normalizeSoftLineBreaks(String input) {
        String normalized = input == null ? "" : input.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.indexOf('\n') < 0) {
            return normalized;
        }
        String[] lines = normalized.split("\n", -1);
        StringBuilder output = new StringBuilder(normalized.length());
        boolean fenced = false;
        boolean latexBlock = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String stripped = line.stripLeading();
            boolean fenceBoundary = stripped.startsWith("```");
            boolean latexBoundary = stripped.equals("$$") || stripped.equals("\\[") || stripped.equals("\\]");
            boolean preserve = fenced
                    || latexBlock
                    || fenceBoundary
                    || latexBoundary
                    || structuralLine(line)
                    || index == 0
                    || lines[index - 1].isBlank()
                    || line.isBlank()
                    || structuralLine(lines[index - 1])
                    || lines[index - 1].stripTrailing().endsWith("  ");
            if (index > 0) {
                if (preserve) {
                    output.append('\n');
                } else if (!output.isEmpty() && !Character.isWhitespace(output.charAt(output.length() - 1))) {
                    output.append(' ');
                }
            }
            output.append(preserve || index == 0 ? line : stripped);
            if (fenceBoundary) {
                fenced = !fenced;
            }
            if (!fenced && latexBoundary) {
                if (stripped.equals("\\[")) {
                    latexBlock = true;
                } else if (stripped.equals("\\]")) {
                    latexBlock = false;
                } else if (stripped.equals("$$")) {
                    latexBlock = !latexBlock;
                }
            }
        }
        return output.toString();
    }

    /**
     * Keeps model output dense enough for Minecraft chat. Blank presentation
     * rows outside fenced code are removed; semantic structures already carry
     * their own line and height contracts.
     */
    public static String compactChatSpacing(String input) {
        String normalized = input == null ? "" : input.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.indexOf('\n') < 0) {
            return normalized;
        }
        String[] lines = normalized.split("\n", -1);
        List<String> output = new ArrayList<>(lines.length);
        boolean fenced = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String stripped = line.stripLeading();
            boolean boundary = stripped.startsWith("```");
            if (fenced || boundary) {
                output.add(line);
                if (boundary) {
                    fenced = !fenced;
                }
                continue;
            }
            if (line.isBlank()) {
                continue;
            }
            output.add(line);
        }
        return String.join("\n", output);
    }

    /**
     * Small models sometimes wrap a Markdown grid or a masked command link in
     * a code fence. Those constructs cannot become their interactive Rich
     * Chat representation while fenced, so complete structural-only fences
     * are safely unwrapped. Actual source/command code remains fenced.
     */
    public static String unwrapStructuralFences(String input) {
        String normalized = input == null ? "" : input.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.indexOf("```") < 0) {
            return normalized;
        }
        String[] lines = normalized.split("\n", -1);
        List<String> output = new ArrayList<>(lines.length);
        for (int index = 0; index < lines.length; index++) {
            String opening = lines[index];
            if (!opening.stripLeading().startsWith("```")) {
                output.add(opening);
                continue;
            }
            int closing = -1;
            for (int probe = index + 1; probe < lines.length; probe++) {
                if (lines[probe].stripLeading().startsWith("```")) {
                    closing = probe;
                    break;
                }
            }
            if (closing < 0) {
                output.add(opening);
                continue;
            }
            List<String> body = List.of(lines).subList(index + 1, closing);
            if (isMarkdownTable(body) || isMaskedCommandBlock(body)) {
                output.addAll(body);
            } else {
                output.add(opening);
                output.addAll(body);
                output.add(lines[closing]);
            }
            index = closing;
        }
        return String.join("\n", output);
    }

    private static boolean isMarkdownTable(List<String> lines) {
        if (lines == null || lines.size() < 2) {
            return false;
        }
        boolean hasDataRow = false;
        boolean hasDivider = false;
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (trimmed.indexOf('|') < 0) {
                return false;
            }
            if (trimmed.matches("\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?")) {
                hasDivider = true;
            } else {
                hasDataRow = true;
            }
        }
        return hasDivider && hasDataRow;
    }

    private static boolean isMaskedCommandBlock(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        boolean found = false;
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (!trimmed.matches("\\[[^\\]\\n]{1,256}]\\((?:/|(?i:command:/))[^)\\n]{1,2048}\\)")) {
                return false;
            }
            found = true;
        }
        return found;
    }

    private static boolean structuralLine(String line) {
        if (line == null || line.isBlank()) {
            return true;
        }
        String stripped = line.stripLeading();
        if (line.length() - stripped.length() >= 4) {
            return true;
        }
        return stripped.matches("#{1,6}\\s+.*")
                || stripped.matches("-#\\s+.*")
                || stripped.matches("[-*+]\\s+.*")
                || stripped.matches("\\d+[.)]\\s+.*")
                || stripped.startsWith("> ")
                || stripped.startsWith("|")
                || stripped.matches(":?-{3,}:?(?:\\s*\\|\\s*:?-{3,}:?)+")
                || stripped.startsWith("```")
                || stripped.equals("$$")
                || stripped.equals("\\[")
                || stripped.equals("\\]");
    }

    private static String sanitizeMaskedLinks(String value) {
        Matcher matcher = MASKED_LINK.matcher(value);
        StringBuffer out = new StringBuffer(value.length());
        while (matcher.find()) {
            String target = matcher.group(2).trim();
            boolean allowed = target.regionMatches(true, 0, "https://", 0, 8)
                    || target.regionMatches(true, 0, "http://", 0, 7)
                    || target.startsWith("/")
                    || target.regionMatches(true, 0, "command:/", 0, "command:/".length());
            String replacement = allowed ? matcher.group() : matcher.group(1) + " (unsupported link)";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String closeFence(String value, String marker) {
        return occurrenceCount(value, marker) % 2 == 0 ? value : value + "\n" + marker;
    }

    private static String neutralizeTrailingUnmatched(String value, String marker) {
        int count = occurrenceCount(value, marker);
        if (count % 2 == 0) {
            return value;
        }
        int last = value.lastIndexOf(marker);
        return last < 0 ? value : value.substring(0, last) + value.substring(last + marker.length());
    }

    private static String closeDelimited(String value, String open, String close) {
        int opens = occurrenceCount(value, open);
        int closes = occurrenceCount(value, close);
        if (opens <= closes) {
            return value;
        }
        return value + close.repeat(opens - closes);
    }

    private static String closeUnescapedDollar(String value) {
        int single = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '$' || escaped(value, index)) {
                continue;
            }
            if (index + 1 < value.length() && value.charAt(index + 1) == '$') {
                index++;
                continue;
            }
            single++;
        }
        int blocks = occurrenceCount(value, "$$");
        String corrected = blocks % 2 == 0 ? value : value + "\n$$";
        return single % 2 == 0 ? corrected : corrected + "$";
    }

    private static boolean escaped(String value, int index) {
        int slashes = 0;
        for (int cursor = index - 1; cursor >= 0 && value.charAt(cursor) == '\\'; cursor--) {
            slashes++;
        }
        return slashes % 2 != 0;
    }

    private static int occurrenceCount(String value, String marker) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(marker, index)) >= 0; index += marker.length()) {
            count++;
        }
        return count;
    }

    public record Result(String text, boolean changed) {
    }
}
