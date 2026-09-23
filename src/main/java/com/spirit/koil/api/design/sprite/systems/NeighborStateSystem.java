package com.spirit.koil.api.design.sprite.systems;

import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.minecraft.MinecraftStateCodec;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import com.spirit.koil.api.design.sprite.world.FluidGrid;
import com.spirit.koil.api.design.sprite.world.SceneBlockView;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChorusPlantBlock;
import net.minecraft.block.ConnectingBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.MultifaceGrowthBlock;
import net.minecraft.block.ObserverBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.PointedDripstoneBlock;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.RepeaterBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.enums.StairShape;
import net.minecraft.block.enums.Thickness;
import net.minecraft.block.enums.WallShape;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Detached reconciliation of native Minecraft neighbor-derived BlockState.
 *
 * <p>The resulting state is written back to KoilBlockGrid and is authoritative
 * for both collision and rendering. No renderer-only connection state exists.</p>
 */
public final class NeighborStateSystem {
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    public record Statistics(long passes, long stateChanges, long nativeContractFailures, String lastNativeContractFailure,
                             long lastInputRevision, long lastOutputRevision) { }

    private final BlockGrid blocks;
    private final SceneProjection projection;
    private final SceneBlockView view;
    private long observedRevision = Long.MIN_VALUE;
    private long observedInteractionRevision = Long.MIN_VALUE;
    private long passes;
    private long stateChanges;
    private long nativeContractFailures;
    private String lastNativeContractFailure = "";

    public NeighborStateSystem(BlockGrid blocks, FluidGrid fluids, SceneProjection projection) {
        this.blocks = blocks;
        this.projection = projection;
        this.view = new SceneBlockView(blocks, fluids);
    }

    /** Reconciles until stable because one connection update may change another. */
    public void reconcile() {
        if (blocks == null) return;
        long interactionRevision = projection == null ? 0L : projection.interactionRevision();
        if (blocks.revision() == observedRevision && interactionRevision == observedInteractionRevision) return;

        for (int pass = 0; pass < 8; pass++) {
            passes++;
            List<PendingState> pending = new ArrayList<>();
            for (BlockGrid.Entry entry : blocks.entries()) {
                BlockState before = entry.cell().blockState();
                if (before == null || before.isAir()) continue;
                BlockState after;
                try {
                    after = derive(entry.position(), before);
                } catch (IllegalArgumentException contractFailure) {
                    // Native helpers often assume their BlockState belongs to a
                    // specific block family. One malformed/mismatched state must
                    // never crash Koil's render thread. Record the failure and keep
                    // the previous authoritative state so diagnostics can expose it.
                    nativeContractFailures++;
                    lastNativeContractFailure = before.getBlock() + " @ " + entry.position() + ": "
                            + String.valueOf(contractFailure.getMessage());
                    continue;
                }
                if (after != null && !after.equals(before)) pending.add(new PendingState(entry.position(), after));
            }
            if (pending.isEmpty()) break;
            for (PendingState change : pending) {
                BlockState current = blocks.getBlockState(change.pos());
                if (!current.equals(change.state())) {
                    blocks.updateState(change.pos(), change.state());
                    stateChanges++;
                }
            }
        }
        observedRevision = blocks.revision();
        observedInteractionRevision = projection == null ? 0L : projection.interactionRevision();
    }

    public void reset() {
        observedRevision = Long.MIN_VALUE;
        observedInteractionRevision = Long.MIN_VALUE;
        passes = 0L;
        stateChanges = 0L;
        nativeContractFailures = 0L;
        lastNativeContractFailure = "";
    }

    public Statistics statistics() {
        return new Statistics(passes, stateChanges, nativeContractFailures, lastNativeContractFailure,
                observedRevision, blocks == null ? 0L : blocks.revision());
    }

