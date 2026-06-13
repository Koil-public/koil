package com.spirit.client.gui.main.elements;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

public class ClickableImage {
    private final Identifier texture;
    private final int x, y, width, height;
    private final Runnable onClick;

    public ClickableImage(Identifier texture, int x, int y, int width, int height, Runnable onClick) {
        this.texture = texture;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.onClick = onClick;
    }

    public void render(DrawContext context, int mouseX, int mouseY) {
        MinecraftClient client = MinecraftClient.getInstance();
        context.drawTexture(texture, x, y, 0, 0, width, height, width, height);
    }

    public boolean isMouseOver(int mouseX, int mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    public void onClick() {
        onClick.run();
    }
}
