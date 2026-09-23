package com.spirit.koil.api.design.sprite.physics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small deterministic screen-space spatial hash partitioned by scene depth. */
public final class SpatialHash<T> {
    private record Key(int x, int y, int depth) { }

    private final Map<Key, List<T>> buckets = new LinkedHashMap<>();
    private float cellSize;

    public SpatialHash(float cellSize) { setCellSize(cellSize); }

    public void setCellSize(float value) {
        cellSize = Math.max(4.0F, Float.isFinite(value) ? value : 32.0F);
    }

    public float cellSize() { return cellSize; }
    public void clear() { buckets.clear(); }
    public int bucketCount() { return buckets.size(); }

    public void insert(T value, CollisionShape2D bounds, int depth) {
        if (value == null || bounds == null) return;
        int minX = bucket(bounds.left());
        int maxX = bucket(bounds.right() - 0.0001F);
        int minY = bucket(bounds.top());
        int maxY = bucket(bounds.bottom() - 0.0001F);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                buckets.computeIfAbsent(new Key(x, y, depth), ignored -> new ArrayList<>()).add(value);
            }
        }
    }

    public List<T> query(CollisionShape2D bounds, int depth) {
        if (bounds == null) return List.of();
        int minX = bucket(bounds.left());
        int maxX = bucket(bounds.right() - 0.0001F);
        int minY = bucket(bounds.top());
        int maxY = bucket(bounds.bottom() - 0.0001F);
        Set<T> result = new LinkedHashSet<>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                List<T> values = buckets.get(new Key(x, y, depth));
                if (values != null) result.addAll(values);
            }
        }
        return List.copyOf(result);
    }

    private int bucket(float coordinate) { return (int) Math.floor(coordinate / cellSize); }
}
