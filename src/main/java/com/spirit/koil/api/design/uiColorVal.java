package com.spirit.koil.api.design;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.util.file.json.JSONFileEditor;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;

public class uiColorVal {
    public static String FILEBASE = com.spirit.Main.uiDesignFileDirectory;
    private static JsonObject bundledDefaults;

    public static boolean overrideBackgroundTexture;
    public static String backgroundTexture;
    public static boolean overrideSplashLoadingTextTexture;
    public static String splashLoadingTextTexture;

    public static int uiColorConsoleToastConsoleTitle;
    public static int uiColorConsoleToastConsoleDescription;
    public static int uiColorConsoleToastInfoTitle;
    public static int uiColorConsoleToastInfoDescription;
    public static int uiColorConsoleToastWarningTitle;
    public static int uiColorConsoleToastWarningDescription;
    public static int uiColorConsoleToastErrorTitle;
    public static int uiColorConsoleToastErrorDescription;
    public static int uiColorConsoleToastFatalTitle;
    public static int uiColorConsoleToastFatalDescription;
    public static int uiColorConsoleToastDebugTitle;
    public static int uiColorConsoleToastDebugDescription;
    public static int uiColorConsoleToastUpdateTitle;
    public static int uiColorConsoleToastUpdateDescription;
    public static int uiColorConsoleToastOtherTitle;
    public static int uiColorConsoleToastOtherDescription;
    public static int uiColorConsoleToastFallbackBackground;
    public static int uiColorConsoleToastFallbackOutline;
    public static int uiColorBackgroundBorder;
    public static int uiColorBackgroundBorderSelected;
    public static int uiColorBackgroundOverlay;
    public static int uiColorBasicDescriptionText;
    public static int uiColorBasicSubtitleText;
    public static int uiColorBasicTitleText;
    public static int uiColorContentBase;
    public static int uiColorContentBaseDescriptionText;
    public static int uiColorContentBaseTitleText;
    public static int uiColorContentStripeLeft;
    public static int uiColorContentStripeRight;
    public static int uiColorEditorError;
    public static int uiColorEditorSuggestionClass;
    public static int uiColorEditorSuggestionField;
    public static int uiColorEditorSuggestionKeyword;
    public static int uiColorEditorSuggestionLiteral;
    public static int uiColorEditorSuggestionMethod;
    public static int uiColorEditorSuggestionPath;
    public static int uiColorEditorSuggestionProperty;
    public static int uiColorEditorSuggestionSelector;
    public static int uiColorEditorSuggestionSymbol;
    public static int uiColorErrorColor;
    public static int uiColorFileEditorLineDarkenColor;
    public static int uiColorFileEditorLineErrorColor;
    public static int uiColorFileEditorLineWarnColor;
    public static int uiColorFileEditorScrollbarTrack;
    public static int uiColorFileEditorScrollbarThumb;
    public static int uiColorFooter;
    public static int uiColorFooterStripe;
    public static int uiColorHeader;
    public static int uiColorHeaderStripe;
    public static int uiColorHeaderSubTitleText;
    public static int uiColorHeaderTitleText;
    public static int uiColorIDEAudioTimestampBarBorder;
    public static int uiColorIDEAudioTimestampBarFill;
    public static int uiColorIDEAudioTimestampBarLine;
    public static int uiColorIDEAudioTimestampText;
    public static int uiColorIDECursor;
    public static int uiColorIDECursorSelection;
    public static int uiColorIDEFileBackText;
    public static int uiColorIDEFileDisplayWindowText;
    public static int uiColorIDEFileEditButtonText;
    public static int uiColorIDEFileNameText;
    public static int uiColorIDEFileTypeText;
    public static int uiColorIDEFolderNameText;
    public static int uiColorIDESuggestionBoxBackground;
    public static int uiColorIDESuggestionItemText;
    public static int uiColorIDEcColorAnnotationText;
    public static int uiColorIDEcColorBooleanText;
    public static int uiColorIDEcColorBraceText;
    public static int uiColorIDEcColorCharText;
    public static int uiColorIDEcColorCommentText;
    public static int uiColorIDEcColorEscapeText;
    public static int uiColorIDEcColorFunctionText;
    public static int uiColorIDEcColorIdentifierText;
    public static int uiColorIDEcColorKeywordText;
    public static int uiColorIDEcColorNullText;
    public static int uiColorIDEcColorNumberText;
    public static int uiColorIDEcColorOperatorText;
    public static int uiColorIDEcColorPreprocessorText;
    public static int uiColorIDEcColorStringText;
    public static int uiColorIDEcColorTypeText;
    public static int uiColorIDEconfigColorAnnotationText;
    public static int uiColorIDEconfigColorBooleanText;
    public static int uiColorIDEconfigColorCommentText;
    public static int uiColorIDEconfigColorIdentifierText;
    public static int uiColorIDEconfigColorKeywordText;
    public static int uiColorIDEconfigColorNullText;
    public static int uiColorIDEconfigColorNumberText;
    public static int uiColorIDEconfigColorOperatorText;
    public static int uiColorIDEconfigColorPathText;
    public static int uiColorIDEconfigColorStringText;
    public static int uiColorIDEcppColorAnnotationText;
    public static int uiColorIDEcppColorBooleanText;
    public static int uiColorIDEcppColorBraceText;
    public static int uiColorIDEcppColorCharText;
    public static int uiColorIDEcppColorCommentText;
    public static int uiColorIDEcppColorEscapeText;
    public static int uiColorIDEcppColorFunctionText;
    public static int uiColorIDEcppColorIdentifierText;
    public static int uiColorIDEcppColorKeywordText;
    public static int uiColorIDEcppColorNullText;
    public static int uiColorIDEcppColorNumberText;
    public static int uiColorIDEcppColorOperatorText;
    public static int uiColorIDEcppColorPreprocessorText;
    public static int uiColorIDEcppColorStringText;
    public static int uiColorIDEcppColorTypeText;
    public static int uiColorIDEcsharpColorAnnotationText;
    public static int uiColorIDEcsharpColorBooleanText;
    public static int uiColorIDEcsharpColorBraceText;
    public static int uiColorIDEcsharpColorCharText;
    public static int uiColorIDEcsharpColorCommentText;
    public static int uiColorIDEcsharpColorEscapeText;
    public static int uiColorIDEcsharpColorFunctionText;
    public static int uiColorIDEcsharpColorIdentifierText;
    public static int uiColorIDEcsharpColorKeywordText;
    public static int uiColorIDEcsharpColorNullText;
    public static int uiColorIDEcsharpColorNumberText;
    public static int uiColorIDEcsharpColorOperatorText;
    public static int uiColorIDEcsharpColorPreprocessorText;
    public static int uiColorIDEcsharpColorStringText;
    public static int uiColorIDEcsharpColorTypeText;
    public static int uiColorIDEcssColorAtruleText;
    public static int uiColorIDEcssColorBraceText;
    public static int uiColorIDEcssColorCommentText;
    public static int uiColorIDEcssColorFunctionText;
    public static int uiColorIDEcssColorHexText;
    public static int uiColorIDEcssColorNumberText;
    public static int uiColorIDEcssColorOperatorText;
    public static int uiColorIDEcssColorPropertyText;
    public static int uiColorIDEcssColorStringText;
    public static int uiColorIDEjavaColorAnnotationText;
    public static int uiColorIDEjavaColorBooleanText;
    public static int uiColorIDEjavaColorBraceText;
    public static int uiColorIDEjavaColorBracketText;
    public static int uiColorIDEjavaColorCharText;
    public static int uiColorIDEjavaColorCommentText;
    public static int uiColorIDEjavaColorEscapeText;
    public static int uiColorIDEjavaColorIdentifierText;
    public static int uiColorIDEjavaColorJavadocText;
    public static int uiColorIDEjavaColorKeywordText;
    public static int uiColorIDEjavaColorMethodText;
    public static int uiColorIDEjavaColorNullText;
    public static int uiColorIDEjavaColorNumberText;
    public static int uiColorIDEjavaColorOperatorText;
    public static int uiColorIDEjavaColorStringText;
    public static int uiColorIDEjavaColorTypeText;
    public static int uiColorIDEjavascriptColorAnnotationText;
    public static int uiColorIDEjavascriptColorBooleanText;
    public static int uiColorIDEjavascriptColorBraceText;
    public static int uiColorIDEjavascriptColorCommentText;
    public static int uiColorIDEjavascriptColorEscapeText;
    public static int uiColorIDEjavascriptColorIdentifierText;
    public static int uiColorIDEjavascriptColorJavadocText;
    public static int uiColorIDEjavascriptColorKeywordText;
    public static int uiColorIDEjavascriptColorMethodText;
    public static int uiColorIDEjavascriptColorNullText;
    public static int uiColorIDEjavascriptColorNumberText;
    public static int uiColorIDEjavascriptColorOperatorText;
    public static int uiColorIDEjavascriptColorRegexText;
    public static int uiColorIDEjavascriptColorStringText;
    public static int uiColorIDEjavascriptColorTypeText;
    public static int uiColorIDEjsonColorArrayText;
    public static int uiColorIDEjsonColorBooleanText;
    public static int uiColorIDEjsonColorCommentText;
    public static int uiColorIDEjsonColorEscapeText;
    public static int uiColorIDEjsonColorNullText;
    public static int uiColorIDEjsonColorNumberText;
    public static int uiColorIDEjsonColorObjectText;
    public static int uiColorIDEjsonColorOperatorText;
    public static int uiColorIDEjsonColorStringText;
    public static int uiColorIDEktlColorAnnotationText;
    public static int uiColorIDEktlColorBooleanText;
    public static int uiColorIDEktlColorCommentText;
    public static int uiColorIDEktlColorIdentifierText;
    public static int uiColorIDEktlColorKeywordText;
    public static int uiColorIDEktlColorNullText;
    public static int uiColorIDEktlColorNumberText;
    public static int uiColorIDEktlColorOperatorText;
    public static int uiColorIDEktlColorStringText;
    public static int uiColorIDEktlColorCapabilityText;
    public static int uiColorIDEktlColorEvaluatorText;
    public static int uiColorIDEktlColorSemanticText;
    public static int uiColorIDEktlColorMinecraftText;
    public static int uiColorIDElogColorAnnotationText;
    public static int uiColorIDElogColorIdentifierText;
    public static int uiColorIDElogColorKeywordText;
    public static int uiColorIDElogColorNumberText;
    public static int uiColorIDElogColorStringText;
    public static int uiColorIDEmarkdownColorAnnotationText;
    public static int uiColorIDEmarkdownColorCommentText;
    public static int uiColorIDEmarkdownColorIdentifierText;
    public static int uiColorIDEmarkdownColorKeywordText;
    public static int uiColorIDEmarkdownColorObjectText;
    public static int uiColorIDEmarkdownColorStringText;
    public static int uiColorIDEpropertiesColorEscapeText;
    public static int uiColorIDEpropertiesColorNullText;
    public static int uiColorIDEpropertiesColorAnnotationText;
    public static int uiColorIDEpropertiesColorCommentText;
    public static int uiColorIDEpropertiesColorBooleanText;
    public static int uiColorIDEpropertiesColorBracketText;
    public static int uiColorIDEpropertiesColorBraceText;
    public static int uiColorIDEpropertiesColorKeywordText;
    public static int uiColorIDEpropertiesColorNumberText;
    public static int uiColorIDEpropertiesColorCharText;
    public static int uiColorIDEpropertiesColorStringText;
    public static int uiColorIDEpropertiesColorIdentifierText;
    public static int uiColorIDEpropertiesColorOperatorText;
    public static int uiColorIDEpythonColorAnnotationText;
    public static int uiColorIDEpythonColorBooleanText;
    public static int uiColorIDEpythonColorBraceText;
    public static int uiColorIDEpythonColorCharText;
    public static int uiColorIDEpythonColorCommentText;
    public static int uiColorIDEpythonColorEscapeText;
    public static int uiColorIDEpythonColorFunctionText;
    public static int uiColorIDEpythonColorIdentifierText;
    public static int uiColorIDEpythonColorKeywordText;
    public static int uiColorIDEpythonColorNullText;
    public static int uiColorIDEpythonColorNumberText;
    public static int uiColorIDEpythonColorOperatorText;
    public static int uiColorIDEpythonColorPreprocessorText;
    public static int uiColorIDEpythonColorStringText;
    public static int uiColorIDEpythonColorTypeText;
    public static int uiColorIDEshaderColorBraceText;
    public static int uiColorIDEshaderColorCommentText;
    public static int uiColorIDEshaderColorFunctionText;
    public static int uiColorIDEshaderColorKeywordText;
    public static int uiColorIDEshaderColorNumberText;
    public static int uiColorIDEshaderColorOperatorText;
    public static int uiColorIDEshaderColorPreprocessorText;
    public static int uiColorIDEshaderColorQualifierText;
    public static int uiColorIDEshaderColorStringText;
    public static int uiColorIDEtomlColorAnnotationText;
    public static int uiColorIDEtomlColorBooleanText;
    public static int uiColorIDEtomlColorCommentText;
    public static int uiColorIDEtomlColorIdentifierText;
    public static int uiColorIDEtomlColorNullText;
    public static int uiColorIDEtomlColorNumberText;
    public static int uiColorIDEtomlColorOperatorText;
    public static int uiColorIDEtomlColorStringText;
    public static int uiColorIDExmlColorAnnotationText;
    public static int uiColorIDExmlColorBraceText;
    public static int uiColorIDExmlColorCommentText;
    public static int uiColorIDExmlColorEscapeText;
    public static int uiColorIDExmlColorIdentifierText;
    public static int uiColorIDExmlColorKeywordText;
    public static int uiColorIDExmlColorStringText;
    public static int uiColorIDEyamlColorAnnotationText;
    public static int uiColorIDEyamlColorBooleanText;
    public static int uiColorIDEyamlColorCommentText;
    public static int uiColorIDEyamlColorIdentifierText;
    public static int uiColorIDEyamlColorKeywordText;
    public static int uiColorIDEyamlColorNullText;
    public static int uiColorIDEyamlColorNumberText;
    public static int uiColorIDEyamlColorOperatorText;
    public static int uiColorIDEyamlColorStringText;
    public static int uiColorIndentMixed;
    public static int uiColorIssuesError;
    public static int uiColorIssuesWarn;
    public static int uiColorModrinthPreviewProviderModrinth;
    public static int uiColorModrinthPreviewStateInstalled;
    public static int uiColorModrinthPreviewStateDisabled;
    public static int uiColorModrinthPreviewStateInstalledBackground;
    public static int uiColorModrinthPreviewChipText;
    public static int uiColorModrinthPreviewChipBorder;
    public static int uiColorModrinthPreviewChipVanillaText;
    public static int uiColorModrinthPreviewChipDarkText;
    public static int uiColorModrinthPreviewSourceChipBackground;
    public static int uiColorModrinthPreviewSourceChipBorder;
    public static int uiColorModrinthPreviewSourceChipText;
    public static int uiColorModrinthPreviewSectionDivider;
    public static int uiColorModrinthPreviewSectionTitle;
    public static int uiColorModrinthPreviewScrollbarTrack;
    public static int uiColorModrinthPreviewScrollbarThumb;
    public static int uiColorModrinthPreviewInfoLineTopBorder;
    public static int uiColorModrinthPreviewInfoLineAccentBar;
    public static int uiColorModrinthPreviewInfoLineBottomBorder;
    public static int uiColorModrinthPreviewInfoLineLabel;
    public static int uiColorModrinthPreviewRowSides;
    public static int uiColorModrinthPreviewRowTargets;
    public static int uiColorModrinthPreviewRowType;
    public static int uiColorModrinthPreviewRowLicense;
    public static int uiColorModrinthPreviewRowDownloads;
    public static int uiColorModrinthPreviewRowFollowers;
    public static int uiColorModrinthPreviewRowLinks;
    public static int uiColorModrinthPreviewRowDates;
    public static int uiColorModrinthPreviewRowProject;
    public static int uiColorModrinthPreviewRowAuthor;
    public static int uiColorModrinthPreviewRowInstalledFile;
    public static int uiColorModrinthPreviewRowVersionTitle;
    public static int uiColorNonSelectionHighlight;
    public static int uiColorSaveSuccessColor;
    public static int uiColorSearchError;
    public static int uiColorSelectionHighlight;
    public static int uiColorSplashLoadingBar;
    public static int uiColorToolTipError;
    public static int uiColorToolTipFix;
    public static int uiColorToolTipIdea;
    public static int uiColorToolTipLabel;
    public static int uiColorToolTipPrimary;
    public static int uiColorToolTipSecondary;
    public static int uiColorToolTipWarning;
    public static int uiColorWarnColor;
    public static int uiColorWarningPromptText;
    public static int uiColorConfigStatusSaved;
    public static int uiColorConfigStatusError;
    public static int uiColorConfigTooltipError;
    public static int uiColorConfigTooltipLabel;
    public static int uiColorConfigTooltipPrimary;
    public static int uiColorConfigTooltipSecondary;
    public static int uiColorConfigTooltipIdea;
    public static int uiColorConfigValidationError;
    public static int uiColorConfigValidationErrorSoft;
    public static int uiColorConfigBooleanTrue;
    public static int uiColorConfigBooleanFalse;
    public static int uiColorConfigChangedText;
    public static int uiColorConfigPopupOverlay;
    public static int uiColorConfigPopupBorder;
    public static int uiColorConfigPickerSelected;
    public static int uiColorConfigPickerText;
    public static int uiColorConfigColorCheckerLight;
    public static int uiColorConfigColorCheckerDark;
    public static int uiColorConfigAxisX;
    public static int uiColorConfigAxisY;
    public static int uiColorConfigAxisZ;
    public static int uiColorConfigAxisXSoft;
    public static int uiColorConfigAxisYSoft;
    public static int uiColorConfigPointYellow;
    public static int uiColorConfigPointPurple;
    public static int uiColorConfigPointCyan;
    public static int uiColorConfigGammaBackground;
    public static int uiColorConfigTemperatureCold;
    public static int uiColorConfigTemperatureWarm;
    public static int uiColorConfigSparkGold;
    public static int uiColorConfigSparkBlue;
    public static int uiColorConfigSparkCream;
    public static int uiColorConfigSparkGreen;

