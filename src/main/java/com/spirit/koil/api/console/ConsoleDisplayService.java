package com.spirit.koil.api.console;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConsoleDisplayService {
    private static final Pattern LOG_PATTERN = Pattern.compile("^(\\[[\\-\\=\\*~>&? ]\\]) \\| \\[(\\d{2}:\\d{2}:\\d{2})\\] \\[(.+?)(?:/(.+?))?\\]:\\s?(.*)$");

    private ConsoleDisplayService() {
    }

    public static List<ConsoleStyledLine> snapshot(ConsoleChannel channel) {
        List<ConsoleStyledLine> lines = new ArrayList<>();
        for (ConsoleRecord record : ConsoleRepository.getInstance().snapshot(channel)) {
            lines.add(ConsoleFormatter.style(record));
        }
        return lines;
    }

    public static List<ConsoleStyledLine> readStyledLog(Path path, ConsoleChannel channel) {
        List<ConsoleStyledLine> lines = new ArrayList<>();
        if (!Files.exists(path)) {
            return lines;
        }
        try {
            List<String> rawLines = Files.readAllLines(path, StandardCharsets.UTF_8);
            long sequence = 0L;
            for (String rawLine : rawLines) {
                if (rawLine == null || rawLine.isBlank() || rawLine.startsWith("=") || rawLine.startsWith("    ██")) {
                    continue;
                }
                lines.add(ConsoleFormatter.style(parseLogLine(++sequence, channel, rawLine)));
            }
        } catch (IOException ignored) {
        }
        return lines;
    }

    public static ConsoleLogReadResult readStyledLogDelta(Path path, ConsoleChannel channel, long startOffset, long sequenceBase) {
        List<ConsoleStyledLine> lines = new ArrayList<>();
        if (!Files.exists(path)) {
            return new ConsoleLogReadResult(lines, 0L, sequenceBase);
        }
        long sequence = sequenceBase;
        long offset = startOffset;
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long length = file.length();
            if (startOffset < 0L || startOffset > length) {
                startOffset = 0L;
            }
            file.seek(startOffset);
            offset = startOffset;
            String rawLine;
            while ((rawLine = file.readLine()) != null) {
                offset = file.getFilePointer();
                String decoded = new String(rawLine.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                if (decoded.isBlank() || decoded.startsWith("=") || decoded.startsWith("    ██")) {
                    continue;
                }
                lines.add(ConsoleFormatter.style(parseLogLine(++sequence, channel, decoded)));
            }
            return new ConsoleLogReadResult(lines, offset, sequence);
        } catch (IOException ignored) {
            return new ConsoleLogReadResult(lines, startOffset, sequenceBase);
        }
    }

    public static List<ConsoleStyledLine> filter(List<ConsoleStyledLine> lines, String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return lines;
        }
        List<ConsoleStyledLine> filtered = new ArrayList<>();
        for (ConsoleStyledLine line : lines) {
            if (line.plainText().toLowerCase(Locale.ROOT).contains(normalized)) {
                filtered.add(line);
            }
        }
        return filtered;
    }

    private static ConsoleRecord parseLogLine(long sequence, ConsoleChannel channel, String rawLine) {
        Matcher matcher = LOG_PATTERN.matcher(rawLine);
        if (!matcher.matches()) {
            return new ConsoleRecord(sequence, channel, ConsoleLevel.PLAIN, "", "", "", rawLine, rawLine);
        }
        return new ConsoleRecord(
                sequence,
                channel,
                markerLevel(matcher.group(1)),
                matcher.group(2),
                matcher.group(3) == null ? "" : matcher.group(3),
                matcher.group(4) == null ? "" : matcher.group(4),
                matcher.group(5) == null ? "" : matcher.group(5),
                rawLine
        );
    }

    private static ConsoleLevel markerLevel(String marker) {
        return switch (marker) {
            case "[-]" -> ConsoleLevel.INFO;
            case "[=]" -> ConsoleLevel.WARN;
            case "[*]" -> ConsoleLevel.ERROR;
            case "[~]" -> ConsoleLevel.FATAL;
            case "[>]" -> ConsoleLevel.DEBUG;
            case "[&]" -> ConsoleLevel.UPDATE;
            case "[?]" -> ConsoleLevel.OTHER;
            default -> ConsoleLevel.PLAIN;
        };
    }

    public record ConsoleLogReadResult(List<ConsoleStyledLine> lines, long offset, long sequence) {
    }
}
