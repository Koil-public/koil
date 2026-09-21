package com.spirit.client.gui.automation;

import com.google.gson.GsonBuilder;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.spirit.Main;
import com.spirit.client.gui.SuggestionPopupRenderer;
import com.spirit.client.gui.TopBarLayout;
import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.automation.AutomationRouter;
import com.spirit.koil.api.automation.AutomationRuntimeStatus;
import com.spirit.koil.api.automation.cli.AutomationCliRow;
import com.spirit.koil.api.automation.workspace.AutomationWorkspaceRepository;
import com.spirit.koil.api.automation.workspace.AutomationWorkspaceTrace;
import com.spirit.koil.api.chat.*;
import com.spirit.koil.api.chat.input.CommandSuggestionFuturePoller;
import com.spirit.koil.api.chat.input.VanillaBackedChatInputController;
import com.spirit.koil.api.chat.upload.RichChatAttachmentRenderer;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.minecraft.MinecraftNbtSuggestionService;
import com.spirit.koil.api.model.LocalModelService;
import com.spirit.koil.api.model.ModelDebugMode;
import com.spirit.koil.api.model.ModelMessage;
import com.spirit.koil.api.model.ModelRole;
import com.spirit.koil.api.model.ModelActivityState;
import com.spirit.koil.api.model.ModelSemanticPalette;
import com.spirit.koil.api.model.chat.ModelActivityPresentation;
import com.spirit.koil.api.model.chat.ModelChatIdentity;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;
import com.spirit.koil.api.model.chat.ModelOutputMaterialization;
import com.spirit.koil.api.model.chat.ModelRequestStatusPresentation;
import com.spirit.koil.api.model.format.RichChatModelOutputSanitizer;
import com.spirit.koil.api.model.voice.ModelVoiceService;
import com.spirit.koil.api.telemetry.TelemetryCapabilityState;
import com.spirit.koil.api.telemetry.TelemetryText;
import com.spirit.koil.api.util.file.KoilInstancePaths;
import com.spirit.mixin.client.gui.accessor.TextFieldWidgetAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.Rect2i;
import net.minecraft.command.CommandSource;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static com.spirit.Main.LOGO_TEXTURE;
import static com.spirit.client.gui.ide.FileExplorerScreen.STOP_BUTTON;
import static com.spirit.koil.api.design.uiColorVal.*;

/**
 * Connected observability workspace for Automation, Model, and Executor internals.
 *
 * Runtime systems are projected as data panes with explicit input/output sockets.
 * Canonical telemetry edges carry recorded flow identities rather than decorative
 * activity pulses, while legacy rows remain available for compatibility.
 */
public final class AutomationWorkspaceScreen extends Screen {
    private static final Path KTL_ROOT = KoilInstancePaths.automationRoot();

    private static final String AUTOMATION_PACKAGE = "com.spirit.koil.api.automation";
    private static final String EXECUTOR_PACKAGE = "com.spirit.koil.api.automation.runtime";
    private static final String MODEL_PACKAGE = "com.spirit.koil.api.model";
    private static final String AUTOMATION_GUI_PACKAGE = "com.spirit.client.gui.automation";
    private static final String MODEL_GUI_PACKAGE = "com.spirit.client.gui.model";

    private static final int HEADER_BOTTOM = 68;
    private static final int CONTENT_TOP = 70;
    private static final int SIDEBAR_WIDTH = 240;
    private static final int PANEL_GAP = 2;
    private static final int CANVAS_INSET = 1;
    private static final int NOTE_WIDTH = 360;
    private static final int NOTE_BASE_HEIGHT = 82;
    private static final int NOTE_GAP = 10;
    private static final int DEPTH_STEP = 382;
    private static final int DETAIL_LINE_HEIGHT = 10;
    private static final int NOTE_PREVIEW_DETAIL_LINES = 8;
    private static final int MAX_SELECTED_DETAIL_LINES = 36;
    private static final int WORKSPACE_GRID_CELL = 16;
    private static final int CONNECTION_VIEWPORT_OVERSCAN = 180;
    private static final long NOTEBOOK_ACTIVE_REFRESH_MILLIS = 120L;
    private static final long NOTEBOOK_IDLE_REFRESH_MILLIS = 1_000L;
    private static final long FLOW_SIGNAL_VISIBLE_MILLIS = 2_600L;
    private static final int COMPOSER_MIN_LINES = 1;
    private static final int COMPOSER_MAX_LINES = 10;
    private static final int COMPOSER_HORIZONTAL_PADDING = 2;
    private static final int STOP_ICON_SIZE = 16;
    private static final int STOP_ICON_GAP = 6;
    private static final int SEARCH_DROPDOWN_MAX_VISIBLE_ROWS = 8;
    private static final int SEARCH_DROPDOWN_ROW_HEIGHT = 24;
    private static final int SEARCH_DROPDOWN_PADDING = 6;
    private static final float SEARCH_MODAL_Z = 600.0F;
    private static final double MIN_CANVAS_ZOOM = 0.45D;
    private static final double MAX_CANVAS_ZOOM = 1.85D;
    private static final double ZOOM_STEP = 0.08D;
    private static final double CANVAS_SCROLL_STEP = 5.0D;
    private static final int DRAG_THRESHOLD = 3;
    private static final long KTL_REFRESH_MILLIS = 2_000L;
    private static final long FORCED_STOP_VISIBILITY_MILLIS = 2_800L;
    private static final int STATUS_SEPARATOR_COLOR = 0xFF596576;
    private static final int REASONING_TEXT_COLOR = 0xFF7F8896;
    private static final String TOP_BAR_BACK_LABEL = "<";
    private static final String TOP_BAR_EXPORT_LABEL = "Export";
    private static final String TOP_BAR_COPY_LABEL = "Copy";
    private static final String TOP_BAR_CENTER_LABEL = "Center";
    private static final String TOP_BAR_RESET_LABEL = "Reset";
    private static final String TOP_BAR_FILTER_PREFIX = "Filter: ";
    private static final String TOP_BAR_VIEW_PREFIX = "View: ";
    private static final String TOP_BAR_DETAIL_PREFIX = "Text: ";
    private static final String TOP_BAR_COMPARE_LABEL = "Compare";

    private final Screen parent;
    private final String initiallyFocusedTrace;
    private final Set<String> expandedNodes = new HashSet<>();
    private final List<NoteHit> noteHits = new ArrayList<>();
    private final List<ActionHit> actionHits = new ArrayList<>();

    private final AutomationWorkspaceTelemetryViewModel telemetryViewModel = new AutomationWorkspaceTelemetryViewModel();
    private WorkspaceViewMode workspaceViewMode = WorkspaceViewMode.NOTEBOOK;
    private TelemetryDetailMode telemetryDetailMode = TelemetryDetailMode.DETAILS;
    private UUID comparisonRequestA;
    private UUID comparisonRequestB;
    private long cachedTelemetryProjectionRevision = Long.MIN_VALUE;
    private UUID cachedTelemetryProjectionRequestId;
    private WorkspaceViewMode cachedTelemetryProjectionMode;
    private TelemetryDetailMode cachedTelemetryProjectionDetailMode;
    private List<NotebookNode> cachedTelemetryProjection = List.of();
    private final Map<TextWrapKey, List<String>> textWrapCache = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<TextWrapKey, List<String>> eldest) {
            return size() > 4096;
        }
    };
    private final Map<PaneMeasureKey, Integer> paneHeightCache = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<PaneMeasureKey, Integer> eldest) {
            return size() > 8192;
        }
    };
    private final Map<TypedWrapKey, List<List<TelemetryText>>> typedTextWrapCache = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<TypedWrapKey, List<List<TelemetryText>>> eldest) {
            return size() > 4096;
        }
    };
    private final Map<WorkspaceMessageRenderKey, WorkspaceRenderedMessage> workspaceRenderedMessageCache =
        new LinkedHashMap<>(128, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<WorkspaceMessageRenderKey, WorkspaceRenderedMessage> eldest) {
                return size() > 256;
            }
        };
    private List<NotebookNode> renderRootsCache = List.of();
    private long renderRootsCacheAtMillis;
    private long renderRootsGeneration;
    private boolean renderRootsDirty = true;
    private LayoutSnapshot renderLayoutCache = LayoutSnapshot.empty();
    private long renderLayoutRootsGeneration = Long.MIN_VALUE;
    private int renderLayoutExpandedHash;
    private int renderLayoutPositionsHash;
    private String renderLayoutSelectedNodeId = "";

    private NotebookNode automationArchitecture;
    private NotebookNode executorArchitecture;
    private NotebookNode modelArchitecture;
    private NotebookNode automationGuiArchitecture;
    private NotebookNode modelGuiArchitecture;
    private NotebookNode cachedKtlNotebook;
    private long cachedKtlNotebookAt;

    private int automationClassCount;
    private int executorClassCount;
    private int modelClassCount;
    private int ktlFileCount;

    private String selectedNodeId = "";
    private String selectedTraceId = "";
    private String fullyExpandedPaneId = "";

    private double canvasScrollX;
    private double canvasScrollY;
    private double canvasZoom = 1.0D;
    private String pendingCenterNodeId = "";
    private final Map<String, NotePosition> notePositions = new HashMap<>();
    private boolean requestFocusMode;
    private String requestFocusRequestId = "";
    private final Set<String> requestFocusedNodeIds = new HashSet<>();
    private final Set<String> activeWorkspaceTraceIds = new HashSet<>();
    private final Set<String> activeCanvasNodeIds = new HashSet<>();
    private String draggingNoteId = "";
    private NotebookNode draggingNoteNode;
    private double dragStartMouseX;
    private double dragStartMouseY;
    private double dragNoteStartX;
    private double dragNoteStartY;
    private boolean noteDragMoved;
    private boolean draggingCanvas;
    private double canvasDragStartMouseX;
    private double canvasDragStartMouseY;
    private double canvasDragStartX;
    private double canvasDragStartY;

    private TextFieldWidget notebookSearchInput;
    private final List<PadSearchResult> notebookSearchResults = new ArrayList<>();
    private int selectedNotebookSearchIndex = -1;
    private int notebookSearchScrollOffset;
    private boolean notebookSearchDropdownDismissed;
    private int searchDropdownX;
    private int searchDropdownY;
    private int searchDropdownWidth;
    private int searchDropdownHeight;

    private TextFieldWidget chatInput;
    private boolean composerFocused;
    private int chatBorderX;
    private int chatBorderY;
    private int chatBorderWidth;
    private int chatBorderHeight;
    private int chatFieldX;
    private int chatFieldY;
    private int chatFieldWidth;
    private int composerScrollLine;
    private boolean composerManualScroll;

    private boolean draggingComposerScrollbar;
    private double composerScrollbarGrabOffset;
    private int composerScrollbarTrackX;
    private int composerScrollbarTrackTop;
    private int composerScrollbarTrackBottom;
    private int composerScrollbarThumbTop;
    private int composerScrollbarThumbHeight;
    private int composerScrollbarMaxFirst;
    private int stopIconX;
    private int stopIconY;
    private int approvalTop;
    private int approvalBottom;
    private int chatStreamTop;
    private int chatStreamBottom;
    private int chatStreamManualOffset;
    private boolean chatStreamManualScroll;
    private boolean draggingChatScrollbar;
    private double chatScrollbarGrabOffset;
    private int chatScrollbarTrackX;
    private int chatScrollbarTrackTop;
    private int chatScrollbarTrackBottom;
    private int chatScrollbarThumbTop;
    private int chatScrollbarThumbHeight;
    private int chatScrollbarMaximumOffset;
    private int[] currentModelMessageBounds;
    private ModelGenerationHudState.Snapshot retainedModelSnapshot;
    private final List<WorkspaceChatMessage> workspaceMessageHistory = new ArrayList<>();
    private final List<MessageHit> workspaceMessageHits = new ArrayList<>();
    private final List<ToolMessageHit> workspaceToolHits = new ArrayList<>();
    private long workspaceMessageSequence;
    private long observedConversationResetEpoch = -1L;
    private UUID selectedHistoryRequestId;
    private TraceFilter traceFilter = TraceFilter.MESSAGE;
    private boolean followNextSubmittedRequest;
    private String lastSubmittedMessage = "";
    private long lastSubmittedAtMillis;

    private CompletableFuture<Suggestions> customSuggestionFuture;
    private String customSuggestionRequestKey = "";
    private DraftSuggestionContext customSuggestionContext;
    private List<Suggestion> customSuggestions = List.of();
    private List<SuggestionPopupRenderer.Entry> customSuggestionEntries = List.of();
    private boolean customSuggestionsAreSticky;
    private boolean customSuggestionTabCycleActive;
    private String customSuggestionTabCycleValue = "";
    private Rect2i customSuggestionArea;
    private int customSuggestionSelection;
    private int customSuggestionScroll;

    private String notice = "";
    private long noticeUntil;
    private long forcedStopAtMillis;

    public AutomationWorkspaceScreen(Screen parent) {
        this(parent, "");
    }

    public AutomationWorkspaceScreen(Screen parent, String focusedTraceId) {
        super(Text.literal("Automation Workspace"));
        this.parent = parent;
        this.initiallyFocusedTrace = focusedTraceId == null ? "" : focusedTraceId;
        this.selectedTraceId = this.initiallyFocusedTrace;
        if (!this.initiallyFocusedTrace.isBlank()) {
            this.selectedNodeId = traceNodeId(this.initiallyFocusedTrace);
        }
    }

    @Override
    protected void init() {
        super.init();
        if (this.automationArchitecture == null) {
            buildStaticTopology();
        }

        this.expandedNodes.add("root:automation");
        this.expandedNodes.add("root:model");
        this.expandedNodes.add("root:executor");
        this.expandedNodes.add("root:observability");
        if (!this.initiallyFocusedTrace.isBlank()) {
            this.expandedNodes.add(traceNodeId(this.initiallyFocusedTrace));
        }

        this.notebookSearchInput = new TextFieldWidget(this.textRenderer, 0, 0, 220, TopBarLayout.SEARCH_FIELD_HEIGHT, Text.empty());
        this.notebookSearchInput.setMaxLength(512);
        this.notebookSearchInput.setPlaceholder(Text.literal("Search panes"));
        this.notebookSearchInput.setChangedListener(value -> {
            this.notebookSearchScrollOffset = 0;
            this.selectedNotebookSearchIndex = -1;
            this.notebookSearchDropdownDismissed = false;
            rebuildNotebookSearchResults();
        });
        this.addDrawableChild(this.notebookSearchInput);

        this.chatInput = new TextFieldWidget(this.textRenderer, 0, 0, 320, 20, Text.literal("Model input"));
        this.chatInput.setMaxLength(32767);
        this.chatInput.setDrawsBackground(false);
        this.chatInput.setFocused(false);
        this.composerFocused = false;
        updateTopBarSearchGeometry();
        updateComposerGeometry();
        this.observedConversationResetEpoch = LocalModelService.conversationResetEpoch();
        syncWorkspaceConversationTranscript();
        rebuildNotebookSearchResults();
    }

    @Override
    public void tick() {
        super.tick();
        updateTopBarSearchGeometry();
        updateComposerGeometry();
        if (this.chatInput != null) {
            this.chatInput.tick();
        }
        pollCustomSuggestions();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Automation Workspace paints model responses outside ChatHud, so begin the
        // same Rich Chat interaction frame explicitly before any detached rows draw.
        RichChatAttachmentRenderer.beginFrame(mouseX, mouseY);
        updateTopBarSearchGeometry();
        updateComposerGeometry();

        refreshWorkspaceConversationEpoch();
        ModelGenerationHudState.Snapshot liveSnapshot = ModelGenerationHudState.visibleSnapshot();
        ModelGenerationHudState.Snapshot metricsSnapshot = ModelGenerationHudState.metricsSnapshot();
        captureWorkspaceMessageHistory(liveSnapshot, metricsSnapshot);

        ModelGenerationHudState.Snapshot newestSnapshot = liveSnapshot != null ? liveSnapshot : metricsSnapshot;
        if (newestSnapshot != null && (!newestSnapshot.prompt().isBlank() || !newestSnapshot.text().isBlank())) {
            this.retainedModelSnapshot = newestSnapshot;
        }

        int approvalHeight = approvalPanelHeight(liveSnapshot);
        this.approvalBottom = this.chatBorderY - 6;
        this.approvalTop = approvalHeight <= 0
            ? this.approvalBottom
            : Math.max(CONTENT_TOP + 34, this.approvalBottom - approvalHeight);
        this.chatStreamBottom = Math.max(this.chatStreamTop + 20, approvalHeight > 0 ? this.approvalTop - 6 : this.chatBorderY - 6);
        int canvasBottom = Math.max(CONTENT_TOP + 40, this.height - 20);

        this.actionHits.clear();
        renderFileExplorerChrome(context, canvasBottom);
        renderTopBar(context, mouseX, mouseY);

        List<NotebookNode> roots = notebookRootsForRender();
        renderSidebarConversation(context, mouseX, mouseY, selectedTraceSnapshot());
        renderNotebook(context, roots, mouseX, mouseY, canvasBottom);
        renderApprovalPanel(context, mouseX, mouseY, liveSnapshot);
        renderComposer(context, mouseX, mouseY);
        renderFooterStatus(context, mouseX, mouseY);

        super.render(context, mouseX, mouseY, delta);
        renderCustomSuggestionPopup(context, mouseX, mouseY);

        renderNotebookSearchDropdown(context, mouseX, mouseY);
    }

    private List<NotebookNode> notebookRootsForRender() {
        long now = System.currentTimeMillis();
        if (!this.renderRootsDirty && !this.renderRootsCache.isEmpty()
                && (this.draggingCanvas || !this.draggingNoteId.isBlank())) {
            return this.renderRootsCache;
        }
        long refresh = systemsActive() ? NOTEBOOK_ACTIVE_REFRESH_MILLIS : NOTEBOOK_IDLE_REFRESH_MILLIS;
        if (this.renderRootsDirty || this.renderRootsCache.isEmpty()
                || now - this.renderRootsCacheAtMillis >= refresh) {
            this.renderRootsCache = List.copyOf(buildNotebookRoots());
            this.renderRootsCacheAtMillis = now;
            this.renderRootsGeneration++;
            this.renderRootsDirty = false;
        }
        return this.renderRootsCache;
    }

    private void invalidateNotebookRenderModel() {
        this.renderRootsDirty = true;
        this.renderLayoutRootsGeneration = Long.MIN_VALUE;
    }

    private LayoutSnapshot layoutForRender(List<NotebookNode> roots) {
        int expandedHash = this.expandedNodes.hashCode();
        int positionsHash = this.notePositions.hashCode();
        String selected = this.selectedNodeId == null ? "" : this.selectedNodeId;
        if (this.renderLayoutRootsGeneration == this.renderRootsGeneration
                && this.renderLayoutExpandedHash == expandedHash
                && this.renderLayoutPositionsHash == positionsHash
                && this.renderLayoutSelectedNodeId.equals(selected)) {
            return this.renderLayoutCache;
        }

        List<LayoutEntry> layout = layoutVisibleNotes(roots);
        Map<String, LayoutEntry> byId = new HashMap<>(Math.max(16, layout.size() * 2));
        for (LayoutEntry entry : layout) byId.put(entry.node().id, entry);

        List<ConnectionEntry> connections = new ArrayList<>(Math.max(8, layout.size() * 2));
        Set<String> connectionKeys = new HashSet<>();
        for (LayoutEntry entry : layout) {
            if (!entry.parentId().isBlank() && byId.containsKey(entry.parentId())) {
                String key = entry.parentId() + "->" + entry.node().id;
                if (connectionKeys.add(key)) {
                    connections.add(new ConnectionEntry(entry.parentId(), entry.node().id, false));
                }
            }
            for (String targetId : entry.node().links) {
                if (!byId.containsKey(targetId)) continue;
                String key = entry.node().id + "->" + targetId;
                if (connectionKeys.add(key)) {
                    connections.add(new ConnectionEntry(entry.node().id, targetId, true));
                }
            }
        }

        this.renderLayoutCache = new LayoutSnapshot(List.copyOf(layout), Map.copyOf(byId), List.copyOf(connections));
        this.renderLayoutRootsGeneration = this.renderRootsGeneration;
        this.renderLayoutExpandedHash = expandedHash;
        this.renderLayoutPositionsHash = positionsHash;
        this.renderLayoutSelectedNodeId = selected;
        return this.renderLayoutCache;
    }

    private void renderNotebook(
        DrawContext context,
        List<NotebookNode> roots,
        int mouseX,
        int mouseY,
        int canvasBottom
    ) {
        this.noteHits.clear();

        int left = canvasLeft();
        int right = canvasRight();
        int top = CONTENT_TOP + 1;

        int contentTop = top;
        int contentBottom = Math.max(contentTop + 1, canvasBottom - 1);
        int viewportWidth = Math.max(1, right - left);
        int viewportHeight = Math.max(1, contentBottom - contentTop);

        ModelGenerationHudState.Snapshot selectedSnapshot = this.traceFilter == TraceFilter.ALL ? null : selectedTraceSnapshot();
        String viewTargetLabel = this.traceFilter == TraceFilter.ALL
            ? "system topology"
            : selectedSnapshot == null
            ? "no message selected"
            : this.textRenderer.trimToWidth(selectedSnapshot.prompt().replace('\n', ' '), Math.max(40, viewportWidth / 2));
        String summary = "VIEW " + this.traceFilter.label().toUpperCase(Locale.ROOT)
            + "  |  " + viewTargetLabel
            + "  |  " + Math.round(this.canvasZoom * 100.0D) + "%";

        LayoutSnapshot layoutSnapshot = layoutForRender(roots);
        List<LayoutEntry> layout = layoutSnapshot.layout();
        Map<String, LayoutEntry> byId = layoutSnapshot.byId();

        if (!this.pendingCenterNodeId.isBlank()) {
            LayoutEntry centerTarget = byId.get(this.pendingCenterNodeId);
            if (centerTarget != null) {
                double logicalViewportWidth = viewportWidth / this.canvasZoom;
                double logicalViewportHeight = viewportHeight / this.canvasZoom;
                this.canvasScrollX = logicalViewportWidth * 0.5D - (centerTarget.x() + centerTarget.width() * 0.5D);
                this.canvasScrollY = logicalViewportHeight * 0.5D - (centerTarget.y() + centerTarget.height() * 0.5D);
                this.pendingCenterNodeId = "";
            }
        }

        int logicalViewportLeft = (int) Math.floor(-this.canvasScrollX - CONNECTION_VIEWPORT_OVERSCAN);
        int logicalViewportTop = (int) Math.floor(-this.canvasScrollY - CONNECTION_VIEWPORT_OVERSCAN);
        int logicalViewportRight = (int) Math.ceil(-this.canvasScrollX + viewportWidth / this.canvasZoom + CONNECTION_VIEWPORT_OVERSCAN);
        int logicalViewportBottom = (int) Math.ceil(-this.canvasScrollY + viewportHeight / this.canvasZoom + CONNECTION_VIEWPORT_OVERSCAN);

        double logicalMouseX = (mouseX - left) / this.canvasZoom - this.canvasScrollX;
        double logicalMouseY = (mouseY - contentTop) / this.canvasZoom - this.canvasScrollY;

        context.enableScissor(left, contentTop, right, contentBottom);
        renderWorkspaceGrid(context, left, contentTop, right, contentBottom);
        context.getMatrices().push();
        context.getMatrices().translate(left, contentTop, 0.0F);
        context.getMatrices().scale((float) this.canvasZoom, (float) this.canvasZoom, 1.0F);
        context.getMatrices().translate((float) this.canvasScrollX, (float) this.canvasScrollY, 0.0F);

        renderConnections(context, layoutSnapshot, logicalViewportLeft, logicalViewportTop,
            logicalViewportRight, logicalViewportBottom);
        for (LayoutEntry entry : layout) {
            if (entry.bottom() < logicalViewportTop || entry.y() > logicalViewportBottom
                    || entry.right() < logicalViewportLeft || entry.x() > logicalViewportRight) {
                continue;
            }
            int screenLeft = logicalToScreenX(entry.x(), left);
            int screenTop = logicalToScreenY(entry.y(), contentTop);
            int screenRight = logicalToScreenX(entry.right(), left);
            int screenBottom = logicalToScreenY(entry.bottom(), contentTop);
            if (screenBottom < contentTop || screenTop > contentBottom
                || screenRight < left || screenLeft > right) {
                continue;
            }
            renderNote(context, entry, (int) Math.round(logicalMouseX), (int) Math.round(logicalMouseY),
                logicalViewportTop, logicalViewportBottom);
            this.noteHits.add(new NoteHit(
                entry.node(),
                screenLeft,
                screenTop,
                screenRight,
                screenBottom,
                entry.x(),
                entry.y()
            ));
        }
        context.getMatrices().pop();
        context.disableScissor();

        context.drawTextWithShadow(
            this.textRenderer,
            this.textRenderer.trimToWidth(summary, Math.max(20, viewportWidth - 8)),
            left + 3,
            top + 3,
            0xFFD0D6E0
        );

        if (this.draggingCanvas) {
            String hint = "canvas " + Math.round(this.canvasScrollX) + ", " + Math.round(this.canvasScrollY)
                + "  @ " + Math.round(this.canvasZoom * 100.0D) + "%";
            context.drawTextWithShadow(this.textRenderer, hint, left + 3, contentBottom - 11, 0xFFB9C1CE);
        }
    }

    private void renderWorkspaceGrid(
        DrawContext context,
        int left,
        int top,
        int right,
        int bottom
    ) {
        if (right <= left || bottom <= top) return;
        float cell = (float) (WORKSPACE_GRID_CELL * this.canvasZoom);
        if (cell < 5.0F) return;

        float originX = (float) (left + this.canvasScrollX * this.canvasZoom);
        float originY = (float) (top + this.canvasScrollY * this.canvasZoom);
        int border = new Color(uiColorBackgroundBorder, true).getRGB();
        int grid = withAlpha(border, 72);
        int major = withAlpha(new Color(uiColorContentStripeLeft, true).getRGB(), 100);

        float firstBoundaryX = originX - cell * 0.5F;
        int xIndex = (int) Math.floor((left - firstBoundaryX) / cell);
        float gridX = firstBoundaryX + xIndex * cell;
        while (gridX < left) {
            gridX += cell;
            xIndex++;
        }
        for (float x = gridX; x < right; x += cell, xIndex++) {
            int px = Math.round(x);
            context.fill(px, top, px + 1, bottom, Math.floorMod(xIndex, 4) == 0 ? major : grid);
        }

        float firstBoundaryY = originY - cell * 0.5F;
        int yIndex = (int) Math.floor((top - firstBoundaryY) / cell);
        float gridY = firstBoundaryY + yIndex * cell;
        while (gridY < top) {
            gridY += cell;
            yIndex++;
        }
        for (float y = gridY; y < bottom; y += cell, yIndex++) {
            int py = Math.round(y);
            context.fill(left, py, right, py + 1, Math.floorMod(yIndex, 4) == 0 ? major : grid);
        }
    }

    private List<LayoutEntry> layoutVisibleNotes(List<NotebookNode> roots) {
        List<LayoutEntry> result = new ArrayList<>();
        int[] nextYByDepth = new int[96];
        for (NotebookNode root : roots) {
            int preferredRootY = nextYByDepth[0];
            layoutNode(root, 0, "", result, nextYByDepth, preferredRootY);
        }
        return result;
    }

    private void layoutNode(
        NotebookNode node,
        int depth,
        String parentId,
        List<LayoutEntry> result,
        int[] nextYByDepth,
        int preferredY
    ) {
        int safeDepth = Math.max(0, Math.min(depth, nextYByDepth.length - 1));
        int height = noteHeight(node);
        int automaticX = safeDepth * DEPTH_STEP;
        int automaticY = Math.max(preferredY, nextYByDepth[safeDepth]);

        NotePosition manual = this.notePositions.get(node.id);
        int x = manual == null ? automaticX : (int) Math.round(manual.x());
        int y = manual == null ? automaticY : (int) Math.round(manual.y());

        result.add(new LayoutEntry(node, parentId, safeDepth, x, y, NOTE_WIDTH, height));
        nextYByDepth[safeDepth] = Math.max(
            nextYByDepth[safeDepth],
            automaticY + height + NOTE_GAP
        );

        if (!node.children.isEmpty() && this.expandedNodes.contains(node.id)) {
            int childDepth = Math.min(safeDepth + 1, nextYByDepth.length - 1);
            int childPreferredY = automaticY;
            for (NotebookNode child : node.children) {
                int childY = Math.max(childPreferredY, nextYByDepth[childDepth]);
                layoutNode(child, childDepth, node.id, result, nextYByDepth, childY);
                childPreferredY = nextYByDepth[childDepth];
            }
        }
    }

    private List<LayoutEntry> resolveAutomaticOverlaps(List<LayoutEntry> source) {
        // Automatic entries are already lane-separated by layoutNode: fixed-width depth
        // columns never overlap horizontally and nextYByDepth guarantees monotonic Y.
        // Manual drag positions intentionally remain user-owned instead of triggering an
        // O(n²) global collision pass every render frame.
        return source == null ? List.of() : source;
    }

    private int logicalToScreenX(int logicalX, int canvasLeft) {
        return canvasLeft + (int) Math.round((logicalX + this.canvasScrollX) * this.canvasZoom);
    }

    private int logicalToScreenY(int logicalY, int canvasTop) {
        return canvasTop + (int) Math.round((logicalY + this.canvasScrollY) * this.canvasZoom);
    }

    private int noteHeight(NotebookNode node) {
        boolean selected = node != null && node.id.equals(this.selectedNodeId);
        boolean fullDetail = paneShowsAllDetails(node);
        PaneMeasureKey key = new PaneMeasureKey(
            node == null ? "" : node.id,
            node == null ? 0 : node.title.hashCode(),
            node == null ? 0 : node.summary.hashCode(),
            node == null ? 0 : node.detail.hashCode(),
            node == null ? 0 : node.source.hashCode(),
            node == null ? 0 : node.traceId.hashCode(),
            typedDetailFingerprint(node),
            selected,
            fullDetail
        );
        Integer cached = this.paneHeightCache.get(key);
        if (cached != null) return cached;

        int lineHeight = this.textRenderer.fontHeight + 2;
        int titleWidth = Math.max(24, NOTE_WIDTH - 70);
        int bodyWidth = Math.max(24, NOTE_WIDTH - 20);
        int titleLines = Math.max(1, wrapPlain(node.title, titleWidth).size());
        int summaryLines = node.summary.isBlank() ? 0 : wrapPlain(node.summary, bodyWidth).size();
        int detailLines = visibleDetailLineCount(node);

        // Row one is the input/output contract. Row two is subsystem + state.
        // Keeping those on dedicated rows guarantees that long labels never collide
        // with the output socket or silently clip into one another.
        int metaOffset = Math.max(29, 10 + titleLines * lineHeight);
        int metaRows = paneMetaRowCount(node);
        int timingRows = node.startedAtMillis > 0L ? 1 : 0;
        int height = Math.max(NOTE_BASE_HEIGHT,
            metaOffset
                + (metaRows + timingRows) * lineHeight + 5
                + (summaryLines == 0 ? 0 : summaryLines * lineHeight)
                + (detailLines == 0 ? 0 : 8 + detailLines * DETAIL_LINE_HEIGHT)
                + 7);
        this.paneHeightCache.put(key, height);
        return height;
    }

    private boolean paneShowsAllDetails(NotebookNode node) {
        return node != null && !this.fullyExpandedPaneId.isBlank() && node.id.equals(this.fullyExpandedPaneId);
    }

    private int typedDetailFingerprint(NotebookNode node) {
        if (node == null || node.typedDetailLines.isEmpty()) return 0;
        List<List<TelemetryText>> lines = node.typedDetailLines;
        int size = lines.size();
        int hash = 31 + size;
        hash = 31 * hash + lines.get(0).hashCode();
        if (size > 1) hash = 31 * hash + lines.get(size - 1).hashCode();
        if (size > 2) hash = 31 * hash + lines.get(size / 2).hashCode();
        return hash;
    }

    private int paneMetaRowCount(NotebookNode node) {
        if (node == null) return 2;
        int bodyWidth = Math.max(24, NOTE_WIDTH - 20);
        String statusLabel = friendly(node.state.id());
        String visibleStatus = SlidingStatusText.visibleLabel(statusLabel, node.state.id());
        int combined = this.textRenderer.getWidth(node.kind)
            + this.textRenderer.getWidth("  |  ")
            + this.textRenderer.getWidth(visibleStatus);
        return combined <= bodyWidth ? 2 : 3;
    }

    private int visibleDetailLineCount(NotebookNode node) {
        if (node == null) return 0;
        List<List<TelemetryText>> typed = notebookVisibleTypedDetailLines(node);
        if (!typed.isEmpty()) return typed.size();
        return notebookVisibleDetailLines(node).size();
    }

    private List<String> notebookVisibleDetailLines(NotebookNode node) {
        if (node == null) return List.of();
        boolean full = paneShowsAllDetails(node);
        boolean selected = node.id.equals(this.selectedNodeId);
        int maximum = full ? Integer.MAX_VALUE : selected ? MAX_SELECTED_DETAIL_LINES : NOTE_PREVIEW_DETAIL_LINES;
        List<String> details = selectedDetailLines(node, maximum);
        if (details.isEmpty() || full || details.size() <= maximum) return details;

        List<String> preview = new ArrayList<>(details.subList(0, maximum));
        preview.add("… more telemetry · Shift-click to show all · Copy exports all");
        return List.copyOf(preview);
    }

    private List<List<TelemetryText>> notebookVisibleTypedDetailLines(NotebookNode node) {
        if (node == null || node.typedDetailLines.isEmpty()) return List.of();
        boolean full = paneShowsAllDetails(node);
        boolean selected = node.id.equals(this.selectedNodeId);
        int maximum = full ? Integer.MAX_VALUE : selected ? MAX_SELECTED_DETAIL_LINES : NOTE_PREVIEW_DETAIL_LINES;
        List<List<TelemetryText>> details = wrappedTypedDetailLines(node, maximum);
        if (details.isEmpty() || full || details.size() <= maximum) return details;

        List<List<TelemetryText>> preview = new ArrayList<>(details.subList(0, maximum));
        preview.add(List.of(TelemetryText.muted(
            "… more telemetry · Shift-click to show all · Copy exports all"
        )));
        return List.copyOf(preview);
    }

    private List<List<TelemetryText>> wrappedTypedDetailLines(NotebookNode node, int maximumVisibleLines) {
        if (node == null || node.typedDetailLines.isEmpty()) return List.of();
        int width = Math.max(24, NOTE_WIDTH - 20);
        int limit = maximumVisibleLines == Integer.MAX_VALUE
            ? Integer.MAX_VALUE
            : Math.max(1, maximumVisibleLines + 1);
        TypedWrapKey key = new TypedWrapKey(
            node.id,
            typedDetailFingerprint(node),
            node.typedDetailLines.size(),
            width,
            limit
        );
        List<List<TelemetryText>> cached = this.typedTextWrapCache.get(key);
        if (cached != null) return cached;

        List<List<TelemetryText>> wrapped = new ArrayList<>();
        outer:
        for (List<TelemetryText> logicalLine : node.typedDetailLines) {
            for (List<TelemetryText> visualLine : wrapTypedTelemetryLine(logicalLine, width)) {
                wrapped.add(visualLine);
                if (wrapped.size() >= limit) break outer;
            }
        }
        List<List<TelemetryText>> result = List.copyOf(wrapped);
        this.typedTextWrapCache.put(key, result);
        return result;
    }

    private List<List<TelemetryText>> wrapTypedTelemetryLine(List<TelemetryText> spans, int width) {
        if (spans == null || spans.isEmpty()) return List.of(List.of());
        int safeWidth = Math.max(24, width);
        List<List<TelemetryText>> out = new ArrayList<>();
        List<TelemetryText> current = new ArrayList<>();
        int used = 0;

        for (TelemetryText span : spans) {
            if (span == null || span.text().isEmpty()) continue;
            String remainingText = span.text();
            while (!remainingText.isEmpty()) {
                int remainingWidth = safeWidth - used;
                if (remainingWidth <= 0 && !current.isEmpty()) {
                    out.add(List.copyOf(current));
                    current.clear();
                    used = 0;
                    remainingWidth = safeWidth;
                }

                String visible = this.textRenderer.trimToWidth(remainingText, Math.max(1, remainingWidth));
                if (visible.isEmpty()) {
                    if (!current.isEmpty()) {
                        out.add(List.copyOf(current));
                        current.clear();
                        used = 0;
                        continue;
                    }
                    int cp = remainingText.codePointAt(0);
                    visible = new String(Character.toChars(cp));
                }

                if (visible.length() < remainingText.length()) {
                    int whitespace = -1;
                    for (int i = visible.length() - 1; i > 0; i--) {
                        if (Character.isWhitespace(visible.charAt(i))) {
                            whitespace = i + 1;
                            break;
                        }
                    }
                    if (whitespace > 0) visible = visible.substring(0, whitespace);
                }

                current.add(TelemetryText.of(span.kind(), visible));
                used += this.textRenderer.getWidth(visible);
                remainingText = remainingText.substring(visible.length());

                if (!remainingText.isEmpty()) {
                    out.add(List.copyOf(current));
                    current.clear();
                    used = 0;
                    while (!remainingText.isEmpty() && Character.isWhitespace(remainingText.charAt(0))) {
                        remainingText = remainingText.substring(1);
                    }
                }
            }
        }

        if (!current.isEmpty()) out.add(List.copyOf(current));
        if (out.isEmpty()) out.add(List.of());
        return List.copyOf(out);
    }

    private void renderConnections(
        DrawContext context,
        LayoutSnapshot snapshot,
        int viewportLeft,
        int viewportTop,
        int viewportRight,
        int viewportBottom
    ) {
        if (snapshot == null || snapshot.connections().isEmpty()) return;
        long now = System.currentTimeMillis();
        Map<String, LayoutEntry> byId = snapshot.byId();

        for (ConnectionEntry connection : snapshot.connections()) {
            LayoutEntry from = byId.get(connection.fromId());
            LayoutEntry to = byId.get(connection.toId());
            if (from == null || to == null || !connectionIntersectsViewport(
                    from, to, viewportLeft, viewportTop, viewportRight, viewportBottom)) {
                continue;
            }

            boolean selectedEdge = from.node().id.equals(this.selectedNodeId)
                || to.node().id.equals(this.selectedNodeId);
            AutomationWorkspaceTelemetryProjection.Flow flow = to.node().inboundFlow;
            boolean recordedFlow = flow != null
                && flow.kind() != AutomationWorkspaceTelemetryProjection.FlowKind.NONE;
            int alpha = selectedEdge ? 224 : recordedFlow ? 92 : connection.dashed() ? 42 : 58;
            int color = withAlpha(ModelSemanticPalette.color(to.node().state), alpha);
            drawOrthogonalConnection(context, from, to, color, connection.dashed(), flow, selectedEdge, now);
        }
    }

    private boolean connectionIntersectsViewport(
        LayoutEntry from,
        LayoutEntry to,
        int viewportLeft,
        int viewportTop,
        int viewportRight,
        int viewportBottom
    ) {
        int left = Math.min(from.right(), to.x()) - 16;
        int right = Math.max(from.right(), to.x()) + 16;
        int top = Math.min(connectionPortY(from), connectionPortY(to)) - 16;
        int bottom = Math.max(connectionPortY(from), connectionPortY(to)) + 16;
        return right >= viewportLeft && left <= viewportRight && bottom >= viewportTop && top <= viewportBottom;
    }

    private void drawOrthogonalConnection(
        DrawContext context,
        LayoutEntry from,
        LayoutEntry to,
        int color,
        boolean dashed,
        AutomationWorkspaceTelemetryProjection.Flow flow,
        boolean selectedEdge,
        long now
    ) {
        int startX = from.right();
        int endX = to.x();
        int startY = connectionPortY(from);
        int endY = connectionPortY(to);
        int minimumOutRun = 10;
        int elbowX = endX > startX + 3
            ? startX + Math.max(2, (endX - startX) / 2)
            : Math.max(from.right(), to.right()) + minimumOutRun;

        if (dashed) {
            drawDashedHorizontal(context, Math.min(startX, elbowX), Math.max(startX, elbowX), startY, color);
            drawDashedVertical(context, elbowX, Math.min(startY, endY), Math.max(startY, endY), color);
            drawDashedHorizontal(context, Math.min(elbowX, endX), Math.max(elbowX, endX), endY, color);
        } else {
            fillLineH(context, startX, elbowX, startY, color);
            fillLineV(context, elbowX, startY, endY, color);
            fillLineH(context, elbowX, endX, endY, color);
        }

        drawConnectionArrow(context, elbowX, endX, endY, color);

        if (flow != null && flow.kind() != AutomationWorkspaceTelemetryProjection.FlowKind.NONE) {
            boolean ongoing = isNodeActivelyWorking(to.node());
            long age = Math.max(0L, now - flow.timestampMillis());
            if (ongoing || age <= FLOW_SIGNAL_VISIBLE_MILLIS) {
                drawFlowPacket(context, startX, startY, elbowX, endY, endX, flow, ongoing, age, now,
                    ModelSemanticPalette.color(to.node().state));
            } else {
                drawFlowTypeMarker(context, endX, endY, flow, ModelSemanticPalette.color(to.node().state));
            }
            if (selectedEdge) {
                drawFlowLabel(context, startX, startY, elbowX, flow);
            }
        }
    }

    private int connectionPortY(LayoutEntry entry) {
        return Math.min(entry.bottom() - 12, entry.y() + 28);
    }

    private void drawConnectionArrow(DrawContext context, int fromX, int endX, int y, int color) {
        int direction = endX >= fromX ? 1 : -1;
        int tipX = endX - direction;
        int shadow = 0x66000000;
        if (direction > 0) {
            context.fill(tipX - 3, y - 1, tipX + 1, y, shadow);
            context.fill(tipX - 2, y, tipX + 2, y + 3, shadow);
            context.fill(tipX - 3, y + 3, tipX + 1, y + 4, shadow);
            context.fill(tipX - 4, y - 2, tipX, y - 1, color);
            context.fill(tipX - 3, y - 1, tipX + 1, y + 2, color);
            context.fill(tipX - 4, y + 2, tipX, y + 3, color);
        } else {
            context.fill(tipX + 1, y - 1, tipX + 5, y, shadow);
            context.fill(tipX, y, tipX + 4, y + 3, shadow);
            context.fill(tipX + 1, y + 3, tipX + 5, y + 4, shadow);
            context.fill(tipX, y - 2, tipX + 4, y - 1, color);
            context.fill(tipX - 1, y - 1, tipX + 3, y + 2, color);
            context.fill(tipX, y + 2, tipX + 4, y + 3, color);
        }
    }

    private void drawFlowPacket(
        DrawContext context,
        int startX,
        int startY,
        int elbowX,
        int endY,
        int endX,
        AutomationWorkspaceTelemetryProjection.Flow flow,
        boolean ongoing,
        long age,
        long now,
        int color
    ) {
        int firstLength = Math.abs(elbowX - startX);
        int verticalLength = Math.abs(endY - startY);
        int lastLength = Math.abs(endX - elbowX);
        int total = Math.max(1, firstLength + verticalLength + lastLength);
        double progress = ongoing
            ? ((now / 18L) % total) / (double) total
            : Math.min(1.0D, age / (double) FLOW_SIGNAL_VISIBLE_MILLIS);
        int distance = Math.min(total, (int) Math.round(progress * total));
        int packet = flow.amount() >= 8192L ? 5 : flow.amount() > 0L ? 4 : 3;

        if (distance <= firstLength) {
            int sign = elbowX >= startX ? 1 : -1;
            int x = startX + sign * distance;
            drawFlowPacketGlyph(context, x, startY, flow.kind(), packet, color, now);
            return;
        }
        distance -= firstLength;
        if (distance <= verticalLength) {
            int sign = endY >= startY ? 1 : -1;
            int y = startY + sign * distance;
            drawFlowPacketGlyph(context, elbowX, y, flow.kind(), packet, color, now);
            return;
        }
        distance -= verticalLength;
        int sign = endX >= elbowX ? 1 : -1;
        int x = elbowX + sign * Math.min(distance, lastLength);
        drawFlowPacketGlyph(context, x, endY, flow.kind(), packet, color, now);
    }

    private void drawFlowPacketGlyph(
        DrawContext context,
        int centerX,
        int centerY,
        AutomationWorkspaceTelemetryProjection.FlowKind kind,
        int packet,
        int color,
        long now
    ) {
        AutomationWorkspaceTelemetryProjection.FlowKind safe =
            kind == null ? AutomationWorkspaceTelemetryProjection.FlowKind.NONE : kind;
        int half = Math.max(1, packet / 2);
        int pulse = (int) ((now / 120L) % 2L);

        switch (safe) {
            case REQUEST -> {
                drawPixelRectWithShadow(context, centerX - 1, centerY - half, centerX + 2, centerY + half + 1, color);
                drawPixelRectWithShadow(context, centerX - half, centerY - 1, centerX + half + 1, centerY + 2, color);
            }
            case CONTEXT, KNOWLEDGE -> {
                drawPixelRectWithShadow(context, centerX - half, centerY - half, centerX, centerY + half + 1, color);
                drawPixelRectWithShadow(context, centerX + 1, centerY - half, centerX + half + 1, centerY + half + 1, color);
            }
            case PLAN -> {
                drawPixelRectWithShadow(context, centerX - half, centerY - half, centerX + half + 1, centerY - half + 1, color);
                drawPixelRectWithShadow(context, centerX - half + 1, centerY, centerX + half + 1, centerY + 1, color);
                drawPixelRectWithShadow(context, centerX - half + 2, centerY + half, centerX + half + 1, centerY + half + 1, color);
            }
            case THINKING -> {
                drawPixelRectWithShadow(context, centerX - half, centerY - 1, centerX + half + 1, centerY + 2, color);
                drawPixelRectWithShadow(context, centerX - 1, centerY - half, centerX + 2, centerY + half + 1, withAlpha(color, 210));
                if (pulse > 0) drawPixelRectWithShadow(context, centerX + half, centerY - half, centerX + half + 2, centerY - half + 2, color);
            }
            case SKILL -> {
                // Open-book glyph: procedural capability selected/loaded by the model.
                drawPixelRectWithShadow(context, centerX - half, centerY - half, centerX - 1, centerY + half + 1, color);
                drawPixelRectWithShadow(context, centerX + 1, centerY - half, centerX + half + 1, centerY + half + 1, color);
                drawPixelRectWithShadow(context, centerX, centerY - half + 1, centerX + 1, centerY + half, withAlpha(color, 190));
            }
            case TOOL -> {
                drawPixelRectWithShadow(context, centerX - 1, centerY - half, centerX + 1, centerY + half + 1, color);
                drawPixelRectWithShadow(context, centerX - half, centerY - 1, centerX + half + 1, centerY + 1, color);
            }
            case EXECUTION -> {
                drawPixelRectWithShadow(context, centerX - half, centerY - 1, centerX + half, centerY + 1, color);
                drawPixelRectWithShadow(context, centerX + half - 1, centerY - 2 - pulse, centerX + half + 1, centerY + 3 + pulse, color);
            }
            case APPROVAL, VALIDATION -> {
                drawPixelRectWithShadow(context, centerX - half, centerY, centerX - 1, centerY + 2, color);
                drawPixelRectWithShadow(context, centerX - 1, centerY + 1, centerX + 1, centerY + 3, color);
                drawPixelRectWithShadow(context, centerX, centerY - 2, centerX + half + 1, centerY + 1, color);
            }
            case SERIALIZATION -> {
                for (int row = -1; row <= 1; row++) {
                    drawPixelRectWithShadow(context, centerX - half, centerY + row * 2,
                        centerX + half + 1, centerY + row * 2 + 1, color);
                }
            }
            case OUTPUT -> {
                drawPixelRectWithShadow(context, centerX - 1, centerY - half, centerX + 2, centerY + half + 1, color);
                drawPixelRectWithShadow(context, centerX - half, centerY - 1, centerX + half + 1, centerY + 2, withAlpha(color, 220));
            }
            case QUEUE -> {
                drawPixelRectWithShadow(context, centerX - half, centerY - half, centerX + half + 1, centerY - half + 1, color);
                drawPixelRectWithShadow(context, centerX - 1, centerY - 1, centerX + 2, centerY + 2, color);
                drawPixelRectWithShadow(context, centerX - half, centerY + half, centerX + half + 1, centerY + half + 1, color);
            }
            case NONE -> drawPixelRectWithShadow(context, centerX - half, centerY - half,
                centerX + half + 1, centerY + half + 1, color);
        }
    }

    private void drawFlowTypeMarker(
        DrawContext context,
        int endX,
        int endY,
        AutomationWorkspaceTelemetryProjection.Flow flow,
        int color
    ) {
        String code = flowCode(flow.kind());
        if (code.isBlank()) return;
        int markerX = endX - Math.max(8, this.textRenderer.getWidth(code) + 4);
        drawPixelRectWithShadow(context, markerX, endY - 5, endX - 2, endY + 5, withAlpha(uiColorContentBase, 180));
        context.drawTextWithShadow(this.textRenderer, code, markerX + 2, endY - 4, withAlpha(color, 220));
    }

    private void drawFlowLabel(
        DrawContext context,
        int startX,
        int startY,
        int elbowX,
        AutomationWorkspaceTelemetryProjection.Flow flow
    ) {
        String label = flow.label();
        if (label == null || label.isBlank()) return;
        int x = Math.min(startX, elbowX) + 6;
        int y = startY - this.textRenderer.fontHeight - 3;
        context.drawTextWithShadow(this.textRenderer, label, x, y, 0xFF9CA8B8);
    }

    private String flowCode(AutomationWorkspaceTelemetryProjection.FlowKind kind) {
        return switch (kind == null ? AutomationWorkspaceTelemetryProjection.FlowKind.NONE : kind) {
            case REQUEST -> "REQ";
            case CONTEXT -> "CTX";
            case PLAN -> "PLAN";
            case THINKING -> "THINK";
            case SKILL -> "SKILL";
            case TOOL -> "TOOL";
            case EXECUTION -> "EXEC";
            case APPROVAL -> "OK?";
            case SERIALIZATION -> "SER";
            case OUTPUT -> "OUT";
            case QUEUE -> "WAIT";
            case VALIDATION -> "CHK";
            case KNOWLEDGE -> "KNOW";
            case NONE -> "";
        };
    }

    private boolean isActiveCanvasState(ModelActivityState state) {
        ModelActivityState safe = state == null ? ModelActivityState.IDLE : state;
        return SlidingStatusText.working(safe.id()) || safe == ModelActivityState.AWAITING_APPROVAL;
    }

    private boolean isNodeActivelyWorking(NotebookNode node) {
        if (node == null || !isActiveCanvasState(node.state)) {
            return false;
        }
        if (this.activeCanvasNodeIds.contains(node.id)) {
            return true;
        }
        if (!node.traceId.isBlank()) {
            return this.activeWorkspaceTraceIds.contains(node.traceId);
        }
        return true;
    }

    private void renderNote(
        DrawContext context,
        LayoutEntry entry,
        int mouseX,
        int mouseY,
        int viewportTop,
        int viewportBottom
    ) {
        NotebookNode node = entry.node();
        boolean selected = node.id.equals(this.selectedNodeId);
        boolean hovered = mouseX >= entry.x() && mouseX < entry.right()
            && mouseY >= entry.y() && mouseY < entry.bottom();
        int stateColor = ModelSemanticPalette.color(node.state);
        int borderColor = selected ? withAlpha(stateColor, 238)
            : hovered ? withAlpha(stateColor, 178) : withAlpha(notebookBorderColor(), 112);
        long now = System.currentTimeMillis();

        // A data pane, not a paper note or faux terminal. Header glass is deliberately
        // translucent so the shared workspace grid remains visible through the pane.
        int paneBackground = withAlpha(uiColorContentBase, selected ? 172 : hovered ? 146 : 122);
        int headerBackground = withAlpha(uiColorContentBase, selected ? 144 : hovered ? 116 : 94);
        context.fill(entry.x(), entry.y(), entry.right(), entry.bottom(), paneBackground);
        context.fill(entry.x(), entry.y(), entry.right(), entry.y() + 3, withAlpha(stateColor, selected ? 225 : 164));
        context.fill(entry.x(), entry.y() + 3, entry.right(), entry.y() + 26, headerBackground);
        context.drawBorder(entry.x(), entry.y(), entry.width(), entry.height(), borderColor);

        renderPaneActivityRail(context, entry, node, stateColor, now);

        int portY = connectionPortY(entry);
        renderPaneSockets(context, entry, node, stateColor, selected, portY, now);

        int lineHeight = this.textRenderer.fontHeight + 2;
        int indicatorWidth = renderNodeActivityIndicator(context, node, entry.x() + 9, entry.y() + 8);
        int titleX = entry.x() + 11 + indicatorWidth;
        int titleWidth = Math.max(24, entry.width() - (titleX - entry.x()) - 36);
        int cursorY = entry.y() + 8;

        List<String> titleLines = wrapPlain(node.title, titleWidth);
        if (titleLines.isEmpty()) titleLines = List.of(node.title);
        for (String line : titleLines) {
            if (rowVisible(cursorY, lineHeight, viewportTop, viewportBottom)) {
                context.drawTextWithShadow(this.textRenderer, line, titleX, cursorY,
                    selected ? 0xFFF5F7FA : 0xFFDCE2EA);
            }
            cursorY += lineHeight;
        }

        if (!node.children.isEmpty() || !node.className.isBlank() && !node.membersLoaded) {
            boolean expanded = this.expandedNodes.contains(node.id);
            if (rowVisible(entry.y() + 8, lineHeight, viewportTop, viewportBottom)) {
                context.drawTextWithShadow(this.textRenderer, expanded ? "[-]" : "[+]",
                    entry.right() - 28, entry.y() + 8, stateColor);
            }
        }

        int metaY = Math.max(entry.y() + 29, cursorY + 2);
        int kindColor = systemKindColor(node.kind, stateColor);
        String statusLabel = friendly(node.state.id());
        String flowLabel = node.inboundFlow == null ? "" : flowCode(node.inboundFlow.kind());
        String inbound = flowLabel.isBlank() ? "IN" : "IN " + flowLabel;
        String out = "OUT";

        // Contract row: physical input and output identity are kept apart so there is
        // never a collision regardless of subsystem/status text length.
        if (rowVisible(metaY, lineHeight, viewportTop, viewportBottom)) {
            context.drawTextWithShadow(this.textRenderer, inbound, entry.x() + 10, metaY, withAlpha(stateColor, 220));
            context.drawTextWithShadow(this.textRenderer, out,
                entry.right() - this.textRenderer.getWidth(out) - 10, metaY, withAlpha(stateColor, 198));
        }

        int identityY = metaY + lineHeight;
        Text statusText = isNodeActivelyWorking(node)
            ? SlidingStatusText.styled(statusLabel, node.state.id(), stateColor)
            : Text.literal(statusLabel);
        String separator = "  |  ";
        int kindWidth = this.textRenderer.getWidth(node.kind);
        int separatorWidth = this.textRenderer.getWidth(separator);
        int statusWidth = this.textRenderer.getWidth(statusText);
        int bodyWidth = Math.max(24, entry.width() - 20);
        boolean statusFitsSameRow = kindWidth + separatorWidth + statusWidth <= bodyWidth;

        if (rowVisible(identityY, lineHeight, viewportTop, viewportBottom)) {
            context.drawTextWithShadow(this.textRenderer, node.kind, entry.x() + 10, identityY, withAlpha(kindColor, 235));
            if (statusFitsSameRow) {
                int statusX = entry.x() + 10 + kindWidth + separatorWidth;
                context.drawTextWithShadow(this.textRenderer, separator,
                    entry.x() + 10 + kindWidth, identityY, 0xFF596576);
                context.drawTextWithShadow(this.textRenderer, statusText, statusX, identityY, stateColor);
            }
        }

        cursorY = identityY + lineHeight;
        if (!statusFitsSameRow) {
            if (rowVisible(cursorY, lineHeight, viewportTop, viewportBottom)) {
                context.drawTextWithShadow(this.textRenderer, statusText, entry.x() + 10, cursorY, stateColor);
            }
            cursorY += lineHeight;
        }
        if (node.startedAtMillis > 0L) {
            renderPaneTiming(context, entry, node, cursorY, lineHeight, stateColor, viewportTop, viewportBottom);
            cursorY += lineHeight;
        }
        cursorY += 3;

        if (!node.summary.isBlank()) {
            List<String> summaryLines = wrapPlain(node.summary, Math.max(24, entry.width() - 20));
            int summaryStart = visibleFixedLineStart(cursorY, lineHeight, summaryLines.size(), viewportTop);
            int summaryEnd = visibleFixedLineEnd(cursorY, lineHeight, summaryLines.size(), viewportBottom);
            for (int i = summaryStart; i < summaryEnd; i++) {
                int y = cursorY + i * lineHeight;
                renderMeaningfulDetailLine(context, summaryLines.get(i), entry.x() + 10, y, entry.width() - 20, stateColor);
            }
            cursorY += summaryLines.size() * lineHeight;
        }

        List<List<TelemetryText>> typedDetails = notebookVisibleTypedDetailLines(node);
        List<String> details = typedDetails.isEmpty() ? notebookVisibleDetailLines(node) : List.of();
        if (!typedDetails.isEmpty() || !details.isEmpty()) {
            cursorY += 3;
            if (rowVisible(cursorY, 1, viewportTop, viewportBottom)) {
                context.fill(entry.x() + 10, cursorY, entry.right() - 10, cursorY + 1,
                    withAlpha(borderColor, selected ? 110 : 66));
            }
            cursorY += 5;
            if (!typedDetails.isEmpty()) {
                int start = visibleFixedLineStart(cursorY, DETAIL_LINE_HEIGHT, typedDetails.size(), viewportTop);
                int end = visibleFixedLineEnd(cursorY, DETAIL_LINE_HEIGHT, typedDetails.size(), viewportBottom);
                for (int i = start; i < end; i++) {
                    int y = cursorY + i * DETAIL_LINE_HEIGHT;
                    renderTypedTelemetryLine(context, typedDetails.get(i), entry.x() + 10, y, entry.width() - 20, stateColor);
                }
                cursorY += typedDetails.size() * DETAIL_LINE_HEIGHT;
            } else {
                int start = visibleFixedLineStart(cursorY, DETAIL_LINE_HEIGHT, details.size(), viewportTop);
                int end = visibleFixedLineEnd(cursorY, DETAIL_LINE_HEIGHT, details.size(), viewportBottom);
                for (int i = start; i < end; i++) {
                    int y = cursorY + i * DETAIL_LINE_HEIGHT;
                    renderMeaningfulDetailLine(context, details.get(i), entry.x() + 10, y, entry.width() - 20, stateColor);
                }
                cursorY += details.size() * DETAIL_LINE_HEIGHT;
            }
        }
    }

    private void renderPaneTiming(
        DrawContext context,
        LayoutEntry entry,
        NotebookNode node,
        int y,
        int lineHeight,
        int stateColor,
        int viewportTop,
        int viewportBottom
    ) {
        if (node == null || node.startedAtMillis <= 0L || !rowVisible(y, lineHeight, viewportTop, viewportBottom)) return;
        long liveDuration = node.temporalActive
                ? Math.max(node.durationMillis, System.currentTimeMillis() - node.startedAtMillis)
                : node.durationMillis;
        String durationLabel = formatDurationCompact(liveDuration);
        String concurrency = node.concurrentPeers > 0 ? "  PAR +" + node.concurrentPeers : "";
        String label = (node.temporalActive ? "LIVE " : "TIME ") + durationLabel + concurrency;
        int labelWidth = this.textRenderer.getWidth(label);
        int barLeft = entry.x() + 10 + labelWidth + 7;
        int barRight = entry.right() - 10;
        int centerY = y + Math.max(2, this.textRenderer.fontHeight / 2);
        context.drawTextWithShadow(this.textRenderer, label, entry.x() + 10, y,
                node.concurrentPeers > 0 ? withAlpha(stateColor, 230) : 0xFF7E8998);
        if (barRight <= barLeft + 4) return;
        context.fill(barLeft, centerY, barRight, centerY + 2, withAlpha(notebookBorderColor(), 100));
        double normalized = Math.log1p(Math.max(0L, liveDuration)) / Math.log1p(60_000.0D);
        int fill = Math.max(2, (int) Math.round((barRight - barLeft) * Math.min(1.0D, normalized)));
        context.fill(barLeft, centerY, Math.min(barRight, barLeft + fill), centerY + 2, withAlpha(stateColor, 220));
        if (node.temporalActive) {
            int pulse = (int) ((System.currentTimeMillis() / 110L) % 3L);
            int x = Math.min(barRight - 1, barLeft + fill - 1);
            context.fill(Math.max(barLeft, x - pulse), centerY - 1, Math.min(barRight, x + 2), centerY + 3, withAlpha(stateColor, 180));
        }
    }

    private static String formatDurationCompact(long millis) {
        long safe = Math.max(0L, millis);
        if (safe < 1_000L) return safe + "ms";
        if (safe < 10_000L) return String.format(java.util.Locale.ROOT, "%.2fs", safe / 1000.0D);
        if (safe < 60_000L) return String.format(java.util.Locale.ROOT, "%.1fs", safe / 1000.0D);
        long minutes = safe / 60_000L;
        long seconds = (safe % 60_000L) / 1_000L;
        return minutes + "m" + seconds + "s";
    }

    private boolean rowVisible(int y, int height, int viewportTop, int viewportBottom) {
        return y + Math.max(1, height) >= viewportTop && y <= viewportBottom;
    }

    private int visibleFixedLineStart(int startY, int lineHeight, int count, int viewportTop) {
        if (count <= 0) return 0;
        return Math.max(0, Math.min(count, Math.floorDiv(viewportTop - startY, Math.max(1, lineHeight))));
    }

    private int visibleFixedLineEnd(int startY, int lineHeight, int count, int viewportBottom) {
        if (count <= 0) return 0;
        int height = Math.max(1, lineHeight);
        return Math.max(0, Math.min(count, Math.floorDiv(viewportBottom - startY, height) + 2));
    }

    private void renderPaneActivityRail(
        DrawContext context,
        LayoutEntry entry,
        NotebookNode node,
        int color,
        long now
    ) {
        if (node == null || !isNodeActivelyWorking(node)) return;
        int span = Math.max(16, entry.width() - 12);
        int travel = Math.max(1, span - 18);
        int x = entry.x() + 6 + (int) ((now / 22L) % travel);
        drawPixelRectWithShadow(context, x, entry.y() + 1, x + 12, entry.y() + 3, withAlpha(color, 235));
    }

    private void renderPaneSockets(
        DrawContext context,
        LayoutEntry entry,
        NotebookNode node,
        int stateColor,
        boolean selected,
        int portY,
        long now
    ) {
        int socketColor = selected ? stateColor : withAlpha(stateColor, 205);
        int socketBackground = withAlpha(uiColorContentBase, 230);
        drawPixelRectWithShadow(context, entry.x() - 5, portY - 4, entry.x() + 2, portY + 5, socketBackground);
        context.drawBorder(entry.x() - 5, portY - 4, 7, 9, socketColor);
        drawPixelRectWithShadow(context, entry.right() - 2, portY - 4, entry.right() + 5, portY + 5, socketBackground);
        context.drawBorder(entry.right() - 2, portY - 4, 7, 9, socketColor);

        AutomationWorkspaceTelemetryProjection.Flow flow = node == null ? null : node.inboundFlow;
        if (flow != null && flow.kind() != AutomationWorkspaceTelemetryProjection.FlowKind.NONE) {
            long age = Math.max(0L, now - flow.timestampMillis());
            if (age <= FLOW_SIGNAL_VISIBLE_MILLIS) {
                int phase = (int) ((now / 90L) % 3L);
                int alpha = 110 + phase * 45;
                context.drawBorder(entry.x() - 6, portY - 5, 9, 11, withAlpha(stateColor, alpha));
            }
        }
        if (node != null && isNodeActivelyWorking(node)) {
            int phase = (int) ((now / 100L) % 3L);
            context.fill(entry.right(), portY - 1, entry.right() + 2 + phase, portY + 2, withAlpha(stateColor, 150 + phase * 30));
        }
    }

    private void drawPixelRectWithShadow(
        DrawContext context,
        int left,
        int top,
        int right,
        int bottom,
        int color
    ) {
        if (right <= left || bottom <= top) return;
        context.fill(left + 1, top + 1, right + 1, bottom + 1, 0x88000000);
        context.fill(left, top, right, bottom, color);
    }

    private List<String> selectedDetailLines(NotebookNode node, int maximumVisibleLines) {
        if (node == null) return List.of();
        int limit = maximumVisibleLines == Integer.MAX_VALUE
            ? Integer.MAX_VALUE
            : Math.max(1, maximumVisibleLines + 1);
        List<String> raw = new ArrayList<>();
        appendWrappedLimited(raw, node.detail, NOTE_WIDTH - 20, limit);
        if (raw.size() < limit && !node.source.isBlank()) {
            appendWrappedLimited(raw, "source: " + node.source, NOTE_WIDTH - 20, limit);
        }
        if (raw.size() < limit && !node.traceId.isBlank()) {
            appendWrappedLimited(raw, "trace: " + node.traceId, NOTE_WIDTH - 20, limit);
        }
        return List.copyOf(raw);
    }

    private void appendWrappedLimited(List<String> out, String value, int width, int limit) {
        if (out == null || value == null || value.isBlank() || out.size() >= limit) return;
        if (limit == Integer.MAX_VALUE) {
            out.addAll(wrapPlain(value, width));
            return;
        }
        int safeWidth = Math.max(24, width);
        for (String logical : value.split("\\R", -1)) {
            List<OrderedText> wrapped = this.textRenderer.wrapLines(Text.literal(logical), safeWidth);
            if (wrapped.isEmpty()) {
                out.add("");
                if (out.size() >= limit) return;
                continue;
            }
            for (OrderedText ordered : wrapped) {
                StringBuilder plain = new StringBuilder();
                ordered.accept((index, style, codePoint) -> {
                    plain.appendCodePoint(codePoint);
                    return true;
                });
                out.add(plain.toString());
                if (out.size() >= limit) return;
            }
        }
    }

    private List<String> wrapPlain(String value, int width) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        int safeWidth = Math.max(24, width);
        TextWrapKey key = new TextWrapKey(value, safeWidth);
        List<String> cached = this.textWrapCache.get(key);
        if (cached != null) return cached;

        List<String> lines = new ArrayList<>();
        for (String logical : value.split("\\R", -1)) {
            List<OrderedText> wrapped = this.textRenderer.wrapLines(Text.literal(logical), safeWidth);
            if (wrapped.isEmpty()) {
                lines.add("");
                continue;
            }
            for (OrderedText ordered : wrapped) {
                StringBuilder plain = new StringBuilder();
                ordered.accept((index, style, codePoint) -> {
                    plain.appendCodePoint(codePoint);
                    return true;
                });
                lines.add(plain.toString());
            }
        }
        List<String> result = List.copyOf(lines);
        this.textWrapCache.put(key, result);
        return result;
    }

    private void renderFileExplorerChrome(DrawContext context, int canvasBottom) {
        MinecraftClient client = MinecraftClient.getInstance();
        int topBarBackground = withAlpha(uiColorContentBase, 176);
        int topPanelBackground = withAlpha(uiColorContentBase, 196);
        int panelBackground = withAlpha(uiColorContentBase, 124);
        int rightPanelBackground = withAlpha(uiColorContentBase, 82);
        int footerTop = Math.max(CONTENT_TOP + 1, this.height - 16);

        KoilScreenBackgrounds.render(context, client, this.width, this.height);
        context.fill(0, 0, this.width, 40, topPanelBackground);
        context.drawText(this.textRenderer, "Version - " + Main.version(), this.width - 100, 10,
            new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.getMatrices().push();
        context.getMatrices().scale(0.5F, 0.5F, 1.0F);
        context.drawText(this.textRenderer, "By: SpiritXIV", (int) ((this.width - 100) / 0.5F), (int) (20 / 0.5F),
            new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, "Koil", 34, 6, new Color(uiColorHeaderTitleText, true).getRGB(), true);
        context.drawTexture(LOGO_TEXTURE, 10, 5, 0, 0, 22, 22, 22, 22);
        context.getMatrices().push();
        context.getMatrices().scale(0.5F, 0.5F, 1.0F);
        context.drawText(this.textRenderer, "Manager Menu - InDEV", 68, 35,
            new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        if (client != null && KoilScreenBackgrounds.canRender(client)) {
            context.fill(0, 0, this.width, this.height, KoilScreenBackgrounds.overlayColor(client));
        }
        context.drawBorder(0, 0, this.width, this.height, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, 40, this.width, HEADER_BOTTOM, new Color(uiColorHeader, true).getRGB());
        context.fill(0, 40, this.width, HEADER_BOTTOM, topBarBackground);
        context.drawBorder(0, 40, this.width, HEADER_BOTTOM - 40, new Color(uiColorBackgroundBorder, true).getRGB());

        context.fill(0, CONTENT_TOP, SIDEBAR_WIDTH, footerTop, panelBackground);
        context.drawBorder(0, CONTENT_TOP, SIDEBAR_WIDTH, Math.max(1, footerTop - CONTENT_TOP),
            new Color(uiColorBackgroundBorder, true).getRGB());

        int rightX = SIDEBAR_WIDTH + PANEL_GAP;
        context.fill(rightX, CONTENT_TOP, this.width, footerTop, rightPanelBackground);
        context.drawBorder(rightX, CONTENT_TOP, Math.max(1, this.width - rightX), Math.max(1, footerTop - CONTENT_TOP),
            new Color(uiColorBackgroundBorder, true).getRGB());

        context.fill(0, footerTop, this.width, this.height, new Color(uiColorFooter, true).getRGB());
        context.fill(0, footerTop, this.width, Math.min(this.height, footerTop + 3), new Color(uiColorFooterStripe, true).getRGB());
    }

    private void renderTopBar(DrawContext context, int mouseX, int mouseY) {
        TopBarLayout layout = new TopBarLayout(this.textRenderer, this.width);
        List<String> right = topBarActionLabels();

        renderTopBarButton(context, mouseX, mouseY, TopBarLayout.LEFT_MARGIN, TOP_BAR_BACK_LABEL, this::close, true);
        for (int index = 0; index < right.size(); index++) {
            String label = right.get(index);
            Runnable action;
            if (label.startsWith(TOP_BAR_FILTER_PREFIX)) {
                action = this::cycleTraceFilter;
            } else if (label.startsWith(TOP_BAR_VIEW_PREFIX)) {
                action = this::cycleWorkspaceViewMode;
            } else if (label.startsWith(TOP_BAR_DETAIL_PREFIX)) {
                action = this::cycleTelemetryDetailMode;
            } else {
                action = switch (label) {
                    case TOP_BAR_RESET_LABEL -> this::resetNotebookView;
                    case TOP_BAR_CENTER_LABEL -> this::centerNotebookView;
                    case TOP_BAR_EXPORT_LABEL -> this::exportSelected;
                    case TOP_BAR_COPY_LABEL -> this::copySelectedNote;
                    case TOP_BAR_COMPARE_LABEL -> this::captureComparisonRequest;
                    default -> () -> { };
                };
            }
            renderTopBarButton(context, mouseX, mouseY, layout.rightButtonX(right, index), label, action, true);
        }
    }

    private List<String> topBarActionLabels() {
        return List.of(
            TOP_BAR_RESET_LABEL,
            TOP_BAR_CENTER_LABEL,
            TOP_BAR_COPY_LABEL,
            TOP_BAR_EXPORT_LABEL,
            TOP_BAR_COMPARE_LABEL,
            TOP_BAR_VIEW_PREFIX + this.workspaceViewMode.label,
            TOP_BAR_DETAIL_PREFIX + this.telemetryDetailMode.label,
            TOP_BAR_FILTER_PREFIX + this.traceFilter.label()
        );
    }

    private void updateTopBarSearchGeometry() {
        if (this.notebookSearchInput == null || this.textRenderer == null) {
            return;
        }
        TopBarLayout layout = new TopBarLayout(this.textRenderer, this.width);
        int x = layout.searchFieldX(TOP_BAR_BACK_LABEL);
        int width = layout.searchFieldWidth(TOP_BAR_BACK_LABEL, topBarActionLabels(), 220);
        this.notebookSearchInput.setX(x);
        this.notebookSearchInput.setY(TopBarLayout.SEARCH_FIELD_Y);
        this.notebookSearchInput.setWidth(width);
        this.searchDropdownX = x;
        this.searchDropdownY = TopBarLayout.SEARCH_FIELD_Y + TopBarLayout.SEARCH_FIELD_HEIGHT + 2;
        this.searchDropdownWidth = width;
    }

    private void renderTopBarButton(
        DrawContext context,
        int mouseX,
        int mouseY,
        int x,
        String label,
        Runnable action,
        boolean enabled
    ) {
        TopBarLayout layout = new TopBarLayout(this.textRenderer, this.width);
        int width = layout.buttonWidth(label);
        int top = TopBarLayout.BUTTON_Y;
        int bottom = top + TopBarLayout.BUTTON_HEIGHT;
        boolean hovered = enabled && mouseX >= x && mouseX < x + width && mouseY >= top && mouseY < bottom;
        context.fill(x, top, x + width, bottom, withAlpha(uiColorContentBase, hovered ? 214 : 176));
        context.drawBorder(x, top, width, TopBarLayout.BUTTON_HEIGHT, new Color(uiColorBackgroundBorder, true).getRGB());
        int textColor = enabled
            ? hovered ? new Color(uiColorHeaderTitleText, true).getRGB() : new Color(uiColorContentBaseTitleText, true).getRGB()
            : 0xFF4C5562;
        context.drawTextWithShadow(this.textRenderer, label,
            x + (width - this.textRenderer.getWidth(label)) / 2,
            top + (TopBarLayout.BUTTON_HEIGHT - this.textRenderer.fontHeight) / 2,
            textColor);
        this.actionHits.add(new ActionHit(x, top, x + width, bottom, enabled, action));
    }

    private void updateComposerGeometry() {
        int footerTop = Math.max(CONTENT_TOP + 40, this.height - 16);
        this.chatBorderX = 3;
        this.chatBorderWidth = Math.max(96, SIDEBAR_WIDTH - 9 - STOP_ICON_SIZE - STOP_ICON_GAP);
        this.chatFieldX = this.chatBorderX + COMPOSER_HORIZONTAL_PADDING;
        this.chatFieldWidth = Math.max(48, this.chatBorderWidth - (COMPOSER_HORIZONTAL_PADDING * 2));

        List<ComposerVisualLine> lines = composerVisualLines(chatText());
        int sourceLines = Math.max(1, lines.size());
        int visibleLines = Math.max(COMPOSER_MIN_LINES, Math.min(COMPOSER_MAX_LINES, sourceLines));
        int maxFirst = Math.max(0, sourceLines - visibleLines);
        this.composerScrollLine = Math.max(0, Math.min(maxFirst, this.composerScrollLine));

        int contentHeight = 0;
        for (int index = this.composerScrollLine;
             index < lines.size() && index < this.composerScrollLine + visibleLines;
             index++) {
            contentHeight += composerVisualLineHeight(lines.get(index));
        }
        int baseLineHeight = this.textRenderer == null ? 11 : this.textRenderer.fontHeight + 2;
        int renderedLines = Math.min(visibleLines, Math.max(0, lines.size() - this.composerScrollLine));
        contentHeight += Math.max(0, visibleLines - renderedLines) * baseLineHeight;

        this.chatBorderHeight = 8 + Math.max(baseLineHeight * COMPOSER_MIN_LINES, contentHeight);
        this.chatBorderY = Math.max(CONTENT_TOP + 30, footerTop - this.chatBorderHeight - 7);
        this.chatFieldY = this.chatBorderY + Math.max(3, (this.chatBorderHeight - contentHeight) / 2);
        this.stopIconX = this.chatBorderX + this.chatBorderWidth + STOP_ICON_GAP;
        this.stopIconY = this.chatBorderY + Math.max(0, (this.chatBorderHeight - STOP_ICON_SIZE) / 2);

        this.chatStreamTop = CONTENT_TOP + 4;
        this.chatStreamBottom = Math.max(this.chatStreamTop + 24, this.chatBorderY - 3);
    }

    private int composerLineHeight(String line) {
        int normal = this.textRenderer == null ? 11 : this.textRenderer.fontHeight + 2;
        if (this.textRenderer == null) {
            return normal;
        }
        return Math.max(normal, RichChatAttachmentRenderer.liveDraftFormattedLineHeight(this.textRenderer, line));
    }

    private int composerVisualLineHeight(ComposerVisualLine line) {
        if (line == null) {
            return this.textRenderer == null ? 11 : this.textRenderer.fontHeight + 2;
        }
        return composerLineHeight(line.hardLine());
    }

    private List<ComposerVisualLine> composerVisualLines(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        List<ComposerVisualLine> out = new ArrayList<>();
        int globalStart = 0;
        int lineStart = 0;
        while (lineStart <= normalized.length()) {
            int newline = normalized.indexOf('\n', lineStart);
            int lineEnd = newline < 0 ? normalized.length() : newline;
            String hardLine = normalized.substring(lineStart, lineEnd);
            appendComposerWrappedLine(out, hardLine, globalStart);
            if (newline < 0) {
                break;
            }
            globalStart += hardLine.length() + 1;
            lineStart = newline + 1;
            if (lineStart == normalized.length()) {
                appendComposerWrappedLine(out, "", globalStart);
                break;
            }
        }
        if (out.isEmpty()) {
            out.add(new ComposerVisualLine("", 0, 0, 0));
        }
        return List.copyOf(out);
    }

    private void appendComposerWrappedLine(List<ComposerVisualLine> out, String hardLine, int globalStart) {
        String line = hardLine == null ? "" : hardLine;
        if (line.isEmpty()) {
            out.add(new ComposerVisualLine("", globalStart, 0, 0));
            return;
        }
        int from = 0;
        while (from < line.length()) {
            int to = composerWrappedEnd(line, from, Math.max(8, this.chatFieldWidth));
            if (to < line.length()) {
                int whitespace = -1;
                for (int index = to - 1; index > from; index--) {
                    if (Character.isWhitespace(line.charAt(index))) {
                        whitespace = index + 1;
                        break;
                    }
                }
                if (whitespace > from) {
                    to = whitespace;
                }
            }
            if (to <= from) {
                int control = RichChatSectionFormatting.codeLengthAt(line, from);
                to = Math.min(line.length(), from + (control > 0 ? control : Character.charCount(line.codePointAt(from))));
            }
            out.add(new ComposerVisualLine(line, globalStart, from, to));
            from = to;
        }
    }

    private int composerWrappedEnd(String line, int from, int maximumWidth) {
        if (line == null || from >= line.length()) return Math.max(0, from);
        int cursor = Math.max(0, from);
        int lastGood = cursor;
        while (cursor < line.length()) {
            int control = RichChatSectionFormatting.codeLengthAt(line, cursor);
            int next = Math.min(line.length(), cursor + (control > 0
                ? control
                : Character.charCount(line.codePointAt(cursor))));
            int width = VanillaBackedChatInputController.styledRangeWidth(
                this.textRenderer,
                MinecraftClient.getInstance(),
                line,
                from,
                next,
                next
            );
            if (width > maximumWidth && lastGood > from) {
                break;
            }
            lastGood = next;
            cursor = next;
            if (width > maximumWidth) {
                break;
            }
        }
        return Math.max(from, lastGood);
    }

    private int composerCursorVisualLine(List<ComposerVisualLine> lines, int cursor) {
        if (lines == null || lines.isEmpty()) return 0;
        int safeCursor = Math.max(0, Math.min(chatText().length(), cursor));
        for (int index = 0; index < lines.size(); index++) {
            ComposerVisualLine line = lines.get(index);
            int start = line.globalStart();
            int end = line.globalEnd();
            if (safeCursor < start || safeCursor > end) continue;
            if (safeCursor == end && index + 1 < lines.size() && lines.get(index + 1).globalStart() == safeCursor) {
                continue;
            }
            return index;
        }
        return Math.max(0, lines.size() - 1);
    }

    private void renderComposer(DrawContext context, int mouseX, int mouseY) {
        String text = chatText();
        List<ComposerVisualLine> lines = composerVisualLines(text);
        int visibleRows = Math.max(COMPOSER_MIN_LINES, Math.min(COMPOSER_MAX_LINES, lines.size()));
        int cursor = Math.max(0, Math.min(this.chatInput == null ? 0 : this.chatInput.getCursor(), text.length()));
        int cursorLine = composerCursorVisualLine(lines, cursor);
        int maxFirst = Math.max(0, lines.size() - visibleRows);
        this.composerScrollLine = Math.max(0, Math.min(maxFirst, this.composerScrollLine));
        if (!this.composerManualScroll) {
            if (cursorLine < this.composerScrollLine) {
                this.composerScrollLine = cursorLine;
            } else if (cursorLine >= this.composerScrollLine + visibleRows) {
                this.composerScrollLine = Math.max(0, cursorLine - visibleRows + 1);
            }
        }

        int border = this.composerFocused ? ModelSemanticPalette.color(ModelActivityState.WRITING) : notebookBorderColor();
        context.drawBorder(this.chatBorderX, this.chatBorderY, this.chatBorderWidth, this.chatBorderHeight, border);

        if (text.isBlank() && !this.composerFocused) {
            int placeholderY = this.chatBorderY + Math.max(1, (this.chatBorderHeight - this.textRenderer.fontHeight) / 2);
            context.drawTextWithShadow(
                this.textRenderer,
                this.textRenderer.trimToWidth("message or command", this.chatFieldWidth),
                this.chatFieldX,
                placeholderY,
                0xFF596576
            );
        }

        int contentHeight = 0;
        int endVisible = Math.min(lines.size(), this.composerScrollLine + visibleRows);
        for (int index = this.composerScrollLine; index < endVisible; index++) {
            contentHeight += composerVisualLineHeight(lines.get(index));
        }
        int rowY = this.chatBorderY + Math.max(3, (this.chatBorderHeight - contentHeight) / 2);
        this.chatFieldY = rowY;
        for (int lineIndex = this.composerScrollLine; lineIndex < endVisible; lineIndex++) {
            ComposerVisualLine line = lines.get(lineIndex);
            renderComposerLine(context, line, lineIndex == cursorLine, cursor, rowY);
            rowY += composerVisualLineHeight(line);
        }

        if (lines.size() > COMPOSER_MAX_LINES) {
            int markerX = this.chatBorderX + this.chatBorderWidth - 3;
            int markerTop = this.chatFieldY;
            int markerBottom = this.chatBorderY + this.chatBorderHeight - 4;
            int thumbHeight = Math.max(5, (markerBottom - markerTop) * visibleRows / Math.max(1, lines.size()));
            int travel = Math.max(1, (markerBottom - markerTop) - thumbHeight);
            int thumbY = markerTop + (int) Math.round(travel * this.composerScrollLine / (double) Math.max(1, maxFirst));

            this.composerScrollbarTrackX = markerX;
            this.composerScrollbarTrackTop = markerTop;
            this.composerScrollbarTrackBottom = markerBottom;
            this.composerScrollbarThumbTop = thumbY;
            this.composerScrollbarThumbHeight = thumbHeight;
            this.composerScrollbarMaxFirst = maxFirst;

            context.fill(markerX, markerTop, markerX + 1, markerBottom, 0x55404A58);
            context.fill(markerX - 1, thumbY, markerX + 2, thumbY + thumbHeight,
                this.draggingComposerScrollbar ? 0xEEB9C1CE : 0xCC9DAABD);
        } else {
            this.draggingComposerScrollbar = false;
            this.composerScrollbarTrackBottom = this.composerScrollbarTrackTop;
            this.composerScrollbarThumbHeight = 0;
            this.composerScrollbarMaxFirst = 0;
        }

        boolean stopEnabled = systemsActive();
        boolean stopHovered = stopEnabled
            && mouseX >= this.stopIconX - 2 && mouseX < this.stopIconX + STOP_ICON_SIZE + 2
            && mouseY >= this.stopIconY - 2 && mouseY < this.stopIconY + STOP_ICON_SIZE + 2;
        if (stopHovered) {
            context.drawBorder(this.stopIconX - 2, this.stopIconY - 2, STOP_ICON_SIZE + 4, STOP_ICON_SIZE + 4,
                ModelSemanticPalette.color(ModelActivityState.CANCELLED));
        }
        context.drawTexture(STOP_BUTTON, this.stopIconX, this.stopIconY, 0, 0, STOP_ICON_SIZE, STOP_ICON_SIZE, STOP_ICON_SIZE, STOP_ICON_SIZE);
        if (!stopEnabled) {
            context.fill(this.stopIconX, this.stopIconY, this.stopIconX + STOP_ICON_SIZE, this.stopIconY + STOP_ICON_SIZE, 0x99000000);
        }
        this.actionHits.add(new ActionHit(
            this.stopIconX - 2,
            this.stopIconY - 2,
            this.stopIconX + STOP_ICON_SIZE + 2,
            this.stopIconY + STOP_ICON_SIZE + 2,
            stopEnabled,
            this::forceStopSystems
        ));
    }

    private void renderComposerLine(
        DrawContext context,
        ComposerVisualLine visual,
        boolean cursorLine,
        int globalCursor,
        int y
    ) {
        String line = visual.hardLine();
        int globalStart = visual.hardGlobalStart();
        int from = visual.sourceFrom();
        int to = visual.sourceTo();
        int localCursor = Math.max(0, Math.min(line.length(), globalCursor - globalStart));

        renderComposerSelection(context, line, globalStart, from, to, y);
        VanillaBackedChatInputController.renderStyledRange(
            context,
            this.textRenderer,
            MinecraftClient.getInstance(),
            line,
            from,
            to,
            localCursor,
            this.chatFieldX,
            y,
            this.chatFieldWidth
        );

        if (cursorLine && this.composerFocused && (System.currentTimeMillis() / 300L) % 2L == 0L
            && localCursor >= from && localCursor <= to) {
            int cursorX = this.chatFieldX + VanillaBackedChatInputController.styledRangeWidth(
                this.textRenderer,
                MinecraftClient.getInstance(),
                line,
                from,
                localCursor,
                localCursor
            );
            int cursorHeight = Math.max(this.textRenderer.fontHeight + 1, composerVisualLineHeight(visual) - 1);
            context.fill(cursorX, y - 1, cursorX + 1, y - 1 + cursorHeight, 0xFFFFFFFF);
        }
    }

    private void renderComposerSelection(DrawContext context, String line, int globalStart, int from, int to, int y) {
        if (!(this.chatInput instanceof TextFieldWidgetAccessor accessor)) {
            return;
        }
        int selectionStart = Math.min(accessor.koil$getSelectionStart(), accessor.koil$getSelectionEnd());
        int selectionEnd = Math.max(accessor.koil$getSelectionStart(), accessor.koil$getSelectionEnd());
        int lineStart = globalStart;
        int selectedStart = Math.max(selectionStart, lineStart + from);
        int selectedEnd = Math.min(selectionEnd, lineStart + to);
        if (selectedStart >= selectedEnd) {
            return;
        }
        int localStart = selectedStart - lineStart;
        int localEnd = selectedEnd - lineStart;
        int x1 = this.chatFieldX + VanillaBackedChatInputController.styledRangeWidth(
            this.textRenderer, MinecraftClient.getInstance(), line, from, localStart, localStart);
        int x2 = this.chatFieldX + VanillaBackedChatInputController.styledRangeWidth(
            this.textRenderer, MinecraftClient.getInstance(), line, from, localEnd, localEnd);
        int selectionHeight = Math.max(this.textRenderer.fontHeight + 1,
            composerLineHeight(line) - 1);
        context.fill(x1, y - 1, x2, y - 1 + selectionHeight, 0x66FFFFFF);
    }

    private void renderSidebarConversation(
        DrawContext context,
        int mouseX,
        int mouseY,
        ModelGenerationHudState.Snapshot snapshot
    ) {
        int left = 0;
        int right = SIDEBAR_WIDTH - 8;
        int top = this.chatStreamTop;
        int bottom = this.chatStreamBottom;
        int textX = this.chatFieldX;
        int width = Math.max(24, right - textX);
        int viewportHeight = Math.max(1, bottom - top);
        this.workspaceMessageHits.clear();
        this.workspaceToolHits.clear();
        this.currentModelMessageBounds = null;

        List<WorkspaceRenderedMessage> rendered = buildWorkspaceRenderedMessages(width);
        int totalHeight = rendered.stream().mapToInt(WorkspaceRenderedMessage::height).sum();
        int maximumOffset = Math.max(0, totalHeight - viewportHeight);
        if (!this.chatStreamManualScroll) {
            this.chatStreamManualOffset = maximumOffset;
        }
        this.chatStreamManualOffset = Math.max(0, Math.min(maximumOffset, this.chatStreamManualOffset));

        context.enableScissor(left, top, right, bottom);
        try (com.spirit.koil.api.chat.RichChatRenderContext.SurfaceScope ignored =
                 RichChatSurfaceRenderer.surface(width, top, bottom)) {
        int y = top - this.chatStreamManualOffset;
        for (WorkspaceRenderedMessage message : rendered) {
            int messageTop = y;
            int messageBottom = y + message.height();
            if (messageBottom >= top && messageTop <= bottom) {
                boolean selected = message.requestId() != null && message.requestId().equals(this.selectedHistoryRequestId);
                boolean hovered = mouseX >= left && mouseX < right
                    && mouseY >= Math.max(top, messageTop) && mouseY < Math.min(bottom, messageBottom);
                int accent = message.model()
                    ? ModelSemanticPalette.color(message.active() ? message.state() : ModelActivityState.COMPLETE)
                    : 0xFF9DAABD;
                if (selected) {
                    context.fill(0, Math.max(top, messageTop), 2, Math.min(bottom, messageBottom), withAlpha(accent, 210));
                } else if (hovered && message.requestId() != null) {
                    context.fill(0, Math.max(top, messageTop), 1, Math.min(bottom, messageBottom), withAlpha(accent, 150));
                }

                int lineY = messageTop + 1;
                int prefixWidth = message.prefix() == null || message.prefix().isEmpty()
                    ? 0
                    : this.textRenderer.getWidth(message.prefix()) + 4;

                if (!message.model()) {
                    for (int index = 0; index < message.lines().size(); index++) {
                        WorkspaceRenderedLine line = message.lines().get(index);
                        int lineX = textX;
                        if (prefixWidth > 0) {
                            if (index == 0) {
                                context.drawTextWithShadow(this.textRenderer, message.prefix(), textX, lineY, message.prefixColor());
                            }
                            lineX += prefixWidth;
                        }
                        renderWorkspaceRenderedLine(context, line, lineX, lineY);
                        lineY += line.height();
                    }
                } else {
                    for (WorkspaceToolRow tool : message.toolRows()) {
                        int toolTop = lineY;
                        int toolBottom = toolTop + tool.height();
                        if (toolBottom >= top && toolTop <= bottom) {
                            boolean toolHovered = mouseX >= textX && mouseX < right
                                && mouseY >= Math.max(top, toolTop) && mouseY < Math.min(bottom, toolBottom);
                            int toolColor = ModelSemanticPalette.color(tool.state());
                            int visibleToolColor = tool.active()
                                ? toolColor
                                : toolHovered ? withAlpha(toolColor, 235) : withAlpha(toolColor, 190);
                            int badgeX = textX;
                            context.drawTextWithShadow(this.textRenderer, tool.badge(), badgeX, toolTop + 1, visibleToolColor);
                            int bodyX = badgeX + this.textRenderer.getWidth(tool.badge()) + 5;
                            int toolTextY = toolTop + 1;
                            for (WorkspaceRenderedLine line : tool.lines()) {
                                renderWorkspaceRenderedLine(context, line, bodyX, toolTextY);
                                toolTextY += line.height();
                            }
                            this.workspaceToolHits.add(new ToolMessageHit(
                                message.requestId(),
                                tool.nodeId(),
                                textX,
                                Math.max(top, toolTop),
                                right,
                                Math.min(bottom, toolBottom)
                            ));
                        }
                        lineY += tool.height();
                    }

                    if (!message.toolRows().isEmpty() && !message.lines().isEmpty()) {
                        lineY += 1;
                    }
                    boolean prefixDrawn = false;
                    for (int index = 0; index < message.lines().size(); index++) {
                        WorkspaceRenderedLine line = message.lines().get(index);
                        int lineX = textX + prefixWidth;
                        if (prefixWidth > 0 && !prefixDrawn) {
                            context.drawTextWithShadow(this.textRenderer, message.prefix(), textX, lineY, 0xFF596576);
                            prefixDrawn = true;
                        }
                        renderWorkspaceRenderedLine(context, line, lineX, lineY);
                        lineY += line.height();
                    }
                }

                if (message.model() && message.footerVisible()) {
                    int footerY = messageBottom - message.footerHeight();
                    renderWorkspaceMessageFooter(context, message, textX, right, footerY);
                }

                if (message.requestId() != null) {
                    this.workspaceMessageHits.add(new MessageHit(
                        message.requestId(),
                        left,
                        Math.max(top, messageTop),
                        right,
                        Math.min(bottom, messageBottom)
                    ));
                }
            }
            y += message.height();
        }
        }
        context.disableScissor();

        if (maximumOffset > 0) {
            int markerX = right - 1;
            int markerHeight = Math.max(6, viewportHeight * viewportHeight / Math.max(viewportHeight, totalHeight));
            int travel = Math.max(1, viewportHeight - markerHeight);
            int markerY = top + (int) Math.round(travel * this.chatStreamManualOffset / (double) maximumOffset);

            this.chatScrollbarTrackX = markerX;
            this.chatScrollbarTrackTop = top;
            this.chatScrollbarTrackBottom = bottom;
            this.chatScrollbarThumbTop = markerY;
            this.chatScrollbarThumbHeight = markerHeight;
            this.chatScrollbarMaximumOffset = maximumOffset;

            context.fill(markerX, top, markerX + 1, bottom, 0x55404A58);
            context.fill(markerX - 1, markerY, markerX + 2, markerY + markerHeight,
                this.draggingChatScrollbar ? 0xEEB9C1CE : 0xAA9DAABD);
        } else {
            this.draggingChatScrollbar = false;
            this.chatScrollbarTrackBottom = this.chatScrollbarTrackTop;
            this.chatScrollbarThumbHeight = 0;
            this.chatScrollbarMaximumOffset = 0;
        }
    }

    private void renderWorkspaceRenderedLine(DrawContext context, WorkspaceRenderedLine line, int x, int y) {
        if (line == null) return;
        int endX = x;
        if (line.orderedText() != null) {
            endX = RichChatSurfaceRenderer.renderLine(
                context, this.textRenderer, line.orderedText(), x, y, line.color());
        } else if (line.animatedText() != null) {
            endX = context.drawTextWithShadow(this.textRenderer, line.animatedText(), x, y, line.color());
        }
        if (line.cursorAfter()) {
            ModelOutputMaterialization.drawCursor(
                context, this.textRenderer, endX, y, line.materialization());
        }
    }

    private void renderWorkspaceMessageFooter(
        DrawContext context,
        WorkspaceRenderedMessage message,
        int left,
        int right,
        int y
    ) {
        int currentY = y;

        int color = message.footerColor();
        net.minecraft.text.MutableText status = message.active()
            ? Text.empty().append(SlidingStatusText.styled(message.footerStatusLabel(), message.footerSemanticState(), color))
            : Text.literal(message.footerStatusLabel());
        if (!message.footerStatusDetail().isBlank()) {
            status.append(Text.literal(" | ").styled(style -> style.withColor(STATUS_SEPARATOR_COLOR)));
            status.append(Text.literal(message.footerStatusDetail()).styled(style -> style.withColor(
                SlidingStatusText.transitionColor(color)
            )));
        }

        long now = System.currentTimeMillis();
        long end = message.completedAtMillis() > 0L ? message.completedAtMillis() : now;
        long elapsed = Math.max(0L, end - message.createdAtMillis());
        String duration = compactDuration(elapsed);
        String speed = message.tokensPerSecond() > 0.0D
            ? String.format(Locale.ROOT, "%.1f tok/s", message.tokensPerSecond())
            : "";
        // Request age and generation throughput are primary runtime status, not
        // debug diagnostics. Keep this compact pair visible in every mode.
        String metrics = speed.isBlank() ? duration : speed + "  " + duration;
        int metricsWidth = this.textRenderer.getWidth(metrics);
        int metricsX = Math.max(left, right - metricsWidth - 5);

        int statusRight = Math.max(left, metricsX - 6);
        int availableStatusWidth = Math.max(0, statusRight - left);
        if (availableStatusWidth > 0) {
            if (this.textRenderer.getWidth(status) > availableStatusWidth) {
                String label = message.footerStatusLabel();
                Text animatedLabel = message.active()
                    ? SlidingStatusText.styled(label, message.footerSemanticState(), color)
                    : Text.literal(label);
                int prefixWidth = this.textRenderer.getWidth(animatedLabel) + this.textRenderer.getWidth(" | ");
                int detailWidth = Math.max(0, availableStatusWidth - prefixWidth);
                String detail = detailWidth <= 0
                    ? ""
                    : this.textRenderer.trimToWidth(message.footerStatusDetail(), detailWidth);
                status = Text.empty().append(animatedLabel);
                if (!detail.isBlank()) {
                    status.append(Text.literal(" | ").styled(style -> style.withColor(STATUS_SEPARATOR_COLOR)));
                    status.append(Text.literal(detail).styled(style -> style.withColor(
                        SlidingStatusText.transitionColor(color)
                    )));
                }
            }
            context.drawTextWithShadow(this.textRenderer, status, left, currentY, color);
        }
        context.drawTextWithShadow(this.textRenderer, metrics, metricsX, currentY, 0xFF8F9AAA);
    }

    private String compactSidebarStatus(ModelActivityState state, boolean active) {
        ModelActivityState safe = state == null ? ModelActivityState.IDLE : state;
        return switch (safe) {
            case STARTING -> "Starting";
            case PREPARING -> "Ingesting";
            case RESOLVING -> "Resolving";
            case DISCOVERING -> "Discovering";
            case THINKING -> "Thinking";
            case SEARCHING -> "Searching";
            case INSPECTING -> "Inspecting";
            case READING -> "Reading";
            case COMPARING -> "Comparing";
            case CALCULATING -> "Calculating";
            case PLANNING, REPLANNING -> "Planning";
            case AWAITING_APPROVAL -> "Approval";
            case EXECUTING, NAVIGATING, ORIENTING, SPRINTING, SWIMMING, CLIMBING, PARKOUR,
                 RIDING, GLIDING, INTERACTING, USING_ITEM, EATING, MINING, BUILDING, ATTACKING -> "Executing";
            case OBSERVING -> "Waiting";
            case VALIDATING, TESTING -> "Checking";
            case REPAIRING, RETRYING, RECOVERING -> "Recovering";
            case EDITING, FORMATTING, WRITING, FINALIZING -> "Writing";
            case COMPLETE, ALREADY_SATISFIED -> "Done";
            case PARTIAL -> "Partial";
            case BLOCKED -> "Blocked";
            case FAILED -> "Failed";
            case INTERRUPTED, CANCELLED -> "Cancelled";
            case IDLE -> active ? "Working" : "Idle";
        };
    }

    private String compactDuration(long millis) {
        long safe = Math.max(0L, millis);
        if (safe < 1_000L) return safe + "ms";
        if (safe < 60_000L) return String.format(Locale.ROOT, "%.1fs", safe / 1_000.0D);
        long seconds = safe / 1_000L;
        if (seconds < 3_600L) return (seconds / 60L) + "m " + (seconds % 60L) + "s";
        long hours = seconds / 3_600L;
        return hours + "h " + ((seconds % 3_600L) / 60L) + "m";
    }

    private void renderSidebarStatusRows(DrawContext context, int x, int y, int width) {
        AutomationModeController.Snapshot mode = AutomationModeController.snapshot();
        ModelGenerationHudState.Snapshot model = ModelGenerationHudState.visibleSnapshot();
        AutomationRuntimeStatus.Snapshot executor = AutomationRuntimeStatus.snapshot();

        ModelRequestStatusPresentation.View modelView = model == null
            ? new ModelRequestStatusPresentation.View("Idle", ModelActivityState.IDLE)
            : ModelRequestStatusPresentation.forSnapshot(model);
        boolean voice = false;
        try {
            voice = ModelVoiceService.settings().enabled();
        } catch (Throwable ignored) {
        }

        renderSidebarStatusRow(context, x, y, width, "AUTO", automationStatusLabel(mode), automationDisplayState(mode));
        renderSidebarStatusRow(context, x, y + 11, width, "MODEL", modelView.label(), modelView.activityState());
        renderSidebarStatusRow(context, x, y + 22, width, "EXEC", friendly(executor.state()), stateForMarker(executor.state(), "executor"));
        renderSidebarStatusRow(context, x, y + 33, width, "VOICE", voice ? "Enabled" : "Off", ModelActivityState.IDLE);
    }

    private void renderSidebarStatusRow(
        DrawContext context,
        int x,
        int y,
        int width,
        String role,
        String label,
        ModelActivityState state
    ) {
        int stateColor = ModelSemanticPalette.color(state);
        context.drawTextWithShadow(this.textRenderer, role, x, y, systemKindColor(role, stateColor));
        int valueX = x + 42;
        Text value = isActiveCanvasState(state)
            ? SlidingStatusText.styled(label, state.id(), stateColor)
            : Text.literal(label);
        int valueRight = x + width;
        context.enableScissor(valueX, y - 1, valueRight, y + this.textRenderer.fontHeight + 2);
        context.drawTextWithShadow(this.textRenderer, value, valueX, y, stateColor);
        context.disableScissor();
    }

    private int chatTailStart(List<ChatRenderLine> lines, int maximumHeight) {
        int used = 0;
        int start = lines == null ? 0 : lines.size();
        while (start > 0) {
            int next = Math.max(1, lines.get(start - 1).height());
            if (used > 0 && used + next > maximumHeight) {
                break;
            }
            used += next;
            start--;
        }
        return Math.max(0, start);
    }

    private int chatContentHeight(List<ChatRenderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return 0;
        }
        return lines.stream().mapToInt(line -> Math.max(1, line.height())).sum();
    }

    private List<ChatRenderLine> buildChatRenderLines(ModelGenerationHudState.Snapshot snapshot, int width) {
        List<ChatRenderLine> out = new ArrayList<>();
        String prompt = snapshot != null && !snapshot.prompt().isBlank() ? snapshot.prompt() : this.lastSubmittedMessage;
        if (!prompt.isBlank()) {
            List<OrderedText> promptLines = RichChatSurfaceRenderer.wrap(
                this.textRenderer,
                Text.literal("<" + playerName() + "> " + prompt),
                RichChatRowType.PLAYER_CHAT,
                Math.max(24, width - 38)
            );
            for (int i = 0; i < promptLines.size(); i++) {
                OrderedText line = promptLines.get(i);
                out.add(new ChatRenderLine(i == 0 ? "YOU" : "", systemKindColor("USER", 0xFF9DAABD), line, null, 0xFFE0E0E0,
                    Math.max(this.textRenderer.fontHeight + 2, RichChatSurfaceRenderer.lineHeight(this.textRenderer, line))));
            }
        }

        if (snapshot == null) {
            if (System.currentTimeMillis() - this.lastSubmittedAtMillis < 1_500L && !this.lastSubmittedMessage.isBlank()) {
                Text waiting = SlidingStatusText.styled("Starting", ModelActivityState.STARTING.id(), ModelSemanticPalette.color(ModelActivityState.STARTING));
                out.add(new ChatRenderLine("MODEL", systemKindColor("MODEL", 0), null, waiting, ModelSemanticPalette.color(ModelActivityState.STARTING),
                    this.textRenderer.fontHeight + 2));
            }
            return out;
        }

        String text = RichChatModelOutputSanitizer.normalizeStreamingPreview(snapshot.text());
        if (!text.isBlank()) {
            String decorated = ModelChatIdentity.decorate(text);
            List<OrderedText> responseLines = RichChatSurfaceRenderer.wrap(
                this.textRenderer,
                Text.literal(decorated),
                RichChatRowType.MODEL_RESPONSE,
                Math.max(24, width - 45)
            );
            for (int i = 0; i < responseLines.size(); i++) {
                OrderedText line = responseLines.get(i);
                out.add(new ChatRenderLine(i == 0 ? "MODEL" : "", systemKindColor("MODEL", 0), line, null, uiColorLocalModelPopupText,
                    Math.max(this.textRenderer.fontHeight + 2, RichChatSurfaceRenderer.lineHeight(this.textRenderer, line))));
            }
        }

        if (!snapshot.state().terminal()) {
            ModelRequestStatusPresentation.View view = ModelRequestStatusPresentation.forSnapshot(snapshot);
            int color = ModelSemanticPalette.color(view.activityState());
            net.minecraft.text.MutableText status = Text.empty()
                .append(SlidingStatusText.styled(view.label(), view.semanticState(), color));
            if (!view.detail().isBlank()) {
                status.append(Text.literal(" | ").styled(style -> style.withColor(STATUS_SEPARATOR_COLOR)));
                status.append(Text.literal(view.detail()).styled(style -> style.withColor(
                    SlidingStatusText.transitionColor(color)
                )));
            }
            out.add(new ChatRenderLine(text.isBlank() ? "MODEL" : "", systemKindColor("MODEL", 0), null, status, color,
                this.textRenderer.fontHeight + 2));
        }
        return out;
    }

    private void renderSystemStatusStrip(DrawContext context, int x, int y, int width) {
        AutomationModeController.Snapshot mode = AutomationModeController.snapshot();
        ModelGenerationHudState.Snapshot model = ModelGenerationHudState.visibleSnapshot();
        AutomationRuntimeStatus.Snapshot executor = AutomationRuntimeStatus.snapshot();
        int cursorX = x + 4;
        cursorX = renderStatusSegment(context, cursorX, y, "AUTO", automationStatusLabel(mode), automationDisplayState(mode), width - (cursorX - x));
        if (cursorX < x + width - 35) {
            context.drawTextWithShadow(this.textRenderer, "  |  ", cursorX, y, 0xFF596576);
            cursorX += this.textRenderer.getWidth("  |  ");
            ModelRequestStatusPresentation.View view = model == null
                ? new ModelRequestStatusPresentation.View("Idle", ModelActivityState.IDLE)
                : ModelRequestStatusPresentation.forSnapshot(model);
            cursorX = renderStatusSegment(context, cursorX, y, "MODEL", view.label(), view.activityState(), width - (cursorX - x));
        }
        if (cursorX < x + width - 35) {
            context.drawTextWithShadow(this.textRenderer, "  |  ", cursorX, y, 0xFF596576);
            cursorX += this.textRenderer.getWidth("  |  ");
            ModelActivityState executorState = stateForMarker(executor.state(), "executor");
            cursorX = renderStatusSegment(context, cursorX, y, "EXEC", friendly(executor.state()), executorState, width - (cursorX - x));
        }
        if (cursorX < x + width - 35) {
            context.drawTextWithShadow(this.textRenderer, "  |  ", cursorX, y, 0xFF596576);
            cursorX += this.textRenderer.getWidth("  |  ");
            boolean voice = false;
            try { voice = ModelVoiceService.settings().enabled(); } catch (Throwable ignored) { }
            renderStatusSegment(context, cursorX, y, "VOICE", voice ? "Enabled" : "Off", ModelActivityState.IDLE, width - (cursorX - x));
        }
    }

    private int renderStatusSegment(DrawContext context, int x, int y, String role, String label, ModelActivityState state, int availableWidth) {
        if (availableWidth <= 8) {
            return x;
        }
        int roleColor = systemKindColor(role, ModelSemanticPalette.color(state));
        context.drawTextWithShadow(this.textRenderer, role, x, y, roleColor);
        int cursor = x + this.textRenderer.getWidth(role) + 4;
        int stateColor = ModelSemanticPalette.color(state);
        Text status = isActiveCanvasState(state)
            ? SlidingStatusText.styled(label, state.id(), stateColor)
            : Text.literal(label);
        context.drawTextWithShadow(this.textRenderer, status, cursor, y, stateColor);
        return cursor + this.textRenderer.getWidth(status);
    }

    private void renderFooterStatus(DrawContext context, int mouseX, int mouseY) {
        int y = Math.max(CONTENT_TOP, this.height - this.textRenderer.fontHeight - 2);
        int leftX = 8;

        String modelName = LocalModelService.configuredModelId();
        if (modelName == null || modelName.isBlank()) {
            modelName = "No model";
        }

        int maximumContext = LocalModelService.configuredContextWindowTokens();
        ModelGenerationHudState.Snapshot snapshot = ModelGenerationHudState.metricsSnapshot();

        int contextLeftPercent = 100;
        if (maximumContext > 0 && snapshot != null && snapshot.usage() != null) {
            long usedContext = (long) snapshot.usage().promptTokens()
                + (long) snapshot.usage().completionTokens();
            long remaining = Math.max(0L, (long) maximumContext - usedContext);
            contextLeftPercent = (int) Math.round(
                (remaining * 100.0D) / (double) maximumContext
            );
            contextLeftPercent = MathHelper.clamp(contextLeftPercent, 0, 100);
        }

        String footer = ModelDebugMode.enabled()
            ? modelName + "  |  Context left " + contextLeftPercent + "%"
            : modelName;
        context.drawTextWithShadow(
            this.textRenderer,
            this.textRenderer.trimToWidth(footer, Math.max(20, this.width - 16)),
            leftX,
            y,
            0xFFB9C1CE
        );
    }

    private void resetNotebookView() {
        this.notePositions.clear();
        this.canvasScrollX = 0.0D;
        this.canvasScrollY = 0.0D;
        this.canvasZoom = 1.0D;
        this.pendingCenterNodeId = "";
        this.requestFocusMode = false;
        this.requestFocusRequestId = "";
        this.requestFocusedNodeIds.clear();
        this.expandedNodes.clear();
        this.expandedNodes.add("root:automation");
        this.expandedNodes.add("root:model");
        this.expandedNodes.add("root:executor");
        invalidateNotebookRenderModel();
        showNotice("Pane layout reset.");
    }

    private void centerNotebookView() {
        this.pendingCenterNodeId = this.selectedNodeId.isBlank() ? "root:model" : this.selectedNodeId;
        showNotice(this.selectedNodeId.isBlank() ? "Centered on the Model System." : "Centered on the selected pane.");
    }

    private void forceStopSystems() {
        boolean shiftStop = Screen.hasShiftDown();
        boolean automationWasEnabled = AutomationModeController.isAutomationMode();
        boolean hadTask = AutomationRouter.isTaskRunning();
        ModelGenerationHudState.Snapshot visible = ModelGenerationHudState.visibleSnapshot();
        boolean hadModel = visible != null && !visible.state().terminal();

        if (shiftStop) {
            AutomationRouter.stopAutomation(false);
        } else {
            LocalModelService.cancelActiveWork();
            if (AutomationRouter.isTaskRunning()) {
                AutomationRouter.cancelCurrentTask("workspace stop button");
            }
        }
        ModelVoiceService.stopSpeaking("workspace stop button");
        this.forcedStopAtMillis = System.currentTimeMillis();

        if (shiftStop && automationWasEnabled) {
            showNotice("Stopped active work and turned Automation Mode off.");
        } else if (hadModel || hadTask) {
            showNotice("Stopped current model/automation work. Automation Mode was left unchanged.");
        } else if (automationWasEnabled) {
            showNotice("No active work was running. Shift-click Stop to turn Automation Mode off.");
        } else {
            showNotice("No active model or automation work was running.");
        }
    }

    private boolean systemsActive() {
        ModelGenerationHudState.Snapshot model = ModelGenerationHudState.visibleSnapshot();
        return AutomationModeController.isAutomationMode()
            || AutomationRouter.isTaskRunning()
            || LocalModelService.hasActiveWork()
            || model != null && !model.state().terminal();
    }

    private int canvasLeft() {
        return SIDEBAR_WIDTH + PANEL_GAP + CANVAS_INSET;
    }

    private int canvasRight() {
        return Math.max(canvasLeft() + 1, this.width - CANVAS_INSET);
    }

    private int canvasContentTop() {
        return CONTENT_TOP + CANVAS_INSET;
    }

    private static boolean within(int[] bounds, double mouseX, double mouseY) {
        return bounds != null && bounds.length >= 4
            && mouseX >= bounds[0] && mouseX < bounds[0] + bounds[2]
            && mouseY >= bounds[1] && mouseY < bounds[1] + bounds[3];
    }

    private void setCanvasZoom(double requestedZoom, double anchorMouseX, double anchorMouseY) {
        double nextZoom = Math.max(MIN_CANVAS_ZOOM, Math.min(MAX_CANVAS_ZOOM, requestedZoom));
        if (Math.abs(nextZoom - this.canvasZoom) < 0.0001D) {
            return;
        }
        int left = canvasLeft();
        int top = canvasContentTop();
        double oldZoom = this.canvasZoom;
        double logicalX = (anchorMouseX - left) / oldZoom - this.canvasScrollX;
        double logicalY = (anchorMouseY - top) / oldZoom - this.canvasScrollY;
        this.canvasZoom = nextZoom;
        this.canvasScrollX = (anchorMouseX - left) / nextZoom - logicalX;
        this.canvasScrollY = (anchorMouseY - top) / nextZoom - logicalY;
    }

    private int approvalPanelHeight(ModelGenerationHudState.Snapshot snapshot) {
        if (snapshot == null || snapshot.approval() == null || this.textRenderer == null) {
            return 0;
        }
        int width = Math.max(80, SIDEBAR_WIDTH - 28);
        Text formatted = RichChatBodyWrapFormatter.formatConfirmationDetails(
            Text.literal(snapshot.approval().message()), width
        );
        List<OrderedText> lines = RichChatSurfaceRenderer.wrap(
            this.textRenderer, formatted, RichChatRowType.UNKNOWN, width
        );
        int bodyLines = Math.min(7, Math.max(1, lines.size()));
        return 8 + this.textRenderer.fontHeight + 5
            + bodyLines * (this.textRenderer.fontHeight + 2)
            + this.textRenderer.fontHeight + 12;
    }

    private void renderApprovalPanel(
        DrawContext context,
        int mouseX,
        int mouseY,
        ModelGenerationHudState.Snapshot snapshot
    ) {
        if (snapshot == null || snapshot.approval() == null || this.approvalTop >= this.approvalBottom) {
            return;
        }
        ModelGenerationHudState.Approval approval = snapshot.approval();
        int left = 8;
        int right = SIDEBAR_WIDTH - 8;
        int top = this.approvalTop;
        int bottom = this.approvalBottom;
        int border = ModelSemanticPalette.color(ModelActivityState.AWAITING_APPROVAL);

        context.fill(left, top, right, bottom, withAlpha(uiColorContentBase, 230));
        context.drawBorder(left, top, Math.max(1, right - left), Math.max(1, bottom - top),
            new Color(uiColorBackgroundBorder, true).getRGB());

        int x = left + 6;
        int y = top + 5;
        context.drawTextWithShadow(
            this.textRenderer,
            this.textRenderer.trimToWidth(approval.title(), Math.max(24, right - left - 130)),
            x,
            y,
            new Color(uiColorContentBaseTitleText, true).getRGB()
        );
        Text waiting = SlidingStatusText.styled(
            "Awaiting approval",
            ModelActivityState.AWAITING_APPROVAL.id(),
            border
        );
        int statusWidth = this.textRenderer.getWidth(waiting);
        context.drawTextWithShadow(this.textRenderer, waiting, Math.max(x, right - statusWidth - 6), y, border);

        y += this.textRenderer.fontHeight + 5;
        int bodyWidth = Math.max(24, right - left - 12);
        Text body = RichChatBodyWrapFormatter.formatConfirmationDetails(
            Text.literal(approval.message()), bodyWidth
        );
        List<OrderedText> lines = RichChatSurfaceRenderer.wrap(
            this.textRenderer, body, RichChatRowType.UNKNOWN, bodyWidth
        );
        int buttonHeight = this.textRenderer.fontHeight + 6;
        int buttonY = bottom - buttonHeight - 5;
        context.enableScissor(x, y, right - 6, buttonY - 2);
        int visible = 0;
        for (OrderedText line : lines) {
            if (visible++ >= 6 || y + this.textRenderer.fontHeight > buttonY - 2) {
                break;
            }
            RichChatSurfaceRenderer.renderLine(
                context, this.textRenderer, line, x, y, uiColorLocalModelPopupText
            );
            y += Math.max(
                this.textRenderer.fontHeight + 2,
                RichChatSurfaceRenderer.lineHeight(this.textRenderer, line)
            );
        }
        context.disableScissor();

        int approveWidth = this.textRenderer.getWidth(approval.approveLabel()) + 12;
        int denyWidth = this.textRenderer.getWidth(approval.denyLabel()) + 12;
        int buttonX = x;
        renderApprovalButton(
            context, mouseX, mouseY, buttonX, buttonY, approveWidth, buttonHeight,
            approval.approveLabel(), true,
            () -> ModelGenerationHudState.resolveApproval(snapshot.requestId(), true)
        );
        buttonX += approveWidth + 5;
        renderApprovalButton(
            context, mouseX, mouseY, buttonX, buttonY, denyWidth, buttonHeight,
            approval.denyLabel(), false,
            () -> ModelGenerationHudState.resolveApproval(snapshot.requestId(), false)
        );
    }

    private void renderApprovalButton(
        DrawContext context,
        int mouseX,
        int mouseY,
        int x,
        int y,
        int width,
        int height,
        String label,
        boolean approve,
        Runnable action
    ) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        ModelActivityState semantic = approve ? ModelActivityState.EXECUTING : ModelActivityState.CANCELLED;
        int accent = ModelSemanticPalette.color(semantic);
        context.fill(x, y, x + width, y + height, withAlpha(uiColorContentBase, hovered ? 215 : 170));
        context.drawBorder(x, y, width, height, hovered ? accent : new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawTextWithShadow(
            this.textRenderer,
            label,
            x + Math.max(4, (width - this.textRenderer.getWidth(label)) / 2),
            y + 3,
            hovered ? accent : new Color(uiColorContentBaseTitleText, true).getRGB()
        );
        this.actionHits.add(new ActionHit(x, y, x + width, y + height, true, action));
    }

    private void rebuildNotebookSearchResults() {
        this.notebookSearchResults.clear();
        if (this.notebookSearchInput == null) {
            this.selectedNotebookSearchIndex = -1;
            return;
        }
        String query = this.notebookSearchInput.getText() == null
            ? ""
            : this.notebookSearchInput.getText().trim().toLowerCase(Locale.ROOT);
        if (query.isBlank()) {
            this.selectedNotebookSearchIndex = -1;
            this.notebookSearchScrollOffset = 0;
            return;
        }

        for (NotebookNode root : buildNotebookRoots()) {
            collectNotebookSearchResults(root, query, this.notebookSearchResults, 64);
            if (this.notebookSearchResults.size() >= 64) {
                break;
            }
        }
        if (this.notebookSearchResults.isEmpty()) {
            this.selectedNotebookSearchIndex = -1;
            this.notebookSearchScrollOffset = 0;
            return;
        }
        if (this.selectedNotebookSearchIndex < 0) {
            this.selectedNotebookSearchIndex = 0;
        }
        this.selectedNotebookSearchIndex = Math.max(0,
            Math.min(this.selectedNotebookSearchIndex, this.notebookSearchResults.size() - 1));
        keepNotebookSearchSelectionVisible();
    }

    private String typedSearchText(NotebookNode node) {
        if (node == null || node.typedDetailLines.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (List<TelemetryText> line : node.typedDetailLines) {
            for (TelemetryText span : line) if (span != null) out.append(span.text());
            out.append('\n');
        }
        return out.toString();
    }

    private void collectNotebookSearchResults(
        NotebookNode node,
        String query,
        List<PadSearchResult> out,
        int limit
    ) {
        if (node == null || out.size() >= limit) {
            return;
        }
        String searchable = (node.title + "\n" + node.kind + "\n" + node.summary + "\n"
            + node.detail + "\n" + typedSearchText(node) + "\n" + node.source + "\n" + node.id).toLowerCase(Locale.ROOT);
        if (searchable.contains(query)) {
            String secondary = node.kind
                + (node.summary.isBlank() ? "" : "  |  " + node.summary.replace('\n', ' '));
            out.add(new PadSearchResult(node.id, node.title, secondary));
        }
        for (NotebookNode child : node.children) {
            collectNotebookSearchResults(child, query, out, limit);
            if (out.size() >= limit) {
                return;
            }
        }
    }

    private void renderNotebookSearchDropdown(DrawContext context, int mouseX, int mouseY) {
        if (this.notebookSearchInput == null
            || !this.notebookSearchInput.isFocused()
            || this.notebookSearchDropdownDismissed
            || this.notebookSearchInput.getText().isBlank()) {
            this.searchDropdownHeight = 0;
            return;
        }

        int x = this.searchDropdownX;
        int y = this.searchDropdownY;
        int width = Math.max(60, this.searchDropdownWidth);
        int visibleRows = Math.min(
            SEARCH_DROPDOWN_MAX_VISIBLE_ROWS,
            Math.max(1, this.notebookSearchResults.size())
        );
        int statusHeight = SEARCH_DROPDOWN_ROW_HEIGHT;
        int listY = y + statusHeight;
        int height = statusHeight + visibleRows * SEARCH_DROPDOWN_ROW_HEIGHT + SEARCH_DROPDOWN_PADDING;
        this.searchDropdownHeight = height;

        context.getMatrices().push();
        context.getMatrices().translate(0.0F, 0.0F, SEARCH_MODAL_Z);

        context.fill(x, y, x + width, y + height, 0xFF111418);

        int border = new Color(uiColorBackgroundBorder, true).getRGB();
        context.fill(x, y, x + width, y + 1, border);
        context.fill(x, y + height - 1, x + width, y + height, border);
        context.fill(x, y, x + 1, y + height, border);
        context.fill(x + width - 1, y, x + width, y + height, border);

        String status = this.notebookSearchResults.isEmpty()
            ? "No matching panes"
            : this.notebookSearchResults.size() + " panes  |  Enter to jump";
        context.drawText(
            this.textRenderer,
            this.textRenderer.trimToWidth(status, Math.max(20, width - SEARCH_DROPDOWN_PADDING * 2)),
            x + SEARCH_DROPDOWN_PADDING,
            y + 4,
            new Color(uiColorHeaderSubTitleText, true).getRGB(),
            false
        );

        if (!this.notebookSearchResults.isEmpty()) {
            int end = Math.min(
                this.notebookSearchResults.size(),
                this.notebookSearchScrollOffset + visibleRows
            );
            for (int index = this.notebookSearchScrollOffset; index < end; index++) {
                PadSearchResult result = this.notebookSearchResults.get(index);
                int visibleIndex = index - this.notebookSearchScrollOffset;
                int rowY = listY + visibleIndex * SEARCH_DROPDOWN_ROW_HEIGHT;
                boolean hovered = mouseX >= x + 1 && mouseX < x + width - 1
                    && mouseY >= rowY && mouseY < rowY + SEARCH_DROPDOWN_ROW_HEIGHT;
                boolean selected = index == this.selectedNotebookSearchIndex;

                if (hovered || selected) {
                    context.fill(
                        x + 1,
                        rowY,
                        x + width - 1,
                        rowY + SEARCH_DROPDOWN_ROW_HEIGHT,
                        selected ? withAlpha(uiColorHeader, 180) : withAlpha(uiColorHeader, 120)
                    );
                }

                context.drawText(
                    this.textRenderer,
                    this.textRenderer.trimToWidth(result.title(), Math.max(20, width - 12)),
                    x + SEARCH_DROPDOWN_PADDING,
                    rowY + 3,
                    new Color(uiColorIDEFileDisplayWindowText, true).getRGB(),
                    false
                );
                context.drawText(
                    this.textRenderer,
                    this.textRenderer.trimToWidth(result.secondary(), Math.max(20, width - 12)),
                    x + SEARCH_DROPDOWN_PADDING,
                    rowY + 13,
                    new Color(uiColorHeaderSubTitleText, true).getRGB(),
                    false
                );
            }
        }

        context.getMatrices().pop();
    }

    private boolean handleNotebookSearchDropdownClick(double mouseX, double mouseY) {
        if (this.searchDropdownHeight <= 0
            || this.notebookSearchInput == null
            || !this.notebookSearchInput.isFocused()
            || this.notebookSearchDropdownDismissed) {
            return false;
        }
        if (mouseX < this.searchDropdownX || mouseX >= this.searchDropdownX + this.searchDropdownWidth
            || mouseY < this.searchDropdownY || mouseY >= this.searchDropdownY + this.searchDropdownHeight) {
            return false;
        }
        int listY = this.searchDropdownY + SEARCH_DROPDOWN_ROW_HEIGHT;
        if (mouseY >= listY && !this.notebookSearchResults.isEmpty()) {
            int row = (int) ((mouseY - listY) / SEARCH_DROPDOWN_ROW_HEIGHT);
            int index = this.notebookSearchScrollOffset + row;
            if (index >= 0 && index < this.notebookSearchResults.size()) {
                this.selectedNotebookSearchIndex = index;
                return activateSelectedNotebookSearchResult();
            }
        }
        return true;
    }

    private boolean notebookSearchPopupOpen() {
        return this.searchDropdownHeight > 0
            && this.notebookSearchInput != null
            && this.notebookSearchInput.isFocused()
            && !this.notebookSearchDropdownDismissed
            && !this.notebookSearchInput.getText().isBlank();
    }

    private boolean scrollNotebookSearchDropdown(double mouseX, double mouseY, double amount) {
        if (this.searchDropdownHeight <= 0
            || mouseX < this.searchDropdownX || mouseX >= this.searchDropdownX + this.searchDropdownWidth
            || mouseY < this.searchDropdownY || mouseY >= this.searchDropdownY + this.searchDropdownHeight) {
            return false;
        }
        int max = Math.max(0, this.notebookSearchResults.size() - SEARCH_DROPDOWN_MAX_VISIBLE_ROWS);
        if (max <= 0 || amount == 0.0D) {
            return true;
        }
        int direction = amount > 0.0D ? -1 : 1;
        this.notebookSearchScrollOffset = Math.max(0,
            Math.min(max, this.notebookSearchScrollOffset + direction));
        return true;
    }

    private void moveNotebookSearchSelection(int delta) {
        if (this.notebookSearchResults.isEmpty()) {
            return;
        }
        if (this.selectedNotebookSearchIndex < 0) {
            this.selectedNotebookSearchIndex = 0;
        } else {
            this.selectedNotebookSearchIndex = Math.max(0,
                Math.min(this.notebookSearchResults.size() - 1, this.selectedNotebookSearchIndex + delta));
        }
        keepNotebookSearchSelectionVisible();
    }

    private void keepNotebookSearchSelectionVisible() {
        if (this.selectedNotebookSearchIndex < 0) {
            return;
        }
        if (this.selectedNotebookSearchIndex < this.notebookSearchScrollOffset) {
            this.notebookSearchScrollOffset = this.selectedNotebookSearchIndex;
        } else if (this.selectedNotebookSearchIndex
            >= this.notebookSearchScrollOffset + SEARCH_DROPDOWN_MAX_VISIBLE_ROWS) {
            this.notebookSearchScrollOffset = this.selectedNotebookSearchIndex - SEARCH_DROPDOWN_MAX_VISIBLE_ROWS + 1;
        }
        this.notebookSearchScrollOffset = Math.max(0,
            Math.min(
                Math.max(0, this.notebookSearchResults.size() - SEARCH_DROPDOWN_MAX_VISIBLE_ROWS),
                this.notebookSearchScrollOffset
            ));
    }

    private boolean activateSelectedNotebookSearchResult() {
        if (this.selectedNotebookSearchIndex < 0
            || this.selectedNotebookSearchIndex >= this.notebookSearchResults.size()) {
            return false;
        }
        PadSearchResult result = this.notebookSearchResults.get(this.selectedNotebookSearchIndex);
        focusNotebookNode(result.nodeId());
        this.notebookSearchDropdownDismissed = true;
        if (this.notebookSearchInput != null) {
            this.notebookSearchInput.setFocused(false);
        }
        return true;
    }

    private void focusNotebookNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        this.requestFocusMode = false;
        this.requestFocusRequestId = "";
        this.requestFocusedNodeIds.clear();
        List<NotebookNode> roots = buildNotebookRoots();
        List<NotebookNode> path = new ArrayList<>();
        NotebookNode target = null;
        for (NotebookNode root : roots) {
            path.clear();
            target = findNodePath(root, nodeId, path);
            if (target != null) {
                break;
            }
        }
        if (target == null) {
            showNotice("Pane is no longer available.");
            return;
        }
        for (int index = 0; index < path.size() - 1; index++) {
            this.expandedNodes.add(path.get(index).id);
        }
        if (!target.className.isBlank()) {
            populateClassMembers(target);
        }
        this.selectedNodeId = target.id;
        this.selectedTraceId = target.traceId;
        this.fullyExpandedPaneId = "";
        this.pendingCenterNodeId = target.id;
        showNotice("Jumped to " + target.title + ".");
    }

    private NotebookNode findNodePath(NotebookNode node, String nodeId, List<NotebookNode> path) {
        if (node == null) {
            return null;
        }
        path.add(node);
        if (node.id.equals(nodeId)) {
            return node;
        }
        for (NotebookNode child : node.children) {
            NotebookNode found = findNodePath(child, nodeId, path);
            if (found != null) {
                return found;
            }
        }
        path.remove(path.size() - 1);
        return null;
    }

    private void focusCurrentModelMessage(ModelGenerationHudState.Snapshot snapshot) {
        if (snapshot == null || snapshot.requestId() == null) {
            return;
        }
        selectHistoryRequest(snapshot.requestId());
    }

    private void collectRequestFocusedNodes(
        NotebookNode node,
        ModelGenerationHudState.Snapshot snapshot,
        String requestId,
        String prompt,
        Set<String> evidenceTokens,
        List<NotebookNode> path,
        Set<String> focused
    ) {
        if (node == null) {
            return;
        }
        path.add(node);
        String searchable = (node.id + "\n" + node.title + "\n" + node.summary + "\n"
            + node.detail + "\n" + node.source + "\n" + node.traceId).toLowerCase(Locale.ROOT);
        boolean requestTrace = !requestId.isBlank() && searchable.contains(requestId);
        boolean promptTrace = node.id.startsWith("trace:") && !prompt.isBlank()
            && node.title.toLowerCase(Locale.ROOT).contains(prompt.substring(0, Math.min(prompt.length(), 48)));
        boolean evidence = false;
        for (String token : evidenceTokens) {
            if (token != null && token.length() >= 4 && searchable.contains(token)) {
                evidence = true;
                break;
            }
        }
        boolean currentActive = !snapshot.state().terminal() && isNodeActivelyWorking(node);

        if (requestTrace || promptTrace) {
            for (NotebookNode ancestor : path) {
                focused.add(ancestor.id);
            }
            addWholeSubtree(node, focused);
            path.remove(path.size() - 1);
            return;
        }
        if (evidence || currentActive) {
            for (NotebookNode ancestor : path) {
                focused.add(ancestor.id);
            }
        }
        for (NotebookNode child : node.children) {
            collectRequestFocusedNodes(child, snapshot, requestId, prompt, evidenceTokens, path, focused);
        }
        path.remove(path.size() - 1);
    }

    private void addWholeSubtree(NotebookNode node, Set<String> focused) {
        if (node == null) return;
        focused.add(node.id);
        for (NotebookNode child : node.children) {
            addWholeSubtree(child, focused);
        }
    }

    private void addFocusNodeWithPath(List<NotebookNode> roots, String nodeId, Set<String> focused) {
        for (NotebookNode root : roots) {
            List<NotebookNode> path = new ArrayList<>();
            NotebookNode found = findNodePath(root, nodeId, path);
            if (found != null) {
                for (NotebookNode node : path) {
                    focused.add(node.id);
                }
                return;
            }
        }
    }

    private void expandFocusedParents(List<NotebookNode> roots, Set<String> focused) {
        for (NotebookNode root : roots) {
            expandFocusedParents(root, focused);
        }
    }

    private boolean expandFocusedParents(NotebookNode node, Set<String> focused) {
        boolean contains = focused.contains(node.id);
        boolean childContains = false;
        for (NotebookNode child : node.children) {
            childContains |= expandFocusedParents(child, focused);
        }
        if (childContains) {
            focused.add(node.id);
            this.expandedNodes.add(node.id);
        }
        return contains || childContains;
    }

    private void refreshWorkspaceConversationEpoch() {
        long current = LocalModelService.conversationResetEpoch();
        if (this.observedConversationResetEpoch < 0L) {
            this.observedConversationResetEpoch = current;
            return;
        }
        if (current == this.observedConversationResetEpoch) return;

        this.observedConversationResetEpoch = current;
        this.workspaceMessageHistory.clear();
        this.workspaceRenderedMessageCache.clear();
        this.workspaceMessageSequence = 0L;
        this.selectedHistoryRequestId = null;
        this.followNextSubmittedRequest = false;
        syncWorkspaceConversationTranscript();
    }

    /**
     * Seeds the sidebar from the durable model transcript when the workspace opens.
     * Live snapshots then update the active request in place. This means reopening
     * Automation Workspace does not make earlier /ask or Automation exchanges vanish.
     */
    private void syncWorkspaceConversationTranscript() {
        List<ModelMessage> transcript = LocalModelService.conversationTranscriptSnapshot();
        if (transcript == null || transcript.isEmpty()) return;
        for (ModelMessage message : transcript) {
            if (message == null || message.content() == null || message.content().isBlank()) continue;
            if (message.role() != ModelRole.USER && message.role() != ModelRole.ASSISTANT) continue;
            if (message.role() == ModelRole.ASSISTANT && !message.toolCallId().isBlank()) continue;

            WorkspaceChatMessage existing = null;
            for (WorkspaceChatMessage candidate : this.workspaceMessageHistory) {
                if (message.id().equals(candidate.conversationMessageId)) {
                    existing = candidate;
                    break;
                }
            }
            if (existing != null) {
                existing.text = message.content();
                continue;
            }

            boolean model = message.role() == ModelRole.ASSISTANT;
            WorkspaceChatMessage matchedPlaceholder = null;
            for (int index = this.workspaceMessageHistory.size() - 1; index >= 0; index--) {
                WorkspaceChatMessage candidate = this.workspaceMessageHistory.get(index);
                if (candidate.conversationMessageId == null
                        && candidate.model == model
                        && conversationTextEquivalent(candidate.text, message.content(), model)) {
                    matchedPlaceholder = candidate;
                    break;
                }
            }
            if (matchedPlaceholder != null) {
                matchedPlaceholder.conversationMessageId = message.id();
                continue;
            }

            this.workspaceMessageHistory.add(new WorkspaceChatMessage(
                    ++this.workspaceMessageSequence,
                    model,
                    null,
                    message.id(),
                    message.content(),
                    null,
                    message.createdAt().toEpochMilli()
            ));
        }
        this.workspaceMessageHistory.sort(Comparator
                .comparingLong((WorkspaceChatMessage message) -> message.timestampMillis)
                .thenComparingLong(message -> message.sequence));
    }

    private WorkspaceChatMessage durableAssistantForSnapshot(
        ModelGenerationHudState.Snapshot snapshot,
        WorkspaceChatMessage matchedUser
    ) {
        if (snapshot == null || snapshot.text() == null || snapshot.text().isBlank()) return null;

        // Strongest match: same durable turn after the user we already matched.
        if (matchedUser != null) {
            int userIndex = this.workspaceMessageHistory.indexOf(matchedUser);
            for (int index = Math.max(0, userIndex + 1); index < this.workspaceMessageHistory.size(); index++) {
                WorkspaceChatMessage candidate = this.workspaceMessageHistory.get(index);
                if (!candidate.model) break;
                if (candidate.conversationMessageId != null) return candidate;
            }
        }

        // Next prefer normalized final text near this request's lifetime.
        // Streaming/materialization can change whitespace or Rich Chat cleanup, so
        // exact String equality is too brittle, while the timestamp floor prevents
        // repeated generic answers from binding to an older unrelated turn.
        long floor = Math.max(0L, snapshot.createdAtMillis() - 2_000L);
        for (int index = this.workspaceMessageHistory.size() - 1; index >= 0; index--) {
            WorkspaceChatMessage candidate = this.workspaceMessageHistory.get(index);
            if (!candidate.model || candidate.conversationMessageId == null) continue;
            if (candidate.timestampMillis < floor) continue;
            if (conversationTextEquivalent(candidate.text, snapshot.text(), true)) return candidate;
        }

        // A retained terminal snapshot belongs to the latest completed durable turn.
        // Timestamp matching keeps an old retained HUD request from binding to an
        // unrelated assistant message after a reset/reopen.
        for (int index = this.workspaceMessageHistory.size() - 1; index >= 0; index--) {
            WorkspaceChatMessage candidate = this.workspaceMessageHistory.get(index);
            if (!candidate.model || candidate.conversationMessageId == null) continue;
            if (candidate.timestampMillis >= floor) return candidate;
        }
        return null;
    }

    private WorkspaceChatMessage durableUserBefore(WorkspaceChatMessage assistant) {
        if (assistant == null) return null;
        int index = this.workspaceMessageHistory.indexOf(assistant);
        for (int cursor = index - 1; cursor >= 0; cursor--) {
            WorkspaceChatMessage candidate = this.workspaceMessageHistory.get(cursor);
            if (!candidate.model && candidate.conversationMessageId != null) return candidate;
        }
        return null;
    }

    private static boolean conversationTextEquivalent(String left, String right, boolean model) {
        String a = conversationComparable(left, model);
        String b = conversationComparable(right, model);
        if (a.equals(b)) return true;
        if (a.isBlank() || b.isBlank()) return false;
        // A terminal provider snapshot can contain a slightly shorter streaming
        // normalization of the same final answer. Require a substantial common
        // body before considering containment equivalent.
        int shorter = Math.min(a.length(), b.length());
        return shorter >= 48 && (a.contains(b) || b.contains(a));
    }

    private static String conversationComparable(String value, boolean model) {
        String clean = value == null ? "" : value;
        if (model) {
            clean = RichChatModelOutputSanitizer.normalizeStreamingPreview(clean);
            if (clean.startsWith(ModelChatIdentity.PREFIX)) {
                clean = clean.substring(ModelChatIdentity.PREFIX.length());
            }
        } else {
            clean = clean.strip();
            for (String prefix : new String[]{"/ask deep ", "/ask ", "/automate ", "/automation "}) {
                if (clean.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    clean = clean.substring(prefix.length());
                    break;
                }
            }
        }
        return clean.replaceAll("\\s+", " ").strip();
    }

    private void captureWorkspaceMessageHistory(
        ModelGenerationHudState.Snapshot liveSnapshot,
        ModelGenerationHudState.Snapshot metricsSnapshot
    ) {
        ModelGenerationHudState.Snapshot snapshot = liveSnapshot != null ? liveSnapshot : metricsSnapshot;
        if (snapshot == null || snapshot.requestId() == null) {
            return;
        }
        UUID requestId = snapshot.requestId();
        WorkspaceChatMessage user = null;
        WorkspaceChatMessage model = null;
        for (WorkspaceChatMessage entry : this.workspaceMessageHistory) {
            if (requestId.equals(entry.requestId)) {
                if (entry.model) model = entry;
                else user = entry;
            }
        }

        if (user == null && !snapshot.prompt().isBlank()) {
            for (int i = this.workspaceMessageHistory.size() - 1; i >= 0; i--) {
                WorkspaceChatMessage candidate = this.workspaceMessageHistory.get(i);
                if (!candidate.model && candidate.requestId == null
                        && conversationTextEquivalent(candidate.text, snapshot.prompt(), false)) {
                    candidate.requestId = requestId;
                    candidate.snapshot = snapshot;
                    user = candidate;
                    break;
                }
            }
        }

        WorkspaceChatMessage durableModel = model == null && snapshot.state().terminal()
                ? durableAssistantForSnapshot(snapshot, user)
                : null;
        if (user == null && durableModel != null) {
            user = durableUserBefore(durableModel);
            if (user != null) {
                user.requestId = requestId;
                user.snapshot = snapshot;
            }
        }

        if (user == null && !snapshot.prompt().isBlank()) {
            user = new WorkspaceChatMessage(++this.workspaceMessageSequence, false, requestId, snapshot.prompt(), snapshot, snapshot.createdAtMillis());
            this.workspaceMessageHistory.add(user);
        } else if (user != null) {
            user.snapshot = snapshot;
        }

        // A retained terminal HUD snapshot is presentation metadata for the durable
        // assistant turn, not another assistant message. Bind it to the transcript
        // row before any new model row can be created.
        if (model == null && !snapshot.text().isBlank()) {
            if (durableModel == null) durableModel = durableAssistantForSnapshot(snapshot, user);
            if (durableModel != null) {
                durableModel.requestId = requestId;
                durableModel.snapshot = snapshot;
                // The durable transcript owns final text. It is already sanitized
                // and should not be replaced by a retained streaming variant.
                if (!snapshot.state().terminal() || durableModel.text.isBlank()) {
                    durableModel.text = snapshot.text();
                }
                model = durableModel;
            }
        }

        if (model == null) {
            model = new WorkspaceChatMessage(++this.workspaceMessageSequence, true, requestId, snapshot.text(), snapshot, snapshot.createdAtMillis());
            int insert = user == null ? this.workspaceMessageHistory.size() : this.workspaceMessageHistory.indexOf(user) + 1;
            this.workspaceMessageHistory.add(Math.max(0, Math.min(insert, this.workspaceMessageHistory.size())), model);
        } else {
            if (!snapshot.state().terminal() || model.text.isBlank()) {
                model.text = snapshot.text();
            }
            model.snapshot = snapshot;
        }
        if (this.selectedHistoryRequestId == null || (this.followNextSubmittedRequest && snapshot.prompt().equals(this.lastSubmittedMessage))) {
            this.selectedHistoryRequestId = requestId;
            this.traceFilter = TraceFilter.MESSAGE;
            this.expandedNodes.add("request:" + requestId);
            this.expandedNodes.add("request:" + requestId + ":events");
            this.followNextSubmittedRequest = false;
        }
    }

    private void appendWorkspaceUserMessage(String text) {
        if (text == null || text.isBlank()) return;
        WorkspaceChatMessage last = this.workspaceMessageHistory.isEmpty()
            ? null : this.workspaceMessageHistory.get(this.workspaceMessageHistory.size() - 1);
        if (last != null && !last.model && last.requestId == null && last.text.equals(text)) {
            return;
        }
        this.workspaceMessageHistory.add(new WorkspaceChatMessage(
            ++this.workspaceMessageSequence, false, null, text, null, System.currentTimeMillis()
        ));
    }

    private List<WorkspaceRenderedMessage> buildWorkspaceRenderedMessages(int width) {
        List<WorkspaceRenderedMessage> out = new ArrayList<>();
        String player = playerName();
        for (WorkspaceChatMessage message : this.workspaceMessageHistory) {
            ModelGenerationHudState.Snapshot snapshot = message.snapshot;
            boolean active = message.model && snapshot != null && !snapshot.state().terminal();
            ModelActivityState state = snapshot == null ? ModelActivityState.IDLE : snapshot.activityState();
            String rawVisible = message.text == null ? "" : message.text;
            com.spirit.koil.api.model.ModelUsage usage = snapshot == null || snapshot.usage() == null
                ? com.spirit.koil.api.model.ModelUsage.empty()
                : snapshot.usage();
            ModelOutputMaterialization.Frame materialization = message.model && snapshot != null
                ? ModelOutputMaterialization.frameForPresentation(
                    snapshot.requestId(), rawVisible, active, usage.tokensPerSecond(), usage.completionTokens())
                : null;
            String visible = rawVisible;
            if (message.model) {
                // Automation Workspace is a detached renderer, so use the same staged
                // materialization boundary as the model popup. The raw generated text
                // remains authoritative in HUD state; only presentation is delayed by
                // a few glyphs so source characters can visibly be pulled into the
                // cursor before each word appears.
                if (materialization != null
                        && (materialization.presentationPending() || !materialization.presentedText().isBlank())) {
                    visible = materialization.presentedText();
                }
                visible = RichChatModelOutputSanitizer.normalizeStreamingPreview(visible);
            }
            // Generated model output is authoritative. Materialization may style the
            // raw stream, but it never substitutes a delayed/partial copy for it.
            boolean reserveCursor = message.model && materialization != null
                && materialization.cursorVisible();
            WorkspaceMessageRenderKey renderKey = workspaceMessageRenderKey(
                message,
                width,
                reserveCursor,
                visible
            );
            WorkspaceRenderedMessage cached = this.workspaceRenderedMessageCache.get(renderKey);
            if (cached != null) {
                // The body/layout is safe to cache, but the live footer is time-sensitive.
                // Recompute the cheap footer every frame so a state change replaces the old
                // status immediately instead of being frozen by the Rich Chat render cache.
                WorkspaceRenderedMessage current = active
                    ? refreshWorkspaceLiveFooter(cached, snapshot)
                    : cached;
                out.add(decorateWorkspaceMaterialization(current, materialization));
                continue;
            }

            // Keep the visible chat identity inside the Rich Chat source. Rich Chat
            // uses these prefixes to classify MODEL_RESPONSE / PLAYER_CHAT rows,
            // preserve structural wrapping, align continuations, and render all
            // supported markdown/links/tables/code/attachments consistently.
            String prefix = "";
            int prefixColor = message.model ? 0xFF596576 : 0xFFE0E0E0;
            int available = Math.max(24, width);
            List<WorkspaceRenderedLine> lines = new ArrayList<>();
            if (!visible.isBlank()) {
                String richSource = message.model
                    ? ModelChatIdentity.decorate(visible)
                    : "<" + player + "> " + visible;
                RichChatRowType rowType = message.model
                    ? RichChatRowType.MODEL_RESPONSE
                    : RichChatRowType.PLAYER_CHAT;
                int cursorReserve = reserveCursor
                    ? ModelOutputMaterialization.cursorWidth(this.textRenderer) + 1
                    : 0;
                int richWidth = Math.max(24, available - cursorReserve);
                List<OrderedText> wrapped = RichChatSurfaceRenderer.wrap(
                    this.textRenderer,
                    Text.literal(richSource),
                    rowType,
                    richWidth
                );
                for (int index = 0; index < wrapped.size(); index++) {
                    OrderedText line = wrapped.get(index);
                    lines.add(new WorkspaceRenderedLine(
                        line,
                        null,
                        message.model ? uiColorLocalModelPopupText : 0xFFE0E0E0,
                        Math.max(this.textRenderer.fontHeight + 2,
                            RichChatSurfaceRenderer.lineHeight(this.textRenderer, line)),
                        false
                    ));
                }
            }
            if (lines.isEmpty() && (!message.model || reserveCursor)) {
                Text identity = message.model
                    ? Text.literal(ModelChatIdentity.PREFIX)
                    : Text.literal("<" + player + "> ");
                List<OrderedText> identityLines = RichChatSurfaceRenderer.wrap(
                    this.textRenderer,
                    identity,
                    message.model ? RichChatRowType.MODEL_RESPONSE : RichChatRowType.PLAYER_CHAT,
                    available
                );
                OrderedText formattedIdentity = identityLines.isEmpty()
                    ? Text.empty().asOrderedText()
                    : identityLines.get(0);
                lines.add(new WorkspaceRenderedLine(
                    formattedIdentity, null,
                    message.model ? uiColorLocalModelPopupText : 0xFF8793A3,
                    this.textRenderer.fontHeight + 2,
                    false
                ));
            }

            List<WorkspaceToolRow> toolRows = message.model && snapshot != null
                ? buildSidebarToolRows(snapshot, Math.max(48, width))
                : List.of();

            boolean footerVisible = message.model && snapshot != null;
            String footerStatusLabel = "";
            String footerStatusDetail = "";
            String footerSemanticState = "";
            int footerColor = ModelSemanticPalette.color(state);
            String footerHistoryLabel = "";
            String footerHistoryDetail = "";
            int footerHistoryColor = footerColor;
            double footerHistoryAlpha = 0.0D;
            double tokensPerSecond = 0.0D;
            long createdAtMillis = message.timestampMillis;
            long completedAtMillis = 0L;
            if (footerVisible) {
                ModelRequestStatusPresentation.View view = ModelRequestStatusPresentation.forSnapshot(snapshot);
                footerStatusLabel = view.label();
                footerStatusDetail = view.detail();
                footerSemanticState = view.semanticState();
                footerColor = ModelSemanticPalette.color(view.activityState());
                if (snapshot.usage() != null) {
                    tokensPerSecond = snapshot.usage().tokensPerSecond();
                }
                createdAtMillis = snapshot.createdAtMillis();
                completedAtMillis = snapshot.completedAtMillis();
            }

            int toolHeight = toolRows.stream().mapToInt(WorkspaceToolRow::height).sum();
            int responseHeight = lines.stream().mapToInt(WorkspaceRenderedLine::height).sum();
            int between = message.model && !toolRows.isEmpty() && !lines.isEmpty() ? 1 : 0;
            int footerHeight = footerVisible ? this.textRenderer.fontHeight + 4 : 0;
            int height = 1 + toolHeight + between + responseHeight + footerHeight + 1;

            WorkspaceRenderedMessage rendered = new WorkspaceRenderedMessage(
                message.requestId,
                message.model,
                prefix,
                prefixColor,
                List.copyOf(lines),
                List.copyOf(toolRows),
                height,
                active,
                state,
                footerVisible,
                footerStatusLabel,
                footerStatusDetail,
                footerSemanticState,
                footerColor,
                footerHistoryLabel,
                footerHistoryDetail,
                footerHistoryColor,
                footerHistoryAlpha,
                tokensPerSecond,
                createdAtMillis,
                completedAtMillis,
                footerHeight
            );
            this.workspaceRenderedMessageCache.put(renderKey, rendered);
            out.add(decorateWorkspaceMaterialization(rendered, materialization));
        }
        return List.copyOf(out);
    }

    private WorkspaceMessageRenderKey workspaceMessageRenderKey(
        WorkspaceChatMessage message,
        int width,
        boolean cursorReserved,
        String presentedText
    ) {
        ModelGenerationHudState.Snapshot snapshot = message == null ? null : message.snapshot;
        List<ModelGenerationHudState.ActivityEvent> visibleEvents = snapshot == null
            ? List.of()
            : visibleModelEvents(snapshot.events());
        int eventsSize = visibleEvents.size();
        int tailEventHash = 0;
        if (eventsSize > 0) {
            ModelGenerationHudState.ActivityEvent tail = visibleEvents.get(eventsSize - 1);
            tailEventHash = tail == null ? 0 : tail.hashCode();
        }
        return new WorkspaceMessageRenderKey(
            message == null ? 0L : message.sequence,
            Math.max(1, width),
            presentedText == null ? 0 : presentedText.length(),
            presentedText == null ? 0 : tailTextHash(presentedText),
            snapshot == null || snapshot.state() == null ? 0 : snapshot.state().hashCode(),
            snapshot == null || snapshot.activityState() == null ? 0 : snapshot.activityState().hashCode(),
            snapshot == null ? 0 : Objects.hashCode(snapshot.detail()),
            eventsSize,
            tailEventHash,
            snapshot == null ? 0 : snapshot.toolCallCount(),
            snapshot == null ? 0 : snapshot.currentToolStep(),
            snapshot == null ? 0 : snapshot.totalToolSteps(),
            snapshot == null ? 0 : Objects.hashCode(snapshot.activeToolId()),
            snapshot == null ? 0 : Objects.hashCode(snapshot.activeToolDetail()),
            snapshot == null || snapshot.usage() == null ? 0 : snapshot.usage().hashCode(),
            cursorReserved,
            message != null && message.model
        );
    }

    private int tailTextHash(String value) {
        if (value == null || value.isEmpty()) return 0;
        int start = Math.max(0, value.length() - 32);
        int hash = 1;
        for (int i = start; i < value.length(); i++) {
            hash = 31 * hash + value.charAt(i);
        }
        return hash;
    }

    private WorkspaceRenderedMessage refreshWorkspaceLiveFooter(
        WorkspaceRenderedMessage base,
        ModelGenerationHudState.Snapshot snapshot
    ) {
        if (base == null || snapshot == null || snapshot.state().terminal() || !base.footerVisible()) {
            return base;
        }
        ModelRequestStatusPresentation.View view = ModelRequestStatusPresentation.forSnapshot(snapshot);
        double tokensPerSecond = snapshot.usage() == null ? 0.0D : snapshot.usage().tokensPerSecond();
        int footerHeight = this.textRenderer.fontHeight + 4;
        int height = Math.max(1, base.height() - base.footerHeight() + footerHeight);
        return new WorkspaceRenderedMessage(
            base.requestId(),
            base.model(),
            base.prefix(),
            base.prefixColor(),
            base.lines(),
            base.toolRows(),
            height,
            true,
            snapshot.activityState(),
            true,
            view.label(),
            view.detail(),
            view.semanticState(),
            ModelSemanticPalette.color(view.activityState()),
            "",
            "",
            ModelSemanticPalette.color(view.activityState()),
            0.0D,
            tokensPerSecond,
            snapshot.createdAtMillis(),
            snapshot.completedAtMillis(),
            footerHeight
        );
    }

    private WorkspaceRenderedMessage decorateWorkspaceMaterialization(
        WorkspaceRenderedMessage base,
        ModelOutputMaterialization.Frame frame
    ) {
        if (base == null || !base.model() || frame == null
            || base.lines().isEmpty() || (!frame.animating() && !frame.cursorVisible())) {
            return base;
        }
        List<OrderedText> source = new ArrayList<>();
        List<Integer> sourceIndexes = new ArrayList<>();
        for (int index = 0; index < base.lines().size(); index++) {
            WorkspaceRenderedLine line = base.lines().get(index);
            if (line.orderedText() != null) {
                source.add(line.orderedText());
                sourceIndexes.add(index);
            }
        }
        if (source.isEmpty()) return base;
        int protectedCharacters = ModelChatIdentity.PREFIX.codePointCount(0, ModelChatIdentity.PREFIX.length());
        List<OrderedText> materialized = ModelOutputMaterialization.applyWrappedTail(
            source, frame, protectedCharacters);
        List<WorkspaceRenderedLine> lines = new ArrayList<>(base.lines());
        int lastSource = sourceIndexes.get(sourceIndexes.size() - 1);
        for (int i = 0; i < sourceIndexes.size(); i++) {
            int lineIndex = sourceIndexes.get(i);
            WorkspaceRenderedLine original = lines.get(lineIndex);
            lines.set(lineIndex, new WorkspaceRenderedLine(
                materialized.get(i),
                original.animatedText(),
                original.color(),
                original.height(),
                frame.cursorVisible() && lineIndex == lastSource,
                frame
            ));
        }
        return new WorkspaceRenderedMessage(
            base.requestId(), base.model(), base.prefix(), base.prefixColor(),
            lines, base.toolRows(), base.height(), base.active(), base.state(),
            base.footerVisible(), base.footerStatusLabel(), base.footerStatusDetail(), base.footerSemanticState(),
            base.footerColor(), base.footerHistoryLabel(), base.footerHistoryDetail(),
            base.footerHistoryColor(), base.footerHistoryAlpha(), base.tokensPerSecond(), base.createdAtMillis(),
            base.completedAtMillis(), base.footerHeight()
        );
    }

    private List<WorkspaceToolRow> buildSidebarToolRows(
        ModelGenerationHudState.Snapshot snapshot,
        int width
    ) {
        if (snapshot == null || snapshot.requestId() == null
            || snapshot.events() == null || snapshot.events().isEmpty()) {
            return List.of();
        }

        int lastIncludedIndex = -1;
        for (int index = 0; index < snapshot.events().size(); index++) {
            ModelGenerationHudState.ActivityEvent event = snapshot.events().get(index);
            if (isSidebarTraceEvent(event)) {
                lastIncludedIndex = index;
            }
        }
        if (lastIncludedIndex < 0) {
            return List.of();
        }

        int prefixWidth = 0;
        List<WorkspaceToolRow> rows = new ArrayList<>();
        for (int index = 0; index < snapshot.events().size(); index++) {
            ModelGenerationHudState.ActivityEvent event = snapshot.events().get(index);
            if (!isSidebarTraceEvent(event)) {
                continue;
            }

            String badge = sidebarTraceBadge(event);
            int badgeWidth = this.textRenderer.getWidth(badge) + 5;
            int available = Math.max(24, width - prefixWidth - badgeWidth);
            boolean fullThought = event.type() == ModelGenerationHudState.ActivityEventType.THOUGHT_SUMMARY
                || event.type() == ModelGenerationHudState.ActivityEventType.THOUGHT_STOPPED;
            String visible = fullThought
                ? fullSidebarThoughtText(event)
                : compactSidebarTraceText(event);

            List<WorkspaceRenderedLine> renderedLines = new ArrayList<>();
            int rowHeight = 0;
            if (fullThought) {
                // Provider reasoning may contain paragraph breaks. Formatting it through the normal
                // chat preview can cause text after a blank line to re-enter response styling. Wrap
                // each reasoning paragraph explicitly so every continuation remains the same small,
                // dark-gray trace text, including after one or more blank lines.
                String normalizedThought = visible.replace("\r\n", "\n").replace('\r', '\n');
                String[] paragraphs = normalizedThought.split("\n", -1);
                for (String paragraph : paragraphs) {
                    if (paragraph.isEmpty()) {
                        renderedLines.add(new WorkspaceRenderedLine(
                            Text.empty().asOrderedText(), null, REASONING_TEXT_COLOR,
                            this.textRenderer.fontHeight + 2, false));
                        rowHeight += this.textRenderer.fontHeight + 2;
                        continue;
                    }
                    Text reasoningText = Text.literal(paragraph)
                        .styled(style -> style.withColor(REASONING_TEXT_COLOR));
                    for (OrderedText wrapped : this.textRenderer.wrapLines(reasoningText, available)) {
                        int lineHeight = this.textRenderer.fontHeight + 2;
                        renderedLines.add(new WorkspaceRenderedLine(
                            wrapped, null, REASONING_TEXT_COLOR, lineHeight, false));
                        rowHeight += lineHeight;
                    }
                }
            } else {
                Text formatted = RichChatPreviewFormatter.format(Text.literal(visible));
                for (OrderedText wrapped : this.textRenderer.wrapLines(formatted, available)) {
                    int lineHeight = Math.max(
                        this.textRenderer.fontHeight + 2,
                        RichChatSurfaceRenderer.lineHeight(this.textRenderer, wrapped)
                    );
                    renderedLines.add(new WorkspaceRenderedLine(
                        wrapped, null, uiColorLocalModelPopupText, lineHeight, false));
                    rowHeight += lineHeight;
                }
            }
            if (renderedLines.isEmpty()) {
                renderedLines.add(new WorkspaceRenderedLine(
                    Text.empty().asOrderedText(),
                    null,
                    uiColorLocalModelPopupText,
                    this.textRenderer.fontHeight + 2,
                    false
                ));
                rowHeight = this.textRenderer.fontHeight + 2;
            }

            ModelActivityState eventState = event.activityState() == null
                ? ModelActivityState.OBSERVING
                : event.activityState();
            boolean eventActive = !snapshot.state().terminal()
                && index == lastIncludedIndex
                && isActiveCanvasState(eventState);
            String nodeId = "request:" + snapshot.requestId() + ":events:" + index;
            rows.add(new WorkspaceToolRow(
                nodeId,
                badge,
                List.copyOf(renderedLines),
                Math.max(this.textRenderer.fontHeight + 2, rowHeight),
                eventState,
                eventActive
            ));
        }
        return List.copyOf(rows);
    }

    private boolean isSidebarTraceEvent(ModelGenerationHudState.ActivityEvent event) {
        return event != null && event.type() != null && isModelEventVisible(event);
    }

    private static boolean isModelEventVisible(ModelGenerationHudState.ActivityEvent event) {
        return event != null
            && ModelGenerationHudState.isPresentationEventVisible(event.type());
    }

    private static List<ModelGenerationHudState.ActivityEvent> visibleModelEvents(
        List<ModelGenerationHudState.ActivityEvent> events
    ) {
        if (events == null || events.isEmpty()) return List.of();
        return events.stream().filter(AutomationWorkspaceScreen::isModelEventVisible).toList();
    }

    private String fullSidebarThoughtText(ModelGenerationHudState.ActivityEvent event) {
        if (event == null) return "";
        String summary = event.summary() == null ? ""
            : RichChatModelOutputSanitizer.sanitizeRendererControls(event.summary()).strip();
        return summary.isBlank() ? friendly(event.type().name()) : summary;
    }

    private String compactSidebarTraceText(ModelGenerationHudState.ActivityEvent event) {
        if (event == null) return "";
        String summary = event.summary() == null ? "" : RichChatModelOutputSanitizer
            .sanitizeRendererControls(event.summary())
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replaceAll("\\s+", " ")
            .strip();
        if (summary.isBlank()) {
            summary = friendly(event.type().name());
        }
        // Keep the sidebar readable. Full event JSON, arguments, output, files,
        // validation payloads, failures, etc. remain available in the canvas pane.
        int maximum = switch (event.type()) {
            case TOOL_START, TOOL_PROGRESS, SKILL_START, SKILL_PROGRESS, SKILL_RESULT, COMMAND, FILE, DIFF -> 150;
            case VALIDATION, RESULT, FAILURE, REPLAN, APPROVAL -> 180;
            case CANCELLATION, CHECKPOINT, PLAN_STEP, STATUS, MODEL_DATA -> 160;
            case THOUGHT_SUMMARY, THOUGHT_STOPPED -> Integer.MAX_VALUE;
        };
        if (summary.length() > maximum) {
            summary = summary.substring(0, Math.max(0, maximum - 1)).stripTrailing() + "…";
        }
        return summary;
    }

    private String sidebarTraceBadge(ModelGenerationHudState.ActivityEvent event) {
        return switch (event.type()) {
            case THOUGHT_SUMMARY -> ModelGenerationHudState.exposedTag(event);
            case THOUGHT_STOPPED -> "THINKING";
            case MODEL_DATA -> "DATA";
            case STATUS -> "STATUS";
            case PLAN_STEP -> "PLAN";
            case APPROVAL -> "ASK";
            case TOOL_START -> "TOOL";
            case TOOL_PROGRESS -> "STEP";
            case SKILL_START -> "SKILL";
            case SKILL_PROGRESS -> "SKILL";
            case SKILL_RESULT -> "SKILL";
            case FILE -> "FILE";
            case DIFF -> "DIFF";
            case COMMAND -> "CMD";
            case VALIDATION -> "CHECK";
            case RESULT -> "RESULT";
            case FAILURE -> "FAIL";
            case REPLAN -> "REPLAN";
            case CANCELLATION -> "STOP";
            case CHECKPOINT -> "SAVE";
        };
    }

    private String sidebarTraceDataPreview(ModelGenerationHudState.ActivityEvent event) {
        if (event == null || event.data() == null || event.data().entrySet().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (var entry : event.data().entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            String value;
            try {
                value = entry.getValue().isJsonPrimitive()
                    ? entry.getValue().getAsString()
                    : entry.getValue().toString();
            } catch (RuntimeException ignored) {
                continue;
            }
            if (value == null || value.isBlank()) {
                continue;
            }
            String clean = value.replace('\r', ' ').strip();
            parts.add(entry.getKey() + "=" + clean);
        }
        return String.join("  |  ", parts);
    }

    private String playerName() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null && client.player.getGameProfile() != null) {
            String name = client.player.getGameProfile().getName();
            if (name != null && !name.isBlank()) return name;
        }
        return "Player";
    }

    private void selectHistoryRequest(UUID requestId) {
        if (requestId == null) return;
        this.selectedHistoryRequestId = requestId;
        this.traceFilter = TraceFilter.MESSAGE;
        this.selectedNodeId = "";
        this.selectedTraceId = "";
        this.fullyExpandedPaneId = "";
        this.pendingCenterNodeId = "";
        this.requestFocusMode = false;
        this.requestFocusRequestId = requestId.toString();
        this.requestFocusedNodeIds.clear();
        this.expandedNodes.clear();
        this.expandedNodes.add("request:" + requestId);
        this.expandedNodes.add("request:" + requestId + ":events");
        invalidateNotebookRenderModel();
        showNotice("Showing only the activity trace for the selected message.");
    }

    private ModelGenerationHudState.Snapshot selectedTraceSnapshot() {
        if (this.traceFilter == TraceFilter.ACTIVE) {
            ModelGenerationHudState.Snapshot live = ModelGenerationHudState.visibleSnapshot();
            return live != null && !live.state().terminal() ? live : null;
        }
        if (this.selectedHistoryRequestId != null) {
            for (int i = this.workspaceMessageHistory.size() - 1; i >= 0; i--) {
                WorkspaceChatMessage entry = this.workspaceMessageHistory.get(i);
                if (entry.model && this.selectedHistoryRequestId.equals(entry.requestId) && entry.snapshot != null) {
                    return entry.snapshot;
                }
            }
        }
        ModelGenerationHudState.Snapshot live = ModelGenerationHudState.visibleSnapshot();
        if (live != null) return live;
        return this.retainedModelSnapshot;
    }

    private void cycleTraceFilter() {
        this.traceFilter = this.traceFilter.next();
        this.selectedNodeId = "";
        this.selectedTraceId = "";
        this.fullyExpandedPaneId = "";
        this.pendingCenterNodeId = "";
        this.requestFocusMode = false;
        this.requestFocusedNodeIds.clear();
        this.expandedNodes.clear();
        for (NotebookNode root : buildNotebookRoots()) {
            this.expandedNodes.add(root.id);
        }
        invalidateNotebookRenderModel();
        showNotice("Trace filter: " + this.traceFilter.label() + ".");
    }

    private List<NotebookNode> buildNotebookRoots() {
        this.activeWorkspaceTraceIds.clear();
        this.activeCanvasNodeIds.clear();
        ModelGenerationHudState.Snapshot live = ModelGenerationHudState.visibleSnapshot();
        UUID telemetryRequestId = selectedTelemetryRequestId(live);
        if (this.workspaceViewMode == WorkspaceViewMode.TIMELINE
                || this.workspaceViewMode == WorkspaceViewMode.TOPOLOGY) {
            return withComparisonRoot(cachedTelemetryProjection(this.workspaceViewMode, telemetryRequestId));
        }
        if (this.traceFilter == TraceFilter.ALL) {
            return withComparisonRoot(buildAllNotebookRoots());
        }
        ModelGenerationHudState.Snapshot snapshot = this.traceFilter == TraceFilter.ACTIVE
            ? (live != null && !live.state().terminal() ? live : null)
            : selectedTraceSnapshot();
        if (snapshot == null) {
            NotebookNode empty = new NotebookNode(
                "request:none",
                this.traceFilter == TraceFilter.ACTIVE ? "No active request" : "Select a chat message",
                "TRACE",
                this.traceFilter == TraceFilter.ACTIVE ? "idle" : "message history",
                this.traceFilter == TraceFilter.ACTIVE
                    ? "No model request is currently generating or executing a tool."
                    : "Click a user or >_: model message in the left chat history to inspect only that request's activity tree.",
                "",
                ModelActivityState.IDLE,
                "",
                ""
            );
            return List.of(empty);
        }
        return withComparisonRoot(buildRequestNotebookRoots(snapshot));
    }

    private List<NotebookNode> cachedTelemetryProjection(WorkspaceViewMode mode, UUID requestId) {
        long revision = this.telemetryViewModel.projectionRevision(requestId);
        if (revision == this.cachedTelemetryProjectionRevision
                && Objects.equals(requestId, this.cachedTelemetryProjectionRequestId)
                && mode == this.cachedTelemetryProjectionMode
                && this.telemetryDetailMode == this.cachedTelemetryProjectionDetailMode) {
            return this.cachedTelemetryProjection;
        }
        List<NotebookNode> rebuilt = mode == WorkspaceViewMode.TIMELINE
                ? buildTelemetryTimelineRoots(requestId)
                : buildTelemetryTopologyRoots(requestId);
        this.cachedTelemetryProjectionRevision = revision;
        this.cachedTelemetryProjectionRequestId = requestId;
        this.cachedTelemetryProjectionMode = mode;
        this.cachedTelemetryProjectionDetailMode = this.telemetryDetailMode;
        this.cachedTelemetryProjection = List.copyOf(rebuilt);
        return this.cachedTelemetryProjection;
    }

    private void invalidateTelemetryProjection() {
        this.cachedTelemetryProjectionRevision = Long.MIN_VALUE;
        this.cachedTelemetryProjectionRequestId = null;
        this.cachedTelemetryProjectionMode = null;
        this.cachedTelemetryProjectionDetailMode = null;
        this.cachedTelemetryProjection = List.of();
    }

    private UUID selectedTelemetryRequestId(ModelGenerationHudState.Snapshot live) {
        if (this.traceFilter == TraceFilter.ACTIVE && live != null && !live.state().terminal()) {
            return live.requestId();
        }
        if (this.selectedHistoryRequestId != null) return this.selectedHistoryRequestId;
        ModelGenerationHudState.Snapshot selected = selectedTraceSnapshot();
        return selected == null ? null : selected.requestId();
    }

    private void cycleWorkspaceViewMode() {
        this.workspaceViewMode = this.workspaceViewMode.next();
        invalidateTelemetryProjection();
        this.selectedNodeId = "";
        this.fullyExpandedPaneId = "";
        this.notePositions.clear();
        this.expandedNodes.clear();
        for (NotebookNode root : buildNotebookRoots()) this.expandedNodes.add(root.id);
        invalidateNotebookRenderModel();
        showNotice("Workspace view: " + this.workspaceViewMode.label + ".");
    }

    private void cycleTelemetryDetailMode() {
        this.telemetryDetailMode = this.telemetryDetailMode.next();
        invalidateTelemetryProjection();
        this.selectedNodeId = "";
        this.fullyExpandedPaneId = "";
        invalidateNotebookRenderModel();
        showNotice("Telemetry text: " + this.telemetryDetailMode.label + ".");
    }

    private void captureComparisonRequest() {
        ModelGenerationHudState.Snapshot live = ModelGenerationHudState.visibleSnapshot();
        UUID selected = selectedTelemetryRequestId(live);
        if (selected == null) {
            showNotice("Select a request before capturing a comparison point.");
            return;
        }
        if (this.comparisonRequestA == null || this.comparisonRequestB != null) {
            this.comparisonRequestA = selected;
            this.comparisonRequestB = null;
            showNotice("Comparison A = " + shortId(selected) + ". Select another request and press Compare.");
            return;
        }
        if (this.comparisonRequestA.equals(selected)) {
            showNotice("Comparison B must be a different request.");
            return;
        }
        this.comparisonRequestB = selected;
        this.expandedNodes.add("telemetry:diff");
        this.pendingCenterNodeId = "telemetry:diff";
        invalidateNotebookRenderModel();
        showNotice("Comparing " + shortId(this.comparisonRequestA) + " -> " + shortId(this.comparisonRequestB) + ".");
    }

    private List<NotebookNode> withComparisonRoot(List<NotebookNode> roots) {
        if (this.comparisonRequestA == null || this.comparisonRequestB == null) return roots;
        NotebookNode diff = buildTelemetryDiffNode();
        if (diff == null) return roots;
        List<NotebookNode> out = new ArrayList<>();
        out.add(diff);
        if (roots != null) out.addAll(roots);
        return List.copyOf(out);
    }

    private NotebookNode buildTelemetryDiffNode() {
        return fromTelemetryProjection(AutomationWorkspaceTelemetryProjection.diff(
                this.telemetryViewModel, this.comparisonRequestA, this.comparisonRequestB));
    }

    private List<NotebookNode> buildTelemetryTimelineRoots(UUID requestId) {
        return fromTelemetryProjection(AutomationWorkspaceTelemetryProjection.build(
                this.telemetryViewModel, AutomationWorkspaceTelemetryProjection.View.TIMELINE,
                requestId, telemetryProjectionDetail()));
    }

    private List<NotebookNode> buildTelemetryTopologyRoots(UUID requestId) {
        return fromTelemetryProjection(AutomationWorkspaceTelemetryProjection.build(
                this.telemetryViewModel, AutomationWorkspaceTelemetryProjection.View.TOPOLOGY,
                requestId, telemetryProjectionDetail()));
    }

    private AutomationWorkspaceTelemetryProjection.Detail telemetryProjectionDetail() {
        return switch (this.telemetryDetailMode) {
            case SUMMARY -> AutomationWorkspaceTelemetryProjection.Detail.SUMMARY;
            case DETAILS -> AutomationWorkspaceTelemetryProjection.Detail.DETAILS;
            case RAW -> AutomationWorkspaceTelemetryProjection.Detail.RAW;
        };
    }

    private List<NotebookNode> fromTelemetryProjection(List<AutomationWorkspaceTelemetryProjection.Node> roots) {
        if (roots == null || roots.isEmpty()) return List.of();
        List<NotebookNode> out = new ArrayList<>(roots.size());
        for (AutomationWorkspaceTelemetryProjection.Node root : roots) {
            NotebookNode converted = fromTelemetryProjection(root);
            if (converted != null) out.add(converted);
        }
        return List.copyOf(out);
    }

    private NotebookNode fromTelemetryProjection(AutomationWorkspaceTelemetryProjection.Node source) {
        if (source == null) return null;
        NotebookNode node = new NotebookNode(
                source.id, source.title, source.kind, source.summary, "", "TelemetryStore",
                modelState(source.state), "", ""
        );
        node.typedDetailLines = source.text == null ? List.of() : List.copyOf(source.text);
        node.inboundFlow = source.inboundFlow == null
            ? AutomationWorkspaceTelemetryProjection.Flow.none()
            : source.inboundFlow;
        node.startedAtMillis = source.startedAtMillis;
        node.durationMillis = source.durationMillis;
        node.temporalActive = source.active;
        node.concurrentPeers = source.concurrentPeers;
        node.links.addAll(source.links);
        for (AutomationWorkspaceTelemetryProjection.Node child : source.children) {
            node.add(fromTelemetryProjection(child));
        }
        return node;
    }

    private NotebookNode telemetryUnavailableNode(UUID requestId) {
        List<AutomationWorkspaceTelemetryProjection.Node> projected = AutomationWorkspaceTelemetryProjection.build(
                this.telemetryViewModel, AutomationWorkspaceTelemetryProjection.View.TOPOLOGY,
                requestId, telemetryProjectionDetail());
        return projected.isEmpty() ? null : fromTelemetryProjection(projected.get(0));
    }

    private static ModelActivityState modelState(TelemetryCapabilityState state) {
        if (state == null) return ModelActivityState.IDLE;
        return switch (state) {
            case AVAILABLE -> ModelActivityState.COMPLETE;
            case ACTIVE -> ModelActivityState.EXECUTING;
            case DEGRADED -> ModelActivityState.OBSERVING;
            case BLOCKED -> ModelActivityState.BLOCKED;
            case NOT_IMPLEMENTED, UNAVAILABLE, FAILED -> ModelActivityState.FAILED;
            case CANCELLED -> ModelActivityState.CANCELLED;
            case IDLE -> ModelActivityState.IDLE;
        };
    }

    private static String shortId(UUID id) {
        if (id == null) return "none";
        String value = id.toString();
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private List<NotebookNode> buildRequestNotebookRoots(ModelGenerationHudState.Snapshot snapshot) {
        String requestId = snapshot.requestId() == null ? "unknown" : snapshot.requestId().toString();
        String activityTraceId = "request:" + requestId;
        ModelActivityState requestState = snapshot.state().terminal() ? terminalState(snapshot) : snapshot.activityState();
        String requestSource = "src/main/java/com/spirit/koil/api/model/chat/ModelGenerationHudState.java";

        NotebookNode root = new NotebookNode(
            activityTraceId,
            snapshot.automationRequest() ? "Automation neural execution trace" : "Model neural execution trace",
            "REQUEST",
            "request " + requestId + " | " + friendly(requestState.id()),
            "request=" + requestId
                + "\nmode=" + (snapshot.automationRequest() ? "automation" : "ask")
                + "\nstate=" + snapshot.state().name().toLowerCase(Locale.ROOT)
                + "\nactivity=" + snapshot.activityState().id()
                + "\nsession=" + snapshot.sessionNumber()
                + "\ncreated=" + snapshot.createdAtMillis()
                + "\ncompleted=" + snapshot.completedAtMillis()
                + "\ntoolCalls=" + snapshot.toolCallCount()
                + "\nactiveTool=" + snapshot.activeToolId()
                + "\nactiveToolDetail=" + snapshot.activeToolDetail(),
            requestSource,
            requestState,
            "",
            activityTraceId
        );
        if (!snapshot.state().terminal()) {
            this.activeCanvasNodeIds.add(root.id);
        }

        String promptText = snapshot.prompt() == null ? "" : snapshot.prompt();
        NotebookNode prompt = new NotebookNode(
            activityTraceId + ":prompt",
            "Request ingress",
            "INPUT",
            "User input received",
            "request=" + requestId
                + "\nmode=" + (snapshot.automationRequest() ? "automation" : "ask")
                + "\ncharacters=" + promptText.length()
                + "\nlines=" + Math.max(1, promptText.split("\\R", -1).length)
                + "\ncreated=" + snapshot.createdAtMillis(),
            requestSource,
            snapshot.state().terminal() ? ModelActivityState.COMPLETE : ModelActivityState.INSPECTING,
            "",
            activityTraceId
        );
        root.add(prompt);

        String contextDetail = "requestState=" + snapshot.state().name().toLowerCase(Locale.ROOT)
            + "\nactivityState=" + snapshot.activityState().id()
            + "\nqueueDepth=" + LocalModelService.queueDepth()
            + "\ncontextWindowTokens=" + LocalModelService.configuredContextWindowTokens()
            + "\ntoolCalls=" + snapshot.toolCallCount()
            + "\ntoolStep=" + snapshot.currentToolStep() + "/" + snapshot.totalToolSteps()
            + "\nactiveTool=" + snapshot.activeToolId()
            + "\nactiveToolDetail=" + snapshot.activeToolDetail()
            + "\napprovalPending=" + (snapshot.approval() != null)
            + "\ndeepThought=" + (snapshot.deepThoughtStatus() == null ? "none" : snapshot.deepThoughtStatus());
        NotebookNode context = new NotebookNode(
            activityTraceId + ":context",
            "Request context / routing",
            "CONTEXT",
            friendly(snapshot.activityState().id()),
            contextDetail,
            "src/main/java/com/spirit/koil/api/model/LocalModelService.java",
            snapshot.state().terminal() ? ModelActivityState.COMPLETE : snapshot.activityState(),
            "",
            activityTraceId
        );
        root.add(context);
        prompt.links.add(context.id);

        String response = RichChatModelOutputSanitizer.normalizeStreamingPreview(snapshot.text());
        var usage = snapshot.usage();
        NotebookNode tokens = null;
        if (ModelDebugMode.enabled()) {
            String tokenDetail = "promptTokens=" + usage.promptTokens()
                + "\ncompletionTokens=" + usage.completionTokens()
                + "\nreusedPrefixTokens=" + usage.reusedPrefixTokens()
                + "\nqueueMs=" + usage.queueMillis()
                + "\ntimeToFirstTokenMs=" + usage.timeToFirstTokenMillis()
                + "\noutputTokensPerSecond=" + String.format(Locale.ROOT, "%.2f", usage.tokensPerSecond())
                + "\nstreamedCharacters=" + response.length()
                + "\nproviderTelemetry=prompt progress, timing, runtime configuration, and token-id tails when supported"
                + "\nproviderReasoning=shown only when the provider/model emits an explicit reasoning channel"
                + "\ntelemetryIsNotThought=true";
            ModelActivityState tokenState = !snapshot.state().terminal()
                && (snapshot.state().name().contains("GENERAT") || snapshot.state().name().contains("PREFILL"))
                ? snapshot.activityState()
                : snapshot.state().terminal() ? terminalState(snapshot) : ModelActivityState.OBSERVING;
            tokens = new NotebookNode(
                activityTraceId + ":tokens",
                "Token processing telemetry",
                "TOKENS",
                usage.promptTokens() + " prompt | "
                    + usage.completionTokens() + " output | "
                    + String.format(Locale.ROOT, "%.2f tok/s", usage.tokensPerSecond()),
                tokenDetail,
                "src/main/java/com/spirit/koil/api/model/ModelUsage.java",
                tokenState,
                "",
                activityTraceId
            );
            root.add(tokens);
            context.links.add(tokens.id);
            if (!snapshot.state().terminal()
                && (snapshot.state().name().contains("GENERAT") || snapshot.state().name().contains("PREFILL"))) {
                this.activeCanvasNodeIds.add(tokens.id);
            }
        }

        NotebookNode events = new NotebookNode(
            activityTraceId + ":events",
            "Recorded thinking / action path",
            "ACTIVITY",
            visibleModelEvents(snapshot.events()).size() + " recorded events",
            "Chronological request activity. Model-exposed panes preserve the channel tag emitted or identified from provider-native output; "
                + "tool, executor, observation, validation, recovery, and result panes are connected in recorded order.",
            requestSource,
            snapshot.state().terminal() ? ModelActivityState.COMPLETE : snapshot.activityState(),
            "",
            activityTraceId
        );
        root.add(events);
        context.links.add(events.id);
        if (!snapshot.state().terminal()) {
            this.activeCanvasNodeIds.add(events.id);
        }

        List<ModelGenerationHudState.ActivityEvent> recordedEvents = visibleModelEvents(snapshot.events());
        List<NotebookNode> eventNodes = new ArrayList<>();
        int lastEventIndex = recordedEvents.size() - 1;
        for (int index = 0; index < recordedEvents.size(); index++) {
            ModelGenerationHudState.ActivityEvent event = recordedEvents.get(index);
            if (event == null) {
                eventNodes.add(null);
                continue;
            }

            String json = event.data() == null || event.data().entrySet().isEmpty()
                ? ""
                : new GsonBuilder().setPrettyPrinting().create().toJson(event.data());
            long elapsed = Math.max(0L, event.timestampMillis() - snapshot.createdAtMillis());
            String previous = previousRecordedEventDescription(recordedEvents, index);
            String next = nextRecordedEventDescription(recordedEvents, index);
            int effectIndex = event.type() == ModelGenerationHudState.ActivityEventType.THOUGHT_SUMMARY
                ? nextCausalEffectIndex(recordedEvents, index + 1)
                : -1;
            String nextEffect = effectIndex >= 0
                ? eventDescriptor(recordedEvents.get(effectIndex), effectIndex)
                : "";

            String detail = "index=" + index
                + "\ntype=" + event.type().name().toLowerCase(Locale.ROOT)
                + "\nactivity=" + (event.activityState() == null ? "observing" : event.activityState().id())
                + "\neventId=" + event.eventId()
                + "\ntimestamp=" + event.timestampMillis()
                + "\nelapsedFromPromptMs=" + elapsed
                + "\nsummary=" + event.summary()
                + "\nprevious=" + previous
                + "\nnext=" + next
                + (nextEffect.isBlank() ? "" : "\nnextRecordedEffect=" + nextEffect)
                + (json.isBlank() ? "" : "\ndata=\n" + json);

            String eventNodeId = events.id + ":" + index;
            String eventKind = sidebarTraceBadge(event);
            String eventTitle = event.type() == ModelGenerationHudState.ActivityEventType.THOUGHT_SUMMARY
                ? ModelGenerationHudState.exposedLabel(event) + " " + (countEventTypeBefore(
                recordedEvents,
                index,
                ModelGenerationHudState.ActivityEventType.THOUGHT_SUMMARY
            ) + 1)
                : "Event " + (index + 1) + " · " + friendly(event.type().name());

            NotebookNode eventNode = new NotebookNode(
                eventNodeId,
                eventTitle,
                eventKind,
                event.summary(),
                detail,
                sourceFromEvent(event),
                event.activityState(),
                "",
                activityTraceId
            );
            events.add(eventNode);
            eventNodes.add(eventNode);

            if (!snapshot.state().terminal() && index == lastEventIndex
                && isActiveCanvasState(event.activityState())) {
                this.activeCanvasNodeIds.add(eventNodeId);
            }
        }

        NotebookNode previousEventNode = null;
        for (NotebookNode eventNode : eventNodes) {
            if (eventNode == null) {
                continue;
            }
            if (previousEventNode == null) {
                context.links.add(eventNode.id);
            } else {
                previousEventNode.links.add(eventNode.id);
            }
            previousEventNode = eventNode;
        }

        for (int index = 0; index < recordedEvents.size(); index++) {
            ModelGenerationHudState.ActivityEvent event = recordedEvents.get(index);
            if (event == null || event.type() != ModelGenerationHudState.ActivityEventType.THOUGHT_SUMMARY) {
                continue;
            }
            int effectIndex = nextCausalEffectIndex(recordedEvents, index + 1);
            if (effectIndex >= 0
                && index < eventNodes.size()
                && effectIndex < eventNodes.size()
                && eventNodes.get(index) != null
                && eventNodes.get(effectIndex) != null) {
                eventNodes.get(index).links.add(eventNodes.get(effectIndex).id);
            }
        }

        NotebookNode plan = null;
        if (snapshot.plan() != null && snapshot.plan().steps() != null && !snapshot.plan().steps().isEmpty()) {
            plan = new NotebookNode(
                activityTraceId + ":plan",
                "Plan " + snapshot.plan().planId(),
                "PLAN",
                snapshot.plan().revised()
                    ? "revised | " + snapshot.plan().steps().size() + " steps"
                    : snapshot.plan().steps().size() + " steps",
                "planId=" + snapshot.plan().planId()
                    + "\nrevised=" + snapshot.plan().revised()
                    + "\nobjective=" + promptText,
                requestSource,
                snapshot.state().terminal() ? ModelActivityState.COMPLETE : ModelActivityState.PLANNING,
                "",
                activityTraceId
            );
            root.add(plan);
            events.links.add(plan.id);
            if (!snapshot.state().terminal() && snapshot.activityState() == ModelActivityState.PLANNING) {
                this.activeCanvasNodeIds.add(plan.id);
            }

            for (ModelGenerationHudState.PlanStep step : snapshot.plan().steps()) {
                if (step == null) continue;
                String detail = "index=" + step.index()
                    + "\ntool=" + step.toolId()
                    + "\nsummary=" + step.summary()
                    + "\nstatus=" + (step.status() == null ? "pending" : step.status().name().toLowerCase(Locale.ROOT))
                    + "\narguments=" + step.arguments()
                    + "\nexpectedObservation=" + step.expectedObservation()
                    + "\nvalidationRequirement=" + step.validationRequirement()
                    + "\nresult=" + step.result();

                NotebookNode stepNode = new NotebookNode(
                    plan.id + ":step:" + step.index(),
                    "Step " + step.index(),
                    "TASK",
                    step.summary(),
                    detail,
                    "",
                    stateForPlan(step.status()),
                    "",
                    activityTraceId
                );
                plan.add(stepNode);

                int eventIndex = findRecordedEventForPlanStep(recordedEvents, step);
                if (eventIndex >= 0 && eventIndex < eventNodes.size() && eventNodes.get(eventIndex) != null) {
                    stepNode.links.add(eventNodes.get(eventIndex).id);
                }

                if (!snapshot.state().terminal()
                    && step.status() == ModelGenerationHudState.PlanStepStatus.ACTIVE) {
                    this.activeCanvasNodeIds.add(stepNode.id);
                }
            }
        }

        if (snapshot.toolCallCount() > 0
            || !snapshot.activeToolId().isBlank()
            || snapshot.currentToolStep() > 0
            || snapshot.totalToolSteps() > 0) {
            NotebookNode toolRouter = new NotebookNode(
                activityTraceId + ":tool-routing",
                "Tool routing / execution handoff",
                "TOOL ROUTER",
                snapshot.activeToolId().isBlank()
                    ? snapshot.toolCallCount() + " tool calls"
                    : snapshot.activeToolId(),
                "toolCalls=" + snapshot.toolCallCount()
                    + "\ncurrentStep=" + snapshot.currentToolStep()
                    + "\ntotalSteps=" + snapshot.totalToolSteps()
                    + "\nactiveTool=" + snapshot.activeToolId()
                    + "\nactiveToolDetail=" + snapshot.activeToolDetail(),
                "src/main/java/com/spirit/koil/api/model/LocalModelService.java",
                snapshot.state().terminal() ? ModelActivityState.COMPLETE : ModelActivityState.EXECUTING,
                "",
                activityTraceId
            );
            root.add(toolRouter);
            int toolEventIndex = firstToolEventIndex(recordedEvents);
            if (toolEventIndex >= 0 && toolEventIndex < eventNodes.size() && eventNodes.get(toolEventIndex) != null) {
                toolRouter.links.add(eventNodes.get(toolEventIndex).id);
            } else {
                context.links.add(toolRouter.id);
            }
            if (!snapshot.state().terminal() && !snapshot.activeToolId().isBlank()) {
                this.activeCanvasNodeIds.add(toolRouter.id);
            }
        }

        if (snapshot.approval() != null) {
            ModelGenerationHudState.Approval approval = snapshot.approval();
            NotebookNode approvalNode = new NotebookNode(
                activityTraceId + ":approval",
                approval.title(),
                "APPROVAL",
                approval.message(),
                "approveLabel=" + approval.approveLabel()
                    + "\ndenyLabel=" + approval.denyLabel()
                    + "\nrequest=" + requestId,
                requestSource,
                ModelActivityState.AWAITING_APPROVAL,
                "",
                activityTraceId
            );
            root.add(approvalNode);
            int approvalEventIndex = firstEventIndex(recordedEvents, ModelGenerationHudState.ActivityEventType.APPROVAL);
            if (approvalEventIndex >= 0 && approvalEventIndex < eventNodes.size() && eventNodes.get(approvalEventIndex) != null) {
                approvalNode.links.add(eventNodes.get(approvalEventIndex).id);
            }
            this.activeCanvasNodeIds.add(approvalNode.id);
        }

        if (snapshot.deepThoughtStatus() != null) {
            NotebookNode deepThought = new NotebookNode(
                activityTraceId + ":deep-thought",
                "Deep Thought control",
                "THINKING",
                String.valueOf(snapshot.deepThoughtStatus()),
                "status=" + snapshot.deepThoughtStatus()
                    + "\nanswerNowVisible=" + snapshot.answerNowVisible()
                    + "\nanswerNowRequested=" + snapshot.answerNowRequested(),
                "src/main/java/com/spirit/koil/api/model/ModelDeepThoughtControl.java",
                snapshot.state().terminal() ? terminalState(snapshot) : ModelActivityState.THINKING,
                "",
                activityTraceId
            );
            root.add(deepThought);
            prompt.links.add(deepThought.id);
        }

        List<AutomationWorkspaceTrace> matching = matchingTracesForSnapshot(snapshot);
        if (ModelDebugMode.enabled()) {
            NotebookNode coverage = buildRequestObservabilityCoverage(snapshot, matching);
            root.add(coverage);
            context.links.add(coverage.id);
        }

        NotebookNode execution = null;
        if (!matching.isEmpty()) {
            execution = new NotebookNode(
                activityTraceId + ":workspace",
                "Legacy repository correlation (fallback)",
                "FALLBACK TRACE",
                matching.size() + " heuristically related repository traces",
                "Fallback-only correlation for legacy traces that do not carry canonical telemetry IDs."
                    + "\nSignals may include request id, objective text, tool evidence, or a ±2.5 second request-time window."
                    + "\nCanonical spanId/parentSpanId links take precedence and are shown separately.",
                "AutomationWorkspaceRepository",
                snapshot.state().terminal() ? ModelActivityState.COMPLETE : ModelActivityState.EXECUTING,
                "",
                activityTraceId
            );
            root.add(execution);
            int lastActionIndex = lastCausalActionIndex(recordedEvents);
            if (lastActionIndex >= 0 && lastActionIndex < eventNodes.size() && eventNodes.get(lastActionIndex) != null) {
                eventNodes.get(lastActionIndex).links.add(execution.id);
            } else if (plan != null) {
                plan.links.add(execution.id);
            } else {
                events.links.add(execution.id);
            }

            if (!snapshot.state().terminal() && !snapshot.activeToolId().isBlank()) {
                this.activeCanvasNodeIds.add(execution.id);
            }
            for (AutomationWorkspaceTrace trace : matching) {
                execution.add(buildTraceNode(trace));
            }
        }

        NotebookNode output = new NotebookNode(
            activityTraceId + ":output",
            snapshot.state().terminal() ? "Generated response" : "Generated response (live)",
            "OUTPUT",
            response.isBlank()
                ? "No output text has streamed yet."
                : response,
            "request=" + requestId
                + "\nstate=" + snapshot.state().name().toLowerCase(Locale.ROOT)
                + "\ncharacters=" + response.length()
                + "\ntext=\n" + (response.isBlank() ? "<empty>" : response),
            requestSource,
            snapshot.state().terminal() ? terminalState(snapshot) : snapshot.activityState(),
            "",
            activityTraceId
        );
        root.add(output);
        if (previousEventNode != null) {
            previousEventNode.links.add(output.id);
        } else if (execution != null) {
            execution.links.add(output.id);
        } else {
            context.links.add(output.id);
        }
        if (tokens != null) {
            tokens.links.add(output.id);
        }

        if (!snapshot.state().terminal()
            && (snapshot.activityState() == ModelActivityState.WRITING
            || snapshot.activityState() == ModelActivityState.FINALIZING
            || snapshot.state().name().contains("GENERAT"))) {
            this.activeCanvasNodeIds.add(output.id);
        }

        List<NotebookNode> canonicalRoots = buildTelemetryTopologyRoots(snapshot.requestId());
        NotebookNode canonicalNode = canonicalRoots.isEmpty()
                ? telemetryUnavailableNode(snapshot.requestId())
                : canonicalRoots.get(0);
        root.add(canonicalNode);
        context.links.add(canonicalNode.id);
        this.expandedNodes.add(canonicalNode.id);

        this.expandedNodes.add(root.id);
        this.expandedNodes.add(events.id);
        return List.of(root);
    }

    private int countEventTypeBefore(
        List<ModelGenerationHudState.ActivityEvent> events,
        int exclusiveIndex,
        ModelGenerationHudState.ActivityEventType type
    ) {
        int count = 0;
        for (int index = 0; index < Math.min(exclusiveIndex, events.size()); index++) {
            ModelGenerationHudState.ActivityEvent event = events.get(index);
            if (event != null && event.type() == type) {
                count++;
            }
        }
        return count;
    }

    private String previousRecordedEventDescription(List<ModelGenerationHudState.ActivityEvent> events, int index) {
        for (int cursor = index - 1; cursor >= 0; cursor--) {
            ModelGenerationHudState.ActivityEvent event = events.get(cursor);
            if (event != null) {
                return eventDescriptor(event, cursor);
            }
        }
        return "prompt ingress";
    }

    private String nextRecordedEventDescription(List<ModelGenerationHudState.ActivityEvent> events, int index) {
        for (int cursor = index + 1; cursor < events.size(); cursor++) {
            ModelGenerationHudState.ActivityEvent event = events.get(cursor);
            if (event != null) {
                return eventDescriptor(event, cursor);
            }
        }
        return "generated response";
    }

    private String eventDescriptor(ModelGenerationHudState.ActivityEvent event, int index) {
        if (event == null) {
            return "";
        }
        String summary = event.summary() == null ? "" : event.summary().strip();
        return "#" + (index + 1) + " "
            + event.type().name().toLowerCase(Locale.ROOT)
            + (summary.isBlank() ? "" : " | " + summary);
    }

    private int nextCausalEffectIndex(List<ModelGenerationHudState.ActivityEvent> events, int start) {
        for (int index = Math.max(0, start); index < events.size(); index++) {
            ModelGenerationHudState.ActivityEvent event = events.get(index);
            if (event == null || event.type() == null) {
                continue;
            }
            if (switch (event.type()) {
                case PLAN_STEP, APPROVAL, TOOL_START, TOOL_PROGRESS, SKILL_START, SKILL_PROGRESS, SKILL_RESULT, FILE, DIFF, COMMAND,
                     VALIDATION, RESULT, FAILURE, REPLAN, CANCELLATION, CHECKPOINT -> true;
                default -> false;
            }) {
                return index;
            }
        }
        return -1;
    }

    private int firstToolEventIndex(List<ModelGenerationHudState.ActivityEvent> events) {
        for (int index = 0; index < events.size(); index++) {
            ModelGenerationHudState.ActivityEvent event = events.get(index);
            if (event == null || event.type() == null) continue;
            if (event.type() == ModelGenerationHudState.ActivityEventType.TOOL_START
                || event.type() == ModelGenerationHudState.ActivityEventType.TOOL_PROGRESS
                || event.type() == ModelGenerationHudState.ActivityEventType.SKILL_START
                || event.type() == ModelGenerationHudState.ActivityEventType.SKILL_PROGRESS
                || event.type() == ModelGenerationHudState.ActivityEventType.SKILL_RESULT
                || event.type() == ModelGenerationHudState.ActivityEventType.COMMAND
                || event.type() == ModelGenerationHudState.ActivityEventType.FILE
                || event.type() == ModelGenerationHudState.ActivityEventType.DIFF) {
                return index;
            }
        }
        return -1;
    }

    private int lastCausalActionIndex(List<ModelGenerationHudState.ActivityEvent> events) {
        for (int index = events.size() - 1; index >= 0; index--) {
            ModelGenerationHudState.ActivityEvent event = events.get(index);
            if (event == null || event.type() == null) continue;
            if (switch (event.type()) {
                case TOOL_START, TOOL_PROGRESS, SKILL_START, SKILL_PROGRESS, SKILL_RESULT, FILE, DIFF, COMMAND, VALIDATION, RESULT,
                     FAILURE, REPLAN, CANCELLATION, CHECKPOINT -> true;
                default -> false;
            }) {
                return index;
            }
        }
        return -1;
    }

    private int firstEventIndex(
        List<ModelGenerationHudState.ActivityEvent> events,
        ModelGenerationHudState.ActivityEventType type
    ) {
        for (int index = 0; index < events.size(); index++) {
            ModelGenerationHudState.ActivityEvent event = events.get(index);
            if (event != null && event.type() == type) {
                return index;
            }
        }
        return -1;
    }

    private int findRecordedEventForPlanStep(
        List<ModelGenerationHudState.ActivityEvent> events,
        ModelGenerationHudState.PlanStep step
    ) {
        if (step == null) {
            return -1;
        }
        String tool = step.toolId() == null ? "" : step.toolId().strip().toLowerCase(Locale.ROOT);
        String summary = step.summary() == null ? "" : step.summary().strip().toLowerCase(Locale.ROOT);
        for (int index = 0; index < events.size(); index++) {
            ModelGenerationHudState.ActivityEvent event = events.get(index);
            if (event == null) continue;
            String eventSummary = event.summary() == null ? "" : event.summary();
            String haystack = (eventSummary + "\n" + (event.data() == null ? "" : event.data().toString()))
                .toLowerCase(Locale.ROOT);
            if (!tool.isBlank() && haystack.contains(tool)) {
                return index;
            }
            if (tool.isBlank() && !summary.isBlank() && summary.length() >= 6 && haystack.contains(summary)) {
                return index;
            }
        }
        return -1;
    }

    private ModelActivityState terminalState(ModelGenerationHudState.Snapshot snapshot) {
        if (snapshot == null) return ModelActivityState.IDLE;
        return switch (snapshot.state()) {
            case FAILED, BLOCKED -> ModelActivityState.FAILED;
            case CANCELLED, CANCELLING -> ModelActivityState.CANCELLED;
            default -> ModelActivityState.COMPLETE;
        };
    }

    private List<AutomationWorkspaceTrace> matchingTracesForSnapshot(ModelGenerationHudState.Snapshot snapshot) {
        if (snapshot == null) return List.of();
        String requestId = snapshot.requestId() == null ? "" : snapshot.requestId().toString().toLowerCase(Locale.ROOT);
        String prompt = snapshot.prompt() == null ? "" : snapshot.prompt().strip().toLowerCase(Locale.ROOT);
        String promptPrefix = prompt.substring(0, Math.min(prompt.length(), 64));
        Set<String> evidence = new LinkedHashSet<>();
        if (snapshot.activeToolId() != null && !snapshot.activeToolId().isBlank()) evidence.add(snapshot.activeToolId().toLowerCase(Locale.ROOT));
        if (snapshot.events() != null) {
            for (ModelGenerationHudState.ActivityEvent event : snapshot.events()) {
                if (event == null) continue;
                if (event.eventId() != null && !event.eventId().isBlank()) evidence.add(event.eventId().toLowerCase(Locale.ROOT));
                String source = sourceFromEvent(event);
                if (!source.isBlank()) evidence.add(source.toLowerCase(Locale.ROOT));
            }
        }
        long end = snapshot.completedAtMillis() > 0L ? snapshot.completedAtMillis() : System.currentTimeMillis();
        List<AutomationWorkspaceTrace> result = new ArrayList<>();
        for (AutomationWorkspaceTrace trace : workspaceTraces()) {
            if (trace == null) continue;
            String searchable = (trace.id() + "\n" + trace.title() + "\n" + trace.status()).toLowerCase(Locale.ROOT);
            if (trace.modelTrace() != null) searchable += "\n" + trace.modelTrace().objective().toLowerCase(Locale.ROOT);
            final String haystack = searchable;
            boolean direct = !requestId.isBlank() && haystack.contains(requestId);
            boolean objective = !promptPrefix.isBlank() && haystack.contains(promptPrefix);
            boolean tool = evidence.stream().anyMatch(token -> token.length() >= 4 && haystack.contains(token));
            boolean time = trace.createdAtMillis() >= snapshot.createdAtMillis() - 2_500L
                && trace.createdAtMillis() <= end + 2_500L;
            if (direct || objective || tool || time) result.add(trace);
            if (result.size() >= 16) break;
        }
        return List.copyOf(result);
    }

    private NotebookNode buildTraceHistoryGroup(List<AutomationWorkspaceTrace> traces, String kind) {
        String normalized = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        NotebookNode group = new NotebookNode(
            "history:" + normalized,
            friendly(normalized) + " trace history",
            "HISTORY",
            "",
            "Historical traces are nested under their owning system instead of appearing as a separate global session root.",
            "AutomationWorkspaceRepository",
            ModelActivityState.IDLE,
            "",
            ""
        );
        for (AutomationWorkspaceTrace trace : traces) {
            if (trace == null) continue;
            String traceKind = trace.kind() == null ? "" : trace.kind().toLowerCase(Locale.ROOT);
            boolean matches = traceKind.contains(normalized)
                || normalized.equals("executor") && !traceKind.contains("model") && !traceKind.contains("automation");
            if (matches) group.add(buildTraceNode(trace));
        }
        group.summary = group.children.size() + " trace" + (group.children.size() == 1 ? "" : "s");
        return group;
    }

    private static String firstLine(String value) {
        if (value == null || value.isBlank()) return "";
        String clean = value.replace('\r', '\n').strip();
        int newline = clean.indexOf('\n');
        String line = newline >= 0 ? clean.substring(0, newline) : clean;
        return line.length() <= 90 ? line : line.substring(0, 89) + "…";
    }

    private ModelActivityState automationDisplayState(AutomationModeController.Snapshot mode) {
        if (System.currentTimeMillis() - this.forcedStopAtMillis < FORCED_STOP_VISIBILITY_MILLIS) {
            return ModelActivityState.CANCELLED;
        }
        return stateForAutomationMode(mode);
    }

    private String automationStatusLabel(AutomationModeController.Snapshot mode) {
        if (System.currentTimeMillis() - this.forcedStopAtMillis < FORCED_STOP_VISIBILITY_MILLIS) {
            return "Stopped";
        }
        if (mode == null || !mode.enabled()) {
            return "Idle";
        }
        return switch (mode.state()) {
            case OFF -> "Idle";
            case CONNECTING -> "Starting";
            case READY -> "Ready";
            case EXECUTING -> "Executing";
            case PAUSED -> "Paused";
            case UNAVAILABLE -> "Failed";
        };
    }

    private List<NotebookNode> buildAllNotebookRoots() {
        ModelGenerationHudState.Snapshot visibleModel = ModelGenerationHudState.visibleSnapshot();
        ModelGenerationHudState.Snapshot canvasModel = visibleModel != null ? visibleModel : this.retainedModelSnapshot;
        AutomationModeController.Snapshot mode = AutomationModeController.snapshot();
        AutomationRuntimeStatus.Snapshot executorStatus = AutomationRuntimeStatus.snapshot();
        List<AutomationWorkspaceTrace> traces = workspaceTraces();
        this.activeWorkspaceTraceIds.clear();
        for (AutomationWorkspaceTrace trace : traces) {
            if (trace != null
                && trace.completedAtMillis() <= 0L
                && System.currentTimeMillis() - Math.max(trace.createdAtMillis(), trace.updatedAtMillis()) < 30_000L
                && isActiveCanvasState(stateForMarker(trace.status(), trace.kind()))) {
                this.activeWorkspaceTraceIds.add(trace.id());
            }
        }

        NotebookNode automation = new NotebookNode(
            "root:automation",
            "Automation System",
            "SYSTEM",
            mode.enabled() ? mode.state().name().toLowerCase(Locale.ROOT) : "off",
            automationModeDetail(mode),
            "src/main/java/com/spirit/koil/api/automation",
            automationDisplayState(mode),
            "",
            ""
        );
        automation.links.add("root:model");
        automation.links.add("root:executor");
        automation.add(new NotebookNode(
            "automation:mode",
            "Mode / session state",
            "RUNTIME",
            mode.enabled() ? mode.approvalPolicy().name().toLowerCase(Locale.ROOT) : "inactive",
            automationModeDetail(mode),
            "src/main/java/com/spirit/koil/api/automation/AutomationModeController.java",
            automationDisplayState(mode),
            "",
            ""
        ));
        automation.add(ktlNotebook());
        automation.add(this.automationArchitecture);
        if (this.automationGuiArchitecture != null && !this.automationGuiArchitecture.children.isEmpty()) {
            automation.add(this.automationGuiArchitecture);
        }

        NotebookNode model = new NotebookNode(
            "root:model",
            "Model System",
            "SYSTEM",
            canvasModel == null ? "idle" : canvasModel.activityState().id(),
            modelRuntimeDetail(canvasModel),
            "src/main/java/com/spirit/koil/api/model",
            canvasModel == null ? ModelActivityState.IDLE : canvasModel.activityState(),
            "",
            ""
        );
        model.links.add("root:executor");
        model.add(modelRuntimeNode(canvasModel));
        model.add(voiceRuntimeNode());
        model.add(this.modelArchitecture);
        if (this.modelGuiArchitecture != null && !this.modelGuiArchitecture.children.isEmpty()) {
            model.add(this.modelGuiArchitecture);
        }

        NotebookNode executor = new NotebookNode(
            "root:executor",
            "Executor System",
            "SYSTEM",
            executorStatus.state(),
            executorRuntimeDetail(executorStatus),
            "src/main/java/com/spirit/koil/api/automation/runtime",
            executorStatus.active() ? stateForMarker(executorStatus.state(), "executor") : ModelActivityState.IDLE,
            "",
            ""
        );
        executor.links.add("automation:ktl");
        executor.add(new NotebookNode(
            "executor:runtime",
            "Execution runtime",
            "RUNTIME",
            executorStatus.active() ? "active" : "idle",
            executorRuntimeDetail(executorStatus),
            "src/main/java/com/spirit/koil/api/automation/AutomationRuntimeStatus.java",
            executorStatus.active() ? stateForMarker(executorStatus.state(), "executor") : ModelActivityState.IDLE,
            "",
            ""
        ));
        executor.add(this.executorArchitecture);

        NotebookNode modelHistory = buildTraceHistoryGroup(traces, "model");
        if (!modelHistory.children.isEmpty()) {
            model.add(modelHistory);
        }
        NotebookNode executorHistory = buildTraceHistoryGroup(traces, "executor");
        if (!executorHistory.children.isEmpty()) {
            executor.add(executorHistory);
        }
        NotebookNode automationHistory = buildTraceHistoryGroup(traces, "automation");
        if (!automationHistory.children.isEmpty()) {
            automation.add(automationHistory);
        }

        NotebookNode observability = buildGlobalObservabilityLedger(canvasModel, mode, executorStatus, traces);
        observability.links.add("root:model");
        observability.links.add("root:automation");
        observability.links.add("root:executor");

        List<NotebookNode> canonicalTelemetry = cachedTelemetryProjection(WorkspaceViewMode.TOPOLOGY, null);
        if (!canonicalTelemetry.isEmpty()) {
            NotebookNode telemetry = canonicalTelemetry.get(0);
            observability.links.add(telemetry.id);
            return List.of(observability, telemetry, automation, model, executor);
        }
        return List.of(observability, automation, model, executor);
    }

    private NotebookNode buildRequestObservabilityCoverage(
        ModelGenerationHudState.Snapshot snapshot,
        List<AutomationWorkspaceTrace> matching
    ) {
        String requestId = snapshot.requestId() == null ? "unknown" : snapshot.requestId().toString();
        int events = visibleModelEvents(snapshot.events()).size();
        int planSteps = snapshot.plan() == null || snapshot.plan().steps() == null ? 0 : snapshot.plan().steps().size();
        int relatedTraces = matching == null ? 0 : matching.size();
        AutomationRuntimeStatus.Snapshot executor = AutomationRuntimeStatus.snapshot();
        String detail = "modelEventFeed=" + feedState(events > 0, true)
            + "\nplanFeed=" + feedState(planSteps > 0, true)
            + "\ntoolFeed=" + feedState(snapshot.toolCallCount() > 0 || !snapshot.activeToolId().isBlank(), true)
            + "\nexecutorRepositoryFeed=" + feedState(relatedTraces > 0, true)
            + "\nexecutorRuntime=" + (executor == null ? "unavailable" : executor.active() ? "live · " + executor.state() : "wired · idle")
            + "\napprovalFeed=" + feedState(snapshot.approval() != null, true)
            + "\ntokenTelemetry=live"
            + "\noutputStream=" + (snapshot.text() == null || snapshot.text().isBlank() ? "wired · no text observed yet" : "live")
            + "\nrawTokenIds=debug-only when the provider exposes them"
            + "\nproviderReasoning=only explicit provider/model reasoning is shown; hidden reasoning is never inferred"
            + "\nrequest=" + requestId
            + "\nrecordedEvents=" + events
            + "\nplanSteps=" + planSteps
            + "\ntoolCalls=" + snapshot.toolCallCount()
            + "\nmatchedExecutorTraces=" + relatedTraces;
        ModelActivityState state = snapshot.state().terminal() ? terminalState(snapshot) : ModelActivityState.OBSERVING;
        NotebookNode coverage = new NotebookNode(
            "request:" + requestId + ":observability",
            "What Koil can currently see",
            "OBSERVABILITY",
            "Live feed coverage, missing observations, and intentionally unexposed provider internals.",
            detail,
            "ModelGenerationHudState + AutomationWorkspaceRepository + AutomationRuntimeStatus",
            state,
            "",
            "request:" + requestId
        );
        coverage.add(new NotebookNode(
            coverage.id + ":model",
            "Model cognition + planning feed",
            "MODEL FEED",
            events + " events · " + planSteps + " plan steps · " + snapshot.toolCallCount() + " tool calls",
            "events=" + feedState(events > 0, true)
                + "\nplan=" + feedState(planSteps > 0, true)
                + "\ntools=" + feedState(snapshot.toolCallCount() > 0 || !snapshot.activeToolId().isBlank(), true)
                + "\nactiveTool=" + snapshot.activeToolId(),
            "ModelGenerationHudState",
            snapshot.state().terminal() ? terminalState(snapshot) : snapshot.activityState(),
            "",
            "request:" + requestId
        ));
        coverage.add(new NotebookNode(
            coverage.id + ":executor",
            "Executor + automation feed",
            "EXECUTOR FEED",
            executor == null ? "unavailable" : executor.active() ? executor.state() : "wired · idle",
            "runtime=" + (executor == null ? "unavailable" : executor.active() ? "live" : "wired · idle")
                + "\nmatchedRepositoryTraces=" + relatedTraces
                + "\ncorrelation=" + (relatedTraces > 0 ? "linked to request" : "no request-correlated executor trace observed"),
            "AutomationRuntimeStatus + AutomationWorkspaceRepository",
            executor == null ? ModelActivityState.FAILED : executor.active() ? stateForMarker(executor.state(), "executor") : ModelActivityState.IDLE,
            "",
            "request:" + requestId
        ));
        coverage.add(new NotebookNode(
            coverage.id + ":limits",
            "Observability limits",
            "BOUNDARY",
            "Explicit boundaries prevent missing telemetry from looking like model inactivity.",
            "rawTokenIds=debug-only when exposed by the provider\nproviderReasoning=explicit channel only; hidden reasoning is not recoverable or inferred\nmissingEventMeaning=not observed, not inferred\nmissingTraceMeaning=not correlated, not assumed broken",
            "AutomationWorkspaceScreen",
            ModelActivityState.OBSERVING,
            "",
            "request:" + requestId
        ));
        return coverage;
    }

    private NotebookNode buildGlobalObservabilityLedger(
        ModelGenerationHudState.Snapshot model,
        AutomationModeController.Snapshot mode,
        AutomationRuntimeStatus.Snapshot executor,
        List<AutomationWorkspaceTrace> traces
    ) {
        int traceCount = traces == null ? 0 : traces.size();
        int eventCount = model == null || model.events() == null ? 0 : model.events().size();
        int toolCalls = model == null ? 0 : model.toolCallCount();
        int canonicalRequests = this.telemetryViewModel.requests().size();
        long telemetryRevision = this.telemetryViewModel.revision();
        var telemetryHealth = this.telemetryViewModel.health();
        String detail = "canonicalTelemetry=" + (canonicalRequests > 0
                ? "live · " + canonicalRequests + " retained requests · revision " + telemetryRevision
                : "wired · no canonical requests observed")
            + "\nmodelSnapshot=" + (model == null ? "wired · idle" : "live")
            + "\nmodelActivityEvents=" + (model == null ? "not observed" : eventCount)
            + "\nmodelToolCalls=" + (model == null ? "not observed" : toolCalls)
            + "\nautomationMode=" + (mode == null ? "unavailable" : mode.enabled() ? "enabled" : "disabled")
            + "\nexecutorRuntime=" + (executor == null ? "unavailable" : executor.active() ? "live" : "wired · idle")
            + "\nworkspaceTraceRepository=" + (traceCount > 0 ? "live · " + traceCount + " traces" : "wired · no traces observed")
            + "\narchitectureDiscovery=live classpath scan"
            + "\nKTLWorkspace=filesystem-backed"
            + "\nrawTokenIds=debug-only when exposed by the provider"
            + "\nproviderReasoning=explicit channel only; hidden reasoning is never inferred"
            + "\ntelemetryStore=" + (telemetryHealth.degraded() ? "degraded" : "healthy")
            + " · droppedSpans=" + telemetryHealth.droppedSpans()
            + " · droppedEvents=" + telemetryHealth.droppedEvents()
            + " · missingSpanWrites=" + telemetryHealth.missingSpanWrites()
            + " · repairedParents=" + telemetryHealth.repairedParentLinks()
            + "\ntelemetrySnapshotAvgUs=" + telemetryHealth.snapshotBuildMicrosAverage()
            + " · indexAvgUs=" + telemetryHealth.indexReadMicrosAverage()
            + "\nstatusLegend=live | wired-idle | not observed | unavailable | failed/blocked | not exposed";
        NotebookNode ledger = new NotebookNode(
            "root:observability",
            "Team notebook · live observability ledger",
            "OBSERVABILITY",
            "One place to see which systems are feeding this screen and which information does not exist or is not exposed.",
            detail,
            "AutomationWorkspaceScreen",
            model == null ? ModelActivityState.OBSERVING : model.activityState(),
            "",
            ""
        );
        ledger.add(new NotebookNode(
            "observability:model",
            "Model feed",
            "MODEL FEED",
            model == null ? "wired · idle" : model.activityState().id(),
            modelRuntimeDetail(model),
            "ModelGenerationHudState",
            model == null ? ModelActivityState.IDLE : model.activityState(),
            "",
            ""
        ));
        ledger.add(new NotebookNode(
            "observability:automation",
            "Automation feed",
            "AUTOMATION FEED",
            mode == null ? "unavailable" : mode.enabled() ? mode.state().name().toLowerCase(Locale.ROOT) : "disabled",
            automationModeDetail(mode),
            "AutomationModeController",
            mode == null ? ModelActivityState.FAILED : automationDisplayState(mode),
            "",
            ""
        ));
        ledger.add(new NotebookNode(
            "observability:executor",
            "Executor feed",
            "EXECUTOR FEED",
            executor == null ? "unavailable" : executor.active() ? executor.state() : "wired · idle",
            executorRuntimeDetail(executor),
            "AutomationRuntimeStatus + AutomationWorkspaceRepository",
            executor == null ? ModelActivityState.FAILED : executor.active() ? stateForMarker(executor.state(), "executor") : ModelActivityState.IDLE,
            "",
            ""
        ));
        return ledger;
    }

    private static String feedState(boolean observed, boolean wired) {
        if (observed) return "live";
        return wired ? "wired · not observed for this request" : "unavailable";
    }

    private NotebookNode modelRuntimeNode(ModelGenerationHudState.Snapshot visibleModel) {
        return new NotebookNode(
            "model:runtime",
            "Current generation",
            "RUNTIME",
            visibleModel == null ? "no visible request" : visibleModel.activityState().id(),
            modelRuntimeDetail(visibleModel),
            "src/main/java/com/spirit/koil/api/model/chat/ModelGenerationHudState.java",
            visibleModel == null ? ModelActivityState.IDLE : visibleModel.activityState(),
            "",
            ""
        );
    }

    private NotebookNode voiceRuntimeNode() {
        try {
            var settings = ModelVoiceService.settings();
            int voiceCount = ModelVoiceService.voices().size();
            String summary = settings.enabled() ? ModelVoiceService.selectedVoiceLabel() : "disabled";
            String detail = "enabled=" + settings.enabled()
                + "\nvoiceId=" + settings.voiceId()
                + "\nselected=" + ModelVoiceService.selectedVoiceLabel()
                + "\nregisteredVoices=" + voiceCount
                + "\nflow=phrase planner -> synthesis queue -> provider -> playback queue -> isolated clip";
            NotebookNode voice = new NotebookNode(
                "model:voice-runtime",
                "Voice runtime",
                "VOICE",
                summary,
                detail,
                "src/main/java/com/spirit/koil/api/model/voice/ModelVoiceService.java",
                ModelActivityState.IDLE,
                "",
                ""
            );
            voice.links.add("root:model");
            return voice;
        } catch (Throwable failure) {
            return new NotebookNode(
                "model:voice-runtime",
                "Voice runtime",
                "VOICE",
                "unavailable",
                failure.getClass().getSimpleName() + ": " + safeMessage(failure),
                "src/main/java/com/spirit/koil/api/model/voice/ModelVoiceService.java",
                ModelActivityState.FAILED,
                "",
                ""
            );
        }
    }

    private NotebookNode buildTraceNode(AutomationWorkspaceTrace trace) {
        String traceId = trace.id();
        ModelActivityState traceState = stateForMarker(trace.status(), trace.kind());
        String traceKind = trace.kind() == null ? "trace" : trace.kind();
        NotebookNode traceNode = new NotebookNode(
            traceNodeId(traceId),
            trace.title().isBlank() ? traceId : trace.title(),
            traceKind.equals("model") ? "MODEL TRACE" : traceKind.equals("executor") ? "EXECUTOR TRACE" : "AUTOMATION TRACE",
            trace.status().replace('_', ' '),
            "trace=" + traceId
                + "\nkind=" + traceKind
                + "\nstatus=" + trace.status()
                + "\ncreated=" + trace.createdAtMillis()
                + "\nupdated=" + trace.updatedAtMillis()
                + "\ncompleted=" + trace.completedAtMillis(),
            "",
            traceState,
            "",
            traceId
        );
        traceNode.links.add(traceKind.equals("model") ? "root:model" : "root:executor");

        NotebookNode modelNodeForLink = null;
        NotebookNode executorNodeForLink = null;

        if (trace.modelTrace() != null) {
            var model = trace.modelTrace();
            List<ModelGenerationHudState.ActivityEvent> modelEvents = visibleModelEvents(model.events());
            NotebookNode modelNode = new NotebookNode(
                traceNode.id + ":model",
                "Recorded model cognition",
                "MODEL",
                model.objective(),
                "objective=" + model.objective()
                    + "\nevents=" + modelEvents.size()
                    + "\nplan=" + (model.plan() == null ? "none" : model.plan().planId())
                    + "\nobservability=recorded model activity summaries, plans, tool decisions, and results"
                    + "\nproviderReasoning=explicit provider/model reasoning only; hidden reasoning is never inferred",
                "src/main/java/com/spirit/koil/api/model/chat/ModelGenerationHudState.java",
                traceState,
                "",
                traceId
            );
            modelNode.links.add("root:model");
            modelNodeForLink = modelNode;

            List<NotebookNode> modelEventNodes = new ArrayList<>();
            for (int eventIndex = 0; eventIndex < modelEvents.size(); eventIndex++) {
                ModelGenerationHudState.ActivityEvent event = modelEvents.get(eventIndex);
                if (event == null) {
                    modelEventNodes.add(null);
                    continue;
                }
                String json = event.data() == null || event.data().entrySet().isEmpty()
                    ? ""
                    : new GsonBuilder().setPrettyPrinting().create().toJson(event.data());
                String detail = "index=" + eventIndex
                    + "\ntype=" + event.type().name().toLowerCase(Locale.ROOT)
                    + "\nactivity=" + (event.activityState() == null ? "observing" : event.activityState().id())
                    + "\neventId=" + event.eventId()
                    + "\ntime=" + event.timestampMillis()
                    + "\nsummary=" + event.summary()
                    + "\nprevious=" + previousRecordedEventDescription(modelEvents, eventIndex)
                    + "\nnext=" + nextRecordedEventDescription(modelEvents, eventIndex)
                    + (json.isBlank() ? "" : "\ndata=\n" + json);

                NotebookNode eventNode = new NotebookNode(
                    modelNode.id + ":event:" + eventIndex,
                    event.type() == ModelGenerationHudState.ActivityEventType.THOUGHT_SUMMARY
                        ? ModelGenerationHudState.exposedLabel(event) + " " + (countEventTypeBefore(
                        modelEvents,
                        eventIndex,
                        ModelGenerationHudState.ActivityEventType.THOUGHT_SUMMARY
                    ) + 1)
                        : "Event " + (eventIndex + 1) + " · " + friendly(event.type().name()),
                    sidebarTraceBadge(event),
                    event.summary(),
                    detail,
                    sourceFromEvent(event),
                    event.activityState(),
                    "",
                    traceId
                );
                modelNode.add(eventNode);
                modelEventNodes.add(eventNode);
            }

            NotebookNode previousModelEvent = null;
            for (NotebookNode eventNode : modelEventNodes) {
                if (eventNode == null) continue;
                if (previousModelEvent == null) {
                    modelNode.links.add(eventNode.id);
                } else {
                    previousModelEvent.links.add(eventNode.id);
                }
                previousModelEvent = eventNode;
            }
            for (int eventIndex = 0; eventIndex < modelEvents.size(); eventIndex++) {
                ModelGenerationHudState.ActivityEvent event = modelEvents.get(eventIndex);
                if (event == null || event.type() != ModelGenerationHudState.ActivityEventType.THOUGHT_SUMMARY) continue;
                int effectIndex = nextCausalEffectIndex(modelEvents, eventIndex + 1);
                if (effectIndex >= 0
                    && eventIndex < modelEventNodes.size()
                    && effectIndex < modelEventNodes.size()
                    && modelEventNodes.get(eventIndex) != null
                    && modelEventNodes.get(effectIndex) != null) {
                    modelEventNodes.get(eventIndex).links.add(modelEventNodes.get(effectIndex).id);
                }
            }

            if (model.plan() != null && model.plan().steps() != null && !model.plan().steps().isEmpty()) {
                NotebookNode plan = new NotebookNode(
                    modelNode.id + ":plan",
                    "Plan " + model.plan().planId(),
                    "PLAN",
                    model.plan().revised()
                        ? "revised | " + model.plan().steps().size() + " steps"
                        : model.plan().steps().size() + " steps",
                    "planId=" + model.plan().planId()
                        + "\nrevised=" + model.plan().revised()
                        + "\nobjective=" + model.objective(),
                    "",
                    isActiveCanvasState(traceState) ? ModelActivityState.PLANNING : ModelActivityState.COMPLETE,
                    "",
                    traceId
                );
                modelNode.add(plan);
                modelNode.links.add(plan.id);

                for (ModelGenerationHudState.PlanStep step : model.plan().steps()) {
                    if (step == null) continue;
                    String detail = "index=" + step.index()
                        + "\ntool=" + step.toolId()
                        + "\nsummary=" + step.summary()
                        + "\nstatus=" + (step.status() == null ? "pending" : step.status().name().toLowerCase(Locale.ROOT))
                        + "\narguments=" + step.arguments()
                        + "\nexpectedObservation=" + step.expectedObservation()
                        + "\nvalidationRequirement=" + step.validationRequirement()
                        + "\nresult=" + step.result();
                    NotebookNode stepNode = new NotebookNode(
                        plan.id + ":step:" + step.index(),
                        "Step " + step.index(),
                        "TASK",
                        step.summary(),
                        detail,
                        "",
                        stateForPlan(step.status()),
                        "",
                        traceId
                    );
                    plan.add(stepNode);
                    int eventIndex = findRecordedEventForPlanStep(modelEvents, step);
                    if (eventIndex >= 0
                        && eventIndex < modelEventNodes.size()
                        && modelEventNodes.get(eventIndex) != null) {
                        stepNode.links.add(modelEventNodes.get(eventIndex).id);
                    }
                }
            }
            traceNode.add(modelNode);
        }

        if (trace.executorTrace() != null) {
            var executor = trace.executorTrace();
            NotebookNode executorNode = new NotebookNode(
                traceNode.id + ":executor",
                "Executor " + executor.sessionId(),
                "EXECUTOR",
                executor.mode(),
                "session=" + executor.sessionId()
                    + "\nactor=" + executor.actor()
                    + "\nmode=" + executor.mode()
                    + "\ndetail=" + executor.detail()
                    + "\nrows=" + executor.rows().size(),
                "src/main/java/com/spirit/koil/api/automation/runtime",
                traceState,
                "",
                traceId
            );
            executorNode.links.add("root:executor");
            executorNodeForLink = executorNode;

            Map<Integer, NotebookNode> parents = new HashMap<>();
            parents.put(0, executorNode);
            NotebookNode previousExecutorRow = null;
            int index = 0;
            for (AutomationCliRow row : executor.rows()) {
                int depth = Math.max(1, Math.min(12, row.indentationDepth() + 1));
                NotebookNode parent = parents.getOrDefault(depth - 1, executorNode);
                String detail = "index=" + index
                    + "\nrowId=" + row.rowId()
                    + "\ntype=" + row.rowType()
                    + "\nsection=" + row.sectionId()
                    + "\nstatus=" + row.statusMarker()
                    + "\nvisible=" + row.visible()
                    + "\ndirty=" + row.dirty();
                detail = addDetail(detail, "value", row.value());
                detail = addDetail(detail, "search", row.search());
                detail = addDetail(detail, "requires", row.requires());
                detail = addDetail(detail, "received", row.received());
                detail = addDetail(detail, "output", row.output());
                detail = addDetail(detail, "failure", row.failure());
                detail = addDetail(detail, "recovery", row.recovery());
                detail = addDetail(detail, "source", row.source());

                NotebookNode rowNode = new NotebookNode(
                    executorNode.id + ":row:" + index + ":" + row.rowId(),
                    row.label(),
                    row.rowType().isBlank() ? "EXEC" : row.rowType().toUpperCase(Locale.ROOT),
                    row.value(),
                    detail,
                    row.source(),
                    stateForMarker(row.statusMarker(), row.rowType()),
                    "",
                    traceId
                );
                parent.add(rowNode);
                if (previousExecutorRow == null) {
                    executorNode.links.add(rowNode.id);
                } else {
                    previousExecutorRow.links.add(rowNode.id);
                }
                previousExecutorRow = rowNode;

                parents.put(depth, rowNode);
                parents.keySet().removeIf(value -> value > depth);
                index++;
            }
            traceNode.add(executorNode);
        }

        if (modelNodeForLink != null && executorNodeForLink != null) {
            modelNodeForLink.links.add(executorNodeForLink.id);
        }

        return traceNode;
    }

    private void buildStaticTopology() {
        List<String> automationClasses = scanCompiledClasses(AutomationModeController.class, AUTOMATION_PACKAGE);
        List<String> modelClasses = scanCompiledClasses(ModelActivityState.class, MODEL_PACKAGE);
        List<String> automationGuiClasses = scanCompiledClasses(AutomationWorkspaceScreen.class, AUTOMATION_GUI_PACKAGE);
        List<String> modelGuiClasses = scanCompiledClasses(AutomationWorkspaceScreen.class, MODEL_GUI_PACKAGE);

        if (automationClasses.isEmpty()) {
            automationClasses = fallbackAutomationClasses();
        }
        if (modelClasses.isEmpty()) {
            modelClasses = fallbackModelClasses();
        }

        Set<String> executorClasses = new TreeSet<>();
        for (String className : automationClasses) {
            String simple = simpleClassName(className).toLowerCase(Locale.ROOT);
            if (className.startsWith(EXECUTOR_PACKAGE + ".")
                || simple.contains("executor")
                || simple.startsWith("execution")) {
                executorClasses.add(className);
            }
        }

        List<String> automationWithoutRuntime = automationClasses.stream()
            .filter(className -> !className.startsWith(EXECUTOR_PACKAGE + "."))
            .toList();

        this.automationArchitecture = buildArchitectureTree(
            "automation:architecture",
            "Automation source map",
            AUTOMATION_PACKAGE,
            automationWithoutRuntime,
            "AUTOMATION"
        );
        this.executorArchitecture = buildArchitectureTree(
            "executor:architecture",
            "Executor source map",
            EXECUTOR_PACKAGE,
            new ArrayList<>(executorClasses),
            "EXECUTOR"
        );
        this.modelArchitecture = buildArchitectureTree(
            "model:architecture",
            "Model source map",
            MODEL_PACKAGE,
            modelClasses,
            "MODEL"
        );
        this.automationGuiArchitecture = buildArchitectureTree(
            "automation:gui",
            "Automation client surfaces",
            AUTOMATION_GUI_PACKAGE,
            automationGuiClasses,
            "CLIENT UI"
        );
        this.modelGuiArchitecture = buildArchitectureTree(
            "model:gui",
            "Model client surfaces",
            MODEL_GUI_PACKAGE,
            modelGuiClasses,
            "CLIENT UI"
        );

        this.automationClassCount = automationClasses.size() + automationGuiClasses.size();
        this.executorClassCount = executorClasses.size();
        this.modelClassCount = modelClasses.size() + modelGuiClasses.size();
    }

    private NotebookNode buildArchitectureTree(
        String id,
        String title,
        String basePackage,
        List<String> classNames,
        String kind
    ) {
        NotebookNode root = new NotebookNode(
            id,
            title,
            kind,
            classNames.size() + " classes",
            "Click packages, classes, then member groups to walk the compiled system down to fields, constructors, and methods.",
            "src/main/java/" + basePackage.replace('.', '/'),
            ModelActivityState.IDLE,
            "",
            ""
        );
        if (classNames.isEmpty()) {
            return root;
        }

        Map<String, NotebookNode> packages = new LinkedHashMap<>();
        NotebookNode core = new NotebookNode(
            id + ":pkg:core",
            "core",
            "PACKAGE",
            "",
            basePackage,
            "src/main/java/" + basePackage.replace('.', '/'),
            ModelActivityState.IDLE,
            "",
            ""
        );
        packages.put("", core);
        root.add(core);

        List<String> sorted = new ArrayList<>(new LinkedHashSet<>(classNames));
        sorted.sort(String::compareToIgnoreCase);
        for (String className : sorted) {
            String relative = className.startsWith(basePackage + ".")
                ? className.substring(basePackage.length() + 1)
                : className;
            int lastDot = relative.lastIndexOf('.');
            String packageRelative = lastDot < 0 ? "" : relative.substring(0, lastDot);
            NotebookNode parentNode = core;
            if (!packageRelative.isBlank()) {
                StringBuilder packagePath = new StringBuilder();
                for (String segment : packageRelative.split("\\.")) {
                    if (packagePath.length() > 0) {
                        packagePath.append('.');
                    }
                    packagePath.append(segment);
                    String key = packagePath.toString();
                    NotebookNode existing = packages.get(key);
                    if (existing == null) {
                        existing = new NotebookNode(
                            id + ":pkg:" + key,
                            segment,
                            "PACKAGE",
                            "",
                            basePackage + "." + key,
                            "src/main/java/" + (basePackage + "." + key).replace('.', '/'),
                            ModelActivityState.IDLE,
                            "",
                            ""
                        );
                        packages.put(key, existing);
                        parentNode.add(existing);
                    }
                    parentNode = existing;
                }
            }

            String simple = simpleClassName(className);
            String source = "src/main/java/" + className.replace('.', '/') + ".java";
            parentNode.add(new NotebookNode(
                id + ":class:" + className,
                simple,
                "CLASS",
                humanizeIdentifier(simple),
                "class=" + className + "\npackage=" + packageName(className),
                source,
                ModelActivityState.IDLE,
                className,
                ""
            ));
        }

        updatePackageCounts(root);
        return root;
    }

    private int updatePackageCounts(NotebookNode node) {
        if (node.children.isEmpty()) {
            return node.className.isBlank() ? 0 : 1;
        }
        int count = 0;
        for (NotebookNode child : node.children) {
            count += updatePackageCounts(child);
        }
        if ("PACKAGE".equals(node.kind)) {
            node.summary = count + " class" + (count == 1 ? "" : "es");
        }
        return count;
    }

    private NotebookNode ktlNotebook() {
        long now = System.currentTimeMillis();
        if (this.cachedKtlNotebook != null && now - this.cachedKtlNotebookAt < KTL_REFRESH_MILLIS) {
            return this.cachedKtlNotebook;
        }
        this.cachedKtlNotebook = buildKtlNotebook();
        this.cachedKtlNotebookAt = now;
        return this.cachedKtlNotebook;
    }

    private NotebookNode buildKtlNotebook() {
        NotebookNode root = new NotebookNode(
            "automation:ktl",
            "KTL task notebooks",
            "KTL",
            "scanning",
            "Runtime task templates and generated automation files under koil/sys/automation.",
            KTL_ROOT.toString(),
            ModelActivityState.IDLE,
            "",
            ""
        );
        root.links.add("root:executor");
        if (!Files.isDirectory(KTL_ROOT)) {
            root.summary = "0 files";
            this.ktlFileCount = 0;
            return root;
        }

        List<Path> files;
        try (var stream = Files.walk(KTL_ROOT)) {
            files = stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ktl"))
                .sorted()
                .toList();
        } catch (IOException exception) {
            root.summary = "scan failed";
            root.detail = "KTL scan failed: " + exception.getMessage();
            root.state = ModelActivityState.FAILED;
            this.ktlFileCount = 0;
            return root;
        }

        this.ktlFileCount = files.size();
        root.summary = files.size() + " file" + (files.size() == 1 ? "" : "s");
        Map<String, NotebookNode> directories = new LinkedHashMap<>();
        directories.put("", root);

        for (Path file : files) {
            Path relativePath = KTL_ROOT.relativize(file);
            NotebookNode parentNode = root;
            StringBuilder key = new StringBuilder();
            int nameCount = relativePath.getNameCount();
            for (int index = 0; index < nameCount - 1; index++) {
                String segment = relativePath.getName(index).toString();
                if (key.length() > 0) {
                    key.append('/');
                }
                key.append(segment);
                String directoryKey = key.toString();
                NotebookNode directory = directories.get(directoryKey);
                if (directory == null) {
                    directory = new NotebookNode(
                        "automation:ktl:dir:" + directoryKey,
                        segment,
                        "FOLDER",
                        "",
                        directoryKey,
                        KTL_ROOT.resolve(directoryKey).toString(),
                        ModelActivityState.IDLE,
                        "",
                        ""
                    );
                    directories.put(directoryKey, directory);
                    parentNode.add(directory);
                }
                parentNode = directory;
            }

            String relative = relativePath.toString().replace(File.separatorChar, '/');
            String detail = "path=" + relative;
            try {
                detail += "\nbytes=" + Files.size(file)
                    + "\nmodified=" + Files.getLastModifiedTime(file).toMillis();
            } catch (IOException ignored) {
            }
            parentNode.add(new NotebookNode(
                "automation:ktl:file:" + relative,
                file.getFileName().toString(),
                "KTL FILE",
                "task source",
                detail,
                relative,
                ModelActivityState.IDLE,
                "",
                ""
            ));
        }
        return root;
    }

    private void populateClassMembers(NotebookNode classNode) {
        if (classNode.className.isBlank() || classNode.membersLoaded) {
            return;
        }
        classNode.membersLoaded = true;
        try {
            ClassLoader loader = AutomationWorkspaceScreen.class.getClassLoader();
            Class<?> type = Class.forName(classNode.className, false, loader);

            Field[] fields = type.getDeclaredFields();
            Arrays.sort(fields, Comparator.comparing(Field::getName, String.CASE_INSENSITIVE_ORDER));
            if (fields.length > 0) {
                NotebookNode fieldsNode = memberGroupNode(classNode, "fields", "Fields", fields.length);
                for (Field field : fields) {
                    if (field.isSynthetic()) {
                        continue;
                    }
                    fieldsNode.add(new NotebookNode(
                        fieldsNode.id + ":" + field.getName(),
                        field.getName(),
                        "FIELD",
                        typeName(field.getType()),
                        modifierText(field.getModifiers()) + " " + typeName(field.getType()) + " " + field.getName(),
                        classNode.source,
                        ModelActivityState.IDLE,
                        "",
                        ""
                    ));
                }
                if (!fieldsNode.children.isEmpty()) {
                    fieldsNode.summary = fieldsNode.children.size() + " fields";
                    classNode.add(fieldsNode);
                }
            }

            Constructor<?>[] constructors = type.getDeclaredConstructors();
            Arrays.sort(constructors, Comparator.comparing(AutomationWorkspaceScreen::constructorSignature, String.CASE_INSENSITIVE_ORDER));
            if (constructors.length > 0) {
                NotebookNode constructorsNode = memberGroupNode(classNode, "constructors", "Constructors", constructors.length);
                int index = 0;
                for (Constructor<?> constructor : constructors) {
                    if (constructor.isSynthetic()) {
                        continue;
                    }
                    String signature = constructorSignature(constructor);
                    constructorsNode.add(new NotebookNode(
                        constructorsNode.id + ":" + index++,
                        simpleClassName(classNode.className),
                        "CTOR",
                        parameterSummary(constructor.getParameterTypes()),
                        signature,
                        classNode.source,
                        ModelActivityState.IDLE,
                        "",
                        ""
                    ));
                }
                if (!constructorsNode.children.isEmpty()) {
                    constructorsNode.summary = constructorsNode.children.size() + " constructors";
                    classNode.add(constructorsNode);
                }
            }

            Method[] methods = type.getDeclaredMethods();
            Arrays.sort(methods, Comparator
                .comparing(Method::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(AutomationWorkspaceScreen::methodSignature, String.CASE_INSENSITIVE_ORDER));
            if (methods.length > 0) {
                NotebookNode methodsNode = memberGroupNode(classNode, "methods", "Methods", methods.length);
                int index = 0;
                for (Method method : methods) {
                    if (method.isSynthetic() || method.isBridge()) {
                        continue;
                    }
                    String signature = methodSignature(method);
                    methodsNode.add(new NotebookNode(
                        methodsNode.id + ":" + method.getName() + ":" + index++,
                        method.getName(),
                        "METHOD",
                        typeName(method.getReturnType()) + " " + parameterSummary(method.getParameterTypes()),
                        signature,
                        classNode.source,
                        ModelActivityState.IDLE,
                        "",
                        ""
                    ));
                }
                if (!methodsNode.children.isEmpty()) {
                    methodsNode.summary = methodsNode.children.size() + " methods";
                    classNode.add(methodsNode);
                }
            }

            if (classNode.children.isEmpty()) {
                classNode.summary = "no declared members";
            } else {
                classNode.summary = classNode.children.stream().mapToInt(child -> child.children.size()).sum() + " members";
            }
        } catch (Throwable failure) {
            classNode.state = ModelActivityState.FAILED;
            classNode.detail = classNode.detail
                + "\nreflection=" + failure.getClass().getSimpleName()
                + ": " + safeMessage(failure);
        }
    }

    private static NotebookNode memberGroupNode(NotebookNode classNode, String suffix, String title, int count) {
        return new NotebookNode(
            classNode.id + ":members:" + suffix,
            title,
            "MEMBERS",
            count + " declared",
            classNode.className,
            classNode.source,
            ModelActivityState.IDLE,
            "",
            ""
        );
    }

    private static List<String> scanCompiledClasses(Class<?> anchor, String packagePrefix) {
        Set<String> classes = new TreeSet<>();
        if (anchor == null || packagePrefix == null || packagePrefix.isBlank()) {
            return List.of();
        }
        try {
            if (anchor.getProtectionDomain() == null || anchor.getProtectionDomain().getCodeSource() == null) {
                return List.of();
            }
            URL locationUrl = anchor.getProtectionDomain().getCodeSource().getLocation();
            if (locationUrl == null || !"file".equalsIgnoreCase(locationUrl.getProtocol())) {
                return List.of();
            }
            Path location = Path.of(locationUrl.toURI()).toAbsolutePath().normalize();
            String packagePath = packagePrefix.replace('.', '/');
            if (Files.isDirectory(location)) {
                Path base = location.resolve(packagePath);
                if (!Files.isDirectory(base)) {
                    return List.of();
                }
                try (var stream = Files.walk(base)) {
                    stream.filter(Files::isRegularFile)
                        .map(location::relativize)
                        .map(Path::toString)
                        .map(value -> value.replace(File.separatorChar, '/'))
                        .filter(value -> value.endsWith(".class"))
                        .filter(value -> !value.contains("$"))
                        .map(value -> value.substring(0, value.length() - 6).replace('/', '.'))
                        .filter(value -> value.equals(packagePrefix) || value.startsWith(packagePrefix + "."))
                        .forEach(classes::add);
                }
            } else if (Files.isRegularFile(location) && location.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                try (JarFile jar = new JarFile(location.toFile())) {
                    Iterator<JarEntry> entries = jar.stream().iterator();
                    String prefix = packagePath + "/";
                    while (entries.hasNext()) {
                        JarEntry entry = entries.next();
                        String name = entry.getName();
                        if (entry.isDirectory() || !name.startsWith(prefix) || !name.endsWith(".class") || name.contains("$")) {
                            continue;
                        }
                        classes.add(name.substring(0, name.length() - 6).replace('/', '.'));
                    }
                }
            }
        } catch (Throwable ignored) {
            return List.of();
        }
        return List.copyOf(classes);
    }

    private static List<String> fallbackAutomationClasses() {
        return List.of(
            "com.spirit.koil.api.automation.AutomationChatTrigger",
            "com.spirit.koil.api.automation.AutomationCompletionModeController",
            "com.spirit.koil.api.automation.AutomationInterpreter",
            "com.spirit.koil.api.automation.AutomationModeController",
            "com.spirit.koil.api.automation.AutomationReporter",
            "com.spirit.koil.api.automation.AutomationRequest",
            "com.spirit.koil.api.automation.AutomationRouter",
            "com.spirit.koil.api.automation.AutomationRuntimeStatus",
            "com.spirit.koil.api.automation.AutomationSettings",
            "com.spirit.koil.api.automation.cli.AutomationChatHudRenderer",
            "com.spirit.koil.api.automation.cli.AutomationChatHudState",
            "com.spirit.koil.api.automation.cli.AutomationCliRow",
            "com.spirit.koil.api.automation.cli.AutomationCliSnapshot",
            "com.spirit.koil.api.automation.cli.AutomationCliSnapshotStore",
            "com.spirit.koil.api.automation.cli.AutomationCliViewModel",
            "com.spirit.koil.api.automation.runtime.AutomationExecutionResults",
            "com.spirit.koil.api.automation.runtime.AutomationExecutor",
            "com.spirit.koil.api.automation.runtime.ExecutionPlan"
        );
    }

    private static List<String> fallbackModelClasses() {
        return List.of(
            "com.spirit.koil.api.model.LocalModelCommandBridge",
            "com.spirit.koil.api.model.LocalModelRuntimeManager",
            "com.spirit.koil.api.model.LocalModelService",
            "com.spirit.koil.api.model.LocalModelSystemPrompt",
            "com.spirit.koil.api.model.ModelActivityState",
            "com.spirit.koil.api.model.ModelAgentCapabilityProfile",
            "com.spirit.koil.api.model.ModelExperimentalFeatures",
            "com.spirit.koil.api.model.ModelSemanticPalette",
            "com.spirit.koil.api.model.chat.ModelActivityPresentation",
            "com.spirit.koil.api.model.chat.ModelGenerationHudState",
            "com.spirit.koil.api.model.presence.CombinedModelExecutorStatus",
            "com.spirit.koil.api.model.voice.CyzonModelVoiceProvider",
            "com.spirit.koil.api.model.voice.MacOsSayModelVoiceProvider",
            "com.spirit.koil.api.model.voice.ModelVoiceDefinition",
            "com.spirit.koil.api.model.voice.ModelVoicePhrasePlanner",
            "com.spirit.koil.api.model.voice.ModelVoiceProvider",
            "com.spirit.koil.api.model.voice.ModelVoiceRegistry",
            "com.spirit.koil.api.model.voice.ModelVoiceService",
            "com.spirit.koil.api.model.voice.ModelVoiceSettings",
            "com.spirit.koil.api.model.voice.ModelVoiceSettingsStore"
        );
    }

    private boolean composerScrollbarVisible() {
        return this.composerScrollbarThumbHeight > 0
            && this.composerScrollbarTrackBottom > this.composerScrollbarTrackTop
            && this.composerScrollbarMaxFirst > 0;
    }

    private boolean chatScrollbarVisible() {
        return this.chatScrollbarThumbHeight > 0
            && this.chatScrollbarTrackBottom > this.chatScrollbarTrackTop
            && this.chatScrollbarMaximumOffset > 0;
    }

    private boolean mouseOverComposerScrollbar(double mouseX, double mouseY) {
        return composerScrollbarVisible()
            && mouseX >= this.composerScrollbarTrackX - 4
            && mouseX <= this.composerScrollbarTrackX + 4
            && mouseY >= this.composerScrollbarTrackTop
            && mouseY <= this.composerScrollbarTrackBottom;
    }

    private boolean mouseOverChatScrollbar(double mouseX, double mouseY) {
        return chatScrollbarVisible()
            && mouseX >= this.chatScrollbarTrackX - 4
            && mouseX <= this.chatScrollbarTrackX + 4
            && mouseY >= this.chatScrollbarTrackTop
            && mouseY <= this.chatScrollbarTrackBottom;
    }

    private void updateComposerScrollbarFromMouse(double mouseY, boolean preserveGrabOffset) {
        if (!composerScrollbarVisible()) {
            return;
        }
        int trackHeight = this.composerScrollbarTrackBottom - this.composerScrollbarTrackTop;
        int travel = Math.max(1, trackHeight - this.composerScrollbarThumbHeight);
        double grab = preserveGrabOffset
            ? this.composerScrollbarGrabOffset
            : this.composerScrollbarThumbHeight * 0.5D;
        double thumbTop = MathHelper.clamp(
            mouseY - grab,
            this.composerScrollbarTrackTop,
            this.composerScrollbarTrackTop + travel
        );
        double ratio = (thumbTop - this.composerScrollbarTrackTop) / (double) travel;
        this.composerScrollLine = MathHelper.clamp(
            (int) Math.round(ratio * this.composerScrollbarMaxFirst),
            0,
            this.composerScrollbarMaxFirst
        );
        this.composerManualScroll = true;
    }

    private void updateChatScrollbarFromMouse(double mouseY, boolean preserveGrabOffset) {
        if (!chatScrollbarVisible()) {
            return;
        }
        int trackHeight = this.chatScrollbarTrackBottom - this.chatScrollbarTrackTop;
        int travel = Math.max(1, trackHeight - this.chatScrollbarThumbHeight);
        double grab = preserveGrabOffset
            ? this.chatScrollbarGrabOffset
            : this.chatScrollbarThumbHeight * 0.5D;
        double thumbTop = MathHelper.clamp(
            mouseY - grab,
            this.chatScrollbarTrackTop,
            this.chatScrollbarTrackTop + travel
        );
        double ratio = (thumbTop - this.chatScrollbarTrackTop) / (double) travel;
        this.chatStreamManualOffset = MathHelper.clamp(
            (int) Math.round(ratio * this.chatScrollbarMaximumOffset),
            0,
            this.chatScrollbarMaximumOffset
        );
        this.chatStreamManualScroll = true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (notebookSearchPopupOpen()) {
            if (button == 0 && handleNotebookSearchDropdownClick(mouseX, mouseY)) {
                return true;
            }
            if (this.notebookSearchInput != null && this.notebookSearchInput.isMouseOver(mouseX, mouseY)) {
                this.composerFocused = false;
                if (this.chatInput != null) {
                    this.chatInput.setFocused(false);
                }
                return super.mouseClicked(mouseX, mouseY, button);
            }
            if (button == 0) {
                this.notebookSearchDropdownDismissed = true;
                if (this.notebookSearchInput != null) {
                    this.notebookSearchInput.setFocused(false);
                }
            }
            return true;
        }

        if (mouseX >= 0 && mouseX < SIDEBAR_WIDTH
            && mouseY >= this.chatStreamTop && mouseY < this.chatStreamBottom
            && RichChatAttachmentRenderer.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        if (button == 0 && mouseOverComposerScrollbar(mouseX, mouseY)) {
            boolean onThumb = mouseY >= this.composerScrollbarThumbTop
                && mouseY <= this.composerScrollbarThumbTop + this.composerScrollbarThumbHeight;
            if (onThumb) {
                this.composerScrollbarGrabOffset = mouseY - this.composerScrollbarThumbTop;
            } else {
                this.composerScrollbarGrabOffset = this.composerScrollbarThumbHeight * 0.5D;
                updateComposerScrollbarFromMouse(mouseY, false);
            }
            this.draggingComposerScrollbar = true;
            this.draggingChatScrollbar = false;
            this.draggingCanvas = false;
            this.draggingNoteId = "";
            return true;
        }

        if (button == 0 && mouseOverChatScrollbar(mouseX, mouseY)) {
            boolean onThumb = mouseY >= this.chatScrollbarThumbTop
                && mouseY <= this.chatScrollbarThumbTop + this.chatScrollbarThumbHeight;
            if (onThumb) {
                this.chatScrollbarGrabOffset = mouseY - this.chatScrollbarThumbTop;
            } else {
                this.chatScrollbarGrabOffset = this.chatScrollbarThumbHeight * 0.5D;
                updateChatScrollbarFromMouse(mouseY, false);
            }
            this.draggingChatScrollbar = true;
            this.draggingComposerScrollbar = false;
            this.draggingCanvas = false;
            this.draggingNoteId = "";
            return true;
        }

        if (button == 0 && clickCustomSuggestion(mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && handleNotebookSearchDropdownClick(mouseX, mouseY)) {
            return true;
        }
        if (button == 0) {
            for (ToolMessageHit hit : this.workspaceToolHits) {
                if (hit.contains(mouseX, mouseY)) {
                    if (hit.requestId() != null) {
                        selectHistoryRequest(hit.requestId());
                    }
                    focusNotebookNode(hit.nodeId());
                    return true;
                }
            }
            for (MessageHit hit : this.workspaceMessageHits) {
                if (hit.contains(mouseX, mouseY)) {
                    selectHistoryRequest(hit.requestId());
                    return true;
                }
            }
        }

        if (button == 0) {
            for (ActionHit hit : this.actionHits) {
                if (hit.contains(mouseX, mouseY)) {
                    if (hit.enabled()) {
                        hit.action().run();
                    }
                    return true;
                }
            }
        }

        if (this.notebookSearchInput != null && this.notebookSearchInput.isMouseOver(mouseX, mouseY)) {
            this.composerFocused = false;
            if (this.chatInput != null) {
                this.chatInput.setFocused(false);
            }
            this.notebookSearchDropdownDismissed = false;
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (mouseX >= this.chatBorderX && mouseX < this.chatBorderX + this.chatBorderWidth
            && mouseY >= this.chatBorderY && mouseY < this.chatBorderY + this.chatBorderHeight) {
            if (button == 0) {
                if (this.notebookSearchInput != null) {
                    this.notebookSearchInput.setFocused(false);
                }
                this.composerFocused = true;
                this.chatInput.setFocused(true);
                int cursor = composerCursorFromMouse(mouseX, mouseY);
                this.chatInput.setCursor(cursor);
                this.chatInput.setSelectionStart(cursor);
                this.chatInput.setSelectionEnd(cursor);
            }
            return true;
        }

        if (button == 0) {
            this.composerFocused = false;
            if (this.chatInput != null) {
                this.chatInput.setFocused(false);
            }
            if (this.notebookSearchInput != null) {
                this.notebookSearchInput.setFocused(false);
            }
            for (NoteHit hit : this.noteHits) {
                if (!hit.contains(mouseX, mouseY)) {
                    continue;
                }
                NotebookNode node = hit.node();
                boolean showAll = Screen.hasShiftDown();
                this.selectedNodeId = node.id;
                this.selectedTraceId = node.traceId;
                this.fullyExpandedPaneId = showAll ? node.id : "";
                this.renderLayoutRootsGeneration = Long.MIN_VALUE;
                if (!node.className.isBlank()) {
                    populateClassMembers(node);
                }
                if (showAll) {
                    // Shift-click is a semantic "show everything" gesture, not a drag start.
                    this.draggingNoteId = "";
                    this.draggingNoteNode = null;
                } else {
                    this.draggingNoteId = node.id;
                    this.draggingNoteNode = node;
                    this.dragStartMouseX = mouseX;
                    this.dragStartMouseY = mouseY;
                    this.dragNoteStartX = hit.logicalX();
                    this.dragNoteStartY = hit.logicalY();
                    this.noteDragMoved = false;
                }
                this.draggingCanvas = false;
                return true;
            }
        }

        if ((button == 0 || button == 2) && mouseInsideCanvas(mouseX, mouseY)) {
            this.draggingCanvas = true;
            this.draggingNoteId = "";
            this.draggingNoteNode = null;
            this.canvasDragStartMouseX = mouseX;
            this.canvasDragStartMouseY = mouseY;
            this.canvasDragStartX = this.canvasScrollX;
            this.canvasDragStartY = this.canvasScrollY;
            return true;
        }
        if (button == 0) {
            this.notebookSearchDropdownDismissed = true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && this.draggingComposerScrollbar) {
            updateComposerScrollbarFromMouse(mouseY, true);
            return true;
        }
        if (button == 0 && this.draggingChatScrollbar) {
            updateChatScrollbarFromMouse(mouseY, true);
            return true;
        }
        if (button == 0 && !this.draggingNoteId.isBlank()) {
            double dx = (mouseX - this.dragStartMouseX) / this.canvasZoom;
            double dy = (mouseY - this.dragStartMouseY) / this.canvasZoom;
            if (Math.abs(mouseX - this.dragStartMouseX) >= DRAG_THRESHOLD
                || Math.abs(mouseY - this.dragStartMouseY) >= DRAG_THRESHOLD) {
                this.noteDragMoved = true;
            }
            this.notePositions.put(this.draggingNoteId, new NotePosition(this.dragNoteStartX + dx, this.dragNoteStartY + dy));
            return true;
        }
        if ((button == 0 || button == 2) && this.draggingCanvas) {
            this.canvasScrollX = this.canvasDragStartX + (mouseX - this.canvasDragStartMouseX) / this.canvasZoom;
            this.canvasScrollY = this.canvasDragStartY + (mouseY - this.canvasDragStartMouseY) / this.canvasZoom;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.draggingComposerScrollbar) {
            updateComposerScrollbarFromMouse(mouseY, true);
            this.draggingComposerScrollbar = false;
            return true;
        }
        if (button == 0 && this.draggingChatScrollbar) {
            updateChatScrollbarFromMouse(mouseY, true);
            this.draggingChatScrollbar = false;
            return true;
        }
        if (button == 0 && !this.draggingNoteId.isBlank()) {
            NotebookNode node = this.draggingNoteNode;
            boolean moved = this.noteDragMoved;
            this.draggingNoteId = "";
            this.draggingNoteNode = null;
            this.noteDragMoved = false;
            if (!moved && node != null && (!node.children.isEmpty() || !node.className.isBlank())) {
                if (!this.expandedNodes.add(node.id)) {
                    this.expandedNodes.remove(node.id);
                }
                this.renderLayoutRootsGeneration = Long.MIN_VALUE;
            }
            return true;
        }
        if ((button == 0 || button == 2) && this.draggingCanvas) {
            this.draggingCanvas = false;
            return true;
        }
        if (RichChatAttachmentRenderer.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (notebookSearchPopupOpen()) {
            scrollNotebookSearchDropdown(mouseX, mouseY, amount);
            return true;
        }
        if (scrollCustomSuggestionPopup(mouseX, mouseY, amount)) {
            return true;
        }
        if (scrollNotebookSearchDropdown(mouseX, mouseY, amount)) {
            return true;
        }
        if (mouseX >= this.chatBorderX && mouseX < this.chatBorderX + this.chatBorderWidth
            && mouseY >= this.chatBorderY && mouseY < this.chatBorderY + this.chatBorderHeight
            && composerVisualLines(chatText()).size() > COMPOSER_MAX_LINES) {
            int direction = amount > 0.0D ? -1 : 1;
            int max = Math.max(0, composerVisualLines(chatText()).size() - COMPOSER_MAX_LINES);
            this.composerScrollLine = Math.max(0, Math.min(max, this.composerScrollLine + direction));
            this.composerManualScroll = true;
            return true;
        }
        if (mouseX >= 0 && mouseX < SIDEBAR_WIDTH
            && mouseY >= this.chatStreamTop && mouseY < this.chatStreamBottom
            && RichChatAttachmentRenderer.mouseScrolled(mouseX, mouseY, amount)) {
            return true;
        }
        if (mouseX >= 0 && mouseX < SIDEBAR_WIDTH
            && mouseY >= this.chatStreamTop && mouseY < this.chatStreamBottom) {
            int direction = amount > 0.0D ? -8 : 8;
            this.chatStreamManualOffset = Math.max(0, this.chatStreamManualOffset + direction);
            this.chatStreamManualScroll = true;
            return true;
        }
        if (mouseInsideCanvas(mouseX, mouseY)) {
            if (Screen.hasShiftDown()) {
                setCanvasZoom(this.canvasZoom + (amount > 0.0D ? ZOOM_STEP : -ZOOM_STEP), mouseX, mouseY);
            } else if (Screen.hasControlDown()) {
                this.canvasScrollX += amount * CANVAS_SCROLL_STEP / this.canvasZoom;
            } else {
                this.canvasScrollY += amount * CANVAS_SCROLL_STEP / this.canvasZoom;
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (this.notebookSearchInput != null && this.notebookSearchInput.isFocused()) {
            return super.charTyped(chr, modifiers);
        }
        if (!this.composerFocused || this.chatInput == null) {
            return super.charTyped(chr, modifiers);
        }
        if (chr == RichChatSectionFormatting.PREFIX && RichChatSettings.enabled()) {
            insertDraftText(String.valueOf(chr));
            return true;
        }
        boolean handled = this.chatInput.charTyped(chr, modifiers);
        if (handled) {
            this.composerManualScroll = false;
            this.chatStreamManualScroll = false;
            this.customSuggestionRequestKey = "";
        }
        return handled;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.notebookSearchInput != null && this.notebookSearchInput.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                moveNotebookSearchSelection(1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP) {
                moveNotebookSearchSelection(-1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                return activateSelectedNotebookSearchResult();
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                this.notebookSearchInput.setFocused(false);
                this.notebookSearchDropdownDismissed = true;
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        if (!this.composerFocused || this.chatInput == null) {
            if (Screen.isCopy(keyCode) && !this.selectedNodeId.isBlank()) {
                copySelectedNote();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        if (keyCode == GLFW.GLFW_KEY_TAB && applySelectedCustomSuggestion()) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP && moveCustomSuggestionSelectionByPage(-1)) return true;
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN && moveCustomSuggestionSelectionByPage(1)) return true;
        if (keyCode == GLFW.GLFW_KEY_UP && hasActiveCustomSuggestions() && moveCustomSuggestionSelection(-1)) return true;
        if (keyCode == GLFW.GLFW_KEY_DOWN && hasActiveCustomSuggestions() && moveCustomSuggestionSelection(1)) return true;

        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && Screen.hasShiftDown()) {
            insertDraftText("\n");
            this.composerManualScroll = false;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submitChatInput();
            return true;
        }

        if (Screen.isPaste(keyCode)) {
            MinecraftClient client = MinecraftClient.getInstance();
            String clipboard = client == null ? "" : client.keyboard.getClipboard();
            if (clipboard.indexOf('\n') >= 0 || clipboard.indexOf('\r') >= 0
                || RichChatSectionFormatting.containsSectionSign(clipboard)) {
                insertDraftText(clipboard.replace("\r\n", "\n").replace('\r', '\n'));
                return true;
            }
        }

        if (keyCode == GLFW.GLFW_KEY_UP && moveComposerCursorVertically(-1)) {
            this.composerManualScroll = false;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN && moveComposerCursorVertically(1)) {
            this.composerManualScroll = false;
            return true;
        }

        boolean handled = this.chatInput.keyPressed(keyCode, scanCode, modifiers);
        if (handled) {
            this.composerManualScroll = false;
            this.customSuggestionRequestKey = "";
        }
        return handled || super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void submitChatInput() {
        String input = chatText().trim();
        if (input.isBlank()) {
            return;
        }
        this.lastSubmittedMessage = input;
        this.lastSubmittedAtMillis = System.currentTimeMillis();
        this.followNextSubmittedRequest = true;
        appendWorkspaceUserMessage(input);

        if (input.equalsIgnoreCase("/ask") || input.toLowerCase(Locale.ROOT).startsWith("/ask ")) {
            String prompt = input.length() <= 4 ? "" : input.substring(4).trim();
            boolean accepted = LocalModelService.ask(prompt);
            showNotice(accepted ? "Prompt routed through /ask behavior." : "The model could not start the /ask request.");
        } else if (input.startsWith("/")) {
            AutomationRouter.handleConsoleInput(input);
            showNotice("Command routed through Koil/Minecraft command handling.");
        } else if (AutomationModeController.isAutomationMode()) {
            boolean accepted = LocalModelService.automationPrompt(input);
            showNotice(accepted ? "Prompt routed through normal Automation chat behavior." : "Automation Mode could not start the prompt.");
        } else {
            boolean accepted = LocalModelService.ask(input);
            showNotice(accepted ? "Prompt routed through /ask behavior." : "The model could not start the /ask request.");
        }

        this.chatInput.setText("");
        this.chatInput.setCursor(0);
        this.composerScrollLine = 0;
        this.composerManualScroll = false;
        this.chatStreamManualScroll = false;
        clearCustomSuggestions();
    }

    @Override
    public void close() {
        clearCustomSuggestions();
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    private boolean mouseInsideCanvas(double mouseX, double mouseY) {
        int canvasBottom = Math.max(CONTENT_TOP + 40, this.height - 20);
        return mouseX >= canvasLeft() && mouseX < canvasRight()
            && mouseY >= canvasContentTop() && mouseY < canvasBottom;
    }

    private int composerCursorFromMouse(double mouseX, double mouseY) {
        List<ComposerVisualLine> lines = composerVisualLines(chatText());
        int visibleRows = Math.max(COMPOSER_MIN_LINES, Math.min(COMPOSER_MAX_LINES, lines.size()));
        int localY = Math.max(0, (int) mouseY - this.chatFieldY);
        int visualIndex = this.composerScrollLine;
        int consumed = 0;
        for (int visible = 0; visible < visibleRows && visualIndex < lines.size(); visible++, visualIndex++) {
            int height = composerVisualLineHeight(lines.get(visualIndex));
            if (localY < consumed + height) {
                break;
            }
            consumed += height;
        }
        visualIndex = Math.max(0, Math.min(lines.size() - 1, visualIndex));
        ComposerVisualLine visual = lines.get(visualIndex);
        String line = visual.hardLine();
        int targetX = Math.max(0, (int) mouseX - this.chatFieldX);
        int best = visual.sourceFrom();
        int bestDistance = Integer.MAX_VALUE;
        for (int index = visual.sourceFrom(); index <= visual.sourceTo();) {
            int width = VanillaBackedChatInputController.styledRangeWidth(
                this.textRenderer, MinecraftClient.getInstance(), line, visual.sourceFrom(), index, index);
            int distance = Math.abs(width - targetX);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = index;
            } else if (width > targetX) {
                break;
            }
            if (index == visual.sourceTo()) break;
            int control = RichChatSectionFormatting.codeLengthAt(line, index);
            index += control > 0 ? control : Character.charCount(line.codePointAt(index));
            if (index > visual.sourceTo()) index = visual.sourceTo();
        }
        return Math.min(chatText().length(), visual.hardGlobalStart() + best);
    }

    private boolean moveComposerCursorVertically(int direction) {
        String text = chatText();
        List<ComposerVisualLine> lines = composerVisualLines(text);
        if (lines.size() <= 1) return false;
        int cursor = Math.max(0, Math.min(this.chatInput.getCursor(), text.length()));
        int currentIndex = composerCursorVisualLine(lines, cursor);
        int targetIndex = currentIndex + (direction < 0 ? -1 : 1);
        if (targetIndex < 0 || targetIndex >= lines.size()) return false;

        ComposerVisualLine current = lines.get(currentIndex);
        ComposerVisualLine target = lines.get(targetIndex);
        int currentLocal = Math.max(current.sourceFrom(), Math.min(current.sourceTo(), cursor - current.hardGlobalStart()));
        int desiredX = VanillaBackedChatInputController.styledRangeWidth(
            this.textRenderer, MinecraftClient.getInstance(), current.hardLine(), current.sourceFrom(), currentLocal, currentLocal);

        int best = target.sourceFrom();
        int bestDistance = Integer.MAX_VALUE;
        String targetLine = target.hardLine();
        for (int index = target.sourceFrom(); index <= target.sourceTo();) {
            int width = VanillaBackedChatInputController.styledRangeWidth(
                this.textRenderer, MinecraftClient.getInstance(), targetLine, target.sourceFrom(), index, index);
            int distance = Math.abs(width - desiredX);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = index;
            } else if (width > desiredX) {
                break;
            }
            if (index == target.sourceTo()) break;
            int control = RichChatSectionFormatting.codeLengthAt(targetLine, index);
            index += control > 0 ? control : Character.charCount(targetLine.codePointAt(index));
            if (index > target.sourceTo()) index = target.sourceTo();
        }
        int nextCursor = Math.min(text.length(), target.hardGlobalStart() + best);
        this.chatInput.setCursor(nextCursor);
        this.chatInput.setSelectionStart(nextCursor);
        this.chatInput.setSelectionEnd(nextCursor);
        return true;
    }

    private int composerVisibleStart(String line, int localCursor) {
        int from = 0;
        int safeCursor = Math.max(0, Math.min(line == null ? 0 : line.length(), localCursor));
        String safeLine = line == null ? "" : line;
        while (from < safeCursor
            && VanillaBackedChatInputController.styledRangeWidth(
            this.textRenderer, MinecraftClient.getInstance(), safeLine, from, safeCursor, safeCursor
        ) > this.chatFieldWidth - 2) {
            int control = RichChatSectionFormatting.codeLengthAt(safeLine, from);
            from += control > 0 ? control : Character.charCount(safeLine.codePointAt(from));
        }
        return from;
    }

    private void insertDraftText(String inserted) {
        if (this.chatInput == null || inserted == null || inserted.isEmpty()) return;
        inserted = inserted.replace("\r\n", "\n").replace('\r', '\n');
        String text = chatText();
        int selectionStart = this.chatInput.getCursor();
        int selectionEnd = selectionStart;
        if (this.chatInput instanceof TextFieldWidgetAccessor accessor) {
            selectionStart = Math.max(0, Math.min(text.length(), accessor.koil$getSelectionStart()));
            selectionEnd = Math.max(0, Math.min(text.length(), accessor.koil$getSelectionEnd()));
        }
        int start = Math.min(selectionStart, selectionEnd);
        int end = Math.max(selectionStart, selectionEnd);
        String next = text.substring(0, start) + inserted + text.substring(end);
        if (next.length() > 32767) next = next.substring(0, 32767);
        this.chatInput.setText(next);
        int cursor = Math.min(next.length(), start + inserted.length());
        this.chatInput.setCursor(cursor);
        this.chatInput.setSelectionStart(cursor);
        this.chatInput.setSelectionEnd(cursor);
        this.composerManualScroll = false;
        this.customSuggestionRequestKey = "";
    }

    private String chatText() {
        return this.chatInput == null || this.chatInput.getText() == null ? "" : this.chatInput.getText().replace("\r\n", "\n").replace('\r', '\n');
    }

    private List<String> splitDraftLines(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index <= normalized.length(); index++) {
            if (index == normalized.length() || normalized.charAt(index) == '\n') {
                lines.add(normalized.substring(start, index));
                start = index + 1;
            }
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    private int draftCursorLine(List<String> lines, int cursor) {
        int index = 0;
        for (int line = 0; line < lines.size(); line++) {
            int end = index + lines.get(line).length();
            if (cursor <= end) return line;
            index = end + 1;
        }
        return Math.max(0, lines.size() - 1);
    }

    private void renderCustomSuggestionPopup(DrawContext context, int mouseX, int mouseY) {
        pollCustomSuggestions();
        if (this.chatInput == null || !this.composerFocused || this.customSuggestionEntries.isEmpty()) {
            this.customSuggestionArea = null;
            return;
        }
        int visibleRows = Math.min(SuggestionPopupRenderer.MAX_VISIBLE_ROWS, this.customSuggestionEntries.size());
        this.customSuggestionScroll = Math.max(0, Math.min(this.customSuggestionScroll,
            Math.max(0, this.customSuggestionEntries.size() - visibleRows)));
        this.customSuggestionSelection = Math.max(0, Math.min(this.customSuggestionSelection, this.customSuggestionEntries.size() - 1));
        if (this.customSuggestionSelection < this.customSuggestionScroll) {
            this.customSuggestionScroll = this.customSuggestionSelection;
        } else if (this.customSuggestionSelection >= this.customSuggestionScroll + visibleRows) {
            this.customSuggestionScroll = this.customSuggestionSelection - visibleRows + 1;
        }
        int width = SuggestionPopupRenderer.preferredWidth(this.textRenderer, this.customSuggestionEntries);
        int height = SuggestionPopupRenderer.preferredHeight(visibleRows);
        int x = Math.max(2, Math.min(suggestionAnchorX(width), this.width - width - 2));
        int y = Math.max(2, suggestionAnchorY(height));
        this.customSuggestionArea = new Rect2i(x, y, width, height);
        List<SuggestionPopupRenderer.Entry> visibleEntries = this.customSuggestionEntries.subList(
            this.customSuggestionScroll,
            Math.min(this.customSuggestionEntries.size(), this.customSuggestionScroll + visibleRows)
        );
        SuggestionPopupRenderer.render(
            context, this.textRenderer, x, y, width, visibleEntries,
            this.customSuggestionSelection - this.customSuggestionScroll,
            mouseX, mouseY
        );
    }

    private int suggestionAnchorX(int popupWidth) {
        DraftSuggestionContext context = currentSuggestionContext();
        if (context == null) return this.chatFieldX;
        int cursor = this.chatInput == null ? 0 : this.chatInput.getCursor();
        List<ComposerVisualLine> visuals = composerVisualLines(chatText());
        ComposerVisualLine visual = visuals.get(composerCursorVisualLine(visuals, cursor));
        int localCursor = Math.max(visual.sourceFrom(), Math.min(visual.sourceTo(), cursor - visual.hardGlobalStart()));
        String beforeCursor = visual.hardLine().substring(visual.sourceFrom(), localCursor);
        Rect2i field = new Rect2i(this.chatBorderX, this.chatBorderY, this.chatBorderWidth, this.chatBorderHeight);
        return VanillaBackedChatInputController.suggestionAnchorX(
            this.chatInput, this.textRenderer, field, popupWidth, true, beforeCursor, true, this.chatFieldX);
    }

    private int suggestionAnchorY(int popupHeight) {
        return Math.max(2, this.chatBorderY - popupHeight - 2);
    }

    private void pollCustomSuggestions() {
        DraftSuggestionContext context = currentSuggestionContext();
        if (context == null) {
            clearCustomSuggestions();
            return;
        }
        if (this.customSuggestionTabCycleActive) {
            String activeToken = activeSuggestionToken(context);
            if (activeToken.equalsIgnoreCase(this.customSuggestionTabCycleValue)) {
                this.customSuggestionContext = context;
                this.customSuggestionsAreSticky = true;
                return;
            }
            this.customSuggestionTabCycleActive = false;
            this.customSuggestionTabCycleValue = "";
        }
        String requestKey = context.requestKey();
        if (!requestKey.equals(this.customSuggestionRequestKey)) {
            this.customSuggestionRequestKey = requestKey;
            this.customSuggestionContext = context;
            if (this.customSuggestionEntries.isEmpty()) {
                this.customSuggestionSelection = 0;
                this.customSuggestionScroll = 0;
            }
            this.customSuggestionsAreSticky = !this.customSuggestionEntries.isEmpty();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.getNetworkHandler() == null || client.getNetworkHandler().getCommandDispatcher() == null) {
                this.customSuggestionFuture = null;
                return;
            }
            try {
                ParseResults<CommandSource> parse = client.getNetworkHandler().getCommandDispatcher().parse(
                    context.commandText(), client.getNetworkHandler().getCommandSource());
                this.customSuggestionFuture = client.getNetworkHandler().getCommandDispatcher()
                    .getCompletionSuggestions(parse, context.commandCursor());
            } catch (Exception ignored) {
                this.customSuggestionFuture = null;
            }
        }
        if (this.customSuggestionFuture == null || !this.customSuggestionFuture.isDone()) return;
        Suggestions suggestions = CommandSuggestionFuturePoller.readyOrNull(this.customSuggestionFuture);
        this.customSuggestionFuture = null;
        List<Suggestion> nextSuggestions = suggestions == null ? List.of() : suggestions.getList();
        List<Suggestion> nbtSuggestions = this.customSuggestionContext == null ? List.of()
            : MinecraftNbtSuggestionService.suggest(
            this.customSuggestionContext.commandText(), this.customSuggestionContext.commandCursor(), 24);
        LinkedHashMap<String, Suggestion> merged = new LinkedHashMap<>();
        for (Suggestion suggestion : nbtSuggestions) {
            if (suggestion != null) merged.put(
                suggestion.getRange().getStart() + ":" + suggestion.getRange().getEnd() + ":" + suggestion.getText(), suggestion);
        }
        for (Suggestion suggestion : nextSuggestions) {
            if (suggestion != null) merged.putIfAbsent(
                suggestion.getRange().getStart() + ":" + suggestion.getRange().getEnd() + ":" + suggestion.getText(), suggestion);
        }
        List<Suggestion> completed = List.copyOf(merged.values());
        if (completed.isEmpty()) return;
        this.customSuggestions = completed;
        List<SuggestionPopupRenderer.Entry> entries = new ArrayList<>(completed.size());
        for (Suggestion suggestion : completed) {
            String value = suggestion == null ? "" : suggestion.getText();
            String detail = suggestion == null || suggestion.getTooltip() == null ? "" : suggestion.getTooltip().getString();
            entries.add(new SuggestionPopupRenderer.Entry(suggestionKind(value), suggestionKindColor(value), value, detail));
        }
        this.customSuggestionEntries = List.copyOf(entries);
        this.customSuggestionsAreSticky = false;
        if (this.customSuggestionSelection >= entries.size()) {
            this.customSuggestionSelection = Math.max(0, entries.size() - 1);
        }
    }

    private DraftSuggestionContext currentSuggestionContext() {
        String text = chatText();
        int cursor = Math.max(0, Math.min(this.chatInput == null ? 0 : this.chatInput.getCursor(), text.length()));
        List<String> lines = splitDraftLines(text);
        int cursorLine = draftCursorLine(lines, cursor);
        int lineStart = 0;
        for (int i = 0; i < cursorLine; i++) lineStart += lines.get(i).length() + 1;
        String line = lines.get(Math.max(0, Math.min(cursorLine, lines.size() - 1)));
        int localCursor = Math.max(0, Math.min(line.length(), cursor - lineStart));
        if (!line.startsWith("/") || localCursor <= 0) return null;
        return new DraftSuggestionContext(
            lineStart, lineStart + line.length(), line, localCursor, line.substring(1), Math.max(0, localCursor - 1));
    }

    private boolean applySelectedCustomSuggestion() {
        pollCustomSuggestions();
        if (this.customSuggestionsAreSticky && this.customSuggestions.size() > 1
            && activeTokenMatchesSuggestion(this.customSuggestionSelection)) {
            this.customSuggestionSelection = (this.customSuggestionSelection + 1) % this.customSuggestions.size();
            keepCustomSuggestionSelectionVisible();
        }
        return applyCustomSuggestion(this.customSuggestionSelection);
    }

    private boolean activeTokenMatchesSuggestion(int suggestionIndex) {
        if (this.customSuggestionContext == null || suggestionIndex < 0 || suggestionIndex >= this.customSuggestions.size()) return false;
        Suggestion suggestion = this.customSuggestions.get(suggestionIndex);
        return suggestion != null && activeSuggestionToken(this.customSuggestionContext).equalsIgnoreCase(suggestion.getText());
    }

    private String activeSuggestionToken(DraftSuggestionContext context) {
        if (context == null) return "";
        String command = context.commandText();
        int cursor = Math.max(0, Math.min(context.commandCursor(), command.length()));
        int wordStart = cursor;
        while (wordStart > 0 && !Character.isWhitespace(command.charAt(wordStart - 1))) wordStart--;
        return command.substring(wordStart, cursor);
    }

    private void keepCustomSuggestionSelectionVisible() {
        int visibleRows = Math.min(SuggestionPopupRenderer.MAX_VISIBLE_ROWS, this.customSuggestionEntries.size());
        if (visibleRows <= 0) {
            this.customSuggestionScroll = 0;
            return;
        }
        if (this.customSuggestionSelection < this.customSuggestionScroll) {
            this.customSuggestionScroll = this.customSuggestionSelection;
        } else if (this.customSuggestionSelection >= this.customSuggestionScroll + visibleRows) {
            this.customSuggestionScroll = this.customSuggestionSelection - visibleRows + 1;
        }
    }

    private boolean moveCustomSuggestionSelection(int direction) {
        pollCustomSuggestions();
        if (direction == 0 || this.customSuggestionEntries.isEmpty()) return false;
        this.customSuggestionSelection = Math.floorMod(
            this.customSuggestionSelection + direction, this.customSuggestionEntries.size());
        keepCustomSuggestionSelectionVisible();
        return true;
    }

    private boolean moveCustomSuggestionSelectionByPage(int direction) {
        pollCustomSuggestions();
        if (direction == 0 || this.customSuggestionEntries.isEmpty()) return false;
        int visibleRows = Math.min(SuggestionPopupRenderer.MAX_VISIBLE_ROWS, this.customSuggestionEntries.size());
        int delta = Math.max(1, visibleRows - 1) * direction;
        this.customSuggestionSelection = Math.max(0,
            Math.min(this.customSuggestionEntries.size() - 1, this.customSuggestionSelection + delta));
        keepCustomSuggestionSelectionVisible();
        return true;
    }

    private boolean hasActiveCustomSuggestions() {
        pollCustomSuggestions();
        return this.composerFocused && !this.customSuggestionEntries.isEmpty() && currentSuggestionContext() != null;
    }

    private boolean applyCustomSuggestion(int suggestionIndex) {
        pollCustomSuggestions();
        if (this.chatInput == null || this.customSuggestionContext == null
            || suggestionIndex < 0 || suggestionIndex >= this.customSuggestions.size()) return false;
        Suggestion suggestion = this.customSuggestions.get(suggestionIndex);
        if (suggestion == null) return false;
        String applied;
        if (this.customSuggestionsAreSticky) {
            String command = this.customSuggestionContext.commandText();
            int cursor = Math.max(0, Math.min(this.customSuggestionContext.commandCursor(), command.length()));
            int wordStart = cursor;
            while (wordStart > 0 && !Character.isWhitespace(command.charAt(wordStart - 1))) wordStart--;
            applied = command.substring(0, wordStart) + suggestion.getText() + command.substring(cursor);
        } else {
            applied = suggestion.apply(this.customSuggestionContext.commandText());
        }
        String replacement = "/" + applied;
        String text = chatText();
        String next = text.substring(0, Math.max(0, Math.min(text.length(), this.customSuggestionContext.lineStart())))
            + replacement
            + text.substring(Math.max(0, Math.min(text.length(), this.customSuggestionContext.lineEnd())));
        if (next.length() > 32767) next = next.substring(0, 32767);
        this.chatInput.setText(next);
        int cursor = Math.min(next.length(), this.customSuggestionContext.lineStart() + replacement.length());
        this.chatInput.setCursor(cursor);
        this.chatInput.setSelectionStart(cursor);
        this.chatInput.setSelectionEnd(cursor);
        this.customSuggestionRequestKey = "";
        this.customSuggestionArea = null;
        this.customSuggestionsAreSticky = this.customSuggestions.size() > 1;
        this.customSuggestionTabCycleActive = this.customSuggestionsAreSticky;
        this.customSuggestionTabCycleValue = this.customSuggestionTabCycleActive ? suggestion.getText() : "";
        return true;
    }

    private boolean clickCustomSuggestion(double mouseX, double mouseY) {
        pollCustomSuggestions();
        if (!mouseInsideCustomSuggestionPopup(mouseX, mouseY)) return false;
        int row = SuggestionPopupRenderer.rowAt(this.customSuggestionArea.getY(), mouseY);
        int visibleRows = Math.min(SuggestionPopupRenderer.MAX_VISIBLE_ROWS, this.customSuggestionEntries.size());
        if (row < 0 || row >= visibleRows) return true;
        int index = this.customSuggestionScroll + row;
        this.customSuggestionSelection = Math.max(0, Math.min(index, this.customSuggestionEntries.size() - 1));
        return applySelectedCustomSuggestion();
    }

    private boolean mouseInsideCustomSuggestionPopup(double mouseX, double mouseY) {
        return this.customSuggestionArea != null && SuggestionPopupRenderer.containsRow(
            this.customSuggestionArea.getX(), this.customSuggestionArea.getY(),
            this.customSuggestionArea.getWidth(), this.customSuggestionArea.getHeight(), mouseX, mouseY);
    }

    private boolean scrollCustomSuggestionPopup(double mouseX, double mouseY, double amount) {
        pollCustomSuggestions();
        if (!mouseInsideCustomSuggestionPopup(mouseX, mouseY) || this.customSuggestionEntries.isEmpty()) return false;
        int direction = amount > 0.0D ? -1 : amount < 0.0D ? 1 : 0;
        if (direction == 0) return true;
        this.customSuggestionSelection = Math.max(0,
            Math.min(this.customSuggestionEntries.size() - 1, this.customSuggestionSelection + direction));
        keepCustomSuggestionSelectionVisible();
        return true;
    }

    private void clearCustomSuggestions() {
        this.customSuggestionFuture = null;
        this.customSuggestionRequestKey = "";
        this.customSuggestionContext = null;
        this.customSuggestions = List.of();
        this.customSuggestionEntries = List.of();
        this.customSuggestionsAreSticky = false;
        this.customSuggestionTabCycleActive = false;
        this.customSuggestionTabCycleValue = "";
        this.customSuggestionArea = null;
        this.customSuggestionSelection = 0;
        this.customSuggestionScroll = 0;
    }

    private String suggestionKind(String value) {
        if (value == null || value.isBlank()) return "ARG";
        if (value.startsWith("/")) return "CMD";
        if (value.startsWith("@") || value.contains(":")) return "MC";
        return "ARG";
    }

    private int suggestionKindColor(String value) {
        if (value == null || value.isBlank()) return 0xA0D8DEE8;
        if (value.startsWith("/")) return 0xFFD7C16D;
        if (value.startsWith("@")) return 0xFF8FD0E3;
        if (value.contains(":")) return 0xFFA9D98A;
        return 0xA0D8DEE8;
    }

    private void copySelectedNote() {
        NotebookNode selected = null;
        for (NotebookNode root : buildNotebookRoots()) {
            selected = findNodeById(root, this.selectedNodeId);
            if (selected != null) break;
        }
        if (selected == null) {
            showNotice("Select a notebook entry to copy.");
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            showNotice("Clipboard unavailable.");
            return;
        }
        client.keyboard.setClipboard(notebookNodeText(selected));
        showNotice("Copied full notebook entry.");
    }

    private NotebookNode findNodeById(NotebookNode node, String id) {
        if (node == null || id == null || id.isBlank()) return null;
        if (id.equals(node.id)) return node;
        for (NotebookNode child : node.children) {
            NotebookNode found = findNodeById(child, id);
            if (found != null) return found;
        }
        return null;
    }

    private String notebookNodeText(NotebookNode node) {
        StringBuilder out = new StringBuilder();
        out.append('[').append(node.kind).append("] ").append(node.title).append('\n');
        out.append("state=").append(node.state.id()).append('\n');
        if (!node.summary.isBlank()) out.append("summary=").append(node.summary).append('\n');
        if (!node.typedDetailLines.isEmpty()) {
            for (List<TelemetryText> line : node.typedDetailLines) {
                for (TelemetryText span : line) if (span != null) out.append(span.text());
                out.append('\n');
            }
        } else if (!node.detail.isBlank()) {
            out.append(node.detail).append('\n');
        }
        if (!node.source.isBlank()) out.append("source=").append(node.source).append('\n');
        if (!node.traceId.isBlank()) out.append("trace=").append(node.traceId).append('\n');
        return out.toString().stripTrailing();
    }

    private void exportSelected() {
        if (this.selectedTraceId.isBlank()) {
            showNotice("Select a recorded workspace trace before exporting.");
            return;
        }
        try {
            Path path = AutomationWorkspaceRepository.export(this.selectedTraceId);
            showNotice("Exported " + path.getFileName());
        } catch (IOException exception) {
            showNotice("Export failed: " + exception.getMessage());
        }
    }

    private List<AutomationWorkspaceTrace> workspaceTraces() {
        List<AutomationWorkspaceTrace> traces = new ArrayList<>();
        ModelGenerationHudState.Snapshot live = ModelGenerationHudState.visibleSnapshot();
        if (live != null && !live.state().terminal()) {
            traces.add(new AutomationWorkspaceTrace(
                "live-model-" + live.requestId(),
                "model",
                live.prompt().isBlank() ? "Active model request" : live.prompt(),
                live.activityState().id(),
                live.createdAtMillis(),
                System.currentTimeMillis(),
                live.completedAtMillis(),
                ModelActivityPresentation.capture(live),
                null
            ));
        }
        traces.addAll(AutomationWorkspaceRepository.traces());
        return List.copyOf(traces);
    }

    private void showNotice(String value) {
        this.notice = value == null ? "" : value;
        this.noticeUntil = System.currentTimeMillis() + 5_000L;
    }

    private static String automationModeDetail(AutomationModeController.Snapshot snapshot) {
        if (snapshot == null) {
            return "mode snapshot unavailable";
        }
        String experimental = snapshot.enabledExperimentalFeatures().isEmpty()
            ? "none"
            : String.join(", ", snapshot.enabledExperimentalFeatures());
        return "enabled=" + snapshot.enabled()
            + "\nstate=" + snapshot.state().name().toLowerCase(Locale.ROOT)
            + "\napprovals=" + snapshot.approvalPolicy().name().toLowerCase(Locale.ROOT)
            + "\ndeepThinkingEnabled=" + snapshot.deepThinkingEnabled()
            + "\ndeepThinkingActive=" + snapshot.deepThinkingActive()
            + "\nplanningEnabled=" + snapshot.planningModeEnabled()
            + "\nplanningActive=" + snapshot.planningActive()
            + "\nverification=" + snapshot.verificationEnabled()
            + "\nexperimental=" + experimental
            + (snapshot.detail().isBlank() ? "" : "\ndetail=" + snapshot.detail());
    }

    private static String modelRuntimeDetail(ModelGenerationHudState.Snapshot snapshot) {
        if (snapshot == null) {
            return "No visible model generation request. Architecture notes remain available for inspection.";
        }
        return "request=" + snapshot.requestId()
            + "\nstate=" + snapshot.activityState().id()
            + "\ncreated=" + snapshot.createdAtMillis()
            + "\ncompleted=" + snapshot.completedAtMillis()
            + (snapshot.prompt().isBlank() ? "" : "\nprompt=" + snapshot.prompt());
    }

    private static String executorRuntimeDetail(AutomationRuntimeStatus.Snapshot snapshot) {
        if (snapshot == null) {
            return "executor runtime snapshot unavailable";
        }
        return "state=" + snapshot.state()
            + "\nactive=" + snapshot.active()
            + "\nupdated=" + snapshot.updatedAtMillis()
            + (snapshot.detail().isBlank() ? "" : "\ndetail=" + snapshot.detail());
    }

    private static String sourceFromEvent(ModelGenerationHudState.ActivityEvent event) {
        if (event == null || event.data() == null) {
            return "";
        }
        for (String key : List.of("source", "path", "file", "sourcePath")) {
            if (event.data().has(key) && event.data().get(key).isJsonPrimitive()) {
                String value = event.data().get(key).getAsString();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    private static ModelActivityState stateForAutomationMode(AutomationModeController.Snapshot snapshot) {
        if (snapshot == null) {
            return ModelActivityState.IDLE;
        }
        return switch (snapshot.state()) {
            case OFF, READY -> ModelActivityState.IDLE;
            case CONNECTING -> ModelActivityState.SEARCHING;
            case EXECUTING -> ModelActivityState.EXECUTING;
            case PAUSED -> ModelActivityState.OBSERVING;
            case UNAVAILABLE -> ModelActivityState.FAILED;
        };
    }

    private static ModelActivityState stateForPlan(ModelGenerationHudState.PlanStepStatus status) {
        return switch (status == null ? ModelGenerationHudState.PlanStepStatus.PENDING : status) {
            case PENDING, ACTIVE -> ModelActivityState.PLANNING;
            case COMPLETED -> ModelActivityState.COMPLETE;
            case FAILED -> ModelActivityState.FAILED;
            case BLOCKED -> ModelActivityState.BLOCKED;
            case SKIPPED -> ModelActivityState.IDLE;
            case CANCELLED -> ModelActivityState.CANCELLED;
            case REVISED -> ModelActivityState.REPLANNING;
        };
    }

    private static ModelActivityState stateForMarker(String marker, String rowType) {
        String combined = (marker == null ? "" : marker) + " " + (rowType == null ? "" : rowType);
        String lower = combined.toLowerCase(Locale.ROOT);
        if (lower.contains("fail")) return ModelActivityState.FAILED;
        if (lower.contains("block")) return ModelActivityState.BLOCKED;
        if (lower.contains("stop") || lower.contains("cancel")) return ModelActivityState.CANCELLED;
        if (lower.contains("done") || lower.contains("ok") || lower.contains("complete")) return ModelActivityState.COMPLETE;
        if (lower.contains("wait") || lower.contains("observ")) return ModelActivityState.OBSERVING;
        if (lower.contains("search") || lower.contains("pick") || lower.contains("connect")) return ModelActivityState.SEARCHING;
        if (lower.contains("replan")) return ModelActivityState.REPLANNING;
        if (lower.contains("plan")) return ModelActivityState.PLANNING;
        if (lower.contains("move") || lower.contains("navigate")) return ModelActivityState.NAVIGATING;
        if (lower.contains("valid") || lower.contains("verify")) return ModelActivityState.VALIDATING;
        if (lower.contains("idle") || lower.contains("ready") || lower.contains("off")) return ModelActivityState.IDLE;
        return ModelActivityState.EXECUTING;
    }

    private static String addDetail(String current, String name, String value) {
        return value == null || value.isBlank() ? current : current + "\n" + name + "=" + value;
    }

    private int renderNodeActivityIndicator(DrawContext context, NotebookNode node, int x, int y) {
        ModelActivityState safe = node == null || node.state == null ? ModelActivityState.IDLE : node.state;
        int color = ModelSemanticPalette.color(safe);
        long now = System.currentTimeMillis();
        boolean active = node != null && isNodeActivelyWorking(node);

        if (active && (safe == ModelActivityState.THINKING
                || safe == ModelActivityState.PREPARING
                || safe == ModelActivityState.RESOLVING
                || safe == ModelActivityState.DISCOVERING)) {
            int phase = (int) ((now / 180L) % 3L);
            for (int dot = 0; dot < 3; dot++) {
                int alpha = dot == phase ? 255 : 112;
                int dy = dot == phase ? 0 : 1;
                drawPixelRectWithShadow(context, x + dot * 4, y + 4 + dy, x + dot * 4 + 2, y + 6 + dy,
                    withAlpha(color, alpha));
            }
            return 13;
        }

        if (active && (safe == ModelActivityState.SEARCHING
                || safe == ModelActivityState.INSPECTING
                || safe == ModelActivityState.READING
                || safe == ModelActivityState.COMPARING)) {
            int phase = (int) ((now / 160L) % 2L);
            drawPixelRectWithShadow(context, x + 1, y + 1, x + 7, y + 7, withAlpha(color, 205 + phase * 35));
            context.fill(x + 3, y + 3, x + 6, y + 6, withAlpha(uiColorContentBase, 235));
            drawPixelRectWithShadow(context, x + 7, y + 7, x + 10, y + 9, color);
            return 13;
        }

        if (active && (safe == ModelActivityState.PLANNING || safe == ModelActivityState.REPLANNING)) {
            int phase = (int) ((now / 150L) % 3L);
            for (int row = 0; row < 3; row++) {
                int width = row == phase ? 9 : 6;
                drawPixelRectWithShadow(context, x + 1, y + 1 + row * 3, x + 1 + width, y + 3 + row * 3,
                    withAlpha(color, row == phase ? 245 : 150));
            }
            return 13;
        }

        if (active && (safe == ModelActivityState.EXECUTING
                || safe == ModelActivityState.NAVIGATING
                || safe == ModelActivityState.INTERACTING
                || safe == ModelActivityState.MINING
                || safe == ModelActivityState.BUILDING
                || safe == ModelActivityState.ATTACKING
                || safe == ModelActivityState.USING_ITEM)) {
            int phase = (int) ((now / 120L) % 3L);
            drawPixelRectWithShadow(context, x + 1 + phase, y + 4, x + 8 + phase, y + 6, color);
            drawPixelRectWithShadow(context, x + 7 + phase, y + 2, x + 10 + phase, y + 8, color);
            return 13;
        }

        if (active && (safe == ModelActivityState.WRITING
                || safe == ModelActivityState.EDITING
                || safe == ModelActivityState.FORMATTING
                || safe == ModelActivityState.FINALIZING)) {
            int phase = (int) ((now / 180L) % 2L);
            for (int i = 0; i < 4; i++) {
                drawPixelRectWithShadow(context, x + 2 + i * 2, y + 8 - i * 2 - phase,
                    x + 4 + i * 2, y + 10 - i * 2 - phase, color);
            }
            return 13;
        }

        if (active && (safe == ModelActivityState.OBSERVING
                || safe == ModelActivityState.STARTING
                || safe == ModelActivityState.AWAITING_APPROVAL)) {
            int phase = (int) ((now / 220L) % 2L);
            drawPixelRectWithShadow(context, x + 1, y + 1, x + 10, y + 3, color);
            drawPixelRectWithShadow(context, x + 1, y + 8, x + 10, y + 10, color);
            drawPixelRectWithShadow(context, x + 3 + phase, y + 3, x + 8 - phase, y + 5, withAlpha(color, 190));
            drawPixelRectWithShadow(context, x + 4, y + 5, x + 7, y + 8, color);
            return 13;
        }

        if (active && (safe == ModelActivityState.VALIDATING
                || safe == ModelActivityState.TESTING
                || safe == ModelActivityState.REPAIRING
                || safe == ModelActivityState.RETRYING
                || safe == ModelActivityState.RECOVERING)) {
            int phase = (int) ((now / 160L) % 2L);
            drawPixelRectWithShadow(context, x + 1, y + 5, x + 4, y + 7, withAlpha(color, 190 + phase * 50));
            drawPixelRectWithShadow(context, x + 3, y + 7, x + 6, y + 9, color);
            drawPixelRectWithShadow(context, x + 5, y + 3, x + 8, y + 8, color);
            drawPixelRectWithShadow(context, x + 7, y + 1, x + 10, y + 5, color);
            return 13;
        }

        if (safe == ModelActivityState.IDLE) {
            drawPixelRectWithShadow(context, x + 4, y + 4, x + 6, y + 6, withAlpha(color, 118));
            return 13;
        }
        if (safe == ModelActivityState.CANCELLED || safe == ModelActivityState.INTERRUPTED) {
            drawPixelRectWithShadow(context, x + 1, y + 1, x + 3, y + 3, color);
            drawPixelRectWithShadow(context, x + 7, y + 1, x + 9, y + 3, color);
            drawPixelRectWithShadow(context, x + 3, y + 3, x + 7, y + 7, color);
            drawPixelRectWithShadow(context, x + 1, y + 7, x + 3, y + 9, color);
            drawPixelRectWithShadow(context, x + 7, y + 7, x + 9, y + 9, color);
            return 13;
        }
        if (safe == ModelActivityState.COMPLETE || safe == ModelActivityState.ALREADY_SATISFIED) {
            drawPixelRectWithShadow(context, x + 1, y + 5, x + 3, y + 7, color);
            drawPixelRectWithShadow(context, x + 3, y + 7, x + 5, y + 9, color);
            drawPixelRectWithShadow(context, x + 5, y + 3, x + 7, y + 8, color);
            drawPixelRectWithShadow(context, x + 7, y + 1, x + 9, y + 5, color);
            return 13;
        }
        if (safe == ModelActivityState.BLOCKED) {
            drawPixelRectWithShadow(context, x + 2, y + 4, x + 9, y + 10, color);
            drawPixelRectWithShadow(context, x + 3, y + 1, x + 8, y + 5, color);
            context.fill(x + 5, y + 6, x + 7, y + 9, withAlpha(uiColorContentBase, 230));
            return 13;
        }
        if (safe == ModelActivityState.FAILED) {
            drawPixelRectWithShadow(context, x + 4, y + 1, x + 7, y + 7, color);
            drawPixelRectWithShadow(context, x + 4, y + 8, x + 7, y + 11, color);
            return 13;
        }
        if (safe == ModelActivityState.PARTIAL) {
            drawPixelRectWithShadow(context, x + 1, y + 2, x + 10, y + 4, color);
            drawPixelRectWithShadow(context, x + 1, y + 6, x + 7, y + 8, withAlpha(color, 170));
            return 13;
        }

        String glyph = stateBox(safe);
        context.drawTextWithShadow(this.textRenderer, glyph, x, y, color);
        return this.textRenderer.getWidth(glyph) + 2;
    }

    private void renderTypedTelemetryLine(
        DrawContext context,
        List<TelemetryText> spans,
        int x,
        int y,
        int width,
        int stateColor
    ) {
        if (spans == null || spans.isEmpty() || width <= 0) return;
        int cursorX = x;
        for (TelemetryText span : spans) {
            if (span == null || span.text().isEmpty()) continue;
            int color = telemetryTextColor(span.kind(), stateColor);
            context.drawTextWithShadow(this.textRenderer, span.text(), cursorX, y, color);
            cursorX += this.textRenderer.getWidth(span.text());
        }
    }

    private int telemetryTextColor(TelemetryText.Kind kind, int stateColor) {
        TelemetryText.Kind safe = kind == null ? TelemetryText.Kind.PLAIN : kind;
        return switch (safe) {
            case KEY -> 0xFF9CB6D8;
            case ID -> 0xFFB9A7D9;
            case STATE -> stateColor;
            case SOURCE -> 0xFF8FBFC6;
            case DURATION -> 0xFFD5B879;
            case COUNT -> 0xFFAFC98B;
            case SUCCESS -> ModelSemanticPalette.color(ModelActivityState.COMPLETE);
            case WARNING -> ModelSemanticPalette.color(ModelActivityState.BLOCKED);
            case ERROR -> ModelSemanticPalette.color(ModelActivityState.FAILED);
            case MUTED -> 0xFF6F7988;
            case VALUE, PLAIN -> 0xFFC9CFD8;
        };
    }

    private void renderMeaningfulDetailLine(DrawContext context, String line, int x, int y, int width, int stateColor) {
        String visible = line == null ? "" : line;
        DetailLine parts = parseDetailLine(visible);
        if (!parts.structured()) {
            int color = semanticDetailColor("", visible, stateColor);
            context.drawTextWithShadow(this.textRenderer, visible, x, y, color);
            return;
        }

        String key = parts.key() + parts.delimiter();
        String value = parts.value();
        int keyColor = withAlpha(systemKindColor(parts.key(), stateColor), 205);
        int valueColor = semanticDetailColor(parts.key(), value, stateColor);
        int keyWidth = this.textRenderer.getWidth(key);
        context.drawTextWithShadow(this.textRenderer, key, x, y, keyColor);
        int remaining = Math.max(8, width - keyWidth);
        context.drawTextWithShadow(this.textRenderer, value, x + keyWidth, y, valueColor);
    }

    private DetailLine parseDetailLine(String line) {
        String value = line == null ? "" : line;
        if (value.isBlank() || value.startsWith("{") || value.startsWith("[") || value.startsWith("\"") || value.startsWith("- ")) {
            return new DetailLine("", "", value, false);
        }
        int equals = value.indexOf('=');
        int colon = value.indexOf(':');
        int split = -1;
        char delimiter = 0;
        if (equals > 0 && isDetailKey(value.substring(0, equals))) {
            split = equals;
            delimiter = '=';
        } else if (colon > 0 && isDetailKey(value.substring(0, colon))) {
            split = colon;
            delimiter = ':';
        }
        if (split <= 0) return new DetailLine("", "", value, false);
        return new DetailLine(value.substring(0, split).strip(), String.valueOf(delimiter), value.substring(split + 1), true);
    }

    private boolean isDetailKey(String candidate) {
        if (candidate == null) return false;
        String key = candidate.strip();
        if (key.isEmpty() || key.length() > 40) return false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ' ')) return false;
        }
        return true;
    }

    private int semanticDetailColor(String key, String value, int stateColor) {
        String normalizedKey = key == null ? "" : key.strip().toLowerCase(Locale.ROOT);
        String normalizedValue = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);

        if (normalizedKey.equals("failure") || normalizedKey.equals("error") || normalizedKey.equals("exception")
            || normalizedKey.equals("blocked") || normalizedKey.equals("restriction")) {
            return ModelSemanticPalette.color(ModelActivityState.FAILED);
        }
        if (normalizedKey.equals("result") || normalizedKey.equals("output") || normalizedKey.equals("received")
            || normalizedKey.equals("postcondition")) {
            return ModelSemanticPalette.color(ModelActivityState.COMPLETE);
        }
        if (normalizedKey.equals("validation") || normalizedKey.equals("validationrequirement")
            || normalizedKey.equals("expectedobservation") || normalizedKey.equals("requires")
            || normalizedKey.equals("precondition")) {
            return ModelSemanticPalette.color(ModelActivityState.VALIDATING);
        }
        if (normalizedKey.equals("tool") || normalizedKey.equals("activetool") || normalizedKey.equals("command")
            || normalizedKey.equals("source") || normalizedKey.equals("arguments")) {
            return ModelSemanticPalette.color(ModelActivityState.EXECUTING);
        }
        if (normalizedKey.equals("plan") || normalizedKey.equals("planid") || normalizedKey.equals("step")
            || normalizedKey.equals("objective")) {
            return ModelSemanticPalette.color(ModelActivityState.PLANNING);
        }
        if (normalizedKey.equals("status") || normalizedKey.equals("state") || normalizedKey.equals("activity")) {
            ModelActivityState semantic = stateForMarker(normalizedValue, normalizedKey);
            return ModelSemanticPalette.color(semantic);
        }
        if (normalizedValue.equals("not exposed") || normalizedValue.startsWith("not exposed ")
            || normalizedValue.equals("unavailable") || normalizedValue.startsWith("unavailable ")
            || normalizedValue.equals("not implemented") || normalizedValue.equals("unsupported")) {
            return 0xFF8F99A8;
        }
        if (normalizedValue.equals("failed") || normalizedValue.equals("blocked") || normalizedValue.startsWith("error:")) {
            return ModelSemanticPalette.color(ModelActivityState.FAILED);
        }
        if (normalizedValue.equals("complete") || normalizedValue.equals("completed") || normalizedValue.equals("success")) {
            return ModelSemanticPalette.color(ModelActivityState.COMPLETE);
        }
        return 0xFFBCC4D0;
    }

    private record DetailLine(String key, String delimiter, String value, boolean structured) { }

    private static int systemKindColor(String kind, int fallback) {
        String lower = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        if (lower.contains("model") || lower.contains("voice")) return ModelSemanticPalette.color(ModelActivityState.THINKING);
        if (lower.contains("exec") || lower.contains("tool") || lower.contains("command")) return ModelSemanticPalette.color(ModelActivityState.EXECUTING);
        if (lower.contains("ktl") || lower.contains("plan") || lower.contains("task")) return ModelSemanticPalette.color(ModelActivityState.PLANNING);
        if (lower.contains("event") || lower.contains("runtime")) return ModelSemanticPalette.color(ModelActivityState.OBSERVING);
        if (lower.contains("user")) return 0xFF9DAABD;
        return fallback == 0 ? 0xFF8793A3 : fallback;
    }

    private static String stateBox(ModelActivityState state) {
        if (state == ModelActivityState.COMPLETE) return "[x]";
        if (state == ModelActivityState.FAILED || state == ModelActivityState.BLOCKED) return "[!]";
        if (state == ModelActivityState.CANCELLED) return "[-]";
        return "[ ]";
    }

    private static int notebookBorderColor() {
        return new Color(com.spirit.koil.api.design.uiColorVal.uiColorBackgroundBorder, true).getRGB();
    }

    private static double clampScroll(double scroll, int contentSize, int viewportSize) {
        return MathHelper.clamp(scroll, 0.0D, Math.max(0, contentSize - viewportSize));
    }

    private static void renderVerticalScrollbar(
        DrawContext context,
        int x,
        int top,
        int bottom,
        double scroll,
        int content,
        int viewport
    ) {
        if (content <= viewport || viewport <= 0) {
            return;
        }
        int thumb = Math.max(10, viewport * viewport / content);
        int travel = Math.max(1, viewport - thumb);
        int maxScroll = Math.max(1, content - viewport);
        int y = top + (int) Math.round(travel * scroll / maxScroll);
        context.fill(x, top, x + 1, bottom, 0x66404A58);
        context.fill(x - 1, y, x + 2, y + thumb, 0xCC9DAABD);
    }

    private static void renderHorizontalScrollbar(
        DrawContext context,
        int left,
        int right,
        int y,
        double scroll,
        int content,
        int viewport
    ) {
        if (content <= viewport || viewport <= 0) {
            return;
        }
        int width = right - left;
        int thumb = Math.max(12, viewport * viewport / content);
        int travel = Math.max(1, width - thumb);
        int maxScroll = Math.max(1, content - viewport);
        int x = left + (int) Math.round(travel * scroll / maxScroll);
        context.fill(left, y, right, y + 1, 0x66404A58);
        context.fill(x, y - 1, x + thumb, y + 2, 0xCC9DAABD);
    }

    private static void fillLineH(DrawContext context, int x1, int x2, int y, int color) {
        int left = Math.min(x1, x2);
        int right = Math.max(x1, x2);
        context.fill(left, y, Math.max(left + 1, right), y + 1, color);
    }

    private static void fillLineV(DrawContext context, int x, int y1, int y2, int color) {
        int top = Math.min(y1, y2);
        int bottom = Math.max(y1, y2);
        context.fill(x, top, x + 1, Math.max(top + 1, bottom), color);
    }

    private static void drawDashedHorizontal(DrawContext context, int left, int right, int y, int color) {
        for (int x = left; x < right; x += 6) {
            context.fill(x, y, Math.min(right, x + 3), y + 1, color);
        }
    }

    private static void drawDashedVertical(DrawContext context, int x, int top, int bottom, int color) {
        for (int y = top; y < bottom; y += 6) {
            context.fill(x, y, x + 1, Math.min(bottom, y + 3), color);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (MathHelper.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    private static String friendly(String value) {
        String text = value == null ? "Activity" : value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return text.isBlank() ? "Activity" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String humanizeIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "component";
        }
        String spaced = value.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        return spaced.toLowerCase(Locale.ROOT);
    }

    private static String simpleClassName(String className) {
        if (className == null || className.isBlank()) {
            return "Unknown";
        }
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static String packageName(String className) {
        if (className == null || className.isBlank()) {
            return "";
        }
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    private static String modifierText(int modifiers) {
        String value = Modifier.toString(modifiers);
        return value == null || value.isBlank() ? "package" : value;
    }

    private static String methodSignature(Method method) {
        return modifierText(method.getModifiers())
            + " " + typeName(method.getReturnType())
            + " " + method.getName()
            + parameterSummary(method.getParameterTypes());
    }

    private static String constructorSignature(Constructor<?> constructor) {
        return modifierText(constructor.getModifiers())
            + " " + constructor.getDeclaringClass().getSimpleName()
            + parameterSummary(constructor.getParameterTypes());
    }

    private static String parameterSummary(Class<?>[] parameterTypes) {
        StringBuilder builder = new StringBuilder("(");
        for (int index = 0; index < parameterTypes.length; index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(typeName(parameterTypes[index]));
        }
        return builder.append(')').toString();
    }

    private static String typeName(Class<?> type) {
        if (type == null) {
            return "?";
        }
        if (type.isArray()) {
            return typeName(type.getComponentType()) + "[]";
        }
        return type.getSimpleName().isBlank() ? type.getTypeName() : type.getSimpleName();
    }

    private static String safeMessage(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static String traceNodeId(String traceId) {
        return "trace:" + (traceId == null ? "" : traceId);
    }

    private enum TraceFilter {
        MESSAGE("Message"),
        ACTIVE("Active"),
        ALL("All");

        private final String label;

        TraceFilter(String label) {
            this.label = label;
        }

        private String label() {
            return this.label;
        }

        private TraceFilter next() {
            return switch (this) {
                case MESSAGE -> ACTIVE;
                case ACTIVE -> ALL;
                case ALL -> MESSAGE;
            };
        }
    }

    private static final class WorkspaceChatMessage {
        private final long sequence;
        private final boolean model;
        private UUID requestId;
        private UUID conversationMessageId;
        private String text;
        private ModelGenerationHudState.Snapshot snapshot;
        private final long timestampMillis;

        private WorkspaceChatMessage(
            long sequence,
            boolean model,
            UUID requestId,
            String text,
            ModelGenerationHudState.Snapshot snapshot,
            long timestampMillis
        ) {
            this(sequence, model, requestId, null, text, snapshot, timestampMillis);
        }

        private WorkspaceChatMessage(
            long sequence,
            boolean model,
            UUID requestId,
            UUID conversationMessageId,
            String text,
            ModelGenerationHudState.Snapshot snapshot,
            long timestampMillis
        ) {
            this.sequence = sequence;
            this.model = model;
            this.requestId = requestId;
            this.conversationMessageId = conversationMessageId;
            this.text = text == null ? "" : text;
            this.snapshot = snapshot;
            this.timestampMillis = timestampMillis;
        }
    }

    private record WorkspaceRenderedLine(
        OrderedText orderedText,
        Text animatedText,
        int color,
        int height,
        boolean cursorAfter,
        ModelOutputMaterialization.Frame materialization
    ) {
        private WorkspaceRenderedLine(
            OrderedText orderedText,
            Text animatedText,
            int color,
            int height,
            boolean cursorAfter
        ) {
            this(orderedText, animatedText, color, height, cursorAfter, null);
        }

        private WorkspaceRenderedLine {
            height = Math.max(1, height);
        }
    }

    private record WorkspaceToolRow(
        String nodeId,
        String badge,
        List<WorkspaceRenderedLine> lines,
        int height,
        ModelActivityState state,
        boolean active
    ) {
        private WorkspaceToolRow {
            nodeId = nodeId == null ? "" : nodeId;
            badge = badge == null ? "TRACE" : badge;
            lines = lines == null ? List.of() : List.copyOf(lines);
            height = Math.max(1, height);
            state = state == null ? ModelActivityState.OBSERVING : state;
        }
    }

    private record WorkspaceRenderedMessage(
        UUID requestId,
        boolean model,
        String prefix,
        int prefixColor,
        List<WorkspaceRenderedLine> lines,
        List<WorkspaceToolRow> toolRows,
        int height,
        boolean active,
        ModelActivityState state,
        boolean footerVisible,
        String footerStatusLabel,
        String footerStatusDetail,
        String footerSemanticState,
        int footerColor,
        String footerHistoryLabel,
        String footerHistoryDetail,
        int footerHistoryColor,
        double footerHistoryAlpha,
        double tokensPerSecond,
        long createdAtMillis,
        long completedAtMillis,
        int footerHeight
    ) {
        private WorkspaceRenderedMessage {
            lines = lines == null ? List.of() : List.copyOf(lines);
            toolRows = toolRows == null ? List.of() : List.copyOf(toolRows);
            height = Math.max(1, height);
            state = state == null ? ModelActivityState.IDLE : state;
            footerStatusLabel = footerStatusLabel == null ? "" : footerStatusLabel;
            footerStatusDetail = footerStatusDetail == null ? "" : footerStatusDetail;
            footerSemanticState = footerSemanticState == null ? "" : footerSemanticState;
            footerHistoryLabel = footerHistoryLabel == null ? "" : footerHistoryLabel;
            footerHistoryDetail = footerHistoryDetail == null ? "" : footerHistoryDetail;
            footerHistoryAlpha = Math.max(0.0D, Math.min(1.0D, footerHistoryAlpha));
            tokensPerSecond = Math.max(0.0D, tokensPerSecond);
            createdAtMillis = Math.max(0L, createdAtMillis);
            completedAtMillis = Math.max(0L, completedAtMillis);
            footerHeight = Math.max(0, footerHeight);
        }
    }

    private record ToolMessageHit(
        UUID requestId,
        String nodeId,
        int left,
        int top,
        int right,
        int bottom
    ) {
        boolean contains(double x, double y) {
            return x >= this.left && x < this.right && y >= this.top && y < this.bottom;
        }
    }

    private record MessageHit(UUID requestId, int left, int top, int right, int bottom) {
        boolean contains(double x, double y) {
            return x >= this.left && x < this.right && y >= this.top && y < this.bottom;
        }
    }

    private record TextWrapKey(String text, int width) {}

    private record TypedWrapKey(String nodeId, int contentHash, int lineCount, int width, int limit) {
        private TypedWrapKey {
            nodeId = nodeId == null ? "" : nodeId;
            lineCount = Math.max(0, lineCount);
            width = Math.max(1, width);
            limit = Math.max(1, limit);
        }
    }

    private record WorkspaceMessageRenderKey(
        long sequence,
        int width,
        int textLength,
        int textTailHash,
        int stateHash,
        int activityStateHash,
        int detailHash,
        int eventsSize,
        int tailEventHash,
        int toolCallCount,
        int currentToolStep,
        int totalToolSteps,
        int activeToolIdHash,
        int activeToolDetailHash,
        int usageHash,
        boolean cursorReserved,
        boolean model
    ) {}

    private record PaneMeasureKey(
        String id,
        int titleHash,
        int summaryHash,
        int detailHash,
        int sourceHash,
        int traceIdHash,
        int typedDetailHash,
        boolean selected,
        boolean fullDetail
    ) {}

    private enum WorkspaceViewMode {
        NOTEBOOK("Notebook"),
        TIMELINE("Timeline"),
        TOPOLOGY("Topology");

        private final String label;

        WorkspaceViewMode(String label) {
            this.label = label;
        }

        private WorkspaceViewMode next() {
            WorkspaceViewMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private enum TelemetryDetailMode {
        SUMMARY("Summary"),
        DETAILS("Details"),
        RAW("Raw");

        private final String label;

        TelemetryDetailMode(String label) {
            this.label = label;
        }

        private TelemetryDetailMode next() {
            TelemetryDetailMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private static final class NotebookNode {
        private final String id;
        private final String title;
        private final String kind;
        private String summary;
        private String detail;
        private final String source;
        private ModelActivityState state;
        private final String className;
        private final String traceId;
        private final List<NotebookNode> children = new ArrayList<>();
        private final Set<String> links = new LinkedHashSet<>();
        private List<List<TelemetryText>> typedDetailLines = List.of();
        private AutomationWorkspaceTelemetryProjection.Flow inboundFlow = AutomationWorkspaceTelemetryProjection.Flow.none();
        private long startedAtMillis;
        private long durationMillis;
        private boolean temporalActive;
        private int concurrentPeers;
        private boolean membersLoaded;

        private NotebookNode(
            String id,
            String title,
            String kind,
            String summary,
            String detail,
            String source,
            ModelActivityState state,
            String className,
            String traceId
        ) {
            this.id = id == null ? "" : id;
            this.title = title == null || title.isBlank() ? "Untitled" : title;
            this.kind = kind == null || kind.isBlank() ? "NOTE" : kind;
            this.summary = summary == null ? "" : summary;
            this.detail = detail == null ? "" : detail;
            this.source = source == null ? "" : source;
            this.state = state == null ? ModelActivityState.IDLE : state;
            this.className = className == null ? "" : className;
            this.traceId = traceId == null ? "" : traceId;
        }

        private void add(NotebookNode child) {
            if (child != null) {
                this.children.add(child);
            }
        }
    }

    private record LayoutEntry(
        NotebookNode node,
        String parentId,
        int depth,
        int x,
        int y,
        int width,
        int height
    ) {
        int right() {
            return this.x + this.width;
        }

        int bottom() {
            return this.y + this.height;
        }

        LayoutEntry shifted(int offsetX, int offsetY) {
            return new LayoutEntry(this.node, this.parentId, this.depth,
                this.x + offsetX, this.y + offsetY, this.width, this.height);
        }
    }

    private record ConnectionEntry(String fromId, String toId, boolean dashed) {
    }

    private record LayoutSnapshot(
        List<LayoutEntry> layout,
        Map<String, LayoutEntry> byId,
        List<ConnectionEntry> connections
    ) {
        private static LayoutSnapshot empty() {
            return new LayoutSnapshot(List.of(), Map.of(), List.of());
        }
    }

    private record NoteHit(NotebookNode node, int left, int top, int right, int bottom, int logicalX, int logicalY) {
        boolean contains(double x, double y) {
            return x >= this.left && x < this.right && y >= this.top && y < this.bottom;
        }
    }

    private record NotePosition(double x, double y) {
    }

    private record PadSearchResult(String nodeId, String title, String secondary) {
    }

    private record ComposerVisualLine(
        String hardLine,
        int hardGlobalStart,
        int sourceFrom,
        int sourceTo
    ) {
        private ComposerVisualLine {
            hardLine = hardLine == null ? "" : hardLine;
            sourceFrom = Math.max(0, Math.min(hardLine.length(), sourceFrom));
            sourceTo = Math.max(sourceFrom, Math.min(hardLine.length(), sourceTo));
        }

        int globalStart() {
            return this.hardGlobalStart + this.sourceFrom;
        }

        int globalEnd() {
            return this.hardGlobalStart + this.sourceTo;
        }
    }

    private record DraftSuggestionContext(
        int lineStart,
        int lineEnd,
        String lineText,
        int localCursor,
        String commandText,
        int commandCursor
    ) {
        String requestKey() {
            return this.lineStart + ":" + this.lineEnd + ":" + this.localCursor + ":" + this.lineText;
        }
    }

    private record ChatRenderLine(
        String role,
        int roleColor,
        OrderedText orderedText,
        Text animatedText,
        int textColor,
        int height
    ) {
        private ChatRenderLine {
            height = Math.max(1, height);
        }
    }

    private record ActionHit(int left, int top, int right, int bottom, boolean enabled, Runnable action) {
        boolean contains(double x, double y) {
            return x >= this.left && x < this.right && y >= this.top && y < this.bottom;
        }
    }
}
