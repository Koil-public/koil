package com.spirit.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.awt.Color;
import java.util.function.IntConsumer;

import static com.spirit.koil.api.design.uiColorVal.*;

public final class ColorPickerPopup {
    private static final int WIDTH = 96;
    private static final int HEIGHT = 92;
    private static final int PALETTE_WIDTH = 84;
    private static final int PALETTE_HEIGHT = 42;
    private int x;
    private int y;
    private int paletteX;
    private int paletteY;
    private int alphaX;
    private int alphaY;
    private int brightnessX;
    private int brightnessY;
    private boolean open;
    private boolean draggingPalette;
    private boolean draggingAlpha;
    private boolean draggingBrightness;
    private float hue;
    private float saturation;
    private float brightness;
    private int alpha;
    private IntConsumer onChanged;

    public void open(int mouseX, int mouseY, int screenWidth, int screenHeight, int initialColor, IntConsumer onChanged) {
        this.onChanged = onChanged;
        this.alpha = (initialColor >>> 24) & 255;
        int red = (initialColor >>> 16) & 255;
        int green = (initialColor >>> 8) & 255;
        int blue = initialColor & 255;
        float[] hsb = Color.RGBtoHSB(red, green, blue, null);
        this.hue = hsb[0];
        this.saturation = hsb[1];
        this.brightness = hsb[2];
        this.x = Math.max(8, Math.min(mouseX + 8, screenWidth - WIDTH - 8));
        this.y = Math.max(8, Math.min(mouseY - 8, screenHeight - HEIGHT - 8));
        this.open = true;
        this.draggingPalette = false;
        this.draggingAlpha = false;
        this.draggingBrightness = false;
    }

    public boolean isOpen() {
        return this.open;
    }

