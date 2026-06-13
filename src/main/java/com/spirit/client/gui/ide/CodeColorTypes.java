package com.spirit.client.gui.ide;

import java.awt.*;

import static com.spirit.koil.api.design.uiColorVal.*;

public class CodeColorTypes {
    public static final String JSON_COMMENT_PATTERN = "(?<COMMENT>//[^\\n]*|/\\*(?:.|\\R)*?\\*/)";
    public static final String JSON_KEY_PATTERN = "(?<KEY>\"(?:\\\\.|[^\"\\\\])*\"\\s*:)";
    public static final String JSON_STRING_PATTERN = "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\")";
    public static final String JSON_ESCAPE_PATTERN = "(?<ESCAPE>\\\\(?:[\"\\\\/bfnrt]|u[0-9a-fA-F]{4}))";
    public static final String JSON_NUMBER_PATTERN = "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)";
    public static final String JSON_BOOLEAN_PATTERN = "(?<BOOLEAN>\\btrue\\b|\\bfalse\\b)";
    public static final String JSON_NULL_PATTERN = "(?<NULL>\\bnull\\b)";
    public static final String JSON_OBJECT_PATTERN = "(?<OBJECT>[{}])";
    public static final String JSON_ARRAY_PATTERN = "(?<ARRAY>[\\[\\]])";
    public static final String JSON_COLON_PATTERN = "(?<COLON>:)";
    public static final String JSON_COMMA_PATTERN = "(?<COMMA>,)";
    public static final String JSON_COMBINED_PATTERN = String.join("|",
            JSON_COMMENT_PATTERN,
            JSON_KEY_PATTERN,
            JSON_STRING_PATTERN,
            JSON_ESCAPE_PATTERN,
            JSON_NUMBER_PATTERN,
            JSON_BOOLEAN_PATTERN,
            JSON_NULL_PATTERN,
            JSON_OBJECT_PATTERN,
            JSON_ARRAY_PATTERN,
            JSON_COLON_PATTERN,
            JSON_COMMA_PATTERN
    );

    public static final String JAVA_COMMENT_PATTERN = "(?<COMMENT>//[^\\n]*|/\\*(?:.|\\R)*?\\*/)";
    public static final String JAVA_JAVADOC_PATTERN = "(?<JAVADOC>/\\*\\*(?:.|\\R)*?\\*/)";
    public static final String JAVA_ANNOTATION_PATTERN = "(?<ANNOTATION>@\\w+)";
    public static final String JAVA_STRING_PATTERN = "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\")";
    public static final String JAVA_CHAR_PATTERN = "(?<CHAR>'(?:\\\\.|[^'\\\\])')";
    public static final String JAVA_ESCAPE_PATTERN = "(?<ESCAPE>\\\\(?:[btnfr\"'\\\\]|u[0-9a-fA-F]{4}))";
    public static final String JAVA_KEYWORD_PATTERN = "(?<KEYWORD>\\b(public|protected|private|static|final|abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|extends|finally|float|for|goto|if|implements|import|instanceof|int|interface|long|native|new|null|package|return|short|strictfp|super|switch|synchronized|this|throw|throws|transient|try|void|volatile|while)\\b)";
    public static final String JAVA_TYPE_PATTERN = "(?<TYPE>\\b(String|Integer|Boolean|Character|Byte|Short|Long|Float|Double|Void|Object|List|Map|Set|ArrayList|HashMap|HashSet|Optional)\\b)";
    public static final String JAVA_METHOD_PATTERN = "(?<METHOD>\\b[a-zA-Z_][a-zA-Z0-9_]*\\s*(?=\\())";
    public static final String JAVA_IDENTIFIER_PATTERN = "(?<IDENTIFIER>\\b[a-zA-Z_][a-zA-Z0-9_]*\\b)";
    public static final String JAVA_BOOLEAN_PATTERN = "(?<BOOLEAN>\\btrue\\b|\\bfalse\\b)";
    public static final String JAVA_NULL_PATTERN = "(?<NULL>\\bnull\\b)";
    public static final String JAVA_NUMBER_PATTERN = "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[fFdDlL]?\\b)";
    public static final String JAVA_OPERATOR_PATTERN = "(?<OPERATOR>==|!=|<=|>=|&&|\\|\\||[+\\-*/%!=<>?:&|^~])";
    public static final String JAVA_BRACE_PATTERN = "(?<BRACE>[{}()\\[\\]])";
    public static final String JAVA_COMBINED_PATTERN = String.join("|",
            JAVA_JAVADOC_PATTERN,
            JAVA_COMMENT_PATTERN,
            JAVA_ANNOTATION_PATTERN,
            JAVA_STRING_PATTERN,
            JAVA_CHAR_PATTERN,
            JAVA_ESCAPE_PATTERN,
            JAVA_KEYWORD_PATTERN,
            JAVA_TYPE_PATTERN,
            JAVA_METHOD_PATTERN,
            JAVA_BOOLEAN_PATTERN,
            JAVA_NULL_PATTERN,
            JAVA_NUMBER_PATTERN,
            JAVA_OPERATOR_PATTERN,
            JAVA_BRACE_PATTERN,
            JAVA_IDENTIFIER_PATTERN
    );

