package com.spirit.koil.chat.internal.replace;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Replacer {

    private static final Map<String, String> FONTS = Map.of(
            "bb", "mathbb",
            "bf", "mathbf",
            "cal", "mathcal",
            "scr", "mathscr",
            "frak", "mathfrak",
            "sf", "mathsf",
            "tt", "mathtt",
            "rm", "mathrm",
            "it", "mathit"
    );


    public static EditorResult apply(String text, int cursor) {

        ReplacementRule best = null;
        String bestMatch = null;
        Matcher bestMatcher = null;


        String beforeCursor = text.substring(0, cursor);


        for (ReplacementRule rule : ReplacementRule.RULES) {

            if (rule.type == ReplacementRule.Type.LITERAL ||
                    rule.type == ReplacementRule.Type.EXPRESSION) {

                if (beforeCursor.endsWith(rule.trigger)) {

                    if (best == null ||
                            rule.trigger.length() > bestMatch.length()) {

                        best = rule;
                        bestMatch = rule.trigger;
                    }
                }

                continue;
            }


            Matcher matcher = Pattern
                    .compile(rule.trigger)
                    .matcher(beforeCursor);


            while (matcher.find()) {

                if (matcher.end() != cursor)
                    continue;


                String match = matcher.group();


                if (best == null ||
                        match.length() > bestMatch.length()) {

                    best = rule;
                    bestMatch = match;
                    bestMatcher = matcher;
                }
            }
        }


        if (best == null)
            return new EditorResult(text, cursor);


        int start = cursor - bestMatch.length();

        String replacement;


        switch (best.type) {


            case LITERAL -> {

                replacement = best.replacement;
            }


            case EXPRESSION -> {

                start = findExpressionStart(
                        text,
                        start
                );


                String expression =
                        text.substring(
                                start,
                                cursor - bestMatch.length()
                        );


                replacement =
                        insertExpression(
                                best.replacement,
                                expression
                        );
            }


            case COMMAND -> {

                String command =
                        bestMatcher.group(1);

                replacement =
                        "\\" + command;
            }


            case ACCENT -> {

                String expression =
                        bestMatcher.group(1);

                String accent =
                        bestMatcher.group(2);


                replacement =
                        "\\"
                                + accent
                                + "{"
                                + expression
                                + "}";
            }


            case FONT -> {

                String expression =
                        bestMatcher.group(1);

                String font =
                        bestMatcher.group(2);


                replacement =
                        "\\"
                                + FONTS.getOrDefault(font, font)
                                + "{"
                                + expression
                                + "}";
            }


            default -> {
                return new EditorResult(text, cursor);
            }
        }


        int cursorOffset =
                replacement.indexOf('|');


        replacement =
                replacement.replace("|", "");


        String output =
                text.substring(0, start)
                        + replacement
                        + text.substring(cursor);


        int newCursor;

        if (cursorOffset >= 0)
            newCursor = start + cursorOffset;
        else
            newCursor = start + replacement.length();


        return new EditorResult(output, newCursor);
    }



    private static String insertExpression(
            String template,
            String expression
    ) {

        int first =
                template.indexOf('|');


        if (first < 0)
            return template;


        int second =
                template.indexOf(
                        '|',
                        first + 1
                );


        if (second < 0) {

            return template.replace(
                    "|",
                    expression
            );
        }


        return template.substring(0, first)
                + expression
                + template.substring(first + 1, second)
                + template.substring(second + 1);
    }



    private static int findExpressionStart(String text, int end) {
        int parenDepth = 0;
        int braceDepth = 0;
        int bracketDepth = 0;

        for (int i = end - 1; i >= 0; i--) {
            char c = text.charAt(i);


            if (c == '}') {
                braceDepth++;
                continue;
            }

            if (c == '{') {
                braceDepth--;

                if (braceDepth < 0)
                    return i + 1;

                continue;
            }


            if (c == ')') {
                parenDepth++;
                continue;
            }

            if (c == '(') {
                parenDepth--;

                if (parenDepth < 0)
                    return i + 1;

                continue;
            }


            if (c == ']') {
                bracketDepth++;
                continue;
            }

            if (c == '[') {
                bracketDepth--;
                if (bracketDepth < 0)
                    return i + 1;

                continue;
            }


            if (braceDepth == 0 &&
                    parenDepth == 0 &&
                    bracketDepth == 0) {

                if (Character.isWhitespace(c)
                        || c == '+'
                        || c == '-'
                        || c == '='
                        || c == ','
                        || c == '*'
                        || c == '/') {

                    return i + 1;
                }
            }
        }


        return 0;
    }
}