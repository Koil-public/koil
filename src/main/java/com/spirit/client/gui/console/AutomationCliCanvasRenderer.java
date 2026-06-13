package com.spirit.client.gui.console;

import com.spirit.koil.api.automation.cli.AutomationCliRow;
import com.spirit.koil.api.automation.cli.AutomationCliSnapshot;
import com.spirit.koil.api.automation.feedback.AutomationFeedbackNode;
import com.spirit.koil.api.console.ConsoleLevel;
import com.spirit.koil.api.console.ConsoleTheme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class AutomationCliCanvasRenderer {
    private static final int MIN_NODE_WIDTH = 104;
    private static final int MAX_NODE_WIDTH = 196;
    private static final int MIN_NODE_HEIGHT = 22;
    private static final int MAX_NODE_HEIGHT = 46;
    private static final int NODE_GAP_Y = 8;
    private static final int SCROLL_UNIT = 34;
    private static final int TOP_PADDING = 10;
    private static final int DETAIL_MIN_WIDTH = 210;
    private static final int DETAIL_LINE_HEIGHT = 13;
    private static final int HEADER_ROW_HEIGHT = 11;
    private static String selectedRowId = "";

    private AutomationCliCanvasRenderer() {
    }

    static int render(DrawContext context, TextRenderer textRenderer, AutomationCliSnapshot snapshot, int x, int y, int width, int height, double scrollOffset, int panX, String query, int mouseX, int mouseY) {
        if (snapshot == null || snapshot.rows() == null) {
            return 0;
        }
        CanvasLayout layout = layout(snapshot, query, width);
        int contentHeight = Math.max(height, layout.contentHeight());
        int maxScrollPixels = Math.max(0, contentHeight - height);
        int scrollPixels = Math.min(maxScrollPixels, Math.max(0, (int) Math.round(scrollOffset * SCROLL_UNIT)));
        HoverNode hovered = null;
        HoverLink hoveredLink = null;
        HoverLink hoveredAssociation = null;
        HoverCue hoveredCue = null;
        FrameBand hoveredFrame = hoveredFrame(layout, x, y, height, scrollPixels, mouseX, mouseY);
        CanvasNode selected = selectedNode(layout);

        context.enableScissor(x, y, x + width, y + height);
        drawBackground(context, x, y, width, height, scrollPixels);
        HoverCue brainCue = drawEventStream(context, textRenderer, layout, x, y, height, scrollPixels, mouseX, mouseY);
        drawFrameBands(context, textRenderer, layout, x, y, height, scrollPixels, hoveredFrame);
        hoveredAssociation = drawAssociativeLinks(context, layout, x, y, height, scrollPixels, mouseX, mouseY);
        HoverLink hoveredReturnLink = drawReturnLinks(context, layout, x, y, height, scrollPixels, mouseX, mouseY);
        for (CanvasNode node : layout.nodes()) {
            if (node.previous() == null) {
                continue;
            }
            int nodeX = x + node.x() + panX;
            int nodeY = y + node.y() - scrollPixels;
            int previousX = x + node.previous().x() + panX;
            int previousY = y + node.previous().y() - scrollPixels;
            if (connectorVisible(previousY, nodeY, y, height)) {
                HoverLink hoverLink = hoveredConnector(previousX, previousY, nodeX, nodeY, node, mouseX, mouseY);
                boolean selectedLink = selected != null && (selected.rowId().equals(node.rowId()) || selected.rowId().equals(node.previous().rowId()));
                drawConnector(context, previousX, previousY, nodeX, nodeY, node, layout.activeTransfer(), hoverLink, selectedLink);
                if (hoverLink != null) {
                    hoveredLink = hoverLink;
                }
            }
        }
        for (CanvasNode node : layout.nodes()) {
            int nodeX = x + node.x() + panX;
            int nodeY = y + node.y() - scrollPixels;
            if (nodeY > y + height || nodeY + node.height() < y) {
                continue;
            }
            HoverCue cue = cueAt(node, nodeX, nodeY, mouseX, mouseY);
            boolean hover = cue != null || nodeContains(textRenderer, node, nodeX, nodeY, mouseX, mouseY);
            boolean selectedNode = selected != null && selected.rowId().equals(node.rowId());
            drawNeuronNode(context, textRenderer, nodeX, nodeY, node, hover || selectedNode, selectedNode);
            if (cue != null) {
                hoveredCue = cue;
            } else if (hover) {
                hovered = new HoverNode(node, nodeX, nodeY);
            }
        }
        drawHeader(context, textRenderer, snapshot, layout, x, y, width);
        context.disableScissor();
        if (selected != null) {
            drawSelectedNodeInspector(context, textRenderer, selected, layout, x, y, width, height, scrollPixels, panX);
        }
        if (brainCue != null) {
            drawCueHoverCard(context, textRenderer, brainCue, mouseX, mouseY);
        } else if (hoveredCue != null) {
            drawCueHoverCard(context, textRenderer, hoveredCue, mouseX, mouseY);
        } else if (hovered != null) {
            drawHoverCard(context, textRenderer, hovered.node(), mouseX, mouseY, x, y, width, height);
        } else if (hoveredLink != null) {
            drawLinkHoverCard(context, textRenderer, hoveredLink, mouseX, mouseY, x, y, width, height);
        } else if (hoveredReturnLink != null) {
            drawLinkHoverCard(context, textRenderer, hoveredReturnLink, mouseX, mouseY, x, y, width, height);
        } else if (hoveredAssociation != null) {
            drawLinkHoverCard(context, textRenderer, hoveredAssociation, mouseX, mouseY, x, y, width, height);
        } else if (hoveredFrame != null) {
            drawFrameHoverCard(context, textRenderer, hoveredFrame, mouseX, mouseY, x, y, width, height);
        }
        return Math.max(1, (contentHeight + SCROLL_UNIT - 1) / SCROLL_UNIT);
    }

    static int selectNodeAt(AutomationCliSnapshot snapshot, int x, int y, int width, int height, double scrollOffset, int panX, String query, int mouseX, int mouseY) {
        if (snapshot == null || snapshot.rows() == null) {
            selectedRowId = "";
            return -1;
        }
        CanvasLayout layout = layout(snapshot, query, width);
        int contentHeight = Math.max(height, layout.contentHeight());
        int maxScrollPixels = Math.max(0, contentHeight - height);
        int scrollPixels = Math.min(maxScrollPixels, Math.max(0, (int) Math.round(scrollOffset * SCROLL_UNIT)));
        for (int index = layout.nodes().size() - 1; index >= 0; index--) {
            CanvasNode node = layout.nodes().get(index);
            int nodeX = x + node.x() + panX;
            int nodeY = y + node.y() - scrollPixels;
            if (nodeY > y + height || nodeY + node.height() < y) {
                continue;
            }
            if (nodeContains(null, node, nodeX, nodeY, mouseX, mouseY)) {
                selectedRowId = node.rowId();
                int visibleUnits = visibleUnits(height);
                int totalUnits = Math.max(1, (contentHeight + SCROLL_UNIT - 1) / SCROLL_UNIT);
                int target = Math.max(0, (node.y() / SCROLL_UNIT) - Math.max(0, visibleUnits / 2));
                return clamp(target, 0, Math.max(0, totalUnits - visibleUnits));
            }
        }
        return -1;
    }

    static AutomationFeedbackNode feedbackNodeAt(AutomationCliSnapshot snapshot, int x, int y, int width, int height, double scrollOffset, int panX, String query, int mouseX, int mouseY) {
        if (snapshot == null || snapshot.rows() == null) {
            return null;
        }
        CanvasLayout layout = layout(snapshot, query, width);
        int contentHeight = Math.max(height, layout.contentHeight());
        int maxScrollPixels = Math.max(0, contentHeight - height);
        int scrollPixels = Math.min(maxScrollPixels, Math.max(0, (int) Math.round(scrollOffset * SCROLL_UNIT)));
        for (int index = layout.nodes().size() - 1; index >= 0; index--) {
            CanvasNode node = layout.nodes().get(index);
            int nodeX = x + node.x() + panX;
            int nodeY = y + node.y() - scrollPixels;
            if (nodeY > y + height || nodeY + node.height() < y) {
                continue;
            }
            if (nodeContains(null, node, nodeX, nodeY, mouseX, mouseY)) {
                return AutomationFeedbackNode.fromRow(snapshot.sessionId(), rowById(snapshot, node.rowId()));
            }
        }
        return null;
    }

    private static AutomationCliRow rowById(AutomationCliSnapshot snapshot, String rowId) {
        for (AutomationCliRow row : snapshot.rows()) {
            if (row.rowId().equals(rowId)) {
                return row;
            }
        }
        return null;
    }

    static String ktlSourceAt(AutomationCliSnapshot snapshot, int x, int y, int width, int height, double scrollOffset, int panX, String query, int mouseX, int mouseY) {
        if (snapshot == null || snapshot.rows() == null) {
            return "";
        }
        CanvasLayout layout = layout(snapshot, query, width);
        int contentHeight = Math.max(height, layout.contentHeight());
        int maxScrollPixels = Math.max(0, contentHeight - height);
        int scrollPixels = Math.min(maxScrollPixels, Math.max(0, (int) Math.round(scrollOffset * SCROLL_UNIT)));
        for (int index = layout.nodes().size() - 1; index >= 0; index--) {
            CanvasNode node = layout.nodes().get(index);
            int nodeX = x + node.x() + panX;
            int nodeY = y + node.y() - scrollPixels;
            if (nodeY > y + height || nodeY + node.height() < y) {
                continue;
            }
            if (nodeContains(null, node, nodeX, nodeY, mouseX, mouseY)) {
                return resolveKtlSourcePath(node);
            }
        }
        return "";
    }

    static int ktlSourceLineAt(AutomationCliSnapshot snapshot, int x, int y, int width, int height, double scrollOffset, int panX, String query, int mouseX, int mouseY) {
        if (snapshot == null || snapshot.rows() == null) {
            return -1;
        }
        CanvasLayout layout = layout(snapshot, query, width);
        int contentHeight = Math.max(height, layout.contentHeight());
        int maxScrollPixels = Math.max(0, contentHeight - height);
        int scrollPixels = Math.min(maxScrollPixels, Math.max(0, (int) Math.round(scrollOffset * SCROLL_UNIT)));
        for (int index = layout.nodes().size() - 1; index >= 0; index--) {
            CanvasNode node = layout.nodes().get(index);
            int nodeX = x + node.x() + panX;
            int nodeY = y + node.y() - scrollPixels;
            if (nodeY > y + height || nodeY + node.height() < y) {
                continue;
            }
            if (nodeContains(null, node, nodeX, nodeY, mouseX, mouseY)) {
                return sourceLineNumber(node);
            }
        }
        return -1;
    }

    static boolean clearSelection() {
        if (selectedRowId.isBlank()) {
            return false;
        }
        selectedRowId = "";
        return true;
    }

    static boolean clearSelectionIfOutsideViewport(AutomationCliSnapshot snapshot, int width, int height, int scrollOffset, String query) {
        if (selectedRowId.isBlank() || snapshot == null || snapshot.rows() == null) {
            return false;
        }
        CanvasLayout layout = layout(snapshot, query, width);
        CanvasNode selected = selectedNode(layout);
        if (selected == null) {
            selectedRowId = "";
            return true;
        }
        int scrollPixels = Math.max(0, scrollOffset * SCROLL_UNIT);
        int top = selected.y() - scrollPixels;
        int bottom = top + selected.height();
        if (bottom < 0 || top > height) {
            selectedRowId = "";
            return true;
        }
        return false;
    }

    static int count(AutomationCliSnapshot snapshot, String query) {
        if (snapshot == null || snapshot.rows() == null) {
            return 0;
        }
        return Math.max(1, (layout(snapshot, query, 800).contentHeight() + SCROLL_UNIT - 1) / SCROLL_UNIT);
    }

    static int visibleUnits(int viewportHeight) {
        return Math.max(1, viewportHeight / SCROLL_UNIT);
    }

    static int focusOffset(AutomationCliSnapshot snapshot, String query, int viewportHeight, int totalUnits) {
        if (snapshot == null || snapshot.rows() == null) {
            return 0;
        }
        CanvasLayout layout = layout(snapshot, query, 800);
        CanvasNode focus = null;
        for (CanvasNode node : layout.nodes()) {
            if (node.firing()) {
                focus = node;
            }
        }
        if (focus == null && !layout.nodes().isEmpty()) {
            focus = layout.nodes().get(layout.nodes().size() - 1);
        }
        if (focus == null) {
            return 0;
        }
        int visibleUnits = visibleUnits(viewportHeight);
        int target = Math.max(0, (focus.y() / SCROLL_UNIT) - Math.max(0, visibleUnits / 2));
        int maxScroll = Math.max(0, totalUnits - visibleUnits);
        return clamp(target, 0, maxScroll);
    }

    private static CanvasNode selectedNode(CanvasLayout layout) {
        if (selectedRowId.isBlank()) {
            return null;
        }
        for (CanvasNode node : layout.nodes()) {
            if (selectedRowId.equals(node.rowId())) {
                return node;
            }
        }
        return null;
    }

    private static CanvasLayout layout(AutomationCliSnapshot snapshot, String query, int width) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<AutomationCliRow> rows = filteredRows(snapshot, normalizedQuery);
        List<AutomationCliRow> headerRows = headerRows(rows);
        List<AutomationCliRow> graphRows = graphRows(rows);
        Map<String, Integer> frequencies = frequencies(graphRows);
        Map<String, String> parentByFrame = parentFrames(graphRows);
        int eventWidth = width >= 760 ? Math.max(DETAIL_MIN_WIDTH, Math.min(300, width / 3)) : 0;
        int graphWidth = width - eventWidth - (eventWidth > 0 ? 12 : 0);
        int centerX = Math.max(120, graphWidth / 2);
        int maxLeft = 12;
        int maxRight = Math.max(maxLeft + MAX_NODE_WIDTH, graphWidth - MAX_NODE_WIDTH - 12);
        List<CanvasNode> nodes = new ArrayList<>();
        CanvasNode previous = null;
        Map<String, CanvasNode> previousBySection = new LinkedHashMap<>();
        Map<String, CanvasNode> previousByType = new LinkedHashMap<>();
        TerminalState terminalState = terminalState(graphRows);
        int headerHeight = headerBlockHeight(headerRows);
        int y = TOP_PADDING + 52 + headerHeight;
        String currentFrameId = "";
        for (int index = 0; index < graphRows.size(); index++) {
            AutomationCliRow row = graphRows.get(index);
            String frameId = frameIdFor(row, currentFrameId);
            if (!frameId.isBlank()) {
                currentFrameId = frameId;
            }
            int weight = nodeWeight(row, frequencies.getOrDefault(nodeKey(row), 1));
            int nodeWidth = nodeWidth(row, weight);
            int nodeHeight = nodeHeight(row, weight);
            String parentFrameId = parentByFrame.getOrDefault(frameId, "");
            int offset = semanticOffset(row, weight, index, frameId, parentFrameId);
            int nodeX = clamp(centerX + offset - nodeWidth / 2, maxLeft, maxRight);
            String sectionKey = safe(row.sectionId());
            String typeKey = safe(row.rowType());
            CanvasNode sectionSource = previousBySection.get(sectionKey);
            CanvasNode typeSource = previousByType.get(typeKey);
            boolean firing = !terminalState.terminal() && isFiring(row, index, graphRows.size());
            NodeDetails details = detailsFor(row, previous);
            CanvasNode node = new CanvasNode(index, nodeX, y, nodeWidth, nodeHeight, weight, frameId, parentFrameId, row.rowId(), row.sectionId(), row.rowType(), row.indentationDepth(), row.statusMarker(), row.label(), row.value(), row.dirty(), firing, details, previous, sectionSource, typeSource);
            nodes.add(node);
            previous = node;
            previousBySection.put(sectionKey, node);
            previousByType.put(typeKey, node);
            y += nodeHeight + NODE_GAP_Y + Math.max(0, row.indentationDepth()) * 2;
        }
        int streamX = eventWidth > 0 ? graphWidth + 12 : 0;
        int contentHeight = Math.max(TOP_PADDING + 80, y + 12);
        BrainStats stats = stats(nodes, terminalState);
        List<FrameBand> frameBands = frameBands(nodes);
        boolean activeTransfer = !terminalState.terminal() && (stats.running() > 0 || stats.blocked() > 0 || stats.firing() > 0 || stats.processors() > 0);
        return new CanvasLayout(nodes, headerRows, frameBands, stats, activeTransfer, graphWidth, streamX, eventWidth, contentHeight, headerHeight);
    }

    private static List<AutomationCliRow> filteredRows(AutomationCliSnapshot snapshot, String normalizedQuery) {
        List<AutomationCliRow> rows = new ArrayList<>();
        for (AutomationCliRow row : snapshot.rows()) {
            if (!row.visible()) {
                continue;
            }
            String text = row.label() + " " + row.value() + " " + row.rowType() + " " + row.sectionId();
            if (!normalizedQuery.isBlank() && !text.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                continue;
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<AutomationCliRow> headerRows(List<AutomationCliRow> rows) {
        List<AutomationCliRow> headerRows = new ArrayList<>();
        for (AutomationCliRow row : rows) {
            if ("header".equals(safe(row.sectionId()))) {
                headerRows.add(row);
            }
        }
        return headerRows;
    }

    private static List<AutomationCliRow> graphRows(List<AutomationCliRow> rows) {
        List<AutomationCliRow> graphRows = new ArrayList<>();
        for (AutomationCliRow row : rows) {
            if (!"header".equals(safe(row.sectionId()))) {
                graphRows.add(row);
            }
        }
        return graphRows;
    }

    private static int headerBlockHeight(List<AutomationCliRow> headerRows) {
        return headerRows.isEmpty() ? 0 : 18;
    }

    private static String frameIdFor(AutomationCliRow row, String currentFrameId) {
        String rowId = safe(row.rowId());
        String[] parts = rowId.split(":");
        if (parts.length >= 2) {
            String prefix = parts[0];
            if (prefix.equals("frame") || prefix.equals("frame-objective") || prefix.equals("frame-parent") || prefix.equals("frame-resume") || prefix.equals("frame-semantic") || prefix.equals("frame-diagnostic") || prefix.equals("frame-param") || prefix.equals("node") || prefix.equals("primitive") || prefix.equals("primitive-param") || prefix.equals("state-write") || prefix.equals("state-remove") || prefix.equals("state-result") || prefix.equals("branch") || prefix.equals("branch-candidates") || prefix.equals("branch-then") || prefix.equals("branch-else") || prefix.equals("delegate") || prefix.equals("pause") || prefix.equals("world-probe") || prefix.equals("flow") || prefix.equals("blocked") || prefix.equals("frame-state") || prefix.equals("frame-result") || prefix.equals("frame-snapshot")) {
                return parts[1];
            }
            if ((prefix.equals("return") || prefix.equals("resume")) && parts.length >= 3) {
                return parts[1];
            }
            if (prefix.equals("frame-summary")) {
                return parts[1];
            }
        }
        String label = safe(row.label());
        String value = safe(row.value());
        if (label.equals("frame.id")) {
            int split = value.indexOf("  ");
            return split > 0 ? value.substring(0, split).trim() : value.trim();
        }
        if (label.startsWith("frame.") || label.startsWith("parent.frame") || label.startsWith("resume.")) {
            return currentFrameId;
        }
        return currentFrameId;
    }

    private static List<FrameBand> frameBands(List<CanvasNode> nodes) {
        Map<String, FrameAccumulator> frames = new LinkedHashMap<>();
        for (CanvasNode node : nodes) {
            String frameId = safe(node.frameId());
            if (frameId.isBlank()) {
                continue;
            }
            FrameAccumulator accumulator = frames.computeIfAbsent(frameId, FrameAccumulator::new);
            accumulator.accept(node);
        }
        List<FrameBand> bands = new ArrayList<>();
        for (FrameAccumulator accumulator : frames.values()) {
            bands.add(accumulator.toBand());
        }
        return bands;
    }

    private static Map<String, Integer> frequencies(List<AutomationCliRow> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AutomationCliRow row : rows) {
            String key = nodeKey(row);
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        return counts;
    }

    private static String nodeKey(AutomationCliRow row) {
        return safe(row.sectionId()) + ":" + safe(row.rowType()) + ":" + safe(row.label());
    }

    private static Map<String, String> parentFrames(List<AutomationCliRow> rows) {
        Map<String, String> parents = new LinkedHashMap<>();
        for (AutomationCliRow row : rows) {
            String rowId = safe(row.rowId());
            if (!rowId.startsWith("frame-parent:")) {
                continue;
            }
            String frameId = rowId.substring("frame-parent:".length());
            String parentFrame = safe(row.value()).trim();
            if (!frameId.isBlank() && !parentFrame.isBlank()) {
                parents.put(frameId, parentFrame);
            }
        }
        return parents;
    }

    private static int nodeWeight(AutomationCliRow row, int frequency) {
        int weight = Math.min(4, Math.max(1, frequency));
        String type = safe(row.rowType());
        String marker = safe(row.statusMarker());
        if (marker.equals("[fail]") || marker.equals("[block]") || type.contains("failed") || type.contains("blocked")) {
            weight += 4;
        } else if (type.contains("branch") || type.contains("delegate") || type.contains("return")) {
            weight += 3;
        } else if (type.contains("planner") || type.contains("candidate") || type.contains("cluster")) {
            weight += 2;
        } else if (type.contains("flow") || type.contains("world")) {
            weight += 1;
        }
        return clamp(weight, 1, 8);
    }

    private static int nodeWidth(AutomationCliRow row, int weight) {
        int labelWidth = safe(row.label()).length() * 4;
        return clamp(MIN_NODE_WIDTH + weight * 8 + Math.min(36, labelWidth), MIN_NODE_WIDTH, MAX_NODE_WIDTH);
    }

    private static int nodeHeight(AutomationCliRow row, int weight) {
        int height = safe(row.value()).isBlank() ? MIN_NODE_HEIGHT : 30;
        if (weight >= 6) {
            height += 8;
        } else if (weight >= 4) {
            height += 4;
        }
        return clamp(height, MIN_NODE_HEIGHT, MAX_NODE_HEIGHT);
    }

    private static int semanticOffset(AutomationCliRow row, int weight, int index, String frameId, String parentFrameId) {
        String section = safe(row.sectionId());
        String type = safe(row.rowType());
        int sign = index % 2 == 0 ? -1 : 1;
        int depth = Math.max(0, row.indentationDepth());
        int base = 0;
        if (section.equals("pipeline") || type.contains("planner")) {
            base = -160;
        } else if (section.equals("world") || type.contains("world")) {
            base = 160;
        } else if (section.equals("state") || section.equals("summary")) {
            base = 112;
        } else if (type.contains("delegate") || type.contains("frame_pause")) {
            base = -210;
        } else if (type.contains("return") || type.contains("frame_resume") || type.contains("frame_summary")) {
            base = 210;
        } else if (type.contains("primitive") || type.contains("flow")) {
            base = depth % 2 == 0 ? -92 : 92;
        } else if (type.contains("branch") || type.contains("candidate")) {
            base = sign * 118;
        } else if (type.contains("frame") && depth > 0) {
            base = depth % 2 == 0 ? -132 : 132;
        }
        if (!safe(parentFrameId).isBlank()) {
            int branchDirection = Math.floorMod(safe(frameId).hashCode(), 2) == 0 ? -1 : 1;
            base += branchDirection * (150 + Math.min(120, Math.max(1, depth) * 22));
        }
        return base + sign * Math.min(48, depth * 16) + sign * Math.min(24, weight * 3);
    }

    private static boolean isFiring(AutomationCliRow row, int index, int totalRows) {
        String marker = safe(row.statusMarker());
        String type = safe(row.rowType());
        if (marker.equals("[done]") || marker.equals("[ok  ]") || marker.equals("[fail]")) {
            return false;
        }
        if (marker.equals("[run ]") || marker.equals("[block]")) {
            return true;
        }
        if (row.dirty() && !marker.isBlank() && !marker.equals("[info]") && !marker.equals("[meta]")) {
            return true;
        }
        return type.contains("flow_running")
                || type.contains("node_running")
                || type.contains("frame_running")
                || type.contains("primitive_running")
                || type.contains("blocked")
                || type.contains("failed");
    }

    private static TerminalState terminalState(List<AutomationCliRow> rows) {
        int terminalIndex = -1;
        boolean terminalFailed = false;
        for (int index = 0; index < rows.size(); index++) {
            AutomationCliRow row = rows.get(index);
            String section = safe(row.sectionId()).toLowerCase(Locale.ROOT);
            String type = safe(row.rowType()).toLowerCase(Locale.ROOT);
            String marker = safe(row.statusMarker());
            String text = (safe(row.label()) + " " + safe(row.value())).toLowerCase(Locale.ROOT);
            boolean summaryRow = section.equals("summary") || type.contains("summary") || type.contains("frame_state");
            boolean terminalMarker = marker.equals("[done]") || marker.equals("[fail]");
            if (summaryRow && terminalMarker) {
                terminalIndex = index;
                terminalFailed = marker.equals("[fail]") || text.contains("failed");
                continue;
            }
            if (type.contains("final") || text.contains("final completion") || text.contains("final failure")) {
                terminalIndex = index;
                terminalFailed = text.contains("failure") || text.contains("failed") || marker.equals("[fail]");
                continue;
            }
            if (text.contains("task complete") || text.contains("task failed") || text.contains(" = success") || text.contains(" = failed")) {
                terminalIndex = index;
                terminalFailed = text.contains("task failed") || text.contains(" = failed") || marker.equals("[fail]");
                continue;
            }
            if (marker.equals("[fail]") && (index >= rows.size() - 4 || summaryRow)) {
                terminalIndex = index;
                terminalFailed = true;
            }
        }
        if (terminalIndex < 0) {
            return new TerminalState(false, -1, false);
        }
        for (int index = terminalIndex + 1; index < rows.size(); index++) {
            if (isProcessingRow(rows.get(index))) {
                return new TerminalState(false, terminalIndex, terminalFailed);
            }
        }
        return new TerminalState(true, terminalIndex, terminalFailed);
    }

    private static boolean isProcessingRow(AutomationCliRow row) {
        String marker = safe(row.statusMarker());
        String type = safe(row.rowType()).toLowerCase(Locale.ROOT);
        return marker.equals("[run ]")
                || marker.equals("[wait]")
                || marker.equals("[block]")
                || marker.equals("[branch]")
                || type.contains("running")
                || type.contains("phase")
                || type.contains("progress");
    }

    private static NodeDetails detailsFor(AutomationCliRow row, CanvasNode previous) {
        String section = safe(row.sectionId());
        String type = safe(row.rowType());
        String label = safe(row.label());
        String value = safe(row.value());
        String all = (section + " " + type + " " + label + " " + value).toLowerCase(Locale.ROOT);
        String source = firstPresent(row.source(), previous == null ? "session root" : "#" + (previous.sequence() + 1) + " " + previous.label());
        String received = firstPresent(row.received(), previous == null ? "" : compactSignal(previous.label(), firstPresent(previous.details().output(), previous.value())));
        String search = safe(row.search());
        String requires = safe(row.requires());
        String output = safe(row.output());
        String failure = safe(row.failure());
        String recovery = safe(row.recovery());
        if (containsAny(all, "search", "scan", "resolve", "resolver", "candidate", "target", "selector", "world.ray")) {
            search = firstPresent(search, compactSignal(label, value));
        }
        if (containsAny(all, "param", "bind", "requires", "input", "cap.", "eval.", "condition", "semantic_operation", "template")) {
            requires = firstPresent(requires, compactSignal(label, value));
        }
        if (type.contains("primitive") || type.contains("node") || type.contains("frame") || type.contains("flow")) {
            requires = firstPresent(requires, compactSignal(label, value));
        }
        if (containsAny(all, "result", "return", "summary", "state_write", "state_remove", "selected", "resolved", "cache", "outcome", "complete", "success", "fail")) {
            output = compactSignal(label, value);
        }
        if (type.contains("planner_template") || type.contains("planner_resolve") || type.contains("planner_bind")) {
            output = firstPresent(output, compactSignal(label, value));
        }
        if (safe(row.statusMarker()).equals("[fail]") || safe(row.statusMarker()).equals("[block]") || containsAny(all, "fail", "blocked", "unresolved", "ambiguous", "missing", "unknown", "timeout")) {
            failure = firstPresent(failure, failureSummary(row));
            recovery = firstPresent(recovery, recoveryHint(all));
            output = firstPresent(output, compactSignal(label, value));
        }
        return new NodeDetails(search, requires, received, output, failure, recovery, source);
    }

    private static String failureSummary(AutomationCliRow row) {
        String section = safe(row.sectionId());
        String type = safe(row.rowType());
        String label = safe(row.label());
        String value = safe(row.value());
        String where = section.isBlank() ? type : section + "/" + type;
        String reason = value.isBlank() ? label : value;
        return where + " failed at " + label + " because " + reason;
    }

    private static String recoveryHint(String text) {
        if (text.contains("unresolved") || text.contains("target.none") || text.contains("no active target")) {
            return "check target words, selector rules, range, and visible world target";
        }
        if (text.contains("ambiguous") || text.contains("candidate")) {
            return "make the target more specific or add resolver preference rules";
        }
        if (text.contains("template") || text.contains("metadata")) {
            return "check template_metadata intent, target kind, and required params";
        }
        if (text.contains("cap.") || text.contains("primitive")) {
            return "check primitive params, runtime state writes, and capability registration";
        }
        if (text.contains("grammar") || text.contains("lexicon")) {
            return "check .ktl lexicon phrases and grammar pattern roles";
        }
        return "inspect connected input, output, and previous node state";
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String compactSignal(String label, String value) {
        String cleanLabel = safe(label).trim();
        String cleanValue = safe(value).trim();
        String combined;
        if (cleanLabel.isBlank()) {
            combined = cleanValue;
        } else if (cleanValue.isBlank()) {
            combined = cleanLabel;
        } else {
            combined = cleanLabel + " = " + cleanValue;
        }
        combined = combined.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return combined.length() > 128 ? combined.substring(0, 125) + "..." : combined;
    }

    private static String firstPresent(String first, String second) {
        return safe(first).isBlank() ? safe(second) : first;
    }

    private static BrainStats stats(List<CanvasNode> nodes, TerminalState terminalState) {
        int firing = 0;
        int failed = 0;
        int blocked = 0;
        int done = 0;
        int running = 0;
        int processors = 0;
        CanvasNode strongest = null;
        for (CanvasNode node : nodes) {
            if (node.firing()) {
                firing++;
            }
            if ("[fail]".equals(node.marker())) {
                failed++;
            }
            if ("[block]".equals(node.marker()) || "[warn]".equals(node.marker())) {
                blocked++;
            }
            if ("[done]".equals(node.marker()) || "[ok  ]".equals(node.marker())) {
                done++;
            }
            if ("[run ]".equals(node.marker())) {
                running++;
            }
            if (isProcessor(node)) {
                processors++;
            }
            if (strongest == null || node.weight() > strongest.weight()) {
                strongest = node;
            }
        }
        int pipeline = 0;
        int execution = 0;
        int world = 0;
        int memory = 0;
        int ktl = 0;
        int runtime = 0;
        int tokens = 0;
        int dataIn = 0;
        int dataOut = 0;
        String currentSignal = "";
        String activeState = "idle";
        int activeStateColor = ConsoleTheme.secondaryText();
        ActiveState runtimeState = runtimeActiveState(nodes);
        boolean hasLiveNode = false;
        for (CanvasNode node : nodes) {
            if (node.firing() || "[run ]".equals(node.marker())) {
                hasLiveNode = true;
                break;
            }
        }
        if (terminalState.terminal()) {
            hasLiveNode = false;
        }
        for (CanvasNode node : nodes) {
            String section = safe(node.sectionId());
            String type = safe(node.type());
            String text = (safe(node.label()) + " " + safe(node.value()) + " " + type).toLowerCase(Locale.ROOT);
            tokens += tokenCount(node.label()) + tokenCount(node.value());
            if (section.equals("pipeline")) {
                pipeline++;
            } else if (section.equals("execution") || section.equals("objective")) {
                execution++;
            } else if (section.equals("world")) {
                world++;
            } else if (section.equals("state") || section.equals("summary")) {
                memory++;
            }
            if (text.contains("ktl") || text.contains("template") || text.contains("semantic") || text.contains("grammar") || text.contains("lexicon") || text.contains("cache") || text.contains("resolver")) {
                ktl++;
            }
            if (text.contains("cap.") || text.contains("eval.") || text.contains("primitive") || text.contains("runtime") || text.contains("frame")) {
                runtime++;
            }
            if (!node.details().requires().isBlank() || !node.details().received().isBlank()) {
                dataIn++;
            }
            if (!node.details().output().isBlank()) {
                dataOut++;
            }
            if (node.firing()) {
                currentSignal = node.label();
            }
            if (hasLiveNode && !node.firing() && !"[run ]".equals(node.marker())) {
                continue;
            }
            ActiveState nodeState = activeStateForNode(node, text);
            if (nodeState.priority() >= activeStatePriority(activeState)) {
                activeState = nodeState.label();
                activeStateColor = nodeState.color();
            }
        }
        if (currentSignal.isBlank() && strongest != null) {
            currentSignal = strongest.label();
        }
        if (nodes.isEmpty()) {
            activeState = "idle";
            activeStateColor = ConsoleTheme.secondaryText();
        } else if (runtimeState.priority() > 0) {
            activeState = runtimeState.label();
            activeStateColor = runtimeState.color();
        } else if (terminalState.terminal()) {
            activeState = terminalState.failed() ? "failed" : "complete";
            activeStateColor = terminalState.failed() ? ConsoleTheme.levelColor(ConsoleLevel.ERROR) : ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
        } else if (!hasLiveNode && blocked > 0 && activeStatePriority(activeState) < 80) {
            activeState = "blocked";
            activeStateColor = ConsoleTheme.levelColor(ConsoleLevel.WARN);
        } else if (running == 0 && firing == 0 && blocked == 0 && failed == 0 && done > 0) {
            activeState = "complete";
            activeStateColor = ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
        }
        return new BrainStats(nodes.size(), firing, running, done, blocked, failed, pipeline, execution, world, memory, ktl, runtime, tokens, processors, dataIn, dataOut, strongest == null ? "" : strongest.label(), currentSignal, activeState, activeStateColor);
    }

    private static ActiveState runtimeActiveState(List<CanvasNode> nodes) {
        for (int index = nodes.size() - 1; index >= 0; index--) {
            CanvasNode node = nodes.get(index);
            if (!"active_state".equals(safe(node.type())) && !"runtime.active_state".equals(safe(node.label()))) {
                continue;
            }
            String value = safe(node.value()).toLowerCase(Locale.ROOT);
            if (value.contains("state = failed")) {
                return new ActiveState("failed", ConsoleTheme.levelColor(ConsoleLevel.ERROR), 95);
            }
            if (value.contains("state = blocked")) {
                return new ActiveState("blocked", ConsoleTheme.levelColor(ConsoleLevel.WARN), 85);
            }
            if (value.contains("state = complete")) {
                return new ActiveState("complete", ConsoleTheme.levelColor(ConsoleLevel.DEBUG), 75);
            }
            if (value.contains("state = moving")) {
                return new ActiveState("moving", ConsoleTheme.levelColor(ConsoleLevel.UPDATE), 70);
            }
            if (value.contains("state = attacking")) {
                return new ActiveState("attacking", ConsoleTheme.levelColor(ConsoleLevel.ERROR), 70);
            }
            if (value.contains("state = mining")) {
                return new ActiveState("mining", ConsoleTheme.levelColor(ConsoleLevel.WARN), 70);
            }
            if (value.contains("state = using")) {
                return new ActiveState("using", ConsoleTheme.levelColor(ConsoleLevel.UPDATE), 70);
            }
            if (value.contains("state = waiting")) {
                return new ActiveState("waiting", ConsoleTheme.levelColor(ConsoleLevel.INFO), 65);
            }
            if (value.contains("state = thinking")) {
                return new ActiveState("thinking", ConsoleTheme.levelColor(ConsoleLevel.INFO), 60);
            }
            if (value.contains("state = idle")) {
                return new ActiveState("idle", ConsoleTheme.secondaryText(), 20);
            }
        }
        return new ActiveState("", ConsoleTheme.secondaryText(), 0);
    }

    private static ActiveState activeStateForNode(CanvasNode node, String text) {
        if ("[fail]".equals(node.marker()) || !node.details().failure().isBlank() && !isBlockedNode(node)) {
            return new ActiveState("failed", ConsoleTheme.levelColor(ConsoleLevel.ERROR), 90);
        }
        if (isBlockedNode(node)) {
            return new ActiveState("blocked", ConsoleTheme.levelColor(ConsoleLevel.WARN), 80);
        }
        if (node.firing() && containsAny(text, "move", "path", "walk", "follow", "target.distance")) {
            return new ActiveState("moving", ConsoleTheme.levelColor(ConsoleLevel.UPDATE), 70);
        }
        if (node.firing() && containsAny(text, "planner", "resolve", "template", "grammar", "lexicon", "semantic", "candidate", "cache")) {
            return new ActiveState("thinking", ConsoleTheme.levelColor(ConsoleLevel.INFO), 65);
        }
        if (node.firing() || "[run ]".equals(node.marker())) {
            return new ActiveState("running", ConsoleTheme.levelColor(ConsoleLevel.UPDATE), 60);
        }
        if (isDoneNode(node)) {
            return new ActiveState("complete", ConsoleTheme.levelColor(ConsoleLevel.DEBUG), 20);
        }
        return new ActiveState("idle", ConsoleTheme.secondaryText(), 0);
    }

    private static int activeStatePriority(String state) {
        return switch (safe(state)) {
            case "failed" -> 90;
            case "blocked" -> 80;
            case "moving" -> 70;
            case "thinking" -> 65;
            case "running" -> 60;
            case "complete" -> 20;
            default -> 0;
        };
    }

    private static boolean isProcessor(CanvasNode node) {
        String text = (safe(node.type()) + " " + safe(node.label()) + " " + safe(node.value())).toLowerCase(Locale.ROOT);
        return "[run ]".equals(node.marker())
                || text.contains("planner")
                || text.contains("resolve")
                || text.contains("scan")
                || text.contains("primitive")
                || text.contains("flow")
                || text.contains("runtime")
                || text.contains("eval.")
                || text.contains("cap.");
    }

    private static int tokenCount(String value) {
        String text = safe(value).trim();
        return text.isBlank() ? 0 : text.split("\\s+").length;
    }

    private static void drawBackground(DrawContext context, int x, int y, int width, int height, int scrollPixels) {
        int dot = withAlpha(ConsoleTheme.secondaryText(), 30);
        int grid = 18;
        for (int gridY = y - (scrollPixels % grid); gridY < y + height; gridY += grid) {
            for (int gridX = x + 8; gridX < x + width - 8; gridX += grid) {
                context.fill(gridX, gridY, gridX + 1, gridY + 1, dot);
            }
        }
        context.fill(x, y, x + width, y + 1, withAlpha(ConsoleTheme.secondaryText(), 76));
    }

    private static FrameBand hoveredFrame(CanvasLayout layout, int x, int y, int height, int scrollPixels, int mouseX, int mouseY) {
        if (mouseX < x + 8 || mouseX > x + layout.graphWidth() - 8 || mouseY < y || mouseY > y + height) {
            return null;
        }
        FrameBand hovered = null;
        List<FrameBand> ordered = new ArrayList<>(layout.frameBands());
        ordered.sort(Comparator.comparingInt(FrameBand::depth).thenComparingInt(FrameBand::yStart));
        for (FrameBand band : ordered) {
            int left = x + frameBandLeft(band);
            int right = x + layout.graphWidth() - 10;
            if (mouseX < left - 4 || mouseX > right + 4) {
                continue;
            }
            for (FrameSegment segment : visibleFrameSegments(band, ordered)) {
                int top = y + segment.yStart() - scrollPixels;
                int bottom = y + segment.yEnd() - scrollPixels;
                boolean nearLine = Math.abs(mouseY - top) <= 6 || Math.abs(mouseY - bottom) <= 6;
                boolean insideLane = mouseY >= top && mouseY <= bottom && mouseX <= left + 26;
                if (nearLine || insideLane) {
                    if (hovered == null || band.depth() >= hovered.depth()) {
                        hovered = band;
                    }
                }
            }
        }
        return hovered;
    }

    private static void drawFrameBands(DrawContext context, TextRenderer textRenderer, CanvasLayout layout, int x, int y, int height, int scrollPixels, FrameBand hoveredFrame) {
        List<FrameBand> ordered = new ArrayList<>(layout.frameBands());
        ordered.sort(Comparator.comparingInt(FrameBand::depth).thenComparingInt(FrameBand::yStart));
        List<FrameLabelSlot> labelSlots = new ArrayList<>();
        for (FrameBand band : ordered) {
            List<FrameSegment> segments = visibleFrameSegments(band, ordered);
            for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
                FrameSegment segment = segments.get(segmentIndex);
                drawFrameBandSegment(context, textRenderer, layout, band, segment, segmentIndex, x, y, height, scrollPixels, hoveredFrame, labelSlots);
            }
        }
    }

    private static void drawFrameBandSegment(DrawContext context, TextRenderer textRenderer, CanvasLayout layout, FrameBand band, FrameSegment segment, int segmentIndex, int x, int y, int height, int scrollPixels, FrameBand hoveredFrame, List<FrameLabelSlot> labelSlots) {
        int top = y + segment.yStart() - scrollPixels;
        int bottom = y + segment.yEnd() - scrollPixels;
        if (bottom < y || top > y + height) {
            return;
        }
        boolean hovered = hoveredFrame != null && hoveredFrame.id().equals(band.id());
        int accent = frameColor(band);
        int left = x + frameBandLeft(band);
        int right = x + layout.graphWidth() - 10;
        int labelX = left + 6;
        String label = segmentIndex == 0 ? "Frame: " + band.displayId() : "Frame: " + band.displayId() + " resumes";
        int labelWidth = textRenderer.getWidth(label) + 10;
        int alpha = hovered ? 118 : 58;
        int clippedTop = Math.max(y, top);
        int clippedBottom = Math.min(y + height, bottom);
        if (clippedBottom <= clippedTop) {
            return;
        }
        context.fill(left, clippedTop, right, clippedBottom, withAlpha(accent, hovered ? 20 : 10));
        drawFrameLine(context, left, right, top, labelX, labelWidth, withAlpha(accent, alpha));
        drawFrameLine(context, left, right, bottom, labelX, labelWidth, withAlpha(accent, alpha));
        int stickyTop = y + 34 + layout.headerHeight() + 4;
        int maxLabelY = Math.max(y + 2, y + height - 14);
        int minLabelY = Math.min(Math.max(y + 2, stickyTop), maxLabelY);
        int labelY = reserveFrameLabelY(clamp(top - 5, minLabelY, maxLabelY), minLabelY, maxLabelY, labelX - 3, labelX + labelWidth, labelSlots);
        context.fill(labelX - 3, labelY, labelX + labelWidth, labelY + 12, withAlpha(ConsoleTheme.background(), hovered ? 210 : 150));
        context.drawBorder(labelX - 3, labelY, labelWidth + 3, 12, withAlpha(accent, hovered ? 230 : 124));
        context.drawText(textRenderer, label, labelX + 2, labelY + 2, accent, false);
        String meta = band.nodeCount() + " nodes  in " + band.dataIn() + "  out " + band.dataOut();
        int metaWidth = right - labelX - labelWidth - 14;
        if (hovered && metaWidth > 52) {
            context.drawText(textRenderer, trim(textRenderer, meta, metaWidth), labelX + labelWidth + 7, labelY + 2, ConsoleTheme.secondaryText(), false);
        }
    }

    private static int reserveFrameLabelY(int desiredY, int minY, int maxY, int left, int right, List<FrameLabelSlot> labelSlots) {
        int labelHeight = 13;
        int gap = 2;
        int y = clamp(desiredY, minY, maxY);
        for (int attempts = 0; attempts < 64 && overlapsFrameLabel(y, labelHeight, left, right, labelSlots); attempts++) {
            y += labelHeight + gap;
            if (y > maxY) {
                y = minY;
            }
        }
        if (overlapsFrameLabel(y, labelHeight, left, right, labelSlots)) {
            for (int candidate = maxY; candidate >= minY; candidate -= labelHeight + gap) {
                if (!overlapsFrameLabel(candidate, labelHeight, left, right, labelSlots)) {
                    y = candidate;
                    break;
                }
            }
        }
        labelSlots.add(new FrameLabelSlot(left - 2, right + 2, y, y + labelHeight));
        return y;
    }

    private static boolean overlapsFrameLabel(int y, int height, int left, int right, List<FrameLabelSlot> labelSlots) {
        int top = y - 1;
        int bottom = y + height + 1;
        for (FrameLabelSlot slot : labelSlots) {
            boolean horizontal = right >= slot.left() && left <= slot.right();
            boolean vertical = bottom >= slot.top() && top <= slot.bottom();
            if (horizontal && vertical) {
                return true;
            }
        }
        return false;
    }

    private static int frameBandLeft(FrameBand band) {
        return 10 + Math.min(64, band.depth() * 12);
    }

    private static List<FrameSegment> visibleFrameSegments(FrameBand band, List<FrameBand> bands) {
        List<FrameSegment> segments = new ArrayList<>();
        int cursor = band.yStart();
        for (FrameBand child : bands) {
            if (child == band || child.depth() <= band.depth()) {
                continue;
            }
            if (child.yStart() <= band.yStart() || child.yEnd() >= band.yEnd()) {
                continue;
            }
            if (child.yEnd() <= cursor) {
                continue;
            }
            if (child.yStart() > cursor + 6) {
                segments.add(new FrameSegment(cursor, child.yStart() - 4));
            }
            cursor = Math.max(cursor, child.yEnd() + 4);
        }
        if (cursor < band.yEnd()) {
            segments.add(new FrameSegment(cursor, band.yEnd()));
        }
        if (segments.isEmpty()) {
            segments.add(new FrameSegment(band.yStart(), band.yEnd()));
        }
        return segments;
    }

    private static void drawFrameLine(DrawContext context, int left, int right, int y, int labelX, int labelWidth, int color) {
        if (right <= left) {
            return;
        }
        int segment = 6;
        for (int x = left; x < right; x += segment * 2) {
            int end = Math.min(right, x + segment);
            if (end < labelX || x > labelX + labelWidth) {
                context.fill(x, y, end, y + 1, color);
            }
        }
    }

    private static int frameColor(FrameBand band) {
        if (band.failed() > 0) {
            return ConsoleTheme.levelColor(ConsoleLevel.ERROR);
        }
        if (band.blocked() > 0) {
            return ConsoleTheme.levelColor(ConsoleLevel.WARN);
        }
        if (band.running() > 0 || band.firing() > 0) {
            return ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
        }
        return ConsoleTheme.levelColor(ConsoleLevel.INFO);
    }

    private static void drawHeader(DrawContext context, TextRenderer textRenderer, AutomationCliSnapshot snapshot, CanvasLayout layout, int x, int y, int width) {
        context.fill(x + 4, y + 4, x + layout.graphWidth() - 4, y + 26, withAlpha(ConsoleTheme.background(), 138));
        context.drawBorder(x + 4, y + 4, layout.graphWidth() - 8, 22, withAlpha(ConsoleTheme.secondaryText(), 80));
        context.drawText(textRenderer, "automation neural trace", x + 10, y + 10, ConsoleTheme.primaryText(), false);
        String meta = safe(snapshot.mode()) + " / " + safe(snapshot.actor()) + " / " + layout.nodes().size() + " nodes";
        context.drawText(textRenderer, trim(textRenderer, meta, Math.max(40, layout.graphWidth() - 180)), x + 160, y + 10, ConsoleTheme.secondaryText(), false);
        drawSessionBlock(context, textRenderer, layout, x + 10, y + 34);
        if (layout.eventWidth() > 0) {
            context.fill(x + layout.streamX(), y + 4, x + layout.streamX() + layout.eventWidth(), y + 26, withAlpha(ConsoleTheme.background(), 150));
            context.drawBorder(x + layout.streamX(), y + 4, layout.eventWidth(), 22, withAlpha(ConsoleTheme.secondaryText(), 80));
            context.drawText(textRenderer, "brain state", x + layout.streamX() + 7, y + 10, ConsoleTheme.secondaryText(), false);
        }
    }

    private static void drawSessionBlock(DrawContext context, TextRenderer textRenderer, CanvasLayout layout, int x, int y) {
        if (layout.headerRows().isEmpty()) {
            return;
        }
        int width = Math.min(360, Math.max(220, layout.graphWidth() - 28));
        int height = layout.headerHeight();
        int accent = ConsoleTheme.levelColor(ConsoleLevel.INFO);
        context.fill(x, y, x + width, y + height, withAlpha(ConsoleTheme.primaryText(), 48));
        context.fill(x + 1, y + 1, x + width - 1, y + height - 1, withAlpha(ConsoleTheme.background(), 132));
        context.drawBorder(x, y, width, height, withAlpha(ConsoleTheme.primaryText(), 128));
        context.fill(x + 4, y + 4, x + 7, y + height - 4, withAlpha(accent, 188));
        String mode = headerValue(layout.headerRows(), "mode");
        String actor = headerValue(layout.headerRows(), "actor");
        String session = headerValue(layout.headerRows(), "session.id");
        String detail = headerValue(layout.headerRows(), "detail");
        String line = "mode " + emptyAs(mode, "-") + " | actor " + emptyAs(actor, "-") + " | session " + emptyAs(session, "-") + " | " + emptyAs(detail, "trace");
        context.drawText(textRenderer, trim(textRenderer, line, width - 18), x + 12, y + 5, ConsoleTheme.secondaryText(), false);
    }

    private static String headerValue(List<AutomationCliRow> rows, String label) {
        for (AutomationCliRow row : rows) {
            if (label.equals(safe(row.label()))) {
                return safe(row.value());
            }
        }
        return "";
    }

    private static HoverCue drawEventStream(DrawContext context, TextRenderer textRenderer, CanvasLayout layout, int x, int y, int height, int scrollPixels, int mouseX, int mouseY) {
        if (layout.eventWidth() <= 0) {
            return null;
        }
        int streamX = x + layout.streamX();
        int streamTop = y + 30;
        context.fill(streamX, streamTop, streamX + layout.eventWidth(), y + height - 6, withAlpha(ConsoleTheme.background(), 120));
        context.drawBorder(streamX, streamTop, layout.eventWidth(), height - 36, withAlpha(ConsoleTheme.secondaryText(), 72));
        HoverCue brainCue = drawBrainStats(context, textRenderer, layout.stats(), streamX, streamTop, layout.eventWidth(), mouseX, mouseY);
        int traceTop = streamTop + 138;
        context.drawText(textRenderer, "console trace", streamX + 7, traceTop - 4, ConsoleTheme.secondaryText(), false);
        int clipTop = traceTop + 9;
        int clipBottom = y + height - 6;
        HoverCue traceCue = null;
        context.enableScissor(streamX + 1, clipTop, streamX + layout.eventWidth() - 1, clipBottom);
        for (CanvasNode node : layout.nodes()) {
            int lineY = y + node.y() - scrollPixels;
            if (lineY < clipTop - DETAIL_LINE_HEIGHT || lineY > clipBottom) {
                continue;
            }
            if (traceCue == null && containsPoint(mouseX, mouseY, streamX + 5, lineY - 1, streamX + layout.eventWidth() - 5, lineY + DETAIL_LINE_HEIGHT + 10)) {
                traceCue = sidebarNodeCue(node);
            }
            int color = colorFor(node);
            context.fill(streamX + 5, lineY + 3, streamX + 8, lineY + 10, withAlpha(color, 230));
            String line = "#" + (node.sequence() + 1) + " " + node.label();
            context.drawText(textRenderer, trim(textRenderer, line, layout.eventWidth() - 18), streamX + 12, lineY, ConsoleTheme.primaryText(), false);
            String detail = traceDetail(node.details());
            String secondary = detail.isBlank() ? node.value() : detail;
            if (!secondary.isBlank()) {
                context.drawText(textRenderer, trim(textRenderer, secondary, layout.eventWidth() - 18), streamX + 12, lineY + 10, detail.isBlank() ? valueColor(node) : detailColor(node.details()), false);
            }
        }
        context.disableScissor();
        return traceCue != null ? traceCue : brainCue;
    }

    private static HoverCue sidebarNodeCue(CanvasNode node) {
        List<String> details = new ArrayList<>();
        details.add("means: compact side trace for graph node #" + (node.sequence() + 1));
        details.add("state: " + node.marker() + " / " + node.type());
        details.add("why value exists: " + sidebarWhy(node));
        if (!node.details().search().isBlank()) {
            details.add("search: " + node.details().search());
        }
        if (!node.details().requires().isBlank()) {
            details.add("requires: " + node.details().requires());
        }
        if (!node.details().received().isBlank()) {
            details.add("received: " + node.details().received());
        }
        if (!node.details().output().isBlank()) {
            details.add("output: " + node.details().output());
        }
        if (!node.details().source().isBlank()) {
            details.add("source: " + node.details().source());
        }
        return new HoverCue(node, "side trace value", colorFor(node), details);
    }

    private static String sidebarWhy(CanvasNode node) {
        String type = safe(node.type()).toLowerCase(Locale.ROOT);
        String section = safe(node.sectionId()).toLowerCase(Locale.ROOT);
        if (type.contains("planner") || section.equals("pipeline")) {
            return "planner/compiler emitted this row while building the execution graph";
        }
        if (type.contains("primitive")) {
            return "runtime called a Java capability and exposed params/result data";
        }
        if (type.contains("delegate") || type.contains("frame_pause")) {
            return "parent task delegated to a child KTL graph and paused";
        }
        if (type.contains("return") || type.contains("frame_resume")) {
            return "child task returned state back to its parent frame";
        }
        if (section.equals("world")) {
            return "world probe/path/target state changed";
        }
        if (section.equals("state") || section.equals("summary")) {
            return "runtime memory or final frame state was updated";
        }
        return "view-model received a typed automation event for this node";
    }

    private static HoverCue drawBrainStats(DrawContext context, TextRenderer textRenderer, BrainStats stats, int x, int y, int width, int mouseX, int mouseY) {
        int cardY = y + 5;
        context.fill(x + 5, cardY, x + width - 5, cardY + 124, withAlpha(ConsoleTheme.background(), 150));
        context.drawBorder(x + 5, cardY, width - 10, 124, withAlpha(stats.activeStateColor(), 92));
        String top = "nodes " + stats.nodes() + "  firing " + stats.firing() + "  running " + stats.running();
        String mid = "done " + stats.done() + "  blocked " + stats.blocked() + "  failed " + stats.failed();
        String active = "active: " + stats.activeState();
        String groups = "pipe " + stats.pipeline() + "  exec " + stats.execution() + "  world " + stats.world() + "  mem " + stats.memory();
        String engine = "ktl " + stats.ktl() + "  runtime " + stats.runtime();
        String process = "tokens " + stats.tokens() + "  processors " + stats.processors();
        String transfer = "data in " + stats.dataIn() + "  data out " + stats.dataOut();
        String signal = "strong: " + stats.strongestLabel();
        String current = "state: " + stats.currentSignal();
        drawActiveStateBadge(context, x + 11, cardY + 31, stats.activeStateColor(), stats.activeState());
        context.drawText(textRenderer, trim(textRenderer, top, width - 18), x + 10, cardY + 6, ConsoleTheme.primaryText(), false);
        context.drawText(textRenderer, trim(textRenderer, mid, width - 18), x + 10, cardY + 19, stats.failed() > 0 ? ConsoleTheme.levelColor(ConsoleLevel.ERROR) : stats.blocked() > 0 ? ConsoleTheme.levelColor(ConsoleLevel.WARN) : ConsoleTheme.levelColor(ConsoleLevel.DEBUG), false);
        context.drawText(textRenderer, trim(textRenderer, active, width - 34), x + 29, cardY + 32, stats.activeStateColor(), false);
        context.drawText(textRenderer, trim(textRenderer, groups, width - 18), x + 10, cardY + 45, ConsoleTheme.levelColor(ConsoleLevel.INFO), false);
        context.drawText(textRenderer, trim(textRenderer, engine, width - 18), x + 10, cardY + 58, ConsoleTheme.levelColor(ConsoleLevel.UPDATE), false);
        context.drawText(textRenderer, trim(textRenderer, process, width - 18), x + 10, cardY + 71, ConsoleTheme.levelColor(ConsoleLevel.WARN), false);
        context.drawText(textRenderer, trim(textRenderer, transfer, width - 18), x + 10, cardY + 84, ConsoleTheme.levelColor(ConsoleLevel.DEBUG), false);
        context.drawText(textRenderer, trim(textRenderer, signal, width - 18), x + 10, cardY + 97, ConsoleTheme.secondaryText(), false);
        context.drawText(textRenderer, trim(textRenderer, current, width - 18), x + 10, cardY + 109, ConsoleTheme.primaryText(), false);
        if (containsPoint(mouseX, mouseY, x + 8, cardY + 56, x + width - 10, cardY + 70)) {
            return new HoverCue(null, "ktl/runtime counters", ConsoleTheme.levelColor(ConsoleLevel.UPDATE), ktlSidebarDetails(stats));
        }
        if (containsPoint(mouseX, mouseY, x + 8, cardY + 28, x + width - 10, cardY + 43)) {
            return new HoverCue(null, "active-state badge", stats.activeStateColor(), List.of(
                    "means: compact runtime state for the current automation session",
                    "state: " + stats.activeState(),
                    "signal: " + emptyAs(stats.currentSignal(), "no active signal"),
                    "visual: moving packet means active work; solid rail means terminal or idle state"
            ));
        }
        return null;
    }

    private static List<String> ktlSidebarDetails(BrainStats stats) {
        List<String> details = new ArrayList<>();
        details.add("means: KTL rows are file-driven compiler/template signals; runtime rows are Java execution signals");
        details.add("ktl count: " + stats.ktl());
        details.add("runtime count: " + stats.runtime());
        details.add("files: " + ktlFileSummary());
        return details;
    }

    private static String ktlFileSummary() {
        Path root = Path.of("koil/automation");
        if (!Files.isDirectory(root)) {
            return "koil/automation missing";
        }
        try (var stream = Files.walk(root)) {
            List<String> files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".ktl"))
                    .map(path -> root.relativize(path).toString())
                    .sorted()
                    .limit(8)
                    .toList();
            return files.isEmpty() ? "(none)" : String.join(", ", files) + (files.size() >= 8 ? ", ..." : "");
        } catch (Exception ignored) {
            return "unable to scan koil/automation";
        }
    }

    private static void drawActiveStateBadge(DrawContext context, int x, int y, int color, String state) {
        int alpha = "idle".equals(state) || "complete".equals(state) ? 130 : 214;
        context.fill(x, y + 1, x + 13, y + 8, withAlpha(color, 62));
        context.fill(x + 1, y + 2, x + 4, y + 7, withAlpha(color, alpha));
        context.fill(x + 5, y + 4, x + 12, y + 5, withAlpha(color, alpha));
        if ("thinking".equals(state) || "running".equals(state) || "moving".equals(state)) {
            int phase = (int) ((System.currentTimeMillis() / 180L) % 7L);
            context.fill(x + 5 + phase, y + 3, x + 7 + phase, y + 6, color);
        }
        if ("failed".equals(state) || "blocked".equals(state)) {
            context.fill(x + 9, y + 1, x + 12, y + 8, withAlpha(color, 190));
        }
    }

    private static HoverLink drawAssociativeLinks(DrawContext context, CanvasLayout layout, int x, int y, int height, int scrollPixels, int mouseX, int mouseY) {
        HoverLink hovered = null;
        for (CanvasNode node : layout.nodes()) {
            HoverLink section = drawAssociation(context, node.sectionSource(), node, x, y, height, scrollPixels, 42, "section mesh link", mouseX, mouseY);
            if (section != null) {
                hovered = section;
            }
            HoverLink type = drawAssociation(context, node.typeSource(), node, x, y, height, scrollPixels, 26, "type mesh link", mouseX, mouseY);
            if (type != null) {
                hovered = type;
            }
        }
        return hovered;
    }

    private static HoverLink drawReturnLinks(DrawContext context, CanvasLayout layout, int x, int y, int height, int scrollPixels, int mouseX, int mouseY) {
        HoverLink hovered = null;
        for (CanvasNode node : layout.nodes()) {
            if (!isReturnNode(node)) {
                continue;
            }
            String parentFrameId = returnParentFrame(node);
            if (parentFrameId.isBlank()) {
                parentFrameId = node.parentFrameId();
            }
            CanvasNode target = latestNodeInFrameBefore(layout.nodes(), parentFrameId, node.sequence());
            if (target == null || target == node) {
                continue;
            }
            int sourceX = x + node.x() + node.width() / 2;
            int sourceY = y + node.y() - scrollPixels + Math.min(node.height(), 22);
            int targetX = x + target.x() + target.width() / 2;
            int targetY = y + target.y() - scrollPixels + Math.min(target.height(), 22);
            if (!connectorVisible(sourceY, targetY, y, height)) {
                continue;
            }
            int laneY = sourceY + Math.max(10, Math.abs(targetY - sourceY) / 3);
            int color = withAlpha(ConsoleTheme.levelColor(ConsoleLevel.DEBUG), 172);
            drawPatternSegment(context, sourceX, sourceY, sourceX, laneY, color, 1, "return", node.sequence());
            drawPatternSegment(context, sourceX, laneY, targetX, laneY, color, 1, "return", node.sequence() + 1);
            drawPatternSegment(context, targetX, laneY, targetX, targetY, color, 1, "return", node.sequence() + 2);
            boolean over = nearSegment(mouseX, mouseY, sourceX, sourceY, sourceX, laneY, 3)
                    || nearSegment(mouseX, mouseY, sourceX, laneY, targetX, laneY, 3)
                    || nearSegment(mouseX, mouseY, targetX, laneY, targetX, targetY, 3);
            if (over) {
                int strong = ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
                drawConnectorEndpointFocus(context, sourceX, sourceY, targetX, targetY, strong);
                hovered = new HoverLink(node, target, "return reconnect link", 0, "return flow", "caller reconnect", strong, "child frame output returns to " + emptyAs(parentFrameId, "parent frame"));
            }
        }
        return hovered;
    }

    private static CanvasNode latestNodeInFrameBefore(List<CanvasNode> nodes, String frameId, int sequence) {
        if (safe(frameId).isBlank()) {
            return null;
        }
        CanvasNode result = null;
        for (CanvasNode node : nodes) {
            if (node.sequence() >= sequence) {
                break;
            }
            if (frameId.equals(node.frameId())) {
                result = node;
            }
        }
        return result;
    }

    private static HoverLink drawAssociation(DrawContext context, CanvasNode source, CanvasNode node, int x, int y, int height, int scrollPixels, int alpha, String linkKind, int mouseX, int mouseY) {
        if (source == null || source == node || source == node.previous()) {
            return null;
        }
        int sourceY = y + source.y() - scrollPixels;
        int nodeY = y + node.y() - scrollPixels;
        if (!connectorVisible(sourceY, nodeY, y, height)) {
            return null;
        }
        int fromX = x + source.x() + source.width() / 2;
        int fromY = sourceY + Math.min(source.height(), 22);
        int toX = x + node.x() + node.width() / 2;
        int toY = nodeY + Math.min(node.height(), 22);
        int midX = fromX + (toX - fromX) / 2;
        boolean hovered = nearSegment(mouseX, mouseY, fromX, fromY, midX, fromY, 7)
                || nearSegment(mouseX, mouseY, midX, fromY, midX, toY, 7)
                || nearSegment(mouseX, mouseY, midX, toY, toX, toY, 7);
        int color = withAlpha(colorFor(node), hovered ? 230 : alpha);
        int thickness = hovered ? 2 : 1;
        context.fill(Math.min(fromX, midX), fromY, Math.max(fromX, midX) + 1, fromY + thickness, color);
        context.fill(midX, Math.min(fromY, toY), midX + thickness, Math.max(fromY, toY) + 1, color);
        context.fill(Math.min(midX, toX), toY, Math.max(midX, toX) + 1, toY + thickness, color);
        if (hovered) {
            context.fill(fromX - 2, fromY - 2, fromX + 3, fromY + 3, color);
            context.fill(toX - 2, toY - 2, toX + 3, toY + 3, color);
            return new HoverLink(source, node, linkKind, -1, "mesh", "mesh relation", color, "section/type relation");
        }
        return null;
    }

    private static boolean connectorVisible(int previousY, int nodeY, int viewportY, int viewportHeight) {
        int min = Math.min(previousY, nodeY);
        int max = Math.max(previousY + MAX_NODE_HEIGHT, nodeY + MAX_NODE_HEIGHT);
        return max >= viewportY && min <= viewportY + viewportHeight;
    }

    private static void drawConnector(DrawContext context, int previousX, int previousY, int nodeX, int nodeY, CanvasNode node, boolean activeTransfer, HoverLink hoveredLink, boolean selectedLink) {
        boolean transferring = activeTransfer && (node.firing() || node.previous().firing() || hasDataTransfer(node));
        int fromX = previousX + node.previous().width() / 2;
        int fromY = previousY + node.previous().height();
        int toX = nodeX + node.width() / 2;
        int toY = nodeY;
        int midY = fromY + Math.max(6, (toY - fromY) / 2);
        int arrowTop = Math.max(midY + 2, toY - 7);
        List<LinkLane> lanes = linkLanes(node);
        int laneCount = lanes.size();
        for (int lane = 0; lane < laneCount; lane++) {
            LinkLane descriptor = lanes.get(lane);
            int offset = laneOffset(laneCount, lane);
            boolean laneHovered = hoveredLink != null && hoveredLink.lane() == lane;
            boolean emphasized = laneHovered || selectedLink;
            boolean laneActive = activeTransfer && descriptor.active();
            int thickness = emphasized || laneActive || node.weight() >= 6 ? 2 : 1;
            int laneColor = withAlpha(descriptor.color(), emphasized ? 244 : laneActive ? 214 : transferring ? 146 : 88);
            drawConnectorPath(context, fromX + offset, fromY, toX + offset, midY, arrowTop, laneColor, thickness, descriptor.pattern(), node.sequence() + lane);
            if (laneActive) {
                drawActivationPacket(context, fromX + offset, fromY, toX + offset, arrowTop, laneColor, node.sequence() + lane * 3);
            }
            if (laneHovered) {
                drawConnectorEndpointFocus(context, fromX + offset, fromY, toX + offset, arrowTop, laneColor);
            }
        }
        if (selectedLink) {
            context.fill(Math.min(fromX, toX) - 2, midY - 2, Math.max(fromX, toX) + 3, midY + 3, withAlpha(edgeColorFor(node), 42));
        }
    }

    private static void drawConnectorPath(DrawContext context, int fromX, int fromY, int toX, int midY, int arrowTop, int color, int thickness, String pattern, int sequence) {
        drawPatternSegment(context, fromX, fromY, fromX, midY, color, thickness, pattern, sequence);
        drawPatternSegment(context, fromX, midY, toX, midY, color, thickness, pattern, sequence + 1);
        drawPatternSegment(context, toX, midY, toX, arrowTop, color, thickness, pattern, sequence + 2);
        context.fill(toX - 2, arrowTop, toX + 3, arrowTop + 2, color);
        context.fill(toX - 1, arrowTop + 2, toX + 2, arrowTop + 6, color);
    }

    private static void drawPatternSegment(DrawContext context, int x1, int y1, int x2, int y2, int color, int thickness, String pattern, int sequence) {
        if ("solid".equals(pattern)) {
            drawSegment(context, x1, y1, x2, y2, color, thickness);
            return;
        }
        int step = switch (pattern) {
            case "dashed" -> 8;
            case "return" -> 6;
            case "memory" -> 5;
            case "scan" -> 7;
            case "primitive" -> 4;
            case "complete" -> 5;
            default -> 4;
        };
        int dash = switch (pattern) {
            case "dashed" -> 5;
            case "return" -> 3;
            case "memory" -> 2;
            case "scan" -> 2;
            case "primitive" -> 3;
            case "complete" -> 4;
            default -> 3;
        };
        int phase = Math.floorMod(sequence * 3, step);
        if (x1 == x2) {
            int start = Math.min(y1, y2);
            int end = Math.max(y1, y2);
            for (int y = start + phase; y <= end; y += step) {
                context.fill(x1, y, x1 + thickness, Math.min(end, y + dash) + 1, color);
            }
            return;
        }
        if (y1 == y2) {
            int start = Math.min(x1, x2);
            int end = Math.max(x1, x2);
            for (int x = start + phase; x <= end; x += step) {
                context.fill(x, y1, Math.min(end, x + dash) + 1, y1 + thickness, color);
            }
        }
    }

    private static void drawConnectorEndpointFocus(DrawContext context, int fromX, int fromY, int toX, int arrowTop, int color) {
        context.fill(fromX - 3, fromY - 3, fromX + 4, fromY + 4, withAlpha(color, 110));
        context.fill(toX - 3, arrowTop - 3, toX + 4, arrowTop + 4, withAlpha(color, 130));
        context.drawBorder(fromX - 4, fromY - 4, 9, 9, color);
        context.drawBorder(toX - 4, arrowTop - 4, 9, 9, color);
    }

    private static HoverLink hoveredConnector(int previousX, int previousY, int nodeX, int nodeY, CanvasNode node, int mouseX, int mouseY) {
        int fromX = previousX + node.previous().width() / 2;
        int fromY = previousY + node.previous().height();
        int toX = nodeX + node.width() / 2;
        int toY = nodeY;
        int midY = fromY + Math.max(6, (toY - fromY) / 2);
        int arrowTop = Math.max(midY + 2, toY - 5);
        List<LinkLane> lanes = linkLanes(node);
        for (int lane = 0; lane < lanes.size(); lane++) {
            LinkLane descriptor = lanes.get(lane);
            int offset = laneOffset(lanes.size(), lane);
            int laneFromX = fromX + offset;
            int laneToX = toX + offset;
            if (containsPoint(mouseX, mouseY, laneFromX - 4, fromY - 4, laneFromX + 4, fromY + 4)) {
                return new HoverLink(node.previous(), node, "execution data link", lane, descriptor.role(), "source port", descriptor.color(), descriptor.payload());
            }
            if (containsPoint(mouseX, mouseY, laneToX - 5, arrowTop - 4, laneToX + 5, toY + 3)) {
                return new HoverLink(node.previous(), node, "execution data link", lane, descriptor.role(), "target arrow", descriptor.color(), descriptor.payload());
            }
            if (nearSegment(mouseX, mouseY, laneFromX, fromY, laneFromX, midY, 3)) {
                return new HoverLink(node.previous(), node, "execution data link", lane, descriptor.role(), "source descent", descriptor.color(), descriptor.payload());
            }
            if (nearSegment(mouseX, mouseY, laneFromX, midY, laneToX, midY, 3)) {
                return new HoverLink(node.previous(), node, "execution data link", lane, descriptor.role(), "transfer span", descriptor.color(), descriptor.payload());
            }
            if (nearSegment(mouseX, mouseY, laneToX, midY, laneToX, arrowTop, 3)) {
                return new HoverLink(node.previous(), node, "execution data link", lane, descriptor.role(), "target ingress", descriptor.color(), descriptor.payload());
            }
        }
        return null;
    }

    private static boolean nearSegment(int mouseX, int mouseY, int x1, int y1, int x2, int y2) {
        return nearSegment(mouseX, mouseY, x1, y1, x2, y2, 5);
    }

    private static boolean nearSegment(int mouseX, int mouseY, int x1, int y1, int x2, int y2, int tolerance) {
        int minX = Math.min(x1, x2) - tolerance;
        int maxX = Math.max(x1, x2) + tolerance;
        int minY = Math.min(y1, y2) - tolerance;
        int maxY = Math.max(y1, y2) + tolerance;
        if (mouseX < minX || mouseX > maxX || mouseY < minY || mouseY > maxY) {
            return false;
        }
        if (x1 == x2) {
            return Math.abs(mouseX - x1) <= tolerance;
        }
        if (y1 == y2) {
            return Math.abs(mouseY - y1) <= tolerance;
        }
        return false;
    }

    private static boolean hasDataTransfer(CanvasNode node) {
        return !node.details().received().isBlank()
                || !node.details().requires().isBlank()
                || !node.details().search().isBlank()
                || !node.previous().details().output().isBlank()
                || isMemoryNode(node)
                || isReturnNode(node)
                || isDoneNode(node)
                || safe(node.type()).toLowerCase(Locale.ROOT).contains("primitive");
    }

    private static List<LinkLane> linkLanes(CanvasNode node) {
        List<LinkLane> lanes = new ArrayList<>();
        lanes.add(new LinkLane("control", edgeColorFor(node), "solid", "execution order from #" + (node.previous().sequence() + 1) + " to #" + (node.sequence() + 1), node.firing() || node.previous().firing()));
        if (!node.details().search().isBlank()) {
            lanes.add(new LinkLane("search/resolve", searchCueColor(), "scan", node.details().search(), node.firing()));
        }
        if (!node.details().requires().isBlank()) {
            lanes.add(new LinkLane("required input", inputCueColor(), "dashed", node.details().requires(), node.firing()));
        }
        if (!node.previous().details().output().isBlank()) {
            lanes.add(new LinkLane("produced output", outputCueColor(), "pulse", node.previous().details().output(), node.previous().firing()));
        }
        if (!node.details().received().isBlank()) {
            lanes.add(new LinkLane("received data", runtimeCueColor(), "pulse", node.details().received(), node.firing()));
        }
        if (isMemoryNode(node) || isMemoryNode(node.previous())) {
            lanes.add(new LinkLane("memory/state", memoryCueColor(), "memory", firstNonBlank(node.details().received(), node.details().output(), node.previous().details().output(), node.value()), node.firing() || node.previous().firing()));
        }
        if (safe(node.type()).toLowerCase(Locale.ROOT).contains("primitive")) {
            lanes.add(new LinkLane("primitive call", runtimeCueColor(), "primitive", firstNonBlank(node.details().requires(), node.details().received(), node.details().output(), node.value()), node.firing()));
        }
        if (isReturnNode(node)) {
            lanes.add(new LinkLane("return flow", ConsoleTheme.levelColor(ConsoleLevel.DEBUG), "return", firstNonBlank(node.details().output(), node.value(), "control returns to caller"), node.firing()));
        }
        if (isDoneNode(node)) {
            lanes.add(new LinkLane("completion", ConsoleTheme.levelColor(ConsoleLevel.DEBUG), "complete", firstNonBlank(node.details().output(), node.value(), "node completed"), node.firing()));
        }
        return lanes.size() > 7 ? lanes.subList(0, 7) : lanes;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!safe(value).isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static int laneOffset(int lanes, int lane) {
        return lanes == 1 ? 0 : (int) Math.round((lane - (lanes - 1) / 2.0) * 4.0);
    }

    private static void drawActivationPacket(DrawContext context, int fromX, int fromY, int toX, int toY, int color, int sequence) {
        int phase = (int) ((System.currentTimeMillis() / 90L + sequence * 3L) % 18L);
        double t = phase / 17.0;
        int midY = fromY + Math.max(6, (toY - fromY) / 2);
        int firstLength = Math.max(1, Math.abs(midY - fromY));
        int secondLength = Math.max(1, Math.abs(toX - fromX));
        int thirdLength = Math.max(1, Math.abs(toY - midY));
        int total = firstLength + secondLength + thirdLength;
        int distance = (int) Math.round(t * total);
        int packetX;
        int packetY;
        if (distance <= firstLength) {
            double local = distance / (double) firstLength;
            packetX = fromX;
            packetY = (int) Math.round(fromY + (midY - fromY) * local);
        } else if (distance <= firstLength + secondLength) {
            double local = (distance - firstLength) / (double) secondLength;
            packetX = (int) Math.round(fromX + (toX - fromX) * local);
            packetY = midY;
        } else {
            double local = (distance - firstLength - secondLength) / (double) thirdLength;
            packetX = toX;
            packetY = (int) Math.round(midY + (toY - midY) * local);
        }
        context.fill(packetX - 3, packetY, packetX + 4, packetY + 1, withAlpha(color, 150));
        context.fill(packetX, packetY - 3, packetX + 1, packetY + 4, withAlpha(color, 150));
        context.fill(packetX - 2, packetY - 2, packetX + 3, packetY + 3, withAlpha(color, 232));
        context.fill(packetX - 1, packetY - 1, packetX + 2, packetY + 2, ConsoleTheme.primaryText());
        context.fill(packetX, packetY, packetX + 1, packetY + 1, color);
    }

    private static void drawNeuronNode(DrawContext context, TextRenderer textRenderer, int x, int y, CanvasNode node, boolean hovered, boolean selected) {
        int accent = colorFor(node);
        int radius = clamp(4 + node.weight(), 6, 13);
        int centerX = x + node.width() / 2;
        int centerY = y + Math.min(node.height() / 2, 15);
        if (selected) {
            drawSquareDot(context, centerX, centerY, radius + 9, withAlpha(ConsoleTheme.primaryText(), 82));
            context.drawBorder(centerX - radius - 12, centerY - radius - 12, (radius + 12) * 2 + 1, (radius + 12) * 2 + 1, withAlpha(accent, 230));
        }
        drawSquareDot(context, centerX, centerY, radius + 5, withAlpha(accent, selected ? 92 : hovered ? 62 : node.weight() >= 6 ? 42 : 24));
        if (isChildPromptNode(node)) {
            drawChildPromptShell(context, centerX, centerY, radius, node, accent, hovered || selected);
        }
        drawSquareDot(context, centerX, centerY, radius + 2, withAlpha(ConsoleTheme.background(), 220));
        drawSquareDot(context, centerX, centerY, radius, withAlpha(accent, 226));
        drawNodeCorePattern(context, centerX, centerY, radius, node, accent);
        if (isFailedNode(node)) {
            drawErrorEffect(context, centerX, centerY, radius, accent, node.sequence(), node.firing());
        } else if (isBlockedNode(node)) {
            drawBlockedEffect(context, centerX, centerY, radius, accent, node.sequence(), node.firing());
        }
        drawNodeCues(context, node, centerX, centerY, radius, accent);
        drawNodeRoleGlyph(context, node, centerX, centerY, radius, accent, hovered || selected);
        int cardX = centerX + radius + 12;
        int cardY = y + 2;
        int labelWidth = Math.min(132, Math.max(58, textRenderer.getWidth(node.label()) + 10));
        context.fill(cardX, cardY, cardX + labelWidth, cardY + 13, withAlpha(ConsoleTheme.background(), selected ? 238 : hovered ? 220 : 144));
        context.drawBorder(cardX, cardY, labelWidth, 13, withAlpha(accent, selected ? 255 : hovered ? 230 : 116));
        context.drawText(textRenderer, trim(textRenderer, node.label(), labelWidth - 7), cardX + 4, cardY + 3, ConsoleTheme.primaryText(), false);
        if (hovered || node.weight() >= 6) {
            String meta = typeLabel(node) + " " + safe(node.marker());
            context.drawText(textRenderer, trim(textRenderer, meta, labelWidth), cardX + 1, cardY + 15, accent, false);
        }
    }

    private static void drawSquareDot(DrawContext context, int centerX, int centerY, int radius, int color) {
        context.fill(centerX - radius, centerY - radius, centerX + radius + 1, centerY + radius + 1, color);
    }

    private static void drawChildPromptShell(DrawContext context, int centerX, int centerY, int radius, CanvasNode node, int accent, boolean emphasized) {
        int width = Math.max(42, radius * 4 + 18);
        int height = Math.max(22, radius * 2 + 12);
        int left = centerX - width / 2;
        int top = centerY - height / 2;
        context.fill(left, top, left + width, top + height, withAlpha(ConsoleTheme.background(), emphasized ? 210 : 154));
        context.drawBorder(left, top, width, height, withAlpha(accent, emphasized ? 238 : 150));
        context.fill(left + 4, top + 4, left + width - 4, top + 6, withAlpha(ConsoleTheme.levelColor(ConsoleLevel.INFO), 190));
        context.fill(left + 4, top + height - 6, left + width - 4, top + height - 4, withAlpha(ConsoleTheme.levelColor(ConsoleLevel.UPDATE), 150));
    }

    private static void drawNodeCorePattern(DrawContext context, int centerX, int centerY, int radius, CanvasNode node, int accent) {
        int alpha = node.firing() ? 226 : node.weight() >= 6 ? 176 : 124;
        int color = withAlpha(ConsoleTheme.primaryText(), alpha);
        int span = Math.max(4, radius - 3);
        String type = safe(node.type()).toLowerCase(Locale.ROOT);
        if (type.contains("return") || type.contains("done") || isDoneNode(node)) {
            int green = withAlpha(ConsoleTheme.levelColor(ConsoleLevel.DEBUG), node.firing() ? 236 : 196);
            context.fill(centerX - span, centerY + span - 2, centerX + span + 1, centerY + span, green);
            context.fill(centerX + span - 2, centerY - span, centerX + span, centerY + span, withAlpha(green, 210));
            return;
        }
        if (type.contains("branch") || type.contains("condition") || type.contains("candidate")) {
            context.fill(centerX - span, centerY - 1, centerX + span + 1, centerY + 1, color);
            context.fill(centerX - 1, centerY - span, centerX + 1, centerY + span + 1, withAlpha(accent, alpha));
            return;
        }
        if (type.contains("primitive") || type.contains("cap.")) {
            context.fill(centerX - span, centerY - span + 2, centerX + span + 1, centerY - span + 4, color);
            context.fill(centerX - span, centerY + span - 4, centerX + span + 1, centerY + span - 2, withAlpha(accent, alpha));
            return;
        }
        context.fill(centerX - span, centerY, centerX + span + 1, centerY + 1, color);
        context.fill(centerX, centerY - span, centerX + 1, centerY + span + 1, withAlpha(accent, alpha));
    }

    private static void drawErrorEffect(DrawContext context, int centerX, int centerY, int radius, int accent, int sequence, boolean active) {
        int phase = active ? (int) ((System.currentTimeMillis() / 90L + sequence) % 5L) : 1;
        int size = radius + 9 + phase;
        int color = withAlpha(ConsoleTheme.levelColor(ConsoleLevel.ERROR), active ? 230 : 184);
        context.drawBorder(centerX - size, centerY - size, size * 2 + 1, size * 2 + 1, color);
        context.fill(centerX - size, centerY - size, centerX - size + 2, centerY - size + 5, color);
        context.fill(centerX + size - 1, centerY + size - 4, centerX + size + 1, centerY + size + 1, color);
        context.fill(centerX - radius - 2, centerY, centerX + radius + 3, centerY + 1, withAlpha(accent, 150));
        if (active) {
            context.drawBorder(centerX - size - 3, centerY - size - 3, (size + 3) * 2 + 1, (size + 3) * 2 + 1, withAlpha(ConsoleTheme.levelColor(ConsoleLevel.ERROR), 112));
        }
    }

    private static void drawBlockedEffect(DrawContext context, int centerX, int centerY, int radius, int accent, int sequence, boolean active) {
        int phase = active ? (int) ((System.currentTimeMillis() / 160L + sequence) % 4L) : 0;
        int color = withAlpha(ConsoleTheme.levelColor(ConsoleLevel.WARN), active ? 214 : 150);
        int size = radius + 8;
        context.fill(centerX - size, centerY - size, centerX + size + 1, centerY - size + 2, color);
        context.fill(centerX - size, centerY + size - 1, centerX + size + 1, centerY + size + 1, color);
        context.fill(centerX - size + phase, centerY - 1, centerX - size + phase + 4, centerY + 2, color);
        context.fill(centerX + size - phase - 3, centerY - 1, centerX + size - phase + 1, centerY + 2, color);
        context.fill(centerX - radius, centerY + radius + 6, centerX + radius + 1, centerY + radius + 8, withAlpha(accent, active ? 180 : 118));
    }

    private static void drawNodeRoleGlyph(DrawContext context, CanvasNode node, int centerX, int centerY, int radius, int accent, boolean emphasized) {
        String type = safe(node.type()).toLowerCase(Locale.ROOT);
        int color = withAlpha(accent, emphasized ? 220 : 142);
        if (type.contains("delegate") || type.contains("frame_pause")) {
            context.fill(centerX - radius - 6, centerY + radius - 2, centerX - radius + 1, centerY + radius + 1, color);
            context.fill(centerX - radius - 6, centerY + radius - 8, centerX - radius - 4, centerY + radius + 1, withAlpha(accent, emphasized ? 190 : 120));
            return;
        }
        if (type.contains("return") || type.contains("frame_resume") || type.contains("frame_summary")) {
            context.fill(centerX + radius - 1, centerY + radius - 2, centerX + radius + 7, centerY + radius + 1, color);
            context.fill(centerX + radius + 5, centerY - radius + 2, centerX + radius + 7, centerY + radius + 1, withAlpha(accent, emphasized ? 190 : 120));
            return;
        }
        if (type.contains("primitive")) {
            context.fill(centerX - radius + 2, centerY - radius - 6, centerX + radius - 1, centerY - radius - 4, color);
            context.fill(centerX - radius + 5, centerY - radius - 9, centerX + radius - 4, centerY - radius - 7, withAlpha(runtimeCueColor(), emphasized ? 190 : 120));
            return;
        }
        if (type.contains("branch") || type.contains("candidate")) {
            context.fill(centerX + radius - 1, centerY - radius + 2, centerX + radius + 6, centerY - radius + 4, color);
            context.fill(centerX + radius + 4, centerY - radius + 2, centerX + radius + 6, centerY + radius - 2, withAlpha(decisionCueColor(), emphasized ? 190 : 120));
        }
    }

    private static boolean isMemoryNode(CanvasNode node) {
        String text = (safe(node.sectionId()) + " " + safe(node.type()) + " " + safe(node.label())).toLowerCase(Locale.ROOT);
        return text.contains("memory") || text.contains("state") || text.contains("summary") || text.contains("mem");
    }

    private static boolean isFailedNode(CanvasNode node) {
        return "[fail]".equals(node.marker()) || !node.details().failure().isBlank() && !isBlockedNode(node);
    }

    private static boolean isBlockedNode(CanvasNode node) {
        return "[block]".equals(node.marker()) || "[warn]".equals(node.marker());
    }

    private static boolean isDoneNode(CanvasNode node) {
        return "[done]".equals(node.marker()) || "[ok  ]".equals(node.marker());
    }

    private static boolean isReturnNode(CanvasNode node) {
        String type = safe(node.type()).toLowerCase(Locale.ROOT);
        return type.contains("return") || type.contains("frame_resume") || type.contains("frame_summary");
    }

    private static boolean isChildPromptNode(CanvasNode node) {
        return !safe(node.parentFrameId()).isBlank() && safe(node.rowId()).startsWith("frame:");
    }

    private static void drawNodeCues(DrawContext context, CanvasNode node, int centerX, int centerY, int radius, int accent) {
        String text = (safe(node.label()) + " " + safe(node.value()) + " " + safe(node.type())).toLowerCase(Locale.ROOT);
        int top = centerY - radius - 5;
        int left = centerX - radius - 5;
        int right = centerX + radius + 5;
        int bottom = centerY + radius + 5;
        if (text.contains("template") || text.contains("semantic") || text.contains("grammar") || text.contains("lexicon") || text.contains("ktl")) {
            context.fill(left, top, right + 1, top + 2, withAlpha(languageCueColor(), 210));
        }
        if (text.contains("cap.") || text.contains("primitive") || text.contains("runtime") || text.contains("frame")) {
            context.fill(left, bottom - 1, right + 1, bottom + 1, withAlpha(runtimeCueColor(), 210));
        }
        if (text.contains("eval.") || text.contains("branch") || text.contains("condition")) {
            context.fill(left, top, left + 2, bottom + 1, withAlpha(decisionCueColor(), 210));
        }
        if (!node.details().search().isBlank()) {
            context.fill(right - 1, top, right + 1, bottom + 1, withAlpha(searchCueColor(), 210));
        }
        if (!node.details().requires().isBlank()) {
            context.fill(left, bottom + 3, centerX, bottom + 5, withAlpha(inputCueColor(), 190));
        }
        if (!node.details().output().isBlank()) {
            context.fill(centerX, bottom + 3, right + 1, bottom + 5, withAlpha(outputCueColor(), 190));
        }
        if (isMemoryNode(node)) {
            context.fill(left - 4, top + 3, left - 2, bottom - 2, withAlpha(memoryCueColor(), node.firing() ? 230 : 150));
            context.fill(left - 4, bottom - 4, centerX - 2, bottom - 2, withAlpha(memoryCueColor(), node.firing() ? 190 : 104));
        }
        if (isDoneNode(node)) {
            context.fill(left + 2, centerY + radius - 3, right - 2, centerY + radius, withAlpha(ConsoleTheme.levelColor(ConsoleLevel.DEBUG), 218));
            context.fill(right - 4, top + 2, right - 1, centerY + radius, withAlpha(ConsoleTheme.levelColor(ConsoleLevel.DEBUG), 186));
        }
        if (isReturnNode(node)) {
            context.fill(right + 2, centerY, right + 5, bottom, withAlpha(ConsoleTheme.levelColor(ConsoleLevel.DEBUG), 198));
        }
        if (node.firing()) {
            int phase = (int) ((System.currentTimeMillis() / 110L + node.sequence()) % 4L);
            int pulse = radius + 7 + phase;
            context.drawBorder(centerX - pulse, centerY - pulse, pulse * 2 + 1, pulse * 2 + 1, withAlpha(accent, 140 - phase * 20));
        }
    }

    private static boolean nodeContains(TextRenderer textRenderer, CanvasNode node, int x, int y, int mouseX, int mouseY) {
        int radius = clamp(4 + node.weight(), 6, 13) + 5;
        int centerX = x + node.width() / 2;
        int centerY = y + Math.min(node.height() / 2, 15);
        int cardX = centerX + radius;
        int cardY = y;
        int labelWidth = Math.min(132, Math.max(58, textRenderer == null ? node.label().length() * 6 + 10 : textRenderer.getWidth(node.label()) + 10));
        boolean dot = mouseX >= centerX - radius && mouseX <= centerX + radius && mouseY >= centerY - radius && mouseY <= centerY + radius;
        boolean card = mouseX >= cardX && mouseX <= cardX + labelWidth + 6 && mouseY >= cardY && mouseY <= cardY + Math.max(18, node.weight() >= 6 ? 30 : 18);
        return dot || card;
    }

    private static HoverCue cueAt(CanvasNode node, int x, int y, int mouseX, int mouseY) {
        int radius = clamp(4 + node.weight(), 6, 13);
        int centerX = x + node.width() / 2;
        int centerY = y + Math.min(node.height() / 2, 15);
        int top = centerY - radius - 5;
        int left = centerX - radius - 5;
        int right = centerX + radius + 5;
        int bottom = centerY + radius + 5;
        String text = (safe(node.label()) + " " + safe(node.value()) + " " + safe(node.type())).toLowerCase(Locale.ROOT);
        if (containsPoint(mouseX, mouseY, left, top - 3, right + 1, top + 5) && containsAny(text, "template", "semantic", "grammar", "lexicon", "ktl")) {
            return new HoverCue(node, "language cue", languageCueColor(), List.of(
                    "means: file-driven KTL language/compiler signal",
                    "data: " + compactSignal(node.label(), node.value()),
                    "role: lexicon, grammar, semantic, template, or cache work"
            ));
        }
        if (containsPoint(mouseX, mouseY, left, bottom - 4, right + 1, bottom + 4) && containsAny(text, "cap.", "primitive", "runtime", "frame")) {
            return new HoverCue(node, "runtime cue", runtimeCueColor(), List.of(
                    "means: Java runtime/capability/frame signal",
                    "data: " + compactSignal(node.label(), node.value()),
                    "role: execution machinery or primitive dispatch"
            ));
        }
        if (containsPoint(mouseX, mouseY, left - 3, top, left + 5, bottom + 1) && containsAny(text, "eval.", "branch", "condition")) {
            return new HoverCue(node, "decision cue", decisionCueColor(), List.of(
                    "means: evaluator, condition, or branch decision",
                    "data: " + compactSignal(node.label(), node.value()),
                    "role: controls which path the task takes"
            ));
        }
        if (containsPoint(mouseX, mouseY, right - 4, top, right + 4, bottom + 1) && !node.details().search().isBlank()) {
            return new HoverCue(node, "search cue", searchCueColor(), List.of(
                    "means: search, scan, resolve, or selector data",
                    "search: " + node.details().search(),
                    "role: gathers candidates or resolves a target"
            ));
        }
        if (containsPoint(mouseX, mouseY, left, bottom, centerX, bottom + 8) && !node.details().requires().isBlank()) {
            return new HoverCue(node, "required-input cue", inputCueColor(), List.of(
                    "means: this node needs input before it can run cleanly",
                    "requires: " + node.details().requires(),
                    "received: " + firstPresent(node.details().received(), "(not shown)")
            ));
        }
        if (containsPoint(mouseX, mouseY, centerX, bottom, right + 1, bottom + 8) && !node.details().output().isBlank()) {
            return new HoverCue(node, "output cue", outputCueColor(), List.of(
                    "means: this node produced data for later nodes",
                    "output: " + node.details().output(),
                    "lanes: " + (node.previous() == null ? "source cue only" : linkLanes(node).size() + " incoming lane(s)"),
                    "movement: payload packets now travel on execution/data links",
                    "next link: follow the colored connector leaving or entering this node"
            ));
        }
        if (containsPoint(mouseX, mouseY, left - 7, top, left + 3, bottom + 1) && isMemoryNode(node)) {
            return new HoverCue(node, "memory cue", memoryCueColor(), List.of(
                    "means: this node reads, writes, or summarizes runtime memory/state",
                    "state: " + (node.firing() ? "active memory transfer" : "stored memory context"),
                    "received: " + emptyAs(node.details().received(), "(no incoming memory payload shown)"),
                    "stored/output: " + emptyAs(firstPresent(node.details().output(), node.value()), "(no stored payload shown)"),
                    "visual: gray edge lanes mean state/memory is part of this node"
            ));
        }
        if (containsPoint(mouseX, mouseY, left, centerY + radius - 7, right + 5, centerY + radius + 4) && isDoneNode(node)) {
            return new HoverCue(node, "completion trace", ConsoleTheme.levelColor(ConsoleLevel.DEBUG), List.of(
                    "means: this node finished cleanly",
                    "state: " + node.marker(),
                    "output: " + emptyAs(node.details().output(), "completion/control state only"),
                    "visual: green integrated edge cue means complete"
            ));
        }
        HoverCue roleCue = roleCueAt(node, centerX, centerY, radius, mouseX, mouseY);
        if (roleCue != null) {
            return roleCue;
        }
        return null;
    }

    private static HoverCue roleCueAt(CanvasNode node, int centerX, int centerY, int radius, int mouseX, int mouseY) {
        String type = safe(node.type()).toLowerCase(Locale.ROOT);
        if ((type.contains("delegate") || type.contains("frame_pause")) && containsPoint(mouseX, mouseY, centerX - radius - 8, centerY + radius - 9, centerX - radius + 2, centerY + radius + 3)) {
            return new HoverCue(node, "delegate cue", ConsoleTheme.levelColor(ConsoleLevel.UPDATE), List.of(
                    "means: this node hands execution to a child frame",
                    "transfer: parent pauses until child returns",
                    "data: " + compactSignal(node.label(), node.value())
            ));
        }
        if ((type.contains("return") || type.contains("frame_resume") || type.contains("frame_summary")) && containsPoint(mouseX, mouseY, centerX + radius - 2, centerY - radius, centerX + radius + 9, centerY + radius + 3)) {
            return new HoverCue(node, "return cue", ConsoleTheme.levelColor(ConsoleLevel.DEBUG), List.of(
                    "means: child frame returned data/control to parent",
                    "transfer: return values and state merge upward",
                    "data: " + compactSignal(node.label(), node.value())
            ));
        }
        if (type.contains("primitive") && containsPoint(mouseX, mouseY, centerX - radius, centerY - radius - 11, centerX + radius + 2, centerY - radius + 2)) {
            return new HoverCue(node, "primitive cue", ConsoleTheme.levelColor(ConsoleLevel.UPDATE), List.of(
                    "means: Java capability execution",
                    "requires: " + emptyAs(node.details().requires(), "(params not emitted)"),
                    "output: " + emptyAs(node.details().output(), "(pending or none)")
            ));
        }
        if ((type.contains("branch") || type.contains("candidate")) && containsPoint(mouseX, mouseY, centerX + radius - 2, centerY - radius, centerX + radius + 9, centerY + radius)) {
            return new HoverCue(node, "branch cue", ConsoleTheme.levelColor(ConsoleLevel.WARN), List.of(
                    "means: evaluator or candidate decision",
                    "selected: " + compactSignal(node.label(), node.value()),
                    "role: controls which task path executes next"
            ));
        }
        return null;
    }

    private static boolean containsPoint(int mouseX, int mouseY, int left, int top, int right, int bottom) {
        return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
    }

    private static void drawHoverCard(DrawContext context, TextRenderer textRenderer, CanvasNode node, int mouseX, int mouseY, int viewportX, int viewportY, int viewportWidth, int viewportHeight) {
        List<HoverLine> lines = new ArrayList<>();
        lines.add(new HoverLine("node #" + (node.sequence() + 1) + "  " + node.label(), colorFor(node)));
        lines.add(new HoverLine("state " + node.marker() + "  type " + node.type(), statusColor(node)));
        lines.addAll(nodeRoleLines(node));
        lines.add(new HoverLine("source: " + node.details().source(), ConsoleTheme.secondaryText()));
        if (!node.details().search().isBlank()) {
            lines.add(new HoverLine("search: " + node.details().search(), ConsoleTheme.levelColor(ConsoleLevel.INFO)));
        }
        if (!node.details().requires().isBlank()) {
            lines.add(new HoverLine("requires: " + node.details().requires(), ConsoleTheme.levelColor(ConsoleLevel.WARN)));
        }
        if (!node.details().received().isBlank()) {
            lines.add(new HoverLine("received: " + node.details().received(), ConsoleTheme.levelColor(ConsoleLevel.UPDATE)));
        }
        if (!node.details().output().isBlank()) {
            lines.add(new HoverLine("output: " + node.details().output(), ConsoleTheme.levelColor(ConsoleLevel.DEBUG)));
        }
        if (!node.details().failure().isBlank()) {
            lines.add(new HoverLine("failed: " + node.details().failure(), ConsoleTheme.levelColor(ConsoleLevel.ERROR)));
        }
        if (!node.details().recovery().isBlank()) {
            lines.add(new HoverLine("fix: " + node.details().recovery(), ConsoleTheme.levelColor(ConsoleLevel.WARN)));
        }
        lines.add(new HoverLine("metrics: depth " + node.depth() + "  weight " + node.weight() + "  dirty " + node.dirty() + "  firing " + node.firing(), ConsoleTheme.secondaryText()));
        lines.add(new HoverLine("tokens: " + (tokenCount(node.label()) + tokenCount(node.value())) + "  row " + node.rowId(), ConsoleTheme.levelColor(ConsoleLevel.OTHER)));
        drawTooltipPopup(context, textRenderer, lines, mouseX, mouseY);
    }

    private static void drawSelectedNodeInspector(DrawContext context, TextRenderer textRenderer, CanvasNode node, CanvasLayout layout, int viewportX, int viewportY, int viewportWidth, int viewportHeight, int scrollPixels, int panX) {
        int width = Math.min(360, Math.max(250, viewportWidth / 2));
        int lineHeight = 12;
        List<HoverLine> lines = selectedNodeLines(node);
        int visibleLines = Math.min(lines.size(), Math.max(9, (viewportHeight - 66) / lineHeight));
        int height = 16 + visibleLines * lineHeight + 8;
        int accent = colorFor(node);
        int nodeScreenX = viewportX + node.x() + panX;
        int nodeScreenY = viewportY + node.y() - scrollPixels;
        int preferredX = nodeScreenX + node.width() + 18;
        if (preferredX + width > viewportX + viewportWidth - 8) {
            preferredX = nodeScreenX - width - 18;
        }
        int x = clamp(preferredX, viewportX + 8, viewportX + Math.max(8, viewportWidth - width - 8));
        int y = clamp(nodeScreenY - 10, viewportY + 34, viewportY + Math.max(34, viewportHeight - height - 8));
        drawInspectorLeader(context, nodeScreenX, nodeScreenY, node.width(), node.height(), x, y, width, height, accent);
        context.fill(x, y, x + width, y + height, withAlpha(ConsoleTheme.background(), 238));
        context.drawBorder(x, y, width, height, withAlpha(accent, 238));
        context.fill(x + 4, y + 4, x + 7, y + height - 4, withAlpha(accent, 210));
        context.drawText(textRenderer, "focused node inspector", x + 12, y + 6, accent, false);
        int rowY = y + 18;
        for (int index = 0; index < visibleLines; index++) {
            HoverLine line = lines.get(index);
            if (line.section()) {
                String section = "-- " + line.text() + " --";
                context.drawText(textRenderer, trim(textRenderer, section, width - 24), x + 12, rowY, line.color(), false);
            } else {
                drawInspectorLine(context, textRenderer, line, x + 12, rowY, width - 22);
            }
            rowY += lineHeight;
        }
        if (lines.size() > visibleLines) {
            context.drawText(textRenderer, "+" + (lines.size() - visibleLines) + " more fields in node data", x + 12, y + height - 12, ConsoleTheme.secondaryText(), false);
        }
    }

    private static void drawInspectorLeader(DrawContext context, int nodeX, int nodeY, int nodeWidth, int nodeHeight, int panelX, int panelY, int panelWidth, int panelHeight, int accent) {
        int nodeCenterX = nodeX + nodeWidth / 2;
        int nodeCenterY = nodeY + Math.min(nodeHeight / 2, 15);
        int panelCenterY = panelY + panelHeight / 2;
        boolean panelRight = panelX >= nodeCenterX;
        int startX = panelRight ? nodeX + nodeWidth + 2 : nodeX - 2;
        int startY = nodeCenterY;
        int endX = panelRight ? panelX - 1 : panelX + panelWidth + 1;
        int endY = clamp(panelCenterY, panelY + 12, panelY + panelHeight - 12);
        int laneX = panelRight
                ? Math.max(startX + 8, Math.min(endX - 8, nodeX + nodeWidth + 18))
                : Math.min(startX - 8, Math.max(endX + 8, nodeX - 18));
        int color = withAlpha(accent, 168);
        int halo = withAlpha(accent, 38);
        drawSegment(context, startX, startY, laneX, startY, color, 1);
        drawSegment(context, laneX, startY, laneX, endY, color, 1);
        drawSegment(context, laneX, endY, endX, endY, color, 1);
        drawSegment(context, startX, startY + 1, laneX, startY + 1, halo, 1);
        drawSegment(context, laneX + (panelRight ? 1 : -1), Math.min(startY, endY), laneX + (panelRight ? 1 : -1), Math.max(startY, endY), halo, 1);
        context.fill(startX - 2, startY - 2, startX + 3, startY + 3, withAlpha(accent, 178));
        context.fill(endX - 2, endY - 2, endX + 3, endY + 3, withAlpha(accent, 210));
    }

    private static void drawSegment(DrawContext context, int x1, int y1, int x2, int y2, int color, int thickness) {
        if (x1 == x2) {
            context.fill(x1, Math.min(y1, y2), x1 + thickness, Math.max(y1, y2) + 1, color);
            return;
        }
        if (y1 == y2) {
            context.fill(Math.min(x1, x2), y1, Math.max(x1, x2) + 1, y1 + thickness, color);
        }
    }

    private static List<HoverLine> selectedNodeLines(CanvasNode node) {
        List<HoverLine> lines = new ArrayList<>();
        lines.add(new HoverLine("#" + (node.sequence() + 1) + " " + node.label(), colorFor(node), false));
        lines.add(new HoverLine("runtime identity", ConsoleTheme.primaryText(), true));
        lines.add(new HoverLine("state: " + node.marker() + "  type: " + node.type() + "  frame: " + emptyAs(node.frameId(), "root"), statusColor(node), false));
        lines.addAll(nodeRoleLines(node));
        lines.add(new HoverLine("row: " + node.rowId() + "  section: " + node.sectionId(), ConsoleTheme.secondaryText(), false));
        lines.add(new HoverLine("value: " + emptyAs(node.value(), "(empty)"), valueColor(node), false));
        lines.add(new HoverLine("data flow", ConsoleTheme.levelColor(ConsoleLevel.UPDATE), true));
        lines.add(new HoverLine("given by: " + incomingSource(node), ConsoleTheme.levelColor(ConsoleLevel.UPDATE), false));
        lines.add(new HoverLine("received: " + emptyAs(node.details().received(), "(no received payload shown)"), ConsoleTheme.levelColor(ConsoleLevel.UPDATE), false));
        lines.add(new HoverLine("requires: " + emptyAs(node.details().requires(), "(no required input shown)"), ConsoleTheme.levelColor(ConsoleLevel.WARN), false));
        lines.add(new HoverLine("search: " + emptyAs(node.details().search(), "(no search/scan data shown)"), ConsoleTheme.levelColor(ConsoleLevel.INFO), false));
        lines.add(new HoverLine("output: " + emptyAs(node.details().output(), "(no output payload shown)"), ConsoleTheme.levelColor(ConsoleLevel.DEBUG), false));
        if (!node.details().failure().isBlank()) {
            lines.add(new HoverLine("failure handling", ConsoleTheme.levelColor(ConsoleLevel.ERROR), true));
            lines.add(new HoverLine("failure: " + node.details().failure(), ConsoleTheme.levelColor(ConsoleLevel.ERROR), false));
        }
        if (!node.details().recovery().isBlank()) {
            lines.add(new HoverLine("fix: " + node.details().recovery(), ConsoleTheme.levelColor(ConsoleLevel.WARN), false));
        }
        lines.add(new HoverLine("source and links", ConsoleTheme.levelColor(ConsoleLevel.OTHER), true));
        lines.add(new HoverLine("source file: " + sourceFileHint(node), ConsoleTheme.levelColor(ConsoleLevel.OTHER), false));
        lines.add(new HoverLine("source line: " + sourceLineHint(node), ConsoleTheme.levelColor(ConsoleLevel.OTHER), false));
        lines.add(new HoverLine("incoming link: " + incomingLinkHint(node), ConsoleTheme.secondaryText(), false));
        lines.add(new HoverLine("related section: " + relatedHint(node.sectionSource()), ConsoleTheme.secondaryText(), false));
        lines.add(new HoverLine("related type: " + relatedHint(node.typeSource()), ConsoleTheme.secondaryText(), false));
        lines.add(new HoverLine("metrics", ConsoleTheme.primaryText(), true));
        lines.add(new HoverLine("depth " + node.depth() + "  weight " + node.weight() + "  tokens " + (tokenCount(node.label()) + tokenCount(node.value())), ConsoleTheme.primaryText(), false));
        lines.add(new HoverLine("dirty " + node.dirty() + "  firing " + node.firing(), node.firing() ? ConsoleTheme.levelColor(ConsoleLevel.UPDATE) : ConsoleTheme.secondaryText(), false));
        return lines;
    }

    private static List<HoverLine> nodeRoleLines(CanvasNode node) {
        String type = safe(node.type()).toLowerCase(Locale.ROOT);
        if (type.contains("delegate") || type.contains("frame_pause")) {
            return List.of(
                    new HoverLine("role: delegate child frame", ConsoleTheme.levelColor(ConsoleLevel.UPDATE)),
                    new HoverLine("flow: parent pauses, child owns active branch", ConsoleTheme.secondaryText())
            );
        }
        if (type.contains("return") || type.contains("frame_resume") || type.contains("frame_summary")) {
            return List.of(
                    new HoverLine("role: return / resume", ConsoleTheme.levelColor(ConsoleLevel.DEBUG)),
                    new HoverLine("flow: child output is returned to parent", ConsoleTheme.secondaryText())
            );
        }
        if (type.contains("primitive")) {
            return List.of(
                    new HoverLine("role: capability execution", ConsoleTheme.levelColor(ConsoleLevel.UPDATE)),
                    new HoverLine("runtime: Java capability key with bound params", ConsoleTheme.secondaryText())
            );
        }
        if (type.contains("branch") || type.contains("candidate")) {
            return List.of(
                    new HoverLine("role: decision point", ConsoleTheme.levelColor(ConsoleLevel.WARN)),
                    new HoverLine("runtime: evaluator/template candidate selection", ConsoleTheme.secondaryText())
            );
        }
        if (type.contains("world")) {
            return List.of(new HoverLine("role: world probe / scan", ConsoleTheme.levelColor(ConsoleLevel.INFO)));
        }
        if (type.contains("state") || type.contains("memory")) {
            return List.of(new HoverLine("role: runtime memory/state", ConsoleTheme.levelColor(ConsoleLevel.OTHER)));
        }
        return List.of();
    }

    private static void drawInspectorLine(DrawContext context, TextRenderer textRenderer, HoverLine line, int x, int y, int width) {
        String text = safe(line.text());
        int split = text.indexOf(':');
        if (split <= 0) {
            context.drawText(textRenderer, trim(textRenderer, text, width), x, y, line.color(), false);
            return;
        }
        String key = text.substring(0, split + 1);
        String value = text.substring(split + 1).trim();
        int keyWidth = Math.min(86, textRenderer.getWidth(key));
        context.drawText(textRenderer, trim(textRenderer, key, keyWidth), x, y, ConsoleTheme.secondaryText(), false);
        context.drawText(textRenderer, trim(textRenderer, value, Math.max(30, width - keyWidth - 5)), x + keyWidth + 5, y, line.color(), false);
    }

    private static String incomingSource(CanvasNode node) {
        CanvasNode previous = node.previous();
        if (previous == null) {
            return "session root";
        }
        String payload = firstPresent(previous.details().output(), previous.value());
        return "#" + (previous.sequence() + 1) + " " + previous.label() + " -> " + emptyAs(payload, "control flow");
    }

    private static String sourceFileHint(CanvasNode node) {
        String text = safe(node.value()) + " " + safe(node.label()) + " " + safe(node.rowId());
        String ktl = tokenContaining(text, ".ktl");
        if (!ktl.isBlank()) {
            return ktl;
        }
        String template = tokenAfter(text, "template=");
        if (template.isBlank()) {
            template = tokenAfter(text, "template:");
        }
        if (!template.isBlank()) {
            return "template " + template;
        }
        if (text.toLowerCase(Locale.ROOT).contains("ktl") || text.toLowerCase(Locale.ROOT).contains("compile")) {
            return "compiled KTL artifact";
        }
        return "runtime row; no file emitted";
    }

    private static String sourceLineHint(CanvasNode node) {
        String text = safe(node.value()) + " " + safe(node.label()) + " " + safe(node.rowId());
        String line = tokenAfter(text, "line=");
        if (line.isBlank()) {
            line = tokenAfter(text, "line:");
        }
        return line.isBlank() ? "not emitted by current KTL row" : line;
    }

    private static int sourceLineNumber(CanvasNode node) {
        String text = safe(node.value()) + " " + safe(node.label()) + " " + safe(node.rowId()) + " " + safe(node.details().source());
        String line = tokenAfter(text, "source.line=");
        if (line.isBlank()) {
            line = tokenAfter(text, "line=");
        }
        if (line.isBlank()) {
            line = tokenAfter(text, "line:");
        }
        try {
            return line.isBlank() ? -1 : Integer.parseInt(line.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String resolveKtlSourcePath(CanvasNode node) {
        String text = safe(node.value()) + " " + safe(node.label()) + " " + safe(node.rowId()) + " "
                + safe(node.details().source()) + " " + safe(node.details().output()) + " " + safe(node.details().received());
        String ktl = tokenContaining(text, ".ktl");
        if (!ktl.isBlank()) {
            String resolved = resolveExistingKtlPath(ktl);
            if (!resolved.isBlank()) {
                return resolved;
            }
        }
        String template = tokenAfter(text, "template=");
        if (template.isBlank()) {
            template = tokenAfter(text, "template:");
        }
        if (!template.isBlank()) {
            String resolved = resolveExistingKtlPath(template.endsWith(".ktl") ? template : template + ".ktl");
            if (!resolved.isBlank()) {
                return resolved;
            }
            resolved = resolveExistingKtlPath("koil/automation/templates/tasks/" + template + ".ktl");
            if (!resolved.isBlank()) {
                return resolved;
            }
        }
        return "";
    }

    private static String resolveExistingKtlPath(String rawPath) {
        String cleaned = safe(rawPath).replace("source.file=", "").replace("source:", "").replace("file=", "").replace("(", "").replace(")", "").replace("[", "").replace("]", "").trim();
        while (cleaned.endsWith(".") || cleaned.endsWith(",") || cleaned.endsWith(";") || cleaned.endsWith(":")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isBlank()) {
            return "";
        }
        List<Path> candidates = List.of(
                Path.of(cleaned),
                Path.of(".").toAbsolutePath().normalize().resolve(cleaned).normalize(),
                Path.of("koil/automation").resolve(cleaned).normalize(),
                Path.of("koil/automation/templates/tasks").resolve(cleaned).normalize(),
                Path.of("koil/automation/templates/meta").resolve(cleaned).normalize()
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && candidate.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ktl")) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        Path root = Path.of("koil/automation");
        if (Files.isDirectory(root)) {
            String fileName = Path.of(cleaned).getFileName().toString();
            try (var stream = Files.walk(root)) {
                Optional<Path> found = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equals(fileName))
                        .findFirst();
                return found.map(path -> path.toAbsolutePath().normalize().toString()).orElse("");
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static int languageCueColor() {
        return new Color(85, 135, 255).getRGB();
    }

    private static int runtimeCueColor() {
        return new Color(74, 178, 116).getRGB();
    }

    private static int decisionCueColor() {
        return new Color(235, 154, 62).getRGB();
    }

    private static int searchCueColor() {
        return new Color(55, 185, 196).getRGB();
    }

    private static int inputCueColor() {
        return new Color(230, 196, 80).getRGB();
    }

    private static int outputCueColor() {
        return new Color(172, 118, 232).getRGB();
    }

    private static int memoryCueColor() {
        return new Color(190, 190, 190).getRGB();
    }

    private static String incomingLinkHint(CanvasNode node) {
        if (node.previous() == null) {
            return "none";
        }
        String needs = emptyAs(node.details().requires(), "control continuation");
        String received = emptyAs(node.details().received(), "previous node state");
        return "needs " + needs + " / received " + received;
    }

    private static String relatedHint(CanvasNode node) {
        if (node == null) {
            return "none";
        }
        return "#" + (node.sequence() + 1) + " " + node.label();
    }

    private static String returnParentFrame(CanvasNode node) {
        String rowId = safe(node.rowId());
        if (rowId.startsWith("return:")) {
            String[] parts = rowId.split(":");
            if (parts.length >= 3) {
                return parts[1];
            }
        }
        if (rowId.startsWith("resume:")) {
            String[] parts = rowId.split(":");
            if (parts.length >= 3) {
                return parts[1];
            }
        }
        return "";
    }

    private static String tokenContaining(String text, String needle) {
        for (String token : safe(text).split("\\s+")) {
            if (token.contains(needle)) {
                return token.replace(",", "").replace(";", "");
            }
        }
        return "";
    }

    private static String tokenAfter(String text, String prefix) {
        String lower = safe(text).toLowerCase(Locale.ROOT);
        int start = lower.indexOf(prefix);
        if (start < 0) {
            return "";
        }
        int valueStart = start + prefix.length();
        int end = valueStart;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end)) && text.charAt(end) != ',' && text.charAt(end) != ';') {
            end++;
        }
        return text.substring(valueStart, end).trim();
    }

    private static String emptyAs(String value, String fallback) {
        return safe(value).isBlank() ? fallback : value;
    }

    private static void drawCueHoverCard(DrawContext context, TextRenderer textRenderer, HoverCue cue, int mouseX, int mouseY) {
        List<HoverLine> lines = new ArrayList<>();
        if (cue.node() == null) {
            lines.add(new HoverLine(cue.title(), cue.color()));
        } else {
            lines.add(new HoverLine(cue.title() + "  #" + (cue.node().sequence() + 1), cue.color()));
            lines.add(new HoverLine("node: " + cue.node().label(), colorFor(cue.node())));
        }
        for (String detail : cue.details()) {
            lines.add(new HoverLine(detail, detail.startsWith("means:") ? ConsoleTheme.primaryText() : ConsoleTheme.secondaryText()));
        }
        if (cue.node() != null && !cue.node().details().failure().isBlank()) {
            lines.add(new HoverLine("risk: " + cue.node().details().failure(), ConsoleTheme.levelColor(ConsoleLevel.ERROR)));
        }
        drawTooltipPopup(context, textRenderer, lines, mouseX, mouseY);
    }

    private static void drawLinkHoverCard(DrawContext context, TextRenderer textRenderer, HoverLink link, int mouseX, int mouseY, int viewportX, int viewportY, int viewportWidth, int viewportHeight) {
        CanvasNode source = link.source();
        CanvasNode target = link.target();
        List<HoverLine> lines = new ArrayList<>();
        int accent = link.color() == 0 ? edgeColorFor(target) : link.color();
        lines.add(new HoverLine(link.kind() + " #" + (source.sequence() + 1) + " -> #" + (target.sequence() + 1), accent));
        lines.add(new HoverLine("from: " + source.label(), colorFor(source)));
        lines.add(new HoverLine("to: " + target.label(), colorFor(target)));
        if (link.lane() >= 0) {
            lines.add(new HoverLine("lane: " + (link.lane() + 1) + "  role: " + link.role(), accent));
            lines.add(new HoverLine("segment: " + link.segment(), accent));
            lines.add(new HoverLine("payload: " + emptyAs(link.payload(), "control flow"), accent));
            lines.add(new HoverLine("why: this lane exists because " + laneReason(link), ConsoleTheme.primaryText()));
        }
        if (link.kind().contains("mesh")) {
            lines.add(new HoverLine("means: related nodes share section/type context", ConsoleTheme.primaryText()));
        }
        if (!target.details().requires().isBlank()) {
            lines.add(new HoverLine("needed: " + target.details().requires(), ConsoleTheme.levelColor(ConsoleLevel.WARN)));
        }
        if (!target.details().received().isBlank()) {
            lines.add(new HoverLine("received: " + target.details().received(), ConsoleTheme.levelColor(ConsoleLevel.UPDATE)));
        }
        if (!source.details().output().isBlank()) {
            lines.add(new HoverLine("source output: " + source.details().output(), ConsoleTheme.levelColor(ConsoleLevel.DEBUG)));
        }
        if (!target.details().failure().isBlank()) {
            lines.add(new HoverLine("risk: " + target.details().failure(), ConsoleTheme.levelColor(ConsoleLevel.ERROR)));
        }
        drawTooltipPopup(context, textRenderer, lines, mouseX, mouseY);
    }

    private static String laneReason(HoverLink link) {
        String role = safe(link.role()).toLowerCase(Locale.ROOT);
        String payload = safe(link.payload()).toLowerCase(Locale.ROOT);
        if (role.contains("required") || payload.contains("required") || payload.contains("requires")) {
            return "the target node needs explicit input";
        }
        if (role.contains("search")) {
            return "the target node is resolving candidates or scanning world/language state";
        }
        if (role.contains("produced")) {
            return "the source node produced output for downstream nodes";
        }
        if (role.contains("received")) {
            return "the target node accepted payload from an earlier stage";
        }
        if (role.contains("primitive")) {
            return "Java capability execution is being called or reported";
        }
        if (role.contains("completion")) {
            return "the node completed and is exposing final state";
        }
        if (role.contains("return") || payload.contains("return") || safe(link.segment()).contains("return")) {
            return "control or data is returning to a caller";
        }
        if (role.contains("memory") || payload.contains("memory") || payload.contains("state")) {
            return "runtime state or memory is being shared";
        }
        if (safe(link.segment()).contains("source")) {
            return "data is leaving the source node";
        }
        if (safe(link.segment()).contains("target")) {
            return "data is entering the target node";
        }
        return "execution data moves between these two graph nodes";
    }

    private static void drawFrameHoverCard(DrawContext context, TextRenderer textRenderer, FrameBand band, int mouseX, int mouseY, int viewportX, int viewportY, int viewportWidth, int viewportHeight) {
        List<HoverLine> lines = new ArrayList<>();
        int accent = frameColor(band);
        lines.add(new HoverLine("frame " + band.displayId(), accent));
        lines.add(new HoverLine("nodes: " + band.nodeCount() + "  tokens: " + band.tokens() + "  processors: " + band.processors(), ConsoleTheme.primaryText()));
        lines.add(new HoverLine("state: running " + band.running() + "  firing " + band.firing() + "  blocked " + band.blocked() + "  failed " + band.failed(), band.failed() > 0 ? ConsoleTheme.levelColor(ConsoleLevel.ERROR) : band.blocked() > 0 ? ConsoleTheme.levelColor(ConsoleLevel.WARN) : ConsoleTheme.levelColor(ConsoleLevel.UPDATE)));
        lines.add(new HoverLine("data: in " + band.dataIn() + "  out " + band.dataOut() + "  memory " + band.memory(), ConsoleTheme.levelColor(ConsoleLevel.DEBUG)));
        if (!band.strongest().isBlank()) {
            lines.add(new HoverLine("strongest: " + band.strongest(), ConsoleTheme.secondaryText()));
        }
        if (!band.failure().isBlank()) {
            lines.add(new HoverLine("failure: " + band.failure(), ConsoleTheme.levelColor(ConsoleLevel.ERROR)));
        }
        if (!band.recovery().isBlank()) {
            lines.add(new HoverLine("fix: " + band.recovery(), ConsoleTheme.levelColor(ConsoleLevel.WARN)));
        }
        if (!band.search().isBlank()) {
            lines.add(new HoverLine("search: " + band.search(), ConsoleTheme.levelColor(ConsoleLevel.INFO)));
        }
        if (!band.output().isBlank()) {
            lines.add(new HoverLine("latest out: " + band.output(), ConsoleTheme.levelColor(ConsoleLevel.DEBUG)));
        }
        drawTooltipPopup(context, textRenderer, lines, mouseX, mouseY);
    }

    private static void drawTooltipPopup(DrawContext context, TextRenderer textRenderer, List<HoverLine> lines, int mouseX, int mouseY) {
        List<Text> tooltipLines = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            HoverLine line = lines.get(index);
            Style style = Style.EMPTY.withColor(line.color());
            if (index == 0) {
                style = style.withBold(true);
            }
            tooltipLines.add(Text.literal(line.text()).setStyle(style));
        }
        context.drawTooltip(textRenderer, tooltipLines, Optional.empty(), mouseX, mouseY);
    }

    private static String typeLabel(CanvasNode node) {
        String type = safe(node.type()).replace("planner_", "").replace("flow_", "").replace("graph_", "");
        if (type.isBlank()) {
            type = "node";
        }
        return type.length() > 7 ? type.substring(0, 7) : type;
    }

    private static int colorFor(CanvasNode node) {
        if ("[fail]".equals(node.marker())) {
            return ConsoleTheme.levelColor(ConsoleLevel.ERROR);
        }
        if ("[block]".equals(node.marker()) || "[warn]".equals(node.marker())) {
            return ConsoleTheme.levelColor(ConsoleLevel.WARN);
        }
        if ("[done]".equals(node.marker()) || "[ok  ]".equals(node.marker())) {
            return ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
        }
        String type = safe(node.type());
        String section = safe(node.sectionId());
        if (type.contains("return") || type.contains("frame_resume") || type.contains("frame_summary")) {
            return ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
        }
        if (type.contains("delegate") || type.contains("frame_pause")) {
            return ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
        }
        if (type.contains("primitive") || type.contains("flow")) {
            return ConsoleTheme.primaryText();
        }
        if (type.contains("branch") || type.contains("candidate")) {
            return ConsoleTheme.levelColor(ConsoleLevel.WARN);
        }
        if (type.contains("world") || section.equals("world")) {
            return ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
        }
        if (type.contains("state") || section.equals("state") || section.equals("summary")) {
            return ConsoleTheme.levelColor(ConsoleLevel.OTHER);
        }
        if (type.contains("planner") || section.equals("pipeline")) {
            return ConsoleTheme.levelColor(ConsoleLevel.INFO);
        }
        return ConsoleTheme.primaryText();
    }

    private static int edgeColorFor(CanvasNode node) {
        if (node.previous() == null) {
            return colorFor(node);
        }
        int from = colorFor(node.previous());
        int to = colorFor(node);
        Color a = new Color(from, true);
        Color b = new Color(to, true);
        return new Color((a.getRed() + b.getRed()) / 2, (a.getGreen() + b.getGreen()) / 2, (a.getBlue() + b.getBlue()) / 2, 255).getRGB();
    }

    private static int valueColor(CanvasNode node) {
        String lower = safe(node.value()).toLowerCase(Locale.ROOT);
        if (lower.contains("fail") || lower.contains("error")) {
            return ConsoleTheme.levelColor(ConsoleLevel.ERROR);
        }
        if (lower.contains("success") || lower.contains("true") || lower.contains("ready") || lower.contains("complete")) {
            return ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
        }
        if (lower.contains("false") || lower.contains("blocked") || lower.contains("unresolved") || lower.contains("ambiguous")) {
            return ConsoleTheme.levelColor(ConsoleLevel.WARN);
        }
        return ConsoleTheme.secondaryText();
    }

    private static String traceDetail(NodeDetails details) {
        if (!safe(details.failure()).isBlank()) {
            return "error: " + details.failure();
        }
        if (!safe(details.search()).isBlank()) {
            return "search: " + details.search();
        }
        if (!safe(details.output()).isBlank()) {
            return "out: " + details.output();
        }
        if (!safe(details.requires()).isBlank()) {
            return "needs: " + details.requires();
        }
        if (!safe(details.received()).isBlank()) {
            return "in: " + details.received();
        }
        return "";
    }

    private static int detailColor(NodeDetails details) {
        if (!safe(details.failure()).isBlank()) {
            return ConsoleTheme.levelColor(ConsoleLevel.ERROR);
        }
        if (!safe(details.search()).isBlank()) {
            return ConsoleTheme.levelColor(ConsoleLevel.INFO);
        }
        if (!safe(details.output()).isBlank()) {
            return ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
        }
        if (!safe(details.requires()).isBlank()) {
            return ConsoleTheme.levelColor(ConsoleLevel.WARN);
        }
        return ConsoleTheme.secondaryText();
    }

    private static int statusColor(CanvasNode node) {
        if ("[fail]".equals(node.marker())) {
            return ConsoleTheme.levelColor(ConsoleLevel.ERROR);
        }
        if ("[block]".equals(node.marker()) || "[warn]".equals(node.marker())) {
            return ConsoleTheme.levelColor(ConsoleLevel.WARN);
        }
        if ("[run ]".equals(node.marker())) {
            return ConsoleTheme.levelColor(ConsoleLevel.UPDATE);
        }
        if ("[done]".equals(node.marker()) || "[ok  ]".equals(node.marker())) {
            return ConsoleTheme.levelColor(ConsoleLevel.DEBUG);
        }
        return ConsoleTheme.secondaryText();
    }

    private static String trim(TextRenderer textRenderer, String value, int width) {
        String text = value == null ? "" : value;
        if (textRenderer.getWidth(text) <= width) {
            return text;
        }
        while (text.length() > 1 && textRenderer.getWidth(text + "...") > width) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "...";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int withAlpha(int rgb, int alpha) {
        Color color = new Color(rgb, true);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha).getRGB();
    }

    static String rowIdAt(AutomationCliSnapshot snapshot, int x, int y, int width, int height, double scrollOffset, int panX, String query, int mouseX, int mouseY) {
        if (snapshot == null || snapshot.rows() == null) {
            return "";
        }
        CanvasLayout layout = layout(snapshot, query, width);
        int contentHeight = Math.max(height, layout.contentHeight());
        int maxScrollPixels = Math.max(0, contentHeight - height);
        int scrollPixels = Math.min(maxScrollPixels, Math.max(0, (int) Math.round(scrollOffset * SCROLL_UNIT)));
        for (int index = layout.nodes().size() - 1; index >= 0; index--) {
            CanvasNode node = layout.nodes().get(index);
            int nodeX = x + node.x() + panX;
            int nodeY = y + node.y() - scrollPixels;
            if (nodeY > y + height || nodeY + node.height() < y) {
                continue;
            }
            if (nodeContains(null, node, nodeX, nodeY, mouseX, mouseY)) {
                selectedRowId = node.rowId();
                return node.rowId();
            }
        }
        return "";
    }

    private static final class FrameAccumulator {
        private final String id;
        private int yStart = Integer.MAX_VALUE;
        private int yEnd = Integer.MIN_VALUE;
        private int nodeCount;
        private int failed;
        private int blocked;
        private int running;
        private int firing;
        private int dataIn;
        private int dataOut;
        private int memory;
        private int processors;
        private int tokens;
        private int depth;
        private int strongestWeight;
        private String strongest = "";
        private String failure = "";
        private String recovery = "";
        private String search = "";
        private String output = "";

        private FrameAccumulator(String id) {
            this.id = id;
        }

        private void accept(CanvasNode node) {
            this.yStart = Math.min(this.yStart, node.y() - 18);
            this.yEnd = Math.max(this.yEnd, node.y() + node.height() + 18);
            this.nodeCount++;
            this.depth = Math.max(this.depth, node.depth());
            this.tokens += tokenCount(node.label()) + tokenCount(node.value());
            if ("[fail]".equals(node.marker())) {
                this.failed++;
            }
            if ("[block]".equals(node.marker()) || "[warn]".equals(node.marker())) {
                this.blocked++;
            }
            if ("[run ]".equals(node.marker())) {
                this.running++;
            }
            if (node.firing()) {
                this.firing++;
            }
            if (!node.details().requires().isBlank() || !node.details().received().isBlank()) {
                this.dataIn++;
            }
            if (!node.details().output().isBlank()) {
                this.dataOut++;
                this.output = node.details().output();
            }
            if (isMemoryNode(node)) {
                this.memory++;
            }
            if (isProcessor(node)) {
                this.processors++;
            }
            if (node.weight() >= this.strongestWeight) {
                this.strongestWeight = node.weight();
                this.strongest = node.label();
            }
            if (!node.details().failure().isBlank()) {
                this.failure = node.details().failure();
            }
            if (!node.details().recovery().isBlank()) {
                this.recovery = node.details().recovery();
            }
            if (!node.details().search().isBlank()) {
                this.search = node.details().search();
            }
        }

        private FrameBand toBand() {
            String displayId = this.id.length() > 18 ? this.id.substring(0, 18) : this.id;
            return new FrameBand(this.id, displayId, this.yStart == Integer.MAX_VALUE ? 0 : this.yStart, this.yEnd == Integer.MIN_VALUE ? 0 : this.yEnd, this.depth, this.nodeCount, this.failed, this.blocked, this.running, this.firing, this.dataIn, this.dataOut, this.memory, this.processors, this.tokens, this.strongest, this.failure, this.recovery, this.search, this.output);
        }
    }

    private record CanvasLayout(List<CanvasNode> nodes, List<AutomationCliRow> headerRows, List<FrameBand> frameBands, BrainStats stats, boolean activeTransfer, int graphWidth, int streamX, int eventWidth, int contentHeight, int headerHeight) {
    }

    private record CanvasNode(int sequence, int x, int y, int width, int height, int weight, String frameId, String parentFrameId, String rowId, String sectionId, String type, int depth, String marker, String label, String value, boolean dirty, boolean firing, NodeDetails details, CanvasNode previous, CanvasNode sectionSource, CanvasNode typeSource) {
    }

    private record NodeDetails(String search, String requires, String received, String output, String failure, String recovery, String source) {
    }

    private record BrainStats(int nodes, int firing, int running, int done, int blocked, int failed, int pipeline, int execution, int world, int memory, int ktl, int runtime, int tokens, int processors, int dataIn, int dataOut, String strongestLabel, String currentSignal, String activeState, int activeStateColor) {
    }

    private record ActiveState(String label, int color, int priority) {
    }

    private record HoverNode(CanvasNode node, int x, int y) {
    }

    private record HoverLink(CanvasNode source, CanvasNode target, String kind, int lane, String role, String segment, int color, String payload) {
    }

    private record LinkLane(String role, int color, String pattern, String payload, boolean active) {
    }

    private record HoverLine(String text, int color, boolean section) {
        private HoverLine(String text, int color) {
            this(text, color, false);
        }
    }

    private record HoverCue(CanvasNode node, String title, int color, List<String> details) {
    }

    private record TerminalState(boolean terminal, int terminalIndex, boolean failed) {
    }

    private record FrameBand(String id, String displayId, int yStart, int yEnd, int depth, int nodeCount, int failed, int blocked, int running, int firing, int dataIn, int dataOut, int memory, int processors, int tokens, String strongest, String failure, String recovery, String search, String output) {
    }

    private record FrameSegment(int yStart, int yEnd) {
    }

    private record FrameLabelSlot(int left, int right, int top, int bottom) {
    }
}
