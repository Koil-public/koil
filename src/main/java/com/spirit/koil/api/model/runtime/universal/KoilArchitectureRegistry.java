package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactInspection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Formal architecture registry. New architectures extend this registry instead of adding another
 * Koil provider or teaching the model service a new brand-specific branch.
 */
public final class KoilArchitectureRegistry {
    private static final KoilArchitectureRegistry DEFAULT = createDefault();

    private final CopyOnWriteArrayList<KoilArchitectureModule> modules = new CopyOnWriteArrayList<>();

    public static KoilArchitectureRegistry defaultRegistry() {
        return DEFAULT;
    }

    public void register(KoilArchitectureModule module) {
        if (module == null) throw new IllegalArgumentException("architecture module is required");
        if (module.id() == null || module.id().isBlank()) throw new IllegalArgumentException("architecture module id is required");
        modules.removeIf(existing -> existing.id().equals(module.id()));
        modules.add(module);
        modules.sort(Comparator.comparingInt(KoilArchitectureModule::priority).reversed()
                .thenComparing(KoilArchitectureModule::id));
    }

    public List<KoilArchitectureModule> modules() {
        return List.copyOf(modules);
    }

    public KoilArchitectureDescriptor identify(ModelArtifactInspection inspection) {
        if (inspection == null || !inspection.present()) {
            return unknown("model artifact metadata is unavailable");
        }
        for (KoilArchitectureModule module : modules) {
            if (module.matches(inspection)) return module.describe(inspection);
        }
        return unknown("no registered architecture module matched '" + inspection.architectureId() + "'");
    }

    private static KoilArchitectureRegistry createDefault() {
        KoilArchitectureRegistry registry = new KoilArchitectureRegistry();
        registry.register(signature("state-space", 500,
                Set.of("mamba", "mamba2", "jamba"), KoilArchitectureCategory.STATE_SPACE,
                Set.of(KoilOperatorKind.EMBEDDING, KoilOperatorKind.GEMM, KoilOperatorKind.RMS_NORM,
                        KoilOperatorKind.STATE_SPACE_SCAN, KoilOperatorKind.SHORT_CONVOLUTION,
                        KoilOperatorKind.GATING, KoilOperatorKind.OUTPUT_PROJECTION),
                Set.of(KoilModelStateKind.SSM, KoilModelStateKind.CONVOLUTION)));
        registry.register(signature("recurrent", 480,
                Set.of("rwkv", "rwkv6", "rwkv7"), KoilArchitectureCategory.RECURRENT,
                Set.of(KoilOperatorKind.EMBEDDING, KoilOperatorKind.GEMM, KoilOperatorKind.LAYER_NORM,
                        KoilOperatorKind.GATING, KoilOperatorKind.OUTPUT_PROJECTION),
                Set.of(KoilModelStateKind.RECURRENT)));
        registry.register(signature("hybrid-convolution", 460,
                Set.of("lfm2", "lfm2moe", "lfm"), KoilArchitectureCategory.CONVOLUTION_HYBRID,
                Set.of(KoilOperatorKind.EMBEDDING, KoilOperatorKind.GEMM, KoilOperatorKind.RMS_NORM,
                        KoilOperatorKind.ATTENTION, KoilOperatorKind.SHORT_CONVOLUTION,
                        KoilOperatorKind.GATING, KoilOperatorKind.OUTPUT_PROJECTION),
                Set.of(KoilModelStateKind.ATTENTION_KV, KoilModelStateKind.CONVOLUTION)));
        registry.register(new MetadataHybridModule());
        registry.register(new EncoderDecoderModule());
        registry.register(new TransformerModule());
        return registry;
    }