    public void render(DrawContext context) {
        if (!this.open) {
            return;
        }
        this.paletteX = this.x + 6;
        this.paletteY = this.y + 18;
        this.alphaX = this.paletteX;
        this.alphaY = this.paletteY + PALETTE_HEIGHT + 7;
        this.brightnessX = this.paletteX;
        this.brightnessY = this.alphaY + 13;
        context.getMatrices().push();
        context.getMatrices().translate(0.0F, 0.0F, 500.0F);
        context.fill(this.x, this.y, this.x + WIDTH, this.y + HEIGHT, withAlpha(uiColorContentBase, 236));
        context.drawBorder(this.x, this.y, WIDTH, HEIGHT, withAlpha(uiColorBackgroundBorder, 230));
        context.fill(this.x, this.y, this.x + WIDTH, this.y + 14, withAlpha(uiColorHeader, 150));
        context.drawText(MinecraftClient.getInstance().textRenderer, "Color", this.x + 6, this.y + 4, withAlpha(uiColorContentBaseTitleText, 255), false);
        drawPalette(context);
        drawAlphaBar(context);
        drawBrightnessBar(context);
        int preview = selectedColor();
        context.fill(this.x + WIDTH - 19, this.y + 3, this.x + WIDTH - 7, this.y + 12, preview);
        context.drawBorder(this.x + WIDTH - 19, this.y + 3, 12, 9, withAlpha(uiColorBackgroundBorder, 230));
        context.getMatrices().pop();
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.open || button != 0) {
            return false;
        }
        if (inside(mouseX, mouseY, this.paletteX, this.paletteY, this.paletteX + PALETTE_WIDTH, this.paletteY + PALETTE_HEIGHT)) {
            this.draggingPalette = true;
            applyPalette(mouseX, mouseY);
            return true;
        }
        if (inside(mouseX, mouseY, this.alphaX, this.alphaY - 1, this.alphaX + PALETTE_WIDTH, this.alphaY + 8)) {
            this.draggingAlpha = true;
            applyAlpha(mouseX);
            return true;
        }
        if (inside(mouseX, mouseY, this.brightnessX, this.brightnessY - 1, this.brightnessX + PALETTE_WIDTH, this.brightnessY + 8)) {
            this.draggingBrightness = true;
            applyBrightness(mouseX);
            return true;
        }
        if (inside(mouseX, mouseY, this.x, this.y, this.x + WIDTH, this.y + HEIGHT)) {
            return true;
        }
        this.open = false;
        return true;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (!this.open || button != 0) {
            return false;
        }
        if (this.draggingPalette) {
            applyPalette(mouseX, mouseY);
            return true;
        }
        if (this.draggingAlpha) {
            applyAlpha(mouseX);
            return true;
        }
        if (this.draggingBrightness) {
            applyBrightness(mouseX);
            return true;
        }
        return false;
    }

    public boolean mouseReleased() {
        boolean wasDragging = this.draggingPalette || this.draggingAlpha || this.draggingBrightness;
        this.draggingPalette = false;
        this.draggingAlpha = false;
        this.draggingBrightness = false;
        return wasDragging;
    }

    private void drawPalette(DrawContext context) {
        for (int px = 0; px < PALETTE_WIDTH; px++) {
            float hueValue = px / (float) Math.max(1, PALETTE_WIDTH - 1);
            for (int py = 0; py < PALETTE_HEIGHT; py++) {
                float saturationValue = 1.0F - py / (float) Math.max(1, PALETTE_HEIGHT - 1);
                int rgb = Color.HSBtoRGB(hueValue, saturationValue, this.brightness);
                context.fill(this.paletteX + px, this.paletteY + py, this.paletteX + px + 1, this.paletteY + py + 1, 0xFF000000 | (rgb & 0xFFFFFF));
            }
        }
        context.drawBorder(this.paletteX, this.paletteY, PALETTE_WIDTH, PALETTE_HEIGHT, withAlpha(uiColorBackgroundBorder, 230));
        int selectorX = this.paletteX + Math.round(this.hue * (PALETTE_WIDTH - 1));
        int selectorY = this.paletteY + Math.round((1.0F - this.saturation) * (PALETTE_HEIGHT - 1));
        context.drawBorder(selectorX - 2, selectorY - 2, 5, 5, withAlpha(uiColorContentBaseTitleText, 255));
    }

    private void drawAlphaBar(DrawContext context) {
        int rgb = selectedColor() & 0x00FFFFFF;
        for (int px = 0; px < PALETTE_WIDTH; px++) {
            int checker = ((px / 4) & 1) == 0 ? 0xFFB8B8B8 : 0xFF7A7A7A;
            context.fill(this.alphaX + px, this.alphaY, this.alphaX + px + 1, this.alphaY + 6, checker);
            int alphaValue = Math.round(px / (float) Math.max(1, PALETTE_WIDTH - 1) * 255.0F);
            context.fill(this.alphaX + px, this.alphaY, this.alphaX + px + 1, this.alphaY + 6, (alphaValue << 24) | rgb);
        }
        context.drawBorder(this.alphaX, this.alphaY, PALETTE_WIDTH, 6, withAlpha(uiColorBackgroundBorder, 230));
        int selectorX = this.alphaX + Math.round(this.alpha / 255.0F * (PALETTE_WIDTH - 1));
        context.fill(selectorX - 1, this.alphaY - 2, selectorX + 1, this.alphaY + 8, withAlpha(uiColorContentBaseTitleText, 255));
    }

    private void drawBrightnessBar(DrawContext context) {
        for (int px = 0; px < PALETTE_WIDTH; px++) {
            int value = Math.round(px / (float) Math.max(1, PALETTE_WIDTH - 1) * 255.0F);
            int color = 0xFF000000 | (value << 16) | (value << 8) | value;
            context.fill(this.brightnessX + px, this.brightnessY, this.brightnessX + px + 1, this.brightnessY + 6, color);
        }
        context.drawBorder(this.brightnessX, this.brightnessY, PALETTE_WIDTH, 6, withAlpha(uiColorBackgroundBorder, 230));
        int selectorX = this.brightnessX + Math.round(this.brightness * (PALETTE_WIDTH - 1));
        context.fill(selectorX - 1, this.brightnessY - 2, selectorX + 1, this.brightnessY + 8, withAlpha(uiColorContentBaseTitleText, 255));
    }

    private void applyPalette(double mouseX, double mouseY) {
        this.hue = (float) clamp((mouseX - this.paletteX) / Math.max(1.0D, PALETTE_WIDTH - 1));
        this.saturation = 1.0F - (float) clamp((mouseY - this.paletteY) / Math.max(1.0D, PALETTE_HEIGHT - 1));
        publish();
    }

    private void applyAlpha(double mouseX) {
        this.alpha = Math.max(0, Math.min(255, (int) Math.round(clamp((mouseX - this.alphaX) / Math.max(1.0D, PALETTE_WIDTH - 1)) * 255.0D)));
        publish();
    }

    private void applyBrightness(double mouseX) {
        this.brightness = (float) clamp((mouseX - this.brightnessX) / Math.max(1.0D, PALETTE_WIDTH - 1));
        publish();
    }

    private void publish() {
        if (this.onChanged != null) {
            this.onChanged.accept(selectedColor());
        }
    }

    private int selectedColor() {
        int rgb = Color.HSBtoRGB(this.hue, this.saturation, this.brightness);
        return (this.alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static int withAlpha(int argbColor, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argbColor & 0x00FFFFFF);
    }

    private static boolean inside(double mouseX, double mouseY, int left, int top, int right, int bottom) {
        return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
    }
}
