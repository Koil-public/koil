package com.spirit.mixin.client.gui.revamp.listWidget;

import com.spirit.koil.api.util.file.json.JSONFileEditor;
import com.spirit.koil.api.design.KoilListBoundsAccess;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.client.gui.browser.AbstractModrinthContentScreen;
import com.spirit.client.gui.datapack.DatapackScreen;
import com.spirit.client.gui.mod.ModMenuScreen;
import com.spirit.client.gui.resourcepack.ResourcePackMenuScreen;
import com.spirit.client.gui.resourcepack.ResourcepackScreen;
import com.spirit.client.gui.shader.ShaderPackMenuScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.AbstractParentElement;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.CustomizeBuffetLevelScreen;
import net.minecraft.client.gui.screen.CustomizeFlatLevelScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.client.gui.screen.option.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screen.option.ChatOptionsScreen;
import net.minecraft.client.gui.screen.option.ControlsOptionsScreen;
import net.minecraft.client.gui.screen.option.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.gui.screen.option.LanguageOptionsScreen;
import net.minecraft.client.gui.screen.option.MouseOptionsScreen;
import net.minecraft.client.gui.screen.option.OnlineOptionsScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.option.SimpleOptionsScreen;
import net.minecraft.client.gui.screen.option.SkinOptionsScreen;
import net.minecraft.client.gui.screen.option.SoundOptionsScreen;
import net.minecraft.client.gui.screen.option.TelemetryInfoScreen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.gui.screen.pack.ExperimentalWarningScreen;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.EditGameRulesScreen;
import net.minecraft.client.gui.screen.world.ExperimentsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.*;

import static com.spirit.koil.api.design.uiColorVal.uiColorNonSelectionHighlight;
import static com.spirit.koil.api.design.uiColorVal.uiColorSelectionHighlight;

@Environment(EnvType.CLIENT)
@Mixin(EntryListWidget.class)
public abstract class MixinEntryListWidget<E extends AlwaysSelectedEntryListWidget.Entry<E>> extends AbstractParentElement implements KoilListBoundsAccess {
    @Unique private static final int KOIL_LANGUAGE_HEADER_OFFSET = 15;
    @Unique private static final int KOIL_LANGUAGE_WARNING_CLEARANCE = 8;
    @Shadow protected int left;
    @Shadow protected int width;
    @Shadow protected int top;
    @Shadow protected int bottom;
    @Shadow public abstract int getMaxScroll();
    @Shadow public abstract double getScrollAmount();
    @Shadow public abstract void setScrollAmount(double amount);
    @Shadow protected abstract int getScrollbarPositionX();
    @Unique private boolean koil$draggingScrollbar;
    @Unique private int koil$scrollbarDragOffset;

    @Override
    public int koil$getListTop() {
        return this.top;
    }

    @Override
    public int koil$getListBottom() {
        return this.bottom;
    }

    @Override
    public void koil$setListBounds(int top, int bottom) {
        this.top = top;
        this.bottom = Math.max(top, bottom);
        this.setScrollAmount(Math.min(this.getScrollAmount(), this.getMaxScroll()));
    }

    /**
     * @author SpiritXIV
     * @reason to remove black
     */
    @Overwrite
    protected void drawSelectionHighlight(DrawContext context, int y, int entryWidth, int entryHeight, int borderColor, int fillColor) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            if (!koil$usesKoilListRowHighlights()) {
                if (koil$isLanguageOptionsScreen()) {
                    int i = this.left + (this.width - entryWidth) / 2;
                    int j = this.left + (this.width + entryWidth) / 2;
                    context.fill(i, y - 2, j, y + entryHeight + 2, borderColor);
                    context.fill(i + 1, y - 1, j - 1, y + entryHeight + 1, fillColor);
                }
                return;
            }
            int i = this.left + (this.width - entryWidth) / 2;
            int j = this.left + (this.width + entryWidth) / 2;

