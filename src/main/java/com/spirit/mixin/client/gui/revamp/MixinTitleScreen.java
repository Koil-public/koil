package com.spirit.mixin.client.gui.revamp;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.spirit.client.gui.DesignMusicController;
import com.spirit.client.gui.UiSoundHelper;
import com.spirit.client.gui.main.ConsoleToast;
import com.spirit.client.gui.main.FirstLaunchTermsScreen;
import com.spirit.client.gui.main.KoilMessageToast;
import com.spirit.koil.api.design.DesignLoader;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import com.spirit.koil.api.util.system.DeviceInfoManager;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.spirit.koil.api.design.uiColorVal.uiColorBackgroundBorder;
import static com.spirit.koil.api.design.uiColorVal.uiColorContentBase;
import static com.spirit.koil.api.design.uiColorVal.uiColorContentBaseTitleText;
import static com.spirit.koil.api.design.uiColorVal.uiColorHeaderStripe;
import static com.spirit.Main.*;

@Mixin(TitleScreen.class)
public abstract class MixinTitleScreen extends Screen {
    @Mutable @Shadow @Final private static Identifier PANORAMA_OVERLAY;
    @Shadow public abstract void render(DrawContext context, int mouseX, int mouseY, float delta);

    protected MixinTitleScreen(Text title) {
        super(title);
    }

    private static boolean toastShown = false;
    private static boolean debugToastShown = false;
    private static long titleInitializedAtMillis = 0L;
    private CheckboxWidget designMusicCheckbox;
    private boolean koil$versionPopupOpen;
    private int koil$versionLabelX = 8;
    private int koil$versionLabelY = 0;
    private int koil$versionLabelWidth = 0;
    private int koil$versionPopupScroll;
    private boolean koil$versionPopupScrollbarDragging;
    private int koil$versionPopupScrollbarDragOffset;

