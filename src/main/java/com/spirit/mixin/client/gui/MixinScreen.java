package com.spirit.mixin.client.gui;

import com.google.gson.JsonElement;
import com.spirit.client.gui.InfoPopup;
import com.spirit.client.gui.PopupMenu;
import com.spirit.client.gui.ScreenChromeHost;
import com.spirit.client.gui.VanillaScreenToolResolver;
import com.spirit.client.gui.browser.AbstractModrinthContentScreen;
import com.spirit.client.gui.datapack.DatapackScreen;
import com.spirit.client.gui.ide.FileEditorScreen;
import com.spirit.client.gui.ide.FileExplorerScreen;
import com.spirit.client.gui.main.KoilMenuScreen;
import com.spirit.client.gui.measure.PixelDifferenceOverlay;
import com.spirit.client.gui.mod.ModConfigScreen;
import com.spirit.client.gui.mod.ModMenuScreen;
import com.spirit.client.gui.mod.ModScreen;
import com.spirit.client.gui.resourcepack.ResourcePackMenuScreen;
import com.spirit.client.gui.resourcepack.ResourcepackScreen;
import com.spirit.client.gui.shader.ShaderPackMenuScreen;
import com.spirit.client.gui.shader.ShaderpackScreen;
import com.spirit.client.gui.tool.PerformanceOptimizerScreen;
import com.spirit.client.gui.update.UpdateScreen;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import com.spirit.koil.chat.internal.upload.RichChatAttachmentRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.AbstractParentElement;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.*;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerWarningScreen;
import net.minecraft.client.gui.screen.option.*;
import net.minecraft.client.gui.screen.pack.ExperimentalWarningScreen;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.screen.world.*;
import net.minecraft.client.gui.tab.GridScreenTab;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.spirit.Main.uiImageDirectory;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTexture;

@Environment(EnvType.CLIENT)
@Mixin(Screen.class)
public abstract class MixinScreen extends AbstractParentElement implements Drawable, ScreenChromeHost {
    @Unique private static final int KOIL_LANGUAGE_HEADER_BOTTOM = 56;
    @Unique private static final int KOIL_LANGUAGE_FOOTER_RAISE_TOTAL = 64;
    @Unique private static final int KOIL_TOP_BAR_BUTTON_SIZE = 14;
    @Unique private static final int KOIL_TOP_BAR_BUTTON_GAP = 4;
    @Unique private static final int KOIL_TOP_BAR_RIGHT_MARGIN = 10;
    @Unique private static final int KOIL_TOP_BAR_KOIL_SCREEN_LEFT_SHIFT = 4;
    @Unique private static final int KOIL_TOP_BAR_TOP_MARGIN = 6;
    @Unique private static final List<String> KOIL_MACHINE_GENERATED_POPUP = List.of(
            "Machine-generated formatting",
            "Koil may generate previews, grouped controls, metadata, and warning text from local file data.",
            "This can be wrong. Verify important content before acting on it."
    );
    @Unique private final PopupMenu koil$screenToolsMenu = new PopupMenu();
    @Unique private final InfoPopup koil$screenInfoPopup = new InfoPopup();
    @Unique private final InfoPopup koil$machineGeneratedPopup = new InfoPopup();

    @Shadow @Final private List<Drawable> drawables;
    @Shadow @Final private List<Element> children;
    @Shadow @Nullable protected MinecraftClient client;
    @Mutable @Shadow @Final public static Identifier OPTIONS_BACKGROUND_TEXTURE;

    @Shadow public abstract void close();
    @Shadow protected abstract void setTooltip(Text tooltip);