    public static final String JAVASCRIPT_COMMENT_PATTERN = "(?<COMMENT>//[^\\n]*|/\\*(?:.|\\R)*?\\*/)";
    public static final String JAVASCRIPT_JSDOC_PATTERN = "(?<JSDOC>/\\*\\*(?:.|\\R)*?\\*/)";
    public static final String JAVASCRIPT_ANNOTATION_PATTERN = "(?<ANNOTATION>@\\w+)";
    public static final String JAVASCRIPT_STRING_PATTERN = "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')";
    public static final String JAVASCRIPT_TEMPLATE_PATTERN = "(?<TEMPLATE>`(?:\\\\.|[^`\\\\])*`)";
    public static final String JAVASCRIPT_REGEX_PATTERN = "(?<REGEX>/([^/\\\\\\n]|\\\\.)+/[gimsuyd]*)";
    public static final String JAVASCRIPT_ESCAPE_PATTERN = "(?<ESCAPE>\\\\(?:[btnfr\"'`\\\\]|u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}))";
    public static final String JAVASCRIPT_KEYWORD_PATTERN = "(?<KEYWORD>\\b(await|break|case|catch|class|const|continue|debugger|default|delete|do|else|export|extends|finally|for|function|if|import|in|instanceof|let|new|return|super|switch|this|throw|try|typeof|var|void|while|with|yield)\\b)";
    public static final String JAVASCRIPT_TYPE_PATTERN = "(?<TYPE>\\b(Array|Boolean|Date|Error|Function|JSON|Map|Math|Number|Object|Promise|RegExp|Set|String)\\b)";
    public static final String JAVASCRIPT_FUNCTION_PATTERN = "(?<FUNCTION>\\b[a-zA-Z_$][a-zA-Z0-9_$]*\\s*(?=\\())";
    public static final String JAVASCRIPT_IDENTIFIER_PATTERN = "(?<IDENTIFIER>\\b[a-zA-Z_$][a-zA-Z0-9_$]*\\b)";
    public static final String JAVASCRIPT_BOOLEAN_PATTERN = "(?<BOOLEAN>\\btrue\\b|\\bfalse\\b)";
    public static final String JAVASCRIPT_NULL_PATTERN = "(?<NULL>\\bnull\\b|\\bundefined\\b)";
    public static final String JAVASCRIPT_NUMBER_PATTERN = "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)";
    public static final String JAVASCRIPT_OPERATOR_PATTERN = "(?<OPERATOR>===|!==|==|!=|<=|>=|&&|\\|\\||=>|[+\\-*/%!=<>?:&|^~])";
    public static final String JAVASCRIPT_BRACE_PATTERN = "(?<BRACE>[{}()\\[\\]])";
    public static final String JAVASCRIPT_COMBINED_PATTERN = String.join("|",
            JAVASCRIPT_JSDOC_PATTERN,
            JAVASCRIPT_COMMENT_PATTERN,
            JAVASCRIPT_ANNOTATION_PATTERN,
            JAVASCRIPT_TEMPLATE_PATTERN,
            JAVASCRIPT_REGEX_PATTERN,
            JAVASCRIPT_STRING_PATTERN,
            JAVASCRIPT_ESCAPE_PATTERN,
            JAVASCRIPT_KEYWORD_PATTERN,
            JAVASCRIPT_TYPE_PATTERN,
            JAVASCRIPT_FUNCTION_PATTERN,
            JAVASCRIPT_BOOLEAN_PATTERN,
            JAVASCRIPT_NULL_PATTERN,
            JAVASCRIPT_NUMBER_PATTERN,
            JAVASCRIPT_OPERATOR_PATTERN,
            JAVASCRIPT_BRACE_PATTERN,
            JAVASCRIPT_IDENTIFIER_PATTERN
    );

    public static final String PROPERTIES_COMMENT_PATTERN = "(?<COMMENT>[#!][^\\n]*)";
    public static final String PROPERTIES_SECTION_PATTERN = "(?<SECTION>\\[[^\\]\\n]+\\])";
    public static final String PROPERTIES_KEY_PATTERN = "(?<KEY>^[ \\t]*[A-Za-z0-9_.-]+(?=[ \\t]*[=:]))";
    public static final String PROPERTIES_SEPARATOR_PATTERN = "(?<SEPARATOR>[=:])";
    public static final String PROPERTIES_UNICODE_PATTERN = "(?<UNICODE>\\\\u[0-9a-fA-F]{4})";
    public static final String PROPERTIES_ESCAPE_PATTERN = "(?<ESCAPE>\\\\[\\\\:=\\s#!tnrf])";
    public static final String PROPERTIES_BOOLEAN_PATTERN = "(?<BOOLEAN>\\btrue\\b|\\bfalse\\b)";
    public static final String PROPERTIES_NULL_PATTERN = "(?<NULL>\\bnull\\b)";
    public static final String PROPERTIES_NUMBER_PATTERN = "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?\\b)";
    public static final String PROPERTIES_STRING_PATTERN = "(?<STRING>.+$)";
    public static final String PROPERTIES_COMBINED_PATTERN = String.join("|",
            PROPERTIES_COMMENT_PATTERN,
            PROPERTIES_SECTION_PATTERN,
            PROPERTIES_KEY_PATTERN,
            PROPERTIES_SEPARATOR_PATTERN,
            PROPERTIES_UNICODE_PATTERN,
            PROPERTIES_ESCAPE_PATTERN,
            PROPERTIES_BOOLEAN_PATTERN,
            PROPERTIES_NULL_PATTERN,
            PROPERTIES_NUMBER_PATTERN,
            PROPERTIES_STRING_PATTERN
    );

