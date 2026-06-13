package com.spirit.koil.api.f3;

public enum F3RefreshSpeed {
    LOW("Low", 1000L),
    NORMAL("Normal", 350L),
    FAST("Fast", 150L),
    DEVELOPER("Developer", 75L);

    private final String label;
    private final long intervalMillis;

    F3RefreshSpeed(String label, long intervalMillis) {
        this.label = label;
        this.intervalMillis = intervalMillis;
    }

    public String label() {
        return label;
    }

    public long intervalMillis() {
        return intervalMillis;
    }
}
