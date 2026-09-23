package com.spirit.koil.api.model.runtime.universal;

/** A non-reserving admission ceiling for one optional memory consumer. */
public record KoilOptionalResourceAdmission(
        KoilOptionalResourceKind kind,
        boolean allowed,
        long ceilingBytes,
        String reason
) {
    public KoilOptionalResourceAdmission {
        if (kind == null) throw new IllegalArgumentException("kind is required");
        ceilingBytes = Math.max(0L, ceilingBytes);
        if (!allowed) ceilingBytes = 0L;
        reason = reason == null ? "" : reason;
    }
}
