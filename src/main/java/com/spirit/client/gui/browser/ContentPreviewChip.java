package com.spirit.client.gui.browser;

import net.minecraft.text.Text;

import java.util.List;

public record ContentPreviewChip(String label, int background, List<Text> tooltipLines, String actionId) {
	public ContentPreviewChip(String label, int background, List<Text> tooltipLines) {
		this(label, background, tooltipLines, "");
	}
}
