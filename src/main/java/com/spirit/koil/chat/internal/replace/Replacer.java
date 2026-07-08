package com.spirit.koil.chat.internal.replace;

public class Replacer {
    public static EditorResult apply(String text, int cursor) {
        ReplacementRule best = null;

        for (ReplacementRule rule : ReplacementRule.RULES) {
            if (cursor < rule.trigger.length())
                continue;

            String before = text.substring(
                            cursor - rule.trigger.length(),
                            cursor
                    );

            if (before.equals(rule.trigger)) {
                if (best == null ||
                        rule.trigger.length() >
                        best.trigger.length()) {
                    best = rule;
                }
            }
        }

        if (best == null) return new EditorResult(text, cursor);

        int start = cursor - best.trigger.length();
        String replacement = best.replacement;
        int cursorOffset = replacement.indexOf('|');
        replacement = replacement.replace("|", "");

        String output =
                text.substring(0, start)
                + replacement
                + text.substring(cursor);

        int newCursor;

        if (cursorOffset >= 0) {
            newCursor = start + cursorOffset;
        } else {
            newCursor = start + replacement.length();
        }


        return new EditorResult(output, newCursor);
    }
}