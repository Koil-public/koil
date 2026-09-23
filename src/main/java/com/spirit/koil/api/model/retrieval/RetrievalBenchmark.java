package com.spirit.koil.api.model.retrieval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reproducible in-process comparison for Koil's historical lexical shape, exact matching, and Java fallback.
 * It deliberately does not infer TurboVec performance when no verified native bridge is loaded.
 */
public final class RetrievalBenchmark {
    private static volatile long blackhole;

    private RetrievalBenchmark() {
    }

    public static void main(String[] args) {
        Report report = measure(new int[]{256, 1_000, 10_000, 100_000}, 32, 5);
        System.out.println("# Koil retrieval benchmark");
        System.out.println("entries,legacy_lexical_ms,exact_ms,flat_java_ms,hybrid_ms,flat_ingest_ms,flat_restore_ms,raw_vector_bytes,exact_top_id,semantic_top_id,turbovec");
        for (Row row : report.rows()) {
            System.out.println(row.entries() + "," + millis(row.legacyScanNanos()) + "," + millis(row.exactNanos())
                    + "," + millis(row.flatJavaNanos()) + "," + millis(row.hybridNanos()) + "," + millis(row.ingestNanos())
                    + "," + millis(row.restoreNanos()) + "," + row.rawVectorBytes() + "," + row.exactTopId()
                    + "," + row.semanticTopId() + ",not-qualified");
        }
        System.out.println("TurboVec is intentionally not benchmarked until a pinned native bridge loads and passes its positive ABI proof.");
    }

    public static Report measure(int[] sizes, int dimensions, int iterations) {
        if (sizes == null || sizes.length == 0 || dimensions < 8 || iterations <= 0) {
            throw new IllegalArgumentException("sizes, dimensions >= 8, and iterations are required");
        }
        List<Row> rows = new ArrayList<>();
        for (int size : sizes) rows.add(measure(size, dimensions, iterations));
        return new Report(List.copyOf(rows));
    }

    private static Row measure(int size, int dimensions, int iterations) {
        if (size < 2) throw new IllegalArgumentException("benchmark corpus must contain at least two controls");
        Corpus corpus = Corpus.create(size, dimensions);
        ExactKnowledgeIndex exact = ExactKnowledgeIndex.from(corpus.entries());
        Map<Long, KnowledgeEntry> entriesById = new LinkedHashMap<>();
        for (KnowledgeEntry entry : corpus.entries()) entriesById.put(entry.id(), entry);
        long ingest = timed(() -> {
            try (FlatJavaVectorIndex index = index(corpus)) {
                blackhole ^= index.health().entryCount();
            }
            return null;
        });
        FlatJavaVectorIndex flat = index(corpus);
        try {
            flat.search(corpus.semanticQuery(), new VectorSearchRequest(10, Set.of(), 0)); // JIT warm-up
            exact.search(corpus.exactQuery(), Set.of(), 10);
            lexicalScan(corpus.entries(), corpus.semanticText());
            long legacy = median(iterations, () -> lexicalScan(corpus.entries(), corpus.semanticText()));
            long exactNanos = median(iterations, () -> exact.search(corpus.exactQuery(), Set.of(), 10));
            long flatNanos = median(iterations,
                    () -> flat.search(corpus.semanticQuery(), new VectorSearchRequest(10, Set.of(), 0)));
            long hybrid = median(iterations, () -> ReciprocalRankFusion.fuse(entriesById,
                    flat.search(corpus.semanticQuery(), new VectorSearchRequest(10, Set.of(), 0)),
                    exact.search(corpus.exactQuery(), Set.of(), 10)));
            long restore = timed(() -> {
                try (FlatJavaVectorIndex restored = index(corpus)) {
                    blackhole ^= restored.health().entryCount();
                }
                return null;
            });
            long exactTop = exact.search(corpus.exactQuery(), Set.of(), 1).get(0).id();
            long semanticTop = flat.search(corpus.semanticQuery(), new VectorSearchRequest(1, Set.of(), 0)).get(0).id();
            return new Row(size, legacy, exactNanos, flatNanos, hybrid, ingest, restore,
                    (long) size * dimensions * Float.BYTES, exactTop, semanticTop);
        } finally {
            flat.close();
        }
    }

