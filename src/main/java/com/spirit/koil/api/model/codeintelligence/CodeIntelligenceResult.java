package com.spirit.koil.api.model.codeintelligence;

import com.google.gson.JsonObject;

/** Bounded provider evidence; unavailable is distinct from an empty structural result. */
public record CodeIntelligenceResult(
    String status,
    JsonObject data,
    String providerId,
    String failureCode,
    String detail,
    boolean truncated
) {
    public CodeIntelligenceResult {
        status = status == null ? "failed" : status;
        data = data == null ? new JsonObject() : data.deepCopy();
        providerId = providerId == null ? "" : providerId;
        failureCode = failureCode == null ? "" : failureCode;
        detail = detail == null ? "" : detail;
    }

    public static CodeIntelligenceResult unavailable(String providerId, String code, String detail) {
        return new CodeIntelligenceResult("unavailable", new JsonObject(), providerId, code, detail, false);
    }
}
