package com.spirit.mixin.jmixin.generated;

import net.fabricmc.loader.impl.launch.FabricLauncherBase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GeneratedMixinHost {

    private static Path directory;

    private GeneratedMixinHost() {
    }

    public static synchronized void define(String className, byte[] bytecode) {
        try {
            if (directory == null) {
                directory = Files.createTempDirectory("jmixin-generated-");
                FabricLauncherBase.getLauncher().addToClassPath(directory);
                System.out.println("[JMIXIN] Generated classpath: " + directory);
            }

            Path file = directory.resolve(className.replace('.', '/') + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, bytecode);

            System.out.println("[JMIXIN] Defined: " + className);
            System.out.println("[JMIXIN] Exists: " + Files.exists(file));
            System.out.println("[JMIXIN] Size: " + Files.size(file));

            try {
                Class.forName(className, false, GeneratedMixinHost.class.getClassLoader());
                System.out.println("[JMIXIN] ClassLoader can load: " + className);
            } catch (ClassNotFoundException exception) {
                System.err.println("[JMIXIN] ClassLoader CANNOT load: " + className);
                exception.printStackTrace(System.err);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to register generated JMIXIN class", exception);
        }
    }
}
