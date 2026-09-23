package com.spirit.koil.api.model.catalog;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Chooses an exact runtime implementation from catalog evidence. */
public final class LocalModelRuntimeResolver {
    private LocalModelRuntimeResolver() {
    }

    public static Resolution resolve(LocalModelCatalogEntry entry) {
        if (entry == null) {
            return Resolution.unavailable("unknown model");
        }
        List<ModelRuntimeCompatibility> compatible = entry.runtimeCompatibility().stream()
                .filter(ModelRuntimeCompatibility::supportsCurrentPlatform)
                .sorted(Comparator.comparingInt(ModelRuntimeCompatibility::preference).reversed()
                        .thenComparing(ModelRuntimeCompatibility::runtimeId))
                .toList();
        if (compatible.isEmpty()) {
            String known = entry.runtimeCompatibility().isEmpty()
                    ? "no runtime compatibility is registered"
                    : "no registered runtime supports " + LocalModelRuntimePlatform.currentId();
            return Resolution.unavailable(known);
        }
        ModelRuntimeCompatibility selected = compatible.get(0);
        return new Resolution(true, selected, compatible,
                "selected " + selected.runtimeId() + " from exact catalog/runtime compatibility metadata");
    }

    public record Resolution(
            boolean available,
            ModelRuntimeCompatibility selected,
            List<ModelRuntimeCompatibility> candidates,
            String evidence
    ) {
        public Resolution {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            evidence = evidence == null ? "" : evidence.strip();
        }

        public static Resolution unavailable(String evidence) {
            return new Resolution(false, null, List.of(), evidence);
        }

        public Optional<ModelRuntimeCompatibility> selectedOptional() {
            return Optional.ofNullable(selected);
        }
    }
}
