package com.spirit.koil.api.design.sprite.actor;

import net.minecraft.item.ItemStack;

/** A real scene actor backed by ItemStack identity rather than a generic FX particle. */
public final class ItemActor extends Actor {
    private ItemStack stack;
    private boolean usingItem;
    private float useProgress;

    public ItemActor(long id, Authority authority, ItemStack stack, float x, float y, int depth) {
        super(id, authority, x, y, depth);
        this.stack = stack == null ? ItemStack.EMPTY : stack.copy();
        setBodySize(12.0F, 12.0F);
        setMass(1.0F);
        setGravity(256.0F);
        setDrag(0.98F);
        setRestitution(0.14F);
        setSurfaceFriction(0.82F);
    }

    @Override public String kind() { return "item"; }
    public ItemStack stack() { return stack.copy(); }
    public void setStack(ItemStack stack) { this.stack = stack == null ? ItemStack.EMPTY : stack.copy(); }
    public boolean usingItem() { return usingItem; }
    public float useProgress() { return useProgress; }
    public void setUseVisualState(boolean usingItem, float useProgress) {
        this.usingItem = usingItem;
        this.useProgress = Math.max(0.0F, Math.min(1.0F, Float.isFinite(useProgress) ? useProgress : 0.0F));
    }
}
