package com.spirit.koil.api.design.sprite.world;

import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.state.property.Property;

import java.util.Map;

/**
 * Authoritative fluid state for one detached scene cell.
 *
 * <p>Fluid ownership is intentionally separate from {@link BlockCell}. A scene
 * cell may contain both a block and fluid, as with waterlogged blocks. LEGACY_SEED
 * exists only to migrate an old /sprite source into this grid. Flow, decay, mixing
 * and rendering are scene-native.</p>
 */
public final class FluidCell {
    public enum Authority { SCENE, BLOCK_STATE, LEGACY_SEED }

    private FluidState state;
    private int level;
    private boolean source;
    private boolean falling;
    private Authority authority;
    private long sourceId;

    public FluidCell(FluidState state) {
        this(state, Authority.SCENE, -1L);
    }

    public FluidCell(FluidState state, Authority authority, long sourceId) {
        this.authority = authority == null ? Authority.SCENE : authority;
        this.sourceId = sourceId;
        setState(state);
    }

    public static FluidCell empty() { return new FluidCell(Fluids.EMPTY.getDefaultState()); }

    public FluidState state() { return state; }
    public Fluid fluid() { return state == null ? Fluids.EMPTY : state.getFluid(); }
    public int level() { return level; }
    public boolean source() { return source; }
    public boolean falling() { return falling; }
    public Authority authority() { return authority; }
    public long sourceId() { return sourceId; }
    public boolean isEmpty() { return state == null || state.isEmpty(); }

    public void setState(FluidState state) {
        this.state = state == null ? Fluids.EMPTY.getDefaultState() : state;
        if (this.state.isEmpty()) {
            level = 0;
            source = false;
            falling = false;
            return;
        }
        Fluid fluid = this.state.getFluid();
        try { level = Math.max(1, Math.min(8, fluid.getLevel(this.state))); }
        catch (RuntimeException ignored) { level = fluid.isStill(this.state) ? 8 : 1; }
        try { source = fluid.isStill(this.state); }
        catch (RuntimeException ignored) { source = level >= 8; }
        falling = booleanProperty(this.state, "falling", false);
    }

    public void setAuthority(Authority authority, long sourceId) {
        this.authority = authority == null ? Authority.SCENE : authority;
        this.sourceId = sourceId;
    }

    public FluidCell copy() {
        return new FluidCell(state, authority, sourceId);
    }

    private static boolean booleanProperty(FluidState state, String key, boolean fallback) {
        if (state == null || key == null) return fallback;
        try {
            for (Map.Entry<Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
                if (entry.getKey().getName().equalsIgnoreCase(key)) {
                    return Boolean.parseBoolean(String.valueOf(entry.getValue()));
                }
            }
        } catch (RuntimeException ignored) { }
        return fallback;
    }
}
