package com.spirit.client.gui.resourcepack;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.client.gui.*;
import com.spirit.client.gui.PopupMenu;
import com.spirit.client.gui.datapack.DatapackScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.screen.pack.ResourcePackOrganizer;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.apache.commons.io.FileUtils;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
public class ResourcePackMenuScreen extends Screen {
    private static final int LIST_OUTER_LEFT = 31;
    private static final int LIST_INNER_LEFT = 37;
    private static final int LIST_OUTER_RIGHT = 351;
    private static final int LIST_INNER_RIGHT = 345;
    private static final int INSTALLED_FILTER_Y = 10;
    private static final int INSTALLED_FILTER_HEIGHT = 20;
    private static final int INSTALLED_FILTER_GAP = 6;
    private static final int PREVIEW_INFO_LABEL_WIDTH = 96;
    private static final int PREVIEW_INFO_ROW_PADDING = 4;

    private enum InstalledSortMode {
        NAME("Name"),
        PACK_ID("Pack ID"),
        STATE("Status"),
        SOURCE("Source"),
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
        REMOTE("Has Remote");

        private final String label;

        InstalledFilterMode(String label) {
            this.label = label;
        }
    }

    private enum InstalledGroupMode {
        NONE("None"),
        STATUS("Status"),
        SOURCE("Source"),
        FAMILY("Family");

        private final String label;

        InstalledGroupMode(String label) {
            this.label = label;
        }
    }

    private enum PreviewSourceMode {
        AUTO("Auto"),
        MODRINTH("Modrinth"),
        LOCAL("Local");

        private final String label;

        PreviewSourceMode(String label) {
            this.label = label;
        }
    }

    private record LocalPackInsight(
            String fileName,
            String filePath,
            String packFormat,
            String supportedFormats,
            String metadataFile,
            int iconCount,
            boolean zipped,
            boolean found,
            long fileSize
    ) {
    }

    private record LocalPackDescriptor(
            String id,
            String description
    ) {
    }

