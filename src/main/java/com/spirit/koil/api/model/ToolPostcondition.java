package com.spirit.koil.api.model;

/**
 * A concrete state assertion Koil expects to hold after a mutating tool call.
 * Postconditions are descriptive verification targets only; they never execute
 * the mutation and never grant approval.
 */
public record ToolPostcondition(
        Kind kind,
        String workspace,
        String path,
        String expectedValue,
        String description
) {
    public ToolPostcondition {
        if (kind == null) throw new IllegalArgumentException("postcondition kind is required");
        workspace = workspace == null ? "" : workspace.strip();
        path = path == null ? "" : path.strip();
        expectedValue = expectedValue == null ? "" : expectedValue.strip();
        description = description == null ? "" : description.strip();
    }

    public static ToolPostcondition fileHash(String workspace, String path, String hash) {
        return new ToolPostcondition(
                Kind.FILE_HASH_EQUALS, workspace, path, hash,
                "File must exist with the staged content hash."
        );
    }

    public static ToolPostcondition pathAbsent(String workspace, String path) {
        return new ToolPostcondition(
                Kind.PATH_ABSENT, workspace, path, "",
                "Path must not exist after the operation."
        );
    }

    public static ToolPostcondition directoryExists(String workspace, String path) {
        return new ToolPostcondition(
                Kind.DIRECTORY_EXISTS, workspace, path, "",
                "Directory must exist after the operation."
        );
    }

    public static ToolPostcondition fileUnchanged(String workspace, String path, String hash) {
        return new ToolPostcondition(
                Kind.FILE_HASH_EQUALS, workspace, path, hash,
                "Source file must remain unchanged."
        );
    }

    public enum Kind {
        FILE_HASH_EQUALS,
        PATH_ABSENT,
        DIRECTORY_EXISTS
    }
}
