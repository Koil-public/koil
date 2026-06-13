package com.spirit.mixin.client.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(TextFieldWidget.class)
public abstract class MixinTextFieldWidget {
    private static final long KOIL_MULTI_CLICK_MS = 350L;
    private static final double KOIL_MULTI_CLICK_RANGE = 6.0D;

    @Shadow @Final private TextRenderer textRenderer;
    @Shadow private boolean drawsBackground;
    @Shadow private int firstCharacterIndex;
    @Shadow private int selectionStart;
    @Shadow private int selectionEnd;

    @Shadow public abstract String getText();
    @Shadow public abstract int getInnerWidth();
    @Shadow public abstract int getCursor();
    @Shadow public abstract void setSelectionStart(int cursor);
    @Shadow public abstract void setSelectionEnd(int index);
    @Shadow public abstract void playDownSound(net.minecraft.client.sound.SoundManager soundManager);

    private long koil$lastClickTime;
    private double koil$lastClickX;
    private double koil$lastClickY;
    private int koil$clickCount;
    private boolean koil$dragSelecting;
    private int koil$dragAnchor = -1;
    private int koil$selectionAnchor = -1;

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        TextFieldWidget self = (TextFieldWidget)(Object)this;
        if (!self.active || !self.isVisible()) {
            koil$dragSelecting = false;
            return false;
        }
        if (button != 0 || !self.isMouseOver(mouseX, mouseY)) {
            koil$dragSelecting = false;
            return false;
        }

        playDownSound(MinecraftClient.getInstance().getSoundManager());
        self.setFocused(true);

        long now = Util.getMeasuringTimeMs();
        boolean repeatedClick = now - koil$lastClickTime <= KOIL_MULTI_CLICK_MS
                && Math.abs(mouseX - koil$lastClickX) <= KOIL_MULTI_CLICK_RANGE
                && Math.abs(mouseY - koil$lastClickY) <= KOIL_MULTI_CLICK_RANGE;

        koil$clickCount = repeatedClick ? Math.min(3, koil$clickCount + 1) : 1;
        koil$lastClickTime = now;
        koil$lastClickX = mouseX;
        koil$lastClickY = mouseY;

        int clickedIndex = koil$getCursorIndex(mouseX);
        if (Screen.hasShiftDown()) {
            int anchor = koil$selectionAnchor >= 0 && koil$selectionAnchor <= getText().length()
                    ? koil$selectionAnchor
                    : (selectionStart != selectionEnd ? selectionEnd : getCursor());
            koil$selectRange(clickedIndex, anchor);
            koil$dragAnchor = anchor;
            koil$selectionAnchor = anchor;
            koil$dragSelecting = false;
            return true;
        }

        if (koil$clickCount == 2) {
            koil$selectWord(clickedIndex);
            koil$selectionAnchor = Math.min(selectionStart, selectionEnd);
            koil$dragSelecting = false;
        } else if (koil$clickCount >= 3) {
            koil$selectAll();
            koil$selectionAnchor = 0;
            koil$dragSelecting = false;
        } else {
            koil$selectionAnchor = clickedIndex;
            koil$dragAnchor = clickedIndex;
            koil$dragSelecting = true;
        }
        return true;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && koil$dragSelecting) {
            koil$applyDragSelection(mouseX);
            return true;
        }
        return false;
    }

    protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (koil$dragSelecting) {
            koil$applyDragSelection(mouseX);
        }
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && koil$dragSelecting) {
            koil$dragSelecting = false;
            return true;
        }
        return false;
    }

    private void koil$applyDragSelection(double mouseX) {
        TextFieldWidget self = (TextFieldWidget)(Object)this;
        if (!self.isVisible()) {
            return;
        }

        koil$selectRange(koil$getCursorIndex(mouseX), koil$dragAnchor);
        koil$selectionAnchor = koil$dragAnchor;
    }

    private int koil$getCursorIndex(double mouseX) {
        TextFieldWidget self = (TextFieldWidget)(Object)this;
        String text = getText();
        if (text.isEmpty()) {
            return 0;
        }

        int relativeX = MathHelper.floor(mouseX) - self.getX();
        if (drawsBackground) {
            relativeX -= 4;
        }
        relativeX = MathHelper.clamp(relativeX, 0, getInnerWidth());

        int visibleStart = MathHelper.clamp(firstCharacterIndex, 0, text.length());
        String visibleText = textRenderer.trimToWidth(text.substring(visibleStart), getInnerWidth());
        int localIndex = textRenderer.trimToWidth(visibleText, relativeX).length();
        return MathHelper.clamp(visibleStart + localIndex, 0, text.length());
    }

    private void koil$selectWord(int index) {
        String text = getText();
        if (text.isEmpty()) {
            return;
        }

        int probe = MathHelper.clamp(index, 0, text.length() - 1);
        if (index == text.length() && probe > 0) {
            probe--;
        }

        int start = probe;
        int end = probe + 1;
        int mode = koil$charMode(text.charAt(probe));

        while (start > 0 && koil$charMode(text.charAt(start - 1)) == mode) {
            start--;
        }
        while (end < text.length() && koil$charMode(text.charAt(end)) == mode) {
            end++;
        }

        koil$selectRange(end, start);
    }

    private void koil$selectAll() {
        koil$selectRange(getText().length(), 0);
    }

    private void koil$selectRange(int cursor, int anchor) {
        setSelectionStart(cursor);
        setSelectionEnd(anchor);
    }

    private int koil$charMode(char c) {
        if (Character.isWhitespace(c)) {
            return 0;
        }
        if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '/' || c == '\\' || c == ':') {
            return 1;
        }
        return 2;
    }
}
