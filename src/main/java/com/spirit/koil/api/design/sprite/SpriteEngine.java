package com.spirit.koil.api.design.sprite;

import com.spirit.koil.api.design.sprite.actor.Actor;
import com.spirit.koil.api.design.sprite.actor.FallingBlockActor;
import com.spirit.koil.api.design.sprite.actor.ItemActor;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.debug.SceneDiagnostics;
import com.spirit.koil.api.design.sprite.debug.SceneSelfTest;
import com.spirit.koil.api.design.sprite.minecraft.MinecraftStateCodec;
import com.spirit.koil.api.design.sprite.gameplay.GameplayCoverageReport;
import com.spirit.koil.api.design.sprite.physics.KoilPhysicsWorld;
import com.spirit.koil.api.design.sprite.systems.FluidSystem;
import com.spirit.koil.api.design.sprite.world.BlockCell;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import com.spirit.koil.api.design.sprite.world.Scene;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Authoritative root for Koil's detached Terraria-inspired 2D Minecraft scene engine.
 *
 * <p>Rev E moves actor motion into {@link KoilPhysicsWorld}. Legacy particle objects
 * are compatibility render/input proxies only. New systems should read/write the
 * scene, actors and typed commands rather than particle metadata.</p>
 */
public final class SpriteEngine {
    private final Scene scene = new Scene();
    private final Map<Long, SceneCellPos> legacyBlockCells = new HashMap<>();
    private final Map<Long, SceneCellPos> legacyFluidCells = new HashMap<>();
    private final Map<SceneCellPos, Long> legacyCellOwners = new HashMap<>();
    private final Set<Long> previousLegacyBlocks = new HashSet<>();
    private final Set<Long> currentLegacyBlocks = new HashSet<>();
    private final Set<Long> previousLegacyFluids = new HashSet<>();
    private final Set<Long> currentLegacyFluids = new HashSet<>();
    private final Set<Long> previousLegacyActors = new HashSet<>();
    private final Set<Long> currentLegacyActors = new HashSet<>();

    public Scene scene() { return scene; }
    public SceneSelfTest.Report runDetachedSelfTest() { return SceneSelfTest.run(); }
    public GameplayCoverageReport.Summary gameplayCoverage() {
        return GameplayCoverageReport.snapshot(scene.blockInteractions());
    }
    public String gameplayCoverageSummary() { return gameplayCoverage().compact(); }
    public boolean requiresGameplayWorld() { return false; }
    public String runtimeMode() { return "detached_scene"; }

    public void advanceFrame(float deltaSeconds) { scene.advanceFrame(deltaSeconds); }
    public void setViewport(float width, float height) { scene.physics().setViewport(width, height); }

    /** Scene-native explicit block use. Returns false only when no detached adapter owns the action. */
    public boolean useBlock(SceneCellPos position) {
        return position != null && scene.blockInteractions().useBlock(position);
    }

    /** Explicit projected block use retaining the pointer side for native interactions such as bells. */
    public boolean useBlock(SceneCellPos position, float pointerX, float pointerY) {
        return position != null && scene.blockInteractions().useBlock(position, pointerX, pointerY);
    }

    /** Whether scene-native gameplay owns this item actor family, even if the
     *  attempted action is invalid for the current target. Used to prevent legacy
     *  double execution after a subsystem has migrated. */
    public boolean ownsItemGameplay(long actorId) {
        Actor raw = scene.actors().get(actorId);
        return raw instanceof ItemActor item && scene.itemInteractions().owns(item.stack());
    }

    /** Whether scene-native bare-use gameplay owns the block at this position. */
    public boolean ownsBlockGameplay(SceneCellPos position) {
        return position != null && scene.blockInteractions().owns(scene.blocks().getBlockState(position));
    }

    /** Starts an item use action. Immediate throwables launch here; charged families arm here. */
    public boolean beginItemUse(long actorId, float targetX, float targetY) {
        return scene.itemInteractions().beginUse(actorId, targetX, targetY);
    }

    /** Releases charged item families such as bows/tridents. */
    public boolean releaseItemUse(long actorId, float targetX, float targetY) {
        return scene.itemInteractions().releaseUse(actorId, targetX, targetY);
    }

