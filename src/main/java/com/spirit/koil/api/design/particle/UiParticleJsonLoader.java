package com.spirit.koil.api.design.particle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import com.spirit.koil.api.design.particle.UiParticleEngine.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.spirit.koil.api.design.particle.UiParticleEngine.*;
import static com.spirit.koil.api.design.particle.VanillaParticleSprites.SpriteSet;

/**
 * Data-driven particle loader for Koil UI particles.
 *
 * <p>Built-in effects and user effects use the same JSON path. Java contains
 * reusable simulation/render programs only. Effect identity, appearance,
 * material, sound, emission, and compound-projectile configuration live in
 * JSON.</p>
 */
public final class UiParticleJsonLoader {
    private static final Set<String> SUPPORTED_PROGRAMS = Set.of(
            "burst", "float", "cloud", "orbit", "fountain", "rain", "vortex", "jets", "drip", "fall", "pulse",
            "wind_field", "vortex_field", "bounce_bomb", "firework_show", "split_bounce", "ricochet_burst",
            "fuse_bounce", "shatter_bounce", "chain_blast", "sequence", "registered_block_play", "registered_item_party", "button_firework"
    );

    private static volatile List<Block> registeredBlockCache;
    private static volatile List<Item> registeredItemCache;

    private UiParticleJsonLoader() { }

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
        validateDefinition(object);
        String id = string(object, "id", "custom_effect").trim().toLowerCase();
        int theme = color(object.get("theme_color"), 0xFFFFFF);
        int weight = integer(object, "weight", 2, 1, 100);
        UiParticleSoundProfile.Cue beginSound = cue(object, "sound", 0.24F, 1.0F, 0.08F, 100L);
        String program = string(object, "program", object.has("stages") ? "sequence" : "burst").trim().toLowerCase();
        JsonObject definition = object.deepCopy();

