package com.spirit.koil.api.code;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class CodeLanguageDetector {
    private static final Pattern JSON_KEY = Pattern.compile("\"[^\"]+\"\\s*:");
    private static final Pattern YAML_KEY = Pattern.compile("(?m)^\\s*[- ]*[A-Za-z0-9_.\"'-]+\\s*:");
    private static final Pattern TOML_KEY = Pattern.compile("(?m)^\\s*[A-Za-z0-9_.-]+\\s*=\\s*.+$");
    private static final Pattern XML_TAG = Pattern.compile("</?[A-Za-z][^>]*>");
    private static final Pattern CSS_RULE = Pattern.compile("[.#]?[A-Za-z0-9_-]+\\s*\\{");
    private static final Pattern JAVA_CLASS = Pattern.compile("\\b(public\\s+)?(class|interface|enum|record)\\s+[A-Z][A-Za-z0-9_]*");
    private static final Pattern JAVA_MEMBER = Pattern.compile("\\b(public|private|protected)\\s+(static\\s+)?[A-Za-z_][A-Za-z0-9_<>, ?\\[\\]]*\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\(");
    private static final Pattern CSHARP_CLASS = Pattern.compile("\\b(namespace|using\\s+System|Console\\.WriteLine|public\\s+class)\\b");
    private static final Pattern PYTHON_DEF = Pattern.compile("(?m)^\\s*(def|class)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*[:(]");
    private static final Pattern JS_PATTERN = Pattern.compile("\\b(const|let|var|function|=>|console\\.log|import\\s+.+\\s+from)\\b");
    private static final Pattern CPP_PATTERN = Pattern.compile("\\b(std::|cout\\s*<<|cin\\s*>>|template\\s*<)\\b");
    private static final Pattern C_PATTERN = Pattern.compile("\\b(#include|printf\\s*\\(|scanf\\s*\\(|int\\s+main\\s*\\()\\b");
    private static final Pattern SHADER_PATTERN = Pattern.compile("\\b(#version|gl_Position|vec[234]|sampler2D|uniform)\\b");
    private static final Pattern PROPERTIES_PATTERN = Pattern.compile("(?m)^[^#;!\\s][^:=\\n]*[:=].+$");
    private static final Pattern LOG_PATTERN = Pattern.compile("\\b(INFO|WARN|ERROR|DEBUG|TRACE|FATAL)\\b|\\b\\d{2}:\\d{2}:\\d{2}\\b");
    private static final Pattern MARKDOWN_PATTERN = Pattern.compile("(?m)^(#{1,6}\\s|[-*+]\\s|>\\s)|```|\\[[^\\]]+\\]\\([^\\)]+\\)");
    private static final Pattern KTL_PATTERN = Pattern.compile("\\b(task_template|grammar_patterns|selector_pack|resolver_rules|cap\\.|sem\\.|minecraft:)\\b");

    private CodeLanguageDetector() {
    }

    public static DetectionResult detect(String source) {
        String text = source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n');
        String lower = text.toLowerCase(Locale.ROOT);
        Map<CodeLanguage, Score> scores = new LinkedHashMap<>();
        for (CodeLanguage language : CodeLanguage.values()) {
            if (language == CodeLanguage.TEXT) {
                continue;
            }
            scores.put(language, new Score(language));
        }

        add(scores, CodeLanguage.JSON, matches(JSON_KEY, text) * 2.8, "quoted keys");
        add(scores, CodeLanguage.JSON, contains(lower, "{", "}", ":") ? 1.5 : 0.0, "json structure");

        add(scores, CodeLanguage.YAML, matches(YAML_KEY, text) * 1.8, "yaml keys");
        add(scores, CodeLanguage.YAML, contains(lower, "- ", ":") ? 0.8 : 0.0, "yaml list/key shape");

        add(scores, CodeLanguage.TOML, matches(TOML_KEY, text) * 2.2, "toml assignments");
        add(scores, CodeLanguage.TOML, contains(lower, "[", "]", "=") ? 0.9 : 0.0, "toml sections");

        add(scores, CodeLanguage.XML, matches(XML_TAG, text) * 2.5, "xml tags");
        add(scores, CodeLanguage.XML, contains(lower, "<?xml", "</") ? 1.0 : 0.0, "xml prolog");

        add(scores, CodeLanguage.CSS, matches(CSS_RULE, text) * 2.0, "css selectors");
        add(scores, CodeLanguage.CSS, contains(lower, "{", "}", ":") ? 0.6 : 0.0, "css rule structure");

        add(scores, CodeLanguage.JAVA, matches(JAVA_CLASS, text) * 3.0, "java type declaration");
        add(scores, CodeLanguage.JAVA, matches(JAVA_MEMBER, text) * 2.5, "java member declaration");
        add(scores, CodeLanguage.JAVA, contains(lower, "system.out.println", "import java", "package ") ? 2.0 : 0.0, "java runtime/imports");

        add(scores, CodeLanguage.CSHARP, matches(CSHARP_CLASS, text) * 2.8, "csharp namespace/runtime");
        add(scores, CodeLanguage.CSHARP, contains(lower, "using system", "console.writeline", "namespace ") ? 1.5 : 0.0, "csharp imports");

        add(scores, CodeLanguage.PYTHON, matches(PYTHON_DEF, text) * 2.8, "python def/class");
        add(scores, CodeLanguage.PYTHON, contains(lower, "import ", "print(", "self", "elif ") ? 1.6 : 0.0, "python syntax");

        add(scores, CodeLanguage.JAVASCRIPT, matches(JS_PATTERN, text) * 2.5, "javascript syntax");
        add(scores, CodeLanguage.JAVASCRIPT, contains(lower, "=>", "const ", "let ", "function ") ? 1.4 : 0.0, "javascript keywords");

        add(scores, CodeLanguage.CPP, matches(CPP_PATTERN, text) * 2.8, "cpp std/template");
        add(scores, CodeLanguage.CPP, contains(lower, "::", "std::", "#include <") ? 1.6 : 0.0, "cpp operators");

        add(scores, CodeLanguage.C, matches(C_PATTERN, text) * 2.5, "c runtime/includes");
        add(scores, CodeLanguage.C, contains(lower, "#include", "printf(", "scanf(") ? 1.3 : 0.0, "c includes");

        add(scores, CodeLanguage.SHADER, matches(SHADER_PATTERN, text) * 3.0, "shader builtins");
        add(scores, CodeLanguage.SHADER, contains(lower, "#version", "gl_position", "sampler2d") ? 2.0 : 0.0, "shader core");

        add(scores, CodeLanguage.PROPERTIES, matches(PROPERTIES_PATTERN, text) * 1.8, "properties pairs");
        add(scores, CodeLanguage.CONFIG, matches(TOML_KEY, text) * 1.4, "config assignments");
        add(scores, CodeLanguage.CONFIG, contains(lower, "[", "]", "=") ? 0.7 : 0.0, "config sections");

        add(scores, CodeLanguage.LOG, matches(LOG_PATTERN, text) * 2.4, "log markers");
        add(scores, CodeLanguage.MARKDOWN, matches(MARKDOWN_PATTERN, text) * 2.2, "markdown markers");
        add(scores, CodeLanguage.KTL, matches(KTL_PATTERN, text) * 3.0, "ktl domain syntax");

        List<LanguageGuess> guesses = scores.values().stream()
                .map(Score::toGuess)
                .filter(guess -> guess.confidence() > 0.02D)
                .sorted(Comparator.comparingDouble(LanguageGuess::confidence).reversed())
                .limit(6)
                .toList();
        if (guesses.isEmpty()) {
            return new DetectionResult(List.of(new LanguageGuess(CodeLanguage.TEXT, 1.0D, List.of("no strong code markers"))));
        }
        double top = guesses.get(0).confidence();
        List<LanguageGuess> normalized = new ArrayList<>(guesses.size());
        for (LanguageGuess guess : guesses) {
            normalized.add(new LanguageGuess(guess.language(), Math.max(0.01D, Math.min(1.0D, guess.confidence() / Math.max(0.01D, top))), guess.signals()));
        }
        return new DetectionResult(List.copyOf(normalized));
    }

    public static LanguageGuess bestGuess(String source) {
        String text = source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n');
        // A fence without a language label should still prefer Java when the
        // source contains unmistakable Java structure. This avoids a lone
        // generic-like `<T>` being mistaken for an XML tag.
        if (JAVA_CLASS.matcher(text).find() || (JAVA_MEMBER.matcher(text).find()
                && (text.contains(";") || text.contains("{")))
                || text.contains("System.out.")) {
            return new LanguageGuess(CodeLanguage.JAVA, 1.0D, List.of("java structure"));
        }
        return detect(source).guesses().get(0);
    }

    public static CodeLanguage fromFenceLabel(String label) {
        if (label == null || label.isBlank()) {
            return CodeLanguage.TEXT;
        }
        String lower = label.trim().toLowerCase(Locale.ROOT);
        for (CodeLanguage language : CodeLanguage.values()) {
            if (language.matchesAlias(lower)) {
                return language;
            }
        }
        return CodeLanguage.TEXT;
    }

    private static boolean contains(String text, String... parts) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String part : parts) {
            if (!text.contains(part.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static int matches(Pattern pattern, String text) {
        if (pattern == null || text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        var matcher = pattern.matcher(text);
        while (matcher.find() && count < 8) {
            count++;
        }
        return count;
    }

    private static void add(Map<CodeLanguage, Score> scores, CodeLanguage language, double amount, String signal) {
        if (amount <= 0.0D || language == null || signal == null || signal.isBlank()) {
            return;
        }
        Score score = scores.get(language);
        if (score != null) {
            score.add(amount, signal);
        }
    }

    public record DetectionResult(List<LanguageGuess> guesses) {
    }

    public record LanguageGuess(CodeLanguage language, double confidence, List<String> signals) {
    }

    public enum CodeLanguage {
        JSON("snippet.json", "json", "json5", "mcmeta"),
        JAVA("snippet.java", "java"),
        PROPERTIES("snippet.properties", "properties", "prop"),
        CONFIG("snippet.cfg", "cfg", "conf", "ini", "config"),
        XML("snippet.xml", "xml", "html", "svg"),
        YAML("snippet.yml", "yaml", "yml"),
        KTL("snippet.ktl", "ktl"),
        MARKDOWN("snippet.md", "md", "markdown"),
        JAVASCRIPT("snippet.js", "js", "javascript", "ts", "typescript", "jsx", "tsx"),
        TOML("snippet.toml", "toml"),
        CSS("snippet.css", "css", "scss", "sass", "less"),
        C("snippet.c", "c", "h"),
        CPP("snippet.cpp", "cpp", "c++", "cc", "cxx", "hpp", "hh"),
        CSHARP("snippet.cs", "cs", "csharp"),
        PYTHON("snippet.py", "py", "python"),
        SHADER("snippet.glsl", "glsl", "vert", "frag", "shader"),
        LOG("snippet.log", "log"),
        TEXT("snippet.txt", "txt", "text");

        private final String suggestedFileName;
        private final List<String> aliases;

        CodeLanguage(String suggestedFileName, String... aliases) {
            this.suggestedFileName = suggestedFileName;
            this.aliases = List.of(aliases);
        }

        public String suggestedFileName() {
            return suggestedFileName;
        }

        public boolean matchesAlias(String value) {
            if (value == null) {
                return false;
            }
            for (String alias : aliases) {
                if (alias.equals(value)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Score {
        private final CodeLanguage language;
        private double value;
        private final List<String> signals = new ArrayList<>();

        private Score(CodeLanguage language) {
            this.language = language;
        }

        private void add(double amount, String signal) {
            value += amount;
            if (!signals.contains(signal) && signals.size() < 4) {
                signals.add(signal);
            }
        }

        private LanguageGuess toGuess() {
            return new LanguageGuess(language, value, List.copyOf(signals));
        }
    }
}
