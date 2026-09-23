package com.spirit.koil.api.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight session dependency graph for predicted and executed tools. It is
 * coordination state only; it does not execute tools or grant authority.
 */
final class ToolDependencyGraph {
    enum EdgeKind {
        VERIFIED_RESULT,
        STATE_FRESHNESS,
        APPROVAL,
        /** Historical ordering hint only; never blocks execution. */
        TRAJECTORY_SEQUENCE
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Set<Edge> edges = new LinkedHashSet<>();

    synchronized void resetForEpoch(long epoch) {
        nodes.entrySet().removeIf(entry -> entry.getValue().epoch() != epoch);
        edges.removeIf(edge -> !nodes.containsKey(edge.from()) || !nodes.containsKey(edge.to()));
    }

    synchronized void observed(String callId, String toolId, long epoch) {
        if (callId == null || callId.isBlank()) return;
        nodes.put(callId, new Node(callId, clean(toolId), epoch, State.COMPLETED));
    }

    synchronized void predicted(String predictionId, String toolId, long epoch, List<String> sourceCallIds) {
        if (predictionId == null || predictionId.isBlank()) return;
        nodes.put(predictionId, new Node(predictionId, clean(toolId), epoch, State.PREDICTED));
        if (sourceCallIds == null) return;
        for (String source : sourceCallIds) {
            if (source == null || source.isBlank()) continue;
            edges.add(new Edge(source, predictionId, EdgeKind.VERIFIED_RESULT));
        }
    }

    synchronized void seedTrajectory(List<String> toolIds, long epoch) {
        if (toolIds == null || toolIds.isEmpty()) return;
        String previous = null;
        int index = 0;
        for (String toolId : toolIds) {
            if (toolId == null || toolId.isBlank()) continue;
            String id = "trajectory-" + epoch + "-" + (index++) + "-" + clean(toolId);
            nodes.putIfAbsent(id, new Node(id, clean(toolId), epoch, State.PREDICTED));
            if (previous != null) edges.add(new Edge(previous, id, EdgeKind.TRAJECTORY_SEQUENCE));
            previous = id;
        }
    }

    synchronized void executing(String id) {
        Node node = nodes.get(id);
        if (node != null) nodes.put(id, node.withState(State.EXECUTING));
    }

    synchronized void completed(String id) {
        Node node = nodes.get(id);
        if (node != null) nodes.put(id, node.withState(State.COMPLETED));
    }

    synchronized boolean dependenciesSatisfied(String id) {
        for (Edge edge : edges) {
            if (!edge.to().equals(id) || edge.kind() == EdgeKind.TRAJECTORY_SEQUENCE) continue;
            Node source = nodes.get(edge.from());
            if (source == null || source.state() != State.COMPLETED) return false;
        }
        return true;
    }

    synchronized String summary() {
        if (nodes.isEmpty()) return "";
        ArrayList<String> parts = new ArrayList<>();
        for (Node node : nodes.values()) {
            long deps = edges.stream().filter(edge -> edge.to().equals(node.id())).count();
            parts.add(node.toolId() + "=" + node.state().name().toLowerCase() + (deps == 0 ? "" : "[deps=" + deps + "]"));
        }
        return String.join(" -> ", parts);
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private enum State { PREDICTED, EXECUTING, COMPLETED }

    private record Node(String id, String toolId, long epoch, State state) {
        Node withState(State next) { return new Node(id, toolId, epoch, next); }
    }

    private record Edge(String from, String to, EdgeKind kind) {}
}
