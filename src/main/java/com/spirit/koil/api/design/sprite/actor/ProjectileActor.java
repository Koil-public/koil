package com.spirit.koil.api.design.sprite.actor;

import com.spirit.koil.api.design.sprite.gameplay.ItemCapabilityRegistry;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** Scene-native projectile backed by the exact ItemStack that created it. */
public final class ProjectileActor extends Actor {
    private final ItemCapabilityRegistry.ProjectileKind projectileKind;
    private final ItemStack stack;
    private final long sourceActorId;
    private long bornPhysicsStep;
    private boolean impactProcessed;

    public ProjectileActor(long id, Authority authority,
                               ItemCapabilityRegistry.ProjectileKind projectileKind,
                               ItemStack stack, long sourceActorId,
                               float x, float y, int depth, long bornPhysicsStep) {
        super(id, authority, x, y, depth);
        this.projectileKind = projectileKind == null
                ? ItemCapabilityRegistry.ProjectileKind.NONE : projectileKind;
        this.stack = stack == null ? ItemStack.EMPTY : stack.copy();
        this.sourceActorId = sourceActorId;
        this.bornPhysicsStep = bornPhysicsStep;
        configurePhysics(this.projectileKind);
    }

    @Override public String kind() { return "projectile"; }
    public ItemCapabilityRegistry.ProjectileKind projectileKind() { return projectileKind; }
    public ItemStack stack() { return stack.copy(); }
    public long sourceActorId() { return sourceActorId; }
    public long bornPhysicsStep() { return bornPhysicsStep; }
    public void setBornPhysicsStep(long value) { bornPhysicsStep = value; }
    public boolean impactProcessed() { return impactProcessed; }
    public void markImpactProcessed() { impactProcessed = true; }

    /** Compatibility/debug identity without making registry ids behavioral authority. */
    public Identifier projectileType() {
        return stack.isEmpty() ? new Identifier("minecraft", "air") : Registries.ITEM.getId(stack.getItem());
    }

    private void configurePhysics(ItemCapabilityRegistry.ProjectileKind kind) {
        setBodySize(kind == ItemCapabilityRegistry.ProjectileKind.FIREWORK ? 7.0F : 6.0F,
                kind == ItemCapabilityRegistry.ProjectileKind.FIREWORK ? 10.0F : 6.0F);
        setMass(0.35F);
        setRestitution(0.0F);
        setSurfaceFriction(0.10F);
        body().setCanSleep(false);
        body().setCollideActors(true);
        body().setCollideWorld(true);
        switch (kind) {
            case FIREWORK -> {
                setGravity(-190.0F);
                setDrag(0.995F);
            }
            case TRIDENT, ARROW -> {
                setGravity(72.0F);
                setDrag(0.995F);
            }
            case EXPERIENCE_BOTTLE, POTION -> {
                setGravity(168.0F);
                setDrag(0.99F);
            }
            default -> {
                setGravity(150.0F);
                setDrag(0.99F);
            }
        }
    }
}