    @Inject(method = "init", at = @At("TAIL"))
    private void koil$showFirstLaunchTerms(CallbackInfo ci) {
        if (isFirstLaunchPending()) {
            MinecraftClient.getInstance().setScreen(new FirstLaunchTermsScreen());
            return;
        }
        titleInitializedAtMillis = System.currentTimeMillis();
        koil$showTitleToastsOnce();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo info) throws IOException {
        Identifier vanillaOverlay = new Identifier("textures/gui/title/background/panorama_overlay.png");
        PANORAMA_OVERLAY = vanillaOverlay;
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            JsonElement designMusicElement = JSONFileEditor.getValueFromJson("./koil/sys/config.json", "designMusic");
            boolean isDesignMusicModeEnabled = designMusicElement != null && designMusicElement.isJsonPrimitive() && designMusicElement.getAsBoolean();
            boolean menuPanoramaEnabled = koil$configBoolean("menuPanorama", false);
            if (!menuPanoramaEnabled) {
                KoilScreenBackgrounds.render(context, this.client, this.width, this.height);
                context.fill(0, 0, this.width, this.height, KoilScreenBackgrounds.overlayColor(this.client));
                if (!isDesignMusicModeEnabled) {
                    context.fill(0, 0, this.width, this.height, 0x30000000);
                }
            }
            if (isDesignMusicModeEnabled) {
                DesignMusicController.startDesignMusic(true);
                Set<Class<?>> customBackgroundScreens = new HashSet<>();
                customBackgroundScreens.add(TitleScreen.class);
                assert client.currentScreen != null;
                if (!menuPanoramaEnabled && customBackgroundScreens.contains(client.currentScreen.getClass())) {
                    Identifier loadingTexture = DesignLoader.getLoadingTexture();
                    PANORAMA_OVERLAY = loadingTexture == null ? vanillaOverlay : loadingTexture;
                }
            } else {
                DesignMusicController.stopDesignMusic();
            }
        } else {
            JSONFileEditor.updateValueInJson("./koil/sys/config.json", "designMusic", new JsonPrimitive(false));
            DesignMusicController.stopDesignMusic();
        }
        koil$showDelayedDebugToast();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void koil$renderVersionLauncher(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo info) {
        if (!koil$configBoolean("uiRedesign", false) || this.client == null || this.client.textRenderer == null) {
            return;
        }
        TextRenderer renderer = this.client.textRenderer;
        String label = "Version";
        this.koil$versionLabelWidth = renderer.getWidth(label);
        this.koil$versionLabelY = this.height - 15;
        boolean hovered = mouseX >= this.koil$versionLabelX - 2
                && mouseX <= this.koil$versionLabelX + this.koil$versionLabelWidth + 2
                && mouseY >= this.koil$versionLabelY - 2
                && mouseY <= this.koil$versionLabelY + 12;
        int color = hovered ? 0xFFFFFFFF : 0xFFE4EAF1;
        koil$drawReadableText(context, renderer, label, this.koil$versionLabelX, this.koil$versionLabelY, color);
        int underlineY = this.koil$versionLabelY + 10;
        context.fill(this.koil$versionLabelX, underlineY, this.koil$versionLabelX + this.koil$versionLabelWidth, underlineY + 1, hovered ? 0xFFFFFFFF : 0xBFE4EAF1);
        if (this.koil$versionPopupOpen) {
            koil$renderVersionPopup(context, renderer, mouseX, mouseY);
        }
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;III)I"
            ),
            require = 0
    )
    private int koil$suppressVanillaVersionString(DrawContext context, TextRenderer textRenderer, String text, int x, int y, int color) {
        if (koil$shouldReplaceVersionText(text, y)) {
            return textRenderer.getWidth(text);
        }
        return context.drawTextWithShadow(textRenderer, text, x, y, color);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)I"
            ),
            require = 0
    )
    private int koil$suppressVanillaVersionText(DrawContext context, TextRenderer textRenderer, Text text, int x, int y, int color) {
        if (text != null && koil$shouldReplaceVersionText(text.getString(), y)) {
            return textRenderer.getWidth(text);
        }
        return context.drawTextWithShadow(textRenderer, text, x, y, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !koil$configBoolean("uiRedesign", false)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (this.koil$versionPopupOpen && koil$isOverVersionPopupScrollbar(mouseX, mouseY)) {
            int thumbY = koil$versionPopupThumbY();
            int thumbHeight = koil$versionPopupThumbHeight();
            this.koil$versionPopupScrollbarDragging = true;
            this.koil$versionPopupScrollbarDragOffset = mouseY >= thumbY && mouseY <= thumbY + thumbHeight ? (int) mouseY - thumbY : thumbHeight / 2;
            koil$setVersionPopupScrollFromThumbTop((int) mouseY - this.koil$versionPopupScrollbarDragOffset);
            return true;
        }
        if (mouseX >= this.koil$versionLabelX - 2
                && mouseX <= this.koil$versionLabelX + this.koil$versionLabelWidth + 2
                && mouseY >= this.koil$versionLabelY - 2
                && mouseY <= this.koil$versionLabelY + 12) {
            this.koil$versionPopupOpen = !this.koil$versionPopupOpen;
            this.koil$versionPopupScroll = 0;
            UiSoundHelper.playButtonClick();
            return true;
        }
        if (this.koil$versionPopupOpen && !koil$isInsideVersionPopup(mouseX, mouseY)) {
            this.koil$versionPopupOpen = false;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && this.koil$versionPopupScrollbarDragging) {
            koil$setVersionPopupScrollFromThumbTop((int) mouseY - this.koil$versionPopupScrollbarDragOffset);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.koil$versionPopupScrollbarDragging) {
            this.koil$versionPopupScrollbarDragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!this.koil$versionPopupOpen || !koil$isInsideVersionPopup(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, amount);
        }
        int maxScroll = Math.max(0, koil$versionModRows().size() * 12 - koil$versionPopupModViewportHeight());
        this.koil$versionPopupScroll = Math.max(0, Math.min(maxScroll, this.koil$versionPopupScroll - (int) amount * 18));
        return true;
    }

    private boolean koil$shouldReplaceVersionText(String text, int y) {
        if (!koil$configBoolean("uiRedesign", false) || text == null || y < this.height - 28) {
            return false;
        }
        String normalized = text.toLowerCase();
        return normalized.contains("minecraft")
                || normalized.contains("fabric")
                || normalized.contains("fabric loader");
    }

    private void koil$renderVersionPopup(DrawContext context, TextRenderer renderer, int mouseX, int mouseY) {
        int x = this.koil$versionLabelX;
        int height = Math.min(210, Math.max(132, this.height - 42));
        int y = Math.max(12, this.koil$versionLabelY - height - 8);
        int width = Math.min(260, Math.max(190, this.width - 16));
        context.getMatrices().push();
        context.getMatrices().translate(0.0F, 0.0F, 4096.0F);
        context.fill(x + 2, y + 3, x + width + 3, y + height + 3, 0x66000000);
        context.fill(x, y, x + width, y + height, koil$withAlpha(uiColorContentBase, 238));
        context.fill(x, y, x + width, y + 4, koil$withAlpha(uiColorHeaderStripe, 218));
        context.fill(x, y, x + 2, y + height, koil$withAlpha(uiColorHeaderStripe, 155));
        context.fill(x + width - 2, y, x + width, y + height, koil$withAlpha(uiColorHeaderStripe, 155));
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());

        koil$drawReadableText(context, renderer, "Main Versions", x + 8, y + 9, new Color(uiColorContentBaseTitleText, true).getRGB());
        int rowY = y + 26;
        rowY = koil$renderVersionPair(context, renderer, x + 8, rowY, width - 16, "Minecraft", SharedConstants.getGameVersion().getName(), 0xFF8FC5FF);
        rowY = koil$renderVersionPair(context, renderer, x + 8, rowY, width - 16, "Fabric", koil$modVersion("fabricloader"), 0xFFE4EAF1);
        rowY = koil$renderVersionPair(context, renderer, x + 8, rowY, width - 16, "Koil", version(), 0xFF8FEA84);
        context.fill(x + 8, rowY + 3, x + width - 8, rowY + 4, koil$withAlpha(uiColorBackgroundBorder, 150));
        koil$drawReadableText(context, renderer, "Mod Versions", x + 8, rowY + 10, 0xFFD8DFE9);

        int modTop = rowY + 25;
        int modBottom = y + height - 8;
        List<String> rows = koil$versionModRows();
        int maxScroll = Math.max(0, rows.size() * 12 - Math.max(1, modBottom - modTop));
        this.koil$versionPopupScroll = Math.max(0, Math.min(this.koil$versionPopupScroll, maxScroll));
        context.enableScissor(x + 5, modTop, x + width - 5, modBottom);
        int textY = modTop - this.koil$versionPopupScroll;
        for (String row : rows) {
            if (textY > modTop - 12 && textY < modBottom) {
                koil$drawReadableText(context, renderer, renderer.trimToWidth(row, width - 20), x + 8, textY, 0xFFC8D2DE);
            }
            textY += 12;
        }
        context.disableScissor();
        if (maxScroll > 0) {
            int trackX = koil$versionPopupTrackX();
            int thumbHeight = koil$versionPopupThumbHeight();
            int thumbY = koil$versionPopupThumbY();
            context.fill(trackX, modTop, trackX + 2, modBottom, 0x3867727F);
            context.fill(trackX - 1, thumbY, trackX + 3, thumbY + thumbHeight, 0xA8D8E2EE);
        }
        context.getMatrices().pop();
    }

    private int koil$renderVersionPair(DrawContext context, TextRenderer renderer, int x, int y, int width, String label, String value, int valueColor) {
        koil$drawReadableText(context, renderer, label, x, y, 0xFF9EACBA);
        String trimmed = renderer.trimToWidth(value, Math.max(30, width - 82));
        koil$drawReadableText(context, renderer, trimmed, x + 76, y, valueColor);
        return y + 13;
    }

    private List<String> koil$versionModRows() {
        List<String> rows = new ArrayList<>();
        FabricLoader.getInstance().getAllMods().stream()
                .sorted(Comparator.comparing(container -> container.getMetadata().getName().toLowerCase()))
                .forEach(container -> {
                    String id = container.getMetadata().getId();
                    if (!id.equals("minecraft") && !id.equals("fabricloader") && !id.equals("java")) {
                        rows.add(container.getMetadata().getName() + "  " + container.getMetadata().getVersion().getFriendlyString());
                    }
                });
        return rows;
    }

    private String koil$modVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(ModContainer::getMetadata)
                .map(metadata -> metadata.getVersion().getFriendlyString())
                .orElse("Unknown");
    }

    private boolean koil$isInsideVersionPopup(double mouseX, double mouseY) {
        int height = Math.min(210, Math.max(132, this.height - 42));
        int y = Math.max(12, this.koil$versionLabelY - height - 8);
        int width = Math.min(260, Math.max(190, this.width - 16));
        int x = this.koil$versionLabelX;
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private int koil$versionPopupModViewportHeight() {
        int height = Math.min(210, Math.max(132, this.height - 42));
        return Math.max(1, height - 102);
    }

    private int koil$versionPopupX() {
        return this.koil$versionLabelX;
    }

    private int koil$versionPopupY() {
        int height = koil$versionPopupHeight();
        return Math.max(12, this.koil$versionLabelY - height - 8);
    }

    private int koil$versionPopupWidth() {
        return Math.min(260, Math.max(190, this.width - 16));
    }

    private int koil$versionPopupHeight() {
        return Math.min(210, Math.max(132, this.height - 42));
    }

    private int koil$versionPopupModTop() {
        return koil$versionPopupY() + 90;
    }

    private int koil$versionPopupModBottom() {
        return koil$versionPopupY() + koil$versionPopupHeight() - 8;
    }

    private int koil$versionPopupMaxScroll() {
        return Math.max(0, koil$versionModRows().size() * 12 - Math.max(1, koil$versionPopupModBottom() - koil$versionPopupModTop()));
    }

    private int koil$versionPopupTrackX() {
        return koil$versionPopupX() + koil$versionPopupWidth() - 6;
    }

    private int koil$versionPopupThumbHeight() {
        int viewportHeight = Math.max(1, koil$versionPopupModBottom() - koil$versionPopupModTop());
        int maxScroll = koil$versionPopupMaxScroll();
        return Math.max(18, (int) (viewportHeight / (float) (viewportHeight + Math.max(1, maxScroll)) * viewportHeight));
    }

    private int koil$versionPopupThumbY() {
        int modTop = koil$versionPopupModTop();
        int viewportHeight = Math.max(1, koil$versionPopupModBottom() - modTop);
        int thumbHeight = koil$versionPopupThumbHeight();
        int maxScroll = koil$versionPopupMaxScroll();
        int thumbTravel = Math.max(1, viewportHeight - thumbHeight);
        return modTop + (int) (this.koil$versionPopupScroll / (float) Math.max(1, maxScroll) * thumbTravel);
    }

    private boolean koil$isOverVersionPopupScrollbar(double mouseX, double mouseY) {
        if (koil$versionPopupMaxScroll() <= 0) {
            return false;
        }
        int trackX = koil$versionPopupTrackX();
        return mouseX >= trackX - 6
                && mouseX <= trackX + 10
                && mouseY >= koil$versionPopupModTop()
                && mouseY <= koil$versionPopupModBottom();
    }

    private void koil$setVersionPopupScrollFromThumbTop(int thumbTop) {
        int modTop = koil$versionPopupModTop();
        int viewportHeight = Math.max(1, koil$versionPopupModBottom() - modTop);
        int thumbHeight = koil$versionPopupThumbHeight();
        int thumbTravel = Math.max(1, viewportHeight - thumbHeight);
        int clampedTop = Math.max(modTop, Math.min(modTop + thumbTravel, thumbTop));
        double progress = (clampedTop - modTop) / (double) thumbTravel;
        this.koil$versionPopupScroll = (int) Math.round(progress * koil$versionPopupMaxScroll());
    }

    private void koil$drawReadableText(DrawContext context, TextRenderer renderer, String text, int x, int y, int color) {
        context.drawText(renderer, text, x - 1, y, 0xAA000000, false);
        context.drawText(renderer, text, x + 1, y, 0xAA000000, false);
        context.drawText(renderer, text, x, y - 1, 0xAA000000, false);
        context.drawText(renderer, text, x, y + 1, 0xAA000000, false);
        context.drawText(renderer, text, x, y, color, false);
    }

    private int koil$withAlpha(int argbColor, int alpha) {
        Color color = new Color(argbColor, true);
        int clampedAlpha = Math.max(0, Math.min(255, alpha));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clampedAlpha).getRGB();
    }

    private boolean koil$configBoolean(String key, boolean fallback) {
        try {
            JsonElement element = JSONFileEditor.getValueFromJson("./koil/sys/config.json", key);
            return element != null && element.isJsonPrimitive() ? element.getAsBoolean() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void koil$showTitleToastsOnce() {
        if (toastShown) {
            return;
        }
        toastShown = true;
        try {
            showToast();
        } catch (Exception exception) {
            SUBLOGGER.logE("Title Screen", "Failed to queue Koil title toasts: " + exception.getMessage());
        }
    }

    private void showToast() throws IOException {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getToastManager() == null) {
            return;
        }

        if (!DeviceInfoManager.hasInternetAccess()) {
            ConsoleToast.add(client.getToastManager(), ConsoleToast.Type.CONSOLE,
                    Text.of("Internet Connection Unavailable"), Text.of("Please reconnect to a provider!"));
        }

        if (!JSONFileEditor.getValueFromJson("./koil/sys/config.json", "disableMaintenanceToast").getAsBoolean()) {
            if (JSONFileEditor.getValueFromJson("./koil/sys/sys.json", "maintenance").getAsBoolean()) {
                SUBLOGGER.logW("System Management thread", "\n [=====-==-========--========! The system is currently under maintenance !========--========-==-=====]\n\nReason: " + JSONFileEditor.getValueFromJson("./koil/sys/sys.json", "maintenanceReason") + "\n\n [=====-==-========--========! ######################################### !========--========-==-=====]\n", true, "The system is under maintenance, Things will be iffy...");
                JSONFileEditor.updateValueInJson("./koil/sys/sys.json", "maintenance", new JsonPrimitive(false));
            }
        }

        if (!JSONFileEditor.getValueFromJson("./koil/sys/config.json", "disableAnnouncementToast").getAsBoolean()) {
            if (JSONFileEditor.getValueFromJson("./koil/sys/sys.json", "announcement").getAsBoolean()) {
                String announcementReason = JSONFileEditor.getValueFromJson("./koil/sys/sys.json", "announcementReason").getAsString();
                KoilMessageToast.add(client.getToastManager(), KoilMessageToast.Type.ANNOUNCEMENT, Text.of("Announcement:"), Text.of(announcementReason));
                JSONFileEditor.updateValueInJson("./koil/sys/sys.json", "announcement", new JsonPrimitive(false));
            }
        }
    }

    private void koil$showDelayedDebugToast() {
        if (debugToastShown || titleInitializedAtMillis <= 0L || System.currentTimeMillis() - titleInitializedAtMillis < 2200L) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null || client.getToastManager() == null) {
            return;
        }
        try {
            if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "disableDebugToast").getAsBoolean()) {
                return;
            }
            JsonElement betaTestingElement = JSONFileEditor.getValueFromJson("./koil/sys/config.json", "isBetaTesting");
            boolean betaTestingEnabled = betaTestingElement != null && betaTestingElement.isJsonPrimitive() && betaTestingElement.getAsBoolean();
            if (betaTestingEnabled || isBetaTesting) {
                ConsoleToast.add(client.getToastManager(), ConsoleToast.Type.CONSOLE_DEBUG,
                        Text.of("You are running an unreleased version! (" + version() + ")"), Text.of("Caution is advised."));
                debugToastShown = true;
            }
        } catch (Exception exception) {
            SUBLOGGER.logE("Title Screen", "Failed to queue delayed debug toast: " + exception.getMessage());
            debugToastShown = true;
        }
    }
}
