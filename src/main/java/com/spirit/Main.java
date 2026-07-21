package com.spirit;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.spirit.koil.api.automation.AutomationPresenceServerBridge;
import com.spirit.koil.api.automation.AutomationRemoteRunServerBridge;
import com.spirit.koil.api.automation.KoilCommandPauseBridge;
import com.spirit.koil.api.screen.KoilRemoteScreenServerBridge;
import com.spirit.koil.api.console.ConsoleChannel;
import com.spirit.koil.api.stats.global.KoilGlobalActivityServer;
import com.spirit.koil.chat.internal.sync.RichChatSyncServerBridge;
import com.spirit.koil.api.util.application.WindowManager;
import com.spirit.koil.api.util.console.log.SubFileLogger;
import com.spirit.koil.api.util.file.FileSanitizer;
import com.spirit.koil.api.util.file.KoilPackageManager;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import com.spirit.koil.api.util.file.json.JsonBlockMaker;
import com.spirit.koil.api.util.file.json.JsonItemMaker;
import com.spirit.koil.api.util.web.WebFileDownloader;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.spirit.koil.api.util.file.jar.strings.ModIds.KOIL_ID;

public class Main implements ModInitializer {
    public static final SubFileLogger SUBLOGGER;
    public static final SubFileLogger PKG_SUBLOGGER;
    public static boolean preciseStat;

    private static final Path DEFAULT_CONFIG_PATH = Path.of("koil", "sys", "config.json");

    private static final String DEFAULT_CONFIG_JSON = """
{
  "firstLaunch": true,
  "debug": true,
  "openKoilLogOnStartUp": false,
  "isBetaTesting": true,
  "betaBranch": "beta",
  "uiRedesign": true,
  "uiTheme": "default",
  "uiDesignDirectory": "./koil/sys/design",
  "wantsColoredFileIcons": false,
  "background": "",
  "backgroundMusic": "",
  "musicVolume": -10,
  "designMusic": true,
  "holidayDesign": true,
  "holidayDesignMusic": true,
  "disableDebugToast": false,
  "disableMaintenanceToast": false,
  "disableAnnouncementToast": false,
  "compactConfigListing": false,
  "preciseStat": false,
  "vanillaF3Design": false,
  "menuPanorama": false
}
""";

    static {
        SubFileLogger.initialize("mainLogger", "koil/logs", "main");
        SubFileLogger.initialize("packageLogger", "koil/logs/package", "package");
        SUBLOGGER = SubFileLogger.getInstance("mainLogger");
        PKG_SUBLOGGER = SubFileLogger.getInstance("packageLogger");
        SUBLOGGER.logI("Start-up thread", "Starting...");
        ensureDefaultConfigFile();
        preciseStat = getConfigBoolean("preciseStat", false);
        if (getConfigBoolean("openKoilLogOnStartUp", false)) {
            WindowManager.openConsoleWindow(ConsoleChannel.KOIL);
        }
        initializeWebFilesAndDesign();

        KoilGlobalActivityServer.register();
        RichChatSyncServerBridge.register();
    }

    private static void ensureDefaultConfigFile() {
        try {
            Path parent = DEFAULT_CONFIG_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.notExists(DEFAULT_CONFIG_PATH)) {
                Files.writeString(DEFAULT_CONFIG_PATH, DEFAULT_CONFIG_JSON, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                SUBLOGGER.logI("Config", "Created default config.json");
            }
        } catch (IOException e) {
            SUBLOGGER.logE("Config", "Failed to create default config.json: " + e.getMessage());
        }
    }

