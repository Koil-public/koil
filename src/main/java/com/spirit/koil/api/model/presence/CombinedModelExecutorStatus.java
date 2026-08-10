package com.spirit.koil.api.model.presence;

import com.spirit.koil.api.automation.AutomationRuntimeStatus;
import com.spirit.koil.api.model.ModelActivityState;

/**
 * One truthful projection of the independently owned model and Executor
 * lifecycles. The top Automation surface and local presence underline consume
 * this projection; neither popup loses its own source status.
 */
public final class CombinedModelExecutorStatus {
    private CombinedModelExecutorStatus() {
    }

    public static Snapshot snapshot() {
        long now = System.currentTimeMillis();
        ModelPresenceState.Snapshot model = ModelPresenceState.localRequestSnapshot();
        AutomationRuntimeStatus.Snapshot executor = AutomationRuntimeStatus.snapshot();
        boolean automationEnabled = ModelPresenceState.localAutomationEnabled();

        Candidate modelCandidate = candidate(
                Source.MODEL,
                model.semanticState(),
                "",
                model.active(),
                model.updatedAtMillis(),
                model.visibleAt(now)
        );
        Candidate executorCandidate = candidate(
                Source.EXECUTOR,
                executor.state(),
                executor.detail(),
                executor.active(),
                executor.updatedAtMillis(),
                automationEnabled && executor.visibleAt(now)
        );

        Candidate selected;
        if (modelCandidate.active() && executorCandidate.active()) {
            // Preparing/observing are background Executor lifecycle states. A
            // live model deliberation is the more useful combined summary
            // until the Executor starts a concrete planning/action phase.
            selected = executorDefersToModel(executorCandidate.state())
                    ? modelCandidate
                    : executorCandidate;
        } else if (modelCandidate.active()) {
            selected = modelCandidate;
        } else if (executorCandidate.active()) {
            selected = executorCandidate;
        } else if (modelCandidate.visible() && executorCandidate.visible()) {
            selected = modelCandidate.updatedAtMillis() >= executorCandidate.updatedAtMillis()
                    ? modelCandidate
                    : executorCandidate;
        } else if (modelCandidate.visible()) {
            selected = modelCandidate;
        } else if (executorCandidate.visible()) {
            selected = executorCandidate;
        } else {
            selected = candidate(Source.NONE, "idle", "", false, now, automationEnabled);
        }
        return new Snapshot(
                selected.state(),
                selected.detail(),
                selected.source(),
                selected.active(),
                selected.updatedAtMillis(),
                modelCandidate,
                executorCandidate
        );
    }

    private static Candidate candidate(
            Source source,
            String state,
            String detail,
            boolean active,
            long updatedAtMillis,
            boolean visible
    ) {
        ModelActivityState semantic = ModelActivityState.fromLegacy(state);
        return new Candidate(source, semantic.id(), clean(detail), active, updatedAtMillis, visible);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ')
                .replaceAll("\\s+", " ").strip();
    }

    private static boolean executorDefersToModel(String state) {
        return "idle".equals(state) || "starting".equals(state)
                || "preparing".equals(state) || "observing".equals(state);
    }

    public enum Source { MODEL, EXECUTOR, NONE }

    public record Candidate(
            Source source,
            String state,
            String detail,
            boolean active,
            long updatedAtMillis,
            boolean visible
    ) {
    }

    public record Snapshot(
            String state,
            String detail,
            Source source,
            boolean active,
            long updatedAtMillis,
            Candidate model,
            Candidate executor
    ) {
    }
}