    private static FlatJavaVectorIndex index(Corpus corpus) {
        FlatJavaVectorIndex index = new FlatJavaVectorIndex(corpus.dimensions());
        for (int offset = 0; offset < corpus.entries().size(); offset++) {
            index.add(corpus.entries().get(offset).id(), corpus.vectors().get(offset));
        }
        return index;
    }

    /** Representative of the retired token-overlap scan; it is not presented as a TurboVec comparison. */
    private static List<Long> lexicalScan(List<KnowledgeEntry> entries, String query) {
        String[] tokens = query.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+");
        Map<Long, Integer> scores = new HashMap<>();
        for (KnowledgeEntry entry : entries) {
            String text = entry.text().toLowerCase(java.util.Locale.ROOT);
            int score = 0;
            for (String token : tokens) if (!token.isBlank() && text.contains(token)) score++;
            if (score > 0) scores.put(entry.id(), score);
        }
        List<Long> ids = new ArrayList<>(scores.keySet());
        ids.sort((left, right) -> {
            int compared = Integer.compare(scores.get(right), scores.get(left));
            return compared != 0 ? compared : Long.compare(left, right);
        });
        blackhole ^= ids.size();
        return ids.subList(0, Math.min(10, ids.size()));
    }

    private static long median(int iterations, Operation operation) {
        long[] samples = new long[iterations];
        for (int index = 0; index < iterations; index++) samples[index] = timed(operation);
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    private static long timed(Operation operation) {
        long started = System.nanoTime();
        Object result = operation.run();
        if (result != null) blackhole ^= result.hashCode();
        return System.nanoTime() - started;
    }

    private static String millis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    @FunctionalInterface
    private interface Operation {
        Object run();
    }

    private record Corpus(List<KnowledgeEntry> entries, List<float[]> vectors, float[] semanticQuery,
                          String semanticText, String exactQuery, int dimensions) {
        private static Corpus create(int size, int dimensions) {
            List<KnowledgeEntry> entries = new ArrayList<>(size);
            List<float[]> vectors = new ArrayList<>(size);
            entries.add(entry(1L, "minecraft:diamond_shovel", "The exact Minecraft registry identifier."));
            vectors.add(vector(dimensions, 1));
            entries.add(entry(2L, "automation_failure", "Right-clicking a container placed the held block instead of opening it."));
            vectors.add(vector(dimensions, 0));
            for (long id = 3L; id <= size; id++) {
                entries.add(entry(id, "document-" + id, "Documentation record " + id + " for unrelated configuration and tools."));
                vectors.add(vector(dimensions, (int) (id % dimensions)));
            }
            return new Corpus(List.copyOf(entries), List.copyOf(vectors), vector(dimensions, 0),
                    "why did container interaction fail", "minecraft:diamond_shovel", dimensions);
        }

        private static KnowledgeEntry entry(long id, String source, String text) {
            return new KnowledgeEntry(id, KnowledgeType.KOIL_DOCUMENTATION, "benchmark", text, source, "",
                    id, 0.5D, 0.5D, KnowledgeTrust.HISTORICAL_CONTEXT, Map.of("id", source));
        }

        private static float[] vector(int dimensions, int coordinate) {
            float[] vector = new float[dimensions];
            vector[coordinate % dimensions] = 1.0F;
            return vector;
        }
    }

    public record Report(List<Row> rows) {
        public Report {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    public record Row(int entries, long legacyScanNanos, long exactNanos, long flatJavaNanos, long hybridNanos,
                      long ingestNanos, long restoreNanos, long rawVectorBytes, long exactTopId, long semanticTopId) {
    }
}
