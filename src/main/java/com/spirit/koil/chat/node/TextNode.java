package com.spirit.koil.chat.node;

public class TextNode extends Node {

    public final String text;

    public TextNode(String text) {
        super("text");
        this.text = text;
    }
}