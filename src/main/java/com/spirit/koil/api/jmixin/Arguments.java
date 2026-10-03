package com.spirit.koil.api.jmixin;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;

public final class Arguments {
    private Arguments() {}

    public static JsonElement get(JsonObject args, String key) {
        if (!args.has(key) || args.get(key).isJsonNull())
            throw new IllegalArgumentException(
                "Missing argument: " + key
            );

        return args.get(key);
    }

    public static String getString(JsonObject args, String key) {
        return get(args, key).getAsString();
    }

    public static int getInt(JsonObject args, String key) {
        return get(args, key).getAsInt();
    }

    public static double getDouble(JsonObject args, String key) {
        return get(args, key).getAsDouble();
    }

    public static boolean getBoolean(JsonObject args, String key) {
        return get(args, key).getAsBoolean();
    }

    public static JsonObject getObject(JsonObject args, String key) {
        return get(args, key).getAsJsonObject();
    }
}
