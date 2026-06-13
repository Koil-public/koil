package com.spirit.koil.api.automation;

import com.spirit.koil.api.automation.cli.AutomationChatHudState;
import com.spirit.koil.api.automation.cli.AutomationCliViewModel;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public final class AutomationChatTrigger {
    private static long lastTriggerNanos;
    private static String lastMessage = "";

    private AutomationChatTrigger() {
    }

    public static void maybeOpenPrompt(Text message) {
        if (!AutomationModeController.isAutomationMode() && !AutomationSettings.allowChatUsernameTrigger()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || message == null) {
            return;
        }
        String clean = strip(message.getString());
        String username = strip(client.player.getName().getString());
        String payload = chatPayload(clean);
        String actor = chatActor(clean);
        UsernameCommand command = commandForUsername(payload, username);
        if (command == null) {
            return;
        }
        long now = System.nanoTime();
        String triggerKey = payload + "|" + command.prompt();
        if (triggerKey.equals(lastMessage) && now - lastTriggerNanos < 650_000_000L) {
            return;
        }
        lastTriggerNanos = now;
        lastMessage = triggerKey;
        AutomationModeController.setAutomationMode(true);
        if (!command.prompt().isBlank()) {
            AutomationReporter.pipeline("[mode]", "automation chat command from " + (actor.isBlank() ? "chat" : actor));
            AutomationRouter.handleInput(new AutomationRequest(command.prompt(), false, false), actor);
            return;
        }
        AutomationCliViewModel.beginSession("/automate chat", actor);
        MutableText header = AutomationCliViewModel.automationChatHeader();
        MutableText prompt = AutomationCliViewModel.promptLine("type an automation prompt");
        AutomationChatHudState.showHeader(header, prompt);
        AutomationReporter.pipeline("[mode]", "automation chat prompt opened by username trigger");
    }

    private static String strip(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String chatPayload(String message) {
        String clean = strip(message);
        if (clean.startsWith("<")) {
            int close = clean.indexOf('>');
            if (close >= 0 && close + 1 < clean.length()) {
                return strip(clean.substring(close + 1));
            }
        }
        int colon = clean.indexOf(": ");
        if (colon > 0 && colon + 2 < clean.length()) {
            return strip(clean.substring(colon + 2));
        }
        return clean;
    }

    private static String chatActor(String message) {
        String clean = strip(message);
        if (clean.startsWith("<")) {
            int close = clean.indexOf('>');
            if (close > 1) {
                return strip(clean.substring(1, close));
            }
        }
        int colon = clean.indexOf(": ");
        if (colon > 0) {
            return strip(clean.substring(0, colon));
        }
        return "";
    }

    private static UsernameCommand commandForUsername(String payload, String username) {
        String cleanPayload = strip(payload);
        String cleanUsername = strip(username);
        if (cleanPayload.isBlank() || cleanUsername.isBlank()) {
            return null;
        }
        if (cleanPayload.equalsIgnoreCase(cleanUsername)) {
            return new UsernameCommand("");
        }
        if (cleanPayload.length() <= cleanUsername.length() || !cleanPayload.regionMatches(true, 0, cleanUsername, 0, cleanUsername.length())) {
            return null;
        }
        char boundary = cleanPayload.charAt(cleanUsername.length());
        if (!Character.isWhitespace(boundary) && boundary != ':' && boundary != ',' && boundary != '>') {
            return null;
        }
        String prompt = strip(cleanPayload.substring(cleanUsername.length()));
        while (!prompt.isBlank() && (prompt.charAt(0) == ':' || prompt.charAt(0) == ',' || prompt.charAt(0) == '>')) {
            prompt = strip(prompt.substring(1));
        }
        return new UsernameCommand(prompt);
    }

    private record UsernameCommand(String prompt) {
    }
}
