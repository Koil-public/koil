package com.spirit.koil.api.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Session-scoped, validated tool evidence that may become authoritative input
 * to a later predicted call. Evidence is admitted only from completed and
 * validated tool results. Ambiguous values are deliberately not resolved.
 */
final class VerifiedToolEvidence {
    private final String callId;
    private final String toolId;
    private final long observationEpoch;
    private final long capturedAtMillis;
    private final JsonObject output;

    private VerifiedToolEvidence(
            String callId,
            String toolId,
            long observationEpoch,
            long capturedAtMillis,
            JsonObject output
    ) {
        this.callId = clean(callId);
        this.toolId = clean(toolId);
        this.observationEpoch = observationEpoch;
        this.capturedAtMillis = capturedAtMillis;
        this.output = output == null ? new JsonObject() : output.deepCopy();
    }

    static VerifiedToolEvidence from(ModelToolCall call, ModelToolResult result, long observationEpoch) {
        if (call == null || result == null || !result.completedAndValidated()) return null;
        return new VerifiedToolEvidence(
                call.id(),
                call.toolId(),
                observationEpoch,
                result.completedAtMillis(),
                result.output()
        );
    }

    String callId() {
        return callId;
    }

    String toolId() {
        return toolId;
    }

    long observationEpoch() {
        return observationEpoch;
    }

    long capturedAtMillis() {
        return capturedAtMillis;
    }

    JsonObject output() {
        return output.deepCopy();
    }

    String workspace() {
        return primitiveString(output.get("workspace"));
    }

    String uniquePath() {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        add(paths, primitiveString(output.get("path")));
        collectStrings(output.get("matches"), "path", paths);
        collectStrings(output.get("results"), "path", paths);
        collectStrings(output.get("files"), "path", paths);
        paths.removeIf(value -> value.equals(".") || value.equals("/") || value.isBlank());
        return paths.size() == 1 ? paths.iterator().next() : "";
    }

    String uniqueSymbol() {
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        for (String key : List.of("symbol", "qualifiedName", "qualified_name", "name")) {
            add(symbols, primitiveString(output.get(key)));
        }
        for (String collection : List.of("matches", "results", "symbols")) {
            JsonElement element = output.get(collection);
            if (element == null || !element.isJsonArray()) continue;
            for (JsonElement item : element.getAsJsonArray()) {
                if (!item.isJsonObject()) continue;
                JsonObject object = item.getAsJsonObject();
                for (String key : List.of("symbol", "qualifiedName", "qualified_name")) {
                    add(symbols, primitiveString(object.get(key)));
                }
            }
        }
        return symbols.size() == 1 ? symbols.iterator().next() : "";
    }

    private static void collectStrings(JsonElement element, String key, Set<String> values) {
        if (element == null || !element.isJsonArray()) return;
        JsonArray array = element.getAsJsonArray();
        for (JsonElement item : array) {
            if (!item.isJsonObject()) continue;
            add(values, primitiveString(item.getAsJsonObject().get(key)));
        }
    }

    private static String primitiveString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return "";
        return clean(element.getAsString());
    }

    private static void add(Set<String> values, String value) {
        String cleaned = clean(value);
        if (!cleaned.isBlank()) values.add(cleaned);
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
