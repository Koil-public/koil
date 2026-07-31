package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.automation.ktl.KtlCompilerService;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Provider-neutral local coding tools. Every operation resolves through a
 * named workspace and all mutations use the model panel's approval policy.
 */
public final class ModelWorkspaceToolRegistry {
    private static final int MAXIMUM_FILE_BYTES = 256 * 1024;
    private static final int MAXIMUM_TOOL_TEXT_CHARS = 24 * 1024;
    private static final int MAXIMUM_READ_LINES = 400;
    private static final int MAXIMUM_LIST_ENTRIES = 200;
    private static final int MAXIMUM_SEARCH_FILES = 500;
    private static final int MAXIMUM_SEARCH_RESULTS = 50;
    private static final Path TRASH_ROOT = Path.of("koil/sys/model/file-trash");
    private static final Map<String, ModelToolDefinition> DEFINITIONS = definitionsInternal();
    private static final Set<String> MUTATING_TOOLS = Set.of(
            "workspace.create",
            "workspace.write",
            "workspace.replace",
            "workspace.delete",
            "automation.ktl_apply"
    );
    private static final String VERSION = "model-workspace-tools-v2:"
            + Integer.toHexString(DEFINITIONS.keySet().hashCode());

    private ModelWorkspaceToolRegistry() {
    }

    public static String version() {
        return VERSION;
    }

    public static List<ModelToolDefinition> modelTools() {
        return List.copyOf(DEFINITIONS.values());
    }

    public static boolean supports(String toolId) {
        return toolId != null && DEFINITIONS.containsKey(toolId);
    }

    public static CompletableFuture<ModelToolResult> execute(
            UUID displayRequestId,
            ModelToolCall call
    ) {
        return execute(displayRequestId, call, false);
    }

