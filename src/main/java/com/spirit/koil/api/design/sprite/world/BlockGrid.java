package com.spirit.koil.api.design.sprite.world;

import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneEvent;
import com.spirit.koil.api.design.sprite.core.SceneEventBus;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Chunked authoritative block grid for Koil's detached scene. */
public final class BlockGrid {
    public record Entry(SceneCellPos position, BlockCell cell) { }
    private record ChunkKey(int chunkX, int chunkY, int depth) { }

    private final Map<ChunkKey, SceneChunk> chunks = new LinkedHashMap<>();
    private final SceneEventBus events;
    private final LongSupplier gameTick;
    private int blockCount;
    private long revision;
    private long cachedEntriesRevision = Long.MIN_VALUE;
    private List<Entry> cachedEntries = List.of();

    public BlockGrid(SceneEventBus events, LongSupplier gameTick) {
        this.events = events;
        this.gameTick = gameTick;
    }

    public BlockCell get(SceneCellPos pos) {
        if (pos == null) return null;
        SceneChunk chunk = chunks.get(key(pos));
        return chunk == null ? null : chunk.get(pos.x(), pos.y());
    }

    public BlockState getBlockState(SceneCellPos pos) {
        BlockCell cell = get(pos);
        return cell == null || cell.blockState() == null ? Blocks.AIR.getDefaultState() : cell.blockState();
    }

    public BlockCell set(SceneCellPos pos, BlockState state) {
        BlockCell written = setInternal(pos, state, BlockCell.Authority.SCENE, -1L, false);
        // A direct scene placement is a new lifetime owner. Scene-gameplay transformations
        // use setSceneOwned instead so authored legacy lifetime provenance is preserved.
        if (written != null) written.clearLifetimeSource();
        return written;
    }

    /**
     * Scene-gameplay takeover. If the cell originated as a legacy proxy, the old
     * facade is synchronously told to mirror/remove itself so it cannot overwrite
     * the new authoritative BlockState on the next compatibility sync.
     */
    public BlockCell setSceneOwned(SceneCellPos pos, BlockState state) {
        BlockCell current = get(pos);
        long legacySource = current == null ? -1L : current.lifetimeSourceId();
        if (state == null || state.isAir()) return removeSceneOwned(pos);
        BlockCell written = setInternal(pos, state, BlockCell.Authority.SCENE, -1L, false);
        if (legacySource >= 0L && events != null) {
            events.publish(new SceneEvent.LegacyProxyWriteback(legacySource, pos, state, false, gameTick.getAsLong()));
        }
        return written;
    }

    /** Updates BlockState without changing whichever runtime currently owns the cell. */
    public BlockCell updateState(SceneCellPos pos, BlockState state) {
        BlockCell current = get(pos);
        if (current == null) return set(pos, state);
        return setInternal(pos, state, current.authority(), current.sourceId(), false);
    }

    /** Compatibility-only write. It can never overwrite a scene-owned cell. */
    public BlockCell setLegacyProxy(SceneCellPos pos, BlockState state, long sourceId) {
        return setInternal(pos, state, BlockCell.Authority.LEGACY_PROXY, sourceId, true);
    }

    private BlockCell setInternal(SceneCellPos pos, BlockState state, BlockCell.Authority authority,
                                      long sourceId, boolean protectSceneAuthority) {
        if (pos == null) return null;
        if (state == null || state.isAir()) {
            if (authority == BlockCell.Authority.LEGACY_PROXY) return removeLegacyProxy(pos, sourceId);
            return remove(pos);
        }
        ChunkKey key = key(pos);
        SceneChunk chunk = chunks.computeIfAbsent(key, ignored -> new SceneChunk());
        BlockCell previous = chunk.get(pos.x(), pos.y());
        if (protectSceneAuthority && previous != null && previous.authority() == BlockCell.Authority.SCENE) {
            return previous;
        }
        BlockState previousState = previous == null ? Blocks.AIR.getDefaultState() : previous.blockState();
        BlockCell next = previous == null ? new BlockCell(state, authority, sourceId, gameTick.getAsLong()) : previous;
        boolean changed = previous == null || !previousState.equals(state)
                || previous.authority() != authority || previous.sourceId() != sourceId;
        next.setAuthority(authority, sourceId);
        next.setBlockState(state);
        chunk.set(pos.x(), pos.y(), next);
        if (changed) revision++;
        if (previous == null || previousState == null || previousState.isAir()) {
            blockCount++;
            if (events != null) events.publish(new SceneEvent.BlockPlaced(pos, state, gameTick.getAsLong()));
        } else if (!previousState.equals(state) && events != null) {
            events.publish(new SceneEvent.BlockStateChanged(pos, previousState, state, gameTick.getAsLong()));
        }
        return next;
    }

    public BlockCell removeLegacyProxy(SceneCellPos pos, long sourceId) {
        BlockCell cell = get(pos);
        if (cell == null || cell.authority() != BlockCell.Authority.LEGACY_PROXY || cell.sourceId() != sourceId) return cell;
        return remove(pos);
    }


    /**
     * Removes a cell when an authored legacy sprite expires, regardless of whether
     * gameplay has promoted BlockState authority to the scene in the meantime.
     * Authority and lifetime provenance are intentionally separate concerns.
     */
    public BlockCell removeLifetimeSource(SceneCellPos pos, long sourceId) {
        BlockCell cell = get(pos);
        if (cell == null || sourceId < 0L || cell.lifetimeSourceId() != sourceId) return cell;
        return remove(pos);
    }

