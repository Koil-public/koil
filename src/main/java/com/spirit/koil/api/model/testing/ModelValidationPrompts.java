package com.spirit.koil.api.model.testing;

import java.util.Locale;

/** Fixed diagnostic input only; callers use the normal model service. */
public final class ModelValidationPrompts {
    private ModelValidationPrompts() {}

    public static String prompt(String name, int contextTokens) {
        return switch (name) {
            case "short" -> "Reply with exactly: COLIBRI-GIGATOKEN-SHORT-OK";
            case "structured" -> """
                    Remember these five values only for this request:
                    ALPHA = 1847
                    BRAVO = violet
                    CHARLIE = 93.125
                    DELTA = copper
                    ECHO = north
                    Now reply on one line using this exact order:
                    ECHO | CHARLIE | ALPHA | DELTA | BRAVO
                    """;
            case "repetition" -> "The following information intentionally contains repetition.\n"
                    + "Project: Koil\nEnvironment: Steam Deck\nOperating System: Linux\nPurpose: Colibri and GigaToken validation\n\n".repeat(5)
                    + "Without adding information, answer: 1. What project is being tested? 2. What device is being used? 3. What operating system is being used? 4. What two systems are being validated?";
            case "exact" -> """
                    Preserve the following data exactly:
                    UUID: 8f2c314e-72a1-4cb0-93f4-571ac83d9617
                    HEX: A7F3C921
                    DECIMAL: 981742.00317
                    PATH: ./koil/sys/model/testing/example.data
                    CLASS: com.spirit.koil.testing.ExampleRunner
                    Return all five values exactly as written.
                    """;
            case "code" -> """
                    Read this Java code without changing it:
                    public static long mix(long a, long b) {
                        return (a * 31L) ^ (b >>> 3);
                    }
                    Answer only:
                    method=<method name>
                    operator1=<operator between multiplication result and shifted b>
                    shift=<shift operator>
                    """;
            case "long" -> longPrompt(contextTokens);
            default -> throw new IllegalArgumentException("Unknown model probe: " + name);
        };
    }

    private static String longPrompt(int contextTokens) {
        // Conservative ASCII character ceiling, not an exact tokenizer budget.
        // Leave room for Koil's system/tool contracts; never advertise a boundary tier.
        int facts = Math.min(1000, (contextTokens - 8192) / 40);
        if (facts < 20) throw new IllegalArgumentException("Long probe needs a known context above 8992 tokens");
        int[] positions = {1, facts / 4, facts / 2, facts * 3 / 4, facts};
        String[] values = {"cobalt-raven", "marble-orbit", "ember-forest", "silver-anchor", "violet-engine"};
        StringBuilder prompt = new StringBuilder("Read these facts for this request only:\n");
        for (int index = 1; index <= facts; index++) {
            String value = String.format(Locale.ROOT, "value-%04d", index);
            for (int marker = 0; marker < positions.length; marker++) {
                if (index == positions[marker]) value = values[marker];
            }
            prompt.append(String.format(Locale.ROOT, "FACT-%04d = %s%n", index, value));
        }
        prompt.append("Return only the values for ");
        for (int index : positions) prompt.append(String.format(Locale.ROOT, "FACT-%04d, ", index));
        return prompt.append("in numerical order, separated by |.").toString();
    }
}
