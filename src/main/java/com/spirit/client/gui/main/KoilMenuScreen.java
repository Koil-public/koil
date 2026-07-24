package com.spirit.client.gui.main;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.spirit.Main;
import com.spirit.client.gui.UiSoundHelper;
import com.spirit.client.gui.console.ConsoleScreen;
import com.spirit.client.gui.ide.FileExplorerScreen;
import com.spirit.client.gui.main.elements.MenuBookEntry;
import com.spirit.client.gui.main.elements.SparkleButtonWidget;
import com.spirit.client.gui.update.elements.UpdateScreenData;
import com.spirit.koil.api.console.ConsoleChannel;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.util.application.WindowManager;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import com.spirit.koil.api.util.file.media.image.ImageTextureService;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.List;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static com.spirit.Main.*;
import static com.spirit.koil.api.design.uiColorVal.*;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTexture;

@Environment(EnvType.CLIENT)
public class KoilMenuScreen extends Screen {
    private static final Identifier LOGO_TEXTURE = loadExternalPngTexture(uiImageDirectory, "icon.png");
    private static final Identifier FUNDING_TEXTURE = loadExternalPngTexture(uiImageDirectory, "koil_funding.png");
    private static final int PANEL_HOME = 1;
    private static final int PANEL_WIKI = 5;
    private static final int PANEL_CHANGELOG = 7;
    private static final int PANEL_TOOLS = 8;
    private static final int PANEL_BOOK = 9;
    private static final int PANEL_SETTINGS = 10;
    private static final int PANEL_DEBUG = 11;
    private static final int PANEL_DEBUG_VISUALS = 12;
    private static final int PANEL_DEBUG_CONSOLE = 13;
    private final List<ClickableWidget> globalButtons;
    private final List<ClickableWidget> sidebarButtons;
    private final List<ClickableWidget> debugButtons;
    private final List<ClickableWidget> toolButtons;
    private final List<MenuBookEntry> books = new ArrayList<>();
    private final List<ToolEntry> detectedTools = new ArrayList<>();
    private int currentPanel;
    private int scrollOffset;
    private int maxScrollOffset;
    private MenuBookEntry selectedBooks;
    private final String configFilePath = "./koil/sys/config.json";
    private CheckboxWidget debugCheckbox;
    private CheckboxWidget designMusicCheckbox;
    private ButtonWidget uninstallKoilButton;
    private ButtonWidget downloadDummyFilesButton;
    private ButtonWidget deleteBootstrapFilesButton;

    public KoilMenuScreen() {
        super(Text.literal("Title"));
        this.globalButtons = new ArrayList<>();
        this.sidebarButtons = new ArrayList<>();
        this.debugButtons = new ArrayList<>();
        this.toolButtons = new ArrayList<>();
        this.currentPanel = PANEL_HOME;
        this.scrollOffset = 0;

        books.add(new MenuBookEntry(loadExternalPngTexture(uiImageDirectory, "start_book.png"), "Manager Guide", "SpiritXIV", "Overview of the manager menu, navigation, and core Koil tools.", "SpiritXIV", "./koil/wiki/help_book.json"));
        books.add(new MenuBookEntry(loadExternalPngTexture(uiImageDirectory, "code_book.png"), "Content Quickstart", "SpiritXIV", "Starting guide for Koil's world-scoped Content registry.", "SpiritXIV", "./koil/wiki/registry_begin.json"));
        books.add(new MenuBookEntry(loadExternalPngTexture(uiImageDirectory, "code_book.png"), "Blocks and Items Guide", "SpiritXIV", "Guide for Content block and item definitions.", "SpiritXIV", "./koil/wiki/registry_blocksitems.json"));
        books.add(new MenuBookEntry(loadExternalPngTexture(uiImageDirectory, "code_book.png"), "Entities Guide", "SpiritXIV", "Guide for Content entity definitions.", "SpiritXIV", "./koil/wiki/registry_entities.json"));
        books.add(new MenuBookEntry(loadExternalPngTexture(uiImageDirectory, "code_book.png"), "Effects Guide", "SpiritXIV", "Guide for Content effect definitions.", "SpiritXIV", "./koil/wiki/registry_effects.json"));
        books.add(new MenuBookEntry(loadExternalPngTexture(uiImageDirectory, "code_book.png"), "Enchantments Guide", "SpiritXIV", "Guide for Content enchantment definitions.", "SpiritXIV", "./koil/wiki/registry_enchantments.json"));
        books.add(new MenuBookEntry(loadExternalPngTexture(uiImageDirectory, "code_book.png"), "Commands Guide", "SpiritXIV", "Guide for Content commands and reload tools.", "SpiritXIV", "./koil/wiki/registry_commands.json"));
        books.add(new MenuBookEntry(loadExternalPngTexture(uiImageDirectory, "code_book.png"), "Particles Guide", "SpiritXIV", "Guide for Content particle definitions.", "SpiritXIV", "./koil/wiki/registry_particles.json"));
    }

