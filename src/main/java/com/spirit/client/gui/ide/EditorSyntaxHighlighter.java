package com.spirit.client.gui.ide;

import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.spirit.client.gui.ide.CodeColorTypes.*;
import static com.spirit.koil.api.design.uiColorVal.uiColorContentBaseDescriptionText;

public final class EditorSyntaxHighlighter {
    private static final int CACHE_SIZE = 2048;
    private static final int SOFT_TEXT_COLOR = new Color(uiColorContentBaseDescriptionText, true).getRGB();

    private static final Pattern JSON_PATTERN = Pattern.compile(
            "(?<COMMENT>//[^\\n]*|/\\*(?:.|\\R)*?\\*/)|" +
                    "(?<KEY>\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:))|" +
                    "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\")|" +
                    "(?<ESCAPE>\\\\(?:[\"\\\\/bfnrt]|u[0-9a-fA-F]{4}))|" +
                    "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)|" +
                    "(?<OBJECT>[{}])|" +
                    "(?<ARRAY>[\\[\\]])|" +
                    "(?<BOOLEAN>\\btrue\\b|\\bfalse\\b)|" +
                    "(?<NULL>\\bnull\\b)|" +
                    "(?<COLON>:)|" +
                    "(?<COMMA>,)"
    );

    private static final Pattern JAVA_PATTERN = Pattern.compile(
            "(?<JAVADOC>/\\*\\*(?:.|\\R)*?\\*/)|" +
                    "(?<COMMENT>//[^\\n]*|/\\*(?:.|\\R)*?\\*/)|" +
                    "(?<ANNOTATION>@[A-Za-z_][A-Za-z0-9_]*)|" +
                    "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\")|" +
                    "(?<CHAR>'(?:\\\\.|[^'\\\\])')|" +
                    "(?<ESCAPE>\\\\(?:[btnfr\"'\\\\]|u[0-9a-fA-F]{4}))|" +
                    "(?<KEYWORD>\\b(public|protected|private|static|final|abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|extends|finally|float|for|goto|if|implements|import|instanceof|int|interface|long|native|new|null|package|return|short|strictfp|super|switch|synchronized|this|throw|throws|transient|try|void|volatile|while|record|sealed|permits|var)\\b)|" +
                    "(?<TYPE>\\b(String|Integer|Boolean|Character|Byte|Short|Long|Float|Double|Void|Object|List|Map|Set|ArrayList|HashMap|HashSet|Optional)\\b)|" +
                    "(?<METHOD>\\b[a-zA-Z_][a-zA-Z0-9_]*\\s*(?=\\())|" +
                    "(?<IDENTIFIER>\\b[a-zA-Z_][a-zA-Z0-9_]*\\b)|" +
                    "(?<BOOLEAN>\\btrue\\b|\\bfalse\\b)|" +
                    "(?<NULL>\\bnull\\b)|" +
                    "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[fFdDlL]?\\b)|" +
                    "(?<OPERATOR>==|!=|<=|>=|&&|\\|\\||[+\\-*/%!=<>?:&|^~])|" +
                    "(?<BRACE>[{}()\\[\\]])"
    );

    private static final Pattern PROPERTIES_PATTERN = Pattern.compile(
            "(?<COMMENT>^[#!].*)|" +
                    "(?<SECTION>\\[[^\\]\\n]+\\])|" +
                    "(?<KEY>^[^:=\\s][^:=]*?(?=\\s*[:=]))|" +
                    "(?<SEPARATOR>[:=])|" +
                    "(?<UNICODE>\\\\u[0-9a-fA-F]{4})|" +
                    "(?<ESCAPE>\\\\[\\\\:=\\s#!tnrf])|" +
                    "(?<BOOLEAN>\\btrue\\b|\\bfalse\\b)|" +
                    "(?<NULL>\\bnull\\b)|" +
                    "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?\\b)|" +
                    "(?<STRING>(?<=[:=]\\s).+)"
    );

    private static final Pattern CONFIG_PATTERN = Pattern.compile(
            "(?<COMMENT>[#;].*$)|" +
                    "(?<SECTION>\\[[^\\]\\n]+\\])|" +
                    "(?<KEY>^[ \\t]*[A-Za-z0-9_.-]+(?=\\s*=))|" +
                    "(?<SEPARATOR>=)|" +
                    "(?<DIRECTIVE>\\b(include|define|set|unset)\\b)|" +
                    "(?<PATH>(?:/|\\./|\\.\\./)[^\\s#;]+)|" +
                    "(?<BOOLEAN>\\btrue\\b|\\bfalse\\b|\\byes\\b|\\bno\\b|\\bon\\b|\\boff\\b)|" +
                    "(?<NULL>\\bnull\\b|\\bnone\\b)|" +
                    "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?\\b)|" +
                    "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"
    );

    private static final Pattern XML_PATTERN = Pattern.compile(
            "(?<COMMENT><!--(?:.|\\R)*?-->)|" +
                    "(?<PROLOG><\\?.*?\\?>)|" +
                    "(?<DOCTYPE><!DOCTYPE[^>]*>)|" +
                    "(?<CDATA><!\\[CDATA\\[(?:.|\\R)*?\\]\\]>)|" +
                    "(?<ENTITY>&[A-Za-z0-9#]+;)|" +
                    "(?<TAG></?[A-Za-z_:][A-Za-z0-9:._-]*)|" +
                    "(?<ATTR>[A-Za-z_:][A-Za-z0-9:._-]*(?==))|" +
                    "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')|" +
                    "(?<BRACE></|<|/?>|\\?>)"
    );

    private static final Pattern YAML_PATTERN = Pattern.compile(
            "(?<COMMENT>#.*$)|" +
                    "(?<KEY>^[\\s-]*[A-Za-z0-9_.\"'-]+(?=\\s*:))|" +
                    "(?<ANCHOR>&[A-Za-z0-9_.-]+)|" +
                    "(?<ALIAS>\\*[A-Za-z0-9_.-]+)|" +
                    "(?<TAG>![A-Za-z0-9_.:-]+)|" +
                    "(?<DASH>^\\s*-)|" +
                    "(?<COLON>:)|" +
                    "(?<BOOLEAN>\\btrue\\b|\\bfalse\\b|\\byes\\b|\\bno\\b|\\bon\\b|\\boff\\b)|" +
                    "(?<NULL>\\bnull\\b|~)|" +
                    "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?\\b)|" +
                    "(?<MULTILINE>[|>])|" +
                    "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"
    );

