package com.spirit.koil.api.design.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.design.sprite.SpriteEngine;
import com.spirit.koil.api.design.sprite.actor.Actor;
import com.spirit.koil.api.design.sprite.actor.ItemActor;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneEvent;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.physics.PhysicsBody2D;
import com.spirit.koil.api.design.sprite.systems.BlockOrientationSystem;
import com.spirit.koil.api.design.sprite.world.BlockCell;
import com.spirit.koil.api.design.sprite.world.Scene;
import com.spirit.koil.api.design.sprite.world.SceneBlockView;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.spirit.koil.api.design.uiColorVal.*;

/**
 * Scene-native editor and engine test harness for Koil's detached sprite world.
 *
 * <p>This screen deliberately edits {@link Scene} rather than generating
 * legacy particle metadata or executing command strings. It is therefore useful
 * both as a background-scene authoring surface and as a direct correctness test
 * for block states, interactions, physics, items, fluids and particles.</p>
 */
public final class SpritePlaygroundScreen extends Screen {
    private static final int HEADER_HEIGHT = 36;
    private static final int FOOTER_HEIGHT = 35;
    private static final int PANEL_GAP = 5;
    private static final int ROW_HEIGHT = 20;
    private static final int HISTORY_LIMIT = 64;

    private final Screen parent;
    private final String closeRestoreSnapshot;
    private final UiParticleEngine engine = ScreenSpriteOverlay.engine();
    private final SpriteEngine spriteEngine = engine.getSpriteEngine();
    private final EnumMap<Tool, ButtonWidget> toolButtons = new EnumMap<>(Tool.class);
    private final EnumMap<CatalogKind, ButtonWidget> catalogButtons = new EnumMap<>(CatalogKind.class);
    private final List<CatalogEntry> catalog = new ArrayList<>();
    private final List<CatalogEntry> filteredCatalog = new ArrayList<>();
    private final List<SpriteWorldgenCatalog.Entry> generationCatalog = new ArrayList<>();
    private final List<SpriteWorldgenCatalog.Entry> filteredGeneration = new ArrayList<>();
    private final Deque<String> undo = new ArrayDeque<>();
    private final Deque<String> redo = new ArrayDeque<>();
    private final BufferBuilder editorUiBuffer = new BufferBuilder(262144);
    private final VertexConsumerProvider.Immediate editorUiConsumers = VertexConsumerProvider.immediate(editorUiBuffer);

    private Tool tool = Tool.MOUSE;
    private CatalogKind catalogKind = CatalogKind.BLOCKS;
    private TextFieldWidget searchField;
    private TextFieldWidget sceneNameField;
    private TextFieldWidget terrainOriginXField;
    private TextFieldWidget terrainOriginYField;
    private TextFieldWidget terrainLayersField;
    private TextFieldWidget terrainSeedField;
    private ButtonWidget terrainWidthButton;
    private ButtonWidget terrainReplaceButton;
    private ButtonWidget terrainLayerPresetButton;
    private ButtonWidget terrainGenerateButton;
    private ButtonWidget generationToggleButton;
    private TextFieldWidget generationSearchField;
    private ButtonWidget generationKindButton;
    private ButtonWidget generationRefreshButton;
    private ButtonWidget placementGhostButton;
    private ButtonWidget layerLinkButton;
    private TextFieldWidget inspectorValueField;
    private ButtonWidget inspectorApplyButton;
    private ButtonWidget playButton;
    private ButtonWidget projectionButton;
    private int catalogScroll;
    private int selectedCatalogIndex;
    private int inspectorScroll;
    private int generationScroll;
    private int selectedGenerationIndex;
    private String inspectorSelectedKey = "";
    private int currentDepth;
    private boolean running = true;
    private boolean showGrid = true;
    private boolean showPlacementGhost = true;
    private boolean generationPanelOpen;
    private boolean generationAllKinds = true;
    private SpriteWorldgenCatalog.Kind generationKind = SpriteWorldgenCatalog.Kind.BIOME;
    private int terrainWidth = 72;
    private int terrainLayerPresetIndex = 2;
    private boolean terrainReplaceExisting;
    private long terrainSeed = 1847L;
    private int terrainOriginX;
    private int terrainOriginY;
    private boolean terrainOriginInitialized;
    private String generationQuery = "";
    private Identifier placementStateId;
    private BlockState placementBlockState;
    private boolean viewInitialized;
    private SceneCellPos selectedCell;
    private long selectedActorId = -1L;
    private long draggingActorId = -1L;
    private boolean itemUseHeld;
    private boolean painting;
    private boolean erasing;
    private boolean panning;
    private boolean panMoved;
    private double panStartX;
    private double panStartY;
    private SceneCellPos paintLastCell;
    private SceneCellPos eraseLastCell;
    private double dragLastX;
    private double dragLastY;
    private float dragReleaseVelocityX;
    private float dragReleaseVelocityY;
    private boolean dragOriginalKinematic;
    private long statusUntil;
    private String searchQuery = "";
    private String sceneName = "playground";
    private String status = "Mouse: select/drag/use | Place: left build, right erase | middle pick/pan";

    public SpritePlaygroundScreen(Screen parent) {
        this(parent, captureCurrentScene(), null, null);
    }

    public SpritePlaygroundScreen(Screen parent, String closeRestoreSnapshot, String initialSceneName, String initialStatus) {
        super(Text.literal("Koil Sprite Playground"));
        this.parent = parent;
        this.closeRestoreSnapshot = closeRestoreSnapshot;
        if (initialSceneName != null && !initialSceneName.isBlank()) this.sceneName = initialSceneName;
        if (initialStatus != null && !initialStatus.isBlank()) {
            this.status = initialStatus;
            this.statusUntil = System.currentTimeMillis() + 6500L;
        }
        GameParticleRegistryBridge.registerAllAvailable();
        GameSpriteRegistryBridge.registerAllAvailable();
        rebuildCatalog();
    }