    private record RemotePackPreviewData(
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

    private record PreviewChip(String label, int background) {
    }

    private final PackScreen bridge;
    private final ResourcePackOrganizer organizer;
    private final Path packDirectory;
    private final boolean datapackMode;
    private PackListWidget packListWidget;
    private TextFieldWidget searchField;
    private ButtonWidget downloadButton;
    private ButtonWidget enableDisableButton;
    private ButtonWidget deleteButton;
    private ButtonWidget websiteButton;
    private ButtonWidget reloadButton;
    private ButtonWidget openFolderButton;
    private ResourcePackOrganizer.Pack selectedPack;
    private final Map<String, RemotePackPreviewData> remotePreviewCache = new ConcurrentHashMap<>();
    private final Set<String> remotePreviewLoading = ConcurrentHashMap.newKeySet();
    private final Set<String> expandedInstalledGroups = new HashSet<>();
    private final PopupMenu installedFilterPopup = new PopupMenu();
    private final PopupMenu previewSourcePopup = new PopupMenu();
    private final PopupMenu packActionPopup = new PopupMenu();
    private final PopupMenu folderOpenPopup = new PopupMenu();
    private InstalledSortMode installedSortMode = InstalledSortMode.NAME;
    private InstalledFilterMode installedFilterMode = InstalledFilterMode.ALL;
    private InstalledGroupMode installedGroupMode = InstalledGroupMode.NONE;
    private PreviewSourceMode previewSourceMode = PreviewSourceMode.AUTO;
    private boolean showIconlessPacks = true;
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
    private int previewUpdateChipWidth;
    private final List<PreviewTooltipRegion> previewTooltipRegions = new ArrayList<>();
    private File pendingFolderOpenDirectory;
    private int lastMouseX;
    private int lastMouseY;
    private ResourcePackOrganizer.Pack popupActionPack;
    private boolean organizerCallbackPatched = false;
    private boolean reloadRequired = false;
    private boolean datapackReloading = false;
    private boolean suppressOrganizerReloadCallback = false;
    private Set<String> initialEnabledPackNames = Set.of();
    private String lastClickedPackName = "";
    private long lastPackClickTimeMs;

    public ResourcePackMenuScreen(PackScreen bridge, ResourcePackOrganizer organizer, Path packDirectory, Text title) {
        super(title);
        this.bridge = bridge;
        this.organizer = organizer;
        this.packDirectory = packDirectory;
        this.datapackMode = isDatapackContext(title, packDirectory);
        this.expandedInstalledGroups.add(packPluralSpacedLabel());
    }

    private static boolean isDatapackContext(Text title, Path packDirectory) {
        String titleText = title == null ? "" : title.getString().toLowerCase(Locale.ROOT);
        String directoryText = packDirectory == null ? "" : packDirectory.toString().toLowerCase(Locale.ROOT);
        return titleText.contains("data pack")
                || titleText.contains("datapack")
                || directoryText.contains("datapack")
                || directoryText.contains("data-pack");
    }

    private String packPluralLabel() {
        return this.datapackMode ? "Datapacks" : "Resourcepacks";
    }

    private String packPluralSpacedLabel() {
        return this.datapackMode ? "Data Packs" : "Resource Packs";
    }

    private String packSingularLabel() {
        return this.datapackMode ? "Datapack" : "Resourcepack";
    }

    private String packSingularSpacedLabel() {
        return this.datapackMode ? "Data Pack" : "Resource Pack";
    }

    private String packTypeId() {
        return this.datapackMode ? "datapack" : "resourcepack";
    }

    private String packRemotePath() {
        return this.datapackMode ? "datapack" : "resourcepack";
    }

    private String packRemoteSearchPath() {
        return this.datapackMode ? "datapacks" : "resourcepacks";
    }

    @Override
    protected void init() {
        int x = BrowserLayoutHelper.FOOTER_BUTTON_X;
        int searchWidth = 200;
        int searchX = this.width - 210;
        int rightActionX = BrowserLayoutHelper.FOOTER_RIGHT_ACTION_X;
        int topButtonWidth = BrowserLayoutHelper.FOOTER_TOP_BUTTON_WIDTH;
        int smallButtonWidth = BrowserLayoutHelper.FOOTER_SMALL_BUTTON_WIDTH;

        this.searchField = new TextFieldWidget(this.textRenderer, searchX, 10, searchWidth, 20, Text.literal("Search " + packPluralLabel()));
        this.searchField.setChangedListener(this::onSearchChanged);
        this.searchField.setPlaceholder(Text.literal("Search installed " + packPluralLabel().toLowerCase(Locale.ROOT)));
        this.addSelectableChild(this.searchField);

        this.downloadButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Download " + packPluralLabel()), button -> {
            if (this.client != null) {
                if (this.datapackMode) {
                    this.client.setScreen(new DatapackScreen(this, this.packDirectory.toFile()));
                } else {
                    this.client.setScreen(new ResourcepackScreen(this));
                }
            }
        }).dimensions(BrowserLayoutHelper.footerTopButtonX(0), this.height - 52, topButtonWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.of("Import " + packSingularLabel()), button -> openImportResourcepackChooser()).dimensions(BrowserLayoutHelper.footerTopButtonX(1), this.height - 52, topButtonWidth, 20).build());

        this.websiteButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Website"), button -> openSelectedPackRemotePage()).dimensions(rightActionX, this.height - 52, topButtonWidth, 20).build());

        this.enableDisableButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Disable"), button -> {
            if (this.selectedPack != null) {
                toggleSelectedPack();
            }
        }).dimensions(BrowserLayoutHelper.footerSmallButtonX(0), this.height - 28, smallButtonWidth, 20).build());

        this.deleteButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Delete"), button -> {
            if (this.selectedPack != null) {
                confirmDeleteSelectedPack();
            }
        }).dimensions(BrowserLayoutHelper.footerSmallButtonX(1), this.height - 28, smallButtonWidth, 20).build());

        this.openFolderButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Open Folder"), button -> openFolderPopupAtPointer(this.packDirectory.toFile())).dimensions(rightActionX, this.height - 28, topButtonWidth, 20).tooltip(Tooltip.of(Text.translatable("pack.folderInfo"))).build());

        this.reloadButton = this.addDrawableChild(ButtonWidget.builder(Text.of("Reload"), button -> reloadScreen()).dimensions(BrowserLayoutHelper.footerSmallButtonX(2), this.height - 28, smallButtonWidth, 20).build());
        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, button -> finishAndClose()).dimensions(BrowserLayoutHelper.footerSmallButtonX(3), this.height - 28, smallButtonWidth, 20).build());

        this.packListWidget = new PackListWidget(this.client, LIST_INNER_RIGHT - LIST_INNER_LEFT, this.height, 39, this.height - 57, 36);
        this.packListWidget.setLeftPos(LIST_INNER_LEFT);
        this.addSelectableChild(this.packListWidget);

        ensureOrganizerCallbackPatched();
        refreshOrganizerWithoutReloadMark();
        rebuildInstalledPackList();
        this.initialEnabledPackNames = currentEnabledPackNames();
        packSelected(this.selectedPack != null);
    }

    private void packSelected(boolean buttonsActive) {
        updateReloadRequiredFromOrganizerState();
        boolean hasSelection = buttonsActive && this.selectedPack != null;
        boolean canToggle = hasSelection && (this.selectedPack.canBeDisabled() || this.selectedPack.canBeEnabled());
        boolean canDelete = hasSelection && resolvePackPath(this.selectedPack) != null && !this.selectedPack.isAlwaysEnabled();
        this.enableDisableButton.active = canToggle;
        this.deleteButton.active = canDelete;
        this.websiteButton.active = hasSelection;
        this.reloadButton.active = this.reloadRequired && !this.datapackReloading;
        this.openFolderButton.active = true;
        if (this.selectedPack != null) {
            this.enableDisableButton.setMessage(Text.of(this.selectedPack.isEnabled() ? "Disable" : "Enable"));
        } else {
            this.enableDisableButton.setMessage(Text.of("Disable"));
        }
    }

    private void onSearchChanged(String query) {
        rebuildInstalledPackList();
    }

    private List<ResourcePackOrganizer.Pack> allPacks() {
        List<ResourcePackOrganizer.Pack> packs = new ArrayList<>();
        packs.addAll(this.organizer.getEnabledPacks().toList());
        packs.addAll(this.organizer.getDisabledPacks().toList());
        return packs;
    }

    private void rebuildInstalledPackList() {
        this.packListWidget.clearEntries();
        List<ResourcePackOrganizer.Pack> filteredPacks = allPacks().stream()
                .filter(this::matchesInstalledSearch)
                .filter(this::matchesInstalledFilter)
                .filter(this::matchesInstalledPicturePreference)
                .sorted(installedPackComparator())
                .toList();

        filteredPacks.forEach(pack -> this.packListWidget.addPackEntry(new PackListWidget.PackEntry(pack, this.client, this)));

        if (this.selectedPack == null || filteredPacks.stream().noneMatch(pack -> pack.getName().equals(this.selectedPack.getName()))) {
            this.selectedPack = filteredPacks.isEmpty() ? null : filteredPacks.get(0);
            if (this.selectedPack != null) {
                setSelectedPack(this.selectedPack);
            } else {
                this.packSelected(false);
            }
        } else {
            ResourcePackOrganizer.Pack refreshedSelection = filteredPacks.stream()
                    .filter(pack -> pack.getName().equals(this.selectedPack.getName()))
                    .findFirst()
                    .orElse(null);
            this.selectedPack = refreshedSelection;
            this.packSelected(this.selectedPack != null);
        }
    }

    private boolean matchesInstalledSearch(ResourcePackOrganizer.Pack pack) {
        String query = this.searchField == null ? "" : this.searchField.getText().trim().toLowerCase(Locale.ROOT);
        if (query.isBlank()) {
            return true;
        }
        String name = pack.getName().toLowerCase(Locale.ROOT);
        String displayName = pack.getDisplayName().getString().toLowerCase(Locale.ROOT);
        String description = pack.getDescription().getString().toLowerCase(Locale.ROOT);
        String source = String.valueOf(pack.getSource()).toLowerCase(Locale.ROOT);
        return name.contains(query) || displayName.contains(query) || description.contains(query) || source.contains(query);
    }

    private boolean matchesInstalledFilter(ResourcePackOrganizer.Pack pack) {
        return switch (this.installedFilterMode) {
            case ALL -> true;
            case ENABLED -> pack.isEnabled();
            case DISABLED -> !pack.isEnabled();
            case REMOTE -> {
                RemotePackPreviewData remote = getCachedRemotePreview(pack);
                if (remote != null && remote.found()) {
                    yield true;
                }
                fetchRemotePreviewIfNeeded(pack);
                yield false;
            }
        };
    }

    private boolean matchesInstalledPicturePreference(ResourcePackOrganizer.Pack pack) {
        return this.showIconlessPacks || hasPackPicture(pack);
    }

    private Comparator<ResourcePackOrganizer.Pack> installedPackComparator() {
        Comparator<ResourcePackOrganizer.Pack> base = switch (this.installedSortMode) {
            case NAME -> Comparator.comparing(pack -> pack.getDisplayName().getString().toLowerCase(Locale.ROOT));
            case PACK_ID -> Comparator.comparing(pack -> pack.getName().toLowerCase(Locale.ROOT));
            case STATE -> Comparator.comparing((ResourcePackOrganizer.Pack pack) -> !pack.isEnabled()).thenComparing(pack -> pack.getDisplayName().getString().toLowerCase(Locale.ROOT));
            case SOURCE -> Comparator.comparing((ResourcePackOrganizer.Pack pack) -> String.valueOf(pack.getSource()).toLowerCase(Locale.ROOT)).thenComparing(pack -> pack.getDisplayName().getString().toLowerCase(Locale.ROOT));
            case PICTURE -> Comparator.comparing((ResourcePackOrganizer.Pack pack) -> !hasPackPicture(pack)).thenComparing(pack -> pack.getDisplayName().getString().toLowerCase(Locale.ROOT));
        };
        return this.installedSortMode == InstalledSortMode.PICTURE ? base : Comparator.comparing((ResourcePackOrganizer.Pack pack) -> !hasPackPicture(pack)).thenComparing(base);
    }

    private String groupLabel(ResourcePackOrganizer.Pack pack) {
        return switch (this.installedGroupMode) {
            case STATUS -> pack.isEnabled() ? "Enabled" : "Disabled";
            case SOURCE -> blankFallback(String.valueOf(pack.getSource()), "Unknown Source");
            case FAMILY -> familyLabel(pack);
            case NONE -> "";
        };
    }

    private String familyLabel(ResourcePackOrganizer.Pack pack) {
        String name = pack.getName().toLowerCase(Locale.ROOT);
        String source = String.valueOf(pack.getSource()).toLowerCase(Locale.ROOT);
        if (source.contains("default") || name.contains("vanilla")) {
            return "Default Packs";
        }
        if (name.contains("programmer") || name.contains("faithful") || name.contains("bare") || name.contains("visual")) {
            return "Visual Packs";
        }
        return packPluralSpacedLabel();
    }

    private String familyClusterKey(ResourcePackOrganizer.Pack pack) {
        String label = familyLabel(pack);
        if (packPluralSpacedLabel().equals(label)) {
            return pack.getName();
        }
        return label;
    }

    private ResourcePackOrganizer.Pack chooseRepresentative(List<ResourcePackOrganizer.Pack> packs) {
        return packs.stream()
                .sorted(Comparator.comparing((ResourcePackOrganizer.Pack pack) -> !hasPackPicture(pack)).thenComparing(pack -> pack.getDisplayName().getString().length()))
                .findFirst()
                .orElse(packs.get(0));
    }

    private void toggleInstalledGroup(String label) {
        if (this.expandedInstalledGroups.contains(label)) {
            this.expandedInstalledGroups.remove(label);
        } else {
            this.expandedInstalledGroups.add(label);
        }
        rebuildInstalledPackList();
    }

    private boolean hasPackPicture(ResourcePackOrganizer.Pack pack) {
        return pack != null && pack.getIconId() != null;
    }

    public void setSelectedPack(ResourcePackOrganizer.Pack pack) {
        this.selectedPack = pack;
        this.previewScrollOffset = 0;
        this.previewScrollMax = 0;
        if (pack != null) {
            fetchRemotePreviewIfNeeded(pack);
        }
        this.packSelected(pack != null);
    }

    private void selectPackFromList(ResourcePackOrganizer.Pack pack) {
        if (pack == null) {
            return;
        }
        String packName = pack.getName();
        long now = Util.getMeasuringTimeMs();
        boolean doubleClick = packName.equals(this.lastClickedPackName) && now - this.lastPackClickTimeMs <= 350L;
        setSelectedPack(pack);
        this.lastClickedPackName = doubleClick ? "" : packName;
        this.lastPackClickTimeMs = doubleClick ? 0L : now;
        if (doubleClick) {
            toggleSelectedPack();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        updateReloadRequiredFromOrganizerState();
        if (this.reloadButton != null) {
            this.reloadButton.active = this.reloadRequired && !this.datapackReloading;
        }
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        MinecraftClient client = MinecraftClient.getInstance();
        BrowserLayoutHelper.renderContentBackground(context, client, this.width, this.height);

        context.fill(0, 0, this.width, 43, new Color(uiColorHeader, true).getRGB());
        context.fill(0, 39, this.width, 42, new Color(uiColorHeaderStripe, true).getRGB());
        context.fill(0, this.height - 60, this.width, this.height, new Color(uiColorFooter, true).getRGB());
        context.fill(0, this.height - 60, this.width, this.height - 57, new Color(uiColorFooterStripe, true).getRGB());
        context.fill(LIST_OUTER_LEFT, 39, LIST_OUTER_RIGHT, this.height - 60, new Color(uiColorContentBase, true).getRGB());
        context.fill(LIST_INNER_LEFT, 39, LIST_INNER_LEFT + 2, this.height - 60, new Color(uiColorContentStripeLeft, true).getRGB());
        context.fill(LIST_INNER_RIGHT - 2, 39, LIST_INNER_RIGHT, this.height - 60, new Color(uiColorContentStripeRight, true).getRGB());

        context.getMatrices().push();
        context.getMatrices().scale(1.5F, 1.5F, 1.0F);
        context.drawText(this.textRenderer, packPluralLabel(), 25, 6, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "-= -".replace(" ", ""), 37, 23, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);

        int searchX = this.width - 210;
        int filterX = searchX - INSTALLED_FILTER_GAP - getInstalledFilterWidth(getShowButtonLabel()) - INSTALLED_FILTER_GAP - getInstalledFilterWidth(getSortButtonLabel());
        renderInstalledFilterButton(context, filterX, getSortButtonLabel());
        int showX = filterX + getInstalledFilterWidth(getSortButtonLabel()) + INSTALLED_FILTER_GAP;
        renderInstalledFilterButton(context, showX, getShowButtonLabel());
        this.searchField.render(context, mouseX, mouseY, delta);
        this.installedFilterPopup.render(context, mouseX, mouseY);
        this.previewSourcePopup.render(context, mouseX, mouseY);
        this.packActionPopup.render(context, mouseX, mouseY);
        this.folderOpenPopup.render(context, mouseX, mouseY);

        super.render(context, mouseX, mouseY, delta);
        this.packListWidget.render(context, mouseX, mouseY, delta);

        if (this.selectedPack != null) {
            MarkdownPreviewRenderer.beginInteractiveFrame();
            renderPackDetails(context, this.selectedPack, LIST_OUTER_RIGHT + 7, 43);
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
        if (button == 0 && this.packActionPopup.isOpen()) {
            PopupMenu.MenuEntry selected = this.packActionPopup.click(mouseX, mouseY);
            if (selected != null) {
                UiSoundHelper.playButtonClick();
                applyPackAction(selected.id());
                return true;
            }
            if (!this.packActionPopup.isOpen()) {
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
            int searchX = this.width - 210;
            int sortX = searchX - INSTALLED_FILTER_GAP - getInstalledFilterWidth(getShowButtonLabel()) - INSTALLED_FILTER_GAP - getInstalledFilterWidth(getSortButtonLabel());
            int sortWidth = getInstalledFilterWidth(getSortButtonLabel());
            int showWidth = getInstalledFilterWidth(getShowButtonLabel());

            if (isWithinInstalledFilter(mouseX, mouseY, sortX, INSTALLED_FILTER_Y, sortWidth, INSTALLED_FILTER_HEIGHT)) {
                UiSoundHelper.playButtonClick();
                openInstalledFilterMenu(mouseX, mouseY, buildSortEntries());
                return true;
            }
            int showX = sortX + sortWidth + INSTALLED_FILTER_GAP;
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
                openSelectedPackRemotePage();
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

    private void renderPackDetails(DrawContext context, ResourcePackOrganizer.Pack pack, int x, int y) {
        this.previewTooltipRegions.clear();
        String packName = pack.getDisplayName().getString();
        String packId = pack.getName();
        boolean disabled = !pack.isEnabled();
        LocalPackInsight localInsight = inspectLocalPack(pack);
        RemotePackPreviewData remoteData = getOrFetchRemotePreview(pack);
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

        int overlayY = y + 10;
        Identifier icon = pack.getIconId();
        if (icon != null) {
            context.drawTexture(icon, x + 10, overlayY, 0, 0, 64, 64, 64, 64);
        }

        context.getMatrices().push();
        context.getMatrices().scale(1.5F, 1.5F, 1.0F);
        context.drawText(this.textRenderer, packName, (int) (x / 1.5F) + 54, (int) (overlayY / 1.5F), new Color(uiColorContentBaseTitleText, true).getRGB(), true);
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
                        Text.literal(disabled ? "This local " + packSingularSpacedLabel().toLowerCase(Locale.ROOT) + " is available but not active." : "This local " + packSingularSpacedLabel().toLowerCase(Locale.ROOT) + " is active in the current order.").styled(style -> style.withColor(0xFFE6EDF5))
                ));
        if (remoteData != null && remoteData.found()) {
            chipX = renderDetailChip(context, chipX + 4, detailChipY, remoteData.exactVersion() ? "Exact Match" : "Closest Match", remoteData.exactVersion() ? 0x8A33465C : 0x8A5A4B33,
                    List.of(
                            Text.literal(remoteData.exactVersion() ? "Exact Match" : "Closest Match").styled(style -> style.withColor(remoteData.exactVersion() ? 0xFF8ED4A8 : 0xFFD9C48F)),
                            Text.literal(blankFallback(remoteData.provider(), "Remote")).styled(style -> style.withColor("Modrinth".equalsIgnoreCase(remoteData.provider()) ? 0xFF1BD96A : 0xFF8FC5FF)),
                            Text.literal(remoteData.exactVersion() ? "Remote version data matches this installed " + packSingularSpacedLabel().toLowerCase(Locale.ROOT) + " directly." : "Found the nearest matching remote version data for this " + packSingularSpacedLabel().toLowerCase(Locale.ROOT) + ".").styled(style -> style.withColor(0xFFE6EDF5))
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
                                Text.literal("A compatible remote version appears newer than the local pack version.").styled(style -> style.withColor(0xFFE6EDF5)),
                                Text.literal("Click to open provider page.").styled(style -> style.withColor(0xFF8FC5FF))
                        ));
            }
        }

        context.drawText(this.textRenderer, "Pack ID", x + 80, overlayY + 33, uiColorBasicSubtitleText, false);
        context.drawText(this.textRenderer, fitDetailsText(packId, 188), x + 122, overlayY + 33, 0xFFD8DFE9, false);
        context.drawText(this.textRenderer, "Version", x + 80, overlayY + 44, uiColorBasicSubtitleText, false);
        String localVersion = blankFallback(localInsight.packFormat(), "unknown");
        String remoteVersion = remoteData != null && remoteData.found() ? blankFallback(remoteData.versionNumber(), blankFallback(remoteData.versionTitle(), "unknown")) : "";
        String versionLabel = remoteVersion.isBlank() ? localVersion : localVersion + "  ->  " + remoteVersion;
        this.previewVersionX = x + 122;
        this.previewVersionY = overlayY + 44;
        this.previewVersionWidth = this.textRenderer.getWidth(versionLabel);
        this.previewVersionSplitX = remoteVersion.isBlank() ? -1 : this.previewVersionX + this.textRenderer.getWidth(localVersion + "  ->");
        context.drawText(this.textRenderer, versionLabel, this.previewVersionX, this.previewVersionY, 0xFFE9DFC9, false);
        context.drawText(this.textRenderer, "Author", x + 80, overlayY + 55, uiColorBasicSubtitleText, false);
        String previewAuthor = remoteData != null && remoteData.found() ? blankFallback(remoteData.authorName(), "Unknown") : "Unknown";
        context.drawText(this.textRenderer, fitDetailsText(previewAuthor, 188), x + 122, overlayY + 55, 0xFFD8DFE9, false);

        int tagsY = overlayY + 66;
        int tagX = x + 80;
        if (remoteData != null && remoteData.found() && remoteData.categories() != null) {
            Set<String> seenTags = new LinkedHashSet<>();
            for (String rawCategory : remoteData.categories()) {
                String category = sanitizePreviewTag(rawCategory);
                if (category.isBlank() || isGenericPackTag(category) || !seenTags.add(category.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                int chipWidth = this.textRenderer.getWidth(category) + 10;
                if (tagX + chipWidth > x + panelWidth - 12) {
                    break;
                }
                tagX = renderDetailChip(context, tagX, tagsY, category, getPreviewTagBackground(category), buildPreviewTagTooltip(category)) + 4;
            }
        }

        int detailsViewportY = overlayY + 82;
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
                : pack.getDescription().getString();

        renderSectionRule(context, x + 8, x + panelWidth - 8, contentY, "Remote Data");
        contentY += 14;
        contentHeight += 14;
        if (remoteData != null && remoteData.found()) {
            int nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Provider", blankFallback(remoteData.provider(), "Modrinth"), 0xFF76E6A0);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Sides", blankFallback(remoteData.clientSide(), "unknown") + " client  |  " + blankFallback(remoteData.serverSide(), "unknown") + " server", 0xFFD7E2EF);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Targets", formatTargets(remoteData), 0xFFD6E5DA);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Type", blankFallback(remoteData.projectType(), packTypeId()), 0xFFDCE4EE);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "License", blankFallback(remoteData.licenseName(), "unknown"), 0xFFE4DAC8);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Loader", blankFallback(remoteData.loaderRequirement(), "not required"), 0xFFD6E5DA);
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
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Project", blankFallback(remoteData.projectTitle(), packName) + "  |  " + blankFallback(remoteData.projectSlug(), "unknown"), 0xFFF2F4F7);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Author", blankFallback(remoteData.authorName(), "unknown"), 0xFFD8E7DE);
            contentHeight += nextY - contentY;
            contentY = nextY;
            nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Version", blankFallback(remoteData.versionTitle(), blankFallback(remoteData.versionSummary(), remoteData.versionNumber())), 0xFFDCE4EE);
            contentHeight += nextY - contentY;
            contentY = nextY;
            if (!remoteData.exactVersion()) {
                context.drawText(this.textRenderer, "Closest available version info is being shown for this installed " + packSingularSpacedLabel().toLowerCase(Locale.ROOT) + ".", x + 10, contentY, 0xFFD9C48F, false);
                contentY += this.textRenderer.fontHeight + 4;
                contentHeight += this.textRenderer.fontHeight + 4;
            }
        } else if (this.remotePreviewLoading.contains(pack.getName())) {
            context.drawText(this.textRenderer, "Loading Modrinth metadata...", x + 10, contentY, uiColorContentBaseDescriptionText, false);
            contentY += 16;
            contentHeight += 16;
        } else {
            context.drawText(this.textRenderer, "No Modrinth metadata match was found for this " + packSingularSpacedLabel().toLowerCase(Locale.ROOT) + ".", x + 10, contentY, uiColorContentBaseDescriptionText, false);
            contentY += 16;
            contentHeight += 16;
        }

        renderSectionRule(context, x + 8, x + panelWidth - 8, contentY, "Local Metadata");
        contentY += 14;
        contentHeight += 14;
        int nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Type", packTypeId(), 0xFFD5DDE7);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Environment", "client", 0xFFCCD7E6);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Metadata File", blankFallback(localInsight.metadataFile(), "unknown"), 0xFFD8DFE9);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Pack Format", blankFallback(localInsight.packFormat(), "unknown"), 0xFFE5D9C2);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Supported", blankFallback(localInsight.supportedFormats(), "unknown"), 0xFFD2E0CF);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Icon Files", String.valueOf(localInsight.iconCount()), 0xFFD8DFE9);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Package", localInsight.zipped() ? "zip" : "folder", 0xFFD9D4E5);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Source", blankFallback(String.valueOf(pack.getSource()), "unknown"), 0xFFC9DDD9);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "State", pack.isEnabled() ? "Enabled" : "Disabled", pack.isEnabled() ? 0xFF9BDEAE : 0xFFD48E8E);
        contentHeight += nextY - contentY;
        contentY = nextY;

        contentY += 6;
        contentHeight += 6;
        renderSectionRule(context, x + 8, x + panelWidth - 8, contentY, remoteData != null && remoteData.found() ? "Body" : "Local Description");
        contentY += 14;
        contentHeight += 14;
        int descriptionWidth = Math.max(180, panelWidth - 20);
        for (MarkdownPreviewRenderer.Line line : MarkdownPreviewRenderer.wrap(blankFallback(previewDescription, "none"), this.textRenderer, descriptionWidth)) {
            int lineHeight = MarkdownPreviewRenderer.renderLine(context, this.textRenderer, line, x + 10, contentY);
            contentY += lineHeight;
            contentHeight += lineHeight;
        }

        contentY += 6;
        contentHeight += 6;
        renderSectionRule(context, x + 8, x + panelWidth - 8, contentY, "Local File");
        contentY += 14;
        contentHeight += 14;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "File", blankFallback(localInsight.fileName(), "unknown"), uiColorContentBaseTitleText);
        contentHeight += nextY - contentY;
        contentY = nextY;
        nextY = renderPreviewInfoLine(context, x + 10, contentY, panelWidth, "Size", localInsight.fileSize() <= 0 ? "unknown" : localInsight.fileSize() + " bytes", 0xFFD8DFE9);
        contentHeight += nextY - contentY;
        contentY = nextY;
        for (MarkdownPreviewRenderer.Line line : MarkdownPreviewRenderer.wrap(blankFallback(localInsight.filePath(), this.packDirectory.toString().replace("\\", "/")), this.textRenderer, panelWidth - 20)) {
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
        for (MarkdownPreviewRenderer.Line line : lines) {
            int lineHeight = MarkdownPreviewRenderer.renderLine(context, this.textRenderer, new MarkdownPreviewRenderer.Line(line.rawText(), 0, valueColor, 0, MarkdownPreviewRenderer.Accent.NONE), valueX, currentY);
            currentY += lineHeight;
        }
        int rowBottom = Math.max(y + this.textRenderer.fontHeight + PREVIEW_INFO_ROW_PADDING, currentY + 1);
        context.fill(lineLeft, rowBottom, lineRight, rowBottom + 1, 0x142C3643);
        return rowBottom + PREVIEW_INFO_ROW_PADDING;
    }


    private String sanitizePreviewTag(String rawTag) {
        if (rawTag == null) {
            return "";
        }
        String tag = rawTag.trim();
        if (tag.isEmpty()) {
            return "";
        }
        if (tag.length() > 48) {
            return "";
        }
        String lower = tag.toLowerCase(Locale.ROOT);
        if (lower.equals("unknown") || lower.equals("none") || lower.equals("null") || lower.equals("n/a")) {
            return "";
        }
        if (lower.contains(".class") || lower.contains(".java") || lower.contains(".jar") || lower.contains(".zip")) {
            return "";
        }
        if (lower.matches(".*\\.(class|java|jar|zip|png|jpg|jpeg|gif|webp|json|json5|toml|yml|yaml|txt|md|properties|cfg|conf)$")) {
            return "";
        }
        if (tag.contains("/") || tag.contains("\\") || tag.contains(":") || tag.contains("{") || tag.contains("}") || tag.contains("[") || tag.contains("]")) {
            return "";
        }
        if (tag.matches(".*\\s{2,}.*")) {
            return "";
        }
        if (tag.matches("(?:[A-Za-z_$][\\\\w$]*\\\\.){2,}[A-Za-z_$][\\\\w$]*")) {
            return "";
        }
        if (tag.matches("^[A-Za-z_$][\\w$]*\\.[A-Za-z_$][\\w$]*$") && lower.contains("class")) {
            return "";
        }
        return tag;
    }

    private boolean isGenericPackTag(String tag) {
        String normalized = tag == null ? "" : tag.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ").trim();
        if (normalized.equals("pack")) {
            return true;
        }
        if (this.datapackMode) {
            return normalized.equals("data pack") || normalized.equals("datapack");
        }
        return normalized.equals("resource pack") || normalized.equals("resourcepack");
    }

    private int getPreviewTagBackground(String tag) {
        String lower = tag.toLowerCase(Locale.ROOT);
        if (lower.contains("vanilla") || lower.contains("default")) {
            return 0x8A41513A;
        }
        if (lower.contains("faithful") || lower.contains("classic")) {
            return 0x8A33465C;
        }
        if (lower.contains("dark") || lower.contains("night")) {
            return 0x8A4A3B55;
        }
        if (lower.contains("medieval") || lower.contains("fantasy") || lower.contains("rpg")) {
            return 0x8A5A4B33;
        }
        if (lower.contains("pvp") || lower.contains("utility") || lower.contains("ui")) {
            return 0x8A38543F;
        }
        return 0x8A33465C;
    }

    private int getPreviewTagAccentColor(String tag) {
        String lower = tag.toLowerCase(Locale.ROOT);
        if (lower.contains("vanilla") || lower.contains("default")) {
            return 0xFFB9D49A;
        }
        if (lower.contains("faithful") || lower.contains("classic")) {
            return 0xFF8FC5FF;
        }
        if (lower.contains("dark") || lower.contains("night")) {
            return 0xFFD5B6FF;
        }
        if (lower.contains("medieval") || lower.contains("fantasy") || lower.contains("rpg")) {
            return 0xFFD9C48F;
        }
        if (lower.contains("pvp") || lower.contains("utility") || lower.contains("ui")) {
            return 0xFF9BDEAE;
        }
        return 0xFF8FC5FF;
    }

    private List<Text> buildPreviewTagTooltip(String tag) {
        int accent = getPreviewTagAccentColor(tag);
        return List.of(
                Text.literal("Category").styled(style -> style.withColor(0xFF96A9BC)),
                Text.literal(tag).styled(style -> style.withColor(accent)),
                Text.literal("Remote provider category tag used to describe this resource pack's purpose or style.").styled(style -> style.withColor(0xFFE6EDF5))
        );
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

    private void renderPreviewSourceChip(DrawContext context) {
        String chipLabel = this.selectedPack != null && this.previewSourceMode != PreviewSourceMode.LOCAL ? blankFallback(getOrFetchRemotePreview(this.selectedPack) != null ? getOrFetchRemotePreview(this.selectedPack).provider() : this.previewSourceMode.label, this.previewSourceMode.label) : this.previewSourceMode.label;
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
            if (mouseX >= region.x() && mouseX <= region.x() + region.width() && mouseY >= region.y() && mouseY <= region.y() + region.height()) {
                context.drawTooltip(this.textRenderer, region.lines(), mouseX, mouseY);
                return;
            }
        }
        if (isWithinInstalledFilter(mouseX, mouseY, this.previewSourceChipX, this.previewSourceChipY, this.previewSourceChipWidth, 10)) {
            context.drawTooltip(this.textRenderer, List.of(
                    Text.literal("Source").styled(style -> style.withColor(0xFF96A9BC)),
                    Text.literal(switch (this.previewSourceMode) {
                        case AUTO -> "Best available match";
                        case MODRINTH -> "Remote provider data";
                        case LOCAL -> "Local pack metadata only";
                    }).styled(style -> style.withColor(0xFFE6EDF5))
            ), mouseX, mouseY);
            return;
        }
        if (mouseX >= this.previewVersionX && mouseX <= this.previewVersionX + this.previewVersionWidth && mouseY >= this.previewVersionY && mouseY <= this.previewVersionY + this.textRenderer.fontHeight) {
            List<Text> lines = new ArrayList<>();
            if (this.previewVersionSplitX > 0 && mouseX >= this.previewVersionSplitX) {
                lines.add(Text.literal("Source").styled(style -> style.withColor(0xFF96A9BC)));
                String provider = this.selectedPack != null && getOrFetchRemotePreview(this.selectedPack) != null ? getOrFetchRemotePreview(this.selectedPack).provider() : this.previewSourceMode.label;
                lines.add(Text.literal(provider).styled(style -> style.withColor(0xFFE6EDF5)));
            } else {
                lines.add(Text.literal("Source").styled(style -> style.withColor(0xFF96A9BC)));
                lines.add(Text.literal("Local").styled(style -> style.withColor(0xFFE6EDF5)));
            }
            context.drawTooltip(this.textRenderer, lines, mouseX, mouseY);
            return;
        }
        if (this.previewUpdateChipWidth > 0 && mouseX >= this.previewUpdateChipX && mouseX <= this.previewUpdateChipX + this.previewUpdateChipWidth && mouseY >= this.previewUpdateChipY && mouseY <= this.previewUpdateChipY + 11) {
            context.drawTooltip(this.textRenderer, List.of(
                    Text.literal("Open Project Page").styled(style -> style.withColor(0xFF96A9BC)),
                    Text.literal("Jump to the remote project for this resource pack").styled(style -> style.withColor(0xFF76E6A0))
            ), mouseX, mouseY);
        }
    }

    private String formatTargets(RemotePackPreviewData data) {
        String versionPart = data.gameVersions() == null || data.gameVersions().isEmpty() ? "versions unknown" : String.join(", ", data.gameVersions().subList(0, Math.min(3, data.gameVersions().size())));
        return packTypeId() + "  |  " + versionPart + "  |  " + data.versionCount() + " version" + (data.versionCount() == 1 ? "" : "s");
    }

    private String formatLinks(RemotePackPreviewData data) {
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

    private String formatDates(RemotePackPreviewData data) {
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

    private void openSelectedPackRemotePage() {
        if (this.selectedPack == null) {
            return;
        }
        RemotePackPreviewData data = getOrFetchRemotePreview(this.selectedPack);
        if (data != null && data.found() && data.projectSlug() != null && !data.projectSlug().isBlank()) {
            UiSoundHelper.playButtonClick();
            Util.getOperatingSystem().open("https://modrinth.com/" + packRemotePath() + "/" + data.projectSlug());
            return;
        }
        String query = this.selectedPack.getDisplayName().getString();
        if (query.isBlank()) {
            query = this.selectedPack.getName();
        }
        UiSoundHelper.playButtonClick();
        Util.getOperatingSystem().open("https://modrinth.com/" + packRemoteSearchPath() + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
    }

    private void openImportResourcepackChooser() {
        String chosenPath = TinyFileDialogs.tinyfd_openFileDialog("Import " + packSingularLabel(), this.packDirectory.toAbsolutePath().toString(), null, null, false);
        if (chosenPath == null || chosenPath.isBlank()) {
            return;
        }
        Path source = Path.of(chosenPath);
        try {
            Files.createDirectories(this.packDirectory);
            Path target = this.packDirectory.resolve(source.getFileName());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            refreshOrganizerWithoutReloadMark();
            rebuildInstalledPackList();
            markReloadRequired();
        } catch (IOException ignored) {
        }
    }

    private void deleteSelectedPack() {
        if (this.selectedPack == null) {
            return;
        }
        if (this.selectedPack.isAlwaysEnabled()) {
            return;
        }
        String deletedPackName = this.selectedPack.getName();
        Path selectedPath = resolvePackPath(this.selectedPack);
        boolean deleted = false;
        if (selectedPath != null) {
            if (this.selectedPack.isEnabled() && this.selectedPack.canBeDisabled()) {
                ensureOrganizerCallbackPatched();
                this.selectedPack.disable();
                this.organizer.apply();
            }
            FileUtils.deleteQuietly(selectedPath.toFile());
            deleted = !Files.exists(selectedPath);
        }
        refreshOrganizerWithoutReloadMark();
        if (deleted && this.selectedPack != null && deletedPackName.equals(this.selectedPack.getName())) {
            this.selectedPack = null;
        }
        rebuildInstalledPackList();
        if (deleted) {
            markReloadRequired();
        } else {
            packSelected(this.selectedPack != null);
        }
    }

    private void confirmDeleteSelectedPack() {
        if (this.selectedPack == null || this.selectedPack.isAlwaysEnabled()) {
            return;
        }
        Path selectedPath = resolvePackPath(this.selectedPack);
        if (selectedPath == null) {
            packSelected(this.selectedPack != null);
            return;
        }
        String packName = this.selectedPack.getName();
        if (this.client == null) {
            deleteSelectedPack();
            return;
        }
        this.client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                deleteSelectedPack();
            }
            if (this.client != null) {
                this.client.setScreen(this);
            }
        }, Text.literal("Delete " + packSingularSpacedLabel()), Text.literal("Are you sure you want to delete " + packName + "?"), Text.literal("Delete"), Text.literal("Cancel")));
    }

    private void toggleSelectedPack() {
        if (this.selectedPack == null) {
            return;
        }
        String selectedName = this.selectedPack.getName();
        ensureOrganizerCallbackPatched();
        boolean toggled = false;
        try {
            if (this.selectedPack.isEnabled()) {
                if (!this.selectedPack.canBeDisabled()) {
                    return;
                }
                this.selectedPack.disable();
            } else {
                if (!this.selectedPack.canBeEnabled()) {
                    return;
                }
                this.selectedPack.enable();
            }
            toggled = true;
        } catch (NullPointerException ignored) {
            toggled = forceToggleSelectedPack(this.selectedPack);
        } catch (RuntimeException ignored) {
            toggled = forceToggleSelectedPack(this.selectedPack);
        }
        if (!toggled) {
            refreshOrganizerWithoutReloadMark();
            rebuildInstalledPackList();
            this.packSelected(this.selectedPack != null);
            return;
        }
        if (!this.datapackMode) {
            this.organizer.apply();
        }
        refreshOrganizerWithoutReloadMark();
        rebuildInstalledPackList();
        markReloadRequired();
        ResourcePackOrganizer.Pack refreshedSelection = allPacks().stream()
                .filter(pack -> pack.getName().equals(selectedName))
                .findFirst()
                .orElse(null);
        this.selectedPack = refreshedSelection;
        this.packSelected(this.selectedPack != null);
        if (this.selectedPack != null) {
            setSelectedPack(this.selectedPack);
        }
    }

    private boolean forceToggleSelectedPack(ResourcePackOrganizer.Pack pack) {
        if (pack == null) {
            return false;
        }
        try {
            Object profile = getPackProfile(pack);
            if (profile == null) {
                return false;
            }
            List<?> current = getPackProfileList(pack, "getCurrentList");
            List<?> opposite = getPackProfileList(pack, "getOppositeList");
            if (current == null || opposite == null) {
                return false;
            }
            if (!current.contains(profile)) {
                return false;
            }
            ((List<Object>) current).remove(profile);
            int insertIndex = pack.isEnabled() ? opposite.size() : getManualEnableInsertIndex(opposite);
            ((List<Object>) opposite).add(insertIndex, profile);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Object getPackProfile(ResourcePackOrganizer.Pack pack) throws ReflectiveOperationException {
        Class<?> type = pack.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("profile");
                field.setAccessible(true);
                return field.get(pack);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<?> getPackProfileList(ResourcePackOrganizer.Pack pack, String methodName) throws ReflectiveOperationException {
        Class<?> type = pack.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Method method = type.getDeclaredMethod(methodName);
                method.setAccessible(true);
                Object value = method.invoke(pack);
                return value instanceof List<?> list ? list : null;
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private int getManualEnableInsertIndex(List<?> enabledProfiles) {
        int index = 0;
        for (Object profile : enabledProfiles) {
            if (!isPinnedProfile(profile)) {
                break;
            }
            index++;
        }
        return index;
    }

    private boolean isPinnedProfile(Object profile) {
        if (profile == null) {
            return false;
        }
        try {
            java.lang.reflect.Method method = profile.getClass().getMethod("isPinned");
            Object value = method.invoke(profile);
            return value instanceof Boolean bool && bool;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void ensureOrganizerCallbackPatched() {
        if (this.organizerCallbackPatched) {
            return;
        }
        try {
            Field field = ResourcePackOrganizer.class.getDeclaredField("updateCallback");
            field.setAccessible(true);
            field.set(this.organizer, (Runnable) () -> {
                if (!this.suppressOrganizerReloadCallback) {
                    markReloadRequired();
                }
            });
            this.organizerCallbackPatched = true;
        } catch (Exception ignored) {
        }
    }

    private void refreshOrganizerWithoutReloadMark() {
        this.suppressOrganizerReloadCallback = true;
        try {
            this.organizer.refresh();
        } finally {
            this.suppressOrganizerReloadCallback = false;
        }
    }

    @Override
    public void close() {
        finishAndClose();
    }

    private void reloadScreen() {
        if (!this.reloadRequired || this.client == null) {
            return;
        }
        if (this.datapackMode) {
            reloadDatapacks();
            return;
        }
        this.organizer.apply();
        refreshOrganizerWithoutReloadMark();
        this.remotePreviewCache.clear();
        this.remotePreviewLoading.clear();
        this.previewScrollOffset = 0;
        this.previewScrollMax = 0;
        this.reloadRequired = false;
        this.initialEnabledPackNames = currentEnabledPackNames();
        rebuildInstalledPackList();
        packSelected(this.selectedPack != null);
    }

    private void reloadDatapacks() {
        ResourcePackManager manager = organizerResourcePackManager();
        if (manager == null) {
            this.reloadRequired = true;
            packSelected(this.selectedPack != null);
            return;
        }
        List<String> enabledNames = this.organizer.getEnabledPacks()
                .map(ResourcePackOrganizer.Pack::getName)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.reverse(enabledNames);
        manager.setEnabledProfiles(enabledNames);

        MinecraftServer server = this.client == null ? null : this.client.getServer();
        if (server == null) {
            finishDatapackReload();
            return;
        }
        this.datapackReloading = true;
        this.reloadButton.setMessage(Text.literal("Reloading..."));
        packSelected(this.selectedPack != null);
        server.reloadResources(manager.getEnabledNames()).whenComplete((ignored, failure) -> {
            MinecraftClient minecraft = this.client;
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                this.datapackReloading = false;
                this.reloadButton.setMessage(Text.literal("Reload"));
                if (failure == null) {
                    finishDatapackReload();
                } else {
                    this.reloadRequired = true;
                    packSelected(this.selectedPack != null);
                }
            });
        });
    }

    private void finishDatapackReload() {
        refreshOrganizerWithoutReloadMark();
        this.remotePreviewCache.clear();
        this.remotePreviewLoading.clear();
        this.previewScrollOffset = 0;
        this.previewScrollMax = 0;
        this.reloadRequired = false;
        this.initialEnabledPackNames = currentEnabledPackNames();
        rebuildInstalledPackList();
        packSelected(this.selectedPack != null);
    }

    private ResourcePackManager organizerResourcePackManager() {
        Class<?> type = this.organizer.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (!ResourcePackManager.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(this.organizer);
                    if (value instanceof ResourcePackManager manager) {
                        return manager;
                    }
                } catch (Exception ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private void markReloadRequired() {
        this.reloadRequired = true;
        packSelected(this.selectedPack != null);
    }

    private void updateReloadRequiredFromOrganizerState() {
        if (!this.reloadRequired && !this.initialEnabledPackNames.isEmpty() && !this.initialEnabledPackNames.equals(currentEnabledPackNames())) {
            this.reloadRequired = true;
        }
    }

    private Set<String> currentEnabledPackNames() {
        try {
            return this.organizer.getEnabledPacks()
                    .map(ResourcePackOrganizer.Pack::getName)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private void finishAndClose() {
        if (this.bridge != null) {
            this.bridge.close();
        }
    }

    private String getSortButtonLabel() {
        return "Sort: " + this.installedSortMode.label;
    }

    private String getShowButtonLabel() {
        return "Show: " + this.installedFilterMode.label;
    }

    private String getGroupButtonLabel() {
        return "Group: " + this.installedGroupMode.label;
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

    private void openInstalledFilterMenu(double mouseX, double mouseY, List<PopupMenu.MenuEntry> entries) {
        this.installedFilterPopup.openAtPointer(mouseX, mouseY, this.width, this.height, entries);
    }

    private List<PopupMenu.MenuEntry> buildPreviewSourceEntries() {
        return List.of(
                new PopupMenu.MenuEntry("source:AUTO", "Auto"),
                new PopupMenu.MenuEntry("source:MODRINTH", "Modrinth"),
                new PopupMenu.MenuEntry("source:LOCAL", "Local")
        );
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
        entries.add(new PopupMenu.MenuEntry("show_iconless:" + (this.showIconlessPacks ? "hide" : "show"), this.showIconlessPacks ? "Hide No Image Packs" : "Show No Image Packs"));
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
            this.installedSortMode = InstalledSortMode.valueOf(actionId.substring("sort:".length()));
        } else if (actionId.startsWith("group:")) {
            this.installedGroupMode = InstalledGroupMode.valueOf(actionId.substring("group:".length()));
        } else if (actionId.startsWith("show:")) {
            this.installedFilterMode = InstalledFilterMode.valueOf(actionId.substring("show:".length()));
        } else if (actionId.startsWith("show_iconless:")) {
            this.showIconlessPacks = actionId.endsWith("show");
        }
        rebuildInstalledPackList();
    }

    private void applyPreviewSourceAction(String actionId) {
        if (actionId == null || !actionId.startsWith("source:")) {
            return;
        }
        this.previewSourceMode = PreviewSourceMode.valueOf(actionId.substring("source:".length()));
        if (this.selectedPack != null) {
            this.remotePreviewCache.remove(this.selectedPack.getName());
            fetchRemotePreviewIfNeeded(this.selectedPack);
        }
    }

    private List<PopupMenu.MenuEntry> buildPackActionEntries(ResourcePackOrganizer.Pack pack) {
        return List.of(
                new PopupMenu.MenuEntry("pack_action:folder", "Open " + packPluralLabel() + " Folder"),
                new PopupMenu.MenuEntry("pack_action:copy_id", "Copy Pack ID"),
                new PopupMenu.MenuEntry("pack_action:remote", "Open Provider Page"),
                new PopupMenu.MenuEntry("pack_action:toggle", pack != null && pack.isEnabled() ? "Disable Pack" : "Enable Pack")
        );
    }

    private void openPackActionMenu(ResourcePackOrganizer.Pack pack, double mouseX, double mouseY) {
        this.popupActionPack = pack;
        this.packActionPopup.openAtPointer(mouseX, mouseY, this.width, this.height, buildPackActionEntries(pack));
    }

    private void applyPackAction(String actionId) {
        if (this.client == null || this.popupActionPack == null || actionId == null) {
            return;
        }
        switch (actionId) {
            case "pack_action:folder" -> openFolderPopupAtPointer(this.packDirectory.toFile());
            case "pack_action:copy_id" -> this.client.keyboard.setClipboard(this.popupActionPack.getName());
            case "pack_action:remote" -> {
                this.selectedPack = this.popupActionPack;
                openSelectedPackRemotePage();
            }
            case "pack_action:toggle" -> {
                this.selectedPack = this.popupActionPack;
                toggleSelectedPack();
            }
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

    private RemotePackPreviewData getCachedRemotePreview(ResourcePackOrganizer.Pack pack) {
        if (pack == null) {
            return null;
        }
        return this.remotePreviewCache.get(pack.getName());
    }

    private RemotePackPreviewData getOrFetchRemotePreview(ResourcePackOrganizer.Pack pack) {
        if (pack == null || this.previewSourceMode == PreviewSourceMode.LOCAL) {
            return null;
        }
        RemotePackPreviewData cached = this.remotePreviewCache.get(pack.getName());
        if (cached != null) {
            return cached;
        }
        fetchRemotePreviewIfNeeded(pack);
        return null;
    }

    private void fetchRemotePreviewIfNeeded(ResourcePackOrganizer.Pack pack) {
        if (pack == null || this.previewSourceMode == PreviewSourceMode.LOCAL) {
            return;
        }
        String key = pack.getName();
        if (this.remotePreviewCache.containsKey(key) || !this.remotePreviewLoading.add(key)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                this.remotePreviewCache.put(key, fetchRemotePreview(pack));
            } catch (Exception ignored) {
                this.remotePreviewCache.put(key, new RemotePackPreviewData("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", false, false, 0, List.of(), List.of(), List.of(), false, false, false, false));
            } finally {
                this.remotePreviewLoading.remove(key);
            }
        }, "koil-resourcepack-preview-" + key.replaceAll("[^a-zA-Z0-9._-]", "_"));
        thread.setDaemon(true);
        thread.start();
    }

    private RemotePackPreviewData fetchRemotePreview(ResourcePackOrganizer.Pack pack) throws Exception {
        return fetchModrinthPreview(pack);
    }

    private RemotePackPreviewData fetchModrinthPreview(ResourcePackOrganizer.Pack pack) throws Exception {
        List<JsonObject> hits = new ArrayList<>();
        hits.addAll(fetchModrinthHits(pack.getName()));
        if (!pack.getDisplayName().getString().equalsIgnoreCase(pack.getName())) {
            hits.addAll(fetchModrinthHits(pack.getDisplayName().getString()));
        }
        if (hits.isEmpty()) {
            return new RemotePackPreviewData("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", false, false, 0, List.of(), List.of(), List.of(), false, false, false, false);
        }

        JsonObject chosen = null;
        int bestScore = Integer.MIN_VALUE;
        String packId = pack.getName();
        String packName = pack.getDisplayName().getString();
        for (JsonObject object : hits) {
            int score = scoreSearchHit(object, packId, packName);
            if (score > bestScore) {
                bestScore = score;
                chosen = object;
            }
        }
        boolean approximateProject = bestScore < 100;

        String slug = getString(chosen, "slug");
        URI projectUri = new URI("https://api.modrinth.com/v2/project/" + slug);
        HttpResponse<String> projectResponse = HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(projectUri).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonObject project = new Gson().fromJson(projectResponse.body(), JsonObject.class);

        URI versionUri = new URI("https://api.modrinth.com/v2/project/" + slug + "/version");
        HttpResponse<String> versionResponse = HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(versionUri).GET().build(), HttpResponse.BodyHandlers.ofString());
        JsonArray versions = new Gson().fromJson(versionResponse.body(), JsonArray.class);

        LocalPackInsight localInsight = inspectLocalPack(pack);
        String installedVersion = blankFallback(localInsight.packFormat(), "");
        JsonObject chosenVersion = null;
        boolean exactVersion = false;
        boolean exactGameVersion = false;
        if (versions != null) {
            for (JsonElement element : versions) {
                JsonObject object = element.getAsJsonObject();
                String versionNumber = getString(object, "version_number");
                if (!installedVersion.isBlank() && installedVersion.equalsIgnoreCase(versionNumber)) {
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
                    if (localInsight.supportedFormats() != null && localInsight.supportedFormats().contains(gameVersion)) {
                        exactGameVersion = true;
                    }
                }
            }
        }

        boolean updateAvailable = chosenVersion != null && !installedVersion.isBlank() && isVersionLower(installedVersion, getString(chosenVersion, "version_number"));
        return new RemotePackPreviewData(
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
                getString(project, "team"),
                chosenVersion == null ? "" : getString(chosenVersion, "name"),
                chosenVersion == null ? "" : getString(chosenVersion, "version_number"),
                chosenVersion == null ? "" : getString(chosenVersion, "version_type"),
                getString(project, "body"),
                getString(project == null ? chosen : project, "downloads"),
                getString(project == null ? chosen : project, "followers"),
                getString(project, "client_side"),
                getString(project, "server_side"),
                "not required",
                exactGameVersion,
                true,
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

    private List<JsonObject> fetchModrinthHits(String queryText) throws Exception {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }
        String query = URLEncoder.encode(queryText, StandardCharsets.UTF_8);
        URI searchUri = new URI("https://api.modrinth.com/v2/search?query=" + query + "&limit=12&facets=%5B%5B%22project_type%3A" + packTypeId() + "%22%5D%5D");
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

    private int scoreSearchHit(JsonObject object, String packId, String packName) {
        String slug = getString(object, "slug");
        String projectId = getString(object, "project_id");
        String title = getString(object, "title");
        int score = 0;
        if (packId.equalsIgnoreCase(slug)) score += 120;
        if (packId.equalsIgnoreCase(projectId)) score += 110;
        if (packName.equalsIgnoreCase(title)) score += 100;
        if (title.equalsIgnoreCase(packId)) score += 95;
        if (slug.equalsIgnoreCase(packName.replace(" ", "-"))) score += 85;
        if (title.toLowerCase(Locale.ROOT).contains(packName.toLowerCase(Locale.ROOT))) score += 25;
        if (slug.toLowerCase(Locale.ROOT).contains(packId.toLowerCase(Locale.ROOT))) score += 20;
        return score;
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
        return Arrays.stream(rawParts).filter(part -> !part.isBlank()).mapToInt(part -> {
            try {
                return Integer.parseInt(part);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }).toArray();
    }

    private String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private String blankFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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

    private Path resolvePackPath(ResourcePackOrganizer.Pack pack) {
        if (pack == null) {
            return null;
        }
        Set<String> candidates = packPathCandidates(pack);
        for (String candidate : candidates) {
            Path direct = this.packDirectory.resolve(candidate);
            if (Files.exists(direct)) {
                return direct;
            }
            Path zip = this.packDirectory.resolve(candidate + ".zip");
            if (Files.exists(zip)) {
                return zip;
            }
        }
        try {
            if (Files.exists(this.packDirectory)) {
                try (var stream = Files.list(this.packDirectory)) {
                    Optional<Path> match = stream.filter(path -> pathMatchesPack(path, pack, candidates)).findFirst();
                    if (match.isPresent()) {
                        return match.get();
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private Set<String> packPathCandidates(ResourcePackOrganizer.Pack pack) {
        Set<String> candidates = new LinkedHashSet<>();
        addPackPathCandidate(candidates, pack.getName());
        addPackPathCandidate(candidates, pack.getDisplayName().getString());
        addPackPathCandidate(candidates, pack.getDescription().getString());
        String source = String.valueOf(pack.getSource());
        int slash = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < source.length()) {
            addPackPathCandidate(candidates, source.substring(slash + 1));
        }
        return candidates;
    }

    private void addPackPathCandidate(Set<String> candidates, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String trimmed = value.trim();
        candidates.add(trimmed);
        if (trimmed.endsWith(".zip")) {
            candidates.add(trimmed.substring(0, trimmed.length() - 4));
        }
        String normalized = normalizePackLookup(trimmed);
        if (!normalized.isBlank()) {
            candidates.add(normalized);
        }
    }

    private boolean pathMatchesPack(Path path, ResourcePackOrganizer.Pack pack, Set<String> candidates) {
        String fileName = path.getFileName().toString();
        String baseName = fileName.endsWith(".zip") ? fileName.substring(0, fileName.length() - 4) : fileName;
        String normalizedFile = normalizePackLookup(baseName);
        for (String candidate : candidates) {
            if (fileName.equalsIgnoreCase(candidate) || baseName.equalsIgnoreCase(candidate) || normalizedFile.equals(normalizePackLookup(candidate))) {
                return true;
            }
        }
        LocalPackDescriptor descriptor = readLocalPackDescriptor(path);
        if (descriptor == null) {
            return false;
        }
        String packName = normalizePackLookup(pack.getName());
        String displayName = normalizePackLookup(pack.getDisplayName().getString());
        String description = normalizePackLookup(pack.getDescription().getString());
        return descriptor.description().equals(packName)
                || descriptor.description().equals(displayName)
                || descriptor.description().equals(description)
                || descriptor.id().equals(packName)
                || descriptor.id().equals(displayName);
    }

    private LocalPackDescriptor readLocalPackDescriptor(Path path) {
        try {
            JsonObject root = null;
            if (Files.isDirectory(path)) {
                Path mcmeta = path.resolve("pack.mcmeta");
                if (Files.exists(mcmeta)) {
                    root = new Gson().fromJson(Files.readString(mcmeta), JsonObject.class);
                }
            } else if (path.getFileName().toString().endsWith(".zip")) {
                try (ZipFile zipFile = new ZipFile(path.toFile())) {
                    ZipEntry mcmeta = zipFile.getEntry("pack.mcmeta");
                    if (mcmeta != null) {
                        try (InputStream input = zipFile.getInputStream(mcmeta)) {
                            root = new Gson().fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
                        }
                    }
                }
            }
            JsonObject packObject = root != null && root.has("pack") && root.get("pack").isJsonObject() ? root.getAsJsonObject("pack") : null;
            String description = packObject != null && packObject.has("description") ? normalizePackLookup(packObject.get("description").getAsString()) : "";
            return new LocalPackDescriptor(normalizePackLookup(path.getFileName().toString()), description);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizePackLookup(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("(?i)\\.zip$", "").replaceAll("§.", "").toLowerCase(Locale.ROOT);
        return cleaned.replaceAll("[^a-z0-9]+", "");
    }

    private LocalPackInsight inspectLocalPack(ResourcePackOrganizer.Pack pack) {
        Path path = resolvePackPath(pack);
        String fileName = path == null ? "" : path.getFileName().toString();
        String filePath = path == null ? "" : path.toAbsolutePath().toString().replace("\\", "/");
        long fileSize = 0L;
        boolean zipped = false;
        String metadataFile = "";
        String packFormat = "";
        String supportedFormats = "";
        int iconCount = 0;
        boolean found = false;
        try {
            if (path != null && Files.exists(path)) {
                found = true;
                fileSize = Files.isDirectory(path) ? 0L : Files.size(path);
                zipped = !Files.isDirectory(path);
                if (Files.isDirectory(path)) {
                    Path mcmeta = path.resolve("pack.mcmeta");
                    if (Files.exists(mcmeta)) {
                        metadataFile = "pack.mcmeta";
                        JsonObject root = new Gson().fromJson(Files.readString(mcmeta), JsonObject.class);
                        JsonObject packObject = root != null && root.has("pack") && root.get("pack").isJsonObject() ? root.getAsJsonObject("pack") : null;
                        if (packObject != null) {
                            packFormat = getPackFormatLabel(packObject);
                            supportedFormats = getSupportedFormatsLabel(packObject);
                        }
                    }
                    iconCount = Files.exists(path.resolve("pack.png")) ? 1 : 0;
                } else if (fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    try (ZipFile zipFile = new ZipFile(path.toFile())) {
                        ZipEntry mcmeta = zipFile.getEntry("pack.mcmeta");
                        if (mcmeta != null) {
                            metadataFile = "pack.mcmeta";
                            try (InputStream inputStream = zipFile.getInputStream(mcmeta)) {
                                JsonObject root = new Gson().fromJson(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
                                JsonObject packObject = root != null && root.has("pack") && root.get("pack").isJsonObject() ? root.getAsJsonObject("pack") : null;
                                if (packObject != null) {
                                    packFormat = getPackFormatLabel(packObject);
                                    supportedFormats = getSupportedFormatsLabel(packObject);
                                }
                            }
                        }
                        iconCount = zipFile.getEntry("pack.png") != null ? 1 : 0;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new LocalPackInsight(fileName, filePath, packFormat, supportedFormats, metadataFile, iconCount, zipped, found, fileSize);
    }

    private String getPackFormatLabel(JsonObject packObject) {
        if (packObject == null || !packObject.has("pack_format")) {
            return "";
        }
        try {
            return String.valueOf(packObject.get("pack_format").getAsInt());
        } catch (Exception ignored) {
            return getString(packObject, "pack_format");
        }
    }

    private String getSupportedFormatsLabel(JsonObject packObject) {
        if (packObject == null || !packObject.has("supported_formats")) {
            return "";
        }
        JsonElement element = packObject.get("supported_formats");
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonArray()) {
            List<String> values = new ArrayList<>();
            for (JsonElement entry : element.getAsJsonArray()) {
                values.add(entry.getAsString());
            }
            return String.join(", ", values);
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            String min = obj.has("min_inclusive") ? obj.get("min_inclusive").getAsString() : "";
            String max = obj.has("max_inclusive") ? obj.get("max_inclusive").getAsString() : "";
            return min.isBlank() && max.isBlank() ? obj.toString() : min + (max.isBlank() ? "" : " - " + max);
        }
        return element.toString();
    }

    private static class PackListWidget extends EntryListWidget<PackListWidget.PackEntry> {
        public PackListWidget(MinecraftClient client, int width, int height, int top, int bottom, int entryHeight) {
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

        public void addGroupHeader(String label, int count, boolean expanded, ResourcePackMenuScreen parentScreen) {
            this.addEntry(new GroupHeaderEntry(label, count, expanded, parentScreen));
        }

        public void addPackEntry(PackEntry entry) {
            this.addEntry(entry);
        }

        public void addParentPackEntry(ParentPackEntry entry) {
            this.addEntry(entry);
        }

        public void clearEntries() {
            super.clearEntries();
        }

        @Override
        public void appendNarrations(NarrationMessageBuilder builder) {
        }

        private static class GroupHeaderEntry extends PackEntry {
            private final String label;
            private final int count;
            private final boolean expanded;
            private final ResourcePackMenuScreen parentScreen;

            public GroupHeaderEntry(String label, int count, boolean expanded, ResourcePackMenuScreen parentScreen) {
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
                context.drawText(MinecraftClient.getInstance().textRenderer, this.expanded ? "-" : "+", x + 3, y + 11, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
                context.drawText(MinecraftClient.getInstance().textRenderer, this.label, x + 22, y + 6, new Color(uiColorContentBaseTitleText, true).getRGB(), true);
                context.drawText(MinecraftClient.getInstance().textRenderer, "Group", x + 22, y + 19, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                String countLabel = this.count + (this.count == 1 ? " pack" : " packs");
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

        private static class ParentPackEntry extends PackEntry {
            private final List<PackEntry> childPacks = new ArrayList<>();
            private final String groupKey;
            private final int groupCount;

            public ParentPackEntry(ResourcePackOrganizer.Pack pack, MinecraftClient client, ResourcePackMenuScreen parentScreen, String groupKey, int groupCount, boolean expanded) {
                super(pack, client, parentScreen);
                this.groupKey = groupKey;
                this.groupCount = groupCount;
            }

            public void addChildPack(ResourcePackOrganizer.Pack childPack) {
                this.childPacks.add(new PackEntry(childPack, this.client, this.parentScreen, true, this.childPacks.size(), this.groupCount));
            }

            public List<PackEntry> getChildPacks() {
                return this.childPacks;
            }

            @Override
            public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
                super.render(context, index, y, x, entryWidth, entryHeight, mouseX, mouseY, hovered, delta);
                String countLabel = this.groupCount + " packs";
                context.drawText(this.client.textRenderer, countLabel, x + entryWidth - 18 - this.client.textRenderer.getWidth(countLabel), y + 18, 0xB9C7D8, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0 && this.parentScreen != null) {
                    UiSoundHelper.playButtonClick();
                    this.parentScreen.setSelectedPack(this.pack);
                    this.parentScreen.toggleInstalledGroup(this.groupKey);
                    return true;
                }
                return super.mouseClicked(mouseX, mouseY, button);
            }
        }

        private static class PackEntry extends EntryListWidget.Entry<PackEntry> {
            protected final ResourcePackOrganizer.Pack pack;
            protected final MinecraftClient client;
            protected final ResourcePackMenuScreen parentScreen;
            protected final boolean childRow;
            protected final int childIndex;
            protected final int childCount;

            public PackEntry(ResourcePackOrganizer.Pack pack, MinecraftClient client, ResourcePackMenuScreen parentScreen) {
                this(pack, client, parentScreen, false, -1, 0);
            }

            public PackEntry(ResourcePackOrganizer.Pack pack, MinecraftClient client, ResourcePackMenuScreen parentScreen, boolean childRow, int childIndex, int childCount) {
                this.pack = pack;
                this.client = client;
                this.parentScreen = parentScreen;
                this.childRow = childRow;
                this.childIndex = childIndex;
                this.childCount = childCount;
            }

            @Override
            public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
                if (this.pack == null || this.client == null || this.parentScreen == null) {
                    return;
                }
                int guideX = x + 11;
                int branchEndX = x + 25;
                int iconX = this.childRow ? x + 31 : x + BrowserLayoutHelper.LIST_ROW_ICON_OFFSET_X;
                int textX = this.childRow ? x + 68 : x + BrowserLayoutHelper.LIST_ROW_TEXT_OFFSET_X;
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
                Identifier icon = this.pack.getIconId();
                if (icon != null) {
                    context.drawTexture(icon, iconX, y + BrowserLayoutHelper.LIST_ROW_ICON_OFFSET_Y, 0, 0, BrowserLayoutHelper.LIST_ROW_ICON_SIZE, BrowserLayoutHelper.LIST_ROW_ICON_SIZE, BrowserLayoutHelper.LIST_ROW_ICON_SIZE, BrowserLayoutHelper.LIST_ROW_ICON_SIZE);
                }
                String titleLabel = this.client.textRenderer.trimToWidth(this.pack.getDisplayName().getString(), Math.max(72, entryWidth - (textX - x) - BrowserLayoutHelper.LIST_ROW_RIGHT_PADDING));
                String subLabel = this.client.textRenderer.trimToWidth((this.pack.isEnabled() ? "Enabled" : "Disabled") + "  |  " + this.pack.getName(), Math.max(72, entryWidth - (textX - x) - BrowserLayoutHelper.LIST_ROW_RIGHT_PADDING));
                context.drawText(this.client.textRenderer, titleLabel, textX, y + BrowserLayoutHelper.LIST_ROW_TITLE_OFFSET_Y, 0xFFFFFF, true);
                context.drawText(this.client.textRenderer, subLabel, textX, y + BrowserLayoutHelper.LIST_ROW_META_OFFSET_Y, this.childRow ? 0xAEBCCB : 0xAAAAAA, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (this.pack == null || this.parentScreen == null) {
                    return false;
                }
                if (button == 0) {
                    UiSoundHelper.playButtonClick();
                    this.parentScreen.selectPackFromList(this.pack);
                    return true;
                }
                if (button == 1) {
                    UiSoundHelper.playButtonClick();
                    this.parentScreen.openPackActionMenu(this.pack, mouseX, mouseY);
                    return true;
                }
                return false;
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (mouseX >= this.previewViewportX && mouseX <= this.previewViewportX + this.previewViewportWidth && mouseY >= this.previewViewportY && mouseY <= this.previewViewportY + this.previewViewportHeight && this.previewScrollMax > 0) {
            this.previewScrollOffset = Math.max(0, Math.min(this.previewScrollMax, this.previewScrollOffset - (int) amount * 12));
            return true;
        }
        if (this.packListWidget != null && this.packListWidget.isMouseOver(mouseX, mouseY)) {
            this.packListWidget.setScrollAmount(Math.max(0.0D, this.packListWidget.getScrollAmount() - (int) amount * 20.0D));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }
}
