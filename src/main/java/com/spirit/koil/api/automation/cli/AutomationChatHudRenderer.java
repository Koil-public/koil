package com.spirit.koil.api.automation.cli;

import com.spirit.client.gui.console.ConsoleScreen;
import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.automation.feedback.AutomationFeedbackService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AutomationChatHudRenderer {
    private AutomationChatHudRenderer() {
    }

    public static int reservedHeight(MinecraftClient client) {
        return panelHeight(client);
    }

    public static int occupiedHeight(MinecraftClient client) {
        return panelHeight(client);
    }

    public static int panelHeight(MinecraftClient client) {
        AutomationHudBlock block = buildBlock(client);
        return block == null || client == null ? 0 : block.height;
    }

    public static void render(DrawContext context, MinecraftClient client) {
        AutomationHudBlock block = buildBlock(client);
        if (block == null || client == null) {
            return;
        }
        int y = client.getWindow().getScaledHeight() - bottomOffset(client) - block.height;
        renderAt(context, client, y);
    }

    public static void renderAt(DrawContext context, MinecraftClient client, int y) {
        AutomationHudBlock block = buildBlock(client);
        if (block == null || client == null) {
            return;
        }
        int x = 0;
        int background = chatBackgroundColor(client);
        int stateColor = withAlpha(AutomationStateColors.color(AutomationChatHudState.state()), Math.min(255, alpha(background) + 48));
        context.fill(x, y, x + block.width, y + block.height, background);
        context.fill(x, y, x + 2, y + block.height, stateColor);
        int textY = y + block.paddingY;
        for (OrderedText line : block.lines) {
            context.drawTextWithShadow(client.textRenderer, line, x + block.paddingX, textY, 0xFFFFFF);
            textY += block.lineHeight;
        }
        List<ActionRect> rects = actionRects(client, block, x, y);
        for (ActionRect rect : rects) {
            int fill = fillFor(rect.action().kind(), background);
            context.fill(rect.x1(), rect.y1(), rect.x2(), rect.y2(), fill);
            context.fill(rect.x1(), rect.y1(), rect.x2(), rect.y1() + 1, withAlpha(0x00FFFFFF, Math.min(120, alpha(background) + 40)));
            context.drawTextWithShadow(client.textRenderer, Text.literal(rect.action().label()), rect.x1() + 5, rect.y1() + 3, 0xFFFFFF);
        }
    }

    public static boolean mouseClicked(MinecraftClient client, double mouseX, double mouseY, int button) {
        if (button != 0 || client == null) {
            return false;
        }
        AutomationHudBlock block = buildBlock(client);
        if (block == null) {
            return false;
        }
        int y = client.getWindow().getScaledHeight() - bottomOffset(client) - block.height;
        return mouseClickedAt(client, mouseX, mouseY, button, y);
    }

    public static boolean mouseClickedAt(MinecraftClient client, double mouseX, double mouseY, int button, int y) {
        if (button != 0 || client == null) {
            return false;
        }
        AutomationHudBlock block = buildBlock(client);
        if (block == null) {
            return false;
        }
        int x = 0;
        for (ActionRect rect : actionRects(client, block, x, y)) {
            if (!rect.contains(mouseX, mouseY)) {
                continue;
            }
            String command = rect.action().command();
            if (command.isBlank()) {
                return false;
            }
            AutomationFeedbackService.handleConsoleInput(command.startsWith("/") ? command : "/" + command);
            return true;
        }
        return false;
    }

    private static AutomationHudBlock buildBlock(MinecraftClient client) {
        if (client == null || client.player == null || client.currentScreen instanceof ConsoleScreen) {
            return null;
        }
        if (!(client.currentScreen == null || client.currentScreen instanceof ChatScreen)) {
            return null;
        }
        if (!AutomationChatHudState.visible()) {
            return null;
        }
        if (!AutomationModeController.isAutomationMode() && !isFeedbackState(AutomationChatHudState.state())) {
            AutomationChatHudState.hide();
            return null;
        }
        int chatWidth = client.inGameHud == null ? 0 : client.inGameHud.getChatHud().getWidth();
        int maxWidth = Math.min(client.getWindow().getScaledWidth(), Math.max(190, chatWidth + 12));
        int innerWidth = maxWidth - 12;
        List<OrderedText> lines = new ArrayList<>();
        lines.addAll(wrappedLines(client, AutomationChatHudState.header(), innerWidth, 1));
        lines.addAll(wrappedLines(client, AutomationChatHudState.prompt(), innerWidth, 2));
        lines.addAll(wrappedLines(client, AutomationChatHudState.active(), innerWidth, 2));
        int paddingX = 6;
        int paddingY = 3;
        int lineHeight = client.textRenderer.fontHeight + 1;
        int actionRows = countActionRows(client, maxWidth - paddingX * 2);
        int actionHeight = actionRows == 0 ? 0 : actionRows * (client.textRenderer.fontHeight + 7) + 3;
        int height = Math.max(lineHeight, lines.size() * lineHeight) + paddingY * 2 + actionHeight;
        return new AutomationHudBlock(lines, maxWidth, height, paddingX, paddingY, lineHeight);
    }

    private static List<ActionRect> actionRects(MinecraftClient client, AutomationHudBlock block, int x, int y) {
        List<AutomationChatHudState.Action> actions = AutomationChatHudState.actions();
        if (actions.isEmpty()) {
            return List.of();
        }
        List<ActionRect> rects = new ArrayList<>();
        int maxX = x + block.width - block.paddingX;
        int rowHeight = client.textRenderer.fontHeight + 6;
        int cursorX = x + block.paddingX;
        int cursorY = y + block.paddingY + block.lines.size() * block.lineHeight + 3;
        for (AutomationChatHudState.Action action : actions) {
            if (action.label().isBlank()) {
                continue;
            }
            int width = Math.min(block.width - block.paddingX * 2, client.textRenderer.getWidth(action.label()) + 10);
            if (cursorX + width > maxX && cursorX > x + block.paddingX) {
                cursorX = x + block.paddingX;
                cursorY += rowHeight + 1;
            }
            rects.add(new ActionRect(action, cursorX, cursorY, cursorX + width, cursorY + rowHeight));
            cursorX += width + 5;
        }
        return rects;
    }

    private static int countActionRows(MinecraftClient client, int width) {
        List<AutomationChatHudState.Action> actions = AutomationChatHudState.actions();
        if (actions.isEmpty() || !hasVisibleAction(actions)) {
            return 0;
        }
        int rows = 1;
        int cursor = 0;
        for (AutomationChatHudState.Action action : actions) {
            if (action.label().isBlank()) {
                continue;
            }
            int buttonWidth = Math.min(width, client.textRenderer.getWidth(action.label()) + 15);
            if (cursor + buttonWidth > width && cursor > 0) {
                rows++;
                cursor = 0;
            }
            cursor += buttonWidth + 5;
        }
        return rows;
    }

    private static int bottomOffset(MinecraftClient client) {
        if (client.currentScreen instanceof ChatScreen) {
            return 22;
        }
        return 8;
    }

    private static boolean hasVisibleAction(List<AutomationChatHudState.Action> actions) {
        for (AutomationChatHudState.Action action : actions) {
            if (!action.label().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static List<OrderedText> wrappedLines(MinecraftClient client, Text text, int width, int maxLines) {
        if (text == null || text.getString().isBlank()) {
            return List.of();
        }
        List<OrderedText> lines = new ArrayList<>();
        List<OrderedText> wrapped = client.textRenderer.wrapLines(text, width);
        for (int i = 0; i < wrapped.size() && i < maxLines; i++) {
            lines.add(wrapped.get(i));
        }
        return lines;
    }

    private static int chatBackgroundColor(MinecraftClient client) {
        double opacity = client.options.getTextBackgroundOpacity().getValue();
        int value = Math.max(0, Math.min(255, (int) (255.0D * opacity)));
        return value << 24;
    }

    private static int fillFor(String kind, int background) {
        int alpha = Math.min(230, Math.max(90, alpha(background) + 30));
        String normalized = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        if (normalized.contains("good")) {
            return withAlpha(0x00143A21, alpha);
        }
        if (normalized.contains("bad") || normalized.contains("failure")) {
            return withAlpha(0x004A1717, alpha);
        }
        if (normalized.contains("node")) {
            return withAlpha(0x001A273A, alpha);
        }
        if (normalized.contains("file")) {
            return withAlpha(0x001D1D2E, alpha);
        }
        return withAlpha(0x00222222, alpha);
    }

    private static boolean isFeedbackState(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return normalized.startsWith("feedback") || normalized.equals("complete") || normalized.equals("failed") || normalized.equals("blocked") || normalized.equals("canceled") || normalized.startsWith("improvement");
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    private static int alpha(int color) {
        return color >>> 24;
    }

    private record ActionRect(AutomationChatHudState.Action action, int x1, int y1, int x2, int y2) {
        private boolean contains(double x, double y) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2;
        }
    }
}