    /** Explicit item-on-block use. Ordinary contact never routes through this API. */
    public boolean useItemOnBlock(long actorId, SceneCellPos position) {
        return position != null && scene.itemInteractions().useOnBlock(actorId, position);
    }

    /** Item use at an empty scene cell, used for block placement and bucket emptying. */
    public boolean useItemAtCell(long actorId, SceneCellPos position) {
        return position != null && scene.itemInteractions().useAtCell(actorId, position);
    }

    /** Scene-native mutation API. New engine systems should use these commands. */
    public void setBlock(SceneCellPos position, BlockState state) {
        scene.commands().submit(target -> {
            if (state != null && state.getBlock() instanceof FluidBlock) {
                target.blocks().remove(position);
                FluidState fluid = state.getFluidState();
                if (fluid != null && !fluid.isEmpty()) target.fluids().set(position, fluid);
                return;
            }
            target.blocks().set(position, state);
        });
    }

    public void setFluid(SceneCellPos position, Fluid fluid) {
        scene.commands().submit(target -> target.fluidSystem().setSceneSource(position, fluid));
    }

    public void removeBlock(SceneCellPos position) {
        scene.commands().submit(target -> target.blocks().remove(position));
    }

    public long spawnItem(ItemStack stack, float x, float y, int depth, float vx, float vy) {
        long id = scene.actors().allocateNativeId();
        ItemStack copy = stack == null ? ItemStack.EMPTY : stack.copy();
        scene.commands().submit(target -> {
            ItemActor actor = new ItemActor(id, Actor.Authority.SCENE, copy, x, y, depth);
            actor.setVelocity(vx, vy);
            target.actors().put(actor);
        });
        return id;
    }

    public long promoteBlockToFallingActor(SceneCellPos position, float vx, float vy) {
        long id = scene.actors().allocateNativeId();
        scene.commands().submit(target -> {
            BlockCell cell = target.blocks().get(position);
            if (cell == null || cell.blockState() == null || cell.blockState().isAir()) return;
            target.blocks().remove(position);
            float x = target.projection().cellCenterScreenX(position);
            float y = target.projection().cellCenterScreenY(position);
            FallingBlockActor actor = new FallingBlockActor(
                    id, Actor.Authority.SCENE, cell.blockState(), position,
                    x, y, target.projection().depthCoordinate(position));
            actor.captureSourceCell(cell);
            actor.setVelocity(vx, vy);
            actor.setSourceBlockAuthority(BlockCell.Authority.SCENE, -1L);
            target.actors().put(actor);
        });
        return id;
    }

    public void landFallingBlock(long actorId, SceneCellPos position) {
        scene.commands().submit(target -> {
            Actor actor = target.actors().get(actorId);
            if (!(actor instanceof FallingBlockActor falling)) return;
            BlockCell landed = target.blocks().set(position, falling.blockState());
            if (landed != null) {
                target.blocks().setFlags(position, falling.sourceFlags());
                for (Map.Entry<String, String> data : falling.sourceRuntimeData().entrySet())
                    target.blocks().putRuntimeData(position, data.getKey(), data.getValue());
                for (Map.Entry<String, String> data : falling.sourceBlockEntityData().entrySet())
                    target.blocks().putBlockEntityData(position, data.getKey(), data.getValue());
                target.blocks().restartLifetime(position);
            }
            target.actors().remove(actorId);
            target.events().publish(new com.spirit.koil.api.design.sprite.core.SceneEvent.ActorLanded(
                    actorId, position, target.clock().gameTick()));
        });
    }

    public Actor actor(long id) { return scene.actors().get(id); }

    public SceneCellPos legacyBlockCell(long sourceId) {
        SceneCellPos mapped = legacyBlockCells.get(sourceId);
        if (mapped != null) return mapped;
        mapped = legacyFluidCells.get(sourceId);
        if (mapped != null) return mapped;
        mapped = scene.blocks().findLifetimeSource(sourceId);
        if (mapped == null) mapped = scene.blocks().findLegacyProxy(sourceId);
        return mapped != null ? mapped : scene.fluids().findLegacySeed(sourceId);
    }

