package com.spirit.koil.api.design.sprite.gameplay;

import com.spirit.koil.api.design.sprite.actor.Actor;
import com.spirit.koil.api.design.sprite.actor.ActorWorld;
import com.spirit.koil.api.design.sprite.actor.EntityActor;
import com.spirit.koil.api.design.sprite.actor.ExperienceOrbActor;
import com.spirit.koil.api.design.sprite.actor.PrimedTntActor;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneEvent;
import com.spirit.koil.api.design.sprite.core.SceneEventBus;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import net.minecraft.block.BlockState;
import net.minecraft.item.FireworkRocketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.potion.PotionUtil;
import net.minecraft.entity.effect.StatusEffectInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Consumes gameplay outcomes emitted by detached item/projectile systems.
 *
 * <p>This is deliberately scene-owned. FX may observe the same events, but FX is
 * never responsible for applying the physical/status consequence.</p>
 */
public final class GameplayOutcomeSystem {
    private final BlockGrid blocks;
    private final ActorWorld actors;
    private final SceneProjection projection;
    private final GameplayParticleSystem particles;
    private final SceneEventBus events;
    private final LongSupplier gameTick;
    private final List<SceneEvent.AreaEffectRequested> areaEffects = new ArrayList<>();
    private final List<SceneEvent.ExplosionRequested> explosions = new ArrayList<>();
    private final List<SceneEvent.TeleportRequested> teleports = new ArrayList<>();
    private final List<SceneEvent.ExperienceBurst> experienceBursts = new ArrayList<>();
    private final List<AreaCloud> areaClouds = new ArrayList<>();
    private static final class AreaCloud {
        final List<StatusEffectInstance> effects;
        final float x;
        final float y;
        final int depth;
        int ageTicks;
        int remainingTicks = 600;

        AreaCloud(List<StatusEffectInstance> effects, float x, float y, int depth) {
            this.effects = effects;
            this.x = x;
            this.y = y;
            this.depth = depth;
        }
    }

    public GameplayOutcomeSystem(BlockGrid blocks, ActorWorld actors,
                                     SceneProjection projection, GameplayParticleSystem particles,
                                     SceneEventBus events, LongSupplier gameTick) {
        this.blocks = blocks;
        this.actors = actors;
        this.projection = projection;
        this.particles = particles;
        this.events = events;
        this.gameTick = gameTick;
        if (events != null) {
            events.subscribe(event -> {
                if (event instanceof SceneEvent.AreaEffectRequested requested) areaEffects.add(requested);
                else if (event instanceof SceneEvent.ExplosionRequested requested) explosions.add(requested);
                else if (event instanceof SceneEvent.TeleportRequested requested) teleports.add(requested);
                else if (event instanceof SceneEvent.ExperienceBurst burst) experienceBursts.add(burst);
            });
        }
    }

    /** Run after projectile contact processing so its newly emitted outcomes are applied immediately. */
    public void afterPhysicsStep() {
        if (!areaEffects.isEmpty()) {
            List<SceneEvent.AreaEffectRequested> copy = List.copyOf(areaEffects);
            areaEffects.clear();
            for (SceneEvent.AreaEffectRequested request : copy) applyPotionArea(request);
        }
        if (!explosions.isEmpty()) {
            List<SceneEvent.ExplosionRequested> copy = List.copyOf(explosions);
            explosions.clear();
            for (SceneEvent.ExplosionRequested request : copy) applyExplosion(request);
        }
        if (!teleports.isEmpty()) {
            List<SceneEvent.TeleportRequested> copy = List.copyOf(teleports);
            teleports.clear();
            for (SceneEvent.TeleportRequested request : copy) applyTeleport(request);
        }
        if (!experienceBursts.isEmpty()) {
            List<SceneEvent.ExperienceBurst> copy = List.copyOf(experienceBursts);
            experienceBursts.clear();
            for (SceneEvent.ExperienceBurst burst : copy) spawnExperience(burst);
        }
    }

    public void minecraftTick() {
        for (Actor actor : actors.actors()) {
            if (actor instanceof EntityActor entity) entity.tickDetachedEffects();
            else if (actor instanceof ExperienceOrbActor orb) {
                orb.tickAge();
                if (orb.ageTicks() >= 6000) actors.remove(orb.id());
            }
        }
        tickAreaClouds();
        tickPrimedTnt();
    }

    public void reset() {
        areaEffects.clear();
        explosions.clear();
        teleports.clear();
        experienceBursts.clear();
        areaClouds.clear();
    }

