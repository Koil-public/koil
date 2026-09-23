package com.spirit.koil.api.model.cache;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.spirit.koil.api.model.ModelMessage;
import com.spirit.koil.api.model.ModelToolDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Deterministic cache identities shared by prompt construction and providers. */
public final class ModelPromptCacheIdentity {
    public static final String FORMAT_VERSION = "koil-kv-cache-v3";

    private ModelPromptCacheIdentity() {}

    public static String stablePrefix(
            String mode,
            String stableSystemPrompt,
            List<ModelToolDefinition> tools,
            String toolRegistryVersion
    ) {
        StringBuilder value = new StringBuilder(FORMAT_VERSION)
                .append("\nmode=").append(normalize(mode))
                .append("\ntoolRegistry=").append(normalize(toolRegistryVersion))
                .append("\nsystem=").append(normalize(stableSystemPrompt))
                .append("\ntools=").append(canonicalTools(tools));
        return sha256(value.toString());
    }

    /** Identity for a startup seed that may be reused beneath several exact request branches. */
    public static String seedPrefix(String kind, String prompt, List<ModelToolDefinition> tools) {
        return sha256(FORMAT_VERSION
                + "\nseed=" + normalize(kind)
                + "\nprompt=" + normalize(prompt)
                + "\ntools=" + canonicalTools(tools));
    }

    public static String fullRequest(
            String systemPrompt,
            List<ModelMessage> messages,
            List<ModelToolDefinition> tools
    ) {
        StringBuilder value = new StringBuilder(FORMAT_VERSION)
                .append("\nsystem=").append(normalize(systemPrompt))
                .append("\ntools=").append(canonicalTools(tools));
        if (messages != null) {
            for (ModelMessage message : messages) {
                if (message == null) continue;
                value.append("\nmessage=").append(message.role().name())
                        .append('\u0000').append(message.toolCallId())
                        .append('\u0000').append(message.content());
            }
        }
        return sha256(value.toString());
    }

    public static String canonicalTools(List<ModelToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) return "[]";
        List<ModelToolDefinition> ordered = new ArrayList<>(tools);
        ordered.sort(Comparator.comparing(ModelToolDefinition::id));
        StringBuilder value = new StringBuilder("[");
        for (int index = 0; index < ordered.size(); index++) {
            ModelToolDefinition tool = ordered.get(index);
            if (index > 0) value.append(',');
            value.append('{')
                    .append("id=").append(tool.id())
                    .append(";description=").append(tool.description())
                    .append(";schema=").append(canonicalJson(tool.inputSchema()))
                    .append('}');
        }
        return value.append(']').toString();
    }

    public static JsonObject canonicalObject(JsonObject input) {
        JsonElement canonical = canonicalElement(input == null ? JsonNull.INSTANCE : input);
        return canonical.isJsonObject() ? canonical.getAsJsonObject() : new JsonObject();
    }

    public static String canonicalJson(JsonElement element) {
        return canonicalElement(element == null ? JsonNull.INSTANCE : element).toString();
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalize(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static JsonElement canonicalElement(JsonElement element) {
        if (element == null || element.isJsonNull()) return JsonNull.INSTANCE;
        if (element.isJsonObject()) {
            JsonObject result = new JsonObject();
            element.getAsJsonObject().keySet().stream().sorted().forEach(key ->
                    result.add(key, canonicalElement(element.getAsJsonObject().get(key))));
            return result;
        }
        if (element.isJsonArray()) {
            JsonArray array = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) array.add(canonicalElement(child));
            return array;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) return new JsonPrimitive(primitive.getAsBoolean());
        if (primitive.isNumber()) return new JsonPrimitive(primitive.getAsNumber());
        return new JsonPrimitive(primitive.getAsString());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }
}