    private BlockState derive(SceneCellPos pos, BlockState state) {
        Block block = state.getBlock();

        // Vertical model families must be reconciled before ordinary connection
        // blocks because their native texture/model choice is itself neighbor-
        // derived. Repeatedly placing a stem/head block should create the same
        // body/tip silhouette Minecraft would have produced in a world.
        BlockState verticalPlant = deriveVerticalPlantFamily(pos, state);
        if (!verticalPlant.equals(state)) return verticalPlant;
        if (block instanceof PointedDripstoneBlock) return derivePointedDripstone(pos, state);
        if (block instanceof ChorusPlantBlock) return deriveChorusPlant(pos, state);
        if (block instanceof VineBlock) return ensureProjectionVisibleVineFace(state);
        if (block instanceof MultifaceGrowthBlock) return ensureProjectionVisibleMultiface(state);

        if (block instanceof StairsBlock) return deriveStairs(pos, state);
        if (block instanceof RedstoneWireBlock) return deriveRedstone(pos, state);
        if (block instanceof FenceBlock fence) return deriveFence(pos, state, fence);
        if (block instanceof PaneBlock pane) return derivePane(pos, state, pane);
        if (block instanceof WallBlock) return deriveWall(pos, state);
        if (block instanceof FenceGateBlock) return deriveFenceGate(pos, state);
        if (block instanceof AbstractRailBlock) return deriveRail(pos, state);
        return state;
    }

    /**
     * Reconciles vanilla AbstractPlantPartBlock head/body families without needing
     * a WorldAccess. The target block's getStateWithProperties method retains every
     * property shared by the two native block types, including berries, facing and
     * waterlogging where applicable. Registry-name heuristics are intentionally not
     * used, so modded blocks are never silently converted into unrelated states.
     */
    private BlockState deriveVerticalPlantFamily(SceneCellPos pos, BlockState state) {
        PlantFamily family = plantFamily(state);
        if (family == null) return state;
        BlockState growthNeighbor = stateAt(offset(pos, family.growthDirection()));
        boolean continues = family.contains(growthNeighbor);
        Block target = continues ? family.body() : family.head();
        if (state.isOf(target)) return state;
        return target.getStateWithProperties(state);
    }

    private static PlantFamily plantFamily(BlockState state) {
        if (state == null || state.isAir()) return null;
        if (state.isOf(Blocks.TWISTING_VINES) || state.isOf(Blocks.TWISTING_VINES_PLANT)) {
            return new PlantFamily(Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Direction.UP);
        }
        if (state.isOf(Blocks.WEEPING_VINES) || state.isOf(Blocks.WEEPING_VINES_PLANT)) {
            return new PlantFamily(Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT, Direction.DOWN);
        }
        if (state.isOf(Blocks.CAVE_VINES) || state.isOf(Blocks.CAVE_VINES_PLANT)) {
            return new PlantFamily(Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT, Direction.DOWN);
        }
        if (state.isOf(Blocks.KELP) || state.isOf(Blocks.KELP_PLANT)) {
            return new PlantFamily(Blocks.KELP, Blocks.KELP_PLANT, Direction.UP);
        }
        if (state.isOf(Blocks.BIG_DRIPLEAF) || state.isOf(Blocks.BIG_DRIPLEAF_STEM)) {
            return new PlantFamily(Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Direction.UP);
        }
        return null;
    }

