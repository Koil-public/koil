package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactInspection;
import com.spirit.koil.api.model.catalog.ModelTensorDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default structural lowering for currently recognized text architectures.
 * It derives topology from architecture semantics and binds only tensors that actually exist.
 */
final class KoilNormalizedGraphBuilder implements KoilArchitectureGraphBuilder {
    private static final Pattern HF_LAYER = Pattern.compile("(?:^|\\.)layers\\.(\\d+)(?:\\.|$)");
    private static final Pattern GGUF_LAYER = Pattern.compile("(?:^|\\.)blk\\.(\\d+)(?:\\.|$)");
    private static final Pattern ENCODER_LAYER = Pattern.compile("(?:^|\\.)encoder(?:\\.block|\\.layers?)?\\.(\\d+)(?:\\.|$)");
    private static final Pattern DECODER_LAYER = Pattern.compile("(?:^|\\.)decoder(?:\\.block|\\.layers?)?\\.(\\d+)(?:\\.|$)");

    @Override public String id() { return "normalized-structural"; }
    @Override public int priority() { return 100; }
    @Override public boolean supports(KoilArchitectureDescriptor architecture, ModelArtifactInspection inspection) {
        return architecture != null && architecture.recognized() && inspection != null && inspection.present();
    }

    @Override
    public KoilModelGraph build(KoilArchitectureDescriptor architecture, ModelArtifactInspection inspection) {
        List<ModelTensorDescriptor> tensors = inspection.tensors();
        List<KoilGraphDiagnostic> diagnostics = new ArrayList<>();
        List<KoilGraphNode> nodes = new ArrayList<>();
        Set<KoilOperatorKind> requiredOperators = new LinkedHashSet<>(architecture.requiredOperators());
        Set<KoilModelStateKind> state = new LinkedHashSet<>(architecture.persistentState());

        if (tensors.isEmpty()) {
            diagnostics.add(error("tensor_descriptors_missing", "tensor headers were summarized but individual tensor descriptors are unavailable"));
            return result(architecture, nodes, requiredOperators, state, diagnostics, false, "no tensor descriptors available");
        }

        List<String> embedding = matching(tensors, "token_embd.weight", "model.embed_tokens.weight", "shared.weight", "transformer.wte.weight");
        if (embedding.isEmpty()) diagnostics.add(error("embedding_tensor_missing", "no recognized token embedding tensor was found"));
        nodes.add(new KoilGraphNode("input.embedding", KoilGraphStage.INPUT, -1,
                Set.of(KoilOperatorKind.EMBEDDING), Set.of(), embedding, Map.of()));

        Map<Integer, List<String>> ordinaryLayers = groupLayers(tensors, HF_LAYER, GGUF_LAYER);
        Map<Integer, List<String>> encoderLayers = groupLayers(tensors, ENCODER_LAYER);
        Map<Integer, List<String>> decoderLayers = groupLayers(tensors, DECODER_LAYER);

        if (architecture.category() == KoilArchitectureCategory.ENCODER_DECODER_TRANSFORMER) {
            addLayerNodes(nodes, encoderLayers, architecture, KoilGraphStage.ENCODER);
            addLayerNodes(nodes, decoderLayers, architecture, KoilGraphStage.DECODER);
            if (encoderLayers.isEmpty()) diagnostics.add(error("encoder_layers_missing", "encoder-decoder architecture has no identifiable encoder tensors"));
            if (decoderLayers.isEmpty()) diagnostics.add(error("decoder_layers_missing", "encoder-decoder architecture has no identifiable decoder tensors"));
        } else {
            addLayerNodes(nodes, ordinaryLayers, architecture, KoilGraphStage.BLOCK);
            if (ordinaryLayers.isEmpty()) diagnostics.add(error("model_layers_missing", "no identifiable model block tensors were found"));
        }

        List<String> outputNorm = matching(tensors, "output_norm.weight", "model.norm.weight", "transformer.ln_f.weight", "final_layernorm.weight");
        if (!outputNorm.isEmpty()) {
            nodes.add(new KoilGraphNode("output.norm", KoilGraphStage.OUTPUT, -1,
                    normalizationOperators(architecture), Set.of(), outputNorm, Map.of()));
        }
        List<String> output = matching(tensors, "output.weight", "lm_head.weight", "embed_out.weight");
        boolean tiedOutput = output.isEmpty() && !embedding.isEmpty() && tiedEmbeddings(inspection);
        if (output.isEmpty() && !tiedOutput) {
            diagnostics.add(new KoilGraphDiagnostic(KoilGraphDiagnostic.Severity.WARNING, "output_tensor_unresolved",
                    "no explicit output projection tensor was found and tied embeddings were not proven"));
        }
        nodes.add(new KoilGraphNode("output.projection", KoilGraphStage.OUTPUT, -1,
                Set.of(KoilOperatorKind.OUTPUT_PROJECTION), Set.of(), tiedOutput ? embedding : output,
                Map.of("tied_embedding", Boolean.toString(tiedOutput))));

        validateEmbeddingShape(embedding, tensors, inspection, diagnostics);
        validateLayerCount(inspection, ordinaryLayers, encoderLayers, decoderLayers, diagnostics);

        boolean structurallyComplete = diagnostics.stream().noneMatch(d -> d.severity() == KoilGraphDiagnostic.Severity.ERROR);
        return result(architecture, nodes, requiredOperators, state, diagnostics, structurallyComplete,
                "normalized graph built from " + tensors.size() + " tensor descriptors");
    }

