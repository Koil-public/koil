package com.spirit.koil.api.automation.cli;

import com.spirit.client.gui.console.ConsoleScreen;
import com.spirit.koil.api.automation.feedback.AutomationFailureType;
import com.spirit.koil.api.automation.feedback.AutomationFeedbackNode;
import com.spirit.koil.api.console.ConsoleLevel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class AutomationCliViewModel {
    private static final int MAX_ROWS = 900;
    private static final int COMPACT_TO_ROWS = 720;
    private static final long PERSIST_INTERVAL_NANOS = 40_000_000L;
    private static final AtomicInteger SESSION_COUNTER = new AtomicInteger();
    private static final List<AutomationCliRow> ROWS = new ArrayList<>();
    private static final Map<String, Integer> ROW_INDEX = new LinkedHashMap<>();
    private static final Map<String, Integer> FRAME_DEPTHS = new LinkedHashMap<>();
    private static final Map<String, String> FRAME_TEMPLATES = new LinkedHashMap<>();
    private static final Map<String, String> FRAME_PARENTS = new LinkedHashMap<>();
    private static String sessionId = "kts-00000";
    private static String mode = "AUTOMATION";
    private static String actor = "local_player";
    private static String detail = "DEBUG";
    private static int rowCounter;
    private static int frameDepth;
    private static long lastPersistNanos;
    private static long lastCompactChatNanos;
    private static String lastCompactChatLine = "";
    private static String lastCompactChatState = "idle";
    private static String currentRuntimeState = "idle";
    private static String currentPrompt = "";
    private static boolean compactingRows;

    private AutomationCliViewModel() {
    }

    public static synchronized void beginSession(String rawInput) {
        beginSession(rawInput, "");
    }

    public static synchronized void beginSession(String rawInput, String actorOverride) {
        sessionId = String.format("kts-%05d", SESSION_COUNTER.incrementAndGet());
        mode = "AUTOMATION";
        actor = actorOverride == null || actorOverride.isBlank() ? currentActor() : actorOverride;
        detail = "DEBUG";
        ROWS.clear();
        ROW_INDEX.clear();
        FRAME_DEPTHS.clear();
        FRAME_TEMPLATES.clear();
        FRAME_PARENTS.clear();
        rowCounter = 0;
        frameDepth = 0;
        lastPersistNanos = 0L;
        lastCompactChatNanos = 0L;
        lastCompactChatLine = "";
        lastCompactChatState = "idle";
        currentRuntimeState = "idle";
        currentPrompt = rawInput == null ? "" : rawInput;
        compactingRows = false;
        upsert("header:mode", "header", "header", 0, "[info]", "mode", mode, true);
        upsert("header:actor", "header", "header", 0, "[info]", "actor", actor, true);
        upsert("header:session", "header", "header", 0, "[info]", "session", sessionId, true);
        upsert("header:detail", "header", "header", 0, "[info]", "detail", detail, true);
        if (rawInput != null && !rawInput.isBlank()) {
            upsert("header:input", "header", "header", 0, "[info]", "raw_input", rawInput, true);
        }
        publishCompactChatHeader();
        persist();
    }

    public static synchronized void appendFromReporter(ConsoleLevel level, String stage, String message) {
        String marker = toMarker(level, stage);
        ParsedRow parsed = parseRow(stage, message);
        String section = sectionFor(stage, parsed);
        int indent = indentFor(stage);

        if ("[mode]".equals(stage)) {
            upsert("header:mode", "header", "header", 0, marker, "mode", parsed.value(), true);
            persist();
            return;
        }
        if ("[run ]".equals(stage) && "objective".equals(parsed.label())) {
            upsert("header:objective", "header", "header", 0, marker, "objective", parsed.value(), true);
            upsert("objective:active_template", "objective", "objective_state", 0, marker, "active.template", parsed.value(), true);
            persist();
            return;
        }
        if (isPipelineStage(stage)) {
            pipelineGraph(stage, parsed);
            return;
        }
        if ("[mem ]".equals(stage) || "[prog]".equals(stage)) {
            upsert(section + ":" + sanitize(parsed.label()), section, rowTypeForState(section, parsed.label()), indent, marker, parsed.label(), parsed.value(), true);
            persist();
            return;
        }
        if ("[done]".equals(stage) || "[fail]".equals(stage)) {
            upsert("summary:state", "summary", "summary", 0, marker, "state", parsed.value(), true);
            append(section, parsed.rowType(), indent, marker, parsed.label(), parsed.value());
            persist();
            return;
        }
        if ("[cand]".equals(stage)) {
            candidateGraph(parsed);
            persist();
            return;
        }

        append(section, parsed.rowType(), indent, marker, parsed.label(), parsed.value());
        if ("[delegate]".equals(stage)) {
            frameDepth++;
        } else if ("[return]".equals(stage)) {
            frameDepth = Math.max(0, frameDepth - 1);
        }
        persist();
    }

    public static synchronized AutomationCliSnapshot snapshot() {
        return new AutomationCliSnapshot(sessionId, mode, actor, detail, List.copyOf(ROWS));
    }

    public static synchronized void offerFeedbackPrompt() {
        offerFeedbackPrompt("completed root task");
    }

    public static synchronized void offerFeedbackPrompt(String reason) {
        hideRowsByPrefix("feedback:");
        String detailText = reason == null || reason.isBlank() ? "completed root task" : reason;
        upsertDetailed("feedback:label", "feedback", "feedback_prompt", 0, "[info]", "Feedback:", detailText, true,
                "", detailText, "", "optional structured feedback", "", "", "automation feedback");
        upsertDetailed("feedback:good", "feedback", "feedback_button_good", 1, "[ok  ]", "[ \u2713 Good ]", "click = accepted, no event stored", true,
                "", "non-blocking", "", "ignored = no action", "", "", "automation feedback");
        upsertDetailed("feedback:bad", "feedback", "feedback_button_bad", 1, "[fail]", "[ \u2717 Bad ]", "click = select failed node", true,
                "", "non-blocking", "", "begin deterministic failure flow", "", "select node, then failure type", "automation feedback");
        publishFeedbackChatPrompt(detailText);
        persist();
    }

    public static synchronized void feedbackGood() {
        upsertDetailed("feedback:good", "feedback", "feedback_button_good", 1, "[ok  ]", "[ \u2713 Good ]", "accepted", true,
                "", "", "", "no event stored", "", "", "automation feedback");
        hideRow("feedback:bad");
        publishFeedbackChatResult("Good feedback accepted", "No event stored.", "complete");
        persist();
    }

    public static synchronized void feedbackRecorded(String nodeId, String failureType) {
        appendDetailed("feedback", "feedback_result", 1, "[fail]", "feedback.recorded", nodeId + " -> " + failureType,
                "", "selected node + failure type", "", "stored structured event", failureType, "", "automation feedback");
        publishFeedbackChatResult("Bad feedback recorded", nodeId + " -> " + failureType, "failed");
        persist();
    }

    public static synchronized void feedbackHelp() {
        upsertDetailed("feedback:help", "feedback", "feedback_help", 1, "[info]", "feedback.commands", "/feedback good | /feedback bad | /feedback node <id> | /feedback type <id>", true,
                "", "structured command input", "", "deterministic feedback flow", "", "", "automation feedback");
        publishFeedbackChatResult("Feedback commands", "/feedback good | /feedback bad", "header");
        persist();
    }

    public static synchronized void feedbackNodeSelection(List<AutomationFeedbackNode> nodes) {
        hideRowsByPrefix("feedback:node:");
        hideRowsByPrefix("feedback:type:");
        upsertDetailed("feedback:select-node:title", "feedback", "feedback_node_prompt", 0, "[branch]", "Select where it failed:", "use /feedback node <node_id>", true,
                "executed node list", "runtime trace", "", "waiting for selected_node", "", "", "automation feedback");
        if (nodes == null || nodes.isEmpty()) {
            upsertDetailed("feedback:select-node:none", "feedback", "feedback_node_empty", 1, "[block]", "nodes", "no executable nodes in current trace", true,
                    "", "completed runtime trace", "", "no node selection available", "no executed nodes", "run a task first, then choose Bad", "automation feedback");
            publishFeedbackChatResult("Select where it failed", "No executable nodes found.", "blocked");
            persist();
            return;
        }
        int index = 1;
        for (AutomationFeedbackNode node : nodes) {
            String id = node.nodeId().isBlank() ? node.rowId() : node.nodeId();
            String value = "node_id=" + id + "  type=" + node.nodeType() + (node.source().isBlank() ? "" : "  source=" + node.source());
            upsertDetailed("feedback:node:" + sanitize(node.rowId().isBlank() ? id : node.rowId()), "feedback", "feedback_node_option", index, "[info]", node.label().isBlank() ? id : node.label(), value, true,
                    node.label(), "click node or /feedback node " + id, "runtime trace row", "selected_node = " + id, "", "", node.source());
            index++;
        }
        publishFeedbackNodeChatPrompt(nodes);
        persist();
    }

    public static synchronized void feedbackFailureTypeSelection(AutomationFeedbackNode node, List<AutomationFailureType> types) {
        hideRowsByPrefix("feedback:type:");
        String nodeId = node == null ? "" : (node.nodeId().isBlank() ? node.rowId() : node.nodeId());
        String nodeType = node == null ? "ui" : node.nodeType();
        upsertDetailed("feedback:select-type:title", "feedback", "feedback_type_prompt", 0, "[branch]", "Select what went wrong:", "node=" + nodeId + "  type=" + nodeType + "  use /feedback type <failure_id>", true,
                "failure type list", "selected_node = " + nodeId, "", "waiting for failure_type", "", "", "automation feedback");
        if (types == null || types.isEmpty()) {
            upsertDetailed("feedback:type:none", "feedback", "feedback_type_empty", 1, "[block]", "failure_types", "no registered failure types for " + nodeType, true,
                    "", "failure registry", "", "no deterministic failure type", "missing registry entries", "add a JSON entry under koil/automation/failure_types", "automation feedback");
            publishFeedbackChatResult("Select what went wrong", "No failure types for " + nodeType, "blocked");
            persist();
            return;
        }
        int index = 1;
        for (AutomationFailureType type : types) {
            String value = "failure_id=" + type.id() + "  rules=" + String.join(",", type.suggested_fix_rules());
            upsertDetailed("feedback:type:" + sanitize(type.id()), "feedback", "feedback_type_option", index, "[info]", type.label(), value, true,
                    type.id(), "registry filtered by " + nodeType, nodeId, "failure_type = " + type.id(), "", type.auto_fix_rule(), "automation feedback");
            index++;
        }
        publishFeedbackTypeChatPrompt(node, types);
        persist();
    }

    public static synchronized void feedbackError(String message) {
        upsertDetailed("feedback:error", "feedback", "feedback_error", 1, "[fail]", "feedback.error", message == null ? "" : message, true,
                "", "structured feedback state", "", "", message == null ? "" : message, "retry the deterministic feedback command", "automation feedback");
        publishFeedbackChatResult("Feedback error", message == null ? "" : message, "failed");
        persist();
    }

    public static synchronized void feedbackCanceled() {
        upsertDetailed("feedback:cancel", "feedback", "feedback_cancel", 1, "[warn]", "feedback", "canceled", true,
                "", "", "", "no event stored", "", "", "automation feedback");
        hideRowsByPrefix("feedback:node:");
        hideRowsByPrefix("feedback:type:");
        publishFeedbackChatResult("Feedback canceled", "No event stored.", "idle");
        persist();
    }

    public static synchronized void feedbackFileSelection(List<String> files) {
        hideRowsByPrefix("feedback:file:");
        hideRowsByPrefix("feedback:node:");
        hideRowsByPrefix("feedback:type:");
        upsertDetailed("feedback:select-file:title", "feedback", "feedback_file_prompt", 0, "[branch]", "Select the KTL file:", "use /feedback file <file>", true,
                "executed ktl file list", "runtime trace sources", "", "waiting for selected_file", "", "", "automation feedback");
        if (files == null || files.isEmpty()) {
            upsertDetailed("feedback:file:none", "feedback", "feedback_file_empty", 1, "[block]", "files", "no KTL source files in current trace", true,
                    "", "completed runtime trace", "", "no file selection available", "no executed ktl files", "run a task first, then choose Bad", "automation feedback");
            publishFeedbackChatResult("No KTL files found", "Run a task first, then choose Bad.", "blocked");
            persist();
            return;
        }
        int index = 1;
        for (String file : files) {
            String clean = file == null || file.isBlank() ? "task root" : file;
            upsertDetailed("feedback:file:" + sanitize(clean), "feedback", "feedback_file_option", index, "[info]", index + ". " + shortSource(clean), clean, true,
                    clean, "click file or /feedback file " + clean, "runtime trace source", "selected_file = " + clean, "", "", clean);
            index++;
        }
        publishFeedbackFileChatPrompt(files);
        persist();
    }

    private static void publishFeedbackChatPrompt(String reason) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        String detailText = reason == null || reason.isBlank() ? "task finished" : reason;
        MutableText header = automationChatHeader();
        MutableText prompt = promptLine("Feedback: " + detailText + ". How did it do?");
        MutableText active = Text.literal("click a button, or type /feedback good or /feedback bad").formatted(Formatting.DARK_GRAY);
        List<AutomationChatHudState.Action> actions = List.of(
                new AutomationChatHudState.Action("feedback.good", "[ ✓ Good ]", "/feedback good", "", "good"),
                new AutomationChatHudState.Action("feedback.bad", "[ ✗ Bad ]", "/feedback bad", "", "bad")
        );
        AutomationChatHudState.showHeader(header, prompt, active, "feedback_prompt", actions);
    }

    private static void publishFeedbackFileChatPrompt(List<String> files) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        List<AutomationChatHudState.Action> actions = new ArrayList<>();
        String first = "";
        if (files != null) {
            int limit = Math.min(files.size(), 8);
            for (int i = 0; i < limit; i++) {
                String file = files.get(i) == null || files.get(i).isBlank() ? "task root" : files.get(i);
                if (first.isBlank()) {
                    first = file;
                }
                actions.add(new AutomationChatHudState.Action("feedback.file." + i, trimButton((i + 1) + ". " + shortSource(file)), "/feedback file " + file, file, "file"));
            }
        }
        MutableText header = automationChatHeader();
        MutableText prompt = promptLine(files == null ? "Select the KTL file: 0 files" : "Select the KTL file: " + files.size() + " files ran");
        MutableText active = Text.literal(first.isBlank() ? "type /feedback file <file>" : "pick one file; for two files, submit this one then choose Bad again").formatted(Formatting.DARK_GRAY);
        AutomationChatHudState.showHeader(header, prompt, active, "feedback_file_select", actions);
    }

    private static void publishFeedbackNodeChatPrompt(List<AutomationFeedbackNode> nodes) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        List<AutomationChatHudState.Action> actions = new ArrayList<>();
        String first = "";
        if (nodes != null) {
            int limit = Math.min(nodes.size(), 8);
            for (int i = 0; i < limit; i++) {
                AutomationFeedbackNode node = nodes.get(i);
                String id = node.nodeId().isBlank() ? node.rowId() : node.nodeId();
                if (first.isBlank()) {
                    first = id;
                }
                String label = node.label().isBlank() ? id : node.label();
                actions.add(new AutomationChatHudState.Action("feedback.node." + i, trimButton(label), "/feedback node " + id, id, "node"));
            }
        }
        MutableText header = automationChatHeader();
        MutableText prompt = promptLine(nodes == null ? "Select where it failed: 0 nodes" : "Select where it failed: " + nodes.size() + " nodes");
        MutableText active = Text.literal(first.isBlank() ? "type /feedback node <node_id>" : "click a node button, or type /feedback node " + first).formatted(Formatting.DARK_GRAY);
        AutomationChatHudState.showHeader(header, prompt, active, "feedback_node_select", actions);
    }

    private static void publishFeedbackTypeChatPrompt(AutomationFeedbackNode node, List<AutomationFailureType> types) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        String nodeId = node == null ? "" : (node.nodeId().isBlank() ? node.rowId() : node.nodeId());
        String first = "";
        List<AutomationChatHudState.Action> actions = new ArrayList<>();
        if (types != null) {
            int limit = Math.min(types.size(), 8);
            for (int i = 0; i < limit; i++) {
                AutomationFailureType type = types.get(i);
                if (first.isBlank()) {
                    first = type.id();
                }
                actions.add(new AutomationChatHudState.Action("feedback.type." + i, trimButton(type.label()), "/feedback type " + type.id(), type.id(), "failure"));
            }
        }
        MutableText header = automationChatHeader();
        MutableText prompt = promptLine(nodeId.isBlank() ? "Select what went wrong" : "Select what went wrong: " + nodeId);
        MutableText active = Text.literal(first.isBlank() ? "type /feedback type <failure_id>" : "click a failure button, or type /feedback type " + first).formatted(Formatting.DARK_GRAY);
        AutomationChatHudState.showHeader(header, prompt, active, "feedback_type_select", actions);
    }

    private static void publishFeedbackChatResult(String headerText, String detailText, String state) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        MutableText header = automationChatHeader();
        String title = headerText == null || headerText.isBlank() ? "Feedback" : headerText;
        String detail = detailText == null || detailText.isBlank() ? "" : " - " + detailText;
        MutableText prompt = promptLine(title + detail);
        List<AutomationChatHudState.Action> actions = title.equals("Bad feedback recorded") ? List.of(
                new AutomationChatHudState.Action("feedback.another", "[ + Another File ]", "/feedback bad", "", "file")
        ) : List.of();
        AutomationChatHudState.showHeader(header, prompt, Text.empty(), state == null || state.isBlank() ? "header" : state, actions);
    }

    public static MutableText automationChatHeader() {
        int visibleRows = 0;
        int executableNodes = 0;
        int failures = 0;
        for (AutomationCliRow row : ROWS) {
            if (!row.visible()) {
                continue;
            }
            visibleRows++;
            String rowType = row.rowType() == null ? "" : row.rowType();
            if (rowType.equals("node") || rowType.equals("primitive") || rowType.equals("branch") || rowType.equals("delegate") || rowType.equals("state_result") || rowType.equals("blocked")) {
                executableNodes++;
            }
            String marker = row.statusMarker() == null ? "" : row.statusMarker();
            if (marker.equals("[fail]") || rowType.contains("failure") || rowType.equals("blocked")) {
                failures++;
            }
        }
        return Text.literal("Automation").formatted(Formatting.GRAY).append(Text.literal(" | ").formatted(Formatting.DARK_GRAY).append(Text.literal("session ").formatted(Formatting.GRAY).append(Text.literal(sessionId).formatted(Formatting.WHITE).append(Text.literal(" | ").formatted(Formatting.DARK_GRAY).append(Text.literal(String.valueOf(executableNodes)).formatted(Formatting.WHITE).append(Text.literal(" | ").formatted(Formatting.DARK_GRAY).append(Text.literal(String.valueOf(visibleRows)).formatted(Formatting.WHITE).append(Text.literal(" | ").formatted(Formatting.DARK_GRAY).append(Text.literal(String.valueOf(failures)).formatted(Formatting.WHITE))))))))));
    }

    public static MutableText promptLine(String text) {
        return Text.literal(">_: ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(text == null || text.isBlank() ? "type an automation prompt" : text).formatted(Formatting.WHITE));
    }

    private static String shortSource(String source) {
        String clean = source == null || source.isBlank() ? "task root" : source;
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        return slash >= 0 && slash < clean.length() - 1 ? clean.substring(slash + 1) : clean;
    }

    public static synchronized void improvementSummary(int eventCount, int groupCount, String planFile, String patchQueueFile, String renderFile) {
        upsertDetailed("improve:summary", "improvement", "improvement_summary", 0, "[done]", "self_improvement.summary", "events=" + eventCount + "  groups=" + groupCount, true,
                "feedback events", "grouped deterministic failures", "", "plan=" + planFile, "", "review generated candidates before applying", renderFile);
        upsertDetailed("improve:files", "improvement", "improvement_files", 1, "[info]", "self_improvement.files", "plan=" + planFile + "  patches=" + patchQueueFile, true,
                "", "generated artifacts", "", renderFile, "", "", patchQueueFile);
        persist();
    }

    public static synchronized void improvementCandidate(String id, String source, String nodeType, String failureType, int count, String status, String patchFile) {
        String safeId = sanitize(id == null || id.isBlank() ? source + ":" + nodeType + ":" + failureType : id);
        String cleanSource = source == null || source.isBlank() ? "unknown.ktl" : source;
        upsertDetailed("improve:file:" + safeId, "improvement", "improvement_file", 1, "[info]", shortSource(cleanSource), "failures=" + count + "  type=" + nodeType + "  failure=" + failureType, true,
                cleanSource, "feedback grouped by KTL file", "events=" + count, "candidate_patch=" + patchFile, failureType, "review deterministic patch before apply", patchFile);
        persist();
    }

    public static synchronized void beginFrame(String frameId, String parentFrameId, String templateId, String objective) {
        int depth = parentFrameId == null || parentFrameId.isBlank() ? 0 : FRAME_DEPTHS.getOrDefault(parentFrameId, 0) + 1;
        FRAME_DEPTHS.put(frameId, depth);
        FRAME_TEMPLATES.put(frameId, templateId == null ? "" : templateId);
        FRAME_PARENTS.put(frameId, parentFrameId == null ? "" : parentFrameId);
        upsert("frame:" + frameId, "execution", "frame", depth, "[run ]", "frame.id", frameId + "  template = " + templateId, true);
        if (objective != null && !objective.isBlank()) {
            upsert("frame-objective:" + frameId, "execution", "frame_meta", depth + 1, "[info]", "objective", objective, true);
        }
        persist();
    }

    public static synchronized void frameContext(String frameId, String parentFrameId, String resumeLabel, String semanticOperationId, Map<String, Object> boundParams, Map<String, Object> diagnostics) {
        int depth = FRAME_DEPTHS.getOrDefault(frameId, 0) + 1;
        if (parentFrameId != null && !parentFrameId.isBlank()) {
            upsert("frame-parent:" + frameId, "execution", "frame_link", depth, "[info]", "parent.frame", parentFrameId, true);
        }
        if (resumeLabel != null && !resumeLabel.isBlank()) {
            upsert("frame-resume:" + frameId, "execution", "frame_link", depth, "[info]", "resume.label", resumeLabel, true);
        }
        if (semanticOperationId != null && !semanticOperationId.isBlank()) {
            upsert("frame-semantic:" + frameId, "execution", "frame_meta", depth, "[info]", "semantic_operation.id", semanticOperationId, true);
        }
        if (diagnostics != null && !diagnostics.isEmpty()) {
            for (Map.Entry<String, Object> entry : diagnostics.entrySet()) {
                upsert("frame-diagnostic:" + frameId + ":" + sanitize(entry.getKey()), "execution", "frame_diag", depth + 1, "[info]", "diag." + entry.getKey(), String.valueOf(entry.getValue()), true);
            }
        }
        if (boundParams != null && !boundParams.isEmpty()) {
            for (Map.Entry<String, Object> entry : new java.util.TreeMap<>(boundParams).entrySet()) {
                upsert("frame-param:" + frameId + ":" + sanitize(entry.getKey()), "execution", "frame_param", depth + 1, "[bind]", entry.getKey(), String.valueOf(entry.getValue()), true);
            }
        }
        persist();
    }

    public static synchronized void enterNode(String frameId, String nodeId, String label, String detailValue) {
        int depth = FRAME_DEPTHS.getOrDefault(frameId, 0) + 1;
        String nodeLabel = label == null || label.isBlank() ? nodeId : label;
        String detailText = detailValue == null ? "" : detailValue;
        upsertDetailed("node:" + frameId + ":" + sanitize(nodeId), "execution", "node", depth, "[run ]", "node " + nodeLabel, "node.id = " + nodeId + (detailText.isBlank() ? "" : "  " + detailText), true,
                "", compactSignal("node", detailText), "", compactSignal("node.id", nodeId), "", "", sourceFromDetail(detailText));
        persist();
    }

    public static synchronized void primitiveCall(String frameId, String nodeId, String action, Map<String, Object> params) {
        int depth = FRAME_DEPTHS.getOrDefault(frameId, 0) + 2;
        String sanitizedNodeId = sanitize(nodeId == null ? "primitive" : nodeId);
        String renderedParams = renderMap(params);
        upsertDetailed("primitive:" + frameId + ":" + sanitizedNodeId, "execution", "primitive", depth, "[run ]", nodeId == null || nodeId.isBlank() ? "primitive" : "primitive " + nodeId, action, true,
                "", renderedParams, "", "pending " + action, "", "", action);
        if (params == null || params.isEmpty()) {
            upsertDetailed("primitive-param:" + frameId + ":" + sanitizedNodeId + ":none", "execution", "primitive_param", depth + 1, "[info]", "params", "(none)", true,
                    "", "(none)", "", "", "", "", action);
        } else {
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                upsertDetailed("primitive-param:" + frameId + ":" + sanitizedNodeId + ":" + sanitize(entry.getKey()), "execution", "primitive_param", depth + 1, "[bind]", entry.getKey(), String.valueOf(entry.getValue()), true,
                        "", entry.getKey() + " = " + String.valueOf(entry.getValue()), "", "", "", "", action);
            }
        }
        persist();
    }

    public static synchronized void primitiveStateWrite(String frameId, String nodeId, String key, Object previousValue, Object value) {
        int depth = FRAME_DEPTHS.getOrDefault(frameId, 0) + 2;
        String label = nodeId == null || nodeId.isBlank() ? key : nodeId + " :: " + key;
        String rendered = previousValue == null ? String.valueOf(value) : String.valueOf(previousValue) + " -> " + String.valueOf(value);
        upsertDetailed("state-write:" + frameId + ":" + sanitize(nodeId == null ? "primitive" : nodeId) + ":" + sanitize(key), "execution", "state_write", depth + 1, "[mem ]", label, rendered, true,
                "", "", String.valueOf(previousValue), key + " = " + String.valueOf(value), "", "", "runtime state");
        persist();
    }

    public static synchronized void primitiveStateRemove(String frameId, String nodeId, String key, Object previousValue) {
        int depth = FRAME_DEPTHS.getOrDefault(frameId, 0) + 2;
        String label = nodeId == null || nodeId.isBlank() ? key : nodeId + " :: " + key;
        upsertDetailed("state-remove:" + frameId + ":" + sanitize(nodeId == null ? "primitive" : nodeId) + ":" + sanitize(key), "execution", "state_remove", depth + 1, "[warn]", label, previousValue == null ? "(removed)" : String.valueOf(previousValue) + " -> (removed)", true,
                "", "", String.valueOf(previousValue), key + " removed", "", "", "runtime state");
        persist();
    }

    public static synchronized void primitiveResult(String frameId, String nodeId, String action, boolean success, String resultValue) {
        int depth = FRAME_DEPTHS.getOrDefault(frameId, 0) + 2;
        String label = nodeId == null || nodeId.isBlank() ? "result" : "result " + nodeId;
        String value = action + " => " + (success ? "SUCCESS" : "FAIL");
        if (resultValue != null && !resultValue.isBlank()) {
            value += "  result.action = " + resultValue;
        }
        upsertDetailed("state-result:" + frameId + ":" + sanitize(nodeId == null ? "primitive" : nodeId), "execution", "state_result", depth, success ? "[done]" : "[fail]", label, value, true,
                "", action, "", value, success ? "" : value, success ? "" : recoveryForFailure(value + " " + action), action);
        persist();
    }

    public static synchronized void branch(String frameId, String nodeId, String expression, String selectedPath) {
        int depth = FRAME_DEPTHS.getOrDefault(frameId, 0) + 1;
        upsertDetailed("branch:" + frameId + ":" + sanitize(nodeId == null ? "branch" : nodeId), "execution", "branch", depth, "[branch]", nodeId == null || nodeId.isBlank() ? "branch.path" : "branch " + nodeId, expression + " -> " + selectedPath, true,
                "", "condition = " + expression, "", "selected path = " + selectedPath, "", "", "evaluator");
        persist();
    }

    public static synchronized void branchCandidates(String frameId, String nodeId, String thenInput, String elseInput, String selectedPath) {
        int depth = FRAME_DEPTHS.getOrDefault(frameId, 0) + 2;
        String safeNode = sanitize(nodeId == null ? "branch" : nodeId);
        upsertDetailed("branch-candidates:" + frameId + ":" + safeNode, "execution", "candidate_cluster", depth, "[branch]", "branch.candidates", "selected = " + selectedPath, true,
                "then/else candidates", "selected path", "", selectedPath, "", "", "grammar branch");
        upsertDetailed("branch-then:" + frameId + ":" + safeNode, "execution", "branch_candidate", depth + 1, "THEN".equals(selectedPath) ? "[ok  ]" : "[info]", "then", thenInput == null || thenInput.isBlank() ? "(continue)" : thenInput, true,
                "", "", "", "THEN".equals(selectedPath) ? "selected" : "", "", "", "grammar branch");
        upsertDetailed("branch-else:" + frameId + ":" + safeNode, "execution", "branch_candidate", depth + 1, "ELSE".equals(selectedPath) ? "[ok  ]" : "[info]", "else", elseInput == null || elseInput.isBlank() ? "(continue)" : elseInput, true,
                "", "", "", "ELSE".equals(selectedPath) ? "selected" : "", "", "", "grammar branch");
        persist();
    }

    public static synchronized void delegate(String frameId, String nodeId, String childFrameId, String delegatedInput) {
        int depth = FRAME_DEPTHS.getOrDefault(frameId, 0) + 1;
        String rowKey = sanitize(nodeId == null ? "delegate" : nodeId);
        upsertDetailed("delegate:" + frameId + ":" + rowKey, "execution", "delegate", depth, "[delegate]", nodeId == null || nodeId.isBlank() ? "delegate" : "delegate " + nodeId, "child = " + childFrameId + "  input = " + delegatedInput, true,
                "", "delegated input = " + delegatedInput, "parent frame = " + frameId, "child frame = " + childFrameId, "", "", "task stack");
        upsertDetailed("pause:" + frameId + ":" + childFrameId, "execution", "frame_pause", depth + 1, "[wait]", "frame.pause", frameId + " -> waiting for " + childFrameId, true,
                "", "child frame result", frameId, "paused until " + childFrameId + " returns", "", "", "task stack");
        persist();
    }

    public static synchronized void returned(String childFrameId, String parentFrameId, String state, String resumeLabel) {
        int depth = FRAME_DEPTHS.getOrDefault(parentFrameId, 0) + 1;
        String templateId = FRAME_TEMPLATES.getOrDefault(childFrameId, "");
        String marker = "success".equalsIgnoreCase(state) ? "[ok  ]" : ("blocked".equalsIgnoreCase(state) ? "[block]" : "[fail]");
        upsertDetailed("frame-summary:" + childFrameId, "execution", "frame_summary", depth, marker, "child.frame", childFrameId + (templateId.isBlank() ? "" : "  template = " + templateId) + "  state = " + state, true,
                "", "", "child frame = " + childFrameId, "state = " + state, "", "", "task stack");
        upsertDetailed("return:" + parentFrameId + ":" + childFrameId, "execution", "return", depth, "[return]", "return_to", (resumeLabel == null || resumeLabel.isBlank() ? parentFrameId : resumeLabel) + " <= " + childFrameId, true,
                "", "resume parent frame", "child frame = " + childFrameId, "return state = " + state, "", "", "task stack");
        upsertDetailed("resume:" + parentFrameId + ":" + childFrameId, "execution", "frame_resume", depth + 1, "[ok  ]", "frame.resume", parentFrameId + " <- resumed after " + childFrameId, true,
                "", "", "return state = " + state, "parent active = " + parentFrameId, "", "", "task stack");
        if ("success".equalsIgnoreCase(state)) {
            compactSuccessfulFrame(childFrameId);
        }
        persist();
    }

    public static synchronized void blocked(String frameId, String nodeId, String reason) {
        int depth = FRAME_DEPTHS.getOrDefault(frameId, 0) + 1;
        upsertDetailed("blocked:" + frameId + ":" + sanitize(nodeId == null ? "blocked" : nodeId), "execution", "blocked", depth, "[block]", nodeId == null || nodeId.isBlank() ? "blocked" : "blocked " + nodeId, reason, true,
                "", "", "", "", reason, recoveryForFailure(reason), "runtime block");
        persist();
    }

    public static synchronized void activeState(String state, String frameId, String detailValue) {
        String normalized = state == null || state.isBlank() ? "idle" : state;
        if ("idle".equals(normalized) && isTerminalRuntimeState(currentRuntimeState)) {
            return;
        }
        currentRuntimeState = normalized;
        String detailText = detailValue == null ? "" : detailValue;
        String value = "state = " + normalized;
        if (frameId != null && !frameId.isBlank()) {
            value += "  frame = " + frameId;
        }
        if (!detailText.isBlank()) {
            value += "  detail = " + detailText;
        }
        String marker = switch (normalized) {
            case "failed" -> "[fail]";
            case "blocked" -> "[block]";
            case "complete" -> "[done]";
            case "idle" -> "[info]";
            case "waiting" -> "[wait]";
            default -> "[run ]";
        };
        upsert("runtime:active_state", "summary", "active_state", 0, marker, "runtime.active_state", value, true);
        publishCompactChatStatus(normalized, detailText);
        persist();
    }

    private static boolean isTerminalRuntimeState(String state) {
        return "complete".equals(state) || "blocked".equals(state) || "failed".equals(state);
    }

    public static synchronized void completeFrame(String frameId, String state) {
        int depth = FRAME_DEPTHS.getOrDefault(frameId, 0);
        String marker = "success".equalsIgnoreCase(state) ? "[done]" : ("blocked".equalsIgnoreCase(state) ? "[block]" : "[fail]");
        upsert("frame-state:" + frameId, "summary", "frame_state", depth, marker, "frame.state", frameId + " = " + state, true);
        activeState("success".equalsIgnoreCase(state) ? "complete" : ("blocked".equalsIgnoreCase(state) ? "blocked" : "failed"), frameId, state);
        FRAME_DEPTHS.remove(frameId);
        FRAME_PARENTS.remove(frameId);
        persist();
    }

    public static synchronized void frameResult(String frameId, String key, Object value) {
        int depth = FRAME_DEPTHS.getOrDefault(frameId, 0) + 1;
        upsertDetailed("frame-result:" + frameId + ":" + sanitize(key), "summary", "frame_result", depth, "[info]", key, String.valueOf(value), true,
                "", "", "", key + " = " + String.valueOf(value), "", "", "frame return");
        persist();
    }

    public static synchronized void frameStateSnapshot(String frameId, Map<String, Object> state, String prefix) {
        if (state == null || state.isEmpty()) {
            return;
        }
        int depth = FRAME_DEPTHS.getOrDefault(frameId, 0) + 1;
        String snapshotPrefix = prefix == null || prefix.isBlank() ? "state" : prefix;
        for (Map.Entry<String, Object> entry : new java.util.TreeMap<>(state).entrySet()) {
            String key = entry.getKey();
            String section = sectionForState(key);
            String rowType = "frame_state_snapshot";
            if ("world".equals(section)) {
                rowType = "world_snapshot";
            } else if ("summary".equals(section)) {
                rowType = "summary_snapshot";
            }
            upsert("frame-snapshot:" + frameId + ":" + sanitize(snapshotPrefix + "." + key), section, rowType, depth, "[info]", snapshotPrefix + "." + key, String.valueOf(entry.getValue()), true);
        }
        persist();
    }

    public static synchronized void runtimeFlow(String frameId, String nodeId, String flowId, String rowType, String marker, String label, Object value) {
        int depth = FRAME_DEPTHS.getOrDefault(frameId, 0) + 3;
        String safeNode = sanitize(nodeId == null || nodeId.isBlank() ? "runtime" : nodeId);
        String safeFlow = sanitize(flowId == null || flowId.isBlank() ? label : flowId);
        String rendered = String.valueOf(value);
        boolean problem = "[fail]".equals(marker) || "[block]".equals(marker);
        boolean search = label != null && (label.contains("scan") || label.contains("ray") || label.contains("target") || label.contains("search"));
        upsertDetailed("flow:" + frameId + ":" + safeNode + ":" + safeFlow, "execution", rowType, depth, marker, label, rendered, true,
                search ? label + " = " + rendered : "", label + " input", "", label + " = " + rendered, problem ? rendered : "", problem ? recoveryForFailure(rendered) : "", "runtime flow");
        persist();
    }

    private static void publishCompactChatStatus(String state, String detailValue) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        String normalizedState = state == null || state.isBlank() ? "idle" : state;
        boolean terminal = normalizedState.equals("complete") || normalizedState.equals("blocked") || normalizedState.equals("failed");
        boolean idle = normalizedState.equals("idle");
        if (idle && lastCompactChatState.equals("idle")) {
            return;
        }
        String detailText = detailValue == null ? "" : detailValue.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (detailText.length() > 36) {
            detailText = detailText.substring(0, 33) + "...";
        }
        AutomationPresenceState.updateLocal(normalizedState, detailText);
        if (client.currentScreen instanceof ConsoleScreen) {
            return;
        }
        String line = "Koil: " + normalizedState + (detailText.isBlank() ? "" : " - " + detailText);
        long now = System.nanoTime();
        boolean changed = !line.equals(lastCompactChatLine);
        boolean expired = now - lastCompactChatNanos >= 900_000_000L;
        if (!terminal && !idle && !changed && !expired && AutomationChatHudState.visible()) {
            return;
        }
        if (!terminal && changed && now - lastCompactChatNanos < 350_000_000L) {
            return;
        }
        if (terminal && line.equals(lastCompactChatLine) && lastCompactChatState.equals(normalizedState)) {
            return;
        }

        String prompt = currentPrompt == null || currentPrompt.isBlank() ? "(none)" : currentPrompt;
        AutomationChatHudState.showHeader(automationChatHeader(), promptLine(prompt), compactStatusText(normalizedState, detailText), normalizedState);
        lastCompactChatLine = line;
        lastCompactChatState = normalizedState;
        lastCompactChatNanos = now;
    }

    private static void publishCompactChatHeader() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        String prompt = currentPrompt == null || currentPrompt.isBlank() ? "(none)" : currentPrompt;
        if (prompt.length() > 96) {
            prompt = prompt.substring(0, 93) + "...";
        }
        AutomationPresenceState.updateLocal("header", prompt);
        if (client.currentScreen instanceof ConsoleScreen) {
            return;
        }
        MutableText header = automationChatHeader();
        MutableText promptLine = promptLine(prompt.equals("/automate chat") ? "type an automation prompt" : prompt);
        AutomationChatHudState.showHeader(header, promptLine);
    }

    private static MutableText compactStatusText(String state, String detailText) {
        MutableText text = Text.literal("|--- ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(state).styled(style -> style.withColor(AutomationStateColors.color(state))));
        if (detailText != null && !detailText.isBlank()) {
            text.append(Text.literal(" : ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(detailText).formatted(Formatting.GRAY));
        }
        return text;
    }

    public static synchronized void plannerGraph(String stepId, String rowType, String marker, String label, Object value) {
        String rendered = String.valueOf(value);
        boolean problem = "[fail]".equals(marker) || "[block]".equals(marker) || rendered.toLowerCase().contains("fail");
        upsertDetailed("planner:" + sanitize(stepId), "pipeline", rowType, 0, marker, label, rendered, true,
                label + " = " + rendered, plannerRequires(rowType, label), "", label + " = " + rendered, problem ? rendered : "", problem ? recoveryForFailure(rendered) : "", "planner");
        persist();
    }

    public static synchronized void worldProbe(String frameId, String probeId, String rowType, String marker, String label, Object value) {
        int depth = FRAME_DEPTHS.getOrDefault(frameId, 0) + 1;
        String rendered = String.valueOf(value);
        boolean problem = "[fail]".equals(marker) || "[block]".equals(marker) || "[warn]".equals(marker);
        upsertDetailed("world-probe:" + frameId + ":" + sanitize(probeId), "world", rowType, depth, marker, label, rendered, true,
                label + " = " + rendered, "world/client state", "", label + " = " + rendered, problem ? rendered : "", problem ? recoveryForFailure(label + " " + rendered) : "", "world probe");
        persist();
    }

    private static void append(String section, String rowType, int indent, String marker, String label, String value) {
        appendDetailed(section, rowType, indent, marker, label, value, "", "", "", "", "", "", "");
    }

    private static void appendDetailed(String section, String rowType, int indent, String marker, String label, String value, String search, String requires, String received, String output, String failure, String recovery, String source) {
        ROWS.add(new AutomationCliRow(
                sessionId + ":" + (++rowCounter),
                section,
                rowType,
                indent,
                marker,
                label,
                value == null ? "" : value,
                true,
                true,
                clean(search),
                clean(requires),
                clean(received),
                clean(output),
                clean(failure),
                clean(recovery),
                clean(source)
        ));
        enforceRowBudget();
    }

    private static void upsert(String rowId, String section, String rowType, int indent, String marker, String label, String value, boolean visible) {
        upsertDetailed(rowId, section, rowType, indent, marker, label, value, visible, "", "", "", "", "", "", "");
    }

    private static void upsertDetailed(String rowId, String section, String rowType, int indent, String marker, String label, String value, boolean visible, String search, String requires, String received, String output, String failure, String recovery, String source) {
        AutomationCliRow row = new AutomationCliRow(rowId, section, rowType, indent, marker, label, value == null ? "" : value, visible, true, clean(search), clean(requires), clean(received), clean(output), clean(failure), clean(recovery), clean(source));
        Integer index = ROW_INDEX.get(rowId);
        if (index == null) {
            ROW_INDEX.put(rowId, ROWS.size());
            ROWS.add(row);
            if (!compactingRows) {
                enforceRowBudget();
            }
            return;
        }
        ROWS.set(index, row);
    }

    private static boolean isPipelineStage(String stage) {
        return "[raw ]".equals(stage)
                || "[tok ]".equals(stage)
                || "[lex ]".equals(stage)
                || "[sel ]".equals(stage)
                || "[gram]".equals(stage)
                || "[cond]".equals(stage)
                || "[res ]".equals(stage)
                || "[ref ]".equals(stage)
                || "[pick]".equals(stage)
                || "[bind]".equals(stage)
                || "[cache]".equals(stage);
    }

    private static void pipelineGraph(String stage, ParsedRow parsed) {
        String cluster = switch (stage) {
            case "[raw ]", "[tok ]", "[lex ]", "[sel ]", "[gram]", "[cond]" -> "parse";
            case "[res ]", "[ref ]" -> "resolve";
            case "[pick]" -> "template";
            case "[bind]" -> "bind";
            case "[cache]" -> "cache";
            default -> "pipeline";
        };
        String clusterLabel = switch (cluster) {
            case "parse" -> "parse.cluster";
            case "resolve" -> "resolve.cluster";
            case "template" -> "template.cluster";
            case "bind" -> "bind.cluster";
            case "cache" -> "compile.cluster";
            default -> "pipeline.cluster";
        };
        upsertDetailed("pipeline-cluster:" + cluster, "pipeline", "graph_cluster", 0, "[run ]", clusterLabel, cluster, true,
                cluster + " pipeline", "", "", cluster, "", "", "compiler");
        String rowType = rowTypeForPipeline(stage);
        String search = switch (rowType) {
            case "planner_lexicon", "planner_selector", "planner_resolve", "planner_template" -> parsed.label() + " = " + parsed.value();
            default -> "";
        };
        String requires = switch (rowType) {
            case "planner_grammar" -> "tokens + lexicon matches";
            case "planner_bind" -> "template params + resolved target";
            case "planner_cache" -> "source hash + dependencies";
            default -> "";
        };
        String output = switch (rowType) {
            case "planner_input", "planner_tokenize", "planner_grammar", "planner_resolve", "planner_template", "planner_bind", "planner_cache" -> parsed.label() + " = " + parsed.value();
            default -> "";
        };
        upsertDetailed("pipeline-edge:" + cluster + ":" + sanitize(parsed.label()), "pipeline", rowType, 1, toMarker(ConsoleLevel.INFO, stage), parsed.label(), parsed.value(), true,
                search, requires, "", output, "", "", "compiler");
        persist();
    }

    private static void candidateGraph(ParsedRow parsed) {
        upsertDetailed("candidate-cluster", "pipeline", "candidate_cluster", 0, "[branch]", "candidate.cluster", "resolver alternatives", true,
                "candidate set", "resolver context", "", "ranked alternatives", "", "", "resolver");
        appendDetailed("pipeline", "candidate", 1, "[cand]", parsed.label(), parsed.value(),
                parsed.label() + " = " + parsed.value(), "disambiguation evidence", "", parsed.value(), "", "", "resolver");
    }

    private static String rowTypeForPipeline(String stage) {
        return switch (stage) {
            case "[raw ]" -> "planner_input";
            case "[tok ]" -> "planner_tokenize";
            case "[lex ]" -> "planner_lexicon";
            case "[sel ]" -> "planner_selector";
            case "[gram]" -> "planner_grammar";
            case "[cond]" -> "planner_condition";
            case "[res ]", "[ref ]" -> "planner_resolve";
            case "[pick]" -> "planner_template";
            case "[bind]" -> "planner_bind";
            case "[cache]" -> "planner_cache";
            default -> "planner_event";
        };
    }

    private static String trimButton(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() <= 26) {
            return text;
        }
        return text.substring(0, 23) + "...";
    }

    private static String currentActor() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            return client.player.getName().getString();
        }
        return "local_player";
    }

    private static String toMarker(ConsoleLevel level, String stage) {
        if (stage != null && !stage.isBlank() && stage.startsWith("[")) {
            return stage;
        }
        return switch (level) {
            case ERROR, FATAL -> "[fail]";
            case WARN -> "[warn]";
            case UPDATE -> "[ok  ]";
            case DEBUG -> "[dev ]";
            default -> "[info]";
        };
    }

    private static String sectionFor(String stage, ParsedRow parsed) {
        String value = stage == null ? "" : stage;
        if (value.equals("[mode]")) {
            return "header";
        }
        if (value.equals("[mem ]")) {
            return sectionForState(parsed.label());
        }
        if (value.equals("[done]") || value.equals("[fail]")) {
            return "summary";
        }
        if (value.equals("[run ]") || value.equals("[delegate]") || value.equals("[return]") || value.equals("[wait]") || value.equals("[prog]") || value.equals("[branch]")) {
            if ("[prog]".equals(value)) {
                return sectionForState(parsed.label());
            }
            return "execution";
        }
        if (parsed.label() != null && (parsed.label().startsWith("final.progress") || parsed.label().startsWith("result."))) {
            return "summary";
        }
        return "pipeline";
    }

    private static String sectionForState(String label) {
        String key = label == null ? "" : label.toLowerCase();
        if (key.startsWith("active.template") || key.startsWith("current.step") || key.startsWith("objective")) {
            return "objective";
        }
        if (key.startsWith("current.target.") || key.startsWith("selected.") || key.startsWith("consume.") || key.startsWith("move.") || key.startsWith("path.") || key.startsWith("world.") || key.startsWith("result.action")) {
            return "world";
        }
        if (key.startsWith("result.") || key.startsWith("final.progress")) {
            return "summary";
        }
        return "state";
    }

    private static String rowTypeForState(String section, String label) {
        return switch (section) {
            case "objective" -> "objective_state";
            case "world" -> "world_state";
            case "summary" -> "summary_state";
            default -> "memory";
        };
    }

    private static int indentFor(String stage) {
        if (stage == null || stage.equals("[mode]")) {
            return 0;
        }
        return switch (stage) {
            case "[delegate]" -> frameDepth + 1;
            case "[return]" -> Math.max(1, frameDepth);
            case "[wait]", "[prog]", "[mem ]" -> frameDepth + 1;
            case "[run ]", "[branch]", "[ok  ]", "[block]", "[fail]", "[done]" -> frameDepth;
            default -> Math.max(0, frameDepth);
        };
    }

    private static ParsedRow parseRow(String stage, String message) {
        String raw = message == null ? "" : message.trim();
        String label = typeFor(stage);
        String value = raw;
        if (raw.contains(" = ")) {
            int split = raw.indexOf(" = ");
            label = raw.substring(0, split).trim();
            value = raw.substring(split + 3).trim();
        } else if (raw.contains(" -> ")) {
            int split = raw.indexOf(" -> ");
            label = raw.substring(0, split).trim();
            value = raw.substring(split + 4).trim();
        }
        if ("[raw ]".equals(stage)) {
            label = "raw_input";
            value = raw;
        }
        return new ParsedRow(typeFor(stage), label, value);
    }

    private static String typeFor(String stage) {
        if (stage == null || stage.isBlank()) {
            return "event";
        }
        return switch (stage) {
            case "[raw ]" -> "input";
            case "[tok ]" -> "tokenize";
            case "[lex ]" -> "lexicon";
            case "[sel ]" -> "selector";
            case "[gram]" -> "grammar";
            case "[cond]" -> "condition";
            case "[res ]" -> "resolve";
            case "[pick]" -> "search";
            case "[bind]" -> "bind";
            case "[cache]" -> "compile_cache";
            case "[run ]" -> "run";
            case "[delegate]" -> "delegate";
            case "[return]" -> "return";
            case "[wait]" -> "wait";
            case "[branch]" -> "branch";
            case "[mem ]" -> "memory";
            case "[done]", "[fail]" -> "summary";
            case "[prog]" -> "progress";
            case "[mode]" -> "header";
            case "[cand]" -> "candidate";
            default -> "event";
        };
    }

    private static String sanitize(String value) {
        return value == null ? "row" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void persist() {
        long now = System.nanoTime();
        if (lastPersistNanos != 0L && now - lastPersistNanos < PERSIST_INTERVAL_NANOS) {
            return;
        }
        lastPersistNanos = now;
        AutomationCliSnapshotStore.save(snapshot());
    }

    private static void enforceRowBudget() {
        if (ROWS.size() <= MAX_ROWS) {
            return;
        }
        compactingRows = true;
        int hidden = 0;
        try {
            for (int index = 0; index < ROWS.size() && ROWS.size() - hidden > COMPACT_TO_ROWS; index++) {
                AutomationCliRow row = ROWS.get(index);
                if (!row.visible() || "header".equals(row.sectionId()) || "summary".equals(row.sectionId())) {
                    continue;
                }
                ROWS.set(index, row.withVisibility(false));
                hidden++;
            }
            upsert("history:compacted", "summary", "summary", 0, "[info]", "history.compacted_rows", String.valueOf(hidden), true);
        } finally {
            compactingRows = false;
        }
    }

    private static void compactSuccessfulFrame(String frameId) {
        hideRow("frame:" + frameId);
        hideRow("frame-objective:" + frameId);
        hideRow("frame-state:" + frameId);
        hideRowsByPrefix("node:" + frameId + ":");
        hideRowsByPrefix("frame-parent:" + frameId);
        hideRowsByPrefix("frame-resume:" + frameId);
        hideRowsByPrefix("frame-semantic:" + frameId);
        hideRowsByPrefix("frame-diagnostic:" + frameId + ":");
        hideRowsByPrefix("frame-param:" + frameId + ":");
        hideRowsByPrefix("frame-result:" + frameId + ":");
        hideRowsByPrefix("frame-snapshot:" + frameId + ":");
        hideRowsByPrefix("primitive:" + frameId + ":");
        hideRowsByPrefix("primitive-param:" + frameId + ":");
        hideRowsByPrefix("state-write:" + frameId + ":");
        hideRowsByPrefix("state-remove:" + frameId + ":");
        hideRowsByPrefix("state-result:" + frameId + ":");
        hideRowsByPrefix("flow:" + frameId + ":");
        hideRowsByPrefix("branch:" + frameId + ":");
        hideRowsByPrefix("delegate:" + frameId + ":");
        hideRowsByPrefix("pause:" + frameId + ":");
        hideRowsByPrefix("blocked:" + frameId + ":");
    }

    private static void hideRow(String rowId) {
        Integer index = ROW_INDEX.get(rowId);
        if (index == null) {
            return;
        }
        AutomationCliRow existing = ROWS.get(index);
        ROWS.set(index, existing.withVisibility(false));
    }

    private static void hideRowsByPrefix(String prefix) {
        for (Map.Entry<String, Integer> entry : ROW_INDEX.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            AutomationCliRow existing = ROWS.get(entry.getValue());
            ROWS.set(entry.getValue(), existing.withVisibility(false));
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }

    private static String compactSignal(String label, Object value) {
        String cleanLabel = clean(label).trim();
        String cleanValue = value == null ? "" : String.valueOf(value).trim();
        if (cleanLabel.isBlank()) {
            return cleanValue;
        }
        if (cleanValue.isBlank()) {
            return cleanLabel;
        }
        return cleanLabel + " = " + cleanValue;
    }

    private static String renderMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return "(none)";
        }
        List<String> rendered = new ArrayList<>();
        for (Map.Entry<String, Object> entry : new java.util.TreeMap<>(values).entrySet()) {
            rendered.add(entry.getKey() + "=" + String.valueOf(entry.getValue()));
        }
        return String.join(", ", rendered);
    }

    private static String sourceFromDetail(String detailValue) {
        String text = clean(detailValue);
        String file = tokenAfter(text, "source.file=");
        String line = tokenAfter(text, "source.line=");
        if (file.isBlank()) {
            return "";
        }
        return line.isBlank() ? file : file + ":" + line;
    }

    private static String tokenAfter(String text, String prefix) {
        String value = clean(text);
        int start = value.indexOf(prefix);
        if (start < 0) {
            return "";
        }
        int begin = start + prefix.length();
        int end = begin;
        while (end < value.length() && !Character.isWhitespace(value.charAt(end)) && value.charAt(end) != ',' && value.charAt(end) != ';') {
            end++;
        }
        return value.substring(begin, end).trim();
    }

    private static String plannerRequires(String rowType, String label) {
        String combined = clean(rowType) + " " + clean(label);
        if (combined.contains("reload")) {
            return "automation source fingerprint";
        }
        if (combined.contains("interpret")) {
            return "raw prompt + compiled KTL indexes";
        }
        if (combined.contains("ready")) {
            return "compiled execution plan";
        }
        return "";
    }

    private static String recoveryForFailure(String text) {
        String lower = clean(text).toLowerCase();
        if (lower.contains("target") && (lower.contains("range") || lower.contains("reachable"))) {
            return "move closer, check target resolution, or lower the stop distance";
        }
        if (lower.contains("inventory") || lower.contains("item") || lower.contains("consume")) {
            return "check item id binding, selected slot, item count, and whether the item can be used";
        }
        if (lower.contains("block") || lower.contains("mine") || lower.contains("place")) {
            return "check block target, reach distance, tool state, and obstruction";
        }
        if (lower.contains("cap.")) {
            return "check capability registration and required primitive params";
        }
        if (lower.contains("eval.") || lower.contains("condition")) {
            return "check evaluator key, condition bindings, and branch params";
        }
        return "inspect required input, received data, and connected previous node output";
    }

    private record ParsedRow(String rowType, String label, String value) {
    }
}
