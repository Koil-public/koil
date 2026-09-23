package com.spirit.koil.api.model.retrieval;

import java.util.List;

/** Koil-owned dense-search contract; TurboVec is one implementation detail. */
public interface VectorIndex extends AutoCloseable {
    void add(long id, float[] embedding);

    boolean remove(long id);

    List<VectorSearchResult> search(float[] query, VectorSearchRequest request);

    void sync();

    VectorIndexHealth health();

    @Override
    void close();
}
