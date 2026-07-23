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

final class ContentIndexStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path contentDirectory;

    ContentIndexStore(Path gameDirectory) {
        this.contentDirectory = gameDirectory.resolve("koil").resolve("sys").resolve("content");
    }

    void writeWorldIndex(WorldContentIndex index) throws IOException {
        write("world_content_index.json", GSON.toJson(index));
    }

    void writeActiveWorld(WorldContentIndex.ActiveWorldSnapshot snapshot) throws IOException {
        write("active_world_registry.json", GSON.toJson(snapshot));
    }

    private void write(String fileName, String json) throws IOException {
        Files.createDirectories(contentDirectory);
        Path destination = contentDirectory.resolve(fileName);
        Path temporary = contentDirectory.resolve(fileName + ".tmp");
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
