package com.spirit.client.gui.skin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.spirit.client.gui.UiSoundHelper;
import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.entity.PlayerModelPart;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.spirit.Main.SUBLOGGER;

public class ChangeSkinScreen extends Screen {
    private final Screen parent;
    private final SkinLibrary library;
    private final String initialSelectedId;
    private List<SkinEntry> entries;
    private SkinEntry selected;
    private Identifier sessionSkin;
    private boolean sessionSlim;
    private float modelYaw = -28.0F;
    private float modelPitch = 0.0F;
    private float modelRoll = 0.0F;
    private double scrollOffset;
    private int previewLeft;
    private int previewTop;
    private int previewRight;
    private int previewBottom;
    private int listLeft;
    private int listTop;
    private int listRight;
    private int listBottom;
    private int rowStart;
    private int createRowTop;
    private int createRowBottom;
    private int rowHeight = 68;
    private int createRowHeight = 108;
    private Path draftPath;
    private String draftName = "";
    private boolean draftSlim;
    private boolean draftNameFocused;
    private String editingEntryId;
    private String editingName = "";
    private boolean editingSlim;
    private boolean editingNameFocused;
    private boolean draggingScrollbar;
    private int scrollbarDragOffset;
    private boolean draggingPreviewScrollbar;
    private int previewScrollbarDragOffset;
    private int previewScrollOffset;
    private String status = "Create a skin from a PNG, fetch a player's skin, edit saved skins, or apply one locally.";
    private boolean loadingOnline;

    public ChangeSkinScreen(Screen parent) {
        super(Text.literal("Change Skin"));
        this.parent = parent;
        this.library = SkinLibrary.get();
        this.initialSelectedId = null;
        this.sessionSkin = resolveSessionSkin();
    }

    public ChangeSkinScreen(Screen parent, String selectedId) {
        super(Text.literal("Change Skin"));
        this.parent = parent;
        this.library = SkinLibrary.get();
        this.initialSelectedId = selectedId;
        this.sessionSkin = resolveSessionSkin();
    }