    private void applyPotionArea(SceneEvent.AreaEffectRequested request) {
        if (request == null || request.stack() == null || request.stack().isEmpty()) return;
        List<StatusEffectInstance> potionEffects;
        try { potionEffects = PotionUtil.getPotionEffects(request.stack()); }
        catch (RuntimeException ignored) { return; }
        if (potionEffects.isEmpty()) return;
        if (request.stack().isOf(Items.LINGERING_POTION)) {
            List<StatusEffectInstance> copies = potionEffects.stream().map(StatusEffectInstance::new).toList();
            areaClouds.add(new AreaCloud(copies, request.x(), request.y(), request.depth()));
            diagnostic("lingering_cloud", "effects=" + copies.size() + ",depth=" + request.depth());
            return;
        }
        applyEffects(potionEffects, request.x(), request.y(), request.depth(), Math.max(1.0F, request.radius()), 1.0F);
        diagnostic("potion_area", "effects=" + potionEffects.size() + ",depth=" + request.depth());
    }

    private void tickAreaClouds() {
        for (AreaCloud cloud : List.copyOf(areaClouds)) {
            cloud.ageTicks++;
            cloud.remainingTicks--;
            if (cloud.remainingTicks <= 0) {
                areaClouds.remove(cloud);
                continue;
            }
            if (cloud.ageTicks < 10 || cloud.ageTicks % 10 != 0) continue;
            float radiusCells = Math.max(0.5F, 3.0F * (cloud.remainingTicks / 600.0F));
            applyEffects(cloud.effects, cloud.x, cloud.y, cloud.depth, radiusCells, 0.25F);
        }
    }

    private void tickPrimedTnt() {
        for (Actor raw : actors.actors()) {
            if (!(raw instanceof PrimedTntActor primed) || primed.removed()) continue;
            int remaining = primed.tickFuse();
            // Vanilla PrimedTntEntity emits smoke every tick while the fuse burns.
            if (remaining > 0) {
                if (particles != null) {
                    particles.emit(new net.minecraft.util.Identifier("minecraft", "smoke"),
                            primed.x(), primed.y() - 8.0F, primed.depth(), 1, 1.5F, 1.5F, 0.62F);
                }
                continue;
            }
            float x = primed.x();
            float y = primed.y();
            int depth = primed.depth();
            ItemStack source = primed.sourceStack();
            actors.remove(primed.id());
            if (events != null) {
                events.publish(new SceneEvent.ExplosionRequested(x, y, depth, source, gameTick.getAsLong()));
            }
        }
    }

