package com.spirit.koil.api.model.catalog;

import java.util.List;

/** Inert tensor-header evidence. Payload bytes are never loaded by artifact inspection. */
public record ModelTensorDescriptor(
        String name,
        List<Long> shape,
        String storageType,
        long dataOffset,
        long storageBytes,
        String shard
) {
    public ModelTensorDescriptor {
        name = name == null ? "" : name.strip();
        shape = shape == null ? List.of() : List.copyOf(shape);
        storageType = storageType == null || storageType.isBlank() ? "UNKNOWN" : storageType.strip();
        dataOffset = Math.max(0L, dataOffset);
        storageBytes = Math.max(0L, storageBytes);
        shard = shard == null ? "" : shard.strip();
    }

    public long parameterCount() {
        if (shape.isEmpty()) return 0L;
        long product = 1L;
        for (long extent : shape) {
            if (extent < 0) return 0L;
            if (extent == 0) return 0L;
            if (product > Long.MAX_VALUE / extent) return Long.MAX_VALUE;
            product *= extent;
        }
        return product;
    }
}