    public SceneCellPos findLifetimeSource(long sourceId) {
        if (sourceId < 0L) return null;
        for (Entry entry : entries()) {
            BlockCell cell = entry.cell();
            if (cell != null && cell.lifetimeSourceId() == sourceId) return entry.position();
        }
        return null;
    }

    /**
     * Removes gameplay-owned state and retires any legacy visual/input proxy whose
     * authored lifetime originally created the cell. This prevents a destroyed or
     * consumed scene block from being resurrected by the compatibility sync.
     */
    public BlockCell removeSceneOwned(SceneCellPos pos) {
        BlockCell current = get(pos);
        long legacySource = current == null ? -1L : current.lifetimeSourceId();
        BlockCell removed = remove(pos);
        if (removed != null && legacySource >= 0L && events != null) {
            events.publish(new SceneEvent.LegacyProxyWriteback(legacySource, pos, Blocks.AIR.getDefaultState(),
                    true, gameTick.getAsLong()));
        }
        return removed;
    }

    public BlockCell remove(SceneCellPos pos) {
        if (pos == null) return null;
        ChunkKey key = key(pos);
        SceneChunk chunk = chunks.get(key);
        if (chunk == null) return null;
        BlockCell previous = chunk.get(pos.x(), pos.y());
        if (previous == null) return null;
        chunk.set(pos.x(), pos.y(), null);
        blockCount = Math.max(0, blockCount - 1);
        revision++;
        if (events != null) events.publish(new SceneEvent.BlockRemoved(pos, previous.blockState(), gameTick.getAsLong()));
        if (chunk.isEmpty()) chunks.remove(key);
        return previous;
    }

    /** Updates scene-authoring/runtime metadata and bumps the block revision so dependent caches rebuild. */
    public boolean putRuntimeData(SceneCellPos pos, String key, String value) {
        BlockCell cell = get(pos);
        if (cell == null || key == null || key.isBlank()) return false;
        String before = cell.runtimeData().get(key.trim().toLowerCase(java.util.Locale.ROOT));
        cell.putRuntimeData(key, value);
        String after = cell.runtimeData().get(key.trim().toLowerCase(java.util.Locale.ROOT));
        if (!java.util.Objects.equals(before, after)) revision++;
        return true;
    }

    public boolean putBlockEntityData(SceneCellPos pos, String key, String value) {
        BlockCell cell = get(pos);
        if (cell == null || key == null || key.isBlank()) return false;
        String before = cell.blockEntityData().get(key);
        cell.putBlockEntityData(key, value);
        String after = cell.blockEntityData().get(key);
        if (!java.util.Objects.equals(before, after)) revision++;
        return true;
    }

    public boolean setFlags(SceneCellPos pos, int flags) {
        BlockCell cell = get(pos);
        if (cell == null) return false;
        if (cell.flags() != flags) { cell.setFlags(flags); revision++; }
        return true;
    }

    public void restartLifetime(SceneCellPos pos) {
        BlockCell cell = get(pos);
        if (cell != null) cell.setCreatedGameTick(gameTick.getAsLong());
    }

    public boolean occupied(SceneCellPos pos) { return !getBlockState(pos).isAir(); }
    public int blockCount() { return blockCount; }
    public int chunkCount() { return chunks.size(); }
    public long revision() { return revision; }

    public List<Entry> entries() {
        if (cachedEntriesRevision == revision) return cachedEntries;
        List<Entry> result = new ArrayList<>(blockCount);
        for (Map.Entry<ChunkKey, SceneChunk> chunkEntry : chunks.entrySet()) {
            ChunkKey key = chunkEntry.getKey();
            SceneChunk chunk = chunkEntry.getValue();
            int baseX = key.chunkX * SceneChunk.SIZE;
            int baseY = key.chunkY * SceneChunk.SIZE;
            for (int localY = 0; localY < SceneChunk.SIZE; localY++) {
                for (int localX = 0; localX < SceneChunk.SIZE; localX++) {
                    BlockCell cell = chunk.get(localX, localY);
                    if (cell != null && !cell.isAir()) {
                        result.add(new Entry(new SceneCellPos(baseX + localX, baseY + localY, key.depth), cell));
                    }
                }
            }
        }
        cachedEntries = List.copyOf(result);
        cachedEntriesRevision = revision;
        return cachedEntries;
    }

    public Collection<SceneChunk> chunks() { return List.copyOf(chunks.values()); }

    public SceneCellPos findLegacyProxy(long sourceId) {
        for (Entry entry : entries()) {
            BlockCell cell = entry.cell();
            if (cell != null && cell.authority() == BlockCell.Authority.LEGACY_PROXY
                    && cell.sourceId() == sourceId) return entry.position();
        }
        return null;
    }

    public void clear() {
        if (!chunks.isEmpty() || blockCount != 0) revision++;
        chunks.clear();
        blockCount = 0;
    }

    private static ChunkKey key(SceneCellPos pos) {
        return new ChunkKey(Math.floorDiv(pos.x(), SceneChunk.SIZE), Math.floorDiv(pos.y(), SceneChunk.SIZE), pos.depth());
    }
}
