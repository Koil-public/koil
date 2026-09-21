package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.model.voice.ModelVoiceService;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared renderer-side materialization state for streamed local-model output.
 *
 * The generated/stored model text is never mutated. Renderers opt into a transient
 * Minecraft-native obfuscated trail behind a cursor while characters arrive. The
 * trail is paced from live token cadence when usage is available and falls back to
 * observed text-delta cadence otherwise. Fast output shifts toward Koil's cyan
 * "speed" tint and settles back into the original formatting as generation slows.
 */
public final class ModelOutputMaterialization {
    private static final int MAXIMUM_TRACKED_REQUESTS = 256;
    private static final int MAXIMUM_GLITCH_GLYPHS = 52;
    private static final int INITIAL_GLITCH_GLYPHS = 5;
    private static final int APPROXIMATE_GLYPHS_PER_TOKEN = 4;
    private static final long MINIMUM_REVEAL_MILLIS = 12L;
    private static final long MAXIMUM_REVEAL_MILLIS = 180L;
    private static final long DEFAULT_REVEAL_MILLIS = 62L;
    private static final long POST_COMPLETION_SETTLE_MILLIS = 720L;
    private static final long POST_COMPLETION_CURSOR_MILLIS = 840L;
    private static final long SPEED_DECAY_GRACE_MILLIS = 90L;
    private static final double SPEED_DECAY_MILLIS = 520.0D;
    private static final long STATE_IDLE_EVICTION_MILLIS = 120_000L;
    private static final long WORD_LOCK_DELAY_MAX_MILLIS = 140L;
    private static final long WORD_LOCK_PULSE_MILLIS = 210L;
    private static final long PUNCTUATION_IMPACT_MILLIS = 170L;
    private static final long PARAGRAPH_IGNITION_MILLIS = 260L;
    private static final long BURST_COMPRESSION_MILLIS = 220L;
    private static final long LINE_WAKE_MILLIS = 300L;
    private static final long PRESENTATION_SOURCE_LEAD_MIN_MILLIS = 10L;
    private static final long PRESENTATION_SOURCE_LEAD_MAX_MILLIS = 96L;
    private static final long PRESENTATION_CHARACTER_MIN_MILLIS = 7L;
    private static final long PRESENTATION_CHARACTER_MAX_MILLIS = 80L;
    private static final long PRESENTATION_CURSOR_CYCLE_MIN_MILLIS = 38L;
    private static final double PRESENTATION_APPROX_CHARS_PER_TOKEN = 3.85D;
    private static final double PRESENTATION_MIN_CHARS_PER_SECOND = 14.0D;
    private static final double PRESENTATION_MAX_CHARS_PER_SECOND = 280.0D;
    private static final double PRESENTATION_MAX_CATCHUP_MULTIPLIER = 12.0D;
    private static final int MAX_SOURCE_OFFSET_X = 104;
    private static final int MIN_SOURCE_OFFSET_X = 40;
    private static final int MAX_SOURCE_OFFSET_Y = 10;
    private static final int MAX_SOURCE_WORD_GLYPHS = 12;
    private static final int MAX_VISUAL_SOURCE_GLYPHS = 20;
    private static final int MAX_ACTIVE_SOURCE_WAVES = 4;
    private static final int SOURCE_CHAT_FIELD_EXTRA_X = 52;
    private static final int SOURCE_CHAT_FIELD_SIDE_JITTER = 22;
    private static final int SOURCE_CHAT_TOP_MIN_BANDS = 2;
    private static final int SOURCE_CHAT_TOP_MAX_BANDS = 6;
    private static final int CURSOR_PIXEL_WIDTH = 7;
    private static final int CURSOR_PIXEL_HEIGHT = 9;
    private static final double SPEED_EFFECT_START_TPS = 14.0D;
    private static final double SPEED_EFFECT_FULL_TPS = 56.0D;
    private static final double CHAOS_EFFECT_START_TPS = 20.0D;
    private static final double INSTABILITY_EFFECT_START_TPS = 30.0D;
    private static final int SPEED_TINT = 0x24FFE6;
    private static final int SPEED_HIGHLIGHT = 0xC9FFF8;
    private static final int SPEED_GLOW = 0x00FFD9;
    private static final int CURSOR_BASE = 0xF2F4F7;
    private static final int SOURCE_BASE = 0xF4F6F8;
    private static final int LOCK_HIGHLIGHT = 0xF4FFFD;
    private static final String SOURCE_GLYPH = "x";
    private static final String SOURCE_MOTE_GLYPH = "·";

    private static final ConcurrentHashMap<UUID, State> STATES = new ConcurrentHashMap<>();
    private static final AtomicInteger CLEANUP_TICK = new AtomicInteger();

    private ModelOutputMaterialization() {
    }

    public static Frame frame(UUID requestId, String generatedText, boolean active) {
        return frame(requestId, generatedText, active, 0.0D, 0);
    }

    /**
     * Observes the current generated text and live model throughput. completionTokens
     * is used as the authoritative cadence signal when available; the renderer falls
     * back to text deltas for providers that do not report streaming usage.
     */
    public static Frame frame(
            UUID requestId,
            String generatedText,
            boolean active,
            double tokensPerSecond,
            int completionTokens
    ) {
        return frameInternal(requestId, generatedText, active, tokensPerSecond, completionTokens, false);
    }

    /**
     * Returns the same materialization frame while also exposing the renderer-side
     * staged text boundary. Detached surfaces such as Automation Workspace use this
     * so incoming source glyphs visibly reach the assembly cursor before the newly
     * generated word becomes readable, matching the bottom chat popup instead of
     * rendering the raw stream immediately and showing only its obfuscated tail.
     */
    public static Frame frameForPresentation(
            UUID requestId,
            String generatedText,
            boolean active,
            double tokensPerSecond,
            int completionTokens
    ) {
        return frameInternal(requestId, generatedText, active, tokensPerSecond, completionTokens, true);
    }

    private static Frame frameInternal(
            UUID requestId,
            String generatedText,
            boolean active,
            double tokensPerSecond,
            int completionTokens,
            boolean includePresentedText
    ) {
        if (requestId == null) return Frame.settled();
        long now = System.currentTimeMillis();
        State state = STATES.computeIfAbsent(requestId, State::new);
        boolean voicePresentationActive = ModelVoiceService.hasPresentedStream(requestId);
        Frame frame = state.observe(
                generatedText == null ? "" : generatedText,
                active,
                Math.max(0.0D, tokensPerSecond),
                Math.max(0, completionTokens),
                now,
                includePresentedText || voicePresentationActive
        );
        if (voicePresentationActive) {
            ModelVoiceService.syncPresentedText(requestId, frame.presentedText());
        }
        maybeCleanup(now);
        return frame;
    }

    /** Convenience key for renderer caches. Calling this also observes new text. */
    public static int animationRevision(UUID requestId, String generatedText, boolean active) {
        return frame(requestId, generatedText, active).revision();
    }

    /**
     * Applies a frame to an already formatted Text object. This occurs after Rich
     * Chat formatting so markdown, attachments, masked links, tables, and styles
     * are never parsed from obfuscated source text.
     *
     * Prefer {@link #applyWrappedTail(List, Frame, int)} when text has already been
     * wrapped. That path changes only the trailing rendered glyphs and does not
     * invalidate line layout on every animation step.
     */
    public static Text apply(Text formatted, Frame frame, int protectedCharacters) {
        if (formatted == null || frame == null || !frame.animating()) {
            return formatted;
        }

        List<Glyph> glyphs = new ArrayList<>();
        formatted.visit((style, value) -> {
            String safe = value == null ? "" : value;
            for (int offset = 0; offset < safe.length();) {
                int codePoint = safe.codePointAt(offset);
                glyphs.add(new Glyph(codePoint, style == null ? Style.EMPTY : style));
                offset += Character.charCount(codePoint);
            }
            return Optional.empty();
        }, Style.EMPTY);
        if (glyphs.isEmpty()) return formatted;

        int safeProtected = Math.max(0, protectedCharacters);
        int eligible = 0;
        for (int index = safeProtected; index < glyphs.size(); index++) {
            if (glitchEligible(glyphs.get(index).codePoint())) eligible++;
        }

        MutableText result = Text.empty();
        StringBuilder run = new StringBuilder();
        Style runStyle = null;
        int seenEligible = 0;
        for (int index = 0; index < glyphs.size(); index++) {
            Glyph glyph = glyphs.get(index);
            Style style = glyph.style();
            if (index >= safeProtected && glitchEligible(glyph.codePoint())) {
                int tailIndex = Math.max(0, eligible - 1 - seenEligible++);
                style = styleForTail(style, frame, tailIndex);
            }
            if (runStyle != null && !runStyle.equals(style)) {
                appendRun(result, run, runStyle);
                run.setLength(0);
            }
            runStyle = style;
            run.appendCodePoint(glyph.codePoint());
        }
        appendRun(result, run, runStyle == null ? Style.EMPTY : runStyle);
        return result;
    }

    /**
     * Restyles only the wrapped tail containing the materialization region. Line
     * breaks and attachment layout remain untouched, which makes this appropriate
     * for high-frequency popup rendering.
     */
    public static List<OrderedText> applyWrappedTail(
            List<OrderedText> wrapped,
            Frame frame,
            int protectedCharacters
    ) {
        if (wrapped == null || wrapped.isEmpty() || frame == null || !frame.animating()) {
            return wrapped == null ? List.of() : wrapped;
        }

        int lockReach = frame.lockStrength() <= 0.001F
                ? 0
                : Math.max(0, frame.lockGapGlyphs()) + Math.max(0, frame.lockGlyphs());
        int requested = Math.max(
                Math.max(0, frame.glitchGlyphs()) + Math.max(0, frame.glowGlyphs()),
                lockReach
        );
        if (requested <= 0) return wrapped;

        int startLine = wrapped.size() - 1;
        int selectedEligible = 0;
        for (int index = wrapped.size() - 1; index >= 0; index--) {
            int protect = index == 0 ? Math.max(0, protectedCharacters) : 0;
            selectedEligible += eligibleGlyphs(wrapped.get(index), protect);
            startLine = index;
            if (selectedEligible >= requested) break;
        }
        int selected = Math.min(requested, selectedEligible);
        if (selected <= 0) return wrapped;

        TransformState state = new TransformState(
                Math.max(0, selectedEligible - selected), selected);
        List<OrderedText> result = new ArrayList<>(wrapped);
        for (int index = startLine; index < wrapped.size(); index++) {
            int protect = index == 0 ? Math.max(0, protectedCharacters) : 0;
            result.set(index, transformLine(wrapped.get(index), protect, frame, state));
        }
        return List.copyOf(result);
    }

