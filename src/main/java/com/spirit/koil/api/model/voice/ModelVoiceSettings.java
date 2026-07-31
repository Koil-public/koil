package com.spirit.koil.api.model.voice;

public record ModelVoiceSettings(boolean enabled, String voiceId) {
    public static final String DEFAULT_VOICE_ID = "cyzon:default";

    public ModelVoiceSettings {
        voiceId = voiceId == null || voiceId.isBlank() ? DEFAULT_VOICE_ID : voiceId.strip();
    }

    public static ModelVoiceSettings defaults() {
        return new ModelVoiceSettings(false, DEFAULT_VOICE_ID);
    }
}
