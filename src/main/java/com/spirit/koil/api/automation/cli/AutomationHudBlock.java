package com.spirit.koil.api.automation.cli;

import net.minecraft.text.OrderedText;

import java.util.List;

public final class AutomationHudBlock {
    public final List<OrderedText> lines;
    public final int width;
    public final int height;
    public final int paddingX;
    public final int paddingY;
    public final int lineHeight;

    public AutomationHudBlock(List<OrderedText> lines, int width, int height, int paddingX, int paddingY, int lineHeight) {
        this.lines = lines;
        this.width = width;
        this.height = height;
        this.paddingX = paddingX;
        this.paddingY = paddingY;
        this.lineHeight = lineHeight;
    }
}
