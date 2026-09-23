package com.spirit.koil.api.automation.goal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded planner facts with explicit authority and lifetime. */
public final class AutomationFactGraph {
    private final Map<String, Fact> facts;

    private AutomationFactGraph(Map<String, Fact> facts) {
        this.facts = Map.copyOf(facts);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Fact fact(String key) {
        Fact value = facts.get(key);
        if (value == null) throw new IllegalArgumentException("unknown automation fact: " + key);
        return value;
    }

    public boolean has(String key) {
        return facts.containsKey(key);
    }

    public int inventoryCount(String itemId) {
        Fact fact = facts.get(inventoryKey(itemId));
        return fact != null && fact.value() instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    public Map<String, Fact> facts() {
        return facts;
    }

    public static String inventoryKey(String itemId) {
        return "inventory.item_count:" + itemId;
    }

    public enum Provenance {
        AUTHORITATIVE_RUNTIME,
        SERVER_SYNCHRONIZED,
        MOD_RESOURCE,
        OBSERVED_EXECUTION,
        CACHED_SUCCESS,
        INTERNET_DOCUMENTATION,
        MODEL_INFERENCE,
        UNVERIFIED
    }

    public record Fact(
            String key,
            Object value,
            Provenance provenance,
            String source,
            String sourceId,
            Instant capturedAt,
            Instant validUntil,
            String world,
            String dimension
    ) {
        public Fact {
            key = key == null ? "" : key;
            provenance = provenance == null ? Provenance.UNVERIFIED : provenance;
            source = source == null ? "" : source;
            sourceId = sourceId == null ? "" : sourceId;
            capturedAt = capturedAt == null ? Instant.now() : capturedAt;
            world = world == null ? "" : world;
            dimension = dimension == null ? "" : dimension;
        }

        public boolean authoritative() {
            return provenance == Provenance.AUTHORITATIVE_RUNTIME || provenance == Provenance.SERVER_SYNCHRONIZED;
        }

        public boolean validAt(Instant time) {
            return validUntil == null || !validUntil.isBefore(time == null ? Instant.now() : time);
        }
    }

    public static final class Builder {
        private final Map<String, Fact> facts = new LinkedHashMap<>();

        public Builder putInventoryCount(String itemId, int count, Provenance provenance) {
            return put(inventoryKey(itemId), Math.max(0, count), provenance, "player_inventory", itemId, "", "");
        }

        public Builder put(
                String key,
                Object value,
                Provenance provenance,
                String source,
                String sourceId,
                String world,
                String dimension
        ) {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("automation fact key is required");
            facts.put(key, new Fact(key, value, provenance, source, sourceId, Instant.now(), null, world, dimension));
            return this;
        }

        public AutomationFactGraph build() {
            return new AutomationFactGraph(facts);
        }
    }
}
