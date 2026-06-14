package com.spirit.mixin.client.gui;

import com.spirit.koil.api.chat.RichChatDetectorRegistry;
import com.spirit.koil.api.chat.message.RichChatEnvelope;
import com.spirit.koil.api.automation.cli.AutomationChatHudRenderer;
import com.spirit.koil.chat.client.RichChatClientBridge;
import com.spirit.koil.chat.common.config.RichChatConfig;
import com.spirit.koil.chat.common.config.RichChatConfigStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public abstract class MixinChatScreen {
    @Shadow protected TextFieldWidget chatField;

    @Shadow public abstract boolean sendMessage(String chatText, boolean addToHistory);

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void koil$automationHudMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (AutomationChatHudRenderer.mouseClicked(MinecraftClient.getInstance(), mouseX, mouseY, button)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void koil$configureRichChatField(CallbackInfo ci) {
        RichChatConfig config = RichChatConfigStore.load();
        if (chatField != null && config.richChat.multilineEnabled) {
            chatField.setMaxLength(Math.max(256, config.richChat.maxCharsPerMessage));
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void koil$richChatMultilineKeys(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        RichChatConfig config = RichChatConfigStore.load();
        if (chatField == null || !config.richChat.multilineEnabled) {
            return;
        }
        boolean enter = keyCode == 257 || keyCode == 335;
        if (!enter) {
            return;
        }
        if (config.richChat.shiftEnterAddsNewLine && Screen.hasShiftDown()) {
            if (koil$lineCount(chatField.getText()) < Math.max(1, config.richChat.maxLinesPerMessage)) {
                chatField.write("\n");
            }
            cir.setReturnValue(true);
            return;
        }
        if (config.richChat.ctrlEnterSendsMessage && Screen.hasControlDown()) {
            if (sendMessage(chatField.getText(), true)) {
                MinecraftClient.getInstance().setScreen(null);
            }
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "sendMessage", at = @At("RETURN"))
    private void koil$sendDetectedRichVanillaPayload(String chatText, boolean addToHistory, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) {
            return;
        }
        RichChatConfig config = RichChatConfigStore.load();
        if (!config.richChat.enabled || !config.richChat.automaticDetectionEnabled) {
            return;
        }
        String source = chatText == null ? "" : chatText.trim();
        if (source.isBlank() || source.length() > Math.max(1, config.richChat.maxCharsPerMessage) || koil$lineCount(source) > Math.max(1, config.richChat.maxLinesPerMessage)) {
            return;
        }
        RichChatDetectorRegistry.detect(source).ifPresent(envelope -> {
            envelope.fallbackText = source;
            envelope.rawText = source;
            RichChatClientBridge.sendDetectedVanillaPayload(MinecraftClient.getInstance(), envelope);
        });
    }

    @Inject(method = "normalize", at = @At("HEAD"), cancellable = true)
    private void koil$preserveMultilineDraft(String chatText, CallbackInfoReturnable<String> cir) {
        RichChatConfig config = RichChatConfigStore.load();
        if (!config.richChat.multilineEnabled || chatText == null || !(chatText.contains("\n") || chatText.contains("\r"))) {
            return;
        }
        String value = chatText.replace("\r\n", "\n").replace('\r', '\n').strip();
        int maxLines = Math.max(1, config.richChat.maxLinesPerMessage);
        String[] lines = value.split("\n", -1);
        if (lines.length > maxLines) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < maxLines; i++) {
                if (i > 0) {
                    builder.append('\n');
                }
                builder.append(lines[i]);
            }
            value = builder.toString();
        }
        int maxChars = Math.max(1, config.richChat.maxCharsPerMessage);
        if (value.length() > maxChars) {
            value = value.substring(0, maxChars);
        }
        cir.setReturnValue(value);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void koil$renderRichChatDraftPreview(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null || chatField == null) {
            return;
        }
        RichChatConfig config = RichChatConfigStore.load();
        if (!config.richChat.enabled || !config.richChat.automaticDetectionEnabled) {
            return;
        }
        String text = chatField.getText();
        int lineCount = koil$lineCount(text);
        String message = null;
        Formatting color = Formatting.GRAY;
        if (lineCount > Math.max(1, config.richChat.maxLinesPerMessage)) {
            message = "Rich Chat: too many lines";
            color = Formatting.RED;
        } else if (text.length() > Math.max(1, config.richChat.maxCharsPerMessage)) {
            message = "Rich Chat: message is too large";
            color = Formatting.RED;
        } else if (RichChatDetectorRegistry.detect(text).isPresent()) {
            message = lineCount > 1 ? "Rich Chat: multiline LaTeX preview ready" : "Rich Chat: LaTeX preview ready";
            color = Formatting.AQUA;
        } else if (lineCount > 1) {
            message = "Rich Chat: multiline message";
            color = Formatting.YELLOW;
        }
        if (message != null) {
            context.drawTextWithShadow(client.textRenderer, Text.literal(message).formatted(color), 4, Math.max(4, this.chatField.getY() - 12), 0xFFFFFF);
        }
    }

    private int koil$lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }
}
