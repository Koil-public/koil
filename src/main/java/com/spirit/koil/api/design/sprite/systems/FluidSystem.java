package com.spirit.koil.api.design.sprite.systems;

import com.spirit.koil.api.design.sprite.actor.Actor;
import com.spirit.koil.api.design.sprite.actor.FallingBlockActor;
import com.spirit.koil.api.design.sprite.actor.ItemActor;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneEnvironment;
import com.spirit.koil.api.design.sprite.core.SceneEvent;
import com.spirit.koil.api.design.sprite.core.SceneEventBus;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.minecraft.MinecraftStateCodec;
import com.spirit.koil.api.design.sprite.physics.PhysicsBody2D;
import com.spirit.koil.api.design.sprite.world.BlockCell;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import com.spirit.koil.api.design.sprite.world.FluidCell;
import com.spirit.koil.api.design.sprite.world.FluidGrid;
import com.spirit.koil.api.design.sprite.world.SceneBlockView;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Scene-native detached Minecraft fluid simulation.
 *
 * <p>This system is authoritative for water/lava propagation in the new scene. It
 * does not delegate flow to KoilVirtualBlockWorld and does not require ClientWorld
 * or ServerWorld. Minecraft FluidState/FlowableFluid objects remain the content
 * specification, while the 2D scene limits horizontal spreading to directions in
 * the active scene plane.</p>
 */
public final class FluidSystem {
    private static final int WATER_TICK_RATE = 5;
    private static final int LAVA_OVERWORLD_TICK_RATE = 30;
    private static final int LAVA_NETHER_TICK_RATE = 10;

    public record Statistics(int fluidCells, int waterCells, int lavaCells, int sourceCells,
                             int fallingCells, long lastWaterTick, long lastLavaTick) { }

    private record Proposal(FluidState state, FluidCell.Authority authority, long sourceId) { }

    private final BlockGrid blocks;
    private final FluidGrid fluids;
    private final SceneProjection projection;
    private final SceneEnvironment environment;
    private final SceneEventBus events;
    private final SceneBlockView view;
    private final LongSupplier gameTick;
    private final LongSupplier physicsStep;
    private final Set<SceneCellPos> embeddedFluidCells = new HashSet<>();
    private long lastWaterTick = Long.MIN_VALUE;
    private long lastLavaTick = Long.MIN_VALUE;

    public FluidSystem(BlockGrid blocks, FluidGrid fluids, SceneProjection projection,
                           SceneEnvironment environment, SceneEventBus events,
                           LongSupplier gameTick, LongSupplier physicsStep) {
        this.blocks = blocks;
        this.fluids = fluids;
        this.projection = projection;
        this.environment = environment;
        this.events = events;
        this.view = new SceneBlockView(blocks, fluids);
        this.gameTick = gameTick;
        this.physicsStep = physicsStep;
    }

    /** Called at deterministic Minecraft 20 TPS. */
    public void minecraftTick() {
        importBlockStateFluids();
        long tick = gameTick.getAsLong();
        if (lastWaterTick == Long.MIN_VALUE || tick - lastWaterTick >= WATER_TICK_RATE) {
            simulateFamily(true);
            lastWaterTick = tick;
        }
        int lavaRate = isNether() ? LAVA_NETHER_TICK_RATE : LAVA_OVERWORLD_TICK_RATE;
        if (lastLavaTick == Long.MIN_VALUE || tick - lastLavaTick >= lavaRate) {
            simulateFamily(false);
            lastLavaTick = tick;
        }
    }

    /** Seeds an old command/render proxy into the new grid. Flow remains scene-native. */
    public void setLegacySeed(SceneCellPos pos, FluidState state, long sourceId) {
        if (pos == null || state == null || state.isEmpty()) return;
        fluids.setLegacySeed(pos, normalizeSourceState(state), sourceId);
    }

    public void removeLegacySeed(SceneCellPos pos, long sourceId) {
        if (pos != null) fluids.removeLegacySeed(pos, sourceId);
    }

