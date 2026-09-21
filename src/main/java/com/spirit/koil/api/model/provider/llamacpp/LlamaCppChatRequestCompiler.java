package com.spirit.koil.api.model.provider.llamacpp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.LocalModelRuntimeLog;
import com.spirit.koil.api.model.ModelDebugMode;
import com.spirit.koil.api.model.ModelMessage;
import com.spirit.koil.api.model.ModelRole;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.StreamingModelRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Compiles Koil's canonical conversation into llama-server's OpenAI wire protocol. */
final class LlamaCppChatRequestCompiler {
    private LlamaCppChatRequestCompiler() {
    }

    static JsonObject compile(
            StreamingModelRequest request,
            LlamaCppToolNameMap toolNames,
            int slotId,
            String modelId,
            LlamaCppProtocolProfile profile,
            boolean allowSystemRole,
            boolean allowTools,
            boolean allowParallelTools,
            boolean allowReasoning
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("model", modelId);
        root.addProperty("max_tokens", request.unboundedOutput() ? -1 : request.maximumOutputTokens());
        root.addProperty("stream", true);
        root.addProperty("id_slot", Math.max(0, slotId));
        root.addProperty("cache_prompt", true);

        // Koil always asks llama-server to begin a fresh assistant turn.  The
        // conversation projector deliberately removes incomplete plain-assistant
        // drafts from the active turn, so assistant-prefill/continuation semantics
        // are never desired here.  Being explicit also protects strict Jinja
        // templates from interpreting a retained assistant tool turn as a request
        // to continue that assistant message.
        root.addProperty("add_generation_prompt", true);
        root.addProperty("continue_final_message", false);

        // Modern GGUF chat templates own EOS/turn termination. Injecting legacy
        // textual stop markers corrupts Qwen, DeepSeek, GPT-OSS, GLM, Gemma and
        // other template-driven models, so no universal stop list is emitted.
        JsonObject streamOptions = new JsonObject();
        streamOptions.addProperty("include_usage", true);
        root.add("stream_options", streamOptions);

        // Prompt progress is operational liveness, not debug-only model data.
        // Request it on every stream so Koil can distinguish real prefill work
        // from a dead/stalled request. Presentation still remains debug-gated.
        // Older llama.cpp builds are handled by the provider's optional-field
        // compatibility retry.
        root.addProperty("return_progress", true);
        root.addProperty("sse_ping_interval", 10);

        // Debug mode adds heavier per-token diagnostics. None of these fields
        // are interpreted as model reasoning.
        if (ModelDebugMode.enabled()) {
            root.addProperty("timings_per_token", true);
            root.addProperty("return_tokens", true);
        }

        JsonArray messages = new JsonArray();
        List<ModelMessage> projected = project(request.messages(), profile.rolePolicy());
        if (!request.systemPrompt().isBlank()) {
            if (allowSystemRole && profile.systemPolicy() == LlamaCppProtocolProfile.SystemPolicy.NATIVE) {
                JsonObject system = new JsonObject();
                system.addProperty("role", "system");
                system.addProperty("content", request.systemPrompt());
                messages.add(system);
            } else {
                projected = bridgeSystemPrompt(request.systemPrompt(), projected);
            }
        }

        if (!sameIdentitySequence(projected, request.messages())) {
            LocalModelRuntimeLog.write(
                    "llama_protocol_projection",
                    "request=" + request.id() + " profile=" + profile.family()
                            + " input=" + roleSequence(request.messages())
                            + " projected=" + roleSequence(projected)
            );
        }
        for (int index = 0; index < projected.size();) {
            ModelMessage message = projected.get(index);
            if (message.role() == ModelRole.SYSTEM) {
                index++;
                continue;
            }
            if (isAssistantToolCall(message)) {
                List<ModelMessage> calls = new ArrayList<>();
                while (index < projected.size() && isAssistantToolCall(projected.get(index))) {
                    calls.add(projected.get(index++));
                }
                messages.add(assistantToolCalls(calls, toolNames));
                continue;
            }
            messages.add(message(message, toolNames));
            index++;
        }
        root.add("messages", messages);

        // Ask llama.cpp to parse any reasoning convention its active chat template
        // recognizes, even when the catalogue did not classify the model as a
        // dedicated reasoning model. For ordinary templates this is a no-op; for
        // families that expose reasoning it allows the server to surface the native
        // side channel instead of folding it into visible content.
        root.addProperty("reasoning_format", "auto");
        if (allowReasoning) {
            JsonObject templateKwargs = new JsonObject();
            templateKwargs.addProperty("enable_thinking", true);
            root.add("chat_template_kwargs", templateKwargs);
        }

        if (!request.tools().isEmpty() && allowTools) {
            root.add("tools", tools(request.tools(), toolNames));
            root.addProperty("tool_choice", "auto");
            root.addProperty("parse_tool_calls", true);
            root.addProperty("parallel_tool_calls", allowParallelTools);
        } else if (!request.tools().isEmpty()) {
            LocalModelRuntimeLog.write(
                    "llama_tools_disabled",
                    "request=" + request.id() + " model=" + modelId
                            + " family=" + profile.family() + " reason=protocol_profile"
            );
        }
        return root;
    }


