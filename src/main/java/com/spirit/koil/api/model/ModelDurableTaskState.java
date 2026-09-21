package com.spirit.koil.api.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compact exact state retained independently from trimmed transcript history. */
public final class ModelDurableTaskState {
    private final String objective;
    private String planId = "";
    private final Map<String, Integer> requiredToolCounts = new LinkedHashMap<>();
    private final Map<String, Integer> completedToolCounts = new LinkedHashMap<>();
    private final Map<Integer, TaskProgress> taskProgress = new LinkedHashMap<>();
    private final Map<String, String> pathHashes = new LinkedHashMap<>();
    private final Set<String> changedFiles = new LinkedHashSet<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final Set<String> validations = new LinkedHashSet<>();

    public ModelDurableTaskState(String objective, List<ModelObjectiveLedger.Objective> orderedObjectives) {
        this.objective = objective == null ? "" : objective;
        if (orderedObjectives != null) {
            for (ModelObjectiveLedger.Objective item : orderedObjectives) {
                if (item == null) continue;
                TaskProgress existing = taskProgress.get(item.taskIndex());
                if (existing == null) {
                    taskProgress.put(item.taskIndex(), new TaskProgress(item.text(), new LinkedHashSet<>(), new LinkedHashSet<>(), "pending"));
                    existing = taskProgress.get(item.taskIndex());
                }
                if (item.toolId() == null || item.toolId().isBlank()) continue;
                requiredToolCounts.merge(item.toolId(), 1, Integer::sum);
                existing.requiredTools().add(item.toolId());
            }
        }
    }

    public synchronized void plan(String id) { this.planId = id == null ? "" : id; }

    public synchronized void clearPlan() { this.planId = ""; }

    public synchronized void record(ModelToolResult result, ModelObjectiveLedger.RecordOutcome outcome) {
        if (result == null) return;
        boolean matchedOrderedObjective = outcome != null && outcome.matchedObjective();
        if (matchedOrderedObjective && result.completedAndValidated()) {
            TaskProgress progress = outcome.taskBefore() > 0 ? taskProgress.get(outcome.taskBefore()) : null;
            // A task that was unresolved when the ledger was created can be
            // late-bound to a concrete capability. Promote that occurrence
            // into the durable capability counts exactly once when its bound
            // result arrives so repeated late-bound tasks retain multiplicity.
            if (progress != null && !progress.requiredTools().contains(result.toolId())) {
                progress.requiredTools().add(result.toolId());
                requiredToolCounts.merge(result.toolId(), 1, Integer::sum);
            }
            int required = requiredToolCounts.getOrDefault(result.toolId(), 0);
            int completed = completedToolCounts.getOrDefault(result.toolId(), 0);
            if (required <= 0 || completed < required) {
                completedToolCounts.put(result.toolId(), completed + 1);
            }
            failures.remove(result.toolId());
            if (progress != null) {
                progress.completedTools().add(result.toolId());
                if (outcome.taskAdvanced()) {
                    progress.status("completed");
                }
            }
        } else if (!result.completedAndValidated()) {
            failures.put(result.toolId(), result.status() + ":" + result.failureCode());
            if (outcome != null && outcome.taskBefore() > 0) {
                TaskProgress progress = taskProgress.get(outcome.taskBefore());
                if (progress != null) progress.status("pending_recovery");
            }
        }
        JsonObject output = result.output();
        String path = string(output, "path");
        String hash = string(output, "resultingContentHash");
        if (hash.isBlank()) hash = string(output, "contentHash");
        if (!path.isBlank() && !hash.isBlank()) pathHashes.put(path, hash);
        if (!path.isBlank() && !result.changedTargets().isEmpty()) changedFiles.add(path);
        if ("passed".equals(result.validationStatus())) validations.add(result.toolId());
    }

    public synchronized String promptSummary() {
        StringBuilder value = new StringBuilder("Durable task state (authoritative; do not recreate from memory):\n")
                .append("objective: ").append(objective).append('\n');
        if (!planId.isBlank()) value.append("active_plan: ").append(planId).append('\n');
        value.append("task_capability_progress: ").append(capabilityProgress()).append('\n')
                .append("task_occurrence_progress: ").append(taskOccurrenceProgress()).append('\n')
                .append("path_hashes: ").append(pathHashes).append('\n')
                .append("changed_files: ").append(changedFiles).append('\n')
                .append("validation_passed: ").append(validations).append('\n')
                .append("failures: ").append(failures);
        return value.toString();
    }

    private Map<String, String> capabilityProgress() {
        LinkedHashMap<String, String> progress = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : requiredToolCounts.entrySet()) {
            int completed = Math.min(entry.getValue(), completedToolCounts.getOrDefault(entry.getKey(), 0));
            progress.put(entry.getKey(), completed + "/" + entry.getValue());
        }
        return progress;
    }

    private Map<Integer, String> taskOccurrenceProgress() {
        LinkedHashMap<Integer, String> out = new LinkedHashMap<>();
        int firstOpen = taskProgress.entrySet().stream()
                .filter(entry -> !"completed".equals(entry.getValue().status()))
                .mapToInt(Map.Entry::getKey).min().orElse(0);
        int shown = 0;
        for (Map.Entry<Integer, TaskProgress> entry : taskProgress.entrySet()) {
            if (firstOpen > 0 && entry.getKey() < firstOpen) continue;
            if (shown++ >= 8) break;
            TaskProgress task = entry.getValue();
            out.put(entry.getKey(), task.status() + " | " + task.text()
                    + " | tools=" + task.completedTools().size() + "/" + task.requiredTools().size());
        }
        if (firstOpen > 0 && taskProgress.size() > firstOpen + 7) {
            out.put(-1, "later task occurrences omitted from prompt summary: " + Math.max(0, taskProgress.size() - firstOpen - 7));
        }
        return out;
    }

    private static final class TaskProgress {
        private final String text;
        private final Set<String> requiredTools;
        private final Set<String> completedTools;
        private String status;

        private TaskProgress(String text, Set<String> requiredTools, Set<String> completedTools, String status) {
            this.text = text == null ? "" : text;
            this.requiredTools = requiredTools;
            this.completedTools = completedTools;
            this.status = status == null ? "pending" : status;
        }
        private String text() { return text; }
        private Set<String> requiredTools() { return requiredTools; }
        private Set<String> completedTools() { return completedTools; }
        private String status() { return status; }
        private void status(String value) { this.status = value == null ? "pending" : value; }
    }

    private static String string(JsonObject object, String key) {
        try { JsonElement value = object.get(key); return value == null || value.isJsonNull() ? "" : value.getAsString(); }
        catch (RuntimeException ignored) { return ""; }
    }
}
