package com.spirit.koil.api.bytecode.jmixin;

import com.spirit.koil.api.bytecode.BytecodeUtil;
import com.spirit.koil.api.bytecode.jmixin.math.MathOperations;
import com.spirit.koil.api.jmixin.Expression;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

final class JMixinExpressionEmitter {
    private JMixinExpressionEmitter() { }

    static void emit(MethodVisitor visitor, Type type, Expression expression, int inputLocal) {
        if (expression == null) throw new IllegalArgumentException("Missing expression");
        if (expression.isConstant()) {
            BytecodeUtil.emitConstant(visitor, type, expression.value());
            return;
        }
        if ("input".equals(expression.op())) {
            visitor.visitVarInsn(loadOpcode(type), inputLocal);
            return;
        }
        if ("clamp".equals(expression.op())) {
            emit(visitor, type, expression.left(), inputLocal);
            emit(visitor, type, expression.min(), inputLocal);
            emit(visitor, type, expression.max(), inputLocal);
        } else if (isUnary(expression.op())) {
            emit(visitor, type, expression.left(), inputLocal);
        } else {
            emit(visitor, type, expression.left(), inputLocal);
            emit(visitor, type, expression.right(), inputLocal);
        }
        MathOperations.get(expression.op()).generate(visitor, type, null);
    }

    private static boolean isUnary(String operation) {
        return switch (operation) {
            case "abs", "negate", "sqrt", "floor", "ceil", "round", "not",
                 "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh",
                 "exp", "expm1", "log", "log10", "log1p", "cbrt", "to_degrees",
                 "to_radians", "rint", "signum", "next_up", "next_down", "ulp",
                 "increment_exact", "decrement_exact", "negate_exact", "reverse",
                 "reverse_bytes", "highest_one_bit", "lowest_one_bit" -> true;
            default -> false;
        };
    }

    private static int loadOpcode(Type type) {
        return switch (type.getSort()) {
            case Type.LONG -> Opcodes.LLOAD;
            case Type.FLOAT -> Opcodes.FLOAD;
            case Type.DOUBLE -> Opcodes.DLOAD;
            case Type.OBJECT, Type.ARRAY -> Opcodes.ALOAD;
            default -> Opcodes.ILOAD;
        };
    }
}
