package com.spirit.koil.api.chat;

import com.spirit.koil.api.chat.latex.RichChatLatexTextureCache;

import java.util.ArrayDeque;
import java.util.Deque;

public final class RichChatRenderContext {
    private static volatile int chatViewportOffsetY;
    private static final ThreadLocal<Deque<DetachedSurface>> DETACHED_SURFACES =
            ThreadLocal.withInitial(ArrayDeque::new);

    private RichChatRenderContext() {
    }

    public static void beginChatHudFrame(int viewportOffsetY) {
        chatViewportOffsetY = viewportOffsetY;
    }

    public static void endChatHudFrame() {
        chatViewportOffsetY = 0;
    }

    /**
     * Pushes an explicit detached Rich Chat surface. Detached model/workspace
     * renderers must never inherit ChatHud width or viewport clipping because
     * the underlying chat may be closed, scrolled, or completely hidden.
     */
    public static SurfaceScope detachedSurface(int contentWidth, int viewportTop, int viewportBottom) {
        int safeWidth = Math.max(24, contentWidth);
        int safeTop = Math.min(viewportTop, viewportBottom);
        int safeBottom = Math.max(viewportTop, viewportBottom);
        Deque<DetachedSurface> stack = DETACHED_SURFACES.get();
        stack.push(new DetachedSurface(safeWidth, safeTop, safeBottom));
        return new SurfaceScope(stack);
    }

    public static int detachedContentWidth() {
        DetachedSurface surface = currentDetachedSurface();
        return surface == null ? -1 : surface.contentWidth();
    }

    public static int currentChatViewportTop() {
        DetachedSurface surface = currentDetachedSurface();
        return surface == null ? RichChatLatexTextureCache.currentChatViewportTop() : surface.viewportTop();
    }

    public static int currentChatViewportBottom() {
        DetachedSurface surface = currentDetachedSurface();
        return surface == null ? RichChatLatexTextureCache.currentChatViewportBottom() : surface.viewportBottom();
    }

    public static int currentScreenChatViewportTop() {
        DetachedSurface surface = currentDetachedSurface();
        return surface == null ? currentChatViewportTop() + chatViewportOffsetY : surface.viewportTop();
    }

    public static int currentScreenChatViewportBottom() {
        DetachedSurface surface = currentDetachedSurface();
        return surface == null ? currentChatViewportBottom() + chatViewportOffsetY : surface.viewportBottom();
    }

    private static DetachedSurface currentDetachedSurface() {
        Deque<DetachedSurface> stack = DETACHED_SURFACES.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    private record DetachedSurface(int contentWidth, int viewportTop, int viewportBottom) {
    }

    public static final class SurfaceScope implements AutoCloseable {
        private final Deque<DetachedSurface> stack;
        private boolean closed;

        private SurfaceScope(Deque<DetachedSurface> stack) {
            this.stack = stack;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (!stack.isEmpty()) stack.pop();
            if (stack.isEmpty()) DETACHED_SURFACES.remove();
        }
    }
}
