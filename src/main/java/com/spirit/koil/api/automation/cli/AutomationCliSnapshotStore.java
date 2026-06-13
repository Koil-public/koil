package com.spirit.koil.api.automation.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AutomationCliSnapshotStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SNAPSHOT_PATH = Path.of("koil/sys/cache/automation/cli-snapshot.json");

    private AutomationCliSnapshotStore() {
    }

    public static void save(AutomationCliSnapshot snapshot) {
        try {
            Files.createDirectories(SNAPSHOT_PATH.getParent());
            Files.writeString(SNAPSHOT_PATH, GSON.toJson(snapshot), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static AutomationCliSnapshot load() {
        if (!Files.exists(SNAPSHOT_PATH)) {
            return null;
        }
        try {
            return GSON.fromJson(Files.readString(SNAPSHOT_PATH, StandardCharsets.UTF_8), AutomationCliSnapshot.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static Path path() {
        return SNAPSHOT_PATH;
    }
}
