package com.spirit.koil.api.model.runtime.universal;

import java.util.List;
import java.util.Set;

/** Immutable normalized graph produced from architecture metadata plus actual tensor descriptors. */
public record KoilModelGraph(
        String architectureId,
        KoilArchitectureCategory category,
        List<KoilGraphNode> nodes,
        Set<KoilOperatorKind> requiredOperators,
        Set<KoilModelStateKind> persistentState,
        List<KoilGraphDiagnostic> diagnostics,
        boolean structurallyComplete,
        String evidence
) {
    public KoilModelGraph {
        architectureId = architectureId == null ? "" : architectureId.strip();
        category = category == null ? KoilArchitectureCategory.UNKNOWN : category;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        requiredOperators = requiredOperators == null ? Set.of() : Set.copyOf(requiredOperators);
        persistentState = persistentState == null ? Set.of() : Set.copyOf(persistentState);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        evidence = evidence == null ? "" : evidence.strip();
    }

    public boolean valid() {
        return structurallyComplete && diagnostics.stream().noneMatch(d -> d.severity() == KoilGraphDiagnostic.Severity.ERROR);
    }
}
