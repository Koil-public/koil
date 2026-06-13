package com.spirit.koil.api.automation.feedback;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.spirit.koil.api.automation.cli.AutomationCliRow;
import com.spirit.koil.api.automation.cli.AutomationCliSnapshot;
import com.spirit.koil.api.automation.cli.AutomationCliViewModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;

public final class AutomationFeedbackService {
    private static final Path EVENTS = Path.of("koil/automation/feedback/events.jsonl");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static PendingFlow pending = PendingFlow.empty();

    private AutomationFeedbackService() {
    }

    public static synchronized boolean handleConsoleInput(String input) {
        String trimmed = input == null ? "" : input.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (trimmed.isBlank()) {
            return false;
        }
        if (!lower.startsWith("/feedback") && !lower.startsWith("feedback ") && !lower.equals("feedback")) {
            return false;
        }
        String body = lower.startsWith("/feedback") ? trimmed.substring("/feedback".length()).trim() : trimmed.substring("feedback".length()).trim();
        if (body.isBlank()) {
            AutomationCliViewModel.feedbackHelp();
            return true;
        }
        String bodyLower = body.toLowerCase(Locale.ROOT);
        if (bodyLower.equals("good")) {
            submitGood();
            return true;
        }
        if (bodyLower.equals("bad")) {
            startBadFlow(AutomationCliViewModel.snapshot());
            return true;
        }
        if (bodyLower.equals("cancel")) {
            cancelPending();
            return true;
        }
        if (bodyLower.equals("files") || bodyLower.equals("sources")) {
            if (pending.nodes.isEmpty()) {
                startBadFlow(AutomationCliViewModel.snapshot());
            } else {
                AutomationCliViewModel.feedbackFileSelection(sourceFiles(pending.nodes));
            }
            return true;
        }
        if ((bodyLower.startsWith("file ") || bodyLower.startsWith("source ")) && !bodyLower.contains("=")) {
            String value = bodyLower.startsWith("source ") ? body.substring("source ".length()).trim() : body.substring("file ".length()).trim();
            if (pending.nodes.isEmpty()) {
                startBadFlow(AutomationCliViewModel.snapshot());
            }
            selectSource(value);
            return true;
        }
        Map<String, String> named = parseNamed(body);
        if (bodyLower.startsWith("bad ") && named.containsKey("node") && named.containsKey("failure")) {
            startBadFlow(AutomationCliViewModel.snapshot());
            selectNode(named.get("node"));
            selectFailureType(named.get("failure"));
            return true;
        }
        if ((bodyLower.startsWith("node ") || bodyLower.startsWith("select node ")) && !bodyLower.contains("=")) {
            String id = bodyLower.startsWith("select node ") ? body.substring("select node ".length()).trim() : body.substring("node ".length()).trim();
            if (pending.nodes.isEmpty()) {
                startBadFlow(AutomationCliViewModel.snapshot());
            }
            selectNode(id);
            return true;
        }
        if ((bodyLower.startsWith("type ") || bodyLower.startsWith("failure ") || bodyLower.startsWith("select failure ")) && !bodyLower.contains("=")) {
            String id = body;
            if (bodyLower.startsWith("type ")) {
                id = body.substring("type ".length()).trim();
            } else if (bodyLower.startsWith("failure ")) {
                id = body.substring("failure ".length()).trim();
            } else if (bodyLower.startsWith("select failure ")) {
                id = body.substring("select failure ".length()).trim();
            }
            selectFailureType(id);
            return true;
        }
        if (named.containsKey("file") || named.containsKey("source")) {
            if (pending.nodes.isEmpty()) {
                startBadFlow(AutomationCliViewModel.snapshot());
            }
            selectSource(named.getOrDefault("file", named.get("source")));
            return true;
        }
        if (named.containsKey("node")) {
            if (pending.nodes.isEmpty()) {
                startBadFlow(AutomationCliViewModel.snapshot());
            }
            selectNode(named.get("node"));
            if (named.containsKey("failure")) {
                selectFailureType(named.get("failure"));
            }
            return true;
        }
        if (named.containsKey("failure")) {
            selectFailureType(named.get("failure"));
            return true;
        }
        AutomationCliViewModel.feedbackHelp();
        return true;
    }

