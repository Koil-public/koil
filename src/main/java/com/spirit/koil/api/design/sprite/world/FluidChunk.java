package com.spirit.koil.api.design.sprite.world;

/** 32x32 fluid-cell storage for one detached scene depth lane. */
public final class FluidChunk {
    public static final int SIZE = 32;
    private final FluidCell[] cells = new FluidCell[SIZE * SIZE];
    private boolean dirty = true;

    FluidCell get(int localX, int localY) { return cells[index(localX, localY)]; }
    void set(int localX, int localY, FluidCell cell) {
        cells[index(localX, localY)] = cell;
        dirty = true;
    }
    boolean isEmpty() {
        for (FluidCell cell : cells) if (cell != null && !cell.isEmpty()) return false;
        return true;
    }
    public boolean dirty() { return dirty; }
    public void clearDirty() { dirty = false; }
    private static int index(int x, int y) {
        return Math.floorMod(y, SIZE) * SIZE + Math.floorMod(x, SIZE);
    }
}
