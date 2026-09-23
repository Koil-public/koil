package com.spirit.koil.api.design.particle;

import net.minecraft.block.Block;
import net.minecraft.block.BlockSetType;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.WoodType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Equipment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MusicDiscItem;
import net.minecraft.registry.Registries;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.UseAction;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecraft-native audio resolver for registry-backed Koil sprites.
 *
 * <p>Physical block audio comes directly from the block state's
 * {@link BlockSoundGroup}. Functional block audio uses Minecraft's
 * {@link BlockSetType}/{@link WoodType} records where those records are the
 * authoritative source (doors, trapdoors, buttons, plates, fence gates).
 * Item audio prefers ItemStack, Equipment and MusicDiscItem APIs and only
 * falls back to small vanilla action-family adapters when Minecraft does not
 * expose one generic use-sound getter.</p>
 *
 * <p>Mods with a context-specific item action that cannot be inferred from a
 * registry entry alone can register an exact override without replacing the
 * sprite engine.</p>
 */
public final class GameSpriteSoundResolver {
    public record UseSounds(SoundEvent press, SoundEvent release, float volume, float pitch) {
        public static UseSounds none() { return new UseSounds(null, null, 0.0F, 1.0F); }
    }

    private static final Map<Identifier, UseSounds> ITEM_USE_OVERRIDES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<Field>> BLOCK_SET_TYPE_FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<Field>> WOOD_TYPE_FIELDS = new ConcurrentHashMap<>();

    private GameSpriteSoundResolver() { }

    public static void registerItemUseSounds(Identifier itemId, UseSounds sounds) {
        if (itemId == null) return;
        if (sounds == null) ITEM_USE_OVERRIDES.remove(itemId);
        else ITEM_USE_OVERRIDES.put(itemId, sounds);
    }

    /** Exact place/hit/fall/break material sounds for every registered block. */
    public static UiParticleSoundProfile blockProfile(Block block) {
        if (block == null) return UiParticleSoundProfile.builder().build();
        BlockSoundGroup group = block.getDefaultState().getSoundGroup();
        float volume = Math.max(0.08F, group.getVolume());
        float pitch = Math.max(0.25F, group.getPitch());
        return UiParticleSoundProfile.builder()
                .spawn(new UiParticleSoundProfile.Cue(group.getPlaceSound(), volume * 0.60F, pitch, 0.035F, 42L))
                .bounce(new UiParticleSoundProfile.Cue(group.getHitSound(), volume * 0.42F, pitch, 0.055F, 52L))
                .fall(new UiParticleSoundProfile.Cue(group.getFallSound(), volume * 0.50F, pitch, 0.055F, 62L))
                .breakCue(new UiParticleSoundProfile.Cue(group.getBreakSound(), volume * 0.74F, pitch, 0.04F, 42L))
                .minimumBounceSpeed(18.0F)
                .build();
    }

    /**
     * Items on the ground do not have a universal material impact sound in
     * vanilla. BlockItems reuse the represented block's real sound group;
     * ordinary items remain quiet until an actual item function is invoked.
     */
    public static UiParticleSoundProfile itemPhysicsProfile(Item item) {
        if (item instanceof BlockItem blockItem) return blockProfile(blockItem.getBlock());
        return UiParticleSoundProfile.builder().build();
    }

