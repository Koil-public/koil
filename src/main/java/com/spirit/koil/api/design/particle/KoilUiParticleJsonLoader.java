package com.spirit.koil.api.design.particle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

import com.spirit.koil.api.design.particle.KoilUiParticleEngine.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.spirit.koil.api.design.particle.KoilUiParticleEngine.*;
import static com.spirit.koil.api.design.particle.KoilVanillaParticleSprites.SpriteSet;

/**
 * Data-driven particle loader for Koil UI particles.
 *
 * <p>Built-in effects and user effects use the same JSON path. Java contains
 * reusable simulation/render programs only. Effect identity, appearance,
 * material, sound, emission, and compound-projectile configuration live in
 * JSON.</p>
 */
public final class KoilUiParticleJsonLoader {
    private KoilUiParticleJsonLoader() { }

    /** One JSON definition that could not be converted into an executable effect. */
    public record RegistrationFailure(String id, String message) { }

    /** Best-effort registration result. A broken effect never aborts later siblings. */
    public record RegistrationReport(List<String> registeredIds, List<RegistrationFailure> failures) {
        public RegistrationReport {
            registeredIds = List.copyOf(registeredIds == null ? List.of() : registeredIds);
            failures = List.copyOf(failures == null ? List.of() : failures);
        }

        public boolean complete() { return failures.isEmpty(); }
    }

    public static String effectId(String json) {
        List<String> ids = effectIds(json);
        return ids.isEmpty() ? "custom_effect" : ids.get(0);
    }

