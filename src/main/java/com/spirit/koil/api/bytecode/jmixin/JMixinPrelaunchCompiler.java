package com.spirit.koil.api.bytecode.jmixin;

import com.google.gson.JsonElement;
import com.spirit.koil.api.jmixin.*;
import com.spirit.mixin.jmixin.generated.GeneratedMixinHost;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

public final class JMixinPrelaunchCompiler {

    private static final String GENERATED_PACKAGE = "com.spirit.mixin.jmixin.generated";
    private static final AtomicBoolean COMPILED = new AtomicBoolean();
    private static final List<String> MIXINS = new ArrayList<>();

    private JMixinPrelaunchCompiler() {}

    public static void compileIfNeeded() {
        if (COMPILED.get()) return;

        synchronized (COMPILED) {
            if (COMPILED.get()) return;

            try {
                compile(discover());
                COMPILED.set(true);
            } catch (Exception exception) {
                System.err.println("[JMIXIN] Prelaunch compilation failed: " + exception.getMessage());
                exception.printStackTrace(System.err);
            }
        }
    }

    public static List<String> mixinClassNames() {
        compileIfNeeded();
        synchronized (MIXINS) {return List.copyOf(MIXINS);}
    }

    private static void compile(List<Source> sources) {
        Set<String> seen = new LinkedHashSet<>();
        for (Source source : sources) {
            try {
                ClassDefinition definition = BytecodeJsonParser.parse(source.json());
                String identity = source.packId() + ':' + definition.id();
                if (!seen.add(identity)) continue;
                String className = GENERATED_PACKAGE + ".JMixin_" + digest(identity);
                GeneratedMixinHost.define(
                    className,
                    generate(className, source.packId(), definition)
                );
                synchronized (MIXINS) {
                    MIXINS.add(className.substring("com.spirit.mixin.".length()));
                }
            } catch (Exception exception) {
                System.err.println("[JMIXIN] Ignored " + source.path() + ": " + exception.getMessage());
            }
        }
    }

