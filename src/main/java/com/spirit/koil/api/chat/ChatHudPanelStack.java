package com.spirit.koil.api.chat;

import com.spirit.koil.api.automation.cli.AutomationChatHudRenderer;
import com.spirit.koil.api.chat.upload.RichChatAttachmentRenderer;
import com.spirit.koil.api.stats.global.client.MarketHudRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Compatibility facade for the dynamic {@link ChatHudPanelRegistry}.
 * Existing Koil surfaces are registered through the same public panel contract
 * available to extensions.
 */
public final class ChatHudPanelStack {
    private static boolean builtInsRegistered;

    private ChatHudPanelStack() {
    }

    public static int reservedHeight(MinecraftClient client) {
        ensureBuiltInsRegistered();
        return ChatHudPanelRegistry.reservedBottomHeight(client);
    }

    public static void beginChatFrame(int reservedHeight) {
        ensureBuiltInsRegistered();
        ChatHudPanelRegistry.beginChatFrame(reservedHeight);
    }

    public static void observeChatLine(DrawContext context, int localY) {
        ChatHudPanelRegistry.observeChatLine(context, localY);
    }

    public static void render(DrawContext context, MinecraftClient client) {
        ensureBuiltInsRegistered();
        ChatHudPanelRegistry.render(context, client);
    }

    public static boolean mouseClicked(MinecraftClient client, double mouseX, double mouseY, int button) {
        ensureBuiltInsRegistered();
        return ChatHudPanelRegistry.mouseClicked(client, mouseX, mouseY, button);
    }

    private static synchronized void ensureBuiltInsRegistered() {
        if (builtInsRegistered) {
            return;
        }
        ChatHudPanelRegistry.registerIfAbsent(new ChatHudPanel() {
            @Override
            public String id() {
                return "koil:automation";
            }

            @Override
            public ChatHudPanelPlacement placement() {
                return ChatHudPanelPlacement.BOTTOM;
            }

            @Override
            public int order() {
                return 100;
            }

            @Override
            public int height(ChatHudPanelContext context) {
                return AutomationChatHudRenderer.panelHeight(context.client(), context.panelWidth());
            }

            @Override
            public void render(DrawContext drawContext, ChatHudPanelContext context, ChatHudPanelBounds bounds) {
                AutomationChatHudRenderer.renderAt(drawContext, context.client(), bounds.y(), bounds.width());
            }

            @Override
            public boolean mouseClicked(ChatHudPanelContext context, ChatHudPanelBounds bounds, double mouseX, double mouseY, int button) {
                return AutomationChatHudRenderer.mouseClickedAt(context.client(), mouseX, mouseY, button, bounds.y(), bounds.width());
            }
        });
        ChatHudPanelRegistry.registerIfAbsent(new ChatHudPanel() {
            @Override
            public String id() {
                return "koil:market";
            }

            @Override
            public ChatHudPanelPlacement placement() {
                return ChatHudPanelPlacement.BOTTOM;
            }

            @Override
            public int order() {
                return 200;
            }

            @Override
            public int height(ChatHudPanelContext context) {
                return MarketHudRenderer.panelHeight(context.client(), context.panelWidth());
            }

            @Override
            public void render(DrawContext drawContext, ChatHudPanelContext context, ChatHudPanelBounds bounds) {
                MarketHudRenderer.renderAt(drawContext, context.client(), bounds.y(), bounds.width());
            }
        });
        ChatHudPanelRegistry.registerIfAbsent(new ChatHudPanel() {
            @Override
            public String id() {
                return "koil:pinned_media";
            }

            @Override
            public ChatHudPanelPlacement placement() {
                return ChatHudPanelPlacement.BOTTOM;
            }

            @Override
            public int order() {
                return 300;
            }

            @Override
            public int height(ChatHudPanelContext context) {
                return RichChatAttachmentRenderer.pinnedPanelHeight(context.client(), context.panelWidth());
            }

            @Override
            public int width(ChatHudPanelContext context) {
                return RichChatAttachmentRenderer.pinnedPanelWidth(context.client(), context.panelWidth());
            }

            @Override
            public void render(DrawContext drawContext, ChatHudPanelContext context, ChatHudPanelBounds bounds) {
                RichChatAttachmentRenderer.renderPinnedPanel(drawContext, context.client(), bounds.x(), bounds.y(), bounds.width());
            }
        });
        builtInsRegistered = true;
    }
}
