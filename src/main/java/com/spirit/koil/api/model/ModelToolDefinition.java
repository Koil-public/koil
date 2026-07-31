package com.spirit.koil.api.model;

import com.google.gson.JsonObject;

import java.time.Duration;
import java.util.List;
import java.util.Set;

public record ModelToolDefinition(
        String id,
        String description,
        JsonObject inputSchema,
        List<String> preconditions,
        Set<String> sideEffects,
        boolean reversible,
        Duration timeout,
        boolean cancellationSupported,
        boolean confirmationRequired,
        Set<String> resultStates
) {
    public ModelToolDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("tool id is required");
        }
        id = id.trim();
        description = description == null ? "" : description.trim();
        inputSchema = inputSchema == null ? new JsonObject() : inputSchema.deepCopy();
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        sideEffects = sideEffects == null ? Set.of() : Set.copyOf(sideEffects);
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(30) : timeout;
        resultStates = resultStates == null ? Set.of() : Set.copyOf(resultStates);
    }
}
