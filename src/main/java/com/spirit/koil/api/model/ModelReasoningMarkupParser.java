package com.spirit.koil.api.model;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Incrementally separates explicit model-authored side-channel markup from user-facing text.
 *
 * <p>Koil only classifies explicit control regions. It does not infer hidden reasoning from
 * ordinary prose. Supported regions include common {@code <think>} / {@code [THINK]} forms and
 * explicit reasoning, analysis, scratchpad, planning, reflection, critique, commentary and
 * reasoning-summary tags. Tags may be split across streamed provider chunks.</p>
 */
public final class ModelReasoningMarkupParser {
    private final StringBuilder pending = new StringBuilder();
    private boolean insideThink;
    private boolean sawThinkMarkup;
    private boolean sawClosedThinkBlock;
    private String markupStyle = "inline_think_markup";
    private ModelExposedData.Kind activeKind = ModelExposedData.Kind.THOUGHT;
    private String activeTagName = "think";

    public void accept(String delta, Consumer<String> visible, Consumer<String> reasoning) {
        acceptTyped(delta, visible, chunk -> reasoning.accept(chunk.text()));
    }

    public void finish(Consumer<String> visible, Consumer<String> reasoning) {
        finishTyped(visible, chunk -> reasoning.accept(chunk.text()));
    }

    public void acceptTyped(String delta, Consumer<String> visible, Consumer<ExposedChunk> exposed) {
        if (delta == null || delta.isEmpty()) return;
        this.pending.append(delta);
        drain(false, visible, exposed);
    }

    public void finishTyped(Consumer<String> visible, Consumer<ExposedChunk> exposed) {
        drain(true, visible, exposed);
    }

    public boolean insideThink() {
        return this.insideThink;
    }

    public boolean sawThinkMarkup() {
        return this.sawThinkMarkup;
    }

    public boolean sawClosedThinkBlock() {
        return this.sawClosedThinkBlock;
    }

    /** Native inline convention most recently observed by the parser. */
    public String activeMarkupStyle() {
        return this.markupStyle;
    }

    public ModelExposedData.Kind activeKind() {
        return this.activeKind;
    }

    public static Partition partition(String value) {
        StringBuilder visible = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ModelReasoningMarkupParser parser = new ModelReasoningMarkupParser();
        parser.acceptTyped(value == null ? "" : value, visible::append, chunk -> reasoning.append(chunk.text()));
        parser.finishTyped(visible::append, chunk -> reasoning.append(chunk.text()));
        return new Partition(
                visible.toString(),
                reasoning.toString(),
                parser.sawThinkMarkup(),
                parser.sawClosedThinkBlock(),
                parser.insideThink()
        );
    }

    private void drain(boolean finishing, Consumer<String> visible, Consumer<ExposedChunk> exposed) {
        Objects.requireNonNull(visible, "visible");
        Objects.requireNonNull(exposed, "exposed");

        while (!this.pending.isEmpty()) {
            int angle = this.pending.indexOf("<");
            int square = this.pending.indexOf("[");
            int marker = nextMarker(angle, square);
            if (marker < 0) {
                emit(this.pending.toString(), visible, exposed);
                this.pending.setLength(0);
                return;
            }
            if (marker > 0) {
                String prefix = this.pending.substring(0, marker);
                emit(prefix, visible, exposed);
                this.pending.delete(0, marker);
                continue;
            }

            char opener = this.pending.charAt(0);
            char closer = opener == '<' ? '>' : ']';
            int end = this.pending.indexOf(String.valueOf(closer));
            if (end < 0) {
                if (finishing) {
                    emit(this.pending.toString(), visible, exposed);
                    this.pending.setLength(0);
                }
                return;
            }

            String tag = this.pending.substring(0, end + 1);
            ParsedTag parsed = parseTag(tag);
            if (parsed != null) {
                this.sawThinkMarkup = true;
                if (!parsed.closing()) {
                    this.activeKind = parsed.kind();
                    this.activeTagName = parsed.name();
                    this.markupStyle = parsed.nativeChannel();
                    this.insideThink = true;
                    this.pending.delete(0, end + 1);
                    continue;
                }
                if (this.insideThink && parsed.name().equals(this.activeTagName)) {
                    this.sawClosedThinkBlock = true;
                    this.insideThink = false;
                    this.pending.delete(0, end + 1);
                    continue;
                }
                if (parsed.closing() && !this.insideThink) {
                    // A number of chat templates emit a closing reasoning marker even when the
                    // corresponding opening marker was handled by a native provider channel.
                    // It is still control markup, not user-facing answer text. Consume it rather
                    // than leaking strings such as </think> into chat.
                    this.pending.delete(0, end + 1);
                    continue;
                }
            }

            // Not a recognized side-channel control tag. Preserve it exactly in the current channel.
            emit(tag, visible, exposed);
            this.pending.delete(0, end + 1);
        }
    }

