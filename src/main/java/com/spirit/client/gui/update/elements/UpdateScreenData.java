package com.spirit.client.gui.update.elements;

import com.google.gson.*;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import com.spirit.koil.api.util.web.WebFileDownloader;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.*;

import static com.spirit.Main.SUBLOGGER;
import static com.spirit.Main.activeKoilBranch;
import static com.spirit.Main.version;
import static com.spirit.koil.api.design.uiColorVal.*;

public final class UpdateScreenData {
    public static final String DATA_URL = "https://raw.githubusercontent.com/Koil-public/koil-online-data/main/data.json";
    public static final Path DATA_PATH = Paths.get("./koil/sys/data.json");
    public static final String DEFAULT_MODRINTH_PROJECT = "koil";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int UPDATE_UI_COLOR = new Color(0xA7, 0x00, 0x3A).getRGB();
    private static final int UPDATE_DEBUG_COLOR = new Color(0x2D, 0xA7, 0x00).getRGB();
    private static final int UPDATE_API_COLOR = new Color(0x00, 0x85, 0xA4).getRGB();
    private static final int UPDATE_CONSOLE_COLOR = new Color(0x74, 0x00, 0xA4).getRGB();
    private static UpdateData cachedData;
    private static long cachedModified = -1L;

    private UpdateScreenData() {
    }

    public static void refreshOnlineData() {
        try {
            Files.createDirectories(DATA_PATH.getParent());
            WebFileDownloader.downloadFile(DATA_URL, "data.json", "./koil/sys", 16);
        } catch (Exception e) {
            SUBLOGGER.logE("Update thread", "Failed to refresh Koil update data: " + e.getMessage());
        }
    }

    public static UpdateData readLocalData() {
        try {
            if (!Files.exists(DATA_PATH)) {
                writeDefaultData();
            }
            long modified = Files.getLastModifiedTime(DATA_PATH).toMillis();
            if (cachedData != null && modified == cachedModified) {
                return cachedData;
            }
            try (Reader reader = Files.newBufferedReader(DATA_PATH, StandardCharsets.UTF_8)) {
                UpdateData data = GSON.fromJson(reader, UpdateData.class);
                cachedData = normalize(data);
                cachedModified = modified;
                return cachedData;
            }
        } catch (Exception e) {
            SUBLOGGER.logE("Update thread", "Failed to read Koil update data: " + e.getMessage());
            cachedData = normalize(new UpdateData());
            return cachedData;
        }
    }

