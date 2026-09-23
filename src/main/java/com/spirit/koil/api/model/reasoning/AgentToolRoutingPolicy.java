package com.spirit.koil.api.model.reasoning;

import com.spirit.koil.api.model.ModelToolDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Converts authoritative AgentState into a bounded next-tool focus.
 *
 * <p>This policy never grants execution authority and never invents tools. It
 * only ranks tools already exposed by the normal catalog. Prompt routing remains
 * the fallback when state cannot identify a concrete unresolved need.</p>
 */
public final class AgentToolRoutingPolicy {
    private AgentToolRoutingPolicy() {}

    public static Focus focus(AgentState state, List<ModelToolDefinition> available, Set<String> requiredToolIds) {
        if (state == null) return Focus.empty();
        AgentState.Snapshot snapshot = state.snapshot();
        List<ModelToolDefinition> tools = available == null ? List.of() : available;
        Set<String> availableIds = tools.stream().map(ModelToolDefinition::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<String> priority = new LinkedHashSet<>();
        ArrayList<String> reasons = new ArrayList<>();

        // 1. Explicitly failed verification/recovery work comes first.
        snapshot.verificationTargets().stream()
                .filter(v -> v.status() == AgentState.VerificationStatus.FAILED)
                .forEach(v -> addConcrete(priority, availableIds, v.source(), reasons, "failed_verification"));
        snapshot.unknowns().stream()
                .filter(u -> u.status() == AgentState.UnknownStatus.OPEN)
                .filter(u -> "failure_recovery".equals(u.kind()))
                .forEach(u -> addConcrete(priority, availableIds, u.source(), reasons, "failure_recovery"));

        // 2. The current ordered objective frontier outranks future objective unknowns.
        // LocalModelService passes only the capabilities belonging to the current
        // durable task here. Future objective occurrences remain in AgentState,
        // but they must stay dormant until their task becomes current.
        Set<String> currentRequired = requiredToolIds == null ? Set.of() : Set.copyOf(requiredToolIds);
        for (String id : currentRequired) {
            addConcrete(priority, availableIds, id, reasons, "ordered_task_frontier");
        }

        // 3. Open unknowns with an exact resolver, or a semantic resolver family.
        // Objective unknowns for later ordered tasks are deliberately excluded.
        snapshot.unknowns().stream()
                .filter(u -> u.status() == AgentState.UnknownStatus.OPEN)
                .filter(u -> !"failure_recovery".equals(u.kind()))
                .filter(u -> !u.id().startsWith("objective:")
                        || currentRequired.isEmpty()
                        || currentRequired.contains(clean(u.source())))
                .forEach(u -> {
                    if (!addConcrete(priority, availableIds, u.source(), reasons,
                            u.id().startsWith("objective:") ? "current_objective_unknown" : "open_unknown")) {
                        addFamily(priority, availableIds, familyPrefixes(u.kind()), reasons, "unknown:" + u.kind());
                    }
                });

        // 4. Pending verification should select the observer that can prove the result.
        snapshot.verificationTargets().stream()
                .filter(v -> v.status() == AgentState.VerificationStatus.PENDING)
                .forEach(v -> {
                    if (!addConcrete(priority, availableIds, v.source(), reasons, "pending_verification")) {
                        addFamily(priority, availableIds, familyPrefixes("verification_required"), reasons,
                                "pending_verification_family");
                    }
                });

        // 5. The currently active/pending plan step is authoritative for execution order.
        if (snapshot.plan() != null && snapshot.plan().status() != AgentState.PlanStatus.NONE) {
            snapshot.plan().steps().stream()
                    .filter(step -> step.status() == AgentState.PlanStepStatus.ACTIVE)
                    .findFirst()
                    .or(() -> snapshot.plan().steps().stream()
                            .filter(step -> step.status() == AgentState.PlanStepStatus.PENDING)
                            .min(Comparator.comparingInt(AgentState.PlanStepState::index)))
                    .ifPresent(step -> addConcrete(priority, availableIds, step.toolId(), reasons, "active_plan_step"));
        }

        // 6. Explicit current-objective tools stay high priority when state has not resolved them.
        if (requiredToolIds != null) {
            for (String id : requiredToolIds) addConcrete(priority, availableIds, id, reasons, "required_objective");
        }

        if (priority.isEmpty()) return Focus.empty();
        String target = firstTarget(snapshot, currentRequired);
        return new Focus(List.copyOf(priority), List.copyOf(reasons), target);
    }

    public static List<ModelToolDefinition> prioritize(
            AgentState state,
            List<ModelToolDefinition> available,
            Set<String> requiredToolIds
    ) {
        if (available == null || available.size() < 2) return available == null ? List.of() : available;
        Focus focus = focus(state, available, requiredToolIds);
        if (focus.toolIds().isEmpty()) return available;
        Set<String> priority = new LinkedHashSet<>(focus.toolIds());
        ArrayList<ModelToolDefinition> ordered = new ArrayList<>(available.size());
        for (String id : focus.toolIds()) {
            available.stream().filter(tool -> id.equals(tool.id())).findFirst().ifPresent(ordered::add);
        }
        for (ModelToolDefinition tool : available) if (!priority.contains(tool.id())) ordered.add(tool);
        return List.copyOf(ordered);
    }

    public static String predictionObjective(AgentState state, String fallback) {
        if (state == null) return fallback == null ? "" : fallback;
        Focus focus = focus(state, List.of(), Set.of());
        String target = focus.target();
        if (target.isBlank()) {
            AgentState.Snapshot snapshot = state.snapshot();
            target = firstTarget(snapshot, Set.of());
        }
        if (target.isBlank()) return fallback == null ? "" : fallback;
        return "Current unresolved agent-state target: " + target
                + ". Original objective: " + (fallback == null ? "" : fallback);
    }


    public static String promptDirective(
            AgentState state,
            List<ModelToolDefinition> available,
            Set<String> requiredToolIds
    ) {
        Focus focus = focus(state, available, requiredToolIds);
        if (focus.target().isBlank() || focus.toolIds().isEmpty()) return "";
        return "Agent tool focus: " + focus.target()
                + ". Prefer the supplied resolver(s) that directly advance this state target: "
                + String.join(", ", focus.toolIds())
                + ". Use a different supplied tool only when current evidence shows it is necessary. "
                + "Do not reopen already resolved state just to reconsider wording.";
    }

    public record Focus(List<String> toolIds, List<String> reasons, String target) {
        public Focus {
            toolIds = toolIds == null ? List.of() : List.copyOf(toolIds);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            target = target == null ? "" : target.strip();
        }
        public static Focus empty() { return new Focus(List.of(), List.of(), ""); }
    }

    private static boolean addConcrete(
            LinkedHashSet<String> out,
            Set<String> availableIds,
            String id,
            List<String> reasons,
            String reason
    ) {
        String clean = clean(id);
        if (clean.isBlank() || !availableIds.contains(clean)) return false;
        if (out.add(clean)) reasons.add(clean + "=" + reason);
        return true;
    }

    private static void addFamily(
            LinkedHashSet<String> out,
            Set<String> availableIds,
            List<String> prefixes,
            List<String> reasons,
            String reason
    ) {
        if (prefixes.isEmpty()) return;
        for (String id : availableIds) {
            for (String prefix : prefixes) {
                if (id.startsWith(prefix)) {
                    if (out.add(id)) reasons.add(id + "=" + reason);
                    break;
                }
            }
        }
    }

    private static List<String> familyPrefixes(String kind) {
        return switch (clean(kind).toLowerCase(Locale.ROOT)) {
            case "identifier_resolution" -> List.of("minecraft.");
            case "factual_uncertainty" -> List.of("code.", "internet.", "browser.", "dataset.", "content.", "koil.");
            case "context_dependency" -> List.of("context.", "workspace.");
            case "plan_required" -> List.of("automation.plan");
            case "verification_required" -> List.of("development.", "minecraft.", "workspace.", "code.");
            default -> List.of();
        };
    }

    private static String firstTarget(AgentState.Snapshot snapshot, Set<String> currentRequired) {
        if (snapshot == null) return "";
        Set<String> current = currentRequired == null ? Set.of() : currentRequired;
        return snapshot.verificationTargets().stream()
                .filter(v -> v.status() == AgentState.VerificationStatus.FAILED)
                .map(v -> "recover failed verification: " + v.requirement())
                .findFirst()
                .or(() -> snapshot.unknowns().stream()
                        .filter(u -> u.status() == AgentState.UnknownStatus.OPEN)
                        .filter(u -> "failure_recovery".equals(u.kind()))
                        .map(u -> "recover failure: " + u.question()).findFirst())
                .or(() -> snapshot.unknowns().stream()
                        .filter(u -> u.status() == AgentState.UnknownStatus.OPEN)
                        .filter(u -> u.id().startsWith("objective:"))
                        .filter(u -> current.isEmpty() || current.contains(clean(u.source())))
                        .map(u -> "execute current ordered task: " + u.question()).findFirst())
                .or(() -> snapshot.unknowns().stream()
                        .filter(u -> u.status() == AgentState.UnknownStatus.OPEN)
                        .filter(u -> !u.id().startsWith("objective:"))
                        .map(u -> "resolve " + u.kind() + ": " + u.question()).findFirst())
                .or(() -> snapshot.verificationTargets().stream()
                        .filter(v -> v.status() == AgentState.VerificationStatus.PENDING)
                        .map(v -> "verify: " + v.requirement()).findFirst())
                .or(() -> snapshot.plan() == null ? java.util.Optional.<String>empty()
                        : snapshot.plan().steps().stream()
                        .filter(step -> step.status() == AgentState.PlanStepStatus.ACTIVE
                                || step.status() == AgentState.PlanStepStatus.PENDING)
                        .min(Comparator.comparingInt(AgentState.PlanStepState::index))
                        .map(step -> "execute plan step " + step.index() + ": " + step.toolId()))
                .orElse("");
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
