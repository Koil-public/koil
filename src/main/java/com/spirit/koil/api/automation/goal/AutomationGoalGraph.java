package com.spirit.koil.api.automation.goal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, bounded dependency graph compiled from one high-level goal. */
public final class AutomationGoalGraph {
    private static final int MAXIMUM_NODES = 128;

    private final AutomationGoal goal;
    private final Map<String, Node> nodes;

    private AutomationGoalGraph(AutomationGoal goal, Map<String, Node> nodes) {
        this.goal = goal;
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    }

    public static AutomationGoalGraph of(AutomationGoal goal, Collection<Node> inputNodes) {
        Objects.requireNonNull(goal, "goal");
        if (inputNodes == null || inputNodes.isEmpty()) {
            throw new IllegalArgumentException("goal graph requires at least one node");
        }
        if (inputNodes.size() > MAXIMUM_NODES) {
            throw new IllegalArgumentException("goal graph exceeds " + MAXIMUM_NODES + " nodes");
        }
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (Node node : inputNodes) {
            if (node == null || nodes.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("goal graph contains a duplicate or empty node");
            }
        }
        for (Node node : nodes.values()) {
            for (String dependency : node.dependencies()) {
                if (!nodes.containsKey(dependency)) {
                    throw new IllegalArgumentException("goal graph node " + node.id() + " depends on unknown node " + dependency);
                }
            }
        }
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String nodeId : nodes.keySet()) {
            validateAcyclic(nodeId, nodes, visiting, visited);
        }
        return new AutomationGoalGraph(goal, nodes);
    }

    public static Node node(String id, List<String> dependencies) {
        return new Node(id, "", Map.of(), dependencies, List.of(), Set.of(), "", Verification.none(), 0, 0, 0, "fail", "");
    }

    public AutomationGoal goal() {
        return goal;
    }

    public List<Node> nodes() {
        return List.copyOf(nodes.values());
    }

    public List<String> readyNodeIds(Set<String> completedNodeIds) {
        Set<String> completed = completedNodeIds == null ? Set.of() : Set.copyOf(completedNodeIds);
        List<String> ready = new ArrayList<>();
        for (Node node : nodes.values()) {
            if (!completed.contains(node.id()) && completed.containsAll(node.dependencies())) {
                ready.add(node.id());
            }
        }
        return List.copyOf(ready);
    }

    private static void validateAcyclic(
            String id,
            Map<String, Node> nodes,
            Set<String> visiting,
            Set<String> visited
    ) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) {
            throw new IllegalArgumentException("goal graph contains a dependency cycle at " + id);
        }
        for (String dependency : nodes.get(id).dependencies()) {
            validateAcyclic(dependency, nodes, visiting, visited);
        }
        visiting.remove(id);
        visited.add(id);
    }

    public record Node(
            String id,
            String skillId,
            Map<String, Object> arguments,
            List<String> dependencies,
            List<String> requiredFacts,
            Set<String> resourceLocks,
            String expectedObservation,
            Verification verification,
            int estimatedTicks,
            int risk,
            int retryLimit,
            String failurePolicy,
            String recoverySkill
    ) {
        public Node {
            id = id == null ? "" : id.strip();
            if (id.isEmpty()) throw new IllegalArgumentException("goal graph node id is required");
            skillId = skillId == null ? "" : skillId.strip();
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            requiredFacts = requiredFacts == null ? List.of() : List.copyOf(requiredFacts);
            resourceLocks = resourceLocks == null ? Set.of() : Set.copyOf(resourceLocks);
            expectedObservation = expectedObservation == null ? "" : expectedObservation;
            verification = verification == null ? Verification.none() : verification;
            estimatedTicks = Math.max(0, estimatedTicks);
            risk = Math.max(0, risk);
            retryLimit = Math.max(0, retryLimit);
            failurePolicy = failurePolicy == null || failurePolicy.isBlank() ? "fail" : failurePolicy;
            recoverySkill = recoverySkill == null ? "" : recoverySkill;
        }
    }

    public record Verification(String factKey, String operator, Object expected) {
        public Verification {
            factKey = factKey == null ? "" : factKey;
            operator = operator == null ? "" : operator;
        }

        public static Verification none() {
            return new Verification("", "", null);
        }

        public boolean required() {
            return !factKey.isBlank();
        }
    }
}