    public void setSceneSource(SceneCellPos pos, Fluid fluid) {
        if (pos == null || fluid == null || fluid == Fluids.EMPTY) return;
        FluidState state = sourceState(fluid, false);
        if (state != null && !state.isEmpty()) fluids.set(pos, state);
    }

    public float fillHeight(SceneCellPos pos) {
        FluidCell cell = fluids.get(pos);
        if (cell == null || cell.isEmpty()) return 0.0F;
        if (cell.falling() || sameFamily(cell.state(), fluids.getFluidState(projection.interactionOffset(pos, Direction.UP)))) return 1.0F;
        try { return clamp01(cell.state().getFluid().getHeight(cell.state())); }
        catch (RuntimeException ignored) { return cell.source() ? 0.8888889F : clamp01(cell.level() / 9.0F); }
    }

    /**
     * Visible liquid surface for the current 2D projection. Vanilla fluid rendering
     * derives corner heights from neighboring fluid levels; Koil mirrors that idea
     * in the visible slice by deriving the two screen-space edge heights. Adjacent
     * cells therefore share an edge and naturally form slopes instead of a staircase
     * of flat rectangles. Falling columns stay full-height/vertical.
     */
    public SurfaceProfile surfaceProfile(SceneCellPos pos) {
        FluidCell cell = fluids.get(pos);
        if (cell == null || cell.isEmpty()) return new SurfaceProfile(0.0F, 0.0F);
        if (projection.mode() == SceneProjection.Mode.XZ_TOP || cell.falling()) {
            return new SurfaceProfile(1.0F, 1.0F);
        }
        FluidState state = cell.state();
        SceneCellPos above = projection.interactionOffset(pos, Direction.UP);
        if (sameFamily(state, fluids.getFluidState(above))) return new SurfaceProfile(1.0F, 1.0F);

        float center = fillHeight(pos);
        // Surface vertices are CANONICAL SHARED boundaries. The same physical
        // boundary is evaluated from the same four samples regardless of which
        // adjacent cell asks for it, so right(cell N) == left(cell N+1). This is
        // the key invariant that prevents the disconnected saw-tooth slopes from
        // Rev AJ. Direction comes from the sequence of fluid levels, never from a
        // per-cell visual tilt or texture rotation.
        float left = sharedSurfaceVertex(pos, projection.screenLeftDirection(), state, center, cell.source());
        float right = sharedSurfaceVertex(pos, projection.screenRightDirection(), state, center, cell.source());
        return new SurfaceProfile(clamp01(left), clamp01(right));
    }

    public record SurfaceProfile(float leftHeight, float rightHeight) {
        public float averageHeight() { return (leftHeight + rightHeight) * 0.5F; }
        public boolean sloped() { return Math.abs(leftHeight - rightHeight) > 0.015625F; }
    }

    /**
     * Native fluid velocity projected into Koil's visible 2D plane. Minecraft's
     * FlowableFluid derives this vector from neighboring levels/obstructions;
     * keeping it here lets the renderer shape the visible liquid surface in the
     * same direction without rotating or replacing Minecraft's flow texture.
     */
    public FlowVector2D flowVector(SceneCellPos pos) {
        if (pos == null) return new FlowVector2D(0.0F, 0.0F);
        FluidState state = fluids.getFluidState(pos);
        if (state == null || state.isEmpty()) return new FlowVector2D(0.0F, 0.0F);
        try {
            BlockPos minecraftPos = projection.toMinecraft(pos);
            Vec3d velocity = state.getVelocity(view, minecraftPos);
            if (velocity == null) return new FlowVector2D(0.0F, 0.0F);
            return switch (projection.mode()) {
                case XY_SIDE -> new FlowVector2D((float) velocity.x, (float) -velocity.y);
                case ZY_SIDE -> new FlowVector2D((float) velocity.z, (float) -velocity.y);
                case XZ_TOP -> new FlowVector2D((float) velocity.x, (float) velocity.z);
            };
        } catch (RuntimeException ignored) {
            return new FlowVector2D(0.0F, 0.0F);
        }
    }

