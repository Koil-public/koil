package com.spirit.koil.api.automation;

import java.util.UUID;

public record AutomationRequest(String rawInput, boolean runCommand, boolean directTemplate, UUID executionId) {
    public AutomationRequest {
        rawInput = rawInput == null ? "" : rawInput;
        executionId = executionId == null ? UUID.randomUUID() : executionId;
    }

    public AutomationRequest(String rawInput, boolean runCommand, boolean directTemplate) {
        this(rawInput, runCommand, directTemplate, UUID.randomUUID());
    }
}