    private static KoilArchitectureModule signature(
            String id,
            int priority,
            Set<String> architectureIds,
            KoilArchitectureCategory category,
            Set<KoilOperatorKind> operators,
            Set<KoilModelStateKind> states
    ) {
        Set<String> normalized = architectureIds.stream().map(KoilArchitectureRegistry::normalize).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new KoilArchitectureModule() {
            @Override public String id() { return id; }
            @Override public int priority() { return priority; }
            @Override public boolean matches(ModelArtifactInspection inspection) {
                return normalized.contains(normalizeArchitecture(inspection.architectureId()));
            }
            @Override public KoilArchitectureDescriptor describe(ModelArtifactInspection inspection) {
                boolean moe = inspection.mixtureOfExperts();
                Set<KoilOperatorKind> resolved = new LinkedHashSet<>(operators);
                Set<KoilModelStateKind> resolvedStates = new LinkedHashSet<>(states);
                if (moe) {
                    resolved.add(KoilOperatorKind.MOE_ROUTING);
                    resolved.add(KoilOperatorKind.EXPERT_EXECUTION);
                    resolvedStates.add(KoilModelStateKind.EXPERT_ROUTING);
                }
                return new KoilArchitectureDescriptor(inspection.architectureId(), category, resolved,
                        resolvedStates, moe, "matched architecture registry module '" + id + "'");
            }
        };
    }

    private static final class MetadataHybridModule implements KoilArchitectureModule {
        @Override public String id() { return "metadata-hybrid"; }
        @Override public int priority() { return 300; }
        @Override public boolean matches(ModelArtifactInspection inspection) {
            return inspection.metadata().keySet().stream().map(KoilArchitectureRegistry::normalize)
                    .anyMatch(key -> key.contains("ssm") || key.contains("recurrent") || key.contains("conv"));
        }
        @Override public KoilArchitectureDescriptor describe(ModelArtifactInspection inspection) {
            Set<KoilOperatorKind> operators = baseTransformerOperators(inspection);
            Set<KoilModelStateKind> states = new LinkedHashSet<>();
            boolean ssm = hasMetadata(inspection, "ssm");
            boolean recurrent = hasMetadata(inspection, "recurrent");
            boolean convolution = hasMetadata(inspection, "conv");
            if (hasMetadata(inspection, "attention")) states.add(KoilModelStateKind.ATTENTION_KV);
            if (ssm) { operators.add(KoilOperatorKind.STATE_SPACE_SCAN); states.add(KoilModelStateKind.SSM); }
            if (recurrent) states.add(KoilModelStateKind.RECURRENT);
            if (convolution) { operators.add(KoilOperatorKind.SHORT_CONVOLUTION); states.add(KoilModelStateKind.CONVOLUTION); }
            if (inspection.mixtureOfExperts()) {
                operators.add(KoilOperatorKind.MOE_ROUTING);
                operators.add(KoilOperatorKind.EXPERT_EXECUTION);
                states.add(KoilModelStateKind.EXPERT_ROUTING);
            }
            return new KoilArchitectureDescriptor(inspection.architectureId(), KoilArchitectureCategory.HYBRID_ATTENTION,
                    operators, states, inspection.mixtureOfExperts(), "hybrid state requirements inferred from artifact metadata");
        }
    }


    private static final class EncoderDecoderModule implements KoilArchitectureModule {
        private static final Set<String> IDS = Set.of("t5", "mt5", "bart", "mbart", "pegasus", "marian", "ul2");
        @Override public String id() { return "encoder-decoder-transformer"; }
        @Override public int priority() { return 220; }
        @Override public boolean matches(ModelArtifactInspection inspection) {
            String architecture = normalizeArchitecture(inspection.architectureId());
            if (IDS.contains(architecture)) return true;
            boolean encoder = hasMetadata(inspection, "encoder");
            boolean decoder = hasMetadata(inspection, "decoder");
            return encoder && decoder;
        }
        @Override public KoilArchitectureDescriptor describe(ModelArtifactInspection inspection) {
            Set<KoilOperatorKind> operators = baseTransformerOperators(inspection);
            Set<KoilModelStateKind> states = new LinkedHashSet<>();
            states.add(KoilModelStateKind.ENCODER);
            states.add(KoilModelStateKind.ATTENTION_KV);
            states.add(KoilModelStateKind.CROSS_ATTENTION);
            if (inspection.mixtureOfExperts()) {
                operators.add(KoilOperatorKind.MOE_ROUTING);
                operators.add(KoilOperatorKind.EXPERT_EXECUTION);
                states.add(KoilModelStateKind.EXPERT_ROUTING);
            }
            return new KoilArchitectureDescriptor(inspection.architectureId(), KoilArchitectureCategory.ENCODER_DECODER_TRANSFORMER,
                    operators, states, inspection.mixtureOfExperts(), "encoder/decoder topology inferred from architecture id or metadata");
        }
    }