    public record FlowVector2D(float screenX, float screenY) {
        public float magnitude() { return (float) Math.sqrt(screenX * screenX + screenY * screenY); }
        public boolean moving() { return magnitude() > 0.001F; }
    }

    private float sharedSurfaceVertex(SceneCellPos pos, Direction side, FluidState state,
                                      float centerHeight, boolean source) {
        if (side == null) return centerHeight;
        SceneCellPos neighborPos = projection.interactionOffset(pos, side);
        FluidState neighborState = fluids.getFluidState(neighborPos);
        if (sameFamily(state, neighborState)) {
            float neighborHeight = visibleSurfaceSample(neighborPos, neighborState);

            // Smooth one shared vertex using the two cells touching the edge and
            // one sample beyond each side. The 1:3:3:1 kernel softens abrupt level
            // changes while remaining symmetric, therefore both cells calculate
            // the EXACT same edge height. Missing outer samples clamp to the
            // nearest participating cell instead of creating a discontinuity.
            SceneCellPos farCurrentPos = projection.interactionOffset(pos, side.getOpposite());
            SceneCellPos farNeighborPos = projection.interactionOffset(neighborPos, side);
            FluidState farCurrentState = fluids.getFluidState(farCurrentPos);
            FluidState farNeighborState = fluids.getFluidState(farNeighborPos);
            float farCurrent = sameFamily(state, farCurrentState)
                    ? visibleSurfaceSample(farCurrentPos, farCurrentState) : centerHeight;
            float farNeighbor = sameFamily(state, farNeighborState)
                    ? visibleSurfaceSample(farNeighborPos, farNeighborState) : neighborHeight;
            float smoothed = (farCurrent + 3.0F * centerHeight + 3.0F * neighborHeight + farNeighbor) / 8.0F;
            float min = Math.min(centerHeight, neighborHeight);
            float max = Math.max(centerHeight, neighborHeight);
            return Math.max(min, Math.min(max, smoothed));
        }

        BlockState neighborBlock = blocks.getBlockState(neighborPos);
        boolean blocked = neighborBlock != null && !neighborBlock.isAir() && !neighborBlock.isReplaceable();
        if (blocked || source) return centerHeight;

        // At the terminal edge of a flowing run, taper toward the lip. This edge
        // has no adjacent same-fluid cell, so it does not participate in the
        // shared-boundary invariant. A cell below the lip makes the pour almost
        // reach zero; otherwise retain a small visible meniscus.
        SceneCellPos belowNeighbor = projection.interactionOffset(neighborPos, Direction.DOWN);
        boolean poursOverEdge = sameFamily(state, fluids.getFluidState(belowNeighbor));
        float lip = poursOverEdge ? 0.02F : Math.min(0.14F, centerHeight * 0.22F);
        return Math.min(centerHeight, lip);
    }

    private float visibleSurfaceSample(SceneCellPos pos, FluidState state) {
        if (pos == null || state == null || state.isEmpty()) return 0.0F;
        SceneCellPos above = projection.interactionOffset(pos, Direction.UP);
        if (sameFamily(state, fluids.getFluidState(above))) return 1.0F;
        return fillHeight(pos);
    }