    private static void initializeWebFilesAndDesign() {
        ensureBootstrapDirectories();
        if (isFirstLaunchPending()) {
            SUBLOGGER.logI("Start-up thread", "First launch terms are pending. External bootstrap downloads are deferred.");
            applyResolvedDesignPaths();
            return;
        }
        WebFileDownloader.downloadCheckedFile("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/config.json", "config.json", "./koil/sys", 16);
        WebFileDownloader.updateFileWithTemp("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/config.json", "config.json", "./koil/sys", 16);
        WebFileDownloader.downloadFile("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/sys.json", "sys.json", "./koil/sys", 16);
        WebFileDownloader.downloadCheckedFile("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/koil.json", "koil.json", "./config", 16);
        WebFileDownloader.downloadCheckedFile("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/key.json", "key.json", "./koil/sys", 16);
        WebFileDownloader.downloadFile("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/catcher.json", "catcher.json", "./koil/sys", 16);
        String requestedTheme = resolveRequestedTheme();
        String themeSavePath = "./koil/sys/design/" + requestedTheme + "/";
        WebFileDownloader.downloadCheckedFile("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/design.json", "design.json", themeSavePath, 16);
        WebFileDownloader.updateFileWithTemp("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/design.json", "design.json", themeSavePath, 16);
        WebFileDownloader.downloadFile("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/data.json", "data.json", "./koil/sys", 16);
        WebFileDownloader.downloadCheckedFile("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/content/sys/design/files/music.json", "music.json", themeSavePath + "files", 16);
        WebFileDownloader.updateFileWithTemp("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/content/sys/design/files/music.json", "music.json", themeSavePath + "files", 16);
        WebFileDownloader.downloadCheckedFile("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/content/sys/design/files/background.json", "background.json", themeSavePath + "files", 16);
        WebFileDownloader.updateFileWithTemp("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/content/sys/design/files/background.json", "background.json", themeSavePath + "files", 16);
        WebFileDownloader.downloadFile("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/wiki/help_book.json", "help_book.json", "./koil/wiki", 16);
        WebFileDownloader.assetsUpdater(Path.of("./koil/sys/key.json"), Path.of("./koil/sys/catcher.json"), "./koil");
        WebFileDownloader.downloadCheckedFile("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/auth/validDigits.json", "validDigits.json", "./koil/auth", 16);
        WebFileDownloader.updateFileWithTemp("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/auth/validDigits.json", "validDigits.json", "./koil/auth", 16);
        WebFileDownloader.downloadCheckedFile("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/auth/validSerial.json", "validSerial.json", "./koil/auth", 16);
        WebFileDownloader.updateFileWithTemp("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/auth/validSerial.json", "validSerial.json", "./koil/auth", 16);
        WebFileDownloader.downloadCheckedFile("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/auth/verifiedAuthors.json", "verifiedAuthors.json", "./koil/auth", 16);
        WebFileDownloader.updateFileWithTemp("https://raw.githubusercontent.com/Koil-public/koil-online-data/main/auth/verifiedAuthors.json", "verifiedAuthors.json", "./koil/auth", 16);


        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getSession() != null) {
            WebFileDownloader.downloadFile(
                    "https://raw.githubusercontent.com/Koil-public/koil-online-data/main/data/player_data/" + client.getSession().getUsername() + "/uuid.json",
                    "player_data.json",
                    "./koil/sys/cache/player_data",
                    16
            );
        } else {
            SUBLOGGER.logW("Start-up thread", "Skipping player-data bootstrap because the Minecraft client session is not available yet.");
        }

