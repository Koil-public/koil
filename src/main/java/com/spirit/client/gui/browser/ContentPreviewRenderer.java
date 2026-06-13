package com.spirit.client.gui.browser;

import com.spirit.client.gui.BrowserLayoutHelper;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static com.spirit.koil.api.design.uiColorVal.uiColorBackgroundBorder;
import static com.spirit.koil.api.design.uiColorVal.uiColorBasicSubtitleText;
import static com.spirit.koil.api.design.uiColorVal.uiColorContentBase;
import static com.spirit.koil.api.design.uiColorVal.uiColorContentBaseTitleText;
import static com.spirit.koil.api.design.uiColorVal.uiColorContentStripeLeft;
import static com.spirit.koil.api.design.uiColorVal.uiColorContentStripeRight;

public final class ContentPreviewRenderer {
	private ContentPreviewRenderer() {
	}

	public static ContentPreviewRenderState render(
			DrawContext context,
			TextRenderer textRenderer,
			int screenWidth,
			int screenHeight,
			ContentPreviewModel model,
			int previewScrollOffset
	) {
		int x = BrowserLayoutHelper.previewX(screenWidth);
		int y = BrowserLayoutHelper.previewY();
		int panelWidth = BrowserLayoutHelper.previewWidth(screenWidth);
		int panelHeight = BrowserLayoutHelper.previewHeight(screenHeight);
		List<ContentPreviewTooltipRegion> tooltipRegions = new ArrayList<>();
		List<ContentPreviewInteractiveRegion> interactiveRegions = new ArrayList<>();
		int sourceChipWidth = Math.max(56, textRenderer.getWidth("Source " + model.sourceChipLabel()) + 12);
		int sourceChipX = x + panelWidth - sourceChipWidth - 10;
		int sourceChipY = y + 5;

		context.fill(x, y, x + panelWidth, y + panelHeight, new Color(uiColorContentBase, true).getRGB());
		context.drawBorder(x, y, panelWidth, panelHeight, new Color(uiColorBackgroundBorder, true).getRGB());
		context.fill(x, y, x + 2, y + panelHeight, new Color(uiColorContentStripeLeft, true).getRGB());
		context.fill(x + panelWidth - 2, y, x + panelWidth, y + panelHeight, new Color(uiColorContentStripeRight, true).getRGB());
		renderSourceChip(context, textRenderer, sourceChipX, sourceChipY, sourceChipWidth, model.sourceChipLabel());

		Identifier icon = model.icon();
		if (icon != null) {
			context.drawTexture(icon, x + 10, y + 10, 0, 0, 64, 64, 64, 64);
		}

		int overlayY = y + 10;
		context.getMatrices().push();
		context.getMatrices().scale(1.5F, 1.5F, 1.0F);
		context.drawText(textRenderer, trimPreview(textRenderer, model.title(), Math.max(80, panelWidth - 150)), (int) (x / 1.5F) + 54, (int) (overlayY / 1.5F), new Color(uiColorContentBaseTitleText, true).getRGB(), true);
		context.getMatrices().pop();

		int chipX = x + 80;
		int detailChipY = overlayY + 15;
		for (ContentPreviewChip chip : model.headerChips()) {
			chipX = renderDetailChip(context, textRenderer, tooltipRegions, interactiveRegions, chipX, detailChipY, chip) + 4;
		}

		context.drawText(textRenderer, model.idLabel(), x + 80, overlayY + 33, uiColorBasicSubtitleText, false);
		context.drawText(textRenderer, model.idValue(), x + 122, overlayY + 33, 0xFFD8DFE9, false);
		context.drawText(textRenderer, "Version", x + 80, overlayY + 44, uiColorBasicSubtitleText, false);
		int versionX = x + 122;
		int versionY = overlayY + 44;
		int versionWidth = textRenderer.getWidth(model.versionLabel());
		int versionSplitX = model.versionLabel().contains("  ->  ") ? versionX + textRenderer.getWidth(model.versionLabel().substring(0, model.versionLabel().indexOf("  ->") + 4)) : -1;
		context.drawText(textRenderer, model.versionLabel(), versionX, versionY, 0xFFE9DFC9, false);
		context.drawText(textRenderer, "Authors", x + 80, overlayY + 55, uiColorBasicSubtitleText, false);
		context.drawText(textRenderer, trimPreview(textRenderer, model.authors(), 188), x + 122, overlayY + 55, 0xFFD8DFE9, false);

		int tagsY = overlayY + 66;
		int tagX = x + 80;
		for (ContentPreviewChip chip : model.categoryChips()) {
			if (tagX > x + panelWidth - 90) {
				break;
			}
			tagX = renderDetailChip(context, textRenderer, tooltipRegions, interactiveRegions, tagX, tagsY, chip) + 4;
		}

		int detailsViewportY = overlayY + 82;
		int detailsViewportHeight = Math.max(70, panelHeight - (detailsViewportY - y) - 8);
		int viewportX = x + 8;
		int viewportY = detailsViewportY;
		int viewportWidth = panelWidth - 16;
		int viewportHeight = detailsViewportHeight;
		context.enableScissor(viewportX, viewportY, viewportX + viewportWidth, viewportY + viewportHeight);
		ContentPreviewSectionRenderResult renderResult = ContentPreviewSectionRenderer.renderSections(context, textRenderer, x, panelWidth, detailsViewportY - previewScrollOffset, model.sections());
		context.disableScissor();
		int scrollMax = Math.max(0, renderResult.contentHeight() - detailsViewportHeight + 4);
		renderScrollbar(context, viewportX, viewportY, viewportWidth, viewportHeight, previewScrollOffset, scrollMax);

		return new ContentPreviewRenderState(
				viewportX,
				viewportY,
				viewportWidth,
				viewportHeight,
				scrollMax,
				sourceChipX,
				sourceChipY,
				sourceChipWidth,
				versionX,
				versionY,
				versionWidth,
				versionSplitX,
				tooltipRegions,
				interactiveRegions
		);
	}

