package com.spirit.koil.api.model.retrieval;

import java.util.Objects;

/** Bounded caller intent for hybrid retrieval. */
public record KnowledgeQuery(
        String text,
        KnowledgeFilter filter,
        int candidateLimit,
        int contextTokenBudget,
        String requestId,
        KnowledgeTrust callerTrust
) {
    public KnowledgeQuery {
        text = text == null ? "" : text.strip();
        if (text.isEmpty()) throw new IllegalArgumentException("query text must not be blank");
        filter = filter == null ? KnowledgeFilter.any() : filter;
        if (candidateLimit <= 0) throw new IllegalArgumentException("candidateLimit must be positive");
        if (contextTokenBudget < 0) throw new IllegalArgumentException("contextTokenBudget must not be negative");
        requestId = requestId == null ? "" : requestId.strip();
        callerTrust = Objects.requireNonNull(callerTrust, "callerTrust");
    }
}
