package com.spirit.koil.api.design.sprite.actor;

import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

public final class EntityActor extends Actor {
    private final Identifier entityType;
    private ItemStack visualStack = ItemStack.EMPTY;
    private final Map<Identifier, DetachedEffect> detachedEffects = new HashMap<>();

    public record DetachedEffect(Identifier effectId, int durationTicks, int amplifier) { }

    public EntityActor(long id, Authority authority, Identifier entityType, float x, float y, int depth) {
        super(id, authority, x, y, depth);
        this.entityType = entityType;
        setBodySize(14.0F, 14.0F);
        setGravity(256.0F);
        setDrag(0.91F);
        setRestitution(0.02F);
        setSurfaceFriction(0.82F);
    }

    @Override public String kind() { return "entity"; }
    public Identifier entityType() { return entityType; }
    public ItemStack visualStack() { return visualStack.copy(); }
    public void setVisualStack(ItemStack stack) { visualStack = stack == null ? ItemStack.EMPTY : stack.copy(); }

    public void applyDetachedEffect(StatusEffect effect, int durationTicks, int amplifier) {
        if (effect == null || durationTicks == 0) return;
        Identifier id = Registries.STATUS_EFFECT.getId(effect);
        if (id == null) return;
        DetachedEffect existing = detachedEffects.get(id);
        int nextDuration = durationTicks;
        int nextAmplifier = Math.max(0, amplifier);
        if (existing != null) {
            if (existing.amplifier() > nextAmplifier) {
                nextAmplifier = existing.amplifier();
                nextDuration = Math.max(existing.durationTicks(), nextDuration);
            } else if (existing.amplifier() == nextAmplifier) {
                nextDuration = Math.max(existing.durationTicks(), nextDuration);
            }
        }
        detachedEffects.put(id, new DetachedEffect(id, nextDuration, nextAmplifier));
    }

    public Map<Identifier, DetachedEffect> detachedEffects() { return Map.copyOf(detachedEffects); }

    public void tickDetachedEffects() {
        for (Map.Entry<Identifier, DetachedEffect> entry : Map.copyOf(detachedEffects).entrySet()) {
            DetachedEffect effect = entry.getValue();
            if (effect.durationTicks() < 0) continue;
            int remaining = effect.durationTicks() - 1;
            if (remaining <= 0) detachedEffects.remove(entry.getKey());
            else detachedEffects.put(entry.getKey(), new DetachedEffect(effect.effectId(), remaining, effect.amplifier()));
        }
    }
}