    private static byte[] generate(String className, String packId, ClassDefinition definition) {
        String internalName = className.replace('.', '/');
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, internalName,
            null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("targets");
        targets.visit(null, definition.target());
        targets.visitEnd();
        mixin.visitEnd();

        int index = 0;
        for (Operation operation : definition.operations()) {
            if (!"modify_constant".equals(operation.type())) {
                throw new IllegalArgumentException("Unsupported operation type: " + operation.type());
            }
            emitModifyConstant(writer, packId, operation, index++);
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitModifyConstant(ClassWriter writer, String packId, Operation operation, int index) {
        if (!operation.target().isMethod())
            throw new IllegalArgumentException("modify_constant requires a method target");
        if (operation.point() == null || operation.point().expected() == null) {
            throw new IllegalArgumentException("modify_constant requires point.expected");
        }
        Type type = constantType(operation.point());
        Expression expression = operation.expression("value");
        String descriptor = '(' + type.getDescriptor() + ')' + type.getDescriptor();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
            "jmixin$constant$" + index, descriptor, null, null);
        AnnotationVisitor modify = method.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/ModifyConstant;", true);
        modify.visit("require", 1);
        AnnotationVisitor targets = modify.visitArray("method");
        targets.visit(null, operation.target().name() + operation.target().desc());
        targets.visitEnd();
        AnnotationVisitor constant = modify.visitAnnotation("constant", "Lorg/spongepowered/asm/mixin/injection/Constant;");
        emitConstantSelector(constant, operation.point());
        if (operation.point().ordinal() != null) constant.visit("ordinal", operation.point().ordinal());
        constant.visitEnd();
        modify.visitEnd();

        method.visitCode();
        method.visitLdcInsn(packId);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/spirit/koil/api/jmixin/DatapackGate",
            "isEnabled", "(Ljava/lang/String;)Z", false);
        Label enabled = new Label();
        method.visitJumpInsn(Opcodes.IFNE, enabled);
        method.visitVarInsn(loadOpcode(type), 0);
        method.visitInsn(returnOpcode(type));
        method.visitLabel(enabled);
        JMixinExpressionEmitter.emit(method, type, expression, 0);
        method.visitInsn(returnOpcode(type));
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void emitConstantSelector(AnnotationVisitor visitor, Point point) {
        JsonElement value = point.expected();

        if (!value.isJsonPrimitive())
            throw new IllegalArgumentException(
                "Constant selector must be primitive"
            );

        if (point.type() != null) {
            switch (point.type()) {
                case "byte", "short", "int", "char" -> visitor.visit("intValue", value.getAsInt());

                case "long" -> visitor.visit("longValue", value.getAsLong());

                case "float" -> visitor.visit("floatValue", value.getAsFloat());

                case "double" -> visitor.visit("doubleValue", value.getAsDouble());

                case "string" -> visitor.visit("stringValue", value.getAsString());

                case "boolean" -> throw new IllegalArgumentException(
                    "Boolean constants are unsupported"
                );

                default -> throw new IllegalArgumentException(
                    "Unsupported constant type: " + point.type()
                );
            }

            return;
        }

        if (value.getAsJsonPrimitive().isString()) {
            visitor.visit("stringValue", value.getAsString());
            return;
        }

        if (value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException(
                "Boolean constants are unsupported"
            );

        Number number = value.getAsNumber();
        String text = value.getAsString();

        if (text.contains(".") || text.contains("e") || text.contains("E"))
            visitor.visit("doubleValue", number.doubleValue());
        else if (number.longValue() >= Integer.MIN_VALUE
            && number.longValue() <= Integer.MAX_VALUE)
            visitor.visit("intValue", number.intValue());
        else
            visitor.visit("longValue", number.longValue());
    }

    private static Type constantType(Point point) {
        JsonElement value = point.expected();

        if (point.type() != null) {
            return switch (point.type()) {
                case "byte" -> Type.BYTE_TYPE;
                case "short" -> Type.SHORT_TYPE;
                case "int" -> Type.INT_TYPE;
                case "long" -> Type.LONG_TYPE;
                case "float" -> Type.FLOAT_TYPE;
                case "double" -> Type.DOUBLE_TYPE;
                case "char" -> Type.CHAR_TYPE;
                case "boolean" -> Type.BOOLEAN_TYPE;
                case "string" -> Type.getType(String.class);
                default -> throw new IllegalArgumentException(
                    "Unsupported constant type: " + point.type()
                );
            };
        }

        if (value.getAsJsonPrimitive().isString())
            return Type.getType(String.class);

        if (value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("Boolean constants are unsupported");

        String text = value.getAsString();

        if (text.contains(".") || text.contains("e") || text.contains("E"))
            return Type.DOUBLE_TYPE;

        long parsed = value.getAsLong();

        return parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE
            ? Type.INT_TYPE
            : Type.LONG_TYPE;
    }

    private static int loadOpcode(Type type) {
        return switch (type.getSort()) {
            case Type.LONG -> Opcodes.LLOAD;
            case Type.DOUBLE -> Opcodes.DLOAD;
            case Type.FLOAT -> Opcodes.FLOAD;
            case Type.OBJECT, Type.ARRAY -> Opcodes.ALOAD;
            default -> Opcodes.ILOAD;
        };
    }

    private static int returnOpcode(Type type) {
        return switch (type.getSort()) {
            case Type.LONG -> Opcodes.LRETURN;
            case Type.DOUBLE -> Opcodes.DRETURN;
            case Type.FLOAT -> Opcodes.FRETURN;
            case Type.OBJECT, Type.ARRAY -> Opcodes.ARETURN;
            default -> Opcodes.IRETURN;
        };
    }

    /*private static List<Source> discover() {
        String json = """
            {
               "id": "zombie_speed",
               "target": "net.minecraft.entity.mob.ZombieEntity",
               "operations": [
                 {
                   "type": "modify_constant",
                   "target": {
                     "kind": "method",
                     "name": "createZombieAttributes",
                     "desc": "()Lnet/minecraft/entity/attribute/DefaultAttributeContainer$Builder;"
                   },
                   "point": {
                     "at": "constant",
                     "expected": 0.23000000417232513,
                     "type": "double"
                   },
                   "args": {
                     "value": {
                       "op": "multiply",
                       "left": 0.23000000417232513,
                       "right": 10
                     }
                   }
                 }
               ]
             }
            """;

        return List.of(new Source("test", "hardcoded", json));
    }*/

    private static List<Source> discover() throws IOException {
        Path saves = FabricLoader.getInstance().getGameDir().resolve("saves");
        if (!Files.isDirectory(saves)) return List.of();
        List<Source> sources = new ArrayList<>();
        try (var worlds = Files.list(saves)) {
            for (Path world : worlds.filter(Files::isDirectory).toList()) {
                Path packs = world.resolve("datapacks");
                if (!Files.isDirectory(packs)) continue;
                try (var entries = Files.list(packs)) {
                    for (Path pack : entries.toList()) discoverPack(pack, sources);
                }
            }
        }
        return sources;
    }

    private static void discoverPack(Path pack, List<Source> output) throws IOException {
        String packId = "file/" + pack.getFileName();
        if (Files.isDirectory(pack)) {
            Path data = pack.resolve("data");
            if (!Files.isDirectory(data)) return;
            try (var files = Files.walk(data, 32)) {
                for (Path file : files.filter(Files::isRegularFile).filter(JMixinPrelaunchCompiler::isJMixinFile).toList()) {
                    output.add(new Source(packId, file.toString(), Files.readString(file, StandardCharsets.UTF_8)));
                }
            }
        } else if (pack.getFileName().toString().endsWith(".zip")) {
            try (ZipFile zip = new ZipFile(pack.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.isDirectory() && isJMixinPath(entry.getName())) {
                        output.add(new Source(packId, pack + "!/" + entry.getName(),
                            new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8)));
                    }
                }
            }
        }
    }

    private static boolean isJMixinFile(Path path) {return isJMixinPath(path.toString().replace('\\', '/'));}

    private static boolean isJMixinPath(String path) {return path.matches("data/[^/]+/jmixin/.+\\.json");}

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception exception) {throw new IllegalStateException(exception);}
    }

    private record Source(String packId, String path, String json) {

    }
}
