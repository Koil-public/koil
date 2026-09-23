package com.spirit.koil.api.context;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import com.spirit.koil.api.model.tool.LocalModelToolCatalog;
import com.spirit.koil.api.model.ModelToolCall;
import com.google.gson.JsonObject;

/** Contract proof for canonical context registration, reversible compaction, and L5 active eviction. */
public final class ContextIntelligenceProof {
    private ContextIntelligenceProof() {
    }

    public static void main(String[] args) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-09-17T18:30:00Z"), ZoneOffset.UTC);
        ContextStore store = new InMemoryContextStore(clock, 8);
        ContextArtifact artifact = store.register(new ContextArtifactRequest(
                "tool-result", "https://example.test/catalogue",
                "model=Small\nmodel=Large\nmodel=Large\nmodel=Large\nstatus=complete", 600L
        ));
        require(artifact.id().startsWith("ctx-") && artifact.level() == ContextRepresentationLevel.L0_EXACT,
                "registration must issue a stable exact context reference");

        ContextOptimizer optimizer = new DeterministicContextOptimizer();
        ContextRepresentation structural = optimizer.optimize(artifact, new ContextOptimizationRequest(
                ContextRepresentationLevel.L2_STRUCTURAL, 80, "extract model names"
        ));
        require(structural.level() == ContextRepresentationLevel.L2_STRUCTURAL
                        && structural.content().contains("model=Large"),
                "structural compaction must retain objective-relevant distinct evidence");
        require(store.retrieve(artifact.id()).orElseThrow().canonicalContent().equals(artifact.canonicalContent()),
                "compaction must never overwrite canonical content");

        ContextRepresentation evicted = optimizer.optimize(artifact, new ContextOptimizationRequest(
                ContextRepresentationLevel.L5_EVICTED_RETRIEVABLE, 8, ""
        ));
        require(evicted.level() == ContextRepresentationLevel.L5_EVICTED_RETRIEVABLE
                        && evicted.content().equals("[" + artifact.id() + "]"),
                "L5 must remove the active body while preserving an exact reference");
        require(store.retrieve(artifact.id()).orElseThrow().canonicalContent().contains("model=Small"),
                "L5 reference must still rehydrate canonical content");
        require(store.search("local", "large", 2).stream().map(ContextArtifact::id).toList().contains(artifact.id()),
                "bounded store search must find only matching current-scope context artifacts");
        require(store.stats("local").artifactCount() == 1L && store.stats("other").artifactCount() == 0L,
                "context store statistics must remain scope-filtered");

        ContextOptimizer unavailableProvider = (ignoredArtifact, ignoredRequest) -> {
            throw new IllegalStateException("provider unavailable");
        };
        ContextRepresentation failOpen = new FailOpenContextOptimizer(unavailableProvider, optimizer).optimize(artifact,
                new ContextOptimizationRequest(ContextRepresentationLevel.L2_STRUCTURAL, 80, "extract model names"));
        require(failOpen.level() == ContextRepresentationLevel.L2_STRUCTURAL
                        && failOpen.content().contains("model=Small")
                        && failOpen.strategy().startsWith("fail-open:"),
                "an unavailable optimization provider must return the deterministic bounded representation");

        ContextStore pinnedStore = new InMemoryContextStore(clock, 1);
        ContextArtifact pinned = pinnedStore.register(new ContextArtifactRequest("observation", "first", "keep me", 600L));
        require(pinnedStore.pin(pinned.id()), "a registered context reference must be pinnable");
        pinnedStore.register(new ContextArtifactRequest("observation", "second", "evict me", 600L));
        require(pinnedStore.retrieve(pinned.id()).isPresent(), "a pin must protect a reference from ordinary LRU eviction");
        require(pinnedStore.release(pinned.id()), "a pin must be releasable without deleting canonical content");

