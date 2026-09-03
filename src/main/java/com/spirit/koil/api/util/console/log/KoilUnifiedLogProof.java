package com.spirit.koil.api.util.console.log;

import com.spirit.koil.api.console.ConsoleLevel;

import java.nio.file.Files;
import java.nio.file.Path;

/** Proves subsystem groups share the one Koil physical log. */
public final class KoilUnifiedLogProof {
    private KoilUnifiedLogProof() {
    }

    public static void main(String[] args) throws Exception {
        KoilLog.write(KoilLog.AUTOMATION_THREAD, ConsoleLevel.INFO, "runtime", "model event");
        KoilLog.write(KoilLog.PACKAGING_THREAD, ConsoleLevel.WARN, "package", "package event");
        KoilLog.write(KoilLog.BRIDGE_THREAD, ConsoleLevel.ERROR, "bridge", "bridge event");
        Thread.currentThread().interrupt();
        KoilLog.info(KoilLog.AUTOMATION_THREAD, "cancel", "interrupted producer event");
        require(Thread.currentThread().isInterrupted(), "logging cleared the producer cancellation signal");
        Thread.interrupted();
        Thread.sleep(350L);
        Path main = Path.of(KoilLog.MAIN_PATH);
        require(Files.isRegularFile(main), "main Koil log was not created");
        String text = Files.readString(main);
        require(text.contains("[Automation Thread/Info]")
                        && text.contains("[Packaging Thread/Warn]")
                        && text.contains("[Bridge Thread/Error]")
                        && text.contains("interrupted producer event"),
                "thread-group records did not share the main log");
        require(!Files.exists(Path.of("koil/logs/package/latest.log"))
                        && !Files.exists(Path.of("koil/logs/automate/latest.log"))
                        && !Files.exists(Path.of("koil/sys/model/logs/local-model-runtime.log"))
                        && !Files.exists(Path.of("koil/logs/development-command-bridge/latest.log")),
                "a retired subsystem log was recreated");
        SubFileLogger.closeAll();
        System.out.println("Koil unified-log proof passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
