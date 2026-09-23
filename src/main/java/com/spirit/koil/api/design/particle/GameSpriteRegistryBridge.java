package com.spirit.koil.api.design.particle;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import com.spirit.koil.api.design.particle.UiParticleEngine.ParticleBuilder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.spirit.koil.api.design.particle.UiParticleEngine.*;

/**
 * Code-backed sprite adapters for every registered block and item.
 *
 * <p>These are deliberately not authored "effects". They are capability
 * mirrors, similar to {@link GameParticleRegistryBridge}. Every vanilla or
 * modded registry entry automatically receives the current Koil interaction,
 * relation and interaction behavior without requiring a JSON definition that can
 * go stale when the engine grows. Registered blocks are grid-owned and never use
 * free sprite rotation; registered items remain dynamic actors.</p>
 *
 * <p>Ids:</p>
 * <ul>
 *   <li>{@code block.minecraft.redstone_lamp}</li>
 *   <li>{@code block.minecraft.piston}</li>
 *   <li>{@code item.minecraft.diamond}</li>
 *   <li>{@code item.<modid>.<path>}</li>
 * </ul>
 */
public final class GameSpriteRegistryBridge {
    private static final Set<String> REGISTERED_IDS = new LinkedHashSet<>();
    private static boolean registered;

    private GameSpriteRegistryBridge() { }

    public static synchronized void registerAllAvailable() {
        if (registered) return;
        unregisterMirrors();

        for (Identifier id : Registries.BLOCK.getIds()) {
            Block block = Registries.BLOCK.get(id);
            if (block == null || block == Blocks.AIR) continue;
            registerBlock(id, block);
        }
        for (Identifier id : Registries.ITEM.getIds()) {
            Item item = Registries.ITEM.get(id);
            if (item == null || item == Items.AIR) continue;
            registerItem(id, item);
        }
        registered = true;
    }

    public static synchronized void invalidateResourceCache() {
        // Keep the previous adapters usable until the next registration pass.
        // registerAllAvailable() will atomically replace them after resources reload.
        registered = false;
    }

    public static synchronized List<String> registeredIds() {
        return List.copyOf(REGISTERED_IDS);
    }

    public static synchronized boolean containsId(String id) {
        return id != null && REGISTERED_IDS.contains(id.trim().toLowerCase(Locale.ROOT));
    }

    private static void unregisterMirrors() {
        for (String id : REGISTERED_IDS) UiParticleRegistry.unregister(id);
        REGISTERED_IDS.clear();
    }

    private static void registerBlock(Identifier registryId, Block block) {
        String effectId = capabilityId("block", registryId);
        if (UiParticleRegistry.contains(effectId)) return;
        UiParticleRegistry.register(UiParticleEffect.builder(effectId)
                .selectionFamily("registry_block_capability")
                .weight(1)
                .onBegin(ctx -> {
                    float x = ctx.centerX() + ctx.random(-3.0F, 3.0F);
                    float y = ctx.centerY() + ctx.random(-3.0F, 3.0F);
                    ParticleBuilder builder = configureBlockBuilder(ctx.particle(Shape.BLOCK_SHARD, x, y), registryId, block);
                    ctx.spawn(builder);
                })
                .build());
        REGISTERED_IDS.add(effectId);
    }

    private static void registerItem(Identifier registryId, Item item) {
        String effectId = capabilityId("item", registryId);
        if (UiParticleRegistry.contains(effectId)) return;
        UiParticleRegistry.register(UiParticleEffect.builder(effectId)
                .selectionFamily("registry_item_capability")
                .weight(1)
                .onBegin(ctx -> {
                    float x = ctx.centerX() + ctx.random(-3.0F, 3.0F);
                    float y = ctx.centerY() + ctx.random(-3.0F, 3.0F);
                    ParticleBuilder builder = configureItemBuilder(ctx.particle(Shape.PIXEL, x, y), registryId, item);
                    ctx.spawn(builder);
                })
                .build());
        REGISTERED_IDS.add(effectId);
    }