    /**
     * Some chat templates cannot accept a native system role.  Never solve that by
     * concatenating Koil's system contract into the real human message: doing so changes
     * the wire-level speaker identity and allows small models to attribute policy text to
     * the user.  Instead, use a transport-only control turn followed by a fixed assistant
     * acknowledgement, then preserve the real user message as its own untouched turn.
     *
     * <p>The bridge is ephemeral. It is never persisted into ModelConversation and its
     * metadata makes its origin explicit for proofs/diagnostics. Strict alternating
     * templates receive user(control), assistant(ack), user(actual), which remains valid.
     * If there is no actual user turn, there is no safe role-preserving fallback and the
     * request is rejected instead of inventing a user instruction.</p>
     */
    private static List<ModelMessage> bridgeSystemPrompt(String systemPrompt, List<ModelMessage> messages) {
        List<ModelMessage> projected = new ArrayList<>(messages == null ? List.of() : messages);
        boolean hasActualUser = projected.stream()
                .anyMatch(message -> message != null
                        && message.role() == ModelRole.USER
                        && message.toolCallId().isBlank());
        if (!hasActualUser) {
            throw new IllegalStateException(
                    "Cannot transport system context through a template without native system-role support: no actual user turn is present"
            );
        }

        String control = """
                [KOIL_CONTROL_CONTEXT authority=system transport=role_bridge]
                The following content is Koil system/runtime policy. It is NOT authored by the user,
                is NOT the user's request, and must never be quoted or attributed as a user instruction.

                %s
                [END_KOIL_CONTROL_CONTEXT]

                The next user-role message is the actual human request. Keep these authorities distinct.
                """.formatted(systemPrompt == null ? "" : systemPrompt).strip();

        ModelMessage controlTurn = new ModelMessage(
                null,
                ModelRole.USER,
                control,
                "",
                null,
                java.util.Map.of(
                        "koil_origin", "system_transport",
                        "koil_authority", "system",
                        "koil_not_user_authored", "true"
                )
        );
        ModelMessage acknowledgement = new ModelMessage(
                null,
                ModelRole.ASSISTANT,
                "[KOIL_CONTROL_ACK] System/runtime context loaded. The next user-role message is the actual user's request.",
                "",
                null,
                java.util.Map.of(
                        "koil_origin", "system_transport_ack",
                        "koil_authority", "system"
                )
        );

        List<ModelMessage> bridged = new ArrayList<>(projected.size() + 2);
        bridged.add(controlTurn);
        bridged.add(acknowledgement);
        bridged.addAll(projected);
        return List.copyOf(bridged);
    }

    private static boolean isAssistantToolCall(ModelMessage message) {
        return message != null
                && message.role() == ModelRole.ASSISTANT
                && !message.toolCallId().isBlank()
                && message.metadata().containsKey("tool_name");
    }

    private static JsonObject assistantToolCalls(List<ModelMessage> messages, LlamaCppToolNameMap toolNames) {
        JsonObject entry = new JsonObject();
        entry.addProperty("role", "assistant");
        String content = messages == null ? "" : messages.stream()
                .map(ModelMessage::content)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n\n"));
        entry.addProperty("content", content);
        JsonArray calls = new JsonArray();
        if (messages != null) {
            for (ModelMessage message : messages) {
                JsonObject call = new JsonObject();
                call.addProperty("id", message.toolCallId());
                call.addProperty("type", "function");
                JsonObject function = new JsonObject();
                function.addProperty("name", toolNames.toWire(message.metadata().get("tool_name")));
                function.addProperty("arguments", message.metadata().getOrDefault("tool_arguments", "{}"));
                call.add("function", function);
                calls.add(call);
            }
        }
        entry.add("tool_calls", calls);
        return entry;
    }

    private static JsonObject message(ModelMessage message, LlamaCppToolNameMap toolNames) {
        JsonObject entry = new JsonObject();
        if (message.role() == ModelRole.TOOL) {
            entry.addProperty("role", "tool");
            entry.addProperty("tool_call_id", message.toolCallId());
            entry.addProperty("content", message.content());
            return entry;
        }
        if (isAssistantToolCall(message)) {
            return assistantToolCalls(List.of(message), toolNames);
        }
        entry.addProperty("role", message.role() == ModelRole.ASSISTANT ? "assistant" : "user");
        entry.addProperty("content", message.content());
        return entry;
    }

