package com.spirit.koil.api.model;

import com.google.gson.JsonObject;
import com.spirit.koil.api.model.reasoning.AgentState;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Deterministic proof for state-driven argument provenance and blocking. */
public final class ToolArgumentResolverProof {
    private ToolArgumentResolverProof() {}

    public static void main(String[] args) { run(); }

    public static void run() {
        AgentState decided = new AgentState(UUID.randomUUID(), "tell me about the golden apple", null, List.of());
        decided.decide("golden-id", "canonical_id", "minecraft:golden_apple", 1.0D, Set.of("registry"), true);
        JsonObject proposedArgs = new JsonObject();
        proposedArgs.addProperty("id", "minecraft:golden_apple");
        ToolArgumentResolver.ExecutionResolution decisionBinding = ToolArgumentResolver.bindForExecution(
                decided,
                new ModelToolCall("item-call", "minecraft.item_info", proposedArgs),
                "tell me about the golden apple",
                false
        );
        require(decisionBinding.executable(), "locked decision did not produce an executable binding");
        require(decisionBinding.bindings().get("id").source() == AgentState.ArgumentSource.STATE_DECISION,
                "canonical id was not attributed to AgentState decision provenance");

        AgentState evidenceState = new AgentState(UUID.randomUUID(), "inspect the resolved item", null, List.of());
        JsonObject registryOutput = new JsonObject();
        registryOutput.addProperty("id", "minecraft:golden_apple");
        evidenceState.observeToolResult(new ModelToolResult(
                "registry-call", "minecraft.registry_search", "completed", registryOutput,
                "", "resolved exact item", System.currentTimeMillis(), System.currentTimeMillis(),
                "passed", List.of(), false, false, "not_required"
        ));
        ToolArgumentResolver.ExecutionResolution evidenceBinding = ToolArgumentResolver.bindForExecution(
                evidenceState,
                new ModelToolCall("item-after-registry", "minecraft.item_info", new JsonObject()),
                "inspect the resolved item",
                false
        );
        require(evidenceBinding.executable(), "validated prior tool result did not fill the required id");
        require("minecraft:golden_apple".equals(evidenceBinding.call().arguments().get("id").getAsString()),
                "prior tool output supplied the wrong id");
        require(evidenceBinding.bindings().get("id").source() == AgentState.ArgumentSource.TOOL_RESULT,
                "prior tool result provenance was not retained");

        AgentState missingState = new AgentState(UUID.randomUUID(), "inspect an item", null, List.of());
        ToolArgumentResolver.ExecutionResolution missing = ToolArgumentResolver.bindForExecution(
                missingState,
                new ModelToolCall("missing-item", "minecraft.item_info", new JsonObject()),
                "inspect an item",
                false
        );
        require(!missing.executable(), "missing required id was incorrectly executable");
        require(missingState.snapshot().unknowns().stream().anyMatch(value ->
                        value.id().equals("tool-argument:missing-item:id") && value.status() == AgentState.UnknownStatus.OPEN),
                "missing required argument did not become an explicit AgentState unknown");

        AgentState movementState = new AgentState(UUID.randomUUID(), "walk, then jump, then put me in creative mode", null, List.of());
        ToolArgumentResolver.ExecutionResolution walk = ToolArgumentResolver.bindForExecution(
                movementState, new ModelToolCall("walk-current", "movement.walk_relative", new JsonObject()),
                "walk", true
        );
        require(walk.executable(), "bare current-task walk did not resolve deterministically");
        require("forward".equals(walk.call().arguments().get("direction").getAsString())
                        && Math.abs(walk.call().arguments().get("distance").getAsDouble() - 1.0D) < 0.001D,
                "bare walk did not resolve to the minimal forward movement");

        ToolArgumentResolver.ExecutionResolution creative = ToolArgumentResolver.bindForExecution(
                movementState, new ModelToolCall("creative-current", "minecraft.command", new JsonObject()),
                "put me in creative mode", true
        );
        require(creative.executable(), "creative-mode task did not resolve its command deterministically");
        require("gamemode creative".equals(creative.call().arguments().get("command").getAsString()),
                "creative-mode task resolved the wrong command");


        ToolArgumentResolver.ExecutionResolution day = ToolArgumentResolver.bindForExecution(
                movementState, new ModelToolCall("day-current", "world.set_time", new JsonObject()),
                "set the time day", true
        );
        require(day.executable(), "world-time task did not resolve deterministically");
        require("day".equals(day.call().arguments().get("time").getAsString()),
                "world-time task resolved the wrong time value");

        ToolArgumentResolver.ExecutionResolution giveApples = ToolArgumentResolver.bindForExecution(
                movementState, new ModelToolCall("give-current", "minecraft.command", new JsonObject()),
                "give me 4 apples.", true
        );
        require(giveApples.executable(), "ordered give task did not resolve deterministically");
        require("give @s minecraft:apple 4".equals(giveApples.call().arguments().get("command").getAsString()),
                "ordered give task resolved the wrong command");

        ToolArgumentResolver.ExecutionResolution killSelf = ToolArgumentResolver.bindForExecution(
                movementState, new ModelToolCall("kill-self", "minecraft.command", new JsonObject()),
                "kill me", true
        );
        require(killSelf.executable(), "self-kill task did not resolve deterministically");
        require("kill @s".equals(killSelf.call().arguments().get("command").getAsString()),
                "self-kill task resolved the wrong command");

        System.out.println("Tool argument resolver proof passed.");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