    @Override
    protected void init() {
        this.library.load();
        this.entries = this.library.entries();
        calculateLayout();
        if (this.selected == null && this.initialSelectedId != null) {
            this.selected = this.library.get(this.initialSelectedId);
        }
        if (this.selected == null) {
            this.selected = this.library.activeEntry();
        }
        if (this.selected == null && !this.entries.isEmpty()) {
            this.selected = this.entries.get(0);
        }
        if (this.selected != null) {
            keepSelectedVisible();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        KoilVanillaScreenChrome.renderOptionsShell(context, this.client, this.width, this.height);
        KoilVanillaScreenChrome.renderTitle(context, this.textRenderer, Text.literal("Options"), Text.literal("Skin Changer"));
        calculateLayout();
        int headingY = this.previewTop + 12;
        context.drawTextWithShadow(this.textRenderer, Text.literal("Current / Selected Preview"), this.previewLeft + 10, headingY, 0xFFE6EAF0);
        drawPreviewScrollContent(context, mouseX, mouseY);
        int libraryY = this.listTop + 12;
        context.drawTextWithShadow(this.textRenderer, Text.literal("Skin Library"), this.listLeft + 10, libraryY, 0xFFE6EAF0);
        context.drawTextWithShadow(this.textRenderer, Text.literal(this.loadingOnline ? "Fetching skin data and saving valid textures locally..." : this.status), this.listLeft + 10, libraryY + 12, this.loadingOnline ? 0xFFE6C46A : 0xFF9DA7B6);
        drawCreateSkinRow(context, mouseX, mouseY);
        drawSkinRows(context, mouseX, mouseY);
        drawFooterButtons(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    private void calculateLayout() {
        int left = 38;
        int right = this.width - 38;
        int top = 34;
        int bottom = this.height - 40;
        int previewWidth = Math.max(270, Math.min(350, this.width / 3));
        this.previewLeft = left;
        this.previewTop = top;
        this.previewRight = left + previewWidth;
        this.previewBottom = bottom;
        this.listLeft = this.previewRight + 16;
        this.listRight = right;
        this.listTop = top;
        this.listBottom = bottom;
        this.createRowTop = this.listTop + 46;
        this.createRowBottom = this.createRowTop + this.createRowHeight;
        this.rowStart = this.createRowBottom + 8;
    }

    private int modelDataTop() {
        return this.previewBottom - 58;
    }

    private int modelDataBottom() {
        return this.previewBottom - 8;
    }

    private int vanillaPartsTop() {
        return modelDataTop() - 96;
    }

    private int previewViewportTop() {
        return this.previewTop + 30;
    }

    private int previewViewportBottom() {
        return this.previewBottom - 8;
    }

    private int previewContentHeight() {
        return 396;
    }

    private int maxPreviewScrollOffset() {
        return Math.max(0, previewContentHeight() - Math.max(1, previewViewportBottom() - previewViewportTop()));
    }

    private void drawPreviewScrollContent(DrawContext context, int mouseX, int mouseY) {
        this.previewScrollOffset = Math.max(0, Math.min(maxPreviewScrollOffset(), this.previewScrollOffset));
        int left = this.previewLeft + 8;
        int right = previewScrollbarTrackLeft() - 6;
        int viewportTop = previewViewportTop();
        int viewportBottom = previewViewportBottom();
        renderScissored(context, left, viewportTop, right, viewportBottom, () -> {
            int y = viewportTop - this.previewScrollOffset;
            Identifier previewSkin = previewSkin();
            boolean slim = previewSlim();
            int modelTop = y - 8;
            int modelCenter = modelTop + 92;
            float modelScale = Math.min(90.0F, Math.max(66.0F, (this.previewRight - this.previewLeft) / 3.9F));
            SkinModelRenderer.render(context, previewSkin, slim, (this.previewLeft + this.previewRight) / 2, modelCenter, modelScale, this.modelYaw, this.modelPitch, this.modelRoll);
            int dataTop = modelTop + 214;
            int dataBottom = dataTop + 58;
            drawPanel(context, left, dataTop, right, dataBottom, 0x3320242C, 0x66727C8D);
            String previewName = this.selected == null ? "Minecraft account skin" : this.selected.safeName();
            String previewSource = this.selected == null ? "Session" : this.selected.safeSource();
            context.drawTextWithShadow(this.textRenderer, Text.literal("Model Data"), left + 8, dataTop + 7, 0xFFE6EAF0);
            context.drawTextWithShadow(this.textRenderer, Text.literal(previewName), left + 8, dataTop + 20, 0xFFB7C0CF);
            context.drawTextWithShadow(this.textRenderer, Text.literal((slim ? "Slim" : "Regular") + "  |  " + previewSource), left + 8, dataTop + 33, 0xFF8F98A8);
            context.drawTextWithShadow(this.textRenderer, Text.literal(textureLabel()), left + 8, dataTop + 46, 0xFF7F8898);
            int vanillaY = dataBottom + 22;
            context.drawTextWithShadow(this.textRenderer, Text.literal("Vanilla Skin Parts"), left + 2, vanillaY, 0xFFE6EAF0);
            drawVanillaSkinParts(context, mouseX, mouseY, left + 2, vanillaY + 14);
        });
        drawPreviewScrollbar(context);
    }

    private void drawVanillaSkinParts(DrawContext context, int mouseX, int mouseY, int x, int y) {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (minecraft == null || minecraft.options == null) {
            return;
        }
        GameOptions options = minecraft.options;
        int gap = 4;
        int columnWidth = Math.max(82, (this.previewRight - this.previewLeft - 28 - gap) / 2);
        int row = 0;
        int column = 0;
        for (PlayerModelPart part : PlayerModelPart.values()) {
            if (part == PlayerModelPart.HAT) {
                continue;
            }
            int buttonX = x + column * (columnWidth + gap);
            int buttonY = y + row * 18;
            drawVanillaButton(context, buttonX, buttonY, columnWidth, 16, part.getOptionName().getString() + ": " + (options.isPlayerModelPartEnabled(part) ? "On" : "Off"), true, mouseX, mouseY);
            column++;
            if (column >= 2) {
                column = 0;
                row++;
            }
        }
        if (column != 0) {
            column = 0;
            row++;
        }
        int hatY = y + row * 18;
        drawVanillaButton(context, x, hatY, columnWidth, 16, PlayerModelPart.HAT.getOptionName().getString() + ": " + (options.isPlayerModelPartEnabled(PlayerModelPart.HAT) ? "On" : "Off"), true, mouseX, mouseY);
        net.minecraft.util.Arm arm = options.getMainArm().getValue();
        drawVanillaButton(context, x + columnWidth + gap, hatY, columnWidth, 16, "Main: " + (arm == net.minecraft.util.Arm.LEFT ? "Left" : "Right"), true, mouseX, mouseY);
    }


    private void drawVanillaButton(DrawContext context, int left, int top, int width, int height, String label, boolean enabled, int mouseX, int mouseY) {
        drawMinecraftButton(context, mouseX, mouseY, left, top, left + width, top + height, label);
    }

    private void drawMinecraftButton(DrawContext context, int mouseX, int mouseY, int left, int top, int right, int bottom, String label) {
        ButtonWidget button = ButtonWidget.builder(Text.literal(label), value -> {}).dimensions(left, top, right - left, bottom - top).build();
        button.render(context, mouseX, mouseY, 0.0F);
    }


    private void drawPreviewScrollbar(DrawContext context) {
        int max = maxPreviewScrollOffset();
        if (max <= 0) {
            return;
        }
        int trackLeft = previewScrollbarTrackLeft();
        int trackTop = previewScrollbarTrackTop();
        int trackBottom = previewScrollbarTrackBottom();
        int thumbTop = previewScrollbarThumbTop();
        int thumbHeight = previewScrollbarThumbHeight();
        int lineX = trackLeft + 1;
        context.fill(lineX, trackTop, lineX + 1, trackBottom, 0x664D5563);
        context.fill(trackLeft, thumbTop, trackLeft + 3, thumbTop + thumbHeight, 0xFF727C8D);
    }


    private int previewScrollbarTrackLeft() {
        return this.previewRight - 12;
    }

    private int previewScrollbarTrackTop() {
        return previewViewportTop();
    }

    private int previewScrollbarTrackBottom() {
        return previewViewportBottom();
    }

    private int previewScrollbarThumbHeight() {
        int trackHeight = Math.max(1, previewScrollbarTrackBottom() - previewScrollbarTrackTop());
        return Math.max(24, trackHeight * trackHeight / Math.max(trackHeight, previewContentHeight()));
    }

    private int previewScrollbarThumbTop() {
        int max = maxPreviewScrollOffset();
        int trackTop = previewScrollbarTrackTop();
        int movable = Math.max(0, previewScrollbarTrackBottom() - trackTop - previewScrollbarThumbHeight());
        if (max <= 0) {
            return trackTop;
        }
        return trackTop + this.previewScrollOffset * movable / max;
    }

    private void updatePreviewScrollFromThumb(double mouseY) {
        int max = maxPreviewScrollOffset();
        int movable = Math.max(1, previewScrollbarTrackBottom() - previewScrollbarTrackTop() - previewScrollbarThumbHeight());
        int top = (int) mouseY - this.previewScrollbarDragOffset;
        int clamped = Math.max(previewScrollbarTrackTop(), Math.min(previewScrollbarTrackTop() + movable, top));
        this.previewScrollOffset = Math.max(0, Math.min(max, (clamped - previewScrollbarTrackTop()) * max / movable));
    }

    private boolean handlePreviewClick(double mouseX, double mouseY) {
        int viewportTop = previewViewportTop();
        int y = viewportTop - this.previewScrollOffset;
        int dataTop = y - 14 + 212;
        int vanillaY = dataTop + 58 + 22 + 14;
        int left = this.previewLeft + 10;
        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (minecraft == null || minecraft.options == null) {
            return false;
        }
        GameOptions options = minecraft.options;
        int partGap = 4;
        int columnWidth = Math.max(82, (this.previewRight - this.previewLeft - 28 - partGap) / 2);
        int row = 0;
        int column = 0;
        for (PlayerModelPart part : PlayerModelPart.values()) {
            if (part == PlayerModelPart.HAT) {
                continue;
            }
            int buttonX = left + column * (columnWidth + partGap);
            int partButtonY = vanillaY + row * 18;
            if (isInside(mouseX, mouseY, buttonX, partButtonY, buttonX + columnWidth, partButtonY + 16)) {
                playClick();
                options.togglePlayerModelPart(part, !options.isPlayerModelPartEnabled(part));
                setStatus(part.getOptionName().getString() + " " + (options.isPlayerModelPartEnabled(part) ? "enabled" : "disabled") + ".");
                return true;
            }
            column++;
            if (column >= 2) {
                column = 0;
                row++;
            }
        }
        if (column != 0) {
            column = 0;
            row++;
        }
        int hatY = vanillaY + row * 18;
        if (isInside(mouseX, mouseY, left, hatY, left + columnWidth, hatY + 16)) {
            playClick();
            options.togglePlayerModelPart(PlayerModelPart.HAT, !options.isPlayerModelPartEnabled(PlayerModelPart.HAT));
            setStatus(PlayerModelPart.HAT.getOptionName().getString() + " " + (options.isPlayerModelPartEnabled(PlayerModelPart.HAT) ? "enabled" : "disabled") + ".");
            return true;
        }
        int mainHandX = left + columnWidth + partGap;
        if (isInside(mouseX, mouseY, mainHandX, hatY, mainHandX + columnWidth, hatY + 16)) {
            playClick();
            net.minecraft.util.Arm arm = options.getMainArm().getValue();
            options.getMainArm().setValue(arm == net.minecraft.util.Arm.LEFT ? net.minecraft.util.Arm.RIGHT : net.minecraft.util.Arm.LEFT);
            setStatus("Main hand changed.");
            return true;
        }
        return false;
    }


    private void renderScissored(DrawContext context, int left, int top, int right, int bottom, Runnable render) {
        context.enableScissor(left, top, right, bottom);
        try {
            render.run();
        } finally {
            context.disableScissor();
        }
    }

    private void addVanillaSkinOptionWidgets() {
    }

    private void drawCreateSkinRow(DrawContext context, int mouseX, int mouseY) {
        drawPanel(context, this.listLeft + 6, this.createRowTop, this.listRight - 6, this.createRowBottom, 0x331B1F26, this.draftPath == null ? 0x664D5563 : 0xFFE6C46A);
        int x = this.listLeft + 16;
        int y = this.createRowTop + 9;
        context.drawTextWithShadow(this.textRenderer, Text.literal("+ Upload skin"), x, y, 0xFFB7C0CF);
        context.drawTextWithShadow(this.textRenderer, Text.literal(this.draftPath == null ? "Open a PNG or type a player name, then press Save to fetch." : "Finish this skin card, then save or cancel."), x, y + 13, 0xFF8F98A8);
        int fieldTop = this.createRowTop + 42;
        int fieldLeft = x;
        int fieldRight = Math.min(this.listRight - 292, fieldLeft + 220);
        drawTextField(context, fieldLeft, fieldTop, fieldRight, fieldTop + 20, this.draftName, this.draftNameFocused, this.draftPath == null ? "Skin name or player name" : "Skin name");
        int openLeft = fieldRight + 8;
        int saveLeft = openLeft + 58;
        int cancelLeft = saveLeft + 58;
        drawCardButton(context, mouseX, mouseY, openLeft, fieldTop, openLeft + 52, fieldTop + 20, "Open", 0xFFE6EAF0);
        drawCardButton(context, mouseX, mouseY, saveLeft, fieldTop, saveLeft + 52, fieldTop + 20, "Save", 0xFF9FE6A0);
        drawCardButton(context, mouseX, mouseY, cancelLeft, fieldTop, cancelLeft + 58, fieldTop + 20, "Cancel", 0xFFE69F9F);
        drawCardButton(context, mouseX, mouseY, openLeft, fieldTop + 24, openLeft + 68, fieldTop + 42, this.draftSlim ? "Slim" : "Regular", 0xFFB7C0CF);
        String footer = this.draftPath == null ? "Save fetches the typed player when no PNG is open." : "PNG: " + this.draftPath.getFileName().toString();
        context.drawTextWithShadow(this.textRenderer, Text.literal(footer), x, this.createRowBottom - 15, 0xFF7F8898);
    }

    private void drawTextField(DrawContext context, int left, int top, int right, int bottom, String value, boolean focused, String placeholder) {
        TextFieldWidget widget = new TextFieldWidget(this.textRenderer, left, top, right - left, bottom - top, Text.empty());
        widget.setText(value == null ? "" : value);
        widget.setPlaceholder(Text.literal(placeholder == null ? "" : placeholder));
        widget.setFocused(focused);
        widget.setEditableColor(0xFFE6EAF0);
        widget.setUneditableColor(0xFFB7C0CF);
        widget.render(context, 0, 0, 0.0F);
    }

    private void drawSkinRows(DrawContext context, int mouseX, int mouseY) {
        if (this.entries == null) {
            this.entries = this.library.entries();
        }
        int viewportHeight = listViewportHeight();
        int firstIndex = firstVisibleSkinIndex();
        int lastIndex = lastVisibleSkinIndex();
        if (this.entries.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("No saved skins yet. Create one above or fetch a player's skin."), this.listLeft + 10, this.rowStart + 12, 0xFFB7C0CF);
            return;
        }
        this.scrollOffset = clampScroll(this.scrollOffset, maxScrollOffset());
        renderScissored(context, this.listLeft + 4, this.rowStart, this.listRight - 10, this.listBottom - 16, () -> {
            for (int index = firstIndex; index <= lastIndex; index++) {
                if (index < 0 || index >= this.entries.size()) {
                    continue;
                }
                SkinEntry entry = this.entries.get(index);
                int rowY = this.rowStart + (int) Math.round(index * this.rowHeight - this.scrollOffset);
                boolean active = this.selected != null && entry.id.equals(this.selected.id);
                int fill = active ? 0x66425163 : 0x3320242C;
                int border = active ? 0xFFB7C0CF : 0x44727C8D;
                drawPanel(context, this.listLeft + 6, rowY + 2, this.listRight - 12, rowY + this.rowHeight - 4, fill, border);
                if (entry.id.equals(this.editingEntryId)) {
                    drawEditableSavedRow(context, mouseX, mouseY, entry, rowY);
                } else {
                    drawSavedRow(context, mouseX, mouseY, entry, rowY);
                }
            }
        });
        drawScrollbar(context);
    }

    private void drawSavedRow(DrawContext context, int mouseX, int mouseY, SkinEntry entry, int rowY) {
        int textX = this.listLeft + 16;
        int buttonWidth = 52;
        int gap = 6;
        int buttonsLeft = this.listRight - 184;
        context.drawTextWithShadow(this.textRenderer, Text.literal(entry.safeName()), textX, rowY + 8, 0xFFE6EAF0);
        context.drawTextWithShadow(this.textRenderer, Text.literal(entry.modelLabel() + "  |  " + entry.width + "x" + entry.height + "  |  " + entry.safeSource()), textX, rowY + 21, 0xFFB7C0CF);
        context.drawTextWithShadow(this.textRenderer, Text.literal(entry.id.equals(this.library.activeId()) ? "Applied" : "Saved"), textX, rowY + 34, entry.id.equals(this.library.activeId()) ? 0xFF9FE6A0 : 0xFF8F98A8);
        int buttonY = rowY + 19;
        drawListButton(context, buttonsLeft, buttonY, buttonsLeft + buttonWidth, buttonY + 18, "Edit", 0xFFE6EAF0);
        drawListButton(context, buttonsLeft + buttonWidth + gap, buttonY, buttonsLeft + buttonWidth * 2 + gap, buttonY + 18, "Apply", 0xFF9FE6A0);
        drawListButton(context, buttonsLeft + (buttonWidth + gap) * 2, buttonY, buttonsLeft + buttonWidth * 3 + gap * 2, buttonY + 18, "Delete", 0xFFE69F9F);
    }

    private void drawEditableSavedRow(DrawContext context, int mouseX, int mouseY, SkinEntry entry, int rowY) {
        int textX = this.listLeft + 16;
        int fieldTop = rowY + 8;
        int buttonWidth = 52;
        int gap = 6;
        int buttonsLeft = this.listRight - 184;
        int fieldRight = Math.min(buttonsLeft - 10, textX + 230);
        drawTextField(context, textX, fieldTop, fieldRight, fieldTop + 20, this.editingName, this.editingNameFocused, entry.safeName());
        context.drawTextWithShadow(this.textRenderer, Text.literal(entry.width + "x" + entry.height + "  |  " + entry.safeSource()), textX, rowY + 34, 0xFFB7C0CF);
        int buttonY = rowY + 19;
        drawListButton(context, buttonsLeft, buttonY, buttonsLeft + buttonWidth, buttonY + 18, "Open", 0xFFE6EAF0);
        drawListButton(context, buttonsLeft + buttonWidth + gap, buttonY, buttonsLeft + buttonWidth * 2 + gap, buttonY + 18, "Save", 0xFF9FE6A0);
        drawListButton(context, buttonsLeft + (buttonWidth + gap) * 2, buttonY, buttonsLeft + buttonWidth * 3 + gap * 2, buttonY + 18, "Cancel", 0xFFE69F9F);
        drawListButton(context, buttonsLeft, rowY + 40, buttonsLeft + buttonWidth, rowY + 58, this.editingSlim ? "Slim" : "Regular", 0xFFB7C0CF);
    }

    private int footerButtonY() {
        return this.height - 28;
    }

    private int footerButtonWidth() {
        return 116;
    }

    private int footerButtonGap() {
        return 8;
    }

    private int footerStartX() {
        int buttonWidth = footerButtonWidth();
        int gap = footerButtonGap();
        return this.width / 2 - (buttonWidth * 3 + gap * 2) / 2;
    }

    private void drawFooterButtons(DrawContext context, int mouseX, int mouseY) {
        int y = footerButtonY();
        int w = footerButtonWidth();
        int gap = footerButtonGap();
        int x = footerStartX();
        drawMinecraftButton(context, mouseX, mouseY, x, y, x + w, y + 20, "Back");
        drawMinecraftButton(context, mouseX, mouseY, x + w + gap, y, x + w * 2 + gap, y + 20, "Use Account Skin");
        drawMinecraftButton(context, mouseX, mouseY, x + (w + gap) * 2, y, x + w * 3 + gap * 2, y + 20, "Apply Selected");
    }


    private boolean handleFooterClick(double mouseX, double mouseY) {
        int y = footerButtonY();
        int w = footerButtonWidth();
        int gap = footerButtonGap();
        int x = footerStartX();
        if (isInside(mouseX, mouseY, x, y, x + w, y + 20)) {
            playClick();
            this.client.setScreen(this.parent);
            return true;
        }
        if (isInside(mouseX, mouseY, x + w + gap, y, x + w * 2 + gap, y + 20)) {
            playClick();
            useAccountSkin();
            return true;
        }
        if (isInside(mouseX, mouseY, x + (w + gap) * 2, y, x + w * 3 + gap * 2, y + 20)) {
            playClick();
            applySelected();
            return true;
        }
        return false;
    }

    private void drawCardButton(DrawContext context, int mouseX, int mouseY, int left, int top, int right, int bottom, String label, int textColor) {
        boolean hovered = isInside(mouseX, mouseY, left, top, right, bottom);
        context.fill(left, top, right, bottom, hovered ? 0x44262C34 : 0x331B1F26);
        context.drawBorder(left, top, right - left, bottom - top, hovered ? 0x88727C8D : 0x664D5563);
        context.drawTextWithShadow(this.textRenderer, Text.literal(label), left + 5, top + 5, textColor);
    }

    private void drawListButton(DrawContext context, int left, int top, int right, int bottom, String label, int textColor) {
        context.fill(left, top, right, bottom, 0x331B1F26);
        context.drawBorder(left, top, right - left, bottom - top, 0x664D5563);
        context.drawTextWithShadow(this.textRenderer, Text.literal(label), left + 5, top + 4, textColor);
    }

    private void drawScrollbar(DrawContext context) {
        if (this.entries == null || maxScrollOffset() <= 0.0D) {
            return;
        }
        int trackLeft = scrollbarTrackLeft();
        int trackTop = scrollbarTrackTop();
        int trackBottom = scrollbarTrackBottom();
        int thumbTop = scrollbarThumbTop();
        int thumbBottom = thumbTop + scrollbarThumbHeight();
        int lineX = trackLeft + 1;
        context.fill(lineX, trackTop, lineX + 1, trackBottom, 0x664D5563);
        context.fill(trackLeft, thumbTop, trackLeft + 3, thumbBottom, 0xFF727C8D);
    }

    private int scrollbarTrackLeft() {
        return this.listRight - 8;
    }

    private int scrollbarTrackTop() {
        return this.rowStart + 2;
    }

    private int scrollbarTrackBottom() {
        return this.listBottom - 18;
    }

    private int scrollbarThumbHeight() {
        int trackHeight = Math.max(1, scrollbarTrackBottom() - scrollbarTrackTop());
        if (this.entries == null || this.entries.isEmpty()) {
            return trackHeight;
        }
        int contentHeight = Math.max(1, this.entries.size() * this.rowHeight);
        return Math.max(24, Math.min(trackHeight, trackHeight * listViewportHeight() / contentHeight));
    }

    private int scrollbarThumbTop() {
        double max = maxScrollOffset();
        int trackTop = scrollbarTrackTop();
        int movable = Math.max(0, scrollbarTrackBottom() - trackTop - scrollbarThumbHeight());
        if (max <= 0.0D) {
            return trackTop;
        }
        return trackTop + (int) Math.round(this.scrollOffset * movable / max);
    }

    private void updateScrollFromThumb(double mouseY) {
        double max = maxScrollOffset();
        int movable = Math.max(1, scrollbarTrackBottom() - scrollbarTrackTop() - scrollbarThumbHeight());
        int top = (int) mouseY - this.scrollbarDragOffset;
        int clamped = Math.max(scrollbarTrackTop(), Math.min(scrollbarTrackTop() + movable, top));
        this.scrollOffset = clampScroll((clamped - scrollbarTrackTop()) * max / movable, max);
    }

    private double maxScrollOffset() {
        if (this.entries == null) {
            return 0.0D;
        }
        return Math.max(0.0D, this.entries.size() * this.rowHeight - listViewportHeight());
    }

    private int listViewportHeight() {
        return Math.max(1, this.listBottom - 16 - this.rowStart);
    }

    private double clampScroll(double value, double max) {
        return Math.max(0.0D, Math.min(max, value));
    }

    private int firstVisibleSkinIndex() {
        return this.entries == null || this.entries.isEmpty() ? 0 : Math.max(0, (int) Math.floor(this.scrollOffset / this.rowHeight));
    }

    private int lastVisibleSkinIndex() {
        if (this.entries == null || this.entries.isEmpty()) {
            return 0;
        }
        int viewportHeight = listViewportHeight();
        return Math.min(this.entries.size() - 1, (int) Math.ceil((this.scrollOffset + viewportHeight) / this.rowHeight));
    }

    private Identifier previewSkin() {
        if (this.selected != null) {
            return this.library.texture(this.selected);
        }
        return this.sessionSkin;
    }

    private boolean previewSlim() {
        if (this.selected != null) {
            return this.selected.isSlim();
        }
        return this.sessionSlim;
    }

    private String textureLabel() {
        if (this.selected == null) {
            return this.sessionSkin == null ? "default" : this.sessionSkin.toString();
        }
        return this.selected.file == null ? "stored dynamic texture" : this.selected.file;
    }

    private Identifier resolveSessionSkin() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            GameProfile profile = client.getSession().getProfile();
            if (profile != null) {
                Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures = client.getSkinProvider().getTextures(profile);
                if (textures != null && textures.containsKey(MinecraftProfileTexture.Type.SKIN)) {
                    MinecraftProfileTexture skinTexture = textures.get(MinecraftProfileTexture.Type.SKIN);
                    this.sessionSlim = skinTexture.getMetadata("model") != null && "slim".equalsIgnoreCase(skinTexture.getMetadata("model"));
                    return client.getSkinProvider().loadSkin(skinTexture, MinecraftProfileTexture.Type.SKIN);
                }
            }
        } catch (Exception e) {
            SUBLOGGER.logE("Skin-Management thread", "Failed to resolve session skin: " + e.getMessage());
        }
        return new Identifier("minecraft", "textures/entity/steve.png");
    }

