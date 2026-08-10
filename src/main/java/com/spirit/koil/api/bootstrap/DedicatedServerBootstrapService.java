package com.spirit.koil.api.bootstrap;

import com.google.gson.JsonPrimitive;
import com.spirit.koil.api.util.file.json.JSONFileEditor;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** Console-consented, failure-tolerant bootstrap lifecycle for dedicated servers. */
public final class DedicatedServerBootstrapService {
    public static final String TERMS_VERSION = "2026-08-07";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Koil-Dedicated-Bootstrap");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicReference<Snapshot> STATE = new AtomicReference<>(
            new Snapshot(State.UNINITIALIZED, "not initialized", "", 0L, 0L)
    );
    private static volatile Runnable bootstrap = () -> { };
    private static volatile BiConsumer<String, Throwable> logger = (message, failure) -> { };
    private static volatile CompletableFuture<Void> active;

    private DedicatedServerBootstrapService() {
    }

    public static synchronized void initialize(Runnable bootstrapAction, BiConsumer<String, Throwable> log) {
        bootstrap = Objects.requireNonNull(bootstrapAction, "bootstrapAction");
        logger = log == null ? (message, failure) -> { } : log;
        if (termsAccepted()) {
            STATE.set(new Snapshot(State.READY_TO_START, "terms accepted; bootstrap scheduled", "", 0L, 0L));
            start(false);
        } else {
            STATE.set(new Snapshot(
                    State.AWAITING_CONSENT,
                    "external downloads and package rewrites are deferred",
                    "",
                    0L,
                    0L
            ));
            String instruction = "Koil dedicated-server terms are pending. From the physical server console run: koil terms status, then koil terms accept";
            System.out.println(instruction);
            logger.accept(instruction, null);
        }
    }

    public static synchronized boolean acceptFromPhysicalConsole() {
        if (termsAccepted()) {
            if (STATE.get().state() == State.AWAITING_CONSENT) start(false);
            return false;
        }
        try {
            JSONFileEditor.updateValueInJson("./koil/sys/config.json", "termsVersion", new JsonPrimitive(TERMS_VERSION));
            JSONFileEditor.updateValueInJson("./koil/sys/config.json", "termsAcceptedAt", new JsonPrimitive(Instant.now().toString()));
            JSONFileEditor.updateValueInJson("./koil/sys/config.json", "firstLaunch", new JsonPrimitive(false));
        } catch (IOException exception) {
            STATE.set(new Snapshot(State.DEGRADED, "failed to persist terms acceptance", exception.getMessage(), 0L, System.currentTimeMillis()));
            logger.accept("Koil could not persist dedicated-server terms acceptance", exception);
            return false;
        }
        STATE.set(new Snapshot(State.READY_TO_START, "terms accepted; bootstrap scheduled", "", 0L, 0L));
        start(false);
        return true;
    }

    public static synchronized boolean retryFromPhysicalConsole() {
        if (!termsAccepted() || active != null && !active.isDone()) return false;
        start(true);
        return true;
    }

    private static void start(boolean retry) {
        long started = System.currentTimeMillis();
        STATE.set(new Snapshot(State.RUNNING, retry ? "retrying server bootstrap" : "server bootstrap running", "", started, 0L));
        active = CompletableFuture.runAsync(bootstrap, EXECUTOR).whenComplete((unused, failure) -> {
            long completed = System.currentTimeMillis();
            if (failure == null) {
                STATE.set(new Snapshot(State.READY, "server bootstrap complete", "", started, completed));
                logger.accept("Koil dedicated-server bootstrap completed.", null);
            } else {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                STATE.set(new Snapshot(State.DEGRADED, "server remains online in degraded mode", clean(cause.getMessage()), started, completed));
                logger.accept("Koil dedicated-server bootstrap failed; the server remains online in degraded mode.", cause);
            }
        });
    }

    public static Snapshot snapshot() {
        return STATE.get();
    }

    public static boolean termsAccepted() {
        try {
            var firstLaunch = JSONFileEditor.getValueFromJson("./koil/sys/config.json", "firstLaunch");
            var version = JSONFileEditor.getValueFromJson("./koil/sys/config.json", "termsVersion");
            return firstLaunch != null && firstLaunch.isJsonPrimitive() && !firstLaunch.getAsBoolean()
                    && version != null && version.isJsonPrimitive() && TERMS_VERSION.equals(version.getAsString());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        String clean = value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").strip();
        return clean.length() <= 300 ? clean : clean.substring(0, 299) + "…";
    }

    public enum State {
        UNINITIALIZED,
        AWAITING_CONSENT,
        READY_TO_START,
        RUNNING,
        READY,
        DEGRADED
    }

    public record Snapshot(State state, String detail, String failure, long startedAtMillis, long completedAtMillis) {
        public Snapshot {
            state = state == null ? State.UNINITIALIZED : state;
            detail = clean(detail);
            failure = clean(failure);
        }

        public String statusLine() {
            return state.name().toLowerCase(java.util.Locale.ROOT) + " — " + detail
                    + (failure.isBlank() ? "" : " | " + failure);
        }
    }
}
