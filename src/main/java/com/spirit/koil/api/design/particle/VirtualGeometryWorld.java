package com.spirit.koil.api.design.particle;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvent;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Projected collision geometry shared by Koil registry blocks, item/entity actors,
 * and authored particles that explicitly opt into virtual-world collision.
 *
 * <p>Registered blocks remain logical grid cells in {@link VirtualBlockWorld}.
 * This class derives physical screen-space solids from each block's live Minecraft
 * {@link BlockState#getCollisionShape(net.minecraft.world.BlockView, BlockPos)}.
 * Dynamic actors are swept against those solids, so fast items cannot tunnel
 * through thin or partial block shapes.</p>
 *
 * <p>The default scene projection is XY: Minecraft X maps to screen X and Minecraft
 * Y maps upward on screen. ZY is available for side views along the other horizontal
 * axis, and XZ is available for top-down scenes. The virtual block-world's logical
 * neighbor grid remains independent from this rendering/collision projection.</p>
 */
public final class VirtualGeometryWorld {
    private static final float MIN_EXTENT = 0.0005F;
    private static final float CONTACT_EPSILON = 0.45F;
    private static final float SWEEP_EPSILON = 0.015F;
    private static final int MAX_SWEEP_ITERATIONS = 5;
    private static final int MAX_DEPENETRATION_ITERATIONS = 6;

    public enum Projection {
        XY,
        ZY,
        XZ;

        public static Projection parse(String value) {
            if (value == null) return XY;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "zy", "yz", "side_z" -> ZY;
                case "xz", "zx", "top", "top_down" -> XZ;
                default -> XY;
            };
        }
    }

    public record Contact(
            long blockId,
            Block block,
            BlockState state,
            float normalX,
            float normalY,
            float impactSpeed,
            boolean grounded
    ) { }

    private record SolidBox(
            long blockId,
            Block block,
            BlockState state,
            int simulationLayer,
            float left,
            float top,
            float right,
            float bottom,
            float slipperiness,
            boolean slime,
            boolean honey
    ) { }

    private record BlockTarget(
            long blockId,
            int simulationLayer,
            float left,
            float top,
            float right,
            float bottom,
            float centerX,
            float centerY
    ) { }

    private record SweepHit(SolidBox box, float time, float normalX, float normalY) { }

    private record SceneBlock(
            VirtualBlockWorld.SpriteNode node,
            Block block,
            BlockState state,
            BlockPos logicalPos,
            float centerX,
            float centerY
    ) { }

    private static final class SceneBlockView implements BlockView {
        private final Map<BlockPos, BlockState> states;

        private SceneBlockView(Map<BlockPos, BlockState> states) {
            this.states = states;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (pos == null) return Blocks.AIR.getDefaultState();
            return states.getOrDefault(pos, Blocks.AIR.getDefaultState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            BlockState state = getBlockState(pos);
            try {
                return state.getFluidState();
            } catch (RuntimeException ignored) {
                return Fluids.EMPTY.getDefaultState();
            }
        }

        @Override
        public int getHeight() {
            return 4096;
        }

        @Override
        public int getBottomY() {
            return -2048;
        }
    }

    private final Map<Integer, List<SolidBox>> solidsByLayer = new HashMap<>();
    private final Map<Long, List<SolidBox>> solidsByBlock = new HashMap<>();
    private final Map<Integer, List<BlockTarget>> interactionTargetsByLayer = new HashMap<>();
    private final Map<BlockPos, BlockState> sceneStates = new HashMap<>();
    private float cellPixels = VirtualBlockWorld.DEFAULT_CELL_PIXELS;
    private Projection projection = Projection.XY;

    public void clear() {
        solidsByLayer.clear();
        solidsByBlock.clear();
        interactionTargetsByLayer.clear();
        sceneStates.clear();
    }

    public float getCellPixels() {
        return cellPixels;
    }

    public void setCellPixels(float pixels) {
        cellPixels = Math.max(4.0F, Math.min(96.0F, pixels));
    }

    public Projection getProjection() {
        return projection;
    }

    public void setProjection(Projection projection) {
        this.projection = projection == null ? Projection.XY : projection;
    }

    public void setProjection(String projection) {
        setProjection(Projection.parse(projection));
    }

    /** Rebuilds static/moving block collision geometry from the current sprite frame. */
    public void rebuild(Collection<? extends VirtualBlockWorld.SpriteNode> nodes) {
        clear();
        if (nodes == null || nodes.isEmpty()) return;
        List<VirtualBlockWorld.SpriteNode> ordered = new ArrayList<>(nodes);
        ordered.sort(Comparator.comparingLong(VirtualBlockWorld.SpriteNode::id));

        // Pass one publishes the complete virtual grid before any BlockState asks
        // for its shape. This keeps the engine detached from a ClientWorld while
        // still allowing neighbor-aware vanilla shape code to inspect the scene.
        List<SceneBlock> sceneBlocks = new ArrayList<>();
        for (VirtualBlockWorld.SpriteNode node : ordered) {
            if (node == null || node.removed() || !node.blockNode()) continue;
            Block block = node.block();
            if (block == null || block == Blocks.AIR) continue;
            BlockState state = resolveBlockState(block, node.blockState());
            BlockPos logicalPos = logicalBlockPos(node);
            sceneStates.put(logicalPos, state);
            sceneBlocks.add(new SceneBlock(node, block, state, logicalPos, collisionCenterX(node), collisionCenterY(node)));
        }

        SceneBlockView sceneView = new SceneBlockView(sceneStates);
        for (SceneBlock sceneBlock : sceneBlocks) {
            VirtualBlockWorld.SpriteNode node = sceneBlock.node();
            Block block = sceneBlock.block();
            BlockState state = sceneBlock.state();
            float centerX = sceneBlock.centerX();
            float centerY = sceneBlock.centerY();
            float halfCell = cellPixels * 0.5F;
            interactionTargetsByLayer.computeIfAbsent(node.simulationLayer(), ignored -> new ArrayList<>()).add(
                    new BlockTarget(node.id(), node.simulationLayer(),
                            centerX - halfCell, centerY - halfCell, centerX + halfCell, centerY + halfCell,
                            centerX, centerY));
            VoxelShape shape;
            try {
                shape = state.getCollisionShape(sceneView, sceneBlock.logicalPos());
            } catch (RuntimeException ignored) {
                node.data("__koil_geometry_error", "collision_shape_exception");
                continue;
            }
            if (shape == null || shape.isEmpty()) {
                node.data("__koil_geometry_box_count", 0);
                node.data("__koil_geometry_block_view", "detached_scene");
                continue;
            }

            List<SolidBox> projected = new ArrayList<>();
            for (Box box : shape.getBoundingBoxes()) {
                SolidBox solid = project(node, block, state, box, centerX, centerY);
                if (solid != null) projected.add(solid);
            }
            if (projected.isEmpty()) continue;
            solidsByBlock.put(node.id(), List.copyOf(projected));
            solidsByLayer.computeIfAbsent(node.simulationLayer(), ignored -> new ArrayList<>()).addAll(projected);
            node.data("__koil_geometry_box_count", projected.size());
            node.data("__koil_geometry_projection", projection.name().toLowerCase(Locale.ROOT));
            node.data("__koil_geometry_block_view", "detached_scene");
        }
    }

    private BlockPos logicalBlockPos(VirtualBlockWorld.SpriteNode node) {
        int x = intData(node == null ? null : node.data("__koil_vw_x"),
                node == null ? 0 : Math.round(node.x() / cellPixels));
        int y = intData(node == null ? null : node.data("__koil_vw_y"),
                node == null ? 0 : node.simulationLayer());
        int z = intData(node == null ? null : node.data("__koil_vw_z"),
                node == null ? 0 : Math.round(node.y() / cellPixels));
        return new BlockPos(x, y, z);
    }

    /**
     * Sweeps one dynamic actor from its frame-start position to its requested position.
     * Returns every blocking contact encountered during the sweep.
     */
    public List<Contact> resolveMotion(VirtualBlockWorld.SpriteNode actor) {
        if (!collidesWithWorld(actor)) return List.of();
        List<SolidBox> solids = solidsByLayer.get(actor.simulationLayer());
        if (solids == null || solids.isEmpty()) {
            publishNoContact(actor);
            return List.of();
        }

        float halfWidth = clampExtent(actor.halfWidth());
        float halfHeight = clampExtent(actor.halfHeight());
        float startX = actor.previousX();
        float startY = actor.previousY();
        float targetX = actor.x();
        float targetY = actor.y();
        float x = startX;
        float y = startY;

        // If an actor was spawned by another system already inside geometry, make a
        // deterministic correction before sweeping its requested frame movement.
        float[] start = depenetratePoint(x, y, halfWidth, halfHeight, solids);
        x = start[0];
        y = start[1];

        float remainingX = targetX - x;
        float remainingY = targetY - y;
        List<Contact> contacts = new ArrayList<>();
        for (int iteration = 0; iteration < MAX_SWEEP_ITERATIONS; iteration++) {
            if (Math.abs(remainingX) < 0.0001F && Math.abs(remainingY) < 0.0001F) break;
            SweepHit hit = earliestHit(x, y, remainingX, remainingY, halfWidth, halfHeight, solids);
            if (hit == null) {
                x += remainingX;
                y += remainingY;
                remainingX = 0.0F;
                remainingY = 0.0F;
                break;
            }

            float travel = Math.max(0.0F, hit.time() - SWEEP_EPSILON / Math.max(1.0F,
                    Math.abs(remainingX) + Math.abs(remainingY)));
            x += remainingX * travel;
            y += remainingY * travel;
            float impact = Math.abs(hit.normalX() != 0.0F ? actor.velocityX() : actor.velocityY());
            boolean grounded = hit.normalY() < -0.5F;
            contacts.add(new Contact(hit.box().blockId(), hit.box().block(), hit.box().state(),
                    hit.normalX(), hit.normalY(), impact, grounded));
            applyVelocityResponse(actor, hit.box(), hit.normalX(), hit.normalY(), impact);

            float leftover = Math.max(0.0F, 1.0F - hit.time());
            remainingX *= leftover;
            remainingY *= leftover;
            if (hit.normalX() != 0.0F) remainingX = 0.0F;
            if (hit.normalY() != 0.0F) remainingY = 0.0F;

            x += hit.normalX() * SWEEP_EPSILON;
            y += hit.normalY() * SWEEP_EPSILON;
        }

        float[] corrected = depenetratePoint(x, y, halfWidth, halfHeight, solids);
        actor.position(corrected[0], corrected[1]);
        publishContacts(actor, contacts);
        return List.copyOf(contacts);
    }

    /** Corrects overlap introduced after actor/actor collision resolution or block motion. */
    public boolean depenetrate(VirtualBlockWorld.SpriteNode actor) {
        if (!collidesWithWorld(actor)) return false;
        List<SolidBox> solids = solidsByLayer.get(actor.simulationLayer());
        if (solids == null || solids.isEmpty()) return false;
        float[] corrected = depenetratePoint(actor.x(), actor.y(), clampExtent(actor.halfWidth()),
                clampExtent(actor.halfHeight()), solids);
        if (Math.abs(corrected[0] - actor.x()) < 0.0001F && Math.abs(corrected[1] - actor.y()) < 0.0001F) return false;
        actor.position(corrected[0], corrected[1]);
        return true;
    }

    /** Returns physical block solids touching or overlapping the actor AABB. */
    public Set<Long> touchingBlockIds(VirtualBlockWorld.SpriteNode actor) {
        if (actor == null || actor.removed()) return Set.of();
        List<SolidBox> solids = solidsByLayer.get(actor.simulationLayer());
        if (solids == null || solids.isEmpty()) return Set.of();
        float hw = clampExtent(actor.halfWidth()) + CONTACT_EPSILON;
        float hh = clampExtent(actor.halfHeight()) + CONTACT_EPSILON;
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (SolidBox solid : solids) {
            if (intersects(actor.x() - hw, actor.y() - hh, actor.x() + hw, actor.y() + hh,
                    solid.left(), solid.top(), solid.right(), solid.bottom())) {
                ids.add(solid.blockId());
            }
        }
        return Set.copyOf(ids);
    }

    /**
     * Returns logical block cells that are close enough to be an explicit use target,
     * including non-solid blocks such as water. This is intentionally separate from
     * physical collision contact.
     */
    public Set<Long> interactionBlockIds(VirtualBlockWorld.SpriteNode actor) {
        if (actor == null || actor.removed()) return Set.of();
        List<BlockTarget> targets = interactionTargetsByLayer.get(actor.simulationLayer());
        if (targets == null || targets.isEmpty()) return Set.of();
        float hw = clampExtent(actor.halfWidth()) + CONTACT_EPSILON;
        float hh = clampExtent(actor.halfHeight()) + CONTACT_EPSILON;
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (BlockTarget target : targets) {
            if (intersects(actor.x() - hw, actor.y() - hh, actor.x() + hw, actor.y() + hh,
                    target.left(), target.top(), target.right(), target.bottom())) {
                ids.add(target.blockId());
            }
        }
        return Set.copyOf(ids);
    }

    /** Chooses the nearest explicit-use block cell with deterministic id tie-breaking. */
    public long bestInteractionBlockId(VirtualBlockWorld.SpriteNode actor) {
        if (actor == null || actor.removed()) return -1L;
        Set<Long> candidates = interactionBlockIds(actor);
        if (candidates.isEmpty()) return -1L;
        List<BlockTarget> targets = interactionTargetsByLayer.get(actor.simulationLayer());
        if (targets == null) return -1L;
        BlockTarget best = null;
        float bestDistance = Float.POSITIVE_INFINITY;
        for (BlockTarget target : targets) {
            if (!candidates.contains(target.blockId())) continue;
            float dx = actor.x() - target.centerX();
            float dy = actor.y() - target.centerY();
            float distance = dx * dx + dy * dy;
            if (best == null || distance < bestDistance - 0.0001F
                    || (Math.abs(distance - bestDistance) < 0.0001F && target.blockId() < best.blockId())) {
                best = target;
                bestDistance = distance;
            }
        }
        return best == null ? -1L : best.blockId();
    }

    public boolean actorTouchesBlock(VirtualBlockWorld.SpriteNode actor, long blockId) {
        return touchingBlockIds(actor).contains(blockId);
    }

    public long firstTouchingBlockId(VirtualBlockWorld.SpriteNode actor) {
        return touchingBlockIds(actor).stream().min(Long::compareTo).orElse(-1L);
    }

    public int solidBoxCount() {
        int count = 0;
        for (List<SolidBox> solids : solidsByLayer.values()) count += solids.size();
        return count;
    }

    private float collisionCenterX(VirtualBlockWorld.SpriteNode node) {
        if (node != null && node.dragging() && bool(node.data("__koil_vw_grid_locked"))) {
            return floatData(node.data("__koil_vw_x"), node.x() / cellPixels) * cellPixels;
        }
        return node == null ? 0.0F : node.x();
    }

    private float collisionCenterY(VirtualBlockWorld.SpriteNode node) {
        if (node != null && node.dragging() && bool(node.data("__koil_vw_grid_locked"))) {
            return floatData(node.data("__koil_vw_z"), node.y() / cellPixels) * cellPixels;
        }
        return node == null ? 0.0F : node.y();
    }

    private SolidBox project(VirtualBlockWorld.SpriteNode node, Block block, BlockState state, Box box,
                             float centerX, float centerY) {
        double hMin;
        double hMax;
        double vMin;
        double vMax;
        boolean verticalIsMinecraftY = projection != Projection.XZ;
        switch (projection) {
            case XY -> {
                hMin = box.minX;
                hMax = box.maxX;
                vMin = box.minY;
                vMax = box.maxY;
            }
            case ZY -> {
                hMin = box.minZ;
                hMax = box.maxZ;
                vMin = box.minY;
                vMax = box.maxY;
            }
            case XZ -> {
                hMin = box.minX;
                hMax = box.maxX;
                vMin = box.minZ;
                vMax = box.maxZ;
            }
            default -> throw new IllegalStateException("Unhandled projection " + projection);
        }
        if (hMax - hMin <= MIN_EXTENT || vMax - vMin <= MIN_EXTENT) return null;
        float cellLeft = centerX - cellPixels * 0.5F;
        float cellTop = centerY - cellPixels * 0.5F;
        float left = cellLeft + (float) hMin * cellPixels;
        float right = cellLeft + (float) hMax * cellPixels;
        float top;
        float bottom;
        if (verticalIsMinecraftY) {
            top = cellTop + (1.0F - (float) vMax) * cellPixels;
            bottom = cellTop + (1.0F - (float) vMin) * cellPixels;
        } else {
            top = cellTop + (float) vMin * cellPixels;
            bottom = cellTop + (float) vMax * cellPixels;
        }
        if (right - left <= MIN_EXTENT || bottom - top <= MIN_EXTENT) return null;
        return new SolidBox(node.id(), block, state, node.simulationLayer(), left, top, right, bottom,
                clamp(block.getSlipperiness(), 0.0F, 1.2F), block == Blocks.SLIME_BLOCK, block == Blocks.HONEY_BLOCK);
    }

    private SweepHit earliestHit(float x, float y, float dx, float dy, float hw, float hh, List<SolidBox> solids) {
        SweepHit best = null;
        for (SolidBox solid : solids) {
            SweepHit hit = sweepAgainst(x, y, dx, dy, hw, hh, solid);
            if (hit == null) continue;
            if (best == null || hit.time() < best.time() - 0.00001F
                    || (Math.abs(hit.time() - best.time()) < 0.00001F && hit.box().blockId() < best.box().blockId())) {
                best = hit;
            }
        }
        return best;
    }

    private SweepHit sweepAgainst(float x, float y, float dx, float dy, float hw, float hh, SolidBox solid) {
        float left = solid.left() - hw;
        float right = solid.right() + hw;
        float top = solid.top() - hh;
        float bottom = solid.bottom() + hh;
        if (x > left && x < right && y > top && y < bottom) return null;

        float txEntry;
        float txExit;
        if (Math.abs(dx) < 0.000001F) {
            if (x <= left || x >= right) return null;
            txEntry = Float.NEGATIVE_INFINITY;
            txExit = Float.POSITIVE_INFINITY;
        } else if (dx > 0.0F) {
            txEntry = (left - x) / dx;
            txExit = (right - x) / dx;
        } else {
            txEntry = (right - x) / dx;
            txExit = (left - x) / dx;
        }

        float tyEntry;
        float tyExit;
        if (Math.abs(dy) < 0.000001F) {
            if (y <= top || y >= bottom) return null;
            tyEntry = Float.NEGATIVE_INFINITY;
            tyExit = Float.POSITIVE_INFINITY;
        } else if (dy > 0.0F) {
            tyEntry = (top - y) / dy;
            tyExit = (bottom - y) / dy;
        } else {
            tyEntry = (bottom - y) / dy;
            tyExit = (top - y) / dy;
        }

        float entry = Math.max(txEntry, tyEntry);
        float exit = Math.min(txExit, tyExit);
        if (entry > exit || exit < 0.0F || entry < 0.0F || entry > 1.0F) return null;

        float nx = 0.0F;
        float ny = 0.0F;
        if (txEntry > tyEntry) nx = dx > 0.0F ? -1.0F : 1.0F;
        else ny = dy > 0.0F ? -1.0F : 1.0F;
        return new SweepHit(solid, entry, nx, ny);
    }

    private float[] depenetratePoint(float x, float y, float hw, float hh, List<SolidBox> solids) {
        float outX = x;
        float outY = y;
        for (int iteration = 0; iteration < MAX_DEPENETRATION_ITERATIONS; iteration++) {
            SolidBox chosen = null;
            float chosenDx = 0.0F;
            float chosenDy = 0.0F;
            float bestDistance = Float.POSITIVE_INFINITY;
            for (SolidBox solid : solids) {
                float actorLeft = outX - hw;
                float actorRight = outX + hw;
                float actorTop = outY - hh;
                float actorBottom = outY + hh;
                if (!intersectsStrict(actorLeft, actorTop, actorRight, actorBottom,
                        solid.left(), solid.top(), solid.right(), solid.bottom())) continue;

                float moveLeft = solid.left() - actorRight - SWEEP_EPSILON;
                float moveRight = solid.right() - actorLeft + SWEEP_EPSILON;
                float moveUp = solid.top() - actorBottom - SWEEP_EPSILON;
                float moveDown = solid.bottom() - actorTop + SWEEP_EPSILON;
                float dx = Math.abs(moveLeft) < Math.abs(moveRight) ? moveLeft : moveRight;
                float dy = Math.abs(moveUp) < Math.abs(moveDown) ? moveUp : moveDown;
                float candidateDx = Math.abs(dx) <= Math.abs(dy) ? dx : 0.0F;
                float candidateDy = candidateDx == 0.0F ? dy : 0.0F;
                float distance = Math.abs(candidateDx) + Math.abs(candidateDy);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    chosen = solid;
                    chosenDx = candidateDx;
                    chosenDy = candidateDy;
                }
            }
            if (chosen == null) break;
            outX += chosenDx;
            outY += chosenDy;
        }
        return new float[]{outX, outY};
    }

    private void applyVelocityResponse(VirtualBlockWorld.SpriteNode actor, SolidBox solid,
                                       float normalX, float normalY, float impactSpeed) {
        float vx = actor.velocityX();
        float vy = actor.velocityY();
        playSurfaceCollisionSound(actor, solid, normalY, impactSpeed);
        float restitution = clamp(actor.restitution(), 0.0F, 1.05F);
        float surfaceFriction = clamp(actor.surfaceFriction(), 0.0F, 1.0F);

        if (normalY < -0.5F) {
            if (solid.slime() && !actor.dragging()) {
                vy = -Math.max(11.0F, Math.abs(vy) * Math.max(0.82F, restitution));
                vx *= 0.94F;
            } else if (solid.honey()) {
                vy = 0.0F;
                vx *= 0.42F;
            } else {
                vy = impactSpeed > 18.0F && restitution > 0.08F ? -Math.abs(vy) * restitution : 0.0F;
                float blockRetention = clamp(0.42F + solid.slipperiness() * 0.58F, 0.42F, 0.995F);
                vx *= blockRetention * surfaceFriction;
            }
        } else if (normalY > 0.5F) {
            vy = impactSpeed > 20.0F ? Math.abs(vy) * restitution : 0.0F;
        }

        if (normalX != 0.0F) {
            vx = impactSpeed > 24.0F && restitution > 0.10F ? -vx * restitution : 0.0F;
            if (solid.honey()) vy *= 0.45F;
            else vy *= clamp(0.70F + surfaceFriction * 0.25F, 0.70F, 0.95F);
        }
        if (actor.dragging()) {
            if (normalX != 0.0F) vx = 0.0F;
            if (normalY != 0.0F) vy = 0.0F;
        }
        actor.velocity(vx, vy);
    }

    private void playSurfaceCollisionSound(VirtualBlockWorld.SpriteNode actor, SolidBox solid,
                                           float normalY, float impactSpeed) {
        if (actor == null || solid == null || actor.dragging() || impactSpeed < 20.0F) return;
        long now = System.nanoTime();
        long last = longData(actor.data("__koil_world_collision_sound_nanos"), Long.MIN_VALUE);
        if (last != Long.MIN_VALUE && now - last < 75_000_000L) return;
        try {
            BlockSoundGroup group = solid.state().getSoundGroup();
            if (group == null) return;
            SoundEvent event = normalY < -0.5F ? group.getFallSound() : group.getHitSound();
            if (event == null) return;
            float volume = clamp(group.getVolume() * 0.34F, 0.08F, 0.55F);
            actor.playSound(event, volume, group.getPitch());
            actor.data("__koil_world_collision_sound_nanos", now);
        } catch (RuntimeException ignored) { }
    }

    private void publishContacts(VirtualBlockWorld.SpriteNode actor, List<Contact> contacts) {
        if (actor == null) return;
        if (contacts == null || contacts.isEmpty()) {
            publishNoContact(actor);
            return;
        }
        Contact last = contacts.get(contacts.size() - 1);
        actor.data("__koil_world_collision", true);
        actor.data("__koil_world_grounded", contacts.stream().anyMatch(Contact::grounded));
        actor.data("__koil_world_collision_block", registryId(last.block()));
        actor.data("__koil_world_collision_block_sprite", last.blockId());
        actor.data("__koil_world_collision_nx", last.normalX());
        actor.data("__koil_world_collision_ny", last.normalY());
        actor.data("__koil_world_collision_impact", last.impactSpeed());
    }

    private void publishNoContact(VirtualBlockWorld.SpriteNode actor) {
        if (actor == null) return;
        actor.data("__koil_world_collision", false);
        actor.data("__koil_world_grounded", false);
        actor.data("__koil_world_collision_block", "");
        actor.data("__koil_world_collision_block_sprite", -1L);
        actor.data("__koil_world_collision_nx", 0.0F);
        actor.data("__koil_world_collision_ny", 0.0F);
        actor.data("__koil_world_collision_impact", 0.0F);
    }

    private boolean collidesWithWorld(VirtualBlockWorld.SpriteNode actor) {
        if (actor == null || actor.removed() || actor.blockNode()) return false;
        if (actor.itemNode() || actor.entityNode()) return true;
        return bool(actor.data("world_collision")) || bool(actor.data("__koil_world_collision_enabled"));
    }

    private static boolean intersects(float aLeft, float aTop, float aRight, float aBottom,
                                      float bLeft, float bTop, float bRight, float bBottom) {
        return aRight >= bLeft && aLeft <= bRight && aBottom >= bTop && aTop <= bBottom;
    }

    private static boolean intersectsStrict(float aLeft, float aTop, float aRight, float aBottom,
                                            float bLeft, float bTop, float bRight, float bBottom) {
        return aRight > bLeft && aLeft < bRight && aBottom > bTop && aTop < bBottom;
    }

    private static float clampExtent(float value) {
        return Math.max(0.35F, Math.min(48.0F, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean bool(String value) {
        return value != null && ("true".equalsIgnoreCase(value) || "1".equals(value)
                || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value));
    }

    private static int intData(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static float floatData(String value, float fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longData(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String registryId(Block block) {
        if (block == null) return "minecraft:air";
        Identifier id = Registries.BLOCK.getId(block);
        return id == null ? "minecraft:air" : id.toString();
    }

    private static BlockState resolveBlockState(Block block, String signature) {
        if (block == null) return Blocks.AIR.getDefaultState();
        BlockState state = block.getDefaultState();
        if (signature == null || signature.isBlank()) return state;
        Map<String, String> values = parseSignature(signature);
        for (Property<?> property : block.getStateManager().getProperties()) {
            String value = values.get(property.getName().toLowerCase(Locale.ROOT));
            if (value != null) state = withParsedProperty(state, property, value);
        }
        return state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState withParsedProperty(BlockState state, Property property, String value) {
        try {
            Optional parsed = property.parse(value);
            if (parsed.isPresent()) return state.with(property, (Comparable) parsed.get());
        } catch (RuntimeException ignored) { }
        return state;
    }

    private static Map<String, String> parseSignature(String signature) {
        Map<String, String> result = new HashMap<>();
        if (signature == null || signature.isBlank()) return result;
        for (String part : signature.split(",")) {
            int split = part.indexOf('=');
            if (split <= 0 || split >= part.length() - 1) continue;
            String key = part.substring(0, split).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(split + 1).trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty() && !value.isEmpty()) result.put(key, value);
        }
        return result;
    }
}
