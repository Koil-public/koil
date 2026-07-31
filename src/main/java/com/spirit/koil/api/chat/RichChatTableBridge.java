package com.spirit.koil.api.chat;

import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Converts compact Markdown tables into fixed-row Rich Chat markers. The
 * marker rows let ChatHud reserve exact vertical space while the renderer
 * paints one continuous table surface using the content-preview table design.
 */
public final class RichChatTableBridge {
    public static final char MARKER_START = '\uE370';
    public static final char MARKER_END = '\uE371';
    public static final char SPACER_MARKER = '\uE372';
    private static final int MAX_TABLES = 128;
    private static final int MAX_ROWS = 16;
    private static final int MAX_COLUMNS = 8;
    private static final Pattern TABLE_RULE = Pattern.compile(
            "^\\s*\\|?(?:\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?\\s*$"
    );
    private static final Map<String, TableBlock> TABLES = new LinkedHashMap<>(MAX_TABLES, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TableBlock> eldest) {
            return size() > MAX_TABLES;
        }
    };

    private RichChatTableBridge() {
    }

    public static Text rewrite(Text message) {
        if (message == null) {
            return null;
        }
        String visible = message.getString();
        if (visible == null || visible.isBlank() || visible.indexOf('|') < 0) {
            return message;
        }
        String[] lines = visible.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> output = new ArrayList<>();
        boolean changed = false;
        for (int index = 0; index < lines.length; index++) {
            PrefixBody header = splitPrefix(lines[index]);
            if (index + 1 >= lines.length
                    || !header.body().contains("|")
                    || !TABLE_RULE.matcher(splitPrefix(lines[index + 1]).body().trim()).matches()) {
                output.add(lines[index]);
                continue;
            }

            List<List<String>> rows = new ArrayList<>();
            int cursor = index;
            while (cursor < lines.length && rows.size() < MAX_ROWS) {
                PrefixBody candidate = splitPrefix(lines[cursor]);
                String body = candidate.body().trim();
                if (!body.contains("|")) {
                    break;
                }
                if (!TABLE_RULE.matcher(body).matches()) {
                    rows.add(limitColumns(splitRow(body)));
                }
                cursor++;
            }
            if (rows.isEmpty()) {
                output.add(lines[index]);
                continue;
            }

            String id = UUID.randomUUID().toString();
            String firstPrefix = header.prefix();
            String continuationPrefix = firstPrefix.isBlank()
                    ? ""
                    : LocalMultilineChatBridge.indentForPrefix(firstPrefix);
            synchronized (TABLES) {
                TABLES.put(id, new TableBlock(id, List.copyOf(rows), true, firstPrefix, continuationPrefix));
            }
            for (int row = 0; row < rows.size(); row++) {
                String prefix = row == 0 ? firstPrefix : continuationPrefix;
                output.add(prefix + MARKER_START + "TABLE:" + id + ":" + row + MARKER_END);
            }
            output.add(continuationPrefix + SPACER_MARKER);
            changed = true;
            index = cursor - 1;
        }
        return changed ? Text.literal(String.join("\n", output)) : message;
    }

    public static boolean containsMarker(String text) {
        return text != null && (text.indexOf(MARKER_START) >= 0 || text.indexOf(SPACER_MARKER) >= 0);
    }

    public static Marker nextMarker(String text, int fromIndex) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf(MARKER_START, Math.max(0, fromIndex));
        if (start < 0) {
            return null;
        }
        int end = text.indexOf(MARKER_END, start + 1);
        if (end < 0) {
            return null;
        }
        String payload = text.substring(start + 1, end);
        if (!payload.startsWith("TABLE:")) {
            return null;
        }
        String[] parts = payload.substring("TABLE:".length()).split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new Marker(start, end + 1, parts[0], Integer.parseInt(parts[1]));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static TableBlock table(Marker marker) {
        if (marker == null) {
            return null;
        }
        synchronized (TABLES) {
            return TABLES.get(marker.tableId());
        }
    }

    public static String logFriendlyText(String visible) {
        if (visible == null || visible.isBlank() || visible.indexOf(MARKER_START) < 0) {
            return visible == null ? "" : visible;
        }
        StringBuilder output = new StringBuilder();
        List<String> emitted = new ArrayList<>();
        String[] lines = visible.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String line : lines) {
            String stripped = line.stripLeading();
            if (stripped.length() == 1 && stripped.charAt(0) == SPACER_MARKER) {
                continue;
            }
            String prefix = RichChatBodyWrapFormatter.detectVisibleBodyPrefix(line);
            String markerBody = prefix.isEmpty() ? line : line.substring(prefix.length());
            Marker marker = nextMarker(markerBody, 0);
            TableBlock table = table(marker);
            if (marker == null || table == null || emitted.contains(table.id())) {
                if (marker == null) {
                    appendLine(output, line);
                }
                continue;
            }
            emitted.add(table.id());
            for (int row = 0; row < table.rows().size(); row++) {
                appendLine(
                        output,
                        (row == 0 ? table.firstVisiblePrefix() : table.continuationVisiblePrefix())
                                + "| " + String.join(" | ", table.rows().get(row)) + " |"
                );
                if (row == 0) {
                    appendLine(
                            output,
                            table.continuationVisiblePrefix()
                                    + "|" + " --- |".repeat(Math.max(1, table.rows().get(0).size()))
                    );
                }
            }
        }
        return output.toString();
    }

    private static void appendLine(StringBuilder output, String line) {
        if (!output.isEmpty()) {
            output.append('\n');
        }
        output.append(line == null ? "" : line);
    }

    private static PrefixBody splitPrefix(String line) {
        String safe = line == null ? "" : line;
        String prefix = RichChatBodyWrapFormatter.detectVisibleBodyPrefix(safe);
        return prefix.isEmpty()
                ? new PrefixBody("", safe)
                : new PrefixBody(prefix, safe.substring(prefix.length()));
    }

    private static List<String> splitRow(String row) {
        String value = row == null ? "" : row.trim();
        if (value.startsWith("|")) {
            value = value.substring(1);
        }
        if (value.endsWith("|")) {
            value = value.substring(0, value.length() - 1);
        }
        String[] cells = value.split("\\|", -1);
        List<String> result = new ArrayList<>();
        for (String cell : cells) {
            result.add(cell.strip());
        }
        return result;
    }

    private static List<String> limitColumns(List<String> row) {
        if (row.size() <= MAX_COLUMNS) {
            return List.copyOf(row);
        }
        List<String> bounded = new ArrayList<>(row.subList(0, MAX_COLUMNS));
        bounded.set(MAX_COLUMNS - 1, bounded.get(MAX_COLUMNS - 1) + " …");
        return List.copyOf(bounded);
    }

    public record Marker(int start, int end, String tableId, int row) {
    }

    public record TableBlock(
            String id,
            List<List<String>> rows,
            boolean hasHeader,
            String firstVisiblePrefix,
            String continuationVisiblePrefix
    ) {
        public TableBlock {
            rows = rows == null ? List.of() : List.copyOf(rows);
            firstVisiblePrefix = firstVisiblePrefix == null ? "" : firstVisiblePrefix;
            continuationVisiblePrefix = continuationVisiblePrefix == null ? "" : continuationVisiblePrefix;
        }
    }

    private record PrefixBody(String prefix, String body) {
    }
}
