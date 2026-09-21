package com.spirit.koil.api.model.provider.llamacpp;

import com.google.gson.JsonArray;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

final class OpenAiChatStreamDecoder {
    private static final Map<String, ModelExposedData.Kind> EXPOSED_FIELD_KINDS = Map.ofEntries(
            Map.entry("reasoning_content", ModelExposedData.Kind.REASONING),
            Map.entry("reasoning", ModelExposedData.Kind.REASONING),
            Map.entry("reasoning_text", ModelExposedData.Kind.REASONING),
            Map.entry("reasoning_delta", ModelExposedData.Kind.REASONING),
            Map.entry("rationale", ModelExposedData.Kind.REASONING),
            Map.entry("rationale_content", ModelExposedData.Kind.REASONING),
            Map.entry("deliberation", ModelExposedData.Kind.REASONING),
            Map.entry("deliberation_content", ModelExposedData.Kind.REASONING),
            Map.entry("thinking", ModelExposedData.Kind.THOUGHT),
            Map.entry("thinking_content", ModelExposedData.Kind.THOUGHT),
            Map.entry("thinking_text", ModelExposedData.Kind.THOUGHT),
            Map.entry("thought", ModelExposedData.Kind.THOUGHT),
            Map.entry("thoughts", ModelExposedData.Kind.THOUGHT),
            Map.entry("thought_content", ModelExposedData.Kind.THOUGHT),
            Map.entry("analysis", ModelExposedData.Kind.ANALYSIS),
            Map.entry("analysis_content", ModelExposedData.Kind.ANALYSIS),
            Map.entry("analysis_text", ModelExposedData.Kind.ANALYSIS),
            Map.entry("scratchpad", ModelExposedData.Kind.SCRATCHPAD),
            Map.entry("scratch_pad", ModelExposedData.Kind.SCRATCHPAD),
            Map.entry("scratchpad_content", ModelExposedData.Kind.SCRATCHPAD),
            Map.entry("internal_notes", ModelExposedData.Kind.SCRATCHPAD),
            Map.entry("working_notes", ModelExposedData.Kind.SCRATCHPAD),
            Map.entry("plan", ModelExposedData.Kind.PLAN),
            Map.entry("planning", ModelExposedData.Kind.PLAN),
            Map.entry("plan_content", ModelExposedData.Kind.PLAN),
            Map.entry("reflection", ModelExposedData.Kind.REFLECTION),
            Map.entry("self_reflection", ModelExposedData.Kind.REFLECTION),
            Map.entry("critique", ModelExposedData.Kind.CRITIQUE),
            Map.entry("self_critique", ModelExposedData.Kind.CRITIQUE),
            Map.entry("commentary", ModelExposedData.Kind.COMMENTARY),
            Map.entry("reasoning_summary", ModelExposedData.Kind.REASONING_SUMMARY),
            Map.entry("reasoning_summary_text", ModelExposedData.Kind.REASONING_SUMMARY),
            Map.entry("thinking_summary", ModelExposedData.Kind.REASONING_SUMMARY),
            Map.entry("confidence", ModelExposedData.Kind.CONFIDENCE),
            Map.entry("confidence_text", ModelExposedData.Kind.CONFIDENCE),
            Map.entry("refusal", ModelExposedData.Kind.REFUSAL)
    );
    private final UUID requestId;
    private final StreamingModelObserver observer;
    private final Function<String, String> canonicalToolName;
    private final LlamaCppTextToolCallBridge textToolBridge;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final ModelReasoningMarkupParser reasoningMarkup = new ModelReasoningMarkupParser();
    private final Map<Integer, ToolAccumulator> tools = new LinkedHashMap<>();
    private final List<ModelToolCall> completedTools = new ArrayList<>();
    private int promptTokens;
    private int evaluatedPromptTokens;
    private int completionTokens;
    private int cachedTokens;
    private int streamedOutputUnits;
    private int streamedReasoningUnits;
    private int streamedTextUnits;
    private double promptMillis;
    private double promptTokensPerSecond;
    private double predictedMillis;
    private double reportedTokensPerSecond;
    private int lastPromptProgressProcessed = -1;
    private int lastTimingPredictedTokens = -1;
    private final Deque<Integer> recentTokenIds = new ArrayDeque<>();
    private final Set<String> observedUnknownDeltaFields = new LinkedHashSet<>();
    private boolean streamIdentityReported;
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
        this.textToolBridge = new LlamaCppTextToolCallBridge(this.canonicalToolName);
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
        readPromptProgress(object(root, "prompt_progress"));
        readTimings(object(root, "timings"));
        List<Integer> eventTokenIds = readRawTokens(array(root, "tokens"));
        boolean tokenObservationEmitted = false;
        readStreamIdentity(root);
        JsonArray choices = array(root, "choices");
        for (JsonElement choiceElement : choices) {
            if (!choiceElement.isJsonObject()) {
                continue;
            }
            JsonObject choice = choiceElement.getAsJsonObject();
            String reason = string(choice, "finish_reason", "");
            if (!reason.isBlank()) {
                this.finishReason = reason;
                emitTelemetry("finish", "llama.cpp finish reason · " + reason, Map.of(
                        "finishReason", reason,
                        "completionTokens", Integer.toString(this.completionTokens),
                        "reasoningCharacters", Integer.toString(this.reasoning.length()),
                        "visibleCharacters", Integer.toString(this.text.length())
                ));
            }
            JsonObject delta = object(choice, "delta");
            readDeltaShape(delta);
            String content = string(delta, "content", "");
            TokenObservation tokenObservation = tokenObservation(delta, content);
            boolean producedOutput = false;
            if (!content.isEmpty()) {
                int beforeText = this.text.length();
                int beforeReasoning = this.reasoning.length();
                int beforeTools = this.completedTools.size();
                this.textToolBridge.accept(
                        content,
                        chunk -> this.reasoningMarkup.acceptTyped(
                                chunk,
                                this::emitVisible,
                                exposed -> emitExposed(exposed.kind(), exposed.text(), exposed.nativeChannel())
                        ),
                        this::addRecoveredTextToolCall
                );
                producedOutput = this.text.length() > beforeText
                        || this.reasoning.length() > beforeReasoning
                        || this.completedTools.size() > beforeTools;
            }
            if (readExposedDeltas(delta)) {
                producedOutput = true;
            }
            readLogprobs(object(choice, "logprobs"));
            if (readToolDeltas(array(delta, "tool_calls"))) {
                producedOutput = true;
            }
            if (!tokenObservationEmitted && !eventTokenIds.isEmpty() && tokenObservation.hasPiece()) {
                emitTokenPieceTelemetry(eventTokenIds, tokenObservation);
                tokenObservationEmitted = true;
            }
            if (producedOutput) {
                this.streamedOutputUnits++;
            }
        }
        if (!eventTokenIds.isEmpty() && !tokenObservationEmitted) {
            emitRawTokenIdTelemetry(eventTokenIds);
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
                ModelExposedData.of(kind, value, nativeChannel, "llama_cpp")
        );
        this.streamedReasoningUnits++;
    }

    private boolean readExposedDeltas(JsonObject delta) {
        if (delta == null || delta.entrySet().isEmpty()) return false;
        boolean emitted = false;
        Set<String> consumedKinds = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : delta.entrySet()) {
            String field = entry.getKey();
            ModelExposedData.Kind kind = semanticKindForField(field);
            if (kind == null) continue;
            JsonElement value = entry.getValue();
            String text = exposedText(value);
            if (text.isEmpty()) continue;
            // Some providers duplicate the same logical channel under aliases in one delta.
            // Preserve distinct native fields when their text differs, but suppress exact alias duplicates.
            String duplicateKey = kind.name() + "\u0000" + text;
            if (!consumedKinds.add(duplicateKey)) continue;
            emitExposed(kind, text, field);
            emitted = true;
        }
        return emitted;
    }

    private static ModelExposedData.Kind semanticKindForField(String field) {
        if (field == null || field.isBlank()) return null;
        String normalized = field.strip().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        ModelExposedData.Kind exact = EXPOSED_FIELD_KINDS.get(normalized);
        if (exact != null) return exact;
        if (normalized.equals("reasoning_details")) return ModelExposedData.Kind.REASONING;
        if (normalized.equals("thinking_details")) return ModelExposedData.Kind.THOUGHT;
        if (normalized.equals("analysis_details")) return ModelExposedData.Kind.ANALYSIS;
        return null;
    }

    private static String exposedText(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) {
            try { return value.getAsString(); } catch (Exception ignored) { return ""; }
        }
        StringBuilder out = new StringBuilder();
        collectExposedText(value, out, 0);
        return out.toString();
    }

    private static void collectExposedText(JsonElement value, StringBuilder out, int depth) {
        if (value == null || value.isJsonNull() || depth > 5) return;
        if (value.isJsonPrimitive()) {
            String text;
            try { text = value.getAsString(); } catch (Exception ignored) { return; }
            if (!text.isBlank()) {
                if (!out.isEmpty()) out.append('\n');
                out.append(text);
            }
            return;
        }
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) collectExposedText(item, out, depth + 1);
            return;
        }
        JsonObject object = value.getAsJsonObject();
        for (String key : List.of("text", "content", "summary", "reasoning", "thinking", "analysis", "rationale", "deliberation")) {
            if (object.has(key)) collectExposedText(object.get(key), out, depth + 1);
        }
    }

    private void readDeltaShape(JsonObject delta) {
        if (delta == null || delta.entrySet().isEmpty()) return;
        Set<String> protocolFields = Set.of("role", "content", "tool_calls", "function_call", "audio",
                "reasoning_details", "thinking_details", "analysis_details");
        for (Map.Entry<String, JsonElement> entry : delta.entrySet()) {
            String field = entry.getKey();
            if (field == null || field.isBlank() || protocolFields.contains(field)
                    || semanticKindForField(field) != null
                    || !this.observedUnknownDeltaFields.add(field)) {
                continue;
            }
            JsonElement value = entry.getValue();
            String jsonType = value == null || value.isJsonNull() ? "null"
                    : value.isJsonPrimitive() ? "primitive"
                    : value.isJsonArray() ? "array" : value.isJsonObject() ? "object" : "unknown";
            emitTelemetry(
                    "unclassified_model_channel",
                    "Unclassified model delta field observed · " + field,
                    Map.of(
                            "field", field,
                            "jsonType", jsonType,
                            "note", "Field name recorded for family discovery; value was not reclassified as thought or answer."
                    )
            );
        }
    }

    private void readLogprobs(JsonObject logprobs) {
        if (logprobs == null || logprobs.entrySet().isEmpty()) return;
        JsonArray content = array(logprobs, "content");
        if (content.size() == 0) return;
        JsonElement last = content.get(content.size() - 1);
        if (!last.isJsonObject()) return;
        JsonObject token = last.getAsJsonObject();
        String tokenText = string(token, "token", "");
        double logprob = decimal(token, "logprob", Double.NaN);
        if (tokenText.isBlank() && Double.isNaN(logprob)) return;
        Map<String, String> fields = new LinkedHashMap<>();
        if (!tokenText.isBlank()) fields.put("token", tokenText);
        if (!Double.isNaN(logprob)) {
            fields.put("logprob", String.format(java.util.Locale.ROOT, "%.6f", logprob));
            double probability = Math.exp(Math.min(0.0D, logprob));
            fields.put("probability", String.format(java.util.Locale.ROOT, "%.6f", probability));
        }
        JsonArray top = array(token, "top_logprobs");
        if (top.size() > 0) fields.put("topCandidateCount", Integer.toString(top.size()));
        emitTelemetry(
                "token_confidence",
                tokenText.isBlank() ? "Token confidence available" : "Token confidence · " + tokenText,
                fields
        );
    }

    private boolean readToolDeltas(JsonArray toolCalls) {
        boolean producedOutput = false;
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
                producedOutput = true;
            }
            JsonObject function = object(call, "function");
            String name = string(function, "name", "");
            if (!name.isBlank()) {
                accumulator.name.append(name);
                producedOutput = true;
            }
            String arguments = string(function, "arguments", "");
            if (!arguments.isEmpty()) {
                accumulator.arguments.append(arguments);
                producedOutput = true;
            }
        }
        return producedOutput;
    }

    private void readUsage(JsonObject usage) {
        if (usage == null || usage.entrySet().isEmpty()) return;
        this.promptTokens = Math.max(this.promptTokens, integer(usage, "prompt_tokens", this.promptTokens));
        this.completionTokens = Math.max(this.completionTokens, integer(usage, "completion_tokens", this.completionTokens));
        JsonObject details = object(usage, "prompt_tokens_details");
        this.cachedTokens = Math.max(this.cachedTokens, integer(details, "cached_tokens", this.cachedTokens));
    }

    private void readPromptProgress(JsonObject progress) {
        if (progress == null || progress.entrySet().isEmpty()) return;
        int total = Math.max(0, integer(progress, "total", this.promptTokens));
        int cache = Math.max(0, integer(progress, "cache", this.cachedTokens));
        int processed = Math.max(0, integer(progress, "processed", 0));
        double timeMs = Math.max(0.0D, decimal(progress, "time_ms", 0.0D));
        this.promptTokens = Math.max(this.promptTokens, total);
        this.cachedTokens = Math.max(this.cachedTokens, cache);
        this.evaluatedPromptTokens = Math.max(this.evaluatedPromptTokens, Math.max(0, processed - cache));
        if (processed == this.lastPromptProgressProcessed) return;
        this.lastPromptProgressProcessed = processed;
        double percent = total <= 0 ? 0.0D : Math.min(100.0D, processed * 100.0D / total);
        emitTelemetry(
                "prompt_progress",
                String.format(java.util.Locale.ROOT,
                        "Prompt processing · %d/%d tokens · %.1f%% · cache %d",
                        processed, total, percent, cache),
                Map.of(
                        "promptTotalTokens", Integer.toString(total),
                        "promptProcessedTokens", Integer.toString(processed),
                        "promptCachedTokens", Integer.toString(cache),
                        "promptEvaluatedTokens", Integer.toString(Math.max(0, processed - cache)),
                        "promptProgressPercent", String.format(java.util.Locale.ROOT, "%.2f", percent),
                        "promptElapsedMillis", String.format(java.util.Locale.ROOT, "%.3f", timeMs)
                )
        );
    }

    private void readTimings(JsonObject timings) {
        if (timings == null || timings.entrySet().isEmpty()) return;
        int promptN = Math.max(0, integer(timings, "prompt_n", this.evaluatedPromptTokens));
        int cacheN = Math.max(0, integer(timings, "cache_n", this.cachedTokens));
        int predictedN = Math.max(0, integer(timings, "predicted_n", this.completionTokens));
        this.evaluatedPromptTokens = Math.max(this.evaluatedPromptTokens, promptN);
        this.cachedTokens = Math.max(this.cachedTokens, cacheN);
        if (this.promptTokens <= 0) {
            this.promptTokens = promptN + cacheN;
        }
        this.completionTokens = Math.max(this.completionTokens, predictedN);
        this.promptMillis = Math.max(this.promptMillis, decimal(timings, "prompt_ms", this.promptMillis));
        this.promptTokensPerSecond = decimal(timings, "prompt_per_second", this.promptTokensPerSecond);
        this.predictedMillis = Math.max(this.predictedMillis, decimal(timings, "predicted_ms", this.predictedMillis));
        this.reportedTokensPerSecond = decimal(timings, "predicted_per_second", this.reportedTokensPerSecond);
        if (predictedN == this.lastTimingPredictedTokens && predictedN > 0) return;
        this.lastTimingPredictedTokens = predictedN;
        int contextTokens = Math.max(0, promptN + cacheN + predictedN);
        emitTelemetry(
                "inference_timings",
                String.format(java.util.Locale.ROOT,
                        "Inference · prompt %.1f tok/s · decode %.1f tok/s · context %d tokens",
                        this.promptTokensPerSecond, this.reportedTokensPerSecond, contextTokens),
                Map.ofEntries(
                        Map.entry("promptEvaluatedTokens", Integer.toString(promptN)),
                        Map.entry("promptCachedTokens", Integer.toString(cacheN)),
                        Map.entry("completionTokens", Integer.toString(predictedN)),
                        Map.entry("contextTokensObserved", Integer.toString(contextTokens)),
                        Map.entry("promptMillis", String.format(java.util.Locale.ROOT, "%.3f", this.promptMillis)),
                        Map.entry("promptTokensPerSecond", String.format(java.util.Locale.ROOT, "%.4f", this.promptTokensPerSecond)),
                        Map.entry("decodeMillis", String.format(java.util.Locale.ROOT, "%.3f", this.predictedMillis)),
                        Map.entry("decodeTokensPerSecond", String.format(java.util.Locale.ROOT, "%.4f", this.reportedTokensPerSecond))
                )
        );
    }

    private List<Integer> readRawTokens(JsonArray tokens) {
        if (tokens == null || tokens.size() == 0) return List.of();
        List<Integer> current = new ArrayList<>();
        for (JsonElement token : tokens) {
            try {
                int id = token.getAsInt();
                current.add(id);
                this.recentTokenIds.addLast(id);
                while (this.recentTokenIds.size() > 24) this.recentTokenIds.removeFirst();
            } catch (Exception ignored) {
            }
        }
        return current.isEmpty() ? List.of() : List.copyOf(current);
    }

    private void emitTokenPieceTelemetry(List<Integer> tokenIds, TokenObservation observation) {
        if (tokenIds == null || tokenIds.isEmpty() || observation == null || !observation.hasPiece()) return;
        String piece = observation.piece();
        String printable = printableTokenPiece(piece);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("channel", observation.channel());
        fields.put("tokenCount", Integer.toString(tokenIds.size()));
        fields.put("tokenIds", tokenIds.toString());
        if (tokenIds.size() == 1) fields.put("tokenId", Integer.toString(tokenIds.get(0)));
        fields.put("piece", piece);
        fields.put("recentTokenIdTail", this.recentTokenIds.toString());
        fields.put("note", "Observable sampled decoder token/piece correlation; this does not reveal unexposed hidden reasoning or a future token before it is emitted.");
        emitTelemetry(
                "token_piece",
                tokenIds.size() == 1
                        ? "TOKEN | " + tokenIds.get(0) + " | " + observation.channel() + " | " + printable
                        : "TOKEN | " + tokenIds + " | " + observation.channel() + " | " + printable,
                fields
        );
    }

    private void emitRawTokenIdTelemetry(List<Integer> tokenIds) {
        if (tokenIds == null || tokenIds.isEmpty()) return;
        emitTelemetry(
                "token_ids",
                "TOKEN | ids " + tokenIds + " | piece unavailable",
                Map.of(
                        "reportedTokenIdsThisEvent", tokenIds.toString(),
                        "recentTokenIdTail", this.recentTokenIds.toString(),
                        "note", "The runtime exposed sampled token ids for this event but no directly correlatable text piece."
                )
        );
    }

    private static TokenObservation tokenObservation(JsonObject delta, String content) {
        if (content != null && !content.isEmpty()) {
            return new TokenObservation("content", content);
        }
        if (delta != null) {
            for (Map.Entry<String, JsonElement> entry : delta.entrySet()) {
                if (semanticKindForField(entry.getKey()) == null) continue;
                String value = exposedText(entry.getValue());
                if (!value.isEmpty()) return new TokenObservation(entry.getKey(), value);
            }
        }
        return new TokenObservation("unknown", "");
    }

    private static String printableTokenPiece(String value) {
        if (value == null || value.isEmpty()) return "<empty>";
        String printable = value
                .replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
        if (printable.length() > 96) printable = printable.substring(0, 95) + "…";
        return printable.isEmpty() ? "<empty>" : printable;
    }

    private record TokenObservation(String channel, String piece) {
        private TokenObservation {
            channel = channel == null || channel.isBlank() ? "unknown" : channel;
            piece = piece == null ? "" : piece;
        }

        private boolean hasPiece() {
            return !this.piece.isEmpty();
        }
    }

    private void readStreamIdentity(JsonObject root) {
        if (this.streamIdentityReported || root == null) return;
        String id = string(root, "id", "");
        String model = string(root, "model", "");
        String fingerprint = string(root, "system_fingerprint", "");
        if (id.isBlank() && model.isBlank() && fingerprint.isBlank()) return;
        this.streamIdentityReported = true;
        Map<String, String> fields = new LinkedHashMap<>();
        if (!id.isBlank()) fields.put("completionId", id);
        if (!model.isBlank()) fields.put("model", model);
        if (!fingerprint.isBlank()) fields.put("systemFingerprint", fingerprint);
        emitTelemetry("stream_identity", "llama.cpp stream attached", fields);
    }

    private void emitTelemetry(String kind, String summary, Map<String, String> fields) {
        this.observer.onTelemetry(this.requestId, new ModelRuntimeTelemetry(kind, summary, fields));
    }

    void finishTools() {
        this.textToolBridge.finish(
                chunk -> this.reasoningMarkup.acceptTyped(
                        chunk,
                        this::emitVisible,
                        exposed -> emitExposed(exposed.kind(), exposed.text(), exposed.nativeChannel())
                ),
                this::addRecoveredTextToolCall
        );
        this.reasoningMarkup.finishTyped(
                this::emitVisible,
                chunk -> emitExposed(chunk.kind(), chunk.text(), chunk.nativeChannel())
        );
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
            addCompletedTool(call);
        }
        this.tools.clear();
    }

    private void addRecoveredTextToolCall(ModelToolCall call) {
        if (call == null) return;
        addCompletedTool(call);
        emitTelemetry(
                "textual_tool_call_recovered",
                "Recovered native text tool call · " + call.toolId(),
                Map.of("toolId", call.toolId(), "source", "assistant_content")
        );
    }

    private void addCompletedTool(ModelToolCall call) {
        if (call == null) return;
        String signature = call.toolId() + '\u0000' + call.arguments();
        boolean duplicate = this.completedTools.stream()
                .anyMatch(existing -> (existing.toolId() + '\u0000' + existing.arguments()).equals(signature));
        if (duplicate) return;
        this.completedTools.add(call);
        this.observer.onToolCall(this.requestId, call);
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

    ModelUsage usage(long timeToFirstTokenMillis, double tokensPerSecond) {
        return new ModelUsage(
                this.promptTokens,
                this.completionTokens,
                this.cachedTokens,
                0L,
                timeToFirstTokenMillis,
                this.reportedTokensPerSecond > 0.0D
                        ? this.reportedTokensPerSecond
                        : tokensPerSecond
        );
    }

    int liveCompletionTokens() {
        return Math.max(this.completionTokens, this.streamedOutputUnits);
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


    private static String scalarString(JsonObject root, String key) {
        try {
            if (root == null || !root.has(key) || root.get(key).isJsonNull()) return "";
            JsonElement value = root.get(key);
            return value.isJsonPrimitive() ? value.getAsString() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String firstString(JsonObject root, String... keys) {
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

    private static double decimal(JsonObject root, String key, double fallback) {
        try {
            return root != null && root.has(key) ? root.get(key).getAsDouble() : fallback;
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