    private static void addLayerNodes(List<KoilGraphNode> nodes, Map<Integer, List<String>> layers,
                                      KoilArchitectureDescriptor architecture, KoilGraphStage stage) {
        for (Map.Entry<Integer, List<String>> entry : layers.entrySet()) {
            Set<KoilOperatorKind> operators = layerOperators(architecture, entry.getValue());
            Set<KoilModelStateKind> layerState = layerState(architecture, operators);
            nodes.add(new KoilGraphNode(stage.name().toLowerCase(Locale.ROOT) + "." + entry.getKey(), stage,
                    entry.getKey(), operators, layerState, entry.getValue(), Map.of()));
        }
    }

    private static Set<KoilOperatorKind> layerOperators(KoilArchitectureDescriptor architecture, List<String> tensors) {
        Set<KoilOperatorKind> result = new LinkedHashSet<>();
        Set<KoilOperatorKind> declared = architecture.requiredOperators();
        addIfDeclared(result, declared, KoilOperatorKind.RMS_NORM, KoilOperatorKind.LAYER_NORM, KoilOperatorKind.GEMM,
                KoilOperatorKind.GEMV, KoilOperatorKind.ROPE, KoilOperatorKind.ATTENTION,
                KoilOperatorKind.GROUPED_QUERY_ATTENTION, KoilOperatorKind.MULTI_QUERY_ATTENTION,
                KoilOperatorKind.MULTI_HEAD_ATTENTION, KoilOperatorKind.MULTI_HEAD_LATENT_ATTENTION,
                KoilOperatorKind.SLIDING_WINDOW_ATTENTION, KoilOperatorKind.LINEAR_ATTENTION,
                KoilOperatorKind.SHORT_CONVOLUTION, KoilOperatorKind.STATE_SPACE_SCAN, KoilOperatorKind.DELTA_NET,
                KoilOperatorKind.GATING, KoilOperatorKind.ACTIVATION);
        String joined = String.join(" ", tensors).toLowerCase(Locale.ROOT);
        if (declared.contains(KoilOperatorKind.MOE_ROUTING) || joined.contains("expert") || joined.contains("moe")) {
            result.add(KoilOperatorKind.MOE_ROUTING);
            result.add(KoilOperatorKind.EXPERT_EXECUTION);
        }
        return Set.copyOf(result);
    }

    private static Set<KoilModelStateKind> layerState(KoilArchitectureDescriptor architecture, Set<KoilOperatorKind> operators) {
        Set<KoilModelStateKind> result = new LinkedHashSet<>();
        for (KoilModelStateKind kind : architecture.persistentState()) {
            if (kind == KoilModelStateKind.ATTENTION_KV && operators.stream().noneMatch(KoilNormalizedGraphBuilder::isAttention)) continue;
            if (kind == KoilModelStateKind.CONVOLUTION && !operators.contains(KoilOperatorKind.SHORT_CONVOLUTION)) continue;
            if (kind == KoilModelStateKind.SSM && !operators.contains(KoilOperatorKind.STATE_SPACE_SCAN)) continue;
            if (kind == KoilModelStateKind.EXPERT_ROUTING && !operators.contains(KoilOperatorKind.MOE_ROUTING)) continue;
            result.add(kind);
        }
        return Set.copyOf(result);
    }

    private static boolean isAttention(KoilOperatorKind kind) {
        return kind == KoilOperatorKind.ATTENTION || kind == KoilOperatorKind.GROUPED_QUERY_ATTENTION
                || kind == KoilOperatorKind.MULTI_QUERY_ATTENTION || kind == KoilOperatorKind.MULTI_HEAD_ATTENTION
                || kind == KoilOperatorKind.MULTI_HEAD_LATENT_ATTENTION || kind == KoilOperatorKind.SLIDING_WINDOW_ATTENTION
                || kind == KoilOperatorKind.LINEAR_ATTENTION;
    }

    private static void addIfDeclared(Set<KoilOperatorKind> target, Set<KoilOperatorKind> declared, KoilOperatorKind... kinds) {
        for (KoilOperatorKind kind : kinds) if (declared.contains(kind)) target.add(kind);
    }