	private static int renderDetailChip(
			DrawContext context,
			TextRenderer textRenderer,
			List<ContentPreviewTooltipRegion> tooltipRegions,
			List<ContentPreviewInteractiveRegion> interactiveRegions,
			int x,
			int y,
			ContentPreviewChip chip
	) {
		int width = textRenderer.getWidth(chip.label()) + 10;
		context.fill(x, y, x + width, y + 11, chip.background());
		context.drawBorder(x, y, width, 11, 0xB06B7485);
		context.drawText(textRenderer, chip.label(), x + 5, y + 2, 0xFFE8EDF5, false);
		if (chip.tooltipLines() != null && !chip.tooltipLines().isEmpty()) {
			tooltipRegions.add(new ContentPreviewTooltipRegion(x, y, width, 11, chip.tooltipLines()));
		}
		if (chip.actionId() != null && !chip.actionId().isBlank()) {
			interactiveRegions.add(new ContentPreviewInteractiveRegion(x, y, width, 11, chip.actionId()));
		}
		return x + width;
	}

	private static void renderSourceChip(DrawContext context, TextRenderer textRenderer, int x, int y, int width, String label) {
		context.fill(x - 4, y - 3, x + width, y + 10, 0x27333D49);
		context.drawBorder(x - 4, y - 3, width + 4, 13, 0x8E6A7684);
		context.drawText(textRenderer, "Source " + label, x, y, 0xFFD5DEE8, false);
	}

	private static void renderScrollbar(DrawContext context, int viewportX, int viewportY, int viewportWidth, int viewportHeight, int previewScrollOffset, int scrollMax) {
		if (scrollMax <= 0 || viewportHeight <= 0) {
			return;
		}
		int scrollbarX = viewportX + viewportWidth - 2;
		context.fill(scrollbarX, viewportY, scrollbarX + 3, viewportY + viewportHeight, 0x20374455);
		int thumbHeight = Math.max(18, (int) ((viewportHeight / (float) (viewportHeight + scrollMax)) * viewportHeight));
		int thumbTravel = Math.max(1, viewportHeight - thumbHeight);
		int thumbY = viewportY + (int) ((previewScrollOffset / (float) scrollMax) * thumbTravel);
		context.fill(scrollbarX, thumbY, scrollbarX + 3, thumbY + thumbHeight, 0x8890A7C1);
	}

	private static String trimPreview(TextRenderer textRenderer, String text, int maxWidth) {
		if (text == null) {
			return "";
		}
		String trimmed = textRenderer.trimToWidth(text, maxWidth);
		if (trimmed.length() == text.length()) {
			return trimmed;
		}
		return textRenderer.trimToWidth(text, Math.max(8, maxWidth - textRenderer.getWidth("..."))) + "...";
	}
}
