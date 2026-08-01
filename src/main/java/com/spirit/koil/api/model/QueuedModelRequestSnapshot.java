package com.spirit.koil.api.model;

import java.util.UUID;

public record QueuedModelRequestSnapshot(
        UUID requestId,
        String conversationId,
        String providerId,
        String mode,
        String prompt,
        int position,
        long revision
) {
}
