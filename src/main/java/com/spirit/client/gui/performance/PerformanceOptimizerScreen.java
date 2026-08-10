package com.spirit.client.gui.performance;

import com.spirit.Main;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.performance.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.awt.Color;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
public class PerformanceOptimizerScreen extends Screen {
    private static final int HEADER_HEIGHT = 43;
    private static final int FOOTER_HEIGHT = 60;
    private static final int MARGIN = 10;
    private static final int CONTROL_HEIGHT = 20;
    private static final int CONTROL_GAP = 5;
    private static final int TAB_HEIGHT = 18;
    private static final int CHART_HEIGHT = 58;
    private static final int LINE_HEIGHT = 10;
    private static final int SCROLLBAR_WIDTH = 4;
    private static SessionState savedSession;

    private final Screen parent;
    private final List<PaneHitbox> paneHitboxes = new ArrayList<>();
    private final List<RecommendationHitbox> recommendationHitboxes = new ArrayList<>();
    private PerformanceProfileMode activeMode = PerformanceProfileMode.AUTO;
    private PerformanceHardwareProfile hardwareProfile;
    private PerformanceRuntimeContext runtimeContext;
    private PerformanceBenchmarkResult latestBenchmark;
    private List<PerformanceRecommendation> recommendations = new ArrayList<>();
    private List<PerformanceSettingDescriptor> providerSettings = new ArrayList<>();
    private List<PerformanceProviderApplyResult> lastProviderResults = new ArrayList<>();
    private List<PerformanceApplyEntryResult> lastApplyEntries = new ArrayList<>();
    private Map<String, String> appliedTargetsBySetting = new LinkedHashMap<>();
    private DiagnosticsPane activePane = DiagnosticsPane.OVERVIEW;
    private String status = "Run a benchmark for a clean world sample, or inspect the live measurements below.";
    private TextFieldWidget searchField;
    private ButtonWidget benchmarkButton;
    private ButtonWidget applyButton;
    private ButtonWidget reportButton;
    private ButtonWidget revertButton;
    private ButtonWidget doneButton;
    private Rect profilePrevious = Rect.EMPTY;
    private Rect profileNext = Rect.EMPTY;
    private int controlsBottom = HEADER_HEIGHT + 60;
    private int mainScroll;
    private int mainContentHeight;
    private int mainViewportX;
    private int mainViewportY;
    private int mainViewportWidth;
    private int mainViewportHeight;
    private boolean draggingMainScrollbar;
    private int mainScrollbarDragOffset;
    private long observedBenchmarkResultAtMillis;
    private long runtimeContextAtMillis;
    private List<Text> hoverTooltipLines = List.of();
    private int hoverTooltipX;
    private int hoverTooltipY;
    private List<PerformanceMonitor.Sample> chartSamples = List.of();
    private long chartCacheAtMillis;
    private int chartEntityMax = 1;
    private PerformanceSnapshot chartSnapshot;

    public PerformanceOptimizerScreen() {
        this(null);
    }

