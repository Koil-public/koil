package com.spirit.koil.api.design.sprite.gameplay;

import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneEvent;
import com.spirit.koil.api.design.sprite.core.SceneEventBus;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Detached chest pairing + lid-animation state.
 *
 * <p>Normal and trapped chests pair with one same-block neighbor in the visible
 * scene plane. Ender chests deliberately remain single. Animation state is runtime
 * scene state, not a fake BlockState property, matching Minecraft's block-entity
 * separation between chest type/facing and lid progress.</p>
 */
public final class ChestSystem {
    private static final float LID_STEP_PER_TICK = 0.10F;

    public record Pair(SceneCellPos first, SceneCellPos second) {
        public boolean contains(SceneCellPos pos) { return first.equals(pos) || second.equals(pos); }
        public SceneCellPos other(SceneCellPos pos) { return first.equals(pos) ? second : first; }
    }

    private static final class LidState {
        float previous;
        float current;
        boolean open;
    }

    private final BlockGrid blocks;
    private final SceneProjection projection;
    private final SceneEventBus events;
    private final LongSupplier gameTick;
    private final Map<SceneCellPos, Pair> pairByCell = new HashMap<>();
    private final Map<SceneCellPos, LidState> lids = new HashMap<>();
    private long observedRevision = Long.MIN_VALUE;

    public ChestSystem(BlockGrid blocks, SceneProjection projection,
                           SceneEventBus events, LongSupplier gameTick) {
        this.blocks = blocks;
        this.projection = projection;
        this.events = events;
        this.gameTick = gameTick;
    }

    /** Rebuilds chest pairing only when scene block state changed. */
    public void reconcilePairs() {
        if (blocks == null || projection == null || blocks.revision() == observedRevision) return;
        List<BlockGrid.Entry> chests = new ArrayList<>();
        for (BlockGrid.Entry entry : blocks.entries()) {
            BlockState state = entry.cell().blockState();
            if (state != null && state.getBlock() instanceof ChestBlock) chests.add(entry);
        }
        chests.sort(Comparator.comparingInt((BlockGrid.Entry e) -> e.position().depth())
                .thenComparingInt(e -> e.position().y())
                .thenComparingInt(e -> e.position().x()));

        // First clear stale pair properties. EnderChestBlock never reaches this list.
        for (BlockGrid.Entry entry : chests) {
            BlockState state = entry.cell().blockState();
            if (state.contains(Properties.CHEST_TYPE) && state.get(Properties.CHEST_TYPE) != ChestType.SINGLE) {
                blocks.updateState(entry.position(), state.with(Properties.CHEST_TYPE, ChestType.SINGLE));
            }
        }

        pairByCell.clear();
        Set<SceneCellPos> claimed = new HashSet<>();
        Direction right = projection.screenRightDirection();
        for (BlockGrid.Entry entry : chests) {
            SceneCellPos a = entry.position();
            if (claimed.contains(a)) continue;
            BlockState aState = blocks.getBlockState(a);
            SceneCellPos b = projection.offset(a, right);
            BlockState bState = blocks.getBlockState(b);
            if (!canPair(aState, bState) || claimed.contains(b)) continue;

            ChestType aType = pairType(aState, right);
            ChestType bType = aType == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT;
            blocks.updateState(a, aState.with(Properties.CHEST_TYPE, aType));
            blocks.updateState(b, bState.with(Properties.CHEST_TYPE, bType));
            Pair pair = new Pair(a, b);
            pairByCell.put(a, pair);
            pairByCell.put(b, pair);
            claimed.add(a);
            claimed.add(b);
        }

        // Preserve lid animation by canonical pair anchor when pairing changes.
        Map<SceneCellPos, LidState> migrated = new HashMap<>();
        for (BlockGrid.Entry entry : chests) {
            SceneCellPos pos = entry.position();
            SceneCellPos key = anchor(pos);
            LidState existing = lids.get(pos);
            if (existing == null) existing = lids.get(key);
            if (existing != null) migrated.putIfAbsent(key, existing);
        }
        // Ender chests are not in the ChestBlock list, but can still animate singly.
        for (Map.Entry<SceneCellPos, LidState> entry : lids.entrySet()) {
            BlockState state = blocks.getBlockState(entry.getKey());
            if (state.getBlock() instanceof EnderChestBlock) migrated.put(entry.getKey(), entry.getValue());
        }
        lids.clear();
        lids.putAll(migrated);
        observedRevision = blocks.revision();
    }

