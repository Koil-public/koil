package com.spirit.koil.api.model.runtime.universal;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Bounded durable history of universal benchmark sweeps.
 *
 * Winner persistence answers "what should Koil launch?". This store answers the different question
 * "what did Koil actually measure?" so candidate evidence can later drive workload-aware planning,
 * regression detection, memory safety, and optimization selection without turning adapter-native
 * tuning files into the universal source of truth.
 */
public final class KoilBenchmarkHistoryStore {
    private static final int FORMAT_VERSION = 3;
    private static final int MAX_SESSIONS = 32;
    private static final int MAX_RESULTS_PER_SESSION = 64;
    private static final String PREFIX = "session.";

    private KoilBenchmarkHistoryStore() {}

    public static void save(
            Path path,
            KoilTuningKey key,
            KoilBenchmarkSession session,
            KoilBenchmarkWorkload workload,
            String adapterId
    ) {
        if (path == null || key == null || session == null) return;
        synchronized (KoilBenchmarkHistoryStore.class) {
            List<StoredSession> sessions = new ArrayList<>(read(path));
            sessions.removeIf(value -> value.session().id().equals(session.id()));
            sessions.add(new StoredSession(key, normalize(session), normalize(workload), safe(adapterId)));
            sessions.sort(Comparator.comparing((StoredSession value) -> value.session().completedAt()).reversed());
            if (sessions.size() > MAX_SESSIONS) {
                sessions = new ArrayList<>(sessions.subList(0, MAX_SESSIONS));
            }
            write(path, sessions);
        }
    }

    public static List<StoredSession> recent(Path path, KoilTuningKey query, int limit) {
        if (path == null) return List.of();
        int bounded = Math.max(1, Math.min(MAX_SESSIONS, limit));
        synchronized (KoilBenchmarkHistoryStore.class) {
            return read(path).stream()
                    .filter(value -> query == null || value.key().matches(query))
                    .sorted(Comparator.comparing((StoredSession value) -> value.session().completedAt()).reversed())
                    .limit(bounded)
                    .toList();
        }
    }

    public static Optional<StoredSession> latest(Path path, KoilTuningKey query) {
        return recent(path, query, 1).stream().findFirst();
    }

    public static int size(Path path) {
        synchronized (KoilBenchmarkHistoryStore.class) {
            return read(path).size();
        }
    }

    private static KoilBenchmarkSession normalize(KoilBenchmarkSession session) {
        List<KoilBenchmarkResult> results = session.results().size() <= MAX_RESULTS_PER_SESSION
                ? session.results()
                : session.results().subList(0, MAX_RESULTS_PER_SESSION);
        List<KoilCandidateScorer.ScoredResult> scored = KoilCandidateScorer.score(results);
        int failed = (int) results.stream()
                .filter(result -> result.outcome() != KoilBenchmarkResult.Outcome.SUCCESS)
                .count();
        return new KoilBenchmarkSession(
                session.id(), session.startedAt(), session.completedAt(), results, scored, failed);
    }

    private static KoilBenchmarkWorkload normalize(KoilBenchmarkWorkload workload) {
        return workload == null
                ? new KoilBenchmarkWorkload("", 64, Duration.ofMinutes(2), "benchmark")
                : workload;
    }

    private static List<StoredSession> read(Path path) {
        if (!Files.isRegularFile(path)) return List.of();
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException ignored) {
            return List.of();
        }
        String version = p.getProperty("version", "");
        if (!(Integer.toString(FORMAT_VERSION).equals(version) || "2".equals(version) || "1".equals(version))) return List.of();

