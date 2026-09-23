package com.spirit.koil.api.design.sprite.systems;

import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.minecraft.MinecraftStateCodec;
import com.spirit.koil.api.design.sprite.world.BlockCell;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.PistonHeadBlock;
import net.minecraft.block.PitcherCropBlock;
import net.minecraft.block.enums.PistonType;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Maintains scene-native multi-cell Minecraft structures.
 *
 * <p>Vertical two-cell blocks are represented as two real {@link BlockState}
 * cells. This is deliberately state-driven rather than a texture workaround:
 * Minecraft's baked model selects the lower/top textures from the half property,
 * so Koil must materialize the missing cell before rendering.</p>
 *
 * <p>The standard {@link Properties#DOUBLE_BLOCK_HALF} path covers vanilla doors,
 * tall grass/ferns, tall flowers, small dripleaf, tall seagrass and other
 * {@code TallPlantBlock} families. A conservative generic fallback also accepts
 * modded state properties that can parse both {@code lower} and {@code upper},
 * allowing conventional modded two-block-tall blocks to work without registry-id
 * heuristics. Only cells created by this system are ever automatically removed.</p>
 */
public final class MultipartBlockSystem {
    /**
     * Field names are retained for diagnostics compatibility. "Door" counters now
     * include every derived vertical lower/upper pair, not doors alone.
     */
    public record Statistics(long passes, long createdDoorHalves, long synchronizedDoorHalves,
                             long removedOrphans, int derivedDoorHalves) { }

    private static final int PITCHER_DOUBLE_TALL_AGE = 3;

    private final BlockGrid blocks;
    private final SceneProjection projection;
    private record DerivedVerticalHalf(SceneCellPos lowerPos, Block block, String halfProperty) { }

    private final Map<SceneCellPos, DerivedVerticalHalf> derivedUpperToLower = new HashMap<>();
    private final Map<SceneCellPos, SceneCellPos> derivedPistonHeadToBase = new HashMap<>();
    private long observedRevision = Long.MIN_VALUE;
    private long observedInteractionRevision = Long.MIN_VALUE;
    private long passes;
    private long createdDoorHalves;
    private long synchronizedDoorHalves;
    private long removedOrphans;

    public MultipartBlockSystem(BlockGrid blocks, SceneProjection projection) {
        this.blocks = blocks;
        this.projection = projection;
    }

    public void reconcile() {
        long interactionRevision = projection == null ? 0L : projection.interactionRevision();
        if (blocks == null || (blocks.revision() == observedRevision
                && interactionRevision == observedInteractionRevision)) return;
        passes++;

        cleanupDerivedVerticalHalves();
        cleanupDerivedPistonHeads();

        // Snapshot because materializing an upper cell mutates BlockGrid.
        for (BlockGrid.Entry entry : new ArrayList<>(blocks.entries())) {
            SceneCellPos lowerPos = entry.position();
            BlockCell lowerCell = entry.cell();
            BlockState lower = lowerCell == null ? null : lowerCell.blockState();
            if (!isMaterializableLowerHalf(lower)) continue;
            synchronizeVerticalLower(lowerPos, lower, true);
        }

        // Extended pistons have a real piston-head block in the cell in front of
        // the base. Keeping that head as a derived BlockState means rendering,
        // collision and orientation all see the same native Minecraft model.
        for (BlockGrid.Entry entry : new ArrayList<>(blocks.entries())) {
            SceneCellPos basePos = entry.position();
            BlockState base = entry.cell() == null ? null : entry.cell().blockState();
            if (!isExtendedPiston(base)) continue;
            Direction facing = base.get(PistonBlock.FACING);
            SceneCellPos headPos = offset(basePos, facing);
            if (headPos == null) continue;
            BlockState desired = pistonHeadFromBase(base);
            BlockState existing = blocks.getBlockState(headPos);

            if (existing == null || existing.isAir()) {
                blocks.set(headPos, desired);
                derivedPistonHeadToBase.put(headPos, basePos);
            } else if (existing.isOf(Blocks.PISTON_HEAD)) {
                if (!existing.equals(desired)) blocks.updateState(headPos, desired);
                derivedPistonHeadToBase.put(headPos, basePos);
            }
            // A non-head occupant is preserved. The piston gameplay system owns
            // pushing that object; this structural system never deletes it.
        }

        observedRevision = blocks.revision();
        observedInteractionRevision = projection == null ? 0L : projection.interactionRevision();
    }

    /**
     * Redirects an upper vertical half to its lower authoritative anchor.
     * Explicit (non-derived) upper halves are recognized too when a matching lower
     * cell exists, so interaction semantics are consistent for native/modded pairs.
     */
    public SceneCellPos anchor(SceneCellPos pos) {
        if (pos == null) return null;
        BlockState upper = blocks.getBlockState(pos);
        Property<?> half = verticalHalfProperty(upper);
        if (half == null || !isHalfValue(upper, half, "upper")) return pos;

        SceneCellPos lowerPos = offset(pos, Direction.DOWN);
        if (lowerPos == null) return pos;
        BlockState lower = blocks.getBlockState(lowerPos);
        Property<?> lowerHalf = verticalHalfProperty(lower);
        if (lowerHalf != null && lower != null && upper != null
                && lower.getBlock() == upper.getBlock()
                && lowerHalf.getName().equals(half.getName())
                && isHalfValue(lower, lowerHalf, "lower")) {
            return lowerPos;
        }
        return pos;
    }

    /** Immediately synchronizes a lower door's current state into its upper half. */
    public void synchronizeDoorNow(SceneCellPos anyDoorCell) {
        SceneCellPos lowerPos = anchor(anyDoorCell);
        if (lowerPos == null) return;
        BlockState lower = blocks.getBlockState(lowerPos);
        if (lower == null || !(lower.getBlock() instanceof DoorBlock)
                || !isMaterializableLowerHalf(lower)) return;
        synchronizeVerticalLower(lowerPos, lower, true);
        observedRevision = Long.MIN_VALUE;
    }

    public Statistics statistics() {
        return new Statistics(passes, createdDoorHalves, synchronizedDoorHalves, removedOrphans,
                derivedUpperToLower.size());
    }

    public void reset() {
        derivedUpperToLower.clear();
        derivedPistonHeadToBase.clear();
        observedRevision = Long.MIN_VALUE;
        observedInteractionRevision = Long.MIN_VALUE;
        passes = 0L;
        createdDoorHalves = 0L;
        synchronizedDoorHalves = 0L;
        removedOrphans = 0L;
    }

    private void cleanupDerivedVerticalHalves() {
        for (Map.Entry<SceneCellPos, DerivedVerticalHalf> entry : new ArrayList<>(derivedUpperToLower.entrySet())) {
            SceneCellPos upperPos = entry.getKey();
            DerivedVerticalHalf ownership = entry.getValue();
            SceneCellPos lowerPos = ownership.lowerPos();
            BlockState upper = blocks.getBlockState(upperPos);
            BlockState lower = blocks.getBlockState(lowerPos);
            SceneCellPos expectedUpper = offset(lowerPos, Direction.UP);
            if (expectedUpper != null && expectedUpper.equals(upperPos)
                    && isMatchingVerticalPair(lower, upper)) {
                continue;
            }

            // Never delete an arbitrary replacement block. We only remove a cell
            // that still looks like the upper half Koil itself had materialized.
            Property<?> upperHalf = verticalHalfProperty(upper);
            if (upper != null && upper.getBlock() == ownership.block()
                    && upperHalf != null && upperHalf.getName().equals(ownership.halfProperty())
                    && isHalfValue(upper, upperHalf, "upper")) {
                blocks.remove(upperPos);
            }
            derivedUpperToLower.remove(upperPos);
            removedOrphans++;
        }
    }

    private void cleanupDerivedPistonHeads() {
        for (Map.Entry<SceneCellPos, SceneCellPos> entry : new ArrayList<>(derivedPistonHeadToBase.entrySet())) {
            SceneCellPos headPos = entry.getKey();
            SceneCellPos basePos = entry.getValue();
            BlockState base = blocks.getBlockState(basePos);
            BlockState head = blocks.getBlockState(headPos);
            if (!isExtendedPiston(base) || !isMatchingPistonHead(base, head, basePos, headPos)) {
                if (head != null && head.isOf(Blocks.PISTON_HEAD)) blocks.remove(headPos);
                derivedPistonHeadToBase.remove(headPos);
                removedOrphans++;
            }
        }
    }

    private void synchronizeVerticalLower(SceneCellPos lowerPos, BlockState lower, boolean claimNewCell) {
        Property<?> half = verticalHalfProperty(lower);
        if (half == null || !isHalfValue(lower, half, "lower") || !shouldMaterializeLower(lower)) return;

        SceneCellPos upperPos = offset(lowerPos, Direction.UP);
        if (upperPos == null) return;
        BlockState desiredUpper = withHalfValue(lower, half, "upper");
        if (desiredUpper == null || desiredUpper.equals(lower)) return;
        BlockState existingUpper = blocks.getBlockState(upperPos);

        if (existingUpper == null || existingUpper.isAir()) {
            blocks.set(upperPos, desiredUpper);
            if (claimNewCell) {
                derivedUpperToLower.put(upperPos, new DerivedVerticalHalf(lowerPos, lower.getBlock(), half.getName()));
            }
            createdDoorHalves++;
            return;
        }

        Property<?> existingHalf = verticalHalfProperty(existingUpper);
        if (existingUpper.getBlock() == lower.getBlock()
                && existingHalf != null
                && existingHalf.getName().equals(half.getName())
                && isHalfValue(existingUpper, existingHalf, "upper")) {
            if (!existingUpper.equals(desiredUpper)) {
                blocks.updateState(upperPos, desiredUpper);
                synchronizedDoorHalves++;
            }
            // Preserve ownership only if Koil created this upper half previously.
            if (derivedUpperToLower.containsKey(upperPos)) {
                derivedUpperToLower.put(upperPos, new DerivedVerticalHalf(lowerPos, lower.getBlock(), half.getName()));
            }
        }
        // Occupied by another block: preserve authoritative scene state. Native
        // placement would likewise fail when the upper cell is obstructed.
    }

    private static boolean isExtendedPiston(BlockState state) {
        return state != null && state.getBlock() instanceof PistonBlock
                && state.contains(PistonBlock.EXTENDED) && state.get(PistonBlock.EXTENDED)
                && state.contains(PistonBlock.FACING);
    }

    private SceneCellPos offset(SceneCellPos pos, Direction direction) {
        if (pos == null || direction == null) return null;
        return projection == null ? new SceneCellPos(pos.x() + direction.getOffsetX(),
                pos.y() + direction.getOffsetY(), pos.depth() + direction.getOffsetZ())
                : projection.interactionOffset(pos, direction);
    }

    private static BlockState pistonHeadFromBase(BlockState base) {
        Direction facing = base.get(PistonBlock.FACING);
        PistonType type = base.isOf(Blocks.STICKY_PISTON) ? PistonType.STICKY : PistonType.DEFAULT;
        return Blocks.PISTON_HEAD.getDefaultState()
                .with(PistonHeadBlock.FACING, facing)
                .with(PistonHeadBlock.TYPE, type)
                .with(PistonHeadBlock.SHORT, false);
    }

    private boolean isMatchingPistonHead(BlockState base, BlockState head,
                                         SceneCellPos basePos, SceneCellPos headPos) {
        if (!isExtendedPiston(base) || head == null || !head.isOf(Blocks.PISTON_HEAD)) return false;
        Direction facing = base.get(PistonBlock.FACING);
        SceneCellPos expectedHead = offset(basePos, facing);
        if (expectedHead == null || !expectedHead.equals(headPos)) return false;
        if (!head.contains(PistonHeadBlock.FACING) || head.get(PistonHeadBlock.FACING) != facing) return false;
        PistonType expected = base.isOf(Blocks.STICKY_PISTON) ? PistonType.STICKY : PistonType.DEFAULT;
        return head.contains(PistonHeadBlock.TYPE) && head.get(PistonHeadBlock.TYPE) == expected;
    }

    private static boolean isMaterializableLowerHalf(BlockState state) {
        Property<?> half = verticalHalfProperty(state);
        return half != null && isHalfValue(state, half, "lower") && shouldMaterializeLower(state);
    }

    private static boolean isMatchingVerticalPair(BlockState lower, BlockState upper) {
        if (!isMaterializableLowerHalf(lower) || upper == null || lower.getBlock() != upper.getBlock()) return false;
        Property<?> lowerHalf = verticalHalfProperty(lower);
        Property<?> upperHalf = verticalHalfProperty(upper);
        return lowerHalf != null && upperHalf != null
                && lowerHalf.getName().equals(upperHalf.getName())
                && isHalfValue(upper, upperHalf, "upper");
    }

    /**
     * Pitcher crop is the important vanilla exception to "half=lower means create
     * upper now": ages 0-2 are intentionally one block tall and become double tall
     * starting at age 3. Other native TallPlantBlock families are immediately two
     * blocks tall. Modded lower/upper blocks follow their authored property directly.
     */
    private static boolean shouldMaterializeLower(BlockState state) {
        if (state == null) return false;
        if (!(state.getBlock() instanceof PitcherCropBlock)) return true;
        return integerProperty(state, "age", 0) >= PITCHER_DOUBLE_TALL_AGE;
    }

    private static int integerProperty(BlockState state, String name, int fallback) {
        if (state == null || name == null) return fallback;
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
            if (!entry.getKey().getName().equalsIgnoreCase(name)) continue;
            try { return Integer.parseInt(String.valueOf(entry.getValue())); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }

    /**
     * Finds Minecraft's standard lower/upper half property first, then a modded
     * equivalent whose parser explicitly accepts both serialized values. This is
     * based on state capability rather than block registry-name guessing.
     */
    private static Property<?> verticalHalfProperty(BlockState state) {
        if (state == null) return null;
        if (state.contains(Properties.DOUBLE_BLOCK_HALF)) return Properties.DOUBLE_BLOCK_HALF;
        for (Property<?> property : state.getProperties()) {
            // Stairs/trapdoors use top/bottom, not lower/upper, but keep the guard
            // explicit so a nonstandard alias cannot accidentally become multipart.
            if (property == Properties.BLOCK_HALF) continue;
            try {
                if (property.parse("lower").isPresent() && property.parse("upper").isPresent()) return property;
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    private static boolean isHalfValue(BlockState state, Property<?> half, String serializedValue) {
        if (state == null || half == null || serializedValue == null) return false;
        BlockState normalized = MinecraftStateCodec.withProperty(state, half.getName(), serializedValue);
        return normalized != null && normalized.equals(state);
    }

    private static BlockState withHalfValue(BlockState state, Property<?> half, String serializedValue) {
        if (state == null || half == null || serializedValue == null) return state;
        return MinecraftStateCodec.withProperty(state, half.getName(), serializedValue);
    }
}
