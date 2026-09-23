package com.spirit.koil.api.model.runtime.universal;

/** Internal execution adapters implement this when they can consume Koil's typed execution policy. */
public interface KoilExecutionPlanConsumer {
    void applyKoilExecutionPlan(KoilExecutionPlan plan);
}
