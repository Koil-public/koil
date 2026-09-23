package com.spirit.koil.api.model.reasoning;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bridges concrete tool observations into first-class AgentState claims.
 *
 * <p>This policy does not grant tool authority and it does not treat model
 * prose as verification. Consequential claims remain pending until an
 * independent/registered validation observation passes. A tool result that
 * already carries a passed validation status is accepted as verified evidence
 * from the existing Koil verifier/executor contract.</p>
 */
public final class AgentVerificationPolicy {
    private AgentVerificationPolicy() {}

    public static Observation observe(
            AgentState state,
            ModelToolResult result,
            ModelToolDefinition definition,
            List<ModelToolDefinition> availableTools
    ) {
        if (state == null || result == null) return Observation.none();

        String evidenceId = evidenceIdFor(state, result.callId());
        boolean completed = "completed".equalsIgnoreCase(clean(result.status()))
                || "already_satisfied".equalsIgnoreCase(clean(result.status()));
        if (!completed) {
            // A failed verification tool is meaningful contradictory evidence.
            state.verifyClaimsForTool(result.toolId(), false, evidenceId,
                    result.detail().isBlank() ? result.failureCode() : result.detail());
            return Observation.none();
        }

        boolean consequential = consequential(definition, result);
        boolean validated = "passed".equalsIgnoreCase(clean(result.validationStatus()));
        String verifier = validated ? result.toolId() : selectVerifier(result.toolId(), availableTools);
        String subject = claimSubject(result);
        String value = claimValue(result);
        String claimId = "tool-claim:" + clean(result.callId());

        state.registerClaim(claimId, subject, value, result.toolId(), consequential,
                verifier, evidenceId);

        if (!consequential || validated) {
            state.verifyClaim(claimId, true, evidenceId,
                    validated ? "Existing Koil validation passed." : "Read-only/non-consequential observation.");
        }

        // If this result was itself selected as a verifier for earlier claims,
        // satisfy those before the next model round. This uses the verifier
        // tool identity chosen when the claim was created, not free-form prose.
        if (validated || !consequential) {
            state.verifyClaimsForTool(result.toolId(), true, evidenceId, result.detail());
        }

        return new Observation(claimId, consequential, validated, verifier, subject, value);
    }

    public static String selectVerifier(String originatingTool, List<ModelToolDefinition> availableTools) {
        Set<String> ids = new LinkedHashSet<>();
        if (availableTools != null) {
            for (ModelToolDefinition tool : availableTools) if (tool != null) ids.add(tool.id());
        }
        String tool = clean(originatingTool);
        if (tool.startsWith("workspace.")) {
            if (ids.contains("workspace.stat")) return "workspace.stat";
            if (ids.contains("workspace.read")) return "workspace.read";
        }
        if (tool.startsWith("development.")) {
            if (!"development.run".equals(tool) && ids.contains("development.run")) return "development.run";
            if (ids.contains("development.tasks")) return "development.tasks";
        }
        if (tool.startsWith("minecraft.") || tool.startsWith("movement.") || tool.startsWith("inventory.")) {
            if (ids.contains("minecraft.player_state")) return "minecraft.player_state";
            if (ids.contains("minecraft.target_info")) return "minecraft.target_info";
        }
        if (tool.startsWith("code.")) {
            for (String id : ids) if (id.startsWith("code.")) return id;
        }
        if (tool.startsWith("automation.")) {
            if (ids.contains("minecraft.player_state")) return "minecraft.player_state";
            if (ids.contains("development.run")) return "development.run";
        }
        return "";
    }

    private static boolean consequential(ModelToolDefinition definition, ModelToolResult result) {
        if (definition != null && (!definition.sideEffects().isEmpty()
                || definition.confirmationRequired()
                || !definition.reversible())) {
            return !definition.sideEffects().isEmpty() || definition.confirmationRequired();
        }
        return result.changedTargets() != null && !result.changedTargets().isEmpty()
                || "approved".equalsIgnoreCase(clean(result.approvalStatus()));
    }

    private static String claimSubject(ModelToolResult result) {
        if (result.changedTargets() != null && !result.changedTargets().isEmpty()) {
            return "state:" + String.join(",", result.changedTargets());
        }
        JsonObject output = result.output();
        for (String key : List.of("path", "id", "target", "operation", "command", "objective")) {
            if (output != null && output.has(key) && output.get(key).isJsonPrimitive()) {
                return result.toolId() + ":" + key + ":" + safe(output.get(key));
            }
        }
        return "tool:" + result.toolId();
    }

    private static String claimValue(ModelToolResult result) {
        JsonObject output = result.output();
        if (output != null) {
            for (String key : List.of("result", "status", "id", "path", "finalPosition", "objectiveReached", "stateChanged")) {
                if (output.has(key)) return key + "=" + abbreviate(safe(output.get(key)), 220);
            }
        }
        if (!clean(result.detail()).isBlank()) return abbreviate(clean(result.detail()), 220);
        return clean(result.status());
    }

    private static String evidenceIdFor(AgentState state, String callId) {
        if (state == null) return "";
        return state.snapshot().toolEvidence().stream()
                .filter(e -> clean(callId).equals(e.callId()))
                .max(java.util.Comparator.comparingLong(AgentState.ToolEvidence::revision))
                .map(AgentState.ToolEvidence::id)
                .orElse("");
    }

    private static String safe(JsonElement value) {
        if (value == null || value.isJsonNull()) return "null";
        try { return value.isJsonPrimitive() ? value.getAsString() : value.toString(); }
        catch (RuntimeException ignored) { return value.toString(); }
    }

    private static String abbreviate(String value, int max) {
        String v = clean(value);
        return v.length() <= max ? v : v.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\s+", " ").strip();
    }

    public record Observation(
            String claimId, boolean consequential, boolean alreadyValidated,
            String verifierTool, String subject, String value
    ) {
        public Observation {
            claimId = clean(claimId); verifierTool = clean(verifierTool);
            subject = clean(subject); value = clean(value);
        }
        static Observation none() { return new Observation("", false, false, "", "", ""); }
    }
}
