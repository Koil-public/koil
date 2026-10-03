package com.spirit.koil.api.jmixin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public final class BytecodeJsonParser {

    private static final Gson GSON = new GsonBuilder().create();

    private static final Set<String> TARGET_KINDS = Set.of(
        "field",
        "method",
        "constructor",
        "instruction"
    );

    private static final Set<String> CONSTANT_TYPES = Set.of(
        "byte",
        "short",
        "int",
        "long",
        "float",
        "double",
        "char",
        "boolean",
        "string"
    );

    private BytecodeJsonParser() {}

    public static ClassDefinition parse(Path path)
        throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            return parse(reader);
        }
    }

    public static ClassDefinition parse(String json) {
        ClassDefinition definition =
            GSON.fromJson(json, ClassDefinition.class);

        validate(definition);
        return definition;
    }

    public static ClassDefinition parse(Reader reader) {
        ClassDefinition definition =
            GSON.fromJson(reader, ClassDefinition.class);

        validate(definition);
        return definition;
    }

    private static void validate(ClassDefinition definition) {
        if (definition == null)
            throw error("Definition is empty");

        require(definition.id(), "id");
        require(definition.target(), "target");

        if (definition.operations() == null)
            throw error("Missing operations");

        for (int i = 0; i < definition.operations().size(); i++) {
            Operation operation = definition.operations().get(i);
            String path = "operations[" + i + "]";

            if (operation == null)
                throw error(path + " is null");

            require(operation.type(), path + ".type");

            if (operation.target() == null)
                throw error("Missing " + path + ".target");

            validateTarget(operation.target(), path + ".target");

            if (operation.point() != null)
                validatePoint(operation.point(), path + ".point");

            if (operation.args() == null)
                throw error("Missing " + path + ".args");
        }
    }

    private static void validateTarget(Target target, String path) {
        require(target.kind(), path + ".kind");

        if (!TARGET_KINDS.contains(target.kind()))
            throw error(
                "Unknown target kind at " + path +
                    ": " + target.kind()
            );

        if (target.isMemberReference()) {
            require(target.name(), path + ".name");
            require(target.desc(), path + ".desc");
        }

        if (target.isConstructor()
            && !"<init>".equals(target.name())) {
            throw error(
                path + ".name must be <init> for constructor targets"
            );
        }
    }

    private static void validatePoint(Point point, String path) {
        require(point.at(), path + ".at");

        if (point.ordinal() != null && point.ordinal() < 0)
            throw error(
                path + ".ordinal cannot be negative"
            );

        if (point.type() != null
            && !CONSTANT_TYPES.contains(point.type())) {
            throw error(
                "Unknown constant type at " +
                    path + ".type: " +
                    point.type()
            );
        }

        if (point.target() != null)
            validateTarget(
                point.target(),
                path + ".target"
            );
    }

    private static void require(String value, String path) {
        if (value == null || value.isBlank())
            throw error(
                "Missing or empty " + path
            );
    }

    private static JsonParseException error(String message) {
        return new JsonParseException(message);
    }
}
