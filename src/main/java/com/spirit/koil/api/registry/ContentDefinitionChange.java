package com.spirit.koil.api.registry;

import com.spirit.koil.api.registry.definition.ReloadClassification;

import java.util.List;

/** One auditable definition change produced by a world activation/reload diff. */
public record ContentDefinitionChange(
        String id,
        String type,
        ContentChangeKind kind,
        ReloadClassification classification,
        List<String> changedPaths,
        String message
) {
    public ContentDefinitionChange {
        changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
        message = message == null ? "" : message;
    }
}