    private static final class TransformerModule implements KoilArchitectureModule {
        private static final Set<String> KNOWN_TRANSFORMER_IDS = Set.of(
                "llama", "qwen", "qwen2", "qwen2moe", "qwen3", "qwen3moe", "qwen3next",
                "mistral", "mixtral", "gemma", "gemma2", "gemma3", "phi2", "phi3", "phi4",
                "deepseek", "deepseek2", "deepseek3", "gpt2", "gptneox", "gptoss", "granite", "smollm"
        );
        @Override public String id() { return "decoder-transformer"; }
        @Override public int priority() { return 100; }
        @Override public boolean matches(ModelArtifactInspection inspection) {
            String architecture = normalizeArchitecture(inspection.architectureId());
            if (KNOWN_TRANSFORMER_IDS.contains(architecture)) return true;
            return hasMetadata(inspection, "attention") && !hasMetadata(inspection, "ssm") && !hasMetadata(inspection, "recurrent");
        }
        @Override public KoilArchitectureDescriptor describe(ModelArtifactInspection inspection) {
            Set<KoilOperatorKind> operators = baseTransformerOperators(inspection);
            Set<KoilModelStateKind> states = new LinkedHashSet<>();
            states.add(KoilModelStateKind.ATTENTION_KV);
            boolean moe = inspection.mixtureOfExperts();
            if (moe) {
                operators.add(KoilOperatorKind.MOE_ROUTING);
                operators.add(KoilOperatorKind.EXPERT_EXECUTION);
                states.add(KoilModelStateKind.EXPERT_ROUTING);
            }
            KoilArchitectureCategory category = moe ? KoilArchitectureCategory.MIXTURE_OF_EXPERTS
                    : KoilArchitectureCategory.DECODER_TRANSFORMER;
            return new KoilArchitectureDescriptor(inspection.architectureId(), category, operators, states, moe,
                    "transformer topology inferred from architecture id and retained attention metadata");
        }
    }

    private static Set<KoilOperatorKind> baseTransformerOperators(ModelArtifactInspection inspection) {
        Set<KoilOperatorKind> operators = new LinkedHashSet<>();
        operators.add(KoilOperatorKind.EMBEDDING);
        operators.add(KoilOperatorKind.GEMM);
        operators.add(KoilOperatorKind.RMS_NORM);
        operators.add(KoilOperatorKind.ATTENTION);
        operators.add(KoilOperatorKind.GATING);
        operators.add(KoilOperatorKind.ACTIVATION);
        operators.add(KoilOperatorKind.OUTPUT_PROJECTION);
        if (hasMetadata(inspection, "rope")) operators.add(KoilOperatorKind.ROPE);
        if (hasMetadata(inspection, "head_count_kv")) operators.add(KoilOperatorKind.GROUPED_QUERY_ATTENTION);
        if (hasMetadata(inspection, "sliding_window")) operators.add(KoilOperatorKind.SLIDING_WINDOW_ATTENTION);
        return operators;
    }

    private static boolean hasMetadata(ModelArtifactInspection inspection, String fragment) {
        String needle = normalize(fragment);
        return inspection.metadata().keySet().stream().map(KoilArchitectureRegistry::normalize).anyMatch(key -> key.contains(needle));
    }

    private static KoilArchitectureDescriptor unknown(String evidence) {
        return new KoilArchitectureDescriptor("", KoilArchitectureCategory.UNKNOWN, Set.of(), Set.of(), false, evidence);
    }


    private static String normalizeArchitecture(String value) {
        String normalized = normalize(value);
        String[] suffixes = {"forcausallm", "forconditionalgeneration", "forseq2seqlm", "model"};
        for (String suffix : suffixes) {
            if (normalized.endsWith(suffix) && normalized.length() > suffix.length()) {
                normalized = normalized.substring(0, normalized.length() - suffix.length());
                break;
            }
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }
}
