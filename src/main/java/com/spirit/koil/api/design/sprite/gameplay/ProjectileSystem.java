package com.spirit.koil.api.design.sprite.gameplay;

import com.spirit.koil.api.design.sprite.actor.Actor;
import com.spirit.koil.api.design.sprite.actor.ActorWorld;
import com.spirit.koil.api.design.sprite.actor.EntityActor;
import com.spirit.koil.api.design.sprite.actor.ProjectileActor;
import com.spirit.koil.api.design.sprite.actor.ItemActor;
import com.spirit.koil.api.design.sprite.core.SceneEvent;
import com.spirit.koil.api.design.sprite.core.SceneEventBus;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * Detached projectile lifecycle for item-family projectiles.
 *
 * <p>Collision is supplied by KoilPhysicsWorld. This class converts those contacts
 * into Minecraft-family outcomes without constructing a real Entity/World instance.
 * Mods that subclass vanilla throwable items therefore inherit the same lifecycle.</p>
 */
public final class ProjectileSystem {
    private final ActorWorld actors;
    private final SceneEventBus events;
    private final LongSupplier physicsStep;
    private final LongSupplier gameTick;
    private final List<SceneEvent.PhysicsContact> pendingContacts = new ArrayList<>();
    private final Random random = new Random(0x4B4F494CL);

    public ProjectileSystem(ActorWorld actors, SceneEventBus events,
                                LongSupplier physicsStep, LongSupplier gameTick) {
        this.actors = actors;
        this.events = events;
        this.physicsStep = physicsStep;
        this.gameTick = gameTick;
        if (events != null) {
            events.subscribe(event -> {
                if (event instanceof SceneEvent.PhysicsContact contact
                        && actors.get(contact.actorId()) instanceof ProjectileActor) {
                    pendingContacts.add(contact);
                }
            });
        }
    }

