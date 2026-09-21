package com.spirit.koil.api.design.sprite.world;

import com.spirit.koil.api.design.sprite.actor.ActorWorld;
import com.spirit.koil.api.design.sprite.core.SceneClock;
import com.spirit.koil.api.design.sprite.core.SceneCommandQueue;
import com.spirit.koil.api.design.sprite.core.SceneEnvironment;
import com.spirit.koil.api.design.sprite.core.SceneEventBus;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.gameplay.BellSystem;
import com.spirit.koil.api.design.sprite.gameplay.BlockInteractionSystem;
import com.spirit.koil.api.design.sprite.gameplay.ChestSystem;
import com.spirit.koil.api.design.sprite.gameplay.ItemInteractionSystem;
import com.spirit.koil.api.design.sprite.gameplay.GameplayOutcomeSystem;
import com.spirit.koil.api.design.sprite.gameplay.GameplayParticleSystem;
import com.spirit.koil.api.design.sprite.gameplay.JukeboxSystem;
import com.spirit.koil.api.design.sprite.gameplay.ProjectileSystem;
import com.spirit.koil.api.design.sprite.physics.KoilPhysicsWorld;
import com.spirit.koil.api.design.sprite.systems.BlockOrientationSystem;
import com.spirit.koil.api.design.sprite.systems.FluidSystem;
import com.spirit.koil.api.design.sprite.systems.MultipartBlockSystem;
import com.spirit.koil.api.design.sprite.systems.NeighborStateSystem;
import com.spirit.koil.api.design.sprite.systems.SceneLightingSystem;

/**
 * The one detached scene runtime that new Koil sprite-engine systems operate on.
 * No Minecraft world, server, player, network handler or chunk manager is owned here.
 */
public final class Scene {
    private final SceneClock clock = new SceneClock();
    private final SceneProjection projection = new SceneProjection();
    private final SceneEnvironment environment = new SceneEnvironment();
    private final SceneEventBus events = new SceneEventBus();
    private final SceneCommandQueue commands = new SceneCommandQueue();
    private final BlockGrid blocks = new BlockGrid(events, clock::gameTick);
    private final FluidGrid fluids = new FluidGrid(events, clock::gameTick);
    private final ActorWorld actors = new ActorWorld(events, clock::physicsStep);
    private final FluidSystem fluidSystem = new FluidSystem(
            blocks, fluids, projection, environment, events, clock::gameTick, clock::physicsStep);
    private final MultipartBlockSystem multipartBlocks = new MultipartBlockSystem(blocks, projection);
    private final BlockOrientationSystem blockOrientation = new BlockOrientationSystem(blocks, multipartBlocks);
    private final NeighborStateSystem neighborStates = new NeighborStateSystem(blocks, fluids, projection);
    private final SceneLightingSystem lighting = new SceneLightingSystem(blocks, fluids, projection, environment);
    private final ChestSystem chestSystem = new ChestSystem(blocks, projection, events, clock::gameTick);
    private final JukeboxSystem jukeboxSystem = new JukeboxSystem(blocks, actors, projection, events, clock::gameTick);
    private final BellSystem bellSystem = new BellSystem(projection, clock::gameTick);
    private final BlockInteractionSystem blockInteractions = new BlockInteractionSystem(
            blocks, multipartBlocks, actors, chestSystem, jukeboxSystem, bellSystem, projection, events, clock::gameTick);
    private final ProjectileSystem projectileSystem = new ProjectileSystem(
            actors, events, clock::physicsStep, clock::gameTick);
    private final GameplayParticleSystem gameplayParticles = new GameplayParticleSystem(
            blocks, projection, events, clock::gameTick);
    private final ItemInteractionSystem itemInteractions = new ItemInteractionSystem(
            blocks, fluids, actors, projection, projectileSystem, jukeboxSystem, gameplayParticles, events, clock::gameTick);
    private final GameplayOutcomeSystem gameplayOutcomes = new GameplayOutcomeSystem(
            blocks, actors, projection, gameplayParticles, events, clock::gameTick);
    private final KoilPhysicsWorld physics = new KoilPhysicsWorld(
            blocks, fluids, actors, projection, fluidSystem, events, clock::physicsStep, clock::gameTick);

