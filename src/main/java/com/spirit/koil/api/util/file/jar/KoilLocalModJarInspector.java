package com.spirit.koil.api.util.file.jar;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class KoilLocalModJarInspector {
    private KoilLocalModJarInspector() {
    }

    public static KoilLocalModJarInsight inspect(File file) {
        if (file == null || !file.isFile()) {
            return KoilLocalModJarInsight.empty();
        }
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry fabric = zip.getEntry("fabric.mod.json");
            ZipEntry quilt = zip.getEntry("quilt.mod.json");
            ZipEntry metadata = fabric != null ? fabric : quilt;
            if (metadata == null) {
                return KoilLocalModJarInsight.empty();
            }
            try (InputStream stream = zip.getInputStream(metadata)) {
                JsonObject root = new Gson().fromJson(new String(stream.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
                int entrypoints = countJsonEntries(root == null ? null : root.get("entrypoints"));
                int mixins = countJsonEntries(root == null ? null : root.get("mixins"));
                int accessWideners = (root != null && root.has("accessWidener")) ? 1 : 0;
                String loaderHints = collectLoaderHints(root);
                return new KoilLocalModJarInsight(metadata.getName(), entrypoints, mixins, accessWideners, loaderHints);
            }
        } catch (Exception ignored) {
            return KoilLocalModJarInsight.empty();
        }
    }

    private static int countJsonEntries(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return 0;
        }
        if (element.isJsonArray()) {
            return element.getAsJsonArray().size();
        }
        if (element.isJsonObject()) {
            int count = 0;
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                count += countJsonEntries(entry.getValue());
            }
            return count;
        }
        return 1;
    }

    private static String collectLoaderHints(JsonObject root) {
        if (root == null) {
            return "";
        }
        List<String> hints = new ArrayList<>();
        if (root.has("jars")) {
            hints.add("nested jars");
        }
        if (root.has("languageAdapters")) {
            hints.add("language adapters");
        }
        if (root.has("custom")) {
            hints.add("custom metadata");
        }
        if (root.has("depends")) {
            hints.add("dependency rules");
        }
        return String.join(", ", hints);
    }

    public record KoilLocalModJarInsight(
            String metadataFile,
            int entrypointCount,
            int mixinCount,
            int accessWidenerCount,
            String loaderHints
    ) {
        public static KoilLocalModJarInsight empty() {
            return new KoilLocalModJarInsight("", 0, 0, 0, "");
        }
    }
}
