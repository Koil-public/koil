package com.spirit.koil.api.model.runtime.universal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Resolves graph requirements against explicit adapter, backend, operator and state capability evidence. */
public final class KoilExecutionCompatibilityAnalyzer {
    private KoilExecutionCompatibilityAnalyzer() {}

    public static KoilExecutionCompatibility analyze(
            KoilExecutionRepresentation representation,
            KoilExecutionAdapterDescriptor adapter,
            KoilRuntimeBackend backend
    ) {
        return analyze(representation, adapter, backend,
                KoilOperatorCapabilityRegistry.defaultRegistry(), KoilStateCapabilityRegistry.defaultRegistry());
    }

    public static KoilExecutionCompatibility analyze(
            KoilExecutionRepresentation representation,
            KoilExecutionAdapterDescriptor adapter,
            KoilRuntimeBackend backend,
            KoilOperatorCapabilityRegistry operatorRegistry,
            KoilStateCapabilityRegistry stateRegistry
    ) {
        String adapterId = adapter == null ? "" : adapter.id();
        KoilRuntimeBackend actualBackend = backend == null ? KoilRuntimeBackend.UNKNOWN : backend;
        boolean graphValid = representation != null && representation.executionGraphReady();
        boolean tensorValid = representation != null && representation.tensorResolution() != null
                && representation.tensorResolution().valid();
        List<KoilOperatorResolution> operators = new ArrayList<>();
        List<KoilStateResolution> states = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        if (!graphValid) blockers.add("normalized graph is not validated");
        if (!tensorValid) {
            blockers.add("required tensor roles are not fully resolved and valid");
            if (representation != null && representation.tensorResolution() != null) {
                representation.tensorResolution().unresolvedRequiredRoles().stream()
                        .sorted(Comparator.comparing(Enum::name))
                        .forEach(role -> blockers.add("tensor role unresolved=" + role.name()));
            }
        }
        if (adapterId.isBlank()) blockers.add("execution adapter is unavailable");
        if (actualBackend == KoilRuntimeBackend.UNKNOWN) blockers.add("runtime backend is unknown");

        if (representation != null) {
            representation.requiredOperators().stream()
                    .sorted(Comparator.comparing(Enum::name))
                    .forEach(operator -> {
                        KoilOperatorCapability capability = operatorRegistry.resolve(adapterId, operator);
                        KoilCapabilitySupport adapterSupport = capability.support();
                        KoilCapabilitySupport backendSupport = capability.supportOn(actualBackend);
                        operators.add(new KoilOperatorResolution(operator, adapterSupport, backendSupport, capability.evidence()));
                        if (adapterSupport != KoilCapabilitySupport.SUPPORTED) {
                            blockers.add(operator.name() + " adapter support=" + adapterSupport.name().toLowerCase(java.util.Locale.ROOT));
                        } else if (backendSupport != KoilCapabilitySupport.SUPPORTED) {
                            blockers.add(operator.name() + " backend support=" + backendSupport.name().toLowerCase(java.util.Locale.ROOT)
                                    + " on " + actualBackend.name().toLowerCase(java.util.Locale.ROOT));
                        }
                    });
            representation.persistentState().stream()
                    .sorted(Comparator.comparing(Enum::name))
                    .forEach(state -> {
                        KoilStateCapability capability = stateRegistry.resolve(adapterId, state);
                        KoilCapabilitySupport adapterSupport = capability.support();
                        KoilCapabilitySupport backendSupport = capability.supportOn(actualBackend);
                        states.add(new KoilStateResolution(state, adapterSupport, backendSupport, capability.evidence()));
                        if (adapterSupport != KoilCapabilitySupport.SUPPORTED) {
                            blockers.add("state " + state.name() + " adapter support=" + adapterSupport.name().toLowerCase(java.util.Locale.ROOT));
                        } else if (backendSupport != KoilCapabilitySupport.SUPPORTED) {
                            blockers.add("state " + state.name() + " backend support=" + backendSupport.name().toLowerCase(java.util.Locale.ROOT)
                                    + " on " + actualBackend.name().toLowerCase(java.util.Locale.ROOT));
                        }
                    });
        }

        boolean executable = blockers.isEmpty();
        return new KoilExecutionCompatibility(adapterId, actualBackend, graphValid, tensorValid, operators, states, blockers,
                executable,
                "graph=" + graphValid + " | tensors=" + tensorValid + " | operators=" + operators.size()
                        + " | states=" + states.size() + " | blockers=" + blockers.size());
    }
}