    public SceneClock clock() { return clock; }
    public SceneProjection projection() { return projection; }
    public SceneEnvironment environment() { return environment; }
    public SceneEventBus events() { return events; }
    public SceneCommandQueue commands() { return commands; }
    public BlockGrid blocks() { return blocks; }
    public FluidGrid fluids() { return fluids; }
    public ActorWorld actors() { return actors; }
    public FluidSystem fluidSystem() { return fluidSystem; }
    public MultipartBlockSystem multipartBlocks() { return multipartBlocks; }
    public BlockOrientationSystem blockOrientation() { return blockOrientation; }
    public NeighborStateSystem neighborStates() { return neighborStates; }
    public SceneLightingSystem lighting() { return lighting; }
    public ChestSystem chestSystem() { return chestSystem; }
    public JukeboxSystem jukeboxSystem() { return jukeboxSystem; }
    public BellSystem bellSystem() { return bellSystem; }
    public BlockInteractionSystem blockInteractions() { return blockInteractions; }
    public ProjectileSystem projectileSystem() { return projectileSystem; }
    public GameplayParticleSystem gameplayParticles() { return gameplayParticles; }
    public ItemInteractionSystem itemInteractions() { return itemInteractions; }
    public GameplayOutcomeSystem gameplayOutcomes() { return gameplayOutcomes; }
    public KoilPhysicsWorld physics() { return physics; }

    public void advanceFrame(float deltaSeconds) {
        commands.drain(this);
        reconcileStaticState();
        clock.advance(deltaSeconds,
                () -> {
                    commands.drain(this);
                    reconcileStaticState();
                    expireAuthoredBlocks();
                    blockInteractions.minecraftTick();
                    chestSystem.minecraftTick();
                    jukeboxSystem.minecraftTick();
                    bellSystem.minecraftTick();
                    itemInteractions.minecraftTick();
                    gameplayOutcomes.minecraftTick();
                    gameplayParticles.minecraftTick();
                    fluidSystem.minecraftTick();
                    reconcileStaticState();
                    physics.minecraftTick();
                },
                () -> {
                    physics.step((float) SceneClock.PHYSICS_STEP_SECONDS);
                    projectileSystem.afterPhysicsStep();
                    gameplayOutcomes.afterPhysicsStep();
                });
    }

    /** Applies optional per-cell authoring lifetime without turning blocks back into particles. */
    private void expireAuthoredBlocks() {
        long tick = clock.gameTick();
        for (BlockGrid.Entry entry : new java.util.ArrayList<>(blocks.entries())) {
            if (entry == null || entry.cell() == null || entry.cell().authority() != BlockCell.Authority.SCENE) continue;
            float seconds = entry.cell().runtimeFloat("lifetime", -1.0F);
            if (seconds <= 0.0F) continue;
            long lifetimeTicks = Math.max(1L, (long) Math.ceil(seconds * 20.0F));
            if (tick - entry.cell().createdGameTick() >= lifetimeTicks) blocks.removeSceneOwned(entry.position());
        }
    }

    private void reconcileStaticState() {
        multipartBlocks.reconcile();
        neighborStates.reconcile();
        chestSystem.reconcilePairs();
        // Chest pairing changes CHEST_TYPE and can affect model/collision revisions.
        // A second neighbor pass makes the same frame authoritative and stable.
        neighborStates.reconcile();
    }

    public void reset() {
        commands.clear();
        blocks.clear();
        fluids.clear();
        actors.clear();
        fluidSystem.reset();
        multipartBlocks.reset();
        neighborStates.reset();
        lighting.reset();
        chestSystem.reset();
        jukeboxSystem.reset();
        bellSystem.reset();
        blockInteractions.reset();
        projectileSystem.reset();
        itemInteractions.reset();
        gameplayOutcomes.reset();
        physics.reset();
        clock.reset();
    }
}
