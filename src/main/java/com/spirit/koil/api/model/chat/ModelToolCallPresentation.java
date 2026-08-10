package com.spirit.koil.api.model.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Human-readable, bounded presentation for canonical tool calls. */
public final class ModelToolCallPresentation {
    private static final int MAXIMUM_FIELDS = 8;

    private ModelToolCallPresentation() {
    }

    public static String toolName(String toolId) {
        String value = toolId == null ? "" : toolId.strip();
        if (value.isBlank()) return "Action";
        String[] words = value.replace('.', ' ').replace('_', ' ').split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    public static String callSummary(ModelToolCall call) {
        if (call == null) return "Action";
        String fields = arguments(call.arguments());
        return toolName(call.toolId()) + (fields.isBlank() ? "" : " — " + fields);
    }

    public static String arguments(JsonObject arguments) {
        if (arguments == null || arguments.size() == 0) return "";
        List<String> fields = new ArrayList<>();
        flatten("", arguments, fields);
        return String.join(" | ", fields);
    }

    private static void flatten(String prefix, JsonObject object, List<String> output) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (output.size() >= MAXIMUM_FIELDS) {
                output.add("More settings omitted");
                return;
            }
            String key = prefix.isBlank() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonObject()) {
                flatten(key, value.getAsJsonObject(), output);
                continue;
            }
            output.add(label(key) + ": " + compact(displayValue(key, value), 96));
        }
    }

    private static String label(String key) {
        String normalized = key == null ? "" : key.replace('_', ' ').replace('.', ' ').strip();
        if (normalized.isBlank()) return "Value";
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.equals("id")) return "ID";
        if (lower.endsWith(" id")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static String displayValue(String key, JsonElement value) {
        if (value == null || value.isJsonNull()) return "none";
        if (value.isJsonPrimitive()) {
            String raw = value.getAsJsonPrimitive().getAsString();
            String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
            if (normalizedKey.contains("path") || normalizedKey.contains("file") || normalizedKey.contains("directory")) {
                return concisePath(raw);
            }
            return raw;
        }
        if (value.isJsonArray()) {
            List<String> values = new ArrayList<>();
            for (JsonElement element : value.getAsJsonArray()) values.add(displayValue(key, element));
            return String.join(", ", values);
        }
        return "structured settings";
    }

    private static String concisePath(String raw) {
        String value = raw == null ? "" : raw.replace('\\', '/').strip();
        if (value.isBlank()) return value;
        String[] parts = value.split("/");
        if (parts.length <= 3) return value;
        return "…/" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    private static String compact(String value, int maximum) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return clean.length() <= maximum ? clean : clean.substring(0, maximum - 1) + "…";
    }
}
