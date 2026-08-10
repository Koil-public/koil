package com.spirit.koil.api.chat;

import net.minecraft.client.gui.DrawContext;

/** Native-chat-only bottom-up scrollbar geometry and rendering. */
public final class ChatHudScrollbar {
    public static final int TRACK_WIDTH = 3;
    public static final int MINIMUM_THUMB_HEIGHT = 18;
    public static final int TRACK_COLOR = 0x20374455;
    public static final int THUMB_COLOR = 0x8890A7C1;

    private ChatHudScrollbar() {
    }

    /** Bottom-up metrics where offset zero follows the newest chat row. */
    public static Metrics bottomUp(
            int x,
            int y,
            int height,
            int totalRows,
            int visibleRows,
            int offset
    ) {
        int safeVisible = Math.max(1, visibleRows);
        int safeTotal = Math.max(safeVisible, totalRows);
        int maxScroll = Math.max(0, safeTotal - safeVisible);
        if (maxScroll == 0 || height <= 0) return null;
        int thumbHeight = Math.min(height, Math.max(MINIMUM_THUMB_HEIGHT, height * safeVisible / safeTotal));
        float ratio = Math.max(0.0F, Math.min(1.0F, offset / (float) maxScroll));
        int thumbY = y + height - thumbHeight - Math.round((height - thumbHeight) * ratio);
        return new Metrics(x, y, TRACK_WIDTH, height, thumbY, thumbHeight, maxScroll);
    }

    public static void render(DrawContext context, Metrics metrics) {
        if (context == null || metrics == null) return;
        context.fill(metrics.x(), metrics.y(), metrics.x() + metrics.width(), metrics.y() + metrics.height(), TRACK_COLOR);
        context.fill(metrics.x(), metrics.thumbY(), metrics.x() + metrics.width(), metrics.thumbY() + metrics.thumbHeight(), THUMB_COLOR);
    }

    public static int offsetFromThumbTop(int thumbTop, Metrics metrics) {
        if (metrics == null) return 0;
        int minTop = metrics.y();
        int maxTop = metrics.y() + metrics.height() - metrics.thumbHeight();
        int clamped = Math.max(minTop, Math.min(maxTop, thumbTop));
        int track = Math.max(1, maxTop - minTop);
        float ratio = 1.0F - (clamped - minTop) / (float) track;
        return Math.max(0, Math.min(metrics.maxScroll(), Math.round(ratio * metrics.maxScroll())));
    }

    public record Metrics(
            int x,
            int y,
            int width,
            int height,
            int thumbY,
            int thumbHeight,
            int maxScroll
    ) {
    }
}