    public boolean isLegacyFluidProxy(long sourceId) {
        SceneCellPos pos = legacyFluidCells.get(sourceId);
        return pos != null && scene.fluids().isLegacySeed(pos, sourceId);
    }

    public void reset() {
        scene.reset();
        legacyBlockCells.clear();
        legacyFluidCells.clear();
        legacyCellOwners.clear();
        previousLegacyBlocks.clear();
        currentLegacyBlocks.clear();
        previousLegacyFluids.clear();
        currentLegacyFluids.clear();
        previousLegacyActors.clear();
        currentLegacyActors.clear();
    }

    public void setCellPixels(float pixels) { scene.projection().setCellPixels(pixels); }
    public void setProjection(String projection) { scene.projection().setMode(projection); }

    /**
     * Authoring input for static scene blocks. Mouse-wheel orientation changes
     * mutate the authoritative BlockState, then immediately reconcile multipart
     * structures and neighbor-derived state so rendering and collision observe
     * exactly the same result in the same frame.
     */
    public boolean cycleBlockOrientation(SceneCellPos position, int steps) {
        if (position == null || steps == 0) return false;
        boolean changed = scene.blockOrientation().cycle(position, steps);
        if (!changed) return false;
        scene.multipartBlocks().reconcile();
        scene.neighborStates().reconcile();
        return true;
    }

    /** Rotates a scene item actor without handing transform authority back to the legacy proxy. */
    public boolean rotateItem(long actorId, float degrees) {
        Actor raw = scene.actors().get(actorId);
        if (!(raw instanceof ItemActor item) || !Float.isFinite(degrees) || Math.abs(degrees) < 0.0001F) return false;
        item.setRotation(item.rotation() + degrees);
        item.setAngularVelocity(0.0F);
        return true;
    }

    public BlockState blockState(SceneCellPos position) {
        return position == null ? null : scene.blocks().getBlockState(position);
    }

    /**
     * Returns the legacy proxy source that owns the authoritative block cell, if
     * one exists. Multipart child cells (currently door uppers) are redirected to
     * their lower anchor first so scrolling either half updates the same proxy
     * metadata before the next compatibility sync.
     */
    public long legacyBlockSourceId(SceneCellPos position) {
        if (position == null) return -1L;
        SceneCellPos anchor = scene.multipartBlocks().anchor(position);
        BlockCell cell = anchor == null ? null : scene.blocks().get(anchor);
        if (cell == null) return -1L;
        if (cell.lifetimeSourceId() >= 0L) return cell.lifetimeSourceId();
        return cell.authority() == BlockCell.Authority.LEGACY_PROXY ? cell.sourceId() : -1L;
    }

    /**
     * Hit-tests the authoritative scene grid directly. This deliberately does not
     * depend on a legacy Particle proxy, so derived door halves and future native
     * scene blocks remain authorable. Higher depth lanes win because they render
     * later in the default scene compositor.
     */
    public SceneCellPos sceneBlockAtScreen(float screenX, float screenY) {
        float half = scene.projection().cellPixels() * 0.5F;
        SceneCellPos best = null;
        int bestDepth = Integer.MIN_VALUE;
        for (BlockGrid.Entry entry : scene.blocks().entries()) {
            if (entry == null || entry.cell() == null || entry.cell().blockState() == null
                    || entry.cell().blockState().isAir()) continue;
            SceneCellPos pos = entry.position();
            float cx = scene.projection().cellCenterScreenX(pos);
            float cy = scene.projection().cellCenterScreenY(pos);
            if (Math.abs(screenX - cx) > half || Math.abs(screenY - cy) > half) continue;
            int depth = scene.projection().depthCoordinate(pos);
            if (best == null || depth >= bestDepth) {
                best = pos;
                bestDepth = depth;
            }
        }
        return best;
    }

    public void beginLegacySync() {
        currentLegacyBlocks.clear();
        currentLegacyFluids.clear();
        currentLegacyActors.clear();
    }

