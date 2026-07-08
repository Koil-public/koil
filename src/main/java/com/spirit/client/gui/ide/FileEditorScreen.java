package com.spirit.client.gui.ide;

import com.spirit.Main;
import com.spirit.client.gui.PopupMenu;
import com.spirit.client.gui.PopupMenu.MenuEntry;
import com.spirit.client.gui.TopBarLayout;
import com.spirit.client.gui.UiSoundHelper;
import com.spirit.client.gui.mod.ModConfigScreen;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.util.file.media.video.VideoService;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.spirit.Main.LOGO_TEXTURE;
import static com.spirit.client.gui.ide.FileExplorerScreen.FOLDER_ICON;
import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
public class FileEditorScreen extends Screen {
    private static final int HEADER_HEIGHT = 60;
    private static final int CONTENT_TOP = 70;
    private static final int SIDEBAR_WIDTH = 170;
    private static final int EDITOR_HEADER_HEIGHT = 42;
    private static final int STATUS_BAR_HEIGHT = 20;
    private static final int PADDING = 10;
    private static final int DOUBLE_CLICK_MS = 250;
    private static final int SAVE_SUCCESS_COLOR = new Color(uiColorSaveSuccessColor, true).getRGB();
    private static final DateTimeFormatter SAVE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String TOP_BAR_BACK_LABEL = "<";
    private static final String TOP_BAR_CHOOSE_LABEL = "Open";
    private static final String TOP_BAR_SAVE_LABEL = "Save";
    private static final String TOP_BAR_CLEAR_LABEL = "Clear";
    private static final String TOP_BAR_RELOAD_LABEL = "Reset";
    private static final int SEARCH_DROPDOWN_MAX_VISIBLE_ROWS = 8;
    private static final int SEARCH_DROPDOWN_ROW_HEIGHT = 18;
    private static final int SEARCH_DROPDOWN_PADDING = 6;
    private static final int SEARCH_DROPDOWN_SECTION_GAP = 4;
    private static final int MAX_SEARCH_RESULTS = 500;
    private static final int MAX_RECENT_SEARCHES = 8;
    private static final int MAX_SUGGESTIONS = 6;
    private static final int MAX_UNDO_HISTORY = 100;
    private static final int MAX_EDITOR_SUGGESTIONS = 8;
    private static final int EDITOR_SUGGESTION_ROW_HEIGHT = 16;
    private static final int EDITOR_SUGGESTION_PADDING = 5;
    private static final Map<String, String> SIDEBAR_NOTES = new HashMap<>();
    private static final int TOOLTIP_ERROR_COLOR = new Color(uiColorToolTipError, true).getRGB();
    private static final int TOOLTIP_WARNING_COLOR = new Color(uiColorToolTipWarning, true).getRGB();
    private static final int TOOLTIP_LABEL_COLOR = new Color(uiColorToolTipLabel, true).getRGB();
    private static final int TOOLTIP_PRIMARY_COLOR = new Color(uiColorToolTipPrimary, true).getRGB();
    private static final int TOOLTIP_SECONDARY_COLOR = new Color(uiColorToolTipSecondary, true).getRGB();
    private static final int TOOLTIP_IDEA_COLOR = new Color(uiColorToolTipIdea, true).getRGB();
    private static final int TOOLTIP_FIX_COLOR = new Color(uiColorToolTipFix, true).getRGB();
    private static final Set<String> CONFIG_EDITOR_EXTENSIONS = Set.of(
            "json", "json5", "toml", "yaml", "yml", "properties", "cfg", "conf"
    );

    private final Screen parent;
    private final File fileItem;
    private final FileItem loadedFileItem;

    private EditorDocument document;
    private TextFieldWidget searchInput;
    private TextFieldWidget replaceInput;
    private EditorDocument sidebarNotesDocument;
    private boolean sidebarNotesFocused = false;
    private boolean sidebarNotesDragSelecting = false;
    private int sidebarNotesVerticalScrollOffset = 0;
    private int[] sidebarNotesBounds = null;
    private final List<SearchMatch> searchMatches = new ArrayList<>();
    private final List<DropdownEntry> searchDropdownEntries = new ArrayList<>();
    private final List<String> recentSearches = new ArrayList<>();
    private int activeSearchMatchIndex = -1;
    private int selectedDropdownIndex = -1;
    private int dropdownScrollOffset = 0;
    private boolean searchCaseSensitive = false;
    private boolean searchWholeWord = false;
    private boolean searchRegex = false;
    private boolean searchReplaceVisible = false;
    private boolean searchHighlightOnly = false;
    private boolean searchAutoJump = true;
    private boolean searchDropdownDismissed = false;
    private String searchErrorMessage = "";
    private ParsedSearchCommand parsedSearchCommand = ParsedSearchCommand.search("");
    private Pattern activeSearchPattern;

    private int verticalScrollOffset = 0;
    private int horizontalScrollOffset = 0;
    private long lastCursorBlink = 0L;
    private boolean cursorVisible = true;
    private long lastClickTime = 0L;
    private int clickStreak = 0;
    private int lastClickLine = -1;
    private String lastSavedText = "";
    private String saveStatusMessage = "Loaded";
    private int saveStatusColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
    private long saveStatusUntil = 0L;
    private boolean dragSelecting = false;
    private boolean editorScrollbarDragging = false;
    private int editorScrollbarDragOffset = 0;
    private int cachedLongestLineWidth = -1;
    private final Deque<String> undoHistory = new ArrayDeque<>();
    private final Deque<String> redoHistory = new ArrayDeque<>();
    private final List<EditorSuggestion> editorSuggestions = new ArrayList<>();
    private int selectedEditorSuggestionIndex = 0;
    private boolean editorSuggestionDismissed = false;
    private int[] editorSuggestionBounds = null;
    private final List<int[]> editorSuggestionRowBounds = new ArrayList<>();
    private boolean fileLoadFailed = false;
    private boolean readOnlyMode = false;
    private String lastErrorMessage = "";
    private String lastErrorDetail = "";
    private final List<EditorDiagnostic> lineDiagnostics = new ArrayList<>();
    private final List<int[]> sidebarDiagnosticMarkerBounds = new ArrayList<>();
    private int cachedErrorCount = 0;
    private int cachedWarningCount = 0;
    private final PopupMenu topBarOpenMenu = new PopupMenu();
    private int[] topBarOpenBounds = null;
    private final int initialLineNumber;
    private final boolean requestedReadOnly;

    public FileEditorScreen(Screen parent, File fileItem, FileItem loadedFileItem) {
        this(parent, fileItem, loadedFileItem, -1);
    }

    public FileEditorScreen(Screen parent, File fileItem, FileItem loadedFileItem, int initialLineNumber) {
        this(parent, fileItem, loadedFileItem, initialLineNumber, false);
    }

    public FileEditorScreen(Screen parent, File fileItem, FileItem loadedFileItem, int initialLineNumber, boolean readOnly) {
        super(Text.literal("Koil Editor"));
        this.parent = sanitizeDirectParent(parent);
        this.fileItem = fileItem;
        this.loadedFileItem = loadedFileItem;
        this.initialLineNumber = initialLineNumber;
        this.requestedReadOnly = readOnly;
    }

