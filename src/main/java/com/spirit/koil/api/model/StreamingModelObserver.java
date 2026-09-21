package com.spirit.koil.api.model;

import java.util.UUID;

public interface StreamingModelObserver {
    default void onState(UUID requestId, ModelRequestState state, String detail) {
    }

    default void onTextDelta(UUID requestId, String delta) {
    }

    /** Model-native reasoning/thinking delta kept separate from visible assistant text. */
    default void onReasoningDelta(UUID requestId, String delta) {
    }

    /**
     * Typed model-authored side-channel data. The default keeps older observers compatible by
     * forwarding textual exposed channels through the legacy reasoning callback.
     */
    default void onExposedData(UUID requestId, ModelExposedData exposed) {
        if (exposed != null && exposed.hasText()) {
            onReasoningDelta(requestId, exposed.text());
        }
    }

    default void onToolCall(UUID requestId, ModelToolCall call) {
    }

    default void onUsage(UUID requestId, ModelUsage usage) {
    }

    /** Provider/runtime diagnostics. These are telemetry, never assistant text or reasoning. */
    default void onTelemetry(UUID requestId, ModelRuntimeTelemetry telemetry) {
    }

    default void onComplete(StreamingModelResponse response) {
    }

    default void onFailure(UUID requestId, String code, String detail, Throwable cause) {
    }
}
