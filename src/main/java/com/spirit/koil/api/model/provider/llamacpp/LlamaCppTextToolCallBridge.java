package com.spirit.koil.api.model.provider.llamacpp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.spirit.koil.api.model.ModelToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Recovers native text tool-call envelopes that some llama.cpp-compatible chat
 * templates emit in the normal content channel instead of structured
 * {@code delta.tool_calls}.
 *
 * <p>In particular, LFM-family templates may emit:</p>
 * <pre>
 * &lt;|tool_call_start|&gt;[tool_name(arg='value')]&lt;|tool_call_end|&gt;
 * </pre>
 *
 * <p>The bridge is streaming-safe: possible marker prefixes are held back from
 * the visible-text sink, a complete tool envelope is converted into structured
 * {@link ModelToolCall}s, and malformed/incomplete envelopes fail closed rather
 * than leaking protocol tokens into chat.</p>
 */
final class LlamaCppTextToolCallBridge {
    private static final String START_LFM = "<|tool_call_start|>";
    private static final String END_LFM = "<|tool_call_end|>";
    private static final String START_XML = "<tool_call>";
    private static final String END_XML = "</tool_call>";
    private static final int MAXIMUM_CAPTURE_CHARACTERS = 64 * 1024;

    private final Function<String, String> canonicalToolName;
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder capture = new StringBuilder();
    private String activeEnd = "";
    private boolean capturing;

    LlamaCppTextToolCallBridge(Function<String, String> canonicalToolName) {
        this.canonicalToolName = canonicalToolName == null ? Function.identity() : canonicalToolName;
    }

    /** @return true when this chunk produced visible output or one recovered tool call. */
    boolean accept(String chunk, Consumer<String> visible, Consumer<ModelToolCall> tool) {
        if (chunk == null || chunk.isEmpty()) return false;
        pending.append(chunk);
        boolean produced = false;
        while (true) {
            if (capturing) {
                int end = pending.indexOf(activeEnd);
                if (end < 0) {
                    capture.append(pending);
                    pending.setLength(0);
                    if (capture.length() > MAXIMUM_CAPTURE_CHARACTERS) {
                        throw new OpenAiChatStreamDecoder.ProtocolException(
                                "text_tool_call_too_large",
                                "Model-native textual tool call exceeded Koil's bounded protocol envelope.",
                                null
                        );
                    }
                    break;
                }
                capture.append(pending, 0, end);
                pending.delete(0, end + activeEnd.length());
                List<ModelToolCall> recovered = parseEnvelope(capture.toString());
                if (recovered.isEmpty()) {
                    throw new OpenAiChatStreamDecoder.ProtocolException(
                            "invalid_textual_tool_call",
                            "Model emitted a native tool-call envelope that could not be parsed safely.",
                            null
                    );
                }
                for (ModelToolCall call : recovered) tool.accept(call);
                produced = true;
                capture.setLength(0);
                activeEnd = "";
                capturing = false;
                continue;
            }

            Marker marker = earliestMarker(pending);
            if (marker != null) {
                if (marker.index > 0) {
                    String prefix = pending.substring(0, marker.index);
                    if (!prefix.isEmpty()) {
                        visible.accept(prefix);
                        produced = true;
                    }
                }
                pending.delete(0, marker.index + marker.start.length());
                capturing = true;
                activeEnd = marker.end;
                continue;
            }

            int keep = markerPrefixSuffixLength(pending);
            int emit = pending.length() - keep;
            if (emit > 0) {
                String prefix = pending.substring(0, emit);
                visible.accept(prefix);
                produced = true;
                pending.delete(0, emit);
            }
            break;
        }
        return produced;
    }

