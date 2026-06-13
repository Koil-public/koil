package com.spirit.koil.api.util.file.json5;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class JSON5FileHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static JsonObject parseJSON5File(File file) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append('\n');
            }
        }

        String sanitized = sanitizeJson5(content.toString());
        JsonElement parsed = JsonParser.parseString(sanitized);
        if (!parsed.isJsonObject()) {
            throw new JsonSyntaxException("Expected JSON5 object root");
        }
        return parsed.getAsJsonObject();
    }

    public static void writeJSON5File(JsonObject jsonObject, File file) throws IOException {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            GSON.toJson(jsonObject, writer);
        }
    }

    private static String sanitizeJson5(String input) {
        String withoutComments = stripComments(input);
        String normalizedStrings = normalizeSingleQuotedStrings(withoutComments);
        String normalizedKeys = quoteUnquotedKeys(normalizedStrings);
        return removeTrailingCommas(normalizedKeys);
    }

    private static String stripComments(String input) {
        StringBuilder out = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean lineComment = false;
        boolean blockComment = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            char next = i + 1 < input.length() ? input.charAt(i + 1) : '\0';

            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    out.append(c);
                }
                continue;
            }

            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }

            if (!inSingle && !inDouble && c == '/' && next == '/') {
                lineComment = true;
                i++;
                continue;
            }

            if (!inSingle && !inDouble && c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }

            if (c == '"' && !inSingle && !isEscaped(input, i)) {
                inDouble = !inDouble;
            } else if (c == '\'' && !inDouble && !isEscaped(input, i)) {
                inSingle = !inSingle;
            }

            out.append(c);
        }

        return out.toString();
    }

    private static String normalizeSingleQuotedStrings(String input) {
        StringBuilder out = new StringBuilder();
        boolean inDouble = false;
        boolean inSingle = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"' && !inSingle && !isEscaped(input, i)) {
                inDouble = !inDouble;
                out.append(c);
                continue;
            }

            if (c == '\'' && !inDouble && !isEscaped(input, i)) {
                out.append('"');
                inSingle = !inSingle;
                continue;
            }

            if (inSingle && c == '"' && !isEscaped(input, i)) {
                out.append("\\\"");
                continue;
            }

            out.append(c);
        }

        return out.toString();
    }

    private static String quoteUnquotedKeys(String input) {
        return input.replaceAll("(?m)([\\{,]\\s*)([A-Za-z_][A-Za-z0-9_\\-]*)(\\s*:)", "$1\"$2\"$3");
    }

    private static String removeTrailingCommas(String input) {
        return input.replaceAll(",(?=\\s*[}\\]])", "");
    }

    private static boolean isEscaped(String input, int index) {
        int slashCount = 0;
        for (int i = index - 1; i >= 0 && input.charAt(i) == '\\'; i--) {
            slashCount++;
        }
        return slashCount % 2 != 0;
    }
}
