package com.spirit.koil.api.model.testing;

import com.spirit.koil.api.model.ModelReasoningMarkupParser;

/** Deterministic proof for inline reasoning stream partitioning. */
public final class ModelReasoningMarkupParserProof {
    private ModelReasoningMarkupParserProof() {
    }

    public static void main(String[] args) {
        splitTagsAcrossChunks();
        preservesVisibleTextAroundThought();
        supportsMistralThinkTokens();
        supportsNamespacedThinkTokens();
        preservesTypedExposedMarkup();
        keepsUnclosedThoughtAsReasoning();
        suppressesOrphanClosingThinkControlMarkup();
        leavesOrdinaryProseVisible();
        System.out.println("Model reasoning markup parser proof passed.");
    }

    private static void splitTagsAcrossChunks() {
        StringBuilder visible = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ModelReasoningMarkupParser parser = new ModelReasoningMarkupParser();
        parser.accept("<thi", visible::append, reasoning::append);
        parser.accept("nk>checking facts</th", visible::append, reasoning::append);
        parser.accept("ink>Final answer", visible::append, reasoning::append);
        parser.finish(visible::append, reasoning::append);
        require("checking facts".contentEquals(reasoning), "split reasoning tags were not reconstructed");
        require("Final answer".contentEquals(visible), "visible answer after </think> was not preserved");
    }

    private static void preservesVisibleTextAroundThought() {
        ModelReasoningMarkupParser.Partition result = ModelReasoningMarkupParser.partition(
                "prefix <think>private work</think> suffix"
        );
        require("prefix  suffix".equals(result.visibleText()), "text outside think markup was changed");
        require("private work".equals(result.reasoningText()), "think body was not isolated");
        require(result.sawClosedThinkBlock(), "closed think block was not observed");
        require(!result.reasoningOnly(), "mixed reasoning and answer was classified as reasoning-only");
    }

    private static void supportsMistralThinkTokens() {
        StringBuilder visible = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ModelReasoningMarkupParser parser = new ModelReasoningMarkupParser();
        parser.accept("[TH", visible::append, reasoning::append);
        parser.accept("INK]scratch work[/TH", visible::append, reasoning::append);
        parser.accept("INK]Answer", visible::append, reasoning::append);
        parser.finish(visible::append, reasoning::append);
        require("scratch work".contentEquals(reasoning), "Mistral [THINK] body was not isolated");
        require("Answer".contentEquals(visible), "Mistral visible answer was not preserved");
        require("mistral_think".equals(parser.activeMarkupStyle()), "Mistral markup source was not identified");
    }


    private static void supportsNamespacedThinkTokens() {
        StringBuilder visible = new StringBuilder();
        java.util.List<ModelReasoningMarkupParser.ExposedChunk> chunks = new java.util.ArrayList<>();
        ModelReasoningMarkupParser parser = new ModelReasoningMarkupParser();
        parser.acceptTyped("<seed:think>verify state</seed:think>Answer", visible::append, chunks::add);
        parser.finishTyped(visible::append, chunks::add);
        require("Answer".contentEquals(visible), "namespaced think markup leaked into visible output");
        require(chunks.size() == 1, "namespaced think markup was not captured");
        require(chunks.get(0).kind() == com.spirit.koil.api.model.ModelExposedData.Kind.THOUGHT,
                "namespaced think markup was not classified as thought");
        require("xml_seed_think".equals(chunks.get(0).nativeChannel()),
                "namespaced native channel was not preserved");
    }

    private static void preservesTypedExposedMarkup() {
        StringBuilder visible = new StringBuilder();
        java.util.List<ModelReasoningMarkupParser.ExposedChunk> chunks = new java.util.ArrayList<>();
        ModelReasoningMarkupParser parser = new ModelReasoningMarkupParser();
        parser.acceptTyped("<analysis>check premise</analysis><reflection>revise premise</reflection>Answer",
                visible::append, chunks::add);
        parser.finishTyped(visible::append, chunks::add);
        require("Answer".contentEquals(visible), "typed model markup leaked into visible answer");
        require(chunks.size() == 2, "typed model markup did not produce two exposed channels");
        require(chunks.get(0).kind() == com.spirit.koil.api.model.ModelExposedData.Kind.ANALYSIS
                        && "xml_analysis".equals(chunks.get(0).nativeChannel()),
                "analysis markup was not typed correctly");
        require(chunks.get(1).kind() == com.spirit.koil.api.model.ModelExposedData.Kind.REFLECTION,
                "reflection markup was not typed correctly");
    }

    private static void keepsUnclosedThoughtAsReasoning() {
        ModelReasoningMarkupParser.Partition result = ModelReasoningMarkupParser.partition("<think>still working");
        require(result.reasoningOnly(), "unclosed reasoning was not kept out of visible output");
        require(result.endedInsideThink(), "unclosed think state was not reported");
    }

    private static void suppressesOrphanClosingThinkControlMarkup() {
        ModelReasoningMarkupParser.Partition result = ModelReasoningMarkupParser.partition(
                "Visible answer</think>"
        );
        require("Visible answer".equals(result.visibleText()), "orphan closing think tag leaked into visible output");
        require(result.reasoningText().isBlank(), "orphan closing think tag invented reasoning content");
        require(result.sawThinkMarkup(), "orphan closing think tag was not recognized as control markup");
    }

    private static void leavesOrdinaryProseVisible() {
        ModelReasoningMarkupParser.Partition result = ModelReasoningMarkupParser.partition(
                "Okay, let me reason carefully and then answer directly."
        );
        require(result.reasoningText().isBlank(), "ordinary prose was guessed to be reasoning");
        require(!result.visibleText().isBlank(), "ordinary prose disappeared");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
