package com.spirit.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.awt.Color;
import java.util.List;

import static com.spirit.koil.api.design.uiColorVal.uiColorBackgroundBorder;
import static com.spirit.koil.api.design.uiColorVal.uiColorContentBase;
import static com.spirit.koil.api.design.uiColorVal.uiColorContentBaseTitleText;
import static com.spirit.koil.api.design.uiColorVal.uiColorHeader;
import static com.spirit.koil.api.design.uiColorVal.uiColorHeaderSubTitleText;

@Environment(EnvType.CLIENT)
public final class SuggestionPopupRenderer {
    public static final int ROW_HEIGHT = 15;
    public static final int PADDING = 6;
    public static final int KIND_COLUMN_WIDTH = 24;
    public static final int KIND_VALUE_GAP = 2;
    public static final int DETAIL_MAX_WIDTH = 104;
    public static final int MAX_VISIBLE_ROWS = 15;

    private SuggestionPopupRenderer() {
    }

    public static void render(DrawContext context, TextRenderer renderer, int x, int y, int width, List<Entry> entries, int selectedIndex, int mouseX, int mouseY) {
        if (context == null || renderer == null || entries == null || entries.isEmpty() || width <= 0) {
            return;
        }
        int height = 6 + (entries.size() * ROW_HEIGHT) + 6;
        context.getMatrices().push();
        context.getMatrices().translate(0.0F, 0.0F, 9000.0F);
        context.fill(x, y, x + width, y + height, withAlpha(uiColorContentBase, 234));
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            int rowY = y + 4 + (i * ROW_HEIGHT);
            boolean hovered = mouseX >= x + 1 && mouseX <= x + width - 1 && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT - 1;
            if (i == selectedIndex) {
                context.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT - 1, selectedRowColor());
            } else if (hovered) {
                context.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT - 1, withAlpha(uiColorHeader, 96));
            }

            int kindX = x + PADDING;
            int valueX = kindX + KIND_COLUMN_WIDTH + KIND_VALUE_GAP;
            int trailingDetailWidth = entry.detail().isBlank() ? 0 : Math.min(DETAIL_MAX_WIDTH, renderer.getWidth(entry.detail()));
            int leftWidth = Math.max(40, width - (valueX - x) - PADDING - (trailingDetailWidth > 0 ? trailingDetailWidth + 8 : 0));
            String leftText = entry.value();
            int leftColor = new Color(uiColorContentBaseTitleText, true).getRGB();

            context.drawText(renderer, entry.kind(), kindX, rowY + 4, entry.kindColor(), false);
            context.drawText(renderer, fitText(renderer, leftText, leftWidth), valueX, rowY + 4, leftColor, false);
            if (!entry.detail().isBlank()) {
                int trailingX = x + width - PADDING - trailingDetailWidth;
                context.drawText(renderer, fitText(renderer, entry.detail(), Math.max(18, trailingDetailWidth)), trailingX, rowY + 4, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            }
        }
        context.getMatrices().pop();
    }

    public static boolean containsRow(int popupX, int popupY, int popupWidth, int popupHeight, double mouseX, double mouseY) {
        return mouseX >= popupX
                && mouseX <= popupX + popupWidth
                && mouseY >= popupY
                && mouseY <= popupY + popupHeight;
    }

    public static int rowAt(int popupY, double mouseY) {
        return (int) ((mouseY - (popupY + 4)) / ROW_HEIGHT);
    }

    public static int preferredWidth(TextRenderer renderer, List<Entry> entries) {
        if (renderer == null || entries == null || entries.isEmpty()) {
            return 120;
        }
        int widest = 120;
        for (Entry entry : entries) {
            String value = entry == null ? "" : entry.value();
            String detail = entry == null ? "" : entry.detail();
            int detailWidth = detail.isBlank() ? 0 : Math.min(DETAIL_MAX_WIDTH, renderer.getWidth(detail));
            int candidate = (PADDING * 2) + KIND_COLUMN_WIDTH + KIND_VALUE_GAP + 8 + renderer.getWidth(value) + (detailWidth > 0 ? 10 + detailWidth : 0);
            widest = Math.max(widest, candidate);
        }
        return Math.min(320, widest);
    }

    public static int preferredHeight(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        return preferredHeight(entries.size());
    }

    public static int preferredHeight(int rowCount) {
        if (rowCount <= 0) {
            return 0;
        }
        return 6 + (rowCount * ROW_HEIGHT) + 6;
    }

    private static String fitText(TextRenderer renderer, String text, int maxWidth) {
        if (renderer == null || text == null || text.isEmpty() || maxWidth <= 0) {
            return text == null ? "" : text;
        }
        if (renderer.getWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int ellipsisWidth = renderer.getWidth(ellipsis);
        String trimmed = renderer.trimToWidth(text, Math.max(1, maxWidth - ellipsisWidth));
        while (!trimmed.isEmpty() && renderer.getWidth(trimmed + ellipsis) > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + ellipsis;
    }

    private static int withAlpha(int argbColor, int alpha) {
        Color color = new Color(argbColor, true);
        int clampedAlpha = Math.max(0, Math.min(255, alpha));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clampedAlpha).getRGB();
    }

    private static int selectedRowColor() {
        Color background = new Color(uiColorContentBase, true);
        Color accent = new Color(uiColorHeader, true);
        int red = Math.max(Math.min(255, background.getRed() + 34), accent.getRed());
        int green = Math.max(Math.min(255, background.getGreen() + 34), accent.getGreen());
        int blue = Math.max(Math.min(255, background.getBlue() + 34), accent.getBlue());
        return new Color(red, green, blue, 224).getRGB();
    }

    public record Entry(String kind, int kindColor, String value, String detail) {
    }
}
