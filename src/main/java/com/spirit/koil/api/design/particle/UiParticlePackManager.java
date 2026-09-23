package com.spirit.koil.api.design.particle;

import com.spirit.Main;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads Koil theme/user particle packs. The capability-first baseline has no authored built-in effect catalog.
 *
 * <p>The capability baseline deliberately ships no authored built-in effect catalog.
 * Registered game particles, blocks and items are mirrored dynamically by the
 * code-backed registry bridges. Theme/user JSON packs remain supported through
 * the same generic loader and registry path for future authored effects.</p>
 */
public final class UiParticlePackManager {
    // Capability-first baseline: the old 600 authored effect catalogs are intentionally disabled.
    // Registered Minecraft/Fabric particles, blocks and items are mirrored dynamically by code-backed bridges.
    private static final String[] BUILTIN_RESOURCES = { };

    private static boolean builtinsLoaded;
    private static boolean loadedOnce;
    private static Path loadedDirectory;

    private static final List<String> builtinDeclaredIds = new ArrayList<>();
    private static final List<String> builtinIds = new ArrayList<>();
    private static final List<String> loadedIds = new ArrayList<>();
    private static final List<String> errors = new ArrayList<>();
    private static final Map<String, String> failedRegistrations = new LinkedHashMap<>();
    private static final Map<String, UiParticleEffect> replacedDefinitions = new LinkedHashMap<>();

    private UiParticlePackManager() { }

    public static synchronized void loadBuiltinCatalogsOnce() {
        if (builtinsLoaded) return;

        builtinDeclaredIds.clear();
        builtinIds.clear();
        failedRegistrations.clear();
        errors.removeIf(message -> message.startsWith("builtin:"));

        boolean allResourcesPresent = true;
        for (String resource : BUILTIN_RESOURCES) {
            try (InputStream stream = UiParticlePackManager.class.getClassLoader().getResourceAsStream(resource)) {
                if (stream == null) {
                    allResourcesPresent = false;
                    errors.add("builtin: missing catalog " + resource);
                    continue;
                }

                String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                for (String id : UiParticleJsonLoader.effectIds(json)) {
                    if (!builtinDeclaredIds.contains(id)) builtinDeclaredIds.add(id);
                }

                UiParticleJsonLoader.RegistrationReport report =
                        UiParticleJsonLoader.registerJsonAllBestEffort(json);
                for (String id : report.registeredIds()) {
                    if (!builtinIds.contains(id)) builtinIds.add(id);
                    failedRegistrations.remove(id);
                }
                for (UiParticleJsonLoader.RegistrationFailure failure : report.failures()) {
                    String key = resource + "::" + failure.id();
                    failedRegistrations.put(key, failure.message());
                    errors.add("builtin: " + key + ": " + failure.message());
                }
            } catch (IOException | RuntimeException exception) {
                allResourcesPresent = false;
                errors.add("builtin: " + resource + ": " + describe(exception));
            }
        }

        // Only latch when all classpath catalogs were actually found.
        // If resources were unavailable during an early bootstrap call, later
        // command/engine initialization gets another chance to load them.
        builtinsLoaded = allResourcesPresent;
    }

    /** Resolves the active theme's external particle directory at call time. */
    public static synchronized Path currentDirectory() {
        String resolvedDesignDirectory = Main.uiDesignDirectory;
        if (resolvedDesignDirectory != null && !resolvedDesignDirectory.isBlank()) {
            return Paths.get(resolvedDesignDirectory).resolve("particles").normalize();
        }
        String theme = Main.uiTheme;
        if (theme == null || theme.isBlank()) theme = "default";
        return Paths.get("./koil/sys/design").resolve(theme.trim()).resolve("particles").normalize();
    }

    public static synchronized void loadDefaultDirectoryOnce() {
        loadBuiltinCatalogsOnce();
        Path current = currentDirectory();
        if (loadedOnce && samePath(loadedDirectory, current)) return;
        reloadDefaultDirectory();
    }

    public static synchronized List<String> reloadDefaultDirectory() {
        return reloadDirectory(currentDirectory());
    }

