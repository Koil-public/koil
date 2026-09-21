package com.spirit.koil.api.design.particle;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Transparent pointer-capture layer for interacting with an active Koil scene
 * while no normal Minecraft GUI is open.
 *
 * <p>The layer intentionally does not draw a background and never pauses the
 * game. Holding Alt gives the scene a free mouse cursor; releasing Alt returns
 * control to normal Minecraft input.</p>
 */
final class SpriteScenePointerScreen extends Screen {
    SpriteScenePointerScreen() {
        super(Text.literal("Koil Scene Pointer"));
    }

    @Override
    public void tick() {
        if (!altHeld()) close();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!altHeld()) {
            close();
            return;
        }
        context.drawTextWithShadow(this.textRenderer,
                Text.literal("Scene Mouse  |  release Alt to return"),
                6, Math.max(6, this.height - 14), 0xFFB7C0CF);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        MinecraftClient minecraft = this.client;
        if (minecraft != null && minecraft.currentScreen == this) minecraft.setScreen(null);
    }

    private boolean altHeld() {
        MinecraftClient minecraft = this.client;
        if (minecraft == null || minecraft.getWindow() == null) return false;
        long window = minecraft.getWindow().getHandle();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }
}
