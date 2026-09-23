package com.spirit.koil.api.model.runtime.universal;

import java.util.List;

/** One inert tensor descriptor bound to a semantic role. */
public record KoilTensorBinding(
        KoilTensorRole role,
        String tensorName,
        int layerIndex,
        List<Long> shape,
        String storageType,
        boolean validShape,
        String evidence
) {
    public KoilTensorBinding {
        role = role == null ? KoilTensorRole.UNKNOWN : role;
        tensorName = tensorName == null ? "" : tensorName.strip();
        layerIndex = Math.max(-1, layerIndex);
        shape = shape == null ? List.of() : List.copyOf(shape);
        storageType = storageType == null || storageType.isBlank() ? "UNKNOWN" : storageType.strip();
        evidence = evidence == null ? "" : evidence.strip();
    }
}
