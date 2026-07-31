package com.spirit.koil.api.model;

public interface ModelCancellationHandle {
    boolean cancel(String reason);

    boolean isCancellationRequested();

    String cancellationReason();
}
