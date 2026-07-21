package com.spirit.client.gui.measure;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public final class PixelDifferenceOverlay {
    private static boolean armed;
    private static boolean measuring;
    private static boolean pinned;
    private static int startX;
    private static int startY;
    private static int endX;
    private static int endY;

    private PixelDifferenceOverlay() {
    }

    public static void arm() {
        armed = true;
    }

    public static boolean active() {
        return armed || measuring || pinned;
    }

    public static boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!armed || button != 0) {
            return false;
        }
        startX = (int) Math.round(mouseX);
        startY = (int) Math.round(mouseY);
        endX = startX;
        endY = startY;
        measuring = true;
        pinned = false;
        return true;
    }

    public static boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (!measuring || button != 0) {
            return false;
        }
        endX = (int) Math.round(mouseX);
        endY = (int) Math.round(mouseY);
        return true;
    }

    public static boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!measuring || button != 0) {
            return false;
        }
        endX = (int) Math.round(mouseX);
        endY = (int) Math.round(mouseY);
        measuring = false;
        armed = false;
        pinned = Screen.hasShiftDown();
        if (!pinned) {
            clear();
        }
        return true;
    }

    public static boolean keyPressed(int keyCode) {
        if (!active() || keyCode != GLFW.GLFW_KEY_ESCAPE) {
            return false;
        }
        clear();
        return true;
    }

    public static void clearForRemovedScreen() {
        armed = false;
        measuring = false;
    }

    public static void render(DrawContext context, int mouseX, int mouseY) {
        if (armed && !measuring && !pinned) {
            renderArmedHint(context, mouseX, mouseY);
            return;
        }
        if (!measuring && !pinned) {
            return;
        }
        int x2 = measuring ? mouseX : endX;
        int y2 = measuring ? mouseY : endY;
        renderMeasurement(context, startX, startY, x2, y2, measuring);
    }

    private static void renderArmedHint(DrawContext context, int mouseX, int mouseY) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null) {
            return;
        }
        String text = "Pixel Difference: drag to measure • Esc to close";
        int width = client.textRenderer.getWidth(text) + 10;
        int x = Math.max(6, Math.min(mouseX + 10, client.getWindow().getScaledWidth() - width - 6));
        int y = Math.max(6, Math.min(mouseY + 12, client.getWindow().getScaledHeight() - 20));
        context.getMatrices().push();
        context.getMatrices().translate(0.0F, 0.0F, 5000.0F);
        context.fill(x + 1, y + 1, x + width + 1, y + 17, 0x90000000);
        context.fill(x, y, x + width, y + 16, 0xEA10151C);
        context.drawBorder(x, y, width, 16, 0xFFC7D2E4);
        context.drawText(client.textRenderer, text, x + 5, y + 4, 0xFFE9F1FF, false);
        context.getMatrices().pop();
    }

    private static void renderMeasurement(DrawContext context, int x1, int y1, int x2, int y2, boolean live) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null) {
            return;
        }
        int dx = x2 - x1;
        int dy = y2 - y1;
        int absX = Math.abs(dx);
        int absY = Math.abs(dy);
        int distance = (int) Math.round(Math.sqrt(dx * dx + dy * dy));
        String label = "ΔX " + absX + " px  ΔY " + absY + " px  D " + distance + " px";
        int midX = (x1 + x2) / 2;
        int midY = (y1 + y2) / 2;
        int labelWidth = client.textRenderer.getWidth(label) + 10;
        int perpendicularX = dy == 0 ? 0 : (dy > 0 ? 1 : -1) * 12;
        int perpendicularY = dx == 0 ? -18 : (dx > 0 ? -18 : 18);
        int labelX = Math.max(6, Math.min(midX - labelWidth / 2 + perpendicularX, client.getWindow().getScaledWidth() - labelWidth - 6));
        int labelY = Math.max(6, Math.min(midY + perpendicularY, client.getWindow().getScaledHeight() - 20));
        int lineColor = live ? 0xFFE7F1FF : 0xFF79D0FF;
        context.getMatrices().push();
        context.getMatrices().translate(0.0F, 0.0F, 5000.0F);
        drawLine(context, x1, y1, x2, y2, 0x98000000, 2);
        drawLine(context, x1, y1, x2, y2, lineColor, 1);
        drawTapeTicks(context, x1, y1, x2, y2);
        drawEndpoint(context, x1, y1, 0xFFFFD166);
        drawEndpoint(context, x2, y2, live ? 0xFF8BF779 : 0xFF79D0FF);
        drawMidpoint(context, midX, midY);
        context.fill(labelX + 1, labelY + 1, labelX + labelWidth + 1, labelY + 17, 0x95000000);
        context.fill(labelX, labelY, labelX + labelWidth, labelY + 16, 0xEE10151C);
        context.drawBorder(labelX, labelY, labelWidth, 16, lineColor);
        context.drawText(client.textRenderer, label, labelX + 5, labelY + 4, 0xFFF7FAFF, false);
        context.getMatrices().pop();
    }

    private static void drawEndpoint(DrawContext context, int x, int y, int color) {
        context.fill(x - 2, y - 2, x + 3, y + 3, 0xC0000000);
        context.fill(x - 1, y - 1, x + 2, y + 2, color);
        context.fill(x, y - 3, x + 1, y + 4, color);
        context.fill(x - 3, y, x + 4, y + 1, color);
    }

    private static void drawMidpoint(DrawContext context, int x, int y) {
        context.fill(x - 2, y - 2, x + 3, y + 3, 0xFFEDF6FF);
        context.fill(x - 1, y - 1, x + 2, y + 2, 0xFF162231);
    }

    private static void drawTapeTicks(DrawContext context, int x1, int y1, int x2, int y2) {
        double distance = Math.hypot(x2 - x1, y2 - y1);
        if (distance < 10.0D) {
            return;
        }
        int tickCount = Math.max(1, (int) distance / 8);
        for (int tick = 1; tick < tickCount; tick++) {
            float progress = tick / (float) tickCount;
            int x = Math.round(x1 + (x2 - x1) * progress);
            int y = Math.round(y1 + (y2 - y1) * progress);
            int color = tick % 5 == 0 ? 0xFFE7F1FF : 0x6695BAD7;
            int radius = tick % 5 == 0 ? 1 : 0;
            context.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, color);
        }
    }

    private static void drawLine(DrawContext context, int x1, int y1, int x2, int y2, int color, int thickness) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int error = dx - dy;
        int x = x1;
        int y = y1;
        int radius = Math.max(0, thickness / 2);
        while (true) {
            context.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, color);
            if (x == x2 && y == y2) {
                break;
            }
            int doubleError = 2 * error;
            if (doubleError > -dy) {
                error -= dy;
                x += sx;
            }
            if (doubleError < dx) {
                error += dx;
                y += sy;
            }
        }
    }

    private static void clear() {
        armed = false;
        measuring = false;
        pinned = false;
    }
}
