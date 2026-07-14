package com.spirit.client.gui.console;

import com.spirit.Main;
import com.spirit.client.gui.ScreenActionHelper;
import com.spirit.client.gui.TopBarLayout;
import com.spirit.client.gui.UiSoundHelper;
import com.spirit.koil.api.automation.AutomationRouter;
import com.spirit.koil.api.automation.cli.AutomationCliRenderer;
import com.spirit.koil.api.automation.cli.AutomationCliViewModel;
import com.spirit.koil.api.automation.feedback.AutomationFailureRegistry;
import com.spirit.koil.api.automation.feedback.AutomationFailureType;
import com.spirit.koil.api.automation.feedback.AutomationFeedbackNode;
import com.spirit.koil.api.automation.feedback.AutomationFeedbackService;
import com.spirit.koil.chat.internal.RichChatCommandOutputBridge;
import com.spirit.koil.api.console.*;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.util.application.WindowManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.spirit.Main.LOGO_TEXTURE;
import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
public class ConsoleScreen extends Screen implements ConsoleRepository.Listener {
    private static final int HEADER_HEIGHT = 60;
    private static final int CONTENT_TOP = 70;
    private static final int STATUS_BAR_HEIGHT = 24;
    private static final int INPUT_BAR_HORIZONTAL_PADDING = 8;
    private static final int INPUT_BAR_VERTICAL_PADDING = 5;
    private static final String TOP_BAR_BACK_LABEL = "<";
    private static final String TOP_BAR_POP_LABEL = "Pop Out";
    private static final String TOP_BAR_KOIL_LABEL = "Koil Logs";
    private static final String TOP_BAR_PACKAGE_LABEL = "Package Logs";
    private static final String TOP_BAR_MINECRAFT_LABEL = "Minecraft Logs";
    private static final String TOP_BAR_CLI_LABEL = "Automation";

    private final Screen parent;
    private final List<ConsoleStyledLine> cachedLines = new ArrayList<>();
    private ConsoleChannel activeChannel;
    private final boolean automationMode;
    private TextFieldWidget searchField;
    private TextFieldWidget inputField;
    private int scrollOffset;
    private boolean autoScroll = true;
    private long lastFileStamp = Long.MIN_VALUE;
    private final List<ConsoleInputSuggestionService.ConsoleInputSuggestion> inputSuggestions = new ArrayList<>();
    private int selectedSuggestionIndex;
    private boolean draggingScrollbar;
    private int scrollbarDragOffset;
    private double renderedCliScrollOffset;
    private boolean cliAutoFocusLatest = true;
    private boolean draggingCliCanvas;
    private int cliCanvasPanX;
    private double cliCanvasDragScrollRemainder;
    private long lastKtlNodeClickTime;
    private String lastKtlNodeClickPath = "";
    private boolean feedbackSelectingNode;
    private AutomationFeedbackNode feedbackNode;
    private List<AutomationFailureType> feedbackFailureTypes = List.of();

    public ConsoleScreen(Screen parent) {
        this(parent, ConsoleChannel.KOIL, false);
    }

    public ConsoleScreen(Screen parent, ConsoleChannel initialChannel, boolean automationMode) {
        super(Text.literal("Koil Console"));
        this.parent = parent;
        this.activeChannel = initialChannel;
        this.automationMode = automationMode;
    }

    @Override
    protected void init() {
        super.init();

        this.searchField = new TextFieldWidget(this.textRenderer, 0, 0, 0, TopBarLayout.SEARCH_FIELD_HEIGHT, Text.literal("console-search"));
        this.searchField.setMaxLength(256);
        this.searchField.setPlaceholder(Text.literal("Search visible log history"));
        this.searchField.setChangedListener(value -> {
            this.scrollOffset = 0;
            this.renderedCliScrollOffset = 0.0;
            this.cliAutoFocusLatest = true;
            this.autoScroll = true;
        });

        this.inputField = new TextFieldWidget(this.textRenderer, 0, 0, 0, inputFieldHeight(), Text.literal("console-input"));
        this.inputField.setMaxLength(512);
        this.inputField.setPlaceholder(Text.literal("Enter console input, command, or automation prompt"));
        this.inputField.setDrawsBackground(false);
        this.inputField.setEditableColor(new Color(uiColorContentBaseTitleText, true).getRGB());
        this.inputField.setUneditableColor(new Color(uiColorContentBaseTitleText, true).getRGB());
        this.inputField.setChangedListener(value -> updateInputSuggestions());

        updateInputLayout();

        this.addDrawableChild(this.searchField);
        this.addDrawableChild(this.inputField);

        this.searchField.setFocused(false);
        this.inputField.setFocused(false);

        reloadSnapshot();
        ConsoleRepository.getInstance().subscribe(this.activeChannel, this);
    }

    @Override
    public void tick() {
        if (this.searchField != null) {
            this.searchField.tick();
        }
        if (this.inputField != null) {
            this.inputField.tick();
        }
        pollActiveFileChannel();
        super.tick();
    }

    private void switchChannel(ConsoleChannel channel) {
        ConsoleRepository.getInstance().unsubscribe(this.activeChannel, this);
        this.activeChannel = channel;
        this.scrollOffset = 0;
        this.renderedCliScrollOffset = 0.0;
        this.cliAutoFocusLatest = true;
        this.draggingCliCanvas = false;
        this.cliCanvasDragScrollRemainder = 0.0D;
        this.autoScroll = true;
        reloadSnapshot();
        ConsoleRepository.getInstance().subscribe(this.activeChannel, this);
    }

    private void reloadSnapshot() {
        this.cachedLines.clear();
        if (usesFileBackedChannel(this.activeChannel)) {
            this.cachedLines.addAll(ConsoleDisplayService.readStyledLog(channelLogPath(this.activeChannel), this.activeChannel));
            this.lastFileStamp = fileStamp(channelLogPath(this.activeChannel));
        } else {
            this.cachedLines.addAll(ConsoleDisplayService.snapshot(this.activeChannel));
            this.lastFileStamp = Long.MIN_VALUE;
        }
    }

    @Override
    public void removed() {
        ConsoleRepository.getInstance().unsubscribe(this.activeChannel, this);
        super.removed();
    }

