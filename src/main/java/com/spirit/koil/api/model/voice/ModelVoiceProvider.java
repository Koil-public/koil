package com.spirit.koil.api.model.voice;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public interface ModelVoiceProvider {
    String id();

    List<ModelVoiceDefinition> voices();

    Path synthesize(String voiceId, String text, Path outputDirectory) throws Exception;

    default Set<ModelVoiceExpression> supportedExpressions() {
        return EnumSet.of(ModelVoiceExpression.NEUTRAL);
    }

    default Path synthesize(
            String voiceId,
            String text,
            ModelVoiceExpression expression,
            Path outputDirectory
    ) throws Exception {
        return synthesize(voiceId, text, outputDirectory);
    }
}
