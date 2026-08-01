package com.spirit.koil.api.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Compact exact state retained independently from trimmed transcript history. */
public final class ModelDurableTaskState {
    private final String objective;
    private String planId = "";
    private final Set<String> completedTools = new LinkedHashSet<>();
    private final Set<String> unresolvedTools = new LinkedHashSet<>();
    private final Map<String, String> pathHashes = new LinkedHashMap<>();
    private final Set<String> changedFiles = new LinkedHashSet<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final Set<String> validations = new LinkedHashSet<>();

    public ModelDurableTaskState(String objective, Set<String> requiredTools) {
        this.objective = objective == null ? "" : objective;
        if (requiredTools != null) this.unresolvedTools.addAll(requiredTools);
    }

    public synchronized void plan(String id) { this.planId = id == null ? "" : id; }

    public synchronized void record(ModelToolResult result) {
        if (result == null) return;
        if (result.completedAndValidated()) {
            completedTools.add(result.toolId()); unresolvedTools.remove(result.toolId()); failures.remove(result.toolId());
        } else {
            failures.put(result.toolId(), result.status() + ":" + result.failureCode());
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
        value.append("completed_tools: ").append(completedTools).append('\n')
                .append("unresolved_tools: ").append(unresolvedTools).append('\n')
                .append("path_hashes: ").append(pathHashes).append('\n')
                .append("changed_files: ").append(changedFiles).append('\n')
                .append("validation_passed: ").append(validations).append('\n')
                .append("failures: ").append(failures);
        return value.toString();
    }

    private static String string(JsonObject object, String key) {
        try { JsonElement value = object.get(key); return value == null || value.isJsonNull() ? "" : value.getAsString(); }
        catch (RuntimeException ignored) { return ""; }
    }
}
