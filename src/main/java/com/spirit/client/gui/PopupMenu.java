package com.spirit.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static com.spirit.koil.api.design.uiColorVal.uiColorBackgroundBorder;
import static com.spirit.koil.api.design.uiColorVal.uiColorContentBase;
import static com.spirit.koil.api.design.uiColorVal.uiColorContentBaseTitleText;
import static com.spirit.koil.api.design.uiColorVal.uiColorHeader;

@Environment(EnvType.CLIENT)
public final class PopupMenu {
    private static final int PADDING_X = 7;
    private static final int PADDING_Y = 6;
    private static final int ROW_HEIGHT = 16;
    private static final int GAP = 4;

    private final List<MenuEntry> items = new ArrayList<>();
    private boolean open;
    private int x;
    private int y;
    private int width;
    private int height;
    private int hoveredIndex = -1;

    public void toggleAtPointer(double mouseX, double mouseY, int screenWidth, int screenHeight, List<MenuEntry> menuItems) {
        UiSoundHelper.playButtonClick();
        if (open && sameItems(menuItems)) {
            close();
            return;
        }
        openAtPointer(mouseX, mouseY, screenWidth, screenHeight, menuItems);
    }

    public void toggleNearAnchor(int anchorX, int anchorY, int anchorWidth, int screenWidth, int screenHeight, List<MenuEntry> menuItems) {
        UiSoundHelper.playButtonClick();
        if (open && sameItems(menuItems)) {
            close();
            return;
        }
        openNearAnchor(anchorX, anchorY, anchorWidth, screenWidth, screenHeight, menuItems);
    }

    public void openAtPointer(double mouseX, double mouseY, int screenWidth, int screenHeight, List<MenuEntry> menuItems) {
        items.clear();
        items.addAll(menuItems);
        if (items.isEmpty()) {
            close();
            return;
        }
        measureMenu();
        int pointerX = (int) Math.round(mouseX);
        int pointerY = (int) Math.round(mouseY);
        x = clampHorizontal(pointerX + GAP, screenWidth);
        if (x + width > screenWidth - 8) {
            x = clampHorizontal(pointerX - width - GAP, screenWidth);
        }
        y = clampVertical(pointerY - 2, screenHeight);
        open = true;
        hoveredIndex = -1;
    }

    public void openNearAnchor(int anchorX, int anchorY, int anchorWidth, int screenWidth, int screenHeight, List<MenuEntry> menuItems) {
        items.clear();
        items.addAll(menuItems);
        if (items.isEmpty()) {
            close();
            return;
        }
        measureMenu();
        int desiredX = anchorX + anchorWidth + GAP;
        x = clampHorizontal(desiredX, screenWidth);
        if (x + width > screenWidth - 8) {
            x = clampHorizontal(anchorX - width - GAP, screenWidth);
        }
        y = clampVertical(anchorY - 2, screenHeight);
        open = true;
        hoveredIndex = -1;
    }

    public void close() {
        open = false;
        hoveredIndex = -1;
    }

    public boolean isOpen() {
        return open;
    }

