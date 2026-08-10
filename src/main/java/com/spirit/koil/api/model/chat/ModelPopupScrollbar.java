package com.spirit.koil.api.model.chat;

import net.minecraft.client.gui.DrawContext;

/** Model-popup-only top-down scrollbar geometry, hit testing, and rendering. */
public final class ModelPopupScrollbar {
    public static final int TRACK_WIDTH = 3;
    public static final int MINIMUM_THUMB_HEIGHT = 18;
    public static final int TRACK_COLOR = 0x20374455;
    public static final int THUMB_COLOR = 0x8890A7C1;

    private ModelPopupScrollbar() {
    }

    public static Metrics topDownRange(
            int x,
            int y,
            int height,
            int maxScroll,
            int offset,
            double visibleFraction
    ) {
        if (maxScroll <= 0 || height <= 0) return null;
        double fraction = Math.max(0.05D, Math.min(1.0D, visibleFraction));
        int thumbHeight = Math.min(height, Math.max(MINIMUM_THUMB_HEIGHT, (int) Math.round(height * fraction)));
        float ratio = Math.max(0.0F, Math.min(1.0F, offset / (float) maxScroll));
        int thumbY = y + Math.round((height - thumbHeight) * ratio);
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
        float ratio = (clamped - minTop) / (float) track;
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
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x - 2 && mouseX <= x + width + 2
                    && mouseY >= y && mouseY <= y + height;
        }

        public boolean thumbContains(double mouseX, double mouseY) {
            return mouseX >= x - 2 && mouseX <= x + width + 2
                    && mouseY >= thumbY && mouseY <= thumbY + thumbHeight;
        }
    }
}
