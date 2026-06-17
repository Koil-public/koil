package com.spirit.koil.chat.node;

public abstract class Node {
    public final String type;

    protected Node(String type) {
        this.type = type;
    }
}