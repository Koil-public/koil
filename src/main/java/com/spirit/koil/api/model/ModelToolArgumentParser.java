package com.spirit.koil.api.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Deterministic, provider-neutral repair for structurally unambiguous compact-model JSON mistakes. */
public final class ModelToolArgumentParser {
    private ModelToolArgumentParser() {}

    public static JsonObject parseObject(String raw) {
        String source = raw == null ? "" : raw.strip();
        if (source.isBlank()) return new JsonObject();
        RuntimeException original;
        try { return object(JsonParser.parseString(source)); }
        catch (RuntimeException failure) { original = failure; }
        String repaired = source;
        if (repaired.startsWith("```")) {
            repaired = repaired.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").strip();
        }
        repaired = repaired.replaceAll(",\\s*([}\\]])", "$1");
        int braces = balance(repaired, '{', '}');
        int brackets = balance(repaired, '[', ']');
        if (braces > 0 || brackets > 0) repaired += "]".repeat(Math.max(0, brackets)) + "}".repeat(Math.max(0, braces));
        try { return object(JsonParser.parseString(repaired)); }
        catch (RuntimeException ignored) { throw original; }
    }

    private static JsonObject object(JsonElement parsed) {
        if (parsed == null || !parsed.isJsonObject()) throw new IllegalArgumentException("Tool arguments were not a JSON object.");
        return parsed.getAsJsonObject();
    }

    private static int balance(String value, char open, char close) {
        int balance = 0; boolean quoted = false; boolean escaped = false;
        for (int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if (escaped) { escaped=false; continue; }
            if (c=='\\' && quoted) { escaped=true; continue; }
            if (c=='"') { quoted=!quoted; continue; }
            if (!quoted) { if(c==open) balance++; else if(c==close) balance--; }
        }
        return Math.max(0, balance);
    }
}
