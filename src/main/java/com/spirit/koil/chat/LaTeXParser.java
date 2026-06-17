package com.spirit.koil.chat;

import com.spirit.koil.chat.node.MathNode;
import com.spirit.koil.chat.node.Node;
import com.spirit.koil.chat.node.TextNode;

import java.util.ArrayList;
import java.util.List;

public class LaTeXParser {
    public List<Node> parse(String input) {
        List<Node> nodes = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        boolean inInlineMath = false;
        boolean inBlockMath = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (!inInlineMath && i + 1 < input.length()
                    && input.charAt(i) == '$'
                    && input.charAt(i + 1) == '$') {

                if (buffer.length() > 0 && !inBlockMath) {
                    nodes.add(new TextNode(buffer.toString()));
                    buffer.setLength(0);
                }

                if (inBlockMath) {
                    nodes.add(new MathNode(buffer.toString(), true));
                    buffer.setLength(0);
                    inBlockMath = false;
                } else {
                    inBlockMath = true;
                }

                i++;
                continue;
            }

            if (!inBlockMath && c == '$') {
                if (inInlineMath) {
                    nodes.add(new MathNode(buffer.toString(), false));
                    buffer.setLength(0);
                    inInlineMath = false;
                } else {
                    if (buffer.length() > 0) {
                        nodes.add(new TextNode(buffer.toString()));
                        buffer.setLength(0);
                    }
                    inInlineMath = true;
                }

                continue;
            }

            buffer.append(c);
        }

        if (buffer.length() > 0) {
            if (inInlineMath) {
                nodes.add(new MathNode(buffer.toString(), false));
            } else if (inBlockMath) {
                nodes.add(new MathNode(buffer.toString(), true));
            } else {
                nodes.add(new TextNode(buffer.toString()));
            }
        }

        return nodes;
    }
}
