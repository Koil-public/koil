package com.spirit.koil.api.model.provider.llamacpp;

import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelHealthState;
import com.spirit.koil.api.model.ModelMessage;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
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
        String key = "proof-local-key";
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/health", exchange -> json(exchange, key, "{\"status\":\"ok\"}"));
        server.createContext("/v1/models", exchange -> json(
                exchange,
                key,
                "{\"object\":\"list\",\"data\":[{\"id\":\"proof-model\",\"object\":\"model\"}]}"
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
                Duration.ofSeconds(5)
        );
        OkHttpClient http = new OkHttpClient.Builder().readTimeout(0L, TimeUnit.MILLISECONDS).build();
        LlamaCppLocalModelProvider provider = new LlamaCppLocalModelProvider(configuration, http);
        try {
            require(provider.start().get(5L, TimeUnit.SECONDS).state() == ModelHealthState.READY,
                    "provider did not connect to compatible local runtime");
            CompletableFuture<StreamingModelResponse> completed = new CompletableFuture<>();
            StringBuilder streamed = new StringBuilder();
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
            require(response.usage().promptTokens() == 12, "prompt usage was not decoded");
            require(response.usage().completionTokens() == 4, "completion usage was not decoded");
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
