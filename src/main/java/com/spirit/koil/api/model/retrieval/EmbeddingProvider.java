package com.spirit.koil.api.model.retrieval;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Asynchronous local embedding role; never invoke it from the render thread. */
public interface EmbeddingProvider extends AutoCloseable {
    EmbeddingIdentity identity();

    CompletableFuture<List<float[]>> embed(List<String> texts);

    @Override
    default void close() {
    }
}
