package com.spirit.koil.api.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/** Reloadable state/behavior evaluation for startup-stable dynamic blocks. */
final class DynamicContentBlockBehavior {
    private DynamicContentBlockBehavior() {
    }

    static int luminance(
            String contentId,
            DynamicBlockStateSchema schema,
            BlockState state,
            int bootstrapFallback
    ) {
        WorldContentIndex.DefinitionEntry active = WorldScopedContentPresentation.activeDefinition(contentId);
        if (active == null) {
            return 0;
        }
        int base = integer(
                DefinitionValueReader.object(active.definition(), "properties"),
                "luminance",
                bootstrapFallback
        );
        return MathHelper.clamp(mappedInt(active, schema, state, "luminance", base), 0, 15);
    }

    static boolean emitsPower(String contentId, DynamicBlockStateSchema schema, BlockState state) {
        WorldContentIndex.DefinitionEntry active = WorldScopedContentPresentation.activeDefinition(contentId);
        if (active == null) {
            return false;
        }
        JsonObject redstone = DefinitionValueReader.object(
                DefinitionValueReader.object(active.definition(), "behavior"),
                "redstone"
        );
        return DefinitionValueReader.bool(redstone, "emits_power", false)
                || weakPower(contentId, schema, state) > 0
                || strongPower(contentId, schema, state) > 0;
    }

    static int weakPower(String contentId, DynamicBlockStateSchema schema, BlockState state) {
        return power(contentId, schema, state, "weak_power");
    }

    static int strongPower(String contentId, DynamicBlockStateSchema schema, BlockState state) {
        return power(contentId, schema, state, "strong_power");
    }

    static List<String> cycleOnUse(String contentId) {
        WorldContentIndex.DefinitionEntry active = WorldScopedContentPresentation.activeDefinition(contentId);
        if (active == null) {
            return List.of();
        }
        JsonObject interactions = DefinitionValueReader.object(
                DefinitionValueReader.object(active.definition(), "behavior"),
                "state_interactions"
        );
        JsonElement cycle = interactions.get("cycle_on_use");
        if (cycle == null || !cycle.isJsonArray()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (JsonElement element : cycle.getAsJsonArray()) {
            if (element.isJsonPrimitive()) {
                names.add(element.getAsString());
            }
        }
        return List.copyOf(names);
    }

    private static int power(
            String contentId,
            DynamicBlockStateSchema schema,
            BlockState state,
            String key
    ) {
        WorldContentIndex.DefinitionEntry active = WorldScopedContentPresentation.activeDefinition(contentId);
        if (active == null) {
            return 0;
        }
        JsonObject redstone = DefinitionValueReader.object(
                DefinitionValueReader.object(active.definition(), "behavior"),
                "redstone"
        );
        int base = integer(redstone, key, 0);
        return MathHelper.clamp(
                mappedInt(active, schema, state, "redstone." + key, base),
                0,
                15
        );
    }

    private static int mappedInt(
            WorldContentIndex.DefinitionEntry active,
            DynamicBlockStateSchema schema,
            BlockState state,
            String key,
            int fallback
    ) {
        JsonElement section = active.definition().get("blockstates");
        if (section == null || !section.isJsonObject()) {
            return fallback;
        }
        JsonObject mappings = DefinitionValueReader.object(section.getAsJsonObject(), "behavior_mapping");
        int result = fallback;
        for (var mapping : mappings.entrySet()) {
            if (!mapping.getValue().isJsonObject() || !schema.matches(state, mapping.getKey())) {
                continue;
            }
            JsonElement value = dotted(mapping.getValue().getAsJsonObject(), key);
            if (value != null) {
                try {
                    result = value.getAsInt();
                } catch (RuntimeException ignored) {
                    // Validation reports malformed values; runtime keeps the last
                    // valid/fallback behavior instead of crashing a world tick.
                }
            }
        }
        return result;
    }

    private static JsonElement dotted(JsonObject object, String path) {
        JsonElement direct = object.get(path);
        if (direct != null) {
            return direct;
        }
        JsonElement current = object;
        for (String segment : path.split("\\.")) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(segment);
        }
        return current;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        return DefinitionValueReader.integer(object, key, fallback);
    }
}
