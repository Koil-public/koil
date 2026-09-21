package com.spirit.koil.api.design.particle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.design.sprite.actor.Actor;
import com.spirit.koil.api.design.sprite.actor.ItemActor;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.minecraft.MinecraftStateCodec;
import com.spirit.koil.api.design.sprite.world.BlockCell;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import com.spirit.koil.api.design.sprite.world.FluidGrid;
import com.spirit.koil.api.design.sprite.world.Scene;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Persistent document format for scene-owned Koil sprite playground backgrounds.
 *
 * <p>The document stores authoritative BlockState values, detached fluid state,
 * stable item actors, scene projection/camera values and detached environment
 * metadata. Transient projectiles/falling actors are intentionally not persisted;
 * they are runtime outcomes used for testing rather than background authoring.</p>
 */
public final class SpriteSceneStorage {
    public static final int FORMAT_VERSION = 2;
    public static final Path DIRECTORY = Paths.get("./koil/sys/design/scenes");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SpriteSceneStorage() { }

    public record Result(boolean success, String message, Path path) { }

    public static Path scenePath(String requestedName) {
        String safe = requestedName == null ? "playground" : requestedName.strip().toLowerCase(Locale.ROOT);
        safe = safe.replaceAll("[^a-z0-9._-]+", "_");
        while (safe.startsWith(".")) safe = safe.substring(1);
        if (safe.isBlank()) safe = "playground";
        if (!safe.endsWith(".json")) safe += ".json";
        return DIRECTORY.resolve(safe).normalize();
    }

    public static Result save(UiParticleEngine engine, String requestedName) {
        if (engine == null) return new Result(false, "No playground engine", null);
        Path file = scenePath(requestedName);
        try {
            Files.createDirectories(DIRECTORY);
            Files.writeString(file, GSON.toJson(capture(engine)), StandardCharsets.UTF_8);
            return new Result(true, "Saved " + file.getFileName(), file);
        } catch (IOException | RuntimeException exception) {
            return new Result(false, "Save failed: " + compact(exception), file);
        }
    }

