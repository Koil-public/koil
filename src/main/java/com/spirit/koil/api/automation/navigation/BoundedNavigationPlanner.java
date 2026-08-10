package com.spirit.koil.api.automation.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

import static com.spirit.koil.api.automation.navigation.BoundedNavigationSnapshot.Cell;
import static com.spirit.koil.api.automation.navigation.BoundedNavigationSnapshot.Node;

/** Pure weighted 3D A* over a bounded snapshot. It never reads Minecraft world state. */
public final class BoundedNavigationPlanner {
    private static final int[][] HORIZONTAL = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private BoundedNavigationPlanner() {
    }

    public static Plan plan(BoundedNavigationSnapshot snapshot, Request request, BooleanSupplier cancelled) {
        long started = System.nanoTime();
        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(SearchNode::score));
        Map<Node, Double> costs = new HashMap<>();
        Map<Node, Parent> parents = new HashMap<>();
        Node best = request.start();
        double bestDistance = heuristic(best, request.goal());
        open.add(new SearchNode(best, 0.0D, bestDistance));
        costs.put(best, 0.0D);
        int expanded = 0;
        boolean budgetHit = false;

        while (!open.isEmpty()) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                return result(Status.CANCELLED, snapshot, request, parents, best, expanded, started, "cancelled");
            }
            if (expanded >= request.maxNodes() || System.nanoTime() - started >= request.maxNanos()) {
                budgetHit = true;
                break;
            }
            SearchNode current = open.poll();
            if (current.cost() > costs.getOrDefault(current.node(), Double.MAX_VALUE) + 0.0001D) continue;
            expanded++;
            double distance = heuristic(current.node(), request.goal());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = current.node();
            }
            if (current.node().equals(request.goal()) || distance <= request.arrivalDistance()) {
                return result(Status.ARRIVED, snapshot, request, parents, current.node(), expanded, started, "goal_reached");
            }
            for (Transition transition : transitions(snapshot, current.node(), request)) {
                Node next = transition.to();
                double candidate = current.cost() + transition.cost()
                        + threatCost(snapshot, next, request);
                if (candidate >= costs.getOrDefault(next, Double.MAX_VALUE)) continue;
                costs.put(next, candidate);
                parents.put(next, new Parent(current.node(), transition.kind()));
                double score = candidate + heuristic(next, request.goal()) * request.heuristicWeight();
                open.add(new SearchNode(next, candidate, score));
            }
        }

        if (best.equals(request.start())) {
            return result(budgetHit ? Status.BUDGET_EXHAUSTED : Status.BLOCKED, snapshot, request,
                    parents, best, expanded, started, budgetHit ? "budget_exhausted_without_progress" : "no_supported_route");
        }
        return result(budgetHit ? Status.BUDGET_EXHAUSTED : Status.PARTIAL, snapshot, request,
                parents, best, expanded, started, budgetHit ? "bounded_receding_horizon" : "best_local_route");
    }

    private static List<Transition> transitions(BoundedNavigationSnapshot snapshot, Node from, Request request) {
        List<Transition> result = new ArrayList<>(16);
        Cell fromCell = snapshot.cell(from);
        if (fromCell.climbable()) {
            addIfValid(result, snapshot, request, from, new Node(from.x(), from.y() + 1, from.z()), Kind.CLIMB, 1.35D);
            addIfValid(result, snapshot, request, from, new Node(from.x(), from.y() - 1, from.z()), Kind.CLIMB, 1.1D);
        }
        for (int[] offset : HORIZONTAL) {
            int dx = offset[0];
            int dz = offset[1];
            boolean diagonal = dx != 0 && dz != 0;
            if (diagonal && (!occupiable(snapshot, new Node(from.x() + dx, from.y(), from.z()), request)
                    || !occupiable(snapshot, new Node(from.x(), from.y(), from.z() + dz), request))) continue;
            boolean added = false;
            for (int dy = 1; dy >= -3; dy--) {
                Node next = new Node(from.x() + dx, from.y() + dy, from.z() + dz);
                if (!occupiable(snapshot, next, request)) continue;
                Cell cell = snapshot.cell(next);
                Kind kind = movementKind(fromCell, cell, dy, request);
                if (kind == null) continue;
                double base = diagonal ? 1.414D : 1.0D;
                double vertical = dy > 0 ? 0.75D : Math.abs(dy) * 0.35D;
                double terrain = cell.water() ? 1.1D : cell.crouched() ? 0.45D : cell.interactable() ? 1.0D : 0.0D;
                result.add(new Transition(next, kind, base + vertical + terrain));
                added = true;
                break;
            }
            if (!added && request.allowParkour() && !diagonal) {
                for (int length = 2; length <= request.maxParkourGap() + 1; length++) {
                    Node landing = new Node(from.x() + dx * length, from.y(), from.z() + dz * length);
                    if (!occupiable(snapshot, landing, request)) continue;
                    boolean gap = true;
                    for (int step = 1; step < length; step++) {
                        Cell crossed = snapshot.cell(new Node(from.x() + dx * step, from.y(), from.z() + dz * step));
                        if (crossed.supported() || crossed.water() || crossed.climbable()) {
                            gap = false;
                            break;
                        }
                    }
                    if (gap) result.add(new Transition(landing, Kind.PARKOUR, 2.2D + length * 0.7D));
                    break;
                }
            }
        }
        return result;
    }

    private static void addIfValid(List<Transition> result, BoundedNavigationSnapshot snapshot, Request request,
                                   Node from, Node next, Kind kind, double cost) {
        if (occupiable(snapshot, next, request)) result.add(new Transition(next, kind, cost));
    }

    private static boolean occupiable(BoundedNavigationSnapshot snapshot, Node node, Request request) {
        return snapshot.cell(node).occupiable(request.allowInteractions());
    }

    private static Kind movementKind(Cell from, Cell to, int dy, Request request) {
        if (to.interactable() && !to.bodyClear()) return request.allowInteractions() ? Kind.INTERACT : null;
        if (to.climbable() || from.climbable()) return Kind.CLIMB;
        if (to.water() || from.water()) return request.allowSwimming() ? Kind.SWIM : null;
        if (dy > 0) return Kind.JUMP;
        if (dy < 0) return Kind.DROP;
        return to.crouched() ? Kind.CROUCH_EDGE : request.preferSprint() ? Kind.SPRINT : Kind.WALK;
    }

    private static double threatCost(BoundedNavigationSnapshot snapshot, Node node, Request request) {
        double cost = 0.0D;
        for (BoundedNavigationSnapshot.Threat threat : snapshot.threats()) {
            if (!request.explicitTargetId().isBlank() && request.explicitTargetId().equals(threat.id())) continue;
            double distance = Math.max(0.5D, node.distanceTo(threat.position()));
            double weight = switch (threat.kind()) {
                case HOSTILE -> 14.0D;
                case DANGEROUS -> 20.0D;
                case PASSIVE -> 1.8D;
            };
            if (threat.lineOfSight()) weight *= 1.45D;
            cost += weight * Math.max(0.0D, 1.0D - distance / request.threatRadius());
        }
        return cost * Math.max(0.25D, request.historicalRiskWeight());
    }

    private static Plan result(Status status, BoundedNavigationSnapshot snapshot, Request request,
                               Map<Node, Parent> parents, Node end, int expanded, long started, String reason) {
        List<Node> reversed = new ArrayList<>();
        Node cursor = end;
        reversed.add(cursor);
        while (parents.containsKey(cursor)) {
            Parent parent = parents.get(cursor);
            reversed.add(parent.node());
            cursor = parent.node();
        }
        Collections.reverse(reversed);
        List<Waypoint> path = new ArrayList<>(reversed.size());
        for (int index = 0; index < reversed.size(); index++) {
            Node node = reversed.get(index);
            Kind transition = index == 0 ? Kind.START : parents.get(node).kind();
            path.add(new Waypoint(node, transition));
        }
        return new Plan(status, List.copyOf(path), expanded, System.nanoTime() - started,
                snapshot.fingerprint(), reason, heuristic(end, request.goal()));
    }

    private static double heuristic(Node from, Node to) {
        int dx = Math.abs(from.x() - to.x());
        int dz = Math.abs(from.z() - to.z());
        int diagonal = Math.min(dx, dz);
        return diagonal * 1.414D + (Math.max(dx, dz) - diagonal) + Math.abs(from.y() - to.y()) * 0.65D;
    }

    public record Request(Node start, Node goal, int maxNodes, long maxNanos, double heuristicWeight,
                          double arrivalDistance, boolean allowSwimming, boolean allowInteractions,
                          boolean allowParkour, int maxParkourGap, boolean preferSprint,
                          double threatRadius, double historicalRiskWeight, String explicitTargetId) {
        public Request {
            if (start == null || goal == null) throw new IllegalArgumentException("start and goal are required");
            maxNodes = Math.max(1, Math.min(20_000, maxNodes));
            maxNanos = Math.max(100_000L, Math.min(100_000_000L, maxNanos));
            heuristicWeight = Math.max(1.0D, Math.min(3.0D, heuristicWeight));
            arrivalDistance = Math.max(0.0D, arrivalDistance);
            maxParkourGap = Math.max(1, Math.min(4, maxParkourGap));
            threatRadius = Math.max(1.0D, threatRadius);
            historicalRiskWeight = Math.max(0.25D, Math.min(4.0D, historicalRiskWeight));
            explicitTargetId = explicitTargetId == null ? "" : explicitTargetId;
        }
    }

    public record Plan(Status status, List<Waypoint> waypoints, int expandedNodes, long elapsedNanos,
                       long snapshotFingerprint, String reason, double remainingDistance) {
        public Plan {
            waypoints = List.copyOf(waypoints == null ? List.of() : waypoints);
            reason = reason == null ? "" : reason;
        }

        public boolean hasProgress() {
            return waypoints.size() > 1;
        }
    }

    public record Waypoint(Node node, Kind transition) { }
    private record SearchNode(Node node, double cost, double score) { }
    private record Parent(Node node, Kind kind) { }
    private record Transition(Node to, Kind kind, double cost) { }

    public enum Status { ARRIVED, PARTIAL, BLOCKED, BUDGET_EXHAUSTED, CANCELLED }
    public enum Kind { START, WALK, SPRINT, JUMP, DROP, SWIM, CLIMB, CROUCH_EDGE, INTERACT, PARKOUR }
}
