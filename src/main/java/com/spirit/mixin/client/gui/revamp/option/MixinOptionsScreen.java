package com.spirit.mixin.client.gui.revamp.option;

import com.spirit.client.gui.options.ContentOptionsScreen;
import com.spirit.client.gui.options.WorldDatapackScreenHelper;
import com.spirit.client.gui.mod.ModMenuScreen;
import com.spirit.client.gui.macro.MacroScreen;
import com.spirit.client.gui.skin.ChangeSkinScreen;
import com.spirit.client.gui.skin.EditSkinScreen;
import com.spirit.client.gui.shader.ShaderPackMenuScreen;
import com.spirit.client.gui.performance.PerformanceOptimizerScreen;
import com.spirit.client.gui.video.KoilVideoOptionsScreen;
import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.CreditsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.gui.screen.option.MouseOptionsScreen;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.client.option.GameOptions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
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
    private static final String CONTROLS_TRANSLATION_KEY = "options.controls";
    private static final String CREDITS_AND_ATTRIBUTION_TRANSLATION_KEY = "options.credits_and_attribution";
    private static final String ATTRIBUTION_URL = "https://aka.ms/MinecraftJavaAttribution";
    private static final String LICENSES_URL = "https://aka.ms/MinecraftJavaLicenses";

    @Shadow @Final private GameOptions settings;

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

    @Inject(method = "init", at = @At("TAIL"))
    private void koil$replaceControlsButton(CallbackInfo ci) {
        if (!JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            return;
        }
        for (Element child : new ArrayList<>(this.children())) {
            if (!(child instanceof ButtonWidget button) || !koil$isControlsButton(button)) {
                continue;
            }
            int x = button.getX();
            int y = button.getY();
            int width = button.getWidth();
            int height = button.getHeight();
            this.remove(button);
            int gap = 2;
            int available = Math.max(3, width - gap * 2);
            int mouseWidth = Math.max(1, Math.round(available * 0.29F));
            int keybindWidth = Math.max(1, Math.round(available * 0.39F));
            int macroWidth = Math.max(1, width - mouseWidth - keybindWidth - gap * 2);
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Mouse"), pressed -> {
                if (this.client != null) {
                    this.client.setScreen(new MouseOptionsScreen(this, this.settings));
                }
            }).dimensions(x, y, mouseWidth, height).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Keybinds"), pressed -> {
                if (this.client != null) {
                    this.client.setScreen(new KeybindsScreen(this, this.settings));
                }
            }).dimensions(x + mouseWidth + gap, y, keybindWidth, height).build());
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Macros"), pressed -> {
                if (this.client != null) {
                    this.client.setScreen(new MacroScreen(this));
                }
            }).dimensions(x + mouseWidth + gap + keybindWidth + gap, y, macroWidth, height).build());
            return;
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void koil$replaceCreditsAndAttributionButton(CallbackInfo ci) {
        if (!JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            return;
        }
        for (Element child : new ArrayList<>(this.children())) {
            if (!(child instanceof ButtonWidget button) || !koil$isCreditsAndAttributionButton(button)) {
                continue;
            }
            int x = button.getX();
            int y = button.getY();
            int width = button.getWidth();
            int height = button.getHeight();
            this.remove(button);

            int gap = 2;
            int available = Math.max(3, width - gap * 2);
            int creditsWidth = Math.max(1, Math.round(available * 0.34F));
            int attributionWidth = Math.max(1, Math.round(available * 0.49F));
            int licensesWidth = Math.max(1, width - creditsWidth - attributionWidth - gap * 2);

            this.addDrawableChild(ButtonWidget.builder(Text.literal("Credits"), pressed -> koil$openCredits())
                    .dimensions(x, y, creditsWidth, height)
                    .build());
            this.addDrawableChild(ButtonWidget.builder(
                            Text.literal("Attribution"),
                            ConfirmLinkScreen.opening(ATTRIBUTION_URL, this, true)
                    )
                    .dimensions(x + creditsWidth + gap, y, attributionWidth, height)
                    .build());
            ButtonWidget licenses = ButtonWidget.builder(
                            Text.literal("©"),
                            ConfirmLinkScreen.opening(LICENSES_URL, this, true)
                    )
                    .dimensions(x + creditsWidth + gap + attributionWidth + gap, y, licensesWidth, height)
                    .tooltip(Tooltip.of(Text.literal("Licenses")))
                    .build();
            this.addDrawableChild(licenses);
            return;
        }
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

    private boolean koil$isControlsButton(ButtonWidget button) {
        String key = koil$translationKey(button.getMessage());
        if (CONTROLS_TRANSLATION_KEY.equals(key)) {
            return true;
        }
        String label = button.getMessage().getString();
        if (label == null) {
            return false;
        }
        String normalized = label.toLowerCase().replace(".", "").replace("…", "").trim();
        return normalized.equals("controls")
                || normalized.equals("controls settings")
                || normalized.contains("controls");
    }

    private boolean koil$isCreditsAndAttributionButton(ButtonWidget button) {
        String key = koil$translationKey(button.getMessage());
        if (CREDITS_AND_ATTRIBUTION_TRANSLATION_KEY.equals(key)) {
            return true;
        }
        String label = button.getMessage().getString();
        if (label == null) {
            return false;
        }
        String normalized = label.toLowerCase().replace(".", "").replace("…", "").trim();
        return normalized.equals("credits & attribution")
                || normalized.equals("credits and attribution")
                || normalized.contains("credits & attribution")
                || normalized.contains("credits and attribution");
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

    private void koil$openCredits() {
        if (this.client == null) {
            return;
        }
        MinecraftClient minecraft = this.client;
        minecraft.setScreen(new CreditsScreen(false, () -> minecraft.setScreen(this)));
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