    @Shadow public int width;
    @Shadow public int height;

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        for (Drawable drawable : this.drawables) {
            drawable.render(context, mouseX, mouseY, delta);
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void koil$renderVanillaToolsButton(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if ((Object) this instanceof ModConfigScreen
                || (Object) this instanceof ModMenuScreen
                || (Object) this instanceof ShaderPackMenuScreen
                || (Object) this instanceof ResourcePackMenuScreen
                || (Object) this instanceof AbstractModrinthContentScreen) {
            return;
        }
        koil$renderScreenChromeLate(context, mouseX, mouseY, delta);
    }

    @Override
    public void koil$renderScreenChromeLate(DrawContext context, int mouseX, int mouseY, float delta) {
        boolean showToolsButton = koil$shouldShowToolsButton();
        boolean showMachineGeneratedBadge = koil$showsMachineGeneratedBadge();
        if (!showToolsButton && !showMachineGeneratedBadge) {
            koil$screenToolsMenu.close();
            koil$screenInfoPopup.close();
            koil$machineGeneratedPopup.close();
            PixelDifferenceOverlay.render(context, mouseX, mouseY);
            return;
        }

        int screenWidth = koil$screenWidth();
        int screenHeight = koil$screenHeight();
        int buttonY = koil$topBarButtonY();
        int toolsButtonX = Math.max(8, screenWidth - KOIL_TOP_BAR_RIGHT_MARGIN - KOIL_TOP_BAR_BUTTON_SIZE - koil$topBarButtonLeftShift());
        int infoButtonX = showToolsButton
                ? toolsButtonX - KOIL_TOP_BAR_BUTTON_GAP - KOIL_TOP_BAR_BUTTON_SIZE
                : screenWidth - KOIL_TOP_BAR_RIGHT_MARGIN - KOIL_TOP_BAR_BUTTON_SIZE;
        infoButtonX = Math.max(8, Math.min(infoButtonX, screenWidth - KOIL_TOP_BAR_BUTTON_RIGHT_SAFE_SIZE()));
        boolean toolsHovered = showToolsButton && koil$isInside(mouseX, mouseY, toolsButtonX, buttonY, KOIL_TOP_BAR_BUTTON_SIZE, KOIL_TOP_BAR_BUTTON_SIZE);
        boolean infoHovered = showMachineGeneratedBadge && koil$isInside(mouseX, mouseY, infoButtonX, buttonY, KOIL_TOP_BAR_BUTTON_SIZE, KOIL_TOP_BAR_BUTTON_SIZE);

        context.getMatrices().push();
        context.getMatrices().translate(0.0F, 0.0F, 325.0F);

        if (showMachineGeneratedBadge) {
            koil$drawInfoIcon(context, infoButtonX, buttonY, infoHovered);
            boolean machinePopupHovered = koil$machineGeneratedPopup.isOpen() && koil$machineGeneratedPopup.contains(mouseX, mouseY);
            if (infoHovered || machinePopupHovered) {
                koil$machineGeneratedPopup.openWarningAtPointer(mouseX, mouseY, screenWidth, screenHeight, KOIL_MACHINE_GENERATED_POPUP);
            } else {
                koil$machineGeneratedPopup.close();
            }
        }

        if (showToolsButton) {
            koil$drawTopBarButton(context, toolsButtonX, buttonY, toolsHovered || koil$screenToolsMenu.isOpen(), "...");
        }

        koil$screenToolsMenu.render(context, mouseX, mouseY);
        koil$screenInfoPopup.render(context);
        koil$machineGeneratedPopup.render(context);
        PixelDifferenceOverlay.render(context, mouseX, mouseY);
        context.getMatrices().pop();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (PixelDifferenceOverlay.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (koil$consumeScreenChromeClick(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (PixelDifferenceOverlay.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (PixelDifferenceOverlay.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        if ((Object) this instanceof ChatScreen && RichChatAttachmentRenderer.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void koil$consumePixelDifferenceKey(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (PixelDifferenceOverlay.keyPressed(keyCode)) {
            cir.setReturnValue(true);
        }
    }

    @Override
    public boolean koil$consumeScreenChromeClick(double mouseX, double mouseY, int button) {
        boolean showToolsButton = koil$shouldShowToolsButton();
        boolean showMachineGeneratedBadge = koil$showsMachineGeneratedBadge();
        if (!showToolsButton && !showMachineGeneratedBadge) {
            return false;
        }

        int screenWidth = koil$screenWidth();
        int screenHeight = koil$screenHeight();
        int buttonY = koil$topBarButtonY();
        int toolsButtonX = Math.max(8, screenWidth - KOIL_TOP_BAR_RIGHT_MARGIN - KOIL_TOP_BAR_BUTTON_SIZE - koil$topBarButtonLeftShift());
        int infoButtonX = showToolsButton
                ? toolsButtonX - KOIL_TOP_BAR_BUTTON_GAP - KOIL_TOP_BAR_BUTTON_SIZE
                : screenWidth - KOIL_TOP_BAR_RIGHT_MARGIN - KOIL_TOP_BAR_BUTTON_SIZE;
        infoButtonX = Math.max(8, Math.min(infoButtonX, screenWidth - KOIL_TOP_BAR_BUTTON_RIGHT_SAFE_SIZE()));
        boolean toolsButtonHovered = showToolsButton && koil$isInside(mouseX, mouseY, toolsButtonX, buttonY, KOIL_TOP_BAR_BUTTON_SIZE, KOIL_TOP_BAR_BUTTON_SIZE);
        boolean infoButtonHovered = showMachineGeneratedBadge && koil$isInside(mouseX, mouseY, infoButtonX, buttonY, KOIL_TOP_BAR_BUTTON_SIZE, KOIL_TOP_BAR_BUTTON_SIZE);

        if (koil$screenInfoPopup.isOpen() && !koil$screenInfoPopup.contains(mouseX, mouseY) && !toolsButtonHovered) {
            koil$screenInfoPopup.close();
        }
        if (koil$machineGeneratedPopup.isOpen() && !koil$machineGeneratedPopup.contains(mouseX, mouseY) && !infoButtonHovered) {
            koil$machineGeneratedPopup.close();
        }

        if (button == 0 && infoButtonHovered) {
            return true;
        }

        if (button == 0 && toolsButtonHovered) {
            VanillaScreenToolResolver.ResolvedScreenTools tools = koil$resolvedTools();
            koil$screenInfoPopup.close();
            if (tools != null) {
                koil$screenToolsMenu.toggleNearAnchor(toolsButtonX, buttonY, KOIL_TOP_BAR_BUTTON_SIZE, screenWidth, screenHeight, tools.actions());
                return true;
            }
        }

        if (button == 0 && koil$screenToolsMenu.isOpen()) {
            VanillaScreenToolResolver.ResolvedScreenTools tools = koil$resolvedTools();
            PopupMenu.MenuEntry selected = koil$screenToolsMenu.click(mouseX, mouseY);
            if (selected != null && tools != null) {
                koil$handleToolAction(selected.id(), tools, mouseX, mouseY);
                return true;
            }
            if (!koil$screenToolsMenu.isOpen()) {
                return true;
            }
        }

        if ((koil$screenToolsMenu.isOpen() && koil$screenToolsMenu.contains(mouseX, mouseY))
                || (koil$screenInfoPopup.isOpen() && koil$screenInfoPopup.contains(mouseX, mouseY))
                || (koil$machineGeneratedPopup.isOpen() && koil$machineGeneratedPopup.contains(mouseX, mouseY))) {
            return true;
        }
        return false;
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void koil$closeVanillaToolsPopups(CallbackInfo ci) {
        koil$screenToolsMenu.close();
        koil$screenInfoPopup.close();
        koil$machineGeneratedPopup.close();
        PixelDifferenceOverlay.clearForRemovedScreen();
    }

    @Inject(method = "renderBackgroundTexture", at = @At(value = "HEAD"), cancellable = true)
    private void onRenderBackgroundTexture(DrawContext context, CallbackInfo ci) {
        if (client == null || client.currentScreen == null) {
            return;
        }
        if (client.currentScreen instanceof CreditsScreen && koil$isUiRedesignEnabled()) {
            int screenWidth = koil$screenWidth();
            int screenHeight = koil$screenHeight();
            koil$setOptionsBackgroundBlank(true);
            KoilScreenBackgrounds.render(context, client, screenWidth, screenHeight);
            if (KoilScreenBackgrounds.canRender(client)) {
                context.fill(0, 0, screenWidth, screenHeight, KoilScreenBackgrounds.overlayColor(client));
            }
            KoilVanillaScreenChrome.renderCreditsTextPanel(context, screenWidth, screenHeight);
            ci.cancel();
            return;
        }
        if (client.currentScreen instanceof GameMenuScreen && koil$isUiRedesignEnabled()) {
            int screenWidth = koil$screenWidth();
            int screenHeight = koil$screenHeight();
            koil$setOptionsBackgroundBlank(true);
            KoilVanillaScreenChrome.renderOptionsShell(context, client, screenWidth, screenHeight);
            ci.cancel();
            return;
        }
        if (client.currentScreen instanceof StatsScreen && koil$isUiRedesignEnabled()) {
            int screenWidth = koil$screenWidth();
            int screenHeight = koil$screenHeight();
            koil$setOptionsBackgroundBlank(true);
            KoilVanillaScreenChrome.renderListShell(context, client, screenWidth, screenHeight, KoilVanillaScreenChrome.listTop(false), KoilVanillaScreenChrome.listBottom(screenHeight));
            ci.cancel();
            return;
        }
        if (client.currentScreen instanceof LanguageOptionsScreen && koil$isUiRedesignEnabled()) {
            koil$renderLanguageScreenBackground(context);
            ci.cancel();
            return;
        }
        if (koil$shouldUseSharedScreenBackground(client.currentScreen)) {
            int screenWidth = koil$screenWidth();
            int screenHeight = koil$screenHeight();
            koil$setOptionsBackgroundBlank(true);
            KoilVanillaScreenChrome.renderOptionsShell(context, client, screenWidth, screenHeight);
            ci.cancel();
            return;
        }
        Set<Class<?>> customBackgroundScreens = new HashSet<>();
        List<Class<?>> uiScreens = Arrays.asList(
                SelectWorldScreen.class,
                MultiplayerWarningScreen.class,
                ConfirmScreen.class,
                ConfirmLinkScreen.class,
                ConnectScreen.class,
                DisconnectedScreen.class,
                CreateWorldScreen.class,
                EditWorldScreen.class,
                EditGameRulesScreen.class,
                CustomizeFlatLevelScreen.class,
                CustomizeBuffetLevelScreen.class,
                OptimizeWorldScreen.class,
                BackupPromptScreen.class,
                PresetsScreen.class,
                AddServerScreen.class,
                DirectConnectScreen.class,
                LevelLoadingScreen.class,
                DownloadingTerrainScreen.class,
                ProgressScreen.class,
                DialogScreen.class,
                NoticeScreen.class,
                GameOptionsScreen.class,
                ChatOptionsScreen.class,
                ControlsOptionsScreen.class,
                CreditsAndAttributionScreen.class,
                KeybindsScreen.class,
                LanguageOptionsScreen.class,
                MouseOptionsScreen.class,
                OnlineOptionsScreen.class,
                OptionsScreen.class,
                SimpleOptionsScreen.class,
                SkinOptionsScreen.class,
                SoundOptionsScreen.class,
                TelemetryInfoScreen.class,
                StatsScreen.class,
                VideoOptionsScreen.class,
                PackScreen.class,
                ExperimentalWarningScreen.class,
                AccessibilityOptionsScreen.class,
                WarningScreen.class,
                GridScreenTab.class,
                TitleScreen.class,
                ShaderpackScreen.class
        );

        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            customBackgroundScreens.addAll(uiScreens);
        } else {
            customBackgroundScreens.removeAll(uiScreens);
        }

        customBackgroundScreens.addAll(Arrays.asList(
                KoilMenuScreen.class,
                UpdateScreen.class,
                ModMenuScreen.class,
                ResourcePackMenuScreen.class,
                ShaderPackMenuScreen.class,
                FileEditorScreen.class,
                FileExplorerScreen.class,
                ModConfigScreen.class,
                EntryListWidget.class
        ));

        Class<?> currentScreenClass = client.currentScreen.getClass();

        koil$setOptionsBackgroundBlank(customBackgroundScreens.contains(currentScreenClass)
                || koil$shouldUseSharedScreenBackground(client.currentScreen));
    }

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void koil$renderSharedMinecraftBackground(DrawContext context, CallbackInfo ci) {
        if (client == null || client.currentScreen == null) {
            return;
        }
        if (client.currentScreen instanceof LanguageOptionsScreen && koil$isUiRedesignEnabled()) {
            koil$renderLanguageScreenBackground(context);
            ci.cancel();
            return;
        }
        if (client.world != null && koil$isUiRedesignEnabled() && koil$shouldSuppressInWorldVanillaBackground(client.currentScreen)) {
            ci.cancel();
            return;
        }
        if (client.currentScreen instanceof CreditsScreen && koil$isUiRedesignEnabled()) {
            int screenWidth = koil$screenWidth();
            int screenHeight = koil$screenHeight();
            koil$setOptionsBackgroundBlank(true);
            KoilScreenBackgrounds.render(context, client, screenWidth, screenHeight);
            if (KoilScreenBackgrounds.canRender(client)) {
                context.fill(0, 0, screenWidth, screenHeight, KoilScreenBackgrounds.overlayColor(client));
            }
            KoilVanillaScreenChrome.renderCreditsTextPanel(context, screenWidth, screenHeight);
            ci.cancel();
            return;
        }
        if (client.currentScreen instanceof GameMenuScreen && koil$isUiRedesignEnabled()) {
            int screenWidth = koil$screenWidth();
            int screenHeight = koil$screenHeight();
            koil$setOptionsBackgroundBlank(true);
            KoilVanillaScreenChrome.renderOptionsShell(context, client, screenWidth, screenHeight);
            ci.cancel();
            return;
        }
        if (client.currentScreen instanceof StatsScreen && koil$isUiRedesignEnabled()) {
            int screenWidth = koil$screenWidth();
            int screenHeight = koil$screenHeight();
            koil$setOptionsBackgroundBlank(true);
            KoilVanillaScreenChrome.renderListShell(context, client, screenWidth, screenHeight, KoilVanillaScreenChrome.listTop(false), KoilVanillaScreenChrome.listBottom(screenHeight));
            ci.cancel();
            return;
        }
        if (!koil$shouldUseSharedScreenBackground(client.currentScreen)) {
            return;
        }
        int screenWidth = koil$screenWidth();
        int screenHeight = koil$screenHeight();
        koil$setOptionsBackgroundBlank(true);
        KoilVanillaScreenChrome.renderOptionsShell(context, client, screenWidth, screenHeight);
        ci.cancel();
    }

    @Unique
    private void koil$renderLanguageScreenBackground(DrawContext context) {
        int screenWidth = koil$screenWidth();
        int screenHeight = koil$screenHeight();
        koil$setOptionsBackgroundBlank(true);
        KoilVanillaScreenChrome.renderListShell(
                context,
                client,
                screenWidth,
                screenHeight,
                Math.min(screenHeight, KOIL_LANGUAGE_HEADER_BOTTOM),
                Math.max(KOIL_LANGUAGE_HEADER_BOTTOM, screenHeight - KOIL_LANGUAGE_FOOTER_RAISE_TOTAL)
        );
    }

    @Override
    public List<? extends Element> children() {
        return this.children;
    }

    @Unique
    private boolean koil$shouldShowToolsButton() {
        if (client != null && koil$isPassiveTransitionScreen(client.currentScreen)) {
            return false;
        }
        return koil$resolvedTools() != null;
    }

    @Unique
    private boolean koil$showsMachineGeneratedBadge() {
        if (client == null || client.currentScreen == null) {
            return false;
        }
        Screen screen = client.currentScreen;
        return screen instanceof FileEditorScreen
                || screen instanceof ModConfigScreen
                || screen instanceof StatsScreen;
    }

    @Unique
    private int koil$topBarButtonY() {
        return koil$isKoilChromeScreen() ? 20 : KOIL_TOP_BAR_TOP_MARGIN + 3;
    }

    @Unique
    private int koil$topBarButtonLeftShift() {
        return koil$isKoilChromeScreen() ? KOIL_TOP_BAR_KOIL_SCREEN_LEFT_SHIFT : 0;
    }

    @Unique
    private boolean koil$isKoilChromeScreen() {
        if (client == null || client.currentScreen == null) {
            return false;
        }
        Screen screen = client.currentScreen;
        return screen instanceof ModMenuScreen
                || screen instanceof FileExplorerScreen
                || screen instanceof FileEditorScreen
                || screen instanceof ModConfigScreen
                || screen instanceof KoilMenuScreen
                || screen instanceof UpdateScreen
                || screen instanceof PerformanceOptimizerScreen
                || screen instanceof ResourcePackMenuScreen
                || screen instanceof ShaderPackMenuScreen
                || screen instanceof ShaderpackScreen
                || screen instanceof ModScreen
                || screen instanceof ResourcepackScreen
                || screen instanceof DatapackScreen;
    }

    @Unique
    private void koil$drawTopBarButton(DrawContext context, int x, int y, boolean hovered, String label) {
        PopupMenu.renderTriggerButton(context, client.textRenderer, x, y, hovered, label);
    }

    @Unique
    private void koil$drawInfoIcon(DrawContext context, int x, int y, boolean hovered) {
        context.fill(x, y, x + KOIL_TOP_BAR_BUTTON_SIZE, y + KOIL_TOP_BAR_BUTTON_SIZE, hovered ? 0x824D5563 : 0x282A303A);
        context.drawBorder(x, y, KOIL_TOP_BAR_BUTTON_SIZE, KOIL_TOP_BAR_BUTTON_SIZE, hovered ? 0x9AB8C2D4 : 0x286B7485);
        context.drawText(client.textRenderer, "?", x + 5, y + 3, hovered ? 0xC8FFFFFF : 0x48F5F7FA, false);
    }

    @Unique
    private boolean koil$isUiRedesignEnabled() {
        try {
            JsonElement element = JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign");
            return element != null && element.isJsonPrimitive() && element.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    @Unique
    private boolean koil$isMinecraftOrRealmsScreen(Screen screen) {
        String name = screen.getClass().getName();
        return name.startsWith("net.minecraft.client.gui.screen.")
                || name.startsWith("net.minecraft.client.realms.")
                || name.startsWith("com.mojang.realmsclient.");
    }

    @Unique
    private boolean koil$shouldUseSharedScreenBackground(Screen screen) {
        if (screen == null || client == null || !koil$isUiRedesignEnabled()) {
            return false;
        }
        if (screen instanceof TitleScreen || koil$isPassiveTransitionScreen(screen)) {
            return false;
        }
        if (screen instanceof GameMenuScreen) {
            return true;
        }
        if (client.world != null) {
            return screen instanceof PackScreen;
        }
        if (screen instanceof MultiplayerScreen || screen instanceof AddServerScreen || screen instanceof DirectConnectScreen) {
            return false;
        }
        return true;
    }

    private boolean koil$shouldSuppressInWorldVanillaBackground(Screen screen) {
        return screen instanceof LanguageOptionsScreen
                || screen instanceof SoundOptionsScreen
                || screen instanceof ChatOptionsScreen
                || screen instanceof AccessibilityOptionsScreen
                || screen instanceof SimpleOptionsScreen
                || screen instanceof GameOptionsScreen
                || screen instanceof OptionsScreen
                || screen instanceof ControlsOptionsScreen
                || screen instanceof KeybindsScreen
                || screen instanceof TelemetryInfoScreen
                || screen instanceof WarningScreen;
    }

    @Unique
    private boolean koil$isPassiveTransitionScreen(Screen screen) {
        return screen instanceof LevelLoadingScreen
                || screen instanceof DownloadingTerrainScreen
                || screen instanceof ConnectScreen
                || screen instanceof ProgressScreen;
    }

    @Unique
    private boolean koil$isDesignMusicEnabled() {
        try {
            JsonElement element = JSONFileEditor.getValueFromJson("./koil/sys/config.json", "designMusic");
            return element != null && element.isJsonPrimitive() && element.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    @Unique
    private void koil$setOptionsBackgroundBlank(boolean blank) {
        OPTIONS_BACKGROUND_TEXTURE = blank
                ? loadExternalPngTexture(uiImageDirectory, "options_background_blank.png")
                : new Identifier("textures/gui/options_background.png");
    }

    @Unique
    private boolean koil$isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    @Unique
    private int koil$screenWidth() {
        if (client != null && client.currentScreen != null && client.currentScreen.width > 0) {
            return client.currentScreen.width;
        }
        if (client != null && client.getWindow() != null) {
            return Math.max(1, client.getWindow().getScaledWidth());
        }
        return Math.max(1, width);
    }

    @Unique
    private int koil$screenHeight() {
        if (client != null && client.currentScreen != null && client.currentScreen.height > 0) {
            return client.currentScreen.height;
        }
        if (client != null && client.getWindow() != null) {
            return Math.max(1, client.getWindow().getScaledHeight());
        }
        return Math.max(1, height);
    }

    @Unique
    private static int KOIL_TOP_BAR_BUTTON_RIGHT_SAFE_SIZE() {
        return KOIL_TOP_BAR_BUTTON_SIZE + KOIL_TOP_BAR_RIGHT_MARGIN;
    }

    @Unique
    private VanillaScreenToolResolver.ResolvedScreenTools koil$resolvedTools() {
        return VanillaScreenToolResolver.resolve((Screen) (Object) this);
    }

    @Unique
    private void koil$handleToolAction(String actionId, VanillaScreenToolResolver.ResolvedScreenTools tools, double mouseX, double mouseY) {
        if (tools.actionHandler() != null) {
            tools.actionHandler().handle(actionId, tools, mouseX, mouseY, koil$screenWidth(), koil$screenHeight(), koil$screenInfoPopup);
        }
    }
}
