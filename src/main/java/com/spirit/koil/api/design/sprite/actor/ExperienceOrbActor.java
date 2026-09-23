package com.spirit.koil.api.design.sprite.actor;

/** Detached experience-orb actor created by bottle-of-enchanting impacts. */
public final class ExperienceOrbActor extends Actor {
    private final int amount;
    private int ageTicks;

    public ExperienceOrbActor(long id, Authority authority, int amount, float x, float y, int depth) {
        super(id, authority, x, y, depth);
        this.amount = Math.max(1, amount);
        setBodySize(5.0F, 5.0F);
        setMass(0.08F);
        setGravity(92.0F);
        setDrag(0.96F);
        setRestitution(0.28F);
        setSurfaceFriction(0.18F);
        body().setCanSleep(false);
        body().setCollideActors(false);
    }

    @Override public String kind() { return "experience_orb"; }
    public int amount() { return amount; }
    public int ageTicks() { return ageTicks; }
    public void tickAge() { ageTicks++; }

    /** Vanilla experience-orb texture quadrant selection from XP value. */
    public int textureIndex() {
        if (amount >= 2477) return 10;
        if (amount >= 1237) return 9;
        if (amount >= 617) return 8;
        if (amount >= 307) return 7;
        if (amount >= 149) return 6;
        if (amount >= 73) return 5;
        if (amount >= 37) return 4;
        if (amount >= 17) return 3;
        if (amount >= 7) return 2;
        if (amount >= 3) return 1;
        return 0;
    }
}
