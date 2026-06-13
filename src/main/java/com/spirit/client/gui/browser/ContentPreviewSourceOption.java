package com.spirit.client.gui.browser;

import net.minecraft.text.Text;

import java.util.List;

public record ContentPreviewSourceOption(String id, String label, List<Text> tooltipLines) {
}
