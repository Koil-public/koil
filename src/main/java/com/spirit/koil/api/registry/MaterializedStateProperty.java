package com.spirit.koil.api.registry;

import net.minecraft.state.property.Property;

import java.util.function.Function;

/** Physical property plus JSON-token parser returned by a custom state handler. */
public record MaterializedStateProperty<T extends Comparable<T>>(
        Property<T> property,
        Function<String, T> parser
) {
    public MaterializedStateProperty {
        if (property == null || parser == null) {
            throw new IllegalArgumentException("Materialized state property requires property and parser");
        }
    }
}
