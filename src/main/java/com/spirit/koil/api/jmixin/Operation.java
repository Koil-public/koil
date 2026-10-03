package com.spirit.koil.api.jmixin;

import com.google.gson.JsonObject;

import java.util.List;

public record Operation(
    String type,
    Target target,
    Point point,
    JsonObject args
) {

    public Expression expression(String key) {
        if (!args.has(key))
            throw new IllegalArgumentException(
                "Missing argument: " + key
            );

        return Expression.parse(args.get(key));
    }

    public List<Expression> expressions() {
        if (!args.has("operations"))
            return List.of();

        return args.getAsJsonArray("operations")
            .asList()
            .stream()
            .map(Expression::parse)
            .toList();
    }
}