    /** Reloads themed/user overrides while preserving JSON built-ins underneath. */
    public static synchronized List<String> reloadDirectory(Path directory) {
        loadBuiltinCatalogsOnce();
        restorePreviousDefinitions();
        loadedIds.clear();

        // Keep built-in load diagnostics but discard stale theme scan errors/failures.
        errors.removeIf(message -> message.startsWith("theme:"));
        failedRegistrations.keySet().removeIf(key -> key.startsWith("theme::"));

        Path resolved = directory == null ? currentDirectory() : directory.normalize();
        loadedDirectory = resolved;
        loadedOnce = true;

        try {
            Files.createDirectories(resolved);
        } catch (IOException | RuntimeException exception) {
            errors.add("theme: directory: " + describe(exception));
            return loadedIds();
        }

        try (var stream = Files.list(resolved)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .sorted()
                    .toList();
            for (Path file : files) loadFile(file);
        } catch (IOException | RuntimeException exception) {
            errors.add("theme: scan " + resolved + ": " + describe(exception));
        }
        return loadedIds();
    }

    public static synchronized void unloadCurrentPacks() {
        restorePreviousDefinitions();
        loadedIds.clear();
        loadedDirectory = null;
        loadedOnce = false;
    }

    public static synchronized List<String> builtinDeclaredIds() {
        return Collections.unmodifiableList(new ArrayList<>(builtinDeclaredIds));
    }

    public static synchronized List<String> builtinIds() {
        return Collections.unmodifiableList(new ArrayList<>(builtinIds));
    }

    public static synchronized List<String> missingBuiltinIds() {
        List<String> missing = new ArrayList<>(builtinDeclaredIds);
        missing.removeAll(builtinIds);
        return Collections.unmodifiableList(missing);
    }

    public static synchronized Map<String, String> failedRegistrations() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(failedRegistrations));
    }

    public static synchronized String diagnosticSummary() {
        return "builtins declared=" + builtinDeclaredIds.size()
                + ", registered=" + builtinIds.size()
                + ", failed=" + failedRegistrations.size()
                + ", theme registered=" + loadedIds.size()
                + ", registry total=" + UiParticleRegistry.ids().size()
                + ", directory=" + String.valueOf(loadedDirectory);
    }

    public static synchronized List<String> loadedIds() {
        return Collections.unmodifiableList(new ArrayList<>(loadedIds));
    }

    public static synchronized List<String> errors() {
        return Collections.unmodifiableList(new ArrayList<>(errors));
    }

    public static synchronized Path loadedDirectory() { return loadedDirectory; }

    public static synchronized boolean isLoadedForCurrentTheme() {
        return loadedOnce && samePath(loadedDirectory, currentDirectory());
    }

    private static void loadFile(Path file) {
        try {
            String json = Files.readString(file);
            List<String> ids = UiParticleJsonLoader.effectIds(json);
            Map<String, UiParticleEffect> captured = new LinkedHashMap<>();
            for (String id : ids) {
                if (!replacedDefinitions.containsKey(id)) {
                    UiParticleEffect previous = UiParticleRegistry.get(id);
                    replacedDefinitions.put(id, previous);
                    captured.put(id, previous);
                }
            }

            UiParticleJsonLoader.RegistrationReport report =
                    UiParticleJsonLoader.registerJsonAllBestEffort(json);

            for (String registered : report.registeredIds()) {
                if (!loadedIds.contains(registered)) loadedIds.add(registered);
                failedRegistrations.remove("theme::" + registered);
            }

            for (UiParticleJsonLoader.RegistrationFailure failure : report.failures()) {
                String id = failure.id();
                UiParticleEffect previous = captured.get(id);
                if (previous != null) UiParticleRegistry.register(previous);
                else if (!id.equals("<unnamed>") && !id.equals("<document>")) UiParticleRegistry.unregister(id);
                replacedDefinitions.remove(id);
                failedRegistrations.put("theme::" + id, failure.message());
                errors.add("theme: " + file.getFileName() + "::" + id + ": " + failure.message());
            }
        } catch (IOException | RuntimeException exception) {
            errors.add("theme: " + file.getFileName() + ": " + describe(exception));
        }
    }

    private static void restorePreviousDefinitions() {
        for (Map.Entry<String, UiParticleEffect> entry : replacedDefinitions.entrySet()) {
            if (entry.getValue() != null) UiParticleRegistry.register(entry.getValue());
            else UiParticleRegistry.unregister(entry.getKey());
        }
        replacedDefinitions.clear();
    }

    private static boolean samePath(Path a, Path b) {
        if (a == null || b == null) return false;
        return Objects.equals(a.toAbsolutePath().normalize(), b.toAbsolutePath().normalize());
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
