package com.spirit.koil.api.design.sprite.actor;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Scene-native primed TNT actor.
 *
 * <p>Primed TNT is not an ItemStack actor in Minecraft. Keeping fuse state on
 * this actor gives Koil one authoritative owner for motion, fuse timing and the
 * vanilla flashing-block render path.</p>
 */
public final class PrimedTntActor extends Actor {
    public static final int DEFAULT_FUSE_TICKS = 80;

    private int fuseTicks;

    public PrimedTntActor(long id, Authority authority, float x, float y, int depth, int fuseTicks) {
        super(id, authority, x, y, depth);
        this.fuseTicks = Math.max(1, fuseTicks);
        setBodySize(15.6F, 15.6F);
        setMass(1.0F);
        setGravity(256.0F);
        setDrag(0.98F);
        // Vanilla TntEntity multiplies ground velocity by (0.7, -0.5, 0.7).
        // Koil's 2D static response expresses that as 0.5 normal restitution and
        // 0.7 tangential retention on an ordinary full-friction surface.
        setRestitution(0.5F);
        setSurfaceFriction(0.70F);
        setAngularVelocity(0.0F);
        setRotation(0.0F);
    }

    @Override public String kind() { return "primed_tnt"; }

    public int fuseTicks() { return fuseTicks; }

    /** @return remaining fuse after one Minecraft tick. */
    public int tickFuse() {
        if (fuseTicks > 0) fuseTicks--;
        return fuseTicks;
    }

    public ItemStack sourceStack() {
        return new ItemStack(Items.TNT);
    }
}
