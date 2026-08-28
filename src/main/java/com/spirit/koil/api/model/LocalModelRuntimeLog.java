package com.spirit.koil.api.model;

import java.nio.file.Path;
import com.spirit.koil.api.util.console.log.KoilLog;

public final class LocalModelRuntimeLog {
    public static final Path LOG_PATH = Path.of(KoilLog.MAIN_PATH);

    private LocalModelRuntimeLog() {
    }

    public static synchronized void write(String event, String detail) {
        KoilLog.info(KoilLog.AUTOMATION_THREAD, event, detail);
    }
}
