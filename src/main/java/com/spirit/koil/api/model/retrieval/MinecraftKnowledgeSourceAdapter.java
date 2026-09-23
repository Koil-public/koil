package com.spirit.koil.api.model.retrieval;

import com.spirit.koil.api.minecraft.MinecraftKnowledgeService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/** Bridges bounded static Minecraft facts into historical retrieval; live game state stays in MinecraftKnowledgeService. */
public final class MinecraftKnowledgeSourceAdapter {
    private static final String SOURCE_ID = "minecraft.static";
    private static final Set<String> ALLOWED_KINDS = Set.of("registry", "recipe", "tag", "mod", "resource");
    private static final int FACTS_PER_ENTRY = 8;
    private final KoilRetrievalEngine engine;
    private String lastFingerprint = "";

    public MinecraftKnowledgeSourceAdapter(KoilRetrievalEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public CompletableFuture<SourceSyncResult> synchronizeWhenReady() {
        return MinecraftKnowledgeService.staticSnapshotForIndexing().thenCompose(snapshot -> {
            if (snapshot.fingerprint().equals(lastFingerprint)) return CompletableFuture.completedFuture(new SourceSyncResult(0, 0, 0, 0));
            return synchronize(snapshot).thenApply(result -> {
                lastFingerprint = snapshot.fingerprint();
                return result;
            });
        });
    }

    public CompletableFuture<SourceSyncResult> synchronize(MinecraftKnowledgeService.StaticKnowledgeSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        TreeMap<String, List<MinecraftKnowledgeService.StaticKnowledgeFact>> byKind = new TreeMap<>();
        snapshot.facts().stream()
                .filter(fact -> ALLOWED_KINDS.contains(fact.kind()))
                .filter(fact -> !fact.key().isBlank() && !fact.text().isBlank())
                .sorted(java.util.Comparator.comparing(MinecraftKnowledgeService.StaticKnowledgeFact::key))
                .forEach(fact -> byKind.computeIfAbsent(fact.kind(), ignored -> new java.util.ArrayList<>()).add(fact));
        List<KnowledgeEntry> entries = new java.util.ArrayList<>();
        byKind.forEach((kind, facts) -> {
            for (int start = 0; start < facts.size(); start += FACTS_PER_ENTRY) {
                entries.add(entry(kind, start / FACTS_PER_ENTRY, facts.subList(start, Math.min(facts.size(), start + FACTS_PER_ENTRY))));
            }
        });
        if (snapshot.fingerprint().isBlank()) return CompletableFuture.completedFuture(new SourceSyncResult(0, 0, 0, 0));
        return this.engine.synchronizeSource(new KnowledgeSourceSnapshot(SOURCE_ID, snapshot.fingerprint(), entries));
    }

    private KnowledgeEntry entry(String kind, int chunk, List<MinecraftKnowledgeService.StaticKnowledgeFact> facts) {
        String sourceKey = kind + ":" + chunk;
        String text = facts.stream().map(MinecraftKnowledgeService.StaticKnowledgeFact::text).collect(java.util.stream.Collectors.joining("\n\n"));
        String identifiers = facts.stream().map(MinecraftKnowledgeService.StaticKnowledgeFact::key).collect(java.util.stream.Collectors.joining(","));
        return new KnowledgeEntry(1L, KnowledgeType.MINECRAFT_KNOWLEDGE, "minecraft", text, SOURCE_ID, "",
                System.currentTimeMillis(), 0.55D, 0.8D, KnowledgeTrust.HISTORICAL_CONTEXT,
                new LinkedHashMap<>(java.util.Map.of(
                        "sourceId", SOURCE_ID,
                        "sourceKey", sourceKey,
                        "minecraftKind", kind,
                        "identifier", identifiers
                )));
    }
}