    public static final String CONFIG_COMMENT_PATTERN = "(?<COMMENT>[#;][^\\n]*)";
    public static final String CONFIG_SECTION_PATTERN = "(?<SECTION>\\[[^\\]\\n]+\\])";
    public static final String CONFIG_KEY_PATTERN = "(?<KEY>^[ \\t]*[A-Za-z0-9_.-]+(?=[ \\t]*=))";
    public static final String CONFIG_SEPARATOR_PATTERN = "(?<SEPARATOR>=)";
    public static final String CONFIG_DIRECTIVE_PATTERN = "(?<DIRECTIVE>\\b(include|define|set|unset)\\b)";
    public static final String CONFIG_PATH_PATTERN = "(?<PATH>(?:/|\\./|\\.\\./)[^\\s#;]+)";
    public static final String CONFIG_BOOLEAN_PATTERN = "(?<BOOLEAN>\\btrue\\b|\\bfalse\\b|\\byes\\b|\\bno\\b|\\bon\\b|\\boff\\b)";
    public static final String CONFIG_NULL_PATTERN = "(?<NULL>\\bnull\\b|\\bnone\\b)";
    public static final String CONFIG_NUMBER_PATTERN = "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?\\b)";
    public static final String CONFIG_STRING_PATTERN = "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')";
    public static final String CONFIG_COMBINED_PATTERN = String.join("|",
            CONFIG_COMMENT_PATTERN,
            CONFIG_SECTION_PATTERN,
            CONFIG_KEY_PATTERN,
            CONFIG_SEPARATOR_PATTERN,
            CONFIG_DIRECTIVE_PATTERN,
            CONFIG_PATH_PATTERN,
            CONFIG_BOOLEAN_PATTERN,
            CONFIG_NULL_PATTERN,
            CONFIG_NUMBER_PATTERN,
            CONFIG_STRING_PATTERN
    );

    public static final String YAML_COMMENT_PATTERN = "(?<COMMENT>#[^\\n]*)";
    public static final String YAML_KEY_PATTERN = "(?<KEY>\\b[A-Za-z0-9_.-]+(?=\\s*:))";
    public static final String YAML_ANCHOR_PATTERN = "(?<ANCHOR>&[A-Za-z0-9_.-]+)";
    public static final String YAML_ALIAS_PATTERN = "(?<ALIAS>\\*[A-Za-z0-9_.-]+)";
    public static final String YAML_TAG_PATTERN = "(?<TAG>![A-Za-z0-9_.:-]+)";
    public static final String YAML_DASH_PATTERN = "(?<DASH>^\\s*-)";
    public static final String YAML_COLON_PATTERN = "(?<COLON>:)";
    public static final String YAML_BOOLEAN_PATTERN = "(?<BOOLEAN>\\btrue\\b|\\bfalse\\b|\\byes\\b|\\bno\\b|\\bon\\b|\\boff\\b)";
    public static final String YAML_NULL_PATTERN = "(?<NULL>\\bnull\\b|~)";
    public static final String YAML_NUMBER_PATTERN = "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?\\b)";
    public static final String YAML_MULTILINE_PATTERN = "(?<MULTILINE>[|>])";
    public static final String YAML_STRING_PATTERN = "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')";
    public static final String YAML_COMBINED_PATTERN = String.join("|",
            YAML_COMMENT_PATTERN,
            YAML_KEY_PATTERN,
            YAML_ANCHOR_PATTERN,
            YAML_ALIAS_PATTERN,
            YAML_TAG_PATTERN,
            YAML_DASH_PATTERN,
            YAML_COLON_PATTERN,
            YAML_BOOLEAN_PATTERN,
            YAML_NULL_PATTERN,
            YAML_NUMBER_PATTERN,
            YAML_MULTILINE_PATTERN,
            YAML_STRING_PATTERN
    );

    public static final String TOML_COMMENT_PATTERN = "(?<COMMENT>#[^\\n]*)";
    public static final String TOML_SECTION_PATTERN = "(?<SECTION>\\[[A-Za-z0-9_.-]+\\])";
    public static final String TOML_ARRAY_SECTION_PATTERN = "(?<ARRAY_SECTION>\\[\\[[A-Za-z0-9_.-]+\\]\\])";
    public static final String TOML_KEY_PATTERN = "(?<KEY>\\b[A-Za-z0-9_.-]+\\b(?=\\s*=))";
    public static final String TOML_DOT_PATTERN = "(?<DOT>\\.)";
    public static final String TOML_SEPARATOR_PATTERN = "(?<SEPARATOR>=)";
    public static final String TOML_DATE_PATTERN = "(?<DATE>\\b\\d{4}-\\d{2}-\\d{2}(?:[Tt ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:\\d{2})?\\b)";
    public static final String TOML_BOOLEAN_PATTERN = "(?<BOOLEAN>\\btrue\\b|\\bfalse\\b)";
    public static final String TOML_NULL_PATTERN = "(?<NULL>\\bnull\\b)";
    public static final String TOML_NUMBER_PATTERN = "(?<NUMBER>-?\\b\\d+(?:_\\d+)*(?:\\.\\d+(?:_\\d+)*)?\\b)";
    public static final String TOML_MULTILINE_STRING_PATTERN = "(?<MULTILINE_STRING>\"\"\"(?:.|\\R)*?\"\"\"|'''(?:.|\\R)*?''')";
    public static final String TOML_STRING_PATTERN = "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')";
    public static final String TOML_COMBINED_PATTERN = String.join("|",
            TOML_COMMENT_PATTERN,
            TOML_ARRAY_SECTION_PATTERN,
            TOML_SECTION_PATTERN,
            TOML_KEY_PATTERN,
            TOML_DOT_PATTERN,
            TOML_SEPARATOR_PATTERN,
            TOML_DATE_PATTERN,
            TOML_BOOLEAN_PATTERN,
            TOML_NULL_PATTERN,
            TOML_NUMBER_PATTERN,
            TOML_MULTILINE_STRING_PATTERN,
            TOML_STRING_PATTERN
    );