    /**
     * Draws the single-cell assembly press cursor plus transient source matter.
     *
     * Random obfuscated matter spawns at a burst-stable point well to the right,
     * visually inside the surrounding chat space, and is pulled into the press at
     * a throughput-dependent velocity. The source remains bounded and decorative:
     * it never mutates or consumes characters from previous chat messages.
     */
    public static int drawCursor(DrawContext context, TextRenderer renderer, int x, int y, Frame frame) {
        if (context == null || renderer == null || frame == null || !frame.cursorVisible()) return x;

        double speed = speedEffectStrength(frame.effectiveTokensPerSecond());
        double chaos = clamp01(frame.chaosStrength());
        double instability = clamp01(frame.instabilityStrength());
        long now = System.currentTimeMillis();
        int phase = (int) ((now / 42L) & 0x7FFFFFFF);
        double pulse = 0.5D + 0.5D * Math.sin(now / 88.0D);

        // One-character-cell assembly press cursor. It is intentionally broken into
        // separate pixel plates rather than drawn as one icon or filled rectangle.
        // During a source burst the plates close toward the center, briefly squeeze,
        // then reopen for the next incoming word. This makes the cursor itself read as
        // the mechanism building the text while preserving a fixed glyph-sized footprint.
        double sourceProgress = clamp01(frame.sourceProgress());
        double cursorProgress = clamp01(frame.cursorAssemblyProgress());
        int cursorY = y + Math.max(0, (renderer.fontHeight - CURSOR_PIXEL_HEIGHT) / 2);
        if (instability > 0.78D && ((phase + frame.sourceSeed()) & 7) == 0) {
            cursorY += Math.floorMod(frame.sourceSeed() + phase, 3) - 1;
        }
        // White/neutral is the default visual language. The press only becomes
        // Koil-cyan once the real throughput crosses the speed-effect threshold.
        int cursorColor = blendRgb(CURSOR_BASE, SPEED_HIGHLIGHT,
                clamp01(speed * (0.34D + 0.18D * pulse)));
        int energyColor = blendRgb(CURSOR_BASE,
                blendRgb(SPEED_TINT, SPEED_HIGHLIGHT, clamp01(0.20D + 0.38D * speed)),
                clamp01(speed * (0.58D + 0.24D * pulse)));
        drawAssemblyPressCursor(
                context,
                x,
                cursorY,
                phase,
                cursorProgress,
                frame.cursorAssembling(),
                frame.burstStrength(),
                frame.lockStrength(),
                frame.presentationPending(),
                sourceProgress,
                speed,
                cursorColor,
                energyColor
        );
        drawAssemblyEjectGlyphs(
                context,
                renderer,
                x,
                cursorY,
                cursorProgress,
                frame.cursorAssembling(),
                frame.lockStrength(),
                speed,
                cursorColor,
                energyColor,
                phase
        );

        // Source harvesting is deliberately pipelined. Each staged word owns a
        // separate wave and older waves keep travelling while newer waves spawn.
        // This prevents the visual stream from going empty between words and makes
        // the incoming characters read as a continuous supply feeding the response.
        List<SourceWaveFrame> sourceWaves = frame.sourceWaves();
        if (sourceWaves == null || sourceWaves.isEmpty()) {
            // Compatibility fallback for a frame created before overlapping waves
            // existed. Normal live frames always populate sourceWaves.
            sourceWaves = frame.sourceProgress() >= 0.999F
                    ? List.of()
                    : List.of(new SourceWaveFrame(
                    frame.sourceSeed(),
                    frame.sourceOffsetX(),
                    frame.sourceOffsetY(),
                    frame.sourceGlyphCount(),
                    frame.sourceAttractionMillis(),
                    frame.sourceSpawnIntervalMillis(),
                    frame.sourceCycleMillis(),
                    frame.sourceProgress()
            ));
        }
        double compression = clamp01(frame.burstStrength());
        int waveOrdinal = 0;
        for (SourceWaveFrame wave : sourceWaves) {
            drawSourceWave(
                    context, renderer, x, y, wave, waveOrdinal++, compression,
                    speed, chaos, instability, pulse, phase
            );
        }

        // Punctuation/paragraph/burst energy is communicated through tiny fading
        // text sparks and the existing glyph coloring. No broad cyan background,
        // underline, box, or filled wake is drawn behind the generated text.
        float punctuation = frame.punctuationPulse();
        float paragraph = frame.paragraphPulse();
        float eventStrength = Math.max(punctuation, Math.max(paragraph, frame.burstStrength() * 0.65F));
        if (eventStrength > 0.04F) {
            int sparks = 1 + (eventStrength > 0.62F ? 1 : 0);
            for (int i = 0; i < sparks; i++) {
                int seed = mix32(frame.sourceSeed() + phase * 19 + i * 53);
                int sx = x - 1 - Math.floorMod(seed, 5);
                int sy = y - 1 + Math.floorMod(seed >>> 8, Math.max(2, renderer.fontHeight + 2));
                int energized = i == 0 ? SPEED_HIGHLIGHT : SPEED_TINT;
                int sparkColor = blendRgb(SOURCE_BASE, energized, clamp01(speed * 0.88D));
                int alpha = Math.min(225, 70 + (int) Math.round(eventStrength * 140.0F));
                Text spark = Text.literal(SOURCE_MOTE_GLYPH).setStyle(Style.EMPTY.withColor(sparkColor));
                context.drawText(renderer, spark, sx, sy, argb(alpha, sparkColor), false);
            }
        }

        return x + CURSOR_PIXEL_WIDTH;
    }

    private static void drawSourceWave(
            DrawContext context,
            TextRenderer renderer,
            int x,
            int y,
            SourceWaveFrame wave,
            int waveOrdinal,
            double compression,
            double speed,
            double chaos,
            double instability,
            double pulse,
            int phase
    ) {
        if (wave == null) return;
        double sourceProgress = clamp01(wave.progress());
        if (sourceProgress >= 0.999D) return;

        int sourceDx = Math.max(MIN_SOURCE_OFFSET_X, Math.min(MAX_SOURCE_OFFSET_X, wave.offsetX()));
        int cloudCount = visualSourceGlyphCount(wave.glyphCount());
        int lineStep = Math.max(renderer.fontHeight + 2, 10);
        List<ModelOutputHarvestField.Cell> harvestCells = ModelOutputHarvestField.sampleAbove(
                x, y, cloudCount, wave.seed());
        if (harvestCells.isEmpty()) return;

        long attraction = Math.max(1L, wave.attractionMillis());
        long spawnInterval = Math.max(1L, wave.spawnIntervalMillis());
        long cycle = Math.max(1L, wave.cycleMillis());
        long sourceAge = Math.max(0L, Math.round(sourceProgress * cycle));

        for (int i = 0; i < cloudCount; i++) {
            int seed = mix32(wave.seed() + i * 0x9E3779B9);
            long jitter = i == 0 ? 0L : Math.floorMod(seed >>> 3, 17);
            long spawnAt = i * spawnInterval + jitter;
            if (sourceAge < spawnAt) continue;
            double localProgress = clamp01((sourceAge - spawnAt) / (double) attraction);

            ModelOutputHarvestField.Cell harvested = harvestCells.get(i % harvestCells.size());
            int topBand = SOURCE_CHAT_TOP_MIN_BANDS
                    + Math.floorMod(seed >>> 7, SOURCE_CHAT_TOP_MAX_BANDS - SOURCE_CHAT_TOP_MIN_BANDS + 1);
            int harvestX = harvested.x() + Math.max(1, harvested.width() / 2);
            int harvestY = harvested.y() - 3;
            int sourceCodePoint = harvested.codePoint();

            double harvestEnd = 0.42D + Math.floorMod(seed >>> 20, 11) / 100.0D;
            double travelProgress = localProgress <= harvestEnd
                    ? 0.0D
                    : clamp01((localProgress - harvestEnd) / Math.max(0.10D, 1.0D - harvestEnd));
            int driftX = Math.floorMod(seed >>> 17, 7) - 3;
            int driftY = Math.floorMod(seed >>> 22, 7) - 3;
            int gx;
            int gy;
            if (travelProgress <= 0.001D) {
                double birth = clamp01(localProgress / Math.max(0.01D, harvestEnd));
                gx = harvestX + (int) Math.round(birth * 2.0D);
                gy = harvestY - (int) Math.round(birth * 2.0D);
            } else {
                double t = Math.pow(clamp01(travelProgress), 1.82D);
                int waveSpread = Math.min(18, waveOrdinal * 4);
                int swingX = Math.max(harvestX, x) + Math.max(24, (sourceDx * 3) / 4)
                        + Math.floorMod(seed >>> 25, 24) + waveSpread;
                int swingY = Math.min(y - 2, harvestY + Math.max(4, topBand));
                double omt = 1.0D - t;
                double curveX = omt * omt * harvestX + 2.0D * omt * t * swingX + t * t * x;
                double curveY = omt * omt * harvestY + 2.0D * omt * t * swingY + t * t * y;
                double settle = 1.0D - t;
                gx = (int) Math.round(curveX + driftX * settle * (1.0D - 0.45D * compression));
                gy = (int) Math.round(curveY + driftY * settle * (1.0D - 0.45D * compression));
            }

            int speedColor = blendRgb(SPEED_TINT, SPEED_HIGHLIGHT,
                    clamp01(0.20D + chaos * 0.46D + pulse * 0.10D));
            int sourceColor = blendRgb(SOURCE_BASE, speedColor,
                    clamp01(speed * (0.72D + 0.22D * chaos)));
            double fade = 1.0D - travelProgress;
            int alpha = travelProgress <= 0.001D
                    ? 226
                    : Math.min(252, 92 + (int) Math.round(102.0D * fade
                    + 42.0D * localProgress + 26.0D * chaos));
            String sourceCharacter = new String(Character.toChars(sourceCodePoint));
            Style sourceStyle = Style.EMPTY.withColor(sourceColor);
            if (localProgress > 0.14D) sourceStyle = sourceStyle.withObfuscated(true);
            Text randomGlyph = Text.literal(sourceCharacter).setStyle(sourceStyle);

            if (travelProgress > 0.02D && travelProgress < 0.94D) {
                ModelOutputHarvestField.Cell hovered = ModelOutputHarvestField.nearestCell(
                        gx, gy, 8, Math.max(5, lineStep / 2));
                if (hovered != null) {
                    int overlayColor = argb(Math.min(236, alpha), sourceColor);
                    Text hoveredGlyph = Text.literal(new String(Character.toChars(hovered.codePoint())))
                            .setStyle(Style.EMPTY.withColor(sourceColor).withObfuscated(true));
                    context.drawTextWithShadow(renderer, hoveredGlyph, hovered.x(), hovered.y(), overlayColor);
                }
            }
            context.drawTextWithShadow(renderer, randomGlyph, gx, gy, argb(alpha, sourceColor));
        }

        // Each wave carries a small set of motes so overlap remains visually
        // continuous even between the larger harvested characters.
        int motes = Math.min(4, 1 + cloudCount / 4 + (int) Math.round(chaos));
        for (int i = 0; i < motes; i++) {
            double stagger = (i / (double) Math.max(1, motes)) * 0.52D;
            double p = clamp01((sourceProgress - stagger) / Math.max(0.18D, 1.0D - stagger));
            ModelOutputHarvestField.Cell moteSource = harvestCells.get(i % harvestCells.size());
            int moteStartX = moteSource.x() + Math.max(1, moteSource.width() / 2);
            int moteStartY = moteSource.y() - 2;
            int px = x + (int) Math.round((moteStartX - x) * (1.0D - p));
            int py = y + (int) Math.round((moteStartY - y) * (1.0D - p));
            int seed = mix32(wave.seed() * 31 + i * 97 + phase * 7);
            if (instability > 0.08D) {
                int jitter = instability > 0.52D ? 2 : 1;
                px += Math.floorMod(seed, jitter * 2 + 1) - jitter;
                py += Math.floorMod(seed >>> 7, 3) - 1;
            }
            int alpha = Math.min(220, 72 + Math.floorMod(seed, 52)
                    + (int) Math.round(70.0D * speed));
            int energized = (i & 1) == 0 ? SPEED_HIGHLIGHT : SPEED_GLOW;
            int moteColor = blendRgb(SOURCE_BASE, energized,
                    clamp01(speed * (0.74D + 0.14D * chaos)));
            Text mote = Text.literal(SOURCE_MOTE_GLYPH).setStyle(Style.EMPTY.withColor(moteColor));
            context.drawText(renderer, mote, px, py, argb(alpha, moteColor), false);
        }
    }

