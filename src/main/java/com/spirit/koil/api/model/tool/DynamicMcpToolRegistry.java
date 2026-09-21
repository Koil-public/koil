package com.spirit.koil.api.model.tool;

import com.google.gson.JsonObject;
import com.spirit.koil.api.mcp.McpRuntimeManager;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ToolExecutionPolicy;
import com.spirit.koil.api.model.retrieval.KoilKnowledgeRuntime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import com.spirit.koil.api.mcp.McpRuntimeHealth;

/** Dynamic MCP tools normalized into Koil definitions; unclassified external tools require approval. */
public final class DynamicMcpToolRegistry {
    private static final Map<String, Binding> TOOLS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, McpRuntimeManager> ATTACHED_RUNTIMES = new java.util.concurrent.ConcurrentHashMap<>();
    private DynamicMcpToolRegistry() {}

    /**
     * Starts one configured runtime and keeps its model tools synchronized with
     * each successful MCP tools/list negotiation or later recovery.
     */
    public static CompletableFuture<McpRuntimeHealth> register(String providerIdentity, McpRuntimeManager runtime) {
        String identity = providerIdentity == null ? "" : providerIdentity.strip();
        if (identity.isBlank() || runtime == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("MCP provider identity and runtime are required."));
        }
        if (ATTACHED_RUNTIMES.put(identity, runtime) != runtime) {
            runtime.addHealthListener(health -> synchronize(identity, runtime));
        }
        return runtime.start().thenApply(health -> {
            synchronize(identity, runtime);
            return health;
        });
    }

    /** Removes only model registrations owned by this runtime; caller owns process shutdown. */
    public static void unregister(String providerIdentity, McpRuntimeManager runtime) {
        String identity = providerIdentity == null ? "" : providerIdentity.strip();
        if (identity.isBlank()) return;
        ATTACHED_RUNTIMES.remove(identity, runtime);
        synchronize(identity, null);
    }

    public static synchronized void synchronize(String providerIdentity, McpRuntimeManager runtime) {
        String provider = stableProviderId(providerIdentity);
        TOOLS.entrySet().removeIf(entry -> entry.getValue().provider().equals(provider));
        if (runtime == null || runtime.health().state() != com.spirit.koil.api.mcp.McpRuntimeHealth.State.READY) {
            KoilKnowledgeRuntime.refreshBuiltInKnowledgeIfActive();
            return;
        }
        for (Map.Entry<String, JsonObject> entry : runtime.toolDescriptors().entrySet()) {
            String nativeName = entry.getKey();
            JsonObject descriptor = entry.getValue();
            String id = "integration." + provider + "." + normalize(nativeName);
            JsonObject schema = descriptor.has("inputSchema") && descriptor.get("inputSchema").isJsonObject()
                    ? descriptor.getAsJsonObject("inputSchema").deepCopy() : objectSchema();
            String description = descriptor.has("description") ? descriptor.get("description").getAsString() : "External integration capability.";
            TOOLS.put(id, new Binding(provider, nativeName, runtime, new ModelToolDefinition(id,
                    "External integration capability: " + description, schema, List.of("mcp_provider_ready"),
                    Set.of("external_mcp"), false, Duration.ofSeconds(30), true, true,
                    Set.of("completed", "failed", "unsupported"), ToolExecutionPolicy.conservative())));
        }
        KoilKnowledgeRuntime.refreshBuiltInKnowledgeIfActive();
    }

    public static List<ModelToolDefinition> modelTools() { return TOOLS.values().stream().map(binding -> binding.definition()).sorted(java.util.Comparator.comparing(ModelToolDefinition::id)).toList(); }
    public static boolean supports(String id) { return id != null && TOOLS.containsKey(id); }
    public static java.util.Optional<ModelToolDefinition> definition(String id) { Binding binding = TOOLS.get(id); return binding == null ? java.util.Optional.empty() : java.util.Optional.of(binding.definition()); }
    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        Binding binding = call == null ? null : TOOLS.get(call.toolId());
        if (binding == null) return CompletableFuture.completedFuture(new ModelToolResult("", call == null ? "" : call.toolId(), "unsupported", new JsonObject(), "dynamic_tool_unavailable", "The external tool is no longer registered."));
        return binding.runtime().callTool(binding.nativeName(), call.arguments(), binding.definition().timeout())
                .handle((result, failure) -> {
                    JsonObject output = result == null ? new JsonObject() : result.deepCopy();
                    output.addProperty("hook", "MCP " + binding.provider() + " / " + binding.nativeName());
                    java.util.List<String> tail = binding.runtime().stderrTail();
                    if (!tail.isEmpty()) {
                        com.google.gson.JsonArray stderr = new com.google.gson.JsonArray();
                        int start = Math.max(0, tail.size() - 12);
                        for (int index = start; index < tail.size(); index++) stderr.add(tail.get(index));
                        output.add("stderrTail", stderr);
                    }
                    if (failure == null) {
                        boolean errored = output.has("isError") && output.get("isError").isJsonPrimitive()
                                && output.get("isError").getAsBoolean();
                        return new ModelToolResult(call.id(), call.toolId(), errored ? "failed" : "completed",
                                output, errored ? "external_mcp_error" : "",
                                errored ? "External MCP tool reported an error." : "External MCP tool completed.");
                    }
                    output.addProperty("lifecycleOutput", "MCP call failed; captured runtime tail is attached when available.");
                    return new ModelToolResult(call.id(), call.toolId(), "failed", output,
                            "external_mcp_failed", failure.getMessage());
                });
    }

    private static String stableProviderId(String value) { return hash(value == null ? "" : value).substring(0, 10); }
    private static String normalize(String value) { String clean = value == null ? "tool" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("(^_+|_+$)", ""); return clean.isBlank() ? "tool" : clean; }
    private static String hash(String value) { try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b)); return out.toString(); } catch (Exception failure) { throw new IllegalStateException(failure); } }
    private static JsonObject objectSchema() { JsonObject schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", new JsonObject()); schema.addProperty("additionalProperties", false); return schema; }
    private record Binding(String provider, String nativeName, McpRuntimeManager runtime, ModelToolDefinition definition) {}
}
