package com.spirit.koil.chat.node;

public class MathNode extends Node {

    public final String expression;
    public final boolean block;

    public MathNode(String expression, boolean block) {
        super(block ? "math_block" : "math_inline");
        this.expression = expression;
        this.block = block;
    }
}