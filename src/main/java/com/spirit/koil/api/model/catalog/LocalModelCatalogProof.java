package com.spirit.koil.api.model.catalog;

import com.spirit.koil.api.model.install.LlamaCppRuntimeCatalog;
import com.spirit.koil.api.util.file.KoilInstancePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class LocalModelCatalogProof {
    private LocalModelCatalogProof() {
    }

    public static void main(String[] args) throws Exception {
        require(LocalModelCatalog.entries().size() > 100, "expanded canonical roster was not loaded");
        require(LocalModelCatalog.entries().stream().anyMatch(entry -> entry.canonical().architecture()
                        == LocalModelCanonicalMetadata.Architecture.EMBEDDING),
                "catalog must retain specialized embedding metadata");
        require(LocalModelCatalog.generationEntries().stream().noneMatch(entry -> {
                    LocalModelCanonicalMetadata.Architecture architecture = entry.canonical().architecture();
                    return architecture == LocalModelCanonicalMetadata.Architecture.EMBEDDING
                            || architecture == LocalModelCanonicalMetadata.Architecture.RERANKER;
                }),
                "embedding and reranker entries must not appear in generation selection");
        LocalModelCatalogEntry embedding = LocalModelCatalog.find("hf-qwen-qwen3-embedding-4b").orElseThrow();
        require(embedding.runnable(), "pinned embedding GGUF must use the existing installer path");
        require(embedding.canonical().architecture() == LocalModelCanonicalMetadata.Architecture.EMBEDDING,
                "pinned embedding GGUF lost its specialized role");
        require(LocalModelCatalog.generationEntries().stream().noneMatch(entry -> entry.id().equals(embedding.id())),
                "pinned embedding GGUF leaked into generation selection");
        Set<String> ids = new HashSet<>();
        int qwenChoices = 0;
        int gptOssChoices = 0;
        int graniteChoices = 0;
        int ministralChoices = 0;
        int smolLmChoices = 0;
        int lfmChoices = 0;
        int runnableChoices = 0;
        int colibriChoices = 0;
        int dynamicallyResolvableChoices = 0;
        int unavailableChoices = 0;
        for (LocalModelCatalogEntry entry : LocalModelCatalog.entries()) {
            require(ids.add(entry.id()), "catalog IDs must be unique");
            require(!entry.family().isBlank(), "catalog choice omitted canonical family");
            require(!entry.canonical().baseCapability().isBlank(), "catalog choice omitted base capability");
            if (LocalModelCatalog.canResolveForInstall(entry) && !entry.runnable()) {
                dynamicallyResolvableChoices++;
                require("llama_cpp".equals(entry.providerId()), "resolvable text model escaped the shared llama.cpp provider path");
                require(entry.runtimeId().equals("llama.cpp-" + LlamaCppRuntimeCatalog.VERSION),
                        "resolvable text model escaped the shared llama.cpp runtime path");
            }
            if (entry.runnable()) {
                runnableChoices++;
                LocalModelRuntimeResolver.Resolution resolution = LocalModelRuntimeResolver.resolve(entry);
                require(resolution.available(), "runnable model had no runtime resolution");
                ModelRuntimeCompatibility selected = resolution.selected();
                boolean embeddingRole = entry.canonical().architecture()
                        == LocalModelCanonicalMetadata.Architecture.EMBEDDING;
                if ("colibri".equals(selected.providerId())) {
                    colibriChoices++;
                    require(selected.installsRepositorySnapshot(),
                            "managed Colibri model omitted its immutable repository snapshot");
                    require(selected.preference() > entry.runtimeCompatibility().stream()
                                    .filter(value -> !"colibri".equals(value.providerId()))
                                    .mapToInt(ModelRuntimeCompatibility::preference).max().orElse(-1),
                            "exact Colibri runtime was not preferred over generic fallbacks");
                } else {
                    require("llama_cpp".equals(selected.providerId()),
                            "runnable text model resolved to an unknown provider");
                    require(entry.contextTokens() == (embeddingRole ? 40_960 : 32_768),
                            "verified llama.cpp context metadata drifted");
                }
                require(embeddingRole
                                ? !entry.capabilityTags().contains(LocalModelCapabilityTag.CHAT)
                                : entry.capabilityTags().contains(LocalModelCapabilityTag.CHAT),
                        "specialized embedding role leaked into chat capability selection");
                if (embeddingRole) {
                    require(selected.protocolCapabilities().contains("openai_embeddings"),
                            "embedding role omitted the dedicated llama.cpp endpoint protocol");
                }
                if (entry.toolCalling()) {
                    require(entry.capabilityTags().contains(LocalModelCapabilityTag.AUTOMATION_TOOLS),
                            "tool-capable runnable model omitted the Automation tools tag");
                }
                require(embeddingRole || entry.complexReasoningEstimatePercent() > 0,
                        "runnable catalog reasoning estimate was missing");
            } else {
                unavailableChoices++;
                require(entry.artifacts().isEmpty(), "unavailable catalog entry exposed an unverified download");
                require(!entry.canonical().unavailableReason().isBlank(), "unavailable catalog entry omitted unavailable reason");
            }
            long sum = entry.artifacts().stream().mapToLong(ModelArtifact::sizeBytes).sum();
            require(sum == entry.downloadBytes(), "artifact size sum was incorrect");
            entry.artifacts().forEach(artifact -> {
                require("https".equals(artifact.downloadUri().getScheme()), "artifact URL was not HTTPS");
                require(artifact.sha256().length() == 64, "artifact digest was incomplete");
            });
            String id = entry.id();
            if (entry.runnable() && entry.canonical().architecture()
                    != LocalModelCanonicalMetadata.Architecture.EMBEDDING) {
                require(!id.contains("-vl") && !id.contains("audio") && !id.contains("embedding")
                                && !id.contains("reranker") && !id.endsWith("-base"),
                        "non-text-generation model leaked into the runnable local chat/tool catalog");
            }
            if (id.startsWith("qwen") || id.startsWith("codeqwen") || id.startsWith("qwq")) {
                qwenChoices++;
            }
            if (id.startsWith("gpt-oss")) {
                gptOssChoices++;
            }
            if (entry.family().startsWith("Granite")) {
                graniteChoices++;
            }
            if (id.startsWith("ministral")) {
                ministralChoices++;
            }
            if (id.startsWith("smollm")) {
                smolLmChoices++;
            }
            if (id.startsWith("lfm")) {
                lfmChoices++;
            }
        }
        require(runnableChoices >= 72, "verified runnable roster unexpectedly lost pinned models");
        require(colibriChoices >= 6, "exact managed Colibri roster was not activated");
        require(unavailableChoices > 40, "current Hugging Face local-model expansion was incomplete");
        require(qwenChoices == 54, "expected fifty-four verified Qwen-family choices");
        require(gptOssChoices == 2, "expected both verified GPT-OSS choices");
        require(graniteChoices == 6, "expected Granite 4.1 and Granite 4.2 size ladders");
        require(dynamicallyResolvableChoices > 100, "dynamic GGUF resolution did not cover the expanded text roster");
        require(ministralChoices == 3, "expected all three verified Ministral choices");
        require(smolLmChoices == 1, "expected the verified SmolLM3 compatibility choice");
        require(lfmChoices == 1, "expected the verified LFM2 compatibility choice");
        require(ids.contains("qwen1.5-0.5b-q4"), "oldest supported official Qwen GGUF family was omitted");
        require(ids.contains("qwen3-coder-next-q4"), "newest stable supported Qwen coder GGUF was omitted");
        require(ids.contains("qwen3.5-397b-a17b-q4"), "Qwen3.5 flagship GGUF was omitted");
        require(ids.contains("qwen3.6-35b-a3b-q4"), "latest supported Qwen3.6 GGUF was omitted");
        require(ids.contains("gpt-oss-20b-mxfp4"), "GPT-OSS 20B compatibility choice was omitted");
        require(ids.contains("granite-4.1-3b-q4"), "IBM Granite 4.1 3B compatibility choice was omitted");
        require(ids.contains("granite-4.1-8b-q4"), "IBM Granite 4.1 8B compatibility choice was omitted");
        require(ids.contains("granite-4.1-30b-q4"), "IBM Granite 4.1 30B compatibility choice was omitted");
        require(ids.contains("hf-ibm-granite-granite-4-2-3b"), "IBM Granite 4.2 3B verified GGUF was omitted");
        require(ids.contains("hf-ibm-granite-granite-4-2-8b"), "IBM Granite 4.2 8B verified GGUF was omitted");
        require(ids.contains("hf-ibm-granite-granite-4-2-30b"), "IBM Granite 4.2 30B verified GGUF was omitted");
        require(ids.contains("ministral-3-3b-instruct-2512-q4"), "Ministral 3 3B compatibility choice was omitted");
        require(ids.contains("ministral-3-8b-instruct-2512-q4"), "Ministral 3 8B compatibility choice was omitted");
        require(ids.contains("ministral-3-14b-instruct-2512-q4"), "Ministral 3 14B compatibility choice was omitted");
        require(ids.contains("smollm3-3b-q4"), "SmolLM3 compatibility choice was omitted");
        require(ids.contains("lfm2-2.6b-q4"), "LFM2 compatibility choice was omitted");
        require(ids.contains("hf-qwen-qwen3-8-27b"), "Qwen3.8 27B verified GGUF was omitted");
        require(ids.contains("hf-google-gemma-3-1b-it"), "Gemma 3 1B verified GGUF was omitted");
        require(ids.contains("hf-google-gemma-3-4b-it"), "Gemma 3 4B verified GGUF was omitted");
        require(ids.contains("hf-google-gemma-3-12b-it"), "Gemma 3 12B verified GGUF was omitted");
        require(ids.contains("hf-google-gemma-3-27b-it"), "Gemma 3 27B verified GGUF was omitted");
        require(ids.contains("hf-qwen-qwen3-8-2-4t-a95b"), "Qwen3.8 frontier metadata was omitted");
        LocalModelCatalogEntry olmoe = LocalModelCatalog.find("hf-allenai-olmoe-1b-7b-0125-instruct")
                .orElseThrow(() -> new IllegalStateException("OLMoE Steam Deck Colibri fixture was omitted"));
        ModelRuntimeCompatibility olmoeRuntime = LocalModelRuntimeResolver.resolve(olmoe).selected();
        require("colibri".equals(olmoeRuntime.providerId()) && "olmoe".equals(olmoeRuntime.engineId()),
                "OLMoE did not resolve to the Colibri engine");
        require(olmoeRuntime.maximumContextTokens() == 4_096,
                "OLMoE context contract drifted from the pinned Colibri engine");
        require(ids.contains("hf-black-forest-labs-flux-2-klein-4b"), "FLUX.2 typed metadata was omitted");
        require(ids.contains("hf-openai-gpt-oss-20b"), "GPT-OSS protocol metadata was omitted");
        require(ids.contains("hf-brokenshards-ox-alpha"), "experimental ox-alpha metadata was omitted");
        require(LlamaCppRuntimeCatalog.currentPlatform().isPresent(), "current proof platform has no verified runtime");

        Path root = Files.createTempDirectory("koil-model-selection-proof");
        String previousInstanceRoot = System.getProperty("koil.instanceRoot");
        System.setProperty("koil.instanceRoot", root.toString());
        LocalModelSelection expected = new LocalModelSelection(
                "qwen2.5-0.5b-q4",
                "llama_cpp",
                "qwen2.5-0.5b",
                root.resolve("llama-server"),
                root.resolve("model.gguf"),
                32_768
        );
        try {
            Path selectionPath = root.resolve("koil/sys/model/model-selection.json");
            require(LocalModelSelectionStore.defaultPath().equals(selectionPath), "default selection path must use the active instance model root");
            require(KoilInstancePaths.modelRoot().equals(root.resolve("koil/sys/model")), "model root must use the active instance");
            LocalModelSelectionStore.save(expected);
            require(expected.equals(LocalModelSelectionStore.load()), "selection did not round-trip");
            LocalModelSelectionStore.clear();
            require(!LocalModelSelectionStore.load().complete(), "cleared selection remained active");
            require(!Files.exists(selectionPath), "selection file was not removed");
        } finally {
            if (previousInstanceRoot == null) {
                System.clearProperty("koil.instanceRoot");
            } else {
                System.setProperty("koil.instanceRoot", previousInstanceRoot);
            }
        }
        System.out.println("Local model catalog proof passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
