package com.spirit.client.gui.browser;

import java.util.List;

public record ContentPreviewRenderState(
	int viewportX,
	int viewportY,
	int viewportWidth,
	int viewportHeight,
	int scrollMax,
	int sourceChipX,
	int sourceChipY,
	int sourceChipWidth,
	int versionX,
	int versionY,
	int versionWidth,
	int versionSplitX,
	List<ContentPreviewTooltipRegion> tooltipRegions,
	List<ContentPreviewInteractiveRegion> interactiveRegions
) {
}