    public static final String XML_COMMENT_PATTERN = "(?<COMMENT><!--(?:.|\\R)*?-->)";
    public static final String XML_PROLOG_PATTERN = "(?<PROLOG><\\?.*?\\?>)";
    public static final String XML_DOCTYPE_PATTERN = "(?<DOCTYPE><!DOCTYPE[^>]*>)";
    public static final String XML_CDATA_PATTERN = "(?<CDATA><!\\[CDATA\\[(?:.|\\R)*?\\]\\]>)";
    public static final String XML_ENTITY_PATTERN = "(?<ENTITY>&[A-Za-z0-9#]+;)";
    public static final String XML_TAG_PATTERN = "(?<TAG></?[A-Za-z_:][A-Za-z0-9_.:-]*)";
    public static final String XML_ATTR_PATTERN = "(?<ATTR>\\b[A-Za-z_:][A-Za-z0-9_.:-]*(?=\\s*=))";
    public static final String XML_STRING_PATTERN = "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')";
    public static final String XML_BRACE_PATTERN = "(?<BRACE>/?>|<|</|\\?>)";
    public static final String XML_COMBINED_PATTERN = String.join("|",
            XML_COMMENT_PATTERN,
            XML_PROLOG_PATTERN,
            XML_DOCTYPE_PATTERN,
            XML_CDATA_PATTERN,
            XML_ENTITY_PATTERN,
            XML_TAG_PATTERN,
            XML_ATTR_PATTERN,
            XML_STRING_PATTERN,
            XML_BRACE_PATTERN
    );

    public static final String MARKDOWN_HEADING_PATTERN = "(?<HEADING>^#{1,6}\\s.*$)";
    public static final String MARKDOWN_LIST_PATTERN = "(?<LIST>^\\s*(?:[-*+]\\s|\\d+\\.\\s).*$)";
    public static final String MARKDOWN_QUOTE_PATTERN = "(?<QUOTE>^>\\s.*$)";
    public static final String MARKDOWN_CODE_BLOCK_PATTERN = "(?<CODE_BLOCK>```(?:.|\\R)*?```)";
    public static final String MARKDOWN_CODE_PATTERN = "(?<CODE>`[^`]+`)";
    public static final String MARKDOWN_BOLD_PATTERN = "(?<BOLD>\\*\\*[^*]+\\*\\*|__[^_]+__)";
    public static final String MARKDOWN_ITALIC_PATTERN = "(?<ITALIC>\\*[^*]+\\*|_[^_]+_)";
    public static final String MARKDOWN_EMPHASIS_PATTERN = "(?<EMPHASIS>\\*{1,3}[^*]+\\*{1,3}|_{1,3}[^_]+_{1,3})";
    public static final String MARKDOWN_LINK_PATTERN = "(?<LINK>\\[[^\\]]+\\]\\([^\\)]+\\))";
    public static final String MARKDOWN_URL_PATTERN = "(?<URL>https?://\\S+)";
    public static final String MARKDOWN_COMBINED_PATTERN = String.join("|",
            MARKDOWN_CODE_BLOCK_PATTERN,
            MARKDOWN_HEADING_PATTERN,
            MARKDOWN_LIST_PATTERN,
            MARKDOWN_QUOTE_PATTERN,
            MARKDOWN_CODE_PATTERN,
            MARKDOWN_BOLD_PATTERN,
            MARKDOWN_ITALIC_PATTERN,
            MARKDOWN_EMPHASIS_PATTERN,
            MARKDOWN_LINK_PATTERN,
            MARKDOWN_URL_PATTERN
    );

    public static final String LOG_TIMESTAMP_PATTERN = "(?<TIMESTAMP>\\b\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:,\\d{3}|\\.\\d{3})?\\b)";
    public static final String LOG_LEVEL_PATTERN = "(?<LEVEL>\\bTRACE\\b|\\bDEBUG\\b|\\bINFO\\b|\\bWARN\\b|\\bERROR\\b|\\bFATAL\\b)";
    public static final String LOG_LOGGER_PATTERN = "(?<LOGGER>\\b[a-zA-Z_][\\w.]*\\b)";
    public static final String LOG_THREAD_PATTERN = "(?<THREAD>\\[[^\\]]+\\])";
    public static final String LOG_CLASS_PATTERN = "(?<CLASS>\\b[A-Z][A-Za-z0-9_$.]*\\b)";
    public static final String LOG_NUMBER_PATTERN = "(?<NUMBER>-?\\b\\d+(?:\\.\\d+)?\\b)";
    public static final String LOG_MESSAGE_PATTERN = "(?<MESSAGE>.+)";
    public static final String LOG_COMBINED_PATTERN = String.join("|",
            LOG_TIMESTAMP_PATTERN,
            LOG_LEVEL_PATTERN,
            LOG_LOGGER_PATTERN,
            LOG_THREAD_PATTERN,
            LOG_CLASS_PATTERN,
            LOG_NUMBER_PATTERN,
            LOG_MESSAGE_PATTERN
    );

    public static final int JSON_COLOR_STRING = new Color(uiColorIDEjsonColorStringText).getRGB();
    public static final int JSON_COLOR_NUMBER = new Color(uiColorIDEjsonColorNumberText).getRGB();
    public static final int JSON_COLOR_OBJECT = new Color(uiColorIDEjsonColorObjectText).getRGB();
    public static final int JSON_COLOR_ARRAY = new Color(uiColorIDEjsonColorArrayText).getRGB();
    public static final int JSON_COLOR_BOOLEAN = new Color(uiColorIDEjsonColorBooleanText).getRGB();
    public static final int JSON_COLOR_NULL = new Color(uiColorIDEjsonColorNullText).getRGB();
    public static final int JSON_COLOR_COMMENT = new Color(uiColorIDEjsonColorCommentText).getRGB();
    public static final int JSON_COLOR_ESCAPE = new Color(uiColorIDEjsonColorEscapeText).getRGB();
    public static final int JSON_COLOR_OPERATOR = new Color(uiColorIDEjsonColorOperatorText).getRGB();

