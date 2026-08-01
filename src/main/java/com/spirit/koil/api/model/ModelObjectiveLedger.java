package com.spirit.koil.api.model;

import com.spirit.koil.api.model.tool.LocalModelToolCatalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Tracks distinct imperative objectives and the structured evidence required to satisfy each one. */
public final class ModelObjectiveLedger {
    private final List<Objective> objectives;

    private ModelObjectiveLedger(List<Objective> objectives) { this.objectives = new ArrayList<>(objectives); }

    public static ModelObjectiveLedger parse(String prompt) {
        String source = prompt == null ? "" : prompt.replace('\r', ' ').replace('\n', ' ').strip();
        String[] clauses = source.split("(?i)\\b(?:and then|then|after that|before that)\\b|[.;]");
        List<Objective> objectives = new ArrayList<>();
        for (String clause : clauses) {
            String clean = clause.replaceAll("\\s+", " ").strip();
            if (clean.isBlank()) continue;
            for (String toolId : LocalModelToolCatalog.requiredToolIdsForPrompt(clean)) {
                objectives.add(new Objective("objective-" + UUID.randomUUID(), clean, toolId,
                        evidenceRequirement(toolId), State.PENDING, "", ""));
            }
        }
        return new ModelObjectiveLedger(objectives);
    }

    public synchronized void record(ModelToolResult result) {
        if (result == null) return;
        for (int i=0;i<objectives.size();i++) {
            Objective objective=objectives.get(i);
            if (objective.state != State.PENDING || !objective.toolId.equals(result.toolId())) continue;
            State state = result.completedAndValidated() ? State.COMPLETED
                    : result.retryable() ? State.PENDING : State.BLOCKED;
            objectives.set(i, new Objective(objective.id, objective.text, objective.toolId,
                    objective.requiredEvidence, state, result.status(), result.failureCode()));
            return;
        }
    }

    public synchronized Set<String> pendingToolIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        objectives.stream().filter(value -> value.state == State.PENDING).map(Objective::toolId).forEach(ids::add);
        return Set.copyOf(ids);
    }

    public synchronized List<Objective> snapshot() { return List.copyOf(objectives); }
    public synchronized boolean satisfied() { return objectives.stream().noneMatch(value -> value.state == State.PENDING); }

    private static String evidenceRequirement(String toolId) {
        if (toolId.startsWith("workspace.")) return "completed result, filesystem reread, and matching resulting hash";
        if (toolId.startsWith("development.")) return "completed process with exit code 0";
        if (toolId.startsWith("minecraft.command")) return "command feedback or resulting-state observation";
        return "completed structured result with required validation";
    }

    public enum State { PENDING, COMPLETED, BLOCKED, CANCELLED }
    public record Objective(String id, String text, String toolId, String requiredEvidence, State state, String resultStatus, String failureCode) {}
}