    /**
     * Detached equivalent of pointed-dripstone thickness selection. The state
     * itself remains authoritative, allowing Minecraft's normal blockstate/model
     * JSON to select BASE/MIDDLE/FRUSTUM/TIP/TIP_MERGE textures and geometry.
     */
    private BlockState derivePointedDripstone(SceneCellPos pos, BlockState state) {
        if (!state.contains(PointedDripstoneBlock.VERTICAL_DIRECTION)
                || !state.contains(PointedDripstoneBlock.THICKNESS)) return state;
        Direction direction = state.get(PointedDripstoneBlock.VERTICAL_DIRECTION);
        SceneCellPos forwardPos = offset(pos, direction);
        BlockState forward = stateAt(forwardPos);

        Thickness thickness;
        if (isPointedDripstoneFacing(forward, direction.getOpposite())) {
            // Opposing tips meeting in one vertical run use the native merged-tip
            // model. Both cells independently converge to TIP_MERGE.
            thickness = Thickness.TIP_MERGE;
        } else if (!isPointedDripstoneFacing(forward, direction)) {
            thickness = Thickness.TIP;
        } else {
            BlockState twoForward = stateAt(offset(forwardPos, direction));
            if (!isPointedDripstoneFacing(twoForward, direction)) {
                thickness = Thickness.FRUSTUM;
            } else {
                BlockState backward = stateAt(offset(pos, direction.getOpposite()));
                thickness = isPointedDripstoneFacing(backward, direction)
                        ? Thickness.MIDDLE : Thickness.BASE;
            }
        }
        return state.get(PointedDripstoneBlock.THICKNESS) == thickness
                ? state : state.with(PointedDripstoneBlock.THICKNESS, thickness);
    }

    private static boolean isPointedDripstoneFacing(BlockState state, Direction direction) {
        return state != null && state.getBlock() instanceof PointedDripstoneBlock
                && state.contains(PointedDripstoneBlock.VERTICAL_DIRECTION)
                && state.get(PointedDripstoneBlock.VERTICAL_DIRECTION) == direction;
    }

    /** Rebuilds the six native ConnectingBlock flags used by chorus plants. */
    private BlockState deriveChorusPlant(SceneCellPos pos, BlockState state) {
        BlockState out = state;
        for (Direction direction : Direction.values()) {
            BooleanProperty property = ConnectingBlock.FACING_PROPERTIES.get(direction);
            if (property == null || !out.contains(property)) continue;
            BlockState neighbor = stateAt(offset(pos, direction));
            boolean connect = neighbor.isOf(Blocks.CHORUS_PLANT) || neighbor.isOf(Blocks.CHORUS_FLOWER);
            if (direction == Direction.DOWN && neighbor.isOf(Blocks.END_STONE)) connect = true;
            out = out.with(property, connect);
        }
        return out;
    }

    private record PlantFamily(Block head, Block body, Direction growthDirection) {
        boolean contains(BlockState state) {
            return state != null && (state.isOf(head) || state.isOf(body));
        }
    }

    /**
     * Vanilla vines are one-face sheets. A perfectly legitimate EAST/WEST-only
     * vine becomes mathematically edge-on in Koil's default XY projection, while
     * a freshly authored vine can also arrive with every attachment bit false.
     * Preserve all authored attachments and add only the camera-facing native
     * VineBlock property when the state has no visible sheet in this projection.
     * This is an intentional 2D presentation bridge, not a replacement texture.
     */
    private BlockState ensureProjectionVisibleVineFace(BlockState state) {
        Direction visibleFace = projectionVisibleFace();
        BooleanProperty front = VineBlock.getFacingProperty(visibleFace);
        BooleanProperty back = VineBlock.getFacingProperty(visibleFace.getOpposite());
        if (isTrue(state, front) || isTrue(state, back)) return state;
        if (front == null || !state.contains(front)) return state;
        return state.with(front, true);
    }

    /**
     * The same projection problem applies to glow lichen, sculk vein and modded
     * MultifaceGrowthBlock subclasses. Use the block's native directional state
     * property so its normal multipart baked model and resource-pack texture stay
     * authoritative. Existing authored faces are never cleared.
     */
    private BlockState ensureProjectionVisibleMultiface(BlockState state) {
        Direction visibleFace = projectionVisibleFace();
        BooleanProperty front = MultifaceGrowthBlock.getProperty(visibleFace);
        BooleanProperty back = MultifaceGrowthBlock.getProperty(visibleFace.getOpposite());
        if (isTrue(state, front) || isTrue(state, back)) return state;
        if (front != null && state.contains(front)) return state.with(front, true);

        // A modded subclass may expose a restricted direction set. Fall back to
        // the first native direction property it actually owns rather than
        // guessing from registry names or drawing a fake quad.
        for (Direction direction : Direction.values()) {
            BooleanProperty candidate = MultifaceGrowthBlock.getProperty(direction);
            if (candidate != null && state.contains(candidate)) {
                return state.get(candidate) ? state : state.with(candidate, true);
            }
        }
        return state;
    }

