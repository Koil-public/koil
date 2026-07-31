package com.spirit.koil.api.model;

import com.google.gson.JsonObject;

public record ModelToolResult(
        String callId,
        String toolId,
        String status,
        JsonObject output,
        String failureCode,
        String detail
) {
    public ModelToolResult {
        callId = callId == null ? "" : callId.trim();
        toolId = toolId == null ? "" : toolId.trim();
        status = status == null ? "failed" : status.trim();
        output = output == null ? new JsonObject() : output.deepCopy();
        failureCode = failureCode == null ? "" : failureCode.trim();
        detail = detail == null ? "" : detail;
    }
}