        UiParticleRegistry.register(UiParticleEffect.builder(id)
                .themeColor(theme)
                .weight(weight)
                .selectionFamily(string(object, "selection_family", ""))
                .beginSound(beginSound)
                .onBegin(ctx -> runBegin(program, definition, ctx, SpawnRegion.capture(ctx)))
                .onTick((ctx, dt) -> runTick(program, definition, ctx, dt))
                .build());
    }

    private static void runBegin(String program, JsonObject cfg, EffectContext ctx) {
        runBegin(program, cfg, ctx, SpawnRegion.capture(ctx));
    }

    private static void runBegin(String program, JsonObject cfg, EffectContext ctx, SpawnRegion region) {
        pulseFromConfig(cfg, ctx);
        switch (program) {
            case "sequence" -> runSequence(cfg, ctx, region);
            case "float", "cloud", "orbit", "fountain", "rain", "vortex", "jets", "drip", "fall", "pulse", "burst" ->
                    emitInitial(program, cfg, ctx, region);
            case "wind_field" -> {
                addWind(cfg, ctx, region);
                emitInitial("cloud", cfg, ctx, region);
            }
            case "vortex_field" -> {
                addVortex(cfg, ctx, region);
                emitInitial("vortex", cfg, ctx, region);
            }
            case "bounce_bomb" -> spawnBounceBomb(cfg, ctx);
            case "firework_show" -> spawnFireworkShow(cfg, ctx);
            case "button_firework" -> spawnButtonFirework(cfg, ctx);
            case "registered_block_play" -> spawnRegisteredBlockPlay(cfg, ctx);
            case "registered_item_party" -> spawnRegisteredItemParty(cfg, ctx);
            case "split_bounce" -> spawnSplitBounce(cfg, ctx);
            case "ricochet_burst" -> spawnRicochet(cfg, ctx);
            case "fuse_bounce" -> spawnFuseBounce(cfg, ctx);
            case "shatter_bounce" -> spawnShatterBounce(cfg, ctx);
            case "chain_blast" -> spawnChainBlast(cfg, ctx);
            default -> emitInitial("burst", cfg, ctx, region);
        }
    }

    private static void runTick(String program, JsonObject cfg, EffectContext ctx, float dt) {
        runIdle(cfg, ctx, dt);
        if (program.equals("sequence") || program.equals("registered_block_play") || program.equals("registered_item_party") || program.equals("button_firework")) return;
        float rate = decimal(cfg, "rate", 0.0F);
        if (rate > 0.0F && !isCompound(program)) {
            int count = ctx.emissionCount("json:" + string(cfg, "id", program), rate, dt);
            SpawnRegion region = SpawnRegion.capture(ctx);
            for (int i = 0; i < count; i++) spawnSimple(program, cfg, ctx, false, region, null);
        }
        if (program.equals("wind_field") && bool(cfg, "refresh_field", false)) addWind(cfg, ctx);
        if (program.equals("vortex_field") && bool(cfg, "refresh_field", false)) addVortex(cfg, ctx);
    }

    private static boolean isCompound(String program) {
        return switch (program) {
            case "bounce_bomb", "firework_show", "split_bounce", "ricochet_burst", "fuse_bounce", "shatter_bounce", "chain_blast", "sequence", "registered_block_play", "registered_item_party", "button_firework" -> true;
            default -> false;
        };
    }

    private static void emitInitial(String program, JsonObject cfg, EffectContext ctx) {
        emitInitial(program, cfg, ctx, SpawnRegion.capture(ctx));
    }

    private static void emitInitial(String program, JsonObject cfg, EffectContext ctx, SpawnRegion region) {
        int count = integerRange(ctx, cfg, "count", 28, 1, 500);
        float frontRatio = clamp(decimal(cfg, "front_ratio", 0.32F), 0.0F, 1.0F);
        String pattern = string(cfg, "pattern", "default").trim().toLowerCase();
        for (int i = 0; i < count; i++) {
            Launch forced = pattern.equals("default") ? null : patternLaunch(pattern, i, count, cfg, ctx, region);
            spawnSimple(program, cfg, ctx, ctx.chance(frontRatio), region, forced);
        }
    }

    private static void spawnSimple(String program, JsonObject cfg, EffectContext ctx, boolean front) {
        spawnSimple(program, cfg, ctx, front, SpawnRegion.capture(ctx), null);
    }

    private static void spawnSimple(String program, JsonObject cfg, EffectContext ctx, boolean front, SpawnRegion region, Launch forcedLaunch) {
        Shape shape = enumValue(Shape.class, string(cfg, "shape", defaultShape(program)), Shape.PIXEL);
        SpriteSet sprite = enumValue(SpriteSet.class, string(cfg, "sprite", defaultSprite(program)), SpriteSet.GENERIC);
        VisualFamily visual = enumValue(VisualFamily.class, string(cfg, "visual_family", "auto"), VisualFamily.AUTO);
        UiParticleMaterials.Material material = enumValue(UiParticleMaterials.Material.class,
                string(cfg, "material", defaultMaterial(program)), UiParticleMaterials.Material.DEFAULT);
        Behavior behavior = enumValue(Behavior.class, string(cfg, "behavior", defaultBehavior(program)), Behavior.BALLISTIC);
        SizeBand sizeBand = enumValue(SizeBand.class, string(cfg, "size_band", "normal"), SizeBand.NORMAL);
        RotationPolicy rotation = enumValue(RotationPolicy.class, string(cfg, "rotation_policy", "locked"), RotationPolicy.LOCKED);
        PixelSnap pixelSnap = enumValue(PixelSnap.class, string(cfg, "pixel_snap", "soft"), PixelSnap.SOFT);

        Launch launch = forcedLaunch != null ? forcedLaunch : launchFor(program, cfg, ctx, region);
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
                .primitive(bool(cfg, "primitive", false) && string(cfg, "visual_role", "primary").trim().equalsIgnoreCase("filler"))
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
                .collideTarget(bool(cfg, "collide_target", true))
                .collideButtons(bool(cfg, "collide_buttons", true))
                .collideScreen(bool(cfg, "collide_screen", true))
                .bounceCeiling(bool(cfg, "bounce_ceiling", true))
                .sourceButtonExit(bool(cfg, "source_button_exit", true))
                .protectTarget(bool(cfg, "protect_target", false))
                .alpha(decimal(cfg, "alpha", 1.0F))
                .frontAlpha(decimal(cfg, "front_alpha", 0.94F))
                .spawnCollisionInvulnerability(SPAWN_COLLISION_INVULNERABILITY_SECONDS)
                .rotation(randomRange(ctx, cfg, "rotation", 0.0F, 0.0F))
                .angularVelocity(angular)
                .collisionScale(decimal(cfg, "collision_scale", 1.0F))
                .stretchWithVelocity(bool(cfg, "stretch_with_velocity", false))
                .settleOnSurfaces(bool(cfg, "settle_on_surfaces", false))
                .spawnCollisionInvulnerability(decimal(cfg, "spawn_invulnerability", SPAWN_COLLISION_INVULNERABILITY_SECONDS))
                .animationSpeed(decimal(cfg, "animation_speed", 1.0F))
                .animationLoop(bool(cfg, "animation_loop", true))
                .animationInterpolate(bool(cfg, "animation_interpolate", true));

        if (visualPixels > 0.0F) p.visualPixels(visualPixels);
        if (cfg.has("fade_in") || cfg.has("fade_out")) {
            p.fades(decimal(cfg, "fade_in", 0.05F), decimal(cfg, "fade_out", 0.30F));
        }
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
        UiParticleSoundProfile profile = particleSoundProfile(cfg);
        if (profile != null) p.soundProfile(profile);
        String blockSoundId = string(cfg, "block_sound", "").trim();
        if (!blockSoundId.isBlank()) {
            Block soundBlock = Registries.BLOCK.get(identifierWithDefaultNamespace(blockSoundId));
            if (bool(cfg, "block_break_on_expire", false)) p.blockSounds(soundBlock);
            else p.bouncingBlockSounds(soundBlock);
        }

        if (behavior == Behavior.ORBIT || behavior == Behavior.ATTRACT) {
            p.anchor(region.centerX(), region.centerY())
                    .orbitRadius(randomRange(ctx, cfg, "orbit_radius", 18.0F, 34.0F))
                    .force(decimal(cfg, "force", 48.0F))
                    .spring(decimal(cfg, "spring", 5.5F));
        } else if (behavior == Behavior.VORTEX || behavior == Behavior.SPIRAL || behavior == Behavior.WANDER || behavior == Behavior.DRIFT) {
            p.anchor(region.centerX(), region.centerY())
                    .force(decimal(cfg, "force", 20.0F))
                    .frequency(decimal(cfg, "frequency", 5.0F))
                    .lift(decimal(cfg, "lift", 0.0F));
        }
        attachParticleEvents(p, cfg, ctx, region);
        configureInteraction(cfg, cfg, p, region);
        ctx.spawn(p);
    }

    private static Launch launchFor(String program, JsonObject cfg, EffectContext ctx, SpawnRegion region) {
        String origin = string(cfg, "origin", "").trim().toLowerCase();
        if (origin.isBlank()) {
            if (program.equals("rain") || program.equals("fall")) origin = "screen_top";
            else if (program.equals("drip")) origin = "face";
            else if (program.equals("jets")) origin = ctx.chance(0.5F) ? "target_left" : "target_right";
            else origin = ctx.chance(clamp(decimal(cfg, "face_chance", 0.70F), 0.0F, 1.0F)) ? "face" : "border";
        }

        Launch launch;
        switch (origin) {
            case "center", "target_center" -> {
                double angle = ctx.random(0.0F, (float) (Math.PI * 2.0));
                float nx = (float) Math.cos(angle);
                float ny = (float) Math.sin(angle);
                launch = new Launch(region.centerX(), region.centerY(), nx, ny, -ny, nx);
            }
            case "face" -> launch = region.face(ctx, decimal(cfg, "face_inset", 0.5F));
            case "border", "perimeter" -> launch = region.border(ctx, decimal(cfg, "border_outset_min", -1.0F), decimal(cfg, "border_outset_max", 2.5F));
            case "target_top" -> {
                float x = ctx.random(region.targetX(), region.targetX() + region.targetWidth());
                launch = new Launch(x, region.targetY(), 0.0F, -1.0F, 1.0F, 0.0F);
            }
            case "target_bottom" -> {
                float x = ctx.random(region.targetX(), region.targetX() + region.targetWidth());
                launch = new Launch(x, region.targetY() + region.targetHeight(), 0.0F, 1.0F, -1.0F, 0.0F);
            }
            case "target_left" -> {
                float y = ctx.random(region.targetY(), region.targetY() + region.targetHeight());
                launch = new Launch(region.targetX(), y, -1.0F, 0.0F, 0.0F, -1.0F);
            }
            case "target_right" -> {
                float y = ctx.random(region.targetY(), region.targetY() + region.targetHeight());
                launch = new Launch(region.targetX() + region.targetWidth(), y, 1.0F, 0.0F, 0.0F, 1.0F);
            }
            case "screen_top" -> {
                float x = ctx.random(0.0F, Math.max(1.0F, region.screenWidth()));
                launch = new Launch(x, -ctx.random(2.0F, 18.0F), 0.0F, 1.0F, 1.0F, 0.0F);
            }
            case "screen_bottom" -> {
                float x = ctx.random(0.0F, Math.max(1.0F, region.screenWidth()));
                launch = new Launch(x, region.screenHeight() + ctx.random(2.0F, 18.0F), 0.0F, -1.0F, -1.0F, 0.0F);
            }
            case "screen_left" -> {
                float y = ctx.random(0.0F, Math.max(1.0F, region.screenHeight()));
                launch = new Launch(-ctx.random(2.0F, 18.0F), y, 1.0F, 0.0F, 0.0F, 1.0F);
            }
            case "screen_right" -> {
                float y = ctx.random(0.0F, Math.max(1.0F, region.screenHeight()));
                launch = new Launch(region.screenWidth() + ctx.random(2.0F, 18.0F), y, -1.0F, 0.0F, 0.0F, -1.0F);
            }
            case "screen_random" -> {
                float x = ctx.random(0.0F, Math.max(1.0F, region.screenWidth()));
                float y = ctx.random(0.0F, Math.max(1.0F, region.screenHeight()));
                float dx = region.centerX() - x;
                float dy = region.centerY() - y;
                float len = Math.max(0.001F, (float) Math.sqrt(dx * dx + dy * dy));
                launch = new Launch(x, y, dx / len, dy / len, -dy / len, dx / len);
            }
            case "corner" -> {
                int corner = ctx.randomInt(4);
                float x = (corner == 0 || corner == 3) ? region.targetX() : region.targetX() + region.targetWidth();
                float y = (corner < 2) ? region.targetY() : region.targetY() + region.targetHeight();
                float dx = x - region.centerX();
                float dy = y - region.centerY();
                float len = Math.max(0.001F, (float) Math.sqrt(dx * dx + dy * dy));
                launch = new Launch(x, y, dx / len, dy / len, -dy / len, dx / len);
            }
            default -> launch = region.border(ctx, -1.0F, 2.5F);
        }
        return directionAdjusted(launch, string(cfg, "direction", "outward"), ctx, region);
    }

    private static Launch directionAdjusted(Launch launch, String rawDirection, EffectContext ctx, SpawnRegion region) {
        String direction = rawDirection == null ? "outward" : rawDirection.trim().toLowerCase();
        return switch (direction) {
            case "inward" -> new Launch(launch.x, launch.y, -launch.nx, -launch.ny, launch.tx, launch.ty);
            case "up" -> new Launch(launch.x, launch.y, 0.0F, -1.0F, 1.0F, 0.0F);
            case "down" -> new Launch(launch.x, launch.y, 0.0F, 1.0F, 1.0F, 0.0F);
            case "left" -> new Launch(launch.x, launch.y, -1.0F, 0.0F, 0.0F, 1.0F);
            case "right" -> new Launch(launch.x, launch.y, 1.0F, 0.0F, 0.0F, 1.0F);
            case "clockwise", "tangent" -> new Launch(launch.x, launch.y, launch.tx, launch.ty, -launch.nx, -launch.ny);
            case "counterclockwise" -> new Launch(launch.x, launch.y, -launch.tx, -launch.ty, launch.nx, launch.ny);
            case "toward_center" -> {
                float dx = region.centerX() - launch.x;
                float dy = region.centerY() - launch.y;
                float len = Math.max(0.001F, (float) Math.sqrt(dx * dx + dy * dy));
                yield new Launch(launch.x, launch.y, dx / len, dy / len, -dy / len, dx / len);
            }
            case "random" -> {
                double angle = ctx.random(0.0F, (float) (Math.PI * 2.0));
                float nx = (float) Math.cos(angle);
                float ny = (float) Math.sin(angle);
                yield new Launch(launch.x, launch.y, nx, ny, -ny, nx);
            }
            default -> launch;
        };
    }

    private static Launch patternLaunch(String pattern, int index, int count, JsonObject cfg, EffectContext ctx, SpawnRegion region) {
        float radius = randomRange(ctx, cfg, "pattern_radius", Math.max(8.0F, Math.min(region.targetWidth(), region.targetHeight()) * 0.45F),
                Math.max(14.0F, Math.min(region.targetWidth(), region.targetHeight()) * 0.90F));
        float t = count <= 1 ? 0.5F : index / (float) (count - 1);
        double angle = Math.PI * 2.0 * index / Math.max(1, count) + decimal(cfg, "pattern_phase", 0.0F);
        return switch (pattern) {
            case "ring", "radial_ring" -> {
                float nx = (float) Math.cos(angle), ny = (float) Math.sin(angle);
                yield directionAdjusted(new Launch(region.centerX() + nx * radius, region.centerY() + ny * radius, nx, ny, -ny, nx), string(cfg, "direction", "outward"), ctx, region);
            }
            case "inward_ring" -> {
                float nx = (float) Math.cos(angle), ny = (float) Math.sin(angle);
                yield new Launch(region.centerX() + nx * radius, region.centerY() + ny * radius, -nx, -ny, ny, -nx);
            }
            case "center_burst" -> {
                float nx = (float) Math.cos(angle), ny = (float) Math.sin(angle);
                yield directionAdjusted(new Launch(region.centerX(), region.centerY(), nx, ny, -ny, nx), string(cfg, "direction", "outward"), ctx, region);
            }
            case "horizontal_line" -> {
                float x = region.targetX() + t * region.targetWidth();
                yield directionAdjusted(new Launch(x, region.centerY(), 0.0F, -1.0F, 1.0F, 0.0F), string(cfg, "direction", "up"), ctx, region);
            }
            case "vertical_line" -> {
                float y = region.targetY() + t * region.targetHeight();
                yield directionAdjusted(new Launch(region.centerX(), y, 1.0F, 0.0F, 0.0F, 1.0F), string(cfg, "direction", "right"), ctx, region);
            }
            case "top_line" -> {
                float x = region.targetX() + t * region.targetWidth();
                yield directionAdjusted(new Launch(x, region.targetY(), 0.0F, -1.0F, 1.0F, 0.0F), string(cfg, "direction", "up"), ctx, region);
            }
            case "bottom_line" -> {
                float x = region.targetX() + t * region.targetWidth();
                yield directionAdjusted(new Launch(x, region.targetY() + region.targetHeight(), 0.0F, 1.0F, 1.0F, 0.0F), string(cfg, "direction", "down"), ctx, region);
            }
            case "left_line" -> {
                float y = region.targetY() + t * region.targetHeight();
                yield directionAdjusted(new Launch(region.targetX(), y, -1.0F, 0.0F, 0.0F, 1.0F), string(cfg, "direction", "left"), ctx, region);
            }
            case "right_line" -> {
                float y = region.targetY() + t * region.targetHeight();
                yield directionAdjusted(new Launch(region.targetX() + region.targetWidth(), y, 1.0F, 0.0F, 0.0F, 1.0F), string(cfg, "direction", "right"), ctx, region);
            }
            case "cross" -> {
                int arm = index % 4;
                float d = radius * ((index / 4 + 1.0F) / Math.max(1.0F, (count + 3) / 4.0F));
                float nx = arm == 0 ? 1.0F : arm == 1 ? -1.0F : 0.0F;
                float ny = arm == 2 ? 1.0F : arm == 3 ? -1.0F : 0.0F;
                yield directionAdjusted(new Launch(region.centerX() + nx * d, region.centerY() + ny * d, nx, ny, -ny, nx), string(cfg, "direction", "outward"), ctx, region);
            }
            case "corners" -> {
                int corner = index % 4;
                float x = (corner == 0 || corner == 3) ? region.targetX() : region.targetX() + region.targetWidth();
                float y = corner < 2 ? region.targetY() : region.targetY() + region.targetHeight();
                float dx = x - region.centerX(), dy = y - region.centerY();
                float len = Math.max(0.001F, (float) Math.sqrt(dx * dx + dy * dy));
                yield directionAdjusted(new Launch(x, y, dx / len, dy / len, -dy / len, dx / len), string(cfg, "direction", "outward"), ctx, region);
            }
            case "screen_top" -> new Launch(ctx.random(0.0F, region.screenWidth()), -ctx.random(2.0F, 18.0F), 0.0F, 1.0F, 1.0F, 0.0F);
            case "screen_bottom" -> new Launch(ctx.random(0.0F, region.screenWidth()), region.screenHeight() + ctx.random(2.0F, 18.0F), 0.0F, -1.0F, 1.0F, 0.0F);
            case "screen_random" -> launchFor("burst", merged(cfg, objectOf("origin", "screen_random")), ctx, region);
            case "button_above" -> {
                float x = ctx.random(region.targetX(), region.targetX() + region.targetWidth());
                float outset = randomRange(ctx, cfg, "button_outset", 2.0F, Math.max(6.0F, region.targetHeight() * 0.75F));
                yield directionAdjusted(new Launch(x, region.targetY() - outset, 0.0F, -1.0F, 1.0F, 0.0F), string(cfg, "direction", "down"), ctx, region);
            }
            case "button_below" -> {
                float x = ctx.random(region.targetX(), region.targetX() + region.targetWidth());
                float outset = randomRange(ctx, cfg, "button_outset", 2.0F, Math.max(6.0F, region.targetHeight() * 0.75F));
                yield directionAdjusted(new Launch(x, region.targetY() + region.targetHeight() + outset, 0.0F, 1.0F, 1.0F, 0.0F), string(cfg, "direction", "up"), ctx, region);
            }
            case "button_left" -> {
                float y = ctx.random(region.targetY(), region.targetY() + region.targetHeight());
                float outset = randomRange(ctx, cfg, "button_outset", 2.0F, Math.max(6.0F, region.targetWidth() * 0.75F));
                yield directionAdjusted(new Launch(region.targetX() - outset, y, -1.0F, 0.0F, 0.0F, 1.0F), string(cfg, "direction", "right"), ctx, region);
            }
            case "button_right" -> {
                float y = ctx.random(region.targetY(), region.targetY() + region.targetHeight());
                float outset = randomRange(ctx, cfg, "button_outset", 2.0F, Math.max(6.0F, region.targetWidth() * 0.75F));
                yield directionAdjusted(new Launch(region.targetX() + region.targetWidth() + outset, y, 1.0F, 0.0F, 0.0F, 1.0F), string(cfg, "direction", "left"), ctx, region);
            }
            case "button_halo" -> {
                double a = ctx.random(0.0F, (float) (Math.PI * 2.0));
                float nx = (float) Math.cos(a), ny = (float) Math.sin(a);
                float baseX = Math.max(4.0F, region.targetWidth() * 0.5F);
                float baseY = Math.max(4.0F, region.targetHeight() * 0.5F);
                float extra = randomRange(ctx, cfg, "button_outset", 2.0F, Math.max(8.0F, Math.min(region.targetWidth(), region.targetHeight()) * 0.8F));
                float x = region.centerX() + nx * (baseX + extra);
                float y = region.centerY() + ny * (baseY + extra);
                yield directionAdjusted(new Launch(x, y, nx, ny, -ny, nx), string(cfg, "direction", "outward"), ctx, region);
            }
            case "perimeter" -> region.border(ctx, decimal(cfg, "border_outset_min", -1.0F), decimal(cfg, "border_outset_max", 2.5F));
            case "grid" -> {
                int cols = Math.max(1, (int) Math.ceil(Math.sqrt(count)));
                int rows = Math.max(1, (int) Math.ceil(count / (double) cols));
                int col = index % cols, row = index / cols;
                float x = region.targetX() + (col + 0.5F) / cols * region.targetWidth();
                float y = region.targetY() + (row + 0.5F) / rows * region.targetHeight();
                float dx = x - region.centerX(), dy = y - region.centerY();
                float len = Math.max(0.001F, (float) Math.sqrt(dx * dx + dy * dy));
                yield directionAdjusted(new Launch(x, y, dx / len, dy / len, -dy / len, dx / len), string(cfg, "direction", "outward"), ctx, region);
            }
            default -> launchFor("burst", cfg, ctx, region);
        };
    }

    private record Launch(float x, float y, float nx, float ny, float tx, float ty) {
        float velocityX(float outward, float tangent) { return nx * outward + tx * tangent; }
        float velocityY(float outward, float tangent) { return ny * outward + ty * tangent; }
    }

    private record SpawnRegion(float targetX, float targetY, float targetWidth, float targetHeight,
                               float screenWidth, float screenHeight, float centerX, float centerY) {
        static SpawnRegion capture(EffectContext ctx) {
            return new SpawnRegion(ctx.targetX(), ctx.targetY(), Math.max(1.0F, ctx.targetWidth()), Math.max(1.0F, ctx.targetHeight()),
                    Math.max(1.0F, ctx.screenWidth()), Math.max(1.0F, ctx.screenHeight()), ctx.centerX(), ctx.centerY());
        }

        static SpawnRegion point(float x, float y, SpawnRegion parent) {
            return new SpawnRegion(x, y, 1.0F, 1.0F, parent.screenWidth, parent.screenHeight, x, y);
        }

        Launch face(EffectContext ctx, float inset) {
            float safeInset = Math.max(0.0F, inset);
            float left = targetX + safeInset;
            float right = targetX + targetWidth - safeInset;
            float top = targetY + safeInset;
            float bottom = targetY + targetHeight - safeInset;
            if (right <= left) { left = targetX; right = targetX + targetWidth; }
            if (bottom <= top) { top = targetY; bottom = targetY + targetHeight; }
            float x = ctx.random(left, right);
            float y = ctx.random(top, bottom);
            float dx = x - centerX, dy = y - centerY;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 0.01F) {
                double angle = ctx.random(0.0F, (float) (Math.PI * 2.0));
                dx = (float) Math.cos(angle); dy = (float) Math.sin(angle); len = 1.0F;
            }
            float nx = dx / len, ny = dy / len;
            return new Launch(x, y, nx, ny, -ny, nx);
        }

        Launch border(EffectContext ctx, float minOutset, float maxOutset) {
            float outset = ctx.random(Math.min(minOutset, maxOutset), Math.max(minOutset, maxOutset));
            return switch (ctx.randomInt(4)) {
                case 0 -> new Launch(ctx.random(targetX, targetX + targetWidth), targetY - outset, 0.0F, -1.0F, 1.0F, 0.0F);
                case 1 -> new Launch(targetX + targetWidth + outset, ctx.random(targetY, targetY + targetHeight), 1.0F, 0.0F, 0.0F, 1.0F);
                case 2 -> new Launch(ctx.random(targetX, targetX + targetWidth), targetY + targetHeight + outset, 0.0F, 1.0F, -1.0F, 0.0F);
                default -> new Launch(targetX - outset, ctx.random(targetY, targetY + targetHeight), -1.0F, 0.0F, 0.0F, -1.0F);
            };
        }
    }

    private static void runSequence(JsonObject cfg, EffectContext ctx, SpawnRegion region) {
        JsonArray stages = array(cfg, "stages");
        for (JsonElement element : stages) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject stage = element.getAsJsonObject();
            int repeat = integerRange(ctx, stage, "repeat", 1, 1, 64);
            float at = randomRange(ctx, stage, "at", 0.0F, 0.0F);
            float interval = randomRange(ctx, stage, "interval", 0.0F, 0.0F);
            float jitter = Math.max(0.0F, decimal(stage, "jitter", 0.0F));
            for (int i = 0; i < repeat; i++) {
                float delay = Math.max(0.0F, at + interval * i + (jitter <= 0.0F ? 0.0F : ctx.random(-jitter, jitter)));
                JsonObject scheduledStage = stage.deepCopy();
                ctx.schedule(delay, scheduledCtx -> runStageActions(cfg, scheduledStage, scheduledCtx, region));
            }
        }
    }

    /**
     * Executes a restrained JSON-authored idle state only while the owning
     * widget remains active. Idle particles are short lived, so leaving hover
     * stops new emissions immediately and the remaining visual decays naturally.
     */
    private static void runIdle(JsonObject cfg, EffectContext ctx, float dt) {
        if (!ctx.hoverActive() || !cfg.has("idle") || !cfg.get("idle").isJsonObject()) return;
        JsonObject idle = cfg.getAsJsonObject("idle");
        float startAfter = Math.max(0.0F, decimal(idle, "start_after", 0.35F));
        if (ctx.effectAgeSeconds() < startAfter) return;
        float rate = Math.max(0.0F, decimal(idle, "rate", 1.25F));
        if (rate <= 0.0F) return;

        int firings = ctx.emissionCount("json-idle:" + string(cfg, "id", "effect"), rate, dt);
        firings = Math.min(firings, integer(idle, "max_per_frame", 2, 1, 8));
        SpawnRegion region = SpawnRegion.capture(ctx);
        for (int i = 0; i < firings; i++) {
            runStageActions(cfg, idle, ctx, region);
        }
    }

    private static void runStageActions(JsonObject parent, JsonObject stage, EffectContext ctx, SpawnRegion region) {
        if (!ctx.chance(clamp(decimal(stage, "chance", 1.0F), 0.0F, 1.0F))) return;
        JsonArray actions = array(stage, "actions");
        for (JsonElement element : actions) {
            if (element == null || !element.isJsonObject()) continue;
            runStageAction(parent, element.getAsJsonObject(), ctx, region);
        }
    }

    private static void runStageAction(JsonObject parent, JsonObject action, EffectContext ctx, SpawnRegion region) {
        String type = string(action, "type", "emit").trim().toLowerCase();
        switch (type) {
            case "emit", "burst", "emitter" -> {
                JsonObject particleCfg = emissionConfig(parent, action);
                String program = string(action, "program", string(particleCfg, "program", "burst")).trim().toLowerCase();
                if (program.equals("sequence")) program = "burst";
                emitInitial(program, particleCfg, ctx, region);
            }
            case "sound" -> playActionSound(action, ctx);
            case "block_object" -> spawnKinematicObject(parent, action, ctx, region, true);
            case "item_object" -> spawnKinematicObject(parent, action, ctx, region, false);
            case "pulse" -> {
                int tint = color(action.get("color"), color(parent.get("theme_color"), 0xFFFFFF));
                float delay = Math.max(0.0F, decimal(action, "delay", 0.0F));
                float duration = Math.max(0.05F, decimal(action, "duration", 0.28F));
                float scale = Math.max(0.1F, decimal(action, "scale", 0.85F));
                Layer layer = enumValue(Layer.class, string(action, "layer", "back"), Layer.BACK);
                ctx.pulse(delay, duration, tint, scale, layer);
            }
            case "wind", "wind_field" -> {
                boolean screen = bool(action, "screen_wide", false);
                float padding = screen ? 0.0F : Math.max(0.0F, decimal(action, "field_padding",
                        Math.max(6.0F, Math.min(region.targetWidth(), region.targetHeight()) * 0.75F)));
                float x = screen ? 0.0F : region.targetX() - padding;
                float y = screen ? 0.0F : region.targetY() - padding;
                float w = screen ? region.screenWidth() : region.targetWidth() + padding * 2.0F;
                float h = screen ? region.screenHeight() : region.targetHeight() + padding * 2.0F;
                ctx.windZone(x, y, w, h, decimal(action, "force_x", decimal(action, "wind_x", 28.0F)),
                        decimal(action, "force_y", decimal(action, "wind_y", 0.0F)), decimal(action, "lifetime", 1.1F));
            }
            case "vortex", "vortex_field" -> ctx.vortexWell(region.centerX(), region.centerY(),
                    decimal(action, "radius", 72.0F), decimal(action, "spin", 95.0F),
                    decimal(action, "pull", 36.0F), decimal(action, "lifetime", 1.2F));
            case "child_effect" -> {
                String id = string(action, "id", "").trim().toLowerCase();
                String parentId = string(parent, "id", "").trim().toLowerCase();
                UiParticleEffect child = UiParticleRegistry.get(id);
                if (child != null && !id.equals(parentId)) {
                    ctx.withTarget(region.targetX(), region.targetY(), region.targetWidth(), region.targetHeight(), childCtx -> {
                        if (bool(action, "play_begin_sound", true) && child.beginSound() != null) {
                            childCtx.playSound(child.beginSound());
                        }
                        child.onBegin(childCtx);
                    });
                }
            }
            default -> { }
        }
    }

    private static JsonObject emissionConfig(JsonObject parent, JsonObject action) {
        JsonObject result = parent.deepCopy();
        result.remove("stages");
        result.remove("sound");
        // Event-spawned particles must not implicitly inherit the event graph that spawned them.
        // Without this, an expire -> emit action can recursively create another particle with the
        // same expire -> emit action forever. Nested event choreography remains available by
        // declaring an explicit events object on action.particle.
        result.remove("events");
        for (Map.Entry<String, JsonElement> entry : action.entrySet()) {
            String key = entry.getKey();
            if (key.equals("type") || key.equals("actions") || key.equals("particle") || key.equals("id")) continue;
            result.add(key, entry.getValue().deepCopy());
        }
        if (action.has("particle") && action.get("particle").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : action.getAsJsonObject("particle").entrySet()) {
                result.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        return result;
    }

    private static void playActionSound(JsonObject action, EffectContext ctx) {
        String path = string(action, "sound", "").trim();
        if (path.isBlank()) return;
        UiParticleSoundProfile.Cue cue = new UiParticleSoundProfile.Cue(
                SoundEvent.of(identifierWithDefaultNamespace(path)),
                decimal(action, "volume", 0.22F),
                decimal(action, "pitch", 1.0F),
                decimal(action, "jitter", 0.06F),
                integer(action, "cooldown_ms", 80, 0, 60_000));
        ctx.playSound(cue);
    }

    /**
     * Spawns a flat resource-pack-aware block face or item texture as a small
     * kinematic contraption piece. Multiple JSON actions can share the same
     * motion parameters with different offsets to behave like one assembled
     * Minecraft machine without introducing a second rendering system.
     */
    private static void spawnKinematicObject(JsonObject parent, JsonObject action, EffectContext ctx, SpawnRegion region, boolean blockObject) {
        int count = integerRange(ctx, action, "count", 1, 1, 12);
        float visualScale = Math.max(0.05F, ctx.visualScale());
        String motion = string(action, "motion", "fixed").trim().toLowerCase();
        for (int index = 0; index < count; index++) {
            String rawId = pickObjectId(action, ctx, blockObject);
            if (rawId.isBlank()) continue;

            Block block = null;
            Item item = null;
            try {
                Identifier objectId = identifierWithDefaultNamespace(rawId);
                if (blockObject) {
                    block = Registries.BLOCK.get(objectId);
                    if (block == null || block == Blocks.AIR) continue;
                } else {
                    item = Registries.ITEM.get(objectId);
                    if (item == null || item == Items.AIR) continue;
                }
            } catch (RuntimeException ignored) {
                continue;
            }

            float baseX = region.centerX() + decimal(action, "offset_x", 0.0F) * visualScale;
            float baseY = region.centerY() + decimal(action, "offset_y", 0.0F) * visualScale;
            float scatterX = Math.max(0.0F, decimal(action, "scatter_x", 0.0F)) * visualScale;
            float scatterY = Math.max(0.0F, decimal(action, "scatter_y", 0.0F)) * visualScale;
            if (count > 1) {
                baseX += ctx.random(-scatterX, scatterX);
                baseY += ctx.random(-scatterY, scatterY);
            }

            float life = Math.max(0.12F, randomRange(ctx, action, "life", 1.45F, 2.35F));
            float pixels = Math.max(2.0F, randomRange(ctx, action, "visual_pixels", blockObject ? 10.0F : 9.0F, blockObject ? 15.0F : 13.0F));
            float rotation = decimal(action, "rotation", 0.0F);
            float rotationSpeed = decimal(action, "rotation_speed", 0.0F);
            Layer layer = enumValue(Layer.class, string(action, "layer", "front"), Layer.FRONT);
            float alpha = clamp(decimal(action, "alpha", 1.0F), 0.05F, 1.0F);

            float defaultMass = blockObject ? interactionMassForBlock(block) : interactionMassForItem(item);
            ParticleBuilder builder = ctx.particle(blockObject ? Shape.BLOCK_SHARD : Shape.PIXEL, baseX, baseY)
                    .visualPixels(pixels)
                    .lifetime(life)
                    .layer(layer)
                    .rotation(rotation)
                    .rotationPolicy(RotationPolicy.FREE)
                    .angularVelocity(0.0F)
                    .alpha(alpha)
                    .fades(decimal(action, "fade_in", 0.04F), decimal(action, "fade_out", 0.24F))
                    .mass(decimal(action, "mass", defaultMass))
                    .solidity(decimal(action, "solidity", blockObject ? 0.90F : 0.38F))
                    .particleCollision(bool(action, "particle_collision", false))
                    .protectTarget(false)
                    .collideButtons(false)
                    .collideTarget(false)
                    .collideScreen(false)
                    .simulationLayer(integer(action, "simulation_layer", integer(action, "layer_id", 0, -4096, 4096), -4096, 4096))
                    .crossLayerInteractions(bool(action, "cross_layer_interactions", false))
                    .textureTint(false)
                    .tag(blockObject ? "block_object" : "item_object")
                    .tag(blockObject ? Registries.BLOCK.getId(block).toString() : Registries.ITEM.getId(item).toString())
                    .data("__koil_kinematic", "true")
                    .data("__koil_motion", motion);
            if (blockObject) {
                builder.data("__koil_block_state", UiBlockFaceTextureResolver.defaultStateSignature(block));
            }
            String authoredBlockState = string(action, "block_state", "").trim();
            if (blockObject && !authoredBlockState.isBlank()) builder.data("__koil_block_state", authoredBlockState);
            for (JsonElement tag : array(action, "tags")) if (tag != null && tag.isJsonPrimitive()) builder.tag(tag.getAsString());
            if (blockObject) builder.blockIcon(block);
            else builder.itemIcon(item);

            if (motion.equals("ballistic")) {
                builder.velocity(randomRange(ctx, action, "vx", -18.0F, 18.0F), randomRange(ctx, action, "vy", -42.0F, -20.0F))
                        .gravity(decimal(action, "gravity", 145.0F))
                        .drag(decimal(action, "drag", 0.992F))
                        .restitution(decimal(action, "restitution", 0.62F))
                        .collideTarget(bool(action, "collide_target", true))
                        .collideButtons(bool(action, "collide_buttons", true))
                        .collideScreen(bool(action, "collide_screen", true))
                        .bounceCeiling(bool(action, "bounce_ceiling", false))
                        .angularVelocity(rotationSpeed == 0.0F ? ctx.random(-180.0F, 180.0F) : rotationSpeed);
            } else {
                final float originX = baseX;
                final float originY = baseY;
                final float finalLife = life;
                final float phase = decimal(action, "phase", 0.0F);
                final float frequency = Math.max(0.05F, decimal(action, "frequency", 2.2F));
                final float ampX = decimal(action, "amplitude_x", decimal(action, "amplitude", 8.0F)) * visualScale;
                final float ampY = decimal(action, "amplitude_y", decimal(action, "amplitude", 8.0F)) * visualScale;
                final float dirX = decimal(action, "direction_x", 1.0F);
                final float dirY = decimal(action, "direction_y", -1.0F);
                final float launchVx = decimal(action, "vx", 30.0F) * visualScale;
                final float launchVy = decimal(action, "vy", -55.0F) * visualScale;
                final float gravity = decimal(action, "gravity", 95.0F) * visualScale;
                final float rot0 = rotation;
                final float rotSpeed = rotationSpeed;
                builder.velocity(0.0F, 0.0F).gravity(0.0F).drag(1.0F)
                        .onTick(ev -> {
                            float age = ev.age();
                            float angle = (float) (Math.PI * 2.0) * frequency * age + phase;
                            float activeOriginX = eventFloatData(ev, "__koil_origin_x", originX);
                            float activeOriginY = eventFloatData(ev, "__koil_origin_y", originY);
                            float x = activeOriginX;
                            float y = activeOriginY;
                            switch (motion) {
                                case "pogo" -> {
                                    x += (float) Math.sin(angle * 0.5F) * ampX * 0.18F;
                                    y -= Math.abs((float) Math.sin(angle)) * Math.abs(ampY);
                                }
                                case "recoil" -> {
                                    float decay = (float) Math.exp(-2.7F * age);
                                    x += (float) Math.sin(angle) * ampX * decay * dirX;
                                    y += (float) Math.sin(angle) * ampY * 0.18F * decay * dirY;
                                }
                                case "pendulum" -> {
                                    x += (float) Math.sin(angle) * ampX;
                                    y += (1.0F - (float) Math.cos(angle)) * ampY * 0.32F;
                                }
                                case "orbit" -> {
                                    x += (float) Math.cos(angle) * ampX;
                                    y += (float) Math.sin(angle) * ampY;
                                }
                                case "boomerang" -> {
                                    float t = clamp(age / Math.max(0.05F, finalLife), 0.0F, 1.0F);
                                    float arc = (float) Math.sin(Math.PI * t);
                                    x += ampX * arc * dirX;
                                    y -= ampY * (float) Math.sin(Math.PI * t * 0.92F) * Math.abs(dirY);
                                }
                                case "shake" -> {
                                    x += (float) Math.sin(angle * 2.7F) * ampX;
                                    y += (float) Math.cos(angle * 3.1F) * ampY;
                                }
                                case "wave" -> {
                                    x += (float) Math.sin(angle) * ampX;
                                    y += (float) Math.sin(angle * 0.55F + phase) * ampY;
                                }
                                case "launch" -> {
                                    x += launchVx * age * dirX;
                                    y += launchVy * age * Math.abs(dirY) + 0.5F * gravity * age * age;
                                }
                                default -> { }
                            }
                            ev.position(x, y);
                            if (rotSpeed != 0.0F) ev.rotation(rot0 + rotSpeed * age);
                        });
            }
            configureNativeGameObject(parent, action, builder, block, item, blockObject);
            configureInteraction(parent, action, builder, region);
            ctx.spawn(builder);
        }
    }

    private static float eventFloatData(ParticleEventContext event, String key, float fallback) {
        try {
            String value = event.data(key);
            return value == null || value.isBlank() ? fallback : Float.parseFloat(value);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static float interactionMassForBlock(Block block) {
        if (block == null || block == Blocks.AIR) return 2.4F;
        Identifier id = Registries.BLOCK.getId(block);
        String path = id == null ? "" : id.getPath().toLowerCase();
        if (containsAny(path, "anvil")) return 12.0F;
        if (containsAny(path, "obsidian", "netherite_block", "lodestone")) return 9.0F;
        if (containsAny(path, "iron_block", "gold_block", "copper", "cauldron", "hopper", "piston")) return 6.4F;
        if (containsAny(path, "deepslate", "stone", "brick", "basalt", "blackstone", "ore", "terracotta")) return 4.8F;
        if (containsAny(path, "glass", "amethyst", "ice")) return 1.65F;
        if (containsAny(path, "slime")) return 1.15F;
        if (containsAny(path, "honey")) return 1.40F;
        if (containsAny(path, "sand", "gravel", "concrete_powder")) return 1.30F;
        if (containsAny(path, "wool", "leaves", "moss", "sponge")) return 0.62F;
        if (containsAny(path, "planks", "wood", "log", "stem", "hyphae", "chest", "barrel", "bookshelf")) return 2.15F;
        return 2.6F;
    }

    private static float interactionMassForItem(Item item) {
        if (item == null || item == Items.AIR) return 0.55F;
        Identifier id = Registries.ITEM.getId(item);
        String path = id == null ? "" : id.getPath().toLowerCase();
        if (containsAny(path, "netherite", "anvil", "shield")) return 1.65F;
        if (containsAny(path, "iron", "gold", "chainmail", "trident", "crossbow")) return 1.15F;
        if (containsAny(path, "diamond", "emerald", "amethyst", "quartz")) return 0.82F;
        if (containsAny(path, "sword", "pickaxe", "axe", "shovel", "hoe", "bow", "fishing_rod")) return 0.90F;
        if (containsAny(path, "book", "map", "paper", "feather", "elytra")) return 0.28F;
        if (containsAny(path, "snowball", "egg", "seed", "berry", "cookie")) return 0.22F;
        if (containsAny(path, "bucket")) return 1.05F;
        return 0.48F;
    }

    /**
     * Installs the same native user/sprite/environment capability layer used by
     * code-backed registry mirrors onto JSON-authored block_object/item_object
     * sprites. JSON can extend the behavior because native handlers are added,
     * not replaced. Authors can opt out with native_interactions=false.
     */
    private static void configureNativeGameObject(JsonObject parent, JsonObject action, ParticleBuilder builder,
                                                  Block block, Item item, boolean blockObject) {
        if (builder == null || action == null) return;
        boolean inherited = parent == null || bool(parent, "native_interactions", true);
        if (!bool(action, "native_interactions", inherited)) return;

        if (blockObject && block != null && block != Blocks.AIR) {
            Identifier id = Registries.BLOCK.getId(block);
            GameSpriteBehavior.BlockProfile profile = GameSpriteBehavior.blockProfile(block);
            String relationName = string(action, "name", "").trim();
            if (relationName.isBlank()) relationName = "block." + id.getNamespace() + "." + id.getPath().replace('/', '.');
            builder
                    .interactive(true)
                    .hoverInteraction(true)
                    .clickInteraction(true)
                    .consumePointerInput(true)
                    .hoverResetAge(true)
                    .selectable(true, true)
                    .dragInteraction(true, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0.035F)
                    .dragMassPhysics(true, 1.35F)
                    .swipeInteraction(true, 2050.0F, 0.0045F, 7.5F, 420L)
                    .swipeMassPhysics(true, 1.55F, 0.07F, 80.0F)
                    .scrollRotation(true, false, 90.0F, false, 0.45F, 90.0F, 360.0F, false)
                    .keyboardInteraction(true,
                            GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D,
                            GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_RIGHT,
                            GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_E)
                    .relationIdentity(relationName, profile.role())
                    .relationInteraction(true, profile.sensorRadius(), 1.05F, false, true)
                    .signal("power", profile.initialPower())
                    .soundProfile(GameSpriteSoundResolver.blockProfile(block))
                    .tag("native_game_object")
                    .tag("registry_sprite")
                    .tag("registry_block")
                    .tag("block")
                    .tag("block:" + id)
                    .tag("scroll_rotatable")
                    .tag(profile.role())
                    .data("registry_id", id.toString())
                    .data("native_capability", "block")
                    .addOnInteract(ev -> GameSpriteBehavior.handleBlockInteraction(block, ev))
                    .addOnRelation(ev -> GameSpriteBehavior.handleBlockRelation(block, ev))
                    .addOnTick(ev -> GameSpriteBehavior.tickBlock(block, ev));
            for (String tag : profile.tags()) builder.tag(tag);
            return;
        }

        if (!blockObject && item != null && item != Items.AIR) {
            Identifier id = Registries.ITEM.getId(item);
            GameSpriteBehavior.ItemProfile profile = GameSpriteBehavior.itemProfile(item);
            String relationName = string(action, "name", "").trim();
            if (relationName.isBlank()) relationName = "item." + id.getNamespace() + "." + id.getPath().replace('/', '.');
            builder
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
                    .relationIdentity(relationName, profile.role())
                    .relationInteraction(true, profile.sensorRadius(), 1.0F, false, true)
                    .soundProfile(GameSpriteBehavior.itemSoundProfile(item))
                    .tag("native_game_object")
                    .tag("registry_sprite")
                    .tag("registry_item")
                    .tag("item")
                    .tag("item:" + id)
                    .tag("scroll_rotatable")
                    .tag(profile.role())
                    .data("registry_id", id.toString())
                    .data("native_capability", "item")
                    .addOnInteract(ev -> GameSpriteBehavior.handleItemInteraction(item, ev))
                    .addOnRelation(ev -> GameSpriteBehavior.handleItemRelation(item, ev))
                    .addOnTick(ev -> GameSpriteBehavior.tickItem(item, ev));
            for (String tag : profile.tags()) builder.tag(tag);
        }
    }

    private static String pickObjectId(JsonObject action, EffectContext ctx, boolean blockObject) {
        String singularKey = blockObject ? "block" : "item";
        String pluralKey = blockObject ? "blocks" : "items";
        JsonArray options = array(action, pluralKey);
        if (!options.isEmpty()) {
            for (int attempt = 0; attempt < options.size(); attempt++) {
                JsonElement candidate = options.get(ctx.randomInt(options.size()));
                if (candidate != null && candidate.isJsonPrimitive()) {
                    String value = candidate.getAsString().trim();
                    if (!value.isBlank()) return value;
                }
            }
        }
        return string(action, singularKey, "").trim();
    }

    private static void configureInteraction(JsonObject parent, JsonObject cfg, ParticleBuilder builder, SpawnRegion region) {
        if (builder == null || cfg == null || !cfg.has("interaction") || !cfg.get("interaction").isJsonObject()) return;
        JsonObject interaction = cfg.getAsJsonObject("interaction");
        boolean enabled = bool(interaction, "enabled", true);
        if (!enabled) {
            builder.interactive(false)
                    .hoverInteraction(false)
                    .clickInteraction(false)
                    .hoverResetAge(false)
                    .selectable(false, false)
                    .dragInteraction(false, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0.0F)
                    .swipeInteraction(false, 1100.0F, 0.0F, 34.0F, 250L)
                    .scrollRotation(false, true, 15.0F, false, 0.0F, 0.0F, 420.0F, false)
                    .keyboardInteraction(false)
                    .relationInteraction(false, 0.0F, 1.0F, true, false)
                    .interactive(false);
            return;
        }
        builder.interactive(true)
                .hoverInteraction(bool(interaction, "hover_enabled", true))
                .clickInteraction(bool(interaction, "click_enabled", true))
                .consumePointerInput(bool(interaction, "consume_input", false))
                .hoverResetAge(bool(interaction, "hover_reset_age", true))
                .interactionHitboxScale(decimal(interaction, "hitbox_scale", 1.35F));

        JsonObject selection = object(interaction, "selection");
        builder.selectable(selection.entrySet().isEmpty() ? bool(interaction, "selectable", true) : bool(selection, "enabled", true),
                selection.entrySet().isEmpty() ? bool(interaction, "selection_reset_age", true) : bool(selection, "reset_age", true));

        JsonObject drag = object(interaction, "drag");
        if (!drag.entrySet().isEmpty()) {
            builder.dragInteraction(bool(drag, "enabled", false), mouseButton(drag.get("button"), 0),
                    decimal(drag, "release_velocity", 0.11F));
            builder.dragMassPhysics(bool(drag, "mass_aware", true), decimal(drag, "mass_exponent", 1.05F));
        }

        JsonObject swipe = object(interaction, "swipe");
        if (!swipe.entrySet().isEmpty()) {
            builder.swipeInteraction(bool(swipe, "enabled", false),
                    decimal(swipe, "min_speed", 1100.0F), decimal(swipe, "strength", 0.026F),
                    decimal(swipe, "max_impulse", 34.0F), integer(swipe, "cooldown_ms", 250, 0, 60_000));
            builder.swipeMassPhysics(bool(swipe, "mass_aware", true), decimal(swipe, "mass_exponent", 1.15F),
                    decimal(swipe, "torque_strength", 0.20F), decimal(swipe, "max_angular_impulse", 240.0F));
        }

        JsonObject scroll = object(interaction, "scroll_rotate");
        boolean taggedScroll = hasTag(cfg, "scroll_rotatable") || hasTag(cfg, "wheel_rotatable");
        if (!scroll.entrySet().isEmpty() || taggedScroll) {
            builder.scrollRotation(scroll.entrySet().isEmpty() || bool(scroll, "enabled", true),
                    scroll.entrySet().isEmpty() || bool(scroll, "require_grabbed", true),
                    decimal(scroll, "degrees_per_step", 15.0F), bool(scroll, "inertial", false),
                    decimal(scroll, "angular_impulse", 1.0F), decimal(scroll, "snap_degrees", 0.0F),
                    decimal(scroll, "max_angular_velocity", 900.0F), bool(scroll, "invert", false));
        }

        JsonObject keyboard = object(interaction, "keyboard");
        if (!keyboard.entrySet().isEmpty() && bool(keyboard, "enabled", true)) {
            for (JsonElement bindingElement : array(keyboard, "bindings")) {
                if (bindingElement == null || !bindingElement.isJsonObject()) continue;
                int key = keyCode(bindingElement.getAsJsonObject().get("key"), -1);
                if (key >= 0) builder.watchKey(key);
            }
        }

        JsonObject relations = object(interaction, "relations");
        if (!relations.entrySet().isEmpty() && bool(relations, "enabled", true)) {
            builder.relationIdentity(string(relations, "name", string(cfg, "name", "")),
                    string(relations, "role", string(cfg, "role", "")))
                    .relationInteraction(true, decimal(relations, "sensor_radius", 0.0F),
                            decimal(relations, "overlap_scale", 1.0F), bool(relations, "same_layer_only", true),
                            bool(relations, "cross_layer", false));
            for (JsonElement tag : array(relations, "tags")) {
                if (tag != null && tag.isJsonPrimitive()) builder.tag(tag.getAsString());
            }
            JsonObject signals = object(relations, "signals");
            for (Map.Entry<String, JsonElement> entry : signals.entrySet()) {
                try { builder.signal(entry.getKey(), entry.getValue().getAsFloat()); } catch (RuntimeException ignored) { }
            }
            JsonObject frozenRelations = relations.deepCopy();
            JsonObject frozenParent = parent == null ? cfg.deepCopy() : parent.deepCopy();
            builder.addOnRelation(ev -> runRelationDefinition(frozenParent, frozenRelations, ev, region));
        }

        JsonObject frozenParent = parent == null ? cfg.deepCopy() : parent.deepCopy();
        JsonObject frozenInteraction = interaction.deepCopy();
        builder.addOnInteract(ev -> runInteractionDefinition(frozenParent, frozenInteraction, ev, region));
    }

    private static boolean hasTag(JsonObject cfg, String wanted) {
        if (cfg == null || wanted == null) return false;
        for (JsonElement tag : array(cfg, "tags")) {
            if (tag != null && tag.isJsonPrimitive() && wanted.equalsIgnoreCase(tag.getAsString().trim())) return true;
        }
        return false;
    }

    private static void runInteractionDefinition(JsonObject parent, JsonObject interaction, ParticleInteractionContext ev, SpawnRegion region) {
        String eventKey = interactionEventKey(ev.type());
        JsonObject events = object(interaction, "events");
        runNamedInteractionEvent(parent, events, eventKey, ev, region);
        if (ev.type() == ParticleInteractionType.PRESS) runNamedInteractionEvent(parent, events, "click", ev, region);

        JsonArray buttons = array(interaction, "buttons");
        for (JsonElement element : buttons) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject rule = element.getAsJsonObject();
            int wantedButton = mouseButton(rule.get("button"), -1);
            if (wantedButton >= 0 && wantedButton != ev.button()) continue;
            String wantedEvent = string(rule, "event", "press").trim().toLowerCase();
            if (!interactionEventMatches(wantedEvent, ev.type())) continue;
            if (!ev.chance(clamp(decimal(rule, "chance", 1.0F), 0.0F, 1.0F))) continue;
            runInteractionActions(parent, array(rule, "actions"), ev, region);
        }

        JsonObject keyboard = object(interaction, "keyboard");
        if (!keyboard.entrySet().isEmpty()) {
            for (JsonElement element : array(keyboard, "bindings")) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject binding = element.getAsJsonObject();
                int wantedKey = keyCode(binding.get("key"), -1);
                if (wantedKey < 0 || wantedKey != ev.key()) continue;
                String wantedEvent = string(binding, "event", "press").trim().toLowerCase();
                if (!keyInteractionEventMatches(wantedEvent, ev.type())) continue;
                if (!ev.chance(clamp(decimal(binding, "chance", 1.0F), 0.0F, 1.0F))) continue;
                runInteractionActions(parent, array(binding, "actions"), ev, region);
            }
        }
    }

    private static void runRelationDefinition(JsonObject parent, JsonObject relations,
                                              ParticleRelationContext ev, SpawnRegion region) {
        JsonArray rules = array(relations, "rules");
        for (int index = 0; index < rules.size(); index++) {
            JsonElement element = rules.get(index);
            if (element == null || !element.isJsonObject()) continue;
            JsonObject rule = element.getAsJsonObject();
            if (!relationRuleMatches(rule, ev)) continue;
            if (!ev.chance(clamp(decimal(rule, "chance", 1.0F), 0.0F, 1.0F))) continue;
            long cooldown = integer(rule, "cooldown_ms", 0, 0, 60_000);
            if (!ev.cooldownReady("relation:" + index + ":" + ev.otherId(), cooldown)) continue;
            for (JsonElement actionElement : array(rule, "actions")) {
                if (actionElement == null || !actionElement.isJsonObject()) continue;
                runRelationAction(parent, actionElement.getAsJsonObject(), ev, region);
            }
        }
    }

    private static boolean relationRuleMatches(JsonObject rule, ParticleRelationContext ev) {
        String event = string(rule, "event", "touch_enter").trim().toLowerCase();
        if (!event.equals(relationEventKey(ev.type()))) return false;
        String otherName = string(rule, "other_name", "").trim();
        if (!otherName.isBlank() && !otherName.equals("*") && !otherName.equalsIgnoreCase(ev.otherName())) return false;
        String otherRole = string(rule, "other_role", "").trim();
        if (!otherRole.isBlank() && !otherRole.equals("*") && !otherRole.equalsIgnoreCase(ev.otherRole())) return false;
        if (rule.has("self_dragging") && bool(rule, "self_dragging", false) != ev.selfDragging()) return false;
        if (rule.has("other_dragging") && bool(rule, "other_dragging", false) != ev.otherDragging()) return false;
        if (rule.has("self_selected") && bool(rule, "self_selected", false) != ev.selfSelected()) return false;
        if (rule.has("other_selected") && bool(rule, "other_selected", false) != ev.otherSelected()) return false;
        if (ev.distance() < decimal(rule, "min_distance", 0.0F)) return false;
        if (ev.distance() > decimal(rule, "max_distance", Float.MAX_VALUE)) return false;
        if (rule.has("same_layer") && bool(rule, "same_layer", true) != (ev.selfLayer() == ev.otherLayer())) return false;

        for (JsonElement tag : array(rule, "other_tags")) {
            if (tag != null && tag.isJsonPrimitive() && !ev.otherHasTag(tag.getAsString())) return false;
        }
        JsonArray anyTags = array(rule, "other_any_tags");
        if (!anyTags.isEmpty()) {
            boolean found = false;
            for (JsonElement tag : anyTags) {
                if (tag != null && tag.isJsonPrimitive() && ev.otherHasTag(tag.getAsString())) { found = true; break; }
            }
            if (!found) return false;
        }
        for (JsonElement tag : array(rule, "self_tags")) {
            if (tag != null && tag.isJsonPrimitive() && !ev.selfHasTag(tag.getAsString())) return false;
        }
        if (!relationSignalsMatch(object(rule, "self_signals"), true, ev)) return false;
        if (!relationSignalsMatch(object(rule, "other_signals"), false, ev)) return false;
        return true;
    }

    private static boolean relationSignalsMatch(JsonObject conditions, boolean self, ParticleRelationContext ev) {
        for (Map.Entry<String, JsonElement> entry : conditions.entrySet()) {
            float value = self ? ev.selfSignal(entry.getKey()) : ev.otherSignal(entry.getKey());
            JsonElement condition = entry.getValue();
            if (condition == null || condition.isJsonNull()) continue;
            try {
                if (condition.isJsonArray() && condition.getAsJsonArray().size() >= 2) {
                    float a = condition.getAsJsonArray().get(0).getAsFloat();
                    float b = condition.getAsJsonArray().get(1).getAsFloat();
                    if (value < Math.min(a, b) || value > Math.max(a, b)) return false;
                } else if (condition.isJsonObject()) {
                    JsonObject c = condition.getAsJsonObject();
                    if (value < decimal(c, "min", -Float.MAX_VALUE) || value > decimal(c, "max", Float.MAX_VALUE)) return false;
                } else if (condition.isJsonPrimitive()) {
                    float minimum = condition.getAsFloat();
                    if (value < minimum) return false;
                }
            } catch (RuntimeException ignored) { return false; }
        }
        return true;
    }

    private static String relationEventKey(ParticleRelationType type) {
        return switch (type) {
            case TOUCH_ENTER -> "touch_enter";
            case TOUCH -> "touch";
            case TOUCH_EXIT -> "touch_exit";
            case OVERLAP_ENTER -> "overlap_enter";
            case OVERLAP -> "overlap";
            case OVERLAP_EXIT -> "overlap_exit";
            case SENSOR_ENTER -> "sensor_enter";
            case SENSOR -> "sensor";
            case SENSOR_EXIT -> "sensor_exit";
        };
    }

    private static void runRelationAction(JsonObject parent, JsonObject action,
                                          ParticleRelationContext ev, SpawnRegion region) {
        String type = string(action, "type", "emit").trim().toLowerCase();
        switch (type) {
            case "set_signal", "add_signal", "toggle_signal", "copy_signal" -> {
                String signal = string(action, "signal", "power").trim().toLowerCase();
                String target = string(action, "target", "other").trim().toLowerCase();
                float currentSelf = ev.selfSignal(signal);
                float currentOther = ev.otherSignal(signal);
                float value;
                if (type.equals("copy_signal")) {
                    String from = string(action, "from", "self").trim().toLowerCase();
                    String sourceSignal = string(action, "source_signal", signal).trim().toLowerCase();
                    float source = from.equals("other") ? ev.otherSignal(sourceSignal) : ev.selfSignal(sourceSignal);
                    value = source * decimal(action, "scale", 1.0F) + decimal(action, "offset", 0.0F);
                } else if (type.equals("add_signal")) {
                    float amount = decimal(action, "amount", 1.0F);
                    value = (target.equals("self") ? currentSelf : currentOther) + amount;
                } else if (type.equals("toggle_signal")) {
                    float current = target.equals("self") ? currentSelf : currentOther;
                    value = Math.abs(current) > 0.0001F ? 0.0F : decimal(action, "on_value", 15.0F);
                } else {
                    value = decimal(action, "value", 0.0F);
                }
                value = clamp(value, decimal(action, "min", -1_000_000.0F), decimal(action, "max", 1_000_000.0F));
                if (target.equals("self")) ev.selfSignal(signal, value);
                else if (target.equals("both")) { ev.selfSignal(signal, value); ev.otherSignal(signal, value); }
                else ev.otherSignal(signal, value);
            }
            case "set_block_state" -> {
                String target = string(action, "target", "other").trim().toLowerCase();
                String state = string(action, "state", string(action, "block_state", ""));
                if (target.equals("self") || target.equals("both")) ev.withSelf(ctx -> ctx.blockState(state));
                if (target.equals("other") || target.equals("both")) ev.withOther(ctx -> ctx.blockState(state));
            }
            case "apply_force" -> {
                String target = string(action, "target", "other").trim().toLowerCase();
                float fx = decimal(action, "force_x", 0.0F), fy = decimal(action, "force_y", 0.0F);
                if (target.equals("self") || target.equals("both")) ev.applyForceToSelf(fx, fy);
                if (target.equals("other") || target.equals("both")) ev.applyForceToOther(fx, fy);
            }
            case "attract", "repel" -> {
                float dx = ev.otherX() - ev.selfX();
                float dy = ev.otherY() - ev.selfY();
                float len = Math.max(0.001F, (float) Math.sqrt(dx * dx + dy * dy));
                float strength = Math.abs(decimal(action, "strength", 28.0F));
                float sign = type.equals("attract") ? 1.0F : -1.0F;
                float fx = dx / len * strength * sign;
                float fy = dy / len * strength * sign;
                String target = string(action, "target", "other").trim().toLowerCase();
                // dx/dy point from self to other. Attraction moves self toward other
                // and other toward self; repulsion reverses those forces.
                if (target.equals("self")) ev.applyForceToSelf(fx, fy);
                else if (target.equals("both")) { ev.applyForceToSelf(fx, fy); ev.applyForceToOther(-fx, -fy); }
                else ev.applyForceToOther(-fx, -fy);
            }
            case "transfer_velocity" -> {
                float scale = decimal(action, "scale", 1.0F);
                String from = string(action, "from", "self").trim().toLowerCase();
                String target = string(action, "target", from.equals("self") ? "other" : "self").trim().toLowerCase();
                float vx = from.equals("other") ? ev.otherVx() : ev.selfVx();
                float vy = from.equals("other") ? ev.otherVy() : ev.selfVy();
                if (target.equals("self")) ev.withSelf(ctx -> ctx.addVelocity(vx * scale, vy * scale));
                else ev.withOther(ctx -> ctx.addVelocity(vx * scale, vy * scale));
            }
            case "spring_link" -> ev.linkSpring(decimal(action, "rest_length", Math.max(4.0F, ev.distance())),
                    decimal(action, "stiffness", 18.0F), decimal(action, "damping", 2.5F),
                    decimal(action, "break_distance", Math.max(24.0F, ev.distance() * 2.0F)),
                    decimal(action, "max_force", 280.0F));
            case "unlink", "remove_link" -> ev.unlink();
            default -> {
                String target = string(action, "target", "self").trim().toLowerCase();
                if (target.equals("self") || target.equals("both")) ev.withSelf(ctx -> runInteractionAction(parent, action, ctx, region));
                if (target.equals("other") || target.equals("both")) ev.withOther(ctx -> runInteractionAction(parent, action, ctx, region));
            }
        }
    }

    private static void runNamedInteractionEvent(JsonObject parent, JsonObject events, String key,
                                                 ParticleInteractionContext ev, SpawnRegion region) {
        if (key == null || key.isBlank() || !events.has(key)) return;
        JsonElement value = events.get(key);
        if (value.isJsonObject()) {
            JsonObject rule = value.getAsJsonObject();
            if (!ev.chance(clamp(decimal(rule, "chance", 1.0F), 0.0F, 1.0F))) return;
            runInteractionActions(parent, array(rule, "actions"), ev, region);
        } else if (value.isJsonArray()) {
            runInteractionActions(parent, value.getAsJsonArray(), ev, region);
        }
    }

    private static void runInteractionActions(JsonObject parent, JsonArray actions,
                                              ParticleInteractionContext ev, SpawnRegion region) {
        for (JsonElement element : actions) {
            if (element == null || !element.isJsonObject()) continue;
            runInteractionAction(parent, element.getAsJsonObject(), ev, region);
        }
    }

    private static void runInteractionAction(JsonObject parent, JsonObject action,
                                             ParticleInteractionContext ev, SpawnRegion region) {
        String type = string(action, "type", "emit").trim().toLowerCase();
        switch (type) {
            case "emit", "burst", "emitter" -> {
                JsonObject particleCfg = emissionConfig(parent, action);
                particleCfg.remove("interaction");
                if (!particleCfg.has("origin")) particleCfg.addProperty("origin", "center");
                if (!particleCfg.has("pattern")) particleCfg.addProperty("pattern", "center_burst");
                String program = string(action, "program", string(particleCfg, "program", "burst")).trim().toLowerCase();
                SpawnRegion point = SpawnRegion.point(ev.x(), ev.y(), region);
                ev.withEffect(ctx -> emitInitial(program.equals("sequence") ? "burst" : program, particleCfg, ctx, point));
            }
            case "sound" -> {
                String path = string(action, "sound", "").trim();
                if (!path.isBlank()) ev.playSound(new UiParticleSoundProfile.Cue(
                        SoundEvent.of(identifierWithDefaultNamespace(path)), decimal(action, "volume", 0.12F),
                        decimal(action, "pitch", 1.0F), decimal(action, "jitter", 0.05F),
                        integer(action, "cooldown_ms", 90, 0, 60_000)));
            }
            case "pulse" -> ev.pulse(decimal(action, "duration", 0.20F),
                    color(action.get("color"), ev.color()), decimal(action, "scale", 0.55F),
                    enumValue(Layer.class, string(action, "layer", "front"), Layer.FRONT));
            case "kill" -> ev.kill();
            case "reset_age" -> ev.resetAge();
            case "set_age" -> ev.age(interactionRange(ev, action, "age", 0.0F, 0.0F));
            case "set_lifetime" -> ev.lifetime(interactionRange(ev, action, "lifetime", ev.lifetime(), ev.lifetime()));
            case "add_velocity" -> ev.addVelocity(interactionRange(ev, action, "vx", 0.0F, 0.0F),
                    interactionRange(ev, action, "vy", 0.0F, 0.0F));
            case "set_velocity" -> ev.velocity(interactionRange(ev, action, "vx", ev.vx(), ev.vx()),
                    interactionRange(ev, action, "vy", ev.vy(), ev.vy()));
            case "set_gravity" -> ev.gravity(decimal(action, "gravity", 0.0F));
            case "set_drag" -> ev.drag(decimal(action, "drag", 0.98F));
            case "set_restitution" -> ev.restitution(decimal(action, "restitution", 0.5F));
            case "set_behavior" -> ev.behavior(enumValue(Behavior.class, string(action, "behavior", "ballistic"), Behavior.BALLISTIC));
            case "set_material" -> ev.material(enumValue(UiParticleMaterials.Material.class,
                    string(action, "material", "default"), UiParticleMaterials.Material.DEFAULT));
            case "set_block_state" -> ev.blockState(string(action, "state", string(action, "block_state", "")));
            case "set_alpha" -> ev.alpha(decimal(action, "alpha", 1.0F));
            case "set_color" -> ev.color(color(action.get("color"), ev.color()));
            case "set_visual_pixels" -> ev.visualPixels(interactionRange(ev, action, "visual_pixels", ev.visualPixels(), ev.visualPixels()));
            case "set_sprite_scale" -> ev.spriteScale(interactionRange(ev, action, "sprite_scale", ev.visualScale(), ev.visualScale()));
            case "set_rotation" -> ev.rotation(interactionRange(ev, action, "rotation", ev.rotation(), ev.rotation()));
            case "add_rotation" -> ev.rotation(ev.rotation() + interactionRange(ev, action, "degrees", 0.0F, 0.0F));
            case "set_angular_velocity" -> ev.angularVelocity(interactionRange(ev, action, "angular_velocity", ev.angularVelocity(), ev.angularVelocity()));
            case "add_angular_velocity" -> ev.addAngularVelocity(interactionRange(ev, action, "angular_velocity", 0.0F, 0.0F));
            case "apply_force" -> ev.applyForce(interactionRange(ev, action, "force_x", 0.0F, 0.0F),
                    interactionRange(ev, action, "force_y", 0.0F, 0.0F));
            case "set_signal" -> {
                String signal = string(action, "signal", "power").trim().toLowerCase();
                ev.signal(signal, interactionRange(ev, action, "value", 0.0F, 0.0F));
            }
            case "add_signal" -> {
                String signal = string(action, "signal", "power").trim().toLowerCase();
                ev.addSignal(signal, interactionRange(ev, action, "amount", 1.0F, 1.0F));
            }
            case "toggle_signal" -> {
                String signal = string(action, "signal", "power").trim().toLowerCase();
                float on = decimal(action, "on_value", 15.0F);
                ev.signal(signal, Math.abs(ev.signal(signal)) > 0.0001F ? 0.0F : on);
            }
            case "teleport" -> {
                boolean relative = bool(action, "relative", true);
                float x = interactionRange(ev, action, "x", 0.0F, 0.0F);
                float y = interactionRange(ev, action, "y", 0.0F, 0.0F);
                ev.position(relative ? ev.x() + x : x, relative ? ev.y() + y : y);
            }
            case "cursor_impulse" -> {
                String direction = string(action, "direction", "with_mouse").trim().toLowerCase();
                float strength = decimal(action, "strength", 0.24F);
                float max = Math.max(1.0F, decimal(action, "max_impulse", 220.0F));
                float ix;
                float iy;
                if (direction.equals("toward_cursor") || direction.equals("away_cursor")) {
                    float dx = ev.pointerX() - ev.x();
                    float dy = ev.pointerY() - ev.y();
                    float len = Math.max(0.001F, (float) Math.sqrt(dx * dx + dy * dy));
                    float sign = direction.equals("away_cursor") ? -1.0F : 1.0F;
                    float magnitude = Math.min(max, Math.max(24.0F, ev.pointerSpeed() * strength));
                    ix = dx / len * magnitude * sign;
                    iy = dy / len * magnitude * sign;
                } else {
                    ix = clamp(ev.pointerVelocityX() * strength, -max, max);
                    iy = clamp(ev.pointerVelocityY() * strength, -max, max);
                }
                ev.addVelocity(ix, iy);
            }
            case "wind", "wind_field" -> {
                float padding = decimal(action, "field_padding", 18.0F);
                ev.windZone(ev.x() - padding, ev.y() - padding, padding * 2.0F, padding * 2.0F,
                        decimal(action, "force_x", 22.0F), decimal(action, "force_y", 0.0F), decimal(action, "lifetime", 0.55F));
            }
            case "vortex", "vortex_field" -> ev.vortexWell(ev.x(), ev.y(), decimal(action, "radius", 34.0F),
                    decimal(action, "spin", 88.0F), decimal(action, "pull", 30.0F), decimal(action, "lifetime", 0.65F));
            case "block_object" -> {
                SpawnRegion point = SpawnRegion.point(ev.x(), ev.y(), region);
                ev.withEffect(ctx -> spawnKinematicObject(parent, action, ctx, point, true));
            }
            case "item_object" -> {
                SpawnRegion point = SpawnRegion.point(ev.x(), ev.y(), region);
                ev.withEffect(ctx -> spawnKinematicObject(parent, action, ctx, point, false));
            }
            case "child_effect" -> {
                String id = string(action, "id", "").trim().toLowerCase();
                UiParticleEffect child = UiParticleRegistry.get(id);
                if (child != null && !id.equals(string(parent, "id", "").trim().toLowerCase())) {
                    ev.withEffect(ctx -> ctx.withTarget(ev.x() - 10.0F, ev.y() - 10.0F, 20.0F, 20.0F, childCtx -> {
                        if (bool(action, "play_begin_sound", true) && child.beginSound() != null) childCtx.playSound(child.beginSound());
                        child.onBegin(childCtx);
                    }));
                }
            }
            case "set_data" -> {
                String key = string(action, "key", "").trim();
                if (!key.isBlank()) ev.data(key, string(action, "value", ""));
            }
            case "increment_data" -> {
                String key = string(action, "key", "counter").trim();
                int value = 0;
                try { String raw = ev.data(key); if (raw != null) value = Integer.parseInt(raw); } catch (RuntimeException ignored) { }
                ev.data(key, value + integer(action, "amount", 1, -1_000_000, 1_000_000));
            }
            default -> { }
        }
    }

    private static float interactionRange(ParticleInteractionContext ev, JsonObject cfg, String key,
                                          float fallbackMin, float fallbackMax) {
        JsonElement element = cfg.get(key);
        if (element != null && element.isJsonArray() && element.getAsJsonArray().size() >= 2) {
            float a = element.getAsJsonArray().get(0).getAsFloat();
            float b = element.getAsJsonArray().get(1).getAsFloat();
            return ev.random(Math.min(a, b), Math.max(a, b));
        }
        if (element != null && element.isJsonPrimitive()) {
            try { return element.getAsFloat(); } catch (RuntimeException ignored) { }
        }
        return ev.random(Math.min(fallbackMin, fallbackMax), Math.max(fallbackMin, fallbackMax));
    }

    private static String interactionEventKey(ParticleInteractionType type) {
        return switch (type) {
            case HOVER_ENTER -> "hover_enter";
            case HOVER -> "hover";
            case HOVER_EXIT -> "hover_exit";
            case PRESS -> "press";
            case RELEASE -> "release";
            case DRAG_START -> "drag_start";
            case DRAG -> "drag";
            case DRAG_END -> "drag_end";
            case SWIPE -> "swipe";
            case SELECT -> "select";
            case DESELECT -> "deselect";
            case SCROLL -> "scroll";
            case KEY_PRESS -> "key_press";
            case KEY_RELEASE -> "key_release";
            case KEY_HELD -> "key_held";
            case SCREEN_HIT -> "screen_hit";
            case BUTTON_HIT -> "button_hit";
            case TARGET_HIT -> "target_hit";
            case SPRITE_HIT -> "sprite_hit";
            case SIGNAL_CHANGED -> "signal_changed";
        };
    }

    private static boolean interactionEventMatches(String raw, ParticleInteractionType type) {
        String event = raw == null ? "press" : raw.trim().toLowerCase();
        if (event.equals("click")) return type == ParticleInteractionType.PRESS;
        return event.equals(interactionEventKey(type));
    }

    private static boolean keyInteractionEventMatches(String raw, ParticleInteractionType type) {
        String event = raw == null ? "press" : raw.trim().toLowerCase();
        return switch (event) {
            case "press", "down" -> type == ParticleInteractionType.KEY_PRESS;
            case "release", "up" -> type == ParticleInteractionType.KEY_RELEASE;
            case "held", "hold", "repeat" -> type == ParticleInteractionType.KEY_HELD;
            default -> false;
        };
    }

    private static int keyCode(JsonElement element, int fallback) {
        if (element == null || element.isJsonNull()) return fallback;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            try { return element.getAsInt(); } catch (RuntimeException ignored) { return fallback; }
        }
        String raw;
        try { raw = element.getAsString().trim().toLowerCase(); } catch (RuntimeException failure) { return fallback; }
        if (raw.length() == 1) {
            char c = raw.charAt(0);
            if (c >= 'a' && c <= 'z') return GLFW.GLFW_KEY_A + (c - 'a');
            if (c >= '0' && c <= '9') return GLFW.GLFW_KEY_0 + (c - '0');
        }
        return switch (raw.replace('-', '_').replace(' ', '_')) {
            case "space" -> GLFW.GLFW_KEY_SPACE;
            case "enter", "return" -> GLFW.GLFW_KEY_ENTER;
            case "tab" -> GLFW.GLFW_KEY_TAB;
            case "escape", "esc" -> GLFW.GLFW_KEY_ESCAPE;
            case "backspace" -> GLFW.GLFW_KEY_BACKSPACE;
            case "delete" -> GLFW.GLFW_KEY_DELETE;
            case "left" -> GLFW.GLFW_KEY_LEFT;
            case "right" -> GLFW.GLFW_KEY_RIGHT;
            case "up" -> GLFW.GLFW_KEY_UP;
            case "down" -> GLFW.GLFW_KEY_DOWN;
            case "left_shift", "lshift" -> GLFW.GLFW_KEY_LEFT_SHIFT;
            case "right_shift", "rshift" -> GLFW.GLFW_KEY_RIGHT_SHIFT;
            case "left_ctrl", "left_control", "lctrl" -> GLFW.GLFW_KEY_LEFT_CONTROL;
            case "right_ctrl", "right_control", "rctrl" -> GLFW.GLFW_KEY_RIGHT_CONTROL;
            case "left_alt", "lalt" -> GLFW.GLFW_KEY_LEFT_ALT;
            case "right_alt", "ralt" -> GLFW.GLFW_KEY_RIGHT_ALT;
            case "home" -> GLFW.GLFW_KEY_HOME;
            case "end" -> GLFW.GLFW_KEY_END;
            case "page_up", "pageup" -> GLFW.GLFW_KEY_PAGE_UP;
            case "page_down", "pagedown" -> GLFW.GLFW_KEY_PAGE_DOWN;
            case "insert" -> GLFW.GLFW_KEY_INSERT;
            case "f1" -> GLFW.GLFW_KEY_F1;
            case "f2" -> GLFW.GLFW_KEY_F2;
            case "f3" -> GLFW.GLFW_KEY_F3;
            case "f4" -> GLFW.GLFW_KEY_F4;
            case "f5" -> GLFW.GLFW_KEY_F5;
            case "f6" -> GLFW.GLFW_KEY_F6;
            case "f7" -> GLFW.GLFW_KEY_F7;
            case "f8" -> GLFW.GLFW_KEY_F8;
            case "f9" -> GLFW.GLFW_KEY_F9;
            case "f10" -> GLFW.GLFW_KEY_F10;
            case "f11" -> GLFW.GLFW_KEY_F11;
            case "f12" -> GLFW.GLFW_KEY_F12;
            default -> fallback;
        };
    }

    private static int mouseButton(JsonElement element, int fallback) {
        if (element == null || element.isJsonNull()) return fallback;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            try { return element.getAsInt(); } catch (RuntimeException ignored) { return fallback; }
        }
        String raw;
        try { raw = element.getAsString().trim().toLowerCase(); } catch (RuntimeException failure) { return fallback; }
        return switch (raw) {
            case "left", "primary" -> 0;
            case "right", "secondary" -> 1;
            case "middle", "wheel" -> 2;
            case "any", "*" -> -1;
            default -> {
                try { yield Integer.parseInt(raw); } catch (NumberFormatException ignored) { yield fallback; }
            }
        };
    }

    private static void validateInteraction(JsonElement element) {
        if (element == null || !element.isJsonObject()) throw new IllegalArgumentException("interaction must be an object");
        JsonObject interaction = element.getAsJsonObject();
        JsonObject drag = object(interaction, "drag");
        if (!drag.entrySet().isEmpty()) mouseButton(drag.get("button"), 0);
        JsonObject scroll = object(interaction, "scroll_rotate");
        if (!scroll.entrySet().isEmpty() && decimal(scroll, "max_angular_velocity", 900.0F) <= 0.0F) {
            throw new IllegalArgumentException("interaction.scroll_rotate.max_angular_velocity must be positive");
        }
        JsonObject events = object(interaction, "events");
        for (String key : List.of("hover_enter", "hover", "hover_exit", "press", "click", "release", "drag_start", "drag", "drag_end", "swipe",
                "select", "deselect", "scroll", "key_press", "key_release", "key_held", "screen_hit", "button_hit", "target_hit", "sprite_hit", "signal_changed")) {
            if (!events.has(key)) continue;
            JsonElement event = events.get(key);
            JsonArray actions = event.isJsonArray() ? event.getAsJsonArray() : array(event.getAsJsonObject(), "actions");
            for (JsonElement action : actions) {
                if (action == null || !action.isJsonObject()) throw new IllegalArgumentException("interaction event action must be an object");
                validateAction(action.getAsJsonObject());
            }
        }
        for (JsonElement entry : array(interaction, "buttons")) {
            if (entry == null || !entry.isJsonObject()) throw new IllegalArgumentException("interaction button rule must be an object");
            JsonObject rule = entry.getAsJsonObject();
            mouseButton(rule.get("button"), -1);
            String event = string(rule, "event", "press").trim().toLowerCase();
            if (!Set.of("click", "press", "release", "drag_start", "drag", "drag_end").contains(event)) {
                throw new IllegalArgumentException("Unsupported interaction button event: " + event);
            }
            for (JsonElement action : array(rule, "actions")) {
                if (action == null || !action.isJsonObject()) throw new IllegalArgumentException("interaction button action must be an object");
                validateAction(action.getAsJsonObject());
            }
        }
        JsonObject keyboard = object(interaction, "keyboard");
        for (JsonElement entry : array(keyboard, "bindings")) {
            if (entry == null || !entry.isJsonObject()) throw new IllegalArgumentException("keyboard binding must be an object");
            JsonObject binding = entry.getAsJsonObject();
            if (keyCode(binding.get("key"), -1) < 0) throw new IllegalArgumentException("keyboard binding has invalid key");
            String event = string(binding, "event", "press").trim().toLowerCase();
            if (!Set.of("press", "down", "release", "up", "held", "hold", "repeat").contains(event)) {
                throw new IllegalArgumentException("Unsupported keyboard interaction event: " + event);
            }
            for (JsonElement action : array(binding, "actions")) {
                if (action == null || !action.isJsonObject()) throw new IllegalArgumentException("keyboard action must be an object");
                validateAction(action.getAsJsonObject());
            }
        }
        JsonObject relations = object(interaction, "relations");
        for (JsonElement entry : array(relations, "rules")) {
            if (entry == null || !entry.isJsonObject()) throw new IllegalArgumentException("relation rule must be an object");
            JsonObject rule = entry.getAsJsonObject();
            String event = string(rule, "event", "touch_enter").trim().toLowerCase();
            if (!Set.of("touch_enter", "touch", "touch_exit", "overlap_enter", "overlap", "overlap_exit", "sensor_enter", "sensor", "sensor_exit").contains(event)) {
                throw new IllegalArgumentException("Unsupported relation event: " + event);
            }
            for (JsonElement action : array(rule, "actions")) {
                if (action == null || !action.isJsonObject()) throw new IllegalArgumentException("relation action must be an object");
                validateRelationAction(action.getAsJsonObject());
            }
        }
    }

    private static void validateRelationAction(JsonObject action) {
        String type = string(action, "type", "emit").trim().toLowerCase();
        if (Set.of("copy_signal", "attract", "repel", "transfer_velocity", "spring_link", "unlink", "remove_link").contains(type)) return;
        validateAction(action);
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : new JsonObject();
    }

    private static void attachParticleEvents(ParticleBuilder builder, JsonObject cfg, EffectContext ctx, SpawnRegion region) {
        if (!cfg.has("events") || !cfg.get("events").isJsonObject()) return;
        JsonObject events = cfg.getAsJsonObject("events");
        if (events.has("bounce") && events.get("bounce").isJsonObject()) {
            builder.onBounce(eventHandler(events.getAsJsonObject("bounce"), cfg, ctx, region));
        }
        if (events.has("expire") && events.get("expire").isJsonObject()) {
            builder.onExpire(eventHandler(events.getAsJsonObject("expire"), cfg, ctx, region));
        }
        if (events.has("tick") && events.get("tick").isJsonObject()) {
            builder.onTick(eventHandler(events.getAsJsonObject("tick"), cfg, ctx, region));
        }
    }

    private static ParticleEventHandler eventHandler(JsonObject rule, JsonObject parent, EffectContext ctx, SpawnRegion region) {
        final int[] fires = {0};
        final float[] nextAge = {Math.max(0.0F, decimal(rule, "start_age", 0.0F))};
        return ev -> {
            int maxFires = integer(rule, "max_fires", bool(rule, "once", false) ? 1 : 128, 1, 4096);
            if (fires[0] >= maxFires) return;
            if (ev.age() + 0.0001F < nextAge[0]) return;
            if (!eventCondition(rule, ev)) return;
            if (!ev.chance(clamp(decimal(rule, "chance", 1.0F), 0.0F, 1.0F))) return;
            fires[0]++;
            float interval = Math.max(0.0F, decimal(rule, "interval", 0.0F));
            nextAge[0] = ev.age() + interval;
            JsonArray actions = array(rule, "actions");
            for (JsonElement element : actions) {
                if (element == null || !element.isJsonObject()) continue;
                runParticleAction(parent, element.getAsJsonObject(), ev, ctx, region);
            }
        };
    }

    private static boolean eventCondition(JsonObject rule, ParticleEventContext ev) {
        if (ev.bounceCount() < integer(rule, "min_bounces", 0, 0, Integer.MAX_VALUE)) return false;
        if (ev.bounceCount() > integer(rule, "max_bounces", Integer.MAX_VALUE, 0, Integer.MAX_VALUE)) return false;
        if (ev.impactSpeed() < decimal(rule, "min_impact_speed", 0.0F)) return false;
        if (ev.impactSpeed() > decimal(rule, "max_impact_speed", Float.MAX_VALUE)) return false;
        if (ev.age() < decimal(rule, "min_age", 0.0F)) return false;
        if (ev.age() > decimal(rule, "max_age", Float.MAX_VALUE)) return false;
        String kind = string(rule, "collision_kind", "").trim().toUpperCase();
        if (!kind.isBlank() && !ev.kind().name().equals(kind)) return false;
        JsonArray kinds = array(rule, "collision_kinds");
        if (!kinds.isEmpty()) {
            boolean matched = false;
            for (JsonElement element : kinds) {
                if (element != null && element.isJsonPrimitive() && ev.kind().name().equalsIgnoreCase(element.getAsString())) {
                    matched = true; break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    private static void runParticleAction(JsonObject parent, JsonObject action, ParticleEventContext ev, EffectContext ctx, SpawnRegion region) {
        String type = string(action, "type", "emit").trim().toLowerCase();
        switch (type) {
            case "emit", "burst" -> {
                JsonObject particleCfg = emissionConfig(parent, action);
                if (!particleCfg.has("origin")) particleCfg.addProperty("origin", "center");
                if (!particleCfg.has("pattern")) particleCfg.addProperty("pattern", "center_burst");
                String program = string(action, "program", string(particleCfg, "program", "burst")).trim().toLowerCase();
                emitInitial(program.equals("sequence") ? "burst" : program, particleCfg, ctx, SpawnRegion.point(ev.x(), ev.y(), region));
            }
            case "sound" -> {
                String path = string(action, "sound", "").trim();
                if (!path.isBlank()) {
                    ev.playSound(new UiParticleSoundProfile.Cue(SoundEvent.of(identifierWithDefaultNamespace(path)),
                            decimal(action, "volume", 0.18F), decimal(action, "pitch", 1.0F),
                            decimal(action, "jitter", 0.07F), integer(action, "cooldown_ms", 70, 0, 60_000)));
                }
            }
            case "pulse" -> ev.pulse(decimal(action, "duration", 0.22F),
                    color(action.get("color"), color(parent.get("theme_color"), 0xFFFFFF)),
                    decimal(action, "scale", 0.65F), enumValue(Layer.class, string(action, "layer", "back"), Layer.BACK));
            case "kill" -> ev.kill();
            case "add_velocity" -> ev.addVelocity(randomRange(ev, action, "vx", 0.0F, 0.0F), randomRange(ev, action, "vy", 0.0F, 0.0F));
            case "set_velocity" -> ev.velocity(randomRange(ev, action, "vx", ev.vx(), ev.vx()), randomRange(ev, action, "vy", ev.vy(), ev.vy()));
            case "set_gravity" -> ev.gravity(decimal(action, "gravity", 0.0F));
            case "set_drag" -> ev.drag(decimal(action, "drag", 0.98F));
            case "set_restitution" -> ev.restitution(decimal(action, "restitution", 0.5F));
            case "set_behavior" -> ev.behavior(enumValue(Behavior.class, string(action, "behavior", "ballistic"), Behavior.BALLISTIC));
            case "set_material" -> ev.material(enumValue(UiParticleMaterials.Material.class,
                    string(action, "material", "default"), UiParticleMaterials.Material.DEFAULT));
            case "vortex", "vortex_field" -> ev.vortexWell(ev.x(), ev.y(), decimal(action, "radius", 44.0F),
                    decimal(action, "spin", 90.0F), decimal(action, "pull", 34.0F), decimal(action, "lifetime", 0.65F));
            case "wind", "wind_field" -> {
                boolean screen = bool(action, "screen_wide", false);
                float padding = screen ? 0.0F : Math.max(0.0F, decimal(action, "field_padding",
                        Math.max(6.0F, Math.min(region.targetWidth(), region.targetHeight()) * 0.75F)));
                float x = screen ? 0.0F : region.targetX() - padding;
                float y = screen ? 0.0F : region.targetY() - padding;
                float w = screen ? region.screenWidth() : region.targetWidth() + padding * 2.0F;
                float h = screen ? region.screenHeight() : region.targetHeight() + padding * 2.0F;
                ev.windZone(x, y, w, h, decimal(action, "force_x", 24.0F),
                        decimal(action, "force_y", 0.0F), decimal(action, "lifetime", 0.55F));
            }
            case "set_data" -> {
                String key = string(action, "key", "").trim();
                if (!key.isBlank()) ev.data(key, string(action, "value", ""));
            }
            case "increment_data" -> {
                String key = string(action, "key", "counter").trim();
                int value = 0;
                try { String raw = ev.data(key); if (raw != null) value = Integer.parseInt(raw); } catch (RuntimeException ignored) { }
                ev.data(key, value + integer(action, "amount", 1, -1_000_000, 1_000_000));
            }
            case "child_effect" -> {
                String id = string(action, "id", "").trim().toLowerCase();
                UiParticleEffect child = UiParticleRegistry.get(id);
                if (child != null) {
                    SpawnRegion point = SpawnRegion.point(ev.x(), ev.y(), region);
                    ctx.withTarget(point.targetX(), point.targetY(), point.targetWidth(), point.targetHeight(), childCtx -> {
                        if (bool(action, "play_begin_sound", false) && child.beginSound() != null) {
                            childCtx.playSound(child.beginSound());
                        }
                        child.onBegin(childCtx);
                    });
                }
            }
            default -> { }
        }
    }

    private static float randomRange(ParticleEventContext ev, JsonObject cfg, String key, float fallbackMin, float fallbackMax) {
        JsonElement element = cfg.get(key);
        if (element != null && element.isJsonArray() && element.getAsJsonArray().size() >= 2) {
            float a = element.getAsJsonArray().get(0).getAsFloat();
            float b = element.getAsJsonArray().get(1).getAsFloat();
            return ev.random(Math.min(a, b), Math.max(a, b));
        }
        if (element != null && element.isJsonPrimitive()) {
            try { return element.getAsFloat(); } catch (RuntimeException ignored) { }
        }
        return ev.random(Math.min(fallbackMin, fallbackMax), Math.max(fallbackMin, fallbackMax));
    }

    private static JsonObject merged(JsonObject base, JsonObject overlay) {
        JsonObject result = base == null ? new JsonObject() : base.deepCopy();
        if (overlay != null) {
            for (Map.Entry<String, JsonElement> entry : overlay.entrySet()) result.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return result;
    }

    private static JsonObject objectOf(String key, String value) {
        JsonObject object = new JsonObject();
        object.addProperty(key, value);
        return object;
    }

    private static void validateDefinition(JsonObject object) {
        String id = string(object, "id", "").trim().toLowerCase();
        if (id.isBlank()) throw new IllegalArgumentException("Effect id cannot be blank");
        if (!id.matches("[a-z0-9_.]+")) throw new IllegalArgumentException("Effect id must use lowercase dot namespaces and snake_case segments only: " + id);
        if (id.startsWith("minecraft.") || id.contains(":")) throw new IllegalArgumentException("Effect id conflicts with game-particle mirror namespace: " + id);
        String program = string(object, "program", object.has("stages") ? "sequence" : "burst").trim().toLowerCase();
        if (!SUPPORTED_PROGRAMS.contains(program)) throw new IllegalArgumentException("Unsupported particle program: " + program);
        validateParticleConfig(object);
        if (object.has("sound")) identifierWithDefaultNamespace(string(object, "sound", ""));
        if (program.equals("sequence")) {
            JsonArray stages = array(object, "stages");
            if (stages.isEmpty()) throw new IllegalArgumentException("sequence effect requires non-empty stages");
            for (JsonElement element : stages) {
                if (element == null || !element.isJsonObject()) throw new IllegalArgumentException("stage entry must be an object");
                JsonObject stage = element.getAsJsonObject();
                JsonArray actions = array(stage, "actions");
                if (actions.isEmpty()) throw new IllegalArgumentException("stage requires non-empty actions");
                for (JsonElement actionElement : actions) {
                    if (actionElement == null || !actionElement.isJsonObject()) throw new IllegalArgumentException("stage action must be an object");
                    validateAction(actionElement.getAsJsonObject());
                }
            }
        }
        if (object.has("idle")) {
            if (!object.get("idle").isJsonObject()) throw new IllegalArgumentException("idle must be an object");
            JsonObject idle = object.getAsJsonObject("idle");
            JsonArray actions = array(idle, "actions");
            if (actions.isEmpty()) throw new IllegalArgumentException("idle requires non-empty actions");
            for (JsonElement actionElement : actions) {
                if (actionElement == null || !actionElement.isJsonObject()) throw new IllegalArgumentException("idle action must be an object");
                validateAction(actionElement.getAsJsonObject());
            }
        }
    }

    private static void validateAction(JsonObject action) {
        String type = string(action, "type", "emit").trim().toLowerCase();
        Set<String> allowed = Set.of("emit", "burst", "emitter", "sound", "pulse", "wind", "wind_field", "vortex", "vortex_field", "child_effect",
                "block_object", "item_object", "kill", "add_velocity", "set_velocity", "set_gravity", "set_drag", "set_restitution", "set_behavior", "set_material", "set_data", "increment_data",
                "reset_age", "set_age", "set_lifetime", "set_alpha", "set_color", "set_visual_pixels", "set_sprite_scale", "teleport", "cursor_impulse",
                "set_rotation", "add_rotation", "set_angular_velocity", "add_angular_velocity", "apply_force", "set_signal", "add_signal", "toggle_signal");
        if (!allowed.contains(type)) throw new IllegalArgumentException("Unsupported action type: " + type);
        if ((type.equals("emit") || type.equals("burst") || type.equals("emitter"))) {
            JsonObject config = action.has("particle") && action.get("particle").isJsonObject() ? action.getAsJsonObject("particle") : action;
            validateParticleConfig(config);
            String program = string(action, "program", "burst").trim().toLowerCase();
            if (!SUPPORTED_PROGRAMS.contains(program)) throw new IllegalArgumentException("Unsupported emit program: " + program);
        }
        if (type.equals("sound")) identifierWithDefaultNamespace(string(action, "sound", ""));
        if (type.equals("block_object")) {
            if (!action.has("block") && array(action, "blocks").isEmpty()) throw new IllegalArgumentException("block_object requires block or blocks");
            if (action.has("block")) identifierWithDefaultNamespace(string(action, "block", ""));
            for (JsonElement element : array(action, "blocks")) if (element != null && element.isJsonPrimitive()) identifierWithDefaultNamespace(element.getAsString());
            validateObjectMotion(action);
        }
        if (type.equals("item_object")) {
            if (!action.has("item") && array(action, "items").isEmpty()) throw new IllegalArgumentException("item_object requires item or items");
            if (action.has("item")) identifierWithDefaultNamespace(string(action, "item", ""));
            for (JsonElement element : array(action, "items")) if (element != null && element.isJsonPrimitive()) identifierWithDefaultNamespace(element.getAsString());
            validateObjectMotion(action);
        }
        if (type.equals("set_behavior")) validateEnumField(action, "behavior", Behavior.class);
        if (type.equals("set_material")) validateEnumField(action, "material", UiParticleMaterials.Material.class);
        if (action.has("interaction")) validateInteraction(action.get("interaction"));
    }

    private static void validateObjectMotion(JsonObject action) {
        String motion = string(action, "motion", "fixed").trim().toLowerCase();
        if (!Set.of("fixed", "ballistic", "pogo", "recoil", "pendulum", "orbit", "boomerang", "shake", "wave", "launch").contains(motion)) {
            throw new IllegalArgumentException("Unsupported object motion: " + motion);
        }
    }

    private static void validateParticleConfig(JsonObject cfg) {
        validateEnumField(cfg, "shape", Shape.class);
        validateEnumField(cfg, "sprite", SpriteSet.class);
        validateEnumField(cfg, "visual_family", VisualFamily.class);
        validateEnumField(cfg, "material", UiParticleMaterials.Material.class);
        validateEnumField(cfg, "behavior", Behavior.class);
        validateEnumField(cfg, "size_band", SizeBand.class);
        validateEnumField(cfg, "rotation_policy", RotationPolicy.class);
        validateEnumField(cfg, "pixel_snap", PixelSnap.class);
        validateEnumField(cfg, "contact_mode", ParticleContactMode.class);
        if (cfg.has("texture")) identifier(string(cfg, "texture", ""));
        if (cfg.has("block_sound")) identifierWithDefaultNamespace(string(cfg, "block_sound", ""));
        if (cfg.has("events") && cfg.get("events").isJsonObject()) {
            JsonObject events = cfg.getAsJsonObject("events");
            for (String key : List.of("bounce", "expire", "tick")) {
                if (!events.has(key)) continue;
                if (!events.get(key).isJsonObject()) throw new IllegalArgumentException("events." + key + " must be an object");
                JsonArray actions = array(events.getAsJsonObject(key), "actions");
                for (JsonElement action : actions) {
                    if (action == null || !action.isJsonObject()) throw new IllegalArgumentException("events." + key + " action must be an object");
                    validateAction(action.getAsJsonObject());
                }
            }
        }
        if (cfg.has("interaction")) validateInteraction(cfg.get("interaction"));
    }

    private static <T extends Enum<T>> void validateEnumField(JsonObject cfg, String key, Class<T> type) {
        if (!cfg.has(key)) return;
        String raw = string(cfg, key, "").trim();
        if (raw.isBlank()) return;
        try { Enum.valueOf(type, raw.trim().toUpperCase().replace('-', '_').replace(' ', '_')); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid " + key + " value: " + raw); }
    }

    private static void pulseFromConfig(JsonObject cfg, EffectContext ctx) {
        if (!bool(cfg, "pulse", true)) return;
        int color = color(cfg.get("theme_color"), 0xFFFFFF);
        ctx.pulse(0.0F, decimal(cfg, "pulse_duration", 0.28F), color, decimal(cfg, "pulse_scale", 0.75F),
                bool(cfg, "pulse_front", false) ? Layer.FRONT : Layer.BACK);
    }

    private static void addWind(JsonObject cfg, EffectContext ctx) {
        addWind(cfg, ctx, SpawnRegion.capture(ctx));
    }

    private static void addWind(JsonObject cfg, EffectContext ctx, SpawnRegion region) {
        float padding = Math.max(4.0F, decimal(cfg, "field_padding", Math.min(region.targetWidth(), region.targetHeight()) * 0.80F));
        ctx.windZone(region.targetX() - padding, region.targetY() - padding,
                region.targetWidth() + padding * 2.0F, region.targetHeight() + padding * 2.0F,
                decimal(cfg, "wind_x", 32.0F), decimal(cfg, "wind_y", 0.0F), decimal(cfg, "field_life", 1.5F));
    }

    private static void addVortex(JsonObject cfg, EffectContext ctx) {
        addVortex(cfg, ctx, SpawnRegion.capture(ctx));
    }

    private static void addVortex(JsonObject cfg, EffectContext ctx, SpawnRegion region) {
        ctx.vortexWell(region.centerX(), region.centerY(), decimal(cfg, "field_radius", 72.0F), decimal(cfg, "field_spin", 95.0F), decimal(cfg, "field_pull", 36.0F), decimal(cfg, "field_life", 1.5F));
    }

    /**
     * Picks a real registered block and turns it into a compact button toy.
     * The block identity is dynamic, while material behavior stays generic and
     * reusable: slime keeps bouncing until lifetime expiry, honey sticks,
     * falling blocks settle, brittle blocks shatter, and ordinary blocks get a
     * short physical bounce before breaking.
     */
    private static void spawnRegisteredBlockPlay(JsonObject cfg, EffectContext ctx) {
        int count = integerRange(ctx, cfg, "block_count", 3, 1, 6);
        for (int i = 0; i < count; i++) {
            Block block = randomRegisteredBlock(ctx, cfg);
            if (block == null || block == Blocks.AIR) continue;
            Identifier blockId = Registries.BLOCK.getId(block);
            String path = blockId == null ? "" : blockId.getPath();
            UiParticleMaterials.Material material = materialForRegisteredBlock(block, path);

            boolean slime = block == Blocks.SLIME_BLOCK || path.contains("slime");
            boolean honey = block == Blocks.HONEY_BLOCK || path.contains("honey");
            boolean falling = block instanceof FallingBlock || path.contains("sand") || path.contains("gravel")
                    || path.contains("concrete_powder");
            boolean brittle = path.contains("glass") || path.contains("amethyst") || path.contains("ice")
                    || path.contains("crystal");
            boolean leafy = path.contains("leaves") || path.contains("moss") || path.contains("azalea")
                    || path.contains("wart_block") || path.contains("shroomlight");
            boolean wood = path.contains("log") || path.contains("wood") || path.contains("planks")
                    || path.contains("stem") || path.contains("hyphae") || path.contains("bamboo");
            boolean soft = path.contains("wool") || path.contains("sponge") || path.contains("hay_block")
                    || path.contains("mushroom") || leafy;
            boolean metal = path.contains("iron") || path.contains("gold") || path.contains("copper")
                    || path.contains("netherite") || path.contains("anvil") || path.contains("lodestone");
            boolean hot = path.contains("magma") || path.contains("netherrack") || path.contains("nether_brick")
                    || path.contains("blackstone") || path.contains("basalt");
            boolean sculk = path.contains("sculk") || path.contains("reinforced_deepslate");
            boolean ender = path.contains("end_stone") || path.contains("purpur") || path.contains("obsidian");
            boolean tnt = block == Blocks.TNT || path.equals("tnt");

            float x = ctx.centerX() + ctx.random(-5.0F, 5.0F);
            float y = ctx.targetY() - ctx.random(6.0F, 11.0F) - i * 2.1F;
            float life = slime ? ctx.random(6.2F, 8.8F)
                    : honey ? ctx.random(5.2F, 7.2F)
                    : falling ? ctx.random(5.0F, 7.0F)
                    : leafy ? ctx.random(4.8F, 6.8F)
                    : metal ? ctx.random(4.8F, 6.6F)
                    : brittle ? ctx.random(4.2F, 5.8F)
                    : ctx.random(4.8F, 7.2F);
            int breakAfter = brittle ? 2 + ctx.randomInt(2) : metal ? 3 + ctx.randomInt(2)
                    : wood ? 3 + ctx.randomInt(2) : 3 + ctx.randomInt(3);
            final int[] floorBounces = {0};

            ParticleBuilder builder = ctx.particle(Shape.BLOCK_SHARD, x, y)
                    .blockIcon(block)
                    .material(material)
                    .visualPixels(ctx.random(10.0F, 14.0F))
                    .lifetime(life)
                    .layer(ctx.chance(0.78F) ? Layer.FRONT : Layer.BACK)
                    .velocity(ctx.random(-38.0F, 38.0F), ctx.random(-88.0F, -46.0F))
                    .gravity(slime ? 150.0F : leafy ? 64.0F : falling ? 205.0F : metal ? 188.0F : 172.0F)
                    .drag(slime ? 0.996F : leafy ? 0.974F : 0.989F)
                    .restitution(slime ? 0.95F : soft ? 0.54F : brittle ? 0.30F : metal ? 0.70F : 0.64F)
                    .surfaceFriction(path.contains("ice") ? 0.988F : honey ? 0.28F : leafy ? 0.78F : 0.86F)
                    .collideTarget(true)
                    .collideButtons(true)
                    .collideScreen(true)
                    .bounceCeiling(false)
                    .protectTarget(false)
                    .rotationPolicy(RotationPolicy.QUARTER_TURN)
                    .angularVelocity(ctx.random(-185.0F, 185.0F))
                    .soundProfile(GameSpriteSoundResolver.blockProfile(block));

            if (falling) builder.settleOnSurfaces(true);
            if (honey) builder.stickToButtons(ctx.random(0.48F, 0.92F));
            if (leafy) builder.behavior(Behavior.FLUTTER).rotationPolicy(RotationPolicy.FREE)
                    .angularVelocity(ctx.random(-80.0F, 80.0F));

            final int accentColor = pickColor(cfg, ctx, color(cfg.get("theme_color"), 0xD7D7D7));
            final float[] nextAmbient = {0.0F};
            if (hot || sculk || ender || leafy || slime || honey) {
                builder.onTick(ev -> {
                    if (ev.age() < nextAmbient[0]) return;
                    nextAmbient[0] = ev.age() + (slime ? 0.16F : 0.13F);
                    Shape accentShape = sculk ? Shape.SCULK : ender ? Shape.STAR
                            : leafy ? Shape.LEAF : honey ? Shape.DROP : hot ? Shape.FLAME : Shape.SLIME;
                    SpriteSet accentSprite = sculk ? SpriteSet.SCULK_CHARGE : ender ? SpriteSet.PORTAL
                            : leafy ? SpriteSet.CHERRY : honey ? SpriteSet.DRIP_FALL
                            : hot ? SpriteSet.FLAME : SpriteSet.GENERIC;
                    ev.spawn(ev.particle(accentShape)
                            .primitive(false)
                            .sprite(accentSprite)
                            .color(accentColor)
                            .visualPixels(ev.random(2.3F, 4.2F))
                            .lifetime(ev.random(0.42F, 0.82F))
                            .velocity(ev.random(-5.0F, 5.0F), ev.random(-8.0F, 4.0F))
                            .gravity(hot ? -10.0F : leafy ? 10.0F : 4.0F)
                            .drag(0.96F)
                            .alpha(0.72F)
                            .collideButtons(false)
                            .collideScreen(false)
                            .layer(Layer.BACK));
                });
            }

            if (slime) {
                // Slime blocks are explicitly toys. They never shatter on bounce;
                // they keep rebounding until their normal particle lifetime expires.
                builder.onBounce(ev -> {
                    if (ev.bounceCount() % 2 == 0) {
                        ev.playSound(SoundEvent.of(identifierWithDefaultNamespace("block.slime_block.hit")),
                                0.055F, ev.random(1.12F, 1.35F));
                        for (int n = 0; n < 2; n++) {
                            ev.spawn(ev.particle(Shape.SLIME)
                                    .primitive(false)
                                    .sprite(SpriteSet.GENERIC)
                                    .material(UiParticleMaterials.Material.SLIME)
                                    .color(accentColor)
                                    .visualPixels(ev.random(2.8F, 4.8F))
                                    .lifetime(ev.random(0.45F, 0.88F))
                                    .velocity(ev.random(-10.0F, 10.0F), ev.random(-15.0F, -4.0F))
                                    .gravity(90.0F)
                                    .drag(0.98F)
                                    .restitution(0.70F)
                                    .collideButtons(false)
                                    .collideScreen(false)
                                    .layer(Layer.FRONT));
                        }
                    }
                });
            } else if (honey || falling || leafy || soft) {
                // Sticky, granular and soft blocks resolve through settling/expiry
                // rather than an arbitrary brittle break.
            } else if (tnt) {
                builder.onExpire(ev -> {
                    ev.playSound(SoundEvent.of(identifierWithDefaultNamespace("entity.generic.explode")),
                            0.24F, ev.random(0.94F, 1.08F));
                    ev.pulse(0.22F, color(cfg.get("theme_color"), 0xFFB04A), 0.68F, Layer.BACK);
                    for (int n = 0; n < 12; n++) {
                        double a = Math.PI * 2.0 * n / 12.0 + ev.random(-0.12F, 0.12F);
                        float speed = ev.random(18.0F, 42.0F);
                        ev.spawn(ev.particle(n % 4 == 0 ? Shape.FLAME : Shape.SPARK)
                                .primitive(false)
                                .sprite(n % 4 == 0 ? SpriteSet.FLAME : SpriteSet.FIREWORK)
                                .color(n % 3 == 0 ? 0xFFF2A8 : 0xE8662C)
                                .visualPixels(ev.random(2.0F, 4.2F))
                                .lifetime(ev.random(0.26F, 0.56F))
                                .velocity((float)Math.cos(a) * speed, (float)Math.sin(a) * speed)
                                .gravity(30.0F)
                                .drag(0.97F)
                                .collideButtons(false)
                                .collideScreen(false)
                                .layer(n % 4 == 0 ? Layer.FRONT : Layer.BACK));
                    }
                });
            } else {
                builder.onBounce(ev -> {
                    // The block party should have time to travel. Button/target contacts
                    // never cause the early brittle shatter that made the old party die
                    // before users could watch it reach the HUD floor.
                    if (ev.kind() == CollisionKind.BUTTON || ev.kind() == CollisionKind.TARGET) return;
                    if (ev.kind() != CollisionKind.SCREEN || ev.normalY() > -0.5F) return;
                    floorBounces[0]++;
                    if (floorBounces[0] < breakAfter || ev.impactSpeed() <= 11.0F) return;
                    ev.playBlockBreak(block);
                    int chips = brittle ? 7 : metal ? 4 : wood ? 5 : 6;
                    for (int n = 0; n < chips; n++) {
                        ParticleBuilder chip = ev.particle(Shape.BLOCK_SHARD)
                                .blockIcon(block)
                                .material(material)
                                .visualPixels(ev.random(3.2F, 5.4F))
                                .lifetime(ev.random(0.70F, 1.35F))
                                .velocity(ev.random(-26.0F, 26.0F), ev.random(-30.0F, 8.0F))
                                .gravity(metal ? 150.0F : 98.0F)
                                .drag(0.982F)
                                .collideButtons(false)
                                .collideScreen(true)
                                .bounceCeiling(false)
                                .restitution(0.34F)
                                .rotationPolicy(RotationPolicy.FREE)
                                .angularVelocity(ev.random(-260.0F, 260.0F))
                                .layer(n == 0 ? Layer.FRONT : Layer.BACK);
                        ev.spawn(chip);
                    }
                    if (metal || brittle) {
                        int accents = metal ? 4 : 3;
                        for (int n = 0; n < accents; n++) {
                            ev.spawn(ev.particle(Shape.SPARK)
                                    .primitive(false)
                                    .sprite(SpriteSet.GLITTER)
                                    .color(accentColor)
                                    .visualPixels(ev.random(2.4F, 4.0F))
                                    .lifetime(ev.random(0.42F, 0.78F))
                                    .velocity(ev.random(-18.0F, 18.0F), ev.random(-20.0F, 4.0F))
                                    .gravity(22.0F)
                                    .drag(0.96F)
                                    .collideButtons(false)
                                    .collideScreen(false)
                                    .layer(Layer.FRONT));
                        }
                    }
                    ev.kill();
                });
            }
            configureInteraction(cfg, cfg, builder, SpawnRegion.capture(ctx));
            ctx.spawn(builder);
        }
    }

    private static Block randomRegisteredBlock(EffectContext ctx, JsonObject cfg) {
        JsonArray authored = array(cfg, "blocks");
        if (!authored.isEmpty()) {
            for (int attempt = 0; attempt < authored.size(); attempt++) {
                JsonElement element = authored.get(ctx.randomInt(authored.size()));
                if (element == null || !element.isJsonPrimitive()) continue;
                try {
                    Block block = Registries.BLOCK.get(identifierWithDefaultNamespace(element.getAsString()));
                    if (block != null && block != Blocks.AIR) return block;
                } catch (RuntimeException ignored) { }
            }
        }

        List<Block> cache = registeredBlockCache;
        if (cache == null || cache.isEmpty()) {
            synchronized (UiParticleJsonLoader.class) {
                cache = registeredBlockCache;
                if (cache == null || cache.isEmpty()) {
                    cache = Registries.BLOCK.stream().filter(block -> block != null && block != Blocks.AIR).toList();
                    registeredBlockCache = cache;
                }
            }
        }
        if (cache == null || cache.isEmpty()) return Blocks.STONE;

        // Prefer blocks with inventory representations so resource packs and
        // modded block models render correctly as the particle icon.
        Block fallback = cache.get(ctx.randomInt(cache.size()));
        for (int attempt = 0; attempt < 12; attempt++) {
            Block candidate = cache.get(ctx.randomInt(cache.size()));
            if (candidate != null && candidate != Blocks.AIR && !candidate.asItem().getDefaultStack().isEmpty()) return candidate;
        }
        return fallback;
    }

    private static UiParticleMaterials.Material materialForRegisteredBlock(Block block, String path) {
        if (block == Blocks.SLIME_BLOCK || path.contains("slime")) return UiParticleMaterials.Material.SLIME;
        if (block == Blocks.HONEY_BLOCK || path.contains("honey")) return UiParticleMaterials.Material.HONEY;
        if (block instanceof FallingBlock || path.contains("sand") || path.contains("gravel") || path.contains("concrete_powder")) {
            return UiParticleMaterials.Material.SAND;
        }
        if (path.contains("snow")) return UiParticleMaterials.Material.SNOW;
        if (path.contains("amethyst") || path.contains("glass") || path.contains("ice")) return UiParticleMaterials.Material.AMETHYST;
        if (path.contains("sculk") || path.contains("end_stone") || path.contains("purpur") || path.contains("obsidian")) {
            return UiParticleMaterials.Material.PORTAL;
        }
        if (path.contains("redstone")) return UiParticleMaterials.Material.REDSTONE;
        return UiParticleMaterials.Material.BLOCK;
    }

    /**
     * Throws real registered non-block items out of the button as flat 2D
     * resource-pack-aware sprites. The default count is deliberately 3-6 so the
     * effect reads as an item handful instead of one lonely icon.
     */
    private static void spawnRegisteredItemParty(JsonObject cfg, EffectContext ctx) {
        int count = integerRange(ctx, cfg, "item_count", 4, 3, 6);
        float motionScale = clamp(ctx.visualScale(), 0.65F, 2.75F);
        for (int i = 0; i < count; i++) {
            Item item = randomRegisteredItem(ctx, cfg);
            if (item == null || item == Items.AIR) continue;
            ItemSoundProfile itemSounds = itemSoundProfile(item);
            playItemSound(ctx, itemSounds.launch());

            float x = ctx.centerX() + ctx.random(-ctx.targetWidth() * 0.28F, ctx.targetWidth() * 0.28F);
            float y = ctx.targetY() + ctx.targetHeight() * ctx.random(0.15F, 0.55F);
            int accent = pickColor(cfg, ctx, color(cfg.get("theme_color"), 0xFFF1A8));
            float[] nextGlint = {0.0F};

            ParticleBuilder builder = ctx.particle(Shape.PIXEL, x, y)
                    .itemIcon(item)
                    .visualPixels(ctx.random(9.5F, 13.5F))
                    .lifetime(ctx.random(5.0F, 7.6F))
                    .layer(ctx.chance(0.82F) ? Layer.FRONT : Layer.BACK)
                    .velocity(ctx.random(-42.0F, 42.0F) * Math.min(1.35F, motionScale),
                            ctx.random(-96.0F, -54.0F) * Math.min(1.28F, motionScale))
                    .gravity(ctx.random(158.0F, 186.0F))
                    .drag(0.993F)
                    .restitution(ctx.random(0.58F, 0.76F))
                    .surfaceFriction(0.84F)
                    .collideTarget(true)
                    .collideButtons(true)
                    .collideScreen(true)
                    .bounceCeiling(false)
                    .protectTarget(false)
                    .rotationPolicy(RotationPolicy.FREE)
                    .angularVelocity(ctx.random(-240.0F, 240.0F))
                    .onTick(ev -> {
                        if (ev.age() < nextGlint[0]) return;
                        nextGlint[0] = ev.age() + ev.random(0.22F, 0.38F);
                        if (!ev.chance(0.68F)) return;
                        ev.spawn(ev.particle(ev.chance(0.28F) ? Shape.STAR : Shape.SPARK)
                                .primitive(false)
                                .sprite(ev.chance(0.34F) ? SpriteSet.GLITTER : SpriteSet.FIREWORK)
                                .color(accent)
                                .visualPixels(ev.random(2.1F, 3.8F))
                                .lifetime(ev.random(0.34F, 0.68F))
                                .velocity(ev.random(-5.0F, 5.0F), ev.random(-8.0F, 3.0F))
                                .gravity(8.0F)
                                .drag(0.965F)
                                .alpha(0.74F)
                                .collideButtons(false)
                                .collideScreen(false)
                                .layer(Layer.BACK));
                    })
                    .onBounce(ev -> {
                        if (ev.kind() == CollisionKind.SCREEN && ev.normalY() < -0.5F && ev.bounceCount() <= 3) {
                            playItemSound(ev, itemSounds.impact(), 0.78F);
                            for (int n = 0; n < 2; n++) {
                                ev.spawn(ev.particle(Shape.SPARK)
                                        .primitive(false)
                                        .sprite(SpriteSet.GLITTER)
                                        .color(accent)
                                        .visualPixels(ev.random(2.0F, 3.5F))
                                        .lifetime(ev.random(0.26F, 0.52F))
                                        .velocity(ev.random(-12.0F, 12.0F), ev.random(-14.0F, -4.0F))
                                        .gravity(24.0F)
                                        .drag(0.966F)
                                        .collideButtons(false)
                                        .collideScreen(false)
                                        .layer(Layer.FRONT));
                            }
                        }
                    })
                    .onExpire(ev -> {
                        if (ev.chance(0.82F)) playItemSound(ev, itemSounds.finish(), 0.72F);
                        for (int n = 0; n < 3; n++) {
                            ev.spawn(ev.particle(n == 0 ? Shape.STAR : Shape.SPARK)
                                    .primitive(false)
                                    .sprite(n == 0 ? SpriteSet.GLITTER : SpriteSet.FIREWORK)
                                    .color(accent)
                                    .visualPixels(ev.random(2.1F, 4.0F))
                                    .lifetime(ev.random(0.32F, 0.64F))
                                    .velocity(ev.random(-10.0F, 10.0F), ev.random(-12.0F, 2.0F))
                                    .gravity(16.0F)
                                    .drag(0.965F)
                                    .collideButtons(false)
                                    .collideScreen(false)
                                    .layer(n == 0 ? Layer.FRONT : Layer.BACK));
                        }
                    });
            configureInteraction(cfg, cfg, builder, SpawnRegion.capture(ctx));
            ctx.spawn(builder);
        }
    }

    private static ItemSoundProfile itemSoundProfile(Item item) {
        Identifier id = Registries.ITEM.getId(item);
        String path = id == null ? "" : id.getPath().toLowerCase();

        if (containsAny(path, "trident")) {
            return itemSounds("item.trident.throw", "item.trident.hit_ground", "item.trident.return", 0.070F, 0.060F, 0.055F, 0.92F, 1.12F);
        }
        if (containsAny(path, "crossbow")) {
            return itemSounds("item.crossbow.loading_end", "block.wood.hit", "item.crossbow.quick_charge_3", 0.060F, 0.046F, 0.048F, 1.02F, 1.24F);
        }
        if (containsAny(path, "bow")) {
            return itemSounds("entity.arrow.shoot", "block.wood.hit", "block.note_block.hat", 0.055F, 0.044F, 0.036F, 1.00F, 1.22F);
        }
        if (containsAny(path, "fishing_rod")) {
            return itemSounds("entity.fishing_bobber.throw", "entity.fishing_bobber.splash", "entity.fishing_bobber.retrieve", 0.060F, 0.052F, 0.050F, 0.96F, 1.16F);
        }
        if (containsAny(path, "firework_rocket", "firework_star")) {
            return itemSounds("entity.firework_rocket.launch", "entity.firework_rocket.blast", "entity.firework_rocket.twinkle", 0.055F, 0.050F, 0.040F, 1.08F, 1.34F);
        }
        if (containsAny(path, "ender_pearl", "ender_eye", "eye_of_ender", "chorus_fruit")) {
            return itemSounds("entity.ender_pearl.throw", "block.amethyst_block.hit", "entity.enderman.teleport", 0.055F, 0.042F, 0.052F, 1.00F, 1.24F);
        }
        if (containsAny(path, "totem_of_undying", "totem")) {
            return itemSounds("block.amethyst_block.chime", "block.wood.hit", "item.totem.use", 0.050F, 0.038F, 0.060F, 1.04F, 1.24F);
        }
        if (containsAny(path, "goat_horn", "horn")) {
            return itemSounds("item.goat_horn.sound.0", "block.wood.hit", "item.goat_horn.sound.1", 0.050F, 0.038F, 0.040F, 1.00F, 1.08F);
        }
        if (containsAny(path, "music_disc", "disc_fragment")) {
            return itemSounds("block.note_block.pling", "block.note_block.hat", "block.note_block.chime", 0.045F, 0.034F, 0.038F, 0.96F, 1.18F);
        }
        if (containsAny(path, "potion", "honey_bottle")) {
            return itemSounds("entity.generic.drink", "block.glass.hit", "block.brewing_stand.brew", 0.048F, 0.040F, 0.036F, 1.02F, 1.24F);
        }
        if (containsAny(path, "glass_bottle", "dragon_breath")) {
            return itemSounds("item.bottle.fill", "block.glass.hit", "item.bottle.empty", 0.046F, 0.040F, 0.036F, 1.02F, 1.24F);
        }
        if (containsAny(path, "bucket")) {
            return itemSounds("item.bucket.empty", "block.metal.hit", "item.bucket.fill", 0.052F, 0.044F, 0.040F, 0.94F, 1.12F);
        }
        if (containsAny(path, "shears")) {
            return itemSounds("entity.sheep.shear", "block.metal.hit", "entity.sheep.shear", 0.050F, 0.042F, 0.034F, 1.02F, 1.22F);
        }
        if (containsAny(path, "flint_and_steel", "fire_charge")) {
            return itemSounds("item.flintandsteel.use", "block.metal.hit", "block.fire.ambient", 0.052F, 0.040F, 0.036F, 1.00F, 1.18F);
        }
        if (containsAny(path, "snowball")) {
            return itemSounds("entity.snowball.throw", "block.snow.hit", "block.snow.break", 0.046F, 0.038F, 0.032F, 1.02F, 1.26F);
        }
        if (containsAny(path, "egg")) {
            return itemSounds("entity.egg.throw", "block.wool.hit", "entity.chicken.egg", 0.046F, 0.034F, 0.034F, 1.04F, 1.28F);
        }
        if (containsAny(path, "book", "map", "paper", "name_tag", "written", "writable")) {
            return itemSounds("item.book.page_turn", "block.wood.hit", "item.book.page_turn", 0.040F, 0.032F, 0.030F, 1.02F, 1.28F);
        }
        if (containsAny(path, "elytra")) {
            return itemSounds("item.armor.equip_elytra", "block.wool.hit", "item.armor.equip_elytra", 0.048F, 0.032F, 0.034F, 0.98F, 1.16F);
        }
        if (containsAny(path, "helmet", "chestplate", "leggings", "boots")) {
            String equip = armorEquipSound(path);
            return itemSounds(equip, "block.metal.hit", equip, 0.046F, 0.038F, 0.032F, 0.96F, 1.14F);
        }
        if (containsAny(path, "sword")) {
            return itemSounds("entity.player.attack.sweep", "block.metal.hit", "entity.player.attack.strong", 0.050F, 0.040F, 0.034F, 1.00F, 1.18F);
        }
        if (containsAny(path, "pickaxe", "axe", "shovel", "hoe")) {
            return itemSounds("block.anvil.hit", "block.stone.hit", "block.grindstone.use", 0.040F, 0.038F, 0.032F, 1.02F, 1.22F);
        }
        if (containsAny(path, "diamond", "emerald", "amethyst", "nether_star", "echo_shard", "prismarine_crystals")) {
            return itemSounds("block.amethyst_block.chime", "block.amethyst_block.hit", "block.amethyst_cluster.step", 0.044F, 0.036F, 0.034F, 1.02F, 1.28F);
        }
        if (containsAny(path, "iron_ingot", "gold_ingot", "copper_ingot", "netherite_ingot", "nugget", "chain", "minecart")) {
            return itemSounds("block.metal.hit", "block.metal.hit", "block.anvil.hit", 0.040F, 0.036F, 0.032F, 1.00F, 1.18F);
        }
        if (containsAny(path, "apple", "bread", "carrot", "potato", "beetroot", "cookie", "melon", "berry", "beef", "pork", "chicken", "mutton", "rabbit", "cod", "salmon", "stew", "soup", "pie")) {
            return itemSounds("entity.generic.eat", "block.wool.hit", "entity.generic.eat", 0.038F, 0.030F, 0.028F, 1.04F, 1.28F);
        }
        if (containsAny(path, "seed", "sapling", "flower", "wheat", "sugar_cane", "bamboo", "kelp", "cocoa", "fungus", "roots", "vine")) {
            return itemSounds("block.grass.hit", "block.grass.hit", "block.grass.place", 0.034F, 0.030F, 0.028F, 1.02F, 1.24F);
        }
        if (containsAny(path, "leather", "saddle", "lead")) {
            return itemSounds("item.armor.equip_leather", "block.wool.hit", "item.armor.equip_leather", 0.038F, 0.030F, 0.028F, 1.00F, 1.20F);
        }
        if (containsAny(path, "blaze_rod", "blaze_powder", "magma_cream")) {
            return itemSounds("entity.blaze.shoot", "block.nether_bricks.hit", "block.fire.ambient", 0.044F, 0.034F, 0.032F, 1.04F, 1.24F);
        }
        if (containsAny(path, "experience_bottle")) {
            return itemSounds("entity.experience_bottle.throw", "block.glass.hit", "entity.experience_orb.pickup", 0.046F, 0.036F, 0.038F, 1.04F, 1.28F);
        }
        return itemSounds("entity.item.pickup", "entity.item.pickup", "entity.item.pickup", 0.040F, 0.030F, 0.026F, 1.08F, 1.34F);
    }

    private static String armorEquipSound(String path) {
        if (path.contains("netherite")) return "item.armor.equip_netherite";
        if (path.contains("diamond")) return "item.armor.equip_diamond";
        if (path.contains("gold")) return "item.armor.equip_gold";
        if (path.contains("chain")) return "item.armor.equip_chain";
        if (path.contains("leather")) return "item.armor.equip_leather";
        if (path.contains("turtle")) return "item.armor.equip_turtle";
        return "item.armor.equip_iron";
    }

    private static boolean containsAny(String value, String... tokens) {
        if (value == null || value.isBlank() || tokens == null) return false;
        for (String token : tokens) {
            if (token != null && !token.isBlank() && value.contains(token)) return true;
        }
        return false;
    }

    private static ItemSoundProfile itemSounds(String launch, String impact, String finish,
                                               float launchVolume, float impactVolume, float finishVolume,
                                               float minPitch, float maxPitch) {
        return new ItemSoundProfile(
                new ItemSoundCue(launch, launchVolume, minPitch, maxPitch),
                new ItemSoundCue(impact, impactVolume, Math.max(0.72F, minPitch - 0.08F), Math.max(minPitch, maxPitch - 0.06F)),
                new ItemSoundCue(finish, finishVolume, minPitch, maxPitch)
        );
    }

    private static void playItemSound(EffectContext ctx, ItemSoundCue cue) {
        if (ctx == null || cue == null || cue.sound().isBlank() || cue.volume() <= 0.0F) return;
        ctx.playSound(SoundEvent.of(identifierWithDefaultNamespace(cue.sound())), cue.volume(), ctx.random(cue.minPitch(), cue.maxPitch()));
    }

    private static void playItemSound(ParticleEventContext ev, ItemSoundCue cue, float volumeScale) {
        if (ev == null || cue == null || cue.sound().isBlank() || cue.volume() <= 0.0F) return;
        ev.playSound(SoundEvent.of(identifierWithDefaultNamespace(cue.sound())), cue.volume() * Math.max(0.0F, volumeScale),
                ev.random(cue.minPitch(), cue.maxPitch()));
    }

    private record ItemSoundCue(String sound, float volume, float minPitch, float maxPitch) { }
    private record ItemSoundProfile(ItemSoundCue launch, ItemSoundCue impact, ItemSoundCue finish) { }

    private static Item randomRegisteredItem(EffectContext ctx, JsonObject cfg) {
        JsonArray authored = array(cfg, "items");
        if (!authored.isEmpty()) {
            for (int attempt = 0; attempt < authored.size(); attempt++) {
                JsonElement element = authored.get(ctx.randomInt(authored.size()));
                if (element == null || !element.isJsonPrimitive()) continue;
                try {
                    Item item = Registries.ITEM.get(identifierWithDefaultNamespace(element.getAsString()));
                    if (item != null && item != Items.AIR && !(item instanceof BlockItem)) return item;
                } catch (RuntimeException ignored) { }
            }
        }

        List<Item> cache = registeredItemCache;
        if (cache == null || cache.isEmpty()) {
            synchronized (UiParticleJsonLoader.class) {
                cache = registeredItemCache;
                if (cache == null || cache.isEmpty()) {
                    cache = Registries.ITEM.stream()
                            .filter(item -> item != null && item != Items.AIR && !(item instanceof BlockItem))
                            .toList();
                    registeredItemCache = cache;
                }
            }
        }
        if (cache == null || cache.isEmpty()) return Items.DIAMOND;
        return cache.get(ctx.randomInt(cache.size()));
    }

    /**
     * A compact button-scale interpretation of Minecraft firework stars. It
     * supports the five normal explosion shapes plus randomized trail, twinkle,
     * color and fade accents without flooding a 20x20 control with hundreds of
     * particles.
     */
    private static void spawnButtonFirework(JsonObject cfg, EffectContext ctx) {
        int stars = integerRange(ctx, cfg, "star_count", 3, 1, 6);
        for (int i = 0; i < stars; i++) {
            final int index = i;
            float delay = i * ctx.random(0.075F, 0.145F);
            ctx.schedule(delay, scheduled -> {
                int color = pickColor(cfg, scheduled, 0xFFE56F);
                float showScale = clamp(scheduled.visualScale(), 0.50F, 4.0F);
                float spreadScale = (float) Math.sqrt(showScale);
                float lane = stars <= 1 ? 0.0F : (index / (float) (stars - 1) - 0.5F);
                float x = scheduled.centerX() + lane * Math.min(11.0F * spreadScale, scheduled.targetWidth() * 0.48F)
                        + scheduled.random(-2.6F, 2.6F) * spreadScale;
                float y = scheduled.targetY() + scheduled.targetHeight() * scheduled.random(0.70F, 0.96F);
                float launchVx = (lane * scheduled.random(6.0F, 13.0F) + scheduled.random(-3.5F, 3.5F)) * spreadScale;
                float launchVy = scheduled.random(-64.0F, -44.0F) * Math.min(1.55F, spreadScale);
                float[] nextTrail = {0.0F};
                int[] trailStep = {0};

                scheduled.playSound(SoundEvent.of(identifierWithDefaultNamespace("entity.firework_rocket.launch")),
                        0.10F, scheduled.random(1.10F, 1.34F));
                scheduled.spawn(scheduled.particle(Shape.STREAK, x, y)
                        .primitive(false)
                        .sprite(SpriteSet.FIREWORK)
                        .material(UiParticleMaterials.Material.FIREWORK)
                        .color(color)
                        .visualPixels(scheduled.random(4.4F, 6.8F))
                        .lifetime(scheduled.random(0.46F, 0.76F))
                        .velocity(launchVx, launchVy)
                        .gravity(-2.0F)
                        .drag(0.993F)
                        .stretchWithVelocity(true)
                        .collideButtons(false)
                        .collideScreen(false)
                        .protectTarget(false)
                        .layer(Layer.FRONT)
                        .rotationPolicy(RotationPolicy.FACE_VELOCITY)
                        .onTick(ev -> {
                            if (ev.age() < nextTrail[0]) return;
                            nextTrail[0] = ev.age() + 0.052F;
                            trailStep[0]++;
                            ev.spawn(ev.particle(Shape.SPARK)
                                    .primitive(false)
                                    .sprite(SpriteSet.FIREWORK)
                                    .material(UiParticleMaterials.Material.FIREWORK)
                                    .color(trailStep[0] % 3 == 0 ? pickColor(cfg, ev, color) : color)
                                    .visualPixels(ev.random(2.5F, 4.2F))
                                    .lifetime(ev.random(0.34F, 0.68F))
                                    .velocity(ev.random(-4.0F, 4.0F), ev.random(3.0F, 10.0F))
                                    .gravity(10.0F)
                                    .drag(0.965F)
                                    .collideButtons(false)
                                    .collideScreen(false)
                                    .layer(trailStep[0] % 4 == 0 ? Layer.FRONT : Layer.BACK));
                            if (trailStep[0] % 2 == 0) {
                                ev.spawn(ev.particle(Shape.SMOKE)
                                        .primitive(false)
                                        .sprite(SpriteSet.BIG_SMOKE)
                                        .material(UiParticleMaterials.Material.SMOKE)
                                        .color(0xD8D8D8)
                                        .visualPixels(ev.random(3.0F, 4.8F))
                                        .lifetime(ev.random(0.42F, 0.78F))
                                        .velocity(ev.random(-2.5F, 2.5F), ev.random(2.0F, 6.0F))
                                        .gravity(-5.0F)
                                        .drag(0.95F)
                                        .alpha(0.34F)
                                        .collideButtons(false)
                                        .collideScreen(false)
                                        .layer(Layer.BACK));
                            }
                        })
                        .onExpire(ev -> buttonFireworkDetonation(ev, cfg, color, index)));
            });
        }
    }

    private static void buttonFireworkDetonation(ParticleEventContext ev, JsonObject cfg, int primary, int starIndex) {
        String baseShape = pickFireworkShape(cfg, ev, string(cfg, "firework_shape", "random"));
        int layers = integer(cfg, "explosion_layers", 2, 1, 3);
        boolean mixShapes = bool(cfg, "mix_shapes", true);
        boolean trail = bool(cfg, "trail", ev.chance(decimal(cfg, "trail_chance", 0.54F)));
        boolean twinkle = bool(cfg, "twinkle", ev.chance(decimal(cfg, "twinkle_chance", 0.44F)));
        float triggerScale = clamp(ev.visualScale(), 0.50F, 4.0F);
        float motionScale = clamp((float) Math.sqrt(triggerScale), 0.72F, 2.0F);
        float density = clamp(decimal(cfg, "spark_density", 1.18F), 0.65F, 1.90F);

        for (int layer = 0; layer < layers; layer++) {
            final int layerIndex = layer;
            final String shape = layer == 0 || !mixShapes
                    ? baseShape
                    : pickFireworkShape(cfg, ev, "random");
            final int layerPrimary = layer == 0 ? primary : pickColor(cfg, ev, primary);
            final int layerFade = pickColor(cfg, ev, layerPrimary);
            final float layerScale = motionScale * (1.12F + layer * 0.20F);
            final boolean layerTrail = trail && (layer == 0 || ev.chance(0.70F));
            final boolean layerTwinkle = twinkle && (layer == layers - 1 || ev.chance(0.65F));

            if (layer == 0) {
                buttonFireworkLayer(ev, cfg, shape, layerPrimary, layerFade,
                        layerTrail, layerTwinkle, density, layerScale, true);
            } else {
                float delay = 0.085F + layer * 0.075F;
                ev.spawn(ev.particle(Shape.PIXEL)
                        .primitive(false)
                        .color(layerPrimary)
                        .visualPixels(0.7F)
                        .lifetime(delay)
                        .velocity(0.0F, 0.0F)
                        .gravity(0.0F)
                        .drag(1.0F)
                        .alpha(0.0F)
                        .collideButtons(false)
                        .collideScreen(false)
                        .layer(Layer.BACK)
                        .onExpire(done -> buttonFireworkLayer(done, cfg, shape, layerPrimary, layerFade,
                                layerTrail, layerTwinkle, density, layerScale, layerIndex == 1)));
            }
        }

        int afterglow = integer(cfg, "afterglow_count", 8, 3, 18);
        for (int i = 0; i < afterglow; i++) {
            int glowColor = i % 2 == 0 ? primary : pickColor(cfg, ev, primary);
            double angle = ev.random(0.0F, (float) (Math.PI * 2.0));
            float speed = ev.random(5.0F, 16.0F) * motionScale;
            ev.spawn(ev.particle(i % 3 == 0 ? Shape.STAR : Shape.SPARK)
                    .primitive(false)
                    .sprite(i % 3 == 0 ? SpriteSet.GLITTER : SpriteSet.FIREWORK)
                    .material(UiParticleMaterials.Material.FIREWORK)
                    .color(glowColor)
                    .visualPixels(ev.random(2.8F, 5.0F))
                    .lifetime(ev.random(0.82F, 1.55F))
                    .velocity((float)Math.cos(angle) * speed, (float)Math.sin(angle) * speed - ev.random(0.0F, 5.0F))
                    .gravity(ev.random(5.0F, 14.0F))
                    .drag(0.968F)
                    .alpha(0.78F)
                    .collideButtons(false)
                    .collideScreen(false)
                    .layer(i % 5 == 0 ? Layer.FRONT : Layer.BACK));
        }
    }

    private static void buttonFireworkLayer(ParticleEventContext ev, JsonObject cfg, String shape,
                                            int primary, int fade, boolean trail, boolean twinkle,
                                            float density, float scale, boolean playBlast) {
        int baseCount = switch (shape) {
            case "large_ball" -> 34;
            case "burst" -> 22;
            case "creeper" -> 26;
            case "star" -> 25;
            default -> 24;
        };
        int count = Math.max(8, Math.round(baseCount * density));

        if (playBlast) {
            ev.playSound(SoundEvent.of(identifierWithDefaultNamespace(shape.equals("large_ball")
                            ? "entity.firework_rocket.large_blast" : "entity.firework_rocket.blast")),
                    shape.equals("large_ball") ? 0.22F : 0.16F, ev.random(1.04F, 1.26F));
            ev.pulse(0.17F, primary, shape.equals("large_ball") ? 0.74F : 0.58F, Layer.BACK);
        }

        if (shape.equals("star")) {
            for (int i = 0; i < count; i++) {
                double arm = Math.PI * 2.0 * i / count - Math.PI / 2.0;
                double r = i % 2 == 0 ? 1.0 : 0.43;
                float speed = (float) r * ev.random(34.0F, 54.0F) * scale;
                spawnButtonFireworkSpark(ev, i < count / 2 ? primary : fade,
                        (float)Math.cos(arm) * speed, (float)Math.sin(arm) * speed,
                        trail, twinkle && i % 5 == 0, i == 0 && twinkle);
            }
        } else if (shape.equals("creeper")) {
            int[][] points = {
                    {-2,-2},{-1,-2},{1,-2},{2,-2}, {-2,-1},{-1,-1},{1,-1},{2,-1},
                    {-1,0},{0,0}, {-2,1},{-1,1},{0,1},{1,1}, {-2,2},{1,2},
                    {-1,2},{0,2}
            };
            for (int i = 0; i < points.length; i++) {
                float vx = points[i][0] * ev.random(8.4F, 11.2F) * scale;
                float vy = points[i][1] * ev.random(8.4F, 11.2F) * scale;
                spawnButtonFireworkSpark(ev, i % 3 == 0 ? fade : primary, vx, vy,
                        trail, twinkle && i % 4 == 0, i == 0 && twinkle);
            }
        } else if (shape.equals("burst")) {
            float direction = ev.random(-0.42F, 0.42F) - (float)Math.PI / 2.0F;
            for (int i = 0; i < count; i++) {
                float a = direction + ev.random(-0.78F, 0.78F);
                float speed = ev.random(28.0F, 62.0F) * scale;
                spawnButtonFireworkSpark(ev, i % 2 == 0 ? primary : fade,
                        (float)Math.cos(a) * speed, (float)Math.sin(a) * speed,
                        trail, twinkle && i % 4 == 0, i == 0 && twinkle);
            }
        } else {
            float radius = (shape.equals("large_ball") ? 58.0F : 42.0F) * scale;
            for (int i = 0; i < count; i++) {
                double a = Math.PI * 2.0 * i / count + ev.random(-0.08F, 0.08F);
                float speed = ev.random(radius * 0.70F, radius);
                spawnButtonFireworkSpark(ev, i < count * 0.58F ? primary : fade,
                        (float)Math.cos(a) * speed, (float)Math.sin(a) * speed,
                        trail, twinkle && i % 5 == 0, i == 0 && twinkle);
            }
        }

        int flashCount = shape.equals("large_ball") ? 3 : 2;
        for (int i = 0; i < flashCount; i++) {
            ev.spawn(ev.particle(i == 0 ? Shape.STAR : Shape.SPARK)
                    .primitive(false)
                    .sprite(i == 0 ? SpriteSet.FLASH : SpriteSet.GLITTER)
                    .material(UiParticleMaterials.Material.FIREWORK)
                    .color(i == 0 ? primary : fade)
                    .visualPixels(ev.random(4.2F, 7.2F))
                    .lifetime(ev.random(0.20F, 0.38F))
                    .velocity(ev.random(-4.0F, 4.0F), ev.random(-4.0F, 4.0F))
                    .gravity(0.0F)
                    .drag(0.95F)
                    .collideButtons(false)
                    .collideScreen(false)
                    .layer(i == 0 ? Layer.FRONT : Layer.BACK));
        }
    }

    private static String pickFireworkShape(JsonObject cfg, ParticleEventContext ev, String requested) {
        String[] vanillaShapes = {"small_ball", "large_ball", "star", "creeper", "burst"};
        JsonArray authored = array(cfg, "firework_shapes");
        if (!authored.isEmpty()) {
            for (int attempts = 0; attempts < authored.size(); attempts++) {
                JsonElement element = authored.get(ev.randomInt(authored.size()));
                if (element != null && element.isJsonPrimitive()) {
                    String candidate = element.getAsString().trim().toLowerCase();
                    if (Set.of(vanillaShapes).contains(candidate)) return candidate;
                }
            }
        }
        String shape = requested == null ? "random" : requested.trim().toLowerCase();
        if (shape.equals("random") || !Set.of(vanillaShapes).contains(shape)) {
            shape = vanillaShapes[ev.randomInt(vanillaShapes.length)];
        }
        return shape;
    }

    private static void spawnButtonFireworkSpark(ParticleEventContext ev, int color, float vx, float vy,
                                                  boolean trail, boolean twinkleShape, boolean twinkleCarrier) {
        Shape shape = twinkleShape ? Shape.STAR : (trail ? Shape.RIBBON : Shape.SPARK);
        ParticleBuilder spark = ev.particle(shape)
                .primitive(false)
                .sprite(twinkleShape ? SpriteSet.GLITTER : SpriteSet.FIREWORK)
                .material(UiParticleMaterials.Material.FIREWORK)
                .color(color)
                .visualPixels(ev.random(twinkleShape ? 3.6F : 2.8F, twinkleShape ? 5.8F : 4.8F))
                .lifetime(ev.random(0.72F, 1.30F))
                .velocity(vx, vy)
                .gravity(ev.random(13.0F, 25.0F))
                .drag(0.975F)
                .stretchWithVelocity(trail)
                .collideButtons(false)
                .collideScreen(false)
                .rotationPolicy(trail ? RotationPolicy.FACE_VELOCITY : RotationPolicy.LOCKED)
                .layer(ev.chance(0.18F) ? Layer.FRONT : Layer.BACK);
        if (twinkleCarrier) {
            spark.onExpire(done -> {
                done.playSound(SoundEvent.of(identifierWithDefaultNamespace("entity.firework_rocket.twinkle")),
                        0.10F, done.random(1.16F, 1.42F));
                for (int i = 0; i < 3; i++) {
                    done.spawn(done.particle(Shape.SPARK)
                            .primitive(false)
                            .sprite(SpriteSet.GLITTER)
                            .material(UiParticleMaterials.Material.FIREWORK)
                            .color(color)
                            .visualPixels(done.random(2.4F, 4.0F))
                            .lifetime(done.random(0.32F, 0.60F))
                            .velocity(done.random(-7.0F, 7.0F), done.random(-7.0F, 7.0F))
                            .gravity(7.0F)
                            .drag(0.96F)
                            .collideButtons(false)
                            .collideScreen(false)
                            .layer(Layer.FRONT));
                }
            });
        }
        ev.spawn(spark);
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
                    .material(UiParticleMaterials.Material.BLOCK)
                    .visual(VisualFamily.BLOCK_SHARD)
                    .texture(texture).textureAutoSize(true).textureTint(false)
                    .visualPixels(randomRange(ctx, cfg, "projectile_pixels", 10.0F, 15.0F))
                    .lifetime(decimal(cfg, "projectile_life", 4.0F))
                    .velocity(origin.velocityX(ctx.random(70, 125), ctx.random(-55, 55)), origin.velocityY(ctx.random(70, 125), ctx.random(-55, 55)) - ctx.random(35, 85))
                    .layer(ctx.chance(decimal(cfg, "front_ratio", 0.55F)) ? Layer.FRONT : Layer.BACK)
                    .spawnCollisionInvulnerability(SPAWN_COLLISION_INVULNERABILITY_SECONDS)
                    .soundProfile(GameSpriteSoundResolver.blockProfile(block))
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
                .material(UiParticleMaterials.Material.SLIME)
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
                            ev.spawn(ev.particle(Shape.SLIME).material(UiParticleMaterials.Material.SLIME)
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
                    .material(UiParticleMaterials.Material.PORTAL).behavior(Behavior.RICOCHET)
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
                .texture(texture).textureTint(false).visualPixels(14.0F).material(UiParticleMaterials.Material.BLOCK)
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
                    .texture(texture).textureTint(false).visualPixels(12.0F).material(UiParticleMaterials.Material.AMETHYST)
                    .velocity(origin.velocityX(ctx.random(65, 115), ctx.random(-55, 55)), origin.velocityY(ctx.random(65, 115), ctx.random(-55, 55)) - 45)
                    .lifetime(4.0F).layer(i == 0 ? Layer.FRONT : Layer.BACK).spawnCollisionInvulnerability(SPAWN_COLLISION_INVULNERABILITY_SECONDS)
                    .soundProfile(GameSpriteSoundResolver.blockProfile(block))
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
                .material(UiParticleMaterials.Material.FIREWORK).color(color).visualPixels(ev.random(4.0F, 7.0F))
                .lifetime(ev.random(0.8F, 2.1F)).velocity(vx, vy).layer(layer).collideButtons(true).collideScreen(true).spawnCollisionInvulnerability(SPAWN_COLLISION_INVULNERABILITY_SECONDS));
    }

    private static void spawnChainBlast(JsonObject cfg, EffectContext ctx) {
        int count = integer(cfg, "count", 8, 2, 24);
        for (int i = 0; i < count; i++) {
            final int index = i;
            float angle = (float)(Math.PI * 2.0 * i / count);
            FaceSpawn origin = ctx.faceSpawn(0.4F);
            ctx.spawn(ctx.particle(Shape.SPARK, origin.x(), origin.y()).sprite(SpriteSet.FIREWORK).material(UiParticleMaterials.Material.FIREWORK)
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
            if (texture != null) child.texture(texture).textureTint(false).material(UiParticleMaterials.Material.BLOCK);
            if (block != null) child.blockSounds(block);
            ev.spawn(child);
        }
    }

    private static void playConfiguredSound(ParticleEventContext ev, JsonObject cfg, String key, String fallback, float volume, float pitch) {
        String sound = string(cfg, key, fallback);
        if (!sound.isBlank()) ev.playSound(SoundEvent.of(identifierWithDefaultNamespace(sound)), volume, pitch);
    }

    private static UiParticleSoundProfile particleSoundProfile(JsonObject cfg) {
        UiParticleSoundProfile.Cue bounce = cue(cfg, "bounce_sound", 0.20F, 1.0F, 0.08F, 60L);
        UiParticleSoundProfile.Cue breakCue = cue(cfg, "break_sound", 0.28F, 1.0F, 0.06F, 70L);
        UiParticleSoundProfile.Cue expire = cue(cfg, "expire_sound", 0.18F, 1.0F, 0.06F, 90L);
        UiParticleSoundProfile.Cue spawn = cue(cfg, "spawn_sound", 0.18F, 1.0F, 0.06F, 90L);
        if (bounce == null && breakCue == null && expire == null && spawn == null) return null;
        return UiParticleSoundProfile.builder().spawn(spawn).bounce(bounce).breakCue(breakCue).expire(expire)
                .minimumBounceSpeed(decimal(cfg, "minimum_bounce_sound_speed", 16.0F)).build();
    }

    private static UiParticleSoundProfile.Cue cue(JsonObject object, String key, float volume, float pitch, float jitter, long cooldown) {
        String path = string(object, key, "").trim();
        if (path.isBlank()) return null;
        String prefix = key.endsWith("_sound") ? key.substring(0, key.length() - 6) : key;
        return new UiParticleSoundProfile.Cue(
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

    private static int pickColor(JsonObject cfg, ParticleEventContext ev, int fallback) {
        JsonArray palette = array(cfg, "palette");
        if (palette.size() == 0) return fallback;
        return color(palette.get(ev.randomInt(palette.size())), fallback);
    }

    private static int pickColor(JsonObject cfg, EffectContext ctx, int fallback) {
        JsonArray palette = array(cfg, "palette");
        if (palette.size() == 0) return fallback;
        return color(palette.get(ctx.randomInt(palette.size())), fallback);
    }

    private static int integerRange(EffectContext ctx, JsonObject cfg, String key, int fallback, int min, int max) {
        JsonElement element = cfg.get(key);
        if (element != null && element.isJsonArray() && element.getAsJsonArray().size() >= 2) {
            int a = element.getAsJsonArray().get(0).getAsInt();
            int b = element.getAsJsonArray().get(1).getAsInt();
            int low = Math.max(min, Math.min(a, b));
            int high = Math.min(max, Math.max(a, b));
            if (high < low) return Math.max(min, Math.min(max, fallback));
            return low + ctx.randomInt(Math.max(1, high - low + 1));
        }
        return integer(cfg, key, fallback, min, max);
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
