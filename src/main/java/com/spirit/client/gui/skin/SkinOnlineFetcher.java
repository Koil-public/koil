package com.spirit.client.gui.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.texture.NativeImage;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.spirit.Main.SUBLOGGER;

public final class SkinOnlineFetcher {
    private static final Pattern PNG_URL_PATTERN = Pattern.compile("https://[^\\\"'<>\\s]+?\\.png(?:\\?[^\\\"'<>\\s]*)?");

    private SkinOnlineFetcher() {
    }

    public static int fetchPlayerSkins(String username, SkinLibrary library) {
        int added = 0;
        try {
            SkinEntry current = fetchMojangCurrent(username, library);
            if (current != null) {
                added++;
            }
        } catch (Exception e) {
            SUBLOGGER.logE("Skin-Online thread", "Mojang skin fetch failed: " + e.getMessage());
        }
        try {
            added += fetchNameMcHistory(username, library);
        } catch (Exception e) {
            SUBLOGGER.logE("Skin-Online thread", "NameMC skin fetch failed: " + e.getMessage());
        }
        return added;
    }

    public static SkinEntry fetchMojangCurrent(String username, SkinLibrary library) throws Exception {
        String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
        JsonObject profile = JsonParser.parseString(readString("https://api.mojang.com/users/profiles/minecraft/" + encoded)).getAsJsonObject();
        if (!profile.has("id")) {
            return null;
        }
        String uuid = profile.get("id").getAsString();
        JsonObject session = JsonParser.parseString(readString("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false")).getAsJsonObject();
        JsonArray properties = session.getAsJsonArray("properties");
        if (properties == null || properties.isEmpty()) {
            return null;
        }
        String value = properties.get(0).getAsJsonObject().get("value").getAsString();
        String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        JsonObject textures = JsonParser.parseString(decoded).getAsJsonObject().getAsJsonObject("textures");
        if (textures == null || !textures.has("SKIN")) {
            return null;
        }
        JsonObject skin = textures.getAsJsonObject("SKIN");
        String url = skin.get("url").getAsString();
        if (library.containsTextureUrl(url)) {
            return null;
        }
        boolean slim = false;
        if (skin.has("metadata")) {
            JsonObject metadata = skin.getAsJsonObject("metadata");
            slim = metadata.has("model") && "slim".equalsIgnoreCase(metadata.get("model").getAsString());
        }
        Path file = downloadSkin(url, "mojang_current");
        return library.addDownloaded(file, username + " current", slim, "Mojang", "Current profile skin", url);
    }

    public static int fetchNameMcHistory(String username, SkinLibrary library) throws Exception {
        String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8).replace("+", "%20");
        List<String> pages = new ArrayList<>();
        pages.add("https://namemc.com/profile/" + encoded + ".1");
        String html = readString(pages.get(0));
        Set<String> urls = new LinkedHashSet<>();
        collectPngUrls(html, urls);
        Matcher skinLinkMatcher = Pattern.compile("href=\\\"(/skin/[a-zA-Z0-9_.-]+)\\\"").matcher(html);
        while (skinLinkMatcher.find()) {
            pages.add("https://namemc.com" + skinLinkMatcher.group(1));
        }
        for (int i = 1; i < pages.size() && i < 12; i++) {
            try {
                collectPngUrls(readString(pages.get(i)), urls);
            } catch (Exception ignored) {
            }
        }
        int added = 0;
        int index = 1;
        for (String url : urls) {
            if (library.containsTextureUrl(url)) {
                continue;
            }
            try {
                Path downloaded = downloadSkin(url, "namemc_" + index);
                SkinEntry entry = library.addDownloaded(downloaded, username + " NameMC " + index, false, "NameMC", "Best-effort public profile/history skin", url);
                if (entry != null) {
                    added++;
                    index++;
                }
            } catch (Exception ignored) {
            }
        }
        return added;
    }

    private static void collectPngUrls(String html, Set<String> urls) {
        Matcher matcher = PNG_URL_PATTERN.matcher(html);
        while (matcher.find()) {
            String url = matcher.group();
            if (url.contains("namemc") || url.contains("minecraft.net") || url.contains("textures.minecraft.net")) {
                urls.add(url.replace("&amp;", "&"));
            }
        }
    }

    private static Path downloadSkin(String url, String name) throws Exception {
        Path temp = Files.createTempFile("koil_skin_" + name + "_", ".png");
        HttpURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream()) {
            Files.copy(input, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        NativeImage image = SkinTextureTools.readSkin(temp);
        try {
            SkinTextureTools.writeSkin(image, temp);
        } finally {
            image.close();
        }
        return temp;
    }

    private static String readString(String url) throws Exception {
        HttpURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("User-Agent", "SkinLibrary/1.0");
        connection.setRequestProperty("Accept", "application/json,text/html,image/png,*/*");
        return connection;
    }
}
