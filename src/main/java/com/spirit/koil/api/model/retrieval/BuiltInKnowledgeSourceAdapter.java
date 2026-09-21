package com.spirit.koil.api.model.retrieval;

import com.spirit.koil.api.automation.ktl.AutomationKtlSkillRegistry;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.knowledge.BundledKoilKnowledgeService;
import com.spirit.koil.api.model.skill.KoilSkillRegistry;
import com.spirit.koil.api.model.tool.LocalModelToolCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Converts existing Koil-owned static registries into revisioned retrieval snapshots. */
public final class BuiltInKnowledgeSourceAdapter {
    private final KoilRetrievalEngine engine;

    public BuiltInKnowledgeSourceAdapter(KoilRetrievalEngine engine) {
        this.engine = java.util.Objects.requireNonNull(engine, "engine");
    }

    public CompletableFuture<List<SourceSyncResult>> synchronizeStaticSources() {
        try {
            List<AutomationKtlSkillRegistry.SkillDescriptor> skills = AutomationKtlSkillRegistry.descriptors();
            return skills.isEmpty() ? synchronizeToolsAndDocumentation() : synchronizeStaticSources(skills);
        } catch (RuntimeException | LinkageError unavailable) {
            return synchronizeToolsAndDocumentation();
        }
    }

    /** Uses a caller-provided compiled descriptor view; useful for the KTL lifecycle and proof controls. */
    public CompletableFuture<List<SourceSyncResult>> synchronizeStaticSources(
            List<AutomationKtlSkillRegistry.SkillDescriptor> skills
    ) {
        try {
            KnowledgeSourceSnapshot documentation = documentationSnapshot();
            return this.engine.synchronizeSource(ktlSnapshot(skills))
                    .thenCompose(ktl -> this.engine.synchronizeSource(toolSnapshot())
                            .thenCompose(tools -> this.engine.synchronizeSource(KoilSkillRegistry.knowledgeSnapshot())
                                    .thenCompose(procedures -> this.engine.synchronizeSource(documentation)
                                            .thenApply(documents -> List.of(ktl, tools, procedures, documents)))));
        } catch (IOException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletableFuture<List<SourceSyncResult>> synchronizeToolsAndDocumentation() {
        try {
            KnowledgeSourceSnapshot documentation = documentationSnapshot();
            return this.engine.synchronizeSource(toolSnapshot())
                    .thenCompose(tools -> this.engine.synchronizeSource(KoilSkillRegistry.knowledgeSnapshot())
                            .thenCompose(procedures -> this.engine.synchronizeSource(documentation)
                                    .thenApply(documents -> List.of(tools, procedures, documents))));
        } catch (IOException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static KnowledgeSourceSnapshot ktlSnapshot(List<AutomationKtlSkillRegistry.SkillDescriptor> skills) {
        List<KnowledgeEntry> entries = new ArrayList<>();
        for (AutomationKtlSkillRegistry.SkillDescriptor skill : skills == null ? List.<AutomationKtlSkillRegistry.SkillDescriptor>of() : skills) {
            Map<String, String> metadata = metadata("koil.ktl", "ktl:" + skill.id());
            metadata.put("ktlId", skill.id());
            metadata.put("semanticOperation", skill.semanticOperation());
            metadata.put("visibility", skill.visibility());
            metadata.put("recoveryTask", skill.recoveryTask());
            entries.add(entry(KnowledgeType.KTL, "automation", "ktl.registry", skill.id(), text(skill), metadata));
        }
        return new KnowledgeSourceSnapshot("koil.ktl", "ktl-descriptors-v1", entries);
    }

    private static KnowledgeSourceSnapshot toolSnapshot() {
        List<KnowledgeEntry> entries = new ArrayList<>();
        for (ModelToolDefinition tool : LocalModelToolCatalog.allRegisteredTools()) {
            if (tool.id().startsWith("minecraft.command")) continue;
            Map<String, String> metadata = metadata("koil.tools", "tool:" + tool.id());
            metadata.put("toolId", tool.id());
            metadata.put("readOnly", Boolean.toString(tool.executionPolicy().allowsSpeculativeRead()));
            metadata.put("confirmationRequired", Boolean.toString(tool.confirmationRequired()));
            entries.add(entry(KnowledgeType.TOOL, "model", "tool.registry", tool.id(), tool.id() + "\n" + tool.description(), metadata));
        }
        return new KnowledgeSourceSnapshot("koil.tools", "tool-catalog-" + LocalModelToolCatalog.version(), entries);
    }

    private static KnowledgeSourceSnapshot documentationSnapshot() throws IOException {
        List<KnowledgeEntry> entries = new ArrayList<>();
        for (BundledKoilKnowledgeService.IndexSection section : BundledKoilKnowledgeService.sectionsForIndexing()) {
            Map<String, String> metadata = metadata("koil.documentation",
                    "document:" + section.documentId() + ":" + section.startLine());
            metadata.put("documentId", section.documentId());
            metadata.put("section", section.section());
            metadata.put("startLine", Integer.toString(section.startLine()));
            metadata.put("endLine", Integer.toString(section.endLine()));
            entries.add(entry(KnowledgeType.KOIL_DOCUMENTATION, "koil", "bundled.documentation", section.documentId(),
                    section.title() + "\n" + section.section() + "\n" + section.text(), metadata));
        }
        return new KnowledgeSourceSnapshot("koil.documentation", hash(entries), entries);
    }

    private static KnowledgeEntry entry(KnowledgeType type, String scope, String source, String sessionId,
                                        String text, Map<String, String> metadata) {
        return new KnowledgeEntry(1L, type, scope, text, source, sessionId, 0L, 0.7D, 0.9D,
                KnowledgeTrust.HISTORICAL_CONTEXT, metadata);
    }

    private static String text(AutomationKtlSkillRegistry.SkillDescriptor skill) {
        return "KTL capability: " + skill.id()
                + "\nSemantic operation: " + skill.semanticOperation()
                + "\nDescription: " + skill.description()
                + "\nTags: " + String.join(", ", skill.tags())
                + "\nRequired parameters: " + String.join(", ", skill.requiredParameters())
                + "\nOptional parameters: " + String.join(", ", skill.optionalParameters())
                + "\nRecovery task: " + skill.recoveryTask();
    }

    private static Map<String, String> metadata(String sourceId, String sourceKey) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("sourceId", sourceId);
        values.put("sourceKey", sourceKey);
        return values;
    }

    private static String hash(List<KnowledgeEntry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (KnowledgeEntry entry : entries) {
                digest.update(entry.metadata().get("sourceKey").getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.text().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