    private void applyEffects(List<StatusEffectInstance> effects, float x, float y, int depth,
                              float radiusCells, float minimumScale) {
        float radius = Math.max(1.0F, radiusCells * projection.cellPixels());
        for (Actor raw : actors.actors()) {
            if (!(raw instanceof EntityActor entity) || entity.depth() != depth) continue;
            float dx = entity.x() - x;
            float dy = entity.y() - y;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance > radius) continue;
            float intensity = Math.max(0.0F, 1.0F - distance / radius);
            float durationScale = Math.max(minimumScale, minimumScale + intensity * (1.0F - minimumScale));
            for (StatusEffectInstance effect : effects) {
                int duration = effect.isInfinite() ? StatusEffectInstance.INFINITE
                        : Math.max(1, Math.round(effect.getDuration() * durationScale));
                entity.applyDetachedEffect(effect.getEffectType(), duration, effect.getAmplifier());
            }
        }
    }

    private void spawnExperience(SceneEvent.ExperienceBurst burst) {
        if (burst == null || burst.amount() <= 0) return;
        int remaining = burst.amount();
        int index = 0;
        while (remaining > 0 && index < 64) {
            int amount;
            try { amount = ExperienceOrbEntity.roundToOrbSize(remaining); }
            catch (RuntimeException ignored) { amount = Math.min(remaining, 7); }
            amount = Math.max(1, Math.min(amount, remaining));
            remaining -= amount;
            long id = actors.allocateNativeId();
            float offset = (index - 1.5F) * 2.0F;
            ExperienceOrbActor orb = new ExperienceOrbActor(id, Actor.Authority.SCENE, amount,
                    burst.x() + offset, burst.y() - 2.0F, burst.depth());
            orb.setVelocity((index % 2 == 0 ? -1.0F : 1.0F) * (18.0F + index * 2.0F), -55.0F - index * 3.0F);
            actors.put(orb);
            index++;
        }
        diagnostic("experience_burst", "amount=" + burst.amount() + ",orbs=" + index);
    }

    private void applyExplosion(SceneEvent.ExplosionRequested request) {
        if (request == null) return;
        ItemStack source = request.sourceStack();
        boolean tnt = source != null && source.isOf(Items.TNT);
        boolean firework = source != null && source.getItem() instanceof FireworkRocketItem;
        float radiusCells = tnt ? 4.0F : firework ? 2.25F : 2.5F;
        float radius = radiusCells * projection.cellPixels();
        float impulse = tnt ? 290.0F : 170.0F;

        for (Actor actor : actors.actors()) {
            if (actor == null || actor.removed() || actor.depth() != request.depth() || !actor.body().dynamic()) continue;
            float dx = actor.x() - request.x();
            float dy = actor.y() - request.y();
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance <= 0.001F || distance > radius) continue;
            float strength = 1.0F - distance / radius;
            actor.body().addVelocity(dx / distance * impulse * strength, dy / distance * impulse * strength);
        }

        // Fireworks do not destroy terrain. TNT gets a detached approximation
        // based on the block's native blast resistance; the future explosion
        // service can replace this without changing projectile/item ownership.
        if (tnt) {
            for (BlockGrid.Entry entry : List.copyOf(blocks.entries())) {
                SceneCellPos pos = entry.position();
                BlockState state = entry.cell().blockState();
                if (state == null || state.isAir() || pos.depth() != request.depth()) continue;
                float dx = projection.cellCenterScreenX(pos) - request.x();
                float dy = projection.cellCenterScreenY(pos) - request.y();
                float distanceCells = (float) Math.sqrt(dx * dx + dy * dy) / Math.max(1.0F, projection.cellPixels());
                if (distanceCells > radiusCells) continue;
                float resistance;
                try { resistance = state.getBlock().getBlastResistance(); }
                catch (RuntimeException ignored) { resistance = Float.MAX_VALUE; }
                float remaining = (radiusCells - distanceCells) * 2.0F;
                if (resistance <= remaining) {
                    if (particles != null) {
                        particles.emitBlock(state, projection.cellCenterScreenX(pos), projection.cellCenterScreenY(pos),
                                projection.depthCoordinate(pos), 2, 8.0F, 8.0F, 0.55F);
                    }
                    blocks.removeSceneOwned(pos);
                }
            }
        }
        if (particles != null) {
            // Vanilla explosion_emitter is a NoRenderParticle whose job is to spawn
            // visible explosion children in a ClientWorld. Koil is detached from a
            // playable world, so publish those visible native particle children
            // directly instead of rendering a fake emitter sprite.
            particles.emit(new net.minecraft.util.Identifier("minecraft", "explosion"),
                    request.x(), request.y(), request.depth(), tnt ? 7 : 4,
                    projection.cellPixels() * (tnt ? 1.6F : 1.0F), projection.cellPixels() * (tnt ? 1.3F : 0.9F), 1.0F);
            particles.emit(new net.minecraft.util.Identifier("minecraft", "poof"),
                    request.x(), request.y(), request.depth(), tnt ? 14 : 6,
                    projection.cellPixels() * 1.2F, projection.cellPixels() * 1.0F, 0.85F);
        }
        if (events != null) events.publish(new SceneEvent.SoundRequested(
                SoundEvents.ENTITY_GENERIC_EXPLODE, tnt ? 0.62F : 0.48F, 1.0F, gameTick.getAsLong()));
        diagnostic("explosion", (tnt ? "tnt" : firework ? "firework" : "generic") + ",depth=" + request.depth());
    }

    private void applyTeleport(SceneEvent.TeleportRequested request) {
        if (request == null) return;
        Actor source = actors.get(request.sourceActorId());
        // The scene currently has no detached player/avatar actor. If a custom
        // adapter launches a pearl-like projectile from a persistent actor, honor
        // it. A consumed item proxy may already be gone, in which case the typed
        // request remains observable for a future owner/avatar system.
        if (source != null && !source.removed()) {
            source.body().teleport(request.x(), request.y());
            source.setDepth(request.depth());
            source.setVelocity(0.0F, 0.0F);
            diagnostic("teleport", "actor=" + source.id());
        }
    }

    private void diagnostic(String code, String detail) {
        if (events != null) events.publish(new SceneEvent.Diagnostic(code, detail + ",tick=" + gameTick.getAsLong()));
    }
}