    private static final Pattern KTL_PATTERN = Pattern.compile(
            "(?<COMMENT>#.*$)|" +
                    "(?<PLACEHOLDER>\\$\\{[A-Za-z0-9_.-]+})|" +
                    "(?<CAPABILITY>\\bcap\\.[A-Za-z0-9_.-]+\\b)|" +
                    "(?<EVALUATOR>\\beval\\.[A-Za-z0-9_.-]+\\b)|" +
                    "(?<SEMANTIC>\\bsem\\.[A-Za-z0-9_.-]+\\b)|" +
                    "(?<MINECRAFT>\\bminecraft:[A-Za-z0-9_./-]+\\b)|" +
                    "(?<KIND>\\b(lexicon|grammar_patterns|condition_definition|condition_pack|semantic_operation|semantic_operation_pack|template_metadata|task_template|task_preset|task_macro|resolver_rules|alias_pack|compile_profile|namespace_pack|reference_patterns|selector_pack)\\b)|" +
                    "(?<STEP>\\b(run_primitive|delegate|branch|goto|return|write_state|emit_event)\\b)|" +
                    "(?<KEY>^[\\s-]*[A-Za-z0-9_.\"'-]+(?=\\s*:))|" +
                    "(?<DASH>^\\s*-)|" +
                    "(?<COLON>:)|" +
                    "(?<BOOLEAN>\\btrue\\b|\\bfalse\\b|\\byes\\b|\\bno\\b|\\bon\\b|\\boff\\b)|" +
                    "(?<NULL>\\bnull\\b|~)|" +
                    "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?\\b)|" +
                    "(?<MULTILINE>[|>])|" +
                    "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"
    );

    private static final Pattern MARKDOWN_PATTERN = Pattern.compile(
            "(?<CODEBLOCK>```(?:.|\\R)*?```)|" +
                    "(?<HEADING>^#{1,6}\\s.*)|" +
                    "(?<LIST>^\\s*(?:[-*+]\\s|\\d+\\.\\s).*)|" +
                    "(?<QUOTE>^>\\s.*)|" +
                    "(?<CODE>`[^`]+`)|" +
                    "(?<BOLD>\\*\\*[^*]+\\*\\*|__[^_]+__)|" +
                    "(?<ITALIC>\\*[^*]+\\*|_[^_]+_)|" +
                    "(?<EMPHASIS>\\*{1,3}[^*]+\\*{1,3}|_{1,3}[^_]+_{1,3})|" +
                    "(?<LINK>\\[[^\\]]+\\]\\([^\\)]+\\))|" +
                    "(?<URL>https?://\\S+)"
    );

    private static final Pattern LOG_PATTERN = Pattern.compile(
            "(?<TIMESTAMP>\\b\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\b|\\b\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\b)|" +
                    "(?<LEVEL>\\bTRACE\\b|\\bDEBUG\\b|\\bINFO\\b|\\bWARN\\b|\\bERROR\\b|\\bFATAL\\b)|" +
                    "(?<LOGGER>\\b[a-zA-Z_][\\w.]*\\b)|" +
                    "(?<THREAD>\\[[^\\]]+\\])|" +
                    "(?<CLASS>\\b[A-Z][A-Za-z0-9_$.]*\\b)|" +
                    "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?\\b)|" +
                    "(?<MESSAGE>.+)"
    );

    private static final Pattern JAVASCRIPT_PATTERN = Pattern.compile(
            "(?<JSDOC>/\\*\\*(?:.|\\R)*?\\*/)|" +
                    "(?<COMMENT>//[^\\n]*|/\\*(?:.|\\R)*?\\*/)|" +
                    "(?<ANNOTATION>@[A-Za-z_][A-Za-z0-9_]*)|" +
                    "(?<TEMPLATE>`(?:\\\\.|[^`\\\\])*`)|" +
                    "(?<REGEX>/([^/\\\\\\n]|\\\\.)+/[gimsuyd]*)|" +
                    "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')|" +
                    "(?<ESCAPE>\\\\(?:[btnfr\"'`\\\\]|u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}))|" +
                    "(?<KEYWORD>\\b(function|return|const|let|var|if|else|switch|case|break|continue|for|while|do|class|extends|import|from|export|default|new|this|throw|try|catch|finally|async|await|null|undefined)\\b)|" +
                    "(?<TYPE>\\b(Array|Boolean|Date|Error|Function|JSON|Map|Math|Number|Object|Promise|RegExp|Set|String)\\b)|" +
                    "(?<FUNCTION>\\b[a-zA-Z_$][a-zA-Z0-9_$]*\\s*(?=\\())|" +
                    "(?<IDENTIFIER>\\b[a-zA-Z_$][a-zA-Z0-9_$]*\\b)|" +
                    "(?<BOOLEAN>\\btrue\\b|\\bfalse\\b)|" +
                    "(?<NULL>\\bnull\\b|\\bundefined\\b)|" +
                    "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?\\b)|" +
                    "(?<OPERATOR>===|!==|==|!=|<=|>=|&&|\\|\\||=>|[+\\-*/%!=<>?:&|^~])|" +
                    "(?<BRACE>[{}()\\[\\]])"
    );

    private static final Pattern TOML_PATTERN = Pattern.compile(
            "(?<COMMENT>#.*$)|" +
                    "(?<ARRAYSECTION>\\[\\[[^\\]]+\\]\\])|" +
                    "(?<SECTION>\\[[^\\]]+\\])|" +
                    "(?<KEY>^[A-Za-z0-9_.-]+(?=\\s*=))|" +
                    "(?<DOT>\\.)|" +
                    "(?<SEPARATOR>=)|" +
                    "(?<DATE>\\b\\d{4}-\\d{2}-\\d{2}(?:[Tt ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:\\d{2})?\\b)|" +
                    "(?<BOOLEAN>\\btrue\\b|\\bfalse\\b)|" +
                    "(?<NULL>\\bnull\\b)|" +
                    "(?<NUMBER>-?\\b\\d+(?:_\\d+)*(?:\\.\\d+(?:_\\d+)*)?\\b)|" +
                    "(?<MULTILINESTRING>\"\"\"(?:.|\\R)*?\"\"\"|'''(?:.|\\R)*?''')|" +
                    "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"
    );

