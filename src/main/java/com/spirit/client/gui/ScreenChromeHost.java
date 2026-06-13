package com.spirit.client.gui;

import net.minecraft.client.gui.DrawContext;

public interface ScreenChromeHost {
    void koil$renderScreenChromeLate(DrawContext context, int mouseX, int mouseY, float delta);

    boolean koil$consumeScreenChromeClick(double mouseX, double mouseY, int button);
}