    boolean finish(Consumer<String> visible, Consumer<ModelToolCall> tool) {
        boolean produced = false;
        if (capturing) {
            int end = pending.indexOf(activeEnd);
            if (end >= 0) {
                capture.append(pending, 0, end);
                pending.delete(0, end + activeEnd.length());
                List<ModelToolCall> recovered = parseEnvelope(capture.toString());
                if (recovered.isEmpty()) {
                    throw new OpenAiChatStreamDecoder.ProtocolException(
                            "invalid_textual_tool_call",
                            "Model emitted a native tool-call envelope that could not be parsed safely.",
                            null
                    );
                }
                for (ModelToolCall call : recovered) tool.accept(call);
                produced = true;
                capture.setLength(0);
                activeEnd = "";
                capturing = false;
            } else {
                throw new OpenAiChatStreamDecoder.ProtocolException(
                        "incomplete_textual_tool_call",
                        "Model ended generation inside a native tool-call envelope.",
                        null
                );
            }
        }
        if (!pending.isEmpty()) {
            visible.accept(pending.toString());
            pending.setLength(0);
            produced = true;
        }
        return produced;
    }

    private List<ModelToolCall> parseEnvelope(String raw) {
        String source = raw == null ? "" : raw.strip();
        if (source.isBlank()) return List.of();
        if (source.startsWith("[") && source.endsWith("]")) {
            source = source.substring(1, source.length() - 1).strip();
        }
        List<String> expressions = splitTopLevel(source, ',');
        List<ModelToolCall> calls = new ArrayList<>();
        for (String expression : expressions) {
            String item = expression.strip();
            if (item.isBlank()) continue;
            int open = firstTopLevelParen(item);
            int close = matchingClosingParen(item, open);
            if (open <= 0 || close != item.length() - 1) return List.of();
            String wireName = item.substring(0, open).strip();
            if (!wireName.matches("[A-Za-z_][A-Za-z0-9_.:-]*")) return List.of();
            JsonObject arguments = parseArguments(item.substring(open + 1, close));
            calls.add(new ModelToolCall(
                    UUID.randomUUID().toString(),
                    canonicalToolName.apply(wireName),
                    arguments
            ));
        }
        return List.copyOf(calls);
    }

