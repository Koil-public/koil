package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactInspection;
import com.spirit.koil.api.model.catalog.ModelTensorDescriptor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves semantic tensor roles from retained artifact descriptors without loading payload bytes.
 * Unknown names remain UNKNOWN rather than being guessed into a known role.
 */
public final class KoilTensorRoleResolver {
    private static final Pattern LAYER = Pattern.compile("(?:^|\\.)(?:layers|blk|block)\\.(\\d+)(?:\\.|$)");

    private KoilTensorRoleResolver() {}

    public static KoilTensorResolution resolve(ModelArtifactInspection inspection, KoilModelGraph graph) {
        if (inspection == null || !inspection.present()) {
            return unavailable("artifact inspection unavailable");
        }
        if (graph == null || !graph.valid()) {
            return unavailable("normalized graph unavailable or invalid");
        }

        List<KoilTensorBinding> bindings = new ArrayList<>();
        List<KoilGraphDiagnostic> diagnostics = new ArrayList<>();
        for (ModelTensorDescriptor tensor : inspection.tensors()) {
            KoilTensorRole role = roleOf(tensor.name());
            boolean validShape = validateRank(role, tensor.shape());
            if (role != KoilTensorRole.UNKNOWN && !validShape) {
                diagnostics.add(new KoilGraphDiagnostic(KoilGraphDiagnostic.Severity.ERROR,
                        "tensor_role_shape_invalid",
                        role.name().toLowerCase(Locale.ROOT) + " tensor '" + tensor.name()
                                + "' has incompatible shape " + tensor.shape()));
            }
            bindings.add(new KoilTensorBinding(role, tensor.name(), layerIndex(tensor.name()), tensor.shape(),
                    tensor.storageType(), validShape,
                    role == KoilTensorRole.UNKNOWN ? "no verified semantic role" : "resolved from tensor naming convention"));
        }

        Set<KoilTensorRole> required = requiredRoles(graph);
        Set<KoilTensorRole> resolved = EnumSet.noneOf(KoilTensorRole.class);
        for (KoilTensorBinding binding : bindings) {
            if (binding.role() != KoilTensorRole.UNKNOWN && binding.validShape()) resolved.add(binding.role());
        }
        Set<KoilTensorRole> unresolved = new LinkedHashSet<>(required);
        unresolved.removeAll(resolved);
        for (KoilTensorRole role : unresolved) {
            diagnostics.add(new KoilGraphDiagnostic(KoilGraphDiagnostic.Severity.ERROR,
                    "required_tensor_role_missing",
                    "normalized graph requires tensor role " + role.name().toLowerCase(Locale.ROOT)
                            + " but no valid tensor binding was resolved"));
        }

        boolean complete = !inspection.tensors().isEmpty();
        return new KoilTensorResolution(bindings, unresolved, diagnostics, complete,
                "resolved=" + bindings.stream().filter(b -> b.role() != KoilTensorRole.UNKNOWN).count()
                        + "/" + bindings.size() + " | required=" + required.size()
                        + " | unresolved_required=" + unresolved.size());
    }

    private static KoilTensorResolution unavailable(String evidence) {
        return new KoilTensorResolution(List.of(), Set.of(), List.of(), false, evidence);
    }

    private static Set<KoilTensorRole> requiredRoles(KoilModelGraph graph) {
        Set<KoilTensorRole> roles = EnumSet.noneOf(KoilTensorRole.class);
        if (graph.requiredOperators().contains(KoilOperatorKind.EMBEDDING)) {
            roles.add(KoilTensorRole.TOKEN_EMBEDDING);
        }
        boolean tiedOutput = graph.nodes().stream()
                .filter(node -> "output.projection".equals(node.id()))
                .anyMatch(node -> Boolean.parseBoolean(node.attributes().getOrDefault("tied_embedding", "false")));
        if (graph.requiredOperators().contains(KoilOperatorKind.OUTPUT_PROJECTION) && !tiedOutput) {
            roles.add(KoilTensorRole.OUTPUT_PROJECTION);
        }
        return Set.copyOf(roles);
    }

