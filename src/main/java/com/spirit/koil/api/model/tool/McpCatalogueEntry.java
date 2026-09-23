package com.spirit.koil.api.model.tool;

/** Normalized untrusted discovery metadata; discovery never grants execution authority. */
public record McpCatalogueEntry(
        String stableId,
        String name,
        String description,
        String repositoryUrl,
        String category,
        String sourceRevision,
        TrustState trustState
) {
    public McpCatalogueEntry {
        stableId = stableId == null ? "" : stableId;
        name = name == null ? "" : name.strip();
        description = description == null ? "" : description.strip();
        repositoryUrl = repositoryUrl == null ? "" : repositoryUrl.strip();
        category = category == null ? "" : category.strip();
        sourceRevision = sourceRevision == null ? "" : sourceRevision.strip();
        trustState = trustState == null ? TrustState.DISCOVERED : trustState;
    }

    public enum TrustState { DISCOVERED, VERIFIED, INSTALLED, ENABLED, RUNNING }
}