    static {
        reload();
    }

    public static void reload() {
        FILEBASE = com.spirit.Main.uiDesignFileDirectory;
        reloadTextureValues();
        reloadRegisteredColors();
    }

    private static void reloadTextureValues() {
        overrideBackgroundTexture = getBoolean("overrideBackgroundTexture");
        backgroundTexture = getString("backgroundTexture");
        overrideSplashLoadingTextTexture = getBoolean("overrideSplashLoadingTextTexture");
        splashLoadingTextTexture = getString("splashLoadingTextTexture");
    }

    private static void reloadRegisteredColors() {
        for (Field field : uiColorVal.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() != int.class || !Modifier.isStatic(modifiers) || !field.getName().startsWith("uiColor")) {
                continue;
            }
            try {
                field.setInt(null, colorFromChannels(field.getName(), 0xFFFFFFFF));
            } catch (Exception ignored) {
            }
        }
    }

    public static int getInt(String key) {
        JsonElement element = designValue(key);
        if (element != null && !element.isJsonNull()) {
            try {
                return element.getAsInt();
            } catch (Exception ignored) {
            }
        }
        JsonElement fallback = bundledDefault(key);
        if (fallback != null && !fallback.isJsonNull()) {
            try {
                return fallback.getAsInt();
            } catch (Exception ignored) {
            }
        }
        return fallbackIntForKey(key);
    }

    public static boolean getBoolean(String key) {
        JsonElement element = designValue(key);
        if (element != null && !element.isJsonNull()) {
            try {
                return element.getAsBoolean();
            } catch (Exception ignored) {
            }
        }
        JsonElement fallback = bundledDefault(key);
        if (fallback != null && !fallback.isJsonNull()) {
            try {
                return fallback.getAsBoolean();
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public static String getString(String key) {
        JsonElement element = designValue(key);
        if (element != null && !element.isJsonNull()) {
            try {
                return element.getAsString();
            } catch (Exception ignored) {
            }
        }
        JsonElement fallback = bundledDefault(key);
        if (fallback != null && !fallback.isJsonNull()) {
            try {
                return fallback.getAsString();
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    public static int color(String key, int fallbackArgb) {
        Integer parsed = parseColorElement(designValue(key));
        if (parsed != null) {
            return parsed;
        }
        parsed = parseColorElement(bundledDefault(key));
        return parsed == null ? fallbackArgb : parsed;
    }

    public static int color(String key) {
        return color(key, 0xFFFFFFFF);
    }

    public static int colorFromChannels(String baseKey, int fallbackArgb) {
        Integer parsed = parseColorElement(designValue(baseKey));
        if (parsed != null) {
            return parsed;
        }
        parsed = parseColorElement(bundledDefault(baseKey));
        if (parsed != null) {
            return parsed;
        }
        if (!hasAnyChannel(baseKey)) {
            return fallbackArgb;
        }
        return combineColor(
                getInt(baseKey + "R"),
                getInt(baseKey + "G"),
                getInt(baseKey + "B"),
                getInt(baseKey + "A"));
    }

    private static boolean hasAnyChannel(String baseKey) {
        return designValue(baseKey + "R") != null
                || designValue(baseKey + "G") != null
                || designValue(baseKey + "B") != null
                || designValue(baseKey + "A") != null
                || bundledDefault(baseKey + "R") != null
                || bundledDefault(baseKey + "G") != null
                || bundledDefault(baseKey + "B") != null
                || bundledDefault(baseKey + "A") != null;
    }

    private static JsonElement designValue(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return JSONFileEditor.getValueFromJson(FILEBASE, key);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer parseColorElement(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            if (element.isJsonPrimitive()) {
                if (element.getAsJsonPrimitive().isNumber()) {
                    return element.getAsInt();
                }
                String value = element.getAsString().trim();
                if (value.startsWith("#")) {
                    value = value.substring(1);
                } else if (value.startsWith("0x") || value.startsWith("0X")) {
                    return (int) Long.parseLong(value.substring(2), 16);
                }
                if (value.length() == 6) {
                    return 0xFF000000 | (int) Long.parseLong(value, 16);
                }
                if (value.length() == 8) {
                    int r = Integer.parseInt(value.substring(0, 2), 16);
                    int g = Integer.parseInt(value.substring(2, 4), 16);
                    int b = Integer.parseInt(value.substring(4, 6), 16);
                    int a = Integer.parseInt(value.substring(6, 8), 16);
                    return combineColor(r, g, b, a);
                }
            }
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                if (object.has("argb")) {
                    return parseColorElement(object.get("argb"));
                }
                if (object.has("rgba")) {
                    return parseColorElement(object.get("rgba"));
                }
                int r = channel(object, "r", "R", 255);
                int g = channel(object, "g", "G", 255);
                int b = channel(object, "b", "B", 255);
                int a = channel(object, "a", "A", 255);
                return combineColor(r, g, b, a);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static int channel(JsonObject object, String lower, String upper, int fallback) {
        JsonElement element = object.has(lower) ? object.get(lower) : object.get(upper);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        return Math.max(0, Math.min(255, element.getAsInt()));
    }

    private static JsonElement bundledDefault(String key) {
        JsonObject defaults = bundledDefaults();
        if (defaults == null) {
            return null;
        }
        JsonElement value = findValue(defaults, key);
        return value == null && defaults.has(key) ? defaults.get(key) : value;
    }

    private static JsonObject bundledDefaults() {
        if (bundledDefaults != null) {
            return bundledDefaults;
        }
        try (InputStream stream = uiColorVal.class.getClassLoader().getResourceAsStream("koil-default-design.json")) {
            if (stream == null) {
                bundledDefaults = new JsonObject();
                return bundledDefaults;
            }
            bundledDefaults = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception ignored) {
            bundledDefaults = new JsonObject();
        }
        return bundledDefaults;
    }

    private static JsonElement findValue(JsonObject root, String key) {
        if (root == null || key == null || key.isBlank()) {
            return null;
        }
        if (root.has(key)) {
            return root.get(key);
        }
        JsonElement dotted = findDottedValue(root, key);
        if (dotted != null) {
            return dotted;
        }
        return findValueRecursive(root, key);
    }

    private static JsonElement findDottedValue(JsonObject root, String key) {
        JsonElement current = root;
        for (String part : key.split("\\.")) {
            if (part.isBlank() || current == null || !current.isJsonObject()) {
                return null;
            }
            JsonObject object = current.getAsJsonObject();
            if (!object.has(part)) {
                return null;
            }
            current = object.get(part);
        }
        return current;
    }

    private static JsonElement findValueRecursive(JsonElement element, String key) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has(key)) {
            return object.get(key);
        }
        for (String member : object.keySet()) {
            JsonElement child = object.get(member);
            JsonElement found = findValueRecursive(child, key);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static int fallbackIntForKey(String key) {
        if (key == null) {
            return 255;
        }
        if (key.endsWith("A")) {
            return 255;
        }
        if (key.endsWith("R") || key.endsWith("G") || key.endsWith("B")) {
            return key.contains("Content") || key.contains("Header") || key.contains("Footer") || key.contains("Background") ? 0 : 255;
        }
        return 255;
    }

    public static int combineColor(int r, int g, int b, int a) {
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        a = Math.max(0, Math.min(255, a));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