    @Override
    public void onRecord(ConsoleRecord record) {
        if (record.channel() != this.activeChannel) {
            return;
        }
        this.cachedLines.add(ConsoleFormatter.style(record));
        if (this.cachedLines.size() > 4_000) {
            this.cachedLines.remove(0);
        }
        if (this.autoScroll) {
            this.scrollOffset = 0;
        }
    }

    private void focusSearchField() {
        if (this.searchField != null) {
            this.searchField.setFocused(true);
            this.setFocused(this.searchField);
            UiSoundHelper.playButtonClick();
        }
        if (this.inputField != null) {
            this.inputField.setFocused(false);
        }
    }

    private void focusInputField() {
        if (this.inputField != null) {
            this.inputField.setFocused(true);
            this.setFocused(this.inputField);
            UiSoundHelper.playButtonClick();
            updateInputSuggestions();
        }
        if (this.searchField != null) {
            this.searchField.setFocused(false);
        }
    }

    private void clearFieldFocus() {
        if (this.searchField != null) {
            this.searchField.setFocused(false);
        }
        if (this.inputField != null) {
            this.inputField.setFocused(false);
            this.inputField.setSuggestion("");
        }
        this.inputSuggestions.clear();
        this.setFocused(null);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (hasControlDown() && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_F && this.searchField != null) {
            focusSearchField();
            return true;
        }

        if (keyCode == 256 && this.searchField != null && this.searchField.isFocused()) {
            clearFieldFocus();
            return true;
        }

        if (this.searchField != null && this.searchField.isFocused()) {
            if (this.searchField.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }

        if (this.inputField != null && this.inputField.isFocused()) {
            if (isSuggestionVisible()) {
                if (hasControlDown() && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_UP) {
                    moveSuggestionSelection(-1);
                    return true;
                }
                if (hasControlDown() && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN) {
                    moveSuggestionSelection(1);
                    return true;
                }
                if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) {
                    if (acceptSelectedSuggestion()) {
                        return true;
                    }
                }
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_UP) {
                String previous = ConsoleCommandHistory.previous();
                if (previous != null) {
                    this.inputField.setText(previous);
                    this.inputField.setCursorToEnd();
                }
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN) {
                this.inputField.setText(ConsoleCommandHistory.next());
                this.inputField.setCursorToEnd();
                return true;
            }
            if (keyCode == 257 || keyCode == 335) {
                submitInput(this.inputField.getText());
                return true;
            }
            if (this.inputField.keyPressed(keyCode, scanCode, modifiers)) {
                updateInputSuggestions();
                return true;
            }
        }

        if (this.activeChannel == ConsoleChannel.CLI && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_END) {
            this.cliAutoFocusLatest = true;
            this.scrollOffset = focusLatestCliOffset(scrollViewportHeight(), scrollItemCount());
            return true;
        }
        if (this.activeChannel == ConsoleChannel.CLI && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_HOME) {
            this.cliAutoFocusLatest = false;
            this.scrollOffset = 0;
            this.renderedCliScrollOffset = 0.0;
            return true;
        }

        if (this.activeChannel == ConsoleChannel.CLI && keyCode == 256 && AutomationCliCanvasRenderer.clearSelection()) {
            return true;
        }

        if (keyCode == 256) {
            close();
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (this.searchField != null && this.searchField.isFocused()) {
            return this.searchField.charTyped(chr, modifiers);
        }
        if (this.inputField != null && this.inputField.isFocused()) {
            boolean handled = this.inputField.charTyped(chr, modifiers);
            if (handled) {
                updateInputSuggestions();
            }
            return handled;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if ((button == 0 || button == 1) && this.activeChannel == ConsoleChannel.CLI && isCliViewportClick(mouseX, mouseY)) {
            String rowId = AutomationCliCanvasRenderer.rowIdAt(
                    AutomationCliViewModel.snapshot(),
                    cliViewportX(),
                    cliViewportY(),
                    cliViewportWidth(),
                    scrollViewportHeight(),
                    smoothedCliScrollOffset(),
                    this.cliCanvasPanX,
                    this.searchField == null ? "" : this.searchField.getText(),
                    (int) mouseX,
                    (int) mouseY
            );
            if (!rowId.isBlank() && AutomationFeedbackService.handleConsoleRowClick(rowId)) {
                UiSoundHelper.playButtonClick();
                return true;
            }
            if (button == 1 && !rowId.isBlank()) {
                AutomationFeedbackService.openNodeFeedback(rowId);
                UiSoundHelper.playButtonClick();
                return true;
            }
        }

        if (handleFeedbackPopupClick(mouseX, mouseY, button)) {
            return true;
        }

        if (button == 1 && this.activeChannel == ConsoleChannel.CLI && isCliViewportClick(mouseX, mouseY)) {
            AutomationFeedbackNode node = AutomationCliCanvasRenderer.feedbackNodeAt(
                    AutomationCliViewModel.snapshot(),
                    cliViewportX(),
                    cliViewportY(),
                    cliViewportWidth(),
                    scrollViewportHeight(),
                    smoothedCliScrollOffset(),
                    this.cliCanvasPanX,
                    this.searchField == null ? "" : this.searchField.getText(),
                    (int) mouseX,
                    (int) mouseY
            );
            if (node != null) {
                openFeedbackTypes(node);
                UiSoundHelper.playButtonClick();
                return true;
            }
        }

        if (button == 1 && this.activeChannel == ConsoleChannel.CLI && isCliViewportClick(mouseX, mouseY)) {
            String rowId = AutomationCliCanvasRenderer.rowIdAt(
                    AutomationCliViewModel.snapshot(),
                    cliViewportX(),
                    cliViewportY(),
                    cliViewportWidth(),
                    scrollViewportHeight(),
                    smoothedCliScrollOffset(),
                    this.cliCanvasPanX,
                    this.searchField == null ? "" : this.searchField.getText(),
                    (int) mouseX,
                    (int) mouseY
            );
            if (!rowId.isBlank()) {
                AutomationFeedbackService.openNodeFeedback(rowId);
                UiSoundHelper.playButtonClick();
                return true;
            }
        }

        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        ScrollbarMetrics scrollbar = scrollbarMetrics();
        if (scrollbar != null && scrollbar.contains(mouseX, mouseY)) {
            if (scrollbar.thumbContains(mouseX, mouseY)) {
                this.draggingScrollbar = true;
                this.scrollbarDragOffset = (int) mouseY - scrollbar.thumbY();
            } else {
                setScrollOffsetFromThumbTop((int) mouseY - scrollbar.thumbHeight() / 2, scrollbar);
            }
            return true;
        }

        boolean searchClicked = this.searchField != null && this.searchField.isMouseOver(mouseX, mouseY);
        boolean inputClicked = this.inputField != null && this.inputField.isMouseOver(mouseX, mouseY);

        if (super.mouseClicked(mouseX, mouseY, button)) {
            if (searchClicked) {
                focusSearchField();
            } else if (inputClicked) {
                focusInputField();
            }
            return true;
        }

        if (isSuggestionVisible()) {
            int rowHeight = ConsoleSuggestionPresentation.ROW_HEIGHT;
            int width = suggestionPopupWidth();
            int visibleRows = Math.min(ConsoleSuggestionPresentation.MAX_VISIBLE_ROWS, this.inputSuggestions.size());
            int height = 6 + (visibleRows * rowHeight) + 6;
            int x = this.inputField.getX();
            int y = this.inputField.getY() - height - 4;
            if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height) {
                int index = ((int) mouseY - y - 4) / rowHeight;
                if (index >= 0 && index < visibleRows) {
                    this.selectedSuggestionIndex = index;
                    return acceptSelectedSuggestion();
                }
            }
        }

        if (this.activeChannel == ConsoleChannel.CLI && isCliViewportClick(mouseX, mouseY)) {
            AutomationFeedbackNode hit = AutomationCliCanvasRenderer.feedbackNodeAt(
                    AutomationCliViewModel.snapshot(),
                    cliViewportX(),
                    cliViewportY(),
                    cliViewportWidth(),
                    scrollViewportHeight(),
                    smoothedCliScrollOffset(),
                    this.cliCanvasPanX,
                    this.searchField == null ? "" : this.searchField.getText(),
                    (int) mouseX,
                    (int) mouseY
            );
            if (hit != null && hit.label().contains("Good")) {
                if (mouseX < this.width / 2.0D) {
                    AutomationCliViewModel.feedbackGood();
                } else {
                    this.feedbackSelectingNode = true;
                    this.feedbackNode = null;
                    this.feedbackFailureTypes = List.of();
                }
                UiSoundHelper.playButtonClick();
                return true;
            }
            String ktlSourcePath = AutomationCliCanvasRenderer.ktlSourceAt(
                    AutomationCliViewModel.snapshot(),
                    cliViewportX(),
                    cliViewportY(),
                    cliViewportWidth(),
                    scrollViewportHeight(),
                    smoothedCliScrollOffset(),
                    this.cliCanvasPanX,
                    this.searchField == null ? "" : this.searchField.getText(),
                    (int) mouseX,
                    (int) mouseY
            );
            int ktlSourceLine = AutomationCliCanvasRenderer.ktlSourceLineAt(
                    AutomationCliViewModel.snapshot(),
                    cliViewportX(),
                    cliViewportY(),
                    cliViewportWidth(),
                    scrollViewportHeight(),
                    smoothedCliScrollOffset(),
                    this.cliCanvasPanX,
                    this.searchField == null ? "" : this.searchField.getText(),
                    (int) mouseX,
                    (int) mouseY
            );
            int selectedOffset = AutomationCliCanvasRenderer.selectNodeAt(
                    AutomationCliViewModel.snapshot(),
                    cliViewportX(),
                    cliViewportY(),
                    cliViewportWidth(),
                    scrollViewportHeight(),
                    smoothedCliScrollOffset(),
                    this.cliCanvasPanX,
                    this.searchField == null ? "" : this.searchField.getText(),
                    (int) mouseX,
                    (int) mouseY
            );
            if (selectedOffset >= 0) {
                long now = Util.getMeasuringTimeMs();
                boolean sourceDoubleClick = !ktlSourcePath.isBlank()
                        && ktlSourcePath.equals(this.lastKtlNodeClickPath)
                        && now - this.lastKtlNodeClickTime <= 420L;
                this.lastKtlNodeClickTime = now;
                this.lastKtlNodeClickPath = ktlSourcePath;
                this.scrollOffset = selectedOffset;
                this.renderedCliScrollOffset = selectedOffset;
                this.cliAutoFocusLatest = false;
                UiSoundHelper.playButtonClick();
                if (sourceDoubleClick) {
                    ScreenActionHelper.openInKoilEditor(ktlSourcePath, ktlSourceLine);
                }
                return true;
            }
            if (AutomationCliCanvasRenderer.clearSelection()) {
                UiSoundHelper.playButtonClick();
                return true;
            }
            this.draggingCliCanvas = true;
            this.cliAutoFocusLatest = false;
            return true;
        }

        if (isTopBarButtonClicked(mouseX, mouseY, 10, getTopBarButtonWidth(TOP_BAR_BACK_LABEL))) {
            UiSoundHelper.playButtonClick();
            close();
            return true;
        }
        if (isTopBarButtonClicked(mouseX, mouseY, getTopBarChannelButtonX(TOP_BAR_KOIL_LABEL), getTopBarButtonWidth(TOP_BAR_KOIL_LABEL))) {
            UiSoundHelper.playButtonClick();
            switchChannel(ConsoleChannel.KOIL);
            return true;
        }
        if (isTopBarButtonClicked(mouseX, mouseY, getTopBarChannelButtonX(TOP_BAR_PACKAGE_LABEL), getTopBarButtonWidth(TOP_BAR_PACKAGE_LABEL))) {
            UiSoundHelper.playButtonClick();
            switchChannel(ConsoleChannel.PACKAGE);
            return true;
        }
        if (isTopBarButtonClicked(mouseX, mouseY, getTopBarChannelButtonX(TOP_BAR_MINECRAFT_LABEL), getTopBarButtonWidth(TOP_BAR_MINECRAFT_LABEL))) {
            UiSoundHelper.playButtonClick();
            switchChannel(ConsoleChannel.MINECRAFT);
            return true;
        }
        if (isTopBarButtonClicked(mouseX, mouseY, getTopBarChannelButtonX(TOP_BAR_CLI_LABEL), getTopBarButtonWidth(TOP_BAR_CLI_LABEL))) {
            UiSoundHelper.playButtonClick();
            switchChannel(ConsoleChannel.CLI);
            return true;
        }
        if (isTopBarButtonClicked(mouseX, mouseY, getTopBarPopButtonX(), getTopBarButtonWidth(TOP_BAR_POP_LABEL))) {
            UiSoundHelper.playButtonClick();
            WindowManager.openConsoleWindow(this.activeChannel);
            return true;
        }

        clearFieldFocus();
        return false;
    }

    private void submitInput(String input) {
        if (input == null || input.isBlank()) {
            return;
        }
        String trimmed = input.trim();
        UiSoundHelper.playButtonClick();
        ConsoleCommandHistory.push(trimmed);
        if (this.activeChannel == ConsoleChannel.CLI || this.automationMode) {
            this.cliAutoFocusLatest = true;
            AutomationRouter.handleConsoleInput(trimmed);
        } else {
            ConsoleLogBridge.publish(ConsoleChannel.CLI, ConsoleLevel.PLAIN, nowTimestamp(), "ConsoleScreen", "Input", trimmed, trimmed);
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && client.getNetworkHandler() != null) {
                if (trimmed.startsWith("/")) {
                    RichChatCommandOutputBridge.rememberOutgoingChatCommand(trimmed.substring(1));
                    client.getNetworkHandler().sendChatCommand(trimmed.substring(1));
                } else {
                    client.getNetworkHandler().sendChatMessage(trimmed);
                }
            }
        }
        this.inputField.setText("");
        this.inputField.setSuggestion("");
        this.inputSuggestions.clear();
    }

