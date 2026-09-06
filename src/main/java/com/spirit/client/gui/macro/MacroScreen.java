package com.spirit.client.gui.macro;

import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.macro.MacroAction;
import com.spirit.koil.api.macro.MacroActionType;
import com.spirit.koil.api.macro.MacroDefinition;
import com.spirit.koil.api.macro.MacroInputNames;
import com.spirit.koil.api.macro.MacroRegistry;
import com.spirit.koil.api.macro.MacroRuntime;
import com.spirit.koil.api.macro.MacroTriggerType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class MacroScreen extends Screen {
    private static final int OPTIONS_HEADER_BOTTOM = 36;
    private static final int VANILLA_BUTTON_WIDTH = 150;
    private static final int VANILLA_COLUMN_GAP = 10;
    private static final int MACRO_ROW_HEIGHT = 24;
    private static final int ACTION_ROW_HEIGHT = 24;

    private final Screen parent;
    private View view = View.LIST;

    private TextFieldWidget searchField;
    private String searchQuery = "";
    private String selectedId;
    private double listScroll;
    private long lastMacroClick;

    private String editingId;
    private String draftName = "New Macro";
    private boolean editingEnabled = true;
    private MacroTriggerType triggerType = MacroTriggerType.NONE;
    private int triggerCode = -1;
    private final List<MacroAction> actions = new ArrayList<>();
    private int selectedAction = -1;
    private double actionScroll;
    private long lastActionClick;

    private TextFieldWidget nameField;
    private ButtonWidget enabledButton;
    private ButtonWidget bindButton;
    private ButtonWidget addActionButton;
    private ButtonWidget duplicateActionButton;
    private ButtonWidget actionTypeButton;
    private ButtonWidget actionInputButton;
    private TextFieldWidget commandField;
    private TextFieldWidget durationField;
    private TextFieldWidget xField;
    private TextFieldWidget yField;
    private boolean capturingTrigger;
    private boolean capturingNewAction;
    private boolean capturingActionInput;

    public MacroScreen(Screen parent) {
        super(Text.literal("Macros"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (this.view == View.EDIT) {
            initEditor();
        } else {
            initList();
        }
    }

    private void initList() {
        int searchWidth = Math.min(vanillaPairWidth(), Math.max(90, this.width - 20));
        this.searchField = new TextFieldWidget(
            this.textRenderer,
            (this.width - searchWidth) / 2,
            42,
            searchWidth,
            20,
            Text.literal("Search Macros")
        );
        this.searchField.setPlaceholder(Text.literal("Search macros..."));
        this.searchField.setMaxLength(128);
        this.searchField.setText(this.searchQuery);
        this.searchField.setChangedListener(value -> {
            this.searchQuery = value;
            this.listScroll = 0.0D;
        });
        this.addDrawableChild(this.searchField);

        int footerWidth = vanillaPairWidth();
        int gap = 5;
        int buttonWidth = Math.max(20, (footerWidth - gap * 2) / 3);
        int actualWidth = buttonWidth * 3 + gap * 2;
        int leftX = (this.width - actualWidth) / 2;
        int middleX = leftX + buttonWidth + gap;
        int rightX = middleX + buttonWidth + gap;
        int firstRowY = this.height - 53;
        int secondRowY = this.height - 29;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Add Macro"), button -> addMacro())
            .dimensions(leftX, firstRowY, buttonWidth, 20).build());

        ButtonWidget editButton = this.addDrawableChild(
            ButtonWidget.builder(Text.literal("Edit"), button -> selectedMacro().ifPresent(this::beginEdit))
                .dimensions(middleX, firstRowY, buttonWidth, 20)
                .build()
        );

        ButtonWidget runButton = this.addDrawableChild(
            ButtonWidget.builder(Text.literal("Run"), button -> runSelected())
                .dimensions(rightX, firstRowY, buttonWidth, 20)
                .build()
        );

        ButtonWidget duplicateButton = this.addDrawableChild(
            ButtonWidget.builder(Text.literal("Duplicate"), button -> duplicateSelectedMacro())
                .dimensions(leftX, secondRowY, buttonWidth, 20)
                .build()
        );

        ButtonWidget deleteButton = this.addDrawableChild(
            ButtonWidget.builder(Text.literal("Delete"), button -> deleteSelected())
                .dimensions(middleX, secondRowY, buttonWidth, 20)
                .build()
        );

        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
            .dimensions(rightX, secondRowY, buttonWidth, 20).build());

        boolean selected = selectedMacro().isPresent();
        duplicateButton.active = selected;
        editButton.active = selected;
        deleteButton.active = selected;
        runButton.active = selected && this.client != null
            && this.client.world != null && this.client.player != null;
        clampListScroll();
    }

    private void initEditor() {
        resetEditorWidgetReferences();

        int totalWidth = vanillaPairWidth();
        int left = (this.width - totalWidth) / 2;
        int gap = VANILLA_COLUMN_GAP;
        int enabledWidth = Math.min(100, Math.max(70, totalWidth / 3));
        int nameWidth = Math.max(40, totalWidth - gap - enabledWidth);

        this.nameField = new TextFieldWidget(
            this.textRenderer,
            left,
            42,
            nameWidth,
            20,
            Text.literal("Macro Name")
        );
        this.nameField.setMaxLength(96);
        this.nameField.setText(this.draftName);
        this.nameField.setPlaceholder(Text.literal("Macro name"));
        this.addDrawableChild(this.nameField);

        this.enabledButton = this.addDrawableChild(ButtonWidget.builder(enabledText(), button -> {
            this.editingEnabled = !this.editingEnabled;
            button.setMessage(enabledText());
        }).dimensions(left + nameWidth + gap, 42, enabledWidth, 20).build());

        this.bindButton = this.addDrawableChild(ButtonWidget.builder(bindText(), button -> {
            captureEditorFields();
            stopInputCapture();
            this.capturingTrigger = true;
            button.setMessage(Text.literal("Press input..."));
        }).dimensions(left, 66, totalWidth, 20).build());

        addActionToolbar();
        addSelectedActionWidgets();
        addEditorFooter();
        clampActionScroll();
    }

    private void resetEditorWidgetReferences() {
        this.nameField = null;
        this.enabledButton = null;
        this.bindButton = null;
        this.addActionButton = null;
        this.duplicateActionButton = null;
        this.actionTypeButton = null;
        this.actionInputButton = null;
        this.commandField = null;
        this.durationField = null;
        this.xField = null;
        this.yField = null;
    }

    private void addActionToolbar() {
        int gap = 4;
        int removeWidth = 64;
        int moveWidth = 48;
        int total = moveWidth * 2 + removeWidth + gap * 2;
        int x = listLeft() + listWidth() - total;
        int y = 90;

        ButtonWidget upButton = this.addDrawableChild(
            ButtonWidget.builder(Text.literal("Up"), button -> moveSelectedAction(-1))
                .dimensions(x, y, moveWidth, 20)
                .build()
        );
        x += moveWidth + gap;

        ButtonWidget downButton = this.addDrawableChild(
            ButtonWidget.builder(Text.literal("Down"), button -> moveSelectedAction(1))
                .dimensions(x, y, moveWidth, 20)
                .build()
        );
        x += moveWidth + gap;

        ButtonWidget removeButton = this.addDrawableChild(
            ButtonWidget.builder(Text.literal("Remove"), button -> removeSelectedAction())
                .dimensions(x, y, removeWidth, 20)
                .build()
        );

        upButton.active = this.selectedAction > 0;
        downButton.active = this.selectedAction >= 0 && this.selectedAction + 1 < this.actions.size();
        removeButton.active = selectedAction() != null;
    }

    private void addSelectedActionWidgets() {
        MacroAction action = selectedAction();
        if (action == null) {
            return;
        }

        int gap = 4;
        int totalWidth = vanillaPairWidth();
        int left = (this.width - totalWidth) / 2;
        int y = this.height - 53;
        int typeWidth = Math.min(105, Math.max(72, totalWidth / 3));

        this.actionTypeButton = this.addDrawableChild(ButtonWidget.builder(
            Text.literal(action.type().displayName()),
            button -> cycleSelectedActionType()
        ).dimensions(left, y, typeWidth, 20).build());

        int valueX = left + typeWidth + gap;
        int remaining = Math.max(1, totalWidth - typeWidth - gap);
        switch (action.type()) {
            case COMMAND -> {
                this.commandField = new TextFieldWidget(
                    this.textRenderer,
                    valueX,
                    y,
                    remaining,
                    20,
                    Text.literal("Command")
                );
                this.commandField.setMaxLength(2048);
                this.commandField.setText(action.text());
                this.commandField.setPlaceholder(Text.literal("/command arguments"));
                this.addDrawableChild(this.commandField);
            }
            case KEY, MOUSE_BUTTON -> {
                int durationWidth = Math.min(78, Math.max(56, remaining / 4));
                int inputWidth = Math.max(1, remaining - durationWidth - gap);
                this.actionInputButton = this.addDrawableChild(ButtonWidget.builder(
                    actionInputText(action),
                    button -> {
                        captureEditorFields();
                        stopInputCapture();
                        this.capturingActionInput = true;
                        button.setMessage(Text.literal("Press input..."));
                    }
                ).dimensions(valueX, y, inputWidth, 20).build());
                this.durationField = textField(
                    valueX + inputWidth + gap,
                    y,
                    durationWidth,
                    Integer.toString(action.durationTicks()),
                    "Ticks"
                );
            }
            case MOUSE_MOVE -> {
                int fieldWidth = Math.max(1, (remaining - gap) / 2);
                this.xField = textField(valueX, y, fieldWidth, format(action.x()), "Look X");
                this.yField = textField(
                    valueX + fieldWidth + gap,
                    y,
                    Math.max(1, remaining - fieldWidth - gap),
                    format(action.y()),
                    "Look Y"
                );
            }
            case WAIT -> this.durationField = textField(
                valueX,
                y,
                remaining,
                Integer.toString(action.durationTicks()),
                "Wait ticks"
            );
        }
    }

    private TextFieldWidget textField(int x, int y, int width, String value, String placeholder) {
        TextFieldWidget field = new TextFieldWidget(
            this.textRenderer,
            x,
            y,
            width,
            20,
            Text.literal(placeholder)
        );
        field.setMaxLength(32);
        field.setText(value);
        field.setPlaceholder(Text.literal(placeholder));
        return this.addDrawableChild(field);
    }

    private void addEditorFooter() {
        int footerWidth = vanillaPairWidth();
        int gap = 4;
        int buttonWidth = Math.max(20, (footerWidth - gap * 3) / 4);
        int actualWidth = buttonWidth * 4 + gap * 3;
        int x = (this.width - actualWidth) / 2;
        int y = this.height - 29;

        this.addActionButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Add Action"), button -> {
            captureEditorFields();
            stopInputCapture();
            this.capturingNewAction = true;
            button.setMessage(Text.literal("Press input..."));
        }).dimensions(x, y, buttonWidth, 20).build());
        x += buttonWidth + gap;

        this.duplicateActionButton = this.addDrawableChild(
            ButtonWidget.builder(Text.literal("Duplicate"), button -> duplicateSelectedAction())
                .dimensions(x, y, buttonWidth, 20)
                .build()
        );
        this.duplicateActionButton.active = selectedAction() != null;
        x += buttonWidth + gap;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> saveEdit())
            .dimensions(x, y, buttonWidth, 20).build());
        x += buttonWidth + gap;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Back"), button -> leaveEditor())
            .dimensions(x, y, buttonWidth, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (this.view == View.EDIT) {
            renderEditor(context, mouseX, mouseY, delta);
        } else {
            renderList(context, mouseX, mouseY, delta);
        }
    }

    private void renderList(DrawContext context, int mouseX, int mouseY, float delta) {
        int top = listTop();
        int bottom = listBottom();
        KoilVanillaScreenChrome.renderListShell(
            context,
            this.client,
            this.width,
            this.height,
            OPTIONS_HEADER_BOTTOM,
            bottom
        );
        KoilVanillaScreenChrome.renderTitle(context, this.textRenderer, Text.literal("Options"), this.title);
        renderMacroRows(context, mouseX, mouseY, top, bottom);
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderEditor(DrawContext context, int mouseX, int mouseY, float delta) {
        int top = actionListTop();
        int bottom = actionListBottom();
        KoilVanillaScreenChrome.renderListShell(
            context,
            this.client,
            this.width,
            this.height,
            OPTIONS_HEADER_BOTTOM,
            editorFooterTop()
        );
        KoilVanillaScreenChrome.renderTitle(
            context,
            this.textRenderer,
            Text.literal("Macros"),
            Text.literal("Edit Macro")
        );
        context.drawText(this.textRenderer, Text.literal("Actions"), listLeft(), 96, 0xFFFFFFFF, true);
        renderActionRows(context, mouseX, mouseY, top, bottom);
        if (selectedAction() == null) {
            context.drawCenteredTextWithShadow(
                this.textRenderer,
                Text.literal("Select an action, or add a new one."),
                this.width / 2,
                this.height - 72,
                0xFFB0B0B0
            );
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderMacroRows(DrawContext context, int mouseX, int mouseY, int top, int bottom) {
        List<MacroDefinition> macros = filteredMacros();
        int left = listLeft();
        int width = listWidth();
        context.enableScissor(left, top, left + width, bottom);
        int y = top + 2 - (int) this.listScroll;

        if (macros.isEmpty()) {
            context.drawCenteredTextWithShadow(
                this.textRenderer,
                Text.literal("No macros found."),
                this.width / 2,
                top + 12,
                0xFFB0B0B0
            );
        }

        for (MacroDefinition macro : macros) {
            if (y + 20 >= top && y < bottom) {
                boolean selected = macro.id().equals(this.selectedId);
                boolean hovered = mouseX >= left && mouseX < left + width
                    && mouseY >= y && mouseY < y + 20;
                renderVanillaSelection(context, left, y, width, 20, selected, hovered);

                String name = trim(macro.name(), Math.max(40, width / 2 - 16));
                int nameColor = macro.enabled() ? 0xFFFFFFFF : 0xFF909090;
                context.drawText(
                    this.textRenderer,
                    name,
                    left + 8,
                    y + 6,
                    nameColor,
                    true
                );

                String detail = macro.actions().size()
                    + (macro.actions().size() == 1 ? " action  " : " actions  ")
                    + MacroInputNames.triggerName(macro.triggerType(), macro.triggerCode());
                detail = trim(detail, Math.max(40, width / 2 - 16));
                context.drawText(
                    this.textRenderer,
                    detail,
                    left + width - this.textRenderer.getWidth(detail) - 8,
                    y + 6,
                    0xFFB0B0B0,
                    false
                );
            }
            y += MACRO_ROW_HEIGHT;
        }

        context.disableScissor();
        renderScrollbar(context, macros.size(), MACRO_ROW_HEIGHT, this.listScroll, top, bottom, left, width);
    }

    private void renderActionRows(DrawContext context, int mouseX, int mouseY, int top, int bottom) {
        int left = listLeft();
        int width = listWidth();
        context.enableScissor(left, top, left + width, bottom);
        int y = top + 2 - (int) this.actionScroll;

        if (this.actions.isEmpty()) {
            context.drawCenteredTextWithShadow(
                this.textRenderer,
                Text.literal("No actions."),
                this.width / 2,
                top + 12,
                0xFFB0B0B0
            );
        }

        for (int index = 0; index < this.actions.size(); index++) {
            MacroAction action = this.actions.get(index);
            if (y + 20 >= top && y < bottom) {
                boolean selected = index == this.selectedAction;
                boolean hovered = mouseX >= left && mouseX < left + width
                    && mouseY >= y && mouseY < y + 20;
                renderVanillaSelection(context, left, y, width, 20, selected, hovered);

                String label = (index + 1) + ". " + action.type().displayName();
                context.drawText(
                    this.textRenderer,
                    label,
                    left + 8,
                    y + 6,
                    0xFFFFFFFF,
                    true
                );

                String summary = trim(actionSummary(action), Math.max(40, width - 150));
                context.drawText(
                    this.textRenderer,
                    summary,
                    left + width - this.textRenderer.getWidth(summary) - 8,
                    y + 6,
                    0xFFB0B0B0,
                    false
                );
            }
            y += ACTION_ROW_HEIGHT;
        }

        context.disableScissor();
        renderScrollbar(
            context,
            this.actions.size(),
            ACTION_ROW_HEIGHT,
            this.actionScroll,
            top,
            bottom,
            left,
            width
        );
    }

    private static void renderVanillaSelection(
        DrawContext context,
        int x,
        int y,
        int width,
        int height,
        boolean selected,
        boolean hovered
    ) {
        if (selected) {
            context.fill(x, y, x + width, y + height, 0x50000000);
            context.drawBorder(x, y, width, height, 0xFFFFFFFF);
        } else if (hovered) {
            context.fill(x, y, x + width, y + height, 0x30000000);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.view == View.EDIT) {
            if (this.capturingNewAction) {
                addCapturedAction(MacroActionType.MOUSE_BUTTON, button);
                return true;
            }
            if (this.capturingActionInput) {
                replaceSelectedInput(MacroActionType.MOUSE_BUTTON, button);
                return true;
            }
            if (this.capturingTrigger) {
                this.triggerType = MacroTriggerType.MOUSE;
                this.triggerCode = button;
                stopInputCapture();
                clearAndInit();
                return true;
            }
            if (super.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            return clickActionRow(mouseX, mouseY);
        }

        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return clickMacroRow(mouseX, mouseY);
    }

    private boolean clickMacroRow(double mouseX, double mouseY) {
        if (mouseX < listLeft() || mouseX >= listLeft() + listWidth()
            || mouseY < listTop() || mouseY >= listBottom()) {
            return false;
        }
        int index = (int) ((mouseY - listTop() - 2 + this.listScroll) / MACRO_ROW_HEIGHT);
        List<MacroDefinition> macros = filteredMacros();
        if (index < 0 || index >= macros.size()) {
            return false;
        }
        MacroDefinition macro = macros.get(index);
        long now = System.currentTimeMillis();
        boolean doubleClick = macro.id().equals(this.selectedId) && now - this.lastMacroClick < 350L;
        this.selectedId = macro.id();
        this.lastMacroClick = now;
        if (doubleClick) {
            beginEdit(macro);
        } else {
            clearAndInit();
        }
        return true;
    }

    private boolean clickActionRow(double mouseX, double mouseY) {
        if (mouseX < listLeft() || mouseX >= listLeft() + listWidth()
            || mouseY < actionListTop() || mouseY >= actionListBottom()) {
            return false;
        }
        int index = (int) ((mouseY - actionListTop() - 2 + this.actionScroll) / ACTION_ROW_HEIGHT);
        if (index < 0 || index >= this.actions.size()) {
            return false;
        }
        captureEditorFields();
        long now = System.currentTimeMillis();
        boolean doubleClick = index == this.selectedAction && now - this.lastActionClick < 350L;
        this.selectedAction = index;
        this.lastActionClick = now;
        clearAndInit();
        if (doubleClick && selectedAction() != null
            && (selectedAction().type() == MacroActionType.KEY
            || selectedAction().type() == MacroActionType.MOUSE_BUTTON)) {
            this.capturingActionInput = true;
            if (this.actionInputButton != null) {
                this.actionInputButton.setMessage(Text.literal("Press input..."));
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (this.view == View.EDIT && mouseY >= actionListTop() && mouseY < actionListBottom()) {
            captureEditorFields();
            this.actionScroll -= amount * ACTION_ROW_HEIGHT;
            clampActionScroll();
            return true;
        }
        if (this.view == View.LIST && mouseY >= listTop() && mouseY < listBottom()) {
            this.listScroll -= amount * MACRO_ROW_HEIGHT;
            clampListScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.view == View.EDIT) {
            if (this.capturingNewAction) {
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                    stopInputCapture();
                    clearAndInit();
                } else {
                    addCapturedAction(MacroActionType.KEY, keyCode);
                }
                return true;
            }
            if (this.capturingActionInput) {
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                    stopInputCapture();
                    clearAndInit();
                } else {
                    replaceSelectedInput(MacroActionType.KEY, keyCode);
                }
                return true;
            }
            if (this.capturingTrigger) {
                if (keyCode == GLFW.GLFW_KEY_ESCAPE
                    || keyCode == GLFW.GLFW_KEY_BACKSPACE
                    || keyCode == GLFW.GLFW_KEY_DELETE) {
                    this.triggerType = MacroTriggerType.NONE;
                    this.triggerCode = -1;
                } else {
                    this.triggerType = MacroTriggerType.KEYBOARD;
                    this.triggerCode = keyCode;
                }
                stopInputCapture();
                clearAndInit();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void addMacro() {
        MacroDefinition macro = MacroDefinition.create();
        MacroRegistry.upsert(macro);
        this.selectedId = macro.id();
        this.searchQuery = "";
        this.listScroll = 0.0D;
        clearAndInit();
    }

    private void duplicateSelectedMacro() {
        selectedMacro().ifPresent(macro -> {
            MacroDefinition copy = new MacroDefinition(
                null,
                nextCopyName(macro.name()),
                macro.enabled(),
                MacroTriggerType.NONE,
                -1,
                macro.actions()
            );
            MacroRegistry.upsert(copy);
            this.selectedId = copy.id();
            this.searchQuery = "";
            this.listScroll = 0.0D;
            clearAndInit();
        });
    }

    private String nextCopyName(String originalName) {
        String base = originalName == null || originalName.isBlank() ? "New Macro" : originalName.strip();
        String candidate = base + " Copy";
        int number = 2;
        List<String> names = MacroRegistry.all().stream()
            .map(MacroDefinition::name)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .toList();
        while (names.contains(candidate.toLowerCase(Locale.ROOT))) {
            candidate = base + " Copy " + number++;
        }
        return candidate;
    }

    private void beginEdit(MacroDefinition macro) {
        if (macro == null) {
            return;
        }
        this.editingId = macro.id();
        this.draftName = macro.name();
        this.editingEnabled = macro.enabled();
        this.triggerType = macro.triggerType();
        this.triggerCode = macro.triggerCode();
        this.actions.clear();
        this.actions.addAll(macro.actions());
        this.selectedAction = this.actions.isEmpty() ? -1 : 0;
        this.actionScroll = 0.0D;
        this.view = View.EDIT;
        stopInputCapture();
        clearAndInit();
    }

    private void leaveEditor() {
        this.view = View.LIST;
        stopInputCapture();
        clearAndInit();
    }

    private void saveEdit() {
        captureEditorFields();
        MacroDefinition macro = new MacroDefinition(
            this.editingId,
            this.draftName,
            this.editingEnabled,
            this.triggerType,
            this.triggerCode,
            List.copyOf(this.actions)
        );
        MacroRegistry.upsert(macro);
        this.selectedId = macro.id();
        leaveEditor();
    }

    private void addCapturedAction(MacroActionType type, int code) {
        captureEditorFields();
        this.actions.add(new MacroAction(type, "", code, 1, 0.0D, 0.0D));
        this.selectedAction = this.actions.size() - 1;
        this.actionScroll = Double.MAX_VALUE;
        stopInputCapture();
        clampActionScroll();
        clearAndInit();
    }

    private void duplicateSelectedAction() {
        captureEditorFields();
        MacroAction current = selectedAction();
        if (current == null) {
            return;
        }
        MacroAction copy = new MacroAction(
            current.type(),
            current.text(),
            current.code(),
            current.durationTicks(),
            current.x(),
            current.y()
        );
        int insertAt = this.selectedAction + 1;
        this.actions.add(insertAt, copy);
        this.selectedAction = insertAt;
        keepSelectedActionVisible();
        clearAndInit();
    }

    private void keepSelectedActionVisible() {
        int viewport = Math.max(1, actionListBottom() - actionListTop());
        int rowTop = this.selectedAction * ACTION_ROW_HEIGHT;
        int rowBottom = rowTop + ACTION_ROW_HEIGHT;
        if (rowTop < this.actionScroll) {
            this.actionScroll = rowTop;
        } else if (rowBottom > this.actionScroll + viewport) {
            this.actionScroll = rowBottom - viewport;
        }
        clampActionScroll();
    }

    private void replaceSelectedInput(MacroActionType type, int code) {
        captureEditorFields();
        MacroAction current = selectedAction();
        if (current != null) {
            this.actions.set(
                this.selectedAction,
                new MacroAction(type, "", code, current.durationTicks(), current.x(), current.y())
            );
        }
        stopInputCapture();
        clearAndInit();
    }

    private void cycleSelectedActionType() {
        captureEditorFields();
        MacroAction current = selectedAction();
        if (current == null) {
            return;
        }
        MacroActionType next = current.type().next();
        MacroAction defaults = MacroAction.defaults(next);
        int nextCode = next == MacroActionType.KEY || next == MacroActionType.MOUSE_BUTTON
            ? defaults.code()
            : current.code();
        this.actions.set(
            this.selectedAction,
            new MacroAction(
                next,
                next == MacroActionType.COMMAND && !current.text().isBlank() ? current.text() : defaults.text(),
                nextCode,
                current.durationTicks(),
                next == MacroActionType.MOUSE_MOVE ? defaults.x() : current.x(),
                next == MacroActionType.MOUSE_MOVE ? defaults.y() : current.y()
            )
        );
        clearAndInit();
    }

    private void moveSelectedAction(int direction) {
        captureEditorFields();
        int target = this.selectedAction + direction;
        if (this.selectedAction < 0 || target < 0 || target >= this.actions.size()) {
            return;
        }
        Collections.swap(this.actions, this.selectedAction, target);
        this.selectedAction = target;
        keepSelectedActionVisible();
        clearAndInit();
    }

    private void removeSelectedAction() {
        captureEditorFields();
        if (this.selectedAction < 0 || this.selectedAction >= this.actions.size()) {
            return;
        }
        this.actions.remove(this.selectedAction);
        this.selectedAction = Math.min(this.selectedAction, this.actions.size() - 1);
        clampActionScroll();
        clearAndInit();
    }

    private void captureEditorFields() {
        if (this.nameField != null) {
            this.draftName = this.nameField.getText();
        }
        MacroAction current = selectedAction();
        if (current == null) {
            return;
        }
        String text = this.commandField == null ? current.text() : this.commandField.getText();
        int duration = this.durationField == null
            ? current.durationTicks()
            : parseInt(this.durationField.getText(), current.durationTicks());
        double x = this.xField == null ? current.x() : parseDouble(this.xField.getText(), current.x());
        double y = this.yField == null ? current.y() : parseDouble(this.yField.getText(), current.y());
        this.actions.set(
            this.selectedAction,
            new MacroAction(current.type(), text, current.code(), duration, x, y)
        );
    }

    private void stopInputCapture() {
        this.capturingTrigger = false;
        this.capturingNewAction = false;
        this.capturingActionInput = false;
    }

    private void deleteSelected() {
        selectedMacro().ifPresent(macro -> {
            if (this.client == null) {
                return;
            }
            this.client.setScreen(new ConfirmScreen(confirmed -> {
                if (confirmed) {
                    MacroRegistry.delete(macro.id());
                    this.selectedId = null;
                }
                if (this.client != null) {
                    this.client.setScreen(this);
                }
            }, Text.literal("Delete macro?"), Text.literal("Delete \"" + macro.name() + "\"?"), Text.literal("Delete"), ScreenTexts.CANCEL));
        });
    }

    private void runSelected() {
        if (this.selectedId != null && this.client != null
            && this.client.world != null && this.client.player != null) {
            MacroRuntime.runNow(this.selectedId);
            this.client.setScreen(null);
        }
    }

    private Optional<MacroDefinition> selectedMacro() {
        return MacroRegistry.find(this.selectedId);
    }

    private List<MacroDefinition> filteredMacros() {
        String query = this.searchQuery.strip().toLowerCase(Locale.ROOT);
        if (query.isBlank()) {
            return MacroRegistry.all();
        }
        return MacroRegistry.all().stream()
            .filter(macro -> macro.name().toLowerCase(Locale.ROOT).contains(query)
                || MacroInputNames.triggerName(macro.triggerType(), macro.triggerCode())
                .toLowerCase(Locale.ROOT)
                .contains(query))
            .toList();
    }

    private MacroAction selectedAction() {
        return this.selectedAction >= 0 && this.selectedAction < this.actions.size()
            ? this.actions.get(this.selectedAction)
            : null;
    }

    private Text enabledText() {
        return Text.literal(this.editingEnabled ? "Enabled" : "Disabled");
    }

    private Text bindText() {
        return Text.literal("Bind: " + MacroInputNames.triggerName(this.triggerType, this.triggerCode));
    }

    private Text actionInputText(MacroAction action) {
        boolean mouse = action.type() == MacroActionType.MOUSE_BUTTON;
        return Text.literal((mouse ? "Mouse: " : "Key: ") + MacroInputNames.keyName(mouse, action.code()));
    }

    private static String actionSummary(MacroAction action) {
        return switch (action.type()) {
            case COMMAND -> action.text().isBlank() ? "No command" : action.text();
            case KEY -> MacroInputNames.keyName(false, action.code()) + " for " + action.durationTicks() + " tick(s)";
            case MOUSE_MOVE -> "Look X " + format(action.x()) + " / Y " + format(action.y());
            case MOUSE_BUTTON -> MacroInputNames.keyName(true, action.code()) + " for " + action.durationTicks() + " tick(s)";
            case WAIT -> action.durationTicks() + " tick(s)";
        };
    }

    private void renderScrollbar(
        DrawContext context,
        int itemCount,
        int rowHeight,
        double scroll,
        int top,
        int bottom,
        int left,
        int width
    ) {
        int contentHeight = itemCount * rowHeight + 4;
        int viewport = bottom - top;
        if (contentHeight <= viewport) {
            return;
        }
        int trackX = left + width - 3;
        int thumbHeight = Math.max(18, viewport * viewport / contentHeight);
        int travel = Math.max(1, viewport - thumbHeight);
        int max = Math.max(1, contentHeight - viewport);
        int thumbY = top + (int) Math.round(travel * (scroll / max));
        context.fill(trackX, top, trackX + 2, bottom, 0x70000000);
        context.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, 0xFFD0D0D0);
    }

    private void clampListScroll() {
        int max = Math.max(0, filteredMacros().size() * MACRO_ROW_HEIGHT + 4 - (listBottom() - listTop()));
        this.listScroll = MathHelper.clamp(this.listScroll, 0.0D, max);
    }

    private void clampActionScroll() {
        int max = Math.max(0, this.actions.size() * ACTION_ROW_HEIGHT + 4 - (actionListBottom() - actionListTop()));
        this.actionScroll = MathHelper.clamp(this.actionScroll, 0.0D, max);
    }

    private int listTop() {
        return 68;
    }

    private int listBottom() {
        return Math.max(listTop(), this.height - 60);
    }

    private int actionListTop() {
        return 114;
    }

    private int actionListBottom() {
        return Math.max(actionListTop(), this.height - 60);
    }

    private int editorFooterTop() {
        return Math.max(OPTIONS_HEADER_BOTTOM, this.height - 35);
    }

    private int listWidth() {
        return Math.max(80, Math.min(420, this.width - 40));
    }

    private int listLeft() {
        return (this.width - listWidth()) / 2;
    }

    private int vanillaButtonWidth() {
        return Math.min(VANILLA_BUTTON_WIDTH, Math.max(40, (this.width - 30) / 2));
    }

    private int vanillaPairWidth() {
        return vanillaButtonWidth() * 2 + VANILLA_COLUMN_GAP;
    }

    private int vanillaLeftX() {
        return (this.width - vanillaPairWidth()) / 2;
    }

    private int vanillaRightX() {
        return vanillaLeftX() + vanillaButtonWidth() + VANILLA_COLUMN_GAP;
    }

    private String trim(String value, int width) {
        return this.textRenderer.trimToWidth(value == null ? "" : value, Math.max(1, width));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Math.max(1, Math.min(1200, Integer.parseInt(value.strip())));
        } catch (Exception ignored) {
            return Math.max(1, fallback);
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            double parsed = Double.parseDouble(value.strip());
            return Double.isFinite(parsed) ? Math.max(-10000.0D, Math.min(10000.0D, parsed)) : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String format(double value) {
        return value == Math.rint(value)
            ? Long.toString(Math.round(value))
            : String.format(Locale.ROOT, "%.2f", value);
    }

    @Override
    public void close() {
        if (this.view == View.EDIT) {
            leaveEditor();
        } else if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    private enum View {
        LIST,
        EDIT
    }
}
