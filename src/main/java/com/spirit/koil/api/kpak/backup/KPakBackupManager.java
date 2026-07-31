package com.spirit.koil.api.kpak.backup;

import com.google.gson.GsonBuilder;
import com.spirit.koil.api.kpak.PackageManifest;
import com.spirit.koil.api.kpak.PackageOperation;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class KPakBackupManager {

    private static final Path BACKUP_ROOT =
        FabricLoader.getInstance()
            .getGameDir()
            .resolve(".koil/backups");

    public static KPakBackupMetadata create(
        PackageManifest manifest
    ) throws Exception {

        Path backup =
            BACKUP_ROOT
                .resolve(manifest.getPackageIdentity());

        Files.createDirectories(backup);

        List<KPakBackupMetadata.FileBackupEntry> files =
            new ArrayList<>();

        for (PackageOperation operation :
            manifest.getOperations()) {

            Path original =
                FabricLoader.getInstance()
                    .getGameDir()
                    .resolve(operation.getPath())
                    .normalize();

            if (!Files.exists(original)) {
                continue;
            }

            Path target =
                backup
                    .resolve(operation.getPath())
                    .normalize();

            Files.createDirectories(
                target.getParent()
            );

            Files.copy(
                original,
                target,
                StandardCopyOption.REPLACE_EXISTING
            );

            files.add(
                new KPakBackupMetadata.FileBackupEntry(
                    operation.getPath(),
                    operation.getSha256()
                )
            );
        }

        KPakBackupMetadata metadata =
            new KPakBackupMetadata(
                manifest.getPackageIdentity(),
                manifest.getPackageVersion(),
                Instant.now().toString(),
                files
            );

        Files.writeString(
            backup.resolve("metadata.json"),
            new GsonBuilder()
                .setPrettyPrinting()
                .create()
                .toJson(metadata)
        );

        return metadata;
    }

    public static void restore(
        String packageId
    ) throws IOException {

        Path backup =
            BACKUP_ROOT.resolve(packageId);

        if (!Files.exists(backup)) {
            return;
        }

        Files.walk(backup)
            .filter(Files::isRegularFile)
            .filter(p ->
                !p.getFileName()
                    .toString()
                    .equals("metadata.json"))
            .forEach(file -> {

                try {

                    Path relative =
                        backup.relativize(file);

                    Path target =
                        FabricLoader.getInstance()
                            .getGameDir()
                            .resolve(relative);

                    Files.createDirectories(
                        target.getParent()
                    );

                    Files.copy(
                        file,
                        target,
                        StandardCopyOption.REPLACE_EXISTING
                    );
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
    }
}
