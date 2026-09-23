package com.spirit.koil.api.design.particle;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ComposterBlock;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Equipment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.MusicDiscItem;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.UseAction;
import org.lwjgl.glfw.GLFW;

import com.spirit.koil.api.design.particle.UiParticleEngine.ParticleEventContext;
import com.spirit.koil.api.design.particle.UiParticleEngine.ParticleInteractionContext;
import com.spirit.koil.api.design.particle.UiParticleEngine.ParticleRelationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.spirit.koil.api.design.particle.UiParticleEngine.*;

/**
 * Shared native behavior profiles used by the registry-backed block/item
 * sprite mirrors. This is intentionally capability-oriented: behavior is
 * derived from registry identity/material families instead of one Java method
 * per authored effect.
 */
public final class GameSpriteBehavior {
    public record BlockProfile(String role, List<String> tags,
                               UiParticleMaterials.Material material,
                               float mass, float solidity, float gravity, float drag,
                               float restitution, float surfaceFriction,
                               float sensorRadius, float initialPower) { }

    public record ItemProfile(String role, List<String> tags,
                              float mass, float solidity, float gravity,
                              float drag, float restitution, float sensorRadius) { }

    private GameSpriteBehavior() { }

    private static volatile Map<Item, Integer> furnaceFuelTimes;

    public static BlockProfile blockProfile(Block block) {
        String path = blockPath(block);
        BlockCapabilityRegistry.BlockProfile capabilityProfile = BlockCapabilityRegistry.profile(block);
        List<String> tags = new ArrayList<>();
        String role = VirtualBlockServiceRegistry.primaryRole(capabilityProfile);

        for (BlockCapabilityRegistry.Capability capability : capabilityProfile.capabilities()) {
            addTag(tags, "capability:" + capability.name().toLowerCase(Locale.ROOT));
            addTag(tags, "service:" + VirtualBlockServiceRegistry.serviceFor(capability).name().toLowerCase(Locale.ROOT));
        }

        BlockState defaultState = block.getDefaultState();
        if (defaultState.hasBlockEntity()) addTag(tags, "block_entity");
        if (capabilityProfile.has(BlockCapabilityRegistry.Capability.INVENTORY)) addTag(tags, "inventory");
        addTag(tags, "piston_behavior:" + defaultState.getPistonBehavior().name().toLowerCase(Locale.ROOT));
        for (var property : block.getStateManager().getProperties()) {
            addTag(tags, "state:" + property.getName().toLowerCase(Locale.ROOT));
        }

        UiParticleMaterials.Material material = materialForBlockPath(path);
        float mass = massForBlockPath(path);
        float solidity = material == UiParticleMaterials.Material.WATER ? 0.12F
                : material == UiParticleMaterials.Material.HONEY ? 0.40F : 0.92F;
        float gravity = 178.0F;
        float drag = 0.994F;
        float restitution = 0.50F;
        float friction = 0.88F;
        if (material == UiParticleMaterials.Material.SLIME) {
            gravity = 165.0F;
            restitution = 0.91F;
            friction = 0.97F;
        }
        if (material == UiParticleMaterials.Material.HONEY) {
            gravity = 150.0F;
            drag = 0.985F;
            restitution = 0.12F;
            friction = 0.56F;
        }
        if (material == UiParticleMaterials.Material.SAND) {
            gravity = 206.0F;
            restitution = 0.27F;
        }
        if (material == UiParticleMaterials.Material.WATER) {
            gravity = 28.0F;
            drag = 0.96F;
            restitution = 0.04F;
            friction = 0.62F;
        }

        float sensor = capabilityProfile.has(BlockCapabilityRegistry.Capability.DIRECTIONAL_REDSTONE)
                || capabilityProfile.has(BlockCapabilityRegistry.Capability.ENTITY_CONTACT) ? 24.0F : 18.0F;
        if (capabilityProfile.has(BlockCapabilityRegistry.Capability.HOPPER_TRANSFER)
                || capabilityProfile.has(BlockCapabilityRegistry.Capability.VIBRATION_LISTENER)) sensor = 34.0F;

        float initialPower = (block == Blocks.REDSTONE_BLOCK || block == Blocks.REDSTONE_TORCH
                || block == Blocks.REDSTONE_WALL_TORCH) ? 15.0F : 0.0F;
        return new BlockProfile(role, List.copyOf(tags), material, mass, solidity, gravity, drag,
                restitution, friction, sensor, initialPower);
    }

