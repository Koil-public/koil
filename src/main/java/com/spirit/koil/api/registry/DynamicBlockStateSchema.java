package com.spirit.koil.api.registry;

import com.spirit.koil.api.registry.definition.ContentDefinition;
import com.spirit.koil.api.registry.definition.DynamicBlockStateDefinition;
import com.spirit.koil.api.registry.definition.DynamicStatePropertyDefinition;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Property;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minecraft 1.20.1 materialization of a validated dynamic blockstate descriptor. */
final class DynamicBlockStateSchema {
    private final DynamicBlockStateDefinition definition;
    private final List<Binding<?>> bindings;
    private final Map<String, Binding<?>> bindingsByName;

    private DynamicBlockStateSchema(
            DynamicBlockStateDefinition definition,
            List<Binding<?>> bindings
    ) {
        this.definition = definition;
        this.bindings = List.copyOf(bindings);
        LinkedHashMap<String, Binding<?>> byName = new LinkedHashMap<>();
        for (Binding<?> binding : bindings) {
            byName.put(binding.definition().name(), binding);
        }
        bindingsByName = Map.copyOf(byName);
    }

    static DynamicBlockStateSchema compile(ContentDefinition contentDefinition) {
        DynamicBlockStateDefinition definition = DynamicBlockStateDefinition.parse(contentDefinition);
        List<Binding<?>> bindings = new ArrayList<>();
        long stateCount = 1;
        for (DynamicStatePropertyDefinition property : definition.properties()) {
            Binding<?> binding = null;
            var extension = DynamicStatePropertyHandlers.materialize(property);
            if (extension.isPresent()) {
                binding = extensionBinding(property, extension.get());
            } else if (property.materializable() && property.allowedValues().size() >= 2) {
                binding = createBinding(property);
            }
            if (binding != null
                    && binding.property().getValues().size() > 1
                    && stateCount * binding.property().getValues().size()
                    <= DynamicBlockStateDefinition.MAX_MATERIALIZED_STATES) {
                bindings.add(binding);
                stateCount *= binding.property().getValues().size();
            }
        }
        return new DynamicBlockStateSchema(definition, bindings);
    }

    void appendTo(StateManager.Builder<Block, BlockState> builder) {
        for (Binding<?> binding : bindings) {
            builder.add(binding.property());
        }
    }

    BlockState applyDefaults(BlockState state) {
        BlockState result = state;
        for (Binding<?> binding : bindings) {
            result = applyDefault(result, binding);
        }
        return result;
    }

    BlockState set(BlockState state, String propertyName, String value) {
        Binding<?> binding = bindingsByName.get(propertyName);
        return binding == null ? state : setParsed(state, binding, value);
    }

    boolean has(String propertyName) {
        return bindingsByName.containsKey(propertyName);
    }

    BlockState cycle(BlockState state, String propertyName) {
        Binding<?> binding = bindingsByName.get(propertyName);
        return binding == null ? state : cycle(state, binding);
    }

    String value(BlockState state, String propertyName) {
        Binding<?> binding = bindingsByName.get(propertyName);
        return binding == null ? "" : value(state, binding);
    }

    DynamicBlockStateDefinition definition() {
        return definition;
    }

    int materializedStateCount() {
        int count = 1;
        for (Binding<?> binding : bindings) {
            count *= binding.property().getValues().size();
        }
        return count;
    }

    DirectionProperty directionProperty() {
        Binding<?> binding = bindingsByName.get("facing");
        return binding != null && binding.property() instanceof DirectionProperty property ? property : null;
    }

    @SuppressWarnings("unchecked")
    EnumProperty<Direction.Axis> axisProperty() {
        Binding<?> binding = bindingsByName.get("axis");
        if (binding != null && binding.property() instanceof EnumProperty<?> property
                && property.getType() == Direction.Axis.class) {
            return (EnumProperty<Direction.Axis>) property;
        }
        return null;
    }

    BooleanProperty booleanProperty(String name) {
        Binding<?> binding = bindingsByName.get(name);
        return binding != null && binding.property() instanceof BooleanProperty property ? property : null;
    }

    BlockState rotate(BlockState state, BlockRotation rotation) {
        DirectionProperty facing = directionProperty();
        BlockState result = state;
        if (facing != null) {
            Direction current = result.get(facing);
            Direction rotated = rotation.rotate(current);
            if (facing.getValues().contains(rotated)) {
                result = result.with(facing, rotated);
            }
        }
        EnumProperty<Direction.Axis> axis = axisProperty();
        if (axis != null && rotation != BlockRotation.NONE) {
            Direction.Axis current = result.get(axis);
            if (current != Direction.Axis.Y) {
                Direction direction = current == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
                Direction.Axis rotated = rotation.rotate(direction).getAxis();
                if (axis.getValues().contains(rotated)) {
                    result = result.with(axis, rotated);
                }
            }
        }
        return result;
    }

