package com.spirit.koil.api.console;

public final class ConsoleSuggestionPresentation {
    public static final int MAX_VISIBLE_ROWS = 8;
    public static final int ROW_HEIGHT = 16;
    public static final int PADDING = 5;
    public static final int MIN_WIDTH = 196;
    public static final int MAX_WIDTH = 356;
    public static final int DETAIL_WIDTH = 118;

    private ConsoleSuggestionPresentation() {
    }

    public static Presentation present(String rawKind) {
        if (rawKind == null || rawKind.isBlank()) {
            return new Presentation("symbol", 0xFF9BE67C);
        }
        return switch (rawKind.toUpperCase()) {
            case "CMD", "MC", "RAW" -> new Presentation("keyword", 0xFF77C6FF);
            case "RUN" -> new Presentation("method", 0xFF79D3B4);
            case "TASK" -> new Presentation("symbol", 0xFF9BE67C);
            case "CHAT" -> new Presentation("literal", 0xFFF1C96A);
            case "KTL", "FILE" -> new Presentation("path", 0xFF9FC4D8);
            case "SEM" -> new Presentation("field", 0xFFC4E67C);
            case "SEL" -> new Presentation("selector", 0xFFD8A5FF);
            case "HIST" -> new Presentation("property", 0xFFF0C673);
            default -> new Presentation(rawKind.toLowerCase(), 0xFF9BE67C);
        };
    }

    public record Presentation(String label, int color) {
    }
}
