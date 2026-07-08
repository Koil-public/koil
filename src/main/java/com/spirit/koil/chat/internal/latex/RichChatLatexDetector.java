package com.spirit.koil.chat.internal.latex;

import com.spirit.koil.api.chat.RichChatMessageType;
import com.spirit.koil.api.chat.RichChatSegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RichChatLatexDetector {
    private RichChatLatexDetector() {
    }

    public static Result detect(String input) {
        String text = input == null ? "" : input.replace("\r\n", "\n").replace('\r', '\n');
        if (text.isBlank()) {
            return Result.empty(text);
        }

        List<Match> matches = new ArrayList<>();
        collectDelimited(matches, text, "```latex", "```", Kind.DOCUMENT);
        collectDelimited(matches, text, "```tex", "```", Kind.DOCUMENT);
        collectDelimited(matches, text, "$$", "$$", Kind.BLOCK);
        collectDelimited(matches, text, "\\[", "\\]", Kind.BLOCK);
        collectDelimited(matches, text, "\\(", "\\)", Kind.INLINE);
        collectInlineDollar(matches, text);
        List<Match> ordered = nonOverlapping(matches);
        if (ordered.isEmpty()) {
            return Result.empty(text);
        }

        List<RichChatSegment> segments = new ArrayList<>();
        int cursor = 0;
        int inlineCount = 0;
        int blockCount = 0;
        int documentCount = 0;
        for (Match match : ordered) {
            if (match.start > cursor) {
                segments.add(RichChatSegment.text(text.substring(cursor, match.start)));
            }
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("latex_mode", match.kind.metadataName);
            metadata.put("delimiter", match.openDelimiter);
            metadata.put("source_start", Integer.toString(match.start));
            metadata.put("source_end", Integer.toString(match.end));
            switch (match.kind) {
                case INLINE -> {
                    inlineCount++;
                    segments.add(RichChatSegment.latexInline(match.source, metadata));
                }
                case BLOCK -> {
                    blockCount++;
                    segments.add(RichChatSegment.latexBlock(match.source, metadata));
                }
                case DOCUMENT -> {
                    documentCount++;
                    segments.add(RichChatSegment.latexDocument(match.source, metadata));
                }
            }
            cursor = match.end;
        }
        if (cursor < text.length()) {
            segments.add(RichChatSegment.text(text.substring(cursor)));
        }

        RichChatMessageType type = resolveType(text, segments, inlineCount, blockCount, documentCount);
        return new Result(true, text, segments, type, inlineCount, blockCount, documentCount);
    }

    private static RichChatMessageType resolveType(String text, List<RichChatSegment> segments, int inlineCount, int blockCount, int documentCount) {
        if (documentCount > 0) {
            return segments.size() == 1 ? RichChatMessageType.LATEX_DOCUMENT : RichChatMessageType.MIXED;
        }
        if (blockCount > 0) {
            return segments.size() == 1 ? RichChatMessageType.LATEX_BLOCK : RichChatMessageType.MIXED;
        }
        if (inlineCount > 0) {
            boolean hasVisibleText = segments.stream().anyMatch(segment -> switch (segment.type()) {
                case TEXT, MULTILINE_TEXT -> !segment.text().isBlank();
                default -> false;
            });
            return hasVisibleText || text.indexOf('\n') >= 0 ? RichChatMessageType.MIXED : RichChatMessageType.LATEX_INLINE;
        }
        return text.indexOf('\n') >= 0 ? RichChatMessageType.MULTILINE_TEXT : RichChatMessageType.TEXT;
    }

    private static void collectDelimited(List<Match> matches, String text, String open, String close, Kind kind) {
        int cursor = 0;
        while (cursor < text.length()) {
            int start = findUnescaped(text, open, cursor);
            if (start < 0) {
                return;
            }
            int contentStart = start + open.length();
            int endStart = findUnescaped(text, close, contentStart);
            if (endStart < 0) {
                return;
            }
            int end = endStart + close.length();
            String source = text.substring(contentStart, endStart).trim();
            if (!source.isBlank()) {
                matches.add(new Match(start, end, source, open, kind));
            }
            cursor = end;
        }
    }

    private static void collectInlineDollar(List<Match> matches, String text) {
        int cursor = 0;
        while (cursor < text.length()) {
            int start = findSingleDollar(text, cursor);
            if (start < 0) {
                return;
            }
            int endStart = findSingleDollar(text, start + 1);
            if (endStart < 0) {
                return;
            }
            String source = text.substring(start + 1, endStart).trim();
            if (!source.isBlank() && source.indexOf('\n') < 0) {
                matches.add(new Match(start, endStart + 1, source, "$", Kind.INLINE));
            }
            cursor = endStart + 1;
        }
    }

    private static List<Match> nonOverlapping(List<Match> matches) {
        matches.sort(Comparator
                .comparingInt((Match match) -> match.start)
                .thenComparingInt(match -> -priority(match.kind))
                .thenComparingInt(match -> -(match.end - match.start)));
        List<Match> accepted = new ArrayList<>();
        int cursor = 0;
        for (Match match : matches) {
            if (match.start >= cursor) {
                accepted.add(match);
                cursor = match.end;
            }
        }
        return accepted;
    }

    private static int priority(Kind kind) {
        return switch (kind) {
            case DOCUMENT -> 3;
            case BLOCK -> 2;
            case INLINE -> 1;
        };
    }

    private static int findUnescaped(String text, String needle, int fromIndex) {
        int index = Math.max(0, fromIndex);
        while (index < text.length()) {
            int found = text.indexOf(needle, index);
            if (found < 0) {
                return -1;
            }
            if (!escaped(text, found)) {
                return found;
            }
            index = found + needle.length();
        }
        return -1;
    }

    private static int findSingleDollar(String text, int fromIndex) {
        int index = Math.max(0, fromIndex);
        while (index < text.length()) {
            int found = text.indexOf('$', index);
            if (found < 0) {
                return -1;
            }
            boolean doubleDollar = found + 1 < text.length() && text.charAt(found + 1) == '$'
                    || found > 0 && text.charAt(found - 1) == '$';
            if (!doubleDollar && !escaped(text, found)) {
                return found;
            }
            index = found + 1;
        }
        return -1;
    }

    private static boolean escaped(String text, int index) {
        int slashes = 0;
        for (int i = index - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
            slashes++;
        }
        return slashes % 2 == 1;
    }

    private enum Kind {
        INLINE("inline"),
        BLOCK("block"),
        DOCUMENT("document");

        private final String metadataName;

        Kind(String metadataName) {
            this.metadataName = metadataName;
        }
    }

    private record Match(int start, int end, String source, String openDelimiter, Kind kind) {
    }

    public record Result(
            boolean hasLatex,
            String normalizedText,
            List<RichChatSegment> segments,
            RichChatMessageType messageType,
            int inlineCount,
            int blockCount,
            int documentCount
    ) {
        private static Result empty(String text) {
            return new Result(false, text, List.of(), text.indexOf('\n') >= 0 ? RichChatMessageType.MULTILINE_TEXT : RichChatMessageType.TEXT, 0, 0, 0);
        }
    }
}
