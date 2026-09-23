package com.spirit.koil.api.design.sprite.world;

/** 32x32 cell storage for one scene depth lane. */
public final class SceneChunk {
    public static final int SIZE = 32;
    private final BlockCell[] cells = new BlockCell[SIZE * SIZE];
    private boolean dirty = true;

    BlockCell get(int localX, int localY) {
        return cells[index(localX, localY)];
    }

    void set(int localX, int localY, BlockCell cell) {
        cells[index(localX, localY)] = cell;
        dirty = true;
    }

    boolean isEmpty() {
        for (BlockCell cell : cells) if (cell != null && !cell.isAir()) return false;
        return true;
    }

    public boolean dirty() { return dirty; }
    public void clearDirty() { dirty = false; }

    private static int index(int x, int y) {
        return Math.floorMod(y, SIZE) * SIZE + Math.floorMod(x, SIZE);
    }
}
