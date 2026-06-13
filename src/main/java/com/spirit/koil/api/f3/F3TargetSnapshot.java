package com.spirit.koil.api.f3;

import java.util.List;

public record F3TargetSnapshot(
        F3TargetType type,
        String title,
        String subtitle,
        String registryId,
        String modOwner,
        String position,
        String danger,
        int accentColor,
        List<F3DataLine> lines,
        List<String> tags,
        List<String> actions
) {
    public static F3TargetSnapshot none() {
        return new F3TargetSnapshot(
                F3TargetType.NONE,
                "No Target",
                "Look at a block, fluid, entity, or item",
                "",
                "",
                "",
                "Unknown",
                F3TargetType.NONE.color(),
                List.of(F3DataLine.state("State", "No target selected", "unknown", 0xFF8D8D8D, "Move the crosshair over something to inspect it.")),
                List.of(),
                List.of()
        );
    }
}