    private static ParsedTag parseTag(String raw) {
        if (raw == null || raw.length() < 3) return null;
        boolean square = raw.charAt(0) == '[';
        String inner = raw.substring(1, raw.length() - 1).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        boolean closing = inner.startsWith("/");
        if (closing) inner = inner.substring(1);

        // Some families namespace their control tags (for example <seed:think>)
        // while channel-token templates wrap names in pipes. Preserve the native
        // marker for diagnostics, but classify by its semantic leaf name.
        String normalized = inner.replace('-', '_');
        while (normalized.startsWith("|") && normalized.endsWith("|") && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        String canonical = normalized;
        int namespace = canonical.lastIndexOf(':');
        if (namespace >= 0 && namespace + 1 < canonical.length()) {
            canonical = canonical.substring(namespace + 1);
        }

        ModelExposedData.Kind kind = switch (canonical) {
            case "think", "thinking", "thought" -> ModelExposedData.Kind.THOUGHT;
            case "reason", "reasoning", "rationale", "deliberation" -> ModelExposedData.Kind.REASONING;
            case "analysis" -> ModelExposedData.Kind.ANALYSIS;
            case "scratchpad", "scratch_pad", "working" -> ModelExposedData.Kind.SCRATCHPAD;
            case "plan", "planning" -> ModelExposedData.Kind.PLAN;
            case "reflection", "self_reflection" -> ModelExposedData.Kind.REFLECTION;
            case "critique", "self_critique" -> ModelExposedData.Kind.CRITIQUE;
            case "commentary" -> ModelExposedData.Kind.COMMENTARY;
            case "reasoning_summary", "reasoningsummary" -> ModelExposedData.Kind.REASONING_SUMMARY;
            default -> null;
        };
        if (kind == null) return null;

        String nativeChannel;
        if (kind == ModelExposedData.Kind.THOUGHT && "think".equals(canonical)) {
            if (normalized.contains(":")) {
                nativeChannel = (square ? "square_" : "xml_") + normalized.replace(':', '_');
            } else {
                nativeChannel = square ? "mistral_think" : "xml_think";
            }
        } else {
            nativeChannel = (square ? "square_" : "xml_") + normalized.replace(':', '_');
        }
        return new ParsedTag(canonical, kind, closing, nativeChannel);
    }

    private static int nextMarker(int angle, int square) {
        if (angle < 0) return square;
        if (square < 0) return angle;
        return Math.min(angle, square);
    }

    private void emit(String value, Consumer<String> visible, Consumer<ExposedChunk> exposed) {
        if (value == null || value.isEmpty()) return;
        if (this.insideThink) exposed.accept(new ExposedChunk(this.activeKind, value, this.markupStyle));
        else visible.accept(value);
    }

    public record ExposedChunk(ModelExposedData.Kind kind, String text, String nativeChannel) {
        public ExposedChunk {
            kind = kind == null ? ModelExposedData.Kind.OTHER : kind;
            text = text == null ? "" : text;
            nativeChannel = nativeChannel == null || nativeChannel.isBlank() ? "inline_exposed" : nativeChannel;
        }
    }

    private record ParsedTag(String name, ModelExposedData.Kind kind, boolean closing, String nativeChannel) {
    }

    public record Partition(
            String visibleText,
            String reasoningText,
            boolean sawThinkMarkup,
            boolean sawClosedThinkBlock,
            boolean endedInsideThink
    ) {
        public Partition {
            visibleText = visibleText == null ? "" : visibleText;
            reasoningText = reasoningText == null ? "" : reasoningText;
        }

        public boolean reasoningOnly() {
            return !this.reasoningText.isBlank() && this.visibleText.isBlank();
        }
    }
}
