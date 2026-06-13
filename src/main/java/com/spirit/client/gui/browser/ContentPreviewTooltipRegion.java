package com.spirit.client.gui.browser;

import net.minecraft.text.Text;

import java.util.List;

public record ContentPreviewTooltipRegion(int x, int y, int width, int height, List<Text> lines) {
}