    public static ItemProfile itemProfile(Item item) {
        String path = itemPath(item);
        List<String> tags = new ArrayList<>();
        String role = "item";
        ItemStack stack = item.getDefaultStack();
        UseAction useAction = stack.getUseAction();
        tags.add("use_action:" + useAction.name().toLowerCase(Locale.ROOT));
        if (item instanceof BlockItem) tags.add("block_item");
        if (item instanceof MusicDiscItem) tags.add("music_disc");
        if (Equipment.fromStack(stack) != null) tags.add("equipment");
        if (path.equals("redstone")) { tags.add("redstone_item"); tags.add("redstone_component"); role = "redstone_item"; }
        if (path.contains("compass")) { tags.add("compass"); role = "compass"; }
        if (path.equals("flint_and_steel") || path.equals("fire_charge")) tags.add("igniter");
        if (path.equals("water_bucket")) tags.add("water_container");
        if (path.equals("lava_bucket")) tags.add("lava_container");
        if (path.contains("music_disc")) tags.add("music_disc");
        if (path.contains("potion")) tags.add("potion");
        if (path.startsWith("raw_") || path.contains("raw_")) tags.add("smeltable");
        if (containsAny(path, "arrow", "trident", "snowball", "egg")) tags.add("projectile_item");
        if (path.contains("minecart")) tags.add("minecart_item");
        if (isFurnaceFuel(item)) tags.add("fuel");
        if (path.equals("diamond") || path.equals("emerald") || path.equals("iron_ingot")
                || path.equals("gold_ingot") || path.equals("netherite_ingot")) tags.add("beacon_payment");
        if (path.equals("lapis_lazuli")) tags.add("lapis");
        if (path.contains("book")) tags.add("book");
        if (path.equals("writable_book") || path.equals("written_book")) tags.add("lectern_book");
        if (path.equals("powder_snow_bucket")) tags.add("powder_snow_container");
        if (containsAny(path, "wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "bone_meal")) tags.add("planting_item");
        if (path.equals("bone_meal")) tags.add("bone_meal");
        if (path.equals("honeycomb")) tags.add("wax_item");
        if (path.endsWith("_axe")) tags.add("axe_tool");
        if (path.endsWith("_hoe")) tags.add("hoe_tool");
        if (path.endsWith("_shovel")) tags.add("shovel_tool");
        if (path.endsWith("_pickaxe")) tags.add("pickaxe_tool");
        if (path.equals("glass_bottle")) tags.add("glass_bottle");
        if (path.equals("shears")) tags.add("shears");
        if (path.equals("glowstone")) tags.add("respawn_anchor_fuel");
        if (path.endsWith("_dye") || path.equals("ink_sac") || path.equals("glow_ink_sac")) tags.add("dye");
        if (path.endsWith("_banner")) tags.add("banner");
        if (path.equals("paper")) tags.add("paper");
        if (path.equals("map") || path.equals("filled_map")) tags.add("map_item");
        if (path.contains("smithing_template")) { tags.add("smithing_input"); tags.add("smithing_template"); }
        if (path.contains("ingot") || path.equals("diamond") || path.equals("quartz") || path.equals("redstone")) {
            tags.add("smithing_input");
            tags.add("smithing_material");
        }
        if (stack.isDamageable() || Equipment.fromStack(stack) != null
                || containsAny(path, "sword", "pickaxe", "axe", "shovel", "hoe", "helmet", "chestplate", "leggings", "boots", "elytra")) {
            tags.add("damageable_item");
            tags.add("smithing_base");
            tags.add("anvil_input");
            tags.add("grindstone_input");
        }
        if (path.equals("enchanted_book")) { tags.add("grindstone_input"); tags.add("anvil_input"); }
        if (path.equals("glass_pane")) tags.add("glass_pane");
        if (path.equals("blaze_powder")) tags.add("brewing_fuel");
        if (path.equals("potion") || path.equals("splash_potion") || path.equals("lingering_potion") || path.equals("glass_bottle")) tags.add("brewing_bottle");
        if (containsAny(path, "nether_wart", "glowstone_dust", "gunpowder", "dragon_breath", "fermented_spider_eye",
                "sugar", "rabbit_foot", "glistering_melon_slice", "spider_eye", "pufferfish", "magma_cream",
                "golden_carrot", "phantom_membrane", "redstone")) tags.add("brewing_ingredient");
        try {
            if (ComposterBlock.ITEM_TO_LEVEL_INCREASE_CHANCE.containsKey(item)) tags.add("compostable");
        } catch (RuntimeException ignored) { }

        float mass = massForItemPath(path);
        float gravity = containsAny(path, "feather", "paper", "map") ? 65.0F : 145.0F;
        float restitution = containsAny(path, "slime_ball") ? 0.82F : containsAny(path, "snowball", "egg") ? 0.48F : 0.34F;
        return new ItemProfile(role, List.copyOf(tags), mass, 0.42F, gravity, 0.992F, restitution, 22.0F);
    }

    /** Minecraft-native physics audio used by every registry-backed item sprite. */
    public static UiParticleSoundProfile itemSoundProfile(Item item) {
        return GameSpriteSoundResolver.itemPhysicsProfile(item);
    }

    public static void handleBlockInteraction(Block block, ParticleInteractionContext ev) {
        Block liveBlock = ev.blockIcon() != null ? ev.blockIcon() : block;
        // Registered blocks are virtual grid cells. Their transform is never a
        // gameplay control surface, so keyboard/scroll torque is intentionally
        // not forwarded here. Direction is edited through BlockState mechanics.

        if (ev.type() == ParticleInteractionType.PRESS
                && ev.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && !ev.dragging()) {
            UiParticleSoundProfile profile = GameSpriteSoundResolver.blockProfile(liveBlock);
            if (profile.bounce() != null) ev.playSound(profile.bounce());
        }

        if (ev.type() == ParticleInteractionType.SIGNAL_CHANGED && "power".equals(ev.lastSignalName())) {
            BlockCapabilityRegistry.BlockProfile profile = BlockCapabilityRegistry.profile(liveBlock);
            if (profile != null && (profile.has(BlockCapabilityRegistry.Capability.DIRECTIONAL_REDSTONE)
                    || profile.has(BlockCapabilityRegistry.Capability.REDSTONE_POWER_SOURCE)
                    || profile.has(BlockCapabilityRegistry.Capability.CONTACT_REDSTONE_SOURCE))) {
                emitRedstoneCue(ev, ev.lastSignalValue());
            }
        }
    }

    public static void handleItemInteraction(Item item, ParticleInteractionContext ev) {
        handleKeyboard(ev, false);
        Item liveItem = ev.itemIcon() != null ? ev.itemIcon() : item;
        String path = itemPath(liveItem);
        GameSpriteSoundResolver.UseSounds sounds = GameSpriteSoundResolver.itemUseSounds(liveItem);

        if (ev.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (ev.type() == ParticleInteractionType.PRESS && sounds.press() != null) {
                ev.playSound(sounds.press(), sounds.volume(), sounds.pitch());
            } else if (ev.type() == ParticleInteractionType.RELEASE && sounds.release() != null) {
                ev.playSound(sounds.release(), sounds.volume(), sounds.pitch());
            }
        }

        if (ev.type() == ParticleInteractionType.PRESS && ev.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (path.contains("ender_pearl")) {
                float dx = ev.pointerX() - ev.x();
                float dy = ev.pointerY() - ev.y();
                float len = Math.max(1.0F, (float) Math.sqrt(dx * dx + dy * dy));
                ev.addVelocity(dx / len * 72.0F, dy / len * 72.0F);
            } else if (path.contains("firework_rocket")) {
                ev.addVelocity(0.0F, -105.0F / Math.max(0.7F, ev.mass()));
            }
        }
    }

    public static void tickBlock(Block block, ParticleEventContext ev) {
        // Registered block gameplay is owned exclusively by KoilVirtualBlockWorld.
        // Per-particle ticks remain available for authored particles, but registry
        // blocks do not run a second gameplay implementation here.
    }

    public static void tickItem(Item item, ParticleEventContext ev) {
        // Registry items intentionally share the same physical interaction core.
        // Item-specific continuous behavior is added only when it represents an
        // actual Minecraft mechanic rather than decorative motion.
    }

    public static void handleBlockRelation(Block block, ParticleRelationContext ev) {
        // Registry block gameplay relations are owned by KoilVirtualInteractionRouter.
        // Physical overlap is never promoted to ITEM_USE_ON_BLOCK here.
    }

    public static void handleItemRelation(Item item, ParticleRelationContext ev) {
        // Item-to-block gameplay is owned by the block's virtual-world service.
        // Item-to-item relations remain physical only.
    }


    public static void handleParticleInteraction(Identifier particleType, ParticleInteractionContext ev) {
        handleKeyboard(ev, false);
        if (ev.type() == ParticleInteractionType.PRESS && ev.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            ev.angularVelocity(0.0F);
            ev.velocity(0.0F, 0.0F);
        }
    }

    public static void handleParticleRelation(Identifier particleType, ParticleRelationContext ev) {
        // Registry particle mirrors participate in logical touch/overlap/sensor
        // detection, but do not invent gameplay semantics. JSON/addons can layer
        // rules on top without needing a separate compatibility path.
    }

    private static void handleKeyboard(ParticleInteractionContext ev, boolean block) {
        if (ev.type() == ParticleInteractionType.KEY_HELD) {
            float base = block ? 62.0F : 48.0F;
            if (ev.key() == GLFW.GLFW_KEY_W || ev.key() == GLFW.GLFW_KEY_UP) ev.applyForce(0.0F, -base);
            if (ev.key() == GLFW.GLFW_KEY_S || ev.key() == GLFW.GLFW_KEY_DOWN) ev.applyForce(0.0F, base);
            if (ev.key() == GLFW.GLFW_KEY_A || ev.key() == GLFW.GLFW_KEY_LEFT) ev.applyForce(-base, 0.0F);
            if (ev.key() == GLFW.GLFW_KEY_D || ev.key() == GLFW.GLFW_KEY_RIGHT) ev.applyForce(base, 0.0F);
            if (ev.key() == GLFW.GLFW_KEY_Q) ev.addAngularVelocity(-5.0F / Math.max(0.5F, ev.mass()));
            if (ev.key() == GLFW.GLFW_KEY_E) ev.addAngularVelocity(5.0F / Math.max(0.5F, ev.mass()));
        }
        if (ev.type() == ParticleInteractionType.KEY_PRESS && ev.key() == GLFW.GLFW_KEY_SPACE) {
            ev.applyForce(0.0F, -(block ? 170.0F : 125.0F));
        }
    }

    private static void emitRedstoneCue(ParticleInteractionContext ev, float power) {
        int color = power > 0.0F ? 0xF03A24 : 0x541111;
        ev.pulse(0.13F, color, power > 0.0F ? 0.62F : 0.38F, Layer.FRONT);
        for (int i = 0; i < (power > 0.0F ? 3 : 1); i++) {
            ev.spawn(ev.particle(Shape.REDSTONE)
                    .visual(VisualFamily.REDSTONE_DUST)
                    .color(color)
                    .visualPixels(ev.random(3.0F, 5.0F))
                    .velocity(ev.random(-18.0F, 18.0F), ev.random(-24.0F, -6.0F))
                    .gravity(45.0F).drag(0.98F).lifetime(ev.random(0.28F, 0.55F))
                    .particleCollision(false).collideButtons(false).collideScreen(false));
        }
    }

    private static boolean isFurnaceFuel(Item item) {
        if (item == null || item == Items.AIR) return false;
        Map<Item, Integer> fuels = furnaceFuelTimes;
        if (fuels == null) {
            synchronized (GameSpriteBehavior.class) {
                fuels = furnaceFuelTimes;
                if (fuels == null) {
                    try {
                        fuels = AbstractFurnaceBlockEntity.createFuelTimeMap();
                    } catch (RuntimeException ignored) {
                        fuels = Map.of();
                    }
                    furnaceFuelTimes = fuels;
                }
            }
        }
        return fuels.containsKey(item);
    }

    private static boolean hasStateKey(String state, String key) {
        if (state == null || key == null) return false;
        String needle = key.toLowerCase(Locale.ROOT) + "=";
        for (String token : state.toLowerCase(Locale.ROOT).split("[,;]")) if (token.trim().startsWith(needle)) return true;
        return false;
    }

    private static String mergeState(String state, String key, String value) {
        StringBuilder out = new StringBuilder();
        boolean replaced = false;
        if (state != null && !state.isBlank()) {
            for (String token : state.split("[,;]")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) continue;
                int eq = trimmed.indexOf('=');
                String existingKey = eq > 0 ? trimmed.substring(0, eq).trim() : trimmed;
                if (existingKey.equalsIgnoreCase(key)) {
                    if (!replaced) {
                        if (out.length() > 0) out.append(',');
                        out.append(key).append('=').append(value);
                        replaced = true;
                    }
                } else {
                    if (out.length() > 0) out.append(',');
                    out.append(trimmed);
                }
            }
        }
        if (!replaced) {
            if (out.length() > 0) out.append(',');
            out.append(key).append('=').append(value);
        }
        return out.toString();
    }

    private static UiParticleMaterials.Material materialForBlockPath(String path) {
        if (path.contains("slime")) return UiParticleMaterials.Material.SLIME;
        if (path.contains("honey")) return UiParticleMaterials.Material.HONEY;
        if (containsAny(path, "sand", "gravel", "concrete_powder")) return UiParticleMaterials.Material.SAND;
        if (containsAny(path, "snow", "powder_snow")) return UiParticleMaterials.Material.SNOW;
        if (containsAny(path, "amethyst", "glass", "ice")) return UiParticleMaterials.Material.AMETHYST;
        if (containsAny(path, "redstone", "repeater", "comparator", "piston", "observer")) return UiParticleMaterials.Material.REDSTONE;
        if (containsAny(path, "water", "bubble")) return UiParticleMaterials.Material.WATER;
        if (containsAny(path, "portal", "obsidian", "end_portal")) return UiParticleMaterials.Material.PORTAL;
        return UiParticleMaterials.Material.BLOCK;
    }

    private static float massForBlockPath(String path) {
        if (path.contains("anvil")) return 13.0F;
        if (containsAny(path, "obsidian", "netherite_block", "lodestone")) return 10.0F;
        if (containsAny(path, "iron_block", "gold_block", "copper", "cauldron", "hopper", "piston")) return 7.0F;
        if (containsAny(path, "deepslate", "stone", "brick", "basalt", "blackstone", "ore", "terracotta")) return 5.0F;
        if (containsAny(path, "log", "wood", "planks", "chest", "barrel")) return 3.1F;
        if (containsAny(path, "glass", "amethyst", "ice")) return 1.7F;
        if (path.contains("slime")) return 1.2F;
        if (path.contains("honey")) return 1.45F;
        if (containsAny(path, "wool", "leaves", "moss", "sponge")) return 0.72F;
        return 2.6F;
    }

    private static float massForItemPath(String path) {
        if (containsAny(path, "netherite", "anvil")) return 4.2F;
        if (containsAny(path, "trident", "crossbow", "shield", "bucket")) return 2.4F;
        if (containsAny(path, "pickaxe", "axe", "shovel", "hoe", "sword")) return 1.8F;
        if (containsAny(path, "ingot", "diamond", "emerald", "quartz", "amethyst")) return 1.15F;
        if (containsAny(path, "book", "map", "paper")) return 0.55F;
        if (containsAny(path, "feather", "seed", "string")) return 0.28F;
        if (containsAny(path, "snowball", "egg", "ender_pearl")) return 0.48F;
        return 0.85F;
    }

    private static String blockPath(Block block) {
        Identifier id = block == null ? null : Registries.BLOCK.getId(block);
        return id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
    }

    private static String itemPath(Item item) {
        Identifier id = item == null ? null : Registries.ITEM.getId(item);
        return id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
    }


    private static void addTag(List<String> tags, String tag) {
        if (tag != null && !tag.isBlank() && !tags.contains(tag)) tags.add(tag);
    }

    private static boolean containsAny(String value, String... parts) {
        for (String part : parts) if (value.contains(part)) return true;
        return false;
    }
}
