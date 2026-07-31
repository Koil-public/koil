package com.spirit.koil.api.model;

import java.util.UUID;

public interface StreamingModelObserver {
    default void onState(UUID requestId, ModelRequestState state, String detail) {
    }

    default void onTextDelta(UUID requestId, String delta) {
    }

    default void onToolCall(UUID requestId, ModelToolCall call) {
    }

    default void onUsage(UUID requestId, ModelUsage usage) {
    }

    default void onComplete(StreamingModelResponse response) {
    }

    default void onFailure(UUID requestId, String code, String detail, Throwable cause) {
    }
}
