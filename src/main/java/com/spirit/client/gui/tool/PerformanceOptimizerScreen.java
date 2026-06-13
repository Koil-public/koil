package com.spirit.client.gui.tool;

import com.spirit.Main;
import com.spirit.client.gui.TopBarLayout;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.performance.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.spirit.Main.LOGO_TEXTURE;
import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
public class PerformanceOptimizerScreen extends Screen {
    private static final int HEADER_HEIGHT = 60;
    private static final int CONTENT_TOP = 62;
    private static final int PANEL_CONTENT_START = CONTENT_TOP + 20;
    private static final int SIDE_WIDTH = 184;
    private static final int ROW_HEIGHT = 18;
    private static final int CHART_HEIGHT = 54;
    private static final int CHART_GAP = 24;
    private static final String TOP_BAR_BACK_LABEL = "<";
    private static final String PANE_OVERVIEW_LABEL = "Overview";
    private static final String PANE_RENDERING_LABEL = "Render";
    private static final String PANE_PROCESSING_LABEL = "Process";
    private static final String PANE_MEMORY_LABEL = "Memory";
    private static final String PANE_SERVER_LABEL = "Server";
    private static final String PANE_GAMEPLAY_LABEL = "Gameplay";
    private static final String PANE_UI_LABEL = "UI";
    private static final String PANE_WORLD_LABEL = "World";
    private static final String PANE_MODS_LABEL = "Mods";
    private static final String PANE_ALL_LABEL = "All";
    private static SessionState savedSession;

    private int liveChartBottom = CONTENT_TOP + 230;