    static KoilTensorRole roleOf(String tensorName) {
        String n = tensorName == null ? "" : tensorName.toLowerCase(Locale.ROOT);
        if (matches(n, "token_embd.weight", "embed_tokens.weight", "shared.weight", "wte.weight")) return KoilTensorRole.TOKEN_EMBEDDING;
        if (matches(n, "output_norm.weight", ".norm.weight", "ln_f.weight", "final_layernorm.weight") && !isLayerTensor(n)) return KoilTensorRole.OUTPUT_NORM;
        if (matches(n, "output.weight", "lm_head.weight", "embed_out.weight")) return KoilTensorRole.OUTPUT_PROJECTION;
        if (containsAny(n, "cross_attn.q_proj", "crossattention.q", "cross_attn_q")) return KoilTensorRole.CROSS_ATTENTION_QUERY;
        if (containsAny(n, "cross_attn.k_proj", "crossattention.k", "cross_attn_k")) return KoilTensorRole.CROSS_ATTENTION_KEY;
        if (containsAny(n, "cross_attn.v_proj", "crossattention.v", "cross_attn_v")) return KoilTensorRole.CROSS_ATTENTION_VALUE;
        if (containsAny(n, "cross_attn.o_proj", "crossattention.o", "cross_attn_out")) return KoilTensorRole.CROSS_ATTENTION_OUTPUT;
        if (containsAny(n, "attn_q.weight", "q_proj.weight", "attention.wq.weight", "query.weight")) return KoilTensorRole.ATTENTION_QUERY;
        if (containsAny(n, "attn_k.weight", "k_proj.weight", "attention.wk.weight", "key.weight")) return KoilTensorRole.ATTENTION_KEY;
        if (containsAny(n, "attn_v.weight", "v_proj.weight", "attention.wv.weight", "value.weight")) return KoilTensorRole.ATTENTION_VALUE;
        if (containsAny(n, "attn_output.weight", "attn_o.weight", "o_proj.weight", "attention.wo.weight", "dense.weight") && n.contains("att")) return KoilTensorRole.ATTENTION_OUTPUT;
        if (containsAny(n, "attn_norm.weight", "input_layernorm.weight", "attention_norm.weight", "ln_1.weight")) return KoilTensorRole.ATTENTION_NORM;
        if (containsAny(n, "ffn_norm.weight", "post_attention_layernorm.weight", "ln_2.weight")) return KoilTensorRole.FFN_NORM;
        if (containsAny(n, "ffn_gate.weight", "gate_proj.weight", "w1.weight") && !isExpert(n)) return KoilTensorRole.FFN_GATE;
        if (containsAny(n, "ffn_up.weight", "up_proj.weight", "w3.weight") && !isExpert(n)) return KoilTensorRole.FFN_UP;
        if (containsAny(n, "ffn_down.weight", "down_proj.weight", "w2.weight") && !isExpert(n)) return KoilTensorRole.FFN_DOWN;
        if (containsAny(n, "router.weight", "gate_inp.weight", "moe_gate.weight")) return KoilTensorRole.MOE_ROUTER;
        if (isExpert(n) && containsAny(n, "gate_proj.weight", "w1.weight", "ffn_gate")) return KoilTensorRole.EXPERT_GATE;
        if (isExpert(n) && containsAny(n, "up_proj.weight", "w3.weight", "ffn_up")) return KoilTensorRole.EXPERT_UP;
        if (isExpert(n) && containsAny(n, "down_proj.weight", "w2.weight", "ffn_down")) return KoilTensorRole.EXPERT_DOWN;
        if (containsAny(n, "conv1d", "conv.weight", "short_conv", "conv_kernel")) return KoilTensorRole.CONVOLUTION;
        if (containsAny(n, "ssm.in_proj", "ssm_in", "in_proj.weight") && n.contains("ssm")) return KoilTensorRole.SSM_INPUT;
        if (containsAny(n, "ssm.out_proj", "ssm_out", "out_proj.weight") && n.contains("ssm")) return KoilTensorRole.SSM_OUTPUT;
        if (containsAny(n, "ssm_a", "ssm.d", "state_matrix", "state.weight")) return KoilTensorRole.SSM_STATE;
        return KoilTensorRole.UNKNOWN;
    }

    private static boolean validateRank(KoilTensorRole role, List<Long> shape) {
        if (role == KoilTensorRole.UNKNOWN) return true;
        if (shape == null || shape.isEmpty()) return false;
        return switch (role) {
            case TOKEN_EMBEDDING, OUTPUT_PROJECTION, ATTENTION_QUERY, ATTENTION_KEY, ATTENTION_VALUE,
                    ATTENTION_OUTPUT, FFN_GATE, FFN_UP, FFN_DOWN, MOE_ROUTER, EXPERT_GATE, EXPERT_UP,
                    EXPERT_DOWN, CROSS_ATTENTION_QUERY, CROSS_ATTENTION_KEY, CROSS_ATTENTION_VALUE,
                    CROSS_ATTENTION_OUTPUT -> shape.size() >= 2;
            case OUTPUT_NORM, ATTENTION_NORM, FFN_NORM -> shape.size() == 1;
            case CONVOLUTION, SSM_INPUT, SSM_OUTPUT, SSM_STATE -> shape.size() >= 1;
            case UNKNOWN -> true;
        };
    }

    private static int layerIndex(String name) {
        Matcher matcher = LAYER.matcher(name == null ? "" : name);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static boolean matches(String value, String... candidates) {
        for (String candidate : candidates) if (value.equals(candidate) || value.endsWith("." + candidate)) return true;
        return false;
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private static boolean isExpert(String value) {
        return value.contains("expert") || value.contains("experts.") || value.contains("ffn_exps");
    }

    private static boolean isLayerTensor(String value) {
        return LAYER.matcher(value).find();
    }
}