    public static void open(Screen parent) {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client != null) client.setScreen(new SpritePlaygroundScreen(parent));
    }

    public static String captureCurrentScene() {
        try {
            return SpriteSceneStorage.capture(ScreenSpriteOverlay.engine()).toString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Override
    protected void init() {
        toolButtons.clear();
        catalogButtons.clear();

        Layout l = layout();
        int toolX = l.leftX + 5;
        int toolW = Math.max(70, l.leftWidth - 10);
        int y = l.contentTop + 16;
        int half = Math.max(32, (toolW - 3) / 2);
        Tool[] tools = Tool.values();
        for (int i = 0; i < tools.length; i++) {
            Tool value = tools[i];
            int buttonX = toolX + i * (half + 3);
            int buttonW = i == 0 ? half : toolW - half - 3;
            ButtonWidget button = addDrawableChild(ButtonWidget.builder(Text.literal(value.label), b -> setTool(value))
                    .dimensions(buttonX, y, buttonW, 20).build());
            toolButtons.put(value, button);
        }
        y += 23;

        addDrawableChild(ButtonWidget.builder(Text.literal("Rotate"), b -> rotateSelection(1))
                .dimensions(toolX, y, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Fall"), b -> promoteSelection())
                .dimensions(toolX + half + 3, y, toolW - half - 3, 20).build());
        y += 23;
        addDrawableChild(ButtonWidget.builder(Text.literal("Depth -"), b -> changeDepth(-1))
                .dimensions(toolX, y, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Depth +"), b -> changeDepth(1))
                .dimensions(toolX + half + 3, y, toolW - half - 3, 20).build());
        y += 23;
        projectionButton = addDrawableChild(ButtonWidget.builder(Text.literal("Projection"), b -> cycleProjection())
                .dimensions(toolX, y, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Grid"), b -> showGrid = !showGrid)
                .dimensions(toolX + half + 3, y, toolW - half - 3, 20).build());
        y += 23;
        addDrawableChild(ButtonWidget.builder(Text.literal("Center"), b -> { recordUndo(); centerView(); })
                .dimensions(toolX, y, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Test"), b -> runDetachedSelfTest())
                .dimensions(toolX + half + 3, y, toolW - half - 3, 20).build());
        y += 23;
        placementGhostButton = addDrawableChild(ButtonWidget.builder(Text.literal("Ghost"), b -> togglePlacementGhost())
                .dimensions(toolX, y, half, 20).build());
        layerLinkButton = addDrawableChild(ButtonWidget.builder(Text.literal("Layer Link: Off"), b -> toggleCrossLayerInteractions())
                .dimensions(toolX + half + 3, y, toolW - half - 3, 20).build());

        int terrainY = l.terrainTop() + 18;
        generationToggleButton = addDrawableChild(ButtonWidget.builder(Text.literal("Worldgen Browser"), b -> toggleGenerationPanel())
                .dimensions(toolX, terrainY, toolW, 20).build());

        // Keep persistent labels in renderTerrainPanel. Generation origin is
        // explicit X/Y scene coordinates, while Layers controls Minecraft Z slices.
        // X and Y share one row so they do not masquerade as two unrelated fields.
        if (!terrainOriginInitialized) {
            SceneCellPos initialOrigin = spriteEngine.scene().projection().screenToCellAtDepth(
                    (l.canvasX + l.canvasRight) * 0.5F, l.canvasY + l.canvasHeight() * 0.64F, currentDepth);
            terrainOriginX = initialOrigin.x();
            terrainOriginY = initialOrigin.y();
            terrainOriginInitialized = true;
        }
        terrainY += 33;
        int originGroup = Math.max(32, (toolW - 4) / 2);
        int originFieldW = Math.max(22, originGroup - 10);
        terrainOriginXField = addDrawableChild(new TextFieldWidget(textRenderer, toolX + 9, terrainY, originFieldW, 18, Text.literal("Generation X")));
        terrainOriginXField.setMaxLength(12);
        terrainOriginXField.setText(Integer.toString(terrainOriginX));
        int originYGroupX = toolX + originGroup + 4;
        terrainOriginYField = addDrawableChild(new TextFieldWidget(textRenderer, originYGroupX + 9, terrainY, originFieldW, 18, Text.literal("Generation Y")));
        terrainOriginYField.setMaxLength(12);
        terrainOriginYField.setText(Integer.toString(terrainOriginY));

        terrainY += 30;
        terrainLayersField = addDrawableChild(new TextFieldWidget(textRenderer, toolX, terrainY, toolW, 18, Text.literal("Generation Z layers")));
        terrainLayersField.setMaxLength(96);
        terrainLayersField.setPlaceholder(Text.literal("0..7 or 0,4,8,16,32"));
        terrainLayersField.setText(layerExpressionForPreset(terrainLayerPresetIndex, currentDepth));
        terrainY += 21;
        terrainLayerPresetButton = addDrawableChild(ButtonWidget.builder(Text.literal(layerPresetLabel()), b -> cycleTerrainLayerPreset())
                .dimensions(toolX, terrainY, toolW, 20).build());

        terrainY += 31;
        terrainSeedField = addDrawableChild(new TextFieldWidget(textRenderer, toolX, terrainY, toolW, 18, Text.literal("Generation seed")));
        terrainSeedField.setMaxLength(24);
        terrainSeedField.setPlaceholder(Text.literal("1847"));
        terrainSeedField.setText(Long.toString(terrainSeed));
        terrainY += 21;
        terrainWidthButton = addDrawableChild(ButtonWidget.builder(Text.literal("Width"), b -> cycleTerrainWidth())
                .dimensions(toolX, terrainY, half, 20).build());
        terrainReplaceButton = addDrawableChild(ButtonWidget.builder(Text.literal("Replace"), b -> toggleTerrainReplace())
                .dimensions(toolX + half + 3, terrainY, toolW - half - 3, 20).build());
        terrainY += 23;
        terrainGenerateButton = addDrawableChild(ButtonWidget.builder(Text.literal("Generate Selected"), b -> generateSelectedWorldgen())
                .dimensions(toolX, terrainY, toolW, 20).build());

        searchField = addDrawableChild(new TextFieldWidget(textRenderer, l.rightX + 6, l.contentTop + 16,
                Math.max(40, l.rightWidth - 12), 20, Text.literal("Search registry")));
        searchField.setMaxLength(128);
        searchField.setPlaceholder(Text.literal("Search registry"));
        searchField.setChangedListener(value -> {
            searchQuery = value == null ? "" : value;
            catalogScroll = 0;
            selectedCatalogIndex = 0;
            applyCatalogFilter();
        });
        searchField.setText(searchQuery);

        int tabY = l.contentTop + 40;
        int tabWidth = Math.max(22, (l.rightWidth - 18) / CatalogKind.values().length);
        int tabX = l.rightX + 6;
        boolean compactTabs = l.rightWidth < 150;
        for (CatalogKind kind : CatalogKind.values()) {
            ButtonWidget button = addDrawableChild(ButtonWidget.builder(Text.literal(compactTabs ? kind.shortLabel : kind.label), b -> setCatalogKind(kind))
                    .dimensions(tabX, tabY, tabWidth, 20).build());
            catalogButtons.put(kind, button);
            tabX += tabWidth + 2;
        }

        generationSearchField = addDrawableChild(new TextFieldWidget(textRenderer, l.rightX + 6, l.contentTop + 16,
                Math.max(40, l.rightWidth - 12), 20, Text.literal("Search worldgen")));
        generationSearchField.setMaxLength(160);
        generationSearchField.setPlaceholder(Text.literal("Search biomes, features, structures..."));
        generationSearchField.setChangedListener(value -> {
            generationQuery = value == null ? "" : value;
            generationScroll = 0;
            selectedGenerationIndex = 0;
            applyGenerationFilter();
        });
        generationSearchField.setText(generationQuery);
        int generationKindWidth = Math.max(54, (l.rightWidth - 15) * 2 / 3);
        generationKindButton = addDrawableChild(ButtonWidget.builder(Text.literal("Worldgen"), b -> cycleGenerationKind())
                .dimensions(l.rightX + 6, tabY, generationKindWidth, 20).build());
        generationRefreshButton = addDrawableChild(ButtonWidget.builder(Text.literal("Reload"), b -> reloadGenerationCatalog())
                .dimensions(l.rightX + 8 + generationKindWidth, tabY, Math.max(38, l.rightWidth - generationKindWidth - 16), 20).build());

        int sceneFieldWidth = Math.min(116, Math.max(76, width / 4));
        int sceneFieldX = Math.max(width / 2 + 20, width - sceneFieldWidth - 8);
        sceneNameField = addDrawableChild(new TextFieldWidget(textRenderer,
                sceneFieldX, 8, sceneFieldWidth, 20, Text.literal("Scene name")));
        sceneNameField.setMaxLength(64);
        sceneNameField.setPlaceholder(Text.literal("Scene name"));
        sceneNameField.setChangedListener(value -> sceneName = value == null ? "" : value);
        sceneNameField.setText(sceneName);

        int footerY = height - 22;
        int gap = 3;
        int buttonW = Math.min(70, Math.max(35, (Math.max(1, width - 12) - gap * 7) / 8));
        int total = buttonW * 8 + gap * 7;
        int x = Math.max(4, (width - total) / 2);
        addDrawableChild(ButtonWidget.builder(Text.literal("Save"), b -> saveScene()).dimensions(x, footerY, buttonW, 20).build());
        x += buttonW + gap;
        addDrawableChild(ButtonWidget.builder(Text.literal("Load"), b -> loadScene()).dimensions(x, footerY, buttonW, 20).build());
        x += buttonW + gap;
        addDrawableChild(ButtonWidget.builder(Text.literal("Undo"), b -> undo()).dimensions(x, footerY, buttonW, 20).build());
        x += buttonW + gap;
        addDrawableChild(ButtonWidget.builder(Text.literal("Redo"), b -> redo()).dimensions(x, footerY, buttonW, 20).build());
        x += buttonW + gap;
        playButton = addDrawableChild(ButtonWidget.builder(Text.literal("Pause"), b -> toggleRunning()).dimensions(x, footerY, buttonW, 20).build());
        x += buttonW + gap;
        addDrawableChild(ButtonWidget.builder(Text.literal("Step"), b -> stepScene()).dimensions(x, footerY, buttonW, 20).build());
        x += buttonW + gap;
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear"), b -> clearScene()).dimensions(x, footerY, buttonW, 20).build());
        x += buttonW + gap;
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(x, footerY, buttonW, 20).build());

        int inspectorEditY = l.inspectorEditorY();
        int applyWidth = Math.min(52, Math.max(40, l.rightWidth / 4));
        inspectorValueField = addDrawableChild(new TextFieldWidget(textRenderer, l.rightX + 8, inspectorEditY,
                Math.max(30, l.rightWidth - applyWidth - 19), 18, Text.literal("Inspector value")));
        inspectorValueField.setMaxLength(512);
        inspectorValueField.setPlaceholder(Text.literal("Select a property"));
        inspectorApplyButton = addDrawableChild(ButtonWidget.builder(Text.literal("Apply"), b -> applyInspectorValue())
                .dimensions(l.rightX + l.rightWidth - applyWidth - 7, inspectorEditY, applyWidth, 18).build());
        inspectorValueField.active = false;
        inspectorApplyButton.active = false;

        applyCatalogFilter();
        if (generationPanelOpen) ensureGenerationCatalog();
        updateGenerationWidgetVisibility();
        updateButtonStates();
        if (!viewInitialized) {
            if (sceneHasContent()) viewInitialized = true;
            else centerView();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        Layout l = layout();
        KoilVanillaScreenChrome.renderOptionsShell(context, client, width, height);
        KoilVanillaScreenChrome.renderTitle(context, textRenderer, Text.literal("Sprite Playground"),
                Text.literal("Scene Editor / Engine Test"));

        renderCanvasBase(context, l, mouseX, mouseY);

        // Hard render-pass boundary: screen chrome/grid use GUI RenderLayers while
        // the scene uses Minecraft solid/cutout/translucent layers. Do not leave
        // either family buffered when switching ownership, otherwise DrawContext's
        // shared Immediate provider may flush the canvas after solid terrain and
        // visually erase generated blocks.
        context.draw();

        if (running) {
            engine.beginFrame(width, height, l.canvasX, l.canvasY, l.canvasWidth(), l.canvasHeight(), false);
        } else {
            spriteEngine.setViewport(width, height);
            flushScene();
        }

        // The scene and the editor controls share Minecraft's framebuffer, but
        // they do NOT share depth ownership. Start every scene pass with a fresh
        // depth buffer so GUI depth from a previous frame can never occlude newly
        // generated terrain. This matters most for dense biome/structure output.
        prepareSceneRenderState();
        context.getMatrices().push();
        context.enableScissor(l.canvasX, l.canvasY, l.canvasRight, l.canvasBottom);
        try {
            engine.renderBehind(context);
            renderPlacementGhost(context, l, mouseX, mouseY);
            renderSelection(context);
            engine.renderForeground(context);
            // Finish all native block/fluid/item buffers before the UI pass starts.
            context.draw();
        } finally {
            context.disableScissor();
            context.getMatrices().pop();
        }

        // The GUI gets its own fresh depth domain. Minecraft RenderLayers are
        // allowed to install their own depth state while DrawContext flushes, so
        // disableDepthTest() alone is not a sufficient overlay guarantee. Clear
        // scene depth here, preserve scene COLOR, then draw the editor normally.
        // The next frame also clears depth before rendering the scene, so GUI
        // depth can never feed back into world rendering.
        beginGuiOverlayState();
        try {
            /*
             * Do not render the editor controls through the same Immediate
             * provider used by native block/fluid/entity RenderLayers. Dense
             * generated scenes exercise many RenderLayer transitions and can
             * leave that provider with pending world-layer ownership even after
             * DrawContext.draw(). The widgets were still alive and focused, but
             * their vertices could be swallowed by the scene provider.
             *
             * A fresh DrawContext gives the editor a completely independent GUI
             * buffer. It uses the same framebuffer/projection, so screen-space
             * coordinates remain identical, but no native scene RenderLayer can
             * consume, reorder, or suppress the controls.
             */
            DrawContext uiContext = new DrawContext(client, editorUiConsumers);

            renderLayerRail(uiContext, l, mouseX, mouseY);
            renderPanels(uiContext, l, mouseX, mouseY);
            uiContext.draw();

            super.render(uiContext, mouseX, mouseY, delta);
            renderHeaderInfo(uiContext, l);
            renderStatus(uiContext, l, mouseX, mouseY);
            uiContext.draw();
        } finally {
            endGuiOverlayState();
        }
    }

    /**
     * Native scene renderers use real depth testing. Clear depth before every scene
     * pass so GUI depth from the previous frame can never participate in world
     * composition. The GUI receives a second depth clear after the scene.
     */
    private static void prepareSceneRenderState() {
        RenderSystem.disableScissor();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.clearDepth(1.0D);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.MinecraftClient.IS_SYSTEM_MAC);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Editor chrome/widgets are a separate screen-space pass. Clear only DEPTH
     * after the scene has been completely flushed. Clearing here is intentional:
     * DrawContext/RenderLayer may re-enable depth testing during its own flush, but
     * with a fresh depth buffer the GUI can no longer be rejected by scene geometry.
     */
    private static void beginGuiOverlayState() {
        RenderSystem.disableScissor();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.clearDepth(1.0D);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.MinecraftClient.IS_SYSTEM_MAC);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    private static void endGuiOverlayState() {
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void renderCanvasBase(DrawContext context, Layout l, int mouseX, int mouseY) {
        int border = color(uiColorBackgroundBorder);
        int base = color(uiColorContentBase);
        context.fill(l.canvasX, l.canvasY, l.canvasRight, l.canvasBottom, base);
        context.drawBorder(l.canvasX, l.canvasY, l.canvasWidth(), l.canvasHeight(), border);
        if (!showGrid) return;

        // The grid is one continuous canvas-space projection. The floating
        // controls are a true overlay and do not erase or segment scene content.
        renderGridRegion(context, l, l.canvasX, l.canvasRight);

        if (!pointerOverOverlayPanel(l, mouseX, mouseY)
                && inside(mouseX, mouseY, l.canvasX, l.canvasY, l.canvasRight, l.canvasBottom)) {
            float cell = spriteEngine.scene().projection().cellPixels();
            if (cell < 5.0F) return;
            SceneCellPos hover = cellAt(mouseX, mouseY);
            float cx = spriteEngine.scene().projection().cellCenterScreenX(hover);
            float cy = spriteEngine.scene().projection().cellCenterScreenY(hover);
            float half = cell * 0.5F;
            context.drawBorder(Math.round(cx - half), Math.round(cy - half), Math.max(1, Math.round(cell)),
                    Math.max(1, Math.round(cell)), withAlpha(color(uiColorSelectionHighlight), 150));
        }
    }

    private void renderGridRegion(DrawContext context, Layout l, int regionLeft, int regionRight) {
        if (regionRight <= regionLeft) return;
        float cell = spriteEngine.scene().projection().cellPixels();
        if (cell < 5.0F) return;
        float ox = spriteEngine.scene().projection().originX();
        float oy = spriteEngine.scene().projection().originY();
        int border = color(uiColorBackgroundBorder);
        int grid = withAlpha(border, 72);
        int major = withAlpha(color(uiColorContentStripeLeft), 100);

        float firstBoundaryX = ox - cell * 0.5F;
        int xIndex = (int) Math.floor((regionLeft - firstBoundaryX) / cell);
        float gridX = firstBoundaryX + xIndex * cell;
        while (gridX < regionLeft) { gridX += cell; xIndex++; }
        for (float x = gridX; x < regionRight; x += cell, xIndex++) {
            int px = Math.round(x);
            context.fill(px, l.canvasY + 1, px + 1, l.canvasBottom - 1, floorMod(xIndex, 4) == 0 ? major : grid);
        }

        float firstBoundaryY = oy - cell * 0.5F;
        int yIndex = (int) Math.floor((l.canvasY - firstBoundaryY) / cell);
        float gridY = firstBoundaryY + yIndex * cell;
        while (gridY < l.canvasY) { gridY += cell; yIndex++; }
        for (float y = gridY; y < l.canvasBottom; y += cell, yIndex++) {
            int py = Math.round(y);
            context.fill(regionLeft + 1, py, regionRight - 1, py + 1, floorMod(yIndex, 4) == 0 ? major : grid);
        }
    }

    private void renderSelection(DrawContext context) {
        if (selectedActorId >= 0L) {
            Actor actor = spriteEngine.actor(selectedActorId);
            if (actor != null) {
                int x = Math.round(actor.x() - actor.halfWidth() - 2);
                int y = Math.round(actor.y() - actor.halfHeight() - 2);
                int w = Math.max(4, Math.round(actor.halfWidth() * 2.0F + 4));
                int h = Math.max(4, Math.round(actor.halfHeight() * 2.0F + 4));
                context.drawBorder(x, y, w, h, color(uiColorBackgroundBorderSelected));
                return;
            }
            selectedActorId = -1L;
        }
        if (selectedCell != null) {
            float cell = spriteEngine.scene().projection().cellPixels();
            float cx = spriteEngine.scene().projection().cellCenterScreenX(selectedCell);
            float cy = spriteEngine.scene().projection().cellCenterScreenY(selectedCell);
            int x = Math.round(cx - cell * 0.5F - 1);
            int y = Math.round(cy - cell * 0.5F - 1);
            int size = Math.max(3, Math.round(cell + 2));
            context.drawBorder(x, y, size, size, color(uiColorBackgroundBorderSelected));
        }
    }

    private void renderPanels(DrawContext context, Layout l, int mouseX, int mouseY) {
        int border = color(uiColorBackgroundBorder);
        int panelFill = color(uiColorContentBase);

        // The scene owns the full authoring canvas, but controls must never
        // visually compete with native Minecraft geometry. These are exact panel
        // rectangles only; the central playground remains completely unobstructed.
        // Draw the backplates after the scene pass so even a dense generated
        // biome cannot make panel text/widgets appear to disappear.
        context.fill(l.leftX, l.contentTop, l.leftX + l.leftWidth, l.footerTop, panelFill);
        context.fill(l.rightX, l.contentTop, l.rightX + l.rightWidth, l.footerTop, panelFill);
        context.drawBorder(l.leftX, l.contentTop, l.leftWidth, l.contentHeight(), border);
        context.drawBorder(l.rightX, l.contentTop, l.rightWidth, l.contentHeight(), border);

        context.drawTextWithShadow(textRenderer, Text.literal("Tools"), l.leftX + 7, l.contentTop + 4,
                color(uiColorContentBaseTitleText));
        context.drawTextWithShadow(textRenderer, Text.literal(generationPanelOpen ? "World Generation" : "Registry"), l.rightX + 7, l.contentTop + 4,
                color(uiColorContentBaseTitleText));

        if (generationPanelOpen) {
            renderGenerationCatalog(context, l, mouseX, mouseY);
            renderGenerationDetails(context, l, mouseX, mouseY);
        } else {
            renderCatalog(context, l, mouseX, mouseY);
            renderInspector(context, l, mouseX, mouseY);
        }
        renderTerrainPanel(context, l);

        int infoY = l.footerTop - 37;
        // Hide the readout rather than overlapping the tool stack on unusually
        // short GUI scales. Normal Koil layouts keep all three lines visible.
        if (infoY >= l.contentTop + 180) {
            context.drawText(textRenderer, Text.literal("Edit Z: " + currentDepth + " | All layers visible | " + Math.round(spriteEngine.scene().projection().cellPixels()) + "px"),
                    l.leftX + 7, infoY, color(uiColorContentBaseDescriptionText), false);
            context.drawText(textRenderer, Text.literal("Mid click pick / drag pan"),
                    l.leftX + 7, infoY + 11, color(uiColorContentBaseDescriptionText), false);
            context.drawText(textRenderer, Text.literal("T test | C coverage"), l.leftX + 7, infoY + 22,
                    color(uiColorContentBaseDescriptionText), false);
        }
    }

    private void renderMouseGuide(DrawContext context, Layout l) {
        int y = l.contentTop + 119;
        int bottom = l.terrainTop() - 4;
        if (y + 18 > bottom) return;
        context.drawText(textRenderer, Text.literal(fit("Mid pick / drag pan", l.leftWidth - 14)), l.leftX + 7, y,
                color(uiColorContentBaseDescriptionText), false);
        context.drawText(textRenderer, Text.literal(fit("Bottom rail = edit Z", l.leftWidth - 14)), l.leftX + 7, y + 10,
                color(uiColorContentBaseDescriptionText), false);
    }

    private void renderTerrainPanel(DrawContext context, Layout l) {
        int top = l.terrainTop();
        int bottom = Math.min(l.footerTop - 42, top + 218);
        if (bottom <= top + 18) return;
        context.drawBorder(l.leftX + 4, top, Math.max(10, l.leftWidth - 8), bottom - top, color(uiColorBackgroundBorder));
        context.drawText(textRenderer, Text.literal("Generation"), l.leftX + 8, top + 5, color(uiColorContentBaseTitleText), false);

        int toolX = l.leftX + 5;
        int toolW = Math.max(70, l.leftWidth - 10);
        int originGroup = Math.max(32, (toolW - 4) / 2);
        int originYGroupX = toolX + originGroup + 4;
        context.drawText(textRenderer, Text.literal("X"), toolX, top + 56, color(uiColorBasicSubtitleText), false);
        context.drawText(textRenderer, Text.literal("Y"), originYGroupX, top + 56, color(uiColorBasicSubtitleText), false);
        context.drawText(textRenderer, Text.literal(fit("Layers / Minecraft Z slices", l.leftWidth - 16)),
                l.leftX + 8, top + 72, color(uiColorBasicSubtitleText), false);
        context.drawText(textRenderer, Text.literal("Seed"),
                l.leftX + 8, top + 124, color(uiColorBasicSubtitleText), false);

        String layerInfo = terrainLayersField == null ? String.valueOf(currentDepth) : terrainLayersField.getText();
        SpriteWorldgenCatalog.Entry selected = selectedGenerationEntry();
        String source = selected == null ? "Choose worldgen data" : selected.kind().shortLabel() + ": " + selected.id();
        int infoY = top + 202;
        if (infoY < bottom - 2) {
            context.drawText(textRenderer, Text.literal(fit(source, l.leftWidth - 16)), l.leftX + 8, infoY,
                    color(uiColorContentBaseDescriptionText), false);
        }
        if (top + 190 < bottom - 2) {
            String depthInfo = selected != null && isStructureVolumeKind(selected.kind())
                    ? "Structure: edit Z=" + currentDepth + " + full native depth"
                    : "Slices: " + layerInfo;
            context.drawText(textRenderer, Text.literal(fit(depthInfo, l.leftWidth - 16)), l.leftX + 8, top + 190,
                    color(uiColorContentBaseDescriptionText), false);
        }
    }

    private void renderPlacementGhost(DrawContext context, Layout l, int mouseX, int mouseY) {
        if (!showPlacementGhost || tool != Tool.PLACE || generationPanelOpen
                || pointerOverOverlayPanel(l, mouseX, mouseY)
                || !inside(mouseX, mouseY, l.canvasX, l.canvasY, l.canvasRight, l.canvasBottom)) return;
        CatalogEntry entry = selectedCatalogEntry();
        if (entry == null) return;
        SceneCellPos cell = cellAt(mouseX, mouseY);
        float pixels = spriteEngine.scene().projection().cellPixels();
        float cx = spriteEngine.scene().projection().cellCenterScreenX(cell);
        float cy = spriteEngine.scene().projection().cellCenterScreenY(cell);
        int left = Math.round(cx - pixels * 0.5F);
        int top = Math.round(cy - pixels * 0.5F);
        int size = Math.max(3, Math.round(pixels));
        int outline = withAlpha(color(uiColorSelectionHighlight), 190);
        switch (entry.kind) {
            case BLOCKS -> {
                if (!Registries.BLOCK.containsId(entry.id)) return;
                ensurePlacementState(entry);
                BlockState state = placementBlockState == null ? Registries.BLOCK.get(entry.id).getDefaultState() : placementBlockState;
                boolean rendered = engine.renderBlockGhost(context, cell, state, 0.48F);
                if (rendered) context.draw();
                if (!rendered) {
                    Item item = state.getBlock().asItem();
                    if (item != null && item != Items.AIR) context.drawItem(new ItemStack(item), Math.round(cx) - 8, Math.round(cy) - 8);
                }
                context.drawBorder(left, top, size, size, outline);
                String orientation = compactState(state);
                context.drawText(textRenderer, Text.literal(fit(orientation, Math.max(50, size * 5))), left, top - 10,
                        color(uiColorBasicSubtitleText), false);
            }
            case ITEMS -> {
                if (!entry.icon.isEmpty()) context.drawItem(entry.icon, Math.round(cx) - 8, Math.round(cy) - 8);
                context.drawBorder(left, top, size, size, outline);
            }
            case FLUIDS -> {
                int fill = entry.id.getPath().contains("lava") ? 0x55FF6A00 : 0x553F76E4;
                context.fill(left + 1, top + 1, left + size - 1, top + size - 1, fill);
                context.drawBorder(left, top, size, size, outline);
            }
            case PARTICLES -> context.drawBorder(left, top, size, size, outline);
        }
    }

    /* World-generation browser. */
    private void renderGenerationCatalog(DrawContext context, Layout l, int mouseX, int mouseY) {
        int top = l.catalogTop();
        int bottom = l.inspectorTop() - 4;
        context.drawBorder(l.rightX + 5, top, l.rightWidth - 10, Math.max(10, bottom - top), color(uiColorBackgroundBorder));
        int visible = Math.max(1, (bottom - top - 4) / ROW_HEIGHT);
        generationScroll = Math.max(0, Math.min(generationScroll, Math.max(0, filteredGeneration.size() - visible)));
        int max = Math.min(filteredGeneration.size(), generationScroll + visible);
        int row = 0;
        for (int i = generationScroll; i < max; i++, row++) {
            SpriteWorldgenCatalog.Entry entry = filteredGeneration.get(i);
            int y = top + 2 + row * ROW_HEIGHT;
            boolean selected = i == selectedGenerationIndex;
            boolean hovered = inside(mouseX, mouseY, l.rightX + 6, y, l.rightX + l.rightWidth - 6, y + ROW_HEIGHT);
            if (selected || hovered) {
                context.fill(l.rightX + 6, y, l.rightX + l.rightWidth - 6, y + ROW_HEIGHT - 1,
                        withAlpha(color(selected ? uiColorSelectionHighlight : uiColorNonSelectionHighlight), selected ? 120 : 72));
            }
            context.drawText(textRenderer, Text.literal(fit(entry.label(), Math.max(30, l.rightWidth - 58))),
                    l.rightX + 10, y + 3, color(uiColorContentBaseDescriptionText), false);
            context.drawText(textRenderer, Text.literal(fit(entry.id().getNamespace(), 44)),
                    l.rightX + 10, y + 12, color(uiColorBasicSubtitleText), false);
            context.drawText(textRenderer, Text.literal(fit(entry.summary(), Math.max(30, l.rightWidth / 2))),
                    l.rightX + Math.max(55, l.rightWidth / 2), y + 12, color(uiColorBasicSubtitleText), false);
        }
        if (filteredGeneration.isEmpty()) {
            String scope = generationAllKinds ? "worldgen resources" : generationKind.label().toLowerCase(Locale.ROOT);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("No " + scope + " match"),
                    l.rightX + l.rightWidth / 2, top + 16, color(uiColorBasicSubtitleText));
        }
    }

    private void renderGenerationDetails(DrawContext context, Layout l, int mouseX, int mouseY) {
        int top = l.inspectorTop();
        int left = l.rightX + 5;
        int right = l.rightX + l.rightWidth - 5;
        int bottom = l.footerTop - 5;
        context.drawBorder(left, top, right - left, Math.max(10, bottom - top), color(uiColorBackgroundBorder));
        context.drawText(textRenderer, Text.literal("Generation Source"), left + 5, top + 5, color(uiColorContentBaseTitleText), false);
        SpriteWorldgenCatalog.Entry entry = selectedGenerationEntry();
        if (entry == null) {
            context.drawText(textRenderer, Text.literal("Choose an entry above"), left + 7, top + 22, color(uiColorBasicSubtitleText), false);
            return;
        }
        int y = top + 21;
        String fidelity = entry.kind() == SpriteWorldgenCatalog.Kind.BIOME
                ? "Biome mode: sliced terrain + carvers + ordered placed features; auto-frames the result."
                : entry.kind() == SpriteWorldgenCatalog.Kind.STRUCTURE_TEMPLATE
                ? "Uses the actual structure NBT palette/blocks."
                : "Uses the actual data-pack resource as the 2D generation specification.";
        String[] lines = {
                "ID: " + entry.id(),
                "Type: " + entry.kind().label(),
                "Provider: " + entry.provider(),
                "Data: " + entry.summary(),
                "Layers: " + (terrainLayersField == null ? currentDepth : terrainLayersField.getText()),
                "Width: " + terrainWidth + " | Replace: " + (terrainReplaceExisting ? "On" : "Off"),
                fidelity,
                entry.kind() == SpriteWorldgenCatalog.Kind.BIOME
                        ? "Status reports carvers/features applied/skipped; gaps in Layers remain empty."
                        : "Generate with the left-panel Generate Selected button."
        };
        for (String line : lines) {
            if (y + 10 >= bottom) break;
            context.drawText(textRenderer, Text.literal(fit(line, l.rightWidth - 20)), left + 7, y,
                    color(uiColorContentBaseDescriptionText), false);
            y += 12;
        }
    }

    private void renderCatalog(DrawContext context, Layout l, int mouseX, int mouseY) {
        int top = l.catalogTop();
        int bottom = l.inspectorTop() - 4;
        context.drawBorder(l.rightX + 5, top, l.rightWidth - 10, Math.max(10, bottom - top), color(uiColorBackgroundBorder));
        int visible = Math.max(1, (bottom - top - 4) / ROW_HEIGHT);
        catalogScroll = Math.max(0, Math.min(catalogScroll, Math.max(0, filteredCatalog.size() - visible)));
        int max = Math.min(filteredCatalog.size(), catalogScroll + visible);
        int row = 0;
        for (int i = catalogScroll; i < max; i++, row++) {
            CatalogEntry entry = filteredCatalog.get(i);
            int y = top + 2 + row * ROW_HEIGHT;
            boolean selected = i == selectedCatalogIndex;
            boolean hovered = inside(mouseX, mouseY, l.rightX + 6, y, l.rightX + l.rightWidth - 6, y + ROW_HEIGHT);
            if (selected || hovered) {
                context.fill(l.rightX + 6, y, l.rightX + l.rightWidth - 6, y + ROW_HEIGHT - 1,
                        withAlpha(color(selected ? uiColorSelectionHighlight : uiColorNonSelectionHighlight), selected ? 120 : 72));
            }
            if (!entry.icon.isEmpty()) context.drawItem(entry.icon, l.rightX + 9, y + 2);
            else context.drawText(textRenderer, Text.literal("*"), l.rightX + 13, y + 6,
                    color(uiColorContentStripeLeft), false);
            String label = fit(entry.label, l.rightWidth - 42);
            context.drawText(textRenderer, Text.literal(label), l.rightX + 30, y + 6,
                    color(uiColorContentBaseDescriptionText), false);
        }
        if (filteredCatalog.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("No matches"), l.rightX + l.rightWidth / 2,
                    top + 16, color(uiColorBasicSubtitleText));
        }
    }

    private void renderInspector(DrawContext context, Layout l, int mouseX, int mouseY) {
        int top = l.inspectorTop();
        int left = l.rightX + 5;
        int right = l.rightX + l.rightWidth - 5;
        int bottom = l.footerTop - 5;
        context.drawBorder(left, top, right - left, Math.max(10, bottom - top), color(uiColorBackgroundBorder));
        context.drawText(textRenderer, Text.literal("Inspector"), left + 5, top + 5,
                color(uiColorContentBaseTitleText), false);

        String title = inspectorTitle();
        context.drawText(textRenderer, Text.literal(fit(title, l.rightWidth - 20)), left + 5, top + 18,
                color(uiColorContentBaseDescriptionText), false);

        List<InspectorRow> rows = inspectorRows();
        int viewportTop = top + 31;
        int viewportBottom = l.inspectorEditorY() - 4;
        int visible = Math.max(1, (viewportBottom - viewportTop) / 13);
        inspectorScroll = Math.max(0, Math.min(inspectorScroll, Math.max(0, rows.size() - visible)));
        int endRow = Math.min(rows.size(), inspectorScroll + visible);

        context.enableScissor(left + 1, viewportTop, right - 1, viewportBottom);
        for (int i = inspectorScroll; i < endRow; i++) {
            InspectorRow row = rows.get(i);
            int rowY = viewportTop + (i - inspectorScroll) * 13;
            if (row.kind == InspectorKind.SECTION) {
                context.fill(left + 4, rowY + 10, right - 4, rowY + 11, withAlpha(color(uiColorContentStripeLeft), 100));
                context.drawText(textRenderer, Text.literal(row.label), left + 7, rowY + 1,
                        color(uiColorContentBaseTitleText), false);
                continue;
            }
            boolean hovered = inside(mouseX, mouseY, left + 4, rowY, right - 4, rowY + 12);
            boolean selected = row.key.equals(inspectorSelectedKey);
            if (hovered || selected) {
                context.fill(left + 4, rowY, right - 4, rowY + 12,
                        withAlpha(color(selected ? uiColorSelectionHighlight : uiColorNonSelectionHighlight), selected ? 105 : 60));
            }
            String prefix = row.editable ? "" : "[R] ";
            String text = prefix + row.label + " = " + row.value;
            context.drawText(textRenderer, Text.literal(fit(text, l.rightWidth - 20)), left + 7, rowY + 2,
                    color(row.editable ? uiColorContentBaseDescriptionText : uiColorBasicSubtitleText), false);
        }
        context.disableScissor();

        if (rows.isEmpty()) {
            context.drawText(textRenderer, Text.literal("Select a block, fluid, or item"), left + 7, viewportTop + 4,
                    color(uiColorBasicSubtitleText), false);
        }
        syncInspectorEditor(rows);
    }

    private String inspectorTitle() {
        if (selectedActorId >= 0L) {
            Actor actor = spriteEngine.actor(selectedActorId);
            if (actor instanceof ItemActor item && !item.stack().isEmpty()) {
                return Registries.ITEM.getId(item.stack().getItem()).toString();
            }
            if (actor != null) return actor.kind() + " #" + actor.id();
        }
        if (selectedCell != null) {
            BlockState state = spriteEngine.scene().blocks().getBlockState(selectedCell);
            if (state != null && !state.isAir()) return Registries.BLOCK.getId(state.getBlock()).toString();
            FluidState fluid = spriteEngine.scene().fluids().getFluidState(selectedCell);
            if (fluid != null && !fluid.isEmpty()) return Registries.FLUID.getId(fluid.getFluid()).toString();
        }
        return "No selection";
    }

    private List<InspectorRow> inspectorRows() {
        List<InspectorRow> rows = new ArrayList<>();
        if (selectedActorId >= 0L) {
            Actor actor = spriteEngine.actor(selectedActorId);
            if (actor != null) buildActorInspector(rows, actor);
            return rows;
        }
        if (selectedCell == null) return rows;

        BlockState state = spriteEngine.scene().blocks().getBlockState(selectedCell);
        if (state != null && !state.isAir()) {
            buildBlockInspector(rows, selectedCell, state);
            return rows;
        }
        FluidState fluid = spriteEngine.scene().fluids().getFluidState(selectedCell);
        if (fluid != null && !fluid.isEmpty()) buildFluidInspector(rows, fluid);
        return rows;
    }

    private void buildBlockInspector(List<InspectorRow> rows, SceneCellPos pos, BlockState state) {
        rows.add(InspectorRow.section("Native BlockState"));
        for (Property<?> property : sortedProperties(state)) {
            rows.add(new InspectorRow("state:" + property.getName(), property.getName(), propertyValue(state, property),
                    true, InspectorKind.BLOCK_STATE, property));
        }

        BlockCell cell = spriteEngine.scene().blocks().get(pos);
        GameSpriteBehavior.BlockProfile profile = GameSpriteBehavior.blockProfile(state.getBlock());
        rows.add(InspectorRow.section("Scene Overrides"));
        addRuntimeRow(rows, cell, "lifetime", "lifetime", "-1");
        addRuntimeRow(rows, cell, "health", "health", "20");
        addRuntimeRow(rows, cell, "max_health", "max health", "20");
        addRuntimeRow(rows, cell, "collision_enabled", "collision", "true");
        addRuntimeRow(rows, cell, "interactive", "interactive", "true");
        addRuntimeRow(rows, cell, "hardness", "hardness", vanillaHardness(state, pos));
        addRuntimeRow(rows, cell, "blast_resistance", "blast resistance", round2(state.getBlock().getBlastResistance()));
        addRuntimeRow(rows, cell, "slipperiness", "slipperiness", round2(state.getBlock().getSlipperiness()));
        addRuntimeRow(rows, cell, "velocity_multiplier", "velocity multiplier", round2(state.getBlock().getVelocityMultiplier()));
        addRuntimeRow(rows, cell, "jump_velocity_multiplier", "jump multiplier", round2(state.getBlock().getJumpVelocityMultiplier()));
        addRuntimeRow(rows, cell, "mass", "mass", round2(profile.mass()));
        addRuntimeRow(rows, cell, "solidity", "solidity", round2(profile.solidity()));
        addRuntimeRow(rows, cell, "gravity", "falling gravity", round2(profile.gravity()));
        addRuntimeRow(rows, cell, "drag", "falling drag", round2(profile.drag()));
        addRuntimeRow(rows, cell, "sensor_radius", "sensor radius", round2(profile.sensorRadius()));
        addRuntimeRow(rows, cell, "restitution", "restitution", round2(profile.restitution()));
        addRuntimeRow(rows, cell, "surface_friction", "surface friction", round2(profile.surfaceFriction()));
        addRuntimeRow(rows, cell, "temperature", "temperature", "0");
        addRuntimeRow(rows, cell, "charge", "charge", round2(profile.initialPower()));
        addRuntimeRow(rows, cell, "conductivity", "conductivity", "0");
        addRuntimeRow(rows, cell, "flammability", "flammability", state.isBurnable() ? "1" : "0");
        addRuntimeRow(rows, cell, "buoyancy", "buoyancy", "0");
        addRuntimeRow(rows, cell, "render_alpha", "render alpha", "1");
        addRuntimeRow(rows, cell, "light_intensity", "light intensity", Integer.toString(state.getLuminance()));
        addRuntimeRow(rows, cell, "light_color", "light color", "auto");
        addRuntimeRow(rows, cell, "light_transmission", "light transmission", "auto");
        addRuntimeRow(rows, cell, "light_absorption", "light absorption", "auto");
        rows.add(new InspectorRow("flags", "flags", cell == null ? "0" : Integer.toString(cell.flags()),
                true, InspectorKind.FLAGS, null));

        rows.add(InspectorRow.section("Vanilla Base / Identity"));
        rows.add(InspectorRow.read("vanilla:id", "registry id", String.valueOf(Registries.BLOCK.getId(state.getBlock()))));
        rows.add(InspectorRow.read("scene:authority", "authority", cell == null ? "none" : cell.authority().name().toLowerCase(Locale.ROOT)));
        rows.add(InspectorRow.read("scene:source", "source id", cell == null ? "-1" : Long.toString(cell.sourceId())));
        rows.add(InspectorRow.read("scene:created", "created tick", cell == null ? "0" : Long.toString(cell.createdGameTick())));
        rows.add(InspectorRow.read("light:combined", "scene light", Integer.toString(spriteEngine.scene().lighting().lightLevel(pos))));
        rows.add(InspectorRow.read("light:rgb", "scene light rgb", spriteEngine.scene().lighting().color(pos).hex()));
        rows.add(InspectorRow.read("light:mask", "light medium", spriteEngine.scene().lighting().maskName(pos)));
        rows.add(InspectorRow.read("light:sky", "sky light", Integer.toString(spriteEngine.scene().lighting().skyLight(pos))));
        rows.add(InspectorRow.read("light:sky_rgb", "sky rgb", spriteEngine.scene().lighting().skyColor(pos).hex()));
        rows.add(InspectorRow.read("light:block", "block light", Integer.toString(spriteEngine.scene().lighting().blockLight(pos))));
        rows.add(InspectorRow.read("light:block_rgb", "block rgb", spriteEngine.scene().lighting().blockColor(pos).hex()));
        rows.add(InspectorRow.read("light:brightness", "render brightness", round2(spriteEngine.scene().lighting().brightness(pos))));
        rows.add(InspectorRow.read("vanilla:hardness", "hardness", vanillaHardness(state, pos)));
        rows.add(InspectorRow.read("vanilla:blast", "blast resistance", round2(state.getBlock().getBlastResistance())));
        rows.add(InspectorRow.read("vanilla:luminance", "luminance", Integer.toString(state.getLuminance())));
        rows.add(InspectorRow.read("vanilla:piston", "piston behavior", state.getPistonBehavior().name().toLowerCase(Locale.ROOT)));
        rows.add(InspectorRow.read("vanilla:render", "render type", state.getRenderType().name().toLowerCase(Locale.ROOT)));
        rows.add(InspectorRow.read("vanilla:sound", "sound group", state.getSoundGroup().toString()));
        rows.add(InspectorRow.read("vanilla:tool", "tool required", Boolean.toString(state.isToolRequired())));
        rows.add(InspectorRow.read("vanilla:random", "random ticks", Boolean.toString(state.hasRandomTicks())));
        rows.add(InspectorRow.read("vanilla:opaque", "opaque", Boolean.toString(state.isOpaque())));
        rows.add(InspectorRow.read("vanilla:burnable", "burnable", Boolean.toString(state.isBurnable())));
        rows.add(InspectorRow.read("vanilla:block_entity", "block entity", Boolean.toString(state.hasBlockEntity())));
        rows.add(InspectorRow.read("vanilla:replaceable", "replaceable", Boolean.toString(state.isReplaceable())));
        rows.add(InspectorRow.read("vanilla:liquid", "liquid", Boolean.toString(state.isLiquid())));
        rows.add(InspectorRow.read("vanilla:solid", "solid", Boolean.toString(state.isSolid())));
        rows.add(InspectorRow.read("vanilla:movement", "blocks movement", Boolean.toString(state.blocksMovement())));
        rows.add(InspectorRow.read("vanilla:redstone", "emits redstone", Boolean.toString(state.emitsRedstonePower())));
        rows.add(InspectorRow.read("vanilla:comparator", "comparator output", Boolean.toString(state.hasComparatorOutput())));
        rows.add(InspectorRow.read("vanilla:opacity", "opacity", vanillaOpacity(state, pos)));
        rows.add(InspectorRow.read("vanilla:emissive", "emissive lighting", vanillaEmissive(state, pos)));
        rows.add(InspectorRow.read("vanilla:transparency", "sided transparency", Boolean.toString(state.hasSidedTransparency())));
        rows.add(InspectorRow.read("vanilla:break_particles", "break particles", Boolean.toString(state.hasBlockBreakParticles())));
        rows.add(InspectorRow.read("vanilla:exceeds_cube", "exceeds cube", Boolean.toString(state.exceedsCube())));
        rows.add(InspectorRow.read("vanilla:raw_state", "raw state id", Integer.toString(Block.getRawIdFromState(state))));
        rows.add(InspectorRow.read("vanilla:translation", "translation key", state.getBlock().getTranslationKey()));
        rows.add(InspectorRow.read("vanilla:profile", "profile", profile.role()));
        rows.add(InspectorRow.read("vanilla:material", "material", profile.material().name().toLowerCase(Locale.ROOT)));
        rows.add(InspectorRow.read("vanilla:tags", "capability tags", String.join(",", profile.tags())));

        rows.add(InspectorRow.section("Block Entity / Custom Data"));
        if (cell != null) {
            for (Map.Entry<String, String> entry : cell.blockEntityData().entrySet()) {
                rows.add(new InspectorRow("be:" + entry.getKey(), "be." + entry.getKey(), entry.getValue(),
                        true, InspectorKind.BLOCK_ENTITY, null));
            }
            for (Map.Entry<String, String> entry : cell.runtimeData().entrySet()) {
                if (isStandardRuntimeKey(entry.getKey())) continue;
                rows.add(new InspectorRow("runtime:" + entry.getKey(), entry.getKey(), entry.getValue(),
                        true, InspectorKind.RUNTIME, null));
            }
        }
        rows.add(new InspectorRow("custom", "+ custom", "key=value or be.key=value",
                true, InspectorKind.CUSTOM, null));
    }

    private void buildFluidInspector(List<InspectorRow> rows, FluidState state) {
        rows.add(InspectorRow.section("Fluid State"));
        for (Property<?> property : sortedProperties(state)) {
            rows.add(new InspectorRow("fluid:" + property.getName(), property.getName(), propertyValue(state, property),
                    true, InspectorKind.FLUID_STATE, property));
        }
        rows.add(InspectorRow.read("fluid:level", "level", Integer.toString(safeFluidLevel(state))));
        rows.add(InspectorRow.read("fluid:source", "source", Boolean.toString(state.getFluid().isStill(state))));
    }

    private void buildActorInspector(List<InspectorRow> rows, Actor actor) {
        PhysicsBody2D body = actor.body();
        rows.add(InspectorRow.section("Transform / Motion"));
        addActorRow(rows, "x", round2(actor.x()));
        addActorRow(rows, "y", round2(actor.y()));
        addActorRow(rows, "depth", Integer.toString(actor.depth()));
        addActorRow(rows, "velocity_x", round2(actor.velocityX()));
        addActorRow(rows, "velocity_y", round2(actor.velocityY()));
        addActorRow(rows, "rotation", round2(actor.rotation()));
        addActorRow(rows, "angular_velocity", round2(actor.angularVelocity()));
        addActorRow(rows, "width", round2(actor.halfWidth() * 2.0F));
        addActorRow(rows, "height", round2(actor.halfHeight() * 2.0F));
        rows.add(InspectorRow.section("Physics"));
        addActorRow(rows, "mass", round2(body.mass()));
        addActorRow(rows, "gravity", round2(body.gravity()));
        addActorRow(rows, "drag", round2(body.linearDampingPerMinecraftTick()));
        addActorRow(rows, "angular_drag", round2(body.angularDampingPerMinecraftTick()));
        addActorRow(rows, "restitution", round2(body.restitution()));
        addActorRow(rows, "surface_friction", round2(body.surfaceFriction()));
        addActorRow(rows, "kinematic", Boolean.toString(body.kinematic()));
        addActorRow(rows, "collide_world", Boolean.toString(body.collideWorld()));
        addActorRow(rows, "collide_actors", Boolean.toString(body.collideActors()));
        addActorRow(rows, "collide_bounds", Boolean.toString(body.collideSceneBounds()));
        addActorRow(rows, "can_sleep", Boolean.toString(body.canSleep()));
        rows.add(InspectorRow.read("actor:sleeping", "sleeping", Boolean.toString(body.sleeping())));
        rows.add(InspectorRow.read("actor:grounded", "grounded", Boolean.toString(body.grounded())));
        if (actor instanceof ItemActor item) {
            rows.add(InspectorRow.section("ItemStack"));
            addActorRow(rows, "stack_count", Integer.toString(item.stack().getCount()));
            rows.add(InspectorRow.read("actor:item_nbt", "nbt", String.valueOf(item.stack().getNbt())));
        }
    }

    private void addActorRow(List<InspectorRow> rows, String key, String value) {
        rows.add(new InspectorRow("actor:" + key, key.replace('_', ' '), value, true, InspectorKind.ACTOR, null));
    }

    private void addRuntimeRow(List<InspectorRow> rows, BlockCell cell, String key, String label, String fallback) {
        String value = cell == null ? fallback : cell.runtime(key, fallback);
        boolean override = cell != null && cell.runtimeData().containsKey(key);
        rows.add(new InspectorRow("runtime:" + key, label + (override ? " *" : ""), value,
                true, InspectorKind.RUNTIME, null));
    }

    private String vanillaHardness(BlockState state, SceneCellPos pos) {
        try {
            return round2(state.getHardness(sceneBlockView(), spriteEngine.scene().projection().toMinecraft(pos)));
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }

    private String vanillaOpacity(BlockState state, SceneCellPos pos) {
        try {
            return Integer.toString(state.getOpacity(sceneBlockView(), spriteEngine.scene().projection().toMinecraft(pos)));
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }

    private String vanillaEmissive(BlockState state, SceneCellPos pos) {
        try {
            return Boolean.toString(state.hasEmissiveLighting(sceneBlockView(), spriteEngine.scene().projection().toMinecraft(pos)));
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }

    private SceneBlockView sceneBlockView() {
        return new SceneBlockView(spriteEngine.scene().blocks(), spriteEngine.scene().fluids());
    }

    private void syncInspectorEditor(List<InspectorRow> rows) {
        if (inspectorValueField == null || inspectorApplyButton == null) return;
        InspectorRow selected = null;
        for (InspectorRow row : rows) if (row.key.equals(inspectorSelectedKey)) { selected = row; break; }
        if (selected == null || !selected.editable || selected.kind == InspectorKind.SECTION) {
            inspectorValueField.active = false;
            inspectorApplyButton.active = false;
            if (selected == null) inspectorSelectedKey = "";
            return;
        }
        inspectorValueField.active = true;
        inspectorApplyButton.active = true;
    }

    private boolean isStandardRuntimeKey(String key) {
        if (key == null) return false;
        return switch (key) {
            case "lifetime", "health", "max_health", "collision_enabled", "interactive", "hardness",
                    "blast_resistance", "slipperiness", "velocity_multiplier", "jump_velocity_multiplier",
                    "mass", "solidity", "gravity", "drag", "sensor_radius", "restitution", "surface_friction",
                    "temperature", "charge", "conductivity", "flammability", "buoyancy", "render_alpha",
                    "light_intensity", "light_color", "light_transmission", "light_absorption" -> true;
            default -> false;
        };
    }

    private void renderHeaderInfo(DrawContext context, Layout l) {
        context.drawText(textRenderer, Text.literal(running ? "LIVE" : "PAUSED"), l.canvasX + 7, l.contentTop + 7,
                color(running ? uiColorSaveSuccessColor : uiColorWarnColor), true);
    }

    private void renderStatus(DrawContext context, Layout l, int mouseX, int mouseY) {
        Scene scene = spriteEngine.scene();
        String diagnostics = "blocks " + scene.blocks().blockCount() + " | fluids " + scene.fluids().fluidCount()
                + " | actors " + scene.actors().size() + " | game tick " + scene.clock().gameTick();
        context.drawText(textRenderer, Text.literal(diagnostics), 8, height - 32,
                color(uiColorBasicSubtitleText), false);
        String right = System.currentTimeMillis() < statusUntil ? status
                : tool.label + " | depth " + currentDepth + " | " + shortProjection();
        context.drawText(textRenderer, Text.literal(fit(right, Math.max(80, width / 2))),
                Math.max(width / 2, width - textRenderer.getWidth(right) - 8), height - 32,
                color(uiColorBasicSubtitleText), false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        Layout l = layout();

        if (generationPanelOpen) {
            if (inside(mouseX, mouseY, l.rightX + 5, l.catalogTop(), l.rightX + l.rightWidth - 5, l.inspectorTop() - 4)) {
                int index = generationScroll + ((int) mouseY - l.catalogTop() - 2) / ROW_HEIGHT;
                if (index >= 0 && index < filteredGeneration.size()) {
                    selectedGenerationIndex = index;
                    updateButtonStates();
                    setStatus("Generation selected: " + filteredGeneration.get(index).id());
                    return true;
                }
            }
        } else {
            if (handleInspectorClick(l, mouseX, mouseY, button)) return true;
            if (inside(mouseX, mouseY, l.rightX + 5, l.catalogTop(), l.rightX + l.rightWidth - 5, l.inspectorTop() - 4)) {
                int index = catalogScroll + ((int) mouseY - l.catalogTop() - 2) / ROW_HEIGHT;
                if (index >= 0 && index < filteredCatalog.size()) {
                    selectedCatalogIndex = index;
                    resetPlacementState();
                    setTool(Tool.PLACE);
                    return true;
                }
            }
        }

        Integer railLayer = layerRailLayerAt(l, mouseX, mouseY);
        if (railLayer != null) {
            selectEditDepth(railLayer);
            return true;
        }
        if (pointerOverOverlayPanel(l, mouseX, mouseY)) return true;
        if (!inside(mouseX, mouseY, l.canvasX, l.canvasY, l.canvasRight, l.canvasBottom)) return false;

        // Middle mouse is universal: click = eyedropper, drag = pan. Deferring
        // the eyedropper until release lets one mouse button perform both without
        // an extra Pan tool or accidental picks at the start of a camera drag.
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            panning = true;
            panMoved = false;
            panStartX = mouseX;
            panStartY = mouseY;
            dragLastX = mouseX;
            dragLastY = mouseY;
            return true;
        }

        if (tool == Tool.MOUSE) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                selectAt((float) mouseX, (float) mouseY);
                if (selectedActorId >= 0L) {
                    recordUndo();
                    draggingActorId = selectedActorId;
                    Actor actor = spriteEngine.actor(draggingActorId);
                    if (actor != null) {
                        dragOriginalKinematic = actor.body().kinematic();
                        actor.setKinematic(true);
                        actor.body().teleport((float) mouseX, (float) mouseY);
                        actor.setVelocity(0.0F, 0.0F);
                    }
                    dragLastX = mouseX;
                    dragLastY = mouseY;
                    dragReleaseVelocityX = 0.0F;
                    dragReleaseVelocityY = 0.0F;
                }
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                long actor = actorAt((float) mouseX, (float) mouseY);
                if (actor >= 0L && spriteEngine.actor(actor) instanceof ItemActor) {
                    if (selectedActorId != actor) {
                        clearInspectorSelection();
                        selectedActorId = actor;
                        selectedCell = null;
                        setStatus("Selected item for use. Right-click a block to use it.");
                        return true;
                    }
                }
                useAt((float) mouseX, (float) mouseY);
                return true;
            }
            return false;
        }

        if (tool == Tool.PLACE) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                CatalogEntry entry = selectedCatalogEntry();
                boolean paintable = entry != null && (entry.kind == CatalogKind.BLOCKS || entry.kind == CatalogKind.FLUIDS);
                if (paintable) {
                    recordUndo();
                    painting = true;
                    paintLastCell = null;
                    placeSelected((float) mouseX, (float) mouseY, false);
                } else {
                    placeSelected((float) mouseX, (float) mouseY, true);
                }
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                recordUndo();
                erasing = true;
                eraseLastCell = null;
                eraseAt((float) mouseX, (float) mouseY, false);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        Layout l = layout();
        if (draggingActorId >= 0L && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            Actor actor = spriteEngine.actor(draggingActorId);
            if (actor != null) {
                float x = clamp((float) mouseX, l.canvasX + actor.halfWidth(), l.canvasRight - actor.halfWidth());
                float y = clamp((float) mouseY, l.canvasY + actor.halfHeight(), l.canvasBottom - actor.halfHeight());
                actor.body().teleport(x, y);
                actor.setVelocity(0.0F, 0.0F);
                dragReleaseVelocityX = clamp((float) (mouseX - dragLastX) * 10.0F, -220.0F, 220.0F);
                dragReleaseVelocityY = clamp((float) (mouseY - dragLastY) * 10.0F, -220.0F, 220.0F);
                dragLastX = mouseX;
                dragLastY = mouseY;
                return true;
            }
            draggingActorId = -1L;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && painting
                && !pointerOverOverlayPanel(l, mouseX, mouseY)
                && inside(mouseX, mouseY, l.canvasX, l.canvasY, l.canvasRight, l.canvasBottom)) {
            SceneCellPos cell = cellAt((float) mouseX, (float) mouseY);
            if (!cell.equals(paintLastCell)) placeSelected((float) mouseX, (float) mouseY, false);
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && erasing
                && !pointerOverOverlayPanel(l, mouseX, mouseY)
                && inside(mouseX, mouseY, l.canvasX, l.canvasY, l.canvasRight, l.canvasBottom)) {
            SceneCellPos cell = cellAt((float) mouseX, (float) mouseY);
            if (!cell.equals(eraseLastCell)) eraseAt((float) mouseX, (float) mouseY, false);
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && panning
                && inside(mouseX, mouseY, l.canvasX, l.canvasY, l.canvasRight, l.canvasBottom)) {
            if (!panMoved && (Math.abs(mouseX - panStartX) > 1.5D || Math.abs(mouseY - panStartY) > 1.5D)) {
                recordUndo();
                panMoved = true;
            }
            if (panMoved) translateComposition((float) deltaX, (float) deltaY);
            dragLastX = mouseX;
            dragLastY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && painting) {
            painting = false;
            paintLastCell = null;
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && erasing) {
            erasing = false;
            eraseLastCell = null;
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && panning) {
            boolean moved = panMoved;
            panning = false;
            panMoved = false;
            if (!moved) pickAt((float) mouseX, (float) mouseY);
            return true;
        }
        if (draggingActorId >= 0L && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            Actor actor = spriteEngine.actor(draggingActorId);
            if (actor != null) {
                actor.setKinematic(dragOriginalKinematic);
                if (dragOriginalKinematic) actor.setVelocity(0.0F, 0.0F);
                else actor.setVelocity(dragReleaseVelocityX, dragReleaseVelocityY);
            }
            draggingActorId = -1L;
            dragReleaseVelocityX = 0.0F;
            dragReleaseVelocityY = 0.0F;
            dragOriginalKinematic = false;
            return true;
        }
        if (itemUseHeld) {
            itemUseHeld = false;
            if (selectedActorId >= 0L) spriteEngine.releaseItemUse(selectedActorId, (float) mouseX, (float) mouseY);
            flushScene();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        Layout l = layout();
        if (!generationPanelOpen && inside(mouseX, mouseY, l.rightX + 5, l.inspectorTop(), l.rightX + l.rightWidth - 5, l.inspectorEditorY())) {
            List<InspectorRow> rows = inspectorRows();
            int visible = Math.max(1, (l.inspectorEditorY() - 4 - (l.inspectorTop() + 31)) / 13);
            inspectorScroll = Math.max(0, Math.min(Math.max(0, rows.size() - visible),
                    inspectorScroll + (amount < 0.0D ? 3 : -3)));
            return true;
        }
        if (inside(mouseX, mouseY, l.rightX, l.catalogTop(), l.rightX + l.rightWidth, l.inspectorTop())) {
            int visible = Math.max(1, (l.inspectorTop() - 4 - l.catalogTop() - 4) / ROW_HEIGHT);
            if (generationPanelOpen) {
                generationScroll = Math.max(0, Math.min(Math.max(0, filteredGeneration.size() - visible),
                        generationScroll + (amount < 0.0D ? 3 : -3)));
            } else {
                catalogScroll = Math.max(0, Math.min(Math.max(0, filteredCatalog.size() - visible),
                        catalogScroll + (amount < 0.0D ? 3 : -3)));
            }
            return true;
        }
        if (pointerOverOverlayPanel(l, mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, amount);
        if (!inside(mouseX, mouseY, l.canvasX, l.canvasY, l.canvasRight, l.canvasBottom)) {
            return super.mouseScrolled(mouseX, mouseY, amount);
        }
        int steps = amount > 0.0D ? 1 : -1;
        if (hasShiftDown()) {
            changeDepth(steps);
            return true;
        }
        if (tool == Tool.PLACE) {
            CatalogEntry entry = selectedCatalogEntry();
            if (entry != null && entry.kind == CatalogKind.BLOCKS && Registries.BLOCK.containsId(entry.id)) {
                ensurePlacementState(entry);
                BlockState next = BlockOrientationSystem.cycleState(placementBlockState, steps);
                if (next != null && !next.equals(placementBlockState)) {
                    placementBlockState = next;
                    setStatus("Placement ghost rotated: " + compactState(next));
                } else {
                    setStatus("Selected block has no additional placement orientation");
                }
                return true;
            }
        }
        SceneCellPos hover = blockAtEditableDepth((float) mouseX, (float) mouseY);
        if (hover != null) {
            if (!hover.equals(selectedCell) || selectedActorId >= 0L) clearInspectorSelection();
            selectedCell = hover;
            selectedActorId = -1L;
            recordUndo();
            if (spriteEngine.cycleBlockOrientation(hover, steps)) {
                flushScene();
                setStatus("Rotated " + Registries.BLOCK.getId(spriteEngine.blockState(hover).getBlock()));
            }
            return true;
        }
        if (selectedActorId >= 0L) {
            recordUndo();
            spriteEngine.rotateItem(selectedActorId, steps * 15.0F);
            return true;
        }
        changeDepth(steps);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (hasControlDown()) {
            if (keyCode == GLFW.GLFW_KEY_S) { saveScene(); return true; }
            if (keyCode == GLFW.GLFW_KEY_L || keyCode == GLFW.GLFW_KEY_O) { loadScene(); return true; }
            if (keyCode == GLFW.GLFW_KEY_Z) { undo(); return true; }
            if (keyCode == GLFW.GLFW_KEY_Y) { redo(); return true; }
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_1 -> setTool(Tool.MOUSE);
            case GLFW.GLFW_KEY_2 -> setTool(Tool.PLACE);
            case GLFW.GLFW_KEY_R -> rotateSelection(hasShiftDown() ? -1 : 1);
            case GLFW.GLFW_KEY_F -> promoteSelection();
            case GLFW.GLFW_KEY_G -> showGrid = !showGrid;
            case GLFW.GLFW_KEY_P -> cycleProjection();
            case GLFW.GLFW_KEY_LEFT_BRACKET -> changeDepth(-1);
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> changeDepth(1);
            case GLFW.GLFW_KEY_SPACE -> toggleRunning();
            case GLFW.GLFW_KEY_T -> runDetachedSelfTest();
            case GLFW.GLFW_KEY_C -> setStatus("Coverage: " + spriteEngine.gameplayCoverageSummary());
            case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> deleteSelection();
            default -> { return false; }
        }
        return true;
    }

    private void placeSelected(float x, float y, boolean recordHistory) {
        CatalogEntry entry = selectedCatalogEntry();
        if (entry == null) return;
        SceneCellPos cell = cellAt(x, y);
        if ((entry.kind == CatalogKind.BLOCKS || entry.kind == CatalogKind.FLUIDS) && cell.equals(paintLastCell)) return;
        if (recordHistory && entry.kind != CatalogKind.PARTICLES) recordUndo();
        switch (entry.kind) {
            case BLOCKS -> {
                if (!Registries.BLOCK.containsId(entry.id)) return;
                Block block = Registries.BLOCK.get(entry.id);
                ensurePlacementState(entry);
                BlockState state = placementBlockState != null && entry.id.equals(placementStateId)
                        ? placementBlockState : block.getDefaultState();
                spriteEngine.setBlock(cell, state);
                flushScene();
                playBlockEditSound(state, true);
                selectedCell = cell;
                selectedActorId = -1L;
                paintLastCell = cell;
                setStatus("Placed " + entry.id);
            }
            case ITEMS -> {
                if (!Registries.ITEM.containsId(entry.id)) return;
                Item item = Registries.ITEM.get(entry.id);
                long id = spriteEngine.spawnItem(new ItemStack(item), x, y, currentDepth, 0.0F, 0.0F);
                flushScene();
                selectedActorId = id;
                selectedCell = null;
                setStatus("Spawned " + entry.id);
            }
            case FLUIDS -> {
                if (!Registries.FLUID.containsId(entry.id)) return;
                Fluid fluid = Registries.FLUID.get(entry.id);
                spriteEngine.setFluid(cell, fluid);
                flushScene();
                selectedCell = cell;
                selectedActorId = -1L;
                paintLastCell = cell;
                setStatus("Placed fluid " + entry.id);
            }
            case PARTICLES -> {
                boolean ok = engine.triggerAt(entry.runtimeId, x - 10.0F, y - 10.0F, 20.0F, 20.0F,
                        1.0F, UiParticleEngine.SpawnOverrides.EMPTY, false);
                if (ok) setStatus("Triggered " + entry.runtimeId);
                else setStatus("Particle trigger rejected: " + entry.runtimeId);
            }
        }
    }

    private void eraseAt(float x, float y, boolean recordHistory) {
        long actor = actorAt(x, y);
        if (actor >= 0L) {
            if (recordHistory) recordUndo();
            spriteEngine.scene().actors().remove(actor);
            if (selectedActorId == actor) selectedActorId = -1L;
            setStatus("Removed actor " + actor);
            return;
        }
        SceneCellPos block = blockAtEditableDepth(x, y);
        SceneCellPos fluidCell = block == null ? fluidAtEditableDepth(x, y) : null;
        SceneCellPos cell = block != null ? block : (fluidCell != null ? fluidCell : cellAt(x, y));
        BlockState state = spriteEngine.scene().blocks().getBlockState(cell);
        FluidState fluid = spriteEngine.scene().fluids().getFluidState(cell);
        if ((state == null || state.isAir()) && (fluid == null || fluid.isEmpty())) return;
        if (recordHistory) recordUndo();
        if (state != null && !state.isAir()) spriteEngine.removeBlock(cell);
        if (fluid != null && !fluid.isEmpty()) spriteEngine.scene().fluids().remove(cell);
        flushScene();
        if (state != null && !state.isAir()) playBlockEditSound(state, false);
        if (cell.equals(selectedCell)) selectedCell = null;
        eraseLastCell = cell;
        setStatus("Erased cell " + cell.x() + "," + cell.y() + "," + cell.depth());
    }

    private void selectAt(float x, float y) {
        clearInspectorSelection();
        long actor = actorAt(x, y);
        if (actor >= 0L) {
            selectedActorId = actor;
            selectedCell = null;
            Actor selectedActor = spriteEngine.actor(actor);
            if (selectedActor != null) currentDepth = selectedActor.depth();
            return;
        }
        SceneCellPos block = blockAtEditableDepth(x, y);
        if (block != null) {
            selectedCell = block;
            selectedActorId = -1L;
            currentDepth = spriteEngine.scene().projection().depthCoordinate(block);
            return;
        }
        SceneCellPos cell = fluidAtEditableDepth(x, y);
        if (cell != null) {
            selectedCell = cell;
            selectedActorId = -1L;
            currentDepth = spriteEngine.scene().projection().depthCoordinate(cell);
            return;
        }
        selectedCell = null;
        selectedActorId = -1L;
    }

    private void useAt(float x, float y) {
        SceneCellPos block = blockAtEditableDepth(x, y);
        SceneCellPos cell = block != null ? block : cellAt(x, y);
        if (selectedActorId >= 0L && spriteEngine.actor(selectedActorId) instanceof ItemActor) {
            recordUndo();
            boolean handled = block != null && spriteEngine.useItemOnBlock(selectedActorId, block);
            if (!handled && block == null) handled = spriteEngine.useItemAtCell(selectedActorId, cell);
            if (!handled) {
                handled = spriteEngine.beginItemUse(selectedActorId, x, y);
                itemUseHeld = handled;
            }
            flushScene();
            setStatus(handled ? "Item interaction accepted" : "Item has no matching interaction here");
            return;
        }
        if (block != null) {
            recordUndo();
            boolean handled = spriteEngine.useBlock(block, x, y);
            flushScene();
            selectedCell = block;
            setStatus(handled ? "Block interaction accepted" : "Block has no specialized use action");
        }
    }

    private void pickAt(float x, float y) {
        clearInspectorSelection();
        long actorId = actorAt(x, y);
        if (actorId >= 0L && spriteEngine.actor(actorId) instanceof ItemActor itemActor) {
            Identifier id = Registries.ITEM.getId(itemActor.stack().getItem());
            selectCatalogId(CatalogKind.ITEMS, id);
            selectedActorId = actorId;
            selectedCell = null;
            setStatus("Picked " + id);
            return;
        }
        SceneCellPos blockPos = blockAtEditableDepth(x, y);
        if (blockPos != null) {
            BlockState state = spriteEngine.scene().blocks().getBlockState(blockPos);
            if (state != null && !state.isAir()) {
                Identifier id = Registries.BLOCK.getId(state.getBlock());
                selectCatalogId(CatalogKind.BLOCKS, id);
                selectedCell = blockPos;
                selectedActorId = -1L;
                setStatus("Picked " + id);
                return;
            }
        }
        SceneCellPos cell = fluidAtEditableDepth(x, y);
        FluidState fluid = cell == null ? null : spriteEngine.scene().fluids().getFluidState(cell);
        if (cell != null && fluid != null && !fluid.isEmpty()) {
            Identifier id = Registries.FLUID.getId(fluid.getFluid());
            selectCatalogId(CatalogKind.FLUIDS, id);
            selectedCell = cell;
            selectedActorId = -1L;
            setStatus("Picked " + id);
            return;
        }
        setStatus("Nothing to pick here");
    }

    private void selectCatalogId(CatalogKind kind, Identifier id) {
        if (kind == null || id == null) return;
        catalogKind = kind;
        if (searchField != null && !searchField.getText().isEmpty()) searchField.setText("");
        applyCatalogFilter();
        for (int i = 0; i < filteredCatalog.size(); i++) {
            if (filteredCatalog.get(i).id.equals(id)) {
                selectedCatalogIndex = i;
                catalogScroll = Math.max(0, i - 2);
                break;
            }
        }
        resetPlacementState();
        setTool(Tool.PLACE);
        updateButtonStates();
    }

    private void rotateSelection(int direction) {
        if (selectedActorId >= 0L) {
            recordUndo();
            if (spriteEngine.rotateItem(selectedActorId, direction * 15.0F)) setStatus("Rotated item actor");
            return;
        }
        if (selectedCell == null) return;
        recordUndo();
        if (spriteEngine.cycleBlockOrientation(selectedCell, direction)) {
            flushScene();
            setStatus("Rotated block state");
        } else {
            setStatus("Selected block has no supported orientation property");
        }
    }

    private void promoteSelection() {
        if (selectedCell == null) return;
        BlockState state = spriteEngine.scene().blocks().getBlockState(selectedCell);
        if (state == null || state.isAir()) return;
        recordUndo();
        long id = spriteEngine.promoteBlockToFallingActor(selectedCell, 0.0F, 0.0F);
        flushScene();
        selectedActorId = id;
        selectedCell = null;
        setStatus("Promoted block to falling actor");
    }

    private void deleteSelection() {
        if (selectedActorId >= 0L) {
            recordUndo();
            spriteEngine.scene().actors().remove(selectedActorId);
            selectedActorId = -1L;
            return;
        }
        if (selectedCell != null) {
            recordUndo();
            BlockState removedState = spriteEngine.scene().blocks().getBlockState(selectedCell);
            spriteEngine.scene().blocks().remove(selectedCell);
            spriteEngine.scene().fluids().remove(selectedCell);
            flushScene();
            if (removedState != null && !removedState.isAir()) playBlockEditSound(removedState, false);
            selectedCell = null;
        }
    }

    private boolean handleInspectorClick(Layout l, double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return false;
        int top = l.inspectorTop() + 31;
        int bottom = l.inspectorEditorY() - 4;
        if (!inside(mouseX, mouseY, l.rightX + 7, top, l.rightX + l.rightWidth - 7, bottom)) return false;
        List<InspectorRow> rows = inspectorRows();
        int visibleIndex = ((int) mouseY - top) / 13;
        int index = inspectorScroll + visibleIndex;
        if (index < 0 || index >= rows.size()) return false;
        InspectorRow row = rows.get(index);
        if (row.kind == InspectorKind.SECTION) return true;
        inspectorSelectedKey = row.key;
        if (inspectorValueField != null) {
            inspectorValueField.setText(row.kind == InspectorKind.CUSTOM ? "" : row.value);
            inspectorValueField.active = row.editable;
        }
        if (inspectorApplyButton != null) inspectorApplyButton.active = row.editable;

        // Right click is the quick-edit path for enumerable/boolean values. Exact
        // numeric/string editing remains available in the value box below.
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && row.editable) {
            if (row.kind == InspectorKind.BLOCK_STATE && row.property != null) {
                BlockState state = spriteEngine.scene().blocks().getBlockState(selectedCell);
                BlockState next = cycleProperty(state, row.property, -1);
                if (next != null && !next.equals(state)) {
                    recordUndo();
                    SceneCellPos anchor = spriteEngine.scene().multipartBlocks().anchor(selectedCell);
                    if (anchor == null) anchor = selectedCell;
                    spriteEngine.scene().blocks().updateState(anchor, next);
                    flushScene();
                    selectedCell = anchor;
                    inspectorValueField.setText(propertyValue(next, row.property));
                }
                return true;
            }
            if ("true".equalsIgnoreCase(row.value) || "false".equalsIgnoreCase(row.value)) {
                inspectorValueField.setText(Boolean.toString(!Boolean.parseBoolean(row.value)));
                applyInspectorValue();
                return true;
            }
        }
        return true;
    }

    private void clearInspectorSelection() {
        inspectorScroll = 0;
        inspectorSelectedKey = "";
        if (inspectorValueField != null) {
            inspectorValueField.setText("");
            inspectorValueField.active = false;
        }
        if (inspectorApplyButton != null) inspectorApplyButton.active = false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValue(BlockState state, Property<?> property) {
        if (state == null || property == null) return "";
        try {
            Property raw = property;
            Comparable value = state.get(raw);
            return value == null ? "" : raw.name(value);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValue(FluidState state, Property<?> property) {
        if (state == null || property == null) return "";
        try {
            Property raw = property;
            Comparable value = state.get(raw);
            return value == null ? "" : raw.name(value);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private void applyInspectorValue() {
        if (inspectorValueField == null || inspectorSelectedKey.isBlank()) return;
        InspectorRow row = null;
        for (InspectorRow candidate : inspectorRows()) {
            if (candidate.key.equals(inspectorSelectedKey)) { row = candidate; break; }
        }
        if (row == null || !row.editable) return;
        String value = inspectorValueField.getText() == null ? "" : inspectorValueField.getText().trim();
        if (applyInspectorRow(row, value)) {
            setStatus("Updated " + row.label.replace(" *", ""));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean applyInspectorRow(InspectorRow row, String value) {
        if (row == null || !row.editable) return false;
        try {
            switch (row.kind) {
                case BLOCK_STATE -> {
                    if (selectedCell == null || row.property == null) return false;
                    BlockState state = spriteEngine.scene().blocks().getBlockState(selectedCell);
                    Property property = row.property;
                    Optional parsed = property.parse(value);
                    if (parsed.isEmpty()) { setStatus("Invalid value for " + property.getName()); return false; }
                    BlockState next = state.with(property, (Comparable) parsed.get());
                    if (next.equals(state)) return true;
                    recordUndo();
                    SceneCellPos anchor = spriteEngine.scene().multipartBlocks().anchor(selectedCell);
                    if (anchor == null) anchor = selectedCell;
                    spriteEngine.scene().blocks().updateState(anchor, next);
                    flushScene();
                    selectedCell = anchor;
                    return true;
                }
                case FLUID_STATE -> {
                    if (selectedCell == null || row.property == null) return false;
                    FluidState state = spriteEngine.scene().fluids().getFluidState(selectedCell);
                    Property property = row.property;
                    Optional parsed = property.parse(value);
                    if (parsed.isEmpty()) { setStatus("Invalid fluid value for " + property.getName()); return false; }
                    FluidState next = state.with(property, (Comparable) parsed.get());
                    if (next.equals(state)) return true;
                    recordUndo();
                    spriteEngine.scene().fluids().set(selectedCell, next);
                    flushScene();
                    return true;
                }
                case RUNTIME -> {
                    if (selectedCell == null) return false;
                    String key = row.key.substring("runtime:".length());
                    recordUndo();
                    spriteEngine.scene().blocks().putRuntimeData(selectedCell, key, value.isBlank() ? null : value);
                    if ("lifetime".equals(key)) spriteEngine.scene().blocks().restartLifetime(selectedCell);
                    return true;
                }
                case FLAGS -> {
                    if (selectedCell == null) return false;
                    int flags = Integer.decode(value);
                    recordUndo();
                    spriteEngine.scene().blocks().setFlags(selectedCell, flags);
                    return true;
                }
                case BLOCK_ENTITY -> {
                    if (selectedCell == null) return false;
                    String key = row.key.substring("be:".length());
                    recordUndo();
                    spriteEngine.scene().blocks().putBlockEntityData(selectedCell, key, value.isBlank() ? null : value);
                    return true;
                }
                case CUSTOM -> {
                    if (selectedCell == null) return false;
                    int equals = value.indexOf('=');
                    if (equals <= 0) { setStatus("Custom data uses key=value or be.key=value"); return false; }
                    String key = value.substring(0, equals).trim();
                    String dataValue = value.substring(equals + 1).trim();
                    if (key.isBlank()) return false;
                    recordUndo();
                    if (key.startsWith("be.")) {
                        String blockEntityKey = key.substring(3);
                        spriteEngine.scene().blocks().putBlockEntityData(selectedCell, blockEntityKey, dataValue.isBlank() ? null : dataValue);
                        inspectorSelectedKey = "be:" + blockEntityKey;
                    } else {
                        spriteEngine.scene().blocks().putRuntimeData(selectedCell, key, dataValue.isBlank() ? null : dataValue);
                        inspectorSelectedKey = "runtime:" + key;
                    }
                    return true;
                }
                case ACTOR -> {
                    Actor actor = selectedActorId < 0L ? null : spriteEngine.actor(selectedActorId);
                    if (actor == null) return false;
                    String key = row.key.substring("actor:".length());
                    recordUndo();
                    PhysicsBody2D body = actor.body();
                    switch (key) {
                        case "x" -> body.teleport(parseFloat(value), actor.y());
                        case "y" -> body.teleport(actor.x(), parseFloat(value));
                        case "depth" -> actor.setDepth(Integer.parseInt(value));
                        case "velocity_x" -> actor.setVelocity(parseFloat(value), actor.velocityY());
                        case "velocity_y" -> actor.setVelocity(actor.velocityX(), parseFloat(value));
                        case "rotation" -> actor.setRotation(parseFloat(value));
                        case "angular_velocity" -> actor.setAngularVelocity(parseFloat(value));
                        case "width" -> actor.setBodySize(Math.max(0.5F, parseFloat(value)), actor.halfHeight() * 2.0F);
                        case "height" -> actor.setBodySize(actor.halfWidth() * 2.0F, Math.max(0.5F, parseFloat(value)));
                        case "mass" -> actor.setMass(parseFloat(value));
                        case "gravity" -> actor.setGravity(parseFloat(value));
                        case "drag" -> actor.setDrag(parseFloat(value));
                        case "angular_drag" -> body.setAngularDampingPerMinecraftTick(parseFloat(value));
                        case "restitution" -> actor.setRestitution(parseFloat(value));
                        case "surface_friction" -> actor.setSurfaceFriction(parseFloat(value));
                        case "kinematic" -> actor.setKinematic(parseBoolean(value));
                        case "collide_world" -> body.setCollideWorld(parseBoolean(value));
                        case "collide_actors" -> body.setCollideActors(parseBoolean(value));
                        case "collide_bounds" -> body.setCollideSceneBounds(parseBoolean(value));
                        case "can_sleep" -> body.setCanSleep(parseBoolean(value));
                        case "stack_count" -> {
                            if (!(actor instanceof ItemActor item)) return false;
                            item.stack().setCount(Math.max(1, Math.min(item.stack().getMaxCount(), Integer.parseInt(value))));
                        }
                        default -> { return false; }
                    }
                    return true;
                }
                case READ_ONLY, SECTION -> { return false; }
            }
        } catch (RuntimeException exception) {
            setStatus("Invalid value: " + exception.getClass().getSimpleName());
            return false;
        }
        return false;
    }

    private static float parseFloat(String value) {
        float parsed = Float.parseFloat(value);
        if (!Float.isFinite(parsed)) throw new NumberFormatException("non-finite");
        return parsed;
    }

    private static boolean parseBoolean(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> throw new IllegalArgumentException("boolean");
        };
    }

    private void rebuildCatalog() {
        catalog.clear();
        for (Block block : Registries.BLOCK) {
            if (block == null || block == Blocks.AIR) continue;
            Identifier id = Registries.BLOCK.getId(block);
            Item item = block.asItem();
            ItemStack icon = item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
            catalog.add(new CatalogEntry(CatalogKind.BLOCKS, id, id.toString(), block.getName().getString(), icon));
        }
        for (Item item : Registries.ITEM) {
            if (item == null || item == Items.AIR) continue;
            Identifier id = Registries.ITEM.getId(item);
            catalog.add(new CatalogEntry(CatalogKind.ITEMS, id, id.toString(), item.getName().getString(), new ItemStack(item)));
        }
        for (Fluid fluid : Registries.FLUID) {
            if (fluid == null || fluid == Fluids.EMPTY || fluid.getDefaultState().isEmpty()) continue;
            Identifier id = Registries.FLUID.getId(fluid);
            Item bucket = fluid.getBucketItem();
            ItemStack icon = bucket == null || bucket == Items.AIR ? ItemStack.EMPTY : new ItemStack(bucket);
            catalog.add(new CatalogEntry(CatalogKind.FLUIDS, id, id.toString(), id.toString(), icon));
        }
        for (String idText : UiParticleRegistry.ids()) {
            // Block/item registry mirrors are placement capabilities, not particles.
            // They already appear in the dedicated block/item catalogs above.
            if (GameSpriteRegistryBridge.containsId(idText)) continue;
            Identifier id = particleIdentifier(idText);
            if (id != null) catalog.add(new CatalogEntry(CatalogKind.PARTICLES, id, idText, idText, ItemStack.EMPTY));
        }
        catalog.sort(Comparator.comparing((CatalogEntry entry) -> entry.kind.ordinal()).thenComparing(entry -> entry.id.toString()));
        applyCatalogFilter();
    }

    private void applyCatalogFilter() {
        filteredCatalog.clear();
        String query = (searchField == null ? searchQuery : searchField.getText()).strip().toLowerCase(Locale.ROOT);
        for (CatalogEntry entry : catalog) {
            if (entry.kind != catalogKind) continue;
            if (!query.isEmpty() && !entry.id.toString().toLowerCase(Locale.ROOT).contains(query)
                    && !entry.label.toLowerCase(Locale.ROOT).contains(query)) continue;
            filteredCatalog.add(entry);
        }
        selectedCatalogIndex = Math.max(0, Math.min(selectedCatalogIndex, Math.max(0, filteredCatalog.size() - 1)));
        catalogScroll = Math.max(0, Math.min(catalogScroll, selectedCatalogIndex));
    }

    private CatalogEntry selectedCatalogEntry() {
        if (filteredCatalog.isEmpty()) return null;
        selectedCatalogIndex = Math.max(0, Math.min(selectedCatalogIndex, filteredCatalog.size() - 1));
        return filteredCatalog.get(selectedCatalogIndex);
    }

    private void setTool(Tool next) {
        tool = next == null ? Tool.MOUSE : next;
        updateButtonStates();
    }

    private void setCatalogKind(CatalogKind next) {
        catalogKind = next == null ? CatalogKind.BLOCKS : next;
        selectedCatalogIndex = 0;
        catalogScroll = 0;
        applyCatalogFilter();
        resetPlacementState();
        setTool(Tool.PLACE);
        updateButtonStates();
    }

    private void updateButtonStates() {
        for (Tool value : Tool.values()) {
            ButtonWidget button = toolButtons.get(value);
            if (button != null) button.active = value != tool;
        }
        for (CatalogKind value : CatalogKind.values()) {
            ButtonWidget button = catalogButtons.get(value);
            if (button != null) button.active = !generationPanelOpen && value != catalogKind;
        }
        if (playButton != null) playButton.setMessage(Text.literal(running ? "Pause" : "Play"));
        if (projectionButton != null) projectionButton.setMessage(Text.literal("Projection: " + shortProjection()));
        if (terrainWidthButton != null) terrainWidthButton.setMessage(Text.literal("Width " + terrainWidth));
        if (terrainReplaceButton != null) terrainReplaceButton.setMessage(Text.literal(terrainReplaceExisting ? "Replace On" : "Replace Off"));
        if (terrainLayerPresetButton != null) terrainLayerPresetButton.setMessage(Text.literal(layerPresetLabel()));
        if (terrainGenerateButton != null) {
            SpriteWorldgenCatalog.Entry selectedWorldgen = selectedGenerationEntry();
            terrainGenerateButton.setMessage(Text.literal(selectedWorldgen != null && selectedWorldgen.kind() == SpriteWorldgenCatalog.Kind.BIOME
                    ? "Generate Full Biome" : "Generate Selected"));
        }
        if (placementGhostButton != null) placementGhostButton.setMessage(Text.literal(showPlacementGhost ? "Ghost On" : "Ghost Off"));
        if (layerLinkButton != null) {
            boolean linked = spriteEngine.scene().projection().crossLayerInteractions();
            layerLinkButton.setMessage(Text.literal(linked ? "Layer Link: On" : "Layer Link: Off"));
            layerLinkButton.active = true;
        }
        if (generationToggleButton != null) generationToggleButton.setMessage(Text.literal(generationPanelOpen ? "Close Worldgen" : "Worldgen Browser"));
        if (generationKindButton != null) generationKindButton.setMessage(Text.literal(generationAllKinds ? "All Worldgen" : generationKind.label()));
        updateGenerationWidgetVisibility();
    }


    private void toggleCrossLayerInteractions() {
        boolean enabled = !spriteEngine.scene().projection().crossLayerInteractions();
        spriteEngine.scene().projection().setCrossLayerInteractions(enabled);
        flushScene();
        updateButtonStates();
        setStatus(enabled
                ? "Cross-layer logic enabled: hidden depth slices may now affect one another"
                : "Layer logic isolated: blocks, fluids, physics, lighting, and neighbors stay on their own slice");
    }

    private void cycleTerrainLayerPreset() {
        terrainLayerPresetIndex = (terrainLayerPresetIndex + 1) % 5;
        if (terrainLayersField == null) return;
        String expression = layerExpressionForPreset(terrainLayerPresetIndex, currentDepth);
        terrainLayersField.setText(expression);
        updateButtonStates();
        setStatus("Generation layers " + expression + " (ONLY those depth slices generate; gaps stay empty)");
    }

    private static String layerExpressionForPreset(int preset, int depth) {
        /*
         * These presets are literal depth coordinates, not layer counts.
         * Sparse positive Z samples give Koil a much better-looking set of
         * cross-sections than densely generating every depth between 0 and N.
         * The field remains fully editable, so users can author arbitrary
         * combinations such as "5,11,23,47,95".
         */
        return switch (preset) {
            case 1 -> "4,8,16,32";
            case 2 -> "8,16,32,64";
            case 3 -> "6,14,30,62";
            case 4 -> "8,24,48,80";
            default -> "0";
        };
    }

    private String layerPresetLabel() {
        return switch (terrainLayerPresetIndex) {
            case 1 -> "Depths: 4/8/16/32";
            case 2 -> "Depths: 8/16/32/64";
            case 3 -> "Depths: 6/14/30/62";
            case 4 -> "Depths: 8/24/48/80";
            default -> "Depths: 0";
        };
    }

    private void playBlockEditSound(BlockState state, boolean placing) {
        if (state == null || state.isAir()) return;
        BlockSoundGroup group = state.getSoundGroup();
        if (group == null) return;
        var sound = placing ? group.getPlaceSound() : group.getBreakSound();
        if (sound == null) return;
        float volume = Math.max(0.08F, group.getVolume()) * (placing ? 0.62F : 0.78F);
        float pitch = Math.max(0.25F, group.getPitch());
        spriteEngine.scene().events().publish(new SceneEvent.SoundRequested(
                sound, volume, pitch, spriteEngine.scene().clock().gameTick()));
    }

    private void focusGeneratedScene(SceneCellPos focus, SpriteWorldgenCatalog.Entry entry) {
        if (focus == null) return;
        currentDepth = spriteEngine.scene().projection().depthCoordinate(focus);
        Layout l = layout();
        float targetX = (l.canvasX + l.canvasRight) * 0.5F;
        boolean caveLike = entry != null && entry.id() != null && (entry.id().getPath().contains("cave")
                || entry.id().getPath().contains("deep_dark") || entry.id().getPath().contains("dripstone"));
        float targetY = caveLike ? (l.canvasY + l.canvasBottom) * 0.5F : l.canvasY + l.canvasHeight() * 0.62F;
        float focusX = spriteEngine.scene().projection().cellCenterScreenX(focus);
        float focusY = spriteEngine.scene().projection().cellCenterScreenY(focus);
        translateComposition(targetX - focusX, targetY - focusY);
        viewInitialized = true;
    }

    private void cycleTerrainWidth() {
        int[] values = {32, 48, 72, 96, 128, 160, 224, 320};
        int index = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == terrainWidth) { index = i; break; }
        terrainWidth = values[(index + 1) % values.length];
        updateButtonStates();
    }

    private void toggleTerrainReplace() {
        terrainReplaceExisting = !terrainReplaceExisting;
        updateButtonStates();
        setStatus(terrainReplaceExisting ? "Generation may replace occupied cells" : "Generation preserves occupied cells");
    }

    private void togglePlacementGhost() {
        showPlacementGhost = !showPlacementGhost;
        updateButtonStates();
        setStatus(showPlacementGhost ? "Placement ghost enabled" : "Placement ghost disabled");
    }

    private void toggleGenerationPanel() {
        generationPanelOpen = !generationPanelOpen;
        if (generationPanelOpen) ensureGenerationCatalog();
        updateGenerationWidgetVisibility();
        updateButtonStates();
        setStatus(generationPanelOpen ? "Worldgen browser opened" : "Registry / inspector restored");
    }

    private void ensureGenerationCatalog() {
        if (!generationCatalog.isEmpty()) { applyGenerationFilter(); return; }
        generationCatalog.clear();
        generationCatalog.addAll(SpriteWorldgenCatalog.entries());
        applyGenerationFilter();
    }

    private void reloadGenerationCatalog() {
        generationCatalog.clear();
        generationCatalog.addAll(SpriteWorldgenCatalog.reload());
        generationScroll = 0;
        selectedGenerationIndex = 0;
        applyGenerationFilter();
        setStatus("Reloaded " + generationCatalog.size() + " Minecraft/mod worldgen resources");
    }

    private void cycleGenerationKind() {
        SpriteWorldgenCatalog.Kind[] values = SpriteWorldgenCatalog.Kind.values();
        if (generationAllKinds) {
            generationAllKinds = false;
            generationKind = values[0];
        } else if (generationKind.ordinal() >= values.length - 1) {
            generationAllKinds = true;
        } else {
            generationKind = values[generationKind.ordinal() + 1];
        }
        generationScroll = 0;
        selectedGenerationIndex = 0;
        applyGenerationFilter();
        updateButtonStates();
        setStatus("Worldgen category: " + (generationAllKinds ? "All" : generationKind.label()));
    }

    private void applyGenerationFilter() {
        filteredGeneration.clear();
        String query = generationSearchField == null ? generationQuery : generationSearchField.getText();
        query = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        for (SpriteWorldgenCatalog.Entry entry : generationCatalog) {
            if (!generationAllKinds && entry.kind() != generationKind) continue;
            if (!query.isEmpty() && !entry.searchable().contains(query)) continue;
            filteredGeneration.add(entry);
        }
        selectedGenerationIndex = Math.max(0, Math.min(selectedGenerationIndex, Math.max(0, filteredGeneration.size() - 1)));
        generationScroll = Math.max(0, Math.min(generationScroll, selectedGenerationIndex));
    }

    private SpriteWorldgenCatalog.Entry selectedGenerationEntry() {
        if (filteredGeneration.isEmpty()) return null;
        selectedGenerationIndex = Math.max(0, Math.min(selectedGenerationIndex, filteredGeneration.size() - 1));
        return filteredGeneration.get(selectedGenerationIndex);
    }

    private void generateSelectedWorldgen() {
        ensureGenerationCatalog();
        SpriteWorldgenCatalog.Entry entry = selectedGenerationEntry();
        if (entry == null) {
            generationPanelOpen = true;
            updateButtonStates();
            setStatus("Choose a biome, feature, structure, template, dimension, or terrain resource first");
            return;
        }
        Layout l = layout();
        String layerText = terrainLayersField == null ? String.valueOf(currentDepth) : terrainLayersField.getText();
        boolean structureVolume = isStructureVolumeKind(entry.kind());
        List<Integer> layers = structureVolume
                ? List.of(currentDepth)
                : SpriteTerrainGenerator.parseLayers(layerText, currentDepth);
        if (terrainLayersField != null && !structureVolume) {
            terrainLayersField.setText(SpriteTerrainGenerator.normalizeLayerExpression(layerText, currentDepth));
        }
        try {
            terrainSeed = terrainSeedField == null ? terrainSeed : Long.parseLong(terrainSeedField.getText().trim());
        } catch (RuntimeException ignored) {
            terrainSeed = terrainSeed * 6364136223846793005L + 1442695040888963407L;
            if (terrainSeedField != null) terrainSeedField.setText(Long.toString(terrainSeed));
        }
        int depth = structureVolume ? currentDepth
                : (layers.isEmpty() ? currentDepth : layers.get(layers.size() / 2));
        SceneCellPos fallbackOrigin = spriteEngine.scene().projection().screenToCellAtDepth(
                (l.canvasX + l.canvasRight) * 0.5F,
                l.canvasY + l.canvasHeight() * 0.64F, depth);
        terrainOriginX = parseCoordinateField(terrainOriginXField, fallbackOrigin.x());
        terrainOriginY = parseCoordinateField(terrainOriginYField, fallbackOrigin.y());
        if (terrainOriginXField != null) terrainOriginXField.setText(Integer.toString(terrainOriginX));
        if (terrainOriginYField != null) terrainOriginYField.setText(Integer.toString(terrainOriginY));
        int structureAnchorDepth = currentDepth;
        // The active edit plane is authoritative for structure anchoring even
        // when biome terrain samples sparse source-Z slices such as 8,16,32,64.
        // Structures are full native volumes and must never inherit the terrain
        // slicing preset as their geometry/depth model.
        int generationEditPlane = currentDepth;
        // A structure template is an authored volume, including its interior air.
        // Place it coherently rather than silently dropping every block that
        // overlaps terrain. Terrain/biome generation still honors the editor's
        // Replace toggle.
        boolean generationReplace = structureVolume || terrainReplaceExisting;
        recordUndo();
        SpriteWorldgenGenerator.Result result = SpriteWorldgenGenerator.generate(spriteEngine,
                new SpriteWorldgenGenerator.Request(entry, terrainSeed, layers, terrainOriginX, terrainOriginY,
                        terrainWidth, generationReplace, generationEditPlane));
        flushScene();
        SceneCellPos focus = result.focus();
        if (focus != null) {
            focusGeneratedScene(focus, entry);
            if (structureVolume) selectEditDepth(structureAnchorDepth, false);
        }
        if (!structureVolume && layers.size() > 1 && focus == null) {
            selectEditDepth(layers.get(layers.size() / 2), false);
        }
        String depthStatus = structureVolume
                ? "structure plane Z=" + currentDepth + " (full native depth)"
                : "layers " + SpriteTerrainGenerator.normalizeLayerExpression(layerText, currentDepth);
        setStatus(result.message() + " | " + result.blocks() + " blocks, " + result.fluids() + " fluids | " + depthStatus);
    }

    private static boolean isStructureVolumeKind(SpriteWorldgenCatalog.Kind kind) {
        return kind == SpriteWorldgenCatalog.Kind.STRUCTURE
                || kind == SpriteWorldgenCatalog.Kind.STRUCTURE_SET
                || kind == SpriteWorldgenCatalog.Kind.STRUCTURE_TEMPLATE
                || kind == SpriteWorldgenCatalog.Kind.TEMPLATE_POOL;
    }

    private void updateGenerationWidgetVisibility() {
        if (searchField != null) { searchField.visible = !generationPanelOpen; searchField.active = !generationPanelOpen; }
        for (ButtonWidget button : catalogButtons.values()) if (button != null) {
            button.visible = !generationPanelOpen;
            button.active = !generationPanelOpen && button != catalogButtons.get(catalogKind);
        }
        if (generationSearchField != null) { generationSearchField.visible = generationPanelOpen; generationSearchField.active = generationPanelOpen; }
        if (generationKindButton != null) { generationKindButton.visible = generationPanelOpen; generationKindButton.active = generationPanelOpen; }
        if (generationRefreshButton != null) { generationRefreshButton.visible = generationPanelOpen; generationRefreshButton.active = generationPanelOpen; }
        if (inspectorValueField != null) { inspectorValueField.visible = !generationPanelOpen; if (generationPanelOpen) inspectorValueField.active = false; }
        if (inspectorApplyButton != null) { inspectorApplyButton.visible = !generationPanelOpen; if (generationPanelOpen) inspectorApplyButton.active = false; }
    }

    private int parseCoordinateField(TextFieldWidget field, int fallback) {
        if (field == null) return fallback;
        try { return Math.max(-4096, Math.min(4096, Integer.parseInt(field.getText().trim()))); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private void ensurePlacementState(CatalogEntry entry) {
        if (entry == null || entry.kind != CatalogKind.BLOCKS || !Registries.BLOCK.containsId(entry.id)) return;
        if (!entry.id.equals(placementStateId) || placementBlockState == null) {
            placementStateId = entry.id;
            placementBlockState = Registries.BLOCK.get(entry.id).getDefaultState();
        }
    }

    private void resetPlacementState() {
        placementStateId = null;
        placementBlockState = null;
    }

    private String compactState(BlockState state) {
        if (state == null) return "state";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
            String name = entry.getKey().getName();
            if (name.equals("facing") || name.equals("axis") || name.equals("half") || name.equals("hinge") || name.equals("type")) {
                parts.add(name + "=" + entry.getValue());
            }
        }
        return parts.isEmpty() ? Registries.BLOCK.getId(state.getBlock()).toString() : String.join(",", parts);
    }

    private void cycleProjection() {
        recordUndo();
        SceneProjection.Mode[] values = SceneProjection.Mode.values();
        SceneProjection.Mode current = spriteEngine.scene().projection().mode();
        SceneProjection.Mode next = values[(current.ordinal() + 1) % values.length];
        spriteEngine.scene().projection().setMode(next);
        updateButtonStates();
        setStatus("Projection " + next.name().toLowerCase(Locale.ROOT));
    }

    private void changeDepth(int amount) {
        selectEditDepth(currentDepth + amount);
    }

    private void selectEditDepth(int depth) {
        selectEditDepth(depth, true);
    }

    private void selectEditDepth(int depth, boolean announce) {
        int previousDepth = currentDepth;
        String previousPreset = layerExpressionForPreset(terrainLayerPresetIndex, previousDepth);
        currentDepth = Math.max(-4096, Math.min(4096, depth));
        if (terrainLayersField != null && terrainLayersField.getText().trim().equals(previousPreset)) {
            terrainLayersField.setText(layerExpressionForPreset(terrainLayerPresetIndex, currentDepth));
        }
        if (selectedCell != null && spriteEngine.scene().projection().depthCoordinate(selectedCell) != currentDepth) {
            selectedCell = null;
        }
        if (selectedActorId >= 0L) {
            Actor actor = spriteEngine.actor(selectedActorId);
            if (actor == null || actor.depth() != currentDepth) selectedActorId = -1L;
        }
        updateButtonStates();
        if (announce) setStatus("Edit layer Z=" + currentDepth + " | all layers remain visible");
    }

    private void centerView() {
        Layout l = layout();
        SceneProjection projection = spriteEngine.scene().projection();
        float targetX = (l.canvasX + l.canvasRight) * 0.5F;
        float targetY = (l.canvasY + l.canvasBottom) * 0.5F;
        translateComposition(targetX - projection.originX(), targetY - projection.originY());
        viewInitialized = true;
        setStatus("Scene origin centered");
    }

    /**
     * Moves the authored composition as one unit. Blocks are screen-projected from
     * SceneProjection while native actors store continuous screen coordinates, so
     * both sides must move together. Transient FX and LEGACY_PROXY actors are not
     * background document content and remain under their compatibility owner.
     */
    private void translateComposition(float deltaX, float deltaY) {
        if (!Float.isFinite(deltaX) || !Float.isFinite(deltaY)) return;
        if (Math.abs(deltaX) < 0.0001F && Math.abs(deltaY) < 0.0001F) return;
        spriteEngine.scene().projection().pan(deltaX, deltaY);
        for (Actor actor : spriteEngine.scene().actors().actors()) {
            if (actor == null || actor.removed() || actor.authority() != Actor.Authority.SCENE) continue;
            actor.body().teleport(actor.x() + deltaX, actor.y() + deltaY);
        }
    }

    private boolean sceneHasContent() {
        Scene scene = spriteEngine.scene();
        return !scene.blocks().entries().isEmpty() || !scene.fluids().entries().isEmpty() || !scene.actors().actors().isEmpty();
    }

    private void toggleRunning() {
        running = !running;
        updateButtonStates();
        setStatus(running ? "Simulation running" : "Simulation paused");
    }

    private void stepScene() {
        spriteEngine.setViewport(width, height);
        spriteEngine.advanceFrame(1.0F / 20.0F);
        setStatus("Advanced one Minecraft tick window");
    }

    private void runDetachedSelfTest() {
        com.spirit.koil.api.design.sprite.debug.SceneSelfTest.Report report = spriteEngine.runDetachedSelfTest();
        if (report.passed()) {
            setStatus("Detached engine self-test passed " + report.checks() + "/" + report.checks());
            return;
        }
        String first = report.failures().isEmpty() ? "unknown failure" : report.failures().get(0);
        setStatus("Self-test failed " + (report.checks() - report.failures().size()) + "/" + report.checks() + ": " + first);
    }

    private void clearScene() {
        recordUndo();
        engine.reset();
        selectedCell = null;
        selectedActorId = -1L;
        centerView();
        setStatus("Scene cleared");
    }

    private void saveScene() {
        flushScene();
        SpriteSceneStorage.Result result = SpriteSceneStorage.save(engine, sceneName());
        setStatus(result.message());
    }

    private void loadScene() {
        String before = snapshot();
        SpriteSceneStorage.Result result = SpriteSceneStorage.load(engine, sceneName());
        if (result.success()) {
            undo.addLast(before);
            trimHistory(undo);
            redo.clear();
            selectedCell = null;
            selectedActorId = -1L;
            viewInitialized = true;
        }
        setStatus(result.message());
    }

    private void recordUndo() {
        undo.addLast(snapshot());
        trimHistory(undo);
        redo.clear();
    }

    private void undo() {
        if (undo.isEmpty()) { setStatus("Nothing to undo"); return; }
        redo.addLast(snapshot());
        trimHistory(redo);
        restoreSnapshot(undo.removeLast());
        setStatus("Undo");
    }

    private void redo() {
        if (redo.isEmpty()) { setStatus("Nothing to redo"); return; }
        undo.addLast(snapshot());
        trimHistory(undo);
        restoreSnapshot(redo.removeLast());
        setStatus("Redo");
    }

    private String snapshot() {
        flushScene();
        return SpriteSceneStorage.capture(engine).toString();
    }

    private void restoreSnapshot(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            SpriteSceneStorage.restore(engine, root);
            selectedCell = null;
            selectedActorId = -1L;
            viewInitialized = true;
        } catch (RuntimeException exception) {
            setStatus("History restore failed: " + exception.getClass().getSimpleName());
        }
    }

    private void flushScene() {
        Scene scene = spriteEngine.scene();
        scene.commands().drain(scene);
        scene.multipartBlocks().reconcile();
        scene.neighborStates().reconcile();
        scene.chestSystem().reconcilePairs();
        scene.neighborStates().reconcile();
    }

    private SceneCellPos cellAt(float x, float y) {
        return spriteEngine.scene().projection().screenToCellAtDepth(x, y, currentDepth);
    }

    /**
     * Editing is intentionally constrained to the active Z layer. Other layers
     * remain visible for composition/context, but they are never silently chosen
     * as an edit target when the selected layer is empty.
     */
    private SceneCellPos blockAtEditableDepth(float x, float y) {
        SceneCellPos cell = cellAt(x, y);
        BlockState state = spriteEngine.scene().blocks().getBlockState(cell);
        return state != null && !state.isAir() ? cell : null;
    }

    private SceneCellPos fluidAtEditableDepth(float x, float y) {
        SceneCellPos cell = cellAt(x, y);
        FluidState state = spriteEngine.scene().fluids().getFluidState(cell);
        return state != null && !state.isEmpty() ? cell : null;
    }

    private long actorAt(float x, float y) {
        long best = -1L;
        float bestDistanceSq = Float.MAX_VALUE;
        for (Actor actor : spriteEngine.scene().actors().actors()) {
            if (actor == null || actor.removed() || actor.depth() != currentDepth) continue;
            if (x < actor.x() - actor.halfWidth() - 3.0F || x > actor.x() + actor.halfWidth() + 3.0F
                    || y < actor.y() - actor.halfHeight() - 3.0F || y > actor.y() + actor.halfHeight() + 3.0F) continue;
            float dx = x - actor.x();
            float dy = y - actor.y();
            float distanceSq = dx * dx + dy * dy;
            if (best < 0L || distanceSq < bestDistanceSq) {
                best = actor.id();
                bestDistanceSq = distanceSq;
            }
        }
        return best;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState cycleProperty(BlockState state, Property<?> property, int direction) {
        if (state == null || property == null || !state.contains(property)) return state;
        List values = new ArrayList(property.getValues());
        if (values.size() <= 1) return state;
        Comparable current = (Comparable) state.get((Property) property);
        int index = values.indexOf(current);
        if (index < 0) index = 0;
        Comparable next = (Comparable) values.get(floorMod(index + direction, values.size()));
        try { return state.with((Property) property, next); }
        catch (RuntimeException ignored) { return state; }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static FluidState cycleProperty(FluidState state, Property<?> property, int direction) {
        if (state == null || property == null || !state.contains(property)) return state;
        List values = new ArrayList(property.getValues());
        if (values.size() <= 1) return state;
        Comparable current = (Comparable) state.get((Property) property);
        int index = values.indexOf(current);
        if (index < 0) index = 0;
        Comparable next = (Comparable) values.get(floorMod(index + direction, values.size()));
        try { return state.with((Property) property, next); }
        catch (RuntimeException ignored) { return state; }
    }

    private static List<Property<?>> sortedProperties(BlockState state) {
        List<Property<?>> result = new ArrayList<>(state.getProperties());
        result.sort(Comparator.comparing(Property::getName));
        return result;
    }

    private static List<Property<?>> sortedProperties(FluidState state) {
        List<Property<?>> result = new ArrayList<>(state.getProperties());
        result.sort(Comparator.comparing(Property::getName));
        return result;
    }

    private static float cycleFloat(float current, float[] values, int direction) {
        if (values == null || values.length == 0) return current;
        int nearest = 0;
        float distance = Float.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            float d = Math.abs(values[i] - current);
            if (d < distance) { distance = d; nearest = i; }
        }
        return values[floorMod(nearest + direction, values.length)];
    }

    private String sceneName() {
        String value = sceneNameField == null ? sceneName : sceneNameField.getText();
        return value == null || value.isBlank() ? "playground" : value;
    }

    private String shortProjection() {
        return spriteEngine.scene().projection().mode().name().replace("_SIDE", "").replace("_TOP", "");
    }

    private String fit(String value, int maxWidth) {
        if (value == null) return "";
        if (textRenderer.getWidth(value) <= maxWidth) return value;
        String suffix = "...";
        int width = Math.max(0, maxWidth - textRenderer.getWidth(suffix));
        String trimmed = textRenderer.trimToWidth(value, width);
        return trimmed + suffix;
    }

    private boolean pointerOverOverlayPanel(Layout l, double x, double y) {
        return inside(x, y, l.leftX, l.contentTop, l.leftX + l.leftWidth, l.footerTop)
                || inside(x, y, l.rightX, l.contentTop, l.rightX + l.rightWidth, l.footerTop);
    }

    private List<Integer> occupiedDepths() {
        Set<Integer> depths = new LinkedHashSet<>();
        depths.add(currentDepth);
        for (var entry : spriteEngine.scene().blocks().entries()) depths.add(spriteEngine.scene().projection().depthCoordinate(entry.position()));
        for (var entry : spriteEngine.scene().fluids().entries()) depths.add(spriteEngine.scene().projection().depthCoordinate(entry.position()));
        for (Actor actor : spriteEngine.scene().actors().actors()) if (actor != null && !actor.removed()) depths.add(actor.depth());
        List<Integer> result = new ArrayList<>(depths);
        result.sort(Integer::compareTo);
        return result;
    }

    private int[] layerRailRange() {
        List<Integer> occupied = occupiedDepths();
        int min = currentDepth;
        int max = currentDepth;
        for (int depth : occupied) { min = Math.min(min, depth); max = Math.max(max, depth); }
        if (max - min <= 14) return new int[]{min, max};
        return new int[]{currentDepth - 7, currentDepth + 7};
    }

    private void renderLayerRail(DrawContext context, Layout l, int mouseX, int mouseY) {
        int[] range = layerRailRange();
        int count = Math.max(1, range[1] - range[0] + 1);
        int available = Math.max(0, l.rightX - (l.leftX + l.leftWidth) - 16);
        int boxW = available >= count * 25 + 8 ? 25 : Math.max(18, (available - 8) / count);
        int railW = count * boxW + 8;
        int left = l.leftX + l.leftWidth + 8 + Math.max(0, (available - railW) / 2);
        int top = l.footerTop - 42;
        if (top <= l.contentTop + 8 || available < count * 18 + 8) return;

        Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (var entry : spriteEngine.scene().blocks().entries()) {
            int depth = spriteEngine.scene().projection().depthCoordinate(entry.position());
            counts.merge(depth, 1, Integer::sum);
        }
        for (var entry : spriteEngine.scene().fluids().entries()) {
            int depth = spriteEngine.scene().projection().depthCoordinate(entry.position());
            counts.merge(depth, 1, Integer::sum);
        }
        for (Actor actor : spriteEngine.scene().actors().actors()) {
            if (actor != null && !actor.removed()) counts.merge(actor.depth(), 1, Integer::sum);
        }

        context.fill(left, top, left + railW, top + 36, withAlpha(color(uiColorContentBase), 220));
        context.drawBorder(left, top, railW, 36, color(uiColorBackgroundBorder));
        String title = "EDIT LAYER Z=" + currentDepth + "  |  ALL VISIBLE  |  "
                + (spriteEngine.scene().projection().crossLayerInteractions() ? "LOGIC LINKED" : "LOGIC ISOLATED");
        context.drawText(textRenderer, Text.literal(fit(title, railW - 10)), left + 5, top + 4,
                color(uiColorContentBaseTitleText), false);

        for (int depth = range[0]; depth <= range[1]; depth++) {
            int index = depth - range[0];
            int x = left + 4 + index * boxW;
            boolean occupied = counts.getOrDefault(depth, 0) > 0;
            boolean current = depth == currentDepth;
            int fill = occupied ? withAlpha(color(uiColorSelectionHighlight), current ? 128 : 58)
                    : withAlpha(color(uiColorContentBase), current ? 160 : 72);
            context.fill(x, top + 15, x + boxW - 2, top + 32, fill);
            if (current) context.drawBorder(x, top + 15, boxW - 2, 17, color(uiColorBackgroundBorderSelected));
            String label = Integer.toString(depth);
            context.drawText(textRenderer, Text.literal(label),
                    x + Math.max(2, (boxW - 2 - textRenderer.getWidth(label)) / 2), top + 19,
                    color(occupied ? uiColorContentBaseTitleText : uiColorContentBaseDescriptionText), false);
        }
    }

    private Integer layerRailLayerAt(Layout l, double mouseX, double mouseY) {
        int[] range = layerRailRange();
        int count = Math.max(1, range[1] - range[0] + 1);
        int available = Math.max(0, l.rightX - (l.leftX + l.leftWidth) - 16);
        int boxW = available >= count * 25 + 8 ? 25 : Math.max(18, (available - 8) / count);
        int railW = count * boxW + 8;
        int left = l.leftX + l.leftWidth + 8 + Math.max(0, (available - railW) / 2);
        int top = l.footerTop - 42;
        if (top <= l.contentTop + 8 || available < count * 18 + 8
                || !inside(mouseX, mouseY, left + 4, top + 15, left + railW - 4, top + 32)) return null;
        int index = ((int) mouseX - (left + 4)) / boxW;
        if (index < 0 || index >= count) return null;
        return range[0] + index;
    }

    private void setStatus(String message) {
        status = message == null || message.isBlank() ? "Ready" : message;
        statusUntil = System.currentTimeMillis() + 4500L;
    }

    private Layout layout() {
        int contentTop = HEADER_HEIGHT + 5;
        int footerTop = Math.max(contentTop + 80, height - FOOTER_HEIGHT);
        int outer = width < 400 ? 8 : 39;
        int available = Math.max(220, width - outer * 2);
        int minCanvas = width < 640 ? 92 : 160;
        int leftWidth = Math.min(136, Math.max(80, available / 5));
        int rightWidth = Math.min(244, Math.max(120, available / 3));
        int maximumSides = Math.max(160, available - minCanvas - PANEL_GAP * 2);
        if (leftWidth + rightWidth > maximumSides) {
            int overflow = leftWidth + rightWidth - maximumSides;
            int reduceRight = Math.min(overflow, Math.max(0, rightWidth - 120));
            rightWidth -= reduceRight;
            overflow -= reduceRight;
            if (overflow > 0) leftWidth = Math.max(80, leftWidth - overflow);
        }
        int leftX = outer;
        int rightMargin = outer;
        int rightX = width - rightMargin - rightWidth;
        // The scene is the workspace. Tool/registry panels float above the same
        // full content canvas instead of shrinking the authoring area.
        int canvasX = 0;
        int canvasRight = width;
        return new Layout(leftX, leftWidth, rightX, rightWidth, canvasX, canvasRight,
                contentTop, footerTop, contentTop, footerTop);
    }

    @Override
    public void close() {
        ScreenSpriteOverlay.resetSceneInput();
        // The playground is an authoring session, not an implicit HUD commit.
        // Restore the scene that was active before the editor opened so test/build
        // content does not remain floating over the game after Done/Escape. Saved
        // scenes can be intentionally activated later with /sprite scene load.
        if (closeRestoreSnapshot != null && !closeRestoreSnapshot.isBlank()) {
            try {
                JsonObject root = JsonParser.parseString(closeRestoreSnapshot).getAsJsonObject();
                SpriteSceneStorage.restore(engine, root);
            } catch (RuntimeException ignored) {
                engine.reset();
            }
        } else {
            engine.reset();
        }
        if (client != null) client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }

    private static int color(int value) { return new Color(value, true).getRGB(); }
    private static int withAlpha(int argb, int alpha) { return ((alpha & 0xFF) << 24) | (argb & 0x00FFFFFF); }
    private static boolean inside(double x, double y, int left, int top, int right, int bottom) {
        return x >= left && x < right && y >= top && y < bottom;
    }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static int floorMod(int value, int modulus) { int result = value % modulus; return result < 0 ? result + modulus : result; }
    private static String round2(float value) { return String.format(Locale.ROOT, "%.2f", value); }
    private static int safeFluidLevel(FluidState state) {
        try { return state.getFluid().getLevel(state); }
        catch (RuntimeException ignored) { return 0; }
    }
    private static void trimHistory(Deque<String> history) { while (history.size() > HISTORY_LIMIT) history.removeFirst(); }

    private static Identifier particleIdentifier(String id) {
        if (id == null || id.isBlank()) return null;
        String normalized = id.strip().toLowerCase(Locale.ROOT);
        try {
            if (normalized.indexOf(':') >= 0) return new Identifier(normalized);
            return new Identifier("koil", normalized);
        } catch (RuntimeException ignored) { return null; }
    }

    private enum InspectorKind { SECTION, BLOCK_STATE, FLUID_STATE, RUNTIME, FLAGS, BLOCK_ENTITY, CUSTOM, ACTOR, READ_ONLY }

    private record InspectorRow(String key, String label, String value, boolean editable, InspectorKind kind, Property<?> property) {
        static InspectorRow section(String label) { return new InspectorRow("section:" + label, label, "", false, InspectorKind.SECTION, null); }
        static InspectorRow read(String key, String label, String value) { return new InspectorRow(key, label, value, false, InspectorKind.READ_ONLY, null); }
    }

    private enum Tool {
        MOUSE("1 Mouse"), PLACE("2 Place");
        private final String label;
        Tool(String label) { this.label = label; }
    }

    private enum CatalogKind {
        BLOCKS("Blocks", "Blk"), ITEMS("Items", "Itm"), FLUIDS("Fluids", "Fl"), PARTICLES("FX", "FX");
        private final String label;
        private final String shortLabel;
        CatalogKind(String label, String shortLabel) { this.label = label; this.shortLabel = shortLabel; }
    }

    private record CatalogEntry(CatalogKind kind, Identifier id, String runtimeId, String label, ItemStack icon) { }

    private record Layout(int leftX, int leftWidth, int rightX, int rightWidth,
                          int canvasX, int canvasRight, int contentTop, int footerTop,
                          int canvasY, int canvasBottom) {
        int canvasWidth() { return Math.max(1, canvasRight - canvasX); }
        int canvasHeight() { return Math.max(1, canvasBottom - canvasY); }
        int contentHeight() { return Math.max(1, footerTop - contentTop); }
        int catalogTop() { return contentTop + 64; }
        int terrainTop() { return contentTop + 158; }
        int inspectorTop() { return Math.max(catalogTop() + 82, footerTop - Math.max(180, contentHeight() / 2)); }
        int inspectorEditorY() { return footerTop - 27; }
    }
}
