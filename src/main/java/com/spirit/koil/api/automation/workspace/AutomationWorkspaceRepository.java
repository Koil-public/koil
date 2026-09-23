package com.spirit.koil.api.automation.workspace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.spirit.koil.api.automation.cli.AutomationCliRow;
import com.spirit.koil.api.automation.cli.AutomationCliSnapshot;
import com.spirit.koil.api.automation.cli.AutomationCliSnapshotStore;
import com.spirit.koil.api.model.chat.ModelActivityPresentation;
import com.spirit.koil.api.util.console.log.KoilLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded history for every observable model/executor trace.
 *
 * <p>The legacy CLI snapshot remains a compatibility input. Screen state is
 * deliberately absent so this repository can be reused by chat navigation,
 * exports, and future external screen transports.</p>
 */
public final class AutomationWorkspaceRepository {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path HISTORY_ROOT = Path.of("koil/sys/cache/automation/workspace-traces");
    private static final Path EXPORT_ROOT = Path.of("koil/exports/automation-workspace");
    private static final int MAXIMUM_TRACES = 128;
    private static final long EXECUTOR_PERSIST_INTERVAL_NANOS = 250_000_000L;
    private static final Map<String, AutomationWorkspaceTrace> TRACES = new LinkedHashMap<>();
    private static final AtomicLong MODEL_SEQUENCE = new AtomicLong();
    private static boolean loaded;
    private static long lastExecutorPersistNanos;

    private AutomationWorkspaceRepository() {
    }

    public static synchronized void updateExecutor(AutomationCliSnapshot snapshot, String runtimeState) {
        if (snapshot == null || snapshot.sessionId() == null || snapshot.sessionId().isBlank()) return;
        ensureLoaded();
        long now = System.currentTimeMillis();
        String id = "executor-" + safeId(snapshot.sessionId());
        AutomationWorkspaceTrace existing = TRACES.get(id);
        if (existing == null) {
            existing = new AutomationWorkspaceTrace(
                    id,
                    "executor",
                    AutomationWorkspaceTrace.titleFor(snapshot, snapshot.sessionId()),
                    cleanState(runtimeState),
                    now,
                    now,
                    0L,
                    null,
                    snapshot
            );
        } else {
            existing = existing.withExecutor(snapshot, cleanState(runtimeState), now);
        }
        TRACES.put(id, existing);
        trim();
        long nanos = System.nanoTime();
        if (lastExecutorPersistNanos == 0L || nanos - lastExecutorPersistNanos >= EXECUTOR_PERSIST_INTERVAL_NANOS
                || !existing.active()) {
            lastExecutorPersistNanos = nanos;
            persist(existing);
        }
    }

    public static synchronized String rememberModel(ModelActivityPresentation.TraceSnapshot trace) {
        ensureLoaded();
        ModelActivityPresentation.TraceSnapshot safe = trace == null
                ? ModelActivityPresentation.TraceSnapshot.empty()
                : trace;
        long created = safe.createdAtMillis() > 0L ? safe.createdAtMillis() : System.currentTimeMillis();
        String id = "model-" + created + "-" + MODEL_SEQUENCE.incrementAndGet();
        String status = safe.completedAtMillis() > 0L ? "completed" : "observing";
        AutomationWorkspaceTrace entry = new AutomationWorkspaceTrace(
                id,
                "model",
                safe.objective().isBlank() ? "Model response" : safe.objective(),
                status,
                created,
                Math.max(created, System.currentTimeMillis()),
                safe.completedAtMillis(),
                safe,
                null
        );
        TRACES.put(id, entry);
        trim();
        persist(entry);
        return id;
    }

    public static synchronized List<AutomationWorkspaceTrace> traces() {
        ensureLoaded();
        List<AutomationWorkspaceTrace> values = new ArrayList<>(TRACES.values());
        values.sort(Comparator.comparingLong(AutomationWorkspaceTrace::updatedAtMillis).reversed());
        return List.copyOf(values);
    }

    public static synchronized AutomationWorkspaceTrace trace(String id) {
        ensureLoaded();
        return id == null ? null : TRACES.get(id);
    }

    public static synchronized String latestId() {
        List<AutomationWorkspaceTrace> values = traces();
        return values.isEmpty() ? "" : values.get(0).id();
    }

    public static synchronized Path export(String id) throws IOException {
        ensureLoaded();
        AutomationWorkspaceTrace trace = TRACES.get(id);
        if (trace == null) throw new IOException("Unknown Automation Workspace trace: " + id);
        Files.createDirectories(EXPORT_ROOT);
        String base = safeId(trace.id()) + "-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(trace.updatedAtMillis()));
        Path json = EXPORT_ROOT.resolve(base + ".json");
        Path log = EXPORT_ROOT.resolve(base + ".log");
        writeAtomically(json, GSON.toJson(trace));
        writeAtomically(log, humanReadable(trace));
        KoilLog.info(KoilLog.AUTOMATION_THREAD, "workspace.export", log.toString());
        return log;
    }

