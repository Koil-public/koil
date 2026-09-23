package com.spirit.koil.api.design.sprite.gameplay;

import net.minecraft.item.ArrowItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.FireworkRocketItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.TridentItem;
import net.minecraft.util.UseAction;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Semantic forward/muzzle information for flat directional item art.
 *
 * <p>Koil deliberately keeps {@code KoilItemActor.rotation()} as the rotation the
 * user can see and manipulate. A launcher then adds only the intrinsic forward
 * axis of that item's art to find the direction its head/open face points. This
 * avoids the previous hidden global -45 degree correction that made bows fire
 * from their top edge.</p>
 *
 * <p>Vanilla families have explicit profiles. Mod item subclasses inherit those
 * profiles, and mods with different art can register an item/class override.
 * Generic BOW/SPEAR use-action fallbacks remain available without registry-name
 * heuristics.</p>
 */
public final class DirectionalItemProfile {
    public record Profile(float forwardDegrees, float muzzlePixels) {
        public Profile {
            if (!Float.isFinite(forwardDegrees)) forwardDegrees = 0.0F;
            if (!Float.isFinite(muzzlePixels) || muzzlePixels < 0.0F) muzzlePixels = 8.0F;
        }
    }

    private static final Profile DEFAULT = new Profile(0.0F, 8.0F);
    private static final Map<Item, Profile> ITEM_OVERRIDES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Profile> CLASS_OVERRIDES = new ConcurrentHashMap<>();

    private DirectionalItemProfile() { }

    public static void register(Item item, Profile profile) {
        if (item == null) return;
        if (profile == null) ITEM_OVERRIDES.remove(item);
        else ITEM_OVERRIDES.put(item, profile);
    }

    public static void registerClass(Class<? extends Item> itemClass, Profile profile) {
        if (itemClass == null) return;
        if (profile == null) CLASS_OVERRIDES.remove(itemClass);
        else CLASS_OVERRIDES.put(itemClass, profile);
    }

    public static Profile profile(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return DEFAULT;
        Item item = stack.getItem();
        Profile exact = ITEM_OVERRIDES.get(item);
        if (exact != null) return exact;
        return resolve(stack);
    }

    /** Screen-space launch heading for a visible launcher actor. */
    public static float launchHeadingDegrees(ItemStack launcher, float visibleRotationDegrees) {
        return normalize(visibleRotationDegrees + profile(launcher).forwardDegrees());
    }

    /**
     * Visual rotation for a projectile whose actor rotation already stores its
     * velocity heading. The intrinsic texture forward is subtracted so the
     * projectile's visible tip follows velocity.
     */
    public static float projectileRenderRotationDegrees(ItemStack projectile, float flightHeadingDegrees) {
        return normalize(flightHeadingDegrees - profile(projectile).forwardDegrees());
    }

    private static Profile resolve(ItemStack stack) {
        Item item = stack.getItem();
        for (Class<?> cursor = item.getClass(); cursor != null && Item.class.isAssignableFrom(cursor);
             cursor = cursor.getSuperclass()) {
            Profile override = CLASS_OVERRIDES.get(cursor);
            if (override != null) return override;
        }

        // These angles are measured from the actual unrotated Minecraft 1.20.1
        // generated-item artwork in screen coordinates where +X is right and +Y
        // is down. The pulled bow embeds its arrow toward the upper-left, and the
        // charged crossbow's bolt points the same way. Keeping that visual axis
        // explicit is what makes "fire from the face/head" mean the same thing as
        // what the player sees instead of a hidden gameplay-only orientation.
        if (item instanceof BowItem) return new Profile(-130.0F, 8.0F);           // pulled arrow tip: upper-left
        if (item instanceof CrossbowItem) return new Profile(-135.0F, 8.5F);      // charged bolt tip: upper-left
        if (item instanceof TridentItem) return new Profile(-45.0F, 9.0F);        // trident tip: upper-right
        if (item instanceof ArrowItem) return new Profile(-45.0F, 8.5F);          // arrowhead: upper-right
        if (item instanceof FireworkRocketItem) return new Profile(-90.0F, 8.0F); // rocket nose: upward

        // Safe behavioral fallback for modded items that deliberately expose a
        // vanilla use contract. Mods with different art can register an override.
        try {
            UseAction action = item.getUseAction(stack);
            // Most generated BOW-action assets follow vanilla's pulled-bow
            // orientation. Mods with different art should register an override.
            if (action == UseAction.BOW) return new Profile(-130.0F, 8.0F);
            if (action == UseAction.SPEAR) return new Profile(-45.0F, 9.0F);
        } catch (RuntimeException ignored) { }
        return DEFAULT;
    }

    private static float normalize(float degrees) {
        float value = degrees % 360.0F;
        if (value <= -180.0F) value += 360.0F;
        if (value > 180.0F) value -= 360.0F;
        return value;
    }
}
