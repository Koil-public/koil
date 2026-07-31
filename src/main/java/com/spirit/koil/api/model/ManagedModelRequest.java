package com.spirit.koil.api.model;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public record ManagedModelRequest(
        UUID requestId,
        ModelCancellationHandle cancellation,
        CompletableFuture<StreamingModelResponse> completion
) {
}