    public static Path historyRoot() {
        return HISTORY_ROOT;
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.isDirectory(HISTORY_ROOT)) {
                try (var paths = Files.list(HISTORY_ROOT)) {
                    paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .forEach(AutomationWorkspaceRepository::loadOne);
                }
            }
            // Preserve the most recent pre-workspace session during migration.
            AutomationCliSnapshot legacy = AutomationCliSnapshotStore.load();
            if (legacy != null && !legacy.sessionId().isBlank()
                    && !TRACES.containsKey("executor-" + safeId(legacy.sessionId()))) {
                long now = System.currentTimeMillis();
                TRACES.put("executor-" + safeId(legacy.sessionId()), new AutomationWorkspaceTrace(
                        "executor-" + safeId(legacy.sessionId()), "executor",
                        AutomationWorkspaceTrace.titleFor(legacy, legacy.sessionId()), "observing",
                        now, now, 0L, null, legacy
                ));
            }
            trim();
        } catch (Exception exception) {
            KoilLog.warning(KoilLog.AUTOMATION_THREAD, "workspace.load", exception.getMessage());
        }
    }

    private static void loadOne(Path path) {
        try {
            AutomationWorkspaceTrace trace = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), AutomationWorkspaceTrace.class);
            if (trace != null && !trace.id().isBlank()) TRACES.put(trace.id(), trace);
        } catch (Exception exception) {
            KoilLog.warning(KoilLog.AUTOMATION_THREAD, "workspace.entry", path.getFileName() + ": " + exception.getMessage());
        }
    }

    private static void persist(AutomationWorkspaceTrace trace) {
        try {
            Files.createDirectories(HISTORY_ROOT);
            writeAtomically(HISTORY_ROOT.resolve(safeId(trace.id()) + ".json"), GSON.toJson(trace));
        } catch (Exception exception) {
            KoilLog.warning(KoilLog.AUTOMATION_THREAD, "workspace.persist", exception.getMessage());
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, content == null ? "" : content, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String humanReadable(AutomationWorkspaceTrace trace) {
        StringBuilder value = new StringBuilder();
        value.append("Automation Workspace trace\n")
                .append("id: ").append(trace.id()).append('\n')
                .append("kind: ").append(trace.kind()).append('\n')
                .append("title: ").append(trace.title()).append('\n')
                .append("status: ").append(trace.status()).append('\n')
                .append("created: ").append(trace.createdAtMillis()).append('\n')
                .append("updated: ").append(trace.updatedAtMillis()).append('\n')
                .append("completed: ").append(trace.completedAtMillis()).append("\n\n");
        if (trace.modelTrace() != null) {
            value.append("MODEL ACTIVITY\n")
                    .append(stripFormatting(ModelActivityPresentation.render(trace.modelTrace())))
                    .append("\n\n");
        }
        if (trace.executorTrace() != null) {
            AutomationCliSnapshot executor = trace.executorTrace();
            value.append("EXECUTOR ACTIVITY\n")
                    .append("session: ").append(executor.sessionId()).append('\n')
                    .append("mode: ").append(executor.mode()).append('\n')
                    .append("actor: ").append(executor.actor()).append('\n');
            for (AutomationCliRow row : executor.rows()) {
                if (row == null) continue;
                value.append("  ".repeat(Math.max(0, Math.min(12, row.indentationDepth()))))
                        .append(row.statusMarker()).append(' ')
                        .append(row.label());
                if (!row.value().isBlank()) value.append(" | ").append(row.value());
                value.append('\n');
                appendField(value, "search", row.search());
                appendField(value, "requires", row.requires());
                appendField(value, "received", row.received());
                appendField(value, "output", row.output());
                appendField(value, "failure", row.failure());
                appendField(value, "recovery", row.recovery());
                appendField(value, "source", row.source());
            }
        }
        return value.toString();
    }

    private static void appendField(StringBuilder target, String name, String field) {
        if (field != null && !field.isBlank()) target.append("    ").append(name).append(": ").append(field).append('\n');
    }

    private static String stripFormatting(String text) {
        return text == null ? "" : text.replaceAll("(?i)§[0-9A-FK-OR]", "").replace("-# ", "");
    }

    private static void trim() {
        while (TRACES.size() > MAXIMUM_TRACES) {
            AutomationWorkspaceTrace oldest = TRACES.values().stream()
                    .min(Comparator.comparingLong(AutomationWorkspaceTrace::updatedAtMillis)).orElse(null);
            if (oldest == null) return;
            TRACES.remove(oldest.id());
            try {
                Files.deleteIfExists(HISTORY_ROOT.resolve(safeId(oldest.id()) + ".json"));
            } catch (IOException exception) {
                KoilLog.warning(KoilLog.AUTOMATION_THREAD, "workspace.trim", exception.getMessage());
            }
        }
    }

    private static String cleanState(String state) {
        String value = state == null ? "" : state.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return value.isBlank() ? "observing" : value;
    }

    private static String safeId(String value) {
        String safe = value == null ? "trace" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.isBlank() ? "trace" : safe;
    }
}
