package com.spirit.koil.api.macro;

import java.util.List;
import java.util.UUID;

public record MacroDefinition(
        String id,
        String name,
        boolean enabled,
        MacroTriggerType triggerType,
        int triggerCode,
        List<MacroAction> actions
) {
    public MacroDefinition {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        name = name == null || name.isBlank() ? "New Macro" : name.strip();
        triggerType = triggerType == null ? MacroTriggerType.NONE : triggerType;
        triggerCode = triggerType == MacroTriggerType.NONE ? -1 : triggerCode;
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public static MacroDefinition create() {
        return new MacroDefinition(
                UUID.randomUUID().toString(),
                "New Macro",
                true,
                MacroTriggerType.NONE,
                -1,
                List.of()
        );
    }
}
