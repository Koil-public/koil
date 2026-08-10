package com.spirit.koil.api.automation.cli;

import com.spirit.client.gui.console.ConsoleScreen;
import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.automation.AutomationRouter;
import com.spirit.koil.api.automation.AutomationRuntimeStatus;
import com.spirit.koil.api.automation.feedback.AutomationFeedbackService;
import com.spirit.koil.api.chat.ChatHudPanelVisualStyle;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import com.spirit.koil.api.model.LocalModelService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AutomationChatHudRenderer {
    private static final int ACTION_HORIZONTAL_PADDING = 8;
    private static final int ACTION_GAP = 3;
    private AutomationChatHudRenderer() {
    }

    public static int reservedHeight(MinecraftClient client) {
        return panelHeight(client);
    }

    public static int occupiedHeight(MinecraftClient client) {
        return panelHeight(client);
    }

    public static int panelHeight(MinecraftClient client) {
        return panelHeight(client, 0);
    }

    public static int panelHeight(MinecraftClient client, int requestedWidth) {
        AutomationHudBlock block = buildBlock(client, requestedWidth);
        return block == null || client == null ? 0 : block.height;
    }

    public static void render(DrawContext context, MinecraftClient client) {
        AutomationHudBlock block = buildBlock(client, 0);
        if (block == null || client == null) {
            return;
        }
        int y = client.getWindow().getScaledHeight() - bottomOffset(client) - block.height;
        renderAt(context, client, y);
    }

    public static void renderAt(DrawContext context, MinecraftClient client, int y) {
        renderAt(context, client, y, 0);
    }

    public static void renderAt(DrawContext context, MinecraftClient client, int y, int requestedWidth) {
        AutomationHudBlock block = buildBlock(client, requestedWidth);
        if (block == null || client == null) {
            return;
        }
        int x = 0;
        int background = ChatHudPanelVisualStyle.background(client);
        int stateColor = withAlpha(AutomationStateColors.color(AutomationChatHudState.executorSemanticState()), Math.min(255, alpha(background) + 48));
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
            context.drawTextWithShadow(client.textRenderer, Text.literal(rect.action().label()), rect.x1() + 5, rect.y1() + 3, 0xFFFFFF);
        }
        renderActionTooltip(context, client, rects);
    }

    public static boolean mouseClicked(MinecraftClient client, double mouseX, double mouseY, int button) {
        if (button != 0 || client == null) {
            return false;
        }
        AutomationHudBlock block = buildBlock(client, 0);
        if (block == null) {
            return false;
        }
        int y = client.getWindow().getScaledHeight() - bottomOffset(client) - block.height;
        return mouseClickedAt(client, mouseX, mouseY, button, y);
    }

    public static boolean mouseClickedAt(MinecraftClient client, double mouseX, double mouseY, int button, int y) {
        return mouseClickedAt(client, mouseX, mouseY, button, y, 0);
    }

    public static boolean mouseClickedAt(MinecraftClient client, double mouseX, double mouseY, int button, int y, int requestedWidth) {
        if (button != 0 || client == null) {
            return false;
        }
        AutomationHudBlock block = buildBlock(client, requestedWidth);
        if (block == null) {
            return false;
        }
        int x = 0;
        for (ActionRect rect : actionRects(client, block, x, y)) {
            if (!rect.contains(mouseX, mouseY)) {
                continue;
            }
            if ("stop_automation".equals(rect.action().kind())) {
                com.spirit.koil.api.automation.AutomationCompletionModeController.stoppedByUser();
                LocalModelService.cancelActiveWork();
                AutomationRouter.cancelCurrentTask("stopped from Automation popup");
                return true;
            }
            String command = rect.action().command();
            if (command.isBlank()) {
                return false;
            }
            if ("feedback_note".equals(rect.action().kind())) {
                client.setScreen(new ChatScreen(command));
                return true;
            }
            AutomationFeedbackService.handleConsoleInput(command.startsWith("/") ? command : "/" + command);
            return true;
        }
        return false;
    }

    private static AutomationHudBlock buildBlock(MinecraftClient client, int requestedWidth) {
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
        int defaultWidth = Math.min(client.getWindow().getScaledWidth(), Math.max(190, chatWidth + 12));
        int maxWidth = requestedWidth > 0
                ? Math.min(client.getWindow().getScaledWidth(), Math.max(1, requestedWidth))
                : defaultWidth;
        int innerWidth = maxWidth - 12;
        List<OrderedText> lines = new ArrayList<>();
        lines.addAll(wrappedLines(client, AutomationChatHudState.header(), innerWidth, 1));
        lines.addAll(wrappedLines(client, AutomationChatHudState.executorStatusLine(), innerWidth, 1));
        lines.addAll(wrappedLines(client, AutomationChatHudState.prompt(), innerWidth, 2));
        lines.addAll(wrappedLines(client, AutomationChatHudState.active(), innerWidth, 2));
        lines.addAll(wrappedLines(client, AutomationChatHudState.tool(), innerWidth, 2));
        int paddingX = 6;
        int paddingY = 3;
        int lineHeight = client.textRenderer.fontHeight + 1;
        int actionRows = countActionRows(client, maxWidth - paddingX * 2);
        int actionHeight = actionRows == 0 ? 0 : actionRows * (client.textRenderer.fontHeight + 7) + 3;
        int height = Math.max(lineHeight, lines.size() * lineHeight) + paddingY * 2 + actionHeight;
        return new AutomationHudBlock(lines, maxWidth, height, paddingX, paddingY, lineHeight);
    }

    private static List<ActionRect> actionRects(MinecraftClient client, AutomationHudBlock block, int x, int y) {
        List<AutomationChatHudState.Action> actions = effectiveActions();
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
            int width = Math.min(block.width - block.paddingX * 2, client.textRenderer.getWidth(action.label()) + ACTION_HORIZONTAL_PADDING);
            if (cursorX + width > maxX && cursorX > x + block.paddingX) {
                cursorX = x + block.paddingX;
                cursorY += rowHeight + 1;
            }
            rects.add(new ActionRect(action, cursorX, cursorY, cursorX + width, cursorY + rowHeight));
            cursorX += width + ACTION_GAP;
        }
        return rects;
    }

    private static int countActionRows(MinecraftClient client, int width) {
        List<AutomationChatHudState.Action> actions = effectiveActions();
        if (actions.isEmpty() || !hasVisibleAction(actions)) {
            return 0;
        }
        int rows = 1;
        int cursor = 0;
        for (AutomationChatHudState.Action action : actions) {
            if (action.label().isBlank()) {
                continue;
            }
            int buttonWidth = Math.min(width, client.textRenderer.getWidth(action.label()) + ACTION_HORIZONTAL_PADDING);
            if (cursor + buttonWidth > width && cursor > 0) {
                rows++;
                cursor = 0;
            }
            cursor += buttonWidth + ACTION_GAP;
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

    private static List<AutomationChatHudState.Action> effectiveActions() {
        List<AutomationChatHudState.Action> configured = AutomationChatHudState.actions();
        if (!AutomationRuntimeStatus.isTaskRunning()) return configured;
        List<AutomationChatHudState.Action> actions = new ArrayList<>(configured);
        boolean present = actions.stream().anyMatch(action -> "stop_automation".equals(action.kind()));
        if (!present) {
            actions.add(new AutomationChatHudState.Action("automation.stop", "Stop", "", "", "stop_automation"));
        }
        return List.copyOf(actions);
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

    private static int fillFor(String kind, int background) {
        int alpha = Math.min(230, Math.max(90, alpha(background) + 30));
        String normalized = kind == null ? "" : kind.toLowerCase(Locale.ROOT);
        if (normalized.contains("good")) {
            return withAlpha(0x00143A21, alpha);
        }
        if (normalized.contains("bad") || normalized.contains("failure")) {
            return withAlpha(0x004A1717, alpha);
        }
        if (normalized.contains("stop")) {
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

    private static void renderActionTooltip(
            DrawContext context,
            MinecraftClient client,
            List<ActionRect> rects
    ) {
        if (!(client.currentScreen instanceof ChatScreen) || client.getWindow() == null) {
            return;
        }
        int mouseX = (int) Math.round(client.mouse.getX()
                * client.getWindow().getScaledWidth() / (double) client.getWindow().getWidth());
        int mouseY = (int) Math.round(client.mouse.getY()
                * client.getWindow().getScaledHeight() / (double) client.getWindow().getHeight());
        for (ActionRect rect : rects) {
            if (!rect.contains(mouseX, mouseY) || rect.action().hoverDetails().isBlank()) {
                continue;
            }
            List<Text> tooltip = new ArrayList<>();
            for (String rawLine : rect.action().hoverDetails().split("\\n")) {
                String line = compactTooltipLine(rawLine);
                if (!line.isBlank()) {
                    tooltip.add(Text.literal(line).formatted(tooltip.isEmpty()
                            ? net.minecraft.util.Formatting.WHITE
                            : net.minecraft.util.Formatting.GRAY));
                }
                if (tooltip.size() >= 8) break;
            }
            if (!tooltip.isEmpty()) {
                context.getMatrices().push();
                context.getMatrices().translate(0.0F, 0.0F, 1_000.0F);
                context.drawTooltip(client.textRenderer, tooltip, java.util.Optional.empty(), mouseX, mouseY);
                context.getMatrices().pop();
            }
            return;
        }
    }

    private static String compactTooltipLine(String value) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return clean.length() <= 180 ? clean : clean.substring(0, 179) + "…";
    }

    private static boolean isFeedbackState(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return normalized.startsWith("feedback")
                || normalized.equals("complete")
                || normalized.equals("completed")
                || normalized.equals("failed")
                || normalized.equals("blocked")
                || normalized.equals("partial")
                || normalized.equals("cancelled")
                || normalized.equals("canceled")
                || normalized.equals("interrupted")
                || normalized.equals("already satisfied")
                || normalized.equals("already_satisfied")
                || normalized.startsWith("improvement");
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
