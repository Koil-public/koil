package com.spirit.koil.api.model.retrieval;

import java.util.List;

/** Selected/rejected evidence and its injected bounded context. */
public record RetrievalResult(List<RetrievalCandidate> selected, List<RetrievalCandidate> rejected, String contextText, int contextTokens) {
    public RetrievalResult {
        selected = selected == null ? List.of() : List.copyOf(selected);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
        contextText = contextText == null ? "" : contextText;
        if (contextTokens < 0) throw new IllegalArgumentException("contextTokens must not be negative");
    }

    public List<Long> selectedIds() {
        return this.selected.stream().map(candidate -> candidate.entry().id()).toList();
    }
}
