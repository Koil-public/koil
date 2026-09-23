package com.spirit.koil.api.model.provider.llamacpp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.model.LocalModelRuntimeLog;
import com.spirit.koil.api.model.retrieval.EmbeddingIdentity;
import com.spirit.koil.api.model.retrieval.EmbeddingProvider;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Local-only OpenAI-compatible llama.cpp embedding client; it never starts Python or a chat model. */
public final class LlamaCppEmbeddingProvider implements EmbeddingProvider {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String baseUrl;
    private final String apiKey;
    private final EmbeddingIdentity identity;
    private final OkHttpClient http;
    private final ExecutorService requests;
    private volatile boolean closed;

    public LlamaCppEmbeddingProvider(String host, int port, String apiKey, EmbeddingIdentity identity) {
        this(host, port, apiKey, identity, new OkHttpClient.Builder()
                .connectTimeout(5L, TimeUnit.SECONDS).readTimeout(2L, TimeUnit.MINUTES).build());
    }

    LlamaCppEmbeddingProvider(String host, int port, String apiKey, EmbeddingIdentity identity, OkHttpClient http) {
        String normalizedHost = host == null ? "" : host.strip();
        if (!("127.0.0.1".equals(normalizedHost) || "localhost".equalsIgnoreCase(normalizedHost) || "::1".equals(normalizedHost))) {
            throw new IllegalArgumentException("llama.cpp embeddings must use a localhost endpoint");
        }
        if (port <= 0 || port > 65_535) throw new IllegalArgumentException("embedding port must be valid");
        this.apiKey = apiKey == null ? "" : apiKey;
        if (this.apiKey.isBlank()) throw new IllegalArgumentException("local llama.cpp embedding API key is required");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.http = Objects.requireNonNull(http, "http");
        this.baseUrl = "http://" + normalizedHost + ':' + port;
        this.requests = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "koil-llama-embedding");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public EmbeddingIdentity identity() {
        return this.identity;
    }

    @Override
    public CompletableFuture<List<float[]>> embed(List<String> texts) {
        List<String> input = texts == null ? List.of() : texts.stream().map(value -> value == null ? "" : value).toList();
        if (input.isEmpty()) return CompletableFuture.completedFuture(List.of());
        if (this.closed) return CompletableFuture.failedFuture(new IllegalStateException("llama.cpp embedding provider is closed"));
        return CompletableFuture.supplyAsync(() -> embedBlocking(input), this.requests);
    }

    private List<float[]> embedBlocking(List<String> texts) {
        long started = System.nanoTime();
        JsonObject payload = new JsonObject();
        payload.addProperty("model", this.identity.modelId());
        JsonArray input = new JsonArray();
        texts.forEach(input::add);
        payload.add("input", input);
        Request request = new Request.Builder().url(this.baseUrl + "/v1/embeddings")
                .header("Authorization", "Bearer " + this.apiKey)
                .post(RequestBody.create(payload.toString(), JSON)).build();
        try (Response response = this.http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("llama.cpp embedding request failed with HTTP " + response.code());
            }
            List<float[]> vectors = parse(response.body().string(), texts.size());
            LocalModelRuntimeLog.write("embedding_request", "model=" + this.identity.modelId()
                    + " count=" + texts.size() + " millis=" + ((System.nanoTime() - started) / 1_000_000L));
            return vectors;
        } catch (IOException exception) {
            throw new IllegalStateException("llama.cpp embedding request failed: " + exception.getMessage(), exception);
        }
    }

    private List<float[]> parse(String body, int expected) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("data")) {
            throw new IllegalStateException("llama.cpp embedding response has no data array");
        }
        JsonArray rows = root.getAsJsonObject().getAsJsonArray("data");
        if (rows.size() != expected) throw new IllegalStateException("llama.cpp embedding response count does not match request");
        List<float[]> ordered = new ArrayList<>(java.util.Collections.nCopies(expected, null));
        for (JsonElement row : rows) {
            if (!row.isJsonObject()) throw new IllegalStateException("llama.cpp embedding row is invalid");
            JsonObject object = row.getAsJsonObject();
            int index = object.has("index") ? object.get("index").getAsInt() : -1;
            if (index < 0 || index >= expected || ordered.get(index) != null || !object.has("embedding")) {
                throw new IllegalStateException("llama.cpp embedding response indexes are invalid");
            }
            JsonArray values = object.getAsJsonArray("embedding");
            if (values.size() != this.identity.dimensions()) {
                throw new IllegalStateException("llama.cpp embedding dimensions do not match " + this.identity.dimensions());
            }
            float[] vector = new float[values.size()];
            for (int coordinate = 0; coordinate < vector.length; coordinate++) {
                vector[coordinate] = values.get(coordinate).getAsFloat();
                if (!Float.isFinite(vector[coordinate])) throw new IllegalStateException("llama.cpp embedding contains a non-finite value");
            }
            ordered.set(index, vector);
        }
        if (ordered.stream().anyMatch(Objects::isNull)) throw new IllegalStateException("llama.cpp embedding response omitted an index");
        return List.copyOf(ordered);
    }

    @Override
    public void close() {
        this.closed = true;
        this.requests.shutdownNow();
    }
}
