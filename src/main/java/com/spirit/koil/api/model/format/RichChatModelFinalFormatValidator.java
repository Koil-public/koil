package com.spirit.koil.api.model.format;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded final-only presentation validator; never rewrites literal payloads. */
public final class RichChatModelFinalFormatValidator {
    private static final Pattern LATEX_FENCE = Pattern.compile(
            "(?is)```(?:latex|tex)\\s*\\n(.*?)\\n```"
    );
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern HEADING = Pattern.compile("(?m)^(\\s*)#{1,6}\\s+");
    private static final Pattern ANY_FENCE = Pattern.compile("(?is)```([^\\n`]*)\\n(.*?)\\n```");

    private RichChatModelFinalFormatValidator() {
    }

    public static Result validateAndRepair(String source) {
        RichChatModelOutputSanitizer.Result sanitized = RichChatModelOutputSanitizer.sanitize(source);
        String value = sanitized.text();
        boolean changed = sanitized.changed();
        List<String> issues = new ArrayList<>();

        String withoutHeadings = stripHeadingsOutsideFences(value);
        changed |= !withoutHeadings.equals(value);
        value = withoutHeadings;

        Matcher latex = LATEX_FENCE.matcher(value);
        StringBuffer recovered = new StringBuffer(value.length());
        while (latex.find()) {
            String body = latex.group(1);
            if (looksLikeLatexDocument(body)) {
                issues.add("LaTeX document output is unsupported; use inline or block math only");
                latex.appendReplacement(recovered, Matcher.quoteReplacement(latex.group()));
            } else {
                latex.appendReplacement(recovered, Matcher.quoteReplacement("$$\n" + body + "\n$$"));
                changed = true;
            }
        }
        latex.appendTail(recovered);
        value = recovered.toString();

        Matcher inline = INLINE_CODE.matcher(value);
        StringBuffer unwrappedInline = new StringBuffer(value.length());
        while (inline.find()) {
            String body = inline.group(1);
            boolean forbidden = body.startsWith("/")
                    || body.matches("\\[[^]]+]\\((?:/|(?i:command:/)).+\\)")
                    || looksLikeFormula(body);
            inline.appendReplacement(unwrappedInline, Matcher.quoteReplacement(forbidden ? body : inline.group()));
            changed |= forbidden;
        }
        inline.appendTail(unwrappedInline);
        value = unwrappedInline.toString();

        Matcher fences = ANY_FENCE.matcher(value);
        while (fences.find()) {
            String language = fences.group(1).strip().toLowerCase(java.util.Locale.ROOT);
            String body = fences.group(2).strip();
            if (language.equals("latex") || language.equals("tex") || looksLikeLatexDocument(body)) {
                issues.add("forbidden LaTeX fence or document");
            } else if (body.lines().anyMatch(line -> line.stripLeading().startsWith("/"))) {
                issues.add("Minecraft commands must be validated masked suggestions, not fenced code");
            } else if ((language.equals("math") || language.equals("formula")) && looksLikeFormula(body)) {
                issues.add("math must use inline or block LaTeX delimiters, not a code fence");
            }
        }
        return new Result(value.strip(), changed, List.copyOf(issues));
    }

    private static boolean looksLikeLatexDocument(String value) {
        String lower = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("\\documentclass") || lower.contains("\\begin{document}")
                || lower.contains("\\end{document}") || lower.contains("\\usepackage");
    }

    private static boolean looksLikeFormula(String value) {
        String text = value == null ? "" : value.strip();
        return text.startsWith("$") || text.startsWith("\\(") || text.startsWith("\\[")
                || text.contains("\\frac{") || text.contains("\\sum_") || text.contains("\\sqrt{")
                || text.matches(".*[=+*/^].*[0-9a-zA-Z)].*");
    }

    private static String stripHeadingsOutsideFences(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean fenced = false;
        String[] lines = value.split("\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.stripLeading().startsWith("```")) {
                fenced = !fenced;
            } else if (!fenced) {
                line = HEADING.matcher(line).replaceFirst("$1");
            }
            result.append(line);
            if (index + 1 < lines.length) result.append('\n');
        }
        return result.toString();
    }

    public record Result(String text, boolean changed, List<String> issues) {
        public Result {
            text = text == null ? "" : text;
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public boolean valid() {
            return !text.isBlank() && issues.isEmpty();
        }
    }
}
