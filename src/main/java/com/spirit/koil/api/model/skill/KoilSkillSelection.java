package com.spirit.koil.api.model.skill;

/** A selected bounded guidance projection and its deterministic activation reason. */
public record KoilSkillSelection(KoilSkillDefinition definition, String activation) {
    public KoilSkillSelection {
        if (definition == null) throw new IllegalArgumentException("definition is required");
        activation = activation == null || activation.isBlank() ? "exact" : activation.strip();
    }
}