    /** Applies water/lava drag and buoyancy before static collision resolution. */
    public void applyActorFluidForces(Actor actor, float dt) {
        if (actor == null || actor.removed() || !(dt > 0.0F)) return;
        PhysicsBody2D body = actor.body();
        FluidSample sample = sampleActor(body);
        if (sample == null || sample.submersion <= 0.001F) return;

        float submersion = clamp01(sample.submersion);
        boolean water = isWater(sample.state);
        float tickScale = Math.max(0.0F, dt * 20.0F);
        float horizontalDamping = water ? 0.86F : 0.72F;
        float verticalDamping = water ? 0.88F : 0.76F;
        float vx = body.velocityX() * (float) Math.pow(horizontalDamping, tickScale * submersion);
        float vy = body.velocityY() * (float) Math.pow(verticalDamping, tickScale * submersion);

        float buoyancyScale;
        if (water) {
            if (actor instanceof ItemActor) buoyancyScale = 1.42F;
            else if (actor instanceof FallingBlockActor) buoyancyScale = 0.48F;
            else buoyancyScale = 0.92F;
        } else {
            buoyancyScale = actor instanceof ItemActor ? 0.72F : 0.30F;
        }
        vy -= body.gravity() * buoyancyScale * submersion * dt;
        body.setVelocity(vx, vy);
        body.wake();

        if (events != null) events.publish(new SceneEvent.FluidContact(actor.id(), sample.position,
                sample.state, submersion, physicsStep.getAsLong()));
    }

    public Statistics statistics() {
        int water = 0, lava = 0, source = 0, falling = 0;
        for (FluidGrid.Entry entry : fluids.entries()) {
            FluidCell cell = entry.cell();
            if (isWater(cell.state())) water++;
            if (isLava(cell.state())) lava++;
            if (cell.source()) source++;
            if (cell.falling()) falling++;
        }
        return new Statistics(fluids.fluidCount(), water, lava, source, falling, lastWaterTick, lastLavaTick);
    }

    public void reset() {
        embeddedFluidCells.clear();
        lastWaterTick = Long.MIN_VALUE;
        lastLavaTick = Long.MIN_VALUE;
    }

    private void importBlockStateFluids() {
        Set<SceneCellPos> nowEmbedded = new HashSet<>();
        for (BlockGrid.Entry entry : new ArrayList<>(blocks.entries())) {
            SceneCellPos pos = entry.position();
            BlockCell cell = entry.cell();
            BlockState state = cell == null ? null : cell.blockState();
            if (state == null || state.isAir()) continue;
            FluidState embedded;
            try { embedded = state.getFluidState(); }
            catch (RuntimeException ignored) { embedded = Fluids.EMPTY.getDefaultState(); }
            if (embedded == null || embedded.isEmpty()) continue;

            // Standalone FluidBlock belongs in the FluidGrid, not the block grid.
            if (state.getBlock() instanceof FluidBlock) {
                blocks.remove(pos);
                if (cell.authority() == BlockCell.Authority.LEGACY_PROXY && cell.sourceId() >= 0L) {
                    fluids.setLegacySeed(pos, normalizeSourceState(embedded), cell.sourceId());
                } else {
                    fluids.set(pos, normalizeSourceState(embedded));
                }
                continue;
            }

            nowEmbedded.add(pos);
            FluidCell existing = fluids.get(pos);
            if (existing == null || existing.authority() == FluidCell.Authority.BLOCK_STATE) {
                fluids.setBlockStateFluid(pos, embedded);
            }
        }

        for (SceneCellPos previous : new HashSet<>(embeddedFluidCells)) {
            if (nowEmbedded.contains(previous)) continue;
            FluidCell cell = fluids.get(previous);
            if (cell != null && cell.authority() == FluidCell.Authority.BLOCK_STATE) fluids.remove(previous);
        }
        embeddedFluidCells.clear();
        embeddedFluidCells.addAll(nowEmbedded);
    }