    public long spawn(ItemCapabilityRegistry.ProjectileKind kind, ItemStack stack,
                      long sourceActorId, float x, float y, int depth,
                      float targetX, float targetY) {
        if (kind == null || kind == ItemCapabilityRegistry.ProjectileKind.NONE
                || stack == null || stack.isEmpty()) return -1L;
        long id = actors.allocateNativeId();
        float dx = targetX - x;
        float dy = targetY - y;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 6.0F || !Float.isFinite(length)) {
            dx = 1.0F;
            dy = -0.28F;
            length = (float) Math.sqrt(dx * dx + dy * dy);
        }
        dx /= length;
        dy /= length;
        // x/y are the launcher's already-resolved muzzle point. Do not apply a
        // second hidden offset here. Source-contact immunity handles the first
        // few physics steps if the projectile still grazes the launcher body.
        ProjectileActor projectile = new ProjectileActor(id, Actor.Authority.SCENE,
                kind, stack, sourceActorId, x, y, depth, physicsStep.getAsLong());
        float speed = launchSpeed(kind);
        // The caller has already resolved the item's visible forward axis into
        // this muzzle-to-target vector. Do not silently bend directional families
        // (including fireworks) toward a preferred screen axis here. Crossbow
        // rockets and explicitly rotated standalone launcher items must leave the
        // exact face/head the user aimed. Family-specific acceleration can be
        // layered later without rewriting the initial launch heading.
        projectile.setVelocity(dx * speed, dy * speed);
        if (directional(kind)) {
            projectile.setRotation(directionDegrees(dx, dy));
            projectile.setAngularVelocity(0.0F);
        } else {
            projectile.setAngularVelocity((random.nextFloat() - 0.5F) * 420.0F);
        }
        actors.put(projectile);
        publishLaunchSound(kind);
        return id;
    }

    /** Process impacts only after KoilPhysicsWorld finishes iterating actors. */
    public void afterPhysicsStep() {
        alignDirectionalProjectiles();
        if (!pendingContacts.isEmpty()) {
            List<SceneEvent.PhysicsContact> contacts = List.copyOf(pendingContacts);
            pendingContacts.clear();
            for (SceneEvent.PhysicsContact contact : contacts) handleImpact(contact);
        }
        expireProjectiles();
    }

    public void reset() { pendingContacts.clear(); }

    private void handleImpact(SceneEvent.PhysicsContact contact) {
        Actor raw = actors.get(contact.actorId());
        if (!(raw instanceof ProjectileActor projectile) || projectile.impactProcessed()) return;
        if (contact.otherActorId() != null && contact.otherActorId() == projectile.sourceActorId()
                && physicsStep.getAsLong() - projectile.bornPhysicsStep() < 8L) {
            return;
        }
        projectile.markImpactProcessed();

        float hitX = projectile.x();
        float hitY = projectile.y();
        Long otherId = contact.otherActorId();
        if (otherId != null) {
            Actor other = actors.get(otherId);
            if (other != null && other.body().dynamic()) {
                float scale = projectile.projectileKind() == ItemCapabilityRegistry.ProjectileKind.TRIDENT ? 0.32F : 0.13F;
                other.body().addVelocity(projectile.velocityX() * scale, projectile.velocityY() * scale);
            }
        }

        events.publish(new SceneEvent.ProjectileImpact(projectile.id(), projectile.projectileKind().name().toLowerCase(),
                hitX, hitY, projectile.depth(), otherId, contact.blockCell(), gameTick.getAsLong()));

        switch (projectile.projectileKind()) {
            case EGG -> hatchEgg(projectile, hitX, hitY);
            case EXPERIENCE_BOTTLE -> {
                int amount = 3 + random.nextInt(5) + random.nextInt(5); // vanilla 3..11 distribution
                events.publish(new SceneEvent.ExperienceBurst(hitX, hitY, projectile.depth(), amount, gameTick.getAsLong()));
            }
            case POTION -> events.publish(new SceneEvent.AreaEffectRequested(
                    projectile.stack(), hitX, hitY, projectile.depth(), 4.0F, gameTick.getAsLong()));
            case ENDER_PEARL -> events.publish(new SceneEvent.TeleportRequested(
                    projectile.sourceActorId(), hitX, hitY, projectile.depth(), gameTick.getAsLong()));
            case FIREWORK -> events.publish(new SceneEvent.ExplosionRequested(
                    hitX, hitY, projectile.depth(), projectile.stack(), gameTick.getAsLong()));
            case TRIDENT -> recoverTrident(projectile, hitX, hitY);
            default -> { }
        }
        actors.remove(projectile.id());
    }

    private void recoverTrident(ProjectileActor projectile, float x, float y) {
        ItemStack stack = projectile.stack();
        if (stack.isEmpty()) return;
        long id = actors.allocateNativeId();
        ItemActor recovered = new ItemActor(id, Actor.Authority.SCENE, stack,
                x, y - 2.0F, projectile.depth());
        recovered.setVelocity(projectile.velocityX() * 0.08F, -18.0F);
        recovered.setAngularVelocity(projectile.angularVelocity() * 0.25F);
        actors.put(recovered);
        events.publish(new SceneEvent.Diagnostic("trident_recoverable", "actor=" + id));
    }

    private void hatchEgg(ProjectileActor projectile, float x, float y) {
        if (random.nextInt(8) != 0) return;
        int count = random.nextInt(32) == 0 ? 4 : 1;
        for (int i = 0; i < count; i++) {
            long id = actors.allocateNativeId();
            EntityActor chick = new EntityActor(id, Actor.Authority.SCENE,
                    new Identifier("minecraft", "chicken"), x + (i - (count - 1) * 0.5F) * 4.0F,
                    y - 4.0F, projectile.depth());
            chick.setBodySize(7.0F, 8.0F);
            chick.setVelocity((random.nextFloat() - 0.5F) * 26.0F, -38.0F - random.nextFloat() * 18.0F);
            actors.put(chick);
            events.publish(new SceneEvent.EntitySpawnRequested(new Identifier("minecraft", "chicken"),
                    chick.id(), chick.x(), chick.y(), chick.depth(), "egg_hatch", gameTick.getAsLong()));
        }
    }

    private void expireProjectiles() {
        long now = physicsStep.getAsLong();
        for (Actor actor : actors.actors()) {
            if (!(actor instanceof ProjectileActor projectile)) continue;
            long age = now - projectile.bornPhysicsStep();
            long max = projectile.projectileKind() == ItemCapabilityRegistry.ProjectileKind.FIREWORK ? 90L : 360L;
            if (age < max) continue;
            if (projectile.projectileKind() == ItemCapabilityRegistry.ProjectileKind.FIREWORK) {
                events.publish(new SceneEvent.ExplosionRequested(projectile.x(), projectile.y(), projectile.depth(),
                        projectile.stack(), gameTick.getAsLong()));
            }
            actors.remove(projectile.id());
        }
    }

    private void alignDirectionalProjectiles() {
        for (Actor raw : actors.actors()) {
            if (!(raw instanceof ProjectileActor projectile) || !directional(projectile.projectileKind())) continue;
            float vx = projectile.velocityX();
            float vy = projectile.velocityY();
            if (vx * vx + vy * vy < 0.0001F) continue;
            projectile.setRotation(directionDegrees(vx, vy));
            projectile.setAngularVelocity(0.0F);
        }
    }

    private static boolean directional(ItemCapabilityRegistry.ProjectileKind kind) {
        return kind == ItemCapabilityRegistry.ProjectileKind.ARROW
                || kind == ItemCapabilityRegistry.ProjectileKind.TRIDENT
                || kind == ItemCapabilityRegistry.ProjectileKind.FIREWORK;
    }

    private static float directionDegrees(float x, float y) {
        return (float) Math.toDegrees(Math.atan2(y, x));
    }

    private void publishLaunchSound(ItemCapabilityRegistry.ProjectileKind kind) {
        switch (kind) {
            case SNOWBALL -> sound(SoundEvents.ENTITY_SNOWBALL_THROW, 0.30F, 1.0F);
            case EGG -> sound(SoundEvents.ENTITY_EGG_THROW, 0.30F, 1.0F);
            case ENDER_PEARL -> sound(SoundEvents.ENTITY_ENDER_PEARL_THROW, 0.32F, 1.0F);
            case EXPERIENCE_BOTTLE -> sound(SoundEvents.ENTITY_EXPERIENCE_BOTTLE_THROW, 0.30F, 1.0F);
            case POTION -> sound(SoundEvents.ENTITY_SPLASH_POTION_THROW, 0.30F, 1.0F);
            case FIREWORK -> sound(SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.32F, 1.0F);
            case TRIDENT -> sound(SoundEvents.ITEM_TRIDENT_THROW, 0.34F, 1.0F);
            default -> { }
        }
    }

    private void sound(net.minecraft.sound.SoundEvent sound, float volume, float pitch) {
        if (events != null && sound != null) events.publish(new SceneEvent.SoundRequested(sound, volume, pitch, gameTick.getAsLong()));
    }

    private static float launchSpeed(ItemCapabilityRegistry.ProjectileKind kind) {
        return switch (kind) {
            case SNOWBALL, EGG, ENDER_PEARL -> 310.0F;
            case EXPERIENCE_BOTTLE, POTION -> 255.0F;
            case FIREWORK -> 180.0F;
            case TRIDENT -> 390.0F;
            case ARROW -> 430.0F;
            default -> 260.0F;
        };
    }
}