    /** Shared block-sprite configuration used by registry effects and runtime rebinds. */
    static ParticleBuilder configureBlockBuilder(ParticleBuilder builder, Identifier registryId, Block block) {
        GameSpriteBehavior.BlockProfile profile = GameSpriteBehavior.blockProfile(block);
        builder.blockIcon(block)
                .visualPixels(15.0F)
                .lifetime(120.0F)
                .layer(Layer.FRONT)
                .rotationPolicy(RotationPolicy.LOCKED)
                .rotation(0.0F)
                .angularVelocity(0.0F)
                .material(profile.material())
                .gravity(0.0F)
                .drag(1.0F)
                .restitution(0.0F)
                .surfaceFriction(profile.surfaceFriction())
                .mass(profile.mass())
                .solidity(profile.solidity())
                .soundProfile(GameSpriteSoundResolver.blockProfile(block))
                .collideScreen(true)
                .bounceCeiling(true)
                .collideButtons(false)
                .collideTarget(false)
                .particleCollision(false)
                .contactMode(ParticleContactMode.IGNORE)
                .simulationLayer(0)
                .crossLayerInteractions(true)
                .allInteractionLayers()
                .interactive(true)
                .hoverInteraction(true)
                .clickInteraction(true)
                .consumePointerInput(true)
                .hoverResetAge(true)
                .selectable(true, true)
                .dragInteraction(true, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0.035F)
                .dragMassPhysics(true, 1.35F)
                .swipeInteraction(false, 2050.0F, 0.0F, 0.0F, 420L)
                .swipeMassPhysics(false, 1.55F, 0.0F, 0.0F)
                .scrollRotation(false, false, 90.0F, false, 0.0F, 90.0F, 0.0F, false)
                .keyboardInteraction(false,
                        GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D,
                        GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_RIGHT,
                        GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_E)
                .relationIdentity(capabilityId("block", registryId), profile.role())
                .relationInteraction(true, profile.sensorRadius(), 1.05F, false, true)
                .signal("power", profile.initialPower())
                .data("__koil_block_state", UiBlockFaceTextureResolver.defaultStateSignature(block))
                .tag("registry_sprite")
                .tag("registry_block")
                .tag("block")
                .tag("block:" + registryId)
                .tag("grid_locked_block")
                .tag(profile.role())
                .data("registry_id", registryId.toString())
                .data("native_capability", "block")
                .data("__koil_vw_grid_locked", true)
                .data("__koil_vw_locked", true)
                .data("__koil_profile_tags", String.join("|", profile.tags()))
                .onInteract(ev -> GameSpriteBehavior.handleBlockInteraction(block, ev));
        for (String tag : profile.tags()) {
            if (tag == null) continue;
            String normalized = tag.toLowerCase(Locale.ROOT);
            if ("scroll_rotatable".equals(normalized) || "wheel_rotatable".equals(normalized)) continue;
            builder.tag(tag);
        }
        return builder;
    }

    /** Shared item-sprite configuration, including items emitted by virtual blocks. */
    static ParticleBuilder configureItemBuilder(ParticleBuilder builder, Identifier registryId, Item item) {
        GameSpriteBehavior.ItemProfile profile = GameSpriteBehavior.itemProfile(item);
        builder.itemIcon(item)
                .visualPixels(13.0F)
                .lifetime(120.0F)
                .layer(Layer.FRONT)
                .rotationPolicy(RotationPolicy.FREE)
                .gravity(profile.gravity())
                .drag(profile.drag())
                .restitution(profile.restitution())
                .surfaceFriction(0.92F)
                .mass(profile.mass())
                .solidity(profile.solidity())
                .soundProfile(GameSpriteBehavior.itemSoundProfile(item))
                .collideScreen(true)
                .bounceCeiling(true)
                .collideButtons(false)
                .collideTarget(false)
                .particleCollision(true)
                .contactMode(ParticleContactMode.BOUNCE)
                .simulationLayer(0)
                .crossLayerInteractions(true)
                .allInteractionLayers()
                .interactive(true)
                .hoverInteraction(true)
                .clickInteraction(true)
                .consumePointerInput(true)
                .hoverResetAge(true)
                .selectable(true, true)
                .dragInteraction(true, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0.045F)
                .dragMassPhysics(true, 1.28F)
                .swipeInteraction(true, 1900.0F, 0.0060F, 10.0F, 390L)
                .swipeMassPhysics(true, 1.42F, 0.08F, 95.0F)
                .scrollRotation(true, false, 15.0F, false, 0.45F, 0.0F, 420.0F, false)
                .keyboardInteraction(true,
                        GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D,
                        GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_RIGHT,
                        GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_E)
                .relationIdentity(capabilityId("item", registryId), profile.role())
                .relationInteraction(true, profile.sensorRadius(), 1.0F, false, true)
                .settleOnSurfaces(false)
                .tag("registry_sprite")
                .tag("registry_item")
                .tag("item")
                .tag("item:" + registryId)
                .tag("scroll_rotatable")
                .tag(profile.role())
                .data("registry_id", registryId.toString())
                .data("native_capability", "item")
                .data("world_collision", true)
                .data("world_gameplay_contact", true)
                .data("__koil_profile_tags", String.join("|", profile.tags()))
                .onInteract(ev -> GameSpriteBehavior.handleItemInteraction(item, ev));
        for (String tag : profile.tags()) builder.tag(tag);
        return builder;
    }

    private static String capabilityId(String kind, Identifier id) {
        return kind + "." + id.getNamespace().toLowerCase(Locale.ROOT) + "."
                + id.getPath().toLowerCase(Locale.ROOT).replace('/', '.');
    }
}
