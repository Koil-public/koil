package com.spirit.koil.api.automation.navigation;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, Minecraft-object-free terrain view captured on the client thread. */
public record BoundedNavigationSnapshot(
        Map<Node, Cell> cells,
        List<Threat> threats,
        String dimension,
        long observationVersion,
        long fingerprint
) {
    public BoundedNavigationSnapshot {
        cells = Map.copyOf(cells == null ? Map.of() : cells);
        threats = List.copyOf(threats == null ? List.of() : threats);
        dimension = dimension == null ? "" : dimension;
    }

    public static BoundedNavigationSnapshot of(Map<Node, Cell> cells, List<Threat> threats,
                                                String dimension, long observationVersion) {
        Map<Node, Cell> safeCells = new LinkedHashMap<>(cells == null ? Map.of() : cells);
        List<Threat> safeThreats = List.copyOf(threats == null ? List.of() : threats);
        long hash = 0xcbf29ce484222325L;
        for (Map.Entry<Node, Cell> entry : safeCells.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Node::x)
                        .thenComparingInt(Node::y).thenComparingInt(Node::z))).toList()) {
            hash = mix(hash, entry.getKey().hashCode());
            hash = mix(hash, entry.getValue().hashCode());
        }
        for (Threat threat : safeThreats.stream().sorted(Comparator.comparing(Threat::id)).toList()) {
            hash = mix(hash, threat.hashCode());
        }
        hash = mix(hash, dimension == null ? 0 : dimension.hashCode());
        return new BoundedNavigationSnapshot(safeCells, safeThreats, dimension, observationVersion, hash);
    }

    private static long mix(long current, long value) {
        current ^= value;
        return current * 0x100000001b3L;
    }

    public Cell cell(Node node) {
        return cells.getOrDefault(node, Cell.BLOCKED);
    }

    public record Node(int x, int y, int z) {
        public double distanceTo(Node other) {
            int dx = x - other.x;
            int dy = y - other.y;
            int dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public record Cell(boolean bodyClear, boolean supported, boolean water, boolean climbable,
                       boolean interactable, boolean dangerous, boolean crouched) {
        public static final Cell BLOCKED = new Cell(false, false, false, false, false, false, false);

        public boolean occupiable(boolean allowInteraction) {
            return !dangerous && (bodyClear || allowInteraction && interactable)
                    && (supported || water || climbable);
        }
    }

    public record Threat(String id, Node position, Kind kind, boolean lineOfSight) {
        public Threat {
            id = id == null ? "" : id;
            position = position == null ? new Node(0, 0, 0) : position;
            kind = kind == null ? Kind.PASSIVE : kind;
        }
    }

    public enum Kind {
        HOSTILE,
        DANGEROUS,
        PASSIVE
    }
}