    /** Native right-click/use audio for registered item families. */
    public static UseSounds itemUseSounds(Item item) {
        if (item == null) return UseSounds.none();
        Identifier id = Registries.ITEM.getId(item);
        UseSounds override = id == null ? null : ITEM_USE_OVERRIDES.get(id);
        if (override != null) return override;

        ItemStack stack = item.getDefaultStack();
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        UseAction action = stack.getUseAction();

        // APIs that expose the actual sound carried by the item.
        Equipment equipment = Equipment.fromStack(stack);
        if (equipment != null && action == UseAction.NONE) {
            return new UseSounds(equipment.getEquipSound(), null, 0.42F, 1.0F);
        }
        if (item instanceof MusicDiscItem disc) {
            return new UseSounds(disc.getSound(), null, 0.34F, 1.0F);
        }
        if (item instanceof BlockItem blockItem) {
            BlockSoundGroup group = blockItem.getBlock().getDefaultState().getSoundGroup();
            return new UseSounds(group.getPlaceSound(), null,
                    Math.max(0.10F, group.getVolume() * 0.62F), Math.max(0.25F, group.getPitch()));
        }

        // Generic vanilla use actions.
        switch (action) {
            case EAT:
                return new UseSounds(stack.getEatSound(), null, 0.34F, 1.0F);
            case DRINK:
                return new UseSounds(stack.getDrinkSound(), null, 0.34F, 1.0F);
            case BLOCK:
                return new UseSounds(SoundEvents.ITEM_SHIELD_BLOCK, null, 0.34F, 1.0F);
            case BOW:
                return new UseSounds(null, SoundEvents.ENTITY_ARROW_SHOOT, 0.34F, 1.0F);
            case CROSSBOW:
                return new UseSounds(SoundEvents.ITEM_CROSSBOW_LOADING_START,
                        SoundEvents.ITEM_CROSSBOW_LOADING_END, 0.32F, 1.0F);
            case SPEAR:
                return new UseSounds(null, SoundEvents.ITEM_TRIDENT_THROW, 0.36F, 1.0F);
            case SPYGLASS:
                return new UseSounds(SoundEvents.ITEM_SPYGLASS_USE,
                        SoundEvents.ITEM_SPYGLASS_STOP_USING, 0.34F, 1.0F);
            case TOOT_HORN:
                return new UseSounds(SoundEvents.ITEM_GOAT_HORN_PLAY, null, 0.38F, 1.0F);
            case BRUSH:
                return new UseSounds(SoundEvents.ITEM_BRUSH_BRUSHING_GENERIC, null, 0.30F, 1.0F);
            default:
                break;
        }

        // Vanilla functions whose use action is NONE.
        if (path.equals("flint_and_steel")) return one(SoundEvents.ITEM_FLINTANDSTEEL_USE, 0.34F);
        if (path.equals("fire_charge")) return one(SoundEvents.ITEM_FIRECHARGE_USE, 0.34F);
        if (path.equals("bone_meal")) return one(SoundEvents.ITEM_BONE_MEAL_USE, 0.30F);
        if (path.equals("honeycomb")) return one(SoundEvents.ITEM_HONEYCOMB_WAX_ON, 0.30F);
        if (path.equals("ink_sac")) return one(SoundEvents.ITEM_INK_SAC_USE, 0.30F);
        if (path.equals("glow_ink_sac")) return one(SoundEvents.ITEM_GLOW_INK_SAC_USE, 0.30F);
        if (path.endsWith("_hoe")) return one(SoundEvents.ITEM_HOE_TILL, 0.30F);
        if (path.endsWith("_shovel")) return one(SoundEvents.ITEM_SHOVEL_FLATTEN, 0.30F);
        if (path.endsWith("_axe")) return one(SoundEvents.ITEM_AXE_STRIP, 0.30F);
        if (path.contains("book") || path.contains("map")) return one(SoundEvents.ITEM_BOOK_PAGE_TURN, 0.24F);
        if (path.equals("ender_pearl")) return one(SoundEvents.ENTITY_ENDER_PEARL_THROW, 0.32F);
        if (path.equals("snowball")) return one(SoundEvents.ENTITY_SNOWBALL_THROW, 0.30F);
        if (path.equals("egg")) return one(SoundEvents.ENTITY_EGG_THROW, 0.30F);
        if (path.equals("experience_bottle")) return one(SoundEvents.ENTITY_EXPERIENCE_BOTTLE_THROW, 0.30F);
        if (path.contains("splash_potion") || path.contains("lingering_potion")) {
            return one(SoundEvents.ENTITY_SPLASH_POTION_THROW, 0.30F);
        }
        if (path.equals("firework_rocket")) return one(SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.30F);
        if (path.equals("fishing_rod")) return new UseSounds(SoundEvents.ENTITY_FISHING_BOBBER_THROW,
                SoundEvents.ENTITY_FISHING_BOBBER_RETRIEVE, 0.30F, 1.0F);
        if (path.equals("totem_of_undying")) return one(SoundEvents.ITEM_TOTEM_USE, 0.34F);
        if (path.equals("compass")) return one(SoundEvents.ITEM_LODESTONE_COMPASS_LOCK, 0.25F);
        if (path.contains("bucket")) return bucketSounds(path);
        if (path.contains("bottle")) return one(SoundEvents.ITEM_BOTTLE_EMPTY, 0.25F);

        // Silence is more faithful than inventing a sound for an item whose
        // context-specific behavior is owned by a mod/world implementation.
        return UseSounds.none();
    }

