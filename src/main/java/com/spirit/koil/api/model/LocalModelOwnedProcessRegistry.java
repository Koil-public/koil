package com.spirit.koil.api.model;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Tracks only model sidecars started by this JVM so a normal development-client
 * shutdown cannot leave an orphan runtime behind.
 */
public final class LocalModelOwnedProcessRegistry {
    private static final Set<Process> OWNED = ConcurrentHashMap.newKeySet();
    private static volatile boolean jvmShutdownInProgress;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
                LocalModelOwnedProcessRegistry::stopAll,
                "koil-model-process-shutdown"
        ));
    }

    private LocalModelOwnedProcessRegistry() {
    }

    public static void register(Process process) {
        if (process != null) {
            OWNED.add(process);
        }
    }

    public static void unregister(Process process) {
        if (process != null) {
            OWNED.remove(process);
        }
    }

    static int ownedProcessCount() {
        OWNED.removeIf(process -> !process.isAlive());
        return OWNED.size();
    }

    public static boolean isJvmShutdownInProgress() {
        return jvmShutdownInProgress;
    }

    private static void stopAll() {
        jvmShutdownInProgress = true;
        Process[] processes = OWNED.toArray(Process[]::new);
        for (Process process : processes) {
            if (process.isAlive()) {
                process.destroy();
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        for (Process process : processes) {
            if (!process.isAlive()) {
                OWNED.remove(process);
                continue;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining > 0L) {
                try {
                    process.waitFor(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            OWNED.remove(process);
        }
    }
}
