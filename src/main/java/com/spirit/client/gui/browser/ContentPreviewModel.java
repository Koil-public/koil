package com.spirit.client.gui.browser;

import net.minecraft.util.Identifier;
import net.minecraft.text.Text;

import java.util.List;

public record ContentPreviewModel(
	Identifier icon,
	String title,
	String idLabel,
	String idValue,
	String versionLabel,
	String authors,
	String sourceChipLabel,
	List<Text> sourceTooltipLines,
	List<ContentPreviewChip> headerChips,
	List<ContentPreviewChip> categoryChips,
	List<ContentPreviewSection> sections
) {
}
