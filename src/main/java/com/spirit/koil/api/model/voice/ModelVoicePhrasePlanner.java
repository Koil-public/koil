package com.spirit.koil.api.model.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Incrementally converts arbitrary model stream deltas into short speech
 * phrases. It emits a small first phrase for low time-to-speech, then uses
 * longer punctuation-aware phrases for smoother playback.
 */
public final class ModelVoicePhrasePlanner {
    private static final int FIRST_PHRASE_WORDS = 4;
    private static final int FOLLOWING_PHRASE_WORDS = 12;
    private static final int FIRST_LATENCY_WORDS = 2;
    private static final int FOLLOWING_LATENCY_WORDS = 7;
    private static final int MAXIMUM_PENDING_TOKEN_CHARS = 256;
    private static final Pattern PRONOUNCEABLE = Pattern.compile("[\\p{L}\\p{N}]");
    private static final Pattern TERMINAL_PUNCTUATION = Pattern.compile("[.!?…][\\\"')\\]]*$");
    private static final Pattern CLAUSE_PUNCTUATION = Pattern.compile("[,;:][\\\"')\\]]*$");

    private final StringBuilder pendingToken = new StringBuilder();
    private final StringBuilder pendingPhrase = new StringBuilder();
    private int pendingWords;
    private boolean emittedFirstPhrase;

    public List<ModelVoicePhrase> accept(String delta) {
        if (delta == null || delta.isEmpty()) {
            return List.of();
        }
        List<ModelVoicePhrase> ready = new ArrayList<>();
        for (int index = 0; index < delta.length(); index++) {
            char character = delta.charAt(index);
            if (Character.isWhitespace(character)) {
                completeToken(ready);
            } else if (this.pendingToken.length() < MAXIMUM_PENDING_TOKEN_CHARS) {
                this.pendingToken.append(character);
                if (isPhraseBoundary(character)) {
                    completeToken(ready);
                }
            }
        }
        return List.copyOf(ready);
    }

    public List<ModelVoicePhrase> flushForLatency() {
        int minimumWords = this.emittedFirstPhrase ? FOLLOWING_LATENCY_WORDS : FIRST_LATENCY_WORDS;
        if (this.pendingWords < minimumWords) {
            return List.of();
        }
        return List.of(emitPhrase(true));
    }

    public List<ModelVoicePhrase> finish() {
        List<ModelVoicePhrase> ready = new ArrayList<>();
        completeToken(ready);
        if (this.pendingWords > 0) {
            ready.add(emitPhrase(false));
        }
        return List.copyOf(ready);
    }

    public boolean hasCompletedWords() {
        return this.pendingWords > 0;
    }

    public boolean hasPendingText() {
        return this.pendingWords > 0 || !this.pendingToken.isEmpty();
    }

    public boolean emittedFirstPhrase() {
        return this.emittedFirstPhrase;
    }

    private void completeToken(List<ModelVoicePhrase> ready) {
        if (this.pendingToken.isEmpty()) {
            return;
        }
        String token = this.pendingToken.toString();
        this.pendingToken.setLength(0);
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("file:")
                || lower.startsWith("data:")) {
            return;
        }
        String spoken = sanitizeToken(token);
        if (spoken.isBlank() || !PRONOUNCEABLE.matcher(spoken).find()) {
            return;
        }
        if (!this.pendingPhrase.isEmpty()) {
            this.pendingPhrase.append(' ');
        }
        this.pendingPhrase.append(spoken);
        this.pendingWords++;

        int targetWords = this.emittedFirstPhrase ? FOLLOWING_PHRASE_WORDS : FIRST_PHRASE_WORDS;
        boolean terminal = TERMINAL_PUNCTUATION.matcher(spoken).find()
                && this.pendingWords >= (this.emittedFirstPhrase ? 4 : 3);
        // Tiny colon/comma chunks create choppy speech and can overwhelm a
        // remote provider. Keep the first response quick, then wait for a
        // useful clause size while still honoring full sentence boundaries.
        boolean clause = CLAUSE_PUNCTUATION.matcher(spoken).find()
                && this.pendingWords >= (this.emittedFirstPhrase ? 9 : 4);
        if (terminal || clause || this.pendingWords >= targetWords) {
            ready.add(emitPhrase(!terminal));
        }
    }

    private ModelVoicePhrase emitPhrase(boolean continues) {
        String phrase = this.pendingPhrase.toString().strip();
        this.pendingPhrase.setLength(0);
        this.pendingWords = 0;
        this.emittedFirstPhrase = true;
        if (continues
                && !TERMINAL_PUNCTUATION.matcher(phrase).find()
                && !CLAUSE_PUNCTUATION.matcher(phrase).find()) {
            phrase += ",";
        }
        return new ModelVoicePhrase(phrase, expressionFor(phrase));
    }

    private static String sanitizeToken(String token) {
        return token
                .replaceAll("[`*_#|\\[\\]{}<>]", "")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static boolean isPhraseBoundary(char character) {
        return character == '.'
                || character == '!'
                || character == '?'
                || character == '…'
                || character == '。'
                || character == '！'
                || character == '？'
                || character == ','
                || character == ';'
                || character == ':';
    }

    private static ModelVoiceExpression expressionFor(String phrase) {
        Matcher terminal = TERMINAL_PUNCTUATION.matcher(phrase);
        if (terminal.find()) {
            String punctuation = terminal.group();
            if (punctuation.indexOf('!') >= 0) {
                return ModelVoiceExpression.EXCITED;
            }
            if (punctuation.indexOf('?') >= 0) {
                return ModelVoiceExpression.QUESTIONING;
            }
        }
        return ModelVoiceExpression.NEUTRAL;
    }
}
