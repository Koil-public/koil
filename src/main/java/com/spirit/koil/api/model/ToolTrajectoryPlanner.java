package com.spirit.koil.api.model;

import com.spirit.koil.api.model.retrieval.AutomationExecutionExperience;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extracts reusable tool ordering from verified historical objectives. Historical
 * order is advisory only. It can raise prediction priority but cannot authorize,
 * satisfy arguments, or create a hard dependency.
 */
final class ToolTrajectoryPlanner {
    private ToolTrajectoryPlanner() {}

    static Plan plan(List<AutomationExecutionExperience> history, List<String> currentTools) {
        if (history == null || history.isEmpty()) return Plan.empty();
        Map<String, Aggregate> candidates = new LinkedHashMap<>();
        for (AutomationExecutionExperience item : history) {
            if (item == null || !item.verified() || !item.objectiveCompleted() || item.trajectory().isBlank()) continue;
            List<String> tools = parseTools(item.trajectory());
            if (tools.isEmpty()) continue;
            int prefix = prefixMatch(currentTools, tools);
            if (!currentTools.isEmpty() && prefix == 0) continue;
            String key = String.join("\u0000", tools);
            Aggregate aggregate = candidates.computeIfAbsent(key, ignored -> new Aggregate(tools));
            aggregate.samples++;
            aggregate.score += Math.max(0.10D, Math.min(1.0D, item.retrievalScore()));
            aggregate.bestPrefix = Math.max(aggregate.bestPrefix, prefix);
        }
        Aggregate best = null;
        double bestScore = -1.0D;
        for (Aggregate aggregate : candidates.values()) {
            double score = aggregate.score + aggregate.samples * 0.12D + aggregate.bestPrefix * 0.08D;
            if (score > bestScore) { best = aggregate; bestScore = score; }
        }
        if (best == null) return Plan.empty();
        int consumed = Math.min(best.bestPrefix, best.tools.size());
        List<String> remaining = best.tools.subList(consumed, best.tools.size());
        double confidence = Math.min(0.96D, 0.52D + best.samples * 0.08D + best.bestPrefix * 0.03D);
        return new Plan(List.copyOf(best.tools), List.copyOf(remaining), best.samples, confidence);
    }

    static List<String> parseTools(String trajectory) {
        if (trajectory == null || trajectory.isBlank()) return List.of();
        String main = trajectory;
        int dependencies = main.indexOf(" | dependencies:");
        if (dependencies >= 0) main = main.substring(0, dependencies);
        ArrayList<String> tools = new ArrayList<>();
        for (String raw : main.split("\\s*->\\s*")) {
            String step = raw.strip();
            if (step.isBlank()) continue;
            int equals = step.indexOf('=');
            String tool = equals > 0 ? step.substring(0, equals).strip() : "";
            if (!tool.isBlank() && tool.contains(".")) tools.add(tool);
        }
        return List.copyOf(tools);
    }

    private static int prefixMatch(List<String> current, List<String> historical) {
        if (current == null || current.isEmpty()) return 0;
        int count = Math.min(current.size(), historical.size());
        int matched = 0;
        for (int i = 0; i < count; i++) {
            String a = clean(current.get(i));
            String b = clean(historical.get(i));
            if (!a.equals(b)) break;
            matched++;
        }
        return matched;
    }

    private static String clean(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).strip();
    }

    record Plan(List<String> tools, List<String> remainingTools, int samples, double confidence) {
        Plan {
            tools = tools == null ? List.of() : List.copyOf(tools);
            remainingTools = remainingTools == null ? List.of() : List.copyOf(remainingTools);
            samples = Math.max(0, samples);
            confidence = Math.max(0.0D, Math.min(1.0D, confidence));
        }
        static Plan empty() { return new Plan(List.of(), List.of(), 0, 0.0D); }
        int position(String toolId) { return remainingTools.indexOf(toolId); }
        boolean available() { return !remainingTools.isEmpty(); }
    }

    private static final class Aggregate {
        private final List<String> tools;
        private int samples;
        private int bestPrefix;
        private double score;
        private Aggregate(List<String> tools) { this.tools = tools; }
    }
}