        Path database = Files.createTempFile("koil-context-proof", ".db");
        Files.deleteIfExists(database);
        String durableId;
        try (SqliteContextStore durable = new SqliteContextStore(database, clock, 8)) {
            ContextArtifact durableArtifact = durable.register(new ContextArtifactRequest(
                    "kms-proof", "tool-result", "durable", "retained across a restart", 600L
            ));
            durableId = durableArtifact.id();
            require(durable.retrieve("other-kms", durableId).isEmpty(),
                    "a context reference must not cross its owning scope");
            require(durable.pin("kms-proof", durableId), "a scoped durable reference must be pinnable");
        }
        try (SqliteContextStore reopened = new SqliteContextStore(database, clock, 8)) {
            require(reopened.retrieve("kms-proof", durableId).map(ContextArtifact::canonicalContent)
                            .filter("retained across a restart"::equals).isPresent(),
                    "a durable context reference must survive a store restart");
            require(reopened.release("kms-proof", durableId),
                    "a durable pin must be releasable without deleting the reference");
        } finally {
            Files.deleteIfExists(database);
            Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-wal"));
            Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-shm"));
        }
        require(LocalModelToolCatalog.toolsForPrompt("use context.inspect for ctx-000001").stream()
                        .anyMatch(tool -> "context.inspect".equals(tool.id())),
                "the central model tool catalog must expose an explicitly requested context operation");
        require(LocalModelToolCatalog.informationToolsForPrompt("use context.retrieve for ctx-000001").stream()
                        .anyMatch(tool -> "context.retrieve".equals(tool.id())),
                "read-only model requests must receive context retrieval without Automation mode");
        JsonObject compressArguments = new JsonObject();
        compressArguments.addProperty("content", "compact this exact current evidence");
        compressArguments.addProperty("sourceType", "proof");
        var compressed = ContextIntelligenceService.execute("tool-proof", new ModelToolCall(
                "compress", ContextIntelligenceService.COMPRESS, compressArguments)).join();
        require("completed".equals(compressed.status()), "a model-facing context compress call must register a real artifact");
        String reference = compressed.output().get("ref").getAsString();
        JsonObject retrieveArguments = new JsonObject(); retrieveArguments.addProperty("ref", reference);
        var retrieved = ContextIntelligenceService.execute("tool-proof", new ModelToolCall(
                "retrieve", ContextIntelligenceService.RETRIEVE, retrieveArguments)).join();
        require("completed".equals(retrieved.status())
                        && "compact this exact current evidence".equals(retrieved.output().get("content").getAsString()),
                "a model-facing context retrieve call must rehydrate canonical evidence in its request scope");
        require("failed".equals(ContextIntelligenceService.execute("other-proof", new ModelToolCall(
                "retrieve-other", ContextIntelligenceService.RETRIEVE, retrieveArguments)).join().status()),
                "a model-facing context reference must not cross request scopes");
        JsonObject searchArguments = new JsonObject(); searchArguments.addProperty("query", "exact current");
        var searched = ContextIntelligenceService.execute("tool-proof", new ModelToolCall(
                "search", ContextIntelligenceService.SEARCH, searchArguments)).join();
        require("completed".equals(searched.status()) && containsReference(searched.output().getAsJsonArray("results"), reference),
                "a model-facing context search must expose only bounded current-scope references");
        var stats = ContextIntelligenceService.execute("tool-proof", new ModelToolCall(
                "stats", ContextIntelligenceService.STATS, new JsonObject())).join();
        require("completed".equals(stats.status()) && stats.output().get("artifactCount").getAsLong() >= 1L,
                "a model-facing context stats call must report bounded current-scope diagnostics");
        ContextRepresentation automatic = ContextIntelligenceService.automaticProjection(
                "assembly-proof", "executor-observation", "prefetch", "same\nsame\nimportant=exact\nother=exact", "read current state", 24);
        require(automatic.artifactId().startsWith("ctx-") && automatic.activeCharacters() <= 24
                        && automatic.content().contains("important=exact"),
                "automatic assembly must register a retrievable bounded projection without mutating canonical evidence");
        require(ContextIntelligenceService.execute("assembly-proof", new ModelToolCall(
                "retrieve-assembly", ContextIntelligenceService.RETRIEVE, referenceArguments(automatic.artifactId()))).join()
                        .output().get("content").getAsString().contains("same\nsame"),
                "automatic assembly must retain the exact source for current-scope rehydration");
        System.out.println("ContextIntelligenceProof: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static boolean containsReference(com.google.gson.JsonArray values, String reference) {
        for (com.google.gson.JsonElement value : values) {
            if (reference.equals(value.getAsJsonObject().get("ref").getAsString())) return true;
        }
        return false;
    }

    private static JsonObject referenceArguments(String reference) {
        JsonObject values = new JsonObject();
        values.addProperty("ref", reference);
        return values;
    }
}