    public static List<String> effectIds(String json) {
        JsonElement root = JsonParser.parseString(json);
        List<String> ids = new ArrayList<>();
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) addId(element, ids);
        } else if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            if (object.has("effects") && object.get("effects").isJsonArray()) {
                for (JsonElement element : object.getAsJsonArray("effects")) addId(element, ids);
            } else {
                addId(object, ids);
            }
        }
        return ids;
    }

    private static void addId(JsonElement element, List<String> ids) {
        if (element == null || !element.isJsonObject()) return;
        String id = string(element.getAsJsonObject(), "id", "").trim().toLowerCase();
        if (!id.isBlank()) ids.add(id);
    }

    public static String registerFile(Path path) throws IOException {
        List<String> ids = registerJsonAll(Files.readString(path));
        return ids.isEmpty() ? "" : ids.get(0);
    }

    public static String registerJson(String json) {
        List<String> ids = registerJsonAll(json);
        return ids.isEmpty() ? "" : ids.get(0);
    }

    /**
     * Registers every effect independently. A failure in one definition is
     * reported and registration continues with the next definition.
     */
    public static RegistrationReport registerJsonAllBestEffort(String json) {
        List<String> registered = new ArrayList<>();
        List<RegistrationFailure> failures = new ArrayList<>();

        final JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException parseFailure) {
            failures.add(new RegistrationFailure("<document>", describe(parseFailure)));
            return new RegistrationReport(registered, failures);
        }

        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) registerElementBestEffort(element, registered, failures);
        } else if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            if (object.has("effects") && object.get("effects").isJsonArray()) {
                for (JsonElement element : object.getAsJsonArray("effects")) {
                    registerElementBestEffort(element, registered, failures);
                }
            } else {
                registerElementBestEffort(object, registered, failures);
            }
        } else {
            failures.add(new RegistrationFailure("<document>", "Root must be an object or array."));
        }
        return new RegistrationReport(registered, failures);
    }

    private static void registerElementBestEffort(JsonElement element, List<String> ids, List<RegistrationFailure> failures) {
        if (element == null || !element.isJsonObject()) {
            failures.add(new RegistrationFailure("<unnamed>", "Effect entry is not a JSON object."));
            return;
        }
        JsonObject object = element.getAsJsonObject();
        String id = string(object, "id", "").trim().toLowerCase();
        if (id.isBlank()) {
            failures.add(new RegistrationFailure("<unnamed>", "Effect is missing a non-empty id."));
            return;
        }
        try {
            registerEffect(object);
            ids.add(id);
        } catch (RuntimeException failure) {
            failures.add(new RegistrationFailure(id, describe(failure)));
        }
    }

    private static String describe(Throwable throwable) {
        if (throwable == null) return "Unknown registration failure";
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    public static List<String> registerJsonAll(String json) {
        JsonElement root = JsonParser.parseString(json);
        List<String> ids = new ArrayList<>();
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) registerElement(element, ids);
        } else if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            if (object.has("effects") && object.get("effects").isJsonArray()) {
                for (JsonElement element : object.getAsJsonArray("effects")) registerElement(element, ids);
            } else {
                registerElement(object, ids);
            }
        }
        return ids;
    }

    private static void registerElement(JsonElement element, List<String> ids) {
        if (element == null || !element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        String id = string(object, "id", "").trim().toLowerCase();
        if (id.isBlank()) return;
        registerEffect(object);
        ids.add(id);
    }

    private static void registerEffect(JsonObject object) {
        String id = string(object, "id", "custom_effect").trim().toLowerCase();
        int theme = color(object.get("theme_color"), 0xFFFFFF);
        int weight = integer(object, "weight", 2, 1, 100);
        KoilUiParticleSoundProfile.Cue beginSound = cue(object, "sound", 0.24F, 1.0F, 0.08F, 100L);
        String program = string(object, "program", "burst").trim().toLowerCase();

        KoilUiParticleRegistry.register(KoilUiParticleEffect.builder(id)
                .themeColor(theme)
                .weight(weight)
                .beginSound(beginSound)
                .onBegin(ctx -> runBegin(program, object, ctx))
                .onTick((ctx, dt) -> runTick(program, object, ctx, dt))
                .build());
    }

    private static void runBegin(String program, JsonObject cfg, EffectContext ctx) {
        pulseFromConfig(cfg, ctx);
        switch (program) {
            case "float", "cloud", "orbit", "fountain", "rain", "vortex", "jets", "drip", "fall", "pulse", "burst" ->
                    emitInitial(program, cfg, ctx);
            case "wind_field" -> {
                addWind(cfg, ctx);
                emitInitial("cloud", cfg, ctx);
            }
            case "vortex_field" -> {
                addVortex(cfg, ctx);
                emitInitial("vortex", cfg, ctx);
            }
            case "bounce_bomb" -> spawnBounceBomb(cfg, ctx);
            case "firework_show" -> spawnFireworkShow(cfg, ctx);
            case "split_bounce" -> spawnSplitBounce(cfg, ctx);
            case "ricochet_burst" -> spawnRicochet(cfg, ctx);
            case "fuse_bounce" -> spawnFuseBounce(cfg, ctx);
            case "shatter_bounce" -> spawnShatterBounce(cfg, ctx);
            case "chain_blast" -> spawnChainBlast(cfg, ctx);
            default -> emitInitial("burst", cfg, ctx);
        }
    }

    private static void runTick(String program, JsonObject cfg, EffectContext ctx, float dt) {
        float rate = decimal(cfg, "rate", 0.0F);
        if (rate > 0.0F && !isCompound(program)) {
            int count = ctx.emissionCount("json:" + string(cfg, "id", program), rate, dt);
            for (int i = 0; i < count; i++) spawnSimple(program, cfg, ctx, false);
        }
        if (program.equals("wind_field") && bool(cfg, "refresh_field", false)) addWind(cfg, ctx);
        if (program.equals("vortex_field") && bool(cfg, "refresh_field", false)) addVortex(cfg, ctx);
    }

    private static boolean isCompound(String program) {
        return switch (program) {
            case "bounce_bomb", "firework_show", "split_bounce", "ricochet_burst", "fuse_bounce", "shatter_bounce", "chain_blast" -> true;
            default -> false;
        };
    }

    private static void emitInitial(String program, JsonObject cfg, EffectContext ctx) {
        int count = integer(cfg, "count", 28, 1, 500);
        float frontRatio = clamp(decimal(cfg, "front_ratio", 0.32F), 0.0F, 1.0F);
        for (int i = 0; i < count; i++) spawnSimple(program, cfg, ctx, ctx.chance(frontRatio));
    }

    private static void spawnSimple(String program, JsonObject cfg, EffectContext ctx, boolean front) {
        Shape shape = enumValue(Shape.class, string(cfg, "shape", defaultShape(program)), Shape.PIXEL);
        SpriteSet sprite = enumValue(SpriteSet.class, string(cfg, "sprite", defaultSprite(program)), SpriteSet.GENERIC);
        VisualFamily visual = enumValue(VisualFamily.class, string(cfg, "visual_family", "auto"), VisualFamily.AUTO);
        KoilUiParticleMaterials.Material material = enumValue(KoilUiParticleMaterials.Material.class,
                string(cfg, "material", defaultMaterial(program)), KoilUiParticleMaterials.Material.DEFAULT);
        Behavior behavior = enumValue(Behavior.class, string(cfg, "behavior", defaultBehavior(program)), Behavior.BALLISTIC);
        SizeBand sizeBand = enumValue(SizeBand.class, string(cfg, "size_band", "normal"), SizeBand.NORMAL);
        RotationPolicy rotation = enumValue(RotationPolicy.class, string(cfg, "rotation_policy", "locked"), RotationPolicy.LOCKED);
        PixelSnap pixelSnap = enumValue(PixelSnap.class, string(cfg, "pixel_snap", "soft"), PixelSnap.SOFT);

        Launch launch = launchFor(program, cfg, ctx);
        float minSpeed = rangeMin(cfg, "speed", decimal(cfg, "min_speed", 36.0F));
        float maxSpeed = rangeMax(cfg, "speed", decimal(cfg, "max_speed", 105.0F));
        float speed = ctx.random(Math.min(minSpeed, maxSpeed), Math.max(minSpeed, maxSpeed));
        float tangentFactor = decimal(cfg, "tangent_factor", 0.42F);
        float tangent = ctx.random(-speed * tangentFactor, speed * tangentFactor);

        float vx = launch.velocityX(speed, tangent);
        float vy = launch.velocityY(speed, tangent);
        if (program.equals("float") || program.equals("cloud")) vy += ctx.random(-34.0F, -8.0F);
        if (program.equals("fountain")) {
            vx = ctx.random(-34.0F, 34.0F);
            vy = ctx.random(-150.0F, -70.0F);
        }
        if (program.equals("rain") || program.equals("fall") || program.equals("drip")) {
            vx = ctx.random(-14.0F, 14.0F);
            vy = ctx.random(28.0F, 78.0F);
        }

        int tint = pickColor(cfg, ctx, color(cfg.get("theme_color"), 0xFFFFFF));
        float lifeMin = rangeMin(cfg, "life", decimal(cfg, "min_life", 0.8F));
        float lifeMax = rangeMax(cfg, "life", decimal(cfg, "max_life", 1.8F));
        float gravity = decimal(cfg, "gravity", defaultGravity(program));
        float drag = clamp(decimal(cfg, "drag", defaultDrag(program)), 0.0F, 1.0F);
        float restitution = clamp(decimal(cfg, "restitution", 0.42F), 0.0F, 1.25F);
        float friction = clamp(decimal(cfg, "surface_friction", 0.86F), 0.0F, 1.0F);
        float spriteScale = randomRange(ctx, cfg, "sprite_scale", 1.0F, 1.0F);
        float visualPixels = randomRange(ctx, cfg, "visual_pixels", -1.0F, -1.0F);
        float angular = randomRange(ctx, cfg, "angular_velocity", -120.0F, 120.0F);

        ParticleBuilder p = ctx.particle(shape, launch.x, launch.y)
                .sprite(sprite)
                .visual(visual)
                .material(material)
                .behavior(behavior)
                .sizeBand(sizeBand)
                .rotationPolicy(rotation)
                .pixelSnap(pixelSnap)
                .spriteScale(spriteScale)
                .color(tint)
                .lifetime(ctx.random(Math.min(lifeMin, lifeMax), Math.max(lifeMin, lifeMax)))
                .layer(front ? Layer.FRONT : Layer.BACK)
                .velocity(vx, vy)
                .gravity(gravity)
                .drag(drag)
                .restitution(restitution)
                .surfaceFriction(friction)
                .collideButtons(bool(cfg, "collide_buttons", true))
                .collideScreen(bool(cfg, "collide_screen", true))
                .protectTarget(bool(cfg, "protect_target", false))
                .frontAlpha(decimal(cfg, "front_alpha", 0.94F))
                .spawnCollisionInvulnerability(SPAWN_COLLISION_INVULNERABILITY_SECONDS)
                .angularVelocity(angular)
                .collisionScale(decimal(cfg, "collision_scale", 1.0F))
                .stretchWithVelocity(bool(cfg, "stretch_with_velocity", false))
                .settleOnSurfaces(bool(cfg, "settle_on_surfaces", false))
                .spawnCollisionInvulnerability(decimal(cfg, "spawn_invulnerability", SPAWN_COLLISION_INVULNERABILITY_SECONDS))
                .animationSpeed(decimal(cfg, "animation_speed", 1.0F))
                .animationLoop(bool(cfg, "animation_loop", true))
                .animationInterpolate(bool(cfg, "animation_interpolate", true));

        if (visualPixels > 0.0F) p.visualPixels(visualPixels);
        float stick = decimal(cfg, "stick_seconds", 0.0F);
        if (stick > 0.0F) p.stickToButtons(stick);
        String mergeGroup = string(cfg, "merge_group", "").trim();
        if (!mergeGroup.isBlank() && bool(cfg, "merge_on_contact", false)) p.mergeGroup(mergeGroup, true);
        Identifier customTexture = identifier(string(cfg, "texture", ""));
        if (customTexture != null) {
            p.texture(customTexture).textureAutoSize(bool(cfg, "texture_auto_size", true)).textureTint(bool(cfg, "texture_tint", true));
            if (cfg.has("texture_width") && cfg.has("texture_height")) {
                p.textureSize(integer(cfg, "texture_width", 16, 1, 4096),
                        integer(cfg, "texture_height", 16, 1, 4096));
            }
        }
        JsonArray textureFrames = array(cfg, "texture_frames");
        if (!textureFrames.isEmpty()) {
            List<Identifier> frames = new ArrayList<>();
            for (JsonElement element : textureFrames) {
                if (element != null && element.isJsonPrimitive()) {
                    Identifier frame = identifier(element.getAsString());
                    if (frame != null) frames.add(frame);
                }
            }
            if (!frames.isEmpty()) p.textureSequence(frames).textureAutoSize(bool(cfg, "texture_auto_size", true));
        }

        if (cfg.has("particle_collision")) p.particleCollision(bool(cfg, "particle_collision", false));
        if (cfg.has("contact_mode")) p.contactMode(enumValue(ParticleContactMode.class,
                string(cfg, "contact_mode", "kill_both"), ParticleContactMode.KILL_BOTH));
        if (cfg.has("simulation_layer") || cfg.has("layer_id")) p.simulationLayer(integer(cfg,
                cfg.has("simulation_layer") ? "simulation_layer" : "layer_id", 0, Integer.MIN_VALUE, Integer.MAX_VALUE));
        if (cfg.has("cross_layer_interactions")) p.crossLayerInteractions(bool(cfg, "cross_layer_interactions", false));
        if (cfg.has("interaction_layers")) p.interactionLayers(parseInteractionLayers(cfg.get("interaction_layers")));
        if (cfg.has("mass")) p.mass(decimal(cfg, "mass", 1.0F));
        if (cfg.has("solidity")) p.solidity(decimal(cfg, "solidity", 0.0F));
        if (cfg.has("temperature")) p.temperature(decimal(cfg, "temperature", 0.0F));
        if (cfg.has("charge")) p.charge(decimal(cfg, "charge", 0.0F));
        if (cfg.has("conductivity")) p.conductivity(decimal(cfg, "conductivity", 0.0F));
        if (cfg.has("flammability")) p.flammability(decimal(cfg, "flammability", 0.0F));
        if (cfg.has("buoyancy")) p.buoyancy(decimal(cfg, "buoyancy", 0.0F));
        JsonArray tags = array(cfg, "tags");
        for (JsonElement tag : tags) if (tag != null && tag.isJsonPrimitive()) p.tag(tag.getAsString());
        if (cfg.has("data") && cfg.get("data").isJsonObject()) {
            for (var entry : cfg.getAsJsonObject("data").entrySet()) p.data(entry.getKey(), entry.getValue().toString());
        }
        KoilUiParticleSoundProfile profile = particleSoundProfile(cfg);
        if (profile != null) p.soundProfile(profile);

        if (behavior == Behavior.ORBIT) {
            p.anchor(ctx.centerX(), ctx.centerY())
                    .orbitRadius(randomRange(ctx, cfg, "orbit_radius", 18.0F, 34.0F))
                    .force(decimal(cfg, "force", 48.0F))
                    .spring(decimal(cfg, "spring", 5.5F));
        } else if (behavior == Behavior.VORTEX || behavior == Behavior.SPIRAL || behavior == Behavior.WANDER || behavior == Behavior.DRIFT) {
            p.anchor(ctx.centerX(), ctx.centerY())
                    .force(decimal(cfg, "force", 20.0F))
                    .frequency(decimal(cfg, "frequency", 5.0F))
                    .lift(decimal(cfg, "lift", 0.0F));
        }
        ctx.spawn(p);
    }

    private static Launch launchFor(String program, JsonObject cfg, EffectContext ctx) {
        if (program.equals("rain") || program.equals("fall")) {
            float x = ctx.random(ctx.targetX() - 10.0F, ctx.targetX() + ctx.targetWidth() + 10.0F);
            float y = ctx.targetY() - ctx.random(3.0F, 30.0F);
            return new Launch(x, y, 0.0F, 1.0F, 1.0F, 0.0F);
        }
        if (program.equals("drip")) {
            FaceSpawn face = ctx.faceSpawn(0.5F);
            return new Launch(face.x(), face.y(), 0.0F, 1.0F, 1.0F, 0.0F);
        }
        if (program.equals("jets")) {
            boolean left = ctx.chance(0.5F);
            float x = left ? ctx.targetX() : ctx.targetX() + ctx.targetWidth();
            float y = ctx.random(ctx.targetY(), ctx.targetY() + ctx.targetHeight());
            return new Launch(x, y, left ? -1.0F : 1.0F, 0.0F, 0.0F, 1.0F);
        }
        float faceChance = clamp(decimal(cfg, "face_chance", 0.70F), 0.0F, 1.0F);
        if (ctx.chance(faceChance)) {
            FaceSpawn face = ctx.faceSpawn(decimal(cfg, "face_inset", 0.5F));
            return new Launch(face.x(), face.y(), face.normalX(), face.normalY(), face.tangentX(), face.tangentY());
        }
        BorderSpawn border = ctx.borderSpawn(decimal(cfg, "border_outset_min", -1.0F), decimal(cfg, "border_outset_max", 2.5F));
        return new Launch(border.x(), border.y(), border.normalX(), border.normalY(), border.tangentX(), border.tangentY());
    }

    private record Launch(float x, float y, float nx, float ny, float tx, float ty) {
        float velocityX(float outward, float tangent) { return nx * outward + tx * tangent; }
        float velocityY(float outward, float tangent) { return ny * outward + ty * tangent; }
    }

    private static void pulseFromConfig(JsonObject cfg, EffectContext ctx) {
        if (!bool(cfg, "pulse", true)) return;
        int color = color(cfg.get("theme_color"), 0xFFFFFF);
        ctx.pulse(0.0F, decimal(cfg, "pulse_duration", 0.28F), color, decimal(cfg, "pulse_scale", 0.75F),
                bool(cfg, "pulse_front", false) ? Layer.FRONT : Layer.BACK);
    }

    private static void addWind(JsonObject cfg, EffectContext ctx) {
        ctx.windZone(0, 0, ctx.screenWidth(), ctx.screenHeight(), decimal(cfg, "wind_x", 32.0F), decimal(cfg, "wind_y", 0.0F), decimal(cfg, "field_life", 1.5F));
    }

    private static void addVortex(JsonObject cfg, EffectContext ctx) {
        ctx.vortexWell(ctx.centerX(), ctx.centerY(), decimal(cfg, "field_radius", 72.0F), decimal(cfg, "field_spin", 95.0F), decimal(cfg, "field_pull", 36.0F), decimal(cfg, "field_life", 1.5F));
    }

    private static void spawnBounceBomb(JsonObject cfg, EffectContext ctx) {
        JsonArray blocks = array(cfg, "blocks");
        int count = integer(cfg, "projectile_count", 3, 1, 12);
        for (int i = 0; i < count; i++) {
            String blockId = blocks.size() == 0 ? "minecraft:stone" : blocks.get(ctx.randomInt(blocks.size())).getAsString();
            Identifier blockIdentifier = identifierWithDefaultNamespace(blockId);
            Block block = Registries.BLOCK.get(blockIdentifier);
            String texturePath = string(cfg, "projectile_texture", "");
            Identifier texture = texturePath.isBlank()
                    ? new Identifier(blockIdentifier.getNamespace(), "textures/block/" + blockIdentifier.getPath() + ".png")
                    : identifier(texturePath);
            int detonateAfter = randomIntRange(ctx, cfg, "bounces", 2, 6);
            FaceSpawn origin = ctx.faceSpawn(0.35F);
            ParticleBuilder p = ctx.particle(Shape.BLOCK_SHARD, origin.x(), origin.y())
                    .material(KoilUiParticleMaterials.Material.BLOCK)
                    .visual(VisualFamily.BLOCK_SHARD)
                    .texture(texture).textureAutoSize(true).textureTint(false)
                    .visualPixels(randomRange(ctx, cfg, "projectile_pixels", 10.0F, 15.0F))
                    .lifetime(decimal(cfg, "projectile_life", 4.0F))
                    .velocity(origin.velocityX(ctx.random(70, 125), ctx.random(-55, 55)), origin.velocityY(ctx.random(70, 125), ctx.random(-55, 55)) - ctx.random(35, 85))
                    .layer(ctx.chance(decimal(cfg, "front_ratio", 0.55F)) ? Layer.FRONT : Layer.BACK)
                    .spawnCollisionInvulnerability(SPAWN_COLLISION_INVULNERABILITY_SECONDS)
                    .bouncingBlockSounds(block)
                    .onBounce(ev -> {
                        if (ev.bounceCount() >= detonateAfter) {
                            ev.playBlockBreak(block);
                            emitEventBurst(ev, cfg, texture, block, "explode", 30, 0xFFFFFF);
                            ev.kill();
                        }
                    });
            ctx.spawn(p);
        }
    }

    private static void spawnSplitBounce(JsonObject cfg, EffectContext ctx) {
        FaceSpawn origin = ctx.faceSpawn(0.35F);
        int splitAfter = randomIntRange(ctx, cfg, "bounces", 2, 4);
        Identifier texture = identifier(string(cfg, "projectile_texture", "minecraft:textures/block/slime_block.png"));
        ParticleBuilder p = ctx.particle(Shape.SLIME, origin.x(), origin.y())
                .material(KoilUiParticleMaterials.Material.SLIME)
                .visual(VisualFamily.SLIME_SHARD)
                .texture(texture).textureTint(false).visualPixels(13.0F)
                .lifetime(4.0F).layer(Layer.FRONT)
                .velocity(origin.velocityX(ctx.random(65, 105), ctx.random(-45, 45)), origin.velocityY(ctx.random(65, 105), ctx.random(-45, 45)) - 50)
                .spawnCollisionInvulnerability(SPAWN_COLLISION_INVULNERABILITY_SECONDS)
                .onBounce(ev -> {
                    if (ev.bounceCount() >= splitAfter) {
                        for (int i = 0; i < integer(cfg, "split_count", 16, 4, 64); i++) {
                            float a = (float) (Math.PI * 2.0 * i / Math.max(1, integer(cfg, "split_count", 16, 4, 64)) + ev.random(-0.18F, 0.18F));
                            float s = ev.random(45, 115);
                            ev.spawn(ev.particle(Shape.SLIME).material(KoilUiParticleMaterials.Material.SLIME)
                                    .texture(texture).textureTint(false).visualPixels(ev.random(5, 8))
                                    .color(color(cfg.get("theme_color"), 0x74C94E)).lifetime(ev.random(1.0F, 2.2F))
                                    .velocity((float)Math.cos(a)*s, (float)Math.sin(a)*s - 20).layer(i % 4 == 0 ? Layer.FRONT : Layer.BACK)
                                    .spawnCollisionInvulnerability(SPAWN_COLLISION_INVULNERABILITY_SECONDS));
                        }
                        ev.kill();
                    }
                });
        ctx.spawn(p);
    }

    private static void spawnRicochet(JsonObject cfg, EffectContext ctx) {
        int count = integer(cfg, "projectile_count", 3, 1, 12);
        Identifier texture = identifier(string(cfg, "projectile_texture", "minecraft:textures/item/ender_pearl.png"));
        for (int i = 0; i < count; i++) {
            FaceSpawn origin = ctx.faceSpawn(0.35F);
            int explodeAfter = randomIntRange(ctx, cfg, "bounces", 1, 3);
            final float[] nextTrail = {0.0F};
            ctx.spawn(ctx.particle(Shape.PIXEL, origin.x(), origin.y())
                    .texture(texture).textureTint(false).visualPixels(11.0F)
                    .material(KoilUiParticleMaterials.Material.PORTAL).behavior(Behavior.RICOCHET)
                    .collideButtons(true).collideScreen(true).restitution(0.74F).gravity(55.0F)
                    .velocity(origin.velocityX(ctx.random(75, 135), ctx.random(-60, 60)), origin.velocityY(ctx.random(75, 135), ctx.random(-60, 60)) - 40)
                    .lifetime(3.6F).layer(i % 2 == 0 ? Layer.FRONT : Layer.BACK).spawnCollisionInvulnerability(SPAWN_COLLISION_INVULNERABILITY_SECONDS)
                    .onTick(ev -> {
                        if (ev.age() >= nextTrail[0]) {
                            nextTrail[0] = ev.age() + 0.05F;
                            emitEventBurst(ev, cfg, null, null, "trail", 2, color(cfg.get("theme_color"), 0x6FC9A5));
                        }
                    })
                    .onBounce(ev -> {
                        if (ev.bounceCount() >= explodeAfter) {
                            emitEventBurst(ev, cfg, null, null, "explode", 34, color(cfg.get("theme_color"), 0x6FC9A5));
                            ev.kill();
                        }
                    })
                    .onExpire(ev -> emitEventBurst(ev, cfg, null, null, "explode", 34, color(cfg.get("theme_color"), 0x6FC9A5))));
        }
    }

    private static void spawnFuseBounce(JsonObject cfg, EffectContext ctx) {
        FaceSpawn origin = ctx.faceSpawn(0.35F);
        Identifier texture = identifier(string(cfg, "projectile_texture", "minecraft:textures/block/tnt_side.png"));
        int explodeAfter = randomIntRange(ctx, cfg, "bounces", 2, 5);
        final float[] nextTrail = {0.0F};
        ctx.spawn(ctx.particle(Shape.BLOCK_SHARD, origin.x(), origin.y())
                .texture(texture).textureTint(false).visualPixels(14.0F).material(KoilUiParticleMaterials.Material.BLOCK)
                .velocity(origin.velocityX(ctx.random(65, 115), ctx.random(-50, 50)), origin.velocityY(ctx.random(65, 115), ctx.random(-50, 50)) - 55)
                .lifetime(decimal(cfg, "projectile_life", 4.0F)).layer(Layer.FRONT).spawnCollisionInvulnerability(SPAWN_COLLISION_INVULNERABILITY_SECONDS)
                .onTick(ev -> {
                    if (ev.age() >= nextTrail[0]) {
                        nextTrail[0] = ev.age() + 0.07F;
                        emitEventBurst(ev, cfg, null, null, "trail", 2, 0x5E5148);
                    }
                })
                .onBounce(ev -> {
                    if (ev.bounceCount() >= explodeAfter) {
                        playConfiguredSound(ev, cfg, "detonate_sound", "entity.generic.explode", 0.34F, 1.0F);
                        emitEventBurst(ev, cfg, null, null, "explode", 52, 0xFF9C45);
                        ev.pulse(0.42F, 0xFFB05C, 1.4F, Layer.FRONT);
                        ev.kill();
                    }
                }));
    }

    private static void spawnShatterBounce(JsonObject cfg, EffectContext ctx) {
        int count = integer(cfg, "projectile_count", 2, 1, 8);
        Identifier texture = identifier(string(cfg, "projectile_texture", "minecraft:textures/block/amethyst_block.png"));
        Block block = Registries.BLOCK.get(new Identifier("minecraft", "amethyst_block"));
        for (int i = 0; i < count; i++) {
            FaceSpawn origin = ctx.faceSpawn(0.35F);
            int shatterAfter = randomIntRange(ctx, cfg, "bounces", 2, 5);
            ctx.spawn(ctx.particle(Shape.BLOCK_SHARD, origin.x(), origin.y())
                    .texture(texture).textureTint(false).visualPixels(12.0F).material(KoilUiParticleMaterials.Material.AMETHYST)
                    .velocity(origin.velocityX(ctx.random(65, 115), ctx.random(-55, 55)), origin.velocityY(ctx.random(65, 115), ctx.random(-55, 55)) - 45)
                    .lifetime(4.0F).layer(i == 0 ? Layer.FRONT : Layer.BACK).spawnCollisionInvulnerability(SPAWN_COLLISION_INVULNERABILITY_SECONDS)
                    .bouncingBlockSounds(block)
                    .onBounce(ev -> {
                        if (ev.bounceCount() >= shatterAfter) {
                            ev.playBlockBreak(block);
                            emitEventBurst(ev, cfg, texture, block, "explode", 38, color(cfg.get("theme_color"), 0xB48AE6));
                            ev.kill();
                        }
                    }));
        }
    }

    private static void spawnFireworkShow(JsonObject cfg, EffectContext ctx) {
        int rockets = randomIntRange(ctx, cfg, "rocket_count", 1, 3);
        for (int i = 0; i < rockets; i++) {
            FaceSpawn origin = ctx.faceSpawn(0.3F);
            int color = pickColor(cfg, ctx, 0xFFE45E);
            final float[] nextTrail = {0.0F};
            ctx.spawn(ctx.particle(Shape.STREAK, origin.x(), origin.y())
                    .texture(identifier(string(cfg, "projectile_texture", "minecraft:textures/item/firework_rocket.png")))
                    .textureTint(false).visualPixels(12.0F).behavior(Behavior.ROCKET_ASCENT)
                    .velocity(ctx.random(-18, 18), ctx.random(-120, -78)).lift(54.0F).drag(0.992F).gravity(-8.0F)
                    .collideButtons(true).collideScreen(true).restitution(0.25F)
                    .lifetime(ctx.random(0.65F, 1.25F)).layer(Layer.FRONT).spawnCollisionInvulnerability(SPAWN_COLLISION_INVULNERABILITY_SECONDS)
                    .onTick(ev -> {
                        if (ev.age() >= nextTrail[0]) {
                            nextTrail[0] = ev.age() + 0.035F;
                            emitFireworkSpark(ev, color, 1, 22.0F, Layer.BACK);
                        }
                    })
                    .onBounce(ev -> {
                        if (ev.impactSpeed() > 58.0F) {
                            fireworkDetonation(ev, cfg, color);
                            ev.kill();
                        }
                    })
                    .onExpire(ev -> fireworkDetonation(ev, cfg, color)));
        }
    }

    private static void fireworkDetonation(ParticleEventContext ev, JsonObject cfg, int color) {
        String[] types = {"small_ball", "large_ball", "star", "creeper", "burst"};
        String type = types[ev.randomInt(types.length)];
        int count = type.equals("large_ball") ? 64 : type.equals("small_ball") ? 34 : 46;
        playConfiguredSound(ev, cfg, "detonate_sound", type.equals("large_ball") ? "entity.firework_rocket.large_blast" : "entity.firework_rocket.blast", 0.36F, 1.0F);
        ev.pulse(0.34F, color, type.equals("large_ball") ? 1.55F : 1.1F, Layer.FRONT);
        if (type.equals("star")) {
            for (int i = 0; i < 50; i++) {
                double t = (i / 50.0) * Math.PI * 2.0;
                double r = (i % 2 == 0 ? 1.0 : 0.45);
                float vx = (float)Math.cos(t) * (float)r * ev.random(65, 105);
                float vy = (float)Math.sin(t) * (float)r * ev.random(65, 105);
                spawnFireworkSpark(ev, color, vx, vy, i % 7 == 0 ? Layer.FRONT : Layer.BACK);
            }
        } else if (type.equals("creeper")) {
            for (int i = 0; i < count; i++) {
                float x = (i % 8) - 3.5F;
                float y = (i / 8) - 2.5F;
                if ((Math.abs(x) < 1.2F && y < -0.5F) || (Math.abs(x) < 1.8F && y > 0.5F)) continue;
                spawnFireworkSpark(ev, color, x * 18.0F, y * 18.0F, i % 8 == 0 ? Layer.FRONT : Layer.BACK);
            }
        } else if (type.equals("burst")) {
            for (int i = 0; i < count; i++) spawnFireworkSpark(ev, color, ev.random(-135, 135), ev.random(-90, 70), i % 7 == 0 ? Layer.FRONT : Layer.BACK);
        } else {
            for (int i = 0; i < count; i++) {
                double angle = Math.PI * 2.0 * i / count + ev.random(-0.08F, 0.08F);
                float speed = ev.random(type.equals("large_ball") ? 95 : 60, type.equals("large_ball") ? 155 : 105);
                spawnFireworkSpark(ev, color, (float)Math.cos(angle)*speed, (float)Math.sin(angle)*speed, i % 7 == 0 ? Layer.FRONT : Layer.BACK);
            }
        }
    }

    private static void emitFireworkSpark(ParticleEventContext ev, int color, int count, float speed, Layer layer) {
        for (int i = 0; i < count; i++) spawnFireworkSpark(ev, color, ev.random(-speed, speed), ev.random(-speed, speed), layer);
    }

    private static void spawnFireworkSpark(ParticleEventContext ev, int color, float vx, float vy, Layer layer) {
        ev.spawn(ev.particle(Shape.SPARK).sprite(SpriteSet.FIREWORK).visual(VisualFamily.FIREWORK_SPARK)
                .material(KoilUiParticleMaterials.Material.FIREWORK).color(color).visualPixels(ev.random(4.0F, 7.0F))
                .lifetime(ev.random(0.8F, 2.1F)).velocity(vx, vy).layer(layer).collideButtons(true).collideScreen(true).spawnCollisionInvulnerability(SPAWN_COLLISION_INVULNERABILITY_SECONDS));
    }

    private static void spawnChainBlast(JsonObject cfg, EffectContext ctx) {
        int count = integer(cfg, "count", 8, 2, 24);
        for (int i = 0; i < count; i++) {
            final int index = i;
            float angle = (float)(Math.PI * 2.0 * i / count);
            FaceSpawn origin = ctx.faceSpawn(0.4F);
            ctx.spawn(ctx.particle(Shape.SPARK, origin.x(), origin.y()).sprite(SpriteSet.FIREWORK).material(KoilUiParticleMaterials.Material.FIREWORK)
                    .color(pickColor(cfg, ctx, 0xFF9B42)).visualPixels(ctx.random(5, 8)).lifetime(ctx.random(0.45F, 1.0F))
                    .velocity((float)Math.cos(angle)*ctx.random(55, 100), (float)Math.sin(angle)*ctx.random(55, 100))
                    .layer(index % 3 == 0 ? Layer.FRONT : Layer.BACK).spawnCollisionInvulnerability(SPAWN_COLLISION_INVULNERABILITY_SECONDS)
                    .onExpire(ev -> {
                        emitEventBurst(ev, cfg, null, null, "explode", 16, color(cfg.get("theme_color"), 0xFF9B42));
                        ev.pulse(0.22F, color(cfg.get("theme_color"), 0xFF9B42), 0.65F, Layer.BACK);
                    }));
        }
    }

    private static void emitEventBurst(ParticleEventContext ev, JsonObject cfg, Identifier texture, Block block,
                                       String phase, int defaultCount, int fallbackColor) {
        int count = integer(cfg, phase + "_count", defaultCount, 1, 200);
        String spriteName = string(cfg, phase + "_sprite", phase.equals("trail") ? "portal" : "firework");
        SpriteSet sprite = enumValue(SpriteSet.class, spriteName, phase.equals("trail") ? SpriteSet.PORTAL : SpriteSet.FIREWORK);
        int tint = color(cfg.get("theme_color"), fallbackColor);
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2.0 * i / count + ev.random(-0.15F, 0.15F);
            float speed = ev.random(phase.equals("trail") ? 8.0F : 45.0F, phase.equals("trail") ? 28.0F : 130.0F);
            ParticleBuilder child = ev.particle(texture != null ? Shape.BLOCK_SHARD : Shape.SPARK)
                    .sprite(sprite).color(tint).visualPixels(ev.random(4.0F, texture != null ? 8.0F : 7.0F))
                    .lifetime(ev.random(phase.equals("trail") ? 0.35F : 0.8F, phase.equals("trail") ? 0.8F : 2.0F))
                    .velocity((float)Math.cos(angle)*speed, (float)Math.sin(angle)*speed - (phase.equals("trail") ? 0 : 12))
                    .gravity(phase.equals("trail") ? -3.0F : 68.0F).drag(0.98F)
                    .layer(i % 7 == 0 ? Layer.FRONT : Layer.BACK).collideButtons(true).collideScreen(true).spawnCollisionInvulnerability(SPAWN_COLLISION_INVULNERABILITY_SECONDS);
            if (texture != null) child.texture(texture).textureTint(false).material(KoilUiParticleMaterials.Material.BLOCK);
            if (block != null) child.blockSounds(block);
            ev.spawn(child);
        }
    }

    private static void playConfiguredSound(ParticleEventContext ev, JsonObject cfg, String key, String fallback, float volume, float pitch) {
        String sound = string(cfg, key, fallback);
        if (!sound.isBlank()) ev.playSound(SoundEvent.of(identifierWithDefaultNamespace(sound)), volume, pitch);
    }

    private static KoilUiParticleSoundProfile particleSoundProfile(JsonObject cfg) {
        KoilUiParticleSoundProfile.Cue bounce = cue(cfg, "bounce_sound", 0.20F, 1.0F, 0.08F, 60L);
        KoilUiParticleSoundProfile.Cue breakCue = cue(cfg, "break_sound", 0.28F, 1.0F, 0.06F, 70L);
        KoilUiParticleSoundProfile.Cue expire = cue(cfg, "expire_sound", 0.18F, 1.0F, 0.06F, 90L);
        KoilUiParticleSoundProfile.Cue spawn = cue(cfg, "spawn_sound", 0.18F, 1.0F, 0.06F, 90L);
        if (bounce == null && breakCue == null && expire == null && spawn == null) return null;
        return KoilUiParticleSoundProfile.builder().spawn(spawn).bounce(bounce).breakCue(breakCue).expire(expire)
                .minimumBounceSpeed(decimal(cfg, "minimum_bounce_sound_speed", 16.0F)).build();
    }

    private static KoilUiParticleSoundProfile.Cue cue(JsonObject object, String key, float volume, float pitch, float jitter, long cooldown) {
        String path = string(object, key, "").trim();
        if (path.isBlank()) return null;
        String prefix = key.endsWith("_sound") ? key.substring(0, key.length() - 6) : key;
        return new KoilUiParticleSoundProfile.Cue(
                SoundEvent.of(identifierWithDefaultNamespace(path)),
                decimal(object, prefix + "_volume", volume),
                decimal(object, prefix + "_pitch", pitch),
                decimal(object, prefix + "_jitter", jitter),
                integer(object, prefix + "_cooldown_ms", (int) cooldown, 0, 60_000));
    }

    public static List<String> loadDirectory(Path directory) throws IOException {
        List<String> ids = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) return ids;
        try (var stream = Files.list(directory)) {
            for (Path path : stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json")).sorted().toList()) {
                ids.addAll(registerJsonAll(Files.readString(path)));
            }
        }
        return ids;
    }

    private static String defaultShape(String program) {
        return switch (program) {
            case "float", "cloud" -> "pixel";
            case "rain", "drip" -> "drop";
            case "fountain" -> "drop";
            case "vortex" -> "spark";
            default -> "pixel";
        };
    }

    private static String defaultSprite(String program) {
        return switch (program) {
            case "float", "cloud" -> "generic";
            case "rain", "fountain" -> "splash";
            case "drip" -> "drip_fall";
            case "vortex" -> "portal";
            default -> "generic";
        };
    }

    private static String defaultMaterial(String program) {
        return switch (program) {
            case "rain", "fountain", "drip" -> "water";
            default -> "default";
        };
    }

    private static String defaultBehavior(String program) {
        return switch (program) {
            case "float", "cloud" -> "wander";
            case "orbit" -> "orbit";
            case "vortex" -> "vortex";
            case "drip", "rain", "fall", "fountain", "jets", "burst", "pulse" -> "ballistic";
            default -> "ballistic";
        };
    }

    private static float defaultGravity(String program) {
        return switch (program) {
            case "float", "cloud", "orbit", "vortex" -> -4.0F;
            case "rain", "drip", "fall" -> 120.0F;
            case "fountain" -> 165.0F;
            default -> 55.0F;
        };
    }

    private static float defaultDrag(String program) {
        return switch (program) {
            case "float", "cloud", "orbit", "vortex" -> 0.97F;
            default -> 0.985F;
        };
    }

    private static int pickColor(JsonObject cfg, EffectContext ctx, int fallback) {
        JsonArray palette = array(cfg, "palette");
        if (palette.size() == 0) return fallback;
        return color(palette.get(ctx.randomInt(palette.size())), fallback);
    }

    private static int randomIntRange(EffectContext ctx, JsonObject cfg, String key, int min, int max) {
        JsonElement element = cfg.get(key);
        if (element != null && element.isJsonArray() && element.getAsJsonArray().size() >= 2) {
            int a = element.getAsJsonArray().get(0).getAsInt();
            int b = element.getAsJsonArray().get(1).getAsInt();
            min = Math.min(a, b); max = Math.max(a, b);
        }
        return min + ctx.randomInt(Math.max(1, max - min + 1));
    }

    private static float randomRange(EffectContext ctx, JsonObject cfg, String key, float fallbackMin, float fallbackMax) {
        JsonElement element = cfg.get(key);
        if (element != null && element.isJsonArray() && element.getAsJsonArray().size() >= 2) {
            float a = element.getAsJsonArray().get(0).getAsFloat();
            float b = element.getAsJsonArray().get(1).getAsFloat();
            return ctx.random(Math.min(a, b), Math.max(a, b));
        }
        if (element != null && element.isJsonPrimitive()) {
            try { return element.getAsFloat(); } catch (RuntimeException ignored) { }
        }
        return ctx.random(Math.min(fallbackMin, fallbackMax), Math.max(fallbackMin, fallbackMax));
    }

    private static float rangeMin(JsonObject cfg, String key, float fallback) {
        JsonElement element = cfg.get(key);
        if (element != null && element.isJsonArray() && element.getAsJsonArray().size() >= 2) return Math.min(element.getAsJsonArray().get(0).getAsFloat(), element.getAsJsonArray().get(1).getAsFloat());
        if (element != null && element.isJsonPrimitive()) try { return element.getAsFloat(); } catch (RuntimeException ignored) { }
        return fallback;
    }

    private static float rangeMax(JsonObject cfg, String key, float fallback) {
        JsonElement element = cfg.get(key);
        if (element != null && element.isJsonArray() && element.getAsJsonArray().size() >= 2) return Math.max(element.getAsJsonArray().get(0).getAsFloat(), element.getAsJsonArray().get(1).getAsFloat());
        if (element != null && element.isJsonPrimitive()) try { return element.getAsFloat(); } catch (RuntimeException ignored) { }
        return fallback;
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static Identifier identifier(String raw) {
        String value = raw == null ? "" : raw.trim();
        return value.isBlank() ? null : identifierWithDefaultNamespace(value);
    }

    private static Identifier identifierWithDefaultNamespace(String raw) {
        String value = raw == null ? "" : raw.trim();
        return value.contains(":") ? new Identifier(value) : new Identifier("minecraft", value);
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static float decimal(JsonObject object, String key, float fallback) {
        JsonElement value = object.get(key);
        try { return value == null ? fallback : value.getAsFloat(); } catch (RuntimeException ignored) { return fallback; }
    }

    private static int integer(JsonObject object, String key, int fallback, int min, int max) {
        JsonElement value = object.get(key);
        try { return Math.max(min, Math.min(max, value == null ? fallback : value.getAsInt())); } catch (RuntimeException ignored) { return fallback; }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        try { return value == null ? fallback : value.getAsBoolean(); } catch (RuntimeException ignored) { return fallback; }
    }

    private static int color(JsonElement value, int fallback) {
        if (value == null || value.isJsonNull()) return fallback;
        try {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) return value.getAsInt() & 0xFFFFFF;
            String text = value.getAsString().trim().replace("#", "").replace("0x", "");
            return Integer.parseInt(text, 16) & 0xFFFFFF;
        } catch (RuntimeException ignored) { return fallback; }
    }

    private static InteractionLayers parseInteractionLayers(JsonElement element) {
        if (element == null || element.isJsonNull()) return InteractionLayers.sameLayer();
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            int[] layers = new int[array.size()];
            for (int i = 0; i < array.size(); i++) {
                try { layers[i] = array.get(i).getAsInt(); } catch (RuntimeException ignored) { layers[i] = 0; }
            }
            return InteractionLayers.exact(layers);
        }
        String text;
        try { text = element.getAsString().trim(); } catch (RuntimeException ignored) { return InteractionLayers.sameLayer(); }
        if (text.equalsIgnoreCase("all") || text.equals("*")) return InteractionLayers.all();
        if (text.equalsIgnoreCase("same") || text.isBlank()) return InteractionLayers.sameLayer();
        try {
            if (text.contains("-")) {
                String[] range = text.split("-", 2);
                return InteractionLayers.range(Integer.parseInt(range[0].trim()), Integer.parseInt(range[1].trim()));
            }
            String[] pieces = text.split(",");
            int[] layers = new int[pieces.length];
            for (int i = 0; i < pieces.length; i++) layers[i] = Integer.parseInt(pieces[i].trim());
            return InteractionLayers.exact(layers);
        } catch (RuntimeException ignored) {
            return InteractionLayers.sameLayer();
        }
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, T fallback) {
        if (value == null) return fallback;
        try { return Enum.valueOf(type, value.trim().toUpperCase().replace('-', '_').replace(' ', '_')); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
