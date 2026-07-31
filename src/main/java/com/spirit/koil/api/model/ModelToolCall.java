package com.spirit.koil.api.model;

import com.google.gson.JsonObject;

public record ModelToolCall(String id, String toolId, JsonObject arguments) {
    public ModelToolCall {
        id = id == null ? "" : id.trim();
        toolId = toolId == null ? "" : toolId.trim();
        arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
    }
}
