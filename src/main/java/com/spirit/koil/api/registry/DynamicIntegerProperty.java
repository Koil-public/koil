package com.spirit.koil.api.registry;

import net.minecraft.state.property.Property;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Exact-value integer property supporting sparse data-defined integer sets. */
final class DynamicIntegerProperty extends Property<Integer> {
    private final List<Integer> values;
    private final Set<Integer> valueSet;

    DynamicIntegerProperty(String name, Collection<Integer> values) {
        super(name, Integer.class);
        LinkedHashSet<Integer> unique = new LinkedHashSet<>(values);
        this.values = List.copyOf(unique);
        valueSet = Set.copyOf(unique);
    }

    @Override
    public Collection<Integer> getValues() {
        return values;
    }

    @Override
    public String name(Integer value) {
        return Integer.toString(value);
    }

    @Override
    public Optional<Integer> parse(String name) {
        try {
            int value = Integer.parseInt(name);
            return valueSet.contains(value) ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public boolean equals(Object object) {
        return this == object
                || object instanceof DynamicIntegerProperty other
                && super.equals(other)
                && values.equals(other.values);
    }

    @Override
    public int computeHashCode() {
        return 31 * super.computeHashCode() + values.hashCode();
    }
}
