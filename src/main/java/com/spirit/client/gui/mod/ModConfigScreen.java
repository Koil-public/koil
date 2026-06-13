package com.spirit.client.gui.mod;

import com.google.gson.*;
import com.spirit.Main;
import com.spirit.client.gui.PopupMenu;
import com.spirit.client.gui.PopupMenu.MenuEntry;
import com.spirit.client.gui.ScreenChromeHost;
import com.spirit.client.gui.ScreenActionHelper;
import com.spirit.client.gui.TopBarLayout;
import com.spirit.client.gui.UiSoundHelper;
import com.spirit.client.gui.ide.EditorSyntaxHighlighter;
import com.spirit.client.gui.ide.FIleIconHelper;
import com.spirit.client.gui.ide.FileEditorScreen;
import com.spirit.client.gui.mod.modconfig.ModConfigDocument;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.texture.Sprite;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.spirit.Main.*;
import static com.spirit.koil.api.design.uiColorVal.*;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTexture;

@Environment(EnvType.CLIENT)
public class ModConfigScreen extends Screen {
    private static Predicate<List<String>> RESET_VISIBILITY_PREDICATE = targets -> true;
    private static final int SIDEBAR_WIDTH = 170;
    private static final int WORKSPACE_TOP = 70;
    private static final int STATUS_BAR_HEIGHT = 20;
    private static final DateTimeFormatter SAVE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int ENTRY_CARD_HEIGHT = 36;
    private static final int COMPACT_ENTRY_CARD_HEIGHT = 24;
    private static final int SEARCH_DROPDOWN_MAX_VISIBLE_ROWS = 8;
    private static final int SEARCH_DROPDOWN_HEADER_HEIGHT = 20;
    private static final int SEARCH_DROPDOWN_ROW_HEIGHT = 22;
    private static final int MAX_RECENT_SEARCHES = 8;
    private static final int MAX_SEARCH_DROPDOWN_RESULTS = 20;
    private static final int MAX_COORDINATE_GRID_LINES = 80;
    private static final String TOP_BAR_BACK_LABEL = "<";
    private static final String TOP_BAR_FILTER_LABEL = "Changed";
    private static final String TOP_BAR_OPEN_LABEL = "Open";
    private static final String TOP_BAR_SAVE_LABEL = "Save";
    private static final String TOP_BAR_RESET_LABEL = "Reset";
    private static final String TOP_BAR_RELOAD_LABEL = "Switch";
    private static final int SAVE_SUCCESS_COLOR = new Color(uiColorConfigStatusSaved, true).getRGB();
    private static final int TOOLTIP_ERROR_COLOR = new Color(uiColorConfigTooltipError, true).getRGB();
    private static final int TOOLTIP_LABEL_COLOR = new Color(uiColorConfigTooltipLabel, true).getRGB();
    private static final int TOOLTIP_PRIMARY_COLOR = new Color(uiColorConfigTooltipPrimary, true).getRGB();
    private static final int TOOLTIP_SECONDARY_COLOR = new Color(uiColorConfigTooltipSecondary, true).getRGB();
    private static final int TOOLTIP_IDEA_COLOR = new Color(uiColorConfigTooltipIdea, true).getRGB();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern RGBA_GROUP_PATTERN = Pattern.compile("^(.*?)(?:_)?(red|green|blue|alpha|r|g|b|a)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern XY_GROUP_PATTERN = Pattern.compile("^(.*?)(?:_)?(x|y)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern XYZ_GROUP_PATTERN = Pattern.compile("^(.*?)(?:_)?(x|y|z)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern WH_GROUP_PATTERN = Pattern.compile("^(.*?)(?:_)?(width|height)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MINMAX_GROUP_PATTERN = Pattern.compile("^(.*?)(?:_)?(min|max)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENABLE_DISABLE_PATTERN = Pattern.compile("^(.*?)(enable|enabled|disable|disabled)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EDGE_GROUP_PATTERN = Pattern.compile("^(padding|margin)(top|bottom|left|right)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#?[0-9a-fA-F]{6,8}$|^0x[0-9a-fA-F]{6,8}$");
    private static final Pattern KEYBIND_VALUE_PATTERN = Pattern.compile("^(?:(?:CTRL|CONTROL|SHIFT|ALT|META|SUPER)\\+)+(?:[A-Z0-9_]+|F\\d{1,2})$|^(?:KEY_|GLFW_KEY_|MOUSE_|BUTTON_).+$|^F\\d{1,2}$|^(?:LEFT|RIGHT)?(?:SHIFT|CTRL|ALT)$|^(?:SPACE|ENTER|ESC|ESCAPE|TAB|BACKSPACE|DELETE|INSERT|HOME|END|PAGE_UP|PAGE_DOWN|UP|DOWN|LEFT|RIGHT)$");
    private static final List<String> COLOR_ADJUSTMENT_KEYS = List.of("Visible", "Opacity", "Hue", "Saturation", "Contrast", "Gamma", "Brightness", "ColorTemperature");
    private static final List<String> FEATURE_SUFFIXES = List.of("Enabled", "Enable", "Color", "Scale", "Speed", "Timeout", "Duration", "Mode", "Intensity", "Opacity", "Limit", "Value");
    private static final List<String> RESOLUTION_PRESET_LABELS = List.of("1280x720", "1600x900", "1920x1080", "2560x1440", "3840x2160");
    private static final List<OptionFamily> OPTION_FAMILIES = List.of(
            new OptionFamily(List.of("mode", "quality", "graphics", "render", "preset"), List.of("off", "fast", "fancy", "fabulous", "auto", "simple", "quality")),
            new OptionFamily(List.of("theme", "style", "appearance"), List.of("light", "dark", "auto", "system")),
            new OptionFamily(List.of("activation", "trigger"), List.of("voice", "ptt")),
            new OptionFamily(List.of("ambient", "occlusion"), List.of("auto", "hybrid", "sub_block")),
            new OptionFamily(List.of("profile"), List.of("low", "medium", "high", "ultra")),
            new OptionFamily(List.of("level"), List.of("low", "medium", "high"))
    );
    private final Screen parent;
    private final ModContainer mod;
    private File configFile;
    private final File[] configFiles;
    private ConfigEntryListWidget configEntryListWidget;
    private int currentConfigIndex;
    private TextFieldWidget searchField;
    private String lastSearchQuery = "";
    private ModConfigDocument currentDocument;
    private String savedDocumentSnapshot = "{}";
    private boolean dirty;
    private String statusMessage = "Loaded";
    private int statusColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
    private long statusUntil = 0L;
    private final Set<String> changedPaths = new LinkedHashSet<>();
    private final Set<String> collapsedSections = new LinkedHashSet<>();
    private final List<String> topLevelSections = new ArrayList<>();
    private final Map<String, Integer> sectionScrollTargets = new LinkedHashMap<>();
    private final List<String> recentSearches = new ArrayList<>();
    private final List<SearchDropdownEntry> searchDropdownEntries = new ArrayList<>();
    private final Map<String, int[]> sectionChipBounds = new LinkedHashMap<>();
    private final Map<String, int[]> optionChipBounds = new LinkedHashMap<>();
    private final Map<String, List<String>> sidebarOptionCache = new LinkedHashMap<>();
    private final List<String> rootSidebarOptionCache = new ArrayList<>();
    private final Map<String, String> cachedSidebarValidationLookup = new LinkedHashMap<>();
    private final Map<String, Boolean> changedDescendantCache = new HashMap<>();
    private boolean sidebarValidationCacheDirty = true;
    private boolean changedDescendantCacheDirty = true;
    private boolean sidebarContentHeightCacheDirty = true;
    private int cachedSidebarContentHeight = 0;
    private final PopupMenu sidebarFilePopupMenu = new PopupMenu();
    private String lastSidebarClickedPath = "";
    private long lastSidebarClickTime = 0L;
    private int sidebarScrollOffset = 0;
    private int[] sidebarScrollUpBounds = null;
    private int[] sidebarScrollDownBounds = null;
    private PendingTooltip pendingSidebarTooltip = null;
    private int[] sidebarFileOpenBounds = null;
    private int selectedDropdownIndex = -1;
    private int dropdownScrollOffset = 0;
    private boolean searchDropdownDismissed = false;
    private boolean filterChangedOnly;
    private boolean compactConfigListing;
    private boolean showConfirmPopup;
    private String popupTitle = "";
    private String popupMessage = "";
    private List<String> pendingDeletePaths = List.of();
    private static boolean compactConfigListingCached;
    private static SoundInstance activeRegistryPreviewSound;
    private static Identifier activeRegistryPreviewSoundId;
    private static final List<MenuEntry> CONFIG_FILE_OPEN_MENU = List.of(
            new MenuEntry("editor", "in Editor"),
            new MenuEntry("explorer", "in Explorer"),
            new MenuEntry("parent", "Parent in Explorer"),
            new MenuEntry("system", "with Default Application"),
            new MenuEntry("from_computer", "From Computer")
    );

    public ModConfigScreen(Screen parent, ModContainer mod, File[] configFiles) {
        super(Text.literal("Config"));
        this.parent = parent;
        this.mod = mod;
        this.configFiles = expandConfigFiles(configFiles);
        this.configFile = this.configFiles.length == 0 ? null : this.configFiles[0];
        this.currentConfigIndex = 0;
    }

    private File[] expandConfigFiles(File[] baseFiles) {
        if (baseFiles == null || baseFiles.length == 0) {
            return new File[0];
        }

        LinkedHashSet<File> orderedFiles = new LinkedHashSet<>();
        for (File file : baseFiles) {
            if (file == null) {
                continue;
            }
            orderedFiles.add(file);
            orderedFiles.addAll(loadRectConfigReferences(file));
        }

        return orderedFiles.toArray(new File[0]);
    }

    private List<File> loadRectConfigReferences(File sourceFile) {
        List<File> referencedFiles = new ArrayList<>();
        if (sourceFile == null || !sourceFile.exists() || sourceFile.isDirectory()) {
            return referencedFiles;
        }

        try {
            ModConfigDocument document = ModConfigDocument.load(sourceFile);
            JsonObject root = document.getRoot();
            JsonElement rectConfig = root.has("rectConfig") ? root.get("rectConfig") : root.get("RectConfig");
            if (rectConfig == null || !rectConfig.isJsonArray()) {
                return referencedFiles;
            }

            for (JsonElement element : rectConfig.getAsJsonArray()) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    continue;
                }

                File referenced = resolveReferencedConfigFile(sourceFile, element.getAsString());
                if (referenced.exists() && referenced.isFile()) {
                    referencedFiles.add(referenced);
                }
            }
        } catch (Exception ignored) {
        }

        return referencedFiles;
    }

    private File resolveReferencedConfigFile(File sourceFile, String rawPath) {
        File directFile = new File(rawPath);
        if (directFile.isAbsolute()) {
            return directFile;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (rawPath.startsWith("./") || rawPath.startsWith(".\\")) {
            if (client != null && client.runDirectory != null) {
                File runRelative = new File(client.runDirectory, rawPath.substring(2));
                if (runRelative.exists()) {
                    return runRelative;
                }
            }

            File cwdRelative = new File(rawPath);
            if (cwdRelative.exists()) {
                return cwdRelative;
            }
        }

        File sourceRelative = new File(sourceFile.getParentFile(), rawPath);
        if (sourceRelative.exists()) {
            return sourceRelative;
        }

        if (client != null && client.runDirectory != null) {
            return new File(client.runDirectory, rawPath);
        }

        return directFile;
    }


    @Override
    public void tick() {
        super.tick();
        if (searchField != null) {
            searchField.setX(getSearchFieldX());
            searchField.setY(TopBarLayout.SEARCH_FIELD_Y);
            searchField.setWidth(getSearchFieldWidth());
            searchField.tick();
            if (!searchField.getText().equals(lastSearchQuery)) {
                searchDropdownDismissed = false;
                handleSearchQueryChanged();
            }
        }
        if (configEntryListWidget != null) {
            boolean compactNow = isCompactListingEnabled();
            if (compactNow != compactConfigListing) {
                compactConfigListing = compactNow;
                compactConfigListingCached = compactNow;
                recreateEntryListWidget();
                rebuildEntries();
            }
            configEntryListWidget.updateLayout(getContentAreaX(), getContentAreaWidth());
        }
        refreshSidebarValidationLookup();
        clampSidebarScroll();
        if (configEntryListWidget != null) {
            configEntryListWidget.tickVisibleEntries();
        }
    }

    @Override
    protected void init() {
        super.init();

        searchField = new TextFieldWidget(this.textRenderer, getSearchFieldX(), TopBarLayout.SEARCH_FIELD_Y, getSearchFieldWidth(), TopBarLayout.SEARCH_FIELD_HEIGHT, Text.literal("Search Config"));
        searchField.setPlaceholder(Text.literal("Search config options"));
        searchField.setMaxLength(256);
        this.addDrawableChild(searchField);

        compactConfigListing = isCompactListingEnabled();
        compactConfigListingCached = compactConfigListing;
        recreateEntryListWidget();

        loadConfig();
    }

    @Override
    public void close() {
        assert this.client != null;
        if (dirty) {
            saveCurrentDocument();
        }
        this.client.setScreen(this.parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        pendingSidebarTooltip = null;
        drawShell(context);
        super.render(context, mouseX, mouseY, delta);
        context.enableScissor(0, getWorkspaceTop(), getSidebarWidth(), getWorkspaceBottom());
        drawSectionSidebar(context, mouseX, mouseY);
        context.disableScissor();
        context.enableScissor(getContentAreaX(), getWorkspaceTop(), getContentAreaX() + getContentAreaWidth(), getWorkspaceBottom());
        configEntryListWidget.render(context, mouseX, mouseY, delta);
        context.disableScissor();
        drawSearchDropdown(context, mouseX, mouseY);
        sidebarFilePopupMenu.render(context, mouseX, mouseY);
        configEntryListWidget.renderOverlays(context, mouseX, mouseY, delta);
        renderPendingSidebarTooltip(context);
        drawStatusBar(context);
        if (showConfirmPopup) {
            drawConfirmPopup(context);
        }
        ((ScreenChromeHost) this).koil$renderScreenChromeLate(context, mouseX, mouseY, delta);
    }

    private void drawShell(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        int topBarBackground = withAlpha(uiColorContentBase, 176);
        int topPanelBackground = withAlpha(uiColorContentBase, 196);
        int panelBackground = withAlpha(uiColorContentBase, 124);
        int sidebarWidth = getSidebarWidth();
        int workspaceTop = getWorkspaceTop();
        int workspaceBottom = getWorkspaceBottom();
        KoilScreenBackgrounds.render(context, client, this.width, this.height);
        context.fill(0, 0, this.width, 40, topPanelBackground);
        context.drawText(this.textRenderer, "Version - " + Main.version(), this.width - 100, 10, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.getMatrices().push();
        context.getMatrices().scale(0.5f, 0.5f, 1.0F);
        context.drawText(this.textRenderer, "By: SpiritXIV", (int) ((this.width - 100) / 0.5f), (int) (20 / 0.5f), new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "Koil", 34, 6, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.drawTexture(LOGO_TEXTURE, 10, 5, 0, 0, 22, 22, 22, 22);
        context.getMatrices().push();
        context.getMatrices().scale(0.5F, 0.5F, 1.0F);
        context.drawText(this.textRenderer, "Manager Menu - InDEV", 68, 35, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.fill(0, 0, this.width, this.height, KoilScreenBackgrounds.overlayColor(client));
        context.drawBorder(0, 0, this.width, this.height,  new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, 40, this.width, 68, new Color(uiColorHeader, true).getRGB());
        context.fill(0, 40, this.width, 68, topBarBackground);
        context.drawBorder(0, 40, this.width, 28,  new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, workspaceTop, sidebarWidth, workspaceBottom, panelBackground);
        context.drawBorder(0, workspaceTop, sidebarWidth, workspaceBottom - workspaceTop, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(getContentAreaX(), workspaceTop, this.width, workspaceBottom, withAlpha(uiColorContentBase, 82));
        context.drawBorder(getContentAreaX(), workspaceTop, this.width - getContentAreaX(), workspaceBottom - workspaceTop, new Color(uiColorBackgroundBorder, true).getRGB());

        renderTopBarButton(context, 10, TOP_BAR_BACK_LABEL);
        boolean topBarFilterActive = dirty ? filterChangedOnly : sidebarFilePopupMenu.isOpen();
        renderTopBarButton(context, getTopBarFilterButtonX(), getFilterButtonLabel(), topBarFilterActive);
        sidebarFileOpenBounds = dirty ? null : new int[]{getTopBarFilterButtonX(), TopBarLayout.BUTTON_Y, getTopBarButtonWidth(getFilterButtonLabel()), TopBarLayout.BUTTON_HEIGHT};
        renderTopBarButton(context, getTopBarSaveButtonX(), getSaveButtonLabel());
        renderTopBarButton(context, getTopBarResetButtonX(), getResetButtonLabel());
        renderTopBarButton(context, getTopBarReloadButtonX(), getReloadButtonLabel());
    }

    private void handleConfigFileOpenAction(String actionId) {
        if (configFile == null) {
            return;
        }
        if ("explorer".equals(actionId)) {
            ScreenActionHelper.openInKoilExplorer(configFile.getPath());
            return;
        }
        if ("parent".equals(actionId)) {
            File parentFile = configFile.getParentFile();
            if (parentFile != null) {
                ScreenActionHelper.openInKoilExplorer(parentFile.getPath());
            } else {
                ScreenActionHelper.openInKoilExplorer(configFile.getPath());
            }
            return;
        }
        if ("editor".equals(actionId) && this.client != null) {
            this.client.setScreen(new FileEditorScreen(this, configFile, null));
            return;
        }
        if ("system".equals(actionId)) {
            try {
                Util.getOperatingSystem().open(configFile);
            } catch (Exception ignored) {
                try {
                    Desktop.getDesktop().open(configFile);
                } catch (Exception secondIgnored) {
                }
            }
            return;
        }
        if ("file_path".equals(actionId)) {
            copyConfigPathToClipboard();
            return;
        }
        if ("from_computer".equals(actionId)) {
            openConfigFileChooser();
        }
    }

    private void copyConfigPathToClipboard() {
        if (this.client == null || configFile == null) {
            return;
        }
        this.client.keyboard.setClipboard(configFile.getAbsolutePath());
        statusMessage = "Copied path";
        statusColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
        statusUntil = Util.getMeasuringTimeMs() + 1200L;
    }

    private void openConfigFileChooser() {
        String chosenPath = TinyFileDialogs.tinyfd_openFileDialog(
                "Open Config File",
                configFile != null ? configFile.getAbsolutePath() : new File(".").getAbsolutePath(),
                null,
                null,
                false
        );
        if (chosenPath == null || chosenPath.isBlank()) {
            return;
        }
        openChosenConfigFile(new File(chosenPath));
    }

    private void openChosenConfigFile(File chosenFile) {
        if (chosenFile == null || !chosenFile.exists() || this.client == null) {
            return;
        }
        if (chosenFile.isDirectory()) {
            ScreenActionHelper.openInKoilExplorer(chosenFile.getPath());
            return;
        }
        if (dirty) {
            saveCurrentDocument();
        }
        this.client.setScreen(new ModConfigScreen(this.parent, this.mod, new File[]{chosenFile}));
    }

    private void drawStatusBar(DrawContext context) {
        int barX = getContentAreaX();
        int barY = this.height - STATUS_BAR_HEIGHT;
        int barWidth = this.width - barX - 1;
        long now = Util.getMeasuringTimeMs();
        String stateMessage = now <= statusUntil ? statusMessage : (dirty ? "Unsaved changes" : "Saved");
        int stateTextColor = now <= statusUntil
                ? statusColor
                : (dirty ? new Color(uiColorWarningPromptText, true).getRGB() : new Color(uiColorHeaderSubTitleText, true).getRGB());
        String fileInfo = configFiles.length <= 1
                ? (configFile == null ? "No config" : configFile.getName())
                : (currentConfigIndex + 1) + "/" + configFiles.length + "  " + (configFile == null ? "No config" : configFile.getName());
        ConfigEntry focusedEntry = configEntryListWidget == null ? null : configEntryListWidget.getFocusedEntry();
        String focusInfo = focusedEntry == null || focusedEntry.getPrimaryPath() == null
                ? "Focus none"
                : "Focus " + humanizePath(focusedEntry.getPrimaryPath());

        context.fill(barX, barY, barX + barWidth, this.height, withAlpha(uiColorHeader, 150));
        context.drawBorder(barX, barY, barWidth, STATUS_BAR_HEIGHT, new Color(uiColorBackgroundBorder, true).getRGB());
        int leftPadding = 10;
        int rightPadding = 10;
        String clippedFocusInfo = fitText(focusInfo, Math.max(80, barWidth / 3));
        int focusWidth = this.textRenderer.getWidth(clippedFocusInfo);
        int focusX = barX + barWidth - focusWidth - rightPadding;
        int stateX = barX + Math.max(120, Math.min(200, barWidth / 4));
        int fileMaxWidth = Math.max(80, focusX - stateX - 16);
        String clippedFileInfo = fitText(fileInfo, fileMaxWidth);
        int stateMaxWidth = Math.max(70, focusX - (barX + leftPadding + this.textRenderer.getWidth(clippedFileInfo)) - 24);
        String clippedStateMessage = fitText(stateMessage, stateMaxWidth);
        context.drawText(this.textRenderer, clippedFileInfo, barX + leftPadding, barY + 6, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, clippedStateMessage, stateX, barY + 6, stateTextColor, false);
        context.drawText(this.textRenderer, clippedFocusInfo, focusX, barY + 6, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);

        drawSidebarFooter(context);
    }

    private void drawSidebarFooter(DrawContext context) {
        int footerX = 0;
        int footerY = this.height - STATUS_BAR_HEIGHT;
        int footerWidth = getSidebarWidth();
        int footerHeight = STATUS_BAR_HEIGHT;
        String formatInfo = "Format: " + getConfigSourceLabel();
        context.fill(footerX, footerY, footerX + footerWidth, footerY + footerHeight, withAlpha(uiColorHeader, 150));
        context.drawBorder(footerX, footerY, footerWidth, footerHeight, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawText(this.textRenderer, formatInfo, footerX + 10, footerY + 6, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
    }

    private int getContentAreaX() {
        return getSidebarWidth() + 2;
    }

    private int getContentAreaWidth() {
        return this.width - getContentAreaX();
    }

    private int getWorkspaceTop() {
        return WORKSPACE_TOP;
    }

    private int getWorkspaceBottom() {
        return this.height - STATUS_BAR_HEIGHT;
    }

    private int getSidebarWidth() {
        return Math.max(this.width < 720 ? 120 : 146, Math.min(SIDEBAR_WIDTH, this.width / 3));
    }

    private String getFilterButtonLabel() {
        return dirty ? TOP_BAR_FILTER_LABEL : TOP_BAR_OPEN_LABEL;
    }

    private String getSaveButtonLabel() {
        return TOP_BAR_SAVE_LABEL;
    }

    private String getResetButtonLabel() {
        return TOP_BAR_RESET_LABEL;
    }

    private String getReloadButtonLabel() {
        return TOP_BAR_RELOAD_LABEL;
    }

    private int getTopBarButtonWidth(String label) {
        return getTopBarLayout().buttonWidth(label);
    }

    private int getSearchFieldX() {
        return getTopBarLayout().searchFieldX(TOP_BAR_BACK_LABEL);
    }

    private int getTopBarFilterButtonX() {
        return getTopBarLayout().rightButtonX(getTopBarActionLabels(), 0);
    }

    private int getTopBarReloadButtonX() {
        return getTopBarLayout().rightButtonX(getTopBarActionLabels(), 3);
    }

    private int getTopBarResetButtonX() {
        return getTopBarLayout().rightButtonX(getTopBarActionLabels(), 2);
    }

    private int getTopBarSaveButtonX() {
        return getTopBarLayout().rightButtonX(getTopBarActionLabels(), 1);
    }

    private int getSearchFieldWidth() {
        return getTopBarLayout().searchFieldWidth(TOP_BAR_BACK_LABEL, getTopBarActionLabels(), this.width < 760 ? 132 : 220);
    }

    private TopBarLayout getTopBarLayout() {
        return new TopBarLayout(this.textRenderer, this.width);
    }

    private List<String> getTopBarActionLabels() {
        return List.of(getFilterButtonLabel(), getSaveButtonLabel(), getResetButtonLabel(), getReloadButtonLabel());
    }

    private void renderTopBarButton(DrawContext context, int x, String label) {
        renderTopBarButton(context, x, label, false);
    }

    private void renderTopBarButton(DrawContext context, int x, String label, boolean active) {
        int width = getTopBarButtonWidth(label);
        int background = active ? withAlpha(uiColorHeaderStripe, 188) : withAlpha(uiColorContentBase, 162);
        int border = new Color(uiColorBackgroundBorder, true).getRGB();
        int text = active ? new Color(uiColorContentBaseTitleText, true).getRGB() : new Color(uiColorIDEFileBackText, true).getRGB();
        context.fill(x, TopBarLayout.BUTTON_Y, x + width, TopBarLayout.BUTTON_Y + TopBarLayout.BUTTON_HEIGHT, background);
        context.drawBorder(x, TopBarLayout.BUTTON_Y, width, TopBarLayout.BUTTON_HEIGHT, border);
        int textX = x + Math.max(4, (width - this.textRenderer.getWidth(label)) / 2);
        context.drawText(this.textRenderer, label, textX, TopBarLayout.BUTTON_Y + 7, text, false);
    }

    private void drawSectionSidebar(DrawContext context, int mouseX, int mouseY) {
        clampSidebarScroll();
        int x = 10;
        int viewportTop = getWorkspaceTop() + 12;
        int y = viewportTop - sidebarScrollOffset;
        int width = getSidebarWidth() - 20;
        int bottom = getWorkspaceBottom() - 12;
        Map<String, String> validationLookup = cachedSidebarValidationLookup;
        sectionChipBounds.clear();
        optionChipBounds.clear();
        sidebarScrollUpBounds = null;
        sidebarScrollDownBounds = null;
        PendingTooltip hoveredSidebarTooltip = null;

        int headerIconX = x;
        int combinedTextHeight = (int) Math.ceil((this.textRenderer.fontHeight * 1.3F) + this.textRenderer.fontHeight + this.textRenderer.fontHeight);
        int headerIconSize = Math.max(24, (int) Math.ceil(combinedTextHeight * 1.33F));
        int headerIconY = y + Math.max(0, (combinedTextHeight - headerIconSize) / 2);
        int headerTextX = x + headerIconSize + 10;
        Identifier fileIcon = configFile == null ? null : FIleIconHelper.resolve(configFile.getName());

        if (fileIcon != null) {
            context.drawTexture(fileIcon, headerIconX, headerIconY, 0, 0, headerIconSize, headerIconSize, headerIconSize, headerIconSize);
        }

        context.getMatrices().push();
        context.getMatrices().translate(headerTextX, y, 0.0F);
        context.getMatrices().scale(1.3F, 1.3F, 1.0F);
        context.drawText(this.textRenderer, getSidebarTitle(), 0, 0, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, fitText(getSidebarFileLabel(), Math.max(48, width - 28)), headerTextX, y + 16, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, dirty ? "Unsaved edits" : "Synced", headerTextX, y + 30, dirty ? new Color(uiColorWarningPromptText, true).getRGB() : new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
        y += 64;

        context.drawText(this.textRenderer, "Sections", x, y, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        y += 18;
        String leadingSection = configEntryListWidget == null ? null : configEntryListWidget.getLeadingSection();
        for (String section : getSidebarSectionPaths()) {
            if (!isSidebarSectionVisible(section)) {
                continue;
            }
            int depth = getSidebarSectionDepth(section);
            int sectionX = x + depth * 10;
            int sectionWidth = Math.max(56, width - depth * 10);
            boolean collapsed = collapsedSections.contains(section);
            boolean sectionVisible = y + 18 >= viewportTop - 6 && y <= bottom + 6;
            if (sectionVisible) {
                boolean active = Objects.equals(leadingSection, section);
                boolean hovered = mouseX >= sectionX && mouseX <= sectionX + sectionWidth && mouseY >= y && mouseY <= y + 18;
                int fill = active ? withAlpha(uiColorHeaderStripe, 172) : withAlpha(uiColorHeader, hovered ? 132 : 96);
                context.fill(sectionX, y, sectionX + sectionWidth, y + 18, fill);
                context.drawBorder(sectionX, y, sectionWidth, 18, new Color(uiColorBackgroundBorder, true).getRGB());
                String prefix = collapsed ? "+ " : "- ";
                String sectionLabel = prefix + humanizePath(section);
                String clippedSectionLabel = fitText(sectionLabel, sectionWidth - 20);
                context.drawText(this.textRenderer, clippedSectionLabel, sectionX + 6, y + 5, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
                boolean sectionChanged = hasChangedDescendant(section);
                String sectionValidation = validationLookup.get(section);
                if (sectionChanged) {
                    context.fill(sectionX + 1, y + 16, sectionX + sectionWidth - 1, y + 18, withAlpha(uiColorWarningPromptText, 255));
                }
                if (sectionValidation != null) {
                    int errorTop = sectionChanged ? y + 13 : y + 16;
                    int errorBottom = sectionChanged ? y + 16 : y + 18;
                    int errorLeft = sectionX + 1;
                    int errorRight = sectionX + sectionWidth - 1;
                    context.fill(errorLeft, errorTop, errorRight, errorBottom, new Color(uiColorConfigValidationError, true).getRGB());
                    if (mouseX >= sectionX && mouseX <= sectionX + sectionWidth && mouseY >= errorTop - 2 && mouseY <= errorBottom + 2) {
                        hoveredSidebarTooltip = new PendingTooltip(mouseX, mouseY, buildValidationTooltipLines(sectionValidation));
                    }
                }
                if (!clippedSectionLabel.equals(sectionLabel) && hovered) {
                    hoveredSidebarTooltip = new PendingTooltip(mouseX, mouseY, buildLabelTooltipLines(humanizePath(section)));
                }
                sectionChipBounds.put(section, new int[]{sectionX, y, sectionWidth, 18});
            }
            y += 22;

            if (!collapsed) {
                List<String> optionPaths = getSidebarOptionPaths(section);
                for (String optionPath : optionPaths) {
                    int optionX = sectionX + 10;
                    int optionWidth = Math.max(48, sectionWidth - 10);
                    boolean optionVisible = y + 14 >= viewportTop - 6 && y <= bottom + 6;
                    if (optionVisible) {
                        boolean optionHovered = mouseX >= optionX && mouseX <= optionX + optionWidth && mouseY >= y && mouseY <= y + 14;
                        boolean changed = hasChangedDescendant(optionPath);
                        int dotColor = getSidebarDotColor(optionPath);
                        if (optionHovered) {
                            context.fill(optionX, y, optionX + optionWidth, y + 14, withAlpha(uiColorHeader, 110));
                        }
                        context.fill(optionX + 5, y + 5, optionX + 8, y + 8, dotColor);
                        int optionTextColor = changed
                                ? new Color(uiColorConfigChangedText, true).getRGB()
                                : new Color(uiColorHeaderSubTitleText, true).getRGB();
                        String optionLabel = humanizePath(optionPath);
                        String clippedOptionLabel = fitText(optionLabel, optionWidth - 26);
                        context.drawText(this.textRenderer, clippedOptionLabel, optionX + 12, y + 3, optionTextColor, false);
                        if (changed) {
                            context.fill(optionX + optionWidth - 8, y + 5, optionX + optionWidth - 4, y + 9, withAlpha(uiColorWarningPromptText, 255));
                        }
                        String optionValidation = validationLookup.get(optionPath);
                        if (optionValidation != null) {
                            context.fill(optionX + optionWidth - 14, y + 5, optionX + optionWidth - 10, y + 9, new Color(uiColorConfigValidationError, true).getRGB());
                            if (mouseX >= optionX + optionWidth - 16 && mouseX <= optionX + optionWidth - 8 && mouseY >= y + 3 && mouseY <= y + 11) {
                                hoveredSidebarTooltip = new PendingTooltip(mouseX, mouseY, buildValidationTooltipLines(optionValidation));
                            }
                        }
                        if (!clippedOptionLabel.equals(optionLabel) && optionHovered) {
                            hoveredSidebarTooltip = new PendingTooltip(mouseX, mouseY, buildLabelTooltipLines(optionLabel));
                        }
                        optionChipBounds.put(optionPath, new int[]{optionX, y, optionWidth, 14});
                    }
                    y += 16;
                }
                y += 4;
            }
        }

        for (String optionPath : getRootSidebarOptionPaths()) {
            boolean optionVisible = y + 14 >= viewportTop - 6 && y <= bottom + 6;
            if (optionVisible) {
                boolean optionHovered = mouseX >= x + 10 && mouseX <= x + width && mouseY >= y && mouseY <= y + 14;
                boolean changed = hasChangedDescendant(optionPath);
                int dotColor = getSidebarDotColor(optionPath);
                if (optionHovered) {
                    context.fill(x + 10, y, x + width, y + 14, withAlpha(uiColorHeader, 110));
                }
                context.fill(x + 15, y + 5, x + 18, y + 8, dotColor);
                int optionTextColor = changed
                        ? new Color(uiColorConfigChangedText, true).getRGB()
                        : new Color(uiColorHeaderSubTitleText, true).getRGB();
                String optionLabel = humanizePath(optionPath);
                String clippedOptionLabel = fitText(optionLabel, width - 40);
                context.drawText(this.textRenderer, clippedOptionLabel, x + 22, y + 3, optionTextColor, false);
                if (changed) {
                    context.fill(x + width - 8, y + 5, x + width - 4, y + 9, withAlpha(uiColorWarningPromptText, 255));
                }
                String optionValidation = validationLookup.get(optionPath);
                if (optionValidation != null) {
                    context.fill(x + width - 14, y + 5, x + width - 10, y + 9, new Color(uiColorConfigValidationError, true).getRGB());
                    if (mouseX >= x + width - 16 && mouseX <= x + width - 8 && mouseY >= y + 3 && mouseY <= y + 11) {
                        hoveredSidebarTooltip = new PendingTooltip(mouseX, mouseY, buildValidationTooltipLines(optionValidation));
                    }
                }
                if (!clippedOptionLabel.equals(optionLabel) && optionHovered) {
                    hoveredSidebarTooltip = new PendingTooltip(mouseX, mouseY, buildLabelTooltipLines(optionLabel));
                }
                optionChipBounds.put(optionPath, new int[]{x + 10, y, width - 10, 14});
            }
            y += 16;
        }

        int sidebarTextColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
        if (sidebarScrollOffset > 0) {
            int ellipsisWidth = this.textRenderer.getWidth("...");
            int ellipsisX = x + width - ellipsisWidth;
            int ellipsisY = viewportTop - 10;
            context.drawText(this.textRenderer, "...", ellipsisX, ellipsisY, sidebarTextColor, false);
            sidebarScrollUpBounds = new int[]{ellipsisX - 4, ellipsisY - 3, ellipsisWidth + 8, 12};
        }
        int contentBottom = viewportTop - sidebarScrollOffset + getSidebarContentHeight();
        if (contentBottom > bottom) {
            int ellipsisWidth = this.textRenderer.getWidth("...");
            int ellipsisX = x + width - ellipsisWidth;
            int ellipsisY = bottom - 8;
            context.drawText(this.textRenderer, "...", ellipsisX, ellipsisY, sidebarTextColor, false);
            sidebarScrollDownBounds = new int[]{ellipsisX - 4, ellipsisY - 3, ellipsisWidth + 8, 12};
        }
        pendingSidebarTooltip = hoveredSidebarTooltip;
    }

    private boolean isMouseOverSidebar(double mouseX, double mouseY) {
        int x = 0;
        int width = getSidebarWidth();
        int top = getWorkspaceTop();
        int bottom = getWorkspaceBottom();
        return mouseX >= x && mouseX <= x + width && mouseY >= top && mouseY <= bottom;
    }

    private void renderPendingSidebarTooltip(DrawContext context) {
        if (pendingSidebarTooltip == null || pendingSidebarTooltip.lines().isEmpty()) {
            return;
        }
        context.drawTooltip(this.textRenderer, pendingSidebarTooltip.lines(), Optional.empty(), pendingSidebarTooltip.mouseX(), pendingSidebarTooltip.mouseY());
    }

    private List<Text> buildLabelTooltipLines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(Text.literal(value).setStyle(Style.EMPTY.withColor(TOOLTIP_PRIMARY_COLOR)));
    }

    private static List<Text> buildValidationTooltipLines(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        List<Text> lines = new ArrayList<>();
        String normalized = message.strip();
        String summary = normalized;
        String detail = "";
        int split = normalized.indexOf('\n');
        if (split >= 0) {
            summary = normalized.substring(0, split).strip();
            detail = normalized.substring(split + 1).strip();
        }
        lines.add(
                Text.literal("Error").setStyle(Style.EMPTY.withColor(TOOLTIP_ERROR_COLOR).withBold(true))
                        .append(Text.literal("  ").setStyle(Style.EMPTY.withColor(TOOLTIP_LABEL_COLOR)))
                        .append(Text.literal("Config").setStyle(Style.EMPTY.withColor(TOOLTIP_LABEL_COLOR)))
        );
        lines.add(
                Text.literal("Summary: ").setStyle(Style.EMPTY.withColor(TOOLTIP_LABEL_COLOR))
                        .append(Text.literal(summary).setStyle(Style.EMPTY.withColor(TOOLTIP_PRIMARY_COLOR)))
        );
        if (!detail.isBlank()) {
            lines.add(
                    Text.literal("Detail: ").setStyle(Style.EMPTY.withColor(TOOLTIP_LABEL_COLOR))
                            .append(Text.literal(detail).setStyle(Style.EMPTY.withColor(TOOLTIP_SECONDARY_COLOR)))
            );
        }
        lines.add(
                Text.literal("Idea: ").setStyle(Style.EMPTY.withColor(TOOLTIP_IDEA_COLOR))
                        .append(Text.literal("Fix the highlighted value or reset it.").setStyle(Style.EMPTY.withColor(TOOLTIP_SECONDARY_COLOR)))
        );
        return lines;
    }

    private int getSidebarViewportHeight() {
        return getWorkspaceBottom() - getWorkspaceTop();
    }

    private int getSidebarContentHeight() {
        if (!sidebarContentHeightCacheDirty) {
            return cachedSidebarContentHeight;
        }
        int height = 114;
        for (String section : getSidebarSectionPaths()) {
            if (!isSidebarSectionVisible(section)) {
                continue;
            }
            height += 22;
            if (!collapsedSections.contains(section)) {
                height += getSidebarOptionPaths(section).size() * 16 + 4;
            }
        }
        height += getRootSidebarOptionPaths().size() * 16;
        cachedSidebarContentHeight = height + 12;
        sidebarContentHeightCacheDirty = false;
        return cachedSidebarContentHeight;
    }

    private void clampSidebarScroll() {
        int maxScroll = Math.max(0, getSidebarContentHeight() - getSidebarViewportHeight());
        sidebarScrollOffset = Math.max(0, Math.min(maxScroll, sidebarScrollOffset));
    }

    private void refreshSidebarValidationLookup() {
        if (!sidebarValidationCacheDirty || configEntryListWidget == null) {
            return;
        }
        cachedSidebarValidationLookup.clear();
        for (ConfigEntry entry : configEntryListWidget.children()) {
            String primaryPath = entry.getPrimaryPath();
            String error = entry.getValidationError();
            if (primaryPath == null || error == null || error.isBlank()) {
                continue;
            }
            String path = primaryPath;
            while (path != null && !path.isBlank()) {
                cachedSidebarValidationLookup.putIfAbsent(path, error);
                int dot = path.lastIndexOf('.');
                path = dot > 0 ? path.substring(0, dot) : null;
            }
        }
        sidebarValidationCacheDirty = false;
    }

    private void invalidateSidebarDerivedCaches() {
        sidebarValidationCacheDirty = true;
        changedDescendantCacheDirty = true;
    }

    private Map<String, String> buildSidebarValidationLookup() {
        refreshSidebarValidationLookup();
        return cachedSidebarValidationLookup;
    }

    private List<String> getSidebarOptionPaths(String section) {
        return sidebarOptionCache.getOrDefault(section, List.of());
    }

    private List<String> getSidebarSectionPaths() {
        if (!sectionScrollTargets.isEmpty()) {
            return new ArrayList<>(sectionScrollTargets.keySet());
        }
        return new ArrayList<>(topLevelSections);
    }

    private int getSidebarSectionDepth(String section) {
        if (section == null || section.isBlank()) {
            return 0;
        }
        return Math.max(0, section.split("\\.").length - 1);
    }

    private boolean isSidebarSectionVisible(String section) {
        if (section == null || section.isBlank()) {
            return true;
        }
        int dot = section.lastIndexOf('.');
        while (dot >= 0) {
            String parent = section.substring(0, dot);
            if (collapsedSections.contains(parent)) {
                return false;
            }
            dot = parent.lastIndexOf('.');
        }
        return true;
    }

    private List<String> getRootSidebarOptionPaths() {
        return rootSidebarOptionCache;
    }

    private void rebuildSidebarCaches() {
        sidebarOptionCache.clear();
        rootSidebarOptionCache.clear();
        sidebarContentHeightCacheDirty = true;
        if (configEntryListWidget == null) {
            return;
        }
        for (ConfigEntry entry : configEntryListWidget.children()) {
            String path = entry.getPrimaryPath();
            if (path == null || path.isBlank() || entry instanceof SectionEntry) {
                continue;
            }
            int dot = path.lastIndexOf('.');
            if (dot < 0) {
                rootSidebarOptionCache.add(path);
                continue;
            }
            String parent = path.substring(0, dot);
            String relative = path.substring(dot + 1);
            if (relative.contains(".")) {
                continue;
            }
            sidebarOptionCache.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(path);
        }
    }

    private String getSidebarTitle() {
        if (mod == null) {
            return "Config";
        }
        try {
            String name = mod.getMetadata().getName();
            return name == null || name.isBlank() ? "Config" : name;
        } catch (Exception ignored) {
            return "Config";
        }
    }

    private String getSidebarFileLabel() {
        if (configFile == null) {
            return "No config";
        }
        String path = configFile.getPath().replace('\\', '/');
        int configIndex = path.indexOf("config/");
        if (configIndex >= 0) {
            return path.substring(configIndex);
        }
        return path;
    }

    private void drawSidebarMiniButton(DrawContext context, int x, int y, int width, int height, String label, boolean active) {
        int fill = active ? withAlpha(uiColorHeaderStripe, 180) : withAlpha(uiColorHeader, 120);
        context.fill(x, y, x + width, y + height, fill);
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());
        int textX = x + Math.max(3, (width - this.textRenderer.getWidth(label)) / 2);
        context.drawText(this.textRenderer, label, textX, y + 4, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
    }


    private void drawConfirmPopup(DrawContext context) {
        int popupWidth = 360;
        int popupHeight = 164;
        int popupX = (this.width - popupWidth) / 2;
        int popupY = (this.height - popupHeight) / 2;
        context.fill(popupX, popupY, popupX + popupWidth, popupY + popupHeight, withAlpha(uiColorContentBase, 234));
        context.drawBorder(popupX, popupY, popupWidth, popupHeight, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(popupX, popupY, popupX + popupWidth, popupY + 26, withAlpha(uiColorHeader, 178));
        context.drawText(this.textRenderer, popupTitle, popupX + 10, popupY + 9, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, fitText(popupMessage, popupWidth - 20), popupX + 10, popupY + 52, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        renderPopupButton(context, popupX + 12, popupY + popupHeight - 34, 104, "Cancel", false);
        renderPopupButton(context, popupX + popupWidth - 116, popupY + popupHeight - 34, 104, "Delete", true);
    }

    private void renderPopupButton(DrawContext context, int x, int y, int width, String label, boolean destructive) {
        int fill = destructive ? withAlpha(uiColorWarningPromptText, 142) : withAlpha(uiColorHeader, 122);
        context.fill(x, y, x + width, y + 20, fill);
        context.drawBorder(x, y, width, 20, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawText(this.textRenderer, label, x + Math.max(6, (width - this.textRenderer.getWidth(label)) / 2), y + 6, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
    }

    private String describeEntry(ConfigEntry entry) {
        if (entry instanceof ColorGroupEntry || entry instanceof SingleColorEntry) {
            return "Color tuning";
        }
        if (entry instanceof ColorAdjustmentEntry) {
            return "Color adjustment panel";
        }
        if (entry instanceof RangeEntry) {
            return "Range control";
        }
        if (entry instanceof ResolutionEntry) {
            return "Resolution editor";
        }
        if (entry instanceof FeatureCardEntry) {
            return "Feature card";
        }
        if (entry instanceof SliderEntry) {
            return "Slider control";
        }
        if (entry instanceof NumericPairEntry || entry instanceof SizeEntry || entry instanceof StringCoordinateEntry || entry instanceof BoxEdgeEntry) {
            return "Grouped numeric editor";
        }
        if (entry instanceof BooleanEntry || entry instanceof BooleanPairEntry || entry instanceof TriStateEntry || entry instanceof ToggleValueEntry || entry instanceof BooleanClusterEntry) {
            return "Boolean / mode control";
        }
        if (entry instanceof ListEntry || entry instanceof MappingEntry) {
            return "Structured data editor";
        }
        if (entry instanceof KeybindEntry) {
            return "Key capture";
        }
        return "Value editor";
    }

    private void drawSearchDropdown(DrawContext context, int mouseX, int mouseY) {
        if (!isSearchDropdownVisible()) {
            return;
        }

        SearchDropdownBounds bounds = getSearchDropdownBounds();
        context.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), withAlpha(uiColorContentBase, 236));
        context.drawBorder(bounds.x(), bounds.y(), bounds.width(), bounds.height(), new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(bounds.x(), bounds.y(), bounds.right(), bounds.y() + SEARCH_DROPDOWN_HEADER_HEIGHT, withAlpha(uiColorHeader, 158));
        String header = searchField.getText().isBlank()
                ? (recentSearches.isEmpty() ? "Search config" : "Recent searches")
                : searchDropdownEntries.size() + " result" + (searchDropdownEntries.size() == 1 ? "" : "s");
        context.drawText(this.textRenderer, header, bounds.x() + 8, bounds.y() + 6, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);

        int visibleRows = Math.min(SEARCH_DROPDOWN_MAX_VISIBLE_ROWS, searchDropdownEntries.size());
        for (int row = 0; row < visibleRows; row++) {
            int entryIndex = dropdownScrollOffset + row;
            if (entryIndex >= searchDropdownEntries.size()) {
                break;
            }
            SearchDropdownEntry entry = searchDropdownEntries.get(entryIndex);
            int rowY = bounds.y() + SEARCH_DROPDOWN_HEADER_HEIGHT + row * SEARCH_DROPDOWN_ROW_HEIGHT;
            boolean selected = entryIndex == selectedDropdownIndex;
            boolean hovered = mouseX >= bounds.x() && mouseX <= bounds.right() && mouseY >= rowY && mouseY <= rowY + SEARCH_DROPDOWN_ROW_HEIGHT;
            if (selected || hovered) {
                context.fill(bounds.x() + 2, rowY, bounds.right() - 2, rowY + SEARCH_DROPDOWN_ROW_HEIGHT, selected ? withAlpha(uiColorHeaderStripe, 120) : withAlpha(uiColorHeader, 116));
            }
            context.drawText(this.textRenderer, fitText(entry.primary(), bounds.width() - 14), bounds.x() + 8, rowY + 3, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            context.drawText(this.textRenderer, fitText(entry.secondary(), bounds.width() - 14), bounds.x() + 8, rowY + 13, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        }
    }

    private boolean isTopBarButtonClicked(double mouseX, double mouseY, int x, String label) {
        int width = getTopBarButtonWidth(label);
        return mouseX >= x && mouseX <= x + width && mouseY >= TopBarLayout.BUTTON_Y && mouseY <= TopBarLayout.BUTTON_Y + TopBarLayout.BUTTON_HEIGHT;
    }

    private boolean handleSearchDropdownClick(double mouseX, double mouseY) {
        if (!isSearchDropdownVisible() || !isMouseOverSearchDropdown(mouseX, mouseY)) {
            return false;
        }
        SearchDropdownBounds bounds = getSearchDropdownBounds();
        int row = ((int) mouseY - bounds.y() - SEARCH_DROPDOWN_HEADER_HEIGHT) / SEARCH_DROPDOWN_ROW_HEIGHT;
        int index = dropdownScrollOffset + row;
        if (row >= 0 && index >= 0 && index < searchDropdownEntries.size()) {
            selectedDropdownIndex = index;
            return activateSelectedDropdownEntry();
        }
        return true;
    }

    private boolean isSearchDropdownVisible() {
        return searchField != null && searchField.isFocused() && !searchDropdownDismissed && !searchDropdownEntries.isEmpty();
    }

    private boolean isMouseOverSearchDropdown(double mouseX, double mouseY) {
        SearchDropdownBounds bounds = getSearchDropdownBounds();
        return mouseX >= bounds.x() && mouseX <= bounds.right() && mouseY >= bounds.y() && mouseY <= bounds.bottom();
    }

    private SearchDropdownBounds getSearchDropdownBounds() {
        int rowCount = Math.min(SEARCH_DROPDOWN_MAX_VISIBLE_ROWS, Math.max(1, searchDropdownEntries.size()));
        int height = SEARCH_DROPDOWN_HEADER_HEIGHT + rowCount * SEARCH_DROPDOWN_ROW_HEIGHT + 4;
        return new SearchDropdownBounds(getSearchFieldX(), TopBarLayout.BUTTON_Y + TopBarLayout.BUTTON_HEIGHT, getSearchFieldWidth(), height);
    }

    private boolean isSearchFieldHit(double mouseX, double mouseY) {
        return mouseX >= getSearchFieldX()
                && mouseX <= getSearchFieldX() + getSearchFieldWidth()
                && mouseY >= 44
                && mouseY <= 64;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (showConfirmPopup) {
            return handlePopupClick(mouseX, mouseY);
        }
        if (sidebarFilePopupMenu.isOpen() && !isWithinBounds(sidebarFileOpenBounds, mouseX, mouseY)) {
            MenuEntry selectedEntry = sidebarFilePopupMenu.click(mouseX, mouseY);
            if (selectedEntry != null) {
                handleConfigFileOpenAction(selectedEntry.id());
                if (button == 0) {
                    UiSoundHelper.playButtonClick();
                }
                return true;
            }
        }
        if (configEntryListWidget != null && configEntryListWidget.hasOpenOverlay() && configEntryListWidget.handleOverlayClick(mouseX, mouseY, button)) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            return true;
        }
        if (searchField != null && isSearchFieldHit(mouseX, mouseY)) {
            searchField.setFocused(true);
            searchDropdownDismissed = false;
            return searchField.mouseClicked(mouseX, mouseY, button);
        }
        if (searchField != null && searchField.mouseClicked(mouseX, mouseY, button)) {
            searchDropdownDismissed = false;
            return true;
        }
        if (configEntryListWidget != null && configEntryListWidget.handleOverlayClick(mouseX, mouseY, button)) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            return true;
        }
        if (handleSearchDropdownClick(mouseX, mouseY)) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            return true;
        }
        if (isTopBarButtonClicked(mouseX, mouseY, 10, TOP_BAR_BACK_LABEL)) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            if (configEntryListWidget != null) {
                configEntryListWidget.clearFocus();
            }
            close();
            return true;
        }
        if (isTopBarButtonClicked(mouseX, mouseY, getTopBarFilterButtonX(), getFilterButtonLabel())) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            configEntryListWidget.clearFocus();
            if (dirty) {
                filterChangedOnly = !filterChangedOnly;
                rebuildEntries();
            } else {
                sidebarFilePopupMenu.toggleAtPointer(
                        mouseX,
                        mouseY,
                        this.width,
                        this.height,
                        CONFIG_FILE_OPEN_MENU
                );
            }
            return true;
        }
        if (isTopBarButtonClicked(mouseX, mouseY, getTopBarSaveButtonX(), getSaveButtonLabel())) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            configEntryListWidget.clearFocus();
            saveCurrentDocument();
            return true;
        }
        if (isTopBarButtonClicked(mouseX, mouseY, getTopBarResetButtonX(), getResetButtonLabel())) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            configEntryListWidget.clearFocus();
            resetCurrentDocument();
            return true;
        }
        if (isTopBarButtonClicked(mouseX, mouseY, getTopBarReloadButtonX(), getReloadButtonLabel())) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            configEntryListWidget.clearFocus();
            cycleConfigFiles(1);
            return true;
        }
        if (handleSectionChipClick(mouseX, mouseY, button)) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            return true;
        }
        if (configEntryListWidget.mouseClicked(mouseX, mouseY, button)) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            return true;
        }
        List<String> resetTargets = configEntryListWidget.findResetTargets(mouseX, mouseY);
        if (!resetTargets.isEmpty()) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            configEntryListWidget.clearFocus();
            resetPaths(resetTargets);
            return true;
        }
        List<String> deleteTargets = configEntryListWidget.findDeleteTargets(mouseX, mouseY);
        if (!deleteTargets.isEmpty()) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            configEntryListWidget.clearFocus();
            openDeletePopup(deleteTargets);
            return true;
        }
        if (searchField != null && searchField.isFocused()) {
            searchField.setFocused(false);
            searchDropdownDismissed = true;
        }
        if (configEntryListWidget != null) {
            configEntryListWidget.clearFocus();
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (hasControlDown() && keyCode == GLFW.GLFW_KEY_S) {
            saveCurrentDocument();
            return true;
        }
        if (hasControlDown() && keyCode == GLFW.GLFW_KEY_F && searchField != null) {
            searchField.setFocused(true);
            searchDropdownDismissed = false;
            return true;
        }
        if (searchField != null && searchField.isFocused()) {
            if (searchField.keyPressed(keyCode, scanCode, modifiers)) {
                searchDropdownDismissed = false;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                moveDropdownSelection(1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP) {
                moveDropdownSelection(-1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (!activateSelectedDropdownEntry()) {
                    jumpToSearchQuery();
                }
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && searchField != null && searchField.isFocused()) {
            searchField.setFocused(false);
            searchDropdownDismissed = true;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            if (configEntryListWidget.moveFocus((modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1)) {
                return true;
            }
        }
        if (configEntryListWidget.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN && configEntryListWidget.moveFocus(1)) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP && configEntryListWidget.moveFocus(-1)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (searchField != null && searchField.isFocused()) {
            if (searchField.charTyped(chr, modifiers)) {
                searchDropdownDismissed = false;
                return true;
            }
            return true;
        }
        if (configEntryListWidget.charTyped(chr, modifiers)) {
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (configEntryListWidget != null && configEntryListWidget.hasOpenOverlay()) {
            return configEntryListWidget.handleOverlayScroll(mouseX, mouseY, amount);
        }
        if (isSearchDropdownVisible() && isMouseOverSearchDropdown(mouseX, mouseY)) {
            int maxScroll = Math.max(0, searchDropdownEntries.size() - SEARCH_DROPDOWN_MAX_VISIBLE_ROWS);
            dropdownScrollOffset = Math.max(0, Math.min(maxScroll, dropdownScrollOffset + (amount < 0 ? 1 : -1)));
            return true;
        }
        if (isMouseOverSidebar(mouseX, mouseY)) {
            int maxScroll = Math.max(0, getSidebarContentHeight() - getSidebarViewportHeight());
            sidebarScrollOffset = Math.max(0, Math.min(maxScroll, sidebarScrollOffset + (amount < 0 ? 20 : -20)));
            return true;
        }
        if (configEntryListWidget.mouseScrolled(mouseX, mouseY, amount)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (configEntryListWidget != null && configEntryListWidget.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (configEntryListWidget != null && configEntryListWidget.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    private void cycleConfigFiles(int direction) {
        if (configFiles.length <= 1) {
            return; // No additional files to switch
        }
        if (dirty) {
            saveCurrentDocument();
            if (dirty) {
                return;
            }
        }
        currentConfigIndex = (currentConfigIndex + direction + configFiles.length) % configFiles.length;
        configFile = configFiles[currentConfigIndex];
        loadConfig();
    }

    private void saveCurrentDocument() {
        if (currentDocument == null) {
            return;
        }
        try {
            currentDocument.save();
            savedDocumentSnapshot = GSON.toJson(currentDocument.getRoot());
            dirty = false;
            changedPaths.clear();
            invalidateSidebarDerivedCaches();
            statusMessage = "Saved " + LocalTime.now().format(SAVE_TIME_FORMAT);
            statusColor = SAVE_SUCCESS_COLOR;
            statusUntil = Util.getMeasuringTimeMs() + 2500L;
        } catch (IOException e) {
            statusMessage = "Save failed";
            statusColor = new Color(uiColorWarningPromptText, true).getRGB();
            statusUntil = Util.getMeasuringTimeMs() + 4000L;
            SUBLOGGER.logE("File-Management thread", "Failed to save config: " + e.getMessage(), true, "Failed to save config, See kLogs for details.");
        }
    }

    private void resetCurrentDocument() {
        if (currentDocument == null) {
            return;
        }
        try {
            currentDocument = currentDocument.recreateWithRoot(JsonParser.parseString(savedDocumentSnapshot).getAsJsonObject());
            dirty = false;
            changedPaths.clear();
            statusMessage = "Reset unsaved changes";
            statusColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
            statusUntil = Util.getMeasuringTimeMs() + 1800L;
            rebuildEntries();
        } catch (Exception ignored) {
            reloadCurrentDocument();
        }
    }

    private void reloadCurrentDocument() {
        loadConfig();
        statusMessage = "Reloaded from disk";
        statusColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
        statusUntil = Util.getMeasuringTimeMs() + 1800L;
    }

    private void openDeletePopup(List<String> deleteTargets) {
        pendingDeletePaths = new ArrayList<>(deleteTargets);
        popupTitle = deleteTargets.size() == 1 ? "Delete Config Option" : "Delete Config Group";
        popupMessage = deleteTargets.size() == 1
                ? humanizePath(deleteTargets.get(0)) + "  [" + deleteTargets.get(0) + "]"
                : humanizePath(deleteTargets.get(0)) + " plus " + (deleteTargets.size() - 1) + " linked field(s)";
        showConfirmPopup = true;
    }

    private boolean handlePopupClick(double mouseX, double mouseY) {
        int popupWidth = 360;
        int popupHeight = 164;
        int popupX = (this.width - popupWidth) / 2;
        int popupY = (this.height - popupHeight) / 2;
        int cancelX = popupX + 12;
        int deleteX = popupX + popupWidth - 116;
        int buttonY = popupY + popupHeight - 34;
        if (mouseY >= buttonY && mouseY <= buttonY + 20) {
            if (mouseX >= cancelX && mouseX <= cancelX + 104) {
                showConfirmPopup = false;
                pendingDeletePaths = List.of();
                return true;
            }
            if (mouseX >= deleteX && mouseX <= deleteX + 104) {
                deletePendingPaths();
                return true;
            }
        }
        return true;
    }

    private void deletePendingPaths() {
        if (currentDocument == null || pendingDeletePaths.isEmpty()) {
            showConfirmPopup = false;
            pendingDeletePaths = List.of();
            return;
        }
        for (String path : pendingDeletePaths) {
            currentDocument.removeValue(path);
            changedPaths.add(path);
        }
        invalidateSidebarDerivedCaches();
        dirty = !changedPaths.isEmpty();
        statusMessage = "Removed " + pendingDeletePaths.size() + " option" + (pendingDeletePaths.size() == 1 ? "" : "s");
        statusColor = new Color(uiColorWarningPromptText, true).getRGB();
        statusUntil = Util.getMeasuringTimeMs() + 1800L;
        showConfirmPopup = false;
        pendingDeletePaths = List.of();
        rebuildEntries();
    }

    private void resetPaths(List<String> paths) {
        if (currentDocument == null || paths.isEmpty()) {
            return;
        }
        for (String path : paths) {
            JsonElement savedValue = getValueFromSnapshot(savedDocumentSnapshot, path);
            if (savedValue == null) {
                currentDocument.removeValue(path);
                changedPaths.remove(path);
                continue;
            }
            currentDocument.setValue(path, savedValue.deepCopy());
            changedPaths.remove(path);
        }
        invalidateSidebarDerivedCaches();
        dirty = !changedPaths.isEmpty();
        statusMessage = "Reset " + paths.size() + " value" + (paths.size() == 1 ? "" : "s");
        statusColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
        statusUntil = Util.getMeasuringTimeMs() + 1800L;
        rebuildEntries();
    }

    private boolean handleSectionChipClick(double mouseX, double mouseY, int button) {
        if (isWithinBounds(sidebarScrollUpBounds, mouseX, mouseY)) {
            sidebarScrollBy(-Math.max(36, getSidebarViewportHeight() / 3));
            return true;
        }
        if (isWithinBounds(sidebarScrollDownBounds, mouseX, mouseY)) {
            sidebarScrollBy(Math.max(36, getSidebarViewportHeight() / 3));
            return true;
        }
        for (Map.Entry<String, int[]> entry : optionChipBounds.entrySet()) {
            int[] bounds = entry.getValue();
            if (mouseX >= bounds[0] && mouseX <= bounds[0] + bounds[2] && mouseY >= bounds[1] && mouseY <= bounds[1] + bounds[3]) {
                if (button == 1) {
                    if (openSidebarInlineEditor(entry.getKey(), mouseX, mouseY)) {
                        lastSidebarClickedPath = "";
                        lastSidebarClickTime = 0L;
                        return true;
                    }
                    return jumpToPath(entry.getKey());
                }
                if (button == 2) {
                    resetPaths(List.of(entry.getKey()));
                    lastSidebarClickedPath = "";
                    lastSidebarClickTime = 0L;
                    return true;
                }
                if (button == 0) {
                    long now = Util.getMeasuringTimeMs();
                    if (Objects.equals(lastSidebarClickedPath, entry.getKey()) && now - lastSidebarClickTime <= 500) {
                        if (openSidebarInlineEditor(entry.getKey(), mouseX, mouseY)
                                || toggleSidebarBoolean(entry.getKey())) {
                            lastSidebarClickedPath = "";
                            lastSidebarClickTime = 0L;
                            return true;
                        }
                    }
                    lastSidebarClickedPath = entry.getKey();
                    lastSidebarClickTime = now;
                }
                return jumpToPath(entry.getKey());
            }
        }
        for (Map.Entry<String, int[]> entry : sectionChipBounds.entrySet()) {
            int[] bounds = entry.getValue();
            if (mouseX >= bounds[0] && mouseX <= bounds[0] + bounds[2] && mouseY >= bounds[1] && mouseY <= bounds[1] + bounds[3]) {
                toggleSectionCollapsed(entry.getKey());
                return true;
            }
        }
        return false;
    }

    private void sidebarScrollBy(int delta) {
        int maxScroll = Math.max(0, getSidebarContentHeight() - getSidebarViewportHeight());
        sidebarScrollOffset = Math.max(0, Math.min(maxScroll, sidebarScrollOffset + delta));
    }

    private void recreateEntryListWidget() {
        configEntryListWidget = new ConfigEntryListWidget(this.client, this.width, this.height, getWorkspaceTop(), getWorkspaceBottom(), getListEntryCardHeight());
        configEntryListWidget.updateLayout(getContentAreaX(), getContentAreaWidth());
    }

    private int getListEntryCardHeight() {
        return compactConfigListing ? COMPACT_ENTRY_CARD_HEIGHT : ENTRY_CARD_HEIGHT;
    }

    private boolean isCompactListingEnabled() {
        if (configFile != null && configFile.getPath().replace("\\", "/").endsWith("koil/sys/config.json") && currentDocument != null) {
            JsonElement element = currentDocument.getRoot().get("compactConfigListing");
            if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
                return element.getAsBoolean();
            }
        }
        try {
            JsonElement element = JSONFileEditor.getValueFromJson("./koil/sys/config.json", "compactConfigListing");
            if (element == null) {
                JSONFileEditor.updateValueInJson("./koil/sys/config.json", "compactConfigListing", new JsonPrimitive(false));
                return false;
            }
            return element.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isCompactConfigListingEnabledStatic() {
        return compactConfigListingCached;
    }

    private boolean isWithinBounds(int[] bounds, double mouseX, double mouseY) {
        return bounds != null
                && mouseX >= bounds[0]
                && mouseX <= bounds[0] + bounds[2]
                && mouseY >= bounds[1]
                && mouseY <= bounds[1] + bounds[3];
    }

    private Boolean getSidebarBooleanState(String path) {
        if (currentDocument == null || path == null || path.isBlank()) {
            return null;
        }
        JsonElement element = getValueAtPath(currentDocument.getRoot(), path);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        return element.getAsBoolean();
    }

    private boolean openSidebarInlineEditor(String path, double mouseX, double mouseY) {
        if (configEntryListWidget == null) {
            return false;
        }
        for (int i = 0; i < configEntryListWidget.children().size(); i++) {
            ConfigEntry entry = configEntryListWidget.children().get(i);
            boolean pathMatch = path.equals(entry.getPrimaryPath())
                    || entry.getDeleteTargets().contains(path);
            if (!pathMatch || !(entry instanceof ColorGroupEntry
                    || entry instanceof SingleColorEntry
                    || entry instanceof ColorAdjustmentEntry
                    || entry instanceof BoxEdgeEntry
                    || entry instanceof NumericPairEntry
                    || entry instanceof StringCoordinateEntry)) {
                continue;
            }
            if (!entry.openInlineEditor(mouseX, mouseY)) {
                return false;
            }
            statusMessage = "Opened editor for " + humanizePath(path);
            statusColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
            statusUntil = Util.getMeasuringTimeMs() + 1200L;
            return true;
        }
        return false;
    }

    private int getSidebarDotColor(String path) {
        Boolean booleanState = getSidebarBooleanState(path);
        if (booleanState != null) {
            return booleanState ? new Color(uiColorConfigBooleanTrue, true).getRGB() : new Color(uiColorConfigBooleanFalse, true).getRGB();
        }
        Integer colorPreview = getSidebarColorPreview(path);
        if (colorPreview != null) {
            return (colorPreview & 0x00FFFFFF) | 0xFF000000;
        }
        return withAlpha(uiColorHeaderSubTitleText, 255);
    }

    private Integer getSidebarColorPreview(String path) {
        if (currentDocument == null || path == null || path.isBlank()) {
            return null;
        }
        JsonElement direct = getValueAtPath(currentDocument.getRoot(), path);
        Integer directColor = parseColorPreview(path, direct);
        if (directColor != null) {
            return directColor;
        }

        String leaf = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        String parentPath = path.contains(".") ? path.substring(0, path.lastIndexOf('.')) : "";
        JsonElement parent = parentPath.isBlank() ? currentDocument.getRoot() : getValueAtPath(currentDocument.getRoot(), parentPath);
        if (parent == null || !parent.isJsonObject()) {
            return null;
        }

        Integer derivedColor = deriveSidebarModelColor(parent.getAsJsonObject(), leaf);
        if (derivedColor != null) {
            return derivedColor;
        }

        ComponentMatch match = matchComponentKey(leaf, RGBA_GROUP_PATTERN, "red", "green", "blue", "alpha", "r", "g", "b", "a");
        String baseName = match != null && !match.base().isBlank() ? match.base() : leaf;

        Integer red = null;
        Integer green = null;
        Integer blue = null;
        Integer alpha = 255;
        boolean integerColor = true;
        for (Map.Entry<String, JsonElement> entry : parent.getAsJsonObject().entrySet()) {
            ComponentMatch sibling = matchComponentKey(entry.getKey(), RGBA_GROUP_PATTERN, "red", "green", "blue", "alpha", "r", "g", "b", "a");
            if (sibling == null || !sibling.base().equalsIgnoreCase(baseName) || !isPrimitiveNumber(entry.getValue())) {
                continue;
            }
            double rawComponent = entry.getValue().getAsDouble();
            integerColor &= Math.rint(rawComponent) == rawComponent;
            int componentValue = 0;
            switch (normalizeColorComponent(sibling.component())) {
                case "r" -> red = componentValue;
                case "g" -> green = componentValue;
                case "b" -> blue = componentValue;
                case "a" -> alpha = componentValue;
            }
        }
        if (red == null || green == null || blue == null) {
            return null;
        }
        red = null;
        green = null;
        blue = null;
        alpha = 255;
        for (Map.Entry<String, JsonElement> entry : parent.getAsJsonObject().entrySet()) {
            ComponentMatch sibling = matchComponentKey(entry.getKey(), RGBA_GROUP_PATTERN, "red", "green", "blue", "alpha", "r", "g", "b", "a");
            if (sibling == null || !sibling.base().equalsIgnoreCase(baseName) || !isPrimitiveNumber(entry.getValue())) {
                continue;
            }
            int componentValue = normalizeSidebarColorComponent(entry.getValue().getAsDouble(), integerColor);
            switch (normalizeColorComponent(sibling.component())) {
                case "r" -> red = componentValue;
                case "g" -> green = componentValue;
                case "b" -> blue = componentValue;
                case "a" -> alpha = componentValue;
            }
        }
        return new Color(red, green, blue, alpha).getRGB();
    }

    private boolean looksLikeColorPath(String path) {
        String normalized = path.toLowerCase(Locale.ROOT);
        return hasDirectColorNaming(normalized)
                || normalized.contains("accent")
                || normalized.contains("highlight")
                || normalized.contains("outline")
                || normalized.contains("background")
                || normalized.contains("foreground")
                || normalized.contains("tint")
                || normalized.contains("hue")
                || normalized.contains("saturation")
                || normalized.contains("brightness")
                || normalized.contains("lightness")
                || normalized.contains("opacity")
                || normalized.endsWith(".r")
                || normalized.endsWith(".g")
                || normalized.endsWith(".b")
                || normalized.endsWith(".a");
    }

    private Integer parseColorPreview(String path, JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        try {
            long value;
            if (primitive.isNumber()) {
                if (!isDirectColorValuePath(path)) {
                    return null;
                }
                value = primitive.getAsLong() & 0xFFFFFFFFL;
                if ((value & 0xFF000000L) == 0 && value <= 0xFFFFFFL) {
                    value |= 0xFF000000L;
                }
                return (int) value;
            }
            String raw = primitive.getAsString().trim();
            if (!HEX_COLOR_PATTERN.matcher(raw).matches()) {
                return null;
            }
            if (raw.startsWith("#")) {
                String hex = raw.substring(1);
                if (hex.length() == 8) {
                    int red = Integer.parseInt(hex.substring(0, 2), 16);
                    int green = Integer.parseInt(hex.substring(2, 4), 16);
                    int blue = Integer.parseInt(hex.substring(4, 6), 16);
                    int alpha = Integer.parseInt(hex.substring(6, 8), 16);
                    return new Color(red, green, blue, alpha).getRGB();
                }
                if (hex.length() == 6) {
                    int red = Integer.parseInt(hex.substring(0, 2), 16);
                    int green = Integer.parseInt(hex.substring(2, 4), 16);
                    int blue = Integer.parseInt(hex.substring(4, 6), 16);
                    return new Color(red, green, blue, 255).getRGB();
                }
                return null;
            }

            String normalized = raw.startsWith("0x") || raw.startsWith("0X") ? raw.substring(2) : raw;
            value = Long.parseLong(normalized, 16);
            if (normalized.length() <= 6) {
                value |= 0xFF000000L;
            }
            return (int) value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isDirectColorValuePath(String path) {
        String normalized = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return hasDirectColorNaming(normalized)
                || normalized.contains("accent")
                || normalized.contains("highlight")
                || normalized.contains("outline")
                || normalized.contains("background")
                || normalized.contains("foreground")
                || normalized.contains("tint")
                || normalized.endsWith(".r")
                || normalized.endsWith(".g")
                || normalized.endsWith(".b")
                || normalized.endsWith(".a");
    }

    private Integer deriveSidebarModelColor(JsonObject parent, String leaf) {
        String normalizedLeaf = leaf == null ? "" : leaf.toLowerCase(Locale.ROOT);
        if (!(normalizedLeaf.contains("hue")
                || normalizedLeaf.contains("saturation")
                || normalizedLeaf.contains("brightness")
                || normalizedLeaf.contains("lightness")
                || normalizedLeaf.contains("opacity")
                || normalizedLeaf.endsWith("alpha")
                || normalizedLeaf.equals("h")
                || normalizedLeaf.equals("s")
                || normalizedLeaf.equals("b")
                || normalizedLeaf.equals("v")
                || normalizedLeaf.equals("l")
                || normalizedLeaf.equals("a"))) {
            return null;
        }

        Double hueValue = findParentNumeric(parent, "hue", "h");
        Double saturationValue = findParentNumeric(parent, "saturation", "sat", "s");
        Double brightnessValue = findParentNumeric(parent, "brightness", "value", "lightness", "bright", "v", "l", "b");
        Double opacityValue = findParentNumeric(parent, "opacity", "alpha", "a");
        if (hueValue == null || saturationValue == null || brightnessValue == null) {
            return null;
        }

        float hue = normalizeHueValue(hueValue);
        float saturation = normalizeUnitValue(saturationValue);
        float brightness = normalizeUnitValue(brightnessValue);
        int alpha = opacityValue == null ? 255 : Math.round(normalizeUnitValue(opacityValue) * 255.0f);
        int rgb = Color.HSBtoRGB(hue, saturation, brightness);
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }

    private Double findParentNumeric(JsonObject parent, String... aliases) {
        for (Map.Entry<String, JsonElement> entry : parent.entrySet()) {
            if (!isPrimitiveNumber(entry.getValue())) {
                continue;
            }
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            for (String alias : aliases) {
                if (key.equals(alias) || key.endsWith(alias)) {
                    return entry.getValue().getAsDouble();
                }
            }
        }
        return null;
    }

    private float normalizeHueValue(double value) {
        if (value <= 1.0D && value >= 0.0D) {
            return (float) value;
        }
        double wrapped = value % 360.0D;
        if (wrapped < 0.0D) {
            wrapped += 360.0D;
        }
        return (float) (wrapped / 360.0D);
    }

    private float normalizeUnitValue(double value) {
        if (value <= 1.0D && value >= 0.0D) {
            return (float) value;
        }
        return (float) Math.max(0.0D, Math.min(1.0D, value / 100.0D));
    }

    private int normalizeSidebarColorComponent(double value, boolean integerColor) {
        if (integerColor) {
            return Math.max(0, Math.min(255, (int) Math.round(value)));
        }
        return Math.max(0, Math.min(255, (int) Math.round(value * 255.0D)));
    }

    private boolean toggleSidebarBoolean(String path) {
        Boolean current = getSidebarBooleanState(path);
        if (current == null) {
            return false;
        }
        updateValue(path, new JsonPrimitive(!current));
        rebuildEntries();
        statusMessage = humanizePath(path) + " toggled";
        statusColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
        statusUntil = Util.getMeasuringTimeMs() + 1200L;
        return true;
    }

    private void toggleSectionCollapsed(String section) {
        if (collapsedSections.contains(section)) {
            collapsedSections.remove(section);
        } else {
            collapsedSections.add(section);
        }
        sidebarContentHeightCacheDirty = true;
        rebuildEntries();
    }

    private String getConfigSourceLabel() {
        if (configFile == null || currentDocument == null) {
            return "Unavailable";
        }
        return switch (currentDocument.getFormat()) {
            case JSON -> "JSON";
            case JSON5 -> "JSON5";
            case PROPERTIES -> "Properties";
            case TOML -> "TOML";
            case YAML -> "YAML";
            case UNKNOWN -> "Unknown";
        };
    }

    private String humanizePath(String path) {
        String display = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        display = display.replace('.', ' ').replace('_', ' ').replace('-', ' ');
        display = display.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        if (display.startsWith("No ")) {
            display = display.substring(3) + " Disabled";
        }
        if (display.isEmpty()) {
            return path;
        }
        return Character.toUpperCase(display.charAt(0)) + display.substring(1);
    }

    private String fitText(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int ellipsisWidth = this.textRenderer.getWidth(ellipsis);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String next = builder.toString() + text.charAt(i);
            if (this.textRenderer.getWidth(next) + ellipsisWidth > maxWidth) {
                break;
            }
            builder.append(text.charAt(i));
        }
        return builder + ellipsis;
    }

    private boolean matchesSearchQuery(String path, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String normalizedQuery = query.toLowerCase(Locale.ROOT).trim();
        String normalizedPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        String humanized = humanizePath(path).toLowerCase(Locale.ROOT);
        String leaf = path != null && path.contains(".") ? path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : normalizedPath;
        return normalizedPath.contains(normalizedQuery)
                || humanized.contains(normalizedQuery)
                || leaf.contains(normalizedQuery);
    }

    private boolean shouldRenderPath(String path, String query) {
        return matchesSearchQuery(path, query) && (!filterChangedOnly || path == null || path.isBlank() || hasChangedDescendant(path));
    }

    private boolean hasChangedDescendant(String path) {
        if (changedDescendantCacheDirty) {
            changedDescendantCache.clear();
            changedDescendantCacheDirty = false;
        }
        if (path == null || path.isBlank()) {
            return !changedPaths.isEmpty();
        }
        Boolean cached = changedDescendantCache.get(path);
        if (cached != null) {
            return cached;
        }
        boolean changed = false;
        for (String changedPath : changedPaths) {
            if (changedPath.equals(path) || changedPath.startsWith(path + ".")) {
                changed = true;
                break;
            }
        }
        changedDescendantCache.put(path, changed);
        return changed;
    }

    private boolean hasValidationDescendant(String path) {
        return getValidationMessage(path) != null;
    }

    private String getValidationMessage(String path) {
        if (configEntryListWidget == null || path == null || path.isBlank()) {
            return null;
        }
        refreshSidebarValidationLookup();
        return cachedSidebarValidationLookup.get(path);
    }

    private static int withAlpha(int argbColor, int alpha) {
        Color color = new Color(argbColor, true);
        int clampedAlpha = Math.max(0, Math.min(255, alpha));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clampedAlpha).getRGB();
    }

    private void randomizeCurrentScope() {
        if (currentDocument == null) {
            return;
        }
        ConfigEntry focusedEntry = configEntryListWidget == null ? null : configEntryListWidget.getFocusedEntry();
        List<String> targets = focusedEntry != null && !focusedEntry.getDeleteTargets().isEmpty()
                ? focusedEntry.getDeleteTargets()
                : collectRandomizeTargetsForLeadingSection();
        if (targets.isEmpty()) {
            statusMessage = "Nothing to randomize";
            statusColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
            statusUntil = Util.getMeasuringTimeMs() + 1200L;
            return;
        }
        for (String path : targets) {
            JsonElement current = getValueAtPath(currentDocument.getRoot(), path);
            JsonElement randomized = randomizeValueForPath(path, current);
            if (randomized != null) {
                updateValue(path, randomized);
            }
        }
        statusMessage = "Randomized " + targets.size() + " value" + (targets.size() == 1 ? "" : "s");
        statusColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
        statusUntil = Util.getMeasuringTimeMs() + 1500L;
        rebuildEntries();
    }

    private List<String> collectRandomizeTargetsForLeadingSection() {
        if (configEntryListWidget == null) {
            return List.of();
        }
        String section = configEntryListWidget.getLeadingSection();
        if (section == null || section.isBlank()) {
            return List.of();
        }
        List<String> targets = new ArrayList<>();
        collectPrimitivePaths(section, getValueAtPath(currentDocument.getRoot(), section), targets);
        return targets;
    }

    private void collectPrimitivePaths(String path, JsonElement element, List<String> output) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (isInternalLinkedConfigKey(entry.getKey())) {
                    continue;
                }
                collectPrimitivePaths(joinPath(path, entry.getKey()), entry.getValue(), output);
            }
            return;
        }
        output.add(path);
    }

    private JsonElement randomizeValueForPath(String path, JsonElement current) {
        if (current == null || current.isJsonNull()) {
            return null;
        }
        Random random = new Random((long) path.hashCode() ^ System.nanoTime());
        if (current.isJsonPrimitive()) {
            JsonPrimitive primitive = current.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return new JsonPrimitive(random.nextBoolean());
            }
            if (primitive.isNumber()) {
                double value = primitive.getAsDouble();
                SliderConfig sliderConfig = inferSliderConfig(path, value);
                if (sliderConfig != null) {
                    double span = sliderConfig.max() - sliderConfig.min();
                    double next = sliderConfig.min() + (random.nextDouble() * Math.max(0.0001, span));
                    if (sliderConfig.wholeNumber()) {
                        return new JsonPrimitive((int) Math.round(next));
                    }
                    return new JsonPrimitive(Math.round(next / sliderConfig.step()) * sliderConfig.step());
                }
                if (Math.rint(value) == value) {
                    long magnitude = Math.max(3L, Math.min(5000L, Math.abs((long) value) + 7L));
                    return new JsonPrimitive(random.nextInt((int) magnitude + 1));
                }
                double next = Math.max(0.0, value + (random.nextDouble() - 0.5) * Math.max(1.0, Math.abs(value)));
                return new JsonPrimitive(Math.round(next * 100.0) / 100.0);
            }
            if (primitive.isString()) {
                String raw = primitive.getAsString();
                if (looksLikeSingleColor(path, primitive)) {
                    int next = 0xFF000000 | random.nextInt(0xFFFFFF + 1);
                    if (raw.startsWith("0x") || raw.startsWith("0X")) {
                        return new JsonPrimitive(String.format(Locale.ROOT, "0x%08X", next));
                    }
                    return new JsonPrimitive(String.format(Locale.ROOT, "#%06X", next & 0xFFFFFF));
                }
                List<String> options = inferOptions(path, raw);
                if (options != null && !options.isEmpty()) {
                    return new JsonPrimitive(options.get(random.nextInt(options.size())));
                }
                if (isCoordinateString(raw)) {
                    String[] parts = raw.split("\\s*,\\s*");
                    List<String> randomized = new ArrayList<>();
                    for (String ignored : parts) {
                        randomized.add(String.valueOf(random.nextInt(129) - 64));
                    }
                    return new JsonPrimitive(String.join(", ", randomized));
                }
                if (isRangeString(raw)) {
                    int min = random.nextInt(50);
                    return new JsonPrimitive(min + "-" + (min + 1 + random.nextInt(60)));
                }
            }
        }
        if (current.isJsonArray()) {
            JsonArray randomized = new JsonArray();
            for (JsonElement element : current.getAsJsonArray()) {
                JsonElement next = randomizeValueForPath(path, element);
                randomized.add(next == null ? element : next);
            }
            return randomized;
        }
        return null;
    }


    private void loadConfig() {
        lastSearchQuery = searchField == null ? "" : searchField.getText();
        topLevelSections.clear();
        sectionScrollTargets.clear();

        if (configFile == null) {
            configEntryListWidget.clearConfigEntries();
            return;
        }

        try {
            currentDocument = ModConfigDocument.load(configFile);
            savedDocumentSnapshot = GSON.toJson(currentDocument.getRoot());
            dirty = false;
            changedPaths.clear();
            invalidateSidebarDerivedCaches();
            statusMessage = "Loaded " + configFile.getName();
            statusColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
            statusUntil = Util.getMeasuringTimeMs() + 1500L;
            rebuildEntries();
            rebuildSearchDropdownEntries();
        } catch (IOException | IllegalArgumentException | JsonSyntaxException e) {
            currentDocument = null;
            configEntryListWidget.clearConfigEntries();
            searchDropdownEntries.clear();
            selectedDropdownIndex = -1;
            dropdownScrollOffset = 0;
            statusMessage = "Failed to load " + configFile.getName();
            statusColor = new Color(uiColorWarningPromptText, true).getRGB();
            statusUntil = Util.getMeasuringTimeMs() + 4000L;
            SUBLOGGER.logE("File-Management thread", "Failed to load config: " + e.getMessage(), true, "Failed to load config, See kLogs for details.");
        }
    }

    private void handleSearchQueryChanged() {
        lastSearchQuery = searchField == null ? "" : searchField.getText();
        rebuildEntries();
        rebuildSearchDropdownEntries();
    }

    private void rebuildEntries() {
        if (configEntryListWidget == null) {
            return;
        }
        RESET_VISIBILITY_PREDICATE = targets -> targets != null && targets.stream().anyMatch(changedPaths::contains);
        double preservedScroll = configEntryListWidget.getScrollAmount();
        int preservedSidebarScroll = sidebarScrollOffset;
        ConfigEntry preservedFocus = configEntryListWidget.getFocusedEntry();
        configEntryListWidget.clearConfigEntries();
        topLevelSections.clear();
        sectionScrollTargets.clear();
        if (currentDocument != null) {
            addEntries("", currentDocument.getRoot(), lastSearchQuery.trim().toLowerCase(Locale.ROOT));
        }
        rebuildSidebarCaches();
        invalidateSidebarDerivedCaches();
        refreshSidebarValidationLookup();
        configEntryListWidget.setScrollAmount(preservedScroll);
        sidebarScrollOffset = preservedSidebarScroll;
        clampSidebarScroll();
        if (preservedFocus != null) {
            String preservedPath = preservedFocus.getPrimaryPath();
            for (ConfigEntry entry : configEntryListWidget.children()) {
                boolean focused = Objects.equals(entry.getPrimaryPath(), preservedPath);
                entry.setFocused(focused);
                entry.setFocusedState(focused);
                if (focused) {
                    configEntryListWidget.setFocused(entry);
                }
            }
        } else {
            configEntryListWidget.children().forEach(entry -> entry.setFocused(false));
        }
        rebuildSearchDropdownEntries();
    }

    private void rebuildSearchDropdownEntries() {
        searchDropdownEntries.clear();
        selectedDropdownIndex = -1;
        dropdownScrollOffset = 0;
        String query = searchField == null ? "" : searchField.getText().trim();
        if (currentDocument == null) {
            return;
        }

        if (query.isBlank()) {
            for (String recent : recentSearches) {
                searchDropdownEntries.add(new SearchDropdownEntry("", recent, "Recent search"));
            }
            if (!searchDropdownEntries.isEmpty()) {
                selectedDropdownIndex = 0;
            }
            return;
        }

        collectSearchEntries("", currentDocument.getRoot(), query.toLowerCase(Locale.ROOT), searchDropdownEntries);
        if (searchDropdownEntries.size() > MAX_SEARCH_DROPDOWN_RESULTS) {
            searchDropdownEntries.subList(MAX_SEARCH_DROPDOWN_RESULTS, searchDropdownEntries.size()).clear();
        }
        if (!searchDropdownEntries.isEmpty()) {
            selectedDropdownIndex = 0;
        }
    }

    private void collectSearchEntries(String pathPrefix, JsonElement element, String query, List<SearchDropdownEntry> output) {
        if (element == null || element.isJsonNull() || output.size() >= MAX_SEARCH_DROPDOWN_RESULTS) {
            return;
        }
        if (element.isJsonObject()) {
            if (!pathPrefix.isBlank() && matchesSearchQuery(pathPrefix, query)) {
                output.add(new SearchDropdownEntry(pathPrefix, humanizePath(pathPrefix), "Section"));
            }
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (isInternalLinkedConfigKey(entry.getKey())) {
                    continue;
                }
                collectSearchEntries(joinPath(pathPrefix, entry.getKey()), entry.getValue(), query, output);
                if (output.size() >= MAX_SEARCH_DROPDOWN_RESULTS) {
                    return;
                }
            }
            return;
        }

        if (!matchesSearchQuery(pathPrefix, query)) {
            return;
        }
        output.add(new SearchDropdownEntry(pathPrefix, humanizePath(pathPrefix), buildSearchPreview(element)));
    }

    private String buildSearchPreview(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonObject()) {
            return "Object";
        }
        if (element.isJsonArray()) {
            return "Array[" + element.getAsJsonArray().size() + "]";
        }
        try {
            String raw = element.getAsJsonPrimitive().getAsString();
            return raw.length() > 48 ? raw.substring(0, 45) + "..." : raw;
        } catch (Exception ignored) {
            return element.toString();
        }
    }

    private void moveDropdownSelection(int delta) {
        if (searchDropdownEntries.isEmpty()) {
            return;
        }
        selectedDropdownIndex = Math.max(0, Math.min(searchDropdownEntries.size() - 1, (selectedDropdownIndex < 0 ? 0 : selectedDropdownIndex + delta)));
        if (selectedDropdownIndex < dropdownScrollOffset) {
            dropdownScrollOffset = selectedDropdownIndex;
        } else if (selectedDropdownIndex >= dropdownScrollOffset + SEARCH_DROPDOWN_MAX_VISIBLE_ROWS) {
            dropdownScrollOffset = selectedDropdownIndex - SEARCH_DROPDOWN_MAX_VISIBLE_ROWS + 1;
        }
    }

    private boolean activateSelectedDropdownEntry() {
        if (selectedDropdownIndex < 0 || selectedDropdownIndex >= searchDropdownEntries.size()) {
            return false;
        }
        SearchDropdownEntry entry = searchDropdownEntries.get(selectedDropdownIndex);
        if ("Recent search".equals(entry.secondary())) {
            searchField.setText(entry.primary());
            searchDropdownDismissed = false;
            handleSearchQueryChanged();
            return true;
        }
        rememberSearch(searchField.getText());
        return jumpToPath(entry.path());
    }

    private void jumpToSearchQuery() {
        String query = searchField == null ? "" : searchField.getText().trim();
        if (query.isBlank()) {
            return;
        }
        rememberSearch(query);
        if (!searchDropdownEntries.isEmpty()) {
            jumpToPath(searchDropdownEntries.get(0).path());
        }
    }

    private boolean jumpToPath(String path) {
        if (path == null || path.isBlank() || configEntryListWidget == null) {
            return false;
        }
        for (int i = 0; i < configEntryListWidget.children().size(); i++) {
            ConfigEntry entry = configEntryListWidget.children().get(i);
            if (path.equals(entry.getPrimaryPath())) {
                configEntryListWidget.scrollToIndex(Math.max(0, i - 1));
                configEntryListWidget.setFocused(entry);
                configEntryListWidget.children().forEach(candidate -> {
                    boolean focused = candidate == entry;
                    candidate.setFocusedState(focused);
                    candidate.setFocused(focused);
                });
                searchField.setFocused(false);
                searchDropdownDismissed = true;
                statusMessage = "Jumped to " + humanizePath(path);
                statusColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
                statusUntil = Util.getMeasuringTimeMs() + 1200L;
                return true;
            }
        }
        return false;
    }

    private void rememberSearch(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            return;
        }
        recentSearches.remove(normalized);
        recentSearches.add(0, normalized);
        while (recentSearches.size() > MAX_RECENT_SEARCHES) {
            recentSearches.remove(recentSearches.size() - 1);
        }
    }

    private void addEntries(String pathPrefix, JsonElement element, String query) {
        if (element == null || element.isJsonNull()) {
            return;
        }

        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (!pathPrefix.isBlank() && isPrimitiveMapObject(object) && shouldRenderAsMappingTable(pathPrefix, object)) {
                if (shouldRenderPath(pathPrefix, query)) {
                    configEntryListWidget.addConfigEntry(new MappingEntry(pathPrefix, object, newValue -> updateValue(pathPrefix, newValue)));
                }
                return;
            }
            if (!pathPrefix.isBlank() && shouldRenderPath(pathPrefix, query)) {
                if (!pathPrefix.contains(".")) {
                    topLevelSections.add(pathPrefix);
                }
                sectionScrollTargets.put(pathPrefix, configEntryListWidget.children().size());
                SectionInspectorData inspectorData = buildSectionInspectorData(pathPrefix, object);
                configEntryListWidget.addConfigEntry(new SectionEntry(pathPrefix, buildSectionSubtitle(object), inspectorData, collapsedSections.contains(pathPrefix), () -> toggleSectionCollapsed(pathPrefix)));
                if (query.isBlank() && collapsedSections.contains(pathPrefix)) {
                    return;
                }
            }

            Set<String> consumedKeys = new HashSet<>();
            for (GroupedField groupedField : collectGroupedFields(pathPrefix, object, query)) {
                configEntryListWidget.addConfigEntry(groupedField.entry());
                consumedKeys.addAll(groupedField.keys());
            }

            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (consumedKeys.contains(entry.getKey())) {
                    continue;
                }
                if (isInternalLinkedConfigKey(entry.getKey())) {
                    continue;
                }
                String childPath = pathPrefix.isBlank() ? entry.getKey() : pathPrefix + "." + entry.getKey();
                addEntries(childPath, entry.getValue(), query);
            }
            return;
        }

        if (!shouldRenderPath(pathPrefix, query)) {
            return;
        }

        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (isPrimitiveArray(array)) {
                configEntryListWidget.addConfigEntry(new ListEntry(pathPrefix, array, this::updateValue));
            } else {
                String currentValue = GSON.toJson(element);
                configEntryListWidget.addConfigEntry(new StringEntry(pathPrefix, currentValue, newValue -> updateValue(pathPrefix, parseJsonValue(newValue))));
            }
            return;
        }

        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            configEntryListWidget.addConfigEntry(new BooleanEntry(pathPrefix, primitive.getAsBoolean(), newValue -> updateValue(pathPrefix, new JsonPrimitive(newValue))));
        } else if (primitive.isNumber()) {
            if (isSeedValue(pathPrefix)) {
                configEntryListWidget.addConfigEntry(new SeedEntry(pathPrefix, primitive.getAsLong(), newValue -> updateValue(pathPrefix, new JsonPrimitive(newValue))));
                return;
            }
            if (looksLikeSingleColor(pathPrefix, primitive)) {
                configEntryListWidget.addConfigEntry(new SingleColorEntry(pathPrefix, primitive, newValue -> updateValue(pathPrefix, newValue)));
                return;
            }
            if (isTimeLikePath(pathPrefix)) {
                configEntryListWidget.addConfigEntry(new TimeEntry(pathPrefix, primitive.getAsDouble(), newValue -> updateValue(pathPrefix, newValue)));
                return;
            }
            SliderConfig sliderConfig = inferSliderConfig(pathPrefix, primitive.getAsDouble());
            if (sliderConfig != null) {
                configEntryListWidget.addConfigEntry(new SliderEntry(pathPrefix, primitive.getAsDouble(), sliderConfig, newValue -> updateValue(pathPrefix, sliderConfig.wholeNumber() ? new JsonPrimitive((int) Math.round(newValue)) : new JsonPrimitive(newValue))));
                return;
            }
            String raw = primitive.getAsString();
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                configEntryListWidget.addConfigEntry(new DoubleEntry(pathPrefix, primitive.getAsDouble(), newValue -> updateValue(pathPrefix, new JsonPrimitive(newValue))));
            } else {
                configEntryListWidget.addConfigEntry(new IntegerEntry(pathPrefix, primitive.getAsInt(), newValue -> updateValue(pathPrefix, new JsonPrimitive(newValue))));
            }
        } else {
            String currentValue = primitive.getAsString();
            if (looksLikeSingleColor(pathPrefix, primitive)) {
                configEntryListWidget.addConfigEntry(new SingleColorEntry(pathPrefix, primitive, newValue -> updateValue(pathPrefix, newValue)));
                return;
            }
            if (isKeybindValue(pathPrefix, currentValue)) {
                configEntryListWidget.addConfigEntry(new KeybindEntry(pathPrefix, currentValue, newValue -> updateValue(pathPrefix, new JsonPrimitive(newValue))));
                return;
            }
            if (isUrlValue(currentValue)) {
                configEntryListWidget.addConfigEntry(new LinkEntry(pathPrefix, currentValue, newValue -> updateValue(pathPrefix, new JsonPrimitive(newValue))));
                return;
            }
            if (isCoordinateString(currentValue)) {
                configEntryListWidget.addConfigEntry(new StringCoordinateEntry(pathPrefix, currentValue, newValue -> updateValue(pathPrefix, new JsonPrimitive(newValue))));
                return;
            }
            if (isRangeString(currentValue)) {
                configEntryListWidget.addConfigEntry(new StringRangeEntry(pathPrefix, currentValue, newValue -> updateValue(pathPrefix, new JsonPrimitive(newValue))));
                return;
            }
            if (isRegistryIdPath(pathPrefix)) {
                configEntryListWidget.addConfigEntry(new RegistryIdEntry(pathPrefix, currentValue, newValue -> updateValue(pathPrefix, new JsonPrimitive(newValue))));
                return;
            }
            if (isPathValue(pathPrefix, currentValue)) {
                configEntryListWidget.addConfigEntry(new PathEntry(pathPrefix, currentValue, newValue -> updateValue(pathPrefix, new JsonPrimitive(newValue))));
                return;
            }
            List<String> inferredOptions = inferOptions(pathPrefix, currentValue);
            if (inferredOptions != null) {
                configEntryListWidget.addConfigEntry(new OptionEntry(pathPrefix, currentValue, inferredOptions, newValue -> updateValue(pathPrefix, new JsonPrimitive(newValue))));
            } else if (isVersionStringPath(pathPrefix, currentValue)) {
                configEntryListWidget.addConfigEntry(new VersionEntry(pathPrefix, currentValue, newValue -> updateValue(pathPrefix, new JsonPrimitive(newValue))));
            } else if (isLongTextValue(pathPrefix, currentValue)) {
                configEntryListWidget.addConfigEntry(new LongStringEntry(pathPrefix, currentValue, newValue -> updateValue(pathPrefix, new JsonPrimitive(newValue))));
            } else {
                configEntryListWidget.addConfigEntry(new StringEntry(pathPrefix, currentValue, newValue -> updateValue(pathPrefix, new JsonPrimitive(newValue))));
            }
        }
    }

    private List<GroupedField> collectGroupedFields(String pathPrefix, JsonObject object, String query) {
        Map<String, JsonElement> entries = new LinkedHashMap<>();
        object.entrySet().forEach(entry -> entries.put(entry.getKey(), entry.getValue()));

        List<GroupedField> groupedFields = new ArrayList<>();
        Set<String> consumedKeys = new HashSet<>();

        GroupedField enabledModeGroup = tryBuildEnabledModeGroup(pathPrefix, entries, query);
        if (enabledModeGroup != null) {
            consumedKeys.addAll(enabledModeGroup.keys());
            groupedFields.add(enabledModeGroup);
        }

        GroupedField featureCardGroup = tryBuildFeatureCardGroup(pathPrefix, entries, query);
        if (featureCardGroup != null) {
            consumedKeys.addAll(featureCardGroup.keys());
            groupedFields.add(featureCardGroup);
        }

        GroupedField debugGroup = tryBuildDebugToggleGroup(pathPrefix, entries, query);
        if (debugGroup != null) {
            consumedKeys.addAll(debugGroup.keys());
            groupedFields.add(debugGroup);
        }

        GroupedField colorAdjustmentGroup = tryBuildColorAdjustmentGroup(pathPrefix, entries, query);
        if (colorAdjustmentGroup != null) {
            consumedKeys.addAll(colorAdjustmentGroup.keys());
            groupedFields.add(colorAdjustmentGroup);
        }

        GroupedField soundConfigGroup = tryBuildSoundConfigGroup(pathPrefix, entries, query);
        if (soundConfigGroup != null) {
            consumedKeys.addAll(soundConfigGroup.keys());
            groupedFields.add(soundConfigGroup);
        }

        GroupedField enableClusterGroup = tryBuildEnableClusterGroup(pathPrefix, entries, query);
        if (enableClusterGroup != null) {
            consumedKeys.addAll(enableClusterGroup.keys());
            groupedFields.add(enableClusterGroup);
        }

        List<GroupedField> coordinateClusterGroups = tryBuildCoordinateClusterGroups(pathPrefix, entries, consumedKeys, query);
        for (GroupedField coordinateClusterGroup : coordinateClusterGroups) {
            consumedKeys.addAll(coordinateClusterGroup.keys());
            groupedFields.add(coordinateClusterGroup);
        }

        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            String key = entry.getKey();
            if (consumedKeys.contains(key)) {
                continue;
            }
            GroupedField toggleValueGroup = tryBuildToggleValueGroup(pathPrefix, entries, key, query);
            if (toggleValueGroup != null && consumedKeys.addAll(toggleValueGroup.keys())) {
                groupedFields.add(toggleValueGroup);
            }
        }

        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            String key = entry.getKey();
            if (consumedKeys.contains(key) || !isPrimitiveBoolean(entry.getValue())) {
                continue;
            }

            GroupedField triStateGroup = tryBuildTriStateGroup(pathPrefix, entries, key, query);
            if (triStateGroup != null && consumedKeys.addAll(triStateGroup.keys())) {
                groupedFields.add(triStateGroup);
                continue;
            }

            GroupedField booleanPair = tryBuildBooleanPairGroup(pathPrefix, entries, key, query);
            if (booleanPair != null && consumedKeys.addAll(booleanPair.keys())) {
                groupedFields.add(booleanPair);
            }
        }

        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            String key = entry.getKey();
            if (consumedKeys.contains(key) || !isPrimitiveNumber(entry.getValue())) {
                continue;
            }

            GroupedField spacingGroup = tryBuildSpacingGroup(pathPrefix, entries, key, query);
            if (spacingGroup != null && consumedKeys.addAll(spacingGroup.keys())) {
                groupedFields.add(spacingGroup);
                continue;
            }

            GroupedField resolutionGroup = tryBuildResolutionGroup(pathPrefix, entries, key, query);
            if (resolutionGroup != null && consumedKeys.addAll(resolutionGroup.keys())) {
                groupedFields.add(resolutionGroup);
                continue;
            }

            GroupedField colorGroup = tryBuildColorGroup(pathPrefix, entries, key, query);
            if (colorGroup != null && consumedKeys.addAll(colorGroup.keys())) {
                groupedFields.add(colorGroup);
                continue;
            }

            GroupedField pairGroup = tryBuildPairGroup(pathPrefix, entries, key, query);
            if (pairGroup != null && consumedKeys.addAll(pairGroup.keys())) {
                groupedFields.add(pairGroup);
            }
        }

        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            String key = entry.getKey();
            if (consumedKeys.contains(key) || !looksLikeSingleColor(joinPath(pathPrefix, key), entry.getValue())) {
                continue;
            }
            GroupedField singleColor = tryBuildSingleColorEntry(pathPrefix, key, entry.getValue(), query);
            if (singleColor != null && consumedKeys.addAll(singleColor.keys())) {
                groupedFields.add(singleColor);
            }
        }

        return groupedFields;
    }

    private List<GroupedField> tryBuildCoordinateClusterGroups(String pathPrefix, Map<String, JsonElement> entries, Set<String> consumedKeys, String query) {
        Map<String, CoordinateClusterBuilder> candidates = new LinkedHashMap<>();
        Pattern[] patterns = {XYZ_GROUP_PATTERN, XY_GROUP_PATTERN};
        String[][] components = {{"x", "y", "z"}, {"x", "y"}};

        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            String key = entry.getKey();
            if (consumedKeys.contains(key) || !isPrimitiveNumber(entry.getValue())) {
                continue;
            }
            for (int i = 0; i < patterns.length; i++) {
                ComponentMatch match = matchComponentKey(key, patterns[i], components[i]);
                if (match == null || match.base().isBlank()) {
                    continue;
                }
                String base = match.base();
                CoordinateClusterBuilder builder = candidates.get(base);
                if (builder == null) {
                    builder = new CoordinateClusterBuilder(base, components[i].length);
                    candidates.put(base, builder);
                }
                if (builder.dimension != components[i].length) {
                    continue;
                }
                builder.paths.put(match.component().toLowerCase(Locale.ROOT), joinPath(pathPrefix, key));
                builder.values.put(match.component().toLowerCase(Locale.ROOT), entry.getValue().getAsDouble());
                break;
            }
        }

        List<CoordinateClusterBuilder> completeCandidates = new ArrayList<>();
        for (CoordinateClusterBuilder candidate : candidates.values()) {
            if (candidate.isComplete()) {
                completeCandidates.add(candidate);
            }
        }

        Map<String, List<CoordinateClusterBuilder>> buckets = new LinkedHashMap<>();
        for (CoordinateClusterBuilder candidate : completeCandidates) {
            String familyKey = deriveCoordinateClusterFamily(candidate.base);
            if (familyKey == null || familyKey.isBlank()) {
                continue;
            }
            buckets.computeIfAbsent(candidate.dimension + ":" + familyKey, ignored -> new ArrayList<>()).add(candidate);
        }

        List<GroupedField> result = new ArrayList<>();
        for (Map.Entry<String, List<CoordinateClusterBuilder>> bucket : buckets.entrySet()) {
            List<CoordinateClusterBuilder> bucketCandidates = bucket.getValue();
            if (bucketCandidates.size() < 2) {
                continue;
            }
            bucketCandidates.sort(Comparator.comparing(candidate -> humanizePath(candidate.base), String.CASE_INSENSITIVE_ORDER));
            Set<String> clusterKeys = new LinkedHashSet<>();
            List<CoordinateClusterPoint> points = new ArrayList<>();
            for (CoordinateClusterBuilder candidate : bucketCandidates) {
                if (candidate.conflictsWith(consumedKeys)) {
                    points.clear();
                    clusterKeys.clear();
                    break;
                }
                clusterKeys.addAll(candidate.sourceKeys());
                points.add(candidate.toPoint());
            }
            if (points.size() < 2) {
                continue;
            }
            String familyKey = bucket.getKey().substring(bucket.getKey().indexOf(':') + 1);
            String labelPath = joinPath(pathPrefix, familyKey + (points.get(0).dimension() >= 3 ? "PointGroup3D" : "PointGroup"));
            if (!shouldRenderPath(labelPath, query)) {
                continue;
            }
            result.add(new GroupedField(clusterKeys, new MultiPointCoordinateEntry(labelPath, points, this::updateNumericValue)));
        }
        return result;
    }

    private String deriveCoordinateClusterFamily(String base) {
        List<String> tokens = splitIdentifierTokens(base);
        if (tokens.size() < 2) {
            return null;
        }
        if (tokens.size() >= 3) {
            String second = tokens.get(1).toLowerCase(Locale.ROOT);
            if (List.of("point", "points", "node", "nodes", "anchor", "anchors", "waypoint", "waypoints", "position", "positions", "offset", "offsets").contains(second)) {
                return tokens.get(0) + second;
            }
        }
        return tokens.get(0);
    }

    private List<String> splitIdentifierTokens(String base) {
        if (base == null || base.isBlank()) {
            return List.of();
        }
        String humanized = base
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .trim();
        if (humanized.isBlank()) {
            return List.of();
        }
        return Arrays.stream(humanized.split("\\s+"))
                .filter(token -> !token.isBlank())
                .map(token -> token.substring(0, 1).toLowerCase(Locale.ROOT) + token.substring(1))
                .toList();
    }

    private GroupedField tryBuildColorAdjustmentGroup(String pathPrefix, Map<String, JsonElement> entries, String query) {
        List<String> presentKeys = new ArrayList<>();
        for (String key : COLOR_ADJUSTMENT_KEYS) {
            JsonElement element = entries.get(key);
            if (element == null) {
                continue;
            }
            if ("Visible".equals(key) ? isPrimitiveBoolean(element) : isPrimitiveNumber(element)) {
                presentKeys.add(key);
            }
        }
        if (presentKeys.size() < 3 && !(presentKeys.contains("Visible") && presentKeys.contains("Opacity"))) {
            return null;
        }

        String fullPath = joinPath(pathPrefix, "ColorAdjustments");
        if (!shouldRenderPath(fullPath, query)) {
            return null;
        }

        BooleanBinding visibleBinding = presentKeys.contains("Visible")
                ? new BooleanBinding("Visible", joinPath(pathPrefix, "Visible"), entries.get("Visible").getAsBoolean())
                : null;
        List<ComponentBinding> numericBindings = new ArrayList<>();
        for (String key : List.of("Opacity", "Hue", "Saturation", "Contrast", "Gamma", "Brightness", "ColorTemperature")) {
            if (!presentKeys.contains(key)) {
                continue;
            }
            numericBindings.add(new ComponentBinding(key, joinPath(pathPrefix, key), entries.get(key).getAsDouble()));
        }
        return new GroupedField(new LinkedHashSet<>(presentKeys), new ColorAdjustmentEntry(fullPath, visibleBinding, numericBindings, this::updateBooleanValue, this::updateNumericValue));
    }

    private GroupedField tryBuildResolutionGroup(String pathPrefix, Map<String, JsonElement> entries, String key, String query) {
        if (!("ResolutionWidth".equals(key) || "ResolutionHeight".equals(key))) {
            return null;
        }
        JsonElement width = entries.get("ResolutionWidth");
        JsonElement height = entries.get("ResolutionHeight");
        if (!isPrimitiveNumber(width) || !isPrimitiveNumber(height)) {
            return null;
        }
        String fullPath = joinPath(pathPrefix, "Resolution");
        if (!shouldRenderPath(fullPath, query)) {
            return null;
        }
        List<ComponentBinding> bindings = List.of(
                new ComponentBinding("Width", joinPath(pathPrefix, "ResolutionWidth"), width.getAsDouble()),
                new ComponentBinding("Height", joinPath(pathPrefix, "ResolutionHeight"), height.getAsDouble())
        );
        return new GroupedField(Set.of("ResolutionWidth", "ResolutionHeight"), new ResolutionEntry(fullPath, bindings, this::updateNumericValue));
    }

    private GroupedField tryBuildSoundConfigGroup(String pathPrefix, Map<String, JsonElement> entries, String query) {
        JsonElement soundId = entries.get("SoundID");
        if (soundId == null || !soundId.isJsonPrimitive() || !soundId.getAsJsonPrimitive().isString()) {
            return null;
        }
        JsonElement pitch = entries.get("Pitch");
        JsonElement volume = entries.get("Volume");
        if (!isPrimitiveNumber(pitch) && !isPrimitiveNumber(volume)) {
            return null;
        }

        String fullPath = joinPath(pathPrefix, "Sound");
        if (!shouldRenderPath(fullPath, query)) {
            return null;
        }

        ComponentBinding pitchBinding = isPrimitiveNumber(pitch)
                ? new ComponentBinding("Pitch", joinPath(pathPrefix, "Pitch"), pitch.getAsDouble())
                : null;
        ComponentBinding volumeBinding = isPrimitiveNumber(volume)
                ? new ComponentBinding("Volume", joinPath(pathPrefix, "Volume"), volume.getAsDouble())
                : null;
        Set<String> consumed = new LinkedHashSet<>();
        consumed.add("SoundID");
        if (pitchBinding != null) {
            consumed.add("Pitch");
        }
        if (volumeBinding != null) {
            consumed.add("Volume");
        }
        return new GroupedField(consumed, new SoundConfigEntry(
                fullPath,
                joinPath(pathPrefix, "SoundID"),
                soundId.getAsString(),
                pitchBinding,
                volumeBinding,
                newValue -> updateValue(joinPath(pathPrefix, "SoundID"), new JsonPrimitive(newValue)),
                this::updateNumericValue
        ));
    }

    private GroupedField tryBuildFeatureCardGroup(String pathPrefix, Map<String, JsonElement> entries, String query) {
        Map<String, List<String>> prefixBuckets = new LinkedHashMap<>();
        for (String key : entries.keySet()) {
            String prefix = extractFeaturePrefix(key);
            if (prefix == null) {
                continue;
            }
            prefixBuckets.computeIfAbsent(prefix, ignored -> new ArrayList<>()).add(key);
        }

        for (Map.Entry<String, List<String>> bucket : prefixBuckets.entrySet()) {
            List<String> keys = bucket.getValue();
            if (keys.size() < 3) {
                continue;
            }
            String enabledKey = findFirstKey(keys, "Enabled", "Enable");
            if (enabledKey == null || !isPrimitiveBoolean(entries.get(enabledKey))) {
                continue;
            }

            String fullPath = joinPath(pathPrefix, bucket.getKey());
            if (!shouldRenderPath(fullPath, query)) {
                continue;
            }

            String colorKey = findFirstKey(keys, "Color");
            String speedKey = findFirstKey(keys, "Speed");
            String scaleKey = findFirstKey(keys, "Scale");
            String timeoutKey = findFirstKey(keys, "Timeout", "Duration");
            if (colorKey == null && speedKey == null && scaleKey == null && timeoutKey == null) {
                continue;
            }

            return new GroupedField(new LinkedHashSet<>(keys),
                    new FeatureCardEntry(
                            fullPath,
                            joinPath(pathPrefix, enabledKey),
                            entries.get(enabledKey).getAsBoolean(),
                            colorKey == null ? null : joinPath(pathPrefix, colorKey),
                            colorKey == null ? null : entries.get(colorKey),
                            speedKey == null ? null : new ComponentBinding("Speed", joinPath(pathPrefix, speedKey), entries.get(speedKey).getAsDouble()),
                            scaleKey == null ? null : new ComponentBinding("Scale", joinPath(pathPrefix, scaleKey), entries.get(scaleKey).getAsDouble()),
                            timeoutKey == null ? null : new ComponentBinding(timeoutKey.endsWith("Duration") ? "Duration" : "Timeout", joinPath(pathPrefix, timeoutKey), entries.get(timeoutKey).getAsDouble()),
                            this::updateBooleanValue,
                            this::updateNumericValue,
                            this::updateValue));
        }
        return null;
    }

    private GroupedField tryBuildColorGroup(String pathPrefix, Map<String, JsonElement> entries, String key, String query) {
        ComponentMatch match = matchComponentKey(key, RGBA_GROUP_PATTERN, "red", "green", "blue", "alpha", "r", "g", "b", "a");
        if (match == null || match.base().isBlank()) {
            return null;
        }

        Map<String, String> components = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            if (!isPrimitiveNumber(entry.getValue())) continue;
            ComponentMatch sibling = matchComponentKey(entry.getKey(), RGBA_GROUP_PATTERN, "red", "green", "blue", "alpha", "r", "g", "b", "a");
            if (sibling == null || !sibling.base().equals(match.base())) continue;
            components.put(normalizeColorComponent(sibling.component()), entry.getKey());
        }

        if (!components.keySet().containsAll(List.of("r", "g", "b"))) {
            return null;
        }

        String fullPath = joinPath(pathPrefix, match.base());
        if (!shouldRenderPath(fullPath, query)) {
            return null;
        }

        List<ComponentBinding> bindings = new ArrayList<>();
        for (String component : List.of("r", "g", "b", "a")) {
            String originalKey = components.get(component);
            if (originalKey == null) continue;
            String componentPath = joinPath(pathPrefix, originalKey);
            bindings.add(new ComponentBinding(component.toUpperCase(Locale.ROOT), componentPath, entries.get(originalKey).getAsDouble()));
        }

        return new GroupedField(new HashSet<>(components.values()), new ColorGroupEntry(fullPath, bindings, this::updateNumericValue));
    }

    private GroupedField tryBuildSpacingGroup(String pathPrefix, Map<String, JsonElement> entries, String key, String query) {
        ComponentMatch match = matchComponentKey(key, EDGE_GROUP_PATTERN, "top", "bottom", "left", "right");
        if (match == null || match.base().isBlank()) {
            return null;
        }

        Map<String, String> matchedKeys = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            if (!isPrimitiveNumber(entry.getValue())) {
                continue;
            }
            ComponentMatch sibling = matchComponentKey(entry.getKey(), EDGE_GROUP_PATTERN, "top", "bottom", "left", "right");
            if (sibling == null || !sibling.base().equalsIgnoreCase(match.base())) {
                continue;
            }
            matchedKeys.put(sibling.component().toLowerCase(Locale.ROOT), entry.getKey());
        }

        if (!matchedKeys.keySet().containsAll(List.of("top", "bottom", "left", "right"))) {
            return null;
        }

        String fullPath = joinPath(pathPrefix, match.base());
        if (!shouldRenderPath(fullPath, query)) {
            return null;
        }

        List<ComponentBinding> bindings = List.of(
                new ComponentBinding("Top", joinPath(pathPrefix, matchedKeys.get("top")), entries.get(matchedKeys.get("top")).getAsDouble()),
                new ComponentBinding("Right", joinPath(pathPrefix, matchedKeys.get("right")), entries.get(matchedKeys.get("right")).getAsDouble()),
                new ComponentBinding("Bottom", joinPath(pathPrefix, matchedKeys.get("bottom")), entries.get(matchedKeys.get("bottom")).getAsDouble()),
                new ComponentBinding("Left", joinPath(pathPrefix, matchedKeys.get("left")), entries.get(matchedKeys.get("left")).getAsDouble())
        );
        return new GroupedField(new HashSet<>(matchedKeys.values()), new BoxEdgeEntry(fullPath, bindings, this::updateNumericValue));
    }

    private GroupedField tryBuildEnabledModeGroup(String pathPrefix, Map<String, JsonElement> entries, String query) {
        JsonElement enabled = entries.get("Enabled");
        JsonElement mode = entries.get("Mode");
        if (!isPrimitiveBoolean(enabled) || mode == null || !mode.isJsonPrimitive() || !mode.getAsJsonPrimitive().isString()) {
            return null;
        }

        List<String> inferredOptions = inferOptions(joinPath(pathPrefix, "Mode"), mode.getAsString());
        if (inferredOptions == null || inferredOptions.isEmpty()) {
            return null;
        }

        String fullPath = joinPath(pathPrefix, "ModeState");
        if (!shouldRenderPath(fullPath, query)) {
            return null;
        }

        return new GroupedField(Set.of("Enabled", "Mode"),
                new EnabledModeEntry(fullPath, joinPath(pathPrefix, "Enabled"), joinPath(pathPrefix, "Mode"), enabled.getAsBoolean(), mode.getAsString(), inferredOptions, this::updateEnabledMode));
    }

    private GroupedField tryBuildDebugToggleGroup(String pathPrefix, Map<String, JsonElement> entries, String query) {
        List<BooleanBinding> bindings = new ArrayList<>();
        for (String key : List.of("DebugMode", "ShowLogs", "Verbose")) {
            JsonElement element = entries.get(key);
            if (isPrimitiveBoolean(element)) {
                bindings.add(new BooleanBinding(humanizePath(key), joinPath(pathPrefix, key), element.getAsBoolean()));
            }
        }
        if (bindings.size() < 2) {
            return null;
        }

        String fullPath = joinPath(pathPrefix, "Debug");
        if (!shouldRenderPath(fullPath, query)) {
            return null;
        }
        Set<String> consumed = new LinkedHashSet<>();
        bindings.forEach(binding -> consumed.add(binding.path().substring(binding.path().lastIndexOf('.') + 1)));
        return new GroupedField(consumed, new BooleanClusterEntry(fullPath, "Debug controls", bindings, this::updateBooleanValue));
    }

    private GroupedField tryBuildEnableClusterGroup(String pathPrefix, Map<String, JsonElement> entries, String query) {
        List<BooleanBinding> bindings = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            if (!entry.getKey().startsWith("Enable") || entry.getKey().equals("Enabled") || !isPrimitiveBoolean(entry.getValue())) {
                continue;
            }
            if (entries.containsKey("Disable" + entry.getKey().substring("Enable".length()))) {
                continue;
            }
            bindings.add(new BooleanBinding(humanizePath(entry.getKey().substring("Enable".length())), joinPath(pathPrefix, entry.getKey()), entry.getValue().getAsBoolean()));
        }
        if (bindings.size() < 3) {
            return null;
        }

        String fullPath = joinPath(pathPrefix, "EnableGroup");
        if (!shouldRenderPath(fullPath, query)) {
            return null;
        }
        Set<String> consumed = new LinkedHashSet<>();
        bindings.forEach(binding -> consumed.add(binding.path().substring(binding.path().lastIndexOf('.') + 1)));
        return new GroupedField(consumed, new BooleanClusterEntry(fullPath, "Toggle cluster", bindings, this::updateBooleanValue));
    }

    private GroupedField tryBuildPairGroup(String pathPrefix, Map<String, JsonElement> entries, String key, String query) {
        GroupedField prefixRangeGroup = tryBuildPrefixRangeGroup(pathPrefix, entries, key, query);
        if (prefixRangeGroup != null) {
            return prefixRangeGroup;
        }

        Pattern[] patterns = {XYZ_GROUP_PATTERN, XY_GROUP_PATTERN, WH_GROUP_PATTERN, MINMAX_GROUP_PATTERN};
        String[][] components = {{"x", "y", "z"}, {"x", "y"}, {"width", "height"}, {"min", "max"}};

        for (int i = 0; i < patterns.length; i++) {
            ComponentMatch match = matchComponentKey(key, patterns[i], components[i]);
            if (match == null || match.base().isBlank()) continue;

            Map<String, String> matchedKeys = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                if (!isPrimitiveNumber(entry.getValue())) continue;
                ComponentMatch sibling = matchComponentKey(entry.getKey(), patterns[i], components[i]);
                if (sibling == null || !sibling.base().equals(match.base())) continue;
                matchedKeys.put(sibling.component().toLowerCase(Locale.ROOT), entry.getKey());
            }

            if (!matchedKeys.keySet().containsAll(List.of(components[i]))) {
                continue;
            }

            String fullPath = joinPath(pathPrefix, match.base());
            if (!shouldRenderPath(fullPath, query)) {
                return null;
            }

            List<ComponentBinding> bindings = new ArrayList<>();
            for (String component : components[i]) {
                String originalKey = matchedKeys.get(component);
                String componentPath = joinPath(pathPrefix, originalKey);
                bindings.add(new ComponentBinding(component.substring(0, 1).toUpperCase(Locale.ROOT) + component.substring(1), componentPath, entries.get(originalKey).getAsDouble()));
            }
            if (patterns[i] == MINMAX_GROUP_PATTERN) {
                return new GroupedField(new HashSet<>(matchedKeys.values()), new RangeEntry(fullPath, bindings, this::updateNumericValue));
            }
            if (patterns[i] == WH_GROUP_PATTERN) {
                return new GroupedField(new HashSet<>(matchedKeys.values()), new SizeEntry(fullPath, bindings, this::updateNumericValue));
            }
            return new GroupedField(new HashSet<>(matchedKeys.values()), new NumericPairEntry(fullPath, bindings, this::updateNumericValue));
        }

        return null;
    }

    private GroupedField tryBuildPrefixRangeGroup(String pathPrefix, Map<String, JsonElement> entries, String key, String query) {
        PrefixComponentMatch minMatch = matchPrefixedComponentKey(key, "min");
        PrefixComponentMatch maxMatch = matchPrefixedComponentKey(key, "max");
        PrefixComponentMatch match = minMatch != null ? minMatch : maxMatch;
        if (match == null || match.base().isBlank()) {
            return null;
        }

        String minKey = null;
        String maxKey = null;
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            if (!isPrimitiveNumber(entry.getValue())) {
                continue;
            }
            PrefixComponentMatch siblingMin = matchPrefixedComponentKey(entry.getKey(), "min");
            if (siblingMin != null && siblingMin.base().equalsIgnoreCase(match.base())) {
                minKey = entry.getKey();
            }
            PrefixComponentMatch siblingMax = matchPrefixedComponentKey(entry.getKey(), "max");
            if (siblingMax != null && siblingMax.base().equalsIgnoreCase(match.base())) {
                maxKey = entry.getKey();
            }
        }

        if (minKey == null || maxKey == null) {
            return null;
        }

        String fullPath = joinPath(pathPrefix, match.base());
        if (!shouldRenderPath(fullPath, query)) {
            return null;
        }

        List<ComponentBinding> bindings = new ArrayList<>();
        bindings.add(new ComponentBinding("Min", joinPath(pathPrefix, minKey), entries.get(minKey).getAsDouble()));
        bindings.add(new ComponentBinding("Max", joinPath(pathPrefix, maxKey), entries.get(maxKey).getAsDouble()));
        return new GroupedField(Set.of(minKey, maxKey), new RangeEntry(fullPath, bindings, this::updateNumericValue));
    }

    private GroupedField tryBuildToggleValueGroup(String pathPrefix, Map<String, JsonElement> entries, String key, String query) {
        JsonElement element = entries.get(key);
        if (!isPrimitiveBoolean(element)) {
            return null;
        }

        String normalizedKey = key.toLowerCase(Locale.ROOT);
        String base = null;
        if (normalizedKey.startsWith("enable") && key.length() > 6) {
            base = key.substring(6);
        } else if (normalizedKey.startsWith("use") && key.length() > 3) {
            base = key.substring(3);
        } else if (normalizedKey.startsWith("visible")) {
            base = "Opacity";
        }

        if (base == null || base.isBlank()) {
            return null;
        }

        String dependentKey = null;
        JsonElement dependentValue = null;
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            if (entry.getKey().equals(key) || entry.getValue() == null || entry.getValue().isJsonNull() || entry.getValue().isJsonObject() || entry.getValue().isJsonArray()) {
                continue;
            }
            String sibling = entry.getKey();
            String normalizedSibling = sibling.toLowerCase(Locale.ROOT);
            if (normalizedSibling.equals(normalizedKey)) {
                continue;
            }
            if (normalizedSibling.startsWith(base.toLowerCase(Locale.ROOT)) || ("opacity".equalsIgnoreCase(base) && normalizedSibling.contains("opacity"))) {
                dependentKey = sibling;
                dependentValue = entry.getValue();
                break;
            }
        }

        if (dependentKey == null || dependentValue == null || dependentValue.isJsonPrimitive() && dependentValue.getAsJsonPrimitive().isBoolean()) {
            return null;
        }

        String fullPath = joinPath(pathPrefix, key);
        String dependentPath = joinPath(pathPrefix, dependentKey);
        if (!shouldRenderPath(joinPath(pathPrefix, base), query)) {
            return null;
        }

        return new GroupedField(Set.of(key, dependentKey), new ToggleValueEntry(joinPath(pathPrefix, base), fullPath, dependentPath, element.getAsBoolean(), dependentValue, this::updateValue));
    }

    private GroupedField tryBuildSingleColorEntry(String pathPrefix, String key, JsonElement value, String query) {
        String fullPath = joinPath(pathPrefix, key);
        if (!shouldRenderPath(fullPath, query)) {
            return null;
        }
        return new GroupedField(Set.of(key), new SingleColorEntry(fullPath, value, newValue -> updateValue(fullPath, newValue)));
    }

    private GroupedField tryBuildBooleanPairGroup(String pathPrefix, Map<String, JsonElement> entries, String key, String query) {
        ComponentMatch match = matchComponentKey(key, ENABLE_DISABLE_PATTERN, "enable", "enabled", "disable", "disabled");
        if (match == null || match.base().isBlank()) {
            return null;
        }

        String enableKey = null;
        String disableKey = null;
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            if (!isPrimitiveBoolean(entry.getValue())) {
                continue;
            }
            ComponentMatch sibling = matchComponentKey(entry.getKey(), ENABLE_DISABLE_PATTERN, "enable", "enabled", "disable", "disabled");
            if (sibling == null || !sibling.base().equals(match.base())) {
                continue;
            }
            if (sibling.component().startsWith("enable")) {
                enableKey = entry.getKey();
            } else {
                disableKey = entry.getKey();
            }
        }

        if (enableKey == null || disableKey == null) {
            return null;
        }

        String fullPath = joinPath(pathPrefix, match.base());
        if (!shouldRenderPath(fullPath, query)) {
            return null;
        }

        boolean enabled = entries.get(enableKey).getAsBoolean() && !entries.get(disableKey).getAsBoolean();
        return new GroupedField(Set.of(enableKey, disableKey), new BooleanPairEntry(fullPath, enabled, joinPath(pathPrefix, enableKey), joinPath(pathPrefix, disableKey), this::updateBooleanPair));
    }

    private GroupedField tryBuildTriStateGroup(String pathPrefix, Map<String, JsonElement> entries, String key, String query) {
        PrefixComponentMatch autoMatch = matchPrefixedComponentKey(key, "auto");
        PrefixComponentMatch enableMatch = matchPrefixedComponentKey(key, "enable");
        PrefixComponentMatch disableMatch = matchPrefixedComponentKey(key, "disable");
        PrefixComponentMatch match = autoMatch != null ? autoMatch : (enableMatch != null ? enableMatch : disableMatch);
        if (match == null || match.base().isBlank()) {
            return null;
        }

        String autoKey = null;
        String enableKey = null;
        String disableKey = null;
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            if (!isPrimitiveBoolean(entry.getValue())) {
                continue;
            }
            PrefixComponentMatch siblingAuto = matchPrefixedComponentKey(entry.getKey(), "auto");
            if (siblingAuto != null && siblingAuto.base().equalsIgnoreCase(match.base())) {
                autoKey = entry.getKey();
            }
            PrefixComponentMatch siblingEnable = matchPrefixedComponentKey(entry.getKey(), "enable");
            if (siblingEnable != null && siblingEnable.base().equalsIgnoreCase(match.base())) {
                enableKey = entry.getKey();
            }
            PrefixComponentMatch siblingDisable = matchPrefixedComponentKey(entry.getKey(), "disable");
            if (siblingDisable != null && siblingDisable.base().equalsIgnoreCase(match.base())) {
                disableKey = entry.getKey();
            }
        }

        if (autoKey == null || enableKey == null || disableKey == null) {
            return null;
        }

        String fullPath = joinPath(pathPrefix, match.base());
        if (!shouldRenderPath(fullPath, query)) {
            return null;
        }

        TriStateMode mode = entries.get(autoKey).getAsBoolean()
                ? TriStateMode.AUTO
                : (entries.get(enableKey).getAsBoolean() && !entries.get(disableKey).getAsBoolean() ? TriStateMode.ON : TriStateMode.OFF);
        return new GroupedField(Set.of(autoKey, enableKey, disableKey), new TriStateEntry(fullPath, joinPath(pathPrefix, autoKey), joinPath(pathPrefix, enableKey), joinPath(pathPrefix, disableKey), mode, this::updateTriState));
    }

    private void updateBooleanPair(String enablePath, String disablePath, boolean enabled) {
        updateValue(enablePath, new JsonPrimitive(enabled));
        updateValue(disablePath, new JsonPrimitive(!enabled));
    }

    private void updateTriState(String autoPath, String enablePath, String disablePath, TriStateMode mode) {
        switch (mode) {
            case AUTO -> {
                updateValue(autoPath, new JsonPrimitive(true));
                updateValue(enablePath, new JsonPrimitive(false));
                updateValue(disablePath, new JsonPrimitive(false));
            }
            case ON -> {
                updateValue(autoPath, new JsonPrimitive(false));
                updateValue(enablePath, new JsonPrimitive(true));
                updateValue(disablePath, new JsonPrimitive(false));
            }
            case OFF -> {
                updateValue(autoPath, new JsonPrimitive(false));
                updateValue(enablePath, new JsonPrimitive(false));
                updateValue(disablePath, new JsonPrimitive(true));
            }
        }
    }

    private void updateEnabledMode(String enabledPath, String modePath, boolean enabled, String mode) {
        updateValue(enabledPath, new JsonPrimitive(enabled));
        updateValue(modePath, new JsonPrimitive(mode));
    }

    private void updateBooleanValue(String path, boolean value) {
        updateValue(path, new JsonPrimitive(value));
    }

    private void updateNumericValue(String path, double value, boolean wholeNumber) {
        updateValue(path, wholeNumber ? new JsonPrimitive((int) Math.round(value)) : new JsonPrimitive(value));
    }

    private boolean isPrimitiveBoolean(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean();
    }

    private boolean isPrimitiveNumber(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    private boolean isPrimitiveArray(JsonArray array) {
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive()) {
                return false;
            }
        }
        return true;
    }

    private boolean isInternalLinkedConfigKey(String key) {
        return "rectconfig".equalsIgnoreCase(key);
    }

    private boolean isPrimitiveMapObject(JsonObject object) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) {
                return false;
            }
        }
        return object.size() > 0;
    }

    private boolean shouldRenderAsMappingTable(String path, JsonObject object) {
        String normalizedPath = path.toLowerCase(Locale.ROOT);
        return normalizedPath.contains("mapping")
                || normalizedPath.contains("map")
                || normalizedPath.contains("table");
    }

    private boolean looksLikeSingleColor(String path, JsonElement element) {
        String normalizedPath = path.toLowerCase(Locale.ROOT);
        if (!(hasDirectColorNaming(normalizedPath)
                || normalizedPath.contains("tint")
                || normalizedPath.contains("accent")
                || normalizedPath.contains("highlight")
                || normalizedPath.contains("outline")
                || normalizedPath.contains("background")
                || normalizedPath.contains("foreground"))) {
            return false;
        }
        if (!element.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isString()) {
            return HEX_COLOR_PATTERN.matcher(primitive.getAsString().trim()).matches();
        }
        if (primitive.isNumber()) {
            long value = primitive.getAsLong();
            return value >= 0 && value <= 0xFFFFFFFFL;
        }
        return false;
    }

    private boolean hasDirectColorNaming(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return false;
        }
        return normalizedPath.contains("color")
                || normalizedPath.endsWith("rgba")
                || normalizedPath.endsWith(".rgba")
                || normalizedPath.endsWith("_rgba")
                || normalizedPath.endsWith("rgb")
                || normalizedPath.endsWith(".rgb")
                || normalizedPath.endsWith("_rgb")
                || normalizedPath.endsWith("argb")
                || normalizedPath.endsWith(".argb")
                || normalizedPath.endsWith("_argb")
                || normalizedPath.endsWith("abgr")
                || normalizedPath.endsWith(".abgr")
                || normalizedPath.endsWith("_abgr");
    }

    private List<String> inferOptions(String path, String currentValue) {
        if (currentValue == null || currentValue.isBlank()) {
            return null;
        }

        String normalizedPath = path.toLowerCase(Locale.ROOT);
        String normalizedValue = currentValue.toLowerCase(Locale.ROOT);
        for (OptionFamily family : OPTION_FAMILIES) {
            if (!family.matchesPath(normalizedPath) || !family.values().contains(normalizedValue)) {
                continue;
            }
            List<String> formattedValues = new ArrayList<>();
            for (String value : family.values()) {
                formattedValues.add(applyValueStyle(currentValue, value));
            }
            return formattedValues;
        }
        return null;
    }

    private boolean isUrlValue(String raw) {
        return raw.startsWith("http://") || raw.startsWith("https://");
    }

    private boolean isPathValue(String path, String raw) {
        String normalizedPath = path.toLowerCase(Locale.ROOT);
        String normalizedRaw = raw.toLowerCase(Locale.ROOT);
        return normalizedPath.contains("path")
                || normalizedPath.contains("folder")
                || normalizedPath.contains("file")
                || normalizedRaw.contains("/")
                || normalizedRaw.endsWith(".png")
                || normalizedRaw.endsWith(".ogg")
                || normalizedRaw.endsWith(".json")
                || normalizedRaw.endsWith(".ttf");
    }

    private boolean isRegistryIdPath(String path) {
        String normalizedPath = path.toLowerCase(Locale.ROOT);
        return normalizedPath.endsWith("itemid")
                || normalizedPath.endsWith("blockid")
                || normalizedPath.endsWith("entityid")
                || normalizedPath.endsWith("effectid")
                || normalizedPath.endsWith("particleid")
                || normalizedPath.endsWith("soundid")
                || normalizedPath.contains(".itemid")
                || normalizedPath.contains(".blockid")
                || normalizedPath.contains(".entityid")
                || normalizedPath.contains(".effectid")
                || normalizedPath.contains(".particleid")
                || normalizedPath.contains(".soundid");
    }

    private boolean isVersionStringPath(String path, String raw) {
        String normalizedPath = path.toLowerCase(Locale.ROOT);
        return normalizedPath.contains("version") || raw.matches("v?\\d+(?:\\.\\d+)+(?:[-+._a-zA-Z0-9]*)?");
    }

    private boolean isKeybindValue(String path, String raw) {
        String normalizedPath = path.toLowerCase(Locale.ROOT);
        return normalizedPath.contains("keybind")
                || normalizedPath.contains("shortcut")
                || normalizedPath.contains("hotkey")
                || KEYBIND_VALUE_PATTERN.matcher(raw.toUpperCase(Locale.ROOT)).matches();
    }

    private boolean isLongTextValue(String path, String raw) {
        String normalizedPath = path.toLowerCase(Locale.ROOT);
        return raw.length() > 90
                || normalizedPath.contains("message")
                || normalizedPath.contains("notes")
                || normalizedPath.contains("description");
    }

    private boolean isCoordinateString(String raw) {
        return raw != null && raw.trim().matches("-?\\d+(?:\\.\\d+)?\\s*,\\s*-?\\d+(?:\\.\\d+)?(?:\\s*,\\s*-?\\d+(?:\\.\\d+)?)?");
    }

    private boolean isRangeString(String raw) {
        return raw != null && raw.trim().matches("-?\\d+(?:\\.\\d+)?\\s*-\\s*-?\\d+(?:\\.\\d+)?");
    }

    private boolean isSeedValue(String path) {
        return path.toLowerCase(Locale.ROOT).contains("seed");
    }

    private boolean isTimeLikePath(String path) {
        String normalizedPath = path.toLowerCase(Locale.ROOT);
        return normalizedPath.contains("delay")
                || normalizedPath.contains("cooldown")
                || normalizedPath.contains("timeout")
                || normalizedPath.contains("duration")
                || normalizedPath.contains("tick")
                || normalizedPath.contains("rate");
    }

    private SliderConfig inferSliderConfig(String path, double currentValue) {
        String normalizedPath = path.toLowerCase(Locale.ROOT);
        if (normalizedPath.contains("opacity") || normalizedPath.contains("volume") || normalizedPath.contains("brightness") || normalizedPath.contains("chance") || normalizedPath.contains("probability")) {
            if (currentValue >= 0.0 && currentValue <= 1.0) {
                return new SliderConfig(0.0, 1.0, 0.01, false, "%");
            }
            if (currentValue >= 0.0 && currentValue <= 100.0) {
                return new SliderConfig(0.0, 100.0, 1.0, true, "%");
            }
        }
        if (normalizedPath.contains("scale") || normalizedPath.contains("multiplier") || normalizedPath.contains("zoom")) {
            return new SliderConfig(0.0, Math.max(10.0, Math.ceil(currentValue * 2.0)), 0.1, false, "x");
        }
        if (normalizedPath.contains("rotation") || normalizedPath.contains("angle")) {
            return new SliderConfig(0.0, 360.0, 1.0, true, "deg");
        }
        if (normalizedPath.contains("hue")) {
            return new SliderConfig(0.0, 360.0, 1.0, true, "deg");
        }
        if (normalizedPath.contains("saturation") || normalizedPath.contains("brightness") || normalizedPath.contains("opacity")) {
            return new SliderConfig(0.0, 100.0, 1.0, true, "%");
        }
        if (normalizedPath.contains("contrast")) {
            return new SliderConfig(-100.0, 100.0, 1.0, true, "");
        }
        if (normalizedPath.contains("gamma")) {
            return new SliderConfig(0.0, Math.max(4.0, Math.ceil(currentValue + 1.0)), 0.1, false, "");
        }
        if (normalizedPath.contains("temperature")) {
            return new SliderConfig(1000.0, 10000.0, 100.0, true, "K");
        }
        if (normalizedPath.contains("sensitivity") || normalizedPath.contains("speed")) {
            if (currentValue >= 0.0 && currentValue <= 1.0) {
                return new SliderConfig(0.0, 1.0, 0.01, false, "");
            }
            if (currentValue >= 0.0 && currentValue <= 100.0) {
                return new SliderConfig(0.0, 100.0, 1.0, true, "");
            }
        }
        if (normalizedPath.contains("grid") || normalizedPath.contains("snap")) {
            return new SliderConfig(0.0, Math.max(128.0, Math.ceil(currentValue * 2.0)), 1.0, true, "px");
        }
        if (normalizedPath.contains("threshold") || normalizedPath.contains("limit") || normalizedPath.contains("cutoff")) {
            if (currentValue >= 0.0 && currentValue <= 1.0) {
                return new SliderConfig(0.0, 1.0, 0.01, false, "");
            }
            return new SliderConfig(0.0, Math.max(100.0, Math.ceil(currentValue * 1.5)), 1.0, Math.rint(currentValue) == currentValue, "");
        }
        return null;
    }

    private String applyValueStyle(String template, String value) {
        if (template.equals(template.toUpperCase(Locale.ROOT))) {
            return value.toUpperCase(Locale.ROOT);
        }
        if (template.equals(template.toLowerCase(Locale.ROOT))) {
            return value.toLowerCase(Locale.ROOT);
        }
        if (!template.isEmpty() && Character.isUpperCase(template.charAt(0))) {
            return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1).toLowerCase(Locale.ROOT);
        }
        return value;
    }

    private String joinPath(String prefix, String key) {
        return prefix == null || prefix.isBlank() ? key : prefix + "." + key;
    }

    private String buildSectionSubtitle(JsonObject object) {
        if (object == null || object.size() == 0) {
            return null;
        }
        String inherits = null;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if ("inherits".equalsIgnoreCase(entry.getKey()) && value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                inherits = value.getAsString();
            }
        }
        return inherits == null || inherits.isBlank() ? null : "inherits " + inherits;
    }

    private SectionInspectorData buildSectionInspectorData(String pathPrefix, JsonObject object) {
        if (pathPrefix == null || pathPrefix.isBlank() || object == null) {
            return new SectionInspectorData(List.of(), "{}");
        }
        List<String> chain = resolveInheritanceChain(pathPrefix, object);
        String raw = GSON.toJson(object);
        return new SectionInspectorData(chain, raw);
    }

    private List<String> resolveInheritanceChain(String pathPrefix, JsonObject object) {
        List<String> chain = new ArrayList<>();
        if (pathPrefix == null || pathPrefix.isBlank()) {
            return chain;
        }
        chain.add(humanizePath(pathPrefix));
        JsonObject cursor = object;
        String cursorPath = pathPrefix;
        Set<String> visited = new LinkedHashSet<>();
        visited.add(cursorPath);
        while (cursor != null && cursor.has("inherits") && cursor.get("inherits").isJsonPrimitive() && cursor.get("inherits").getAsJsonPrimitive().isString()) {
            String inheritedName = cursor.get("inherits").getAsString();
            String nextPath = resolveRelativeSectionPath(cursorPath, inheritedName);
            if (nextPath == null || !visited.add(nextPath)) {
                break;
            }
            chain.add(humanizePath(nextPath));
            JsonElement inheritedElement = getValueAtPath(currentDocument.getRoot(), nextPath);
            if (inheritedElement == null || !inheritedElement.isJsonObject()) {
                break;
            }
            cursorPath = nextPath;
            cursor = inheritedElement.getAsJsonObject();
        }
        return chain;
    }

    private String resolveRelativeSectionPath(String currentPath, String relativeName) {
        if (currentPath == null || currentPath.isBlank() || relativeName == null || relativeName.isBlank()) {
            return null;
        }
        String[] parts = currentPath.split("\\.");
        for (int i = parts.length - 2; i >= 0; i--) {
            if (parts[i].equalsIgnoreCase(relativeName)) {
                return String.join(".", Arrays.copyOfRange(parts, 0, i + 1));
            }
        }
        return relativeName.contains(".") ? relativeName : null;
    }

    private String extractFeaturePrefix(String key) {
        for (String suffix : FEATURE_SUFFIXES) {
            if (key.length() > suffix.length() && key.endsWith(suffix)) {
                String prefix = key.substring(0, key.length() - suffix.length());
                if (!prefix.isBlank()) {
                    return prefix;
                }
            }
        }
        return null;
    }

    private String findFirstKey(List<String> keys, String... suffixes) {
        for (String key : keys) {
            for (String suffix : suffixes) {
                if (key.endsWith(suffix)) {
                    return key;
                }
            }
        }
        return null;
    }

    private static String formatCompactNumber(double value, boolean wholeNumber) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        if (wholeNumber) {
            return String.format(Locale.ROOT, "%,d", (long) Math.round(value));
        }
        return String.format(Locale.ROOT, "%,.2f", value);
    }

    private String normalizeColorComponent(String component) {
        return switch (component.toLowerCase(Locale.ROOT)) {
            case "red", "r" -> "r";
            case "green", "g" -> "g";
            case "blue", "b" -> "b";
            case "alpha", "a" -> "a";
            default -> component.toLowerCase(Locale.ROOT);
        };
    }

    private ComponentMatch matchComponentKey(String key, Pattern pattern, String... allowed) {
        Matcher matcher = pattern.matcher(key);
        if (!matcher.matches()) {
            return null;
        }

        String base = matcher.group(1);
        String component = matcher.group(2).toLowerCase(Locale.ROOT);
        boolean allowedComponent = false;
        for (String allowedValue : allowed) {
            if (allowedValue.equalsIgnoreCase(component)) {
                allowedComponent = true;
                break;
            }
        }
        if (!allowedComponent) {
            return null;
        }

        if (base == null || base.isBlank()) {
            return null;
        }

        char suffixStart = key.charAt(key.length() - component.length());
        boolean separated = key.contains("_") || Character.isUpperCase(suffixStart);
        if (!separated && component.length() == 1) {
            return null;
        }

        return new ComponentMatch(base, component);
    }

    private PrefixComponentMatch matchPrefixedComponentKey(String key, String prefix) {
        if (!key.regionMatches(true, 0, prefix, 0, prefix.length()) || key.length() <= prefix.length()) {
            return null;
        }
        String base = key.substring(prefix.length());
        if (base.isBlank()) {
            return null;
        }
        return new PrefixComponentMatch(base, prefix.toLowerCase(Locale.ROOT));
    }

    private void updateValue(String path, JsonElement value) {
        if (currentDocument == null) {
            return;
        }

        currentDocument.setValue(path, value);
        JsonElement savedValue = getValueFromSnapshot(savedDocumentSnapshot, path);
        JsonElement currentValue = getValueAtPath(currentDocument.getRoot(), path);
        if (savedValue == null ? currentValue != null : !savedValue.equals(currentValue)) {
            changedPaths.add(path);
        } else {
            changedPaths.remove(path);
        }
        invalidateSidebarDerivedCaches();
        dirty = !changedPaths.isEmpty();
        statusMessage = dirty ? "Edited" : "Saved";
        statusColor = dirty ? new Color(uiColorHeaderSubTitleText, true).getRGB() : new Color(uiColorContentBaseDescriptionText, true).getRGB();
        statusUntil = dirty ? (Util.getMeasuringTimeMs() + 1200L) : 0L;
    }

    private JsonElement getValueFromSnapshot(String snapshot, String path) {
        try {
            return getValueAtPath(JsonParser.parseString(snapshot).getAsJsonObject(), path);
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonElement getValueAtPath(JsonObject root, String path) {
        String[] parts = path.split("\\.");
        JsonElement current = root;
        for (String part : parts) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            JsonObject object = current.getAsJsonObject();
            if (!object.has(part)) {
                return null;
            }
            current = object.get(part);
        }
        return current;
    }

    private JsonElement parseJsonValue(String raw) {
        try {
            return JsonParser.parseString(raw);
        } catch (Exception ignored) {
            return new JsonPrimitive(raw);
        }
    }

    private static String formatKeyName(int keyCode, int modifiers) {
        List<String> parts = new ArrayList<>();
        if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            parts.add("CTRL");
        }
        if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
            parts.add("SHIFT");
        }
        if ((modifiers & GLFW.GLFW_MOD_ALT) != 0) {
            parts.add("ALT");
        }
        if ((modifiers & GLFW.GLFW_MOD_SUPER) != 0) {
            parts.add("META");
        }
        String keyName = GLFW.glfwGetKeyName(keyCode, 0);
        if (keyName == null || keyName.isBlank()) {
            keyName = switch (keyCode) {
                case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RIGHT_SHIFT";
                case GLFW.GLFW_KEY_LEFT_SHIFT -> "LEFT_SHIFT";
                case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RIGHT_CTRL";
                case GLFW.GLFW_KEY_LEFT_CONTROL -> "LEFT_CTRL";
                case GLFW.GLFW_KEY_RIGHT_ALT -> "RIGHT_ALT";
                case GLFW.GLFW_KEY_LEFT_ALT -> "LEFT_ALT";
                case GLFW.GLFW_KEY_SPACE -> "SPACE";
                case GLFW.GLFW_KEY_ENTER -> "ENTER";
                case GLFW.GLFW_KEY_ESCAPE -> "ESC";
                default -> GLFW.glfwGetKeyName(keyCode, keyCode);
            };
        }
        if (keyName == null || keyName.isBlank()) {
            keyName = "KEY_" + keyCode;
        }
        parts.add(keyName.toUpperCase(Locale.ROOT));
        return String.join("+", parts);
    }

    private static boolean isMouseOverField(TextFieldWidget field, double mouseX, double mouseY) {
        return mouseX >= field.getX()
                && mouseX <= field.getX() + field.getWidth()
                && mouseY >= field.getY()
                && mouseY <= field.getY() + field.getHeight();
    }

    private static boolean focusClickedField(double mouseX, double mouseY, int button, TextFieldWidget... fields) {
        TextFieldWidget clickedField = null;
        for (TextFieldWidget field : fields) {
            if (isMouseOverField(field, mouseX, mouseY)) {
                clickedField = field;
                break;
            }
        }
        if (clickedField == null) {
            return false;
        }
        for (TextFieldWidget field : fields) {
            field.setFocused(field == clickedField);
        }
        clickedField.mouseClicked(mouseX, mouseY, button);
        return true;
    }

    private static boolean nudgeNumericTextField(TextFieldWidget field, double delta, boolean wholeNumber) {
        try {
            double current = Double.parseDouble(field.getText().trim());
            double next = wholeNumber ? Math.round(current + delta) : current + delta;
            if (wholeNumber) {
                field.setText(String.valueOf((int) Math.round(next)));
            } else {
                field.setText(String.format(Locale.ROOT, "%.3f", next));
            }
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean textLooksWholeNumber(TextFieldWidget field) {
        String raw = field.getText().trim();
        return !raw.contains(".") && !raw.contains("e") && !raw.contains("E");
    }

    private static String validateNumericField(TextFieldWidget field, String label, boolean wholeNumber) {
        String raw = field.getText().trim();
        if (raw.isEmpty()) {
            return label + " is empty";
        }
        try {
            double value = Double.parseDouble(raw);
            if (wholeNumber && Math.rint(value) != value) {
                return label + " must be a whole number";
            }
            return null;
        } catch (NumberFormatException ignored) {
            return label + " must be a valid number";
        }
    }

    private static Boolean parseBooleanText(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "on", "yes", "enabled", "1" -> true;
            case "false", "off", "no", "disabled", "0" -> false;
            default -> null;
        };
    }

    private static class ConfigEntryListWidget {
        private static final int SECTION_ROW_HEIGHT = 26;
        private final MinecraftClient client;
        private final int top;
        private final int bottom;
        private final int itemHeight;
        private final List<ConfigEntry> entries = new ArrayList<>();
        private final Map<ConfigEntry, EntryRenderBounds> renderBounds = new LinkedHashMap<>();
        private List<SectionBlock> cachedSectionBlocks = List.of();
        private boolean sectionBlocksDirty = true;
        private int[] cachedEntryHeights = new int[0];
        private int[] cachedEntryOffsets = new int[0];
        private int cachedContentHeight = 0;
        private boolean layoutMetricsDirty = true;
        private int rowLeft = 190;
        private int rowWidth = 400;
        private double scrollAmount;
        private ConfigEntry focusedEntry;

        public ConfigEntryListWidget(MinecraftClient client, int width, int height, int top, int bottom, int entryHeight) {
            this.client = client;
            this.top = top;
            this.bottom = bottom;
            this.itemHeight = entryHeight;
        }

        public void updateLayout(int rowLeft, int rowWidth) {
            this.rowLeft = rowLeft;
            this.rowWidth = rowWidth;
        }

        public int getRowLeft() {
            return rowLeft;
        }

        public int getRowWidth() {
            return rowWidth;
        }

        protected int getScrollbarPositionX() {
            return rowLeft + rowWidth - 6;
        }

        public void addConfigEntry(ConfigEntry entry) {
            this.entries.add(entry);
            this.sectionBlocksDirty = true;
            this.layoutMetricsDirty = true;
        }

        public void clearConfigEntries() {
            this.entries.clear();
            this.renderBounds.clear();
            this.cachedSectionBlocks = List.of();
            this.cachedEntryHeights = new int[0];
            this.cachedEntryOffsets = new int[0];
            this.cachedContentHeight = 0;
            this.sectionBlocksDirty = true;
            this.layoutMetricsDirty = true;
            this.focusedEntry = null;
            this.scrollAmount = 0.0;
        }

        public void scrollToIndex(int index) {
            this.setScrollAmount(getScrollOffsetForIndex(index));
        }

        public List<String> findDeleteTargets(double mouseX, double mouseY) {
            for (int index = 0; index < entries.size(); index++) {
                ConfigEntry entry = entries.get(index);
                int entryTop = getRowTop(index);
                int entryBottom = entryTop + getEntryHeight(index);
                EntryRenderBounds bounds = renderBounds.get(entry);
                int hitX = bounds == null ? this.getRowLeft() : bounds.x();
                int hitWidth = bounds == null ? this.getRowWidth() : bounds.width();
                if (mouseY >= entryTop && mouseY < entryBottom && entry.matchesDeleteButton(mouseX, mouseY, hitX, entryTop, hitWidth, getEntryHeight(index))) {
                    return entry.getDeleteTargets();
                }
            }
            return List.of();
        }

        public List<String> findResetTargets(double mouseX, double mouseY) {
            for (int index = 0; index < entries.size(); index++) {
                ConfigEntry entry = entries.get(index);
                int entryTop = getRowTop(index);
                int entryBottom = entryTop + getEntryHeight(index);
                EntryRenderBounds bounds = renderBounds.get(entry);
                int hitX = bounds == null ? this.getRowLeft() : bounds.x();
                int hitWidth = bounds == null ? this.getRowWidth() : bounds.width();
                if (mouseY >= entryTop && mouseY < entryBottom && entry.matchesResetButton(mouseX, mouseY, hitX, entryTop, hitWidth, getEntryHeight(index))) {
                    return entry.getDeleteTargets();
                }
            }
            return List.of();
        }

        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!isWithinViewport(mouseX, mouseY)) {
                unfocusAllExcept(null);
                return false;
            }
            for (int index = 0; index < entries.size(); index++) {
                ConfigEntry entry = entries.get(index);
                int entryTop = getRowTop(index);
                int entryBottom = entryTop + getEntryHeight(index);

                if (mouseY >= entryTop && mouseY < entryBottom) {
                    if (entry.mouseClicked(mouseX, mouseY, button)) {
                        this.setFocused(entry);
                        unfocusAllExcept(entry);
                        return true;
                    }
                }
            }
            unfocusAllExcept(null);
            return false;
        }

        public void clearFocus() {
            unfocusAllExcept(null);
        }

        public boolean hasOpenOverlay() {
            for (ConfigEntry entry : entries) {
                if (entry.hasModalOverlay()) {
                    return true;
                }
            }
            return false;
        }

        public boolean handleOverlayClick(double mouseX, double mouseY, int button) {
            boolean overlayOpen = false;
            for (ConfigEntry entry : entries) {
                if (!entry.hasModalOverlay()) {
                    continue;
                }
                overlayOpen = true;
                if (entry.handleOverlayClick(mouseX, mouseY, button)) {
                    return true;
                }
            }
            return overlayOpen;
        }

        private void unfocusAllExcept(ConfigEntry focusedEntry) {
            this.focusedEntry = focusedEntry;
            for (ConfigEntry entry : this.entries) {
                entry.setFocusedState(entry == focusedEntry);
                entry.setFocused(entry == focusedEntry);
            }
        }

        public int getFocusedIndex() {
            return focusedEntry == null ? -1 : entries.indexOf(focusedEntry);
        }

        public boolean moveFocus(int delta) {
            if (entries.isEmpty()) {
                return false;
            }
            int start = getFocusedIndex();
            int direction = delta >= 0 ? 1 : -1;
            int target = start < 0 ? (direction > 0 ? 0 : entries.size() - 1) : start + direction;
            while (target >= 0 && target < entries.size()) {
                ConfigEntry entry = entries.get(target);
                if (entry.isKeyboardFocusable()) {
                    unfocusAllExcept(entry);
                    ensureVisible(target);
                    return true;
                }
                target += direction;
            }
            return start >= 0;
        }

        private void ensureVisible(int index) {
            int entryTop = getRowTop(index);
            int entryBottom = entryTop + getEntryHeight(index);
            if (entryTop < top) {
                setScrollAmount(getScrollOffsetForIndex(index));
            } else if (entryBottom > bottom) {
                setScrollAmount(getScrollOffsetForIndex(index) + getEntryHeight(index) - (bottom - top));
            }
        }

        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            boolean overlayOpen = hasOpenOverlay();
            for (ConfigEntry entry : this.entries) {
                if (entry.handleMouseRelease(mouseX, mouseY, button) || entry.mouseReleased(mouseX, mouseY, button)) {
                    return true;
                }
            }
            return overlayOpen;
        }

        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            boolean overlayOpen = hasOpenOverlay();
            if (overlayOpen) {
                for (ConfigEntry entry : this.entries) {
                    if (entry.handleMouseDrag(mouseX, mouseY, button, deltaX, deltaY)) {
                        return true;
                    }
                }
                return true;
            }
            if (!isWithinViewport(mouseX, mouseY) && focusedEntry == null) {
                return false;
            }
            for (ConfigEntry entry : this.entries) {
                if (entry.handleMouseDrag(mouseX, mouseY, button, deltaX, deltaY)) {
                    return true;
                }
            }
            return false;
        }

        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            for (ConfigEntry entry : this.entries) {
                if (entry.isFocused() && isEntryVisible(entry) && entry.keyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }
            }
            return false;
        }

        public boolean charTyped(char chr, int modifiers) {
            for (ConfigEntry entry : this.entries) {
                if (entry.isFocused() && isEntryVisible(entry) && entry.charTyped(chr, modifiers)) {
                    return true;
                }
            }
            return false;
        }

        public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
            if (hasOpenOverlay()) {
                return handleOverlayScroll(mouseX, mouseY, amount);
            }
            if (!isWithinViewport(mouseX, mouseY)) {
                return false;
            }
            for (int index = 0; index < entries.size(); index++) {
                ConfigEntry entry = entries.get(index);
                if (!entry.supportsMouseWheelInput()) {
                    continue;
                }
                int entryTop = getRowTop(index);
                int entryBottom = entryTop + getEntryHeight(index);
                if (mouseY < entryTop || mouseY >= entryBottom) {
                    continue;
                }
                EntryRenderBounds bounds = renderBounds.get(entry);
                int hitX = bounds == null ? this.getRowLeft() : bounds.x();
                int hitWidth = bounds == null ? this.getRowWidth() : bounds.width();
                if (mouseX >= hitX && mouseX <= hitX + hitWidth && entry.handleMouseWheel(mouseX, mouseY, amount)) {
                    return true;
                }
            }
            int max = Math.max(0, getContentHeight() - (bottom - top));
            if (max <= 0) {
                return false;
            }
            scrollAmount = Math.max(0, Math.min(max, scrollAmount + (amount < 0 ? 20 : -20)));
            return true;
        }

        public boolean handleOverlayScroll(double mouseX, double mouseY, double amount) {
            boolean overlayOpen = false;
            for (ConfigEntry entry : entries) {
                if (!entry.hasModalOverlay()) {
                    continue;
                }
                overlayOpen = true;
                if (entry.supportsMouseWheelInput() && entry.handleMouseWheel(mouseX, mouseY, amount)) {
                    return true;
                }
            }
            return overlayOpen;
        }

        private boolean isEntryVisible(ConfigEntry entry) {
            int index = this.entries.indexOf(entry);
            int entryTop = this.getRowTop(index);
            int entryBottom = entryTop + getEntryHeight(index);
            return entryBottom > this.top && entryTop < this.bottom;
        }

        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            int viewportHeight = bottom - top;
            int contentHeight = getContentHeight();
            int visibleBottom = bottom;
            int viewportLeft = rowLeft + 4;
            int viewportRight = rowLeft + rowWidth - 8;
            int editorLeft = viewportLeft + 4;
            int editorRight = viewportRight - 4;
            renderBounds.clear();
            String hoveredValidationError = null;
            List<SectionBlock> blocks = getSectionBlocks();
            int blockIndex = 0;
            SectionBlock currentBlock = blocks.isEmpty() ? null : blocks.get(0);

            for (int index = 0; index < entries.size(); index++) {
                ConfigEntry entry = entries.get(index);
                int entryTop = getRowTop(index);
                int entryBottom = entryTop + getEntryHeight(index);
                if (entryBottom < top || entryTop > visibleBottom) {
                    continue;
                }
                while (currentBlock != null && index > currentBlock.endIndex()) {
                    blockIndex++;
                    currentBlock = blockIndex < blocks.size() ? blocks.get(blockIndex) : null;
                }
                SectionBlock owner = currentBlock != null && index >= currentBlock.startIndex() && index <= currentBlock.endIndex()
                        ? currentBlock
                        : null;
                int entryX = editorLeft + 2;
                int entryWidth = editorRight - editorLeft - 4;
                if (entry instanceof SectionEntry) {
                    entryX = editorLeft + 2;
                    entryWidth = editorRight - editorLeft - 4;
                } else if (owner != null) {
                    entryX = editorLeft + 8;
                    entryWidth = editorRight - editorLeft - 16;
                }
                renderBounds.put(entry, new EntryRenderBounds(entryX, entryWidth));
                boolean hovered = mouseX >= entryX && mouseX <= entryX + entryWidth && mouseY >= entryTop && mouseY <= entryBottom;
                int entryHeight = getEntryHeight(index);
                entry.render(context, index, entryTop, entryX, entryWidth, entryHeight, mouseX, mouseY, hovered, delta);
                int accentX = entryX + 6;
                int accentY = entryTop + 2;
                int accentHeight = entryHeight - 4;
                String validationError = entry.getValidationError();
                boolean entryChanged = entryHasUnsavedChanges(entry);
                if (validationError != null) {
                    context.fill(accentX, accentY, accentX + 4, accentY + accentHeight, new Color(uiColorConfigValidationError, true).getRGB());
                    if (mouseX >= accentX - 2 && mouseX <= accentX + 8 && mouseY >= accentY && mouseY <= accentY + accentHeight) {
                        hoveredValidationError = validationError;
                    }
                } else if (entryChanged) {
                    context.fill(accentX, accentY, accentX + 4, accentY + accentHeight, withAlpha(uiColorWarningPromptText, 255));
                }
                if (entry == focusedEntry) {
                    int whiteX = accentX + ((validationError != null || entryChanged) ? 4 : 0);
                    context.fill(whiteX, accentY, whiteX + 4, accentY + accentHeight, new Color(uiColorConfigPickerText, true).getRGB());
                }
            }

            if (hoveredValidationError != null) {
                drawValidationTooltip(context, mouseX, mouseY, hoveredValidationError);
            }

        }

        private void drawValidationTooltip(DrawContext context, int mouseX, int mouseY, String message) {
            context.drawTooltip(MinecraftClient.getInstance().textRenderer, buildValidationTooltipLines(message), Optional.empty(), mouseX, mouseY);
        }

        public void renderOverlays(DrawContext context, int mouseX, int mouseY, float delta) {
            for (int index = 0; index < entries.size(); index++) {
                ConfigEntry entry = entries.get(index);
                if (!entry.hasModalOverlay()) {
                    continue;
                }
                EntryRenderBounds bounds = renderBounds.get(entry);
                int entryTop = getRowTop(index);
                int entryWidth = bounds == null ? this.getRowWidth() : bounds.width();
                int entryX = bounds == null ? this.getRowLeft() : bounds.x();
                entry.renderOverlay(context, index, entryTop, entryX, entryWidth, getEntryHeight(index), mouseX, mouseY, delta);
            }
        }

        private List<SectionBlock> buildSectionBlocks() {
            List<SectionBlock> blocks = new ArrayList<>();
            SectionEntry currentSection = null;
            int startIndex = -1;
            for (int i = 0; i < entries.size(); i++) {
                ConfigEntry entry = entries.get(i);
                if (entry instanceof SectionEntry sectionEntry) {
                    if (currentSection != null) {
                        blocks.add(new SectionBlock(startIndex, i - 1, currentSection.label, Math.max(0, i - startIndex - 1)));
                    }
                    currentSection = sectionEntry;
                    startIndex = i;
                }
            }
            if (currentSection != null) {
                blocks.add(new SectionBlock(startIndex, entries.size() - 1, currentSection.label, Math.max(0, entries.size() - startIndex - 1)));
            }
            return blocks;
        }

        private List<SectionBlock> getSectionBlocks() {
            if (sectionBlocksDirty) {
                cachedSectionBlocks = buildSectionBlocks();
                sectionBlocksDirty = false;
            }
            return cachedSectionBlocks;
        }

        private SectionBlock findBlockForIndex(List<SectionBlock> blocks, int index) {
            for (SectionBlock block : blocks) {
                if (index >= block.startIndex() && index <= block.endIndex()) {
                    return block;
                }
            }
            return null;
        }

        public List<ConfigEntry> children() {
            return entries;
        }

        public void setFocused(ConfigEntry entry) {
            this.focusedEntry = entry;
        }

        public ConfigEntry getFocusedEntry() {
            return focusedEntry;
        }

        public void tickVisibleEntries() {
            for (ConfigEntry entry : entries) {
                if (entry == focusedEntry || entry.hasModalOverlay() || isEntryVisible(entry)) {
                    entry.tick();
                }
            }
        }

        private boolean entryHasUnsavedChanges(ConfigEntry entry) {
            List<String> targets = entry.getDeleteTargets();
            return targets != null && !targets.isEmpty() && RESET_VISIBILITY_PREDICATE.test(targets);
        }

        public double getScrollAmount() {
            return scrollAmount;
        }

        public void setScrollAmount(double amount) {
            int max = Math.max(0, getContentHeight() - (bottom - top));
            this.scrollAmount = Math.max(0.0, Math.min(max, amount));
        }

        public String getLeadingSection() {
            List<SectionBlock> blocks = getSectionBlocks();
            for (SectionBlock block : blocks) {
                int blockBottom = getRowTop(block.endIndex()) + getEntryHeight(block.endIndex());
                if (blockBottom >= top + 26) {
                    return block.title();
                }
            }
            return blocks.isEmpty() ? null : blocks.get(0).title();
        }

        public int getRowTop(int index) {
            ensureLayoutMetrics();
            return top + (int) Math.round(getScrollOffsetForIndex(index) - scrollAmount);
        }

        private int getEntryHeight(int index) {
            ensureLayoutMetrics();
            if (index < 0 || index >= entries.size()) {
                return itemHeight;
            }
            return cachedEntryHeights[index];
        }

        private int getScrollOffsetForIndex(int index) {
            ensureLayoutMetrics();
            if (index <= 0) {
                return 0;
            }
            if (index >= cachedEntryOffsets.length) {
                return cachedContentHeight;
            }
            return cachedEntryOffsets[index];
        }

        private int getContentHeight() {
            ensureLayoutMetrics();
            return cachedContentHeight;
        }

        private void ensureLayoutMetrics() {
            if (!layoutMetricsDirty) {
                return;
            }
            int size = entries.size();
            cachedEntryHeights = new int[size];
            cachedEntryOffsets = new int[size];
            int runningOffset = 0;
            for (int i = 0; i < size; i++) {
                cachedEntryOffsets[i] = runningOffset;
                ConfigEntry entry = entries.get(i);
                int height = entry instanceof SectionEntry ? SECTION_ROW_HEIGHT : entry.getPreferredHeight(itemHeight);
                cachedEntryHeights[i] = height;
                runningOffset += height;
            }
            cachedContentHeight = runningOffset;
            layoutMetricsDirty = false;
        }

        private boolean isWithinViewport(double mouseX, double mouseY) {
            return mouseX >= rowLeft && mouseX <= rowLeft + rowWidth && mouseY >= top && mouseY <= bottom;
        }
    }

    @Environment(EnvType.CLIENT)
    private static abstract class ConfigEntry extends EntryListWidget.Entry<ConfigEntry> {
        protected static final int CONTROL_BUTTON_WIDTH = 22;
        protected static final int CONTROL_BUTTON_HEIGHT = 18;
        protected static final int PINNED_ACTION_RESERVE = 74;
        protected static final float OVERLAY_Z = 420.0F;
        private boolean focused;

        public abstract void setFocused(boolean focused);
        protected void setFocusedState(boolean focused) {
            this.focused = focused;
        }
        @Override
        public boolean isFocused() {
            return focused;
        }
        public boolean isKeyboardFocusable() {
            return true;
        }
        public boolean supportsMouseWheelInput() {
            return false;
        }
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            return false;
        }
        public boolean handleMouseDrag(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            return false;
        }
        public boolean handleMouseRelease(double mouseX, double mouseY, int button) {
            return false;
        }
        public boolean openInlineEditor() {
            return openInlineEditor(Double.NaN, Double.NaN);
        }
        public boolean openInlineEditor(double mouseX, double mouseY) {
            return false;
        }
        public boolean hasModalOverlay() {
            return false;
        }
        public boolean handleOverlayClick(double mouseX, double mouseY, int button) {
            return false;
        }
        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
            return false;
        }
        public void tick() {
        }
        public void renderOverlay(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, float delta) {
        }
        public int getPreferredHeight(int defaultHeight) {
            return isCompactConfigListingEnabledStatic() ? 72 : 94;
        }
        public List<String> getDeleteTargets() {
            return List.of();
        }
        public String getValidationError() {
            return null;
        }
        public String getPrimaryPath() {
            List<String> deleteTargets = getDeleteTargets();
            return deleteTargets.isEmpty() ? null : deleteTargets.get(0);
        }

        protected String formatLabel(String label) {
            String display = label.contains(".") ? label.substring(label.lastIndexOf('.') + 1) : label;
            display = display.replace('_', ' ').replace('-', ' ');
            display = display.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
            if (display.startsWith("No ")) {
                display = display.substring(3) + " Disabled";
            }
            if (display.isEmpty()) return label;
            return Character.toUpperCase(display.charAt(0)) + display.substring(1);
        }

        protected void drawEntryCard(DrawContext context, int x, int y, int entryWidth, int entryHeight, boolean hovered) {
            int cardLeft = x + 6;
            int cardTop = y + 2;
            int cardRight = x + entryWidth - 8;
            int cardBottom = y + entryHeight - 2;
            int accent = hovered ? withAlpha(uiColorHeaderStripe, 226) : withAlpha(uiColorHeaderStripe, 174);
            int cardFill = hovered ? withAlpha(uiColorContentBase, 92) : withAlpha(uiColorContentBase, 68);
            int border = hovered ? withAlpha(uiColorBackgroundBorder, 236) : withAlpha(uiColorBackgroundBorder, 164);
            context.fill(cardLeft, cardTop, cardRight, cardBottom, cardFill);
            context.drawBorder(cardLeft, cardTop, cardRight - cardLeft, cardBottom - cardTop, border);
            context.fill(cardLeft, cardTop, cardLeft + 4, cardBottom, accent);
        }

        protected int getTextBlockMaxWidth(int x, int entryWidth) {
            int actionLeft = x + entryWidth - PINNED_ACTION_RESERVE;
            return Math.max(84, actionLeft - x - 28);
        }

        protected void drawEntryText(DrawContext context, String label, String subtitle, int x, int y) {
            boolean compact = isCompactConfigListingEnabledStatic();
            TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
            int screenWidth = MinecraftClient.getInstance().currentScreen == null ? 420 : MinecraftClient.getInstance().currentScreen.width;
            int availableFromScreen = Math.max(84, screenWidth - x - (compact ? 190 : 240));
            int availableFromCard = getTextBlockMaxWidth(x - 18, Math.max(160, screenWidth - (x - 18) - 12));
            int maxWidth = Math.min(availableFromScreen, availableFromCard);
            String title = fitInlineText(formatLabel(label), maxWidth);
            int titleY = compact ? y + 3 : y + 4;
            context.drawText(renderer, Text.literal(title), x, titleY, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            if (!compact && subtitle != null && !subtitle.isBlank()) {
                String detail = fitInlineText(subtitle, maxWidth);
                context.drawText(renderer, Text.literal(detail), x, y + 15, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            }
        }

        protected int getControlRowY(int y, int entryHeight) {
            return y + Math.max(5, (entryHeight - CONTROL_BUTTON_HEIGHT) / 2);
        }

        protected int getFieldCaptionY(int controlY) {
            return controlY - 13;
        }

        protected int getDeleteButtonX(int x, int entryWidth) {
            return x + entryWidth - 42;
        }

        protected int getResetButtonX(int x, int entryWidth, int entryHeight) {
            int deleteX = getDeleteButtonX(x, entryWidth);
            return entryHeight <= COMPACT_ENTRY_CARD_HEIGHT ? deleteX - 20 : deleteX;
        }

        protected int getDeleteButtonY(int y, int entryHeight) {
            return getControlRowY(y, entryHeight);
        }

        protected int getResetButtonY(int y, int entryHeight) {
            int lowerBound = y + Math.max(5, entryHeight - CONTROL_BUTTON_HEIGHT - 3);
            return Math.max(y + 3, Math.min(lowerBound, getDeleteButtonY(y, entryHeight) + 20));
        }

        protected void drawDeleteButton(DrawContext context, int x, int y, int entryWidth) {
            int trashSize = 16;
            int entryHeight = getPreferredHeight(0);
            int actionX = getDeleteButtonX(x, entryWidth);
            int trashY = getDeleteButtonY(y, entryHeight);
            context.fill(actionX, trashY, actionX + trashSize, trashY + trashSize, withAlpha(uiColorWarningPromptText, 112));
            context.drawBorder(actionX, trashY, trashSize, trashSize, new Color(uiColorBackgroundBorder, true).getRGB());
            int iconX = actionX + Math.max(1, (trashSize - MinecraftClient.getInstance().textRenderer.getWidth("🗑")) / 2);
            context.drawText(MinecraftClient.getInstance().textRenderer, "🗑", iconX, trashY + 3, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            if (shouldShowResetButton()) {
                int resetX = getResetButtonX(x, entryWidth, entryHeight);
                int resetY = getResetButtonY(y, entryHeight);
                context.fill(resetX, resetY, resetX + trashSize, resetY + trashSize, withAlpha(uiColorHeader, 132));
                context.drawBorder(resetX, resetY, trashSize, trashSize, new Color(uiColorBackgroundBorder, true).getRGB());
                int resetIconX = resetX + Math.max(1, (trashSize - MinecraftClient.getInstance().textRenderer.getWidth("↺")) / 2);
                context.drawText(MinecraftClient.getInstance().textRenderer, "↺", resetIconX, resetY + 3, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            }
        }

        protected boolean shouldShowResetButton() {
            return RESET_VISIBILITY_PREDICATE.test(getDeleteTargets());
        }

        protected int getPinnedFieldStartX(int x, int entryWidth, int totalFieldWidth) {
            return x + entryWidth - PINNED_ACTION_RESERVE - totalFieldWidth - 12;
        }

        protected int getCompactFieldWidth(int entryWidth, int minWidth, int maxWidth) {
            return Math.max(minWidth, Math.min(maxWidth, entryWidth / 4));
        }

        protected int getCompactFieldX(int x, int entryWidth, int fieldWidth) {
            return getPinnedFieldStartX(x, entryWidth, fieldWidth);
        }

        protected boolean showFieldCaptions() {
            return !isCompactConfigListingEnabledStatic();
        }

        protected void drawControlButton(DrawContext context, int x, int y, int width, String label, boolean active) {
            int fill = active ? withAlpha(uiColorHeaderStripe, 176) : withAlpha(uiColorHeader, 120);
            int textColor = active ? new Color(uiColorContentBaseTitleText, true).getRGB() : new Color(uiColorIDEFileBackText, true).getRGB();
            context.fill(x, y, x + width, y + CONTROL_BUTTON_HEIGHT, fill);
            context.drawBorder(x, y, width, CONTROL_BUTTON_HEIGHT, new Color(uiColorBackgroundBorder, true).getRGB());
            int textX = x + Math.max(4, (width - MinecraftClient.getInstance().textRenderer.getWidth(label)) / 2);
            context.drawText(MinecraftClient.getInstance().textRenderer, label, textX, y + 5, textColor, false);
        }

        protected int drawAttachedPreviewBox(DrawContext context, int fieldX, int fieldY, int innerSize, boolean hovered) {
            int outerWidth = 20;
            int outerX = fieldX - outerWidth + 1;
            int outerY = fieldY - 1;
            int fill = hovered ? withAlpha(uiColorHeader, 148) : withAlpha(uiColorHeader, 120);
            context.fill(outerX, outerY, outerX + outerWidth, outerY + 20, fill);
            context.drawBorder(outerX, outerY, outerWidth, 20, new Color(uiColorBackgroundBorder, true).getRGB());
            return outerX + Math.max(1, (outerWidth - innerSize) / 2);
        }

        protected String fitInlineText(String text, int maxWidth) {
            TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
            if (text == null || maxWidth <= 0) {
                return "";
            }
            if (renderer.getWidth(text) <= maxWidth) {
                return text;
            }
            String ellipsis = "...";
            int available = maxWidth - renderer.getWidth(ellipsis);
            if (available <= 0) {
                return ellipsis;
            }
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                String next = builder.toString() + text.charAt(i);
                if (renderer.getWidth(next) > available) {
                    break;
                }
                builder.append(text.charAt(i));
            }
            return builder + ellipsis;
        }

        protected boolean isControlButtonHit(double mouseX, double mouseY, int x, int y, int width) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + CONTROL_BUTTON_HEIGHT;
        }

        public boolean matchesDeleteButton(double mouseX, double mouseY, int x, int y, int entryWidth, int entryHeight) {
            if (getDeleteTargets().isEmpty()) {
                return false;
            }
            int buttonX = getDeleteButtonX(x, entryWidth);
            int buttonY = getDeleteButtonY(y, entryHeight);
            return mouseX >= buttonX && mouseX <= buttonX + 16 && mouseY >= buttonY && mouseY <= buttonY + 16;
        }

        public boolean matchesResetButton(double mouseX, double mouseY, int x, int y, int entryWidth, int entryHeight) {
            if (getDeleteTargets().isEmpty() || !shouldShowResetButton()) {
                return false;
            }
            int buttonX = getResetButtonX(x, entryWidth, entryHeight);
            int buttonY = getResetButtonY(y, entryHeight);
            return mouseX >= buttonX && mouseX <= buttonX + 16 && mouseY >= buttonY && mouseY <= buttonY + 16;
        }
    }

    private static class SectionEntry extends ConfigEntry {
        private final String label;
        private final String subtitle;
        private final SectionInspectorData inspectorData;
        private final boolean collapsed;
        private final Runnable toggleCollapse;
        private int headerX;
        private int headerY;
        private int headerWidth;
        private int inspectButtonX;
        private boolean inspectorOpen;
        private int popoverX;
        private int popoverY;
        private int popoverWidth;
        private int popoverHeight;
        private Integer requestedPopoverX;
        private Integer requestedPopoverY;
        private int inspectorScrollOffset;

        private SectionEntry(String label, String subtitle, SectionInspectorData inspectorData, boolean collapsed, Runnable toggleCollapse) {
            this.label = label;
            this.subtitle = subtitle;
            this.inspectorData = inspectorData;
            this.collapsed = collapsed;
            this.toggleCollapse = toggleCollapse;
        }

        @Override
        public void setFocused(boolean focused) {
        }

        @Override
        public boolean isKeyboardFocusable() {
            return false;
        }

        @Override
        public String getPrimaryPath() {
            return label;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isControlButtonHit(mouseX, mouseY, inspectButtonX, headerY, 38)) {
                inspectorOpen = !inspectorOpen;
                requestedPopoverX = (int) Math.round(mouseX);
                requestedPopoverY = (int) Math.round(mouseY);
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (mouseX >= headerX && mouseX <= headerX + headerWidth && mouseY >= headerY && mouseY <= headerY + 18) {
                toggleCollapse.run();
                return true;
            }
            return false;
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            headerX = x + 10;
            headerY = y + 4;
            headerWidth = entryWidth - 20;
            int accent = hovered ? withAlpha(uiColorHeaderStripe, 200) : withAlpha(uiColorHeaderStripe, 154);
            context.fill(headerX, headerY, headerX + headerWidth, headerY + 18, withAlpha(uiColorHeader, hovered ? 136 : 108));
            context.drawBorder(headerX, headerY, headerWidth, 18, new Color(uiColorBackgroundBorder, true).getRGB());
            context.fill(headerX, headerY, headerX + 4, headerY + 18, accent);
            String text = humanizeStatic(label);
            context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(text), headerX + 8, headerY + 5, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            String state = collapsed ? "Expand" : "Collapse";
            int stateX = headerX + headerWidth - MinecraftClient.getInstance().textRenderer.getWidth(state) - 8;
            inspectButtonX = stateX - 44;
            drawControlButton(context, inspectButtonX, headerY, 38, "View", inspectorOpen);
            context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(state), stateX, headerY + 5, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            if (subtitle != null && !subtitle.isBlank()) {
                int subtitleX = headerX + 10 + MinecraftClient.getInstance().textRenderer.getWidth(text);
                int maxWidth = Math.max(0, inspectButtonX - subtitleX - 10);
                if (maxWidth > 24) {
                    String clipped = subtitle;
                    while (MinecraftClient.getInstance().textRenderer.getWidth(clipped) > maxWidth && clipped.length() > 4) {
                        clipped = clipped.substring(0, clipped.length() - 4) + "...";
                    }
                    context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(clipped), subtitleX + 10, headerY + 5, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                }
            }
        }

        @Override
        public void renderOverlay(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, float delta) {
            if (!inspectorOpen) {
                return;
            }
            context.getMatrices().push();
            context.getMatrices().translate(0.0F, 0.0F, OVERLAY_Z);
            drawInspectorPopover(context);
            context.getMatrices().pop();
        }

        @Override
        public boolean hasModalOverlay() {
            return inspectorOpen;
        }

        @Override
        public boolean handleOverlayClick(double mouseX, double mouseY, int button) {
            boolean inside = (mouseX >= popoverX && mouseX <= popoverX + popoverWidth && mouseY >= popoverY && mouseY <= popoverY + popoverHeight)
                    || isControlButtonHit(mouseX, mouseY, inspectButtonX, headerY, 38);
            if (inside) {
                return mouseClicked(mouseX, mouseY, button) || (mouseX >= popoverX && mouseX <= popoverX + popoverWidth && mouseY >= popoverY && mouseY <= popoverY + popoverHeight);
            }
            inspectorOpen = false;
            requestedPopoverX = null;
            requestedPopoverY = null;
            return true;
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return inspectorOpen;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            if (!inspectorOpen || mouseX < popoverX || mouseX > popoverX + popoverWidth || mouseY < popoverY || mouseY > popoverY + popoverHeight) {
                return false;
            }
            List<String> rawLines = splitRawLines(inspectorData.rawJson(), popoverWidth - 16, Integer.MAX_VALUE);
            int visibleLines = Math.max(1, (popoverHeight - 52) / 11);
            int maxScroll = Math.max(0, rawLines.size() - visibleLines);
            inspectorScrollOffset = Math.max(0, Math.min(maxScroll, inspectorScrollOffset + (amount < 0 ? 1 : -1)));
            return true;
        }

        private void drawInspectorPopover(DrawContext context) {
            var renderer = MinecraftClient.getInstance().textRenderer;
            popoverWidth = 250;
            popoverHeight = 132;
            int desiredX = requestedPopoverX != null ? requestedPopoverX + 8 : inspectButtonX - popoverWidth - 8;
            int desiredY = requestedPopoverY != null ? requestedPopoverY - 8 : headerY - 2;
            int maxX = MinecraftClient.getInstance().currentScreen.width - popoverWidth - 8;
            int maxY = MinecraftClient.getInstance().currentScreen.height - popoverHeight - 8;
            popoverX = Math.max(8, Math.min(desiredX, maxX));
            popoverY = Math.max(8, Math.min(desiredY, maxY));
            context.fill(popoverX, popoverY, popoverX + popoverWidth, popoverY + popoverHeight, withAlpha(uiColorContentBase, 240));
            context.drawBorder(popoverX, popoverY, popoverWidth, popoverHeight, new Color(uiColorBackgroundBorder, true).getRGB());
            context.drawText(renderer, "Section Inspector", popoverX + 8, popoverY + 8, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            String chainLabel = inspectorData.inheritanceChain().isEmpty() ? "No inheritance chain" : String.join(" -> ", inspectorData.inheritanceChain());
            context.drawText(renderer, fitStatic(renderer, chainLabel, popoverWidth - 16), popoverX + 8, popoverY + 22, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            int rawAreaY = popoverY + 38;
            int rawAreaHeight = popoverHeight - 46;
            context.fill(popoverX + 6, rawAreaY, popoverX + popoverWidth - 6, rawAreaY + rawAreaHeight, withAlpha(uiColorHeader, 70));
            context.drawBorder(popoverX + 6, rawAreaY, popoverWidth - 12, rawAreaHeight, new Color(uiColorBackgroundBorder, true).getRGB());
            List<String> rawLines = splitRawLines(inspectorData.rawJson(), popoverWidth - 20, Integer.MAX_VALUE);
            int visibleLines = Math.max(1, (rawAreaHeight - 8) / 11);
            int maxScroll = Math.max(0, rawLines.size() - visibleLines);
            inspectorScrollOffset = Math.max(0, Math.min(maxScroll, inspectorScrollOffset));
            for (int i = 0; i < visibleLines && i + inspectorScrollOffset < rawLines.size(); i++) {
                drawHighlightedInspectorLine(context, renderer, rawLines.get(i + inspectorScrollOffset), popoverX + 10, rawAreaY + 4 + i * 11);
            }
            if (maxScroll > 0) {
                String scrollInfo = (inspectorScrollOffset + 1) + "/" + (maxScroll + 1);
                context.drawText(renderer, scrollInfo, popoverX + popoverWidth - renderer.getWidth(scrollInfo) - 10, popoverY + 8, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            }
        }

        private void drawHighlightedInspectorLine(DrawContext context, TextRenderer renderer, String line, int x, int y) {
            int drawX = x;
            for (EditorSyntaxHighlighter.StyledSpan span : EditorSyntaxHighlighter.highlight(label + ".json", line)) {
                if (span.text() == null || span.text().isEmpty()) {
                    continue;
                }
                context.drawText(renderer, span.text(), drawX, y, span.color(), false);
                drawX += renderer.getWidth(span.text());
            }
        }

        private List<String> splitRawLines(String raw, int maxWidth, int maxLines) {
            List<String> lines = new ArrayList<>();
            if (raw == null || raw.isBlank()) {
                return List.of("{}");
            }
            var renderer = MinecraftClient.getInstance().textRenderer;
            String[] baseLines = raw.split("\\R");
            for (String base : baseLines) {
                String remaining = base;
                while (!remaining.isEmpty()) {
                    int cut = remaining.length();
                    while (cut > 1 && renderer.getWidth(remaining.substring(0, cut)) > maxWidth) {
                        cut--;
                    }
                    lines.add(remaining.substring(0, cut));
                    if (lines.size() >= maxLines) {
                        return lines;
                    }
                    remaining = remaining.substring(cut);
                }
                if (lines.size() >= maxLines) {
                    return lines;
                }
            }
            return lines;
        }

        private String fitStatic(TextRenderer renderer, String text, int maxWidth) {
            if (renderer.getWidth(text) <= maxWidth) {
                return text;
            }
            String trimmed = text;
            while (trimmed.length() > 4 && renderer.getWidth(trimmed + "...") > maxWidth) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed + "...";
        }

        private static String humanizeStatic(String path) {
            String display = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            display = display.replace('.', ' ').replace('_', ' ').replace('-', ' ');
            if (display.isEmpty()) {
                return path;
            }
            return Character.toUpperCase(display.charAt(0)) + display.substring(1);
        }
    }

    private static class NumericPairEntry extends ConfigEntry {
        private final String label;
        private final List<TextFieldWidget> fields = new ArrayList<>();
        private final List<ComponentBinding> bindings;
        private final NumericUpdateConsumer onChange;
        private final boolean wholeNumber;
        private final CoordinateProfile profile;
        private final boolean threeDimensional;
        private final double[] defaultValues;
        private boolean suppressFieldPublish;
        private int openButtonX;
        private int presetButtonX;
        private int buttonY;
        private boolean editorOpen;
        private int popoverX;
        private int popoverY;
        private int popoverWidth;
        private int popoverHeight;
        private Integer requestedPopoverX;
        private Integer requestedPopoverY;
        private int planeX;
        private int planeY;
        private int planeSize;
        private int planeTabY;
        private int xyTabX;
        private int xzTabX;
        private int yzTabX;
        private int xyPlaneX;
        private int xyPlaneY;
        private int xzPlaneX;
        private int xzPlaneY;
        private int yzPlaneX;
        private int yzPlaneY;
        private int subPlaneSize;
        private int resetViewButtonX;
        private int zoomInButtonX;
        private int zoomOutButtonX;
        private int zoomButtonY;
        private int zSliderX;
        private int zSliderY;
        private int zSliderHeight;
        private boolean draggingPlane;
        private boolean draggingZ;
        private boolean panningPlane;
        private double dragStartMouseX;
        private double dragStartMouseY;
        private double dragStartValueX;
        private double dragStartValueY;
        private double dragStartValueZ;
        private double panStartMouseX;
        private double panStartMouseY;
        private double panStartCenterX;
        private double panStartCenterY;
        private double panStartCenterZ;
        private double viewCenterX;
        private double viewCenterY;
        private double viewCenterZ;
        private double viewSpan;
        private char active3dPlane = 'x';

        private NumericPairEntry(String label, List<ComponentBinding> bindings, NumericUpdateConsumer onChange) {
            this.label = label;
            this.bindings = bindings;
            this.onChange = onChange;
            this.wholeNumber = bindings.stream().allMatch(binding -> Math.rint(binding.value()) == binding.value());
            this.threeDimensional = bindings.size() >= 3;
            this.profile = inferCoordinateProfile(label, bindings);
            this.defaultValues = bindings.stream().mapToDouble(ComponentBinding::value).toArray();

            for (ComponentBinding binding : bindings) {
                TextFieldWidget field = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 50, 18, Text.literal(binding.label()));
                field.setText(wholeNumber ? String.valueOf((int) Math.round(binding.value())) : String.valueOf(binding.value()));
                field.setChangedListener(value -> {
                    if (suppressFieldPublish) {
                        return;
                    }
                    try {
                        onChange.accept(binding.path(), Double.parseDouble(value), wholeNumber);
                    } catch (NumberFormatException ignored) {
                    }
                });
                fields.add(field);
            }
            initializeView();
        }

        public void tick() {
            fields.forEach(TextFieldWidget::tick);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isControlButtonHit(mouseX, mouseY, openButtonX, buttonY, 44)) {
                requestedPopoverX = (int) Math.round(mouseX);
                requestedPopoverY = (int) Math.round(mouseY);
                editorOpen = !editorOpen;
                draggingPlane = false;
                draggingZ = false;
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (hasCoordinatePresets() && isControlButtonHit(mouseX, mouseY, presetButtonX, buttonY, 52)) {
                applyNextCoordinatePreset();
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (editorOpen && button == 0) {
                if (threeDimensional) {
                    if (isControlButtonHit(mouseX, mouseY, xyTabX, planeTabY, 28)) {
                        active3dPlane = 'x';
                        UiSoundHelper.playButtonClick();
                        return true;
                    }
                    if (isControlButtonHit(mouseX, mouseY, xzTabX, planeTabY, 28)) {
                        active3dPlane = 'z';
                        UiSoundHelper.playButtonClick();
                        return true;
                    }
                    if (isControlButtonHit(mouseX, mouseY, yzTabX, planeTabY, 28)) {
                        active3dPlane = 'y';
                        UiSoundHelper.playButtonClick();
                        return true;
                    }
                }
                if (threeDimensional && isInsideZSlider(mouseX, mouseY)) {
                    draggingZ = true;
                    applyZDrag(mouseY);
                    UiSoundHelper.playButtonClick();
                    return true;
                }
                if (isInsidePlane(mouseX, mouseY)) {
                    if (threeDimensional && isNearProjectedPoint(mouseX, mouseY)) {
                        draggingPlane = true;
                        dragStartMouseX = mouseX;
                        dragStartMouseY = mouseY;
                        dragStartValueX = getComponentValue(0);
                        dragStartValueY = getComponentValue(1);
                        dragStartValueZ = getComponentValue(2);
                        applyProjected3dDrag(mouseX, mouseY);
                    } else if (threeDimensional && profile != CoordinateProfile.NORMALIZED) {
                        panningPlane = true;
                        panStartMouseX = mouseX;
                        panStartMouseY = mouseY;
                        panStartCenterX = viewCenterX;
                        panStartCenterY = viewCenterY;
                        panStartCenterZ = viewCenterZ;
                    } else if (!threeDimensional && isNearPoint(mouseX, mouseY)) {
                        draggingPlane = true;
                        applyPlaneDrag(mouseX, mouseY);
                    } else if (!threeDimensional && profile != CoordinateProfile.NORMALIZED) {
                        panningPlane = true;
                        panStartMouseX = mouseX;
                        panStartMouseY = mouseY;
                        panStartCenterX = viewCenterX;
                        panStartCenterY = viewCenterY;
                    }
                    UiSoundHelper.playButtonClick();
                    return true;
                }
                if (isControlButtonHit(mouseX, mouseY, zoomInButtonX, zoomButtonY, 22)) {
                    adjustZoom(-1);
                    UiSoundHelper.playButtonClick();
                    return true;
                }
                if (isControlButtonHit(mouseX, mouseY, resetViewButtonX, zoomButtonY, 22)) {
                    initializeView();
                    UiSoundHelper.playButtonClick();
                    return true;
                }
                if (isControlButtonHit(mouseX, mouseY, zoomOutButtonX, zoomButtonY, 22)) {
                    adjustZoom(1);
                    UiSoundHelper.playButtonClick();
                    return true;
                }
            }
            return focusClickedField(mouseX, mouseY, button, fields.toArray(new TextFieldWidget[0]));
        }

        @Override
        public boolean handleMouseDrag(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (button != 0) {
                return false;
            }
            if (draggingPlane) {
                if (threeDimensional) {
                    applyProjected3dDrag(mouseX, mouseY);
                } else {
                    applyPlaneDrag(mouseX, mouseY);
                }
                return true;
            }
            if (panningPlane) {
                applyPlanePan(mouseX, mouseY);
                return true;
            }
            if (draggingZ) {
                applyZDrag(mouseY);
                return true;
            }
            return false;
        }

        @Override
        public boolean handleMouseRelease(double mouseX, double mouseY, int button) {
            boolean wasDragging = draggingPlane || draggingZ || panningPlane;
            draggingPlane = false;
            draggingZ = false;
            panningPlane = false;
            return wasDragging;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            for (TextFieldWidget field : fields) {
                if (field.keyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            for (TextFieldWidget field : fields) {
                if (field.charTyped(chr, modifiers)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            double step = wholeNumber ? 1.0 : 0.1;
            for (TextFieldWidget field : fields) {
                if (isMouseOverField(field, mouseX, mouseY)) {
                    boolean changed = nudgeNumericTextField(field, amount > 0 ? step : -step, wholeNumber);
                    if (changed) {
                        UiSoundHelper.playDialClick();
                    }
                    return changed;
                }
            }
            if (editorOpen && isInsidePopover(mouseX, mouseY) && amount != 0.0) {
                adjustZoom(amount > 0 ? -1 : 1);
                UiSoundHelper.playDialClick();
                return true;
            }
            return false;
        }

        @Override
        public void setFocused(boolean focused) {
            if (!focused) {
                fields.forEach(field -> field.setFocused(false));
                editorOpen = false;
                draggingPlane = false;
                draggingZ = false;
                panningPlane = false;
                active3dPlane = 'x';
                requestedPopoverX = null;
                requestedPopoverY = null;
            }
        }

        @Override
        public boolean isFocused() {
            return fields.stream().anyMatch(TextFieldWidget::isFocused);
        }

        @Override
        public List<String> getDeleteTargets() {
            List<String> targets = new ArrayList<>();
            for (ComponentBinding binding : bindings) {
                targets.add(binding.path());
            }
            return targets;
        }

        @Override
        public String getValidationError() {
            for (int i = 0; i < fields.size(); i++) {
                String error = validateNumericField(fields.get(i), bindings.get(i).label(), wholeNumber);
                if (error != null) {
                    return error;
                }
            }
            return null;
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, null, x + 18, y);
            int buttonWidth = 44;
            int presetWidth = hasCoordinatePresets() ? 52 : 0;
            int buttonGap = 10;
            int fieldWidth = isCompactConfigListingEnabledStatic() ? 42 : 44;
            int fieldGap = 6;
            boolean showCaptions = showFieldCaptions();
            int totalWidth = buttonWidth + (presetWidth > 0 ? presetWidth + 6 : 0) + buttonGap + bindings.size() * fieldWidth + Math.max(0, bindings.size() - 1) * fieldGap;
            int fieldStartX = getPinnedFieldStartX(x, entryWidth, totalWidth);
            openButtonX = fieldStartX;
            buttonY = getControlRowY(y, entryHeight);
            drawControlButton(context, openButtonX, buttonY, buttonWidth, "Edit", editorOpen);
            int currentX = fieldStartX + buttonWidth;
            if (presetWidth > 0) {
                presetButtonX = currentX + 6;
                drawControlButton(context, presetButtonX, buttonY, presetWidth, profile == CoordinateProfile.NORMALIZED ? "Anchor" : "Vector", false);
                currentX = presetButtonX + presetWidth;
            }
            currentX += buttonGap;
            for (int i = 0; i < fields.size(); i++) {
                ComponentBinding binding = bindings.get(i);
                TextFieldWidget field = fields.get(i);
                if (showCaptions) {
                    context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(binding.label()), currentX, getFieldCaptionY(buttonY), new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                }
                field.setWidth(fieldWidth);
                field.setX(currentX);
                field.setY(buttonY);
                field.render(context, mouseX, mouseY, delta);
                currentX += fieldWidth + fieldGap;
            }
            drawDeleteButton(context, x, y, entryWidth);
        }

        @Override
        public void renderOverlay(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, float delta) {
            if (!editorOpen) {
                return;
            }
            context.getMatrices().push();
            context.getMatrices().translate(0.0F, 0.0F, OVERLAY_Z);
            drawCoordinatePopover(context, mouseX, mouseY);
            context.getMatrices().pop();
        }

        @Override
        public boolean hasModalOverlay() {
            return editorOpen;
        }

        @Override
        public boolean openInlineEditor(double mouseX, double mouseY) {
            requestedPopoverX = Double.isNaN(mouseX) ? null : (int) Math.round(mouseX);
            requestedPopoverY = Double.isNaN(mouseY) ? null : (int) Math.round(mouseY);
            editorOpen = true;
            active3dPlane = 'x';
            draggingPlane = false;
            draggingZ = false;
            panningPlane = false;
            return true;
        }

        @Override
        public boolean handleOverlayClick(double mouseX, double mouseY, int button) {
            boolean inside = (mouseX >= popoverX && mouseX <= popoverX + popoverWidth && mouseY >= popoverY && mouseY <= popoverY + popoverHeight)
                    || isControlButtonHit(mouseX, mouseY, openButtonX, buttonY, 44);
            if (inside) {
                return mouseClicked(mouseX, mouseY, button) || (mouseX >= popoverX && mouseX <= popoverX + popoverWidth && mouseY >= popoverY && mouseY <= popoverY + popoverHeight);
            }
            editorOpen = false;
            draggingPlane = false;
            draggingZ = false;
            panningPlane = false;
            active3dPlane = 'x';
            requestedPopoverX = null;
            requestedPopoverY = null;
            return true;
        }

        private void initializeView() {
            double x = getComponentValue(0);
            double y = getComponentValue(1);
            if (profile == CoordinateProfile.NORMALIZED) {
                viewCenterX = 0.5;
                viewCenterY = 0.5;
                viewCenterZ = 0.5;
                viewSpan = 0.5;
                return;
            }
            if (profile == CoordinateProfile.VECTOR) {
                viewCenterX = 0.0;
                viewCenterY = 0.0;
                viewCenterZ = 0.0;
                viewSpan = roundSpan(Math.max(8.0, Math.max(Math.abs(x), Math.abs(y)) * 1.5));
                return;
            }
            double z = threeDimensional ? getComponentValue(2) : 0.0;
            if (profile == CoordinateProfile.WORLD) {
                viewCenterX = x;
                viewCenterY = y;
                viewCenterZ = z;
                viewSpan = roundSpan(Math.max(16.0, Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.abs(z)) * 0.5));
                return;
            }
            viewCenterX = x;
            viewCenterY = y;
            viewCenterZ = z;
            viewSpan = roundSpan(Math.max(8.0, Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.abs(z)) * 1.25));
        }

        private void drawCoordinatePopover(DrawContext context, int mouseX, int mouseY) {
            popoverWidth = threeDimensional ? 272 : 210;
            popoverHeight = threeDimensional ? 212 : 174;
            int anchorX = requestedPopoverX != null ? requestedPopoverX : openButtonX;
            int anchorY = requestedPopoverY != null ? requestedPopoverY : buttonY;
            int desiredX = anchorX - popoverWidth - 8;
            int desiredY = anchorY - 18;
            int screenWidth = MinecraftClient.getInstance().currentScreen.width;
            int screenHeight = MinecraftClient.getInstance().currentScreen.height;
            if (desiredX < 8 && anchorX + 52 + popoverWidth <= screenWidth - 8) {
                desiredX = anchorX + 52;
            }
            int maxX = screenWidth - popoverWidth - 8;
            int maxY = screenHeight - popoverHeight - 8;
            popoverX = Math.max(8, Math.min(desiredX, maxX));
            popoverY = Math.max(8, Math.min(desiredY, maxY));
            context.fill(popoverX, popoverY, popoverX + popoverWidth, popoverY + popoverHeight, withAlpha(uiColorContentBase, 236));
            context.drawBorder(popoverX, popoverY, popoverWidth, popoverHeight, new Color(uiColorBackgroundBorder, true).getRGB());

            TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
            planeSize = threeDimensional ? 122 : 110;
            planeX = popoverX + 10;
            planeY = popoverY + (threeDimensional ? 40 : 25);
            int titleX = planeX;
            if (threeDimensional) {
                planeTabY = popoverY + 18;
                xyTabX = planeX;
                xzTabX = planeX + 32;
                yzTabX = planeX + 64;
                titleX = yzTabX + 36;
            }

            resetViewButtonX = popoverX + popoverWidth - 74;
            zoomInButtonX = popoverX + popoverWidth - 50;
            zoomOutButtonX = popoverX + popoverWidth - 26;
            zoomButtonY = popoverY + 8;
            int titleWidth = Math.max(34, resetViewButtonX - titleX - 8);
            context.drawText(renderer, fitInlineText(profile.label, titleWidth), titleX, popoverY + 10, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            drawControlButton(context, resetViewButtonX, zoomButtonY, 22, "R", false);
            drawControlButton(context, zoomInButtonX, zoomButtonY, 22, "+", true);
            drawControlButton(context, zoomOutButtonX, zoomButtonY, 22, "-", true);

            drawCoordinatePlane(context, mouseX, mouseY);

            double xValue = getComponentValue(0);
            double yValue = getComponentValue(1);
            boolean hoveringPlane = isInsidePlane(mouseX, mouseY);
            String focusLabel = hoveringPlane
                    ? threeDimensional ? format3dHoverLabel(mouseX, mouseY) : "Cursor " + formatAxisValue(fromScreenX(mouseX)) + ", " + formatAxisValue(fromScreenY(mouseY))
                    : "Center " + formatAxisValue(viewCenterX) + ", " + formatAxisValue(viewCenterY);
            String spanLabel = "Span " + formatAxisValue(viewSpan * 2.0);
            if (threeDimensional) {
                drawControlButton(context, xyTabX, planeTabY, 28, "XY", active3dPlane == 'x');
                drawControlButton(context, xzTabX, planeTabY, 28, "XZ", active3dPlane == 'z');
                drawControlButton(context, yzTabX, planeTabY, 28, "YZ", active3dPlane == 'y');
            }

            int readoutY = planeY + planeSize + 6;
            int infoWidth = popoverWidth - 20;
            String xLabel = "X " + formatCoordinateValue(xValue);
            String yLabel = "Y " + formatCoordinateValue(yValue);
            int pairWidth = Math.max(74, (infoWidth - 6) / 2);
            if (!threeDimensional) {
                context.drawText(renderer, fitInlineText(xLabel, pairWidth), planeX, readoutY, new Color(uiColorConfigAxisX, true).getRGB(), false);
                int yLabelX = planeX + pairWidth + 6;
                context.drawText(renderer, fitInlineText(yLabel, pairWidth), yLabelX, readoutY, new Color(uiColorConfigAxisY, true).getRGB(), false);
            }

            if (threeDimensional) {
                double zValue = getComponentValue(2);
                double magnitude = Math.sqrt((xValue * xValue) + (yValue * yValue) + (zValue * zValue));
                String zLabel = "Z " + formatCoordinateValue(zValue);
                String magnitudeLabel = "|v| " + formatCoordinateValue(magnitude);
                int tripleWidth = Math.max(58, (infoWidth - 8) / 3);
                int tripleGap = 4;
                context.drawText(renderer, fitInlineText(xLabel, tripleWidth), planeX, readoutY, new Color(uiColorConfigAxisX, true).getRGB(), false);
                context.drawText(renderer, fitInlineText(yLabel, tripleWidth), planeX + tripleWidth + tripleGap, readoutY, new Color(uiColorConfigAxisY, true).getRGB(), false);
                context.drawText(renderer, fitInlineText(zLabel, tripleWidth), planeX + ((tripleWidth + tripleGap) * 2), readoutY, new Color(uiColorConfigAxisZ, true).getRGB(), false);
                int summaryWidth = Math.max(72, (infoWidth - 6) / 2);
                context.drawText(renderer, fitInlineText(magnitudeLabel, summaryWidth), planeX, readoutY + 12, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
                int spanX = popoverX + popoverWidth - 10 - Math.min(summaryWidth, renderer.getWidth(spanLabel));
                context.drawText(renderer, fitInlineText(spanLabel, summaryWidth), spanX, readoutY + 12, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                context.drawText(renderer, fitInlineText(focusLabel, infoWidth), planeX, readoutY + 26, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            } else {
                int summaryWidth = Math.max(72, (infoWidth - 6) / 2);
                context.drawText(renderer, fitInlineText(focusLabel, summaryWidth), planeX, readoutY + 12, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                int spanX = popoverX + popoverWidth - 10 - Math.min(summaryWidth, renderer.getWidth(spanLabel));
                context.drawText(renderer, fitInlineText(spanLabel, summaryWidth), spanX, readoutY + 12, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            }
        }

        private void drawCoordinatePlane(DrawContext context, int mouseX, int mouseY) {
            if (threeDimensional) {
                drawOrthographicPlane(context, planeX, planeY, planeSize, getPlaneHorizontalAxis(active3dPlane), getPlaneVerticalAxis(active3dPlane), "", mouseX, mouseY);
                return;
            }
            context.fill(planeX, planeY, planeX + planeSize, planeY + planeSize, withAlpha(uiColorHeader, 98));
            context.drawBorder(planeX, planeY, planeSize, planeSize, new Color(uiColorBackgroundBorder, true).getRGB());
            int hoveredGridX = isInsidePlane(mouseX, mouseY) ? getClosestGridScreenX(mouseX) : Integer.MIN_VALUE;
            int hoveredGridY = isInsidePlane(mouseX, mouseY) ? getClosestGridScreenY(mouseY) : Integer.MIN_VALUE;
            double gridStep = getGridStep();
            double majorStep = gridStep * getMajorGridStride();
            context.enableScissor(planeX + 1, planeY + 1, planeX + planeSize, planeY + planeSize);
            int renderedVerticalGridLines = 0;
            for (double gridX = Math.floor(getMinVisibleX() / gridStep) * gridStep; gridX <= getMaxVisibleX() + (gridStep * 0.5) && renderedVerticalGridLines++ < MAX_COORDINATE_GRID_LINES; gridX += gridStep) {
                int px = toScreenX(gridX);
                boolean major = isMajorGridValue(gridX, majorStep);
                int color = px == hoveredGridX ? withAlpha(uiColorContentBaseTitleText, 126) : major ? withAlpha(uiColorBackgroundBorder, 128) : withAlpha(uiColorBackgroundBorder, 68);
                context.fill(px, planeY, px + 1, planeY + planeSize, color);
            }
            int renderedHorizontalGridLines = 0;
            for (double gridY = Math.floor(getMinVisibleY() / gridStep) * gridStep; gridY <= getMaxVisibleY() + (gridStep * 0.5) && renderedHorizontalGridLines++ < MAX_COORDINATE_GRID_LINES; gridY += gridStep) {
                int py = toScreenY(gridY);
                boolean major = isMajorGridValue(gridY, majorStep);
                int color = py == hoveredGridY ? withAlpha(uiColorContentBaseTitleText, 126) : major ? withAlpha(uiColorBackgroundBorder, 128) : withAlpha(uiColorBackgroundBorder, 68);
                context.fill(planeX, py, planeX + planeSize, py + 1, color);
            }

            int xAxisColor = new Color(uiColorConfigAxisXSoft, true).getRGB();
            int yAxisColor = new Color(uiColorConfigAxisYSoft, true).getRGB();
            if (profile == CoordinateProfile.NORMALIZED) {
                context.fill(planeX, planeY + planeSize - 1, planeX + planeSize, planeY + planeSize, xAxisColor);
                context.fill(planeX, planeY, planeX + 1, planeY + planeSize, yAxisColor);
            } else {
                int axisX = toScreenX(0.0);
                int axisY = toScreenY(0.0);
                if (axisX >= planeX && axisX <= planeX + planeSize) {
                    context.fill(axisX, planeY, axisX + 1, planeY + planeSize, yAxisColor);
                }
                if (axisY >= planeY && axisY <= planeY + planeSize) {
                    context.fill(planeX, axisY, planeX + planeSize, axisY + 1, xAxisColor);
                }
            }

            if (profile != CoordinateProfile.NORMALIZED) {
                int originX = toScreenX(0.0);
                int originY = toScreenY(0.0);
                if (originX >= planeX && originX <= planeX + planeSize && originY >= planeY && originY <= planeY + planeSize) {
                    context.fill(originX - 2, originY - 2, originX + 3, originY + 3, withAlpha(uiColorHeaderSubTitleText, 220));
                }
            }

            int ghostX = toScreenX(defaultValues[0]);
            int ghostY = toScreenY(defaultValues[1]);
            context.fill(ghostX, planeY, ghostX + 1, planeY + planeSize, withAlpha(new Color(uiColorConfigAxisY, true).getRGB(), 34));
            context.fill(planeX, ghostY, planeX + planeSize, ghostY + 1, withAlpha(new Color(uiColorConfigAxisX, true).getRGB(), 34));
            context.drawBorder(ghostX - 3, ghostY - 3, 6, 6, withAlpha(uiColorHeaderSubTitleText, 150));

            int pointX = toScreenX(getComponentValue(0));
            int pointY = toScreenY(getComponentValue(1));
            context.fill(pointX, planeY, pointX + 1, planeY + planeSize, withAlpha(new Color(uiColorConfigAxisY, true).getRGB(), 76));
            context.fill(planeX, pointY, planeX + planeSize, pointY + 1, withAlpha(new Color(uiColorConfigAxisX, true).getRGB(), 76));
            context.fill(pointX - 2, pointY - 2, pointX + 3, pointY + 3, new Color(uiColorContentBaseTitleText, true).getRGB());
            if (threeDimensional) {
                drawProjected3dPreview(context);
            }

            if (isInsidePlane(mouseX, mouseY)) {
                int hoverX = (int) Math.max(planeX, Math.min(planeX + planeSize, mouseX));
                int hoverY = (int) Math.max(planeY, Math.min(planeY + planeSize, mouseY));
                context.fill(hoverX, planeY, hoverX + 1, planeY + planeSize, withAlpha(uiColorContentBaseTitleText, 40));
                context.fill(planeX, hoverY, planeX + planeSize, hoverY + 1, withAlpha(uiColorContentBaseTitleText, 40));
                String hoverLabel = formatAxisValue(fromScreenX(mouseX)) + ", " + formatAxisValue(fromScreenY(mouseY));
                int hoverLabelWidth = MinecraftClient.getInstance().textRenderer.getWidth(hoverLabel);
                context.fill(planeX + 6, planeY + 6, planeX + 12 + hoverLabelWidth, planeY + 18, withAlpha(uiColorContentBase, 228));
                context.drawText(MinecraftClient.getInstance().textRenderer, hoverLabel, planeX + 9, planeY + 9, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            }
            context.disableScissor();

            String centerLabel = "C " + formatAxisValue(viewCenterX) + ", " + formatAxisValue(viewCenterY);
            context.drawText(MinecraftClient.getInstance().textRenderer, centerLabel, planeX, planeY - 10, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        }

        private void drawOrthographicPlane(DrawContext context, int left, int top, int size, char horizontalAxis, char verticalAxis, String title, int mouseX, int mouseY) {
            boolean hovered = mouseX >= left && mouseX <= left + size && mouseY >= top && mouseY <= top + size;
            context.fill(left, top, left + size, top + size, withAlpha(uiColorHeader, hovered ? 116 : 98));
            context.drawBorder(left, top, size, size, new Color(uiColorBackgroundBorder, true).getRGB());
            double minH = getAxisMin(horizontalAxis);
            double maxH = getAxisMax(horizontalAxis);
            double minV = getAxisMin(verticalAxis);
            double maxV = getAxisMax(verticalAxis);
            double gridStep = getGridStep();
            double majorStep = gridStep * getMajorGridStride();
            int hoveredGridX = hovered ? axisToScreen(left, size, horizontalAxis, snapCoordinate(screenToAxis(left, size, horizontalAxis, mouseX))) : Integer.MIN_VALUE;
            int hoveredGridY = hovered ? axisToScreenY(top, size, verticalAxis, snapCoordinate(screenToAxisY(top, size, verticalAxis, mouseY))) : Integer.MIN_VALUE;
            context.enableScissor(left + 1, top + 1, left + size, top + size);
            int renderedVerticalGridLines = 0;
            for (double grid = Math.floor(minH / gridStep) * gridStep; grid <= maxH + (gridStep * 0.5) && renderedVerticalGridLines++ < MAX_COORDINATE_GRID_LINES; grid += gridStep) {
                int px = axisToScreen(left, size, horizontalAxis, grid);
                int color = px == hoveredGridX ? withAlpha(uiColorContentBaseTitleText, 120) : isMajorGridValue(grid, majorStep) ? withAlpha(uiColorBackgroundBorder, 110) : withAlpha(uiColorBackgroundBorder, 58);
                context.fill(px, top, px + 1, top + size, color);
            }
            int renderedHorizontalGridLines = 0;
            for (double grid = Math.floor(minV / gridStep) * gridStep; grid <= maxV + (gridStep * 0.5) && renderedHorizontalGridLines++ < MAX_COORDINATE_GRID_LINES; grid += gridStep) {
                int py = axisToScreenY(top, size, verticalAxis, grid);
                int color = py == hoveredGridY ? withAlpha(uiColorContentBaseTitleText, 120) : isMajorGridValue(grid, majorStep) ? withAlpha(uiColorBackgroundBorder, 110) : withAlpha(uiColorBackgroundBorder, 58);
                context.fill(left, py, left + size, py + 1, color);
            }
            drawAxisLine(context, left, top, size, horizontalAxis, true);
            drawAxisLine(context, left, top, size, verticalAxis, false);
            int ghostX = axisToScreen(left, size, horizontalAxis, getAxisValue(horizontalAxis, defaultValues));
            int ghostY = axisToScreenY(top, size, verticalAxis, getAxisValue(verticalAxis, defaultValues));
            context.drawBorder(ghostX - 3, ghostY - 3, 6, 6, withAlpha(uiColorHeaderSubTitleText, 150));
            int pointX = axisToScreen(left, size, horizontalAxis, getAxisValue(horizontalAxis));
            int pointY = axisToScreenY(top, size, verticalAxis, getAxisValue(verticalAxis));
            context.fill(pointX, top, pointX + 1, top + size, withAlpha(axisColor(horizontalAxis), 72));
            context.fill(left, pointY, left + size, pointY + 1, withAlpha(axisColor(verticalAxis), 72));
            context.fill(pointX - 2, pointY - 2, pointX + 3, pointY + 3, new Color(uiColorContentBaseTitleText, true).getRGB());
            if (hovered) {
                int hoverX = Math.max(left, Math.min(left + size, mouseX));
                int hoverY = Math.max(top, Math.min(top + size, mouseY));
                context.fill(hoverX, top, hoverX + 1, top + size, withAlpha(uiColorContentBaseTitleText, 34));
                context.fill(left, hoverY, left + size, hoverY + 1, withAlpha(uiColorContentBaseTitleText, 34));
            }
            context.disableScissor();
            if (!title.isBlank()) {
                context.drawText(MinecraftClient.getInstance().textRenderer, title, left + 4, top - 10, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            }
        }

        private void drawAxisLine(DrawContext context, int left, int top, int size, char axis, boolean verticalLine) {
            double zero = 0.0;
            if (zero < getAxisMin(axis) || zero > getAxisMax(axis)) {
                return;
            }
            if (verticalLine) {
                int px = axisToScreen(left, size, axis, zero);
                context.fill(px, top, px + 1, top + size, axisColor(axis));
            } else {
                int py = axisToScreenY(top, size, axis, zero);
                context.fill(left, py, left + size, py + 1, axisColor(axis));
            }
        }

        private int axisColor(char axis) {
            return switch (axis) {
                case 'x' -> new Color(uiColorConfigAxisX, true).getRGB();
                case 'y' -> new Color(uiColorConfigAxisY, true).getRGB();
                default -> new Color(uiColorConfigAxisZ, true).getRGB();
            };
        }

        private String format3dHoverLabel(double mouseX, double mouseY) {
            char horizontal = getPlaneHorizontalAxis(active3dPlane);
            char vertical = getPlaneVerticalAxis(active3dPlane);
            return getPlaneTitle(active3dPlane) + " "
                    + formatAxisValue(screenToAxis(planeX, planeSize, horizontal, mouseX))
                    + ", "
                    + formatAxisValue(screenToAxisY(planeY, planeSize, vertical, mouseY));
        }

        private void drawProjected3dEditor(DrawContext context, int mouseX, int mouseY) {
            context.fill(planeX, planeY, planeX + planeSize, planeY + planeSize, withAlpha(uiColorHeader, 98));
            context.drawBorder(planeX, planeY, planeSize, planeSize, new Color(uiColorBackgroundBorder, true).getRGB());
            int squareStep = Math.max(12, planeSize / 8);
            for (int gx = planeX; gx <= planeX + planeSize; gx += squareStep) {
                context.fill(gx, planeY, gx + 1, planeY + planeSize, withAlpha(uiColorBackgroundBorder, 34));
            }
            for (int gy = planeY; gy <= planeY + planeSize; gy += squareStep) {
                context.fill(planeX, gy, planeX + planeSize, gy + 1, withAlpha(uiColorBackgroundBorder, 34));
            }
            context.fill(planeX + 10, planeY + planeSize - 42, planeX + planeSize - 10, planeY + planeSize - 10, withAlpha(uiColorHeader, 54));
            for (int grid = -2; grid <= 2; grid++) {
                drawProjectedLine(context, planeX, planeY, planeSize, -1.0, grid / 2.0, -1.0, 1.0, grid / 2.0, -1.0, withAlpha(new Color(uiColorConfigAxisY, true).getRGB(), 70));
                drawProjectedLine(context, planeX, planeY, planeSize, grid / 2.0, -1.0, -1.0, grid / 2.0, 1.0, -1.0, withAlpha(new Color(uiColorConfigAxisX, true).getRGB(), 70));
                drawProjectedLine(context, planeX, planeY, planeSize, -1.0, -1.0, grid / 2.0, -1.0, 1.0, grid / 2.0, withAlpha(new Color(uiColorConfigAxisZ, true).getRGB(), 62));
            }
            double[][] corners = new double[][]{
                    {-1.0, -1.0, -1.0}, {1.0, -1.0, -1.0}, {1.0, 1.0, -1.0}, {-1.0, 1.0, -1.0},
                    {-1.0, -1.0, 1.0}, {1.0, -1.0, 1.0}, {1.0, 1.0, 1.0}, {-1.0, 1.0, 1.0}
            };
            drawProjectedEdgeLoop(context, planeX, planeY, planeSize, corners, 0, 1, 2, 3, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedEdgeLoop(context, planeX, planeY, planeSize, corners, 4, 5, 6, 7, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedLine(context, planeX, planeY, planeSize, -1.0, -1.0, -1.0, -1.0, -1.0, 1.0, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedLine(context, planeX, planeY, planeSize, 1.0, -1.0, -1.0, 1.0, -1.0, 1.0, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedLine(context, planeX, planeY, planeSize, 1.0, 1.0, -1.0, 1.0, 1.0, 1.0, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedLine(context, planeX, planeY, planeSize, -1.0, 1.0, -1.0, -1.0, 1.0, 1.0, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedAxis(context, planeX, planeY, planeSize, 0.0, 0.0, 0.0, 1.15, 0.0, 0.0, new Color(uiColorConfigAxisX, true).getRGB());
            drawProjectedAxis(context, planeX, planeY, planeSize, 0.0, 0.0, 0.0, 0.0, 1.15, 0.0, new Color(uiColorConfigAxisY, true).getRGB());
            drawProjectedAxis(context, planeX, planeY, planeSize, 0.0, 0.0, 0.0, 0.0, 0.0, 1.15, new Color(uiColorConfigAxisZ, true).getRGB());
            int ghostPointX = projectIsoToScreenX(planeX, planeSize, normalizeToCube(defaultValues[0], getMinVisibleX(), getMaxVisibleX()), normalizeToCube(defaultValues[1], getMinVisibleY(), getMaxVisibleY()), normalizeToCube(defaultValues[2], getMinVisibleZ(), getMaxVisibleZ()));
            int ghostPointY = projectIsoToScreenY(planeY, planeSize, normalizeToCube(defaultValues[0], getMinVisibleX(), getMaxVisibleX()), normalizeToCube(defaultValues[1], getMinVisibleY(), getMaxVisibleY()), normalizeToCube(defaultValues[2], getMinVisibleZ(), getMaxVisibleZ()));
            context.drawBorder(ghostPointX - 3, ghostPointY - 3, 6, 6, withAlpha(uiColorHeaderSubTitleText, 150));
            double cubeX = normalizeToCube(getComponentValue(0), getMinVisibleX(), getMaxVisibleX());
            double cubeY = normalizeToCube(getComponentValue(1), getMinVisibleY(), getMaxVisibleY());
            double cubeZ = normalizeToCube(getComponentValue(2), getMinVisibleZ(), getMaxVisibleZ());
            drawProjectedSlicePlane(context, planeX, planeY, planeSize, cubeX, 'x', withAlpha(new Color(uiColorConfigAxisX, true).getRGB(), 108));
            drawProjectedSlicePlane(context, planeX, planeY, planeSize, cubeY, 'y', withAlpha(new Color(uiColorConfigAxisY, true).getRGB(), 108));
            drawProjectedSlicePlane(context, planeX, planeY, planeSize, cubeZ, 'z', withAlpha(new Color(uiColorConfigAxisZ, true).getRGB(), 100));
            int pointX = projectIsoToScreenX(planeX, planeSize, cubeX, cubeY, cubeZ);
            int pointY = projectIsoToScreenY(planeY, planeSize, cubeX, cubeY, cubeZ);
            drawProjectedLine(context, planeX, planeY, planeSize, cubeX, cubeY, cubeZ, cubeX, cubeY, -1.0, withAlpha(new Color(uiColorConfigAxisZ, true).getRGB(), 136));
            drawProjectedLine(context, planeX, planeY, planeSize, cubeX, cubeY, cubeZ, cubeX, -1.0, cubeZ, withAlpha(new Color(uiColorConfigAxisY, true).getRGB(), 136));
            drawProjectedLine(context, planeX, planeY, planeSize, cubeX, cubeY, cubeZ, -1.0, cubeY, cubeZ, withAlpha(new Color(uiColorConfigAxisX, true).getRGB(), 136));
            context.fill(pointX - 2, pointY - 2, pointX + 3, pointY + 3, new Color(uiColorContentBaseTitleText, true).getRGB());
            context.drawText(MinecraftClient.getInstance().textRenderer, "X", planeX + planeSize - 10, planeY + planeSize - 34, new Color(uiColorConfigAxisX, true).getRGB(), false);
            context.drawText(MinecraftClient.getInstance().textRenderer, "Y", planeX + 12, planeY + planeSize - 34, new Color(uiColorConfigAxisY, true).getRGB(), false);
            context.drawText(MinecraftClient.getInstance().textRenderer, "Z", planeX + planeSize / 2 - 3, planeY + 10, new Color(uiColorConfigAxisZ, true).getRGB(), false);
            if (isInsidePlane(mouseX, mouseY)) {
                String hoverLabel = Screen.hasShiftDown() ? "Shift drag = Z" : "Drag = X/Y";
                int hoverLabelWidth = MinecraftClient.getInstance().textRenderer.getWidth(hoverLabel);
                context.fill(planeX + 6, planeY + 6, planeX + 12 + hoverLabelWidth, planeY + 18, withAlpha(uiColorContentBase, 228));
                context.drawText(MinecraftClient.getInstance().textRenderer, hoverLabel, planeX + 9, planeY + 9, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            }
        }


        private void drawZSlider(DrawContext context, double zValue) {
            context.fill(zSliderX, zSliderY, zSliderX + 8, zSliderY + zSliderHeight, withAlpha(uiColorHeader, 120));
            context.drawBorder(zSliderX, zSliderY, 8, zSliderHeight, new Color(uiColorBackgroundBorder, true).getRGB());
            int knobY = toScreenZ(zValue);
            context.fill(zSliderX - 3, knobY - 3, zSliderX + 11, knobY + 4, new Color(uiColorContentBaseTitleText, true).getRGB());
            context.drawText(MinecraftClient.getInstance().textRenderer, formatAxisValue(getMaxVisibleZ()), zSliderX - 10, zSliderY - 10, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            context.drawText(MinecraftClient.getInstance().textRenderer, formatAxisValue(getMinVisibleZ()), zSliderX - 10, zSliderY + zSliderHeight + 4, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        }

        private void drawProjected3dPreview(DrawContext context) {
            int previewSize = 44;
            int previewX = planeX + planeSize - previewSize - 6;
            int previewY = planeY + 6;
            context.fill(previewX, previewY, previewX + previewSize, previewY + previewSize, withAlpha(uiColorContentBase, 196));
            context.drawBorder(previewX, previewY, previewSize, previewSize, withAlpha(uiColorBackgroundBorder, 180));

            double[][] corners = new double[][]{
                    projectIso(-1.0, -1.0, -1.0),
                    projectIso(1.0, -1.0, -1.0),
                    projectIso(1.0, 1.0, -1.0),
                    projectIso(-1.0, 1.0, -1.0),
                    projectIso(-1.0, -1.0, 1.0),
                    projectIso(1.0, -1.0, 1.0),
                    projectIso(1.0, 1.0, 1.0),
                    projectIso(-1.0, 1.0, 1.0)
            };

            for (int grid = -1; grid <= 1; grid++) {
                drawProjectedLine(context, previewX, previewY, previewSize, -1.0, grid, -1.0, 1.0, grid, -1.0, withAlpha(uiColorBackgroundBorder, 110));
                drawProjectedLine(context, previewX, previewY, previewSize, grid, -1.0, -1.0, grid, 1.0, -1.0, withAlpha(uiColorBackgroundBorder, 110));
                drawProjectedLine(context, previewX, previewY, previewSize, grid, -1.0, -1.0, grid, -1.0, 1.0, withAlpha(uiColorBackgroundBorder, 110));
            }

            drawProjectedEdgeLoop(context, previewX, previewY, previewSize, corners, 0, 1, 2, 3, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedEdgeLoop(context, previewX, previewY, previewSize, corners, 4, 5, 6, 7, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedLine(context, previewX, previewY, previewSize, -1.0, -1.0, -1.0, -1.0, -1.0, 1.0, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedLine(context, previewX, previewY, previewSize, 1.0, -1.0, -1.0, 1.0, -1.0, 1.0, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedLine(context, previewX, previewY, previewSize, 1.0, 1.0, -1.0, 1.0, 1.0, 1.0, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedLine(context, previewX, previewY, previewSize, -1.0, 1.0, -1.0, -1.0, 1.0, 1.0, withAlpha(uiColorBackgroundBorder, 168));

            drawProjectedAxis(context, previewX, previewY, previewSize, 0.0, 0.0, 0.0, 1.05, 0.0, 0.0, new Color(uiColorConfigAxisX, true).getRGB());
            drawProjectedAxis(context, previewX, previewY, previewSize, 0.0, 0.0, 0.0, 0.0, 1.05, 0.0, new Color(uiColorConfigAxisY, true).getRGB());
            drawProjectedAxis(context, previewX, previewY, previewSize, 0.0, 0.0, 0.0, 0.0, 0.0, 1.05, new Color(uiColorConfigAxisZ, true).getRGB());

            int pointX = projectIsoToScreenX(previewX, previewSize, normalizeToCube(getComponentValue(0), getMinVisibleX(), getMaxVisibleX()), normalizeToCube(getComponentValue(1), getMinVisibleY(), getMaxVisibleY()), normalizeToCube(getComponentValue(2), getMinVisibleZ(), getMaxVisibleZ()));
            int pointY = projectIsoToScreenY(previewY, previewSize, normalizeToCube(getComponentValue(0), getMinVisibleX(), getMaxVisibleX()), normalizeToCube(getComponentValue(1), getMinVisibleY(), getMaxVisibleY()), normalizeToCube(getComponentValue(2), getMinVisibleZ(), getMaxVisibleZ()));
            context.fill(pointX - 2, pointY - 2, pointX + 3, pointY + 3, new Color(uiColorContentBaseTitleText, true).getRGB());
        }

        private void drawProjectedEdgeLoop(DrawContext context, int previewX, int previewY, int previewSize, double[][] corners, int a, int b, int c, int d, int color) {
            drawProjectedLine(context, previewX, previewY, previewSize, corners[a][0], corners[a][1], corners[a][2], corners[b][0], corners[b][1], corners[b][2], color);
            drawProjectedLine(context, previewX, previewY, previewSize, corners[b][0], corners[b][1], corners[b][2], corners[c][0], corners[c][1], corners[c][2], color);
            drawProjectedLine(context, previewX, previewY, previewSize, corners[c][0], corners[c][1], corners[c][2], corners[d][0], corners[d][1], corners[d][2], color);
            drawProjectedLine(context, previewX, previewY, previewSize, corners[d][0], corners[d][1], corners[d][2], corners[a][0], corners[a][1], corners[a][2], color);
        }

        private void drawProjectedAxis(DrawContext context, int previewX, int previewY, int previewSize, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
            drawProjectedLine(context, previewX, previewY, previewSize, x1, y1, z1, x2, y2, z2, withAlpha(color, 220));
        }

        private void drawProjectedSlicePlane(DrawContext context, int previewX, int previewY, int previewSize, double value, char axis, int color) {
            double[][] corners = switch (axis) {
                case 'x' -> new double[][]{{value, -1.0, -1.0}, {value, 1.0, -1.0}, {value, 1.0, 1.0}, {value, -1.0, 1.0}};
                case 'y' -> new double[][]{{-1.0, value, -1.0}, {1.0, value, -1.0}, {1.0, value, 1.0}, {-1.0, value, 1.0}};
                default -> new double[][]{{-1.0, -1.0, value}, {1.0, -1.0, value}, {1.0, 1.0, value}, {-1.0, 1.0, value}};
            };
            drawProjectedEdgeLoop(context, previewX, previewY, previewSize, corners, 0, 1, 2, 3, color);
            for (double guide = -0.5; guide <= 0.5; guide += 0.5) {
                if (axis == 'x') {
                    drawProjectedLine(context, previewX, previewY, previewSize, value, guide, -1.0, value, guide, 1.0, withAlpha(color, 70));
                    drawProjectedLine(context, previewX, previewY, previewSize, value, -1.0, guide, value, 1.0, guide, withAlpha(color, 70));
                } else if (axis == 'y') {
                    drawProjectedLine(context, previewX, previewY, previewSize, guide, value, -1.0, guide, value, 1.0, withAlpha(color, 70));
                    drawProjectedLine(context, previewX, previewY, previewSize, -1.0, value, guide, 1.0, value, guide, withAlpha(color, 70));
                } else {
                    drawProjectedLine(context, previewX, previewY, previewSize, guide, -1.0, value, guide, 1.0, value, withAlpha(color, 70));
                    drawProjectedLine(context, previewX, previewY, previewSize, -1.0, guide, value, 1.0, guide, value, withAlpha(color, 70));
                }
            }
        }

        private void drawProjectedLine(DrawContext context, int previewX, int previewY, int previewSize, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
            int sx1 = projectIsoToScreenX(previewX, previewSize, x1, y1, z1);
            int sy1 = projectIsoToScreenY(previewY, previewSize, x1, y1, z1);
            int sx2 = projectIsoToScreenX(previewX, previewSize, x2, y2, z2);
            int sy2 = projectIsoToScreenY(previewY, previewSize, x2, y2, z2);
            drawThinLine(context, sx1, sy1, sx2, sy2, color);
        }

        private int projectIsoToScreenX(int previewX, int previewSize, double x, double y, double z) {
            double[] projected = projectIso(x, y, z);
            return previewX + (previewSize / 2) + (int) Math.round(projected[0] * (previewSize * 0.22));
        }

        private int projectIsoToScreenY(int previewY, int previewSize, double x, double y, double z) {
            double[] projected = projectIso(x, y, z);
            return previewY + (previewSize / 2) + (int) Math.round(projected[1] * (previewSize * 0.22));
        }

        private double[] projectIso(double x, double y, double z) {
            double screenX = x - y;
            double screenY = ((x + y) * 0.5) - z;
            return new double[]{screenX, screenY, z};
        }

        private double normalizeToCube(double value, double min, double max) {
            double progress = (value - min) / Math.max(0.0001, max - min);
            progress = Math.max(0.0, Math.min(1.0, progress));
            return (progress * 2.0) - 1.0;
        }

        private void drawThinLine(DrawContext context, int x1, int y1, int x2, int y2, int color) {
            int dx = Math.abs(x2 - x1);
            int dy = Math.abs(y2 - y1);
            int sx = x1 < x2 ? 1 : -1;
            int sy = y1 < y2 ? 1 : -1;
            int err = dx - dy;
            int x = x1;
            int y = y1;
            while (true) {
                context.fill(x, y, x + 1, y + 1, color);
                if (x == x2 && y == y2) {
                    break;
                }
                int e2 = err * 2;
                if (e2 > -dy) {
                    err -= dy;
                    x += sx;
                }
                if (e2 < dx) {
                    err += dx;
                    y += sy;
                }
            }
        }

        private boolean isInsidePlane(double mouseX, double mouseY) {
            if (!editorOpen) {
                return false;
            }
            return mouseX >= planeX && mouseX <= planeX + planeSize && mouseY >= planeY && mouseY <= planeY + planeSize;
        }

        private boolean isInsidePopover(double mouseX, double mouseY) {
            return editorOpen
                    && mouseX >= popoverX
                    && mouseX <= popoverX + popoverWidth
                    && mouseY >= popoverY
                    && mouseY <= popoverY + popoverHeight;
        }

        private boolean isNearPoint(double mouseX, double mouseY) {
            int pointX = toScreenX(getComponentValue(0));
            int pointY = toScreenY(getComponentValue(1));
            return Math.abs(mouseX - pointX) <= 8.0 && Math.abs(mouseY - pointY) <= 8.0;
        }


        private boolean isNearProjectedPoint(double mouseX, double mouseY) {
            int pointX = axisToScreen(planeX, planeSize, getPlaneHorizontalAxis(active3dPlane), getAxisValue(getPlaneHorizontalAxis(active3dPlane)));
            int pointY = axisToScreenY(planeY, planeSize, getPlaneVerticalAxis(active3dPlane), getAxisValue(getPlaneVerticalAxis(active3dPlane)));
            return Math.abs(mouseX - pointX) <= 8.0 && Math.abs(mouseY - pointY) <= 8.0;
        }

        private boolean isInsideZSlider(double mouseX, double mouseY) {
            return editorOpen && mouseX >= zSliderX - 4 && mouseX <= zSliderX + 12 && mouseY >= zSliderY && mouseY <= zSliderY + zSliderHeight;
        }

        private void applyPlaneDrag(double mouseX, double mouseY) {
            double nextX = snapCoordinate(fromScreenX(mouseX));
            double nextY = snapCoordinate(fromScreenY(mouseY));
            setComponentValue(0, nextX);
            setComponentValue(1, nextY);
        }

        private void applyProjected3dDrag(double mouseX, double mouseY) {
            char plane = active3dPlane;
            if (plane == 'x') {
                setComponentValue(0, snapCoordinate(screenToAxis(planeX, planeSize, 'x', mouseX)));
                setComponentValue(1, snapCoordinate(screenToAxisY(planeY, planeSize, 'y', mouseY)));
            } else if (plane == 'z') {
                setComponentValue(0, snapCoordinate(screenToAxis(planeX, planeSize, 'x', mouseX)));
                setComponentValue(2, snapCoordinate(screenToAxisY(planeY, planeSize, 'z', mouseY)));
            } else if (plane == 'y') {
                setComponentValue(1, snapCoordinate(screenToAxis(planeX, planeSize, 'y', mouseX)));
                setComponentValue(2, snapCoordinate(screenToAxisY(planeY, planeSize, 'z', mouseY)));
            }
        }

        private char getPlaneHorizontalAxisUnused(char plane) {
            return switch (plane) {
                case 'z' -> 'x';
                case 'y' -> 'y';
                default -> 'x';
            };
        }

        private char getPlaneVerticalAxisUnused(char plane) {
            return switch (plane) {
                case 'z', 'y' -> 'z';
                default -> 'y';
            };
        }

        private String getPlaneTitleUnused(char plane) {
            return switch (plane) {
                case 'z' -> "XZ";
                case 'y' -> "YZ";
                default -> "XY";
            };
        }

        private double getAxisValue(char axis) {
            return switch (axis) {
                case 'x' -> getComponentValue(0);
                case 'y' -> getComponentValue(1);
                default -> getComponentValue(2);
            };
        }

        private double getAxisValue(char axis, double[] values) {
            return switch (axis) {
                case 'x' -> values.length > 0 ? values[0] : 0.0;
                case 'y' -> values.length > 1 ? values[1] : 0.0;
                default -> values.length > 2 ? values[2] : 0.0;
            };
        }

        private double getAxisMin(char axis) {
            return switch (axis) {
                case 'x' -> getMinVisibleX();
                case 'y' -> getMinVisibleY();
                default -> getMinVisibleZ();
            };
        }

        private double getAxisMax(char axis) {
            return switch (axis) {
                case 'x' -> getMaxVisibleX();
                case 'y' -> getMaxVisibleY();
                default -> getMaxVisibleZ();
            };
        }

        private char getPlaneHorizontalAxis(char plane) {
            return switch (plane) {
                case 'z' -> 'x';
                case 'y' -> 'y';
                default -> 'x';
            };
        }

        private char getPlaneVerticalAxis(char plane) {
            return switch (plane) {
                case 'z', 'y' -> 'z';
                default -> 'y';
            };
        }

        private String getPlaneTitle(char plane) {
            return switch (plane) {
                case 'z' -> "XZ";
                case 'y' -> "YZ";
                default -> "XY";
            };
        }

        private int axisToScreen(int left, int size, char axis, double value) {
            double min = getAxisMin(axis);
            double max = getAxisMax(axis);
            double progress = Math.max(0.0, Math.min(1.0, (value - min) / Math.max(0.0001, max - min)));
            return left + (int) Math.round(progress * size);
        }

        private int axisToScreenY(int top, int size, char axis, double value) {
            double min = getAxisMin(axis);
            double max = getAxisMax(axis);
            double progress = Math.max(0.0, Math.min(1.0, (value - min) / Math.max(0.0001, max - min)));
            if (axis == 'y' ? profile.yPositiveUp : true) {
                progress = 1.0 - progress;
            }
            return top + (int) Math.round(progress * size);
        }

        private double screenToAxis(int left, int size, char axis, double mouseX) {
            double progress = Math.max(0.0, Math.min(1.0, (mouseX - left) / Math.max(1.0, size)));
            return getAxisMin(axis) + (getAxisMax(axis) - getAxisMin(axis)) * progress;
        }

        private double screenToAxisY(int top, int size, char axis, double mouseY) {
            double progress = Math.max(0.0, Math.min(1.0, (mouseY - top) / Math.max(1.0, size)));
            if (axis == 'y' ? profile.yPositiveUp : true) {
                progress = 1.0 - progress;
            }
            return getAxisMin(axis) + (getAxisMax(axis) - getAxisMin(axis)) * progress;
        }

        private void applyPlanePan(double mouseX, double mouseY) {
            double unitsPerPixel = (viewSpan * 2.0) / Math.max(1.0, planeSize);
            char horizontalAxis = threeDimensional ? getPlaneHorizontalAxis(active3dPlane) : 'x';
            char verticalAxis = threeDimensional ? getPlaneVerticalAxis(active3dPlane) : 'y';
            double nextHorizontal = getAxisCenter(horizontalAxis, true) - ((mouseX - panStartMouseX) * unitsPerPixel);
            double yDelta = (mouseY - panStartMouseY) * unitsPerPixel;
            double nextVertical = axisUsesPositiveUp(verticalAxis) ? getAxisCenter(verticalAxis, false) + yDelta : getAxisCenter(verticalAxis, false) - yDelta;
            setAxisCenter(horizontalAxis, nextHorizontal);
            setAxisCenter(verticalAxis, nextVertical);
        }

        private void applyZDrag(double mouseY) {
            setComponentValue(2, fromScreenZ(mouseY));
        }

        private void adjustZoom(int direction) {
            if (profile == CoordinateProfile.NORMALIZED) {
                return;
            }
            double factor = direction < 0 ? 0.82 : 1.22;
            viewSpan = Math.max(0.25, Math.min(512.0, viewSpan * factor));
        }

        private int getGridDivisions() {
            return profile == CoordinateProfile.NORMALIZED ? 10 : 12;
        }

        private int getMajorGridStride() {
            return profile == CoordinateProfile.NORMALIZED ? 2 : 3;
        }

        private boolean isMajorGridValue(double value, double majorStep) {
            if (majorStep <= 0.0) {
                return false;
            }
            double snapped = Math.round(value / majorStep) * majorStep;
            return Math.abs(snapped - value) <= Math.max(0.0001, majorStep * 0.05);
        }

        private double getGridStep() {
            if (profile == CoordinateProfile.NORMALIZED) {
                return 0.1;
            }
            double raw = (viewSpan * 2.0) / getGridDivisions();
            if (raw <= 0.0) {
                return 1.0;
            }
            double pow = Math.pow(10.0, Math.floor(Math.log10(raw)));
            double normalized = raw / pow;
            double snapped = normalized <= 1.0 ? 1.0 : normalized <= 2.0 ? 2.0 : normalized <= 5.0 ? 5.0 : 10.0;
            return snapped * pow;
        }

        private double snapCoordinate(double value) {
            if (Screen.hasShiftDown()) {
                return value;
            }
            double step = getGridStep();
            if (step <= 0.0) {
                return value;
            }
            return Math.round(value / step) * step;
        }

        private int getClosestGridScreenX(double mouseX) {
            double step = getGridStep();
            return toScreenX(snapCoordinate(fromScreenX(mouseX)));
        }

        private int getClosestGridScreenY(double mouseY) {
            double step = getGridStep();
            return toScreenY(snapCoordinate(fromScreenY(mouseY)));
        }

        private void setComponentValue(int index, double value) {
            if (index < 0 || index >= fields.size()) {
                return;
            }
            if (wholeNumber) {
                value = Math.round(value);
            } else {
                double step = Screen.hasShiftDown() ? 0.01 : 0.1;
                value = Math.round(value / step) * step;
            }
            suppressFieldPublish = true;
            fields.get(index).setText(formatCoordinateValue(value));
            suppressFieldPublish = false;
            onChange.accept(bindings.get(index).path(), value, wholeNumber);
        }

        private double getComponentValue(int index) {
            if (index < 0 || index >= fields.size()) {
                return 0.0;
            }
            try {
                return Double.parseDouble(fields.get(index).getText());
            } catch (NumberFormatException ignored) {
                return bindings.get(index).value();
            }
        }

        private int toScreenX(double value) {
            double min = getMinVisibleX();
            double max = getMaxVisibleX();
            double progress = (value - min) / Math.max(0.0001, max - min);
            progress = Math.max(0.0, Math.min(1.0, progress));
            return planeX + (int) Math.round(progress * planeSize);
        }

        private int toScreenY(double value) {
            double min = getMinVisibleY();
            double max = getMaxVisibleY();
            double progress = (value - min) / Math.max(0.0001, max - min);
            progress = Math.max(0.0, Math.min(1.0, progress));
            if (profile.yPositiveUp) {
                progress = 1.0 - progress;
            }
            return planeY + (int) Math.round(progress * planeSize);
        }

        private int toScreenZ(double value) {
            double min = getMinVisibleZ();
            double max = getMaxVisibleZ();
            double progress = (value - min) / Math.max(0.0001, max - min);
            progress = Math.max(0.0, Math.min(1.0, progress));
            return zSliderY + zSliderHeight - (int) Math.round(progress * zSliderHeight);
        }

        private double fromScreenX(double mouseX) {
            double progress = Math.max(0.0, Math.min(1.0, (mouseX - planeX) / Math.max(1.0, planeSize)));
            return getMinVisibleX() + (getMaxVisibleX() - getMinVisibleX()) * progress;
        }

        private double fromScreenY(double mouseY) {
            double progress = Math.max(0.0, Math.min(1.0, (mouseY - planeY) / Math.max(1.0, planeSize)));
            if (profile.yPositiveUp) {
                progress = 1.0 - progress;
            }
            return getMinVisibleY() + (getMaxVisibleY() - getMinVisibleY()) * progress;
        }

        private double fromScreenZ(double mouseY) {
            double progress = 1.0 - Math.max(0.0, Math.min(1.0, (mouseY - zSliderY) / Math.max(1.0, zSliderHeight)));
            return getMinVisibleZ() + (getMaxVisibleZ() - getMinVisibleZ()) * progress;
        }

        private double getMinVisibleX() {
            return profile == CoordinateProfile.NORMALIZED ? 0.0 : viewCenterX - viewSpan;
        }

        private double getMaxVisibleX() {
            return profile == CoordinateProfile.NORMALIZED ? 1.0 : viewCenterX + viewSpan;
        }

        private double getMinVisibleY() {
            return profile == CoordinateProfile.NORMALIZED ? 0.0 : viewCenterY - viewSpan;
        }

        private double getMaxVisibleY() {
            return profile == CoordinateProfile.NORMALIZED ? 1.0 : viewCenterY + viewSpan;
        }

        private double getMinVisibleZ() {
            if (profile == CoordinateProfile.NORMALIZED) {
                return 0.0;
            }
            if (profile == CoordinateProfile.VECTOR) {
                return -viewSpan;
            }
            return viewCenterZ - viewSpan;
        }

        private double getMaxVisibleZ() {
            if (profile == CoordinateProfile.NORMALIZED) {
                return 1.0;
            }
            if (profile == CoordinateProfile.VECTOR) {
                return viewSpan;
            }
            return viewCenterZ + viewSpan;
        }

        private double getAxisCenter(char axis, boolean horizontal) {
            return switch (axis) {
                case 'x' -> panStartCenterX;
                case 'y' -> horizontal ? panStartCenterY : panStartCenterY;
                default -> panStartCenterZ;
            };
        }

        private void setAxisCenter(char axis, double value) {
            switch (axis) {
                case 'x' -> viewCenterX = value;
                case 'y' -> viewCenterY = value;
                default -> viewCenterZ = value;
            }
        }

        private boolean axisUsesPositiveUp(char axis) {
            return axis == 'y' ? profile.yPositiveUp : true;
        }

        private char getPlaneFixedAxis(char plane) {
            return switch (plane) {
                case 'z' -> 'y';
                case 'y' -> 'x';
                default -> 'z';
            };
        }

        private String getPlaneFixedAxisLabel(char plane) {
            char axis = getPlaneFixedAxis(plane);
            return Character.toUpperCase(axis) + " " + formatCoordinateValue(getAxisValue(axis));
        }

        private String formatCoordinateValue(double value) {
            return wholeNumber ? String.valueOf((int) Math.round(value)) : String.format(Locale.ROOT, "%.2f", value);
        }

        private String formatAxisValue(double value) {
            return wholeNumber || Math.abs(value) >= 10.0 ? String.valueOf((int) Math.round(value)) : String.format(Locale.ROOT, "%.1f", value);
        }

        private double roundSpan(double rawSpan) {
            if (rawSpan <= 1.0) {
                return 1.0;
            }
            double pow = Math.pow(10.0, Math.floor(Math.log10(rawSpan)));
            return Math.ceil(rawSpan / pow) * pow;
        }

        private CoordinateProfile inferCoordinateProfile(String path, List<ComponentBinding> bindings) {
            String normalized = path.toLowerCase(Locale.ROOT);
            boolean normalizedRange = bindings.stream().allMatch(binding -> binding.value() >= 0.0 && binding.value() <= 1.0);
            if (normalized.contains("anchor") || normalized.contains("normalized") || normalizedRange) {
                return CoordinateProfile.NORMALIZED;
            }
            if (normalized.contains("velocity") || normalized.contains("vector") || normalized.contains("spawn")) {
                return CoordinateProfile.VECTOR;
            }
            if (normalized.contains("offset")) {
                return CoordinateProfile.OFFSET;
            }
            if (normalized.contains("position")) {
                return CoordinateProfile.POSITION;
            }
            return threeDimensional ? CoordinateProfile.WORLD : CoordinateProfile.OFFSET;
        }

        private boolean hasCoordinatePresets() {
            return profile == CoordinateProfile.NORMALIZED || profile == CoordinateProfile.VECTOR;
        }

        private void applyNextCoordinatePreset() {
            double[][] presets = profile == CoordinateProfile.NORMALIZED
                    ? new double[][]{{0.5, 0.5}, {0.0, 0.0}, {1.0, 0.0}, {0.0, 1.0}, {1.0, 1.0}}
                    : (threeDimensional
                    ? new double[][]{{0.0, 0.0, 0.0}, {0.0, 1.0, 0.0}, {1.0, 0.0, 0.0}, {0.0, 0.0, 1.0}, {-1.0, 0.0, 0.0}, {0.0, -1.0, 0.0}}
                    : new double[][]{{0.0, 0.0}, {0.0, 1.0}, {1.0, 0.0}, {-1.0, 0.0}, {0.0, -1.0}, {1.0, 1.0}});
            double[] current = new double[bindings.size()];
            for (int i = 0; i < current.length; i++) {
                current[i] = getComponentValue(i);
            }
            int nextIndex = 0;
            for (int i = 0; i < presets.length; i++) {
                if (matchesPreset(current, presets[i])) {
                    nextIndex = (i + 1) % presets.length;
                    break;
                }
            }
            double[] preset = presets[nextIndex];
            for (int i = 0; i < preset.length && i < bindings.size(); i++) {
                setComponentValue(i, preset[i]);
            }
            if (profile == CoordinateProfile.NORMALIZED) {
                initializeView();
            }
        }

        private boolean matchesPreset(double[] current, double[] preset) {
            if (current.length < preset.length) {
                return false;
            }
            for (int i = 0; i < preset.length; i++) {
                if (Math.abs(current[i] - preset[i]) > 0.05) {
                    return false;
                }
            }
            return true;
        }
    }

    private static class MultiPointCoordinateEntry extends ConfigEntry {
        private final String label;
        private final List<CoordinateClusterPointState> points = new ArrayList<>();
        private final NumericUpdateConsumer onChange;
        private final CoordinateProfile profile;
        private final boolean wholeNumber;
        private final int dimension;
        private final TextFieldWidget[] fields;
        private final List<int[]> pointChipBounds = new ArrayList<>();
        private boolean editorOpen;
        private int selectedIndex;
        private int openButtonX;
        private int buttonY;
        private int popoverX;
        private int popoverY;
        private int popoverWidth;
        private int popoverHeight;
        private int planeX;
        private int planeY;
        private int planeSize;
        private int planeTabY;
        private int xyTabX;
        private int xzTabX;
        private int yzTabX;
        private Integer requestedPopoverX;
        private Integer requestedPopoverY;
        private boolean draggingPoint;
        private boolean panningPlane;
        private double viewCenterX;
        private double viewCenterY;
        private double viewCenterZ;
        private double viewSpan;
        private int resetViewButtonX;
        private int zoomInButtonX;
        private int zoomOutButtonX;
        private int zoomButtonY;
        private int prevPointButtonX;
        private int nextPointButtonX;
        private int fitPointsButtonX;
        private int pointButtonY;
        private char active3dPlane = 'x';
        private boolean suppressFieldChanges;

        private MultiPointCoordinateEntry(String label, List<CoordinateClusterPoint> initialPoints, NumericUpdateConsumer onChange) {
            this.label = label;
            this.onChange = onChange;
            this.dimension = initialPoints.isEmpty() ? 2 : initialPoints.get(0).dimension();
            this.wholeNumber = initialPoints.stream().allMatch(point -> point.isWholeNumber());
            for (CoordinateClusterPoint point : initialPoints) {
                this.points.add(new CoordinateClusterPointState(point.name(), point.paths(), point.values().clone()));
            }
            this.profile = inferCoordinateProfile(label, initialPoints);
            this.fields = new TextFieldWidget[dimension];
            for (int i = 0; i < dimension; i++) {
                final int componentIndex = i;
                fields[i] = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 46, 18, Text.literal(axisLabel(i)));
                fields[i].setChangedListener(raw -> {
                    if (suppressFieldChanges) {
                        return;
                    }
                    try {
                        double parsed = Double.parseDouble(raw.trim());
                        setPointComponent(selectedIndex, componentIndex, parsed);
                    } catch (NumberFormatException ignored) {
                    }
                });
            }
            syncSelectedFields();
            initializeView();
        }

        @Override
        public void tick() {
            for (TextFieldWidget field : fields) {
                field.tick();
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isControlButtonHit(mouseX, mouseY, openButtonX, buttonY, 44)) {
                requestedPopoverX = (int) Math.round(mouseX);
                requestedPopoverY = (int) Math.round(mouseY);
                editorOpen = !editorOpen;
                draggingPoint = false;
                panningPlane = false;
                if (editorOpen) {
                    initializeView();
                }
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (!editorOpen) {
                return focusClickedField(mouseX, mouseY, button, fields);
            }
            if (dimension >= 3 && isControlButtonHit(mouseX, mouseY, xyTabX, planeTabY, 28)) {
                active3dPlane = 'x';
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (dimension >= 3 && isControlButtonHit(mouseX, mouseY, xzTabX, planeTabY, 28)) {
                active3dPlane = 'z';
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (dimension >= 3 && isControlButtonHit(mouseX, mouseY, yzTabX, planeTabY, 28)) {
                active3dPlane = 'y';
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, resetViewButtonX, zoomButtonY, 22)) {
                initializeView();
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, prevPointButtonX, pointButtonY, 42)) {
                moveSelectedPoint(-1);
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, nextPointButtonX, pointButtonY, 42)) {
                moveSelectedPoint(1);
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, fitPointsButtonX, pointButtonY, 42)) {
                initializeView();
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, zoomInButtonX, zoomButtonY, 22)) {
                adjustZoom(-1);
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, zoomOutButtonX, zoomButtonY, 22)) {
                adjustZoom(1);
                UiSoundHelper.playButtonClick();
                return true;
            }
            for (int i = 0; i < pointChipBounds.size(); i++) {
                int[] bounds = pointChipBounds.get(i);
                if (bounds != null && mouseX >= bounds[0] && mouseX <= bounds[0] + bounds[2] && mouseY >= bounds[1] && mouseY <= bounds[1] + bounds[3]) {
                    selectedIndex = i;
                    syncSelectedFields();
                    UiSoundHelper.playButtonClick();
                    return true;
                }
            }
            if (isInsidePlane(mouseX, mouseY)) {
                int hitPoint = hitPointIndex(mouseX, mouseY);
                if (hitPoint >= 0) {
                    selectedIndex = hitPoint;
                    syncSelectedFields();
                    applyPlaneDrag(mouseX, mouseY);
                    draggingPoint = true;
                    UiSoundHelper.playButtonClick();
                    return true;
                }
                panningPlane = true;
                return true;
            }
            return focusClickedField(mouseX, mouseY, button, fields);
        }

        @Override
        public boolean handleMouseDrag(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (button != 0) {
                return false;
            }
            if (draggingPoint) {
                applyPlaneDrag(mouseX, mouseY);
                return true;
            }
            if (panningPlane) {
                applyPlanePan(deltaX, deltaY);
                return true;
            }
            return false;
        }

        @Override
        public boolean handleMouseRelease(double mouseX, double mouseY, int button) {
            boolean handled = draggingPoint || panningPlane;
            draggingPoint = false;
            panningPlane = false;
            return handled;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            for (TextFieldWidget field : fields) {
                if (field.keyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            for (TextFieldWidget field : fields) {
                if (field.charTyped(chr, modifiers)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            double step = wholeNumber ? 1.0 : 0.1;
            for (TextFieldWidget field : fields) {
                if (isMouseOverField(field, mouseX, mouseY)) {
                    boolean changed = nudgeNumericTextField(field, amount > 0 ? step : -step, wholeNumber);
                    if (changed) {
                        UiSoundHelper.playDialClick();
                    }
                    return changed;
                }
            }
            if (editorOpen && isInsidePopover(mouseX, mouseY) && amount != 0.0) {
                if (Screen.hasShiftDown() && !points.isEmpty()) {
                    moveSelectedPoint(amount > 0 ? -1 : 1);
                } else {
                    adjustZoom(amount > 0 ? -1 : 1);
                }
                UiSoundHelper.playDialClick();
                return true;
            }
            return false;
        }

        @Override
        public void setFocused(boolean focused) {
            if (!focused) {
                for (TextFieldWidget field : fields) {
                    field.setFocused(false);
                }
                editorOpen = false;
                draggingPoint = false;
                panningPlane = false;
                active3dPlane = 'x';
                requestedPopoverX = null;
                requestedPopoverY = null;
            }
        }

        @Override
        public boolean isFocused() {
            for (TextFieldWidget field : fields) {
                if (field.isFocused()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<String> getDeleteTargets() {
            List<String> targets = new ArrayList<>();
            for (CoordinateClusterPointState point : points) {
                targets.addAll(point.paths);
            }
            return targets;
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            String subtitle = points.size() + " linked points  " + summarizePointNames();
            drawEntryText(context, label, subtitle, x + 18, y);
            int buttonWidth = 44;
            int badgeWidth = 38;
            int fieldWidth = isCompactConfigListingEnabledStatic() ? 42 : 44;
            int fieldGap = 6;
            int totalWidth = buttonWidth + 8 + badgeWidth + 10 + fields.length * fieldWidth + Math.max(0, fields.length - 1) * fieldGap;
            int currentX = getPinnedFieldStartX(x, entryWidth, totalWidth);
            openButtonX = currentX;
            buttonY = getControlRowY(y, entryHeight);
            drawControlButton(context, openButtonX, buttonY, buttonWidth, "Edit", editorOpen);
            currentX += buttonWidth + 8;
            context.fill(currentX, buttonY, currentX + badgeWidth, buttonY + 18, withAlpha(uiColorHeader, 116));
            context.drawBorder(currentX, buttonY, badgeWidth, 18, new Color(uiColorBackgroundBorder, true).getRGB());
            String chip = points.size() + " pts";
            context.drawText(MinecraftClient.getInstance().textRenderer, chip, currentX + Math.max(4, (badgeWidth - MinecraftClient.getInstance().textRenderer.getWidth(chip)) / 2), buttonY + 5, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            currentX += badgeWidth + 10;
            boolean showCaptions = showFieldCaptions();
            for (int i = 0; i < fields.length; i++) {
                if (showCaptions) {
                    context.drawText(MinecraftClient.getInstance().textRenderer, axisLabel(i), currentX, getFieldCaptionY(buttonY), new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                }
                fields[i].setWidth(fieldWidth);
                fields[i].setX(currentX);
                fields[i].setY(buttonY);
                fields[i].render(context, mouseX, mouseY, delta);
                currentX += fieldWidth + fieldGap;
            }
            drawDeleteButton(context, x, y, entryWidth);
        }

        @Override
        public void renderOverlay(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, float delta) {
            if (!editorOpen) {
                return;
            }
            context.getMatrices().push();
            context.getMatrices().translate(0.0F, 0.0F, OVERLAY_Z);
            drawClusterPopover(context, mouseX, mouseY);
            context.getMatrices().pop();
        }

        @Override
        public boolean hasModalOverlay() {
            return editorOpen;
        }

        @Override
        public boolean openInlineEditor(double mouseX, double mouseY) {
            requestedPopoverX = Double.isNaN(mouseX) ? null : (int) Math.round(mouseX);
            requestedPopoverY = Double.isNaN(mouseY) ? null : (int) Math.round(mouseY);
            editorOpen = true;
            draggingPoint = false;
            panningPlane = false;
            active3dPlane = 'x';
            initializeView();
            return true;
        }

        @Override
        public boolean handleOverlayClick(double mouseX, double mouseY, int button) {
            boolean inside = (mouseX >= popoverX && mouseX <= popoverX + popoverWidth && mouseY >= popoverY && mouseY <= popoverY + popoverHeight)
                    || isControlButtonHit(mouseX, mouseY, openButtonX, buttonY, 44);
            if (inside) {
                return mouseClicked(mouseX, mouseY, button) || (mouseX >= popoverX && mouseX <= popoverX + popoverWidth && mouseY >= popoverY && mouseY <= popoverY + popoverHeight);
            }
            editorOpen = false;
            draggingPoint = false;
            panningPlane = false;
            active3dPlane = 'x';
            requestedPopoverX = null;
            requestedPopoverY = null;
            return true;
        }

        private void drawClusterPopover(DrawContext context, int mouseX, int mouseY) {
            int chipWidth = 102;
            int chipGap = 4;
            int chipColumns = 1;
            int chipRows = Math.max(1, points.size());
            int listAreaHeight = chipRows * 18;
            int rightPanelWidth = Math.max(118, chipWidth);
            int fieldWidth = 58;
            int fieldStartOffset = 18;
            int fieldVerticalSpacing = 22;
            int fieldAreaHeight = fields.length * fieldVerticalSpacing;
            int buttonRowYLocal = 44 + fieldAreaHeight + 6;
            int buttonAreaHeight = 18;
            int listYLocal = buttonRowYLocal + buttonAreaHeight + 10;
            int rightContentHeight = listYLocal + listAreaHeight + 8;
            int previewPlaneSize = dimension >= 3 ? 118 : 114;
            popoverWidth = 150 + rightPanelWidth;
            popoverHeight = Math.max((dimension >= 3 ? 54 : 46) + previewPlaneSize + 12, rightContentHeight + 24);
            int anchorX = requestedPopoverX != null ? requestedPopoverX : openButtonX;
            int anchorY = requestedPopoverY != null ? requestedPopoverY : buttonY;
            int desiredX = anchorX - popoverWidth - 8;
            int desiredY = anchorY - 18;
            int screenWidth = MinecraftClient.getInstance().currentScreen.width;
            int screenHeight = MinecraftClient.getInstance().currentScreen.height;
            if (desiredX < 8 && anchorX + 52 + popoverWidth <= screenWidth - 8) {
                desiredX = anchorX + 52;
            }
            int maxX = screenWidth - popoverWidth - 8;
            int maxY = screenHeight - popoverHeight - 8;
            popoverX = Math.max(8, Math.min(desiredX, maxX));
            popoverY = Math.max(8, Math.min(desiredY, maxY));
            context.fill(popoverX, popoverY, popoverX + popoverWidth, popoverY + popoverHeight, withAlpha(uiColorContentBase, 236));
            context.drawBorder(popoverX, popoverY, popoverWidth, popoverHeight, new Color(uiColorBackgroundBorder, true).getRGB());

            planeSize = previewPlaneSize;
            planeX = popoverX + 10;
            planeY = popoverY + (dimension >= 3 ? 40 : 24);
            int titleX = planeX;
            if (dimension >= 3) {
                planeTabY = popoverY + 18;
                xyTabX = planeX;
                xzTabX = planeX + 32;
                yzTabX = planeX + 64;
                titleX = yzTabX + 36;
                drawControlButton(context, xyTabX, planeTabY, 28, "XY", active3dPlane == 'x');
                drawControlButton(context, xzTabX, planeTabY, 28, "XZ", active3dPlane == 'z');
                drawControlButton(context, yzTabX, planeTabY, 28, "YZ", active3dPlane == 'y');
            }
            drawPlane(context, mouseX, mouseY);
            int titleWidth = Math.max(40, popoverWidth - (titleX - popoverX) - 84);
            context.drawText(MinecraftClient.getInstance().textRenderer, fitInline(profile.label + " cluster", titleWidth), titleX, popoverY + 10, new Color(uiColorContentBaseTitleText, true).getRGB(), false);

            resetViewButtonX = popoverX + popoverWidth - 74;
            zoomInButtonX = popoverX + popoverWidth - 50;
            zoomOutButtonX = popoverX + popoverWidth - 26;
            zoomButtonY = popoverY + 8;
            drawControlButton(context, resetViewButtonX, zoomButtonY, 22, "R", false);
            drawControlButton(context, zoomInButtonX, zoomButtonY, 22, "+", true);
            drawControlButton(context, zoomOutButtonX, zoomButtonY, 22, "-", true);

            int rightPanelX = planeX + planeSize + 12;
            CoordinateClusterPointState selected = points.get(selectedIndex);
            context.drawText(MinecraftClient.getInstance().textRenderer, fitInline("Selected  " + selected.name, rightPanelWidth), rightPanelX, popoverY + 26, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            for (int i = 0; i < fields.length; i++) {
                int fieldY = popoverY + 44 + (i * fieldVerticalSpacing);
                int fieldX = rightPanelX + fieldStartOffset;
                int axisColor = i == 0 ? new Color(uiColorConfigAxisX, true).getRGB() : i == 1 ? new Color(uiColorConfigAxisY, true).getRGB() : new Color(uiColorConfigAxisZ, true).getRGB();
                context.drawText(MinecraftClient.getInstance().textRenderer, axisLabel(i), rightPanelX, fieldY + 5, axisColor, false);
                fields[i].setWidth(fieldWidth);
                fields[i].setX(fieldX);
                fields[i].setY(fieldY);
                fields[i].render(context, mouseX, mouseY, 0);
            }

            pointButtonY = popoverY + buttonRowYLocal;
            prevPointButtonX = rightPanelX;
            nextPointButtonX = prevPointButtonX + 40;
            fitPointsButtonX = nextPointButtonX + 40;
            drawControlButton(context, prevPointButtonX, pointButtonY, 38, "Prev", false);
            drawControlButton(context, nextPointButtonX, pointButtonY, 38, "Next", false);
            drawControlButton(context, fitPointsButtonX, pointButtonY, 38, "Fit", false);

            int listY = popoverY + listYLocal;
            pointChipBounds.clear();
            for (int i = 0; i < points.size(); i++) {
                int row = i;
                int chipX = rightPanelX;
                int chipY = listY + row * 18;
                boolean active = i == selectedIndex;
                context.fill(chipX, chipY, chipX + chipWidth, chipY + 16, active ? withAlpha(uiColorHeaderStripe, 176) : withAlpha(uiColorHeader, 112));
                context.drawBorder(chipX, chipY, chipWidth, 16, new Color(uiColorBackgroundBorder, true).getRGB());
                String pointLabel = fitInline(points.get(i).name, chipWidth - 12);
                context.drawText(MinecraftClient.getInstance().textRenderer, pointLabel, chipX + 6, chipY + 4, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
                pointChipBounds.add(new int[]{chipX, chipY, chipWidth, 16});
            }
        }

        private void drawPlane(DrawContext context, int mouseX, int mouseY) {
            boolean hovered = isInsidePlane(mouseX, mouseY);
            context.fill(planeX, planeY, planeX + planeSize, planeY + planeSize, withAlpha(uiColorHeader, hovered ? 116 : 98));
            context.drawBorder(planeX, planeY, planeSize, planeSize, new Color(uiColorBackgroundBorder, true).getRGB());
            char horizontalAxis = getClusterPlaneHorizontalAxis();
            char verticalAxis = getClusterPlaneVerticalAxis();
            double minH = getAxisMin(horizontalAxis);
            double maxH = getAxisMax(horizontalAxis);
            double minV = getAxisMin(verticalAxis);
            double maxV = getAxisMax(verticalAxis);
            double gridStep = getGridStep();
            double majorStep = gridStep * getMajorGridStride();
            int hoveredGridX = hovered ? axisToScreen(horizontalAxis, snapCoordinate(screenToAxis(horizontalAxis, mouseX))) : Integer.MIN_VALUE;
            int hoveredGridY = hovered ? axisToScreenY(verticalAxis, snapCoordinate(screenToAxisY(verticalAxis, mouseY))) : Integer.MIN_VALUE;
            context.enableScissor(planeX + 1, planeY + 1, planeX + planeSize, planeY + planeSize);
            int renderedVerticalGridLines = 0;
            for (double grid = Math.floor(minH / gridStep) * gridStep; grid <= maxH + (gridStep * 0.5) && renderedVerticalGridLines++ < MAX_COORDINATE_GRID_LINES; grid += gridStep) {
                int px = axisToScreen(horizontalAxis, grid);
                int color = px == hoveredGridX ? withAlpha(uiColorContentBaseTitleText, 120) : isMajorGridValue(grid, majorStep) ? withAlpha(uiColorBackgroundBorder, 110) : withAlpha(uiColorBackgroundBorder, 58);
                context.fill(px, planeY, px + 1, planeY + planeSize, color);
            }
            int renderedHorizontalGridLines = 0;
            for (double grid = Math.floor(minV / gridStep) * gridStep; grid <= maxV + (gridStep * 0.5) && renderedHorizontalGridLines++ < MAX_COORDINATE_GRID_LINES; grid += gridStep) {
                int py = axisToScreenY(verticalAxis, grid);
                int color = py == hoveredGridY ? withAlpha(uiColorContentBaseTitleText, 120) : isMajorGridValue(grid, majorStep) ? withAlpha(uiColorBackgroundBorder, 110) : withAlpha(uiColorBackgroundBorder, 58);
                context.fill(planeX, py, planeX + planeSize, py + 1, color);
            }
            drawClusterAxisLine(context, horizontalAxis, true);
            drawClusterAxisLine(context, verticalAxis, false);
            for (int i = 0; i < points.size(); i++) {
                CoordinateClusterPointState point = points.get(i);
                int pointX = axisToScreen(horizontalAxis, point.values[axisIndex(horizontalAxis)]);
                int pointY = axisToScreenY(verticalAxis, point.values[axisIndex(verticalAxis)]);
                int color = pointColor(i);
                context.fill(pointX - 2, pointY - 2, pointX + 3, pointY + 3, color);
                context.fill(pointX - 4, pointY, pointX + 5, pointY + 1, withAlpha(color, 160));
                context.fill(pointX, pointY - 4, pointX + 1, pointY + 5, withAlpha(color, 160));
                if (i == selectedIndex) {
                    context.fill(pointX, planeY, pointX + 1, planeY + planeSize, withAlpha(axisColor(horizontalAxis), 72));
                    context.fill(planeX, pointY, planeX + planeSize, pointY + 1, withAlpha(axisColor(verticalAxis), 72));
                    context.drawBorder(pointX - 5, pointY - 5, 10, 10, new Color(uiColorContentBaseTitleText, true).getRGB());
                }
            }
            if (hovered) {
                int hoverX = (int) Math.max(planeX, Math.min(planeX + planeSize, mouseX));
                int hoverY = (int) Math.max(planeY, Math.min(planeY + planeSize, mouseY));
                context.fill(hoverX, planeY, hoverX + 1, planeY + planeSize, withAlpha(uiColorContentBaseTitleText, 34));
                context.fill(planeX, hoverY, planeX + planeSize, hoverY + 1, withAlpha(uiColorContentBaseTitleText, 34));
                String hoverLabel = Character.toUpperCase(horizontalAxis) + " " + formatAxisValue(screenToAxis(horizontalAxis, mouseX))
                        + ", " + Character.toUpperCase(verticalAxis) + " " + formatAxisValue(screenToAxisY(verticalAxis, mouseY));
                int hoverWidth = MinecraftClient.getInstance().textRenderer.getWidth(hoverLabel);
                context.fill(planeX + 6, planeY + 6, planeX + 12 + hoverWidth, planeY + 18, withAlpha(uiColorContentBase, 228));
                context.drawText(MinecraftClient.getInstance().textRenderer, hoverLabel, planeX + 9, planeY + 9, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            }
            context.disableScissor();
            if (dimension < 3) {
                String planeTitle = "" + Character.toUpperCase(horizontalAxis) + Character.toUpperCase(verticalAxis);
                context.drawText(MinecraftClient.getInstance().textRenderer, planeTitle, planeX + 4, planeY - 10, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            }
        }

        private int pointColor(int index) {
            int[] palette = {new Color(uiColorConfigAxisX, true).getRGB(), new Color(uiColorConfigAxisY, true).getRGB(), new Color(uiColorConfigAxisZ, true).getRGB(), new Color(uiColorConfigPointYellow, true).getRGB(), new Color(uiColorConfigPointPurple, true).getRGB(), new Color(uiColorConfigPointCyan, true).getRGB()};
            return palette[index % palette.length];
        }

        private void applyPlaneDrag(double mouseX, double mouseY) {
            char horizontalAxis = getClusterPlaneHorizontalAxis();
            char verticalAxis = getClusterPlaneVerticalAxis();
            setPointComponent(selectedIndex, axisIndex(horizontalAxis), screenToAxis(horizontalAxis, mouseX));
            setPointComponent(selectedIndex, axisIndex(verticalAxis), screenToAxisY(verticalAxis, mouseY));
        }

        private void applyPlanePan(double deltaX, double deltaY) {
            double unitsPerPixel = (viewSpan * 2.0) / planeSize;
            char horizontalAxis = getClusterPlaneHorizontalAxis();
            char verticalAxis = getClusterPlaneVerticalAxis();
            setAxisCenter(horizontalAxis, getAxisCenter(horizontalAxis) - (deltaX * unitsPerPixel));
            double direction = axisUsesPositiveUp(verticalAxis) ? 1.0 : -1.0;
            setAxisCenter(verticalAxis, getAxisCenter(verticalAxis) + (deltaY * unitsPerPixel * direction));
        }

        private int hitPointIndex(double mouseX, double mouseY) {
            char horizontalAxis = getClusterPlaneHorizontalAxis();
            char verticalAxis = getClusterPlaneVerticalAxis();
            for (int i = 0; i < points.size(); i++) {
                CoordinateClusterPointState point = points.get(i);
                int pointX = axisToScreen(horizontalAxis, point.values[axisIndex(horizontalAxis)]);
                int pointY = axisToScreenY(verticalAxis, point.values[axisIndex(verticalAxis)]);
                if (mouseX >= pointX - 5 && mouseX <= pointX + 5 && mouseY >= pointY - 5 && mouseY <= pointY + 5) {
                    return i;
                }
            }
            return -1;
        }

        private void initializeView() {
            if (points.isEmpty()) {
                viewCenterX = 0.0;
                viewCenterY = 0.0;
                viewCenterZ = 0.0;
                viewSpan = 8.0;
                return;
            }
            if (profile == CoordinateProfile.NORMALIZED) {
                viewCenterX = 0.5;
                viewCenterY = 0.5;
                viewCenterZ = 0.5;
                viewSpan = 0.5;
                return;
            }
            double minX = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;
            for (CoordinateClusterPointState point : points) {
                minX = Math.min(minX, point.values[0]);
                maxX = Math.max(maxX, point.values[0]);
                minY = Math.min(minY, point.values[1]);
                maxY = Math.max(maxY, point.values[1]);
                if (dimension >= 3) {
                    minZ = Math.min(minZ, point.values[2]);
                    maxZ = Math.max(maxZ, point.values[2]);
                }
            }
            viewCenterX = (minX + maxX) / 2.0;
            viewCenterY = (minY + maxY) / 2.0;
            viewCenterZ = dimension >= 3 ? (minZ + maxZ) / 2.0 : 0.0;
            double halfSpan = Math.max(Math.max(Math.abs(maxX - viewCenterX), Math.abs(maxY - viewCenterY)), profile == CoordinateProfile.WORLD ? 16.0 : 8.0);
            if (dimension >= 3) {
                halfSpan = Math.max(halfSpan, Math.abs(maxZ - viewCenterZ));
            }
            viewSpan = roundSpan(Math.max(halfSpan * 1.3, 1.0));
        }

        private void adjustZoom(int direction) {
            if (profile == CoordinateProfile.NORMALIZED) {
                return;
            }
            double factor = direction < 0 ? 0.82 : 1.22;
            viewSpan = Math.max(0.25, Math.min(512.0, viewSpan * factor));
        }

        private boolean isInsidePopover(double mouseX, double mouseY) {
            return editorOpen
                    && mouseX >= popoverX
                    && mouseX <= popoverX + popoverWidth
                    && mouseY >= popoverY
                    && mouseY <= popoverY + popoverHeight;
        }

        private void moveSelectedPoint(int direction) {
            if (points.isEmpty()) {
                return;
            }
            selectedIndex = (selectedIndex + direction + points.size()) % points.size();
            syncSelectedFields();
        }

        private void syncSelectedFields() {
            if (points.isEmpty()) {
                return;
            }
            selectedIndex = Math.max(0, Math.min(selectedIndex, points.size() - 1));
            CoordinateClusterPointState point = points.get(selectedIndex);
            suppressFieldChanges = true;
            for (int i = 0; i < fields.length; i++) {
                fields[i].setText(formatCoordinateValue(point.values[i]));
            }
            suppressFieldChanges = false;
        }

        private void setPointComponent(int pointIndex, int componentIndex, double value) {
            if (points.isEmpty() || pointIndex < 0 || pointIndex >= points.size()) {
                return;
            }
            double next = wholeNumber ? Math.round(value) : value;
            CoordinateClusterPointState point = points.get(pointIndex);
            point.values[componentIndex] = next;
            onChange.accept(point.paths.get(componentIndex), next, wholeNumber);
            if (pointIndex == selectedIndex) {
                syncSelectedFields();
            }
        }

        private double getMinVisibleX() {
            return profile == CoordinateProfile.NORMALIZED ? 0.0 : viewCenterX - viewSpan;
        }

        private double getMaxVisibleX() {
            return profile == CoordinateProfile.NORMALIZED ? 1.0 : viewCenterX + viewSpan;
        }

        private double getMinVisibleY() {
            return profile == CoordinateProfile.NORMALIZED ? 0.0 : viewCenterY - viewSpan;
        }

        private double getMaxVisibleY() {
            return profile == CoordinateProfile.NORMALIZED ? 1.0 : viewCenterY + viewSpan;
        }

        private double getMinVisibleZ() {
            if (profile == CoordinateProfile.NORMALIZED) {
                return 0.0;
            }
            if (profile == CoordinateProfile.VECTOR) {
                return -viewSpan;
            }
            return viewCenterZ - viewSpan;
        }

        private double getMaxVisibleZ() {
            if (profile == CoordinateProfile.NORMALIZED) {
                return 1.0;
            }
            if (profile == CoordinateProfile.VECTOR) {
                return viewSpan;
            }
            return viewCenterZ + viewSpan;
        }

        private int toScreenX(double value) {
            double normalized = (value - getMinVisibleX()) / Math.max(0.0001, getMaxVisibleX() - getMinVisibleX());
            return planeX + (int) Math.round(normalized * planeSize);
        }

        private int toScreenY(double value) {
            double normalized = (value - getMinVisibleY()) / Math.max(0.0001, getMaxVisibleY() - getMinVisibleY());
            return profile.yPositiveUp
                    ? planeY + planeSize - (int) Math.round(normalized * planeSize)
                    : planeY + (int) Math.round(normalized * planeSize);
        }

        private double fromScreenX(double mouseX) {
            double normalized = (mouseX - planeX) / Math.max(1.0, planeSize);
            normalized = Math.max(0.0, Math.min(1.0, normalized));
            return getMinVisibleX() + normalized * (getMaxVisibleX() - getMinVisibleX());
        }

        private double fromScreenY(double mouseY) {
            double normalized = (mouseY - planeY) / Math.max(1.0, planeSize);
            normalized = Math.max(0.0, Math.min(1.0, normalized));
            if (profile.yPositiveUp) {
                normalized = 1.0 - normalized;
            }
            return getMinVisibleY() + normalized * (getMaxVisibleY() - getMinVisibleY());
        }

        private boolean isInsidePlane(double mouseX, double mouseY) {
            return mouseX >= planeX && mouseX <= planeX + planeSize && mouseY >= planeY && mouseY <= planeY + planeSize;
        }

        private int axisIndex(char axis) {
            return axis == 'x' ? 0 : axis == 'y' ? 1 : 2;
        }

        private double getAxisMin(char axis) {
            return axis == 'x' ? getMinVisibleX() : axis == 'y' ? getMinVisibleY() : getMinVisibleZ();
        }

        private double getAxisMax(char axis) {
            return axis == 'x' ? getMaxVisibleX() : axis == 'y' ? getMaxVisibleY() : getMaxVisibleZ();
        }

        private double getAxisCenter(char axis) {
            return axis == 'x' ? viewCenterX : axis == 'y' ? viewCenterY : viewCenterZ;
        }

        private void setAxisCenter(char axis, double value) {
            if (axis == 'x') {
                viewCenterX = value;
            } else if (axis == 'y') {
                viewCenterY = value;
            } else {
                viewCenterZ = value;
            }
        }

        private boolean axisUsesPositiveUp(char axis) {
            return axis == 'y' ? profile.yPositiveUp : true;
        }

        private char getClusterPlaneHorizontalAxis() {
            if (dimension < 3) {
                return 'x';
            }
            return active3dPlane == 'y' ? 'y' : 'x';
        }

        private char getClusterPlaneVerticalAxis() {
            if (dimension < 3) {
                return 'y';
            }
            return active3dPlane == 'x' ? 'y' : 'z';
        }

        private char getClusterPlaneFixedAxis(char plane) {
            return plane == 'z' ? 'y' : plane == 'y' ? 'x' : 'z';
        }

        private String getClusterPlaneFixedAxisLabel(char plane) {
            char axis = getClusterPlaneFixedAxis(plane);
            double value = points.isEmpty() ? getAxisCenter(axis) : points.get(Math.max(0, Math.min(selectedIndex, points.size() - 1))).values[axisIndex(axis)];
            return Character.toUpperCase(axis) + " " + formatCoordinateValue(value);
        }

        private int axisToScreen(char axis, double value) {
            double normalized = (value - getAxisMin(axis)) / Math.max(0.0001, getAxisMax(axis) - getAxisMin(axis));
            normalized = Math.max(0.0, Math.min(1.0, normalized));
            return planeX + (int) Math.round(normalized * planeSize);
        }

        private int axisToScreenY(char axis, double value) {
            double normalized = (value - getAxisMin(axis)) / Math.max(0.0001, getAxisMax(axis) - getAxisMin(axis));
            normalized = Math.max(0.0, Math.min(1.0, normalized));
            if (axisUsesPositiveUp(axis)) {
                normalized = 1.0 - normalized;
            }
            return planeY + (int) Math.round(normalized * planeSize);
        }

        private double screenToAxis(char axis, double mouseX) {
            double normalized = Math.max(0.0, Math.min(1.0, (mouseX - planeX) / Math.max(1.0, planeSize)));
            return getAxisMin(axis) + normalized * (getAxisMax(axis) - getAxisMin(axis));
        }

        private double screenToAxisY(char axis, double mouseY) {
            double normalized = Math.max(0.0, Math.min(1.0, (mouseY - planeY) / Math.max(1.0, planeSize)));
            if (axisUsesPositiveUp(axis)) {
                normalized = 1.0 - normalized;
            }
            return getAxisMin(axis) + normalized * (getAxisMax(axis) - getAxisMin(axis));
        }

        private void drawClusterAxisLine(DrawContext context, char axis, boolean verticalLine) {
            double zero = 0.0;
            if (zero < getAxisMin(axis) || zero > getAxisMax(axis)) {
                return;
            }
            if (verticalLine) {
                int px = axisToScreen(axis, zero);
                context.fill(px, planeY, px + 1, planeY + planeSize, axisColor(axis));
            } else {
                int py = axisToScreenY(axis, zero);
                context.fill(planeX, py, planeX + planeSize, py + 1, axisColor(axis));
            }
        }

        private int axisColor(char axis) {
            return axis == 'x' ? new Color(uiColorConfigAxisX, true).getRGB() : axis == 'y' ? new Color(uiColorConfigAxisY, true).getRGB() : new Color(uiColorConfigAxisZ, true).getRGB();
        }

        private double getGridStep() {
            if (profile == CoordinateProfile.NORMALIZED) {
                return 0.1;
            }
            double raw = (viewSpan * 2.0) / 12.0;
            if (raw <= 0.0) {
                return 1.0;
            }
            double pow = Math.pow(10.0, Math.floor(Math.log10(raw)));
            double normalized = raw / pow;
            double snapped = normalized <= 1.0 ? 1.0 : normalized <= 2.0 ? 2.0 : normalized <= 5.0 ? 5.0 : 10.0;
            return snapped * pow;
        }

        private int getMajorGridStride() {
            return profile == CoordinateProfile.NORMALIZED ? 2 : 3;
        }

        private double snapCoordinate(double value) {
            if (Screen.hasShiftDown()) {
                return value;
            }
            double step = getGridStep();
            if (step <= 0.0) {
                return value;
            }
            return Math.round(value / step) * step;
        }

        private boolean isMajorGridValue(double value, double majorStep) {
            if (majorStep <= 0.0) {
                return false;
            }
            double snapped = Math.round(value / majorStep) * majorStep;
            return Math.abs(snapped - value) <= Math.max(0.0001, majorStep * 0.05);
        }

        private String formatCoordinateValue(double value) {
            return wholeNumber ? String.valueOf((int) Math.round(value)) : String.format(Locale.ROOT, "%.2f", value);
        }

        private String formatAxisValue(double value) {
            return wholeNumber || Math.abs(value) >= 10.0 ? String.valueOf((int) Math.round(value)) : String.format(Locale.ROOT, "%.1f", value);
        }

        private double roundSpan(double rawSpan) {
            if (rawSpan <= 0.5) {
                return 0.5;
            }
            double pow = Math.pow(10.0, Math.floor(Math.log10(rawSpan)));
            return Math.ceil(rawSpan / pow) * pow;
        }

        private CoordinateProfile inferCoordinateProfile(String path, List<CoordinateClusterPoint> initialPoints) {
            String normalized = path.toLowerCase(Locale.ROOT);
            boolean normalizedRange = initialPoints.stream().allMatch(point -> point.values()[0] >= 0.0 && point.values()[0] <= 1.0 && point.values()[1] >= 0.0 && point.values()[1] <= 1.0);
            if (normalized.contains("anchor") || normalized.contains("normalized") || normalizedRange) {
                return CoordinateProfile.NORMALIZED;
            }
            if (normalized.contains("velocity") || normalized.contains("vector") || normalized.contains("spawn")) {
                return CoordinateProfile.VECTOR;
            }
            if (normalized.contains("offset")) {
                return CoordinateProfile.OFFSET;
            }
            if (normalized.contains("position")) {
                return CoordinateProfile.POSITION;
            }
            return dimension >= 3 ? CoordinateProfile.WORLD : CoordinateProfile.OFFSET;
        }

        private String summarizePointNames() {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < Math.min(3, points.size()); i++) {
                names.add(points.get(i).name);
            }
            if (points.size() > 3) {
                names.add("...");
            }
            return String.join("  ", names);
        }

        private String axisLabel(int index) {
            return index == 0 ? "X" : index == 1 ? "Y" : "Z";
        }

        private String fitInline(String text, int maxWidth) {
            TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
            if (renderer.getWidth(text) <= maxWidth) {
                return text;
            }
            String ellipsis = "...";
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                String next = builder.toString() + text.charAt(i);
                if (renderer.getWidth(next + ellipsis) > maxWidth) {
                    break;
                }
                builder.append(text.charAt(i));
            }
            return builder + ellipsis;
        }

        private static class CoordinateClusterPointState {
            private final String name;
            private final List<String> paths;
            private final double[] values;

            private CoordinateClusterPointState(String name, List<String> paths, double[] values) {
                this.name = name;
                this.paths = paths;
                this.values = values;
            }
        }
    }

    private static class ColorGroupEntry extends ConfigEntry {
        private final String label;
        private final List<TextFieldWidget> fields = new ArrayList<>();
        private final List<ComponentBinding> bindings;
        private final NumericUpdateConsumer onChange;
        private final boolean integerColor;
        private final boolean supportsAlpha;
        private boolean suppressFieldChange;
        private float hue;
        private float saturation;
        private float brightness;
        private int alpha;
        private int paletteX;
        private int paletteY;
        private int paletteWidth;
        private int paletteHeight;
        private int alphaBarX;
        private int alphaBarY;
        private int alphaBarWidth;
        private int brightnessBarX;
        private int brightnessBarY;
        private int brightnessBarWidth;
        private int swatchX;
        private int swatchY;
        private int swatchSize;
        private int popoverX;
        private int popoverY;
        private int popoverWidth;
        private int popoverHeight;
        private boolean draggingPalette;
        private boolean draggingAlpha;
        private boolean draggingBrightness;
        private boolean pickerOpen;
        private Integer requestedPopoverX;
        private Integer requestedPopoverY;

        private ColorGroupEntry(String label, List<ComponentBinding> bindings, NumericUpdateConsumer onChange) {
            this.label = label;
            this.bindings = bindings;
            this.onChange = onChange;
            this.integerColor = bindings.stream().allMatch(binding -> Math.rint(binding.value()) == binding.value());
            this.supportsAlpha = bindings.stream().anyMatch(binding -> binding.label().equals("A"));

            for (ComponentBinding binding : bindings) {
                TextFieldWidget field = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 34, 18, Text.literal(binding.label()));
                field.setText(integerColor ? String.valueOf((int) Math.round(binding.value())) : String.valueOf(binding.value()));
                field.setChangedListener(value -> {
                    if (suppressFieldChange) {
                        return;
                    }
                    try {
                        onChange.accept(binding.path(), Double.parseDouble(value), integerColor);
                        syncColorFromFields();
                    } catch (NumberFormatException ignored) {
                    }
                });
                fields.add(field);
            }
            syncColorFromFields();
        }

        public void tick() {
            fields.forEach(TextFieldWidget::tick);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (focusClickedField(mouseX, mouseY, button, fields.toArray(new TextFieldWidget[0]))) {
                pickerOpen = false;
                requestedPopoverX = null;
                requestedPopoverY = null;
                return true;
            }
            if (button == 0 && isInsideSwatch(mouseX, mouseY)) {
                requestedPopoverX = (int) Math.round(mouseX);
                requestedPopoverY = (int) Math.round(mouseY);
                pickerOpen = !pickerOpen;
                UiSoundHelper.playButtonClick();
                if (!pickerOpen) {
                    requestedPopoverX = null;
                    requestedPopoverY = null;
                }
                draggingPalette = false;
                draggingAlpha = false;
                draggingBrightness = false;
                return true;
            }
            if (button == 0 && isInsidePalette(mouseX, mouseY)) {
                draggingPalette = true;
                applyPaletteSelection(mouseX, mouseY);
                return true;
            }
            if (supportsAlpha && button == 0 && isInsideAlphaBar(mouseX, mouseY)) {
                draggingAlpha = true;
                applyAlphaSelection(mouseX);
                return true;
            }
            if (button == 0 && isInsideBrightnessBar(mouseX, mouseY)) {
                draggingBrightness = true;
                applyBrightnessSelection(mouseX);
                return true;
            }
            pickerOpen = false;
            requestedPopoverX = null;
            requestedPopoverY = null;
            return false;
        }

        @Override
        public boolean handleMouseDrag(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (button != 0) {
                return false;
            }
            if (draggingPalette) {
                applyPaletteSelection(mouseX, mouseY);
                return true;
            }
            if (draggingAlpha) {
                applyAlphaSelection(mouseX);
                return true;
            }
            if (draggingBrightness) {
                applyBrightnessSelection(mouseX);
                return true;
            }
            return false;
        }

        @Override
        public boolean handleMouseRelease(double mouseX, double mouseY, int button) {
            boolean wasDragging = draggingPalette || draggingAlpha || draggingBrightness;
            draggingPalette = false;
            draggingAlpha = false;
            draggingBrightness = false;
            return wasDragging;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            for (TextFieldWidget field : fields) {
                if (field.keyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            for (TextFieldWidget field : fields) {
                if (field.charTyped(chr, modifiers)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            double step = integerColor ? 1.0 : 0.01;
            for (TextFieldWidget field : fields) {
                if (isMouseOverField(field, mouseX, mouseY)) {
                    boolean changed = nudgeNumericTextField(field, amount > 0 ? step : -step, integerColor);
                    if (changed) {
                        UiSoundHelper.playDialClick();
                    }
                    return changed;
                }
            }
            return false;
        }

        @Override
        public void setFocused(boolean focused) {
            if (!focused) {
                fields.forEach(field -> field.setFocused(false));
                pickerOpen = false;
                requestedPopoverX = null;
                requestedPopoverY = null;
                draggingPalette = false;
                draggingAlpha = false;
                draggingBrightness = false;
            }
        }

        @Override
        public boolean isFocused() {
            return fields.stream().anyMatch(TextFieldWidget::isFocused);
        }

        @Override
        public int getPreferredHeight(int defaultHeight) {
            return defaultHeight;
        }

        @Override
        public List<String> getDeleteTargets() {
            List<String> targets = new ArrayList<>();
            for (ComponentBinding binding : bindings) {
                targets.add(binding.path());
            }
            return targets;
        }

        @Override
        public String getPrimaryPath() {
            return label;
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            int boxX = x + 16;
            int fieldWidth = supportsAlpha ? 32 : 36;
            boolean showCaptions = showFieldCaptions();
            int labelReserve = showCaptions ? 8 : 0;
            int fieldGap = 4;
            int segmentWidth = labelReserve + fieldWidth;
            int totalFieldWidth = (fields.size() * segmentWidth) + ((fields.size() - 1) * fieldGap);
            int fieldStartX = getPinnedFieldStartX(x, entryWidth, totalFieldWidth + 20) + 20;
            swatchX = fieldStartX - 20;
            swatchY = showCaptions ? y + 18 : y + 14;
            swatchSize = 18;
            paletteX = swatchX + swatchSize + 6;
            paletteY = y + 14;
            paletteWidth = 68;
            paletteHeight = 34;
            alphaBarX = paletteX;
            alphaBarY = paletteY + paletteHeight + 6;
            alphaBarWidth = paletteWidth;
            brightnessBarX = paletteX;
            brightnessBarY = paletteY + paletteHeight + 6;
            brightnessBarWidth = paletteWidth;
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, supportsAlpha ? "Color picker + RGBA tuning" : "Color picker + RGB tuning", boxX, y);

            int previewColor = computePreviewColor();
            context.fill(swatchX, swatchY, swatchX + swatchSize, swatchY + swatchSize, previewColor);
            context.drawBorder(swatchX, swatchY, swatchSize, swatchSize, new Color(uiColorBackgroundBorder, true).getRGB());

            for (int i = 0; i < fields.size(); i++) {
                ComponentBinding binding = bindings.get(i);
                TextFieldWidget field = fields.get(i);
                int segmentX = fieldStartX + i * (segmentWidth + fieldGap);
                int fieldX = segmentX + labelReserve;
                if (showCaptions) {
                    context.drawText(MinecraftClient.getInstance().textRenderer, Text.literal(binding.label()), fieldX, y + 9, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                }
                field.setWidth(fieldWidth);
                field.setX(fieldX);
                field.setY(showCaptions ? y + 18 : y + 14);
                field.render(context, mouseX, mouseY, delta);
            }
            drawDeleteButton(context, x, y, entryWidth);
        }

        @Override
        public void renderOverlay(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, float delta) {
            if (!pickerOpen) {
                return;
            }
            context.getMatrices().push();
            context.getMatrices().translate(0.0F, 0.0F, OVERLAY_Z);
            drawPickerPopover(context, computePreviewColor());
            context.getMatrices().pop();
        }

        @Override
        public boolean hasModalOverlay() {
            return pickerOpen;
        }

        @Override
        public boolean openInlineEditor(double mouseX, double mouseY) {
            syncColorFromFields();
            requestedPopoverX = Double.isNaN(mouseX) ? null : (int) Math.round(mouseX);
            requestedPopoverY = Double.isNaN(mouseY) ? null : (int) Math.round(mouseY);
            pickerOpen = true;
            draggingPalette = false;
            draggingAlpha = false;
            draggingBrightness = false;
            return true;
        }

        @Override
        public boolean handleOverlayClick(double mouseX, double mouseY, int button) {
            boolean inside = isInsideSwatch(mouseX, mouseY)
                    || isInsidePalette(mouseX, mouseY)
                    || isInsideAlphaBar(mouseX, mouseY)
                    || isInsideBrightnessBar(mouseX, mouseY)
                    || fields.stream().anyMatch(field -> isMouseOverField(field, mouseX, mouseY))
                    || (mouseX >= popoverX && mouseX <= popoverX + popoverWidth && mouseY >= popoverY && mouseY <= popoverY + popoverHeight);
            if (inside) {
                return mouseClicked(mouseX, mouseY, button);
            }
            pickerOpen = false;
            requestedPopoverX = null;
            requestedPopoverY = null;
            draggingPalette = false;
            draggingAlpha = false;
            draggingBrightness = false;
            return true;
        }

        private int computePreviewColor() {
            double red = getComponentValue("R");
            double green = getComponentValue("G");
            double blue = getComponentValue("B");
            double alpha = getComponentValue("A");

            int r = normalizeColor(red);
            int g = normalizeColor(green);
            int b = normalizeColor(blue);
            int a = supportsAlpha ? normalizeAlpha(alpha) : 255;
            return new Color(r, g, b, a).getRGB();
        }

        private double getComponentValue(String component) {
            for (int i = 0; i < bindings.size(); i++) {
                if (bindings.get(i).label().equals(component)) {
                    try {
                        return Double.parseDouble(fields.get(i).getText());
                    } catch (NumberFormatException ignored) {
                        return bindings.get(i).value();
                    }
                }
            }
            return component.equals("A") ? 1.0 : 0.0;
        }

        private int normalizeColor(double value) {
            if (integerColor) {
                return Math.max(0, Math.min(255, (int) Math.round(value)));
            }
            return Math.max(0, Math.min(255, (int) Math.round(value * 255.0)));
        }

        private int normalizeAlpha(double value) {
            return normalizeColor(value);
        }

        private void syncColorFromFields() {
            int red = normalizeColor(getComponentValue("R"));
            int green = normalizeColor(getComponentValue("G"));
            int blue = normalizeColor(getComponentValue("B"));
            float[] hsb = Color.RGBtoHSB(red, green, blue, null);
            hue = hsb[0];
            saturation = hsb[1];
            brightness = hsb[2];
            alpha = supportsAlpha ? normalizeAlpha(getComponentValue("A")) : 255;
        }

        private void publishPaletteColor() {
            int rgb = Color.HSBtoRGB(hue, saturation, brightness);
            int red = (rgb >> 16) & 0xFF;
            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;
            suppressFieldChange = true;
            for (int i = 0; i < bindings.size(); i++) {
                ComponentBinding binding = bindings.get(i);
                int value = switch (binding.label()) {
                    case "R" -> red;
                    case "G" -> green;
                    case "B" -> blue;
                    case "A" -> alpha;
                    default -> 0;
                };
                String text = integerColor ? String.valueOf(value) : String.format(Locale.ROOT, "%.3f", value / 255.0);
                fields.get(i).setText(text);
                onChange.accept(binding.path(), integerColor ? value : value / 255.0, integerColor);
            }
            suppressFieldChange = false;
        }

        private void drawPalette(DrawContext context) {
            for (int px = 0; px < paletteWidth; px++) {
                float hueValue = px / (float) Math.max(1, paletteWidth - 1);
                for (int py = 0; py < paletteHeight; py++) {
                    float saturationValue = 1.0f - (py / (float) Math.max(1, paletteHeight - 1));
                    int rgb = Color.HSBtoRGB(hueValue, saturationValue, brightness);
                    context.fill(paletteX + px, paletteY + py, paletteX + px + 1, paletteY + py + 1, 0xFF000000 | (rgb & 0xFFFFFF));
                }
            }
            context.drawBorder(paletteX, paletteY, paletteWidth, paletteHeight, new Color(uiColorBackgroundBorder, true).getRGB());
            int selectorX = paletteX + Math.round(hue * (paletteWidth - 1));
            int selectorY = paletteY + Math.round((1.0f - saturation) * (paletteHeight - 1));
            context.drawBorder(selectorX - 2, selectorY - 2, 4, 4, new Color(uiColorContentBaseTitleText, true).getRGB());
        }

        private void drawAlphaBar(DrawContext context, int previewColor) {
            int opaqueColor = (previewColor & 0x00FFFFFF) | 0xFF000000;
            for (int px = 0; px < alphaBarWidth; px++) {
                int checker = ((px / 4) % 2 == 0) ? new Color(uiColorConfigColorCheckerLight, true).getRGB() : new Color(uiColorConfigColorCheckerDark, true).getRGB();
                context.fill(alphaBarX + px, alphaBarY, alphaBarX + px + 1, alphaBarY + 6, checker);
            }
            for (int px = 0; px < alphaBarWidth; px++) {
                int alphaValue = Math.round((px / (float) Math.max(1, alphaBarWidth - 1)) * 255.0f);
                int color = (alphaValue << 24) | (opaqueColor & 0x00FFFFFF);
                context.fill(alphaBarX + px, alphaBarY, alphaBarX + px + 1, alphaBarY + 6, color);
            }
            context.drawBorder(alphaBarX, alphaBarY, alphaBarWidth, 6, new Color(uiColorBackgroundBorder, true).getRGB());
            int selectorX = alphaBarX + Math.round((alpha / 255.0f) * Math.max(0, alphaBarWidth - 1));
            context.fill(selectorX - 1, alphaBarY - 2, selectorX + 1, alphaBarY + 8, new Color(uiColorContentBaseTitleText, true).getRGB());
        }

        private void drawBrightnessBar(DrawContext context) {
            for (int px = 0; px < brightnessBarWidth; px++) {
                int value = Math.round((px / (float) Math.max(1, brightnessBarWidth - 1)) * 255.0f);
                int grayscale = 0xFF000000 | (value << 16) | (value << 8) | value;
                context.fill(brightnessBarX + px, brightnessBarY, brightnessBarX + px + 1, brightnessBarY + 6, grayscale);
            }
            context.drawBorder(brightnessBarX, brightnessBarY, brightnessBarWidth, 6, new Color(uiColorBackgroundBorder, true).getRGB());
            int selectorX = brightnessBarX + Math.round(brightness * Math.max(0, brightnessBarWidth - 1));
            context.fill(selectorX - 1, brightnessBarY - 2, selectorX + 1, brightnessBarY + 8, new Color(uiColorContentBaseTitleText, true).getRGB());
        }

        private boolean isInsidePalette(double mouseX, double mouseY) {
            if (!pickerOpen) {
                return false;
            }
            return mouseX >= paletteX && mouseX <= paletteX + paletteWidth && mouseY >= paletteY && mouseY <= paletteY + paletteHeight;
        }

        private boolean isInsideAlphaBar(double mouseX, double mouseY) {
            if (!pickerOpen) {
                return false;
            }
            return mouseX >= alphaBarX && mouseX <= alphaBarX + alphaBarWidth && mouseY >= alphaBarY - 1 && mouseY <= alphaBarY + 7;
        }

        private boolean isInsideBrightnessBar(double mouseX, double mouseY) {
            if (!pickerOpen) {
                return false;
            }
            return mouseX >= brightnessBarX && mouseX <= brightnessBarX + brightnessBarWidth && mouseY >= brightnessBarY - 1 && mouseY <= brightnessBarY + 7;
        }

        private boolean isInsideSwatch(double mouseX, double mouseY) {
            return mouseX >= swatchX && mouseX <= swatchX + swatchSize && mouseY >= swatchY && mouseY <= swatchY + swatchSize;
        }

        private void drawPickerPopover(DrawContext context, int previewColor) {
            popoverWidth = paletteWidth + 12;
            popoverHeight = supportsAlpha ? paletteHeight + 36 : paletteHeight + 22;
            int desiredX = requestedPopoverX != null ? requestedPopoverX + 8 : swatchX + swatchSize + 6;
            int desiredY = requestedPopoverY != null ? requestedPopoverY - 8 : swatchY - 2;
            int maxX = MinecraftClient.getInstance().currentScreen.width - popoverWidth - 8;
            int maxY = MinecraftClient.getInstance().currentScreen.height - popoverHeight - 8;
            popoverX = Math.max(8, Math.min(desiredX, maxX));
            popoverY = Math.max(8, Math.min(desiredY, maxY));
            if (requestedPopoverX == null && popoverX == maxX && desiredX > maxX) {
                popoverX = Math.max(8, swatchX - popoverWidth - 6);
            }
            paletteX = popoverX + 6;
            paletteY = popoverY + 6;
            alphaBarX = paletteX;
            alphaBarY = paletteY + paletteHeight + 6;
            brightnessBarX = paletteX;
            brightnessBarY = supportsAlpha ? alphaBarY + 12 : paletteY + paletteHeight + 6;
            context.fill(popoverX, popoverY, popoverX + popoverWidth, popoverY + popoverHeight, withAlpha(uiColorContentBase, 236));
            context.drawBorder(popoverX, popoverY, popoverWidth, popoverHeight, new Color(uiColorBackgroundBorder, true).getRGB());
            drawPalette(context);
            if (supportsAlpha) {
                drawAlphaBar(context, previewColor);
            }
            drawBrightnessBar(context);
        }

        private void applyPaletteSelection(double mouseX, double mouseY) {
            hue = (float) Math.max(0.0, Math.min(1.0, (mouseX - paletteX) / Math.max(1.0, paletteWidth - 1.0)));
            saturation = 1.0f - (float) Math.max(0.0, Math.min(1.0, (mouseY - paletteY) / Math.max(1.0, paletteHeight - 1.0)));
            publishPaletteColor();
        }

        private void applyAlphaSelection(double mouseX) {
            alpha = Math.max(0, Math.min(255, (int) Math.round(((mouseX - alphaBarX) / Math.max(1.0, alphaBarWidth - 1.0)) * 255.0)));
            publishPaletteColor();
        }

        private void applyBrightnessSelection(double mouseX) {
            brightness = (float) Math.max(0.0, Math.min(1.0, (mouseX - brightnessBarX) / Math.max(1.0, brightnessBarWidth - 1.0)));
            publishPaletteColor();
        }
    }

    private static class SingleColorEntry extends ConfigEntry {
        private final String label;
        private final TextFieldWidget valueField;
        private final JsonElementConsumer onChange;
        private final boolean numericMode;
        private final boolean supportsAlpha;
        private boolean pickerOpen;
        private boolean suppressFieldChange;
        private float hue;
        private float saturation;
        private float brightness;
        private int alpha;
        private int swatchX;
        private int swatchY;
        private int swatchSize;
        private int popoverX;
        private int popoverY;
        private int popoverWidth;
        private int popoverHeight;
        private int paletteX;
        private int paletteY;
        private int paletteWidth;
        private int paletteHeight;
        private int alphaBarX;
        private int alphaBarY;
        private int alphaBarWidth;
        private int brightnessBarX;
        private int brightnessBarY;
        private int brightnessBarWidth;
        private boolean draggingPalette;
        private boolean draggingAlpha;
        private boolean draggingBrightness;
        private Integer requestedPopoverX;
        private Integer requestedPopoverY;

        private SingleColorEntry(String label, JsonElement initialValue, JsonElementConsumer onChange) {
            this.label = label;
            this.onChange = onChange;
            this.numericMode = initialValue.getAsJsonPrimitive().isNumber();
            this.supportsAlpha = detectAlphaSupport(initialValue);
            this.valueField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 120, 18, Text.literal(label));
            this.valueField.setText(formatColorValue(initialValue));
            this.valueField.setChangedListener(raw -> {
                if (suppressFieldChange) {
                    return;
                }
                JsonElement parsed = parseColorValue(raw);
                if (parsed != null) {
                    onChange.accept(parsed);
                    syncPickerFromRaw(raw);
                }
            });
            syncPickerFromRaw(this.valueField.getText());
        }

        @Override
        public void tick() {
            valueField.tick();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && isInsideSwatch(mouseX, mouseY)) {
                requestedPopoverX = (int) Math.round(mouseX);
                requestedPopoverY = (int) Math.round(mouseY);
                pickerOpen = !pickerOpen;
                UiSoundHelper.playButtonClick();
                if (!pickerOpen) {
                    requestedPopoverX = null;
                    requestedPopoverY = null;
                }
                draggingPalette = false;
                draggingAlpha = false;
                draggingBrightness = false;
                return true;
            }
            if (button == 0 && isInsidePalette(mouseX, mouseY)) {
                draggingPalette = true;
                applyPaletteSelection(mouseX, mouseY);
                return true;
            }
            if (supportsAlpha && button == 0 && isInsideAlphaBar(mouseX, mouseY)) {
                draggingAlpha = true;
                applyAlphaSelection(mouseX);
                return true;
            }
            if (button == 0 && isInsideBrightnessBar(mouseX, mouseY)) {
                draggingBrightness = true;
                applyBrightnessSelection(mouseX);
                return true;
            }
            pickerOpen = false;
            requestedPopoverX = null;
            requestedPopoverY = null;
            return valueField.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean handleMouseDrag(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (button != 0) {
                return false;
            }
            if (draggingPalette) {
                applyPaletteSelection(mouseX, mouseY);
                return true;
            }
            if (draggingAlpha) {
                applyAlphaSelection(mouseX);
                return true;
            }
            if (draggingBrightness) {
                applyBrightnessSelection(mouseX);
                return true;
            }
            return false;
        }

        @Override
        public boolean handleMouseRelease(double mouseX, double mouseY, int button) {
            boolean wasDragging = draggingPalette || draggingAlpha || draggingBrightness;
            draggingPalette = false;
            draggingAlpha = false;
            draggingBrightness = false;
            return wasDragging;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return valueField.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return valueField.charTyped(chr, modifiers);
        }

        @Override
        public void setFocused(boolean focused) {
            valueField.setFocused(focused);
            if (!focused) {
                pickerOpen = false;
                requestedPopoverX = null;
                requestedPopoverY = null;
                draggingPalette = false;
                draggingAlpha = false;
                draggingBrightness = false;
            }
        }

        @Override
        public boolean isFocused() {
            return valueField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public String getValidationError() {
            return null;
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, numericMode ? "Packed / numeric color" : "Hex color value", x + 18, y);
            swatchSize = 18;
            int valueWidth = Math.min(120, Math.max(72, entryWidth / 4));
            int valueX = x + entryWidth - PINNED_ACTION_RESERVE - valueWidth - 10;
            int controlY = getControlRowY(y, entryHeight);
            swatchX = valueX - 24;
            swatchY = controlY + 1;
            paletteX = swatchX + swatchSize + 8;
            paletteY = y + 12;
            paletteWidth = 68;
            paletteHeight = 34;
            alphaBarX = paletteX;
            alphaBarY = paletteY + paletteHeight + 6;
            alphaBarWidth = paletteWidth;
            brightnessBarX = paletteX;
            brightnessBarY = paletteY + paletteHeight + 6;
            brightnessBarWidth = paletteWidth;
            int previewColor = parsePreviewColor(valueField.getText());
            context.fill(swatchX, swatchY, swatchX + swatchSize, swatchY + swatchSize, previewColor);
            context.drawBorder(swatchX, swatchY, swatchSize, swatchSize, new Color(uiColorBackgroundBorder, true).getRGB());
            valueField.setWidth(valueWidth);
            valueField.setX(valueX);
            valueField.setY(controlY);
            valueField.render(context, mouseX, mouseY, delta);
            drawDeleteButton(context, x, y, entryWidth);
        }

        @Override
        public void renderOverlay(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, float delta) {
            if (!pickerOpen) {
                return;
            }
            context.getMatrices().push();
            context.getMatrices().translate(0.0F, 0.0F, OVERLAY_Z);
            drawPickerPopover(context, parsePreviewColor(valueField.getText()));
            context.getMatrices().pop();
        }

        @Override
        public boolean hasModalOverlay() {
            return pickerOpen;
        }

        @Override
        public boolean openInlineEditor(double mouseX, double mouseY) {
            syncPickerFromRaw(valueField.getText());
            requestedPopoverX = Double.isNaN(mouseX) ? null : (int) Math.round(mouseX);
            requestedPopoverY = Double.isNaN(mouseY) ? null : (int) Math.round(mouseY);
            pickerOpen = true;
            draggingPalette = false;
            draggingAlpha = false;
            draggingBrightness = false;
            return true;
        }

        @Override
        public boolean handleOverlayClick(double mouseX, double mouseY, int button) {
            boolean inside = isInsideSwatch(mouseX, mouseY)
                    || isInsidePalette(mouseX, mouseY)
                    || isInsideAlphaBar(mouseX, mouseY)
                    || isInsideBrightnessBar(mouseX, mouseY)
                    || isMouseOverField(valueField, mouseX, mouseY)
                    || (mouseX >= popoverX && mouseX <= popoverX + popoverWidth && mouseY >= popoverY && mouseY <= popoverY + popoverHeight);
            if (inside) {
                return mouseClicked(mouseX, mouseY, button);
            }
            pickerOpen = false;
            requestedPopoverX = null;
            requestedPopoverY = null;
            draggingPalette = false;
            draggingAlpha = false;
            draggingBrightness = false;
            return true;
        }

        private String formatColorValue(JsonElement value) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                long numeric = primitive.getAsLong() & 0xFFFFFFFFL;
                return String.format(Locale.ROOT, "0x%08X", numeric);
            }
            return primitive.getAsString();
        }

        private JsonElement parseColorValue(String raw) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                long value = parseColorLong(trimmed);
                if (numericMode) {
                    return new JsonPrimitive(value);
                }
                if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                    return new JsonPrimitive(String.format(Locale.ROOT, "0x%08X", value));
                }
                if (!supportsAlpha && trimmed.replace("#", "").length() > 6) {
                    value = value & 0xFFFFFFL;
                }
                return new JsonPrimitive(trimmed.startsWith("#") ? trimmed.toUpperCase(Locale.ROOT) : ("#" + trimmed.toUpperCase(Locale.ROOT)));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private int parsePreviewColor(String raw) {
            try {
                long value = parseColorLong(raw);
                if ((value & 0xFF000000L) == 0 && raw.replace("#", "").replace("0x", "").replace("0X", "").length() <= 6) {
                    value |= 0xFF000000L;
                }
                return (int) value;
            } catch (NumberFormatException ignored) {
                return withAlpha(uiColorHeader, 120);
            }
        }

        private long parseColorLong(String raw) {
            String normalized = raw.trim();
            if (normalized.startsWith("#")) {
                normalized = normalized.substring(1);
            } else if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
                normalized = normalized.substring(2);
            }
            return Long.parseLong(normalized, 16);
        }

        private void syncPickerFromRaw(String raw) {
            int previewColor = parsePreviewColor(raw);
            int red = (previewColor >> 16) & 0xFF;
            int green = (previewColor >> 8) & 0xFF;
            int blue = previewColor & 0xFF;
            alpha = supportsAlpha ? ((previewColor >>> 24) & 0xFF) : 255;
            float[] hsb = Color.RGBtoHSB(red, green, blue, null);
            hue = hsb[0];
            saturation = hsb[1];
            brightness = hsb[2];
        }

        private void publishPickerColor() {
            int rgb = Color.HSBtoRGB(hue, saturation, brightness);
            int red = (rgb >> 16) & 0xFF;
            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;
            int packed = ((supportsAlpha ? alpha : 255) << 24) | (red << 16) | (green << 8) | blue;
            suppressFieldChange = true;
            if (numericMode) {
                String text = supportsAlpha
                        ? String.format(Locale.ROOT, "0x%08X", packed & 0xFFFFFFFFL)
                        : String.format(Locale.ROOT, "0x%06X", packed & 0xFFFFFFL);
                valueField.setText(text);
                onChange.accept(new JsonPrimitive(Long.parseLong(text.substring(2), 16)));
            } else {
                String text = supportsAlpha && alpha != 255
                        ? String.format(Locale.ROOT, "#%02X%02X%02X%02X", red, green, blue, alpha)
                        : String.format(Locale.ROOT, "#%02X%02X%02X", red, green, blue);
                valueField.setText(text);
                onChange.accept(new JsonPrimitive(text));
            }
            suppressFieldChange = false;
        }

        private boolean isInsideSwatch(double mouseX, double mouseY) {
            return mouseX >= swatchX && mouseX <= swatchX + swatchSize && mouseY >= swatchY && mouseY <= swatchY + swatchSize;
        }

        private boolean isInsidePalette(double mouseX, double mouseY) {
            return pickerOpen && mouseX >= paletteX && mouseX <= paletteX + paletteWidth && mouseY >= paletteY && mouseY <= paletteY + paletteHeight;
        }

        private boolean isInsideAlphaBar(double mouseX, double mouseY) {
            return pickerOpen && mouseX >= alphaBarX && mouseX <= alphaBarX + alphaBarWidth && mouseY >= alphaBarY - 1 && mouseY <= alphaBarY + 7;
        }

        private boolean isInsideBrightnessBar(double mouseX, double mouseY) {
            return pickerOpen && mouseX >= brightnessBarX && mouseX <= brightnessBarX + brightnessBarWidth && mouseY >= brightnessBarY - 1 && mouseY <= brightnessBarY + 7;
        }

        private void applyPaletteSelection(double mouseX, double mouseY) {
            hue = (float) Math.max(0.0, Math.min(1.0, (mouseX - paletteX) / Math.max(1.0, paletteWidth - 1.0)));
            saturation = 1.0f - (float) Math.max(0.0, Math.min(1.0, (mouseY - paletteY) / Math.max(1.0, paletteHeight - 1.0)));
            publishPickerColor();
        }

        private void applyAlphaSelection(double mouseX) {
            alpha = Math.max(0, Math.min(255, (int) Math.round(((mouseX - alphaBarX) / Math.max(1.0, alphaBarWidth - 1.0)) * 255.0)));
            publishPickerColor();
        }

        private void applyBrightnessSelection(double mouseX) {
            brightness = (float) Math.max(0.0, Math.min(1.0, (mouseX - brightnessBarX) / Math.max(1.0, brightnessBarWidth - 1.0)));
            publishPickerColor();
        }

        private void drawPickerPopover(DrawContext context, int previewColor) {
            popoverWidth = paletteWidth + 12;
            popoverHeight = supportsAlpha ? paletteHeight + 36 : paletteHeight + 22;
            int desiredX = requestedPopoverX != null ? requestedPopoverX + 8 : swatchX + swatchSize + 6;
            int desiredY = requestedPopoverY != null ? requestedPopoverY - 8 : swatchY - 2;
            int maxX = MinecraftClient.getInstance().currentScreen.width - popoverWidth - 8;
            int maxY = MinecraftClient.getInstance().currentScreen.height - popoverHeight - 8;
            popoverX = Math.max(8, Math.min(desiredX, maxX));
            popoverY = Math.max(8, Math.min(desiredY, maxY));
            if (requestedPopoverX == null && popoverX == maxX && desiredX > maxX) {
                popoverX = Math.max(8, swatchX - popoverWidth - 6);
            }
            paletteX = popoverX + 6;
            paletteY = popoverY + 6;
            alphaBarX = paletteX;
            alphaBarY = paletteY + paletteHeight + 6;
            brightnessBarX = paletteX;
            brightnessBarY = supportsAlpha ? alphaBarY + 12 : paletteY + paletteHeight + 6;
            context.fill(popoverX, popoverY, popoverX + popoverWidth, popoverY + popoverHeight, withAlpha(uiColorContentBase, 236));
            context.drawBorder(popoverX, popoverY, popoverWidth, popoverHeight, new Color(uiColorBackgroundBorder, true).getRGB());
            for (int px = 0; px < paletteWidth; px++) {
                float hueValue = px / (float) Math.max(1, paletteWidth - 1);
                for (int py = 0; py < paletteHeight; py++) {
                    float saturationValue = 1.0f - (py / (float) Math.max(1, paletteHeight - 1));
                    int rgb = Color.HSBtoRGB(hueValue, saturationValue, brightness);
                    context.fill(paletteX + px, paletteY + py, paletteX + px + 1, paletteY + py + 1, 0xFF000000 | (rgb & 0xFFFFFF));
                }
            }
            context.drawBorder(paletteX, paletteY, paletteWidth, paletteHeight, new Color(uiColorBackgroundBorder, true).getRGB());
            int selectorX = paletteX + Math.round(hue * (paletteWidth - 1));
            int selectorY = paletteY + Math.round((1.0f - saturation) * (paletteHeight - 1));
            context.drawBorder(selectorX - 2, selectorY - 2, 4, 4, new Color(uiColorContentBaseTitleText, true).getRGB());
            if (supportsAlpha) {
                int opaqueColor = (previewColor & 0x00FFFFFF) | 0xFF000000;
                for (int px = 0; px < alphaBarWidth; px++) {
                    int checker = ((px / 4) % 2 == 0) ? new Color(uiColorConfigColorCheckerLight, true).getRGB() : new Color(uiColorConfigColorCheckerDark, true).getRGB();
                    context.fill(alphaBarX + px, alphaBarY, alphaBarX + px + 1, alphaBarY + 6, checker);
                }
                for (int px = 0; px < alphaBarWidth; px++) {
                    int alphaValue = Math.round((px / (float) Math.max(1, alphaBarWidth - 1)) * 255.0f);
                    int color = (alphaValue << 24) | (opaqueColor & 0x00FFFFFF);
                    context.fill(alphaBarX + px, alphaBarY, alphaBarX + px + 1, alphaBarY + 6, color);
                }
                context.drawBorder(alphaBarX, alphaBarY, alphaBarWidth, 6, new Color(uiColorBackgroundBorder, true).getRGB());
                int selectorAlphaX = alphaBarX + Math.round((alpha / 255.0f) * Math.max(0, alphaBarWidth - 1));
                context.fill(selectorAlphaX - 1, alphaBarY - 2, selectorAlphaX + 1, alphaBarY + 8, new Color(uiColorContentBaseTitleText, true).getRGB());
            }
            for (int px = 0; px < brightnessBarWidth; px++) {
                int value = Math.round((px / (float) Math.max(1, brightnessBarWidth - 1)) * 255.0f);
                int grayscale = 0xFF000000 | (value << 16) | (value << 8) | value;
                context.fill(brightnessBarX + px, brightnessBarY, brightnessBarX + px + 1, brightnessBarY + 6, grayscale);
            }
            context.drawBorder(brightnessBarX, brightnessBarY, brightnessBarWidth, 6, new Color(uiColorBackgroundBorder, true).getRGB());
            int selectorBrightnessX = brightnessBarX + Math.round(brightness * Math.max(0, brightnessBarWidth - 1));
            context.fill(selectorBrightnessX - 1, brightnessBarY - 2, selectorBrightnessX + 1, brightnessBarY + 8, new Color(uiColorContentBaseTitleText, true).getRGB());
        }

        private boolean detectAlphaSupport(JsonElement initialValue) {
            JsonPrimitive primitive = initialValue.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                long numeric = primitive.getAsLong() & 0xFFFFFFFFL;
                return numeric > 0xFFFFFFL;
            }
            String raw = primitive.getAsString().trim();
            String normalized = raw.startsWith("#") ? raw.substring(1) : (raw.startsWith("0x") || raw.startsWith("0X") ? raw.substring(2) : raw);
            return normalized.length() > 6;
        }
    }

    private static class ColorAdjustmentEntry extends ConfigEntry {
        private static final int COLOR_ADJUSTMENT_SWITCH_WIDTH = 44;
        private static final int COLOR_ADJUSTMENT_SWATCH_SIZE = 18;
        private final String label;
        private final BooleanBinding visibleBinding;
        private final List<ComponentBinding> numericBindings;
        private final List<TextFieldWidget> numericFields = new ArrayList<>();
        private final BooleanPathConsumer booleanChange;
        private final NumericUpdateConsumer numericChange;
        private final boolean wholeNumber;
        private int swatchX;
        private int swatchY;
        private int swatchSize;
        private int toggleX;
        private int toggleY;
        private int toggleWidth;
        private boolean pickerOpen;
        private int popoverX;
        private int popoverY;
        private int popoverWidth;
        private int popoverHeight;
        private final List<int[]> sliderBounds = new ArrayList<>();
        private int draggingSliderIndex = -1;
        private Integer requestedPopoverX;
        private Integer requestedPopoverY;

        private ColorAdjustmentEntry(String label, BooleanBinding visibleBinding, List<ComponentBinding> numericBindings, BooleanPathConsumer booleanChange, NumericUpdateConsumer numericChange) {
            this.label = label;
            this.visibleBinding = visibleBinding;
            this.numericBindings = numericBindings;
            this.booleanChange = booleanChange;
            this.numericChange = numericChange;
            this.wholeNumber = numericBindings.stream().allMatch(binding -> Math.rint(binding.value()) == binding.value());
            for (ComponentBinding binding : numericBindings) {
                TextFieldWidget field = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 52, 18, Text.literal(binding.label()));
                field.setText(wholeNumber ? String.valueOf((int) Math.round(binding.value())) : String.format(Locale.ROOT, "%.2f", binding.value()));
                field.setChangedListener(raw -> {
                    try {
                        numericChange.accept(binding.path(), Double.parseDouble(raw), binding.label().equals("ColorTemperature") || wholeNumber);
                    } catch (NumberFormatException ignored) {
                    }
                });
                numericFields.add(field);
            }
        }

        @Override
        public void tick() {
            numericFields.forEach(TextFieldWidget::tick);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && isInsideSwatch(mouseX, mouseY)) {
                requestedPopoverX = (int) Math.round(mouseX);
                requestedPopoverY = (int) Math.round(mouseY);
                pickerOpen = !pickerOpen;
                UiSoundHelper.playButtonClick();
                if (!pickerOpen) {
                    requestedPopoverX = null;
                    requestedPopoverY = null;
                }
                draggingSliderIndex = -1;
                return true;
            }
            if (visibleBinding != null && mouseX >= toggleX && mouseX <= toggleX + toggleWidth && mouseY >= toggleY && mouseY <= toggleY + 18) {
                visibleBinding.setValue(!visibleBinding.value());
                booleanChange.accept(visibleBinding.path(), visibleBinding.value());
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (button == 0 && pickerOpen) {
                for (int i = 0; i < sliderBounds.size(); i++) {
                    int[] bounds = sliderBounds.get(i);
                    if (mouseX >= bounds[0] && mouseX <= bounds[0] + bounds[2] && mouseY >= bounds[1] && mouseY <= bounds[1] + bounds[3]) {
                        draggingSliderIndex = i;
                        applySliderDrag(i, mouseX);
                        return true;
                    }
                }
            }
            boolean focusedField = focusClickedField(mouseX, mouseY, button, numericFields.toArray(new TextFieldWidget[0]));
            if (!focusedField && !pickerOpen) {
                requestedPopoverX = null;
                requestedPopoverY = null;
            }
            return focusedField;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (visibleBinding != null && (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
                visibleBinding.setValue(!visibleBinding.value());
                booleanChange.accept(visibleBinding.path(), visibleBinding.value());
                UiSoundHelper.playButtonClick();
                return true;
            }
            for (TextFieldWidget field : numericFields) {
                if (field.keyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            for (TextFieldWidget field : numericFields) {
                if (field.charTyped(chr, modifiers)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            for (int i = 0; i < numericFields.size(); i++) {
                TextFieldWidget field = numericFields.get(i);
                if (!isMouseOverField(field, mouseX, mouseY)) {
                    int[] bounds = i < sliderBounds.size() ? sliderBounds.get(i) : null;
                    if (bounds == null || mouseX < bounds[0] || mouseX > bounds[0] + bounds[2] || mouseY < bounds[1] || mouseY > bounds[1] + bounds[3]) {
                        continue;
                    }
                }
                double step = getAdjustmentStep(i);
                boolean integerLike = isIntegerLikeAdjustment(i);
                boolean changed = nudgeNumericTextField(field, amount > 0 ? step : -step, integerLike);
                if (changed) {
                    UiSoundHelper.playDialClick();
                }
                return changed;
            }
            return false;
        }

        @Override
        public boolean handleMouseDrag(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (button != 0 || draggingSliderIndex < 0) {
                return false;
            }
            applySliderDrag(draggingSliderIndex, mouseX);
            return true;
        }

        @Override
        public boolean handleMouseRelease(double mouseX, double mouseY, int button) {
            boolean wasDragging = draggingSliderIndex >= 0;
            draggingSliderIndex = -1;
            return wasDragging;
        }

        @Override
        public void setFocused(boolean focused) {
            if (!focused) {
                numericFields.forEach(field -> field.setFocused(false));
                draggingSliderIndex = -1;
                pickerOpen = false;
                requestedPopoverX = null;
                requestedPopoverY = null;
            }
        }

        @Override
        public boolean isFocused() {
            return numericFields.stream().anyMatch(TextFieldWidget::isFocused);
        }

        @Override
        public int getPreferredHeight(int defaultHeight) {
            return defaultHeight;
        }

        @Override
        public List<String> getDeleteTargets() {
            List<String> targets = new ArrayList<>();
            if (visibleBinding != null) {
                targets.add(visibleBinding.path());
            }
            numericBindings.forEach(binding -> targets.add(binding.path()));
            return targets;
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, null, x + 18, y);
            int fieldCount = numericFields.size();
            boolean showCaptions = showFieldCaptions();
            int switchWidth = visibleBinding != null ? COLOR_ADJUSTMENT_SWITCH_WIDTH : 0;
            int switchGap = visibleBinding != null ? 8 : 0;
            int fieldGap = 4;
            int fieldWidth = 34;
            int swatchGap = 6;
            int rightAnchor = x + entryWidth - PINNED_ACTION_RESERVE - 10;
            int switchLeft = visibleBinding != null ? rightAnchor - switchWidth : rightAnchor;
            int previewWidth = COLOR_ADJUSTMENT_SWATCH_SIZE;
            int previewHeight = COLOR_ADJUSTMENT_SWATCH_SIZE;
            int fieldAreaRight = switchLeft - switchGap;
            int fieldBlockWidth = fieldCount * fieldWidth + Math.max(0, fieldCount - 1) * fieldGap;
            int previewX = fieldAreaRight - fieldBlockWidth - swatchGap - previewWidth;
            int controlY = getControlRowY(y, entryHeight);
            int previewY = controlY + 1;
            swatchX = previewX;
            swatchY = previewY;
            swatchSize = previewWidth;
            int previewColor = derivePreviewColor();
            context.fill(previewX, previewY, previewX + previewWidth, previewY + previewHeight, previewColor);
            context.drawBorder(previewX, previewY, previewWidth, previewHeight, new Color(uiColorBackgroundBorder, true).getRGB());
            if (visibleBinding != null) {
                toggleWidth = COLOR_ADJUSTMENT_SWITCH_WIDTH;
                toggleY = getControlRowY(y, entryHeight);
                toggleX = switchLeft;
                context.fill(toggleX, toggleY, toggleX + toggleWidth, toggleY + 18, visibleBinding.value() ? withAlpha(uiColorHeaderStripe, 220) : withAlpha(uiColorHeader, 120));
                context.drawBorder(toggleX, toggleY, toggleWidth, 18, new Color(uiColorBackgroundBorder, true).getRGB());
                int knobX = visibleBinding.value() ? toggleX + toggleWidth - 16 : toggleX + 2;
                context.fill(knobX, toggleY + 2, knobX + 12, toggleY + 16, new Color(uiColorContentBaseTitleText, true).getRGB());
            }

            for (int i = 0; i < fieldCount; i++) {
                TextFieldWidget field = numericFields.get(i);
                ComponentBinding binding = numericBindings.get(i);
                int rowStartX = fieldAreaRight - fieldBlockWidth;
                int currentX = rowStartX + i * (fieldWidth + fieldGap);
                if (showCaptions) {
                    context.drawText(MinecraftClient.getInstance().textRenderer, shortAdjustmentLabel(binding.label()), currentX, getFieldCaptionY(controlY), new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                }
                field.setWidth(fieldWidth);
                field.setX(currentX);
                field.setY(controlY);
                field.render(context, mouseX, mouseY, delta);
            }
            drawDeleteButton(context, x, y, entryWidth);
        }

        @Override
        public void renderOverlay(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, float delta) {
            if (!pickerOpen) {
                return;
            }
            context.getMatrices().push();
            context.getMatrices().translate(0.0F, 0.0F, OVERLAY_Z);
            drawAdjustmentPopover(context, mouseX, mouseY, delta);
            context.getMatrices().pop();
        }

        @Override
        public boolean hasModalOverlay() {
            return pickerOpen;
        }

        @Override
        public boolean openInlineEditor(double mouseX, double mouseY) {
            requestedPopoverX = Double.isNaN(mouseX) ? null : (int) Math.round(mouseX);
            requestedPopoverY = Double.isNaN(mouseY) ? null : (int) Math.round(mouseY);
            pickerOpen = true;
            draggingSliderIndex = -1;
            return true;
        }

        @Override
        public boolean handleOverlayClick(double mouseX, double mouseY, int button) {
            boolean inside = isInsideSwatch(mouseX, mouseY)
                    || (mouseX >= popoverX && mouseX <= popoverX + popoverWidth && mouseY >= popoverY && mouseY <= popoverY + popoverHeight);
            if (inside) {
                return mouseClicked(mouseX, mouseY, button);
            }
            pickerOpen = false;
            requestedPopoverX = null;
            requestedPopoverY = null;
            draggingSliderIndex = -1;
            numericFields.forEach(field -> field.setFocused(false));
            return true;
        }

        private boolean isIntegerLikeAdjustment(int index) {
            return wholeNumber || "ColorTemperature".equals(numericBindings.get(index).label());
        }

        private double getAdjustmentStep(int index) {
            return isIntegerLikeAdjustment(index) ? 1.0 : 0.1;
        }

        private void applySliderDrag(int index, double mouseX) {
            if (index < 0 || index >= sliderBounds.size()) {
                return;
            }
            int[] bounds = sliderBounds.get(index);
            double progress = Math.max(0.0, Math.min(1.0, (mouseX - bounds[0]) / Math.max(1.0, bounds[2])));
            double min = getAdjustmentMin(index);
            double max = getAdjustmentMax(index);
            double value = min + (max - min) * progress;
            if (isIntegerLikeAdjustment(index)) {
                value = Math.round(value);
            } else {
                value = Math.round(value * 10.0) / 10.0;
            }
            TextFieldWidget field = numericFields.get(index);
            field.setText(isIntegerLikeAdjustment(index) ? String.valueOf((int) Math.round(value)) : String.format(Locale.ROOT, "%.1f", value));
        }

        private double getSliderProgress(int index) {
            double value = getNumericValue(numericBindings.get(index).label(), numericBindings.get(index).value());
            double min = getAdjustmentMin(index);
            double max = getAdjustmentMax(index);
            return Math.max(0.0, Math.min(1.0, (value - min) / Math.max(0.0001, max - min)));
        }

        private double getAdjustmentMin(int index) {
            return switch (numericBindings.get(index).label()) {
                case "Opacity", "Brightness" -> 0.0;
                case "Hue" -> 0.0;
                case "Saturation" -> 0.0;
                case "Contrast" -> -100.0;
                case "Gamma" -> 0.0;
                case "ColorTemperature" -> -100.0;
                default -> Math.min(0.0, numericBindings.get(index).value());
            };
        }

        private double getAdjustmentMax(int index) {
            return switch (numericBindings.get(index).label()) {
                case "Opacity", "Brightness" -> 100.0;
                case "Hue" -> 360.0;
                case "Saturation" -> 200.0;
                case "Contrast" -> 100.0;
                case "Gamma" -> 100.0;
                case "ColorTemperature" -> 100.0;
                default -> Math.max(100.0, numericBindings.get(index).value());
            };
        }

        private int derivePreviewColor() {
            double hue = getNumericValue("Hue", 210.0);
            double saturation = getNumericValue("Saturation", 70.0);
            double brightness = getNumericValue("Brightness", 85.0);
            double opacity = getNumericValue("Opacity", 100.0);
            float hueUnit = hue > 1.0 ? (float) ((hue % 360.0) / 360.0) : (float) hue;
            float saturationUnit = saturation > 1.0 ? (float) Math.max(0.0, Math.min(1.0, saturation / 100.0)) : (float) saturation;
            float brightnessUnit = brightness > 1.0 ? (float) Math.max(0.0, Math.min(1.0, brightness / 100.0)) : (float) brightness;
            int alpha = (int) Math.round((opacity > 1.0 ? opacity / 100.0 : opacity) * 255.0);
            int rgb = Color.HSBtoRGB(hueUnit, saturationUnit, brightnessUnit);
            return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00FFFFFF);
        }

        private double getNumericValue(String label, double fallback) {
            for (int i = 0; i < numericBindings.size(); i++) {
                if (numericBindings.get(i).label().equals(label)) {
                    try {
                        return Double.parseDouble(numericFields.get(i).getText());
                    } catch (NumberFormatException ignored) {
                        return numericBindings.get(i).value();
                    }
                }
            }
            return fallback;
        }

        private String shortAdjustmentLabel(String key) {
            return switch (key) {
                case "Opacity" -> "OP";
                case "Hue" -> "HU";
                case "Saturation" -> "SA";
                case "Brightness" -> "BR";
                case "ColorTemperature" -> "CT";
                case "Contrast" -> "CO";
                case "Gamma" -> "GA";
                default -> key;
            };
        }

        private boolean isInsideSwatch(double mouseX, double mouseY) {
            return mouseX >= swatchX && mouseX <= swatchX + swatchSize && mouseY >= swatchY && mouseY <= swatchY + swatchSize;
        }

        private void drawAdjustmentPopover(DrawContext context, int mouseX, int mouseY, float delta) {
            popoverWidth = 136;
            popoverHeight = 10 + numericBindings.size() * 16;
            int desiredX = requestedPopoverX != null ? requestedPopoverX + 8 : swatchX + swatchSize + 6;
            int desiredY = requestedPopoverY != null ? requestedPopoverY - 8 : swatchY - 2;
            int maxX = MinecraftClient.getInstance().currentScreen.width - popoverWidth - 8;
            int maxY = MinecraftClient.getInstance().currentScreen.height - popoverHeight - 8;
            popoverX = Math.max(8, Math.min(desiredX, maxX));
            popoverY = Math.max(8, Math.min(desiredY, maxY));
            if (requestedPopoverX == null && popoverX == maxX && desiredX > maxX) {
                popoverX = Math.max(8, swatchX - popoverWidth - 6);
            }

            context.fill(popoverX, popoverY, popoverX + popoverWidth, popoverY + popoverHeight, withAlpha(uiColorContentBase, 236));
            context.drawBorder(popoverX, popoverY, popoverWidth, popoverHeight, new Color(uiColorBackgroundBorder, true).getRGB());
            sliderBounds.clear();
            int sliderStartY = popoverY + 4;
            int sliderX = popoverX + 8;
            int sliderWidth = popoverWidth - 16;
            for (int i = 0; i < numericFields.size(); i++) {
                ComponentBinding binding = numericBindings.get(i);
                int rowY = sliderStartY + i * 16;
                int trackY = rowY + 4;
                sliderBounds.add(new int[]{sliderX, trackY - 4, sliderWidth, 10});
                drawAdjustmentTrack(context, binding.label(), sliderX, trackY, sliderWidth, 4);
                int knobX = sliderX + (int) Math.round(getSliderProgress(i) * sliderWidth);
                context.drawBorder(sliderX, trackY, sliderWidth, 4, new Color(uiColorBackgroundBorder, true).getRGB());
                context.fill(knobX - 2, trackY - 3, knobX + 2, trackY + 7, new Color(uiColorContentBaseTitleText, true).getRGB());
            }
        }

        private void drawAdjustmentTrack(DrawContext context, String key, int x, int y, int width, int height) {
            switch (key) {
                case "Opacity" -> {
                    for (int px = 0; px < width; px += 4) {
                        int checker = ((px / 4) % 2 == 0) ? new Color(uiColorConfigColorCheckerLight, true).getRGB() : new Color(uiColorConfigColorCheckerDark, true).getRGB();
                        context.fill(x + px, y, Math.min(x + px + 4, x + width), y + height, checker);
                    }
                    int preview = derivePreviewColor() | 0xFF000000;
                    for (int px = 0; px < width; px++) {
                        int alphaValue = Math.round((px / (float) Math.max(1, width - 1)) * 255.0f);
                        int color = (alphaValue << 24) | (preview & 0x00FFFFFF);
                        context.fill(x + px, y, x + px + 1, y + height, color);
                    }
                }
                case "Hue" -> {
                    for (int px = 0; px < width; px++) {
                        int rgb = Color.HSBtoRGB(px / (float) Math.max(1, width - 1), 1.0f, 1.0f);
                        context.fill(x + px, y, x + px + 1, y + height, 0xFF000000 | (rgb & 0xFFFFFF));
                    }
                }
                case "Saturation" -> {
                    double hue = getNumericValue("Hue", 210.0);
                    float hueUnit = hue > 1.0 ? (float) ((hue % 360.0) / 360.0) : (float) hue;
                    for (int px = 0; px < width; px++) {
                        float sat = px / (float) Math.max(1, width - 1);
                        int rgb = Color.HSBtoRGB(hueUnit, sat, 0.95f);
                        context.fill(x + px, y, x + px + 1, y + height, 0xFF000000 | (rgb & 0xFFFFFF));
                    }
                }
                case "Contrast" -> {
                    for (int px = 0; px < width; px++) {
                        double t = px / (double) Math.max(1, width - 1);
                        int shade = t < 0.5 ? (int) Math.round(t * 2.0 * 110.0) : 145 + (int) Math.round((t - 0.5) * 2.0 * 110.0);
                        int color = 0xFF000000 | (shade << 16) | (shade << 8) | shade;
                        context.fill(x + px, y, x + px + 1, y + height, color);
                    }
                }
                case "Gamma" -> {
                    context.fill(x, y, x + width, y + height, new Color(uiColorConfigGammaBackground, true).getRGB());
                    for (int px = 0; px < width; px++) {
                        double t = px / (double) Math.max(1, width - 1);
                        int lineHeight = 1 + (int) Math.round(Math.pow(t, 0.55) * (height - 1));
                        context.fill(x + px, y + height - lineHeight, x + px + 1, y + height, new Color(uiColorConfigPickerText, true).getRGB());
                    }
                }
                case "Brightness" -> {
                    for (int px = 0; px < width; px++) {
                        int shade = 20 + (int) Math.round((px / (double) Math.max(1, width - 1)) * 235.0);
                        int color = 0xFF000000 | (shade << 16) | (shade << 8) | shade;
                        context.fill(x + px, y, x + px + 1, y + height, color);
                    }
                }
                case "ColorTemperature" -> {
                    for (int px = 0; px < width; px++) {
                        float t = px / (float) Math.max(1, width - 1);
                        int cold = new Color(uiColorConfigTemperatureCold, true).getRGB();
                        int warm = new Color(uiColorConfigTemperatureWarm, true).getRGB();
                        int color = lerpColor(cold, warm, t);
                        context.fill(x + px, y, x + px + 1, y + height, color);
                    }
                }
                default -> context.fill(x, y, x + width, y + height, withAlpha(uiColorHeaderStripe, 190));
            }
        }

        private int lerpColor(int left, int right, float t) {
            t = Math.max(0.0f, Math.min(1.0f, t));
            int a = (int) (((left >>> 24) & 0xFF) + (((right >>> 24) & 0xFF) - ((left >>> 24) & 0xFF)) * t);
            int r = (int) (((left >>> 16) & 0xFF) + (((right >>> 16) & 0xFF) - ((left >>> 16) & 0xFF)) * t);
            int g = (int) (((left >>> 8) & 0xFF) + (((right >>> 8) & 0xFF) - ((left >>> 8) & 0xFF)) * t);
            int b = (int) ((left & 0xFF) + ((right & 0xFF) - (left & 0xFF)) * t);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private static class ResolutionEntry extends ConfigEntry {
        private final String label;
        private final ComponentBinding widthBinding;
        private final ComponentBinding heightBinding;
        private final TextFieldWidget widthField;
        private final TextFieldWidget heightField;
        private final NumericUpdateConsumer onChange;
        private int openButtonX;
        private int buttonY;
        private boolean editorOpen;
        private int popoverX;
        private int popoverY;
        private int popoverWidth;
        private int popoverHeight;
        private Integer requestedPopoverX;
        private Integer requestedPopoverY;
        private final List<int[]> presetBounds = new ArrayList<>();
        private final List<int[]> ratioBounds = new ArrayList<>();
        private int presetButtonX;
        private int aspectButtonX;
        private int popupButtonY;
        private boolean presetPopupOpen;
        private boolean aspectPopupOpen;
        private int presetPopupX;
        private int presetPopupY;
        private int presetPopupWidth;
        private int presetPopupHeight;
        private int aspectPopupX;
        private int aspectPopupY;
        private int aspectPopupWidth;
        private int aspectPopupHeight;
        private int previewX;
        private int previewY;
        private int previewWidth;
        private int previewHeight;
        private int handleX;
        private int handleY;
        private int handleSize;
        private boolean draggingPreviewHandle;
        private float dragAspectRatio;
        private int[] activeDragPreset;

        private ResolutionEntry(String label, List<ComponentBinding> bindings, NumericUpdateConsumer onChange) {
            this.label = label;
            this.widthBinding = bindings.get(0);
            this.heightBinding = bindings.get(1);
            this.onChange = onChange;
            this.widthField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 62, 18, Text.literal("Width"));
            this.heightField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 62, 18, Text.literal("Height"));
            this.widthField.setText(String.valueOf((int) Math.round(widthBinding.value())));
            this.heightField.setText(String.valueOf((int) Math.round(heightBinding.value())));
            this.widthField.setChangedListener(raw -> publish(true, raw));
            this.heightField.setChangedListener(raw -> publish(false, raw));
        }

        @Override
        public void tick() {
            widthField.tick();
            heightField.tick();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isControlButtonHit(mouseX, mouseY, openButtonX, buttonY, 44)) {
                requestedPopoverX = (int) Math.round(mouseX);
                requestedPopoverY = (int) Math.round(mouseY);
                editorOpen = !editorOpen;
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (editorOpen) {
                if (button == 0 && isInsidePreviewHandle(mouseX, mouseY)) {
                    draggingPreviewHandle = true;
                    dragAspectRatio = Math.max(0.0001f, parseDimension(widthField, (int) Math.round(widthBinding.value())) / (float) Math.max(1, parseDimension(heightField, (int) Math.round(heightBinding.value()))));
                    activeDragPreset = null;
                    UiSoundHelper.playButtonClick();
                    return true;
                }
                if (isControlButtonHit(mouseX, mouseY, presetButtonX, popupButtonY, 62)) {
                    presetPopupOpen = !presetPopupOpen;
                    aspectPopupOpen = false;
                    UiSoundHelper.playButtonClick();
                    return true;
                }
                if (isControlButtonHit(mouseX, mouseY, aspectButtonX, popupButtonY, 62)) {
                    aspectPopupOpen = !aspectPopupOpen;
                    presetPopupOpen = false;
                    UiSoundHelper.playButtonClick();
                    return true;
                }
                if (presetPopupOpen) {
                    for (int i = 0; i < presetBounds.size() && i < RESOLUTION_PRESET_LABELS.size(); i++) {
                        int[] bounds = presetBounds.get(i);
                        if (mouseX >= bounds[0] && mouseX <= bounds[0] + bounds[2] && mouseY >= bounds[1] && mouseY <= bounds[1] + bounds[3]) {
                            applyPreset(RESOLUTION_PRESET_LABELS.get(i));
                            presetPopupOpen = false;
                            UiSoundHelper.playButtonClick();
                            return true;
                        }
                    }
                }
                String[] ratios = {"16:9", "16:10", "21:9", "4:3", "1:1"};
                if (aspectPopupOpen) {
                    for (int i = 0; i < ratioBounds.size() && i < ratios.length; i++) {
                        int[] bounds = ratioBounds.get(i);
                        if (mouseX >= bounds[0] && mouseX <= bounds[0] + bounds[2] && mouseY >= bounds[1] && mouseY <= bounds[1] + bounds[3]) {
                            applyAspectRatio(ratios[i]);
                            aspectPopupOpen = false;
                            UiSoundHelper.playButtonClick();
                            return true;
                        }
                    }
                }
            }
            return focusClickedField(mouseX, mouseY, button, widthField, heightField);
        }

        @Override
        public boolean handleMouseDrag(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (!editorOpen || button != 0 || !draggingPreviewHandle) {
                return false;
            }
            resizePreviewFromHandle(mouseX, mouseY);
            return true;
        }

        @Override
        public boolean handleMouseRelease(double mouseX, double mouseY, int button) {
            if (!draggingPreviewHandle) {
                return false;
            }
            draggingPreviewHandle = false;
            snapDraggedPreviewToPreset();
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return widthField.keyPressed(keyCode, scanCode, modifiers) || heightField.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return widthField.charTyped(chr, modifiers) || heightField.charTyped(chr, modifiers);
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            if (isMouseOverField(widthField, mouseX, mouseY)) {
                boolean changed = nudgeNumericTextField(widthField, amount > 0 ? 1.0 : -1.0, true);
                if (changed) {
                    UiSoundHelper.playDialClick();
                }
                return changed;
            }
            if (isMouseOverField(heightField, mouseX, mouseY)) {
                boolean changed = nudgeNumericTextField(heightField, amount > 0 ? 1.0 : -1.0, true);
                if (changed) {
                    UiSoundHelper.playDialClick();
                }
                return changed;
            }
            return false;
        }

        @Override
        public void setFocused(boolean focused) {
            if (!focused) {
                widthField.setFocused(false);
                heightField.setFocused(false);
                editorOpen = false;
                draggingPreviewHandle = false;
                presetPopupOpen = false;
                aspectPopupOpen = false;
                requestedPopoverX = null;
                requestedPopoverY = null;
            }
        }

        @Override
        public boolean isFocused() {
            return widthField.isFocused() || heightField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(widthBinding.path(), heightBinding.path());
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, "Popup ratio editor + custom sizing", x + 18, y);
            int totalWidth = 44 + 8 + 62 + 14 + 62;
            int startX = getPinnedFieldStartX(x, entryWidth, totalWidth);
            openButtonX = startX;
            buttonY = getControlRowY(y, entryHeight);
            drawControlButton(context, openButtonX, buttonY, 44, "Edit", editorOpen);
            widthField.setWidth(62);
            widthField.setX(startX + 52);
            widthField.setY(getControlRowY(y, entryHeight));
            widthField.render(context, mouseX, mouseY, delta);
            context.drawText(MinecraftClient.getInstance().textRenderer, "x", startX + 118, buttonY + 5, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            heightField.setWidth(62);
            heightField.setX(startX + 130);
            heightField.setY(getControlRowY(y, entryHeight));
            heightField.render(context, mouseX, mouseY, delta);
            drawDeleteButton(context, x, y, entryWidth);
        }

        @Override
        public void renderOverlay(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, float delta) {
            if (!editorOpen) {
                return;
            }
            context.getMatrices().push();
            context.getMatrices().translate(0.0F, 0.0F, OVERLAY_Z);
            drawResolutionPopover(context, mouseX, mouseY);
            context.getMatrices().pop();
        }

        @Override
        public boolean hasModalOverlay() {
            return editorOpen;
        }

        @Override
        public boolean openInlineEditor(double mouseX, double mouseY) {
            requestedPopoverX = Double.isNaN(mouseX) ? null : (int) Math.round(mouseX);
            requestedPopoverY = Double.isNaN(mouseY) ? null : (int) Math.round(mouseY);
            editorOpen = true;
            return true;
        }

        @Override
        public boolean handleOverlayClick(double mouseX, double mouseY, int button) {
            if (!editorOpen) {
                return false;
            }
            boolean insideMain = mouseX >= popoverX && mouseX <= popoverX + popoverWidth && mouseY >= popoverY && mouseY <= popoverY + popoverHeight;
            boolean insidePreset = presetPopupOpen && mouseX >= presetPopupX && mouseX <= presetPopupX + presetPopupWidth && mouseY >= presetPopupY && mouseY <= presetPopupY + presetPopupHeight;
            boolean insideAspect = aspectPopupOpen && mouseX >= aspectPopupX && mouseX <= aspectPopupX + aspectPopupWidth && mouseY >= aspectPopupY && mouseY <= aspectPopupY + aspectPopupHeight;
            if (insideMain || insidePreset || insideAspect) {
                return mouseClicked(mouseX, mouseY, button);
            }
            if (isMouseOverField(widthField, mouseX, mouseY) || isMouseOverField(heightField, mouseX, mouseY)) {
                return mouseClicked(mouseX, mouseY, button);
            }
            editorOpen = false;
            draggingPreviewHandle = false;
            return false;
        }

        private int parseDimension(TextFieldWidget field, int fallback) {
            try {
                return Math.max(1, Integer.parseInt(field.getText().trim()));
            } catch (NumberFormatException ignored) {
                return Math.max(1, fallback);
            }
        }

        private void publish(boolean width, String raw) {
            try {
                onChange.accept(width ? widthBinding.path() : heightBinding.path(), Double.parseDouble(raw), true);
            } catch (NumberFormatException ignored) {
            }
        }

        private void applyPreset(String preset) {
            String[] parts = preset.split("x");
            if (parts.length != 2) {
                return;
            }
            widthField.setText(parts[0]);
            heightField.setText(parts[1]);
            publish(true, parts[0]);
            publish(false, parts[1]);
        }

        private void applyAspectRatio(String ratio) {
            String[] parts = ratio.split(":");
            if (parts.length != 2) {
                return;
            }
            try {
                double rw = Double.parseDouble(parts[0]);
                double rh = Double.parseDouble(parts[1]);
                int currentHeight = parseDimension(heightField, (int) Math.round(heightBinding.value()));
                int nextWidth = Math.max(1, (int) Math.round(currentHeight * (rw / rh)));
                widthField.setText(String.valueOf(nextWidth));
                publish(true, widthField.getText());
            } catch (NumberFormatException ignored) {
            }
        }

        private void drawResolutionPopover(DrawContext context, int mouseX, int mouseY) {
            int widthValue = parseDimension(widthField, (int) Math.round(widthBinding.value()));
            int heightValue = parseDimension(heightField, (int) Math.round(heightBinding.value()));
            float aspect = heightValue <= 0 ? 1.0f : widthValue / (float) heightValue;
            String ratioText = simplifyRatio(widthValue, heightValue);

            popoverWidth = 208;
            popoverHeight = 166;
            int screenWidth = MinecraftClient.getInstance().currentScreen.width;
            int screenHeight = MinecraftClient.getInstance().currentScreen.height;
            int desiredX = requestedPopoverX == null ? widthField.getX() - popoverWidth - 10 : requestedPopoverX - popoverWidth - 10;
            int desiredY = requestedPopoverY == null ? widthField.getY() + 2 : requestedPopoverY - 8;
            popoverX = Math.max(8, Math.min(screenWidth - popoverWidth - 8, desiredX));
            popoverY = Math.max(26, Math.min(screenHeight - popoverHeight - 8, desiredY));

            int titleColor = new Color(uiColorContentBaseTitleText, true).getRGB();
            int subtitleColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
            int borderColor = new Color(uiColorBackgroundBorder, true).getRGB();
            int headerHeight = 34;

            context.fill(popoverX, popoverY, popoverX + popoverWidth, popoverY + popoverHeight, new Color(uiColorConfigPopupOverlay, true).getRGB());
            context.drawBorder(popoverX, popoverY, popoverWidth, popoverHeight, borderColor);
            context.fill(popoverX, popoverY, popoverX + popoverWidth, popoverY + headerHeight, withAlpha(uiColorHeader, 160));
            context.fill(popoverX, popoverY, popoverX + 4, popoverY + popoverHeight, withAlpha(uiColorHeaderStripe, 210));
            context.drawText(MinecraftClient.getInstance().textRenderer, "Resolution", popoverX + 10, popoverY + 8, titleColor, false);
            context.drawText(MinecraftClient.getInstance().textRenderer, widthValue + " x " + heightValue + "  |  " + ratioText, popoverX + 10, popoverY + 21, subtitleColor, false);

            int previewCardX = popoverX + 10;
            int previewCardY = popoverY + headerHeight + 8;
            int previewCardWidth = popoverWidth - 20;
            int previewCardHeight = 90;
            context.fill(previewCardX, previewCardY, previewCardX + previewCardWidth, previewCardY + previewCardHeight, withAlpha(uiColorHeader, 72));
            context.drawBorder(previewCardX, previewCardY, previewCardWidth, previewCardHeight, borderColor);
            context.drawText(MinecraftClient.getInstance().textRenderer, "Resize preview", previewCardX + 8, previewCardY + 8, titleColor, false);
            context.drawText(MinecraftClient.getInstance().textRenderer, "Drag the lower-right corner", previewCardX + 8, previewCardY + 20, subtitleColor, false);

            previewX = previewCardX + 8;
            previewY = previewCardY + 34;
            previewWidth = previewCardWidth - 16;
            previewHeight = 46;
            context.fill(previewX, previewY, previewX + previewWidth, previewY + previewHeight, withAlpha(uiColorContentBase, 96));
            context.drawBorder(previewX, previewY, previewWidth, previewHeight, borderColor);
            for (int gx = previewX + 6; gx < previewX + previewWidth - 4; gx += 10) {
                context.fill(gx, previewY + 4, gx + 1, previewY + previewHeight - 4, withAlpha(uiColorBackgroundBorder, 82));
            }
            for (int gy = previewY + 6; gy < previewY + previewHeight - 4; gy += 10) {
                context.fill(previewX + 4, gy, previewX + previewWidth - 4, gy + 1, withAlpha(uiColorBackgroundBorder, 82));
            }
            int innerWidth = aspect >= 1.0f ? previewWidth - 16 : Math.max(24, Math.round((previewWidth - 16) * aspect));
            int innerHeight = aspect >= 1.0f ? Math.max(14, Math.round((previewHeight - 12) / Math.max(1.0f, aspect))) : previewHeight - 12;
            int innerX = previewX + (previewWidth - innerWidth) / 2;
            int innerY = previewY + (previewHeight - innerHeight) / 2;
            context.fill(innerX, innerY, innerX + innerWidth, innerY + innerHeight, withAlpha(uiColorHeaderStripe, 176));
            context.drawBorder(innerX, innerY, innerWidth, innerHeight, titleColor);
            handleSize = 9;
            handleX = innerX + innerWidth - handleSize / 2;
            handleY = innerY + innerHeight - handleSize / 2;
            context.fill(handleX, handleY, handleX + handleSize, handleY + handleSize, withAlpha(uiColorContentBaseTitleText, 228));
            context.drawBorder(handleX, handleY, handleSize, handleSize, borderColor);

            popupButtonY = previewCardY + previewCardHeight + 10;
            presetButtonX = popoverX + 10;
            aspectButtonX = presetButtonX + 70;
            drawControlButton(context, presetButtonX, popupButtonY, 62, "Preset", presetPopupOpen);
            drawControlButton(context, aspectButtonX, popupButtonY, 62, "Aspect", aspectPopupOpen);
            context.drawText(MinecraftClient.getInstance().textRenderer, "Open a small helper popup when needed", popoverX + 10, popupButtonY + 24, subtitleColor, false);

            presetBounds.clear();
            ratioBounds.clear();
            String[] ratios = {"16:9", "16:10", "21:9", "4:3", "1:1"};
            if (presetPopupOpen) {
                presetPopupWidth = 116;
                presetPopupHeight = 14 + RESOLUTION_PRESET_LABELS.size() * 18;
                presetPopupX = Math.max(8, Math.min(MinecraftClient.getInstance().currentScreen.width - presetPopupWidth - 8, presetButtonX));
                presetPopupY = Math.min(MinecraftClient.getInstance().currentScreen.height - presetPopupHeight - 8, popupButtonY + 22);
                context.fill(presetPopupX, presetPopupY, presetPopupX + presetPopupWidth, presetPopupY + presetPopupHeight, new Color(uiColorConfigPopupOverlay, true).getRGB());
                context.drawBorder(presetPopupX, presetPopupY, presetPopupWidth, presetPopupHeight, borderColor);
                for (int i = 0; i < RESOLUTION_PRESET_LABELS.size(); i++) {
                    int rowY = presetPopupY + 8 + i * 18;
                    boolean active = RESOLUTION_PRESET_LABELS.get(i).equals(widthValue + "x" + heightValue);
                    drawControlButton(context, presetPopupX + 6, rowY, presetPopupWidth - 12, RESOLUTION_PRESET_LABELS.get(i), active);
                    presetBounds.add(new int[]{presetPopupX + 6, rowY, presetPopupWidth - 12, 18});
                }
            }
            if (aspectPopupOpen) {
                aspectPopupWidth = 128;
                aspectPopupHeight = 14 + ratios.length * 18;
                aspectPopupX = Math.max(8, Math.min(MinecraftClient.getInstance().currentScreen.width - aspectPopupWidth - 8, aspectButtonX));
                aspectPopupY = Math.min(MinecraftClient.getInstance().currentScreen.height - aspectPopupHeight - 8, popupButtonY + 22);
                context.fill(aspectPopupX, aspectPopupY, aspectPopupX + aspectPopupWidth, aspectPopupY + aspectPopupHeight, new Color(uiColorConfigPopupOverlay, true).getRGB());
                context.drawBorder(aspectPopupX, aspectPopupY, aspectPopupWidth, aspectPopupHeight, borderColor);
                for (int i = 0; i < ratios.length; i++) {
                    int rowY = aspectPopupY + 8 + i * 18;
                    boolean active = ratios[i].equals(ratioText);
                    drawControlButton(context, aspectPopupX + 6, rowY, aspectPopupWidth - 12, ratios[i], active);
                    ratioBounds.add(new int[]{aspectPopupX + 6, rowY, aspectPopupWidth - 12, 18});
                }
            }
        }

        private boolean isInsidePreviewHandle(double mouseX, double mouseY) {
            return editorOpen
                    && mouseX >= handleX
                    && mouseX <= handleX + handleSize
                    && mouseY >= handleY
                    && mouseY <= handleY + handleSize;
        }

        private void resizePreviewFromHandle(double mouseX, double mouseY) {
            int localWidth = Math.max(24, (int) Math.round(mouseX - previewX));
            int localHeight = Math.max(18, (int) Math.round(localWidth / Math.max(0.0001f, dragAspectRatio)));
            int maxPreviewWidth = previewWidth - 10;
            int maxPreviewHeight = previewHeight - 10;
            if (localWidth > maxPreviewWidth) {
                localWidth = maxPreviewWidth;
                localHeight = Math.max(18, (int) Math.round(localWidth / Math.max(0.0001f, dragAspectRatio)));
            }
            if (localHeight > maxPreviewHeight) {
                localHeight = maxPreviewHeight;
                localWidth = Math.max(24, (int) Math.round(localHeight * dragAspectRatio));
            }
            int scaledWidth = Math.max(1, localWidth * 16);
            int scaledHeight = Math.max(1, localHeight * 16);
            applyNearestPresetOrCustom(scaledWidth, scaledHeight);
        }

        private void applyNearestPresetOrCustom(int scaledWidth, int scaledHeight) {
            int[] nearestPreset = null;
            int bestDistance = Integer.MAX_VALUE;
            for (String preset : RESOLUTION_PRESET_LABELS) {
                String[] parts = preset.split("x");
                if (parts.length != 2) {
                    continue;
                }
                try {
                    int presetWidth = Integer.parseInt(parts[0]);
                    int presetHeight = Integer.parseInt(parts[1]);
                    int distance = Math.abs(presetWidth - scaledWidth) + Math.abs(presetHeight - scaledHeight);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        nearestPreset = new int[]{presetWidth, presetHeight};
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            if (nearestPreset != null && bestDistance <= 420) {
                activeDragPreset = nearestPreset;
                widthField.setText(String.valueOf(nearestPreset[0]));
                heightField.setText(String.valueOf(nearestPreset[1]));
                publish(true, widthField.getText());
                publish(false, heightField.getText());
                return;
            }
            activeDragPreset = null;
            widthField.setText(String.valueOf(Math.max(1, scaledWidth)));
            heightField.setText(String.valueOf(Math.max(1, scaledHeight)));
            publish(true, widthField.getText());
            publish(false, heightField.getText());
        }

        private void snapDraggedPreviewToPreset() {
            if (activeDragPreset == null) {
                return;
            }
            widthField.setText(String.valueOf(activeDragPreset[0]));
            heightField.setText(String.valueOf(activeDragPreset[1]));
            publish(true, widthField.getText());
            publish(false, heightField.getText());
        }

        private String simplifyRatio(int width, int height) {
            int a = Math.max(1, width);
            int b = Math.max(1, height);
            int gcd = greatestCommonDivisor(a, b);
            return (a / gcd) + ":" + (b / gcd);
        }

        private int greatestCommonDivisor(int a, int b) {
            while (b != 0) {
                int next = a % b;
                a = b;
                b = next;
            }
            return Math.max(1, a);
        }
    }

    private static class FeatureCardEntry extends ConfigEntry {
        private final String label;
        private final String enabledPath;
        private boolean enabled;
        private final String colorPath;
        private final JsonElement colorValue;
        private final ComponentBinding speedBinding;
        private final ComponentBinding scaleBinding;
        private final ComponentBinding timeoutBinding;
        private final BooleanPathConsumer booleanChange;
        private final NumericUpdateConsumer numericChange;
        private final JsonValueConsumer jsonValueChange;
        private final List<TextFieldWidget> fields = new ArrayList<>();
        private final List<ComponentBinding> fieldBindings = new ArrayList<>();
        private int toggleX;
        private int toggleY;

        private FeatureCardEntry(String label, String enabledPath, boolean enabled, String colorPath, JsonElement colorValue, ComponentBinding speedBinding, ComponentBinding scaleBinding, ComponentBinding timeoutBinding, BooleanPathConsumer booleanChange, NumericUpdateConsumer numericChange, JsonValueConsumer jsonValueChange) {
            this.label = label;
            this.enabledPath = enabledPath;
            this.enabled = enabled;
            this.colorPath = colorPath;
            this.colorValue = colorValue;
            this.speedBinding = speedBinding;
            this.scaleBinding = scaleBinding;
            this.timeoutBinding = timeoutBinding;
            this.booleanChange = booleanChange;
            this.numericChange = numericChange;
            this.jsonValueChange = jsonValueChange;
            for (ComponentBinding binding : List.of(speedBinding, scaleBinding, timeoutBinding)) {
                if (binding == null) {
                    continue;
                }
                fieldBindings.add(binding);
                TextFieldWidget field = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 58, 18, Text.literal(binding.label()));
                field.setText(Math.rint(binding.value()) == binding.value() ? String.valueOf((int) Math.round(binding.value())) : String.format(Locale.ROOT, "%.2f", binding.value()));
                field.setChangedListener(raw -> {
                    try {
                        numericChange.accept(binding.path(), Double.parseDouble(raw), Math.rint(binding.value()) == binding.value());
                    } catch (NumberFormatException ignored) {
                    }
                });
                fields.add(field);
            }
        }

        @Override
        public void tick() {
            fields.forEach(TextFieldWidget::tick);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (mouseX >= toggleX && mouseX <= toggleX + 46 && mouseY >= toggleY && mouseY <= toggleY + 18) {
                enabled = !enabled;
                booleanChange.accept(enabledPath, enabled);
                return true;
            }
            return focusClickedField(mouseX, mouseY, button, fields.toArray(new TextFieldWidget[0]));
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                enabled = !enabled;
                booleanChange.accept(enabledPath, enabled);
                return true;
            }
            for (TextFieldWidget field : fields) {
                if (field.keyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            for (TextFieldWidget field : fields) {
                if (field.charTyped(chr, modifiers)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            for (int i = 0; i < fields.size(); i++) {
                if (isMouseOverField(fields.get(i), mouseX, mouseY)) {
                    boolean integerLike = Math.rint(fieldBindings.get(i).value()) == fieldBindings.get(i).value();
                    return nudgeNumericTextField(fields.get(i), amount > 0 ? (integerLike ? 1.0 : 0.1) : (integerLike ? -1.0 : -0.1), integerLike);
                }
            }
            return false;
        }

        @Override
        public void setFocused(boolean focused) {
            if (!focused) {
                fields.forEach(field -> field.setFocused(false));
            }
        }

        @Override
        public boolean isFocused() {
            return fields.stream().anyMatch(TextFieldWidget::isFocused);
        }

        @Override
        public int getPreferredHeight(int defaultHeight) {
            return Math.max(defaultHeight, 74);
        }

        @Override
        public List<String> getDeleteTargets() {
            List<String> targets = new ArrayList<>();
            targets.add(enabledPath);
            if (colorPath != null) {
                targets.add(colorPath);
            }
            fieldBindings.forEach(binding -> targets.add(binding.path()));
            return targets;
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, enabled ? "Feature active" : "Feature disabled", x + 18, y);
            toggleX = x + 18;
            toggleY = y + 34;
            context.fill(toggleX, toggleY, toggleX + 46, toggleY + 18, enabled ? withAlpha(uiColorHeaderStripe, 190) : withAlpha(uiColorHeader, 120));
            context.drawBorder(toggleX, toggleY, 46, 18, new Color(uiColorBackgroundBorder, true).getRGB());
            context.drawText(MinecraftClient.getInstance().textRenderer, enabled ? "On" : "Off", toggleX + 12, toggleY + 5, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            if (colorPath != null && colorValue != null) {
                int colorX = toggleX + 56;
                int colorValueInt = colorValue.isJsonPrimitive() ? colorValue.getAsJsonPrimitive().isNumber() ? colorValue.getAsInt() : parseFeatureColor(colorValue.getAsString()) : withAlpha(uiColorHeaderStripe, 220);
                context.fill(colorX, toggleY, colorX + 18, toggleY + 18, colorValueInt);
                context.drawBorder(colorX, toggleY, 18, 18, new Color(uiColorBackgroundBorder, true).getRGB());
                int previewX = colorX + 26;
                int previewWidth = 96;
                int previewHeight = 10;
                double speed = speedBinding == null ? 1.0 : parseFieldValue(0, speedBinding.value());
                double scale = scaleBinding == null ? 1.0 : parseFieldValue(speedBinding == null ? 0 : 1, scaleBinding.value());
                double timeout = timeoutBinding == null ? 1.0 : parseFieldValue(fields.size() - 1, timeoutBinding.value());
                int fillWidth = Math.max(12, Math.min(previewWidth, (int) Math.round((Math.min(4.0, speed) / 4.0) * previewWidth)));
                int alpha = Math.max(88, Math.min(220, (int) Math.round((Math.min(1000.0, timeout) / 1000.0) * 180.0)));
                context.fill(previewX, toggleY + 4, previewX + previewWidth, toggleY + 4 + previewHeight, withAlpha(uiColorHeader, 86));
                context.fill(previewX, toggleY + 4, previewX + fillWidth, toggleY + 4 + previewHeight, (alpha << 24) | (colorValueInt & 0x00FFFFFF));
                int scaleMarkerX = previewX + Math.max(0, Math.min(previewWidth - 2, (int) Math.round(Math.min(2.0, scale) / 2.0 * (previewWidth - 2))));
                context.fill(scaleMarkerX, toggleY + 2, scaleMarkerX + 2, toggleY + 16, new Color(uiColorContentBaseTitleText, true).getRGB());
            }
            int fieldX = x + entryWidth - (fields.size() * 64) - 22;
            boolean showCaptions = showFieldCaptions();
            for (int i = 0; i < fields.size(); i++) {
                TextFieldWidget field = fields.get(i);
                ComponentBinding binding = fieldBindings.get(i);
                if (showCaptions) {
                    context.drawText(MinecraftClient.getInstance().textRenderer, binding.label(), fieldX, y + 10, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                }
                field.setWidth(58);
                field.setX(fieldX);
                field.setY(showCaptions ? y + 28 : y + 18);
                field.render(context, mouseX, mouseY, delta);
                fieldX += 64;
            }
            drawDeleteButton(context, x, y, entryWidth);
        }

        private int parseFeatureColor(String raw) {
            try {
                String normalized = raw.startsWith("#") ? raw.substring(1) : raw.startsWith("0x") || raw.startsWith("0X") ? raw.substring(2) : raw;
                long value = Long.parseLong(normalized, 16);
                if (normalized.length() <= 6) {
                    value |= 0xFF000000L;
                }
                return (int) value;
            } catch (Exception ignored) {
                return withAlpha(uiColorHeaderStripe, 220);
            }
        }

        private double parseFieldValue(int fieldIndex, double fallback) {
            if (fieldIndex < 0 || fieldIndex >= fields.size()) {
                return fallback;
            }
            try {
                return Double.parseDouble(fields.get(fieldIndex).getText());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }

    private static class RangeEntry extends ConfigEntry {
        private final String label;
        private final ComponentBinding minBinding;
        private final ComponentBinding maxBinding;
        private final TextFieldWidget minField;
        private final TextFieldWidget maxField;
        private final NumericUpdateConsumer onChange;
        private final boolean wholeNumber;
        private double minValue;
        private double maxValue;
        private double viewMin;
        private double viewMax;
        private int sliderX;
        private int sliderY;
        private int sliderWidth;
        private boolean draggingMin;
        private boolean draggingMax;
        private double dragStartMouseX;
        private double dragStartMinValue;
        private double dragStartMaxValue;

        private RangeEntry(String label, List<ComponentBinding> bindings, NumericUpdateConsumer onChange) {
            this.label = label;
            this.minBinding = bindings.get(0);
            this.maxBinding = bindings.get(1);
            this.onChange = onChange;
            this.wholeNumber = bindings.stream().allMatch(binding -> Math.rint(binding.value()) == binding.value());
            this.minValue = minBinding.value();
            this.maxValue = maxBinding.value();
            double[] bounds = computeRangeBounds(this.minValue, this.maxValue);
            this.viewMin = bounds[0];
            this.viewMax = bounds[1];
            this.minField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 54, 18, Text.literal("Min"));
            this.maxField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 54, 18, Text.literal("Max"));
            this.minField.setText(formatValue(minValue));
            this.maxField.setText(formatValue(maxValue));
            this.minField.setChangedListener(raw -> applyFieldChange(true, raw));
            this.maxField.setChangedListener(raw -> applyFieldChange(false, raw));
        }

        @Override
        public void tick() {
            minField.tick();
            maxField.tick();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (focusClickedField(mouseX, mouseY, button, minField, maxField)) {
                draggingMin = false;
                draggingMax = false;
                return true;
            }
            if (button == 0 && isOnTrack(mouseX, mouseY)) {
                int minKnobX = getKnobX(true);
                int maxKnobX = getKnobX(false);
                if (Math.abs(mouseX - minKnobX) <= Math.abs(mouseX - maxKnobX)) {
                    draggingMin = true;
                } else {
                    draggingMax = true;
                }
                dragStartMouseX = mouseX;
                dragStartMinValue = minValue;
                dragStartMaxValue = maxValue;
                UiSoundHelper.playButtonClick();
                applyDrag(mouseX);
                return true;
            }
            return false;
        }

        @Override
        public boolean handleMouseDrag(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (button != 0 || (!draggingMin && !draggingMax)) {
                return false;
            }
            applyDrag(mouseX);
            return true;
        }

        @Override
        public boolean handleMouseRelease(double mouseX, double mouseY, int button) {
            boolean wasDragging = draggingMin || draggingMax;
            draggingMin = false;
            draggingMax = false;
            return wasDragging;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return minField.keyPressed(keyCode, scanCode, modifiers) || maxField.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return minField.charTyped(chr, modifiers) || maxField.charTyped(chr, modifiers);
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            double step = getPrecisionStep();
            if (isMouseOverField(minField, mouseX, mouseY)) {
                boolean changed = nudgeNumericTextField(minField, amount > 0 ? step : -step, wholeNumber);
                if (changed) {
                    UiSoundHelper.playDialClick();
                }
                return changed;
            }
            if (isMouseOverField(maxField, mouseX, mouseY)) {
                boolean changed = nudgeNumericTextField(maxField, amount > 0 ? step : -step, wholeNumber);
                if (changed) {
                    UiSoundHelper.playDialClick();
                }
                return changed;
            }
            return false;
        }

        @Override
        public void setFocused(boolean focused) {
            if (!focused) {
                minField.setFocused(false);
                maxField.setFocused(false);
            }
        }

        @Override
        public boolean isFocused() {
            return minField.isFocused() || maxField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(minBinding.path(), maxBinding.path());
        }

        @Override
        public String getValidationError() {
            String minError = validateNumericField(minField, "Min", wholeNumber);
            if (minError != null) {
                return minError;
            }
            String maxError = validateNumericField(maxField, "Max", wholeNumber);
            if (maxError != null) {
                return maxError;
            }
            return null;
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, "Range control", x + 18, y);
            int fieldWidth = entryWidth < 430 ? 46 : 54;
            int fieldGap = 6;
            int fieldGroupWidth = fieldWidth * 2 + fieldGap;
            int minFieldX = getPinnedFieldStartX(x, entryWidth, fieldGroupWidth);
            int maxFieldX = minFieldX + fieldWidth + fieldGap;
            sliderX = x + Math.min(160, Math.max(98, entryWidth / 4));
            sliderY = y + 28;
            sliderWidth = Math.max(64, (minFieldX - 12) - sliderX);
            ensureRangeBoundsInclude(minValue, maxValue);
            double lowerBound = viewMin;
            double upperBound = viewMax;
            double span = Math.max(0.0001, upperBound - lowerBound);
            double leftProgress = (minValue - lowerBound) / span;
            double rightProgress = (maxValue - lowerBound) / span;
            context.fill(sliderX, sliderY, sliderX + sliderWidth, sliderY + 6, withAlpha(uiColorHeader, 110));
            context.fill(sliderX + (int) Math.round(leftProgress * sliderWidth), sliderY, sliderX + (int) Math.round(rightProgress * sliderWidth), sliderY + 6, withAlpha(uiColorHeaderStripe, 210));
            context.drawBorder(sliderX, sliderY, sliderWidth, 6, new Color(uiColorBackgroundBorder, true).getRGB());
            int minKnobX = sliderX + (int) Math.round(leftProgress * sliderWidth);
            int maxKnobX = sliderX + (int) Math.round(rightProgress * sliderWidth);
            context.fill(minKnobX - 3, sliderY - 3, minKnobX + 3, sliderY + 9, new Color(uiColorContentBaseTitleText, true).getRGB());
            context.fill(maxKnobX - 3, sliderY - 3, maxKnobX + 3, sliderY + 9, new Color(uiColorContentBaseTitleText, true).getRGB());
            context.drawText(MinecraftClient.getInstance().textRenderer, formatValue(minValue) + " - " + formatValue(maxValue), sliderX, y + 12, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            minField.setWidth(fieldWidth);
            minField.setX(minFieldX);
            minField.setY(y + 18);
            minField.render(context, mouseX, mouseY, delta);
            maxField.setWidth(fieldWidth);
            maxField.setX(maxFieldX);
            maxField.setY(y + 18);
            maxField.render(context, mouseX, mouseY, delta);
            drawDeleteButton(context, x, y, entryWidth);
        }

        private void applyFieldChange(boolean isMin, String raw) {
            try {
                double value = Double.parseDouble(raw);
                if (isMin) {
                    minValue = value;
                    if (minValue > maxValue) {
                        maxValue = minValue;
                        maxField.setText(formatValue(maxValue));
                    }
                    onChange.accept(minBinding.path(), minValue, wholeNumber);
                    onChange.accept(maxBinding.path(), maxValue, wholeNumber);
                } else {
                    maxValue = value;
                    if (maxValue < minValue) {
                        minValue = maxValue;
                        minField.setText(formatValue(minValue));
                    }
                }
                ensureRangeBoundsInclude(minValue, maxValue);
                onChange.accept(minBinding.path(), minValue, wholeNumber);
                onChange.accept(maxBinding.path(), maxValue, wholeNumber);
            } catch (NumberFormatException ignored) {
            }
        }

        private boolean isOnTrack(double mouseX, double mouseY) {
            return mouseX >= sliderX - 6 && mouseX <= sliderX + sliderWidth + 6 && mouseY >= sliderY - 4 && mouseY <= sliderY + 10;
        }

        private void applyDrag(double mouseX) {
            if (Screen.hasShiftDown()) {
                double baseValue = draggingMin ? dragStartMinValue : dragStartMaxValue;
                double nextValue = baseValue + ((mouseX - dragStartMouseX) * getPrecisionStep() * 0.18);
                nextValue = snapRangeValue(nextValue);
                if (draggingMin) {
                    minValue = Math.min(nextValue, maxValue);
                    minField.setText(formatValue(minValue));
                } else if (draggingMax) {
                    maxValue = Math.max(nextValue, minValue);
                    maxField.setText(formatValue(maxValue));
                }
                onChange.accept(minBinding.path(), minValue, wholeNumber);
                onChange.accept(maxBinding.path(), maxValue, wholeNumber);
                return;
            }
            double lowerBound = viewMin;
            double upperBound = viewMax;
            double progress = Math.max(0.0, Math.min(1.0, (mouseX - sliderX) / Math.max(1.0, sliderWidth)));
            double nextValue = lowerBound + (upperBound - lowerBound) * progress;
            nextValue = snapRangeValue(nextValue);
            if (draggingMin) {
                minValue = Math.min(nextValue, maxValue);
                minField.setText(formatValue(minValue));
            } else if (draggingMax) {
                maxValue = Math.max(nextValue, minValue);
                maxField.setText(formatValue(maxValue));
            }
            onChange.accept(minBinding.path(), minValue, wholeNumber);
            onChange.accept(maxBinding.path(), maxValue, wholeNumber);
        }

        private double snapRangeValue(double value) {
            double step = getPrecisionStep();
            return Math.round(value / step) * step;
        }

        private double getPrecisionStep() {
            if (wholeNumber) {
                return 1.0;
            }
            return Screen.hasShiftDown() ? 0.02 : 0.1;
        }

        private void ensureRangeBoundsInclude(double left, double right) {
            double floor = Math.min(left, right);
            double ceil = Math.max(left, right);
            if (floor >= viewMin && ceil <= viewMax) {
                return;
            }
            double[] bounds = computeRangeBounds(floor, ceil);
            viewMin = Math.min(viewMin, bounds[0]);
            viewMax = Math.max(viewMax, bounds[1]);
        }

        private double[] computeRangeBounds(double left, double right) {
            double floor = Math.min(left, right);
            double ceil = Math.max(left, right);
            double padding = Math.max(1.0, Math.abs(ceil - floor) * 0.35);
            return new double[]{Math.min(0.0, floor - padding), ceil + padding};
        }

        private int getKnobX(boolean min) {
            double lowerBound = viewMin;
            double upperBound = viewMax;
            double span = Math.max(0.0001, upperBound - lowerBound);
            double value = min ? minValue : maxValue;
            return sliderX + (int) Math.round(((value - lowerBound) / span) * sliderWidth);
        }

        private String formatValue(double value) {
            return wholeNumber ? String.valueOf((int) Math.round(value)) : String.format(Locale.ROOT, "%.2f", value);
        }
    }

    private static class SizeEntry extends ConfigEntry {
        private final String label;
        private final ComponentBinding widthBinding;
        private final ComponentBinding heightBinding;
        private final TextFieldWidget widthField;
        private final TextFieldWidget heightField;
        private final NumericUpdateConsumer onChange;
        private final boolean wholeNumber;
        private boolean aspectLocked;
        private boolean suppress;
        private double widthValue;
        private double heightValue;
        private final double baseRatio;
        private int lockButtonX;
        private int buttonY;

        private SizeEntry(String label, List<ComponentBinding> bindings, NumericUpdateConsumer onChange) {
            this.label = label;
            this.widthBinding = bindings.get(0);
            this.heightBinding = bindings.get(1);
            this.onChange = onChange;
            this.wholeNumber = bindings.stream().allMatch(binding -> Math.rint(binding.value()) == binding.value());
            this.widthValue = widthBinding.value();
            this.heightValue = heightBinding.value();
            this.baseRatio = heightValue == 0.0 ? 1.0 : widthValue / heightValue;
            this.widthField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 56, 18, Text.literal("W"));
            this.heightField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 56, 18, Text.literal("H"));
            this.widthField.setText(formatValue(widthValue));
            this.heightField.setText(formatValue(heightValue));
            this.widthField.setChangedListener(raw -> applyValue(true, raw));
            this.heightField.setChangedListener(raw -> applyValue(false, raw));
        }

        @Override
        public void tick() {
            widthField.tick();
            heightField.tick();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isControlButtonHit(mouseX, mouseY, lockButtonX, buttonY, 38)) {
                aspectLocked = !aspectLocked;
                return true;
            }
            return focusClickedField(mouseX, mouseY, button, widthField, heightField);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return widthField.keyPressed(keyCode, scanCode, modifiers) || heightField.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return widthField.charTyped(chr, modifiers) || heightField.charTyped(chr, modifiers);
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            double step = wholeNumber ? 1.0 : 0.1;
            if (isMouseOverField(widthField, mouseX, mouseY)) {
                return nudgeNumericTextField(widthField, amount > 0 ? step : -step, wholeNumber);
            }
            if (isMouseOverField(heightField, mouseX, mouseY)) {
                return nudgeNumericTextField(heightField, amount > 0 ? step : -step, wholeNumber);
            }
            return false;
        }

        @Override
        public void setFocused(boolean focused) {
            if (!focused) {
                widthField.setFocused(false);
                heightField.setFocused(false);
            }
        }

        @Override
        public boolean isFocused() {
            return widthField.isFocused() || heightField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(widthBinding.path(), heightBinding.path());
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, aspectLocked ? "Aspect locked" : "Aspect unlocked", x + 18, y);
            int widthX = getPinnedFieldStartX(x, entryWidth, 136);
            widthField.setX(widthX);
            widthField.setY(y + 18);
            widthField.render(context, mouseX, mouseY, delta);
            heightField.setX(widthX + 64);
            heightField.setY(y + 18);
            heightField.render(context, mouseX, mouseY, delta);
            lockButtonX = widthX + 128;
            buttonY = y + 18;
            drawControlButton(context, lockButtonX, buttonY, 38, aspectLocked ? "Lock" : "Free", aspectLocked);
            drawDeleteButton(context, x, y, entryWidth);
        }

        private void applyValue(boolean width, String raw) {
            if (suppress) {
                return;
            }
            try {
                double parsed = Double.parseDouble(raw);
                suppress = true;
                if (width) {
                    widthValue = parsed;
                    if (aspectLocked) {
                        heightValue = baseRatio == 0.0 ? heightValue : widthValue / baseRatio;
                        heightField.setText(formatValue(heightValue));
                    }
                } else {
                    heightValue = parsed;
                    if (aspectLocked) {
                        widthValue = heightValue * baseRatio;
                        widthField.setText(formatValue(widthValue));
                    }
                }
                onChange.accept(widthBinding.path(), widthValue, wholeNumber);
                onChange.accept(heightBinding.path(), heightValue, wholeNumber);
            } catch (NumberFormatException ignored) {
            } finally {
                suppress = false;
            }
        }

        private String formatValue(double value) {
            return wholeNumber ? String.valueOf((int) Math.round(value)) : String.format(Locale.ROOT, "%.2f", value);
        }
    }

    private static class ListEntry extends ConfigEntry {
        private final String label;
        private final TextFieldWidget valueField;
        private final List<String> values = new ArrayList<>();
        private final JsonValueConsumer onChange;
        private int selectedIndex = 0;
        private int previousButtonX;
        private int nextButtonX;
        private int addButtonX;
        private int removeButtonX;
        private int buttonY;

        private ListEntry(String label, JsonArray array, JsonValueConsumer onChange) {
            this.label = label;
            this.onChange = onChange;
            array.forEach(element -> values.add(element.getAsString()));

            this.valueField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 180, 18, Text.literal(label));
            this.valueField.setChangedListener(value -> {
                if (values.isEmpty()) {
                    values.add(value);
                    selectedIndex = 0;
                } else if (selectedIndex >= 0 && selectedIndex < values.size()) {
                    values.set(selectedIndex, value);
                }
                publish();
            });

            syncField();
        }

        public void tick() {
            valueField.tick();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isControlButtonHit(mouseX, mouseY, previousButtonX, buttonY, CONTROL_BUTTON_WIDTH) && !values.isEmpty()) {
                selectedIndex = Math.max(0, selectedIndex - 1);
                UiSoundHelper.playButtonClick();
                syncField();
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, nextButtonX, buttonY, CONTROL_BUTTON_WIDTH) && !values.isEmpty()) {
                selectedIndex = Math.min(values.size() - 1, selectedIndex + 1);
                UiSoundHelper.playButtonClick();
                syncField();
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, addButtonX, buttonY, CONTROL_BUTTON_WIDTH)) {
                values.add("");
                selectedIndex = values.size() - 1;
                UiSoundHelper.playButtonClick();
                syncField();
                publish();
                return true;
            }
            if (!values.isEmpty() && isControlButtonHit(mouseX, mouseY, removeButtonX, buttonY, CONTROL_BUTTON_WIDTH)) {
                values.remove(selectedIndex);
                if (selectedIndex >= values.size()) {
                    selectedIndex = Math.max(0, values.size() - 1);
                }
                UiSoundHelper.playButtonClick();
                syncField();
                publish();
                return true;
            }
            return valueField.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            if (values.isEmpty() || !isMouseOverField(valueField, mouseX, mouseY)) {
                return false;
            }
            if (amount < 0) {
                selectedIndex = Math.min(values.size() - 1, selectedIndex + 1);
            } else if (amount > 0) {
                selectedIndex = Math.max(0, selectedIndex - 1);
            }
            UiSoundHelper.playDialClick();
            syncField();
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (!values.isEmpty()) {
                if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_UP) {
                    selectedIndex = Math.max(0, selectedIndex - 1);
                    UiSoundHelper.playButtonClick();
                    syncField();
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_DOWN) {
                    selectedIndex = Math.min(values.size() - 1, selectedIndex + 1);
                    UiSoundHelper.playButtonClick();
                    syncField();
                    return true;
                }
            }
            return valueField.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return valueField.charTyped(chr, modifiers);
        }

        @Override
        public void setFocused(boolean focused) {
            valueField.setFocused(focused);
        }

        @Override
        public boolean isFocused() {
            return valueField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            int baseX = x + 16;
            int valueWidth = Math.max(108, Math.min(156, entryWidth / 3));
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, values.isEmpty() ? "Empty list" : "Item " + (selectedIndex + 1) + " / " + values.size(), baseX, y);
            int groupGap = 6;
            int groupWidth = CONTROL_BUTTON_WIDTH + valueWidth + CONTROL_BUTTON_WIDTH + groupGap + CONTROL_BUTTON_WIDTH + CONTROL_BUTTON_WIDTH;
            previousButtonX = getPinnedFieldStartX(x, entryWidth, groupWidth);
            nextButtonX = previousButtonX + CONTROL_BUTTON_WIDTH + valueWidth;
            addButtonX = nextButtonX + CONTROL_BUTTON_WIDTH + groupGap;
            removeButtonX = addButtonX + CONTROL_BUTTON_WIDTH;
            buttonY = getControlRowY(y, entryHeight);
            drawControlButton(context, previousButtonX, buttonY, CONTROL_BUTTON_WIDTH, "<", !values.isEmpty());
            drawControlButton(context, nextButtonX, buttonY, CONTROL_BUTTON_WIDTH, ">", !values.isEmpty());
            drawControlButton(context, addButtonX, buttonY, CONTROL_BUTTON_WIDTH, "+", true);
            drawControlButton(context, removeButtonX, buttonY, CONTROL_BUTTON_WIDTH, "-", !values.isEmpty());

            valueField.setWidth(valueWidth);
            valueField.setX(previousButtonX + CONTROL_BUTTON_WIDTH);
            valueField.setY(getControlRowY(y, entryHeight));
            valueField.setEditableColor(new Color(uiColorContentBaseDescriptionText, true).getRGB());
            valueField.render(context, mouseX, mouseY, delta);
            drawDeleteButton(context, x, y, entryWidth);
        }

        private void syncField() {
            valueField.setText(values.isEmpty() ? "" : values.get(selectedIndex));
        }

        private void publish() {
            JsonArray array = new JsonArray();
            for (String value : values) {
                array.add(value);
            }
            onChange.accept(label, array);
        }
    }

    private static class OptionEntry extends ConfigEntry {
        private final String label;
        private final List<String> options;
        private final TextFieldWidget valueField;
        private final StringConsumer onChange;
        private int selectedIndex;
        private int previousButtonX;
        private int nextButtonX;
        private int buttonY;

        private OptionEntry(String label, String currentValue, List<String> options, StringConsumer onChange) {
            this.label = label;
            this.options = new ArrayList<>(options);
            this.onChange = onChange;
            this.selectedIndex = Math.max(0, this.options.indexOf(currentValue));

            this.valueField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 160, 20, Text.literal(label));
            this.valueField.setText(this.options.get(this.selectedIndex));
            this.valueField.setEditable(false);
        }

        private void cycle(int direction) {
            if (options.isEmpty()) {
                return;
            }
            selectedIndex = (selectedIndex + direction + options.size()) % options.size();
            String selected = options.get(selectedIndex);
            valueField.setText(selected);
            onChange.accept(selected);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isControlButtonHit(mouseX, mouseY, previousButtonX, buttonY, CONTROL_BUTTON_WIDTH)) {
                cycle(-1);
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, nextButtonX, buttonY, CONTROL_BUTTON_WIDTH)) {
                cycle(1);
                UiSoundHelper.playButtonClick();
                return true;
            }
            return valueField.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            if (!isMouseOverField(valueField, mouseX, mouseY)) {
                return false;
            }
            if (amount < 0) {
                cycle(1);
                UiSoundHelper.playDialClick();
                return true;
            }
            if (amount > 0) {
                cycle(-1);
                UiSoundHelper.playDialClick();
                return true;
            }
            return false;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_LEFT) {
                cycle(-1);
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_RIGHT) {
                cycle(1);
                UiSoundHelper.playButtonClick();
                return true;
            }
            return valueField.keyPressed(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public void setFocused(boolean focused) {
            valueField.setFocused(focused);
        }

        @Override
        public boolean isFocused() {
            return valueField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            int valueWidth = Math.max(100, Math.min(132, entryWidth / 3));
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, "Select from discovered options", x + 18, y);
            previousButtonX = getPinnedFieldStartX(x, entryWidth, valueWidth + (CONTROL_BUTTON_WIDTH * 2));
            nextButtonX = previousButtonX + valueWidth + CONTROL_BUTTON_WIDTH;
            buttonY = getControlRowY(y, entryHeight);
            drawControlButton(context, previousButtonX, buttonY, CONTROL_BUTTON_WIDTH, "<", true);
            valueField.setWidth(valueWidth);
            valueField.setX(previousButtonX + CONTROL_BUTTON_WIDTH);
            valueField.setY(getControlRowY(y, entryHeight));
            valueField.render(context, mouseX, mouseY, delta);
            drawControlButton(context, nextButtonX, buttonY, CONTROL_BUTTON_WIDTH, ">", true);
            drawDeleteButton(context, x, y, entryWidth);
        }

        @Override
        public void renderOverlay(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, float delta) {
            if (isMouseOverField(valueField, mouseX, mouseY) && MinecraftClient.getInstance().textRenderer.getWidth(valueField.getText()) > valueField.getWidth() - 8) {
                context.getMatrices().push();
                context.getMatrices().translate(0.0F, 0.0F, OVERLAY_Z + 80.0F);
                context.drawTooltip(MinecraftClient.getInstance().textRenderer, Text.literal(valueField.getText()), mouseX, mouseY);
                context.getMatrices().pop();
            }
        }
    }

    private static class IntegerEntry extends ConfigEntry {
        private final TextFieldWidget textField;
        private final String label;
        private int buttonWidth;
        private int incrementButtonX;
        private int decrementButtonX;
        private int buttonY;
        private int heldDirection;
        private long holdStartTime;
        private long lastRepeatTime;

        public IntegerEntry(String label, int initialValue, IntConsumer onChange) {
            this.label = label;

            // Initialize the text field and buttons
            this.textField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 60, 18, Text.literal(label));
            this.textField.setText(String.valueOf(initialValue));
            this.textField.setChangedListener(value -> {
                try {
                    onChange.accept(Integer.parseInt(value));
                } catch (NumberFormatException ignored) {
                    // Ignore invalid input
                }
            });

        }

        private int getIntValue() {
            try {
                return Integer.parseInt(this.textField.getText());
            } catch (NumberFormatException e) {
                return 0; // Default to 0 on invalid input
            }
        }

        private void nudgeValue(int delta) {
            this.textField.setText(String.valueOf(getIntValue() + delta));
        }

        public void tick() {
            this.textField.tick();
            if (heldDirection == 0) {
                return;
            }
            long now = Util.getMeasuringTimeMs();
            long heldFor = now - holdStartTime;
            long interval = Math.max(35L, 240L - (heldFor / 5L));
            if (now - lastRepeatTime >= interval) {
                nudgeValue(heldDirection);
                lastRepeatTime = now;
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isControlButtonHit(mouseX, mouseY, decrementButtonX, buttonY, buttonWidth)) {
                heldDirection = -1;
                holdStartTime = Util.getMeasuringTimeMs();
                lastRepeatTime = holdStartTime;
                nudgeValue(-1);
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, incrementButtonX, buttonY, buttonWidth)) {
                heldDirection = 1;
                holdStartTime = Util.getMeasuringTimeMs();
                lastRepeatTime = holdStartTime;
                nudgeValue(1);
                UiSoundHelper.playButtonClick();
                return true;
            }
            heldDirection = 0;
            return this.textField.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            if (amount == 0) {
                return false;
            }
            if (!isMouseOverField(this.textField, mouseX, mouseY)) {
                return false;
            }
            boolean changed = nudgeNumericTextField(this.textField, amount > 0 ? 1.0 : -1.0, true);
            if (changed) {
                UiSoundHelper.playDialClick();
            }
            return changed;
        }

        @Override
        public boolean handleMouseRelease(double mouseX, double mouseY, int button) {
            boolean wasHolding = heldDirection != 0;
            heldDirection = 0;
            return wasHolding;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_LEFT) {
                nudgeValue(-1);
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_RIGHT) {
                nudgeValue(1);
                UiSoundHelper.playButtonClick();
                return true;
            }
            return this.textField.keyPressed(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return this.textField.charTyped(chr, modifiers) || super.charTyped(chr, modifiers);
        }

        @Override
        public void setFocused(boolean focused) {
            this.textField.setFocused(focused);
            if (!focused) {
                heldDirection = 0;
            }
        }

        @Override
        public boolean isFocused() {
            return this.textField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            int buttonSegmentWidth = 28;
            int fieldWidth = 52;
            int groupWidth = buttonSegmentWidth * 2 + fieldWidth;
            buttonWidth = buttonSegmentWidth;
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            int currentValue = getIntValue();
            String subtitle = Math.abs((long) currentValue) >= 1000L
                    ? "Integer value  " + formatCompactNumber(currentValue, true)
                    : "Integer value";
            drawEntryText(context, label, subtitle, x + 18, y);
            decrementButtonX = getCompactFieldX(x, entryWidth, groupWidth);
            incrementButtonX = decrementButtonX + buttonSegmentWidth + fieldWidth;
            buttonY = getControlRowY(y, entryHeight);
            drawControlButton(context, decrementButtonX, buttonY, buttonSegmentWidth, "<", true);
            drawControlButton(context, incrementButtonX, buttonY, buttonSegmentWidth, ">", true);
            this.textField.setWidth(fieldWidth);
            this.textField.setX(decrementButtonX + buttonSegmentWidth);
            this.textField.setY(getControlRowY(y, entryHeight));
            this.textField.render(context, mouseX, mouseY, delta);
            drawDeleteButton(context, x, y, entryWidth);
        }
    }

    private static class DoubleEntry extends ConfigEntry {
        private final TextFieldWidget textField;
        private final String label;

        public DoubleEntry(String label, double initialValue, DoubleConsumer onChange) {
            this.label = label;
            this.textField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 100, 20, Text.literal(label));
            this.textField.setText(String.valueOf(initialValue));
            this.textField.setChangedListener(value -> {
                try {
                    onChange.accept(Double.parseDouble(value));
                } catch (NumberFormatException ignored) {
                }
            });
        }

        public void tick() {
            this.textField.tick();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return this.textField.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_LEFT) {
                adjustValue(-0.1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_RIGHT) {
                adjustValue(0.1);
                return true;
            }
            return this.textField.keyPressed(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            if (amount == 0) {
                return false;
            }
            if (!isMouseOverField(this.textField, mouseX, mouseY)) {
                return false;
            }
            boolean changed = nudgeNumericTextField(this.textField, amount > 0 ? 0.1 : -0.1, false);
            if (changed) {
                UiSoundHelper.playDialClick();
            }
            return changed;
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return this.textField.charTyped(chr, modifiers) || super.charTyped(chr, modifiers);
        }

        @Override
        public void setFocused(boolean focused) {
            this.textField.setFocused(focused);
        }

        @Override
        public boolean isFocused() {
            return this.textField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            int fieldWidth = Math.max(72, Math.min(96, entryWidth / 4));
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            double currentValue;
            try {
                currentValue = Double.parseDouble(this.textField.getText());
            } catch (NumberFormatException ignored) {
                currentValue = 0.0;
            }
            drawEntryText(context, label, "Decimal value  " + formatCompactNumber(currentValue, false), x + 18, y);
            this.textField.setWidth(fieldWidth);
            this.textField.setX(getCompactFieldX(x, entryWidth, fieldWidth));
            this.textField.setY(getControlRowY(y, entryHeight));
            this.textField.render(context, mouseX, mouseY, delta);
            drawDeleteButton(context, x, y, entryWidth);
        }

        private void adjustValue(double delta) {
            try {
                double value = Double.parseDouble(this.textField.getText());
                this.textField.setText(String.format(Locale.ROOT, "%.2f", value + delta));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static class StringEntry extends ConfigEntry {
        private final TextFieldWidget textField;
        private final String label;

        public StringEntry(String label, String initialValue, StringConsumer onChange) {
            this.label = label;
            this.textField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 200, 20, Text.literal(label));
            this.textField.setMaxLength(8192);
            this.textField.setText(initialValue);
            this.textField.setChangedListener(onChange::accept);
        }

        public void tick() {
            this.textField.tick();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return this.textField.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return this.textField.keyPressed(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return this.textField.charTyped(chr, modifiers) || super.charTyped(chr, modifiers);
        }

        @Override
        public void setFocused(boolean focused) {
            this.textField.setFocused(focused);
        }

        @Override
        public boolean isFocused() {
            return this.textField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            int fieldWidth = Math.max(220, Math.min(entryWidth - 138, (entryWidth * 3) / 5));
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, null, x + 18, y);
            this.textField.setWidth(fieldWidth);
            this.textField.setX(getCompactFieldX(x, entryWidth, fieldWidth));
            this.textField.setY(getControlRowY(y, entryHeight));
            this.textField.render(context, mouseX, mouseY, delta);
            drawDeleteButton(context, x, y, entryWidth);
        }
    }

    private static class LongStringEntry extends ConfigEntry {
        private final TextFieldWidget textField;
        private final String label;

        private LongStringEntry(String label, String initialValue, StringConsumer onChange) {
            this.label = label;
            this.textField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 320, 18, Text.literal(label));
            this.textField.setMaxLength(32767);
            this.textField.setText(initialValue);
            this.textField.setChangedListener(onChange::accept);
        }

        @Override
        public void tick() {
            textField.tick();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return textField.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return textField.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return textField.charTyped(chr, modifiers);
        }

        @Override
        public void setFocused(boolean focused) {
            textField.setFocused(focused);
        }

        @Override
        public boolean isFocused() {
            return textField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            int fieldWidth = Math.max(320, entryWidth - 92);
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, "Long text value", x + 18, y);
            textField.setWidth(fieldWidth);
            textField.setX(x + 18);
            textField.setY(getControlRowY(y, entryHeight));
            textField.render(context, mouseX, mouseY, delta);
            String preview = textField.getText();
            int previewWidth = fieldWidth;
            String wrapped = preview;
            while (MinecraftClient.getInstance().textRenderer.getWidth(wrapped) > previewWidth && wrapped.length() > 4) {
                wrapped = wrapped.substring(0, wrapped.length() - 4) + "...";
            }
            context.drawText(MinecraftClient.getInstance().textRenderer, wrapped, textField.getX(), y + 38, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            drawDeleteButton(context, x, y, entryWidth);
        }
    }

    private static class PathEntry extends ConfigEntry {
        private final String label;
        private final TextFieldWidget textField;

        private PathEntry(String label, String initialValue, StringConsumer onChange) {
            this.label = label;
            this.textField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 280, 18, Text.literal(label));
            this.textField.setMaxLength(8192);
            this.textField.setText(initialValue);
            this.textField.setChangedListener(onChange::accept);
        }

        @Override
        public void tick() {
            textField.tick();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return textField.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return textField.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return textField.charTyped(chr, modifiers);
        }

        @Override
        public void setFocused(boolean focused) {
            textField.setFocused(focused);
        }

        @Override
        public boolean isFocused() {
            return textField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            int fieldWidth = Math.max(164, Math.min(228, entryWidth / 2));
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, "Asset / file path", x + 18, y);
            textField.setWidth(fieldWidth);
            textField.setX(getCompactFieldX(x, entryWidth, fieldWidth));
            textField.setY(getControlRowY(y, entryHeight));
            textField.render(context, mouseX, mouseY, delta);
            drawDeleteButton(context, x, y, entryWidth);
        }
    }

    private static class LinkEntry extends ConfigEntry {
        private final String label;
        private final TextFieldWidget textField;
        private int openButtonX;
        private int presetButtonX;
        private int buttonY;

        private LinkEntry(String label, String initialValue, StringConsumer onChange) {
            this.label = label;
            this.textField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 244, 18, Text.literal(label));
            this.textField.setMaxLength(8192);
            this.textField.setText(initialValue);
            this.textField.setChangedListener(onChange::accept);
        }

        @Override
        public void tick() {
            textField.tick();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isControlButtonHit(mouseX, mouseY, openButtonX, buttonY, 40)) {
                Util.getOperatingSystem().open(textField.getText());
                return true;
            }
            return textField.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return textField.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return textField.charTyped(chr, modifiers);
        }

        @Override
        public void setFocused(boolean focused) {
            textField.setFocused(focused);
        }

        @Override
        public boolean isFocused() {
            return textField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            int fieldWidth = Math.max(138, Math.min(204, entryWidth / 2));
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, "External link", x + 18, y);
            textField.setWidth(fieldWidth);
            textField.setX(getCompactFieldX(x, entryWidth, fieldWidth + 46));
            textField.setY(getControlRowY(y, entryHeight));
            textField.render(context, mouseX, mouseY, delta);
            openButtonX = textField.getX() + fieldWidth + 6;
            buttonY = getControlRowY(y, entryHeight);
            drawControlButton(context, openButtonX, buttonY, 40, "Open", true);
            drawDeleteButton(context, x, y, entryWidth);
        }
    }

    private static class RegistryIdEntry extends ConfigEntry {
        private final String label;
        private final TextFieldWidget textField;
        private final RegistryKind registryKind;
        private int iconX;
        private int iconY;
        private int iconSize;
        private int badgeX;
        private int badgeY;
        private int badgeWidth;
        private int pickerX;
        private int pickerY;
        private int pickerWidth;
        private int pickerHeight;
        private boolean pickerOpen;
        private int hoveredSuggestion = -1;
        private int pickerScrollOffset;

        private RegistryIdEntry(String label, String initialValue, StringConsumer onChange) {
            this.label = label;
            this.registryKind = RegistryKind.fromPath(label);
            this.textField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 178, 18, Text.literal(label));
            this.textField.setMaxLength(512);
            this.textField.setText(initialValue);
            this.textField.setChangedListener(onChange::accept);
        }

        @Override
        public void tick() {
            textField.tick();
            syncPreviewSoundState();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && isInsidePicker(mouseX, mouseY)) {
                List<String> suggestions = getSuggestions();
                if (hoveredSuggestion >= 0 && hoveredSuggestion < suggestions.size()) {
                    textField.setText(suggestions.get(hoveredSuggestion));
                    pickerOpen = false;
                    UiSoundHelper.playButtonClick();
                    return true;
                }
            }

            if (button == 0 && mouseX >= textField.getX() - 19 && mouseX <= textField.getX() + 1 && mouseY >= textField.getY() - 1 && mouseY <= textField.getY() + 19) {
                if (registryKind == RegistryKind.SOUND) {
                    if (isPreviewSoundActive()) {
                        stopPreviewSound();
                    } else {
                        playPreviewSound();
                    }
                    return true;
                }
            }
            boolean clickedField = textField.mouseClicked(mouseX, mouseY, button);
            if (clickedField) {
                pickerOpen = !textField.getText().trim().isEmpty();
                pickerScrollOffset = 0;
                return true;
            }
            if (!isInsidePicker(mouseX, mouseY)) {
                pickerOpen = false;
            }
            return false;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            boolean handled = textField.keyPressed(keyCode, scanCode, modifiers);
            if (handled) {
                pickerOpen = !textField.getText().trim().isEmpty();
                hoveredSuggestion = -1;
            }
            return handled;
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            boolean handled = textField.charTyped(chr, modifiers);
            if (handled) {
                pickerOpen = !textField.getText().trim().isEmpty();
                hoveredSuggestion = -1;
            }
            return handled;
        }

        @Override
        public void setFocused(boolean focused) {
            textField.setFocused(focused);
            if (!focused) {
                pickerOpen = false;
                hoveredSuggestion = -1;
            }
        }

        @Override
        public boolean isFocused() {
            return textField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            int fieldWidth = Math.max(136, Math.min(188, entryWidth / 2));
            boolean valid = isValidId();
            ItemStack previewStack = getPreviewStack();
            Identifier identifier = Identifier.tryParse(textField.getText().trim());
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, valid ? "Valid " + registryKind.displayName + " id" : "Invalid " + registryKind.displayName + " id", x + 18, y);
            int previewBoxWidth = 20;
            int fieldBaseX = getCompactFieldX(x, entryWidth, fieldWidth + 24 + previewBoxWidth - 1);
            int controlY = getControlRowY(y, entryHeight);
            iconSize = 16;
            iconY = controlY + 1;
            iconX = drawAttachedPreviewBox(context, fieldBaseX, controlY, iconSize, mouseX >= fieldBaseX - previewBoxWidth && mouseX <= fieldBaseX + 1 && mouseY >= controlY - 1 && mouseY <= controlY + 19);
            if (!previewStack.isEmpty()) {
                context.drawItem(previewStack, iconX, iconY);
            } else if (registryKind == RegistryKind.EFFECT && drawEffectIcon(context)) {
                // Drawn through the status-effect sprite manager.
            } else if (registryKind == RegistryKind.PARTICLE) {
                drawParticlePreview(context, iconX, iconY, identifier);
            } else if (registryKind == RegistryKind.SOUND) {
                if (!isPreviewSoundActive()) {
                    context.drawTexture(loadExternalPngTexture(uiImageDirectory, "play.png"), iconX, iconY, 0, 0, 16, 16, 16, 16);
                } else {
                    context.drawTexture(loadExternalPngTexture(uiImageDirectory, "stop.png"), iconX, iconY, 0, 0, 16, 16, 16, 16);
                }
            } else {
                String abbrev = registryKind.abbrev;
                int textX = iconX + Math.max(0, (iconSize - MinecraftClient.getInstance().textRenderer.getWidth(abbrev)) / 2);
                context.drawText(MinecraftClient.getInstance().textRenderer, abbrev, textX, iconY + 4, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            }
            textField.setWidth(fieldWidth);
            textField.setX(fieldBaseX);
            textField.setY(controlY);
            textField.render(context, mouseX, mouseY, delta);
            badgeX = textField.getX() + fieldWidth;
            badgeY = controlY;
            badgeWidth = 20;
            int badgeColor = valid ? new Color(uiColorConfigBooleanTrue, true).getRGB() : withAlpha(uiColorWarningPromptText, 140);
            context.fill(badgeX, badgeY - 1, badgeX + badgeWidth, badgeY + 19, badgeColor);
            context.drawBorder(badgeX, badgeY - 1, badgeWidth, 20, new Color(uiColorBackgroundBorder, true).getRGB());
            String badgeLabel = valid ? "✓" : "✕";
            int badgeTextX = badgeX + Math.max(0, (badgeWidth - MinecraftClient.getInstance().textRenderer.getWidth(badgeLabel)) / 2);
            context.drawText(MinecraftClient.getInstance().textRenderer, badgeLabel, badgeTextX, controlY + 4, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            if (mouseX >= fieldBaseX - previewBoxWidth && mouseX <= fieldBaseX + 1 && mouseY >= controlY - 1 && mouseY <= controlY + 19) {
                drawPreviewTooltip(context, mouseX, mouseY, previewStack);
            }
            if (mouseX >= badgeX && mouseX <= badgeX + badgeWidth && mouseY >= badgeY - 1 && mouseY <= badgeY + 19) {
                context.drawTooltip(MinecraftClient.getInstance().textRenderer, Text.literal(valid ? "Valid " + registryKind.displayName + " id" : getValidationError()), mouseX, mouseY);
            }
            drawDeleteButton(context, x, y, entryWidth);
        }

        @Override
        public void renderOverlay(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, float delta) {
            if (!pickerOpen) {
                return;
            }
            List<String> suggestions = getSuggestions();
            if (suggestions.isEmpty()) {
                pickerOpen = false;
                return;
            }
            pickerWidth = Math.max(182, textField.getWidth() + 24);
            int visibleRows = Math.min(7, suggestions.size());
            pickerHeight = 8 + visibleRows * 18;
            pickerX = Math.max(8, Math.min(textField.getX(), MinecraftClient.getInstance().currentScreen.width - pickerWidth - 8));
            pickerY = Math.min(MinecraftClient.getInstance().currentScreen.height - pickerHeight - 8, textField.getY() + 22);
            hoveredSuggestion = -1;
            context.getMatrices().push();
            context.getMatrices().translate(0.0F, 0.0F, OVERLAY_Z);
            context.fill(pickerX, pickerY, pickerX + pickerWidth, pickerY + pickerHeight, new Color(uiColorConfigPopupOverlay, true).getRGB());
            context.drawBorder(pickerX, pickerY, pickerWidth, pickerHeight, new Color(uiColorConfigPopupBorder, true).getRGB());
            int maxOffset = Math.max(0, suggestions.size() - visibleRows);
            pickerScrollOffset = Math.max(0, Math.min(maxOffset, pickerScrollOffset));
            for (int i = 0; i < visibleRows; i++) {
                int suggestionIndex = i + pickerScrollOffset;
                if (suggestionIndex >= suggestions.size()) {
                    break;
                }
                int rowY = pickerY + 4 + i * 18;
                boolean rowHovered = mouseX >= pickerX + 2 && mouseX <= pickerX + pickerWidth - 2 && mouseY >= rowY && mouseY <= rowY + 16;
                if (rowHovered) {
                    hoveredSuggestion = suggestionIndex;
                    context.fill(pickerX + 2, rowY, pickerX + pickerWidth - 2, rowY + 16, new Color(uiColorConfigPickerSelected, true).getRGB());
                }
                String suggestion = suggestions.get(suggestionIndex);
                context.drawText(MinecraftClient.getInstance().textRenderer, suggestion, pickerX + 24, rowY + 4, new Color(uiColorConfigPickerText, true).getRGB(), false);
                drawSuggestionGlyph(context, pickerX + 5, rowY + 1, suggestion);
            }
            context.getMatrices().pop();
        }

        @Override
        public boolean hasModalOverlay() {
            return pickerOpen;
        }

        @Override
        public boolean handleOverlayClick(double mouseX, double mouseY, int button) {
            if (!pickerOpen) {
                return false;
            }
            if (isInsidePicker(mouseX, mouseY)) {
                return mouseClicked(mouseX, mouseY, button);
            }
            if (mouseX >= textField.getX() && mouseX <= textField.getX() + textField.getWidth() && mouseY >= textField.getY() && mouseY <= textField.getY() + textField.getHeight()) {
                return mouseClicked(mouseX, mouseY, button);
            }
            pickerOpen = false;
            return false;
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return pickerOpen;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            if (pickerOpen && isInsidePicker(mouseX, mouseY)) {
                List<String> suggestions = getSuggestions();
                int visibleRows = Math.min(7, suggestions.size());
                int maxOffset = Math.max(0, suggestions.size() - visibleRows);
                if (maxOffset > 0) {
                    pickerScrollOffset = Math.max(0, Math.min(maxOffset, pickerScrollOffset + (amount < 0 ? 1 : -1)));
                    UiSoundHelper.playDialClick();
                }
                return true;
            }
            return false;
        }

        private boolean isValidId() {
            Identifier identifier = Identifier.tryParse(textField.getText().trim());
            if (identifier == null) {
                return false;
            }
            return registryKind.isValid(identifier);
        }

        private ItemStack getPreviewStack() {
            Identifier identifier = Identifier.tryParse(textField.getText().trim());
            if (identifier == null) {
                return ItemStack.EMPTY;
            }
            if (registryKind == RegistryKind.ITEM) {
                if (!Registries.ITEM.containsId(identifier)) {
                    return ItemStack.EMPTY;
                }
                Item item = Registries.ITEM.get(identifier);
                return item == null ? ItemStack.EMPTY : new ItemStack(item);
            }
            if (registryKind != RegistryKind.BLOCK || !Registries.BLOCK.containsId(identifier)) {
                if (registryKind == RegistryKind.ENTITY && Registries.ENTITY_TYPE.containsId(identifier)) {
                    SpawnEggItem spawnEggItem = SpawnEggItem.forEntity(Registries.ENTITY_TYPE.get(identifier));
                    return spawnEggItem == null ? ItemStack.EMPTY : new ItemStack(spawnEggItem);
                }
                return ItemStack.EMPTY;
            }
            Item item = Registries.BLOCK.get(identifier).asItem();
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        }

        @Override
        public String getValidationError() {
            String raw = textField.getText().trim();
            if (raw.isEmpty()) {
                return registryKind.displayName.substring(0, 1).toUpperCase(Locale.ROOT) + registryKind.displayName.substring(1) + " id is empty";
            }
            Identifier identifier = Identifier.tryParse(raw);
            if (identifier == null) {
                return "Registry id must use namespace:path format";
            }
            if (!registryKind.isValid(identifier)) {
                return "Unknown " + registryKind.displayName + " id";
            }
            return null;
        }

        private boolean drawEffectIcon(DrawContext context) {
            Identifier identifier = Identifier.tryParse(textField.getText().trim());
            if (identifier == null || !Registries.STATUS_EFFECT.containsId(identifier)) {
                return false;
            }
            try {
                Sprite sprite = MinecraftClient.getInstance().getStatusEffectSpriteManager().getSprite(Registries.STATUS_EFFECT.get(identifier));
                context.drawSprite(iconX, iconY, 0, 16, 16, sprite);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private void drawPreviewTooltip(DrawContext context, int mouseX, int mouseY, ItemStack previewStack) {
            if (!previewStack.isEmpty() && (registryKind == RegistryKind.ITEM || registryKind == RegistryKind.BLOCK)) {
                context.drawItemTooltip(MinecraftClient.getInstance().textRenderer, previewStack, mouseX, mouseY);
                return;
            }
            List<Text> lines = new ArrayList<>();
            String raw = textField.getText().trim();
            Identifier identifier = Identifier.tryParse(raw);
            lines.add(Text.literal(formatLabel(label)).formatted(Formatting.WHITE));
            lines.add(Text.literal(raw.isEmpty() ? "(empty)" : raw).formatted(Formatting.DARK_GRAY));
            if (identifier != null && registryKind.isValid(identifier)) {
                switch (registryKind) {
                    case ITEM -> lines.add(Text.literal(Registries.ITEM.get(identifier).getName().getString()).formatted(Formatting.GRAY));
                    case BLOCK -> lines.add(Text.literal(Registries.BLOCK.get(identifier).getName().getString()).formatted(Formatting.GRAY));
                    case ENTITY -> {
                        EntityType<?> entityType = Registries.ENTITY_TYPE.get(identifier);
                        lines.clear();
                        appendEntityTooltipLines(lines, identifier, entityType);
                    }
                    case EFFECT -> {
                        appendEffectTooltipLines(lines, identifier);
                    }
                    case PARTICLE -> {
                        appendParticleTooltipLines(lines, identifier);
                    }
                    case SOUND -> {
                        lines.add(Text.literal("Sound event").formatted(Formatting.GRAY));
                        lines.add(Text.literal("Preview icon plays this sound").formatted(Formatting.BLUE));
                    }
                }
            } else {
                lines.add(Text.literal("Not found in registry").formatted(Formatting.RED));
            }
            context.drawTooltip(MinecraftClient.getInstance().textRenderer, lines, Optional.empty(), mouseX, mouseY);
        }

        private void appendEntityTooltipLines(List<Text> lines, Identifier identifier, EntityType<?> entityType) {
            String modName = FabricLoader.getInstance().getModContainer(identifier.getNamespace())
                    .map(container -> container.getMetadata().getName())
                    .orElse(identifier.getNamespace());
            lines.add(entityType.getName().copy().formatted(Formatting.WHITE));
            lines.add(Text.literal(identifier.toString()).formatted(Formatting.DARK_GRAY));
            lines.add(Text.literal(modName).formatted(Formatting.BLUE));

            LivingEntity livingEntity = null;
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.world != null) {
                    var created = entityType.create(client.world);
                    if (created instanceof LivingEntity living) {
                        livingEntity = living;
                    }
                }
            } catch (Throwable ignored) {
            }

            if (livingEntity == null) {
                lines.add(nbtStatLine("spawn_group", entityType.getSpawnGroup().asString()));
                lines.add(nbtStatLine("width", formatTooltipNumber(entityType.getWidth())));
                lines.add(nbtStatLine("height", formatTooltipNumber(entityType.getHeight())));
                return;
            }

            lines.add(nbtStatLine("spawn_group", entityType.getSpawnGroup().asString()));
            lines.add(nbtStatLine("width", formatTooltipNumber(entityType.getWidth())));
            lines.add(nbtStatLine("height", formatTooltipNumber(entityType.getHeight())));
            appendEntityAttributeLine(lines, livingEntity, "max_health", EntityAttributes.GENERIC_MAX_HEALTH);
            appendEntityAttributeLine(lines, livingEntity, "armor", EntityAttributes.GENERIC_ARMOR);
            appendEntityAttributeLine(lines, livingEntity, "armor_toughness", EntityAttributes.GENERIC_ARMOR_TOUGHNESS);
            appendEntityAttributeLine(lines, livingEntity, "attack_damage", EntityAttributes.GENERIC_ATTACK_DAMAGE);
            appendEntityAttributeLine(lines, livingEntity, "attack_speed", EntityAttributes.GENERIC_ATTACK_SPEED);
            appendEntityAttributeLine(lines, livingEntity, "attack_knockback", EntityAttributes.GENERIC_ATTACK_KNOCKBACK);
            appendEntityAttributeLine(lines, livingEntity, "movement_speed", EntityAttributes.GENERIC_MOVEMENT_SPEED);
            appendEntityAttributeLine(lines, livingEntity, "flying_speed", EntityAttributes.GENERIC_FLYING_SPEED);
            appendEntityAttributeLine(lines, livingEntity, "follow_range", EntityAttributes.GENERIC_FOLLOW_RANGE);
            appendEntityAttributeLine(lines, livingEntity, "knockback_resistance", EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE);
            appendEntityAttributeLine(lines, livingEntity, "luck", EntityAttributes.GENERIC_LUCK);
            appendEntityAttributeLine(lines, livingEntity, "zombie_spawn_reinforcements", EntityAttributes.ZOMBIE_SPAWN_REINFORCEMENTS);
            appendEntityAttributeLine(lines, livingEntity, "horse_jump_strength", EntityAttributes.HORSE_JUMP_STRENGTH);
        }

        private void appendEntityAttributeLine(List<Text> lines, LivingEntity entity, String key, EntityAttribute attribute) {
            EntityAttributeInstance instance = entity.getAttributeInstance(attribute);
            if (instance == null) {
                return;
            }
            lines.add(nbtStatLine(key, formatTooltipNumber(instance.getValue())));
        }

        private void appendEffectTooltipLines(List<Text> lines, Identifier identifier) {
            var effect = Registries.STATUS_EFFECT.get(identifier);
            String modName = resolveModName(identifier);
            lines.clear();
            lines.add(effect.getName().copy().formatted(Formatting.WHITE));
            lines.add(Text.literal(identifier.toString()).formatted(Formatting.DARK_GRAY));
            lines.add(Text.literal(modName).formatted(Formatting.BLUE));
            lines.add(nbtStatLine("category", effect.getCategory().name().toLowerCase(Locale.ROOT)));
            lines.add(nbtStatLine("instant", String.valueOf(effect.isInstant())));
            lines.add(nbtStatLine("color", String.format(Locale.ROOT, "#%06X", effect.getColor() & 0xFFFFFF)));
            lines.add(nbtStatLine("translation_key", effect.getTranslationKey()));
        }

        private void appendParticleTooltipLines(List<Text> lines, Identifier identifier) {
            String modName = resolveModName(identifier);
            List<String> particleTextures = resolveParticleTextureCandidates(identifier);
            lines.clear();
            lines.add(Text.literal(formatParticleName(identifier)).formatted(Formatting.WHITE));
            lines.add(Text.literal(identifier.toString()).formatted(Formatting.DARK_GRAY));
            lines.add(Text.literal(modName).formatted(Formatting.BLUE));
            lines.add(nbtStatLine("type", Registries.PARTICLE_TYPE.get(identifier).getClass().getSimpleName()));
            lines.add(nbtStatLine("texture_count", String.valueOf(particleTextures.size())));
            if (!particleTextures.isEmpty()) {
                lines.add(nbtStatLine("texture", particleTextures.get(0)));
                lines.add(nbtStatLine("animated", String.valueOf(particleTextures.size() > 1)));
            } else {
                lines.add(nbtStatLine("texture", "unresolved"));
                lines.add(nbtStatLine("fallback", "true"));
            }
        }

        private String resolveModName(Identifier identifier) {
            return FabricLoader.getInstance().getModContainer(identifier.getNamespace())
                    .map(container -> container.getMetadata().getName())
                    .orElse(identifier.getNamespace());
        }

        private Text nbtStatLine(String key, String value) {
            return Text.literal(key).formatted(Formatting.GRAY)
                    .append(Text.literal(": ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(value).formatted(Formatting.GREEN));
        }

        private String formatTooltipNumber(double value) {
            if (Math.abs(value - Math.rint(value)) < 0.0001) {
                return String.format(Locale.ROOT, "%.0f", value);
            }
            return String.format(Locale.ROOT, "%.3f", value);
        }

        private void drawSuggestionGlyph(DrawContext context, int x, int y, String suggestion) {
            Identifier identifier = Identifier.tryParse(suggestion);
            if (identifier == null) {
                return;
            }
            ItemStack stack = getPreviewStack(identifier);
            context.fill(x, y, x + 16, y + 16, withAlpha(uiColorHeader, 118));
            context.drawBorder(x, y, 16, 16, new Color(uiColorBackgroundBorder, true).getRGB());
            if (!stack.isEmpty()) {
                context.drawItem(stack, x, y);
                return;
            }
            if (registryKind == RegistryKind.EFFECT) {
                try {
                    Sprite sprite = MinecraftClient.getInstance().getStatusEffectSpriteManager().getSprite(Registries.STATUS_EFFECT.get(identifier));
                    context.drawSprite(x, y, 0, 16, 16, sprite);
                    return;
                } catch (Throwable ignored) {
                }
            }
            if (registryKind == RegistryKind.PARTICLE) {
                drawParticlePreview(context, x, y, identifier);
                return;
            }
            String abbrev = registryKind.abbrev;
            int textX = x + Math.max(0, (16 - MinecraftClient.getInstance().textRenderer.getWidth(abbrev)) / 2);
            context.drawText(MinecraftClient.getInstance().textRenderer, abbrev, textX, y + 4, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        }

        private ItemStack getPreviewStack(Identifier identifier) {
            if (identifier == null) {
                return ItemStack.EMPTY;
            }
            return switch (registryKind) {
                case ITEM -> Registries.ITEM.containsId(identifier) ? new ItemStack(Registries.ITEM.get(identifier)) : ItemStack.EMPTY;
                case BLOCK -> Registries.BLOCK.containsId(identifier) ? new ItemStack(Registries.BLOCK.get(identifier).asItem()) : ItemStack.EMPTY;
                case ENTITY -> {
                    if (!Registries.ENTITY_TYPE.containsId(identifier)) {
                        yield ItemStack.EMPTY;
                    }
                    SpawnEggItem spawnEggItem = SpawnEggItem.forEntity(Registries.ENTITY_TYPE.get(identifier));
                    yield spawnEggItem == null ? ItemStack.EMPTY : new ItemStack(spawnEggItem);
                }
                default -> ItemStack.EMPTY;
            };
        }

        private boolean isInsidePicker(double mouseX, double mouseY) {
            return pickerOpen && mouseX >= pickerX && mouseX <= pickerX + pickerWidth && mouseY >= pickerY && mouseY <= pickerY + pickerHeight;
        }

        private void drawParticlePreview(DrawContext context, int x, int y, Identifier identifier) {
            Identifier particleTexture = resolveParticleTexture(identifier);
            if (particleTexture != null) {
                context.drawTexture(particleTexture, x, y, 0, 0, 16, 16, 16, 16);
                return;
            }
            long tick = Util.getMeasuringTimeMs() / 140L;
            int[] colors = {new Color(uiColorConfigSparkGold, true).getRGB(), new Color(uiColorConfigSparkBlue, true).getRGB(), new Color(uiColorConfigSparkCream, true).getRGB(), new Color(uiColorConfigSparkGreen, true).getRGB()};
            for (int i = 0; i < 4; i++) {
                int px = x + 3 + (int) ((tick + i * 13L) % 10L);
                int py = y + 2 + (int) (((tick * 3L) + i * 7L) % 10L);
                context.fill(px, py, px + 2, py + 2, colors[i % colors.length]);
            }
        }

        private Identifier resolveParticleTexture(Identifier identifier) {
            if (identifier == null) {
                return null;
            }
            List<String> candidates = resolveParticleTextureCandidates(identifier);
            if (!candidates.isEmpty()) {
                return parseParticleTextureReference(identifier, candidates.get((int) ((Util.getMeasuringTimeMs() / 180L) % candidates.size())));
            }
            return null;
        }

        private List<String> resolveParticleTextureCandidates(Identifier identifier) {
            List<String> textures = new ArrayList<>();
            MinecraftClient client = MinecraftClient.getInstance();
            Identifier direct = new Identifier(identifier.getNamespace(), "textures/particle/" + identifier.getPath() + ".png");
            if (client.getResourceManager().getResource(direct).isPresent()) {
                textures.add(identifier.toString());
            }
            Identifier namespaced = new Identifier(identifier.getNamespace(), "textures/particle/" + identifier.getNamespace() + "/" + identifier.getPath() + ".png");
            if (client.getResourceManager().getResource(namespaced).isPresent()) {
                textures.add(identifier.getNamespace() + ":" + identifier.getNamespace() + "/" + identifier.getPath());
            }
            Identifier particleDefinition = new Identifier(identifier.getNamespace(), "particles/" + identifier.getPath() + ".json");
            client.getResourceManager().getResource(particleDefinition).ifPresent(resource -> {
                try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed.isJsonObject()) {
                        JsonArray array = parsed.getAsJsonObject().getAsJsonArray("textures");
                        if (array != null) {
                            for (JsonElement element : array) {
                                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                                    String texture = element.getAsString();
                                    if (!textures.contains(texture)) {
                                        textures.add(texture);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            return textures;
        }

        private Identifier parseParticleTextureReference(Identifier particleId, String textureRef) {
            if (textureRef == null || textureRef.isBlank()) {
                return null;
            }
            Identifier base = Identifier.tryParse(textureRef.contains(":") ? textureRef : particleId.getNamespace() + ":" + textureRef);
            if (base == null) {
                return null;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            Identifier direct = new Identifier(base.getNamespace(), "textures/particle/" + base.getPath() + ".png");
            if (client.getResourceManager().getResource(direct).isPresent()) {
                return direct;
            }
            Identifier numbered = new Identifier(base.getNamespace(), "textures/particle/" + base.getPath() + "_0.png");
            if (client.getResourceManager().getResource(numbered).isPresent()) {
                return numbered;
            }
            return null;
        }

        private String formatParticleName(Identifier identifier) {
            String path = identifier.getPath().replace('/', ' ').replace('_', ' ').replace('-', ' ');
            path = path.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
            if (path.isBlank()) {
                return identifier.toString();
            }
            return Character.toUpperCase(path.charAt(0)) + path.substring(1);
        }

        private void playPreviewSound() {
            Identifier identifier = Identifier.tryParse(textField.getText().trim());
            if (identifier == null || !Registries.SOUND_EVENT.containsId(identifier)) {
                return;
            }
            SoundEvent sound = Registries.SOUND_EVENT.get(identifier);
            if (sound == null) {
                return;
            }
            stopPreviewSound();
            PositionedSoundInstance instance = PositionedSoundInstance.master(sound, 1.0F);
            MinecraftClient.getInstance().getSoundManager().play(instance);
            activeRegistryPreviewSound = instance;
            activeRegistryPreviewSoundId = identifier;
            UiSoundHelper.playButtonClick();
        }

        private void stopPreviewSound() {
            if (activeRegistryPreviewSound != null) {
                MinecraftClient.getInstance().getSoundManager().stop(activeRegistryPreviewSound);
            }
            activeRegistryPreviewSound = null;
            activeRegistryPreviewSoundId = null;
            UiSoundHelper.playButtonClick();
        }

        private void syncPreviewSoundState() {
            if (activeRegistryPreviewSound == null) {
                activeRegistryPreviewSoundId = null;
                return;
            }
            if (!MinecraftClient.getInstance().getSoundManager().isPlaying(activeRegistryPreviewSound)) {
                activeRegistryPreviewSound = null;
                activeRegistryPreviewSoundId = null;
            }
        }

        private boolean isPreviewSoundActive() {
            syncPreviewSoundState();
            Identifier identifier = Identifier.tryParse(textField.getText().trim());
            return registryKind == RegistryKind.SOUND
                    && identifier != null
                    && identifier.equals(activeRegistryPreviewSoundId)
                    && activeRegistryPreviewSound != null;
        }

        private List<String> getSuggestions() {
            String query = textField.getText().trim().toLowerCase(Locale.ROOT);
            if (query.isEmpty()) {
                return List.of();
            }
            List<String> suggestions = new ArrayList<>();
            Iterable<Identifier> ids = switch (registryKind) {
                case ITEM -> Registries.ITEM.getIds();
                case BLOCK -> Registries.BLOCK.getIds();
                case ENTITY -> Registries.ENTITY_TYPE.getIds();
                case EFFECT -> Registries.STATUS_EFFECT.getIds();
                case PARTICLE -> Registries.PARTICLE_TYPE.getIds();
                case SOUND -> Registries.SOUND_EVENT.getIds();
            };
            for (Identifier id : ids) {
                String value = id.toString();
                if (value.contains(query)) {
                    suggestions.add(value);
                }
            }
            suggestions.sort(Comparator.naturalOrder());
            return suggestions;
        }

        private enum RegistryKind {
            ITEM("item", "--"),
            BLOCK("block", "--"),
            ENTITY("entity", "--"),
            EFFECT("effect", "--"),
            PARTICLE("particle", "--"),
            SOUND("sound", "--");

            private final String displayName;
            private final String abbrev;

            RegistryKind(String displayName, String abbrev) {
                this.displayName = displayName;
                this.abbrev = abbrev;
            }

            private static RegistryKind fromPath(String path) {
                String normalized = path.toLowerCase(Locale.ROOT);
                if (normalized.contains("entity")) {
                    return ENTITY;
                }
                if (normalized.contains("effect")) {
                    return EFFECT;
                }
                if (normalized.contains("particle")) {
                    return PARTICLE;
                }
                if (normalized.contains("sound")) {
                    return SOUND;
                }
                if (normalized.contains("block")) {
                    return BLOCK;
                }
                return ITEM;
            }

            private boolean isValid(Identifier identifier) {
                return switch (this) {
                    case ITEM -> Registries.ITEM.containsId(identifier);
                    case BLOCK -> Registries.BLOCK.containsId(identifier);
                    case ENTITY -> Registries.ENTITY_TYPE.containsId(identifier);
                    case EFFECT -> Registries.STATUS_EFFECT.containsId(identifier);
                    case PARTICLE -> Registries.PARTICLE_TYPE.containsId(identifier);
                    case SOUND -> Registries.SOUND_EVENT.containsId(identifier);
                };
            }
        }
    }

    private static class VersionEntry extends ConfigEntry {
        private final String label;
        private final TextFieldWidget textField;

        private VersionEntry(String label, String initialValue, StringConsumer onChange) {
            this.label = label;
            this.textField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 152, 18, Text.literal(label));
            this.textField.setText(initialValue);
            this.textField.setChangedListener(onChange::accept);
        }

        @Override
        public void tick() {
            textField.tick();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return textField.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return textField.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return textField.charTyped(chr, modifiers);
        }

        @Override
        public void setFocused(boolean focused) {
            textField.setFocused(focused);
        }

        @Override
        public boolean isFocused() {
            return textField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            int fieldWidth = Math.max(120, Math.min(168, entryWidth / 2));
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, "Version string", x + 18, y);
            textField.setWidth(fieldWidth);
            textField.setX(getCompactFieldX(x, entryWidth, fieldWidth + 50));
            textField.setY(getControlRowY(y, entryHeight));
            textField.render(context, mouseX, mouseY, delta);
            int badgeX = textField.getX() + fieldWidth + 6;
            context.fill(badgeX, y + 18, badgeX + 44, y + 36, withAlpha(uiColorHeader, 132));
            context.drawBorder(badgeX, y + 18, 44, 18, new Color(uiColorBackgroundBorder, true).getRGB());
            context.drawText(MinecraftClient.getInstance().textRenderer, "Ver", badgeX + 10, y + 23, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            drawDeleteButton(context, x, y, entryWidth);
        }
    }

    private static class SoundConfigEntry extends ConfigEntry {
        private final String label;
        private final String soundPath;
        private final TextFieldWidget soundField;
        private final TextFieldWidget pitchField;
        private final TextFieldWidget volumeField;
        private final ComponentBinding pitchBinding;
        private final ComponentBinding volumeBinding;
        private final StringConsumer soundChange;
        private final NumericUpdateConsumer numericChange;
        private int iconX;
        private int iconY;
        private int iconSize;
        private int badgeX;
        private int badgeY;
        private int badgeWidth;
        private int pickerX;
        private int pickerY;
        private int pickerWidth;
        private int pickerHeight;
        private boolean pickerOpen;
        private int hoveredSuggestion = -1;
        private int pickerScrollOffset;

        private SoundConfigEntry(String label, String soundPath, String initialValue, ComponentBinding pitchBinding, ComponentBinding volumeBinding, StringConsumer soundChange, NumericUpdateConsumer numericChange) {
            this.label = label;
            this.soundPath = soundPath;
            this.pitchBinding = pitchBinding;
            this.volumeBinding = volumeBinding;
            this.soundChange = soundChange;
            this.numericChange = numericChange;
            this.soundField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 168, 18, Text.literal(label));
            this.soundField.setMaxLength(512);
            this.soundField.setText(initialValue);
            this.soundField.setChangedListener(raw -> {
                soundChange.accept(raw);
                pickerOpen = !raw.trim().isEmpty();
                hoveredSuggestion = -1;
            });
            this.pitchField = pitchBinding == null ? null : createNumericField(pitchBinding);
            this.volumeField = volumeBinding == null ? null : createNumericField(volumeBinding);
        }

        @Override
        public void tick() {
            soundField.tick();
            if (pitchField != null) {
                pitchField.tick();
            }
            if (volumeField != null) {
                volumeField.tick();
            }
            syncPreviewSoundState();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && isInsidePicker(mouseX, mouseY)) {
                List<String> suggestions = getSuggestions();
                if (hoveredSuggestion >= 0 && hoveredSuggestion < suggestions.size()) {
                    soundField.setText(suggestions.get(hoveredSuggestion));
                    pickerOpen = false;
                    UiSoundHelper.playButtonClick();
                    return true;
                }
            }
            if (button == 0
                    && mouseX >= soundField.getX() - 19
                    && mouseX <= soundField.getX() + 1
                    && mouseY >= soundField.getY() - 1
                    && mouseY <= soundField.getY() + 19) {
                if (isPreviewSoundActive()) {
                    stopPreviewSound();
                } else {
                    playPreviewSound();
                }
                return true;
            }

            boolean clickedField = focusClickedField(mouseX, mouseY, button, editableFields());
            if (clickedField) {
                pickerOpen = isMouseOverField(soundField, mouseX, mouseY) && !soundField.getText().trim().isEmpty();
                return true;
            }
            if (!isInsidePicker(mouseX, mouseY)) {
                pickerOpen = false;
            }
            return false;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (soundField.keyPressed(keyCode, scanCode, modifiers)) {
                pickerOpen = !soundField.getText().trim().isEmpty();
                hoveredSuggestion = -1;
                return true;
            }
            if (pitchField != null && pitchField.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            return volumeField != null && volumeField.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            if (soundField.charTyped(chr, modifiers)) {
                pickerOpen = !soundField.getText().trim().isEmpty();
                hoveredSuggestion = -1;
                return true;
            }
            if (pitchField != null && pitchField.charTyped(chr, modifiers)) {
                return true;
            }
            return volumeField != null && volumeField.charTyped(chr, modifiers);
        }

        @Override
        public void setFocused(boolean focused) {
            soundField.setFocused(focused && soundField.isFocused());
            if (pitchField != null) {
                pitchField.setFocused(focused && pitchField.isFocused());
            }
            if (volumeField != null) {
                volumeField.setFocused(focused && volumeField.isFocused());
            }
            if (!focused) {
                pickerOpen = false;
                hoveredSuggestion = -1;
            }
        }

        @Override
        public boolean isFocused() {
            return soundField.isFocused()
                    || (pitchField != null && pitchField.isFocused())
                    || (volumeField != null && volumeField.isFocused());
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return pickerOpen || pitchField != null || volumeField != null;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            if (pickerOpen && isInsidePicker(mouseX, mouseY)) {
                List<String> suggestions = getSuggestions();
                int visibleRows = Math.min(7, suggestions.size());
                int maxOffset = Math.max(0, suggestions.size() - visibleRows);
                if (maxOffset > 0) {
                    pickerScrollOffset = Math.max(0, Math.min(maxOffset, pickerScrollOffset + (amount < 0 ? 1 : -1)));
                    UiSoundHelper.playDialClick();
                }
                return true;
            }
            if (pitchField != null && isMouseOverField(pitchField, mouseX, mouseY)) {
                boolean changed = nudgeNumericTextField(pitchField, amount > 0 ? 0.05 : -0.05, false);
                if (changed) {
                    UiSoundHelper.playDialClick();
                }
                return changed;
            }
            if (volumeField != null && isMouseOverField(volumeField, mouseX, mouseY)) {
                boolean changed = nudgeNumericTextField(volumeField, amount > 0 ? 0.05 : -0.05, false);
                if (changed) {
                    UiSoundHelper.playDialClick();
                }
                return changed;
            }
            return false;
        }

        @Override
        public List<String> getDeleteTargets() {
            List<String> targets = new ArrayList<>();
            targets.add(soundPath);
            if (pitchBinding != null) {
                targets.add(pitchBinding.path());
            }
            if (volumeBinding != null) {
                targets.add(volumeBinding.path());
            }
            return targets;
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, "Sound id with live preview tuning", x + 18, y);
            int soundWidth = 172;
            int numericWidth = 50;
            int previewBoxWidth = 20;
            int groupWidth = previewBoxWidth - 1 + soundWidth + badgeWidth() + 12 + (pitchField != null ? numericWidth + 16 : 0) + (volumeField != null ? numericWidth + 16 : 0);
            int startX = getPinnedFieldStartX(x, entryWidth, groupWidth);
            int controlY = getControlRowY(y, entryHeight);
            iconSize = 16;
            iconY = controlY + 1;
            iconX = drawAttachedPreviewBox(context, startX, controlY, iconSize, mouseX >= startX - previewBoxWidth && mouseX <= startX + 1 && mouseY >= controlY - 1 && mouseY <= controlY + 19);
            if (!isPreviewSoundActive()) {
                context.drawTexture(loadExternalPngTexture(uiImageDirectory, "play.png"), iconX, iconY, 0, 0, 16, 16, 16, 16);
            } else {
                context.drawTexture(loadExternalPngTexture(uiImageDirectory, "stop.png"), iconX, iconY, 0, 0, 16, 16, 16, 16);
            }

            soundField.setWidth(soundWidth);
            soundField.setX(startX);
            soundField.setY(controlY);
            soundField.render(context, mouseX, mouseY, delta);

            badgeX = soundField.getX() + soundWidth;
            badgeY = controlY;
            badgeWidth = badgeWidth();
            boolean valid = isValidId();
            int badgeColor = valid ? new Color(uiColorConfigBooleanTrue, true).getRGB() : withAlpha(uiColorWarningPromptText, 140);
            context.fill(badgeX, badgeY - 1, badgeX + badgeWidth, badgeY + 19, badgeColor);
            context.drawBorder(badgeX, badgeY - 1, badgeWidth, 20, new Color(uiColorBackgroundBorder, true).getRGB());
            String badgeLabel = valid ? "✓" : "✕";
            int badgeTextX = badgeX + Math.max(0, (badgeWidth - MinecraftClient.getInstance().textRenderer.getWidth(badgeLabel)) / 2);
            context.drawText(MinecraftClient.getInstance().textRenderer, badgeLabel, badgeTextX, controlY + 4, new Color(uiColorContentBaseTitleText, true).getRGB(), false);

            int currentX = badgeX + badgeWidth + 12;
            if (volumeField != null) {
                if (showFieldCaptions()) {
                    context.drawText(MinecraftClient.getInstance().textRenderer, "VO", currentX, getFieldCaptionY(controlY), new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                }
                volumeField.setWidth(numericWidth);
                volumeField.setX(currentX);
                volumeField.setY(controlY);
                volumeField.render(context, mouseX, mouseY, delta);
                currentX += numericWidth + 12;
            }
            if (pitchField != null) {
                if (showFieldCaptions()) {
                    context.drawText(MinecraftClient.getInstance().textRenderer, "PT", currentX, getFieldCaptionY(controlY), new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                }
                pitchField.setWidth(numericWidth);
                pitchField.setX(currentX);
                pitchField.setY(controlY);
                pitchField.render(context, mouseX, mouseY, delta);
            }

            if (mouseX >= startX - previewBoxWidth && mouseX <= startX + 1 && mouseY >= controlY - 1 && mouseY <= controlY + 19) {
                drawPreviewTooltip(context, mouseX, mouseY);
            }
            if (mouseX >= badgeX && mouseX <= badgeX + badgeWidth && mouseY >= badgeY - 1 && mouseY <= badgeY + 19) {
                context.drawTooltip(MinecraftClient.getInstance().textRenderer, Text.literal(valid ? "Valid sound id" : getValidationError()), mouseX, mouseY);
            }
            drawDeleteButton(context, x, y, entryWidth);
        }

        @Override
        public void renderOverlay(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, float delta) {
            if (!pickerOpen) {
                return;
            }
            List<String> suggestions = getSuggestions();
            if (suggestions.isEmpty()) {
                pickerOpen = false;
                return;
            }
            pickerWidth = Math.max(190, soundField.getWidth() + 24);
            int visibleRows = Math.min(7, suggestions.size());
            pickerHeight = 8 + visibleRows * 18;
            pickerX = Math.max(8, Math.min(soundField.getX(), MinecraftClient.getInstance().currentScreen.width - pickerWidth - 8));
            pickerY = Math.min(MinecraftClient.getInstance().currentScreen.height - pickerHeight - 8, soundField.getY() + 22);
            hoveredSuggestion = -1;
            context.getMatrices().push();
            context.getMatrices().translate(0.0F, 0.0F, OVERLAY_Z);
            context.fill(pickerX, pickerY, pickerX + pickerWidth, pickerY + pickerHeight, new Color(uiColorConfigPopupOverlay, true).getRGB());
            context.drawBorder(pickerX, pickerY, pickerWidth, pickerHeight, new Color(uiColorConfigPopupBorder, true).getRGB());
            int maxOffset = Math.max(0, suggestions.size() - visibleRows);
            pickerScrollOffset = Math.max(0, Math.min(maxOffset, pickerScrollOffset));
            for (int i = 0; i < visibleRows; i++) {
                int suggestionIndex = i + pickerScrollOffset;
                if (suggestionIndex >= suggestions.size()) {
                    break;
                }
                int rowY = pickerY + 4 + i * 18;
                boolean rowHovered = mouseX >= pickerX + 2 && mouseX <= pickerX + pickerWidth - 2 && mouseY >= rowY && mouseY <= rowY + 16;
                if (rowHovered) {
                    hoveredSuggestion = suggestionIndex;
                    context.fill(pickerX + 2, rowY, pickerX + pickerWidth - 2, rowY + 16, new Color(uiColorConfigPickerSelected, true).getRGB());
                }
                String suggestion = suggestions.get(suggestionIndex);
                context.drawTexture(loadExternalPngTexture(uiImageDirectory, "play.png"), pickerX + 5, rowY + 1, 0, 0, 16, 16, 16, 16);
                context.drawText(MinecraftClient.getInstance().textRenderer, suggestion, pickerX + 24, rowY + 4, new Color(uiColorConfigPickerText, true).getRGB(), false);
            }
            context.getMatrices().pop();
        }

        @Override
        public boolean hasModalOverlay() {
            return pickerOpen;
        }

        @Override
        public boolean handleOverlayClick(double mouseX, double mouseY, int button) {
            if (!pickerOpen) {
                return false;
            }
            if (isInsidePicker(mouseX, mouseY)) {
                return mouseClicked(mouseX, mouseY, button);
            }
            if (isMouseOverField(soundField, mouseX, mouseY)) {
                return mouseClicked(mouseX, mouseY, button);
            }
            pickerOpen = false;
            return false;
        }

        @Override
        public String getValidationError() {
            String raw = soundField.getText().trim();
            if (raw.isEmpty()) {
                return "Sound id is empty";
            }
            Identifier identifier = Identifier.tryParse(raw);
            if (identifier == null) {
                return "Registry id must use namespace:path format";
            }
            if (!Registries.SOUND_EVENT.containsId(identifier)) {
                return "Unknown sound id";
            }
            return null;
        }

        private TextFieldWidget createNumericField(ComponentBinding binding) {
            TextFieldWidget field = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 50, 18, Text.literal(binding.label()));
            field.setText(String.format(Locale.ROOT, "%.2f", binding.value()));
            field.setChangedListener(raw -> {
                try {
                    numericChange.accept(binding.path(), Double.parseDouble(raw), false);
                } catch (NumberFormatException ignored) {
                }
            });
            return field;
        }

        private TextFieldWidget[] editableFields() {
            List<TextFieldWidget> fields = new ArrayList<>();
            fields.add(soundField);
            if (pitchField != null) {
                fields.add(pitchField);
            }
            if (volumeField != null) {
                fields.add(volumeField);
            }
            return fields.toArray(new TextFieldWidget[0]);
        }

        private int badgeWidth() {
            return 20;
        }

        private boolean isValidId() {
            Identifier identifier = Identifier.tryParse(soundField.getText().trim());
            return identifier != null && Registries.SOUND_EVENT.containsId(identifier);
        }

        private boolean isInsidePicker(double mouseX, double mouseY) {
            return pickerOpen && mouseX >= pickerX && mouseX <= pickerX + pickerWidth && mouseY >= pickerY && mouseY <= pickerY + pickerHeight;
        }

        private List<String> getSuggestions() {
            String query = soundField.getText().trim().toLowerCase(Locale.ROOT);
            if (query.isEmpty()) {
                return List.of();
            }
            List<String> suggestions = new ArrayList<>();
            for (Identifier id : Registries.SOUND_EVENT.getIds()) {
                String value = id.toString();
                if (value.contains(query)) {
                    suggestions.add(value);
                }
            }
            suggestions.sort(Comparator.naturalOrder());
            return suggestions;
        }

        private void playPreviewSound() {
            Identifier identifier = Identifier.tryParse(soundField.getText().trim());
            if (identifier == null || !Registries.SOUND_EVENT.containsId(identifier)) {
                return;
            }
            SoundEvent sound = Registries.SOUND_EVENT.get(identifier);
            if (sound == null) {
                return;
            }
            stopPreviewSound(false);
            float volume = volumeField == null ? 1.0F : parseFloat(volumeField, 1.0F);
            float pitch = pitchField == null ? 1.0F : parseFloat(pitchField, 1.0F);
            PositionedSoundInstance instance = PositionedSoundInstance.master(sound, pitch, volume);
            MinecraftClient.getInstance().getSoundManager().play(instance);
            activeRegistryPreviewSound = instance;
            activeRegistryPreviewSoundId = identifier;
            UiSoundHelper.playButtonClick();
        }

        private void stopPreviewSound() {
            stopPreviewSound(true);
        }

        private void stopPreviewSound(boolean playClick) {
            if (activeRegistryPreviewSound != null) {
                MinecraftClient.getInstance().getSoundManager().stop(activeRegistryPreviewSound);
            }
            activeRegistryPreviewSound = null;
            activeRegistryPreviewSoundId = null;
            if (playClick) {
                UiSoundHelper.playButtonClick();
            }
        }

        private void syncPreviewSoundState() {
            if (activeRegistryPreviewSound == null) {
                activeRegistryPreviewSoundId = null;
                return;
            }
            if (!MinecraftClient.getInstance().getSoundManager().isPlaying(activeRegistryPreviewSound)) {
                activeRegistryPreviewSound = null;
                activeRegistryPreviewSoundId = null;
            }
        }

        private boolean isPreviewSoundActive() {
            syncPreviewSoundState();
            Identifier identifier = Identifier.tryParse(soundField.getText().trim());
            return identifier != null
                    && identifier.equals(activeRegistryPreviewSoundId)
                    && activeRegistryPreviewSound != null;
        }

        private float parseFloat(TextFieldWidget field, float fallback) {
            try {
                return (float) Double.parseDouble(field.getText().trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private void drawPreviewTooltip(DrawContext context, int mouseX, int mouseY) {
            List<Text> lines = new ArrayList<>();
            String raw = soundField.getText().trim();
            Identifier identifier = Identifier.tryParse(raw);
            lines.add(Text.literal(formatLabel(label)).formatted(Formatting.WHITE));
            lines.add(Text.literal(raw.isEmpty() ? "(empty)" : raw).formatted(Formatting.DARK_GRAY));
            if (identifier != null && Registries.SOUND_EVENT.containsId(identifier)) {
                lines.add(Text.literal("Volume: " + String.format(Locale.ROOT, "%.2f", volumeField == null ? 1.0 : parseFloat(volumeField, 1.0F))).formatted(Formatting.GRAY));
                lines.add(Text.literal("Pitch: " + String.format(Locale.ROOT, "%.2f", pitchField == null ? 1.0 : parseFloat(pitchField, 1.0F))).formatted(Formatting.GRAY));
                lines.add(Text.literal(isPreviewSoundActive() ? "Click to stop preview" : "Click to preview sound").formatted(Formatting.BLUE));
            } else {
                lines.add(Text.literal("Not found in sound registry").formatted(Formatting.RED));
            }
            context.drawTooltip(MinecraftClient.getInstance().textRenderer, lines, Optional.empty(), mouseX, mouseY);
        }
    }

    private static class KeybindEntry extends ConfigEntry {
        private final String label;
        private String value;
        private final StringConsumer onChange;
        private boolean captureMode;
        private int captureButtonX;
        private int buttonY;

        private KeybindEntry(String label, String initialValue, StringConsumer onChange) {
            this.label = label;
            this.value = initialValue;
            this.onChange = onChange;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isControlButtonHit(mouseX, mouseY, captureButtonX, buttonY, 52)) {
                captureMode = !captureMode;
                UiSoundHelper.playButtonClick();
                return true;
            }
            return false;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (!captureMode && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE)) {
                captureMode = true;
                return true;
            }
            if (!captureMode) {
                return false;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                captureMode = false;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                value = "";
                captureMode = false;
                onChange.accept(value);
                return true;
            }
            value = formatKeyName(keyCode, modifiers);
            captureMode = false;
            onChange.accept(value);
            return true;
        }

        @Override
        public void setFocused(boolean focused) {
            if (!focused) {
                captureMode = false;
            }
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, captureMode ? "Press a key combination" : "Keybind capture", x + 18, y);
            int fieldWidth = 112;
            int fieldX = getCompactFieldX(x, entryWidth, fieldWidth + 58);
            context.fill(fieldX, y + 18, fieldX + fieldWidth, y + 36, withAlpha(uiColorHeader, 110));
            context.drawBorder(fieldX, y + 18, fieldWidth, 18, new Color(uiColorBackgroundBorder, true).getRGB());
            context.drawText(MinecraftClient.getInstance().textRenderer, fitInline(value, fieldWidth - 8), fieldX + 4, y + 23, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
            captureButtonX = fieldX + fieldWidth + 6;
            buttonY = y + 18;
            drawControlButton(context, captureButtonX, buttonY, 52, captureMode ? "..." : "Set", captureMode);
            drawDeleteButton(context, x, y, entryWidth);
        }

        private String fitInline(String text, int maxWidth) {
            TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
            if (renderer.getWidth(text) <= maxWidth) {
                return text;
            }
            String ellipsis = "...";
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                String next = builder.toString() + text.charAt(i);
                if (renderer.getWidth(next + ellipsis) > maxWidth) {
                    break;
                }
                builder.append(text.charAt(i));
            }
            return builder + ellipsis;
        }
    }

    private static class ToggleValueEntry extends ConfigEntry {
        private final String label;
        private final String togglePath;
        private final String valuePath;
        private boolean enabled;
        private final TextFieldWidget valueField;
        private final JsonValuePathConsumer onChange;
        private final boolean numericValue;

        private ToggleValueEntry(String label, String togglePath, String valuePath, boolean enabled, JsonElement value, JsonValuePathConsumer onChange) {
            this.label = label;
            this.togglePath = togglePath;
            this.valuePath = valuePath;
            this.enabled = enabled;
            this.onChange = onChange;
            this.numericValue = value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            this.valueField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 110, 18, Text.literal(valuePath));
            this.valueField.setText(value.getAsJsonPrimitive().getAsString());
            this.valueField.setChangedListener(raw -> {
                if (!this.enabled) {
                    return;
                }
                JsonElement next = numericValue ? tryParseNumber(raw) : new JsonPrimitive(raw);
                if (next != null) {
                    onChange.accept(valuePath, next);
                }
            });
        }

        @Override
        public void tick() {
            valueField.tick();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            int toggleX = valueField.getX() - 60;
            int toggleY = valueField.getY();
            int toggleWidth = 44;
            if (mouseX >= toggleX && mouseX <= toggleX + toggleWidth && mouseY >= toggleY && mouseY <= toggleY + 18) {
                enabled = !enabled;
                onChange.accept(togglePath, new JsonPrimitive(enabled));
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (!enabled) {
                return false;
            }
            return valueField.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
                enabled = !enabled;
                onChange.accept(togglePath, new JsonPrimitive(enabled));
                if (!enabled) {
                    valueField.setFocused(false);
                }
                UiSoundHelper.playButtonClick();
                return true;
            }
            return enabled && valueField.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return enabled && valueField.charTyped(chr, modifiers);
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return numericValue;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            if (!enabled || !numericValue || amount == 0 || !isMouseOverField(valueField, mouseX, mouseY)) {
                return false;
            }
            boolean changed = nudgeNumericTextField(valueField, amount > 0 ? 1.0 : -1.0, textLooksWholeNumber(valueField));
            if (changed) {
                UiSoundHelper.playDialClick();
            }
            return changed;
        }

        @Override
        public void setFocused(boolean focused) {
            valueField.setFocused(focused && enabled);
        }

        @Override
        public boolean isFocused() {
            return valueField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(togglePath, valuePath);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            int valueWidth = 92;
            int toggleX = getCompactFieldX(x, entryWidth, valueWidth + 56);
            int toggleY = getControlRowY(y, entryHeight);
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, enabled ? "Dependent value enabled" : "Dependent value disabled", x + 18, y);
            context.fill(toggleX, toggleY, toggleX + 44, toggleY + 18, enabled ? withAlpha(uiColorHeaderStripe, 220) : withAlpha(uiColorHeader, 120));
            context.drawBorder(toggleX, toggleY, 44, 18, new Color(uiColorBackgroundBorder, true).getRGB());
            int knobX = enabled ? toggleX + 28 : toggleX + 2;
            context.fill(knobX, toggleY + 2, knobX + 14, toggleY + 16, new Color(uiColorContentBaseTitleText, true).getRGB());
            valueField.setEditable(enabled);
            valueField.setWidth(valueWidth);
            valueField.setX(toggleX + 50);
            valueField.setY(toggleY);
            valueField.render(context, mouseX, mouseY, delta);
            drawDeleteButton(context, x, y, entryWidth);
        }

        private JsonElement tryParseNumber(String raw) {
            try {
                if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                    return new JsonPrimitive(Double.parseDouble(raw));
                }
                return new JsonPrimitive(Long.parseLong(raw));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private static class TimeEntry extends ConfigEntry {
        private final String label;
        private final TextFieldWidget valueField;
        private final JsonElementConsumer onChange;
        private TimeUnitMode displayUnit;
        private final TimeUnitMode baseUnit;
        private double rawValue;
        private int unitButtonWidth;
        private int unitButtonX;
        private int buttonY;

        private TimeEntry(String label, double initialValue, JsonElementConsumer onChange) {
            this.label = label;
            this.onChange = onChange;
            this.rawValue = initialValue;
            this.baseUnit = inferBaseUnit(label, initialValue);
            this.displayUnit = this.baseUnit;
            this.valueField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 74, 18, Text.literal(label));
            this.valueField.setText(formatDisplayValue());
            this.valueField.setChangedListener(raw -> {
                try {
                    double displayValue = Double.parseDouble(raw);
                    rawValue = convertToBase(displayValue, displayUnit, baseUnit);
                    onChange.accept(baseUnit.wholeNumber ? new JsonPrimitive((int) Math.round(rawValue)) : new JsonPrimitive(rawValue));
                } catch (NumberFormatException ignored) {
                }
            });
        }

        @Override
        public void tick() {
            valueField.tick();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isControlButtonHit(mouseX, mouseY, unitButtonX, buttonY, unitButtonWidth)) {
                displayUnit = displayUnit.next();
                valueField.setText(formatDisplayValue());
                UiSoundHelper.playButtonClick();
                return true;
            }
            return valueField.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return valueField.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return valueField.charTyped(chr, modifiers);
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            if (amount == 0 || !isMouseOverField(valueField, mouseX, mouseY)) {
                return false;
            }
            double step = displayUnit.wholeNumber ? 1.0 : 0.1;
            boolean changed = nudgeNumericTextField(valueField, amount > 0 ? step : -step, displayUnit.wholeNumber);
            if (changed) {
                UiSoundHelper.playDialClick();
            }
            return changed;
        }

        @Override
        public void setFocused(boolean focused) {
            valueField.setFocused(focused);
        }

        @Override
        public boolean isFocused() {
            return valueField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, "Time / duration control", x + 18, y);
            int valueWidth = 72;
            int buttonWidth = 36;
            valueField.setWidth(valueWidth);
            valueField.setX(getCompactFieldX(x, entryWidth, valueWidth + buttonWidth));
            valueField.setY(getControlRowY(y, entryHeight));
            valueField.render(context, mouseX, mouseY, delta);
            unitButtonX = valueField.getX() + valueField.getWidth();
            unitButtonWidth = buttonWidth;
            buttonY = getControlRowY(y, entryHeight);
            drawControlButton(context, unitButtonX, buttonY, unitButtonWidth, displayUnit.label, true);
            drawDeleteButton(context, x, y, entryWidth);
        }

        private String formatDisplayValue() {
            double displayValue = convertToDisplay(rawValue, baseUnit, displayUnit);
            return displayUnit.wholeNumber ? String.valueOf((int) Math.round(displayValue)) : String.format(Locale.ROOT, "%.2f", displayValue);
        }

        private TimeUnitMode inferBaseUnit(String path, double value) {
            String normalizedPath = path.toLowerCase(Locale.ROOT);
            if (normalizedPath.contains("tick")) {
                return TimeUnitMode.TICKS;
            }
            if (value >= 100.0 || normalizedPath.contains("timeout") || normalizedPath.contains("delay") || normalizedPath.contains("cooldown")) {
                return TimeUnitMode.MILLISECONDS;
            }
            return TimeUnitMode.SECONDS;
        }

        private double convertToDisplay(double rawValue, TimeUnitMode base, TimeUnitMode target) {
            double millis = switch (base) {
                case MILLISECONDS -> rawValue;
                case SECONDS -> rawValue * 1000.0;
                case MINUTES -> rawValue * 60000.0;
                case TICKS -> rawValue * 50.0;
            };
            return switch (target) {
                case MILLISECONDS -> millis;
                case SECONDS -> millis / 1000.0;
                case MINUTES -> millis / 60000.0;
                case TICKS -> millis / 50.0;
            };
        }

        private double convertToBase(double displayValue, TimeUnitMode display, TimeUnitMode base) {
            double millis = switch (display) {
                case MILLISECONDS -> displayValue;
                case SECONDS -> displayValue * 1000.0;
                case MINUTES -> displayValue * 60000.0;
                case TICKS -> displayValue * 50.0;
            };
            return switch (base) {
                case MILLISECONDS -> millis;
                case SECONDS -> millis / 1000.0;
                case MINUTES -> millis / 60000.0;
                case TICKS -> millis / 50.0;
            };
        }
    }

    private static class BoxEdgeEntry extends ConfigEntry {
        private final String label;
        private final List<ComponentBinding> bindings;
        private final List<TextFieldWidget> fields = new ArrayList<>();
        private final NumericUpdateConsumer onChange;
        private final boolean wholeNumber;
        private int openButtonX;
        private int presetButtonX;
        private int buttonY;
        private boolean editorOpen;
        private int popoverX;
        private int popoverY;
        private int popoverWidth;
        private int popoverHeight;
        private Integer requestedPopoverX;
        private Integer requestedPopoverY;
        private final int[][] edgeHandleBounds = new int[4][];
        private int draggingEdge = -1;

        private BoxEdgeEntry(String label, List<ComponentBinding> bindings, NumericUpdateConsumer onChange) {
            this.label = label;
            this.bindings = bindings;
            this.onChange = onChange;
            this.wholeNumber = bindings.stream().allMatch(binding -> Math.rint(binding.value()) == binding.value());
            for (ComponentBinding binding : bindings) {
                TextFieldWidget field = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 42, 18, Text.literal(binding.label()));
                field.setText(wholeNumber ? String.valueOf((int) Math.round(binding.value())) : String.format(Locale.ROOT, "%.2f", binding.value()));
                field.setChangedListener(raw -> {
                    try {
                        onChange.accept(binding.path(), Double.parseDouble(raw), wholeNumber);
                    } catch (NumberFormatException ignored) {
                    }
                });
                fields.add(field);
            }
        }

        @Override
        public void tick() {
            fields.forEach(TextFieldWidget::tick);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isControlButtonHit(mouseX, mouseY, openButtonX, buttonY, 44)) {
                requestedPopoverX = (int) Math.round(mouseX);
                requestedPopoverY = (int) Math.round(mouseY);
                editorOpen = !editorOpen;
                draggingEdge = -1;
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, presetButtonX, buttonY, 50)) {
                applyNextSpacingPreset();
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (editorOpen) {
                for (int edge = 0; edge < edgeHandleBounds.length; edge++) {
                    int[] bounds = edgeHandleBounds[edge];
                    if (bounds != null
                            && mouseX >= bounds[0]
                            && mouseX <= bounds[0] + bounds[2]
                            && mouseY >= bounds[1]
                            && mouseY <= bounds[1] + bounds[3]) {
                        draggingEdge = edge;
                        applyEdgeDrag(edge, mouseX, mouseY);
                        UiSoundHelper.playButtonClick();
                        return true;
                    }
                }
            }
            return focusClickedField(mouseX, mouseY, button, fields.toArray(new TextFieldWidget[0]));
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
                editorOpen = !editorOpen;
                draggingEdge = -1;
                UiSoundHelper.playButtonClick();
                return true;
            }
            for (TextFieldWidget field : fields) {
                if (field.keyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            for (TextFieldWidget field : fields) {
                if (field.charTyped(chr, modifiers)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            double step = wholeNumber ? 1.0 : 0.1;
            for (TextFieldWidget field : fields) {
                if (isMouseOverField(field, mouseX, mouseY)) {
                    boolean changed = nudgeNumericTextField(field, amount > 0 ? step : -step, wholeNumber);
                    if (changed) {
                        UiSoundHelper.playDialClick();
                    }
                    return changed;
                }
            }
            return false;
        }

        @Override
        public void setFocused(boolean focused) {
            if (!focused) {
                fields.forEach(field -> field.setFocused(false));
                editorOpen = false;
                draggingEdge = -1;
                requestedPopoverX = null;
                requestedPopoverY = null;
            }
        }

        @Override
        public boolean isFocused() {
            return fields.stream().anyMatch(TextFieldWidget::isFocused);
        }

        @Override
        public int getPreferredHeight(int defaultHeight) {
            return defaultHeight;
        }

        @Override
        public List<String> getDeleteTargets() {
            List<String> targets = new ArrayList<>();
            bindings.forEach(binding -> targets.add(binding.path()));
            return targets;
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, null, x + 18, y);
            int buttonWidth = 44;
            int presetWidth = 50;
            int buttonGap = 10;
            int fieldWidth = 38;
            int fieldGap = 6;
            int totalWidth = buttonWidth + 6 + presetWidth + buttonGap + fields.size() * fieldWidth + Math.max(0, fields.size() - 1) * fieldGap;
            int startX = getPinnedFieldStartX(x, entryWidth, totalWidth);
            openButtonX = startX;
            buttonY = getControlRowY(y, entryHeight);
            drawControlButton(context, openButtonX, buttonY, buttonWidth, "Edit", editorOpen);
            presetButtonX = startX + buttonWidth + 6;
            drawControlButton(context, presetButtonX, buttonY, presetWidth, "Preset", false);
            int currentX = presetButtonX + presetWidth + buttonGap;
            boolean showCaptions = showFieldCaptions();
            for (int i = 0; i < fields.size(); i++) {
                TextFieldWidget field = fields.get(i);
                if (showCaptions) {
                    context.drawText(MinecraftClient.getInstance().textRenderer, shortLabel(i), currentX, getFieldCaptionY(buttonY), new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                }
                field.setWidth(fieldWidth);
                field.setX(currentX);
                field.setY(buttonY);
                field.render(context, mouseX, mouseY, delta);
                currentX += fieldWidth + fieldGap;
            }
            drawDeleteButton(context, x, y, entryWidth);
        }

        @Override
        public boolean handleMouseDrag(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (button != 0 || draggingEdge < 0) {
                return false;
            }
            applyEdgeDrag(draggingEdge, mouseX, mouseY);
            return true;
        }

        @Override
        public boolean handleMouseRelease(double mouseX, double mouseY, int button) {
            boolean wasDragging = draggingEdge >= 0;
            draggingEdge = -1;
            return wasDragging;
        }

        @Override
        public void renderOverlay(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, float delta) {
            if (!editorOpen) {
                return;
            }
            context.getMatrices().push();
            context.getMatrices().translate(0.0F, 0.0F, OVERLAY_Z);
            drawSpacingPopover(context);
            context.getMatrices().pop();
        }

        @Override
        public boolean hasModalOverlay() {
            return editorOpen;
        }

        @Override
        public boolean openInlineEditor(double mouseX, double mouseY) {
            requestedPopoverX = Double.isNaN(mouseX) ? null : (int) Math.round(mouseX);
            requestedPopoverY = Double.isNaN(mouseY) ? null : (int) Math.round(mouseY);
            editorOpen = true;
            draggingEdge = -1;
            return true;
        }

        @Override
        public boolean handleOverlayClick(double mouseX, double mouseY, int button) {
            boolean inside = (mouseX >= popoverX && mouseX <= popoverX + popoverWidth && mouseY >= popoverY && mouseY <= popoverY + popoverHeight)
                    || isControlButtonHit(mouseX, mouseY, openButtonX, buttonY, 44);
            if (inside) {
                return mouseClicked(mouseX, mouseY, button) || (mouseX >= popoverX && mouseX <= popoverX + popoverWidth && mouseY >= popoverY && mouseY <= popoverY + popoverHeight);
            }
            editorOpen = false;
            draggingEdge = -1;
            requestedPopoverX = null;
            requestedPopoverY = null;
            return true;
        }

        private String shortLabel(int index) {
            return switch (index) {
                case 0 -> "T";
                case 1 -> "R";
                case 2 -> "B";
                case 3 -> "L";
                default -> "?";
            };
        }

        private void drawSpacingPopover(DrawContext context) {
            popoverWidth = 188;
            popoverHeight = 152;
            int desiredX = requestedPopoverX != null ? requestedPopoverX + 8 : openButtonX + 50;
            int desiredY = requestedPopoverY != null ? requestedPopoverY - 8 : buttonY - 2;
            int maxX = MinecraftClient.getInstance().currentScreen.width - popoverWidth - 8;
            int maxY = MinecraftClient.getInstance().currentScreen.height - popoverHeight - 8;
            popoverX = Math.max(8, Math.min(desiredX, maxX));
            popoverY = Math.max(8, Math.min(desiredY, maxY));
            context.fill(popoverX, popoverY, popoverX + popoverWidth, popoverY + popoverHeight, withAlpha(uiColorContentBase, 236));
            context.drawBorder(popoverX, popoverY, popoverWidth, popoverHeight, new Color(uiColorBackgroundBorder, true).getRGB());

            int outerX = popoverX + 30;
            int outerY = popoverY + 28;
            int outerWidth = 128;
            int outerHeight = 96;
            context.fill(outerX, outerY, outerX + outerWidth, outerY + outerHeight, withAlpha(uiColorHeader, 96));
            context.drawBorder(outerX, outerY, outerWidth, outerHeight, new Color(uiColorBackgroundBorder, true).getRGB());

            double top = getFieldValue(0);
            double right = getFieldValue(1);
            double bottom = getFieldValue(2);
            double left = getFieldValue(3);
            double maxValue = Math.max(1.0, Math.max(Math.max(top, right), Math.max(bottom, left)));
            int topInset = normalizeInset(top, maxValue);
            int rightInset = normalizeInset(right, maxValue);
            int bottomInset = normalizeInset(bottom, maxValue);
            int leftInset = normalizeInset(left, maxValue);

            int innerX = outerX + leftInset;
            int innerY = outerY + topInset;
            int innerWidth = Math.max(28, outerWidth - leftInset - rightInset);
            int innerHeight = Math.max(28, outerHeight - topInset - bottomInset);
            context.fill(innerX, innerY, innerX + innerWidth, innerY + innerHeight, withAlpha(uiColorHeaderStripe, 138));
            context.drawBorder(innerX, innerY, innerWidth, innerHeight, new Color(uiColorBackgroundBorder, true).getRGB());

            if (!isCompactConfigListingEnabledStatic()) {
                drawEdgeLabel(context, "T", formatEdgeValue(top), popoverX + popoverWidth / 2, popoverY + 7, EdgeLabelAnchor.TOP);
                drawEdgeLabel(context, "R", formatEdgeValue(right), popoverX + popoverWidth - 10, popoverY + popoverHeight / 2, EdgeLabelAnchor.RIGHT);
                drawEdgeLabel(context, "B", formatEdgeValue(bottom), popoverX + popoverWidth / 2, popoverY + popoverHeight - 20, EdgeLabelAnchor.BOTTOM);
                drawEdgeLabel(context, "L", formatEdgeValue(left), popoverX + 10, popoverY + popoverHeight / 2, EdgeLabelAnchor.LEFT);
            }

            edgeHandleBounds[0] = new int[]{outerX + outerWidth / 2 - 20, outerY, 40, Math.max(8, topInset)};
            edgeHandleBounds[1] = new int[]{outerX + outerWidth - Math.max(8, rightInset), outerY + outerHeight / 2 - 20, Math.max(8, rightInset), 40};
            edgeHandleBounds[2] = new int[]{outerX + outerWidth / 2 - 20, outerY + outerHeight - Math.max(8, bottomInset), 40, Math.max(8, bottomInset)};
            edgeHandleBounds[3] = new int[]{outerX, outerY + outerHeight / 2 - 20, Math.max(8, leftInset), 40};

            int accent = withAlpha(uiColorContentBaseTitleText, 124);
            int activeAccent = withAlpha(uiColorWarningPromptText, 178);
            drawEdgeIndicator(context, 0, outerX + 8, outerY + Math.max(0, topInset - 2), outerWidth - 16, 3, topInset > 0 ? accent : withAlpha(uiColorHeaderSubTitleText, 96));
            drawEdgeIndicator(context, 1, outerX + outerWidth - Math.max(0, rightInset - 2), outerY + 8, 3, outerHeight - 16, rightInset > 0 ? accent : withAlpha(uiColorHeaderSubTitleText, 96));
            drawEdgeIndicator(context, 2, outerX + 8, outerY + outerHeight - Math.max(0, bottomInset - 2), outerWidth - 16, 3, bottomInset > 0 ? accent : withAlpha(uiColorHeaderSubTitleText, 96));
            drawEdgeIndicator(context, 3, outerX + Math.max(0, leftInset - 2), outerY + 8, 3, outerHeight - 16, leftInset > 0 ? accent : withAlpha(uiColorHeaderSubTitleText, 96));
            if (draggingEdge >= 0) {
                int[] hit = edgeHandleBounds[draggingEdge];
                context.drawBorder(hit[0], hit[1], hit[2], hit[3], activeAccent);
            }
        }

        private void drawEdgeIndicator(DrawContext context, int edge, int x, int y, int width, int height, int color) {
            if (width <= 0 || height <= 0) {
                return;
            }
            int fill = draggingEdge == edge ? withAlpha(uiColorWarningPromptText, 178) : color;
            context.fill(x, y, x + width, y + height, fill);
        }

        private void drawEdgeLabel(DrawContext context, String shortLabel, String value, int centerX, int centerY, EdgeLabelAnchor anchor) {
            var renderer = MinecraftClient.getInstance().textRenderer;
            int shortWidth = renderer.getWidth(shortLabel);
            int valueWidth = renderer.getWidth(value);
            int drawX;
            int drawY;
            switch (anchor) {
                case TOP, BOTTOM -> {
                    drawX = centerX - Math.max(shortWidth, valueWidth) / 2;
                    drawY = centerY;
                }
                case LEFT -> {
                    drawX = centerX;
                    drawY = centerY - 8;
                }
                case RIGHT -> {
                    drawX = centerX - Math.max(shortWidth, valueWidth);
                    drawY = centerY - 8;
                }
                default -> {
                    drawX = centerX;
                    drawY = centerY;
                }
            }
            context.drawText(renderer, shortLabel, drawX, drawY, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            context.drawText(renderer, value, drawX, drawY + 9, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        }

        private int normalizeInset(double value, double maxValue) {
            double normalized = maxValue <= 0.0 ? 0.0 : value / maxValue;
            return Math.min(24, Math.max(0, (int) Math.round(normalized * 24.0)));
        }

        private String formatEdgeValue(double value) {
            return wholeNumber ? String.valueOf((int) Math.round(value)) : String.format(Locale.ROOT, "%.1f", value);
        }

        private double getFieldValue(int index) {
            try {
                return Double.parseDouble(fields.get(index).getText());
            } catch (NumberFormatException ignored) {
                return bindings.get(index).value();
            }
        }

        private void applyEdgeDrag(int edge, double mouseX, double mouseY) {
            int outerX = popoverX + 30;
            int outerY = popoverY + 28;
            int outerWidth = 128;
            int outerHeight = 96;
            double nextValue;
            if (edge == 0) {
                nextValue = ((mouseY - outerY) / Math.max(1.0, outerHeight)) * 64.0;
            } else if (edge == 1) {
                nextValue = ((outerX + outerWidth - mouseX) / Math.max(1.0, outerWidth)) * 64.0;
            } else if (edge == 2) {
                nextValue = ((outerY + outerHeight - mouseY) / Math.max(1.0, outerHeight)) * 64.0;
            } else {
                nextValue = ((mouseX - outerX) / Math.max(1.0, outerWidth)) * 64.0;
            }
            nextValue = Math.max(0.0, Math.min(64.0, nextValue));
            double step = wholeNumber ? 1.0 : (Screen.hasShiftDown() ? 0.1 : 0.5);
            nextValue = Math.round(nextValue / step) * step;
            TextFieldWidget field = fields.get(edge);
            field.setText(wholeNumber ? String.valueOf((int) Math.round(nextValue)) : String.format(Locale.ROOT, "%.1f", nextValue));
        }

        private void applyNextSpacingPreset() {
            double[][] presets = {
                    {0.0, 0.0, 0.0, 0.0},
                    {8.0, 8.0, 8.0, 8.0},
                    {12.0, 12.0, 12.0, 12.0},
                    {24.0, 24.0, 24.0, 24.0},
                    {8.0, 16.0, 8.0, 16.0},
                    {16.0, 8.0, 16.0, 8.0}
            };
            double[] current = new double[fields.size()];
            for (int i = 0; i < fields.size(); i++) {
                current[i] = getFieldValue(i);
            }
            int nextIndex = 0;
            for (int i = 0; i < presets.length; i++) {
                if (matchesSpacingPreset(current, presets[i])) {
                    nextIndex = (i + 1) % presets.length;
                    break;
                }
            }
            double[] preset = presets[nextIndex];
            for (int i = 0; i < preset.length && i < fields.size(); i++) {
                fields.get(i).setText(wholeNumber ? String.valueOf((int) Math.round(preset[i])) : String.format(Locale.ROOT, "%.1f", preset[i]));
            }
        }

        private boolean matchesSpacingPreset(double[] current, double[] preset) {
            for (int i = 0; i < preset.length; i++) {
                if (Math.abs(current[i] - preset[i]) > 0.05) {
                    return false;
                }
            }
            return true;
        }
    }

    private enum EdgeLabelAnchor {
        TOP,
        RIGHT,
        BOTTOM,
        LEFT
    }

    private enum CoordinateProfile {
        NORMALIZED("Normalized", false),
        POSITION("Position", true),
        OFFSET("Offset", false),
        VECTOR("Vector", true),
        WORLD("World", true);

        private final String label;
        private final boolean yPositiveUp;

        CoordinateProfile(String label, boolean yPositiveUp) {
            this.label = label;
            this.yPositiveUp = yPositiveUp;
        }
    }

    private static class BooleanClusterEntry extends ConfigEntry {
        private final String label;
        private final String subtitle;
        private final List<BooleanBinding> bindings;
        private final BooleanPathConsumer onChange;
        private final List<int[]> hitboxes = new ArrayList<>();

        private BooleanClusterEntry(String label, String subtitle, List<BooleanBinding> bindings, BooleanPathConsumer onChange) {
            this.label = label;
            this.subtitle = subtitle;
            this.bindings = bindings;
            this.onChange = onChange;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            for (int i = 0; i < hitboxes.size(); i++) {
                int[] hitbox = hitboxes.get(i);
                if (mouseX >= hitbox[0] && mouseX <= hitbox[0] + hitbox[2] && mouseY >= hitbox[1] && mouseY <= hitbox[1] + hitbox[3]) {
                    BooleanBinding binding = bindings.get(i);
                    binding.setValue(!binding.value());
                    onChange.accept(binding.path(), binding.value());
                    return true;
                }
            }
            return false;
        }

        @Override
        public void setFocused(boolean focused) {
        }

        @Override
        public List<String> getDeleteTargets() {
            List<String> targets = new ArrayList<>();
            bindings.forEach(binding -> targets.add(binding.path()));
            return targets;
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, subtitle, x + 18, y);
            hitboxes.clear();
            int startX = x + entryWidth - (bindings.size() * 74) - 18;
            for (int i = 0; i < bindings.size(); i++) {
                BooleanBinding binding = bindings.get(i);
                int chipX = startX + i * 74;
                int chipY = y + 18;
                int chipWidth = 66;
                context.fill(chipX, chipY, chipX + chipWidth, chipY + 18, binding.value() ? withAlpha(uiColorHeaderStripe, 180) : withAlpha(uiColorHeader, 116));
                context.drawBorder(chipX, chipY, chipWidth, 18, new Color(uiColorBackgroundBorder, true).getRGB());
                context.drawText(MinecraftClient.getInstance().textRenderer, binding.label(), chipX + 6, chipY + 5, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
                hitboxes.add(new int[]{chipX, chipY, chipWidth, 18});
            }
            drawDeleteButton(context, x, y, entryWidth);
        }
    }

    private static class EnabledModeEntry extends ConfigEntry {
        private final String label;
        private final String enabledPath;
        private final String modePath;
        private final List<String> options;
        private final EnabledModeConsumer onChange;
        private boolean enabled;
        private int selectedIndex;
        private int toggleX;
        private int previousButtonX;
        private int nextButtonX;
        private int buttonY;

        private EnabledModeEntry(String label, String enabledPath, String modePath, boolean enabled, String mode, List<String> options, EnabledModeConsumer onChange) {
            this.label = label;
            this.enabledPath = enabledPath;
            this.modePath = modePath;
            this.enabled = enabled;
            this.options = new ArrayList<>(options);
            this.selectedIndex = Math.max(0, this.options.indexOf(mode));
            this.onChange = onChange;
        }

        private void publish() {
            onChange.accept(enabledPath, modePath, enabled, options.get(selectedIndex));
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (mouseX >= toggleX && mouseX <= toggleX + 46 && mouseY >= buttonY && mouseY <= buttonY + 18) {
                enabled = !enabled;
                publish();
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, previousButtonX, buttonY, CONTROL_BUTTON_WIDTH)) {
                selectedIndex = (selectedIndex - 1 + options.size()) % options.size();
                publish();
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, nextButtonX, buttonY, CONTROL_BUTTON_WIDTH)) {
                selectedIndex = (selectedIndex + 1) % options.size();
                publish();
                return true;
            }
            return false;
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            return false;
        }

        @Override
        public void setFocused(boolean focused) {
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (options.isEmpty()) {
                return false;
            }
            if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                enabled = !enabled;
                publish();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_LEFT) {
                selectedIndex = (selectedIndex - 1 + options.size()) % options.size();
                publish();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_RIGHT) {
                selectedIndex = (selectedIndex + 1) % options.size();
                publish();
                return true;
            }
            return false;
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(enabledPath, modePath);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, enabled ? "Mode active" : "Mode disabled", x + 18, y);
            toggleX = x + entryWidth - 206;
            buttonY = y + 18;
            context.fill(toggleX, buttonY, toggleX + 46, buttonY + 18, enabled ? withAlpha(uiColorHeaderStripe, 180) : withAlpha(uiColorHeader, 116));
            context.drawBorder(toggleX, buttonY, 46, 18, new Color(uiColorBackgroundBorder, true).getRGB());
            context.drawText(MinecraftClient.getInstance().textRenderer, enabled ? "On" : "Off", toggleX + 12, buttonY + 5, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            previousButtonX = x + entryWidth - 152;
            nextButtonX = x + entryWidth - 22;
            drawControlButton(context, previousButtonX, buttonY, CONTROL_BUTTON_WIDTH, "<", true);
            context.fill(x + entryWidth - 122, buttonY, x + entryWidth - 50, buttonY + 18, withAlpha(uiColorHeader, 110));
            context.drawBorder(x + entryWidth - 122, buttonY, 72, 18, new Color(uiColorBackgroundBorder, true).getRGB());
            String current = options.isEmpty() ? "-" : options.get(selectedIndex);
            context.drawText(MinecraftClient.getInstance().textRenderer, current, x + entryWidth - 116, buttonY + 5, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
            drawControlButton(context, nextButtonX, buttonY, CONTROL_BUTTON_WIDTH, ">", true);
            drawDeleteButton(context, x, y, entryWidth);
        }
    }

    private static class MappingEntry extends ConfigEntry {
        private final String label;
        private final List<MappingRow> rows = new ArrayList<>();
        private final TextFieldWidget keyField;
        private final TextFieldWidget valueField;
        private final JsonElementConsumer onChange;
        private int selectedIndex;
        private boolean suppressPublish;
        private int previousButtonX;
        private int nextButtonX;
        private int addButtonX;
        private int removeButtonX;
        private int buttonY;

        private MappingEntry(String label, JsonObject initialValue, JsonElementConsumer onChange) {
            this.label = label;
            this.onChange = onChange;
            initialValue.entrySet().forEach(entry -> rows.add(new MappingRow(entry.getKey(), entry.getValue().getAsString())));
            this.keyField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 104, 18, Text.literal("Key"));
            this.valueField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 118, 18, Text.literal("Value"));
            this.keyField.setChangedListener(raw -> publish());
            this.valueField.setChangedListener(raw -> publish());
            syncFields();
        }

        private void syncFields() {
            suppressPublish = true;
            if (rows.isEmpty()) {
                keyField.setText("");
                valueField.setText("");
                suppressPublish = false;
                return;
            }
            MappingRow row = rows.get(Math.max(0, Math.min(selectedIndex, rows.size() - 1)));
            keyField.setText(row.key);
            valueField.setText(row.value);
            suppressPublish = false;
        }

        private void publish() {
            if (suppressPublish) {
                return;
            }
            if (!rows.isEmpty()) {
                MappingRow selected = rows.get(selectedIndex);
                selected.key = keyField.getText();
                selected.value = valueField.getText();
            }
            JsonObject object = new JsonObject();
            for (MappingRow row : rows) {
                if (row.key == null || row.key.isBlank()) {
                    continue;
                }
                object.addProperty(row.key, row.value);
            }
            onChange.accept(object);
        }

        @Override
        public void tick() {
            keyField.tick();
            valueField.tick();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isControlButtonHit(mouseX, mouseY, previousButtonX, buttonY, CONTROL_BUTTON_WIDTH) && !rows.isEmpty()) {
                selectedIndex = (selectedIndex - 1 + rows.size()) % rows.size();
                syncFields();
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, nextButtonX, buttonY, CONTROL_BUTTON_WIDTH) && !rows.isEmpty()) {
                selectedIndex = (selectedIndex + 1) % rows.size();
                syncFields();
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, addButtonX, buttonY, CONTROL_BUTTON_WIDTH)) {
                rows.add(new MappingRow("new_key_" + (rows.size() + 1), ""));
                selectedIndex = rows.size() - 1;
                keyField.setText(rows.get(selectedIndex).key);
                valueField.setText("");
                publish();
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, removeButtonX, buttonY, CONTROL_BUTTON_WIDTH) && !rows.isEmpty()) {
                rows.remove(selectedIndex);
                selectedIndex = Math.max(0, Math.min(selectedIndex, rows.size() - 1));
                if (rows.isEmpty()) {
                    keyField.setText("");
                    valueField.setText("");
                } else {
                    syncFields();
                }
                publish();
                UiSoundHelper.playButtonClick();
                return true;
            }
            return focusClickedField(mouseX, mouseY, button, keyField, valueField);
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            if (rows.isEmpty() || amount == 0) {
                return false;
            }
            if (mouseX >= previousButtonX && mouseX <= nextButtonX + CONTROL_BUTTON_WIDTH && mouseY >= buttonY && mouseY <= buttonY + 18) {
                selectedIndex = amount > 0 ? (selectedIndex - 1 + rows.size()) % rows.size() : (selectedIndex + 1) % rows.size();
                syncFields();
                UiSoundHelper.playDialClick();
                return true;
            }
            return false;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return keyField.keyPressed(keyCode, scanCode, modifiers) || valueField.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return keyField.charTyped(chr, modifiers) || valueField.charTyped(chr, modifiers);
        }

        @Override
        public void setFocused(boolean focused) {
            if (!focused) {
                keyField.setFocused(false);
                valueField.setFocused(false);
            }
        }

        @Override
        public boolean isFocused() {
            return keyField.isFocused() || valueField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, rows.isEmpty() ? "Empty mapping table" : "Mapping " + (selectedIndex + 1) + " / " + rows.size(), x + 18, y);
            int keyWidth = 72;
            int valueWidth = 76;
            int fieldGap = 6;
            int actionGap = 6;
            int groupWidth = CONTROL_BUTTON_WIDTH + keyWidth + fieldGap + valueWidth + CONTROL_BUTTON_WIDTH + actionGap + CONTROL_BUTTON_WIDTH + CONTROL_BUTTON_WIDTH;
            previousButtonX = getPinnedFieldStartX(x, entryWidth, groupWidth);
            nextButtonX = previousButtonX + CONTROL_BUTTON_WIDTH + keyWidth + fieldGap + valueWidth;
            addButtonX = nextButtonX + CONTROL_BUTTON_WIDTH + actionGap;
            removeButtonX = addButtonX + CONTROL_BUTTON_WIDTH;
            buttonY = y + 18;
            drawControlButton(context, previousButtonX, buttonY, CONTROL_BUTTON_WIDTH, "<", !rows.isEmpty());
            keyField.setX(previousButtonX + CONTROL_BUTTON_WIDTH);
            keyField.setY(y + 18);
            keyField.setWidth(keyWidth);
            keyField.render(context, mouseX, mouseY, delta);
            valueField.setX(keyField.getX() + keyField.getWidth() + 6);
            valueField.setY(y + 18);
            valueField.setWidth(valueWidth);
            valueField.render(context, mouseX, mouseY, delta);
            drawControlButton(context, nextButtonX, buttonY, CONTROL_BUTTON_WIDTH, ">", !rows.isEmpty());
            drawControlButton(context, addButtonX, buttonY, CONTROL_BUTTON_WIDTH, "+", true);
            drawControlButton(context, removeButtonX, buttonY, CONTROL_BUTTON_WIDTH, "-", !rows.isEmpty());
            drawDeleteButton(context, x, y, entryWidth);
        }
    }

    private static class SeedEntry extends ConfigEntry {
        private final String label;
        private final TextFieldWidget valueField;
        private final LongConsumer onChange;
        private int randomizeButtonX;
        private int signedButtonX;
        private int buttonY;

        private SeedEntry(String label, long initialValue, LongConsumer onChange) {
            this.label = label;
            this.onChange = onChange;
            this.valueField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 132, 18, Text.literal(label));
            this.valueField.setText(String.valueOf(initialValue));
            this.valueField.setChangedListener(raw -> {
                try {
                    onChange.accept(Long.parseLong(raw));
                } catch (NumberFormatException ignored) {
                }
            });
        }

        @Override
        public void tick() {
            valueField.tick();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isControlButtonHit(mouseX, mouseY, randomizeButtonX, buttonY, 56)) {
                long nextSeed = createGeneratedSeed();
                valueField.setText(String.valueOf(nextSeed));
                onChange.accept(nextSeed);
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, signedButtonX, buttonY, 40)) {
                try {
                    long flipped = -Long.parseLong(valueField.getText().trim());
                    valueField.setText(String.valueOf(flipped));
                    onChange.accept(flipped);
                    UiSoundHelper.playButtonClick();
                    return true;
                } catch (NumberFormatException ignored) {
                }
                return true;
            }
            return valueField.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return valueField.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            if (amount == 0 || !isMouseOverField(valueField, mouseX, mouseY)) {
                return false;
            }
            boolean changed = nudgeNumericTextField(valueField, amount > 0 ? 1.0 : -1.0, true);
            if (changed) {
                UiSoundHelper.playDialClick();
            }
            return changed;
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return valueField.charTyped(chr, modifiers);
        }

        @Override
        public void setFocused(boolean focused) {
            valueField.setFocused(focused);
        }

        @Override
        public boolean isFocused() {
            return valueField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            String helper = "Roll uses time, nano, and world-style mixing";
            drawEntryText(context, label, helper, x + 18, y);
            valueField.setWidth(156);
            valueField.setX(getCompactFieldX(x, entryWidth, 258));
            valueField.setY(getControlRowY(y, entryHeight));
            valueField.render(context, mouseX, mouseY, delta);
            randomizeButtonX = valueField.getX() + valueField.getWidth() + 6;
            signedButtonX = randomizeButtonX + 62;
            buttonY = getControlRowY(y, entryHeight);
            drawControlButton(context, randomizeButtonX, buttonY, 56, "Roll", true);
            drawControlButton(context, signedButtonX, buttonY, 40, "+/-", false);
            drawDeleteButton(context, x, y, entryWidth);
        }

        private long createGeneratedSeed() {
            long time = Util.getMeasuringTimeMs();
            long nano = System.nanoTime();
            long mixed = time ^ Long.rotateLeft(nano, 21) ^ 0x9E3779B97F4A7C15L;
            mixed ^= (mixed >>> 33);
            mixed *= 0xff51afd7ed558ccdL;
            mixed ^= (mixed >>> 33);
            mixed *= 0xc4ceb9fe1a85ec53L;
            mixed ^= (mixed >>> 33);
            return mixed;
        }
    }

    private static class BooleanBinding {
        private final String label;
        private final String path;
        private boolean value;

        private BooleanBinding(String label, String path, boolean value) {
            this.label = label;
            this.path = path;
            this.value = value;
        }

        private String label() {
            return label;
        }

        private String path() {
            return path;
        }

        private boolean value() {
            return value;
        }

        private void setValue(boolean value) {
            this.value = value;
        }
    }

    private static class MappingRow {
        private String key;
        private String value;

        private MappingRow(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    private static class BooleanEntry extends ConfigEntry {
        private boolean value;
        private final BooleanConsumer onChange;
        private final String label;
        private final TextFieldWidget stateField;
        private int toggleX;
        private int toggleY;
        private int toggleWidth;
        private int toggleHeight;

        public BooleanEntry(String label, boolean initialValue, BooleanConsumer onChange) {
            this.label = label;
            this.value = initialValue;
            this.onChange = onChange;
            this.stateField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 54, 18, Text.literal("state"));
            this.stateField.setText(String.valueOf(initialValue));
            this.stateField.setChangedListener(raw -> {
                Boolean parsed = parseBooleanText(raw);
                if (parsed == null || parsed == value) {
                    return;
                }
                value = parsed;
                onChange.accept(value);
            });
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            toggleWidth = 44;
            toggleHeight = 18;
            toggleY = getControlRowY(y, entryHeight);
            toggleX = getPinnedFieldStartX(x, entryWidth, 44 + 6 + 54);
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, "Boolean value", x + 18, y);
            stateField.setWidth(54);
            stateField.setX(toggleX - 60);
            stateField.setY(toggleY);
            if (!stateField.isFocused() && !stateField.getText().equalsIgnoreCase(String.valueOf(value))) {
                stateField.setText(String.valueOf(value));
            }
            stateField.render(context, mouseX, mouseY, delta);
            context.fill(toggleX, toggleY, toggleX + toggleWidth, toggleY + toggleHeight, value ? withAlpha(uiColorHeaderStripe, 220) : withAlpha(uiColorHeader, 120));
            context.drawBorder(toggleX, toggleY, toggleWidth, toggleHeight, new Color(uiColorBackgroundBorder, true).getRGB());
            int knobX = value ? toggleX + toggleWidth - 16 : toggleX + 2;
            context.fill(knobX, toggleY + 2, knobX + 12, toggleY + toggleHeight - 2, new Color(uiColorContentBaseTitleText, true).getRGB());
            drawDeleteButton(context, x, y, entryWidth);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (focusClickedField(mouseX, mouseY, button, stateField)) {
                return true;
            }
            if (mouseX < toggleX || mouseX > toggleX + toggleWidth || mouseY < toggleY || mouseY > toggleY + toggleHeight) {
                return false;
            }
            value = !value;
            stateField.setText(String.valueOf(value));
            onChange.accept(value);
            UiSoundHelper.playButtonClick();
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (stateField.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
                value = !value;
                stateField.setText(String.valueOf(value));
                onChange.accept(value);
                UiSoundHelper.playButtonClick();
                return true;
            }
            return false;
        }

        @Override
        public void setFocused(boolean focused) {
            stateField.setFocused(focused);
        }

        @Override
        public void tick() {
            stateField.tick();
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return stateField.charTyped(chr, modifiers);
        }

        @Override
        public boolean isFocused() {
            return stateField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }
    }

    private static class BooleanPairEntry extends ConfigEntry {
        private final String label;
        private boolean value;
        private final String enablePath;
        private final String disablePath;
        private final BooleanPairConsumer onChange;
        private final TextFieldWidget stateField;
        private int toggleX;
        private int toggleY;
        private int toggleWidth;
        private int toggleHeight;

        private BooleanPairEntry(String label, boolean initialValue, String enablePath, String disablePath, BooleanPairConsumer onChange) {
            this.label = label;
            this.value = initialValue;
            this.enablePath = enablePath;
            this.disablePath = disablePath;
            this.onChange = onChange;
            this.stateField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 54, 18, Text.literal("state"));
            this.stateField.setText(String.valueOf(initialValue));
            this.stateField.setChangedListener(raw -> {
                Boolean parsed = parseBooleanText(raw);
                if (parsed == null || parsed == value) {
                    return;
                }
                value = parsed;
                onChange.accept(enablePath, disablePath, value);
            });
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (focusClickedField(mouseX, mouseY, button, stateField)) {
                return true;
            }
            if (mouseX < toggleX || mouseX > toggleX + toggleWidth || mouseY < toggleY || mouseY > toggleY + toggleHeight) {
                return false;
            }
            value = !value;
            stateField.setText(String.valueOf(value));
            onChange.accept(enablePath, disablePath, value);
            UiSoundHelper.playButtonClick();
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (stateField.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
                value = !value;
                stateField.setText(String.valueOf(value));
                onChange.accept(enablePath, disablePath, value);
                UiSoundHelper.playButtonClick();
                return true;
            }
            return false;
        }

        @Override
        public void setFocused(boolean focused) {
            stateField.setFocused(focused);
        }

        @Override
        public void tick() {
            stateField.tick();
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return stateField.charTyped(chr, modifiers);
        }

        @Override
        public boolean isFocused() {
            return stateField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(enablePath, disablePath);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            toggleWidth = 54;
            toggleHeight = 18;
            toggleY = getControlRowY(y, entryHeight);
            toggleX = getPinnedFieldStartX(x, entryWidth, 54 + 6 + 54);
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, value ? "Enabled / disabled inverse" : "Disabled / enabled inverse", x + 18, y);
            stateField.setWidth(54);
            stateField.setX(toggleX - 60);
            stateField.setY(toggleY);
            if (!stateField.isFocused() && !stateField.getText().equalsIgnoreCase(String.valueOf(value))) {
                stateField.setText(String.valueOf(value));
            }
            stateField.render(context, mouseX, mouseY, delta);
            int switchFill = value ? withAlpha(uiColorHeaderStripe, 220) : withAlpha(uiColorHeader, 120);
            context.fill(toggleX, toggleY, toggleX + toggleWidth, toggleY + toggleHeight, switchFill);
            context.drawBorder(toggleX, toggleY, toggleWidth, toggleHeight, new Color(uiColorBackgroundBorder, true).getRGB());
            int knobX = value ? toggleX + toggleWidth - 20 : toggleX + 2;
            context.fill(knobX, toggleY + 2, knobX + 16, toggleY + toggleHeight - 2, new Color(uiColorContentBaseTitleText, true).getRGB());
            context.drawText(MinecraftClient.getInstance().textRenderer, value ? "ON" : "OFF", toggleX - 24, toggleY + 5, value ? new Color(uiColorContentBaseTitleText, true).getRGB() : new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            drawDeleteButton(context, x, y, entryWidth);
        }
    }

    private static class TriStateEntry extends ConfigEntry {
        private final String label;
        private final String autoPath;
        private final String enablePath;
        private final String disablePath;
        private final TriStateConsumer onChange;
        private TriStateMode mode;

        private TriStateEntry(String label, String autoPath, String enablePath, String disablePath, TriStateMode mode, TriStateConsumer onChange) {
            this.label = label;
            this.autoPath = autoPath;
            this.enablePath = enablePath;
            this.disablePath = disablePath;
            this.mode = mode;
            this.onChange = onChange;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            int segmentX = getSegmentX();
            int segmentY = getControlRowY(lastRenderY, getPreferredHeight(0));
            int segmentWidth = 54;
            if (isControlButtonHit(mouseX, mouseY, segmentX, segmentY, segmentWidth)) {
                mode = TriStateMode.AUTO;
                onChange.accept(autoPath, enablePath, disablePath, mode);
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, segmentX + 60, segmentY, segmentWidth)) {
                mode = TriStateMode.ON;
                onChange.accept(autoPath, enablePath, disablePath, mode);
                return true;
            }
            if (isControlButtonHit(mouseX, mouseY, segmentX + 120, segmentY, segmentWidth)) {
                mode = TriStateMode.OFF;
                onChange.accept(autoPath, enablePath, disablePath, mode);
                return true;
            }
            return false;
        }

        private int lastRenderX;
        private int lastRenderY;
        private int lastRenderWidth;

        @Override
        public void setFocused(boolean focused) {
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_UP) {
                mode = switch (mode) {
                    case AUTO -> TriStateMode.OFF;
                    case ON -> TriStateMode.AUTO;
                    case OFF -> TriStateMode.ON;
                };
                onChange.accept(autoPath, enablePath, disablePath, mode);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                mode = switch (mode) {
                    case AUTO -> TriStateMode.ON;
                    case ON -> TriStateMode.OFF;
                    case OFF -> TriStateMode.AUTO;
                };
                onChange.accept(autoPath, enablePath, disablePath, mode);
                return true;
            }
            return false;
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(autoPath, enablePath, disablePath);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            lastRenderX = x;
            lastRenderY = y;
            lastRenderWidth = entryWidth;
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, "Tri-state mode", x + 18, y);
            int segmentX = getSegmentX();
            int segmentY = getControlRowY(y, entryHeight);
            drawControlButton(context, segmentX, segmentY, 54, "Auto", mode == TriStateMode.AUTO);
            drawControlButton(context, segmentX + 60, segmentY, 54, "On", mode == TriStateMode.ON);
            drawControlButton(context, segmentX + 120, segmentY, 54, "Off", mode == TriStateMode.OFF);
            drawDeleteButton(context, x, y, entryWidth);
        }

        private int getSegmentX() {
            return getPinnedFieldStartX(lastRenderX, lastRenderWidth, 174);
        }
    }

    private static class StringCoordinateEntry extends ConfigEntry {
        private final String label;
        private final TextFieldWidget[] fields;
        private final int componentCount;
        private final StringConsumer onChange;
        private boolean suppress;
        private final CoordinateProfile profile;
        private final double[] defaultValues;
        private int openButtonX;
        private int buttonY;
        private boolean editorOpen;
        private int popoverX;
        private int popoverY;
        private int popoverWidth;
        private int popoverHeight;
        private Integer requestedPopoverX;
        private Integer requestedPopoverY;
        private int planeX;
        private int planeY;
        private int planeSize;
        private int planeTabY;
        private int xyTabX;
        private int xzTabX;
        private int yzTabX;
        private int xyPlaneX;
        private int xyPlaneY;
        private int xzPlaneX;
        private int xzPlaneY;
        private int yzPlaneX;
        private int yzPlaneY;
        private int subPlaneSize;
        private int resetViewButtonX;
        private int zoomInButtonX;
        private int zoomOutButtonX;
        private int zoomButtonY;
        private int zSliderX;
        private int zSliderY;
        private int zSliderHeight;
        private boolean draggingPlane;
        private boolean draggingZ;
        private boolean panningPlane;
        private double dragStartMouseX;
        private double dragStartMouseY;
        private double dragStartValueX;
        private double dragStartValueY;
        private double dragStartValueZ;
        private double panStartMouseX;
        private double panStartMouseY;
        private double panStartCenterX;
        private double panStartCenterY;
        private double panStartCenterZ;
        private double viewCenterX;
        private double viewCenterY;
        private double viewCenterZ;
        private double viewSpan;
        private char active3dPlane = 'x';

        private StringCoordinateEntry(String label, String initialValue, StringConsumer onChange) {
            this.label = label;
            this.onChange = onChange;
            String[] parts = initialValue.split("\\s*,\\s*");
            this.componentCount = parts.length;
            this.fields = new TextFieldWidget[componentCount];
            for (int i = 0; i < componentCount; i++) {
                TextFieldWidget field = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 44, 18, Text.literal("V"));
                field.setText(parts[i]);
                final int index = i;
                field.setChangedListener(value -> publish(index, value));
                fields[i] = field;
            }
            this.profile = inferCoordinateProfile(label);
            this.defaultValues = new double[componentCount];
            for (int i = 0; i < componentCount; i++) {
                try {
                    defaultValues[i] = Double.parseDouble(parts[i]);
                } catch (NumberFormatException ignored) {
                    defaultValues[i] = 0.0;
                }
            }
            initializeView();
        }

        private void publish(int changedIndex, String ignored) {
            if (suppress) {
                return;
            }
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(fields[i].getText().trim());
            }
            onChange.accept(builder.toString());
        }

        private char getStringPlaneHorizontalAxis(char plane) {
            return switch (plane) {
                case 'z' -> 'x';
                case 'y' -> 'y';
                default -> 'x';
            };
        }

        private char getStringPlaneVerticalAxis(char plane) {
            return switch (plane) {
                case 'z', 'y' -> 'z';
                default -> 'y';
            };
        }

        private String getStringPlaneTitle(char plane) {
            return switch (plane) {
                case 'z' -> "XZ";
                case 'y' -> "YZ";
                default -> "XY";
            };
        }

        @Override
        public void tick() {
            for (TextFieldWidget field : fields) {
                field.tick();
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isControlButtonHit(mouseX, mouseY, openButtonX, buttonY, 44)) {
                requestedPopoverX = (int) Math.round(mouseX);
                requestedPopoverY = (int) Math.round(mouseY);
                editorOpen = !editorOpen;
                draggingPlane = false;
                draggingZ = false;
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (editorOpen && button == 0) {
                if (componentCount >= 3) {
                    if (isControlButtonHit(mouseX, mouseY, xyTabX, planeTabY, 28)) {
                        active3dPlane = 'x';
                        UiSoundHelper.playButtonClick();
                        return true;
                    }
                    if (isControlButtonHit(mouseX, mouseY, xzTabX, planeTabY, 28)) {
                        active3dPlane = 'z';
                        UiSoundHelper.playButtonClick();
                        return true;
                    }
                    if (isControlButtonHit(mouseX, mouseY, yzTabX, planeTabY, 28)) {
                        active3dPlane = 'y';
                        UiSoundHelper.playButtonClick();
                        return true;
                    }
                }
                if (isInsidePlane(mouseX, mouseY)) {
                    if (componentCount >= 3 && isNearProjectedPoint(mouseX, mouseY)) {
                        draggingPlane = true;
                        dragStartMouseX = mouseX;
                        dragStartMouseY = mouseY;
                        dragStartValueX = getComponentValue(0);
                        dragStartValueY = getComponentValue(1);
                        dragStartValueZ = getComponentValue(2);
                        applyProjected3dDrag(mouseX, mouseY);
                    } else if (componentCount >= 3 && profile != CoordinateProfile.NORMALIZED) {
                        panningPlane = true;
                        panStartMouseX = mouseX;
                        panStartMouseY = mouseY;
                        panStartCenterX = viewCenterX;
                        panStartCenterY = viewCenterY;
                        panStartCenterZ = viewCenterZ;
                    } else if (componentCount < 3 && isNearPoint(mouseX, mouseY)) {
                        draggingPlane = true;
                        applyPlaneDrag(mouseX, mouseY);
                    } else if (componentCount < 3 && profile != CoordinateProfile.NORMALIZED) {
                        panningPlane = true;
                        panStartMouseX = mouseX;
                        panStartMouseY = mouseY;
                        panStartCenterX = viewCenterX;
                        panStartCenterY = viewCenterY;
                    }
                    UiSoundHelper.playButtonClick();
                    return true;
                }
                if (isControlButtonHit(mouseX, mouseY, zoomInButtonX, zoomButtonY, 22)) {
                    adjustZoom(-1);
                    UiSoundHelper.playButtonClick();
                    return true;
                }
                if (isControlButtonHit(mouseX, mouseY, resetViewButtonX, zoomButtonY, 22)) {
                    initializeView();
                    UiSoundHelper.playButtonClick();
                    return true;
                }
                if (isControlButtonHit(mouseX, mouseY, zoomOutButtonX, zoomButtonY, 22)) {
                    adjustZoom(1);
                    UiSoundHelper.playButtonClick();
                    return true;
                }
            }
            return focusClickedField(mouseX, mouseY, button, fields);
        }

        @Override
        public boolean handleMouseDrag(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (button != 0) {
                return false;
            }
            if (draggingPlane) {
                if (componentCount >= 3) {
                    applyProjected3dDrag(mouseX, mouseY);
                } else {
                    applyPlaneDrag(mouseX, mouseY);
                }
                return true;
            }
            if (panningPlane) {
                applyPlanePan(mouseX, mouseY);
                return true;
            }
            if (draggingZ) {
                applyZDrag(mouseY);
                return true;
            }
            return false;
        }

        @Override
        public boolean handleMouseRelease(double mouseX, double mouseY, int button) {
            boolean wasDragging = draggingPlane || draggingZ || panningPlane;
            draggingPlane = false;
            draggingZ = false;
            panningPlane = false;
            return wasDragging;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            for (TextFieldWidget field : fields) {
                if (field.keyPressed(keyCode, scanCode, modifiers)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            for (TextFieldWidget field : fields) {
                if (field.charTyped(chr, modifiers)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            for (TextFieldWidget field : fields) {
                if (isMouseOverField(field, mouseX, mouseY)) {
                    boolean changed = nudgeNumericTextField(field, amount > 0 ? 1.0 : -1.0, textLooksWholeNumber(field));
                    if (changed) {
                        UiSoundHelper.playDialClick();
                    }
                    return changed;
                }
            }
            if (editorOpen && isInsidePlane(mouseX, mouseY) && amount != 0.0) {
                adjustZoom(amount > 0 ? -1 : 1);
                UiSoundHelper.playDialClick();
                return true;
            }
            return false;
        }

        @Override
        public void setFocused(boolean focused) {
            if (!focused) {
                for (TextFieldWidget field : fields) {
                    field.setFocused(false);
                }
                editorOpen = false;
                draggingPlane = false;
                draggingZ = false;
                panningPlane = false;
                active3dPlane = 'x';
                requestedPopoverX = null;
                requestedPopoverY = null;
            }
        }

        @Override
        public boolean isFocused() {
            for (TextFieldWidget field : fields) {
                if (field.isFocused()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, null, x + 18, y);
            int buttonWidth = 44;
            int buttonGap = 10;
            int fieldWidth = isCompactConfigListingEnabledStatic() ? 42 : 44;
            int fieldGap = 6;
            int totalWidth = buttonWidth + buttonGap + componentCount * fieldWidth + Math.max(0, componentCount - 1) * fieldGap;
            int startX = getPinnedFieldStartX(x, entryWidth, totalWidth);
            openButtonX = startX;
            buttonY = getControlRowY(y, entryHeight);
            drawControlButton(context, openButtonX, buttonY, buttonWidth, "Edit", editorOpen);
            int fieldStartX = startX + buttonWidth + buttonGap;
            boolean showCaptions = showFieldCaptions();
            for (int i = 0; i < fields.length; i++) {
                TextFieldWidget field = fields[i];
                int fieldX = fieldStartX + i * (fieldWidth + fieldGap);
                if (showCaptions) {
                    context.drawText(MinecraftClient.getInstance().textRenderer, componentCount == 3 ? (i == 0 ? "X" : i == 1 ? "Y" : "Z") : (i == 0 ? "X" : "Y"), fieldX, getFieldCaptionY(buttonY), new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                }
                field.setWidth(fieldWidth);
                field.setX(fieldX);
                field.setY(buttonY);
                field.render(context, mouseX, mouseY, delta);
            }
            drawDeleteButton(context, x, y, entryWidth);
        }

        @Override
        public void renderOverlay(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, float delta) {
            if (!editorOpen) {
                return;
            }
            context.getMatrices().push();
            context.getMatrices().translate(0.0F, 0.0F, OVERLAY_Z);
            drawCoordinatePopover(context, mouseX, mouseY);
            context.getMatrices().pop();
        }

        @Override
        public boolean hasModalOverlay() {
            return editorOpen;
        }

        @Override
        public boolean openInlineEditor(double mouseX, double mouseY) {
            requestedPopoverX = Double.isNaN(mouseX) ? null : (int) Math.round(mouseX);
            requestedPopoverY = Double.isNaN(mouseY) ? null : (int) Math.round(mouseY);
            editorOpen = true;
            active3dPlane = 'x';
            draggingPlane = false;
            draggingZ = false;
            panningPlane = false;
            return true;
        }

        @Override
        public boolean handleOverlayClick(double mouseX, double mouseY, int button) {
            boolean inside = (mouseX >= popoverX && mouseX <= popoverX + popoverWidth && mouseY >= popoverY && mouseY <= popoverY + popoverHeight)
                    || isControlButtonHit(mouseX, mouseY, openButtonX, buttonY, 44);
            if (inside) {
                return mouseClicked(mouseX, mouseY, button) || (mouseX >= popoverX && mouseX <= popoverX + popoverWidth && mouseY >= popoverY && mouseY <= popoverY + popoverHeight);
            }
            editorOpen = false;
            draggingPlane = false;
            draggingZ = false;
            panningPlane = false;
            active3dPlane = 'x';
            requestedPopoverX = null;
            requestedPopoverY = null;
            return true;
        }

        private void initializeView() {
            double x = getComponentValue(0);
            double y = getComponentValue(1);
            if (profile == CoordinateProfile.NORMALIZED) {
                viewCenterX = 0.5;
                viewCenterY = 0.5;
                viewCenterZ = 0.5;
                viewSpan = 0.5;
                return;
            }
            if (profile == CoordinateProfile.VECTOR) {
                viewCenterX = 0.0;
                viewCenterY = 0.0;
                viewCenterZ = 0.0;
                viewSpan = roundSpan(Math.max(8.0, Math.max(Math.abs(x), Math.abs(y)) * 1.5));
                return;
            }
            double z = componentCount >= 3 ? getComponentValue(2) : 0.0;
            if (profile == CoordinateProfile.WORLD) {
                viewCenterX = x;
                viewCenterY = y;
                viewCenterZ = z;
                viewSpan = roundSpan(Math.max(16.0, Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.abs(z)) * 0.5));
                return;
            }
            viewCenterX = x;
            viewCenterY = y;
            viewCenterZ = z;
            viewSpan = roundSpan(Math.max(8.0, Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.abs(z)) * 1.25));
        }

        private void drawCoordinatePopover(DrawContext context, int mouseX, int mouseY) {
            popoverWidth = componentCount >= 3 ? 252 : 208;
            popoverHeight = componentCount >= 3 ? 212 : 174;
            int anchorX = requestedPopoverX != null ? requestedPopoverX : openButtonX;
            int anchorY = requestedPopoverY != null ? requestedPopoverY : buttonY;
            int desiredX = anchorX - popoverWidth - 8;
            int desiredY = anchorY - 14;
            int maxX = MinecraftClient.getInstance().currentScreen.width - popoverWidth - 8;
            int maxY = MinecraftClient.getInstance().currentScreen.height - popoverHeight - 8;
            popoverX = Math.max(8, Math.min(desiredX, maxX));
            popoverY = Math.max(8, Math.min(desiredY, maxY));
            context.fill(popoverX, popoverY, popoverX + popoverWidth, popoverY + popoverHeight, withAlpha(uiColorContentBase, 236));
            context.drawBorder(popoverX, popoverY, popoverWidth, popoverHeight, new Color(uiColorBackgroundBorder, true).getRGB());

            TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
            planeSize = componentCount >= 3 ? 120 : 108;
            planeX = popoverX + 10;
            planeY = popoverY + (componentCount >= 3 ? 40 : 25);
            int titleX = planeX;
            if (componentCount >= 3) {
                planeTabY = popoverY + 18;
                xyTabX = planeX;
                xzTabX = planeX + 32;
                yzTabX = planeX + 64;
                titleX = yzTabX + 36;
            }

            resetViewButtonX = popoverX + popoverWidth - 74;
            zoomInButtonX = popoverX + popoverWidth - 50;
            zoomOutButtonX = popoverX + popoverWidth - 26;
            zoomButtonY = popoverY + 8;
            int titleWidth = Math.max(34, resetViewButtonX - titleX - 8);
            context.drawText(renderer, fitInlineText(profile.label, titleWidth), titleX, popoverY + 10, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            drawControlButton(context, resetViewButtonX, zoomButtonY, 22, "R", false);
            drawControlButton(context, zoomInButtonX, zoomButtonY, 22, "+", true);
            drawControlButton(context, zoomOutButtonX, zoomButtonY, 22, "-", true);

            drawCoordinatePlane(context, mouseX, mouseY);

            double xValue = getComponentValue(0);
            double yValue = getComponentValue(1);
            boolean hoveringPlane = isInsidePlane(mouseX, mouseY);
            String focusLabel = hoveringPlane
                    ? componentCount >= 3 ? format3dHoverLabel(mouseX, mouseY) : "Cursor " + formatAxisValue(fromScreenX(mouseX)) + ", " + formatAxisValue(fromScreenY(mouseY))
                    : "Center " + formatAxisValue(viewCenterX) + ", " + formatAxisValue(viewCenterY);
            String spanLabel = "Span " + formatAxisValue(viewSpan * 2.0);
            if (componentCount >= 3) {
                drawControlButton(context, xyTabX, planeTabY, 28, "XY", active3dPlane == 'x');
                drawControlButton(context, xzTabX, planeTabY, 28, "XZ", active3dPlane == 'z');
                drawControlButton(context, yzTabX, planeTabY, 28, "YZ", active3dPlane == 'y');
            }

            int readoutY = planeY + planeSize + 6;
            int infoWidth = popoverWidth - 20;
            String xLabel = "X " + formatCoordinateValue(xValue);
            String yLabel = "Y " + formatCoordinateValue(yValue);
            int pairWidth = Math.max(70, (infoWidth - 6) / 2);
            if (componentCount < 3) {
                context.drawText(renderer, fitInlineText(xLabel, pairWidth), planeX, readoutY, new Color(uiColorConfigAxisX, true).getRGB(), false);
                int yLabelX = planeX + pairWidth + 6;
                context.drawText(renderer, fitInlineText(yLabel, pairWidth), yLabelX, readoutY, new Color(uiColorConfigAxisY, true).getRGB(), false);
            }
            if (componentCount >= 3) {
                double zValue = getComponentValue(2);
                double magnitude = Math.sqrt((xValue * xValue) + (yValue * yValue) + (zValue * zValue));
                String zLabel = "Z " + formatCoordinateValue(zValue);
                String magnitudeLabel = "|v| " + formatCoordinateValue(magnitude);
                int tripleWidth = Math.max(52, (infoWidth - 8) / 3);
                int tripleGap = 4;
                context.drawText(renderer, fitInlineText(xLabel, tripleWidth), planeX, readoutY, new Color(uiColorConfigAxisX, true).getRGB(), false);
                context.drawText(renderer, fitInlineText(yLabel, tripleWidth), planeX + tripleWidth + tripleGap, readoutY, new Color(uiColorConfigAxisY, true).getRGB(), false);
                context.drawText(renderer, fitInlineText(zLabel, tripleWidth), planeX + ((tripleWidth + tripleGap) * 2), readoutY, new Color(uiColorConfigAxisZ, true).getRGB(), false);
                int summaryWidth = Math.max(68, (infoWidth - 6) / 2);
                context.drawText(renderer, fitInlineText(magnitudeLabel, summaryWidth), planeX, readoutY + 12, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
                int spanX = popoverX + popoverWidth - 10 - Math.min(summaryWidth, renderer.getWidth(spanLabel));
                context.drawText(renderer, fitInlineText(spanLabel, summaryWidth), spanX, readoutY + 12, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                context.drawText(renderer, fitInlineText(focusLabel, infoWidth), planeX, readoutY + 26, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            } else {
                int summaryWidth = Math.max(68, (infoWidth - 6) / 2);
                context.drawText(renderer, fitInlineText(focusLabel, summaryWidth), planeX, readoutY + 12, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
                int spanX = popoverX + popoverWidth - 10 - Math.min(summaryWidth, renderer.getWidth(spanLabel));
                context.drawText(renderer, fitInlineText(spanLabel, summaryWidth), spanX, readoutY + 12, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            }
        }

        private void drawCoordinatePlane(DrawContext context, int mouseX, int mouseY) {
            if (componentCount >= 3) {
                drawOrthographicPlane(context, planeX, planeY, planeSize, getStringPlaneHorizontalAxis(active3dPlane), getStringPlaneVerticalAxis(active3dPlane), "", mouseX, mouseY);
                return;
            }
            context.fill(planeX, planeY, planeX + planeSize, planeY + planeSize, withAlpha(uiColorHeader, 98));
            context.drawBorder(planeX, planeY, planeSize, planeSize, new Color(uiColorBackgroundBorder, true).getRGB());
            int hoveredGridX = isInsidePlane(mouseX, mouseY) ? getClosestGridScreenX(mouseX) : Integer.MIN_VALUE;
            int hoveredGridY = isInsidePlane(mouseX, mouseY) ? getClosestGridScreenY(mouseY) : Integer.MIN_VALUE;
            double gridStep = getGridStep();
            double majorStep = gridStep * getMajorGridStride();
            context.enableScissor(planeX + 1, planeY + 1, planeX + planeSize, planeY + planeSize);
            int renderedVerticalGridLines = 0;
            for (double gridX = Math.floor(getMinVisibleX() / gridStep) * gridStep; gridX <= getMaxVisibleX() + (gridStep * 0.5) && renderedVerticalGridLines++ < MAX_COORDINATE_GRID_LINES; gridX += gridStep) {
                int px = toScreenX(gridX);
                boolean major = isMajorGridValue(gridX, majorStep);
                int color = px == hoveredGridX ? withAlpha(uiColorContentBaseTitleText, 126) : major ? withAlpha(uiColorBackgroundBorder, 128) : withAlpha(uiColorBackgroundBorder, 68);
                context.fill(px, planeY, px + 1, planeY + planeSize, color);
            }
            int renderedHorizontalGridLines = 0;
            for (double gridY = Math.floor(getMinVisibleY() / gridStep) * gridStep; gridY <= getMaxVisibleY() + (gridStep * 0.5) && renderedHorizontalGridLines++ < MAX_COORDINATE_GRID_LINES; gridY += gridStep) {
                int py = toScreenY(gridY);
                boolean major = isMajorGridValue(gridY, majorStep);
                int color = py == hoveredGridY ? withAlpha(uiColorContentBaseTitleText, 126) : major ? withAlpha(uiColorBackgroundBorder, 128) : withAlpha(uiColorBackgroundBorder, 68);
                context.fill(planeX, py, planeX + planeSize, py + 1, color);
            }
            int xAxisColor = new Color(uiColorConfigAxisXSoft, true).getRGB();
            int yAxisColor = new Color(uiColorConfigAxisYSoft, true).getRGB();
            if (profile == CoordinateProfile.NORMALIZED) {
                context.fill(planeX, planeY + planeSize - 1, planeX + planeSize, planeY + planeSize, xAxisColor);
                context.fill(planeX, planeY, planeX + 1, planeY + planeSize, yAxisColor);
            } else {
                int axisX = toScreenX(0.0);
                int axisY = toScreenY(0.0);
                if (axisX >= planeX && axisX <= planeX + planeSize) {
                    context.fill(axisX, planeY, axisX + 1, planeY + planeSize, yAxisColor);
                }
                if (axisY >= planeY && axisY <= planeY + planeSize) {
                    context.fill(planeX, axisY, planeX + planeSize, axisY + 1, xAxisColor);
                }
            }
            if (profile != CoordinateProfile.NORMALIZED) {
                int originX = toScreenX(0.0);
                int originY = toScreenY(0.0);
                if (originX >= planeX && originX <= planeX + planeSize && originY >= planeY && originY <= planeY + planeSize) {
                    context.fill(originX - 2, originY - 2, originX + 3, originY + 3, withAlpha(uiColorHeaderSubTitleText, 220));
                }
            }
            int ghostX = toScreenX(defaultValues[0]);
            int ghostY = toScreenY(defaultValues[1]);
            context.fill(ghostX, planeY, ghostX + 1, planeY + planeSize, withAlpha(new Color(uiColorConfigAxisY, true).getRGB(), 34));
            context.fill(planeX, ghostY, planeX + planeSize, ghostY + 1, withAlpha(new Color(uiColorConfigAxisX, true).getRGB(), 34));
            context.drawBorder(ghostX - 3, ghostY - 3, 6, 6, withAlpha(uiColorHeaderSubTitleText, 150));
            int pointX = toScreenX(getComponentValue(0));
            int pointY = toScreenY(getComponentValue(1));
            context.fill(pointX, planeY, pointX + 1, planeY + planeSize, withAlpha(new Color(uiColorConfigAxisY, true).getRGB(), 76));
            context.fill(planeX, pointY, planeX + planeSize, pointY + 1, withAlpha(new Color(uiColorConfigAxisX, true).getRGB(), 76));
            context.fill(pointX - 2, pointY - 2, pointX + 3, pointY + 3, new Color(uiColorContentBaseTitleText, true).getRGB());
            if (componentCount >= 3) {
                drawProjected3dPreview(context);
            }

            if (isInsidePlane(mouseX, mouseY)) {
                int hoverX = (int) Math.max(planeX, Math.min(planeX + planeSize, mouseX));
                int hoverY = (int) Math.max(planeY, Math.min(planeY + planeSize, mouseY));
                context.fill(hoverX, planeY, hoverX + 1, planeY + planeSize, withAlpha(uiColorContentBaseTitleText, 40));
                context.fill(planeX, hoverY, planeX + planeSize, hoverY + 1, withAlpha(uiColorContentBaseTitleText, 40));
                String hoverLabel = formatAxisValue(fromScreenX(mouseX)) + ", " + formatAxisValue(fromScreenY(mouseY));
                int hoverLabelWidth = MinecraftClient.getInstance().textRenderer.getWidth(hoverLabel);
                context.fill(planeX + 6, planeY + 6, planeX + 12 + hoverLabelWidth, planeY + 18, withAlpha(uiColorContentBase, 228));
                context.drawText(MinecraftClient.getInstance().textRenderer, hoverLabel, planeX + 9, planeY + 9, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            }
            context.disableScissor();

            String centerLabel = "C " + formatAxisValue(viewCenterX) + ", " + formatAxisValue(viewCenterY);
            context.drawText(MinecraftClient.getInstance().textRenderer, centerLabel, planeX, planeY - 10, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        }

        private void drawOrthographicPlane(DrawContext context, int left, int top, int size, char horizontalAxis, char verticalAxis, String title, int mouseX, int mouseY) {
            boolean hovered = mouseX >= left && mouseX <= left + size && mouseY >= top && mouseY <= top + size;
            context.fill(left, top, left + size, top + size, withAlpha(uiColorHeader, hovered ? 116 : 98));
            context.drawBorder(left, top, size, size, new Color(uiColorBackgroundBorder, true).getRGB());
            double minH = getAxisMin(horizontalAxis);
            double maxH = getAxisMax(horizontalAxis);
            double minV = getAxisMin(verticalAxis);
            double maxV = getAxisMax(verticalAxis);
            double gridStep = getGridStep();
            double majorStep = gridStep * getMajorGridStride();
            int hoveredGridX = hovered ? axisToScreen(left, size, horizontalAxis, snapCoordinate(screenToAxis(left, size, horizontalAxis, mouseX))) : Integer.MIN_VALUE;
            int hoveredGridY = hovered ? axisToScreenY(top, size, verticalAxis, snapCoordinate(screenToAxisY(top, size, verticalAxis, mouseY))) : Integer.MIN_VALUE;
            context.enableScissor(left + 1, top + 1, left + size, top + size);
            int renderedVerticalGridLines = 0;
            for (double grid = Math.floor(minH / gridStep) * gridStep; grid <= maxH + (gridStep * 0.5) && renderedVerticalGridLines++ < MAX_COORDINATE_GRID_LINES; grid += gridStep) {
                int px = axisToScreen(left, size, horizontalAxis, grid);
                int color = px == hoveredGridX ? withAlpha(uiColorContentBaseTitleText, 120) : isMajorGridValue(grid, majorStep) ? withAlpha(uiColorBackgroundBorder, 110) : withAlpha(uiColorBackgroundBorder, 58);
                context.fill(px, top, px + 1, top + size, color);
            }
            int renderedHorizontalGridLines = 0;
            for (double grid = Math.floor(minV / gridStep) * gridStep; grid <= maxV + (gridStep * 0.5) && renderedHorizontalGridLines++ < MAX_COORDINATE_GRID_LINES; grid += gridStep) {
                int py = axisToScreenY(top, size, verticalAxis, grid);
                int color = py == hoveredGridY ? withAlpha(uiColorContentBaseTitleText, 120) : isMajorGridValue(grid, majorStep) ? withAlpha(uiColorBackgroundBorder, 110) : withAlpha(uiColorBackgroundBorder, 58);
                context.fill(left, py, left + size, py + 1, color);
            }
            drawAxisLine(context, left, top, size, horizontalAxis, true);
            drawAxisLine(context, left, top, size, verticalAxis, false);
            int ghostX = axisToScreen(left, size, horizontalAxis, getAxisValue(horizontalAxis, defaultValues));
            int ghostY = axisToScreenY(top, size, verticalAxis, getAxisValue(verticalAxis, defaultValues));
            context.drawBorder(ghostX - 3, ghostY - 3, 6, 6, withAlpha(uiColorHeaderSubTitleText, 150));
            int pointX = axisToScreen(left, size, horizontalAxis, getAxisValue(horizontalAxis));
            int pointY = axisToScreenY(top, size, verticalAxis, getAxisValue(verticalAxis));
            context.fill(pointX, top, pointX + 1, top + size, withAlpha(axisColor(horizontalAxis), 72));
            context.fill(left, pointY, left + size, pointY + 1, withAlpha(axisColor(verticalAxis), 72));
            context.fill(pointX - 2, pointY - 2, pointX + 3, pointY + 3, new Color(uiColorContentBaseTitleText, true).getRGB());
            if (hovered) {
                int hoverX = Math.max(left, Math.min(left + size, mouseX));
                int hoverY = Math.max(top, Math.min(top + size, mouseY));
                context.fill(hoverX, top, hoverX + 1, top + size, withAlpha(uiColorContentBaseTitleText, 34));
                context.fill(left, hoverY, left + size, hoverY + 1, withAlpha(uiColorContentBaseTitleText, 34));
            }
            context.disableScissor();
            if (!title.isBlank()) {
                context.drawText(MinecraftClient.getInstance().textRenderer, title, left + 4, top - 10, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            }
        }

        private void drawAxisLine(DrawContext context, int left, int top, int size, char axis, boolean verticalLine) {
            double zero = 0.0;
            if (zero < getAxisMin(axis) || zero > getAxisMax(axis)) {
                return;
            }
            if (verticalLine) {
                int px = axisToScreen(left, size, axis, zero);
                context.fill(px, top, px + 1, top + size, axisColor(axis));
            } else {
                int py = axisToScreenY(top, size, axis, zero);
                context.fill(left, py, left + size, py + 1, axisColor(axis));
            }
        }

        private int axisColor(char axis) {
            return switch (axis) {
                case 'x' -> new Color(uiColorConfigAxisX, true).getRGB();
                case 'y' -> new Color(uiColorConfigAxisY, true).getRGB();
                default -> new Color(uiColorConfigAxisZ, true).getRGB();
            };
        }

        private String format3dHoverLabel(double mouseX, double mouseY) {
            char horizontal = getStringPlaneHorizontalAxis(active3dPlane);
            char vertical = getStringPlaneVerticalAxis(active3dPlane);
            return getStringPlaneTitle(active3dPlane) + " "
                    + formatAxisValue(screenToAxis(planeX, planeSize, horizontal, mouseX))
                    + ", "
                    + formatAxisValue(screenToAxisY(planeY, planeSize, vertical, mouseY));
        }

        private void drawProjected3dEditor(DrawContext context, int mouseX, int mouseY) {
            context.fill(planeX, planeY, planeX + planeSize, planeY + planeSize, withAlpha(uiColorHeader, 98));
            context.drawBorder(planeX, planeY, planeSize, planeSize, new Color(uiColorBackgroundBorder, true).getRGB());
            int squareStep = Math.max(12, planeSize / 8);
            for (int gx = planeX; gx <= planeX + planeSize; gx += squareStep) {
                context.fill(gx, planeY, gx + 1, planeY + planeSize, withAlpha(uiColorBackgroundBorder, 34));
            }
            for (int gy = planeY; gy <= planeY + planeSize; gy += squareStep) {
                context.fill(planeX, gy, planeX + planeSize, gy + 1, withAlpha(uiColorBackgroundBorder, 34));
            }
            context.fill(planeX + 10, planeY + planeSize - 42, planeX + planeSize - 10, planeY + planeSize - 10, withAlpha(uiColorHeader, 54));
            for (int grid = -2; grid <= 2; grid++) {
                drawProjectedLine(context, planeX, planeY, planeSize, -1.0, grid / 2.0, -1.0, 1.0, grid / 2.0, -1.0, withAlpha(new Color(uiColorConfigAxisY, true).getRGB(), 70));
                drawProjectedLine(context, planeX, planeY, planeSize, grid / 2.0, -1.0, -1.0, grid / 2.0, 1.0, -1.0, withAlpha(new Color(uiColorConfigAxisX, true).getRGB(), 70));
                drawProjectedLine(context, planeX, planeY, planeSize, -1.0, -1.0, grid / 2.0, -1.0, 1.0, grid / 2.0, withAlpha(new Color(uiColorConfigAxisZ, true).getRGB(), 62));
            }
            double[][] corners = new double[][]{
                    {-1.0, -1.0, -1.0}, {1.0, -1.0, -1.0}, {1.0, 1.0, -1.0}, {-1.0, 1.0, -1.0},
                    {-1.0, -1.0, 1.0}, {1.0, -1.0, 1.0}, {1.0, 1.0, 1.0}, {-1.0, 1.0, 1.0}
            };
            drawProjectedEdgeLoop(context, planeX, planeY, planeSize, corners, 0, 1, 2, 3, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedEdgeLoop(context, planeX, planeY, planeSize, corners, 4, 5, 6, 7, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedLine(context, planeX, planeY, planeSize, -1.0, -1.0, -1.0, -1.0, -1.0, 1.0, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedLine(context, planeX, planeY, planeSize, 1.0, -1.0, -1.0, 1.0, -1.0, 1.0, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedLine(context, planeX, planeY, planeSize, 1.0, 1.0, -1.0, 1.0, 1.0, 1.0, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedLine(context, planeX, planeY, planeSize, -1.0, 1.0, -1.0, -1.0, 1.0, 1.0, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedAxis(context, planeX, planeY, planeSize, 0.0, 0.0, 0.0, 1.15, 0.0, 0.0, new Color(uiColorConfigAxisX, true).getRGB());
            drawProjectedAxis(context, planeX, planeY, planeSize, 0.0, 0.0, 0.0, 0.0, 1.15, 0.0, new Color(uiColorConfigAxisY, true).getRGB());
            drawProjectedAxis(context, planeX, planeY, planeSize, 0.0, 0.0, 0.0, 0.0, 0.0, 1.15, new Color(uiColorConfigAxisZ, true).getRGB());
            int ghostPointX = projectIsoToScreenX(planeX, planeSize, normalizeToCube(defaultValues[0], getMinVisibleX(), getMaxVisibleX()), normalizeToCube(defaultValues[1], getMinVisibleY(), getMaxVisibleY()), normalizeToCube(defaultValues[2], getMinVisibleZ(), getMaxVisibleZ()));
            int ghostPointY = projectIsoToScreenY(planeY, planeSize, normalizeToCube(defaultValues[0], getMinVisibleX(), getMaxVisibleX()), normalizeToCube(defaultValues[1], getMinVisibleY(), getMaxVisibleY()), normalizeToCube(defaultValues[2], getMinVisibleZ(), getMaxVisibleZ()));
            context.drawBorder(ghostPointX - 3, ghostPointY - 3, 6, 6, withAlpha(uiColorHeaderSubTitleText, 150));
            double cubeX = normalizeToCube(getComponentValue(0), getMinVisibleX(), getMaxVisibleX());
            double cubeY = normalizeToCube(getComponentValue(1), getMinVisibleY(), getMaxVisibleY());
            double cubeZ = normalizeToCube(getComponentValue(2), getMinVisibleZ(), getMaxVisibleZ());
            drawProjectedSlicePlane(context, planeX, planeY, planeSize, cubeX, 'x', withAlpha(new Color(uiColorConfigAxisX, true).getRGB(), 108));
            drawProjectedSlicePlane(context, planeX, planeY, planeSize, cubeY, 'y', withAlpha(new Color(uiColorConfigAxisY, true).getRGB(), 108));
            drawProjectedSlicePlane(context, planeX, planeY, planeSize, cubeZ, 'z', withAlpha(new Color(uiColorConfigAxisZ, true).getRGB(), 100));
            int pointX = projectIsoToScreenX(planeX, planeSize, cubeX, cubeY, cubeZ);
            int pointY = projectIsoToScreenY(planeY, planeSize, cubeX, cubeY, cubeZ);
            drawProjectedLine(context, planeX, planeY, planeSize, cubeX, cubeY, cubeZ, cubeX, cubeY, -1.0, withAlpha(new Color(uiColorConfigAxisZ, true).getRGB(), 136));
            drawProjectedLine(context, planeX, planeY, planeSize, cubeX, cubeY, cubeZ, cubeX, -1.0, cubeZ, withAlpha(new Color(uiColorConfigAxisY, true).getRGB(), 136));
            drawProjectedLine(context, planeX, planeY, planeSize, cubeX, cubeY, cubeZ, -1.0, cubeY, cubeZ, withAlpha(new Color(uiColorConfigAxisX, true).getRGB(), 136));
            context.fill(pointX - 2, pointY - 2, pointX + 3, pointY + 3, new Color(uiColorContentBaseTitleText, true).getRGB());
            context.drawText(MinecraftClient.getInstance().textRenderer, "X", planeX + planeSize - 10, planeY + planeSize - 34, new Color(uiColorConfigAxisX, true).getRGB(), false);
            context.drawText(MinecraftClient.getInstance().textRenderer, "Y", planeX + 12, planeY + planeSize - 34, new Color(uiColorConfigAxisY, true).getRGB(), false);
            context.drawText(MinecraftClient.getInstance().textRenderer, "Z", planeX + planeSize / 2 - 3, planeY + 10, new Color(uiColorConfigAxisZ, true).getRGB(), false);
            if (isInsidePlane(mouseX, mouseY)) {
                String hoverLabel = Screen.hasShiftDown() ? "Shift drag = Z" : "Drag = X/Y";
                int hoverLabelWidth = MinecraftClient.getInstance().textRenderer.getWidth(hoverLabel);
                context.fill(planeX + 6, planeY + 6, planeX + 12 + hoverLabelWidth, planeY + 18, withAlpha(uiColorContentBase, 228));
                context.drawText(MinecraftClient.getInstance().textRenderer, hoverLabel, planeX + 9, planeY + 9, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            }
        }

        private void drawZSlider(DrawContext context, double zValue) {
            context.fill(zSliderX, zSliderY, zSliderX + 8, zSliderY + zSliderHeight, withAlpha(uiColorHeader, 120));
            context.drawBorder(zSliderX, zSliderY, 8, zSliderHeight, new Color(uiColorBackgroundBorder, true).getRGB());
            int knobY = toScreenZ(zValue);
            context.fill(zSliderX - 3, knobY - 3, zSliderX + 11, knobY + 4, new Color(uiColorContentBaseTitleText, true).getRGB());
            context.drawText(MinecraftClient.getInstance().textRenderer, formatAxisValue(getMaxVisibleZ()), zSliderX - 10, zSliderY - 10, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            context.drawText(MinecraftClient.getInstance().textRenderer, formatAxisValue(getMinVisibleZ()), zSliderX - 10, zSliderY + zSliderHeight + 4, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        }

        private void drawProjected3dPreview(DrawContext context) {
            int previewSize = 44;
            int previewX = planeX + planeSize - previewSize - 6;
            int previewY = planeY + 6;
            context.fill(previewX, previewY, previewX + previewSize, previewY + previewSize, withAlpha(uiColorContentBase, 196));
            context.drawBorder(previewX, previewY, previewSize, previewSize, withAlpha(uiColorBackgroundBorder, 180));

            double[][] corners = new double[][]{
                    {-1.0, -1.0, -1.0}, {1.0, -1.0, -1.0}, {1.0, 1.0, -1.0}, {-1.0, 1.0, -1.0},
                    {-1.0, -1.0, 1.0}, {1.0, -1.0, 1.0}, {1.0, 1.0, 1.0}, {-1.0, 1.0, 1.0}
            };

            for (int grid = -1; grid <= 1; grid++) {
                drawProjectedLine(context, previewX, previewY, previewSize, -1.0, grid, -1.0, 1.0, grid, -1.0, withAlpha(uiColorBackgroundBorder, 110));
                drawProjectedLine(context, previewX, previewY, previewSize, grid, -1.0, -1.0, grid, 1.0, -1.0, withAlpha(uiColorBackgroundBorder, 110));
                drawProjectedLine(context, previewX, previewY, previewSize, grid, -1.0, -1.0, grid, -1.0, 1.0, withAlpha(uiColorBackgroundBorder, 110));
            }

            drawProjectedEdgeLoop(context, previewX, previewY, previewSize, corners, 0, 1, 2, 3, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedEdgeLoop(context, previewX, previewY, previewSize, corners, 4, 5, 6, 7, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedLine(context, previewX, previewY, previewSize, -1.0, -1.0, -1.0, -1.0, -1.0, 1.0, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedLine(context, previewX, previewY, previewSize, 1.0, -1.0, -1.0, 1.0, -1.0, 1.0, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedLine(context, previewX, previewY, previewSize, 1.0, 1.0, -1.0, 1.0, 1.0, 1.0, withAlpha(uiColorBackgroundBorder, 168));
            drawProjectedLine(context, previewX, previewY, previewSize, -1.0, 1.0, -1.0, -1.0, 1.0, 1.0, withAlpha(uiColorBackgroundBorder, 168));

            drawProjectedAxis(context, previewX, previewY, previewSize, 0.0, 0.0, 0.0, 1.05, 0.0, 0.0, new Color(uiColorConfigAxisX, true).getRGB());
            drawProjectedAxis(context, previewX, previewY, previewSize, 0.0, 0.0, 0.0, 0.0, 1.05, 0.0, new Color(uiColorConfigAxisY, true).getRGB());
            drawProjectedAxis(context, previewX, previewY, previewSize, 0.0, 0.0, 0.0, 0.0, 0.0, 1.05, new Color(uiColorConfigAxisZ, true).getRGB());

            int pointX = projectIsoToScreenX(previewX, previewSize, normalizeToCube(getComponentValue(0), getMinVisibleX(), getMaxVisibleX()), normalizeToCube(getComponentValue(1), getMinVisibleY(), getMaxVisibleY()), normalizeToCube(getComponentValue(2), getMinVisibleZ(), getMaxVisibleZ()));
            int pointY = projectIsoToScreenY(previewY, previewSize, normalizeToCube(getComponentValue(0), getMinVisibleX(), getMaxVisibleX()), normalizeToCube(getComponentValue(1), getMinVisibleY(), getMaxVisibleY()), normalizeToCube(getComponentValue(2), getMinVisibleZ(), getMaxVisibleZ()));
            context.fill(pointX - 2, pointY - 2, pointX + 3, pointY + 3, new Color(uiColorContentBaseTitleText, true).getRGB());
        }

        private void drawProjectedEdgeLoop(DrawContext context, int previewX, int previewY, int previewSize, double[][] corners, int a, int b, int c, int d, int color) {
            drawProjectedLine(context, previewX, previewY, previewSize, corners[a][0], corners[a][1], corners[a][2], corners[b][0], corners[b][1], corners[b][2], color);
            drawProjectedLine(context, previewX, previewY, previewSize, corners[b][0], corners[b][1], corners[b][2], corners[c][0], corners[c][1], corners[c][2], color);
            drawProjectedLine(context, previewX, previewY, previewSize, corners[c][0], corners[c][1], corners[c][2], corners[d][0], corners[d][1], corners[d][2], color);
            drawProjectedLine(context, previewX, previewY, previewSize, corners[d][0], corners[d][1], corners[d][2], corners[a][0], corners[a][1], corners[a][2], color);
        }

        private void drawProjectedAxis(DrawContext context, int previewX, int previewY, int previewSize, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
            drawProjectedLine(context, previewX, previewY, previewSize, x1, y1, z1, x2, y2, z2, withAlpha(color, 220));
        }

        private void drawProjectedSlicePlane(DrawContext context, int previewX, int previewY, int previewSize, double value, char axis, int color) {
            double[][] corners = switch (axis) {
                case 'x' -> new double[][]{{value, -1.0, -1.0}, {value, 1.0, -1.0}, {value, 1.0, 1.0}, {value, -1.0, 1.0}};
                case 'y' -> new double[][]{{-1.0, value, -1.0}, {1.0, value, -1.0}, {1.0, value, 1.0}, {-1.0, value, 1.0}};
                default -> new double[][]{{-1.0, -1.0, value}, {1.0, -1.0, value}, {1.0, 1.0, value}, {-1.0, 1.0, value}};
            };
            drawProjectedEdgeLoop(context, previewX, previewY, previewSize, corners, 0, 1, 2, 3, color);
            for (double guide = -0.5; guide <= 0.5; guide += 0.5) {
                if (axis == 'x') {
                    drawProjectedLine(context, previewX, previewY, previewSize, value, guide, -1.0, value, guide, 1.0, withAlpha(color, 70));
                    drawProjectedLine(context, previewX, previewY, previewSize, value, -1.0, guide, value, 1.0, guide, withAlpha(color, 70));
                } else if (axis == 'y') {
                    drawProjectedLine(context, previewX, previewY, previewSize, guide, value, -1.0, guide, value, 1.0, withAlpha(color, 70));
                    drawProjectedLine(context, previewX, previewY, previewSize, -1.0, value, guide, 1.0, value, guide, withAlpha(color, 70));
                } else {
                    drawProjectedLine(context, previewX, previewY, previewSize, guide, -1.0, value, guide, 1.0, value, withAlpha(color, 70));
                    drawProjectedLine(context, previewX, previewY, previewSize, -1.0, guide, value, 1.0, guide, value, withAlpha(color, 70));
                }
            }
        }

        private void drawProjectedLine(DrawContext context, int previewX, int previewY, int previewSize, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
            int sx1 = projectIsoToScreenX(previewX, previewSize, x1, y1, z1);
            int sy1 = projectIsoToScreenY(previewY, previewSize, x1, y1, z1);
            int sx2 = projectIsoToScreenX(previewX, previewSize, x2, y2, z2);
            int sy2 = projectIsoToScreenY(previewY, previewSize, x2, y2, z2);
            drawThinLine(context, sx1, sy1, sx2, sy2, color);
        }

        private int projectIsoToScreenX(int previewX, int previewSize, double x, double y, double z) {
            double[] projected = projectIso(x, y, z);
            return previewX + (previewSize / 2) + (int) Math.round(projected[0] * (previewSize * 0.22));
        }

        private int projectIsoToScreenY(int previewY, int previewSize, double x, double y, double z) {
            double[] projected = projectIso(x, y, z);
            return previewY + (previewSize / 2) + (int) Math.round(projected[1] * (previewSize * 0.22));
        }

        private double[] projectIso(double x, double y, double z) {
            double screenX = x - y;
            double screenY = ((x + y) * 0.5) - z;
            return new double[]{screenX, screenY, z};
        }

        private double normalizeToCube(double value, double min, double max) {
            double progress = (value - min) / Math.max(0.0001, max - min);
            progress = Math.max(0.0, Math.min(1.0, progress));
            return (progress * 2.0) - 1.0;
        }

        private void drawThinLine(DrawContext context, int x1, int y1, int x2, int y2, int color) {
            int dx = Math.abs(x2 - x1);
            int dy = Math.abs(y2 - y1);
            int sx = x1 < x2 ? 1 : -1;
            int sy = y1 < y2 ? 1 : -1;
            int err = dx - dy;
            int x = x1;
            int y = y1;
            while (true) {
                context.fill(x, y, x + 1, y + 1, color);
                if (x == x2 && y == y2) {
                    break;
                }
                int e2 = err * 2;
                if (e2 > -dy) {
                    err -= dy;
                    x += sx;
                }
                if (e2 < dx) {
                    err += dx;
                    y += sy;
                }
            }
        }

        private boolean isInsidePlane(double mouseX, double mouseY) {
            if (!editorOpen) {
                return false;
            }
            return mouseX >= planeX && mouseX <= planeX + planeSize && mouseY >= planeY && mouseY <= planeY + planeSize;
        }

        private boolean isNearPoint(double mouseX, double mouseY) {
            int pointX = toScreenX(getComponentValue(0));
            int pointY = toScreenY(getComponentValue(1));
            return Math.abs(mouseX - pointX) <= 8.0 && Math.abs(mouseY - pointY) <= 8.0;
        }

        private boolean isNearProjectedPoint(double mouseX, double mouseY) {
            int pointX = axisToScreen(planeX, planeSize, getStringPlaneHorizontalAxis(active3dPlane), getAxisValue(getStringPlaneHorizontalAxis(active3dPlane)));
            int pointY = axisToScreenY(planeY, planeSize, getStringPlaneVerticalAxis(active3dPlane), getAxisValue(getStringPlaneVerticalAxis(active3dPlane)));
            return Math.abs(mouseX - pointX) <= 8.0 && Math.abs(mouseY - pointY) <= 8.0;
        }

        private boolean isInsideZSlider(double mouseX, double mouseY) {
            return editorOpen && mouseX >= zSliderX - 4 && mouseX <= zSliderX + 12 && mouseY >= zSliderY && mouseY <= zSliderY + zSliderHeight;
        }

        private void applyPlaneDrag(double mouseX, double mouseY) {
            setComponentValue(0, snapCoordinate(fromScreenX(mouseX)));
            setComponentValue(1, snapCoordinate(fromScreenY(mouseY)));
        }

        private void applyProjected3dDrag(double mouseX, double mouseY) {
            char plane = active3dPlane;
            if (plane == 'x') {
                setComponentValue(0, snapCoordinate(screenToAxis(planeX, planeSize, 'x', mouseX)));
                setComponentValue(1, snapCoordinate(screenToAxisY(planeY, planeSize, 'y', mouseY)));
            } else if (plane == 'z') {
                setComponentValue(0, snapCoordinate(screenToAxis(planeX, planeSize, 'x', mouseX)));
                setComponentValue(2, snapCoordinate(screenToAxisY(planeY, planeSize, 'z', mouseY)));
            } else if (plane == 'y') {
                setComponentValue(1, snapCoordinate(screenToAxis(planeX, planeSize, 'y', mouseX)));
                setComponentValue(2, snapCoordinate(screenToAxisY(planeY, planeSize, 'z', mouseY)));
            }
        }

        private double getAxisValue(char axis) {
            return switch (axis) {
                case 'x' -> getComponentValue(0);
                case 'y' -> getComponentValue(1);
                default -> getComponentValue(2);
            };
        }

        private double getAxisValue(char axis, double[] values) {
            return switch (axis) {
                case 'x' -> values.length > 0 ? values[0] : 0.0;
                case 'y' -> values.length > 1 ? values[1] : 0.0;
                default -> values.length > 2 ? values[2] : 0.0;
            };
        }

        private double getAxisMin(char axis) {
            return switch (axis) {
                case 'x' -> getMinVisibleX();
                case 'y' -> getMinVisibleY();
                default -> getMinVisibleZ();
            };
        }

        private double getAxisMax(char axis) {
            return switch (axis) {
                case 'x' -> getMaxVisibleX();
                case 'y' -> getMaxVisibleY();
                default -> getMaxVisibleZ();
            };
        }

        private int axisToScreen(int left, int size, char axis, double value) {
            double min = getAxisMin(axis);
            double max = getAxisMax(axis);
            double progress = Math.max(0.0, Math.min(1.0, (value - min) / Math.max(0.0001, max - min)));
            return left + (int) Math.round(progress * size);
        }

        private int axisToScreenY(int top, int size, char axis, double value) {
            double min = getAxisMin(axis);
            double max = getAxisMax(axis);
            double progress = Math.max(0.0, Math.min(1.0, (value - min) / Math.max(0.0001, max - min)));
            if (axis == 'y' ? profile.yPositiveUp : true) {
                progress = 1.0 - progress;
            }
            return top + (int) Math.round(progress * size);
        }

        private double screenToAxis(int left, int size, char axis, double mouseX) {
            double progress = Math.max(0.0, Math.min(1.0, (mouseX - left) / Math.max(1.0, size)));
            return getAxisMin(axis) + (getAxisMax(axis) - getAxisMin(axis)) * progress;
        }

        private double screenToAxisY(int top, int size, char axis, double mouseY) {
            double progress = Math.max(0.0, Math.min(1.0, (mouseY - top) / Math.max(1.0, size)));
            if (axis == 'y' ? profile.yPositiveUp : true) {
                progress = 1.0 - progress;
            }
            return getAxisMin(axis) + (getAxisMax(axis) - getAxisMin(axis)) * progress;
        }

        private void applyPlanePan(double mouseX, double mouseY) {
            double unitsPerPixel = (viewSpan * 2.0) / Math.max(1.0, planeSize);
            char horizontalAxis = componentCount >= 3 ? getStringPlaneHorizontalAxis(active3dPlane) : 'x';
            char verticalAxis = componentCount >= 3 ? getStringPlaneVerticalAxis(active3dPlane) : 'y';
            double nextHorizontal = getAxisCenter(horizontalAxis, true) - ((mouseX - panStartMouseX) * unitsPerPixel);
            double yDelta = (mouseY - panStartMouseY) * unitsPerPixel;
            double nextVertical = axisUsesPositiveUp(verticalAxis) ? getAxisCenter(verticalAxis, false) + yDelta : getAxisCenter(verticalAxis, false) - yDelta;
            setAxisCenter(horizontalAxis, nextHorizontal);
            setAxisCenter(verticalAxis, nextVertical);
        }

        private void applyZDrag(double mouseY) {
            if (componentCount >= 3) {
                setComponentValue(2, fromScreenZ(mouseY));
            }
        }

        private void adjustZoom(int direction) {
            if (profile == CoordinateProfile.NORMALIZED) {
                return;
            }
            double factor = direction < 0 ? 0.82 : 1.22;
            viewSpan = Math.max(0.25, Math.min(512.0, viewSpan * factor));
        }

        private int getGridDivisions() {
            return profile == CoordinateProfile.NORMALIZED ? 10 : 12;
        }

        private int getMajorGridStride() {
            return profile == CoordinateProfile.NORMALIZED ? 2 : 3;
        }

        private boolean isMajorGridValue(double value, double majorStep) {
            if (majorStep <= 0.0) {
                return false;
            }
            double snapped = Math.round(value / majorStep) * majorStep;
            return Math.abs(snapped - value) <= Math.max(0.0001, majorStep * 0.05);
        }

        private double getGridStep() {
            if (profile == CoordinateProfile.NORMALIZED) {
                return 0.1;
            }
            double raw = (viewSpan * 2.0) / getGridDivisions();
            if (raw <= 0.0) {
                return 1.0;
            }
            double pow = Math.pow(10.0, Math.floor(Math.log10(raw)));
            double normalized = raw / pow;
            double snapped = normalized <= 1.0 ? 1.0 : normalized <= 2.0 ? 2.0 : normalized <= 5.0 ? 5.0 : 10.0;
            return snapped * pow;
        }

        private double snapCoordinate(double value) {
            if (Screen.hasShiftDown()) {
                return value;
            }
            double step = getGridStep();
            if (step <= 0.0) {
                return value;
            }
            return Math.round(value / step) * step;
        }

        private int getClosestGridScreenX(double mouseX) {
            return toScreenX(snapCoordinate(fromScreenX(mouseX)));
        }

        private int getClosestGridScreenY(double mouseY) {
            return toScreenY(snapCoordinate(fromScreenY(mouseY)));
        }

        private void setComponentValue(int index, double value) {
            if (index < 0 || index >= fields.length) {
                return;
            }
            boolean whole = textLooksWholeNumber(fields[index]);
            if (whole) {
                value = Math.round(value);
            } else {
                double step = Screen.hasShiftDown() ? 0.01 : 0.1;
                value = Math.round(value / step) * step;
            }
            suppress = true;
            fields[index].setText(whole ? String.valueOf((int) Math.round(value)) : String.format(Locale.ROOT, "%.2f", value));
            suppress = false;
            publish(index, "");
        }

        private double getComponentValue(int index) {
            if (index < 0 || index >= fields.length) {
                return 0.0;
            }
            try {
                return Double.parseDouble(fields[index].getText());
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }

        private int toScreenX(double value) {
            double min = getMinVisibleX();
            double max = getMaxVisibleX();
            double progress = Math.max(0.0, Math.min(1.0, (value - min) / Math.max(0.0001, max - min)));
            return planeX + (int) Math.round(progress * planeSize);
        }

        private int toScreenY(double value) {
            double min = getMinVisibleY();
            double max = getMaxVisibleY();
            double progress = Math.max(0.0, Math.min(1.0, (value - min) / Math.max(0.0001, max - min)));
            if (profile.yPositiveUp) {
                progress = 1.0 - progress;
            }
            return planeY + (int) Math.round(progress * planeSize);
        }

        private int toScreenZ(double value) {
            double min = getMinVisibleZ();
            double max = getMaxVisibleZ();
            double progress = Math.max(0.0, Math.min(1.0, (value - min) / Math.max(0.0001, max - min)));
            return zSliderY + zSliderHeight - (int) Math.round(progress * zSliderHeight);
        }

        private double fromScreenX(double mouseX) {
            double progress = Math.max(0.0, Math.min(1.0, (mouseX - planeX) / Math.max(1.0, planeSize)));
            return getMinVisibleX() + (getMaxVisibleX() - getMinVisibleX()) * progress;
        }

        private double fromScreenY(double mouseY) {
            double progress = Math.max(0.0, Math.min(1.0, (mouseY - planeY) / Math.max(1.0, planeSize)));
            if (profile.yPositiveUp) {
                progress = 1.0 - progress;
            }
            return getMinVisibleY() + (getMaxVisibleY() - getMinVisibleY()) * progress;
        }

        private double fromScreenZ(double mouseY) {
            double progress = 1.0 - Math.max(0.0, Math.min(1.0, (mouseY - zSliderY) / Math.max(1.0, zSliderHeight)));
            return getMinVisibleZ() + (getMaxVisibleZ() - getMinVisibleZ()) * progress;
        }

        private double getMinVisibleX() {
            return profile == CoordinateProfile.NORMALIZED ? 0.0 : viewCenterX - viewSpan;
        }

        private double getMaxVisibleX() {
            return profile == CoordinateProfile.NORMALIZED ? 1.0 : viewCenterX + viewSpan;
        }

        private double getMinVisibleY() {
            return profile == CoordinateProfile.NORMALIZED ? 0.0 : viewCenterY - viewSpan;
        }

        private double getMaxVisibleY() {
            return profile == CoordinateProfile.NORMALIZED ? 1.0 : viewCenterY + viewSpan;
        }

        private double getMinVisibleZ() {
            if (profile == CoordinateProfile.NORMALIZED) {
                return 0.0;
            }
            if (profile == CoordinateProfile.VECTOR) {
                return -viewSpan;
            }
            return viewCenterZ - viewSpan;
        }

        private double getMaxVisibleZ() {
            if (profile == CoordinateProfile.NORMALIZED) {
                return 1.0;
            }
            if (profile == CoordinateProfile.VECTOR) {
                return viewSpan;
            }
            return viewCenterZ + viewSpan;
        }

        private double getAxisCenter(char axis, boolean horizontal) {
            return switch (axis) {
                case 'x' -> panStartCenterX;
                case 'y' -> horizontal ? panStartCenterY : panStartCenterY;
                default -> panStartCenterZ;
            };
        }

        private void setAxisCenter(char axis, double value) {
            switch (axis) {
                case 'x' -> viewCenterX = value;
                case 'y' -> viewCenterY = value;
                default -> viewCenterZ = value;
            }
        }

        private boolean axisUsesPositiveUp(char axis) {
            return axis == 'y' ? profile.yPositiveUp : true;
        }

        private char getStringPlaneFixedAxis(char plane) {
            return switch (plane) {
                case 'z' -> 'y';
                case 'y' -> 'x';
                default -> 'z';
            };
        }

        private String getStringPlaneFixedAxisLabel(char plane) {
            char axis = getStringPlaneFixedAxis(plane);
            return Character.toUpperCase(axis) + " " + formatCoordinateValue(getAxisValue(axis));
        }

        private String formatCoordinateValue(double value) {
            return textLooksWholeNumber(fields[0]) ? String.valueOf((int) Math.round(value)) : String.format(Locale.ROOT, "%.2f", value);
        }

        private String formatAxisValue(double value) {
            return Math.abs(value) >= 10.0 ? String.valueOf((int) Math.round(value)) : String.format(Locale.ROOT, "%.1f", value);
        }

        private double roundSpan(double rawSpan) {
            if (rawSpan <= 1.0) {
                return 1.0;
            }
            double pow = Math.pow(10.0, Math.floor(Math.log10(rawSpan)));
            return Math.ceil(rawSpan / pow) * pow;
        }

        private CoordinateProfile inferCoordinateProfile(String path) {
            String normalized = path.toLowerCase(Locale.ROOT);
            boolean normalizedRange = true;
            for (TextFieldWidget field : fields) {
                try {
                    double value = Double.parseDouble(field.getText());
                    normalizedRange &= value >= 0.0 && value <= 1.0;
                } catch (NumberFormatException ignored) {
                    normalizedRange = false;
                }
            }
            if (normalized.contains("anchor") || normalized.contains("normalized") || normalizedRange) {
                return CoordinateProfile.NORMALIZED;
            }
            if (normalized.contains("velocity") || normalized.contains("vector") || normalized.contains("spawn")) {
                return CoordinateProfile.VECTOR;
            }
            if (normalized.contains("offset")) {
                return CoordinateProfile.OFFSET;
            }
            if (normalized.contains("position")) {
                return CoordinateProfile.POSITION;
            }
            return componentCount >= 3 ? CoordinateProfile.WORLD : CoordinateProfile.OFFSET;
        }
    }

    private static class StringRangeEntry extends ConfigEntry {
        private final String label;
        private final TextFieldWidget minField;
        private final TextFieldWidget maxField;
        private final StringConsumer onChange;

        private StringRangeEntry(String label, String initialValue, StringConsumer onChange) {
            this.label = label;
            this.onChange = onChange;
            String[] parts = initialValue.split("\\s*-\\s*", 2);
            this.minField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 54, 18, Text.literal("Min"));
            this.maxField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 54, 18, Text.literal("Max"));
            this.minField.setText(parts.length > 0 ? parts[0] : "");
            this.maxField.setText(parts.length > 1 ? parts[1] : "");
            this.minField.setChangedListener(value -> publish());
            this.maxField.setChangedListener(value -> publish());
        }

        private void publish() {
            onChange.accept(minField.getText().trim() + "-" + maxField.getText().trim());
        }

        @Override
        public void tick() {
            minField.tick();
            maxField.tick();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return focusClickedField(mouseX, mouseY, button, minField, maxField);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return minField.keyPressed(keyCode, scanCode, modifiers) || maxField.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return minField.charTyped(chr, modifiers) || maxField.charTyped(chr, modifiers);
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            if (isMouseOverField(minField, mouseX, mouseY)) {
                return nudgeNumericTextField(minField, amount > 0 ? 1.0 : -1.0, textLooksWholeNumber(minField));
            }
            if (isMouseOverField(maxField, mouseX, mouseY)) {
                return nudgeNumericTextField(maxField, amount > 0 ? 1.0 : -1.0, textLooksWholeNumber(maxField));
            }
            return false;
        }

        @Override
        public void setFocused(boolean focused) {
            if (!focused) {
                minField.setFocused(false);
                maxField.setFocused(false);
            }
        }

        @Override
        public boolean isFocused() {
            return minField.isFocused() || maxField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, "Range string", x + 18, y);
            int minX = getPinnedFieldStartX(x, entryWidth, 108);
            minField.setX(minX);
            minField.setY(y + 18);
            minField.render(context, mouseX, mouseY, delta);
            maxField.setX(minX + 62);
            maxField.setY(y + 18);
            maxField.render(context, mouseX, mouseY, delta);
            drawDeleteButton(context, x, y, entryWidth);
        }
    }

    private static class SliderEntry extends ConfigEntry {
        private final String label;
        private final SliderConfig config;
        private final TextFieldWidget valueField;
        private final DoubleConsumer onChange;
        private double value;
        private double viewMin;
        private double viewMax;
        private boolean suppressFieldUpdate;
        private int sliderX;
        private int sliderY;
        private int sliderWidth;
        private boolean dragging;
        private double dragStartMouseX;
        private double dragStartValue;

        private SliderEntry(String label, double initialValue, SliderConfig config, DoubleConsumer onChange) {
            this.label = label;
            this.value = initialValue;
            this.config = config;
            this.onChange = onChange;
            this.viewMin = Math.min(config.min(), initialValue);
            this.viewMax = Math.max(config.max(), initialValue);
            this.valueField = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, 0, 0, 74, 18, Text.literal(label));
            this.valueField.setText(config.wholeNumber() ? String.valueOf((int) Math.round(initialValue)) : String.valueOf(initialValue));
            this.valueField.setChangedListener(raw -> {
                if (suppressFieldUpdate) {
                    return;
                }
                try {
                    setValue(Double.parseDouble(raw), true);
                } catch (NumberFormatException ignored) {
                }
            });
        }

        private void setValue(double nextValue, boolean publish) {
            value = config.wholeNumber() ? Math.round(nextValue) : nextValue;
            ensureSliderBoundsInclude(value);
            suppressFieldUpdate = true;
            valueField.setText(config.wholeNumber() ? String.valueOf((int) Math.round(value)) : String.format(Locale.ROOT, "%.2f", value));
            suppressFieldUpdate = false;
            if (publish) {
                onChange.accept(value);
            }
        }

        private void ensureSliderBoundsInclude(double target) {
            double span = Math.max(0.0001, viewMax - viewMin);
            double padding = Math.max(config.step(), span * 0.15);
            if (target < viewMin) {
                viewMin = target - padding;
            }
            if (target > viewMax) {
                viewMax = target + padding;
            }
            if (viewMin > config.min()) {
                viewMin = config.min();
            }
            if (viewMax < config.max()) {
                viewMax = config.max();
            }
        }

        public void tick() {
            valueField.tick();
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (valueField.mouseClicked(mouseX, mouseY, button)) {
                dragging = false;
                return true;
            }
            if (button == 0 && isOnTrack(mouseX, mouseY)) {
                dragging = true;
                dragStartMouseX = mouseX;
                dragStartValue = value;
                UiSoundHelper.playButtonClick();
                applyDrag(mouseX);
                return true;
            }
            return false;
        }

        @Override
        public boolean handleMouseDrag(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (button != 0 || !dragging) {
                return false;
            }
            applyDrag(mouseX);
            return true;
        }

        @Override
        public boolean handleMouseRelease(double mouseX, double mouseY, int button) {
            boolean wasDragging = dragging;
            dragging = false;
            return wasDragging;
        }

        @Override
        public boolean supportsMouseWheelInput() {
            return true;
        }

        @Override
        public boolean handleMouseWheel(double mouseX, double mouseY, double amount) {
            if (amount == 0) {
                return false;
            }
            if (isMouseOverField(valueField, mouseX, mouseY)) {
                double step = getPrecisionStep();
                boolean changed = nudgeNumericTextField(valueField, amount > 0 ? step : -step, config.wholeNumber());
                if (changed) {
                    UiSoundHelper.playDialClick();
                }
                return changed;
            }
            return false;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (!valueField.isFocused() && keyCode == GLFW.GLFW_KEY_LEFT) {
                setValue(value - getPrecisionStep(), true);
                UiSoundHelper.playDialClick();
                return true;
            }
            if (!valueField.isFocused() && keyCode == GLFW.GLFW_KEY_RIGHT) {
                setValue(value + getPrecisionStep(), true);
                UiSoundHelper.playDialClick();
                return true;
            }
            return valueField.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return valueField.charTyped(chr, modifiers);
        }

        @Override
        public void setFocused(boolean focused) {
            valueField.setFocused(focused);
        }

        @Override
        public boolean isFocused() {
            return valueField.isFocused();
        }

        @Override
        public List<String> getDeleteTargets() {
            return List.of(label);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            int fieldWidth = entryWidth < 430 ? 62 : 74;
            int fieldX = getPinnedFieldStartX(x, entryWidth, fieldWidth);
            sliderX = x + Math.min(156, Math.max(98, entryWidth / 4));
            sliderY = y + 25;
            sliderWidth = Math.max(72, (fieldX - 14) - sliderX);
            ensureSliderBoundsInclude(value);
            double range = Math.max(0.0001, viewMax - viewMin);
            double progress = (value - viewMin) / range;
            int knobX = sliderX + (int) Math.round(progress * sliderWidth);
            drawEntryCard(context, x, y, entryWidth, entryHeight, hovered);
            drawEntryText(context, label, "Slider input", x + 18, y);
            context.fill(sliderX, sliderY, sliderX + sliderWidth, sliderY + 6, withAlpha(uiColorHeader, 120));
            context.fill(sliderX, sliderY, knobX, sliderY + 6, withAlpha(uiColorHeaderStripe, 210));
            context.drawBorder(sliderX, sliderY, sliderWidth, 6, new Color(uiColorBackgroundBorder, true).getRGB());
            context.fill(knobX - 3, sliderY - 3, knobX + 3, sliderY + 9, new Color(uiColorContentBaseTitleText, true).getRGB());
            context.drawText(MinecraftClient.getInstance().textRenderer, config.unit().isBlank() ? String.valueOf(config.wholeNumber() ? (int) Math.round(value) : value) : (config.wholeNumber() ? (int) Math.round(value) : String.format(Locale.ROOT, "%.2f", value)) + " " + config.unit(), sliderX, y + 12, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            valueField.setWidth(fieldWidth);
            valueField.setX(fieldX);
            valueField.setY(y + 18);
            valueField.render(context, mouseX, mouseY, delta);
            drawDeleteButton(context, x, y, entryWidth);
        }

        private boolean isOnTrack(double mouseX, double mouseY) {
            return mouseX >= sliderX - 6 && mouseX <= sliderX + sliderWidth + 6 && mouseY >= sliderY - 4 && mouseY <= sliderY + 10;
        }

        private void applyDrag(double mouseX) {
            if (Screen.hasShiftDown()) {
                double nextValue = dragStartValue + ((mouseX - dragStartMouseX) * getPrecisionStep() * 0.18);
                if (config.wholeNumber()) {
                    nextValue = Math.round(nextValue);
                } else {
                    double step = getPrecisionStep();
                    nextValue = Math.round(nextValue / step) * step;
                }
                setValue(nextValue, true);
                return;
            }
            double progress = Math.max(0.0, Math.min(1.0, (mouseX - sliderX) / Math.max(1.0, sliderWidth)));
            double nextValue = viewMin + (viewMax - viewMin) * progress;
            if (config.wholeNumber()) {
                nextValue = Math.round(nextValue);
            } else {
                double step = getPrecisionStep();
                if (step > 0) {
                    nextValue = Math.round(nextValue / step) * step;
                }
            }

            setValue(nextValue, true);
        }

        private double getPrecisionStep() {
            if (config.wholeNumber()) {
                return config.step();
            }
            return Screen.hasShiftDown() ? Math.max(0.01, config.step() / 8.0) : config.step();
        }
    }

    @FunctionalInterface
    private interface BooleanConsumer {
        void accept(boolean value);
    }

    @FunctionalInterface
    private interface BooleanPairConsumer {
        void accept(String enablePath, String disablePath, boolean enabled);
    }

    @FunctionalInterface
    private interface BooleanPathConsumer {
        void accept(String path, boolean value);
    }

    @FunctionalInterface
    private interface TriStateConsumer {
        void accept(String autoPath, String enablePath, String disablePath, TriStateMode mode);
    }

    @FunctionalInterface
    private interface EnabledModeConsumer {
        void accept(String enabledPath, String modePath, boolean enabled, String mode);
    }

    @FunctionalInterface
    private interface IntConsumer {
        void accept(int value);
    }

    @FunctionalInterface
    private interface LongConsumer {
        void accept(long value);
    }

    @FunctionalInterface
    private interface StringConsumer {
        void accept(String value);
    }

    @FunctionalInterface
    private interface JsonElementConsumer {
        void accept(JsonElement value);
    }

    @FunctionalInterface
    private interface DoubleConsumer {
        void accept(double value);
    }

    @FunctionalInterface
    private interface JsonValueConsumer {
        void accept(String path, JsonElement value);
    }

    @FunctionalInterface
    private interface JsonValuePathConsumer {
        void accept(String path, JsonElement value);
    }

    private record ComponentBinding(String label, String path, double value) {
    }

    private static class CoordinateClusterBuilder {
        private final String base;
        private final int dimension;
        private final Map<String, String> paths = new LinkedHashMap<>();
        private final Map<String, Double> values = new LinkedHashMap<>();

        private CoordinateClusterBuilder(String base, int dimension) {
            this.base = base;
            this.dimension = dimension;
        }

        private boolean isComplete() {
            return dimension >= 3
                    ? paths.keySet().containsAll(List.of("x", "y", "z"))
                    : paths.keySet().containsAll(List.of("x", "y"));
        }

        private boolean conflictsWith(Set<String> consumedKeys) {
            for (String key : sourceKeys()) {
                if (consumedKeys.contains(key)) {
                    return true;
                }
            }
            return false;
        }

        private Set<String> sourceKeys() {
            Set<String> keys = new LinkedHashSet<>();
            for (String path : paths.values()) {
                String key = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                keys.add(key);
            }
            return keys;
        }

        private CoordinateClusterPoint toPoint() {
            List<String> orderedPaths = new ArrayList<>();
            double[] orderedValues = new double[dimension];
            orderedPaths.add(paths.get("x"));
            orderedValues[0] = values.get("x");
            orderedPaths.add(paths.get("y"));
            orderedValues[1] = values.get("y");
            if (dimension >= 3) {
                orderedPaths.add(paths.get("z"));
                orderedValues[2] = values.get("z");
            }
            boolean whole = values.values().stream().allMatch(value -> Math.rint(value) == value);
            return new CoordinateClusterPoint(humanizePointName(base), orderedPaths, orderedValues, dimension, whole);
        }

        private String humanizePointName(String rawBase) {
            String display = rawBase.replace('_', ' ').replace('-', ' ').replaceAll("([a-z0-9])([A-Z])", "$1 $2");
            if (display.isBlank()) {
                return rawBase;
            }
            return Character.toUpperCase(display.charAt(0)) + display.substring(1);
        }
    }

    private record GroupedField(Set<String> keys, ConfigEntry entry) {
    }

    private record EntryRenderBounds(int x, int width) {
    }

    private record SectionBlock(int startIndex, int endIndex, String title, int entryCount) {
    }

    private record ComponentMatch(String base, String component) {
    }

    private record PrefixComponentMatch(String base, String component) {
    }

    private record OptionFamily(List<String> pathHints, List<String> values) {
        private boolean matchesPath(String path) {
            for (String hint : pathHints) {
                if (path.contains(hint)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record SliderConfig(double min, double max, double step, boolean wholeNumber, String unit) {
    }

    private record CoordinateClusterPoint(String name, List<String> paths, double[] values, int dimension, boolean isWholeNumber) {
    }

    private record SearchDropdownEntry(String path, String primary, String secondary) {
    }

    private record SearchDropdownBounds(int x, int y, int width, int height) {
        private int right() {
            return x + width;
        }

        private int bottom() {
            return y + height;
        }
    }

    private record PendingTooltip(int mouseX, int mouseY, List<Text> lines) {
    }

    private record SectionInspectorData(List<String> inheritanceChain, String rawJson) {
    }

    private enum TimeUnitMode {
        MILLISECONDS("ms", true),
        SECONDS("sec", false),
        MINUTES("min", false),
        TICKS("tick", true);

        private final String label;
        private final boolean wholeNumber;

        TimeUnitMode(String label, boolean wholeNumber) {
            this.label = label;
            this.wholeNumber = wholeNumber;
        }

        private TimeUnitMode next() {
            TimeUnitMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private enum TriStateMode {
        AUTO,
        ON,
        OFF
    }

    @FunctionalInterface
    private interface NumericUpdateConsumer {
        void accept(String path, double value, boolean wholeNumber);
    }
}
