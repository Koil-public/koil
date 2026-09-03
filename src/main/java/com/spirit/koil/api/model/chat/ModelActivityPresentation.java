package com.spirit.koil.api.model.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.cli.AutomationStateColors;
import com.spirit.koil.api.model.ModelDeepThoughtControl;
import com.spirit.koil.api.model.ModelSemanticPalette;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared presentation contract for model activity regions.
 *
 * <p>Execution state remains typed outside this class. This class gives each
 * kind of reasoning artifact a distinct visual grammar: dotted activity,
 * connected plan trees, and diamond-marked Deep Thought evidence.</p>
 */
public final class ModelActivityPresentation {
    private static final int MAXIMUM_EVIDENCE_ROWS = 36;
    private static final int MAXIMUM_ARRAY_ITEMS = 12;
    private static final Set<String> SUMMARY_KEYS = Set.of(
            "toolId", "status", "detail", "failureCode", "validationStatus"
    );

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
        return timeline(objective, events, 0L);
    }

    public static String timeline(
            String objective,
            List<ModelGenerationHudState.ActivityEvent> events,
            long requestStartedAtMillis
    ) {
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
                appendEventMetadata(rendered, event, requestStartedAtMillis, "│     ");
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
                appendEventMetadata(rendered, event, requestStartedAtMillis, "│     ");
                toolOpen = true;
            } else {
                appendEvidenceDetails(rendered, event.data(), "│  ");
                appendEventMetadata(rendered, event, requestStartedAtMillis, "│  ");
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
        List<EvidenceRow> rows = new ArrayList<>();
        Set<String> consumed = new LinkedHashSet<>();
        addNamed(rows, consumed, data, "Validation", "validationStatus");
        addNamed(rows, consumed, data, "Command", "command", "normalizedCommand", "submittedCommand");
        addNamed(rows, consumed, data, "Problem", "problem", "error", "failure");
        addNamed(rows, consumed, data, "Suggestion", "suggestion", "suggestions", "recovery");
        addNamed(rows, consumed, data, "Target", "target", "targetId", "block", "entity");
        addNamed(rows, consumed, data, "Evidence", "fact", "observation", "validationEvidence");
        for (var entry : data.entrySet()) {
            if (consumed.contains(entry.getKey()) || SUMMARY_KEYS.contains(entry.getKey())
                    || "arguments".equals(entry.getKey())) continue;
            flattenEvidence(rows, friendly(entry.getKey()), entry.getKey(), entry.getValue(), 0);
            if (rows.size() >= MAXIMUM_EVIDENCE_ROWS) break;
        }
        int count = Math.min(rows.size(), MAXIMUM_EVIDENCE_ROWS);
        for (int index = 0; index < count; index++) {
            EvidenceRow row = rows.get(index);
            appendEvidenceField(rendered, rail, row.label(), row.value(), index == count - 1);
        }
        if (rows.size() > MAXIMUM_EVIDENCE_ROWS) {
            appendEvidenceField(rendered, rail, "More evidence",
                    (rows.size() - MAXIMUM_EVIDENCE_ROWS) + " additional fields retained in the structured result", true);
        }
    }

    private static void appendEventMetadata(
            StringBuilder rendered,
            ModelGenerationHudState.ActivityEvent event,
            long requestStartedAtMillis,
            String rail
    ) {
        if (event == null) return;
        if (!event.eventId().isBlank()) {
            appendEvidenceField(rendered, rail, "Event", event.eventId(), false);
        }
        long elapsed = requestStartedAtMillis <= 0L
                ? 0L
                : Math.max(0L, event.timestampMillis() - requestStartedAtMillis);
        appendEvidenceField(rendered, rail, "Time",
                requestStartedAtMillis <= 0L ? Long.toString(event.timestampMillis()) : "+" + formatDuration(elapsed), true);
    }

    private static void addNamed(
            List<EvidenceRow> rows,
            Set<String> consumed,
            JsonObject data,
            String label,
            String... keys
    ) {
        for (String key : keys) {
            String value = string(data, key);
            if (value.isBlank()) continue;
            rows.add(new EvidenceRow(label, compactStructuredValue(key, value)));
            consumed.add(key);
            return;
        }
    }

    private static void flattenEvidence(
            List<EvidenceRow> rows,
            String label,
            String key,
            JsonElement value,
            int depth
    ) {
        if (value == null || value.isJsonNull() || rows.size() > MAXIMUM_EVIDENCE_ROWS || depth > 4) return;
        if (value.isJsonPrimitive()) {
            rows.add(new EvidenceRow(label, compactStructuredValue(key, value.getAsString())));
            return;
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.entrySet().isEmpty()) {
                rows.add(new EvidenceRow(label, "empty"));
                return;
            }
            for (var entry : object.entrySet()) {
                flattenEvidence(rows, label + " / " + friendly(entry.getKey()), entry.getKey(), entry.getValue(), depth + 1);
                if (rows.size() > MAXIMUM_EVIDENCE_ROWS) return;
            }
            return;
        }
        int index = 0;
        int size = value.getAsJsonArray().size();
        for (JsonElement item : value.getAsJsonArray()) {
            if (index >= MAXIMUM_ARRAY_ITEMS) break;
            flattenEvidence(rows, label + " [" + index + "]", key, item, depth + 1);
            index++;
            if (rows.size() > MAXIMUM_EVIDENCE_ROWS) return;
        }
        if (size > MAXIMUM_ARRAY_ITEMS) {
            rows.add(new EvidenceRow(label, (size - MAXIMUM_ARRAY_ITEMS) + " more entries retained"));
        }
    }

    private static String compactStructuredValue(String key, String value) {
        String clean = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
        String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (normalizedKey.equals("text") || normalizedKey.equals("content") || normalizedKey.equals("body")
                || normalizedKey.equals("html") || normalizedKey.equals("raw") || normalizedKey.endsWith("bytes")) {
            int lines = clean.isEmpty() ? 0 : clean.split("\n", -1).length;
            return clean.length() + " characters" + (lines > 1 ? " / " + lines + " lines" : "");
        }
        return compact(clean, 280);
    }

    private static void appendEvidenceField(
            StringBuilder rendered,
            String rail,
            String label,
            String value,
            boolean last
    ) {
        if (value.isBlank() || value.equals("not_required")) return;
        rendered.append("\n-# §8").append(rail)
                .append(last ? ModelActivityTreeGlyphs.LAST_BRANCH : ModelActivityTreeGlyphs.BRANCH).append("§r ")
                .append(AutomationStateColors.section(fieldState(label)))
                .append(label).append("§r | ").append(compact(value, 280));
    }

    private static String friendly(String key) {
        if (key == null || key.isBlank()) return "Value";
        String spaced = key.replace('_', ' ').replace('-', ' ')
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("\\s+", " ").strip();
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String formatDuration(long millis) {
        if (millis < 1_000L) return millis + "ms";
        return String.format(Locale.ROOT, "%.3fs", millis / 1_000.0D);
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
                false,
                snapshot.usage(),
                snapshot.createdAtMillis(),
                snapshot.completedAtMillis()
        );
    }

    /** The same typed trace projection is used by the live panel and final hover. */
    public static String render(TraceSnapshot trace) {
        if (trace == null) return "";
        StringBuilder rendered = new StringBuilder();
        String metrics = requestMetrics(trace.usage(), trace.createdAtMillis(), trace.completedAtMillis());
        if (!metrics.isBlank()) rendered.append(metrics);
        String deep = deepThought(trace.deepThoughtStatus(), trace.expanded());
        if (!deep.isBlank()) {
            if (!rendered.isEmpty()) rendered.append('\n');
            rendered.append(deep);
        }
        String timeline = timeline(trace.objective(), trace.events(), trace.createdAtMillis());
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

    public static String requestMetrics(
            com.spirit.koil.api.model.ModelUsage usage,
            long createdAtMillis,
            long completedAtMillis
    ) {
        if (usage == null) usage = com.spirit.koil.api.model.ModelUsage.empty();
        long end = completedAtMillis > 0L ? completedAtMillis : System.currentTimeMillis();
        long elapsed = createdAtMillis <= 0L ? 0L : Math.max(0L, end - createdAtMillis);
        if (usage.promptTokens() <= 0 && usage.completionTokens() <= 0 && usage.reusedPrefixTokens() <= 0
                && usage.queueMillis() <= 0L && usage.timeToFirstTokenMillis() <= 0L
                && usage.tokensPerSecond() <= 0.0D && elapsed <= 0L) return "";
        StringBuilder value = new StringBuilder("-# §8").append(ModelActivityTreeGlyphs.BRANCH).append("§r ")
            .append(AutomationStateColors.section("observing")).append("Request metrics§r");
        value.append("\n-# §8│  ├─§r §7Prompt tokens§r | ").append(usage.promptTokens());
        value.append("\n-# §8│  ├─§r §7Output tokens§r | ").append(usage.completionTokens());
        value.append("\n-# §8│  ├─§r §7Reused prefix§r | ").append(usage.reusedPrefixTokens()).append(" tokens");
        value.append("\n-# §8│  ├─§r §7Queue§r | ").append(formatDuration(usage.queueMillis()));
        value.append("\n-# §8│  ├─§r §7First token§r | ").append(formatDuration(usage.timeToFirstTokenMillis()));
        value.append("\n-# §8│  ├─§r §7Average speed§r | ")
            .append(String.format(Locale.ROOT, "%.2f output tokens/s", usage.tokensPerSecond()));
        value.append("\n-# §8│  └─§r §7Elapsed§r | ").append(formatDuration(elapsed));
        return value.toString();
    }

    private record EvidenceRow(String label, String value) {
    }

    public record TraceSnapshot(
            String objective,
            List<ModelGenerationHudState.ActivityEvent> events,
            ModelGenerationHudState.PlanView plan,
            ModelDeepThoughtControl.Status deepThoughtStatus,
            boolean expanded,
            com.spirit.koil.api.model.ModelUsage usage,
            long createdAtMillis,
            long completedAtMillis
    ) {
        public TraceSnapshot {
            objective = objective == null ? "" : objective;
            events = events == null ? List.of() : List.copyOf(events);
            usage = usage == null ? com.spirit.koil.api.model.ModelUsage.empty() : usage;
            createdAtMillis = Math.max(0L, createdAtMillis);
            completedAtMillis = Math.max(0L, completedAtMillis);
        }

        public TraceSnapshot(
                String objective,
                List<ModelGenerationHudState.ActivityEvent> events,
                ModelGenerationHudState.PlanView plan,
                ModelDeepThoughtControl.Status deepThoughtStatus,
                boolean expanded
        ) {
            this(objective, events, plan, deepThoughtStatus, expanded,
                    com.spirit.koil.api.model.ModelUsage.empty(), 0L, 0L);
        }

        public static TraceSnapshot empty() {
            return new TraceSnapshot("", List.of(), null, null, false,
                    com.spirit.koil.api.model.ModelUsage.empty(), 0L, 0L);
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