    private static void drawAssemblyPressCursor(
            DrawContext context,
            int x,
            int y,
            int phase,
            double assemblyProgress,
            boolean assembling,
            float burstStrength,
            float wordLockStrength,
            boolean presentationPending,
            double sourceProgress,
            double speedStrength,
            int metalColor,
            int energyColor
    ) {
        // Compact [ ... ] assembly cursor. The brackets and center dots crunch
        // horizontally for each presented character. When the full word becomes
        // visible, the same brackets perform a stronger word-complete slam.
        double progress = assembling ? clamp01(assemblyProgress) : 0.0D;
        double lock = clamp01(wordLockStrength);
        double burst = clamp01(burstStrength);
        double idlePulse = 0.5D + 0.5D * Math.sin(phase * 0.33D);

        // Per-character crunch: open -> crush -> release. Word lock overrides
        // this with a stronger inward slam only after the full word is visible.
        double characterCrunch = assembling
                ? Math.sin(Math.PI * clamp01(progress))
                : 0.0D;
        double slam = lock <= 0.001D ? 0.0D : clamp01(lock * 1.18D);
        double dotCrunch = Math.max(characterCrunch * 0.72D, slam);

        int shadow = argb(158, 0x10161C);
        int bracketRgb = blendRgb(metalColor, energyColor,
                clamp01(speedStrength * (0.50D + 0.18D * idlePulse)));
        int bracket = 0xFF000000 | bracketRgb;
        int softBracket = argb(212, blendRgb(0xE8ECF0, bracketRgb, 0.70D));
        int dotRgb = blendRgb(metalColor, energyColor,
                clamp01(0.08D + speedStrength * (0.76D + burst * 0.12D)));

        int cx = x + CURSOR_PIXEL_WIDTH / 2;
        int cy = y + CURSOR_PIXEL_HEIGHT / 2;
        // Character cycles work only the center dots. The brackets themselves
        // stay open until the word-complete lock pulse, then slam inward once.
        int bracketInset = slam > 0.52D ? 2 : slam > 0.18D ? 1 : 0;
        int left = x + bracketInset;
        int right = x + CURSOR_PIXEL_WIDTH - 1 - bracketInset;
        int top = y + 1;
        int bottom = y + CURSOR_PIXEL_HEIGHT - 2;

        // [ and ] stay recognizable at every phase. They never balloon outside
        // the one-cell footprint; the motion is entirely inward.
        drawCursorSegment(context, left, top, 1, Math.max(3, bottom - top + 1), shadow, bracket);
        drawCursorSegment(context, left + 1, top, 1, 1, shadow, softBracket);
        drawCursorSegment(context, left + 1, bottom, 1, 1, shadow, softBracket);
        drawCursorSegment(context, right, top, 1, Math.max(3, bottom - top + 1), shadow, bracket);
        drawCursorSegment(context, right - 1, top, 1, 1, shadow, softBracket);
        drawCursorSegment(context, right - 1, bottom, 1, 1, shadow, softBracket);

        // Dots exist while source matter is being gathered / waiting to become
        // a character. During the crush they collapse toward the center, then
        // pop out as the character is emitted. They repopulate for the next slot.
        boolean sourceVisible = presentationPending && sourceProgress < 0.999D;
        double dotLife;
        if (slam > 0.001D) {
            dotLife = 1.0D;
        } else if (assembling) {
            if (progress < 0.54D) dotLife = 1.0D;
            else if (progress < 0.72D) dotLife = 1.0D - (progress - 0.54D) / 0.18D;
            else dotLife = 0.0D;
        } else {
            dotLife = sourceVisible || presentationPending ? 0.72D + idlePulse * 0.28D : 0.42D;
        }
        dotLife = clamp01(dotLife);

        int spread = dotCrunch > 0.72D ? 0 : dotCrunch > 0.28D ? 1 : 2;
        int moving = ((phase >> 1) & 1) == 0 ? 0 : 1;
        int dotAlpha = Math.max(0, (int) Math.round(dotLife * (188.0D + 54.0D * idlePulse)));
        if (dotAlpha > 8) {
            int[] offsets = {-spread, 0, spread};
            for (int i = 0; i < offsets.length; i++) {
                int dx = cx + offsets[i];
                int dy = cy + ((i == 1 ? moving : 0) - (i == 0 && moving == 1 ? 1 : 0));
                int alpha = Math.max(60, dotAlpha - i * 12);
                context.fill(dx, dy, dx + 1, dy + 1, argb(alpha, dotRgb));
            }
        }

        // Tiny center flash at peak character crunch or word slam. It remains a
        // point effect rather than a filled background box.
        if ((assembling && characterCrunch > 0.72D) || slam > 0.36D) {
            int flashRgb = blendRgb(dotRgb, energyColor, clamp01(speedStrength * 0.82D + slam * 0.38D));
            int alpha = Math.min(255, 152 + (int) Math.round(88.0D * Math.max(characterCrunch, slam)));
            context.fill(cx, cy, cx + 1, cy + 1, argb(alpha, flashRgb));
        }
    }

    private static void drawAssemblyEjectGlyphs(
            DrawContext context,
            TextRenderer renderer,
            int x,
            int y,
            double assemblyProgress,
            boolean assembling,
            float wordLockStrength,
            double speedStrength,
            int metalColor,
            int energyColor,
            int phase
    ) {
        double progress = assembling ? clamp01(assemblyProgress) : 0.0D;
        double lock = clamp01(wordLockStrength);
        int coreX = x + CURSOR_PIXEL_WIDTH / 2;
        int coreY = y + CURSOR_PIXEL_HEIGHT / 2;

        // Normal per-character spit. It starts as the center dots disappear and
        // travels mainly left so the assembled glyph has a visible handoff into
        // the response text.
        if (assembling && progress >= 0.48D) {
            double eject = clamp01((progress - 0.48D) / 0.46D);
            int particles = 3 + (eject > 0.30D ? 1 : 0) + (eject > 0.64D ? 1 : 0);
            for (int i = 0; i < particles; i++) {
                double stagger = i * 0.10D;
                double p = clamp01((eject - stagger) / Math.max(0.17D, 1.0D - stagger));
                if (p <= 0.001D) continue;
                int px = coreX - 1 - (int) Math.round(p * (4.0D + i * 2.2D));
                int py = coreY + ((phase + i) % 3) - 1;
                int rgb = blendRgb(metalColor, energyColor,
                        clamp01(0.10D + speedStrength * (i == 0 ? 0.98D : 0.74D)));
                int alpha = Math.max(148, 250 - i * 16 - (int) Math.round(p * 20.0D));
                context.fill(px, py, px + (i <= 1 ? 2 : 1), py + 1, argb(alpha, rgb));
                Text mote = Text.literal("·").setStyle(Style.EMPTY.withColor(rgb));
                context.drawText(renderer, mote, px - 2, py - 3 + (i & 1),
                        argb(Math.max(136, alpha - 16), rgb), false);
            }
        }

        // Full-word completion event. The brackets are already slamming inward
        // in drawAssemblyPressCursor(); here the center dots explode outward.
        // Particles radiate in several directions, with extra left-biased matter
        // to visually land the completed word into the response.
        if (lock > 0.015D) {
            double blast = Math.sin(Math.PI * clamp01(lock));
            blast = Math.max(blast, lock * 0.72D);
            int count = 7 + (lock > 0.46D ? 3 : 0) + (speedStrength > 0.45D ? 2 : 0);
            for (int i = 0; i < count; i++) {
                int seed = mix32(phase * 131 + i * 0x9E3779B9 + x * 17 + y * 31);
                double angle = (Math.PI * 2.0D * i / Math.max(1, count))
                        + (Math.floorMod(seed, 23) - 11) * 0.018D;
                double leftBias = i < 4 ? 1.35D : 1.0D;
                double distance = (2.0D + Math.floorMod(seed >>> 6, 5)) * blast;
                int px = coreX + (int) Math.round(Math.cos(angle) * distance * leftBias)
                        - (i < 4 ? (int) Math.round(3.0D * blast) : 0);
                int py = coreY + (int) Math.round(Math.sin(angle) * distance * 0.72D);
                int rgb = blendRgb(metalColor, energyColor,
                        clamp01(0.12D + speedStrength * 0.86D));
                int alpha = Math.max(102, (int) Math.round(235.0D * blast) - i * 5);
                if (alpha <= 0) continue;
                context.fill(px, py, px + 1, py + 1, argb(alpha, rgb));
                if ((i & 1) == 0) {
                    Text mote = Text.literal("·").setStyle(Style.EMPTY.withColor(rgb));
                    context.drawText(renderer, mote, px - 1, py - 3,
                            argb(Math.max(96, alpha - 18), rgb), false);
                }
            }
        }
    }

    private static void drawCursorSegment(
            DrawContext context,
            int x,
            int y,
            int width,
            int height,
            int shadowColor,
            int foregroundColor
    ) {
        context.fill(x + 1, y + 1, x + width + 1, y + height + 1, shadowColor);
        context.fill(x, y, x + width, y + height, foregroundColor);
    }

    /** Compatibility overload for older render sites. */
    public static int drawCursor(DrawContext context, TextRenderer renderer, int x, int y) {
        return drawCursor(context, renderer, x, y, Frame.cursorOnly());
    }

    /**
     * Reserve only the actual one-character assembly cursor. The source cloud is
     * deliberately a visual overlay that can originate far inside the existing
     * chat area; reserving its full travel distance would unnecessarily shrink
     * and rewrap every model response line.
     */
    public static int cursorWidth(TextRenderer renderer) {
        if (renderer == null) return 0;
        return CURSOR_PIXEL_WIDTH + 3;
    }