    private static boolean isTrue(BlockState state, BooleanProperty property) {
        return state != null && property != null && state.contains(property) && state.get(property);
    }

    private Direction projectionVisibleFace() {
        if (projection == null) return Direction.SOUTH;
        return switch (projection.mode()) {
            case XY_SIDE -> Direction.SOUTH;
            case ZY_SIDE -> Direction.EAST;
            case XZ_TOP -> Direction.UP;
        };
    }

    private BlockState deriveFence(SceneCellPos pos, BlockState state, FenceBlock fence) {
        BlockState out = state;
        for (Direction direction : HORIZONTAL) {
            SceneCellPos neighborPos = offset(pos, direction);
            BlockState neighbor = stateAt(neighborPos);
            boolean full = fullFace(neighbor, neighborPos, direction.getOpposite());
            // Koil side-view bridge: vanilla Java keeps fences and walls as
            // separate connection families, but that produces a conspicuous
            // visual/physical gap in a one-cell-thick 2D scene. Preserve the
            // native fence predicate first, then bridge directly-adjacent walls
            // as an explicit scene-projection extension. This only changes the
            // connection property; the block, texture and collision are still
            // Minecraft's native state/model.
            boolean connect = fence.canConnect(neighbor, full, direction)
                    || neighbor.getBlock() instanceof WallBlock
                    || neighbor.isIn(BlockTags.WALLS);
            out = MinecraftStateCodec.withProperty(out, direction.getName(), String.valueOf(connect));
        }
        return out;
    }

    private BlockState derivePane(SceneCellPos pos, BlockState state, PaneBlock pane) {
        BlockState out = state;
        for (Direction direction : HORIZONTAL) {
            SceneCellPos neighborPos = offset(pos, direction);
            BlockState neighbor = stateAt(neighborPos);
            boolean full = fullFace(neighbor, neighborPos, direction.getOpposite());
            // Use PaneBlock's own native connection predicate instead of guessing
            // based on class identity.
            boolean connect = pane.connectsTo(neighbor, full);
            out = MinecraftStateCodec.withProperty(out, direction.getName(), String.valueOf(connect));
        }
        return out;
    }

    private BlockState deriveFenceGate(SceneCellPos pos, BlockState state) {
        if (!state.contains(Properties.HORIZONTAL_FACING)) return state;
        Direction facing = state.get(Properties.HORIZONTAL_FACING);
        Direction left = facing.rotateYCounterclockwise();
        Direction right = facing.rotateYClockwise();
        boolean inWall = stateAt(offset(pos, left)).isIn(BlockTags.WALLS)
                || stateAt(offset(pos, right)).isIn(BlockTags.WALLS);

        // Koil side-view bridge: a vanilla gate tests walls perpendicular to its
        // 3D facing, but those neighbors can live entirely on the hidden Z axis in
        // our XY scene. Also accept the two visible screen-side cells so a gate
        // placed between visible walls gets Minecraft's native IN_WALL model/state.
        if (!inWall && projection != null) {
            BlockState screenLeft = stateAt(projection.interactionOffset(pos, projection.screenLeftDirection()));
            BlockState screenRight = stateAt(projection.interactionOffset(pos, projection.screenRightDirection()));
            inWall = screenLeft.isIn(BlockTags.WALLS) || screenRight.isIn(BlockTags.WALLS);
        }
        return MinecraftStateCodec.withProperty(state, "in_wall", String.valueOf(inWall));
    }

