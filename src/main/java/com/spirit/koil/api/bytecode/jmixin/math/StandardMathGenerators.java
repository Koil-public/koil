package com.spirit.koil.api.bytecode.jmixin.math;

import com.google.gson.JsonObject;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

final class StandardMathGenerators {

    private StandardMathGenerators() {
    }

    static MathOperation unaryDouble(String method) {
        return new UnaryDouble(method);
    }

    static MathOperation basic(String operation) {
        return new Basic(operation);
    }

    static MathOperation binaryDouble(String method) {
        return new BinaryDouble(method);
    }

    static MathOperation unaryFloating(String method) {
        return new UnaryFloating(method);
    }

    static MathOperation binaryFloating(String method) {
        return new BinaryFloating(method);
    }

    static MathOperation nextAfter() {
        return new NextAfter();
    }

    static MathOperation scalb() {
        return new Scalb();
    }

    static MathOperation fma() {
        return new Fma();
    }

    static MathOperation exactBinary(String method) {
        return new ExactBinary(method);
    }

    static MathOperation exactUnary(String method) {
        return new ExactUnary(method);
    }

    static MathOperation integralBinary(String method) {
        return new IntegralBinary(method);
    }

    static MathOperation integralBits(String method, boolean hasDistance) {
        return new IntegralBits(method, hasDistance);
    }

    private record UnaryDouble(String method) implements MathOperation {

        @Override
        public void generate(MethodVisitor visitor, Type type, JsonObject args) {
            MathSupport.unaryDouble(visitor, type, method);
        }
    }

    private record Basic(String operation) implements MathOperation {

        @Override
        public void generate(MethodVisitor visitor, Type type, JsonObject args) {
            switch (operation) {
                case "add" -> MathSupport.arithmetic(visitor, type,
                    org.objectweb.asm.Opcodes.IADD, org.objectweb.asm.Opcodes.LADD,
                    org.objectweb.asm.Opcodes.FADD, org.objectweb.asm.Opcodes.DADD);
                case "subtract" -> MathSupport.arithmetic(visitor, type,
                    org.objectweb.asm.Opcodes.ISUB, org.objectweb.asm.Opcodes.LSUB,
                    org.objectweb.asm.Opcodes.FSUB, org.objectweb.asm.Opcodes.DSUB);
                case "multiply" -> MathSupport.arithmetic(visitor, type,
                    org.objectweb.asm.Opcodes.IMUL, org.objectweb.asm.Opcodes.LMUL,
                    org.objectweb.asm.Opcodes.FMUL, org.objectweb.asm.Opcodes.DMUL);
                case "divide" -> MathSupport.arithmetic(visitor, type,
                    org.objectweb.asm.Opcodes.IDIV, org.objectweb.asm.Opcodes.LDIV,
                    org.objectweb.asm.Opcodes.FDIV, org.objectweb.asm.Opcodes.DDIV);
                case "mod" -> MathSupport.arithmetic(visitor, type,
                    org.objectweb.asm.Opcodes.IREM, org.objectweb.asm.Opcodes.LREM,
                    org.objectweb.asm.Opcodes.FREM, org.objectweb.asm.Opcodes.DREM);
                case "pow" -> MathSupport.pow(visitor, type);
                case "min" -> MathSupport.min(visitor, type);
                case "max" -> MathSupport.max(visitor, type);
                case "clamp" -> MathSupport.clamp(visitor, type);
                case "abs" -> MathSupport.abs(visitor, type);
                case "negate" -> MathSupport.negate(visitor, type);
                case "sqrt" -> MathSupport.sqrt(visitor, type);
                case "floor" -> MathSupport.floor(visitor, type);
                case "ceil" -> MathSupport.ceil(visitor, type);
                case "round" -> MathSupport.round(visitor, type);
                case "and" -> MathSupport.bitwise(visitor, type,
                    org.objectweb.asm.Opcodes.IAND, org.objectweb.asm.Opcodes.LAND);
                case "or" -> MathSupport.bitwise(visitor, type,
                    org.objectweb.asm.Opcodes.IOR, org.objectweb.asm.Opcodes.LOR);
                case "xor" -> MathSupport.bitwise(visitor, type,
                    org.objectweb.asm.Opcodes.IXOR, org.objectweb.asm.Opcodes.LXOR);
                case "not" -> MathSupport.not(visitor, type);
                case "shift_left" -> MathSupport.shift(visitor, type,
                    org.objectweb.asm.Opcodes.ISHL, org.objectweb.asm.Opcodes.LSHL);
                case "shift_right" -> MathSupport.shift(visitor, type,
                    org.objectweb.asm.Opcodes.ISHR, org.objectweb.asm.Opcodes.LSHR);
                case "unsigned_shift_right" -> MathSupport.shift(visitor, type,
                    org.objectweb.asm.Opcodes.IUSHR, org.objectweb.asm.Opcodes.LUSHR);
                default -> throw new IllegalArgumentException("Unknown basic math operation: " + operation);
            }
        }
    }

    private record BinaryDouble(String method) implements MathOperation {

        @Override
        public void generate(MethodVisitor visitor, Type type, JsonObject args) {
            MathSupport.binaryDouble(visitor, type, method);
        }
    }

    private record UnaryFloating(String method) implements MathOperation {

        @Override
        public void generate(MethodVisitor visitor, Type type, JsonObject args) {
            MathSupport.unaryFloating(visitor, type, method);
        }
    }

    private record BinaryFloating(String method) implements MathOperation {

        @Override
        public void generate(MethodVisitor visitor, Type type, JsonObject args) {
            MathSupport.binaryFloating(visitor, type, method);
        }
    }

    private static final class NextAfter extends MathGenerator {

        @Override
        public void generate(MethodVisitor visitor, Type type, JsonObject args) {
            MathSupport.nextAfter(visitor, type);
        }
    }

    private static final class Scalb extends MathGenerator {

        @Override
        public void generate(MethodVisitor visitor, Type type, JsonObject args) {
            MathSupport.scalb(visitor, type);
        }
    }

    private static final class Fma extends MathGenerator {

        @Override
        public void generate(MethodVisitor visitor, Type type, JsonObject args) {
            MathSupport.fma(visitor, type);
        }
    }

    private record ExactBinary(String method) implements MathOperation {

        @Override
        public void generate(MethodVisitor visitor, Type type, JsonObject args) {
            MathSupport.exactBinary(visitor, type, method);
        }
    }

    private record ExactUnary(String method) implements MathOperation {

        @Override
        public void generate(MethodVisitor visitor, Type type, JsonObject args) {
            MathSupport.exactUnary(visitor, type, method);
        }
    }

    private record IntegralBinary(String method) implements MathOperation {

        @Override
        public void generate(MethodVisitor visitor, Type type, JsonObject args) {
            MathSupport.integralBinary(visitor, type, method);
        }
    }

    private record IntegralBits(String method, boolean hasDistance) implements MathOperation {

        @Override
        public void generate(MethodVisitor visitor, Type type, JsonObject args) {
            MathSupport.integralBits(visitor, type, method, hasDistance);
        }
    }
}