    private static final Pattern CSS_PATTERN = Pattern.compile(
            "(?<COMMENT>/\\*(?:.|\\R)*?\\*/)|" +
                    "(?<ATRULE>@[A-Za-z_-][A-Za-z0-9_-]*)|" +
                    "(?<HEX>#[0-9a-fA-F]{3,8}\\b)|" +
                    "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?(?:px|em|rem|vw|vh|%|ms|s|deg)?\\b)|" +
                    "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')|" +
                    "(?<PROPERTY>\\b[a-zA-Z-]+(?=\\s*:))|" +
                    "(?<FUNCTION>\\b[a-zA-Z-]+(?=\\())|" +
                    "(?<BRACE>[{}()\\[\\]])|" +
                    "(?<OPERATOR>[:;,.>+~])"
    );

    private static final Pattern C_PATTERN = Pattern.compile(
            "(?<COMMENT>//[^\\n]*|/\\*(?:.|\\R)*?\\*/)|" +
                    "(?<PREPROCESSOR>#[A-Za-z_][A-Za-z0-9_]*)|" +
                    "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\")|" +
                    "(?<CHAR>'(?:\\\\.|[^'\\\\])')|" +
                    "(?<ESCAPE>\\\\(?:[btnfr\"'\\\\]|x[0-9a-fA-F]{2}|[0-7]{1,3}))|" +
                    "(?<KEYWORD>\\b(auto|break|case|char|const|continue|default|do|double|else|enum|extern|float|for|goto|if|inline|int|long|register|restrict|return|short|signed|sizeof|static|struct|switch|typedef|union|unsigned|void|volatile|while|_Bool|_Complex|_Imaginary)\\b)|" +
                    "(?<TYPE>\\b(bool|size_t|FILE|uint\\d+_t|int\\d+_t)\\b)|" +
                    "(?<FUNCTION>\\b[a-zA-Z_][a-zA-Z0-9_]*\\s*(?=\\())|" +
                    "(?<IDENTIFIER>\\b[a-zA-Z_][a-zA-Z0-9_]*\\b)|" +
                    "(?<NUMBER>-?\\b(?:0x[0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b[uUlLfF]*)|" +
                    "(?<OPERATOR>==|!=|<=|>=|&&|\\|\\||<<|>>|->|[+\\-*/%!=<>?:&|^~])|" +
                    "(?<BRACE>[{}()\\[\\]])"
    );

    private static final Pattern CPP_PATTERN = Pattern.compile(
            "(?<COMMENT>//[^\\n]*|/\\*(?:.|\\R)*?\\*/)|" +
                    "(?<PREPROCESSOR>#[A-Za-z_][A-Za-z0-9_]*)|" +
                    "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\")|" +
                    "(?<CHAR>'(?:\\\\.|[^'\\\\])')|" +
                    "(?<ESCAPE>\\\\(?:[btnfr\"'\\\\]|u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}))|" +
                    "(?<KEYWORD>\\b(alignas|alignof|auto|break|case|catch|class|const|consteval|constexpr|constinit|continue|co_await|co_return|co_yield|decltype|default|delete|do|else|enum|explicit|export|extern|final|for|friend|goto|if|inline|mutable|namespace|new|noexcept|nullptr|operator|override|private|protected|public|register|requires|return|sizeof|static|struct|switch|template|this|throw|try|typedef|typename|union|using|virtual|while)\\b)|" +
                    "(?<TYPE>\\b(bool|char16_t|char32_t|double|float|int|long|short|signed|std|string|unsigned|void|wchar_t)\\b)|" +
                    "(?<FUNCTION>\\b[a-zA-Z_][a-zA-Z0-9_]*\\s*(?=\\())|" +
                    "(?<IDENTIFIER>\\b[a-zA-Z_][a-zA-Z0-9_]*\\b)|" +
                    "(?<BOOLEAN>\\btrue\\b|\\bfalse\\b)|" +
                    "(?<NULL>\\bnullptr\\b)|" +
                    "(?<NUMBER>-?\\b(?:0x[0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b[uUlLfF]*)|" +
                    "(?<OPERATOR>==|!=|<=|>=|&&|\\|\\||<<|>>|->|::|[+\\-*/%!=<>?:&|^~])|" +
                    "(?<BRACE>[{}()\\[\\]])"
    );

    private static final Pattern CSHARP_PATTERN = Pattern.compile(
            "(?<COMMENT>//[^\\n]*|/\\*(?:.|\\R)*?\\*/)|" +
                    "(?<PREPROCESSOR>#[A-Za-z_][A-Za-z0-9_]*)|" +
                    "(?<ANNOTATION>@[A-Za-z_][A-Za-z0-9_]*)|" +
                    "(?<STRING>@\"(?:\"\"|[^\"])*\"|\"(?:\\\\.|[^\"\\\\])*\")|" +
                    "(?<CHAR>'(?:\\\\.|[^'\\\\])')|" +
                    "(?<ESCAPE>\\\\(?:[abfnrtv\"'\\\\]|u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2,4}))|" +
                    "(?<KEYWORD>\\b(abstract|as|async|await|base|break|case|catch|checked|class|const|continue|default|delegate|do|else|enum|event|explicit|extern|finally|fixed|for|foreach|goto|if|implicit|in|interface|internal|is|lock|namespace|new|operator|out|override|params|partial|private|protected|public|readonly|record|ref|required|return|sealed|sizeof|stackalloc|static|struct|switch|this|throw|try|typeof|unchecked|unsafe|using|virtual|volatile|while)\\b)|" +
                    "(?<TYPE>\\b(bool|byte|char|decimal|double|dynamic|float|int|long|nint|nuint|object|sbyte|short|string|uint|ulong|ushort|var|void)\\b)|" +
                    "(?<FUNCTION>\\b[a-zA-Z_][a-zA-Z0-9_]*\\s*(?=\\())|" +
                    "(?<IDENTIFIER>\\b[a-zA-Z_][a-zA-Z0-9_]*\\b)|" +
                    "(?<BOOLEAN>\\btrue\\b|\\bfalse\\b)|" +
                    "(?<NULL>\\bnull\\b)|" +
                    "(?<NUMBER>-?\\b(?:0x[0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b[mMdDfFlLuU]*)|" +
                    "(?<OPERATOR>==|!=|<=|>=|&&|\\|\\||=>|\\?\\.|\\?\\?|::|[+\\-*/%!=<>?:&|^~])|" +
                    "(?<BRACE>[{}()\\[\\]])"
    );