    public static synchronized List<AutomationFeedbackNode> executableNodes(AutomationCliSnapshot snapshot) {
        if (snapshot == null || snapshot.rows() == null) {
            return List.of();
        }
        List<AutomationFeedbackNode> nodes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AutomationCliRow row : snapshot.rows()) {
            if (!isExecutableRow(row)) {
                continue;
            }
            AutomationFeedbackNode node = AutomationFeedbackNode.fromRow(snapshot.sessionId(), row);
            String key = node.rowId().isBlank() ? node.nodeId() : node.rowId();
            if (seen.add(key)) {
                nodes.add(node);
            }
        }
        if (nodes.isEmpty()) {
            nodes.add(new AutomationFeedbackNode(snapshot.sessionId(), "feedback:task_root", "task_root", "task root", "ui", "", "task-level feedback", "", ""));
        }
        return nodes;
    }

    public static synchronized void submitGood() {
        pending = PendingFlow.empty();
        AutomationCliViewModel.feedbackGood();
    }

    public static synchronized void startBadFlow(AutomationCliSnapshot snapshot) {
        List<AutomationFeedbackNode> nodes = executableNodes(snapshot);
        pending = new PendingFlow(snapshot == null ? "" : snapshot.sessionId(), nodes, "", null, List.of());
        List<String> sources = sourceFiles(nodes);
        if (sources.size() > 1) {
            AutomationCliViewModel.feedbackFileSelection(sources);
            return;
        }
        if (sources.size() == 1) {
            pending = new PendingFlow(pending.taskId, pending.nodes, sources.get(0), null, List.of());
        }
        AutomationCliViewModel.feedbackNodeSelection(nodesForPending());
    }

    public static synchronized boolean handleConsoleRowClick(String rowId) {
        String normalized = normalize(rowId);
        if (normalized.isBlank()) {
            return false;
        }
        if (normalized.equals("feedback:good") || normalized.equals("feedback.good") || normalized.equals("feedback_button_good")) {
            submitGood();
            return true;
        }
        if (normalized.equals("feedback:bad") || normalized.equals("feedback.bad") || normalized.equals("feedback_button_bad")) {
            startBadFlow(AutomationCliViewModel.snapshot());
            return true;
        }
        if (normalized.startsWith("feedback:file:")) {
            selectSource(rowId.substring("feedback:file:".length()));
            return true;
        }
        if (normalized.startsWith("feedback:node:")) {
            selectNode(rowId.substring("feedback:node:".length()));
            return true;
        }
        if (normalized.startsWith("feedback:type:")) {
            String id = rowId.substring(rowId.lastIndexOf(':') + 1);
            selectFailureType(id);
            return true;
        }
        return false;
    }

    public static synchronized void openNodeFeedback(String rowOrNodeId) {
        if (pending.nodes.isEmpty()) {
            startBadFlow(AutomationCliViewModel.snapshot());
        }
        selectNode(rowOrNodeId);
    }

    public static synchronized void selectSource(String sourceId) {
        if (pending.nodes.isEmpty()) {
            startBadFlow(AutomationCliViewModel.snapshot());
        }
        String source = findSource(sourceId, sourceFiles(pending.nodes));
        if (source == null) {
            AutomationCliViewModel.feedbackError("unknown KTL file: " + safe(sourceId));
            AutomationCliViewModel.feedbackFileSelection(sourceFiles(pending.nodes));
            return;
        }
        pending = new PendingFlow(pending.taskId, pending.nodes, source, null, List.of());
        AutomationCliViewModel.feedbackNodeSelection(nodesForPending());
    }

    public static synchronized void selectNode(String rowOrNodeId) {
        if (pending.nodes.isEmpty()) {
            startBadFlow(AutomationCliViewModel.snapshot());
        }
        AutomationFeedbackNode node = findNode(rowOrNodeId, nodesForPending());
        if (node == null) {
            AutomationCliViewModel.feedbackError("unknown feedback node: " + safe(rowOrNodeId));
            AutomationCliViewModel.feedbackNodeSelection(nodesForPending());
            return;
        }
        List<AutomationFailureType> types = AutomationFailureRegistry.failureTypesFor(node.nodeType());
        pending = new PendingFlow(pending.taskId, pending.nodes, pending.selectedSource, node, types);
        AutomationCliViewModel.feedbackFailureTypeSelection(node, types);
    }

    public static synchronized void selectFailureType(String failureTypeId) {
        if (pending.selectedNode == null) {
            AutomationCliViewModel.feedbackError("select a node before selecting a failure type");
            if (pending.nodes.isEmpty()) {
                startBadFlow(AutomationCliViewModel.snapshot());
            } else {
                AutomationCliViewModel.feedbackNodeSelection(nodesForPending());
            }
            return;
        }
        List<AutomationFailureType> types = pending.failureTypes.isEmpty() ? AutomationFailureRegistry.failureTypesFor(pending.selectedNode.nodeType()) : pending.failureTypes;
        AutomationFailureType type = findFailureType(failureTypeId, types);
        if (type == null) {
            AutomationCliViewModel.feedbackError("unknown failure type: " + safe(failureTypeId));
            AutomationCliViewModel.feedbackFailureTypeSelection(pending.selectedNode, types);
            return;
        }
        submitBad(pending.selectedNode, type);
        pending = PendingFlow.empty();
    }

    public static synchronized List<AutomationFailureType> failureTypesForNode(String rowOrNodeId) {
        if (pending.nodes.isEmpty()) {
            startBadFlow(AutomationCliViewModel.snapshot());
        }
        AutomationFeedbackNode node = findNode(rowOrNodeId, nodesForPending());
        if (node == null) {
            return List.of();
        }
        return AutomationFailureRegistry.failureTypesFor(node.nodeType());
    }

    public static synchronized AutomationFeedbackNode pendingSelectedNode() {
        return pending.selectedNode;
    }

    public static void submitBad(AutomationFeedbackNode node, AutomationFailureType failureType) {
        if (node == null || failureType == null || failureType.id().isBlank()) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schema_version", 1);
        event.put("timestamp", Instant.now().toString());
        event.put("task_id", node.taskId());
        event.put("node_id", node.nodeId());
        event.put("node_row_id", node.rowId());
        event.put("node_label", node.label());
        event.put("node_type", node.nodeType());
        event.put("failure_type", failureType.id());
        event.put("failure_label", failureType.label());
        event.put("inputs", node.inputs());
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("frame_id", node.frameId());
        context.put("source", node.source());
        context.put("value", node.value());
        event.put("execution_context", context);
        event.put("suggested_fix_rules", failureType.suggested_fix_rules());
        Map<String, Object> hooks = new LinkedHashMap<>();
        hooks.put("auto_fix_rule", failureType.auto_fix_rule());
        hooks.put("patch_generator", failureType.patch_generator());
        hooks.put("learning_hook", failureType.learning_hook());
        event.put("future_hooks", hooks);
        try {
            Files.createDirectories(EVENTS.getParent());
            Files.writeString(EVENTS, GSON.toJson(event) + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            AutomationCliViewModel.feedbackRecorded(node.nodeId(), failureType.label());
        } catch (IOException exception) {
            AutomationCliViewModel.feedbackRecorded(node.nodeId(), "failed to store feedback: " + exception.getMessage());
        }
    }

    public static synchronized void cancelPending() {
        pending = PendingFlow.empty();
        AutomationCliViewModel.feedbackCanceled();
    }

    public static Path eventsPath() {
        return EVENTS;
    }

    private static boolean isExecutableRow(AutomationCliRow row) {
        if (row == null || !row.visible()) {
            return false;
        }
        String rowType = row.rowType() == null ? "" : row.rowType();
        return rowType.equals("node")
                || rowType.equals("primitive")
                || rowType.equals("branch")
                || rowType.equals("delegate")
                || rowType.equals("state_result")
                || rowType.equals("blocked")
                || rowType.equals("flow_decision")
                || rowType.equals("world_probe");
    }

    private static AutomationFeedbackNode findNode(String id, List<AutomationFeedbackNode> nodes) {
        String normalized = normalize(id);
        if (normalized.isBlank()) {
            return null;
        }
        for (AutomationFeedbackNode node : nodes) {
            if (node.matches(normalized)) {
                return node;
            }
        }
        String loose = looseNormalize(id);
        for (AutomationFeedbackNode node : nodes) {
            if (normalize(node.rowId()).contains(normalized) || normalize(node.nodeId()).contains(normalized) || normalize(node.label()).contains(normalized)) {
                return node;
            }
            if (!loose.isBlank() && (looseNormalize(node.rowId()).contains(loose) || looseNormalize(node.nodeId()).contains(loose) || looseNormalize(node.label()).contains(loose))) {
                return node;
            }
        }
        return null;
    }

    private static AutomationFailureType findFailureType(String id, List<AutomationFailureType> types) {
        String normalized = normalize(id);
        if (normalized.isBlank()) {
            return null;
        }
        for (AutomationFailureType type : types) {
            if (normalize(type.id()).equals(normalized) || normalize(type.label()).equals(normalized)) {
                return type;
            }
        }
        for (AutomationFailureType type : types) {
            if (normalize(type.id()).contains(normalized) || normalize(type.label()).contains(normalized)) {
                return type;
            }
        }
        return null;
    }

    private static Map<String, String> parseNamed(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String token : body.split("\\s+")) {
            int split = token.indexOf('=');
            if (split <= 0 || split >= token.length() - 1) {
                continue;
            }
            values.put(token.substring(0, split).trim().toLowerCase(Locale.ROOT), token.substring(split + 1).trim());
        }
        return values;
    }

    private static String normalize(String value) {
        return safe(value).trim().toLowerCase(Locale.ROOT);
    }

    private static String looseNormalize(String value) {
        return safe(value).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static List<String> sourceFiles(List<AutomationFeedbackNode> nodes) {
        List<String> files = new ArrayList<>();
        List<String> fallback = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> seenFallback = new LinkedHashSet<>();
        if (nodes == null) {
            return files;
        }
        for (AutomationFeedbackNode node : nodes) {
            String source = sourceKey(node);
            if (source.isBlank()) {
                continue;
            }
            if (source.toLowerCase(Locale.ROOT).contains(".ktl")) {
                if (seen.add(source)) {
                    files.add(source);
                }
            } else if (seenFallback.add(source)) {
                fallback.add(source);
            }
        }
        return files.isEmpty() ? fallback : files;
    }

    private static List<AutomationFeedbackNode> nodesForPending() {
        if (pending.selectedSource == null || pending.selectedSource.isBlank()) {
            return pending.nodes;
        }
        List<AutomationFeedbackNode> filtered = new ArrayList<>();
        for (AutomationFeedbackNode node : pending.nodes) {
            if (sourceKey(node).equals(pending.selectedSource)) {
                filtered.add(node);
            }
        }
        return filtered.isEmpty() ? pending.nodes : filtered;
    }

    private static String findSource(String id, List<String> files) {
        String normalized = normalize(id);
        String loose = looseNormalize(id);
        if (normalized.isBlank()) {
            return null;
        }
        for (int i = 0; i < files.size(); i++) {
            if (normalized.equals(String.valueOf(i + 1))) {
                return files.get(i);
            }
        }
        for (String file : files) {
            if (normalize(file).equals(normalized) || normalize(shortSource(file)).equals(normalized)) {
                return file;
            }
        }
        for (String file : files) {
            if (normalize(file).contains(normalized) || normalize(shortSource(file)).contains(normalized) || (!loose.isBlank() && looseNormalize(file).contains(loose))) {
                return file;
            }
        }
        return null;
    }

    private static String sourceKey(AutomationFeedbackNode node) {
        if (node == null) {
            return "";
        }
        String source = safe(node.source()).trim();
        if (source.isBlank()) {
            source = safe(node.label()).trim();
        }
        int ktl = source.indexOf(".ktl:");
        if (ktl >= 0) {
            source = source.substring(0, ktl + 4);
        }
        if (!source.toLowerCase(Locale.ROOT).contains(".ktl") && !source.equals("task-level feedback") && !source.equals("task root")) {
            return source.isBlank() ? "task root" : source;
        }
        return source.isBlank() ? "task root" : source;
    }

    private static String shortSource(String source) {
        String clean = safe(source);
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        return slash >= 0 && slash < clean.length() - 1 ? clean.substring(slash + 1) : clean;
    }

    private record PendingFlow(String taskId, List<AutomationFeedbackNode> nodes, String selectedSource, AutomationFeedbackNode selectedNode, List<AutomationFailureType> failureTypes) {
        private PendingFlow {
            taskId = safe(taskId);
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            selectedSource = safe(selectedSource);
            failureTypes = failureTypes == null ? List.of() : List.copyOf(failureTypes);
        }

        private static PendingFlow empty() {
            return new PendingFlow("", List.of(), "", null, List.of());
        }
    }
}
