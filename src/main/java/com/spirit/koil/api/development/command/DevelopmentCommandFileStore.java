package com.spirit.koil.api.development.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Atomic, bounded filesystem operations for the local development bridge. */
final class DevelopmentCommandFileStore {
    static final long MAX_REQUEST_BYTES = 64L * 1024L;
    static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path root;
    private final Path requests;
    private final Path processing;
    private final Path results;
    private final Path scripts;

    DevelopmentCommandFileStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.requests = this.root.resolve("requests");
        this.processing = this.root.resolve("processing");
        this.results = this.root.resolve("results");
        this.scripts = this.root.resolve("scripts");
    }

    void initialize() throws Exception {
        Files.createDirectories(requests);
        Files.createDirectories(processing);
        Files.createDirectories(results);
        Files.createDirectories(scripts);
    }

    Path root() {
        return root;
    }

    Path statusPath() {
        return root.resolve("status.json");
    }

    Path resultsDirectory() {
        return results;
    }

    List<Path> requestCandidates(boolean scriptRequests) throws Exception {
        Path directory = scriptRequests ? scripts : requests;
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
            for (Path candidate : stream) {
                if (Files.isRegularFile(candidate) && !Files.isSymbolicLink(candidate)) {
                    candidates.add(candidate);
                }
            }
        }
        candidates.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return candidates;
    }

    Path claim(Path request) throws Exception {
        Path normalized = request.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null || (!parent.equals(requests) && !parent.equals(scripts))) {
            throw new IllegalArgumentException("Request is outside a bridge inbox.");
        }
        String prefix = parent.equals(scripts) ? "script-" : "command-";
        Path destination = processing.resolve(prefix + normalized.getFileName());
        moveAtomic(normalized, destination, false);
        return destination;
    }

    JsonObject readObject(Path file) throws Exception {
        if (Files.size(file) > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Request exceeds the 64 KiB file limit.");
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Request root must be a JSON object.");
            }
            return parsed.getAsJsonObject();
        }
    }

    boolean resultExists(String id) {
        return SAFE_ID.matcher(id).matches() && Files.exists(results.resolve(id + ".json"));
    }

    void writeStatus(JsonObject status) throws Exception {
        writeJsonAtomic(statusPath(), status, true);
    }

    Path writeResult(String id, JsonObject result) throws Exception {
        if (!SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Unsafe result identifier.");
        }
        Path destination = results.resolve(id + ".json");
        writeJsonAtomic(destination, result, false);
        return destination;
    }

    Path writeDuplicateResult(String id, JsonObject result) throws Exception {
        String safeId = SAFE_ID.matcher(id).matches() ? id : "invalid-request";
        Path destination = results.resolve(safeId + "-duplicate-" + System.currentTimeMillis() + ".json");
        writeJsonAtomic(destination, result, false);
        return destination;
    }

    void complete(Path processingFile) throws Exception {
        Files.deleteIfExists(processingFile);
    }

    List<Path> staleProcessingFiles() throws Exception {
        List<Path> stale = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(processing, "*.json")) {
            for (Path path : stream) {
                if (Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
                    stale.add(path);
                }
            }
        }
        stale.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return stale;
    }

    private void writeJsonAtomic(Path destination, JsonObject value, boolean replace) throws Exception {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "." + destination.getFileName(), ".tmp");
        boolean moved = false;
        try {
            Files.writeString(
                    temporary,
                    gson.toJson(value) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            moveAtomic(temporary, destination, replace);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void moveAtomic(Path source, Path destination, boolean replace) throws Exception {
        StandardCopyOption[] atomicOptions = replace
                ? new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING}
                : new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE};
        StandardCopyOption[] fallbackOptions = replace
                ? new StandardCopyOption[]{StandardCopyOption.REPLACE_EXISTING}
                : new StandardCopyOption[]{};
        try {
            Files.move(source, destination, atomicOptions);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, fallbackOptions);
        }
    }
}
