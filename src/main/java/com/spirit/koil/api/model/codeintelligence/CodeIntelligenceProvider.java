package com.spirit.koil.api.model.codeintelligence;

import java.util.concurrent.CompletableFuture;

/** Provider-neutral asynchronous source-code intelligence boundary. */
public interface CodeIntelligenceProvider extends AutoCloseable {
    CompletableFuture<CodeIntelligenceResult> query(CodeIntelligenceRequest request);

    CodeIntelligenceHealth health();

    @Override
    default void close() {
    }
}
