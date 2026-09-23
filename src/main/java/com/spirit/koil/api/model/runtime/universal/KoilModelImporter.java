package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactFormat;
import com.spirit.koil.api.model.catalog.ModelArtifactInspection;
import com.spirit.koil.api.model.catalog.ModelArtifactInspector;

import java.nio.file.Path;
import java.util.Set;

/**
 * Canonical architecture-neutral import entrypoint.
 *
 * <p>The importer performs only inert parsing: metadata, tokenizer/template descriptors and tensor
 * headers. It never imports or executes remote model code. Graph lowering and tensor-role resolution are inert import stages; native execution remains a
 * separate capability decision and must advertise its own operator/state/backend coverage.</p>
 */
public final class KoilModelImporter {
    private KoilModelImporter() {}

    public static KoilExecutionRepresentation importModel(Path source) {
        return build(ModelArtifactInspector.inspect(source));
    }

    public static KoilExecutionRepresentation importModel(Path source, ModelArtifactFormat format) {
        return build(ModelArtifactInspector.inspect(source, format));
    }

    public static KoilExecutionRepresentation build(ModelArtifactInspection inspection) {
        KoilModelProfile profile = KoilModelProfiler.profile(inspection);
        KoilTensorManifestSummary tensors = KoilTensorManifestSummary.from(inspection);
        KoilQuantizationProfile quantization = KoilQuantizationProfile.infer(inspection, tensors);
        KoilTokenizerDescriptor tokenizer = KoilTokenizerDescriptor.from(inspection);

        KoilImportReadiness readiness;
        KoilModelGraph graph = null;
        if (inspection == null || !inspection.present()) readiness = KoilImportReadiness.UNAVAILABLE;
        else if (!tensors.complete() || tensors.tensorCount() <= 0) readiness = KoilImportReadiness.METADATA_ONLY;
        else if (!profile.architectureRecognized()) readiness = KoilImportReadiness.TENSOR_MANIFEST_READY;
        else {
            readiness = KoilImportReadiness.ARCHITECTURE_MAPPED;
            graph = KoilArchitectureGraphRegistry.defaultRegistry().build(profile.architecture(), inspection);
            if (graph != null && !graph.nodes().isEmpty()) readiness = KoilImportReadiness.GRAPH_BUILT;
            if (graph != null && graph.valid()) readiness = KoilImportReadiness.GRAPH_VALIDATED;
        }

        KoilTensorResolution tensorResolution = graph != null && graph.valid()
                ? KoilTensorRoleResolver.resolve(inspection, graph)
                : new KoilTensorResolution(java.util.List.of(), Set.of(), java.util.List.of(), false, "graph not ready for tensor role resolution");
        if (readiness.ordinal() >= KoilImportReadiness.GRAPH_VALIDATED.ordinal() && tensorResolution.resolvedCount() > 0) {
            readiness = KoilImportReadiness.TENSOR_ROLES_RESOLVED;
            if (tensorResolution.valid()) readiness = KoilImportReadiness.TENSOR_ROLES_VALIDATED;
        }

        Set<KoilOperatorKind> operators = graph != null && !graph.requiredOperators().isEmpty()
                ? graph.requiredOperators()
                : profile.architectureRecognized() ? profile.architecture().requiredOperators() : Set.of();
        Set<KoilModelStateKind> state = graph != null && !graph.persistentState().isEmpty()
                ? graph.persistentState()
                : profile.architectureRecognized() ? profile.architecture().persistentState() : Set.of();
        return new KoilExecutionRepresentation(readiness, profile, tensors, quantization, tokenizer, graph, tensorResolution,
                operators, state, evidence(readiness, profile, tensors, graph, tensorResolution));
    }

    private static String evidence(KoilImportReadiness readiness, KoilModelProfile profile, KoilTensorManifestSummary tensors, KoilModelGraph graph, KoilTensorResolution tensorResolution) {
        return "import=" + readiness.name().toLowerCase(java.util.Locale.ROOT)
                + " | format=" + profile.sourceFormat().name().toLowerCase(java.util.Locale.ROOT)
                + " | architecture=" + (profile.architectureId().isBlank() ? "unknown" : profile.architectureId())
                + " | tensors=" + tensors.tensorCount()
                + " | parameters=" + tensors.parameterCount()
                + " | graph_nodes=" + (graph == null ? 0 : graph.nodes().size())
                + " | graph_valid=" + (graph != null && graph.valid())
                + " | tensor_roles_resolved=" + (tensorResolution == null ? 0 : tensorResolution.resolvedCount())
                + " | tensor_roles_valid=" + (tensorResolution != null && tensorResolution.valid());
    }
}
