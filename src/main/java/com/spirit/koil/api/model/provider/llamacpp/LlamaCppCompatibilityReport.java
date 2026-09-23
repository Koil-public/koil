package com.spirit.koil.api.model.provider.llamacpp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Final negotiated compatibility after combining catalog/artifact facts with
 * observations from the running llama-server instance.
 */
record LlamaCppCompatibilityReport(
        Status status,
        boolean chat,
        boolean systemRole,
        boolean toolCalling,
        boolean parallelToolCalling,
        boolean reasoning,
        int contextTokens,
        Set<String> protocolCapabilities,
        List<String> notes
) {
    LlamaCppCompatibilityReport {
        status = status == null ? Status.UNKNOWN : status;
        contextTokens = Math.max(0, contextTokens);
        protocolCapabilities = protocolCapabilities == null ? Set.of() : Set.copyOf(protocolCapabilities);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    static LlamaCppCompatibilityReport negotiate(
            LlamaCppProtocolProfile profile,
            LlamaCppRuntimeCapabilities runtime,
            int configuredContextTokens
    ) {
        LlamaCppProtocolProfile safeProfile = profile == null ? LlamaCppProtocolProfile.generic() : profile;
        LlamaCppRuntimeCapabilities safeRuntime = runtime == null ? LlamaCppRuntimeCapabilities.unknown() : runtime;
        List<String> notes = new ArrayList<>();
        Set<String> protocols = new LinkedHashSet<>();
        protocols.add("openai_chat");

        boolean chat = true;
        boolean systemRole = safeProfile.systemPolicy() == LlamaCppProtocolProfile.SystemPolicy.NATIVE;
        boolean tools = safeProfile.permitsTools();
        boolean parallelTools = tools;
        boolean reasoning = safeProfile.reasoning();

        if (safeRuntime.observed()) {
            protocols.addAll(safeRuntime.capabilities());
            boolean exactTemplateCaps = safeRuntime.capabilities().contains("chat_template_caps_observed");
            if (exactTemplateCaps) {
                systemRole = safeRuntime.capabilities().contains("supports_system_role");
                tools = safeRuntime.capabilities().contains("supports_tools")
                        && safeRuntime.capabilities().contains("supports_tool_calls");
                parallelTools = tools && safeRuntime.capabilities().contains("supports_parallel_tool_calls");
                if (safeProfile.permitsTools() && !tools) {
                    notes.add("runtime chat template does not support Koil tool definitions/calls; tools disabled for this model");
                }
                if (safeProfile.systemPolicy() == LlamaCppProtocolProfile.SystemPolicy.NATIVE && !systemRole) {
                    notes.add("runtime chat template rejects native system role; Koil will use an isolated transport control turn and preserve the actual user message separately");
                }
            } else {
                if (safeRuntime.capabilities().contains("template_tools")) tools = true;
                parallelTools = tools;
            }
            if (safeRuntime.capabilities().contains("template_reasoning")) reasoning = true;
            if (!safeRuntime.chatTemplate().isBlank() && !safeProfile.artifactInspected()) {
                notes.add("runtime supplied chat-template evidence unavailable during artifact inspection");
            }
        } else {
            notes.add("runtime capability endpoint was unavailable; using artifact/catalog protocol evidence");
        }

        if (tools) protocols.add("tool_calling");
        if (parallelTools) protocols.add("parallel_tool_calling");
        if (reasoning) protocols.add("reasoning_stream");
        if (systemRole) protocols.add("system_role");
        if (safeProfile.computeTopology() == LlamaCppProtocolProfile.ComputeTopology.MOE
                || safeProfile.computeTopology() == LlamaCppProtocolProfile.ComputeTopology.HYBRID_MOE) protocols.add("moe");
        if (safeProfile.computeTopology() == LlamaCppProtocolProfile.ComputeTopology.HYBRID
                || safeProfile.computeTopology() == LlamaCppProtocolProfile.ComputeTopology.HYBRID_MOE) protocols.add("hybrid_sequence");

        int context = configuredContextTokens;
        if (safeProfile.artifactContextTokens() > 0) context = safeProfile.artifactContextTokens();
        if (safeRuntime.contextTokens() > 0) context = safeRuntime.contextTokens();
        if (context <= 0) context = Math.max(512, configuredContextTokens);

        Status status = notes.isEmpty() ? Status.COMPATIBLE : Status.DEGRADED;
        return new LlamaCppCompatibilityReport(
                status, chat, systemRole, tools, parallelTools, reasoning,
                context, protocols, notes
        );
    }

    String summary() {
        return status.name().toLowerCase(java.util.Locale.ROOT)
                + "; chat=" + chat
                + "; system=" + systemRole
                + "; tools=" + toolCalling
                + "; parallelTools=" + parallelToolCalling
                + "; reasoning=" + reasoning
                + "; context=" + contextTokens;
    }

    enum Status { COMPATIBLE, DEGRADED, INCOMPATIBLE, UNKNOWN }
}
