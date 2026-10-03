package com.spirit.koil.api.bytecode;

import com.google.gson.JsonElement;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

public final class BytecodeUtil {

    private BytecodeUtil() {
    }

    public static void emitConstant(
        MethodVisitor visitor,
        Type type,
        JsonElement value
    ) {
        switch (type.getSort()) {
            case Type.INT,
                 Type.BOOLEAN,
                 Type.BYTE,
                 Type.CHAR,
                 Type.SHORT -> {
                visitor.visitLdcInsn(value.getAsInt());
            }

            case Type.LONG -> {
                visitor.visitLdcInsn(value.getAsLong());
            }

            case Type.FLOAT -> {
                visitor.visitLdcInsn(value.getAsFloat());
            }

            case Type.DOUBLE -> {
                visitor.visitLdcInsn(value.getAsDouble());
            }

            case Type.OBJECT -> {
                if (type.equals(Type.getObjectType("java/lang/String"))) {
                    visitor.visitLdcInsn(value.getAsString());
                } else {
                    throw new IllegalArgumentException(
                        "Cannot create constant for " + type
                    );
                }
            }

            default -> throw new IllegalArgumentException(
                "Cannot create constant for " + type
            );
        }
    }
}
