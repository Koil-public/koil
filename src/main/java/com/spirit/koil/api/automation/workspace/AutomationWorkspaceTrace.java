package com.spirit.koil.api.automation.workspace;

import com.spirit.koil.api.automation.cli.AutomationCliSnapshot;
import com.spirit.koil.api.model.chat.ModelActivityPresentation;

/** Immutable, persistence-safe unit shown by the Automation Workspace. */
public record AutomationWorkspaceTrace(
        String id,
        String kind,
        String title,
        String status,
        long createdAtMillis,
        long updatedAtMillis,
        long completedAtMillis,
        ModelActivityPresentation.TraceSnapshot modelTrace,
        AutomationCliSnapshot executorTrace
) {
    public AutomationWorkspaceTrace {
        id = clean(id, "trace");
        kind = clean(kind, "activity");
        title = clean(title, "Untitled activity");
        status = clean(status, "observing");
        createdAtMillis = Math.max(0L, createdAtMillis);
        updatedAtMillis = Math.max(createdAtMillis, updatedAtMillis);
        completedAtMillis = Math.max(0L, completedAtMillis);
    }

    public boolean active() {
        return completedAtMillis <= 0L && !isTerminal(status);
    }

    public AutomationWorkspaceTrace withExecutor(
            AutomationCliSnapshot snapshot,
            String nextStatus,
            long now
    ) {
        long created = createdAtMillis > 0L ? createdAtMillis : now;
        long completed = isTerminal(nextStatus) ? Math.max(created, now) : 0L;
        return new AutomationWorkspaceTrace(
                id, kind, titleFor(snapshot, title), nextStatus, created, now, completed, modelTrace, snapshot
        );
    }

    static String titleFor(AutomationCliSnapshot snapshot, String fallback) {
        if (snapshot == null || snapshot.rows() == null) return clean(fallback, "Executor activity");
        for (var row : snapshot.rows()) {
            if (row == null) continue;
            if ("raw_input".equals(row.label()) || "objective".equals(row.label())) {
                String value = clean(row.value(), "");
                if (!value.isBlank()) return value;
            }
        }
        return clean(fallback, "Executor activity");
    }

    static boolean isTerminal(String status) {
        String value = clean(status, "").toLowerCase(java.util.Locale.ROOT);
        return value.equals("complete") || value.equals("completed") || value.equals("success")
                || value.equals("failed") || value.equals("blocked") || value.equals("cancelled")
                || value.equals("canceled") || value.equals("interrupted")
                || value.equals("already_satisfied");
    }

    private static String clean(String value, String fallback) {
        String clean = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').strip();
        return clean.isBlank() ? fallback : clean;
    }
}
