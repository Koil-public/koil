package com.spirit.koil.api.kpak.uninstall;

import com.spirit.Main;
import com.spirit.koil.api.kpak.backup.KPakBackupManager;
import com.spirit.koil.api.kpak.registry.KPakRegistry;
import com.spirit.koil.api.kpak.registry.KPakRegistryEntry;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class KPakUninstaller {

    private static final Path GAME_DIRECTORY =
        FabricLoader.getInstance()
            .getGameDir()
            .normalize();


    public static void uninstall(
        String packageId
    ) throws Exception {

        Optional<KPakRegistryEntry> optional =
            KPakRegistry.get(packageId);

        if (optional.isEmpty()) {
            throw new IllegalStateException(
                "Package not installed: " + packageId
            );
        }

        KPakRegistryEntry entry =
            optional.get();

        Main.PKG_SUBLOGGER.logI(
            "Package Uninstaller",
            "Removing " + packageId
        );

        for (String file :
            entry.installedFiles()) {

            Path target =
                GAME_DIRECTORY
                    .resolve(file)
                    .normalize();

            if (!target.startsWith(GAME_DIRECTORY)) {
                throw new SecurityException(
                    "Unsafe uninstall path: " + file
                );
            }

            Files.deleteIfExists(target);
        }

        KPakBackupManager.restore(
            packageId
        );

        KPakRegistry.remove(
            packageId
        );

        Main.PKG_SUBLOGGER.logI(
            "Package Uninstaller",
            "Uninstalled " + packageId
        );
    }
}
