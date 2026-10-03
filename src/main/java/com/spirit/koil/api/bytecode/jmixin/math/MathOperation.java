package com.spirit.koil.api.bytecode.jmixin.math;

import com.google.gson.JsonObject;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

public interface MathOperation {

    void generate(
        MethodVisitor visitor,
        Type type,
        JsonObject args
    );
}