    public PerformanceOptimizerScreen(Screen parent) {
        super(Text.literal("Performance Optimizer"));
        this.parent = parent;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        restoreSession();
        this.searchField = new TextFieldWidget(this.textRenderer, MARGIN, HEADER_HEIGHT + 30, 180, CONTROL_HEIGHT, Text.literal("performance-search"));
        this.searchField.setMaxLength(256);
        this.searchField.setPlaceholder(Text.literal("Search recommendations / provider settings"));
        this.searchField.setChangedListener(value -> this.mainScroll = 0);
        this.addDrawableChild(this.searchField);
        restoreSearchText();
        this.benchmarkButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Benchmark"), button -> runBenchmark()).dimensions(0, 0, 80, 20).build());
        this.applyButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Apply Supported"), button -> applySafe()).dimensions(0, 0, 100, 20).build());
        this.reportButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Report"), button -> writeReport()).dimensions(0, 0, 70, 20).build());
        this.revertButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Revert Last"), button -> revertLast()).dimensions(0, 0, 90, 20).build());
        this.doneButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> closeAndRemember()).dimensions(0, 0, 70, 20).build());
        layoutControls();
        MinecraftClient client = MinecraftClient.getInstance();
        if (this.hardwareProfile == null) {
            this.hardwareProfile = PerformanceHardwareScanner.scan(client);
        }
        this.runtimeContext = PerformanceRuntimeContextService.capture(client);
        this.runtimeContextAtMillis = System.currentTimeMillis();
        if (this.recommendations.isEmpty()) {
            this.recommendations = PerformanceRecommendationEngine.recommend(client, this.activeMode, PerformanceMonitor.freshSnapshot(client));
        }
        refreshProviderSettings();
    }

    private void restoreSession() {
        if (savedSession == null) {
            return;
        }
        this.activeMode = savedSession.activeMode();
        this.activePane = savedSession.activePane();
        this.status = savedSession.status();
        this.mainScroll = savedSession.mainScroll();
        this.latestBenchmark = savedSession.latestBenchmark();
        this.recommendations = new ArrayList<>(savedSession.recommendations());
        this.lastProviderResults = new ArrayList<>(savedSession.lastProviderResults());
        this.lastApplyEntries = new ArrayList<>(savedSession.lastApplyEntries());
        this.appliedTargetsBySetting = new LinkedHashMap<>(savedSession.appliedTargetsBySetting());
        this.observedBenchmarkResultAtMillis = savedSession.observedBenchmarkResultAtMillis();
    }

    private void restoreSearchText() {
        if (savedSession != null && this.searchField != null) {
            this.searchField.setText(savedSession.searchText());
        }
    }

    private void saveSession() {
        savedSession = new SessionState(
            this.activeMode,
            this.activePane,
            this.status,
            this.mainScroll,
            this.latestBenchmark,
            List.copyOf(this.recommendations),
            List.copyOf(this.lastProviderResults),
            List.copyOf(this.lastApplyEntries),
            Map.copyOf(this.appliedTargetsBySetting),
            this.searchField == null ? "" : this.searchField.getText(),
            this.observedBenchmarkResultAtMillis
        );
    }

    @Override
    public void tick() {
        syncOptimizationTestResult();
        if (PerformanceOptimizationTestService.active()) {
            this.status = PerformanceOptimizationTestService.status();
        }
        long now = System.currentTimeMillis();
        if (this.runtimeContext == null || now - this.runtimeContextAtMillis > 2000L) {
            this.runtimeContext = PerformanceRuntimeContextService.capture(MinecraftClient.getInstance());
            this.runtimeContextAtMillis = now;
        }
        layoutControls();
        if (this.searchField != null) {
            this.searchField.tick();
        }
        saveSession();
        super.tick();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        layoutControls();
        renderBackgroundAndChrome(context);
        this.hoverTooltipLines = List.of();
        this.hoverTooltipX = mouseX;
        this.hoverTooltipY = mouseY;
        ensureChartCache();
        renderControlBand(context, mouseX, mouseY);
        renderMainPanel(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
        renderHoverTooltip(context);
    }

    private void renderBackgroundAndChrome(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        KoilScreenBackgrounds.render(context, client, this.width, this.height);
        if (KoilScreenBackgrounds.canRender(client)) {
            context.fill(0, 0, this.width, this.height, KoilScreenBackgrounds.overlayColor(client));
        } else {
            context.fill(0, 0, this.width, this.height, new Color(uiColorContentBase, true).getRGB());
        }
        context.fill(0, 0, this.width, HEADER_HEIGHT, new Color(uiColorHeader, true).getRGB());
        context.fill(0, HEADER_HEIGHT - 4, this.width, HEADER_HEIGHT - 1, new Color(uiColorHeaderStripe, true).getRGB());
        int footerY = Math.max(HEADER_HEIGHT, this.height - footerHeight());
        context.fill(0, footerY, this.width, this.height, new Color(uiColorFooter, true).getRGB());
        context.fill(0, footerY, this.width, footerY + 3, new Color(uiColorFooterStripe, true).getRGB());
        context.drawBorder(0, 0, this.width, this.height, new Color(uiColorBackgroundBorder, true).getRGB());
        context.getMatrices().push();
        context.getMatrices().scale(1.5F, 1.5F, 1.0F);
        context.drawText(this.textRenderer, "Performance", 25, 6, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "-=- optimizer / diagnostics", 37, 23, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
    }

    private void layoutControls() {
        int footerTop = Math.max(HEADER_HEIGHT + 20, this.height - footerHeight());
        int innerWidth = Math.max(1, this.width - MARGIN * 2);
        int y = HEADER_HEIGHT + 5;
        boolean inlineSearch = this.width >= 260;
        int searchWidth = Math.min(230, Math.max(96, innerWidth / 3));
        if (this.searchField != null) {
            if (inlineSearch) {
                this.searchField.setX(this.width - MARGIN - searchWidth);
                this.searchField.setY(y);
                this.searchField.setWidth(searchWidth);
            } else {
                this.searchField.setX(MARGIN);
                this.searchField.setY(y + CONTROL_HEIGHT + CONTROL_GAP);
                this.searchField.setWidth(innerWidth);
            }
        }
        int profileRight = inlineSearch ? this.width - MARGIN - searchWidth - CONTROL_GAP : this.width - MARGIN;
        int profileLabelWidth = this.textRenderer == null ? 42 : this.textRenderer.getWidth("Profile") + 8;
        int arrowWidth = 22;
        int previousX = MARGIN + profileLabelWidth;
        int nextX = Math.max(previousX + arrowWidth + 48, profileRight - arrowWidth);
        this.profilePrevious = new Rect(previousX, y, arrowWidth, CONTROL_HEIGHT);
        this.profileNext = new Rect(nextX, y, arrowWidth, CONTROL_HEIGHT);
        int tabsY = inlineSearch ? y + CONTROL_HEIGHT + CONTROL_GAP : y + (CONTROL_HEIGHT + CONTROL_GAP) * 2;
        this.paneHitboxes.clear();
        int tabX = MARGIN;
        int rowY = tabsY;
        boolean compactTabs = this.width < 460;
        for (DiagnosticsPane pane : DiagnosticsPane.values()) {
            String tabLabel = pane.displayLabel(compactTabs);
            int tabWidth = Math.max(compactTabs ? 30 : 40, this.textRenderer == null ? 48 : this.textRenderer.getWidth(tabLabel) + (compactTabs ? 8 : 12));
            if (tabX > MARGIN && tabX + tabWidth > this.width - MARGIN) {
                tabX = MARGIN;
                rowY += TAB_HEIGHT + 3;
            }
            this.paneHitboxes.add(new PaneHitbox(pane, tabX, rowY, tabWidth, TAB_HEIGHT));
            tabX += tabWidth + 4;
        }
        this.controlsBottom = Math.min(footerTop - 4, rowY + TAB_HEIGHT + 5);
        layoutFooterButtons();
    }

    private void layoutFooterButtons() {
        if (this.benchmarkButton == null) {
            return;
        }
        int available = Math.max(1, this.width - MARGIN * 2);
        if (compactFooter()) {
            int gap = 4;
            int buttonWidth = Math.max(1, (available - gap * 4) / 5);
            int y = this.height - 28;
            ButtonWidget[] buttons = {this.benchmarkButton, this.applyButton, this.reportButton, this.revertButton, this.doneButton};
            String[] labels = {"Bench", "Apply", "Report", "Revert", "Done"};
            int x = MARGIN;
            for (int i = 0; i < buttons.length; i++) {
                ButtonWidget button = buttons[i];
                button.setX(x);
                button.setY(y);
                int width = i == buttons.length - 1 ? Math.max(1, this.width - MARGIN - x) : buttonWidth;
                button.setWidth(width);
                button.setMessage(Text.literal(labels[i]));
                x += width + gap;
            }
            return;
        }
        int gap = 6;
        int firstWidth = Math.max(1, (available - gap * 2) / 3);
        int firstY = this.height - 52;
        this.benchmarkButton.setX(MARGIN);
        this.benchmarkButton.setY(firstY);
        this.benchmarkButton.setWidth(firstWidth);
        this.applyButton.setX(MARGIN + firstWidth + gap);
        this.applyButton.setY(firstY);
        this.applyButton.setWidth(firstWidth);
        this.reportButton.setX(MARGIN + (firstWidth + gap) * 2);
        this.reportButton.setY(firstY);
        this.reportButton.setWidth(Math.max(1, this.width - MARGIN - this.reportButton.getX()));
        int secondY = this.height - 28;
        int secondWidth = Math.max(1, (available - gap) / 2);
        this.revertButton.setX(MARGIN);
        this.revertButton.setY(secondY);
        this.revertButton.setWidth(secondWidth);
        this.doneButton.setX(MARGIN + secondWidth + gap);
        this.doneButton.setY(secondY);
        this.doneButton.setWidth(Math.max(1, this.width - MARGIN - this.doneButton.getX()));
        this.benchmarkButton.setMessage(Text.literal("Benchmark"));
        this.applyButton.setMessage(Text.literal(this.width < 360 ? "Apply" : "Apply Supported"));
        this.reportButton.setMessage(Text.literal("Report"));
        this.revertButton.setMessage(Text.literal(this.width < 360 ? "Revert" : "Revert Last"));
        this.doneButton.setMessage(Text.literal("Done"));
    }

    private boolean compactFooter() {
        return this.height < 230 && this.width >= 260;
    }

    private int footerHeight() {
        return compactFooter() ? 36 : FOOTER_HEIGHT;
    }

    private void renderControlBand(DrawContext context, int mouseX, int mouseY) {
        int y = HEADER_HEIGHT + 5;
        int labelColor = new Color(uiColorContentBaseDescriptionText, true).getRGB();
        context.drawText(this.textRenderer, "Profile", MARGIN, y + 6, labelColor, false);
        drawSquareControl(context, this.profilePrevious, "<", false, this.activeMode.color());
        drawSquareControl(context, this.profileNext, ">", false, this.activeMode.color());
        int selectedX = this.profilePrevious.right() + 3;
        int selectedRight = this.profileNext.x() - 3;
        int selectedWidth = Math.max(1, selectedRight - selectedX);
        context.fill(selectedX, y, selectedRight, y + CONTROL_HEIGHT, withAlpha(uiColorContentBase, 208));
        context.fill(selectedX, y, selectedX + 3, y + CONTROL_HEIGHT, withAlpha(this.activeMode.color(), 180));
        context.drawBorder(selectedX, y, selectedWidth, CONTROL_HEIGHT, new Color(uiColorBackgroundBorder, true).getRGB());
        String mode = trimToPixels(this.activeMode.label(), Math.max(10, selectedWidth - 12));
        context.drawText(this.textRenderer, mode, selectedX + 7, y + 6, softText(this.activeMode.color()), false);
        for (PaneHitbox tab : this.paneHitboxes) {
            boolean selected = tab.pane() == this.activePane;
            int fill = selected ? withAlpha(uiColorContentBase, 230) : withAlpha(0xFF000000, 42);
            int border = selected ? shadedBorder(this.activeMode.color()) : new Color(uiColorBackgroundBorder, true).getRGB();
            context.fill(tab.x(), tab.y(), tab.x() + tab.width(), tab.y() + tab.height(), fill);
            if (selected) {
                context.fill(tab.x(), tab.y() + tab.height() - 2, tab.x() + tab.width(), tab.y() + tab.height(), withAlpha(this.activeMode.color(), 180));
            }
            context.drawBorder(tab.x(), tab.y(), tab.width(), tab.height(), border);
            String label = trimToPixels(tab.pane().displayLabel(this.width < 460), tab.width() - 8);
            context.drawText(this.textRenderer, label, tab.x() + Math.max(4, (tab.width() - this.textRenderer.getWidth(label)) / 2), tab.y() + 5, selected ? new Color(uiColorContentBaseTitleText, true).getRGB() : new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
        }
        int suggestedY = this.controlsBottom - 1;
        if (this.runtimeContext != null && suggestedY < this.height - footerHeight()) {
            String suggested = "Suggested: " + this.runtimeContext.suggestedProfile().replace('_', ' ');
            if (this.width >= 620) {
                String display = trimToPixels(suggested, 180);
                context.drawText(this.textRenderer, display, this.width - MARGIN - this.textRenderer.getWidth(display), suggestedY - 10, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
            }
        }
    }

    private void drawSquareControl(DrawContext context, Rect rect, String label, boolean selected, int accent) {
        context.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), selected ? withAlpha(uiColorContentBase, 230) : withAlpha(0xFF000000, 42));
        context.drawBorder(rect.x(), rect.y(), rect.width(), rect.height(), selected ? withAlpha(accent, 200) : new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawText(this.textRenderer, label, rect.x() + Math.max(4, (rect.width() - this.textRenderer.getWidth(label)) / 2), rect.y() + 6, softText(accent), false);
    }

    private void renderMainPanel(DrawContext context, int mouseX, int mouseY) {
        int footerTop = Math.max(HEADER_HEIGHT, this.height - footerHeight());
        this.mainViewportX = MARGIN;
        this.mainViewportY = Math.min(footerTop - 2, this.controlsBottom + 2);
        this.mainViewportWidth = Math.max(1, this.width - MARGIN * 2 - 9);
        this.mainViewportHeight = Math.max(1, footerTop - this.mainViewportY - 3);
        PerformanceSnapshot snapshot = this.chartSnapshot == null ? PerformanceMonitor.latestSnapshot(MinecraftClient.getInstance()) : this.chartSnapshot;
        int contentStartY = this.mainViewportY - this.mainScroll;
        int y = contentStartY;
        context.enableScissor(this.mainViewportX, this.mainViewportY, this.mainViewportX + this.mainViewportWidth, this.mainViewportY + this.mainViewportHeight);
        y = renderLiveStatus(context, this.mainViewportX, y, this.mainViewportWidth, snapshot);
        y += 6;
        switch (this.activePane) {
            case OVERVIEW -> y = renderOverview(context, this.mainViewportX, y, this.mainViewportWidth, snapshot);
            case RENDERING -> y = renderRendering(context, this.mainViewportX, y, this.mainViewportWidth, snapshot);
            case PROCESSING -> y = renderProcessing(context, this.mainViewportX, y, this.mainViewportWidth, snapshot);
            case MEMORY -> y = renderMemory(context, this.mainViewportX, y, this.mainViewportWidth, snapshot);
            case WORLD -> y = renderWorld(context, this.mainViewportX, y, this.mainViewportWidth, snapshot);
            case MODS -> y = renderMods(context, this.mainViewportX, y, this.mainViewportWidth, snapshot);
            case SERVER -> y = renderServer(context, this.mainViewportX, y, this.mainViewportWidth, snapshot);
            case ALL -> y = renderAll(context, this.mainViewportX, y, this.mainViewportWidth, snapshot);
        }
        y += 4;
        y = renderRecommendations(context, this.mainViewportX, y, this.mainViewportWidth);
        this.mainContentHeight = Math.max(1, y - contentStartY + 4);
        context.disableScissor();
        scrollMain(0);
        renderMainScrollbar(context);
    }

    private int renderLiveStatus(DrawContext context, int x, int y, int width, PerformanceSnapshot snapshot) {
        int accent = snapshot.primaryBottleneck().color();
        int textWidth = Math.max(20, width - 24);
        List<OrderedText> statusLines = wrapped(this.status, textWidth);
        List<OrderedText> causeLines = wrapped(snapshot.likelyCause(), textWidth);
        int height = 30 + statusLines.size() * LINE_HEIGHT + causeLines.size() * LINE_HEIGHT;
        context.fill(x, y, x + width, y + height, withAlpha(uiColorContentBase, 185));
        context.fill(x, y, x + 3, y + height, withAlpha(accent, 190));
        context.drawBorder(x, y, width, height, shadedBorder(accent));
        String measured = snapshot.primaryBottleneck() == PerformanceBottleneck.UNKNOWN ? "unverified cause" : snapshot.primaryBottleneck() == PerformanceBottleneck.HEALTHY ? "stable sample" : "strongest signal";
        int measuredWidth = this.textRenderer.getWidth(measured);
        int labelMax = Math.max(12, width - measuredWidth - 30);
        context.drawText(this.textRenderer, trimToPixels(snapshot.primaryBottleneck().label(), labelMax), x + 9, y + 7, softText(accent), false);
        context.drawText(this.textRenderer, trimToPixels(measured, Math.max(12, width / 2)), x + width - 9 - Math.min(measuredWidth, this.textRenderer.getWidth(trimToPixels(measured, Math.max(12, width / 2)))), y + 7, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
        int lineY = y + 20;
        lineY = drawWrapped(context, statusLines, x + 9, lineY, new Color(uiColorContentBaseTitleText, true).getRGB());
        lineY = drawWrapped(context, causeLines, x + 9, lineY, new Color(uiColorContentBaseDescriptionText, true).getRGB());
        return y + height;
    }

    private int renderOverview(DrawContext context, int x, int y, int width, PerformanceSnapshot snapshot) {
        y = sectionHeader(context, x, y, width, "Live snapshot");
        y = renderInfoRows(context, x, y, width, List.of(
            exact("Current FPS", snapshot.fps() + " fps", fpsColor(snapshot.fps())),
            exact("Average FPS", format1(snapshot.averageFps()) + " fps", fpsColor((int) Math.round(snapshot.averageFps()))),
            exact("1% low", format1(snapshot.onePercentLowFps()) + " fps", fpsColor((int) Math.round(snapshot.onePercentLowFps()))),
            exact("Frame time", format1(snapshot.frameTimeMs()) + " ms now | " + format1(snapshot.maxFrameTimeMs()) + " ms sampled max", pressureColor(Math.min(1.0D, snapshot.maxFrameTimeMs() / 120.0D))),
            exact("JVM heap", snapshot.usedMemoryMb() + " / " + snapshot.maxMemoryMb() + " MB | " + percentText(snapshot.memoryPressure()), memoryColor(snapshot.memoryPressure())),
            exact("Loaded entities", worldActive(snapshot) ? snapshot.entityCount() + " client-side entities" : "no world loaded", worldActive(snapshot) ? pressureColor(Math.min(1.0D, snapshot.entityCount() / 220.0D)) : 0xFF8D8D8D)
        ));
        y += 6;
        y = sectionHeader(context, x, y, width, "Current game settings");
        List<InfoLine> settings = new ArrayList<>();
        settings.add(exact("Render distance", snapshot.renderDistance() + " chunks", 0xFFE2E2E2));
        if ("server".equals(snapshot.worldType())) {
            settings.add(exact("Simulation distance", snapshot.simulationDistance() + " chunks in local options; the remote server controls its own simulation distance", new Color(uiColorToolTipWarning, true).getRGB()));
        } else {
            settings.add(exact("Simulation distance", snapshot.simulationDistance() + " chunks", 0xFFE2E2E2));
        }
        settings.add(exact("Graphics", snapshot.graphicsMode() + " | clouds " + snapshot.cloudsMode() + " | particles " + snapshot.particlesMode(), 0xFFE2E2E2));
        settings.add(exact("Frame pacing", maxFpsLabel(snapshot.maxFps()) + " | VSync " + (snapshot.vsync() ? "on" : "off"), 0xFFE2E2E2));
        settings.add(exact("Texture / effects", "mipmaps " + snapshot.mipmapLevels() + " | biome blend " + snapshot.biomeBlend() + " | entity shadows " + (snapshot.entityShadows() ? "on" : "off"), 0xFFE2E2E2));
        y = renderInfoRows(context, x, y, width, settings);
        y += 6;
        y = renderHardwareSummary(context, x, y, width, snapshot, true);
        y += 6;
        y = renderChartGroup(context, x, y, width, List.of(
            new ChartSpec("FPS history", ChartKind.FPS),
            new ChartSpec("Frame-time history", ChartKind.FRAME_TIME)
        ));
        if (this.latestBenchmark != null) {
            y += 6;
            y = renderBenchmarkPhases(context, x, y, width);
        }
        y += 6;
        return renderPressureSignals(context, x, y, width, snapshot, true);
    }

    private int renderRendering(DrawContext context, int x, int y, int width, PerformanceSnapshot snapshot) {
        y = sectionHeader(context, x, y, width, "Renderer and display");
        List<InfoLine> rows = new ArrayList<>();
        if (this.hardwareProfile != null) {
            rows.add(exact("GPU renderer", safe(this.hardwareProfile.gpuRenderer()), 0xFFE2E2E2));
            rows.add(exact("GPU vendor", safe(this.hardwareProfile.gpuVendor()), 0xFFE2E2E2));
            rows.add(exact("OpenGL", safe(this.hardwareProfile.gpuVersion()), 0xFFE2E2E2));
            rows.add(exact("Display mode", displayModeText(this.hardwareProfile), 0xFFE2E2E2));
            rows.add(exact("VRAM telemetry", "Unavailable from the current cross-platform OpenGL probe; Koil will not invent a VRAM value", 0xFF8D8D8D));
        }
        rows.add(exact("Game render size", windowRenderSize(), 0xFFE2E2E2));
        rows.add(exact("Shader state", this.runtimeContext == null ? "unknown" : this.runtimeContext.shaderState(), PerformanceRuntimeContextService.shaderPipelineActive() ? 0xFF8B39DD : 0xFF8D8D8D));
        y = renderInfoRows(context, x, y, width, rows);
        y += 6;
        y = sectionHeader(context, x, y, width, "Render settings");
        y = renderInfoRows(context, x, y, width, List.of(
            exact("Graphics mode", snapshot.graphicsMode(), 0xFFE2E2E2),
            exact("Render distance", snapshot.renderDistance() + " chunks", 0xFFE2E2E2),
            exact("Clouds", snapshot.cloudsMode(), 0xFFE2E2E2),
            exact("Particles", snapshot.particlesMode(), 0xFFE2E2E2),
            exact("Mipmaps", String.valueOf(snapshot.mipmapLevels()), 0xFFE2E2E2),
            exact("Entity distance", format2(snapshot.entityDistanceScale()), 0xFFE2E2E2),
            exact("Biome blend", String.valueOf(snapshot.biomeBlend()), 0xFFE2E2E2),
            exact("Smooth lighting", String.valueOf(snapshot.smoothLighting()), 0xFFE2E2E2),
            exact("Entity shadows", String.valueOf(snapshot.entityShadows()), 0xFFE2E2E2)
        ));
        y += 6;
        y = renderChartGroup(context, x, y, width, List.of(
            new ChartSpec("FPS history", ChartKind.FPS),
            new ChartSpec("Frame-time history", ChartKind.FRAME_TIME),
            new ChartSpec("Chunk-load estimate", ChartKind.CHUNK),
            new ChartSpec("Shader pressure estimate", ChartKind.SHADER)
        ));
        y += 6;
        return renderPressureSignals(context, x, y, width, snapshot, false);
    }

    private int renderProcessing(DrawContext context, int x, int y, int width, PerformanceSnapshot snapshot) {
        y = sectionHeader(context, x, y, width, "CPU and simulation context");
        double processCpu = PerformanceMonitor.processCpuLoad();
        double systemCpu = PerformanceMonitor.systemCpuLoad();
        List<InfoLine> rows = new ArrayList<>();
        rows.add(exact("Minecraft JVM CPU", loadText(processCpu), loadColor(processCpu)));
        rows.add(exact("Whole-system CPU", loadText(systemCpu), loadColor(systemCpu)));
        if (this.hardwareProfile != null) {
            rows.add(exact("Logical processors", String.valueOf(this.hardwareProfile.cpuThreads()), 0xFFE2E2E2));
        }
        rows.add(exact("JVM live threads", String.valueOf(ManagementFactory.getThreadMXBean().getThreadCount()), 0xFFE2E2E2));
        rows.add(exact("Loaded entities", worldActive(snapshot) ? String.valueOf(snapshot.entityCount()) : "inactive outside a world", worldActive(snapshot) ? 0xFFE2E2E2 : 0xFF8D8D8D));
        if ("server".equals(snapshot.worldType())) {
            rows.add(exact("Simulation distance", "Remote server controlled. The local option is not treated as a server performance fix.", new Color(uiColorToolTipWarning, true).getRGB()));
        } else {
            rows.add(exact("Simulation distance", snapshot.simulationDistance() + " chunks", 0xFFE2E2E2));
        }
        rows.add(estimate("Tick / simulation pressure", gameplayPressureLabel(snapshot), pressureColor(Math.min(1.0D, snapshot.entityCount() / 220.0D))));
        rows.add(exact("Frame time", format1(snapshot.frameTimeMs()) + " ms now | " + format1(snapshot.maxFrameTimeMs()) + " ms sampled max", pressureColor(Math.min(1.0D, snapshot.maxFrameTimeMs() / 120.0D))));
        y = renderInfoRows(context, x, y, width, rows);
        y += 6;
        y = renderChartGroup(context, x, y, width, List.of(
            new ChartSpec("Entity count", ChartKind.ENTITY),
            new ChartSpec("Strongest pressure estimate", ChartKind.FAULT_PRESSURE),
            new ChartSpec("Frame-time history", ChartKind.FRAME_TIME)
        ));
        y += 6;
        y = sectionHeader(context, x, y, width, "Interpretation");
        return renderInfoRows(context, x, y, width, List.of(
            exact("CPU telemetry scope", "Process and system CPU load are measured. Minecraft main-thread saturation is not directly exposed here, so Koil does not present an invented per-thread bottleneck percentage.", new Color(uiColorContentBaseDescriptionText, true).getRGB()),
            estimate("Entity/tick signal", "Uses client entity count plus frame behavior as a workload signal. It is not server MSPT or TPS.", new Color(uiColorContentBaseDescriptionText, true).getRGB())
        ));
    }

    private int renderMemory(DrawContext context, int x, int y, int width, PerformanceSnapshot snapshot) {
        y = sectionHeader(context, x, y, width, "Memory");
        long freeSystem = PerformanceMonitor.freeSystemMemoryMb();
        List<InfoLine> rows = new ArrayList<>();
        rows.add(exact("JVM heap used", snapshot.usedMemoryMb() + " MB", memoryColor(snapshot.memoryPressure())));
        rows.add(exact("JVM heap limit", snapshot.maxMemoryMb() + " MB", 0xFFE2E2E2));
        rows.add(exact("JVM heap pressure", percentText(snapshot.memoryPressure()), memoryColor(snapshot.memoryPressure())));
        if (this.hardwareProfile != null) {
            rows.add(exact("System RAM total", memoryText(this.hardwareProfile.systemMemoryMb()), 0xFFE2E2E2));
        }
        rows.add(exact("System RAM free", freeSystem >= 0 ? freeSystem + " MB" : "unavailable", freeSystem >= 0 ? 0xFFE2E2E2 : 0xFF8D8D8D));
        rows.add(estimate("GC pressure", percentText(snapshot.gcPressure()) + " from garbage-collector time in the sample window", pressureColor(snapshot.gcPressure())));
        rows.add(exact("Resource packs", snapshot.resourcePackCount() + " enabled", 0xFFE2E2E2));
        rows.add(exact("VRAM", "Not reported because the current cross-platform probe cannot verify dedicated graphics memory reliably", 0xFF8D8D8D));
        y = renderInfoRows(context, x, y, width, rows);
        y += 6;
        y = renderChartGroup(context, x, y, width, List.of(
            new ChartSpec("Heap pressure", ChartKind.MEMORY),
            new ChartSpec("GC activity", ChartKind.GC),
            new ChartSpec("Resource-pack stack estimate", ChartKind.RESOURCEPACK)
        ));
        return y;
    }

    private int renderWorld(DrawContext context, int x, int y, int width, PerformanceSnapshot snapshot) {
        y = sectionHeader(context, x, y, width, "World context");
        List<InfoLine> rows = new ArrayList<>();
        if (this.runtimeContext != null) {
            rows.add(exact("Context", this.runtimeContext.worldType(), 0xFFE2E2E2));
            rows.add(exact("World", safe(this.runtimeContext.worldName()), 0xFFE2E2E2));
            rows.add(exact("Dimension", safe(this.runtimeContext.dimension()), 0xFFE2E2E2));
        }
        rows.add(exact("Render distance", snapshot.renderDistance() + " chunks", 0xFFE2E2E2));
        if ("server".equals(snapshot.worldType())) {
            rows.add(exact("Simulation distance", snapshot.simulationDistance() + " in local options; remote server simulation is not changed by Koil", new Color(uiColorToolTipWarning, true).getRGB()));
        } else {
            rows.add(exact("Simulation distance", snapshot.simulationDistance() + " chunks", 0xFFE2E2E2));
        }
        rows.add(exact("Client entities", worldActive(snapshot) ? String.valueOf(snapshot.entityCount()) : "inactive", worldActive(snapshot) ? 0xFFE2E2E2 : 0xFF8D8D8D));
        rows.add(estimate("Chunk-load pressure", worldActive(snapshot) ? percentText(snapshot.chunkStress()) + " inferred from frame spikes and render-distance context" : "inactive outside a world", worldActive(snapshot) ? pressureColor(snapshot.chunkStress()) : 0xFF8D8D8D));
        y = renderInfoRows(context, x, y, width, rows);
        y += 6;
        y = renderChartGroup(context, x, y, width, List.of(
            new ChartSpec("Chunk-load estimate", ChartKind.CHUNK),
            new ChartSpec("World-load estimate", ChartKind.WORLD_SIMULATION),
            new ChartSpec("Entity count", ChartKind.ENTITY)
        ));
        return y;
    }

    private int renderMods(DrawContext context, int x, int y, int width, PerformanceSnapshot snapshot) {
        y = sectionHeader(context, x, y, width, "Mods, packs, and optimization providers");
        List<InfoLine> rows = new ArrayList<>();
        rows.add(exact("Loaded mods", String.valueOf(snapshot.loadedModCount()), 0xFFE2E2E2));
        if (this.hardwareProfile != null) {
            rows.add(exact("Optimization mods", this.hardwareProfile.optimizationMods().isEmpty() ? "none detected" : String.join(", ", this.hardwareProfile.optimizationMods()), this.hardwareProfile.optimizationMods().isEmpty() ? 0xFF8D8D8D : new Color(uiColorSaveSuccessColor, true).getRGB()));
        }
        if (this.runtimeContext != null) {
            rows.add(exact("Optimization configs", this.runtimeContext.optimizationModConfigs().isEmpty() ? "none detected" : String.join(", ", this.runtimeContext.optimizationModConfigs()), this.runtimeContext.optimizationModConfigs().isEmpty() ? 0xFF8D8D8D : 0xFFE2E2E2));
            rows.add(exact("Resource packs", this.runtimeContext.resourcePacks().isEmpty() ? "none enabled" : String.join(", ", this.runtimeContext.resourcePacks()), 0xFFE2E2E2));
            rows.add(exact("Shader state", this.runtimeContext.shaderState(), PerformanceRuntimeContextService.shaderPipelineActive() ? 0xFF8B39DD : 0xFF8D8D8D));
        }
        rows.add(estimate("Modpack size signal", percentText(snapshot.modLoadPressure()) + " from loaded-mod count normalization", pressureColor(snapshot.modLoadPressure())));
        rows.add(estimate("Resource-pack size signal", percentText(snapshot.resourcePackPressure()) + " from enabled-pack count normalization", pressureColor(snapshot.resourcePackPressure())));
        y = renderInfoRows(context, x, y, width, rows);
        y += 6;
        y = renderChartGroup(context, x, y, width, List.of(
            new ChartSpec("Modpack size estimate", ChartKind.MOD_LOAD),
            new ChartSpec("Resource-pack stack estimate", ChartKind.RESOURCEPACK)
        ));
        y += 6;
        return renderProviderSettings(context, x, y, width);
    }

    private int renderServer(DrawContext context, int x, int y, int width, PerformanceSnapshot snapshot) {
        y = sectionHeader(context, x, y, width, "Server / connection context");
        List<InfoLine> rows = new ArrayList<>();
        if (this.runtimeContext == null || "menu".equals(this.runtimeContext.worldType())) {
            rows.add(exact("Connection", "No active world or server", 0xFF8D8D8D));
        } else if ("server".equals(this.runtimeContext.worldType())) {
            rows.add(exact("Connection", "Remote multiplayer server", 0xFFE2E2E2));
            rows.add(exact("Address", safe(this.runtimeContext.serverAddress()), 0xFFE2E2E2));
            int latency = currentLatencyMs();
            rows.add(exact("Player latency", latency >= 0 ? latency + " ms" : "unavailable", latencyColor(latency)));
            rows.add(exact("Server TPS / MSPT", "Not exposed reliably to a vanilla client. Koil does not estimate server TPS from client FPS.", 0xFF8D8D8D));
            rows.add(exact("Simulation distance", "Controlled by the server. Local simulation-distance recommendations are suppressed in this context.", new Color(uiColorToolTipWarning, true).getRGB()));
        } else {
            rows.add(exact("Connection", "Integrated singleplayer server", 0xFFE2E2E2));
            rows.add(exact("World", safe(this.runtimeContext.worldName()), 0xFFE2E2E2));
            rows.add(exact("Server tick work", "Runs in the same Minecraft process; client CPU and world simulation can compete for frame time", 0xFFE2E2E2));
        }
        rows.add(exact("Client render distance", snapshot.renderDistance() + " chunks", 0xFFE2E2E2));
        rows.add(exact("Client entities", worldActive(snapshot) ? String.valueOf(snapshot.entityCount()) : "inactive", 0xFFE2E2E2));
        y = renderInfoRows(context, x, y, width, rows);
        y += 6;
        return renderChartGroup(context, x, y, width, List.of(
            new ChartSpec("Client frame-time history", ChartKind.FRAME_TIME),
            new ChartSpec("Client world-load estimate", ChartKind.CHUNK)
        ));
    }

    private int renderAll(DrawContext context, int x, int y, int width, PerformanceSnapshot snapshot) {
        y = renderHardwareSummary(context, x, y, width, snapshot, false);
        y += 6;
        y = renderRendering(context, x, y, width, snapshot);
        y += 6;
        y = renderProcessing(context, x, y, width, snapshot);
        y += 6;
        y = renderMemory(context, x, y, width, snapshot);
        y += 6;
        y = renderWorld(context, x, y, width, snapshot);
        y += 6;
        y = renderServer(context, x, y, width, snapshot);
        y += 6;
        return renderMods(context, x, y, width, snapshot);
    }

    private int renderHardwareSummary(DrawContext context, int x, int y, int width, PerformanceSnapshot snapshot, boolean compact) {
        y = sectionHeader(context, x, y, width, compact ? "System summary" : "System and runtime");
        List<InfoLine> rows = new ArrayList<>();
        if (this.hardwareProfile != null) {
            rows.add(exact("Operating system", safe(this.hardwareProfile.operatingSystem()) + " | " + safe(this.hardwareProfile.architecture()), 0xFFE2E2E2));
            rows.add(exact("CPU", this.hardwareProfile.cpuThreads() + " logical processors | JVM CPU " + loadText(PerformanceMonitor.processCpuLoad()), loadColor(PerformanceMonitor.processCpuLoad())));
            rows.add(exact("System RAM", memoryText(this.hardwareProfile.systemMemoryMb()) + " total | " + memoryText(PerformanceMonitor.freeSystemMemoryMb()) + " free", 0xFFE2E2E2));
            rows.add(exact("GPU", safe(this.hardwareProfile.gpuRenderer()), 0xFFE2E2E2));
            rows.add(exact("Display", displayModeText(this.hardwareProfile), 0xFFE2E2E2));
            rows.add(exact("Game render size", windowRenderSize(), 0xFFE2E2E2));
            if (!compact) {
                rows.add(exact("OpenGL", safe(this.hardwareProfile.gpuVersion()), 0xFFE2E2E2));
                rows.add(exact("Java", System.getProperty("java.version", "unknown") + " | " + System.getProperty("java.vendor", "unknown"), 0xFFE2E2E2));
                rows.add(exact("Minecraft", safe(this.hardwareProfile.minecraftVersion()), 0xFFE2E2E2));
                rows.add(estimate("Game-directory I/O probe", storageProbeText(this.hardwareProfile.storageProbeMbPerSecond()), this.hardwareProfile.storageProbeMbPerSecond() > 0.0D ? 0xFFE2E2E2 : 0xFF8D8D8D));
                rows.add(exact("Game drive", storageCapacityText(), 0xFFE2E2E2));
            }
        }
        rows.add(exact("JVM heap", snapshot.usedMemoryMb() + " / " + snapshot.maxMemoryMb() + " MB", memoryColor(snapshot.memoryPressure())));
        return renderInfoRows(context, x, y, width, rows);
    }

    private int renderBenchmarkPhases(DrawContext context, int x, int y, int width) {
        y = sectionHeader(context, x, y, width, "Latest benchmark phases");
        if (this.latestBenchmark == null || this.latestBenchmark.phaseResults().isEmpty()) {
            return renderInfoRows(context, x, y, width, List.of(exact("Benchmark", "No phase data yet", 0xFF8D8D8D)));
        }
        for (PerformanceBenchmarkPhaseResult phase : this.latestBenchmark.phaseResults()) {
            PerformanceSnapshot phaseSnapshot = phase.snapshot();
            String value = phaseSnapshot == null
                ? phase.note()
                : format1(phaseSnapshot.averageFps()) + " avg fps | " + format1(phaseSnapshot.onePercentLowFps()) + " 1% low | " + format1(phaseSnapshot.maxFrameTimeMs()) + " ms max | " + phase.dominantPressure().label();
            y = renderInfoRow(context, x, y, width, new InfoLine(phase.label(), value, phase.dominantPressure().color(), false));
            if (phase.note() != null && !phase.note().isBlank()) {
                y = renderInfoRow(context, x, y, width, exact("Phase note", phase.note(), new Color(uiColorContentBaseDescriptionText, true).getRGB()));
            }
        }
        for (String note : this.latestBenchmark.testNotes()) {
            y = renderInfoRow(context, x, y, width, exact("Test note", note, new Color(uiColorContentBaseDescriptionText, true).getRGB()));
        }
        return y;
    }

    private int renderProviderSettings(DrawContext context, int x, int y, int width) {
        y = sectionHeader(context, x, y, width, "Verified provider settings");
        List<PerformanceSettingDescriptor> settings = filteredProviderSettings();
        y = renderInfoRow(context, x, y, width, exact("Provider scan", this.providerSettings.size() + " verified settings | " + settings.size() + " visible | last apply " + lastApplySummary(), new Color(uiColorContentBaseDescriptionText, true).getRGB()));
        if (settings.isEmpty()) {
            return renderInfoRow(context, x, y, width, exact("Filter", "No verified provider settings match the current search.", 0xFF8D8D8D));
        }
        for (PerformanceSettingDescriptor setting : settings) {
            boolean observationOnly = setting.observationOnly();
            boolean needsChange = settingNeedsChange(setting);
            int accent = observationOnly ? 0xFF7C8288 : !needsChange ? new Color(uiColorSaveSuccessColor, true).getRGB() : setting.liveApplySupported() ? 0xFF2DA700 : 0xFFE3B735;
            String current = setting.currentValue() == null ? "unavailable" : setting.currentValue();
            String target = setting.recommendedValue() == null ? "review" : setting.recommendedValue();
            String value = observationOnly
                ? current + " | observation only"
                : needsChange ? current + " -> " + target + (setting.requiresResourceReload() ? " | resource reload" : " | live") : current + " | already at target";
            String title = setting.providerId() + " / " + setting.label();
            y = renderInfoRow(context, x, y, width, new InfoLine(title, value + " | " + setting.description(), accent, false));
        }
        return y;
    }

    private int renderPressureSignals(DrawContext context, int x, int y, int width, PerformanceSnapshot snapshot, boolean includeLegend) {
        y = sectionHeader(context, x, y, width, "Pressure signals");
        if (includeLegend) {
            y = renderInfoRow(context, x, y, width, exact("Legend", "Rows prefixed with ~ are inferred signals, not hardware utilization measurements.", new Color(uiColorContentBaseDescriptionText, true).getRGB()));
        }
        boolean world = worldActive(snapshot);
        double fpsTarget = configuredFpsTarget(snapshot);
        y = drawSignalBar(context, x, y, width, "~ FPS target gap", snapshot.fps() <= 0 || fpsTarget <= 0.0D ? -1.0D : Math.max(0.0D, (fpsTarget - snapshot.averageFps()) / fpsTarget), 0xFFE06A21);
        y = drawSignalBar(context, x, y, width, "~ Frame-spike pressure", Math.min(1.0D, snapshot.maxFrameTimeMs() / 140.0D), 0xFFE06A21);
        y = drawSignalBar(context, x, y, width, "Heap pressure", snapshot.memoryPressure(), 0xFFA7003A);
        y = drawSignalBar(context, x, y, width, "~ GC pressure", snapshot.gcPressure(), 0xFFFF597D);
        y = drawSignalBar(context, x, y, width, "~ Chunk-load pressure", world ? snapshot.chunkStress() : -1.0D, 0xFFE3B735);
        y = drawSignalBar(context, x, y, width, "~ Entity workload", world ? Math.min(1.0D, snapshot.entityCount() / 220.0D) : -1.0D, 0xFFE6862C);
        y = drawSignalBar(context, x, y, width, "~ Shader pressure", PerformanceRuntimeContextService.shaderPipelineActive() ? snapshot.shaderPressure() : -1.0D, 0xFF7400A4);
        y = drawSignalBar(context, x, y, width, "~ Resource-pack size", snapshot.resourcePackPressure(), 0xFFB0199E);
        y = drawSignalBar(context, x, y, width, "~ Modpack size", snapshot.modLoadPressure(), 0xFFC32222);
        y = drawSignalBar(context, x, y, width, "~ Screen-open frame pressure", snapshot.uiFramePressure(), 0xFF0085A4);
        return y;
    }

    private int drawSignalBar(DrawContext context, int x, int y, int width, String label, double value, int color) {
        int height = 20;
        boolean inactive = value < 0.0D;
        double clamped = inactive ? 0.0D : Math.max(0.0D, Math.min(1.0D, value));
        int visibleColor = inactive ? 0xFF777777 : color;
        context.fill(x, y, x + width, y + height, withAlpha(0xFF000000, 32));
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());
        String valueText = inactive ? "inactive" : (int) Math.round(clamped * 100.0D) + "%";
        String labelText = trimToPixels(label, Math.max(20, width - this.textRenderer.getWidth(valueText) - 24));
        context.drawText(this.textRenderer, labelText, x + 7, y + 4, inactive ? 0xFF777777 : new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
        context.drawText(this.textRenderer, valueText, x + width - 7 - this.textRenderer.getWidth(valueText), y + 4, inactive ? 0xFF8D8D8D : softText(color), false);
        int barX = x + 7;
        int barY = y + 14;
        int barWidth = Math.max(1, width - 14);
        context.fill(barX, barY, barX + barWidth, barY + 3, withAlpha(0xFF000000, 100));
        context.fill(barX, barY, barX + (int) Math.round(barWidth * clamped), barY + 3, withAlpha(visibleColor, inactive ? 55 : 180));
        if (isOver(this.hoverTooltipX, this.hoverTooltipY, x, y, width, height)) {
            this.hoverTooltipLines = signalTooltip(label, valueText, inactive);
        }
        return y + height + 2;
    }

    private int renderChartGroup(DrawContext context, int x, int y, int width, List<ChartSpec> charts) {
        if (charts.isEmpty()) {
            return y;
        }
        int columns = width >= 520 ? 2 : 1;
        int gap = 8;
        int chartWidth = columns == 2 ? Math.max(80, (width - gap) / 2) : width;
        int index = 0;
        while (index < charts.size()) {
            ChartSpec left = charts.get(index++);
            renderMiniChart(context, x, y, chartWidth, CHART_HEIGHT, left.title(), left.kind());
            if (columns == 2 && index < charts.size()) {
                ChartSpec right = charts.get(index++);
                renderMiniChart(context, x + chartWidth + gap, y, chartWidth, CHART_HEIGHT, right.title(), right.kind());
            }
            y += CHART_HEIGHT + 8;
        }
        return y;
    }

    private void renderMiniChart(DrawContext context, int x, int y, int width, int height, String title, ChartKind kind) {
        if (y + height < this.mainViewportY || y > this.mainViewportY + this.mainViewportHeight) {
            return;
        }
        int accent = chartAccent(kind);
        context.fill(x, y, x + width, y + height, withAlpha(0xFF000000, 58));
        context.fill(x, y, x + 3, y + height, withAlpha(accent, 155));
        context.drawBorder(x, y, width, height, shadedBorder(accent));
        String valueLabel = trimToPixels(chartValueLabel(kind), Math.max(10, width / 2));
        int valueWidth = this.textRenderer.getWidth(valueLabel);
        int titleMax = Math.max(10, width - valueWidth - 24);
        context.drawText(this.textRenderer, trimToPixels(title, titleMax), x + 8, y + 5, softText(accent), false);
        context.drawText(this.textRenderer, valueLabel, x + width - 8 - valueWidth, y + 5, chartValueColor(kind), false);
        int graphLeft = x + 7;
        int graphRight = x + width - 8;
        int graphTop = y + 18;
        int graphBottom = y + height - 8;
        context.drawHorizontalLine(graphLeft, graphRight, graphTop + (graphBottom - graphTop) / 2, withAlpha(0xFFFFFFFF, 30));
        List<PerformanceMonitor.Sample> samples = this.chartSamples;
        if (samples.size() < 2) {
            context.drawText(this.textRenderer, "Waiting for samples...", x + 8, y + 28, 0xFF8D8D8D, false);
        } else {
            int graphWidth = Math.max(1, graphRight - graphLeft);
            int count = Math.min(samples.size(), Math.max(2, Math.min(graphWidth, 72)));
            int sampleStart = Math.max(0, samples.size() - count);
            for (int i = 1; i < count; i++) {
                PerformanceMonitor.Sample previous = samples.get(sampleStart + i - 1);
                PerformanceMonitor.Sample current = samples.get(sampleStart + i);
                int x1 = graphLeft + (i - 1) * graphWidth / Math.max(1, count - 1);
                int x2 = graphLeft + i * graphWidth / Math.max(1, count - 1);
                int y1 = chartY(previous, kind, graphTop, graphBottom, this.chartEntityMax);
                int y2 = chartY(current, kind, graphTop, graphBottom, this.chartEntityMax);
                drawSmallLine(context, x1, y1, x2, y2, chartSampleColor(kind, current));
                if ((kind == ChartKind.FRAME_TIME && current.frameTimeMs() > 75.0D) || (kind == ChartKind.GC && current.gcTimeMs() > 0L)) {
                    context.drawVerticalLine(x2, graphTop, graphBottom, withAlpha(kind == ChartKind.GC ? 0xFFA7003A : 0xFFE06A21, 145));
                }
            }
        }
        if (isOver(this.hoverTooltipX, this.hoverTooltipY, x, y, width, height)) {
            this.hoverTooltipLines = chartTooltip(kind);
        }
    }

    private int chartY(PerformanceMonitor.Sample sample, ChartKind kind, int graphTop, int graphBottom, int maxEntity) {
        double normalized = switch (kind) {
            case FPS -> Math.min(1.0D, sample.fps() / 120.0D);
            case FRAME_TIME -> Math.min(1.0D, sample.frameTimeMs() / 120.0D);
            case MEMORY -> sample.maxMemoryMb() <= 0L ? 0.0D : sample.usedMemoryMb() / (double) sample.maxMemoryMb();
            case GC -> Math.min(1.0D, sample.gcTimeMs() / 75.0D);
            case ENTITY -> sample.entityCount() / (double) Math.max(1, maxEntity);
            case CHUNK -> sample.chunkStress();
            case SHADER -> sample.shaderPressure();
            case MOD_LOAD -> sample.modLoadPressure();
            case RESOURCEPACK -> sample.resourcePackPressure();
            case WORLD_SIMULATION -> worldActive(sample.worldType()) ? Math.max(sample.chunkStress(), sample.entityCount() / (double) Math.max(1, maxEntity)) : 0.0D;
            case FAULT_PRESSURE -> {
                double memory = sample.maxMemoryMb() <= 0L ? 0.0D : sample.usedMemoryMb() / (double) sample.maxMemoryMb();
                double frame = Math.min(1.0D, sample.frameTimeMs() / 120.0D);
                double entity = sample.entityCount() / (double) Math.max(1, maxEntity);
                yield Math.max(Math.max(memory, frame), Math.max(entity, Math.max(sample.chunkStress(), Math.max(sample.shaderPressure(), sample.modLoadPressure()))));
            }
        };
        return graphBottom - (int) Math.min(graphBottom - graphTop, Math.max(1.0D, normalized * (graphBottom - graphTop)));
    }

    private int chartAccent(ChartKind kind) {
        return switch (kind) {
            case FPS -> 0xFF2DA700;
            case FRAME_TIME -> 0xFFE06A21;
            case MEMORY -> 0xFFA7003A;
            case GC -> 0xFFFF597D;
            case ENTITY -> 0xFFE6862C;
            case CHUNK -> 0xFFE3B735;
            case SHADER -> 0xFF7400A4;
            case MOD_LOAD -> 0xFFC32222;
            case RESOURCEPACK -> 0xFFB0199E;
            case WORLD_SIMULATION -> 0xFF6F89A8;
            case FAULT_PRESSURE -> 0xFF0085A4;
        };
    }

    private int chartSampleColor(ChartKind kind, PerformanceMonitor.Sample sample) {
        return switch (kind) {
            case FPS -> fpsColor(sample.fps());
            case FRAME_TIME -> pressureColor(Math.min(1.0D, sample.frameTimeMs() / 120.0D));
            case MEMORY -> pressureColor(sample.maxMemoryMb() <= 0L ? 0.0D : sample.usedMemoryMb() / (double) sample.maxMemoryMb());
            case GC -> pressureColor(Math.min(1.0D, sample.gcTimeMs() / 75.0D));
            case ENTITY -> pressureColor(Math.min(1.0D, sample.entityCount() / 220.0D));
            case CHUNK -> pressureColor(sample.chunkStress());
            case SHADER -> shaderColor(sample.shaderPressure());
            case MOD_LOAD -> pressureColor(sample.modLoadPressure());
            case RESOURCEPACK -> pressureColor(sample.resourcePackPressure());
            case WORLD_SIMULATION, FAULT_PRESSURE -> pressureColor(Math.max(sample.chunkStress(), Math.max(sample.shaderPressure(), sample.modLoadPressure())));
        };
    }

    private int chartValueColor(ChartKind kind) {
        PerformanceSnapshot snapshot = this.chartSnapshot == null ? PerformanceMonitor.latestSnapshot(MinecraftClient.getInstance()) : this.chartSnapshot;
        return switch (kind) {
            case FPS -> fpsColor(snapshot.fps());
            case FRAME_TIME -> pressureColor(Math.min(1.0D, snapshot.frameTimeMs() / 120.0D));
            case MEMORY -> memoryColor(snapshot.memoryPressure());
            case GC -> pressureColor(snapshot.gcPressure());
            case ENTITY -> pressureColor(Math.min(1.0D, snapshot.entityCount() / 220.0D));
            case CHUNK, WORLD_SIMULATION -> worldActive(snapshot) ? pressureColor(snapshot.chunkStress()) : 0xFF8D8D8D;
            case SHADER -> PerformanceRuntimeContextService.shaderPipelineActive() ? shaderColor(snapshot.shaderPressure()) : 0xFF8D8D8D;
            case MOD_LOAD -> pressureColor(snapshot.modLoadPressure());
            case RESOURCEPACK -> pressureColor(snapshot.resourcePackPressure());
            case FAULT_PRESSURE -> softText(snapshot.primaryBottleneck().color());
        };
    }

    private String chartValueLabel(ChartKind kind) {
        PerformanceSnapshot snapshot = this.chartSnapshot == null ? PerformanceMonitor.latestSnapshot(MinecraftClient.getInstance()) : this.chartSnapshot;
        return switch (kind) {
            case FPS -> snapshot.fps() + " fps";
            case FRAME_TIME -> format1(snapshot.frameTimeMs()) + " ms";
            case MEMORY -> percentText(snapshot.memoryPressure());
            case GC -> percentText(snapshot.gcPressure());
            case ENTITY -> snapshot.entityCount() + " ent";
            case CHUNK -> worldActive(snapshot) ? percentText(snapshot.chunkStress()) : "inactive";
            case SHADER -> PerformanceRuntimeContextService.shaderPipelineActive() ? percentText(snapshot.shaderPressure()) : "inactive";
            case MOD_LOAD -> snapshot.loadedModCount() + " mods";
            case RESOURCEPACK -> snapshot.resourcePackCount() + " packs";
            case WORLD_SIMULATION -> worldActive(snapshot) ? snapshot.renderDistance() + "/" + snapshot.simulationDistance() : "inactive";
            case FAULT_PRESSURE -> snapshot.primaryBottleneck().label();
        };
    }

    private List<Text> chartTooltip(ChartKind kind) {
        PerformanceSnapshot snapshot = this.chartSnapshot == null ? PerformanceMonitor.latestSnapshot(MinecraftClient.getInstance()) : this.chartSnapshot;
        return switch (kind) {
            case FPS -> diagnosticTooltip("FPS", "Current " + snapshot.fps() + " | Avg " + format1(snapshot.averageFps()) + " | 1% low " + format1(snapshot.onePercentLowFps()), fpsAdvice(snapshot));
            case FRAME_TIME -> diagnosticTooltip("Frame Time", "Now " + format1(snapshot.frameTimeMs()) + " ms | sampled max " + format1(snapshot.maxFrameTimeMs()) + " ms", snapshot.maxFrameTimeMs() > 75.0D ? "Frame spikes are elevated. Compare the benchmark phases before assigning a cause." : "Frame pacing is currently within a moderate range.");
            case MEMORY -> diagnosticTooltip("Memory", snapshot.usedMemoryMb() + "/" + snapshot.maxMemoryMb() + " MB | " + percentText(snapshot.memoryPressure()), snapshot.memoryPressure() > 0.85D ? "Heap pressure is high. Reduce texture/resource pressure before blindly increasing allocation." : "The JVM heap has usable headroom in the current sample.");
            case GC -> diagnosticTooltip("Garbage Collection", percentText(snapshot.gcPressure()) + " sample-window estimate", snapshot.gcPressure() > 0.50D ? "Garbage-collector time is elevated in the sample window." : "Garbage collection is not currently the strongest signal.");
            case ENTITY -> diagnosticTooltip("Entities", snapshot.entityCount() + " client-side entities", snapshot.entityCount() > 180 ? "Entity density is high enough to review entity distance and culling." : "Entity count is not currently extreme.");
            case CHUNK -> diagnosticTooltip("Chunk Load", worldActive(snapshot) ? percentText(snapshot.chunkStress()) + " inferred pressure" : "inactive", "This is inferred from frame spikes, world context, and render distance. It is not direct disk or chunk-thread utilization.");
            case SHADER -> diagnosticTooltip("Shader", PerformanceRuntimeContextService.shaderPipelineActive() ? percentText(snapshot.shaderPressure()) + " inferred pressure" : "inactive", "Shader pressure is only estimated when an active shader pack is verified. GPU utilization is not available here.");
            case MOD_LOAD -> diagnosticTooltip("Modpack Size", snapshot.loadedModCount() + " loaded mods | " + percentText(snapshot.modLoadPressure()), "This is a normalized workload-size signal. It does not prove that a specific mod is slow.");
            case RESOURCEPACK -> diagnosticTooltip("Resource Packs", snapshot.resourcePackCount() + " enabled | " + percentText(snapshot.resourcePackPressure()), "This is a pack-count signal, not measured VRAM usage.");
            case WORLD_SIMULATION -> diagnosticTooltip("World Load", worldActive(snapshot) ? "RD " + snapshot.renderDistance() + " | SD " + snapshot.simulationDistance() : "inactive", "Combines chunk-load and entity signals. Remote server simulation is not measured.");
            case FAULT_PRESSURE -> diagnosticTooltip("Strongest Signal", snapshot.primaryBottleneck().label(), snapshot.likelyCause());
        };
    }

    private void ensureChartCache() {
        long now = System.currentTimeMillis();
        if (!Main.preciseStat() && now - this.chartCacheAtMillis < 750L && !this.chartSamples.isEmpty()) {
            return;
        }
        this.chartSamples = new ArrayList<>(PerformanceMonitor.samples());
        this.chartEntityMax = Math.max(1, this.chartSamples.stream().mapToInt(PerformanceMonitor.Sample::entityCount).max().orElse(1));
        this.chartSnapshot = PerformanceMonitor.latestSnapshot(MinecraftClient.getInstance());
        this.chartCacheAtMillis = now;
    }

    private int sectionHeader(DrawContext context, int x, int y, int width, String title) {
        int color = new Color(uiColorContentBaseTitleText, true).getRGB();
        String display = trimToPixels(title, Math.max(20, width - 14));
        context.drawText(this.textRenderer, display, x, y + 2, color, false);
        int lineX = x + this.textRenderer.getWidth(display) + 7;
        if (lineX < x + width) {
            context.drawHorizontalLine(lineX, x + width, y + 6, withAlpha(uiColorBackgroundBorder, 150));
        }
        return y + 15;
    }

    private int renderInfoRows(DrawContext context, int x, int y, int width, List<InfoLine> rows) {
        for (InfoLine row : rows) {
            y = renderInfoRow(context, x, y, width, row);
        }
        return y;
    }

    private int renderInfoRow(DrawContext context, int x, int y, int width, InfoLine row) {
        int padding = 7;
        int labelMax = Math.max(54, Math.min(132, width / 3));
        String label = (row.estimated() ? "~ " : "") + safe(row.label());
        boolean stacked = width < 360 || this.textRenderer.getWidth(label) > labelMax - 8;
        int valueWidth = stacked ? Math.max(20, width - padding * 2) : Math.max(20, width - padding * 3 - labelMax);
        List<OrderedText> valueLines = wrapped(row.value(), valueWidth);
        int height = stacked ? 17 + valueLines.size() * LINE_HEIGHT : Math.max(18, 8 + valueLines.size() * LINE_HEIGHT);
        context.fill(x, y, x + width, y + height, withAlpha(0xFF000000, 30));
        context.fill(x, y, x + 2, y + height, withAlpha(row.color(), 125));
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawText(this.textRenderer, trimToPixels(label, stacked ? width - padding * 2 : labelMax - 8), x + padding, y + 5, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
        int valueX = stacked ? x + padding : x + padding + labelMax;
        int valueY = stacked ? y + 15 : y + 5;
        drawWrapped(context, valueLines, valueX, valueY, row.color());
        return y + height + 2;
    }

    private List<OrderedText> wrapped(String value, int width) {
        String safeValue = safe(value);
        List<OrderedText> lines = this.textRenderer.wrapLines(Text.literal(safeValue), Math.max(1, width));
        return lines.isEmpty() ? List.of(Text.literal("").asOrderedText()) : lines;
    }

    private int drawWrapped(DrawContext context, List<OrderedText> lines, int x, int y, int color) {
        for (OrderedText line : lines) {
            context.drawText(this.textRenderer, line, x, y, color, false);
            y += LINE_HEIGHT;
        }
        return y;
    }

    private void renderMainScrollbar(DrawContext context) {
        if (this.mainContentHeight <= this.mainViewportHeight) {
            this.mainScroll = 0;
            return;
        }
        int trackX = this.mainViewportX + this.mainViewportWidth + 4;
        int trackY = this.mainViewportY;
        int trackHeight = this.mainViewportHeight;
        int thumbHeight = Math.max(20, (int) (trackHeight * (this.mainViewportHeight / (double) this.mainContentHeight)));
        int maxScroll = Math.max(1, this.mainContentHeight - this.mainViewportHeight);
        int thumbY = trackY + (int) ((trackHeight - thumbHeight) * (this.mainScroll / (double) maxScroll));
        context.fill(trackX, trackY, trackX + SCROLLBAR_WIDTH, trackY + trackHeight, withAlpha(0xFF000000, 90));
        context.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight, withAlpha(uiColorContentBaseTitleText, 125));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (this.profilePrevious.contains(mouseX, mouseY)) {
                cycleProfile(-1);
                return true;
            }
            if (this.profileNext.contains(mouseX, mouseY)) {
                cycleProfile(1);
                return true;
            }
            for (PaneHitbox tab : this.paneHitboxes) {
                if (tab.contains(mouseX, mouseY)) {
                    if (this.activePane != tab.pane()) {
                        this.activePane = tab.pane();
                        this.mainScroll = 0;
                    }
                    saveSession();
                    return true;
                }
            }
            if (isOver((int) mouseX, (int) mouseY, this.mainViewportX, this.mainViewportY, this.mainViewportWidth, this.mainViewportHeight)) {
                for (RecommendationHitbox hitbox : this.recommendationHitboxes) {
                    if (hitbox.contains(mouseX, mouseY)) {
                        applySelectedRecommendation(hitbox.recommendation());
                        return true;
                    }
                }
            }
            if (this.mainContentHeight > this.mainViewportHeight) {
                int trackX = this.mainViewportX + this.mainViewportWidth + 2;
                int trackHeight = this.mainViewportHeight;
                int thumbHeight = Math.max(20, (int) (trackHeight * (this.mainViewportHeight / (double) this.mainContentHeight)));
                int maxScroll = Math.max(1, this.mainContentHeight - this.mainViewportHeight);
                int thumbY = this.mainViewportY + (int) ((trackHeight - thumbHeight) * (this.mainScroll / (double) maxScroll));
                if (isOver((int) mouseX, (int) mouseY, trackX, thumbY, 8, thumbHeight)) {
                    this.draggingMainScrollbar = true;
                    this.mainScrollbarDragOffset = (int) mouseY - thumbY;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (isOver((int) mouseX, (int) mouseY, this.mainViewportX, this.mainViewportY, this.mainViewportWidth + 12, this.mainViewportHeight)) {
            scrollMain((int) (-amount * 26));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (this.draggingMainScrollbar) {
            dragMainScrollbar((int) mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.draggingMainScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void cycleProfile(int direction) {
        PerformanceProfileMode[] modes = PerformanceProfileMode.values();
        int next = Math.floorMod(this.activeMode.ordinal() + direction, modes.length);
        this.activeMode = modes[next];
        this.recommendations = PerformanceRecommendationEngine.recommend(MinecraftClient.getInstance(), this.activeMode, PerformanceMonitor.freshSnapshot(MinecraftClient.getInstance()));
        this.status = "Profile selected: " + this.activeMode.label() + ". Recommendations were recalculated from the current measurements.";
        this.mainScroll = 0;
        saveSession();
    }

    private void runBenchmark() {
        this.hardwareProfile = PerformanceHardwareScanner.scan(MinecraftClient.getInstance());
        this.runtimeContext = PerformanceRuntimeContextService.capture(MinecraftClient.getInstance());
        boolean started = PerformanceOptimizationTestService.start(MinecraftClient.getInstance(), this.activeMode, this);
        this.status = started ? PerformanceOptimizationTestService.status() : "Benchmark is already running.";
    }

    private void applySafe() {
        PerformanceSnapshot before = PerformanceMonitor.freshSnapshot(MinecraftClient.getInstance());
        refreshProviderSettings();
        List<PerformanceRecommendation> actionable = actionableRecommendations();
        PerformanceConfigApplier.ApplyResult result = PerformanceConfigApplier.applySafe(MinecraftClient.getInstance(), actionable);
        List<PerformanceProviderApplyResult> providerResults = PerformanceLiveApplyService.applyRecommendations(MinecraftClient.getInstance(), this.providerSettings, actionable);
        PerformanceMonitor.freshSnapshot(MinecraftClient.getInstance());
        this.lastProviderResults = providerResults;
        this.lastApplyEntries = result.entryResults();
        rememberAppliedTargets(actionable, result, providerResults);
        long changed = providerResults.stream().filter(PerformanceProviderApplyResult::changed).count();
        this.status = result.message() + (providerResults.isEmpty() ? "" : " Provider configs: " + changed + "/" + providerResults.size() + " changed; reload requested where needed.");
        PerformanceLearningService.recordApply(MinecraftClient.getInstance(), before, actionable, result, providerResults);
        refreshProviderSettings();
        this.recommendations = refreshedRecommendationsAfterApply();
        saveSession();
    }

    private void applySelectedRecommendation(PerformanceRecommendation recommendation) {
        if (!recommendationNeedsChange(recommendation)) {
            this.status = "Already set: " + recommendation.title();
            return;
        }
        PerformanceSnapshot before = PerformanceMonitor.freshSnapshot(MinecraftClient.getInstance());
        refreshProviderSettings();
        PerformanceRecommendation selected = forceUserSelectedApply(recommendation);
        List<PerformanceRecommendation> selectedList = List.of(selected);
        PerformanceConfigApplier.ApplyResult result = PerformanceConfigApplier.applySafe(MinecraftClient.getInstance(), selectedList);
        List<PerformanceProviderApplyResult> providerResults = PerformanceLiveApplyService.applyRecommendations(MinecraftClient.getInstance(), this.providerSettings, selectedList);
        PerformanceMonitor.freshSnapshot(MinecraftClient.getInstance());
        this.lastProviderResults = providerResults;
        this.lastApplyEntries = result.entryResults();
        rememberAppliedTargets(selectedList, result, providerResults);
        long changed = providerResults.stream().filter(PerformanceProviderApplyResult::changed).count();
        this.status = "Applied selected target: " + recommendation.title()
            + (result.changed() ? " | vanilla changed" : "")
            + (providerResults.isEmpty() ? "" : " | providers " + changed + "/" + providerResults.size() + " changed");
        PerformanceLearningService.recordApply(MinecraftClient.getInstance(), before, selectedList, result, providerResults);
        refreshProviderSettings();
        this.recommendations = refreshedRecommendationsAfterApply();
        saveSession();
    }

    private List<PerformanceRecommendation> refreshedRecommendationsAfterApply() {
        PerformanceSnapshot fresh = PerformanceMonitor.freshSnapshot(MinecraftClient.getInstance());
        if (this.latestBenchmark != null && this.latestBenchmark.requestedMode() == this.activeMode) {
            return PerformanceRecommendationEngine.recommendFromBenchmark(MinecraftClient.getInstance(), this.activeMode, fresh, this.latestBenchmark.phaseResults());
        }
        return PerformanceRecommendationEngine.recommend(MinecraftClient.getInstance(), this.activeMode, fresh);
    }

    private PerformanceRecommendation forceUserSelectedApply(PerformanceRecommendation recommendation) {
        if (recommendation.safeAutoFix()) {
            return recommendation;
        }
        return new PerformanceRecommendation(
            recommendation.id(),
            recommendation.title(),
            recommendation.reason(),
            recommendation.bottleneck(),
            recommendation.severity(),
            true,
            recommendation.settingKey(),
            recommendation.beforeValue(),
            recommendation.afterValue()
        );
    }

    private void revertLast() {
        boolean reverted = PerformanceConfigApplier.revertLastBackup(MinecraftClient.getInstance());
        this.status = reverted ? "Reverted the latest vanilla options backup and reloaded GameOptions." : "No optimization backup found.";
        PerformanceMonitor.freshSnapshot(MinecraftClient.getInstance());
        refreshProviderSettings();
        this.recommendations = refreshedRecommendationsAfterApply();
        saveSession();
    }

    private void writeReport() {
        PerformanceReportService.writeReport(MinecraftClient.getInstance(), this.activeMode, this.recommendations);
        this.status = "Report written: " + PerformancePaths.PERFORMANCE_REPORT;
        saveSession();
    }

    private void closeAndRemember() {
        saveSession();
        MinecraftClient.getInstance().setScreen(this.parent == null ? new com.spirit.client.gui.main.KoilMenuScreen() : this.parent);
    }

    @Override
    public void close() {
        closeAndRemember();
    }

    @Override
    public void removed() {
        saveSession();
        super.removed();
    }

    private void refreshProviderSettings() {
        this.providerSettings = PerformanceProviderRegistry.settings(MinecraftClient.getInstance(), PerformanceMonitor.freshSnapshot(MinecraftClient.getInstance()));
    }

    private void rememberAppliedTargets(List<PerformanceRecommendation> appliedRecommendations, PerformanceConfigApplier.ApplyResult result, List<PerformanceProviderApplyResult> providerResults) {
        for (PerformanceRecommendation recommendation : appliedRecommendations) {
            if (recommendation == null || recommendation.settingKey() == null || recommendation.settingKey().isBlank()) {
                continue;
            }
            boolean vanillaApplied = result.entryResults().stream().anyMatch(entry -> entry.recommendationId().equals(recommendation.id()) && "applied".equals(entry.status()));
            boolean providerApplied = providerResults.stream().anyMatch(providerResult -> matchesSettingKey(providerResult.settingId(), recommendation.settingKey()) && providerResult.changed());
            if (vanillaApplied || providerApplied || sameSettingValue(currentValueForRecommendation(recommendation), recommendation.afterValue())) {
                this.appliedTargetsBySetting.put(normalizeSettingKey(recommendation.settingKey()), recommendation.afterValue());
            }
        }
    }

    private List<PerformanceSettingDescriptor> filteredProviderSettings() {
        String query = searchQuery();
        if (query.isEmpty()) {
            return this.providerSettings;
        }
        return this.providerSettings.stream()
            .filter(setting -> contains(setting.providerId(), query)
                || contains(setting.label(), query)
                || contains(setting.category(), query)
                || contains(setting.description(), query)
                || contains(setting.currentValue(), query)
                || contains(setting.recommendedValue(), query))
            .toList();
    }

    private List<PerformanceRecommendation> actionableRecommendations() {
        return this.recommendations.stream()
            .filter(this::recommendationNeedsChange)
            .toList();
    }

    private List<PerformanceRecommendation> visibleRecommendations() {
        String query = searchQuery();
        return this.recommendations.stream()
            .filter(recommendation -> recommendationNeedsChange(recommendation) || isAppliedTarget(recommendation))
            .filter(recommendation -> query.isEmpty()
                || contains(recommendation.title(), query)
                || contains(recommendation.reason(), query)
                || contains(recommendation.settingKey(), query)
                || contains(recommendation.severity().label(), query))
            .toList();
    }

    private boolean isAppliedTarget(PerformanceRecommendation recommendation) {
        if (recommendation == null) {
            return false;
        }
        String appliedTarget = this.appliedTargetsBySetting.get(normalizeSettingKey(recommendation.settingKey()));
        return appliedTarget != null && sameSettingValue(appliedTarget, recommendation.afterValue());
    }

    private boolean recommendationNeedsChange(PerformanceRecommendation recommendation) {
        if (recommendation == null || recommendation.settingKey() == null || recommendation.settingKey().isBlank() || "none".equals(recommendation.settingKey())) {
            return false;
        }
        if ("No change".equals(recommendationApplyState(recommendation))) {
            return false;
        }
        String before = safe(recommendation.beforeValue()).trim();
        String after = safe(recommendation.afterValue()).trim();
        if (sameSettingValue(before, after)) {
            return false;
        }
        String current = currentValueForRecommendation(recommendation);
        return current.isBlank() || !sameSettingValue(current, after);
    }

    private String currentValueForRecommendation(PerformanceRecommendation recommendation) {
        String key = safe(recommendation.settingKey());
        for (PerformanceSettingDescriptor setting : this.providerSettings) {
            if (matchesSettingKey(setting.settingId(), key)) {
                return safe(setting.currentValue());
            }
        }
        PerformanceSnapshot snapshot = PerformanceMonitor.freshSnapshot(MinecraftClient.getInstance());
        return switch (key.toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "render_distance" -> String.valueOf(snapshot.renderDistance());
            case "simulation_distance" -> String.valueOf(snapshot.simulationDistance());
            case "max_fps" -> String.valueOf(snapshot.maxFps());
            case "clouds" -> snapshot.cloudsMode();
            case "entity_distance" -> format2(snapshot.entityDistanceScale());
            case "mipmaps" -> String.valueOf(snapshot.mipmapLevels());
            case "particles" -> snapshot.particlesMode();
            case "graphics_mode" -> snapshot.graphicsMode();
            case "smooth_lighting" -> String.valueOf(snapshot.smoothLighting());
            case "biome_blend" -> String.valueOf(snapshot.biomeBlend());
            case "entity_shadows" -> String.valueOf(snapshot.entityShadows());
            case "vsync" -> String.valueOf(snapshot.vsync());
            default -> "";
        };
    }

    private String displayBeforeValue(PerformanceRecommendation recommendation) {
        String current = currentValueForRecommendation(recommendation);
        return current.isBlank() ? safe(recommendation.beforeValue()) : current;
    }

    private String normalizeSettingKey(String key) {
        return safe(key).toLowerCase(Locale.ROOT).replace('-', '_').replace('.', '_');
    }

    private boolean sameSettingValue(String left, String right) {
        String a = safe(left).trim();
        String b = safe(right).trim();
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        try {
            return Math.abs(Double.parseDouble(a) - Double.parseDouble(b)) < 0.005D;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean settingNeedsChange(PerformanceSettingDescriptor setting) {
        if (setting == null) {
            return false;
        }
        return !safe(setting.currentValue()).trim().equalsIgnoreCase(safe(setting.recommendedValue()).trim());
    }

    private String lastApplySummary() {
        if (this.lastProviderResults.isEmpty()) {
            return "none";
        }
        long changed = this.lastProviderResults.stream().filter(PerformanceProviderApplyResult::changed).count();
        return changed + "/" + this.lastProviderResults.size() + " changed";
    }

    private boolean matchesSettingKey(String settingId, String recommendationKey) {
        return PerformanceSettingKeyMatcher.matches(settingId, recommendationKey);
    }

    private void syncOptimizationTestResult() {
        long resultAt = PerformanceOptimizationTestService.latestResultAtMillis();
        if (resultAt <= 0L || resultAt == this.observedBenchmarkResultAtMillis) {
            return;
        }
        PerformanceBenchmarkResult result = PerformanceOptimizationTestService.latestResult();
        if (result == null) {
            return;
        }
        this.observedBenchmarkResultAtMillis = resultAt;
        this.latestBenchmark = result;
        this.recommendations = result.recommendations();
        this.status = result.summary();
        this.runtimeContext = PerformanceRuntimeContextService.capture(MinecraftClient.getInstance());
        this.runtimeContextAtMillis = System.currentTimeMillis();
        PerformanceProfileManager.saveProfileSuggestion(MinecraftClient.getInstance(), this.activeMode, result.snapshot());
        refreshProviderSettings();
        this.mainScroll = 0;
    }

    private int renderRecommendations(DrawContext context, int x, int y, int width) {
        this.recommendationHitboxes.clear();
        y = sectionHeader(context, x, y, width, "Recommended changes");
        List<PerformanceRecommendation> visible = visibleRecommendations();
        if (visible.isEmpty()) {
            return renderInfoRow(context, x, y, width, exact("State", searchQuery().isEmpty() ? "No pending changes. Current verified values already match the active targets." : "No recommendations match the current search.", new Color(uiColorSaveSuccessColor, true).getRGB()));
        }
        for (PerformanceRecommendation recommendation : visible) {
            int accent = severityDisplayColor(recommendation.severity());
            String state = recommendationApplyState(recommendation);
            String title = recommendation.severity().label() + " | " + recommendation.title();
            String change = displayBeforeValue(recommendation) + " -> " + safe(recommendation.afterValue());
            if (!state.isBlank()) {
                change += " | " + state;
            }
            List<OrderedText> reasonLines = wrapped(systemVoice(recommendation.reason()), Math.max(20, width - 20));
            List<OrderedText> changeLines = wrapped(change, Math.max(20, width - 20));
            int rowHeight = 28 + reasonLines.size() * LINE_HEIGHT + changeLines.size() * LINE_HEIGHT;
            this.recommendationHitboxes.add(new RecommendationHitbox(x, y, width, rowHeight, recommendation));
            context.fill(x, y, x + width, y + rowHeight, withAlpha(0xFF000000, 38));
            context.fill(x, y, x + 3, y + rowHeight, withAlpha(accent, 190));
            context.drawBorder(x, y, width, rowHeight, shadedBorder(recommendation.bottleneck().color()));
            String titleDisplay = trimToPixels(title, Math.max(20, width - 16));
            context.drawText(this.textRenderer, titleDisplay, x + 8, y + 5, accent, false);
            int lineY = y + 16;
            lineY = drawWrapped(context, changeLines, x + 8, lineY, state.isBlank() ? 0xFFD7D7D7 : applyStateColor(state));
            drawWrapped(context, reasonLines, x + 8, lineY, new Color(uiColorContentBaseDescriptionText, true).getRGB());
            if (isOver(this.hoverTooltipX, this.hoverTooltipY, x, y, width, rowHeight)) {
                this.hoverTooltipLines = recommendationTooltip(recommendation);
            }
            y += rowHeight + 4;
        }
        return y;
    }

    private String recommendationApplyState(PerformanceRecommendation recommendation) {
        String appliedTarget = this.appliedTargetsBySetting.get(normalizeSettingKey(recommendation.settingKey()));
        if (appliedTarget != null && sameSettingValue(appliedTarget, recommendation.afterValue())) {
            return "Applied";
        }
        for (PerformanceApplyEntryResult entry : this.lastApplyEntries) {
            if (entry.recommendationId().equals(recommendation.id())) {
                return switch (entry.status()) {
                    case "applied" -> "Applied";
                    case "failed" -> "Failed";
                    case "skipped" -> "Review";
                    case "provider-or-unsupported" -> "";
                    default -> entry.status();
                };
            }
        }
        for (PerformanceProviderApplyResult result : this.lastProviderResults) {
            if (matchesSettingKey(result.settingId(), recommendation.settingKey())) {
                return result.changed() ? "Applied" : "No change";
            }
        }
        return "";
    }

    private int applyStateColor(String state) {
        return switch (state) {
            case "Applied" -> new Color(uiColorSaveSuccessColor, true).getRGB();
            case "Review" -> new Color(uiColorToolTipWarning, true).getRGB();
            case "Failed" -> new Color(uiColorToolTipError, true).getRGB();
            default -> new Color(uiColorToolTipSecondary, true).getRGB();
        };
    }

    private void scrollMain(int amount) {
        int maxScroll = Math.max(0, this.mainContentHeight - this.mainViewportHeight);
        this.mainScroll = Math.max(0, Math.min(maxScroll, this.mainScroll + amount));
    }

    private void dragMainScrollbar(int mouseY) {
        int trackHeight = this.mainViewportHeight;
        int thumbHeight = Math.max(20, (int) (trackHeight * (this.mainViewportHeight / (double) Math.max(1, this.mainContentHeight))));
        int maxThumbTravel = Math.max(1, trackHeight - thumbHeight);
        int thumbY = Math.max(this.mainViewportY, Math.min(this.mainViewportY + maxThumbTravel, mouseY - this.mainScrollbarDragOffset));
        int maxScroll = Math.max(0, this.mainContentHeight - this.mainViewportHeight);
        this.mainScroll = (int) ((thumbY - this.mainViewportY) / (double) maxThumbTravel * maxScroll);
    }

    private int fpsColor(int fps) {
        if (fps <= 0) return 0xFF8D8D8D;
        if (fps >= 60) return 0xFF2DA700;
        if (fps >= 45) return 0xFFE3B735;
        if (fps >= 30) return 0xFFE06A21;
        return 0xFFA7003A;
    }

    private int memoryColor(double pressure) {
        if (pressure >= 0.90D) return 0xFFA7003A;
        if (pressure >= 0.80D) return 0xFFE06A21;
        if (pressure >= 0.68D) return 0xFFE3B735;
        return 0xFF2DA700;
    }

    private int pressureColor(double value) {
        if (value >= 0.85D) return 0xFFA7003A;
        if (value >= 0.65D) return 0xFFE06A21;
        if (value >= 0.38D) return 0xFFE3B735;
        return 0xFF2DA700;
    }

    private int shaderColor(double value) {
        if (value >= 0.70D) return 0xFFB0199E;
        if (value >= 0.40D) return 0xFF8B39DD;
        return 0xFF5D54D8;
    }

    private int loadColor(double load) {
        return load < 0.0D ? 0xFF8D8D8D : pressureColor(load);
    }

    private int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    private int shadedBorder(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = (int) (r * 0.70D);
        g = (int) (g * 0.70D);
        b = (int) (b * 0.70D);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private int softText(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = Math.min(255, (int) (r * 0.65D + 90));
        g = Math.min(255, (int) (g * 0.65D + 90));
        b = Math.min(255, (int) (b * 0.65D + 90));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void drawSmallLine(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int error = dx - dy;
        int x = x1;
        int y = y1;
        while (true) {
            context.fill(x, y, x + 1, y + 1, color);
            if (x == x2 && y == y2) {
                break;
            }
            int e2 = error * 2;
            if (e2 > -dy) {
                error -= dy;
                x += sx;
            }
            if (e2 < dx) {
                error += dx;
                y += sy;
            }
        }
    }

    private String trimToPixels(String value, int maxPixels) {
        String safeValue = safe(value);
        if (maxPixels <= 0) {
            return "";
        }
        if (this.textRenderer.getWidth(safeValue) <= maxPixels) {
            return safeValue;
        }
        String ellipsis = "...";
        int end = safeValue.length();
        while (end > 0 && this.textRenderer.getWidth(safeValue.substring(0, end) + ellipsis) > maxPixels) {
            end--;
        }
        if (end <= 0) {
            return this.textRenderer.getWidth(ellipsis) <= maxPixels ? ellipsis : "";
        }
        return safeValue.substring(0, end) + ellipsis;
    }

    private void renderHoverTooltip(DrawContext context) {
        if (this.hoverTooltipLines == null || this.hoverTooltipLines.isEmpty()) {
            return;
        }
        int x = Math.min(this.width - 18, Math.max(8, this.hoverTooltipX + 10));
        int y = Math.min(this.height - 18, Math.max(8, this.hoverTooltipY + 12));
        context.drawTooltip(this.textRenderer, this.hoverTooltipLines, Optional.empty(), x, y);
    }

    private List<Text> recommendationTooltip(PerformanceRecommendation recommendation) {
        int severityColor = severityDisplayColor(recommendation.severity());
        int labelColor = new Color(uiColorToolTipLabel, true).getRGB();
        int primaryColor = new Color(uiColorToolTipPrimary, true).getRGB();
        int secondaryColor = new Color(uiColorToolTipSecondary, true).getRGB();
        int ideaColor = new Color(uiColorToolTipIdea, true).getRGB();
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal(recommendation.severity().label()).setStyle(Style.EMPTY.withColor(severityColor).withBold(true))
            .append(Text.literal("  " + recommendation.title()).setStyle(Style.EMPTY.withColor(primaryColor))));
        lines.add(Text.literal("Setting: ").setStyle(Style.EMPTY.withColor(labelColor))
            .append(Text.literal(safe(recommendation.settingKey())).setStyle(Style.EMPTY.withColor(primaryColor))));
        lines.add(Text.literal("Change: ").setStyle(Style.EMPTY.withColor(labelColor))
            .append(Text.literal(displayBeforeValue(recommendation)).setStyle(Style.EMPTY.withColor(secondaryColor)))
            .append(Text.literal(" -> ").setStyle(Style.EMPTY.withColor(labelColor)))
            .append(Text.literal(safe(recommendation.afterValue())).setStyle(Style.EMPTY.withColor(severityColor).withBold(true))));
        String applyState = recommendationApplyState(recommendation);
        if (!applyState.isBlank()) {
            lines.add(Text.literal("State: ").setStyle(Style.EMPTY.withColor(labelColor))
                .append(Text.literal(applyState).setStyle(Style.EMPTY.withColor(applyStateColor(applyState)).withBold(true))));
        }
        lines.add(Text.literal("Reason:").setStyle(Style.EMPTY.withColor(ideaColor).withBold(true)));
        for (String line : wrapTooltipText(systemVoice(recommendation.reason()), 72)) {
            lines.add(Text.literal("  " + line).setStyle(Style.EMPTY.withColor(secondaryColor)));
        }
        lines.add(Text.literal("Source: ").setStyle(Style.EMPTY.withColor(labelColor))
            .append(Text.literal(recommendationSource(recommendation)).setStyle(Style.EMPTY.withColor(primaryColor))));
        return lines;
    }

    private List<Text> signalTooltip(String label, String valueText, boolean inactive) {
        int stateColor = inactive ? 0xFF8D8D8D : signalValueColor(label, valueText);
        return List.of(
            Text.literal(label).setStyle(Style.EMPTY.withColor(new Color(uiColorToolTipPrimary, true).getRGB()).withBold(true)),
            Text.literal("State: ").setStyle(Style.EMPTY.withColor(new Color(uiColorToolTipLabel, true).getRGB()))
                .append(Text.literal(inactive ? "Inactive" : valueText).setStyle(Style.EMPTY.withColor(stateColor).withBold(!inactive))),
            Text.literal("Source: ").setStyle(Style.EMPTY.withColor(new Color(uiColorToolTipLabel, true).getRGB()))
                .append(Text.literal(signalSource(label)).setStyle(Style.EMPTY.withColor(new Color(uiColorToolTipPrimary, true).getRGB())))
        );
    }

    private List<Text> diagnosticTooltip(String title, String value, String action) {
        int valueColor = diagnosticValueColor(title);
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal(title).setStyle(Style.EMPTY.withColor(new Color(uiColorToolTipPrimary, true).getRGB()).withBold(true)));
        lines.add(Text.literal("Value: ").setStyle(Style.EMPTY.withColor(new Color(uiColorToolTipLabel, true).getRGB()))
            .append(Text.literal(value).setStyle(Style.EMPTY.withColor(valueColor))));
        lines.add(Text.literal("Interpretation:").setStyle(Style.EMPTY.withColor(new Color(uiColorToolTipIdea, true).getRGB()).withBold(true)));
        for (String line : wrapTooltipText(systemVoice(action), 72)) {
            lines.add(Text.literal("  " + line).setStyle(Style.EMPTY.withColor(new Color(uiColorToolTipSecondary, true).getRGB())));
        }
        lines.add(Text.literal("Source: ").setStyle(Style.EMPTY.withColor(new Color(uiColorToolTipLabel, true).getRGB()))
            .append(Text.literal(diagnosticSource(title)).setStyle(Style.EMPTY.withColor(new Color(uiColorToolTipPrimary, true).getRGB()))));
        return lines;
    }

    private int severityDisplayColor(PerformanceRecommendation.Severity severity) {
        if (severity == PerformanceRecommendation.Severity.MANUAL_REVIEW) {
            return new Color(uiColorToolTipWarning, true).getRGB();
        }
        if (severity == PerformanceRecommendation.Severity.APPLIED) {
            return new Color(uiColorSaveSuccessColor, true).getRGB();
        }
        return severity.color();
    }

    private int diagnosticValueColor(String title) {
        PerformanceSnapshot snapshot = this.chartSnapshot == null ? PerformanceMonitor.latestSnapshot(MinecraftClient.getInstance()) : this.chartSnapshot;
        String lower = safe(title).toLowerCase(Locale.ROOT);
        if (lower.contains("fps")) return fpsColor(snapshot.fps());
        if (lower.contains("frame")) return pressureColor(Math.min(1.0D, snapshot.frameTimeMs() / 120.0D));
        if (lower.contains("memory")) return memoryColor(snapshot.memoryPressure());
        if (lower.contains("garbage")) return pressureColor(snapshot.gcPressure());
        if (lower.contains("entity")) return pressureColor(Math.min(1.0D, snapshot.entityCount() / 220.0D));
        if (lower.contains("chunk") || lower.contains("world")) return worldActive(snapshot) ? pressureColor(snapshot.chunkStress()) : 0xFF8D8D8D;
        if (lower.contains("shader")) return shaderColor(snapshot.shaderPressure());
        if (lower.contains("modpack")) return pressureColor(snapshot.modLoadPressure());
        if (lower.contains("resource")) return pressureColor(snapshot.resourcePackPressure());
        return new Color(uiColorToolTipPrimary, true).getRGB();
    }

    private String recommendationSource(PerformanceRecommendation recommendation) {
        String key = safe(recommendation.settingKey());
        if (key.contains(".") || key.contains("::")) {
            return "verified optimization/shader config provider";
        }
        if ("loaded_mods".equals(key) || "resourcepacks".equals(key)) {
            return "Fabric Loader and Minecraft resource-pack manager";
        }
        if ("memory_allocation".equals(key)) {
            return "JVM runtime memory counters";
        }
        return "Minecraft GameOptions plus sampled performance signals";
    }

    private String diagnosticSource(String title) {
        String lower = safe(title).toLowerCase(Locale.ROOT);
        if (lower.contains("fps")) return "Minecraft frame metrics and rolling samples";
        if (lower.contains("frame")) return "Minecraft frame-time samples";
        if (lower.contains("memory")) return "JVM runtime used/max memory";
        if (lower.contains("garbage")) return "GarbageCollectorMXBean deltas";
        if (lower.contains("entity")) return "client-world entity iteration";
        if (lower.contains("chunk") || lower.contains("world")) return "frame spikes plus world/render-distance inference";
        if (lower.contains("shader")) return "verified active shader state plus frame-performance inference";
        if (lower.contains("modpack")) return "Fabric Loader loaded-mod count normalization";
        if (lower.contains("resource")) return "enabled resource-pack count normalization";
        return "latest performance snapshot";
    }

    private String signalSource(String label) {
        String lower = safe(label).toLowerCase(Locale.ROOT);
        if (lower.contains("fps")) return "rolling FPS samples";
        if (lower.contains("frame")) return "rolling frame-time samples";
        if (lower.contains("heap")) return "JVM runtime heap usage";
        if (lower.contains("gc")) return "GarbageCollectorMXBean sample-window deltas";
        if (lower.contains("chunk")) return "world context, render distance, and frame-spike inference";
        if (lower.contains("entity")) return "client-world entity count normalization";
        if (lower.contains("shader")) return "active shader detection plus frame-performance inference";
        if (lower.contains("resource")) return "enabled resource-pack count normalization";
        if (lower.contains("mod")) return "Fabric Loader loaded-mod count normalization";
        if (lower.contains("screen")) return "frame pressure observed while a screen is open";
        return "latest performance snapshot";
    }

    private int signalValueColor(String label, String valueText) {
        if (valueText == null || valueText.equals("inactive")) {
            return 0xFF8D8D8D;
        }
        double value;
        try {
            value = Double.parseDouble(valueText.replace("%", "").trim()) / 100.0D;
        } catch (NumberFormatException ignored) {
            value = 0.0D;
        }
        String lower = safe(label).toLowerCase(Locale.ROOT);
        if (lower.contains("shader")) return shaderColor(value);
        if (lower.contains("heap")) return memoryColor(value);
        return pressureColor(value);
    }

    private List<String> wrapTooltipText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return List.of("");
        }
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : value.split("\\s+")) {
            if (current.length() > 0 && current.length() + word.length() + 1 > maxChars) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private double configuredFpsTarget(PerformanceSnapshot snapshot) {
        if (snapshot == null) {
            return 60.0D;
        }
        if (snapshot.vsync() && this.hardwareProfile != null && this.hardwareProfile.refreshRate() > 0) {
            return this.hardwareProfile.refreshRate();
        }
        if (snapshot.maxFps() >= 260 || snapshot.maxFps() <= 0) {
            return 60.0D;
        }
        return Math.max(10.0D, snapshot.maxFps());
    }

    private String fpsAdvice(PerformanceSnapshot snapshot) {
        double target = configuredFpsTarget(snapshot);
        if (snapshot.averageFps() >= target * 0.95D && snapshot.onePercentLowFps() >= target * 0.65D) {
            return "The sample is close to the configured frame-rate target and the one-percent low is proportionally stable.";
        }
        if (snapshot.onePercentLowFps() < target * 0.50D) {
            return "The slowest one percent of sampled frames are weak relative to the configured target. Compare frame time, chunk, entity, and shader signals before applying changes.";
        }
        return "Average FPS is below the configured target, but benchmark phases are needed to identify which workload changes the pressure.";
    }

    private String gameplayPressureLabel(PerformanceSnapshot snapshot) {
        if (!worldActive(snapshot)) {
            return "inactive outside a world";
        }
        if (snapshot.primaryBottleneck() == PerformanceBottleneck.ENTITY_TICK) {
            return "entity/tick signal is strongest";
        }
        if (snapshot.entityCount() > 180) {
            return "high client entity density";
        }
        return "no strong entity signal";
    }

    private boolean worldActive(PerformanceSnapshot snapshot) {
        return snapshot != null && worldActive(snapshot.worldType());
    }

    private boolean worldActive(String worldType) {
        return "singleplayer".equals(worldType) || "server".equals(worldType);
    }

    private String percentText(double value) {
        return (int) Math.round(Math.max(0.0D, Math.min(1.0D, value)) * 100.0D) + "%";
    }

    private String format1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String format2(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String maxFpsLabel(int maxFps) {
        return maxFps >= 260 ? "unlimited FPS" : maxFps + " FPS cap";
    }

    private String displayModeText(PerformanceHardwareProfile profile) {
        if (profile == null || profile.monitorWidth() <= 0 || profile.monitorHeight() <= 0) {
            return "unavailable";
        }
        return profile.monitorWidth() + "x" + profile.monitorHeight() + (profile.refreshRate() > 0 ? " @ " + profile.refreshRate() + " Hz" : "");
    }

    private String windowRenderSize() {
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            int windowWidth = client.getWindow().getWidth();
            int windowHeight = client.getWindow().getHeight();
            int framebufferWidth = client.getWindow().getFramebufferWidth();
            int framebufferHeight = client.getWindow().getFramebufferHeight();
            double scale = client.getWindow().getScaleFactor();
            return windowWidth + "x" + windowHeight + " window | " + framebufferWidth + "x" + framebufferHeight + " framebuffer | GUI scale " + format2(scale) + "x";
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    private String loadText(double load) {
        return load < 0.0D ? "unavailable" : percentText(load);
    }

    private String memoryText(long mb) {
        return mb < 0L ? "unavailable" : mb == 0L ? "unknown" : mb + " MB";
    }

    private String storageProbeText(double value) {
        return value <= 0.0D ? "unavailable" : format1(value) + " MiB/s combined forced-write + read quick probe; useful only as a rough local I/O signal";
    }

    private String storageCapacityText() {
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            FileStore store = Files.getFileStore(gameDir);
            return formatGiB(store.getUsableSpace()) + " free / " + formatGiB(store.getTotalSpace()) + " total";
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    private String formatGiB(long bytes) {
        return String.format(Locale.ROOT, "%.1f GiB", bytes / 1024.0D / 1024.0D / 1024.0D);
    }

    private int currentLatencyMs() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.getNetworkHandler() == null) {
                return -1;
            }
            var entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
            return entry == null ? -1 : entry.getLatency();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private int latencyColor(int latency) {
        if (latency < 0) return 0xFF8D8D8D;
        if (latency <= 70) return 0xFF2DA700;
        if (latency <= 140) return 0xFFE3B735;
        if (latency <= 250) return 0xFFE06A21;
        return 0xFFA7003A;
    }

    private String searchQuery() {
        return this.searchField == null ? "" : this.searchField.getText().trim().toLowerCase(Locale.ROOT);
    }

    private boolean contains(String value, String query) {
        return safe(value).toLowerCase(Locale.ROOT).contains(query);
    }

    private String systemVoice(String text) {
        if (text == null) {
            return "";
        }
        return text
            .replace("Koil's", "the optimizer's")
            .replace("Koil ", "The optimizer ")
            .replace(" Koil", " the optimizer")
            .replace("koil ", "the optimizer ");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isOver(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private InfoLine exact(String label, String value, int color) {
        return new InfoLine(label, value, color, false);
    }

    private InfoLine estimate(String label, String value, int color) {
        return new InfoLine(label, value, color, true);
    }

    private enum ChartKind {
        FPS,
        FRAME_TIME,
        MEMORY,
        GC,
        ENTITY,
        CHUNK,
        SHADER,
        MOD_LOAD,
        RESOURCEPACK,
        WORLD_SIMULATION,
        FAULT_PRESSURE
    }

    private enum DiagnosticsPane {
        OVERVIEW("Overview", "Ovr"),
        RENDERING("Render", "GPU"),
        PROCESSING("CPU / Tick", "CPU"),
        MEMORY("Memory", "Mem"),
        WORLD("World", "Wld"),
        MODS("Mods", "Mod"),
        SERVER("Server", "Net"),
        ALL("All", "All");

        private final String label;
        private final String compactLabel;

        DiagnosticsPane(String label, String compactLabel) {
            this.label = label;
            this.compactLabel = compactLabel;
        }

        private String label() {
            return this.label;
        }

        private String displayLabel(boolean compact) {
            return compact ? this.compactLabel : this.label;
        }
    }

    private record InfoLine(String label, String value, int color, boolean estimated) {
    }

    private record ChartSpec(String title, ChartKind kind) {
    }

    private record PaneHitbox(DiagnosticsPane pane, int x, int y, int width, int height) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }

    private record RecommendationHitbox(int x, int y, int width, int height, PerformanceRecommendation recommendation) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }

    private record Rect(int x, int y, int width, int height) {
        private static final Rect EMPTY = new Rect(0, 0, 0, 0);

        private int right() {
            return x + width;
        }

        private int bottom() {
            return y + height;
        }

        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= right() && mouseY >= y && mouseY <= bottom();
        }
    }

    private record SessionState(
        PerformanceProfileMode activeMode,
        DiagnosticsPane activePane,
        String status,
        int mainScroll,
        PerformanceBenchmarkResult latestBenchmark,
        List<PerformanceRecommendation> recommendations,
        List<PerformanceProviderApplyResult> lastProviderResults,
        List<PerformanceApplyEntryResult> lastApplyEntries,
        Map<String, String> appliedTargetsBySetting,
        String searchText,
        long observedBenchmarkResultAtMillis
    ) {
    }
}
