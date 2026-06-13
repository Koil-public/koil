package com.spirit.client.gui.mod;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.client.gui.*;
import com.spirit.client.gui.PopupMenu;
import com.spirit.client.gui.mod.modconfig.DiscoveredModConfigSet;
import com.spirit.client.gui.mod.modconfig.ModConfigDiscoveryService;
import com.spirit.koil.api.util.file.jar.KoilLocalModJarInspector;
import com.spirit.koil.api.util.file.jar.KoilLocalModJarInspector.KoilLocalModJarInsight;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.spirit.Main.SUBLOGGER;
import static com.spirit.koil.api.design.uiColorVal.*;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadImage;

@Environment(EnvType.CLIENT)
public class ModMenuScreen extends Screen {
    private static final int LIST_OUTER_LEFT = 31;
    private static final int LIST_INNER_LEFT = 37;
    private static final int LIST_OUTER_RIGHT = 351;
    private static final int LIST_INNER_RIGHT = 345;
    private enum InstalledSortMode {
        NAME("Name"),
        MOD_ID("Mod ID"),
        AUTHOR("Author"),
        STATUS("Status"),
        PICTURE("Picture");

        private final String label;

        InstalledSortMode(String label) {
            this.label = label;
        }
    }

    private enum InstalledFilterMode {
        ALL("All"),
        ENABLED("Enabled"),
        DISABLED("Disabled"),
        CONFIGS("Has Config");

        private final String label;

        InstalledFilterMode(String label) {
            this.label = label;
        }
    }

    private enum InstalledGroupMode {
        NONE("None"),
        AUTHOR("Author"),
        STATUS("Status"),
        FAMILY("Family");

        private final String label;

        InstalledGroupMode(String label) {
            this.label = label;
        }
    }

    private enum PreviewSourceMode {
        AUTO("Auto"),
        MODRINTH("Modrinth"),
        CURSEFORGE("CurseForge"),
        LOCAL("Local");

        private final String label;

        PreviewSourceMode(String label) {
            this.label = label;
        }
    }

    private final List<ModContainer> mods;
    private ModListWidget modListWidget;
    private ModContainer selectedMod;
    private NativeImageBackedTexture selectedModIcon;
    private Identifier selectedModIconIdentifier;
    private TextFieldWidget searchField;
    private NativeImageBackedTexture selectedModBanner;
    private Identifier selectedModBannerIdentifier;
    private ButtonWidget downloadButton;
    private ButtonWidget disableButton;
    private ButtonWidget deleteButton;
    private ButtonWidget configButton;
    private ButtonWidget websiteButton;
    private ButtonWidget openFolderButton;
    private final Map<String, DiscoveredModConfigSet> configDiscoveryCache = new HashMap<>();
    private final Map<String, ModrinthPreviewData> modrinthPreviewCache = new ConcurrentHashMap<>();
    private final Set<String> modrinthPreviewLoading = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> curseForgeAvailabilityCache = new ConcurrentHashMap<>();
    private final Set<String> curseForgeAvailabilityLoading = ConcurrentHashMap.newKeySet();
    private final Set<String> expandedInstalledGroups = new HashSet<>(Set.of("Mods"));
    private final PopupMenu installedFilterPopup = new PopupMenu();
    private final PopupMenu previewSourcePopup = new PopupMenu();
    private final PopupMenu modActionPopup = new PopupMenu();
    private final PopupMenu folderOpenPopup = new PopupMenu();
    private InstalledSortMode installedSortMode = InstalledSortMode.NAME;
    private InstalledFilterMode installedFilterMode = InstalledFilterMode.ALL;
    private InstalledGroupMode installedGroupMode = InstalledGroupMode.FAMILY;
    private PreviewSourceMode previewSourceMode = PreviewSourceMode.AUTO;
    private boolean showIconlessMods;
    private int previewScrollOffset;
    private int previewScrollMax;
    private int previewViewportX;
    private int previewViewportY;
    private int previewViewportWidth;
    private int previewViewportHeight;
    private boolean previewScrollbarDragging;
    private int previewScrollbarDragOffset;
    private int previewSourceChipX;
    private int previewSourceChipY;
    private int previewSourceChipWidth;
    private int previewVersionX;
    private int previewVersionY;
    private int previewVersionWidth;
    private int previewVersionSplitX = -1;
    private int previewUpdateChipX = -1;
    private int previewUpdateChipY = -1;
    private int previewUpdateChipWidth = 0;
    private final List<PreviewTooltipRegion> previewTooltipRegions = new ArrayList<>();
    private ModContainer popupActionMod;
    private File pendingFolderOpenDirectory;
    private int lastMouseX;
    private int lastMouseY;

    private Screen parent;
    private static final int INSTALLED_FILTER_Y = 10;
    private static final int INSTALLED_FILTER_HEIGHT = 20;
    private static final int INSTALLED_FILTER_GAP = 6;
    private static final int PREVIEW_INFO_LABEL_WIDTH = 96;
    private static final int PREVIEW_INFO_ROW_PADDING = 4;

    private record ModrinthPreviewData(
            String provider,
            String projectTitle,
            String projectSlug,
            String projectDescription,
            String projectType,
            String licenseName,
            String sourceUrl,
            String issuesUrl,
            String wikiUrl,
            String discordUrl,
            String publishedAt,
            String updatedAt,
            String authorName,
            String versionTitle,
            String versionNumber,
            String versionSummary,
            String body,
            String downloads,
            String followers,
            String clientSide,
            String serverSide,
            String loaderRequirement,
            boolean exactGameVersion,
            boolean exactLoaderMatch,
            int versionCount,
            List<String> loaders,
            List<String> gameVersions,
            List<String> categories,
            boolean exactVersion,
            boolean approximateProject,
            boolean updateAvailable,
            boolean found
    ) {
    }

    private record PreviewTooltipRegion(int x, int y, int width, int height, List<Text> lines) {
    }

    public ModMenuScreen(Text title) {
        super(title);
        this.mods = FabricLoader.getInstance().getAllMods().stream().toList();
    }

    public ModMenuScreen(Screen parent) {
        this(Text.literal("Mods"));
        this.parent = parent;
    }


    public void modSelected(boolean buttonsActive, boolean deleteButtonActive) {
        this.disableButton.active = buttonsActive;
        this.configButton.active = buttonsActive;
        this.deleteButton.active = deleteButtonActive;
        if (this.websiteButton != null) {
            this.websiteButton.active = buttonsActive;
        }
        if (this.openFolderButton != null) {
            this.openFolderButton.active = true;
        }
    }

