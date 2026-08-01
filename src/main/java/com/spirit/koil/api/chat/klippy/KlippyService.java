package com.spirit.koil.api.chat.klippy;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class KlippyService {

    private final KlippyApi api;

    public KlippyService(KlippyApi api) {
        this.api = api;
    }

    public CompletableFuture<List<GifSearchResult>> trending() {
        return api.trending(25)
            .thenApply(this::mapResults);
    }

    public CompletableFuture<List<GifSearchResult>> search(String query) {
        if (query == null || query.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return api.search(query, 25)
            .thenApply(this::mapResults);
    }

    public CompletableFuture<GifPage> searchPage(String query, String next) {
        return api.search(query, 25, next)
            .thenApply(response -> new GifPage(
                mapResults(response),
                response.next()
            ));
    }

    public CompletableFuture<GifPage> trendingPage() {
        return api.trending(25)
            .thenApply(response -> new GifPage(
                mapResults(response),
                response.next()
            ));
    }

    private List<GifSearchResult> mapResults(
        KlippyModels.SearchResponse response
    ) {
        if (response == null || response.results() == null) {
            return List.of();
        }

        return response.results()
            .stream()
            .map(this::mapGif)
            .toList();
    }

    private GifSearchResult mapGif(KlippyModels.Gif gif) {
        KlippyModels.Media preview = null;
        KlippyModels.Media full = null;

        if (gif.media_formats() != null) {
            preview = gif.media_formats().get("tinygif");

            if (preview == null) {
                preview = gif.media_formats().get("nanogif");
            }

            full = gif.media_formats().get("gif");

            if (full == null) {
                full = preview;
            }
        }

        return new GifSearchResult(
            gif.id(),
            gif.title() == null || gif.title().isBlank()
                ? "Untitled GIF"
                : gif.title(),
            preview != null
                ? preview.url()
                : "",
            full != null
                ? full.url()
                : "",
            full != null
                ? full.width()
                : 0,
            full != null
                ? full.height()
                : 0
        );
    }

    public record GifPage(
        List<GifSearchResult> gifs,
        String next
    ) {
    }
}
