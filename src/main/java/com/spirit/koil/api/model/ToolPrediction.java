package com.spirit.koil.api.model;

import com.google.gson.JsonObject;
import java.util.List;

/** A ranked, explainable prediction of a tool that may be useful next. */
public record ToolPrediction(
        String toolId,
        double confidence,
        int requiredArguments,
        boolean speculativeEligible,
        JsonObject predictedArguments,
        String argumentProvenance,
        boolean argumentProvenanceAuthoritative,
        List<String> sourceCallIds,
        List<String> reasons
) {
    public ToolPrediction {
        toolId = toolId == null ? "" : toolId.strip();
        confidence = Math.max(0.0D, Math.min(1.0D, confidence));
        requiredArguments = Math.max(0, requiredArguments);
        predictedArguments = predictedArguments == null ? new JsonObject() : predictedArguments.deepCopy();
        argumentProvenance = argumentProvenance == null ? "none" : argumentProvenance.strip();
        sourceCallIds = sourceCallIds == null ? List.of() : List.copyOf(sourceCallIds);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public boolean argumentsResolved() {
        return requiredArguments == 0 || ToolArgumentResolver.satisfiesRequiredArguments(toolId, predictedArguments);
    }

    public boolean argumentsSafeForSpeculation() {
        return argumentsResolved() && (requiredArguments == 0 || argumentProvenanceAuthoritative);
    }
}
