package com.spirit.koil.api.model.provider.colibri;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.model.ModelExposedData;
import com.spirit.koil.api.model.ModelReasoningMarkupParser;
import com.spirit.koil.api.model.ModelRuntimeTelemetry;
import com.spirit.koil.api.model.ModelToolArgumentParser;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelUsage;
import com.spirit.koil.api.model.StreamingModelObserver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ColibriStreamDecoder {
    private final UUID requestId;
    private final StreamingModelObserver observer;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final ModelReasoningMarkupParser reasoningMarkup = new ModelReasoningMarkupParser();
    private final Map<Integer, ToolAccumulator> tools = new LinkedHashMap<>();
    private final List<ModelToolCall> completedTools = new ArrayList<>();
    private final Set<String> observedUnknownDeltaTypes = new LinkedHashSet<>();
    private int promptTokens;
    private int completionTokens;
    private int streamedOutputUnits;
    private int streamedReasoningUnits;
    private int streamedTextUnits;
    private boolean stopped;
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
            case "message_stop" -> this.stopped = true;
            case "ping" -> {
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
        this.observer.onTelemetry(this.requestId, ModelRuntimeTelemetry.of(
                "provider_usage",
                "Colibri input · " + Math.max(0, this.promptTokens) + " tokens",
                Map.of(
                        "promptTokens", Math.max(0, this.promptTokens),
                        "provider", "colibri"
                )
        ));
    }

    private static ModelExposedData.Kind semanticKind(String raw) {
        String value = raw == null ? "" : raw.strip().toLowerCase(java.util.Locale.ROOT)
                .replace('-', '_');
        if (value.endsWith("_delta")) value = value.substring(0, value.length() - 6);
        if (value.endsWith("_block")) value = value.substring(0, value.length() - 6);
        return switch (value) {
            case "think", "thinking", "thought", "thoughts", "thinking_content", "thought_content" -> ModelExposedData.Kind.THOUGHT;
            case "reason", "reasoning", "reasoning_content", "reasoning_text", "rationale", "deliberation" -> ModelExposedData.Kind.REASONING;
            case "analysis", "analysis_content", "analysis_text" -> ModelExposedData.Kind.ANALYSIS;
            case "scratchpad", "scratch_pad", "internal_notes", "working_notes" -> ModelExposedData.Kind.SCRATCHPAD;
            case "plan", "planning", "plan_content" -> ModelExposedData.Kind.PLAN;
            case "reflection", "self_reflection" -> ModelExposedData.Kind.REFLECTION;
            case "critique", "self_critique" -> ModelExposedData.Kind.CRITIQUE;
            case "commentary" -> ModelExposedData.Kind.COMMENTARY;
            case "reasoning_summary", "thinking_summary" -> ModelExposedData.Kind.REASONING_SUMMARY;
            case "confidence" -> ModelExposedData.Kind.CONFIDENCE;
            case "refusal" -> ModelExposedData.Kind.REFUSAL;
            default -> null;
        };
    }

    private static String exposedValue(JsonObject root) {
        if (root == null || root.entrySet().isEmpty()) return "";
        return firstString(root,
                "thinking", "thought", "reasoning", "rationale", "deliberation", "analysis",
                "scratchpad", "internal_notes", "plan", "planning", "reflection", "critique",
                "commentary", "summary", "confidence", "refusal", "content", "text");
    }

    private void readBlockStart(JsonObject root) {
        int index = integer(root, "index", -1);
        JsonObject block = object(root, "content_block");
        String blockType = string(block, "type", "");
        if ("redacted_thinking".equals(blockType) || "redacted_reasoning".equals(blockType)) {
            this.observer.onTelemetry(this.requestId, ModelRuntimeTelemetry.of(
                    "redacted_reasoning",
                    "Provider exposed a redacted reasoning block",
                    Map.of("provider", "colibri", "blockType", blockType)
            ));
            return;
        }
        ModelExposedData.Kind blockKind = semanticKind(blockType);
        if (blockKind != null) {
            String value = exposedValue(block);
            if (!value.isEmpty()) emitExposed(blockKind, value, blockType + "_block");
            return;
        }
        if (index < 0 || !"tool_use".equals(blockType)) {
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
                int beforeText = this.text.length();
                int beforeReasoning = this.reasoning.length();
                this.reasoningMarkup.acceptTyped(
                        value,
                        this::emitVisible,
                        chunk -> emitExposed(chunk.kind(), chunk.text(), chunk.nativeChannel())
                );
                if (this.text.length() > beforeText || this.reasoning.length() > beforeReasoning) {
                    this.streamedOutputUnits++;
                }
            }
            return;
        }
        ModelExposedData.Kind exposedKind = semanticKind(deltaType);
        if (exposedKind != null) {
            String value = exposedValue(delta);
            if (!value.isEmpty()) {
                emitExposed(exposedKind, value, deltaType);
                this.streamedOutputUnits++;
            }
            return;
        }
        if ("signature_delta".equals(deltaType)) {
            String signature = firstString(delta, "signature", "text");
            this.observer.onTelemetry(this.requestId, ModelRuntimeTelemetry.of(
                    "reasoning_signature",
                    "Provider reasoning signature observed",
                    signature.isBlank()
                            ? Map.of("provider", "colibri")
                            : Map.of("provider", "colibri", "signatureCharacters", signature.length())
            ));
            return;
        }
        if (!deltaType.isBlank() && this.observedUnknownDeltaTypes.add(deltaType)
                && !"input_json_delta".equals(deltaType)) {
            this.observer.onTelemetry(this.requestId, ModelRuntimeTelemetry.of(
                    "unclassified_model_channel",
                    "Unclassified Colibri delta type observed · " + deltaType,
                    Map.of(
                            "deltaType", deltaType,
                            "provider", "colibri",
                            "note", "Type name recorded for family discovery; payload was not reclassified as thought or answer."
                    )
            ));
        }
        if ("input_json_delta".equals(deltaType)) {
            ToolAccumulator accumulator = this.tools.get(index);
            if (accumulator == null) {
                accumulator = new ToolAccumulator("", "");
                this.tools.put(index, accumulator);
            }
            accumulator.json.append(string(delta, "partial_json", ""));
            this.streamedOutputUnits++;
        }
    }


    private void emitVisible(String value) {
        if (value == null || value.isEmpty()) return;
        this.text.append(value);
        this.observer.onTextDelta(this.requestId, value);
        this.streamedTextUnits++;
    }

    private void emitReasoning(String value) {
        emitExposed(ModelExposedData.Kind.REASONING, value, "legacy_reasoning");
    }

    private void emitExposed(ModelExposedData.Kind kind, String value, String nativeChannel) {
        if (value == null || value.isEmpty()) return;
        this.reasoning.append(value);
        this.observer.onExposedData(
                this.requestId,
                ModelExposedData.of(kind, value, nativeChannel, "colibri")
        );
        this.streamedReasoningUnits++;
    }

    private void readMessageDelta(JsonObject root) {
        JsonObject delta = object(root, "delta");
        this.finishReason = string(delta, "stop_reason", this.finishReason);
        JsonObject usage = object(root, "usage");
        this.completionTokens = integer(usage, "output_tokens", this.completionTokens);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("completionTokens", Math.max(0, this.completionTokens));
        fields.put("provider", "colibri");
        if (!this.finishReason.isBlank()) fields.put("finishReason", this.finishReason);
        this.observer.onTelemetry(this.requestId, ModelRuntimeTelemetry.of(
                "provider_usage",
                this.finishReason.isBlank()
                        ? "Colibri output · " + Math.max(0, this.completionTokens) + " tokens"
                        : "Colibri output · " + Math.max(0, this.completionTokens) + " tokens · " + this.finishReason,
                fields
        ));
    }

    private void finishTool(int index) {
        ToolAccumulator accumulator = this.tools.remove(index);
        if (accumulator == null) {
            return;
        }
        JsonObject arguments = new JsonObject();
        if (!accumulator.json.isEmpty()) {
            try {
                arguments = ModelToolArgumentParser.parseObject(accumulator.json.toString());
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
        this.reasoningMarkup.finishTyped(
                this::emitVisible,
                chunk -> emitExposed(chunk.kind(), chunk.text(), chunk.nativeChannel())
        );
        if (!this.stopped) {
            throw new ProtocolException("incomplete_stream", "Colibri stream ended without message_stop", null);
        }
        for (Integer index : List.copyOf(this.tools.keySet())) {
            finishTool(index);
        }
    }

    String text() {
        return this.text.toString();
    }

    String reasoningText() {
        return this.reasoning.toString();
    }

    int streamedOutputUnits() {
        return this.streamedOutputUnits;
    }

    int streamedReasoningUnits() {
        return this.streamedReasoningUnits;
    }

    int streamedTextUnits() {
        return this.streamedTextUnits;
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

    int liveCompletionTokens() {
        return Math.max(this.completionTokens, this.streamedOutputUnits);
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

    private static String firstString(JsonObject root, String... keys) {
        if (keys == null) return "";
        for (String key : keys) {
            String value = string(root, key, "");
            if (!value.isEmpty()) return value;
        }
        return "";
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
