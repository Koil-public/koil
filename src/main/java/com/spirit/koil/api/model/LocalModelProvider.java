package com.spirit.koil.api.model;

import java.util.concurrent.CompletableFuture;

public interface LocalModelProvider extends AutoCloseable {
    String id();

    ModelCapabilityDescriptor capabilities();

    ModelHealthSnapshot health();

    CompletableFuture<ModelHealthSnapshot> start();

    ModelCancellationHandle generate(StreamingModelRequest request, StreamingModelObserver observer);

    CompletableFuture<Void> stop();

    @Override
    default void close() {
        stop().join();
    }
}
