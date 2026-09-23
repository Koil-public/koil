package com.spirit.koil.api.design.sprite.gameplay;

import net.minecraft.item.AxeItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BoneMealItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.BucketItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.EggItem;
import net.minecraft.item.EnderPearlItem;
import net.minecraft.item.ExperienceBottleItem;
import net.minecraft.item.FireChargeItem;
import net.minecraft.item.FireworkRocketItem;
import net.minecraft.item.FlintAndSteelItem;
import net.minecraft.item.GlassBottleItem;
import net.minecraft.item.MusicDiscItem;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ShearsItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.item.SnowballItem;
import net.minecraft.item.ThrowablePotionItem;
import net.minecraft.item.TridentItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.UseAction;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scene-native item behavior classifier.
 *
 * <p>Vanilla and modded subclasses inherit behavior through Java item families;
 * registry-name guessing is deliberately not authoritative. Mods with a custom
 * Item subclass can register an exact adapter/profile without requiring Koil to
 * know the mod's registry namespace.</p>
 */
public final class ItemCapabilityRegistry {
    public enum UseFamily {
        GENERIC,
        BLOCK_PLACEMENT,
        BUCKET,
        THROW_IMMEDIATE,
        CHARGE_RELEASE,
        TOOL,
        FIRE_STARTER,
        BONE_MEAL,
        SHEARS,
        GLASS_BOTTLE,
        MUSIC_DISC,
        SPAWN_EGG
    }

    public enum ProjectileKind {
        NONE,
        SNOWBALL,
        EGG,
        ENDER_PEARL,
        EXPERIENCE_BOTTLE,
        POTION,
        FIREWORK,
        TRIDENT,
        ARROW
    }

    public record Profile(UseFamily family, ProjectileKind projectile, boolean sceneHandled,
                          String supportNote) {
        public Profile {
            family = family == null ? UseFamily.GENERIC : family;
            projectile = projectile == null ? ProjectileKind.NONE : projectile;
            supportNote = supportNote == null ? "" : supportNote;
        }

        public static Profile generic() {
            return new Profile(UseFamily.GENERIC, ProjectileKind.NONE, false,
                    "No safe detached behavior adapter registered");
        }
    }

    private static final Map<Item, Profile> ITEM_OVERRIDES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Profile> CLASS_OVERRIDES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Profile> CACHE = new ConcurrentHashMap<>();

    private ItemCapabilityRegistry() { }

    public static void register(Item item, Profile profile) {
        if (item == null) return;
        if (profile == null) ITEM_OVERRIDES.remove(item);
        else ITEM_OVERRIDES.put(item, profile);
    }

    public static void registerClass(Class<? extends Item> itemClass, Profile profile) {
        if (itemClass == null) return;
        if (profile == null) CLASS_OVERRIDES.remove(itemClass);
        else CLASS_OVERRIDES.put(itemClass, profile);
        CACHE.clear();
    }

    public static Profile profile(Item item) {
        if (item == null) return Profile.generic();
        Profile exact = ITEM_OVERRIDES.get(item);
        if (exact != null) return exact;
        return CACHE.computeIfAbsent(item.getClass(), type -> resolve(item, type));
    }

    private static Profile resolve(Item item, Class<?> type) {
        Class<?> cursor = type;
        while (cursor != null && Item.class.isAssignableFrom(cursor)) {
            Profile override = CLASS_OVERRIDES.get(cursor);
            if (override != null) return override;
            cursor = cursor.getSuperclass();
        }

        // Immediate throwable families. Custom mod subclasses inherit these paths.
        if (item instanceof SnowballItem) {
            return supported(UseFamily.THROW_IMMEDIATE, ProjectileKind.SNOWBALL, "Snowball-style thrown item");
        }
        if (item instanceof EggItem) {
            return supported(UseFamily.THROW_IMMEDIATE, ProjectileKind.EGG, "Egg-style thrown item");
        }
        if (item instanceof EnderPearlItem) {
            return supported(UseFamily.THROW_IMMEDIATE, ProjectileKind.ENDER_PEARL, "Ender-pearl-style thrown item");
        }
        if (item instanceof ExperienceBottleItem) {
            return supported(UseFamily.THROW_IMMEDIATE, ProjectileKind.EXPERIENCE_BOTTLE, "Experience-bottle-style thrown item");
        }
        if (item instanceof ThrowablePotionItem) {
            return supported(UseFamily.THROW_IMMEDIATE, ProjectileKind.POTION, "Throwable potion item");
        }
        if (item instanceof FireworkRocketItem) {
            return supported(UseFamily.THROW_IMMEDIATE, ProjectileKind.FIREWORK, "Detached firework projectile");
        }

        // Charged weapon families are identified now so custom subclasses are not
        // misclassified as generic. Their full hold/release cycle can be serviced by
        // the same gameplay system without registry-name special cases.
        if (item instanceof TridentItem) {
            return supported(UseFamily.CHARGE_RELEASE, ProjectileKind.TRIDENT, "Charge/release projectile family");
        }
        if (item instanceof BowItem || item instanceof CrossbowItem) {
            return supported(UseFamily.CHARGE_RELEASE, ProjectileKind.ARROW, "Charge/release ranged family");
        }

        if (item instanceof SpawnEggItem) return supported(UseFamily.SPAWN_EGG, ProjectileKind.NONE, "Entity spawn egg family");
        if (item instanceof MusicDiscItem) return supported(UseFamily.MUSIC_DISC, ProjectileKind.NONE, "Jukebox music disc family");
        if (item instanceof GlassBottleItem) return supported(UseFamily.GLASS_BOTTLE, ProjectileKind.NONE, "Glass bottle fluid/honey interaction");
        if (item instanceof BlockItem) return supported(UseFamily.BLOCK_PLACEMENT, ProjectileKind.NONE, "Block placement item");
        if (item instanceof BucketItem) return supported(UseFamily.BUCKET, ProjectileKind.NONE, "Bucket/fluid item");
        if (item instanceof FlintAndSteelItem || item instanceof FireChargeItem) {
            return supported(UseFamily.FIRE_STARTER, ProjectileKind.NONE, "Fire-starting item");
        }
        if (item instanceof BoneMealItem) return supported(UseFamily.BONE_MEAL, ProjectileKind.NONE, "Bone meal item");
        if (item instanceof ShearsItem) return supported(UseFamily.SHEARS, ProjectileKind.NONE, "Shears item");
        if (item instanceof AxeItem || item instanceof ShovelItem || item instanceof HoeItem) {
            return supported(UseFamily.TOOL, ProjectileKind.NONE, "Tool transformation family");
        }

        // Capability fallback for mod items that expose a vanilla use contract
        // without subclassing the corresponding vanilla implementation. This is
        // deliberately behavior-based rather than registry-name guessing. BOW and
        // SPEAR have enough semantics to map safely in the detached scene. Custom
        // CROSSBOW implementations keep using an explicit adapter because their
        // charged-projectile storage format is not standardized by UseAction.
        try {
            UseAction action = item.getUseAction(new ItemStack(item));
            if (action == UseAction.BOW) {
                return supported(UseFamily.CHARGE_RELEASE, ProjectileKind.ARROW,
                        "UseAction.BOW capability fallback");
            }
            if (action == UseAction.SPEAR) {
                return supported(UseFamily.CHARGE_RELEASE, ProjectileKind.TRIDENT,
                        "UseAction.SPEAR capability fallback");
            }
        } catch (RuntimeException ignored) { }
        return Profile.generic();
    }

    private static Profile supported(UseFamily family, ProjectileKind projectile, String note) {
        return new Profile(family, projectile, true, note);
    }
}
