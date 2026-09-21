package com.spirit.koil.api.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.spirit.koil.api.util.text.FuzzyTextMatcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Conservative schema-aware repair for malformed model tool calls.
 *
 * <p>Repairs are bounded to definitions already supplied to the model. This
 * class never grants a capability and never bypasses argument grounding,
 * confirmation, or execution policy. Its job is only to recover obvious
 * protocol mistakes made by small/local models.</p>
 */
final class ModelToolCallTolerance {
    private static final int NAME_SCORE = 840;
    private static final int NAME_MARGIN = 90;
    private static final int ENUM_SCORE = 760;
    private static final int ENUM_MARGIN = 70;

    private ModelToolCallTolerance() {}

    static Repair repair(ModelToolCall raw, List<ModelToolDefinition> supplied) {
        if (raw == null) return new Repair(null, List.of());
        List<ModelToolDefinition> definitions = supplied == null ? List.of() : supplied;
        List<String> changes = new ArrayList<>();

        ModelToolDefinition definition = definitions.stream()
                .filter(candidate -> candidate.id().equals(raw.toolId()))
                .findFirst().orElse(null);
        String toolId = raw.toolId();
        if (definition == null) {
            RankedName repaired = bestName(raw.toolId(), definitions.stream().map(ModelToolDefinition::id).toList());
            if (repaired.accepted(NAME_SCORE, NAME_MARGIN)) {
                toolId = repaired.value();
                String selectedId = toolId;
                definition = definitions.stream().filter(candidate -> candidate.id().equals(selectedId)).findFirst().orElse(null);
                changes.add("tool:" + raw.toolId() + "->" + toolId);
            }
        }
        if (definition == null) return new Repair(raw, List.copyOf(changes));

        JsonObject schema = definition.inputSchema();
        JsonObject properties = schema != null && schema.has("properties") && schema.get("properties").isJsonObject()
                ? schema.getAsJsonObject("properties") : new JsonObject();
        JsonObject source = raw.arguments() == null ? new JsonObject() : raw.arguments();
        JsonObject repairedArguments = new JsonObject();

        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            String key = entry.getKey();
            String repairedKey = key;
            if (!properties.has(key) && properties.size() > 0) {
                RankedName match = bestName(key, properties.keySet().stream().toList());
                if (match.accepted(NAME_SCORE, NAME_MARGIN) && !source.has(match.value()) && !repairedArguments.has(match.value())) {
                    repairedKey = match.value();
                    changes.add("arg:" + key + "->" + repairedKey);
                }
            }
            JsonObject propertySchema = properties.has(repairedKey) && properties.get(repairedKey).isJsonObject()
                    ? properties.getAsJsonObject(repairedKey) : null;

            EnumPayloadRepair enumPayload = repairEnumPayload(
                    repairedKey, entry.getValue(), propertySchema, properties, source, repairedArguments
            );
            if (enumPayload != null) {
                repairedArguments.addProperty(repairedKey, enumPayload.enumValue());
                repairedArguments.addProperty(enumPayload.payloadKey(), enumPayload.payloadValue());
                changes.add("enum_payload:" + repairedKey + "=" + enumPayload.payloadValue()
                        + "->" + enumPayload.enumValue() + ";" + enumPayload.payloadKey());
                continue;
            }

            JsonElement value = repairValue(repairedKey, entry.getValue(), propertySchema, changes);
            repairedArguments.add(repairedKey, value == null ? entry.getValue().deepCopy() : value);
        }

