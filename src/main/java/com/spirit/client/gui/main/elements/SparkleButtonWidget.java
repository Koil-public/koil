package com.spirit.client.gui.main.elements;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TexturedButtonWidget;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;

public class SparkleButtonWidget extends TexturedButtonWidget {
    private final Random sparkleRandom = Random.createLocal();
    private static final int[] GLITTER_COLORS = {
            0xFFE8A3,
            0xFFD86B,
            0xFFF3C7,
            0xFFC94D
    };

    public SparkleButtonWidget(int x, int y, int width, int height, int u, int v, int hoveredV,
                               Identifier texture, int textureWidth, int textureHeight,
                               PressAction onPress) {
        super(x, y, width, height, u, v, hoveredV, texture, textureWidth, textureHeight, onPress);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        if (this.isHovered()) {
            long now = System.currentTimeMillis();
            int sparkleCount = 10;
            int effectLeft = this.getX() - 6;
            int effectWidth = this.getWidth() + 12;
            int effectTop = this.getY() - 12;
            int fallDistance = this.getHeight() + 24;
            for (int i = 0; i < sparkleCount; i++) {
                double phase = (now / (360.0 + (i * 35.0))) + (i * 0.67);
                int laneX = effectLeft + ((i * 7) % Math.max(8, effectWidth));
                int sparkleX = laneX + (int) Math.round(Math.sin(phase * 1.1) * 2.0);
                int fall = (int) ((((now / (26.0 + i * 2.0)) + (i * 9.0))) % Math.max(1, fallDistance));
                int sparkleY = effectTop + fall;
                float fade = 1.0f - Math.min(1.0f, fall / (float) Math.max(1, fallDistance - 1));
                int alpha = Math.max(36, Math.round((120 + 80 * (float) Math.abs(Math.sin(phase * 1.35))) * fade));
                int color = withAlpha(GLITTER_COLORS[i % GLITTER_COLORS.length], alpha);
                drawSparkle(context, sparkleX, sparkleY, color, alpha);
            }

            int glowAlpha = 38 + MathHelper.nextInt(sparkleRandom, 0, 22);
            context.fill(this.getX() - 2, this.getY() - 2, this.getX() + this.getWidth() + 2, this.getY() + this.getHeight() + 2, withAlpha(0xF7D27A, glowAlpha));
            context.drawBorder(this.getX() - 1, this.getY() - 1, this.getWidth() + 2, this.getHeight() + 2, withAlpha(0xFFF1B8, 150));
        }
    }

    private void drawSparkle(DrawContext context, int x, int y, int color, int alpha) {
        context.fill(x, y, x + 2, y + 2, color);
        context.fill(x - 1, y + 1, x + 3, y + 2, withAlpha(0xFFFFFF, alpha / 4));
        context.fill(x + 1, y - 1, x + 2, y + 3, withAlpha(0xFFFFFF, alpha / 4));
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00FFFFFF);
    }
}
