package com.spirit.koil.api.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class LocalModelRuntimeLog {
    public static final Path LOG_PATH = Path.of("koil/sys/model/logs/local-model-runtime.log");

    private LocalModelRuntimeLog() {
    }

    public static synchronized void write(String event, String detail) {
        try {
            Files.createDirectories(LOG_PATH.toAbsolutePath().normalize().getParent());
            String safeEvent = event == null ? "event" : event.replaceAll("[\\r\\n\\p{Cntrl}]", "_");
            String safeDetail = detail == null ? "" : detail.replace('\r', ' ').replace('\n', ' ');
            Files.writeString(
                    LOG_PATH,
                    Instant.now() + " [" + safeEvent + "] " + safeDetail + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
        }
    }
}
