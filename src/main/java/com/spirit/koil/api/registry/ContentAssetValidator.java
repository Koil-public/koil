package com.spirit.koil.api.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Validates definition-owned client asset references without rewriting sources. */
public final class ContentAssetValidator {
    private static final int MAX_ASSET_FILES = 50_000;
    private static final int MAX_JSON_BYTES = 1_048_576;
    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?:[a-z0-9_.-]+:)?[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*"
    );

    private ContentAssetValidator() {
    }

    public static List<WorldContentIndex.ValidationMessage> validate(
            Path packPath,
            boolean packed,
            List<WorldContentIndex.DefinitionEntry> definitions
    ) {
        if (definitions == null || definitions.isEmpty()) {
            return List.of();
        }
        AssetIndex assets = packed ? indexZip(packPath) : indexFolder(packPath);
        List<WorldContentIndex.ValidationMessage> messages = new ArrayList<>(assets.validation());
        if (!assets.hasAssetRoot()) {
            messages.add(WorldContentIndex.ValidationMessage.warning(
                    "missing_assets_root",
                    "Content datapack has definitions but no assets/ root.",
                    packPath.toString(),
                    "Add assets/<namespace>/models, textures, blockstates, and lang files; Koil will use an inactive fallback until they exist."
            ));
        }
        for (WorldContentIndex.DefinitionEntry definition : definitions) {
            validateDefinition(definition, assets, messages);
        }
        return List.copyOf(messages);
    }

    private static void validateDefinition(
            WorldContentIndex.DefinitionEntry definition,
            AssetIndex index,
            List<WorldContentIndex.ValidationMessage> messages
    ) {
        JsonElement assetsElement = definition.definition().get("assets");
        JsonObject assets = assetsElement != null && assetsElement.isJsonObject()
                ? assetsElement.getAsJsonObject()
                : new JsonObject();
        validateReference(definition, index, assets, "model", "models", ".json", messages);
        validateReference(definition, index, assets, "block_model", "models", ".json", messages);
        validateReference(definition, index, assets, "item_model", "models", ".json", messages);
        validateReference(definition, index, assets, "texture", "textures", ".png", messages);
        validateReference(definition, index, assets, "blockstate", "blockstates", ".json", messages);

        JsonElement displayElement = definition.definition().get("display");
        JsonObject display = displayElement != null && displayElement.isJsonObject()
                ? displayElement.getAsJsonObject()
                : new JsonObject();
        JsonElement langKey = display.get("lang_key");
        if (langKey != null && langKey.isJsonPrimitive() && langKey.getAsJsonPrimitive().isString()) {
            String key = langKey.getAsString();
            if (!key.isBlank() && !index.languageKeys().contains(key)) {
                messages.add(WorldContentIndex.ValidationMessage.warning(
                        "missing_lang_key",
                        "No datapack language file defines \"" + key + "\" for " + definition.id() + ".",
                        definition.sourcePath(),
                        "Add the key under assets/" + definition.namespace() + "/lang/<language>.json or keep display.name as the fallback."
                ));
            }
        }
    }

    private static void validateReference(
            WorldContentIndex.DefinitionEntry definition,
            AssetIndex index,
            JsonObject assets,
            String field,
            String directory,
            String extension,
            List<WorldContentIndex.ValidationMessage> messages
    ) {
        JsonElement value = assets.get(field);
        if (value == null) {
            return;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            messages.add(WorldContentIndex.ValidationMessage.warning(
                    "invalid_asset_reference",
                    "Asset field \"" + field + "\" is not a string for " + definition.id() + ".",
                    definition.sourcePath(),
                    "Use a namespaced path such as \"" + definition.namespace() + ":item/" + definition.localId() + "\"."
            ));
            return;
        }
        String reference = value.getAsString().trim();
        if (!IDENTIFIER.matcher(reference).matches()) {
            messages.add(WorldContentIndex.ValidationMessage.warning(
                    "invalid_asset_reference",
                    "Asset field \"" + field + "\" has invalid identifier \"" + reference + "\".",
                    definition.sourcePath(),
                    "Use lowercase namespace:path syntax; custom fields are preserved."
            ));
            return;
        }
        int separator = reference.indexOf(':');
        String namespace = separator >= 0 ? reference.substring(0, separator) : definition.namespace();
        String path = separator >= 0 ? reference.substring(separator + 1) : reference;
        String expected = "assets/" + namespace + "/" + directory + "/" + path + extension;
        if (!index.paths().contains(expected)) {
            messages.add(WorldContentIndex.ValidationMessage.warning(
                    "missing_" + field,
                    "Missing " + field.replace('_', ' ') + " asset for " + definition.id() + ": " + expected,
                    definition.sourcePath(),
                    "Generate or restore " + expected + " in the owning datapack; the source datapack remains authoritative."
            ));
        }
    }

    private static AssetIndex indexFolder(Path packPath) {
        Path assetsRoot = packPath.resolve("assets");
        if (!Files.isDirectory(assetsRoot)) {
            return new AssetIndex(false, Set.of(), Set.of(), List.of());
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        LinkedHashSet<String> languageKeys = new LinkedHashSet<>();
        List<WorldContentIndex.ValidationMessage> validation = new ArrayList<>();
        try (var files = Files.walk(assetsRoot, 24)) {
            for (Path file : files.filter(Files::isRegularFile).limit(MAX_ASSET_FILES).toList()) {
                String relative = packPath.relativize(file).toString().replace('\\', '/');
                paths.add(relative);
                if (isLanguageFile(relative)) {
                    readLanguageKeys(() -> readBounded(file), relative, languageKeys, validation);
                }
            }
        } catch (IOException exception) {
            validation.add(assetScanWarning(packPath, exception));
        }
        return new AssetIndex(true, paths, languageKeys, validation);
    }

    private static AssetIndex indexZip(Path packPath) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        LinkedHashSet<String> languageKeys = new LinkedHashSet<>();
        List<WorldContentIndex.ValidationMessage> validation = new ArrayList<>();
        boolean assetRoot = false;
        try (ZipFile zip = new ZipFile(packPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int visited = 0;
            while (entries.hasMoreElements() && visited++ < MAX_ASSET_FILES) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !name.startsWith("assets/") || unsafeZipPath(name)) {
                    continue;
                }
                assetRoot = true;
                paths.add(name);
                if (isLanguageFile(name)) {
                    readLanguageKeys(() -> readBounded(zip, entry), packPath + "!/" + name, languageKeys, validation);
                }
            }
        } catch (IOException exception) {
            validation.add(assetScanWarning(packPath, exception));
        }
        return new AssetIndex(assetRoot, paths, languageKeys, validation);
    }

    private static void readLanguageKeys(
            Supplier<byte[]> source,
            String sourcePath,
            Set<String> keys,
            List<WorldContentIndex.ValidationMessage> validation
    ) {
        try {
            JsonElement parsed = JsonParser.parseString(new String(source.get(), StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) {
                keys.addAll(parsed.getAsJsonObject().keySet());
            }
        } catch (RuntimeException exception) {
            validation.add(WorldContentIndex.ValidationMessage.warning(
                    "invalid_lang_asset",
                    "Could not parse language asset: " + exception.getMessage(),
                    sourcePath,
                    "Repair the JSON language file; other Content assets can still load."
            ));
        }
    }

    private static boolean isLanguageFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.startsWith("assets/") && lower.contains("/lang/") && lower.endsWith(".json");
    }

    private static byte[] readBounded(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(MAX_JSON_BYTES + 1);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] readBounded(ZipFile zip, ZipEntry entry) {
        try (InputStream input = zip.getInputStream(entry)) {
            return input.readNBytes(MAX_JSON_BYTES + 1);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean unsafeZipPath(String path) {
        return path.startsWith("/") || path.contains("../") || path.contains("/..");
    }

    private static WorldContentIndex.ValidationMessage assetScanWarning(Path packPath, Exception exception) {
        return WorldContentIndex.ValidationMessage.warning(
                "asset_scan_failed",
                "Could not fully inspect Content assets: " + exception.getMessage(),
                packPath.toString(),
                "Check the datapack file and permissions; definitions remain indexed with fallback presentation."
        );
    }

    private record AssetIndex(
            boolean hasAssetRoot,
            Set<String> paths,
            Set<String> languageKeys,
            List<WorldContentIndex.ValidationMessage> validation
    ) {
        private AssetIndex {
            paths = Set.copyOf(paths);
            languageKeys = Set.copyOf(languageKeys);
            validation = List.copyOf(validation);
        }
    }
}
