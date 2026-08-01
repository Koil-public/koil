package com.spirit.koil.api.chat.klippy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class KlippyApi {

    private static final String API_BASE = "https://api.klippy.com/v1";

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final HttpClient client;
    private final String apiKey;

    public KlippyApi(String apiKey) {
        this.apiKey = apiKey;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public CompletableFuture<KlippyModels.SearchResponse> trending(int limit) {
        return get("/trending?limit=" + limit);
    }

    public CompletableFuture<KlippyModels.SearchResponse> search(String query, int limit) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return get("/search?q=" + encoded + "&limit=" + limit);
    }

    public CompletableFuture<KlippyModels.SearchResponse> search(String query, int limit, String next) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);

        StringBuilder builder = new StringBuilder()
            .append("/search?q=")
            .append(encoded)
            .append("&limit=")
            .append(limit);

        if (next != null && !next.isBlank()) {
            builder.append("&pos=")
                .append(URLEncoder.encode(next, StandardCharsets.UTF_8));
        }

        return get(builder.toString());
    }

    private CompletableFuture<KlippyModels.SearchResponse> get(String endpoint) {
        String seperator = endpoint.contains("?") ? "&" : "?";
        URI uri = URI.create(API_BASE + endpoint + seperator + "key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder()
            .header("Accept", "application/json")
            .GET().timeout(Duration.ofSeconds(20))
            .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    throw new CompletionException(
                        new IOException(
                            "Klippy API returned HTTP "
                                + response.statusCode()
                                + "\n"
                                + response.body()
                        )
                    );
                }

                try {
                    return MAPPER.readValue(
                        response.body(),
                        KlippyModels.SearchResponse.class
                    );
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            });
    }
}
