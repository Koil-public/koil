package com.spirit.koil.api.model.voice;

public record ModelVoicePhrase(String text, ModelVoiceExpression expression) {
    public ModelVoicePhrase {
        text = text == null ? "" : text.strip();
        expression = expression == null ? ModelVoiceExpression.NEUTRAL : expression;
        if (text.isBlank()) {
            throw new IllegalArgumentException("model voice phrase is empty");
        }
    }
}
