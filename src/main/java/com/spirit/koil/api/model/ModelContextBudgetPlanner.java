package com.spirit.koil.api.model;

import com.spirit.koil.api.context.ContextIntelligenceService;
import com.spirit.koil.api.context.ContextRepresentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pre-provider context budget controller.
 *
 * <p>It estimates the complete request before llama.cpp sees it and performs
 * deterministic, retrievable compaction of older large messages when pressure
 * is high. It never asks the language model to summarize its own context.</p>
 */
final class ModelContextBudgetPlanner {
    private ModelContextBudgetPlanner() {}

    static Plan plan(String scopeId, String objective, String systemPrompt, List<ModelMessage> messages,
                     List<ModelToolDefinition> tools, int maximumContextTokens) {
        int maximum = maximumContextTokens > 0 ? maximumContextTokens : 16_384;
        int reserve = Math.max(1_536, Math.min(4_096, (int) Math.round(maximum * 0.20D)));
        int workingLimit = Math.max(1_024, maximum - reserve);
        List<ModelMessage> current = new ArrayList<>(messages == null ? List.of() : messages);
        int before = estimateTokens(systemPrompt, current, tools);
        int compacted = 0;
        List<String> references = new ArrayList<>();

        if (before > (int) Math.round(workingLimit * 0.78D)) {
            for (int index = 0; index < Math.max(0, current.size() - 3); index++) {
                ModelMessage message = current.get(index);
                if (message.content().length() < 900) continue;
                int targetCharacters = targetCharacters(message, before, workingLimit);
                ContextRepresentation projection = ContextIntelligenceService.automaticProjection(
                        clean(scopeId), "provider-message:" + message.role().name().toLowerCase(Locale.ROOT),
                        message.id().toString(), message.content(), objective, targetCharacters);
                if (projection.content().isBlank() || projection.content().length() >= message.content().length() * 0.90D) continue;
                current.set(index, new ModelMessage(message.id(), message.role(), projection.content(), message.toolCallId(),
                        message.createdAt(), withContextMetadata(message.metadata(), projection)));
                compacted++;
                if (!projection.artifactId().isBlank()) references.add(projection.artifactId());
                int now = estimateTokens(systemPrompt, current, tools);
                if (now <= (int) Math.round(workingLimit * 0.72D)) break;
            }
        }

        int after = estimateTokens(systemPrompt, current, tools);
        int remaining = Math.max(0, maximum - after);
        int percent = (int) Math.round((remaining * 100.0D) / maximum);
        String manifest = "Context preflight: estimated=" + after + "/" + maximum + " tokens; remaining≈" + remaining
                + " (" + percent + "%); generation reserve=" + reserve + "; compacted_messages=" + compacted
                + (references.isEmpty() ? "" : "; retrievable_refs=" + String.join(",", references.stream().limit(4).toList()))
                + ". Treat supplied context as a budgeted evidence window. When context.compress/context.retrieve are supplied this round, "
                + "use them for new large evidence instead of repeating the full body; otherwise Koil will compact automatically before the next provider round. "
                + "Compression is extractive and canonical source remains retrievable.";
        return new Plan(List.copyOf(current), manifest, before, after, maximum, reserve, compacted, List.copyOf(references));
    }


    /**
     * Final tool-schema guard before provider submission. Routing already orders
     * likely-useful tools first, so when an unusually small model context cannot
     * hold every exposed schema we retain that order and shed the least useful
     * tail. Discovery controls are retained whenever possible so the model can
     * deliberately expand again on a later round instead of losing access to
     * the live capability catalog.
     */
    static ToolPlan fitTools(String systemPrompt, List<ModelMessage> messages,
                             List<ModelToolDefinition> tools, int maximumContextTokens) {
        int maximum = maximumContextTokens > 0 ? maximumContextTokens : 16_384;
        int reserve = Math.max(1_536, Math.min(4_096, (int) Math.round(maximum * 0.20D)));
        int workingLimit = Math.max(1_024, maximum - reserve);
        List<ModelToolDefinition> current = new ArrayList<>(tools == null ? List.of() : tools);
        int before = estimateTokens(systemPrompt, messages, current);
        if (before <= workingLimit || current.isEmpty()) {
            return new ToolPlan(List.copyOf(current), before, before, 0);
        }

        int dropped = 0;
        for (int index = current.size() - 1; index >= 0 && estimateTokens(systemPrompt, messages, current) > workingLimit; index--) {
            ModelToolDefinition candidate = current.get(index);
            if (isDiscoveryControl(candidate.id())) continue;
            current.remove(index);
            dropped++;
        }
        for (int index = current.size() - 1; index >= 0 && estimateTokens(systemPrompt, messages, current) > workingLimit; index--) {
            ModelToolDefinition candidate = current.get(index);
            if (isEssentialDiscoveryControl(candidate.id())) continue;
            current.remove(index);
            dropped++;
        }
        int after = estimateTokens(systemPrompt, messages, current);
        return new ToolPlan(List.copyOf(current), before, after, dropped);
    }

    private static boolean isDiscoveryControl(String id) {
        return "tool.search".equals(id) || "tool.inspect".equals(id)
                || "skill.search".equals(id) || "skill.inspect".equals(id)
                || "automation.cancel".equals(id);
    }

    private static boolean isEssentialDiscoveryControl(String id) {
        return "tool.search".equals(id) || "skill.search".equals(id);
    }

    static int estimateTokens(String systemPrompt, List<ModelMessage> messages, List<ModelToolDefinition> tools) {
        long characters = systemPrompt == null ? 0L : systemPrompt.length();
        if (messages != null) for (ModelMessage message : messages) {
            characters += message.content().length() + 48L;
            for (Map.Entry<String, String> entry : message.metadata().entrySet()) characters += entry.getKey().length() + entry.getValue().length() + 8L;
        }
        if (tools != null) for (ModelToolDefinition tool : tools) {
            characters += tool.id().length() + tool.description().length() + tool.inputSchema().toString().length() + 96L;
        }
        // Chat templates/tokenizers vary. 3.55 chars/token is deliberately conservative for JSON/tool-heavy prompts.
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(characters / 3.55D) + (messages == null ? 0 : messages.size() * 6L));
    }

    private static int targetCharacters(ModelMessage message, int estimated, int workingLimit) {
        int excessTokens = Math.max(0, estimated - workingLimit);
        int desiredReduction = Math.max(400, excessTokens * 4);
        int current = message.content().length();
        int target = current - desiredReduction;
        int floor = message.role() == ModelRole.TOOL ? 900 : 600;
        int ceiling = message.role() == ModelRole.TOOL ? 2_400 : 1_800;
        return Math.max(floor, Math.min(ceiling, target));
    }

    private static Map<String, String> withContextMetadata(Map<String, String> prior, ContextRepresentation projection) {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>(prior == null ? Map.of() : prior);
        values.put("context_compacted", "true");
        values.put("context_level", projection.level().name());
        values.put("context_strategy", projection.strategy());
        if (!projection.artifactId().isBlank()) values.put("context_ref", projection.artifactId());
        return Map.copyOf(values);
    }

    private static String clean(String value) { return value == null || value.isBlank() ? "local" : value.strip(); }

    record Plan(List<ModelMessage> messages, String manifest, int estimatedTokensBefore, int estimatedTokensAfter,
                int maximumTokens, int reservedTokens, int compactedMessages, List<String> references) {}

    record ToolPlan(List<ModelToolDefinition> tools, int estimatedTokensBefore, int estimatedTokensAfter,
                    int droppedTools) {}
}
