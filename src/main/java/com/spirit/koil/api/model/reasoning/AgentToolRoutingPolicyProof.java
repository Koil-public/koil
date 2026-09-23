package com.spirit.koil.api.model.reasoning;

import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelObjectiveLedger;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Deterministic proof that authoritative state, not prompt order, drives later tool ranking. */
public final class AgentToolRoutingPolicyProof {
    private AgentToolRoutingPolicyProof() {}

    public static void main(String[] args) { run(); }

    public static void run() {
        AgentState state = new AgentState(UUID.randomUUID(), "give me a golden apple", null, List.of());
        state.addUnknown("item-id", "Resolve the exact Minecraft item identifier", "identifier_resolution",
                "minecraft.registry_search");

        List<ModelToolDefinition> tools = List.of(
                tool("internet.search"),
                tool("minecraft.item_info"),
                tool("minecraft.registry_search"),
                tool("workspace.read")
        );
        List<ModelToolDefinition> prioritized = AgentToolRoutingPolicy.prioritize(state, tools, Set.of());
        require("minecraft.registry_search".equals(prioritized.get(0).id()),
                "exact unknown resolver was not ranked first");

        AgentToolRoutingPolicy.Focus focus = AgentToolRoutingPolicy.focus(state, tools, Set.of());
        require(focus.toolIds().contains("minecraft.registry_search"),
                "focus did not retain the exact resolver");
        require(focus.target().contains("identifier_resolution"),
                "focus did not expose the unresolved state target");

        AgentState familyState = new AgentState(UUID.randomUUID(), "inspect current context", null, List.of());
        familyState.addUnknown("context", "Need current workspace context", "context_dependency", "reasoning_controller");
        List<ModelToolDefinition> family = AgentToolRoutingPolicy.prioritize(familyState, tools, Set.of());
        require("workspace.read".equals(family.get(0).id()),
                "semantic state family did not prioritize an available workspace resolver");

        ModelObjectiveLedger orderedLedger = ModelObjectiveLedger.parse(
                "walk, then jump, then put me in creative mode");
        AgentState orderedState = new AgentState(UUID.randomUUID(),
                "walk, then jump, then put me in creative mode", null, orderedLedger.snapshot());
        List<ModelToolDefinition> orderedTools = List.of(
                tool("movement.walk_relative"),
                tool("player.jump"),
                tool("minecraft.command")
        );
        Set<String> current = orderedLedger.pendingToolIds();
        AgentToolRoutingPolicy.Focus orderedFocus = AgentToolRoutingPolicy.focus(orderedState, orderedTools, current);
        require(!current.isEmpty(), "ordered ledger did not expose a current frontier");
        require(current.contains(orderedFocus.toolIds().get(0)),
                "future ordered objective outranked the current durable task frontier");
        require(!orderedFocus.target().contains("creative"),
                "future creative-mode objective leaked into the current routing target");

        System.out.println("Agent tool routing policy proof passed.");
    }

    private static ModelToolDefinition tool(String id) {
        return new ModelToolDefinition(id, id, new JsonObject(), List.of(), Set.of(), false,
                Duration.ofSeconds(5), true, false, Set.of("completed"));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
