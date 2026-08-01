package com.spirit.koil.api.model.provider.colibri;

import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelHealthState;
import com.spirit.koil.api.model.ModelMessage;
import com.spirit.koil.api.model.ModelRequestState;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.StreamingModelObserver;
import com.spirit.koil.api.model.StreamingModelRequest;
import com.spirit.koil.api.model.StreamingModelResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ColibriProviderProof {
    private ColibriProviderProof() {
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String apiKey = "proof-secret";
        server.createContext("/health", exchange -> json(exchange, 200, "{\"status\":\"ok\"}"));
        server.createContext("/v1/models", exchange -> json(
                exchange,
                200,
                "{\"data\":[{\"id\":\"proof-model\",\"object\":\"model\"}]}"
        ));
        server.createContext("/v1/messages", exchange -> messages(exchange, apiKey));
        server.start();
        try {
            proveProvider(server.getAddress().getPort(), apiKey);
        } finally {
            server.stop(0);
        }
        System.out.println("Colibri provider proof passed.");
    }

    private static void proveProvider(int port, String apiKey) throws Exception {
        ColibriConfiguration configuration = new ColibriConfiguration(
                true,
                null,
                null,
                "proof-model",
                "127.0.0.1",
                port,
                apiKey,
                2,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                1,
                0,
                Duration.ofSeconds(1)
        );
        ColibriLocalModelProvider provider = new ColibriLocalModelProvider(configuration);
        require(provider.start().get(5L, TimeUnit.SECONDS).state() == ModelHealthState.READY, "provider did not connect");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        CountDownLatch terminal = new CountDownLatch(1);
        StringBuilder streamedText = new StringBuilder();
        List<ModelRequestState> states = new ArrayList<>();
        List<ModelToolCall> calls = new ArrayList<>();
        AtomicReference<StreamingModelResponse> response = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        StreamingModelRequest request = new StreamingModelRequest(
                UUID.randomUUID(),
                "proof",
                "Use compact Rich Chat output.",
                List.of(ModelMessage.user("Walk forward 50 blocks")),
                List.of(new ModelToolDefinition(
                        "movement.walk_relative",
                        "Walk relative to the planning-time view direction.",
                        schema,
                        List.of("player available"),
                        Set.of("movement"),
                        true,
                        Duration.ofSeconds(30),
                        true,
                        false,
                        Set.of("completed", "failed", "cancelled")
                )),
                128,
                Duration.ofSeconds(5),
                Map.of("cache_slot", "0")
        );
        provider.generate(request, new StreamingModelObserver() {
            @Override
            public synchronized void onState(UUID requestId, ModelRequestState state, String detail) {
                states.add(state);
            }

            @Override
            public synchronized void onTextDelta(UUID requestId, String delta) {
                streamedText.append(delta);
            }

            @Override
            public synchronized void onToolCall(UUID requestId, ModelToolCall call) {
                calls.add(call);
            }

            @Override
            public void onComplete(StreamingModelResponse completed) {
                response.set(completed);
                terminal.countDown();
            }

            @Override
            public void onFailure(UUID requestId, String code, String detail, Throwable cause) {
                failure.set(code + ": " + detail);
                terminal.countDown();
            }
        });
        require(terminal.await(5L, TimeUnit.SECONDS), "stream did not finish");
        require(failure.get() == null, "stream failed: " + failure.get());
        require(response.get() != null, "final response was missing");
        require("Walking now.".contentEquals(streamedText), "text deltas were decoded incorrectly");
        require(calls.size() == 1, "tool call was not decoded");
        require("movement.walk_relative".equals(calls.get(0).toolId()), "tool name was incorrect");
        require(calls.get(0).arguments().get("distance").getAsInt() == 50, "tool arguments were incorrect");
        require(response.get().usage().promptTokens() == 12, "prompt usage was not decoded");
        require(response.get().usage().completionTokens() == 4, "completion usage was not decoded");
        require(response.get().usage().queueMillis() == 17L, "queue wait header was not decoded");
        require(states.contains(ModelRequestState.PREFILLING), "prefill state was not emitted");
        require(states.contains(ModelRequestState.GENERATING), "generating state was not emitted");
        require(states.contains(ModelRequestState.SELECTING_TOOL), "tool-selection state was not emitted");
        require(states.contains(ModelRequestState.COMPLETED), "completed state was not emitted");
        proveToolResultContinuation(provider, request.tools(), calls.get(0));
        provider.stop().get(5L, TimeUnit.SECONDS);
    }

    private static void proveToolResultContinuation(
            ColibriLocalModelProvider provider,
            List<ModelToolDefinition> tools,
            ModelToolCall call
    ) throws Exception {
        JsonObject output = new JsonObject();
        output.addProperty("actualDistance", 49.84D);
        ModelToolResult result = new ModelToolResult(
                call.id(),
                call.toolId(),
                "completed",
                output,
                "",
                "automation execution completed"
        );
        StreamingModelRequest continuation = new StreamingModelRequest(
                UUID.randomUUID(),
                "proof",
                "Use the structured tool result before answering.",
                List.of(
                        ModelMessage.user("Walk forward 50 blocks"),
                        ModelMessage.assistantToolCall("I will verify that movement.", call),
                        ModelMessage.toolResult(result)
                ),
                tools,
                128,
                Duration.ofSeconds(5),
                Map.of("cache_slot", "0")
        );
        CountDownLatch terminal = new CountDownLatch(1);
        StringBuilder text = new StringBuilder();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<StreamingModelResponse> response = new AtomicReference<>();
        provider.generate(continuation, new StreamingModelObserver() {
            @Override
            public void onTextDelta(UUID requestId, String delta) {
                text.append(delta);
            }

            @Override
            public void onComplete(StreamingModelResponse completed) {
                response.set(completed);
                terminal.countDown();
            }

            @Override
            public void onFailure(UUID requestId, String code, String detail, Throwable cause) {
                failure.set(code + ": " + detail);
                terminal.countDown();
            }
        });
        require(terminal.await(5L, TimeUnit.SECONDS), "tool-result continuation did not finish");
        require(failure.get() == null, "tool-result continuation failed: " + failure.get());
        require(response.get() != null, "tool-result continuation response was missing");
        require("Movement verified.".contentEquals(text), "tool-result continuation text was incorrect");
        require(response.get().toolCalls().isEmpty(), "continuation unexpectedly requested another tool");
    }

    private static void messages(HttpExchange exchange, String apiKey) throws IOException {
        if (!apiKey.equals(exchange.getRequestHeaders().getFirst("x-api-key"))) {
            json(exchange, 401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"bad key\"}}");
            return;
        }
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!request.contains("\"movement.walk_relative\"") || !request.contains("\"stream\":true")) {
            json(exchange, 400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request\",\"message\":\"tool schema missing\"}}");
            return;
        }
        if (request.contains("\"tool_result\"")) {
            if (!request.contains("\"tool_use\"")
                    || !request.contains("\"tool_use_id\":\"tool-1\"")
                    || !request.contains("actualDistance")
                    || !request.contains("49.84")) {
                json(exchange, 400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request\",\"message\":\"tool continuation malformed\"}}");
                return;
            }
            String continuation = """
                    event: message_start
                    data: {"type":"message_start","message":{"usage":{"input_tokens":24}}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Movement "}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"verified."}}

                    event: message_delta
                    data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """;
            sse(exchange, continuation);
            return;
        }
        String stream = """
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":12}}}

                event: ping
                data: {"type":"ping"}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Walking "}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"now."}}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tool-1","name":"movement.walk_relative","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"distance\\":"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"50,}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":4}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        sse(exchange, stream);
    }

    private static void sse(HttpExchange exchange, String stream) throws IOException {
        byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("x-colibri-queue-wait-ms", "17");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void json(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
