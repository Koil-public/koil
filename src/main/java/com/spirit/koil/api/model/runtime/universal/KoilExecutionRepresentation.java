package com.spirit.koil.api.model.runtime.universal;

import java.util.Set;

/**
 * Runtime-neutral imported representation. This is evidence for later graph building and hardware
 * planning; it does not claim Koil has native kernels for the required operators.
 */
public record KoilExecutionRepresentation(
        KoilImportReadiness readiness,
        KoilModelProfile model,
        KoilTensorManifestSummary tensors,
        KoilQuantizationProfile quantization,
        KoilTokenizerDescriptor tokenizer,
        KoilModelGraph graph,
        KoilTensorResolution tensorResolution,
        Set<KoilOperatorKind> requiredOperators,
        Set<KoilModelStateKind> persistentState,
        String evidence
) {
    public KoilExecutionRepresentation {
        readiness = readiness == null ? KoilImportReadiness.UNAVAILABLE : readiness;
        graph = graph == null ? new KoilModelGraph("", KoilArchitectureCategory.UNKNOWN, java.util.List.of(), Set.of(), Set.of(), java.util.List.of(), false, "graph unavailable") : graph;
        tensorResolution = tensorResolution == null ? new KoilTensorResolution(java.util.List.of(), Set.of(), java.util.List.of(), false, "tensor role resolution unavailable") : tensorResolution;
        requiredOperators = requiredOperators == null ? Set.of() : Set.copyOf(requiredOperators);
        persistentState = persistentState == null ? Set.of() : Set.copyOf(persistentState);
        evidence = evidence == null ? "" : evidence.strip();
    }

    public KoilExecutionCompatibility analyzeCompatibility(
            KoilExecutionAdapterDescriptor adapter, KoilRuntimeBackend backend) {
        return KoilExecutionCompatibilityAnalyzer.analyze(this, adapter, backend);
    }

    public boolean executionGraphReady() {
        return graph != null && graph.valid() && readiness.ordinal() >= KoilImportReadiness.GRAPH_VALIDATED.ordinal();
    }
}
