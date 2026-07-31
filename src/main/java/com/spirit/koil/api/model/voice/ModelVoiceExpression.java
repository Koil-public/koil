package com.spirit.koil.api.model.voice;

/**
 * A provider-neutral speaking intent. Providers that expose expressive speech
 * can use it directly; simpler providers still receive the original
 * punctuation so their native prosody remains available.
 */
public enum ModelVoiceExpression {
    NEUTRAL,
    EXCITED,
    QUESTIONING
}