    private BlockState deriveWall(SceneCellPos pos, BlockState state) {
        // WallBlock owns four WallShape properties, not booleans. Keep those
        // native enum values authoritative so the baked multipart model and the
        // collision shape read the exact same state.
        boolean north = wallConnects(pos, Direction.NORTH);
        boolean east = wallConnects(pos, Direction.EAST);
        boolean south = wallConnects(pos, Direction.SOUTH);
        boolean west = wallConnects(pos, Direction.WEST);
        BlockState above = stateAt(offset(pos, Direction.UP));
        VoxelShape aboveShape = collisionShape(offset(pos, Direction.UP), above);

        WallShape northShape = wallSideShape(north, aboveShape, Direction.NORTH);
        WallShape eastShape = wallSideShape(east, aboveShape, Direction.EAST);
        WallShape southShape = wallSideShape(south, aboveShape, Direction.SOUTH);
        WallShape westShape = wallSideShape(west, aboveShape, Direction.WEST);

        BlockState out = state;
        if (out.contains(WallBlock.NORTH_SHAPE)) out = out.with(WallBlock.NORTH_SHAPE, northShape);
        if (out.contains(WallBlock.EAST_SHAPE)) out = out.with(WallBlock.EAST_SHAPE, eastShape);
        if (out.contains(WallBlock.SOUTH_SHAPE)) out = out.with(WallBlock.SOUTH_SHAPE, southShape);
        if (out.contains(WallBlock.WEST_SHAPE)) out = out.with(WallBlock.WEST_SHAPE, westShape);
        if (out.contains(WallBlock.UP)) out = out.with(WallBlock.UP, wallNeedsPost(out, above, aboveShape));
        return out;
    }

    /**
     * Detached reproduction of vanilla WallBlock.shouldConnectTo.
     *
     * <p>Vanilla walls connect horizontally to other walls, panes/iron bars,
     * compatible fence gates, and ordinary blocks with a full solid face. Koil's
     * one-cell-thick side-view additionally bridges directly-adjacent fences so
     * the two native shapes physically meet on screen instead of leaving a UI-
     * scale hole. The extension is isolated here and never changes block identity,
     * texture selection, sound, or other Minecraft behavior.</p>
     */
    private boolean wallConnects(SceneCellPos pos, Direction direction) {
        SceneCellPos neighborPos = offset(pos, direction);
        BlockState neighbor = stateAt(neighborPos);
        if (neighbor == null || neighbor.isAir()) return false;
        if (neighbor.getBlock() instanceof WallBlock || neighbor.isIn(BlockTags.WALLS)) return true;
        if (neighbor.getBlock() instanceof FenceBlock) return true;
        if (neighbor.getBlock() instanceof PaneBlock) return true;
        if (isCompatibleFenceGateForWall(neighbor, direction)) return true;
        boolean full = fullFace(neighbor, neighborPos, direction.getOpposite());
        return !Block.cannotConnect(neighbor) && full;
    }

    /**
     * FenceGateBlock.canWallConnect assumes the supplied state is a fence-gate
     * state and reads its horizontal-facing property directly. Passing AIR or an
     * unrelated block is therefore invalid and crashes State#get. Keep the native
     * helper, but only invoke it for the block family whose state contract it owns.
     */
    private boolean isCompatibleFenceGateForWall(BlockState neighbor, Direction direction) {
        if (neighbor == null || direction == null || neighbor.isAir()) return false;
        if (!(neighbor.getBlock() instanceof FenceGateBlock)) return false;
        if (!neighbor.contains(Properties.HORIZONTAL_FACING)) return false;
        try {
            return FenceGateBlock.canWallConnect(neighbor, direction);
        } catch (IllegalArgumentException ignored) {
            // A malformed/modded fence-gate state must not take down the render
            // thread. Treat it as non-connectable and let diagnostics/tests expose it.
            return false;
        }
    }

    /** Vanilla walls distinguish NONE/LOW/TALL from the geometry above the arm. */
    private WallShape wallSideShape(boolean connected, VoxelShape aboveShape, Direction direction) {
        if (!connected) return WallShape.NONE;
        return aboveCoversWallArm(aboveShape, direction) ? WallShape.TALL : WallShape.LOW;
    }