    public boolean contains(double mouseX, double mouseY) {
        return open && mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    public MenuEntry entryAt(double mouseX, double mouseY) {
        if (!contains(mouseX, mouseY)) {
            return null;
        }
        int rowTop = y + PADDING_Y;
        int index = (int) ((mouseY - rowTop) / ROW_HEIGHT);
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    public MenuEntry click(double mouseX, double mouseY) {
        if (!open) {
            return null;
        }
        MenuEntry selected = entryAt(mouseX, mouseY);
        if (selected == null) {
            close();
            return null;
        }
        close();
        UiSoundHelper.playButtonClick();
        return selected;
    }

    public void render(DrawContext context, int mouseX, int mouseY) {
        if (!open || items.isEmpty()) {
            return;
        }
        hoveredIndex = -1;
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        context.getMatrices().push();
        context.getMatrices().translate(0.0F, 0.0F, 9000.0F);
        context.fill(x, y, x + width, y + height, withAlpha(uiColorContentBase, 234));
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());
        for (int i = 0; i < items.size(); i++) {
            int rowY = y + PADDING_Y + i * ROW_HEIGHT;
            if (i > 0) {
                context.fill(x + 4, rowY - 2, x + width - 4, rowY - 1, withAlpha(uiColorBackgroundBorder, 96));
            }
            boolean hovered = mouseX >= x + 1 && mouseX <= x + width - 2 && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT - 1;
            if (hovered) {
                hoveredIndex = i;
                context.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT - 1, withAlpha(uiColorHeader, 144));
            }
            MenuEntry entry = items.get(i);
            int textX = x + PADDING_X;
            int trailingWidth = entry.hasTrailingIndicator() ? renderer.getWidth(entry.trailingIndicatorText()) : 0;
            int textRight = x + width - PADDING_X - trailingWidth - (entry.hasTrailingIndicator() ? 4 : 0);
            if (entry.hasIndicator()) {
                context.drawText(renderer, entry.indicatorText(), textX, rowY + 4, entry.indicatorColor(), false);
                textX += renderer.getWidth(entry.indicatorText()) + 4;
            }
            String label = trimWithEllipsis(renderer, entry.label(), Math.max(12, textRight - textX));
            context.drawText(renderer, label, textX, rowY + 4, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            if (entry.hasTrailingIndicator()) {
                int trailingX = x + width - PADDING_X - trailingWidth;
                context.drawText(renderer, entry.trailingIndicatorText(), trailingX, rowY + 4, entry.trailingIndicatorColor(), false);
            }
        }
        context.getMatrices().pop();
    }

    public static void renderTriggerButton(DrawContext context, TextRenderer renderer, int x, int y, boolean hovered, String label) {
        renderTriggerButton(context, renderer, x, y, hovered, label, 255);
    }

    public static void renderTriggerButton(DrawContext context, TextRenderer renderer, int x, int y, boolean hovered, String label, int alpha) {
        renderTriggerButton(context, renderer, x, y, hovered, label, alpha, 14);
    }

    public static void renderTriggerButton(DrawContext context, TextRenderer renderer, int x, int y, boolean hovered, String label, int alpha, int height) {
        if (context == null || renderer == null || label == null) {
            return;
        }
        int safeHeight = Math.max(10, height);
        context.fill(x, y, x + 14, y + safeHeight, multiplyAlpha(hovered ? 0x824D5563 : 0x302A303A, alpha));
        context.drawBorder(x, y, 14, safeHeight, multiplyAlpha(hovered ? 0x9A9AA5B7 : 0x306B7485, alpha));
        int textWidth = renderer.getWidth(label);
        context.drawText(renderer, label, x + (14 - textWidth) / 2, y + Math.max(1, (safeHeight - renderer.fontHeight) / 2), multiplyAlpha(hovered ? 0xC8F5F7FA : 0x52F5F7FA, alpha), false);
    }

    private void measureMenu() {
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        int computedWidth = 0;
        for (MenuEntry item : items) {
            int itemWidth = renderer.getWidth(item.label());
            if (item.hasIndicator()) {
                itemWidth += renderer.getWidth(item.indicatorText()) + 4;
            }
            if (item.hasTrailingIndicator()) {
                itemWidth += renderer.getWidth(item.trailingIndicatorText()) + 4;
            }
            computedWidth = Math.max(computedWidth, itemWidth);
        }
        width = Math.max(88, computedWidth + (PADDING_X * 2));
        height = (PADDING_Y * 2) + (items.size() * ROW_HEIGHT);
    }

    private int clampHorizontal(int desiredX, int screenWidth) {
        return Math.max(8, Math.min(desiredX, screenWidth - width - 8));
    }

    private int clampVertical(int desiredY, int screenHeight) {
        return Math.max(8, Math.min(desiredY, screenHeight - height - 8));
    }

    private boolean sameItems(List<MenuEntry> menuItems) {
        if (items.size() != menuItems.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            MenuEntry current = items.get(i);
            MenuEntry next = menuItems.get(i);
            if (!current.id().equals(next.id())
                    || !current.label().equals(next.label())
                    || current.indicatorColor() != next.indicatorColor()
                    || !current.indicatorText().equals(next.indicatorText())
                    || current.trailingIndicatorColor() != next.trailingIndicatorColor()
                    || !current.trailingIndicatorText().equals(next.trailingIndicatorText())) {
                return false;
            }
        }
        return true;
    }

    private static String trimWithEllipsis(TextRenderer renderer, String text, int maxWidth) {
        if (renderer == null || text == null || text.isEmpty() || maxWidth <= 0) {
            return text == null ? "" : text;
        }
        if (renderer.getWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int ellipsisWidth = renderer.getWidth(ellipsis);
        if (ellipsisWidth >= maxWidth) {
            return renderer.trimToWidth(ellipsis, maxWidth);
        }
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

    private static int multiplyAlpha(int argbColor, int alpha) {
        Color color = new Color(argbColor, true);
        int clampedAlpha = Math.max(0, Math.min(255, Math.round(color.getAlpha() * (alpha / 255.0F))));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clampedAlpha).getRGB();
    }

    public record MenuEntry(String id, String label, int indicatorColor, String indicatorText, int trailingIndicatorColor, String trailingIndicatorText) {
        public MenuEntry(String id, String label) {
            this(id, label, 0, "", 0, "");
        }

        public MenuEntry(String id, String label, int indicatorColor) {
            this(id, label, indicatorColor, "•", 0, "");
        }

        public MenuEntry(String id, String label, int indicatorColor, String indicatorText) {
            this(id, label, indicatorColor, indicatorText, 0, "");
        }

        public MenuEntry(String id, String label, int indicatorColor, String indicatorText, int trailingIndicatorColor, String trailingIndicatorText) {
            this.id = id;
            this.label = label;
            this.indicatorColor = indicatorColor;
            this.indicatorText = indicatorText;
            this.trailingIndicatorColor = trailingIndicatorColor;
            this.trailingIndicatorText = trailingIndicatorText;
        }

        public boolean hasIndicator() {
            return indicatorText != null && !indicatorText.isBlank();
        }

        public boolean hasTrailingIndicator() {
            return trailingIndicatorText != null && !trailingIndicatorText.isBlank();
        }
    }
}
