package com.spirit.koil.api.chat;

import net.minecraft.client.MinecraftClient;

/** Immutable per-frame layout data supplied to chat panel extensions. */
public record ChatHudPanelContext(
        MinecraftClient client,
        int screenWidth,
        int screenHeight,
        int chatWidth,
        int panelWidth,
        boolean chatOpen
) {
}
