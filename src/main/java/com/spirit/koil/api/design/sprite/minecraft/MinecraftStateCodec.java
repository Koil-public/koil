package com.spirit.koil.api.design.sprite.minecraft;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Property;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Compact BlockState codec retained only for legacy compatibility and serialization. */
public final class MinecraftStateCodec {
    private MinecraftStateCodec() { }

    public static BlockState resolve(Block block, String signature) {
        if (block == null) return null;
        BlockState state = block.getDefaultState();
        if (signature == null || signature.isBlank()) return state;
        Map<String, String> values = parse(signature);
        for (Property<?> property : block.getStateManager().getProperties()) {
            String value = values.get(property.getName().toLowerCase(Locale.ROOT));
            if (value != null) state = apply(state, property, value);
        }
        return state;
    }

    public static String encode(BlockState state) {
        if (state == null) return "";
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
            values.put(entry.getKey().getName().toLowerCase(Locale.ROOT),
                    String.valueOf(entry.getValue()).toLowerCase(Locale.ROOT));
        }
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    /** Applies one state property by its Minecraft serialized name. */
    public static BlockState withProperty(BlockState state, String key, String value) {
        if (state == null || key == null || value == null) return state;
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equalsIgnoreCase(key)) return apply(state, property, value);
        }
        return state;
    }

    public static boolean hasProperty(BlockState state, String key) {
        if (state == null || key == null) return false;
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private static Map<String, String> parse(String signature) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String token : signature.split("[,;]")) {
            int equals = token.indexOf('=');
            if (equals <= 0) continue;
            String key = token.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = token.substring(equals + 1).trim().toLowerCase(Locale.ROOT);
            if (!key.isBlank() && !value.isBlank()) values.put(key, value);
        }
        return values;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState apply(BlockState state, Property property, String value) {
        try {
            Optional parsed = property.parse(value);
            if (parsed.isPresent()) return state.with(property, (Comparable) parsed.get());
        } catch (RuntimeException ignored) { }
        return state;
    }
}