        applyResolvedDesignPaths();
    }

    public static void refreshBootstrapFiles() {
        initializeWebFilesAndDesign();
    }

    public static boolean isFirstLaunchPending() {
        return getConfigBoolean("firstLaunch", true);
    }

    public static void completeFirstLaunch(boolean uiRedesign) {
        try {
            JSONFileEditor.updateValueInJson("./koil/sys/config.json", "uiRedesign", new JsonPrimitive(uiRedesign));
            JSONFileEditor.updateValueInJson("./koil/sys/config.json", "firstLaunch", new JsonPrimitive(false));
            SUBLOGGER.logI("Start-up thread", "Saved first launch terms state. uiRedesign=" + uiRedesign + ", firstLaunch=false");
        } catch (IOException e) {
            SUBLOGGER.logE("Start-up thread", "Failed to save first launch terms state: " + e.getMessage());
            throw new RuntimeException("Failed to save first launch terms state", e);
        }
    }

    private static boolean getConfigBoolean(String key, boolean fallback) {
        try {
            JsonElement value = JSONFileEditor.getValueFromJson("./koil/sys/config.json", key);
            if (value != null && value.isJsonPrimitive()) {
                return value.getAsBoolean();
            }
            JSONFileEditor.updateValueInJson("./koil/sys/config.json", key, new JsonPrimitive(fallback));
        } catch (Exception ignored) {
        }
        return fallback;
    }

    public static boolean preciseStat() {
        preciseStat = getConfigBoolean("preciseStat", false);
        return preciseStat;
    }

    public static boolean vanillaF3Design() {
        return getConfigBoolean("vanillaF3Design", false);
    }

    public static void deleteCoreBootstrapFiles() {
        deleteIfExists(Paths.get("./koil/sys/key.json"));
        deleteIfExists(Paths.get("./koil/sys/catcher.json"));
    }

    public static final String VERSION = "0.70.25";
    public static final String BETA_VERSION = "0.70.26-unfinished.11";
    public static final String FREQUENT_BETA_VERSION = "0.70.26-frequent.0";
    public static final Identifier LOGO_TEXTURE = new Identifier(KOIL_ID, "textures/gui/icons/icon.png");
    public static final Identifier AUTOMATION_TEXTURE = new Identifier(KOIL_ID, "textures/gui/icons/automation.png");
    public static final Identifier MOJANG_LOGO = new Identifier("textures/gui/menus/mojang_mini_icon.png");
    public static final Identifier LOADING_TEXT_TEXTURE = new Identifier("textures/gui/menus/loading_text.png");
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");

    public static boolean isPlayerAllowed;
    public static boolean isBetaTesting;
    public static boolean wantsColoredFileIcons;
    public static boolean musicToastShown = false;
    public static boolean isHalloween;
    public static boolean isChristmas;

    public static String uiTheme;
    public static String uiDesignDirectory;
    public static String uiDesignFileDirectory;

    public static String uiAudioDirectory;
    public static String uiFilesDirectory;
    public static String uiImageDirectory;
    public static String uiImageDatapackIconDirectory;
    public static String uiImageModIconDirectory;
    public static String uiImageResourcepackIconDirectory;
    public static String uiImageServerIconDirectory;
    public static String uiImageWorldIconDirectory;

    public static void reloadDesign() {
        applyResolvedDesignPaths();
    }

    private static void ensureBootstrapDirectories() {
        String[] directories = {
                "./koil",
                "./koil/sys",
                "./koil/sys/design/default/files",
                "./config"
        };
        for (String directory : directories) {
            try {
                Files.createDirectories(Paths.get(directory));
            } catch (IOException e) {
                SUBLOGGER.logE("Start-up thread", "Failed to create bootstrap directory " + directory + ": " + e.getMessage());
            }
        }
        ensureBundledDefaultDesign();
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            SUBLOGGER.logE("Start-up thread", "Failed to delete " + path + ": " + e.getMessage());
        }
    }

    private static void ensureBundledDefaultDesign() {
        Path fallbackDesign = Paths.get("./koil/sys/design/default/design.json");
        if (Files.exists(fallbackDesign)) {
            return;
        }
        try (InputStream stream = Main.class.getClassLoader().getResourceAsStream("koil-default-design.json")) {
            if (stream == null) {
                SUBLOGGER.logW("Start-up thread", "Bundled default design resource is missing.");
                return;
            }
            Files.createDirectories(fallbackDesign.getParent());
            Files.writeString(fallbackDesign, readResource(stream), StandardCharsets.UTF_8);
        } catch (IOException e) {
            SUBLOGGER.logE("Start-up thread", "Failed to write bundled default design: " + e.getMessage());
        }
    }

    private static String readResource(InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        }
    }

    private static String resolveRequestedTheme() {
        try {
            return JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiTheme").getAsString();
        } catch (Exception ignored) {
            return "default";
        }
    }

    private static String resolveDesignBaseDirectory() {
        try {
            return JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiDesignDirectory").getAsString();
        } catch (Exception ignored) {
            return "./koil/sys/design";
        }
    }

    private static void applyResolvedDesignPaths() {
        String baseDir = resolveDesignBaseDirectory();
        String requestedTheme = resolveRequestedTheme();
        String themePath = baseDir + "/" + requestedTheme;
        String designFile = themePath + "/design.json";

        if (!new File(designFile).exists()) {
            requestedTheme = "default";
            themePath = baseDir + "/default";
            designFile = themePath + "/design.json";
        }

        uiTheme = requestedTheme;
        uiDesignDirectory = ensureTrailingSlash(themePath);
        uiDesignFileDirectory = designFile;

        uiAudioDirectory = getSafePath(themePath, designFile, "uiAudioDirectory");
        uiFilesDirectory = getSafePath(themePath, designFile, "uiFilesDirectory");
        uiImageDirectory = getSafePath(themePath, designFile, "uiImageDirectory");
        uiImageDatapackIconDirectory = getSafePath(themePath, designFile, "uiImageDatapackIconDirectory");
        uiImageModIconDirectory = getSafePath(themePath, designFile, "uiImageModIconDirectory");
        uiImageResourcepackIconDirectory = getSafePath(themePath, designFile, "uiImageResourcepackIconDirectory");
        uiImageServerIconDirectory = getSafePath(themePath, designFile, "uiImageServerIconDirectory");
        uiImageWorldIconDirectory = getSafePath(themePath, designFile, "uiImageWorldIconDirectory");
    }

    private static String getSafePath(String themePath, String designFile, String key) {
        try {
            String value = JSONFileEditor.getValueFromJson(designFile, key).getAsString();
            return buildThemeRelativePath(themePath, value);
        } catch (Exception e) {
            return ensureTrailingSlash(themePath);
        }
    }

    private static String buildThemeRelativePath(String themePath, String value) {
        if (value == null || value.isBlank()) return ensureTrailingSlash(themePath);

        String normalizedThemePath = themePath.replace("\\", "/");
        String normalizedValue = value.replace("\\", "/");

        if (normalizedValue.startsWith("./")) normalizedValue = normalizedValue.substring(2);
        while (normalizedValue.startsWith("/")) normalizedValue = normalizedValue.substring(1);

        return ensureTrailingSlash(normalizedThemePath + "/" + normalizedValue);
    }

    private static String ensureTrailingSlash(String path) {
        if (path == null || path.isBlank()) return "./";
        return path.endsWith("/") ? path : path + "/";
    }

    @Override
    public void onInitialize() {
        if (!isFirstLaunchPending()) {
            try {
                KoilPackageManager.packageMain();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            SUBLOGGER.logI("Start-up thread", "Skipping Koil package processing until first launch terms are accepted.");
        }

        AutomationRemoteRunServerBridge.registerCommands();
        KoilRemoteScreenServerBridge.registerCommands();
        KoilCommandPauseBridge.register();
        com.spirit.koil.api.chat.KoilAttentionCommandBridge.register();
        AutomationPresenceServerBridge.register();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SUBLOGGER.logF("Shut-down thread", "Ending All Processes.");
            SubFileLogger.closeAll();
        }));
        SUBLOGGER.logI("Start-up thread", "Beginning Initialization Threads...");
        File romsgbDirectory = new File("./koil/emu/roms/gb");
        FileSanitizer.sanitizeDirectory(romsgbDirectory);
        File romsgbcDirectory = new File("./koil/emu/roms/gbc");
        FileSanitizer.sanitizeDirectory(romsgbcDirectory);
        JsonItemMaker.makeTheJsonItem();
        JsonItemMaker.registerItemsFromJson();
        JsonBlockMaker.makeTheJsonBlocks();
        JsonBlockMaker.registerBlocksFromJson();
    }

    public static String version() {
        isBetaTesting = configBoolean("isBetaTesting", false);
        if (!isBetaTesting) {
            return jsonString("./koil/sys/sys.json", "version", VERSION);
        }
        return switch (activeBetaBranch()) {
            case "frequent" -> firstJsonString("./koil/sys/sys.json", FREQUENT_BETA_VERSION, "frequentBetaVersion", "frequentVersion");
            case "unfinished" -> firstJsonString("./koil/sys/sys.json", BETA_VERSION, "unfinishedBetaVersion", "unfinishedVersion", "betaVersion");
            default -> firstJsonString("./koil/sys/sys.json", BETA_VERSION, "betaVersion", "unfinishedBetaVersion", "unfinishedVersion");
        };
    }


    public static String currentVersion() {
        isBetaTesting = configBoolean("isBetaTesting", false);
        String branch = isBetaTesting ? activeBetaBranch() : "public";
        String runtimeVersion = runtimeModVersion();
        if (branch.equals(versionBranch(runtimeVersion))) {
            return runtimeVersion;
        }
        if (!isBetaTesting) {
            return VERSION;
        }
        return "frequent".equals(branch) ? FREQUENT_BETA_VERSION : BETA_VERSION;
    }

    public static String activeKoilBranch() {
        if (!configBoolean("isBetaTesting", false)) {
            return "public";
        }
        return activeBetaBranch();
    }

    private static String activeBetaBranch() {
        String branch = firstConfigString("unfinished", "activeBranch", "koilBranch", "betaBranch");
        branch = branch.trim().toLowerCase();
        if (branch.startsWith(".")) {
            branch = branch.substring(1);
        }
        if ("frequent".equals(branch)) {
            return "frequent";
        }
        return "unfinished";
    }

    private static String runtimeModVersion() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer(KOIL_ID)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .filter(value -> value != null && !value.isBlank())
                    .orElse("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String versionBranch(String version) {
        String lower = version == null ? "" : version.toLowerCase();
        if (lower.contains("frequent")) {
            return "frequent";
        }
        if (lower.contains("unfinished") || lower.contains("beta")) {
            return "unfinished";
        }
        return lower.isBlank() ? "" : "public";
    }

    private static boolean configBoolean(String key, boolean fallback) {
        try {
            JsonElement element = JSONFileEditor.getValueFromJson("./koil/sys/config.json", key);
            return element != null && element.isJsonPrimitive() ? element.getAsBoolean() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String firstConfigString(String fallback, String... keys) {
        for (String key : keys) {
            String value = jsonString("./koil/sys/config.json", key, "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    private static String firstJsonString(String path, String fallback, String... keys) {
        for (String key : keys) {
            String value = jsonString(path, key, "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    private static String jsonString(String path, String key, String fallback) {
        try {
            JsonElement element = JSONFileEditor.getValueFromJson(path, key);
            if (element != null && element.isJsonPrimitive()) {
                String value = element.getAsString();
                return value == null || value.isBlank() ? fallback : value;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    public static String changelog() {
        isBetaTesting = JSONFileEditor.getValueFromJson("./koil/sys/config.json", "isBetaTesting").getAsBoolean();
        if (isBetaTesting) return JSONFileEditor.getValueFromJson("./koil/sys/data.json", "changeLogBeta").getAsString();
        else return JSONFileEditor.getValueFromJson("./koil/sys/data.json", "changeLog").getAsString();
    }

    public static String changelogFull() {
        isBetaTesting = JSONFileEditor.getValueFromJson("./koil/sys/config.json", "isBetaTesting").getAsBoolean();
        if (isBetaTesting) return JSONFileEditor.getValueFromJson("./koil/sys/data.json", "changeLogFullBeta").getAsString();
        else return JSONFileEditor.getValueFromJson("./koil/sys/data.json", "changeLogFull").getAsString();
    }

    /*
    --Branch Check Layout--
    registerMod
    Blocks
    BlockEntities
    Items
    ItemGroups

    Sounds

    LootTable
    WorldGen

    Particles
    Paintings

    Effects
    Potions

    EntityAttributeRegistry

    Packets
    */

    public static UUID getSessionUUID(MinecraftClient client) {
        String uuidString = client.getSession().getUuid().replace("-", ""); // Remove dashes if present

        if (isValidUUID(uuidString)) {
            String formattedUUID = uuidString.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                    "$1-$2-$3-$4-$5"
            );
            return UUID.fromString(formattedUUID);
        } else {
            throw new IllegalArgumentException("Invalid UUID string: " + uuidString);
        }
    }

    private static boolean isValidUUID(String uuid) {
        return UUID_PATTERN.matcher(uuid).matches();
    }


    public static final Map<Character, String[]> ENCRYPTION_MAP = new HashMap<>();
    private static final Map<String, Character> DECRYPTION_MAP = new HashMap<>();

    static {
        ENCRYPTION_MAP.put('a', new String[]{"tr", "aa", "eh", "7w", "ja", "y8", "gt", "mr", "n0", "ni", "pa", "em", "y7", "g5", "72", "a8", "qj", "l1", "hu", "53", "xq", "k4", "n2", "am", "q0", "0m", "2h", "e5", "xe", "4v", "ze", "r5", "2t", "h2", "n7", "qe"});
        ENCRYPTION_MAP.put('b', new String[]{"1v", "su", "jh", "k5", "v2", "j6", "hq", "9t", "d2", "g2", "uy", "xp", "xz", "3p", "2d", "ek", "vn", "56", "zg", "uc", "id", "yw", "x6", "3v", "jc", "2s", "rs", "jg", "h4", "ub", "k0", "5t", "fi", "v1", "h7", "uu"});
        ENCRYPTION_MAP.put('c', new String[]{"az", "4i", "8c", "8g", "a2", "l5", "32", "8q", "92", "cf", "1j", "t6", "i2", "6k", "46", "4w", "3s", "i0", "k1", "28", "wd", "ij", "o8", "j0", "ye", "t4", "d9", "14", "y0", "86", "qo", "z1", "zj", "1d", "gh", "e9"});
        ENCRYPTION_MAP.put('d', new String[]{"nb", "w3", "d4", "a4", "h6", "td", "ab", "w9", "01", "en", "t2", "31", "r0", "gz", "y5", "9i", "yt", "rm", "p9", "cr", "5d", "vt", "7v", "v6", "3m", "j3", "2n", "zm", "ez", "ra", "ke", "ua", "dp", "k2", "20", "7q"});
        ENCRYPTION_MAP.put('e', new String[]{"3n", "vk", "1t", "hl", "rr", "mv", "qv", "tz", "gp", "oh", "ah", "xg", "0c", "17", "bg", "ai", "ka", "h5", "tm", "kr", "ww", "xu", "gi", "8d", "l8", "4q", "67", "9g", "3w", "pk", "wv", "s6", "sr", "83", "gj", "r9"});
        ENCRYPTION_MAP.put('f', new String[]{"e0", "ix", "tj", "dd", "33", "dj", "xh", "o0", "7x", "cn", "nj", "5r", "b1", "by", "zh", "8r", "i1", "mc", "kh", "dc", "d3", "sz", "xm", "zy", "5a", "bt", "vo", "2e", "8e", "ny", "lv", "bk", "rj", "ih", "0w", "mq"});
        ENCRYPTION_MAP.put('g', new String[]{"cx", "7z", "st", "7j", "aq", "fk", "sh", "hh", "wq", "mg", "90", "fp", "cz", "kc", "de", "sb", "eg", "qh", "xv", "ur", "73", "ca", "un", "ve", "ln", "yc", "i9", "ro", "b9", "iz", "mm", "gg", "e4", "fv", "hi", "ll"});
        ENCRYPTION_MAP.put('h', new String[]{"9k", "39", "dx", "kd", "ku", "dl", "5u", "bs", "ly", "cg", "ma", "bu", "ou", "z0", "rc", "44", "e2", "nk", "je", "5l", "hm", "q3", "oa", "c5", "nn", "61", "kz", "n5", "vs", "h9", "vi", "fm", "ls", "zn", "qa", "yq"});
        ENCRYPTION_MAP.put('i', new String[]{"rw", "pl", "vm", "qg", "k8", "oj", "4c", "cp", "4n", "lf", "is", "a0", "ci", "a6", "f8", "c2", "ot", "ts", "3x", "9q", "02", "t5", "ii", "re", "xl", "nd", "9b", "km", "8a", "fb", "ap", "0t", "vr", "4g", "jm", "85"});
        ENCRYPTION_MAP.put('j', new String[]{"4s", "24", "5i", "kf", "uk", "zd", "jx", "zv", "7l", "9m", "4x", "dt", "6r", "6t", "jo", "7b", "vq", "9v", "tc", "10", "ew", "3j", "ge", "47", "sk", "j5", "wm", "ir", "9h", "4l", "38", "cq", "v4", "q6", "sv", "g6"});
        ENCRYPTION_MAP.put('k', new String[]{"76", "mf", "u6", "w8", "96", "j2", "vd", "ie", "0u", "2j", "py", "vp", "8b", "ce", "v3", "ks", "ed", "g0", "y4", "rh", "h0", "o3", "ct", "gl", "dh", "od", "w1", "lb", "9d", "tg", "4p", "ok", "4b", "g7", "do", "z2"});
        ENCRYPTION_MAP.put('l', new String[]{"av", "7h", "h8", "8p", "o7", "y2", "l9", "ph", "1u", "ig", "vc", "2m", "cl", "i6", "wk", "sm", "5o", "it", "m7", "jd", "59", "iy", "ba", "50", "rt", "tv", "q1", "1o", "ds", "vl", "om", "mo", "1l", "95", "8v", "2w"});
        ENCRYPTION_MAP.put('m', new String[]{"v9", "35", "93", "3o", "ee", "lw", "pm", "qp", "7n", "wy", "4k", "7e", "0n", "5s", "tk", "da", "zu", "r1", "a1", "a7", "p1", "lz", "06", "p4", "vg", "wf", "70", "wr", "g3", "c6", "08", "5g", "0p", "6y", "3d", "lr"});
        ENCRYPTION_MAP.put('n', new String[]{"63", "27", "ey", "ws", "gw", "6l", "qy", "zo", "n3", "wl", "p6", "cb", "ql", "of", "nc", "z5", "2r", "6p", "3a", "xr", "qf", "qd", "8l", "nq", "io", "d5", "ju", "y1", "t1", "4y", "z7", "e6", "ui", "4d", "jl", "ry"});
        ENCRYPTION_MAP.put('o', new String[]{"uf", "o4", "os", "wg", "oo", "yj", "ti", "2a", "5e", "dq", "68", "ic", "sw", "c0", "jv", "45", "rf", "03", "ss", "vh", "yr", "yv", "kb", "he", "lo", "i7", "i3", "ho", "3z", "2b", "1z", "qu", "q7", "lq", "f5", "pc"});
        ENCRYPTION_MAP.put('p', new String[]{"bv", "3r", "vw", "uw", "sl", "43", "h1", "88", "1q", "57", "sc", "ys", "f1", "ht", "nl", "yo", "kx", "18", "26", "82", "ox", "d7", "wi", "uj", "m5", "7a", "6v", "tn", "my", "0q", "j8", "cw", "bw", "80", "2f", "xs"});
        ENCRYPTION_MAP.put('q', new String[]{"k9", "zb", "1r", "0i", "xo", "ei", "p2", "t8", "11", "j7", "ej", "mz", "ov", "kw", "mw", "zf", "ux", "iw", "qt", "48", "lh", "bi", "tb", "w4", "lg", "te", "xb", "tl", "wc", "gd", "m9", "x9", "3k", "uh", "se", "fn"});
        ENCRYPTION_MAP.put('r', new String[]{"22", "j1", "ac", "ha", "bc", "dv", "o9", "64", "wa", "d1", "mj", "pu", "q5", "6m", "we", "dg", "1f", "yy", "c3", "bl", "pg", "aw", "si", "br", "hz", "5f", "1i", "tw", "1s", "qs", "6n", "pt", "8j", "04", "m3", "m4"});
        ENCRYPTION_MAP.put('s', new String[]{"nv", "b7", "4a", "7m", "9w", "0a", "3c", "e3", "8n", "rb", "q2", "8z", "ga", "zk", "wj", "so", "n1", "gs", "wz", "dr", "71", "m2", "nt", "hn", "uo", "u7", "6b", "lx", "41", "3u", "fr", "kt", "8h", "65", "36", "nw"});
        ENCRYPTION_MAP.put('t', new String[]{"g4", "r3", "cc", "dm", "xw", "ib", "00", "ow", "db", "va", "l0", "xx", "ep", "7p", "pq", "z9", "mb", "ki", "8u", "9z", "07", "0s", "r4", "f4", "xn", "0r", "pw", "5h", "me", "tq", "hj", "cs", "0y", "tf", "s2", "rk"});
        ENCRYPTION_MAP.put('u', new String[]{"bj", "34", "wo", "30", "pd", "bp", "ch", "5n", "4h", "5z", "8i", "5m", "ul", "9f", "4r", "hk", "7r", "29", "ty", "ae", "jn", "9o", "c7", "kl", "9s", "rd", "x8", "er", "lm", "ef", "75", "6j", "dn", "fj", "0f", "9y"});
        ENCRYPTION_MAP.put('v', new String[]{"2g", "ko", "vf", "4o", "u0", "bh", "n6", "62", "qb", "ak", "oc", "l6", "1k", "hv", "8k", "vv", "n9", "zs", "2i", "hc", "eu", "an", "m6", "ev", "hx", "fg", "7i", "ol", "0e", "rp", "25", "8y", "pe", "rl", "fe", "zr"});
        ENCRYPTION_MAP.put('w', new String[]{"zz", "q8", "z8", "u9", "o2", "mn", "im", "pb", "6c", "jz", "6g", "ld", "sd", "94", "4f", "81", "hr", "0l", "w2", "qz", "2z", "6h", "tu", "9n", "0v", "ax", "yk", "pf", "1y", "uz", "f9", "co", "s1", "au", "w7", "d6"});
        ENCRYPTION_MAP.put('x', new String[]{"g9", "oq", "mp", "jp", "qn", "t7", "gb", "ps", "eb", "pv", "13", "c8", "a9", "w0", "dy", "ex", "05", "7g", "u2", "40", "wx", "r2", "x4", "4u", "p7", "6w", "js", "et", "r8", "m1", "4e", "cu", "nh", "5c", "qq", "lc"});
        ENCRYPTION_MAP.put('y', new String[]{"8f", "el", "vy", "p0", "dw", "t3", "ag", "i5", "e7", "s7", "p8", "2k", "u4", "wb", "p5", "c1", "xd", "5y", "qr", "4m", "yx", "5j", "3t", "ne", "mx", "2u", "b6", "eq", "16", "1h", "1x", "l2", "bn", "iu", "qk", "es"});
        ENCRYPTION_MAP.put('z', new String[]{"ta", "bo", "y3", "3g", "pp", "0o", "hy", "fl", "yn", "i8", "ud", "f2", "b2", "hf", "gn", "hs", "x1", "pz", "qx", "ob", "sf", "lt", "6q", "lp", "4t", "r7", "ad", "f3", "fc", "9c", "go", "v5", "tt", "qw", "um", "b5"});
        ENCRYPTION_MAP.put('0', new String[]{"u1", "b0", "cv", "ug", "no", "sn", "o1", "ky", "kv", "1w", "b8", "p3", "84", "yl", "1p", "k7", "97", "yh", "fh", "5p", "2x", "rx", "po", "mu", "cm", "r6", "rv", "oe", "f0", "7o", "dz", "df", "vx", "6s", "fw", "15"});
        ENCRYPTION_MAP.put('1', new String[]{"x0", "7s", "6a", "gx", "pr", "n8", "z6", "60", "gy", "bm", "qi", "8w", "19", "2l", "la", "89", "x5", "1b", "kp", "lj", "xy", "9x", "dk", "zc", "af", "f7", "ay", "le", "3f", "ng", "f6", "if", "1g", "z4", "i4", "kn"});
        ENCRYPTION_MAP.put('2', new String[]{"n4", "8x", "m0", "w6", "ar", "u5", "5k", "zl", "za", "e1", "np", "69", "bd", "g1", "zx", "or", "xa", "c4", "g8", "iv", "fd", "77", "pj", "6e", "12", "v8", "6i", "4z", "0g", "wp", "o5", "3y", "xi", "6z", "5b", "nx"});
        ENCRYPTION_MAP.put('3', new String[]{"6d", "wt", "xf", "vj", "ip", "52", "gf", "s0", "ns", "pn", "y9", "di", "px", "jf", "wu", "7y", "51", "5q", "at", "du", "8t", "gc", "bx", "fy", "49", "lk", "gv", "6u", "in", "ec", "9j", "99", "uq", "kq", "x7", "fu"});
        ENCRYPTION_MAP.put('4', new String[]{"0j", "oi", "sa", "7u", "y6", "hw", "il", "79", "mk", "op", "66", "cd", "9l", "ms", "vu", "jq", "0d", "l7", "42", "ym", "wn", "yu", "a5", "yg", "q4", "2o", "7k", "9u", "2p", "jj", "us", "9a", "o6", "21", "k3", "s9"});
        ENCRYPTION_MAP.put('5', new String[]{"nf", "bf", "yi", "ik", "hp", "v7", "vb", "sy", "5x", "74", "gr", "wh", "kk", "v0", "w5", "7t", "fo", "tp", "ck", "ji", "b3", "9r", "l3", "h3", "hb", "8m", "k6", "ri", "9p", "fx", "bz", "vz", "yp", "nr", "bq", "aj"});
        ENCRYPTION_MAP.put('6', new String[]{"kj", "sg", "s4", "on", "as", "rg", "jy", "iq", "be", "q9", "ea", "d8", "3b", "lu", "98", "2c", "3l", "x3", "7f", "ao", "yd", "8o", "xc", "rn", "3i", "oz", "6o", "a3", "7c", "gm", "t9", "oy", "mi", "fa", "6f", "ru"});
        ENCRYPTION_MAP.put('7', new String[]{"37", "cy", "nm", "zq", "j9", "u8", "6x", "sx", "3e", "91", "1m", "x2", "nz", "z3", "2v", "0z", "87", "zt", "gu", "t0", "2y", "nu", "jb", "5v", "cj", "yb", "sq", "al", "mt", "ue", "78", "b4", "ia", "xt", "md", "jw"});
        ENCRYPTION_MAP.put('8', new String[]{"ff", "0h", "ft", "hd", "yf", "3h", "bb", "zi", "23", "ml", "3q", "zp", "li", "4j", "0k", "fs", "mh", "jk", "u3", "og", "xj", "hg", "9e", "eo", "na", "2q", "d0", "zw", "09", "s5", "yz", "kg", "c9", "ut", "7d", "sj"});
        ENCRYPTION_MAP.put('9', new String[]{"54", "gk", "th", "s3", "e8", "0b", "8s", "58", "s8", "1a", "jt", "pi", "1e", "tx", "qm", "0x", "rq", "1n", "jr", "l4", "fz", "j4", "5w", "ya", "sp", "rz", "1c", "55", "to", "gq", "fq", "up", "qc", "xk", "m8", "uv"});
        ENCRYPTION_MAP.put('-', new String[]{"-"});

        for (Map.Entry<Character, String[]> entry : ENCRYPTION_MAP.entrySet()) {
            char originalChar = entry.getKey();
            String[] encryptedValues = entry.getValue();
            for (String encryptedValue : encryptedValues) {
                DECRYPTION_MAP.put(encryptedValue, originalChar);
            }
        }
    }

    public static String decrypt(String encryptedString) {
        StringBuilder decryptedString = new StringBuilder();
        int i = 0;
        while (i < encryptedString.length()) {
            String sub = encryptedString.substring(i, Math.min(i + 2, encryptedString.length()));
            if (DECRYPTION_MAP.containsKey(sub)) {
                decryptedString.append(DECRYPTION_MAP.get(sub));
                i += 2;
            } else {
                decryptedString.append("-");
                i += 1;
            }
        }
        return decryptedString.toString();
    }

    public static String multiDecrypt(String encryptedUUID, int times) {
        String result = encryptedUUID;
        for (int i = 0; i < times; i++) {
            result = decrypt(result);
        }
        return result;
    }
}
