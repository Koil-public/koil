package com.spirit.koil.api.telemetry;

/** Typed text removes semantic coloring guesses from the renderer. */
public record TelemetryText(Kind kind, String text) {
    public TelemetryText {
        kind = kind == null ? Kind.VALUE : kind;
        text = text == null ? "" : text;
    }

    public static TelemetryText of(Kind kind, String text) {
        return new TelemetryText(kind, text);
    }

    public static TelemetryText plain(String text) { return of(Kind.PLAIN, text); }
    public static TelemetryText key(String text) { return of(Kind.KEY, text); }
    public static TelemetryText value(String text) { return of(Kind.VALUE, text); }
    public static TelemetryText id(String text) { return of(Kind.ID, text); }
    public static TelemetryText state(String text) { return of(Kind.STATE, text); }
    public static TelemetryText source(String text) { return of(Kind.SOURCE, text); }
    public static TelemetryText duration(String text) { return of(Kind.DURATION, text); }
    public static TelemetryText count(String text) { return of(Kind.COUNT, text); }
    public static TelemetryText success(String text) { return of(Kind.SUCCESS, text); }
    public static TelemetryText warning(String text) { return of(Kind.WARNING, text); }
    public static TelemetryText error(String text) { return of(Kind.ERROR, text); }
    public static TelemetryText muted(String text) { return of(Kind.MUTED, text); }

    public enum Kind {
        PLAIN,
        KEY,
        VALUE,
        ID,
        STATE,
        SOURCE,
        DURATION,
        COUNT,
        SUCCESS,
        WARNING,
        ERROR,
        MUTED
    }
}
