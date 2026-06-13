package com.spirit.koil.api.f3;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Set;

public final class F3LayoutState {
    private static F3Mode mode = F3Mode.NORMAL;
    private static F3RefreshSpeed refreshSpeed = F3RefreshSpeed.NORMAL;
    private static boolean frozen;
    private static boolean overlayVisible;
    private static boolean compactOverlay;
    private static final EnumMap<ScrollPanel, Integer> offsets = new EnumMap<>(ScrollPanel.class);
    private static final EnumMap<ScrollPanel, Integer> limits = new EnumMap<>(ScrollPanel.class);
    private static final EnumMap<ScrollPanel, Integer> frameLimits = new EnumMap<>(ScrollPanel.class);
    private static final EnumMap<ScrollPanel, Bounds> bounds = new EnumMap<>(ScrollPanel.class);
    private static final Set<String> pinnedCards = new LinkedHashSet<>();

    static {
        for (ScrollPanel panel : ScrollPanel.values()) {
            offsets.put(panel, 0);
            limits.put(panel, 0);
            frameLimits.put(panel, 0);
            bounds.put(panel, Bounds.empty());
        }
    }

    private F3LayoutState() {
    }

    public static F3Mode mode() {
        return mode;
    }

    public static void mode(F3Mode next) {
        mode = next == null ? F3Mode.NORMAL : next;
    }

    public static F3RefreshSpeed refreshSpeed() {
        return refreshSpeed;
    }

    public static void cycleRefreshSpeed() {
        F3RefreshSpeed[] values = F3RefreshSpeed.values();
        refreshSpeed = values[(refreshSpeed.ordinal() + 1) % values.length];
    }

    public static boolean frozen() {
        return frozen;
    }

    public static void toggleFrozen() {
        frozen = !frozen;
    }

    public static boolean compactOverlay() {
        return compactOverlay;
    }

    public static boolean overlayVisible() {
        return overlayVisible;
    }

    public static void overlayVisible(boolean enabled) {
        overlayVisible = enabled;
    }

    public static void toggleOverlayVisible() {
        overlayVisible = !overlayVisible;
    }

    public static int overlayLineOffset() {
        int max = 0;
        for (ScrollPanel panel : ScrollPanel.values()) {
            max = Math.max(max, offset(panel));
        }
        return max;
    }

    public static int overlayLeftLineOffset() {
        return offset(ScrollPanel.LEFT);
    }

    public static int overlayTargetLineOffset() {
        return offset(ScrollPanel.TARGET);
    }

    public static int overlaySelfLineOffset() {
        return offset(ScrollPanel.SELF);
    }

    public static int overlayRightLineOffset() {
        return offset(ScrollPanel.RIGHT);
    }

    public static int overlayGraphLineOffset() {
        return offset(ScrollPanel.GRAPH);
    }

    public static int offset(ScrollPanel panel) {
        return offsets.getOrDefault(panel, 0);
    }

    public static void scrollOverlayLines(int amount) {
        scrollLeftSidePanels(amount);
        scrollRightSidePanels(amount);
    }

    public static void scrollLeftSidePanels(int amount) {
        scrollPanel(ScrollPanel.LEFT, amount);
        scrollPanel(ScrollPanel.TARGET, amount);
        scrollPanel(ScrollPanel.SELF, amount);
    }

    public static void scrollRightSidePanels(int amount) {
        scrollPanel(ScrollPanel.RIGHT, amount);
        scrollPanel(ScrollPanel.GRAPH, amount);
    }

    public static void scrollOverlayLeftLines(int amount) {
        scrollLeftSidePanels(amount);
    }

    public static void scrollOverlayRightLines(int amount) {
        scrollRightSidePanels(amount);
    }

    public static void scrollOverlayGraphLines(int amount) {
        scrollPanel(ScrollPanel.GRAPH, amount);
    }

    public static void scrollPanel(ScrollPanel panel, int amount) {
        offsets.put(panel, clampOffset(offset(panel) + amount, limits.getOrDefault(panel, 0)));
    }

