package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactInspection;

/** One contained architecture recognizer/descriptor registered with the universal runtime. */
public interface KoilArchitectureModule {
    String id();

    int priority();

    boolean matches(ModelArtifactInspection inspection);

    KoilArchitectureDescriptor describe(ModelArtifactInspection inspection);
}