    /**
     * Synchronizes a legacy registered block into the scene. If scene physics has
     * already promoted this source into a FallingBlockActor, the actor remains the
     * authority and the old block particle is treated only as its visual proxy.
     */
    public void syncLegacyBlock(long sourceId, Block block, String stateSignature,
                                float screenX, float screenY, int depth,
                                boolean falling, float vx, float vy, Map<String, String> authoredOverrides) {
        if (block == null) return;
        BlockState state = MinecraftStateCodec.resolve(block, stateSignature);
        if (state == null) return;

        if (block instanceof FluidBlock) {
            SceneCellPos cell = scene.projection().screenToCellAtDepth(screenX, screenY, depth);
            currentLegacyFluids.add(sourceId);
            removeLegacyBlockMapping(sourceId);
            SceneCellPos previousFluid = legacyFluidCells.put(sourceId, cell);
            if (previousFluid != null && !previousFluid.equals(cell)) {
                scene.fluidSystem().removeLegacySeed(previousFluid, sourceId);
            }
            FluidState fluidState = state.getFluidState();
            if (fluidState != null && !fluidState.isEmpty()) {
                scene.fluidSystem().setLegacySeed(cell, fluidState, sourceId);
            }
            Actor existing = scene.actors().get(sourceId);
            if (existing != null) scene.actors().remove(sourceId);
            return;
        }

        SceneCellPos previousFluid = legacyFluidCells.remove(sourceId);
        if (previousFluid != null) scene.fluidSystem().removeLegacySeed(previousFluid, sourceId);

        Actor existingActor = scene.actors().get(sourceId);
        if (existingActor instanceof FallingBlockActor fallingActor) {
            currentLegacyActors.add(sourceId);
            removeLegacyBlockMapping(sourceId);
            if (falling) fallingActor.setVelocity(vx, vy);
            return;
        }

        SceneCellPos cell = scene.projection().screenToCellAtDepth(screenX, screenY, depth);
        if (falling) {
            removeLegacyBlockMapping(sourceId);
            currentLegacyActors.add(sourceId);
            if (existingActor != null) scene.actors().remove(sourceId);
            FallingBlockActor actor = new FallingBlockActor(sourceId, Actor.Authority.LEGACY_PROXY,
                    state, cell, screenX, screenY, depth);
            actor.setSourceBlockAuthority(BlockCell.Authority.LEGACY_PROXY, sourceId);
            actor.setVelocity(vx, vy);
            scene.actors().put(actor);
            return;
        }

        currentLegacyBlocks.add(sourceId);
        SceneCellPos previousCell = legacyBlockCells.put(sourceId, cell);
        if (previousCell != null && !previousCell.equals(cell)
                && Long.valueOf(sourceId).equals(legacyCellOwners.get(previousCell))) {
            legacyCellOwners.remove(previousCell);
            scene.blocks().removeLifetimeSource(previousCell, sourceId);
        }
        Long displacedOwner = legacyCellOwners.put(cell, sourceId);
        if (displacedOwner != null && displacedOwner.longValue() != sourceId) {
            legacyBlockCells.remove(displacedOwner);
        }
        BlockCell synced = scene.blocks().setLegacyProxy(cell, state, sourceId);
        if (synced != null && synced.authority() == BlockCell.Authority.LEGACY_PROXY && synced.sourceId() == sourceId) {
            applyLegacyAuthoringOverrides(cell, authoredOverrides);
        }
        if (existingActor instanceof FallingBlockActor) scene.actors().remove(sourceId);
    }


