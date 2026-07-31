package com.spirit.koil.api.model.provider.colibri;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelUsage;
import com.spirit.koil.api.model.StreamingModelObserver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ColibriStreamDecoder {
    private final UUID requestId;
    private final StreamingModelObserver observer;
    private final StringBuilder text = new StringBuilder();
    private final Map<Integer, ToolAccumulator> tools = new LinkedHashMap<>();
    private final List<ModelToolCall> completedTools = new ArrayList<>();
    private int promptTokens;
    private int completionTokens;
    private String finishReason = "";

    ColibriStreamDecoder(UUID requestId, StreamingModelObserver observer) {
        this.requestId = requestId;
        this.observer = observer;
    }

    void accept(String eventName, String jsonData) {
        if (jsonData == null || jsonData.isBlank() || "[DONE]".equals(jsonData.trim())) {
            return;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(jsonData);
        } catch (Exception exception) {
            throw new ProtocolException("malformed_stream_event", "malformed Colibri streaming JSON", exception);
        }
        if (!parsed.isJsonObject()) {
            throw new ProtocolException("malformed_stream_event", "Colibri stream event was not an object", null);
        }
        JsonObject root = parsed.getAsJsonObject();
        String type = string(root, "type", eventName == null ? "" : eventName);
        switch (type) {
            case "message_start" -> readMessageStart(root);
            case "content_block_start" -> readBlockStart(root);
            case "content_block_delta" -> readBlockDelta(root);
            case "content_block_stop" -> finishTool(integer(root, "index", -1));
            case "message_delta" -> readMessageDelta(root);
            case "error" -> throw error(root);
            case "ping", "message_stop" -> {
            }
            default -> {
                if ("error".equalsIgnoreCase(eventName)) {
                    throw error(root);
                }
            }
        }
    }

    private void readMessageStart(JsonObject root) {
        JsonObject message = object(root, "message");
        JsonObject usage = object(message, "usage");
        this.promptTokens = integer(usage, "input_tokens", this.promptTokens);
    }

    private void readBlockStart(JsonObject root) {
        int index = integer(root, "index", -1);
        JsonObject block = object(root, "content_block");
        if (index < 0 || !"tool_use".equals(string(block, "type", ""))) {
            return;
        }
        ToolAccumulator accumulator = new ToolAccumulator(
                string(block, "id", ""),
                string(block, "name", "")
        );
        JsonElement input = block.get("input");
        if (input != null && input.isJsonObject() && input.getAsJsonObject().size() > 0) {
            accumulator.json.append(input);
        }
        this.tools.put(index, accumulator);
    }

    private void readBlockDelta(JsonObject root) {
        int index = integer(root, "index", -1);
        JsonObject delta = object(root, "delta");
        String deltaType = string(delta, "type", "");
        if ("text_delta".equals(deltaType)) {
            String value = string(delta, "text", "");
            if (!value.isEmpty()) {
                this.text.append(value);
                this.observer.onTextDelta(this.requestId, value);
            }
            return;
        }
        if ("input_json_delta".equals(deltaType)) {
            ToolAccumulator accumulator = this.tools.get(index);
            if (accumulator == null) {
                accumulator = new ToolAccumulator("", "");
                this.tools.put(index, accumulator);
            }
            accumulator.json.append(string(delta, "partial_json", ""));
        }
    }

    private void readMessageDelta(JsonObject root) {
        JsonObject delta = object(root, "delta");
        this.finishReason = string(delta, "stop_reason", this.finishReason);
        JsonObject usage = object(root, "usage");
        this.completionTokens = integer(usage, "output_tokens", this.completionTokens);
    }

    private void finishTool(int index) {
        ToolAccumulator accumulator = this.tools.remove(index);
        if (accumulator == null) {
            return;
        }
        JsonObject arguments = new JsonObject();
        if (!accumulator.json.isEmpty()) {
            try {
                JsonElement parsed = JsonParser.parseString(accumulator.json.toString());
                if (parsed.isJsonObject()) {
                    arguments = parsed.getAsJsonObject();
                } else {
                    throw new ProtocolException("invalid_tool_arguments", "tool input was not a JSON object", null);
                }
            } catch (ProtocolException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ProtocolException("invalid_tool_arguments", "tool input JSON was incomplete", exception);
            }
        }
        ModelToolCall call = new ModelToolCall(accumulator.id, accumulator.name, arguments);
        this.completedTools.add(call);
        this.observer.onToolCall(this.requestId, call);
    }

    void finishOpenBlocks() {
        for (Integer index : List.copyOf(this.tools.keySet())) {
            finishTool(index);
        }
    }

    String text() {
        return this.text.toString();
    }

    List<ModelToolCall> toolCalls() {
        return List.copyOf(this.completedTools);
    }

    ModelUsage usage(long queueMillis, long timeToFirstTokenMillis, double tokensPerSecond) {
        return new ModelUsage(
                this.promptTokens,
                this.completionTokens,
                0,
                queueMillis,
                timeToFirstTokenMillis,
                tokensPerSecond
        );
    }

    String finishReason() {
        return this.finishReason;
    }

    private static ProtocolException error(JsonObject root) {
        JsonObject error = object(root, "error");
        return new ProtocolException(
                string(error, "type", "provider_error"),
                string(error, "message", "Colibri returned an error"),
                null
        );
    }

    private static JsonObject object(JsonObject root, String key) {
        if (root == null || !root.has(key) || !root.get(key).isJsonObject()) {
            return new JsonObject();
        }
        return root.getAsJsonObject(key);
    }

    private static String string(JsonObject root, String key, String fallback) {
        try {
            return root != null && root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsString() : fallback;
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
        private final String id;
        private final String name;
        private final StringBuilder json = new StringBuilder();

        private ToolAccumulator(String id, String name) {
            this.id = id;
            this.name = name;
        }
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
