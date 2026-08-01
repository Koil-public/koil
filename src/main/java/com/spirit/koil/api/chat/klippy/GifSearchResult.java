package com.spirit.koil.api.chat.klippy;

public record GifSearchResult(
    String id,
    String title,
    String previewUrl,
    String gifUrl,
    int width,
    int height
) {

}
