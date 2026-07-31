package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.model.ModelUsage;
import com.spirit.koil.api.model.ModelContextWindowState;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Compact, shared request diagnostics for model and Automation chat panels.
 */
public final class ModelRequestMetricsPresentation {
    private ModelRequestMetricsPresentation() {
    }

    /**
     * Compact styled metrics used by the persistent Automation top panel.
     * Metric meaning is positional and matches the established bottom-panel
     * order: session, model, tokens, queue, tools, TTFT.
     */
    public static Text automationTopLine(
            ModelGenerationHudState.Snapshot snapshot,
            String modelId,
            int queueDepth,
            int maximumContextTokens
    ) {
        return automationTopLine(snapshot, modelId, queueDepth, maximumContextTokens, Text.empty());
    }

    public static Text automationTopLine(
            ModelGenerationHudState.Snapshot snapshot,
            String modelId,
            int queueDepth,
            int maximumContextTokens,
            Text afterSession
    ) {
        ModelUsage usage = snapshot == null || snapshot.usage() == null
                ? ModelUsage.empty()
                : snapshot.usage();
        long session = snapshot == null ? 0L : Math.max(0L, snapshot.sessionNumber());
        long tokens = Math.max(0, usage.promptTokens()) + Math.max(0, usage.completionTokens());
        int tools = snapshot == null ? 0 : Math.max(0, snapshot.toolCallCount());
        long ttft = Math.max(0L, usage.timeToFirstTokenMillis());
        String safeModel = modelId == null || modelId.isBlank() ? "unconfigured" : modelId.strip();

        MutableText line = Text.literal("session ").formatted(Formatting.GRAY)
                .append(Text.literal(String.format("kts-%05d", session)).formatted(Formatting.WHITE));
        if (afterSession != null && !afterSession.getString().isBlank()) {
            line.append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                    .append(afterSession);
        }
        appendValue(line, safeModel);
        appendValue(line, Long.toString(tokens));
        appendValue(line, Integer.toString(Math.max(0, queueDepth)));
        appendValue(line, Integer.toString(tools));
        appendValue(line, Long.toString(ttft));
        appendValue(line, ModelContextWindowState.from(usage, maximumContextTokens)
                .map(state -> " " + state.remainingPercent() + "%")
                .orElse(" --"));
        return line;
    }

    private static void appendValue(MutableText line, String value) {
        line.append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(value).formatted(Formatting.WHITE));
    }
}
