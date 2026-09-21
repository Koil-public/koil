package com.spirit.koil.api.model;

import java.util.List;

/**
 * Immutable coordinator-side inspection of a model tool call. Preflight never
 * executes the capability and never grants approval. It combines the
 * authoritative tool schema, explicit execution policy, live checkable
 * preconditions and a list of domain-specific checks deferred to the registry.
 */
public record ToolPreflight(
        String toolId,
        boolean known,
        boolean executableNow,
        boolean fullyEvaluated,
        boolean readOnly,
        boolean speculativeReadAllowed,
        boolean preparationAllowed,
        boolean confirmationRequired,
        boolean reversible,
        long observationEpoch,
        List<String> blockers,
        List<String> deferredPreconditions,
        List<String> preconditions,
        ToolExecutionPolicy executionPolicy
) {
    public ToolPreflight {
        toolId = toolId == null ? "" : toolId.trim();
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        deferredPreconditions = deferredPreconditions == null ? List.of() : List.copyOf(deferredPreconditions);
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        executionPolicy = executionPolicy == null ? ToolExecutionPolicy.conservative() : executionPolicy;
    }

    public boolean blocked() {
        return !this.executableNow || !this.blockers.isEmpty();
    }

    /** True when execution can proceed but the registry still owns one or more
     * semantic checks that generic preflight cannot prove. */
    public boolean conditional() {
        return !blocked() && !this.fullyEvaluated;
    }
}
