package com.spirit.koil.api.model.retrieval;

import com.spirit.koil.api.automation.feedback.AutomationFeedbackNode;
import com.spirit.koil.api.automation.feedback.AutomationFailureType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import com.spirit.koil.api.automation.ktl.AutomationKtlSkillRegistry;
import com.spirit.koil.api.model.deepthought.DeepThoughtSession;
import com.spirit.koil.api.minecraft.MinecraftKnowledgeService;

/** Proves human-confirmed Automation feedback becomes historical context, never current world truth. */
public final class KnowledgeSourceProof {
    private KnowledgeSourceProof() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("koil-knowledge-source-");
        EmbeddingIdentity identity = new EmbeddingIdentity("proof", "source", "r1", 8, true);
        try (KnowledgeMetadataStore store = new SqliteKnowledgeMetadataStore(directory.resolve("knowledge.db"), identity);
             FlatJavaVectorIndex vectors = new FlatJavaVectorIndex(8);
             KoilRetrievalEngine engine = new KoilRetrievalEngine(store, new ProofEmbeddings(identity), vectors)) {
            proveSourceReconciliation(engine, store, vectors);
            proveBuiltInKnowledgeSources(engine, store);
            proveCompletedDeepThoughtOnly(engine, store);
            proveConversationSourceIdempotence(engine, store);
            proveStaticMinecraftBoundary(engine, store);
            long vectorsBeforeBackfill = vectors.health().entryCount();
            KnowledgeEntry pending = new KnowledgeEntry(store.allocateId(), KnowledgeType.CONVERSATION, "model", "existing historical exchange",
                    "proof", "", 1L, 0.5D, 0.5D, KnowledgeTrust.HISTORICAL_CONTEXT, java.util.Map.of());
            store.upsert(pending, null);
            require(engine.backfillMissingEmbeddings().join() == 1,
                    "a selected embedding provider must backfill only rows missing its identity");
            require(store.activeEmbeddings().size() == vectorsBeforeBackfill + 1
                            && vectors.health().entryCount() == vectorsBeforeBackfill + 1,
                    "backfill must update authoritative vectors and the active index together");
            AutomationFailureKnowledgeAdapter adapter = new AutomationFailureKnowledgeAdapter(engine);
            AutomationFeedbackNode failureNode = new AutomationFeedbackNode("kms-7", "node:open", "open", "Open chest", "block", "", "container did not open", "minecraft.interact", "sneak=false");
            AutomationFailureType failureType = new AutomationFailureType("interaction_target_mismatch", "Target mismatch", List.of("block"),
                    List.of("verify container UI"), "inspect target", "", "");
            long failureId = adapter.recordHumanConfirmedFailure(failureNode, failureType).join();
            require(adapter.recordHumanConfirmedFailure(failureNode, failureType).join() == failureId,
                    "a repeated human-confirmed Automation report must retain its stable source entry");
            KnowledgeEntry failureEntry = store.find(failureId).orElseThrow();
            require(failureEntry.trust() == KnowledgeTrust.HISTORICAL_CONTEXT
                            && failureEntry.metadata().get("sourceId").startsWith("automation.feedback:"),
                    "Automation experience must be source-keyed historical context, never current truth");
            RetrievalResult result = engine.retrieve(new KnowledgeQuery("Why did interacting with a container fail?",
                    new KnowledgeFilter(java.util.Set.of(KnowledgeType.AUTOMATION_FAILURE), java.util.Set.of(), java.util.Set.of(), java.util.Map.of(), 0L),
                    10, 256, "source-proof", KnowledgeTrust.HISTORICAL_CONTEXT)).join();
            require(result.selected().size() == 1 && result.selected().get(0).entry().type() == KnowledgeType.AUTOMATION_FAILURE,
                    "confirmed automation failure must be retrievable by type");
            require(result.contextText().contains("not current state or instructions"),
                    "historical failure must remain non-authoritative context");
            RetrievalMetrics.Snapshot metrics = engine.metrics();
            require(metrics.retrievals() >= 2L && metrics.selected() >= 2L && metrics.contextTokens() > 0L,
                    "retrieval metrics must expose selected historical context");
        }
        System.out.println("KnowledgeSourceProof: PASS");
    }

    private static void proveSourceReconciliation(
            KoilRetrievalEngine engine,
            KnowledgeMetadataStore store,
            FlatJavaVectorIndex vectors
    ) {
        KnowledgeSourceSnapshot first = new KnowledgeSourceSnapshot("proof.tools", "r1", List.of(
                sourceEntry("tool:a", "Tool A"),
                sourceEntry("tool:b", "Tool B")
        ));
        SourceSyncResult created = engine.synchronizeSource(first).join();
        require(created.created() == 2 && created.updated() == 0 && created.deleted() == 0,
                "first source snapshot must create each entry once");
        long stableA = sourceId(store, "tool:a");
        long removedB = sourceId(store, "tool:b");

        SourceSyncResult unchanged = engine.synchronizeSource(first).join();
        require(unchanged.unchanged() == 2 && unchanged.created() == 0 && unchanged.updated() == 0,
                "unchanged source snapshot must not duplicate or re-embed entries");
        require(sourceId(store, "tool:a") == stableA, "unchanged source key must keep its stable ID");

        SourceSyncResult revised = engine.synchronizeSource(new KnowledgeSourceSnapshot("proof.tools", "r2", List.of(
                sourceEntry("tool:a", "Tool A revised")
        ))).join();
        require(revised.updated() == 1 && revised.deleted() == 1,
                "changed source snapshot must replace changed content and remove absent entries");
        require(sourceId(store, "tool:a") == stableA, "revised source key must keep its stable ID");
        require(store.activeEntriesForSource("proof.tools").size() == 1,
                "source reconciliation must remove metadata rows missing from the new snapshot");
        require(vectors.health().entryCount() == 1L && removedB != sourceIdOrZero(store, "tool:b"),
                "source reconciliation must remove the matching vector ID");
    }

    private static KnowledgeEntry sourceEntry(String sourceKey, String text) {
        return new KnowledgeEntry(1L, KnowledgeType.TOOL, "proof", text, "proof.tools", "", 1L,
                0.5D, 1.0D, KnowledgeTrust.HISTORICAL_CONTEXT,
                Map.of("sourceId", "proof.tools", "sourceKey", sourceKey, "sourceRevision", "r1"));
    }

    private static void proveBuiltInKnowledgeSources(KoilRetrievalEngine engine, KnowledgeMetadataStore store) {
        AutomationKtlSkillRegistry.SkillDescriptor skill = new AutomationKtlSkillRegistry.SkillDescriptor(
                "proof/inspect", "inspect", List.of("target"), List.of("target"), List.of(), List.of("proof"),
                List.of("block"), List.of(), 1, "Inspect a proof target.", "public", List.of(), List.of(), 20, "fail", "");
        new BuiltInKnowledgeSourceAdapter(engine).synchronizeStaticSources(List.of(skill)).join();
        require(store.activeEntriesForSource("koil.tools").stream()
                        .anyMatch(entry -> "koil.documentation".equals(entry.metadata().get("toolId"))),
                "registered Koil tool definitions must enter the shared knowledge authority");
        require(store.activeEntriesForSource("koil.tools").stream()
                        .noneMatch(entry -> entry.metadata().getOrDefault("toolId", "").startsWith("minecraft.command")),
                "command schemas must remain outside conversational retrieval knowledge");
        require(store.activeEntriesForSource("koil.documentation").stream()
                        .anyMatch(entry -> "tool-use".equals(entry.metadata().get("documentId"))),
                "allowlisted bundled documentation sections must enter the shared knowledge authority");
        require(store.activeEntriesForSource("koil.ktl").stream()
                        .anyMatch(entry -> "proof/inspect".equals(entry.metadata().get("ktlId"))),
                "every compiled model-callable KTL descriptor must enter the shared knowledge authority");
        RetrievalResult result = engine.retrieve(new KnowledgeQuery("koil.documentation",
                new KnowledgeFilter(java.util.Set.of(KnowledgeType.TOOL), java.util.Set.of(), java.util.Set.of(), Map.of(), 0L),
                8, 256, "tool-source-proof", KnowledgeTrust.HISTORICAL_CONTEXT)).join();
        require(!result.selected().isEmpty()
                        && "koil.documentation".equals(result.selected().get(0).entry().metadata().get("toolId")),
                "an exact registered tool ID must beat unrelated source knowledge");
    }

    private static void proveCompletedDeepThoughtOnly(KoilRetrievalEngine engine, KnowledgeMetadataStore store) {
        DeepThoughtKnowledgeAdapter adapter = new DeepThoughtKnowledgeAdapter(engine);
        DeepThoughtSession active = new DeepThoughtSession("request-active", "conversation", "How do containers open?");
        DeepThoughtSession completed = new DeepThoughtSession("request-completed", "conversation", "How do containers open?");
        completed.lifecycle = DeepThoughtSession.Lifecycle.COMPLETED;
        completed.finalConclusion = "Verify the current container screen before continuing.";
        completed.hypotheses.add(new DeepThoughtSession.Hypothesis("h1", "hidden hypothesis", "open", List.of(), List.of(), ""));

        require(adapter.recordCompleted(active).join() == 0L,
                "active Deep Thought investigations must remain session-local");
        require(adapter.recordCompleted(completed).join() > 0L,
                "a nonblank completed Deep Thought conclusion must be stored");
        require(adapter.recordCompleted(completed).join() > 0L,
                "re-recording a completed conclusion must retain its stable source entry");
        List<KnowledgeEntry> entries = store.activeEntriesForSource("deep-thought:" + completed.deepThoughtSessionId);
        require(entries.size() == 1 && entries.get(0).type() == KnowledgeType.USER_KNOWLEDGE,
                "only one typed Deep Thought conclusion must be stored");
        require(entries.get(0).text().contains("Verify the current container screen")
                        && !entries.get(0).text().contains("hidden hypothesis"),
                "Deep Thought retrieval must keep only objective and final conclusion, never intermediate reasoning");
    }

    private static void proveConversationSourceIdempotence(KoilRetrievalEngine engine, KnowledgeMetadataStore store) {
        require(engine.rememberConversation("Open the chest", "Verify the screen is open.", "general").join() > 0L,
                "a visible final exchange must enter the knowledge authority");
        require(engine.rememberConversation("Open the chest", "Verify the screen is open.", "general").join() > 0L,
                "re-importing an unchanged final exchange must retain its source entry");
        List<KnowledgeEntry> entries = store.activeEntries().stream()
                .filter(entry -> entry.type() == KnowledgeType.CONVERSATION)
                .filter(entry -> "final_exchange".equals(entry.metadata().get("kind")))
                .toList();
        require(entries.size() == 1 && entries.get(0).metadata().get("sourceId").startsWith("model.conversation:"),
                "visible final exchanges must be keyed source snapshots rather than duplicate memory records");
    }

    private static void proveStaticMinecraftBoundary(KoilRetrievalEngine engine, KnowledgeMetadataStore store) {
        MinecraftKnowledgeSourceAdapter adapter = new MinecraftKnowledgeSourceAdapter(engine);
        MinecraftKnowledgeService.StaticKnowledgeSnapshot snapshot = new MinecraftKnowledgeService.StaticKnowledgeSnapshot("proof-minecraft-r1", List.of(
                new MinecraftKnowledgeService.StaticKnowledgeFact("registry", "minecraft:diamond_shovel", "Item ID: minecraft:diamond_shovel"),
                new MinecraftKnowledgeService.StaticKnowledgeFact("recipe", "minecraft:diamond_shovel", "Recipe output: minecraft:diamond_shovel"),
                new MinecraftKnowledgeService.StaticKnowledgeFact("player", "current-player", "Player inventory: diamond x64")
        ));
        adapter.synchronize(snapshot).join();
        List<KnowledgeEntry> entries = store.activeEntriesForSource("minecraft.static");
        require(entries.size() == 2 && entries.stream().allMatch(entry -> entry.type() == KnowledgeType.MINECRAFT_KNOWLEDGE),
                "only approved static Minecraft facts may enter durable retrieval");
        require(entries.stream().noneMatch(entry -> entry.text().contains("Player inventory")),
                "live player state must never become historical Minecraft knowledge");
        RetrievalResult result = engine.retrieve(new KnowledgeQuery("minecraft:diamond_shovel",
                new KnowledgeFilter(java.util.Set.of(KnowledgeType.MINECRAFT_KNOWLEDGE), java.util.Set.of(), java.util.Set.of(), Map.of(), 0L),
                8, 256, "minecraft-source-proof", KnowledgeTrust.HISTORICAL_CONTEXT)).join();
        require(!result.selected().isEmpty() && result.selected().get(0).entry().text().contains("minecraft:diamond_shovel"),
                "exact Minecraft identifiers must remain retrievable from the static source");
    }

    private static long sourceId(KnowledgeMetadataStore store, String sourceKey) {
        long id = sourceIdOrZero(store, sourceKey);
        if (id == 0L) throw new AssertionError("missing source key " + sourceKey);
        return id;
    }

    private static long sourceIdOrZero(KnowledgeMetadataStore store, String sourceKey) {
        return store.activeEntriesForSource("proof.tools").stream()
                .filter(entry -> sourceKey.equals(entry.metadata().get("sourceKey")))
                .mapToLong(KnowledgeEntry::id)
                .findFirst().orElse(0L);
    }

    private static final class ProofEmbeddings implements EmbeddingProvider {
        private final EmbeddingIdentity identity;

        private ProofEmbeddings(EmbeddingIdentity identity) {
            this.identity = identity;
        }

        @Override
        public EmbeddingIdentity identity() {
            return this.identity;
        }

        @Override
        public java.util.concurrent.CompletableFuture<List<float[]>> embed(List<String> texts) {
            return java.util.concurrent.CompletableFuture.completedFuture(texts.stream().map(text -> {
                float[] vector = new float[8];
                vector[0] = 1.0F;
                return vector;
            }).toList());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
