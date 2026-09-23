package com.spirit.koil.api.model;

import com.spirit.koil.api.model.tool.ModelWorkspaceRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Verifies predicted filesystem postconditions against current reality. This is
 * intentionally independent from a tool's returned success flag.
 */
public final class ToolPostconditionVerifier {
    private ToolPostconditionVerifier() {
    }

    public static Verification verify(List<ToolPostcondition> postconditions) {
        if (postconditions == null || postconditions.isEmpty()) {
            return Verification.notRequired();
        }
        ArrayList<String> failures = new ArrayList<>();
        for (ToolPostcondition condition : postconditions) {
            try {
                verifyOne(condition, failures);
            } catch (IOException | RuntimeException failure) {
                failures.add(condition.kind().name().toLowerCase() + " "
                        + target(condition) + ": " + concise(failure));
            }
        }
        return failures.isEmpty()
                ? new Verification("passed", List.of())
                : new Verification("failed", failures);
    }

    private static void verifyOne(ToolPostcondition condition, List<String> failures) throws IOException {
        ModelWorkspaceRegistry.ResolvedPath resolved = ModelWorkspaceRegistry.inspect(
                condition.workspace(), condition.path(), false
        );
        switch (condition.kind()) {
            case PATH_ABSENT -> {
                if (Files.exists(resolved.path())) {
                    failures.add("Expected absent path still exists: " + target(condition));
                }
            }
            case DIRECTORY_EXISTS -> {
                if (!Files.isDirectory(resolved.path())) {
                    failures.add("Expected directory does not exist: " + target(condition));
                }
            }
            case FILE_HASH_EQUALS -> {
                if (!Files.isRegularFile(resolved.path())) {
                    failures.add("Expected file does not exist: " + target(condition));
                    return;
                }
                String actual = sha256(Files.readAllBytes(resolved.path()));
                if (!actual.equalsIgnoreCase(condition.expectedValue())) {
                    failures.add("File hash mismatch for " + target(condition)
                            + " expected=" + condition.expectedValue() + " actual=" + actual);
                }
            }
        }
    }

    private static String target(ToolPostcondition condition) {
        return condition.workspace() + ":" + condition.path();
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable.", impossible);
        }
    }

    private static String concise(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    public record Verification(String status, List<String> failures) {
        public Verification {
            status = status == null || status.isBlank() ? "failed" : status.strip();
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        public static Verification notRequired() {
            return new Verification("not_required", List.of());
        }

        public boolean passed() {
            return "passed".equals(this.status) || "not_required".equals(this.status);
        }
    }
}
