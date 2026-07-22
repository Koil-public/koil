package com.spirit.koil.chat.internal;

import com.spirit.koil.chat.internal.latex.RichChatLatexTextureCache;

public final class RichChatRenderContext {
    private static volatile int chatViewportOffsetY;

    private RichChatRenderContext() {
    }

    public static void beginChatHudFrame(int viewportOffsetY) {
        chatViewportOffsetY = viewportOffsetY;
    }

    public static void endChatHudFrame() {
        chatViewportOffsetY = 0;
    }

    public static int currentChatViewportTop() {
        return RichChatLatexTextureCache.currentChatViewportTop();
    }

    public static int currentChatViewportBottom() {
        return RichChatLatexTextureCache.currentChatViewportBottom();
    }

    public static int currentScreenChatViewportTop() {
        return currentChatViewportTop() + chatViewportOffsetY;
    }

    public static int currentScreenChatViewportBottom() {
        return currentChatViewportBottom() + chatViewportOffsetY;
    }
}
