package com.spirit.koil.api.model.install;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Optional proof against a pinned upstream Colibri openai_server.py fixture. */
public final class ColibriRuntimeIntegrationProof {
    private ColibriRuntimeIntegrationProof() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Colibri c/ fixture path required");
        Path source = Path.of(arguments[0]).toAbsolutePath().normalize();
        require(Files.isRegularFile(source.resolve("openai_server.py")), "openai_server.py missing");
        Path temporary = Files.createTempDirectory("koil-colibri-integration-proof-");
        try {
            Files.copy(source.resolve("openai_server.py"), temporary.resolve("openai_server.py"),
                    StandardCopyOption.REPLACE_EXISTING);
            ColibriRuntimeIntegrator.integrate(temporary);
            String integrated = Files.readString(temporary.resolve("openai_server.py"), StandardCharsets.UTF_8);
            require(integrated.contains("from koil_gigatoken import encode_colibri_prompt"),
                    "Gigatoken prompt bridge was not installed");
            require(integrated.contains("ids=1"), "Colibri token-ID intake flag was not installed");
            require(Files.isRegularFile(temporary.resolve("koil_gigatoken.py")),
                    "Colibri bridge module was not installed");
            String once = integrated;
            ColibriRuntimeIntegrator.integrate(temporary);
            require(once.equals(Files.readString(temporary.resolve("openai_server.py"), StandardCharsets.UTF_8)),
                    "Colibri integration was not idempotent");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        System.out.println("Colibri runtime integration proof passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