    private final Screen parent;
    private PerformanceProfileMode activeMode = PerformanceProfileMode.AUTO;
    private PerformanceHardwareProfile hardwareProfile;
    private PerformanceRuntimeContext runtimeContext;
    private PerformanceBenchmarkResult latestBenchmark;
    private List<PerformanceRecommendation> recommendations = new ArrayList<>();
    private List<PerformanceSettingDescriptor> providerSettings = new ArrayList<>();
    private List<PerformanceProviderApplyResult> lastProviderResults = new ArrayList<>();
    private List<PerformanceApplyEntryResult> lastApplyEntries = new ArrayList<>();
    private Map<String, String> appliedTargetsBySetting = new LinkedHashMap<>();
    private TextFieldWidget searchField;
    private DiagnosticsPane activePane = DiagnosticsPane.OVERVIEW;
    private String status = "Run benchmark first. The system will collect a live sample window before recommending changes.";
    private int recommendationScroll;
    private boolean benchmarkRunning;
    private long benchmarkStartMillis;
    private int mainScroll;
    private int mainContentHeight;
    private int mainViewportX;
    private int mainViewportY;
    private int mainViewportWidth;
    private int mainViewportHeight;
    private boolean draggingMainScrollbar;
    private int mainScrollbarDragOffset;
    private long observedBenchmarkResultAtMillis;
    private List<Text> hoverTooltipLines = List.of();
    private int hoverTooltipX;
    private int hoverTooltipY;
    private List<PerformanceMonitor.Sample> chartSamples = List.of();
    private long chartCacheAtMillis;
    private int chartEntityMax = 1;
    private PerformanceSnapshot chartSnapshot;
    private String chartWorldType = "unknown";
    private String chartScreenName = "none";
    private final List<RecommendationHitbox> recommendationHitboxes = new ArrayList<>();

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
        initTopBar();
        restoreSession();
        int buttonY = this.height - 28;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Benchmark"), button -> runBenchmark())
                .dimensions(SIDE_WIDTH + 24, buttonY, 88, 20)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Apply Supported"), button -> applySafe())
                .dimensions(SIDE_WIDTH + 118, buttonY, 110, 20)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Revert Last"), button -> revertLast())
                .dimensions(SIDE_WIDTH + 234, buttonY, 86, 20)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Report"), button -> writeReport())
                .dimensions(SIDE_WIDTH + 326, buttonY, 62, 20)
                .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> closeAndRemember())
                .dimensions(this.width - 74, buttonY, 60, 20)
                .build());
        if (this.hardwareProfile == null) {
            this.hardwareProfile = PerformanceHardwareScanner.scan(MinecraftClient.getInstance());
        }
        this.runtimeContext = PerformanceRuntimeContextService.capture(MinecraftClient.getInstance());
        if (this.recommendations.isEmpty()) {
            this.recommendations = PerformanceRecommendationEngine.recommend(MinecraftClient.getInstance(), this.activeMode, PerformanceMonitor.freshSnapshot(MinecraftClient.getInstance()));
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
        if (this.searchField != null) {
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

    private void initTopBar() {
        TopBarLayout layout = getTopBarLayout();
        this.searchField = new TextFieldWidget(
                this.textRenderer,
                layout.searchFieldX(TOP_BAR_BACK_LABEL),
                TopBarLayout.SEARCH_FIELD_Y,
                topBarSearchWidth(),
                TopBarLayout.SEARCH_FIELD_HEIGHT,
                Text.literal("performance-search")
        );
        this.searchField.setMaxLength(256);
        this.searchField.setPlaceholder(Text.literal("Filter providers, settings, recommendations"));
        this.searchField.setChangedListener(value -> refreshProviderSettings());
        this.addDrawableChild(this.searchField);
    }

    @Override
    public void tick() {
        syncOptimizationTestResult();
        if (this.benchmarkRunning) {
            long elapsed = System.currentTimeMillis() - this.benchmarkStartMillis;
            if (elapsed >= 5000L) {
                finishBenchmark();
            } else {
                this.status = "Benchmarking live frame, memory, entity, and chunk-pressure samples... " + Math.max(0, 5 - (elapsed / 1000L)) + "s";
            }
        }
        if (PerformanceOptimizationTestService.active()) {
            this.status = PerformanceOptimizationTestService.status();
        }
        saveSession();
        if (this.searchField != null) {
            TopBarLayout layout = getTopBarLayout();
            this.searchField.setX(layout.searchFieldX(TOP_BAR_BACK_LABEL));
            this.searchField.setY(TopBarLayout.SEARCH_FIELD_Y);
            this.searchField.setWidth(topBarSearchWidth());
            this.searchField.tick();
        }
        super.tick();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MinecraftClient client = MinecraftClient.getInstance();
        KoilScreenBackgrounds.render(context, client, this.width, this.height);
        if (KoilScreenBackgrounds.canRender(client)) {
            context.fill(0, 0, this.width, this.height, KoilScreenBackgrounds.overlayColor(client));
        }
        renderChrome(context);
        renderSidePanel(context, mouseX, mouseY);
        this.hoverTooltipLines = List.of();
        this.hoverTooltipX = mouseX;
        this.hoverTooltipY = mouseY;
        ensureChartCache();
        renderMainPanel(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
        renderHoverTooltip(context);
    }

    private void renderChrome(DrawContext context) {
        assert client != null;
        int topBarBackground = withAlpha(uiColorContentBase, 176);
        int topPanelBackground = withAlpha(uiColorContentBase, 196);
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
        context.drawText(this.textRenderer, "Diagnostics - InDEV", 68, 35, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawBorder(0, 0, this.width, this.height,  new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, 40, this.width, 68, new Color(uiColorHeader, true).getRGB());
        context.fill(0, 40, this.width, 68, topBarBackground);
        context.drawBorder(0, 40, this.width, 28,  new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(172, 70, this.width, this.height, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(172, 70, this.width, this.height,  new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, 70, 170, this.height, new Color(uiColorContentBase, true).getRGB());
        context.drawBorder(0, 70, 170, this.height,  new Color(uiColorBackgroundBorder, true).getRGB());

        renderTopBarButton(context, 10, TOP_BAR_BACK_LABEL);
        for (int i = 0; i < getTopBarActionLabels().size(); i++) {
            String label = getTopBarActionLabels().get(i);
            renderTopBarButton(context, getTopBarButtonX(i), label, false);
        }
    }

    private void renderSidePanel(DrawContext context, int mouseX, int mouseY) {
        int x = 8;
        int y = PANEL_CONTENT_START;
        int rowRight = 166;
        context.drawText(this.textRenderer, "Profiles", x, y, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        y += 18;
        for (PerformanceProfileMode mode : PerformanceProfileMode.values()) {
            boolean selected = mode == this.activeMode;
            context.fill(x, y, rowRight, y + 14, selected ? withAlpha(0xFFFFFFFF, 18) : withAlpha(0xFF000000, 35));
            context.fill(x, y, x + 2, y + 14, selected ? withAlpha(mode.color(), 150) : muted(mode.color()));
            context.drawBorder(x, y, rowRight - x, 14, new Color(uiColorBackgroundBorder, true).getRGB());
            context.drawText(this.textRenderer, trim(mode.label(), 20), x + 7, y + 3, 0xFFE2E2E2, false);
            y += 15;
        }
        y += 10;
        PerformanceSnapshot snapshot = this.chartSnapshot == null ? PerformanceMonitor.latestSnapshot(MinecraftClient.getInstance()) : this.chartSnapshot;
        drawMetric(context, x, y, "State", snapshot.primaryBottleneck().label(), snapshot.primaryBottleneck().color());
        drawMetric(context, x, y + 18, "FPS", String.valueOf(snapshot.fps()), fpsColor(snapshot.fps()));
        drawMetric(context, x, y + 36, "Frame", snapshot.frameTimeMs() + " ms", snapshot.maxFrameTimeMs() > 75 ? 0xFFE06A21 : 0xFF2DA700);
        drawMetric(context, x, y + 54, "Memory", snapshot.usedMemoryMb() + "/" + snapshot.maxMemoryMb() + " MB", memoryColor(snapshot.memoryPressure()));
        String warning = PerformanceMonitor.latestWarning();
        if (!warning.isBlank()) {
            context.drawText(this.textRenderer, trim(warning, 32), x, y + 82, 0xFFE3B735, false);
        }
    }

    private void renderMainPanel(DrawContext context, int mouseX, int mouseY) {
        int x = SIDE_WIDTH + 12;
        int y = PANEL_CONTENT_START;
        int right = this.width - 18;
        int bottom = this.height - 38;
        this.mainViewportX = x;
        this.mainViewportY = y;
        this.mainViewportWidth = Math.max(1, right - x - 8);
        this.mainViewportHeight = Math.max(1, bottom - y);
        int contentY = y - this.mainScroll;
        int contentEndOffset;
        context.enableScissor(Math.max(0, x - 18), y, Math.min(this.width, right + 28), bottom);
        context.drawText(this.textRenderer, "Live analysis", x, contentY, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, trim(systemVoice(this.status), Math.max(24, (right - x) / 6)), x, contentY + 12, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
        PerformanceSnapshot snapshot = PerformanceMonitor.latestSnapshot(MinecraftClient.getInstance());
        context.drawText(this.textRenderer, "Sources: Minecraft runtime/options, JVM memory/GC, Fabric Loader, GL strings, and editable config files. Pressure rows are estimates.", x, contentY + 25, 0xFF9FAFC2, false);
        int cardY = contentY + 46;
        drawInfoCard(context, x, cardY, 128, "Primary", snapshot.primaryBottleneck().label(), snapshot.primaryBottleneck().color());
        drawInfoCard(context, x + 136, cardY, 128, "Average FPS", String.valueOf(snapshot.averageFps()), fpsColor((int) snapshot.averageFps()));
        drawInfoCard(context, x + 272, cardY, 128, "1% Low", String.valueOf(snapshot.onePercentLowFps()), fpsColor((int) snapshot.onePercentLowFps()));
        drawInfoCard(context, x + 408, cardY, 128, "Entities", String.valueOf(snapshot.entityCount()), snapshot.entityCount() > 180 ? 0xFFE6862C : 0xFF2DA700);
        int hardwareY = cardY + 54;
        int cursorY = hardwareY;
        if (shows(DiagnosticsPane.OVERVIEW)) {
            renderCausePanel(context, x, cursorY, right - x - 16, snapshot);
            cursorY += 58;
        }
        if (shows(DiagnosticsPane.OVERVIEW) || shows(DiagnosticsPane.RENDERING)) {
            renderRuntimeContext(context, x, cursorY, right - x - 16);
            cursorY += 80;
        }
        if (shows(DiagnosticsPane.OVERVIEW) || shows(DiagnosticsPane.SERVER) || shows(DiagnosticsPane.GAMEPLAY) || shows(DiagnosticsPane.UI) || shows(DiagnosticsPane.WORLD)) {
            renderDataResponsibilityPanel(context, x, cursorY, right - x - 16, snapshot);
            cursorY += 116;
        }
        if (shows(DiagnosticsPane.OVERVIEW) || shows(DiagnosticsPane.MODS)) {
            renderHardwareSummary(context, x, cursorY, right - x - 16);
            cursorY += 92;
        }
        int chartY = cursorY;
        int chartWidth = Math.max(120, (right - x - 28) / 2);
        int chartRightX = x + chartWidth + 12;
        int row = 0;
        if (shows(DiagnosticsPane.OVERVIEW) || shows(DiagnosticsPane.RENDERING)) {
            renderMiniChart(context, x, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "FPS stability", ChartKind.FPS);
            renderMiniChart(context, chartRightX, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "Frame time spikes", ChartKind.FRAME_TIME);
            row++;
        }
        if (shows(DiagnosticsPane.MEMORY)) {
            renderMiniChart(context, x, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "Memory pressure", ChartKind.MEMORY);
            renderMiniChart(context, chartRightX, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "GC activity", ChartKind.GC);
            row++;
        }
        if (shows(DiagnosticsPane.PROCESSING)) {
            renderMiniChart(context, x, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "Entity load", ChartKind.ENTITY);
            renderMiniChart(context, chartRightX, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "Tick pressure", ChartKind.FAULT_PRESSURE);
            row++;
        }
        if (shows(DiagnosticsPane.RENDERING)) {
            renderMiniChart(context, x, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "Chunk stress", ChartKind.CHUNK);
            renderMiniChart(context, chartRightX, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "Shader pressure", ChartKind.SHADER);
            row++;
        }
        if (shows(DiagnosticsPane.MODS)) {
            renderMiniChart(context, x, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "Modpack pressure", ChartKind.MOD_LOAD);
            renderMiniChart(context, chartRightX, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "Resourcepack pressure", ChartKind.RESOURCEPACK);
            row++;
        }
        if (shows(DiagnosticsPane.SERVER)) {
            renderMiniChart(context, x, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "Server wait / network context", ChartKind.SERVER_WAIT);
            renderMiniChart(context, chartRightX, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "Server-side load risk", ChartKind.SERVER_CONTEXT);
            row++;
        }
        if (shows(DiagnosticsPane.GAMEPLAY)) {
            renderMiniChart(context, x, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "Gameplay entity pressure", ChartKind.ENTITY);
            renderMiniChart(context, chartRightX, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "Input/update pressure", ChartKind.GAMEPLAY);
            row++;
        }
        if (shows(DiagnosticsPane.UI)) {
            renderMiniChart(context, x, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "UI frame cost", ChartKind.UI_FRAME);
            renderMiniChart(context, chartRightX, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "UI memory pressure", ChartKind.UI_MEMORY);
            row++;
        }
        if (shows(DiagnosticsPane.WORLD)) {
            renderMiniChart(context, x, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "World/chunk streaming", ChartKind.CHUNK);
            renderMiniChart(context, chartRightX, chartY + row * (CHART_HEIGHT + 12), chartWidth, CHART_HEIGHT, "World simulation load", ChartKind.WORLD_SIMULATION);
            row++;
        }
        int faultY = chartY + Math.max(1, row) * (CHART_HEIGHT + 12) + 4;
        if (shows(DiagnosticsPane.OVERVIEW) || shows(DiagnosticsPane.PROCESSING) || shows(DiagnosticsPane.MODS)) {
            renderFaultDomainPanel(context, x, faultY, right - x - 16, snapshot);
            faultY += 74;
        }
        renderAdvancedSignals(context, x, faultY, right - x - 16, snapshot);
        int providerY = faultY + 170;
        if (shows(DiagnosticsPane.MODS)) {
            renderProviderSettings(context, x, providerY, right - x - 16);
            providerY += 58 + Math.max(1, Math.min(12, filteredProviderSettings().size())) * 30;
        }
        int recY = providerY;
        renderRecommendations(context, x, recY, right - x - 16);
        contentEndOffset = (recY - contentY) + 54 + Math.max(1, visibleRecommendations().size()) * 36;
        this.mainContentHeight = contentEndOffset;
        this.liveChartBottom = chartY + CHART_HEIGHT;
        context.disableScissor();
        scrollMain(0);
        renderMainScrollbar(context, mouseX, mouseY);
    }

    private void renderProviderSettings(DrawContext context, int x, int y, int width) {
        List<PerformanceSettingDescriptor> settings = filteredProviderSettings();
        context.drawText(this.textRenderer, "Optimization providers / live settings", x, y, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, "Detected settings: " + this.providerSettings.size() + " | visible: " + settings.size() + " | last apply: " + lastApplySummary(), x, y + 12, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
        int rowY = y + 30;
        if (settings.isEmpty()) {
            context.drawText(this.textRenderer, "No provider settings match the current filter.", x + 8, rowY, 0xFF8D8D8D, false);
            return;
        }
        int shown = 0;
        for (PerformanceSettingDescriptor setting : settings) {
            if (shown >= 12) {
                context.drawText(this.textRenderer, "... " + (settings.size() - shown) + " more settings hidden by compact view", x + 8, rowY + 4, 0xFF8D8D8D, false);
                break;
            }
            boolean needsChange = settingNeedsChange(setting);
            int accent = !needsChange ? new Color(uiColorSaveSuccessColor, true).getRGB() : setting.liveApplySupported() ? 0xFF2DA700 : 0xFFE3B735;
            context.fill(x, rowY, x + width, rowY + 26, withAlpha(0xFF000000, 38));
            context.fill(x, rowY, x + 3, rowY + 26, accent);
            context.drawBorder(x, rowY, width, 26, shadedBorder(accent));
            context.drawText(this.textRenderer, setting.providerId() + " | " + trim(setting.label(), 32), x + 9, rowY + 4, 0xFFFFFFFF, false);
            String valueLine = needsChange
                    ? setting.currentValue() + " -> " + setting.recommendedValue() + " | " + (setting.requiresResourceReload() ? "reload requested" : "live")
                    : "Already optimized: " + setting.currentValue();
            context.drawText(this.textRenderer, trim(valueLine, Math.max(24, (width - 18) / 6)), x + 9, rowY + 15, needsChange ? 0xFFB8B8B8 : new Color(uiColorSaveSuccessColor, true).getRGB(), false);
            rowY += 30;
            shown++;
        }
    }

    private void renderCausePanel(DrawContext context, int x, int y, int width, PerformanceSnapshot snapshot) {
        context.fill(x, y, x + width, y + 46, withAlpha(0xFF000000, 42));
        context.fill(x, y, x + 3, y + 46, snapshot.primaryBottleneck().color());
        context.drawBorder(x, y, width, 46, shadedBorder(snapshot.primaryBottleneck().color()));
        context.drawText(this.textRenderer, snapshot.primaryBottleneck().label() + " diagnosis", x + 10, y + 7, softText(snapshot.primaryBottleneck().color()), false);
        context.drawText(this.textRenderer, trim(snapshot.likelyCause(), Math.max(30, (width - 20) / 6)), x + 10, y + 22, 0xFFE2E2E2, false);
    }

    private void renderRuntimeContext(DrawContext context, int x, int y, int width) {
        if (this.runtimeContext == null || System.currentTimeMillis() - this.runtimeContext.capturedAtMillis() > 5000L) {
            this.runtimeContext = PerformanceRuntimeContextService.capture(MinecraftClient.getInstance());
        }
        context.drawText(this.textRenderer, "World / profile context", x, y, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        if (this.runtimeContext != null) {
            context.drawText(this.textRenderer, "Target: " + runtimeContext.worldType() + " | " + trim(runtimeContext.profileKey(), 56), x, y + 14, 0xFFE2E2E2, false);
            context.drawText(this.textRenderer, "Suggested profile: " + runtimeContext.suggestedProfile() + " | Dimension: " + runtimeContext.dimension(), x, y + 28, 0xFF00A8D8, false);
            context.drawText(this.textRenderer, "Resourcepacks: " + runtimeContext.resourcePackCount() + " | Shader: " + runtimeContext.shaderState(), x, y + 42, 0xFFE2E2E2, false);
            String mods = runtimeContext.optimizationModConfigs().isEmpty() ? "No optimization config files detected" : String.join(", ", runtimeContext.optimizationModConfigs());
            context.drawText(this.textRenderer, trim(mods, Math.max(36, width / 6)), x, y + 56, runtimeContext.optimizationModConfigs().isEmpty() ? 0xFF8D8D8D : 0xFF2DA700, false);
        }
    }

    private void renderDataResponsibilityPanel(DrawContext context, int x, int y, int width, PerformanceSnapshot snapshot) {
        if (this.runtimeContext == null || System.currentTimeMillis() - this.runtimeContext.capturedAtMillis() > 5000L) {
            this.runtimeContext = PerformanceRuntimeContextService.capture(MinecraftClient.getInstance());
        }
        context.drawText(this.textRenderer, "Performance responsibility map", x, y, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        int cellWidth = Math.max(130, (width - 14) / 3);
        int rowY = y + 16;
        drawResponsibilityCell(context, x, rowY, cellWidth, "Client", "Render/UI/Input", "Screen: " + currentScreenName(), 0xFF0085A4);
        drawResponsibilityCell(context, x + cellWidth + 7, rowY, cellWidth, "World", "Chunks/Entities/Ticks", "Dim: " + snapshot.worldType() + " | RD " + snapshot.renderDistance() + " SD " + snapshot.simulationDistance(), 0xFFE3B735);
        drawResponsibilityCell(context, x + (cellWidth + 7) * 2, rowY, cellWidth, "Server", serverTitle(), serverDetail(), 0xFF6F89A8);
        rowY += 43;
        drawResponsibilityCell(context, x, rowY, cellWidth, "Gameplay", "Entities/Input/Effects", snapshot.entityCount() + " entities | " + gameplayPressureLabel(snapshot), 0xFFE6862C);
        drawResponsibilityCell(context, x + cellWidth + 7, rowY, cellWidth, "Render", "Chunks/Shaders/Textures", renderPressureLabel(snapshot), 0xFF7400A4);
        drawResponsibilityCell(context, x + (cellWidth + 7) * 2, rowY, cellWidth, "Modpack", "Mods/Packs/Configs", snapshot.loadedModCount() + " mods | " + snapshot.resourcePackCount() + " packs | " + snapshot.optimizationModCount() + " configs", 0xFFC32222);
    }

    private void drawResponsibilityCell(DrawContext context, int x, int y, int width, String label, String owner, String detail, int color) {
        context.fill(x, y, x + width, y + 36, withAlpha(0xFF000000, 40));
        context.fill(x, y, x + width, y + 2, withAlpha(color, 135));
        context.drawBorder(x, y, width, 36, shadedBorder(color));
        context.drawText(this.textRenderer, label + " | " + owner, x + 7, y + 5, softText(color), false);
        context.drawText(this.textRenderer, trim(detail, Math.max(14, (width - 14) / 6)), x + 7, y + 19, 0xFFD7D7D7, false);
    }

    private String currentScreenName() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.currentScreen == null) {
            return "none";
        }
        return client.currentScreen.getClass().getSimpleName();
    }

    private String serverTitle() {
        if (this.runtimeContext == null) {
            return "Unknown";
        }
        return "server".equals(this.runtimeContext.worldType()) ? "Remote server data" : "singleplayer".equals(this.runtimeContext.worldType()) ? "Integrated server data" : "No active server";
    }

    private String serverDetail() {
        if (this.runtimeContext == null) {
            return "No runtime context";
        }
        if ("server".equals(this.runtimeContext.worldType())) {
            return trim(this.runtimeContext.serverAddress(), 36) + " | client can only infer server pressure";
        }
        if ("singleplayer".equals(this.runtimeContext.worldType())) {
            return trim(this.runtimeContext.worldName(), 30) + " | local ticks share CPU with client";
        }
        return "menu context";
    }

    private String gameplayPressureLabel(PerformanceSnapshot snapshot) {
        if (snapshot.primaryBottleneck() == PerformanceBottleneck.ENTITY_TICK) {
            return "entity/tick bound";
        }
        if (snapshot.entityCount() > 180) {
            return "high entity density";
        }
        return "stable";
    }

    private String renderPressureLabel(PerformanceSnapshot snapshot) {
        if (snapshot.primaryBottleneck() == PerformanceBottleneck.SHADER_RENDER) {
            return "shader bound";
        }
        if (snapshot.primaryBottleneck() == PerformanceBottleneck.GPU) {
            return "GPU bound";
        }
        if (snapshot.chunkStress() > 0.65D) {
            return "chunk/render distance pressure";
        }
        return "stable";
    }

    private void renderRecommendations(DrawContext context, int x, int y, int width) {
        this.recommendationHitboxes.clear();
        List<PerformanceRecommendation> visibleRecommendations = visibleRecommendations();
        int height = 46 + Math.max(1, visibleRecommendations.size()) * 36;
        context.drawText(this.textRenderer, "Recommended changes", x, y - 12, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        context.fill(x, y, x + width, y + height, withAlpha(uiColorContentBase, 130));
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());
        int rowY = y + 8;
        if (visibleRecommendations.isEmpty()) {
            context.drawText(this.textRenderer, "No pending changes. Current values already match the active targets.", x + 12, rowY + 8, new Color(uiColorSaveSuccessColor, true).getRGB(), false);
            return;
        }
        for (PerformanceRecommendation recommendation : visibleRecommendations) {
            this.recommendationHitboxes.add(new RecommendationHitbox(x + 6, rowY, width - 14, 32, recommendation));
            context.fill(x + 6, rowY, x + width - 8, rowY + 32, withAlpha(0xFF000000, 42));
            context.fill(x + 6, rowY, x + 9, rowY + 32, recommendation.severity().color());
            context.drawBorder(x + 6, rowY, width - 14, 32, shadedBorder(recommendation.bottleneck().color()));
            context.drawText(this.textRenderer, recommendation.severity().label(), x + 14, rowY + 4, softText(recommendation.severity().color()), false);
            String applyState = recommendationApplyState(recommendation);
            int stateWidth = applyState.isBlank() ? 0 : this.textRenderer.getWidth(applyState) + 10;
            context.drawText(this.textRenderer, trimToPixels(recommendation.title(), Math.max(40, width - 92 - stateWidth)), x + 74, rowY + 4, 0xFFFFFFFF, false);
            if (!applyState.isBlank()) {
                context.drawText(this.textRenderer, applyState, x + width - 16 - this.textRenderer.getWidth(applyState), rowY + 4, applyStateColor(applyState), false);
            }
            String valueText = displayBeforeValue(recommendation) + " -> " + recommendation.afterValue() + " | " + trim(systemVoice(recommendation.reason()), Math.max(18, (width - 120) / 6));
            context.drawText(this.textRenderer, trim(valueText, Math.max(30, (width - 28) / 6)), x + 14, rowY + 17, 0xFFD7D7D7, false);
            if (isOver(this.hoverTooltipX, this.hoverTooltipY, x + 6, rowY, width - 14, 32)) {
                this.hoverTooltipLines = recommendationTooltip(recommendation);
            }
            rowY += 36;
        }
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

    private void renderMiniChart(DrawContext context, int x, int y, int width, int height, String title, ChartKind kind) {
        if (y + height < this.mainViewportY || y > this.mainViewportY + this.mainViewportHeight) {
            return;
        }
        int accent = chartAccent(kind);
        context.fill(x, y, x + width, y + height, withAlpha(0xFF000000, 72));
        context.drawBorder(x, y, width, height, shadedBorder(accent));
        context.fill(x, y, x + 3, y + height, withAlpha(accent, 150));
        context.drawHorizontalLine(x + 4, x + width - 5, y + height / 2, withAlpha(0xFFFFFFFF, 34));
        if (kind == ChartKind.FPS) {
            int targetY = y + height - 8 - Math.min(height - 24, Math.max(1, 60 * (height - 24) / 120));
            drawDashedHorizontal(context, x + 8, x + width - 7, targetY, withAlpha(0xFFFFFFFF, 70));
        }
        List<PerformanceMonitor.Sample> samples = this.chartSamples;
        if (samples.size() < 2) {
            context.drawText(this.textRenderer, "Waiting for live samples...", x + 8, y + 22, 0xFF8D8D8D, false);
            return;
        }
        int graphLeft = x + 6;
        int graphRight = x + width - 8;
        int graphTop = y + 17;
        int graphBottom = y + height - 8;
        int graphWidth = Math.max(1, graphRight - graphLeft);
        int count = Math.min(samples.size(), Math.max(2, Math.min(graphWidth, 72)));
        int entityMax = this.chartEntityMax;
        int sampleStart = Math.max(0, samples.size() - count);
        for (int i = 1; i < count; i++) {
            PerformanceMonitor.Sample prev = samples.get(sampleStart + i - 1);
            PerformanceMonitor.Sample current = samples.get(sampleStart + i);
            int x1 = graphLeft + (i - 1) * graphWidth / Math.max(1, count - 1);
            int x2 = graphLeft + i * graphWidth / Math.max(1, count - 1);
            int y1 = chartY(prev, kind, graphTop, graphBottom, entityMax);
            int y2 = chartY(current, kind, graphTop, graphBottom, entityMax);
            int lineColor = chartSampleColor(kind, current);
            drawSmallLine(context, x1, y1, x2, y2, lineColor);
            if ((kind == ChartKind.FRAME_TIME && current.frameTimeMs() > 75.0D) || (kind == ChartKind.GC && current.gcTimeMs() > 0)) {
                context.drawVerticalLine(x2, graphTop, graphBottom, withAlpha(kind == ChartKind.GC ? 0xFFA7003A : 0xFFE06A21, 175));
            }
        }
        String valueLabel = chartValueLabel(kind);
        int valueWidth = this.textRenderer.getWidth(valueLabel);
        int valueX = Math.max(x + 8, x + width - 8 - valueWidth);
        int titleMaxPx = Math.max(20, valueX - (x + 12) - 4);
        context.drawText(this.textRenderer, trimToPixels(title, titleMaxPx), x + 8, y + 4, softText(accent), false);
        context.drawText(this.textRenderer, trimToPixels(valueLabel, width - 16), valueX, y + 4, chartValueColor(kind), false);
        if (isOver(this.hoverTooltipX, this.hoverTooltipY, x, y, width, height)) {
            this.hoverTooltipLines = chartTooltip(kind);
        }
        if (this.benchmarkRunning) {
            int progress = (int) Math.min(width - 12, ((System.currentTimeMillis() - this.benchmarkStartMillis) / 5000.0D) * (width - 12));
            context.fill(x + 6, y + height - 4, x + 6 + progress, y + height - 2, 0xFF00A8D8);
        }
    }

    private int chartY(PerformanceMonitor.Sample sample, ChartKind kind, int graphTop, int graphBottom, int maxEntity) {
        double normalized;
        normalized = switch (kind) {
            case FPS -> Math.min(1.0D, sample.fps() / 120.0D);
            case FRAME_TIME -> Math.min(1.0D, sample.frameTimeMs() / 120.0D);
            case MEMORY -> sample.maxMemoryMb() <= 0 ? 0.0D : sample.usedMemoryMb() / (double) sample.maxMemoryMb();
            case GC -> Math.min(1.0D, sample.gcTimeMs() / 75.0D);
            case ENTITY -> sample.entityCount() / (double) maxEntity;
            case CHUNK -> sample.chunkStress();
            case SHADER -> sample.shaderPressure();
            case MOD_LOAD -> sample.modLoadPressure();
            case RESOURCEPACK -> sample.resourcePackPressure();
            case SERVER_WAIT -> "server".equals(this.chartWorldType) ? Math.max(sample.chunkStress(), Math.min(1.0D, sample.frameTimeMs() / 140.0D)) : 0.05D;
            case SERVER_CONTEXT -> {
                double entity = sample.entityCount() / (double) maxEntity;
                double world = Math.max(sample.chunkStress(), entity);
                yield "server".equals(this.chartWorldType) || "singleplayer".equals(this.chartWorldType) ? world : 0.0D;
            }
            case GAMEPLAY -> Math.max(sample.entityCount() / (double) maxEntity, Math.min(1.0D, sample.frameTimeMs() / 130.0D) * 0.65D);
            case UI_FRAME -> sample.uiFramePressure();
            case UI_MEMORY -> this.chartScreenName.equals("none") ? 0.0D : (sample.maxMemoryMb() <= 0 ? 0.0D : sample.usedMemoryMb() / (double) sample.maxMemoryMb());
            case WORLD_SIMULATION -> ("server".equals(sample.worldType()) || "singleplayer".equals(sample.worldType())) ? Math.max(sample.chunkStress(), sample.entityCount() / (double) maxEntity) : 0.0D;
            case FAULT_PRESSURE -> {
                double memory = sample.maxMemoryMb() <= 0 ? 0.0D : sample.usedMemoryMb() / (double) sample.maxMemoryMb();
                double frame = Math.min(1.0D, sample.frameTimeMs() / 120.0D);
                double entity = sample.entityCount() / (double) maxEntity;
                yield Math.max(Math.max(memory, frame), Math.max(entity, Math.max(sample.chunkStress(), Math.max(sample.shaderPressure(), sample.modLoadPressure()))));
            }
        };
        return graphBottom - (int) Math.min(graphBottom - graphTop, Math.max(1, normalized * (graphBottom - graphTop)));
    }

    private int maxEntity(List<PerformanceMonitor.Sample> samples) {
        return Math.max(1, samples.stream().mapToInt(PerformanceMonitor.Sample::entityCount).max().orElse(1));
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
            case SERVER_WAIT -> 0xFF6F89A8;
            case SERVER_CONTEXT -> 0xFF4E6C8A;
            case GAMEPLAY -> 0xFFE6862C;
            case UI_FRAME -> 0xFF0085A4;
            case UI_MEMORY -> 0xFFA7003A;
            case WORLD_SIMULATION -> 0xFFE3B735;
            case FAULT_PRESSURE -> 0xFF0085A4;
        };
    }

    private int chartSampleColor(ChartKind kind, PerformanceMonitor.Sample sample) {
        return switch (kind) {
            case FPS -> fpsColor(sample.fps());
            case FRAME_TIME -> pressureColor(Math.min(1.0D, sample.frameTimeMs() / 120.0D));
            case MEMORY -> pressureColor(sample.maxMemoryMb() <= 0 ? 0.0D : sample.usedMemoryMb() / (double) sample.maxMemoryMb());
            case GC -> pressureColor(Math.min(1.0D, sample.gcTimeMs() / 75.0D));
            case ENTITY -> pressureColor(Math.min(1.0D, sample.entityCount() / 220.0D));
            case CHUNK -> pressureColor(sample.chunkStress());
            case SHADER -> shaderColor(sample.shaderPressure());
            case MOD_LOAD -> pressureColor(sample.modLoadPressure());
            case RESOURCEPACK -> pressureColor(sample.resourcePackPressure());
            case UI_FRAME, UI_MEMORY -> uiColor(sample);
            default -> pressureColor(Math.max(sample.chunkStress(), Math.max(sample.shaderPressure(), sample.modLoadPressure())));
        };
    }

    private int chartValueColor(ChartKind kind) {
        PerformanceSnapshot snapshot = PerformanceMonitor.latestSnapshot(MinecraftClient.getInstance());
        return switch (kind) {
            case FPS -> fpsColor(snapshot.fps());
            case FRAME_TIME -> pressureColor(Math.min(1.0D, snapshot.frameTimeMs() / 120.0D));
            case MEMORY, UI_MEMORY -> memoryColor(snapshot.memoryPressure());
            case GC -> pressureColor(snapshot.gcPressure());
            case ENTITY, GAMEPLAY -> pressureColor(Math.min(1.0D, snapshot.entityCount() / 220.0D));
            case CHUNK, WORLD_SIMULATION -> ("singleplayer".equals(snapshot.worldType()) || "server".equals(snapshot.worldType())) ? pressureColor(snapshot.chunkStress()) : 0xFF8D8D8D;
            case SHADER -> shaderColor(snapshot.shaderPressure());
            case MOD_LOAD -> pressureColor(snapshot.modLoadPressure());
            case RESOURCEPACK -> pressureColor(Math.min(1.0D, snapshot.resourcePackCount() / 12.0D));
            case UI_FRAME -> pressureColor(snapshot.uiFramePressure());
            default -> softText(chartAccent(kind));
        };
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

    private int uiColor(PerformanceMonitor.Sample sample) {
        return this.chartScreenName.equals("none") ? 0xFF8D8D8D : pressureColor(sample.uiFramePressure());
    }

    private String chartValueLabel(ChartKind kind) {
        PerformanceSnapshot snapshot = this.chartSnapshot == null ? PerformanceMonitor.latestSnapshot(MinecraftClient.getInstance()) : this.chartSnapshot;
        return switch (kind) {
            case FPS -> snapshot.fps() + " fps";
            case FRAME_TIME -> snapshot.frameTimeMs() + " ms";
            case MEMORY -> (int) Math.round(snapshot.memoryPressure() * 100.0D) + "%";
            case GC -> (int) Math.round(snapshot.gcPressure() * 100.0D) + "%";
            case ENTITY -> snapshot.entityCount() + " ent";
            case CHUNK -> (int) Math.round(snapshot.chunkStress() * 100.0D) + "%";
            case SHADER -> (int) Math.round(snapshot.shaderPressure() * 100.0D) + "%";
            case MOD_LOAD -> (int) Math.round(snapshot.modLoadPressure() * 100.0D) + "%";
            case RESOURCEPACK -> snapshot.resourcePackCount() + " packs";
            case SERVER_WAIT -> "server".equals(this.chartWorldType) ? "remote" : this.chartWorldType;
            case SERVER_CONTEXT -> serverTitle();
            case GAMEPLAY -> snapshot.entityCount() + " ent";
            case UI_FRAME -> this.chartScreenName;
            case UI_MEMORY -> (int) Math.round(snapshot.memoryPressure() * 100.0D) + "% mem";
            case WORLD_SIMULATION -> snapshot.renderDistance() + "/" + snapshot.simulationDistance();
            case FAULT_PRESSURE -> snapshot.primaryBottleneck().label();
        };
    }

    private List<Text> chartTooltip(ChartKind kind) {
        PerformanceSnapshot snapshot = this.chartSnapshot == null ? PerformanceMonitor.latestSnapshot(MinecraftClient.getInstance()) : this.chartSnapshot;
        return switch (kind) {
            case FPS -> diagnosticTooltip("FPS", "Current " + snapshot.fps() + " | Avg " + snapshot.averageFps() + " | 1% low " + snapshot.onePercentLowFps(), fpsAdvice(snapshot));
            case FRAME_TIME -> diagnosticTooltip("Frame Time", "Now " + snapshot.frameTimeMs() + " ms | Spike " + snapshot.maxFrameTimeMs() + " ms", snapshot.maxFrameTimeMs() > 75.0D ? "Spikes are high. Apply frame-pacing/render recommendations first." : "Frame pacing is inside the normal range.");
            case MEMORY -> diagnosticTooltip("Memory", snapshot.usedMemoryMb() + "/" + snapshot.maxMemoryMb() + " MB | " + percentText(snapshot.memoryPressure()), snapshot.memoryPressure() > 0.85D ? "Memory pressure is high. Prefer texture/mipmap/resource changes over adding RAM blindly." : "Memory has enough headroom for current settings.");
            case GC -> diagnosticTooltip("Garbage Collection", percentText(snapshot.gcPressure()) + " GC pressure", snapshot.gcPressure() > 0.50D ? "GC churn is visible. Lower texture/update pressure before raising allocation." : "GC is not currently a major limiter.");
            case ENTITY -> diagnosticTooltip("Entities", snapshot.entityCount() + " sampled", snapshot.entityCount() > 180 ? "Entity density is high. Entity distance and culling are relevant." : "Entity pressure is low.");
            case CHUNK -> diagnosticTooltip("Chunks", worldActive(snapshot) ? percentText(snapshot.chunkStress()) + " chunk stress | RD " + snapshot.renderDistance() : "Inactive outside world/server", worldActive(snapshot) ? "Chunk settings affect render distance, meshing, and streaming." : "No world is loaded, so chunk pressure is not measured.");
            case SHADER -> diagnosticTooltip("Shader / Render", percentText(snapshot.shaderPressure()) + " shader pressure", snapshot.shaderPressure() > 0.45D ? "Shader/render cost is active. Shadow/render provider settings matter." : "Shader pressure is low or inactive.");
            case MOD_LOAD -> diagnosticTooltip("Modpack", snapshot.loadedModCount() + " mods | " + snapshot.optimizationModCount() + " optimization configs", snapshot.modLoadPressure() > 0.80D ? "Large modpack pressure is visible. Config/provider changes are safer than disabling mods automatically." : "Mod count is not currently the strongest limiter.");
            case RESOURCEPACK -> diagnosticTooltip("Resourcepacks", snapshot.resourcePackCount() + " enabled", snapshot.resourcePackPressure() > 0.70D ? "Resourcepack stack may affect texture memory/reload cost." : "Resourcepack pressure is low.");
            case SERVER_WAIT, SERVER_CONTEXT -> diagnosticTooltip("Server Context", this.chartWorldType, "Remote server tick time cannot be directly changed by local graphics settings.");
            case GAMEPLAY -> diagnosticTooltip("Gameplay", snapshot.entityCount() + " entities | " + snapshot.particlesMode() + " particles", "Gameplay pressure maps to entity distance, particles, and culling.");
            case UI_FRAME -> diagnosticTooltip("UI Render", percentText(snapshot.uiFramePressure()) + " UI frame pressure", snapshot.uiFramePressure() > 0.55D ? "This screen is adding measurable frame cost. Keep graph sampling compact." : "UI cost is not a major limiter.");
            case UI_MEMORY -> diagnosticTooltip("UI Memory", percentText(snapshot.memoryPressure()) + " heap pressure", "Shows memory pressure while a screen is open.");
            case WORLD_SIMULATION -> diagnosticTooltip("World Simulation", worldActive(snapshot) ? "RD " + snapshot.renderDistance() + " | SD " + snapshot.simulationDistance() : "Inactive outside world/server", worldActive(snapshot) ? "Simulation distance and entity density affect CPU/tick pressure." : "No active world simulation to measure.");
            case FAULT_PRESSURE -> diagnosticTooltip("Strongest Fault", snapshot.primaryBottleneck().label(), snapshot.likelyCause());
        };
    }

    private void ensureChartCache() {
        long now = System.currentTimeMillis();
        if (!Main.preciseStat() && now - this.chartCacheAtMillis < 750L && !this.chartSamples.isEmpty()) {
            return;
        }
        this.chartSamples = new ArrayList<>(PerformanceMonitor.samples());
        this.chartEntityMax = maxEntity(this.chartSamples);
        this.chartSnapshot = PerformanceMonitor.latestSnapshot(MinecraftClient.getInstance());
        this.chartWorldType = this.chartSnapshot == null ? currentWorldType() : this.chartSnapshot.worldType();
        this.chartScreenName = currentScreenName();
        this.chartCacheAtMillis = now;
    }

    private String currentWorldType() {
        if (this.runtimeContext == null || System.currentTimeMillis() - this.runtimeContext.capturedAtMillis() > 5000L) {
            this.runtimeContext = PerformanceRuntimeContextService.capture(MinecraftClient.getInstance());
        }
        return this.runtimeContext == null ? "unknown" : this.runtimeContext.worldType();
    }

    private void renderHardwareSummary(DrawContext context, int x, int y, int width) {
        context.drawText(this.textRenderer, "Hardware and loaded systems", x, y, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        if (hardwareProfile != null) {
            context.drawText(this.textRenderer, "CPU threads: " + hardwareProfile.cpuThreads() + " | RAM: " + hardwareProfile.systemMemoryMb() + " MB | Display: " + hardwareProfile.monitorWidth() + "x" + hardwareProfile.monitorHeight() + "@" + hardwareProfile.refreshRate() + "hz", x, y + 14, 0xFFE2E2E2, false);
            context.drawText(this.textRenderer, "GPU: " + trim(hardwareProfile.gpuRenderer(), Math.max(24, width / 6)), x, y + 28, 0xFFE2E2E2, false);
            context.drawText(this.textRenderer, "Optimization mods: " + (hardwareProfile.optimizationMods().isEmpty() ? "none detected" : String.join(", ", hardwareProfile.optimizationMods())), x, y + 42, hardwareProfile.optimizationMods().isEmpty() ? 0xFF8D8D8D : 0xFF2DA700, false);
        }
    }

    private void renderFaultDomainPanel(DrawContext context, int x, int y, int width, PerformanceSnapshot snapshot) {
        context.drawText(this.textRenderer, "Developer fault domains", x, y, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        int cellWidth = Math.max(90, (width - 18) / 4);
        drawFaultCell(context, x, y + 16, cellWidth, "Client", snapshot.primaryBottleneck() == PerformanceBottleneck.GPU || snapshot.primaryBottleneck() == PerformanceBottleneck.SHADER_RENDER ? "render pressure" : "normal", snapshot.primaryBottleneck() == PerformanceBottleneck.GPU ? 0xFF7400A4 : 0xFF2DA700);
        drawFaultCell(context, x + cellWidth + 6, y + 16, cellWidth, "World", snapshot.primaryBottleneck() == PerformanceBottleneck.ENTITY_TICK || snapshot.primaryBottleneck() == PerformanceBottleneck.CHUNK_STORAGE ? "world load" : snapshot.worldType(), snapshot.primaryBottleneck() == PerformanceBottleneck.CHUNK_STORAGE ? 0xFFE3B735 : 0xFF0085A4);
        drawFaultCell(context, x + (cellWidth + 6) * 2, y + 16, cellWidth, "Modpack", snapshot.modLoadPressure() > 0.80D ? "review mods" : snapshot.loadedModCount() + " mods", snapshot.modLoadPressure() > 0.80D ? 0xFFC32222 : 0xFF8D8D8D);
        drawFaultCell(context, x + (cellWidth + 6) * 3, y + 16, cellWidth, "Memory", snapshot.gcPressure() > 0.50D ? "GC active" : snapshot.usedMemoryMb() + " MB", memoryColor(snapshot.memoryPressure()));
    }

    private void drawFaultCell(DrawContext context, int x, int y, int width, String label, String value, int color) {
        context.fill(x, y, x + width, y + 38, withAlpha(0xFF000000, 42));
        context.fill(x, y, x + width, y + 2, withAlpha(color, 160));
        context.drawBorder(x, y, width, 38, shadedBorder(color));
        context.drawText(this.textRenderer, label, x + 7, y + 6, 0xFFB8B8B8, false);
        context.drawText(this.textRenderer, trim(value, Math.max(8, (width - 14) / 6)), x + 7, y + 21, softText(color), false);
    }

    private void renderAdvancedSignals(DrawContext context, int x, int y, int width, PerformanceSnapshot snapshot) {
        context.drawText(this.textRenderer, "Pressure breakdown", x, y, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        boolean worldActive = "singleplayer".equals(snapshot.worldType()) || "server".equals(snapshot.worldType());
        drawSignalBar(context, x, y + 16, width, "FPS loss", snapshot.fps() <= 0 ? 0.0D : Math.max(0.0D, (60.0D - snapshot.averageFps()) / 60.0D), 0xFFE06A21);
        drawSignalBar(context, x, y + 30, width, "Frame spike", Math.min(1.0D, snapshot.maxFrameTimeMs() / 140.0D), 0xFFE06A21);
        drawSignalBar(context, x, y + 44, width, "Memory", snapshot.memoryPressure(), 0xFFA7003A);
        drawSignalBar(context, x, y + 58, width, "GC", snapshot.gcPressure(), 0xFFFF597D);
        drawSignalBar(context, x, y + 72, width, "Chunk", worldActive ? snapshot.chunkStress() : -1.0D, 0xFFE3B735);
        drawSignalBar(context, x, y + 86, width, "Entity/tick", worldActive ? Math.min(1.0D, snapshot.entityCount() / 220.0D) : -1.0D, 0xFFE6862C);
        drawSignalBar(context, x, y + 100, width, "Shader", snapshot.shaderPressure(), 0xFF7400A4);
        drawSignalBar(context, x, y + 114, width, "Resource", Math.min(1.0D, snapshot.resourcePackCount() / 12.0D), 0xFFB0199E);
        drawSignalBar(context, x, y + 128, width, "Mod load", snapshot.modLoadPressure(), 0xFFC32222);
        drawSignalBar(context, x, y + 142, width, "UI render", snapshot.uiFramePressure(), 0xFF0085A4);
    }

    private void drawSignalBar(DrawContext context, int x, int y, int width, String label, double value, int color) {
        int labelWidth = 58;
        int barWidth = Math.max(1, width - labelWidth - 42);
        boolean inactive = value < 0.0D;
        double clamped = inactive ? 0.0D : Math.max(0.0D, Math.min(1.0D, value));
        int visibleColor = inactive ? 0xFF777777 : color;
        context.drawText(this.textRenderer, label, x, y, inactive ? 0xFF777777 : 0xFFB8B8B8, false);
        context.fill(x + labelWidth, y + 2, x + labelWidth + barWidth, y + 8, withAlpha(0xFF000000, 95));
        context.fill(x + labelWidth, y + 2, x + labelWidth + (int) (barWidth * clamped), y + 8, withAlpha(visibleColor, inactive ? 75 : 185));
        String valueText = inactive ? "inactive" : (int) Math.round(clamped * 100.0D) + "%";
        context.drawText(this.textRenderer, valueText, x + labelWidth + barWidth + 6, y, inactive ? 0xFF8D8D8D : softText(color), false);
        if (isOver(this.hoverTooltipX, this.hoverTooltipY, x, y, width, 11)) {
            this.hoverTooltipLines = signalTooltip(label, valueText, inactive);
        }
    }

    private void renderMainScrollbar(DrawContext context, int mouseX, int mouseY) {
        if (this.mainContentHeight <= this.mainViewportHeight) {
            this.mainScroll = 0;
            return;
        }
        int trackX = this.mainViewportX + this.mainViewportWidth + 5;
        int trackY = this.mainViewportY;
        int trackHeight = this.mainViewportHeight;
        int thumbHeight = Math.max(24, (int) (trackHeight * (this.mainViewportHeight / (double) this.mainContentHeight)));
        int maxScroll = Math.max(1, this.mainContentHeight - this.mainViewportHeight);
        int thumbY = trackY + (int) ((trackHeight - thumbHeight) * (this.mainScroll / (double) maxScroll));
        context.fill(trackX, trackY, trackX + 4, trackY + trackHeight, withAlpha(0xFF000000, 90));
        context.fill(trackX, thumbY, trackX + 4, thumbY + thumbHeight, withAlpha(0xFFE2E2E2, 125));
    }

    private void drawMetric(DrawContext context, int x, int y, String label, String value, int color) {
        context.drawText(this.textRenderer, label, x, y, 0xFFB8B8B8, false);
        context.drawText(this.textRenderer, value, x + 70, y, color, false);
    }

    private void drawInfoCard(DrawContext context, int x, int y, int width, String label, String value, int color) {
        context.fill(x, y, x + width, y + 44, withAlpha(0xFF000000, 42));
        context.fill(x, y, x + width, y + 2, withAlpha(color, 180));
        context.drawBorder(x, y, width, 44, shadedBorder(color));
        context.drawText(this.textRenderer, label, x + 8, y + 7, 0xFFB8B8B8, false);
        context.drawText(this.textRenderer, trim(value, 16), x + 8, y + 23, softText(color), false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = 8;
        int y = PANEL_CONTENT_START + 18;
        int rowRight = 166;
        for (PerformanceProfileMode mode : PerformanceProfileMode.values()) {
            if (mouseX >= x && mouseX <= rowRight && mouseY >= y && mouseY <= y + 14) {
                this.activeMode = mode;
                this.recommendations = PerformanceRecommendationEngine.recommend(MinecraftClient.getInstance(), this.activeMode, PerformanceMonitor.freshSnapshot(MinecraftClient.getInstance()));
                this.status = "Profile selected: " + mode.label() + ". Review recommendations before applying.";
                saveSession();
                return true;
            }
            y += 15;
        }
        if (isTopBarButtonClicked(mouseX, mouseY, 10, getTopBarButtonWidth(TOP_BAR_BACK_LABEL))) {
            closeAndRemember();
            return true;
        }
        for (int i = 0; i < getTopBarActionLabels().size(); i++) {
            String label = getTopBarActionLabels().get(i);
            if (isTopBarButtonClicked(mouseX, mouseY, getTopBarButtonX(i), getTopBarButtonWidth(label))) {
                DiagnosticsPane selectedPane = DiagnosticsPane.fromLabel(label);
                if (selectedPane != this.activePane) {
                    this.activePane = selectedPane;
                    this.mainScroll = 0;
                }
                return true;
            }
        }
        if (button == 0 && isOver((int) mouseX, (int) mouseY, this.mainViewportX, this.mainViewportY, this.mainViewportWidth, this.mainViewportHeight)) {
            for (RecommendationHitbox hitbox : this.recommendationHitboxes) {
                if (isOver((int) mouseX, (int) mouseY, hitbox.x(), hitbox.y(), hitbox.width(), hitbox.height())) {
                    applySelectedRecommendation(hitbox.recommendation());
                    return true;
                }
            }
        }
        if (this.mainContentHeight > this.mainViewportHeight) {
            int trackX = this.mainViewportX + this.mainViewportWidth + 5;
            int trackHeight = this.mainViewportHeight;
            int thumbHeight = Math.max(24, (int) (trackHeight * (this.mainViewportHeight / (double) this.mainContentHeight)));
            int maxScroll = Math.max(1, this.mainContentHeight - this.mainViewportHeight);
            int thumbY = this.mainViewportY + (int) ((trackHeight - thumbHeight) * (this.mainScroll / (double) maxScroll));
            if (isOver((int) mouseX, (int) mouseY, trackX - 2, thumbY, 8, thumbHeight)) {
                this.draggingMainScrollbar = true;
                this.mainScrollbarDragOffset = (int) mouseY - thumbY;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (isOver((int) mouseX, (int) mouseY, this.mainViewportX, this.mainViewportY, this.mainViewportWidth + 12, this.mainViewportHeight)) {
            scrollMain((int) (-amount * 24));
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

    private void runBenchmark() {
        this.hardwareProfile = PerformanceHardwareScanner.scan(MinecraftClient.getInstance());
        this.runtimeContext = PerformanceRuntimeContextService.capture(MinecraftClient.getInstance());
        this.benchmarkRunning = false;
        boolean started = PerformanceOptimizationTestService.start(MinecraftClient.getInstance(), this.activeMode, this);
        this.status = started ? PerformanceOptimizationTestService.status() : "Benchmark is already running.";
    }

    private void finishBenchmark() {
        this.benchmarkRunning = false;
        this.latestBenchmark = PerformanceBenchmarkRunner.finishBenchmark(MinecraftClient.getInstance(), this.activeMode, this.benchmarkStartMillis);
        this.recommendations = latestBenchmark.recommendations();
        PerformanceProfileManager.saveProfileSuggestion(MinecraftClient.getInstance(), this.activeMode, latestBenchmark.snapshot());
        this.status = latestBenchmark.summary();
    }

    private void applySafe() {
        PerformanceSnapshot before = PerformanceMonitor.freshSnapshot(MinecraftClient.getInstance());
        refreshProviderSettings();
        List<PerformanceRecommendation> actionableRecommendations = visibleRecommendations();
        PerformanceConfigApplier.ApplyResult result = PerformanceConfigApplier.applySafe(MinecraftClient.getInstance(), actionableRecommendations);
        List<PerformanceProviderApplyResult> providerResults = PerformanceLiveApplyService.applyRecommendations(MinecraftClient.getInstance(), this.providerSettings, actionableRecommendations);
        PerformanceMonitor.freshSnapshot(MinecraftClient.getInstance());
        this.lastProviderResults = providerResults;
        this.lastApplyEntries = result.entryResults();
        rememberAppliedTargets(actionableRecommendations, result, providerResults);
        long changed = providerResults.stream().filter(PerformanceProviderApplyResult::changed).count();
        this.status = result.message() + (providerResults.isEmpty() ? "" : " Provider configs: " + changed + "/" + providerResults.size() + " changed; reload requested where needed.");
        PerformanceLearningService.recordApply(MinecraftClient.getInstance(), before, actionableRecommendations, result, providerResults);
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
        boolean vanillaChanged = result.changed();
        this.status = "Applied selected target: " + recommendation.title()
                + (vanillaChanged ? " | vanilla changed" : "")
                + (providerResults.isEmpty() ? "" : " | providers " + changed + "/" + providerResults.size() + " changed");
        PerformanceLearningService.recordApply(MinecraftClient.getInstance(), before, selectedList, result, providerResults);
        refreshProviderSettings();
        this.recommendations = refreshedRecommendationsAfterApply();
        saveSession();
    }

    private List<PerformanceRecommendation> refreshedRecommendationsAfterApply() {
        PerformanceSnapshot fresh = PerformanceMonitor.freshSnapshot(MinecraftClient.getInstance());
        if (this.latestBenchmark != null && this.latestBenchmark.requestedMode() == this.activeMode) {
            return PerformanceRecommendationEngine.recommendFromBenchmark(
                    MinecraftClient.getInstance(),
                    this.activeMode,
                    fresh,
                    this.latestBenchmark.phaseResults()
            );
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
        this.status = PerformanceConfigApplier.revertLastBackup(MinecraftClient.getInstance()) ? "Reverted last optimization backup." : "No optimization backup found.";
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
            boolean vanillaApplied = result.entryResults().stream()
                    .anyMatch(entry -> entry.recommendationId().equals(recommendation.id()) && "applied".equals(entry.status()));
            boolean providerApplied = providerResults.stream()
                    .anyMatch(providerResult -> matchesSettingKey(providerResult.settingId(), recommendation.settingKey()) && providerResult.changed());
            if (vanillaApplied || providerApplied || sameSettingValue(currentValueForRecommendation(recommendation), recommendation.afterValue())) {
                this.appliedTargetsBySetting.put(normalizeSettingKey(recommendation.settingKey()), recommendation.afterValue());
            }
        }
    }

    private List<PerformanceSettingDescriptor> filteredProviderSettings() {
        String query = this.searchField == null ? "" : this.searchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        if (query.isEmpty()) {
            return this.providerSettings;
        }
        return this.providerSettings.stream()
                .filter(setting -> setting.providerId().toLowerCase(java.util.Locale.ROOT).contains(query)
                        || setting.label().toLowerCase(java.util.Locale.ROOT).contains(query)
                        || setting.category().toLowerCase(java.util.Locale.ROOT).contains(query)
                        || setting.description().toLowerCase(java.util.Locale.ROOT).contains(query))
                .toList();
    }

    private List<PerformanceRecommendation> visibleRecommendations() {
        return this.recommendations.stream()
                .filter(recommendation -> recommendationNeedsChange(recommendation) || isAppliedTarget(recommendation))
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
        String before = recommendation.beforeValue() == null ? "" : recommendation.beforeValue().trim();
        String after = recommendation.afterValue() == null ? "" : recommendation.afterValue().trim();
        if (sameSettingValue(before, after)) {
            return false;
        }
        String current = currentValueForRecommendation(recommendation);
        return current.isBlank() || !sameSettingValue(current, after);
    }

    private String currentValueForRecommendation(PerformanceRecommendation recommendation) {
        String key = recommendation.settingKey() == null ? "" : recommendation.settingKey();
        for (PerformanceSettingDescriptor setting : this.providerSettings) {
            if (matchesSettingKey(setting.settingId(), key)) {
                return setting.currentValue() == null ? "" : setting.currentValue();
            }
        }
        PerformanceSnapshot snapshot = PerformanceMonitor.freshSnapshot(MinecraftClient.getInstance());
        return switch (key.toLowerCase(java.util.Locale.ROOT).replace('-', '_')) {
            case "render_distance" -> String.valueOf(snapshot.renderDistance());
            case "simulation_distance" -> String.valueOf(snapshot.simulationDistance());
            case "max_fps" -> String.valueOf(snapshot.maxFps());
            case "clouds" -> snapshot.cloudsMode();
            case "entity_distance" -> String.format(java.util.Locale.ROOT, "%.2f", snapshot.entityDistanceScale());
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
        return current.isBlank() ? recommendation.beforeValue() : current;
    }

    private String normalizeSettingKey(String key) {
        return key == null ? "" : key.toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace('.', '_');
    }

    private boolean sameSettingValue(String left, String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
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
        String current = setting.currentValue() == null ? "" : setting.currentValue().trim();
        String recommended = setting.recommendedValue() == null ? "" : setting.recommendedValue().trim();
        return !current.equalsIgnoreCase(recommended);
    }

    private void applyProviderSettings() {
        this.lastProviderResults = PerformanceLiveApplyService.applyAllSupported(MinecraftClient.getInstance(), this.providerSettings, this.searchField == null ? "" : this.searchField.getText());
        long changed = this.lastProviderResults.stream().filter(PerformanceProviderApplyResult::changed).count();
        this.status = "Provider apply complete: " + changed + "/" + this.lastProviderResults.size() + " setting(s) changed live or reload-requested.";
        refreshProviderSettings();
    }

    private String lastApplySummary() {
        if (this.lastProviderResults.isEmpty()) {
            return "none";
        }
        long changed = this.lastProviderResults.stream().filter(PerformanceProviderApplyResult::changed).count();
        return changed + "/" + this.lastProviderResults.size() + " changed";
    }

    private boolean matchesSettingKey(String settingId, String recommendationKey) {
        String setting = settingId == null ? "" : settingId.toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        String key = recommendationKey == null ? "" : recommendationKey.toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        if (setting.isBlank() || key.isBlank()) {
            return false;
        }
        return setting.equals(key)
                || setting.contains(key)
                || ("shadow_distance".equals(key) && setting.contains("shadow") && setting.contains("distance"));
    }

    private List<String> getTopBarActionLabels() {
        return List.of(PANE_OVERVIEW_LABEL, PANE_RENDERING_LABEL, PANE_PROCESSING_LABEL, PANE_MEMORY_LABEL, PANE_SERVER_LABEL, PANE_GAMEPLAY_LABEL, PANE_UI_LABEL, PANE_WORLD_LABEL, PANE_MODS_LABEL, PANE_ALL_LABEL);
    }

    private TopBarLayout getTopBarLayout() {
        return new TopBarLayout(this.textRenderer, this.width);
    }

    private int getTopBarButtonX(int index) {
        List<String> labels = getTopBarActionLabels();
        int x = this.width - TopBarLayout.RIGHT_MARGIN;
        for (int i = labels.size() - 1; i >= index; i--) {
            x -= getTopBarButtonWidth(labels.get(i));
            if (i > index) {
                x -= TopBarLayout.BUTTON_GAP;
            }
        }
        return x;
    }

    private int getTopBarButtonWidth(String label) {
        if (TOP_BAR_BACK_LABEL.equals(label)) {
            return getTopBarLayout().buttonWidth(label);
        }
        return Math.max(30, this.textRenderer.getWidth(label) + 10);
    }

    private int topBarSearchWidth() {
        TopBarLayout layout = getTopBarLayout();
        int searchX = layout.searchFieldX(TOP_BAR_BACK_LABEL);
        int actionsLeft = getTopBarButtonX(0);
        return Math.max(80, actionsLeft - searchX - 8);
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
        this.status = result.summary() + result.testNotes().stream().findFirst().map(note -> " " + note).orElse("");
        this.runtimeContext = PerformanceRuntimeContextService.capture(MinecraftClient.getInstance());
        refreshProviderSettings();
    }

    private void renderTopBarButton(DrawContext context, int x, String label) {
        renderTopBarButton(context, x, label, false);
    }

    private void renderTopBarButton(DrawContext context, int x, String label, boolean selected) {
        int buttonWidth = getTopBarButtonWidth(label);
        context.fill(x, TopBarLayout.BUTTON_Y, x + buttonWidth, TopBarLayout.BUTTON_Y + TopBarLayout.BUTTON_HEIGHT, withAlpha(uiColorContentBase, 176));
        context.drawBorder(x, TopBarLayout.BUTTON_Y, buttonWidth, TopBarLayout.BUTTON_HEIGHT, new Color(uiColorBackgroundBorder, true).getRGB());
        int textX = x + Math.max(4, (buttonWidth - this.textRenderer.getWidth(label)) / 2);
        context.drawText(this.textRenderer, label, textX, TopBarLayout.BUTTON_Y + 7, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
    }

    private boolean isTopBarButtonClicked(double mouseX, double mouseY, int x, int width) {
        return mouseX >= x && mouseX <= x + width && mouseY >= TopBarLayout.BUTTON_Y && mouseY <= TopBarLayout.BUTTON_Y + TopBarLayout.BUTTON_HEIGHT;
    }

    private void scrollMain(int amount) {
        int maxScroll = Math.max(0, this.mainContentHeight - this.mainViewportHeight);
        this.mainScroll = Math.max(0, Math.min(maxScroll, this.mainScroll + amount));
    }

    private void dragMainScrollbar(int mouseY) {
        int trackHeight = this.mainViewportHeight;
        int thumbHeight = Math.max(24, (int) (trackHeight * (this.mainViewportHeight / (double) Math.max(1, this.mainContentHeight))));
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
        if (pressure < 0.70D) return 0xFF2DA700;
        if (pressure < 0.85D) return 0xFFE3B735;
        if (pressure < 0.93D) return 0xFFE06A21;
        return 0xFFA7003A;
    }

    private int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    private int shadedBorder(int color) {
        int r = ((color >> 16) & 255);
        int g = ((color >> 8) & 255);
        int b = (color & 255);
        r = (r + 55) / 3;
        g = (g + 55) / 3;
        b = (b + 55) / 3;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private int muted(int color) {
        return withAlpha(color, 90);
    }

    private int softText(int color) {
        int r = Math.min(255, (((color >> 16) & 255) + 255) / 2);
        int g = Math.min(255, (((color >> 8) & 255) + 255) / 2);
        int b = Math.min(255, ((color & 255) + 255) / 2);
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
            int e2 = 2 * error;
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

    private void drawDashedHorizontal(DrawContext context, int x1, int x2, int y, int color) {
        for (int x = x1; x <= x2; x += 6) {
            context.drawHorizontalLine(x, Math.min(x + 3, x2), y, color);
        }
    }

    private String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }

    private String trimToPixels(String value, int maxPixels) {
        if (value == null || maxPixels <= 0) {
            return "";
        }
        if (this.textRenderer.getWidth(value) <= maxPixels) {
            return value;
        }
        String ellipsis = "...";
        int end = value.length();
        while (end > 0 && this.textRenderer.getWidth(value.substring(0, end) + ellipsis) > maxPixels) {
            end--;
        }
        return end <= 0 ? ellipsis : value.substring(0, end) + ellipsis;
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
        int valueColor = recommendation.severity() == PerformanceRecommendation.Severity.MANUAL_REVIEW
                ? new Color(uiColorToolTipWarning, true).getRGB()
                : severityColor;
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal(recommendation.severity().label()).setStyle(Style.EMPTY.withColor(severityColor).withBold(true))
                .append(Text.literal("  " + recommendation.title()).setStyle(Style.EMPTY.withColor(primaryColor))));
        lines.add(Text.literal("Setting: ").setStyle(Style.EMPTY.withColor(labelColor))
                .append(Text.literal(recommendation.settingKey()).setStyle(Style.EMPTY.withColor(primaryColor))));
        lines.add(Text.literal("Change: ").setStyle(Style.EMPTY.withColor(labelColor))
                .append(Text.literal(recommendation.beforeValue()).setStyle(Style.EMPTY.withColor(secondaryColor)))
                .append(Text.literal(" -> ").setStyle(Style.EMPTY.withColor(labelColor)))
                .append(Text.literal(recommendation.afterValue()).setStyle(Style.EMPTY.withColor(valueColor).withBold(true))));
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
                Text.literal("Context: ").setStyle(Style.EMPTY.withColor(new Color(uiColorToolTipLabel, true).getRGB()))
                        .append(Text.literal(this.chartSnapshot == null ? "unknown" : this.chartSnapshot.worldType()).setStyle(Style.EMPTY.withColor(new Color(uiColorToolTipSecondary, true).getRGB()))),
                Text.literal("Source: ").setStyle(Style.EMPTY.withColor(new Color(uiColorToolTipLabel, true).getRGB()))
                        .append(Text.literal(signalSource(label)).setStyle(Style.EMPTY.withColor(new Color(uiColorToolTipPrimary, true).getRGB())))
        );
    }

    private List<Text> diagnosticTooltip(String title, String value, String action) {
        int valueColor = diagnosticValueColor(title);
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal(title).setStyle(Style.EMPTY.withColor(new Color(uiColorToolTipPrimary, true).getRGB()).withBold(true)));
        lines.add(valueLine("Value: ", value, valueColor));
        lines.add(Text.literal("Action:").setStyle(Style.EMPTY.withColor(new Color(uiColorToolTipIdea, true).getRGB()).withBold(true)));
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
        String lower = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("fps")) return fpsColor(snapshot.fps());
        if (lower.contains("frame")) return pressureColor(Math.min(1.0D, snapshot.frameTimeMs() / 120.0D));
        if (lower.contains("memory")) return memoryColor(snapshot.memoryPressure());
        if (lower.contains("garbage")) return pressureColor(snapshot.gcPressure());
        if (lower.contains("entity") || lower.contains("gameplay")) return pressureColor(Math.min(1.0D, snapshot.entityCount() / 220.0D));
        if (lower.contains("chunk") || lower.contains("world")) return worldActive(snapshot) ? pressureColor(snapshot.chunkStress()) : 0xFF8D8D8D;
        if (lower.contains("shader")) return shaderColor(snapshot.shaderPressure());
        if (lower.contains("modpack")) return pressureColor(snapshot.modLoadPressure());
        if (lower.contains("resource")) return pressureColor(snapshot.resourcePackPressure());
        if (lower.contains("ui")) return pressureColor(snapshot.uiFramePressure());
        return new Color(uiColorToolTipPrimary, true).getRGB();
    }

    private Text valueLine(String label, String value, int valueColor) {
        int labelColor = new Color(uiColorToolTipLabel, true).getRGB();
        int secondaryColor = new Color(uiColorToolTipSecondary, true).getRGB();
        Text line = Text.literal(label).setStyle(Style.EMPTY.withColor(labelColor));
        for (String token : value.split("(?<=\\s)|(?=\\s)|(?=\\|)|(?<=\\|)")) {
            if (token.isBlank() || "|".equals(token)) {
                line = line.copy().append(Text.literal(token).setStyle(Style.EMPTY.withColor(labelColor)));
            } else if (isValueToken(token)) {
                line = line.copy().append(Text.literal(token).setStyle(Style.EMPTY.withColor(valueColor).withBold(true)));
            } else {
                line = line.copy().append(Text.literal(token).setStyle(Style.EMPTY.withColor(secondaryColor)));
            }
        }
        return line;
    }

    private String systemVoice(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("Koil's", "the system's")
                .replace("Koil ", "The system ")
                .replace(" Koil", " the system")
                .replace("koil ", "the system ");
    }

    private boolean isValueToken(String token) {
        String cleaned = token.replace(",", "").replace("%", "").replace("ms", "").replace("MB", "").replace("/", "").trim();
        if (cleaned.isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(cleaned);
            return true;
        } catch (NumberFormatException ignored) {
            return "inactive".equalsIgnoreCase(token)
                    || "remote".equalsIgnoreCase(token)
                    || "server".equalsIgnoreCase(token)
                    || "singleplayer".equalsIgnoreCase(token)
                    || token.toLowerCase(java.util.Locale.ROOT).contains("bound");
        }
    }

    private String recommendationSource(PerformanceRecommendation recommendation) {
        String key = recommendation.settingKey() == null ? "" : recommendation.settingKey();
        if (key.contains(".") || key.contains("::")) {
            return "editable optimization/shader config provider";
        }
        if ("loaded_mods".equals(key) || "resourcepacks".equals(key)) {
            return "Fabric Loader and Minecraft resource pack manager";
        }
        if ("memory_allocation".equals(key)) {
            return "JVM Runtime memory counters";
        }
        return "Minecraft GameOptions plus live benchmark snapshot";
    }

    private String diagnosticSource(String title) {
        String lower = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("fps")) return "MinecraftClient current FPS and rolling samples";
        if (lower.contains("frame")) return "client tick/frame sample timing";
        if (lower.contains("memory")) return "JVM Runtime used/max memory";
        if (lower.contains("garbage")) return "GarbageCollectorMXBean deltas";
        if (lower.contains("entity")) return "current client world entity iteration";
        if (lower.contains("chunk") || lower.contains("world")) return "world context, frame spikes, and render distance estimate";
        if (lower.contains("shader")) return "shader mod/config detection plus FPS pressure estimate";
        if (lower.contains("modpack")) return "Fabric Loader loaded mod count";
        if (lower.contains("resource")) return "Minecraft enabled resource pack count";
        if (lower.contains("ui")) return "screen-open frame pressure estimate";
        return "latest performance snapshot";
    }

    private String signalSource(String label) {
        String lower = label == null ? "" : label.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("fps")) return "rolling FPS samples";
        if (lower.contains("frame")) return "rolling frame-time samples";
        if (lower.contains("memory")) return "JVM Runtime memory";
        if (lower.contains("gc")) return "GarbageCollectorMXBean";
        if (lower.contains("chunk")) return "world + render distance + frame spike estimate";
        if (lower.contains("entity")) return "client world entity count";
        if (lower.contains("shader")) return "shader pipeline estimate";
        if (lower.contains("resource")) return "enabled resource packs";
        if (lower.contains("mod")) return "Fabric Loader mod count";
        if (lower.contains("ui")) return "screen-open frame cost estimate";
        return "latest performance snapshot";
    }

    private int signalValueColor(String label, String valueText) {
        if (valueText == null || valueText.equals("inactive")) {
            return 0xFF8D8D8D;
        }
        String number = valueText.replace("%", "").trim();
        double value;
        try {
            value = Double.parseDouble(number) / 100.0D;
        } catch (NumberFormatException ignored) {
            value = 0.0D;
        }
        String lower = label == null ? "" : label.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("shader")) return shaderColor(value);
        if (lower.contains("memory")) return memoryColor(value);
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

    private String fpsAdvice(PerformanceSnapshot snapshot) {
        if (snapshot.averageFps() >= 75.0D && snapshot.onePercentLowFps() >= 45.0D) {
            return "Benchmark has headroom. Quality settings can stay higher.";
        }
        if (snapshot.onePercentLowFps() < 30.0D) {
            return "1% low FPS is weak. Apply pacing/render recommendations.";
        }
        return "FPS is usable but can be tuned from benchmark results.";
    }

    private boolean worldActive(PerformanceSnapshot snapshot) {
        return "singleplayer".equals(snapshot.worldType()) || "server".equals(snapshot.worldType());
    }

    private String percentText(double value) {
        return (int) Math.round(Math.max(0.0D, Math.min(1.0D, value)) * 100.0D) + "%";
    }

    private boolean isOver(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
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
        SERVER_WAIT,
        SERVER_CONTEXT,
        GAMEPLAY,
        UI_FRAME,
        UI_MEMORY,
        WORLD_SIMULATION,
        FAULT_PRESSURE
    }

    private record RecommendationHitbox(int x, int y, int width, int height, PerformanceRecommendation recommendation) {
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

    private boolean shows(DiagnosticsPane pane) {
        return this.activePane == DiagnosticsPane.ALL || this.activePane == pane;
    }

    private enum DiagnosticsPane {
        OVERVIEW(PANE_OVERVIEW_LABEL),
        RENDERING(PANE_RENDERING_LABEL),
        PROCESSING(PANE_PROCESSING_LABEL),
        MEMORY(PANE_MEMORY_LABEL),
        SERVER(PANE_SERVER_LABEL),
        GAMEPLAY(PANE_GAMEPLAY_LABEL),
        UI(PANE_UI_LABEL),
        WORLD(PANE_WORLD_LABEL),
        MODS(PANE_MODS_LABEL),
        ALL(PANE_ALL_LABEL);

        private final String label;

        DiagnosticsPane(String label) {
            this.label = label;
        }

        private static DiagnosticsPane fromLabel(String label) {
            for (DiagnosticsPane pane : values()) {
                if (pane.label.equals(label)) {
                    return pane;
                }
            }
            return OVERVIEW;
        }
    }
}
