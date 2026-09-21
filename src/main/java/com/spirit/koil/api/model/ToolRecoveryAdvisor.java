package com.spirit.koil.api.model;

import com.spirit.koil.api.model.retrieval.AutomationExecutionExperience;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds structured historical recovery evidence; current preflight always remains authoritative. */
final class ToolRecoveryAdvisor {
    private ToolRecoveryAdvisor() {}

    static ToolRecoveryTrajectory advise(List<AutomationExecutionExperience> experience, ModelToolCall call, ModelToolResult result) {
        if (experience == null || experience.isEmpty() || result == null) return empty();
        String signature = signature(call, result);
        Map<String, Aggregate> grouped = new LinkedHashMap<>();
        for (AutomationExecutionExperience item : experience) {
            if (item == null || !item.verified() || !item.objectiveCompleted()) continue;
            String itemSignature = clean(item.recoverySignature());
            List<String> tools = parseRecoveryTools(item.recoverySequence());
            if (tools.isEmpty() && !item.trajectory().isBlank()) {
                Derived derived = derive(item.trajectory());
                itemSignature = itemSignature.isBlank() ? derived.signature() : itemSignature;
                tools = derived.tools();
            }
            if (tools.isEmpty()) continue;
            if (!itemSignature.isBlank() && !matches(signature, itemSignature)) continue;
            List<String> recoveryTools = tools;
            String key = String.join("\u0000", recoveryTools);
            Aggregate aggregate = grouped.computeIfAbsent(key, ignored -> new Aggregate(recoveryTools, item.trajectory()));
            aggregate.samples++;
            aggregate.score += Math.max(0.10D, Math.min(1.0D, item.retrievalScore()));
        }
        Aggregate best = grouped.values().stream()
                .max(Comparator.comparingDouble(a -> a.score + a.samples * 0.15D)).orElse(null);
        if (best == null) return empty();
        double confidence = Math.min(0.97D, 0.55D + best.samples * 0.09D + Math.min(0.18D, best.score * 0.05D));
        return new ToolRecoveryTrajectory(signature, best.tools, best.samples, confidence, best.sourceTrajectory);
    }

    static String hint(List<AutomationExecutionExperience> experience, ModelToolCall call, ModelToolResult result, String currentTrajectory) {
        ToolRecoveryTrajectory recovery = advise(experience, call, result);
        if (!recovery.available()) return "";
        StringBuilder out = new StringBuilder("Historical verified recovery sequence for ")
                .append(recovery.failureSignature()).append(": ")
                .append(String.join(" -> ", recovery.recoveryTools()))
                .append(" (samples=").append(recovery.samples())
                .append(", confidence=").append(String.format(Locale.ROOT, "%.2f", recovery.confidence())).append(')');
        if (currentTrajectory != null && !currentTrajectory.isBlank()) {
            out.append(". Current trajectory: ").append(compact(currentTrajectory, 420));
        }
        out.append(". Treat this only as strategy evidence; resolve arguments from current evidence and revalidate every current precondition before retrying.");
        return out.toString();
    }

    private static String signature(ModelToolCall call, ModelToolResult result) {
        String tool = clean(call == null ? result.toolId() : call.toolId());
        String failure = clean(result.failureCode());
        if (failure.isBlank()) failure = clean(result.validationStatus());
        if (failure.isBlank()) failure = clean(result.status());
        return tool + "|" + failure;
    }

    private static boolean matches(String current, String historical) {
        if (historical.equals(current)) return true;
        String currentTool = current.contains("|") ? current.substring(0, current.indexOf('|')) : current;
        String historicalTool = historical.contains("|") ? historical.substring(0, historical.indexOf('|')) : historical;
        return !currentTool.isBlank() && currentTool.equals(historicalTool);
    }

    static Derived derive(String trajectory) {
        if (trajectory == null || trajectory.isBlank()) return new Derived("", List.of());
        String main = trajectory;
        int dependency = main.indexOf(" | dependencies:");
        if (dependency >= 0) main = main.substring(0, dependency);
        String[] steps = main.split("\\s*->\\s*");
        int failureIndex = -1;
        String signature = "";
        for (int i = 0; i < steps.length; i++) {
            String step = steps[i].strip();
            int equals = step.indexOf('=');
            if (equals <= 0) continue;
            String tool = clean(step.substring(0, equals));
            String state = clean(step.substring(equals + 1));
            int marker = state.indexOf("failure=");
            boolean failed = marker >= 0 || state.startsWith("failed") || state.startsWith("stale")
                    || state.startsWith("rejected") || state.startsWith("partial");
            if (!failed) continue;
            String failure = marker >= 0 ? state.substring(marker + "failure=".length()).replace(")", "").strip() : state;
            signature = tool + "|" + failure;
            failureIndex = i;
            break;
        }
        if (failureIndex < 0) return new Derived("", List.of());
        ArrayList<String> recovery = new ArrayList<>();
        for (int i = failureIndex + 1; i < steps.length; i++) {
            String step = steps[i].strip();
            int equals = step.indexOf('=');
            if (equals > 0) {
                String tool = step.substring(0, equals).strip();
                if (!tool.isBlank()) recovery.add(tool);
            }
        }
        return new Derived(signature, List.copyOf(recovery));
    }

    private static List<String> parseRecoveryTools(String value) {
        if (value == null || value.isBlank()) return List.of();
        ArrayList<String> out = new ArrayList<>();
        for (String tool : value.split("\\s*->\\s*")) if (!tool.isBlank()) out.add(tool.strip());
        return List.copyOf(out);
    }

    private static String compact(String value, int maximum) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return clean.length() <= maximum ? clean : clean.substring(0, maximum - 1).stripTrailing() + "…";
    }

    private static String clean(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static ToolRecoveryTrajectory empty() { return new ToolRecoveryTrajectory("", List.of(), 0, 0.0D, ""); }
    record Derived(String signature, List<String> tools) {}
    private static final class Aggregate {
        private final List<String> tools;
        private final String sourceTrajectory;
        private int samples;
        private double score;
        private Aggregate(List<String> tools, String sourceTrajectory) { this.tools = tools; this.sourceTrajectory = sourceTrajectory; }
    }
}
