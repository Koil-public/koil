package com.spirit.koil.api.model.tool;

import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class ModelToolApproval {
    private ModelToolApproval() {}

    static CompletableFuture<Boolean> require(UUID displayRequestId, ModelToolCall call, boolean preapproved,
                                              String title, String detail) {
        if (!AutomationModeController.isAutomationMode()) return CompletableFuture.completedFuture(false);
        if (preapproved || AutomationModeController.isUnrestrictedMode()) return CompletableFuture.completedFuture(true);
        if (displayRequestId == null) return CompletableFuture.completedFuture(false);
        String safeDetail = detail == null || detail.isBlank() ? (call == null ? "Execute tool" : call.toolId()) : detail;
        return ModelGenerationHudState.requestApproval(displayRequestId, title, safeDetail, "Run", "Deny");
    }
}
