package com.spirit.koil.chat.internal;

import com.spirit.koil.api.automation.cli.AutomationChatHudRenderer;
import com.spirit.koil.api.stats.global.client.MarketHudRenderer;
import com.spirit.koil.chat.internal.upload.RichChatAttachmentRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;

/**
 * Owns the vertical contract between chat-adjacent HUD panels and vanilla chat.
 * Panels are packed without gaps from the input-safe bottom edge upward.
 */
public final class ChatHudPanelStack {
    private static final int VANILLA_CHAT_BOTTOM = 40;
    private static final int OPEN_CHAT_CONTROL_CLEARANCE = 35;
    private static final int CLOSED_CHAT_CLEARANCE = 8;

    private ChatHudPanelStack() {
    }

    public static int reservedHeight(MinecraftClient client) {
        int panelHeight = totalPanelHeight(client);
        if (panelHeight <= 0) {
            return 0;
        }
        return Math.max(0, controlClearance(client) + panelHeight - VANILLA_CHAT_BOTTOM);
    }

    public static void render(DrawContext context, MinecraftClient client) {
        if (context == null || client == null || client.getWindow() == null) {
            return;
        }
        int bottom = client.getWindow().getScaledHeight()
                - controlClearance(client)
                - MultilineChatInputLayout.reservedHeight(client);

        int automationHeight = AutomationChatHudRenderer.panelHeight(client);
        if (automationHeight > 0) {
            bottom -= automationHeight;
            AutomationChatHudRenderer.renderAt(context, client, bottom);
        }

        int marketHeight = MarketHudRenderer.panelHeight(client);
        if (marketHeight > 0) {
            bottom -= marketHeight;
            MarketHudRenderer.renderAt(context, client, bottom);
        }

        int pinnedHeight = RichChatAttachmentRenderer.pinnedPanelHeight(client);
        if (pinnedHeight > 0) {
            bottom -= pinnedHeight;
            RichChatAttachmentRenderer.renderPinnedPanel(context, client, bottom);
        }
    }

    public static boolean mouseClicked(MinecraftClient client, double mouseX, double mouseY, int button) {
        if (client == null || client.getWindow() == null || button != 0) {
            return false;
        }
        int bottom = client.getWindow().getScaledHeight()
                - controlClearance(client)
                - MultilineChatInputLayout.reservedHeight(client);
        int automationHeight = AutomationChatHudRenderer.panelHeight(client);
        if (automationHeight <= 0) {
            return false;
        }
        return AutomationChatHudRenderer.mouseClickedAt(client, mouseX, mouseY, button, bottom - automationHeight);
    }

    private static int totalPanelHeight(MinecraftClient client) {
        return AutomationChatHudRenderer.panelHeight(client)
                + MarketHudRenderer.panelHeight(client)
                + RichChatAttachmentRenderer.pinnedPanelHeight(client);
    }

    private static int controlClearance(MinecraftClient client) {
        return client != null && client.currentScreen instanceof ChatScreen
                ? OPEN_CHAT_CONTROL_CLEARANCE
                : CLOSED_CHAT_CLEARANCE;
    }
}
