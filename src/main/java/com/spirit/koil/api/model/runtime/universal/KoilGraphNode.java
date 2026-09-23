package com.spirit.koil.api.model.runtime.universal;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** One normalized computation unit. It describes semantics, not a backend kernel implementation. */
public record KoilGraphNode(
        String id,
        KoilGraphStage stage,
        int layerIndex,
        Set<KoilOperatorKind> operators,
        Set<KoilModelStateKind> persistentState,
        List<String> tensorNames,
        Map<String, String> attributes
) {
    public KoilGraphNode {
        id = id == null ? "" : id.strip();
        stage = stage == null ? KoilGraphStage.UNKNOWN : stage;
        layerIndex = Math.max(-1, layerIndex);
        operators = operators == null ? Set.of() : Set.copyOf(operators);
        persistentState = persistentState == null ? Set.of() : Set.copyOf(persistentState);
        tensorNames = tensorNames == null ? List.of() : List.copyOf(tensorNames);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
