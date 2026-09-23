package com.spirit.koil.api.model.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small bounded JSON reader for untrusted model metadata. It never instantiates model code. */
final class SafeJsonMetadataReader {
    private static final long MAX_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_BUFFER_BYTES = 100L * 1024L * 1024L;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_VALUES = 1_000_000;

    private SafeJsonMetadataReader() {
    }

    static Map<String, Object> readObject(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return Map.of();
        long size = Files.size(file);
        if (size < 0 || size > MAX_BYTES) throw new IOException("JSON metadata file exceeds bounded size");
        return readObjectText(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Parses an already-bounded JSON metadata buffer without creating a temporary file. */
    static Map<String, Object> readObject(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) return Map.of();
        if (bytes.length > MAX_BUFFER_BYTES) throw new IOException("JSON metadata buffer exceeds bounded size");
        return readObjectText(new String(bytes, StandardCharsets.UTF_8));
    }

    private static Map<String, Object> readObjectText(String text) throws IOException {
        Parser parser = new Parser(text);
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (!parser.finished()) throw new IOException("trailing content in JSON metadata");
        if (!(value instanceof Map<?, ?> raw)) throw new IOException("JSON metadata root is not an object");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() instanceof String key) result.put(key, entry.getValue());
        }
        return Map.copyOf(result);
    }

    private static final class Parser {
        private final String text;
        private int index;
        private int values;

        private Parser(String text) {
            this.text = text == null ? "" : text;
        }

        private Object readValue(int depth) throws IOException {
            if (depth > MAX_DEPTH) throw new IOException("JSON metadata nesting is too deep");
            if (++values > MAX_VALUES) throw new IOException("JSON metadata contains too many values");
            skipWhitespace();
            if (finished()) throw new IOException("unexpected end of JSON metadata");
            char c = text.charAt(index);
            if (c == '{') return readObject(depth + 1);
            if (c == '[') return readArray(depth + 1);
            if (c == '"') return readString();
            if (c == 't') { expect("true"); return Boolean.TRUE; }
            if (c == 'f') { expect("false"); return Boolean.FALSE; }
            if (c == 'n') { expect("null"); return null; }
            if (c == '-' || Character.isDigit(c)) return readNumber();
            throw new IOException("invalid JSON token at offset " + index);
        }

        private Map<String, Object> readObject(int depth) throws IOException {
            expect('{');
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) return result;
            while (true) {
                skipWhitespace();
                if (finished() || text.charAt(index) != '"') throw new IOException("JSON object key must be a string");
                String key = readString();
                skipWhitespace();
                expect(':');
                result.put(key, readValue(depth));
                skipWhitespace();
                if (consume('}')) return result;
                expect(',');
            }
        }

        private List<Object> readArray(int depth) throws IOException {
            expect('[');
            ArrayList<Object> result = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) return result;
            while (true) {
                result.add(readValue(depth));
                skipWhitespace();
                if (consume(']')) return result;
                expect(',');
            }
        }

        private String readString() throws IOException {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (!finished()) {
                char c = text.charAt(index++);
                if (c == '"') return result.toString();
                if (c == '\\') {
                    if (finished()) throw new IOException("truncated JSON escape");
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> result.append(escaped);
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> result.append(readUnicodeEscape());
                        default -> throw new IOException("invalid JSON escape");
                    }
                } else {
                    if (c < 0x20) throw new IOException("control character in JSON string");
                    result.append(c);
                }
            }
            throw new IOException("unterminated JSON string");
        }

        private char readUnicodeEscape() throws IOException {
            if (index + 4 > text.length()) throw new IOException("truncated unicode escape");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(index++), 16);
                if (digit < 0) throw new IOException("invalid unicode escape");
                value = (value << 4) | digit;
            }
            return (char)value;
        }

        private Number readNumber() throws IOException {
            int start = index;
            if (consume('-') && finished()) throw new IOException("truncated JSON number");
            if (consume('0')) {
                // zero is complete unless followed by fraction/exponent
            } else {
                if (finished() || !Character.isDigit(text.charAt(index))) throw new IOException("invalid JSON number");
                while (!finished() && Character.isDigit(text.charAt(index))) index++;
            }
            boolean decimal = false;
            if (consume('.')) {
                decimal = true;
                if (finished() || !Character.isDigit(text.charAt(index))) throw new IOException("invalid JSON fraction");
                while (!finished() && Character.isDigit(text.charAt(index))) index++;
            }
            if (!finished() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                decimal = true;
                index++;
                if (!finished() && (text.charAt(index) == '+' || text.charAt(index) == '-')) index++;
                if (finished() || !Character.isDigit(text.charAt(index))) throw new IOException("invalid JSON exponent");
                while (!finished() && Character.isDigit(text.charAt(index))) index++;
            }
            String raw = text.substring(start, index);
            try {
                return decimal ? Double.parseDouble(raw) : Long.parseLong(raw);
            } catch (NumberFormatException exception) {
                throw new IOException("invalid JSON number", exception);
            }
        }

        private void expect(String literal) throws IOException {
            if (!text.regionMatches(index, literal, 0, literal.length())) throw new IOException("expected '" + literal + "'");
            index += literal.length();
        }

        private void expect(char expected) throws IOException {
            skipWhitespace();
            if (finished() || text.charAt(index) != expected) throw new IOException("expected '" + expected + "' at offset " + index);
            index++;
        }

        private boolean consume(char expected) {
            if (!finished() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (!finished()) {
                char c = text.charAt(index);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') index++;
                else break;
            }
        }

        private boolean finished() {
            return index >= text.length();
        }
    }
}