    /**
     * Vanilla button press duration in game ticks. ButtonBlock stores this as
     * an implementation detail, so the sprite runtime reads the mapped field
     * rather than duplicating the wooden/stone timing table. Custom subclasses
     * inherit the same field and therefore keep their configured duration.
     */
    public static int buttonPressTicks(Block block) {
        if (!(block instanceof ButtonBlock)) return 20;
        Class<?> type = block.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField("pressTicks");
                if (field.getType() == int.class) {
                    field.setAccessible(true);
                    return Math.max(1, field.getInt(block));
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
            type = type.getSuperclass();
        }
        return 20;
    }

    /** Exact material-family button/lever sound. */
    public static SoundEvent toggleSound(Block block, boolean on) {
        String path = blockPath(block);
        if (path.contains("button")) {
            BlockSetType type = resolveBlockSetType(block);
            if (type != null) return on ? type.buttonClickOn() : type.buttonClickOff();
        }
        return SoundEvents.BLOCK_LEVER_CLICK;
    }

    /** Exact door/trapdoor/fence-gate sound family where Minecraft exposes one. */
    public static SoundEvent openCloseSound(Block block, boolean open) {
        String path = blockPath(block);
        if (path.contains("fence_gate")) {
            WoodType wood = resolveWoodType(block);
            if (wood != null) return open ? wood.fenceGateOpen() : wood.fenceGateClose();
            return open ? SoundEvents.BLOCK_FENCE_GATE_OPEN : SoundEvents.BLOCK_FENCE_GATE_CLOSE;
        }
        BlockSetType type = resolveBlockSetType(block);
        if (type != null) {
            if (path.contains("trapdoor")) return open ? type.trapdoorOpen() : type.trapdoorClose();
            if (path.contains("door")) return open ? type.doorOpen() : type.doorClose();
        }
        if (path.contains("trapdoor")) return open ? SoundEvents.BLOCK_WOODEN_TRAPDOOR_OPEN : SoundEvents.BLOCK_WOODEN_TRAPDOOR_CLOSE;
        return open ? SoundEvents.BLOCK_WOODEN_DOOR_OPEN : SoundEvents.BLOCK_WOODEN_DOOR_CLOSE;
    }

    public static SoundEvent pressurePlateSound(Block block, boolean on) {
        BlockSetType type = resolveBlockSetType(block);
        if (type != null) return on ? type.pressurePlateClickOn() : type.pressurePlateClickOff();
        return on ? SoundEvents.BLOCK_STONE_PRESSURE_PLATE_CLICK_ON : SoundEvents.BLOCK_STONE_PRESSURE_PLATE_CLICK_OFF;
    }

    public static SoundEvent containerSound(Block block, boolean open) {
        String path = blockPath(block);
        if (path.contains("ender_chest")) return open ? SoundEvents.BLOCK_ENDER_CHEST_OPEN : SoundEvents.BLOCK_ENDER_CHEST_CLOSE;
        if (path.contains("shulker_box")) return open ? SoundEvents.BLOCK_SHULKER_BOX_OPEN : SoundEvents.BLOCK_SHULKER_BOX_CLOSE;
        if (path.contains("barrel")) return open ? SoundEvents.BLOCK_BARREL_OPEN : SoundEvents.BLOCK_BARREL_CLOSE;
        if (path.contains("chest")) return open ? SoundEvents.BLOCK_CHEST_OPEN : SoundEvents.BLOCK_CHEST_CLOSE;
        return null;
    }

    public static SoundEvent musicDiscSoundFromRegistryId(String registryId) {
        Identifier id = parseIdentifier(registryId);
        if (id == null || !Registries.ITEM.containsId(id)) return null;
        Item item = Registries.ITEM.get(id);
        return item instanceof MusicDiscItem disc ? disc.getSound() : null;
    }

    public static SoundEvent itemPressSoundFromRegistryId(String registryId) {
        Identifier id = parseIdentifier(registryId);
        if (id == null || !Registries.ITEM.containsId(id)) return null;
        return itemUseSounds(Registries.ITEM.get(id)).press();
    }

