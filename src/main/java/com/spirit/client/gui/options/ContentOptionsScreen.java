package com.spirit.client.gui.options;

import com.spirit.client.gui.mod.ModMenuScreen;
import com.spirit.client.gui.shader.ShaderPackMenuScreen;
import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.awt.*;
import java.nio.file.Path;

import static com.spirit.koil.api.design.uiColorVal.uiColorHeaderSubTitleText;
import static com.spirit.koil.api.design.uiColorVal.uiColorHeaderTitleText;

public class ContentOptionsScreen extends Screen {
    private final Screen parent;

    public ContentOptionsScreen(Screen parent) {
        super(Text.literal("Content"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int leftX = this.width / 2 - 155;
        int rightX = this.width / 2 + 5;
        int y = this.height / 6 + 24;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Resource Packs"), button -> openResourcePackManager()).dimensions(leftX, y, 150, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Mods"), button -> this.client.setScreen(new ModMenuScreen(this))).dimensions(rightX, y, 150, 20).build());
        y += 24;
        if (WorldDatapackScreenHelper.canOpenDatapackManager()) {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Data Packs"), button -> WorldDatapackScreenHelper.open(this)).dimensions(leftX, y, 150, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Shaders"), button -> this.client.setScreen(new ShaderPackMenuScreen(this))).dimensions(rightX, y, 150, 20).build());
        } else {
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Shaders"), button -> this.client.setScreen(new ShaderPackMenuScreen(this))).dimensions(leftX, y, 150, 20).build());
        }
        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> this.client.setScreen(this.parent)).dimensions(this.width / 2 - 100, this.height / 6 + 120, 200, 20).build());
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MinecraftClient client = MinecraftClient.getInstance();
        KoilVanillaScreenChrome.renderOptionsShell(context, client, this.width, this.height);
        context.getMatrices().push();
        context.getMatrices().scale(1.5F, 1.5F, 1.0F);
        context.drawText(this.textRenderer, Text.literal("Options"), 25, 3, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, Text.literal("Content"), 37, 18, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
        super.render(context, mouseX, mouseY, delta);
    }

    private void openResourcePackManager() {
        if (this.client == null) {
            return;
        }
        MinecraftClient minecraft = this.client;
        Path packDir = minecraft.getResourcePackDir();
        minecraft.setScreen(new PackScreen(minecraft.getResourcePackManager(), manager -> {
            minecraft.options.refreshResourcePacks(manager);
            minecraft.setScreen(this);
        }, packDir, Text.translatable("resourcePack.title")));
    }
}
