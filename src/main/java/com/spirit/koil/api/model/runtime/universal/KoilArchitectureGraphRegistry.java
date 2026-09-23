package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactInspection;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Registry for architecture lowering. Recognition and graph construction remain separate readiness stages. */
public final class KoilArchitectureGraphRegistry {
    private static final KoilArchitectureGraphRegistry DEFAULT = createDefault();
    private final CopyOnWriteArrayList<KoilArchitectureGraphBuilder> builders = new CopyOnWriteArrayList<>();

    public static KoilArchitectureGraphRegistry defaultRegistry() { return DEFAULT; }

    public void register(KoilArchitectureGraphBuilder builder) {
        if (builder == null || builder.id() == null || builder.id().isBlank()) {
            throw new IllegalArgumentException("architecture graph builder id is required");
        }
        builders.removeIf(existing -> existing.id().equals(builder.id()));
        builders.add(builder);
        builders.sort(Comparator.comparingInt(KoilArchitectureGraphBuilder::priority).reversed()
                .thenComparing(KoilArchitectureGraphBuilder::id));
    }

    public List<KoilArchitectureGraphBuilder> builders() { return List.copyOf(builders); }

    public KoilModelGraph build(KoilArchitectureDescriptor architecture, ModelArtifactInspection inspection) {
        if (architecture == null || !architecture.recognized()) {
            return unavailable(inspection, "architecture is not mapped");
        }
        for (KoilArchitectureGraphBuilder builder : builders) {
            if (builder.supports(architecture, inspection)) return builder.build(architecture, inspection);
        }
        return unavailable(inspection, "no registered graph builder supports " + architecture.architectureId());
    }

    private static KoilArchitectureGraphRegistry createDefault() {
        KoilArchitectureGraphRegistry registry = new KoilArchitectureGraphRegistry();
        registry.register(new KoilNormalizedGraphBuilder());
        return registry;
    }

    private static KoilModelGraph unavailable(ModelArtifactInspection inspection, String reason) {
        String id = inspection == null ? "" : inspection.architectureId();
        return new KoilModelGraph(id, KoilArchitectureCategory.UNKNOWN, List.of(), java.util.Set.of(), java.util.Set.of(),
                List.of(new KoilGraphDiagnostic(KoilGraphDiagnostic.Severity.ERROR, "graph_builder_unavailable", reason)),
                false, reason);
    }
}
