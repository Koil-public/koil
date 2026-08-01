package com.spirit.koil.api.chat;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

/**
 * Shared left-to-right highlight used by active model status labels.
 *
 * <p>The top Automation bar uses {@link #styled}. The bottom model popup uses
 * {@link #baseStyled} plus {@link #highlightWindow} so it can paint the same
 * visual as a clipped overlay over one fixed glyph run. That keeps glyph
 * geometry stable while the color band moves.</p>
 */
public final class SlidingStatusText {
    public static final long STEP_MILLIS = 110L;
    public static final int RESTART_PAUSE_STEPS = 4;

    private SlidingStatusText() {
    }

    public static Text styled(String label, String semanticState, int color) {
        return styled(label, semanticState, color, System.currentTimeMillis());
    }

    public static Text styled(String label, String semanticState, int color, long nowMillis) {
        String visible = visibleLabel(label, semanticState);
        if (!working(semanticState)) {
            return Text.literal(visible).styled(style -> style.withColor(color & 0x00FFFFFF));
        }
        int[] colors = characterColors(label, color, nowMillis);
        MutableText text = Text.empty();
        for (int index = 0; index < visible.length(); index++) {
            int characterColor = colors[index];
            text.append(Text.literal(String.valueOf(visible.charAt(index)))
                    .styled(style -> style.withColor(characterColor)));
        }
        return text;
    }

    /** Stable one-run base used by the bottom popup's clipped highlight. */
    public static Text baseStyled(String label, String semanticState, int color) {
        int resolved = working(semanticState)
                ? blend(color & 0x00FFFFFF, 0x4A4F57, 0.68F)
                : color & 0x00FFFFFF;
        return Text.literal(visibleLabel(label, semanticState))
                .styled(style -> style.withColor(resolved));
    }

    public static HighlightWindow highlightWindow(
            String label,
            String semanticState,
            long nowMillis
    ) {
        String visible = visibleLabel(label, semanticState);
        if (!working(semanticState) || visible.isEmpty()) {
            return new HighlightWindow(visible, 0, 0, false);
        }
        int wordLength = Math.max(1, label == null ? 0 : label.length());
        int bandWidth = Math.max(2, Math.min(6, (wordLength + 2) / 3));
        int movementSteps = visible.length() + bandWidth;
        int cycleSteps = movementSteps + RESTART_PAUSE_STEPS;
        int phase = (int) ((Math.max(0L, nowMillis) / STEP_MILLIS) % Math.max(1, cycleSteps));
        if (phase >= movementSteps) {
            return new HighlightWindow(visible, 0, 0, false);
        }
        int bandStart = phase - bandWidth + 1;
        int start = Math.max(0, bandStart);
        int end = Math.min(visible.length(), bandStart + bandWidth);
        return new HighlightWindow(visible, start, Math.max(start, end), end > start);
    }

    public static int transitionColor(int color) {
        int base = color & 0x00FFFFFF;
        int dim = blend(base, 0x4A4F57, 0.68F);
        return blend(base, dim, 0.38F);
    }

    public static boolean working(String semanticState) {
        String normalized = semanticState == null
                ? ""
                : semanticState.trim().toLowerCase(java.util.Locale.ROOT);
        return "thinking".equals(normalized)
                || "waiting".equals(normalized)
                || "running".equals(normalized)
                || "using".equals(normalized)
                || "moving".equals(normalized)
                || "starting".equals(normalized)
                || "inspecting".equals(normalized)
                || "planning".equals(normalized)
                || "executing".equals(normalized)
                || "observing".equals(normalized)
                || "validating".equals(normalized)
                || "retrying".equals(normalized)
                || "replanning".equals(normalized)
                || "finalizing".equals(normalized)
                || "writing".equals(normalized);
    }

    public static String visibleLabel(String label, String semanticState) {
        String word = label == null ? "" : label;
        return working(semanticState) ? word + "..." : word;
    }

    private static int[] characterColors(String label, int color, long nowMillis) {
        String visible = visibleLabel(label, "thinking");
        int wordLength = Math.max(1, label == null ? 0 : label.length());
        int bandWidth = Math.max(2, Math.min(6, (wordLength + 2) / 3));
        int movementSteps = visible.length() + bandWidth;
        int cycleSteps = movementSteps + RESTART_PAUSE_STEPS;
        int phase = (int) ((Math.max(0L, nowMillis) / STEP_MILLIS) % Math.max(1, cycleSteps));
        int bandStart = phase < movementSteps ? phase - bandWidth + 1 : visible.length() + 1;
        int base = color & 0x00FFFFFF;
        int dimColor = blend(base, 0x4A4F57, 0.68F);
        int edgeColor = blend(base, dimColor, 0.38F);
        int[] colors = new int[visible.length()];
        for (int index = 0; index < visible.length(); index++) {
            boolean highlighted = index >= bandStart && index < bandStart + bandWidth;
            boolean edge = index == bandStart - 1 || index == bandStart + bandWidth;
            colors[index] = highlighted ? base : (edge ? edgeColor : dimColor);
        }
        return colors;
    }

    private static int blend(int source, int target, float targetWeight) {
        float weight = Math.max(0.0F, Math.min(1.0F, targetWeight));
        int red = Math.round(((source >> 16) & 0xFF) * (1.0F - weight) + ((target >> 16) & 0xFF) * weight);
        int green = Math.round(((source >> 8) & 0xFF) * (1.0F - weight) + ((target >> 8) & 0xFF) * weight);
        int blue = Math.round((source & 0xFF) * (1.0F - weight) + (target & 0xFF) * weight);
        return (red << 16) | (green << 8) | blue;
    }

    public record HighlightWindow(
            String visibleText,
            int startCharacter,
            int endCharacter,
            boolean active
    ) {
    }

}
