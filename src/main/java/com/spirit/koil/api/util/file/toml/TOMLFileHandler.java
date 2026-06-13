package com.spirit.koil.api.util.file.toml;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class TOMLFileHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static JsonObject parseTomlFile(File file) throws IOException {
        JsonObject root = new JsonObject();
        JsonObject currentTable = root;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                String line = stripComment(rawLine).trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("[") && line.endsWith("]")) {
                    String tablePath = line.substring(1, line.length() - 1).trim();
                    currentTable = ensureTable(root, tablePath);
                    continue;
                }

                int separator = line.indexOf('=');
                if (separator < 0) continue;

                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                currentTable.add(key, parseValue(value));
            }
        }

        return root;
    }

    public static void saveTomlFile(JsonObject jsonObject, File file) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writeTable(writer, "", jsonObject);
        }
    }

    private static void writeTable(BufferedWriter writer, String prefix, JsonObject object) throws IOException {
        boolean wroteHeader = false;
        if (!prefix.isEmpty()) {
            writer.write("[" + prefix + "]");
            writer.newLine();
            wroteHeader = true;
        }

        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue().isJsonObject()) continue;
            writer.write(entry.getKey() + " = " + toTomlValue(entry.getValue()));
            writer.newLine();
        }

        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            if (wroteHeader || !prefix.isEmpty()) {
                writer.newLine();
            }
            String childPrefix = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            writeTable(writer, childPrefix, entry.getValue().getAsJsonObject());
            wroteHeader = true;
        }
    }

    private static JsonObject ensureTable(JsonObject root, String tablePath) {
        JsonObject current = root;
        for (String part : tablePath.split("\\.")) {
            if (!current.has(part) || !current.get(part).isJsonObject()) {
                current.add(part, new JsonObject());
            }
            current = current.getAsJsonObject(part);
        }
        return current;
    }

    private static JsonElement parseValue(String raw) {
        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            return new JsonPrimitive(unescape(raw.substring(1, raw.length() - 1)));
        }
        if (raw.startsWith("'") && raw.endsWith("'")) {
            return new JsonPrimitive(raw.substring(1, raw.length() - 1));
        }
        if (raw.startsWith("[") && raw.endsWith("]")) {
            JsonArray array = new JsonArray();
            String body = raw.substring(1, raw.length() - 1).trim();
            if (!body.isEmpty()) {
                for (String part : splitArray(body)) {
                    array.add(parseValue(part.trim()));
                }
            }
            return array;
        }
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return new JsonPrimitive(Boolean.parseBoolean(raw));
        }
        try {
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                return new JsonPrimitive(Double.parseDouble(raw));
            }
            return new JsonPrimitive(Long.parseLong(raw));
        } catch (NumberFormatException ignored) {
        }
        return new JsonPrimitive(raw);
    }

    private static String toTomlValue(JsonElement element) {
        if (element == null || element.isJsonNull()) return "\"\"";
        if (element.isJsonArray()) {
            StringBuilder builder = new StringBuilder("[");
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) builder.append(", ");
                builder.append(toTomlValue(array.get(i)));
            }
            builder.append("]");
            return builder.toString();
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean() || primitive.isNumber()) return primitive.getAsString();
            return GSON.toJson(primitive.getAsString());
        }
        return GSON.toJson(element.toString());
    }

    private static String stripComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && !inSingle && (i == 0 || line.charAt(i - 1) != '\\')) inDouble = !inDouble;
            if (c == '\'' && !inDouble && (i == 0 || line.charAt(i - 1) != '\\')) inSingle = !inSingle;
            if (c == '#' && !inSingle && !inDouble) return line.substring(0, i);
        }
        return line;
    }

    private static String[] splitArray(String body) {
        return body.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t");
    }
}
