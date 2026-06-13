package com.spirit.client.gui;

import com.spirit.koil.api.design.KoilScreenBackgrounds;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.MinecraftClient;
import com.spirit.koil.api.util.file.json.JSONFileEditor;

import java.awt.*;

import static com.spirit.koil.api.design.uiColorVal.*;

public final class BrowserLayoutHelper {
    public static final int LIST_OUTER_LEFT = 31;
    public static final int LIST_INNER_LEFT = 37;
    public static final int LIST_OUTER_RIGHT = 351;
    public static final int LIST_INNER_RIGHT = 345;
    public static final int TOP_BAR_HEIGHT = 43;
    public static final int LIST_WIDGET_TOP = TOP_BAR_HEIGHT - 2;
    public static final int TOP_BAR_STRIPE_BOTTOM = 42;
    public static final int FOOTER_HEIGHT = 60;
    public static final int FOOTER_STRIPE_TOP_OFFSET = 57;
    public static final int FILTER_BUTTON_Y = 10;
    public static final int FILTER_BUTTON_HEIGHT = 20;
    public static final int FILTER_BUTTON_GAP = 6;
    public static final int FOOTER_BUTTON_X = 37;
    public static final int FOOTER_TOP_BUTTON_WIDTH = 150;
    public static final int FOOTER_TOP_BUTTON_GAP = 6;
    public static final int FOOTER_RIGHT_ACTION_X = 353;
    public static final int FOOTER_RIGHT_ACTION_WIDTH = 150;
    public static final int FOOTER_SMALL_BUTTON_WIDTH = 72;
    public static final int FOOTER_SMALL_BUTTON_GAP = 6;
    public static final int LIST_ROW_ICON_OFFSET_X = 2;
    public static final int LIST_ROW_ICON_OFFSET_Y = 0;
    public static final int LIST_ROW_ICON_SIZE = 32;
    public static final int LIST_ROW_TEXT_OFFSET_X = 54;
    public static final int LIST_ROW_TITLE_OFFSET_Y = 5;
    public static final int LIST_ROW_META_OFFSET_Y = 18;
    public static final int LIST_ROW_SELECTION_X_OFFSET = 0;
    public static final int LIST_ROW_SELECTION_Y_OFFSET = 3;
    public static final int LIST_ROW_SELECTION_HEIGHT = 30;
    public static final int LIST_ROW_RIGHT_PADDING = 14;

    private BrowserLayoutHelper() {
    }

