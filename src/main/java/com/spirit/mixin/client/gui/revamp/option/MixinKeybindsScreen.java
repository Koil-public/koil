package com.spirit.mixin.client.gui.revamp.option;

import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.design.KoilListBoundsAccess;
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
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(KeybindsScreen.class)
public class MixinKeybindsScreen extends GameOptionsScreen {
    @Shadow private ButtonWidget resetAllButton;

    @Shadow private ControlsListWidget controlsList;
    @Unique private ClickableWidget koil$sneakButton;
    @Unique private ClickableWidget koil$sprintButton;
    @Unique private ClickableWidget koil$autoJumpButton;
    @Unique private ClickableWidget koil$operatorButton;
    @Unique private ButtonWidget koil$indevButton;

    public MixinKeybindsScreen(Screen parent, GameOptions gameOptions, Text title) {
        super(parent, gameOptions, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void koil$addMovedControlOptions(CallbackInfo ci) {
        this.koil$sneakButton = this.addDrawableChild(
                this.gameOptions.getSneakToggled().createWidget(this.gameOptions, 0, 0, 90)
        );
        this.koil$sprintButton = this.addDrawableChild(
                this.gameOptions.getSprintToggled().createWidget(this.gameOptions, 0, 0, 90)
        );
        this.koil$autoJumpButton = this.addDrawableChild(
                this.gameOptions.getAutoJump().createWidget(this.gameOptions, 0, 0, 90)
        );
        this.koil$operatorButton = this.addDrawableChild(
                this.gameOptions.getOperatorItemsTab().createWidget(this.gameOptions, 0, 0, 120)
        );
        this.koil$indevButton = ButtonWidget.builder(Text.literal("Indev"), button -> {
        }).dimensions(0, 0, 90, 20).build();
        this.koil$indevButton.active = false;
        this.addDrawableChild(this.koil$indevButton);
        this.koil$layoutMovedControlOptions();
    }

    @Unique
    private void koil$layoutMovedControlOptions() {
        if (this.koil$indevButton == null) {
            return;
        }
        boolean controlling = FabricLoader.getInstance().getModContainer("controlling").isPresent();
        if (this.controlsList instanceof KoilListBoundsAccess bounds) {
            bounds.koil$setListBounds(
                    KoilVanillaScreenChrome.listTop(controlling),
                    KoilVanillaScreenChrome.keybindListBottom(this.height, controlling)
            );
        }
        if (controlling && this.width >= 620) {
            int centerX = this.width / 2;
            int existingLeftButtonX = centerX - 155;
            int existingRightButtonEdge = centerX + 155;
            int surroundingGap = 9;
            int columnGap = 4;
            int availableGridWidth = existingLeftButtonX - surroundingGap - 4;
            int columnWidth = Math.min(82, Math.max(42, (availableGridWidth - columnGap) / 2));
            int gridWidth = columnWidth * 2 + columnGap;
            int x = existingLeftButtonX - surroundingGap - gridWidth;
            int topY = this.height - 53;
            koil$place(this.koil$autoJumpButton, x, topY, columnWidth);
            koil$place(this.koil$sprintButton, x + columnWidth + columnGap, topY, columnWidth);
            koil$place(this.koil$indevButton, x, topY + 24, columnWidth);
            koil$place(this.koil$sneakButton, x + columnWidth + columnGap, topY + 24, columnWidth);
            koil$place(this.koil$operatorButton, existingRightButtonEdge + surroundingGap, this.height - 29, 120);
            return;
        }

        int gap = 4;
        int available = Math.max(1, this.width - 16);
        int operatorWidth = Math.max(82, Math.min(132, available / 3));
        int gridWidth = Math.max(90, available - operatorWidth - 8);
        int columnWidth = Math.max(42, (gridWidth - gap) / 2);
        int actualWidth = columnWidth * 2 + gap + 8 + operatorWidth;
        int x = Math.max(4, (this.width - actualWidth) / 2);
        int topY = this.height - 76;
        koil$place(this.koil$autoJumpButton, x, topY, columnWidth);
        koil$place(this.koil$sprintButton, x + columnWidth + gap, topY, columnWidth);
        koil$place(this.koil$indevButton, x, topY + 24, columnWidth);
        koil$place(this.koil$sneakButton, x + columnWidth + gap, topY + 24, columnWidth);
        koil$place(this.koil$operatorButton, x + columnWidth * 2 + gap + 8, topY + 12, operatorWidth);
    }

    @Unique
    private static void koil$place(ClickableWidget widget, int x, int y, int width) {
        if (widget == null) {
            return;
        }
        widget.setX(x);
        widget.setY(y);
        widget.setWidth(Math.max(1, width));
    }

    /**
     * @author SpiritXIV
     * @reason to make it look better
     */
    @Overwrite
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.koil$layoutMovedControlOptions();
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