    private boolean wallNeedsPost(BlockState wall, BlockState above, VoxelShape aboveShape) {
        WallShape northShape = wallShape(wall, WallBlock.NORTH_SHAPE);
        WallShape eastShape = wallShape(wall, WallBlock.EAST_SHAPE);
        WallShape southShape = wallShape(wall, WallBlock.SOUTH_SHAPE);
        WallShape westShape = wallShape(wall, WallBlock.WEST_SHAPE);
        boolean north = northShape != WallShape.NONE;
        boolean east = eastShape != WallShape.NONE;
        boolean south = southShape != WallShape.NONE;
        boolean west = westShape != WallShape.NONE;
        int count = (north ? 1 : 0) + (east ? 1 : 0) + (south ? 1 : 0) + (west ? 1 : 0);
        if (count == 0) return true;
        if (above != null && above.getBlock() instanceof WallBlock
                && above.contains(WallBlock.UP) && above.get(WallBlock.UP)) return true;

        boolean straightNS = north && south && !east && !west;
        boolean straightEW = east && west && !north && !south;
        if (!straightNS && !straightEW) return true;

        // Mixed LOW/TALL opposite arms retain the post. A symmetric straight
        // run can omit it unless geometry above occupies the center test area.
        if (straightNS && northShape != southShape) return true;
        if (straightEW && eastShape != westShape) return true;
        return aboveCoversCenter(aboveShape);
    }

    private static WallShape wallShape(BlockState state, net.minecraft.state.property.Property<WallShape> property) {
        if (state == null || property == null || !state.contains(property)) return WallShape.NONE;
        try { return state.get(property); }
        catch (RuntimeException ignored) { return WallShape.NONE; }
    }

    private boolean aboveCoversWallArm(VoxelShape shape, Direction direction) {
        if (shape == null || shape.isEmpty()) return false;
        for (Box box : shape.getBoundingBoxes()) {
            if (box.minY > 0.001D) continue;
            boolean hit = switch (direction) {
                case NORTH -> overlaps(box.minX, box.maxX, 0.3125, 0.6875)
                        && overlaps(box.minZ, box.maxZ, 0.0, 0.5);
                case SOUTH -> overlaps(box.minX, box.maxX, 0.3125, 0.6875)
                        && overlaps(box.minZ, box.maxZ, 0.5, 1.0);
                case WEST -> overlaps(box.minX, box.maxX, 0.0, 0.5)
                        && overlaps(box.minZ, box.maxZ, 0.3125, 0.6875);
                case EAST -> overlaps(box.minX, box.maxX, 0.5, 1.0)
                        && overlaps(box.minZ, box.maxZ, 0.3125, 0.6875);
                default -> false;
            };
            if (hit) return true;
        }
        return false;
    }

    private boolean aboveCoversCenter(VoxelShape shape) {
        if (shape == null || shape.isEmpty()) return false;
        for (Box box : shape.getBoundingBoxes()) {
            if (box.minY <= 0.001D
                    && overlaps(box.minX, box.maxX, 0.25, 0.75)
                    && overlaps(box.minZ, box.maxZ, 0.25, 0.75)) return true;
        }
        return false;
    }

    private BlockState deriveRedstone(SceneCellPos pos, BlockState state) {
        BlockState out = state;
        for (Direction direction : HORIZONTAL) {
            out = MinecraftStateCodec.withProperty(out, direction.getName(), redstoneConnection(pos, direction));
        }
        return out;
    }

