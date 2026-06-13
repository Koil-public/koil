package com.spirit.mixin.client.gui.revamp.world;

import com.spirit.client.gui.BrowserLayoutHelper;
import com.spirit.client.gui.FolderOpenHelper;
import com.spirit.client.gui.PopupMenu;
import com.spirit.client.gui.world.DiscoveredWorldListWidget;
import com.spirit.koil.api.design.DesignLoader;
import com.spirit.koil.api.world.LocalWorldDiscovery;
import com.spirit.koil.api.util.file.image.ExternalImageLoader;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import com.spirit.mixin.client.gui.revamp.accessor.WorldListWidgetWorldEntryAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.VanillaDataPackProvider;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.world.level.storage.SaveVersionInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.spirit.Main.uiImageDirectory;
import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
@Mixin(SelectWorldScreen.class)
public abstract class MixinSelectWorldScreen extends Screen {
    private static final int KOIL_WORLD_LIST_WIDTH = BrowserLayoutHelper.LIST_INNER_RIGHT - BrowserLayoutHelper.LIST_INNER_LEFT - 16;
    private static final int KOIL_DISCOVERED_WORLD_LIST_WIDTH = KOIL_WORLD_LIST_WIDTH + 12;
    private static final int KOIL_WORLD_LIST_LEFT = BrowserLayoutHelper.LIST_INNER_LEFT;
    private static final int KOIL_WORLD_LIST_TOP = 39;
    private static final int KOIL_WORLD_LIST_BOTTOM_PADDING = 57;
    private static final int KOIL_WORLD_LIST_ENTRY_HEIGHT = 36;
    private static final Identifier KOIL_FALLBACK_WORLD_ICON = ExternalImageLoader.loadExternalPngTextureWithColorVariant(uiImageDirectory, "image.png");

    @Shadow protected TextFieldWidget searchBox;
    @Shadow private WorldListWidget levelList;
    @Shadow private ButtonWidget selectButton;
    @Shadow private ButtonWidget editButton;
    @Shadow private ButtonWidget deleteButton;
    @Shadow private ButtonWidget recreateButton;
    private ButtonWidget openIPButton;
    private ButtonWidget copyIPButton;
    private ButtonWidget ipButton;
    private ButtonWidget inDevButton;
    private ButtonWidget findWorldsButton;
    private final PopupMenu folderOpenPopup = new PopupMenu();
    private File pendingFolderOpenDirectory;
    private int lastMouseX;
    private int lastMouseY;
    @Shadow @Final protected Screen parent;
    private WorldListWidget.WorldEntry selectedWorld;
    private int previewScrollOffset;
    private int previewScrollMax;
    private int previewViewportX = -1;
    private int previewViewportY = -1;
    private int previewViewportWidth;
    private int previewViewportHeight;
    private boolean previewScrollbarDragging;
    private int previewScrollbarDragOffset;
    private String previewSelectionKey;
    private boolean localWorldDiscoveryMode;
    private boolean localWorldShowIncompatible;
    private boolean localWorldDiscoveryLoading;
    private List<LocalWorldDiscovery.DiscoveredWorld> discoveredWorlds = List.of();
    private LocalWorldDiscovery.DiscoveredWorld selectedDiscoveredWorld;
    private DiscoveredWorldListWidget discoveredWorldListWidget;
    private int compatibilityFilterX = -1;
    private int compatibilityFilterWidth = 0;
    private int discoveredViewportX = -1;
    private int discoveredViewportY = -1;
    private int discoveredViewportWidth;
    private int discoveredViewportHeight;

    protected MixinSelectWorldScreen(Text title) {
        super(title);
    }

    @Override
    public void renderBackgroundTexture(DrawContext context) {
        context.setShaderColor(0.25F, 0.25F, 0.25F, 0F);
        context.drawTexture(OPTIONS_BACKGROUND_TEXTURE, 0, 0, 0, 0.0F, 0.0F, this.width, this.height, 32, 32);
        context.setShaderColor(1.0F, 1.0F, 1.0F, 0F);
    }