    private void simulateFamily(boolean waterFamily) {
        List<FluidGrid.Entry> entries = new ArrayList<>();
        for (FluidGrid.Entry entry : fluids.entries()) {
            if (waterFamily ? isWater(entry.cell().state()) : isLava(entry.cell().state())) entries.add(entry);
        }
        entries.sort(Comparator.comparingInt((FluidGrid.Entry e) -> e.position().depth())
                .thenComparingInt(e -> e.position().x()).thenComparingInt(e -> e.position().y()));
        if (entries.isEmpty()) return;

        Map<SceneCellPos, Proposal> proposals = new LinkedHashMap<>();
        Fluid familyFluid = entries.get(0).cell().state().getFluid();
        int decrease = waterFamily ? 1 : 2;

        // First preserve true sources. Flowing cells must earn their continued
        // existence from a source/upstream proposal on every simulation pass.
        for (FluidGrid.Entry entry : entries) {
            FluidCell cell = entry.cell();
            if (cell.source()) {
                proposals.put(entry.position(), new Proposal(sourceState(cell.state().getFluid(), false),
                        cell.authority(), cell.sourceId()));
            }
        }

        // Minecraft-style infinite water sources in the visible 2D plane.
        if (waterFamily) {
            for (FluidGrid.Entry entry : entries) {
                if (entry.cell().source()) continue;
                SceneCellPos pos = entry.position();
                int sources = 0;
                for (Direction side : horizontalDirections()) {
                    FluidCell sideCell = fluids.get(projection.interactionOffset(pos, side));
                    if (sideCell != null && sideCell.source() && isWater(sideCell.state())) sources++;
                }
                if (sources >= 2 && hasSolidSupport(projection.interactionOffset(pos, Direction.DOWN))) {
                    proposals.put(pos, new Proposal(sourceState(Fluids.WATER, false),
                            FluidCell.Authority.SCENE, -1L));
                }
            }
        }

        for (FluidGrid.Entry entry : entries) {
            SceneCellPos pos = entry.position();
            FluidCell cell = entry.cell();
            Fluid fluid = cell.state().getFluid();
            SceneCellPos down = projection.interactionOffset(pos, Direction.DOWN);
            if (canAccept(down, fluid)) {
                propose(proposals, down, flowingState(fluid, 8, true), FluidCell.Authority.SCENE, -1L);
                continue;
            }

            int horizontalLevel = cell.source() ? 7 : cell.level() - decrease;
            if (cell.falling()) horizontalLevel = Math.max(horizontalLevel, 7 - (waterFamily ? 0 : 1));
            if (horizontalLevel <= 0) continue;
            for (Direction side : horizontalDirections()) {
                SceneCellPos target = projection.interactionOffset(pos, side);
                if (canAccept(target, fluid)) {
                    propose(proposals, target, flowingState(fluid, horizontalLevel, false),
                            FluidCell.Authority.SCENE, -1L);
                }
            }
        }

        // Remove old non-source flow of this family unless it was recreated.
        for (FluidGrid.Entry entry : entries) {
            FluidCell cell = entry.cell();
            if (cell.source()) continue;
            if (!proposals.containsKey(entry.position())) fluids.remove(entry.position());
        }

        for (Map.Entry<SceneCellPos, Proposal> entry : proposals.entrySet()) {
            applyProposal(entry.getKey(), entry.getValue());
        }
    }

    private void propose(Map<SceneCellPos, Proposal> proposals, SceneCellPos pos, FluidState state,
                         FluidCell.Authority authority, long sourceId) {
        if (state == null || state.isEmpty()) return;
        Proposal existing = proposals.get(pos);
        if (existing == null) {
            proposals.put(pos, new Proposal(state, authority, sourceId));
            return;
        }
        if (!sameFamily(existing.state, state)) return;
        int oldLevel = level(existing.state);
        int newLevel = level(state);
        boolean oldSource = isStill(existing.state);
        boolean newSource = isStill(state);
        if ((newSource && !oldSource) || (!oldSource && !newSource && newLevel > oldLevel)) {
            proposals.put(pos, new Proposal(state, authority, sourceId));
        }
    }

