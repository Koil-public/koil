package com.spirit.koil.api.chat;

import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.regex.Pattern;

public final class RichChatRowClassifier {
    private static final Pattern PLAYER_CHAT_PREFIX = Pattern.compile("^<[^>]+>\\s+.*$");
    private static final Pattern JOIN_LEAVE_MESSAGE = Pattern.compile("^.+\\s+(joined|left)\\s+the\\s+game\\.?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADVANCEMENT_TASK = Pattern.compile("^.+\\s+has\\s+made\\s+the\\s+advancement\\s+\\[.+]$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADVANCEMENT_GOAL = Pattern.compile("^.+\\s+has\\s+reached\\s+the\\s+goal\\s+\\[.+]$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADVANCEMENT_CHALLENGE = Pattern.compile("^.+\\s+has\\s+completed\\s+the\\s+challenge\\s+\\[.+]$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMANDISH_MESSAGE = Pattern.compile("^(unknown command|incorrect argument|usage:|set own game mode to|teleported|gave |cleared |summoned |killed |located |time set|weather set|difficulty set|saved the game|reloaded|data of|no entity was found|found no elements|seed:).*$", Pattern.CASE_INSENSITIVE);

    private RichChatRowClassifier() {
    }

    public static RichChatRowType classify(Text message, MessageIndicator originalIndicator) {
        if (message == null) {
            return RichChatRowType.UNKNOWN;
        }
        String rawVisible = message.getString();
        if (rawVisible == null || rawVisible.isBlank()) {
            return RichChatRowType.UNKNOWN;
        }
        if (RichChatPrivateMessageBridge.isAttentionMessage(rawVisible)) {
            return RichChatRowType.ATTENTION;
        }
        if (RichChatPrivateMessageBridge.isPrivateMessageLine(rawVisible)) {
            return RichChatRowType.PRIVATE_MESSAGE;
        }
        String visible = RichChatPrivateMessageBridge.stripVisibleMarkersForLayout(
                RichChatPrivateMessageBridge.rebuildVisibleText(rawVisible)
        );
        String firstLine = firstVisibleLine(visible);
        if (firstLine.isBlank()) {
            return RichChatRowType.UNKNOWN;
        }
        if (RichChatPrivateMessageBridge.isPrivateMessageLine(firstLine)) {
            return RichChatRowType.PRIVATE_MESSAGE;
        }
        if (PLAYER_CHAT_PREFIX.matcher(firstLine).matches()) {
            return RichChatRowType.PLAYER_CHAT;
        }
        if (RichChatPrivateMessageBridge.isAttentionMessage(firstLine)) {
            return RichChatRowType.ATTENTION;
        }
        if (JOIN_LEAVE_MESSAGE.matcher(firstLine).matches()) {
            return RichChatRowType.PLAYER_ACTIVITY;
        }
        if (ADVANCEMENT_CHALLENGE.matcher(firstLine).matches()) {
            return RichChatRowType.ADVANCEMENT_CHALLENGE;
        }
        if (ADVANCEMENT_GOAL.matcher(firstLine).matches()) {
            return RichChatRowType.ADVANCEMENT_GOAL;
        }
        if (ADVANCEMENT_TASK.matcher(firstLine).matches()) {
            return RichChatRowType.ADVANCEMENT_TASK;
        }
        RichChatRowType commandBlockType = commandBlockType(originalIndicator);
        if (commandBlockType != null) {
            return commandBlockType;
        }
        RichChatRowType pendingType = RichChatCommandOutputBridge.pendingType(firstLine, originalIndicator);
        if (pendingType != null) {
            return pendingType;
        }
        if (looksLikeCommandOutput(firstLine, originalIndicator)) {
            return RichChatRowType.COMMAND_OUTPUT;
        }
        return RichChatRowType.UNKNOWN;
    }

    private static String firstVisibleLine(String visible) {
        if (visible == null || visible.isBlank()) {
            return "";
        }
        String trimmed = visible.trim();
        int newline = trimmed.indexOf('\n');
        return newline >= 0 ? trimmed.substring(0, newline).trim() : trimmed;
    }

    private static boolean looksLikeCommandOutput(String firstLine, MessageIndicator originalIndicator) {
        if (firstLine == null || firstLine.isBlank()) {
            return false;
        }
        if (COMMANDISH_MESSAGE.matcher(firstLine).matches()) {
            return true;
        }
        if (originalIndicator == null) {
            return false;
        }
        String metadata = indicatorMetadata(originalIndicator);
        return metadata.contains("command");
    }

    private static RichChatRowType commandBlockType(MessageIndicator originalIndicator) {
        if (originalIndicator == null) {
            return null;
        }
        String metadata = indicatorMetadata(originalIndicator);
        if (!metadata.contains("command block")) {
            return null;
        }
        if (metadata.contains("chain command block")) {
            return RichChatRowType.COMMAND_BLOCK_CHAIN;
        }
        if (metadata.contains("repeating command block")) {
            return RichChatRowType.COMMAND_BLOCK_REPEATING;
        }
        return RichChatRowType.COMMAND_BLOCK_IMPULSE;
    }

    private static String indicatorMetadata(MessageIndicator originalIndicator) {
        if (originalIndicator == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        String loggedName = originalIndicator.loggedName();
        if (loggedName != null) {
            builder.append(loggedName.toLowerCase(Locale.ROOT)).append(' ');
        }
        if (originalIndicator.text() != null) {
            builder.append(originalIndicator.text().getString().toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }
}