        return new Repair(new ModelToolCall(raw.id(), toolId, repairedArguments), List.copyOf(changes));
    }


    /**
     * Recovers a common small-model schema mistake without knowing any specific tool:
     * a free-form search phrase is placed in an enum selector field (for example a
     * query/mode/operation field) while the schema also exposes a separate string
     * payload field. If the enum advertises a discovery-like operation, preserve the
     * model's text in the companion field and select that operation.
     */
    private static EnumPayloadRepair repairEnumPayload(
            String key,
            JsonElement raw,
            JsonObject schema,
            JsonObject properties,
            JsonObject source,
            JsonObject repairedArguments
    ) {
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()
                || schema == null || !schema.has("enum") || !schema.get("enum").isJsonArray()) {
            return null;
        }
        String current = raw.getAsString().strip();
        if (current.isBlank()) return null;

        List<String> allowed = new ArrayList<>();
        for (JsonElement element : schema.getAsJsonArray("enum")) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                allowed.add(element.getAsString());
            }
        }
        if (allowed.contains(current)) return null;

        // Let the normal fuzzy enum repair win when the model was merely close to an
        // actual enum literal. This fallback is only for phrases that clearly do not
        // represent one selector value.
        RankedName direct = bestName(current, allowed);
        if (direct.accepted(ENUM_SCORE, ENUM_MARGIN)) return null;

        String operation = firstPresent(allowed, List.of("search", "find", "query", "inspect", "resolve", "lookup"));
        if (operation.isBlank()) return null;

        for (String payloadKey : List.of("value", "query", "text", "term", "pattern", "name")) {
            if (payloadKey.equals(key) || source.has(payloadKey) || repairedArguments.has(payloadKey)) continue;
            if (!properties.has(payloadKey) || !properties.get(payloadKey).isJsonObject()) continue;
            JsonObject payloadSchema = properties.getAsJsonObject(payloadKey);
            String payloadType = payloadSchema.has("type") && payloadSchema.get("type").isJsonPrimitive()
                    ? payloadSchema.get("type").getAsString() : "";
            if (!"string".equals(payloadType)) continue;
            return new EnumPayloadRepair(operation, payloadKey, current);
        }
        return null;
    }

    private static String firstPresent(List<String> allowed, List<String> preferred) {
        for (String candidate : preferred) {
            if (allowed.contains(candidate)) return candidate;
        }
        return "";
    }

    private static JsonElement repairValue(String key, JsonElement raw, JsonObject schema, List<String> changes) {
        if (raw == null || raw.isJsonNull() || schema == null) return raw == null ? null : raw.deepCopy();

        if (schema.has("enum") && schema.get("enum").isJsonArray() && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
            List<String> values = new ArrayList<>();
            for (JsonElement element : schema.getAsJsonArray("enum")) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) values.add(element.getAsString());
            }
            String current = raw.getAsString();
            if (!values.contains(current)) {
                RankedName match = bestName(current, values);
                if (match.accepted(ENUM_SCORE, ENUM_MARGIN)) {
                    changes.add("enum:" + key + "=" + current + "->" + match.value());
                    return new JsonPrimitive(match.value());
                }
            }
        }

        String type = schema.has("type") && schema.get("type").isJsonPrimitive()
                ? schema.get("type").getAsString() : "";
        if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
            String text = raw.getAsString().strip();
            try {
                if (("integer".equals(type) || "number".equals(type)) && text.matches("[-+]?\\d+(?:\\.\\d+)?")) {
                    JsonPrimitive value = "integer".equals(type)
                            ? new JsonPrimitive(Long.parseLong(text.contains(".") ? text.substring(0, text.indexOf('.')) : text))
                            : new JsonPrimitive(Double.parseDouble(text));
                    changes.add("type:" + key + "=string->" + type);
                    return value;
                }
            } catch (NumberFormatException ignored) {
            }
            if ("boolean".equals(type) && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
                changes.add("type:" + key + "=string->boolean");
                return new JsonPrimitive(Boolean.parseBoolean(text));
            }
        }
        return raw.deepCopy();
    }

    private static RankedName bestName(String query, List<String> candidates) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) return RankedName.none();
        List<RankedName> ranked = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) continue;
            ranked.add(new RankedName(candidate, FuzzyTextMatcher.score(query, candidate), 0));
        }
        ranked.sort(Comparator.comparingInt(RankedName::score).reversed().thenComparing(RankedName::value));
        if (ranked.isEmpty()) return RankedName.none();
        RankedName best = ranked.get(0);
        int second = ranked.size() > 1 ? ranked.get(1).score() : 0;
        return new RankedName(best.value(), best.score(), second);
    }

    private record EnumPayloadRepair(String enumValue, String payloadKey, String payloadValue) {}

    record Repair(ModelToolCall call, List<String> changes) {
        boolean changed() { return changes != null && !changes.isEmpty(); }
    }

    private record RankedName(String value, int score, int second) {
        static RankedName none() { return new RankedName("", 0, 0); }
        boolean accepted(int minimum, int margin) {
            return !value.isBlank() && score >= minimum && (score >= 970 || score - second >= margin);
        }
    }
}