    public static Result load(UiParticleEngine engine, String requestedName) {
        if (engine == null) return new Result(false, "No playground engine", null);
        Path file = scenePath(requestedName);
        if (!Files.isRegularFile(file)) return new Result(false, "Scene not found: " + file.getFileName(), file);
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return new Result(false, "Scene file is not a JSON object", file);
            validate(root.getAsJsonObject());
            RestoreStats stats = restore(engine, root.getAsJsonObject());
            return new Result(true,
                    "Loaded " + file.getFileName() + " | " + stats.blocks + " blocks, "
                            + stats.fluids + " fluids, " + stats.items + " items", file);
        } catch (IOException | RuntimeException exception) {
            return new Result(false, "Load failed: " + compact(exception), file);
        }
    }

    /** Local saved-scene names for the playground command/UI completion list. */
    public static List<String> listSceneNames() {
        if (!Files.isDirectory(DIRECTORY)) return List.of();
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(DIRECTORY)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".json"))
                    .forEach(name -> names.add(name.substring(0, name.length() - 5)));
        } catch (IOException ignored) {
            return List.of();
        }
        names.sort(Comparator.naturalOrder());
        return List.copyOf(names);
    }

    /** Returns the newest saved scene name, if any. */
    public static Optional<String> mostRecentSceneName() {
        if (!Files.isDirectory(DIRECTORY)) return Optional.empty();
        try (var stream = Files.list(DIRECTORY)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .max(Comparator.comparingLong(path -> {
                        try { return Files.getLastModifiedTime(path).toMillis(); }
                        catch (IOException ignored) { return Long.MIN_VALUE; }
                    }))
                    .map(path -> {
                        String name = path.getFileName().toString();
                        return name.substring(0, name.length() - 5);
                    });
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    /** Captures a stable background-authoring snapshot. Also used by undo/redo. */
    public static JsonObject capture(UiParticleEngine engine) {
        JsonObject root = new JsonObject();
        root.addProperty("format", "koil_sprite_scene");
        root.addProperty("version", FORMAT_VERSION);
        Scene scene = engine.getSpriteEngine().scene();

        JsonObject projection = new JsonObject();
        float viewportWidth = Math.max(1.0F, scene.physics().viewportWidth());
        float viewportHeight = Math.max(1.0F, scene.physics().viewportHeight());
        projection.addProperty("mode", scene.projection().mode().name().toLowerCase(Locale.ROOT));
        projection.addProperty("cell_pixels", scene.projection().cellPixels());
        projection.addProperty("origin_x", scene.projection().originX());
        projection.addProperty("origin_y", scene.projection().originY());
        projection.addProperty("origin_ratio_x", scene.projection().originX() / viewportWidth);
        projection.addProperty("origin_ratio_y", scene.projection().originY() / viewportHeight);
        projection.addProperty("reference_width", viewportWidth);
        projection.addProperty("reference_height", viewportHeight);
        root.add("projection", projection);

        JsonObject environment = new JsonObject();
        environment.addProperty("biome", scene.environment().biomeId().toString());
        environment.addProperty("dimension", scene.environment().dimensionId().toString());
        environment.addProperty("water_color", scene.environment().waterColor());
        environment.addProperty("foliage_color", scene.environment().foliageColor());
        environment.addProperty("grass_color", scene.environment().grassColor());
        environment.addProperty("grass_color_modifier", scene.environment().grassColorModifier().asString());
        environment.addProperty("sky_light", scene.environment().skyLight());
        environment.addProperty("block_light", scene.environment().blockLight());
        environment.addProperty("time_of_day", scene.environment().timeOfDay());
        environment.addProperty("temperature", scene.environment().temperature());
        environment.addProperty("downfall", scene.environment().downfall());
        environment.addProperty("raining", scene.environment().raining());
        environment.addProperty("thundering", scene.environment().thundering());
        root.add("environment", environment);

        JsonArray blocks = new JsonArray();
        for (BlockGrid.Entry entry : scene.blocks().entries()) {
            if (entry == null || entry.cell() == null) continue;
            BlockState state = entry.cell().blockState();
            if (state == null || state.isAir()) continue;
            Identifier id = Registries.BLOCK.getId(state.getBlock());
            JsonObject value = position(entry.position());
            value.addProperty("id", id.toString());
            value.addProperty("state", MinecraftStateCodec.encode(state));
            value.addProperty("flags", entry.cell().flags());
            if (!entry.cell().runtimeData().isEmpty()) value.add("runtime", stringMap(entry.cell().runtimeData()));
            if (!entry.cell().blockEntityData().isEmpty()) value.add("block_entity", stringMap(entry.cell().blockEntityData()));
            blocks.add(value);
        }
        root.add("blocks", blocks);

        JsonArray fluids = new JsonArray();
        for (FluidGrid.Entry entry : scene.fluids().entries()) {
            if (entry == null || entry.cell() == null || entry.cell().isEmpty()) continue;
            FluidState state = entry.cell().state();
            Identifier id = Registries.FLUID.getId(state.getFluid());
            JsonObject value = position(entry.position());
            value.addProperty("id", id.toString());
            value.addProperty("state", encodeProperties(state.getEntries()));
            fluids.add(value);
        }
        root.add("fluids", fluids);

        JsonArray items = new JsonArray();
        for (Actor actor : scene.actors().actors()) {
            if (!(actor instanceof ItemActor item) || item.removed()
                    || item.authority() != Actor.Authority.SCENE || item.stack().isEmpty()) continue;
            ItemStack stack = item.stack();
            JsonObject value = new JsonObject();
            value.addProperty("id", Registries.ITEM.getId(stack.getItem()).toString());
            value.addProperty("count", stack.getCount());
            value.addProperty("stack", stack.writeNbt(new NbtCompound()).toString());
            value.addProperty("x", item.x());
            value.addProperty("y", item.y());
            value.addProperty("x_ratio", item.x() / viewportWidth);
            value.addProperty("y_ratio", item.y() / viewportHeight);
            value.addProperty("depth", item.depth());
            value.addProperty("velocity_x", item.velocityX());
            value.addProperty("velocity_y", item.velocityY());
            value.addProperty("rotation", item.rotation());
            value.addProperty("angular_velocity", item.angularVelocity());
            value.addProperty("gravity", item.gravity());
            value.addProperty("drag", item.drag());
            value.addProperty("mass", item.body().mass());
            value.addProperty("restitution", item.body().restitution());
            value.addProperty("friction", item.body().surfaceFriction());
            value.addProperty("kinematic", item.body().kinematic());
            value.addProperty("collide_world", item.body().collideWorld());
            value.addProperty("collide_actors", item.body().collideActors());
            value.addProperty("collide_bounds", item.body().collideSceneBounds());
            value.addProperty("can_sleep", item.body().canSleep());
            items.add(value);
        }
        root.add("items", items);
        return root;
    }

    public static RestoreStats restore(UiParticleEngine engine, JsonObject root) {
        if (engine == null || root == null) return new RestoreStats(0, 0, 0, 0);
        validate(root);
        engine.reset();
        Scene scene = engine.getSpriteEngine().scene();

        JsonObject projection = object(root, "projection");
        float viewportWidth = Math.max(1.0F, scene.physics().viewportWidth());
        float viewportHeight = Math.max(1.0F, scene.physics().viewportHeight());
        if (projection != null) {
            scene.projection().setMode(string(projection, "mode", "xy_side"));
            scene.projection().setCellPixels(decimal(projection, "cell_pixels", 16.0F));
            float originX = projection.has("origin_ratio_x")
                    ? decimal(projection, "origin_ratio_x", 0.0F) * viewportWidth
                    : decimal(projection, "origin_x", 0.0F);
            float originY = projection.has("origin_ratio_y")
                    ? decimal(projection, "origin_ratio_y", 0.0F) * viewportHeight
                    : decimal(projection, "origin_y", 0.0F);
            scene.projection().setOrigin(originX, originY);
        }

        JsonObject environment = object(root, "environment");
        if (environment != null) {
            Identifier biome = identifier(string(environment, "biome", "minecraft:plains"));
            Identifier dimension = identifier(string(environment, "dimension", "minecraft:overworld"));
            if (biome != null) scene.environment().setBiomeId(biome);
            if (dimension != null) scene.environment().setDimensionId(dimension);
            scene.environment().setWaterColor(integer(environment, "water_color", 0x3F76E4));
            scene.environment().setFoliageColor(integer(environment, "foliage_color", 0x59AE30));
            scene.environment().setGrassColor(integer(environment, "grass_color", 0x91BD59));
            scene.environment().setGrassColorModifier(string(environment, "grass_color_modifier", "none"));
            scene.environment().setSkyLight(integer(environment, "sky_light", 15));
            scene.environment().setBlockLight(integer(environment, "block_light", 0));
            scene.environment().setTimeOfDay(longValue(environment, "time_of_day", 6000L));
            scene.environment().setTemperature(decimal(environment, "temperature", 0.8F));
            scene.environment().setDownfall(decimal(environment, "downfall", 0.4F));
            scene.environment().setRaining(bool(environment, "raining", false));
            scene.environment().setThundering(bool(environment, "thundering", false));
        }

        int blocks = 0;
        JsonArray blockArray = array(root, "blocks");
        if (blockArray != null) {
            for (JsonElement element : blockArray) {
                if (!element.isJsonObject()) continue;
                JsonObject value = element.getAsJsonObject();
                Identifier id = identifier(string(value, "id", ""));
                if (id == null || !Registries.BLOCK.containsId(id)) continue;
                Block block = Registries.BLOCK.get(id);
                BlockState state = MinecraftStateCodec.resolve(block, string(value, "state", ""));
                if (state == null || state.isAir()) continue;
                SceneCellPos blockPos = position(value);
                BlockCell cell = scene.blocks().setSceneOwned(blockPos, state);
                if (cell != null) {
                    scene.blocks().setFlags(blockPos, integer(value, "flags", 0));
                    JsonObject runtime = object(value, "runtime");
                    if (runtime != null) for (Map.Entry<String, JsonElement> data : runtime.entrySet()) {
                        if (data.getValue() != null && data.getValue().isJsonPrimitive()) {
                            scene.blocks().putRuntimeData(blockPos, data.getKey(), data.getValue().getAsString());
                        }
                    }
                    JsonObject blockEntity = object(value, "block_entity");
                    if (blockEntity != null) for (Map.Entry<String, JsonElement> data : blockEntity.entrySet()) {
                        if (data.getValue() != null && data.getValue().isJsonPrimitive()) {
                            scene.blocks().putBlockEntityData(blockPos, data.getKey(), data.getValue().getAsString());
                        }
                    }
                    scene.blocks().restartLifetime(blockPos);
                }
                blocks++;
            }
        }

        int fluids = 0;
        JsonArray fluidArray = array(root, "fluids");
        if (fluidArray != null) {
            for (JsonElement element : fluidArray) {
                if (!element.isJsonObject()) continue;
                JsonObject value = element.getAsJsonObject();
                Identifier id = identifier(string(value, "id", ""));
                if (id == null || !Registries.FLUID.containsId(id)) continue;
                Fluid fluid = Registries.FLUID.get(id);
                FluidState state = applyProperties(fluid.getDefaultState(), string(value, "state", ""));
                if (state == null || state.isEmpty()) continue;
                scene.fluids().set(position(value), state);
                fluids++;
            }
        }

        int items = 0;
        JsonArray itemArray = array(root, "items");
        if (itemArray != null) {
            for (JsonElement element : itemArray) {
                if (!element.isJsonObject()) continue;
                JsonObject value = element.getAsJsonObject();
                Identifier id = identifier(string(value, "id", ""));
                if (id == null || !Registries.ITEM.containsId(id)) continue;
                Item item = Registries.ITEM.get(id);
                int count = Math.max(1, integer(value, "count", 1));
                ItemStack stack = new ItemStack(item, Math.min(count, Math.max(1, item.getMaxCount())));
                String serializedStack = string(value, "stack", "");
                if (!serializedStack.isBlank()) {
                    try {
                        ItemStack decoded = ItemStack.fromNbt(StringNbtReader.parse(serializedStack));
                        if (!decoded.isEmpty()) stack = decoded;
                    } catch (Exception ignored) {
                        // Keep the readable id/count fallback for forward/backward compatibility.
                    }
                }
                float actorX = value.has("x_ratio")
                        ? decimal(value, "x_ratio", 0.0F) * viewportWidth
                        : decimal(value, "x", 0.0F);
                float actorY = value.has("y_ratio")
                        ? decimal(value, "y_ratio", 0.0F) * viewportHeight
                        : decimal(value, "y", 0.0F);
                long actorId = scene.actors().allocateNativeId();
                ItemActor actor = new ItemActor(actorId, Actor.Authority.SCENE, stack,
                        actorX, actorY, integer(value, "depth", 0));
                actor.setVelocity(decimal(value, "velocity_x", 0.0F), decimal(value, "velocity_y", 0.0F));
                actor.setRotation(decimal(value, "rotation", 0.0F));
                actor.setAngularVelocity(decimal(value, "angular_velocity", 0.0F));
                actor.setGravity(decimal(value, "gravity", actor.gravity()));
                actor.setDrag(decimal(value, "drag", actor.drag()));
                actor.setMass(decimal(value, "mass", actor.body().mass()));
                actor.setRestitution(decimal(value, "restitution", actor.body().restitution()));
                actor.setSurfaceFriction(decimal(value, "friction", actor.body().surfaceFriction()));
                actor.setKinematic(bool(value, "kinematic", false));
                actor.body().setCollideWorld(bool(value, "collide_world", true));
                actor.body().setCollideActors(bool(value, "collide_actors", true));
                actor.body().setCollideSceneBounds(bool(value, "collide_bounds", true));
                actor.body().setCanSleep(bool(value, "can_sleep", true));
                scene.actors().put(actor);
                items++;
            }
        }

        scene.multipartBlocks().reconcile();
        scene.neighborStates().reconcile();
        scene.chestSystem().reconcilePairs();
        scene.neighborStates().reconcile();
        return new RestoreStats(blocks, fluids, items, 0);
    }

    public record RestoreStats(int blocks, int fluids, int items, int skippedTransientActors) { }


    private static void validate(JsonObject root) {
        String format = string(root, "format", "");
        if (!"koil_sprite_scene".equals(format)) {
            throw new IllegalArgumentException("Unsupported scene format: " + (format.isBlank() ? "missing" : format));
        }
        int version = integer(root, "version", -1);
        if (version < 1 || version > FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported scene version: " + version);
        }
    }

    private static JsonObject position(SceneCellPos pos) {
        JsonObject value = new JsonObject();
        value.addProperty("x", pos.x());
        value.addProperty("y", pos.y());
        value.addProperty("depth", pos.depth());
        return value;
    }

    private static SceneCellPos position(JsonObject value) {
        return new SceneCellPos(integer(value, "x", 0), integer(value, "y", 0), integer(value, "depth", 0));
    }

    private static JsonObject stringMap(Map<String, String> values) {
        JsonObject object = new JsonObject();
        if (values != null) values.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) object.addProperty(key, value);
        });
        return object;
    }

    private static String encodeProperties(Map<Property<?>, Comparable<?>> entries) {
        if (entries == null || entries.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        entries.entrySet().stream().sorted((a, b) -> a.getKey().getName().compareTo(b.getKey().getName())).forEach(entry -> {
            if (builder.length() > 0) builder.append(',');
            builder.append(entry.getKey().getName()).append('=').append(entry.getValue());
        });
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private static FluidState applyProperties(FluidState state, String signature) {
        if (state == null || signature == null || signature.isBlank()) return state;
        FluidState current = state;
        for (String token : signature.split("[,;]")) {
            int equals = token.indexOf('=');
            if (equals <= 0) continue;
            String key = token.substring(0, equals).trim();
            String value = token.substring(equals + 1).trim();
            current = withProperty(current, key, value);
        }
        return current;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static FluidState withProperty(FluidState state, String key, String value) {
        if (state == null) return null;
        for (Property property : state.getProperties()) {
            if (!property.getName().equalsIgnoreCase(key)) continue;
            try {
                Optional parsed = property.parse(value);
                if (parsed.isPresent()) return state.with(property, (Comparable) parsed.get());
            } catch (RuntimeException ignored) { }
        }
        return state;
    }

    private static JsonObject object(JsonObject root, String key) {
        JsonElement element = root.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject root, String key) {
        JsonElement element = root.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String string(JsonObject root, String key, String fallback) {
        try { return root.has(key) ? root.get(key).getAsString() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static int integer(JsonObject root, String key, int fallback) {
        try { return root.has(key) ? root.get(key).getAsInt() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static long longValue(JsonObject root, String key, long fallback) {
        try { return root.has(key) ? root.get(key).getAsLong() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static float decimal(JsonObject root, String key, float fallback) {
        try { return root.has(key) ? root.get(key).getAsFloat() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static boolean bool(JsonObject root, String key, boolean fallback) {
        try { return root.has(key) ? root.get(key).getAsBoolean() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static Identifier identifier(String value) {
        if (value == null || value.isBlank()) return null;
        try { return new Identifier(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private static String compact(Throwable throwable) {
        String message = throwable == null ? "unknown error" : throwable.getMessage();
        if (message == null || message.isBlank()) message = throwable == null ? "unknown error" : throwable.getClass().getSimpleName();
        return message.length() > 180 ? message.substring(0, 180) : message;
    }
}
