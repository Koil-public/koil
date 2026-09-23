package com.spirit.koil.api.model.retrieval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/** Rebuilds a disposable native index from SQLite authority without disturbing a usable prior file. */
public final class KnowledgeIndexMaintenance {
    private static final int MAXIMUM_CALIBRATION_ROWS = 1_000;

    private KnowledgeIndexMaintenance() {
    }

    public static RebuildResult rebuild(KnowledgeMetadataStore store, Path vectorPath, int bitWidth) {
        Objects.requireNonNull(store, "store");
        Path target = Objects.requireNonNull(vectorPath, "vectorPath").toAbsolutePath().normalize();
        List<KnowledgeMetadataStore.StoredEmbedding> embeddings = store.activeEmbeddings();
        Path staged;
        try {
            Path parent = target.getParent();
            if (parent == null) throw new IOException("vector index path has no parent");
            Files.createDirectories(parent);
            staged = Files.createTempFile(parent, target.getFileName().toString() + ".rebuild-", ".tvim");
            Files.deleteIfExists(staged); // only the temporary path allocated immediately above
        } catch (IOException exception) {
            return new RebuildResult(false, 0L, target, "Unable to stage TurboVec rebuild: " + exception.getMessage());
        }
        TurboVecVectorIndex index = TurboVecVectorIndex.open(staged, store.embeddingIdentity().dimensions(), bitWidth);
        if (!index.health().ready()) {
            index.close();
            return new RebuildResult(false, 0L, staged, "TurboVec rebuild unavailable: " + index.health().detail());
        }
        try {
            float[] calibrationSample = calibrationSample(embeddings, store.embeddingIdentity().dimensions());
            // TurboVec TQ+ calibration is only safe before insertion. One-row indexes remain valid uncalibrated TurboQuant indexes.
            if (calibrationSample != null) index.calibrate(calibrationSample);
            for (KnowledgeMetadataStore.StoredEmbedding embedding : embeddings) {
                index.add(embedding.entry().id(), embedding.embedding());
            }
            index.sync();
            if (index.health().entryCount() != embeddings.size()) {
                return new RebuildResult(false, index.health().entryCount(), staged,
                        "TurboVec rebuild count does not match authoritative metadata");
            }
        } catch (RuntimeException exception) {
            return new RebuildResult(false, index.health().entryCount(), staged,
                    "TurboVec rebuild failed: " + concise(exception));
        } finally {
            index.close();
        }
        try {
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupported) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new RebuildResult(true, embeddings.size(), target, "");
        } catch (IOException exception) {
            return new RebuildResult(false, embeddings.size(), staged,
                    "TurboVec rebuilt index was retained at staging path after publish failure: " + exception.getMessage());
        }
    }

    private static String concise(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static float[] calibrationSample(List<KnowledgeMetadataStore.StoredEmbedding> embeddings, int dimensions) {
        int rows = Math.min(MAXIMUM_CALIBRATION_ROWS, embeddings.size());
        if (rows < 2) return null;
        float[] sample = new float[Math.multiplyExact(rows, dimensions)];
        for (int row = 0; row < rows; row++) {
            int source = (int) ((long) row * embeddings.size() / rows);
            float[] vector = embeddings.get(source).embedding();
            System.arraycopy(vector, 0, sample, row * dimensions, dimensions);
        }
        return sample;
    }

    public record RebuildResult(boolean rebuilt, long entries, Path path, String detail) {
        public RebuildResult {
            if (entries < 0L) throw new IllegalArgumentException("entry count must not be negative");
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            detail = detail == null ? "" : detail.strip();
        }
    }
}
