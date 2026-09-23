package com.spirit.koil.api.model.retrieval;

/** Small deterministic check for the reproducible benchmark harness. */
public final class RetrievalBenchmarkProof {
    private RetrievalBenchmarkProof() {
    }

    public static void main(String[] args) {
        RetrievalBenchmark.Report report = RetrievalBenchmark.measure(new int[]{256, 1_000}, 16, 3);
        require(report.rows().size() == 2, "benchmark must report every requested corpus size");
        for (RetrievalBenchmark.Row row : report.rows()) {
            require(row.exactTopId() == 1L, "exact identifier control must remain correct");
            require(row.semanticTopId() == 2L, "semantic control must remain correct");
            require(row.legacyScanNanos() >= 0L && row.hybridNanos() >= 0L, "latencies must be measurable");
            require(row.rawVectorBytes() == (long) row.entries() * 16L * Float.BYTES,
                    "raw vector footprint must be explicit");
        }
        System.out.println("RetrievalBenchmarkProof: PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
