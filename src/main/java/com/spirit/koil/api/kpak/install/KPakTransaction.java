package com.spirit.koil.api.kpak.install;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public class KPakTransaction {

    private static final Path TRANSACTION_DIRECTORY =
        FabricLoader.getInstance()
            .getGameDir()
            .resolve(".koil/transactions");

    private static final Gson GSON =
        new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private final String packageId;
    private final String version;
    private final String created;
    private State state;

    public KPakTransaction(
        String packageId,
        String version
    ) {
        this.packageId = packageId;
        this.version = version;
        this.created = Instant.now().toString();
        this.state = State.INSTALLING;
    }

    public static KPakTransaction load(
        String packageId
    ) throws IOException {

        Path file =
            TRANSACTION_DIRECTORY.resolve(
                packageId + ".json"
            );

        if (!Files.exists(file)) {
            return null;
        }

        return GSON.fromJson(
            Files.readString(file),
            KPakTransaction.class
        );
    }

    public static void recover() throws IOException {

        if (!Files.exists(TRANSACTION_DIRECTORY)) {
            return;
        }

        try (DirectoryStream<Path> stream =
                 Files.newDirectoryStream(
                     TRANSACTION_DIRECTORY,
                     "*.json"
                 )) {

            for (Path file : stream) {

                KPakTransaction transaction =
                    GSON.fromJson(
                        Files.readString(file),
                        KPakTransaction.class
                    );

                if (transaction.state != State.COMPLETED) {

                    // Recovery hook.
                    // Installer/uninstaller decides rollback action.

                    transaction.state =
                        State.FAILED;

                    Files.writeString(
                        file,
                        GSON.toJson(transaction)
                    );
                }
            }
        }
    }

    public String getPackageId() {
        return packageId;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public void save() throws IOException {

        Files.createDirectories(
            TRANSACTION_DIRECTORY
        );

        Files.writeString(
            TRANSACTION_DIRECTORY.resolve(packageId + ".json"),
            GSON.toJson(this)
        );
    }

    public void delete() throws IOException {

        Files.deleteIfExists(
            TRANSACTION_DIRECTORY.resolve(packageId + ".json")
        );
    }


    public enum State {
        INSTALLING,
        BACKING_UP,
        WRITING_FILES,
        VERIFYING,
        COMPLETED,
        FAILED
    }
}