    private void openSkinFileChooser() {
        CompletableFuture.<String>supplyAsync(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.png"));
                filters.flip();
                return TinyFileDialogs.tinyfd_openFileDialog("Select Minecraft Skin", "", filters, "Minecraft skin PNG", false);
            }
        }).whenComplete((path, throwable) -> {
            if (throwable != null) {
                MinecraftClient.getInstance().execute(() -> setStatus("Failed to open file chooser."));
                SUBLOGGER.logE("Skin-Management thread", "Failed to open skin file chooser: " + throwable.getMessage());
                return;
            }
            if (path == null || path.trim().isEmpty()) {
                MinecraftClient.getInstance().execute(() -> setStatus("No skin selected."));
                return;
            }
            MinecraftClient.getInstance().execute(() -> startDraftUpload(Path.of(path)));
        });
    }

    private void startDraftUpload(Path path) {
        try {
            File file = path.toFile();
            if (!file.exists() || !file.isFile() || !file.getName().toLowerCase().endsWith(".png")) {
                setStatus("Invalid skin file. Select a 64x64 or legacy 64x32 PNG.");
                return;
            }
            this.draftPath = path;
            if (this.draftName == null || this.draftName.trim().isEmpty()) {
                this.draftName = stripPng(file.getName());
            }
            this.draftNameFocused = true;
            setStatus("Name the upload, pick Regular or Slim, then save it into the library.");
        } catch (Exception e) {
            setStatus("Upload setup failed: " + e.getMessage());
            SUBLOGGER.logE("Skin-Management thread", "Failed to start upload: " + e.getMessage());
        }
    }

    private void saveDraftUpload() {
        if (this.draftPath == null) {
            return;
        }
        try {
            String name = this.draftName == null || this.draftName.trim().isEmpty() ? stripPng(this.draftPath.getFileName().toString()) : this.draftName.trim();
            SkinEntry entry = this.library.addFromPath(this.draftPath, name, this.draftSlim, "Local", "Uploaded from file chooser", "");
            clearDraft();
            this.selected = entry;
            reloadEntries();
            keepSelectedVisible();
            setStatus("Saved " + entry.safeName() + " to the skin library.");
        } catch (Exception e) {
            setStatus("Save failed: " + e.getMessage());
            SUBLOGGER.logE("Skin-Management thread", "Failed to save pending upload: " + e.getMessage());
        }
    }

    private void clearDraft() {
        this.draftPath = null;
        this.draftName = "";
        this.draftSlim = false;
        this.draftNameFocused = false;
    }

    private void fetchTypedSkin() {
        String username = this.draftName == null || this.draftName.trim().isEmpty() ? MinecraftClient.getInstance().getSession().getUsername() : this.draftName.trim();
        fetchOnlineSkins(username);
    }

    private void fetchOnlineSkins(String username) {
        if (this.loadingOnline || username == null || username.trim().isEmpty()) {
            return;
        }
        this.loadingOnline = true;
        setStatus("Fetching skins for " + username + "...");
        CompletableFuture.supplyAsync(() -> SkinOnlineFetcher.fetchPlayerSkins(username, this.library)).whenComplete((count, throwable) -> MinecraftClient.getInstance().execute(() -> {
            this.loadingOnline = false;
            reloadEntries();
            if (throwable != null) {
                setStatus("Fetch failed for " + username + ": " + throwable.getMessage());
                SUBLOGGER.logE("Skin-Online thread", "Online fetch failed: " + throwable.getMessage());
                return;
            }
            setStatus(count == null || count == 0 ? "No new skins found for " + username + "." : "Added " + count + " fetched skin" + (count == 1 ? "" : "s") + " for " + username + ".");
        }));
    }

    private void editEntryInEditor(SkinEntry entry) {
        if (entry == null) {
            this.client.setScreen(new EditSkinScreen(this, null, this.sessionSkin, this.sessionSlim));
            return;
        }
        this.client.setScreen(new EditSkinScreen(this, entry.id, this.library.texture(entry), entry.isSlim()));
    }

    private void applySelected() {
        if (this.selected == null) {
            setStatus("Select a saved skin card first, or use the account skin button.");
            return;
        }
        applyEntry(this.selected);
    }

    private void applyEntry(SkinEntry entry) {
        if (entry == null) {
            return;
        }
        this.selected = entry;
        this.library.setActive(entry);
        setStatus("Applied " + entry.safeName() + ".");
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null) {
            player.sendMessage(Text.literal("Skin applied: " + entry.safeName()), true);
        }
        MinecraftClient.getInstance().worldRenderer.reload();
    }

    private void useAccountSkin() {
        this.selected = null;
        this.library.setActive(null);
        setStatus("Using Minecraft account skin.");
        MinecraftClient.getInstance().worldRenderer.reload();
    }

    private void deleteEntry(SkinEntry entry) {
        if (entry == null) {
            return;
        }
        String name = entry.safeName();
        this.library.delete(entry);
        if (this.selected != null && entry.id.equals(this.selected.id)) {
            this.selected = null;
        }
        if (entry.id.equals(this.editingEntryId)) {
            cancelEditingEntry();
        }
        reloadEntries();
        if (this.selected == null && !this.entries.isEmpty()) {
            this.selected = this.entries.get(Math.min(firstVisibleSkinIndex(), this.entries.size() - 1));
        }
        setStatus("Deleted " + name + ".");
    }

    private void confirmDeleteEntry(SkinEntry entry) {
        if (entry == null) {
            return;
        }
        String name = entry.safeName();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            deleteEntry(entry);
            return;
        }
        client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                deleteEntry(entry);
            }
            client.setScreen(this);
        }, Text.literal("Delete Skin"), Text.literal("Are you sure you want to delete " + name + "?"), Text.literal("Delete"), Text.literal("Cancel")));
    }

    private void startEditingEntry(SkinEntry entry) {
        if (entry == null) {
            return;
        }
        this.editingEntryId = entry.id;
        this.editingName = entry.safeName();
        this.editingSlim = entry.isSlim();
        this.editingNameFocused = true;
        this.selected = entry;
        setStatus("Editing saved card settings for " + entry.safeName() + ".");
    }

    private void saveEditingEntry() {
        SkinEntry entry = this.library.get(this.editingEntryId);
        if (entry == null) {
            cancelEditingEntry();
            return;
        }
        this.library.rename(entry, this.editingName, this.editingSlim);
        this.selected = this.library.get(entry.id);
        cancelEditingEntry();
        reloadEntries();
        setStatus("Saved skin card changes.");
    }

    private void cancelEditingEntry() {
        this.editingEntryId = null;
        this.editingName = "";
        this.editingSlim = false;
        this.editingNameFocused = false;
    }

    private void reloadEntries() {
        this.library.load();
        this.entries = this.library.entries();
        if (this.selected != null) {
            this.selected = this.library.get(this.selected.id);
        }
        this.scrollOffset = clampScroll(this.scrollOffset, maxScrollOffset());
    }

    private void keepSelectedVisible() {
        if (this.entries == null || this.selected == null) {
            return;
        }
        int index = -1;
        for (int i = 0; i < this.entries.size(); i++) {
            if (this.selected.id.equals(this.entries.get(i).id)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        int rowTop = index * this.rowHeight;
        int rowBottom = rowTop + this.rowHeight;
        int viewportHeight = listViewportHeight();
        if (rowTop < this.scrollOffset) {
            this.scrollOffset = rowTop;
        }
        if (rowBottom > this.scrollOffset + viewportHeight) {
            this.scrollOffset = rowBottom - viewportHeight;
        }
        this.scrollOffset = clampScroll(this.scrollOffset, maxScrollOffset());
    }

    private int visibleRows() {
        int height = listViewportHeight();
        return Math.max(1, height / this.rowHeight);
    }

    private void setStatus(String message) {
        this.status = message == null ? "" : message;
    }

    private void playClick() {
        UiSoundHelper.playButtonClick();
    }

    private static String stripPng(String name) {
        if (name == null) {
            return "Imported Skin";
        }
        return name.toLowerCase().endsWith(".png") ? name.substring(0, name.length() - 4) : name;
    }

    private void drawPanel(DrawContext context, int left, int top, int right, int bottom, int fill, int border) {
        context.fill(left, top, right, bottom, fill);
        context.drawBorder(left, top, right - left, bottom - top, border);
    }

    private boolean isInside(double mouseX, double mouseY, int left, int top, int right, int bottom) {
        return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        if (handleFooterClick(mouseX, mouseY)) {
            return true;
        }
        if (maxPreviewScrollOffset() > 0) {
            int thumbTop = previewScrollbarThumbTop();
            int thumbBottom = thumbTop + previewScrollbarThumbHeight();
            if (isInside(mouseX, mouseY, previewScrollbarTrackLeft() - 2, thumbTop, previewScrollbarTrackLeft() + 6, thumbBottom)) {
                this.draggingPreviewScrollbar = true;
                this.previewScrollbarDragOffset = (int) mouseY - thumbTop;
                return true;
            }
        }
        if (this.entries != null && maxScrollOffset() > 0.0D) {
            int thumbTop = scrollbarThumbTop();
            int thumbBottom = thumbTop + scrollbarThumbHeight();
            if (isInside(mouseX, mouseY, scrollbarTrackLeft() - 2, thumbTop, scrollbarTrackLeft() + 6, thumbBottom)) {
                this.draggingScrollbar = true;
                this.scrollbarDragOffset = (int) mouseY - thumbTop;
                return true;
            }
        }
        if (isInside(mouseX, mouseY, this.listLeft + 6, this.createRowTop, this.listRight - 6, this.createRowBottom)) {
            handleCreateRowClick(mouseX, mouseY);
            return true;
        }
        this.draftNameFocused = false;
        this.editingNameFocused = false;
        if (isInside(mouseX, mouseY, this.listLeft, this.rowStart, this.listRight - 12, this.listBottom - 16)) {
            int index = (int) Math.floor((mouseY - this.rowStart + this.scrollOffset) / this.rowHeight);
            if (this.entries != null && index >= 0 && index < this.entries.size()) {
                SkinEntry entry = this.entries.get(index);
                int rowY = this.rowStart + (int) Math.round(index * this.rowHeight - this.scrollOffset);
                handleSavedRowClick(entry, rowY, mouseX, mouseY);
                return true;
            }
        }
        if (isInside(mouseX, mouseY, this.previewLeft, previewViewportTop(), this.previewRight, previewViewportBottom())) {
            if (handlePreviewClick(mouseX, mouseY)) {
                return true;
            }
            return true;
        }
        return isInside(mouseX, mouseY, this.listLeft, this.listTop, this.listRight, this.listBottom);
    }

    private void handleCreateRowClick(double mouseX, double mouseY) {
        int x = this.listLeft + 16;
        int fieldTop = this.createRowTop + 42;
        int fieldLeft = x;
        int fieldRight = Math.min(this.listRight - 292, fieldLeft + 220);
        int openLeft = fieldRight + 8;
        int saveLeft = openLeft + 58;
        int cancelLeft = saveLeft + 58;
        this.draftNameFocused = isInside(mouseX, mouseY, fieldLeft, fieldTop, fieldRight, fieldTop + 20);
        this.editingNameFocused = false;
        if (isInside(mouseX, mouseY, openLeft, fieldTop, openLeft + 52, fieldTop + 20)) {
            playClick();
            openSkinFileChooser();
        } else if (isInside(mouseX, mouseY, saveLeft, fieldTop, saveLeft + 52, fieldTop + 20)) {
            playClick();
            if (this.draftPath == null) {
                fetchTypedSkin();
            } else {
                saveDraftUpload();
            }
        } else if (isInside(mouseX, mouseY, cancelLeft, fieldTop, cancelLeft + 58, fieldTop + 20)) {
            playClick();
            clearDraft();
            setStatus("Draft skin cancelled.");
        } else if (isInside(mouseX, mouseY, openLeft, fieldTop + 24, openLeft + 68, fieldTop + 42)) {
            playClick();
            this.draftSlim = !this.draftSlim;
            setStatus("Draft model: " + (this.draftSlim ? "Slim" : "Regular") + ".");
        }
    }

    private void handleSavedRowClick(SkinEntry entry, int rowY, double mouseX, double mouseY) {
        if (entry != null && entry.id.equals(this.editingEntryId)) {
            handleEditableSavedRowClick(entry, rowY, mouseX, mouseY);
            return;
        }
        int textX = this.listLeft + 16;
        int buttonWidth = 52;
        int gap = 6;
        int buttonsLeft = this.listRight - 184;
        int buttonY = rowY + 19;
        this.selected = entry;
        this.editingNameFocused = false;
        if (isInside(mouseX, mouseY, buttonsLeft, buttonY, buttonsLeft + buttonWidth, buttonY + 18)) {
            playClick();
            startEditingEntry(entry);
        } else if (isInside(mouseX, mouseY, buttonsLeft + buttonWidth + gap, buttonY, buttonsLeft + buttonWidth * 2 + gap, buttonY + 18)) {
            playClick();
            applyEntry(entry);
        } else if (isInside(mouseX, mouseY, buttonsLeft + (buttonWidth + gap) * 2, buttonY, buttonsLeft + buttonWidth * 3 + gap * 2, buttonY + 18)) {
            playClick();
            confirmDeleteEntry(entry);
        } else if (isInside(mouseX, mouseY, textX, rowY + 6, buttonsLeft - 8, rowY + this.rowHeight - 6)) {
            playClick();
            setStatus("Selected " + entry.safeName() + ".");
        }
    }

    private void handleEditableSavedRowClick(SkinEntry entry, int rowY, double mouseX, double mouseY) {
        int textX = this.listLeft + 16;
        int fieldTop = rowY + 8;
        int buttonWidth = 52;
        int gap = 6;
        int buttonsLeft = this.listRight - 184;
        int fieldRight = Math.min(buttonsLeft - 10, textX + 230);
        int buttonY = rowY + 19;
        this.selected = entry;
        this.editingNameFocused = isInside(mouseX, mouseY, textX, fieldTop, fieldRight, fieldTop + 20);
        if (isInside(mouseX, mouseY, buttonsLeft, buttonY, buttonsLeft + buttonWidth, buttonY + 18)) {
            playClick();
            editEntryInEditor(entry);
        } else if (isInside(mouseX, mouseY, buttonsLeft + buttonWidth + gap, buttonY, buttonsLeft + buttonWidth * 2 + gap, buttonY + 18)) {
            playClick();
            saveEditingEntry();
        } else if (isInside(mouseX, mouseY, buttonsLeft + (buttonWidth + gap) * 2, buttonY, buttonsLeft + buttonWidth * 3 + gap * 2, buttonY + 18)) {
            playClick();
            cancelEditingEntry();
            setStatus("Cancelled skin card editing.");
        } else if (isInside(mouseX, mouseY, buttonsLeft, rowY + 40, buttonsLeft + buttonWidth, rowY + 58)) {
            playClick();
            this.editingSlim = !this.editingSlim;
            setStatus("Model changed to " + (this.editingSlim ? "Slim" : "Regular") + ".");
        }
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (this.draftNameFocused) {
            if (chr >= 32 && chr != '\u007F' && this.draftName.length() < 48) {
                this.draftName += chr;
            }
            return true;
        }
        if (this.editingNameFocused) {
            if (chr >= 32 && chr != '\u007F' && this.editingName.length() < 48) {
                this.editingName += chr;
            }
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.draftNameFocused) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !this.draftName.isEmpty()) {
                this.draftName = this.draftName.substring(0, this.draftName.length() - 1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER) {
                playClick();
                if (this.draftPath == null) {
                    fetchTypedSkin();
                } else {
                    saveDraftUpload();
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                playClick();
                this.draftNameFocused = false;
                return true;
            }
            return true;
        }
        if (this.editingNameFocused) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !this.editingName.isEmpty()) {
                this.editingName = this.editingName.substring(0, this.editingName.length() - 1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER) {
                playClick();
                saveEditingEntry();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                playClick();
                cancelEditingEntry();
                return true;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (this.draggingPreviewScrollbar) {
            updatePreviewScrollFromThumb(mouseY);
            return true;
        }
        if (this.draggingScrollbar) {
            updateScrollFromThumb(mouseY);
            return true;
        }
        if (button == 0 && isInside(mouseX, mouseY, this.previewLeft, this.previewTop, this.previewRight, this.previewBottom)) {
            if (hasShiftDown()) {
                this.modelRoll = SkinModelRenderer.wrap(this.modelRoll + (float) deltaX * 0.75F);
            } else {
                this.modelYaw = SkinModelRenderer.wrap(this.modelYaw + (float) deltaX * 0.75F);
                this.modelPitch = SkinModelRenderer.clampPitch(this.modelPitch + (float) deltaY * 0.75F);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.draggingPreviewScrollbar) {
            this.draggingPreviewScrollbar = false;
            return true;
        }
        if (this.draggingScrollbar) {
            this.draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (isInside(mouseX, mouseY, this.previewLeft, previewViewportTop(), this.previewRight, previewViewportBottom())) {
            int max = maxPreviewScrollOffset();
            this.previewScrollOffset = Math.max(0, Math.min(max, this.previewScrollOffset - (int) Math.signum(amount) * 18));
            return true;
        }
        if (isInside(mouseX, mouseY, this.listLeft, this.rowStart, this.listRight, this.listBottom - 16) && this.entries != null) {
            double max = maxScrollOffset();
            this.scrollOffset = clampScroll(this.scrollOffset - amount * 28.0D, max);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }
}
