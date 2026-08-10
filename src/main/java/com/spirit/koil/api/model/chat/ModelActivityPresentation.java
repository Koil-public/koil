package com.spirit.koil.api.model.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.cli.AutomationStateColors;
import com.spirit.koil.api.model.ModelDeepThoughtControl;
import com.spirit.koil.api.model.ModelSemanticPalette;

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
        String color = ModelSemanticPalette.section(event.activityState());
        return "-# §8" + ModelActivityTreeGlyphs.BRANCH + "§r " + color + label + "§r | " + event.summary();
    }

    public static String timeline(String objective, List<ModelGenerationHudState.ActivityEvent> events) {
        if (events == null || events.isEmpty()) return "";
        StringBuilder rendered = new StringBuilder("§f")
                .append(compact(objective, 420))
                .append("§r");
        boolean toolOpen = false;
        for (ModelGenerationHudState.ActivityEvent event : events) {
            if (event == null) continue;
            if (toolOpen && isToolEvidence(event.type())) {
                rendered.append("\n-# §8").append(ModelActivityTreeGlyphs.RAIL).append("  ")
                        .append(ModelActivityTreeGlyphs.LAST_BRANCH).append("§r ")
                        .append(event.type() == ModelGenerationHudState.ActivityEventType.FAILURE
                                ? AutomationStateColors.section("failed")
                                : AutomationStateColors.section("observing"))
                        .append(event.type() == ModelGenerationHudState.ActivityEventType.VALIDATION ? "Validation" : "Result")
                        .append("§r | ").append(compact(evidenceSummary(event), 360));
                appendEvidenceDetails(rendered, event.data(), "│     ");
                if (event.type() != ModelGenerationHudState.ActivityEventType.VALIDATION) {
                    toolOpen = false;
                }
                continue;
            }
            rendered.append('\n').append(timelineEvent(event, false));
            if (event.type() == ModelGenerationHudState.ActivityEventType.TOOL_START) {
                String arguments = event.data() == null || !event.data().has("arguments")
                        || !event.data().get("arguments").isJsonObject()
                        ? ""
                        : ModelToolCallPresentation.arguments(event.data().getAsJsonObject("arguments"));
                if (!arguments.isBlank()) {
                    rendered.append("\n-# §8").append(ModelActivityTreeGlyphs.RAIL).append("  ")
                            .append(ModelActivityTreeGlyphs.BRANCH).append("§r ")
                            .append(AutomationStateColors.section("preparing"))
                            .append("Request§r | ").append(compact(arguments, 360));
                }
                toolOpen = true;
            }
        }
        if (toolOpen) {
            rendered.append("\n-# §8").append(ModelActivityTreeGlyphs.RAIL).append("  ")
                    .append(ModelActivityTreeGlyphs.LAST_BRANCH).append("§r ")
                            .append(AutomationStateColors.section("observing"))
                    .append("Status§r | Waiting for structured evidence");
        }
        return rendered.toString();
    }

    private static boolean isToolEvidence(ModelGenerationHudState.ActivityEventType type) {
        return type == ModelGenerationHudState.ActivityEventType.RESULT
                || type == ModelGenerationHudState.ActivityEventType.FAILURE
                || type == ModelGenerationHudState.ActivityEventType.VALIDATION
                || type == ModelGenerationHudState.ActivityEventType.FILE
                || type == ModelGenerationHudState.ActivityEventType.DIFF
                || type == ModelGenerationHudState.ActivityEventType.COMMAND;
    }

    private static String evidenceSummary(ModelGenerationHudState.ActivityEvent event) {
        JsonObject data = event.data();
        String status = string(data, "status");
        String detail = string(data, "detail");
        String failure = string(data, "failureCode");
        StringBuilder value = new StringBuilder(status.isBlank() ? event.summary() : status);
        if (!failure.isBlank()) value.append(" | ").append(failure);
        if (!detail.isBlank()) value.append(" — ").append(detail);
        return value.toString();
    }

    private static void appendEvidenceDetails(StringBuilder rendered, JsonObject data, String rail) {
        if (data == null) return;
        appendEvidenceField(rendered, rail, "Validation", string(data, "validationStatus"));
        appendEvidenceField(rendered, rail, "Command", first(data, "command", "normalizedCommand", "submittedCommand"));
        appendEvidenceField(rendered, rail, "Problem", first(data, "problem", "error", "failure"));
        appendEvidenceField(rendered, rail, "Suggestion", first(data, "suggestion", "suggestions", "recovery"));
        appendEvidenceField(rendered, rail, "Target", first(data, "target", "targetId", "block", "entity"));
        appendEvidenceField(rendered, rail, "Evidence", first(data, "fact", "observation", "validationEvidence"));
    }

    private static void appendEvidenceField(StringBuilder rendered, String rail, String label, String value) {
        if (value.isBlank() || value.equals("not_required")) return;
        rendered.append("\n-# §8").append(rail).append(ModelActivityTreeGlyphs.LAST_BRANCH).append("§r ")
                .append(AutomationStateColors.section(fieldState(label)))
                .append(label).append("§r | ").append(compact(value, 280));
    }

    private static String first(JsonObject data, String... keys) {
        for (String key : keys) {
            String value = string(data, key);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String string(JsonObject data, String key) {
        if (data == null || key == null || !data.has(key)) return "";
        JsonElement value = data.get(key);
        if (value == null || value.isJsonNull()) return "";
        try {
            if (value.isJsonArray()) {
                StringBuilder joined = new StringBuilder();
                for (JsonElement item : value.getAsJsonArray()) {
                    if (!joined.isEmpty()) joined.append(", ");
                    joined.append(item.isJsonPrimitive() ? item.getAsString() : item.toString());
                }
                return joined.toString();
            }
            return value.isJsonPrimitive() ? value.getAsString() : value.toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public static String plan(
            String planId,
            String objective,
            List<ModelGenerationHudState.PlanStep> steps,
            boolean revised
    ) {
        if (steps == null || steps.isEmpty()) return "";
        String safeId = compact(planId, 80);
        StringBuilder rendered = new StringBuilder("-# §8").append(ModelActivityTreeGlyphs.BRANCH).append("§r ")
                .append(AutomationStateColors.section("planning"))
                .append("Plan§r | ")
                .append(compact(objective, 300));
        if (!safeId.isBlank()) rendered.append(" §8[").append(safeId).append(']');
        if (revised) rendered.append(" — revised");
        rendered.append("§r");
        for (int index = 0; index < steps.size(); index++) {
            ModelGenerationHudState.PlanStep step = steps.get(index);
            boolean last = index == steps.size() - 1;
            String rail = last ? "   " : "│  ";
            String shortName = step.summary().isBlank()
                    ? step.toolId().replace('.', ' ').replace('_', ' ')
                    : step.summary();
            rendered.append("\n-# §8│  ")
                    .append(last ? ModelActivityTreeGlyphs.LAST_BRANCH : ModelActivityTreeGlyphs.BRANCH)
                    .append("§r ")
                    .append(stepColor(step.status()))
                    .append(steps.size() > 1
                            ? "Step " + step.index() + "/" + steps.size() + ": "
                            : "")
                    .append(compact(shortName, 180)).append("§r")
                    .append("\n-# §8│  ").append(rail).append("├─§r ")
                    .append(AutomationStateColors.section("executing"))
                    .append("Action§r | ")
                    .append(compact(ModelToolCallPresentation.toolName(step.toolId()), 120));
            if (!step.arguments().isBlank()) {
                rendered.append("\n-# §8│  ").append(rail).append("│  └─§r ")
                        .append(AutomationStateColors.section("preparing"))
                        .append("Request§r | ")
                        .append(compact(step.arguments(), 300));
            }
            rendered
                    .append("\n-# §8│  ").append(rail).append("│  └─§r ")
                    .append(stepColor(step.status()))
                    .append("Status§r | ").append(stepSymbol(step.status())).append(' ')
                    .append(step.status().name().toLowerCase(Locale.ROOT))
                    .append("\n-# §8│  ").append(rail).append("└─§r ")
                    .append(AutomationStateColors.section("observing"))
                    .append("Process§r | ")
                    .append(step.result().isBlank() ? "Awaiting structured result" : compact(step.result(), 240));
            if (!step.expectedObservation().isBlank()) {
                rendered.append("\n-# §8│  ").append(rail).append("   └─§r ")
                        .append(AutomationStateColors.section("observing"))
                        .append("Expected§r | ")
                        .append(compact(step.expectedObservation(), 260));
            }
            if (!step.validationRequirement().isBlank()) {
                rendered.append("\n-# §8│  ").append(rail).append("      └─§r ")
                        .append(AutomationStateColors.section("validating"))
                        .append("Validation§r | ")
                        .append(compact(step.validationRequirement(), 220));
            }
        }
        return rendered.toString();
    }

    public static String deepThought(ModelDeepThoughtControl.Status status, boolean expanded) {
        if (status == null) return "";
        StringBuilder summary = new StringBuilder("**§5Deep Thought§r**")
                .append("\n-# §5= Phase§r — ").append(compact(status.phase(), 80))
                .append("\n-# §3= Evidence§r — ").append(status.evidenceCount())
                .append(" claims | ").append(status.verifiedClaims()).append(" verified / ")
                .append(status.unresolvedClaims()).append(" unresolved")
                .append("\n-# §e= Confidence§r — ").append(compact(status.confidence(), 80));
        if (!status.sessionId().isBlank()) {
            summary.append(" | §8").append(compact(status.sessionId(), 90)).append("§r");
        }
        if (expanded) {
            summary.append("\n-# §5= Investigation§r — ")
                    .append(status.hypothesisCount()).append(" hypotheses | ")
                    .append(status.contradictionCount()).append(" contradictions")
                    .append("\n-# §3= Tests§r — ").append(status.testsPassed()).append(" passed / ")
                    .append(status.testsFailed()).append(" failed | active ")
                    .append(status.activeMillis() / 1000L).append('s');
            if (!status.lastDiscovery().isBlank()) {
                summary.append("\n-# §7= Discovery§r — ").append(compact(status.lastDiscovery(), 420));
            }
        }
        return summary.toString();
    }

    public static TraceSnapshot capture(ModelGenerationHudState.Snapshot snapshot) {
        if (snapshot == null) return TraceSnapshot.empty();
        return new TraceSnapshot(
                snapshot.prompt(),
                snapshot.events(),
                snapshot.plan(),
                snapshot.deepThoughtStatus(),
                false
        );
    }

    /** The same typed trace projection is used by the live panel and final hover. */
    public static String render(TraceSnapshot trace) {
        if (trace == null) return "";
        StringBuilder rendered = new StringBuilder();
        String deep = deepThought(trace.deepThoughtStatus(), trace.expanded());
        if (!deep.isBlank()) rendered.append(deep);
        String timeline = timeline(trace.objective(), trace.events());
        if (!timeline.isBlank()) {
            if (!rendered.isEmpty()) rendered.append('\n');
            rendered.append(timeline);
        }
        ModelGenerationHudState.PlanView plan = trace.plan();
        if (plan != null && plan.steps() != null && !plan.steps().isEmpty()) {
            String planText = plan(plan.planId(), trace.objective(), plan.steps(), plan.revised());
            if (!planText.isBlank()) {
                if (!rendered.isEmpty()) rendered.append('\n');
                rendered.append(planText);
            }
        }
        return rendered.toString();
    }

    public record TraceSnapshot(
            String objective,
            List<ModelGenerationHudState.ActivityEvent> events,
            ModelGenerationHudState.PlanView plan,
            ModelDeepThoughtControl.Status deepThoughtStatus,
            boolean expanded
    ) {
        public TraceSnapshot {
            objective = objective == null ? "" : objective;
            events = events == null ? List.of() : List.copyOf(events);
        }

        public static TraceSnapshot empty() {
            return new TraceSnapshot("", List.of(), null, null, false);
        }
    }

    private static String stepColor(ModelGenerationHudState.PlanStepStatus status) {
        return switch (status == null ? ModelGenerationHudState.PlanStepStatus.PENDING : status) {
            case PENDING -> AutomationStateColors.section("planning");
            case ACTIVE -> AutomationStateColors.section("executing");
            case COMPLETED -> AutomationStateColors.section("complete");
            case FAILED, BLOCKED, CANCELLED -> AutomationStateColors.section("failed");
            case SKIPPED -> AutomationStateColors.section("idle");
            case REVISED -> AutomationStateColors.section("replanning");
        };
    }

    private static String fieldState(String label) {
        return switch (label) {
            case "Validation", "Evidence" -> "validating";
            case "Command" -> "testing";
            case "Problem" -> "failed";
            case "Suggestion" -> "retrying";
            case "Target" -> "inspecting";
            default -> "observing";
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
