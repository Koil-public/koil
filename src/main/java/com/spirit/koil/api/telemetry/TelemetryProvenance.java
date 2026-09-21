package com.spirit.koil.api.telemetry;

import java.util.UUID;

/** Observable knowledge/context supplied to a request. Hidden model reasoning is intentionally excluded. */
public record TelemetryProvenance(
        String id,
        UUID requestId,
        String spanId,
        String source,
        String producer,
        String stage,
        long timestampMillis,
        long sizeBytes,
        int estimatedTokens,
        String digest,
        String summary,
        String payload,
        boolean payloadTruncated
) {
    public TelemetryProvenance {
        id = clean(id);
        spanId = clean(spanId);
        source = clean(source);
        producer = clean(producer);
        stage = clean(stage);
        timestampMillis = Math.max(0L, timestampMillis);
        sizeBytes = Math.max(0L, sizeBytes);
        estimatedTokens = Math.max(0, estimatedTokens);
        digest = clean(digest);
        summary = clean(summary);
        payload = payload == null ? "" : payload;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').strip();
    }
}
