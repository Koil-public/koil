package com.spirit.koil.api.model.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Truthful no-native fallback; bounded callers use it only while TurboVec is unavailable. */
public final class FlatJavaVectorIndex implements VectorIndex {
    private final int dimensions;
    private final Map<Long, float[]> embeddings = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean closed;

    public FlatJavaVectorIndex(int dimensions) {
        if (dimensions <= 0) throw new IllegalArgumentException("dimensions must be positive");
        this.dimensions = dimensions;
    }

    @Override
    public void add(long id, float[] embedding) {
        if (id <= 0L) throw new IllegalArgumentException("id must be positive");
        float[] normalized = normalize(embedding);
        this.lock.writeLock().lock();
        try {
            ensureOpen();
            this.embeddings.put(id, normalized);
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    @Override
    public boolean remove(long id) {
        this.lock.writeLock().lock();
        try {
            ensureOpen();
            return this.embeddings.remove(id) != null;
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    @Override
    public List<VectorSearchResult> search(float[] query, VectorSearchRequest request) {
        float[] normalizedQuery = normalize(query);
        if (request == null) throw new IllegalArgumentException("request is required");
        this.lock.readLock().lock();
        try {
            ensureOpen();
            // ponytail: linear fallback; TurboVec replaces this path when its managed native bridge is healthy.
            List<VectorSearchResult> results = new ArrayList<>();
            for (Map.Entry<Long, float[]> entry : this.embeddings.entrySet()) {
                if (request.filtered() && !request.allowedIds().contains(entry.getKey())) continue;
                results.add(new VectorSearchResult(entry.getKey(), dot(normalizedQuery, entry.getValue())));
            }
            results.sort(Comparator.comparing(VectorSearchResult::score).reversed().thenComparingLong(VectorSearchResult::id));
            return List.copyOf(results.subList(0, Math.min(request.limit(), results.size())));
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public void sync() {
        this.lock.readLock().lock();
        try {
            ensureOpen();
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public VectorIndexHealth health() {
        this.lock.readLock().lock();
        try {
            return this.closed
                    ? new VectorIndexHealth(VectorIndexHealth.State.CLOSED, "flat-java", "closed", 0L, this.dimensions)
                    : VectorIndexHealth.ready("flat-java", this.embeddings.size(), this.dimensions);
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        this.lock.writeLock().lock();
        try {
            if (this.closed) return;
            this.closed = true;
            this.embeddings.clear();
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    private float[] normalize(float[] vector) {
        if (vector == null || vector.length != this.dimensions) {
            throw new IllegalArgumentException("vector dimensions must match " + this.dimensions);
        }
        double normSquared = 0.0D;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("vector values must be finite");
            normSquared += value * value;
        }
        if (normSquared == 0.0D) throw new IllegalArgumentException("vector must not be zero");
        float scale = (float) (1.0D / Math.sqrt(normSquared));
        float[] normalized = vector.clone();
        for (int index = 0; index < normalized.length; index++) normalized[index] *= scale;
        return normalized;
    }

    private static float dot(float[] left, float[] right) {
        float score = 0.0F;
        for (int index = 0; index < left.length; index++) score += left[index] * right[index];
        return score;
    }

    private void ensureOpen() {
        if (this.closed) throw new IllegalStateException("vector index is closed");
    }
}
