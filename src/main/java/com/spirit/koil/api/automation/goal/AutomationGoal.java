package com.spirit.koil.api.automation.goal;

import java.util.Locale;
import java.util.regex.Pattern;

/** Immutable high-level request accepted by the automation goal compiler. */
public record AutomationGoal(Operation operation, String targetId, int count) {
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public enum Operation {
        OBTAIN
    }

    public AutomationGoal {
        operation = operation == null ? Operation.OBTAIN : operation;
        targetId = targetId == null ? "" : targetId.strip().toLowerCase(Locale.ROOT);
        if (!ITEM_ID.matcher(targetId).matches()) {
            throw new IllegalArgumentException("goal target must be an exact namespaced item id");
        }
        if (count < 1) {
            throw new IllegalArgumentException("goal count must be positive");
        }
    }

    public static AutomationGoal obtain(String targetId, int count) {
        return new AutomationGoal(Operation.OBTAIN, targetId, count);
    }
}