    public static boolean isUiRedesignEnabled() {
        try {
            return JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void renderContentBackground(DrawContext context, MinecraftClient client, int width, int height) {
        if (KoilScreenBackgrounds.uiRedesignEnabled()) {
            KoilScreenBackgrounds.render(context, client, width, height);
            if (KoilScreenBackgrounds.canRender(client)) {
                context.fill(0, 0, width, height, KoilScreenBackgrounds.overlayColor(client));
            }
            return;
        }
        context.fill(0, 0, width, height, new Color(uiColorContentBase, true).getRGB());
    }

    public static void renderBrowserChrome(DrawContext context, MinecraftClient client, TextRenderer textRenderer, int width, int height, String title) {
        int listTop = TOP_BAR_HEIGHT;
        int listBottom = height - FOOTER_HEIGHT;
        context.fill(0, 0, width, TOP_BAR_HEIGHT, new Color(uiColorHeader, true).getRGB());
        context.fill(0, 39, width, TOP_BAR_STRIPE_BOTTOM, new Color(uiColorHeaderStripe, true).getRGB());
        context.fill(0, height - FOOTER_HEIGHT, width, height, new Color(uiColorFooter, true).getRGB());
        context.fill(0, height - FOOTER_HEIGHT, width, height - FOOTER_STRIPE_TOP_OFFSET, new Color(uiColorFooterStripe, true).getRGB());
        context.fill(LIST_OUTER_LEFT, listTop, LIST_OUTER_RIGHT, listBottom, new Color(uiColorContentBase, true).getRGB());
        context.fill(LIST_INNER_LEFT, listTop, LIST_INNER_LEFT + 2, listBottom, new Color(uiColorContentStripeLeft, true).getRGB());
        context.fill(LIST_INNER_RIGHT - 2, listTop, LIST_INNER_RIGHT, listBottom, new Color(uiColorContentStripeRight, true).getRGB());

        context.getMatrices().push();
        context.getMatrices().scale(1.5F, 1.5F, 1.0F);
        context.drawText(textRenderer, title, 25, 6, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(textRenderer, "-=-", 37, 23, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
    }

    public static void renderBrowserBars(DrawContext context, MinecraftClient client, TextRenderer textRenderer, int width, int height, String title) {
        context.getMatrices().push();
        context.getMatrices().scale(1.5F, 1.5F, 1.0F);
        context.drawText(textRenderer, title, 25, 6, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(textRenderer, "-=-", 37, 23, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
    }

    public static void renderFilterButton(DrawContext context, TextRenderer textRenderer, int x, String label) {
        int width = getFilterButtonWidth(textRenderer, label);
        context.fill(x, FILTER_BUTTON_Y, x + width, FILTER_BUTTON_Y + FILTER_BUTTON_HEIGHT, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(x, FILTER_BUTTON_Y, width, FILTER_BUTTON_HEIGHT, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawText(textRenderer, label, x + 7, FILTER_BUTTON_Y + 6, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
    }

    public static int getFilterButtonWidth(TextRenderer textRenderer, String label) {
        return Math.max(72, textRenderer.getWidth(label) + 18);
    }

    public static int footerTopButtonX(int index) {
        return FOOTER_BUTTON_X + (index * (FOOTER_TOP_BUTTON_WIDTH + FOOTER_TOP_BUTTON_GAP));
    }

    public static int footerSmallButtonX(int index) {
        return FOOTER_BUTTON_X + (index * (FOOTER_SMALL_BUTTON_WIDTH + FOOTER_SMALL_BUTTON_GAP));
    }

    public static int previewX(int screenWidth) {
        return LIST_OUTER_RIGHT + 7;
    }

    public static int previewY() {
        return 43;
    }

    public static int previewWidth(int screenWidth) {
        return Math.max(220, screenWidth - previewX(screenWidth) - 8);
    }

    public static int previewHeight(int screenHeight) {
        return Math.max(120, (screenHeight - FOOTER_HEIGHT) - previewY());
    }

    public static void renderPreviewPanelFrame(DrawContext context, int x, int y, int width, int height) {
        context.fill(x, y, x + width, y + height, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(x, y, x + 2, y + height, new Color(uiColorContentStripeLeft, true).getRGB());
        context.fill(x + width - 2, y, x + width, y + height, new Color(uiColorContentStripeRight, true).getRGB());
    }

    public static void renderSectionRule(DrawContext context, TextRenderer textRenderer, int left, int right, int y, String title) {
        context.fill(left, y, right, y + 1, 0x50798596);
        context.drawText(textRenderer, title, left + 2, y + 3, 0xFFBFCAD8, false);
    }

    public static int renderInfoLine(DrawContext context, TextRenderer textRenderer, int x, int y, int panelWidth, String label, String value, int valueColor) {
        int labelWidth = 92;
        int valueX = x + labelWidth;
        int valueWidth = Math.max(40, panelWidth - (valueX - x) - 18);
        int lineLeft = x - 2;
        int lineRight = x + panelWidth - 12;
        context.fill(lineLeft, y - 2, lineRight, y - 1, 0x20374455);
        context.drawText(textRenderer, label, x, y, 0xFF9FB1C4, false);
        context.fill(valueX - 7, y - 1, valueX - 6, y + textRenderer.fontHeight + 1, 0x38465567);
        String rendered = value == null || value.isBlank() ? "none" : value;
        int currentY = y;
        for (var line : textRenderer.wrapLines(net.minecraft.text.Text.literal(rendered), valueWidth)) {
            context.drawText(textRenderer, line, valueX, currentY, valueColor, false);
            currentY += textRenderer.fontHeight + 2;
        }
        int rowBottom = Math.max(y + textRenderer.fontHeight + 4, currentY + 1);
        context.fill(lineLeft, rowBottom, lineRight, rowBottom + 1, 0x142C3643);
        return rowBottom + 4;
    }
}
