package com.spirit.koil.api.kpak.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class KPakInstallLock {

    private static final Path LOCK =
        Path.of("./koil/.install.lock");

    public static void acquire() throws IOException {
        if (Files.exists(LOCK)) {
            throw new IllegalStateException(
                "Another Koil package installation is running"
            );
        }

        Files.createDirectories(
            LOCK.getParent()
        );

        Files.createFile(LOCK);
    }

    public static void release() {
        try {
            Files.deleteIfExists(LOCK);
        } catch (IOException ignored) {
        }
    }
}
