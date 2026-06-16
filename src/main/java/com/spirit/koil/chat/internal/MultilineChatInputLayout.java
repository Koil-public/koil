package com.spirit.koil.chat.internal;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;

public final class MultilineChatInputLayout {
    private static int reservedHeight;

    private MultilineChatInputLayout() {
    }

    public static void setReservedHeight(int height) {
        reservedHeight = Math.max(0, height);
    }

    public static void clear() {
        reservedHeight = 0;
    }

    public static int reservedHeight(MinecraftClient client) {
        if (client == null || !(client.currentScreen instanceof ChatScreen)) {
            return 0;
        }
        return reservedHeight;
    }
}
