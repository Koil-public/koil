package com.spirit.koil.api.model.cache;

import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelMessage;
import com.spirit.koil.api.model.ModelToolDefinition;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Small deterministic proof for cache keys and canonical schema serialization. */
public final class ModelPromptCacheIdentityProof {
    private ModelPromptCacheIdentityProof() {}

    public static void main(String[] args) {
        JsonObject firstSchema = new JsonObject();
        firstSchema.addProperty("type", "object");
        JsonObject firstProperties = new JsonObject();
        JsonObject alpha = new JsonObject();
        alpha.addProperty("type", "string");
        JsonObject beta = new JsonObject();
        beta.addProperty("type", "integer");
        firstProperties.add("beta", beta);
        firstProperties.add("alpha", alpha);
        firstSchema.add("properties", firstProperties);

        JsonObject secondSchema = new JsonObject();
        JsonObject secondProperties = new JsonObject();
        secondProperties.add("alpha", alpha.deepCopy());
        secondProperties.add("beta", beta.deepCopy());
        secondSchema.add("properties", secondProperties);
        secondSchema.addProperty("type", "object");

        ModelToolDefinition first = tool("b.tool", firstSchema);
        ModelToolDefinition second = tool("a.tool", secondSchema);
        String left = ModelPromptCacheIdentity.stablePrefix("ask", "system\r\ncore", List.of(first, second), "v1");
        String right = ModelPromptCacheIdentity.stablePrefix("ask", "system\ncore", List.of(second, first), "v1");
        require(left.equals(right), "tool ordering or JSON member ordering changed the stable cache key");

        String requestA = ModelPromptCacheIdentity.fullRequest(
                "stable\nvolatile-a", List.of(ModelMessage.user("hello")), List.of(first, second));
        String requestB = ModelPromptCacheIdentity.fullRequest(
                "stable\nvolatile-b", List.of(ModelMessage.user("hello")), List.of(first, second));
        require(!requestA.equals(requestB), "volatile request changes did not alter the full request fingerprint");
        require(left.matches("[0-9a-f]{64}"), "stable fingerprint was not SHA-256");
        System.out.println("model prompt cache identity proof passed");
    }

    private static ModelToolDefinition tool(String id, JsonObject schema) {
        return new ModelToolDefinition(
                id, "proof", schema, List.of(), Set.of(), true,
                Duration.ofSeconds(1), true, false, Set.of("completed")
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