    /** Detached reproduction of RedstoneWireBlock's render-connection query. */
    private String redstoneConnection(SceneCellPos pos, Direction direction) {
        SceneCellPos sidePos = offset(pos, direction);
        BlockState side = stateAt(sidePos);
        SceneCellPos abovePos = offset(pos, Direction.UP);
        BlockState above = stateAt(abovePos);
        boolean roomAbove = !solidBlock(above, abovePos);

        if (roomAbove && canRunRedstoneOnTop(sidePos, side)) {
            SceneCellPos aboveSidePos = offset(sidePos, Direction.UP);
            BlockState aboveSide = stateAt(aboveSidePos);
            if (connectsRedstone(aboveSide, direction.getOpposite())) {
                return fullFace(side, sidePos, direction.getOpposite()) ? "up" : "side";
            }
        }

        if (connectsRedstone(side, direction.getOpposite())) return "side";
        if (solidBlock(side, sidePos)) return "none";

        BlockState belowSide = stateAt(offset(sidePos, Direction.DOWN));
        return connectsRedstone(belowSide, direction.getOpposite()) ? "side" : "none";
    }

    private boolean canRunRedstoneOnTop(SceneCellPos pos, BlockState state) {
        if (state == null || state.isAir()) return false;
        return state.isOf(Blocks.HOPPER) || fullFace(state, pos, Direction.UP);
    }

    /** Mirrors vanilla RedstoneWireBlock.connectsTo rules used for rendering. */
    private boolean connectsRedstone(BlockState state, Direction towardWire) {
        if (state == null || state.isAir()) return false;
        if (state.getBlock() instanceof RedstoneWireBlock) return true;
        if (state.getBlock() instanceof RepeaterBlock && state.contains(Properties.HORIZONTAL_FACING)) {
            Direction facing = state.get(Properties.HORIZONTAL_FACING);
            return facing == towardWire || facing.getOpposite() == towardWire;
        }
        if (state.getBlock() instanceof ObserverBlock && state.contains(Properties.FACING)) {
            return state.get(Properties.FACING) == towardWire;
        }
        // Comparator intentionally falls through here. Unlike repeaters, side
        // redstone input is part of its native behavior.
        return state.emitsRedstonePower() && towardWire != null;
    }

    /** Matches the vanilla StairsBlock inner/outer/straight neighbor algorithm. */
    private BlockState deriveStairs(SceneCellPos pos, BlockState state) {
        if (!state.contains(Properties.HORIZONTAL_FACING) || !state.contains(Properties.STAIR_SHAPE)
                || !state.contains(Properties.BLOCK_HALF)) return state;
        Direction facing = state.get(Properties.HORIZONTAL_FACING);
        Comparable<?> half = state.get(Properties.BLOCK_HALF);

        BlockState front = stateAt(offset(pos, facing));
        if (isSameHalfStair(front, half)) {
            Direction otherFacing = front.get(Properties.HORIZONTAL_FACING);
            if (otherFacing.getAxis() != facing.getAxis()
                    && differentStairOrientation(pos, state, otherFacing.getOpposite())) {
                StairShape shape = otherFacing == facing.rotateYCounterclockwise()
                        ? StairShape.OUTER_LEFT : StairShape.OUTER_RIGHT;
                return state.with(Properties.STAIR_SHAPE, shape);
            }
        }

        BlockState back = stateAt(offset(pos, facing.getOpposite()));
        if (isSameHalfStair(back, half)) {
            Direction otherFacing = back.get(Properties.HORIZONTAL_FACING);
            if (otherFacing.getAxis() != facing.getAxis()
                    && differentStairOrientation(pos, state, otherFacing)) {
                StairShape shape = otherFacing == facing.rotateYCounterclockwise()
                        ? StairShape.INNER_LEFT : StairShape.INNER_RIGHT;
                return state.with(Properties.STAIR_SHAPE, shape);
            }
        }
        return state.with(Properties.STAIR_SHAPE, StairShape.STRAIGHT);
    }

    private boolean isSameHalfStair(BlockState candidate, Comparable<?> half) {
        return candidate != null && candidate.getBlock() instanceof StairsBlock
                && candidate.contains(Properties.BLOCK_HALF)
                && candidate.contains(Properties.HORIZONTAL_FACING)
                && candidate.get(Properties.BLOCK_HALF).equals(half);
    }

