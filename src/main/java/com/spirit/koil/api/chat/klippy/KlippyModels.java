package com.spirit.koil.api.chat.klippy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

public final class KlippyModels {

    private KlippyModels() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResponse(
        List<Gif> results,
        String next
    ) {

    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Gif(
        String id,
        String title,
        String content_description,
        String itemurl,
        Map<String, Media> media_formats,
        List<String> tags,
        double created,
        boolean hasaudio
    ) {

    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Media(
        String url,
        int width,
        int height,
        long size,
        double duration,
        String preview
    ) {

    }
}
