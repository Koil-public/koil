package com.spirit.client.gui;

import net.minecraft.client.font.TextRenderer;

import java.util.List;

public final class TopBarLayout {
    public static final int LEFT_MARGIN = 10;
    public static final int RIGHT_MARGIN = 10;
    public static final int BUTTON_Y = 43;
    public static final int BUTTON_HEIGHT = 22;
    public static final int BUTTON_GAP = 6;
    public static final int SEARCH_FIELD_Y = 44;
    public static final int SEARCH_FIELD_HEIGHT = 20;

    private final TextRenderer textRenderer;
    private final int screenWidth;

    public TopBarLayout(TextRenderer textRenderer, int screenWidth) {
        this.textRenderer = textRenderer;
        this.screenWidth = screenWidth;
    }

    public int buttonWidth(String label) {
        return Math.max(44, this.textRenderer.getWidth(label) + 18);
    }

    public int searchFieldX(String backLabel) {
        return LEFT_MARGIN + buttonWidth(backLabel) + 8;
    }

    public int searchFieldWidth(String backLabel, List<String> rightButtonLabels, int minimumWidth) {
        int rightStart = rightButtonLabels.isEmpty() ? this.screenWidth - RIGHT_MARGIN : rightButtonX(rightButtonLabels, 0);
        return Math.max(minimumWidth, rightStart - searchFieldX(backLabel) - BUTTON_GAP);
    }

    public int rightButtonX(List<String> orderedRightLabels, int index) {
        int x = this.screenWidth - RIGHT_MARGIN;
        for (int i = orderedRightLabels.size() - 1; i >= index; i--) {
            x -= buttonWidth(orderedRightLabels.get(i));
            if (i > index) {
                x -= BUTTON_GAP;
            }
        }
        return x;
    }
}
