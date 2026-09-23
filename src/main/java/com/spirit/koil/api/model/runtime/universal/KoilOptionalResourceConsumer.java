package com.spirit.koil.api.model.runtime.universal;

import java.util.Map;

/**
 * Internal execution adapters implement this when they can consume Koil's typed
 * optional-resource admission policy. Admissions are ceilings, never reservations.
 */
public interface KoilOptionalResourceConsumer {
    void applyKoilOptionalResourceAdmissions(
            Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> launchAdmissions,
            Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> liveAdmissions
    );
}