    private static Set<KoilOperatorKind> normalizationOperators(KoilArchitectureDescriptor architecture) {
        if (architecture.requiredOperators().contains(KoilOperatorKind.RMS_NORM)) return Set.of(KoilOperatorKind.RMS_NORM);
        if (architecture.requiredOperators().contains(KoilOperatorKind.LAYER_NORM)) return Set.of(KoilOperatorKind.LAYER_NORM);
        return Set.of();
    }

    @SafeVarargs
    private static Map<Integer, List<String>> groupLayers(List<ModelTensorDescriptor> tensors, Pattern... patterns) {
        Map<Integer, List<String>> result = new TreeMap<>();
        for (ModelTensorDescriptor tensor : tensors) {
            Integer index = null;
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(tensor.name());
                if (matcher.find()) { index = Integer.parseInt(matcher.group(1)); break; }
            }
            if (index != null) result.computeIfAbsent(index, ignored -> new ArrayList<>()).add(tensor.name());
        }
        result.replaceAll((key, value) -> value.stream().sorted().toList());
        return result;
    }

    private static List<String> matching(List<ModelTensorDescriptor> tensors, String... exactOrSuffix) {
        List<String> result = new ArrayList<>();
        for (ModelTensorDescriptor tensor : tensors) {
            String name = tensor.name();
            for (String candidate : exactOrSuffix) {
                if (name.equals(candidate) || name.endsWith("." + candidate)) { result.add(name); break; }
            }
        }
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    private static void validateEmbeddingShape(List<String> embeddingNames, List<ModelTensorDescriptor> tensors,
                                               ModelArtifactInspection inspection, List<KoilGraphDiagnostic> diagnostics) {
        if (embeddingNames.isEmpty()) return;
        Map<String, ModelTensorDescriptor> byName = new LinkedHashMap<>();
        for (ModelTensorDescriptor tensor : tensors) byName.put(tensor.name(), tensor);
        ModelTensorDescriptor embedding = byName.get(embeddingNames.get(0));
        if (embedding == null || embedding.shape().size() != 2) {
            diagnostics.add(error("embedding_shape_invalid", "token embedding must be rank 2"));
            return;
        }
        long expectedEmbedding = metadataLong(inspection, "embedding_length", "hidden_size", "d_model", "n_embd");
        if (expectedEmbedding > 0 && !embedding.shape().contains(expectedEmbedding)) {
            diagnostics.add(error("embedding_dimension_mismatch", "embedding tensor shape " + embedding.shape()
                    + " does not contain declared embedding dimension " + expectedEmbedding));
        }
    }

    private static void validateLayerCount(ModelArtifactInspection inspection, Map<Integer, List<String>> ordinary,
                                           Map<Integer, List<String>> encoder, Map<Integer, List<String>> decoder,
                                           List<KoilGraphDiagnostic> diagnostics) {
        long declared = metadataLong(inspection, "block_count", "layer_count", "num_hidden_layers", "n_layer", "num_layers");
        if (declared <= 0) return;
        long observed = ordinary.isEmpty() ? Math.max(encoder.size(), decoder.size()) : ordinary.size();
        if (observed > 0 && observed != declared) {
            diagnostics.add(new KoilGraphDiagnostic(KoilGraphDiagnostic.Severity.WARNING, "layer_count_mismatch",
                    "declared layer count=" + declared + " but identifiable tensor layers=" + observed));
        }
    }

    private static boolean tiedEmbeddings(ModelArtifactInspection inspection) {
        return inspection.metadata().entrySet().stream().anyMatch(entry -> {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            return key.contains("tie_word_embeddings") && Boolean.parseBoolean(entry.getValue());
        });
    }

    private static long metadataLong(ModelArtifactInspection inspection, String... fragments) {
        for (Map.Entry<String, String> entry : inspection.metadata().entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            for (String fragment : fragments) {
                if (!key.endsWith(fragment.toLowerCase(Locale.ROOT)) && !key.contains("." + fragment.toLowerCase(Locale.ROOT))) continue;
                try { return Long.parseLong(entry.getValue()); } catch (NumberFormatException ignored) { }
            }
        }
        return 0L;
    }

    private static KoilGraphDiagnostic error(String code, String message) {
        return new KoilGraphDiagnostic(KoilGraphDiagnostic.Severity.ERROR, code, message);
    }

    private static KoilModelGraph result(KoilArchitectureDescriptor architecture, List<KoilGraphNode> nodes,
                                         Set<KoilOperatorKind> operators, Set<KoilModelStateKind> state,
                                         List<KoilGraphDiagnostic> diagnostics, boolean complete, String evidence) {
        return new KoilModelGraph(architecture.architectureId(), architecture.category(), nodes, operators, state,
                diagnostics, complete, evidence);
    }
}
