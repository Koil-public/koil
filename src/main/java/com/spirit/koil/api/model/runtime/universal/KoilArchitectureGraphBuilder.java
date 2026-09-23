package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactInspection;

/** Architecture-specific lowering from inert artifact evidence to Koil's backend-neutral graph. */
public interface KoilArchitectureGraphBuilder {
    String id();
    int priority();
    boolean supports(KoilArchitectureDescriptor architecture, ModelArtifactInspection inspection);
    KoilModelGraph build(KoilArchitectureDescriptor architecture, ModelArtifactInspection inspection);
}
