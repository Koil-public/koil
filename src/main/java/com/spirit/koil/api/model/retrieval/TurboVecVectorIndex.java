package com.spirit.koil.api.model.retrieval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Preferred native vector adapter; an unavailable or corrupt native index is always reported truthfully. */
public final class TurboVecVectorIndex implements VectorIndex {
    private final Path path;
    private final int dimensions;
    private final int bitWidth;
    private final TurboVecNativeBridge bridge;
    private final VectorIndexHealth.State unavailableState;
    private final String detail;
    private long entryCount;
    private boolean closed;

    private TurboVecVectorIndex(Path path, int dimensions, int bitWidth, TurboVecNativeBridge bridge,
                                VectorIndexHealth.State unavailableState, String detail, long entryCount) {
        this.path = path;
        this.dimensions = dimensions;
        this.bitWidth = bitWidth;
        this.bridge = bridge;
        this.unavailableState = unavailableState;
        this.detail = detail == null ? "" : detail;
        this.entryCount = entryCount;
    }

    public static TurboVecVectorIndex open(Path path, int dimensions, int bitWidth) {
        if (dimensions <= 0 || dimensions % 8 != 0) throw new IllegalArgumentException("TurboVec dimensions must be a positive multiple of 8");
        if (bitWidth < 2 || bitWidth > 4) throw new IllegalArgumentException("TurboVec bit width must be 2, 3, or 4");
        Path absolute = path.toAbsolutePath();
        TurboVecNativeBridge.Availability availability = TurboVecNativeBridge.availability();
        if (!availability.available()) {
            return new TurboVecVectorIndex(absolute, dimensions, bitWidth, null, VectorIndexHealth.State.UNAVAILABLE,
                    availability.detail(), 0L);
        }
        try {
            Path parent = absolute.getParent();
            if (parent != null) Files.createDirectories(parent);
            TurboVecNativeBridge bridge = Files.exists(absolute) ? TurboVecNativeBridge.load(absolute) : TurboVecNativeBridge.create(dimensions, bitWidth);
            long[] stats = bridge.stats();
            if (stats[1] != dimensions || stats[2] != bitWidth) {
                bridge.close();
                return new TurboVecVectorIndex(absolute, dimensions, bitWidth, null, VectorIndexHealth.State.REBUILD_REQUIRED,
                        "TurboVec index dimensions or bit width do not match the selected embedding configuration", 0L);
            }
            return new TurboVecVectorIndex(absolute, dimensions, bitWidth, bridge, VectorIndexHealth.State.UNAVAILABLE, "", stats[0]);
        } catch (IOException exception) {
            return new TurboVecVectorIndex(absolute, dimensions, bitWidth, null, VectorIndexHealth.State.UNAVAILABLE,
                    "Unable to prepare TurboVec index path: " + exception.getMessage(), 0L);
        } catch (RuntimeException exception) {
            VectorIndexHealth.State state = Files.exists(absolute) ? VectorIndexHealth.State.REBUILD_REQUIRED : VectorIndexHealth.State.UNAVAILABLE;
            return new TurboVecVectorIndex(absolute, dimensions, bitWidth, null, state,
                    "Unable to open TurboVec index: " + exception.getMessage(), 0L);
        }
    }

    @Override
    public synchronized void add(long id, float[] embedding) {
        requireReady();
        boolean replacing = this.bridge.contains(id);
        if (replacing) this.bridge.remove(id);
        this.bridge.add(id, embedding);
        if (!replacing) this.entryCount++;
    }

    @Override
    public synchronized boolean remove(long id) {
        requireReady();
        boolean removed = this.bridge.remove(id);
        if (removed) this.entryCount--;
        return removed;
    }

    @Override
    public synchronized List<VectorSearchResult> search(float[] query, VectorSearchRequest request) {
        requireReady();
        return this.bridge.search(query, request);
    }

    @Override
    public synchronized void sync() {
        requireReady();
        this.bridge.sync(this.path);
    }

    public synchronized void calibrate(float[] sample) {
        requireReady();
        this.bridge.calibrate(sample);
    }

    public synchronized String version() {
        requireReady();
        return this.bridge.version();
    }

    public int bitWidth() {
        return this.bitWidth;
    }

    @Override
    public synchronized VectorIndexHealth health() {
        if (this.closed) return new VectorIndexHealth(VectorIndexHealth.State.CLOSED, "turbovec", "closed", 0L, this.dimensions);
        if (this.bridge == null) return new VectorIndexHealth(this.unavailableState, "turbovec", this.detail, this.entryCount, this.dimensions);
        return VectorIndexHealth.ready("turbovec", this.entryCount, this.dimensions);
    }

    @Override
    public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        if (this.bridge != null) this.bridge.close();
    }

    private void requireReady() {
        if (this.closed) throw new IllegalStateException("TurboVec vector index is closed");
        if (this.bridge == null) throw new IllegalStateException(this.detail);
    }
}
