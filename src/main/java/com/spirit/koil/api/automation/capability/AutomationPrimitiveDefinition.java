package com.spirit.koil.api.automation.capability;

public record AutomationPrimitiveDefinition(
        String id,
        String category,
        String description,
        boolean clientThreadRequired,
        boolean cancellationSupported,
        boolean modelExposed
) {
    public AutomationPrimitiveDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("primitive id is required");
        }
        id = id.trim();
        category = category == null ? "" : category.trim();
        description = description == null ? "" : description.trim();
    }
}
