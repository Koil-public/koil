package com.spirit.koil.api.design.sprite.world;

import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneEvent;
import com.spirit.koil.api.design.sprite.core.SceneEventBus;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Chunked authoritative fluid grid. It is independent from block-cell storage. */
public final class FluidGrid {
    public record Entry(SceneCellPos position, FluidCell cell) { }
    private record ChunkKey(int chunkX, int chunkY, int depth) { }

    private final Map<ChunkKey, FluidChunk> chunks = new LinkedHashMap<>();
    private final SceneEventBus events;
    private final LongSupplier gameTick;
    private int fluidCount;
    private long revision;
    private long cachedEntriesRevision = Long.MIN_VALUE;
    private List<Entry> cachedEntries = List.of();

    public FluidGrid(SceneEventBus events, LongSupplier gameTick) {
        this.events = events;
        this.gameTick = gameTick;
    }

    public FluidCell get(SceneCellPos pos) {
        if (pos == null) return null;
        FluidChunk chunk = chunks.get(key(pos));
        return chunk == null ? null : chunk.get(pos.x(), pos.y());
    }

    public FluidState getFluidState(SceneCellPos pos) {
        FluidCell cell = get(pos);
        return cell == null || cell.state() == null ? Fluids.EMPTY.getDefaultState() : cell.state();
    }

    public FluidCell set(SceneCellPos pos, FluidState state) {
        return setInternal(pos, state, FluidCell.Authority.SCENE, -1L, false);
    }

    public FluidCell setBlockStateFluid(SceneCellPos pos, FluidState state) {
        return setInternal(pos, state, FluidCell.Authority.BLOCK_STATE, -1L, false);
    }

    public FluidCell setLegacySeed(SceneCellPos pos, FluidState state, long sourceId) {
        return setInternal(pos, state, FluidCell.Authority.LEGACY_SEED, sourceId, false);
    }

    private FluidCell setInternal(SceneCellPos pos, FluidState state, FluidCell.Authority authority,
                                  long sourceId, boolean preserveScene) {
        if (pos == null) return null;
        if (state == null || state.isEmpty()) return remove(pos);
        ChunkKey key = key(pos);
        FluidChunk chunk = chunks.computeIfAbsent(key, ignored -> new FluidChunk());
        FluidCell previous = chunk.get(pos.x(), pos.y());
        if (preserveScene && previous != null && previous.authority() == FluidCell.Authority.SCENE) return previous;
        FluidState previousState = previous == null ? Fluids.EMPTY.getDefaultState() : previous.state();
        FluidCell next = previous == null ? new FluidCell(state, authority, sourceId) : previous;
        boolean changed = previous == null || !previousState.equals(state)
                || previous.authority() != authority || previous.sourceId() != sourceId;
        next.setAuthority(authority, sourceId);
        next.setState(state);
        chunk.set(pos.x(), pos.y(), next);
        if (previous == null || previousState.isEmpty()) fluidCount++;
        if (changed) {
            revision++;
            if (events != null) events.publish(new SceneEvent.FluidChanged(pos, previousState, state, gameTick.getAsLong()));
        }
        return next;
    }

    public FluidCell removeLegacySeed(SceneCellPos pos, long sourceId) {
        FluidCell cell = get(pos);
        if (cell == null || cell.authority() != FluidCell.Authority.LEGACY_SEED || cell.sourceId() != sourceId) return cell;
        return remove(pos);
    }

    public FluidCell remove(SceneCellPos pos) {
        if (pos == null) return null;
        ChunkKey key = key(pos);
        FluidChunk chunk = chunks.get(key);
        if (chunk == null) return null;
        FluidCell previous = chunk.get(pos.x(), pos.y());
        if (previous == null) return null;
        chunk.set(pos.x(), pos.y(), null);
        fluidCount = Math.max(0, fluidCount - 1);
        revision++;
        if (events != null) events.publish(new SceneEvent.FluidChanged(pos, previous.state(),
                Fluids.EMPTY.getDefaultState(), gameTick.getAsLong()));
        if (chunk.isEmpty()) chunks.remove(key);
        return previous;
    }

    public List<Entry> entries() {
        if (cachedEntriesRevision == revision) return cachedEntries;
        List<Entry> result = new ArrayList<>(fluidCount);
        for (Map.Entry<ChunkKey, FluidChunk> chunkEntry : chunks.entrySet()) {
            ChunkKey key = chunkEntry.getKey();
            FluidChunk chunk = chunkEntry.getValue();
            int baseX = key.chunkX * FluidChunk.SIZE;
            int baseY = key.chunkY * FluidChunk.SIZE;
            for (int localY = 0; localY < FluidChunk.SIZE; localY++) {
                for (int localX = 0; localX < FluidChunk.SIZE; localX++) {
                    FluidCell cell = chunk.get(localX, localY);
                    if (cell != null && !cell.isEmpty()) {
                        result.add(new Entry(new SceneCellPos(baseX + localX, baseY + localY, key.depth), cell));
                    }
                }
            }
        }
        cachedEntries = List.copyOf(result);
        cachedEntriesRevision = revision;
        return cachedEntries;
    }

    public SceneCellPos findLegacySeed(long sourceId) {
        for (Entry entry : entries()) {
            if (entry.cell().authority() == FluidCell.Authority.LEGACY_SEED
                    && entry.cell().sourceId() == sourceId) return entry.position();
        }
        return null;
    }

    public boolean isLegacySeed(SceneCellPos pos, long sourceId) {
        FluidCell cell = get(pos);
        return cell != null && cell.authority() == FluidCell.Authority.LEGACY_SEED && cell.sourceId() == sourceId;
    }

    public int fluidCount() { return fluidCount; }
    public int chunkCount() { return chunks.size(); }
    public long revision() { return revision; }

    public void clear() {
        if (!chunks.isEmpty()) revision++;
        chunks.clear();
        fluidCount = 0;
    }

    private static ChunkKey key(SceneCellPos pos) {
        return new ChunkKey(Math.floorDiv(pos.x(), FluidChunk.SIZE),
                Math.floorDiv(pos.y(), FluidChunk.SIZE), pos.depth());
    }
}