    public static CompletableFuture<ModelToolResult> execute(
            UUID displayRequestId,
            ModelToolCall call,
            boolean preapproved
    ) {
        if (call == null || !supports(call.toolId())) {
            return CompletableFuture.completedFuture(failure(call, "unknown_tool", "Unknown workspace tool."));
        }
        if (!AutomationModeController.isAutomationMode()) {
            return CompletableFuture.completedFuture(failure(
                    call,
                    "automation_disabled",
                    "Automation Mode must be enabled before the model can inspect or change files."
            ));
        }
        CompletableFuture<Boolean> approval;
        if (preapproved || !MUTATING_TOOLS.contains(call.toolId()) || AutomationModeController.isYoloMode()) {
            approval = CompletableFuture.completedFuture(true);
        } else if (displayRequestId == null) {
            return CompletableFuture.completedFuture(failure(
                    call,
                    "approval_unavailable",
                    "This file change has no model-panel approval surface."
            ));
        } else {
            approval = ModelGenerationHudState.requestApproval(
                    displayRequestId,
                    "Model file approval",
                    approvalDetail(call),
                    "Apply File Change",
                    "Deny"
            );
        }
        return approval.thenCompose(approved -> {
            if (!approved) {
                return CompletableFuture.completedFuture(new ModelToolResult(
                        call.id(), call.toolId(), "rejected", new JsonObject(),
                        "user_declined", "The player declined the requested file change."
                ));
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return executeBlocking(call);
                } catch (Exception failure) {
                    return failure(call, "workspace_operation_failed", message(failure));
                }
            });
        });
    }

    private static ModelToolResult executeBlocking(ModelToolCall call) throws Exception {
        return switch (call.toolId()) {
            case "workspace.roots" -> listRoots(call);
            case "workspace.list" -> list(call);
            case "workspace.read" -> read(call);
            case "workspace.search" -> search(call);
            case "workspace.create" -> create(call);
            case "workspace.write" -> write(call);
            case "workspace.replace" -> replace(call);
            case "workspace.delete" -> delete(call);
            case "automation.ktl_apply" -> applyKtl(call);
            default -> failure(call, "unknown_tool", "Unknown workspace tool.");
        };
    }

    private static ModelToolResult listRoots(ModelToolCall call) {
        JsonArray roots = new JsonArray();
        ModelWorkspaceRegistry.workspaces().values().stream()
                .sorted(Comparator.comparing(ModelWorkspaceRegistry.Workspace::id))
                .forEach(workspace -> {
                    JsonObject root = new JsonObject();
                    root.addProperty("id", workspace.id());
                    root.addProperty("description", workspace.description());
                    root.addProperty("writable", workspace.writable());
                    roots.add(root);
                });
        JsonObject output = new JsonObject();
        output.add("workspaces", roots);
        return completed(call, output, "Available model workspaces were listed.");
    }

    private static ModelToolResult list(ModelToolCall call) throws IOException {
        JsonObject arguments = call.arguments();
        String workspaceId = requiredString(arguments, "workspace");
        String relative = optionalString(arguments, "path", "");
        int depth = boundedInt(arguments, "depth", 1, 1, 4);
        ModelWorkspaceRegistry.ResolvedPath resolved =
                ModelWorkspaceRegistry.resolve(workspaceId, relative, false);
        if (!Files.isDirectory(resolved.path())) {
            throw new IOException("List target is not a directory.");
        }
        JsonArray entries = new JsonArray();
        try (var paths = Files.walk(resolved.path(), depth)) {
            paths.filter(path -> !path.equals(resolved.path()))
                    .limit(MAXIMUM_LIST_ENTRIES)
                    .sorted()
                    .forEach(path -> {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("path", resolved.workspace().root().relativize(path)
                                .toString().replace('\\', '/'));
                        entry.addProperty("type", Files.isDirectory(path) ? "directory" : "file");
                        if (Files.isRegularFile(path)) {
                            try {
                                entry.addProperty("bytes", Files.size(path));
                            } catch (IOException ignored) {
                            }
                        }
                        entries.add(entry);
                    });
        }
        JsonObject output = new JsonObject();
        output.addProperty("workspace", resolved.workspace().id());
        output.addProperty("path", resolved.relativePath());
        output.add("entries", entries);
        output.addProperty("truncated", entries.size() >= MAXIMUM_LIST_ENTRIES);
        return completed(call, output, "Workspace directory was listed.");
    }

    private static ModelToolResult read(ModelToolCall call) throws IOException {
        JsonObject arguments = call.arguments();
        ModelWorkspaceRegistry.ResolvedPath resolved = ModelWorkspaceRegistry.resolve(
                requiredString(arguments, "workspace"),
                requiredString(arguments, "path"),
                false
        );
        requireTextFile(resolved.path());
        int startLine = boundedInt(arguments, "startLine", 1, 1, Integer.MAX_VALUE);
        int maximumLines = boundedInt(arguments, "maxLines", 160, 1, MAXIMUM_READ_LINES);
        List<String> lines = Files.readAllLines(resolved.path(), StandardCharsets.UTF_8);
        int from = Math.min(lines.size(), startLine - 1);
        int to = Math.min(lines.size(), from + maximumLines);
        StringBuilder text = new StringBuilder();
        int end = from;
        for (int index = from; index < to; index++) {
            String line = (index + 1) + " | " + lines.get(index) + '\n';
            if (text.length() + line.length() > MAXIMUM_TOOL_TEXT_CHARS) {
                break;
            }
            text.append(line);
            end = index + 1;
        }
        JsonObject output = new JsonObject();
        output.addProperty("workspace", resolved.workspace().id());
        output.addProperty("path", resolved.relativePath());
        output.addProperty("startLine", from + 1);
        output.addProperty("endLine", end);
        output.addProperty("totalLines", lines.size());
        output.addProperty("text", text.toString());
        output.addProperty("truncated", end < lines.size());
        return completed(call, output, "Requested file section was read.");
    }

    private static ModelToolResult search(ModelToolCall call) throws IOException {
        JsonObject arguments = call.arguments();
        String query = requiredString(arguments, "query");
        boolean caseSensitive = optionalBoolean(arguments, "caseSensitive", false);
        ModelWorkspaceRegistry.ResolvedPath resolved = ModelWorkspaceRegistry.resolve(
                requiredString(arguments, "workspace"),
                optionalString(arguments, "path", ""),
                false
        );
        if (!Files.isDirectory(resolved.path())) {
            throw new IOException("Search target is not a directory.");
        }
        String needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        JsonArray matches = new JsonArray();
        int scanned = 0;
        try (var paths = Files.walk(resolved.path())) {
            for (Path path : paths
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(Files::isRegularFile)
                    .limit(MAXIMUM_SEARCH_FILES)
                    .toList()) {
                if (scanned++ >= MAXIMUM_SEARCH_FILES || matches.size() >= MAXIMUM_SEARCH_RESULTS) {
                    break;
                }
                if (Files.size(path) > MAXIMUM_FILE_BYTES) {
                    continue;
                }
                List<String> lines;
                try {
                    lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                } catch (IOException | RuntimeException ignored) {
                    continue;
                }
                for (int index = 0; index < lines.size() && matches.size() < MAXIMUM_SEARCH_RESULTS; index++) {
                    String haystack = caseSensitive ? lines.get(index) : lines.get(index).toLowerCase(Locale.ROOT);
                    if (!haystack.contains(needle)) {
                        continue;
                    }
                    JsonObject match = new JsonObject();
                    match.addProperty("path", resolved.workspace().root().relativize(path)
                            .toString().replace('\\', '/'));
                    match.addProperty("line", index + 1);
                    match.addProperty("text", abbreviate(lines.get(index), 300));
                    matches.add(match);
                }
            }
        }
        JsonObject output = new JsonObject();
        output.addProperty("workspace", resolved.workspace().id());
        output.addProperty("query", query);
        output.addProperty("scannedFiles", Math.min(scanned, MAXIMUM_SEARCH_FILES));
        output.add("matches", matches);
        output.addProperty("truncated", scanned >= MAXIMUM_SEARCH_FILES
                || matches.size() >= MAXIMUM_SEARCH_RESULTS);
        return completed(call, output, "Workspace text search completed.");
    }

    private static ModelToolResult create(ModelToolCall call) throws IOException {
        JsonObject arguments = call.arguments();
        ModelWorkspaceRegistry.ResolvedPath resolved = writableFile(arguments);
        if (Files.exists(resolved.path())) {
            throw new IOException("File already exists; use workspace.write or workspace.replace.");
        }
        String content = boundedContent(arguments);
        atomicWrite(resolved.path(), content, false);
        return mutationResult(call, resolved, "created", content.length());
    }

    private static ModelToolResult write(ModelToolCall call) throws IOException {
        JsonObject arguments = call.arguments();
        ModelWorkspaceRegistry.ResolvedPath resolved = writableFile(arguments);
        if (!Files.isRegularFile(resolved.path())) {
            throw new IOException("File does not exist; use workspace.create.");
        }
        String content = boundedContent(arguments);
        atomicWrite(resolved.path(), content, true);
        return mutationResult(call, resolved, "written", content.length());
    }

    private static ModelToolResult replace(ModelToolCall call) throws IOException {
        JsonObject arguments = call.arguments();
        ModelWorkspaceRegistry.ResolvedPath resolved = writableFile(arguments);
        requireTextFile(resolved.path());
        String find = requiredString(arguments, "find");
        String replacement = optionalString(arguments, "replacement", "");
        if (find.length() > MAXIMUM_FILE_BYTES || replacement.length() > MAXIMUM_FILE_BYTES) {
            throw new IOException("Replacement input exceeds the 262144 b limit.");
        }
        String content = Files.readString(resolved.path(), StandardCharsets.UTF_8);
        int occurrences = countOccurrences(content, find);
        int expected = boundedInt(arguments, "expectedOccurrences", 1, 1, 1000);
        if (occurrences != expected) {
            throw new IOException("Expected " + expected + " occurrence(s), found " + occurrences + "; no file was changed.");
        }
        String updated = content.replace(find, replacement);
        if (updated.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_FILE_BYTES) {
            throw new IOException("Updated file exceeds the 262144 b limit.");
        }
        atomicWrite(resolved.path(), updated, true);
        return mutationResult(call, resolved, "replaced", occurrences);
    }

    private static ModelToolResult delete(ModelToolCall call) throws IOException {
        JsonObject arguments = call.arguments();
        ModelWorkspaceRegistry.ResolvedPath resolved = writableFile(arguments);
        if (!Files.isRegularFile(resolved.path())) {
            throw new IOException("Only individual regular files can be removed.");
        }
        Path trashDirectory = TRASH_ROOT.toAbsolutePath().normalize()
                .resolve(UUID.randomUUID().toString());
        Files.createDirectories(trashDirectory);
        Path trashed = trashDirectory.resolve(resolved.path().getFileName().toString());
        try {
            Files.move(resolved.path(), trashed, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unsupported) {
            Files.move(resolved.path(), trashed);
        }
        JsonObject output = new JsonObject();
        output.addProperty("workspace", resolved.workspace().id());
        output.addProperty("path", resolved.relativePath());
        output.addProperty("recoverable", true);
        output.addProperty("trashPath", trashed.toString());
        return completed(call, output, "File was moved to Koil's recoverable model trash.");
    }

    private static ModelToolResult applyKtl(ModelToolCall call) throws IOException {
        JsonObject arguments = call.arguments();
        String relative = requiredString(arguments, "path");
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".ktl")) {
            throw new IOException("KTL apply requires a .ktl path.");
        }
        ModelWorkspaceRegistry.ResolvedPath resolved =
                ModelWorkspaceRegistry.resolve("automation", relative, true);
        String content = boundedContent(arguments);
        boolean existed = Files.isRegularFile(resolved.path());
        String previous = existed
                ? Files.readString(resolved.path(), StandardCharsets.UTF_8)
                : "";
        if (previous.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_FILE_BYTES) {
            throw new IOException("Existing KTL file exceeds the model-tool limit.");
        }
        try {
            atomicWrite(resolved.path(), content, existed);
            KtlCompilerService.getInstance().reload();
        } catch (RuntimeException | IOException validationFailure) {
            try {
                if (existed) {
                    atomicWrite(resolved.path(), previous, true);
                } else {
                    Files.deleteIfExists(resolved.path());
                }
                KtlCompilerService.getInstance().reload();
            } catch (Exception rollbackFailure) {
                validationFailure.addSuppressed(rollbackFailure);
            }
            throw new IOException("KTL validation failed and the prior registry was restored: "
                    + message(validationFailure), validationFailure);
        }
        JsonObject output = new JsonObject();
        output.addProperty("workspace", "automation");
        output.addProperty("path", resolved.relativePath());
        output.addProperty("operation", existed ? "updated" : "created");
        output.addProperty("registryReloaded", true);
        return completed(call, output, "KTL file validated, applied, and reloaded.");
    }

    private static ModelWorkspaceRegistry.ResolvedPath writableFile(JsonObject arguments) throws IOException {
        ModelWorkspaceRegistry.ResolvedPath resolved = ModelWorkspaceRegistry.resolve(
                requiredString(arguments, "workspace"),
                requiredString(arguments, "path"),
                true
        );
        if (resolved.relativePath().isBlank()) {
            throw new IOException("A file path is required.");
        }
        return resolved;
    }

    private static void requireTextFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Requested path is not a regular file.");
        }
        long size = Files.size(path);
        if (size > MAXIMUM_FILE_BYTES) {
            throw new IOException("File exceeds the 262144 b model-tool limit.");
        }
        byte[] probe = Files.readAllBytes(path);
        for (byte value : probe) {
            if (value == 0) {
                throw new IOException("Binary files are not exposed as text.");
            }
        }
    }

    private static String boundedContent(JsonObject arguments) throws IOException {
        String content = optionalString(arguments, "content", "");
        if (content.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_FILE_BYTES) {
            throw new IOException("Content exceeds the 262144 b model-tool limit.");
        }
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void atomicWrite(Path path, String content, boolean replace) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("File has no writable parent.");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".koil-model-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                if (replace) {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (UnsupportedOperationException | IOException atomicFailure) {
                if (replace) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, path);
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static ModelToolResult mutationResult(
            ModelToolCall call,
            ModelWorkspaceRegistry.ResolvedPath resolved,
            String operation,
            long changed
    ) {
        JsonObject output = new JsonObject();
        output.addProperty("workspace", resolved.workspace().id());
        output.addProperty("path", resolved.relativePath());
        output.addProperty("operation", operation);
        output.addProperty("changed", changed);
        return completed(call, output, "Workspace file change completed atomically.");
    }

    private static ModelToolResult completed(ModelToolCall call, JsonObject output, String detail) {
        return new ModelToolResult(call.id(), call.toolId(), "completed", output, "", detail);
    }

    private static ModelToolResult failure(ModelToolCall call, String code, String detail) {
        return new ModelToolResult(
                call == null ? "" : call.id(),
                call == null ? "" : call.toolId(),
                "failed",
                new JsonObject(),
                code,
                detail
        );
    }

    private static String approvalDetail(ModelToolCall call) {
        JsonObject arguments = call.arguments();
        String workspace = optionalString(arguments, "workspace", "unknown");
        String path = optionalString(arguments, "path", "unknown");
        return call.toolId() + " requested for " + workspace + ":" + path
                + "\n\nThe operation is restricted to that named workspace. Deletes move files to Koil's recoverable model trash.";
    }

    private static Map<String, ModelToolDefinition> definitionsInternal() {
        Map<String, ModelToolDefinition> definitions = new LinkedHashMap<>();
        definitions.put("workspace.roots", definition(
                "workspace.roots",
                "List named local workspaces available to model file tools.",
                objectSchema(Map.of(), List.of()),
                false,
                Set.of("returns_workspace_metadata")
        ));
        definitions.put("workspace.list", definition(
                "workspace.list",
                "List files and directories under a bounded named workspace path.",
                objectSchema(Map.of(
                        "workspace", stringSchema(),
                        "path", stringSchema(),
                        "depth", integerSchema(1, 4)
                ), List.of("workspace")),
                false,
                Set.of("reads_file_metadata")
        ));
        definitions.put("workspace.read", definition(
                "workspace.read",
                "Read a bounded line-numbered section of any permitted UTF-8 text file, including JSON, JSON5, YAML, TOML, mcfunction, mcmeta, language, properties, Markdown, Java/source, config, and KTL files.",
                objectSchema(Map.of(
                        "workspace", stringSchema(),
                        "path", stringSchema(),
                        "startLine", integerSchema(1, Integer.MAX_VALUE),
                        "maxLines", integerSchema(1, MAXIMUM_READ_LINES)
                ), List.of("workspace", "path")),
                false,
                Set.of("reads_file")
        ));
        definitions.put("workspace.search", definition(
                "workspace.search",
                "Search bounded permitted UTF-8 workspace files of any text format for literal text.",
                objectSchema(Map.of(
                        "workspace", stringSchema(),
                        "path", stringSchema(),
                        "query", stringSchema(),
                        "caseSensitive", booleanSchema()
                ), List.of("workspace", "query")),
                false,
                Set.of("reads_files")
        ));
        definitions.put("workspace.create", definition(
                "workspace.create",
                "Atomically create a new permitted UTF-8 text file of any format; fails if it already exists.",
                mutationSchema(false),
                true,
                Set.of("creates_file")
        ));
        definitions.put("workspace.write", definition(
                "workspace.write",
                "Atomically replace the complete content of an existing permitted UTF-8 text file of any format.",
                mutationSchema(false),
                true,
                Set.of("changes_file")
        ));
        definitions.put("workspace.replace", definition(
                "workspace.replace",
                "Atomically replace an exact occurrence in any permitted UTF-8 text file after verifying its expected count.",
                objectSchema(Map.of(
                        "workspace", stringSchema(),
                        "path", stringSchema(),
                        "find", stringSchema(),
                        "replacement", stringSchema(),
                        "expectedOccurrences", integerSchema(1, 1000)
                ), List.of("workspace", "path", "find")),
                true,
                Set.of("changes_file")
        ));
        definitions.put("workspace.delete", definition(
                "workspace.delete",
                "Move one regular file to Koil's recoverable model trash.",
                objectSchema(Map.of(
                        "workspace", stringSchema(),
                        "path", stringSchema()
                ), List.of("workspace", "path")),
                true,
                Set.of("moves_file_to_trash")
        ));
        definitions.put("automation.ktl_apply", definition(
                "automation.ktl_apply",
                "Atomically create or update one active KTL file, validate the complete KTL registry, and roll back on failure.",
                objectSchema(Map.of(
                        "path", stringSchema(),
                        "content", stringSchema()
                ), List.of("path", "content")),
                true,
                Set.of("changes_automation_file", "reloads_ktl_registry")
        ));
        return Map.copyOf(definitions);
    }

    private static ModelToolDefinition definition(
            String id,
            String description,
            JsonObject schema,
            boolean confirmation,
            Set<String> sideEffects
    ) {
        return new ModelToolDefinition(
                id,
                description,
                schema,
                List.of("automation_mode_enabled", "path_inside_named_workspace"),
                sideEffects,
                "workspace.delete".equals(id),
                Duration.ofSeconds(30),
                false,
                confirmation,
                Set.of("completed", "rejected", "failed")
        );
    }

    private static JsonObject mutationSchema(boolean unused) {
        return objectSchema(Map.of(
                "workspace", stringSchema(),
                "path", stringSchema(),
                "content", stringSchema()
        ), List.of("workspace", "path", "content"));
    }

    private static JsonObject objectSchema(Map<String, JsonObject> properties, List<String> required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject propertyObject = new JsonObject();
        properties.forEach(propertyObject::add);
        schema.add("properties", propertyObject);
        schema.addProperty("additionalProperties", false);
        JsonArray requiredArray = new JsonArray();
        required.forEach(requiredArray::add);
        schema.add("required", requiredArray);
        return schema;
    }

    private static JsonObject stringSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("maxLength", MAXIMUM_FILE_BYTES);
        return schema;
    }

    private static JsonObject integerSchema(int minimum, int maximum) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        schema.addProperty("minimum", minimum);
        schema.addProperty("maximum", maximum);
        return schema;
    }

    private static JsonObject booleanSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "boolean");
        return schema;
    }

    private static String requiredString(JsonObject root, String key) throws IOException {
        String value = optionalString(root, key, "");
        if (value.isBlank()) {
            throw new IOException("Missing required argument '" + key + "'.");
        }
        return value;
    }

    private static String optionalString(JsonObject root, String key, String fallback) {
        try {
            return root != null && root.has(key) && !root.get(key).isJsonNull()
                    ? root.get(key).getAsString()
                    : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean optionalBoolean(JsonObject root, String key, boolean fallback) {
        try {
            return root != null && root.has(key) && root.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int boundedInt(JsonObject root, String key, int fallback, int minimum, int maximum) {
        try {
            int value = root != null && root.has(key) ? root.get(key).getAsInt() : fallback;
            return Math.max(minimum, Math.min(maximum, value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int countOccurrences(String text, String find) {
        if (find.isEmpty()) {
            return 0;
        }
        int count = 0;
        int cursor = 0;
        while ((cursor = text.indexOf(find, cursor)) >= 0) {
            count++;
            cursor += find.length();
        }
        return count;
    }

    private static String abbreviate(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value == null ? "" : value;
        }
        return value.substring(0, maximum - 1) + "…";
    }

    private static String message(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String value = cursor.getMessage();
        return value == null || value.isBlank() ? cursor.getClass().getSimpleName() : value;
    }
}