    private static BlockSetType resolveBlockSetType(Block block) {
        if (block instanceof DoorBlock door) return door.getBlockSetType();
        BlockSetType reflected = reflectTypedField(block, BlockSetType.class, BLOCK_SET_TYPE_FIELDS);
        if (reflected != null) return reflected;
        String path = blockPath(block);
        if (path.contains("polished_blackstone")) return BlockSetType.POLISHED_BLACKSTONE;
        if (path.startsWith("iron_") || path.equals("iron_door") || path.equals("iron_trapdoor")
                || path.contains("heavy_weighted_pressure_plate")) return BlockSetType.IRON;
        if (path.startsWith("gold_") || path.contains("light_weighted_pressure_plate")) return BlockSetType.GOLD;
        if (path.contains("stone_button") || path.contains("stone_pressure_plate")) return BlockSetType.STONE;
        if (path.startsWith("spruce_")) return BlockSetType.SPRUCE;
        if (path.startsWith("birch_")) return BlockSetType.BIRCH;
        if (path.startsWith("jungle_")) return BlockSetType.JUNGLE;
        if (path.startsWith("acacia_")) return BlockSetType.ACACIA;
        if (path.startsWith("cherry_")) return BlockSetType.CHERRY;
        if (path.startsWith("dark_oak_")) return BlockSetType.DARK_OAK;
        if (path.startsWith("mangrove_")) return BlockSetType.MANGROVE;
        if (path.startsWith("bamboo_")) return BlockSetType.BAMBOO;
        if (path.startsWith("crimson_")) return BlockSetType.CRIMSON;
        if (path.startsWith("warped_")) return BlockSetType.WARPED;
        if (path.startsWith("oak_")) return BlockSetType.OAK;

        // This also gives custom registered BlockSetTypes a chance when their
        // sound group is distinctive.
        BlockSoundGroup group = block == null ? null : block.getDefaultState().getSoundGroup();
        if (group != null) {
            return BlockSetType.stream().filter(type -> type.soundType() == group).findFirst().orElse(null);
        }
        return null;
    }

    private static WoodType resolveWoodType(Block block) {
        WoodType reflected = reflectTypedField(block, WoodType.class, WOOD_TYPE_FIELDS);
        if (reflected != null) return reflected;
        String path = blockPath(block);
        if (path.startsWith("spruce_")) return WoodType.SPRUCE;
        if (path.startsWith("birch_")) return WoodType.BIRCH;
        if (path.startsWith("jungle_")) return WoodType.JUNGLE;
        if (path.startsWith("acacia_")) return WoodType.ACACIA;
        if (path.startsWith("cherry_")) return WoodType.CHERRY;
        if (path.startsWith("dark_oak_")) return WoodType.DARK_OAK;
        if (path.startsWith("mangrove_")) return WoodType.MANGROVE;
        if (path.startsWith("bamboo_")) return WoodType.BAMBOO;
        if (path.startsWith("crimson_")) return WoodType.CRIMSON;
        if (path.startsWith("warped_")) return WoodType.WARPED;
        if (path.startsWith("oak_")) return WoodType.OAK;
        BlockSoundGroup group = block == null ? null : block.getDefaultState().getSoundGroup();
        if (group != null) {
            return WoodType.stream().filter(type -> type.soundType() == group).findFirst().orElse(null);
        }
        return null;
    }

    private static <T> T reflectTypedField(Object owner, Class<T> type, Map<Class<?>, Optional<Field>> cache) {
        if (owner == null || type == null) return null;
        Optional<Field> optional = cache.computeIfAbsent(owner.getClass(), cls -> {
            Class<?> cursor = cls;
            while (cursor != null && cursor != Object.class) {
                for (Field field : cursor.getDeclaredFields()) {
                    if (!type.isAssignableFrom(field.getType())) continue;
                    try {
                        field.setAccessible(true);
                        return Optional.of(field);
                    } catch (RuntimeException ignored) {
                        // Continue through the hierarchy. Filename/material fallbacks
                        // below remain available when reflection is restricted.
                    }
                }
                cursor = cursor.getSuperclass();
            }
            return Optional.empty();
        });
        if (optional.isEmpty()) return null;
        try {
            Object value = optional.get().get(owner);
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (IllegalAccessException | RuntimeException ignored) {
            return null;
        }
    }

    private static UseSounds bucketSounds(String path) {
        if (path.contains("lava")) return one(SoundEvents.ITEM_BUCKET_EMPTY_LAVA, 0.30F);
        if (path.contains("powder_snow")) return one(SoundEvents.ITEM_BUCKET_EMPTY_POWDER_SNOW, 0.30F);
        if (path.equals("bucket")) return one(SoundEvents.ITEM_BUCKET_FILL, 0.28F);
        return one(SoundEvents.ITEM_BUCKET_EMPTY, 0.28F);
    }

    private static UseSounds one(SoundEvent sound, float volume) {
        return new UseSounds(sound, null, volume, 1.0F);
    }

    private static String blockPath(Block block) {
        Identifier id = block == null ? null : Registries.BLOCK.getId(block);
        return id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
    }

    private static Identifier parseIdentifier(String value) {
        if (value == null || value.isBlank()) return null;
        try { return new Identifier(value.trim()); }
        catch (RuntimeException ignored) { return null; }
    }
}
