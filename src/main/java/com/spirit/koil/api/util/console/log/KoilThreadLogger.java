package com.spirit.koil.api.util.console.log;

/**
 * A thread-group view over Koil's one physical log writer.
 *
 * <p>The legacy thread argument remains accepted so existing callers can migrate
 * without creating another file or writer. The bound group is authoritative.</p>
 */
public final class KoilThreadLogger {
    private final SubFileLogger delegate;
    private final String threadGroup;

    public KoilThreadLogger(SubFileLogger delegate, String threadGroup) {
        if (delegate == null) throw new IllegalArgumentException("delegate logger is required");
        this.delegate = delegate;
        this.threadGroup = threadGroup == null || threadGroup.isBlank() ? "Koil Thread" : threadGroup.strip();
    }

    public void log(String ignoredThread, String message) { delegate.log(threadGroup, message); }
    public void logI(String ignoredThread, String message) { delegate.logI(threadGroup, message); }
    public void logW(String ignoredThread, String message) { delegate.logW(threadGroup, message); }
    public void logE(String ignoredThread, String message) { delegate.logE(threadGroup, message); }
    public void logF(String ignoredThread, String message) { delegate.logF(threadGroup, message); }
    public void logD(String ignoredThread, String message) { delegate.logD(threadGroup, message); }
    public void logU(String ignoredThread, String message) { delegate.logU(threadGroup, message); }
    public void logO(String ignoredThread, String message) { delegate.logO(threadGroup, message); }
}
