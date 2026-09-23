package com.spirit.koil.api.model.runtime.universal;

/** Import progress is intentionally separate from executable-adapter/backend support. */
public enum KoilImportReadiness {
    UNAVAILABLE,
    METADATA_ONLY,
    TENSOR_MANIFEST_READY,
    ARCHITECTURE_MAPPED,
    GRAPH_BUILT,
    GRAPH_VALIDATED,
    TENSOR_ROLES_RESOLVED,
    TENSOR_ROLES_VALIDATED
}
