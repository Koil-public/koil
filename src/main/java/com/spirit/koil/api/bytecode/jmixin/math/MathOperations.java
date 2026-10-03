package com.spirit.koil.api.bytecode.jmixin.math;

import java.util.Map;
import java.util.Set;


public final class MathOperations {

    private static final Map<String, MathOperation> OPERATIONS = Map.ofEntries(
        Map.entry("add", StandardMathGenerators.basic("add")),
        Map.entry("subtract", StandardMathGenerators.basic("subtract")),
        Map.entry("multiply", StandardMathGenerators.basic("multiply")),
        Map.entry("divide", StandardMathGenerators.basic("divide")),
        Map.entry("mod", StandardMathGenerators.basic("mod")),
        Map.entry("pow", StandardMathGenerators.basic("pow")),
        Map.entry("min", StandardMathGenerators.basic("min")),
        Map.entry("max", StandardMathGenerators.basic("max")),
        Map.entry("clamp", StandardMathGenerators.basic("clamp")),
        Map.entry("abs", StandardMathGenerators.basic("abs")),
        Map.entry("negate", StandardMathGenerators.basic("negate")),
        Map.entry("sqrt", StandardMathGenerators.basic("sqrt")),
        Map.entry("floor", StandardMathGenerators.basic("floor")),
        Map.entry("ceil", StandardMathGenerators.basic("ceil")),
        Map.entry("round", StandardMathGenerators.basic("round")),
        Map.entry("and", StandardMathGenerators.basic("and")),
        Map.entry("or", StandardMathGenerators.basic("or")),
        Map.entry("xor", StandardMathGenerators.basic("xor")),
        Map.entry("not", StandardMathGenerators.basic("not")),
        Map.entry("shift_left", StandardMathGenerators.basic("shift_left")),
        Map.entry("shift_right", StandardMathGenerators.basic("shift_right")),
        Map.entry("unsigned_shift_right", StandardMathGenerators.basic("unsigned_shift_right")),
        Map.entry("sin", StandardMathGenerators.unaryDouble("sin")),
        Map.entry("cos", StandardMathGenerators.unaryDouble("cos")),
        Map.entry("tan", StandardMathGenerators.unaryDouble("tan")),
        Map.entry("asin", StandardMathGenerators.unaryDouble("asin")),
        Map.entry("acos", StandardMathGenerators.unaryDouble("acos")),
        Map.entry("atan", StandardMathGenerators.unaryDouble("atan")),
        Map.entry("atan2", StandardMathGenerators.binaryDouble("atan2")),
        Map.entry("sinh", StandardMathGenerators.unaryDouble("sinh")),
        Map.entry("cosh", StandardMathGenerators.unaryDouble("cosh")),
        Map.entry("tanh", StandardMathGenerators.unaryDouble("tanh")),
        Map.entry("exp", StandardMathGenerators.unaryDouble("exp")),
        Map.entry("expm1", StandardMathGenerators.unaryDouble("expm1")),
        Map.entry("log", StandardMathGenerators.unaryDouble("log")),
        Map.entry("log10", StandardMathGenerators.unaryDouble("log10")),
        Map.entry("log1p", StandardMathGenerators.unaryDouble("log1p")),
        Map.entry("cbrt", StandardMathGenerators.unaryDouble("cbrt")),
        Map.entry("hypot", StandardMathGenerators.binaryDouble("hypot")),
        Map.entry("ieee_remainder", StandardMathGenerators.binaryDouble("IEEEremainder")),
        Map.entry("to_degrees", StandardMathGenerators.unaryDouble("toDegrees")),
        Map.entry("to_radians", StandardMathGenerators.unaryDouble("toRadians")),
        Map.entry("rint", StandardMathGenerators.unaryDouble("rint")),
        Map.entry("signum", StandardMathGenerators.unaryDouble("signum")),
        Map.entry("copy_sign", StandardMathGenerators.binaryFloating("copySign")),
        Map.entry("next_after", StandardMathGenerators.nextAfter()),
        Map.entry("next_up", StandardMathGenerators.unaryFloating("nextUp")),
        Map.entry("next_down", StandardMathGenerators.unaryFloating("nextDown")),
        Map.entry("ulp", StandardMathGenerators.unaryFloating("ulp")),
        Map.entry("scalb", StandardMathGenerators.scalb()),
        Map.entry("fma", StandardMathGenerators.fma()),
        Map.entry("add_exact", StandardMathGenerators.exactBinary("addExact")),
        Map.entry("subtract_exact", StandardMathGenerators.exactBinary("subtractExact")),
        Map.entry("multiply_exact", StandardMathGenerators.exactBinary("multiplyExact")),
        Map.entry("increment_exact", StandardMathGenerators.exactUnary("incrementExact")),
        Map.entry("decrement_exact", StandardMathGenerators.exactUnary("decrementExact")),
        Map.entry("negate_exact", StandardMathGenerators.exactUnary("negateExact")),
        Map.entry("floor_div", StandardMathGenerators.integralBinary("floorDiv")),
        Map.entry("floor_mod", StandardMathGenerators.integralBinary("floorMod")),
        Map.entry("rotate_left", StandardMathGenerators.integralBits("rotateLeft", true)),
        Map.entry("rotate_right", StandardMathGenerators.integralBits("rotateRight", true)),
        Map.entry("reverse", StandardMathGenerators.integralBits("reverse", false)),
        Map.entry("reverse_bytes", StandardMathGenerators.integralBits("reverseBytes", false)),
        Map.entry("highest_one_bit", StandardMathGenerators.integralBits("highestOneBit", false)),
        Map.entry("lowest_one_bit", StandardMathGenerators.integralBits("lowestOneBit", false))
    );

    private MathOperations() {
    }

    public static MathOperation get(String name) {
        MathOperation operation = OPERATIONS.get(name);
        if (operation == null) {
            throw new IllegalArgumentException("Unknown math operation: " + name);
        }
        return operation;
    }

    public static Set<String> names() {
        return OPERATIONS.keySet();
    }
}
