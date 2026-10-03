package com.spirit.koil.api.bytecode.jmixin.math;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

final class MathSupport {

    private static final String MATH = "java/lang/Math";

    private MathSupport() {
    }

    static void arithmetic(MethodVisitor visitor, Type type, int intOpcode, int longOpcode, int floatOpcode, int doubleOpcode) {
        requireNumeric(type);
        visitor.visitInsn(opcode(type, intOpcode, longOpcode, floatOpcode, doubleOpcode));
    }

    static void min(MethodVisitor visitor, Type type) {
        invokeMath(visitor, "min", type, type, type);
    }

    static void max(MethodVisitor visitor, Type type) {
        invokeMath(visitor, "max", type, type, type);
    }

    static void clamp(MethodVisitor visitor, Type type) {
        if (isCategory2(type)) {
            visitor.visitInsn(Opcodes.DUP2_X2);
            visitor.visitInsn(Opcodes.POP2);
        } else {
            visitor.visitInsn(Opcodes.DUP_X2);
            visitor.visitInsn(Opcodes.POP);
        }
        max(visitor, type);
        if (isCategory2(type)) {
            visitor.visitInsn(Opcodes.DUP2_X2);
            visitor.visitInsn(Opcodes.POP2);
        } else {
            visitor.visitInsn(Opcodes.SWAP);
        }
        min(visitor, type);
    }

    static void abs(MethodVisitor visitor, Type type) {
        invokeMath(visitor, "abs", type, type);
    }

    static void negate(MethodVisitor visitor, Type type) {
        requireNumeric(type);
        visitor.visitInsn(opcode(type, Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG));
    }

