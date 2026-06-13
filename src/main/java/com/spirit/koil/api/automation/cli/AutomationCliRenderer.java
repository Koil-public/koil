package com.spirit.koil.api.automation.cli;

import com.spirit.koil.api.console.ConsoleChannel;
import com.spirit.koil.api.console.ConsoleLevel;
import com.spirit.koil.api.console.ConsoleRecord;
import com.spirit.koil.api.console.ConsoleStyledLine;
import com.spirit.koil.api.console.ConsoleStyledSpan;
import com.spirit.koil.api.console.ConsoleTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class AutomationCliRenderer {
    private static final int LABEL_WIDTH = 36;
    private static final int TYPE_WIDTH = 9;
    private static final String RAIL = "│  ";

    private static String cachedSessionId = "";
    private static String cachedQuery = "";
    private static int cachedSignature;
    private static List<ConsoleStyledLine> cachedLines = List.of();

    private AutomationCliRenderer() {
    }

    public static synchronized List<ConsoleStyledLine> render(AutomationCliSnapshot snapshot, String query) {
        if (snapshot == null || snapshot.rows() == null) {
            return List.of();
        }
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int signature = signature(snapshot, normalizedQuery);
        if (Objects.equals(cachedSessionId, snapshot.sessionId()) && Objects.equals(cachedQuery, normalizedQuery) && cachedSignature == signature) {
            return cachedLines;
        }

        List<ConsoleStyledLine> lines = new ArrayList<>();
        long sequence = 1L;
        String lastSection = "";
        for (AutomationCliRow row : snapshot.rows()) {
            if (!row.visible()) {
                continue;
            }
            if (!lastSection.equals(row.sectionId())) {
                if (!lastSection.isEmpty()) {
                    lines.add(spacer(sequence++));
                }
                lines.add(section(sequence++, row.sectionId()));
                lastSection = row.sectionId();
            }
            ConsoleStyledLine line = renderRow(sequence++, row);
            if (normalizedQuery.isEmpty() || line.plainText().toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                lines.add(line);
            }
        }

        cachedSessionId = snapshot.sessionId();
        cachedQuery = normalizedQuery;
        cachedSignature = signature;
        cachedLines = List.copyOf(lines);
        return cachedLines;
    }

    private static int signature(AutomationCliSnapshot snapshot, String query) {
        int hash = Objects.hash(snapshot.sessionId(), query, snapshot.rows().size());
        for (AutomationCliRow row : snapshot.rows()) {
            if (!row.visible()) {
                continue;
            }
            hash = 31 * hash + Objects.hash(
                    row.rowId(),
                    row.sectionId(),
                    row.rowType(),
                    row.indentationDepth(),
                    row.statusMarker(),
                    row.label(),
                    row.value()
            );
        }
        return hash;
    }

    private static ConsoleStyledLine section(long sequence, String sectionId) {
        String title = switch (sectionId) {
            case "header" -> "CLI SESSION";
            case "objective" -> "OBJECTIVE / ACTIVE BRANCH";
            case "pipeline" -> "INPUT / INTERPRETATION";
            case "execution" -> "TASK GRAPH";
            case "world" -> "WORLD / TARGET / PATH";
            case "state" -> "STATE / MEMORY / SHARED DATA";
            case "summary" -> "RESULT / EXIT STATE";
            default -> sectionId.toUpperCase(Locale.ROOT);
        };
        List<ConsoleStyledSpan> spans = new ArrayList<>();
        append(spans, "╭", ConsoleTheme.secondaryText());
        append(spans, "─".repeat(3), ConsoleTheme.secondaryText());
        append(spans, " ", ConsoleTheme.secondaryText());
        append(spans, title, sectionColor(sectionId));
        append(spans, " ", ConsoleTheme.secondaryText());
        append(spans, "─".repeat(Math.max(0, 92 - title.length())), ConsoleTheme.secondaryText());
        String plain = flatten(spans);
        return new ConsoleStyledLine(new ConsoleRecord(sequence, ConsoleChannel.CLI, ConsoleLevel.PLAIN, "", "CLI", "section", plain, plain), spans, plain);
    }

    private static int sectionColor(String sectionId) {
        return switch (sectionId) {
            case "header" -> ConsoleTheme.primaryText();
            case "objective" -> ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
            case "pipeline" -> ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "execution" -> ConsoleTheme.primaryText();
            case "world" -> ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
            case "state" -> ConsoleTheme.levelColor(ConsoleLevel.OTHER);
            case "summary" -> ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
            default -> ConsoleTheme.primaryText();
        };
    }

    private static ConsoleStyledLine spacer(long sequence) {
        List<ConsoleStyledSpan> spans = List.of(new ConsoleStyledSpan(" ", ConsoleTheme.secondaryText()));
        return new ConsoleStyledLine(new ConsoleRecord(sequence, ConsoleChannel.CLI, ConsoleLevel.PLAIN, "", "CLI", "spacer", " ", " "), spans, " ");
    }

    private static ConsoleStyledLine renderRow(long sequence, AutomationCliRow row) {
        List<ConsoleStyledSpan> spans = new ArrayList<>();
        append(spans, padStatus(row.statusMarker()), statusColor(row.statusMarker()));
        append(spans, " ", ConsoleTheme.secondaryText());
        append(spans, graphPrefix(row), graphColor(row));
        append(spans, padType(typeLabel(row)), typeColor(row));
        append(spans, " ", ConsoleTheme.secondaryText());
        append(spans, padLabel(labelForRow(row)), labelColor(row));
        if (row.value() != null && !row.value().isBlank()) {
            append(spans, " ┆ ", ConsoleTheme.secondaryText());
            append(spans, row.value(), valueColor(row, row.value()));
        }
        String plain = flatten(spans);
        return new ConsoleStyledLine(new ConsoleRecord(sequence, ConsoleChannel.CLI, ConsoleLevel.PLAIN, "", "CLI", "cli", plain, plain), spans, plain);
    }

    private static String graphPrefix(AutomationCliRow row) {
        int depth = Math.max(0, row.indentationDepth());
        if (depth == 0) {
            return nodeConnector(row);
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < depth - 1; i++) {
            builder.append(RAIL);
        }
        builder.append(nodeConnector(row));
        return builder.toString();
    }

    private static String nodeConnector(AutomationCliRow row) {
        return switch (row.rowType()) {
            case "graph_cluster", "candidate_cluster" -> "╠═▶";
            case "planner_input" -> "╟─▶";
            case "planner_tokenize", "planner_lexicon", "planner_selector", "planner_grammar", "planner_condition" -> "├─▶";
            case "planner_resolve", "planner_template", "planner_bind", "planner_cache", "planner_event" -> "├─▶";
            case "frame" -> "◆─ ";
            case "frame_pause" -> "╞═ ";
            case "frame_resume" -> "╘═ ";
            case "delegate" -> "╞═ ";
            case "return", "frame_summary", "frame_state" -> "╰─ ";
            case "branch" -> "├┬ ";
            case "blocked" -> "├! ";
            case "flow_phase" -> "│◇ ";
            case "flow_metric" -> "│● ";
            case "flow_decision" -> "│◆ ";
            case "candidate", "branch_candidate" -> "│• ";
            case "primitive", "node", "frame_meta", "frame_link", "frame_param", "frame_diag", "primitive_param", "frame_state_snapshot", "world_snapshot", "summary_snapshot" -> "├─ ";
            case "state_write", "memory", "world_state", "summary_state", "progress", "state_result" -> "│• ";
            case "state_remove" -> "│x ";
            default -> "├─ ";
        };
    }

    private static String typeLabel(AutomationCliRow row) {
        return switch (row.rowType()) {
            case "graph_cluster" -> "cluster";
            case "candidate_cluster" -> "choices";
            case "branch_candidate" -> "option";
            case "planner_input" -> "input";
            case "planner_tokenize" -> "tokens";
            case "planner_lexicon" -> "lexicon";
            case "planner_selector" -> "selector";
            case "planner_grammar" -> "grammar";
            case "planner_condition" -> "condition";
            case "planner_resolve" -> "resolve";
            case "planner_template" -> "template";
            case "planner_bind" -> "bind";
            case "planner_cache" -> "cache";
            case "planner_event" -> "planner";
            case "frame" -> "frame";
            case "frame_meta" -> "meta";
            case "frame_link" -> "link";
            case "frame_param", "primitive_param" -> "arg";
            case "frame_diag" -> "diag";
            case "frame_pause" -> "pause";
            case "frame_resume" -> "resume";
            case "node" -> "node";
            case "delegate" -> "call";
            case "return" -> "return";
            case "branch" -> "branch";
            case "blocked" -> "blocked";
            case "candidate" -> "candidate";
            case "memory", "state_write" -> "write";
            case "state_remove" -> "remove";
            case "progress" -> "progress";
            case "flow_phase" -> "phase";
            case "flow_metric" -> "metric";
            case "flow_decision" -> "decision";
            case "objective_state" -> "objective";
            case "world_state", "world_snapshot" -> "world";
            case "summary_state", "summary_snapshot", "frame_summary", "frame_state" -> "summary";
            case "primitive" -> "primitive";
            case "state_result" -> "result";
            case "resolve" -> "resolve";
            case "search" -> "search";
            case "lexicon" -> "lexicon";
            case "grammar" -> "grammar";
            case "condition" -> "condition";
            case "tokenize" -> "token";
            case "header" -> "header";
            default -> "event";
        };
    }

    private static String labelForRow(AutomationCliRow row) {
        return row.label();
    }

    private static String padStatus(String value) {
        String marker = value == null || value.isBlank() ? "[info]" : value;
        return marker.length() >= 6 ? marker : String.format("%-6s", marker);
    }

    private static String padType(String value) {
        String type = value == null ? "" : value;
        if (type.length() >= TYPE_WIDTH) {
            return type;
        }
        return String.format("%-" + TYPE_WIDTH + "s", type);
    }

    private static String padLabel(String value) {
        String label = value == null ? "" : value;
        if (label.length() >= LABEL_WIDTH) {
            return label;
        }
        return String.format("%-" + LABEL_WIDTH + "s", label);
    }

    private static int statusColor(String marker) {
        return switch (marker) {
            case "[fail]" -> ConsoleTheme.levelColor(ConsoleLevel.ERROR);
            case "[block]", "[warn]" -> ConsoleTheme.levelColor(ConsoleLevel.WARN);
            case "[done]" -> ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
            case "[ok  ]" -> ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
            case "[cache]" -> ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
            case "[branch]" -> ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "[bind]" -> ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
            case "[mem ]" -> ConsoleTheme.levelColor(ConsoleLevel.OTHER);
            default -> ConsoleTheme.primaryText();
        };
    }

    private static int graphColor(AutomationCliRow row) {
        return switch (row.rowType()) {
            case "graph_cluster", "candidate_cluster", "branch_candidate", "planner_input", "planner_tokenize", "planner_lexicon", "planner_selector", "planner_grammar", "planner_condition" -> ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "planner_resolve", "planner_template", "planner_bind", "planner_cache", "planner_event" -> ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
            case "frame", "node" -> ConsoleTheme.primaryText();
            case "delegate", "frame_pause", "branch" -> ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "return", "frame_resume", "frame_summary", "frame_state", "state_result" -> ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
            case "blocked", "state_remove", "condition" -> ConsoleTheme.levelColor(ConsoleLevel.WARN);
            case "flow_phase" -> ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "flow_metric" -> ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
            case "flow_decision" -> ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
            case "world_state", "world_snapshot" -> ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
            case "memory", "state_write", "progress" -> ConsoleTheme.secondaryText();
            default -> ConsoleTheme.secondaryText();
        };
    }

    private static int typeColor(AutomationCliRow row) {
        return switch (row.rowType()) {
            case "graph_cluster", "candidate_cluster" -> ConsoleTheme.primaryText();
            case "branch_candidate" -> ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "planner_input", "planner_tokenize", "planner_lexicon", "planner_selector", "planner_grammar", "planner_condition" -> ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "planner_resolve", "planner_template", "planner_bind", "planner_cache", "planner_event" -> ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
            case "frame", "node", "header" -> ConsoleTheme.primaryText();
            case "delegate", "frame_pause", "branch", "grammar" -> ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "return", "frame_resume", "frame_summary", "frame_state", "summary_state", "summary_snapshot", "state_result" -> ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
            case "blocked", "condition", "state_remove" -> ConsoleTheme.levelColor(ConsoleLevel.WARN);
            case "resolve", "search", "frame_param", "primitive_param" -> ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
            case "lexicon", "tokenize", "candidate" -> ConsoleTheme.levelColor(ConsoleLevel.OTHER);
            case "flow_phase" -> ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "flow_metric" -> ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
            case "flow_decision" -> ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
            case "world_state", "world_snapshot" -> ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
            case "memory", "state_write", "progress", "frame_diag", "frame_state_snapshot" -> ConsoleTheme.secondaryText();
            default -> ConsoleTheme.secondaryText();
        };
    }

    private static int labelColor(AutomationCliRow row) {
        return switch (row.rowType()) {
            case "graph_cluster", "candidate_cluster" -> ConsoleTheme.primaryText();
            case "branch_candidate" -> row.statusMarker().equals("[ok  ]") ? ConsoleTheme.levelColor(ConsoleLevel.DEBUG) : ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "planner_input", "planner_tokenize", "planner_lexicon", "planner_selector", "planner_grammar", "planner_condition" -> ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "planner_resolve", "planner_template", "planner_bind", "planner_cache", "planner_event" -> ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
            case "blocked" -> ConsoleTheme.levelColor(ConsoleLevel.WARN);
            case "frame_state", "frame_summary" -> ConsoleTheme.primaryText();
            case "condition" -> ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "flow_phase" -> ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "flow_metric" -> ConsoleTheme.primaryText();
            case "flow_decision" -> ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
            default -> ConsoleTheme.primaryText();
        };
    }

    private static int valueColor(AutomationCliRow row, String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("ambiguous") || lower.contains("unresolved") || lower.contains("no_match")) {
            return ConsoleTheme.levelColor(ConsoleLevel.WARN);
        }
        if (lower.contains("success") || lower.contains("complete") || lower.contains("done")) {
            return ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
        }
        if (lower.contains("fail") || lower.contains("error") || lower.contains("blocked")) {
            return ConsoleTheme.levelColor(ConsoleLevel.ERROR);
        }
        if (lower.contains("then") || lower.contains("else") || lower.contains("queued") || lower.contains("ready")) {
            return ConsoleTheme.levelColor(ConsoleLevel.INFO);
        }
        return switch (row.rowType()) {
            case "graph_cluster", "candidate_cluster" -> ConsoleTheme.primaryText();
            case "branch_candidate" -> row.statusMarker().equals("[ok  ]") ? ConsoleTheme.levelColor(ConsoleLevel.DEBUG) : ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "planner_input", "planner_tokenize", "planner_lexicon", "planner_selector", "planner_grammar", "planner_condition" -> ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "planner_resolve", "planner_template", "planner_bind", "planner_cache", "planner_event" -> ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
            case "lexicon", "tokenize", "candidate" -> ConsoleTheme.levelColor(ConsoleLevel.OTHER);
            case "resolve", "search", "frame_param", "primitive_param", "world_state", "world_snapshot" -> ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
            case "memory", "state_write", "frame_diag", "frame_state_snapshot", "progress" -> ConsoleTheme.secondaryText();
            case "delegate", "frame_pause", "branch" -> ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "flow_phase" -> ConsoleTheme.levelColor(ConsoleLevel.INFO);
            case "flow_metric" -> ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
            case "flow_decision" -> ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
            case "return", "frame_resume", "frame_summary", "frame_state", "state_result" -> row.statusMarker().equals("[fail]") ? ConsoleTheme.levelColor(ConsoleLevel.ERROR) : ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
            case "blocked", "state_remove", "condition" -> ConsoleTheme.levelColor(ConsoleLevel.WARN);
            default -> ConsoleTheme.primaryText();
        };
    }

    private static void append(List<ConsoleStyledSpan> spans, String text, int color) {
        if (text != null && !text.isEmpty()) {
            spans.add(new ConsoleStyledSpan(text, color));
        }
    }

    private static String flatten(List<ConsoleStyledSpan> spans) {
        StringBuilder builder = new StringBuilder();
        for (ConsoleStyledSpan span : spans) {
            builder.append(span.text());
        }
        return builder.toString();
    }
}