    private static final Pattern PYTHON_PATTERN = Pattern.compile(
            "(?<COMMENT>#[^\\n]*)|" +
                    "(?<ANNOTATION>@[A-Za-z_][A-Za-z0-9_]*)|" +
                    "(?<MULTILINESTRING>\"\"\"(?:.|\\R)*?\"\"\"|'''(?:.|\\R)*?''')|" +
                    "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')|" +
                    "(?<ESCAPE>\\\\(?:[abfnrtv\"'\\\\]|u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|N\\{[^}]+\\}))|" +
                    "(?<KEYWORD>\\b(and|as|assert|async|await|break|case|class|continue|def|del|elif|else|except|finally|for|from|global|if|import|in|is|lambda|match|nonlocal|not|or|pass|raise|return|try|while|with|yield)\\b)|" +
                    "(?<TYPE>\\b(dict|float|int|list|set|str|tuple)\\b)|" +
                    "(?<FUNCTION>\\b[a-zA-Z_][a-zA-Z0-9_]*\\s*(?=\\())|" +
                    "(?<IDENTIFIER>\\b[a-zA-Z_][a-zA-Z0-9_]*\\b)|" +
                    "(?<BOOLEAN>\\bTrue\\b|\\bFalse\\b)|" +
                    "(?<NULL>\\bNone\\b)|" +
                    "(?<NUMBER>-?\\b(?:0x[0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b[jJ]?)|" +
                    "(?<OPERATOR>==|!=|<=|>=|//=|\\*\\*|:=|->|[+\\-*/%!=<>:&|^~])|" +
                    "(?<BRACE>[{}()\\[\\]])"
    );

    private static final Pattern SHADER_PATTERN = Pattern.compile(
            "(?<COMMENT>//[^\\n]*|/\\*(?:.|\\R)*?\\*/)|" +
                    "(?<PREPROCESSOR>#[A-Za-z_][A-Za-z0-9_]*)|" +
                    "(?<QUALIFIER>\\b(attribute|const|in|out|inout|uniform|varying|buffer|shared|layout|flat|smooth|noperspective|precision|highp|mediump|lowp)\\b)|" +
                    "(?<KEYWORD>\\b(void|bool|int|float|double|vec[234]|ivec[234]|bvec[234]|uvec[234]|mat[234]|sampler2D|samplerCube|return|if|else|for|while|do|break|continue|discard|struct)\\b)|" +
                    "(?<FUNCTION>\\b[a-zA-Z_][a-zA-Z0-9_]*\\s*(?=\\())|" +
                    "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[fF]?\\b)|" +
                    "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')|" +
                    "(?<OPERATOR>==|!=|<=|>=|&&|\\|\\||[+\\-*/%!=<>?:&|^~])|" +
                    "(?<BRACE>[{}()\\[\\]])"
    );

    private static final Map<CacheKey, List<StyledSpan>> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, List<StyledSpan>> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private EditorSyntaxHighlighter() {
    }

    public static List<StyledSpan> highlight(String fileName, String line) {
        SyntaxLanguage language = SyntaxLanguage.fromFileName(fileName);
        CacheKey key = new CacheKey(language, line);
        List<StyledSpan> cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        List<StyledSpan> spans = switch (language) {
            case JSON -> tokenize(line, JSON_PATTERN, language);
            case JAVA -> tokenize(line, JAVA_PATTERN, language);
            case PROPERTIES -> tokenize(line, PROPERTIES_PATTERN, language);
            case CONFIG -> tokenize(line, CONFIG_PATTERN, language);
            case XML -> tokenize(line, XML_PATTERN, language);
            case YAML -> tokenize(line, YAML_PATTERN, language);
            case KTL -> tokenize(line, KTL_PATTERN, language);
            case MARKDOWN -> tokenize(line, MARKDOWN_PATTERN, language);
            case JAVASCRIPT -> tokenize(line, JAVASCRIPT_PATTERN, language);
            case TOML -> tokenize(line, TOML_PATTERN, language);
            case CSS -> tokenize(line, CSS_PATTERN, language);
            case C -> tokenize(line, C_PATTERN, language);
            case CPP -> tokenize(line, CPP_PATTERN, language);
            case CSHARP -> tokenize(line, CSHARP_PATTERN, language);
            case PYTHON -> tokenize(line, PYTHON_PATTERN, language);
            case SHADER -> tokenize(line, SHADER_PATTERN, language);
            case LOG -> tokenize(line, LOG_PATTERN, language);
            case TEXT -> List.of(new StyledSpan(line, COLOR_DEFAULT));
        };

        CACHE.put(key, spans);
        return spans;
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private static List<StyledSpan> tokenize(String line, Pattern pattern, SyntaxLanguage language) {
        if (line == null || line.isEmpty()) {
            return List.of(new StyledSpan("", COLOR_DEFAULT));
        }

        Matcher matcher = pattern.matcher(line);
        List<StyledSpan> spans = new ArrayList<>();
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                spans.add(new StyledSpan(line.substring(lastEnd, matcher.start()), COLOR_DEFAULT));
            }

            String token = matcher.group();
            spans.add(new StyledSpan(token, resolveColor(language, matcher)));
            lastEnd = matcher.end();
        }

        if (lastEnd < line.length()) {
            spans.add(new StyledSpan(line.substring(lastEnd), COLOR_DEFAULT));
        }