    public static void beginOverlayFrame() {
        for (ScrollPanel panel : ScrollPanel.values()) {
            frameLimits.put(panel, 0);
            bounds.put(panel, Bounds.empty());
        }
    }

    public static void observeLeftLineLimit(int contentRows, int visibleRows) {
        observePanelLimit(ScrollPanel.LEFT, contentRows, visibleRows);
    }

    public static void observeTargetLineLimit(int contentRows, int visibleRows) {
        observePanelLimit(ScrollPanel.TARGET, contentRows, visibleRows);
    }

    public static void observeSelfLineLimit(int contentRows, int visibleRows) {
        observePanelLimit(ScrollPanel.SELF, contentRows, visibleRows);
    }

    public static void observeRightLineLimit(int contentRows, int visibleRows) {
        observePanelLimit(ScrollPanel.RIGHT, contentRows, visibleRows);
    }

    public static void observeGraphLineLimit(int contentRows, int visibleRows) {
        observePanelLimit(ScrollPanel.GRAPH, contentRows, visibleRows);
    }

    public static void observePanelLimit(ScrollPanel panel, int contentRows, int visibleRows) {
        frameLimits.put(panel, Math.max(frameLimits.getOrDefault(panel, 0), Math.max(0, contentRows - visibleRows)));
    }

    public static void registerPanel(ScrollPanel panel, int x, int y, int w, int h, int contentRows, int visibleRows) {
        bounds.put(panel, new Bounds(x, y, w, h));
        observePanelLimit(panel, contentRows, visibleRows);
    }

    public static void finishOverlayFrame() {
        for (ScrollPanel panel : ScrollPanel.values()) {
            int limit = frameLimits.getOrDefault(panel, 0);
            limits.put(panel, limit);
            offsets.put(panel, clampOffset(offset(panel), limit));
        }
    }

    public static boolean canScrollLeftLines(int amount) {
        return canScroll(ScrollPanel.LEFT, amount) || canScroll(ScrollPanel.TARGET, amount) || canScroll(ScrollPanel.SELF, amount);
    }

    public static boolean canScrollRightLines(int amount) {
        return canScroll(ScrollPanel.RIGHT, amount) || canScroll(ScrollPanel.GRAPH, amount);
    }

    public static boolean canScrollGraphLines(int amount) {
        return canScroll(ScrollPanel.GRAPH, amount);
    }

    public static boolean canScroll(ScrollPanel panel, int amount) {
        return clampOffset(offset(panel) + amount, limits.getOrDefault(panel, 0)) != offset(panel);
    }

    public static ScrollPanel panelAt(double mouseX, double mouseY) {
        for (ScrollPanel panel : ScrollPanel.values()) {
            Bounds b = bounds.getOrDefault(panel, Bounds.empty());
            if (b.contains(mouseX, mouseY)) {
                return panel;
            }
        }
        return ScrollPanel.NONE;
    }

    public static void resetOverlayScroll() {
        for (ScrollPanel panel : ScrollPanel.values()) {
            offsets.put(panel, 0);
        }
    }

    private static int clampOffset(int value, int limit) {
        return Math.max(0, Math.min(Math.max(0, limit), value));
    }

    public static void compactOverlay(boolean enabled) {
        compactOverlay = enabled;
    }

    public static Set<String> pinnedCards() {
        return new LinkedHashSet<>(pinnedCards);
    }

    public static void togglePinned(String id) {
        if (pinnedCards.contains(id)) {
            pinnedCards.remove(id);
        } else if (id != null && !id.isBlank()) {
            pinnedCards.add(id);
        }
    }

    public enum ScrollPanel {
        LEFT,
        TARGET,
        SELF,
        RIGHT,
        GRAPH,
        NONE
    }

    private record Bounds(int x, int y, int w, int h) {
        private static Bounds empty() {
            return new Bounds(0, 0, 0, 0);
        }

        private boolean contains(double px, double py) {
            return w > 0 && h > 0 && px >= x && py >= y && px <= x + w && py <= y + h;
        }
    }
}