    public static int cursorWidth(TextRenderer renderer, Frame frame) {
        return frame != null && frame.cursorVisible() ? cursorWidth(renderer) : 0;
    }

    /** Clears transient state once a request is explicitly forgotten/dismissed. */
    public static void forget(UUID requestId) {
        if (requestId != null) STATES.remove(requestId);
    }

    private static OrderedText transformLine(
            OrderedText source,
            int protectedCharacters,
            Frame frame,
            TransformState state
    ) {
        if (source == null) return Text.empty().asOrderedText();
        MutableText result = Text.empty();
        StringBuilder run = new StringBuilder();
        Style[] runStyle = new Style[] {null};
        int[] glyphIndex = new int[] {0};
        source.accept((index, incomingStyle, codePoint) -> {
            Style style = incomingStyle == null ? Style.EMPTY : incomingStyle;
            int localIndex = glyphIndex[0]++;
            if (localIndex >= protectedCharacters && glitchEligible(codePoint)) {
                int seen = state.seenEligible++;
                if (seen >= state.skipEligible) {
                    int relative = state.selectedOrdinal++;
                    int tailIndex = Math.max(0, state.selectedEligible - 1 - relative);
                    style = styleForTail(style, frame, tailIndex);
                }
            }
            if (runStyle[0] != null && !runStyle[0].equals(style)) {
                appendRun(result, run, runStyle[0]);
                run.setLength(0);
            }
            runStyle[0] = style;
            run.appendCodePoint(codePoint);
            return true;
        });
        appendRun(result, run, runStyle[0] == null ? Style.EMPTY : runStyle[0]);
        return result.asOrderedText();
    }

    private static Style styleForTail(Style original, Frame frame, int tailIndex) {
        Style style = original == null ? Style.EMPTY : original;
        int glitch = Math.max(0, frame.glitchGlyphs());
        int glow = Math.max(0, frame.glowGlyphs());
        if (tailIndex < glitch) {
            double gradient = 1.0D - tailIndex / (double) Math.max(1, glitch);
            style = materializingStyle(style, frame.tintStrength(), gradient);
        } else if (tailIndex < glitch + glow) {
            int glowIndex = tailIndex - glitch;
            double gradient = 1.0D - glowIndex / (double) Math.max(1, glow);
            style = settlingStyle(style, frame.tintStrength(), frame.wakeStrength(), gradient);
        }

        if (frame.lockStrength() > 0.001F
                && tailIndex >= Math.max(0, frame.lockGapGlyphs())
                && tailIndex < Math.max(0, frame.lockGapGlyphs()) + Math.max(0, frame.lockGlyphs())) {
            int ordinal = tailIndex - Math.max(0, frame.lockGapGlyphs());
            double gradient = 1.0D - ordinal / (double) Math.max(1, frame.lockGlyphs());
            style = wordLockStyle(style, frame.lockStrength(), gradient, frame.tintStrength());
        }
        return style;
    }

    private static Style settlingStyle(
            Style original,
            float tintStrength,
            float wakeStrength,
            double gradient
    ) {
        Style base = original == null ? Style.EMPTY : original;
        int originalRgb = base.getColor() == null ? 0xE6EDF5 : base.getColor().getRgb();
        double g = clamp01(gradient);
        double speed = clamp01(tintStrength);
        double wake = clamp01(wakeStrength);
        long phase = System.currentTimeMillis() / 37L;
        double shimmer = ((phase + (long) Math.floor(g * 11.0D)) & 1L) == 0L ? 0.045D : 0.0D;
        // Every response gets a very small settling wake. The saturated cyan part
        // is still gated by real throughput, so slow output remains restrained.
        double neutralWake = wake * (0.08D + 0.16D * g);
        double speedWake = speed * (0.16D + 0.50D * g + shimmer);
        double strength = clamp01(neutralWake + speedWake);
        int target = blendRgb(SPEED_TINT, SPEED_HIGHLIGHT,
                0.08D + 0.28D * speed * g + 0.08D * wake);
        return base.withColor(blendRgb(originalRgb, target, strength));
    }

    private static Style wordLockStyle(Style original, float lockStrength, double gradient, float tintStrength) {
        Style base = original == null ? Style.EMPTY : original;
        int originalRgb = base.getColor() == null ? 0xE6EDF5 : base.getColor().getRgb();
        double lock = clamp01(lockStrength);
        double g = clamp01(gradient);
        int energetic = blendRgb(LOCK_HIGHLIGHT, SPEED_HIGHLIGHT, clamp01(tintStrength) * 0.38D);
        double strength = lock * (0.42D + 0.34D * g);
        return base.withColor(blendRgb(originalRgb, energetic, strength));
    }

    private static Style materializingStyle(Style original, float tintStrength, double gradient) {
        Style base = original == null ? Style.EMPTY : original;
        int originalRgb = base.getColor() == null ? 0xE6EDF5 : base.getColor().getRgb();
        double g = clamp01(gradient);
        double speed = clamp01(tintStrength);
        long phase = System.currentTimeMillis() / 29L;
        double chaosFlash = ((phase + (long) Math.floor(g * 17.0D)) % 5L == 0L) ? 0.18D * speed : 0.0D;
        double strength = speed * (0.42D + 0.58D * g);
        int target = blendRgb(SPEED_TINT, SPEED_HIGHLIGHT, clamp01(0.16D + 0.42D * speed * g + chaosFlash));
        int tint = blendRgb(originalRgb, target, strength);
        return base.withColor(tint).withObfuscated(true);
    }

    private static int blendRgb(int from, int to, double amount) {
        double t = clamp01(amount);
        int fr = (from >>> 16) & 0xFF;
        int fg = (from >>> 8) & 0xFF;
        int fb = from & 0xFF;
        int tr = (to >>> 16) & 0xFF;
        int tg = (to >>> 8) & 0xFF;
        int tb = to & 0xFF;
        int r = (int) Math.round(fr + (tr - fr) * t);
        int g = (int) Math.round(fg + (tg - fg) * t);
        int b = (int) Math.round(fb + (tb - fb) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static int mix32(int value) {
        int x = value;
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }

    private static void appendRun(MutableText target, StringBuilder run, Style style) {
        if (target == null || run == null || run.isEmpty()) return;
        target.append(Text.literal(run.toString()).setStyle(style == null ? Style.EMPTY : style));
    }

    private static boolean glitchEligible(int codePoint) {
        // Materialization is visual, so eligibility follows visible glyphs rather
        // than Java's letter/digit categories. This intentionally includes
        // punctuation and symbols such as °, %, $, mathematical operators, etc.
        // Invisible formatting/control code points stay stable.
        if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) return false;
        int type = Character.getType(codePoint);
        return type != Character.FORMAT
                && type != Character.SPACE_SEPARATOR
                && type != Character.LINE_SEPARATOR
                && type != Character.PARAGRAPH_SEPARATOR
                && type != Character.SURROGATE
                && type != Character.UNASSIGNED;
    }

    private static int eligibleGlyphs(String value) {
        if (value == null || value.isEmpty()) return 0;
        int count = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (glitchEligible(codePoint)) count++;
            offset += Character.charCount(codePoint);
        }
        return count;
    }

    private static int eligibleGlyphs(OrderedText value, int protectedCharacters) {
        if (value == null) return 0;
        int[] glyph = new int[] {0};
        int[] eligible = new int[] {0};
        value.accept((index, style, codePoint) -> {
            if (glyph[0]++ >= protectedCharacters && glitchEligible(codePoint)) eligible[0]++;
            return true;
        });
        return eligible[0];
    }

    private static int commonPrefixLength(String left, String right) {
        String a = left == null ? "" : left;
        String b = right == null ? "" : right;
        int maximum = Math.min(a.length(), b.length());
        int index = 0;
        while (index < maximum && a.charAt(index) == b.charAt(index)) {
            index++;
        }
        // Never split a surrogate pair at the presentation boundary.
        if (index > 0 && index < a.length() && Character.isHighSurrogate(a.charAt(index - 1))) {
            index--;
        }
        return index;
    }

    private static int eligibleGlyphsAdded(String previous, String current) {
        String oldValue = previous == null ? "" : previous;
        String newValue = current == null ? "" : current;
        if (newValue.startsWith(oldValue)) {
            return eligibleGlyphs(newValue.substring(oldValue.length()));
        }
        int total = eligibleGlyphs(newValue);
        return Math.min(MAXIMUM_GLITCH_GLYPHS, total);
    }

    /**
     * Counts the visible glyphs in the word/chunk currently being assembled. A
     * trailing whitespace boundary keeps the immediately completed word so its
     * source cloud can finish travelling during the short post-word settle.
     * Work is bounded to 64 code points and the returned render budget is capped.
     */
    private static int sourceGlyphCountForWord(String value) {
        if (value == null || value.isEmpty()) return 1;
        int offset = value.length();
        int scanned = 0;
        // Skip trailing whitespace so a just-completed word retains its cloud.
        while (offset > 0 && scanned < 64) {
            int cp = value.codePointBefore(offset);
            if (!Character.isWhitespace(cp) && !Character.isISOControl(cp)) break;
            offset -= Character.charCount(cp);
            scanned++;
        }
        int count = 0;
        while (offset > 0 && scanned < 64) {
            int cp = value.codePointBefore(offset);
            if (Character.isWhitespace(cp) || Character.isISOControl(cp)) break;
            if (glitchEligible(cp)) count++;
            offset -= Character.charCount(cp);
            scanned++;
            if (count >= MAX_SOURCE_WORD_GLYPHS) break;
        }
        return Math.max(1, Math.min(MAX_SOURCE_WORD_GLYPHS, count));
    }

