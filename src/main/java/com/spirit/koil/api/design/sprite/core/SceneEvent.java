package com.spirit.koil.api.design.sprite.core;

import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/** Typed scene events. Strings are for diagnostics only, never authoritative state. */
public interface SceneEvent {
    record BlockPlaced(SceneCellPos position, BlockState state, long gameTick) implements SceneEvent { }
    record BlockRemoved(SceneCellPos position, BlockState previousState, long gameTick) implements SceneEvent { }
    record BlockStateChanged(SceneCellPos position, BlockState previousState, BlockState state, long gameTick) implements SceneEvent { }
    record BlockInteraction(SceneCellPos position, String action, long gameTick) implements SceneEvent { }
    record ActorSpawned(long actorId, String actorKind, long physicsStep) implements SceneEvent { }
    record ActorRemoved(long actorId, String actorKind, long physicsStep) implements SceneEvent { }
    record ActorMoved(long actorId, float previousX, float previousY, float x, float y, long physicsStep) implements SceneEvent { }
    record ActorLanded(long actorId, SceneCellPos position, long gameTick) implements SceneEvent { }
    record PhysicsContact(long actorId, Long otherActorId, SceneCellPos blockCell, BlockState blockState,
                          float normalX, float normalY, float impactSpeed, boolean grounded,
                          long physicsStep) implements SceneEvent { }
    record ItemStackChanged(long actorId, ItemStack stack, long gameTick) implements SceneEvent { }
    /** Temporary migration event: update/remove the old visual/input proxy after scene authority takes over. */
    record LegacyProxyWriteback(long sourceId, SceneCellPos position, BlockState state, boolean removeProxy,
                                long gameTick) implements SceneEvent { }
    record FluidChanged(SceneCellPos position, FluidState previousState, FluidState state, long gameTick) implements SceneEvent { }
    record FluidContact(long actorId, SceneCellPos position, FluidState state, float submersion, long physicsStep) implements SceneEvent { }
    record SoundRequested(SoundEvent sound, float volume, float pitch, long gameTick) implements SceneEvent { }
    record SoundStopRequested(SoundEvent sound, long gameTick) implements SceneEvent { }
    /**
     * Visual-only gameplay consequence. Optional item/block payloads preserve the
     * native texture source for parameterized Minecraft particle families while FX
     * remains an observer rather than gameplay authority.
     */
    record ParticleRequested(Identifier particleType, float x, float y, int depth, int count,
                             float spreadX, float spreadY, float visualScale,
                             ItemStack particleItem, BlockState particleBlock, long gameTick) implements SceneEvent {
        public ParticleRequested(Identifier particleType, float x, float y, int depth, int count,
                                 float spreadX, float spreadY, float visualScale, long gameTick) {
            this(particleType, x, y, depth, count, spreadX, spreadY, visualScale,
                    ItemStack.EMPTY, null, gameTick);
        }
        public ParticleRequested {
            particleItem = particleItem == null ? ItemStack.EMPTY : particleItem.copy();
        }
    }
    /** Scene-native TNT fuse request. The primed actor remains gameplay-owned until detonation. */
    record TntPrimed(long actorId, float x, float y, int depth, int fuseTicks, ItemStack sourceStack,
                     long gameTick) implements SceneEvent { }
    record ProjectileImpact(long projectileActorId, String projectileKind, float x, float y, int depth,
                            Long otherActorId, SceneCellPos blockCell, long gameTick) implements SceneEvent { }
    record EntitySpawnRequested(Identifier entityType, long actorId, float x, float y, int depth,
                                String reason, long gameTick) implements SceneEvent { }
    record ExperienceBurst(float x, float y, int depth, int amount, long gameTick) implements SceneEvent { }
    record AreaEffectRequested(ItemStack stack, float x, float y, int depth, float radius,
                               long gameTick) implements SceneEvent { }
    record TeleportRequested(long sourceActorId, float x, float y, int depth, long gameTick) implements SceneEvent { }
    record ExplosionRequested(float x, float y, int depth, ItemStack sourceStack, long gameTick) implements SceneEvent { }
    record Diagnostic(String code, String detail) implements SceneEvent { }
}
