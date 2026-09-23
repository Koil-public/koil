package com.spirit.koil.api.model.tool;

/** Provider-neutral, bounded public search result. */
public record InternetSearchResult(String title, String url, String snippet, String publishedAt, int providerRank) {
    public InternetSearchResult {
        title = title == null ? "" : title.strip();
        url = url == null ? "" : url.strip();
        snippet = snippet == null ? "" : snippet.strip();
        publishedAt = publishedAt == null ? "" : publishedAt.strip();
        providerRank = Math.max(1, providerRank);
    }
}
