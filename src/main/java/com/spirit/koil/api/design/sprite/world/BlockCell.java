package com.spirit.koil.api.design.sprite.world;

import net.minecraft.block.BlockState;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Authoritative static block cell. Fluid state is owned by FluidGrid. */
public final class BlockCell {
    public enum Authority { SCENE, LEGACY_PROXY }

    private BlockState blockState;
    private final Map<String, String> blockEntityData = new LinkedHashMap<>();
    private final Map<String, String> runtimeData = new LinkedHashMap<>();
    private int flags;
    private Authority authority;
    private long sourceId;
    /** Legacy authoring source whose lifetime still owns this cell, even after scene gameplay takes state authority. */
    private long lifetimeSourceId;
    /** Scene game tick when this cell's current authored lifetime began. */
    private long createdGameTick;

    public BlockCell(BlockState blockState) {
        this(blockState, Authority.SCENE, -1L, 0L);
    }

    public BlockCell(BlockState blockState, Authority authority, long sourceId) {
        this(blockState, authority, sourceId, 0L);
    }

    public BlockCell(BlockState blockState, Authority authority, long sourceId, long createdGameTick) {
        this.authority = authority == null ? Authority.SCENE : authority;
        this.sourceId = sourceId;
        this.lifetimeSourceId = this.authority == Authority.LEGACY_PROXY ? sourceId : -1L;
        this.blockState = blockState;
        this.createdGameTick = Math.max(0L, createdGameTick);
    }

    public BlockState blockState() { return blockState; }
    public int flags() { return flags; }
    public Authority authority() { return authority; }
    public long sourceId() { return sourceId; }
    public long lifetimeSourceId() { return lifetimeSourceId; }
    public long createdGameTick() { return createdGameTick; }
    public Map<String, String> blockEntityData() { return Collections.unmodifiableMap(blockEntityData); }
    public Map<String, String> runtimeData() { return Collections.unmodifiableMap(runtimeData); }

    public void setBlockState(BlockState blockState) { this.blockState = blockState; }
    public void setCreatedGameTick(long tick) { this.createdGameTick = Math.max(0L, tick); }

    void setAuthority(Authority authority, long sourceId) {
        this.authority = authority == null ? Authority.SCENE : authority;
        this.sourceId = sourceId;
        if (this.authority == Authority.LEGACY_PROXY && sourceId >= 0L) this.lifetimeSourceId = sourceId;
    }

    void clearLifetimeSource() { this.lifetimeSourceId = -1L; }

    public void setFlags(int flags) { this.flags = flags; }
    public void putBlockEntityData(String key, String value) {
        if (key == null || key.isBlank()) return;
        if (value == null) blockEntityData.remove(key); else blockEntityData.put(key, value);
    }

    public void putRuntimeData(String key, String value) {
        String normalized = normalizeKey(key);
        if (normalized.isEmpty()) return;
        if (value == null || value.isBlank()) runtimeData.remove(normalized);
        else runtimeData.put(normalized, value.trim());
    }

    public String runtime(String key, String fallback) {
        String value = runtimeData.get(normalizeKey(key));
        return value == null ? fallback : value;
    }

    public boolean runtimeBoolean(String key, boolean fallback) {
        String value = runtimeData.get(normalizeKey(key));
        if (value == null) return fallback;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> fallback;
        };
    }

    public float runtimeFloat(String key, float fallback) {
        String value = runtimeData.get(normalizeKey(key));
        if (value == null) return fallback;
        try {
            float parsed = Float.parseFloat(value.trim());
            return Float.isFinite(parsed) ? parsed : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public long runtimeLong(String key, long fallback) {
        String value = runtimeData.get(normalizeKey(key));
        if (value == null) return fallback;
        try { return Long.parseLong(value.trim()); }
        catch (RuntimeException ignored) { return fallback; }
    }

    public boolean isAir() { return blockState == null || blockState.isAir(); }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }
}
