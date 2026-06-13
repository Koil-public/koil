package com.spirit.koil.api.automation.feedback;

import java.util.List;

public record AutomationFailureType(
        String id,
        String label,
        List<String> applicable_node_types,
        List<String> suggested_fix_rules,
        String auto_fix_rule,
        String patch_generator,
        String learning_hook
) {
    public AutomationFailureType {
        id = id == null ? "" : id;
        label = label == null ? id : label;
        applicable_node_types = applicable_node_types == null ? List.of() : List.copyOf(applicable_node_types);
        suggested_fix_rules = suggested_fix_rules == null ? List.of() : List.copyOf(suggested_fix_rules);
        auto_fix_rule = auto_fix_rule == null ? "" : auto_fix_rule;
        patch_generator = patch_generator == null ? "" : patch_generator;
        learning_hook = learning_hook == null ? "" : learning_hook;
    }
}