    private void applyProposal(SceneCellPos pos, Proposal proposal) {
        if (pos == null || proposal == null || proposal.state == null || proposal.state.isEmpty()) return;
        FluidCell existing = fluids.get(pos);
        if (existing != null && !existing.isEmpty() && !sameFamily(existing.state(), proposal.state)) {
            mixAt(pos, existing.state(), proposal.state);
            return;
        }

        BlockState block = blocks.getBlockState(pos);
        if (block != null && !block.isAir()) {
            if (isWater(proposal.state) && MinecraftStateCodec.hasProperty(block, "waterlogged")) {
                BlockState waterlogged = MinecraftStateCodec.withProperty(block, "waterlogged", "true");
                if (!waterlogged.equals(block)) blocks.updateState(pos, waterlogged);
                fluids.setBlockStateFluid(pos, sourceState(Fluids.WATER, false));
                return;
            }
            boolean replaceable;
            try { replaceable = block.isReplaceable(); }
            catch (RuntimeException ignored) { replaceable = false; }
            if (!replaceable) return;
            blocks.remove(pos);
        }

        if (proposal.authority == FluidCell.Authority.LEGACY_SEED) {
            fluids.setLegacySeed(pos, proposal.state, proposal.sourceId);
        } else if (proposal.authority == FluidCell.Authority.BLOCK_STATE) {
            fluids.setBlockStateFluid(pos, proposal.state);
        } else {
            fluids.set(pos, proposal.state);
        }
    }

    private void mixAt(SceneCellPos pos, FluidState existing, FluidState incoming) {
        boolean waterLava = (isWater(existing) && isLava(incoming)) || (isLava(existing) && isWater(incoming));
        if (!waterLava) return;
        FluidState lava = isLava(existing) ? existing : incoming;
        BlockState solid = isStill(lava) ? Blocks.OBSIDIAN.getDefaultState() : Blocks.COBBLESTONE.getDefaultState();
        fluids.remove(pos);
        blocks.set(pos, solid);
    }

    private boolean canAccept(SceneCellPos pos, Fluid incoming) {
        if (pos == null || incoming == null || incoming == Fluids.EMPTY) return false;
        FluidCell existing = fluids.get(pos);
        if (existing != null && !existing.isEmpty()) {
            if (!sameFamily(existing.state(), incoming.getDefaultState())) return true; // mixing
            return !existing.source();
        }
        BlockState block = blocks.getBlockState(pos);
        if (block == null || block.isAir()) return true;
        if (isWater(incoming.getDefaultState()) && MinecraftStateCodec.hasProperty(block, "waterlogged")) {
            String encoded = MinecraftStateCodec.encode(block);
            return !encoded.toLowerCase(java.util.Locale.ROOT).contains("waterlogged=true");
        }
        try { return block.isReplaceable(); }
        catch (RuntimeException ignored) { return false; }
    }

    private boolean hasSolidSupport(SceneCellPos pos) {
        BlockState state = blocks.getBlockState(pos);
        if (state == null || state.isAir()) return false;
        try { return !state.isReplaceable(); }
        catch (RuntimeException ignored) { return true; }
    }

    private List<Direction> horizontalDirections() {
        return switch (projection.mode()) {
            case XY_SIDE -> List.of(Direction.WEST, Direction.EAST);
            case ZY_SIDE -> List.of(Direction.NORTH, Direction.SOUTH);
            case XZ_TOP -> List.of(Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH);
        };
    }

