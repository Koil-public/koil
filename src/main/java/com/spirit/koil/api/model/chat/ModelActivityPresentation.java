package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.model.ModelDeepThoughtControl;

import java.util.List;
import java.util.Locale;

/**
 * Shared presentation contract for model activity regions.
 *
 * <p>Execution state remains typed outside this class. This class gives each
 * kind of reasoning artifact a distinct visual grammar: dotted activity,
 * connected plan trees, and diamond-marked Deep Thought evidence.</p>
 */
public final class ModelActivityPresentation {
    private ModelActivityPresentation() {
    }

    public static String timelineEvent(ModelGenerationHudState.ActivityEvent event, boolean first) {
        if (event == null) return "";
        String label = switch (event.type()) {
            case THOUGHT_SUMMARY -> "Thought";
            case THOUGHT_STOPPED -> "Stopped thinking";
            case PLAN_STEP -> "Plan update";
            case APPROVAL -> "Approval";
            case TOOL_START -> "Tool";
            case TOOL_PROGRESS -> "Progress";
            case FILE -> "File";
            case DIFF -> "Diff";
            case COMMAND -> "Command";
            case VALIDATION -> "Validation";
            case RESULT -> "Result";
            case FAILURE -> "Failed";
            case REPLAN -> "Replan";
            case CANCELLATION -> "Cancelled";
            case CHECKPOINT -> "Saved";
        };
        String color = switch (event.type()) {
            case THOUGHT_SUMMARY -> "§7";
            case THOUGHT_STOPPED -> "§e";
            case PLAN_STEP, REPLAN -> "§5";
            case APPROVAL, VALIDATION -> "§e";
            case TOOL_START, TOOL_PROGRESS, COMMAND -> "§6";
            case FILE, DIFF, CHECKPOINT -> "§3";
            case RESULT -> "§a";
            case FAILURE, CANCELLATION -> "§c";
        };
        String marker = first ? "•" : "◆";
        return "-# §8" + marker + "§r " + color + label + "§r — " + event.summary()
                + "\n-# §8┊§r";
    }

    public static String plan(
            String planId,
            String objective,
            List<ModelGenerationHudState.PlanStep> steps,
            boolean revised
    ) {
        if (steps == null || steps.isEmpty()) return "";
        String safeId = compact(planId, 80);
        StringBuilder rendered = new StringBuilder("**§5Plan");
        if (!safeId.isBlank()) rendered.append(' ').append(safeId);
        if (revised) rendered.append(" — revised");
        rendered.append("§r**\n-# §5┬─ Goal§r");
        String safeObjective = compact(objective, 300);
        if (!safeObjective.isBlank()) rendered.append(" — ").append(safeObjective);
        for (int index = 0; index < steps.size(); index++) {
            ModelGenerationHudState.PlanStep step = steps.get(index);
            boolean last = index == steps.size() - 1;
            rendered.append("\n-# §5")
                    .append(last ? "└─" : "├─")
                    .append("§r ")
                    .append(stepColor(step.status()))
                    .append(stepSymbol(step.status()))
                    .append(' ')
                    .append(step.index())
                    .append(' ')
                    .append(step.status().name().toLowerCase(Locale.ROOT))
                    .append("§r — ")
                    .append(compact(step.toolId(), 120));
            if (!step.summary().isBlank()) rendered.append(" — ").append(compact(step.summary(), 240));
            if (!step.result().isBlank()) rendered.append(" — ").append(compact(step.result(), 240));
        }
        return rendered.toString();
    }

    public static String deepThought(ModelDeepThoughtControl.Status status, boolean expanded) {
        if (status == null) return "";
        StringBuilder summary = new StringBuilder("**§5Deep Thought§r**")
                .append("\n-# §5◇ Phase§r — ").append(compact(status.phase(), 80))
                .append("\n-# §3◇ Evidence§r — ").append(status.evidenceCount())
                .append(" | claims ").append(status.verifiedClaims()).append(" verified / ")
                .append(status.unresolvedClaims()).append(" unresolved")
                .append("\n-# §e◇ Confidence§r — ").append(compact(status.confidence(), 80));
        if (!status.sessionId().isBlank()) {
            summary.append(" | §8").append(compact(status.sessionId(), 90)).append("§r");
        }
        if (expanded) {
            summary.append("\n-# §5◇ Investigation§r — ")
                    .append(status.hypothesisCount()).append(" hypotheses | ")
                    .append(status.contradictionCount()).append(" contradictions")
                    .append("\n-# §3◇ Tests§r — ").append(status.testsPassed()).append(" passed / ")
                    .append(status.testsFailed()).append(" failed | active ")
                    .append(status.activeMillis() / 1000L).append('s');
            if (!status.lastDiscovery().isBlank()) {
                summary.append("\n-# §7◇ Discovery§r — ").append(compact(status.lastDiscovery(), 420));
            }
        }
        return summary.toString();
    }

    private static String stepColor(ModelGenerationHudState.PlanStepStatus status) {
        return switch (status == null ? ModelGenerationHudState.PlanStepStatus.PENDING : status) {
            case PENDING -> "§8";
            case ACTIVE -> "§6";
            case COMPLETED -> "§a";
            case FAILED, BLOCKED, CANCELLED -> "§c";
            case SKIPPED -> "§7";
            case REVISED -> "§5";
        };
    }

    private static String stepSymbol(ModelGenerationHudState.PlanStepStatus status) {
        return switch (status == null ? ModelGenerationHudState.PlanStepStatus.PENDING : status) {
            case PENDING -> "○";
            case ACTIVE -> "▶";
            case COMPLETED -> "✓";
            case FAILED, CANCELLED -> "✕";
            case BLOCKED -> "!";
            case SKIPPED -> "–";
            case REVISED -> "↻";
        };
    }

    private static String compact(String value, int maximum) {
        String clean = value == null ? "" : value.replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .strip();
        return clean.length() <= maximum ? clean : clean.substring(0, Math.max(0, maximum - 1)) + "…";
    }
}
