package com.spirit.koil.api.chat;

import net.minecraft.client.gui.DrawContext;

/**
 * Public contract for a panel that follows Minecraft's chat layout.
 * Implementations own their content; the registry owns placement and stacking.
 */
public interface ChatHudPanel {
    String id();

    ChatHudPanelPlacement placement();

    /** Lower values are placed nearest the placement edge. */
    default int order() {
        return 0;
    }

    default boolean visible(ChatHudPanelContext context) {
        return true;
    }

    int height(ChatHudPanelContext context);

    /** Defaults to Koil's chat-width-responsive panel width. */
    default int width(ChatHudPanelContext context) {
        return context.panelWidth();
    }

    void render(DrawContext drawContext, ChatHudPanelContext context, ChatHudPanelBounds bounds);

    default boolean mouseClicked(ChatHudPanelContext context, ChatHudPanelBounds bounds, double mouseX, double mouseY, int button) {
        return false;
    }
}
