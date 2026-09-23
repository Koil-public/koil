package com.spirit.koil.api.model;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * Side-effect-free preview of a concrete mutation. Resource fingerprints bind
 * the preview to the exact state that was inspected, while postconditions
 * describe the state that must be observed after commit.
 */
public record StagedToolPayload(
        String toolId,
        Map<String, String> resourceFingerprints,
        JsonObject preview,
        List<ToolPostcondition> postconditions
) {
    public StagedToolPayload {
        toolId = toolId == null ? "" : toolId.strip();
        resourceFingerprints = resourceFingerprints == null ? Map.of() : Map.copyOf(resourceFingerprints);
        preview = preview == null ? new JsonObject() : preview.deepCopy();
        postconditions = postconditions == null ? List.of() : List.copyOf(postconditions);
    }

    public boolean hasPostconditions() {
        return !this.postconditions.isEmpty();
    }
}