    /**
     * @author SpiritXIV
     * @reason button fix
     */
    @Overwrite
    public void worldSelected(boolean buttonsActive, boolean deleteButtonActive) {
        if (this.localWorldDiscoveryMode) {
            boolean discoveredSelected = this.selectedDiscoveredWorld != null;
            this.selectButton.active = discoveredSelected;
            this.editButton.active = false;
            this.recreateButton.active = false;
            this.deleteButton.active = false;
            if (this.copyIPButton != null) {
                this.copyIPButton.active = discoveredSelected;
            }
            if (this.openIPButton != null) {
                this.openIPButton.active = false;
            }
            if (this.ipButton != null) {
                this.ipButton.active = true;
            }
            if (this.inDevButton != null) {
                this.inDevButton.active = false;
            }
            return;
        }
        this.selectButton.active = buttonsActive;
        this.editButton.active = buttonsActive;
        this.recreateButton.active = buttonsActive;
        this.deleteButton.active = deleteButtonActive;
        syncSelectedWorldFromList();
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            this.openIPButton.active = buttonsActive;
            this.copyIPButton.active = buttonsActive;
            this.ipButton.active = false;
            this.inDevButton.active = false;
        } else {
            if (this.openIPButton != null) {
                this.openIPButton.active = buttonsActive;
            }
            if (this.copyIPButton != null) {
                this.copyIPButton.active = buttonsActive;
            }
            if (this.ipButton != null) {
                this.ipButton.active = false;
            }
            if (this.inDevButton != null) {
                this.inDevButton.active = false;
            }
        }
    }

    /**
     * @author SpiritXIV
     * @reason WE BE BALLING
     */
    @Overwrite
    protected void init() {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            int x = 37;
            this.searchBox = new TextFieldWidget(this.textRenderer, width - 210, 10, 200, 20, this.searchBox, Text.translatable("selectWorld.search"));
            this.searchBox.setPlaceholder(Text.literal("Search Worlds"));
            this.searchBox.setChangedListener((search) -> {
                if (this.localWorldDiscoveryMode) {
                    syncSelectedDiscoveredWorld();
                    updateDiscoveredWorldList();
                } else {
                    this.levelList.setSearch(search);
                }
            });
            this.levelList = new WorldListWidget((SelectWorldScreen) (Object) this, this.client, KOIL_WORLD_LIST_WIDTH, this.height, KOIL_WORLD_LIST_TOP, this.height - KOIL_WORLD_LIST_BOTTOM_PADDING, KOIL_WORLD_LIST_ENTRY_HEIGHT, this.searchBox.getText(), this.levelList);
            this.levelList.setLeftPos(KOIL_WORLD_LIST_LEFT);
            this.discoveredWorldListWidget = new DiscoveredWorldListWidget(this.client, KOIL_DISCOVERED_WORLD_LIST_WIDTH, this.height, KOIL_WORLD_LIST_TOP, this.height - KOIL_WORLD_LIST_BOTTOM_PADDING, KOIL_WORLD_LIST_ENTRY_HEIGHT, this::resolveDiscoveredIcon, () -> this.selectedDiscoveredWorld, this::selectDiscoveredWorld);
            this.discoveredWorldListWidget.setLeftPos(KOIL_WORLD_LIST_LEFT);
            this.addSelectableChild(this.searchBox);
            this.addSelectableChild(this.levelList);
            this.addSelectableChild(this.discoveredWorldListWidget);
            this.selectButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectWorld.select"), (button) -> {
                if (this.localWorldDiscoveryMode) {
                    openSelectedDiscoveredWorld();
                } else {
                    this.levelList.getSelectedAsOptional().ifPresent(WorldListWidget.WorldEntry::play);
                }
            }).dimensions(x, this.height - 52, 150, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectWorld.create"), (button) -> {
                assert this.client != null;
                CreateWorldScreen.create(this.client, this);
            }).dimensions(x + 158, this.height - 52, 150, 20).build());
            this.editButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectWorld.edit"), (button) -> this.levelList.getSelectedAsOptional().ifPresent(WorldListWidget.WorldEntry::edit)).dimensions(x, this.height - 28, 74, 20).build());

            this.deleteButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectWorld.delete"), (button) -> this.levelList.getSelectedAsOptional().ifPresent(WorldListWidget.WorldEntry::deleteIfConfirmed)).dimensions(x + 78, this.height - 28, 72, 20).tooltip(Tooltip.of(Text.literal("[!] This cannot be undone!").formatted(Formatting.RED))).build());
            this.recreateButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectWorld.recreate"), (button) -> this.levelList.getSelectedAsOptional().ifPresent(WorldListWidget.WorldEntry::recreate)).dimensions(x + 158, this.height - 28, 73, 20).build());
            this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, (button) -> {
                assert this.client != null;
                this.client.setScreen(this.parent);
            }).dimensions(x + 235, this.height - 28, 73, 20).build());
            this.copyIPButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Open Folder"), (button) -> {
                if (this.localWorldDiscoveryMode) {
                    openSelectedDiscoveredFolder();
                } else {
                    openSelectedWorldFolder();
                }
            }).dimensions(x + 316, this.height - 52, 74, 20).build());
            this.openIPButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Datapacks"), (button) -> openVanillaDatapackScreen()).dimensions(x + 316, this.height - 28, 74, 20).build());
            this.findWorldsButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Find Worlds"), (button) -> toggleLocalWorldDiscovery()).dimensions(x + 394, this.height - 52, 120, 20).build());
            this.inDevButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Indev"), (button) -> {}).dimensions(x + 394, this.height - 28, 120, 20).build());
            this.inDevButton.active = false;
            this.ipButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Compatible Only"), (button) -> toggleLocalWorldFilter()).dimensions(this.width - 332, 10, 116, 20).build());
            selectFirstWorldEntry();
            this.worldSelected(false, false);
            this.setInitialFocus(this.searchBox);
        } else {
            this.searchBox = new TextFieldWidget(this.textRenderer, this.width / 2 - 100, 22, 200, 20, this.searchBox, Text.translatable("selectWorld.search"));
            this.searchBox.setPlaceholder(Text.literal("Search Worlds"));
            this.searchBox.setChangedListener((search) -> this.levelList.setSearch(search));
            this.levelList = new WorldListWidget((SelectWorldScreen) (Object) this, this.client, this.width, this.height, 48, this.height - 64, 36, this.searchBox.getText(), this.levelList);
            this.addSelectableChild(this.searchBox);
            this.addSelectableChild(this.levelList);
            this.selectButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectWorld.select"), (button) -> this.levelList.getSelectedAsOptional().ifPresent(WorldListWidget.WorldEntry::play)).dimensions(this.width / 2 - 154, this.height - 52, 150, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectWorld.create"), (button) -> CreateWorldScreen.create(this.client, this)).dimensions(this.width / 2 + 4, this.height - 52, 150, 20).build());
            this.editButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectWorld.edit"), (button) -> this.levelList.getSelectedAsOptional().ifPresent(WorldListWidget.WorldEntry::edit)).dimensions(this.width / 2 - 154, this.height - 28, 72, 20).build());
            this.deleteButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectWorld.delete"), (button) -> this.levelList.getSelectedAsOptional().ifPresent(WorldListWidget.WorldEntry::deleteIfConfirmed)).dimensions(this.width / 2 - 76, this.height - 28, 72, 20).build());
            this.recreateButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectWorld.recreate"), (button) -> this.levelList.getSelectedAsOptional().ifPresent(WorldListWidget.WorldEntry::recreate)).dimensions(this.width / 2 + 4, this.height - 28, 72, 20).build());
            this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, (button) -> this.client.setScreen(this.parent)).dimensions(this.width / 2 + 82, this.height - 28, 72, 20).build());
            int rightToolX = Math.min(this.width - 78, this.width / 2 + 160);
            this.copyIPButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Open Folder"), (button) -> openSelectedWorldFolder()).dimensions(rightToolX, this.height - 52, 74, 20).build());
            this.openIPButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Datapacks"), (button) -> {
                openVanillaDatapackScreen();
            }).dimensions(rightToolX, this.height - 28, 74, 20).build());
            this.findWorldsButton = this.addDrawableChild(ButtonWidget.builder(Text.of(""), (button) -> {}).dimensions(-1000, -1000, 1, 20).build());
            this.ipButton = this.addDrawableChild(ButtonWidget.builder(Text.of(""), (button) -> {}).dimensions(-1000, -1000, 1, 20).build());
            this.inDevButton = this.addDrawableChild(ButtonWidget.builder(Text.of(""), (button) -> {}).dimensions(-1000, -1000, 1, 20).build());
            this.worldSelected(false, false);
            this.setInitialFocus(this.searchBox);
        }
    }
    
    @Inject(method = "render", at = @At(value = "HEAD"), cancellable = true)
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            this.lastMouseX = mouseX;
            this.lastMouseY = mouseY;
            MinecraftClient client = MinecraftClient.getInstance();
            context.drawTexture(DesignLoader.getLoadingTexture(), 0, 0, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), 0.0F, 0.0F, 319, 192, 319, 192);

            context.fill(0, 0, width, 43, new Color(uiColorHeader, true).getRGB());
            context.fill(0, 39, width, 42, new Color(uiColorHeaderStripe, true).getRGB());
            context.fill(0, height - 60, width, height, new Color(uiColorFooter, true).getRGB());
            context.fill(0, this.height - 60, width, this.height - 57, new Color(uiColorFooterStripe, true).getRGB());
            context.fill(35, 39, 350, this.height - 60, new Color(uiColorContentBase, true).getRGB());
            context.fill(37, 39, 39, this.height - 60, new Color(uiColorContentStripeLeft, true).getRGB());
            context.fill(346, 39, 348, this.height - 60, new Color(uiColorContentStripeRight, true).getRGB());
            context.fill(0, 0, width, height, new Color(uiColorBackgroundOverlay, true).getRGB());

            if (this.ipButton != null) {
                this.ipButton.visible = false;
                this.ipButton.active = false;
            }
            if (this.findWorldsButton != null) {
                this.findWorldsButton.setMessage(Text.literal(this.localWorldDiscoveryMode ? "Saved Worlds" : "Find Worlds"));
            }

            Optional<WorldListWidget.WorldEntry> selectedWorldOptional = this.localWorldDiscoveryMode ? Optional.empty() : this.levelList.getSelectedAsOptional();
            if (this.localWorldDiscoveryMode) {
                syncSelectedDiscoveredWorld();
                renderDiscoveredWorldList(context, mouseX, mouseY);
                renderSelectedDiscoveredWorldInfo(context);
                this.worldSelected(false, false);
            } else if (selectedWorldOptional.isPresent()) {
                this.selectedWorld = selectedWorldOptional.get();
                this.copyIPButton.active = true;
                this.openIPButton.active = true;
                this.ipButton.active = false;
                this.inDevButton.active = false;
                syncPreviewSelection();
                renderSelectedWorldInfo(context);
            } else {
                this.selectedWorld = null;
                this.previewSelectionKey = null;
                this.previewScrollOffset = 0;
                this.previewScrollMax = 0;
                this.copyIPButton.active = false;
                this.openIPButton.active = false;
                this.ipButton.active = false;
                this.inDevButton.active = false;
            }

            if (!this.localWorldDiscoveryMode) {
                this.levelList.render(context, mouseX, mouseY, delta);
            }
            this.searchBox.render(context, mouseX, mouseY, delta);
            renderCompatibilityFilterButton(context);
            context.getMatrices().push();
            context.getMatrices().scale(1.5F, 1.5F, 1.0F);
            context.drawText(this.textRenderer, Text.translatable("menu.singleplayer"), 25, 6, new Color(uiColorHeaderTitleText, true).getRGB(), true);
            context.getMatrices().pop();
            context.drawText(this.textRenderer, this.title, 37, 23, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);

            super.render(context, mouseX, mouseY, delta);
            this.folderOpenPopup.render(context, mouseX, mouseY);
            ci.cancel();
        } else {
            this.levelList.render(context, mouseX, mouseY, delta);
            this.searchBox.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 16777215);
            super.render(context, mouseX, mouseY, delta);
            this.folderOpenPopup.render(context, mouseX, mouseY);
        }
    }

    @SuppressWarnings("unused")
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;drawCenteredTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V"), cancellable = true)
    private void onDrawCenteredText(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            this.levelList.render(context, mouseX, mouseY, delta);
            this.searchBox.render(context, mouseX, mouseY, delta);
            context.getMatrices().push();
            context.getMatrices().scale(1.5F, 1.5F, 1.0F);
            context.drawText(this.textRenderer, Text.translatable("menu.singleplayer"), 25, 6, new Color(uiColorHeaderTitleText, true).getRGB(), true);
            context.getMatrices().pop();
            context.drawText(this.textRenderer, this.title, 37, 23, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);

            super.render(context, mouseX, mouseY, delta);
            this.folderOpenPopup.render(context, mouseX, mouseY);
            ci.cancel();
        }
    }

    private void renderSelectedWorldInfo(DrawContext context) {
        LevelSummary level = getSelectedLevel();
        if (level == null) {
            return;
        }
        int panelX = BrowserLayoutHelper.previewX(this.width);
        int panelY = BrowserLayoutHelper.previewY();
        int panelWidth = BrowserLayoutHelper.previewWidth(this.width);
        int panelHeight = BrowserLayoutHelper.previewHeight(this.height);
        BrowserLayoutHelper.renderPreviewPanelFrame(context, panelX, panelY, panelWidth, panelHeight);

        Identifier icon = resolveWorldIcon(level);
        if (icon != null) {
            context.drawTexture(icon, panelX + 10, panelY + 10, 0, 0, 64, 64, 64, 64);
        }
        int titleX = panelX + 84;
        context.getMatrices().push();
        context.getMatrices().scale(1.5F, 1.5F, 1.0F);
        context.drawText(this.textRenderer, trimPreview(level.getDisplayName().isBlank() ? level.getName() : level.getDisplayName(), 170), (int) (titleX / 1.5F), (int) ((panelY + 10) / 1.5F), new Color(uiColorContentBaseTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, level.getName(), titleX, panelY + 34, 0xFFD8DFE9, false);
        String detailText = level.getDetails().getString();
        if (!detailText.isBlank()) {
            context.drawText(this.textRenderer, trimPreview(detailText, panelWidth - 96), titleX, panelY + 48, 0xFFE9DFC9, false);
        }

        int detailsViewportY = panelY + 86;
        int detailsViewportHeight = Math.max(70, panelHeight - (detailsViewportY - panelY) - 8);
        this.previewViewportX = panelX + 8;
        this.previewViewportY = detailsViewportY;
        this.previewViewportWidth = panelWidth - 16;
        this.previewViewportHeight = detailsViewportHeight;
        context.enableScissor(this.previewViewportX, this.previewViewportY, this.previewViewportX + this.previewViewportWidth, this.previewViewportY + this.previewViewportHeight);

        int infoY = detailsViewportY - this.previewScrollOffset;
        int contentHeight = 0;
        BrowserLayoutHelper.renderSectionRule(context, this.textRenderer, panelX + 10, panelX + panelWidth - 10, infoY, "Identity");
        infoY += 16;
        contentHeight += 16;
        int nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Folder", level.getName(), 0xFFDCE4EE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Path", describeWorldPath(), 0xFFD8E7DE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Last Played", formatTimestamp(level.getLastPlayed()), 0xFFD9D5E8);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Icon", describeWorldIcon(level), 0xFFD6E5DA);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Summary", detailText.isBlank() ? "No summary" : detailText, 0xFFD8DFE9);
        contentHeight += nextY - infoY;
        infoY = nextY;

        BrowserLayoutHelper.renderSectionRule(context, this.textRenderer, panelX + 10, panelX + panelWidth - 10, infoY, "Gameplay");
        infoY += 16;
        contentHeight += 16;
        LevelInfo levelInfo = level.getLevelInfo();
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Mode", level.getGameMode().getTranslatableName().getString(), 0xFFDCE4EE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Difficulty", levelInfo == null ? "Unknown" : levelInfo.getDifficulty().getTranslatableName().getString(), 0xFFD8E7DE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Commands", levelInfo != null && levelInfo.areCommandsAllowed() ? "Allowed" : "Off", 0xFFD9D5E8);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Flags", buildWorldFlags(level), 0xFFD6E5DA);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Features", levelInfo == null ? "Unknown" : String.valueOf(levelInfo.getDataConfiguration()), 0xFFD8DFE9);
        contentHeight += nextY - infoY;
        infoY = nextY;

        BrowserLayoutHelper.renderSectionRule(context, this.textRenderer, panelX + 10, panelX + panelWidth - 10, infoY, "Developer Details");
        infoY += 16;
        contentHeight += 16;
        SaveVersionInfo versionInfo = level.getVersionInfo();
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Version", level.getVersion().getString(), 0xFFDCE4EE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Save Info", versionInfo == null ? "Unknown" : versionInfo.getVersionName() + "  |  format " + versionInfo.getLevelFormatVersion(), 0xFFD8E7DE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Storage", buildWorldStorageFlags(level, versionInfo), 0xFFD9D5E8);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Conversion", formatConversionWarning(level), 0xFFD6E5DA);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Lifecycle", buildLifecycleFlags(level, levelInfo, versionInfo), 0xFFE4DAC8);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Data Pack", describeDataConfiguration(levelInfo), 0xFFD2E0CF);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Identity", buildIdentityFlags(level), 0xFFD8DFE9);
        contentHeight += nextY - infoY;
        context.disableScissor();
        this.previewScrollMax = Math.max(0, contentHeight - detailsViewportHeight + 4);
        if (this.previewScrollOffset > this.previewScrollMax) {
            this.previewScrollOffset = this.previewScrollMax;
        }
        renderPreviewScrollbar(context);
    }

    private LevelSummary getSelectedLevel() {
        if (this.selectedWorld == null) {
            return null;
        }
        return ((WorldListWidgetWorldEntryAccessor) (Object) this.selectedWorld).koil$getLevel();
    }

    private Identifier resolveWorldIcon(LevelSummary level) {
        if (level == null || level.getIconPath() == null) {
            return KOIL_FALLBACK_WORLD_ICON;
        }
        File iconFile = level.getIconPath().toFile();
        if (!iconFile.exists()) {
            return KOIL_FALLBACK_WORLD_ICON;
        }
        try {
            Identifier icon = ExternalImageLoader.registerDynamicTexture("koil", "world_icon/" + level.getName(), iconFile);
            return icon != null ? icon : KOIL_FALLBACK_WORLD_ICON;
        } catch (IOException ignored) {
            return KOIL_FALLBACK_WORLD_ICON;
        }
    }

    private void openSelectedWorldFolder() {
        syncSelectedWorldFromList();
        Path path = getSelectedWorldFolder();
        if (path == null || this.copyIPButton == null) {
            return;
        }
        File folder = path.toFile();
        folder.mkdirs();
        this.pendingFolderOpenDirectory = folder;
        this.folderOpenPopup.toggleAtPointer(this.lastMouseX, this.lastMouseY, this.width, this.height, FolderOpenHelper.menuEntries());
    }

    private void openVanillaDatapackScreen() {
        syncSelectedWorldFromList();
        LevelSummary level = getSelectedLevel();
        Path worldFolder = getSelectedWorldFolder();
        if (level == null || worldFolder == null || this.client == null) {
            return;
        }
        Path datapacksFolder = worldFolder.resolve("datapacks");
        try {
            Files.createDirectories(datapacksFolder);
        } catch (IOException e) {
            return;
        }

        ResourcePackManager manager = VanillaDataPackProvider.createManager(datapacksFolder);
        manager.scanPacks();
        LevelInfo levelInfo = level.getLevelInfo();
        DataConfiguration dataConfiguration = levelInfo == null ? DataConfiguration.SAFE_MODE : levelInfo.getDataConfiguration();
        if (dataConfiguration != null && dataConfiguration.dataPacks() != null) {
            manager.setEnabledProfiles(dataConfiguration.dataPacks().getEnabled());
        }

        Screen returnScreen = (Screen) (Object) this;
        this.client.setScreen(new PackScreen(manager, ignored -> this.client.setScreen(returnScreen), datapacksFolder, Text.literal("Data Packs")));
    }

    private void toggleLocalWorldDiscovery() {
        this.localWorldDiscoveryMode = !this.localWorldDiscoveryMode;
        this.previewScrollOffset = 0;
        this.previewScrollMax = 0;
        if (this.localWorldDiscoveryMode && this.discoveredWorlds.isEmpty()) {
            refreshDiscoveredWorlds();
        }
        updateDiscoveredWorldList();
        this.worldSelected(false, false);
    }

    private void toggleLocalWorldFilter() {
        this.localWorldShowIncompatible = !this.localWorldShowIncompatible;
        refreshDiscoveredWorlds();
    }

    private void refreshDiscoveredWorlds() {
        this.localWorldDiscoveryLoading = true;
        this.discoveredWorlds = LocalWorldDiscovery.discover(this.localWorldShowIncompatible);
        this.localWorldDiscoveryLoading = false;
        syncSelectedDiscoveredWorld();
        updateDiscoveredWorldList();
    }

    private void syncSelectedDiscoveredWorld() {
        List<LocalWorldDiscovery.DiscoveredWorld> visible = filteredDiscoveredWorlds();
        if (visible.isEmpty()) {
            this.selectedDiscoveredWorld = null;
            return;
        }
        if (this.selectedDiscoveredWorld == null || visible.stream().noneMatch(world -> world.worldPath().equals(this.selectedDiscoveredWorld.worldPath()))) {
            this.selectedDiscoveredWorld = visible.get(0);
        }
    }

    private List<LocalWorldDiscovery.DiscoveredWorld> filteredDiscoveredWorlds() {
        String query = this.searchBox == null ? "" : this.searchBox.getText().trim().toLowerCase(Locale.ROOT);
        if (query.isBlank()) {
            return this.discoveredWorlds;
        }
        List<LocalWorldDiscovery.DiscoveredWorld> visible = new ArrayList<>();
        for (LocalWorldDiscovery.DiscoveredWorld world : this.discoveredWorlds) {
            String searchable = (world.displayName() + " " + world.folderName() + " " + world.instanceName() + " " + world.worldVersion() + " " + world.worldPath()).toLowerCase(Locale.ROOT);
            if (searchable.contains(query)) {
                visible.add(world);
            }
        }
        return visible;
    }

    private void updateDiscoveredWorldList() {
        if (this.discoveredWorldListWidget == null) {
            return;
        }
        this.discoveredWorldListWidget.updateEntries(filteredDiscoveredWorlds());
    }

    private void selectDiscoveredWorld(LocalWorldDiscovery.DiscoveredWorld world) {
        this.selectedDiscoveredWorld = world;
        this.previewScrollOffset = 0;
        this.previewScrollMax = 0;
        this.worldSelected(false, false);
    }

    private void renderDiscoveredWorldList(DrawContext context, int mouseX, int mouseY) {
        int listX = KOIL_WORLD_LIST_LEFT + 2;
        int listY = KOIL_WORLD_LIST_TOP + 4;
        int listWidth = KOIL_WORLD_LIST_WIDTH - 4;
        int listHeight = this.height - KOIL_WORLD_LIST_BOTTOM_PADDING - listY - 4;
        this.discoveredViewportX = listX;
        this.discoveredViewportY = listY;
        this.discoveredViewportWidth = listWidth;
        this.discoveredViewportHeight = listHeight;

        if (this.localWorldDiscoveryLoading) {
            context.drawText(this.textRenderer, "Scanning local instances...", listX + 8, listY + 8, 0xFFD8DFE9, false);
            return;
        }
        updateDiscoveredWorldList();
        if (filteredDiscoveredWorlds().isEmpty()) {
            context.drawText(this.textRenderer, "No compatible local worlds found", listX + 8, listY + 8, 0xFFD8DFE9, false);
            context.drawText(this.textRenderer, "Use Show All to include version-mismatched worlds", listX + 8, listY + 21, 0xFF9EACBA, false);
            return;
        }
        if (this.discoveredWorldListWidget != null) {
            this.discoveredWorldListWidget.render(context, mouseX, mouseY, 0.0F);
        }
    }

    private void renderCompatibilityFilterButton(DrawContext context) {
        if (!this.localWorldDiscoveryMode) {
            this.compatibilityFilterX = -1;
            this.compatibilityFilterWidth = 0;
            return;
        }
        String label = compatibilityFilterLabel();
        this.compatibilityFilterWidth = BrowserLayoutHelper.getFilterButtonWidth(this.textRenderer, label);
        this.compatibilityFilterX = this.searchBox == null ? this.width - 332 : this.searchBox.getX() - BrowserLayoutHelper.FILTER_BUTTON_GAP - this.compatibilityFilterWidth;
        BrowserLayoutHelper.renderFilterButton(context, this.textRenderer, this.compatibilityFilterX, label);
    }

    private String compatibilityFilterLabel() {
        return this.localWorldShowIncompatible ? "Version: Show All" : "Version: Compatible";
    }

    private Identifier resolveDiscoveredIcon(LocalWorldDiscovery.DiscoveredWorld world) {
        if (world == null || world.iconPath() == null || !Files.exists(world.iconPath())) {
            return KOIL_FALLBACK_WORLD_ICON;
        }
        try {
            Identifier icon = ExternalImageLoader.registerDynamicTexture("koil", "discovered_world_icon/" + Math.abs(world.worldPath().hashCode()), world.iconPath().toFile());
            return icon != null ? icon : KOIL_FALLBACK_WORLD_ICON;
        } catch (IOException ignored) {
            return KOIL_FALLBACK_WORLD_ICON;
        }
    }

    private void renderSelectedDiscoveredWorldInfo(DrawContext context) {
        int panelX = BrowserLayoutHelper.previewX(this.width);
        int panelY = BrowserLayoutHelper.previewY();
        int panelWidth = BrowserLayoutHelper.previewWidth(this.width);
        int panelHeight = BrowserLayoutHelper.previewHeight(this.height);
        BrowserLayoutHelper.renderPreviewPanelFrame(context, panelX, panelY, panelWidth, panelHeight);

        if (this.selectedDiscoveredWorld == null) {
            context.drawText(this.textRenderer, "Local World Discovery", panelX + 10, panelY + 10, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            context.drawText(this.textRenderer, "Scan known Minecraft instances for compatible saves.", panelX + 10, panelY + 26, 0xFFD8DFE9, false);
            return;
        }

        LocalWorldDiscovery.DiscoveredWorld world = this.selectedDiscoveredWorld;
        Identifier icon = resolveDiscoveredIcon(world);
        context.drawTexture(icon, panelX + 10, panelY + 10, 0, 0, 64, 64, 64, 64);
        int titleX = panelX + 84;
        context.getMatrices().push();
        context.getMatrices().scale(1.5F, 1.5F, 1.0F);
        context.drawText(this.textRenderer, trimPreview(world.displayName(), 170), (int) (titleX / 1.5F), (int) ((panelY + 10) / 1.5F), new Color(uiColorContentBaseTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, world.folderName(), titleX, panelY + 34, 0xFFD8DFE9, false);
        context.drawText(this.textRenderer, world.versionCompatible() ? "Compatible with this Minecraft version" : "Version mismatch - warning required", titleX, panelY + 48, world.versionCompatible() ? 0xFF8FEA84 : 0xFFFFD08A, false);

        int detailsViewportY = panelY + 86;
        int detailsViewportHeight = Math.max(70, panelHeight - (detailsViewportY - panelY) - 8);
        this.previewViewportX = panelX + 8;
        this.previewViewportY = detailsViewportY;
        this.previewViewportWidth = panelWidth - 16;
        this.previewViewportHeight = detailsViewportHeight;
        context.enableScissor(this.previewViewportX, this.previewViewportY, this.previewViewportX + this.previewViewportWidth, this.previewViewportY + this.previewViewportHeight);

        int infoY = detailsViewportY - this.previewScrollOffset;
        int contentHeight = 0;
        int nextY;
        BrowserLayoutHelper.renderSectionRule(context, this.textRenderer, panelX + 10, panelX + panelWidth - 10, infoY, "Identity");
        infoY += 16;
        contentHeight += 16;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Folder", world.folderName(), 0xFFDCE4EE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Path", world.worldPath().toAbsolutePath().normalize().toString().replace('\\', '/'), 0xFFD8E7DE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Last Played", formatTimestamp(world.lastPlayed()), 0xFFD9D5E8);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Icon", world.iconPath() == null ? "Default world icon" : world.iconPath().toAbsolutePath().normalize().toString().replace('\\', '/'), 0xFFD6E5DA);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Summary", world.versionCompatible() ? "Compatible with this Minecraft version" : "Version mismatch - warning required", 0xFFD8DFE9);
        contentHeight += nextY - infoY;
        infoY = nextY;

        BrowserLayoutHelper.renderSectionRule(context, this.textRenderer, panelX + 10, panelX + panelWidth - 10, infoY, "Gameplay");
        infoY += 16;
        contentHeight += 16;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Mode", world.gameMode(), 0xFFDCE4EE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Difficulty", "Unknown from external save scan", 0xFFD8E7DE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Commands", world.commandsAllowed() ? "Allowed" : "Off", 0xFFD9D5E8);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Flags", world.hardcore() ? "Hardcore" : "Standard world", 0xFFD6E5DA);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Features", "Read from external level.dat metadata", 0xFFD8DFE9);
        contentHeight += nextY - infoY;
        infoY = nextY;

        BrowserLayoutHelper.renderSectionRule(context, this.textRenderer, panelX + 10, panelX + panelWidth - 10, infoY, "Developer Details");
        infoY += 16;
        contentHeight += 16;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Version", world.worldVersion(), 0xFFDCE4EE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Save Info", "Data version " + world.dataVersion(), 0xFFD8E7DE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Storage", "Instance " + world.instanceName(), 0xFFD9D5E8);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Conversion", world.versionCompatible() ? "No version warning expected" : "Opening requires warning confirmation", 0xFFD6E5DA);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Lifecycle", "External save linked into current instance on open", 0xFFE4DAC8);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Data Pack", "Detected after world is opened by Minecraft", 0xFFD2E0CF);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Identity", world.instanceRoot().toAbsolutePath().normalize().toString().replace('\\', '/'), 0xFFD8DFE9);
        contentHeight += nextY - infoY;
        infoY = nextY;

        BrowserLayoutHelper.renderSectionRule(context, this.textRenderer, panelX + 10, panelX + panelWidth - 10, infoY, "Compatibility");
        infoY += 16;
        contentHeight += 16;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Version", world.worldVersion() + "  |  current " + world.currentVersion(), 0xFFDCE4EE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Data Version", String.valueOf(world.dataVersion()), 0xFFD8E7DE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Mode", world.gameMode() + (world.hardcore() ? " | Hardcore" : ""), 0xFFD9D5E8);
        contentHeight += nextY - infoY;
        infoY = nextY;

        BrowserLayoutHelper.renderSectionRule(context, this.textRenderer, panelX + 10, panelX + panelWidth - 10, infoY, "Mod Comparison");
        infoY += 16;
        contentHeight += 16;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Source Mods", world.sourceModCount() + " detected in source instance", 0xFFDCE4EE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Current Instance", world.modComparison().summary(), 0xFFD8E7DE);
        contentHeight += nextY - infoY;
        infoY = nextY;
        nextY = BrowserLayoutHelper.renderInfoLine(context, this.textRenderer, panelX + 12, infoY, panelWidth, "Open Method", "Creates a current-instance save link after warning confirmation", 0xFFE4DAC8);
        contentHeight += nextY - infoY;
        context.disableScissor();
        this.previewScrollMax = Math.max(0, contentHeight - detailsViewportHeight + 4);
        this.previewScrollOffset = Math.max(0, Math.min(this.previewScrollOffset, this.previewScrollMax));
        renderPreviewScrollbar(context);
    }

    private void openSelectedDiscoveredFolder() {
        if (this.selectedDiscoveredWorld == null) {
            return;
        }
        this.pendingFolderOpenDirectory = this.selectedDiscoveredWorld.worldPath().toFile();
        this.folderOpenPopup.toggleAtPointer(this.lastMouseX, this.lastMouseY, this.width, this.height, FolderOpenHelper.menuEntries());
    }

    private void openSelectedDiscoveredWorld() {
        if (this.selectedDiscoveredWorld == null || this.client == null) {
            return;
        }
        LocalWorldDiscovery.DiscoveredWorld world = this.selectedDiscoveredWorld;
        Text message = Text.literal("This world is outside the current instance. Opening it here creates a save link in this instance. Back up the world first, especially when the mod list or version differs. Source: " + world.worldPath().toAbsolutePath().normalize());
        this.client.setScreen(new ConfirmScreen(confirmed -> {
            if (!confirmed) {
                this.client.setScreen((Screen) (Object) this);
                return;
            }
            try {
                LocalWorldDiscovery.createCurrentInstanceLink(world);
                this.client.setScreen(new SelectWorldScreen(this.parent));
            } catch (Exception exception) {
                Util.getOperatingSystem().open(world.worldPath().toFile());
                this.client.setScreen((Screen) (Object) this);
            }
        }, Text.literal("Open external world?"), message, Text.literal("Open In Place"), ScreenTexts.CANCEL));
    }

    private Path getSelectedWorldFolder() {
        LevelSummary level = getSelectedLevel();
        if (level == null || this.client == null) {
            return null;
        }
        return this.client.getLevelStorage().getSavesDirectory().resolve(level.getName());
    }

    private void syncSelectedWorldFromList() {
        if (this.levelList == null) {
            this.selectedWorld = null;
            return;
        }
        Optional<WorldListWidget.WorldEntry> selectedWorldOptional = this.levelList.getSelectedAsOptional();
        this.selectedWorld = selectedWorldOptional.orElse(null);
    }

    private String formatTimestamp(long timestamp) {
        if (timestamp <= 0L) {
            return "Unknown";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(timestamp));
    }

    private String buildWorldFlags(LevelSummary level) {
        StringBuilder builder = new StringBuilder();
        if (level.isHardcore()) {
            builder.append("Hardcore");
        }
        if (level.isExperimental()) {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append("Experimental");
        }
        if (level.isLocked()) {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append("Locked");
        }
        if (level.requiresConversion()) {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append("Conversion");
        }
        return builder.isEmpty() ? "None" : builder.toString();
    }

    private String describeWorldPath() {
        Path path = getSelectedWorldFolder();
        return path == null ? "Unknown" : path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private String describeWorldIcon(LevelSummary level) {
        if (level == null || level.getIconPath() == null) {
            return "Default icon";
        }
        return level.getIconPath().toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private String buildWorldStorageFlags(LevelSummary level, SaveVersionInfo versionInfo) {
        List<String> flags = new ArrayList<>();
        if (versionInfo != null) {
            flags.add(versionInfo.isStable() ? "Stable" : "Snapshot");
        }
        if (level.isDifferentVersion()) {
            flags.add("Different Version");
        }
        if (level.isFutureLevel()) {
            flags.add("Future Level");
        }
        if (level.isUnavailable()) {
            flags.add("Unavailable");
        }
        if (!level.isVersionAvailable()) {
            flags.add("Version Missing");
        }
        return flags.isEmpty() ? "None" : String.join(" | ", flags);
    }

    private String buildLifecycleFlags(LevelSummary level, LevelInfo levelInfo, SaveVersionInfo versionInfo) {
        List<String> flags = new ArrayList<>();
        if (levelInfo != null) {
            flags.add(levelInfo.isHardcore() ? "Hardcore Profile" : "Standard Profile");
        }
        if (versionInfo != null) {
            flags.add(versionInfo.isStable() ? "Stable Save" : "Snapshot Save");
        }
        if (levelInfo != null && levelInfo.areCommandsAllowed()) {
            flags.add("Cheats Enabled");
        }
        if (level.isExperimental()) {
            flags.add("Experimental Features");
        }
        if (level.isLocked()) {
            flags.add("Session Locked");
        }
        return flags.isEmpty() ? "None" : String.join(" | ", flags);
    }

    private String describeDataConfiguration(LevelInfo levelInfo) {
        if (levelInfo == null || levelInfo.getDataConfiguration() == null) {
            return "Unknown";
        }
        return levelInfo.getDataConfiguration().toString();
    }

    private String buildIdentityFlags(LevelSummary level) {
        List<String> flags = new ArrayList<>();
        flags.add(level.getDisplayName().isBlank() ? "Folder Name Title" : "Custom Display Name");
        if (level.getIconPath() != null) {
            flags.add("Custom Icon");
        } else {
            flags.add("Default Icon");
        }
        if (level.requiresConversion()) {
            flags.add("Needs Conversion");
        }
        return String.join(" | ", flags);
    }

    private String formatConversionWarning(LevelSummary level) {
        if (level == null) {
            return "None";
        }
        LevelSummary.ConversionWarning warning = level.getConversionWarning();
        if (warning == null || warning == LevelSummary.ConversionWarning.NONE) {
            return "None";
        }
        return warning.name() + (warning.promptsBackup() ? "  |  backup suggested" : "");
    }

    private String trimPreview(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        String trimmed = this.textRenderer.trimToWidth(text, maxWidth);
        if (trimmed.length() == text.length()) {
            return trimmed;
        }
        return this.textRenderer.trimToWidth(text, Math.max(8, maxWidth - this.textRenderer.getWidth("..."))) + "...";
    }

    private void selectFirstWorldEntry() {
        if (this.levelList == null || this.levelList.getSelectedAsOptional().isPresent()) {
            return;
        }
        for (WorldListWidget.Entry entry : this.levelList.children()) {
            if (entry instanceof WorldListWidget.WorldEntry worldEntry && worldEntry.isAvailable()) {
                this.levelList.setSelected(worldEntry);
                this.selectedWorld = worldEntry;
                this.worldSelected(true, true);
                return;
            }
        }
    }

    private void syncPreviewSelection() {
        LevelSummary level = getSelectedLevel();
        String selectionKey = level == null ? null : level.getName() + "|" + level.getLastPlayed();
        if (selectionKey != null && !selectionKey.equals(this.previewSelectionKey)) {
            this.previewSelectionKey = selectionKey;
            this.previewScrollOffset = 0;
            this.previewScrollMax = 0;
        }
    }

    private void renderPreviewScrollbar(DrawContext context) {
        if (this.previewScrollMax <= 0 || this.previewViewportHeight <= 0) {
            return;
        }
        int trackX = this.previewViewportX + this.previewViewportWidth - 4;
        context.fill(trackX, this.previewViewportY, trackX + 2, this.previewViewportY + this.previewViewportHeight, 0x2E67727F);
        int thumbHeight = Math.max(18, (int) ((this.previewViewportHeight / (float) (this.previewViewportHeight + this.previewScrollMax)) * this.previewViewportHeight));
        int thumbTravel = Math.max(1, this.previewViewportHeight - thumbHeight);
        int thumbY = this.previewViewportY + (int) ((this.previewScrollOffset / (float) this.previewScrollMax) * thumbTravel);
        context.fill(trackX - 1, thumbY, trackX + 3, thumbY + thumbHeight, 0x9AA9B9CA);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (this.localWorldDiscoveryMode && this.discoveredWorldListWidget != null && this.discoveredWorldListWidget.isMouseOver(mouseX, mouseY)) {
            this.discoveredWorldListWidget.setScrollAmount(Math.max(0.0D, this.discoveredWorldListWidget.getScrollAmount() - (int) amount * 20.0D));
            return true;
        }
        if (mouseX >= this.previewViewportX && mouseX <= this.previewViewportX + this.previewViewportWidth
                && mouseY >= this.previewViewportY && mouseY <= this.previewViewportY + this.previewViewportHeight
                && this.previewScrollMax > 0) {
            this.previewScrollOffset = Math.max(0, Math.min(this.previewScrollMax, this.previewScrollOffset - (int) amount * 12));
            return true;
        }
        if (this.levelList != null && this.levelList.isMouseOver(mouseX, mouseY)) {
            this.levelList.setScrollAmount(Math.max(0.0D, this.levelList.getScrollAmount() - (int) amount * 20.0D));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.folderOpenPopup.isOpen()) {
            PopupMenu.MenuEntry selected = this.folderOpenPopup.click(mouseX, mouseY);
            if (selected != null) {
                FolderOpenHelper.handleAction(this.client, this.pendingFolderOpenDirectory, selected.id());
                return true;
            }
            if (!this.folderOpenPopup.isOpen()) {
                return true;
            }
        }
        if (this.folderOpenPopup.isOpen() && this.folderOpenPopup.contains(mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && this.localWorldDiscoveryMode && isOverCompatibilityFilter(mouseX, mouseY)) {
            toggleLocalWorldFilter();
            return true;
        }
        if (this.localWorldDiscoveryMode && this.discoveredWorldListWidget != null && this.discoveredWorldListWidget.isMouseOver(mouseX, mouseY)
                && this.discoveredWorldListWidget.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0 && this.previewScrollMax > 0 && isOverPreviewScrollbar(mouseX, mouseY)) {
            int thumbY = previewScrollbarThumbY();
            int thumbHeight = previewScrollbarThumbHeight();
            this.previewScrollbarDragging = true;
            this.previewScrollbarDragOffset = mouseY >= thumbY && mouseY <= thumbY + thumbHeight ? (int) mouseY - thumbY : thumbHeight / 2;
            setPreviewScrollFromThumbTop((int) mouseY - this.previewScrollbarDragOffset);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (this.localWorldDiscoveryMode && this.discoveredWorldListWidget != null
                && this.discoveredWorldListWidget.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) {
            return true;
        }
        if (button == 0 && this.previewScrollbarDragging) {
            setPreviewScrollFromThumbTop((int) mouseY - this.previewScrollbarDragOffset);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.localWorldDiscoveryMode && this.discoveredWorldListWidget != null
                && this.discoveredWorldListWidget.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0 && this.previewScrollbarDragging) {
            this.previewScrollbarDragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean isOverPreviewScrollbar(double mouseX, double mouseY) {
        int trackX = this.previewViewportX + this.previewViewportWidth - 4;
        return this.previewViewportX >= 0
                && mouseX >= trackX - 4
                && mouseX <= trackX + 8
                && mouseY >= this.previewViewportY
                && mouseY <= this.previewViewportY + this.previewViewportHeight;
    }

    private boolean isOverCompatibilityFilter(double mouseX, double mouseY) {
        return this.compatibilityFilterX >= 0
                && mouseX >= this.compatibilityFilterX
                && mouseX <= this.compatibilityFilterX + this.compatibilityFilterWidth
                && mouseY >= BrowserLayoutHelper.FILTER_BUTTON_Y
                && mouseY <= BrowserLayoutHelper.FILTER_BUTTON_Y + BrowserLayoutHelper.FILTER_BUTTON_HEIGHT;
    }

    private int previewScrollbarThumbHeight() {
        return Math.max(18, (int) ((this.previewViewportHeight / (float) (this.previewViewportHeight + this.previewScrollMax)) * this.previewViewportHeight));
    }

    private int previewScrollbarThumbY() {
        int thumbTravel = Math.max(1, this.previewViewportHeight - previewScrollbarThumbHeight());
        return this.previewViewportY + (int) ((this.previewScrollOffset / (float) this.previewScrollMax) * thumbTravel);
    }

    private void setPreviewScrollFromThumbTop(int thumbTop) {
        int thumbHeight = previewScrollbarThumbHeight();
        int minTop = this.previewViewportY;
        int maxTop = this.previewViewportY + this.previewViewportHeight - thumbHeight;
        int clampedTop = Math.max(minTop, Math.min(maxTop, thumbTop));
        int travel = Math.max(1, maxTop - minTop);
        float ratio = (clampedTop - minTop) / (float) travel;
        this.previewScrollOffset = Math.max(0, Math.min(this.previewScrollMax, Math.round(ratio * this.previewScrollMax)));
    }

}
