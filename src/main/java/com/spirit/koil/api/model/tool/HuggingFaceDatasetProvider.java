package com.spirit.koil.api.model.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * First Dataset Intelligence provider. Uses documented Hub/Dataset Viewer HTTP
 * contracts directly from Java rather than embedding the Python client.
 */
public final class HuggingFaceDatasetProvider implements DatasetProvider {
    private static final URI HUB = URI.create("https://huggingface.co");
    private static final URI VIEWER = URI.create("https://datasets-server.huggingface.co");
    private static final int MAX_RESPONSE_BYTES = 2_000_000;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override public String id() { return "dataset_hub"; }

    @Override public JsonObject search(String query, int limit) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("search", query);
        params.put("limit", Integer.toString(Math.max(1, Math.min(20, limit))));
        params.put("full", "false");
        JsonElement payload = get(HUB.resolve("/api/datasets"), params);
        JsonObject out = new JsonObject();
        out.add("datasets", payload);
        return out;
    }

    @Override public JsonObject inspect(String dataset) throws Exception {
        String encoded = pathSegment(dataset);
        JsonObject out = new JsonObject();
        out.addProperty("dataset", dataset);
        out.add("hub", object(get(HUB.resolve("/api/datasets/" + encoded), Map.of())));
        out.add("capabilities", object(get(VIEWER.resolve("/is-valid"), Map.of("dataset", dataset))));
        out.add("splits", object(get(VIEWER.resolve("/splits"), Map.of("dataset", dataset))));
        try {
            out.add("size", object(get(VIEWER.resolve("/size"), Map.of("dataset", dataset))));
        } catch (ProviderException failure) {
            if (!failure.retryable()) throw failure;
            JsonObject unavailable = new JsonObject();
            unavailable.addProperty("available", false);
            unavailable.addProperty("reason", failure.getMessage());
            out.add("size", unavailable);
        }
        return out;
    }

    @Override public JsonObject rows(String dataset, String config, String split, int offset, int length) throws Exception {
        return object(get(VIEWER.resolve("/rows"), slice(dataset, config, split, offset, length)));
    }

    @Override public JsonObject query(String dataset, String config, String split, String query, int offset, int length) throws Exception {
        Map<String, String> params = slice(dataset, config, split, offset, length);
        params.put("query", query);
        return object(get(VIEWER.resolve("/search"), params));
    }

    @Override public JsonObject filter(String dataset, String config, String split, String where, String orderBy, int offset, int length) throws Exception {
        Map<String, String> params = slice(dataset, config, split, offset, length);
        params.put("where", where);
        if (orderBy != null && !orderBy.isBlank()) params.put("orderby", orderBy.strip());
        return object(get(VIEWER.resolve("/filter"), params));
    }

    @Override public JsonObject stats(String dataset, String config, String split) throws Exception {
        return object(get(VIEWER.resolve("/statistics"), Map.of(
                "dataset", dataset, "config", config, "split", split)));
    }

    @Override public JsonObject parquet(String dataset) throws Exception {
        return object(get(VIEWER.resolve("/parquet"), Map.of("dataset", dataset)));
    }

    private static Map<String, String> slice(String dataset, String config, String split, int offset, int length) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dataset", dataset);
        params.put("config", config);
        params.put("split", split);
        params.put("offset", Integer.toString(Math.max(0, offset)));
        params.put("length", Integer.toString(Math.max(1, Math.min(100, length))));
        return params;
    }

    private static JsonElement get(URI base, Map<String, String> parameters) throws Exception {
        URI uri = withQuery(base, parameters);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .GET().timeout(Duration.ofSeconds(25))
                .header("Accept", "application/json")
                .header("User-Agent", "Koil-Dataset-Intelligence/1");
        String token = token();
        if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
        HttpResponse<byte[]> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] bytes = response.body() == null ? new byte[0] : response.body();
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new ProviderException("dataset_response_too_large", "Dataset provider response exceeded the 2 MB bound.", false, response.statusCode());
        }
        String body = new String(bytes, StandardCharsets.UTF_8);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String code = response.statusCode() == 401 || response.statusCode() == 403
                    ? "dataset_auth_or_gating_required"
                    : response.statusCode() == 429 ? "dataset_rate_limited" : "dataset_provider_http_error";
            boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
            String retry = response.headers().firstValue("retry-after").orElse("");
            throw new ProviderException(code,
                    "Dataset provider returned HTTP " + response.statusCode() + (retry.isBlank() ? "" : " (retry-after " + retry + ")."),
                    retryable, response.statusCode());
        }
        try {
            return JsonParser.parseString(body.isBlank() ? "{}" : body);
        } catch (RuntimeException invalid) {
            throw new ProviderException("dataset_invalid_json", "Dataset provider returned malformed JSON.", false, response.statusCode());
        }
    }

    private static URI withQuery(URI base, Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) return base;
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (entry.getValue() == null) continue;
            if (!query.isEmpty()) query.append('&');
            query.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return URI.create(base.toString() + "?" + query);
    }

    private static String pathSegment(String dataset) {
        String[] parts = dataset.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (!out.isEmpty()) out.append('/');
            out.append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

    private static JsonObject object(JsonElement element) {
        if (element != null && element.isJsonObject()) return element.getAsJsonObject();
        JsonObject wrapper = new JsonObject();
        wrapper.add("value", element == null ? new JsonObject() : element);
        return wrapper;
    }

    private static String token() {
        String property = System.getProperty("koil.huggingface.token", "").strip();
        if (!property.isBlank()) return property;
        String environment = System.getenv("HF_TOKEN");
        return environment == null ? "" : environment.strip();
    }

    public static final class ProviderException extends Exception {
        private final String code;
        private final boolean retryable;
        private final int statusCode;
        ProviderException(String code, String message, boolean retryable, int statusCode) {
            super(message);
            this.code = code;
            this.retryable = retryable;
            this.statusCode = statusCode;
        }
        public String code() { return code; }
        public boolean retryable() { return retryable; }
        public int statusCode() { return statusCode; }
    }
}