    private FluidSample sampleActor(PhysicsBody2D body) {
        FluidSample best = null;
        float actorLeft = body.x() - body.halfWidth();
        float actorRight = body.x() + body.halfWidth();
        float actorTop = body.y() - body.halfHeight();
        float actorBottom = body.y() + body.halfHeight();
        float actorArea = Math.max(0.001F, (actorRight - actorLeft) * (actorBottom - actorTop));

        for (FluidGrid.Entry entry : fluids.entries()) {
            if (projection.depthCoordinate(entry.position()) != body.depth()) continue;
            float centerX = projection.cellCenterScreenX(entry.position());
            float centerY = projection.cellCenterScreenY(entry.position());
            float pixels = projection.cellPixels();
            float left = centerX - pixels * 0.5F;
            float right = centerX + pixels * 0.5F;
            float top;
            float bottom;
            if (projection.mode() == SceneProjection.Mode.XZ_TOP) {
                top = centerY - pixels * 0.5F;
                bottom = centerY + pixels * 0.5F;
            } else {
                bottom = centerY + pixels * 0.5F;
                SurfaceProfile profile = surfaceProfile(entry.position());
                float sampleX = Math.max(left, Math.min(right, body.x()));
                float t = clamp01((sampleX - left) / Math.max(0.001F, right - left));
                float localHeight = profile.leftHeight() + (profile.rightHeight() - profile.leftHeight()) * t;
                top = bottom - pixels * clamp01(localHeight);
            }
            float overlapW = Math.max(0.0F, Math.min(actorRight, right) - Math.max(actorLeft, left));
            float overlapH = Math.max(0.0F, Math.min(actorBottom, bottom) - Math.max(actorTop, top));
            float submersion = clamp01((overlapW * overlapH) / actorArea);
            if (submersion <= 0.001F) continue;
            if (best == null || submersion > best.submersion) {
                best = new FluidSample(entry.position(), entry.cell().state(), submersion);
            }
        }
        return best;
    }

    private record FluidSample(SceneCellPos position, FluidState state, float submersion) { }

    private boolean isNether() {
        Identifier id = environment.dimensionId();
        return id != null && id.getPath().equals("the_nether");
    }

    private static boolean sameFamily(FluidState a, FluidState b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        return sameFamily(a.getFluid(), b.getFluid());
    }

    private static boolean sameFamily(Fluid a, Fluid b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (isWater(a.getDefaultState()) && isWater(b.getDefaultState())) return true;
        if (isLava(a.getDefaultState()) && isLava(b.getDefaultState())) return true;
        if (a instanceof FlowableFluid fa && b instanceof FlowableFluid fb) {
            try { return fa.getStill() == fb.getStill(); }
            catch (RuntimeException ignored) { return false; }
        }
        return false;
    }

    private static FluidState normalizeSourceState(FluidState state) {
        if (state == null || state.isEmpty()) return Fluids.EMPTY.getDefaultState();
        return isStill(state) ? sourceState(state.getFluid(), false) : state;
    }

    private static FluidState sourceState(Fluid fluid, boolean falling) {
        if (fluid instanceof FlowableFluid flowable) {
            try { return flowable.getStill(falling); }
            catch (RuntimeException ignored) { }
        }
        return fluid == null ? Fluids.EMPTY.getDefaultState() : fluid.getDefaultState();
    }

    private static FluidState flowingState(Fluid fluid, int level, boolean falling) {
        int clamped = Math.max(1, Math.min(8, level));
        if (fluid instanceof FlowableFluid flowable) {
            try { return flowable.getFlowing(clamped, falling); }
            catch (RuntimeException ignored) { }
        }
        return fluid == null ? Fluids.EMPTY.getDefaultState() : fluid.getDefaultState();
    }

    private static int level(FluidState state) {
        if (state == null || state.isEmpty()) return 0;
        try { return Math.max(1, Math.min(8, state.getFluid().getLevel(state))); }
        catch (RuntimeException ignored) { return isStill(state) ? 8 : 1; }
    }

    private static boolean isStill(FluidState state) {
        if (state == null || state.isEmpty()) return false;
        try { return state.getFluid().isStill(state); }
        catch (RuntimeException ignored) { return level(state) >= 8; }
    }

    public static boolean isWater(FluidState state) {
        if (state == null || state.isEmpty()) return false;
        Fluid fluid = state.getFluid();
        return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
    }

    public static boolean isLava(FluidState state) {
        if (state == null || state.isEmpty()) return false;
        Fluid fluid = state.getFluid();
        return fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA;
    }

    private static float clamp01(float value) {
        if (!Float.isFinite(value)) return 0.0F;
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