    public static final int JAVA_COLOR_IDENTIFIER = new Color(uiColorIDEjavaColorIdentifierText).getRGB();
    public static final int JAVA_COLOR_STRING = new Color(uiColorIDEjavaColorStringText).getRGB();
    public static final int JAVA_COLOR_CHAR = new Color(uiColorIDEjavaColorCharText).getRGB();
    public static final int JAVA_COLOR_NUMBER = new Color(uiColorIDEjavaColorNumberText).getRGB();
    public static final int JAVA_COLOR_KEYWORD = new Color(uiColorIDEjavaColorKeywordText).getRGB();
    public static final int JAVA_COLOR_BRACE = new Color(uiColorIDEjavaColorBraceText).getRGB();
    public static final int JAVA_COLOR_BRACKET = new Color(uiColorIDEjavaColorBracketText).getRGB();
    public static final int JAVA_COLOR_BOOLEAN = new Color(uiColorIDEjavaColorBooleanText).getRGB();
    public static final int JAVA_COLOR_COMMENT = new Color(uiColorIDEjavaColorCommentText).getRGB();
    public static final int JAVA_COLOR_ANNOTATION = new Color(uiColorIDEjavaColorAnnotationText).getRGB();
    public static final int JAVA_COLOR_NULL = new Color(uiColorIDEjavaColorNullText).getRGB();
    public static final int JAVA_COLOR_JAVADOC = new Color(uiColorIDEjavaColorJavadocText).getRGB();
    public static final int JAVA_COLOR_ESCAPE = new Color(uiColorIDEjavaColorEscapeText).getRGB();
    public static final int JAVA_COLOR_TYPE = new Color(uiColorIDEjavaColorTypeText).getRGB();
    public static final int JAVA_COLOR_METHOD = new Color(uiColorIDEjavaColorMethodText).getRGB();
    public static final int JAVA_COLOR_OPERATOR = new Color(uiColorIDEjavaColorOperatorText).getRGB();

    public static final int JAVASCRIPT_COLOR_COMMENT = new Color(uiColorIDEjavascriptColorCommentText).getRGB();
    public static final int JAVASCRIPT_COLOR_JAVADOC = new Color(uiColorIDEjavascriptColorJavadocText).getRGB();
    public static final int JAVASCRIPT_COLOR_ANNOTATION = new Color(uiColorIDEjavascriptColorAnnotationText).getRGB();
    public static final int JAVASCRIPT_COLOR_STRING = new Color(uiColorIDEjavascriptColorStringText).getRGB();
    public static final int JAVASCRIPT_COLOR_REGEX = new Color(uiColorIDEjavascriptColorRegexText).getRGB();
    public static final int JAVASCRIPT_COLOR_ESCAPE = new Color(uiColorIDEjavascriptColorEscapeText).getRGB();
    public static final int JAVASCRIPT_COLOR_KEYWORD = new Color(uiColorIDEjavascriptColorKeywordText).getRGB();
    public static final int JAVASCRIPT_COLOR_TYPE = new Color(uiColorIDEjavascriptColorTypeText).getRGB();
    public static final int JAVASCRIPT_COLOR_METHOD = new Color(uiColorIDEjavascriptColorMethodText).getRGB();
    public static final int JAVASCRIPT_COLOR_IDENTIFIER = new Color(uiColorIDEjavascriptColorIdentifierText).getRGB();
    public static final int JAVASCRIPT_COLOR_BOOLEAN = new Color(uiColorIDEjavascriptColorBooleanText).getRGB();
    public static final int JAVASCRIPT_COLOR_NULL = new Color(uiColorIDEjavascriptColorNullText).getRGB();
    public static final int JAVASCRIPT_COLOR_NUMBER = new Color(uiColorIDEjavascriptColorNumberText).getRGB();
    public static final int JAVASCRIPT_COLOR_OPERATOR = new Color(uiColorIDEjavascriptColorOperatorText).getRGB();
    public static final int JAVASCRIPT_COLOR_BRACE = new Color(uiColorIDEjavascriptColorBraceText).getRGB();

    public static final int PROPERTIES_COLOR_IDENTIFIER = new Color(uiColorIDEpropertiesColorIdentifierText).getRGB();
    public static final int PROPERTIES_COLOR_STRING = new Color(uiColorIDEpropertiesColorStringText).getRGB();
    public static final int PROPERTIES_COLOR_CHAR = new Color(uiColorIDEpropertiesColorCharText).getRGB();
    public static final int PROPERTIES_COLOR_NUMBER = new Color(uiColorIDEpropertiesColorNumberText).getRGB();
    public static final int PROPERTIES_COLOR_KEYWORD = new Color(uiColorIDEpropertiesColorKeywordText).getRGB();
    public static final int PROPERTIES_COLOR_BRACE = new Color(uiColorIDEpropertiesColorBraceText).getRGB();
    public static final int PROPERTIES_COLOR_BRACKET = new Color(uiColorIDEpropertiesColorBracketText).getRGB();
    public static final int PROPERTIES_COLOR_BOOLEAN = new Color(uiColorIDEpropertiesColorBooleanText).getRGB();
    public static final int PROPERTIES_COLOR_COMMENT = new Color(uiColorIDEpropertiesColorCommentText).getRGB();
    public static final int PROPERTIES_COLOR_ANNOTATION = new Color(uiColorIDEpropertiesColorAnnotationText).getRGB();
    public static final int PROPERTIES_COLOR_NULL = new Color(uiColorIDEpropertiesColorNullText).getRGB();
    public static final int PROPERTIES_COLOR_OPERATOR = new Color(uiColorIDEpropertiesColorOperatorText).getRGB();
    public static final int PROPERTIES_COLOR_ESCAPE = new Color(uiColorIDEpropertiesColorEscapeText).getRGB();

