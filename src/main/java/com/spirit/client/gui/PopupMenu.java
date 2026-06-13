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
    private static final int PADDING_Y = 5;
    private static final int ROW_HEIGHT = 15;
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

    public MenuEntry click(double mouseX, double mouseY) {
        if (!open) {
            return null;
        }
        if (!contains(mouseX, mouseY)) {
            close();
            return null;
        }
        int rowTop = y + PADDING_Y;
        int index = (int) ((mouseY - rowTop) / ROW_HEIGHT);
        if (index >= 0 && index < items.size()) {
            MenuEntry selected = items.get(index);
            close();
            UiSoundHelper.playButtonClick();
            return selected;
        }
        close();
        return null;
    }

    public void render(DrawContext context, int mouseX, int mouseY) {
        if (!open || items.isEmpty()) {
            return;
        }
        hoveredIndex = -1;
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        context.getMatrices().push();
        context.getMatrices().translate(0.0F, 0.0F, 4096.0F);
        context.fill(x + 1, y + 2, x + width + 3, y + height + 3, 0x6A000000);
        context.fill(x, y, x + width, y + height, withAlpha(uiColorContentBase, 242));
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());
        for (int i = 0; i < items.size(); i++) {
            int rowY = y + PADDING_Y + i * ROW_HEIGHT;
            boolean hovered = mouseX >= x + 1 && mouseX <= x + width - 2 && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT - 1;
            if (hovered) {
                hoveredIndex = i;
                context.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT - 1, withAlpha(uiColorHeader, 118));
            }
            if (i > 0) {
                context.fill(x + 3, rowY, x + width - 3, rowY + 1, withAlpha(uiColorBackgroundBorder, 86));
            }
            context.drawText(renderer, items.get(i).label(), x + PADDING_X, rowY + 4, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        }
        context.getMatrices().pop();
    }

    private void measureMenu() {
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        int computedWidth = 0;
        for (MenuEntry item : items) {
            computedWidth = Math.max(computedWidth, renderer.getWidth(item.label()));
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
            if (!current.id().equals(next.id()) || !current.label().equals(next.label())) {
                return false;
            }
        }
        return true;
    }

    private static int withAlpha(int argbColor, int alpha) {
        Color color = new Color(argbColor, true);
        int clampedAlpha = Math.max(0, Math.min(255, alpha));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clampedAlpha).getRGB();
    }

    public record MenuEntry(String id, String label) {
    }
}
