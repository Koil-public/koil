package com.spirit.koil.api.model.catalog;

import com.spirit.koil.api.model.install.LlamaCppRuntimeCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class LocalModelCatalogProof {
    private LocalModelCatalogProof() {
    }

    public static void main(String[] args) throws Exception {
        require(LocalModelCatalog.entries().size() == 64, "expected sixty-four selectable model choices");
        Set<String> ids = new HashSet<>();
        int qwenChoices = 0;
        int gptOssChoices = 0;
        int graniteChoices = 0;
        int ministralChoices = 0;
        int smolLmChoices = 0;
        int lfmChoices = 0;
        for (LocalModelCatalogEntry entry : LocalModelCatalog.entries()) {
            require(ids.add(entry.id()), "catalog IDs must be unique");
            require("llama_cpp".equals(entry.providerId()), "catalog models must use the llama.cpp provider");
            require(entry.toolCalling(), "catalog choice must support model automation tools");
            require(
                    entry.capabilityTags().containsAll(Set.of(
                            LocalModelCapabilityTag.CHAT,
                            LocalModelCapabilityTag.AUTOMATION_TOOLS
                    )),
                    "catalog choice omitted user-visible capability tags"
            );
            require(!entry.capabilityLabel().isBlank(), "catalog capability label was empty");
            require(entry.complexReasoningEstimatePercent() > 0, "catalog reasoning estimate was missing");
            require(entry.contextTokens() == 32_768, "catalog context metadata drifted");
            long sum = entry.artifacts().stream().mapToLong(ModelArtifact::sizeBytes).sum();
            require(sum == entry.downloadBytes(), "artifact size sum was incorrect");
            entry.artifacts().forEach(artifact -> {
                require("https".equals(artifact.downloadUri().getScheme()), "artifact URL was not HTTPS");
                require(artifact.sha256().length() == 64, "artifact digest was incomplete");
            });
            String id = entry.id();
            require(!id.contains("-vl") && !id.contains("audio") && !id.contains("embedding")
                            && !id.contains("reranker") && !id.endsWith("-base"),
                    "non-text-generation model leaked into the local chat/tool catalog");
            if (id.startsWith("qwen") || id.startsWith("codeqwen") || id.startsWith("qwq")) {
                qwenChoices++;
            }
            if (id.startsWith("gpt-oss")) {
                gptOssChoices++;
            }
            if (id.startsWith("granite")) {
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
        require(qwenChoices == 54, "expected fifty-four verified Qwen-family choices");
        require(gptOssChoices == 2, "expected both verified GPT-OSS choices");
        require(graniteChoices == 3, "expected all three verified IBM Granite choices");
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
        require(ids.contains("ministral-3-3b-instruct-2512-q4"), "Ministral 3 3B compatibility choice was omitted");
        require(ids.contains("ministral-3-8b-instruct-2512-q4"), "Ministral 3 8B compatibility choice was omitted");
        require(ids.contains("ministral-3-14b-instruct-2512-q4"), "Ministral 3 14B compatibility choice was omitted");
        require(ids.contains("smollm3-3b-q4"), "SmolLM3 compatibility choice was omitted");
        require(ids.contains("lfm2-2.6b-q4"), "LFM2 compatibility choice was omitted");
        require(LlamaCppRuntimeCatalog.currentPlatform().isPresent(), "current proof platform has no verified runtime");

        Path root = Files.createTempDirectory("koil-model-selection-proof");
        Path selectionPath = root.resolve("selection.json");
        LocalModelSelection expected = new LocalModelSelection(
                "qwen2.5-0.5b-q4",
                "llama_cpp",
                "qwen2.5-0.5b",
                root.resolve("llama-server"),
                root.resolve("model.gguf"),
                32_768
        );
        LocalModelSelectionStore.save(selectionPath, expected);
        require(expected.equals(LocalModelSelectionStore.load(selectionPath)), "selection did not round-trip");
        LocalModelSelectionStore.clear(selectionPath);
        require(!LocalModelSelectionStore.load(selectionPath).complete(), "cleared selection remained active");
        require(!Files.exists(selectionPath), "selection file was not removed");
        System.out.println("Local model catalog proof passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}