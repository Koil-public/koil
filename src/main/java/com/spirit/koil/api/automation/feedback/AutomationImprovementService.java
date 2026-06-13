package com.spirit.koil.api.automation.feedback;

import com.google.gson.*;
import com.spirit.koil.api.automation.AutomationReporter;
import com.spirit.koil.api.automation.cli.AutomationCliViewModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public final class AutomationImprovementService {
    private static final Path EVENTS = Path.of("koil/automation/feedback/events.jsonl");
    private static final Path OUTPUT_ROOT = Path.of("koil/automation/improvements");
    private static final Path PLAN_FILE = OUTPUT_ROOT.resolve("improvement-plan.json");
    private static final Path KTL_INDEX_FILE = OUTPUT_ROOT.resolve("generated-feedback-improvements.ktl");
    private static final Path PATCH_QUEUE_FILE = OUTPUT_ROOT.resolve("patch-queue.jsonl");
    private static final Path RENDER_INDEX_FILE = OUTPUT_ROOT.resolve("automation-screen-nodes.json");
    private static final Path CANDIDATE_ROOT = OUTPUT_ROOT.resolve("candidates");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson JSONL = new GsonBuilder().disableHtmlEscaping().create();

    private AutomationImprovementService() {
    }

    public static ImprovementSummary improve() {
        List<JsonObject> events = readEvents();
        if (events.isEmpty()) {
            AutomationReporter.info("[info]", "improve = no feedback events found");
            AutomationCliViewModel.improvementSummary(0, 0, PLAN_FILE.toString(), PATCH_QUEUE_FILE.toString(), RENDER_INDEX_FILE.toString());
            return new ImprovementSummary(0, 0, List.of(), PLAN_FILE.toString(), KTL_INDEX_FILE.toString(), PATCH_QUEUE_FILE.toString(), RENDER_INDEX_FILE.toString());
        }
        Map<String, ImprovementGroup> groups = groupEvents(events);
        writePlan(groups.values(), events.size());
        writeKtlIndex(groups.values());
        writePatchQueue(groups.values());
        writeRenderIndex(groups.values());
        for (ImprovementGroup group : groups.values()) {
            String patchFile = writeCandidatePatch(group);
            AutomationCliViewModel.improvementCandidate(group.key(), group.source(), group.nodeType(), group.failureType(), group.count(), "candidate_generated", patchFile);
        }
        AutomationCliViewModel.improvementSummary(events.size(), groups.size(), PLAN_FILE.toString(), PATCH_QUEUE_FILE.toString(), RENDER_INDEX_FILE.toString());
        AutomationReporter.done("[done]", "improve.events = " + events.size() + " groups = " + groups.size());
        AutomationReporter.info("[info]", "improve.plan = " + PLAN_FILE);
        AutomationReporter.info("[info]", "improve.patch_queue = " + PATCH_QUEUE_FILE);
        AutomationReporter.info("[info]", "improve.render_nodes = " + RENDER_INDEX_FILE);
        return new ImprovementSummary(events.size(), groups.size(), groups.values().stream().map(ImprovementGroup::source).distinct().toList(), PLAN_FILE.toString(), KTL_INDEX_FILE.toString(), PATCH_QUEUE_FILE.toString(), RENDER_INDEX_FILE.toString());
    }

    private static Map<String, ImprovementGroup> groupEvents(List<JsonObject> events) {
        Map<String, ImprovementGroup> groups = new LinkedHashMap<>();
        for (JsonObject event : events) {
            String source = sourcePath(event);
            String nodeType = string(event, "node_type", "ui");
            String failureType = string(event, "failure_type", "unknown");
            String nodeId = string(event, "node_id", "");
            String key = safeKey(source + "|" + nodeType + "|" + failureType);
            ImprovementGroup group = groups.computeIfAbsent(key, ignored -> new ImprovementGroup(key, source, nodeType, failureType));
            group.count++;
            if (!nodeId.isBlank()) {
                group.nodes.add(nodeId);
            }
            String label = string(event, "failure_label", "");
            if (!label.isBlank()) {
                group.labels.add(label);
            }
            JsonElement rules = event.get("suggested_fix_rules");
            if (rules != null && rules.isJsonArray()) {
                for (JsonElement rule : rules.getAsJsonArray()) {
                    String value = rule.getAsString();
                    if (!value.isBlank()) {
                        group.rules.add(value);
                    }
                }
            }
            JsonObject hooks = event.has("future_hooks") && event.get("future_hooks").isJsonObject() ? event.getAsJsonObject("future_hooks") : new JsonObject();
            addIfPresent(group.hooks, "auto_fix_rule", string(hooks, "auto_fix_rule", ""));
            addIfPresent(group.hooks, "patch_generator", string(hooks, "patch_generator", ""));
            addIfPresent(group.hooks, "learning_hook", string(hooks, "learning_hook", ""));
            JsonObject context = event.has("execution_context") && event.get("execution_context").isJsonObject() ? event.getAsJsonObject("execution_context") : new JsonObject();
            addIfPresent(group.contextValues, "frame_id", string(context, "frame_id", ""));
            addIfPresent(group.contextValues, "value", string(context, "value", ""));
        }
        return groups;
    }

    private static void writePlan(Collection<ImprovementGroup> groups, int eventCount) {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 2);
        root.addProperty("generated_at", Instant.now().toString());
        root.addProperty("source", EVENTS.toString());
        root.addProperty("event_count", eventCount);
        JsonArray entries = new JsonArray();
        for (ImprovementGroup group : groups) {
            JsonObject entry = groupJson(group);
            entry.addProperty("status", "candidate_generated");
            entry.addProperty("deterministic_note", noteFor(group));
            entry.addProperty("candidate_patch", candidatePath(group).toString());
            entry.addProperty("safe_apply_policy", "manual_review_required");
            entries.add(entry);
            writeSourceSidecar(group);
        }
        root.add("improvements", entries);
        writeJson(PLAN_FILE, root);
    }

    private static void writeSourceSidecar(ImprovementGroup group) {
        JsonObject root = groupJson(group);
        root.addProperty("status", "candidate_generated");
        root.addProperty("deterministic_note", noteFor(group));
        root.addProperty("candidate_patch", candidatePath(group).toString());
        root.addProperty("safe_apply_policy", "manual_review_required");
        Path sidecar = OUTPUT_ROOT.resolve("by_source").resolve(safeKey(group.source()) + ".json");
        writeJson(sidecar, root);
    }

    private static void writeKtlIndex(Collection<ImprovementGroup> groups) {
        StringBuilder builder = new StringBuilder();
        builder.append("version: 1\n");
        builder.append("kind: namespace_pack\n");
        builder.append("id: automation.feedback.improvements\n");
        builder.append("generated_at: \"").append(Instant.now()).append("\"\n");
        builder.append("improvements:\n");
        for (ImprovementGroup group : groups) {
            builder.append("  - id: \"").append(escapeYaml(group.key())).append("\"\n");
            builder.append("    source_file: \"").append(escapeYaml(group.source())).append("\"\n");
            builder.append("    node_type: \"").append(escapeYaml(group.nodeType())).append("\"\n");
            builder.append("    failure_type: \"").append(escapeYaml(group.failureType())).append("\"\n");
            builder.append("    count: ").append(group.count()).append("\n");
            builder.append("    status: candidate_generated\n");
            builder.append("    candidate_patch: \"").append(escapeYaml(candidatePath(group).toString())).append("\"\n");
            builder.append("    suggested_fix_rules:\n");
            for (String rule : group.rules) {
                builder.append("      - \"").append(escapeYaml(rule)).append("\"\n");
            }
        }
        writeText(KTL_INDEX_FILE, builder.toString());
    }

    private static void writePatchQueue(Collection<ImprovementGroup> groups) {
        StringBuilder builder = new StringBuilder();
        for (ImprovementGroup group : groups) {
            JsonObject entry = groupJson(group);
            entry.addProperty("queued_at", Instant.now().toString());
            entry.addProperty("candidate_patch", candidatePath(group).toString());
            entry.addProperty("apply_state", "pending_manual_review");
            entry.addProperty("risk", riskFor(group));
            builder.append(JSONL.toJson(entry)).append(System.lineSeparator());
        }
        writeText(PATCH_QUEUE_FILE, builder.toString());
    }

    private static void writeRenderIndex(Collection<ImprovementGroup> groups) {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 2);
        root.addProperty("generated_at", Instant.now().toString());
        root.addProperty("render_mode", "list");
        root.addProperty("node_shape", "text_only");
        JsonArray files = new JsonArray();
        Map<String, List<ImprovementGroup>> byFile = new LinkedHashMap<>();
        for (ImprovementGroup group : groups) {
            byFile.computeIfAbsent(group.source(), ignored -> new ArrayList<>()).add(group);
        }
        for (Map.Entry<String, List<ImprovementGroup>> entry : byFile.entrySet()) {
            JsonObject file = new JsonObject();
            file.addProperty("id", safeKey(entry.getKey()));
            file.addProperty("source_file", entry.getKey());
            file.addProperty("label", shortSource(entry.getKey()));
            int count = 0;
            JsonArray failures = new JsonArray();
            for (ImprovementGroup group : entry.getValue()) {
                count += group.count();
                JsonObject failure = new JsonObject();
                failure.addProperty("node_type", group.nodeType());
                failure.addProperty("failure_type", group.failureType());
                failure.addProperty("count", group.count());
                failure.addProperty("candidate_patch", candidatePath(group).toString());
                failure.addProperty("note", noteFor(group));
                failures.add(failure);
            }
            file.addProperty("feedback_count", count);
            file.add("failures", failures);
            files.add(file);
        }
        root.add("files", files);
        writeJson(RENDER_INDEX_FILE, root);
    }

    private static String writeCandidatePatch(ImprovementGroup group) {
        Path path = candidatePath(group);
        StringBuilder builder = new StringBuilder();
        builder.append("id: ").append(group.key()).append("\n");
        builder.append("source_file: ").append(group.source()).append("\n");
        builder.append("node_type: ").append(group.nodeType()).append("\n");
        builder.append("failure_type: ").append(group.failureType()).append("\n");
        builder.append("feedback_count: ").append(group.count()).append("\n");
        builder.append("status: candidate_generated\n");
        builder.append("safe_apply_policy: manual_review_required\n");
        builder.append("candidate_changes:\n");
        for (String line : candidateChanges(group)) {
            builder.append("  - ").append(line).append("\n");
        }
        builder.append("suggested_fix_rules:\n");
        for (String rule : group.rules) {
            builder.append("  - ").append(rule).append("\n");
        }
        builder.append("render_hint:\n");
        builder.append("  label: ").append(group.nodeType()).append(" / ").append(group.failureType()).append("\n");
        builder.append("  detail: count=").append(group.count()).append(" source=").append(group.source()).append("\n");
        writeText(path, builder.toString());
        return path.toString();
    }

    private static List<String> candidateChanges(ImprovementGroup group) {
        List<String> changes = new ArrayList<>();
        switch (group.nodeType()) {
            case "movement" -> {
                if (group.failureType().contains("stuck")) {
                    changes.add("insert or verify delegate: automation/movement/recovery/recover_stuck.ktl before returning failure");
                    changes.add("store blocked position and mark local cell before replanning");
                } else if (group.failureType().contains("overshoot")) {
                    changes.add("lower final stop distance and add precision finish near target");
                    changes.add("slow movement input when remaining distance is under one block");
                } else if (group.failureType().contains("direction")) {
                    changes.add("capture player yaw before movement and verify direction binding");
                } else {
                    changes.add("add local replan and arrival verification around movement node");
                }
            }
            case "inventory" -> {
                changes.add("add before/after inventory count confirmation");
                changes.add("retry selection or transfer only after screen and slot state are confirmed");
            }
            case "combat" -> {
                changes.add("refresh target entity before each attack attempt");
                changes.add("verify range and crosshair alignment before attack primitive");
            }
            case "block" -> {
                changes.add("verify block target, reach distance, and interaction face before action");
                changes.add("retry with updated raytrace result after one failed interaction");
            }
            default -> {
                changes.add("capture command or UI state before and after the node");
                changes.add("gate the node with a deterministic state check before returning success");
            }
        }
        if (changes.isEmpty()) {
            changes.add("review failure registry rules and add a deterministic guard to the source task");
        }
        return changes;
    }

    private static List<JsonObject> readEvents() {
        if (!Files.exists(EVENTS)) {
            return List.of();
        }
        List<JsonObject> events = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(EVENTS, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                JsonElement element = JsonParser.parseString(line);
                if (element.isJsonObject()) {
                    events.add(element.getAsJsonObject());
                }
            }
        } catch (IOException | JsonParseException exception) {
            AutomationReporter.fail("[fail]", "improve.read = " + exception.getMessage());
        }
        return events;
    }

    private static JsonObject groupJson(ImprovementGroup group) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", group.key());
        entry.addProperty("source_file", group.source());
        entry.addProperty("node_type", group.nodeType());
        entry.addProperty("failure_type", group.failureType());
        entry.addProperty("count", group.count());
        entry.add("nodes", stringArray(group.nodes));
        entry.add("labels", stringArray(group.labels));
        entry.add("suggested_fix_rules", stringArray(group.rules));
        JsonObject hooks = new JsonObject();
        for (Map.Entry<String, Set<String>> entryValue : group.hooks.entrySet()) {
            hooks.add(entryValue.getKey(), stringArray(entryValue.getValue()));
        }
        entry.add("future_hooks", hooks);
        return entry;
    }

    private static JsonObject renderNode(String id, String type, String label, String detail, String tooltip) {
        JsonObject node = new JsonObject();
        node.addProperty("id", id);
        node.addProperty("type", type);
        node.addProperty("label", label);
        node.addProperty("detail", detail);
        node.addProperty("tooltip", tooltip);
        return node;
    }

    private static JsonObject renderEdge(String from, String to, String label) {
        JsonObject edge = new JsonObject();
        edge.addProperty("from", from);
        edge.addProperty("to", to);
        edge.addProperty("label", label);
        return edge;
    }

    private static String noteFor(ImprovementGroup group) {
        return switch (group.nodeType()) {
            case "movement" -> "Review movement progress checks, recovery delegation, local replanning, and final arrival verification.";
            case "inventory" -> "Review item resolution, selected slot verification, screen sync, transfer retry, and before/after count confirmation.";
            case "combat" -> "Review target refresh, range checks, facing, cooldown timing, and death confirmation.";
            case "block" -> "Review block target resolution, raytrace face selection, reach distance, and state-change confirmation.";
            default -> "Review command, UI, condition, or parent-child runtime state assumptions.";
        };
    }

    private static String riskFor(ImprovementGroup group) {
        if (group.nodeType().equals("ui") || group.failureType().contains("command")) {
            return "medium";
        }
        if (group.count() >= 5) {
            return "low_repeated_pattern";
        }
        return "low_candidate_only";
    }

    private static JsonArray stringArray(Collection<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static void addIfPresent(Map<String, Set<String>> target, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        target.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(value);
    }

    private static String sourcePath(JsonObject event) {
        JsonObject context = event.has("execution_context") && event.get("execution_context").isJsonObject() ? event.getAsJsonObject("execution_context") : new JsonObject();
        String source = string(context, "source", "");
        if (source.isBlank()) {
            source = string(event, "node_label", "unknown");
        }
        int line = source.indexOf(".ktl:");
        if (line >= 0) {
            return source.substring(0, line + 4);
        }
        return source;
    }

    private static String string(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        return object.get(key).getAsString();
    }


    private static String shortSource(String source) {
        String clean = source == null || source.isBlank() ? "unknown.ktl" : source;
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        return slash >= 0 && slash < clean.length() - 1 ? clean.substring(slash + 1) : clean;
    }

    private static Path candidatePath(ImprovementGroup group) {
        return CANDIDATE_ROOT.resolve(safeKey(group.key()) + ".ktl.patch");
    }

    private static void writeJson(Path path, JsonObject object) {
        writeText(path, GSON.toJson(object));
    }

    private static void writeText(Path path, String text) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            AutomationReporter.fail("[fail]", "improve.write = " + path + " :: " + exception.getMessage());
        }
    }

    private static String safeKey(String value) {
        String safe = value == null ? "" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.isBlank() ? "unknown" : safe;
    }

    private static String escapeYaml(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record ImprovementSummary(int eventCount, int groupCount, List<String> sourceFiles, String planFile, String ktlIndexFile, String patchQueueFile, String renderIndexFile) {
    }

    private static final class ImprovementGroup {
        private final String key;
        private final String source;
        private final String nodeType;
        private final String failureType;
        private final Set<String> nodes = new LinkedHashSet<>();
        private final Set<String> labels = new LinkedHashSet<>();
        private final Set<String> rules = new LinkedHashSet<>();
        private final Map<String, Set<String>> hooks = new LinkedHashMap<>();
        private final Map<String, Set<String>> contextValues = new LinkedHashMap<>();
        private int count;

        private ImprovementGroup(String key, String source, String nodeType, String failureType) {
            this.key = key == null || key.isBlank() ? "unknown" : key;
            this.source = source == null || source.isBlank() ? "unknown" : source;
            this.nodeType = nodeType == null || nodeType.isBlank() ? "ui" : nodeType;
            this.failureType = failureType == null || failureType.isBlank() ? "unknown" : failureType;
        }

        private String key() {
            return key;
        }

        private String source() {
            return source;
        }

        private String nodeType() {
            return nodeType;
        }

        private String failureType() {
            return failureType;
        }

        private int count() {
            return count;
        }
    }
}
