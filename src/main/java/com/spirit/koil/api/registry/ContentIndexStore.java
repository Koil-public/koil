package com.spirit.koil.api.registry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

final class ContentIndexStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path contentDirectory;
    private final Path reportDirectory;

    ContentIndexStore(Path gameDirectory) {
        this.contentDirectory = gameDirectory.resolve("koil").resolve("sys").resolve("content");
        reportDirectory = gameDirectory.resolve("koil").resolve("sys").resolve("registry").resolve("reports");
    }

    void writeWorldIndex(WorldContentIndex index) throws IOException {
        String json = GSON.toJson(index);
        write("world_content_index.json", json);
        writeReport("world_scan_report.json", json);
    }

    void writeActiveWorld(WorldContentIndex.ActiveWorldSnapshot snapshot) throws IOException {
        write("active_world_registry.json", GSON.toJson(snapshot));
    }

    void writeReloadHistory(List<ContentReloadResult> history) throws IOException {
        write("content_reload_history.json", GSON.toJson(history));
    }

    void writeValidationResults(List<WorldContentIndex.ValidationMessage> validation) throws IOException {
        write("content_validation_results.json", GSON.toJson(validation));
        writeReport("validation_report.json", GSON.toJson(validation));
        List<WorldContentIndex.ValidationMessage> missing = validation.stream()
                .filter(message -> message.code().contains("missing"))
                .toList();
        writeReport("missing_content_report.json", GSON.toJson(missing));
    }

    void writeReloadReport(ContentReloadResult result) throws IOException {
        String json = GSON.toJson(result);
        writeReport("reload_report.json", json);
        writeReport("hot_reload_report.json", json);
    }

    Path contentDirectory() {
        return contentDirectory;
    }

    Path reportDirectory() {
        return reportDirectory;
    }

    private void write(String fileName, String json) throws IOException {
        writeAtomic(contentDirectory, fileName, json);
    }

    private void writeReport(String fileName, String json) throws IOException {
        writeAtomic(reportDirectory, fileName, json);
    }

    private static void writeAtomic(Path directory, String fileName, String json) throws IOException {
        Files.createDirectories(directory);
        Path destination = directory.resolve(fileName);
        Path temporary = directory.resolve(fileName + ".tmp");
        Files.writeString(
                temporary,
                json,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        try {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
