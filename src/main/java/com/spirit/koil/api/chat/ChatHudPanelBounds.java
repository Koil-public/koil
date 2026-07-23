package com.spirit.koil.api.chat;

/** Final screen-space bounds assigned to a registered chat panel. */
public record ChatHudPanelBounds(int x, int y, int width, int height) {
    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }
}
