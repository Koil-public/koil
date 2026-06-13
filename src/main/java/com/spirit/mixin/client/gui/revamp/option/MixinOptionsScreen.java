package com.spirit.mixin.client.gui.revamp.option;

import com.spirit.client.gui.options.ContentOptionsScreen;
import com.spirit.client.gui.options.WorldDatapackScreenHelper;
import com.spirit.client.gui.mod.ModMenuScreen;
import com.spirit.client.gui.skin.ChangeSkinScreen;
import com.spirit.client.gui.skin.EditSkinScreen;
import com.spirit.client.gui.shader.ShaderPackMenuScreen;
import com.spirit.client.gui.tool.PerformanceOptimizerScreen;
import com.spirit.client.gui.video.KoilVideoOptionsScreen;
import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.text.TranslatableTextContent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
@Mixin(OptionsScreen.class)
public class MixinOptionsScreen extends Screen {
    private static final String RESOURCE_PACK_TRANSLATION_KEY = "options.resourcepack";
    private static final String SKIN_CUSTOMIZATION_TRANSLATION_KEY = "options.skinCustomisation";
    private static final String VIDEO_OPTIONS_TRANSLATION_KEY = "options.video";

    protected MixinOptionsScreen(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void koil$replaceResourcePackButton(CallbackInfo ci) {
        if (!JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            return;
        }
        List<Element> snapshot = new ArrayList<>(this.children());
        for (Element child : snapshot) {
            if (!(child instanceof ButtonWidget button)) {
                continue;
            }
            if (!koil$isResourcePackButton(button)) {
                continue;
            }
            int x = button.getX();
            int y = button.getY();
            int width = button.getWidth();
            int height = button.getHeight();
            this.remove(button);
            if (!koil$canFitDirectContentCluster(width)) {
                this.addDrawableChild(ButtonWidget.builder(Text.literal("Content"), pressed -> {
                    if (this.client != null) {
                        this.client.setScreen(new ContentOptionsScreen(this));
                    }
                }).dimensions(x, y, width, height).build());
                return;
            }
            koil$addContentButtons(x, y, width, height);
            return;
        }
        koil$replaceSkinCustomizationButton(snapshot);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void koil$replaceSkinCustomizationButton(CallbackInfo ci) {
        if (!JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            return;
        }
        koil$replaceSkinCustomizationButton(new ArrayList<>(this.children()));
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void koil$replaceVideoOptionsButton(CallbackInfo ci) {
        if (!JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            return;
        }
        koil$replaceVideoOptionsButton(new ArrayList<>(this.children()));
    }

    private void koil$replaceSkinCustomizationButton(List<Element> snapshot) {
        for (Element child : snapshot) {
            if (!(child instanceof ButtonWidget button)) {
                continue;
            }
            if (!koil$isSkinCustomizationButton(button)) {
                continue;
            }
            int x = button.getX();
            int y = button.getY();
            int width = button.getWidth();
            int height = button.getHeight();
            this.remove(button);
            int gap = 2;
            int half = Math.max(1, (width - gap) / 2);
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Change Skin"), pressed -> {
                if (this.client != null) {
                    this.client.setScreen(new ChangeSkinScreen(this));
                }
            }).dimensions(x, y, half, height).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Edit Skin"), pressed -> {
                if (this.client != null) {
                    this.client.setScreen(new EditSkinScreen(this));
                }
            }).dimensions(x + half + gap, y, Math.max(1, x + width - (x + half + gap)), height).build());
            return;
        }
    }

    private boolean koil$canFitDirectContentCluster(int width) {
        return width >= 150;
    }

    private boolean koil$isResourcePackButton(ButtonWidget button) {
        String key = koil$translationKey(button.getMessage());
        if (RESOURCE_PACK_TRANSLATION_KEY.equals(key)) {
            return true;
        }
        String label = button.getMessage().getString();
        if (label == null) {
            return false;
        }
        String normalized = label.toLowerCase().replace(".", "").replace("…", "").trim();
        return normalized.equals("resource packs")
                || normalized.equals("resource pack")
                || normalized.contains("resource packs")
                || normalized.contains("resource pack");
    }

    private boolean koil$isSkinCustomizationButton(ButtonWidget button) {
        String key = koil$translationKey(button.getMessage());
        if (SKIN_CUSTOMIZATION_TRANSLATION_KEY.equals(key)) {
            return true;
        }
        String label = button.getMessage().getString();
        if (label == null) {
            return false;
        }
        String normalized = label.toLowerCase().replace(".", "").replace("…", "").trim();
        return normalized.equals("skin customization")
                || normalized.equals("skin customisation")
                || normalized.contains("skin customization")
                || normalized.contains("skin customisation");
    }

    private void koil$replaceVideoOptionsButton(List<Element> snapshot) {
        for (Element child : snapshot) {
            if (!(child instanceof ButtonWidget button)) {
                continue;
            }
            if (!koil$isVideoOptionsButton(button)) {
                continue;
            }
            int x = button.getX();
            int y = button.getY();
            int width = button.getWidth();
            int height = button.getHeight();
            this.remove(button);
            int gap = 2;
            int performanceWidth = Math.min(width - gap - 38, Math.max(70, Math.round(width * 0.66F)));
            int videoWidth = Math.max(1, width - gap - performanceWidth);
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Video"), pressed -> koil$openVideoOptions()).dimensions(x, y, videoWidth, height).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Performance"), pressed -> {
                if (this.client != null) {
                    this.client.setScreen(new PerformanceOptimizerScreen(this));
                }
            }).dimensions(x + videoWidth + gap, y, Math.max(1, x + width - (x + videoWidth + gap)), height).build());
            return;
        }
    }

    private boolean koil$isVideoOptionsButton(ButtonWidget button) {
        String key = koil$translationKey(button.getMessage());
        if (VIDEO_OPTIONS_TRANSLATION_KEY.equals(key)) {
            return true;
        }
        String label = button.getMessage().getString();
        if (label == null) {
            return false;
        }
        String normalized = label.toLowerCase().replace(".", "").replace("…", "").trim();
        return normalized.equals("video settings")
                || normalized.equals("video")
                || normalized.contains("video settings");
    }

    private String koil$translationKey(Text text) {
        if (text == null) {
            return "";
        }
        TextContent content = text.getContent();
        if (content instanceof TranslatableTextContent translatable) {
            return translatable.getKey();
        }
        return "";
    }

    private void koil$openResourcePackManager() {
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

    private void koil$openVideoOptions() {
        if (this.client == null) {
            return;
        }
        this.client.setScreen(new KoilVideoOptionsScreen(this));
    }

    /**
     * @author SpiritXIV
     * @reason to make it look better
     */
    @Overwrite
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {

            MinecraftClient client = MinecraftClient.getInstance();
            KoilVanillaScreenChrome.renderOptionsShell(context, client, this.width, this.height);


            context.getMatrices().push();
            context.getMatrices().scale(1.5F, 1.5F, 1.0F);
            context.drawText(this.textRenderer, Text.literal("Options"), 25, 3, new Color(uiColorHeaderTitleText, true).getRGB(), true);
            context.getMatrices().pop();
            super.render(context, mouseX, mouseY, delta);
        } else {
            this.renderBackground(context);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 16777215);
            super.render(context, mouseX, mouseY, delta);
        }
    }

    private void koil$addContentButtons(int x, int y, int width, int height) {
        List<ContentButtonSpec> specs = new ArrayList<>();
        specs.add(new ContentButtonSpec("Packs", 40, this::koil$openResourcePackManager));
        specs.add(new ContentButtonSpec("Mods", 36, () -> {
            if (this.client != null) {
                this.client.setScreen(new ModMenuScreen(this));
            }
        }));
        if (WorldDatapackScreenHelper.canOpenDatapackManager()) {
            specs.add(new ContentButtonSpec("Data", 34, () -> {
                WorldDatapackScreenHelper.open(this);
            }));
        }
        specs.add(new ContentButtonSpec("Shaders", 56, () -> {
            if (this.client != null) {
                this.client.setScreen(new ShaderPackMenuScreen(this));
            }
        }));
        int gap = 2;
        int totalWeight = specs.stream().mapToInt(ContentButtonSpec::weight).sum();
        int available = width - gap * (specs.size() - 1);
        int currentX = x;
        int remainingWidth = available;
        int remainingWeight = totalWeight;
        for (int i = 0; i < specs.size(); i++) {
            ContentButtonSpec spec = specs.get(i);
            int buttonWidth = i == specs.size() - 1 ? Math.max(1, x + width - currentX) : Math.max(1, Math.round(remainingWidth * (spec.weight() / (float) remainingWeight)));
            this.addDrawableChild(ButtonWidget.builder(Text.literal(spec.label()), pressed -> spec.action().run()).dimensions(currentX, y, buttonWidth, height).build());
            currentX += buttonWidth + gap;
            remainingWidth -= buttonWidth;
            remainingWeight -= spec.weight();
        }
    }

    private record ContentButtonSpec(String label, int weight, Runnable action) {
    }
}
