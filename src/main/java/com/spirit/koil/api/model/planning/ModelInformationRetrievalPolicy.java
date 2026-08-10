package com.spirit.koil.api.model.planning;

import com.spirit.koil.api.model.ModelToolDefinition;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compact, tool-set-aware retrieval contract. It teaches the model how to use
 * only the information capabilities actually supplied for the current round
 * without duplicating their full schemas in prompt text.
 */
public final class ModelInformationRetrievalPolicy {
    private ModelInformationRetrievalPolicy() {
    }

    public static String promptFor(List<ModelToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) return "";
        Set<String> ids = tools.stream().map(ModelToolDefinition::id).collect(Collectors.toSet());
        boolean workspace = ids.stream().anyMatch(id -> id.startsWith("workspace."));
        boolean minecraft = ids.stream().anyMatch(id -> id.startsWith("minecraft."));
        boolean internet = ids.stream().anyMatch(id -> id.startsWith("internet."));
        if (!workspace && !minecraft && !internet) return "";

        StringBuilder policy = new StringBuilder("""
                Information-efficiency contract: supplied tool definitions are the exact current capability and argument contract. Request only the smallest missing fact needed for the next decision, inspect its structured result, then stop gathering or make one narrower follow-up. Do not inventory capabilities, repeat unchanged observations, or fetch broad context speculatively.
                """.strip());
        if (workspace) {
            policy.append(" For workspace search, narrow path/fileGlob; use outputMode=count for totals, files for paths, matches for exact words/columns, and lines only when surrounding code is needed. Add contextBefore/contextAfter only when required. Read from an exact returned line with a small maxLines and follow nextStartLine only while missing evidence remains.");
        }
        if (minecraft) {
            policy.append(" For Minecraft knowledge/state, request the specific query and smallest fields/limit subset; use the live registry/command evidence instead of broad catalogs when the target is already known.");
        }
        if (internet) {
            policy.append(" For internet research, search narrowly, fetch only a result that can materially resolve the question, and stop when sufficient independent evidence exists.");
        }
        return policy.toString();
    }
}