    public static final int CONFIG_COLOR_COMMENT = new Color(uiColorIDEconfigColorCommentText).getRGB();
    public static final int CONFIG_COLOR_ANNOTATION = new Color(uiColorIDEconfigColorAnnotationText).getRGB();
    public static final int CONFIG_COLOR_IDENTIFIER = new Color(uiColorIDEconfigColorIdentifierText).getRGB();
    public static final int CONFIG_COLOR_OPERATOR = new Color(uiColorIDEconfigColorOperatorText).getRGB();
    public static final int CONFIG_COLOR_KEYWORD = new Color(uiColorIDEconfigColorKeywordText).getRGB();
    public static final int CONFIG_COLOR_PATH = new Color(uiColorIDEconfigColorPathText).getRGB();
    public static final int CONFIG_COLOR_BOOLEAN = new Color(uiColorIDEconfigColorBooleanText).getRGB();
    public static final int CONFIG_COLOR_NULL = new Color(uiColorIDEconfigColorNullText).getRGB();
    public static final int CONFIG_COLOR_NUMBER = new Color(uiColorIDEconfigColorNumberText).getRGB();
    public static final int CONFIG_COLOR_STRING = new Color(uiColorIDEconfigColorStringText).getRGB();

    public static final int YAML_COLOR_COMMENT = new Color(uiColorIDEyamlColorCommentText).getRGB();
    public static final int YAML_COLOR_ANNOTATION = new Color(uiColorIDEyamlColorAnnotationText).getRGB();
    public static final int YAML_COLOR_IDENTIFIER = new Color(uiColorIDEyamlColorIdentifierText).getRGB();
    public static final int YAML_COLOR_KEYWORD = new Color(uiColorIDEyamlColorKeywordText).getRGB();
    public static final int YAML_COLOR_OPERATOR = new Color(uiColorIDEyamlColorOperatorText).getRGB();
    public static final int YAML_COLOR_BOOLEAN = new Color(uiColorIDEyamlColorBooleanText).getRGB();
    public static final int YAML_COLOR_NULL = new Color(uiColorIDEyamlColorNullText).getRGB();
    public static final int YAML_COLOR_NUMBER = new Color(uiColorIDEyamlColorNumberText).getRGB();
    public static final int YAML_COLOR_STRING = new Color(uiColorIDEyamlColorStringText).getRGB();

    public static final int TOML_COLOR_COMMENT = new Color(uiColorIDEtomlColorCommentText).getRGB();
    public static final int TOML_COLOR_ANNOTATION = new Color(uiColorIDEtomlColorAnnotationText).getRGB();
    public static final int TOML_COLOR_IDENTIFIER = new Color(uiColorIDEtomlColorIdentifierText).getRGB();
    public static final int TOML_COLOR_OPERATOR = new Color(uiColorIDEtomlColorOperatorText).getRGB();
    public static final int TOML_COLOR_BOOLEAN = new Color(uiColorIDEtomlColorBooleanText).getRGB();
    public static final int TOML_COLOR_NULL = new Color(uiColorIDEtomlColorNullText).getRGB();
    public static final int TOML_COLOR_NUMBER = new Color(uiColorIDEtomlColorNumberText).getRGB();
    public static final int TOML_COLOR_STRING = new Color(uiColorIDEtomlColorStringText).getRGB();

    public static final int CSS_COLOR_COMMENT = new Color(uiColorIDEcssColorCommentText).getRGB();
    public static final int CSS_COLOR_ATRULE = new Color(uiColorIDEcssColorAtruleText).getRGB();
    public static final int CSS_COLOR_HEX = new Color(uiColorIDEcssColorHexText).getRGB();
    public static final int CSS_COLOR_NUMBER = new Color(uiColorIDEcssColorNumberText).getRGB();
    public static final int CSS_COLOR_STRING = new Color(uiColorIDEcssColorStringText).getRGB();
    public static final int CSS_COLOR_PROPERTY = new Color(uiColorIDEcssColorPropertyText).getRGB();
    public static final int CSS_COLOR_FUNCTION = new Color(uiColorIDEcssColorFunctionText).getRGB();
    public static final int CSS_COLOR_OPERATOR = new Color(uiColorIDEcssColorOperatorText).getRGB();
    public static final int CSS_COLOR_BRACE = new Color(uiColorIDEcssColorBraceText).getRGB();

    public static final int C_COLOR_COMMENT = new Color(uiColorIDEcColorCommentText).getRGB();
    public static final int C_COLOR_PREPROCESSOR = new Color(uiColorIDEcColorPreprocessorText).getRGB();
    public static final int C_COLOR_ANNOTATION = new Color(uiColorIDEcColorAnnotationText).getRGB();
    public static final int C_COLOR_STRING = new Color(uiColorIDEcColorStringText).getRGB();
    public static final int C_COLOR_CHAR = new Color(uiColorIDEcColorCharText).getRGB();
    public static final int C_COLOR_ESCAPE = new Color(uiColorIDEcColorEscapeText).getRGB();
    public static final int C_COLOR_KEYWORD = new Color(uiColorIDEcColorKeywordText).getRGB();
    public static final int C_COLOR_TYPE = new Color(uiColorIDEcColorTypeText).getRGB();
    public static final int C_COLOR_FUNCTION = new Color(uiColorIDEcColorFunctionText).getRGB();
    public static final int C_COLOR_IDENTIFIER = new Color(uiColorIDEcColorIdentifierText).getRGB();
    public static final int C_COLOR_BOOLEAN = new Color(uiColorIDEcColorBooleanText).getRGB();
    public static final int C_COLOR_NULL = new Color(uiColorIDEcColorNullText).getRGB();
    public static final int C_COLOR_NUMBER = new Color(uiColorIDEcColorNumberText).getRGB();
    public static final int C_COLOR_OPERATOR = new Color(uiColorIDEcColorOperatorText).getRGB();
    public static final int C_COLOR_BRACE = new Color(uiColorIDEcColorBraceText).getRGB();