    private static String nowTimestamp() {
        long millis = Util.getMeasuringTimeMs();
        long seconds = millis / 1000L;
        long hours = (seconds / 3600L) % 24L;
        long minutes = (seconds / 60L) % 60L;
        long secs = seconds % 60L;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int maxScroll = Math.max(0, scrollItemCount() - visibleScrollCapacity(scrollViewportHeight()));
        int direction = amount < 0 ? 3 : -3;
        this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset + direction));
        if (this.activeChannel == ConsoleChannel.CLI) {
            this.cliAutoFocusLatest = false;
            clearCliSelectionIfScrolledOut();
        }
        this.autoScroll = this.scrollOffset == 0;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && this.draggingScrollbar) {
            ScrollbarMetrics scrollbar = scrollbarMetrics();
            if (scrollbar != null) {
                setScrollOffsetFromThumbTop((int) mouseY - this.scrollbarDragOffset, scrollbar);
                if (this.activeChannel == ConsoleChannel.CLI) {
                    this.cliAutoFocusLatest = false;
                    clearCliSelectionIfScrolledOut();
                }
                return true;
            }
        }
        if (button == 0 && this.draggingCliCanvas && this.activeChannel == ConsoleChannel.CLI) {
            this.cliAutoFocusLatest = false;
            this.cliCanvasPanX = clamp((int) Math.round(this.cliCanvasPanX + deltaX), -Math.max(80, this.width / 2), Math.max(80, this.width / 2));
            int maxScroll = Math.max(0, scrollItemCount() - visibleScrollCapacity(scrollViewportHeight()));
            this.cliCanvasDragScrollRemainder += -deltaY / 26.0D;
            int verticalUnits = this.cliCanvasDragScrollRemainder > 0.0D
                    ? (int) Math.floor(this.cliCanvasDragScrollRemainder)
                    : (int) Math.ceil(this.cliCanvasDragScrollRemainder);
            if (verticalUnits != 0) {
                this.cliCanvasDragScrollRemainder -= verticalUnits;
                this.scrollOffset = clamp(this.scrollOffset + verticalUnits, 0, maxScroll);
                clearCliSelectionIfScrolledOut();
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && this.draggingScrollbar) {
            this.draggingScrollbar = false;
            this.cliCanvasDragScrollRemainder = 0.0D;
            return true;
        }
        if (button == 0 && this.draggingCliCanvas) {
            this.draggingCliCanvas = false;
            this.cliCanvasDragScrollRemainder = 0.0D;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        drawShell(context);
        drawRows(context, mouseX, mouseY);
        drawInputBar(context);
        drawInputSuggestions(context, mouseX, mouseY);
        drawStatusBar(context);
        drawFeedbackPopup(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
        String mode = this.activeChannel == ConsoleChannel.CLI || this.automationMode ? "AUTOMATION" : "LOG";
        context.drawText(this.textRenderer, "mode: " + mode + "  channel: " + this.activeChannel.id().toUpperCase() + "  rows: " + scrollItemCount(), 16, this.height - STATUS_BAR_HEIGHT + 8, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
    }

    private boolean handleFeedbackPopupClick(double mouseX, double mouseY, int button) {
        if (button != 0 || (!this.feedbackSelectingNode && this.feedbackNode == null)) {
            return false;
        }
        int x = feedbackPopupX();
        int y = feedbackPopupY();
        int width = feedbackPopupWidth();
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + feedbackPopupHeight()) {
            this.feedbackSelectingNode = false;
            this.feedbackNode = null;
            this.feedbackFailureTypes = List.of();
            return false;
        }
        int row = ((int) mouseY - y - 26) / 18;
        if (row < 0) {
            return true;
        }
        if (this.feedbackSelectingNode) {
            List<AutomationFeedbackNode> nodes = AutomationFeedbackService.executableNodes(AutomationCliViewModel.snapshot());
            if (row < nodes.size()) {
                openFeedbackTypes(nodes.get(row));
                UiSoundHelper.playButtonClick();
            }
            return true;
        }
        if (this.feedbackNode != null && row < this.feedbackFailureTypes.size()) {
            AutomationFeedbackService.submitBad(this.feedbackNode, this.feedbackFailureTypes.get(row));
            this.feedbackNode = null;
            this.feedbackFailureTypes = List.of();
            UiSoundHelper.playButtonClick();
            return true;
        }
        return true;
    }

    private void openFeedbackTypes(AutomationFeedbackNode node) {
        this.feedbackSelectingNode = false;
        this.feedbackNode = node;
        this.feedbackFailureTypes = AutomationFailureRegistry.failureTypesFor(node.nodeType());
    }

    private void drawFeedbackPopup(DrawContext context, int mouseX, int mouseY) {
        if (!this.feedbackSelectingNode && this.feedbackNode == null) {
            return;
        }
        int x = feedbackPopupX();
        int y = feedbackPopupY();
        int width = feedbackPopupWidth();
        int height = feedbackPopupHeight();
        context.fill(x, y, x + width, y + height, withAlpha(uiColorContentBase, 230));
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());
        String title = this.feedbackSelectingNode ? "Select where it failed:" : "Select what went wrong:";
        context.drawText(this.textRenderer, title, x + 8, y + 8, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
        if (this.feedbackSelectingNode) {
            List<AutomationFeedbackNode> nodes = AutomationFeedbackService.executableNodes(AutomationCliViewModel.snapshot());
            drawFeedbackRows(context, x, y, width, nodes.stream().map(node -> node.nodeId() + "  " + node.nodeType()).toList());
        } else {
            drawFeedbackRows(context, x, y, width, this.feedbackFailureTypes.stream().map(AutomationFailureType::label).toList());
        }
    }

    private void drawFeedbackRows(DrawContext context, int x, int y, int width, List<String> rows) {
        int rowY = y + 26;
        int visible = Math.min(rows.size(), 9);
        for (int i = 0; i < visible; i++) {
            int top = rowY + i * 18;
            context.fill(x + 6, top, x + width - 6, top + 16, withAlpha(uiColorBackgroundOverlay, 46));
            context.drawBorder(x + 6, top, width - 12, 16, new Color(uiColorBackgroundBorder, true).getRGB());
            String text = rows.get(i);
            if (this.textRenderer.getWidth(text) > width - 22) {
                while (text.length() > 3 && this.textRenderer.getWidth(text + "...") > width - 22) {
                    text = text.substring(0, text.length() - 1);
                }
                text += "...";
            }
            context.drawText(this.textRenderer, text, x + 10, top + 4, new Color(uiColorContentBaseDescriptionText, true).getRGB(), false);
        }
    }

    private int feedbackPopupX() {
        return Math.max(14, this.width - feedbackPopupWidth() - 18);
    }

    private int feedbackPopupY() {
        return CONTENT_TOP + 18;
    }

    private int feedbackPopupWidth() {
        return Math.min(320, Math.max(230, this.width / 3));
    }

    private int feedbackPopupHeight() {
        int rows = this.feedbackSelectingNode
                ? Math.min(9, AutomationFeedbackService.executableNodes(AutomationCliViewModel.snapshot()).size())
                : Math.min(9, this.feedbackFailureTypes.size());
        return 34 + Math.max(1, rows) * 18;
    }

    private void drawRows(DrawContext context, int mouseX, int mouseY) {
        int viewportX = cliViewportX();
        int viewportY = cliViewportY();
        int scrollbarWidth = 8;
        int viewportWidth = cliViewportWidth();
        int viewportHeight = this.height - viewportY - inputBarHeight() - STATUS_BAR_HEIGHT - 22;
        if (this.activeChannel == ConsoleChannel.CLI) {
            int totalRows = AutomationCliCanvasRenderer.count(
                    AutomationCliViewModel.snapshot(),
                    this.searchField == null ? "" : this.searchField.getText()
            );
            if (this.cliAutoFocusLatest) {
                this.scrollOffset = focusLatestCliOffset(viewportHeight, totalRows);
            }
            AutomationCliCanvasRenderer.render(
                    context,
                    this.textRenderer,
                    AutomationCliViewModel.snapshot(),
                    viewportX,
                    viewportY,
                    viewportWidth,
                    viewportHeight,
                    smoothedCliScrollOffset(),
                    this.cliCanvasPanX,
                    this.searchField == null ? "" : this.searchField.getText(),
                    mouseX,
                    mouseY
            );
            int visible = visibleScrollCapacity(viewportHeight);
            drawScrollbar(context, viewportX + viewportWidth + 6, viewportY, scrollbarWidth, viewportHeight, totalRows, visible);
            return;
        }
        List<ConsoleStyledLine> visibleLines = filteredLines();
        int bottom = viewportY + viewportHeight;
        int visible = visibleLineCapacity(viewportHeight);
        int endIndex = visibleLines.size() - this.scrollOffset;
        int startIndex = Math.max(0, endIndex - visible);
        context.enableScissor(viewportX, viewportY, viewportX + viewportWidth, viewportY + viewportHeight);
        int y = viewportY;
        for (int i = startIndex; i < endIndex; i++) {
            ConsoleStyledLine line = visibleLines.get(i);
            int x = viewportX + 8;
            for (ConsoleStyledSpan span : line.spans()) {
                context.drawText(this.textRenderer, span.text(), x, y, span.color(), false);
                x += this.textRenderer.getWidth(span.text());
                if (x > viewportX + viewportWidth - 16) {
                    break;
                }
            }
            y += this.textRenderer.fontHeight + 4;
            if (y > bottom) {
                break;
            }
        }
        context.disableScissor();
        drawScrollbar(context, viewportX + viewportWidth + 6, viewportY, scrollbarWidth, viewportHeight, visibleLines.size(), visible);
    }

    private List<ConsoleStyledLine> filteredLines() {
        String query = this.searchField == null ? "" : this.searchField.getText();
        if (this.activeChannel == ConsoleChannel.CLI) {
            return AutomationCliRenderer.render(AutomationCliViewModel.snapshot(), query);
        }
        return ConsoleDisplayService.filter(this.cachedLines, query);
    }

    private int visibleLineCapacity(int viewportHeight) {
        return Math.max(1, viewportHeight / (this.textRenderer.fontHeight + 4));
    }

    private int visibleScrollCapacity(int viewportHeight) {
        if (this.activeChannel == ConsoleChannel.CLI) {
            return AutomationCliCanvasRenderer.visibleUnits(viewportHeight);
        }
        return visibleLineCapacity(viewportHeight);
    }

    private int scrollItemCount() {
        String query = this.searchField == null ? "" : this.searchField.getText();
        if (this.activeChannel == ConsoleChannel.CLI) {
            return AutomationCliCanvasRenderer.count(AutomationCliViewModel.snapshot(), query);
        }
        return filteredLines().size();
    }

    private int scrollViewportHeight() {
        int viewportY = cliViewportY();
        return this.height - viewportY - inputBarHeight() - STATUS_BAR_HEIGHT - 22;
    }

    private int cliViewportX() {
        return 12;
    }

    private int cliViewportY() {
        return CONTENT_TOP + 8;
    }

    private int cliViewportWidth() {
        int scrollbarWidth = 8;
        return this.width - 24 - scrollbarWidth - 6;
    }

    private boolean isCliViewportClick(double mouseX, double mouseY) {
        int x = cliViewportX();
        int y = cliViewportY();
        int width = cliViewportWidth();
        int height = scrollViewportHeight();
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private void clearCliSelectionIfScrolledOut() {
        AutomationCliCanvasRenderer.clearSelectionIfOutsideViewport(
                AutomationCliViewModel.snapshot(),
                cliViewportWidth(),
                scrollViewportHeight(),
                this.scrollOffset,
                this.searchField == null ? "" : this.searchField.getText()
        );
    }

    private double smoothedCliScrollOffset() {
        double target = this.scrollOffset;
        double delta = target - this.renderedCliScrollOffset;
        if (Math.abs(delta) < 0.03D) {
            this.renderedCliScrollOffset = target;
        } else {
            this.renderedCliScrollOffset += delta * 0.72D;
        }
        return this.renderedCliScrollOffset;
    }

    private int focusLatestCliOffset(int viewportHeight, int totalRows) {
        String query = this.searchField == null ? "" : this.searchField.getText();
        return AutomationCliCanvasRenderer.focusOffset(AutomationCliViewModel.snapshot(), query, viewportHeight, totalRows);
    }

    private void drawShell(DrawContext context) {
        assert client != null;
        int topBarBackground = withAlpha(uiColorContentBase, 176);
        int topPanelBackground = withAlpha(uiColorContentBase, 196);
        int panelBackground = withAlpha(uiColorContentBase, 124);
        int panelBottom = this.height - STATUS_BAR_HEIGHT - 4;
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
        context.drawText(this.textRenderer, "Manager Menu - Console", 68, 35, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
        context.getMatrices().pop();
        context.fill(0, 0, this.width, this.height, KoilScreenBackgrounds.overlayColor(client));
        context.drawBorder(0, 0, this.width, this.height, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(0, 40, this.width, 68, new Color(uiColorHeader, true).getRGB());
        context.fill(0, 40, this.width, 68, topBarBackground);
        context.drawBorder(0, 40, this.width, 28, new Color(uiColorBackgroundBorder, true).getRGB());
        context.fill(10, CONTENT_TOP, this.width - 10, panelBottom, panelBackground);
        context.drawBorder(10, CONTENT_TOP, this.width - 20, panelBottom - CONTENT_TOP, new Color(uiColorBackgroundBorder, true).getRGB());
        int dividerY = panelBottom - inputBarHeight() - 6;
        context.fill(11, dividerY, this.width - 11, dividerY + 1, new Color(uiColorBackgroundBorder, true).getRGB());

        renderTopBarButton(context, 10, TOP_BAR_BACK_LABEL);
        renderTopBarButton(context, getTopBarChannelButtonX(TOP_BAR_KOIL_LABEL), TOP_BAR_KOIL_LABEL);
        renderTopBarButton(context, getTopBarChannelButtonX(TOP_BAR_PACKAGE_LABEL), TOP_BAR_PACKAGE_LABEL);
        renderTopBarButton(context, getTopBarChannelButtonX(TOP_BAR_MINECRAFT_LABEL), TOP_BAR_MINECRAFT_LABEL);
        renderTopBarButton(context, getTopBarChannelButtonX(TOP_BAR_CLI_LABEL), TOP_BAR_CLI_LABEL);
        renderTopBarButton(context, getTopBarPopButtonX(), TOP_BAR_POP_LABEL);
    }

    private void drawInputBar(DrawContext context) {
        int panelBottom = this.height - STATUS_BAR_HEIGHT - 4;
        int barHeight = inputBarHeight();
        int barY = panelBottom - barHeight - 4;
        context.fill(12, barY, this.width - 12, barY + barHeight, withAlpha(uiColorBackgroundOverlay, 38));
    }

    private void drawStatusBar(DrawContext context) {
        int y = this.height - STATUS_BAR_HEIGHT;
        context.fill(0, y, this.width, this.height, withAlpha(uiColorContentBase, 176));
        context.drawBorder(0, y, this.width, STATUS_BAR_HEIGHT, new Color(uiColorBackgroundBorder, true).getRGB());
    }

    private void renderTopBarButton(DrawContext context, int x, String label) {
        int width = getTopBarButtonWidth(label);
        context.fill(x, TopBarLayout.BUTTON_Y, x + width, TopBarLayout.BUTTON_Y + TopBarLayout.BUTTON_HEIGHT, withAlpha(uiColorContentBase, 176));
        context.drawBorder(x, TopBarLayout.BUTTON_Y, width, TopBarLayout.BUTTON_HEIGHT, new Color(uiColorBackgroundBorder, true).getRGB());
        int textX = x + Math.max(4, (width - this.textRenderer.getWidth(label)) / 2);
        context.drawText(this.textRenderer, label, textX, TopBarLayout.BUTTON_Y + 7, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
    }

    private boolean isTopBarButtonClicked(double mouseX, double mouseY, int x, int width) {
        return mouseX >= x && mouseX <= x + width && mouseY >= TopBarLayout.BUTTON_Y && mouseY <= TopBarLayout.BUTTON_Y + TopBarLayout.BUTTON_HEIGHT;
    }

    private TopBarLayout getTopBarLayout() {
        return new TopBarLayout(this.textRenderer, this.width);
    }

    private List<String> getTopBarActionLabels() {
        return List.of(TOP_BAR_KOIL_LABEL, TOP_BAR_PACKAGE_LABEL, TOP_BAR_MINECRAFT_LABEL, TOP_BAR_CLI_LABEL, TOP_BAR_POP_LABEL);
    }

    private int getTopBarChannelButtonX(String label) {
        List<String> labels = getTopBarActionLabels();
        int index = labels.indexOf(label);
        return getTopBarLayout().rightButtonX(labels, index);
    }

    private int getTopBarPopButtonX() {
        return getTopBarChannelButtonX(TOP_BAR_POP_LABEL);
    }

    private int getTopBarButtonWidth(String label) {
        return getTopBarLayout().buttonWidth(label);
    }

    private void updateInputLayout() {
        if (this.searchField != null) {
            TopBarLayout layout = getTopBarLayout();
            this.searchField.setX(layout.searchFieldX(TOP_BAR_BACK_LABEL));
            this.searchField.setY(TopBarLayout.SEARCH_FIELD_Y);
            this.searchField.setWidth(layout.searchFieldWidth(TOP_BAR_BACK_LABEL, getTopBarActionLabels(), this.width < 760 ? 132 : 220));
        }

        if (this.inputField != null) {
            int panelBottom = this.height - STATUS_BAR_HEIGHT - 4;
            int barHeight = inputBarHeight();
            int barY = panelBottom - barHeight - 4;
            int fieldHeight = this.inputField.getHeight();
            int fieldY = barY + Math.max(0, (barHeight - fieldHeight) / 2);
            this.inputField.setX(18);
            this.inputField.setY(fieldY);
            this.inputField.setWidth(this.width - 36);
        }
    }

    private int inputFieldHeight() {
        return Math.max(24, this.textRenderer.fontHeight + 12);
    }

    private int inputBarHeight() {
        return Math.max(30, inputFieldHeight() + 6);
    }

    private void drawScrollbar(DrawContext context, int x, int y, int width, int height, int totalRows, int visibleRows) {
        context.fill(x, y, x + width, y + height, withAlpha(uiColorBackgroundOverlay, 56));
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());
        if (totalRows <= visibleRows) {
            return;
        }
        int maxScroll = Math.max(1, totalRows - visibleRows);
        int thumbHeight = Math.max(18, (int) ((visibleRows / (double) totalRows) * (height - 2)));
        int trackHeight = height - 2 - thumbHeight;
        double ratio = this.scrollOffset / (double) maxScroll;
        int thumbOffset = trackHeight <= 0 ? 0 : (int) Math.round((this.activeChannel == ConsoleChannel.CLI ? ratio : 1.0 - ratio) * trackHeight);
        int thumbY = y + 1 + thumbOffset;
        context.fill(x + 1, thumbY, x + width - 1, thumbY + thumbHeight, withAlpha(uiColorContentBase, 204));
        context.drawBorder(x + 1, thumbY, width - 2, thumbHeight, new Color(uiColorBackgroundBorder, true).getRGB());
    }

    private ScrollbarMetrics scrollbarMetrics() {
        int viewportX = 12;
        int viewportY = CONTENT_TOP + 8;
        int scrollbarWidth = 8;
        int viewportWidth = this.width - 24 - scrollbarWidth - 6;
        int viewportHeight = scrollViewportHeight();
        int visible = visibleScrollCapacity(viewportHeight);
        int total = scrollItemCount();
        if (total <= visible) {
            return null;
        }
        int maxScroll = Math.max(1, total - visible);
        int thumbHeight = Math.max(18, (int) ((visible / (double) total) * (viewportHeight - 2)));
        int trackHeight = viewportHeight - 2 - thumbHeight;
        double ratio = this.scrollOffset / (double) maxScroll;
        int thumbOffset = trackHeight <= 0 ? 0 : (int) Math.round((this.activeChannel == ConsoleChannel.CLI ? ratio : 1.0 - ratio) * trackHeight);
        int x = viewportX + viewportWidth + 6;
        int y = viewportY;
        return new ScrollbarMetrics(x, y, scrollbarWidth, viewportHeight, y + 1 + thumbOffset, thumbHeight, maxScroll);
    }

    private void setScrollOffsetFromThumbTop(int thumbTop, ScrollbarMetrics scrollbar) {
        int minTop = scrollbar.y() + 1;
        int maxTop = scrollbar.y() + scrollbar.height() - 1 - scrollbar.thumbHeight();
        int clampedTop = Math.max(minTop, Math.min(maxTop, thumbTop));
        int trackHeight = Math.max(1, scrollbar.height() - 2 - scrollbar.thumbHeight());
        double ratio = (clampedTop - minTop) / (double) trackHeight;
        this.scrollOffset = (int) Math.round((this.activeChannel == ConsoleChannel.CLI ? ratio : 1.0 - ratio) * scrollbar.maxScroll());
        this.scrollOffset = Math.max(0, Math.min(scrollbar.maxScroll(), this.scrollOffset));
        if (this.activeChannel == ConsoleChannel.CLI) {
            this.renderedCliScrollOffset = this.scrollOffset;
            this.cliAutoFocusLatest = false;
        }
        this.autoScroll = this.scrollOffset == 0;
    }

    private record ScrollbarMetrics(int x, int y, int width, int height, int thumbY, int thumbHeight, int maxScroll) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }

        private boolean thumbContains(double mouseX, double mouseY) {
            return mouseX >= x + 1 && mouseX <= x + width - 1 && mouseY >= thumbY && mouseY <= thumbY + thumbHeight;
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void updateInputSuggestions() {
        this.inputSuggestions.clear();
        if (this.inputField == null || !this.inputField.isFocused()) {
            if (this.inputField != null) {
                this.inputField.setSuggestion("");
            }
            return;
        }
        this.inputSuggestions.addAll(ConsoleInputSuggestionService.suggestions(this.inputField.getText(), this.activeChannel, this.automationMode));
        this.selectedSuggestionIndex = clamp(this.selectedSuggestionIndex, 0, Math.max(0, this.inputSuggestions.size() - 1));
        this.inputField.setSuggestion(selectedSuggestionSuffix());
    }

    private void moveSuggestionSelection(int delta) {
        if (!isSuggestionVisible()) {
            return;
        }
        this.selectedSuggestionIndex = clamp(this.selectedSuggestionIndex + delta, 0, this.inputSuggestions.size() - 1);
        UiSoundHelper.playButtonClick();
    }

    private boolean acceptSelectedSuggestion() {
        if (!isSuggestionVisible()) {
            return false;
        }
        this.inputField.setText(this.inputSuggestions.get(this.selectedSuggestionIndex).value());
        this.inputField.setCursorToEnd();
        updateInputSuggestions();
        UiSoundHelper.playButtonClick();
        return true;
    }

    private boolean isSuggestionVisible() {
        return this.inputField != null && this.inputField.isFocused() && !this.inputSuggestions.isEmpty();
    }

    private String selectedSuggestionSuffix() {
        if (!isSuggestionVisible()) {
            return "";
        }
        String current = this.inputField.getText();
        String suggestion = this.inputSuggestions.get(this.selectedSuggestionIndex).value();
        if (current == null || current.isBlank() || !suggestion.startsWith(current)) {
            return "";
        }
        return suggestion.substring(current.length());
    }

    private void drawInputSuggestions(DrawContext context, int mouseX, int mouseY) {
        if (!isSuggestionVisible()) {
            return;
        }
        int rowHeight = ConsoleSuggestionPresentation.ROW_HEIGHT;
        int width = suggestionPopupWidth();
        int visibleRows = Math.min(ConsoleSuggestionPresentation.MAX_VISIBLE_ROWS, this.inputSuggestions.size());
        int height = 6 + (visibleRows * rowHeight) + 6;
        int x = this.inputField.getX();
        int y = this.inputField.getY() - height - 4;
        context.fill(x, y, x + width, y + height, withAlpha(uiColorContentBase, 234));
        context.drawBorder(x, y, width, height, new Color(uiColorBackgroundBorder, true).getRGB());
        for (int i = 0; i < visibleRows; i++) {
            int rowY = y + 4 + (i * rowHeight);
            boolean hovered = mouseX >= x + 1 && mouseX <= x + width - 1 && mouseY >= rowY && mouseY <= rowY + rowHeight - 1;
            if (i == this.selectedSuggestionIndex) {
                context.fill(x + 1, rowY, x + width - 1, rowY + rowHeight - 1, withAlpha(uiColorHeader, 144));
            } else if (hovered) {
                context.fill(x + 1, rowY, x + width - 1, rowY + rowHeight - 1, withAlpha(uiColorHeader, 96));
            }
            ConsoleInputSuggestionService.ConsoleInputSuggestion suggestion = this.inputSuggestions.get(i);
            ConsoleSuggestionPresentation.Presentation presentation = ConsoleSuggestionPresentation.present(suggestion.kind());
            int detailWidth = Math.min(ConsoleSuggestionPresentation.DETAIL_WIDTH, this.textRenderer.getWidth(suggestion.detail()));
            int detailX = x + width - 8 - detailWidth;
            context.drawText(this.textRenderer, presentation.label(), x + ConsoleSuggestionPresentation.PADDING, rowY + 4, presentation.color(), false);
            context.drawText(this.textRenderer, fitText(suggestion.value(), Math.max(56, detailX - (x + 58) - 8)), x + 58, rowY + 4, new Color(uiColorContentBaseTitleText, true).getRGB(), false);
            if (!suggestion.detail().isBlank()) {
                context.drawText(this.textRenderer, fitText(suggestion.detail(), 116), detailX, rowY + 3, new Color(uiColorHeaderSubTitleText, true).getRGB(), false);
            }
        }
    }

    private int suggestionPopupWidth() {
        int valueWidth = 0;
        int detailWidth = 0;
        int visibleRows = Math.min(ConsoleSuggestionPresentation.MAX_VISIBLE_ROWS, this.inputSuggestions.size());
        for (int i = 0; i < visibleRows; i++) {
            ConsoleInputSuggestionService.ConsoleInputSuggestion suggestion = this.inputSuggestions.get(i);
            valueWidth = Math.max(valueWidth, this.textRenderer.getWidth(suggestion.value()));
            detailWidth = Math.max(detailWidth, this.textRenderer.getWidth(suggestion.detail()));
        }
        return Math.max(
                ConsoleSuggestionPresentation.MIN_WIDTH,
                Math.min(
                        ConsoleSuggestionPresentation.MAX_WIDTH,
                        18 + 58 + valueWidth + (detailWidth > 0 ? Math.min(ConsoleSuggestionPresentation.DETAIL_WIDTH, detailWidth) + 10 : 0)
                )
        );
    }

    private boolean usesFileBackedChannel(ConsoleChannel channel) {
        return channel == ConsoleChannel.MINECRAFT;
    }

    private Path channelLogPath(ConsoleChannel channel) {
        return switch (channel) {
            case KOIL -> Path.of("koil/logs/latest.log");
            case PACKAGE -> Path.of("koil/logs/package/latest.log");
            case MINECRAFT -> Path.of("logs/latest.log");
            case CLI -> Path.of("koil/logs/cli/latest.log");
        };
    }

    private void pollActiveFileChannel() {
        if (!usesFileBackedChannel(this.activeChannel)) {
            return;
        }
        long stamp = fileStamp(channelLogPath(this.activeChannel));
        if (stamp != this.lastFileStamp) {
            this.lastFileStamp = stamp;
            reloadSnapshot();
            if (this.autoScroll) {
                this.scrollOffset = 0;
            }
        }
    }

    private long fileStamp(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : Long.MIN_VALUE;
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String fitText(String text, int maxWidth) {
        if (text == null || this.textRenderer.getWidth(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        String ellipsis = "...";
        int ellipsisWidth = this.textRenderer.getWidth(ellipsis);
        String candidate = text;
        while (!candidate.isEmpty() && this.textRenderer.getWidth(candidate) + ellipsisWidth > maxWidth) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate + ellipsis;
    }

    @Override
    public void resize(MinecraftClient client, int width, int height) {
        this.width = width;
        this.height = height;
        updateInputLayout();
    }

    private int withAlpha(int argb, int alpha) {
        Color color = new Color(argb, true);
        int clampedAlpha = Math.max(0, Math.min(255, alpha));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clampedAlpha).getRGB();
    }
}
