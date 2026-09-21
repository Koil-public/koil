package com.spirit.koil.api.model;

import com.spirit.koil.api.model.planning.ValidatedAutomationPlan;
import com.spirit.koil.api.model.tool.LocalModelToolCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Ordered, multiplicity-preserving ledger for the explicit actions in one
 * Automation request.
 *
 * <p>The ledger is intentionally independent from transcript length and model
 * memory. A tool id appearing several times in the request creates several
 * distinct objectives, and a successful result can close only the currently
 * runnable occurrence. This prevents one jump, command, write, etc. from
 * accidentally satisfying several requested tasks merely because they share a
 * capability id.</p>
 *
 * <p>Large objectives are executed as a sequence of bounded plan/tool windows.
 * There is no total-task limit here. Only the small provider-facing summary is
 * windowed so a long task list does not consume the model context.</p>
 */
public final class ModelObjectiveLedger {
    private static final Pattern PRIMARY_SEQUENCE = Pattern.compile(
            "(?i)\\b(?:and\\s+then|then|after\\s+that|subsequently|followed\\s+by)\\b|\\bnext\\b(?!\\s+to\\b)\\s*,?\\s*|\\bfinally\\b\\s*,?\\s*|[;\\n]+"
    );
    private static final Pattern COMMA_SEQUENCE = Pattern.compile("\\s*,\\s*(?:and\\s+)?");
    // Common fast-typing typo for "then". Only treat "them" as a sequence
    // separator when it is immediately followed by a clear action verb, so
    // ordinary phrases such as "give them an item" remain intact.
    private static final Pattern LIKELY_THEN_TYPO = Pattern.compile(
            "(?i)\\bthem\\b(?=\\s+(?:walk|move|go|jump|set|put|switch|change|turn|run|execute|open|close|place|break|mine|give|take|drop|throw|use|click|press|teleport|tp|summon)\\b)"
    );
    private static final int SUMMARY_WINDOW = 8;

    private final List<Objective> objectives;
    private final Map<String, String> callObjectiveBindings = new LinkedHashMap<>();

    private ModelObjectiveLedger(List<Objective> objectives) {
        this.objectives = new ArrayList<>(objectives);
    }

    public static ModelObjectiveLedger parse(String prompt) {
        String source = prompt == null ? "" : prompt.replace('\r', ' ').strip();
        List<String> clauses = orderedClauses(source);
        List<Objective> objectives = new ArrayList<>();
        int taskIndex = 0;
        for (String clause : clauses) {
            String clean = normalizeText(clause);
            if (clean.isBlank()) continue;
            Set<String> ids = LocalModelToolCatalog.requiredToolIdsForPrompt(clean);
            taskIndex++;
            if (ids.isEmpty()) {
                objectives.add(new Objective(
                        "objective-" + UUID.randomUUID(),
                        taskIndex,
                        1,
                        clean,
                        "",
                        "resolve one concrete supported capability for this task, then obtain its structured completion evidence",
                        State.PENDING,
                        "",
                        ""
                ));
                continue;
            }
            int withinTask = 0;
            for (String toolId : ids) {
                withinTask++;
                objectives.add(new Objective(
                        "objective-" + UUID.randomUUID(),
                        taskIndex,
                        withinTask,
                        clean,
                        toolId,
                        evidenceRequirement(toolId),
                        State.PENDING,
                        "",
                        ""
                ));
            }
        }
        return new ModelObjectiveLedger(objectives);
    }

    /**
     * Binds one concrete invocation to exactly one occurrence on the current
     * ordered frontier. A later result can advance the ledger only through this
     * binding, so auxiliary observations or another identical capability cannot
     * accidentally satisfy a different requested occurrence.
     */
    public synchronized CallBinding bindCall(ModelToolCall call) {
        if (call == null || safe(call.id()).isBlank() || safe(call.toolId()).isBlank()) {
            return CallBinding.unbound();
        }
        int current = currentTaskIndexInternal(true);
        if (current <= 0) return CallBinding.unbound();
        for (int i = 0; i < objectives.size(); i++) {
            Objective objective = objectives.get(i);
            if (objective.taskIndex != current
                    || objective.state == State.COMPLETED
                    || objective.state == State.CANCELLED) {
                continue;
            }
            if (objective.toolId.isBlank()) {
                if (!consequential(call.toolId())) continue;
                objective = new Objective(
                        objective.id, objective.taskIndex, objective.withinTaskIndex, objective.text,
                        call.toolId(), evidenceRequirement(call.toolId()), objective.state, objective.resultStatus, objective.failureCode
                );
                objectives.set(i, objective);
            } else if (!objective.toolId.equals(call.toolId())) {
                continue;
            }
            callObjectiveBindings.put(call.id(), objective.id);
            return new CallBinding(true, objective.id, objective.taskIndex, objective.withinTaskIndex, objective.text, objective.toolId);
        }
        return CallBinding.unbound();
    }