    static void sqrt(MethodVisitor visitor, Type type) {
        requireNumeric(type);
        toDouble(visitor, type);
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, MATH, "sqrt", "(D)D", false);
        fromDouble(visitor, type);
    }

    static void floor(MethodVisitor visitor, Type type) {
        requireNumeric(type);
        if (isIntegral(type)) {
            return;
        }
        toDouble(visitor, type);
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, MATH, "floor", "(D)D", false);
        fromDouble(visitor, type);
    }

    static void ceil(MethodVisitor visitor, Type type) {
        requireNumeric(type);
        if (isIntegral(type)) {
            return;
        }
        toDouble(visitor, type);
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, MATH, "ceil", "(D)D", false);
        fromDouble(visitor, type);
    }

    static void round(MethodVisitor visitor, Type type) {
        requireNumeric(type);
        switch (type.getSort()) {
            case Type.FLOAT -> {
                visitor.visitMethodInsn(Opcodes.INVOKESTATIC, MATH, "round", "(F)I", false);
                visitor.visitInsn(Opcodes.I2F);
            }
            case Type.DOUBLE -> {
                visitor.visitMethodInsn(Opcodes.INVOKESTATIC, MATH, "round", "(D)J", false);
                visitor.visitInsn(Opcodes.L2D);
            }
            default -> requireIntegral(type);
        }
    }

    static void pow(MethodVisitor visitor, Type type) {
        binaryDouble(visitor, type, "pow");
    }

    static void unaryDouble(MethodVisitor visitor, Type type, String method) {
        requireNumeric(type);
        toDouble(visitor, type);
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, MATH, method, "(D)D", false);
        fromDouble(visitor, type);
    }

    static void binaryDouble(MethodVisitor visitor, Type type, String method) {
        requireNumeric(type);

        if (isCategory2(type)) {
            toDouble(visitor, type);
            visitor.visitInsn(Opcodes.DUP2_X2);
            visitor.visitInsn(Opcodes.POP2);
            toDouble(visitor, type);
            visitor.visitInsn(Opcodes.DUP2_X2);
            visitor.visitInsn(Opcodes.POP2);
        } else {
            toDouble(visitor, type);
            visitor.visitInsn(Opcodes.DUP2_X1);
            visitor.visitInsn(Opcodes.POP2);
            toDouble(visitor, type);
            visitor.visitInsn(Opcodes.DUP2_X2);
            visitor.visitInsn(Opcodes.POP2);
        }

        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, MATH, method, "(DD)D", false);
        fromDouble(visitor, type);
    }

    static void unaryFloating(MethodVisitor visitor, Type type, String method) {
        requireFloating(type);
        String descriptor = type.getSort() == Type.FLOAT ? "(F)F" : "(D)D";
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, MATH, method, descriptor, false);
    }

    static void binaryFloating(MethodVisitor visitor, Type type, String method) {
        requireFloating(type);
        String descriptor = type.getSort() == Type.FLOAT ? "(FF)F" : "(DD)D";
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, MATH, method, descriptor, false);
    }

    static void nextAfter(MethodVisitor visitor, Type type) {
        requireFloating(type);
        if (type.getSort() == Type.FLOAT) {
            visitor.visitInsn(Opcodes.F2D);
            visitor.visitMethodInsn(Opcodes.INVOKESTATIC, MATH, "nextAfter", "(FD)F", false);
            return;
        }
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, MATH, "nextAfter", "(DD)D", false);
    }

    static void scalb(MethodVisitor visitor, Type type) {
        requireFloating(type);
        String descriptor = type.getSort() == Type.FLOAT ? "(FI)F" : "(DI)D";
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, MATH, "scalb", descriptor, false);
    }

    static void fma(MethodVisitor visitor, Type type) {
        requireFloating(type);
        String descriptor = type.getSort() == Type.FLOAT ? "(FFF)F" : "(DDD)D";
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, MATH, "fma", descriptor, false);
    }

    static void exactBinary(MethodVisitor visitor, Type type, String method) {
        invokeIntegralMath(visitor, type, method, true);
    }

    static void exactUnary(MethodVisitor visitor, Type type, String method) {
        invokeIntegralMath(visitor, type, method, false);
    }

    static void integralBinary(MethodVisitor visitor, Type type, String method) {
        invokeIntegralMath(visitor, type, method, true);
    }

    static void integralBits(MethodVisitor visitor, Type type, String method, boolean hasDistance) {
        String owner = integralOwner(type);
        String descriptor = hasDistance
            ? (type.getSort() == Type.LONG ? "(JI)J" : "(II)I")
            : (type.getSort() == Type.LONG ? "(J)J" : "(I)I");
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, owner, method, descriptor, false);
    }

    static void not(MethodVisitor visitor, Type type) {
        if (type.getSort() == Type.LONG) {
            visitor.visitLdcInsn(-1L);
            visitor.visitInsn(Opcodes.LXOR);
            return;
        }
        requireIntLike(type);
        visitor.visitInsn(Opcodes.ICONST_M1);
        visitor.visitInsn(Opcodes.IXOR);
    }

    static void bitwise(MethodVisitor visitor, Type type, int intOpcode, int longOpcode) {
        if (type.getSort() == Type.LONG) {
            visitor.visitInsn(longOpcode);
            return;
        }
        requireIntLike(type);
        visitor.visitInsn(intOpcode);
    }

    static void shift(MethodVisitor visitor, Type type, int intOpcode, int longOpcode) {
        if (type.getSort() == Type.LONG) {
            visitor.visitInsn(longOpcode);
            return;
        }
        requireIntLike(type);
        visitor.visitInsn(intOpcode);
    }

    private static void invokeMath(MethodVisitor visitor, String name, Type result, Type... parameters) {
        requireNumeric(result);
        StringBuilder descriptor = new StringBuilder("(");
        for (Type parameter : parameters) {
            requireSameNumericKind(result, parameter);
            descriptor.append(mathType(parameter).getDescriptor());
        }
        descriptor.append(')').append(mathType(result).getDescriptor());
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, MATH, name, descriptor.toString(), false);
    }

    private static Type mathType(Type type) {
        return switch (type.getSort()) {
            case Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Type.INT_TYPE;
            case Type.LONG -> Type.LONG_TYPE;
            case Type.FLOAT -> Type.FLOAT_TYPE;
            case Type.DOUBLE -> Type.DOUBLE_TYPE;
            default -> throw unsupported(type);
        };
    }

    private static int opcode(Type type, int intOpcode, int longOpcode, int floatOpcode, int doubleOpcode) {
        return switch (type.getSort()) {
            case Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> intOpcode;
            case Type.LONG -> longOpcode;
            case Type.FLOAT -> floatOpcode;
            case Type.DOUBLE -> doubleOpcode;
            default -> throw unsupported(type);
        };
    }

    private static void toDouble(MethodVisitor visitor, Type type) {
        switch (type.getSort()) {
            case Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> visitor.visitInsn(Opcodes.I2D);
            case Type.LONG -> visitor.visitInsn(Opcodes.L2D);
            case Type.FLOAT -> visitor.visitInsn(Opcodes.F2D);
            case Type.DOUBLE -> {}
            default -> throw unsupported(type);
        }
    }

    private static void fromDouble(MethodVisitor visitor, Type type) {
        switch (type.getSort()) {
            case Type.BYTE -> {
                visitor.visitInsn(Opcodes.D2I);
                visitor.visitInsn(Opcodes.I2B);
            }
            case Type.CHAR -> {
                visitor.visitInsn(Opcodes.D2I);
                visitor.visitInsn(Opcodes.I2C);
            }
            case Type.SHORT -> {
                visitor.visitInsn(Opcodes.D2I);
                visitor.visitInsn(Opcodes.I2S);
            }
            case Type.INT -> visitor.visitInsn(Opcodes.D2I);
            case Type.LONG -> visitor.visitInsn(Opcodes.D2L);
            case Type.FLOAT -> visitor.visitInsn(Opcodes.D2F);
            case Type.DOUBLE -> {}
            default -> throw unsupported(type);
        }
    }

    private static boolean isCategory2(Type type) {
        return type.getSort() == Type.LONG || type.getSort() == Type.DOUBLE;
    }

    private static boolean isIntegral(Type type) {
        return switch (type.getSort()) {
            case Type.BYTE, Type.CHAR, Type.SHORT, Type.INT, Type.LONG -> true;
            default -> false;
        };
    }

    private static void requireIntegral(Type type) {
        if (!isIntegral(type)) {
            throw unsupported(type);
        }
    }

    private static void requireIntLike(Type type) {
        switch (type.getSort()) {
            case Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {}
            default ->
                throw new IllegalArgumentException("Bitwise operation requires an int-like or long type, got " + type);
        }
    }

    private static void invokeIntegralMath(MethodVisitor visitor, Type type, String method, boolean binary) {
        String descriptor;
        if (type.getSort() == Type.LONG) {
            descriptor = binary ? "(JJ)J" : "(J)J";
        } else {
            requireIntLike(type);
            descriptor = binary ? "(II)I" : "(I)I";
        }
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, MATH, method, descriptor, false);
    }

    private static String integralOwner(Type type) {
        return switch (type.getSort()) {
            case Type.LONG -> "java/lang/Long";
            case Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> "java/lang/Integer";
            default ->
                throw new IllegalArgumentException("Integer bit operation requires an int-like or long type, got " + type);
        };
    }

    private static void requireFloating(Type type) {
        if (type.getSort() != Type.FLOAT && type.getSort() != Type.DOUBLE) {
            throw new IllegalArgumentException("Floating-point operation requires float or double, got " + type);
        }
    }

    private static void requireNumeric(Type type) {
        switch (type.getSort()) {
            case Type.BYTE, Type.CHAR, Type.SHORT, Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE -> {}
            default -> throw unsupported(type);
        }
    }

    private static void requireSameNumericKind(Type result, Type parameter) {
        if (!mathType(result).equals(mathType(parameter))) {
            throw new IllegalArgumentException("All math operands must have the same JVM type");
        }
    }

    private static IllegalArgumentException unsupported(Type type) {
        return new IllegalArgumentException("Unsupported math type: " + type);
    }
}