    public void minecraftTick() {
        reconcilePairs();
        for (LidState state : lids.values()) {
            state.previous = state.current;
            float target = state.open ? 1.0F : 0.0F;
            if (state.current < target) state.current = Math.min(target, state.current + LID_STEP_PER_TICK);
            else if (state.current > target) state.current = Math.max(target, state.current - LID_STEP_PER_TICK);
        }
    }

    public boolean toggle(SceneCellPos requested) {
        if (requested == null) return false;
        reconcilePairs();
        BlockState state = blocks.getBlockState(requested);
        if (!(state.getBlock() instanceof ChestBlock) && !(state.getBlock() instanceof EnderChestBlock)) return false;
        SceneCellPos key = anchor(requested);
        LidState lid = lids.computeIfAbsent(key, ignored -> new LidState());
        lid.open = !lid.open;
        if (events != null) {
            boolean ender = state.getBlock() instanceof EnderChestBlock;
            events.publish(new SceneEvent.SoundRequested(
                    ender
                            ? (lid.open ? SoundEvents.BLOCK_ENDER_CHEST_OPEN : SoundEvents.BLOCK_ENDER_CHEST_CLOSE)
                            : (lid.open ? SoundEvents.BLOCK_CHEST_OPEN : SoundEvents.BLOCK_CHEST_CLOSE),
                    0.35F, lid.open ? 1.0F : 0.95F, gameTick.getAsLong()));
            events.publish(new SceneEvent.BlockInteraction(requested, "chest_" + (lid.open ? "open" : "close"), gameTick.getAsLong()));
        }
        return true;
    }

    public float lidProgress(SceneCellPos pos, float renderAlpha) {
        if (pos == null) return 0.0F;
        LidState state = lids.get(anchor(pos));
        if (state == null) return 0.0F;
        float alpha = Math.max(0.0F, Math.min(1.0F, renderAlpha));
        return state.previous + (state.current - state.previous) * alpha;
    }

    public boolean isOpen(SceneCellPos pos) {
        LidState state = lids.get(anchor(pos));
        return state != null && state.open;
    }

    public Pair pair(SceneCellPos pos) { return pos == null ? null : pairByCell.get(pos); }

    public SceneCellPos anchor(SceneCellPos pos) {
        Pair pair = pairByCell.get(pos);
        if (pair == null) return pos;
        float ax = projection.cellCenterScreenX(pair.first());
        float bx = projection.cellCenterScreenX(pair.second());
        return ax <= bx ? pair.first() : pair.second();
    }

    public void reset() {
        pairByCell.clear();
        lids.clear();
        observedRevision = Long.MIN_VALUE;
    }

    private static boolean canPair(BlockState a, BlockState b) {
        if (a == null || b == null || a.isAir() || b.isAir()) return false;
        if (!(a.getBlock() instanceof ChestBlock) || !(b.getBlock() instanceof ChestBlock)) return false;
        if (a.getBlock() != b.getBlock()) return false; // normal/trapped/mod family must match exactly
        if (!a.contains(Properties.CHEST_TYPE) || !b.contains(Properties.CHEST_TYPE)) return false;
        if (a.contains(Properties.HORIZONTAL_FACING) && b.contains(Properties.HORIZONTAL_FACING)
                && a.get(Properties.HORIZONTAL_FACING) != b.get(Properties.HORIZONTAL_FACING)) return false;
        return true;
    }

    private static ChestType pairType(BlockState state, Direction neighborDirection) {
        if (state == null || !state.contains(Properties.HORIZONTAL_FACING)) return ChestType.LEFT;
        Direction facing = state.get(Properties.HORIZONTAL_FACING);
        return neighborDirection == facing.rotateYClockwise() ? ChestType.LEFT : ChestType.RIGHT;
    }
}
