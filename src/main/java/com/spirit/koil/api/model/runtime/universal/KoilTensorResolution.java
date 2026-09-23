package com.spirit.koil.api.model.runtime.universal;

import java.util.List;
import java.util.Set;

/** Tensor-role binding result independent of any execution backend. */
public record KoilTensorResolution(
        List<KoilTensorBinding> bindings,
        Set<KoilTensorRole> unresolvedRequiredRoles,
        List<KoilGraphDiagnostic> diagnostics,
        boolean complete,
        String evidence
) {
    public KoilTensorResolution {
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        unresolvedRequiredRoles = unresolvedRequiredRoles == null ? Set.of() : Set.copyOf(unresolvedRequiredRoles);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        evidence = evidence == null ? "" : evidence.strip();
    }

    public long resolvedCount() {
        return bindings.stream().filter(binding -> binding.role() != KoilTensorRole.UNKNOWN).count();
    }

    public boolean valid() {
        return complete && unresolvedRequiredRoles.isEmpty()
                && diagnostics.stream().noneMatch(d -> d.severity() == KoilGraphDiagnostic.Severity.ERROR);
    }
}