    private static JsonArray tools(List<ModelToolDefinition> definitions, LlamaCppToolNameMap toolNames) {
        JsonArray tools = new JsonArray();
        definitions.stream().sorted(Comparator.comparing(ModelToolDefinition::id)).forEach(definition -> {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "function");
            JsonObject function = new JsonObject();
            function.addProperty("name", toolNames.toWire(definition.id()));
            function.addProperty("description", definition.description());
            function.add("parameters", LlamaCppToolSchemaAdapter.toWire(
                    com.spirit.koil.api.model.cache.ModelPromptCacheIdentity.canonicalObject(definition.inputSchema())));
            wrapper.add("function", function);
            tools.add(wrapper);
        });
        return tools;
    }

    /**
     * Projects Koil's durable/session transcript into a chat-template-safe history.
     *
     * <p>Old completed turns are reduced to their visible user/final-assistant pair.
     * Old interrupted turns are discarded instead of leaking a half-finished role sequence
     * into a new request. The newest user turn keeps its tool-call/result transcript, but
     * non-final assistant drafts are intentionally omitted because the provider is about to
     * generate that assistant turn. This shape satisfies strict Mistral/Gemma/Granite-style
     * templates without inventing fake user messages.</p>
     */
    static List<ModelMessage> project(List<ModelMessage> messages, LlamaCppProtocolProfile.RolePolicy policy) {
        List<ModelMessage> source = messages == null ? List.of() : messages.stream()
                .filter(java.util.Objects::nonNull)
                .filter(message -> message.role() != ModelRole.SYSTEM)
                .filter(message -> message.role() == ModelRole.USER || !message.content().isBlank() || !message.toolCallId().isBlank())
                .toList();
        if (source.isEmpty()) return List.of();

        List<Turn> turns = new ArrayList<>();
        Turn current = null;
        for (ModelMessage message : source) {
            if (message.role() == ModelRole.USER) {
                if (current != null) turns.add(current);
                current = new Turn(message);
                continue;
            }
            if (current != null) current.events.add(message);
        }
        if (current != null) turns.add(current);
        if (turns.isEmpty()) return List.of();

        List<ModelMessage> projected = new ArrayList<>();
        for (int index = 0; index < turns.size(); index++) {
            Turn turn = turns.get(index);
            boolean active = index == turns.size() - 1;
            if (!active) {
                ModelMessage finalAssistant = lastPlainAssistant(turn.events);
                if (finalAssistant == null) {
                    // A cancelled/interrupted historical turn has no durable assistant answer.
                    // Dropping it is safer than fabricating an assistant/user bridge and keeps
                    // strict templates from seeing user,user after tool-call-only turns.
                    continue;
                }
                projected.add(turn.user);
                projected.add(finalAssistant);
                continue;
            }

            projected.add(turn.user);
            java.util.Set<String> activeToolCalls = new java.util.LinkedHashSet<>();
            for (ModelMessage event : turn.events) {
                if (isAssistantToolCall(event)) {
                    projected.add(event);
                    activeToolCalls.add(event.toolCallId());
                } else if (event.role() == ModelRole.TOOL
                        && !event.toolCallId().isBlank()
                        && activeToolCalls.contains(event.toolCallId())) {
                    projected.add(event);
                }
                // Plain assistant text in the active turn is an intermediate/non-final draft.
                // The next chat completion is generating the assistant role, so carrying that
                // draft as another assistant message creates assistant,assistant failures.
                // Orphan tool results are also dropped because strict templates require a
                // matching assistant tool call in the same retained request window.
            }
        }
        return List.copyOf(projected);
    }

    private static ModelMessage lastPlainAssistant(List<ModelMessage> events) {
        ModelMessage latest = null;
        for (ModelMessage event : events) {
            if (event.role() == ModelRole.ASSISTANT && !isAssistantToolCall(event) && !event.content().isBlank()) {
                latest = event;
            }
        }
        return latest;
    }

    private static final class Turn {
        private final ModelMessage user;
        private final List<ModelMessage> events = new ArrayList<>();

        private Turn(ModelMessage user) {
            this.user = user;
        }
    }

    private static boolean sameIdentitySequence(List<ModelMessage> a, List<ModelMessage> b) {
        List<ModelMessage> filtered = b == null ? List.of() : b.stream()
                .filter(message -> message != null && message.role() != ModelRole.SYSTEM).toList();
        if (a.size() != filtered.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != filtered.get(i)) return false;
        }
        return true;
    }

    private static String roleSequence(List<ModelMessage> messages) {
        return (messages == null ? List.<ModelMessage>of() : messages).stream()
                .filter(java.util.Objects::nonNull)
                .map(message -> message.role().name().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.joining(","));
    }
}
