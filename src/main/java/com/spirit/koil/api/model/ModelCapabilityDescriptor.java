package com.spirit.koil.api.model;

import java.util.Set;

public record ModelCapabilityDescriptor(
        boolean streaming,
        boolean toolCalling,
        boolean multiTurn,
        boolean cancellation,
        boolean prefixCache,
        int maximumContextTokens,
        Set<String> protocols
) {
    public ModelCapabilityDescriptor {
        maximumContextTokens = Math.max(0, maximumContextTokens);
        protocols = protocols == null ? Set.of() : Set.copyOf(protocols);
    }
}
