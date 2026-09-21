package com.spirit.koil.api.design.particle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static com.spirit.koil.api.design.particle.UiParticleEngine.*;

/**
 * Mirrors every particle type currently registered by Minecraft/Fabric into
 * Koil's screen-sprite registry. These are code-backed adapters, not JSON
 * definitions, so particles contributed by other mods are discovered too.
 *
 * <p>Ids are exposed as {@code <namespace>.<path>}, for example
 * {@code minecraft.heart}. The bridge reads the
 * particle's normal resource definition when one exists and uses its texture
 * list as an animated sequence. Parameterized vanilla particle types such as
 * block, item, dust and vibration remain spawnable as a generic visual family;
 * callers can provide command overrides such as texture, color, material and
 * physics values to specialize an individual spawn.</p>
 */
public final class GameParticleRegistryBridge {
    private static final Set<String> REGISTERED_IDS = new LinkedHashSet<>();
    private static boolean registered;

    private GameParticleRegistryBridge() { }

    public static synchronized void registerAllAvailable() {
        MinecraftClient client;
        try {
            client = MinecraftClient.getInstance();
        } catch (Throwable unavailable) {
            return;
        }
        if (client == null || client.getResourceManager() == null) return;
        if (registered) return;

        unregisterMirrors();
        for (Identifier typeId : Registries.PARTICLE_TYPE.getIds()) {
            register(typeId, resolveTextures(client, typeId));
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

    private static void register(Identifier typeId, List<Identifier> textures) {
        List<Identifier> immutableTextures = textures == null ? List.of() : List.copyOf(textures);
        ParticlePreset preset = preset(typeId.getPath().toLowerCase(Locale.ROOT));
        registerAlias(effectId(typeId), typeId, immutableTextures, preset);
    }

    private static void registerAlias(String effectId, Identifier typeId, List<Identifier> textures, ParticlePreset preset) {
        if (UiParticleRegistry.contains(effectId)) return;
        UiParticleRegistry.register(UiParticleEffect.builder(effectId)
                .themeColor(preset.color())
                .weight(1)
                .onBegin(ctx -> {
                    float angle = ctx.random(0.0F, (float) (Math.PI * 2.0));
                    float speed = ctx.random(preset.minSpeed(), preset.maxSpeed());
                    float x = ctx.targetX() + ctx.random(0.0F, Math.max(1.0F, ctx.targetWidth()));
                    float y = ctx.targetY() + ctx.random(0.0F, Math.max(1.0F, ctx.targetHeight()));
                    UiParticleEngine.ParticleBuilder particle = ctx.particle(preset.shape(), x, y)
                            .visual(preset.visual())
                            .behavior(preset.behavior())
                            .material(preset.material())
                            .color(preset.color())
                            .visualPixels(ctx.random(preset.minPixels(), preset.maxPixels()))
                            .velocity((float) Math.cos(angle) * speed,
                                    (float) Math.sin(angle) * speed + ctx.random(preset.minVerticalBias(), preset.maxVerticalBias()))
                            .gravity(preset.gravity())
                            .drag(preset.drag())
                            .lifetime(ctx.random(preset.minLife(), preset.maxLife()))
                            .layer(ctx.chance(0.34F) ? Layer.FRONT : Layer.BACK)
                                                        .collideButtons(false)
                            .collideScreen(true)
                            .particleCollision(false)
                            .animationSpeed(1.0F)
                            .animationLoop(true)
                            .textureAutoSize(true)
                            .tag("game_particle")
                            .tag("registry_particle")
                            .tag("particle:" + typeId)
                            .data("particle_type", typeId.toString())
                            .interactive(true)
                            .hoverInteraction(true)
                            .clickInteraction(true)
                            .hoverResetAge(true)
                            .selectable(true, true)
                            .dragInteraction(true, 0, 0.08F)
                            .dragMassPhysics(true, 1.0F)
                            .swipeInteraction(true, 1280.0F, 0.020F, 26.0F, 270L)
                            .swipeMassPhysics(true, 1.12F, 0.16F, 190.0F)
                            .keyboardInteraction(true, GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D,
                                    GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_RIGHT,
                                    GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_E)
                            .relationIdentity(effectId, "particle")
                            .relationInteraction(true, 14.0F, 1.0F, false, true)
                            .onInteract(ev -> GameSpriteBehavior.handleParticleInteraction(typeId, ev))
                            .onRelation(ev -> GameSpriteBehavior.handleParticleRelation(typeId, ev));
                    if (!textures.isEmpty()) particle.textureSequence(textures).textureTint(preset.tintable());
                    ctx.spawn(particle);
                })
                .build());
        REGISTERED_IDS.add(effectId);
    }

    private static List<Identifier> resolveTextures(MinecraftClient client, Identifier typeId) {
        Identifier definition = new Identifier(typeId.getNamespace(), "particles/" + typeId.getPath() + ".json");
        try {
            Optional<Resource> optional = client.getResourceManager().getResource(definition);
            if (optional.isEmpty()) return List.of();
            try (InputStreamReader reader = new InputStreamReader(optional.get().getInputStream(), StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) return List.of();
                JsonObject object = parsed.getAsJsonObject();
                JsonArray array = object.has("textures") && object.get("textures").isJsonArray()
                        ? object.getAsJsonArray("textures") : new JsonArray();
                List<Identifier> textures = new ArrayList<>();
                for (JsonElement element : array) {
                    if (!element.isJsonPrimitive()) continue;
                    Identifier spriteId = new Identifier(element.getAsString());
                    textures.add(new Identifier(spriteId.getNamespace(), "textures/particle/" + spriteId.getPath() + ".png"));
                }
                return textures;
            }
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String effectId(Identifier id) {
        return id.getNamespace().toLowerCase(Locale.ROOT) + "."
                + id.getPath().toLowerCase(Locale.ROOT).replace('/', '.');
    }

    private static ParticlePreset preset(String path) {
        // High-value Minecraft gameplay families get detached motion profiles that
        // mirror their vanilla role instead of falling through to the generic
        // ballistic adapter. Texture frames still come from the active resource
        // pack's normal particles/<id>.json definition.
        if (path.equals("campfire_cosy_smoke"))
            return new ParticlePreset(Shape.SMOKE, VisualFamily.SMOKE, Behavior.RISING_SMOKE, UiParticleMaterials.Material.SMOKE, 0xB7B7B7, 10, 16, 1, 7, -22, -12, -2, .985F, 2.2F, 4.2F, false);
        if (path.equals("campfire_signal_smoke"))
            return new ParticlePreset(Shape.SMOKE, VisualFamily.SMOKE, Behavior.RISING_SMOKE, UiParticleMaterials.Material.SMOKE, 0xB7B7B7, 11, 17, 1, 6, -30, -18, -2, .988F, 3.2F, 5.4F, false);
        if (path.equals("happy_villager"))
            return new ParticlePreset(Shape.PIXEL, VisualFamily.AUTO, Behavior.RISE_AND_WANDER, UiParticleMaterials.Material.DEFAULT, 0xFFFFFF, 6, 9, 2, 10, -22, -9, 1, .975F, .75F, 1.35F, false);
        if (path.equals("wax_on") || path.equals("wax_off") || path.equals("scrape") || path.equals("electric_spark"))
            return new ParticlePreset(Shape.SPARK, VisualFamily.FIREWORK_SPARK, Behavior.BALLISTIC, UiParticleMaterials.Material.DEFAULT, 0xFFFFFF, 5, 8, 18, 58, -16, 16, 20, .965F, .35F, .85F, false);
        if (path.equals("explosion"))
            return new ParticlePreset(Shape.CLOUD, VisualFamily.EXPLOSION, Behavior.DRIFT, UiParticleMaterials.Material.SMOKE, 0xFFFFFF, 13, 18, 1, 8, -5, 5, 0, .94F, .45F, .85F, false);
        if (path.equals("poof"))
            return new ParticlePreset(Shape.CLOUD, VisualFamily.SMOKE, Behavior.DRIFT, UiParticleMaterials.Material.SMOKE, 0xFFFFFF, 6, 11, 8, 28, -14, 8, 8, .94F, .35F, .8F, false);
        if (path.contains("smoke") || path.contains("ash") || path.contains("spore"))
            return new ParticlePreset(Shape.SMOKE, VisualFamily.SMOKE, Behavior.WANDER, UiParticleMaterials.Material.SMOKE, 0xB7B7B7, 8, 14, 4, 22, -24, -10, -6, .965F, 1.2F, 2.8F, false);
        if (path.contains("bubble") || path.contains("splash") || path.contains("drip") || path.contains("water"))
            return new ParticlePreset(Shape.DROP, VisualFamily.SPLASH, Behavior.BALLISTIC, UiParticleMaterials.Material.WATER, 0x76BDF2, 6, 10, 8, 42, -18, 25, 70, .985F, .7F, 1.8F, true);
        if (path.contains("heart"))
            return new ParticlePreset(Shape.HEART, VisualFamily.HEART, Behavior.DRIFT, UiParticleMaterials.Material.DEFAULT, 0xFF617B, 9, 13, 3, 16, -26, -15, -8, .975F, .9F, 1.8F, false);
        if (path.contains("note"))
            return new ParticlePreset(Shape.NOTE, VisualFamily.NOTE, Behavior.DRIFT, UiParticleMaterials.Material.DEFAULT, 0xFFFFFF, 9, 13, 4, 20, -22, -8, -5, .975F, .8F, 1.6F, true);
        if (path.contains("portal") || path.contains("nautilus"))
            return new ParticlePreset(Shape.PIXEL, VisualFamily.PORTAL_MOTE, Behavior.SPIRAL, UiParticleMaterials.Material.PORTAL, 0xA86BFF, 6, 10, 12, 48, 0, 0, -3, .98F, .9F, 2.0F, true);
        if (path.contains("flame") || path.contains("lava"))
            return new ParticlePreset(Shape.FLAME, path.contains("soul") ? VisualFamily.SOUL_FLAME : VisualFamily.FLAME, Behavior.DRIFT, path.contains("soul") ? UiParticleMaterials.Material.SOUL_FIRE : UiParticleMaterials.Material.DEFAULT, path.contains("soul") ? 0x67E7FF : 0xFFB13B, 7, 11, 5, 28, -30, -12, -7, .97F, .7F, 1.5F, false);
        if (path.contains("crit") || path.contains("damage"))
            return new ParticlePreset(Shape.CRIT, VisualFamily.CRIT, Behavior.BALLISTIC, UiParticleMaterials.Material.DEFAULT, 0xFFFFFF, 6, 9, 30, 95, 0, 0, 45, .985F, .45F, 1.2F, true);
        if (path.contains("firework") || path.contains("spark"))
            return new ParticlePreset(Shape.SPARK, VisualFamily.FIREWORK_SPARK, Behavior.BALLISTIC, UiParticleMaterials.Material.FIREWORK, 0xFFFFFF, 5, 8, 35, 115, -8, 10, 34, .985F, .5F, 1.4F, true);
        if (path.contains("sculk") || path.contains("shriek") || path.contains("sonic"))
            return new ParticlePreset(Shape.SCULK, VisualFamily.SCULK_CHARGE, Behavior.DRIFT, UiParticleMaterials.Material.DEFAULT, 0x49D4C5, 8, 14, 4, 32, -18, 4, -2, .975F, 1.0F, 2.2F, false);
        if (path.contains("cherry"))
            return new ParticlePreset(Shape.PETAL, VisualFamily.CHERRY_PETAL, Behavior.DRIFT, UiParticleMaterials.Material.DEFAULT, 0xFFB5D5, 7, 11, 3, 20, 16, 30, 20, .97F, 1.2F, 2.8F, false);
        return new ParticlePreset(Shape.PIXEL, VisualFamily.AUTO, Behavior.BALLISTIC, UiParticleMaterials.Material.DEFAULT, 0xFFFFFF, 5, 9, 12, 58, -8, 8, 42, .985F, .65F, 1.7F, true);
    }

    private record ParticlePreset(Shape shape, VisualFamily visual, Behavior behavior,
                                  UiParticleMaterials.Material material, int color,
                                  float minPixels, float maxPixels, float minSpeed, float maxSpeed,
                                  float minVerticalBias, float maxVerticalBias, float gravity, float drag,
                                  float minLife, float maxLife, boolean tintable) { }
}