    public static final int CPP_COLOR_COMMENT = new Color(uiColorIDEcppColorCommentText).getRGB();
    public static final int CPP_COLOR_PREPROCESSOR = new Color(uiColorIDEcppColorPreprocessorText).getRGB();
    public static final int CPP_COLOR_ANNOTATION = new Color(uiColorIDEcppColorAnnotationText).getRGB();
    public static final int CPP_COLOR_STRING = new Color(uiColorIDEcppColorStringText).getRGB();
    public static final int CPP_COLOR_CHAR = new Color(uiColorIDEcppColorCharText).getRGB();
    public static final int CPP_COLOR_ESCAPE = new Color(uiColorIDEcppColorEscapeText).getRGB();
    public static final int CPP_COLOR_KEYWORD = new Color(uiColorIDEcppColorKeywordText).getRGB();
    public static final int CPP_COLOR_TYPE = new Color(uiColorIDEcppColorTypeText).getRGB();
    public static final int CPP_COLOR_FUNCTION = new Color(uiColorIDEcppColorFunctionText).getRGB();
    public static final int CPP_COLOR_IDENTIFIER = new Color(uiColorIDEcppColorIdentifierText).getRGB();
    public static final int CPP_COLOR_BOOLEAN = new Color(uiColorIDEcppColorBooleanText).getRGB();
    public static final int CPP_COLOR_NULL = new Color(uiColorIDEcppColorNullText).getRGB();
    public static final int CPP_COLOR_NUMBER = new Color(uiColorIDEcppColorNumberText).getRGB();
    public static final int CPP_COLOR_OPERATOR = new Color(uiColorIDEcppColorOperatorText).getRGB();
    public static final int CPP_COLOR_BRACE = new Color(uiColorIDEcppColorBraceText).getRGB();

    public static final int CSHARP_COLOR_COMMENT = new Color(uiColorIDEcsharpColorCommentText).getRGB();
    public static final int CSHARP_COLOR_PREPROCESSOR = new Color(uiColorIDEcsharpColorPreprocessorText).getRGB();
    public static final int CSHARP_COLOR_ANNOTATION = new Color(uiColorIDEcsharpColorAnnotationText).getRGB();
    public static final int CSHARP_COLOR_STRING = new Color(uiColorIDEcsharpColorStringText).getRGB();
    public static final int CSHARP_COLOR_CHAR = new Color(uiColorIDEcsharpColorCharText).getRGB();
    public static final int CSHARP_COLOR_ESCAPE = new Color(uiColorIDEcsharpColorEscapeText).getRGB();
    public static final int CSHARP_COLOR_KEYWORD = new Color(uiColorIDEcsharpColorKeywordText).getRGB();
    public static final int CSHARP_COLOR_TYPE = new Color(uiColorIDEcsharpColorTypeText).getRGB();
    public static final int CSHARP_COLOR_FUNCTION = new Color(uiColorIDEcsharpColorFunctionText).getRGB();
    public static final int CSHARP_COLOR_IDENTIFIER = new Color(uiColorIDEcsharpColorIdentifierText).getRGB();
    public static final int CSHARP_COLOR_BOOLEAN = new Color(uiColorIDEcsharpColorBooleanText).getRGB();
    public static final int CSHARP_COLOR_NULL = new Color(uiColorIDEcsharpColorNullText).getRGB();
    public static final int CSHARP_COLOR_NUMBER = new Color(uiColorIDEcsharpColorNumberText).getRGB();
    public static final int CSHARP_COLOR_OPERATOR = new Color(uiColorIDEcsharpColorOperatorText).getRGB();
    public static final int CSHARP_COLOR_BRACE = new Color(uiColorIDEcsharpColorBraceText).getRGB();

    public static final int PYTHON_COLOR_COMMENT = new Color(uiColorIDEpythonColorCommentText).getRGB();
    public static final int PYTHON_COLOR_PREPROCESSOR = new Color(uiColorIDEpythonColorPreprocessorText).getRGB();
    public static final int PYTHON_COLOR_ANNOTATION = new Color(uiColorIDEpythonColorAnnotationText).getRGB();
    public static final int PYTHON_COLOR_STRING = new Color(uiColorIDEpythonColorStringText).getRGB();
    public static final int PYTHON_COLOR_CHAR = new Color(uiColorIDEpythonColorCharText).getRGB();
    public static final int PYTHON_COLOR_ESCAPE = new Color(uiColorIDEpythonColorEscapeText).getRGB();
    public static final int PYTHON_COLOR_KEYWORD = new Color(uiColorIDEpythonColorKeywordText).getRGB();
    public static final int PYTHON_COLOR_TYPE = new Color(uiColorIDEpythonColorTypeText).getRGB();
    public static final int PYTHON_COLOR_FUNCTION = new Color(uiColorIDEpythonColorFunctionText).getRGB();
    public static final int PYTHON_COLOR_IDENTIFIER = new Color(uiColorIDEpythonColorIdentifierText).getRGB();
    public static final int PYTHON_COLOR_BOOLEAN = new Color(uiColorIDEpythonColorBooleanText).getRGB();
    public static final int PYTHON_COLOR_NULL = new Color(uiColorIDEpythonColorNullText).getRGB();
    public static final int PYTHON_COLOR_NUMBER = new Color(uiColorIDEpythonColorNumberText).getRGB();
    public static final int PYTHON_COLOR_OPERATOR = new Color(uiColorIDEpythonColorOperatorText).getRGB();
    public static final int PYTHON_COLOR_BRACE = new Color(uiColorIDEpythonColorBraceText).getRGB();

