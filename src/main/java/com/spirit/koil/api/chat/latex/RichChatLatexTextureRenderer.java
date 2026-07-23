package com.spirit.koil.api.chat.latex;

import com.mojang.blaze3d.systems.RenderSystem;
import com.spirit.koil.api.chat.RichChatRenderContext;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;

public final class RichChatLatexTextureRenderer {
    private static final int FAILED_COLOR = 0xFFFF7676;
    private static final int PENDING_COLOR = 0xFFFFD166;

    private RichChatLatexTextureRenderer() {
    }

    public static int renderOrDrawText(DrawContext context, TextRenderer renderer, OrderedText orderedText, int x, int y, int color) {
        String text = plainText(orderedText);
        if (!RichChatLatexTextureCache.containsMarker(text)) {
            return context.drawTextWithShadow(renderer, orderedText, x, y, color);
        }

        int lineX = x;
        int cursor = x;
        int maxRight = x;
        int index = 0;
        RichChatLatexTextureCache.Marker marker;
        while ((marker = RichChatLatexTextureCache.nextMarker(text, index)) != null) {
            if (marker.start() > index) {
                String before = text.substring(index, marker.start());
                context.drawTextWithShadow(renderer, before, cursor, y, color);
                cursor += renderer.getWidth(before);
                maxRight = Math.max(maxRight, cursor);
            }
            int renderedWidth = renderMarker(context, renderer, marker.entry(), marker.row(), lineX, cursor, y, color);
            cursor += renderedWidth;
            maxRight = Math.max(maxRight, cursor);
            index = marker.end();
        }
        if (index < text.length()) {
            String after = text.substring(index);
            context.drawTextWithShadow(renderer, after, cursor, y, color);
            cursor += renderer.getWidth(after);
            maxRight = Math.max(maxRight, cursor);
        }
        return maxRight;
    }

    private static int renderMarker(DrawContext context, TextRenderer renderer, RichChatLatexTextureCache.Entry entry, int row, int lineX, int x, int y, int color) {
        if (entry == null) {
            String fallback = "[math]";
            context.drawTextWithShadow(renderer, fallback, x, y, color);
            return renderer.getWidth(fallback);
        }

        int availableWidth = RichChatLatexTextureCache.availableWidthFrom(lineX, x);
        if (availableWidth <= 0) {
            return 0;
        }
        int spacing = RichChatLatexTextureCache.spacingForMode(entry.mode());
        int reservedAdvance = Math.max(1, Math.min(entry.advanceWidth(), availableWidth));

        if (entry.status() == RichChatLatexTextureCache.Status.READY && entry.textureId() != null) {
            int width = Math.max(1, entry.width());
            int height = Math.max(1, entry.height());
            int drawWidth = width;
            int drawHeight = height;
            int drawY = drawY(renderer, entry.mode(), y, drawHeight) - Math.max(0, row) * RichChatLatexTextureCache.currentChatLineHeight();
            int visibleAdvance = Math.min(availableWidth, Math.max(1, Math.min(entry.advanceWidth(), drawWidth + spacing)));

            drawVisibleFormulaTexture(context, entry, row, lineX, x, y, drawY, drawWidth, drawHeight, color);
            return row > 0 ? 0 : visibleAdvance;
        }
        if (entry.status() == RichChatLatexTextureCache.Status.FAILED) {
            String fallback = "[LaTeX error]";
            context.drawTextWithShadow(renderer, fallback, x, y, FAILED_COLOR);
            return Math.min(availableWidth, Math.max(renderer.getWidth(fallback), reservedAdvance));
        }
        String fallback = "[LaTeX loading]";
        context.drawTextWithShadow(renderer, fallback, x, y, PENDING_COLOR);
        return Math.min(availableWidth, Math.max(renderer.getWidth(fallback), reservedAdvance));
    }

    private static void drawVisibleFormulaTexture(DrawContext context, RichChatLatexTextureCache.Entry entry, int row, int lineX, int x, int lineY, int y, int drawWidth, int drawHeight, int color) {
        int chatLeft = lineX;
        int chatRight = lineX + Math.max(1, RichChatLatexTextureCache.currentChatContentWidth());
        int visibleLeft = Math.max(chatLeft, x);
        int visibleRight = Math.min(chatRight, x + Math.max(1, drawWidth));
        int viewportTop = RichChatRenderContext.currentChatViewportTop();
        int viewportBottom = RichChatRenderContext.currentChatViewportBottom();
        int rowHeight = Math.max(1, RichChatLatexTextureCache.currentChatLineHeight());
        boolean slicedRows = entry.mode() != RichChatLatexTextureCache.Mode.INLINE && drawHeight > rowHeight;
        int rowTop = slicedRows ? lineY : Integer.MIN_VALUE / 4;
        int rowBottom = slicedRows ? lineY + rowHeight : Integer.MAX_VALUE / 4;
        int visibleTop = Math.max(Math.max(viewportTop, rowTop), y);
        int visibleBottom = Math.min(Math.min(viewportBottom, rowBottom), y + Math.max(1, drawHeight));
        if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) {
            return;
        }

        int visibleWidth = Math.max(1, visibleRight - visibleLeft);
        int visibleHeight = Math.max(1, visibleBottom - visibleTop);
        int textureWidth = Math.max(1, entry.textureWidth());
        int textureHeight = Math.max(1, entry.textureHeight());
        float textureScaleX = textureWidth / (float) Math.max(1, drawWidth);
        float textureScaleY = textureHeight / (float) Math.max(1, drawHeight);
        int croppedDisplayX = Math.max(0, visibleLeft - x);
        int croppedDisplayY = Math.max(0, visibleTop - y);
        float u = croppedDisplayX * textureScaleX;
        float v = croppedDisplayY * textureScaleY;
        int sourceWidth = Math.max(1, Math.round(visibleWidth * textureScaleX));
        sourceWidth = Math.min(sourceWidth, Math.max(1, textureWidth - Math.round(u)));
        int sourceHeight = Math.max(1, Math.round(visibleHeight * textureScaleY));
        sourceHeight = Math.min(sourceHeight, Math.max(1, textureHeight - Math.round(v)));

        applyShaderColor(color);
        context.drawTexture(entry.textureId(), visibleLeft, visibleTop, visibleWidth, visibleHeight, u, v, sourceWidth, sourceHeight, textureWidth, textureHeight);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static int drawY(TextRenderer renderer, RichChatLatexTextureCache.Mode mode, int y, int height) {
        return y + 1;
    }

    private static void applyShaderColor(int color) {
        float alpha = ((color >>> 24) & 255) / 255.0F;
        if (alpha <= 0.0F) {
            alpha = 1.0F;
        }
        float red = ((color >>> 16) & 255) / 255.0F;
        float green = ((color >>> 8) & 255) / 255.0F;
        float blue = (color & 255) / 255.0F;
        RenderSystem.setShaderColor(red, green, blue, alpha);
    }

    public static String plainText(OrderedText orderedText) {
        StringBuilder builder = new StringBuilder();
        orderedText.accept((index, style, codePoint) -> {
            builder.appendCodePoint(codePoint);
            return true;
        });
        return builder.toString();
    }
}
