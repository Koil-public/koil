package com.spirit.koil.api.model;

import com.google.gson.JsonObject;

import java.util.List;

public record ModelToolResult(
        String callId,
        String toolId,
        String status,
        JsonObject output,
        String failureCode,
        String detail,
        long startedAtMillis,
        long completedAtMillis,
        String validationStatus,
        List<String> changedTargets,
        boolean retryable,
        boolean cancelled,
        String approvalStatus
) {
    public ModelToolResult(
            String callId,
            String toolId,
            String status,
            JsonObject output,
            String failureCode,
            String detail
    ) {
        this(
                callId,
                toolId,
                status,
                output,
                failureCode,
                detail,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "not_required",
                List.of(),
                "failed".equals(status) || "timed_out".equals(status) || "stale".equals(status),
                "cancelled".equals(status),
                "not_required"
        );
    }

    public ModelToolResult {
        callId = callId == null ? "" : callId.trim();
        toolId = toolId == null ? "" : toolId.trim();
        status = status == null ? "failed" : status.trim();
        output = output == null ? new JsonObject() : output.deepCopy();
        failureCode = failureCode == null ? "" : failureCode.trim();
        detail = detail == null ? "" : detail;
        long now = System.currentTimeMillis();
        startedAtMillis = startedAtMillis <= 0L ? now : startedAtMillis;
        completedAtMillis = completedAtMillis <= 0L ? startedAtMillis : Math.max(startedAtMillis, completedAtMillis);
        validationStatus = validationStatus == null || validationStatus.isBlank()
                ? "not_required"
                : validationStatus.trim();
        changedTargets = changedTargets == null ? List.of() : List.copyOf(changedTargets);
        approvalStatus = approvalStatus == null || approvalStatus.isBlank()
                ? "not_required"
                : approvalStatus.trim();
    }

    public long durationMillis() {
        return Math.max(0L, this.completedAtMillis - this.startedAtMillis);
    }

    public boolean completedAndValidated() {
        return "completed".equals(this.status)
                && ("passed".equals(this.validationStatus) || "not_required".equals(this.validationStatus));
    }
}
