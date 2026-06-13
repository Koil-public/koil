package com.spirit.koil.api.automation.feedback;

import com.spirit.koil.api.automation.cli.AutomationCliRow;

import java.util.Locale;

public record AutomationFeedbackNode(
        String taskId,
        String rowId,
        String nodeId,
        String label,
        String nodeType,
        String frameId,
        String value,
        String source,
        String inputs
) {
    public AutomationFeedbackNode {
        taskId = safe(taskId);
        rowId = safe(rowId);
        nodeId = safe(nodeId);
        label = safe(label);
        nodeType = safe(nodeType).isBlank() ? "ui" : safe(nodeType).toLowerCase(Locale.ROOT);
        frameId = safe(frameId);
        value = safe(value);
        source = safe(source);
        inputs = safe(inputs);
    }

    public static AutomationFeedbackNode fromRow(String taskId, AutomationCliRow row) {
        String rowId = row == null ? "" : row.rowId();
        String value = row == null ? "" : row.value();
        String nodeId = parseNodeId(rowId, value, row == null ? "" : row.label());
        String frameId = parseFrameId(rowId, value);
        String nodeType = AutomationFailureRegistry.nodeTypeFor(row);
        String source = row == null ? "" : row.source();
        String inputs = row == null ? "" : firstNonBlank(row.requires(), row.received(), row.search());
        return new AutomationFeedbackNode(taskId, rowId, nodeId, row == null ? "" : row.label(), nodeType, frameId, value, source, inputs);
    }

    public boolean matches(String id) {
        String normalized = normalize(id);
        if (normalized.isBlank()) {
            return false;
        }
        return normalize(rowId).equals(normalized)
                || normalize(nodeId).equals(normalized)
                || normalize(label).equals(normalized)
                || normalize(shortNodeId()).equals(normalized);
    }

    public String shortNodeId() {
        if (!nodeId.isBlank()) {
            return nodeId;
        }
        if (!rowId.isBlank()) {
            int last = rowId.lastIndexOf(':');
            return last < 0 ? rowId : rowId.substring(last + 1);
        }
        return label;
    }

    private static String parseNodeId(String rowId, String value, String label) {
        String fromValue = tokenAfter(value, "node.id = ");
        if (!fromValue.isBlank()) {
            return fromValue;
        }
        if (rowId != null && rowId.startsWith("node:")) {
            String[] parts = rowId.split(":");
            if (parts.length >= 3) {
                return parts[parts.length - 1];
            }
        }
        if (label != null && label.startsWith("node ")) {
            return label.substring("node ".length()).trim();
        }
        return rowId == null ? "" : rowId;
    }

    private static String parseFrameId(String rowId, String value) {
        String fromValue = tokenAfter(value, "frame = ");
        if (!fromValue.isBlank()) {
            return fromValue;
        }
        if (rowId != null) {
            String[] parts = rowId.split(":");
            for (String part : parts) {
                if (part.startsWith("frame-")) {
                    return part;
                }
            }
        }
        return "";
    }

    private static String tokenAfter(String text, String prefix) {
        if (text == null || prefix == null) {
            return "";
        }
        int start = text.indexOf(prefix);
        if (start < 0) {
            return "";
        }
        String tail = text.substring(start + prefix.length()).trim();
        int end = 0;
        while (end < tail.length()) {
            char value = tail.charAt(end);
            if (Character.isWhitespace(value) && end + 1 < tail.length() && Character.isWhitespace(tail.charAt(end + 1))) {
                break;
            }
            end++;
        }
        return tail.substring(0, end).trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String normalize(String value) {
        return safe(value).trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