    private static boolean wordCodePoint(int codePoint) {
        if (Character.isLetterOrDigit(codePoint)) return true;
        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK) {
            return true;
        }
        return codePoint == '\'' || codePoint == '’' || codePoint == '_' || codePoint == '-';
    }

    /**
     * Finds the most recently completed word whose terminating boundary was part
     * of the latest text change. The returned end index is expressed in eligible
     * rendered glyphs so the lock highlight can continue tracking that word while
     * newer characters arrive to its right.
     */
    private static WordLock detectCompletedWord(String text, int changedFromUtf16) {
        if (text == null || text.isEmpty()) return null;
        int eligible = 0;
        int wordGlyphs = 0;
        boolean inWord = false;
        WordLock latest = null;
        int threshold = Math.max(0, changedFromUtf16 - 1);
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            int next = offset + Character.charCount(codePoint);
            int eligibleBefore = eligible;
            if (glitchEligible(codePoint)) eligible++;
            if (wordCodePoint(codePoint)) {
                if (!inWord) {
                    inWord = true;
                    wordGlyphs = 0;
                }
                if (glitchEligible(codePoint)) wordGlyphs++;
            } else {
                if (inWord && wordGlyphs > 0 && offset >= threshold) {
                    latest = new WordLock(wordGlyphs, eligibleBefore);
                }
                inWord = false;
                wordGlyphs = 0;
            }
            offset = next;
        }
        return latest;
    }

    private static boolean containsImpactPunctuation(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            if (cp == '.' || cp == '!' || cp == '?' || cp == ':' || cp == ';'
                    || cp == '。' || cp == '！' || cp == '？' || cp == '…') {
                return true;
            }
            offset += Character.charCount(cp);
        }
        return false;
    }

    private static boolean paragraphIgnited(String previous, String delta) {
        if (delta == null || delta.isEmpty()) return false;
        boolean afterBreak = previous != null && (previous.endsWith("\n") || previous.endsWith("\r"));
        for (int offset = 0; offset < delta.length();) {
            int cp = delta.codePointAt(offset);
            if (cp == '\n' || cp == '\r') {
                afterBreak = true;
            } else if (!Character.isWhitespace(cp)) {
                if (afterBreak) return true;
                afterBreak = false;
            }
            offset += Character.charCount(cp);
        }
        return false;
    }

    private static float decayPulse(long eventAt, long duration, long now) {
        if (eventAt <= 0L || duration <= 0L || now < eventAt) return 0.0F;
        long elapsed = now - eventAt;
        if (elapsed >= duration) return 0.0F;
        return (float) clamp01(1.0D - elapsed / (double) duration);
    }

    private static float snapPulse(long eventAt, long duration, long now) {
        if (eventAt <= 0L || duration <= 0L || now < eventAt) return 0.0F;
        long elapsed = now - eventAt;
        if (elapsed >= duration) return 0.0F;
        long attack = Math.min(36L, Math.max(12L, duration / 5L));
        if (elapsed < attack) return (float) clamp01(elapsed / (double) attack);
        return (float) clamp01(1.0D - (elapsed - attack) / (double) Math.max(1L, duration - attack));
    }

    private static int visualSourceGlyphCount(int semanticCount) {
        int base = Math.max(1, Math.min(MAX_SOURCE_WORD_GLYPHS, semanticCount));
        int extras = base <= 2 ? 1 : Math.max(2, (int) Math.ceil(base * 0.75D));
        return Math.max(1, Math.min(MAX_VISUAL_SOURCE_GLYPHS, base + extras));
    }

    private static long sourceSpawnIntervalMillis(double tokensPerSecond) {
        double tps = Double.isFinite(tokensPerSecond) ? Math.max(0.0D, tokensPerSecond) : 0.0D;
        double speed = clamp01((tps - 6.0D) / 58.0D);
        // Slow models keep the deliberate one-by-one gather. Fast models tighten
        // the interval so source matter stays visually coupled to real throughput.
        return Math.round(124.0D - speed * 76.0D);
    }

    private static long sourceCycleMillis(double tokensPerSecond, int distancePixels, int glyphCount) {
        int count = Math.max(1, Math.min(MAX_VISUAL_SOURCE_GLYPHS, glyphCount));
        long attraction = attractionMillis(tokensPerSecond, distancePixels);
        long stagger = sourceSpawnIntervalMillis(tokensPerSecond) * Math.max(0, count - 1);
        return attraction + stagger;
    }

    private static long attractionMillis(double tokensPerSecond, int distancePixels) {
        double tps = Double.isFinite(tokensPerSecond) ? Math.max(0.0D, tokensPerSecond) : 0.0D;
        // The pull now follows actual throughput more strongly. Slow generation
        // remains admire-able; very fast generation gets a proportionally faster
        // swing so the visual stream does not drift behind tools/events.
        double pixelsPerSecond = 60.0D + Math.min(80.0D, tps) * 8.5D;
        double distance = Math.max(MIN_SOURCE_OFFSET_X, Math.abs(distancePixels));
        long duration = Math.round(distance / Math.max(1.0D, pixelsPerSecond) * 1_000.0D);
        return Math.max(110L, Math.min(1_360L, duration));
    }

    private static double instabilityStrength(double tokensPerSecond) {
        if (!Double.isFinite(tokensPerSecond) || tokensPerSecond <= INSTABILITY_EFFECT_START_TPS) return 0.0D;
        return clamp01((tokensPerSecond - INSTABILITY_EFFECT_START_TPS) / 42.0D);
    }

    private record WordLock(int glyphs, int endEligibleCount) {
    }

    private static void maybeCleanup(long now) {
        if ((CLEANUP_TICK.incrementAndGet() & 63) != 0 && STATES.size() <= MAXIMUM_TRACKED_REQUESTS) return;
        STATES.entrySet().removeIf(entry -> entry.getValue().expired(now));
        if (STATES.size() <= MAXIMUM_TRACKED_REQUESTS) return;
        UUID oldest = null;
        long oldestAt = Long.MAX_VALUE;
        for (var entry : STATES.entrySet()) {
            long seen = entry.getValue().lastObservedAt();
            if (seen < oldestAt) {
                oldestAt = seen;
                oldest = entry.getKey();
            }
        }
        if (oldest != null) STATES.remove(oldest);
    }

    public record Frame(
            int glitchGlyphs,
            int glowGlyphs,
            boolean cursorVisible,
            int revision,
            float tintStrength,
            int cursorGlyphs,
            double effectiveTokensPerSecond,
            long revealMillis,
            float chaosStrength,
            int sourceSeed,
            int sourceOffsetX,
            int sourceOffsetY,
            int sourceGlyphCount,
            long sourceAttractionMillis,
            long sourceSpawnIntervalMillis,
            long sourceCycleMillis,
            float sourceProgress,
            List<SourceWaveFrame> sourceWaves,
            float burstStrength,
            float lockStrength,
            int lockGlyphs,
            int lockGapGlyphs,
            float punctuationPulse,
            float paragraphPulse,
            float instabilityStrength,
            float wakeStrength,
            String presentedText,
            float cursorAssemblyProgress,
            boolean cursorAssembling,
            boolean presentationPending
    ) {
        private static Frame settled() {
            return new Frame(
                    0, 0, false, 0, 0.0F, 1, 0.0D, DEFAULT_REVEAL_MILLIS,
                    0.0F, 0, MIN_SOURCE_OFFSET_X, 0, 1, 0L, 0L, 1L, 1.0F, List.of(), 0.0F,
                    0.0F, 0, 0, 0.0F, 0.0F, 0.0F, 0.0F,
                    "", 0.0F, false, false
            );
        }

        private static Frame cursorOnly() {
            return new Frame(
                    0, 0, true, 1, 0.0F, 1, 0.0D, DEFAULT_REVEAL_MILLIS,
                    0.0F, 1, MIN_SOURCE_OFFSET_X, 0, 1, 0L, 0L, 1L, 1.0F, List.of(), 0.0F,
                    0.0F, 0, 0, 0.0F, 0.0F, 0.0F, 0.0F,
                    "", 0.0F, false, false
            );
        }

        public boolean animating() {
            return this.glitchGlyphs > 0
                    || this.glowGlyphs > 0
                    || this.lockStrength > 0.001F
                    || this.wakeStrength > 0.001F;
        }
    }

    /** Immutable render snapshot for one overlapping source-harvest wave. */
    public record SourceWaveFrame(
            int seed,
            int offsetX,
            int offsetY,
            int glyphCount,
            long attractionMillis,
            long spawnIntervalMillis,
            long cycleMillis,
            float progress
    ) {
    }

    private record Glyph(int codePoint, Style style) {
    }

    private static final class TransformState {
        private final int skipEligible;
        private final int selectedEligible;
        private int seenEligible;
        private int selectedOrdinal;

        private TransformState(int skipEligible, int selectedEligible) {
            this.skipEligible = Math.max(0, skipEligible);
            this.selectedEligible = Math.max(0, selectedEligible);
        }
    }

    private record SourceWaveState(
            int seed,
            int offsetX,
            int offsetY,
            int glyphCount,
            long attractionMillis,
            long spawnIntervalMillis,
            long cycleMillis,
            long startedAt
    ) {
    }

    private static final class State {
        private final UUID requestId;
        private String text = "";
        private boolean initialized;
        private boolean active;
        private boolean everActive;
        private int burstGlyphs;
        private long burstAt;
        private float burstMagnitude;
        private long lastChangeAt;
        private long lastObservedAt;
        private long previousChangeAt;
        private long completedAt;
        private int completionGlitchGlyphs;
        private int lastCompletionTokens;
        private double smoothedTokensPerSecond;
        private int sequence;

        private int sourceSeed;
        private int sourceOffsetX = MIN_SOURCE_OFFSET_X;
        private int sourceOffsetY;
        private int sourceGlyphCount = 1;
        private long sourceAttractionMillis = 280L;
        private long sourceSpawnIntervalMillis = 96L;
        private long sourceCycleMillis = 1L;
        private long sourceBurstAt;
        private final List<SourceWaveState> sourceWaves = new ArrayList<>();

        // Renderer-side presentation staging. Raw model output stays untouched in
        // ModelGenerationHudState; only the text exposed by Frame.presentedText()
        // is delayed so source matter can visibly exist before its word appears.
        private int presentedEnd;
        private int stagedWordStart = -1;
        private int stagedWordEnd = -1;
        private int stagedWordGlyphCount;
        private long stagedWordAt;
        private long stagedRevealAt;
        private long lastPresentedGlyphAt;
        private long cursorCycleAt;
        private long lastPresentationAdvanceAt;
        private double presentationCredit;
        private String presentedTextCache = "";
        private int presentedTextCacheEnd = -1;
        private int presentedEligibleCount;
        private int presentedEligibleCountEnd;

        private long lockAt;
        private int lockGlyphs;
        private int lockEndEligibleCount;
        private long punctuationAt;
        private long paragraphAt;

        private State(UUID requestId) {
            this.requestId = requestId;
        }

        synchronized Frame observe(
                String current,
                boolean currentlyActive,
                double reportedTokensPerSecond,
                int completionTokens,
                long now,
                boolean includePresentedText
        ) {
            String safe = current == null ? "" : current;
            int carry = remainingGlitch(now);
            if (!this.initialized) {
                this.initialized = true;
                this.text = safe;
                this.presentedEnd = 0;
                resetPresentedCaches();
                this.active = currentlyActive;
                this.everActive = currentlyActive;
                this.burstAt = now;
                this.lastChangeAt = now;
                this.previousChangeAt = now;
                this.lastObservedAt = now;
                this.lastCompletionTokens = completionTokens;
                if (reportedTokensPerSecond > 0.0D) {
                    this.smoothedTokensPerSecond = reportedTokensPerSecond;
                }
                this.sequence++;
            } else if (!Objects.equals(this.text, safe)) {
                String previous = this.text;
                boolean appendOnly = safe.startsWith(previous);
                int added = eligibleGlyphsAdded(previous, safe);
                int tokenDelta = Math.max(0, completionTokens - this.lastCompletionTokens);
                long deltaMillis = Math.max(1L, now - this.lastChangeAt);
                double observedTps = tokenDelta > 0
                        ? tokenDelta * 1_000.0D / deltaMillis
                        : approximateTokenDelta(added) * 1_000.0D / deltaMillis;
                double sample = reportedTokensPerSecond > 0.0D
                        ? reportedTokensPerSecond
                        : observedTps;
                if (sample > 0.0D && Double.isFinite(sample)) {
                    this.smoothedTokensPerSecond = this.smoothedTokensPerSecond <= 0.0D
                            ? sample
                            : this.smoothedTokensPerSecond * 0.58D + sample * 0.42D;
                }

                if (!appendOnly) {
                    // Provider-side rewrites can occur when a final sanitizer or a
                    // continuation replaces the tail. Preserve the already-presented
                    // common prefix instead of jumping back to character zero. That
                    // keeps the materializer monotonic and prevents the UI from
                    // visually restarting/catching up from the beginning mid-response.
                    int stablePrefix = commonPrefixLength(previous, safe);
                    this.presentedEnd = Math.min(this.presentedEnd, stablePrefix);
                    resetPresentedCaches();
                    this.stagedWordStart = -1;
                    this.stagedWordEnd = -1;
                    this.stagedWordGlyphCount = 0;
                    this.stagedWordAt = 0L;
                    this.stagedRevealAt = 0L;
                    this.sourceBurstAt = 0L;
                    this.sourceWaves.clear();
                    this.lastPresentedGlyphAt = 0L;
                    this.cursorCycleAt = 0L;
                    this.lastPresentationAdvanceAt = 0L;
                    this.presentationCredit = 0.0D;
                    this.burstGlyphs = 0;
                }
                this.text = safe;
                this.previousChangeAt = this.lastChangeAt;
                this.lastChangeAt = now;
                this.lastCompletionTokens = Math.max(this.lastCompletionTokens, completionTokens);
                this.completedAt = 0L;
                this.completionGlitchGlyphs = 0;
                this.sequence++;
            } else if (reportedTokensPerSecond > 0.0D && currentlyActive) {
                if (this.smoothedTokensPerSecond <= 0.0D) this.smoothedTokensPerSecond = reportedTokensPerSecond;
                else this.smoothedTokensPerSecond = this.smoothedTokensPerSecond * 0.92D
                        + reportedTokensPerSecond * 0.08D;
                this.lastCompletionTokens = Math.max(this.lastCompletionTokens, completionTokens);
            }

            if (currentlyActive) this.everActive = true;
            if (this.active != currentlyActive) {
                if (this.active && !currentlyActive) {
                    this.completionGlitchGlyphs = Math.max(
                            Math.min(MAXIMUM_GLITCH_GLYPHS, carry),
                            safe.isBlank() ? 0 : Math.min(6, Math.max(2, eligibleGlyphs(safe)))
                    );
                    this.completedAt = now;
                } else if (currentlyActive) {
                    this.completedAt = 0L;
                    this.completionGlitchGlyphs = 0;
                }
                this.active = currentlyActive;
                this.sequence++;
            }

            advancePresentation(now, currentlyActive);
            this.lastObservedAt = now;

            int remainingGlitch = remainingGlitch(now);
            double effectiveTps = effectiveTokensPerSecond(now);
            float tint = (float) tintStrength(effectiveTps, now);
            float chaos = (float) chaosStrength(effectiveTps);
            float instability = (float) instabilityStrength(effectiveTps);
            float wake = wakeStrength(now, tint);
            float sourceProgress = (float) sourceProgress(now, effectiveTps);
            float cursorProgress = (float) cursorAssemblyProgress(now, effectiveTps);
            boolean cursorAssembling = cursorAssemblyActive(now, effectiveTps);
            float burst = this.burstMagnitude * decayPulse(this.burstAt, BURST_COMPRESSION_MILLIS, now);
            float lock = snapPulse(this.lockAt, WORD_LOCK_PULSE_MILLIS, now);
            String presented = includePresentedText ? presentedText() : "";
            int eligibleTotal = presentedEligibleCount();
            int lockGap = lock > 0.001F
                    ? Math.max(0, eligibleTotal - this.lockEndEligibleCount)
                    : 0;
            float punctuation = decayPulse(this.punctuationAt, PUNCTUATION_IMPACT_MILLIS, now);
            float paragraph = decayPulse(this.paragraphAt, PARAGRAPH_IGNITION_MILLIS, now);
            boolean presentationPending = presentationPending();

            boolean cursor = !safe.isBlank()
                    && (currentlyActive || presentationPending || remainingGlitch > 0
                    || lock > 0.0F || punctuation > 0.0F || paragraph > 0.0F
                    || (this.everActive && this.completedAt > 0L
                    && now - this.completedAt < POST_COMPLETION_CURSOR_MILLIS));
            int glowGlyphs = wake <= 0.01F ? 0
                    : 2 + (int) Math.round(clamp01(wake) * 6.0D + clamp01(tint) * 3.0D);
            long reveal = presentationCharacterMillis(effectiveTps);

            int revision = 31 * this.sequence
                    + 17 * remainingGlitch
                    + 13 * bucket(tint, 20)
                    + 11 * bucket(wake, 20)
                    + 7 * bucket(sourceProgress, 16)
                    + 5 * bucket(lock, 12)
                    + 3 * bucket(punctuation + paragraph, 12)
                    + 19 * bucket(cursorProgress, 14)
                    + this.presentedEnd
                    + bucket(instability, 10)
                    + (cursor ? 1 : 0);
            return new Frame(
                    remainingGlitch,
                    glowGlyphs,
                    cursor,
                    revision,
                    tint,
                    1,
                    effectiveTps,
                    reveal,
                    chaos,
                    this.sourceSeed,
                    this.sourceOffsetX,
                    this.sourceOffsetY,
                    this.sourceGlyphCount,
                    this.sourceAttractionMillis,
                    this.sourceSpawnIntervalMillis,
                    this.sourceCycleMillis,
                    sourceProgress,
                    sourceWaveFrames(now),
                    burst,
                    lock,
                    lock > 0.001F ? this.lockGlyphs : 0,
                    lockGap,
                    punctuation,
                    paragraph,
                    instability,
                    wake,
                    presented,
                    cursorProgress,
                    cursorAssembling,
                    presentationPending
            );
        }

        private void advancePresentation(long now, boolean currentlyActive) {
            advancePresentation(now, currentlyActive, 0);
        }

        private void advancePresentation(long now, boolean currentlyActive, int chainDepth) {
            // The raw model stream remains authoritative. Presentation only trails
            // it by a small dynamic budget so source matter can appear first.
            // If the visual stream falls farther behind than that budget, it
            // accelerates automatically instead of running on an independent clock.
            // Once the provider is terminal there is no future token cadence to
            // follow, so commit the remaining visible text immediately. The tail
            // materialization/cursor may settle briefly, but the answer itself is
            // fully visible in the same terminal phase as the real generation.
            if (!currentlyActive && this.presentedEnd < this.text.length()) {
                String remaining = this.text.substring(this.presentedEnd);
                int remainingGlyphs = Math.max(1, eligibleGlyphs(remaining));
                this.presentedEnd = this.text.length();
                this.stagedWordStart = -1;
                this.stagedWordEnd = -1;
                this.stagedWordGlyphCount = 0;
                this.stagedWordAt = 0L;
                this.stagedRevealAt = 0L;
                this.presentationCredit = 0.0D;
                this.lastPresentationAdvanceAt = now;
                this.lastPresentedGlyphAt = now;
                this.burstGlyphs = Math.min(MAXIMUM_GLITCH_GLYPHS,
                        Math.max(INITIAL_GLITCH_GLYPHS, Math.min(12, remainingGlyphs)));
                this.burstAt = now;
                this.cursorCycleAt = now - Math.max(1L,
                        Math.round(presentationCursorCycleMillis(this.smoothedTokensPerSecond) * 0.52D));
                this.sequence++;
                return;
            }
            if (this.stagedWordStart >= 0 && this.stagedWordEnd > this.stagedWordStart) {
                int rawBacklog = Math.max(0, this.text.codePointCount(
                        Math.max(0, Math.min(this.presentedEnd, this.text.length())), this.text.length()));
                long revealStart = this.stagedRevealAt > 0L
                        ? this.stagedRevealAt
                        : this.stagedWordAt + presentationSourceLeadMillis(
                        this.smoothedTokensPerSecond, this.stagedWordGlyphCount, rawBacklog, currentlyActive);
                double baseCps = presentationCharactersPerSecond(this.smoothedTokensPerSecond);
                double catchUp = presentationCatchUpMultiplier(
                        rawBacklog, baseCps, this.smoothedTokensPerSecond, currentlyActive);

                if (now >= revealStart) {
                    if (this.lastPresentationAdvanceAt < revealStart) {
                        this.lastPresentationAdvanceAt = revealStart;
                        this.presentationCredit = 0.0D;
                        if (revealStart > this.cursorCycleAt) {
                            this.cursorCycleAt = revealStart;
                            this.sequence++;
                        }
                    }
                    long elapsed = Math.max(0L, now - this.lastPresentationAdvanceAt);
                    this.lastPresentationAdvanceAt = now;
                    this.presentationCredit += elapsed * baseCps * catchUp / 1_000.0D;

                    long cursorStep = presentationCharacterMillis(this.smoothedTokensPerSecond);
                    long visibleOffset = Math.max(18L,
                            Math.min(42L, Math.round(presentationCursorCycleMillis(
                                    this.smoothedTokensPerSecond) * 0.48D)));
                    if (now >= revealStart + visibleOffset && this.presentationCredit >= 1.0D) {
                        int remainingWord = Math.max(0, this.text.codePointCount(
                                Math.max(this.presentedEnd, this.stagedWordStart), this.stagedWordEnd));
                        int revealCount = Math.max(1, Math.min(remainingWord,
                                (int) Math.floor(this.presentationCredit)));
                        // A single frame may reveal several characters on a fast
                        // model. The cursor represents that real batch rather than
                        // artificially slowing the visible stream to one glyph.
                        int targetBacklog = presentationTargetBacklogCharacters(
                                baseCps, this.smoothedTokensPerSecond);
                        int hardPressure = rawBacklog > Math.max(6, targetBacklog * 2) ? 24 : 12;
                        revealCount = Math.min(revealCount, hardPressure);
                        int revealEnd = offsetByCodePointsBounded(
                                this.text, this.presentedEnd, this.stagedWordEnd, revealCount);
                        if (revealEnd > this.presentedEnd) {
                            String newlyVisible = this.text.substring(this.presentedEnd, revealEnd);
                            int revealedCodePoints = newlyVisible.codePointCount(0, newlyVisible.length());
                            int revealedGlyphs = Math.max(1, eligibleGlyphs(newlyVisible));
                            this.presentedEnd = revealEnd;
                            this.presentationCredit = Math.max(0.0D,
                                    this.presentationCredit - revealedCodePoints);
                            this.lastPresentedGlyphAt = now;
                            long cycleDuration = presentationCursorCycleMillis(this.smoothedTokensPerSecond);
                            this.cursorCycleAt = Math.max(revealStart,
                                    now - Math.min(visibleOffset, Math.max(1L, cycleDuration - 1L)));
                            this.burstGlyphs = Math.min(
                                    MAXIMUM_GLITCH_GLYPHS,
                                    Math.max(INITIAL_GLITCH_GLYPHS,
                                            revealedGlyphs + 2 + Math.min(6, revealCount - 1))
                            );
                            this.burstAt = now;
                            this.burstMagnitude = (float) clamp01(
                                    0.22D + revealedGlyphs / 5.0D
                                            + Math.min(0.34D, (revealCount - 1) * 0.055D)
                                            + chaosStrength(this.smoothedTokensPerSecond) * 0.32D
                            );
                            if (containsImpactPunctuation(newlyVisible)) this.punctuationAt = now;
                            this.sequence++;
                        }
                    }
                }

                if (this.presentedEnd >= this.stagedWordEnd) {
                    String completedWord = this.text.substring(this.stagedWordStart, this.stagedWordEnd);
                    int wordGlyphs = Math.max(1, eligibleGlyphs(completedWord));
                    this.lockAt = now + Math.min(
                            WORD_LOCK_DELAY_MAX_MILLIS,
                            Math.max(14L, presentationCharacterMillis(this.smoothedTokensPerSecond))
                    );
                    this.lockGlyphs = wordGlyphs;
                    this.lockEndEligibleCount = presentedEligibleCount();
                    this.stagedWordStart = -1;
                    this.stagedWordEnd = -1;
                    this.stagedWordGlyphCount = 0;
                    this.stagedWordAt = 0L;
                    this.stagedRevealAt = 0L;
                    this.lastPresentationAdvanceAt = 0L;
                    this.presentationCredit = 0.0D;
                    consumeVisibleWhitespace(now);
                    if (chainDepth < 4 && this.presentedEnd < this.text.length()) {
                        double cps = presentationCharactersPerSecond(this.smoothedTokensPerSecond);
                        int target = presentationTargetBacklogCharacters(cps, this.smoothedTokensPerSecond);
                        int backlog = this.text.codePointCount(
                                Math.max(0, Math.min(this.presentedEnd, this.text.length())), this.text.length());
                        if (!currentlyActive || backlog > target) {
                            advancePresentation(now, currentlyActive, chainDepth + 1);
                        }
                    }
                }
                return;
            }

            consumeVisibleWhitespace(now);
            if (this.presentedEnd >= this.text.length()) return;

            int wordStart = this.presentedEnd;
            int wordEnd = wordStart;
            while (wordEnd < this.text.length()) {
                int cp = this.text.codePointAt(wordEnd);
                if (Character.isWhitespace(cp) || Character.isISOControl(cp)) break;
                wordEnd += Character.charCount(cp);
            }
            if (wordEnd <= wordStart) return;

            boolean boundaryAvailable = wordEnd < this.text.length();
            boolean terminalWord = !currentlyActive;
            boolean punctuationBoundary = presentationBoundaryPunctuation(
                    this.text.codePointBefore(wordEnd));
            if (!boundaryAvailable && !terminalWord && !punctuationBoundary) {
                // Intentionally hold the in-flight word. This is the key contract:
                // raw output may already contain it, but the user does not see it
                // until a harvest burst has had time to spawn first.
                return;
            }

            this.stagedWordStart = wordStart;
            this.stagedWordEnd = wordEnd;
            int backlogNow = this.text.codePointCount(wordStart, this.text.length());
            double cpsNow = presentationCharactersPerSecond(this.smoothedTokensPerSecond);
            int targetNow = presentationTargetBacklogCharacters(cpsNow, this.smoothedTokensPerSecond);
            boolean sourceAlreadyVisible = sourceMatterReady(now);
            boolean pipelined = sourceAlreadyVisible && (backlogNow > targetNow || !currentlyActive);
            long sourceAge = this.sourceBurstAt <= 0L ? 0L : Math.max(0L, now - this.sourceBurstAt);
            this.stagedWordAt = now;
            this.lastPresentationAdvanceAt = 0L;
            this.presentationCredit = 0.0D;
            String word = this.text.substring(wordStart, wordEnd);
            this.stagedWordGlyphCount = Math.max(1,
                    Math.min(MAX_SOURCE_WORD_GLYPHS, eligibleGlyphs(word)));
            ensureSourceBurst(now, word, this.stagedWordGlyphCount);
            long lead = pipelined ? 0L : presentationSourceLeadMillis(
                    this.smoothedTokensPerSecond, this.stagedWordGlyphCount, backlogNow, currentlyActive);
            this.stagedRevealAt = pipelined
                    ? now - Math.min(96L, Math.max(12L, sourceAge))
                    : now + lead;
            this.sequence++;
            if (chainDepth < 4 && pipelined) {
                advancePresentation(now, currentlyActive, chainDepth + 1);
            }
        }

        private void consumeVisibleWhitespace(long now) {
            int start = this.presentedEnd;
            boolean sawParagraph = false;
            while (this.presentedEnd < this.text.length()) {
                int cp = this.text.codePointAt(this.presentedEnd);
                if (!Character.isWhitespace(cp) && !Character.isISOControl(cp)) break;
                if (cp == '\n' || cp == '\r') sawParagraph = true;
                this.presentedEnd += Character.charCount(cp);
            }
            if (this.presentedEnd > start) {
                if (sawParagraph) this.paragraphAt = now;
                this.sequence++;
            }
        }

        private void ensureSourceBurst(long now, String word, int semanticGlyphCount) {
            // Every staged word gets its own source wave. Previous waves remain alive
            // until their natural cycle completes, so incoming characters never pause
            // merely because an older wave is still travelling into the cursor.
            startSourceBurst(now, word, semanticGlyphCount);
        }

        private void startSourceBurst(long now, String word, int semanticGlyphCount) {
            this.sourceGlyphCount = Math.max(1,
                    Math.min(MAX_SOURCE_WORD_GLYPHS, semanticGlyphCount));
            int base = this.requestId == null ? 0 : this.requestId.hashCode();
            int wordHash = word == null ? 0 : word.hashCode();
            int seed = mix32(base ^ (this.sequence * 0x9E3779B9) ^ wordHash ^ this.presentedEnd);
            this.sourceSeed = seed;
            this.sourceOffsetX = MIN_SOURCE_OFFSET_X
                    + Math.floorMod(seed, MAX_SOURCE_OFFSET_X - MIN_SOURCE_OFFSET_X + 1);
            this.sourceOffsetY = Math.floorMod(seed >>> 7, MAX_SOURCE_OFFSET_Y * 2 + 1) - MAX_SOURCE_OFFSET_Y;
            int visualCount = visualSourceGlyphCount(this.sourceGlyphCount);
            this.sourceSpawnIntervalMillis = sourceSpawnIntervalMillis(this.smoothedTokensPerSecond);
            this.sourceAttractionMillis = attractionMillis(this.smoothedTokensPerSecond, this.sourceOffsetX);
            this.sourceCycleMillis = sourceCycleMillis(this.smoothedTokensPerSecond, this.sourceOffsetX, visualCount);
            this.sourceBurstAt = now;

            pruneSourceWaves(now);
            this.sourceWaves.add(new SourceWaveState(
                    this.sourceSeed,
                    this.sourceOffsetX,
                    this.sourceOffsetY,
                    this.sourceGlyphCount,
                    this.sourceAttractionMillis,
                    this.sourceSpawnIntervalMillis,
                    this.sourceCycleMillis,
                    now
            ));
            while (this.sourceWaves.size() > MAX_ACTIVE_SOURCE_WAVES) {
                this.sourceWaves.remove(0);
            }
        }

        private void pruneSourceWaves(long now) {
            this.sourceWaves.removeIf(wave ->
                    now - wave.startedAt() >= Math.max(1L, wave.cycleMillis()));
        }

        private List<SourceWaveFrame> sourceWaveFrames(long now) {
            pruneSourceWaves(now);
            if (this.sourceWaves.isEmpty()) return List.of();
            List<SourceWaveFrame> frames = new ArrayList<>(this.sourceWaves.size());
            for (SourceWaveState wave : this.sourceWaves) {
                long cycle = Math.max(1L, wave.cycleMillis());
                float progress = (float) clamp01((now - wave.startedAt()) / (double) cycle);
                frames.add(new SourceWaveFrame(
                        wave.seed(),
                        wave.offsetX(),
                        wave.offsetY(),
                        wave.glyphCount(),
                        wave.attractionMillis(),
                        wave.spawnIntervalMillis(),
                        wave.cycleMillis(),
                        progress
                ));
            }
            return List.copyOf(frames);
        }

        private boolean sourceMatterReady(long now) {
            pruneSourceWaves(now);
            for (int i = this.sourceWaves.size() - 1; i >= 0; i--) {
                SourceWaveState wave = this.sourceWaves.get(i);
                long age = Math.max(0L, now - wave.startedAt());
                if (age >= 8L && age < Math.max(1L, wave.cycleMillis())) return true;
            }
            return false;
        }

        private double sourceProgress(long now, double tokensPerSecond) {
            if (this.sourceBurstAt <= 0L) return 1.0D;
            long cycle = Math.max(1L, this.sourceCycleMillis);
            return clamp01((now - this.sourceBurstAt) / (double) cycle);
        }

        private double cursorAssemblyProgress(long now, double tokensPerSecond) {
            if (this.cursorCycleAt <= 0L || now < this.cursorCycleAt) return 0.0D;
            long duration = presentationCursorCycleMillis(tokensPerSecond);
            long elapsed = Math.max(0L, now - this.cursorCycleAt);
            if (elapsed >= duration) return 0.0D;
            return clamp01(elapsed / (double) Math.max(1L, duration));
        }

        private boolean cursorAssemblyActive(long now, double tokensPerSecond) {
            if (this.cursorCycleAt <= 0L || now < this.cursorCycleAt) return false;
            return now - this.cursorCycleAt < presentationCursorCycleMillis(tokensPerSecond);
        }

        private String presentedText() {
            int end = Math.max(0, Math.min(this.presentedEnd, this.text.length()));
            if (this.presentedTextCacheEnd == end) return this.presentedTextCache;
            this.presentedTextCache = end == this.text.length() ? this.text : this.text.substring(0, end);
            this.presentedTextCacheEnd = end;
            return this.presentedTextCache;
        }

        private int presentedEligibleCount() {
            int end = Math.max(0, Math.min(this.presentedEnd, this.text.length()));
            if (end < this.presentedEligibleCountEnd) {
                this.presentedEligibleCount = 0;
                this.presentedEligibleCountEnd = 0;
            }
            if (end > this.presentedEligibleCountEnd) {
                this.presentedEligibleCount += eligibleGlyphs(
                        this.text.substring(this.presentedEligibleCountEnd, end));
                this.presentedEligibleCountEnd = end;
            }
            return this.presentedEligibleCount;
        }

        private void resetPresentedCaches() {
            this.presentedTextCache = "";
            this.presentedTextCacheEnd = -1;
            this.presentedEligibleCount = 0;
            this.presentedEligibleCountEnd = 0;
        }

        private boolean presentationPending() {
            return this.stagedWordStart >= 0 || this.presentedEnd < this.text.length();
        }

        private float wakeStrength(long now, float tint) {
            float recent = decayPulse(this.lastPresentedGlyphAt > 0L ? this.lastPresentedGlyphAt : this.lastChangeAt,
                    LINE_WAKE_MILLIS, now);
            float completion = this.completedAt <= 0L
                    ? 0.0F
                    : decayPulse(this.completedAt, POST_COMPLETION_SETTLE_MILLIS, now);
            return (float) clamp01(Math.max(recent, completion * 0.65F)
                    * (0.42D + 0.42D * clamp01(tint)));
        }

        private int remainingGlitch(long now) {
            if (!this.active && this.completedAt > 0L && !presentationPending()) {
                if (this.completionGlitchGlyphs <= 0) return 0;
                long elapsed = Math.max(0L, now - this.completedAt);
                if (elapsed >= POST_COMPLETION_SETTLE_MILLIS) return 0;
                double remainingFraction = 1.0D - elapsed / (double) POST_COMPLETION_SETTLE_MILLIS;
                return Math.max(0, (int) Math.ceil(this.completionGlitchGlyphs * remainingFraction));
            }
            if (this.burstGlyphs <= 0) return 0;
            long elapsed = Math.max(0L, now - this.burstAt);
            long step = revealMillis(effectiveTokensPerSecond(now));
            int revealed = (int) Math.min(Integer.MAX_VALUE, elapsed / Math.max(1L, step));
            return Math.max(0, this.burstGlyphs - revealed);
        }

        private double effectiveTokensPerSecond(long now) {
            if (this.smoothedTokensPerSecond <= 0.0D) return 0.0D;
            long activityAnchor = Math.max(this.lastChangeAt, this.lastPresentedGlyphAt);
            long idle = Math.max(0L, now - activityAnchor);
            double decay = idle <= SPEED_DECAY_GRACE_MILLIS
                    ? 1.0D
                    : Math.exp(-(idle - SPEED_DECAY_GRACE_MILLIS) / SPEED_DECAY_MILLIS);
            if (!this.active && this.completedAt > 0L && !presentationPending()) {
                double settle = 1.0D - clamp01((now - this.completedAt)
                        / (double) POST_COMPLETION_CURSOR_MILLIS);
                decay *= settle;
            }
            return Math.max(0.0D, this.smoothedTokensPerSecond * decay);
        }

        private double tintStrength(double effectiveTps, long now) {
            double speed = speedEffectStrength(effectiveTps);
            if (!this.active && this.completedAt > 0L && !presentationPending()) {
                speed *= 1.0D - clamp01((now - this.completedAt)
                        / (double) POST_COMPLETION_CURSOR_MILLIS);
            }
            return speed;
        }

        synchronized boolean expired(long now) {
            return !this.active
                    && !presentationPending()
                    && remainingGlitch(now) <= 0
                    && snapPulse(this.lockAt, WORD_LOCK_PULSE_MILLIS, now) <= 0.0F
                    && (!this.everActive || this.completedAt <= 0L
                    || now - this.completedAt >= POST_COMPLETION_CURSOR_MILLIS)
                    && now - this.lastObservedAt > STATE_IDLE_EVICTION_MILLIS;
        }

        synchronized long lastObservedAt() {
            return this.lastObservedAt;
        }
    }

    private static long presentationSourceLeadMillis(
            double tokensPerSecond,
            int semanticGlyphCount,
            int rawBacklogCodePoints,
            boolean currentlyActive
    ) {
        int visualCount = visualSourceGlyphCount(semanticGlyphCount);
        double tps = Double.isFinite(tokensPerSecond) ? Math.max(0.0D, tokensPerSecond) : 0.0D;
        double cps = presentationCharactersPerSecond(tps);
        int targetBacklog = presentationTargetBacklogCharacters(cps, tps);
        boolean urgent = !currentlyActive || rawBacklogCodePoints > Math.max(4, targetBacklog * 3 / 2);

        int leadSources;
        if (urgent || tps >= 32.0D) leadSources = 1;
        else if (tps >= 12.0D) leadSources = 2;
        else leadSources = 3;
        leadSources = Math.min(visualCount, Math.max(1, leadSources));

        long lead = 10L + sourceSpawnIntervalMillis(tps) * Math.max(0, leadSources - 1);
        if (urgent) lead = 8L;
        return Math.max(PRESENTATION_SOURCE_LEAD_MIN_MILLIS,
                Math.min(PRESENTATION_SOURCE_LEAD_MAX_MILLIS, lead));
    }

    private static double presentationCharactersPerSecond(double tokensPerSecond) {
        double tps = Double.isFinite(tokensPerSecond) ? Math.max(0.0D, tokensPerSecond) : 0.0D;
        if (tps <= 0.0D) return 18.0D;
        return Math.max(PRESENTATION_MIN_CHARS_PER_SECOND,
                Math.min(PRESENTATION_MAX_CHARS_PER_SECOND,
                        tps * PRESENTATION_APPROX_CHARS_PER_TOKEN));
    }

    private static long presentationTargetLagMillis(double tokensPerSecond) {
        double tps = Double.isFinite(tokensPerSecond) ? Math.max(0.0D, tokensPerSecond) : 0.0D;
        double speed = clamp01((tps - 4.0D) / 52.0D);
        return Math.round(88.0D - speed * 56.0D);
    }

    private static int presentationTargetBacklogCharacters(double charsPerSecond, double tokensPerSecond) {
        return Math.max(2, (int) Math.ceil(charsPerSecond
                * presentationTargetLagMillis(tokensPerSecond) / 1_000.0D));
    }

    private static double presentationCatchUpMultiplier(
            int rawBacklogCodePoints,
            double charsPerSecond,
            double tokensPerSecond,
            boolean currentlyActive
    ) {
        int target = presentationTargetBacklogCharacters(charsPerSecond, tokensPerSecond);
        double ratio = rawBacklogCodePoints / (double) Math.max(1, target);
        double multiplier = 1.0D;
        if (ratio > 1.0D) {
            multiplier += Math.min(PRESENTATION_MAX_CATCHUP_MULTIPLIER - 1.0D,
                    (ratio - 1.0D) * 2.35D);
        }
        if (ratio > 2.0D) multiplier = Math.max(multiplier, 7.5D);
        if (!currentlyActive) multiplier = Math.max(multiplier, 10.0D);
        return Math.max(1.0D, Math.min(PRESENTATION_MAX_CATCHUP_MULTIPLIER, multiplier));
    }

    private static long presentationCharacterMillis(double tokensPerSecond) {
        double cps = presentationCharactersPerSecond(tokensPerSecond);
        long step = Math.round(1_000.0D / Math.max(1.0D, cps));
        return Math.max(PRESENTATION_CHARACTER_MIN_MILLIS,
                Math.min(PRESENTATION_CHARACTER_MAX_MILLIS, step));
    }

    private static long presentationCursorCycleMillis(double tokensPerSecond) {
        long characterStep = presentationCharacterMillis(tokensPerSecond);
        return Math.max(PRESENTATION_CURSOR_CYCLE_MIN_MILLIS,
                Math.min(96L, Math.round(characterStep * 1.55D)));
    }

    private static int offsetByCodePointsBounded(String value, int start, int end, int count) {
        if (value == null || value.isEmpty()) return Math.max(0, start);
        int index = Math.max(0, Math.min(start, value.length()));
        int limit = Math.max(index, Math.min(end, value.length()));
        int remaining = Math.max(0, count);
        while (index < limit && remaining-- > 0) {
            int cp = value.codePointAt(index);
            index += Character.charCount(cp);
        }
        return Math.min(index, limit);
    }

    private static boolean presentationBoundaryPunctuation(int codePoint) {
        return codePoint == '.' || codePoint == ',' || codePoint == '!' || codePoint == '?'
                || codePoint == ':' || codePoint == ';' || codePoint == '…'
                || codePoint == ')' || codePoint == ']' || codePoint == '}'
                || codePoint == '。' || codePoint == '！' || codePoint == '？';
    }

    private static int bucket(float value, int buckets) {
        return Math.max(0, Math.min(buckets, Math.round((float) clamp01(value) * buckets)));
    }

    private static int initialTrailTarget(double tokensPerSecond) {
        return Math.min(MAXIMUM_GLITCH_GLYPHS,
                INITIAL_GLITCH_GLYPHS + speedTrailBonus(tokensPerSecond));
    }

    private static int speedTrailBonus(double tokensPerSecond) {
        if (tokensPerSecond <= 4.0D) return 0;
        double speed = clamp01((tokensPerSecond - 4.0D) / 46.0D);
        double chaos = chaosStrength(tokensPerSecond);
        return Math.max(0, Math.min(30, (int) Math.round(4.0D * speed + 22.0D * chaos)));
    }

    private static double speedEffectStrength(double tokensPerSecond) {
        if (!Double.isFinite(tokensPerSecond) || tokensPerSecond <= SPEED_EFFECT_START_TPS) return 0.0D;
        return clamp01((tokensPerSecond - SPEED_EFFECT_START_TPS)
                / Math.max(1.0D, SPEED_EFFECT_FULL_TPS - SPEED_EFFECT_START_TPS));
    }

    private static double chaosStrength(double tokensPerSecond) {
        if (!Double.isFinite(tokensPerSecond) || tokensPerSecond <= CHAOS_EFFECT_START_TPS) return 0.0D;
        return clamp01((tokensPerSecond - CHAOS_EFFECT_START_TPS) / 52.0D);
    }

    private static int argb(int alpha, int rgb) {
        int safeAlpha = Math.max(0, Math.min(255, alpha));
        return (safeAlpha << 24) | (rgb & 0x00FFFFFF);
    }

    private static int approximateTokenDelta(int eligibleGlyphs) {
        if (eligibleGlyphs <= 0) return 0;
        return Math.max(1, (eligibleGlyphs + APPROXIMATE_GLYPHS_PER_TOKEN - 1) / APPROXIMATE_GLYPHS_PER_TOKEN);
    }

    private static long revealMillis(double tokensPerSecond) {
        if (tokensPerSecond <= 0.0D || !Double.isFinite(tokensPerSecond)) return DEFAULT_REVEAL_MILLIS;
        double tokenMillis = 1_000.0D / Math.max(0.1D, tokensPerSecond);
        long reveal = Math.round(tokenMillis / 1.15D);
        return Math.max(MINIMUM_REVEAL_MILLIS, Math.min(MAXIMUM_REVEAL_MILLIS, reveal));
    }
}
