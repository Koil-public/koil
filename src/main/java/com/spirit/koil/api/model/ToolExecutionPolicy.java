package com.spirit.koil.api.model;

/**
 * Explicit runtime policy for one model-facing tool definition.
 *
 * <p>This is deliberately separate from {@code reversible}. Reversibility
 * describes whether an already committed side effect can be undone. Preparation
 * describes whether Koil may validate/stage work before commit, while
 * speculation describes whether Koil may execute the tool before the model has
 * emitted the authoritative call.</p>
 */
public record ToolExecutionPolicy(
        SpeculationMode speculation,
        PreparationMode preparation,
        FreshnessMode freshness,
        CostClass cost
) {
    public ToolExecutionPolicy {
        speculation = speculation == null ? SpeculationMode.NEVER : speculation;
        preparation = preparation == null ? PreparationMode.NEVER : preparation;
        freshness = freshness == null ? FreshnessMode.SESSION : freshness;
        cost = cost == null ? CostClass.MODERATE : cost;
    }

    public static ToolExecutionPolicy conservative() {
        return new ToolExecutionPolicy(
                SpeculationMode.NEVER,
                PreparationMode.NEVER,
                FreshnessMode.SESSION,
                CostClass.MODERATE
        );
    }

    public static ToolExecutionPolicy readOnly(FreshnessMode freshness, CostClass cost) {
        return new ToolExecutionPolicy(
                SpeculationMode.READ_ONLY,
                PreparationMode.NEVER,
                freshness,
                cost
        );
    }

    public static ToolExecutionPolicy validateOnlyMutation(CostClass cost) {
        return new ToolExecutionPolicy(
                SpeculationMode.NEVER,
                PreparationMode.VALIDATE_ONLY,
                FreshnessMode.WORKSPACE,
                cost
        );
    }

    public static ToolExecutionPolicy stagedMutation(CostClass cost) {
        return new ToolExecutionPolicy(
                SpeculationMode.NEVER,
                PreparationMode.STAGE_PAYLOAD,
                FreshnessMode.WORKSPACE,
                cost
        );
    }

    public boolean allowsSpeculativeRead() {
        return this.speculation == SpeculationMode.READ_ONLY;
    }

    public boolean allowsPreparation() {
        return this.preparation != PreparationMode.NEVER;
    }

    public enum SpeculationMode {
        NEVER,
        READ_ONLY
    }

    /**
     * VALIDATE_ONLY means Koil may preflight and retain an exact invocation
     * token, but it has not staged a mutation payload and has performed no side
     * effect. STAGE_PAYLOAD means the registry can also build a true
     * side-effect-free mutation preview with optimistic concurrency
     * fingerprints and predicted postconditions.
     */
    public enum PreparationMode {
        NEVER,
        VALIDATE_ONLY,
        STAGE_PAYLOAD
    }

    public enum FreshnessMode {
        /** Bundled/static evidence that cannot change during this process. */
        IMMUTABLE,
        /** Data tied to files, source graphs, or the current workspace state. */
        WORKSPACE,
        /** Data tied to the current server/client connection but not every tick. */
        CONNECTION,
        /** Tick-sensitive player/world/target state. */
        LIVE,
        /** Public-network evidence whose remote source may change independently. */
        REMOTE,
        /** Session-scoped evidence without a stronger invalidation signal. */
        SESSION
    }

    public enum CostClass {
        CHEAP,
        MODERATE,
        EXPENSIVE
    }
}
