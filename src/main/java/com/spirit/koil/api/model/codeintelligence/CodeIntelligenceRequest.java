package com.spirit.koil.api.model.codeintelligence;

import java.nio.file.Path;

/** A bounded read-only request against one approved workspace root. */
public record CodeIntelligenceRequest(
    CodeIntelligenceOperation operation,
    Path workspaceRoot,
    String query,
    int limit,
    int depth,
    int maximumCharacters
) {
    public CodeIntelligenceRequest {
        operation = operation == null ? CodeIntelligenceOperation.SEARCH : operation;
        workspaceRoot = workspaceRoot == null ? Path.of(".") : workspaceRoot.toAbsolutePath().normalize();
        query = query == null ? "" : query.strip();
        limit = clamp(limit, 1, 50);
        depth = clamp(depth, 1, 5);
        maximumCharacters = clamp(maximumCharacters, 256, 24_000);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
