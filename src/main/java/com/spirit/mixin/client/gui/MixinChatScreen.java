package com.spirit.mixin.client.gui;

import com.spirit.koil.api.automation.cli.AutomationChatHudRenderer;
import com.spirit.koil.api.chat.RichChatMessageData;
import com.spirit.koil.api.chat.RichMessageBuilder;
import com.spirit.koil.chat.internal.LocalMultilineChatBridge;
import com.spirit.koil.chat.internal.MultilineChatInputLayout;
import com.spirit.koil.chat.internal.RichChatMessageStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.StringHelper;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(ChatScreen.class)
public abstract class MixinChatScreen extends Screen {
    @Shadow protected TextFieldWidget chatField;
    @Shadow public abstract String normalize(String chatText);

    @Unique private int koil$draftScrollLine;

    protected MixinChatScreen(Text title) {
        super(title);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void koil$updateMultilineLayoutOnTick(CallbackInfo ci) {
        koil$updateMultilineLayoutReservation();
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void koil$clearMultilineLayoutOnRemoved(CallbackInfo ci) {
        MultilineChatInputLayout.clear();
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void koil$handleMultilineKeys(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (chatField == null) {
            return;
        }

        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && Screen.hasShiftDown()) {
            koil$insertDraftText("\n");
            cir.setReturnValue(true);
            return;
        }

        if (Screen.isPaste(keyCode)) {
            String clipboard = MinecraftClient.getInstance().keyboard.getClipboard();
            if (clipboard.indexOf('\n') >= 0 || clipboard.indexOf('\r') >= 0) {
                koil$insertDraftText(clipboard.replace("\r\n", "\n").replace('\r', '\n'));
                cir.setReturnValue(true);
                return;
            }
        }

        if (koil$isMultilineDraft()) {
            if (keyCode == GLFW.GLFW_KEY_UP && koil$moveCursorVertically(-1)) {
                cir.setReturnValue(true);
            } else if (keyCode == GLFW.GLFW_KEY_DOWN && koil$moveCursorVertically(1)) {
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "normalize", at = @At("HEAD"), cancellable = true)
    private void koil$preserveMultilineNormalize(String chatText, CallbackInfoReturnable<String> cir) {
        if (chatText.indexOf('\n') < 0 && chatText.indexOf('\r') < 0) {
            return;
        }

        String normalized = koil$networkSafeMultiline(chatText);
        cir.setReturnValue(StringHelper.truncateChat(normalized));
    }

    @Inject(method = "sendMessage", at = @At("HEAD"), cancellable = true)
    private void koil$sendNetworkSafeMultiline(String chatText, boolean addToHistory, CallbackInfoReturnable<Boolean> cir) {
        if (chatText.indexOf('\n') < 0 && chatText.indexOf('\r') < 0) {
            return;
        }

        String historyText = chatText.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (historyText.isEmpty()) {
            cir.setReturnValue(true);
            return;
        }

        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (addToHistory && minecraft.inGameHud != null) {
            minecraft.inGameHud.getChatHud().addToMessageHistory(StringHelper.truncateChat(historyText));
        }

        String networkText = StringHelper.truncateChat(koil$networkSafeMultiline(chatText));
        if (!networkText.isEmpty() && minecraft.player != null && minecraft.player.networkHandler != null) {
            RichChatMessageData richMessage = RichMessageBuilder.multilineText(historyText)
                    .sender(minecraft.player.getUuid(), minecraft.player.getGameProfile().getName())
                    .fallbackText(networkText)
                    .metadata("source", "vanilla_chat")
                    .metadata("phase", "multiline")
                    .build();
            RichChatMessageStore.remember(richMessage);
            LocalMultilineChatBridge.remember(networkText, StringHelper.truncateChat(historyText));
            if (networkText.startsWith("/")) {
                minecraft.player.networkHandler.sendChatCommand(networkText.substring(1));
            } else {
                minecraft.player.networkHandler.sendChatMessage(networkText);
            }
        }
        cir.setReturnValue(true);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/widget/TextFieldWidget;render(Lnet/minecraft/client/gui/DrawContext;IIF)V"))
    private void koil$renderVanillaOrMultilineField(TextFieldWidget field, DrawContext context, int mouseX, int mouseY, float delta) {
        if (!koil$isMultilineDraft()) {
            field.render(context, mouseX, mouseY, delta);
        }
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"))
    private void koil$renderVanillaInputBackground(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        if (!koil$isMultilineDraft()) {
            context.fill(x1, y1, x2, y2, color);
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void koil$renderMultilineDraft(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!koil$isMultilineDraft()) {
            MultilineChatInputLayout.clear();
            return;
        }
        koil$updateMultilineLayoutReservation();

        TextRenderer renderer = this.textRenderer;
        List<String> lines = koil$splitLines(chatField.getText());
        int cursorLine = koil$cursorLine(lines, chatField.getCursor());
        int lineHeight = renderer.fontHeight + 2;
        int maxVisibleLines = Math.max(2, Math.min(6, (this.height - 34) / lineHeight));
        int visibleLines = Math.min(maxVisibleLines, Math.max(2, lines.size()));
        int firstLine = koil$firstVisibleLine(lines, visibleLines, cursorLine);

        int left = 2;
        int right = this.width - 2;
        int bottom = this.height - 2;
        int top = bottom - visibleLines * lineHeight - 8;
        int background = MinecraftClient.getInstance().options.getTextBackgroundColor(Integer.MIN_VALUE);
        context.fill(left, top, right, bottom, background);

        int textX = left + 4;
        int textY = top + 5;
        int color = 0xE0E0E0;
        for (int i = 0; i < visibleLines; i++) {
            int lineIndex = firstLine + i;
            if (lineIndex >= lines.size()) {
                break;
            }
            String line = renderer.trimToWidth(lines.get(lineIndex), Math.max(20, right - left - 12));
            context.drawTextWithShadow(renderer, line, textX, textY + i * lineHeight, color);
        }

        if ((System.currentTimeMillis() / 300L) % 2L == 0L && cursorLine >= firstLine && cursorLine < firstLine + visibleLines) {
            int cursorX = textX + renderer.getWidth(koil$lineBeforeCursor(lines, chatField.getCursor()));
            int cursorY = textY + (cursorLine - firstLine) * lineHeight - 1;
            context.fill(cursorX, cursorY, cursorX + 1, cursorY + renderer.fontHeight + 1, 0xFFFFFFFF);
        }

        if (lines.size() > visibleLines) {
            int trackX = right - 5;
            int trackTop = top + 5;
            int trackBottom = bottom - 5;
            int nubHeight = Math.max(10, (trackBottom - trackTop) * visibleLines / lines.size());
            int maxFirst = Math.max(1, lines.size() - visibleLines);
            int nubY = trackTop + (trackBottom - trackTop - nubHeight) * firstLine / maxFirst;
            context.fill(trackX, trackTop, trackX + 1, trackBottom, 0x66303030);
            context.fill(trackX - 1, nubY, trackX + 2, nubY + nubHeight, 0xCCBFC7D5);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void koil$automationHudMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (AutomationChatHudRenderer.mouseClicked(MinecraftClient.getInstance(), mouseX, mouseY, button)) {
            cir.setReturnValue(true);
            return;
        }

        if (button == 0 && koil$isMultilineDraft() && koil$mouseInsideMultilineDraft(mouseX, mouseY)) {
            chatField.setFocused(true);
            chatField.setCursor(koil$cursorFromMouse(mouseX, mouseY));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void koil$scrollMultilineDraft(double mouseX, double mouseY, double amount, CallbackInfoReturnable<Boolean> cir) {
        if (!koil$isMultilineDraft() || !koil$mouseInsideMultilineDraft(mouseX, mouseY)) {
            return;
        }

        List<String> lines = koil$splitLines(chatField.getText());
        int visibleLines = koil$visibleLineCount(lines);
        int maxFirst = Math.max(0, lines.size() - visibleLines);
        int direction = amount > 0.0D ? -1 : 1;
        koil$draftScrollLine = Math.max(0, Math.min(maxFirst, koil$draftScrollLine + direction));
        cir.setReturnValue(true);
    }

    @Unique
    private boolean koil$isMultilineDraft() {
        boolean multiline = chatField != null && chatField.getText().indexOf('\n') >= 0;
        if (!multiline) {
            koil$draftScrollLine = 0;
        }
        return multiline;
    }

    @Unique
    private void koil$insertDraftText(String inserted) {
        String text = chatField.getText();
        int cursor = Math.max(0, Math.min(chatField.getCursor(), text.length()));
        String next = text.substring(0, cursor) + inserted + text.substring(cursor);
        chatField.setText(next);
        chatField.setCursor(Math.min(chatField.getText().length(), cursor + inserted.length()));
        koil$updateMultilineLayoutReservation();
    }

    @Unique
    private String koil$networkSafeMultiline(String chatText) {
        return chatText.replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
                .replaceAll("[\\t ]*\\n[\\t ]*", " | ");
    }

    @Unique
    private List<String> koil$splitLines(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= normalized.length(); i++) {
            if (i == normalized.length() || normalized.charAt(i) == '\n') {
                lines.add(normalized.substring(start, i));
                start = i + 1;
            }
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    @Unique
    private int koil$cursorLine(List<String> lines, int cursor) {
        int index = 0;
        for (int i = 0; i < lines.size(); i++) {
            int lineLength = lines.get(i).length();
            if (cursor <= index + lineLength) {
                return i;
            }
            index += lineLength + 1;
        }
        return Math.max(0, lines.size() - 1);
    }

    @Unique
    private String koil$lineBeforeCursor(List<String> lines, int cursor) {
        int index = 0;
        for (String line : lines) {
            int end = index + line.length();
            if (cursor <= end) {
                return line.substring(0, Math.max(0, Math.min(line.length(), cursor - index)));
            }
            index = end + 1;
        }
        return "";
    }

    @Unique
    private boolean koil$moveCursorVertically(int direction) {
        String text = chatField.getText().replace("\r\n", "\n").replace('\r', '\n');
        int cursor = Math.max(0, Math.min(chatField.getCursor(), text.length()));
        int currentStart = text.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
        int currentEnd = text.indexOf('\n', cursor);
        if (currentEnd < 0) {
            currentEnd = text.length();
        }
        int column = cursor - currentStart;

        if (direction < 0) {
            if (currentStart <= 0) {
                return false;
            }
            int previousEnd = currentStart - 1;
            int previousStart = text.lastIndexOf('\n', Math.max(0, previousEnd - 1)) + 1;
            chatField.setCursor(previousStart + Math.min(column, previousEnd - previousStart));
            return true;
        }

        if (currentEnd >= text.length()) {
            return false;
        }
        int nextStart = currentEnd + 1;
        int nextEnd = text.indexOf('\n', nextStart);
        if (nextEnd < 0) {
            nextEnd = text.length();
        }
        chatField.setCursor(nextStart + Math.min(column, nextEnd - nextStart));
        return true;
    }

    @Unique
    private boolean koil$mouseInsideMultilineDraft(double mouseX, double mouseY) {
        int top = koil$draftTop();
        return mouseX >= 2 && mouseX <= this.width - 2 && mouseY >= top && mouseY <= this.height - 2;
    }

    @Unique
    private int koil$cursorFromMouse(double mouseX, double mouseY) {
        List<String> lines = koil$splitLines(chatField.getText());
        int lineHeight = this.textRenderer.fontHeight + 2;
        int visibleLines = koil$visibleLineCount(lines);
        int cursorLine = koil$cursorLine(lines, chatField.getCursor());
        int firstLine = koil$firstVisibleLine(lines, visibleLines, cursorLine);
        int top = koil$draftTop();
        int line = firstLine + Math.max(0, Math.min(visibleLines - 1, (int)((mouseY - top - 5) / lineHeight)));
        line = Math.max(0, Math.min(lines.size() - 1, line));
        int index = 0;
        for (int i = 0; i < line; i++) {
            index += lines.get(i).length() + 1;
        }

        String textLine = lines.get(line);
        int relativeX = Math.max(0, (int)mouseX - 6);
        int column = this.textRenderer.trimToWidth(textLine, relativeX).length();
        return Math.min(chatField.getText().length(), index + Math.min(column, textLine.length()));
    }

    @Unique
    private int koil$draftTop() {
        int lineHeight = this.textRenderer.fontHeight + 2;
        int visibleLines = koil$visibleLineCount(koil$splitLines(chatField.getText()));
        return this.height - 2 - visibleLines * lineHeight - 8;
    }

    @Unique
    private void koil$updateMultilineLayoutReservation() {
        if (!koil$isMultilineDraft()) {
            MultilineChatInputLayout.clear();
            return;
        }

        int lineHeight = this.textRenderer.fontHeight + 2;
        int visibleLines = koil$visibleLineCount(koil$splitLines(chatField.getText()));
        int draftHeight = visibleLines * lineHeight + 8;
        MultilineChatInputLayout.setReservedHeight(Math.max(0, draftHeight - 12));
    }

    @Unique
    private int koil$visibleLineCount(List<String> lines) {
        int lineHeight = this.textRenderer.fontHeight + 2;
        int maxVisibleLines = Math.max(2, Math.min(6, (this.height - 34) / lineHeight));
        return Math.min(maxVisibleLines, Math.max(2, lines.size()));
    }

    @Unique
    private int koil$firstVisibleLine(List<String> lines, int visibleLines, int cursorLine) {
        int maxFirst = Math.max(0, lines.size() - visibleLines);
        koil$draftScrollLine = Math.max(0, Math.min(maxFirst, koil$draftScrollLine));
        if (cursorLine < koil$draftScrollLine) {
            koil$draftScrollLine = cursorLine;
        } else if (cursorLine >= koil$draftScrollLine + visibleLines) {
            koil$draftScrollLine = cursorLine - visibleLines + 1;
        }
        return Math.max(0, Math.min(maxFirst, koil$draftScrollLine));
    }
}
