package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.ModelMessage;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.StreamingModelRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adapts Koil's stable dotted capability identifiers to the restricted
 * OpenAI-compatible function-name alphabet used by llama.cpp grammars.
 */
final class LlamaCppToolNameMap {
    private static final int MAX_WIRE_LENGTH = 64;
    private final Map<String, String> canonicalToWire = new LinkedHashMap<>();
    private final Map<String, String> wireToCanonical = new LinkedHashMap<>();

    static LlamaCppToolNameMap from(StreamingModelRequest request) {
        LlamaCppToolNameMap names = new LlamaCppToolNameMap();
        for (ModelToolDefinition definition : request.tools()) {
            names.toWire(definition.id());
        }
        for (ModelMessage message : request.messages()) {
            String toolName = message.metadata().getOrDefault("tool_name", "");
            if (!toolName.isBlank()) {
                names.toWire(toolName);
            }
        }
        return names;
    }

    String toWire(String canonicalName) {
        String canonical = canonicalName == null ? "" : canonicalName.trim();
        if (canonical.isEmpty()) {
            return "";
        }
        String existing = this.canonicalToWire.get(canonical);
        if (existing != null) {
            return existing;
        }
        String base = sanitize(canonical);
        String candidate = base;
        int collision = 0;
        while (this.wireToCanonical.containsKey(candidate)
                && !canonical.equals(this.wireToCanonical.get(candidate))) {
            collision++;
            String suffix = "_" + Integer.toUnsignedString(canonical.hashCode(), 36)
                    + (collision == 1 ? "" : "_" + collision);
            int prefixLength = Math.max(1, MAX_WIRE_LENGTH - suffix.length());
            candidate = base.substring(0, Math.min(base.length(), prefixLength)) + suffix;
        }
        this.canonicalToWire.put(canonical, candidate);
        this.wireToCanonical.put(candidate, canonical);
        return candidate;
    }

    String toCanonical(String wireName) {
        String wire = wireName == null ? "" : wireName.trim();
        return this.wireToCanonical.getOrDefault(wire, wire);
    }

    private static String sanitize(String canonical) {
        StringBuilder sanitized = new StringBuilder(canonical.length());
        for (int index = 0; index < canonical.length(); index++) {
            char value = canonical.charAt(index);
            if ((value >= 'a' && value <= 'z')
                    || (value >= 'A' && value <= 'Z')
                    || (value >= '0' && value <= '9')
                    || value == '_'
                    || value == '-') {
                sanitized.append(value);
            } else {
                sanitized.append('_');
            }
        }
        if (sanitized.isEmpty()) {
            sanitized.append("tool");
        }
        if (sanitized.length() > MAX_WIRE_LENGTH) {
            sanitized.setLength(MAX_WIRE_LENGTH);
        }
        return sanitized.toString();
    }
}