    /**
     * Records evidence only against the exact objective occurrence bound when
     * the tool invocation started. Later tasks cannot be satisfied out of order
     * by an unrelated or speculative result that happens to share a tool id.
     */
    public synchronized RecordOutcome record(ModelToolResult result) {
        int before = currentTaskIndexInternal(true);
        if (result == null || before <= 0) return new RecordOutcome(false, before, before, false);
        String boundObjectiveId = callObjectiveBindings.remove(safe(result.callId()));
        if (boundObjectiveId == null || boundObjectiveId.isBlank()) {
            return new RecordOutcome(false, before, before, false);
        }
        int current = before;
        for (int i = 0; i < objectives.size(); i++) {
            Objective objective = objectives.get(i);
            if (!objective.id.equals(boundObjectiveId)
                    || objective.taskIndex != current
                    || objective.state == State.COMPLETED
                    || objective.state == State.CANCELLED
                    || !objective.toolId.equals(result.toolId())) {
                continue;
            }
            String status = safe(result.status()).toLowerCase(Locale.ROOT);
            String failure = safe(result.failureCode()).toLowerCase(Locale.ROOT);
            boolean terminalLimitation = failure.startsWith("unknown_")
                    || failure.contains("invalid_id")
                    || failure.contains("unsupported")
                    || failure.contains("permission")
                    || failure.contains("impossible");
            State state = result.completedAndValidated() || "already_satisfied".equals(status)
                    ? State.COMPLETED
                    : result.cancelled() || "cancelled".equals(status) ? State.CANCELLED
                    : terminalLimitation ? State.BLOCKED : State.PENDING;
            objectives.set(i, new Objective(
                    objective.id,
                    objective.taskIndex,
                    objective.withinTaskIndex,
                    objective.text,
                    objective.toolId,
                    objective.requiredEvidence,
                    state,
                    result.status(),
                    result.failureCode()
            ));
            int after = currentTaskIndexInternal(true);
            return new RecordOutcome(true, before, after, before != after);
        }
        return new RecordOutcome(false, before, before, false);
    }

    /** Current task capabilities only. This is the authoritative execution frontier. */
    public synchronized Set<String> pendingToolIds() {
        return toolIdsForCurrent(false);
    }

    /** Current task capabilities including recoverable blocked objectives. */
    public synchronized Set<String> incompleteToolIds() {
        return toolIdsForCurrent(true);
    }