    public static final int SHADER_COLOR_COMMENT = new Color(uiColorIDEshaderColorCommentText).getRGB();
    public static final int SHADER_COLOR_PREPROCESSOR = new Color(uiColorIDEshaderColorPreprocessorText).getRGB();
    public static final int SHADER_COLOR_QUALIFIER = new Color(uiColorIDEshaderColorQualifierText).getRGB();
    public static final int SHADER_COLOR_KEYWORD = new Color(uiColorIDEshaderColorKeywordText).getRGB();
    public static final int SHADER_COLOR_FUNCTION = new Color(uiColorIDEshaderColorFunctionText).getRGB();
    public static final int SHADER_COLOR_NUMBER = new Color(uiColorIDEshaderColorNumberText).getRGB();
    public static final int SHADER_COLOR_STRING = new Color(uiColorIDEshaderColorStringText).getRGB();
    public static final int SHADER_COLOR_OPERATOR = new Color(uiColorIDEshaderColorOperatorText).getRGB();
    public static final int SHADER_COLOR_BRACE = new Color(uiColorIDEshaderColorBraceText).getRGB();

    public static final int XML_COLOR_COMMENT = new Color(uiColorIDExmlColorCommentText).getRGB();
    public static final int XML_COLOR_ANNOTATION = new Color(uiColorIDExmlColorAnnotationText).getRGB();
    public static final int XML_COLOR_KEYWORD = new Color(uiColorIDExmlColorKeywordText).getRGB();
    public static final int XML_COLOR_IDENTIFIER = new Color(uiColorIDExmlColorIdentifierText).getRGB();
    public static final int XML_COLOR_STRING = new Color(uiColorIDExmlColorStringText).getRGB();
    public static final int XML_COLOR_ESCAPE = new Color(uiColorIDExmlColorEscapeText).getRGB();
    public static final int XML_COLOR_BRACE = new Color(uiColorIDExmlColorBraceText).getRGB();

    public static final int MARKDOWN_COLOR_KEYWORD = new Color(uiColorIDEmarkdownColorKeywordText).getRGB();
    public static final int MARKDOWN_COLOR_IDENTIFIER = new Color(uiColorIDEmarkdownColorIdentifierText).getRGB();
    public static final int MARKDOWN_COLOR_COMMENT = new Color(uiColorIDEmarkdownColorCommentText).getRGB();
    public static final int MARKDOWN_COLOR_STRING = new Color(uiColorIDEmarkdownColorStringText).getRGB();
    public static final int MARKDOWN_COLOR_OBJECT = new Color(uiColorIDEmarkdownColorObjectText).getRGB();
    public static final int MARKDOWN_COLOR_ANNOTATION = new Color(uiColorIDEmarkdownColorAnnotationText).getRGB();

    public static final int LOG_COLOR_NUMBER = new Color(uiColorIDElogColorNumberText).getRGB();
    public static final int LOG_COLOR_KEYWORD = new Color(uiColorIDElogColorKeywordText).getRGB();
    public static final int LOG_COLOR_IDENTIFIER = new Color(uiColorIDElogColorIdentifierText).getRGB();
    public static final int LOG_COLOR_ANNOTATION = new Color(uiColorIDElogColorAnnotationText).getRGB();
    public static final int LOG_COLOR_STRING = new Color(uiColorIDElogColorStringText).getRGB();

    public static final int KTL_COLOR_ANNOTATION = new Color(uiColorIDEktlColorAnnotationText).getRGB();
    public static final int KTL_COLOR_BOOLEAN = new Color(uiColorIDEktlColorBooleanText).getRGB();
    public static final int KTL_COLOR_COMMENT = new Color(uiColorIDEktlColorCommentText).getRGB();
    public static final int KTL_COLOR_IDENTIFIER = new Color(uiColorIDEktlColorIdentifierText).getRGB();
    public static final int KTL_COLOR_KEYWORD = new Color(uiColorIDEktlColorKeywordText).getRGB();
    public static final int KTL_COLOR_NULL = new Color(uiColorIDEktlColorNullText).getRGB();
    public static final int KTL_COLOR_NUMBER = new Color(uiColorIDEktlColorNumberText).getRGB();
    public static final int KTL_COLOR_OPERATOR = new Color(uiColorIDEktlColorOperatorText).getRGB();
    public static final int KTL_COLOR_STRING = new Color(uiColorIDEktlColorStringText).getRGB();
    public static final int KTL_COLOR_CAPABILITY = new Color(uiColorIDEktlColorCapabilityText).getRGB();
    public static final int KTL_COLOR_EVALUATOR = new Color(uiColorIDEktlColorEvaluatorText).getRGB();
    public static final int KTL_COLOR_SEMANTIC = new Color(uiColorIDEktlColorSemanticText).getRGB();
    public static final int KTL_COLOR_MINECRAFT = new Color(uiColorIDEktlColorMinecraftText).getRGB();


    public static final int COLOR_DEFAULT = Color.WHITE.getRGB();
}
