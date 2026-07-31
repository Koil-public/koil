package com.spirit.koil.api.automation.capability;

import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolDefinition;

import java.time.Duration;
import java.util.List;
import java.util.Set;

public record AutomationCapabilityDefinition(
        String id,
        String description,
        JsonObject inputSchema,
        List<String> requiredParameters,
        List<String> optionalParameters,
        List<String> preconditions,
        Set<String> sideEffects,
        boolean reversible,
        Duration timeout,
        boolean cancellationSupported,
        AutomationMultiplayerPolicy multiplayerPolicy,
        boolean confirmationRequired,
        Set<String> resultStates,
        InvocationCompiler invocationCompiler
) {
    public AutomationCapabilityDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("capability id is required");
        }
        id = id.trim();
        description = description == null ? "" : description.trim();
        inputSchema = inputSchema == null ? new JsonObject() : inputSchema.deepCopy();
        requiredParameters = requiredParameters == null ? List.of() : List.copyOf(requiredParameters);
        optionalParameters = optionalParameters == null ? List.of() : List.copyOf(optionalParameters);
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        sideEffects = sideEffects == null ? Set.of() : Set.copyOf(sideEffects);
        timeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(30)
                : timeout;
        multiplayerPolicy = multiplayerPolicy == null
                ? AutomationMultiplayerPolicy.DISABLED
                : multiplayerPolicy;
        resultStates = resultStates == null ? Set.of() : Set.copyOf(resultStates);
        if (invocationCompiler == null) {
            throw new IllegalArgumentException("invocation compiler is required");
        }
    }

    public ModelToolDefinition toModelToolDefinition() {
        List<String> modelPreconditions = new java.util.ArrayList<>(this.preconditions);
        modelPreconditions.add("multiplayer_policy=" + this.multiplayerPolicy.name().toLowerCase(java.util.Locale.ROOT));
        return new ModelToolDefinition(
                this.id,
                this.description,
                this.inputSchema,
                modelPreconditions,
                this.sideEffects,
                this.reversible,
                this.timeout,
                this.cancellationSupported,
                this.confirmationRequired,
                this.resultStates
        );
    }

    @FunctionalInterface
    public interface InvocationCompiler {
        AutomationCapabilityPlan compile(JsonObject arguments);
    }
}
