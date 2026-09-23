package com.spirit.koil.api.design.sprite.core;

/** Immutable logical cell inside Koil's detached 2D Minecraft scene. */
public record SceneCellPos(int x, int y, int depth) {
    public SceneCellPos add(int dx, int dy, int dDepth) {
        return new SceneCellPos(x + dx, y + dy, depth + dDepth);
    }
}
