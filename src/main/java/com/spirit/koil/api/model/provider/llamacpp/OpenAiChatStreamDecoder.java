package com.spirit.koil.api.model.provider.llamacpp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.model.ModelToolArgumentParser;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelUsage;
import com.spirit.koil.api.model.StreamingModelObserver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

final class OpenAiChatStreamDecoder {
    private final UUID requestId;
    private final StreamingModelObserver observer;
    private final Function<String, String> canonicalToolName;
    private final StringBuilder text = new StringBuilder();
    private final Map<Integer, ToolAccumulator> tools = new LinkedHashMap<>();
    private final List<ModelToolCall> completedTools = new ArrayList<>();
    private int promptTokens;
    private int completionTokens;
    private int cachedTokens;
    private String finishReason = "";

    OpenAiChatStreamDecoder(UUID requestId, StreamingModelObserver observer) {
        this(requestId, observer, Function.identity());
    }

    OpenAiChatStreamDecoder(
            UUID requestId,
            StreamingModelObserver observer,
            Function<String, String> canonicalToolName
    ) {
        this.requestId = requestId;
        this.observer = observer;
        this.canonicalToolName = canonicalToolName == null ? Function.identity() : canonicalToolName;
    }

    void accept(String data) {
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
            return;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(data);
        } catch (Exception exception) {
            throw new ProtocolException("malformed_stream_event", "Malformed llama.cpp streaming JSON.", exception);
        }
        if (!parsed.isJsonObject()) {
            throw new ProtocolException("malformed_stream_event", "llama.cpp stream event was not an object.", null);
        }
        JsonObject root = parsed.getAsJsonObject();
        if (root.has("error")) {
            JsonObject error = object(root, "error");
            throw new ProtocolException(
                    string(error, "type", "provider_error"),
                    string(error, "message", "llama.cpp returned an error."),
                    null
            );
        }
        readUsage(object(root, "usage"));
        JsonArray choices = array(root, "choices");
        for (JsonElement choiceElement : choices) {
            if (!choiceElement.isJsonObject()) {
                continue;
            }
            JsonObject choice = choiceElement.getAsJsonObject();
            String reason = string(choice, "finish_reason", "");
            if (!reason.isBlank()) {
                this.finishReason = reason;
            }
            JsonObject delta = object(choice, "delta");
            String content = string(delta, "content", "");
            if (!content.isEmpty()) {
                this.text.append(content);
                this.observer.onTextDelta(this.requestId, content);
            }
            readToolDeltas(array(delta, "tool_calls"));
        }
    }

    private void readToolDeltas(JsonArray toolCalls) {
        for (JsonElement element : toolCalls) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject call = element.getAsJsonObject();
            int index = integer(call, "index", this.tools.size());
            ToolAccumulator accumulator = this.tools.computeIfAbsent(index, ignored -> new ToolAccumulator());
            String id = string(call, "id", "");
            if (!id.isBlank()) {
                accumulator.id = id;
            }
            JsonObject function = object(call, "function");
            String name = string(function, "name", "");
            if (!name.isBlank()) {
                accumulator.name.append(name);
            }
            String arguments = string(function, "arguments", "");
            if (!arguments.isEmpty()) {
                accumulator.arguments.append(arguments);
            }
        }
    }

    private void readUsage(JsonObject usage) {
        this.promptTokens = integer(usage, "prompt_tokens", this.promptTokens);
        this.completionTokens = integer(usage, "completion_tokens", this.completionTokens);
        JsonObject details = object(usage, "prompt_tokens_details");
        this.cachedTokens = integer(details, "cached_tokens", this.cachedTokens);
    }

    void finishTools() {
        for (ToolAccumulator accumulator : this.tools.values()) {
            JsonObject arguments = new JsonObject();
            if (!accumulator.arguments.isEmpty()) {
                try {
                    arguments = ModelToolArgumentParser.parseObject(accumulator.arguments.toString());
                } catch (ProtocolException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new ProtocolException("invalid_tool_arguments", "Tool arguments were incomplete JSON.", exception);
                }
            }
            ModelToolCall call = new ModelToolCall(
                    accumulator.id.isBlank() ? UUID.randomUUID().toString() : accumulator.id,
                    this.canonicalToolName.apply(accumulator.name.toString()),
                    arguments
            );
            this.completedTools.add(call);
            this.observer.onToolCall(this.requestId, call);
        }
        this.tools.clear();
    }

    String text() {
        return this.text.toString();
    }

    List<ModelToolCall> toolCalls() {
        return List.copyOf(this.completedTools);
    }

    ModelUsage usage(long timeToFirstTokenMillis, double tokensPerSecond) {
        return new ModelUsage(
                this.promptTokens,
                this.completionTokens,
                this.cachedTokens,
                0L,
                timeToFirstTokenMillis,
                tokensPerSecond
        );
    }

    String finishReason() {
        return this.finishReason;
    }

    private static JsonObject object(JsonObject root, String key) {
        return root != null && root.has(key) && root.get(key).isJsonObject()
                ? root.getAsJsonObject(key)
                : new JsonObject();
    }

    private static JsonArray array(JsonObject root, String key) {
        return root != null && root.has(key) && root.get(key).isJsonArray()
                ? root.getAsJsonArray(key)
                : new JsonArray();
    }

    private static String string(JsonObject root, String key, String fallback) {
        try {
            return root != null && root.has(key) && !root.get(key).isJsonNull()
                    ? root.get(key).getAsString()
                    : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int integer(JsonObject root, String key, int fallback) {
        try {
            return root != null && root.has(key) ? root.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static final class ToolAccumulator {
        private String id = "";
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }

    static final class ProtocolException extends RuntimeException {
        private final String code;

        ProtocolException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        String code() {
            return this.code;
        }
    }
}
