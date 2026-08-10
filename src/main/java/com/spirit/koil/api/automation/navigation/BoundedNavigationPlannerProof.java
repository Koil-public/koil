package com.spirit.koil.api.automation.navigation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.spirit.koil.api.automation.navigation.BoundedNavigationSnapshot.Cell;
import static com.spirit.koil.api.automation.navigation.BoundedNavigationSnapshot.Node;

/** Deterministic proofs for the Minecraft-free route planner and travel selector. */
public final class BoundedNavigationPlannerProof {
    private static final Cell LAND = new Cell(true, true, false, false, false, false, false);
    private static final Cell WATER = new Cell(true, false, true, false, false, false, false);

    private BoundedNavigationPlannerProof() {
    }

    public static void main(String[] args) {
        provesWallAndHoleRouting();
        provesSwimmingAndClimbing();
        provesHostileAvoidanceAndExplicitApproach();
        provesParkourAndBudgets();
        provesTravelSelection();
        System.out.println("Bounded navigation planner proof passed");
    }

    private static void provesWallAndHoleRouting() {
        Map<Node, Cell> cells = flat(-1, 7, -3, 3);
        cells.remove(new Node(2, 0, 0));
        cells.remove(new Node(3, 0, 0));
        BoundedNavigationPlanner.Plan plan = plan(cells, List.of(), new Node(0, 0, 0), new Node(6, 0, 0), false, "");
        require(plan.hasProgress(), "wall/hole route made no progress");
        require(plan.waypoints().stream().noneMatch(point -> !cells.containsKey(point.node())), "route crossed blocked cells");
        require(plan.waypoints().stream().anyMatch(point -> point.node().z() != 0), "route did not go around wall/hole");
    }

    private static void provesSwimmingAndClimbing() {
        Map<Node, Cell> water = new LinkedHashMap<>();
        for (int x = 0; x <= 5; x++) water.put(new Node(x, 0, 0), x == 0 || x == 5 ? LAND : WATER);
        BoundedNavigationPlanner.Plan swim = plan(water, List.of(), new Node(0, 0, 0), new Node(5, 0, 0), false, "");
        require(swim.waypoints().stream().anyMatch(point -> point.transition() == BoundedNavigationPlanner.Kind.SWIM), "swim transition missing");

        Map<Node, Cell> ladder = new LinkedHashMap<>();
        for (int y = 0; y <= 4; y++) ladder.put(new Node(0, y, 0), new Cell(true, true, false, true, false, false, false));
        BoundedNavigationPlanner.Plan climb = plan(ladder, List.of(), new Node(0, 0, 0), new Node(0, 4, 0), false, "");
        require(climb.status() == BoundedNavigationPlanner.Status.ARRIVED, "climb did not arrive");
    }

    private static void provesHostileAvoidanceAndExplicitApproach() {
        Map<Node, Cell> cells = flat(0, 8, -4, 4);
        var threat = new BoundedNavigationSnapshot.Threat("zombie", new Node(4, 0, 0),
                BoundedNavigationSnapshot.Kind.HOSTILE, true);
        BoundedNavigationPlanner.Plan avoid = plan(cells, List.of(threat), new Node(0, 0, 0), new Node(8, 0, 0), false, "");
        require(avoid.waypoints().stream().anyMatch(point -> Math.abs(point.node().z()) >= 2), "hostile avoidance cost was ignored");
        BoundedNavigationPlanner.Plan approach = plan(cells, List.of(threat), new Node(0, 0, 0), new Node(4, 0, 0), false, "zombie");
        require(approach.status() == BoundedNavigationPlanner.Status.ARRIVED, "explicit hostile target was not approachable");
    }

    private static void provesParkourAndBudgets() {
        Map<Node, Cell> gap = new LinkedHashMap<>();
        gap.put(new Node(0, 0, 0), LAND);
        gap.put(new Node(1, 0, 0), new Cell(true, false, false, false, false, false, false));
        gap.put(new Node(2, 0, 0), LAND);
        BoundedNavigationPlanner.Plan parkour = plan(gap, List.of(), new Node(0, 0, 0), new Node(2, 0, 0), true, "");
        require(parkour.waypoints().stream().anyMatch(point -> point.transition() == BoundedNavigationPlanner.Kind.PARKOUR), "parkour transition missing");

        BoundedNavigationSnapshot snapshot = BoundedNavigationSnapshot.of(flat(0, 20, -5, 5), List.of(), "proof", 1L);
        BoundedNavigationPlanner.Request bounded = request(new Node(0, 0, 0), new Node(20, 0, 0), false, "", 1);
        BoundedNavigationPlanner.Plan plan = BoundedNavigationPlanner.plan(snapshot, bounded, () -> false);
        require(plan.expandedNodes() <= 1, "node budget exceeded");
        require(plan.status() == BoundedNavigationPlanner.Status.BUDGET_EXHAUSTED, "budget exhaustion not reported");
        require(BoundedNavigationPlanner.plan(snapshot, request(new Node(0, 0, 0), new Node(20, 0, 0), false, "", 100), () -> true).status()
                == BoundedNavigationPlanner.Status.CANCELLED, "cancellation not honored");
    }

    private static void provesTravelSelection() {
        var craft = TravelStrategySelector.select(new TravelStrategySelector.Observation(
                120, 0.8, false, false, false, 0, false, false,
                true, true, true, true, 20, List.of("minecraft:oak_planks")));
        require(craft.strategy() == TravelStrategySelector.Strategy.CRAFT_BOAT, "verified boat crafting was not selected");
        var blocked = TravelStrategySelector.select(new TravelStrategySelector.Observation(
                120, 0.8, false, false, false, 0, false, false,
                true, true, false, true, 20, List.of()));
        require(blocked.strategy() == TravelStrategySelector.Strategy.BLOCKED, "missing ingredients did not block");
    }

    private static BoundedNavigationPlanner.Plan plan(Map<Node, Cell> cells,
                                                       List<BoundedNavigationSnapshot.Threat> threats,
                                                       Node start, Node goal, boolean parkour, String explicit) {
        return BoundedNavigationPlanner.plan(BoundedNavigationSnapshot.of(cells, threats, "proof", 1L),
                request(start, goal, parkour, explicit, 2_000), () -> false);
    }

    private static BoundedNavigationPlanner.Request request(Node start, Node goal, boolean parkour, String explicit, int nodes) {
        return new BoundedNavigationPlanner.Request(start, goal, nodes, 100_000_000L, 1.15D,
                0.1D, true, false, parkour, 3, true, 6.0D, 1.0D, explicit);
    }

    private static Map<Node, Cell> flat(int minX, int maxX, int minZ, int maxZ) {
        Map<Node, Cell> result = new LinkedHashMap<>();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) result.put(new Node(x, 0, z), LAND);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
