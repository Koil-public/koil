package com.spirit.koil.api.design.sprite.systems;

import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.SlabType;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;

/**
 * Authoritative scroll-orientation controller for static scene blocks.
 *
 * <p>This changes BlockState, never the old particle's visual rotation. Native
 * baked models, collision and neighbor-derived state therefore all observe the
 * same orientation. Wheel steps cycle every placement-relevant orientation for
 * common 2D-world blocks, including top/bottom stairs.</p>
 */
public final class BlockOrientationSystem {
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };
    private static final Direction[] SIX_WAY = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST,
            Direction.UP, Direction.DOWN
    };

    private final BlockGrid blocks;
    private final MultipartBlockSystem multipart;

    public BlockOrientationSystem(BlockGrid blocks, MultipartBlockSystem multipart) {
        this.blocks = blocks;
        this.multipart = multipart;
    }

    /** Returns true when at least one authoritative BlockState property changed. */
    public boolean cycle(SceneCellPos requestedPos, int steps) {
        if (requestedPos == null || steps == 0) return false;
        SceneCellPos pos = multipart == null ? requestedPos : multipart.anchor(requestedPos);
        if (pos == null) return false;
        BlockState original = blocks.getBlockState(pos);
        if (original == null || original.isAir()) return false;

        BlockState state = original;
        int count = Math.max(1, Math.min(32, Math.abs(steps)));
        int direction = steps > 0 ? 1 : -1;
        for (int i = 0; i < count; i++) state = step(state, direction);
        if (state == null || state.equals(original)) return false;

        blocks.updateState(pos, state);
        if (state.getBlock() instanceof DoorBlock && multipart != null) multipart.synchronizeDoorNow(pos);
        return true;
    }

    /**
     * Stateless authoring preview used by the playground ghost. This applies the
     * exact same native BlockState orientation cycle as a placed scene block,
     * without temporarily inserting anything into the authoritative grid.
     */
    public static BlockState cycleState(BlockState state, int steps) {
        if (state == null || state.isAir() || steps == 0) return state;
        int count = Math.max(1, Math.min(32, Math.abs(steps)));
        int direction = steps > 0 ? 1 : -1;
        BlockState out = state;
        for (int i = 0; i < count; i++) out = step(out, direction);
        return out;
    }

    private static BlockState step(BlockState state, int direction) {
        if (state == null || state.isAir()) return state;

        // Stairs expose all eight 2D-authoring orientations: four horizontal
        // facings for the bottom half, then the same four upside-down/top.
        if (state.getBlock() instanceof StairsBlock
                && state.contains(Properties.HORIZONTAL_FACING)
                && state.contains(Properties.BLOCK_HALF)) {
            Direction facing = state.get(Properties.HORIZONTAL_FACING);
            BlockHalf half = state.get(Properties.BLOCK_HALF);
            int facingIndex = indexOf(HORIZONTAL, facing);
            int index = (half == BlockHalf.TOP ? 4 : 0) + Math.max(0, facingIndex);
            index = floorMod(index + direction, 8);
            BlockState out = state.with(Properties.HORIZONTAL_FACING, HORIZONTAL[index & 3])
                    .with(Properties.BLOCK_HALF, index >= 4 ? BlockHalf.TOP : BlockHalf.BOTTOM);
            if (out.contains(Properties.STAIR_SHAPE)) {
                out = out.with(Properties.STAIR_SHAPE, net.minecraft.block.enums.StairShape.STRAIGHT);
            }
            return out;
        }

        // Doors are two-cell structures. Scroll cycles facing first, then hinge,
        // while HALF remains structural and OPEN/POWERED remain gameplay state.
        if (state.getBlock() instanceof DoorBlock && state.contains(DoorBlock.FACING)
                && state.contains(DoorBlock.HINGE)) {
            Direction facing = state.get(DoorBlock.FACING);
            DoorHinge hinge = state.get(DoorBlock.HINGE);
            int index = (hinge == DoorHinge.RIGHT ? 4 : 0) + Math.max(0, indexOf(HORIZONTAL, facing));
            index = floorMod(index + direction, 8);
            return state.with(DoorBlock.FACING, HORIZONTAL[index & 3])
                    .with(DoorBlock.HINGE, index >= 4 ? DoorHinge.RIGHT : DoorHinge.LEFT);
        }

        // Trapdoors mirror stair-style orientation: four facings x top/bottom.
        if (state.getBlock() instanceof TrapdoorBlock
                && state.contains(Properties.HORIZONTAL_FACING)
                && state.contains(Properties.BLOCK_HALF)) {
            Direction facing = state.get(Properties.HORIZONTAL_FACING);
            BlockHalf half = state.get(Properties.BLOCK_HALF);
            int index = (half == BlockHalf.TOP ? 4 : 0) + Math.max(0, indexOf(HORIZONTAL, facing));
            index = floorMod(index + direction, 8);
            return state.with(Properties.HORIZONTAL_FACING, HORIZONTAL[index & 3])
                    .with(Properties.BLOCK_HALF, index >= 4 ? BlockHalf.TOP : BlockHalf.BOTTOM);
        }

        if (state.getBlock() instanceof FenceGateBlock && state.contains(Properties.HORIZONTAL_FACING)) {
            return state.with(Properties.HORIZONTAL_FACING,
                    cycle(HORIZONTAL, state.get(Properties.HORIZONTAL_FACING), direction));
        }

        // Generic blocks with full six-way FACING include pistons, observers,
        // dispensers and similar Minecraft-native directional blocks.
        if (state.contains(Properties.FACING)) {
            return state.with(Properties.FACING, cycleAllowedDirection(state, Properties.FACING, direction));
        }

        if (state.contains(Properties.HORIZONTAL_FACING)) {
            return state.with(Properties.HORIZONTAL_FACING,
                    cycle(HORIZONTAL, state.get(Properties.HORIZONTAL_FACING), direction));
        }

        if (state.contains(Properties.HOPPER_FACING)) {
            Direction[] hopper = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN };
            return state.with(Properties.HOPPER_FACING, cycle(hopper, state.get(Properties.HOPPER_FACING), direction));
        }

        if (state.contains(Properties.AXIS)) {
            Direction.Axis[] axes = { Direction.Axis.X, Direction.Axis.Y, Direction.Axis.Z };
            Direction.Axis current = state.get(Properties.AXIS);
            int idx = floorMod(indexOf(axes, current) + direction, axes.length);
            return state.with(Properties.AXIS, axes[idx]);
        }

        if (state.contains(Properties.HORIZONTAL_AXIS)) {
            Direction.Axis[] axes = { Direction.Axis.X, Direction.Axis.Z };
            Direction.Axis current = state.get(Properties.HORIZONTAL_AXIS);
            int idx = floorMod(indexOf(axes, current) + direction, axes.length);
            return state.with(Properties.HORIZONTAL_AXIS, axes[idx]);
        }

        if (state.contains(Properties.SLAB_TYPE)) {
            SlabType current = state.get(Properties.SLAB_TYPE);
            if (current == SlabType.DOUBLE) return state;
            return state.with(Properties.SLAB_TYPE, current == SlabType.TOP ? SlabType.BOTTOM : SlabType.TOP);
        }

        if (state.contains(Properties.BLOCK_HALF)) {
            BlockHalf current = state.get(Properties.BLOCK_HALF);
            return state.with(Properties.BLOCK_HALF, current == BlockHalf.TOP ? BlockHalf.BOTTOM : BlockHalf.TOP);
        }

        return state;
    }

    private static Direction cycleAllowedDirection(BlockState state,
                                                    net.minecraft.state.property.DirectionProperty property,
                                                    int direction) {
        Direction current = state.get(property);
        int start = indexOf(SIX_WAY, current);
        for (int i = 1; i <= SIX_WAY.length; i++) {
            Direction candidate = SIX_WAY[floorMod(start + direction * i, SIX_WAY.length)];
            if (property.getValues().contains(candidate)) return candidate;
        }
        return current;
    }

    private static Direction cycle(Direction[] values, Direction current, int direction) {
        int index = indexOf(values, current);
        if (index < 0) index = 0;
        return values[floorMod(index + direction, values.length)];
    }

    private static int indexOf(Direction[] values, Direction value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return -1;
    }

    private static <T> int indexOf(T[] values, T value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value || values[i].equals(value)) return i;
        return -1;
    }

    private static int floorMod(int value, int modulus) {
        int result = value % modulus;
        return result < 0 ? result + modulus : result;
    }
}
