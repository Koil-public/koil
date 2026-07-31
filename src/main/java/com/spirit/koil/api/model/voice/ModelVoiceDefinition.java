package com.spirit.koil.api.model.voice;

public record ModelVoiceDefinition(
        String id,
        String displayName,
        String providerId,
        boolean remote
) {
    public ModelVoiceDefinition {
        id = clean(id);
        displayName = clean(displayName);
        providerId = clean(providerId);
        if (id.isBlank() || displayName.isBlank() || providerId.isBlank()) {
            throw new IllegalArgumentException("model voice definition is incomplete");
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