    public static void writeDefaultData() {
        try {
            Files.createDirectories(DATA_PATH.getParent());
            Files.writeString(DATA_PATH, defaultDataJson(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            SUBLOGGER.logE("Update thread", "Failed to write default Koil update data: " + e.getMessage());
        }
    }

    private static UpdateData normalize(UpdateData data) {
        if (data == null) {
            data = new UpdateData();
        }
        if (data.modrinthProject == null || data.modrinthProject.isBlank()) {
            data.modrinthProject = DEFAULT_MODRINTH_PROJECT;
        }
        if (data.githubJarBaseUrl == null || data.githubJarBaseUrl.isBlank()) {
            data.githubJarBaseUrl = "https://github.com/Koil-public/koil-online-data/raw/main";
        }
        if (data.balanceMargin <= 0) {
            data.balanceMargin = 10;
        }
        if (data.updateTypes == null || data.updateTypes.isEmpty()) {
            data.updateTypes = defaultTypes();
        }
        if (data.branches == null || data.branches.isEmpty()) {
            data.branches = defaultBranches();
        }
        if (data.releases == null || data.releases.isEmpty()) {
            data.releases = legacyReleases(data);
            if (data.releases.isEmpty()) {
                data.releases = defaultReleases();
            }
        }
        for (Release release : data.releases) {
            if (release.name == null) {
                release.name = "Koil Update";
            }
            if (release.version == null) {
                release.version = version();
            }
            if (release.branch == null || release.branch.isBlank()) {
                release.branch = "public";
            }
            if (release.percentages == null) {
                release.percentages = new LinkedHashMap<>();
            }
            if (release.sections == null) {
                release.sections = new ArrayList<>();
            }
            if (release.files == null) {
                release.files = new ArrayList<>();
            }
        }
        return data;
    }

    public static boolean isBetaTester() {
        return readBooleanConfig("isBetaTesting") || readBooleanConfig("isBetaTester") || readBooleanConfig("betaTester") || readBooleanConfig("beta");
    }

    private static boolean readBooleanConfig(String key) {
        try {
            JsonElement element = JSONFileEditor.getValueFromJson("./koil/sys/config.json", key);
            return element != null && element.isJsonPrimitive() && element.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static List<Branch> visibleBranches(UpdateData data, boolean betaTester) {
        List<Branch> output = new ArrayList<>();
        for (Branch branch : data.branches) {
            if (branch == null || branch.key == null) {
                continue;
            }
            if (!branch.betaOnly || betaTester) {
                output.add(branch);
            }
        }
        if (output.isEmpty()) {
            output.add(new Branch("public", "Public", "public", false, "Modrinth public release branch."));
        }
        return output;
    }

    public static List<Release> releasesForBranch(UpdateData data, String branchKey, boolean betaTester) {
        List<Release> output = new ArrayList<>();
        for (Release release : data.releases) {
            if (release == null) {
                continue;
            }
            if (!betaTester && !"public".equalsIgnoreCase(release.branch)) {
                continue;
            }
            if (branchKey == null || branchKey.isBlank() || normalizeBranchKey(branchKey).equalsIgnoreCase(normalizeBranchKey(release.branch))) {
                output.add(release);
            }
        }
        output.sort((a, b) -> compareVersions(b.version, a.version));
        return output;
    }

    public static Release newestRelease(UpdateData data, String branchKey, boolean betaTester) {
        List<Release> releases = releasesForBranch(data, branchKey, betaTester);
        if (releases.isEmpty()) {
            return null;
        }
        return releases.get(0);
    }

    public static String displayType(UpdateData data, Release release) {
        String key = dominantTypeKey(data, release);
        UpdateType type = data.updateTypes.get(key);
        if (type == null) {
            return "Other";
        }
        return type.label == null || type.label.isBlank() ? key : type.label;
    }

    public static String dominantTypeKey(UpdateData data, Release release) {
        if (release == null || release.percentages == null || release.percentages.isEmpty()) {
            return "combined";
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        String maxKey = "combined";
        for (Map.Entry<String, Integer> entry : release.percentages.entrySet()) {
            int value = entry.getValue() == null ? 0 : entry.getValue();
            min = Math.min(min, value);
            max = Math.max(max, value);
            if (value > valueOf(release.percentages, maxKey)) {
                maxKey = entry.getKey();
            }
        }
        if (max - min <= Math.max(5, data.balanceMargin)) {
            return "combined";
        }
        return maxKey;
    }

    public static boolean isRemoteNewer(String localVersion, String remoteVersion) {
        return compareVersions(remoteVersion, localVersion) > 0;
    }

    public static Release releaseForVersion(UpdateData data, String branchKey, boolean betaTester, String version) {
        String normalizedBranch = normalizeBranchKey(branchKey);
        for (Release release : releasesForBranch(data, normalizedBranch, betaTester)) {
            if (release.version != null && release.version.equalsIgnoreCase(version)) {
                return release;
            }
        }
        Release newest = newestRelease(data, normalizedBranch, betaTester);
        return newest != null && newest.version != null && newest.version.equalsIgnoreCase(version) ? newest : null;
    }

    public static Release closestReleaseForVersion(UpdateData data, String branchKey, boolean betaTester, String version) {
        String normalizedBranch = normalizeBranchKey(branchKey);
        List<Release> branchReleases = releasesForBranch(data, normalizedBranch, betaTester);
        if (branchReleases.isEmpty()) {
            return null;
        }

        String targetFamily = versionFamily(version);
        for (Release release : branchReleases) {
            if (release.version != null && versionFamily(release.version).equalsIgnoreCase(targetFamily)) {
                return release;
            }
        }

        String targetBase = numericVersionBase(version);
        for (Release release : branchReleases) {
            if (release.version != null && numericVersionBase(release.version).equalsIgnoreCase(targetBase)) {
                return release;
            }
        }

        return branchReleases.get(0);
    }

    private static String versionFamily(String version) {
        String value = version == null ? "" : version.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return "";
        }
        int jarIndex = value.endsWith(".jar") ? value.length() - 4 : value.length();
        value = value.substring(0, jarIndex);
        int frequentIndex = value.indexOf("-frequent.");
        if (frequentIndex >= 0) {
            return value.substring(0, frequentIndex) + "-frequent";
        }
        int unfinishedIndex = value.indexOf("-unfinished.");
        if (unfinishedIndex >= 0) {
            return value.substring(0, unfinishedIndex) + "-unfinished";
        }
        return numericVersionBase(value);
    }

    private static String numericVersionBase(String version) {
        String value = version == null ? "" : version.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("koil-")) {
            value = value.substring("koil-".length());
        }
        if (value.endsWith(".jar")) {
            value = value.substring(0, value.length() - 4);
        }
        int suffixIndex = value.indexOf('-');
        if (suffixIndex >= 0) {
            value = value.substring(0, suffixIndex);
        }
        return value;
    }

    public static String normalizeBranchKey(String branch) {
        String key = branch == null ? "public" : branch.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith(".")) {
            key = key.substring(1);
        }
        if ("beta".equals(key) || "unfinished".equals(key) || "unfin".equals(key)) {
            return "unfinished";
        }
        if ("freq".equals(key) || "frequent".equals(key)) {
            return "frequent";
        }
        if ("public".equals(key) || "release".equals(key) || "stable".equals(key)) {
            return "public";
        }
        return key.isBlank() ? "public" : key;
    }

    public static com.spirit.client.gui.main.KoilUpdateToast.Type toastTypeForRelease(UpdateData data, Release release) {
        String key = dominantTypeKey(data, release);
        if ("ui".equalsIgnoreCase(key)) {
            return com.spirit.client.gui.main.KoilUpdateToast.Type.UPDATE_UI;
        }
        if ("debug".equalsIgnoreCase(key)) {
            return com.spirit.client.gui.main.KoilUpdateToast.Type.UPDATE_DEBUG;
        }
        if ("api".equalsIgnoreCase(key)) {
            return com.spirit.client.gui.main.KoilUpdateToast.Type.UPDATE_API;
        }
        if ("console".equalsIgnoreCase(key)) {
            return com.spirit.client.gui.main.KoilUpdateToast.Type.UPDATE_CONSOLE;
        }
        return com.spirit.client.gui.main.KoilUpdateToast.Type.UPDATE_OTHER;
    }

    private static int valueOf(Map<String, Integer> map, String key) {
        Integer value = map.get(key);
        return value == null ? Integer.MIN_VALUE : value;
    }

    public static int colorForType(UpdateData data, String typeKey) {
        if ("ui".equalsIgnoreCase(typeKey)) {
            return UPDATE_UI_COLOR;
        }
        if ("debug".equalsIgnoreCase(typeKey)) {
            return UPDATE_DEBUG_COLOR;
        }
        if ("api".equalsIgnoreCase(typeKey)) {
            return UPDATE_API_COLOR;
        }
        if ("console".equalsIgnoreCase(typeKey)) {
            return UPDATE_CONSOLE_COLOR;
        }
        UpdateType type = data.updateTypes.get(typeKey);
        if (type == null || type.color == null || type.color.isBlank()) {
            return parseHex("#8c88b5");
        }
        return parseHex(type.color);
    }

    public static String toastTextureForRelease(UpdateData data, Release release) {
        String key = dominantTypeKey(data, release);
        UpdateType type = data.updateTypes.get(key);
        if (type != null && type.toast != null && !type.toast.isBlank()) {
            return type.toast;
        }
        return "combined".equals(key) ? "koil_update_toasts.png:combined" : "koil_update_toasts.png:" + key;
    }

    public static DownloadResult downloadSelected(UpdateData data, Branch branch, Release release, String selectedFileName) {
        if (branch == null || "public".equalsIgnoreCase(branch.key)) {
            return downloadPublicModrinth(data);
        }
        String fileName = selectedFileName;
        if (fileName == null || fileName.isBlank()) {
            fileName = defaultJarFileName(release);
        }
        String base = trimSlash(data.githubJarBaseUrl);
        String branchPath = release != null && release.githubPath != null && !release.githubPath.isBlank()
                ? release.githubPath
                : branch.githubPath == null || branch.githubPath.isBlank() ? branch.key : branch.githubPath;
        branchPath = normalizeGithubBranchPath(branchPath);
        String url = branchPath.isBlank() ? base + "/" + fileName : base + "/" + branchPath + "/" + fileName;
        return downloadJar(url, fileName);
    }

    public static DownloadResult downloadPublicModrinth(UpdateData data) {
        try {
            String apiUrl = "https://api.modrinth.com/v2/project/" + url(data.modrinthProject) + "/version";
            String json = httpGet(apiUrl);
            JsonArray versions = JsonParser.parseString(json).getAsJsonArray();
            if (versions.isEmpty()) {
                return new DownloadResult(false, "Modrinth returned no Koil versions.", null);
            }
            JsonObject versionObject = versions.get(0).getAsJsonObject();
            JsonArray files = versionObject.getAsJsonArray("files");
            if (files == null || files.isEmpty()) {
                return new DownloadResult(false, "Modrinth version has no files.", null);
            }
            JsonObject selected = files.get(0).getAsJsonObject();
            for (JsonElement element : files) {
                JsonObject file = element.getAsJsonObject();
                if (file.has("primary") && file.get("primary").getAsBoolean()) {
                    selected = file;
                    break;
                }
            }
            String fileName = selected.get("filename").getAsString();
            String url = selected.get("url").getAsString();
            return downloadJar(url, fileName);
        } catch (Exception e) {
            return new DownloadResult(false, "Public download failed: " + e.getMessage(), null);
        }
    }

    public static DownloadResult downloadJar(String downloadUrl, String fileName) {
        try {
            if (fileName == null || fileName.isBlank()) {
                fileName = "koil-" + version() + ".jar";
            }
            WebFileDownloader.downloadFile(downloadUrl, fileName, "./mods", 64);
            deleteOldJars(fileName);
            return new DownloadResult(true, "Downloaded " + fileName, Paths.get("./mods", fileName));
        } catch (Exception e) {
            return new DownloadResult(false, "Download failed: " + e.getMessage(), null);
        }
    }

    private static void deleteOldJars(String currentFileName) {
        File modsDir = new File("./mods");
        File[] files = modsDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).startsWith("koil-") && name.toLowerCase(Locale.ROOT).endsWith(".jar") && !name.equals(currentFileName));
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.delete()) {
                SUBLOGGER.logI("Update thread", "Deleted old Koil mod jar: " + file.getName());
            } else {
                SUBLOGGER.logE("Update thread", "Failed to delete old Koil mod jar: " + file.getName());
            }
        }
    }

    public static String defaultJarFileName(Release release) {
        if (release != null && release.files != null && !release.files.isEmpty()) {
            return release.files.get(0).fileName;
        }
        return "koil-" + version() + ".jar";
    }

    public static int renderReleaseTimeline(DrawContext context, TextRenderer textRenderer, UpdateData data, List<Release> releases, int x, int y, int width, int scroll, int topClip, int bottomClip, boolean showBranch) {
        return renderReleaseTimelineInteractive(context, textRenderer, data, releases, x, y, width, scroll, topClip, bottomClip, showBranch, -1, -1).contentHeight;
    }

    public static TimelineRenderResult renderReleaseTimelineInteractive(DrawContext context, TextRenderer textRenderer, UpdateData data, List<Release> releases, int x, int y, int width, int scroll, int topClip, int bottomClip, boolean showBranch, int mouseX, int mouseY) {
        int timelineX = x + 10;
        int contentX = x + 30;
        int drawY = y - scroll;
        int startY = drawY;
        int textColor = new Color(uiColorContentBaseDescriptionText, true).getRGB();
        int titleColor = new Color(uiColorContentBaseTitleText, true).getRGB();
        int subColor = new Color(uiColorHeaderSubTitleText, true).getRGB();
        int borderColor = new Color(uiColorBackgroundBorder, true).getRGB();
        int railFill = dim(darken(new Color(uiColorHeaderSubTitleText, true).getRGB(), 0.45F), 120);
        TimelineRenderResult result = new TimelineRenderResult();
        int totalHeight = calculateTimelineHeight(textRenderer, releases, width);
        int railTop = y - scroll + 9;
        int railBottom = y - scroll + Math.max(18, totalHeight - 18);
        if (!releases.isEmpty() && railBottom >= topClip && railTop <= bottomClip) {
            drawTimelineRail(context, timelineX, Math.max(topClip, railTop), Math.min(bottomClip, railBottom), borderColor, railFill);
        }
        for (Release release : releases) {
            String typeKey = dominantTypeKey(data, release);
            int accent = colorForType(data, typeKey);
            int headerHeight = 39;
            int sectionHeight = 0;
            for (ReleaseSection section : release.sections) {
                sectionHeight += 15;
                if (section.items != null) {
                    for (String item : section.items) {
                        sectionHeight += Math.max(12, textRenderer.wrapLines(Text.literal(item), Math.max(80, width - 92)).size() * 11);
                    }
                }
                sectionHeight += 4;
            }
            int releaseHeight = headerHeight + sectionHeight + 16;
            boolean visible = drawY + releaseHeight >= topClip && drawY <= bottomClip;
            if (visible) {
                int nodeY = drawY + 9;
                int barWidth = Math.min(198, width - 58);
                drawTimelineConnector(context, timelineX, nodeY, contentX - 8, borderColor, railFill);
                drawTimelineNode(context, timelineX, nodeY, accent, borderColor);
                context.drawText(textRenderer, release.name, contentX, drawY, titleColor, true);
                drawTechnicalUnderline(context, contentX, drawY + 11, contentX + textRenderer.getWidth(release.name), accent);
                int chipX = contentX;
                chipX = drawMetaChip(context, textRenderer, chipX, drawY + 15, displayType(data, release), accent);
                chipX = drawMetaChip(context, textRenderer, chipX + 4, drawY + 15, release.version, new Color(uiColorHeaderSubTitleText, true).getRGB());
                if (showBranch) {
                    drawMetaChip(context, textRenderer, chipX + 4, drawY + 15, branchLabel(data, release.branch), accent);
                }
                drawPercentageBar(context, data, release, contentX, drawY + 29, barWidth, 6);
                if (inside(mouseX, mouseY, timelineX - 8, nodeY - 8, 16, 16)) {
                    result.tooltipTitle = release.name;
                    result.tooltipAccent = accent;
                    result.tooltipLines = tooltipLinesForRelease(data, release);
                } else if (inside(mouseX, mouseY, contentX, drawY, Math.min(width - 48, 260), 28)) {
                    result.tooltipTitle = release.version + " release data";
                    result.tooltipAccent = accent;
                    result.tooltipLines = tooltipLinesForRelease(data, release);
                } else if (inside(mouseX, mouseY, contentX, drawY + 27, barWidth + 2, 10)) {
                    result.tooltipTitle = "Update type mix";
                    result.tooltipAccent = accent;
                    result.tooltipLines = percentageTooltipLines(data, release);
                }
            }
            drawY += headerHeight;
            for (ReleaseSection section : release.sections) {
                if (drawY >= topClip && drawY <= bottomClip) {
                    drawSectionConnector(context, contentX, drawY, accent, borderColor);
                    context.drawText(textRenderer, section.title, contentX + 18, drawY, subColor, false);
                }
                drawY += 15;
                if (section.items != null) {
                    for (String item : section.items) {
                        List<OrderedText> lines = textRenderer.wrapLines(Text.literal(item), Math.max(80, width - 110));
                        if (drawY + 10 >= topClip && drawY <= bottomClip) {
                            context.fill(contentX + 10, drawY + 4, contentX + 14, drawY + 8, dim(borderColor, 160));
                            context.fill(contentX + 11, drawY + 5, contentX + 13, drawY + 7, dim(subColor, 160));
                            context.fill(contentX + 16, drawY + 6, contentX + 21, drawY + 7, dim(borderColor, 120));
                        }
                        for (OrderedText line : lines) {
                            if (drawY >= topClip && drawY <= bottomClip) {
                                context.drawText(textRenderer, line, contentX + 24, drawY, textColor, false);
                            }
                            drawY += 11;
                        }
                    }
                }
                drawY += 4;
            }
            drawY += 16;
        }
        result.contentHeight = Math.max(0, drawY - startY);
        return result;
    }

    private static int drawMetaChip(DrawContext context, TextRenderer textRenderer, int x, int y, String text, int accent) {
        String value = text == null || text.isBlank() ? "unknown" : text;
        int width = Math.min(140, textRenderer.getWidth(value) + 12);
        context.fill(x, y, x + width, y + 11, dim(darken(new Color(uiColorContentBase, true).getRGB(), 0.12F), 184));
        context.drawBorder(x, y, width, 11, dim(new Color(uiColorBackgroundBorder, true).getRGB(), 178));
        context.fill(x + 3, y + 9, x + width - 3, y + 10, dim(accent, 132));
        context.drawText(textRenderer, value.length() > 20 ? value.substring(0, 17) + "..." : value, x + 6, y + 2, lighten(accent, 0.18F), false);
        return x + width;
    }

    private static void drawSectionConnector(DrawContext context, int x, int y, int accent, int borderColor) {
        context.fill(x + 5, y + 5, x + 15, y + 6, dim(borderColor, 128));
        context.fill(x + 3, y + 3, x + 8, y + 8, dim(borderColor, 170));
        context.fill(x + 4, y + 4, x + 7, y + 7, dim(accent, 188));
    }

    public static void renderHoverPopup(DrawContext context, TextRenderer textRenderer, TimelineRenderResult result, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        if (result == null || result.tooltipLines == null || result.tooltipLines.isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        if (result.tooltipTitle != null && !result.tooltipTitle.isBlank()) {
            lines.add(result.tooltipTitle);
        }
        lines.addAll(result.tooltipLines);
        int maxLineWidth = 0;
        for (String line : lines) {
            maxLineWidth = Math.max(maxLineWidth, textRenderer.getWidth(line));
        }
        int width = Math.min(Math.max(118, maxLineWidth + 18), 282);
        int accent = result.tooltipAccent == 0 ? new Color(uiColorHeaderSubTitleText, true).getRGB() : result.tooltipAccent;
        int title = new Color(uiColorContentBaseTitleText, true).getRGB();
        int key = new Color(uiColorHeaderSubTitleText, true).getRGB();
        int value = new Color(uiColorContentBaseDescriptionText, true).getRGB();
        List<Text> tooltip = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 0) {
                tooltip.add(Text.literal(line).setStyle(Style.EMPTY.withColor(accent).withBold(true)));
                continue;
            }
            if (line.contains(":")) {
                String left = line.substring(0, line.indexOf(":") + 1);
                String right = line.substring(line.indexOf(":") + 1).trim();
                tooltip.add(
                        Text.literal(left + " ").setStyle(Style.EMPTY.withColor(key))
                                .append(Text.literal(right).setStyle(Style.EMPTY.withColor(valueForTooltipLine(line, accent, value))))
                );
            } else {
                tooltip.add(Text.literal(line).setStyle(Style.EMPTY.withColor(value)));
            }
        }
        context.drawTooltip(textRenderer, tooltip, Optional.empty(), mouseX, mouseY);
    }

    private static void drawTimelineRail(DrawContext context, int x, int y1, int y2, int borderColor, int fillColor) {
        if (y2 <= y1) {
            return;
        }
        context.fill(x, y1, x + 1, y2, dim(borderColor, 170));
        context.fill(x + 1, y1, x + 2, y2, dim(fillColor, 78));
    }

    private static void drawTimelineConnector(DrawContext context, int x1, int y, int x2, int borderColor, int fillColor) {
        if (x2 <= x1) {
            return;
        }
        context.fill(x1 + 3, y, x2, y + 1, dim(borderColor, 145));
        context.fill(x1 + 3, y + 1, x2, y + 2, dim(fillColor, 72));
    }

    private static void drawTimelineNode(DrawContext context, int x, int y, int accent, int borderColor) {
        context.fill(x - 5, y - 5, x + 6, y + 6, borderColor);
        context.fill(x - 4, y - 4, x + 5, y + 5, dim(darken(new Color(uiColorContentBase, true).getRGB(), 0.15F), 238));
        context.fill(x - 2, y - 2, x + 3, y + 3, dim(accent, 190));
    }

    private static List<String> percentageTooltipLines(UpdateData data, Release release) {
        List<String> lines = new ArrayList<>();
        lines.add("Dominant: " + displayType(data, release));
        lines.add("Toast: " + toastTextureForRelease(data, release));
        if (release.percentages != null && !release.percentages.isEmpty()) {
            for (Map.Entry<String, Integer> entry : release.percentages.entrySet()) {
                UpdateType type = data.updateTypes.get(entry.getKey());
                String label = type == null || type.label == null ? entry.getKey() : type.label;
                lines.add(label + ": " + (entry.getValue() == null ? 0 : entry.getValue()) + "%");
            }
        }
        return lines;
    }

    private static int valueForTooltipLine(String line, int accent, int fallback) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.startsWith("dominant:") || lower.startsWith("type:")) {
            return lighten(accent, 0.18F);
        }
        if (lower.startsWith("version:") || lower.startsWith("branch:")) {
            return new Color(120, 210, 255).getRGB();
        }
        if (lower.startsWith("date:") || lower.startsWith("toast:")) {
            return new Color(190, 180, 255).getRGB();
        }
        if (line.endsWith("%")) {
            return accent;
        }
        return fallback;
    }

    private static int calculateTimelineHeight(TextRenderer textRenderer, List<Release> releases, int width) {
        int total = 0;
        for (Release release : releases) {
            int height = 39;
            for (ReleaseSection section : release.sections) {
                height += 15;
                if (section.items != null) {
                    for (String item : section.items) {
                        height += Math.max(12, textRenderer.wrapLines(Text.literal(item), Math.max(80, width - 92)).size() * 11);
                    }
                }
                height += 4;
            }
            total += height + 16;
        }
        return total;
    }

    private static List<String> tooltipLinesForRelease(UpdateData data, Release release) {
        List<String> lines = new ArrayList<>();
        lines.add("Type: " + displayType(data, release));
        lines.add("Version: " + release.version);
        lines.add("Branch: " + branchLabel(data, release.branch));
        if (release.date != null && !release.date.isBlank()) {
            lines.add("Date: " + release.date);
        }
        if (release.percentages != null && !release.percentages.isEmpty()) {
            StringBuilder mix = new StringBuilder("Mix: ");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : release.percentages.entrySet()) {
                if (!first) {
                    mix.append(" / ");
                }
                first = false;
                mix.append(entry.getKey()).append(" ").append(entry.getValue()).append("%");
            }
            lines.add(mix.toString());
        }
        return lines;
    }

    private static void drawPercentageBar(DrawContext context, UpdateData data, Release release, int x, int y, int width, int height) {
        int offset = 0;
        int total = 0;
        for (Integer value : release.percentages.values()) {
            total += Math.max(0, value == null ? 0 : value);
        }
        if (total <= 0) {
            total = 100;
        }
        context.fill(x - 1, y - 1, x + width + 1, y + height + 1, new Color(uiColorBackgroundBorder, true).getRGB());
        for (Map.Entry<String, Integer> entry : release.percentages.entrySet()) {
            int part = Math.round((Math.max(0, entry.getValue() == null ? 0 : entry.getValue()) / (float) total) * width);
            int color = colorForType(data, entry.getKey());
            context.fill(x + offset, y, x + Math.min(width, offset + part), y + height, dim(color, 205));
            context.fill(x + offset, y, x + Math.min(width, offset + part), y + 1, dim(lighten(color, 0.14F), 132));
            offset += part;
        }
        context.drawBorder(x - 1, y - 1, width + 2, height + 2, new Color(uiColorBackgroundBorder, true).getRGB());
    }

    private static void drawTechnicalUnderline(DrawContext context, int x1, int y, int x2, int color) {
        context.fill(x1, y, x2, y + 1, dim(new Color(uiColorBackgroundBorder, true).getRGB(), 185));
        context.fill(x1, y + 1, x2, y + 2, dim(color, 128));
    }

    private static void drawUnderline(DrawContext context, int x1, int y, int x2, int color) {
        drawTechnicalUnderline(context, x1, y, x2, color);
    }

    private static int dim(int rgb, int alpha) {
        Color c = new Color(rgb);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha).getRGB();
    }

    private static int darken(int rgb, float amount) {
        Color c = new Color(rgb);
        int r = Math.round(c.getRed() * (1.0F - amount));
        int g = Math.round(c.getGreen() * (1.0F - amount));
        int b = Math.round(c.getBlue() * (1.0F - amount));
        return new Color(Math.max(0, r), Math.max(0, g), Math.max(0, b), c.getAlpha()).getRGB();
    }

    private static int lighten(int rgb, float amount) {
        Color c = new Color(rgb);
        int r = c.getRed() + Math.round((255 - c.getRed()) * amount);
        int g = c.getGreen() + Math.round((255 - c.getGreen()) * amount);
        int b = c.getBlue() + Math.round((255 - c.getBlue()) * amount);
        return new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b), c.getAlpha()).getRGB();
    }

    private static boolean inside(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static String branchLabel(UpdateData data, String key) {
        if (data.branches != null) {
            for (Branch branch : data.branches) {
                if (branch.key.equalsIgnoreCase(key)) {
                    return branch.label;
                }
            }
        }
        return key;
    }

    private static int parseHex(String hex) {
        try {
            String value = hex.startsWith("#") ? hex.substring(1) : hex;
            return new Color(Integer.parseInt(value, 16)).getRGB();
        } catch (Exception ignored) {
            return new Color(99, 95, 137).getRGB();
        }
    }

    private static String trimSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String normalizeGithubBranchPath(String value) {
        String path = value == null ? "" : value.trim();
        if (path.equals(".unfinished") || path.equals("unfinished") || path.equals(".frequent") || path.equals("frequent")) {
            return "";
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static String url(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20");
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "Koil Update System");
        try (InputStream stream = connection.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static int compareVersions(String left, String right) {
        String[] a = safeVersion(left).split("\\.");
        String[] b = safeVersion(right).split("\\.");
        int max = Math.max(a.length, b.length);
        for (int i = 0; i < max; i++) {
            int ai = i < a.length ? parseInt(a[i]) : 0;
            int bi = i < b.length ? parseInt(b[i]) : 0;
            if (ai != bi) {
                return Integer.compare(ai, bi);
            }
        }
        return safeVersion(left).compareToIgnoreCase(safeVersion(right));
    }

    private static String safeVersion(String value) {
        return value == null ? "0" : value.replaceAll("[^0-9.]", "");
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static Map<String, UpdateType> defaultTypes() {
        Map<String, UpdateType> types = new LinkedHashMap<>();
        types.put("ui", new UpdateType("UI Integration and UI Development", "#a7003a", "koil_update_toasts.png:red"));
        types.put("debug", new UpdateType("In-Game Debugging", "#2da700", "koil_update_toasts.png:green"));
        types.put("api", new UpdateType("J-API and Datapack Modding", "#0085a4", "koil_update_toasts.png:blue"));
        types.put("console", new UpdateType("Console Gameplay", "#7400a4", "koil_update_toasts.png:purple"));
        types.put("combined", new UpdateType("All Balanced", "#8c88b5", "koil_update_toasts.png:combined"));
        return types;
    }

    private static List<Branch> defaultBranches() {
        List<Branch> branches = new ArrayList<>();
        branches.add(new Branch("public", "Public", "public", false, "Downloads the newest public Koil jar from Modrinth."));
        branches.add(new Branch("unfinished", ".unfinished", ".unfinished", true, "Downloads unfinished beta jars from GitHub."));
        branches.add(new Branch("frequent", ".frequent", ".frequent", true, "Downloads frequent beta jars from GitHub."));
        return branches;
    }

    private static List<Release> defaultReleases() {
        List<Release> releases = new ArrayList<>();
        Release release = new Release();
        release.name = "Koil Development Update";
        release.version = version();
        release.branch = activeKoilBranch();
        release.githubPath = activeKoilBranch().equals("public") ? "public" : "." + activeKoilBranch();
        release.percentages = new LinkedHashMap<>();
        release.percentages.put("ui", 45);
        release.percentages.put("debug", 20);
        release.percentages.put("api", 20);
        release.percentages.put("console", 15);
        release.files = new ArrayList<>();
        release.files.add(defaultReleaseFile("Default jar", "koil-" + version() + ".jar"));
        release.sections = new ArrayList<>();
        ReleaseSection design = new ReleaseSection();
        design.title = "UI and manager screens";
        design.items = new ArrayList<>(List.of("Structured changelog timeline rendering is available.", "Update branch selection supports public and beta tracks."));
        release.sections.add(design);
        ReleaseSection systems = new ReleaseSection();
        systems.title = "Systems";
        systems.items = new ArrayList<>(List.of("Update data now uses structured release sections.", "Legacy changelog strings are converted into release entries when needed."));
        release.sections.add(systems);
        releases.add(release);
        return releases;
    }

    private static ReleaseFile defaultReleaseFile(String label, String fileName) {
        ReleaseFile file = new ReleaseFile();
        file.label = label;
        file.fileName = fileName;
        return file;
    }

    private static List<Release> legacyReleases(UpdateData data) {
        List<Release> releases = new ArrayList<>();
        String current = version();
        String publicLog = firstNonBlank(data.changeLogFull, data.changeLog);
        String betaLog = firstNonBlank(data.changeLogFullBeta, data.changeLogBeta);
        if (!publicLog.isBlank()) {
            releases.add(legacyRelease("Koil Public Update", current, "public", publicLog));
        }
        if (!betaLog.isBlank()) {
            releases.add(legacyRelease("Koil Beta Update", current, activeKoilBranch(), betaLog));
        }
        return releases;
    }

    private static Release legacyRelease(String name, String version, String branch, String body) {
        Release release = new Release();
        release.name = name;
        release.version = version;
        release.branch = branch == null || branch.isBlank() ? "public" : branch;
        release.percentages = new LinkedHashMap<>();
        release.percentages.put("ui", 25);
        release.percentages.put("debug", 25);
        release.percentages.put("api", 25);
        release.percentages.put("console", 25);
        release.files = new ArrayList<>();
        release.sections = new ArrayList<>();
        ReleaseSection section = new ReleaseSection();
        section.title = "Legacy changelog";
        section.items = splitLegacyChangelog(body);
        release.sections.add(section);
        return release;
    }

    private static List<String> splitLegacyChangelog(String body) {
        List<String> items = new ArrayList<>();
        if (body == null) {
            return items;
        }
        for (String line : body.split("\\R")) {
            String trimmed = line.replaceFirst("^[\\-•|\\s]+", "").trim();
            if (!trimmed.isBlank()) {
                items.add(trimmed);
            }
        }
        if (items.isEmpty() && !body.isBlank()) {
            items.add(body.trim());
        }
        return items;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    public static String defaultDataJson() {
        return "{\n" +
                "  \"schemaVersion\": 2,\n" +
                "  \"modrinthProject\": \"koil\",\n" +
                "  \"githubJarBaseUrl\": \"https://github.com/Koil-public/koil-online-data/raw/main\",\n" +
                "  \"balanceMargin\": 10,\n" +
                "  \"updateTypes\": {\n" +
                "    \"ui\": { \"label\": \"UI Integration and UI Development\", \"color\": \"#a7003a\", \"toast\": \"koil_update_toasts.png:red\" },\n" +
                "    \"debug\": { \"label\": \"In-Game Debugging\", \"color\": \"#2da700\", \"toast\": \"koil_update_toasts.png:green\" },\n" +
                "    \"api\": { \"label\": \"J-API and Datapack Modding\", \"color\": \"#0085a4\", \"toast\": \"koil_update_toasts.png:blue\" },\n" +
                "    \"console\": { \"label\": \"Console Gameplay\", \"color\": \"#7400a4\", \"toast\": \"koil_update_toasts.png:purple\" },\n" +
                "    \"combined\": { \"label\": \"All Balanced\", \"color\": \"#8c88b5\", \"toast\": \"koil_update_toasts.png:combined\" }\n" +
                "  },\n" +
                "  \"branches\": [\n" +
                "    { \"key\": \"public\", \"label\": \"Public\", \"githubPath\": \"public\", \"betaOnly\": false, \"description\": \"Downloads the newest public Koil jar from Modrinth.\" },\n" +
                "    { \"key\": \"unfinished\", \"label\": \".unfinished\", \"githubPath\": \".unfinished\", \"betaOnly\": true, \"description\": \"Downloads unfinished beta jars from GitHub.\" },\n" +
                "    { \"key\": \"frequent\", \"label\": \".frequent\", \"githubPath\": \".frequent\", \"betaOnly\": true, \"description\": \"Downloads frequent beta jars from GitHub.\" }\n" +
                "  ],\n" +
                "  \"activeBranchHint\": \"" + activeKoilBranch() + "\",\n" +
                "  \"releases\": [\n" +
                "    {\n" +
                "      \"name\": \"Koil Development Update\",\n" +
                "      \"version\": \"" + version() + "\",\n" +
                "      \"branch\": \"" + activeKoilBranch() + "\",\n" +
                "      \"githubPath\": \"" + (activeKoilBranch().equals("public") ? "public" : "." + activeKoilBranch()) + "\",\n" +
                "      \"percentages\": { \"ui\": 45, \"debug\": 20, \"api\": 20, \"console\": 15 },\n" +
                "      \"files\": [ { \"label\": \"Default jar\", \"fileName\": \"koil-" + version() + ".jar\" } ],\n" +
                "      \"sections\": [\n" +
                "        { \"title\": \"UI and manager screens\", \"items\": [ \"Structured changelog timeline rendering is available.\", \"Update branch selection supports public and beta tracks.\" ] },\n" +
                "        { \"title\": \"Systems\", \"items\": [ \"Update data now uses structured release sections.\", \"Legacy changelog strings are converted into release entries when needed.\" ] }\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}\n";
    }

    public static class UpdateData {
        public int schemaVersion;
        public String modrinthProject;
        public String githubJarBaseUrl;
        public int balanceMargin;
        public Map<String, UpdateType> updateTypes;
        public List<Branch> branches;
        public List<Release> releases;
        public String changeLog;
        public String changeLogBeta;
        public String changeLogFull;
        public String changeLogFullBeta;
    }

    public static class UpdateType {
        public String label;
        public String color;
        public String toast;

        public UpdateType() {
        }

        public UpdateType(String label, String color, String toast) {
            this.label = label;
            this.color = color;
            this.toast = toast;
        }
    }

    public static class Branch {
        public String key;
        public String label;
        public String githubPath;
        public boolean betaOnly;
        public String description;

        public Branch() {
        }

        public Branch(String key, String label, String githubPath, boolean betaOnly, String description) {
            this.key = key;
            this.label = label;
            this.githubPath = githubPath;
            this.betaOnly = betaOnly;
            this.description = description;
        }
    }

    public static class Release {
        public String version;
        public String name;
        public String branch;
        public String githubPath;
        public String date;
        public Map<String, Integer> percentages;
        public List<ReleaseFile> files;
        public List<ReleaseSection> sections;
    }

    public static class ReleaseFile {
        public String label;
        public String fileName;
    }

    public static class ReleaseSection {
        public String title;
        public List<String> items;
    }

    public static class TimelineRenderResult {
        public int contentHeight;
        public int tooltipAccent;
        public String tooltipTitle;
        public List<String> tooltipLines;
    }

    public static class DownloadResult {
        public final boolean success;
        public final String message;
        public final Path file;

        public DownloadResult(boolean success, String message, Path file) {
            this.success = success;
            this.message = message;
            this.file = file;
        }
    }
}