    BlockState mirror(BlockState state, BlockMirror mirror) {
        DirectionProperty facing = directionProperty();
        if (facing == null) {
            return state;
        }
        Direction mirrored = mirror.apply(state.get(facing));
        return facing.getValues().contains(mirrored) ? state.with(facing, mirrored) : state;
    }

    boolean matches(BlockState state, String selector) {
        if (selector == null || selector.isBlank()) {
            return true;
        }
        for (String condition : selector.split(",")) {
            String[] pair = condition.trim().split("=", 2);
            if (pair.length != 2
                    || !value(state, pair[0].trim().toLowerCase(Locale.ROOT))
                    .equals(pair[1].trim().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static Binding<?> createBinding(DynamicStatePropertyDefinition definition) {
        return switch (definition.type()) {
            case BOOLEAN -> new Binding<>(
                    definition,
                    canonicalBooleanProperty(definition.name()),
                    Boolean::valueOf
            );
            case INTEGER -> {
                List<Integer> values = definition.allowedValues().stream().map(Integer::valueOf).toList();
                yield new Binding<>(
                        definition,
                        new DynamicIntegerProperty(definition.name(), values),
                        Integer::valueOf
                );
            }
            case DIRECTION -> {
                List<Direction> values = definition.allowedValues().stream()
                        .map(Direction::byName)
                        .filter(java.util.Objects::nonNull)
                        .toList();
                DirectionProperty property = "facing".equals(definition.name())
                        && values.size() == 4
                        && values.stream().allMatch(direction -> direction.getAxis().isHorizontal())
                        ? Properties.HORIZONTAL_FACING
                        : "facing".equals(definition.name()) && values.size() == 6
                        ? Properties.FACING
                        : DirectionProperty.of(definition.name(), values);
                yield new Binding<>(definition, property, Direction::byName);
            }
            case AXIS -> {
                List<Direction.Axis> values = definition.allowedValues().stream()
                        .map(Direction.Axis::fromName)
                        .filter(java.util.Objects::nonNull)
                        .toList();
                EnumProperty<Direction.Axis> property = "axis".equals(definition.name())
                        && values.size() == 3
                        ? Properties.AXIS
                        : EnumProperty.of(definition.name(), Direction.Axis.class, values);
                yield new Binding<>(definition, property, Direction.Axis::fromName);
            }
            case ENUM -> new Binding<>(
                    definition,
                    new DynamicStringProperty(definition.name(), definition.allowedValues()),
                    value -> value
            );
            case CUSTOM -> null;
        };
    }

    private static BooleanProperty canonicalBooleanProperty(String name) {
        return switch (name) {
            case "waterlogged" -> Properties.WATERLOGGED;
            case "powered" -> Properties.POWERED;
            case "lit" -> Properties.LIT;
            default -> BooleanProperty.of(name);
        };
    }

    private static Binding<?> extensionBinding(
            DynamicStatePropertyDefinition definition,
            MaterializedStateProperty<?> materialized
    ) {
        if (!definition.name().equals(materialized.property().getName())
                || materialized.property().getValues().size() < 2) {
            return null;
        }
        return captureExtensionBinding(definition, materialized);
    }

    private static <T extends Comparable<T>> Binding<T> captureExtensionBinding(
            DynamicStatePropertyDefinition definition,
            MaterializedStateProperty<T> materialized
    ) {
        return new Binding<>(definition, materialized.property(), materialized.parser()::apply);
    }

    private static <T extends Comparable<T>> BlockState applyDefault(BlockState state, Binding<T> binding) {
        return setParsed(state, binding, binding.definition().defaultValue());
    }

    private static <T extends Comparable<T>> BlockState setParsed(
            BlockState state,
            Binding<T> binding,
            String rawValue
    ) {
        T value = binding.parser().parse(rawValue);
        return value != null && binding.property().getValues().contains(value)
                ? state.with(binding.property(), value)
                : state;
    }

    private static <T extends Comparable<T>> String value(BlockState state, Binding<T> binding) {
        return binding.property().name(state.get(binding.property()));
    }

    private static <T extends Comparable<T>> BlockState cycle(BlockState state, Binding<T> binding) {
        return state.cycle(binding.property());
    }

    private record Binding<T extends Comparable<T>>(
            DynamicStatePropertyDefinition definition,
            Property<T> property,
            ValueParser<T> parser
    ) {
    }

    @FunctionalInterface
    private interface ValueParser<T> {
        T parse(String value);
    }
}
