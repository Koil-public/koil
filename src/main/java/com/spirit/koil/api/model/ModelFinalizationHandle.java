package com.spirit.koil.api.model;

/** Separate from cancellation: asks an active session to stop optional work and answer. */
public interface ModelFinalizationHandle {
    boolean requestAnswerNow();

    boolean isFinalizationRequested();
}