    private void applyLegacyAuthoringOverrides(SceneCellPos cell, Map<String, String> values) {
        if (cell == null || values == null || values.isEmpty()) return;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String key = entry.getKey().trim().toLowerCase(java.util.Locale.ROOT);
            String target = switch (key) {
                case "world_collision", "collide_world" -> "collision_enabled";
                case "alpha" -> "render_alpha";
                case "lifetime", "health", "max_health", "collision_enabled", "interactive",
                        "hardness", "blast_resistance", "slipperiness", "velocity_multiplier",
                        "jump_velocity_multiplier", "mass", "solidity", "gravity", "drag",
                        "restitution", "surface_friction", "temperature", "charge", "conductivity",
                        "flammability", "buoyancy", "render_alpha", "sensor_radius" -> key;
                default -> null;
            };
            if (target != null) scene.blocks().putRuntimeData(cell, target, entry.getValue());
        }
        if (values.containsKey("lifetime")) scene.blocks().restartLifetime(cell);
    }

    public void syncLegacyItem(long sourceId, ItemStack stack, float screenX, float screenY, int depth,
                               float vx, float vy, float rotation, float angularVelocity) {
        syncLegacyItem(sourceId, stack, screenX, screenY, depth, vx, vy, rotation, angularVelocity, false);
    }

    /**
     * Synchronizes a legacy registry-item proxy. Position stays physics-owned unless
     * the user is actively dragging the item. Velocity is accepted every frame so
     * pointer swipes/keyboard impulses remain compatible with the old input facade.
     */

    public void syncLegacyItem(long sourceId, ItemStack stack, float screenX, float screenY, int depth,
                               float vx, float vy, float rotation, float angularVelocity,
                               boolean externalPositionControl) {
        if (stack == null || stack.isEmpty()) return;
        currentLegacyActors.add(sourceId);
        Actor existing = scene.actors().get(sourceId);
        ItemActor actor;
        if (existing instanceof ItemActor itemActor) {
            actor = itemActor;
            actor.setStack(stack);
        } else {
            if (existing != null) scene.actors().remove(sourceId);
            actor = scene.actors().put(new ItemActor(sourceId, Actor.Authority.LEGACY_PROXY,
                    stack, screenX, screenY, depth));
            actor.syncExternal(screenX, screenY, vx, vy, rotation, angularVelocity, depth);
        }

        if (externalPositionControl) {
            actor.setKinematic(true);
            actor.syncExternal(screenX, screenY, vx, vy, rotation, angularVelocity, depth);
        } else {
            actor.setKinematic(false);
            actor.setDepth(depth);
            actor.setVelocity(vx, vy);
            actor.setAngularVelocity(angularVelocity);
        }
    }

    public void endLegacySync() {
        for (Long id : previousLegacyBlocks) {
            if (currentLegacyBlocks.contains(id)) continue;
            removeLegacyBlockMapping(id);
        }
        for (Long id : previousLegacyFluids) {
            if (currentLegacyFluids.contains(id)) continue;
            SceneCellPos pos = legacyFluidCells.remove(id);
            if (pos != null) scene.fluidSystem().removeLegacySeed(pos, id);
        }
        for (Long id : previousLegacyActors) {
            if (!currentLegacyActors.contains(id) && !currentLegacyBlocks.contains(id)) scene.actors().remove(id);
        }
        previousLegacyBlocks.clear();
        previousLegacyBlocks.addAll(currentLegacyBlocks);
        previousLegacyFluids.clear();
        previousLegacyFluids.addAll(currentLegacyFluids);
        previousLegacyActors.clear();
        previousLegacyActors.addAll(currentLegacyActors);
    }

    private void removeLegacyBlockMapping(long sourceId) {
        SceneCellPos previousCell = legacyBlockCells.remove(sourceId);
        if (previousCell != null && Long.valueOf(sourceId).equals(legacyCellOwners.get(previousCell))) {
            legacyCellOwners.remove(previousCell);
            scene.blocks().removeLifetimeSource(previousCell, sourceId);
        }
    }

    public SceneDiagnostics diagnostics() {
        KoilPhysicsWorld.Statistics physics = scene.physics().statistics();
        FluidSystem.Statistics fluid = scene.fluidSystem().statistics();
        return new SceneDiagnostics(
                runtimeMode(), false, scene.projection().mode().name().toLowerCase(java.util.Locale.ROOT),
                scene.clock().gameTick(), scene.clock().physicsStep(), scene.clock().renderAlpha(),
                scene.blocks().blockCount(), scene.blocks().chunkCount(),
                fluid.fluidCells(), fluid.sourceCells(), fluid.fallingCells(), scene.actors().size(),
                previousLegacyBlocks.size(), previousLegacyFluids.size(), previousLegacyActors.size(), scene.commands().size(),
                physics.staticColliders(), physics.contactsLastStep(), physics.sleepingActors(), physics.fallingActors());
    }
}
