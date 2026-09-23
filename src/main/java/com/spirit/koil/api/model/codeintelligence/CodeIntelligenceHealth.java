package com.spirit.koil.api.model.codeintelligence;

/** Current provider availability without exposing transport diagnostics to a model. */
public record CodeIntelligenceHealth(State state, String providerId, String detail) {
    public enum State { NOT_INSTALLED, INSTALLING, STARTING, NEGOTIATING, INDEXING, READY, DEGRADED, RESTARTING, FAILED, STOPPED }

    public CodeIntelligenceHealth {
        state = state == null ? State.FAILED : state;
        providerId = providerId == null ? "" : providerId;
        detail = detail == null ? "" : detail;
    }
}
