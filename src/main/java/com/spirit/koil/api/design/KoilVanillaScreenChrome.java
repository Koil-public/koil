package com.spirit.koil.api.design;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.awt.*;

import static com.spirit.koil.api.design.uiColorVal.*;

public final class KoilVanillaScreenChrome {
    private static final int HEADER_HEIGHT = 36;
    private static final int HEADER_STRIPE_Y = 32;
    private static final int HEADER_STRIPE_HEIGHT = 3;
    private static final int FOOTER_HEIGHT = 35;
    private static final int FOOTER_STRIPE_HEIGHT = 3;
    private static final int CONTENT_SIDE_INSET = 35;
    private static final int CONTENT_STRIPE_LEFT_X_OFFSET = 37;
    private static final int CONTENT_STRIPE_RIGHT_X_OFFSET = 39;

    private KoilVanillaScreenChrome() {
    }

    public static void renderOptionsShell(DrawContext context, MinecraftClient client, int width, int height) {
        KoilScreenBackgrounds.render(context, client, width, height);
        renderFrame(context, width, height);
        if (KoilScreenBackgrounds.canRender(client)) {
            context.fill(0, 0, width, height, KoilScreenBackgrounds.overlayColor(client));
        }
    }

    public static void renderOptionsShellForcedBackground(DrawContext context, MinecraftClient client, int width, int height) {
        KoilScreenBackgrounds.renderForced(context, client, width, height);
        renderFrame(context, width, height);
        context.fill(0, 0, width, height, KoilScreenBackgrounds.overlayColor(client));
    }

    public static void renderListShell(DrawContext context, MinecraftClient client, int width, int height, int listTop, int listBottom) {
        KoilScreenBackgrounds.render(context, client, width, height);
        renderFrame(context, width, height, listTop, listBottom);
        if (KoilScreenBackgrounds.canRender(client)) {
            context.fill(0, 0, width, height, KoilScreenBackgrounds.overlayColor(client));
        }
    }

    public static void renderPanelSideStripes(DrawContext context, int width, int height) {
        int footerTop = Math.max(HEADER_HEIGHT, height - FOOTER_HEIGHT);
        if (width <= CONTENT_SIDE_INSET * 2 || footerTop <= HEADER_STRIPE_Y) {
            return;
        }
        int left = CONTENT_SIDE_INSET;
        int right = width - CONTENT_SIDE_INSET;
        int top = HEADER_STRIPE_Y + 8;
        int bottom = footerTop - 8;
        int stripeLeft = new Color(uiColorContentStripeLeft, true).getRGB();
        int stripeRight = new Color(uiColorContentStripeRight, true).getRGB();
        int dash = new Color(uiColorBackgroundBorder, true).getRGB();
        context.fill(left - 7, top, left - 5, bottom, stripeLeft);
        context.fill(left - 3, top + 10, left - 2, bottom - 10, dash);
        context.fill(right + 5, top, right + 7, bottom, stripeRight);
        context.fill(right + 2, top + 10, right + 3, bottom - 10, dash);
    }

    public static void renderCreditsTextPanel(DrawContext context, int width, int height) {
        if (width <= CONTENT_SIDE_INSET * 2 || height <= 12) {
            return;
        }
        int left = CONTENT_SIDE_INSET;
        int right = width - CONTENT_SIDE_INSET;
        int panelColor = new Color(uiColorContentBase, true).getRGB();
        int borderColor = new Color(uiColorBackgroundBorder, true).getRGB();
        int stripeLeft = new Color(uiColorContentStripeLeft, true).getRGB();
        int stripeRight = new Color(uiColorContentStripeRight, true).getRGB();
        int dash = borderColor;
        context.fill(left, 0, right, height, panelColor);
        context.drawBorder(left, 0, right - left, height, borderColor);
        context.fill(left + 2, 8, left + 4, height - 8, stripeLeft);
        context.fill(left + 7, 18, left + 8, height - 18, dash);
        context.fill(right - 4, 8, right - 2, height - 8, stripeRight);
        context.fill(right - 8, 18, right - 7, height - 18, dash);
    }

    public static void renderCreditsShell(DrawContext context, MinecraftClient client, int width, int height) {
        KoilScreenBackgrounds.renderForced(context, client, width, height);
        context.fill(0, 0, width, height, KoilScreenBackgrounds.overlayColor(client));
        renderCreditsTextPanel(context, width, height);
    }

    public static void renderTitle(DrawContext context, TextRenderer textRenderer, Text mainTitle, Text subTitle) {
        context.getMatrices().push();
        context.getMatrices().scale(1.5F, 1.5F, 1.0F);
        context.drawText(textRenderer, mainTitle, 25, 3, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.getMatrices().pop();

        if (subTitle != null) {
            context.drawText(textRenderer, subTitle, 37, 18, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
        }
    }

    public static int listTop(boolean hasExtendedControls) {
        return hasExtendedControls ? 56 : 42;
    }

    public static int listBottom(int screenHeight) {
        return Math.max(42, screenHeight - 42);
    }

    public static int languageListBottom(int screenHeight) {
        return Math.max(42, screenHeight - 61);
    }

    public static int keybindListBottom(int screenHeight, boolean hasControllingLayout) {
        return Math.max(42, screenHeight - (hasControllingLayout ? 72 : 88));
    }

    private static void renderFrame(DrawContext context, int width, int height) {
        renderFrame(context, width, height, HEADER_HEIGHT, Math.max(HEADER_HEIGHT, height - FOOTER_HEIGHT));
    }

    private static void renderFrame(DrawContext context, int width, int height, int headerBottom, int footerTop) {
        headerBottom = Math.max(HEADER_HEIGHT, Math.min(height, headerBottom));
        footerTop = Math.max(headerBottom, Math.min(height, footerTop));
        context.fill(0, 0, width, headerBottom, new Color(uiColorHeader, true).getRGB());
        int headerStripeY = Math.max(0, headerBottom - HEADER_STRIPE_HEIGHT);
        context.fill(0, headerStripeY, width, headerBottom, new Color(uiColorHeaderStripe, true).getRGB());
        context.fill(0, footerTop, width, height, new Color(uiColorFooter, true).getRGB());
        context.fill(0, footerTop, width, Math.min(height, footerTop + FOOTER_STRIPE_HEIGHT), new Color(uiColorFooterStripe, true).getRGB());

        if (width > CONTENT_SIDE_INSET * 2 && footerTop > headerBottom) {
            context.fill(CONTENT_SIDE_INSET, headerBottom, width - CONTENT_SIDE_INSET, footerTop, new Color(uiColorContentBase, true).getRGB());
            context.fill(CONTENT_STRIPE_LEFT_X_OFFSET, headerBottom, CONTENT_STRIPE_LEFT_X_OFFSET + 2, footerTop, new Color(uiColorContentStripeLeft, true).getRGB());
            context.fill(width - CONTENT_STRIPE_RIGHT_X_OFFSET, headerBottom, width - CONTENT_STRIPE_RIGHT_X_OFFSET + 2, footerTop, new Color(uiColorContentStripeRight, true).getRGB());
        }
    }
}