    private static JsonObject parseArguments(String body) {
        JsonObject output = new JsonObject();
        String source = body == null ? "" : body.strip();
        if (source.isBlank()) return output;
        for (String part : splitTopLevel(source, ',')) {
            String pair = part.strip();
            if (pair.isBlank()) continue;
            int equals = topLevelEquals(pair);
            if (equals <= 0) throw new IllegalArgumentException("Textual tool argument was not key=value.");
            String key = pair.substring(0, equals).strip();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Textual tool argument key was invalid.");
            }
            LiteralParser parser = new LiteralParser(pair.substring(equals + 1));
            JsonElement value = parser.parseValue();
            parser.requireEnd();
            output.add(key, value);
        }
        return output;
    }

    private static List<String> splitTopLevel(String source, char separator) {
        List<String> output = new ArrayList<>();
        int start = 0;
        int paren = 0, bracket = 0, brace = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quote != 0) {
                if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '(') paren++;
            else if (c == ')') paren--;
            else if (c == '[') bracket++;
            else if (c == ']') bracket--;
            else if (c == '{') brace++;
            else if (c == '}') brace--;
            else if (c == separator && paren == 0 && bracket == 0 && brace == 0) {
                output.add(source.substring(start, i));
                start = i + 1;
            }
        }
        output.add(source.substring(start));
        return output;
    }

    private static int firstTopLevelParen(String source) {
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quote != 0) {
                if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') quote = c;
            else if (c == '(') return i;
        }
        return -1;
    }

    private static int matchingClosingParen(String source, int open) {
        if (open < 0) return -1;
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quote != 0) {
                if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static int topLevelEquals(String source) {
        int paren = 0, bracket = 0, brace = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quote != 0) {
                if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '(') paren++;
            else if (c == ')') paren--;
            else if (c == '[') bracket++;
            else if (c == ']') bracket--;
            else if (c == '{') brace++;
            else if (c == '}') brace--;
            else if (c == '=' && paren == 0 && bracket == 0 && brace == 0) return i;
        }
        return -1;
    }

    private static Marker earliestMarker(StringBuilder source) {
        int lfm = source.indexOf(START_LFM);
        int xml = source.indexOf(START_XML);
        if (lfm < 0 && xml < 0) return null;
        if (lfm >= 0 && (xml < 0 || lfm <= xml)) return new Marker(lfm, START_LFM, END_LFM);
        return new Marker(xml, START_XML, END_XML);
    }

    private static int markerPrefixSuffixLength(StringBuilder source) {
        int maximum = 0;
        for (String marker : List.of(START_LFM, START_XML)) {
            int upper = Math.min(source.length(), marker.length() - 1);
            for (int length = upper; length > maximum; length--) {
                boolean matches = true;
                int offset = source.length() - length;
                for (int i = 0; i < length; i++) {
                    if (source.charAt(offset + i) != marker.charAt(i)) { matches = false; break; }
                }
                if (matches) { maximum = length; break; }
            }
        }
        return maximum;
    }

    private record Marker(int index, String start, String end) {}

    private static final class LiteralParser {
        private final String source;
        private int index;

        LiteralParser(String source) { this.source = source == null ? "" : source; }

        JsonElement parseValue() {
            whitespace();
            if (index >= source.length()) return JsonNull.INSTANCE;
            char c = source.charAt(index);
            if (c == '\'' || c == '"') return new JsonPrimitive(string());
            if (c == '[') return array();
            if (c == '{') return object();
            if (c == '-' || Character.isDigit(c)) return number();
            String word = word();
            if (word.equalsIgnoreCase("true")) return new JsonPrimitive(true);
            if (word.equalsIgnoreCase("false")) return new JsonPrimitive(false);
            if (word.equalsIgnoreCase("none") || word.equalsIgnoreCase("null")) return JsonNull.INSTANCE;
            return new JsonPrimitive(word);
        }

        void requireEnd() {
            whitespace();
            if (index != source.length()) throw new IllegalArgumentException("Trailing textual tool argument data.");
        }

        private JsonArray array() {
            JsonArray result = new JsonArray();
            index++;
            whitespace();
            if (peek(']')) { index++; return result; }
            while (true) {
                result.add(parseValue());
                whitespace();
                if (peek(']')) { index++; return result; }
                require(',');
            }
        }

        private JsonObject object() {
            JsonObject result = new JsonObject();
            index++;
            whitespace();
            if (peek('}')) { index++; return result; }
            while (true) {
                whitespace();
                String key;
                if (peek('\'') || peek('"')) key = string();
                else key = word();
                whitespace();
                require(':');
                result.add(key, parseValue());
                whitespace();
                if (peek('}')) { index++; return result; }
                require(',');
            }
        }

        private JsonPrimitive number() {
            int start = index;
            if (peek('-')) index++;
            while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
            boolean decimal = false;
            if (peek('.')) {
                decimal = true;
                index++;
                while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
            }
            if (index < source.length() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                decimal = true;
                index++;
                if (peek('+') || peek('-')) index++;
                while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
            }
            String raw = source.substring(start, index);
            try {
                return decimal ? new JsonPrimitive(Double.parseDouble(raw)) : new JsonPrimitive(Long.parseLong(raw));
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Invalid numeric textual tool argument.", invalid);
            }
        }

        private String string() {
            char quote = source.charAt(index++);
            StringBuilder out = new StringBuilder();
            boolean escaped = false;
            while (index < source.length()) {
                char c = source.charAt(index++);
                if (escaped) {
                    escaped = false;
                    out.append(switch (c) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case '\\' -> '\\';
                        case '\'' -> '\'';
                        case '"' -> '"';
                        default -> c;
                    });
                    continue;
                }
                if (c == '\\') { escaped = true; continue; }
                if (c == quote) return out.toString();
                out.append(c);
            }
            throw new IllegalArgumentException("Unterminated textual tool argument string.");
        }

        private String word() {
            whitespace();
            int start = index;
            while (index < source.length()) {
                char c = source.charAt(index);
                if (Character.isWhitespace(c) || c == ',' || c == ']' || c == '}' || c == ':') break;
                index++;
            }
            if (start == index) throw new IllegalArgumentException("Expected textual tool argument literal.");
            return source.substring(start, index);
        }

        private void whitespace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        }

        private boolean peek(char c) { return index < source.length() && source.charAt(index) == c; }

        private void require(char c) {
            whitespace();
            if (!peek(c)) throw new IllegalArgumentException("Expected '" + c + "' in textual tool argument.");
            index++;
        }
    }
}
