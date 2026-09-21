package com.spirit.koil.api.model.provider.llamacpp;

import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelExposedData;
import com.spirit.koil.api.model.ModelHealthState;
import com.spirit.koil.api.model.ModelMessage;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ModelRuntimeTelemetry;
import com.spirit.koil.api.model.ModelUsage;
import com.spirit.koil.api.model.cache.ModelCacheProfile;
import com.spirit.koil.api.model.StreamingModelObserver;
import com.spirit.koil.api.model.StreamingModelRequest;
import com.spirit.koil.api.model.StreamingModelResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class LlamaCppProviderProof {
    private LlamaCppProviderProof() {
    }

    public static void main(String[] args) throws Exception {
        LlamaCppRoleBoundaryProof.run();
        LlamaCppTextToolCallBridgeProof.run();
        proveInlineThinkPartitioning();
        String key = "proof-local-key";
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/health", exchange -> json(exchange, key, "{\"status\":\"ok\"}"));
        server.createContext("/v1/models", exchange -> json(
                exchange,
                key,
                "{\"object\":\"list\",\"data\":[{\"id\":\"proof-model\",\"object\":\"model\"}]}"
        ));
        server.createContext("/props", exchange -> json(
                exchange,
                key,
                "{\"model\":\"proof-model\",\"model_path\":\"/tmp/proof.gguf\",\"build_info\":\"b-proof\",\"default_generation_settings\":{\"n_ctx\":8192},\"chat_template\":\"{% if tools %}tool_calls function{% endif %} <think>reasoning</think>\",\"chat_template_caps\":{\"supports_tools\":true,\"supports_tool_calls\":true,\"supports_system_role\":true,\"supports_parallel_tool_calls\":true,\"supports_preserve_reasoning\":true}}"
        ));
        server.createContext("/v1/chat/completions", exchange -> stream(exchange, key, requestBody));
        server.start();

        Path root = Files.createTempDirectory("koil-llama-provider-proof");
        Path executable = root.resolve("llama-server");
        Path model = root.resolve("proof.gguf");
        Files.writeString(executable, "proof");
        executable.toFile().setExecutable(true);
        Files.writeString(model, "proof");
        LlamaCppConfiguration configuration = new LlamaCppConfiguration(
                true,
                executable,
                model,
                "proof-model",
                4096,
                "127.0.0.1",
                server.getAddress().getPort(),
                key,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                1,
                ModelCacheProfile.LOW_MEMORY,
                root.resolve("cache"),
                0,
                ""
        );
        OkHttpClient http = new OkHttpClient.Builder().readTimeout(0L, TimeUnit.MILLISECONDS).build();
        LlamaCppLocalModelProvider provider = new LlamaCppLocalModelProvider(configuration, http);
        try {
            require(provider.start().get(5L, TimeUnit.SECONDS).state() == ModelHealthState.READY,
                    "provider did not connect to compatible local runtime");
            require(provider.capabilities().maximumContextTokens() == 8192,
                    "runtime /props context was not negotiated from nested default_generation_settings");
            CompletableFuture<StreamingModelResponse> completed = new CompletableFuture<>();
            StringBuilder streamed = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            List<ModelUsage> liveUsage = new java.util.concurrent.CopyOnWriteArrayList<>();
            StreamingModelRequest request = new StreamingModelRequest(
                    UUID.randomUUID(),
                    "proof",
                    "system",
                    List.of(ModelMessage.user("test")),
                    List.of(new ModelToolDefinition(
                            "minecraft.command",
                            "Submit a Minecraft command",
                            schema(),
                            List.of(),
                            java.util.Set.of("movement"),
                            false,
                            Duration.ofSeconds(10),
                            true,
                            false,
                            java.util.Set.of("completed", "failed", "cancelled")
                    )),
                    64,
                    Duration.ofSeconds(5),
                    Map.of()
            );
            provider.generate(request, new StreamingModelObserver() {
                @Override
                public void onTextDelta(UUID requestId, String delta) {
                    streamed.append(delta);
                }

                @Override
                public void onReasoningDelta(UUID requestId, String delta) {
                    reasoning.append(delta);
                }

                @Override
                public void onUsage(UUID requestId, ModelUsage usage) {
                    liveUsage.add(usage);
                }

                @Override
                public void onComplete(StreamingModelResponse response) {
                    completed.complete(response);
                }

                @Override
                public void onFailure(UUID requestId, String code, String detail, Throwable cause) {
                    completed.completeExceptionally(new IllegalStateException(code + ": " + detail, cause));
                }
            });
            StreamingModelResponse response = completed.get(5L, TimeUnit.SECONDS);
            require("small model ready".equals(streamed.toString()), "streamed text was not decoded");
            require("small model ready".equals(response.text()), "final text was not decoded");
            require("checking".equals(reasoning.toString()), "reasoning stream was not separated from visible text");
            require(response.toolCalls().size() == 1, "tool call was not decoded");
            ModelToolCall tool = response.toolCalls().get(0);
            require("minecraft.command".equals(tool.toolId()), "canonical tool name was not restored");
            require("/time set day".equals(tool.arguments().get("command").getAsString()),
                    "tool arguments were incorrect");
            require(requestBody.get().contains("\"name\":\"minecraft_command\""),
                    "dotted tool id was not converted to a llama.cpp-safe wire name");
            require(!requestBody.get().contains("\"name\":\"minecraft.command\""),
                    "canonical dotted tool id leaked into the llama.cpp request");
            require(!requestBody.get().contains("\"maxLength\":2048"),
                    "unsupported large schema repetition leaked into the llama.cpp grammar");
            require(requestBody.get().contains("\"cache_prompt\":true"),
                    "llama.cpp prompt-prefix reuse was not requested");
            require(requestBody.get().contains("\"return_progress\":true"),
                    "llama.cpp prompt-prefill progress was not requested for liveness tracking");
            require(requestBody.get().contains("\"sse_ping_interval\":10"),
                    "llama.cpp stream did not request bounded liveness pings");
            require(requestBody.get().contains("\"add_generation_prompt\":true"),
                    "llama.cpp was not explicitly asked to start a new assistant turn");
            require(requestBody.get().contains("\"continue_final_message\":false"),
                    "llama.cpp assistant-prefill continuation was not explicitly disabled");
            require(requestBody.get().contains("\"id_slot\":0"),
                    "llama.cpp request was not bound to the requested Koil KV slot");
            require(!requestBody.get().contains("\"stop\""),
                    "legacy textual stop markers leaked into a template-owned chat request");
            require(requestBody.get().contains("\"parse_tool_calls\":true"),
                    "llama.cpp native tool parser was not explicitly enabled");
            require(requestBody.get().contains("\"parallel_tool_calls\":true"),
                    "parallel tool-call negotiation was not enabled");
            require(requestBody.get().contains("\"reasoning_format\":\"auto\""),
                    "template-aware reasoning format was not requested");
            require(requestBody.get().contains("\"enable_thinking\":true"),
                    "reasoning-capable template did not receive enable_thinking context");
            require(response.usage().promptTokens() == 12, "prompt usage was not decoded");
            require(response.usage().completionTokens() == 4, "completion usage was not decoded");
            require(liveUsage.size() >= 2
                            && liveUsage.stream().anyMatch(usage -> usage.tokensPerSecond() > 0.0D),
                    "llama.cpp stream did not publish live request-local throughput updates");
            List<ModelMessage> templateMessages = LlamaCppLocalModelProvider.templateMessages(List.of(
                    ModelMessage.assistant("trimmed earlier user turn"),
                    ModelMessage.user("cancelled request"),
                    ModelMessage.user("new request"),
                    ModelMessage.assistant("completed answer"),
                    ModelMessage.user("continue"),
                    ModelMessage.assistant("non-final draft one"),
                    ModelMessage.assistant("non-final draft two")
            ));
            require(templateMessages.size() == 3
                            && templateMessages.get(0).role() == com.spirit.koil.api.model.ModelRole.USER
                            && templateMessages.get(1).role() == com.spirit.koil.api.model.ModelRole.ASSISTANT
                            && templateMessages.get(2).role() == com.spirit.koil.api.model.ModelRole.USER
                            && "new request".equals(templateMessages.get(0).content())
                            && "completed answer".equals(templateMessages.get(1).content())
                            && "continue".equals(templateMessages.get(2).content()),
                    "chat-template projection retained an interrupted turn or trailing assistant draft");

            ModelToolCall projectionCall = new ModelToolCall("projection-call", "minecraft.command", new JsonObject());
            ModelToolResult projectionResult = new ModelToolResult(
                    projectionCall.id(), projectionCall.toolId(), "completed", new JsonObject(), "", "ok");
            List<ModelMessage> toolContinuation = LlamaCppLocalModelProvider.templateMessages(List.of(
                    ModelMessage.user("inspect current state"),
                    ModelMessage.assistantToolCall("", projectionCall),
                    ModelMessage.toolResult(projectionResult),
                    ModelMessage.assistant("non-final answer that must not prefill another assistant")
            ));
            require(toolContinuation.size() == 3
                            && toolContinuation.get(0).role() == com.spirit.koil.api.model.ModelRole.USER
                            && !toolContinuation.get(1).toolCallId().isBlank()
                            && toolContinuation.get(2).role() == com.spirit.koil.api.model.ModelRole.TOOL,
                    "active tool turn was not projected into strict-template-safe user/tool-call/tool-result form");
            LlamaCppToolNameMap collisionProof = new LlamaCppToolNameMap();
            String dotted = collisionProof.toWire("example.tool");
            String underscored = collisionProof.toWire("example_tool");
            require(!dotted.equals(underscored), "sanitized tool-name collision was not resolved");
            require("example.tool".equals(collisionProof.toCanonical(dotted)), "dotted collision mapping was not reversible");
            require("example_tool".equals(collisionProof.toCanonical(underscored)),
                    "underscore collision mapping was not reversible");
            System.out.println("llama.cpp provider proof passed.");
        } finally {
            provider.stop().get(5L, TimeUnit.SECONDS);
            server.stop(0);
        }
    }

    private static JsonObject schema() {
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonObject command = new JsonObject();
        command.addProperty("type", "string");
        command.addProperty("minLength", 1);
        command.addProperty("maxLength", 2048);
        properties.add("command", command);
        root.add("properties", properties);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("command");
        root.add("required", required);
        return root;
    }

    private static void proveInlineThinkPartitioning() {
        StringBuilder visible = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ModelRuntimeTelemetry> telemetry = new java.util.ArrayList<>();
        List<ModelExposedData> exposed = new java.util.ArrayList<>();
        UUID id = UUID.randomUUID();
        OpenAiChatStreamDecoder decoder = new OpenAiChatStreamDecoder(id, new StreamingModelObserver() {
            @Override
            public void onTextDelta(UUID requestId, String delta) {
                visible.append(delta);
            }

            @Override
            public void onReasoningDelta(UUID requestId, String delta) {
                reasoning.append(delta);
            }

            @Override
            public void onExposedData(UUID requestId, ModelExposedData value) {
                exposed.add(value);
                StreamingModelObserver.super.onExposedData(requestId, value);
            }

            @Override
            public void onTelemetry(UUID requestId, ModelRuntimeTelemetry value) {
                telemetry.add(value);
            }
        });
        decoder.accept("{\"prompt_progress\":{\"total\":20,\"cache\":8,\"processed\":12,\"time_ms\":14.5},\"choices\":[]}");
        decoder.accept("{\"timings\":{\"cache_n\":8,\"prompt_n\":12,\"prompt_ms\":14.5,\"prompt_per_second\":827.5,\"predicted_n\":1,\"predicted_ms\":7.0,\"predicted_per_second\":142.8},\"tokens\":[42],\"choices\":[]}");
        decoder.accept("{\"choices\":[{\"delta\":{\"content\":\"<thi\"}}]}");
        decoder.accept("{\"choices\":[{\"delta\":{\"content\":\"nk>private work</th\"}}]}");
        decoder.accept("{\"tokens\":[43],\"choices\":[{\"delta\":{\"content\":\"ink>Visible answer\"}}]}");
        decoder.accept("{\"choices\":[{\"delta\":{\"reasoning_content\":\"native reason\"}}]}");
        decoder.accept("{\"choices\":[{\"delta\":{\"analysis\":\"native analysis\"}}]}");
        decoder.finishTools();
        require("private work".equals(reasoning.toString()), "inline think body leaked out of llama reasoning channel");
        require("Visible answer".equals(visible.toString()), "llama visible answer after </think> was lost");
        require("Visible answer".equals(decoder.text()), "llama decoder retained think markup in final text");
        require(decoder.reasoningText().contains("private work"), "llama decoder did not retain isolated reasoning");
        require(exposed.stream().anyMatch(value -> value.kind() == ModelExposedData.Kind.THOUGHT
                        && "xml_think".equals(value.nativeChannel())),
                "llama inline <think> channel was not tagged as THOUGHT");
        require(exposed.stream().anyMatch(value -> value.kind() == ModelExposedData.Kind.REASONING
                        && "reasoning_content".equals(value.nativeChannel())),
                "llama reasoning_content was not tagged as REASONING");
        require(exposed.stream().anyMatch(value -> value.kind() == ModelExposedData.Kind.ANALYSIS
                        && "analysis".equals(value.nativeChannel())),
                "llama analysis channel was not tagged as ANALYSIS");
        require(telemetry.stream().anyMatch(value -> "prompt_progress".equals(value.kind())),
                "llama prompt-progress telemetry was not surfaced");
        require(telemetry.stream().anyMatch(value -> "inference_timings".equals(value.kind())),
                "llama timing telemetry was not surfaced");
        require(telemetry.stream().anyMatch(value -> "token_ids".equals(value.kind())),
                "llama token-id telemetry was not surfaced");
        require(telemetry.stream().anyMatch(value -> "token_piece".equals(value.kind())
                        && "43".equals(value.fields().get("tokenId"))
                        && "content".equals(value.fields().get("channel"))),
                "llama sampled token id was not correlated with its emitted text piece");
        ModelUsage telemetryUsage = decoder.usage(0L, 0.0D);
        require(telemetryUsage.promptTokens() == 20 && telemetryUsage.reusedPrefixTokens() == 8,
                "llama prompt telemetry conflated cached and evaluated prompt tokens");
    }

    private static void stream(
            HttpExchange exchange,
            String key,
            AtomicReference<String> requestBody
    ) throws java.io.IOException {
        if (!authorized(exchange, key)) {
            exchange.sendResponseHeaders(401, -1L);
            exchange.close();
            return;
        }
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String body = """
                data: {"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"checking"},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"small model "},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"content":"ready","tool_calls":[{"index":0,"id":"tool-proof","type":"function","function":{"name":"minecraft_","arguments":"{\\"command\\":\\"/time "}}]},"finish_reason":null}]}

                data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"name":"command","arguments":"set day\\",}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":12,"completion_tokens":4,"total_tokens":16,"prompt_tokens_details":{"cached_tokens":3}}}

                data: [DONE]

                """;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void json(HttpExchange exchange, String key, String body) throws java.io.IOException {
        if (!authorized(exchange, key)) {
            exchange.sendResponseHeaders(401, -1L);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static boolean authorized(HttpExchange exchange, String key) {
        return ("Bearer " + key).equals(exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