        return spans;
    }

    private static boolean hasGroup(Matcher matcher, String name) {
        try {
            return matcher.group(name) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int resolveColor(SyntaxLanguage language, Matcher matcher) {
        return switch (language) {
            case JSON -> {
                if (hasGroup(matcher, "COMMENT")) yield JSON_COLOR_COMMENT;
                if (hasGroup(matcher, "KEY")) yield JSON_COLOR_OBJECT;
                if (hasGroup(matcher, "STRING")) yield JSON_COLOR_STRING;
                if (hasGroup(matcher, "ESCAPE")) yield JSON_COLOR_ESCAPE;
                if (hasGroup(matcher, "NUMBER")) yield JSON_COLOR_NUMBER;
                if (hasGroup(matcher, "BOOLEAN")) yield JSON_COLOR_BOOLEAN;
                if (hasGroup(matcher, "NULL")) yield JSON_COLOR_NULL;
                if (hasGroup(matcher, "OBJECT")) yield JSON_COLOR_OBJECT;
                if (hasGroup(matcher, "ARRAY")) yield JSON_COLOR_ARRAY;
                if (hasGroup(matcher, "COLON")) yield JSON_COLOR_OPERATOR;
                if (hasGroup(matcher, "COMMA")) yield JSON_COLOR_OPERATOR;
                yield COLOR_DEFAULT;
            }
            case JAVA -> {
                if (hasGroup(matcher, "COMMENT")) yield JAVA_COLOR_COMMENT;
                if (hasGroup(matcher, "JAVADOC")) yield JAVA_COLOR_JAVADOC;
                if (hasGroup(matcher, "ANNOTATION")) yield JAVA_COLOR_ANNOTATION;
                if (hasGroup(matcher, "STRING")) yield JAVA_COLOR_STRING;
                if (hasGroup(matcher, "CHAR")) yield JAVA_COLOR_CHAR;
                if (hasGroup(matcher, "ESCAPE")) yield JAVA_COLOR_ESCAPE;
                if (hasGroup(matcher, "KEYWORD")) yield JAVA_COLOR_KEYWORD;
                if (hasGroup(matcher, "TYPE")) yield JAVA_COLOR_TYPE;
                if (hasGroup(matcher, "METHOD")) yield JAVA_COLOR_METHOD;
                if (hasGroup(matcher, "IDENTIFIER")) yield JAVA_COLOR_IDENTIFIER;
                if (hasGroup(matcher, "BOOLEAN")) yield JAVA_COLOR_BOOLEAN;
                if (hasGroup(matcher, "NULL")) yield JAVA_COLOR_NULL;
                if (hasGroup(matcher, "NUMBER")) yield JAVA_COLOR_NUMBER;
                if (hasGroup(matcher, "OPERATOR")) yield JAVA_COLOR_OPERATOR;
                if (hasGroup(matcher, "BRACE")) yield JAVA_COLOR_BRACE;
                yield COLOR_DEFAULT;
            }
            case JAVASCRIPT -> {
                if (hasGroup(matcher, "COMMENT")) yield JAVASCRIPT_COLOR_COMMENT;
                if (hasGroup(matcher, "JSDOC")) yield JAVASCRIPT_COLOR_JAVADOC;
                if (hasGroup(matcher, "ANNOTATION")) yield JAVASCRIPT_COLOR_ANNOTATION;
                if (hasGroup(matcher, "STRING")) yield JAVASCRIPT_COLOR_STRING;
                if (hasGroup(matcher, "TEMPLATE")) yield JAVASCRIPT_COLOR_STRING;
                if (hasGroup(matcher, "REGEX")) yield JAVASCRIPT_COLOR_REGEX;
                if (hasGroup(matcher, "ESCAPE")) yield JAVASCRIPT_COLOR_ESCAPE;
                if (hasGroup(matcher, "KEYWORD")) yield JAVASCRIPT_COLOR_KEYWORD;
                if (hasGroup(matcher, "TYPE")) yield JAVASCRIPT_COLOR_TYPE;
                if (hasGroup(matcher, "FUNCTION")) yield JAVASCRIPT_COLOR_METHOD;
                if (hasGroup(matcher, "IDENTIFIER")) yield JAVASCRIPT_COLOR_IDENTIFIER;
                if (hasGroup(matcher, "BOOLEAN")) yield JAVASCRIPT_COLOR_BOOLEAN;
                if (hasGroup(matcher, "NULL")) yield JAVASCRIPT_COLOR_NULL;
                if (hasGroup(matcher, "NUMBER")) yield JAVASCRIPT_COLOR_NUMBER;
                if (hasGroup(matcher, "OPERATOR")) yield JAVASCRIPT_COLOR_OPERATOR;
                if (hasGroup(matcher, "BRACE")) yield JAVASCRIPT_COLOR_BRACE;
                yield COLOR_DEFAULT;
            }
            case PROPERTIES -> {
                if (hasGroup(matcher, "COMMENT")) yield PROPERTIES_COLOR_COMMENT;
                if (hasGroup(matcher, "SECTION")) yield PROPERTIES_COLOR_ANNOTATION;
                if (hasGroup(matcher, "KEY")) yield PROPERTIES_COLOR_IDENTIFIER;
                if (hasGroup(matcher, "SEPARATOR")) yield PROPERTIES_COLOR_OPERATOR;
                if (hasGroup(matcher, "UNICODE")) yield PROPERTIES_COLOR_ESCAPE;
                if (hasGroup(matcher, "ESCAPE")) yield PROPERTIES_COLOR_ESCAPE;
                if (hasGroup(matcher, "BOOLEAN")) yield PROPERTIES_COLOR_BOOLEAN;
                if (hasGroup(matcher, "NULL")) yield PROPERTIES_COLOR_NULL;
                if (hasGroup(matcher, "NUMBER")) yield PROPERTIES_COLOR_NUMBER;
                if (hasGroup(matcher, "STRING")) yield PROPERTIES_COLOR_STRING;
                yield SOFT_TEXT_COLOR;
            }
            case CONFIG -> {
                if (hasGroup(matcher, "COMMENT")) yield CONFIG_COLOR_COMMENT;
                if (hasGroup(matcher, "SECTION")) yield CONFIG_COLOR_ANNOTATION;
                if (hasGroup(matcher, "KEY")) yield CONFIG_COLOR_IDENTIFIER;
                if (hasGroup(matcher, "SEPARATOR")) yield CONFIG_COLOR_OPERATOR;
                if (hasGroup(matcher, "DIRECTIVE")) yield CONFIG_COLOR_KEYWORD;
                if (hasGroup(matcher, "PATH")) yield CONFIG_COLOR_PATH;
                if (hasGroup(matcher, "BOOLEAN")) yield CONFIG_COLOR_BOOLEAN;
                if (hasGroup(matcher, "NULL")) yield CONFIG_COLOR_NULL;
                if (hasGroup(matcher, "NUMBER")) yield CONFIG_COLOR_NUMBER;
                if (hasGroup(matcher, "STRING")) yield CONFIG_COLOR_STRING;
                yield SOFT_TEXT_COLOR;
            }
            case YAML -> {
                if (hasGroup(matcher, "COMMENT")) yield YAML_COLOR_COMMENT;
                if (hasGroup(matcher, "KEY")) yield YAML_COLOR_IDENTIFIER;
                if (hasGroup(matcher, "ANCHOR")) yield YAML_COLOR_ANNOTATION;
                if (hasGroup(matcher, "ALIAS")) yield YAML_COLOR_ANNOTATION;
                if (hasGroup(matcher, "TAG")) yield YAML_COLOR_KEYWORD;
                if (hasGroup(matcher, "DASH")) yield YAML_COLOR_OPERATOR;
                if (hasGroup(matcher, "COLON")) yield YAML_COLOR_OPERATOR;
                if (hasGroup(matcher, "BOOLEAN")) yield YAML_COLOR_BOOLEAN;
                if (hasGroup(matcher, "NULL")) yield YAML_COLOR_NULL;
                if (hasGroup(matcher, "NUMBER")) yield YAML_COLOR_NUMBER;
                if (hasGroup(matcher, "STRING")) yield YAML_COLOR_STRING;
                if (hasGroup(matcher, "MULTILINE")) yield YAML_COLOR_STRING;
                yield SOFT_TEXT_COLOR;
            }
            case KTL -> {
                if (hasGroup(matcher, "COMMENT")) yield KTL_COLOR_COMMENT;
                if (hasGroup(matcher, "PLACEHOLDER")) yield KTL_COLOR_STRING;
                if (hasGroup(matcher, "CAPABILITY")) yield KTL_COLOR_CAPABILITY;
                if (hasGroup(matcher, "EVALUATOR")) yield KTL_COLOR_EVALUATOR;
                if (hasGroup(matcher, "SEMANTIC")) yield KTL_COLOR_SEMANTIC;
                if (hasGroup(matcher, "MINECRAFT")) yield KTL_COLOR_MINECRAFT;
                if (hasGroup(matcher, "KIND")) yield KTL_COLOR_KEYWORD;
                if (hasGroup(matcher, "STEP")) yield KTL_COLOR_ANNOTATION;
                if (hasGroup(matcher, "KEY")) yield KTL_COLOR_IDENTIFIER;
                if (hasGroup(matcher, "DASH")) yield KTL_COLOR_OPERATOR;
                if (hasGroup(matcher, "COLON")) yield KTL_COLOR_OPERATOR;
                if (hasGroup(matcher, "BOOLEAN")) yield KTL_COLOR_BOOLEAN;
                if (hasGroup(matcher, "NULL")) yield KTL_COLOR_NULL;
                if (hasGroup(matcher, "NUMBER")) yield KTL_COLOR_NUMBER;
                if (hasGroup(matcher, "STRING")) yield KTL_COLOR_STRING;
                if (hasGroup(matcher, "MULTILINE")) yield KTL_COLOR_STRING;
                yield SOFT_TEXT_COLOR;
            }
            case TOML -> {
                if (hasGroup(matcher, "COMMENT")) yield TOML_COLOR_COMMENT;
                if (hasGroup(matcher, "SECTION")) yield TOML_COLOR_ANNOTATION;
                if (hasGroup(matcher, "ARRAYSECTION")) yield TOML_COLOR_ANNOTATION;
                if (hasGroup(matcher, "KEY")) yield TOML_COLOR_IDENTIFIER;
                if (hasGroup(matcher, "DOT")) yield TOML_COLOR_OPERATOR;
                if (hasGroup(matcher, "SEPARATOR")) yield TOML_COLOR_OPERATOR;
                if (hasGroup(matcher, "DATE")) yield TOML_COLOR_NUMBER;
                if (hasGroup(matcher, "BOOLEAN")) yield TOML_COLOR_BOOLEAN;
                if (hasGroup(matcher, "NULL")) yield TOML_COLOR_NULL;
                if (hasGroup(matcher, "NUMBER")) yield TOML_COLOR_NUMBER;
                if (hasGroup(matcher, "STRING")) yield TOML_COLOR_STRING;
                if (hasGroup(matcher, "MULTILINESTRING")) yield TOML_COLOR_STRING;
                yield SOFT_TEXT_COLOR;
            }
            case CSS -> {
                if (hasGroup(matcher, "COMMENT")) yield CSS_COLOR_COMMENT;
                if (hasGroup(matcher, "ATRULE")) yield CSS_COLOR_ATRULE;
                if (hasGroup(matcher, "HEX")) yield CSS_COLOR_HEX;
                if (hasGroup(matcher, "NUMBER")) yield CSS_COLOR_NUMBER;
                if (hasGroup(matcher, "STRING")) yield CSS_COLOR_STRING;
                if (hasGroup(matcher, "PROPERTY")) yield CSS_COLOR_PROPERTY;
                if (hasGroup(matcher, "FUNCTION")) yield CSS_COLOR_FUNCTION;
                if (hasGroup(matcher, "BRACE")) yield CSS_COLOR_BRACE;
                if (hasGroup(matcher, "OPERATOR")) yield CSS_COLOR_OPERATOR;
                yield COLOR_DEFAULT;
            }
            case C -> {
                if (hasGroup(matcher, "COMMENT")) yield C_COLOR_COMMENT;
                if (hasGroup(matcher, "PREPROCESSOR")) yield C_COLOR_PREPROCESSOR;
                if (hasGroup(matcher, "ANNOTATION")) yield C_COLOR_ANNOTATION;
                if (hasGroup(matcher, "MULTILINESTRING")) yield C_COLOR_STRING;
                if (hasGroup(matcher, "STRING")) yield C_COLOR_STRING;
                if (hasGroup(matcher, "CHAR")) yield C_COLOR_CHAR;
                if (hasGroup(matcher, "ESCAPE")) yield C_COLOR_ESCAPE;
                if (hasGroup(matcher, "KEYWORD")) yield C_COLOR_KEYWORD;
                if (hasGroup(matcher, "TYPE")) yield C_COLOR_TYPE;
                if (hasGroup(matcher, "FUNCTION")) yield C_COLOR_FUNCTION;
                if (hasGroup(matcher, "IDENTIFIER")) yield C_COLOR_IDENTIFIER;
                if (hasGroup(matcher, "BOOLEAN")) yield C_COLOR_BOOLEAN;
                if (hasGroup(matcher, "NULL")) yield C_COLOR_NULL;
                if (hasGroup(matcher, "NUMBER")) yield C_COLOR_NUMBER;
                if (hasGroup(matcher, "OPERATOR")) yield C_COLOR_OPERATOR;
                if (hasGroup(matcher, "BRACE")) yield C_COLOR_BRACE;
                yield COLOR_DEFAULT;
            }
            case CPP -> {
                if (hasGroup(matcher, "COMMENT")) yield CPP_COLOR_COMMENT;
                if (hasGroup(matcher, "PREPROCESSOR")) yield CPP_COLOR_PREPROCESSOR;
                if (hasGroup(matcher, "ANNOTATION")) yield CPP_COLOR_ANNOTATION;
                if (hasGroup(matcher, "MULTILINESTRING")) yield CPP_COLOR_STRING;
                if (hasGroup(matcher, "STRING")) yield CPP_COLOR_STRING;
                if (hasGroup(matcher, "CHAR")) yield CPP_COLOR_CHAR;
                if (hasGroup(matcher, "ESCAPE")) yield CPP_COLOR_ESCAPE;
                if (hasGroup(matcher, "KEYWORD")) yield CPP_COLOR_KEYWORD;
                if (hasGroup(matcher, "TYPE")) yield CPP_COLOR_TYPE;
                if (hasGroup(matcher, "FUNCTION")) yield CPP_COLOR_FUNCTION;
                if (hasGroup(matcher, "IDENTIFIER")) yield CPP_COLOR_IDENTIFIER;
                if (hasGroup(matcher, "BOOLEAN")) yield CPP_COLOR_BOOLEAN;
                if (hasGroup(matcher, "NULL")) yield CPP_COLOR_NULL;
                if (hasGroup(matcher, "NUMBER")) yield CPP_COLOR_NUMBER;
                if (hasGroup(matcher, "OPERATOR")) yield CPP_COLOR_OPERATOR;
                if (hasGroup(matcher, "BRACE")) yield CPP_COLOR_BRACE;
                yield COLOR_DEFAULT;
            }
            case CSHARP -> {
                if (hasGroup(matcher, "COMMENT")) yield CSHARP_COLOR_COMMENT;
                if (hasGroup(matcher, "PREPROCESSOR")) yield CSHARP_COLOR_PREPROCESSOR;
                if (hasGroup(matcher, "ANNOTATION")) yield CSHARP_COLOR_ANNOTATION;
                if (hasGroup(matcher, "MULTILINESTRING")) yield CSHARP_COLOR_STRING;
                if (hasGroup(matcher, "STRING")) yield CSHARP_COLOR_STRING;
                if (hasGroup(matcher, "CHAR")) yield CSHARP_COLOR_CHAR;
                if (hasGroup(matcher, "ESCAPE")) yield CSHARP_COLOR_ESCAPE;
                if (hasGroup(matcher, "KEYWORD")) yield CSHARP_COLOR_KEYWORD;
                if (hasGroup(matcher, "TYPE")) yield CSHARP_COLOR_TYPE;
                if (hasGroup(matcher, "FUNCTION")) yield CSHARP_COLOR_FUNCTION;
                if (hasGroup(matcher, "IDENTIFIER")) yield CSHARP_COLOR_IDENTIFIER;
                if (hasGroup(matcher, "BOOLEAN")) yield CSHARP_COLOR_BOOLEAN;
                if (hasGroup(matcher, "NULL")) yield CSHARP_COLOR_NULL;
                if (hasGroup(matcher, "NUMBER")) yield CSHARP_COLOR_NUMBER;
                if (hasGroup(matcher, "OPERATOR")) yield CSHARP_COLOR_OPERATOR;
                if (hasGroup(matcher, "BRACE")) yield CSHARP_COLOR_BRACE;
                yield COLOR_DEFAULT;
            }
            case PYTHON -> {
                if (hasGroup(matcher, "COMMENT")) yield PYTHON_COLOR_COMMENT;
                if (hasGroup(matcher, "PREPROCESSOR")) yield PYTHON_COLOR_PREPROCESSOR;
                if (hasGroup(matcher, "ANNOTATION")) yield PYTHON_COLOR_ANNOTATION;
                if (hasGroup(matcher, "MULTILINESTRING")) yield PYTHON_COLOR_STRING;
                if (hasGroup(matcher, "STRING")) yield PYTHON_COLOR_STRING;
                if (hasGroup(matcher, "CHAR")) yield PYTHON_COLOR_CHAR;
                if (hasGroup(matcher, "ESCAPE")) yield PYTHON_COLOR_ESCAPE;
                if (hasGroup(matcher, "KEYWORD")) yield PYTHON_COLOR_KEYWORD;
                if (hasGroup(matcher, "TYPE")) yield PYTHON_COLOR_TYPE;
                if (hasGroup(matcher, "FUNCTION")) yield PYTHON_COLOR_FUNCTION;
                if (hasGroup(matcher, "IDENTIFIER")) yield PYTHON_COLOR_IDENTIFIER;
                if (hasGroup(matcher, "BOOLEAN")) yield PYTHON_COLOR_BOOLEAN;
                if (hasGroup(matcher, "NULL")) yield PYTHON_COLOR_NULL;
                if (hasGroup(matcher, "NUMBER")) yield PYTHON_COLOR_NUMBER;
                if (hasGroup(matcher, "OPERATOR")) yield PYTHON_COLOR_OPERATOR;
                if (hasGroup(matcher, "BRACE")) yield PYTHON_COLOR_BRACE;
                yield COLOR_DEFAULT;
            }
            case SHADER -> {
                if (hasGroup(matcher, "COMMENT")) yield SHADER_COLOR_COMMENT;
                if (hasGroup(matcher, "PREPROCESSOR")) yield SHADER_COLOR_PREPROCESSOR;
                if (hasGroup(matcher, "QUALIFIER")) yield SHADER_COLOR_QUALIFIER;
                if (hasGroup(matcher, "KEYWORD")) yield SHADER_COLOR_KEYWORD;
                if (hasGroup(matcher, "FUNCTION")) yield SHADER_COLOR_FUNCTION;
                if (hasGroup(matcher, "NUMBER")) yield SHADER_COLOR_NUMBER;
                if (hasGroup(matcher, "STRING")) yield SHADER_COLOR_STRING;
                if (hasGroup(matcher, "OPERATOR")) yield SHADER_COLOR_OPERATOR;
                if (hasGroup(matcher, "BRACE")) yield SHADER_COLOR_BRACE;
                yield COLOR_DEFAULT;
            }
            case XML -> {
                if (hasGroup(matcher, "COMMENT")) yield XML_COLOR_COMMENT;
                if (hasGroup(matcher, "PROLOG")) yield XML_COLOR_ANNOTATION;
                if (hasGroup(matcher, "DOCTYPE")) yield XML_COLOR_KEYWORD;
                if (hasGroup(matcher, "CDATA")) yield XML_COLOR_STRING;
                if (hasGroup(matcher, "ENTITY")) yield XML_COLOR_ESCAPE;
                if (hasGroup(matcher, "TAG")) yield XML_COLOR_KEYWORD;
                if (hasGroup(matcher, "ATTR")) yield XML_COLOR_IDENTIFIER;
                if (hasGroup(matcher, "STRING")) yield XML_COLOR_STRING;
                if (hasGroup(matcher, "BRACE")) yield XML_COLOR_BRACE;
                yield COLOR_DEFAULT;
            }
            case MARKDOWN -> {
                if (hasGroup(matcher, "HEADING")) yield MARKDOWN_COLOR_KEYWORD;
                if (hasGroup(matcher, "LIST")) yield MARKDOWN_COLOR_IDENTIFIER;
                if (hasGroup(matcher, "QUOTE")) yield MARKDOWN_COLOR_COMMENT;
                if (hasGroup(matcher, "CODE_BLOCK")) yield MARKDOWN_COLOR_STRING;
                if (hasGroup(matcher, "CODE")) yield MARKDOWN_COLOR_STRING;
                if (hasGroup(matcher, "BOLD")) yield MARKDOWN_COLOR_OBJECT;
                if (hasGroup(matcher, "ITALIC")) yield MARKDOWN_COLOR_OBJECT;
                if (hasGroup(matcher, "EMPHASIS")) yield MARKDOWN_COLOR_OBJECT;
                if (hasGroup(matcher, "LINK")) yield MARKDOWN_COLOR_ANNOTATION;
                if (hasGroup(matcher, "URL")) yield MARKDOWN_COLOR_ANNOTATION;
                yield COLOR_DEFAULT;
            }
            case LOG -> {
                if (hasGroup(matcher, "TIMESTAMP")) yield LOG_COLOR_NUMBER;
                if (hasGroup(matcher, "LEVEL")) yield LOG_COLOR_KEYWORD;
                if (hasGroup(matcher, "LOGGER")) yield LOG_COLOR_IDENTIFIER;
                if (hasGroup(matcher, "THREAD")) yield LOG_COLOR_ANNOTATION;
                if (hasGroup(matcher, "CLASS")) yield LOG_COLOR_IDENTIFIER;
                if (hasGroup(matcher, "NUMBER")) yield LOG_COLOR_NUMBER;
                if (hasGroup(matcher, "MESSAGE")) yield LOG_COLOR_STRING;
                yield SOFT_TEXT_COLOR;
            }
            case TEXT -> COLOR_DEFAULT;
        };
    }

    public record StyledSpan(String text, int color) {}

    private record CacheKey(SyntaxLanguage language, String line) {}

    public enum SyntaxLanguage {
        JSON,
        JAVA,
        PROPERTIES,
        CONFIG,
        XML,
        YAML,
        KTL,
        MARKDOWN,
        JAVASCRIPT,
        TOML,
        CSS,
        C,
        CPP,
        CSHARP,
        PYTHON,
        SHADER,
        LOG,
        TEXT;

        public static SyntaxLanguage fromFileName(String fileName) {
            if (fileName == null) {
                return TEXT;
            }

            String lower = fileName.toLowerCase();
            if (lower.endsWith(".disabled")) {
                lower = lower.substring(0, lower.length() - ".disabled".length());
            }
            if (lower.endsWith(".json") || lower.endsWith(".json5") || lower.endsWith(".mcmeta")) return JSON;
            if (lower.endsWith(".java") || lower.endsWith(".class")) return JAVA;
            if (lower.endsWith(".properties")) return PROPERTIES;
            if (lower.endsWith(".cfg") || lower.endsWith(".conf") || lower.endsWith(".ini") || lower.endsWith(".dat")) return CONFIG;
            if (lower.endsWith(".xml")) return XML;
            if (lower.endsWith(".ktl")) return KTL;
            if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return YAML;
            if (lower.endsWith(".md") || lower.endsWith(".markdown")) return MARKDOWN;
            if (lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs") || lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".jsx")) return JAVASCRIPT;
            if (lower.endsWith(".toml")) return TOML;
            if (lower.endsWith(".css") || lower.endsWith(".scss") || lower.endsWith(".sass") || lower.endsWith(".less")) return CSS;
            if (lower.endsWith(".c") || lower.endsWith(".h")) return C;
            if (lower.endsWith(".cc") || lower.endsWith(".cpp") || lower.endsWith(".cxx") || lower.endsWith(".hpp") || lower.endsWith(".hh")) return CPP;
            if (lower.endsWith(".cs")) return CSHARP;
            if (lower.endsWith(".py") || lower.endsWith(".pyw")) return PYTHON;
            if (lower.endsWith(".glsl") || lower.endsWith(".vert") || lower.endsWith(".frag") || lower.endsWith(".geom")
                    || lower.endsWith(".comp") || lower.endsWith(".vsh") || lower.endsWith(".fsh") || lower.endsWith(".placebo")) return SHADER;
            if (lower.endsWith(".log")) return LOG;
            return TEXT;
        }
    }
}