        int count = intValue(p.getProperty("count"), 0);
        List<StoredSession> result = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_SESSIONS, Math.max(0, count)); i++) {
            readSession(p, PREFIX + i + ".").ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    private static Optional<StoredSession> readSession(Properties p, String base) {
        try {
            KoilTuningKey key = new KoilTuningKey(
                    p.getProperty(base + "key.hardware", ""),
                    p.getProperty(base + "key.adapter", ""),
                    p.getProperty(base + "key.model", ""),
                    p.getProperty(base + "key.architecture", ""),
                    p.getProperty(base + "key.format", ""),
                    p.getProperty(base + "key.quantization", ""),
                    p.getProperty(base + "key.context", ""),
                    p.getProperty(base + "key.statePrecision", KoilStatePrecisionEvidence.LEGACY_FP16),
                    enumValue(KoilRuntimeBackend.class, p.getProperty(base + "key.backend", ""), KoilRuntimeBackend.UNKNOWN),
                    p.getProperty(base + "key.runtime", ""));
            String id = p.getProperty(base + "id", "");
            Instant started = Instant.parse(p.getProperty(base + "started"));
            Instant completed = Instant.parse(p.getProperty(base + "completed"));
            String adapter = p.getProperty(base + "adapter", "");
            String workloadName = p.getProperty(base + "workload.name", "benchmark");
            KoilBenchmarkWorkload workload = new KoilBenchmarkWorkload(
                    decode(p.getProperty(base + "workload.prompt", "")),
                    intValue(p.getProperty(base + "workload.outputTokens"), 64),
                    Duration.ofMillis(Math.max(1L, longValue(p.getProperty(base + "workload.timeoutMillis"), 120_000L))),
                    workloadName,
                    enumValue(KoilBenchmarkWorkload.Kind.class,
                            p.getProperty(base + "workload.kind", ""),
                            new KoilBenchmarkWorkload("", 64, Duration.ofMinutes(2), workloadName).kind()));
            int resultCount = Math.min(MAX_RESULTS_PER_SESSION, Math.max(0, intValue(p.getProperty(base + "resultCount"), 0)));
            List<KoilBenchmarkResult> results = new ArrayList<>();
            for (int i = 0; i < resultCount; i++) {
                readResult(p, base + "result." + i + ".").ifPresent(results::add);
            }
            KoilBenchmarkSession session = new KoilBenchmarkSession(
                    id, started, completed, results, KoilCandidateScorer.score(results, workload),
                    (int) results.stream().filter(value -> value.outcome() != KoilBenchmarkResult.Outcome.SUCCESS).count());
            return Optional.of(new StoredSession(key, session, workload, adapter));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<KoilBenchmarkResult> readResult(Properties p, String base) {
        try {
            KoilExecutionSettings settings = new KoilExecutionSettings(
                    enumValue(KoilPlacementPolicy.class, p.getProperty(base + "placement", ""), KoilPlacementPolicy.AUTOMATIC),
                    p.getProperty(base + "device", ""),
                    intValue(p.getProperty(base + "gpuLayers"), -1),
                    intValue(p.getProperty(base + "generationThreads"), 0),
                    intValue(p.getProperty(base + "batchThreads"), 0),
                    intValue(p.getProperty(base + "batchSize"), 0),
                    intValue(p.getProperty(base + "microBatchSize"), 0),
                    intValue(p.getProperty(base + "pollPercent"), 0),
                    intValue(p.getProperty(base + "batchPollMode"), 0),
                    enumValue(KoilStatePlacement.class, p.getProperty(base + "statePlacement", ""), KoilStatePlacement.AUTOMATIC),
                    enumValue(KoilOperatorPlacement.class, p.getProperty(base + "operatorPlacement", ""), KoilOperatorPlacement.AUTOMATIC),
                    enumValue(KoilFeatureMode.class, p.getProperty(base + "flashAttention", ""), KoilFeatureMode.AUTO),
                    Boolean.parseBoolean(p.getProperty(base + "repack", "false")),
                    longValue(p.getProperty(base + "memoryBudgetBytes"), 0L));
            KoilBenchmarkCandidate candidate = new KoilBenchmarkCandidate(
                    p.getProperty(base + "candidateId", ""),
                    p.getProperty(base + "candidateLabel", ""),
                    enumValue(KoilRuntimeBackend.class, p.getProperty(base + "backend", ""), KoilRuntimeBackend.UNKNOWN),
                    settings,
                    enumValue(KoilBenchmarkCandidate.ValidationMode.class,
                            p.getProperty(base + "validationMode", ""), KoilBenchmarkCandidate.ValidationMode.EXACT),
                    p.getProperty(base + "contextRegime", ""),
                    readStringMap(p, base + "candidateAttribute."));
            return Optional.of(new KoilBenchmarkResult(
                    candidate,
                    enumValue(KoilBenchmarkResult.Outcome.class, p.getProperty(base + "outcome", ""), KoilBenchmarkResult.Outcome.FAILED),
                    intValue(p.getProperty(base + "promptTokens"), 0),
                    intValue(p.getProperty(base + "generatedTokens"), 0),
                    doubleOrNull(p.getProperty(base + "promptTps")),
                    doubleOrNull(p.getProperty(base + "generationTps")),
                    doubleOrNull(p.getProperty(base + "promptMillis")),
                    doubleOrNull(p.getProperty(base + "generationMillis")),
                    doubleOrNull(p.getProperty(base + "coldTtftMillis")),
                    doubleOrNull(p.getProperty(base + "warmTtftMillis")),
                    longOrNull(p.getProperty(base + "wallMillis")),
                    longOrNull(p.getProperty(base + "memoryBytes")),
                    longOrNull(p.getProperty(base + "acceleratorMemoryBytes")),
                    p.getProperty(base + "actualPlacement", ""),
                    integerOrNull(p.getProperty(base + "actualGpuLayers")),
                    enumValue(KoilStatePlacement.class, p.getProperty(base + "actualStatePlacement", ""), KoilStatePlacement.AUTOMATIC),
                    enumValue(KoilOperatorPlacement.class, p.getProperty(base + "actualOperatorPlacement", ""), KoilOperatorPlacement.AUTOMATIC),
                    booleanOrNull(p.getProperty(base + "integrityPassed")),
                    p.getProperty(base + "failureType", ""),
                    decode(p.getProperty(base + "detail", "")),
                    Instant.parse(p.getProperty(base + "measuredAt")),
                    p.getProperty(base + "runtimeRevision", ""),
                    readDoubleMap(p, base + "metric."),
                    readStringMap(p, base + "observation.")));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static void write(Path path, List<StoredSession> sessions) {
        Properties p = new Properties();
        p.setProperty("version", Integer.toString(FORMAT_VERSION));
        p.setProperty("count", Integer.toString(sessions.size()));
        for (int i = 0; i < sessions.size(); i++) {
            StoredSession stored = sessions.get(i);
            String base = PREFIX + i + ".";
            writeKey(p, base, stored.key());
            p.setProperty(base + "id", stored.session().id());
            p.setProperty(base + "started", stored.session().startedAt().toString());
            p.setProperty(base + "completed", stored.session().completedAt().toString());
            p.setProperty(base + "adapter", stored.adapterId());
            p.setProperty(base + "workload.name", stored.workload().name());
            p.setProperty(base + "workload.kind", stored.workload().kind().name());
            p.setProperty(base + "workload.prompt", encode(stored.workload().prompt()));
            p.setProperty(base + "workload.outputTokens", Integer.toString(stored.workload().outputTokens()));
            p.setProperty(base + "workload.timeoutMillis", Long.toString(stored.workload().timeout().toMillis()));
            List<KoilBenchmarkResult> results = stored.session().results();
            p.setProperty(base + "resultCount", Integer.toString(results.size()));
            for (int j = 0; j < results.size(); j++) writeResult(p, base + "result." + j + ".", results.get(j));
        }
        atomicWrite(path, p);
    }

    private static void writeKey(Properties p, String base, KoilTuningKey key) {
        p.setProperty(base + "key.hardware", key.hardwareFingerprint());
        p.setProperty(base + "key.adapter", key.adapterId());
        p.setProperty(base + "key.model", key.modelId());
        p.setProperty(base + "key.architecture", key.architectureId());
        p.setProperty(base + "key.format", key.sourceFormat());
        p.setProperty(base + "key.quantization", key.quantization());
        p.setProperty(base + "key.context", key.contextRegime());
        p.setProperty(base + "key.statePrecision", key.statePrecisionRegime());
        p.setProperty(base + "key.backend", key.backend().name());
        p.setProperty(base + "key.runtime", key.runtimeRevision());
    }

    private static void writeResult(Properties p, String base, KoilBenchmarkResult result) {
        KoilBenchmarkCandidate c = result.candidate();
        KoilExecutionSettings s = c.settings();
        p.setProperty(base + "candidateId", c.id());
        p.setProperty(base + "candidateLabel", c.label());
        p.setProperty(base + "backend", c.backend().name());
        p.setProperty(base + "contextRegime", c.contextRegime());
        p.setProperty(base + "validationMode", c.validationMode().name());
        p.setProperty(base + "placement", s.placement().name());
        p.setProperty(base + "device", s.device());
        p.setProperty(base + "gpuLayers", Integer.toString(s.gpuLayers()));
        p.setProperty(base + "generationThreads", Integer.toString(s.generationThreads()));
        p.setProperty(base + "batchThreads", Integer.toString(s.batchThreads()));
        p.setProperty(base + "batchSize", Integer.toString(s.batchSize()));
        p.setProperty(base + "microBatchSize", Integer.toString(s.microBatchSize()));
        p.setProperty(base + "pollPercent", Integer.toString(s.pollPercent()));
        p.setProperty(base + "batchPollMode", Integer.toString(s.batchPollMode()));
        p.setProperty(base + "statePlacement", s.statePlacement().name());
        p.setProperty(base + "operatorPlacement", s.operatorPlacement().name());
        p.setProperty(base + "flashAttention", s.flashAttention().name());
        p.setProperty(base + "repack", Boolean.toString(s.repack()));
        p.setProperty(base + "memoryBudgetBytes", Long.toString(s.memoryBudgetBytes()));
        c.attributes().forEach((name, value) -> putEncoded(p, base + "candidateAttribute.", name, value));
        p.setProperty(base + "outcome", result.outcome().name());
        p.setProperty(base + "promptTokens", Integer.toString(result.promptTokens()));
        p.setProperty(base + "generatedTokens", Integer.toString(result.generatedTokens()));
        put(p, base + "promptTps", result.promptTokensPerSecond());
        put(p, base + "generationTps", result.generationTokensPerSecond());
        put(p, base + "promptMillis", result.promptMillis());
        put(p, base + "generationMillis", result.generationMillis());
        put(p, base + "coldTtftMillis", result.coldTimeToFirstTokenMillis());
        put(p, base + "warmTtftMillis", result.warmTimeToFirstTokenMillis());
        put(p, base + "wallMillis", result.wallMillis());
        put(p, base + "memoryBytes", result.memoryBytes());
        put(p, base + "acceleratorMemoryBytes", result.acceleratorMemoryBytes());
        p.setProperty(base + "actualPlacement", result.actualPlacement());
        if (result.actualGpuLayers() != null) p.setProperty(base + "actualGpuLayers", Integer.toString(result.actualGpuLayers()));
        p.setProperty(base + "actualStatePlacement", result.actualStatePlacement().name());
        p.setProperty(base + "actualOperatorPlacement", result.actualOperatorPlacement().name());
        if (result.integrityPassed() != null) p.setProperty(base + "integrityPassed", Boolean.toString(result.integrityPassed()));
        p.setProperty(base + "failureType", result.failureType());
        p.setProperty(base + "detail", encode(result.detail()));
        p.setProperty(base + "measuredAt", result.measuredAt().toString());
        p.setProperty(base + "runtimeRevision", result.runtimeRevision());
        result.metrics().forEach((name, value) -> {
            if (value != null && Double.isFinite(value)) p.setProperty(base + "metric." + encode(name), Double.toString(value));
        });
        result.observations().forEach((name, value) -> putEncoded(p, base + "observation.", name, value));
    }

    private static void atomicWrite(Path path, Properties properties) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            Path parent = absolute.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent == null ? Path.of(".") : parent, "koil-benchmark-history-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                properties.store(writer, "Koil universal benchmark history");
            }
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Benchmark history is optimization evidence. Persistence failure must never block inference.
        }
    }

    private static void putEncoded(Properties p, String prefix, String name, String value) {
        if (name == null || name.isBlank() || value == null) return;
        p.setProperty(prefix + encode(name), value);
    }

    private static void put(Properties p, String name, Number value) {
        if (value != null) p.setProperty(name, value.toString());
    }

    private static Map<String, String> readStringMap(Properties p, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : p.stringPropertyNames()) {
            if (!name.startsWith(prefix)) continue;
            try { result.put(decode(name.substring(prefix.length())), p.getProperty(name, "")); }
            catch (Exception ignored) { }
        }
        return Map.copyOf(result);
    }

    private static Map<String, Double> readDoubleMap(Properties p, String prefix) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String name : p.stringPropertyNames()) {
            if (!name.startsWith(prefix)) continue;
            try { result.put(decode(name.substring(prefix.length())), Double.parseDouble(p.getProperty(name))); }
            catch (Exception ignored) { }
        }
        return Map.copyOf(result);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(safe(value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        if (value == null || value.isBlank()) return "";
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static int intValue(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
    }

    private static long longValue(String value, long fallback) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return fallback; }
    }

    private static Integer integerOrNull(String value) {
        try { return value == null || value.isBlank() ? null : Integer.parseInt(value); }
        catch (Exception ignored) { return null; }
    }

    private static Long longOrNull(String value) {
        try { return value == null || value.isBlank() ? null : Long.parseLong(value); }
        catch (Exception ignored) { return null; }
    }

    private static Double doubleOrNull(String value) {
        try {
            if (value == null || value.isBlank()) return null;
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (Exception ignored) { return null; }
    }

    private static Boolean booleanOrNull(String value) {
        return value == null || value.isBlank() ? null : Boolean.parseBoolean(value);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        try { return Enum.valueOf(type, safe(value).toUpperCase()); }
        catch (Exception ignored) { return fallback; }
    }

    private static String safe(String value) { return value == null ? "" : value.strip(); }

    public record StoredSession(
            KoilTuningKey key,
            KoilBenchmarkSession session,
            KoilBenchmarkWorkload workload,
            String adapterId
    ) {
        public StoredSession {
            if (key == null) throw new IllegalArgumentException("key is required");
            if (session == null) throw new IllegalArgumentException("session is required");
            workload = normalize(workload);
            adapterId = safe(adapterId);
        }
    }
}
