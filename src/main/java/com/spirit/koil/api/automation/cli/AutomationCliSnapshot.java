package com.spirit.koil.api.automation.cli;

import java.util.List;

public record AutomationCliSnapshot(
        String sessionId,
        String mode,
        String actor,
        String detail,
        List<AutomationCliRow> rows
) {
}
