package com.spirit.koil.api.jmixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public record Expression(
    String op,
    JsonElement value,
    Expression left,
    Expression right,
    Expression min,
    Expression max
) {

    public static Expression parse(JsonElement element) {
        if (element == null || element.isJsonNull())
            return null;

        if (element.isJsonPrimitive())
            return new Expression(
                "constant",
                element,
                null,
                null,
                null,
                null
            );

        if (!element.isJsonObject())
            throw new IllegalArgumentException(
                "Invalid expression: " + element
            );

        JsonObject object = element.getAsJsonObject();

        String op = object.has("op")
            ? object.get("op").getAsString()
            : null;

        return new Expression(
            op,
            object.has("value")
                ? object.get("value")
                : null,
            parse(object.get("left")),
            parse(object.get("right")),
            parse(object.get("min")),
            parse(object.get("max"))
        );
    }

    public boolean isConstant() {
        return "constant".equals(op);
    }

    public boolean isOperation(String operation) {
        return operation.equals(op);
    }
}
