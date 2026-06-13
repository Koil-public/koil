package com.spirit.mixin.client.gui.revamp.option;

import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.ControlsListWidget;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Environment(EnvType.CLIENT)
@Mixin(KeybindsScreen.class)
public class MixinKeybindsScreen extends GameOptionsScreen {
    @Shadow private ButtonWidget resetAllButton;

    @Shadow private ControlsListWidget controlsList;

    public MixinKeybindsScreen(Screen parent, GameOptions gameOptions, Text title) {
        super(parent, gameOptions, title);
    }

    /**
     * @author SpiritXIV
     * @reason to make it look better
     */
    @Overwrite
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {

            MinecraftClient client = MinecraftClient.getInstance();
            boolean hasControllingLayout = FabricLoader.getInstance().getModContainer("controlling").isPresent();
            KoilVanillaScreenChrome.renderListShell(
                    context,
                    client,
                    this.width,
                    this.height,
                    KoilVanillaScreenChrome.listTop(hasControllingLayout),
                    KoilVanillaScreenChrome.keybindListBottom(this.height, hasControllingLayout)
            );
            KoilVanillaScreenChrome.renderTitle(context, this.textRenderer, Text.literal("Options"), this.title);

            this.controlsList.render(context, mouseX, mouseY, delta);
            boolean bl = false;
            KeyBinding[] var6 = this.gameOptions.allKeys;
            int var7 = var6.length;

            for (int var8 = 0; var8 < var7; ++var8) {
                KeyBinding keyBinding = var6[var8];
                if (!keyBinding.isDefault()) {
                    bl = true;
                    break;
                }
            }

            this.resetAllButton.active = bl;
            super.render(context, mouseX, mouseY, delta);
        } else {
            this.renderBackground(context);
            this.controlsList.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 16777215);
            boolean bl = false;
            KeyBinding[] var6 = this.gameOptions.allKeys;
            int var7 = var6.length;

            for(int var8 = 0; var8 < var7; ++var8) {
                KeyBinding keyBinding = var6[var8];
                if (!keyBinding.isDefault()) {
                    bl = true;
                    break;
                }
            }

            this.resetAllButton.active = bl;
            super.render(context, mouseX, mouseY, delta);
        }
    }
}
