package com.spirit.koil.api.automation.navigation;

import java.util.List;

/** Deterministic, side-effect-free travel recommendation; execution still requires an approved plan. */
public final class TravelStrategySelector {
    private TravelStrategySelector() {
    }

    public static Decision select(Observation input) {
        if (input.currentlyMounted() && input.mountUsable()) return new Decision(Strategy.MOUNT, "verified current mount");
        if (input.distance() >= 96.0D && input.elytraEquipped() && input.fireworks() > 0 && input.flightPermitted()) {
            return new Decision(Strategy.ELYTRA, "equipped elytra and verified propulsion");
        }
        if (input.waterFraction() >= 0.55D && input.boatInInventory() && input.boatDeploymentApproved()) {
            return new Decision(Strategy.BOAT, "water-dominant route and verified boat");
        }
        if (input.waterFraction() >= 0.55D && input.boatDeploymentApproved() && input.boatRecipeKnown()) {
            if (input.boatIngredientsVerified()) return new Decision(Strategy.CRAFT_BOAT, "known recipe and verified ingredients");
            return new Decision(Strategy.BLOCKED, "boat ingredients are not verified");
        }
        if (input.waterFraction() >= 0.35D) return new Decision(Strategy.SWIM, "water route");
        if (input.distance() >= 8.0D && input.sprintPermitted() && input.foodLevel() > 6) {
            return new Decision(Strategy.SPRINT, "safe distance and sufficient food");
        }
        return new Decision(Strategy.WALK, "bounded default");
    }

    public record Observation(double distance, double waterFraction, boolean currentlyMounted,
                              boolean mountUsable, boolean elytraEquipped, int fireworks,
                              boolean flightPermitted, boolean boatInInventory,
                              boolean boatDeploymentApproved, boolean boatRecipeKnown,
                              boolean boatIngredientsVerified, boolean sprintPermitted,
                              int foodLevel, List<String> exactInventoryIds) {
        public Observation {
            distance = Math.max(0.0D, distance);
            waterFraction = Math.max(0.0D, Math.min(1.0D, waterFraction));
            fireworks = Math.max(0, fireworks);
            foodLevel = Math.max(0, foodLevel);
            exactInventoryIds = List.copyOf(exactInventoryIds == null ? List.of() : exactInventoryIds);
        }
    }

    public record Decision(Strategy strategy, String evidence) { }
    public enum Strategy { WALK, SPRINT, SWIM, BOAT, CRAFT_BOAT, MOUNT, ELYTRA, BLOCKED }
}
