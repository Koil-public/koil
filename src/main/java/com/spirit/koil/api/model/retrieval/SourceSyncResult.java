package com.spirit.koil.api.model.retrieval;

/** Counts the durable effects of one complete source snapshot reconciliation. */
public record SourceSyncResult(int unchanged, int created, int updated, int deleted) {
    public SourceSyncResult {
        if (unchanged < 0 || created < 0 || updated < 0 || deleted < 0) {
            throw new IllegalArgumentException("source sync counts must not be negative");
        }
    }
}
