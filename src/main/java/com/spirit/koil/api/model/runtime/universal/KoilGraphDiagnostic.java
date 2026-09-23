package com.spirit.koil.api.model.runtime.universal;

/** Precise graph/binding validation evidence retained instead of collapsing failures to unsupported. */
public record KoilGraphDiagnostic(Severity severity, String code, String message) {
    public enum Severity { INFO, WARNING, ERROR }

    public KoilGraphDiagnostic {
        severity = severity == null ? Severity.ERROR : severity;
        code = code == null ? "unknown" : code.strip();
        message = message == null ? "" : message.strip();
    }
}