    @Override
    protected void init() {
        int x = 37;
        int rightActionX = BrowserLayoutHelper.FOOTER_RIGHT_ACTION_X;
        int topButtonWidth = BrowserLayoutHelper.FOOTER_TOP_BUTTON_WIDTH;

        int searchWidth = 200;
        int searchX = width - 210;
        int sortWidth = getInstalledFilterWidth(getSortButtonLabel());
        int groupWidth = getInstalledFilterWidth(getGroupButtonLabel());
        int showWidth = getInstalledFilterWidth(getShowButtonLabel());
        int filterX = searchX - INSTALLED_FILTER_GAP - showWidth - INSTALLED_FILTER_GAP - groupWidth - INSTALLED_FILTER_GAP - sortWidth;

        searchField = new TextFieldWidget(client.textRenderer, searchX, 10, searchWidth, 20, Text.literal("Search Mods"));
        searchField.setChangedListener(this::onSearchChanged);
        searchField.setPlaceholder(Text.literal("Search installed mods"));
        this.addSelectableChild(searchField);

        this.downloadButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Download Mods"), (button) -> {
            assert this.client != null;
            Objects.requireNonNull(this.client).setScreen(new ModScreen(this));
        }).dimensions(x, this.height - 52, 150, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.of("Import Mod"), (button) -> {
            openImportModChooser();
        }).dimensions(x + 158, this.height - 52, 150, 20).build());

        this.websiteButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Website"), button -> openSelectedModWebsite())
                .dimensions(rightActionX, this.height - 52, topButtonWidth, 20).build());

        this.disableButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Disable"), (button) -> {
            if (selectedMod != null) {
                disableModFile(selectedMod, true);
            }
        }).dimensions(x, this.height - 28, 72, 20).build());

        this.deleteButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Delete"), (button) -> {
                    if (selectedMod != null) {
                        deleteModFile(selectedMod);
                    }
                }).dimensions(x + 78, this.height - 28, 72, 20)
                .tooltip(Tooltip.of(Text.literal("[!] This cannot be undone!").formatted(Formatting.RED))).build());

        this.configButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Config"), (button) -> {
            if (selectedMod != null) {
                openConfigMenu(selectedMod);
            }
        }).tooltip(Tooltip.of(Text.literal("[!] This screen is still in development. Formatting may be rendered sloppy."))).dimensions(x + 158, this.height - 28, 72, 20).build());

        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, (button) -> {
            assert this.client != null;
            this.client.setScreen(this.parent);
        }).dimensions(x + 158 + 78, this.height - 28, 72, 20).build());

        this.openFolderButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Open Folder"), button -> openFolderPopupAtPointer(FabricLoader.getInstance().getGameDir().resolve("mods").toFile()))
                .dimensions(rightActionX, this.height - 28, topButtonWidth, 20).build());

        this.modSelected(false, false);
        this.modListWidget = new ModListWidget(client, LIST_INNER_RIGHT - LIST_INNER_LEFT, this.height, 39, this.height - 57, 36);
        this.modListWidget.setLeftPos(LIST_INNER_LEFT);
        rebuildInstalledModList();
        this.addSelectableChild(modListWidget);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    private void disableModFile(ModContainer mod, boolean disable) {
        File modFile = getPhysicalModFile(mod);
        if (modFile != null && modFile.exists()) {
            String fileName = modFile.getName();
            File renamedFile;

            if (disable) {
                if (!fileName.endsWith(".disabled")) {
                    renamedFile = new File(modFile.getParent(), fileName + ".disabled");
                    modFile.renameTo(renamedFile);
                }
            } else {
                if (fileName.endsWith(".disabled")) {
                    renamedFile = new File(modFile.getParent(), fileName.replace(".disabled", ""));
                    modFile.renameTo(renamedFile);
                }
            }
        }
    }

    private void openConfigMenu(ModContainer mod) {
        DiscoveredModConfigSet discoveredConfigs = getDiscoveredConfigs(mod);
        if (discoveredConfigs.hasFiles()) {
            File[] configFiles = discoveredConfigs.files().stream().map(entry -> entry.file()).toArray(File[]::new);
            this.client.setScreen(new ModConfigScreen(this, mod, configFiles));
        } else {
            SUBLOGGER.logW("File-Management", "No config files found for " + mod.getMetadata().getName(), true, "No config files found for " + mod.getMetadata().getName());
        }
    }

    private void openSelectedModWebsite() {
        if (selectedMod == null) {
            return;
        }
        ModrinthPreviewData data = getOrFetchModrinthPreview(selectedMod);
        String url = data != null && data.sourceUrl() != null && !data.sourceUrl().isBlank()
                ? data.sourceUrl()
                : "https://modrinth.com/mod/" + selectedMod.getMetadata().getId();
        try {
            Util.getOperatingSystem().open(new URI(url));
        } catch (Exception ignored) {
        }
    }

    private void deleteModFile(ModContainer mod) {
        File modFile = getPhysicalModFile(mod);
        if (modFile != null && modFile.exists()) {
            this.client.setScreen(new ConfirmScreen((confirmed) -> {
                if (confirmed) {
                    boolean deleted = modFile.delete();
                    if (deleted) {
                        SUBLOGGER.logI("File-Management Thread", "Mod: " + Text.of(modFile.getName() + " has been deleted"), true, "Mod: " + Text.of(modFile.getName() + " has been deleted"));
                    } else {
                        SUBLOGGER.logE("File-Management Thread", Text.of("Failed to delete " + modFile.getName()).toString(), true, Text.of("Failed to delete " + modFile.getName()).toString());
                    }
                    this.client.setScreen(this);
                } else {
                    this.client.setScreen(this);
                }
            }, Text.of("Confirm Delete"), Text.of("Are you sure you want to delete " + modFile.getName() + "?")));
        }
    }


    private void onSearchChanged(String query) {
        rebuildInstalledModList();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        MinecraftClient client = MinecraftClient.getInstance();
        BrowserLayoutHelper.renderContentBackground(context, client, this.width, this.height);


        context.fill(0, 0, width, 43, new Color(uiColorHeader, true).getRGB());
        context.fill(0, 39, width, 42, new Color(uiColorHeaderStripe, true).getRGB());
        context.fill(0, height - 60, width, height, new Color(uiColorFooter, true).getRGB());
        context.fill(0, this.height - 60, width, this.height - 57, new Color(uiColorFooterStripe, true).getRGB());
        int listTop = BrowserLayoutHelper.TOP_BAR_HEIGHT;
        int listBottom = this.height - BrowserLayoutHelper.FOOTER_HEIGHT;
        context.fill(LIST_OUTER_LEFT, listTop, LIST_OUTER_RIGHT, listBottom, new Color(uiColorContentBase, true).getRGB());
        context.fill(LIST_INNER_LEFT, listTop, LIST_INNER_LEFT + 2, listBottom, new Color(uiColorContentStripeLeft, true).getRGB());
        context.fill(LIST_INNER_RIGHT - 2, listTop, LIST_INNER_RIGHT, listBottom, new Color(uiColorContentStripeRight, true).getRGB());
        context.getMatrices().push();
        context.getMatrices().scale(1.5F, 1.5F, 1.0F);
        context.drawText(this.textRenderer, "Mods", 25, 6, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "-=-", 37, 23, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);

        int searchX = width - 210;
        int filterX = searchX - INSTALLED_FILTER_GAP - getInstalledFilterWidth(getShowButtonLabel()) - INSTALLED_FILTER_GAP - getInstalledFilterWidth(getGroupButtonLabel()) - INSTALLED_FILTER_GAP - getInstalledFilterWidth(getSortButtonLabel());
        this.modListWidget.render(context, mouseX, mouseY, delta);

        if (selectedMod != null) {
            MarkdownPreviewRenderer.beginInteractiveFrame();
            renderModDetails(context, selectedMod, LIST_OUTER_RIGHT + 7, 43);
        }
        super.render(context, mouseX, mouseY, delta);
        renderInstalledFilterButton(context, filterX, getSortButtonLabel());
        int groupX = filterX + getInstalledFilterWidth(getSortButtonLabel()) + INSTALLED_FILTER_GAP;
        renderInstalledFilterButton(context, groupX, getGroupButtonLabel());
        renderInstalledFilterButton(context, groupX + getInstalledFilterWidth(getGroupButtonLabel()) + INSTALLED_FILTER_GAP, getShowButtonLabel());
        searchField.render(context, mouseX, mouseY, delta);
        installedFilterPopup.render(context, mouseX, mouseY);
        previewSourcePopup.render(context, mouseX, mouseY);
        modActionPopup.render(context, mouseX, mouseY);
        folderOpenPopup.render(context, mouseX, mouseY);
        if (selectedMod != null) {
            renderPreviewHoverTooltip(context, mouseX, mouseY);
        }
        ((ScreenChromeHost) this).koil$renderScreenChromeLate(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (((ScreenChromeHost) this).koil$consumeScreenChromeClick(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0 && this.installedFilterPopup.isOpen()) {
            PopupMenu.MenuEntry selected = this.installedFilterPopup.click(mouseX, mouseY);
            if (selected != null) {
                UiSoundHelper.playButtonClick();
                applyInstalledFilterAction(selected.id());
                return true;
            }
            if (!this.installedFilterPopup.isOpen()) {
                return true;
            }
        }
        if (button == 0 && this.previewSourcePopup.isOpen()) {
            PopupMenu.MenuEntry selected = this.previewSourcePopup.click(mouseX, mouseY);
            if (selected != null) {
                UiSoundHelper.playButtonClick();
                applyPreviewSourceAction(selected.id());
                return true;
            }
            if (!this.previewSourcePopup.isOpen()) {
                return true;
            }
        }
        if (button == 0 && this.modActionPopup.isOpen()) {
            PopupMenu.MenuEntry selected = this.modActionPopup.click(mouseX, mouseY);
            if (selected != null) {
                UiSoundHelper.playButtonClick();
                applyModAction(selected.id());
                return true;
            }
            if (!this.modActionPopup.isOpen()) {
                return true;
            }
        }
        if (button == 0 && this.folderOpenPopup.isOpen()) {
            PopupMenu.MenuEntry selected = this.folderOpenPopup.click(mouseX, mouseY);
            if (selected != null) {
                FolderOpenHelper.handleAction(this.client, this.pendingFolderOpenDirectory, selected.id());
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (!this.folderOpenPopup.isOpen()) {
                return true;
            }
        }

        if (button == 0) {
            if (MarkdownPreviewRenderer.handleLinkClick(mouseX, mouseY)) {
                UiSoundHelper.playButtonClick();
                return true;
            }
            int searchX = width - 210;
            int sortX = searchX - INSTALLED_FILTER_GAP - getInstalledFilterWidth(getShowButtonLabel()) - INSTALLED_FILTER_GAP - getInstalledFilterWidth(getGroupButtonLabel()) - INSTALLED_FILTER_GAP - getInstalledFilterWidth(getSortButtonLabel());
            int sortWidth = getInstalledFilterWidth(getSortButtonLabel());
            if (isWithinInstalledFilter(mouseX, mouseY, sortX, INSTALLED_FILTER_Y, sortWidth, INSTALLED_FILTER_HEIGHT)) {
                UiSoundHelper.playButtonClick();
                openInstalledFilterMenu(mouseX, mouseY, buildSortEntries());
                return true;
            }
            int groupX = sortX + sortWidth + INSTALLED_FILTER_GAP;
            int groupWidth = getInstalledFilterWidth(getGroupButtonLabel());
            if (isWithinInstalledFilter(mouseX, mouseY, groupX, INSTALLED_FILTER_Y, groupWidth, INSTALLED_FILTER_HEIGHT)) {
                UiSoundHelper.playButtonClick();
                openInstalledFilterMenu(mouseX, mouseY, buildGroupEntries());
                return true;
            }
            int showX = groupX + groupWidth + INSTALLED_FILTER_GAP;
            int showWidth = getInstalledFilterWidth(getShowButtonLabel());
            if (isWithinInstalledFilter(mouseX, mouseY, showX, INSTALLED_FILTER_Y, showWidth, INSTALLED_FILTER_HEIGHT)) {
                UiSoundHelper.playButtonClick();
                openInstalledFilterMenu(mouseX, mouseY, buildShowEntries());
                return true;
            }
            if (isWithinInstalledFilter(mouseX, mouseY, this.previewSourceChipX, this.previewSourceChipY, this.previewSourceChipWidth, 10)) {
                UiSoundHelper.playButtonClick();
                this.previewSourcePopup.openAtPointer(mouseX, mouseY, this.width, this.height, buildPreviewSourceEntries());
                return true;
            }
            if (this.previewScrollMax > 0 && isOverPreviewScrollbar(mouseX, mouseY)) {
                int thumbY = previewScrollbarThumbY();
                int thumbHeight = previewScrollbarThumbHeight();
                this.previewScrollbarDragging = true;
                this.previewScrollbarDragOffset = mouseY >= thumbY && mouseY <= thumbY + thumbHeight ? (int) mouseY - thumbY : thumbHeight / 2;
                setPreviewScrollFromThumbTop((int) mouseY - this.previewScrollbarDragOffset);
                return true;
            }
            if (this.previewUpdateChipWidth > 0 && isWithinInstalledFilter(mouseX, mouseY, this.previewUpdateChipX, this.previewUpdateChipY, this.previewUpdateChipWidth, 11)) {
                openSelectedModInDownloader();
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && this.previewScrollbarDragging) {
            setPreviewScrollFromThumbTop((int) mouseY - this.previewScrollbarDragOffset);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.previewScrollbarDragging) {
            this.previewScrollbarDragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void renderModDetails(DrawContext context, ModContainer mod, int x, int y) {
        this.previewTooltipRegions.clear();
        String modName = mod.getMetadata().getName();
        String modID = mod.getMetadata().getId();
        String version = mod.getMetadata().getVersion().getFriendlyString();
        String description = mod.getMetadata().getDescription();
        String authors = String.join(", ", mod.getMetadata().getAuthors().stream().map(Person::getName).toList());
        boolean disabled = isDisabledMod(mod);
        boolean hasConfig = getDiscoveredConfigs(mod).hasFiles();
        File physicalFile = getPhysicalModFile(mod);
        KoilLocalModJarInsight localJarInsight = inspectLocalJar(mod);
        String fileName = physicalFile == null ? "Unknown" : physicalFile.getName();
        String filePath = physicalFile == null ? "Unavailable" : physicalFile.getPath().replace("\\", "/");
        ModrinthPreviewData remoteData = getOrFetchModrinthPreview(mod);
        String providerName = remoteData != null && remoteData.found() ? remoteData.provider() : this.previewSourceMode == PreviewSourceMode.LOCAL ? "Local" : "Auto";

        int panelWidth = Math.max(220, this.width - x - 8);
        int panelHeight = Math.max(120, (this.height - 60) - y);
        int bannerHeight = 82;
        this.previewSourceChipWidth = Math.max(56, this.textRenderer.getWidth("Source " + providerName) + 12);
        this.previewSourceChipX = x + panelWidth - this.previewSourceChipWidth - 10;
        this.previewSourceChipY = y + 5;
        context.fill(x, y, x + panelWidth, y + panelHeight, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(x, y, panelWidth, panelHeight, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(x, y, x + 2, y + panelHeight, new Color(uiColorContentStripeLeft, true).getRGB());
        context.fill(x + panelWidth - 2, y, x + panelWidth, y + panelHeight, new Color(uiColorContentStripeRight, true).getRGB());
        renderPreviewSourceChip(context);

        if (selectedModBanner != null) {
            client.getTextureManager().bindTexture(selectedModBannerIdentifier);
            context.drawTexture(selectedModBannerIdentifier, x + 1, y + 1, 0, 0, panelWidth - 2, bannerHeight, 300, 100);
        }

        int overlayY = y + 10;

        if (selectedModIcon != null) {
            client.getTextureManager().bindTexture(selectedModIconIdentifier);
            context.drawTexture(selectedModIconIdentifier, x + 10, overlayY, 0, 0, 64, 64, 64, 64);
        }

        context.getMatrices().push();
        context.getMatrices().scale(1.5F, 1.5F, 1.0F);
        context.drawText(this.textRenderer, modName, (int) (x / 1.5) + 54, (int) (overlayY / 1.5F), new Color(uiColorContentBaseTitleText, true).getRGB(), true);
        context.getMatrices().pop();

        int chipX = x + 80;
        this.previewUpdateChipX = -1;
        this.previewUpdateChipY = -1;
        this.previewUpdateChipWidth = 0;
        int detailChipY = overlayY + 15;
        chipX = renderDetailChip(context, chipX, detailChipY, disabled ? "Disabled" : "Enabled", disabled ? 0x8A5A3B3B : 0x8A314E39,
                List.of(
                        Text.literal("State").styled(style -> style.withColor(0xFF96A9BC)),
                        Text.literal(disabled ? "Disabled" : "Enabled").styled(style -> style.withColor(disabled ? 0xFFD48E8E : 0xFF8ED4A8)),
                        Text.literal(disabled ? "This local mod file will not load until it is re-enabled." : "This local mod file is active and available to load.").styled(style -> style.withColor(0xFFE6EDF5))
                ));
        if (hasConfig) {
            chipX = renderDetailChip(context, chipX + 4, detailChipY, "Config", 0x8A33465C,
                    List.of(
                            Text.literal("Config").styled(style -> style.withColor(0xFF96A9BC)),
                            Text.literal("Config files detected").styled(style -> style.withColor(0xFF8FC5FF)),
                            Text.literal("Found a config file or config entry point for this mod.").styled(style -> style.withColor(0xFFE6EDF5))
                    ));
        }
        if (remoteData != null && remoteData.found()) {
            chipX = renderDetailChip(context, chipX + 4, detailChipY, remoteData.exactVersion() ? "Exact Match" : "Closest Match", remoteData.exactVersion() ? 0x8A33465C : 0x8A5A4B33,
                    List.of(
                            Text.literal(remoteData.exactVersion() ? "Exact Match" : "Closest Match").styled(style -> style.withColor(remoteData.exactVersion() ? 0xFF8ED4A8 : 0xFFD9C48F)),
                            Text.literal(blankFallback(remoteData.provider(), "Remote")).styled(style -> style.withColor("Modrinth".equalsIgnoreCase(remoteData.provider()) ? 0xFF1BD96A : 0xFF8FC5FF)),
                            Text.literal(remoteData.exactVersion()
                                    ? "Remote version data matches this installed version directly."
                                    : "Found the nearest matching remote version data for this installed mod.").styled(style -> style.withColor(0xFFE6EDF5))
                    ));
            if (remoteData.approximateProject()) {
                chipX = renderDetailChip(context, chipX + 4, detailChipY, "Closest Project", 0x8A5A4B33,
                        List.of(
                                Text.literal("Closest Project").styled(style -> style.withColor(0xFFD9C48F)),
                                Text.literal(blankFallback(remoteData.projectSlug(), "unknown project")).styled(style -> style.withColor(0xFF8FC5FF)),
                                Text.literal("The remote project title or id was matched as closely as possible instead of exactly.").styled(style -> style.withColor(0xFFE6EDF5))
                        ));
            }
            if (remoteData.updateAvailable()) {
                this.previewUpdateChipX = chipX + 4;
                this.previewUpdateChipY = detailChipY;
                this.previewUpdateChipWidth = this.textRenderer.getWidth("Update Available") + 10;
                chipX = renderDetailChip(context, chipX + 4, detailChipY, "Update Available", 0x8A38543F,
                        List.of(
                                Text.literal("Update Available").styled(style -> style.withColor(0xFF9BDEAE)),
                                Text.literal("A compatible remote version appears newer than the installed one.").styled(style -> style.withColor(0xFFE6EDF5)),
                                Text.literal("Click to open downloader search.").styled(style -> style.withColor(0xFF8FC5FF))
                        ));
            }
            if (!remoteData.exactGameVersion()) {
                chipX = renderDetailChip(context, chipX + 4, detailChipY, "Closest Game Version", 0x8A5A4B33,
                        List.of(
                                Text.literal("Closest Game Version").styled(style -> style.withColor(0xFFD9C48F)),
                                Text.literal("Remote metadata was matched to the nearest supported Minecraft version for this instance.").styled(style -> style.withColor(0xFFE6EDF5))
                        ));
            }
            if (!remoteData.exactLoaderMatch()) {
                chipX = renderDetailChip(context, chipX + 4, detailChipY, "Closest Loader", 0x8A5A4B33,
                        List.of(
                                Text.literal("Closest Loader").styled(style -> style.withColor(0xFFD9C48F)),
                                Text.literal("Remote metadata was matched to the nearest compatible loader requirement.").styled(style -> style.withColor(0xFFE6EDF5))
                        ));
            }
        }

        context.drawText(this.textRenderer, "Mod ID", x + 80, overlayY + 33, uiColorBasicSubtitleText, false);
        context.drawText(this.textRenderer, modID, x + 122, overlayY + 33, 0xFFD8DFE9, false);
        context.drawText(this.textRenderer, "Version", x + 80, overlayY + 44, uiColorBasicSubtitleText, false);
        String versionLabel = remoteData != null && remoteData.found() && remoteData.versionNumber() != null && !remoteData.versionNumber().isBlank()
                ? version + "  ->  " + remoteData.versionNumber()
                : version;
        this.previewVersionX = x + 122;
        this.previewVersionY = overlayY + 44;
        this.previewVersionWidth = this.textRenderer.getWidth(versionLabel);
        this.previewVersionSplitX = remoteData != null && remoteData.found() && remoteData.versionNumber() != null && !remoteData.versionNumber().isBlank()
                ? this.previewVersionX + this.textRenderer.getWidth(version + "  ->")
                : -1;
        context.drawText(this.textRenderer, versionLabel, this.previewVersionX, this.previewVersionY, 0xFFE9DFC9, false);
        context.drawText(this.textRenderer, "Authors", x + 80, overlayY + 55, uiColorBasicSubtitleText, false);
        context.drawText(this.textRenderer, fitDetailsText(authors, 188), x + 122, overlayY + 55, 0xFFD8DFE9, false);

        int tagsY = overlayY + 66;
        List<PreviewChip> tagChips = buildPreviewTagChips(mod, remoteData);
        int tagBottom = renderPreviewMetadataChips(context, x + 80, tagsY, panelWidth - 80, tagChips);

        int detailsViewportY = Math.max(overlayY + 82, tagBottom + 2);
        int detailsViewportHeight = Math.max(70, panelHeight - (detailsViewportY - y) - 8);
        this.previewViewportX = x + 8;
        this.previewViewportY = detailsViewportY;
        this.previewViewportWidth = panelWidth - 16;
        this.previewViewportHeight = detailsViewportHeight;
        context.enableScissor(this.previewViewportX, this.previewViewportY, this.previewViewportX + this.previewViewportWidth, this.previewViewportY + this.previewViewportHeight);

        int contentY = detailsViewportY - this.previewScrollOffset;
        int contentHeight = 0;
        String previewDescription = remoteData != null && remoteData.found() && remoteData.body() != null && !remoteData.body().isBlank()
                ? remoteData.body()
                : remoteData != null && remoteData.found() && remoteData.projectDescription() != null && !remoteData.projectDescription().isBlank()
                ? remoteData.projectDescription()
                : description;
        if (remoteData != null && remoteData.found()) {
            renderSectionRule(context, x + 8, x + panelWidth - 8, contentY, "Remote Data");
            contentY += 14;
            contentHeight += 14;
            int nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Provider", blankFallback(remoteData.provider(), "Modrinth"), 0xFF76E6A0);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Sides", blankFallback(remoteData.clientSide(), "unknown") + " client  |  " + blankFallback(remoteData.serverSide(), "unknown") + " server", 0xFFD7E2EF);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Targets", formatTargets(remoteData), 0xFFD6E5DA);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Type", blankFallback(remoteData.projectType(), "mod"), 0xFFDCE4EE);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "License", blankFallback(remoteData.licenseName(), "unknown"), 0xFFE4DAC8);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Loader", blankFallback(remoteData.loaderRequirement(), "auto"), 0xFFD6E5DA);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Downloads", blankFallback(remoteData.downloads(), "0"), 0xFFDDE5EF);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Followers", blankFallback(remoteData.followers(), "0"), 0xFFD7DDF0);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Links", formatLinks(remoteData), 0xFFD3E3DE);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Dates", formatDates(remoteData), 0xFFD9D5E8);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Project", remoteData.projectTitle() + "  |  " + remoteData.projectSlug(), 0xFFF2F4F7);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Author", blankFallback(remoteData.authorName(), primaryAuthor(mod)), 0xFFD8E7DE);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Version", (remoteData.versionTitle() == null || remoteData.versionTitle().isBlank() ? remoteData.versionSummary() : remoteData.versionTitle()), 0xFFDCE4EE);
            contentHeight += nextY - contentY;
            contentY = nextY;
            if (!remoteData.exactVersion()) {
                context.drawText(this.textRenderer, "Closest available version info is being shown for this installed mod.", x + 10, contentY, 0xFFD9C48F, false);
                contentY += this.textRenderer.fontHeight + 4;
                contentHeight += this.textRenderer.fontHeight + 4;
            }
            if (!remoteData.exactGameVersion() || !remoteData.exactLoaderMatch()) {
                context.drawText(this.textRenderer, "Compatibility was matched as closely as possible for this instance.", x + 10, contentY, 0xFFD9C48F, false);
                contentY += this.textRenderer.fontHeight + 4;
                contentHeight += this.textRenderer.fontHeight + 4;
            }
        } else if (modrinthPreviewLoading.contains(modID)) {
            renderSectionRule(context, x + 8, x + panelWidth - 8, contentY, "Remote Data");
            contentY += 14;
            contentHeight += 14;
            context.drawText(this.textRenderer, "Loading Modrinth metadata...", x + 10, contentY, uiColorContentBaseDescriptionText, false);
            contentY += 16;
            contentHeight += 16;
        } else {
            renderSectionRule(context, x + 8, x + panelWidth - 8, contentY, "Remote Data");
            contentY += 14;
            contentHeight += 14;
            context.drawText(this.textRenderer, "No Modrinth metadata match was found for this mod.", x + 10, contentY, uiColorContentBaseDescriptionText, false);
            contentY += 16;
            contentHeight += 16;
        }
        renderSectionRule(context, x + 8, x + panelWidth - 8, contentY, "Local Metadata");
        contentY += 14;
        contentHeight += 14;
        int nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Type", blankFallback(mod.getMetadata().getType(), "unknown"), 0xFFD5DDE7);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Environment", String.valueOf(mod.getMetadata().getEnvironment()), 0xFFCCD7E6);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Licenses", joinCollection(mod.getMetadata().getLicense(), 4), 0xFFE5D9C2);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Provides", joinCollection(mod.getMetadata().getProvides(), 4), 0xFFD2E0CF);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Contributors", joinPeople(mod.getMetadata().getContributors(), 4), 0xFFD8DFE9);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Depends", String.valueOf(mod.getMetadata().getDependencies().size()), 0xFFD9D4E5);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Contact", formatContactInfo(mod), 0xFFC9DDD9);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Metadata File", blankFallback(localJarInsight.metadataFile(), "unknown"), 0xFFD8DFE9);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Entrypoints", String.valueOf(localJarInsight.entrypointCount()), 0xFFDCE4EE);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Mixins", String.valueOf(localJarInsight.mixinCount()), 0xFFD6E5DA);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Access Wideners", String.valueOf(localJarInsight.accessWidenerCount()), 0xFFD9D4E5);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Loader Hints", blankFallback(localJarInsight.loaderHints(), "none"), 0xFFC9DDD9);
        contentHeight += nextY - contentY;
        contentY = nextY;
        contentY += 6;
        contentHeight += 6;
        int descriptionWidth = Math.max(180, panelWidth - 20);
        renderSectionRule(context, x + 8, x + panelWidth - 8, contentY, remoteData != null && remoteData.found() ? "Description" : "Local Description");
        contentY += 14;
        contentHeight += 14;
        for (MarkdownPreviewRenderer.Line line : MarkdownPreviewRenderer.wrap(previewDescription, this.textRenderer, descriptionWidth)) {
            int lineHeight = MarkdownPreviewRenderer.renderLine(context, this.textRenderer, line, x + 10, contentY);
            contentY += lineHeight;
            contentHeight += lineHeight;
        }
        contentY += 6;
        contentHeight += 6;
        renderSectionRule(context, x + 8, x + panelWidth - 8, contentY, "Local File");
        contentY += 14;
        contentHeight += 14;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "File", fileName, uiColorContentBaseTitleText);
        contentHeight += nextY - contentY;
        contentY = nextY;
        for (MarkdownPreviewRenderer.Line line : MarkdownPreviewRenderer.wrap(filePath, this.textRenderer, panelWidth - 20)) {
            int lineHeight = MarkdownPreviewRenderer.renderLine(context, this.textRenderer, line, x + 10, contentY);
            contentY += lineHeight;
            contentHeight += lineHeight;
        }
        this.previewScrollMax = Math.max(0, contentHeight - detailsViewportHeight + 4);
        if (this.previewScrollOffset > this.previewScrollMax) {
            this.previewScrollOffset = this.previewScrollMax;
        }
        context.disableScissor();
        renderPreviewScrollbar(context);
    }

    private void renderSectionRule(DrawContext context, int left, int right, int y, String title) {
        context.fill(left, y, right, y + 1, 0x50798596);
        context.drawText(this.textRenderer, title, left + 2, y + 3, 0xFFBFCAD8, false);
    }

    private int renderPreviewInfoLine(DrawContext context, int x, int y, int panelWidth, String label, String value, int valueColor) {
        int labelWidth = PREVIEW_INFO_LABEL_WIDTH;
        int valueX = x + labelWidth;
        int valueWidth = Math.max(40, panelWidth - (valueX - x) - 18);
        int lineLeft = x - 2;
        int lineRight = x + panelWidth - 12;

        context.fill(lineLeft, y - 2, lineRight, y - 1, 0x20374455);
        context.drawText(this.textRenderer, label, x, y, 0xFF9FB1C4, false);
        context.fill(valueX - 7, y - 1, valueX - 6, y + this.textRenderer.fontHeight + 1, 0x38465567);

        List<MarkdownPreviewRenderer.Line> lines = MarkdownPreviewRenderer.wrap(blankFallback(value, "none"), this.textRenderer, valueWidth);
        int currentY = y;
        for (int i = 0; i < lines.size(); i++) {
            MarkdownPreviewRenderer.Line line = lines.get(i);
            int lineHeight = MarkdownPreviewRenderer.renderLine(
                    context,
                    this.textRenderer,
                    new MarkdownPreviewRenderer.Line(line.rawText(), 0, valueColor, 0, MarkdownPreviewRenderer.Accent.NONE),
                    valueX,
                    currentY
            );
            currentY += lineHeight;
        }
        int rowBottom = Math.max(y + this.textRenderer.fontHeight + PREVIEW_INFO_ROW_PADDING, currentY + 1);
        context.fill(lineLeft, rowBottom, lineRight, rowBottom + 1, 0x142C3643);
        return rowBottom + PREVIEW_INFO_ROW_PADDING;
    }

    private String joinCollection(Collection<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return "none";
        }
        return values.stream().limit(limit).collect(Collectors.joining(", "));
    }

    private String joinPeople(Collection<Person> values, int limit) {
        if (values == null || values.isEmpty()) {
            return "none";
        }
        return values.stream().map(Person::getName).limit(limit).collect(Collectors.joining(", "));
    }

    private String formatContactInfo(ModContainer mod) {
        Map<String, String> contact = mod.getMetadata().getContact().asMap();
        if (contact == null || contact.isEmpty()) {
            return "none";
        }
        return contact.entrySet().stream().limit(3).map(Map.Entry::getKey).collect(Collectors.joining("  |  "));
    }

    private KoilLocalModJarInsight inspectLocalJar(ModContainer mod) {
        File file = getPhysicalModFile(mod);
        return KoilLocalModJarInspector.inspect(file);
    }

    private void renderPreviewSourceChip(DrawContext context) {
        String chipLabel = this.selectedMod != null && this.previewSourceMode != PreviewSourceMode.LOCAL ? blankFallback(getOrFetchModrinthPreview(this.selectedMod) != null ? getOrFetchModrinthPreview(this.selectedMod).provider() : this.previewSourceMode.label, this.previewSourceMode.label) : this.previewSourceMode.label;
        context.fill(this.previewSourceChipX - 4, this.previewSourceChipY - 3, this.previewSourceChipX + this.previewSourceChipWidth, this.previewSourceChipY + 10, 0x27333D49);
        context.drawBorder(this.previewSourceChipX - 4, this.previewSourceChipY - 3, this.previewSourceChipWidth + 4, 13, 0x8E6A7684);
        context.drawText(this.textRenderer, "Source " + chipLabel, this.previewSourceChipX, this.previewSourceChipY, 0xFFD5DEE8, false);
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

    private boolean isOverPreviewScrollbar(double mouseX, double mouseY) {
        int trackX = this.previewViewportX + this.previewViewportWidth - 4;
        return mouseX >= trackX - 4
                && mouseX <= trackX + 8
                && mouseY >= this.previewViewportY
                && mouseY <= this.previewViewportY + this.previewViewportHeight;
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

    private void renderPreviewHoverTooltip(DrawContext context, int mouseX, int mouseY) {
        if (MarkdownPreviewRenderer.renderLinkTooltip(context, this.textRenderer, mouseX, mouseY)) {
            return;
        }
        for (PreviewTooltipRegion region : this.previewTooltipRegions) {
            if (mouseX >= region.x() && mouseX <= region.x() + region.width()
                    && mouseY >= region.y() && mouseY <= region.y() + region.height()) {
                context.drawTooltip(this.textRenderer, region.lines(), mouseX, mouseY);
                return;
            }
        }
        if (isWithinInstalledFilter(mouseX, mouseY, this.previewSourceChipX, this.previewSourceChipY, this.previewSourceChipWidth, 10)) {
            List<Text> lines = new ArrayList<>();
            lines.add(Text.literal("Source").styled(style -> style.withColor(0xFF96A9BC)));
            lines.add(Text.literal(switch (this.previewSourceMode) {
                case AUTO -> "Best available match";
                case MODRINTH -> "Remote provider data";
                case CURSEFORGE -> getCurseForgeApiKey().isBlank() ? "Unavailable until a CurseForge API key is configured" : "CurseForge provider data";
                case LOCAL -> "Local jar metadata only";
            }).styled(style -> style.withColor(0xFFE6EDF5)));
            if (getCurseForgeApiKey().isBlank()) {
                lines.add(Text.literal("CurseForge is hidden until Koil has a valid API key.").styled(style -> style.withColor(0xFFB8C4D2)));
            }
            context.drawTooltip(this.textRenderer, lines, mouseX, mouseY);
            return;
        }
        if (mouseX >= this.previewVersionX && mouseX <= this.previewVersionX + this.previewVersionWidth
                && mouseY >= this.previewVersionY && mouseY <= this.previewVersionY + this.textRenderer.fontHeight) {
            List<Text> lines = new ArrayList<>();
            if (this.previewVersionSplitX > 0 && mouseX >= this.previewVersionSplitX) {
                lines.add(Text.literal("Source").styled(style -> style.withColor(0xFF96A9BC)));
                if (this.previewSourceMode == PreviewSourceMode.LOCAL) {
                    lines.add(Text.literal("Local").styled(style -> style.withColor(0xFFE6EDF5)));
                } else {
                    String provider = this.selectedMod != null && getOrFetchModrinthPreview(this.selectedMod) != null ? getOrFetchModrinthPreview(this.selectedMod).provider() : this.previewSourceMode.label;
                    int providerColor = "Modrinth".equalsIgnoreCase(provider) ? 0xFF76E6A0 : 0xFFE6EDF5;
                    lines.add(Text.literal(provider).styled(style -> style.withColor(providerColor)));
                }
            } else {
                lines.add(Text.literal("Source").styled(style -> style.withColor(0xFF96A9BC)));
                lines.add(Text.literal("Local").styled(style -> style.withColor(0xFFE6EDF5)));
            }
            context.drawTooltip(this.textRenderer, lines, mouseX, mouseY);
            return;
        }
        if (this.previewUpdateChipWidth > 0
                && mouseX >= this.previewUpdateChipX && mouseX <= this.previewUpdateChipX + this.previewUpdateChipWidth
                && mouseY >= this.previewUpdateChipY && mouseY <= this.previewUpdateChipY + 11) {
            context.drawTooltip(this.textRenderer, List.of(
                    Text.literal("Open Update Search").styled(style -> style.withColor(0xFF96A9BC)),
                    Text.literal("Jump to downloader results for this mod").styled(style -> style.withColor(0xFF76E6A0))
            ), mouseX, mouseY);
        }
    }

    private void openSelectedModInDownloader() {
        if (this.client == null || this.selectedMod == null) {
            return;
        }
        String query = this.selectedMod.getMetadata().getId();
        if (query == null || query.isBlank()) {
            query = this.selectedMod.getMetadata().getName();
        }
        UiSoundHelper.playButtonClick();
        this.client.setScreen(new ModScreen(this, query));
    }

    public void setSelectedMod(ModContainer mod) {
        this.selectedMod = mod;
        this.previewScrollOffset = 0;
        this.previewScrollMax = 0;
        if (mod != null) {
            queueRemoteAvailabilityChecks(mod);
        }

        boolean modSelected = mod != null;
        this.disableButton.active = modSelected;
        this.deleteButton.active = modSelected;
        if (this.websiteButton != null) {
            this.websiteButton.active = modSelected;
        }
        if (!modSelected) {
            this.configButton.active = false;
            this.selectedModIcon = null;
            this.selectedModBanner = null;
            return;
        }

        this.configButton.active = modSelected && getDiscoveredConfigs(mod).hasFiles();


        Optional<String> iconPathOptional = mod.getMetadata().getIconPath(32);
        if (iconPathOptional.isPresent()) {
            String iconPath = iconPathOptional.get();
            try (InputStream iconStream = mod.getPath(iconPath).toUri().toURL().openStream()) {
                this.selectedModIcon = loadImage(iconStream.readAllBytes(), iconPath);
                this.selectedModIconIdentifier = new Identifier("selected_mod_icon_" + mod.getMetadata().getId());
                if (this.selectedModIcon != null) {
                    client.getTextureManager().registerTexture(selectedModIconIdentifier, selectedModIcon);
                }
            } catch (Exception e) {
                e.printStackTrace();
                this.selectedModIcon = null;
            }
        } else {
            this.selectedModIcon = null;
        }

        if (mod.getMetadata().containsCustomValue("banner")) {
            String bannerPath = mod.getMetadata().getCustomValue("banner").getAsString();
            if (!bannerPath.isEmpty()) {
                try (InputStream bannerStream = mod.getPath(bannerPath).toUri().toURL().openStream()) {
                    this.selectedModBanner = loadImage(bannerStream.readAllBytes(), bannerPath);
                    this.selectedModBannerIdentifier = new Identifier("selected_mod_banner_" + mod.getMetadata().getId());
                    if (this.selectedModBanner != null) {
                        client.getTextureManager().registerTexture(selectedModBannerIdentifier, selectedModBanner);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    this.selectedModBanner = null;
                }
            }
        } else {
            this.selectedModBanner = null;
        }
    }

    private DiscoveredModConfigSet getDiscoveredConfigs(ModContainer mod) {
        return configDiscoveryCache.computeIfAbsent(mod.getMetadata().getId(), ignored -> ModConfigDiscoveryService.discover(mod));
    }

    private int renderDetailChip(DrawContext context, int x, int y, String label, int background) {
        return renderDetailChip(context, x, y, label, background, null);
    }

    private int renderDetailChip(DrawContext context, int x, int y, String label, int background, List<Text> tooltipLines) {
        int width = this.textRenderer.getWidth(label) + 10;
        context.fill(x, y, x + width, y + 11, background);
        context.drawBorder(x, y, width, 11, 0xB06B7485);
        context.drawText(this.textRenderer, label, x + 5, y + 2, 0xFFE8EDF5, false);
        if (tooltipLines != null && !tooltipLines.isEmpty()) {
            this.previewTooltipRegions.add(new PreviewTooltipRegion(x, y, width, 11, tooltipLines));
        }
        return x + width;
    }

    private record PreviewChip(String label, int background, List<Text> tooltipLines) {
    }

    private int renderPreviewMetadataChips(DrawContext context, int x, int y, int panelWidth, List<PreviewChip> chips) {
        int cursorX = x;
        int cursorY = y;
        int right = x + panelWidth - 18;
        for (PreviewChip chip : chips) {
            if (chip == null || chip.label() == null || chip.label().isBlank()) {
                continue;
            }
            int chipWidth = this.textRenderer.getWidth(chip.label()) + 10;
            if (cursorX + chipWidth > right) {
                cursorX = x;
                cursorY += 14;
            }
            cursorX = renderDetailChip(context, cursorX, cursorY, chip.label(), chip.background(), chip.tooltipLines()) + 4;
        }
        return cursorY + 14;
    }

    private List<PreviewChip> buildPreviewTagChips(ModContainer mod, ModrinthPreviewData remoteData) {
        List<PreviewChip> chips = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (remoteData == null || !remoteData.found() || remoteData.categories() == null) {
            return chips;
        }
        for (String category : remoteData.categories()) {
            addPreviewTagChip(chips, seen, category);
        }
        return chips;
    }

    private void addPreviewTagChip(List<PreviewChip> chips, Set<String> seen, String label) {
        if (label == null) {
            return;
        }
        String normalized = label.trim();
        if (!isValidPreviewTag(normalized)) {
            return;
        }
        String dedupeKey = normalized.toLowerCase(Locale.ROOT);
        if (!seen.add(dedupeKey)) {
            return;
        }
        chips.add(new PreviewChip(normalized, getPreviewTagBackground(normalized), buildPreviewTagTooltip(normalized)));
    }

    private boolean isValidPreviewTag(String label) {
        if (label == null) {
            return false;
        }
        String normalized = label.trim();
        if (normalized.isBlank() || normalized.length() > 36) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("/") || lower.contains("\\") || lower.contains(":")) {
            return false;
        }
        if (lower.endsWith(".class") || lower.endsWith(".java") || lower.endsWith(".jar") || lower.endsWith(".zip")) {
            return false;
        }
        if (lower.contains(".class") || lower.contains(".java")) {
            return false;
        }
        if (normalized.matches(".*\\$\\d*.*")) {
            return false;
        }
        if (normalized.matches(".*[A-Za-z0-9_]+\\.[A-Za-z0-9_$.]+.*")) {
            return false;
        }
        return normalized.matches(".*[A-Za-z].*");
    }

    private int getPreviewTagBackground(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        if (lower.contains("library") || lower.contains("api") || lower.contains("utility")) {
            return 0x8A33465C;
        }
        if (lower.contains("client") || lower.contains("hud") || lower.contains("gui") || lower.contains("visual")) {
            return 0x8A3B4D63;
        }
        if (lower.contains("server") || lower.contains("world") || lower.contains("generation") || lower.contains("technology")) {
            return 0x8A4A4231;
        }
        if (lower.contains("adventure") || lower.contains("magic") || lower.contains("combat") || lower.contains("equipment")) {
            return 0x8A4E3848;
        }
        return 0x8A33465C;
    }

    private List<Text> buildPreviewTagTooltip(String label) {
        int accent = getPreviewTagAccent(label);
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal("Tag").styled(style -> style.withColor(0xFF96A9BC)));
        lines.add(Text.literal(label).styled(style -> style.withColor(accent)));
        lines.add(Text.literal(describePreviewTag(label)).styled(style -> style.withColor(0xFFE6EDF5)));
        return lines;
    }

    private int getPreviewTagAccent(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        if (lower.contains("library") || lower.contains("api") || lower.contains("utility")) {
            return 0xFF8FC5FF;
        }
        if (lower.contains("client") || lower.contains("hud") || lower.contains("gui") || lower.contains("visual")) {
            return 0xFF9ED6FF;
        }
        if (lower.contains("server") || lower.contains("world") || lower.contains("generation") || lower.contains("technology")) {
            return 0xFFD9C48F;
        }
        if (lower.contains("adventure") || lower.contains("magic") || lower.contains("combat") || lower.contains("equipment")) {
            return 0xFFE0A8D3;
        }
        return 0xFF8FC5FF;
    }

    private String describePreviewTag(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        if (lower.contains("library") || lower.contains("api")) {
            return "Marks shared systems or dependency-style mod features.";
        }
        if (lower.contains("utility")) {
            return "Marks helper-focused quality-of-life or workflow features.";
        }
        if (lower.contains("client") || lower.contains("hud") || lower.contains("gui") || lower.contains("visual")) {
            return "Marks client-side presentation, interface, or visual behavior.";
        }
        if (lower.contains("server")) {
            return "Marks server-side logic or multiplayer-facing behavior.";
        }
        if (lower.contains("world") || lower.contains("generation")) {
            return "Marks terrain, dimension, structure, or world-generation content.";
        }
        if (lower.contains("technology")) {
            return "Marks automation, machines, processing, or technical progression.";
        }
        if (lower.contains("adventure")) {
            return "Marks exploration, travel, progression, or discovery content.";
        }
        if (lower.contains("magic")) {
            return "Marks spell, ritual, arcane, or fantasy-style systems.";
        }
        if (lower.contains("combat")) {
            return "Marks combat, weapons, enemies, or battle-focused mechanics.";
        }
        if (lower.contains("equipment")) {
            return "Marks gear, tools, armor, trinkets, or wearable content.";
        }
        if (lower.contains("storage")) {
            return "Marks inventory, container, network, or item-management features.";
        }
        if (lower.contains("optimization") || lower.contains("performance")) {
            return "Marks performance-focused or technical optimization changes.";
        }
        return "Marks a provider category used to describe this mod's feature focus.";
    }

    private String fitDetailsText(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        String trimmed = this.textRenderer.trimToWidth(text, maxWidth);
        if (trimmed.length() == text.length()) {
            return trimmed;
        }
        return this.textRenderer.trimToWidth(text, Math.max(8, maxWidth - this.textRenderer.getWidth("..."))) + "...";
    }

    private String blankFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String formatTargets(ModrinthPreviewData data) {
        String loaderPart = data.loaders() == null || data.loaders().isEmpty() ? "loaders unknown" : String.join(", ", data.loaders());
        String versionPart = data.gameVersions() == null || data.gameVersions().isEmpty() ? "versions unknown" : String.join(", ", data.gameVersions().subList(0, Math.min(3, data.gameVersions().size())));
        return loaderPart + "  |  " + versionPart + "  |  " + data.versionCount() + " version" + (data.versionCount() == 1 ? "" : "s");
    }

    private String formatTargetsShort(ModrinthPreviewData data) {
        String loaderPart = data.loaders() == null || data.loaders().isEmpty() ? "unknown" : String.join(", ", data.loaders());
        String versionPart = data.gameVersions() == null || data.gameVersions().isEmpty() ? "unknown" : String.join(", ", data.gameVersions().subList(0, Math.min(2, data.gameVersions().size())));
        return loaderPart + "  |  " + versionPart;
    }

    private String formatLinks(ModrinthPreviewData data) {
        List<String> links = new ArrayList<>();
        if (data.sourceUrl() != null && !data.sourceUrl().isBlank()) {
            links.add("source");
        }
        if (data.issuesUrl() != null && !data.issuesUrl().isBlank()) {
            links.add("issues");
        }
        if (data.wikiUrl() != null && !data.wikiUrl().isBlank()) {
            links.add("wiki");
        }
        if (data.discordUrl() != null && !data.discordUrl().isBlank()) {
            links.add("discord");
        }
        return links.isEmpty() ? "no public links" : String.join("  |  ", links);
    }

    private String formatDates(ModrinthPreviewData data) {
        String published = blankFallback(shortIsoDate(data.publishedAt()), "unknown");
        String updated = blankFallback(shortIsoDate(data.updatedAt()), "unknown");
        return "Published " + published + "  |  Updated " + updated;
    }

    private String shortIsoDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.length() >= 10 ? raw.substring(0, 10) : raw;
    }

    private void openImportModChooser() {
        String chosenPath = TinyFileDialogs.tinyfd_openFileDialog(
                "Import Mod",
                FabricLoader.getInstance().getGameDir().resolve("mods").toAbsolutePath().toString(),
                null,
                null,
                false
        );
        if (chosenPath == null || chosenPath.isBlank()) {
            return;
        }

        Path source = Path.of(chosenPath);
        Path modsDirectory = FabricLoader.getInstance().getGameDir().resolve("mods");
        try {
            Files.createDirectories(modsDirectory);
            Path target = modsDirectory.resolve(source.getFileName());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            reloadInstalledScreen();
        } catch (IOException exception) {
            SUBLOGGER.logE("File-Management", "Failed to import mod: " + exception.getMessage(), true, "Failed to import mod: " + exception.getMessage());
        }
    }

    private void reloadInstalledScreen() {
        if (this.client == null) {
            return;
        }
        ModMenuScreen refreshed = new ModMenuScreen(this.title);
        refreshed.parent = this.parent;
        this.client.setScreen(refreshed);
    }

    private void rebuildInstalledModList() {
        this.modListWidget.clearEntries();
        List<ModContainer> filteredMods = mods.stream()
                .filter(this::matchesInstalledSearch)
                .filter(this::matchesInstalledFilter)
                .filter(this::matchesInstalledPicturePreference)
                .sorted(installedModComparator())
                .toList();
        if (installedGroupMode == InstalledGroupMode.NONE) {
            filteredMods.forEach(mod -> modListWidget.addModEntry(new ModListWidget.ModEntry(mod, client, this)));
        } else if (installedGroupMode == InstalledGroupMode.FAMILY) {
            Map<String, List<ModContainer>> grouped = new LinkedHashMap<>();
            for (ModContainer mod : filteredMods) {
                grouped.computeIfAbsent(familyClusterKey(mod), ignored -> new ArrayList<>()).add(mod);
            }
            for (Map.Entry<String, List<ModContainer>> group : grouped.entrySet()) {
                List<ModContainer> clusterMods = group.getValue();
                ModContainer representative = chooseRepresentative(clusterMods);
                if (clusterMods.size() <= 1) {
                    modListWidget.addModEntry(new ModListWidget.ModEntry(representative, client, this));
                    continue;
                }
                boolean expanded = this.expandedInstalledGroups.contains(group.getKey());
                ModListWidget.ParentModEntry parentEntry = new ModListWidget.ParentModEntry(representative, client, this, group.getKey(), clusterMods.size(), expanded);
                for (ModContainer clusterMod : clusterMods) {
                    if (!clusterMod.getMetadata().getId().equals(representative.getMetadata().getId())) {
                        parentEntry.addChildMod(clusterMod);
                    }
                }
                modListWidget.addParentModEntry(parentEntry);
                if (expanded) {
                    for (ModListWidget.ModEntry child : parentEntry.getChildMods()) {
                        modListWidget.addModEntry(child);
                    }
                }
            }
        } else {
            Map<String, List<ModContainer>> grouped = new LinkedHashMap<>();
            for (ModContainer mod : filteredMods) {
                grouped.computeIfAbsent(groupLabel(mod), ignored -> new ArrayList<>()).add(mod);
            }
            for (Map.Entry<String, List<ModContainer>> group : grouped.entrySet()) {
                modListWidget.addGroupHeader(group.getKey(), group.getValue().size(), true, this);
                group.getValue().forEach(mod -> modListWidget.addModEntry(new ModListWidget.ModEntry(mod, client, this)));
            }
        }
        if (selectedMod == null || filteredMods.stream().noneMatch(mod -> mod.getMetadata().getId().equals(selectedMod.getMetadata().getId()))) {
            selectedMod = filteredMods.isEmpty() ? null : filteredMods.get(0);
            if (selectedMod != null) {
                setSelectedMod(selectedMod);
            } else {
                this.modSelected(false, false);
            }
        }
    }

    private boolean matchesInstalledSearch(ModContainer mod) {
        String query = this.searchField == null ? "" : this.searchField.getText().trim().toLowerCase(Locale.ROOT);
        if (query.isBlank()) {
            return true;
        }
        String name = mod.getMetadata().getName().toLowerCase(Locale.ROOT);
        String id = mod.getMetadata().getId().toLowerCase(Locale.ROOT);
        String authors = mod.getMetadata().getAuthors().stream()
                .map(Person::getName)
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
        return name.contains(query) || id.contains(query) || authors.contains(query);
    }

    private boolean matchesInstalledFilter(ModContainer mod) {
        return switch (installedFilterMode) {
            case ALL -> true;
            case ENABLED -> !isDisabledMod(mod);
            case DISABLED -> isDisabledMod(mod);
            case CONFIGS -> getDiscoveredConfigs(mod).hasFiles();
        };
    }

    private boolean matchesInstalledPicturePreference(ModContainer mod) {
        return this.showIconlessMods || hasModPicture(mod);
    }

    private Comparator<ModContainer> installedModComparator() {
        Comparator<ModContainer> base = switch (installedSortMode) {
            case NAME -> Comparator.comparing(mod -> mod.getMetadata().getName().toLowerCase(Locale.ROOT));
            case MOD_ID -> Comparator.comparing(mod -> mod.getMetadata().getId().toLowerCase(Locale.ROOT));
            case AUTHOR -> Comparator.comparing(mod -> primaryAuthor(mod).toLowerCase(Locale.ROOT));
            case STATUS -> Comparator
                    .comparing((ModContainer mod) -> isDisabledMod(mod))
                    .thenComparing(mod -> mod.getMetadata().getName().toLowerCase(Locale.ROOT));
            case PICTURE -> Comparator
                    .comparing((ModContainer mod) -> !hasModPicture(mod))
                    .thenComparing(mod -> mod.getMetadata().getName().toLowerCase(Locale.ROOT));
        };
        return installedSortMode == InstalledSortMode.PICTURE ? base : Comparator
                .comparing((ModContainer mod) -> !hasModPicture(mod))
                .thenComparing(base);
    }

    private String groupLabel(ModContainer mod) {
        return switch (installedGroupMode) {
            case AUTHOR -> primaryAuthor(mod);
            case STATUS -> isDisabledMod(mod) ? "Disabled" : "Enabled";
            case FAMILY -> familyLabel(mod);
            case NONE -> "";
        };
    }

    private String familyLabel(ModContainer mod) {
        String modId = mod.getMetadata().getId().toLowerCase(Locale.ROOT);
        String name = mod.getMetadata().getName().toLowerCase(Locale.ROOT);
        String author = primaryAuthor(mod).toLowerCase(Locale.ROOT);
        if (modId.equals("fabricloader") || modId.startsWith("fabric-") || modId.startsWith("fabric_")
                || name.contains("fabric api") || author.contains("fabricmc")) {
            return "Fabric System";
        }
        if (modId.startsWith("quilt-") || modId.startsWith("qsl-") || author.contains("quilt")) {
            return "Quilt System";
        }
        if (modId.contains("cloth") || modId.contains("yacl") || modId.contains("modmenu") || modId.contains("midnightlib")) {
            return "Support Libraries";
        }
        return "Mods";
    }

    private String familyClusterKey(ModContainer mod) {
        String label = familyLabel(mod);
        if ("Mods".equals(label)) {
            return mod.getMetadata().getId();
        }
        return label;
    }

    private ModContainer chooseRepresentative(List<ModContainer> mods) {
        return mods.stream()
                .sorted(Comparator
                        .comparing((ModContainer mod) -> !hasModPicture(mod))
                        .thenComparing(mod -> !mod.getMetadata().getName().toLowerCase(Locale.ROOT).contains("api"))
                        .thenComparing(mod -> mod.getMetadata().getName().length()))
                .findFirst()
                .orElse(mods.get(0));
    }

    private void toggleInstalledGroup(String label) {
        if (this.expandedInstalledGroups.contains(label)) {
            this.expandedInstalledGroups.remove(label);
        } else {
            this.expandedInstalledGroups.add(label);
        }
        rebuildInstalledModList();
    }

    private String primaryAuthor(ModContainer mod) {
        return mod.getMetadata().getAuthors().stream().findFirst().map(Person::getName).orElse("Unknown");
    }

    private boolean hasModPicture(ModContainer mod) {
        return mod.getMetadata().getIconPath(32).isPresent();
    }

    private boolean isDisabledMod(ModContainer mod) {
        File file = getPhysicalModFile(mod);
        return file != null && file.getName().toLowerCase(Locale.ROOT).endsWith(".disabled");
    }

    private void openInstalledFilterMenu(double mouseX, double mouseY, List<PopupMenu.MenuEntry> entries) {
        this.installedFilterPopup.openAtPointer(mouseX, mouseY, this.width, this.height, entries);
    }

    private List<PopupMenu.MenuEntry> buildPreviewSourceEntries() {
        List<PopupMenu.MenuEntry> entries = new ArrayList<>();
        for (PreviewSourceMode mode : availablePreviewSources()) {
            entries.add(new PopupMenu.MenuEntry("source:" + mode.name(), mode.label));
        }
        return entries;
    }

    private List<PreviewSourceMode> availablePreviewSources() {
        List<PreviewSourceMode> modes = new ArrayList<>();
        modes.add(PreviewSourceMode.AUTO);
        modes.add(PreviewSourceMode.MODRINTH);
        if (isCurseForgeUsableForSelectedMod()) {
            modes.add(PreviewSourceMode.CURSEFORGE);
        }
        modes.add(PreviewSourceMode.LOCAL);
        return modes;
    }

    private boolean isCurseForgeUsableForSelectedMod() {
        if (this.selectedMod == null || getCurseForgeApiKey().isBlank()) {
            return false;
        }
        String modId = this.selectedMod.getMetadata().getId();
        Boolean available = this.curseForgeAvailabilityCache.get(modId);
        if (available != null) {
            return available;
        }
        queueCurseForgeAvailabilityCheck(this.selectedMod);
        return false;
    }

    private void queueRemoteAvailabilityChecks(ModContainer mod) {
        queueCurseForgeAvailabilityCheck(mod);
    }

    private void queueCurseForgeAvailabilityCheck(ModContainer mod) {
        if (mod == null || getCurseForgeApiKey().isBlank()) {
            return;
        }
        String modId = mod.getMetadata().getId();
        if (this.curseForgeAvailabilityCache.containsKey(modId) || !this.curseForgeAvailabilityLoading.add(modId)) {
            return;
        }
        Thread thread = new Thread(() -> {
            boolean available = false;
            try {
                available = hasCurseForgeMatch(mod);
            } catch (Exception ignored) {
                available = false;
            } finally {
                this.curseForgeAvailabilityCache.put(modId, available);
                this.curseForgeAvailabilityLoading.remove(modId);
            }
        }, "koil-curseforge-availability-" + modId);
        thread.setDaemon(true);
        thread.start();
    }

    private List<PopupMenu.MenuEntry> buildSortEntries() {
        List<PopupMenu.MenuEntry> entries = new ArrayList<>();
        for (InstalledSortMode mode : InstalledSortMode.values()) {
            if (mode == InstalledSortMode.PICTURE) {
                continue;
            }
            entries.add(new PopupMenu.MenuEntry("sort:" + mode.name(), mode.label));
        }
        return entries;
    }

    private List<PopupMenu.MenuEntry> buildShowEntries() {
        List<PopupMenu.MenuEntry> entries = new ArrayList<>();
        for (InstalledFilterMode mode : InstalledFilterMode.values()) {
            entries.add(new PopupMenu.MenuEntry("show:" + mode.name(), mode.label));
        }
        entries.add(new PopupMenu.MenuEntry("show_iconless:" + (this.showIconlessMods ? "hide" : "show"), this.showIconlessMods ? "Hide No Image Mods" : "Show No Image Mods"));
        return entries;
    }

    private List<PopupMenu.MenuEntry> buildGroupEntries() {
        List<PopupMenu.MenuEntry> entries = new ArrayList<>();
        for (InstalledGroupMode mode : InstalledGroupMode.values()) {
            entries.add(new PopupMenu.MenuEntry("group:" + mode.name(), mode.label));
        }
        return entries;
    }

    private void applyInstalledFilterAction(String actionId) {
        if (actionId == null || actionId.isBlank()) {
            return;
        }
        if (actionId.startsWith("sort:")) {
            installedSortMode = InstalledSortMode.valueOf(actionId.substring("sort:".length()));
        } else if (actionId.startsWith("group:")) {
            installedGroupMode = InstalledGroupMode.valueOf(actionId.substring("group:".length()));
        } else if (actionId.startsWith("show:")) {
            installedFilterMode = InstalledFilterMode.valueOf(actionId.substring("show:".length()));
        } else if (actionId.startsWith("show_iconless:")) {
            this.showIconlessMods = actionId.endsWith("show");
        }
        rebuildInstalledModList();
    }

    private void applyPreviewSourceAction(String actionId) {
        if (actionId == null || !actionId.startsWith("source:")) {
            return;
        }
        PreviewSourceMode requested = PreviewSourceMode.valueOf(actionId.substring("source:".length()));
        if (!availablePreviewSources().contains(requested)) {
            return;
        }
        this.previewSourceMode = requested;
        if (this.selectedMod != null) {
            this.modrinthPreviewCache.remove(this.selectedMod.getMetadata().getId());
        }
    }

    private List<PopupMenu.MenuEntry> buildModActionEntries(ModContainer mod) {
        List<PopupMenu.MenuEntry> entries = new ArrayList<>();
        if (mod != null && getDiscoveredConfigs(mod).hasFiles()) {
            entries.add(new PopupMenu.MenuEntry("mod_action:config", "Open Config"));
        }
        entries.add(new PopupMenu.MenuEntry("mod_action:folder", "Open Mods Folder"));
        entries.add(new PopupMenu.MenuEntry("mod_action:copy_id", "Copy Mod ID"));
        entries.add(new PopupMenu.MenuEntry("mod_action:download", "Open Downloader"));
        return entries;
    }

    private void openModActionMenu(ModContainer mod, double mouseX, double mouseY) {
        this.popupActionMod = mod;
        this.modActionPopup.openAtPointer(mouseX, mouseY, this.width, this.height, buildModActionEntries(mod));
    }

    private void applyModAction(String actionId) {
        if (this.client == null || this.popupActionMod == null || actionId == null) {
            return;
        }
        switch (actionId) {
            case "mod_action:config" -> openConfigMenu(this.popupActionMod);
            case "mod_action:folder" -> openFolderPopupAtPointer(FabricLoader.getInstance().getGameDir().resolve("mods").toFile());
            case "mod_action:copy_id" -> this.client.keyboard.setClipboard(this.popupActionMod.getMetadata().getId());
            case "mod_action:download" -> this.client.setScreen(new ModScreen(this));
            default -> {
                return;
            }
        }
    }

    private void openFolderPopupAtPointer(File folder) {
        if (folder == null) {
            return;
        }
        this.pendingFolderOpenDirectory = folder;
        this.folderOpenPopup.toggleAtPointer(this.lastMouseX, this.lastMouseY, this.width, this.height, FolderOpenHelper.menuEntries());
    }

    private String getSortButtonLabel() {
        return "Sort: " + installedSortMode.label;
    }

    private String getShowButtonLabel() {
        return "Show: " + installedFilterMode.label;
    }

    private String getGroupButtonLabel() {
        return "Group: " + installedGroupMode.label;
    }

    private int getInstalledFilterWidth(String label) {
        return Math.max(72, this.textRenderer.getWidth(label) + 18);
    }

    private void renderInstalledFilterButton(DrawContext context, int x, String label) {
        int width = getInstalledFilterWidth(label);
        context.fill(x, INSTALLED_FILTER_Y, x + width, INSTALLED_FILTER_Y + INSTALLED_FILTER_HEIGHT, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(x, INSTALLED_FILTER_Y, width, INSTALLED_FILTER_HEIGHT, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawText(this.textRenderer, label, x + 7, INSTALLED_FILTER_Y + 6, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
    }

    private boolean isWithinInstalledFilter(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private ModrinthPreviewData getOrFetchModrinthPreview(ModContainer mod) {
        if (mod == null) {
            return null;
        }
        if (this.previewSourceMode == PreviewSourceMode.LOCAL) {
            return null;
        }
        String modId = mod.getMetadata().getId();
        ModrinthPreviewData cached = modrinthPreviewCache.get(modId);
        if (cached != null) {
            return cached;
        }
        if (modrinthPreviewLoading.add(modId)) {
            Thread thread = new Thread(() -> {
                try {
                    modrinthPreviewCache.put(modId, fetchRemotePreview(mod));
                } catch (Exception ignored) {
                    modrinthPreviewCache.put(modId, new ModrinthPreviewData("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", false, false, 0, List.of(), List.of(), List.of(), false, false, false, false));
                } finally {
                    modrinthPreviewLoading.remove(modId);
                }
            }, "koil-modrinth-preview-" + modId);
            thread.setDaemon(true);
            thread.start();
        }
        return null;
    }

    private ModrinthPreviewData fetchRemotePreview(ModContainer mod) throws Exception {
        return switch (this.previewSourceMode) {
            case CURSEFORGE -> fetchCurseForgePreview(mod);
            case MODRINTH -> fetchModrinthPreview(mod);
            case AUTO -> {
                ModrinthPreviewData modrinth = fetchModrinthPreview(mod);
                if (modrinth != null && modrinth.found()) {
                    yield modrinth;
                }
                yield fetchCurseForgePreview(mod);
            }
            case LOCAL -> null;
        };
    }

    private ModrinthPreviewData fetchModrinthPreview(ModContainer mod) throws Exception {
        String version = FabricLoader.getInstance().getModContainer("minecraft")
                .orElseThrow(() -> new RuntimeException("Missing minecraft mod container"))
                .getMetadata().getVersion().getFriendlyString();
        List<JsonObject> hits = new ArrayList<>();
        hits.addAll(fetchModrinthHits(mod.getMetadata().getId()));
        if (!mod.getMetadata().getName().equalsIgnoreCase(mod.getMetadata().getId())) {
            hits.addAll(fetchModrinthHits(mod.getMetadata().getName()));
        }
        if (hits.isEmpty()) {
            return new ModrinthPreviewData("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", false, false, 0, List.of(), List.of(), List.of(), false, false, false, false);
        }

        JsonObject chosen = null;
        boolean approximateProject = false;
        String modId = mod.getMetadata().getId();
        String modName = mod.getMetadata().getName();
        int bestScore = Integer.MIN_VALUE;
        for (JsonObject object : hits) {
            int score = scoreSearchHit(object, modId, modName);
            if (score > bestScore) {
                bestScore = score;
                chosen = object;
            }
        }
        approximateProject = bestScore < 100;

        String slug = getString(chosen, "slug");
        URI projectUri = new URI("https://api.modrinth.com/v2/project/" + slug);
        HttpResponse<String> projectResponse = HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(projectUri).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonObject project = new Gson().fromJson(projectResponse.body(), JsonObject.class);
        URI versionUri = new URI("https://api.modrinth.com/v2/project/" + slug + "/version?game_versions=%5B%22" + URLEncoder.encode(version, StandardCharsets.UTF_8) + "%22%5D&loaders=%5B%22fabric%22,%22quilt%22%5D");
        HttpResponse<String> versionResponse = HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(versionUri).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonArray versions = new Gson().fromJson(versionResponse.body(), JsonArray.class);

        JsonObject chosenVersion = null;
        boolean exactVersion = false;
        boolean exactGameVersion = false;
        boolean exactLoaderMatch = false;
        String installedVersion = mod.getMetadata().getVersion().getFriendlyString();
        String currentLoaderVersion = FabricLoader.getInstance()
                .getModContainer(FabricLoader.getInstance().isModLoaded("quilt_loader") ? "quilt_loader" : "fabricloader")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("");
        if (versions != null) {
            for (JsonElement element : versions) {
                JsonObject object = element.getAsJsonObject();
                String versionNumber = getString(object, "version_number");
                if (installedVersion.equalsIgnoreCase(versionNumber)) {
                    chosenVersion = object;
                    exactVersion = true;
                    break;
                }
            }
            if (chosenVersion == null && !versions.isEmpty()) {
                chosenVersion = versions.get(0).getAsJsonObject();
            }
        }

        List<String> categories = new ArrayList<>();
        JsonArray categoryArray = project == null ? null : project.getAsJsonArray("categories");
        if (categoryArray != null) {
            for (JsonElement element : categoryArray) {
                categories.add(element.getAsString());
                if (categories.size() >= 6) {
                    break;
                }
            }
        }

        JsonObject license = project == null || !project.has("license") || !project.get("license").isJsonObject() ? null : project.getAsJsonObject("license");

        List<String> loaders = new ArrayList<>();
        List<String> gameVersions = new ArrayList<>();
        String loaderRequirement = "";
        if (chosenVersion != null) {
            JsonArray loaderArray = chosenVersion.getAsJsonArray("loaders");
            if (loaderArray != null) {
                for (JsonElement element : loaderArray) {
                    loaders.add(element.getAsString());
                }
            }
            JsonArray gameVersionArray = chosenVersion.getAsJsonArray("game_versions");
            if (gameVersionArray != null) {
                for (JsonElement element : gameVersionArray) {
                    String gameVersion = element.getAsString();
                    gameVersions.add(gameVersion);
                    if (version.equalsIgnoreCase(gameVersion)) {
                        exactGameVersion = true;
                    }
                }
            }
            exactLoaderMatch = hasCurrentLoader(loaders);
            JsonArray dependencyArray = chosenVersion.getAsJsonArray("dependencies");
            if (dependencyArray != null) {
                for (JsonElement element : dependencyArray) {
                    JsonObject dependency = element.getAsJsonObject();
                    String versionRange = getString(dependency, "version_id");
                    if (versionRange.isBlank()) {
                        versionRange = getString(dependency, "dependency_type");
                    }
                    String fileName = getString(dependency, "file_name");
                    if (!fileName.isBlank() && (fileName.toLowerCase(Locale.ROOT).contains("fabric") || fileName.toLowerCase(Locale.ROOT).contains("quilt"))) {
                        loaderRequirement = fileName;
                        if (!versionRange.isBlank()) {
                            String requiredLoaderVersion = fetchVersionNumberById(versionRange);
                            if (!requiredLoaderVersion.isBlank()) {
                                loaderRequirement = requiredLoaderVersion;
                                exactLoaderMatch = !isVersionLower(currentLoaderVersion, requiredLoaderVersion);
                            }
                        }
                        break;
                    }
                    if (!versionRange.isBlank()) {
                        loaderRequirement = versionRange;
                    }
                }
            }
        }

        boolean updateAvailable = chosenVersion != null
                && exactGameVersion
                && exactLoaderMatch
                && !approximateProject
                && isVersionLower(installedVersion, getString(chosenVersion, "version_number"));

        return new ModrinthPreviewData(
                "Modrinth",
                getString(project == null ? chosen : project, "title"),
                slug,
                getString(project == null ? chosen : project, "description"),
                getString(project, "project_type"),
                getString(license, "name"),
                getString(project, "source_url"),
                getString(project, "issues_url"),
                getString(project, "wiki_url"),
                getString(project, "discord_url"),
                getString(project, "published"),
                getString(project, "updated"),
                project != null && project.has("team") ? getString(project, "team") : primaryAuthor(mod),
                chosenVersion == null ? "" : getString(chosenVersion, "name"),
                chosenVersion == null ? "" : getString(chosenVersion, "version_number"),
                chosenVersion == null ? "" : getString(chosenVersion, "version_type"),
                getString(project, "body"),
                getString(project == null ? chosen : project, "downloads"),
                getString(project == null ? chosen : project, "followers"),
                getString(project, "client_side"),
                getString(project, "server_side"),
                loaderRequirement,
                exactGameVersion,
                exactLoaderMatch,
                versions == null ? 0 : versions.size(),
                loaders,
                gameVersions,
                categories,
                exactVersion,
                approximateProject,
                updateAvailable,
                true
        );
    }

    private ModrinthPreviewData fetchCurseForgePreview(ModContainer mod) throws Exception {
        String apiKey = getCurseForgeApiKey();
        if (apiKey.isBlank()) {
            return new ModrinthPreviewData("CurseForge", "", "", "CurseForge API key not configured.", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", false, false, 0, List.of(), List.of(), List.of(), false, true, false, false);
        }
        String mcVersion = FabricLoader.getInstance().getModContainer("minecraft")
                .orElseThrow(() -> new RuntimeException("Missing minecraft mod container"))
                .getMetadata().getVersion().getFriendlyString();
        String modId = mod.getMetadata().getId();
        String modName = mod.getMetadata().getName();
        String search = URLEncoder.encode(modId.equalsIgnoreCase(modName) ? modId : modId + " " + modName, StandardCharsets.UTF_8);
        URI searchUri = new URI("https://api.curseforge.com/v1/mods/search?gameId=432&classId=6&searchFilter=" + search + "&gameVersion=" + URLEncoder.encode(mcVersion, StandardCharsets.UTF_8));
        HttpRequest searchRequest = HttpRequest.newBuilder()
                .uri(searchUri)
                .header("x-api-key", apiKey)
                .GET()
                .build();
        HttpResponse<String> searchResponse = HttpClient.newHttpClient().send(searchRequest, HttpResponse.BodyHandlers.ofString());
        JsonObject root = new Gson().fromJson(searchResponse.body(), JsonObject.class);
        JsonArray data = root == null ? null : root.getAsJsonArray("data");
        if (data == null || data.isEmpty()) {
            return new ModrinthPreviewData("CurseForge", "", "", "No CurseForge metadata match was found.", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", false, false, 0, List.of(), List.of(), List.of(), false, true, false, false);
        }

        JsonObject chosen = null;
        int bestScore = Integer.MIN_VALUE;
        for (JsonElement element : data) {
            JsonObject object = element.getAsJsonObject();
            int score = scoreCurseForgeHit(object, modId, modName);
            if (score > bestScore) {
                bestScore = score;
                chosen = object;
            }
        }
        if (chosen == null) {
            return new ModrinthPreviewData("CurseForge", "", "", "No CurseForge metadata match was found.", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", false, false, 0, List.of(), List.of(), List.of(), false, true, false, false);
        }

        JsonArray latestFiles = chosen.getAsJsonArray("latestFiles");
        JsonObject bestFile = null;
        boolean exactGameVersion = false;
        boolean exactLoaderMatch = false;
        String installedVersion = mod.getMetadata().getVersion().getFriendlyString();
        List<String> matchedGameVersions = new ArrayList<>();
        List<String> matchedLoaders = new ArrayList<>();
        if (latestFiles != null) {
            for (JsonElement element : latestFiles) {
                JsonObject file = element.getAsJsonObject();
                JsonArray gameVersions = file.getAsJsonArray("gameVersions");
                if (gameVersions != null) {
                    boolean gameMatch = false;
                    boolean loaderMatch = false;
                    List<String> versions = new ArrayList<>();
                    List<String> loaders = new ArrayList<>();
                    for (JsonElement versionElement : gameVersions) {
                        String gameVersion = versionElement.getAsString();
                        versions.add(gameVersion);
                        if (mcVersion.equalsIgnoreCase(gameVersion)) {
                            gameMatch = true;
                        }
                        if ("Fabric".equalsIgnoreCase(gameVersion) || "Quilt".equalsIgnoreCase(gameVersion)) {
                            loaderMatch = true;
                            loaders.add(gameVersion.toLowerCase(Locale.ROOT));
                        }
                    }
                    if (gameMatch && (bestFile == null || loaderMatch)) {
                        bestFile = file;
                        exactGameVersion = gameMatch;
                        exactLoaderMatch = loaderMatch || hasCurrentLoader(List.of("fabric", "quilt"));
                        matchedGameVersions = versions;
                        matchedLoaders = loaders;
                    }
                }
            }
            if (bestFile == null && latestFiles.size() > 0) {
                bestFile = latestFiles.get(0).getAsJsonObject();
                JsonArray gameVersions = bestFile.getAsJsonArray("gameVersions");
                if (gameVersions != null) {
                    for (JsonElement versionElement : gameVersions) {
                        String value = versionElement.getAsString();
                        matchedGameVersions.add(value);
                        if ("Fabric".equalsIgnoreCase(value) || "Quilt".equalsIgnoreCase(value)) {
                            matchedLoaders.add(value.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        }

        String remoteVersion = bestFile == null ? "" : blankFallback(getString(bestFile, "displayName"), getString(bestFile, "fileName"));
        boolean updateAvailable = !remoteVersion.isBlank() && isVersionLower(installedVersion, remoteVersion);
        JsonArray authorsArray = chosen.getAsJsonArray("authors");
        String authorName = "";
        if (authorsArray != null && !authorsArray.isEmpty()) {
            JsonObject authorObject = authorsArray.get(0).getAsJsonObject();
            authorName = getString(authorObject, "name");
        }
        List<String> categories = new ArrayList<>();
        JsonArray categoriesArray = chosen.getAsJsonArray("categories");
        if (categoriesArray != null) {
            for (JsonElement element : categoriesArray) {
                JsonObject category = element.getAsJsonObject();
                String name = getString(category, "name");
                if (!name.isBlank()) {
                    categories.add(name);
                }
                if (categories.size() >= 6) {
                    break;
                }
            }
        }
        JsonObject links = chosen.has("links") && chosen.get("links").isJsonObject() ? chosen.getAsJsonObject("links") : null;
        String changelogBody = "";
        if (bestFile != null && chosen.has("id")) {
            int projectId = chosen.get("id").getAsInt();
            int fileId = bestFile.has("id") ? bestFile.get("id").getAsInt() : -1;
            changelogBody = fetchCurseForgeFileChangelog(projectId, fileId, apiKey);
        }
        String summary = getString(chosen, "summary");
        String body = !changelogBody.isBlank() ? changelogBody : summary;
        String providerType = bestFile != null && bestFile.has("releaseType")
                ? mapCurseForgeReleaseType(bestFile.get("releaseType").getAsInt())
                : "";
        return new ModrinthPreviewData(
                "CurseForge",
                getString(chosen, "name"),
                getString(chosen, "slug"),
                summary,
                "mod",
                "CurseForge",
                getString(links, "sourceUrl"),
                getString(links, "issuesUrl"),
                getString(links, "wikiUrl"),
                "",
                bestFile == null ? "" : getString(bestFile, "fileDate"),
                getString(chosen, "dateModified"),
                authorName,
                remoteVersion,
                remoteVersion,
                providerType,
                body,
                String.valueOf(chosen.has("downloadCount") ? chosen.get("downloadCount").getAsLong() : 0L),
                String.valueOf(chosen.has("thumbsUpCount") ? chosen.get("thumbsUpCount").getAsInt() : 0),
                "",
                "",
                matchedLoaders.isEmpty() ? "auto" : String.join(", ", matchedLoaders),
                exactGameVersion,
                exactLoaderMatch,
                latestFiles == null ? 0 : latestFiles.size(),
                matchedLoaders.isEmpty() ? List.of("fabric", "quilt") : matchedLoaders,
                matchedGameVersions.isEmpty() ? List.of(mcVersion) : matchedGameVersions,
                categories,
                installedVersion.equalsIgnoreCase(remoteVersion),
                bestScore < 100,
                updateAvailable,
                true
        );
    }

    private boolean hasCurseForgeMatch(ModContainer mod) throws Exception {
        String apiKey = getCurseForgeApiKey();
        if (apiKey.isBlank()) {
            return false;
        }
        String mcVersion = FabricLoader.getInstance().getModContainer("minecraft")
                .orElseThrow(() -> new RuntimeException("Missing minecraft mod container"))
                .getMetadata().getVersion().getFriendlyString();
        String modId = mod.getMetadata().getId();
        String modName = mod.getMetadata().getName();
        String search = URLEncoder.encode(modId.equalsIgnoreCase(modName) ? modId : modId + " " + modName, StandardCharsets.UTF_8);
        URI searchUri = new URI("https://api.curseforge.com/v1/mods/search?gameId=432&classId=6&searchFilter=" + search + "&gameVersion=" + URLEncoder.encode(mcVersion, StandardCharsets.UTF_8));
        HttpRequest searchRequest = HttpRequest.newBuilder()
                .uri(searchUri)
                .header("x-api-key", apiKey)
                .GET()
                .build();
        HttpResponse<String> searchResponse = HttpClient.newHttpClient().send(searchRequest, HttpResponse.BodyHandlers.ofString());
        JsonObject root = new Gson().fromJson(searchResponse.body(), JsonObject.class);
        JsonArray data = root == null ? null : root.getAsJsonArray("data");
        if (data == null || data.isEmpty()) {
            return false;
        }
        int bestScore = Integer.MIN_VALUE;
        for (JsonElement element : data) {
            JsonObject object = element.getAsJsonObject();
            bestScore = Math.max(bestScore, scoreCurseForgeHit(object, modId, modName));
        }
        return bestScore >= 70;
    }

    private String fetchCurseForgeFileChangelog(int modId, int fileId, String apiKey) {
        if (modId <= 0 || fileId <= 0 || apiKey == null || apiKey.isBlank()) {
            return "";
        }
        try {
            URI changelogUri = new URI("https://api.curseforge.com/v1/mods/" + modId + "/files/" + fileId + "/changelog");
            HttpRequest changelogRequest = HttpRequest.newBuilder()
                    .uri(changelogUri)
                    .header("x-api-key", apiKey)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> changelogResponse = HttpClient.newHttpClient().send(changelogRequest, HttpResponse.BodyHandlers.ofString());
            JsonObject root = new Gson().fromJson(changelogResponse.body(), JsonObject.class);
            return getString(root, "data");
        } catch (Exception ignored) {
            return "";
        }
    }

    private String mapCurseForgeReleaseType(int releaseType) {
        return switch (releaseType) {
            case 1 -> "Release";
            case 2 -> "Beta";
            case 3 -> "Alpha";
            default -> "";
        };
    }

    private String getCurseForgeApiKey() {
        String env = System.getenv("CURSEFORGE_API_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        String property = System.getProperty("koil.curseforge.apiKey", "");
        return property == null ? "" : property;
    }

    private int scoreCurseForgeHit(JsonObject object, String modId, String modName) {
        String slug = getString(object, "slug");
        String name = getString(object, "name");
        int score = 0;
        if (modId.equalsIgnoreCase(slug)) score += 120;
        if (modName.equalsIgnoreCase(name)) score += 100;
        if (name.equalsIgnoreCase(modId)) score += 95;
        if (slug.equalsIgnoreCase(modName.replace(" ", "-"))) score += 85;
        if (name.toLowerCase(Locale.ROOT).contains(modName.toLowerCase(Locale.ROOT))) score += 25;
        if (slug.toLowerCase(Locale.ROOT).contains(modId.toLowerCase(Locale.ROOT))) score += 20;
        return score;
    }

    private List<JsonObject> fetchModrinthHits(String queryText) throws Exception {
        String query = URLEncoder.encode(queryText, StandardCharsets.UTF_8);
        URI searchUri = new URI("https://api.modrinth.com/v2/search?query=" + query + "&limit=12&facets=%5B%5B%22project_type%3Amod%22%5D%5D");
        HttpResponse<String> searchResponse = HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(searchUri).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonObject searchRoot = new Gson().fromJson(searchResponse.body(), JsonObject.class);
        JsonArray hits = searchRoot == null ? null : searchRoot.getAsJsonArray("hits");
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<JsonObject> results = new ArrayList<>();
        Set<String> seenProjects = new HashSet<>();
        for (JsonElement hit : hits) {
            JsonObject object = hit.getAsJsonObject();
            String projectId = getString(object, "project_id");
            if (seenProjects.add(projectId)) {
                results.add(object);
            }
        }
        return results;
    }

    private int scoreSearchHit(JsonObject object, String modId, String modName) {
        String slug = getString(object, "slug");
        String projectId = getString(object, "project_id");
        String title = getString(object, "title");
        int score = 0;
        if (modId.equalsIgnoreCase(slug)) score += 120;
        if (modId.equalsIgnoreCase(projectId)) score += 110;
        if (modName.equalsIgnoreCase(title)) score += 100;
        if (title.equalsIgnoreCase(modId)) score += 95;
        if (slug.equalsIgnoreCase(modName.replace(" ", "-"))) score += 85;
        if (title.toLowerCase(Locale.ROOT).contains(modName.toLowerCase(Locale.ROOT))) score += 25;
        if (slug.toLowerCase(Locale.ROOT).contains(modId.toLowerCase(Locale.ROOT))) score += 20;
        return score;
    }

    private boolean hasCurrentLoader(List<String> loaders) {
        if (loaders == null || loaders.isEmpty()) {
            return false;
        }
        boolean hasQuilt = FabricLoader.getInstance().isModLoaded("quilt_loader");
        String currentLoader = hasQuilt ? "quilt" : "fabric";
        return loaders.stream().anyMatch(loader -> currentLoader.equalsIgnoreCase(loader));
    }

    private String fetchVersionNumberById(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return "";
        }
        try {
            URI uri = new URI("https://api.modrinth.com/v2/version/" + versionId);
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonObject version = new Gson().fromJson(response.body(), JsonObject.class);
            return getString(version, "version_number");
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean isVersionLower(String installedVersion, String remoteVersion) {
        if (installedVersion == null || installedVersion.isBlank() || remoteVersion == null || remoteVersion.isBlank()) {
            return false;
        }
        int[] installed = parseVersionSegments(installedVersion);
        int[] remote = parseVersionSegments(remoteVersion);
        int length = Math.max(installed.length, remote.length);
        for (int i = 0; i < length; i++) {
            int left = i < installed.length ? installed[i] : 0;
            int right = i < remote.length ? remote[i] : 0;
            if (left != right) {
                return left < right;
            }
        }
        return false;
    }

    private int[] parseVersionSegments(String version) {
        String[] rawParts = version.replaceAll("[^0-9.]", "").split("\\.");
        return Arrays.stream(rawParts)
                .filter(part -> !part.isBlank())
                .mapToInt(part -> {
                    try {
                        return Integer.parseInt(part);
                    } catch (NumberFormatException ignored) {
                        return 0;
                    }
                })
                .toArray();
    }

    private String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private File getPhysicalModFile(ModContainer mod) {
        if (mod == null) {
            return null;
        }
        try {
            List<java.nio.file.Path> paths = mod.getOrigin().getPaths();
            if (paths == null || paths.isEmpty()) {
                return null;
            }
            return paths.get(0).toFile();
        } catch (UnsupportedOperationException exception) {
            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static class ModListWidget extends EntryListWidget<ModListWidget.ModEntry> {
        public ModListWidget(MinecraftClient client, int width, int height, int top, int bottom, int entryHeight) {
            super(client, width, height, top, bottom, entryHeight);
        }

        @Override
        public int getRowWidth() {
            return this.width - 4;
        }

        @Override
        public int getRowLeft() {
            return this.left + 2;
        }

        @Override
        protected int getScrollbarPositionX() {
            return this.right - 6;
        }

        @Override
        protected void renderBackground(DrawContext context) {
        }

        public void addGroupHeader(String label, int count, boolean expanded, ModMenuScreen parentScreen) {
            this.addEntry(new GroupHeaderEntry(label, count, expanded, parentScreen));
        }

        public void addModEntry(ModEntry entry) {
            this.addEntry(entry);
        }

        public void addParentModEntry(ParentModEntry entry) {
            this.addEntry(entry);
        }

        public void clearEntries() {
            super.clearEntries();
        }

        @Override
        public void appendNarrations(NarrationMessageBuilder builder) {
        }

        @Environment(EnvType.CLIENT)
        public static class GroupHeaderEntry extends ModEntry {
            private final String label;
            private final int count;
            private final boolean expanded;
            private final ModMenuScreen parentScreen;

            public GroupHeaderEntry(String label, int count, boolean expanded, ModMenuScreen parentScreen) {
                super(null, MinecraftClient.getInstance(), parentScreen);
                this.label = label;
                this.count = count;
                this.expanded = expanded;
                this.parentScreen = parentScreen;
            }

            @Override
            public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
                if (hovered) {
                    context.fill(x + 2, y + 2, x + entryWidth - 10, y + 34, 0x2F5D6674);
                }
                context.fill(x + 9, y + 8, x + 10, y + 27, 0x5A728294);
                context.fill(x + 10, y + 17, x + 18, y + 18, 0x7A8FA2B6);
                context.drawText(MinecraftClient.getInstance().textRenderer, expanded ? "-" : "+", x + 3, y + 11, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
                context.drawText(MinecraftClient.getInstance().textRenderer, label, x + 22, y + 6, new Color(uiColorContentBaseTitleText, true).getRGB(), true);
                context.drawText(MinecraftClient.getInstance().textRenderer, "Group", x + 22, y + 19, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                String countLabel = count + (count == 1 ? " mod" : " mods");
                context.drawText(MinecraftClient.getInstance().textRenderer, countLabel, x + entryWidth - 24 - MinecraftClient.getInstance().textRenderer.getWidth(countLabel), y + 12, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0 && this.parentScreen != null) {
                    UiSoundHelper.playButtonClick();
                    this.parentScreen.toggleInstalledGroup(this.label);
                    return true;
                }
                return false;
            }
        }

        @Environment(EnvType.CLIENT)
        public static class ParentModEntry extends ModEntry {
            private final List<ModEntry> childMods = new ArrayList<>();
            private final String groupKey;
            private final int groupCount;
            private final boolean expanded;

            public ParentModEntry(ModContainer mod, MinecraftClient client, ModMenuScreen parentScreen, String groupKey, int groupCount, boolean expanded) {
                super(mod, client, parentScreen);
                this.groupKey = groupKey;
                this.groupCount = groupCount;
                this.expanded = expanded;
            }

            public void addChildMod(ModContainer childMod) {
                this.childMods.add(new ModEntry(childMod, client, parentScreen, true, this.childMods.size(), this.groupCount));
            }

            public List<ModEntry> getChildMods() {
                return this.childMods;
            }

            @Override
            public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
                super.render(context, index, y, x, entryWidth, entryHeight, mouseX, mouseY, hovered, delta);
                String countLabel = groupCount + " mods";
                context.drawText(client.textRenderer, countLabel, x + entryWidth - 18 - client.textRenderer.getWidth(countLabel), y + 18, 0xB9C7D8, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0 && this.parentScreen != null) {
                    UiSoundHelper.playButtonClick();
                    this.parentScreen.setSelectedMod(this.mod);
                    this.parentScreen.toggleInstalledGroup(this.groupKey);
                    return true;
                }
                return super.mouseClicked(mouseX, mouseY, button);
            }
        }

        @Environment(EnvType.CLIENT)
        public static class ModEntry extends EntryListWidget.Entry<ModEntry> {
            protected final ModContainer mod;
            protected final MinecraftClient client;
            protected final ModMenuScreen parentScreen;
            protected final Identifier iconIdentifier;
            protected final NativeImageBackedTexture iconTexture;
            protected final boolean childRow;
            protected final int childIndex;
            protected final int childCount;

            public ModEntry(ModContainer mod, MinecraftClient client, ModMenuScreen parentScreen) {
                this(mod, client, parentScreen, false, -1, 0);
            }

            public ModEntry(ModContainer mod, MinecraftClient client, ModMenuScreen parentScreen, boolean childRow) {
                this(mod, client, parentScreen, childRow, -1, 0);
            }

            public ModEntry(ModContainer mod, MinecraftClient client, ModMenuScreen parentScreen, boolean childRow, int childIndex, int childCount) {
                this.mod = mod;
                this.client = client;
                this.parentScreen = parentScreen;
                this.childRow = childRow;
                this.childIndex = childIndex;
                this.childCount = childCount;
                if (mod == null || client == null || parentScreen == null) {
                    this.iconIdentifier = null;
                    this.iconTexture = null;
                    return;
                }
                this.iconIdentifier = new Identifier("mod_icon_" + mod.getMetadata().getId());

                this.iconTexture = loadModIcon(mod);
                if (this.iconTexture != null) {
                    client.getTextureManager().registerTexture(iconIdentifier, iconTexture);
                }
            }

            @Override
            public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
                if (mod == null || client == null || parentScreen == null) {
                    return;
                }
                String modName = mod.getMetadata().getName();
                String version = mod.getMetadata().getVersion().getFriendlyString();
                int guideX = x + 11;
                int branchEndX = x + 25;
                int iconX = this.childRow ? x + 31 : x + BrowserLayoutHelper.LIST_ROW_ICON_OFFSET_X;
                int textX = this.childRow
                        ? x + 68
                        : x + BrowserLayoutHelper.LIST_ROW_TEXT_OFFSET_X + (parentScreen.installedGroupMode == InstalledGroupMode.FAMILY ? 0 : 0);

                if (this.childRow) {
                    boolean lastChild = this.childIndex >= this.childCount - 1;
                    int connectorY = y + 18;
                    int verticalTop = y - 2;
                    int verticalBottom = lastChild ? y + 18 : y + 33;
                    if (verticalBottom > verticalTop) {
                        context.fill(guideX, verticalTop, guideX + 1, verticalBottom, 0x4D748291);
                    }
                    if (lastChild) {
                        context.fill(guideX, y + 8, guideX + 1, connectorY, 0x4D748291);
                    }
                    context.fill(guideX, y + 18, branchEndX, y + 19, 0x66748291);
                }

                if (this.iconTexture != null) {
                    client.getTextureManager().bindTexture(iconIdentifier);
                    context.drawTexture(iconIdentifier, iconX, y + BrowserLayoutHelper.LIST_ROW_ICON_OFFSET_Y, 0, 0, BrowserLayoutHelper.LIST_ROW_ICON_SIZE, BrowserLayoutHelper.LIST_ROW_ICON_SIZE, BrowserLayoutHelper.LIST_ROW_ICON_SIZE, BrowserLayoutHelper.LIST_ROW_ICON_SIZE);
                }

                String titleLabel = client.textRenderer.trimToWidth(modName, Math.max(72, entryWidth - (textX - x) - BrowserLayoutHelper.LIST_ROW_RIGHT_PADDING));
                String subLabel = "Version: " + version;
                String trimmedSubLabel = client.textRenderer.trimToWidth(subLabel, Math.max(72, entryWidth - (textX - x) - BrowserLayoutHelper.LIST_ROW_RIGHT_PADDING));
                context.drawText(client.textRenderer, titleLabel, textX, y + BrowserLayoutHelper.LIST_ROW_TITLE_OFFSET_Y, 0xFFFFFF, true);
                context.drawText(client.textRenderer, trimmedSubLabel, textX, y + BrowserLayoutHelper.LIST_ROW_META_OFFSET_Y, this.childRow ? 0xAEBCCB : 0xAAAAAA, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    UiSoundHelper.playButtonClick();
                }
                if (mod == null || parentScreen == null) {
                    return false;
                }
                if (button == 1) {
                    parentScreen.openModActionMenu(mod, mouseX, mouseY);
                    return true;
                }
                parentScreen.setSelectedMod(mod);
                return true;
            }

            private static NativeImageBackedTexture loadModIcon(ModContainer mod) {
                Optional<String> iconPathOptional = mod.getMetadata().getIconPath(32);
                if (iconPathOptional.isPresent()) {
                    String iconPath = iconPathOptional.get();
                    try (InputStream iconStream = mod.getPath(iconPath).toUri().toURL().openStream()) {
                        return loadImage(iconStream.readAllBytes(), iconPath);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                return null;
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (mouseX >= this.previewViewportX && mouseX <= this.previewViewportX + this.previewViewportWidth
                && mouseY >= this.previewViewportY && mouseY <= this.previewViewportY + this.previewViewportHeight
                && this.previewScrollMax > 0) {
            this.previewScrollOffset = Math.max(0, Math.min(this.previewScrollMax, this.previewScrollOffset - (int) amount * 12));
            return true;
        }
        if (this.modListWidget != null && this.modListWidget.isMouseOver(mouseX, mouseY)) {
            this.modListWidget.setScrollAmount(Math.max(0.0D, this.modListWidget.getScrollAmount() - (int) amount * 20.0D));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }
}