    /** All remaining capabilities, useful only for diagnostics/catalog visibility. */
    public synchronized Set<String> allIncompleteToolIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        objectives.stream()
                .filter(value -> value.state != State.COMPLETED && value.state != State.CANCELLED)
                .map(Objective::toolId)
                .filter(id -> id != null && !id.isBlank())
                .forEach(ids::add);
        return Set.copyOf(ids);
    }

    /**
     * Gate used immediately before execution. Auxiliary tools are allowed, but
     * a capability belonging to a later explicit task cannot run before the
     * current task has completed.
     */
    public synchronized ExecutionGate gate(String toolId) {
        String id = safe(toolId);
        if (id.isBlank() || objectives.isEmpty()) return ExecutionGate.auxiliary();
        int current = currentTaskIndexInternal(true);
        if (current <= 0) return ExecutionGate.complete();

        boolean currentMatch = false;
        boolean futureMatch = false;
        int firstFuture = Integer.MAX_VALUE;
        for (Objective objective : objectives) {
            if (!objective.toolId.equals(id)
                    || objective.state == State.COMPLETED
                    || objective.state == State.CANCELLED) continue;
            if (objective.taskIndex == current) currentMatch = true;
            if (objective.taskIndex > current) {
                futureMatch = true;
                firstFuture = Math.min(firstFuture, objective.taskIndex);
            }
        }
        if (currentMatch) return new ExecutionGate(true, false, current, current, "current_task");
        if (futureMatch && consequential(toolId)) {
            return new ExecutionGate(false, true, current, firstFuture,
                    "tool belongs to later task " + firstFuture + " while task " + current + " is still unresolved");
        }
        return ExecutionGate.auxiliary(current);
    }

    /**
     * Validates a plan segment against ordered task multiplicity. A segment may
     * stop anywhere and the next segment can continue later, but it may not
     * execute a future requested action before the current task frontier.
     */
    public synchronized PlanAlignment alignPlan(ValidatedAutomationPlan plan) {
        if (plan == null || objectives.isEmpty()) return PlanAlignment.accepted();
        List<SimulatedObjective> simulation = objectives.stream()
                .map(value -> new SimulatedObjective(value.taskIndex, value.toolId,
                        value.state == State.COMPLETED || value.state == State.CANCELLED))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        for (ValidatedAutomationPlan.Step step : plan.steps()) {
            String toolId = safe(step.toolId());
            int current = simulatedCurrentTask(simulation);
            if (current <= 0) continue;

            int currentMatch = firstSimulatedMatch(simulation, current, toolId);
            if (currentMatch >= 0) {
                simulation.get(currentMatch).done = true;
                continue;
            }

            int future = firstFutureTask(simulation, current, toolId);
            int unresolvedCurrent = firstUnresolvedSimulatedMatch(simulation, current);
            if (unresolvedCurrent >= 0 && consequential(toolId) && future <= current) {
                SimulatedObjective bound = simulation.get(unresolvedCurrent);
                bound.toolId = toolId;
                bound.done = true;
                continue;
            }
            if (future > current && consequential(toolId)) {
                return new PlanAlignment(false, current, future, toolId,
                        "Plan step " + step.index() + " uses " + toolId
                                + " for task " + future + " before task " + current + " is complete.");
            }
            // A capability not represented by an explicit task is supporting
            // inspection/verification/recovery and does not move the frontier.
        }
        return PlanAlignment.accepted();
    }

    public synchronized List<Objective> snapshot() {
        return List.copyOf(objectives);
    }

    public synchronized boolean satisfied() {
        return objectives.stream().noneMatch(value -> value.state == State.PENDING || value.state == State.BLOCKED);
    }

    public synchronized boolean allCompleted() {
        return objectives.stream().allMatch(value -> value.state == State.COMPLETED);
    }

    public synchronized int totalTaskCount() {
        return objectives.stream().mapToInt(Objective::taskIndex).max().orElse(0);
    }

    public synchronized int completedTaskCount() {
        int total = totalTaskCount();
        int completed = 0;
        for (int index = 1; index <= total; index++) {
            final int task = index;
            List<Objective> group = objectives.stream().filter(value -> value.taskIndex == task).toList();
            if (!group.isEmpty() && group.stream().allMatch(value -> value.state == State.COMPLETED)) completed++;
        }
        return completed;
    }

    public synchronized int currentTaskIndex() {
        return currentTaskIndexInternal(true);
    }

    public synchronized String currentTaskText() {
        int current = currentTaskIndexInternal(true);
        if (current <= 0) return "";
        return objectives.stream().filter(value -> value.taskIndex == current)
                .map(Objective::text).findFirst().orElse("");
    }

    public synchronized List<Objective> currentObjectives() {
        int current = currentTaskIndexInternal(true);
        if (current <= 0) return List.of();
        return objectives.stream()
                .filter(value -> value.taskIndex == current)
                .filter(value -> value.state != State.COMPLETED && value.state != State.CANCELLED)
                .toList();
    }

    public synchronized boolean isCurrentTaskTool(String toolId) {
        String id = safe(toolId);
        if (id.isBlank()) return false;
        int current = currentTaskIndexInternal(true);
        return current > 0 && objectives.stream().anyMatch(value -> value.taskIndex == current
                && value.state != State.COMPLETED && value.state != State.CANCELLED
                && value.toolId.equals(id));
    }

    /**
     * True when argument grounding for this capability should use only the
     * current task clause. Besides already-bound tools, an unresolved current
     * task may accept a consequential capability as its late-bound action so
     * long as that capability is not explicitly reserved for a later task.
     */
    public synchronized boolean shouldGroundAgainstCurrentTask(String toolId) {
        String id = safe(toolId);
        if (id.isBlank()) return false;
        int current = currentTaskIndexInternal(true);
        if (current <= 0) return false;
        boolean currentExact = false;
        boolean currentUnresolved = false;
        boolean futureExact = false;
        for (Objective objective : objectives) {
            if (objective.state == State.COMPLETED || objective.state == State.CANCELLED) continue;
            if (objective.taskIndex == current) {
                if (objective.toolId.equals(id)) currentExact = true;
                if (objective.toolId.isBlank()) currentUnresolved = true;
            } else if (objective.taskIndex > current && objective.toolId.equals(id)) {
                futureExact = true;
            }
        }
        if (currentExact) return true;
        return currentUnresolved && !futureExact && consequential(id);
    }

    public synchronized int remainingTaskCount() {
        return Math.max(0, totalTaskCount() - completedTaskCount());
    }

    public synchronized String promptSummary() {
        int total = totalTaskCount();
        if (total <= 1) return "";
        int completed = completedTaskCount();
        int current = currentTaskIndexInternal(true);
        StringBuilder out = new StringBuilder("Ordered task ledger (authoritative; preserve multiplicity and order):\n")
                .append("progress: ").append(completed).append('/').append(total).append(" tasks completed\n");
        if (current <= 0) {
            out.append("status: every explicit task is complete");
            return out.toString();
        }
        out.append("current task: ").append(current).append('/').append(total)
                .append(" — ").append(currentTaskText()).append('\n')
                .append("current required capabilities: ").append(toolIdsForCurrent(true).isEmpty()
                        ? "[unresolved: select a supported capability for this task]"
                        : toolIdsForCurrent(true)).append('\n');

        Map<Integer, String> taskTexts = new LinkedHashMap<>();
        for (Objective objective : objectives) taskTexts.putIfAbsent(objective.taskIndex, objective.text);
        int shown = 0;
        for (int index = current + 1; index <= total && shown < SUMMARY_WINDOW; index++) {
            String text = taskTexts.get(index);
            if (text == null) continue;
            out.append("next task ").append(index).append('/').append(total).append(": ").append(text).append('\n');
            shown++;
        }
        if (current + shown < total) {
            out.append("later tasks not expanded here: ").append(total - current - shown).append('\n');
        }
        out.append("Task rule: execute and verify the current indexed task before any later requested action. "
                + "A successful capability occurrence satisfies only one matching task occurrence. "
                + "Do not finalize until progress reaches ").append(total).append('/').append(total).append('.');
        return out.toString().strip();
    }

    private synchronized Set<String> toolIdsForCurrent(boolean includeBlocked) {
        // The ordered frontier never skips a blocked earlier task. The flag
        // controls whether the blocked capability is returned, not which task
        // is considered current.
        int current = currentTaskIndexInternal(true);
        if (current <= 0) return Set.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        objectives.stream()
                .filter(value -> value.taskIndex == current)
                .filter(value -> value.state == State.PENDING || includeBlocked && value.state == State.BLOCKED)
                .map(Objective::toolId)
                .filter(id -> id != null && !id.isBlank())
                .forEach(ids::add);
        return Set.copyOf(ids);
    }

    private int currentTaskIndexInternal(boolean includeBlocked) {
        return objectives.stream()
                .filter(value -> value.state == State.PENDING || includeBlocked && value.state == State.BLOCKED)
                .mapToInt(Objective::taskIndex)
                .min().orElse(0);
    }

    private static int simulatedCurrentTask(List<SimulatedObjective> values) {
        return values.stream().filter(value -> !value.done).mapToInt(value -> value.taskIndex).min().orElse(0);
    }

    private static int firstSimulatedMatch(List<SimulatedObjective> values, int task, String toolId) {
        for (int i = 0; i < values.size(); i++) {
            SimulatedObjective value = values.get(i);
            if (!value.done && value.taskIndex == task && value.toolId.equals(toolId)) return i;
        }
        return -1;
    }

    private static int firstFutureTask(List<SimulatedObjective> values, int current, String toolId) {
        return values.stream()
                .filter(value -> !value.done && value.taskIndex > current && value.toolId.equals(toolId))
                .mapToInt(value -> value.taskIndex).min().orElse(0);
    }

    private static int firstUnresolvedSimulatedMatch(List<SimulatedObjective> values, int task) {
        for (int i = 0; i < values.size(); i++) {
            SimulatedObjective value = values.get(i);
            if (!value.done && value.taskIndex == task && value.toolId.isBlank()) return i;
        }
        return -1;
    }

    private static List<String> orderedClauses(String source) {
        if (source == null || source.isBlank()) return List.of();
        String sequenceSource = LIKELY_THEN_TYPO.matcher(source).replaceAll("then");
        List<String> primary = new ArrayList<>();
        for (String value : PRIMARY_SEQUENCE.split(sequenceSource)) {
            String clean = normalizeText(value);
            if (!clean.isBlank()) primary.add(clean);
        }
        List<String> output = new ArrayList<>();
        for (String value : primary) {
            String[] comma = COMMA_SEQUENCE.split(value);
            if (comma.length <= 1) {
                output.add(value);
                continue;
            }
            int actionable = 0;
            for (String candidate : comma) {
                if (!LocalModelToolCatalog.requiredToolIdsForPrompt(normalizeText(candidate)).isEmpty()) actionable++;
            }
            if (actionable >= 2) {
                for (String candidate : comma) {
                    String clean = normalizeText(candidate);
                    if (!clean.isBlank()) output.add(clean);
                }
            } else {
                output.add(value);
            }
        }
        return List.copyOf(output);
    }

    private static boolean consequential(String toolId) {
        return LocalModelToolCatalog.definition(toolId)
                .map(definition -> definition.confirmationRequired() || !definition.sideEffects().isEmpty())
                .orElse(false);
    }

    private static String evidenceRequirement(String toolId) {
        if (toolId.startsWith("workspace.")) return "completed result, filesystem reread, and matching resulting hash";
        if (toolId.startsWith("development.")) return "completed process with exit code 0";
        if (toolId.startsWith("minecraft.command")) return "command feedback or resulting-state observation";
        return "completed structured result with required validation";
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    public enum State { PENDING, COMPLETED, BLOCKED, CANCELLED }

    public record Objective(
            String id,
            int taskIndex,
            int withinTaskIndex,
            String text,
            String toolId,
            String requiredEvidence,
            State state,
            String resultStatus,
            String failureCode
    ) {}


    public record CallBinding(
            boolean bound, String objectiveId, int taskIndex, int withinTaskIndex, String taskText, String toolId
    ) {
        private static CallBinding unbound() {
            return new CallBinding(false, "", 0, 0, "", "");
        }
    }

    public record RecordOutcome(boolean matchedObjective, int taskBefore, int taskAfter, boolean taskAdvanced) {}

    public record ExecutionGate(boolean allowed, boolean laterTask, int currentTask, int targetTask, String reason) {
        private static ExecutionGate auxiliary() {
            return new ExecutionGate(true, false, 0, 0, "auxiliary_or_untracked");
        }

        private static ExecutionGate auxiliary(int current) {
            return new ExecutionGate(true, false, current, current, "auxiliary_or_supporting_tool");
        }

        private static ExecutionGate complete() {
            return new ExecutionGate(true, false, 0, 0, "all_tasks_complete");
        }
    }

    public record PlanAlignment(boolean valid, int currentTask, int targetTask, String toolId, String detail) {
        private static PlanAlignment accepted() {
            return new PlanAlignment(true, 0, 0, "", "ordered prefix accepted");
        }
    }

    private static final class SimulatedObjective {
        private final int taskIndex;
        private String toolId;
        private boolean done;

        private SimulatedObjective(int taskIndex, String toolId, boolean done) {
            this.taskIndex = taskIndex;
            this.toolId = toolId;
            this.done = done;
        }
    }
}
