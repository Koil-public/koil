package com.spirit.koil.api.model.voice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ModelVoiceRegistry {
    private static final Map<String, ModelVoiceProvider> PROVIDERS = new LinkedHashMap<>();

    static {
        register(new CyzonModelVoiceProvider());
        register(new MacOsSayModelVoiceProvider());
    }

    private ModelVoiceRegistry() {
    }

    public static synchronized void register(ModelVoiceProvider provider) {
        if (provider == null || provider.id() == null || provider.id().isBlank()) {
            throw new IllegalArgumentException("model voice provider id is required");
        }
        PROVIDERS.put(provider.id(), provider);
    }

    public static synchronized List<ModelVoiceDefinition> voices() {
        return PROVIDERS.values().stream()
                .flatMap(provider -> provider.voices().stream())
                .sorted(java.util.Comparator.comparing(ModelVoiceDefinition::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public static synchronized Optional<ModelVoiceDefinition> find(String voiceId) {
        if (voiceId == null || voiceId.isBlank()) {
            return Optional.empty();
        }
        return voices().stream().filter(voice -> voice.id().equalsIgnoreCase(voiceId.strip())).findFirst();
    }

    public static synchronized Optional<ModelVoiceProvider> providerFor(ModelVoiceDefinition voice) {
        return voice == null ? Optional.empty() : Optional.ofNullable(PROVIDERS.get(voice.providerId()));
    }
}