    @Override
    protected void init() {
        super.init();
        clearWidgetList(globalButtons);
        clearWidgetList(sidebarButtons);
        clearWidgetList(debugButtons);
        clearWidgetList(toolButtons);
        removeSettingsWidgets();

        initGlobalButtons();
        initSidebarButtons();
        refreshDetectedTools();
        if (currentPanel == PANEL_TOOLS) {
            initToolButtons();
        }
    }

    private void initGlobalButtons() {
        ButtonWidget homeButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Home"), button -> switchPanel(PANEL_HOME))
                .dimensions(this.width - 126, 36, 54, 20)
                .build());
        globalButtons.add(homeButton);

        JsonElement debugElement = JSONFileEditor.getValueFromJson(configFilePath, "debug");
        if (debugElement != null && debugElement.isJsonPrimitive() && debugElement.getAsBoolean()) {
            ButtonWidget debugButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Debug"), button -> switchPanel(PANEL_DEBUG))
                    .dimensions(this.width - 68, 36, 54, 20)
                    .build());
            globalButtons.add(debugButton);
        }
    }

    private void initSidebarButtons() {
        int buttonX = 10;
        int buttonWidth = 100;
        int buttonHeight = 20;
        int buttonY = 70;
        int buttonSpacing = 30;

        File fundingTextureFile = new File(uiImageDirectory, "koil_funding.png");
        ImageTextureService.markFilePersistent(fundingTextureFile);

        SparkleButtonWidget fundingButton = this.addDrawableChild(new SparkleButtonWidget(
                buttonX, buttonY,
                20, 20, 0, 0, 20,
                FUNDING_TEXTURE, 32, 64,
                button -> {
                    assert this.client != null;
                    this.client.setScreen(new FundingScreen(this));
                }
        ));
        fundingButton.setTooltip(Tooltip.of(Text.literal("Donate to the project's Development!")));
        sidebarButtons.add(fundingButton);

        boolean debugSidebarMode = currentPanel == PANEL_DEBUG || currentPanel == PANEL_DEBUG_VISUALS || currentPanel == PANEL_DEBUG_CONSOLE;

        if (debugSidebarMode) {
            ButtonWidget debugHomeButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Debug Home"), button -> switchPanel(PANEL_DEBUG))
                    .dimensions(buttonX + 26, buttonY, 74, buttonHeight)
                    .build());
            ButtonWidget debugVisualsButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Visuals"), button -> switchPanel(PANEL_DEBUG_VISUALS))
                    .dimensions(buttonX, buttonY + buttonSpacing, buttonWidth, buttonHeight)
                    .build());
            ButtonWidget debugConsoleButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Console"), button -> switchPanel(PANEL_DEBUG_CONSOLE))
                    .dimensions(buttonX, buttonY + (buttonSpacing * 2), buttonWidth, buttonHeight)
                    .build());

            sidebarButtons.add(debugHomeButton);
            sidebarButtons.add(debugVisualsButton);
            sidebarButtons.add(debugConsoleButton);
            return;
        }

        ButtonWidget welcomeButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Welcome"), button -> switchPanel(PANEL_HOME))
                .dimensions(buttonX + 26, buttonY, 74, buttonHeight)
                .build());
        ButtonWidget panelChangeLog = this.addDrawableChild(ButtonWidget.builder(Text.literal("Change Log"), button -> switchPanel(PANEL_CHANGELOG))
                .dimensions(buttonX, buttonY + buttonSpacing, buttonWidth, buttonHeight)
                .build());
        ButtonWidget panelTools = this.addDrawableChild(ButtonWidget.builder(Text.literal("Tools"), button -> switchPanel(PANEL_TOOLS))
                .dimensions(buttonX, buttonY + (buttonSpacing * 2), buttonWidth, buttonHeight)
                .build());
        ButtonWidget panelSettings = this.addDrawableChild(ButtonWidget.builder(Text.literal("Settings"), button -> switchPanel(PANEL_SETTINGS))
                .dimensions(buttonX, buttonY + (buttonSpacing * 3), buttonWidth, buttonHeight)
                .build());

        sidebarButtons.add(welcomeButton);
        sidebarButtons.add(panelChangeLog);
        sidebarButtons.add(panelTools);
        sidebarButtons.add(panelSettings);
    }

    private void switchPanel(int panelId) {
        removeSettingsWidgets();
        clearWidgetList(debugButtons);
        clearWidgetList(toolButtons);
        this.currentPanel = panelId;
        this.scrollOffset = 0;
        this.maxScrollOffset = 0;
        this.init();
    }

    private void clearWidgetList(List<? extends ClickableWidget> widgets) {
        for (ClickableWidget widget : widgets) {
            this.remove(widget);
        }
        widgets.clear();
    }

    private void removeSettingsWidgets() {
        if (debugCheckbox != null && this.children().contains(debugCheckbox)) {
            this.remove(debugCheckbox);
        }
        if (designMusicCheckbox != null && this.children().contains(designMusicCheckbox)) {
            this.remove(designMusicCheckbox);
        }
        if (uninstallKoilButton != null && this.children().contains(uninstallKoilButton)) {
            this.remove(uninstallKoilButton);
        }
        if (downloadDummyFilesButton != null && this.children().contains(downloadDummyFilesButton)) {
            this.remove(downloadDummyFilesButton);
        }
        if (deleteBootstrapFilesButton != null && this.children().contains(deleteBootstrapFilesButton)) {
            this.remove(deleteBootstrapFilesButton);
        }
        debugCheckbox = null;
        designMusicCheckbox = null;
        uninstallKoilButton = null;
        downloadDummyFilesButton = null;
        deleteBootstrapFilesButton = null;
    }

    private void refreshDetectedTools() {
        detectedTools.clear();
        Set<String> classNames = new LinkedHashSet<>();
        classNames.add("com.spirit.client.gui.ide.FileExplorerScreen");
        classNames.add("com.spirit.client.gui.ide.FileEditorScreen");
        classNames.add("com.spirit.client.gui.ModConfigScreen");
        classNames.add("com.spirit.client.gui.ide.KoilImageEditorScreen");
        classNames.add("com.spirit.client.gui.ide.KoilVideoEditorScreen");
        classNames.add("com.spirit.client.gui.ide.KoilAudioEditorScreen");
        classNames.add("com.spirit.client.gui.tool.KoilToolScreen");
        classNames.add("com.spirit.client.gui.tool.PackageBuilderScreen");
        classNames.add("com.spirit.client.gui.performance.PerformanceOptimizerScreen");

        classNames.addAll(scanToolScreenClassNames());

        for (String className : classNames) {
            ToolEntry entry = buildToolEntry(className);
            if (entry != null && !containsTool(entry.className)) {
                detectedTools.add(entry);
            }
        }
        detectedTools.sort(Comparator.comparing(entry -> entry.title.toLowerCase()));
    }

    private boolean containsTool(String className) {
        for (ToolEntry entry : detectedTools) {
            if (entry.className.equals(className)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> scanToolScreenClassNames() {
        Set<String> classNames = new LinkedHashSet<>();
        try {
            CodeSource codeSource = Main.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return classNames;
            }
            Path sourcePath = Paths.get(codeSource.getLocation().toURI());
            if (!Files.exists(sourcePath) || !sourcePath.toString().endsWith(".jar")) {
                return classNames;
            }

            try (JarFile jarFile = new JarFile(sourcePath.toFile())) {
                var entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.endsWith(".class")) {
                        continue;
                    }
                    if (!name.startsWith("com/spirit/client/gui/")) {
                        continue;
                    }
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    if (isToolScreenClassName(className)) {
                        classNames.add(className);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return classNames;
    }

    private boolean isToolScreenClassName(String className) {
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        if (!simpleName.endsWith("Screen")) {
            return false;
        }
        if (simpleName.equals("KoilMenuScreen") || simpleName.equals("FundingScreen")) {
            return false;
        }
        String lower = className.toLowerCase();
        return lower.contains("tool") || lower.contains("editor") || lower.contains("explorer") || lower.contains("config");
    }

    private ToolEntry buildToolEntry(String className) {
        try {
            Class<?> rawClass = Class.forName(className);
            if (!Screen.class.isAssignableFrom(rawClass)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Class<? extends Screen> screenClass = (Class<? extends Screen>) rawClass;
            screenClass.getDeclaredConstructor();
            String simpleName = screenClass.getSimpleName();
            return new ToolEntry(className, prettifyToolName(simpleName), buildToolDescription(simpleName));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String prettifyToolName(String simpleName) {
        String base = simpleName.replace("Koil", "").replace("Screen", "");
        base = base.replaceAll("([a-z])([A-Z])", "$1 $2").trim();
        if (base.isEmpty()) {
            return "Tool";
        }
        return base;
    }

    private String buildToolDescription(String simpleName) {
        String lower = simpleName.toLowerCase();
        if (lower.contains("explorer")) {
            return "Browse Koil files and folders.";
        }
        if (lower.contains("config")) {
            return "Open Koil configuration editing tools.";
        }
        if (lower.contains("editor")) {
            return "Open a Koil editor tool.";
        }
        return "Open this Koil tool screen.";
    }

    private void initToolButtons() {
        clearWidgetList(toolButtons);
        int startY = 118;
        int buttonWidth = Math.min(220, Math.max(140, this.width - 180));
        for (int i = 0; i < detectedTools.size(); i++) {
            ToolEntry entry = detectedTools.get(i);
            int buttonY = startY + (i * 24);
            ButtonWidget toolButton = this.addDrawableChild(ButtonWidget.builder(Text.literal(entry.title), button -> openToolScreen(entry.className))
                    .dimensions(140, buttonY, buttonWidth, 20)
                    .build());
            toolButton.setTooltip(Tooltip.of(Text.literal(entry.description)));
            toolButtons.add(toolButton);
        }
    }

    private void updateToolButtonPositions() {
        for (int i = 0; i < toolButtons.size(); i++) {
            ClickableWidget widget = toolButtons.get(i);
            int buttonY = 118 + (i * 24) - scrollOffset;
            widget.setX(140);
            widget.setY(buttonY);
            widget.visible = buttonY + widget.getHeight() > 108 && buttonY < this.height - 24;
            widget.active = widget.visible;
        }
        int contentHeight = detectedTools.size() * 24;
        updateMaxScrollOffset(contentHeight, this.height - 150);
    }

    private void openToolScreen(String className) {
        try {
            if ("com.spirit.client.gui.ide.FileExplorerScreen".equals(className)) {
                assert this.client != null;
                this.client.setScreen(FileExplorerScreen.openAtPath("/"));
                UiSoundHelper.playButtonClick();
                return;
            }
            Class<?> rawClass = Class.forName(className);
            if (!Screen.class.isAssignableFrom(rawClass)) {
                return;
            }
            Constructor<?> constructor = rawClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Screen screen = (Screen) constructor.newInstance();
            assert this.client != null;
            this.client.setScreen(screen);
            UiSoundHelper.playButtonClick();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        assert client != null;
        KoilScreenBackgrounds.render(context, client, this.width, this.height);

        context.drawText(this.textRenderer, "Version - " + version(), this.width - 100, 10, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.getMatrices().push();
        context.getMatrices().scale(0.5f, 0.5f, 1.0F);
        context.drawText(this.textRenderer, "By: SpiritXIV", (int) ((this.width - 100) / 0.5f), (int) (20 / 0.5f), new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.fill(0, 0, this.width, this.height, KoilScreenBackgrounds.overlayColor(client));
        context.drawBorder(0, 0, this.width, this.height, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, 0, this.width, 60, new Color(uiColorHeader, true).getRGB());
        context.drawBorder(0, 0, this.width, 60, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(127, 62, this.width, this.height, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(127, 62, this.width, this.height, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, 62, 125, this.height, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(0, 62, 125, this.height, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawTexture(LOGO_TEXTURE, 10, 5, 0, 0, 45, 45, 45, 45);
        File logoTexture = new File(uiImageDirectory, "icon.png");
        ImageTextureService.markFilePersistent(logoTexture);
        context.getMatrices().push();
        context.getMatrices().scale(2, 2, 1.0F);
        context.drawText(this.textRenderer, "Koil", 34, 6, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "Manager Menu - InDEV", 68, 35, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);

        switch (currentPanel) {
            case PANEL_HOME:
                renderHome(context, mouseX, mouseY, delta);
                break;
            case PANEL_WIKI:
                renderHomeWiki(context, mouseX, mouseY, delta);
                break;
            case PANEL_CHANGELOG:
                renderMenuChangeLog(context, mouseX, mouseY, delta);
                break;
            case PANEL_TOOLS:
                renderToolsPanel(context, mouseX, mouseY, delta);
                break;
            case PANEL_BOOK:
                renderBookDetailPanel(context, mouseX, mouseY, delta);
                break;
            case PANEL_SETTINGS:
                renderSettingsPanel(context, mouseX, mouseY, delta);
                break;
            case PANEL_DEBUG:
                renderDebugPanel(context, mouseX, mouseY, delta);
                break;
            case PANEL_DEBUG_VISUALS:
                renderDebugVisualsPanel(context, mouseX, mouseY, delta);
                break;
            case PANEL_DEBUG_CONSOLE:
                renderDebugConsolePanel(context, mouseX, mouseY, delta);
                break;
            default:
                renderHome(context, mouseX, mouseY, delta);
                switchPanel(PANEL_HOME);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void renderHome(DrawContext context, int mouseX, int mouseY, float delta) {
        context.getMatrices().push();
        context.getMatrices().scale(1.2F, 1.2F, 1.0F);
        context.drawText(this.textRenderer, "Welcome", (int) (140 / 1.2F), (int) (75 / 1.2F), new Color(uiColorContentBaseTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "A project-sized manager layer for Koil's runtime tools, guides, and in-game utilities.", 140, 92, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
        context.drawText(this.textRenderer, "Use the left rail for the change log, tools, settings, and the funding shortcut.", 140, 104, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);

        String longText = "Koil is built as a long-term platform project rather than a single feature mod. The manager menu is the operating surface for that platform: it should explain what Koil is, expose the most useful tools quickly, and keep the runtime UI readable.\n\nThe left rail now uses a dedicated fundraising button beside Welcome, then keeps the main navigation focused on Change Log, Tools, and Settings.\n\nUse Tools to launch Koil screens like the file explorer, file editor, config editor, and any other Koil tool screen that can be discovered at runtime.";
        renderScrollableText(context, longText, 140, 128, this.width - 220, this.height - 150);
    }

    private void renderToolsPanel(DrawContext context, int mouseX, int mouseY, float delta) {
        context.getMatrices().push();
        context.getMatrices().scale(1.2F, 1.2F, 1.0F);
        context.drawText(this.textRenderer, "Tools", (int) (140 / 1.2F), (int) (75 / 1.2F), new Color(uiColorContentBaseTitleText, true).getRGB(), true);
        context.getMatrices().pop();

        int toolCount = detectedTools.size();
        context.drawText(this.textRenderer, "Koil tools detected: " + toolCount, 140, 92, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
        context.drawText(this.textRenderer, "Hover a tool to see what it does, then click it to open that screen.", 140, 104, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);

        updateToolButtonPositions();

        if (toolCount == 0) {
            context.drawText(this.textRenderer, "No tool screens were detected.", 140, 132, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
            maxScrollOffset = 0;
            return;
        }

        int infoY = 118 + (toolCount * 24) - scrollOffset;
        if (infoY + 24 > 108 && infoY < this.height - 24) {
            context.drawText(this.textRenderer, "New Koil editor, explorer, tool, and config screens can show up here automatically.", 140, infoY + 12, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        }
    }

    private void renderHomeWiki(DrawContext context, int mouseX, int mouseY, float delta) {
        context.getMatrices().push();
        context.getMatrices().scale(1.2F, 1.2F, 1.0F);
        context.drawText(this.textRenderer, "Wiki", (int) (140 / 1.2F), (int) (75 / 1.2F), new Color(uiColorContentBaseTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "Browse Koil guides and references.", 140, 92, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);

        int yOffset = 118;
        int itemHeight = 54;
        int viewportHeight = this.height - 140;

        updateMaxScrollOffset(books.size(), itemHeight, viewportHeight);

        for (int i = 0; i < books.size(); i++) {
            MenuBookEntry book = books.get(i);
            int iconX = 140;
            int iconY = yOffset + (i * itemHeight) - scrollOffset;

            if (iconY + itemHeight > 108 && iconY < this.height - 24) {
                context.drawTexture(book.iconTexture(), iconX, iconY, 0, 0, 32, 32, 32, 32);
                context.drawText(this.textRenderer, book.name(), iconX + 42, iconY, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
                context.drawText(this.textRenderer, "Author: " + book.miniCredits(), iconX + 42, iconY + 12, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
                context.drawText(this.textRenderer, "Pages: " + book.pageCount(), iconX + 42, iconY + 24, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            }
        }
    }

    private void renderMenuChangeLog(DrawContext context, int mouseX, int mouseY, float delta) {
        UpdateScreenData.UpdateData updateData = UpdateScreenData.readLocalData();
        boolean betaTester = UpdateScreenData.isBetaTester();
        List<UpdateScreenData.Release> releases = UpdateScreenData.releasesForBranch(updateData, null, betaTester);

        context.getMatrices().push();
        context.getMatrices().scale(1.2F, 1.2F, 1.0F);
        context.drawText(this.textRenderer, "Change Log", (int) (140 / 1.2F), (int) (75 / 1.2F), new Color(uiColorContentBaseTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "Release timeline for visible branches.", 140, 92, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);

        int top = 118;
        int bottom = this.height - 24;
        context.enableScissor(140, top, this.width - 18, bottom);
        UpdateScreenData.TimelineRenderResult result = UpdateScreenData.renderReleaseTimelineInteractive(
                context,
                this.textRenderer,
                updateData,
                releases,
                140,
                top + 6,
                this.width - 168,
                this.scrollOffset,
                top,
                bottom,
                betaTester,
                mouseX,
                mouseY
        );
        context.disableScissor();
        this.maxScrollOffset = Math.max(0, result.contentHeight - (bottom - top - 8));
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, this.maxScrollOffset));
        if (releases.isEmpty()) {
            context.drawText(this.textRenderer, "No release entries were found for this branch.", 150, top + 12, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
        }
        UpdateScreenData.renderHoverPopup(context, this.textRenderer, result, mouseX, mouseY, this.width, this.height);
    }

    private void renderBookDetailPanel(DrawContext context, int mouseX, int mouseY, float delta) {
        if (selectedBooks != null) {
            int iconX = 140;
            int iconY = 88;
            String bookText = selectedBooks.description();

            context.getMatrices().push();
            context.getMatrices().scale(1.2F, 1.2F, 1.0F);
            context.drawText(this.textRenderer, selectedBooks.name(), (int) ((iconX + 40) / 1.2F), (int) (75 / 1.2F), new Color(uiColorContentBaseTitleText, true).getRGB(), true);
            context.getMatrices().pop();
            context.drawText(this.textRenderer, "Author: " + selectedBooks.miniCredits(), iconX + 40, 92, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
            context.drawText(this.textRenderer, "Page " + selectedBooks.currentPage() + " / " + selectedBooks.pageCount(), iconX + 40, 104, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            context.drawTexture(selectedBooks.iconTexture(), iconX, iconY, 0, 0, 32, 32, 32, 32);
            renderScrollableOrderedText(context, this.textRenderer.wrapLines(StringVisitable.plain(bookText), this.width - 190), 140, 128, this.width - 190, this.height - 150, new Color(uiColorContentBaseDescriptionText, true).getRGB());
        }
    }

    private void renderSettingsPanel(DrawContext context, int mouseX, int mouseY, float delta) {
        context.getMatrices().push();
        context.getMatrices().scale(1.2F, 1.2F, 1.0F);
        context.drawText(this.textRenderer, "Settings", (int) (140 / 1.2F), (int) (75 / 1.2F), new Color(uiColorContentBaseTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "Manager-level toggles and system actions live here now that the old config pane is gone.", 140, 92, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);

        if (debugCheckbox == null) {
            boolean isDebugModeEnabled = JSONFileEditor.getValueFromJson(configFilePath, "debug").getAsBoolean();
            boolean designMusicisDebugModeEnabled = JSONFileEditor.getValueFromJson(configFilePath, "designMusic").getAsBoolean();

            debugCheckbox = this.addDrawableChild(new CheckboxWidget(140, 118, 170, 20, Text.literal("Toggle Debug"), isDebugModeEnabled) {
                @Override
                public void onPress() {
                    boolean newDebugValue = !debugCheckbox.isChecked();
                    try {
                        JSONFileEditor.updateValueInJson(configFilePath, "debug", new JsonPrimitive(newDebugValue));
                    } catch (IOException e) {
                        SUBLOGGER.logE("File-Management thread", "Failed to update debug config value: " + e.getMessage());
                    }
                }
            });

            designMusicCheckbox = this.addDrawableChild(new CheckboxWidget(140, 146, 170, 20, Text.literal("Toggle Design Music"), designMusicisDebugModeEnabled) {
                @Override
                public void onPress() {
                    boolean newDesignMusicValue = !designMusicCheckbox.isChecked();
                    try {
                        JSONFileEditor.updateValueInJson(configFilePath, "designMusic", new JsonPrimitive(newDesignMusicValue));
                    } catch (IOException e) {
                        SUBLOGGER.logE("File-Management thread", "Failed to update designMusic config value: " + e.getMessage());
                    }
                }
            });
        }

        if (uninstallKoilButton == null) {
            uninstallKoilButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Uninstall Koil"), button -> confirmUninstallKoil()).dimensions(140, 212, 150, 20).build());
        }
    }

    private void renderDebugPanel(DrawContext context, int mouseX, int mouseY, float delta) {
        context.getMatrices().push();
        context.getMatrices().scale(1.2F, 1.2F, 1.0F);
        context.drawText(this.textRenderer, "Debug/Dev", (int) (140 / 1.2F), (int) (75 / 1.2F), new Color(uiColorContentBaseTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "This is the debug menu for testing many things, mostly visual though.", 140, 92, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);

        String longText = "This is the debug menu for testing many things, mostly visual though.\nYou can find everything here that is in dev and made for testing.\n\nDownload Core Files reruns Koil's bootstrap file flow and refreshes the runtime files it depends on.\nDelete Key/Catcher removes ./koil/sys/key.json and ./koil/sys/catcher.json so they can be downloaded again on demand.";
        int maxWidth = this.width / 2 + 50;
        List<String> wrappedText = wrapText(longText, maxWidth);

        int y = 118 - scrollOffset;
        for (String line : wrappedText) {
            if (y >= 118 && y <= this.height - 24) {
                context.drawText(this.textRenderer, line, 140, y, new Color(uiColorContentBaseDescriptionText, true).getRGB(), true);
            }
            y += this.textRenderer.fontHeight;
        }

        int buttonsY = y + 10;
        if (downloadDummyFilesButton == null) {
            downloadDummyFilesButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Download Core Files"), button -> refreshBootstrapFiles())
                    .dimensions(140, buttonsY, 170, 20)
                    .build());
            debugButtons.add(downloadDummyFilesButton);
        }
        if (deleteBootstrapFilesButton == null) {
            deleteBootstrapFilesButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Delete Key/Catcher"), button -> confirmDeleteBootstrapFiles())
                    .dimensions(140, buttonsY + 28, 170, 20)
                    .build());
            debugButtons.add(deleteBootstrapFilesButton);
        }

        downloadDummyFilesButton.setX(140);
        downloadDummyFilesButton.setY(buttonsY);
        deleteBootstrapFilesButton.setX(140);
        deleteBootstrapFilesButton.setY(buttonsY + 28);

        int contentHeight = wrappedText.size() * this.textRenderer.fontHeight + 66;
        maxScrollOffset = Math.max(0, contentHeight - (this.height - 118));
    }

    private void refreshBootstrapFiles() {
        Main.refreshBootstrapFiles();
        UiSoundHelper.playButtonClick();
    }

    private void deleteBootstrapFiles() {
        Main.deleteCoreBootstrapFiles();
        UiSoundHelper.playButtonClick();
    }

    private void confirmDeleteBootstrapFiles() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            deleteBootstrapFiles();
            return;
        }
        client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                deleteBootstrapFiles();
            }
            client.setScreen(this);
        }, Text.literal("Delete Core Files"), Text.literal("Are you sure you want to delete key.json and catcher.json?"), Text.literal("Delete"), Text.literal("Cancel")));
    }

    private void confirmUninstallKoil() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            uninstallKoil();
            return;
        }
        client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                uninstallKoil();
            } else {
                client.setScreen(this);
            }
        }, Text.literal("Uninstall Koil"), Text.literal("Are you sure you want to delete Koil files and close the game?"), Text.literal("Uninstall"), Text.literal("Cancel")));
    }

    private void uninstallKoil() {
        Path koilDir = Paths.get("./koil");
        Path specificFile = Paths.get("./mods", "koil-" + version() + ".jar");
        Path koilConfigFile = Paths.get("./config/koil.json");

        try {
            if (Files.exists(koilDir)) {
                Files.walk(koilDir).sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        SUBLOGGER.logE("File-Management thread", "Failed to delete during Koil uninstall: " + path + " - " + e.getMessage());
                    }
                });
            }

            if (Files.exists(specificFile)) {
                Files.delete(specificFile);
            }

            if (Files.exists(koilConfigFile)) {
                Files.delete(koilConfigFile);
            }

            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                client.stop();
            }
        } catch (IOException e) {
            SUBLOGGER.logE("File-Management thread", "Error occurred during Koil uninstallation: " + e.getMessage());
        }
    }

    private void renderDebugVisualsPanel(DrawContext context, int mouseX, int mouseY, float delta) {
        context.getMatrices().push();
        context.getMatrices().scale(1.2F, 1.2F, 1.0F);
        context.drawText(this.textRenderer, "Debug Visuals", (int) (140 / 1.2F), (int) (75 / 1.2F), new Color(uiColorContentBaseTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "Visual toast and display tests for Koil's in-game UI.", 140, 92, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);

        if (debugButtons.isEmpty()) {
            ButtonWidget debugVisButton1 = this.addDrawableChild(ButtonWidget.builder(Text.literal("Test Console Toasts"), button -> {
                ConsoleToast.add(client.getToastManager(), ConsoleToast.Type.CONSOLE, Text.of("CONSOLE"), Text.of("toast"));
                ConsoleToast.add(client.getToastManager(), ConsoleToast.Type.CONSOLE_INFO, Text.of("CONSOLE_INFO"), Text.of("toast"));
                ConsoleToast.add(client.getToastManager(), ConsoleToast.Type.CONSOLE_WARNING, Text.of("CONSOLE_WARNING"), Text.of("toast"));
                ConsoleToast.add(client.getToastManager(), ConsoleToast.Type.CONSOLE_ERROR, Text.of("CONSOLE_ERROR"), Text.of("toast"));
                ConsoleToast.add(client.getToastManager(), ConsoleToast.Type.CONSOLE_FATAL, Text.of("CONSOLE_FATAL"), Text.of("toast"));
                ConsoleToast.add(client.getToastManager(), ConsoleToast.Type.CONSOLE_DEBUG, Text.of("CONSOLE_DEBUG"), Text.of("toast"));
                ConsoleToast.add(client.getToastManager(), ConsoleToast.Type.CONSOLE_UPDATE, Text.of("CONSOLE_UPDATE"), Text.of("toast"));
                ConsoleToast.add(client.getToastManager(), ConsoleToast.Type.CONSOLE_OTHER, Text.of("CONSOLE_OTHER"), Text.of("toast"));
            }).dimensions(140, 118, 150, 20).build());

            ButtonWidget debugVisButton2 = this.addDrawableChild(ButtonWidget.builder(Text.literal("Test Message Toasts"), button -> {
                KoilMessageToast.add(client.getToastManager(), KoilMessageToast.Type.MUSIC, Text.of("MUSIC"), Text.of("toast"));
                KoilMessageToast.add(client.getToastManager(), KoilMessageToast.Type.ANNOUNCEMENT, Text.of("ANNOUNCEMENT"), Text.of("toast"));
                KoilMessageToast.add(client.getToastManager(), KoilMessageToast.Type.KORO_MESSAGE, Text.of("KORO_MESSAGE"), Text.of("toast"));
            }).dimensions(140, 142, 150, 20).build());

            ButtonWidget debugVisButton3 = this.addDrawableChild(ButtonWidget.builder(Text.literal("Test Update Toasts"), button -> {
                KoilUpdateToast.add(client.getToastManager(), KoilUpdateToast.Type.UPDATE_UI, Text.of("UPDATE_UI"), Text.of("toast"));
                KoilUpdateToast.add(client.getToastManager(), KoilUpdateToast.Type.UPDATE_DEBUG, Text.of("UPDATE_DEBUG"), Text.of("toast"));
                KoilUpdateToast.add(client.getToastManager(), KoilUpdateToast.Type.UPDATE_API, Text.of("UPDATE_CONTENT"), Text.of("toast"));
                KoilUpdateToast.add(client.getToastManager(), KoilUpdateToast.Type.UPDATE_CONSOLE, Text.of("UPDATE_CONSOLE"), Text.of("toast"));
                KoilUpdateToast.add(client.getToastManager(), KoilUpdateToast.Type.UPDATE_OTHER, Text.of("UPDATE_ALL"), Text.of("toast"));
            }).dimensions(140, 166, 150, 20).build());

            debugButtons.add(debugVisButton1);
            debugButtons.add(debugVisButton2);
            debugButtons.add(debugVisButton3);
        }
    }

    private void renderDebugConsolePanel(DrawContext context, int mouseX, int mouseY, float delta) {
        context.getMatrices().push();
        context.getMatrices().scale(1.2F, 1.2F, 1.0F);
        context.drawText(this.textRenderer, "Debug Console", (int) (140 / 1.2F), (int) (75 / 1.2F), new Color(uiColorContentBaseTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "Open external debugging surfaces and Koil log utilities.", 140, 92, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);

        if (debugButtons.isEmpty()) {
            ButtonWidget consoleButton1 = this.addDrawableChild(ButtonWidget.builder(Text.literal("Open Koil Log"), button -> {
                assert this.client != null;
                this.client.setScreen(new ConsoleScreen(this));
            }).dimensions(140, 118, 150, 20).build());
            ButtonWidget consoleButton2 = this.addDrawableChild(ButtonWidget.builder(Text.literal("Pop Out Koil Log"), button -> WindowManager.openConsoleWindow(ConsoleChannel.KOIL))
                    .dimensions(296, 118, 150, 20)
                    .build());
            debugButtons.add(consoleButton1);
            debugButtons.add(consoleButton2);
        }
    }

    private void switchPanelToBook(MenuBookEntry book) {
        this.selectedBooks = book;
        switchPanel(PANEL_BOOK);

        ButtonWidget prevButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("<"), button -> selectedBooks.prevPage()).dimensions(154, 118, 16, 16).build());
        ButtonWidget nextButton = this.addDrawableChild(ButtonWidget.builder(Text.literal(">"), button -> selectedBooks.nextPage()).dimensions(174, 118, 16, 16).build());
        debugButtons.add(prevButton);
        debugButtons.add(nextButton);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && currentPanel == PANEL_WIKI) {
            int yOffset = 146;
            int itemHeight = 54;
            for (int i = 0; i < books.size(); i++) {
                MenuBookEntry book = books.get(i);
                int iconY = yOffset + (i * itemHeight) - scrollOffset;
                if (mouseX >= 140 && mouseX <= this.width - 24 && mouseY >= iconY - 2 && mouseY <= iconY + 34) {
                    UiSoundHelper.playButtonClick();
                    switchPanelToBook(book);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int) amount, maxScrollOffset));
        return true;
    }

    private void updateMaxScrollOffset(int totalItems, int itemHeight, int viewportHeight) {
        int totalContentHeight = totalItems * itemHeight;
        maxScrollOffset = Math.max(0, totalContentHeight - viewportHeight);
    }

    private void updateMaxScrollOffset(int contentHeight, int viewportHeight) {
        maxScrollOffset = Math.max(0, contentHeight - viewportHeight);
    }

    private void renderScrollableText(DrawContext context, String text, int x, int y, int width, int height) {
        List<String> wrappedText = wrapText(text, width);
        int contentHeight = wrappedText.size() * (textRenderer.fontHeight + 2);
        updateMaxScrollOffset(contentHeight, height);

        int textY = y - scrollOffset;
        for (String line : wrappedText) {
            if (textY + textRenderer.fontHeight > y && textY < y + height) {
                context.drawText(this.textRenderer, line, x, textY, Color.WHITE.getRGB(), false);
            }
            textY += textRenderer.fontHeight + 2;
        }
    }

    private void renderScrollableOrderedText(DrawContext context, List<OrderedText> wrappedLines, int x, int y, int width, int height, int color) {
        int contentHeight = wrappedLines.size() * (textRenderer.fontHeight + 2);
        updateMaxScrollOffset(contentHeight, height);

        int textY = y - scrollOffset;
        for (OrderedText line : wrappedLines) {
            if (textY + textRenderer.fontHeight > y && textY < y + height) {
                context.drawText(this.textRenderer, line, x, textY, color, false);
            }
            textY += textRenderer.fontHeight + 2;
        }
    }

    private List<String> wrapText(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || maxWidth <= 0) {
            return lines;
        }

        String[] paragraphs = text.split("\n");
        for (String paragraph : paragraphs) {
            String[] words = paragraph.split(" ");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                String testLine = (!line.isEmpty() ? line + " " : "") + word;
                if (textRenderer.getWidth(testLine) > maxWidth) {
                    if (!line.isEmpty()) {
                        lines.add(line.toString());
                        line = new StringBuilder();
                    }
                }
                line.append((!line.isEmpty() ? " " : "")).append(word);
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
        }
        return lines;
    }

    private void renderChangelog(DrawContext context, String changeLog, int x, int y) {
        int finalY = y - scrollOffset;
        for (OrderedText line : this.textRenderer.wrapLines(Text.literal(changeLog), this.width - 160)) {
            if (finalY >= 80 && finalY <= this.height - 20) {
                context.drawText(this.textRenderer, line, x, finalY, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
            }
            finalY += this.textRenderer.fontHeight + 2;
        }
        int maxScroll = Math.max(0, finalY - this.height + 20);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
    }

    private static class ToolEntry {
        private final String className;
        private final String title;
        private final String description;

        private ToolEntry(String className, String title, String description) {
            this.className = className;
            this.title = title;
            this.description = description;
        }
    }
}
