package com.spirit.koil.api.model.runtime.universal;

import java.util.Set;

/**
 * Architecture-level facts inferred from model metadata. This record describes computation only;
 * it deliberately does not claim that any Koil backend can execute the model.
 */
public record KoilArchitectureDescriptor(
        String architectureId,
        KoilArchitectureCategory category,
        Set<KoilOperatorKind> requiredOperators,
        Set<KoilModelStateKind> persistentState,
        boolean mixtureOfExperts,
        String evidence
) {
    public KoilArchitectureDescriptor {
        architectureId = architectureId == null ? "" : architectureId.strip();
        category = category == null ? KoilArchitectureCategory.UNKNOWN : category;
        requiredOperators = requiredOperators == null ? Set.of() : Set.copyOf(requiredOperators);
        persistentState = persistentState == null ? Set.of() : Set.copyOf(persistentState);
        evidence = evidence == null ? "" : evidence.strip();
    }

    public boolean recognized() {
        return category != KoilArchitectureCategory.UNKNOWN;
    }
}
