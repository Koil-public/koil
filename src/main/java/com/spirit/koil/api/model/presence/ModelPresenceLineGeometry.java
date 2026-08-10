package com.spirit.koil.api.model.presence;

/**
 * Shared one-pixel status-line geometry for world and Tab player names.
 */
public final class ModelPresenceLineGeometry {
    private ModelPresenceLineGeometry() {
    }

    public static Bounds beneathName(int nameX, int nameY, int nameWidth, int fontHeight) {
        int width = Math.max(0, nameWidth);
        int top = nameY + Math.max(0, fontHeight - 1);
        return new Bounds(nameX - 1, top, nameX + width + 1, top + 1);
    }

    public record Bounds(int left, int top, int right, int bottom) {
    }
}
