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

    /** One-row bottom-popup identity plus request-local diagnostics. */
    public static Text bottomHeader(
            ModelGenerationHudState.Snapshot snapshot,
            String modelId,
            int queueDepth,
            int maximumContextTokens
    ) {
        MutableText header = Text.literal(bottomTitle(snapshot)).formatted(Formatting.GRAY);
        if (snapshot != null) {
            header.append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                    .append(bottomMetricsLine(snapshot, modelId, queueDepth, maximumContextTokens));
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
        if (fits(full, maximumWidth, width) || snapshot == null) {
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

    /** Automation's minimal second row: stable session identity plus active mode tags. */
    public static Text automationSessionLine(ModelGenerationHudState.Snapshot snapshot, Text activeTags) {
        // The persistent Automation bar owns the startup-lifetime total. It is
        // deliberately live and is not tied to whichever model request happens
        // to be selected in the under-chat popup.
        com.spirit.koil.api.model.KoilLifetimeCounters.Snapshot counters =
                com.spirit.koil.api.model.KoilLifetimeCounters.snapshot();
        MutableText line = totalCounterLine(counters);
        if (activeTags != null && !activeTags.getString().isBlank()) {
            line.append(Text.literal(" | ").formatted(Formatting.DARK_GRAY)).append(activeTags);
        }
        return line;
    }

    public static Text automationTopLine(
            ModelGenerationHudState.Snapshot snapshot,
            String modelId,
            int queueDepth,
            int maximumContextTokens,
            Text afterSession
    ) {
        String safeModel = modelId == null || modelId.isBlank() ? "unconfigured" : modelId.strip();
        return metricsLineWithModelDisplay(
                snapshot,
                safeModel,
                true,
                queueDepth,
                maximumContextTokens,
                afterSession,
                CounterKind.TOTAL
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
        Text abbreviated = metricsLineWithModelDisplay(
                snapshot,
                "…",
                true,
                queueDepth,
                maximumContextTokens,
                afterSession,
                CounterKind.TOTAL
        );
        if (fits(abbreviated, maximumWidth, width)) {
            return abbreviated;
        }
        return metricsLineWithModelDisplay(
                snapshot,
                "",
                false,
                queueDepth,
                maximumContextTokens,
                afterSession,
                CounterKind.TOTAL
        );
    }

    private static Text bottomHeaderWithModelDisplay(
            ModelGenerationHudState.Snapshot snapshot,
            String modelDisplay,
            boolean includeModel,
            int queueDepth,
            int maximumContextTokens
    ) {
        MutableText header = Text.literal(bottomTitle(snapshot)).formatted(Formatting.GRAY);
        if (snapshot != null) {
            header.append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                    .append(metricsLineWithModelDisplay(
                            snapshot,
                            modelDisplay,
                            includeModel,
                            queueDepth,
                            maximumContextTokens,
                            Text.empty(),
                            bottomCounterKind(snapshot)
                    ));
        }
        return header;
    }

    private static Text bottomMetricsLine(
            ModelGenerationHudState.Snapshot snapshot,
            String modelId,
            int queueDepth,
            int maximumContextTokens
    ) {
        String safeModel = modelId == null || modelId.isBlank() ? "unconfigured" : modelId.strip();
        return metricsLineWithModelDisplay(
                snapshot,
                safeModel,
                true,
                queueDepth,
                maximumContextTokens,
                Text.empty(),
                bottomCounterKind(snapshot)
        );
    }

    private static Text metricsLineWithModelDisplay(
            ModelGenerationHudState.Snapshot snapshot,
            String modelDisplay,
            boolean includeModel,
            int queueDepth,
            int maximumContextTokens,
            Text afterSession,
            CounterKind counterKind
    ) {
        ModelUsage usage = snapshot == null || snapshot.usage() == null
                ? ModelUsage.empty()
                : snapshot.usage();
        com.spirit.koil.api.model.KoilLifetimeCounters.Snapshot counters = counterKind == CounterKind.TOTAL
                ? com.spirit.koil.api.model.KoilLifetimeCounters.snapshot()
                : snapshot == null
                ? com.spirit.koil.api.model.KoilLifetimeCounters.snapshot()
                : snapshot.counters();
        long tokens = Math.max(0, usage.promptTokens()) + Math.max(0, usage.completionTokens());
        int tools = snapshot == null ? 0 : Math.max(0, snapshot.toolCallCount());
        long ttft = Math.max(0L, usage.timeToFirstTokenMillis());

        MutableText line = switch (counterKind) {
            case MODEL -> modelCounterLine(counters);
            case AUTOMATION -> automationCounterLine(counters);
            case TOTAL -> totalCounterLine(counters);
        };
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
                .orElse("100%"));
        return line;
    }

    private static boolean fits(Text line, int maximumWidth, ToIntFunction<Text> width) {
        return width == null || width.applyAsInt(line) <= Math.max(0, maximumWidth);
    }

    private static void appendValue(MutableText line, String value) {
        line.append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(value).formatted(Formatting.WHITE));
    }

    private static MutableText modelCounterLine(com.spirit.koil.api.model.KoilLifetimeCounters.Snapshot counters) {
        com.spirit.koil.api.model.KoilLifetimeCounters.Snapshot safe = counters == null
                ? com.spirit.koil.api.model.KoilLifetimeCounters.snapshot()
                : counters;
        return sessionCounterLine(String.format("kms-%05d", safe.kms()));
    }

    private static MutableText automationCounterLine(com.spirit.koil.api.model.KoilLifetimeCounters.Snapshot counters) {
        com.spirit.koil.api.model.KoilLifetimeCounters.Snapshot safe = counters == null
                ? com.spirit.koil.api.model.KoilLifetimeCounters.snapshot()
                : counters;
        return sessionCounterLine(String.format("kes-%05d", safe.kes()));
    }

    private static MutableText totalCounterLine(com.spirit.koil.api.model.KoilLifetimeCounters.Snapshot counters) {
        com.spirit.koil.api.model.KoilLifetimeCounters.Snapshot safe = counters == null
                ? com.spirit.koil.api.model.KoilLifetimeCounters.snapshot()
                : counters;
        return sessionCounterLine(String.format("kts-%05d", safe.kts()));
    }

    private static MutableText sessionCounterLine(String identifier) {
        return Text.literal("session ").formatted(Formatting.GRAY)
                .append(Text.literal(identifier).formatted(Formatting.WHITE));
    }

    private static String bottomTitle(ModelGenerationHudState.Snapshot snapshot) {
        return "Model";
    }

    private static CounterKind bottomCounterKind(ModelGenerationHudState.Snapshot snapshot) {
        return CounterKind.MODEL;
    }

    private enum CounterKind { MODEL, AUTOMATION, TOTAL }

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
