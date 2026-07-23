package com.spirit.mixin.client.gui;

import com.mojang.brigadier.suggestion.Suggestion;
import com.spirit.client.gui.SuggestionPopupRenderer;
import com.spirit.koil.api.chat.ChatSuggestionAnchor;
import com.spirit.mixin.client.gui.accessor.ChatScreenAccessor;
import com.spirit.mixin.client.gui.accessor.ChatInputSuggestorSuggestionWindowAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ChatInputSuggestor;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.Rect2i;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(ChatInputSuggestor.SuggestionWindow.class)
public abstract class MixinChatInputSuggestorSuggestionWindow {
    @Unique
    private static final int koil$MAX_VISIBLE_ROWS = SuggestionPopupRenderer.MAX_VISIBLE_ROWS;

    @Unique
    private Rect2i koil$sharedArea;

    @Unique
    private int koil$scrollOffset;

    @Unique
    private int koil$visibleRows;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void koil$renderWithSharedPopupSurface(DrawContext context, int mouseX, int mouseY, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null) {
            return;
        }
        if (client.currentScreen instanceof ChatScreen) {
            this.koil$sharedArea = null;
            ci.cancel();
            return;
        }
        if (!(client.currentScreen instanceof ChatSuggestionAnchor anchor) || !anchor.koil$useCustomChatSuggestionAnchor()) {
            this.koil$sharedArea = null;
            return;
        }
        ChatInputSuggestorSuggestionWindowAccessor accessor = (ChatInputSuggestorSuggestionWindowAccessor)this;
        Rect2i area = accessor.koil$getArea();
        List<Suggestion> suggestions = accessor.koil$getSuggestions();
        if (area == null || suggestions == null || suggestions.isEmpty()) {
            this.koil$sharedArea = null;
            return;
        }
        List<SuggestionPopupRenderer.Entry> entries = new ArrayList<>(suggestions.size());
        for (Suggestion suggestion : suggestions) {
            String value = suggestion == null ? "" : suggestion.getText();
            String detail = suggestion == null || suggestion.getTooltip() == null ? "" : suggestion.getTooltip().getString();
            entries.add(new SuggestionPopupRenderer.Entry(koil$kind(value), koil$kindColor(value), value, detail));
        }
        int selectedIndex = Math.max(0, Math.min(accessor.koil$getSelection(), suggestions.size() - 1));
        this.koil$visibleRows = Math.min(koil$MAX_VISIBLE_ROWS, suggestions.size());
        this.koil$scrollOffset = koil$clampScrollOffset(this.koil$scrollOffset, suggestions.size(), this.koil$visibleRows);
        if (selectedIndex < this.koil$scrollOffset) {
            this.koil$scrollOffset = selectedIndex;
        } else if (selectedIndex >= this.koil$scrollOffset + this.koil$visibleRows) {
            this.koil$scrollOffset = selectedIndex - this.koil$visibleRows + 1;
        }
        int visibleEnd = Math.min(entries.size(), this.koil$scrollOffset + this.koil$visibleRows);
        List<SuggestionPopupRenderer.Entry> visibleEntries = entries.subList(this.koil$scrollOffset, visibleEnd);
        int width = Math.max(area.getWidth(), SuggestionPopupRenderer.preferredWidth(client.textRenderer, entries));
        int height = SuggestionPopupRenderer.preferredHeight(this.koil$visibleRows);
        int screenWidth = client.getWindow() == null ? width : client.getWindow().getScaledWidth();
        int anchorX = area.getX();
        int anchorY = area.getY();
        if (client.currentScreen instanceof ChatScreen chatScreen) {
            TextFieldWidget field = ((ChatScreenAccessor) chatScreen).koil$getChatField();
            if (field != null) {
                anchorX = anchor.koil$chatSuggestionAnchorX(area, width, !entries.isEmpty() && entries.get(0).value() != null && entries.get(0).value().startsWith("/"));
                anchorY = anchor.koil$chatSuggestionAnchorY(height);
            }
        }
        int x = Math.max(2, Math.min(anchorX, screenWidth - width - 2));
        int y = Math.max(2, anchorY);
        this.koil$sharedArea = new Rect2i(x, y, width, height);
        SuggestionPopupRenderer.render(context, client.textRenderer, x, y, width, visibleEntries, selectedIndex - this.koil$scrollOffset, mouseX, mouseY);
        ci.cancel();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void koil$mouseClickedSharedSurface(int mouseX, int mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button != 0 || this.koil$sharedArea == null) {
            return;
        }
        Rect2i area = this.koil$sharedArea;
        if (mouseX < area.getX() || mouseX > area.getX() + area.getWidth() || mouseY < area.getY() || mouseY > area.getY() + area.getHeight()) {
            return;
        }
        ChatInputSuggestorSuggestionWindowAccessor accessor = (ChatInputSuggestorSuggestionWindowAccessor) this;
        List<Suggestion> suggestions = accessor.koil$getSuggestions();
        if (suggestions == null || suggestions.isEmpty()) {
            cir.setReturnValue(true);
            return;
        }
        int row = SuggestionPopupRenderer.rowAt(area.getY(), mouseY);
        int absoluteRow = this.koil$scrollOffset + row;
        if (row >= 0 && row < this.koil$visibleRows && absoluteRow < suggestions.size()) {
            accessor.koil$select(absoluteRow);
            accessor.koil$complete();
        }
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void koil$mouseScrolledSharedSurface(double amount, CallbackInfoReturnable<Boolean> cir) {
        if (this.koil$sharedArea == null || this.koil$visibleRows <= 0) {
            return;
        }
        ChatInputSuggestorSuggestionWindowAccessor accessor = (ChatInputSuggestorSuggestionWindowAccessor)this;
        List<Suggestion> suggestions = accessor.koil$getSuggestions();
        if (suggestions == null || suggestions.size() <= this.koil$visibleRows) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.mouse == null || client.getWindow() == null) {
            return;
        }
        double scaledMouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / (double)client.getWindow().getWidth();
        double scaledMouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / (double)client.getWindow().getHeight();
        Rect2i area = this.koil$sharedArea;
        if (scaledMouseX < area.getX() || scaledMouseX > area.getX() + area.getWidth() || scaledMouseY < area.getY() || scaledMouseY > area.getY() + area.getHeight()) {
            return;
        }
        int direction = amount > 0.0D ? -1 : amount < 0.0D ? 1 : 0;
        if (direction == 0) {
            return;
        }
        int selectedIndex = Math.max(0, Math.min(accessor.koil$getSelection(), suggestions.size() - 1));
        int nextSelection = Math.max(0, Math.min(suggestions.size() - 1, selectedIndex + direction));
        if (nextSelection == selectedIndex) {
            cir.setReturnValue(true);
            return;
        }
        accessor.koil$select(nextSelection);
        this.koil$scrollOffset = koil$clampScrollOffset(this.koil$scrollOffset + direction, suggestions.size(), this.koil$visibleRows);
        cir.setReturnValue(true);
    }

    @Unique
    private String koil$kind(String value) {
        if (value == null || value.isBlank()) {
            return "ARG";
        }
        if (value.startsWith("/")) {
            return "CMD";
        }
        if (value.startsWith("@") || value.contains(":")) {
            return "MC";
        }
        return "ARG";
    }

    @Unique
    private int koil$kindColor(String value) {
        if (value == null || value.isBlank()) {
            return 0xA0D8DEE8;
        }
        if (value.startsWith("/")) {
            return 0xFFD7C16D;
        }
        if (value.startsWith("@")) {
            return 0xFF8FD0E3;
        }
        if (value.contains(":")) {
            return 0xFFA9D98A;
        }
        return 0xA0D8DEE8;
    }

    @Unique
    private int koil$clampScrollOffset(int offset, int totalRows, int visibleRows) {
        int maxOffset = Math.max(0, totalRows - Math.max(0, visibleRows));
        return Math.max(0, Math.min(offset, maxOffset));
    }
}