    @Override
    protected void init() {
        super.init();
        loadFileContent();
        loadSidebarNotes();
        if (initialLineNumber > 0 && document != null) {
            jumpToLine(initialLineNumber);
        }
        initTopBar();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        assert client != null;
        drawShell(context);
        drawSidebar(context);
        drawEditorChrome(context);
        drawEditorViewport(context, mouseX, mouseY);
        drawStatusBar(context);
        drawSearchDropdown(context, mouseX, mouseY);
        topBarOpenMenu.render(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (document != null && isDirty() && !saveFileContent()) {
            return;
        }
        if (this.client != null) {
            this.client.setScreen(resolveReturnScreen());
        }
    }

    @Override
    public void tick() {
        long now = Util.getMeasuringTimeMs();
        if (now - lastCursorBlink > 500L) {
            cursorVisible = !cursorVisible;
            lastCursorBlink = now;
        }
        updateSearchWidgetLayout();
        if (searchInput != null) {
            searchInput.setVisible(!searchReplaceVisible);
            searchInput.setEditable(!searchReplaceVisible);
        }
        if (replaceInput != null) {
            replaceInput.setVisible(searchReplaceVisible);
            replaceInput.setEditable(searchReplaceVisible);
        }
    }

    @Override
    public void filesDragged(List<Path> paths) {
        if (paths == null || paths.isEmpty() || this.client == null) {
            return;
        }
        Path droppedPath = paths.get(0);
        if (droppedPath == null) {
            return;
        }
        openChosenPath(droppedPath.toFile());
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (sidebarNotesFocused) {
            if (hasControlDown() || Character.isISOControl(chr) || sidebarNotesDocument == null) {
                return super.charTyped(chr, modifiers);
            }
            if (handleSidebarNotesAutoClosingPair(chr)) {
                persistSidebarNotes();
                return true;
            }
            if (sidebarNotesDocument.insertChar(chr)) {
                persistSidebarNotes();
                ensureSidebarNotesCursorVisible();
                return true;
            }
            return super.charTyped(chr, modifiers);
        }
        if (searchInput != null && searchInput.isFocused()) {
            return super.charTyped(chr, modifiers);
        }
        if (replaceInput != null && replaceInput.isFocused()) {
            return super.charTyped(chr, modifiers);
        }

        if (hasControlDown() || Character.isISOControl(chr)) {
            return super.charTyped(chr, modifiers);
        }

        if (!canMutateDocument("Typing is unavailable")) {
            return true;
        }

        if (handleAutoClosingPair(chr)) {
            onDocumentMutated("Paired");
            editorSuggestionDismissed = false;
            return true;
        }

        captureUndoSnapshot();
        if (document.insertChar(chr)) {
            onDocumentMutated("Edited");
            editorSuggestionDismissed = false;
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean shiftPressed = hasShiftDown();
        boolean controlPressed = hasControlDown();

        if (sidebarNotesFocused) {
            if (handleSidebarNotesKeyPressed(keyCode, shiftPressed)) {
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        if (controlPressed) {
            if (keyCode == GLFW.GLFW_KEY_Z) {
                if (!canMutateDocument("Undo is unavailable")) {
                    return true;
                }
                undoEdit();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_Y) {
                if (!canMutateDocument("Redo is unavailable")) {
                    return true;
                }
                redoEdit();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_C) {
                copySelectionToClipboard();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_X) {
                if (!canMutateDocument("Cut is unavailable")) {
                    return true;
                }
                cutSelectionToClipboard();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_V) {
                pasteClipboardIntoDocument();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_S) {
                saveFileContent();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_A) {
                document.selectAll();
                ensureCursorVisible();
                resetCursorBlink();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_HOME) {
                document.moveToDocumentStart(shiftPressed);
                ensureCursorVisible();
                resetCursorBlink();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_END) {
                document.moveToDocumentEnd(shiftPressed);
                ensureCursorVisible();
                resetCursorBlink();
                return true;
            }
        }

        if (searchInput != null && searchInput.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                moveDropdownSelection(1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP) {
                moveDropdownSelection(-1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                if (replaceInput != null && searchReplaceVisible) {
                    searchInput.setFocused(false);
                    replaceInput.setFocused(true);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (!activateSelectedDropdownEntry()) {
                    navigateSearchResult(!shiftPressed);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                searchInput.setFocused(false);
                return true;
            }
            boolean handledByField = super.keyPressed(keyCode, scanCode, modifiers);
            if (handledByField || keyCode != GLFW.GLFW_KEY_ESCAPE) {
                return true;
            }
        }

        if (replaceInput != null && replaceInput.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                replaceInput.setFocused(false);
                searchInput.setFocused(true);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                replaceCurrentMatch();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                exitReplaceMode(true);
                return true;
            }
            boolean handledByReplaceField = super.keyPressed(keyCode, scanCode, modifiers);
            if (handledByReplaceField || keyCode != GLFW.GLFW_KEY_ESCAPE) {
                return true;
            }
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE && isSearchInteractionActive()) {
            clearSearchInteractionFocus();
            return true;
        }

        if (controlPressed && keyCode == GLFW.GLFW_KEY_SPACE) {
            editorSuggestionDismissed = false;
            updateEditorSuggestions();
            return true;
        }

        if (isEditorSuggestionVisible()) {
            if (keyCode == GLFW.GLFW_KEY_TAB || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (acceptSelectedEditorSuggestion()) {
                    return true;
                }
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                editorSuggestionDismissed = true;
                updateEditorSuggestions();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP) {
                moveEditorSuggestionSelection(-1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                moveEditorSuggestionSelection(1);
                return true;
            }
        }

        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                close();
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                document.moveLeft(shiftPressed);
                ensureCursorVisible();
                resetCursorBlink();
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                document.moveRight(shiftPressed);
                ensureCursorVisible();
                resetCursorBlink();
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                document.moveUp(shiftPressed);
                ensureCursorVisible();
                resetCursorBlink();
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                document.moveDown(shiftPressed);
                ensureCursorVisible();
                resetCursorBlink();
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                document.moveHome(shiftPressed);
                ensureCursorVisible();
                resetCursorBlink();
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                document.moveEnd(shiftPressed);
                ensureCursorVisible();
                resetCursorBlink();
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_UP -> {
                document.movePageUp(getVisibleLineCount(), shiftPressed);
                ensureCursorVisible();
                resetCursorBlink();
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_DOWN -> {
                document.movePageDown(getVisibleLineCount(), shiftPressed);
                ensureCursorVisible();
                resetCursorBlink();
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (!canMutateDocument("Insert is unavailable")) {
                    return true;
                }
                captureUndoSnapshot();
                if (insertNewLineWithIndentation()) {
                    onDocumentMutated("Inserted line");
                }
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (!canMutateDocument("Delete is unavailable")) {
                    return true;
                }
                captureUndoSnapshot();
                if (document.backspace()) {
                    onDocumentMutated("Deleted");
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (!canMutateDocument("Delete is unavailable")) {
                    return true;
                }
                captureUndoSnapshot();
                if (document.deleteForward()) {
                    onDocumentMutated("Deleted");
                }
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                if (!canMutateDocument("Indent is unavailable")) {
                    return true;
                }
                captureUndoSnapshot();
                if (shiftPressed) {
                    if (adjustIndentation(false)) {
                        onDocumentMutated("Outdented");
                    }
                } else if (adjustIndentation(true) || document.insertText("    ")) {
                    onDocumentMutated("Indented");
                }
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (isInsideSidebarNotes(mouseX, mouseY) && sidebarNotesDocument != null) {
            sidebarNotesVerticalScrollOffset = clamp(sidebarNotesVerticalScrollOffset - (int) amount, 0, getSidebarNotesMaxScroll());
            return true;
        }
        if (isInsideSearchDropdown(mouseX, mouseY)) {
            int maxScroll = Math.max(0, searchDropdownEntries.size() - SEARCH_DROPDOWN_MAX_VISIBLE_ROWS);
            dropdownScrollOffset = clamp(dropdownScrollOffset - (int) amount, 0, maxScroll);
            return true;
        }

        if (!isInsideEditorViewport(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, amount);
        }

        if (hasShiftDown()) {
            horizontalScrollOffset = clamp(horizontalScrollOffset - (int) amount * 24, 0, getMaxHorizontalScroll());
        } else {
            verticalScrollOffset = clamp(verticalScrollOffset - (int) amount, 0, getMaxVerticalScroll());
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (topBarOpenMenu.isOpen() && (topBarOpenBounds == null || !isWithinBounds(topBarOpenBounds, mouseX, mouseY))) {
            MenuEntry selected = topBarOpenMenu.click(mouseX, mouseY);
            if (selected != null) {
                handleTopBarOpenAction(selected.id());
                return true;
            }
        }

        if (searchInput != null && searchInput.isMouseOver(mouseX, mouseY)) {
            if (replaceInput != null) {
                replaceInput.setFocused(false);
            }
            searchDropdownDismissed = false;
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (replaceInput != null && replaceInput.isVisible() && replaceInput.isMouseOver(mouseX, mouseY)) {
            if (searchInput != null) {
                searchInput.setFocused(false);
            }
            searchDropdownDismissed = false;
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (isInsideSidebarNotes(mouseX, mouseY)) {
            if (searchInput != null) {
                searchInput.setFocused(false);
            }
            if (replaceInput != null) {
                replaceInput.setFocused(false);
            }
            if (button == 0 && sidebarNotesDocument != null) {
                SidebarNotePoint point = resolveSidebarNotesPoint(mouseX, mouseY);
                long now = Util.getMeasuringTimeMs();
                if (now - lastClickTime <= DOUBLE_CLICK_MS && point.line() == lastClickLine) {
                    selectSidebarNotesWord(point.line(), point.column());
                    sidebarNotesDragSelecting = false;
                } else if (hasShiftDown()) {
                    sidebarNotesDocument.setCursor(point.line(), point.column(), true);
                    sidebarNotesDragSelecting = false;
                } else {
                    sidebarNotesDocument.startSelection(point.line(), point.column());
                    sidebarNotesDocument.updateSelection(point.line(), point.column());
                    sidebarNotesDragSelecting = true;
                }
                sidebarNotesFocused = true;
                ensureSidebarNotesCursorVisible();
                lastClickTime = now;
                lastClickLine = point.line();
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (searchInput != null && !searchInput.isMouseOver(mouseX, mouseY)) {
            searchInput.setFocused(false);
        }
        if (replaceInput != null && !replaceInput.isMouseOver(mouseX, mouseY)) {
            replaceInput.setFocused(false);
        }
        if (!isInsideSidebarNotes(mouseX, mouseY)) {
            sidebarNotesFocused = false;
        }

        if (button == 0 && handleSearchDropdownClick(mouseX, mouseY)) {
            searchDropdownDismissed = false;
            UiSoundHelper.playButtonClick();
            return true;
        }

        if (button == 0 && handleEditorSuggestionClick(mouseX, mouseY)) {
            UiSoundHelper.playButtonClick();
            return true;
        }

        if (button == 0 && isOverEditorScrollbar(mouseX, mouseY)) {
            int thumbY = getEditorScrollbarThumbY();
            int thumbHeight = getEditorScrollbarThumbHeight();
            this.editorScrollbarDragging = true;
            this.editorScrollbarDragOffset = mouseY >= thumbY && mouseY <= thumbY + thumbHeight ? (int) mouseY - thumbY : thumbHeight / 2;
            setEditorScrollFromThumbTop((int) mouseY - this.editorScrollbarDragOffset);
            return true;
        }

        if (button == 0) {
            int sidebarDiagnosticLine = getSidebarDiagnosticLineAt(mouseX, mouseY);
            if (sidebarDiagnosticLine >= 0) {
                jumpToLine(sidebarDiagnosticLine + 1);
                UiSoundHelper.playButtonClick();
                return true;
            }
        }

        if (button == 0 && isSearchDropdownVisible() && !isInsideSearchDropdown(mouseX, mouseY)) {
            searchDropdownDismissed = true;
            if (searchInput != null) {
                searchInput.setFocused(false);
            }
            if (replaceInput != null) {
                replaceInput.setFocused(false);
            }
            searchReplaceVisible = false;
        }

        if (button == 0 && isEditorSuggestionVisible() && !isInsideEditorSuggestionPopup(mouseX, mouseY)) {
            editorSuggestionDismissed = true;
            updateEditorSuggestions();
        }

        if (isTopBarButtonClicked(mouseX, mouseY, 10, getTopBarButtonWidth(TOP_BAR_BACK_LABEL))) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            close();
            return true;
        }

        if (isTopBarButtonClicked(mouseX, mouseY, getTopBarChooseButtonX(), getTopBarButtonWidth(TOP_BAR_CHOOSE_LABEL))) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            topBarOpenMenu.toggleAtPointer(mouseX, mouseY, this.width, this.height, buildTopBarOpenMenuItems());
            return true;
        }

        int saveButtonX = getTopBarSaveButtonX();
        int clearButtonX = getTopBarClearButtonX();
        int reloadButtonX = getTopBarReloadButtonX();
        if (isTopBarButtonClicked(mouseX, mouseY, saveButtonX, getTopBarButtonWidth(TOP_BAR_SAVE_LABEL))) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            saveFileContent();
            return true;
        }
        if (isTopBarButtonClicked(mouseX, mouseY, clearButtonX, getTopBarButtonWidth(TOP_BAR_CLEAR_LABEL))) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            if (searchInput != null) {
                searchInput.setText("");
                searchInput.setFocused(true);
            }
            return true;
        }
        if (isTopBarButtonClicked(mouseX, mouseY, reloadButtonX, getTopBarButtonWidth(TOP_BAR_RELOAD_LABEL))) {
            if (button == 0) {
                UiSoundHelper.playButtonClick();
            }
            loadFileContent();
            resetCursorBlink();
            return true;
        }

        if (button == 0) {
            int diagnosticLine = getDiagnosticLineAtMouse(mouseX, mouseY);
            if (diagnosticLine >= 0) {
                if (applyDiagnosticQuickClick(diagnosticLine)) {
                    UiSoundHelper.playQuickFixConfirm();
                    return true;
                }
                jumpToLine(diagnosticLine + 1);
                UiSoundHelper.playButtonClick();
                return true;
            }
        }

        if (button != 0 || !isInsideEditorViewport(mouseX, mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        EditorPoint point = resolvePointFromMouse(mouseX, mouseY);
        long now = Util.getMeasuringTimeMs();
        boolean sameClickTarget = point.line() == lastClickLine;
        if (now - lastClickTime <= DOUBLE_CLICK_MS && sameClickTarget) {
            clickStreak++;
        } else {
            clickStreak = 1;
        }

        if (clickStreak >= 3) {
            selectLineAt(point.line());
            dragSelecting = false;
            clickStreak = 0;
        } else if (clickStreak == 2) {
            selectWordAt(point.line(), point.column());
            dragSelecting = false;
        } else if (hasShiftDown()) {
            document.setCursor(point.line(), point.column(), true);
            dragSelecting = false;
        } else {
            document.startSelection(point.line(), point.column());
            document.updateSelection(point.line(), point.column());
            dragSelecting = true;
        }

        ensureCursorVisible();
        resetCursorBlink();
        lastClickTime = now;
        lastClickLine = point.line();
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && this.editorScrollbarDragging) {
            setEditorScrollFromThumbTop((int) mouseY - this.editorScrollbarDragOffset);
            return true;
        }
        if (button == 0 && sidebarNotesDragSelecting && sidebarNotesDocument != null) {
            SidebarNotePoint point = resolveSidebarNotesPoint(mouseX, mouseY);
            sidebarNotesDocument.updateSelection(point.line(), point.column());
            ensureSidebarNotesCursorVisible();
            return true;
        }
        if (button != 0 || !dragSelecting) {
            return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        }

        EditorPoint point = resolvePointFromMouse(mouseX, mouseY);
        document.updateSelection(point.line(), point.column());
        ensureCursorVisible();
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            dragSelecting = false;
            sidebarNotesDragSelecting = false;
            editorScrollbarDragging = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean handleEditorSuggestionClick(double mouseX, double mouseY) {
        if (!isEditorSuggestionVisible() || !isInsideEditorSuggestionPopup(mouseX, mouseY)) {
            return false;
        }
        for (int i = 0; i < editorSuggestionRowBounds.size(); i++) {
            int[] bounds = editorSuggestionRowBounds.get(i);
            if (isWithinBounds(bounds, mouseX, mouseY)) {
                selectedEditorSuggestionIndex = i;
                return acceptSelectedEditorSuggestion();
            }
        }
        return true;
    }

    private boolean isInsideEditorSuggestionPopup(double mouseX, double mouseY) {
        return isWithinBounds(editorSuggestionBounds, mouseX, mouseY);
    }

    private void setStatus(String message, int color, long durationMs) {
        saveStatusMessage = message == null ? "" : message;
        saveStatusColor = color;
        saveStatusUntil = Util.getMeasuringTimeMs() + Math.max(0L, durationMs);
    }

    private void setErrorState(String message, String detail, long durationMs) {
        lastErrorMessage = message == null ? "" : message;
        lastErrorDetail = detail == null ? "" : detail;
        setStatus(lastErrorMessage, new Color(uiColorErrorColor, true).getRGB(), durationMs);
    }

    private void clearErrorState() {
        lastErrorMessage = "";
        lastErrorDetail = "";
    }

    private boolean canMutateDocument(String action) {
        if (document == null) {
            setErrorState("No document loaded", "", 2500L);
            return false;
        }
        if (readOnlyMode) {
            setErrorState("Editor is read-only", action == null ? "" : action, 3000L);
            return false;
        }
        return true;
    }

    private void initTopBar() {
        TopBarLayout layout = getTopBarLayout();
        searchInput = new TextFieldWidget(this.textRenderer,
                layout.searchFieldX(TOP_BAR_BACK_LABEL),
                TopBarLayout.SEARCH_FIELD_Y,
                layout.searchFieldWidth(TOP_BAR_BACK_LABEL, getTopBarActionLabels(), 220),
                TopBarLayout.SEARCH_FIELD_HEIGHT,
                Text.of(""));
        searchInput.setMaxLength(512);
        searchInput.setPlaceholder(Text.literal("Search in file"));
        searchInput.setChangedListener(text -> {
            dropdownScrollOffset = 0;
            selectedDropdownIndex = -1;
            searchDropdownDismissed = false;
            updateSearchMatches();
        });
        this.addDrawableChild(searchInput);

        replaceInput = new TextFieldWidget(this.textRenderer, searchInput.getX(), searchInput.getY(), searchInput.getWidth(), TopBarLayout.SEARCH_FIELD_HEIGHT, Text.of(""));
        replaceInput.setMaxLength(512);
        replaceInput.setPlaceholder(Text.literal("Replace"));
        replaceInput.setChangedListener(text -> rebuildSearchDropdownEntries());
        replaceInput.setVisible(false);
        replaceInput.setEditable(false);
        this.addDrawableChild(replaceInput);

        updateSearchWidgetLayout();
    }

    private void updateSearchWidgetLayout() {
        if (searchInput == null) {
            return;
        }
        TopBarLayout layout = getTopBarLayout();
        searchInput.setX(layout.searchFieldX(TOP_BAR_BACK_LABEL));
        searchInput.setY(TopBarLayout.SEARCH_FIELD_Y);
        searchInput.setWidth(layout.searchFieldWidth(TOP_BAR_BACK_LABEL, getTopBarActionLabels(), 220));

        if (replaceInput != null) {
            replaceInput.setX(searchInput.getX());
            replaceInput.setY(searchInput.getY());
            replaceInput.setWidth(searchInput.getWidth());
        }
    }

    private void loadFileContent() {
        try {
            byte[] bytes = Files.readAllBytes(fileItem.toPath());
            String text = new String(bytes, StandardCharsets.UTF_8);
            List<String> lines = splitLines(text);
            this.document = new EditorDocument(lines);
            this.lastSavedText = document.getText();
            this.fileLoadFailed = false;
            this.readOnlyMode = requestedReadOnly;
            clearErrorState();
            setStatus(requestedReadOnly ? "Read only" : (bytes.length == 0 ? "Empty file" : "Loaded"), requestedReadOnly ? new Color(uiColorContentBaseDescriptionText, true).getRGB() : new Color(uiColorHeaderSubTitleText, true).getRGB(), 1200L);
        } catch (IOException e) {
            this.document = new EditorDocument(new ArrayList<>(List.of("")));
            this.lastSavedText = "";
            this.fileLoadFailed = true;
            this.readOnlyMode = true;
            setErrorState("Read error", e.getMessage(), 5000L);
        }
        this.verticalScrollOffset = 0;
        this.horizontalScrollOffset = 0;
        this.cachedLongestLineWidth = -1;
        this.undoHistory.clear();
        this.redoHistory.clear();
        EditorSyntaxHighlighter.clearCache();
        updateSearchMatches();
        rebuildLineDiagnostics();
    }

    private boolean saveFileContent() {
        if (document == null) {
            setErrorState("Save failed", "No document loaded", 4000L);
            return false;
        }
        if (readOnlyMode) {
            setErrorState("Save failed", fileLoadFailed ? "File was not loaded successfully" : "Editor is read-only", 4000L);
            return false;
        }

        try {
            String text = document.getText();
            Files.writeString(fileItem.toPath(), text, StandardCharsets.UTF_8);
            lastSavedText = text;
            clearErrorState();
            setStatus("Saved " + LocalTime.now().format(SAVE_TIME_FORMAT), SAVE_SUCCESS_COLOR, 2500L);
            return true;
        } catch (IOException exception) {
            setErrorState("Save failed", exception.getMessage(), 5000L);
            return false;
        } catch (RuntimeException exception) {
            setErrorState("Save failed", exception.getMessage(), 5000L);
            return false;
        }
    }

    private boolean insertNewLineWithIndentation() {
        if (document == null) {
            return false;
        }
        String currentLine = document.getLine(document.getCursorLine());
        String indentation = getLeadingIndentation(currentLine);
        int cursorColumn = document.getCursorColumn();
        boolean indentExtra = cursorColumn > 0 && cursorColumn <= currentLine.length() && isOpeningBracket(currentLine.charAt(cursorColumn - 1));
        String insertion = "\n" + indentation + (indentExtra ? "    " : "");
        return document.insertText(insertion);
    }

    private boolean adjustIndentation(boolean indent) {
        if (document == null) {
            return false;
        }

        EditorDocument.SelectionRange range = document.getSelectionRange();
        int startLine = range != null ? range.start().line() : document.getCursorLine();
        int endLine = range != null ? range.end().line() : document.getCursorLine();
        int originalCursorColumn = document.getCursorColumn();
        List<String> updatedLines = new ArrayList<>(document.getLines());
        boolean changed = false;

        for (int lineIndex = startLine; lineIndex <= endLine; lineIndex++) {
            String line = updatedLines.get(lineIndex);
            if (indent) {
                updatedLines.set(lineIndex, "    " + line);
                changed = true;
            } else if (line.startsWith("    ")) {
                updatedLines.set(lineIndex, line.substring(4));
                changed = true;
            } else if (line.startsWith("\t")) {
                updatedLines.set(lineIndex, line.substring(1));
                changed = true;
            }
        }

        if (!changed) {
            return false;
        }

        this.document = new EditorDocument(updatedLines);
        if (range != null) {
            int startColumn = indent ? range.start().column() + 4 : Math.max(0, range.start().column() - 4);
            int endColumn = indent ? range.end().column() + 4 : Math.max(0, range.end().column() - 4);
            this.document.selectRange(startLine, startColumn, endLine, endColumn);
        } else {
            int newColumn = indent ? originalCursorColumn + 4 : Math.max(0, originalCursorColumn - 4);
            this.document.setCursor(startLine, newColumn, false);
        }
        return true;
    }

    private boolean handleAutoClosingPair(char chr) {
        String closing = switch (chr) {
            case '(' -> ")";
            case '[' -> "]";
            case '{' -> "}";
            case '"' -> "\"";
            case '\'' -> "'";
            default -> null;
        };
        if (closing == null) {
            return false;
        }

        captureUndoSnapshot();
        if (!document.insertText(String.valueOf(chr) + closing)) {
            return false;
        }
        document.moveLeft(false);
        return true;
    }

    private String getLeadingIndentation(String line) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ' || c == '\t') {
                builder.append(c);
            } else {
                break;
            }
        }
        return builder.toString();
    }

    private boolean isOpeningBracket(char c) {
        return c == '{' || c == '[' || c == '(';
    }

    private void onDocumentMutated(String message) {
        cachedLongestLineWidth = -1;
        EditorSyntaxHighlighter.clearCache();
        ensureCursorVisible();
        resetCursorBlink();
        updateSearchMatches();
        rebuildLineDiagnostics();
        updateEditorSuggestions();
        saveStatusMessage = message;
        saveStatusColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
        saveStatusUntil = Util.getMeasuringTimeMs() + 1200L;
    }

    private void resetCursorBlink() {
        cursorVisible = true;
        lastCursorBlink = Util.getMeasuringTimeMs();
    }

    private void drawShell(DrawContext context) {
        assert client != null;
        int topBarBackground = withAlpha(uiColorContentBase, 176);
        int topPanelBackground = withAlpha(uiColorContentBase, 196);
        int panelBackground = withAlpha(uiColorContentBase, 124);
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
        context.fill(0, CONTENT_TOP, SIDEBAR_WIDTH, this.height, panelBackground);
        context.drawBorder(0, CONTENT_TOP, SIDEBAR_WIDTH, this.height - CONTENT_TOP,  new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(SIDEBAR_WIDTH + 2, CONTENT_TOP, this.width, this.height, withAlpha(uiColorContentBase, 82));
        context.drawBorder(SIDEBAR_WIDTH + 2, CONTENT_TOP, this.width - (SIDEBAR_WIDTH + 2), this.height - CONTENT_TOP,  new Color(uiColorBackgroundBorder, true).getRGB());

        renderTopBarButton(context, 10, TOP_BAR_BACK_LABEL);
        renderTopBarButton(context, getTopBarChooseButtonX(), TOP_BAR_CHOOSE_LABEL);
        topBarOpenBounds = new int[]{getTopBarChooseButtonX(), TopBarLayout.BUTTON_Y, getTopBarButtonWidth(TOP_BAR_CHOOSE_LABEL), TopBarLayout.BUTTON_HEIGHT};
        renderTopBarButton(context, getTopBarSaveButtonX(), TOP_BAR_SAVE_LABEL);
        renderTopBarButton(context, getTopBarClearButtonX(), TOP_BAR_CLEAR_LABEL);
        renderTopBarButton(context, getTopBarReloadButtonX(), TOP_BAR_RELOAD_LABEL);
    }

    private void drawSidebar(DrawContext context) {
        int startX = 10;
        int startY = 82;
        String fileName = loadedFileItem != null ? loadedFileItem.getName() : fileItem.getName();
        FileType fileType = loadedFileItem != null ? loadedFileItem.getType() : FileType.FILE;
        Identifier icon = fileType == FileType.FOLDER ? FOLDER_ICON : getFileIcon(fileName);
        String language = EditorSyntaxHighlighter.SyntaxLanguage.fromFileName(fileName).name();
        String filePath = getSidebarDisplayPath();
        int railX = 10;
        int railWidth = SIDEBAR_WIDTH - 20;
        sidebarDiagnosticMarkerBounds.clear();
        int combinedTextHeight = (int) Math.ceil((this.textRenderer.fontHeight * 1.3F) + this.textRenderer.fontHeight + this.textRenderer.fontHeight);
        int headerIconSize = Math.max(24, (int) Math.ceil(combinedTextHeight * 1.33F));
        int headerIconY = startY + Math.max(0, (combinedTextHeight - headerIconSize) / 2);
        int headerTextX = startX + headerIconSize + 10;

        context.drawTexture(icon, startX, headerIconY, 0, 0, headerIconSize, headerIconSize, headerIconSize, headerIconSize);
        context.getMatrices().push();
        context.getMatrices().translate(headerTextX, startY, 0.0F);
        context.getMatrices().scale(1.3F, 1.3F, 1.0F);
        context.drawText(this.textRenderer, fitText(fileName, Math.max(48, railWidth - 28)), 0, 0, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        context.getMatrices().pop();
        context.drawText(this.textRenderer, fitText(filePath, Math.max(48, railWidth - 28)), headerTextX, startY + 16, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, isDirty() ? "Unsaved edits" : "Synced", headerTextX, startY + 30, isDirty() ? new Color(uiColorWarningPromptText, true).getRGB() : new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);

        int rowY = startY + 64;
        context.fill(railX, rowY, railX + railWidth, rowY + 1, withAlpha(uiColorBackgroundBorder, 170));
        rowY += 6;
        rowY = renderSidebarMetricRow(context, railX, railWidth, rowY, "type", language.toLowerCase(Locale.ROOT));
        rowY = renderSidebarMetricRow(context, railX, railWidth, rowY, "save", isDirty() ? "modified" : "saved");
        rowY = renderSidebarMetricRow(context, railX, railWidth, rowY, "issues", getSidebarIssueSummary());
        rowY = renderSidebarMetricRow(context, railX, railWidth, rowY, "scope", getStructureSummary(language));
        rowY = renderSidebarMetricRow(context, railX, railWidth, rowY, "symbols", getSymbolSummary(language));
        rowY = renderSidebarMetricRow(context, railX, railWidth, rowY, "search", getSearchToolSummary());
        rowY = renderSidebarMetricRow(context, railX, railWidth, rowY, "indent", getIndentSummary());
        rowY = renderSidebarMetricRow(context, railX, railWidth, rowY, "flags", getTodoSummary());
        if (!searchErrorMessage.isEmpty()) {
            rowY = renderSidebarMetricRow(context, railX, railWidth, rowY + 2, "search", searchErrorMessage);
        }
        if (!lastErrorMessage.isEmpty()) {
            rowY = renderSidebarMetricRow(context, railX, railWidth, rowY + 2, "editor", lastErrorMessage);
        }
        int notesTop = Math.max(rowY + 6, 178);
        drawSidebarDiagnosticMap(context, railX + railWidth - 8, startY + 54, 4, Math.max(42, notesTop - (startY + 60)));
        drawSidebarNotes(context, railX, railWidth, notesTop, this.height - 12);
    }

    private int renderSidebarMetricRow(DrawContext context, int x, int width, int y, String label, String value) {
        context.drawText(this.textRenderer, humanizeSidebarLabel(label), x, y, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, fitText(value, width - 44), x + 42, y, resolveSidebarValueColor(label, value), false);
        return y + 12;
    }

    private int resolveSidebarValueColor(String label, String value) {
        String lowerLabel = label.toLowerCase(Locale.ROOT);
        String lowerValue = value.toLowerCase(Locale.ROOT);
        if (lowerLabel.equals("issues")) {
            if (lowerValue.contains("error")) {
                return new Color(uiColorIssuesError, true).getRGB();
            }
            if (lowerValue.contains("warn")) {
                return new Color(uiColorIssuesWarn, true).getRGB();
            }
        }
        if (lowerLabel.equals("save") && lowerValue.contains("modified")) {
            return new Color(uiColorSaveSuccessColor, true).getRGB();
        }
        if (lowerLabel.equals("search") && !searchErrorMessage.isEmpty()) {
            return new Color(uiColorEditorError, true).getRGB();
        }
        if (lowerLabel.equals("editor")) {
            return new Color(uiColorEditorError, true).getRGB();
        }
        if (lowerLabel.equals("scope") || lowerLabel.equals("search")) {
            return new Color(uiColorSearchError, true).getRGB();
        }
        if (lowerLabel.equals("indent") && lowerValue.contains("mixed")) {
            return new Color(uiColorIndentMixed, true).getRGB();
        }
        return new Color(uiColorContentBaseTitleText, true).getRGB();
    }

    private void drawSidebarNotes(DrawContext context, int x, int width, int y, int bottom) {
        int height = Math.max(72, bottom - y);
        sidebarNotesBounds = new int[]{x, y, width, height};
        context.fill(x, y, x + width, y + height, withAlpha(uiColorHeader, 62));
        context.drawBorder(x, y, width, height, sidebarNotesFocused ? new Color(uiColorBackgroundBorderSelected, true).getRGB() : withAlpha(uiColorBackgroundBorder, 220));

        if (sidebarNotesDocument == null) {
            return;
        }

        int textX = x + 6;
        int textY = y + 6;
        int lineHeight = this.textRenderer.fontHeight + 2;
        int visibleLines = Math.max(1, (height - 12) / lineHeight);
        int endLine = Math.min(sidebarNotesDocument.getLineCount(), sidebarNotesVerticalScrollOffset + visibleLines);

        context.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);
        EditorDocument.SelectionRange selectionRange = sidebarNotesDocument.getSelectionRange();
        if (selectionRange != null) {
            for (int lineIndex = Math.max(selectionRange.start().line(), sidebarNotesVerticalScrollOffset); lineIndex < Math.min(selectionRange.end().line() + 1, endLine); lineIndex++) {
                String line = sidebarNotesDocument.getLine(lineIndex);
                int startColumn = lineIndex == selectionRange.start().line() ? selectionRange.start().column() : 0;
                int endColumn = lineIndex == selectionRange.end().line() ? selectionRange.end().column() : line.length();
                int x1 = textX + this.textRenderer.getWidth(line.substring(0, Math.min(startColumn, line.length())));
                int x2 = textX + this.textRenderer.getWidth(line.substring(0, Math.min(endColumn, line.length())));
                int drawY = textY + ((lineIndex - sidebarNotesVerticalScrollOffset) * lineHeight) - 1;
                if (x2 > x1) {
                    context.fill(x1, drawY, x2, drawY + this.textRenderer.fontHeight + 2, withAlpha(uiColorIDECursorSelection, 120));
                }
            }
        }

        for (int lineIndex = sidebarNotesVerticalScrollOffset; lineIndex < endLine; lineIndex++) {
            int drawY = textY + ((lineIndex - sidebarNotesVerticalScrollOffset) * lineHeight);
            context.drawText(this.textRenderer, sidebarNotesDocument.getLine(lineIndex), textX, drawY, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        }
        if (sidebarNotesDocument.getText().isBlank()) {
            context.drawText(this.textRenderer, "Write notes for this file...", textX, textY, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        }
        if (sidebarNotesFocused) {
            renderSidebarNotesCaret(context, textX, textY, lineHeight);
        }
        context.disableScissor();
    }

    private void drawSidebarDiagnosticMap(DrawContext context, int x, int y, int width, int height) {
        if (document == null || lineDiagnostics.isEmpty()) {
            return;
        }
        context.fill(x, y, x + width, y + height, withAlpha(uiColorHeader, 64));
        context.drawBorder(x, y, width, height, withAlpha(uiColorBackgroundBorder, 180));
        for (int lineIndex = 0; lineIndex < lineDiagnostics.size(); lineIndex++) {
            EditorDiagnostic diagnostic = lineDiagnostics.get(lineIndex);
            if (diagnostic == null) {
                continue;
            }
            double normalized = lineDiagnostics.size() <= 1 ? 0.0 : (double) lineIndex / (double) (lineDiagnostics.size() - 1);
            int markerY = y + Math.min(height - 2, Math.max(1, (int) Math.round(normalized * (height - 2))));
            int markerHeight = diagnostic.severity() == DiagnosticSeverity.ERROR ? 2 : 1;
            int markerColor = diagnostic.severity() == DiagnosticSeverity.ERROR ? new Color(uiColorFileEditorLineErrorColor, true).getRGB() : new Color(uiColorFileEditorLineWarnColor, true).getRGB();
            context.fill(x + 1, markerY, x + width - 1, markerY + markerHeight, markerColor);
            sidebarDiagnosticMarkerBounds.add(new int[]{x, markerY - 1, width, Math.max(3, markerHeight + 1), lineIndex});
        }
    }

    private void renderSidebarNotesCaret(DrawContext context, int textX, int textY, int lineHeight) {
        int cursorLine = sidebarNotesDocument.getCursorLine();
        if (cursorLine < sidebarNotesVerticalScrollOffset) {
            return;
        }
        int visibleIndex = cursorLine - sidebarNotesVerticalScrollOffset;
        int maxVisible = Math.max(1, (sidebarNotesBounds[3] - 12) / lineHeight);
        if (visibleIndex >= maxVisible) {
            return;
        }
        String line = sidebarNotesDocument.getLine(cursorLine);
        int caretX = textX + this.textRenderer.getWidth(line.substring(0, Math.min(sidebarNotesDocument.getCursorColumn(), line.length())));
        int caretY = textY + (visibleIndex * lineHeight) - 1;
        context.fill(caretX, caretY, caretX + 1, caretY + this.textRenderer.fontHeight + 2, new Color(uiColorIDECursor, true).getRGB());
    }

    private String getSidebarIssueSummary() {
        if (cachedErrorCount > 0) {
            return cachedErrorCount + " errors" + (cachedWarningCount > 0 ? " | " + cachedWarningCount + " warnings" : "");
        }
        if (cachedWarningCount > 0) {
            return cachedWarningCount + " warnings";
        }
        return "clean";
    }

    private String humanizeSidebarLabel(String label) {
        if (label == null || label.isBlank()) {
            return "";
        }
        String normalized = label.trim().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private String getSidebarNoteKey() {
        return fileItem == null ? "" : fileItem.getAbsolutePath();
    }

    private String getSidebarDisplayPath() {
        if (fileItem == null) {
            return "";
        }
        try {
            Path root = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
            Path target = fileItem.toPath().toAbsolutePath().normalize();
            if (target.startsWith(root)) {
                Path relative = root.relativize(target);
                return relative.toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
        }
        return fileItem.getName();
    }

    private String getSidebarNoteText() {
        return SIDEBAR_NOTES.getOrDefault(getSidebarNoteKey(), "");
    }

    private int getSidebarDiagnosticLineAt(double mouseX, double mouseY) {
        for (int[] bounds : sidebarDiagnosticMarkerBounds) {
            if (mouseX >= bounds[0]
                    && mouseX <= bounds[0] + bounds[2]
                    && mouseY >= bounds[1]
                    && mouseY <= bounds[1] + bounds[3]) {
                return bounds[4];
            }
        }
        return -1;
    }

    private void loadSidebarNotes() {
        sidebarNotesDocument = new EditorDocument(splitLines(getSidebarNoteText()));
        sidebarNotesVerticalScrollOffset = 0;
        sidebarNotesFocused = false;
        sidebarNotesDragSelecting = false;
    }

    private void persistSidebarNotes() {
        if (sidebarNotesDocument == null) {
            return;
        }
        SIDEBAR_NOTES.put(getSidebarNoteKey(), sidebarNotesDocument.getText());
    }

    private boolean isInsideSidebarNotes(double mouseX, double mouseY) {
        return isWithinBounds(sidebarNotesBounds, mouseX, mouseY);
    }

    private int getSidebarNotesMaxScroll() {
        if (sidebarNotesDocument == null || sidebarNotesBounds == null) {
            return 0;
        }
        int lineHeight = this.textRenderer.fontHeight + 2;
        int visibleLines = Math.max(1, (sidebarNotesBounds[3] - 12) / lineHeight);
        return Math.max(0, sidebarNotesDocument.getLineCount() - visibleLines);
    }

    private void ensureSidebarNotesCursorVisible() {
        if (sidebarNotesDocument == null || sidebarNotesBounds == null) {
            return;
        }
        int lineHeight = this.textRenderer.fontHeight + 2;
        int visibleLines = Math.max(1, (sidebarNotesBounds[3] - 12) / lineHeight);
        if (sidebarNotesDocument.getCursorLine() < sidebarNotesVerticalScrollOffset) {
            sidebarNotesVerticalScrollOffset = sidebarNotesDocument.getCursorLine();
        } else if (sidebarNotesDocument.getCursorLine() >= sidebarNotesVerticalScrollOffset + visibleLines) {
            sidebarNotesVerticalScrollOffset = sidebarNotesDocument.getCursorLine() - visibleLines + 1;
        }
        sidebarNotesVerticalScrollOffset = clamp(sidebarNotesVerticalScrollOffset, 0, getSidebarNotesMaxScroll());
    }

    private SidebarNotePoint resolveSidebarNotesPoint(double mouseX, double mouseY) {
        if (sidebarNotesDocument == null || sidebarNotesBounds == null) {
            return new SidebarNotePoint(0, 0);
        }
        int lineHeight = this.textRenderer.fontHeight + 2;
        int relativeLine = Math.max(0, (int) ((mouseY - (sidebarNotesBounds[1] + 6)) / lineHeight));
        int lineIndex = clamp(sidebarNotesVerticalScrollOffset + relativeLine, 0, sidebarNotesDocument.getLineCount() - 1);
        String line = sidebarNotesDocument.getLine(lineIndex);
        int localX = (int) mouseX - (sidebarNotesBounds[0] + 6);
        int column = 0;
        for (int i = 1; i <= line.length(); i++) {
            int width = this.textRenderer.getWidth(line.substring(0, i));
            if (localX < width) {
                break;
            }
            column = i;
        }
        return new SidebarNotePoint(lineIndex, column);
    }

    private void selectSidebarNotesWord(int lineIndex, int column) {
        if (sidebarNotesDocument == null) {
            return;
        }
        String line = sidebarNotesDocument.getLine(lineIndex);
        if (line.isEmpty()) {
            sidebarNotesDocument.setCursor(lineIndex, 0, false);
            return;
        }
        int probe = clamp(column, 0, line.length());
        if (probe == line.length() && probe > 0) {
            probe--;
        }
        if (probe < 0 || probe >= line.length() || !isWordChar(line.charAt(probe))) {
            sidebarNotesDocument.setCursor(lineIndex, clamp(column, 0, line.length()), false);
            return;
        }
        int left = probe;
        int right = probe + 1;
        while (left > 0 && isWordChar(line.charAt(left - 1))) {
            left--;
        }
        while (right < line.length() && isWordChar(line.charAt(right))) {
            right++;
        }
        sidebarNotesDocument.selectRange(lineIndex, left, lineIndex, right);
    }

    private boolean handleSidebarNotesAutoClosingPair(char chr) {
        if (sidebarNotesDocument == null) {
            return false;
        }
        String closing = switch (chr) {
            case '(' -> ")";
            case '[' -> "]";
            case '{' -> "}";
            case '"' -> "\"";
            case '\'' -> "'";
            default -> null;
        };
        if (closing == null) {
            return false;
        }
        if (!sidebarNotesDocument.insertText(String.valueOf(chr) + closing)) {
            return false;
        }
        sidebarNotesDocument.moveLeft(false);
        return true;
    }

    private boolean handleSidebarNotesKeyPressed(int keyCode, boolean shiftPressed) {
        if (sidebarNotesDocument == null) {
            return false;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                sidebarNotesFocused = false;
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                sidebarNotesDocument.moveLeft(shiftPressed);
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                sidebarNotesDocument.moveRight(shiftPressed);
            }
            case GLFW.GLFW_KEY_UP -> {
                sidebarNotesDocument.moveUp(shiftPressed);
            }
            case GLFW.GLFW_KEY_DOWN -> {
                sidebarNotesDocument.moveDown(shiftPressed);
            }
            case GLFW.GLFW_KEY_HOME -> {
                sidebarNotesDocument.moveHome(shiftPressed);
            }
            case GLFW.GLFW_KEY_END -> {
                sidebarNotesDocument.moveEnd(shiftPressed);
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                sidebarNotesDocument.insertNewLine();
                persistSidebarNotes();
                ensureSidebarNotesCursorVisible();
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (sidebarNotesDocument.backspace()) {
                    persistSidebarNotes();
                    ensureSidebarNotesCursorVisible();
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (sidebarNotesDocument.deleteForward()) {
                    persistSidebarNotes();
                    ensureSidebarNotesCursorVisible();
                }
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                sidebarNotesDocument.insertText("    ");
                persistSidebarNotes();
                ensureSidebarNotesCursorVisible();
                return true;
            }
            default -> {
                return false;
            }
        }
        ensureSidebarNotesCursorVisible();
        return true;
    }

    private void drawEditorChrome(DrawContext context) {
        int editorX = getEditorAreaX();
        int editorY = CONTENT_TOP;
        int editorWidth = this.width - editorX - 1;
        int headerColor = withAlpha(uiColorHeader, 168);
        String fileName = loadedFileItem != null ? loadedFileItem.getName() : fileItem.getName();
        String typeLabel = getFileTypeLabel(loadedFileItem != null ? loadedFileItem.getType() : FileType.FILE, fileItem);
        String fileSize = getFileSize(fileItem);

        context.fill(editorX, editorY, editorX + editorWidth, editorY + EDITOR_HEADER_HEIGHT, headerColor);
        context.drawBorder(editorX, editorY, editorWidth, EDITOR_HEADER_HEIGHT, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawText(this.textRenderer, fileName, editorX + PADDING, editorY + 6, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, typeLabel, editorX + PADDING, editorY + 18, new Color(uiColorIDEFileTypeText, true).getRGB(), false);
        context.drawText(this.textRenderer, fileSize, editorX + PADDING, editorY + 29, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);

        if (isDirty()) {
            int dotX = editorX + editorWidth - 16;
            int dotY = editorY + 17;
            context.fill(dotX, dotY, dotX + 6, dotY + 6, new Color(uiColorWarningPromptText, true).getRGB());
        }
    }

    private void drawEditorViewport(DrawContext context, int mouseX, int mouseY) {
        int viewportX = getEditorAreaX();
        int viewportY = CONTENT_TOP + EDITOR_HEADER_HEIGHT;
        int viewportWidth = this.width - viewportX - 1;
        int viewportHeight = this.height - viewportY - STATUS_BAR_HEIGHT - 1;
        int gutterWidth = getGutterWidth();
        int textStartX = viewportX + gutterWidth + 10;
        int lineHeight = this.textRenderer.fontHeight + 2;
        int visibleLineCount = getVisibleLineCount();
        int endLine = Math.min(document.getLineCount(), verticalScrollOffset + visibleLineCount);
        int currentLineFill = withAlpha(uiColorHeader, 100);
        int errorLineFill = withAlpha(uiColorFileEditorLineErrorColor, 62);
        int warningLineFill = withAlpha(uiColorFileEditorLineWarnColor, 56);
        int selectionColor = withAlpha(uiColorIDECursorSelection, 130);
        int gutterTextColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
        int activeGutterTextColor = new Color(uiColorContentBaseTitleText, true).getRGB();
        int hoveredMalformedLine = -1;

        context.fill(viewportX, viewportY, viewportX + viewportWidth, viewportY + viewportHeight, withAlpha(uiColorContentBase, 150));
        context.drawBorder(viewportX, viewportY, viewportWidth, viewportHeight, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(viewportX, viewportY, viewportX + gutterWidth, viewportY + viewportHeight, withAlpha(uiColorHeader, 118));
        context.drawVerticalLine(viewportX + gutterWidth, viewportY, viewportY + viewportHeight, new Color(uiColorBackgroundBorder, true).getRGB());

        for (int lineIndex = verticalScrollOffset; lineIndex < endLine; lineIndex++) {
            int visibleIndex = lineIndex - verticalScrollOffset;
            int drawY = viewportY + 6 + (visibleIndex * lineHeight);
            EditorDiagnostic diagnostic = getLineDiagnostic(lineIndex);
            int lineNumberX = viewportX + gutterWidth - 8 - this.textRenderer.getWidth(String.valueOf(lineIndex + 1));
            context.drawText(this.textRenderer, String.valueOf(lineIndex + 1), lineNumberX, drawY, lineIndex == document.getCursorLine() ? activeGutterTextColor : gutterTextColor, false);
            if (diagnostic != null) {
                int markerY = drawY - 2;
                int markerX1 = viewportX + 6;
                int markerX2 = viewportX + 8;
                int markerColor = diagnostic.severity() == DiagnosticSeverity.ERROR
                        ? new Color(uiColorFileEditorLineErrorColor, true).getRGB()
                        : new Color(uiColorFileEditorLineWarnColor, true).getRGB();
                context.fill(markerX1, markerY, markerX2, markerY + this.textRenderer.fontHeight + 2, markerColor);
                if (mouseX >= markerX1 && mouseX <= markerX2 && mouseY >= markerY && mouseY <= markerY + this.textRenderer.fontHeight + 2) {
                    hoveredMalformedLine = lineIndex;
                }
            }
        }

        if (endLine < document.getLineCount()) {
            String overflowMarker = "...";
            int markerX = viewportX + gutterWidth - 8 - this.textRenderer.getWidth(overflowMarker);
            int markerY = viewportY + viewportHeight - this.textRenderer.fontHeight - 4;
            context.drawText(this.textRenderer, overflowMarker, markerX, markerY, gutterTextColor, false);
        }

        context.enableScissor(textStartX, viewportY + 1, viewportX + viewportWidth - 1, viewportY + viewportHeight - 1);

        for (int lineIndex = verticalScrollOffset; lineIndex < endLine; lineIndex++) {
            int visibleIndex = lineIndex - verticalScrollOffset;
            int drawY = viewportY + 6 + (visibleIndex * lineHeight);
            EditorDiagnostic diagnostic = getLineDiagnostic(lineIndex);
            if (diagnostic != null) {
                int fillColor = diagnostic.severity() == DiagnosticSeverity.ERROR
                        ? errorLineFill
                        : warningLineFill;
                context.fill(viewportX + 1, drawY - 2, viewportX + viewportWidth - 1, drawY + this.textRenderer.fontHeight, fillColor);
                int underlineColor = diagnostic.severity() == DiagnosticSeverity.ERROR ? new Color(uiColorFileEditorLineErrorColor, true).getRGB() : new Color(uiColorFileEditorLineWarnColor, true).getRGB();
                context.fill(viewportX + 1, drawY + this.textRenderer.fontHeight, viewportX + viewportWidth - 1, drawY + this.textRenderer.fontHeight + 1, underlineColor);
            }
            if (lineIndex == document.getCursorLine()) {
                context.fill(viewportX + 1, drawY - 2, viewportX + viewportWidth - 1, drawY + this.textRenderer.fontHeight, currentLineFill);
            }
        }

        renderSelection(context, textStartX, viewportY + 6, lineHeight, selectionColor);
        renderCurrentWordHighlights(context, textStartX, viewportY + 6, lineHeight);
        renderSearchMatches(context, textStartX, viewportY + 6, lineHeight);

        for (int lineIndex = verticalScrollOffset; lineIndex < endLine; lineIndex++) {
            int visibleIndex = lineIndex - verticalScrollOffset;
            int drawY = viewportY + 6 + (visibleIndex * lineHeight);
            renderHighlightedLine(context, document.getLine(lineIndex), textStartX, drawY);
        }

        renderCaret(context, textStartX, viewportY + 6, lineHeight);
        context.disableScissor();
        renderEditorSuggestions(context, textStartX, viewportY + 6, lineHeight, mouseX, mouseY);
        renderEditorScrollbar(context, viewportX, viewportY, viewportWidth, viewportHeight);

        if (hoveredMalformedLine >= 0) {
            EditorDiagnostic diagnostic = getLineDiagnostic(hoveredMalformedLine);
            if (diagnostic != null) {
                context.drawTooltip(this.textRenderer, buildDiagnosticTooltip(hoveredMalformedLine, diagnostic), Optional.empty(), mouseX, mouseY);
            }
        }
    }

    private List<Text> buildDiagnosticTooltip(int lineIndex, EditorDiagnostic diagnostic) {
        List<Text> lines = new ArrayList<>();
        String severityLabel = diagnostic.severity() == DiagnosticSeverity.ERROR ? "Error" : "Warning";
        int severityColor = diagnostic.severity() == DiagnosticSeverity.ERROR ? TOOLTIP_ERROR_COLOR : TOOLTIP_WARNING_COLOR;
        lines.add(
                Text.literal(severityLabel).setStyle(Style.EMPTY.withColor(severityColor).withBold(true))
                        .append(Text.literal("  ").setStyle(Style.EMPTY.withColor(TOOLTIP_LABEL_COLOR)))
                        .append(Text.literal("Line " + (lineIndex + 1)).setStyle(Style.EMPTY.withColor(TOOLTIP_LABEL_COLOR)))
        );
        lines.add(
                Text.literal("Summary: ").setStyle(Style.EMPTY.withColor(TOOLTIP_LABEL_COLOR))
                        .append(Text.literal(diagnostic.summary()).setStyle(Style.EMPTY.withColor(TOOLTIP_PRIMARY_COLOR)))
        );
        if (!diagnostic.detail().isBlank()) {
            lines.add(
                    Text.literal("Detail: ").setStyle(Style.EMPTY.withColor(TOOLTIP_LABEL_COLOR))
                            .append(Text.literal(diagnostic.detail()).setStyle(Style.EMPTY.withColor(TOOLTIP_SECONDARY_COLOR)))
            );
        }
        if (!diagnostic.hint().isBlank()) {
            lines.add(
                    Text.literal("Idea: ").setStyle(Style.EMPTY.withColor(TOOLTIP_IDEA_COLOR))
                            .append(Text.literal(diagnostic.hint()).setStyle(Style.EMPTY.withColor(TOOLTIP_SECONDARY_COLOR)))
            );
        }
        if (diagnostic.quickClick() != null) {
            lines.add(
                    Text.literal("Quick Click: ").setStyle(Style.EMPTY.withColor(TOOLTIP_FIX_COLOR))
                            .append(Text.literal(diagnostic.quickClick().label()).setStyle(Style.EMPTY.withColor(TOOLTIP_PRIMARY_COLOR)))
            );
        }
        return lines;
    }

    private void drawStatusBar(DrawContext context) {
        int barX = getEditorAreaX();
        int barY = this.height - STATUS_BAR_HEIGHT;
        int barWidth = this.width - barX - 1;
        long now = Util.getMeasuringTimeMs();
        String stateMessage = now <= saveStatusUntil ? saveStatusMessage : (readOnlyMode ? "Read only" : (isDirty() ? "Unsaved changes" : "Saved"));
        int stateColor = now <= saveStatusUntil ? saveStatusColor : (readOnlyMode ? new Color(uiColorContentBaseDescriptionText, true).getRGB() : (isDirty() ? new Color(uiColorWarningPromptText, true).getRGB() : new Color(uiColorHeaderSubTitleText, true).getRGB()));
        String position = "Ln " + (document.getCursorLine() + 1) + ", Col " + (document.getCursorColumn() + 1);
        String searchInfo = searchMatches.isEmpty() ? "Search 0" : "Search " + (activeSearchMatchIndex + 1) + "/" + searchMatches.size();
        String diagnosticInfo = cachedErrorCount > 0
                ? cachedErrorCount + " errors" + (cachedWarningCount > 0 ? " | " + cachedWarningCount + " warnings" : "")
                : (cachedWarningCount > 0 ? cachedWarningCount + " warnings" : "No issues");

        context.fill(barX, barY, barX + barWidth, this.height, withAlpha(uiColorHeader, 150));
        context.drawBorder(barX, barY, barWidth, STATUS_BAR_HEIGHT, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawText(this.textRenderer, position, barX + PADDING, barY + 6, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, stateMessage, barX + 100, barY + 6, stateColor, false);
        int searchX = barX + barWidth - this.textRenderer.getWidth(searchInfo) - PADDING;
        int diagnosticX = searchX - this.textRenderer.getWidth(diagnosticInfo) - 14;
        context.drawText(this.textRenderer, diagnosticInfo, diagnosticX, barY + 6, cachedErrorCount > 0 ? new Color(uiColorWarningPromptText, true).getRGB() : new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
        context.drawText(this.textRenderer, searchInfo, searchX, barY + 6, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
    }

    private void renderHighlightedLine(DrawContext context, String line, int startX, int y) {
        int drawX = startX - horizontalScrollOffset;
        for (EditorSyntaxHighlighter.StyledSpan span : EditorSyntaxHighlighter.highlight(fileItem.getName(), line)) {
            if (!span.text().isEmpty()) {
                context.drawText(this.textRenderer, span.text(), drawX, y, span.color(), false);
                drawX += this.textRenderer.getWidth(span.text());
            }
        }
    }

    private void renderSelection(DrawContext context, int textStartX, int viewportTextStartY, int lineHeight, int selectionColor) {
        EditorDocument.SelectionRange range = document.getSelectionRange();
        if (range == null) {
            return;
        }

        int visibleStart = verticalScrollOffset;
        int visibleEnd = Math.min(document.getLineCount(), verticalScrollOffset + getVisibleLineCount());
        for (int lineIndex = Math.max(range.start().line(), visibleStart); lineIndex < Math.min(range.end().line() + 1, visibleEnd); lineIndex++) {
            String line = document.getLine(lineIndex);
            int startColumn = lineIndex == range.start().line() ? range.start().column() : 0;
            int endColumn = lineIndex == range.end().line() ? range.end().column() : line.length();
            int x1 = textStartX + this.textRenderer.getWidth(line.substring(0, Math.min(startColumn, line.length()))) - horizontalScrollOffset;
            int x2 = textStartX + this.textRenderer.getWidth(line.substring(0, Math.min(endColumn, line.length()))) - horizontalScrollOffset;
            int visibleIndex = lineIndex - verticalScrollOffset;
            int drawY = viewportTextStartY + (visibleIndex * lineHeight) - 2;
            if (x2 > x1) {
                context.fill(x1, drawY, x2, drawY + this.textRenderer.fontHeight + 2, selectionColor);
            }
        }
    }

    private void renderCurrentWordHighlights(DrawContext context, int textStartX, int viewportTextStartY, int lineHeight) {
        if (document == null || document.hasSelection()) {
            return;
        }
        String targetWord = getCurrentWord();
        if (targetWord.isBlank() || targetWord.length() < 2) {
            return;
        }

        int highlightColor = withAlpha(uiColorHeader, 78);
        int visibleStart = verticalScrollOffset;
        int visibleEnd = Math.min(document.getLineCount(), verticalScrollOffset + getVisibleLineCount());
        for (int lineIndex = visibleStart; lineIndex < visibleEnd; lineIndex++) {
            String line = document.getLine(lineIndex);
            int searchFrom = 0;
            while (searchFrom < line.length()) {
                int matchIndex = line.indexOf(targetWord, searchFrom);
                if (matchIndex < 0) {
                    break;
                }
                int matchEnd = matchIndex + targetWord.length();
                boolean startBoundary = matchIndex == 0 || !isWordChar(line.charAt(matchIndex - 1));
                boolean endBoundary = matchEnd == line.length() || !isWordChar(line.charAt(matchEnd));
                if (startBoundary && endBoundary && !isCursorInsideMatch(lineIndex, matchIndex, matchEnd)) {
                    int x1 = textStartX + this.textRenderer.getWidth(line.substring(0, matchIndex)) - horizontalScrollOffset;
                    int x2 = textStartX + this.textRenderer.getWidth(line.substring(0, matchEnd)) - horizontalScrollOffset;
                    int visibleIndex = lineIndex - verticalScrollOffset;
                    int drawY = viewportTextStartY + (visibleIndex * lineHeight) - 2;
                    if (x2 > x1) {
                        context.fill(x1, drawY, x2, drawY + this.textRenderer.fontHeight + 2, highlightColor);
                    }
                }
                searchFrom = matchEnd;
            }
        }
    }

    private void renderSearchMatches(DrawContext context, int textStartX, int viewportTextStartY, int lineHeight) {
        if (searchMatches.isEmpty()) {
            return;
        }

        int activeColor = withAlpha(uiColorWarningPromptText, 150);
        int passiveColor = withAlpha(uiColorIDECursorSelection, 95);
        int visibleStart = verticalScrollOffset;
        int visibleEnd = Math.min(document.getLineCount(), verticalScrollOffset + getVisibleLineCount());

        for (int i = 0; i < searchMatches.size(); i++) {
            SearchMatch match = searchMatches.get(i);
            int startLine = Math.max(match.startLine(), visibleStart);
            int endLine = Math.min(match.endLine(), visibleEnd - 1);
            for (int lineIndex = startLine; lineIndex <= endLine; lineIndex++) {
                String line = document.getLine(lineIndex);
                int startColumn = lineIndex == match.startLine() ? match.startColumn() : 0;
                int endColumn = lineIndex == match.endLine() ? match.endColumn() : line.length();
                int x1 = textStartX + this.textRenderer.getWidth(line.substring(0, Math.min(startColumn, line.length()))) - horizontalScrollOffset;
                int x2 = textStartX + this.textRenderer.getWidth(line.substring(0, Math.min(endColumn, line.length()))) - horizontalScrollOffset;
                int visibleIndex = lineIndex - verticalScrollOffset;
                int drawY = viewportTextStartY + (visibleIndex * lineHeight) - 2;
                if (x2 > x1) {
                    context.fill(x1, drawY, x2, drawY + this.textRenderer.fontHeight + 2, i == activeSearchMatchIndex ? activeColor : passiveColor);
                }
            }
        }
    }

    private void renderCaret(DrawContext context, int textStartX, int viewportTextStartY, int lineHeight) {
        if (!cursorVisible) {
            return;
        }
        int cursorLine = document.getCursorLine();
        if (cursorLine < verticalScrollOffset || cursorLine >= verticalScrollOffset + getVisibleLineCount()) {
            return;
        }

        String line = document.getLine(cursorLine);
        int caretX = textStartX + this.textRenderer.getWidth(line.substring(0, Math.min(document.getCursorColumn(), line.length()))) - horizontalScrollOffset;
        int drawY = viewportTextStartY + ((cursorLine - verticalScrollOffset) * lineHeight) - 3;
        int caretColor = new Color(uiColorIDECursor, true).getRGB();
        context.fill(caretX, drawY, caretX + 1, drawY + this.textRenderer.fontHeight + 2, caretColor);
    }

    private void renderEditorSuggestions(DrawContext context, int textStartX, int viewportTextStartY, int lineHeight, int mouseX, int mouseY) {
        editorSuggestionBounds = null;
        editorSuggestionRowBounds.clear();
        if (!isEditorSuggestionVisible()) {
            return;
        }
        int cursorLine = document.getCursorLine();
        if (cursorLine < verticalScrollOffset || cursorLine >= verticalScrollOffset + getVisibleLineCount()) {
            return;
        }
        String line = document.getLine(cursorLine);
        int caretX = textStartX + this.textRenderer.getWidth(line.substring(0, Math.min(document.getCursorColumn(), line.length()))) - horizontalScrollOffset;
        int baseY = viewportTextStartY + ((cursorLine - verticalScrollOffset) * lineHeight) + this.textRenderer.fontHeight + 2;
        String prefix = getCurrentTokenPrefix();
        EditorSuggestion selected = editorSuggestions.get(selectedEditorSuggestionIndex);
        String inlineTail = selected.value().startsWith(prefix) ? selected.value().substring(prefix.length()) : "";
        if (!inlineTail.isEmpty()) {
            context.drawText(this.textRenderer, inlineTail, caretX + 2, baseY - lineHeight + 1, withAlpha(uiColorHeaderSubTitleText, 160), false);
        }

        int valueWidth = 0;
        int detailWidth = 0;
        for (EditorSuggestion suggestion : editorSuggestions) {
            valueWidth = Math.max(valueWidth, this.textRenderer.getWidth(suggestion.value()));
            detailWidth = Math.max(detailWidth, this.textRenderer.getWidth(suggestion.detail()));
        }
        int width = Math.max(196, Math.min(356, 18 + 58 + valueWidth + (detailWidth > 0 ? Math.min(118, detailWidth) + 10 : 0)));
        int x = Math.max(getEditorAreaX() + 8, Math.min(caretX, this.width - width - 8));
        int y = Math.max(CONTENT_TOP + EDITOR_HEADER_HEIGHT + 4, Math.min(baseY + 2, this.height - ((editorSuggestions.size() * EDITOR_SUGGESTION_ROW_HEIGHT) + 18)));
        int height = 6 + (editorSuggestions.size() * EDITOR_SUGGESTION_ROW_HEIGHT) + 6;
        editorSuggestionBounds = new int[]{x, y, width, height};
        context.fill(x, y, x + width, y + height, withAlpha(uiColorContentBase, 234));
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());
        for (int i = 0; i < editorSuggestions.size(); i++) {
            int rowY = y + 4 + (i * EDITOR_SUGGESTION_ROW_HEIGHT);
            editorSuggestionRowBounds.add(new int[]{x + 1, rowY, width - 2, EDITOR_SUGGESTION_ROW_HEIGHT - 1});
            EditorSuggestion suggestion = editorSuggestions.get(i);
            boolean hovered = mouseX >= x + 1 && mouseX <= x + width - 1 && mouseY >= rowY && mouseY <= rowY + EDITOR_SUGGESTION_ROW_HEIGHT - 1;
            if (i == selectedEditorSuggestionIndex) {
                context.fill(x + 1, rowY, x + width - 1, rowY + EDITOR_SUGGESTION_ROW_HEIGHT - 1, withAlpha(uiColorHeader, 144));
            } else if (hovered) {
                context.fill(x + 1, rowY, x + width - 1, rowY + EDITOR_SUGGESTION_ROW_HEIGHT - 1, withAlpha(uiColorHeader, 96));
            }
            int kindX = x + EDITOR_SUGGESTION_PADDING;
            int valueX = x + 58;
            int detailWidthClamped = Math.min(118, this.textRenderer.getWidth(suggestion.detail()));
            int detailX = x + width - 8 - detailWidthClamped;
            context.drawText(this.textRenderer, suggestion.kind().label(), kindX, rowY + 4, suggestion.kind().color(), false);
            context.drawText(this.textRenderer, fitText(suggestion.value(), Math.max(56, detailX - valueX - 8)), valueX, rowY + 4, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            if (!suggestion.detail().isBlank()) {
                context.drawText(this.textRenderer, fitText(suggestion.detail(), 116), detailX, rowY + 4, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            }
        }
    }

    private String getCurrentWord() {
        if (document == null) {
            return "";
        }
        String line = document.getLine(document.getCursorLine());
        if (line.isEmpty()) {
            return "";
        }
        int cursorColumn = clamp(document.getCursorColumn(), 0, line.length());
        int probe = cursorColumn;
        if (probe == line.length() && probe > 0) {
            probe--;
        }
        if (probe < 0 || probe >= line.length() || !isWordChar(line.charAt(probe))) {
            return "";
        }
        int start = probe;
        int end = probe + 1;
        while (start > 0 && isWordChar(line.charAt(start - 1))) {
            start--;
        }
        while (end < line.length() && isWordChar(line.charAt(end))) {
            end++;
        }
        return line.substring(start, end);
    }

    private String getCurrentTokenPrefix() {
        if (document == null || document.hasSelection()) {
            return "";
        }
        String line = document.getLine(document.getCursorLine());
        if (line.isEmpty()) {
            return "";
        }
        int cursorColumn = clamp(document.getCursorColumn(), 0, line.length());
        int start = cursorColumn;
        while (start > 0 && isWordChar(line.charAt(start - 1))) {
            start--;
        }
        if (start == cursorColumn) {
            return "";
        }
        return line.substring(start, cursorColumn);
    }

    private boolean isCursorInsideMatch(int lineIndex, int startColumn, int endColumn) {
        return document.getCursorLine() == lineIndex
                && document.getCursorColumn() >= startColumn
                && document.getCursorColumn() <= endColumn;
    }

    private void updateEditorSuggestions() {
        editorSuggestions.clear();
        editorSuggestionBounds = null;
        editorSuggestionRowBounds.clear();
        selectedEditorSuggestionIndex = 0;
        if (document == null || editorSuggestionDismissed || document.hasSelection()) {
            return;
        }
        String prefix = getCurrentTokenPrefix();
        if (prefix.isBlank() || prefix.length() < 1) {
            return;
        }
        editorSuggestions.addAll(buildEditorSuggestions(prefix));
    }

    private List<EditorSuggestion> buildEditorSuggestions(String prefix) {
        if (document == null || prefix == null || prefix.isBlank()) {
            return Collections.emptyList();
        }
        Map<String, EditorSuggestion> values = new HashMap<>();
        String lowerPrefix = prefix.toLowerCase();
        EditorSyntaxHighlighter.SyntaxLanguage language = EditorSyntaxHighlighter.SyntaxLanguage.fromFileName(fileItem.getName());
        addStructuredContextSuggestions(values, prefix, lowerPrefix, language);
        addCodeStructureSuggestions(values, prefix, lowerPrefix, language);
        for (String keyword : getLanguageSuggestionWords(language)) {
            maybeAddSuggestion(values, prefix, lowerPrefix, keyword, EditorSuggestionKind.KEYWORD, language.name().toLowerCase(Locale.ROOT), 900);
        }
        for (String literal : getLanguageLiteralSuggestions(language)) {
            maybeAddSuggestion(values, prefix, lowerPrefix, literal, EditorSuggestionKind.LITERAL, "common", 850);
        }
        for (String line : document.getLines()) {
            for (String token : line.split("[^A-Za-z0-9_\\-]+")) {
                maybeAddSuggestion(values, prefix, lowerPrefix, token, EditorSuggestionKind.SYMBOL, "file", 700);
            }
        }
        List<EditorSuggestion> ordered = new ArrayList<>(values.values());
        ordered.sort((left, right) -> {
            int scoreCompare = Integer.compare(right.score(), left.score());
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            int lengthCompare = Integer.compare(left.value().length(), right.value().length());
            if (lengthCompare != 0) {
                return lengthCompare;
            }
            return left.value().compareToIgnoreCase(right.value());
        });
        if (ordered.size() > MAX_EDITOR_SUGGESTIONS) {
            return new ArrayList<>(ordered.subList(0, MAX_EDITOR_SUGGESTIONS));
        }
        return ordered;
    }

    private void addCodeStructureSuggestions(Map<String, EditorSuggestion> values, String prefix, String lowerPrefix, EditorSyntaxHighlighter.SyntaxLanguage language) {
        if (document == null) {
            return;
        }
        switch (language) {
            case JAVA -> {
                addRegexSuggestions(values, prefix, lowerPrefix, "(?m)\\b(?:class|interface|enum|record)\\s+([A-Za-z_][A-Za-z0-9_]*)", 1, EditorSuggestionKind.CLASS, "java type", 1025);
                addRegexSuggestions(values, prefix, lowerPrefix, "(?m)^\\s*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default)\\s+)*(?:[A-Za-z_][A-Za-z0-9_<>\\[\\],.?\\s]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(", 1, EditorSuggestionKind.METHOD, "java method", 1005);
                addRegexSuggestions(values, prefix, lowerPrefix, "(?m)^\\s*(?:(?:public|private|protected|static|final|volatile|transient)\\s+)*(?:[A-Za-z_][A-Za-z0-9_<>\\[\\],.?\\s]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=|;)", 1, EditorSuggestionKind.FIELD, "java field", 965);
                addRegexSuggestions(values, prefix, lowerPrefix, "(?m)^\\s*import\\s+([A-Za-z0-9_.*]+);", 1, EditorSuggestionKind.PATH, "java import", 930);
            }
            case JAVASCRIPT -> {
                addRegexSuggestions(values, prefix, lowerPrefix, "(?m)\\bclass\\s+([A-Za-z_$][A-Za-z0-9_$]*)", 1, EditorSuggestionKind.CLASS, "js class", 1020);
                addRegexSuggestions(values, prefix, lowerPrefix, "(?m)\\bfunction\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(", 1, EditorSuggestionKind.METHOD, "js function", 1000);
                addRegexSuggestions(values, prefix, lowerPrefix, "(?m)\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(?:async\\s+)?(?:\\([^\\)]*\\)|[A-Za-z_$][A-Za-z0-9_$]*)\\s*=>", 1, EditorSuggestionKind.METHOD, "js lambda", 995);
                addRegexSuggestions(values, prefix, lowerPrefix, "(?m)\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:=|;)", 1, EditorSuggestionKind.FIELD, "js symbol", 955);
            }
            case SHADER -> {
                addRegexSuggestions(values, prefix, lowerPrefix, "(?m)^\\s*(?:uniform|varying|attribute|in|out)\\s+[A-Za-z_][A-Za-z0-9_]*\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;", 1, EditorSuggestionKind.FIELD, "shader binding", 995);
                addRegexSuggestions(values, prefix, lowerPrefix, "(?m)^\\s*(?:[A-Za-z_][A-Za-z0-9_]*\\s+)+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(", 1, EditorSuggestionKind.METHOD, "shader fn", 985);
            }
            case CSS -> {
                addRegexSuggestions(values, prefix, lowerPrefix, "(?m)^\\s*([^@\\s][^\\{]+)\\{", 1, EditorSuggestionKind.SELECTOR, "css selector", 980);
                addRegexSuggestions(values, prefix, lowerPrefix, "(?m)^\\s*(--[A-Za-z0-9_\\-]+)\\s*:", 1, EditorSuggestionKind.PROPERTY, "css var", 990);
            }
            default -> {
            }
        }
    }

    private void addStructuredContextSuggestions(Map<String, EditorSuggestion> values, String prefix, String lowerPrefix, EditorSyntaxHighlighter.SyntaxLanguage language) {
        if (document == null) {
            return;
        }
        String line = document.getLine(document.getCursorLine());
        int cursorColumn = clamp(document.getCursorColumn(), 0, line.length());
        String beforeCursor = line.substring(0, cursorColumn);
        switch (language) {
            case JSON -> {
                if (isJsonKeySuggestionContext(beforeCursor)) {
                    for (String key : collectStructuredSuggestions("\"([^\"]+)\"\\s*:", 1)) {
                        maybeAddSuggestion(values, prefix, lowerPrefix, key, EditorSuggestionKind.SYMBOL, "json key", 995);
                    }
                }
                if (isJsonValueSuggestionContext(beforeCursor)) {
                    for (String literal : List.of("true", "false", "null", "\"\"", "[]", "{}")) {
                        maybeAddSuggestion(values, prefix, lowerPrefix, literal, EditorSuggestionKind.LITERAL, "json value", 980);
                    }
                }
            }
            case YAML -> {
                if (isYamlKeySuggestionContext(beforeCursor)) {
                    for (String key : collectStructuredSuggestions("(?m)^\\s*([A-Za-z0-9_\\-]+):", 1)) {
                        maybeAddSuggestion(values, prefix, lowerPrefix, key, EditorSuggestionKind.SYMBOL, "yaml key", 990);
                    }
                }
                if (isKeyValueValueContext(beforeCursor, ':')) {
                    for (String literal : List.of("true", "false", "null", "yes", "no", "\"\"", "[]", "{}")) {
                        maybeAddSuggestion(values, prefix, lowerPrefix, literal, EditorSuggestionKind.LITERAL, "yaml value", 975);
                    }
                }
            }
            case TOML -> {
                if (isTomlKeySuggestionContext(beforeCursor)) {
                    for (String key : collectStructuredSuggestions("(?m)^\\s*([A-Za-z0-9_\\-\\.]+)\\s*=", 1)) {
                        maybeAddSuggestion(values, prefix, lowerPrefix, key, EditorSuggestionKind.SYMBOL, "toml key", 990);
                    }
                }
                if (isKeyValueValueContext(beforeCursor, '=')) {
                    for (String literal : List.of("true", "false", "\"\"", "[]", "{}", "0", "1")) {
                        maybeAddSuggestion(values, prefix, lowerPrefix, literal, EditorSuggestionKind.LITERAL, "toml value", 975);
                    }
                }
            }
            case PROPERTIES, CONFIG -> {
                if (isPropertyKeySuggestionContext(beforeCursor)) {
                    for (String key : collectStructuredSuggestions("(?m)^\\s*([^#!:=\\s][^:=]*)\\s*[:=]", 1)) {
                        maybeAddSuggestion(values, prefix, lowerPrefix, key.trim(), EditorSuggestionKind.SYMBOL, "key", 990);
                    }
                }
                if (isPropertyValueSuggestionContext(beforeCursor)) {
                    for (String literal : List.of("true", "false")) {
                        maybeAddSuggestion(values, prefix, lowerPrefix, literal, EditorSuggestionKind.LITERAL, "value", 970);
                    }
                }
            }
            case XML -> {
                if (isXmlTagSuggestionContext(beforeCursor)) {
                    for (String tag : collectStructuredSuggestions("<\\s*([A-Za-z0-9_:\\-]+)", 1)) {
                        maybeAddSuggestion(values, prefix, lowerPrefix, tag, EditorSuggestionKind.SYMBOL, "xml tag", 995);
                    }
                }
                if (isXmlAttributeSuggestionContext(beforeCursor)) {
                    for (String attribute : collectStructuredSuggestions("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*\"[^\"]*\"", 1)) {
                        maybeAddSuggestion(values, prefix, lowerPrefix, attribute, EditorSuggestionKind.SYMBOL, "xml attr", 980);
                    }
                }
            }
            case CSS -> {
                if (isCssPropertySuggestionContext(beforeCursor)) {
                    for (String property : collectStructuredSuggestions("(?m)^\\s*([A-Za-z\\-]+)\\s*:", 1)) {
                        maybeAddSuggestion(values, prefix, lowerPrefix, property, EditorSuggestionKind.SYMBOL, "css prop", 990);
                    }
                }
            }
            default -> {
            }
        }
    }

    private List<String> collectStructuredSuggestions(String regex, int groupIndex) {
        if (document == null) {
            return Collections.emptyList();
        }
        Set<String> values = new LinkedHashSet<>();
        try {
            Matcher matcher = Pattern.compile(regex).matcher(document.getText());
            while (matcher.find()) {
                String value = matcher.group(groupIndex);
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            }
        } catch (RuntimeException ignored) {
            return Collections.emptyList();
        }
        return new ArrayList<>(values);
    }

    private void addRegexSuggestions(Map<String, EditorSuggestion> values, String prefix, String lowerPrefix, String regex, int groupIndex, EditorSuggestionKind kind, String detail, int baseScore) {
        if (document == null) {
            return;
        }
        try {
            Matcher matcher = Pattern.compile(regex).matcher(document.getText());
            while (matcher.find()) {
                String candidate = matcher.group(groupIndex);
                if (candidate == null) {
                    continue;
                }
                candidate = candidate.trim();
                if (kind == EditorSuggestionKind.SELECTOR) {
                    candidate = candidate.replaceAll("\\s+", " ").trim();
                }
                maybeAddSuggestion(values, prefix, lowerPrefix, candidate, kind, detail, baseScore);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private boolean isJsonKeySuggestionContext(String beforeCursor) {
        String trimmed = beforeCursor.trim();
        return trimmed.startsWith("\"") && !trimmed.contains(":");
    }

    private boolean isJsonValueSuggestionContext(String beforeCursor) {
        return isKeyValueValueContext(beforeCursor, ':');
    }

    private boolean isYamlKeySuggestionContext(String beforeCursor) {
        String trimmed = beforeCursor.trim();
        return !trimmed.isEmpty() && !trimmed.startsWith("-") && !trimmed.contains(":");
    }

    private boolean isTomlKeySuggestionContext(String beforeCursor) {
        String trimmed = beforeCursor.trim();
        return !trimmed.isEmpty() && !trimmed.startsWith("[") && !trimmed.contains("=");
    }

    private boolean isPropertyKeySuggestionContext(String beforeCursor) {
        String trimmed = beforeCursor.trim();
        return !trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("!") && !trimmed.contains("=") && !trimmed.contains(":");
    }

    private boolean isPropertyValueSuggestionContext(String beforeCursor) {
        return isKeyValueValueContext(beforeCursor, '=') || isKeyValueValueContext(beforeCursor, ':');
    }

    private boolean isKeyValueValueContext(String beforeCursor, char separator) {
        int index = beforeCursor.lastIndexOf(separator);
        return index >= 0 && index < beforeCursor.length() - 1;
    }

    private boolean isXmlTagSuggestionContext(String beforeCursor) {
        String trimmed = beforeCursor.trim();
        return trimmed.endsWith("<") || trimmed.matches(".*<[/]?[A-Za-z0-9_:\\-]*$");
    }

    private boolean isXmlAttributeSuggestionContext(String beforeCursor) {
        return beforeCursor.matches(".*<\\s*[A-Za-z0-9_:\\-]+\\s+[A-Za-z0-9_:\\-]*$");
    }

    private boolean isCssPropertySuggestionContext(String beforeCursor) {
        String trimmed = beforeCursor.trim();
        return !trimmed.isEmpty() && !trimmed.contains(":") && !trimmed.startsWith("@") && !trimmed.startsWith("}") && isInsideCssDeclarationBlock(document.getCursorLine());
    }

    private boolean isEditorSuggestionVisible() {
        return !editorSuggestions.isEmpty();
    }

    private void moveEditorSuggestionSelection(int delta) {
        if (editorSuggestions.isEmpty()) {
            return;
        }
        selectedEditorSuggestionIndex = clamp(selectedEditorSuggestionIndex + delta, 0, editorSuggestions.size() - 1);
    }

    private boolean acceptSelectedEditorSuggestion() {
        if (!canMutateDocument("Autocomplete is unavailable") || editorSuggestions.isEmpty()) {
            return false;
        }
        String prefix = getCurrentTokenPrefix();
        String suggestion = editorSuggestions.get(selectedEditorSuggestionIndex).value();
        if (prefix.isBlank() || !suggestion.startsWith(prefix)) {
            return false;
        }
        String suffix = suggestion.substring(prefix.length());
        if (suffix.isEmpty()) {
            return false;
        }
        captureUndoSnapshot();
        if (document.insertText(suffix)) {
            editorSuggestionDismissed = false;
            onDocumentMutated("Completed");
            return true;
        }
        return false;
    }

    private void maybeAddSuggestion(Map<String, EditorSuggestion> values, String prefix, String lowerPrefix, String rawCandidate, EditorSuggestionKind kind, String detail, int baseScore) {
        if (rawCandidate == null) {
            return;
        }
        String candidate = rawCandidate.trim();
        if (candidate.isEmpty() || candidate.equals(prefix) || candidate.length() < prefix.length()) {
            return;
        }
        String lowerCandidate = candidate.toLowerCase(Locale.ROOT);
        int score;
        if (lowerCandidate.startsWith(lowerPrefix)) {
            score = baseScore + 120 - Math.max(0, candidate.length() - prefix.length());
        } else {
            int containsIndex = lowerCandidate.indexOf(lowerPrefix);
            if (containsIndex < 0) {
                return;
            }
            score = baseScore - 80 - (containsIndex * 8);
        }
        EditorSuggestion existing = values.get(lowerCandidate);
        EditorSuggestion suggestion = new EditorSuggestion(kind, candidate, detail == null ? "" : detail, score);
        if (existing == null || suggestion.score() > existing.score()) {
            values.put(lowerCandidate, suggestion);
        }
    }

    private List<String> getLanguageSuggestionWords(EditorSyntaxHighlighter.SyntaxLanguage language) {
        return switch (language) {
            case JAVA -> List.of("public", "private", "protected", "class", "interface", "enum", "record", "static", "final", "void", "return", "if", "else", "switch", "case", "for", "while", "try", "catch", "new", "extends", "implements", "import", "package");
            case JAVASCRIPT -> List.of("function", "const", "let", "var", "return", "if", "else", "switch", "case", "for", "while", "import", "export", "async", "await", "class", "extends");
            case JSON -> List.of("true", "false", "null");
            case YAML -> List.of("true", "false", "null", "yes", "no");
            case KTL -> List.of("version", "kind", "id", "template_id", "entries", "phrases", "maps_to", "operations", "patterns", "steps", "type", "action", "params", "defaults", "semantic_operation", "semantic_operations", "target_kinds", "required_params", "optional_params", "preferred_templates", "selector", "condition", "evaluator_key", "returns", "run_primitive", "delegate", "branch", "goto", "return", "true", "false", "null");
            case TOML -> List.of("true", "false");
            case XML -> List.of("version", "encoding", "xmlns");
            case CSS -> List.of("display", "position", "color", "background", "background-color", "margin", "padding", "width", "height", "font-size", "border", "flex", "grid", "align-items", "justify-content");
            case SHADER -> List.of("uniform", "varying", "attribute", "vec2", "vec3", "vec4", "mat3", "mat4", "float", "int", "void", "return", "if", "for", "gl_Position", "gl_FragColor");
            case PROPERTIES, CONFIG -> List.of("true", "false");
            default -> Collections.emptyList();
        };
    }

    private List<String> getLanguageLiteralSuggestions(EditorSyntaxHighlighter.SyntaxLanguage language) {
        return switch (language) {
            case JSON -> List.of("\"\"", "[]", "{}", "0", "1");
            case YAML -> List.of("\"\"", "[]", "{}", "0", "1");
            case KTL -> List.of("\"\"", "[]", "{}", "lexicon", "grammar_patterns", "condition_definition", "condition_pack", "semantic_operation", "semantic_operation_pack", "template_metadata", "task_template", "task_preset", "task_macro", "resolver_rules", "reference_patterns", "selector_pack", "run_primitive", "delegate", "branch", "goto", "return", "cap.", "eval.", "sem.", "minecraft:", "${}");
            case TOML -> List.of("\"\"", "[]", "{}", "0", "1");
            case XML -> List.of("\"\"", "/>", "</>");
            case JAVA, JAVASCRIPT, CSS, SHADER -> List.of("true", "false", "null", "\"\"", "0", "1");
            default -> Collections.emptyList();
        };
    }

    private void updateSearchMatches() {
        searchMatches.clear();
        activeSearchMatchIndex = -1;
        searchErrorMessage = "";
        activeSearchPattern = null;
        if (searchInput == null || document == null) {
            rebuildSearchDropdownEntries();
            return;
        }

        parsedSearchCommand = parseSearchCommand(searchInput.getText());
        if (parsedSearchCommand.type() == SearchCommandType.GO_TO_LINE || parsedSearchCommand.query().isBlank()) {
            rebuildSearchDropdownEntries();
            return;
        }

        String fullText = document.getText();
        try {
            activeSearchPattern = buildSearchPattern(parsedSearchCommand.query());
            Matcher matcher = activeSearchPattern.matcher(fullText);
            LineIndexMap lineIndexMap = buildLineIndexMap();
            int count = 0;
            while (matcher.find() && count < MAX_SEARCH_RESULTS) {
                int start = matcher.start();
                int end = matcher.end();
                if (end <= start) {
                    if (end >= fullText.length()) {
                        break;
                    }
                    matcher.region(end + 1, fullText.length());
                    continue;
                }
                SearchPosition startPosition = lineIndexMap.resolve(start);
                SearchPosition endPosition = lineIndexMap.resolve(end);
                String previewLine = document.getLine(startPosition.line());
                searchMatches.add(new SearchMatch(
                        start,
                        end,
                        startPosition.line(),
                        startPosition.column(),
                        endPosition.line(),
                        endPosition.column(),
                        buildMatchPreview(previewLine, startPosition.column(), endPosition.line() == startPosition.line() ? endPosition.column() : previewLine.length()),
                        buildReplacementPreview(matcher)
                ));
                count++;
            }
        } catch (PatternSyntaxException exception) {
            searchErrorMessage = exception.getDescription();
        }

        if (!searchMatches.isEmpty()) {
            activeSearchMatchIndex = 0;
            if (searchAutoJump && !searchHighlightOnly) {
                focusSearchMatch(activeSearchMatchIndex);
            }
        }
        rebuildSearchDropdownEntries();
    }

    private void navigateSearchResult(boolean forward) {
        if (searchMatches.isEmpty()) {
            return;
        }
        rememberSearch(searchInput.getText());
        if (activeSearchMatchIndex < 0) {
            activeSearchMatchIndex = 0;
        } else if (forward) {
            activeSearchMatchIndex = (activeSearchMatchIndex + 1) % searchMatches.size();
        } else {
            activeSearchMatchIndex = (activeSearchMatchIndex - 1 + searchMatches.size()) % searchMatches.size();
        }
        if (!searchHighlightOnly) {
            focusSearchMatch(activeSearchMatchIndex);
        }
        rebuildSearchDropdownEntries();
    }

    private void focusSearchMatch(int index) {
        if (index < 0 || index >= searchMatches.size()) {
            return;
        }
        SearchMatch match = searchMatches.get(index);
        document.setCursor(match.startLine(), match.startColumn(), false);
        document.selectRange(match.startLine(), match.startColumn(), match.endLine(), match.endColumn());
        ensureCursorVisible();
        resetCursorBlink();
    }

    private void drawSearchDropdown(DrawContext context, int mouseX, int mouseY) {
        if (!isSearchDropdownVisible()) {
            return;
        }

        int x = getSearchDropdownX();
        int y = getSearchDropdownY();
        int width = getSearchDropdownWidth();
        int controlsY = y + SEARCH_DROPDOWN_PADDING;
        int statusY = controlsY + SEARCH_DROPDOWN_ROW_HEIGHT + SEARCH_DROPDOWN_SECTION_GAP;
        int listY = statusY + SEARCH_DROPDOWN_ROW_HEIGHT - 2;
        int visibleRows = Math.min(SEARCH_DROPDOWN_MAX_VISIBLE_ROWS, Math.max(1, searchDropdownEntries.size()));
        int height = (listY - y) + (visibleRows * SEARCH_DROPDOWN_ROW_HEIGHT) + SEARCH_DROPDOWN_PADDING;

        context.fill(x, y, x + width, y + height, withAlpha(uiColorContentBase, 230));
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());

        int controlsX = x + SEARCH_DROPDOWN_PADDING;
        controlsX = renderSearchActionButton(context, controlsX, controlsY, "Find", false, mouseX, mouseY);
        controlsX = renderSearchActionButton(context, controlsX, controlsY, "Replace", searchReplaceVisible, mouseX, mouseY);
        controlsX = renderSearchActionButton(context, controlsX, controlsY, "All", false, mouseX, mouseY);
        controlsX = renderSearchToggle(context, controlsX, controlsY, "Aa", searchCaseSensitive, mouseX, mouseY);
        controlsX = renderSearchToggle(context, controlsX, controlsY, "Word", searchWholeWord, mouseX, mouseY);
        controlsX = renderSearchToggle(context, controlsX, controlsY, ".*", searchRegex, mouseX, mouseY);
        controlsX = renderSearchToggle(context, controlsX, controlsY, "Jump", searchAutoJump, mouseX, mouseY);
        renderSearchToggle(context, controlsX, controlsY, "Only", searchHighlightOnly, mouseX, mouseY);

        int nextButtonWidth = renderSearchNavButton(context, x + width - SEARCH_DROPDOWN_PADDING - 16, controlsY + 1, ">", mouseX, mouseY);
        renderSearchNavButton(context, x + width - SEARCH_DROPDOWN_PADDING - 16 - 4 - nextButtonWidth, controlsY + 1, "<", mouseX, mouseY);

        String status = buildSearchStatusText();
        context.drawText(this.textRenderer, fitText(status, width - 12), x + SEARCH_DROPDOWN_PADDING, statusY + 3, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);

        int startIndex = dropdownScrollOffset;
        int endIndex = Math.min(searchDropdownEntries.size(), startIndex + visibleRows);
        for (int rowIndex = startIndex; rowIndex < endIndex; rowIndex++) {
            DropdownEntry entry = searchDropdownEntries.get(rowIndex);
            int visibleIndex = rowIndex - startIndex;
            int rowY = listY + (visibleIndex * SEARCH_DROPDOWN_ROW_HEIGHT);
            boolean hovered = mouseX >= x + 1 && mouseX <= x + width - 1 && mouseY >= rowY && mouseY <= rowY + SEARCH_DROPDOWN_ROW_HEIGHT;
            boolean selected = rowIndex == selectedDropdownIndex;
            if (hovered || selected) {
                context.fill(x + 1, rowY, x + width - 1, rowY + SEARCH_DROPDOWN_ROW_HEIGHT, selected ? withAlpha(uiColorHeader, 180) : withAlpha(uiColorHeader, 120));
            }
            int primaryColor = entry.type() == DropdownEntryType.MATCH && entry.index() == activeSearchMatchIndex
                    ? new Color(uiColorContentBaseTitleText, true).getRGB()
                    : new Color(uiColorIDEFileDisplayWindowText, true).getRGB();
            context.drawText(this.textRenderer, fitText(entry.primary(), width - 20), x + SEARCH_DROPDOWN_PADDING, rowY + 3, primaryColor, false);
            if (!entry.secondary().isEmpty()) {
                context.drawText(this.textRenderer, fitText(entry.secondary(), width - 20), x + SEARCH_DROPDOWN_PADDING, rowY + 11, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            }
        }
    }

    private int renderSearchToggle(DrawContext context, int x, int y, String label, boolean enabled, int mouseX, int mouseY) {
        int width = Math.max(16, this.textRenderer.getWidth(label) + 4);
        boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 14;
        int fill = enabled ? withAlpha(uiColorHeader, 190) : withAlpha(uiColorContentBase, hovered ? 190 : 150);
        context.fill(x, y, x + width, y + 14, fill);
        context.drawBorder(x, y, width, 14, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawText(this.textRenderer, label, x + 2, y + 3, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        if (enabled) {
            int accentColor = new Color(uiColorContentBaseTitleText, true).getRGB();
            context.fill(x + 2, y + 11, x + width - 2, y + 13, accentColor);
            context.fill(x + width - 4, y + 2, x + width - 2, y + 4, accentColor);
        }
        return x + width + 2;
    }

    private int renderSearchActionButton(DrawContext context, int x, int y, String label, boolean active, int mouseX, int mouseY) {
        int width = Math.max(36, this.textRenderer.getWidth(label) + 12);
        boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 14;
        int fill = active ? withAlpha(uiColorHeader, 190) : withAlpha(uiColorContentBase, hovered ? 190 : 150);
        context.fill(x, y, x + width, y + 14, fill);
        context.drawBorder(x, y, width, 14, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawText(this.textRenderer, label, x + 6, y + 3, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        if (active) {
            int accentColor = new Color(uiColorContentBaseTitleText, true).getRGB();
            context.fill(x + 3, y + 11, x + width - 3, y + 13, accentColor);
        }
        return x + width + 4;
    }

    private int renderSearchNavButton(DrawContext context, int x, int y, String label, int mouseX, int mouseY) {
        int width = 16;
        boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 12;
        context.fill(x, y, x + width, y + 12, withAlpha(uiColorContentBase, hovered ? 190 : 150));
        context.drawBorder(x, y, width, 12, new Color(uiColorBackgroundBorder, true).getRGB());
        context.drawText(this.textRenderer, label, x + 5, y + 2, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        return width;
    }

    private void rebuildSearchDropdownEntries() {
        searchDropdownEntries.clear();

        if (parsedSearchCommand.type() == SearchCommandType.GO_TO_LINE) {
            searchDropdownEntries.add(new DropdownEntry(DropdownEntryType.GOTO_LINE, "Go to line " + parsedSearchCommand.lineNumber(), "Press Enter or click to jump", -1, String.valueOf(parsedSearchCommand.lineNumber())));
        } else if (!searchMatches.isEmpty()) {
            for (int i = 0; i < Math.min(searchMatches.size(), MAX_SEARCH_RESULTS); i++) {
                SearchMatch match = searchMatches.get(i);
                String lineLabel = "Line " + (match.startLine() + 1);
                String secondary = match.replacementPreview().isEmpty() ? match.preview() : match.preview() + "  ->  " + match.replacementPreview();
                searchDropdownEntries.add(new DropdownEntry(DropdownEntryType.MATCH, lineLabel, secondary, i, searchInput.getText()));
            }
        } else if (!searchErrorMessage.isEmpty()) {
            searchDropdownEntries.add(new DropdownEntry(DropdownEntryType.ERROR, "Regex error", searchErrorMessage, -1, ""));
        } else if (!parsedSearchCommand.query().isBlank()) {
            for (String suggestion : buildContentSuggestions(parsedSearchCommand.query())) {
                searchDropdownEntries.add(new DropdownEntry(DropdownEntryType.SUGGESTION, suggestion, "Suggested from current file", -1, suggestion));
            }
        } else {
            for (String recent : recentSearches) {
                searchDropdownEntries.add(new DropdownEntry(DropdownEntryType.RECENT, recent, "Recent search", -1, recent));
            }
        }

        if (selectedDropdownIndex < 0 && !searchDropdownEntries.isEmpty()) {
            selectedDropdownIndex = 0;
        }
        selectedDropdownIndex = clamp(selectedDropdownIndex, searchDropdownEntries.isEmpty() ? -1 : 0, Math.max(0, searchDropdownEntries.size() - 1));
        dropdownScrollOffset = clamp(dropdownScrollOffset, 0, Math.max(0, searchDropdownEntries.size() - SEARCH_DROPDOWN_MAX_VISIBLE_ROWS));
        ensureDropdownSelectionVisible();
    }

    private Pattern buildSearchPattern(String query) {
        int flags = Pattern.MULTILINE | Pattern.DOTALL;
        if (!searchCaseSensitive) {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        String patternText = searchRegex ? query : Pattern.quote(query);
        if (searchWholeWord) {
            patternText = "(?<![\\p{L}\\p{N}_])" + patternText + "(?![\\p{L}\\p{N}_])";
        }
        return Pattern.compile(patternText, flags);
    }

    private ParsedSearchCommand parseSearchCommand(String rawInput) {
        String raw = rawInput == null ? "" : rawInput.trim();
        if (raw.startsWith(":")) {
            String value = raw.substring(1).trim();
            if (value.matches("\\d+")) {
                return ParsedSearchCommand.goToLine(Integer.parseInt(value));
            }
        }
        if (raw.toLowerCase().startsWith("find ")) {
            return ParsedSearchCommand.search(raw.substring(5).trim());
        }
        return ParsedSearchCommand.search(raw);
    }

    private List<String> buildContentSuggestions(String query) {
        if (document == null || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        Set<String> values = new LinkedHashSet<>();
        String lowerQuery = query.toLowerCase();
        for (String line : document.getLines()) {
            for (String token : line.split("[^A-Za-z0-9_\\-]+")) {
                if (token.length() < 2) {
                    continue;
                }
                String lowerToken = token.toLowerCase();
                if (lowerToken.contains(lowerQuery)) {
                    values.add(token);
                    if (values.size() >= MAX_SUGGESTIONS) {
                        return new ArrayList<>(values);
                    }
                }
            }
        }
        return new ArrayList<>(values);
    }

    private void moveDropdownSelection(int delta) {
        if (searchDropdownEntries.isEmpty()) {
            return;
        }
        if (selectedDropdownIndex < 0) {
            selectedDropdownIndex = 0;
        } else {
            selectedDropdownIndex = clamp(selectedDropdownIndex + delta, 0, searchDropdownEntries.size() - 1);
        }
        ensureDropdownSelectionVisible();
    }

    private void ensureDropdownSelectionVisible() {
        if (selectedDropdownIndex < 0) {
            return;
        }
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
        return activateDropdownEntry(searchDropdownEntries.get(selectedDropdownIndex));
    }

    private boolean activateDropdownEntry(DropdownEntry entry) {
        switch (entry.type()) {
            case MATCH -> {
                activeSearchMatchIndex = entry.index();
                rememberSearch(searchInput.getText());
                if (!searchHighlightOnly) {
                    focusSearchMatch(activeSearchMatchIndex);
                }
                rebuildSearchDropdownEntries();
                return true;
            }
            case RECENT, SUGGESTION -> {
                searchInput.setText(entry.value());
                searchInput.setFocused(true);
                return true;
            }
            case GOTO_LINE -> {
                jumpToLine(parsedSearchCommand.lineNumber());
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean handleSearchDropdownClick(double mouseX, double mouseY) {
        if (!isSearchDropdownVisible()) {
            return false;
        }
        if (!isInsideSearchDropdown(mouseX, mouseY)) {
            return false;
        }
        int x = getSearchDropdownX();
        int y = getSearchDropdownY();
        int width = getSearchDropdownWidth();
        int controlsY = y + SEARCH_DROPDOWN_PADDING;
        int statusY = controlsY + SEARCH_DROPDOWN_ROW_HEIGHT + SEARCH_DROPDOWN_SECTION_GAP + 1;
        int listY = statusY + SEARCH_DROPDOWN_ROW_HEIGHT;

        if (tryHandleActionClick(mouseX, mouseY, x, controlsY)) {
            return true;
        }
        if (mouseY >= listY) {
            int row = (int) ((mouseY - listY) / SEARCH_DROPDOWN_ROW_HEIGHT);
            int index = dropdownScrollOffset + row;
            if (index >= 0 && index < searchDropdownEntries.size()) {
                selectedDropdownIndex = index;
                return activateDropdownEntry(searchDropdownEntries.get(index));
            }
        }
        return mouseX >= x && mouseX <= x + width;
    }

    private boolean tryHandleToggleClick(double mouseX, double mouseY, int x, int y) {
        int currentX = x + SEARCH_DROPDOWN_PADDING;
        currentX += Math.max(36, this.textRenderer.getWidth("Find") + 12) + 4;
        currentX += Math.max(36, this.textRenderer.getWidth("Replace") + 12) + 4;
        currentX += Math.max(36, this.textRenderer.getWidth("All") + 12) + 4;
        int aaWidth = Math.max(16, this.textRenderer.getWidth("Aa") + 4);
        if (isInsideSmallButton(mouseX, mouseY, currentX, y, aaWidth)) {
            searchCaseSensitive = !searchCaseSensitive;
            updateSearchMatches();
            return true;
        }
        currentX += aaWidth + 2;
        int wordWidth = Math.max(16, this.textRenderer.getWidth("Word") + 4);
        if (isInsideSmallButton(mouseX, mouseY, currentX, y, wordWidth)) {
            searchWholeWord = !searchWholeWord;
            updateSearchMatches();
            return true;
        }
        currentX += wordWidth + 2;
        int regexWidth = Math.max(16, this.textRenderer.getWidth(".*") + 4);
        if (isInsideSmallButton(mouseX, mouseY, currentX, y, regexWidth)) {
            searchRegex = !searchRegex;
            updateSearchMatches();
            return true;
        }
        currentX += regexWidth + 2;
        int jumpWidth = Math.max(16, this.textRenderer.getWidth("Jump") + 4);
        if (isInsideSmallButton(mouseX, mouseY, currentX, y, jumpWidth)) {
            searchAutoJump = !searchAutoJump;
            updateSearchMatches();
            return true;
        }
        currentX += jumpWidth + 2;
        int onlyWidth = Math.max(16, this.textRenderer.getWidth("Only") + 4);
        if (isInsideSmallButton(mouseX, mouseY, currentX, y, onlyWidth)) {
            searchHighlightOnly = !searchHighlightOnly;
            updateSearchMatches();
            return true;
        }
        return false;
    }

    private boolean tryHandleActionClick(double mouseX, double mouseY, int x, int y) {
        if (tryHandleToggleClick(mouseX, mouseY, x, y)) {
            return true;
        }
        int currentX = x + SEARCH_DROPDOWN_PADDING;
        if (isInsideTextButton(mouseX, mouseY, currentX, y, "Find")) {
            navigateSearchResult(true);
            return true;
        }
        currentX += Math.max(36, this.textRenderer.getWidth("Find") + 12) + 4;
        if (isInsideTextButton(mouseX, mouseY, currentX, y, "Replace")) {
            searchReplaceVisible = !searchReplaceVisible;
            if (searchReplaceVisible && replaceInput != null) {
                if (searchInput != null) {
                    searchInput.setFocused(false);
                }
                replaceInput.setFocused(true);
            } else {
                exitReplaceMode(true);
            }
            rebuildSearchDropdownEntries();
            return true;
        }
        currentX += Math.max(36, this.textRenderer.getWidth("Replace") + 12) + 4;
        if (isInsideTextButton(mouseX, mouseY, currentX, y, "All")) {
            replaceAllMatches();
            return true;
        }
        int navRightX = getSearchDropdownX() + getSearchDropdownWidth() - SEARCH_DROPDOWN_PADDING - 16;
        int navLeftX = navRightX - 4 - 16;
        int navY = getSearchDropdownY() + SEARCH_DROPDOWN_PADDING + 1;
        if (mouseX >= navLeftX && mouseX <= navLeftX + 16 && mouseY >= navY && mouseY <= navY + 12) {
            navigateSearchResult(false);
            return true;
        }
        if (mouseX >= navRightX && mouseX <= navRightX + 16 && mouseY >= navY && mouseY <= navY + 12) {
            navigateSearchResult(true);
            return true;
        }
        return false;
    }

    private void replaceCurrentMatch() {
        if (!canMutateDocument("Replace is unavailable")) {
            return;
        }
        if (replaceInput == null || activeSearchMatchIndex < 0 || activeSearchMatchIndex >= searchMatches.size()) {
            return;
        }
        try {
            captureUndoSnapshot();
            SearchMatch match = searchMatches.get(activeSearchMatchIndex);
            String updatedText = document.getText().substring(0, match.startOffset()) + getReplacementForMatch(match) + document.getText().substring(match.endOffset());
            replaceDocumentText(updatedText, "Replaced match");
            clearErrorState();
            exitReplaceMode(true);
        } catch (RuntimeException exception) {
            setErrorState("Replace failed", exception.getMessage(), 5000L);
        }
    }

    private void replaceAllMatches() {
        if (!canMutateDocument("Replace all is unavailable")) {
            return;
        }
        if (replaceInput == null || activeSearchPattern == null || document == null || parsedSearchCommand.query().isBlank()) {
            return;
        }
        try {
            captureUndoSnapshot();
            String replacement = replaceInput.getText();
            String updatedText;
            if (searchRegex) {
                updatedText = activeSearchPattern.matcher(document.getText()).replaceAll(replacement == null ? "" : replacement);
            } else {
                updatedText = activeSearchPattern.matcher(document.getText()).replaceAll(Matcher.quoteReplacement(replacement == null ? "" : replacement));
            }
            replaceDocumentText(updatedText, "Replaced all");
            clearErrorState();
            exitReplaceMode(true);
        } catch (RuntimeException exception) {
            setErrorState("Replace failed", exception.getMessage(), 5000L);
        }
    }

    private void replaceDocumentText(String updatedText, String message) {
        this.document = new EditorDocument(splitLines(updatedText));
        this.cachedLongestLineWidth = -1;
        EditorSyntaxHighlighter.clearCache();
        onDocumentMutated(message);
        rememberSearch(searchInput.getText());
    }

    private void jumpToLine(int lineNumber) {
        int targetLine = clamp(lineNumber - 1, 0, document.getLineCount() - 1);
        document.setCursor(targetLine, 0, false);
        document.moveHome(false);
        ensureCursorVisible();
        resetCursorBlink();
        saveStatusMessage = "Jumped to line " + lineNumber;
        saveStatusColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
        saveStatusUntil = Util.getMeasuringTimeMs() + 1200L;
    }

    private int getDiagnosticLineAtMouse(double mouseX, double mouseY) {
        if (document == null) {
            return -1;
        }
        int viewportX = getEditorAreaX();
        int viewportY = CONTENT_TOP + EDITOR_HEADER_HEIGHT;
        int viewportHeight = this.height - viewportY - STATUS_BAR_HEIGHT - 1;
        int gutterWidth = getGutterWidth();
        if (mouseX < viewportX || mouseX > viewportX + gutterWidth || mouseY < viewportY || mouseY > viewportY + viewportHeight) {
            return -1;
        }
        int lineHeight = this.textRenderer.fontHeight + 2;
        int relativeLine = (int) ((mouseY - (viewportY + 6)) / lineHeight);
        if (relativeLine < 0) {
            return -1;
        }
        int lineIndex = verticalScrollOffset + relativeLine;
        if (lineIndex < 0 || lineIndex >= document.getLineCount()) {
            return -1;
        }
        return getLineDiagnostic(lineIndex) != null ? lineIndex : -1;
    }

    private void rememberSearch(String rawQuery) {
        String normalized = parseSearchCommand(rawQuery).query();
        if (normalized == null || normalized.isBlank()) {
            return;
        }
        recentSearches.remove(normalized);
        recentSearches.add(0, normalized);
        while (recentSearches.size() > MAX_RECENT_SEARCHES) {
            recentSearches.remove(recentSearches.size() - 1);
        }
    }

    private boolean applyDiagnosticQuickClick(int lineIndex) {
        EditorDiagnostic diagnostic = getLineDiagnostic(lineIndex);
        if (diagnostic == null || diagnostic.quickClick() == null || !canMutateDocument("Quick Click is unavailable")) {
            return false;
        }
        EditorQuickClick fix = diagnostic.quickClick();
        String updatedText = switch (fix.kind()) {
            case APPEND_TO_LINE -> appendToLine(lineIndex, fix.payload());
            case REMOVE_LINE_SUFFIX -> removeLineSuffix(lineIndex, fix.payload());
            case REPLACE_FIRST_CLOSER -> replaceFirstCloserOnLine(lineIndex, fix.payload());
            case REMOVE_FIRST_CLOSER -> removeFirstCloserOnLine(lineIndex, fix.payload());
            case TRIM_LINE_ENDING -> trimLineEnding(lineIndex);
            case REPLACE_LEADING_INDENT -> replaceLeadingIndent(lineIndex, fix.payload());
        };
        if (updatedText == null || updatedText.equals(document.getText())) {
            return false;
        }
        captureUndoSnapshot();
        replaceDocumentText(updatedText, fix.label());
        jumpToLine(lineIndex + 1);
        clearErrorState();
        return true;
    }

    private String appendToLine(int lineIndex, String suffix) {
        if (document == null || suffix == null) {
            return null;
        }
        int targetLine = findContinuationTailLine(EditorSyntaxHighlighter.SyntaxLanguage.fromFileName(fileItem.getName()), lineIndex);
        List<String> lines = new ArrayList<>(document.getLines());
        lines.set(targetLine, lines.get(targetLine) + suffix);
        return String.join("\n", lines);
    }

    private String removeLineSuffix(int lineIndex, String suffix) {
        if (document == null || suffix == null || suffix.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>(document.getLines());
        String line = lines.get(lineIndex);
        int index = line.lastIndexOf(suffix);
        if (index < 0) {
            return null;
        }
        lines.set(lineIndex, line.substring(0, index) + line.substring(index + suffix.length()));
        return String.join("\n", lines);
    }

    private String replaceFirstCloserOnLine(int lineIndex, String payload) {
        if (document == null || payload == null || payload.length() < 2) {
            return null;
        }
        char current = payload.charAt(0);
        char replacement = payload.charAt(1);
        List<String> lines = new ArrayList<>(document.getLines());
        String line = lines.get(lineIndex);
        int index = line.indexOf(current);
        if (index < 0) {
            return null;
        }
        lines.set(lineIndex, line.substring(0, index) + replacement + line.substring(index + 1));
        return String.join("\n", lines);
    }

    private String removeFirstCloserOnLine(int lineIndex, String payload) {
        if (document == null || payload == null || payload.isEmpty()) {
            return null;
        }
        char target = payload.charAt(0);
        List<String> lines = new ArrayList<>(document.getLines());
        String line = lines.get(lineIndex);
        int index = line.indexOf(target);
        if (index < 0) {
            return null;
        }
        lines.set(lineIndex, line.substring(0, index) + line.substring(index + 1));
        return String.join("\n", lines);
    }

    private String trimLineEnding(int lineIndex) {
        if (document == null || lineIndex < 0 || lineIndex >= document.getLineCount()) {
            return null;
        }
        List<String> lines = new ArrayList<>(document.getLines());
        String line = lines.get(lineIndex);
        String trimmed = line.replaceFirst("[\t ]+$", "");
        if (trimmed.equals(line)) {
            return null;
        }
        lines.set(lineIndex, trimmed);
        return String.join("\n", lines);
    }

    private String replaceLeadingIndent(int lineIndex, String replacement) {
        if (document == null || replacement == null || lineIndex < 0 || lineIndex >= document.getLineCount()) {
            return null;
        }
        List<String> lines = new ArrayList<>(document.getLines());
        String line = lines.get(lineIndex);
        String updated = line.replaceFirst("^\t+", Matcher.quoteReplacement(replacement));
        if (updated.equals(line)) {
            return null;
        }
        lines.set(lineIndex, updated);
        return String.join("\n", lines);
    }


    private String buildSearchStatusText() {
        if (!searchErrorMessage.isEmpty()) {
            return "Regex error: " + searchErrorMessage;
        }
        if (parsedSearchCommand.type() == SearchCommandType.GO_TO_LINE) {
            return "Go to line " + parsedSearchCommand.lineNumber();
        }
        if (parsedSearchCommand.query().isBlank()) {
            return recentSearches.isEmpty() ? "Search current file" : "Recent searches";
        }
        return searchMatches.size() + " results";
    }

    private String buildReplacementSummary() {
        if (!searchReplaceVisible || replaceInput == null || searchMatches.isEmpty()) {
            return "";
        }
        return "Preview " + searchMatches.size() + " replacements";
    }

    private String buildMatchPreview(String line, int startColumn, int endColumn) {
        int previewStart = Math.max(0, startColumn - 18);
        int previewEnd = Math.min(line.length(), Math.max(endColumn, startColumn + 1) + 18);
        String prefix = previewStart > 0 ? "..." : "";
        String suffix = previewEnd < line.length() ? "..." : "";
        return prefix + line.substring(previewStart, previewEnd) + suffix;
    }

    private String buildReplacementPreview(Matcher matcher) {
        if (!searchReplaceVisible || replaceInput == null || replaceInput.getText().isBlank()) {
            return "";
        }
        return searchRegex ? matcher.replaceFirst(replaceInput.getText()) : replaceInput.getText();
    }

    private String getReplacementForMatch(SearchMatch match) {
        if (replaceInput == null) {
            return "";
        }
        if (!searchRegex || activeSearchPattern == null) {
            return replaceInput.getText();
        }
        Matcher matcher = activeSearchPattern.matcher(document.getText().substring(match.startOffset(), match.endOffset()));
        if (matcher.find()) {
            return matcher.replaceFirst(replaceInput.getText());
        }
        return replaceInput.getText();
    }

    private LineIndexMap buildLineIndexMap() {
        List<Integer> starts = new ArrayList<>();
        int offset = 0;
        for (String line : document.getLines()) {
            starts.add(offset);
            offset += line.length() + 1;
        }
        return new LineIndexMap(starts);
    }

    private boolean isSearchDropdownVisible() {
        String inputText = searchInput == null ? "" : searchInput.getText();
        if (searchDropdownDismissed) {
            return false;
        }
        return !inputText.isBlank();
    }

    private boolean isSearchInteractionActive() {
        return (searchInput != null && !searchInput.getText().isBlank())
                || (replaceInput != null && !replaceInput.getText().isBlank())
                || searchReplaceVisible
                || (searchInput != null && searchInput.isFocused())
                || (replaceInput != null && replaceInput.isFocused())
                || !searchMatches.isEmpty();
    }

    private void clearSearchInteractionFocus() {
        if (searchInput != null) {
            searchInput.setFocused(false);
        }
        if (replaceInput != null) {
            replaceInput.setFocused(false);
        }
        if (searchReplaceVisible) {
            exitReplaceMode(false);
        }
    }

    private void exitReplaceMode(boolean focusSearch) {
        searchReplaceVisible = false;
        if (replaceInput != null) {
            replaceInput.setFocused(false);
        }
        if (searchInput != null) {
            searchInput.setFocused(focusSearch);
        }
    }

    private boolean isInsideSearchDropdown(double mouseX, double mouseY) {
        if (!isSearchDropdownVisible()) {
            return false;
        }
        int x = getSearchDropdownX();
        int y = getSearchDropdownY();
        int width = getSearchDropdownWidth();
        int visibleRows = Math.min(SEARCH_DROPDOWN_MAX_VISIBLE_ROWS, Math.max(1, searchDropdownEntries.size()));
        int height = SEARCH_DROPDOWN_PADDING + SEARCH_DROPDOWN_ROW_HEIGHT + SEARCH_DROPDOWN_SECTION_GAP + 1 + SEARCH_DROPDOWN_ROW_HEIGHT + (visibleRows * SEARCH_DROPDOWN_ROW_HEIGHT) + SEARCH_DROPDOWN_PADDING;
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private int getSearchDropdownX() {
        return searchInput.getX();
    }

    private int getSearchDropdownY() {
        return searchInput.getY() + searchInput.getHeight() + 2;
    }

    private int getSearchDropdownWidth() {
        return searchInput.getWidth();
    }

    private String getDocumentTextSafe() {
        return document == null ? "" : document.getText();
    }

    private List<String> getDocumentLinesSafe() {
        return document == null ? Collections.emptyList() : document.getLines();
    }

    private boolean isInsideSmallButton(double mouseX, double mouseY, int x, int y, int width) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 14;
    }

    private boolean isInsideTextButton(double mouseX, double mouseY, int x, int y, String label) {
        int width = Math.max(36, this.textRenderer.getWidth(label) + 12);
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 14;
    }

    private void selectWordAt(int lineIndex, int column) {
        String line = document.getLine(lineIndex);
        if (line.isEmpty()) {
            document.setCursor(lineIndex, 0, false);
            return;
        }

        int safeColumn = clamp(column, 0, line.length());
        int left = safeColumn;
        int right = safeColumn;
        while (left > 0 && isWordChar(line.charAt(left - 1))) {
            left--;
        }
        while (right < line.length() && isWordChar(line.charAt(right))) {
            right++;
        }
        document.selectRange(lineIndex, left, lineIndex, right);
    }

    private void selectLineAt(int lineIndex) {
        String line = document.getLine(lineIndex);
        document.selectRange(lineIndex, 0, lineIndex, line.length());
    }

    private boolean isInsideEditorViewport(double mouseX, double mouseY) {
        int viewportX = getEditorAreaX();
        int viewportY = CONTENT_TOP + EDITOR_HEADER_HEIGHT;
        int viewportWidth = this.width - viewportX - 1;
        int viewportHeight = this.height - viewportY - STATUS_BAR_HEIGHT - 1;
        return mouseX >= viewportX && mouseX <= viewportX + viewportWidth && mouseY >= viewportY && mouseY <= viewportY + viewportHeight;
    }

    private void renderEditorScrollbar(DrawContext context, int viewportX, int viewportY, int viewportWidth, int viewportHeight) {
        if (getMaxVerticalScroll() <= 0) {
            return;
        }
        int trackX = viewportX + viewportWidth - 6;
        context.fill(trackX, viewportY + 2, trackX + 3, viewportY + viewportHeight - 2, new Color(uiColorFileEditorScrollbarTrack, true).getRGB());
        int thumbY = getEditorScrollbarThumbY();
        int thumbHeight = getEditorScrollbarThumbHeight();
        context.fill(trackX - 1, thumbY, trackX + 4, thumbY + thumbHeight, new Color(uiColorFileEditorScrollbarThumb, true).getRGB());
    }

    private boolean isOverEditorScrollbar(double mouseX, double mouseY) {
        if (getMaxVerticalScroll() <= 0) {
            return false;
        }
        int viewportX = getEditorAreaX();
        int viewportY = CONTENT_TOP + EDITOR_HEADER_HEIGHT;
        int viewportWidth = this.width - viewportX - 1;
        int viewportHeight = this.height - viewportY - STATUS_BAR_HEIGHT - 1;
        int trackX = viewportX + viewportWidth - 6;
        return mouseX >= trackX - 5
                && mouseX <= trackX + 8
                && mouseY >= viewportY
                && mouseY <= viewportY + viewportHeight;
    }

    private int getEditorScrollbarThumbHeight() {
        int viewportHeight = this.height - (CONTENT_TOP + EDITOR_HEADER_HEIGHT) - STATUS_BAR_HEIGHT - 1;
        int totalLines = Math.max(1, document.getLineCount());
        int visibleLines = Math.max(1, getVisibleLineCount());
        return Math.max(18, (int) ((visibleLines / (float) totalLines) * viewportHeight));
    }

    private int getEditorScrollbarThumbY() {
        int viewportY = CONTENT_TOP + EDITOR_HEADER_HEIGHT;
        int viewportHeight = this.height - viewportY - STATUS_BAR_HEIGHT - 1;
        int thumbHeight = getEditorScrollbarThumbHeight();
        int thumbTravel = Math.max(1, viewportHeight - thumbHeight);
        return viewportY + (int) ((verticalScrollOffset / (float) getMaxVerticalScroll()) * thumbTravel);
    }

    private void setEditorScrollFromThumbTop(int thumbTop) {
        int viewportY = CONTENT_TOP + EDITOR_HEADER_HEIGHT;
        int viewportHeight = this.height - viewportY - STATUS_BAR_HEIGHT - 1;
        int thumbHeight = getEditorScrollbarThumbHeight();
        int minTop = viewportY;
        int maxTop = viewportY + viewportHeight - thumbHeight;
        int clampedTop = clamp(thumbTop, minTop, maxTop);
        int travel = Math.max(1, maxTop - minTop);
        float ratio = (clampedTop - minTop) / (float) travel;
        verticalScrollOffset = clamp(Math.round(ratio * getMaxVerticalScroll()), 0, getMaxVerticalScroll());
        updateEditorSuggestions();
    }

    private EditorPoint resolvePointFromMouse(double mouseX, double mouseY) {
        int viewportY = CONTENT_TOP + EDITOR_HEADER_HEIGHT;
        int textStartX = getEditorAreaX() + getGutterWidth() + 10;
        int lineHeight = this.textRenderer.fontHeight + 2;
        int relativeLine = Math.max(0, (int) ((mouseY - (viewportY + 6)) / lineHeight));
        int lineIndex = clamp(verticalScrollOffset + relativeLine, 0, document.getLineCount() - 1);
        String line = document.getLine(lineIndex);
        int column = resolveCursorColumnFromMouse(line, mouseX, textStartX);
        return new EditorPoint(lineIndex, column);
    }

    private int resolveCursorColumnFromMouse(String line, double mouseX, int textStartX) {
        int targetWidth = (int) Math.max(0, mouseX - textStartX + horizontalScrollOffset);
        for (int column = 0; column <= line.length(); column++) {
            int width = this.textRenderer.getWidth(line.substring(0, column));
            if (width >= targetWidth) {
                if (column == 0) {
                    return 0;
                }
                int previousWidth = this.textRenderer.getWidth(line.substring(0, column - 1));
                return Math.abs(targetWidth - previousWidth) <= Math.abs(width - targetWidth) ? column - 1 : column;
            }
        }
        return line.length();
    }

    private void ensureCursorVisible() {
        int visibleLineCount = getVisibleLineCount();
        if (document.getCursorLine() < verticalScrollOffset) {
            verticalScrollOffset = document.getCursorLine();
        } else if (document.getCursorLine() >= verticalScrollOffset + visibleLineCount) {
            verticalScrollOffset = document.getCursorLine() - visibleLineCount + 1;
        }

        String line = document.getLine(document.getCursorLine());
        int caretWidth = this.textRenderer.getWidth(line.substring(0, Math.min(document.getCursorColumn(), line.length())));
        int viewportWidth = getEditorViewportWidth() - getGutterWidth() - 16;
        if (caretWidth < horizontalScrollOffset) {
            horizontalScrollOffset = Math.max(0, caretWidth - 24);
        } else if (caretWidth > horizontalScrollOffset + viewportWidth - 20) {
            horizontalScrollOffset = Math.max(0, caretWidth - viewportWidth + 20);
        }

        verticalScrollOffset = clamp(verticalScrollOffset, 0, getMaxVerticalScroll());
        horizontalScrollOffset = clamp(horizontalScrollOffset, 0, getMaxHorizontalScroll());
        updateEditorSuggestions();
    }

    private int getVisibleLineCount() {
        int viewportHeight = this.height - (CONTENT_TOP + EDITOR_HEADER_HEIGHT) - STATUS_BAR_HEIGHT - 12;
        return Math.max(1, viewportHeight / (this.textRenderer.fontHeight + 2));
    }

    private int getMaxVerticalScroll() {
        return Math.max(0, document.getLineCount() - getVisibleLineCount());
    }

    private int getMaxHorizontalScroll() {
        return Math.max(0, getLongestLineWidth() - (getEditorViewportWidth() - getGutterWidth() - 20));
    }

    private int getLongestLineWidth() {
        if (cachedLongestLineWidth >= 0) {
            return cachedLongestLineWidth;
        }
        int longest = 0;
        for (String line : document.getLines()) {
            longest = Math.max(longest, this.textRenderer.getWidth(line));
        }
        cachedLongestLineWidth = longest;
        return longest;
    }

    private int getGutterWidth() {
        return Math.max(40, this.textRenderer.getWidth(String.valueOf(document.getLineCount())) + 16);
    }

    private int getEditorAreaX() {
        return SIDEBAR_WIDTH + 2;
    }

    private int getEditorViewportWidth() {
        return this.width - getEditorAreaX() - 1;
    }

    private List<String> getTopBarActionLabels() {
        return List.of(TOP_BAR_CHOOSE_LABEL, TOP_BAR_SAVE_LABEL, TOP_BAR_CLEAR_LABEL, TOP_BAR_RELOAD_LABEL);
    }

    private TopBarLayout getTopBarLayout() {
        return new TopBarLayout(this.textRenderer, this.width);
    }

    private int getTopBarChooseButtonX() {
        return getTopBarLayout().rightButtonX(getTopBarActionLabels(), 0);
    }

    private int getTopBarSaveButtonX() {
        return getTopBarLayout().rightButtonX(getTopBarActionLabels(), 1);
    }

    private int getTopBarClearButtonX() {
        return getTopBarLayout().rightButtonX(getTopBarActionLabels(), 2);
    }

    private int getTopBarReloadButtonX() {
        return getTopBarLayout().rightButtonX(getTopBarActionLabels(), 3);
    }

    private int getTopBarButtonWidth(String label) {
        return getTopBarLayout().buttonWidth(label);
    }

    private boolean isWithinBounds(int[] bounds, double mouseX, double mouseY) {
        return bounds != null
                && mouseX >= bounds[0]
                && mouseX <= bounds[0] + bounds[2]
                && mouseY >= bounds[1]
                && mouseY <= bounds[1] + bounds[3];
    }

    private List<MenuEntry> buildTopBarOpenMenuItems() {
        return buildOpenMenuItemsForTarget(getActiveOpenTarget());
    }

    private List<MenuEntry> buildOpenMenuItemsForTarget(File target) {
        List<MenuEntry> items = new ArrayList<>();
        if (target == null) {
            return items;
        }
        if (target.isDirectory()) {
            items.add(new MenuEntry("open_folder", "Folder"));
            return items;
        }
        if (isConfigEditorCandidate(target)) {
            items.add(new MenuEntry("open_config", "in Config"));
        }
        items.add(new MenuEntry("open_folder", "in Explorer"));
        items.add(new MenuEntry("reveal_parent", "Parent in Explorer"));
        items.add(new MenuEntry("open_system", "with Default Application "));
        items.add(new MenuEntry("open_file_path", "File Path"));
        items.add(new MenuEntry("open_from_computer", "From Computer"));
        return items;
    }

    private File getActiveOpenTarget() {
        if (fileItem != null) {
            return fileItem;
        }
        return new File(".");
    }

    private void handleTopBarOpenAction(String actionId) {
        File target = getActiveOpenTarget();
        switch (actionId) {
            case "open_from_computer" -> openEditorFileChooser();
            case "open_config" -> {
                if (target != null && target.isFile() && isConfigEditorCandidate(target) && this.client != null && prepareForDocumentSwap()) {
                    this.client.setScreen(new ModConfigScreen(this.parent, null, new File[]{target}));
                }
            }
            case "open_folder", "reveal_parent" -> {
                if (target != null) {
                    File parentTarget = target.isDirectory() ? target : target.getParentFile();
                    if (parentTarget != null && this.client != null && prepareForDocumentSwap()) {
                        this.client.setScreen(openExplorerAtPath(target.getPath()));
                    }
                }
            }
            case "open_system" -> {
                if (target != null) {
                    Util.getOperatingSystem().open(target);
                }
            }
            case "open_file_path" -> {
                if (target != null) {
                    copyPathToClipboard(target);
                }
            }
        }
        UiSoundHelper.playButtonClick();
    }

    private void copyPathToClipboard(File target) {
        if (target == null || this.client == null) {
            return;
        }
        this.client.keyboard.setClipboard(target.getAbsolutePath());
        saveStatusMessage = "Copied path";
        saveStatusColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
        saveStatusUntil = Util.getMeasuringTimeMs() + 1200L;
    }

    private void openEditorFileChooser() {
        String chosenPath = TinyFileDialogs.tinyfd_openFileDialog(
                "Open File",
                fileItem != null ? fileItem.getAbsolutePath() : new File(".").getAbsolutePath(),
                null,
                null,
                false
        );
        if (chosenPath == null || chosenPath.isBlank() || this.client == null) {
            return;
        }

        openChosenPath(new File(chosenPath));
    }

    private void openChosenPath(File chosenFile) {
        if (chosenFile == null || this.client == null) {
            return;
        }
        if (!chosenFile.exists()) {
            setErrorState("Open failed", "Chosen file does not exist", 3500L);
            return;
        }
        if (!prepareForDocumentSwap()) {
            return;
        }

        if (chosenFile.isDirectory()) {
            this.client.setScreen(openExplorerAtPath(chosenFile.getPath()));
            return;
        }

        FileType fileType = classifyFileType(chosenFile);
        if (isConfigEditorCandidate(chosenFile)) {
            this.client.setScreen(new ModConfigScreen(this.parent, null, new File[]{chosenFile}));
            return;
        }
        if (isEditableTextType(fileType) && isTextFile(chosenFile)) {
            this.client.setScreen(new FileEditorScreen(this.parent, chosenFile, new FileItem(chosenFile.getName(), fileType, chosenFile)));
            return;
        }

        this.client.setScreen(openExplorerAtPath(chosenFile.getPath()));
    }

    private Screen resolveReturnScreen() {
        if (this.parent instanceof FileExplorerScreen) {
            return openExplorerAtPath(fileItem == null ? "." : fileItem.getPath());
        }
        return getNavigationParentScreen();
    }

    private FileExplorerScreen openExplorerAtPath(String path) {
        if (this.parent instanceof FileExplorerScreen explorerParent) {
            return FileExplorerScreen.openAtPath(path, explorerParent.getReturnParentScreen());
        }
        return FileExplorerScreen.openAtPath(path, getNavigationParentScreen());
    }

    public Screen getNavigationParentScreen() {
        if (this.parent instanceof FileExplorerScreen explorerParent) {
            Screen explorerReturnParent = explorerParent.getReturnParentScreen();
            if (explorerReturnParent instanceof FileEditorScreen editorReturnParent) {
                return editorReturnParent.getNavigationParentScreen();
            }
            return explorerReturnParent;
        }
        if (this.parent instanceof FileEditorScreen editorParent) {
            return editorParent.getNavigationParentScreen();
        }
        return this.parent;
    }

    private static Screen sanitizeDirectParent(Screen parent) {
        if (parent instanceof FileEditorScreen editorParent) {
            return editorParent.getNavigationParentScreen();
        }
        return parent;
    }

    private boolean prepareForDocumentSwap() {
        if (document == null || !isDirty()) {
            return true;
        }
        return saveFileContent();
    }

    private boolean isConfigEditorCandidate(File target) {
        if (target == null || !target.isFile()) {
            return false;
        }
        String name = target.getName();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex >= name.length() - 1) {
            return false;
        }
        return CONFIG_EDITOR_EXTENSIONS.contains(name.substring(dotIndex + 1).toLowerCase(Locale.ROOT));
    }

    private FileType classifyFileType(File file) {
        return TextFileViewSupport.classifyFileType(file);
    }

    private boolean isEditableTextType(FileType fileType) {
        return TextFileViewSupport.isEditableTextType(fileType);
    }

    private boolean isTextFile(File file) {
        return TextFileViewSupport.isTextFile(file);
    }

    private boolean isDirty() {
        return document != null && !document.getText().equals(lastSavedText);
    }

    private String getSearchSummary() {
        if (parsedSearchCommand.type() == SearchCommandType.GO_TO_LINE) {
            return "Go to line " + parsedSearchCommand.lineNumber();
        }
        if (parsedSearchCommand.query().isBlank()) {
            return "Idle";
        }
        if (!searchErrorMessage.isEmpty()) {
            return "Invalid regex";
        }
        if (searchMatches.isEmpty()) {
            return "0 results";
        }
        return searchMatches.size() + " results | " + (activeSearchMatchIndex + 1) + " active";
    }

    private String getSearchModeSummary() {
        List<String> modes = new ArrayList<>();
        if (searchCaseSensitive) {
            modes.add("case");
        }
        if (searchWholeWord) {
            modes.add("word");
        }
        if (searchRegex) {
            modes.add("regex");
        }
        if (searchHighlightOnly) {
            modes.add("highlight");
        }
        if (searchReplaceVisible) {
            modes.add("replace");
        }
        if (modes.isEmpty()) {
            return "Modes: normal";
        }
        return "Modes: " + String.join(", ", modes);
    }

    private String getSyntaxHealthSummary(String language) {
        String text = getDocumentTextSafe();
        if (text.isBlank()) {
            return "empty";
        }

        int braces = 0;
        int brackets = 0;
        int parens = 0;
        boolean inString = false;
        char stringChar = 0;
        boolean escaped = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == stringChar) {
                    inString = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                stringChar = c;
                continue;
            }
            switch (c) {
                case '{' -> braces++;
                case '}' -> braces--;
                case '[' -> brackets++;
                case ']' -> brackets--;
                case '(' -> parens++;
                case ')' -> parens--;
                default -> {
                }
            }
            if (braces < 0 || brackets < 0 || parens < 0) {
                return "possible mismatch";
            }
        }

        if (inString || braces != 0 || brackets != 0 || parens != 0) {
            return "possible mismatch";
        }

        String normalized = language == null ? "" : language.trim().toUpperCase();
        return normalized.equals("JSON") || normalized.equals("XML") || normalized.equals("JAVA") ? "balanced" : "clean";
    }

    private int getSyntaxHealthColor(String language) {
        String summary = getSyntaxHealthSummary(language);
        if (summary.equals("possible mismatch")) {
            return new Color(uiColorWarningPromptText, true).getRGB();
        }
        if (summary.equals("empty")) {
            return new Color(uiColorHeaderSubTitleText, true).getRGB();
        }
        return new Color(uiColorContentBaseTitleText, true).getRGB();
    }

    private String getStructureSummary(String language) {
        String text = getDocumentTextSafe();
        if (text.isBlank()) {
            return "empty document";
        }

        String lowerLanguage = language == null ? "" : language.toLowerCase();
        if (lowerLanguage.equals("java")) {
            String pkg = findFirstMatch(text, "(?m)^\\s*package\\s+([\\w.]+)\\s*;");
            String type = findFirstMatch(text, "(?m)^\\s*(public\\s+)?(class|interface|enum|record)\\s+(\\w+)");
            if (!pkg.isEmpty() && !type.isEmpty()) {
                return pkg + " | " + type;
            }
            return !type.isEmpty() ? type : "source file";
        }
        if (lowerLanguage.equals("json")) {
            String trimmed = text.trim();
            if (trimmed.startsWith("{")) {
                return "root object";
            }
            if (trimmed.startsWith("[")) {
                return "root array";
            }
            return "unknown root";
        }
        if (lowerLanguage.equals("xml")) {
            String tag = findFirstMatch(text, "<\\s*([A-Za-z0-9_:\\-]+)");
            return tag.isEmpty() ? "document" : "root <" + tag + ">";
        }
        if (lowerLanguage.equals("yaml")) {
            String key = findFirstMatch(text, "(?m)^([A-Za-z0-9_\\-]+):");
            return key.isEmpty() ? "document" : "top key " + key;
        }
        if (lowerLanguage.equals("toml")) {
            String section = findFirstMatch(text, "(?m)^\\s*\\[([^\\]]+)]");
            return section.isEmpty() ? "document" : "section [" + section + "]";
        }
        return "plain document";
    }

    private String getIndentSummary() {
        int tabLines = 0;
        int twoSpaceLines = 0;
        int fourSpaceLines = 0;
        int mixedLines = 0;

        for (String line : getDocumentLinesSafe()) {
            if (line.isBlank()) {
                continue;
            }
            int spaces = 0;
            int tabs = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == ' ') {
                    spaces++;
                } else if (c == '\t') {
                    tabs++;
                } else {
                    break;
                }
            }

            if (tabs > 0 && spaces > 0) {
                mixedLines++;
            } else if (tabs > 0) {
                tabLines++;
            } else if (spaces >= 4 && spaces % 4 == 0) {
                fourSpaceLines++;
            } else if (spaces >= 2 && spaces % 2 == 0) {
                twoSpaceLines++;
            }
        }

        if (mixedLines > 0) {
            return "mixed";
        }
        if (tabLines > 0 && tabLines >= twoSpaceLines && tabLines >= fourSpaceLines) {
            return "tabs";
        }
        if (fourSpaceLines > 0 && fourSpaceLines >= twoSpaceLines) {
            return "4 spaces";
        }
        if (twoSpaceLines > 0) {
            return "2 spaces";
        }
        return "none";
    }

    private String getSearchToolSummary() {
        if (parsedSearchCommand == null || parsedSearchCommand.query().isBlank()) {
            return "idle";
        }
        StringBuilder builder = new StringBuilder(getSearchSummary());
        if (searchReplaceVisible) {
            builder.append(" | replace");
        }
        if (searchRegex) {
            builder.append(" | regex");
        } else if (searchWholeWord) {
            builder.append(" | whole word");
        }
        return builder.toString();
    }

    private String getSymbolSummary(String language) {
        String text = getDocumentTextSafe();
        if (text.isBlank()) {
            return "0";
        }

        String lowerLanguage = language == null ? "" : language.toLowerCase();
        if (lowerLanguage.equals("java")) {
            int imports = countMatches(text, "(?m)^\\s*import\\s+");
            int methods = countMatches(text, "(?m)^\\s*(public|protected|private|static|final|synchronized|native|abstract|default|\\s)+[\\w<>\\[\\]]+\\s+\\w+\\s*\\(");
            return imports + " imports | " + methods + " methods";
        }
        if (lowerLanguage.equals("json")) {
            return countMatches(text, "\"[^\"]+\"\\s*:") + " keys";
        }
        if (lowerLanguage.equals("xml")) {
            return countMatches(text, "<\\s*[A-Za-z0-9_:\\-]+") + " tags";
        }
        if (lowerLanguage.equals("yaml")) {
            return countMatches(text, "(?m)^\\s*[A-Za-z0-9_\\-]+:") + " keys";
        }
        if (lowerLanguage.equals("toml")) {
            return countMatches(text, "(?m)^\\s*\\[[^\\]]+\\]") + " sections";
        }
        return countMatches(text, "\\b[A-Za-z_][A-Za-z0-9_]*\\b") + " tokens";
    }

    private String getTodoSummary() {
        String text = getDocumentTextSafe();
        if (text.isBlank()) {
            return "none";
        }
        int todos = countMatches(text, "(?i)\\bTODO\\b");
        int fixmes = countMatches(text, "(?i)\\bFIXME\\b");
        int bugs = countMatches(text, "(?i)\\bBUG\\b");
        int total = todos + fixmes + bugs;
        return total == 0 ? "none" : total + " flags";
    }


    private void rebuildLineDiagnostics() {
        lineDiagnostics.clear();
        cachedErrorCount = 0;
        cachedWarningCount = 0;
        if (document == null) {
            return;
        }
        for (int i = 0; i < document.getLineCount(); i++) {
            lineDiagnostics.add(computeLineDiagnostic(i));
        }
        applyStructuralDocumentDiagnostics();
        applyDuplicateKeyDiagnostics();
        applyStyleDiagnostics();
        for (EditorDiagnostic diagnostic : lineDiagnostics) {
            if (diagnostic == null) {
                continue;
            }
            if (diagnostic.severity() == DiagnosticSeverity.ERROR) {
                cachedErrorCount++;
            } else {
                cachedWarningCount++;
            }
        }
    }


    private void applyDuplicateKeyDiagnostics() {
        if (document == null) {
            return;
        }
        EditorSyntaxHighlighter.SyntaxLanguage language = EditorSyntaxHighlighter.SyntaxLanguage.fromFileName(fileItem.getName());
        if (!(language == EditorSyntaxHighlighter.SyntaxLanguage.PROPERTIES
                || language == EditorSyntaxHighlighter.SyntaxLanguage.CONFIG
                || language == EditorSyntaxHighlighter.SyntaxLanguage.TOML
                || language == EditorSyntaxHighlighter.SyntaxLanguage.YAML)) {
            return;
        }
        Map<String, Integer> firstSeenByKey = new LinkedHashMap<>();
        String currentSection = "";
        for (int lineIndex = 0; lineIndex < document.getLineCount(); lineIndex++) {
            String rawLine = document.getLine(lineIndex);
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String nextSection = extractSectionName(language, trimmed);
            if (!nextSection.isEmpty()) {
                currentSection = nextSection;
                continue;
            }
            String key = extractDuplicateDiagnosticKey(language, trimmed);
            if (key.isEmpty()) {
                continue;
            }
            String scopedKey = currentSection + "::" + key.toLowerCase(Locale.ROOT);
            Integer firstSeen = firstSeenByKey.putIfAbsent(scopedKey, lineIndex);
            if (firstSeen != null && getLineDiagnostic(lineIndex) == null) {
                String sectionLabel = currentSection.isEmpty() ? "the current file" : "section '" + currentSection + "'";
                lineDiagnostics.set(lineIndex, warning(
                        "Duplicate key '" + key + "'",
                        "This key already appears on line " + (firstSeen + 1) + " in " + sectionLabel + ".",
                        "Keep only the intended value so the file stays predictable."
                ));
            }
        }
    }

    private void applyStyleDiagnostics() {
        if (document == null) {
            return;
        }
        for (int lineIndex = 0; lineIndex < document.getLineCount(); lineIndex++) {
            if (getLineDiagnostic(lineIndex) != null) {
                continue;
            }
            EditorDiagnostic styleDiagnostic = getStyleDiagnostic(lineIndex, document.getLine(lineIndex));
            if (styleDiagnostic != null) {
                lineDiagnostics.set(lineIndex, styleDiagnostic);
            }
        }
    }

    private EditorDiagnostic getStyleDiagnostic(int lineIndex, String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        if (hasTrailingWhitespace(line)) {
            return warning(
                    "Trailing whitespace",
                    "This line ends with extra spaces or tabs that can create noisy diffs and accidental formatting changes.",
                    "Trim the line ending to keep the file clean.",
                    new EditorQuickClick("Trim line ending", QuickClickKind.TRIM_LINE_ENDING, "")
            );
        }
        if (startsWithTabIndent(line)) {
            String replacement = buildLeadingIndentReplacement(line);
            if (!replacement.isEmpty()) {
                return warning(
                        "Tab indentation detected",
                        "This line starts with tab indentation, which can render unevenly across editors and previews.",
                        "Replace the leading tabs with spaces for steadier alignment.",
                        new EditorQuickClick("Convert leading tabs to spaces", QuickClickKind.REPLACE_LEADING_INDENT, replacement)
                );
            }
        }
        if (line.length() > 180) {
            return warning(
                    "Very long line",
                    "This line is " + line.length() + " characters long, which makes scanning and debugging harder inside the editor.",
                    "Consider wrapping or splitting the line for readability."
            );
        }
        String trimmed = line.trim();
        if (trimmed.matches("(?i).*(\\bTODO\\b|\\bFIXME\\b|\\bBUG\\b).*")) {
            return warning(
                    "Review marker found",
                    "This line contains a TODO, FIXME, or BUG marker that likely still needs attention.",
                    "Use it as a checkpoint, or clear it once the issue is resolved."
            );
        }
        return null;
    }

    private boolean hasTrailingWhitespace(String line) {
        return !line.isEmpty() && (line.endsWith(" ") || line.endsWith("\t"));
    }

    private boolean startsWithTabIndent(String line) {
        return !line.isEmpty() && line.charAt(0) == '\t';
    }

    private String buildLeadingIndentReplacement(String line) {
        int tabCount = 0;
        while (tabCount < line.length() && line.charAt(tabCount) == '\t') {
            tabCount++;
        }
        return "    ".repeat(Math.max(0, tabCount));
    }

    private String extractSectionName(EditorSyntaxHighlighter.SyntaxLanguage language, String trimmed) {
        if (trimmed.isEmpty()) {
            return "";
        }
        if (language == EditorSyntaxHighlighter.SyntaxLanguage.TOML) {
            Matcher matcher = Pattern.compile("^\\[\\[?\\s*([^\\]]+?)\\s*\\]\\]?$").matcher(trimmed);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        if (language == EditorSyntaxHighlighter.SyntaxLanguage.YAML) {
            Matcher matcher = Pattern.compile("^([A-Za-z0-9_.\\-]+)\\s*:\\s*$").matcher(trimmed);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return "";
    }

    private String extractDuplicateDiagnosticKey(EditorSyntaxHighlighter.SyntaxLanguage language, String trimmed) {
        if (trimmed.isEmpty()) {
            return "";
        }
        if (language == EditorSyntaxHighlighter.SyntaxLanguage.PROPERTIES || language == EditorSyntaxHighlighter.SyntaxLanguage.CONFIG) {
            if (trimmed.startsWith("#") || trimmed.startsWith("!") || trimmed.startsWith("[") || trimmed.startsWith(";")) {
                return "";
            }
            Matcher matcher = Pattern.compile("^\s*([^:=\s][^:=]*?)\s*[:=]").matcher(trimmed);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
            return "";
        }
        if (language == EditorSyntaxHighlighter.SyntaxLanguage.TOML) {
            if (trimmed.startsWith("#") || trimmed.startsWith("[[" ) || trimmed.startsWith("[")) {
                return "";
            }
            Matcher matcher = Pattern.compile("^\\s*([A-Za-z0-9_.\\-]+)\\s*=").matcher(trimmed);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
            return "";
        }
        if (language == EditorSyntaxHighlighter.SyntaxLanguage.YAML) {
            if (trimmed.startsWith("#") || trimmed.startsWith("- ") || trimmed.startsWith("? ") || trimmed.startsWith(": ")) {
                return "";
            }
            Matcher matcher = Pattern.compile("^\\s*([A-Za-z0-9_.\\-]+)\\s*:").matcher(trimmed);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
            return "";
        }
        return "";
    }

    private EditorDiagnostic getLineDiagnostic(int lineIndex) {
        if (lineIndex < 0 || lineIndex >= lineDiagnostics.size()) {
            return null;
        }
        return lineDiagnostics.get(lineIndex);
    }

    private EditorDiagnostic computeLineDiagnostic(int lineIndex) {
        String line = document.getLine(lineIndex);
        if (line.isBlank()) {
            return null;
        }
        EditorSyntaxHighlighter.SyntaxLanguage language = EditorSyntaxHighlighter.SyntaxLanguage.fromFileName(fileItem.getName());
        EditorDiagnostic quoteDiagnostic = getQuoteDiagnostic(language, line);
        if (quoteDiagnostic != null) {
            return quoteDiagnostic;
        }

        EditorDiagnostic languageDiagnostic = getLanguageLineDiagnostic(language, lineIndex, line);
        if (languageDiagnostic != null) {
            return languageDiagnostic;
        }

        return null;
    }

    private void applyStructuralDocumentDiagnostics() {
        if (document == null) {
            return;
        }
        EditorSyntaxHighlighter.SyntaxLanguage language = EditorSyntaxHighlighter.SyntaxLanguage.fromFileName(fileItem.getName());
        if (!supportsStructuralBalanceDiagnostics(language)) {
            return;
        }
        List<OpenSymbol> stack = new ArrayList<>();
        for (int lineIndex = 0; lineIndex < document.getLineCount(); lineIndex++) {
            String structuralLine = stripQuotedAndCommentedContent(language, document.getLine(lineIndex));
            for (int i = 0; i < structuralLine.length(); i++) {
                char c = structuralLine.charAt(i);
                if (c == '(' || c == '{' || c == '[') {
                    stack.add(new OpenSymbol(c, lineIndex));
                } else if (c == ')' || c == '}' || c == ']') {
                    if (stack.isEmpty()) {
                        applyStructuralDiagnostic(lineIndex, error(
                                "Closing " + getSymbolName(c) + " has no opener",
                                "This '" + c + "' does not have any earlier matching opener in the file.",
                                "Remove it or add the missing '" + getMatchingOpenSymbol(c) + "' before this point.",
                                new EditorQuickClick("Remove stray '" + c + "'", QuickClickKind.REMOVE_FIRST_CLOSER, String.valueOf(c))
                        ));
                        continue;
                    }
                    OpenSymbol last = stack.get(stack.size() - 1);
                    if (matchesPair(last.symbol(), c)) {
                        stack.remove(stack.size() - 1);
                    } else {
                        applyStructuralDiagnostic(lineIndex, error(
                                "Mismatched closing " + getSymbolName(c),
                                "This '" + c + "' tries to close a '" + last.symbol() + "' opened on line " + (last.line() + 1) + ".",
                                "Use '" + getMatchingCloseSymbol(last.symbol()) + "' here, or fix the opener on line " + (last.line() + 1) + ".",
                                new EditorQuickClick("Replace with '" + getMatchingCloseSymbol(last.symbol()) + "'", QuickClickKind.REPLACE_FIRST_CLOSER, "" + c + getMatchingCloseSymbol(last.symbol()))
                        ));
                    }
                }
            }
        }
        for (OpenSymbol open : stack) {
            applyStructuralDiagnostic(open.line(), error(
                    "Opened " + getSymbolName(open.symbol()) + " never closes",
                    "A '" + open.symbol() + "' opened on this line does not appear to close later in the file.",
                    "Add '" + getMatchingCloseSymbol(open.symbol()) + "' later in the file or remove the unmatched opener."
            ));
        }
    }

    private void applyStructuralDiagnostic(int lineIndex, EditorDiagnostic diagnostic) {
        if (lineIndex < 0 || lineIndex >= lineDiagnostics.size() || diagnostic == null) {
            return;
        }
        EditorDiagnostic existing = lineDiagnostics.get(lineIndex);
        if (existing == null
                || existing.severity() == DiagnosticSeverity.WARNING
                || existing.summary().startsWith("Opened ")
                || existing.summary().startsWith("Closing ")
                || existing.summary().startsWith("Mismatched closing ")) {
            lineDiagnostics.set(lineIndex, diagnostic);
        }
    }

    private boolean matchesPair(char open, char close) {
        return (open == '(' && close == ')')
                || (open == '{' && close == '}')
                || (open == '[' && close == ']');
    }

    private String getSymbolName(char symbol) {
        return switch (symbol) {
            case '(' , ')' -> "parenthesis";
            case '{' , '}' -> "brace";
            case '[' , ']' -> "bracket";
            default -> "symbol";
        };
    }

    private char getMatchingOpenSymbol(char close) {
        return switch (close) {
            case ')' -> '(';
            case '}' -> '{';
            case ']' -> '[';
            default -> '?';
        };
    }

    private char getMatchingCloseSymbol(char open) {
        return switch (open) {
            case '(' -> ')';
            case '{' -> '}';
            case '[' -> ']';
            default -> '?';
        };
    }

    private EditorDiagnostic getQuoteDiagnostic(EditorSyntaxHighlighter.SyntaxLanguage language, String line) {
        String structuralLine = stripCommentContent(language, line);
        if (supportsDoubleQuoteDiagnostics(language) && hasOddQuoteCount(structuralLine, '"')) {
            return error("String appears to start but not close", "This line contains an unmatched double quote.", "Close the string or escape the quote if it belongs inside the value.");
        }
        if (supportsSingleQuoteDiagnostics(language) && hasOddQuoteCount(structuralLine, '\'')) {
            return error("Single-quoted value appears to start but not close", "This line contains an unmatched single quote.", "Close the string or escape the quote if needed.");
        }
        return null;
    }

    private boolean supportsDoubleQuoteDiagnostics(EditorSyntaxHighlighter.SyntaxLanguage language) {
        return switch (language) {
            case JSON, XML, TOML, PROPERTIES, CONFIG, CSS, SHADER, C, CPP, CSHARP, PYTHON -> true;
            case YAML, KTL, JAVA, JAVASCRIPT, MARKDOWN, TEXT, LOG -> false;
        };
    }

    private boolean supportsSingleQuoteDiagnostics(EditorSyntaxHighlighter.SyntaxLanguage language) {
        return switch (language) {
            case TOML, C, CPP, CSHARP, PYTHON -> true;
            default -> false;
        };
    }

    private boolean supportsStructuralBalanceDiagnostics(EditorSyntaxHighlighter.SyntaxLanguage language) {
        return switch (language) {
            case JSON, XML, JAVA, JAVASCRIPT, TOML, CSS, SHADER, C, CPP, CSHARP, PYTHON -> true;
            case PROPERTIES, CONFIG, YAML, KTL, MARKDOWN, TEXT, LOG -> false;
        };
    }

    private EditorDiagnostic getLanguageLineDiagnostic(EditorSyntaxHighlighter.SyntaxLanguage language, int lineIndex, String line) {
        return switch (language) {
            case JSON -> getJsonLineDiagnostic(lineIndex, line);
            case XML -> getXmlLineDiagnostic(lineIndex, line);
            case YAML, KTL -> getYamlLineDiagnostic(lineIndex, line);
            case TOML, PROPERTIES, CONFIG -> getKeyValueLineDiagnostic(language, line);
            case JAVA, JAVASCRIPT, CSS, SHADER, C, CPP, CSHARP, PYTHON -> getCodeLineDiagnostic(language, lineIndex, line);
            default -> null;
        };
    }

    private EditorDiagnostic getJsonLineDiagnostic(int lineIndex, String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith(",")) {
            return error("JSON value starts with an unexpected comma", "This line begins with a comma before any value or key.", "Remove the leading comma or complete the previous line correctly.",
                    new EditorQuickClick("Remove stray comma", QuickClickKind.REMOVE_FIRST_CLOSER, ","));
        }
        if (trimmed.endsWith(",") && nextSignificantLineStartsWith(lineIndex, "}", "]")) {
            return error("Trailing comma before closing JSON block", "JSON does not allow a trailing comma immediately before '}' or ']'.", "Remove the comma or add another entry after it.",
                    new EditorQuickClick("Remove trailing comma", QuickClickKind.REMOVE_LINE_SUFFIX, ","));
        }
        if (trimmed.matches("^\"(?:\\\\.|[^\"\\\\])+\"\\s*$") && !isInsideJsonArrayContext(lineIndex)) {
            return error("Quoted JSON key appears to be missing ':'", "This line looks like a key without a value separator.", "Add ':' followed by a valid JSON value.",
                    new EditorQuickClick("Add ':' separator", QuickClickKind.APPEND_TO_LINE, ": "));
        }
        if (trimmed.matches("^\"(?:\\\\.|[^\"\\\\])+\"\\s*:\\s*$")) {
            return error("JSON key is missing a value", "This key has a ':' but no value after it.", "Add a valid JSON value such as a string, number, boolean, object, or array.");
        }
        if (looksLikeCompleteJsonEntry(trimmed) && nextJsonLineNeedsComma(lineIndex)) {
            return error("JSON entry appears to be missing a comma", "This entry is followed by another JSON entry but does not end with ','.", "Add a comma to separate adjacent JSON entries.",
                    new EditorQuickClick("Add comma", QuickClickKind.APPEND_TO_LINE, ","));
        }
        return null;
    }

    private boolean isInsideJsonArrayContext(int lineIndex) {
        List<Character> stack = new ArrayList<>();
        for (int i = 0; i < lineIndex; i++) {
            String structuralLine = stripQuotedAndCommentedContent(EditorSyntaxHighlighter.SyntaxLanguage.JSON, document.getLine(i));
            for (int j = 0; j < structuralLine.length(); j++) {
                char c = structuralLine.charAt(j);
                if (c == '[') {
                    stack.add(c);
                } else if (c == '{') {
                    stack.add(c);
                } else if (c == ']') {
                    popJsonContainer(stack, '[');
                } else if (c == '}') {
                    popJsonContainer(stack, '{');
                }
            }
        }
        return !stack.isEmpty() && stack.get(stack.size() - 1) == '[';
    }

    private void popJsonContainer(List<Character> stack, char expected) {
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i) == expected) {
                stack.remove(i);
                return;
            }
        }
    }

    private EditorDiagnostic getXmlLineDiagnostic(int lineIndex, String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("<") && !trimmed.endsWith(">") && !trimmed.startsWith("<!--")) {
            return error("XML tag appears to be missing '>'", "This tag-like line starts with '<' but does not close with '>'.", "Add '>' at the end of the tag.",
                    new EditorQuickClick("Add '>'", QuickClickKind.APPEND_TO_LINE, ">"));
        }
        if (trimmed.startsWith("</")) {
            String closingTag = findFirstMatch(trimmed, "</\\s*([A-Za-z0-9_:\\-]+)");
            String expected = findExpectedXmlClosingTag(lineIndex);
            if (!closingTag.isEmpty() && expected != null && !closingTag.equals(expected)) {
                return error("Closing tag </" + closingTag + "> does not match <" + expected + ">", "The XML stack expects a different closing tag here.", "Close the current element before starting a new sibling.");
            }
        }
        if (trimmed.startsWith("<") && !trimmed.startsWith("</") && trimmed.endsWith(">") && !trimmed.contains("</") && !trimmed.endsWith("/>")) {
            String opened = findFirstMatch(trimmed, "<\\s*([A-Za-z0-9_:\\-]+)");
            if (!opened.isEmpty() && nextSignificantLineStartsWith(lineIndex, "</") && !nextSignificantLineStartsWithExact(lineIndex, "</" + opened)) {
                return warning("XML block appears to close with a different tag", "The next closing line does not appear to match this opening element.", "Check nesting order for the surrounding tags.");
            }
        }
        return null;
    }

    private EditorDiagnostic getYamlLineDiagnostic(int lineIndex, String line) {
        if (line.indexOf('\t') >= 0) {
            return warning("YAML indentation should avoid tabs", "YAML parsers usually expect spaces instead of tab indentation.", "Replace tabs with spaces for this block.");
        }
        String trimmed = line.trim();
        if (trimmed.matches("^[^#\\s][^:]*:[^\\s].*$") && !trimmed.contains("://")) {
            return error("YAML mapping appears to be missing a space after ':'", "YAML mappings usually separate the ':' from the value with a space.", "Insert a space after ':'.");
        }
        if (trimmed.matches("^-\\S.*")) {
            return warning("YAML list item should have a space after '-'", "List items should usually start with '- ' before the value.", "Insert a space after '-'.");
        }
        if (!trimmed.startsWith("-")
                && trimmed.contains(":")
                && trimmed.endsWith(":")
                && containsYamlKeyCandidate(trimmed)
                && !nextSignificantLineIsMoreIndented(lineIndex)) {
            return warning("YAML key has no value on this line", "This key opens a block but the following line is not indented as a child value.", "Add a value here or indent the next line.");
        }
        return null;
    }

    private EditorDiagnostic getKeyValueLineDiagnostic(EditorSyntaxHighlighter.SyntaxLanguage language, String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return null;
        }
        if (language == EditorSyntaxHighlighter.SyntaxLanguage.TOML && trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return null;
        }
        if (language == EditorSyntaxHighlighter.SyntaxLanguage.TOML && (trimmed.startsWith("[") || trimmed.endsWith("]")) && !(trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return error("TOML table header appears malformed", "This table header opens or closes with brackets inconsistently.", "Use balanced [table] or [[array-of-tables]] syntax.");
        }
        if ((language == EditorSyntaxHighlighter.SyntaxLanguage.PROPERTIES || language == EditorSyntaxHighlighter.SyntaxLanguage.CONFIG) && (trimmed.startsWith("=") || trimmed.startsWith(":"))) {
            return error("Key/value entry is missing a key", "This line starts with a separator but no key name.", "Add a key before the separator.");
        }
        if (!trimmed.contains("=") && !trimmed.contains(":") && trimmed.matches(".*[A-Za-z0-9_].*")) {
            return error("Key/value entry appears to be missing a separator", "This line looks like a property but does not contain '=' or ':'.", "Add a separator between the key and the value.",
                    new EditorQuickClick("Add '=' separator", QuickClickKind.APPEND_TO_LINE, " = "));
        }
        if (trimmed.matches(".*(?:=|:)\\s*$") && language == EditorSyntaxHighlighter.SyntaxLanguage.TOML) {
            return error("TOML key appears to be missing a value", "This key has a separator but no value after it.", "Add a TOML value after '='.");
        }
        return null;
    }

    private EditorDiagnostic getCodeLineDiagnostic(EditorSyntaxHighlighter.SyntaxLanguage language, int lineIndex, String line) {
        String trimmed = line.trim();
        String codeOnly = stripQuotedAndCommentedContent(language, line).trim();
        if (trimmed.startsWith("/*") && !trimmed.contains("*/") && !document.getText().contains("*/")) {
            return error("Block comment appears to be left open", "The file starts a block comment but never closes it.", "Add '*/' to close the comment.",
                    new EditorQuickClick("Close block comment", QuickClickKind.APPEND_TO_LINE, " */"));
        }
        if (language == EditorSyntaxHighlighter.SyntaxLanguage.JAVA
                && codeOnly.startsWith("return")
                && requiresCodeTerminator(codeOnly)
                && !isMultilineCodeContinuation(language, lineIndex, codeOnly)) {
            return error("Return statement may be missing ';'", "This return statement does not appear to terminate.", "Add ';' at the end of the statement.",
                    new EditorQuickClick("Add ';'", QuickClickKind.APPEND_TO_LINE, ";"));
        }
        if (language == EditorSyntaxHighlighter.SyntaxLanguage.JAVA
                && (codeOnly.startsWith("break") || codeOnly.startsWith("continue"))
                && requiresCodeTerminator(codeOnly)
                && !isMultilineCodeContinuation(language, lineIndex, codeOnly)) {
            return error("Control statement may be missing ';'", "This control statement does not appear to terminate.", "Add ';' at the end of the statement.",
                    new EditorQuickClick("Add ';'", QuickClickKind.APPEND_TO_LINE, ";"));
        }
        if (supportsCodeContinuationDiagnostics(language)
                && looksLikeAssignmentMissingValue(codeOnly)) {
            return error("Assignment appears to be missing a value", "This line ends with an assignment operator but no expression follows it.", "Add the missing expression on this line or the next continuation line.");
        }
        if (language == EditorSyntaxHighlighter.SyntaxLanguage.CSS) {
            EditorDiagnostic cssDiagnostic = getCssDeclarationDiagnostic(lineIndex, trimmed);
            if (cssDiagnostic != null) {
                return cssDiagnostic;
            }
        }
        if ((language == EditorSyntaxHighlighter.SyntaxLanguage.JAVASCRIPT
                || language == EditorSyntaxHighlighter.SyntaxLanguage.PYTHON
                || language == EditorSyntaxHighlighter.SyntaxLanguage.C
                || language == EditorSyntaxHighlighter.SyntaxLanguage.CPP
                || language == EditorSyntaxHighlighter.SyntaxLanguage.CSHARP)
                && looksLikeJavaScriptStatementMissingTerminator(codeOnly)
                && !isMultilineCodeContinuation(language, lineIndex, codeOnly)) {
            return warning("Code statement may be incomplete", "This statement does not end with a terminator and is not clearly continued on the next line.", "Check whether this line should end with ';' or continue with an operator.",
                    new EditorQuickClick("Add ';'", QuickClickKind.APPEND_TO_LINE, ";"));
        }
        if (language == EditorSyntaxHighlighter.SyntaxLanguage.JAVA && codeOnly.matches(".*\\b(class|interface|enum|record)\\b.*") && codeOnly.endsWith(";")) {
            return warning("Type declaration should not end with ';'", "Java type declarations should open a block instead of ending in a semicolon.", "Replace ';' with '{' if this starts a type body.");
        }
        return null;
    }

    private EditorDiagnostic error(String summary, String detail, String hint) {
        return error(summary, detail, hint, null);
    }

    private EditorDiagnostic error(String summary, String detail, String hint, EditorQuickClick quickClick) {
        return new EditorDiagnostic(DiagnosticSeverity.ERROR, summary, detail, hint, quickClick);
    }

    private EditorDiagnostic warning(String summary, String detail, String hint) {
        return warning(summary, detail, hint, null);
    }

    private EditorDiagnostic warning(String summary, String detail, String hint, EditorQuickClick quickClick) {
        return new EditorDiagnostic(DiagnosticSeverity.WARNING, summary, detail, hint, quickClick);
    }

    private boolean requiresCodeTerminator(String codeOnly) {
        if (codeOnly.isEmpty()) {
            return false;
        }
        return !codeOnly.endsWith(";")
                && !codeOnly.endsWith("{")
                && !codeOnly.endsWith("}")
                && !codeOnly.endsWith(":")
                && !codeOnly.endsWith(",")
                && !codeOnly.endsWith(")")
                && !codeOnly.endsWith("->");
    }

    private boolean isMultilineCodeContinuation(EditorSyntaxHighlighter.SyntaxLanguage language, int lineIndex, String codeOnly) {
        if (!supportsCodeContinuationDiagnostics(language) || document == null) {
            return false;
        }
        if (hasPositiveInlineDelimiterBalance(codeOnly) || endsWithContinuationToken(codeOnly)) {
            return true;
        }
        int currentIndent = getIndentWidth(document.getLine(lineIndex));
        for (int i = lineIndex + 1; i < document.getLineCount(); i++) {
            String nextRaw = document.getLine(i);
            String nextCode = stripQuotedAndCommentedContent(language, nextRaw).trim();
            if (nextCode.isEmpty()) {
                continue;
            }
            int nextIndent = getIndentWidth(nextRaw);
            if (startsWithContinuationToken(nextCode)) {
                return true;
            }
            if (nextIndent > currentIndent && (containsLikelyTerminator(nextCode) || hasPositiveInlineDelimiterBalance(nextCode) || endsWithContinuationToken(nextCode))) {
                return true;
            }
            break;
        }
        return false;
    }

    private int findContinuationTailLine(EditorSyntaxHighlighter.SyntaxLanguage language, int lineIndex) {
        if (document == null || !supportsCodeContinuationDiagnostics(language)) {
            return lineIndex;
        }
        int currentIndent = getIndentWidth(document.getLine(lineIndex));
        int tail = lineIndex;
        for (int i = lineIndex + 1; i < document.getLineCount(); i++) {
            String nextRaw = document.getLine(i);
            String nextCode = stripQuotedAndCommentedContent(language, nextRaw).trim();
            if (nextCode.isEmpty()) {
                continue;
            }
            int nextIndent = getIndentWidth(nextRaw);
            if (startsWithContinuationToken(nextCode) || nextIndent > currentIndent) {
                tail = i;
                if (!endsWithContinuationToken(nextCode) && !hasPositiveInlineDelimiterBalance(nextCode)) {
                    break;
                }
                continue;
            }
            break;
        }
        return tail;
    }

    private boolean supportsCodeContinuationDiagnostics(EditorSyntaxHighlighter.SyntaxLanguage language) {
        return switch (language) {
            case JAVA, JAVASCRIPT, CSS, SHADER, C, CPP, CSHARP, PYTHON -> true;
            default -> false;
        };
    }

    private boolean hasPositiveInlineDelimiterBalance(String codeOnly) {
        int paren = 0;
        int brace = 0;
        int bracket = 0;
        for (int i = 0; i < codeOnly.length(); i++) {
            char c = codeOnly.charAt(i);
            if (c == '(') paren++;
            if (c == ')') paren--;
            if (c == '{') brace++;
            if (c == '}') brace--;
            if (c == '[') bracket++;
            if (c == ']') bracket--;
        }
        return paren > 0 || brace > 0 || bracket > 0;
    }

    private boolean endsWithContinuationToken(String codeOnly) {
        String trimmed = codeOnly.trim();
        return trimmed.endsWith("&&")
                || trimmed.endsWith("||")
                || trimmed.endsWith("+")
                || trimmed.endsWith("-")
                || trimmed.endsWith("*")
                || trimmed.endsWith("/")
                || trimmed.endsWith("%")
                || trimmed.endsWith("=")
                || trimmed.endsWith("==")
                || trimmed.endsWith("!=")
                || trimmed.endsWith(">=")
                || trimmed.endsWith("<=")
                || trimmed.endsWith(">")
                || trimmed.endsWith("<")
                || trimmed.endsWith(".")
                || trimmed.endsWith(",")
                || trimmed.endsWith("?")
                || trimmed.endsWith(":")
                || trimmed.endsWith("(")
                || trimmed.endsWith("[")
                || trimmed.endsWith("{");
    }

    private boolean startsWithContinuationToken(String codeOnly) {
        String trimmed = codeOnly.trim();
        return trimmed.startsWith("&&")
                || trimmed.startsWith("||")
                || trimmed.startsWith("+")
                || trimmed.startsWith("-")
                || trimmed.startsWith("*")
                || trimmed.startsWith("/")
                || trimmed.startsWith("%")
                || trimmed.startsWith(".")
                || trimmed.startsWith("?")
                || trimmed.startsWith(":")
                || trimmed.startsWith(",")
                || trimmed.startsWith(")")
                || trimmed.startsWith("]")
                || trimmed.startsWith("}");
    }

    private boolean containsLikelyTerminator(String codeOnly) {
        return codeOnly.contains(";")
                || codeOnly.endsWith(")")
                || codeOnly.endsWith("]")
                || codeOnly.endsWith("}")
                || codeOnly.endsWith(",");
    }

    private boolean looksLikeAssignmentMissingValue(String codeOnly) {
        if (codeOnly.isBlank()) {
            return false;
        }
        return codeOnly.matches(".*(?<![=!<>])=(?![=><]).*")
                && codeOnly.matches(".*(?<![=!<>])=(?![=><])\\s*$");
    }

    private EditorDiagnostic getCssDeclarationDiagnostic(int lineIndex, String trimmed) {
        if (!isInsideCssDeclarationBlock(lineIndex) || trimmed.isEmpty() || trimmed.startsWith("@") || trimmed.startsWith("}") || trimmed.startsWith("{")) {
            return null;
        }
        if (looksLikeCssDeclarationLine(trimmed) && !trimmed.contains(":")) {
            return error("CSS declaration appears to be missing ':'", "This line looks like a CSS property declaration but has no ':' separator.", "Add ':' between the property name and its value.");
        }
        if (trimmed.matches("^[A-Za-z\\-]+\\s*:\\s*$")) {
            return error("CSS property appears to be missing a value", "This property name has a ':' but no value after it.", "Add a value before ending the declaration.");
        }
        if (looksLikeCssDeclarationLine(trimmed)
                && trimmed.contains(":")
                && !trimmed.endsWith(";")
                && !trimmed.endsWith("{")
                && !trimmed.endsWith("}")
                && nextSignificantCssLineLooksLikeDeclaration(lineIndex)) {
            return error("CSS declaration appears to be missing ';'", "Another CSS declaration follows, but this one does not end with ';'.", "Terminate the declaration before the next property.",
                    new EditorQuickClick("Add ';'", QuickClickKind.APPEND_TO_LINE, ";"));
        }
        return null;
    }

    private boolean looksLikeCssDeclarationLine(String trimmed) {
        return trimmed.matches("^[A-Za-z\\-]+\\s*:?.*");
    }

    private boolean isInsideCssDeclarationBlock(int lineIndex) {
        int balance = 0;
        for (int i = 0; i < lineIndex; i++) {
            String line = stripQuotedAndCommentedContent(EditorSyntaxHighlighter.SyntaxLanguage.CSS, document.getLine(i));
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '{') {
                    balance++;
                } else if (c == '}') {
                    balance = Math.max(0, balance - 1);
                }
            }
        }
        return balance > 0;
    }

    private boolean nextSignificantCssLineLooksLikeDeclaration(int lineIndex) {
        for (int i = lineIndex + 1; i < document.getLineCount(); i++) {
            String next = stripQuotedAndCommentedContent(EditorSyntaxHighlighter.SyntaxLanguage.CSS, document.getLine(i)).trim();
            if (next.isEmpty()) {
                continue;
            }
            return looksLikeCssDeclarationLine(next) && !next.startsWith("}");
        }
        return false;
    }

    private boolean looksLikeJavaScriptStatementMissingTerminator(String codeOnly) {
        if (codeOnly.isBlank()) {
            return false;
        }
        return (codeOnly.startsWith("return ")
                || codeOnly.startsWith("throw ")
                || codeOnly.startsWith("const ")
                || codeOnly.startsWith("let ")
                || codeOnly.startsWith("var ")
                || codeOnly.matches("^[A-Za-z_$][A-Za-z0-9_$\\.\\[\\]]*\\s*=.*"))
                && requiresCodeTerminator(codeOnly);
    }

    private boolean looksLikeCompleteJsonEntry(String trimmed) {
        if (trimmed.isEmpty()
                || trimmed.endsWith(",")
                || trimmed.endsWith("{")
                || trimmed.endsWith("[")
                || trimmed.endsWith(":")
                || trimmed.equals("{")
                || trimmed.equals("[")
                || trimmed.equals("}")
                || trimmed.equals("]")) {
            return false;
        }
        return true;
    }

    private boolean nextJsonLineNeedsComma(int lineIndex) {
        int nextIndex = getNextSignificantLineIndex(lineIndex);
        if (nextIndex < 0) {
            return false;
        }
        String next = document.getLine(nextIndex).trim();
        if (next.startsWith("}") || next.startsWith("]")) {
            return false;
        }
        return next.startsWith("\"")
                || next.startsWith("{")
                || next.startsWith("[")
                || next.startsWith("-")
                || next.startsWith("true")
                || next.startsWith("false")
                || next.startsWith("null")
                || next.matches("^[0-9].*");
    }

    private boolean nextSignificantLineIsMoreIndented(int lineIndex) {
        int currentIndent = getIndentWidth(document.getLine(lineIndex));
        for (int i = lineIndex + 1; i < document.getLineCount(); i++) {
            String next = document.getLine(i);
            if (next.trim().isEmpty()) {
                continue;
            }
            return getIndentWidth(next) > currentIndent || next.trim().startsWith("-");
        }
        return false;
    }

    private boolean containsYamlKeyCandidate(String trimmed) {
        if (trimmed.equals("---") || trimmed.equals("...") || trimmed.startsWith("?") || trimmed.startsWith("&") || trimmed.startsWith("*")) {
            return false;
        }
        int colonIndex = trimmed.indexOf(':');
        if (colonIndex <= 0) {
            return false;
        }
        String key = trimmed.substring(0, colonIndex).trim();
        return !key.isEmpty() && key.chars().anyMatch(Character::isLetterOrDigit);
    }

    private int getIndentWidth(String line) {
        int width = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                width++;
            } else if (c == '\t') {
                width += 4;
            } else {
                break;
            }
        }
        return width;
    }

    private String stripCommentContent(EditorSyntaxHighlighter.SyntaxLanguage language, String line) {
        return switch (language) {
            case JAVA, JAVASCRIPT, C, CPP, CSHARP -> stripCodeLineComments(line);
            case PROPERTIES, CONFIG, YAML, TOML, PYTHON -> stripHashComment(line);
            default -> line;
        };
    }

    private String stripQuotedAndCommentedContent(EditorSyntaxHighlighter.SyntaxLanguage language, String line) {
        String withoutComments = stripCommentContent(language, line);
        StringBuilder sanitized = new StringBuilder(withoutComments.length());
        boolean escaped = false;
        char activeQuote = 0;
        for (int i = 0; i < withoutComments.length(); i++) {
            char c = withoutComments.charAt(i);
            if (activeQuote != 0) {
                sanitized.append(' ');
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == activeQuote) {
                    activeQuote = 0;
                }
                continue;
            }

            if (c == '"' || c == '\'') {
                activeQuote = c;
                sanitized.append(' ');
                continue;
            }
            sanitized.append(c);
        }
        return sanitized.toString();
    }

    private String stripCodeLineComments(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            char next = i + 1 < line.length() ? line.charAt(i + 1) : 0;

            if (escaped) {
                builder.append(c);
                escaped = false;
                continue;
            }
            if ((inSingle || inDouble) && c == '\\') {
                builder.append(c);
                escaped = true;
                continue;
            }
            if (!inDouble && c == '\'' ) {
                inSingle = !inSingle;
                builder.append(c);
                continue;
            }
            if (!inSingle && c == '"') {
                inDouble = !inDouble;
                builder.append(c);
                continue;
            }
            if (!inSingle && !inDouble && c == '/' && next == '/') {
                break;
            }
            builder.append(c);
        }
        return builder.toString();
    }

    private String stripHashComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                builder.append(c);
                escaped = false;
                continue;
            }
            if ((inSingle || inDouble) && c == '\\') {
                builder.append(c);
                escaped = true;
                continue;
            }
            if (!inDouble && c == '\'') {
                inSingle = !inSingle;
                builder.append(c);
                continue;
            }
            if (!inSingle && c == '"') {
                inDouble = !inDouble;
                builder.append(c);
                continue;
            }
            if (!inSingle && !inDouble && c == '#') {
                break;
            }
            builder.append(c);
        }
        return builder.toString();
    }

    private boolean nextSignificantLineStartsWith(int lineIndex, String... prefixes) {
        for (int i = lineIndex + 1; i < document.getLineCount(); i++) {
            String trimmed = document.getLine(i).trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            for (String prefix : prefixes) {
                if (trimmed.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private int getNextSignificantLineIndex(int lineIndex) {
        for (int i = lineIndex + 1; i < document.getLineCount(); i++) {
            if (!document.getLine(i).trim().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private boolean nextSignificantLineStartsWithExact(int lineIndex, String prefix) {
        for (int i = lineIndex + 1; i < document.getLineCount(); i++) {
            String trimmed = document.getLine(i).trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            return trimmed.startsWith(prefix);
        }
        return false;
    }

    private String findExpectedXmlClosingTag(int lineIndex) {
        List<String> stack = new ArrayList<>();
        Pattern openPattern = Pattern.compile("<\\s*([A-Za-z0-9_:\\-]+)(?![^>]*?/>)");
        Pattern closePattern = Pattern.compile("</\\s*([A-Za-z0-9_:\\-]+)");
        for (int i = 0; i < lineIndex; i++) {
            String line = document.getLine(i);
            Matcher closeMatcher = closePattern.matcher(line);
            while (closeMatcher.find()) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
            }
            Matcher openMatcher = openPattern.matcher(line);
            while (openMatcher.find()) {
                String tag = openMatcher.group(1);
                if (!line.substring(openMatcher.start()).startsWith("</") && !tag.startsWith("!")) {
                    stack.add(tag);
                }
            }
        }
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    private boolean hasOddQuoteCount(String line, char quote) {
        boolean escaped = false;
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == quote) {
                count++;
            }
        }
        return count % 2 != 0;
    }

    private String findFirstMatch(String text, String regex) {
        if (text == null || text.isEmpty() || regex == null || regex.isEmpty()) {
            return "";
        }
        try {
            Matcher matcher = Pattern.compile(regex).matcher(text);
            if (!matcher.find()) {
                return "";
            }
            if (matcher.groupCount() >= 1) {
                String group = matcher.group(1);
                return group == null ? "" : group.trim();
            }
            String group = matcher.group();
            return group == null ? "" : group.trim();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private int countMatches(String text, String regex) {
        if (text == null || text.isEmpty() || regex == null || regex.isEmpty()) {
            return 0;
        }
        try {
            Matcher matcher = Pattern.compile(regex).matcher(text);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            return count;
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private String getFileTypeLabel(FileType fileType, File file) {
        return switch (fileType) {
            case FOLDER -> "Folder";
            case IMAGE -> "Image | " + getFileTypeDescription(file);
            case FILE -> "File | " + getFileTypeDescription(file);
            case AUDIO -> "Audio | " + getFileTypeDescription(file);
            case VIDEO -> "Video | " + getFileTypeDescription(file);
            case ZIP -> getFileTypeDescription(file);
            case MCMETA -> "Minecraft | " + getFileTypeDescription(file);
        };
    }

    private String getFileTypeDescription(File file) {
        String fileName = file.getName().toLowerCase();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            return fileName.substring(dotIndex + 1).toUpperCase() + " File";
        }
        return "File";
    }

    private String getFileSize(File file) {
        long bytes = file.length();
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(bytes / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    private Identifier getFileIcon(String fileName) {
        return FIleIconHelper.resolve(fileName);
    }

    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex == -1 ? "" : fileName.substring(dotIndex + 1);
    }

    private List<String> splitLines(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = normalized.split("\n", -1);
        List<String> lines = new ArrayList<>(List.of(parts));
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String fitText(String text, int maxWidth) {
        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int available = maxWidth - this.textRenderer.getWidth(ellipsis);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String candidate = builder.toString() + text.charAt(i);
            if (this.textRenderer.getWidth(candidate) > available) {
                break;
            }
            builder.append(text.charAt(i));
        }
        return builder + ellipsis;
    }

    private void renderTopBarButton(DrawContext context, int x, String label) {
        int buttonWidth = getTopBarButtonWidth(label);
        context.fill(x, TopBarLayout.BUTTON_Y, x + buttonWidth, TopBarLayout.BUTTON_Y + TopBarLayout.BUTTON_HEIGHT, withAlpha(uiColorContentBase, 176));
        context.drawBorder(x, TopBarLayout.BUTTON_Y, buttonWidth, TopBarLayout.BUTTON_HEIGHT, new Color(uiColorBackgroundBorder, true).getRGB());
        int textX = x + Math.max(4, (buttonWidth - this.textRenderer.getWidth(label)) / 2);
        context.drawText(this.textRenderer, label, textX, TopBarLayout.BUTTON_Y + 7, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
    }

    private boolean isTopBarButtonClicked(double mouseX, double mouseY, int x, int width) {
        return mouseX >= x && mouseX <= x + width && mouseY >= TopBarLayout.BUTTON_Y && mouseY <= TopBarLayout.BUTTON_Y + TopBarLayout.BUTTON_HEIGHT;
    }

    private static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static boolean isWordChar(char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '-';
    }

    private void captureUndoSnapshot() {
        if (document == null) {
            return;
        }
        String currentText = document.getText();
        if (!undoHistory.isEmpty() && undoHistory.peek().equals(currentText)) {
            return;
        }
        undoHistory.push(currentText);
        while (undoHistory.size() > MAX_UNDO_HISTORY) {
            undoHistory.removeLast();
        }
        redoHistory.clear();
    }

    private void undoEdit() {
        if (document == null || undoHistory.isEmpty()) {
            return;
        }
        redoHistory.push(document.getText());
        restoreDocumentText(undoHistory.pop(), "Undo");
    }

    private void redoEdit() {
        if (document == null || redoHistory.isEmpty()) {
            return;
        }
        undoHistory.push(document.getText());
        restoreDocumentText(redoHistory.pop(), "Redo");
    }

    private void copySelectionToClipboard() {
        if (document == null || !document.hasSelection() || client == null) {
            return;
        }
        client.keyboard.setClipboard(document.getSelectedText());
        saveStatusMessage = "Copied";
        saveStatusColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
        saveStatusUntil = Util.getMeasuringTimeMs() + 1200L;
    }

    private void cutSelectionToClipboard() {
        if (!canMutateDocument("Cut is unavailable")) {
            return;
        }
        if (document == null || !document.hasSelection() || client == null) {
            return;
        }
        client.keyboard.setClipboard(document.getSelectedText());
        captureUndoSnapshot();
        if (document.deleteSelection()) {
            onDocumentMutated("Cut");
        }
    }

    private void pasteClipboardIntoDocument() {
        if (!canMutateDocument("Paste is unavailable")) {
            return;
        }
        if (document == null || client == null) {
            return;
        }
        String clipboard = client.keyboard.getClipboard();
        if (clipboard == null || clipboard.isEmpty()) {
            return;
        }
        captureUndoSnapshot();
        if (document.insertText(clipboard)) {
            onDocumentMutated("Pasted");
        }
    }

    private void restoreDocumentText(String text, String message) {
        this.document = new EditorDocument(splitLines(text));
        this.cachedLongestLineWidth = -1;
        EditorSyntaxHighlighter.clearCache();
        rebuildLineDiagnostics();
        ensureCursorVisible();
        resetCursorBlink();
        updateSearchMatches();
        updateEditorSuggestions();
        saveStatusMessage = message;
        saveStatusColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
        saveStatusUntil = Util.getMeasuringTimeMs() + 1200L;
    }

    private record EditorPoint(int line, int column) {}
    private record SidebarNotePoint(int line, int column) {}
    private record SidebarMetric(String label, String value) {}
    private record OpenSymbol(char symbol, int line) {}
    private record EditorQuickClick(String label, QuickClickKind kind, String payload) {}
    private record EditorDiagnostic(DiagnosticSeverity severity, String summary, String detail, String hint, EditorQuickClick quickClick) {}
    private record EditorSuggestion(EditorSuggestionKind kind, String value, String detail, int score) {}
    private record SearchMatch(int startOffset, int endOffset, int startLine, int startColumn, int endLine, int endColumn, String preview, String replacementPreview) {}
    private record SearchPosition(int line, int column) {}
    private record ParsedSearchCommand(SearchCommandType type, String query, int lineNumber) {
        private static ParsedSearchCommand search(String query) {
            return new ParsedSearchCommand(SearchCommandType.SEARCH, query == null ? "" : query, -1);
        }

        private static ParsedSearchCommand goToLine(int lineNumber) {
            return new ParsedSearchCommand(SearchCommandType.GO_TO_LINE, "", lineNumber);
        }
    }
    private record DropdownEntry(DropdownEntryType type, String primary, String secondary, int index, String value) {}
    private record LineIndexMap(List<Integer> starts) {
        private SearchPosition resolve(int offset) {
            int line = 0;
            for (int i = 0; i < starts.size(); i++) {
                int start = starts.get(i);
                int nextStart = i + 1 < starts.size() ? starts.get(i + 1) : Integer.MAX_VALUE;
                if (offset >= start && offset < nextStart) {
                    line = i;
                    break;
                }
                if (offset >= starts.get(starts.size() - 1)) {
                    line = starts.size() - 1;
                }
            }
            return new SearchPosition(line, Math.max(0, offset - starts.get(line)));
        }
    }
    private enum SearchCommandType {
        SEARCH,
        GO_TO_LINE
    }
    private enum DropdownEntryType {
        MATCH,
        RECENT,
        SUGGESTION,
        GOTO_LINE,
        ERROR
    }
    private enum DiagnosticSeverity {
        ERROR,
        WARNING
    }
    private enum EditorSuggestionKind {
        KEYWORD("keyword", uiColorEditorSuggestionKeyword),
        SYMBOL("symbol", uiColorEditorSuggestionSymbol),
        LITERAL("literal", uiColorEditorSuggestionLiteral),
        CLASS("class", uiColorEditorSuggestionClass),
        METHOD("method", uiColorEditorSuggestionMethod),
        FIELD("field", uiColorEditorSuggestionField),
        PROPERTY("property", uiColorEditorSuggestionProperty),
        SELECTOR("selector", uiColorEditorSuggestionSelector),
        PATH("path", uiColorEditorSuggestionPath);

        private final String label;
        private final int color;

        EditorSuggestionKind(String label, int color) {
            this.label = label;
            this.color = color;
        }

        private String label() {
            return label;
        }

        private int color() {
            return color;
        }
    }
    private enum QuickClickKind {
        APPEND_TO_LINE,
        REMOVE_LINE_SUFFIX,
        REPLACE_FIRST_CLOSER,
        REMOVE_FIRST_CLOSER,
        TRIM_LINE_ENDING,
        REPLACE_LEADING_INDENT
    }
}
