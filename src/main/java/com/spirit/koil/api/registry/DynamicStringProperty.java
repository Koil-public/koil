package com.spirit.koil.api.registry;

import net.minecraft.state.property.Property;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** String-backed Minecraft property for data-defined enum values. */
final class DynamicStringProperty extends Property<String> {
    private final List<String> values;
    private final java.util.Set<String> valueSet;

    DynamicStringProperty(String name, Collection<String> values) {
        super(name, String.class);
        LinkedHashSet<String> unique = new LinkedHashSet<>(values);
        this.values = List.copyOf(unique);
        valueSet = Set.copyOf(unique);
    }

    @Override
    public Collection<String> getValues() {
        return values;
    }

    @Override
    public String name(String value) {
        return value;
    }

    @Override
    public Optional<String> parse(String name) {
        return valueSet.contains(name) ? Optional.of(name) : Optional.empty();
    }

    @Override
    public boolean equals(Object object) {
        return this == object
                || object instanceof DynamicStringProperty other
                && super.equals(other)
                && values.equals(other.values);
    }

    @Override
    public int computeHashCode() {
        return 31 * super.computeHashCode() + values.hashCode();
    }
}
