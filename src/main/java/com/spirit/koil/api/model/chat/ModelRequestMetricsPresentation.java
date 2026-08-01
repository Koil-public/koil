package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.model.ModelUsage;
import com.spirit.koil.api.model.ModelContextWindowState;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.function.ToIntFunction;

/**
 * Compact, shared request diagnostics for model and Automation chat panels.
 */
public final class ModelRequestMetricsPresentation {
    private ModelRequestMetricsPresentation() {
    }

    /** One-row bottom-popup identity plus optional /ask diagnostics. */
    public static Text bottomHeader(
            ModelGenerationHudState.Snapshot snapshot,
            String modelId,
            int queueDepth,
            int maximumContextTokens
    ) {
        MutableText header = Text.literal("Model").formatted(Formatting.GRAY);
        if (snapshot != null && !snapshot.automationRequest()) {
            header.append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                    .append(automationTopLine(
                            snapshot,
                            modelId,
                            queueDepth,
                            maximumContextTokens
                    ));
        }
        return header;
    }

    /**
     * Width-aware bottom header. The model id is the first optional field:
     * replace it with an ellipsis, then remove that slot entirely, before the
     * session or numeric diagnostics are allowed to fall outside the row.
     */
    public static Text bottomHeaderFitted(
            ModelGenerationHudState.Snapshot snapshot,
            String modelId,
            int queueDepth,
            int maximumContextTokens,
            int maximumWidth,
            ToIntFunction<Text> width
    ) {
        Text full = bottomHeader(snapshot, modelId, queueDepth, maximumContextTokens);
        if (fits(full, maximumWidth, width) || snapshot == null || snapshot.automationRequest()) {
            return full;
        }
        Text abbreviated = bottomHeaderWithModelDisplay(
                snapshot,
                "…",
                true,
                queueDepth,
                maximumContextTokens
        );
        if (fits(abbreviated, maximumWidth, width)) {
            return abbreviated;
        }
        return bottomHeaderWithModelDisplay(
                snapshot,
                "",
                false,
                queueDepth,
                maximumContextTokens
        );
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
        String safeModel = modelId == null || modelId.isBlank() ? "unconfigured" : modelId.strip();
        return automationTopLineWithModelDisplay(
                snapshot,
                safeModel,
                true,
                queueDepth,
                maximumContextTokens,
                afterSession
        );
    }

    /** Width-aware top metrics with model-id-first overflow priority. */
    public static Text automationTopLineFitted(
            ModelGenerationHudState.Snapshot snapshot,
            String modelId,
            int queueDepth,
            int maximumContextTokens,
            Text afterSession,
            int maximumWidth,
            ToIntFunction<Text> width
    ) {
        Text full = automationTopLine(snapshot, modelId, queueDepth, maximumContextTokens, afterSession);
        if (fits(full, maximumWidth, width)) {
            return full;
        }
        Text abbreviated = automationTopLineWithModelDisplay(
                snapshot,
                "…",
                true,
                queueDepth,
                maximumContextTokens,
                afterSession
        );
        if (fits(abbreviated, maximumWidth, width)) {
            return abbreviated;
        }
        return automationTopLineWithModelDisplay(
                snapshot,
                "",
                false,
                queueDepth,
                maximumContextTokens,
                afterSession
        );
    }

    private static Text bottomHeaderWithModelDisplay(
            ModelGenerationHudState.Snapshot snapshot,
            String modelDisplay,
            boolean includeModel,
            int queueDepth,
            int maximumContextTokens
    ) {
        MutableText header = Text.literal("Model").formatted(Formatting.GRAY);
        if (snapshot != null && !snapshot.automationRequest()) {
            header.append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                    .append(automationTopLineWithModelDisplay(
                            snapshot,
                            modelDisplay,
                            includeModel,
                            queueDepth,
                            maximumContextTokens,
                            Text.empty()
                    ));
        }
        return header;
    }

    private static Text automationTopLineWithModelDisplay(
            ModelGenerationHudState.Snapshot snapshot,
            String modelDisplay,
            boolean includeModel,
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

        MutableText line = Text.literal("session ").formatted(Formatting.GRAY)
                .append(Text.literal(String.format("kts-%05d", session)).formatted(Formatting.WHITE));
        if (afterSession != null && !afterSession.getString().isBlank()) {
            line.append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                    .append(afterSession);
        }
        if (includeModel) {
            appendValue(line, modelDisplay);
        }
        appendValue(line, Long.toString(tokens));
        appendValue(line, Integer.toString(Math.max(0, queueDepth)));
        appendValue(line, Integer.toString(tools));
        appendValue(line, Long.toString(ttft));
        appendValue(line, ModelContextWindowState.from(usage, maximumContextTokens)
                .map(state -> " " + state.remainingPercent() + "%")
                .orElse(" --"));
        return line;
    }

    private static boolean fits(Text line, int maximumWidth, ToIntFunction<Text> width) {
        return width == null || width.applyAsInt(line) <= Math.max(0, maximumWidth);
    }

    private static void appendValue(MutableText line, String value) {
        line.append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(value).formatted(Formatting.WHITE));
    }

    /** Live request age for the compact bottom-right popup timer. */
    public static String elapsedLabel(ModelGenerationHudState.Snapshot snapshot, long nowMillis) {
        if (snapshot == null) return "00:00";
        long end = snapshot.completedAtMillis() > 0L
                ? snapshot.completedAtMillis()
                : Math.max(snapshot.createdAtMillis(), nowMillis);
        return formatElapsedMillis(snapshot.createdAtMillis(), end);
    }

    public static String formatElapsedMillis(long startedAtMillis, long endedAtMillis) {
        long seconds = Math.max(0L, endedAtMillis - Math.max(0L, startedAtMillis)) / 1_000L;
        long hours = seconds / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        long remainder = seconds % 60L;
        return hours > 0L
                ? String.format("%d:%02d:%02d", hours, minutes, remainder)
                : String.format("%02d:%02d", minutes, remainder);
    }
}