    private boolean differentStairOrientation(SceneCellPos pos, BlockState state, Direction side) {
        BlockState candidate = stateAt(offset(pos, side));
        return !(candidate.getBlock() instanceof StairsBlock)
                || !candidate.contains(Properties.HORIZONTAL_FACING)
                || !candidate.contains(Properties.BLOCK_HALF)
                || candidate.get(Properties.HORIZONTAL_FACING) != state.get(Properties.HORIZONTAL_FACING)
                || !candidate.get(Properties.BLOCK_HALF).equals(state.get(Properties.BLOCK_HALF));
    }

    private BlockState deriveRail(SceneCellPos pos, BlockState state) {
        boolean north = isRail(offset(pos, Direction.NORTH));
        boolean south = isRail(offset(pos, Direction.SOUTH));
        boolean east = isRail(offset(pos, Direction.EAST));
        boolean west = isRail(offset(pos, Direction.WEST));
        String shape;
        if (east && west && !north && !south) shape = "east_west";
        else if (north && south && !east && !west) shape = "north_south";
        else if (south && east && !north && !west) shape = "south_east";
        else if (south && west && !north && !east) shape = "south_west";
        else if (north && west && !south && !east) shape = "north_west";
        else if (north && east && !south && !west) shape = "north_east";
        else if (east || west) shape = "east_west";
        else shape = "north_south";

        if ("east_west".equals(shape)) {
            if (isRail(offset(offset(pos, Direction.EAST), Direction.UP))) shape = "ascending_east";
            else if (isRail(offset(offset(pos, Direction.WEST), Direction.UP))) shape = "ascending_west";
        } else if ("north_south".equals(shape)) {
            if (isRail(offset(offset(pos, Direction.NORTH), Direction.UP))) shape = "ascending_north";
            else if (isRail(offset(offset(pos, Direction.SOUTH), Direction.UP))) shape = "ascending_south";
        }
        return MinecraftStateCodec.withProperty(state, "shape", shape);
    }

    private boolean isRail(SceneCellPos pos) {
        return stateAt(pos).getBlock() instanceof AbstractRailBlock;
    }

    private boolean fullFace(BlockState state, SceneCellPos pos, Direction face) {
        if (state == null || state.isAir()) return false;
        try { return state.isSideSolidFullSquare(view, minecraft(pos), face); }
        catch (RuntimeException ignored) { return false; }
    }

    private boolean solidBlock(BlockState state, SceneCellPos pos) {
        if (state == null || state.isAir()) return false;
        try { return state.isSolidBlock(view, minecraft(pos)); }
        catch (RuntimeException ignored) { return false; }
    }

    private VoxelShape collisionShape(SceneCellPos pos, BlockState state) {
        if (state == null || state.isAir()) return net.minecraft.util.shape.VoxelShapes.empty();
        try { return state.getCollisionShape(view, minecraft(pos)); }
        catch (RuntimeException ignored) { return net.minecraft.util.shape.VoxelShapes.empty(); }
    }

    private BlockState stateAt(SceneCellPos pos) {
        return pos == null ? Blocks.AIR.getDefaultState() : blocks.getBlockState(pos);
    }

    private static BlockPos minecraft(SceneCellPos pos) {
        return pos == null ? BlockPos.ORIGIN : new BlockPos(pos.x(), pos.y(), pos.depth());
    }

    private SceneCellPos offset(SceneCellPos pos, Direction direction) {
        if (pos == null || direction == null) return null;
        if (projection != null) return projection.interactionOffset(pos, direction);
        return new SceneCellPos(pos.x() + direction.getOffsetX(), pos.y() + direction.getOffsetY(),
                pos.depth() + direction.getOffsetZ());
    }

    private static boolean overlaps(double aMin, double aMax, double bMin, double bMax) {
        return aMax > bMin + 1.0E-7 && aMin < bMax - 1.0E-7;
    }

    private record PendingState(SceneCellPos pos, BlockState state) { }
}
