package com.spirit.koil.api.model.provider.llamacpp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelMessage;
import com.spirit.koil.api.model.ModelRole;
import com.spirit.koil.api.model.StreamingModelRequest;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Deterministic proof that system/control context cannot be merged into the actual human turn. */
public final class LlamaCppRoleBoundaryProof {
    private LlamaCppRoleBoundaryProof() {
    }

    public static void run() {
        systemMessagesCanonicalizeOutOfConversation();
        nativeSystemRoleStaysNative();
        unsupportedSystemRoleUsesIsolatedBridge();
        bridgeRequiresActualUserTurn();
    }

    private static void systemMessagesCanonicalizeOutOfConversation() {
        StreamingModelRequest request = new StreamingModelRequest(
                null,
                "proof",
                "primary-system",
                List.of(
                        new ModelMessage(null, ModelRole.SYSTEM, "secondary-system", "", null, Map.of()),
                        ModelMessage.user("actual-user")
                ),
                List.of(),
                128,
                Duration.ofSeconds(30),
                Map.of()
        );
        require(request.messages().size() == 1, "SYSTEM message leaked into canonical conversation");
        require(request.messages().get(0).role() == ModelRole.USER, "actual user role changed during canonicalization");
        require(request.messages().get(0).content().equals("actual-user"), "actual user content changed during canonicalization");
        require(request.systemPrompt().contains("primary-system") && request.systemPrompt().contains("secondary-system"),
                "SYSTEM message was not promoted into the dedicated system contract");
    }

    private static void nativeSystemRoleStaysNative() {
        StreamingModelRequest request = request("SYSTEM_SENTINEL", "USER_SENTINEL");
        JsonObject payload = LlamaCppChatRequestCompiler.compile(
                request,
                LlamaCppToolNameMap.from(request),
                0,
                "proof-model",
                profile(LlamaCppProtocolProfile.SystemPolicy.NATIVE, LlamaCppProtocolProfile.RolePolicy.NATIVE),
                true,
                false,
                false,
                false
        );
        JsonArray messages = payload.getAsJsonArray("messages");
        require(messages.size() == 2, "native system request should contain system + user");
        require("system".equals(messages.get(0).getAsJsonObject().get("role").getAsString()), "native system role was lost");
        require("SYSTEM_SENTINEL".equals(messages.get(0).getAsJsonObject().get("content").getAsString()), "native system content changed");
        require("user".equals(messages.get(1).getAsJsonObject().get("role").getAsString()), "actual user role was lost");
        require("USER_SENTINEL".equals(messages.get(1).getAsJsonObject().get("content").getAsString()), "actual user content changed");
    }

    private static void unsupportedSystemRoleUsesIsolatedBridge() {
        StreamingModelRequest request = request("SYSTEM_SENTINEL", "USER_SENTINEL");
        JsonObject payload = LlamaCppChatRequestCompiler.compile(
                request,
                LlamaCppToolNameMap.from(request),
                0,
                "proof-model",
                profile(LlamaCppProtocolProfile.SystemPolicy.CONTROL_TURN_BRIDGE, LlamaCppProtocolProfile.RolePolicy.STRICT_ALTERNATING),
                false,
                false,
                false,
                false
        );
        JsonArray messages = payload.getAsJsonArray("messages");
        require(messages.size() == 3, "bridge must be control-user + assistant-ack + actual-user");
        JsonObject control = messages.get(0).getAsJsonObject();
        JsonObject ack = messages.get(1).getAsJsonObject();
        JsonObject actual = messages.get(2).getAsJsonObject();
        require("user".equals(control.get("role").getAsString()), "strict-template control bridge role changed");
        require(control.get("content").getAsString().contains("authority=system"), "control bridge lacks explicit authority marker");
        require(control.get("content").getAsString().contains("SYSTEM_SENTINEL"), "system contract missing from bridge");
        require("assistant".equals(ack.get("role").getAsString()), "control acknowledgement must be assistant-authored");
        require("user".equals(actual.get("role").getAsString()), "actual human request lost user role");
        require("USER_SENTINEL".equals(actual.get("content").getAsString()), "actual human request was modified");
        require(!actual.get("content").getAsString().contains("SYSTEM_SENTINEL"), "system contract leaked into actual user content");
    }

    private static void bridgeRequiresActualUserTurn() {
        StreamingModelRequest request = new StreamingModelRequest(
                null, "proof", "SYSTEM_SENTINEL", List.of(ModelMessage.assistant("history")), List.of(),
                128, Duration.ofSeconds(30), Map.of()
        );
        boolean failed = false;
        try {
            LlamaCppChatRequestCompiler.compile(
                    request,
                    LlamaCppToolNameMap.from(request),
                    0,
                    "proof-model",
                    profile(LlamaCppProtocolProfile.SystemPolicy.CONTROL_TURN_BRIDGE, LlamaCppProtocolProfile.RolePolicy.STRICT_ALTERNATING),
                    false,
                    false,
                    false,
                    false
            );
        } catch (IllegalStateException expected) {
            failed = true;
        }
        require(failed, "unsafe system-role fallback should fail closed without an actual user turn");
    }

    private static StreamingModelRequest request(String system, String user) {
        return new StreamingModelRequest(
                null, "proof", system, List.of(ModelMessage.user(user)), List.of(),
                128, Duration.ofSeconds(30), Map.of()
        );
    }

    private static LlamaCppProtocolProfile profile(
            LlamaCppProtocolProfile.SystemPolicy systemPolicy,
            LlamaCppProtocolProfile.RolePolicy rolePolicy
    ) {
        return new LlamaCppProtocolProfile(
                "proof", "proof", LlamaCppProtocolProfile.ComputeTopology.DENSE,
                0, 0, "proof", "", "", "", systemPolicy, rolePolicy,
                LlamaCppProtocolProfile.StopPolicy.TEMPLATE_OWNED,
                false, false, 4096, true, "proof"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
