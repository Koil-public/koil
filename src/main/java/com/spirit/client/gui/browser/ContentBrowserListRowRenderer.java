package com.spirit.client.gui.browser;

import com.spirit.client.gui.BrowserLayoutHelper;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import java.awt.Color;

import static com.spirit.koil.api.design.uiColorVal.uiColorNonSelectionHighlight;
import static com.spirit.koil.api.design.uiColorVal.uiColorSelectionHighlight;

public final class ContentBrowserListRowRenderer {
    private ContentBrowserListRowRenderer() {
    }

    public static void renderItem(DrawContext context, TextRenderer textRenderer, Identifier icon, Identifier fallbackIcon, int x, int y, int entryWidth, int entryHeight, boolean selected, String title, String metadata) {
        renderItem(context, textRenderer, icon, fallbackIcon, x, y, entryWidth, entryHeight, selected, true, title, metadata);
    }

    public static void renderItem(DrawContext context, TextRenderer textRenderer, Identifier icon, Identifier fallbackIcon, int x, int y, int entryWidth, int entryHeight, boolean selected, boolean drawBackground, String title, String metadata) {
        if (drawBackground) {
            int rowColor = new Color(selected ? uiColorSelectionHighlight : uiColorNonSelectionHighlight, true).getRGB();
            context.fill(x - 2, y - 2, x + entryWidth - 10, y + Math.min(entryHeight - 2, 38), rowColor);
        }

        Identifier texture = icon != null ? icon : fallbackIcon;
        if (texture != null) {
            context.drawTexture(texture,
                    x + BrowserLayoutHelper.LIST_ROW_ICON_OFFSET_X,
                    y + BrowserLayoutHelper.LIST_ROW_ICON_OFFSET_Y,
                    0,
                    0,
                    BrowserLayoutHelper.LIST_ROW_ICON_SIZE,
                    BrowserLayoutHelper.LIST_ROW_ICON_SIZE,
                    BrowserLayoutHelper.LIST_ROW_ICON_SIZE,
                    BrowserLayoutHelper.LIST_ROW_ICON_SIZE);
        }

        int textX = x + BrowserLayoutHelper.LIST_ROW_TEXT_OFFSET_X;
        int rightEdge = x + entryWidth - BrowserLayoutHelper.LIST_ROW_RIGHT_PADDING;
        int textWidth = Math.max(72, rightEdge - textX);
        String titleLabel = trim(textRenderer, title, textWidth);
        String metadataLabel = trim(textRenderer, metadata, textWidth);
        context.drawText(textRenderer, titleLabel, textX, y + BrowserLayoutHelper.LIST_ROW_TITLE_OFFSET_Y, selected ? 0xFFFFFFFF : 0xFFF2F4F7, true);
        context.drawText(textRenderer, metadataLabel, textX, y + BrowserLayoutHelper.LIST_ROW_META_OFFSET_Y, selected ? 0xFFD2DAE5 : 0xFFAAAAAA, false);
    }

    public static void renderModMenuStyleItem(DrawContext context, TextRenderer textRenderer, Identifier icon, Identifier fallbackIcon, int x, int y, int entryWidth, String title, String metadata) {
        Identifier texture = icon != null ? icon : fallbackIcon;
        if (texture != null) {
            context.drawTexture(texture,
                    x + BrowserLayoutHelper.LIST_ROW_ICON_OFFSET_X,
                    y + BrowserLayoutHelper.LIST_ROW_ICON_OFFSET_Y,
                    0,
                    0,
                    BrowserLayoutHelper.LIST_ROW_ICON_SIZE,
                    BrowserLayoutHelper.LIST_ROW_ICON_SIZE,
                    BrowserLayoutHelper.LIST_ROW_ICON_SIZE,
                    BrowserLayoutHelper.LIST_ROW_ICON_SIZE);
        }

        int textX = x + BrowserLayoutHelper.LIST_ROW_TEXT_OFFSET_X;
        int textWidth = Math.max(72, entryWidth - (textX - x) - BrowserLayoutHelper.LIST_ROW_RIGHT_PADDING);
        context.drawText(textRenderer, trim(textRenderer, title, textWidth), textX, y + BrowserLayoutHelper.LIST_ROW_TITLE_OFFSET_Y, 0xFFFFFF, true);
        context.drawText(textRenderer, trim(textRenderer, metadata, textWidth), textX, y + BrowserLayoutHelper.LIST_ROW_META_OFFSET_Y, 0xAAAAAA, false);
    }

    private static String trim(TextRenderer textRenderer, String text, int maxWidth) {
        return textRenderer.trimToWidth(text == null ? "" : text, Math.max(24, maxWidth));
    }
}
