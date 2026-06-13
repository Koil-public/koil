package com.spirit.client.gui.browser;

import com.spirit.client.gui.MarkdownPreviewRenderer;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

public final class ContentPreviewSectionRenderer {
	private static final int PREVIEW_INFO_LABEL_WIDTH = 96;
	private static final int PREVIEW_INFO_ROW_PADDING = 4;

	private ContentPreviewSectionRenderer() {
	}

	public static ContentPreviewSectionRenderResult renderSections(DrawContext context, TextRenderer textRenderer, int panelX, int panelWidth, int startY, List<ContentPreviewSection> sections) {
		int contentY = startY;
		int contentHeight = 0;
		int markdownWidth = Math.max(180, panelWidth - 20);
		for (ContentPreviewSection section : sections) {
			if (section == null) {
				continue;
			}
			renderSectionRule(context, textRenderer, panelX + 8, panelX + panelWidth - 8, contentY, section.title());
			contentY += 14;
			contentHeight += 14;
			if (section.rows() != null) {
				for (ContentPreviewSectionRow row : section.rows()) {
					int nextY = renderInfoLine(context, textRenderer, panelX + 10, contentY, panelWidth, row.label(), row.value(), row.valueColor());
					contentHeight += nextY - contentY;
					contentY = nextY;
				}
			}
			if (section.paragraphs() != null) {
				for (String paragraph : section.paragraphs()) {
					for (MarkdownPreviewRenderer.Line line : MarkdownPreviewRenderer.wrap(blank(paragraph), textRenderer, markdownWidth)) {
						int lineHeight = MarkdownPreviewRenderer.renderLine(context, textRenderer, line, panelX + 10, contentY);
						contentY += lineHeight;
						contentHeight += lineHeight;
					}
				}
			}
			contentY += 6;
			contentHeight += 6;
		}
		return new ContentPreviewSectionRenderResult(contentY, contentHeight);
	}

	public static void renderSectionRule(DrawContext context, TextRenderer textRenderer, int left, int right, int y, String title) {
		context.fill(left, y, right, y + 1, 0x50798596);
		context.drawText(textRenderer, title, left + 2, y + 3, 0xFFBFCAD8, false);
	}

	public static int renderInfoLine(DrawContext context, TextRenderer textRenderer, int x, int y, int panelWidth, String label, String value, int valueColor) {
		int labelWidth = PREVIEW_INFO_LABEL_WIDTH;
		int valueX = x + labelWidth;
		int valueWidth = Math.max(40, panelWidth - (valueX - x) - 18);
		int lineLeft = x - 2;
		int lineRight = x + panelWidth - 12;
		context.fill(lineLeft, y - 2, lineRight, y - 1, 0x20374455);
		context.drawText(textRenderer, label, x, y, 0xFF9FB1C4, false);
		context.fill(valueX - 7, y - 1, valueX - 6, y + textRenderer.fontHeight + 1, 0x38465567);
		List<MarkdownPreviewRenderer.Line> lines = MarkdownPreviewRenderer.wrap(blank(value), textRenderer, valueWidth);
		int currentY = y;
		for (MarkdownPreviewRenderer.Line line : lines) {
			List<MarkdownPreviewRenderer.InlineSpan> spans = new java.util.ArrayList<>();
			for (MarkdownPreviewRenderer.InlineSpan span : line.spans()) {
				spans.add(new MarkdownPreviewRenderer.InlineSpan(
						span.text(),
						valueColor,
						span.bold(),
						span.italic(),
						span.underline(),
						span.strikethrough(),
						span.code(),
						span.linkTarget(),
						span.imagePayload()
				));
			}
			MarkdownPreviewRenderer.Line renderedLine = new MarkdownPreviewRenderer.Line(line.rawText(), spans, 0, valueColor, line.accentColor(), line.accent(), line.payload());
			int lineHeight = MarkdownPreviewRenderer.renderLine(context, textRenderer, renderedLine, valueX, currentY);
			currentY += lineHeight;
		}
		int rowBottom = Math.max(y + textRenderer.fontHeight + PREVIEW_INFO_ROW_PADDING, currentY + 1);
		context.fill(lineLeft, rowBottom, lineRight, rowBottom + 1, 0x142C3643);
		return rowBottom + PREVIEW_INFO_ROW_PADDING;
	}

	private static String blank(String text) {
		return text == null || text.isBlank() ? "" : text;
	}
}