            context.fill(i - 2, y - 2, j - 10, y + Math.min(entryHeight - 2, 38), new Color(uiColorSelectionHighlight, true).getRGB());
        } else {
            int i = this.left + (this.width - entryWidth) / 2;
            int j = this.left + (this.width + entryWidth) / 2;
            context.fill(i, y - 2, j, y + entryHeight + 2, borderColor);
            context.fill(i + 1, y - 1, j - 1, y + entryHeight + 1, fillColor);
        }
    }

    protected void drawNonSelectionHighlight(DrawContext context, int y, int entryWidth, int entryHeight) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()
                && koil$usesKoilListRowHighlights()) {
            int i = this.left + (this.width - entryWidth) / 2;
            int j = this.left + (this.width + entryWidth) / 2;

            context.fill(i - 2, y - 2, j - 10, y + Math.min(entryHeight - 2, 38), new Color(uiColorNonSelectionHighlight, true).getRGB());
        }
    }

    @Inject(method = "renderEntry", at = @At("HEAD"))
    protected void onRenderEntry(DrawContext context, int mouseX, int mouseY, float delta, int index, int x, int y, int entryWidth, int entryHeight, CallbackInfo ci) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()
                && koil$usesKoilListRowHighlights()
                && koil$shouldDrawNonSelectionRow(index)) {
            this.drawNonSelectionHighlight(context, y, entryWidth, entryHeight);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void koil$beginScrollbarDrag(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button == 0 && koil$canDragScrollbar() && koil$isOverScrollbar(mouseX, mouseY)) {
            this.koil$draggingScrollbar = true;
            int thumbTop = koil$scrollbarThumbTop();
            int thumbHeight = koil$scrollbarThumbHeight();
            if (mouseY >= thumbTop && mouseY <= thumbTop + thumbHeight) {
                this.koil$scrollbarDragOffset = (int) mouseY - thumbTop;
            } else {
                this.koil$scrollbarDragOffset = thumbHeight / 2;
                koil$scrollToThumbTop((int) mouseY - this.koil$scrollbarDragOffset);
            }
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void koil$dragScrollbar(double mouseX, double mouseY, int button, double deltaX, double deltaY, CallbackInfoReturnable<Boolean> cir) {
        if (button == 0 && this.koil$draggingScrollbar && koil$canDragScrollbar()) {
            koil$scrollToThumbTop((int) mouseY - this.koil$scrollbarDragOffset);
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"))
    private void koil$endScrollbarDrag(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button == 0) {
            this.koil$draggingScrollbar = false;
        }
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIFFIIII)V"
            )
    )
    private void koil$renderWorldCreationListPanel(DrawContext context, Identifier texture, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight) {
        if (koil$isOptionListPanelScreen()) {
            return;
        }
        if (koil$usesOwnListPanelBackground()) {
            return;
        }
        if (!koil$usesSharedListPanelBackground()) {
            context.drawTexture(texture, x, y, u, v, width, height, textureWidth, textureHeight);
            return;
        }
        context.fill(x, y, x + width, y + height, 0x65000000);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void koil$alignOptionListBounds(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.currentScreen == null || !KoilScreenBackgrounds.uiRedesignEnabled()) {
            return;
        }
        Screen screen = client.currentScreen;
        if (screen instanceof KeybindsScreen) {
            boolean hasControllingLayout = FabricLoader.getInstance().getModContainer("controlling").isPresent();
            this.top = Math.max(this.top, KoilVanillaScreenChrome.listTop(hasControllingLayout));
            this.bottom = Math.min(this.bottom, KoilVanillaScreenChrome.keybindListBottom(screen.height, hasControllingLayout));
        } else if (screen instanceof LanguageOptionsScreen) {
            this.top = Math.max(this.top, KoilVanillaScreenChrome.listTop(false) + KOIL_LANGUAGE_HEADER_OFFSET);
            this.bottom = Math.min(this.bottom, Math.min(KoilVanillaScreenChrome.languageListBottom(screen.height), screen.height - 56 - KOIL_LANGUAGE_WARNING_CLEARANCE));
        }
    }

    @Inject(method = "getRowWidth", at = @At("HEAD"), cancellable = true)
    private void koil$getSingleplayerWorldRowWidth(CallbackInfoReturnable<Integer> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null
                && client.currentScreen instanceof SelectWorldScreen
                && KoilScreenBackgrounds.uiRedesignEnabled()
                && (Object) this instanceof WorldListWidget) {
            cir.setReturnValue(this.width - 12);
        }
    }

    @Inject(method = "getRowLeft", at = @At("HEAD"), cancellable = true)
    private void koil$getSingleplayerWorldRowLeft(CallbackInfoReturnable<Integer> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null
                && client.currentScreen instanceof SelectWorldScreen
                && KoilScreenBackgrounds.uiRedesignEnabled()
                && (Object) this instanceof WorldListWidget) {
            cir.setReturnValue(this.left + 2);
        }
    }

    @Inject(method = "getScrollbarPositionX", at = @At("HEAD"), cancellable = true)
    private void koil$getSingleplayerWorldScrollbarX(CallbackInfoReturnable<Integer> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null
                && client.currentScreen instanceof SelectWorldScreen
                && KoilScreenBackgrounds.uiRedesignEnabled()
                && (Object) this instanceof WorldListWidget) {
            cir.setReturnValue(this.left + this.width - 11);
        }
    }

    private boolean koil$usesSharedListPanelBackground() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !KoilScreenBackgrounds.uiRedesignEnabled()) {
            return false;
        }
        Screen screen = client.currentScreen;
        return screen instanceof CreateWorldScreen
                || screen instanceof EditGameRulesScreen
                || screen instanceof ExperimentsScreen
                || screen instanceof CustomizeFlatLevelScreen
                || screen instanceof CustomizeBuffetLevelScreen;
    }

    private boolean koil$isOptionListPanelScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !KoilScreenBackgrounds.uiRedesignEnabled()) {
            return false;
        }
        Screen screen = client.currentScreen;
        return screen instanceof OptionsScreen
                || screen instanceof GameOptionsScreen
                || screen instanceof SimpleOptionsScreen
                || screen instanceof LanguageOptionsScreen
                || screen instanceof AccessibilityOptionsScreen
                || screen instanceof ChatOptionsScreen
                || screen instanceof ControlsOptionsScreen
                || screen instanceof KeybindsScreen
                || screen instanceof MouseOptionsScreen
                || screen instanceof OnlineOptionsScreen
                || screen instanceof SkinOptionsScreen
                || screen instanceof SoundOptionsScreen
                || screen instanceof VideoOptionsScreen
                || screen instanceof TelemetryInfoScreen
                || screen instanceof CreditsAndAttributionScreen;
    }

    private boolean koil$isLanguageOptionsScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null
                && KoilScreenBackgrounds.uiRedesignEnabled()
                && client.currentScreen instanceof LanguageOptionsScreen;
    }

    private boolean koil$usesOwnListPanelBackground() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !KoilScreenBackgrounds.uiRedesignEnabled()) {
            return false;
        }
        Screen screen = client.currentScreen;
        return screen instanceof AbstractModrinthContentScreen
                || screen instanceof SelectWorldScreen
                || screen instanceof MultiplayerScreen
                || screen instanceof ModMenuScreen
                || screen instanceof ResourcePackMenuScreen
                || screen instanceof ShaderPackMenuScreen
                || screen instanceof ResourcepackScreen
                || screen instanceof DatapackScreen
                || screen instanceof PackScreen;
    }

    private boolean koil$usesKoilListRowHighlights() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !KoilScreenBackgrounds.uiRedesignEnabled()) {
            return false;
        }
        Screen screen = client.currentScreen;
        return screen instanceof ModMenuScreen
                || screen instanceof ResourcePackMenuScreen
                || screen instanceof ShaderPackMenuScreen
                || screen instanceof MultiplayerScreen
                || screen instanceof SelectWorldScreen
                || screen instanceof CreateWorldScreen
                || screen instanceof EditGameRulesScreen
                || screen instanceof ExperimentsScreen
                || screen instanceof CustomizeFlatLevelScreen
                || screen instanceof CustomizeBuffetLevelScreen
                || screen instanceof PackScreen
                || screen instanceof ExperimentalWarningScreen;
    }

    private boolean koil$usesDraggableScrollbar() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return false;
        }
        Screen screen = client.currentScreen;
        return screen instanceof AbstractModrinthContentScreen
                || screen instanceof ResourcepackScreen
                || screen instanceof DatapackScreen
                || screen instanceof SelectWorldScreen
                || screen instanceof MultiplayerScreen
                || screen instanceof ResourcePackMenuScreen
                || screen instanceof PackScreen
                || screen instanceof ShaderPackMenuScreen
                || screen instanceof ModMenuScreen;
    }

    private boolean koil$canDragScrollbar() {
        return koil$usesDraggableScrollbar() && this.getMaxScroll() > 0;
    }

    private boolean koil$isOverScrollbar(double mouseX, double mouseY) {
        int scrollbarX = this.getScrollbarPositionX();
        return mouseY >= this.top
                && mouseY <= this.bottom
                && mouseX >= scrollbarX - 6
                && mouseX <= scrollbarX + 12;
    }

    private int koil$scrollbarThumbHeight() {
        int trackHeight = Math.max(1, this.bottom - this.top);
        int maxScroll = Math.max(1, this.getMaxScroll());
        return Math.max(18, (int) ((trackHeight / (float) (trackHeight + maxScroll)) * trackHeight));
    }

    private int koil$scrollbarThumbTop() {
        int trackHeight = Math.max(1, this.bottom - this.top);
        int thumbHeight = koil$scrollbarThumbHeight();
        int thumbTravel = Math.max(1, trackHeight - thumbHeight);
        return this.top + (int) ((this.getScrollAmount() / (float) this.getMaxScroll()) * thumbTravel);
    }

    private void koil$scrollToThumbTop(int thumbTop) {
        int trackHeight = Math.max(1, this.bottom - this.top);
        int thumbHeight = koil$scrollbarThumbHeight();
        int minTop = this.top;
        int maxTop = this.bottom - thumbHeight;
        int clampedTop = Math.max(minTop, Math.min(maxTop, thumbTop));
        int thumbTravel = Math.max(1, trackHeight - thumbHeight);
        double progress = (clampedTop - minTop) / (double) thumbTravel;
        this.setScrollAmount(progress * this.getMaxScroll());
    }

    private boolean koil$isMultiplayerScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.currentScreen instanceof MultiplayerScreen;
    }

    private boolean koil$shouldDrawNonSelectionRow(int index) {
        EntryListWidget<?> list = (EntryListWidget<?>) (Object) this;
        if (index < 0 || index >= list.children().size()) {
            return false;
        }
        Object entry = list.children().get(index);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.currentScreen instanceof MultiplayerScreen) {
            return entry instanceof MultiplayerServerListWidget.ServerEntry
                    || entry instanceof MultiplayerServerListWidget.LanServerEntry;
        }
        return true;
    }
}
