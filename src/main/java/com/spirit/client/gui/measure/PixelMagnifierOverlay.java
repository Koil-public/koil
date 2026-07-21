package com.spirit.client.gui.measure;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/**
 * A small framebuffer-backed screen inspector.  It intentionally samples
 * before measurement overlays render, so other Koil tools never become part
 * of the inspected pixels.
 */
@Environment(EnvType.CLIENT)
public final class PixelMagnifierOverlay {
    private static final int[] ZOOM_STEPS = {2, 4, 8, 16, 32};
    private static final int PANEL_SIZE = 132;
    private static boolean active;
    private static int zoomIndex = 2;

    private PixelMagnifierOverlay() {
    }

    public static void arm() {
        active = true;
    }

    public static boolean active() {
        return active;
    }

    public static void clear() {
        active = false;
    }

    public static boolean keyPressed(int keyCode) {
        if (!active || keyCode != GLFW.GLFW_KEY_ESCAPE) {
            return false;
        }
        clear();
        return true;
    }

    public static boolean mouseScrolled(double amount) {
        if (!active || amount == 0.0D) {
            return false;
        }
        if (amount > 0.0D) {
            zoomIndex = Math.min(ZOOM_STEPS.length - 1, zoomIndex + 1);
        } else {
            zoomIndex = Math.max(0, zoomIndex - 1);
        }
        return true;
    }

    /** Any content click dismisses this inspection-only tool. */
    public static void mouseClicked() {
        clear();
    }

    public static void render(DrawContext context, int mouseX, int mouseY) {
        if (!active) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null || client.getWindow() == null) {
            return;
        }
        int cell = ZOOM_STEPS[zoomIndex];
        int sampleSize = Math.max(3, PANEL_SIZE / cell);
        ByteBuffer pixels = readPixels(client, mouseX, mouseY, sampleSize);
        if (pixels == null) {
            return;
        }
        int gridSize = sampleSize * cell;
        int panelX = Math.max(6, Math.min(mouseX + 15, client.getWindow().getScaledWidth() - PANEL_SIZE - 8));
        int panelY = Math.max(6, Math.min(mouseY + 15, client.getWindow().getScaledHeight() - PANEL_SIZE - 8));
        int gridX = panelX + (PANEL_SIZE - gridSize) / 2;
        int gridY = panelY + (PANEL_SIZE - gridSize) / 2;

        context.getMatrices().push();
        context.getMatrices().translate(0.0F, 0.0F, 4990.0F);
        context.fill(panelX - 3, panelY - 3, panelX + PANEL_SIZE + 3, panelY + PANEL_SIZE + 3, 0xE810151C);
        context.drawBorder(panelX - 3, panelY - 3, PANEL_SIZE + 6, PANEL_SIZE + 6, 0xFFBFCDE0);
        for (int row = 0; row < sampleSize; row++) {
            for (int col = 0; col < sampleSize; col++) {
                int index = (row * sampleSize + col) * 4;
                int red = pixels.get(index) & 0xFF;
                int green = pixels.get(index + 1) & 0xFF;
                int blue = pixels.get(index + 2) & 0xFF;
                int alpha = pixels.get(index + 3) & 0xFF;
                int color = (alpha << 24) | (red << 16) | (green << 8) | blue;
                int x = gridX + col * cell;
                int y = gridY + (sampleSize - 1 - row) * cell;
                context.fill(x, y, x + cell, y + cell, color);
            }
        }
        int center = (sampleSize / 2) * cell;
        context.drawBorder(gridX + center, gridY + center, cell, cell, 0xFFFFFFFF);
        context.getMatrices().pop();
    }

    private static ByteBuffer readPixels(MinecraftClient client, int mouseX, int mouseY, int sampleSize) {
        try {
            double scale = client.getWindow().getScaleFactor();
            int centerX = Math.round((float) (mouseX * scale));
            int centerY = Math.round((float) (mouseY * scale));
            int framebufferWidth = client.getWindow().getFramebufferWidth();
            int framebufferHeight = client.getWindow().getFramebufferHeight();
            int radius = sampleSize / 2;
            int readX = Math.max(0, Math.min(framebufferWidth - sampleSize, centerX - radius));
            int readY = Math.max(0, Math.min(framebufferHeight - sampleSize, framebufferHeight - centerY - radius));
            ByteBuffer pixels = BufferUtils.createByteBuffer(sampleSize * sampleSize * 4);
            client.getFramebuffer().beginRead();
            RenderSystem.assertOnRenderThread();
            GL11.glReadPixels(readX, readY, sampleSize, sampleSize, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            client.getFramebuffer().endRead();
            return pixels;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
