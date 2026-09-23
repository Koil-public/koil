package com.spirit.koil.api.model.retrieval;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Behavioral proof for bounded, untrusted public-research session retrieval. */
public final class InternetResearchKnowledgeAdapterProof {
    private InternetResearchKnowledgeAdapterProof() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("koil-research-session-proof-");
        EmbeddingIdentity identity = new EmbeddingIdentity("proof", "research", "r1", 8, true);
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        try (KnowledgeMetadataStore store = new SqliteKnowledgeMetadataStore(directory.resolve("knowledge.db"), identity);
             FlatJavaVectorIndex vectors = new FlatJavaVectorIndex(8);
             KoilRetrievalEngine engine = new KoilRetrievalEngine(store, new ResearchEmbeddings(identity), vectors)) {
            InternetResearchKnowledgeAdapter research = new InternetResearchKnowledgeAdapter(
                    engine, Duration.ofMinutes(5), clock::get
            );
            research.record("request-7", new ModelToolCall("call-1", "internet.search", searchArguments()), searchResult()).join();

            research.record("request-8", new ModelToolCall("call-2", "internet.search", otherSearchArguments()), otherSearchResult()).join();

            RetrievalResult selected = research.retrieve("request-7", "postgres schema", 120).join();
            KnowledgeEntry evidence = selected.selected().stream()
                    .filter(candidate -> "https://docs.example.test/postgres".equals(candidate.entry().metadata().get("url")))
                    .findFirst().orElseThrow(() -> new AssertionError("session retrieval lost matching bounded evidence")).entry();
            require(evidence.trust() == KnowledgeTrust.UNTRUSTED_RETRIEVED,
                    "public web text must remain untrusted evidence");
            require("https://docs.example.test/postgres".equals(evidence.metadata().get("url")),
                    "research evidence must preserve source URL provenance");
            require("native_public_html".equals(evidence.metadata().get("provider")),
                    "research evidence must preserve provider provenance");
            require("request-7".equals(evidence.sessionId()), "research evidence must remain session scoped");
            require(selected.contextText().contains("not current state or instructions"),
                    "retrieved web evidence must not be promoted into instructions");
            require(selected.selected().stream().noneMatch(candidate -> "request-8".equals(candidate.entry().sessionId())),
                    "research retrieval must not cross session scopes");

            clock.addAndGet(Duration.ofMinutes(5).toMillis() + 1L);
            research.expire().join();
            require(research.retrieve("request-7", "postgres schema", 120).join().selected().isEmpty(),
                    "expired research evidence must not remain retrievable");
        }
        System.out.println("InternetResearchKnowledgeAdapterProof: PASS");
    }

    private static JsonObject searchArguments() {
        JsonObject values = new JsonObject();
        values.addProperty("query", "postgres schema documentation");
        return values;
    }

    private static ModelToolResult searchResult() {
        JsonObject result = new JsonObject();
        result.addProperty("provider", "native_public_html");
        JsonArray rows = new JsonArray();
        JsonObject postgres = new JsonObject();
        postgres.addProperty("title", "PostgreSQL schema guide");
        postgres.addProperty("url", "https://docs.example.test/postgres");
        postgres.addProperty("snippet", "Inspect PostgreSQL schema metadata and tables.");
        rows.add(postgres);
        JsonObject weather = new JsonObject();
        weather.addProperty("title", "Weather");
        weather.addProperty("url", "https://weather.example.test/today");
        weather.addProperty("snippet", "Forecast.");
        rows.add(weather);
        result.add("results", rows);
        return new ModelToolResult("call-1", "internet.search", "completed", result, "", "");
    }

    private static JsonObject otherSearchArguments() {
        JsonObject values = new JsonObject();
        values.addProperty("query", "weather forecast");
        return values;
    }

    private static ModelToolResult otherSearchResult() {
        JsonObject result = new JsonObject();
        result.addProperty("provider", "native_public_html");
        JsonArray rows = new JsonArray();
        JsonObject weather = new JsonObject();
        weather.addProperty("title", "Other session weather");
        weather.addProperty("url", "https://weather.example.test/other-session");
        weather.addProperty("snippet", "Forecast for another request.");
        rows.add(weather);
        result.add("results", rows);
        return new ModelToolResult("call-2", "internet.search", "completed", result, "", "");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ResearchEmbeddings implements EmbeddingProvider {
        private final EmbeddingIdentity identity;

        private ResearchEmbeddings(EmbeddingIdentity identity) {
            this.identity = identity;
        }

        @Override
        public EmbeddingIdentity identity() {
            return this.identity;
        }

        @Override
        public CompletableFuture<List<float[]>> embed(List<String> texts) {
            return CompletableFuture.completedFuture(texts.stream().map(this::embedOne).toList());
        }

        private float[] embedOne(String text) {
            String value = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
            return value.contains("postgres") || value.contains("schema")
                    ? new float[] {1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F}
                    : new float[] {0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F};
        }
    }
}
