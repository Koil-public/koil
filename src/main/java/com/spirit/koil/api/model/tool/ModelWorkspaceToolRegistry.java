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
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
    private static final Set<String> MUTATING_TOOLS = Set.of(
            "workspace.mkdir",
            "workspace.create",
            "workspace.write",
            "workspace.replace",
            "workspace.copy",
            "workspace.move",
            "workspace.delete",
            "workspace.restore",
            "automation.ktl_apply"
    );
    private static final Map<String, ModelToolDefinition> DEFINITIONS = definitionsInternal();
    private static final String VERSION = "model-workspace-tools-v4:"
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

    public static CompletableFuture<ModelToolResult> executeReadOnly(ModelToolCall call) {
        if (call == null || !Set.of("workspace.roots", "workspace.list", "workspace.stat", "workspace.read", "workspace.search").contains(call.toolId())) {
            return CompletableFuture.completedFuture(new ModelToolResult(
                    call == null ? "" : call.id(), call == null ? "" : call.toolId(),
                    "unsupported", new JsonObject(), "read_only_boundary",
                    "Conversational Deep Thought can use only read-only workspace operations."
            ));
        }
        return CompletableFuture.supplyAsync(() -> {
            try { return executeBlocking(call); }
            catch (Exception failure) { return failure(call, "workspace_operation_failed", message(failure)); }
        });
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
        if (preapproved || !MUTATING_TOOLS.contains(call.toolId()) || AutomationModeController.isUnrestrictedMode()) {
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
                } catch (StaleFileException stale) {
                    JsonObject output = new JsonObject();
                    output.addProperty("expectedHash", stale.expected);
                    output.addProperty("actualHash", stale.actual);
                    return new ModelToolResult(
                            call.id(), call.toolId(), "stale", output,
                            "stale_file", stale.getMessage(),
                            stale.startedAt, System.currentTimeMillis(), "failed",
                            List.of(), true, false, "approved"
                    );
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
            case "workspace.stat" -> stat(call);
            case "workspace.read" -> read(call);
            case "workspace.search" -> search(call);
            case "workspace.mkdir" -> mkdir(call);
            case "workspace.create" -> create(call);
            case "workspace.write" -> write(call);
            case "workspace.replace" -> replace(call);
            case "workspace.copy" -> copy(call);
            case "workspace.move" -> move(call);
            case "workspace.delete" -> delete(call);
            case "workspace.restore" -> restore(call);
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
        String workspaceId = workspaceString(arguments, "workspace");
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
                workspaceString(arguments, "workspace"),
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
        output.addProperty("linesReturned", Math.max(0, end - from));
        output.addProperty("totalBytes", Files.size(resolved.path()));
        output.addProperty("contentHash", sha256(Files.readAllBytes(resolved.path())));
        output.addProperty("text", text.toString());
        boolean hasMore = end < lines.size();
        output.addProperty("complete", !hasMore);
        output.addProperty("hasMore", hasMore);
        output.addProperty("truncated", hasMore);
        if (hasMore) {
            output.addProperty("nextStartLine", end + 1);
        }
        return completed(call, output, "Requested file section was read.");
    }

    private static ModelToolResult stat(ModelToolCall call) throws IOException {
        JsonObject arguments = call.arguments();
        ModelWorkspaceRegistry.ResolvedPath resolved = ModelWorkspaceRegistry.resolve(
                workspaceString(arguments, "workspace"),
                optionalString(arguments, "path", ""),
                false
        );
        if (!Files.exists(resolved.path())) {
            throw new IOException("Workspace target does not exist.");
        }
        JsonObject output = new JsonObject();
        output.addProperty("workspace", resolved.workspace().id());
        output.addProperty("path", resolved.relativePath());
        output.addProperty("type", Files.isDirectory(resolved.path()) ? "directory" : "file");
        output.addProperty("readable", Files.isReadable(resolved.path()));
        output.addProperty("writable", resolved.workspace().writable() && Files.isWritable(resolved.path()));
        output.addProperty("modifiedAtMillis", Files.getLastModifiedTime(resolved.path()).toMillis());
        if (Files.isRegularFile(resolved.path())) {
            long bytes = Files.size(resolved.path());
            output.addProperty("bytes", bytes);
            if (bytes <= MAXIMUM_FILE_BYTES) {
                byte[] content = Files.readAllBytes(resolved.path());
                output.addProperty("contentHash", sha256(content));
                output.addProperty("text", isText(content));
                if (isText(content)) {
                    output.addProperty("lines", lineCount(new String(content, StandardCharsets.UTF_8)));
                }
            } else {
                output.addProperty("hashOmitted", true);
            }
        }
        return completed(call, output, "Workspace metadata was inspected.");
    }

    private static ModelToolResult search(ModelToolCall call) throws IOException {
        JsonObject arguments = call.arguments();
        String query = requiredString(arguments, "query");
        boolean caseSensitive = optionalBoolean(arguments, "caseSensitive", false);
        String matchMode = enumValue(arguments, "matchMode", "literal", Set.of("literal", "word", "regex"));
        String outputMode = enumValue(arguments, "outputMode", "lines", Set.of("lines", "matches", "files", "count"));
        String fileGlob = optionalString(arguments, "fileGlob", "").strip();
        int contextBefore = boundedInt(arguments, "contextBefore", 0, 0, 3);
        int contextAfter = boundedInt(arguments, "contextAfter", 0, 0, 3);
        int maximumFiles = boundedInt(arguments, "maxFiles", 200, 1, MAXIMUM_SEARCH_FILES);
        int requestedResults = boundedInt(arguments, "maxResults", 20, 1, MAXIMUM_SEARCH_RESULTS);
        int effectiveResults = "lines".equals(outputMode)
                ? Math.max(1, Math.min(requestedResults,
                MAXIMUM_SEARCH_RESULTS / Math.max(1, 1 + contextBefore + contextAfter)))
                : requestedResults;
        ModelWorkspaceRegistry.ResolvedPath resolved = ModelWorkspaceRegistry.resolve(
                workspaceString(arguments, "workspace"),
                optionalString(arguments, "path", ""),
                false
        );
        if (!Files.isDirectory(resolved.path())) {
            throw new IOException("Search target is not a directory.");
        }
        Pattern pattern = searchPattern(query, matchMode, caseSensitive);
        PathMatcher pathMatcher = fileGlob.isBlank()
                ? null
                : resolved.path().getFileSystem().getPathMatcher("glob:" + fileGlob);
        JsonArray matches = new JsonArray();
        int scanned = 0;
        int matchedFiles = 0;
        int totalMatches = 0;
        boolean resultLimitReached = false;
        boolean fileLimitReached;
        try (var paths = Files.walk(resolved.path())) {
            List<Path> candidates = paths
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(Files::isRegularFile)
                    .filter(path -> pathMatcher == null || pathMatcher.matches(resolved.path().relativize(path)))
                    .sorted()
                    .limit((long) maximumFiles + 1L)
                    .toList();
            fileLimitReached = candidates.size() > maximumFiles;
            int candidateCount = Math.min(maximumFiles, candidates.size());
            for (int candidateIndex = 0; candidateIndex < candidateCount; candidateIndex++) {
                Path path = candidates.get(candidateIndex);
                scanned++;
                if (Files.size(path) > MAXIMUM_FILE_BYTES) {
                    continue;
                }
                List<String> lines;
                try {
                    lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                } catch (IOException | RuntimeException ignored) {
                    continue;
                }
                String relativePath = resolved.workspace().root().relativize(path)
                        .toString().replace('\\', '/');
                int fileMatches = 0;
                for (int index = 0; index < lines.size(); index++) {
                    Matcher matcher = pattern.matcher(lines.get(index));
                    if (!matcher.find()) {
                        continue;
                    }
                    int lineMatches = 0;
                    do {
                        if (matcher.start() == matcher.end()) {
                            throw new IOException("Search regular expression must not produce empty matches.");
                        }
                        lineMatches++;
                        fileMatches++;
                        totalMatches++;
                        if ("matches".equals(outputMode) && matches.size() < effectiveResults) {
                            JsonObject match = baseSearchMatch(relativePath, index + 1);
                            match.addProperty("columnStart", matcher.start() + 1);
                            match.addProperty("columnEnd", matcher.end());
                            match.addProperty("match", abbreviate(matcher.group(), 300));
                            matches.add(match);
                        }
                    } while (matcher.find());

                    if ("lines".equals(outputMode) && matches.size() < effectiveResults) {
                        JsonObject match = baseSearchMatch(relativePath, index + 1);
                        match.addProperty("matchCount", lineMatches);
                        match.addProperty("text", abbreviate(lines.get(index), 300));
                        appendContext(match, "before", lines,
                                Math.max(0, index - contextBefore), index);
                        appendContext(match, "after", lines,
                                index + 1, Math.min(lines.size(), index + 1 + contextAfter));
                        matches.add(match);
                    }
                    if (("lines".equals(outputMode) || "matches".equals(outputMode))
                            && matches.size() >= effectiveResults) {
                        resultLimitReached = true;
                        break;
                    }
                }
                if (fileMatches > 0) {
                    matchedFiles++;
                    if ("files".equals(outputMode)) {
                        if (matches.size() < effectiveResults) {
                            JsonObject match = new JsonObject();
                            match.addProperty("path", relativePath);
                            match.addProperty("matchCount", fileMatches);
                            matches.add(match);
                        } else {
                            resultLimitReached = true;
                        }
                    }
                }
                if (resultLimitReached && !"count".equals(outputMode)) {
                    break;
                }
            }
        }
        JsonObject output = new JsonObject();
        output.addProperty("workspace", resolved.workspace().id());
        output.addProperty("path", resolved.relativePath());
        output.addProperty("query", query);
        output.addProperty("matchMode", matchMode);
        output.addProperty("outputMode", outputMode);
        if (!fileGlob.isBlank()) output.addProperty("fileGlob", fileGlob);
        output.addProperty("scannedFiles", scanned);
        output.addProperty("matchedFiles", matchedFiles);
        output.addProperty("totalMatches", totalMatches);
        output.addProperty("resultsReturned", matches.size());
        boolean truncated = fileLimitReached || resultLimitReached;
        output.addProperty("complete", !truncated);
        output.addProperty("truncated", truncated);
        if (!"count".equals(outputMode)) output.add("matches", matches);
        return completed(call, output, "Selective workspace text search completed.");
    }

    private static Pattern searchPattern(String query, String matchMode, boolean caseSensitive) throws IOException {
        String expression = switch (matchMode) {
            case "word" -> "(?<![\\p{L}\\p{N}_])" + Pattern.quote(query) + "(?![\\p{L}\\p{N}_])";
            case "regex" -> query;
            default -> Pattern.quote(query);
        };
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        try {
            Pattern pattern = Pattern.compile(expression, flags);
            if (pattern.matcher("").find()) {
                throw new IOException("Search expression must not match empty text.");
            }
            return pattern;
        } catch (PatternSyntaxException invalid) {
            throw new IOException("Invalid search regular expression: " + invalid.getDescription());
        }
    }

    private static JsonObject baseSearchMatch(String path, int line) {
        JsonObject match = new JsonObject();
        match.addProperty("path", path);
        match.addProperty("line", line);
        return match;
    }

    private static void appendContext(JsonObject match, String key, List<String> lines, int from, int to) {
        if (from >= to) return;
        JsonArray context = new JsonArray();
        for (int index = from; index < to; index++) {
            JsonObject line = new JsonObject();
            line.addProperty("line", index + 1);
            line.addProperty("text", abbreviate(lines.get(index), 300));
            context.add(line);
        }
        match.add(key, context);
    }

    private static ModelToolResult mkdir(ModelToolCall call) throws IOException {
        JsonObject arguments = call.arguments();
        ModelWorkspaceRegistry.ResolvedPath resolved = ModelWorkspaceRegistry.resolve(
                workspaceString(arguments, "workspace"),
                requiredString(arguments, "path"),
                true
        );
        if (resolved.relativePath().isBlank()) {
            throw new IOException("A directory path is required.");
        }
        if (Files.exists(resolved.path())) {
            throw new IOException("Directory or file already exists.");
        }
        Files.createDirectories(resolved.path());
        if (!Files.isDirectory(resolved.path())) {
            throw new IOException("Directory creation could not be verified.");
        }
        JsonObject output = new JsonObject();
        output.addProperty("workspace", resolved.workspace().id());
        output.addProperty("path", resolved.relativePath());
        output.addProperty("operation", "directory_created");
        output.addProperty("filesystemState", "directory");
        output.addProperty("reread", true);
        output.addProperty("validationStatus", "passed");
        return new ModelToolResult(
                call.id(), call.toolId(), "completed", output, "",
                "Workspace directory was created and verified.",
                System.currentTimeMillis(), System.currentTimeMillis(), "passed",
                List.of(resolved.workspace().id() + ":" + resolved.relativePath()),
                false, false, "approved"
        );
    }

    private static ModelToolResult create(ModelToolCall call) throws IOException {
        JsonObject arguments = call.arguments();
        ModelWorkspaceRegistry.ResolvedPath resolved = writableFile(arguments);
        if (Files.exists(resolved.path())) {
            throw new IOException("File already exists; use workspace.write or workspace.replace.");
        }
        String content = boundedContent(arguments);
        byte[] previous = new byte[0];
        atomicWrite(resolved.path(), content, false);
        return mutationResult(call, resolved, "created", previous, content.getBytes(StandardCharsets.UTF_8), true, "created");
    }

    private static ModelToolResult write(ModelToolCall call) throws IOException {
        JsonObject arguments = call.arguments();
        ModelWorkspaceRegistry.ResolvedPath resolved = writableFile(arguments);
        if (!Files.isRegularFile(resolved.path())) {
            throw new IOException("File does not exist; use workspace.create.");
        }
        byte[] previous = Files.readAllBytes(resolved.path());
        requireExpectedHash(arguments, previous);
        String content = boundedContent(arguments);
        atomicWrite(resolved.path(), content, true);
        return mutationResult(call, resolved, "modified", previous, content.getBytes(StandardCharsets.UTF_8), true, "written");
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
        byte[] previousBytes = Files.readAllBytes(resolved.path());
        requireExpectedHash(arguments, previousBytes);
        String content = new String(previousBytes, StandardCharsets.UTF_8);
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
        return mutationResult(call, resolved, "modified", previousBytes, updated.getBytes(StandardCharsets.UTF_8), true, "replaced");
    }

    private static ModelToolResult copy(ModelToolCall call) throws IOException {
        JsonObject arguments = call.arguments();
        ModelWorkspaceRegistry.ResolvedPath source = sourceFile(arguments);
        ModelWorkspaceRegistry.ResolvedPath destination = destinationFile(arguments);
        rejectSameTarget(source, destination);
        if (Files.exists(destination.path())) {
            throw new IOException("Copy destination already exists; no file was changed.");
        }
        byte[] content = Files.readAllBytes(source.path());
        requireExpectedHash(arguments, content);
        atomicWrite(destination.path(), new String(content, StandardCharsets.UTF_8), false);
        byte[] sourceAfter = Files.readAllBytes(source.path());
        if (!MessageDigest.isEqual(content, sourceAfter)) {
            Files.deleteIfExists(destination.path());
            throw new StaleFileException(sha256(content), sha256(sourceAfter));
        }
        ModelToolResult result = mutationResult(
                call, destination, "copied", new byte[0], content, true, "created"
        );
        result.output().addProperty("sourceWorkspace", source.workspace().id());
        result.output().addProperty("sourcePath", source.relativePath());
        result.output().addProperty("sourceContentHash", sha256(content));
        result.output().addProperty("sourceUnchanged", true);
        return result;
    }

    private static ModelToolResult move(ModelToolCall call) throws IOException {
        JsonObject arguments = call.arguments();
        ModelWorkspaceRegistry.ResolvedPath source = sourceFile(arguments);
        ModelWorkspaceRegistry.ResolvedPath destination = destinationFile(arguments);
        rejectSameTarget(source, destination);
        if (Files.exists(destination.path())) {
            throw new IOException("Move destination already exists; no file was changed.");
        }
        byte[] content = Files.readAllBytes(source.path());
        requireExpectedHash(arguments, content);
        Files.createDirectories(destination.path().getParent());
        try {
            Files.move(source.path(), destination.path(), StandardCopyOption.ATOMIC_MOVE);
        } catch (UnsupportedOperationException | IOException unsupported) {
            Files.move(source.path(), destination.path());
        }
        try {
            if (Files.exists(source.path()) || !Files.isRegularFile(destination.path())
                    || !MessageDigest.isEqual(content, Files.readAllBytes(destination.path()))) {
                throw new IOException("Post-move filesystem validation failed.");
            }
        } catch (IOException validationFailure) {
            if (!Files.exists(source.path()) && Files.exists(destination.path())) {
                try {
                    Files.move(destination.path(), source.path());
                } catch (IOException rollbackFailure) {
                    validationFailure.addSuppressed(rollbackFailure);
                }
            }
            throw validationFailure;
        }
        ModelToolResult mutation = mutationResult(
                call, destination, "moved", new byte[0], content, true, "created"
        );
        JsonObject output = mutation.output();
        output.addProperty("sourceWorkspace", source.workspace().id());
        output.addProperty("sourcePath", source.relativePath());
        output.addProperty("sourceRemoved", true);
        output.addProperty("sourceContentHash", sha256(content));
        return new ModelToolResult(
                call.id(), call.toolId(), "completed", output, "",
                "Workspace file was moved and both source and destination were verified.",
                mutation.startedAtMillis(), System.currentTimeMillis(), "passed",
                List.of(
                        source.workspace().id() + ":" + source.relativePath(),
                        destination.workspace().id() + ":" + destination.relativePath()
                ), false, false, "approved"
        );
    }

    private static ModelToolResult delete(ModelToolCall call) throws IOException {
        JsonObject arguments = call.arguments();
        ModelWorkspaceRegistry.ResolvedPath resolved = writableFile(arguments);
        if (!Files.isRegularFile(resolved.path())) {
            throw new IOException("Only individual regular files can be removed.");
        }
        byte[] previous = Files.readAllBytes(resolved.path());
        requireExpectedHash(arguments, previous);
        String recoveryToken = UUID.randomUUID().toString();
        Path trashDirectory = TRASH_ROOT.toAbsolutePath().normalize().resolve(recoveryToken);
        Files.createDirectories(trashDirectory);
        Path trashed = trashDirectory.resolve(resolved.path().getFileName().toString());
        try {
            Files.move(resolved.path(), trashed, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unsupported) {
            Files.move(resolved.path(), trashed);
        }
        ModelToolResult mutation = mutationResult(call, resolved, "deleted", previous, new byte[0], true, "deleted");
        JsonObject output = mutation.output();
        output.addProperty("recoverable", true);
        output.addProperty("recoveryToken", recoveryToken);
        return new ModelToolResult(
                call.id(), call.toolId(), "completed", output, "",
                "File was moved to Koil's recoverable model trash.",
                mutation.startedAtMillis(), System.currentTimeMillis(), "passed",
                List.of(resolved.workspace().id() + ":" + resolved.relativePath()),
                false, false, "approved"
        );
    }

    private static ModelToolResult restore(ModelToolCall call) throws IOException {
        JsonObject arguments = call.arguments();
        ModelWorkspaceRegistry.ResolvedPath resolved = writableFile(arguments);
        if (Files.exists(resolved.path())) {
            throw new IOException("Restore target already exists; no file was changed.");
        }
        String token = requiredString(arguments, "recoveryToken");
        if (!token.matches("[0-9a-fA-F-]{36}")) {
            throw new IOException("Invalid recovery token.");
        }
        Path directory = TRASH_ROOT.toAbsolutePath().normalize().resolve(token).normalize();
        if (!directory.startsWith(TRASH_ROOT.toAbsolutePath().normalize()) || !Files.isDirectory(directory)) {
            throw new IOException("Recovery token was not found.");
        }
        List<Path> files;
        try (var paths = Files.list(directory)) {
            files = paths.filter(Files::isRegularFile).limit(2).toList();
        }
        if (files.size() != 1) {
            throw new IOException("Recovery token is ambiguous or empty.");
        }
        byte[] restored = Files.readAllBytes(files.get(0));
        Files.createDirectories(resolved.path().getParent());
        try {
            Files.move(files.get(0), resolved.path(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unsupported) {
            Files.move(files.get(0), resolved.path());
        }
        Files.deleteIfExists(directory);
        return mutationResult(call, resolved, "restored", new byte[0], restored, true, "restored");
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
                workspaceString(arguments, "workspace"),
                requiredString(arguments, "path"),
                true
        );
        if (resolved.relativePath().isBlank()) {
            throw new IOException("A file path is required.");
        }
        return resolved;
    }

    private static ModelWorkspaceRegistry.ResolvedPath sourceFile(JsonObject arguments) throws IOException {
        ModelWorkspaceRegistry.ResolvedPath source = ModelWorkspaceRegistry.resolve(
                workspaceString(arguments, "workspace"),
                requiredString(arguments, "path"),
                true
        );
        requireTextFile(source.path());
        return source;
    }

    private static ModelWorkspaceRegistry.ResolvedPath destinationFile(JsonObject arguments) throws IOException {
        ModelWorkspaceRegistry.ResolvedPath destination = ModelWorkspaceRegistry.resolve(
                workspaceString(arguments, "destinationWorkspace"),
                requiredString(arguments, "destinationPath"),
                true
        );
        if (destination.relativePath().isBlank()) {
            throw new IOException("A destination file path is required.");
        }
        return destination;
    }

    private static void rejectSameTarget(
            ModelWorkspaceRegistry.ResolvedPath source,
            ModelWorkspaceRegistry.ResolvedPath destination
    ) throws IOException {
        if (source.path().toAbsolutePath().normalize().equals(destination.path().toAbsolutePath().normalize())) {
            throw new IOException("Source and destination must be different files.");
        }
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
        if (!isText(probe)) {
            throw new IOException("Binary files are not exposed as text.");
        }
    }

    private static boolean isText(byte[] content) {
        for (byte value : content) {
            if (value == 0) return false;
        }
        return true;
    }

    private static int lineCount(String content) {
        if (content == null || content.isEmpty()) return 0;
        return content.split("\\n", -1).length;
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
            byte[] previous,
            byte[] resulting,
            boolean reread,
            String filesystemState
    ) throws IOException {
        long started = System.currentTimeMillis();
        byte[] actual = Files.isRegularFile(resolved.path()) ? Files.readAllBytes(resolved.path()) : new byte[0];
        if (!MessageDigest.isEqual(resulting, actual)) {
            throw new IOException("Post-mutation reread did not match the intended content.");
        }
        DiffSummary diff = diff(
                new String(previous, StandardCharsets.UTF_8),
                new String(resulting, StandardCharsets.UTF_8),
                resolved.relativePath()
        );
        JsonObject output = new JsonObject();
        output.addProperty("workspace", resolved.workspace().id());
        output.addProperty("path", resolved.relativePath());
        output.addProperty("operation", operation);
        output.addProperty("previousContentHash", sha256(previous));
        output.addProperty("resultingContentHash", sha256(resulting));
        output.addProperty("linesAdded", diff.linesAdded());
        output.addProperty("linesRemoved", diff.linesRemoved());
        output.add("diffHunks", diff.hunks());
        output.addProperty("diffTruncated", diff.truncated());
        output.addProperty("reread", reread);
        output.addProperty("filesystemState", filesystemState);
        output.addProperty("validationStatus", "passed");
        return new ModelToolResult(
                call.id(), call.toolId(), "completed", output, "",
                "Workspace file change completed atomically and was reread.",
                started, System.currentTimeMillis(), "passed",
                List.of(resolved.workspace().id() + ":" + resolved.relativePath()),
                false, false, "approved"
        );
    }

    private static void requireExpectedHash(JsonObject arguments, byte[] previous) throws IOException {
        String expected = requiredString(arguments, "expectedHash");
        String actual = sha256(previous);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new StaleFileException(expected, actual);
        }
    }

    private static String sha256(byte[] content) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable.", impossible);
        }
    }

    private static DiffSummary diff(String before, String after, String path) {
        List<String> oldLines = List.of(before.split("\\n", -1));
        List<String> newLines = List.of(after.split("\\n", -1));
        int prefix = 0;
        while (prefix < oldLines.size() && prefix < newLines.size()
                && oldLines.get(prefix).equals(newLines.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < oldLines.size() - prefix && suffix < newLines.size() - prefix
                && oldLines.get(oldLines.size() - 1 - suffix).equals(newLines.get(newLines.size() - 1 - suffix))) {
            suffix++;
        }
        int oldChanged = oldLines.size() - prefix - suffix;
        int newChanged = newLines.size() - prefix - suffix;
        JsonArray hunks = new JsonArray();
        if (oldChanged == 0 && newChanged == 0) {
            return new DiffSummary(0, 0, hunks, false);
        }
        JsonObject hunk = new JsonObject();
        hunk.addProperty("header", "@@ -" + (prefix + 1) + "," + oldChanged
                + " +" + (prefix + 1) + "," + newChanged + " @@");
        JsonArray lines = new JsonArray();
        int contextStart = Math.max(0, prefix - 3);
        for (int i = contextStart; i < prefix; i++) {
            lines.add(" " + oldLines.get(i));
        }
        boolean truncated = false;
        int chars = 0;
        for (int i = prefix; i < oldLines.size() - suffix; i++) {
            String line = "-" + oldLines.get(i);
            if ((chars += line.length()) > MAXIMUM_TOOL_TEXT_CHARS) { truncated = true; break; }
            lines.add(line);
        }
        if (!truncated) {
            for (int i = prefix; i < newLines.size() - suffix; i++) {
                String line = "+" + newLines.get(i);
                if ((chars += line.length()) > MAXIMUM_TOOL_TEXT_CHARS) { truncated = true; break; }
                lines.add(line);
            }
        }
        if (!truncated) {
            for (int i = oldLines.size() - suffix; i < Math.min(oldLines.size(), oldLines.size() - suffix + 3); i++) {
                lines.add(" " + oldLines.get(i));
            }
        }
        hunk.add("lines", lines);
        hunks.add(hunk);
        return new DiffSummary(newChanged, oldChanged, hunks, truncated);
    }

    private record DiffSummary(int linesAdded, int linesRemoved, JsonArray hunks, boolean truncated) {}

    private static final class StaleFileException extends IOException {
        private final String expected;
        private final String actual;
        private final long startedAt = System.currentTimeMillis();

        private StaleFileException(String expected, String actual) {
            super("File changed after it was read; reread it before retrying.");
            this.expected = expected;
            this.actual = actual;
        }
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
        String destinationWorkspace = optionalString(arguments, "destinationWorkspace", "");
        String destinationPath = optionalString(arguments, "destinationPath", "");
        String destination = destinationWorkspace.isBlank() || destinationPath.isBlank()
                ? ""
                : "\nDestination: " + destinationWorkspace + ":" + destinationPath;
        return call.toolId() + " requested for " + workspace + ":" + path + destination
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
                "List files and directories under a bounded named workspace path. Omit workspace to use instance; valid roots are instance, automation, and project when available.",
                objectSchema(Map.of(
                        "workspace", stringSchema(),
                        "path", stringSchema(),
                        "depth", integerSchema(1, 4)
                ), List.of()),
                false,
                Set.of("reads_file_metadata")
        ));
        definitions.put("workspace.stat", definition(
                "workspace.stat",
                "Inspect one permitted file or directory: type, size, revision hash when bounded, modification time, and access state.",
                objectSchema(Map.of(
                        "workspace", stringSchema(),
                        "path", stringSchema()
                ), List.of()),
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
                ), List.of("path")),
                false,
                Set.of("reads_file")
        ));
        definitions.put("workspace.search", definition(
                "workspace.search",
                "Selectively search bounded UTF-8 workspace files. Narrow by path/fileGlob and request only exact matches, matching lines, file names, or counts; add context only when it is needed.",
                searchSchema(),
                false,
                Set.of("reads_files")
        ));
        definitions.put("workspace.mkdir", definition(
                "workspace.mkdir",
                "Create and verify one bounded directory path inside a named writable workspace.",
                objectSchema(Map.of(
                        "workspace", stringSchema(),
                        "path", stringSchema()
                ), List.of("path")),
                true,
                Set.of("creates_directory")
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
                "Atomically replace an existing permitted UTF-8 text file only when expectedHash matches its latest read revision.",
                mutationSchema(true),
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
                        "expectedOccurrences", integerSchema(1, 1000),
                        "expectedHash", hashSchema()
                ), List.of("path", "find", "expectedHash")),
                true,
                Set.of("changes_file")
        ));
        definitions.put("workspace.copy", definition(
                "workspace.copy",
                "Copy one permitted UTF-8 file to a new bounded destination after verifying the source expectedHash; returns a real destination diff and reread evidence.",
                transferSchema(),
                true,
                Set.of("creates_file")
        ));
        definitions.put("workspace.move", definition(
                "workspace.move",
                "Move or rename one permitted UTF-8 file to a new bounded destination after verifying the source expectedHash and both filesystem states.",
                transferSchema(),
                true,
                Set.of("moves_file")
        ));
        definitions.put("workspace.delete", definition(
                "workspace.delete",
                "Move one regular file to Koil's recoverable model trash.",
                objectSchema(Map.of(
                        "workspace", stringSchema(),
                        "path", stringSchema(),
                        "expectedHash", hashSchema()
                ), List.of("path", "expectedHash")),
                true,
                Set.of("moves_file_to_trash")
        ));
        definitions.put("workspace.restore", definition(
                "workspace.restore",
                "Restore one recoverably deleted file using its opaque recovery token; fails if the target now exists.",
                objectSchema(Map.of(
                        "workspace", stringSchema(),
                        "path", stringSchema(),
                        "recoveryToken", stringSchema()
                ), List.of("path", "recoveryToken")),
                true,
                Set.of("restores_file")
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
        boolean mutating = MUTATING_TOOLS.contains(id);
        List<String> preconditions = "workspace.roots".equals(id)
                ? List.of()
                : mutating
                ? List.of("automation_mode_enabled", "path_inside_named_workspace")
                : List.of("path_inside_named_workspace");
        return new ModelToolDefinition(
                id,
                description,
                schema,
                preconditions,
                sideEffects,
                "workspace.delete".equals(id),
                Duration.ofSeconds(30),
                false,
                confirmation,
                mutating
                        ? Set.of("completed", "rejected", "stale", "failed")
                        : Set.of("completed", "failed")
        );
    }

    private static JsonObject mutationSchema(boolean existingFile) {
        Map<String, JsonObject> properties = new LinkedHashMap<>();
        properties.put("workspace", stringSchema());
        properties.put("path", stringSchema());
        properties.put("content", stringSchema());
        if (existingFile) {
            properties.put("expectedHash", hashSchema());
        }
        List<String> required = new ArrayList<>(List.of("path", "content"));
        if (existingFile) {
            required.add("expectedHash");
        }
        return objectSchema(properties, required);
    }

    private static JsonObject transferSchema() {
        return objectSchema(Map.of(
                "workspace", stringSchema(),
                "path", stringSchema(),
                "destinationWorkspace", stringSchema(),
                "destinationPath", stringSchema(),
                "expectedHash", hashSchema()
        ), List.of("path", "destinationPath", "expectedHash"));
    }

    private static JsonObject searchSchema() {
        Map<String, JsonObject> properties = new LinkedHashMap<>();
        properties.put("workspace", described(stringSchema(), "Named workspace; omit for the Minecraft instance root."));
        properties.put("path", described(stringSchema(), "Directory to search, relative to the named workspace. Narrow this whenever known."));
        properties.put("query", described(stringSchema(), "Only the text or regular expression actually needed."));
        properties.put("caseSensitive", booleanSchema());
        properties.put("matchMode", enumSchema("literal", "word", "regex"));
        properties.put("outputMode", described(enumSchema("lines", "matches", "files", "count"),
                "lines returns matching lines; matches returns only exact matched text and columns; files returns paths/counts; count returns totals only."));
        properties.put("fileGlob", described(stringSchema(), "Optional relative glob such as **/*.java or config/*.json."));
        properties.put("contextBefore", integerSchema(0, 3));
        properties.put("contextAfter", integerSchema(0, 3));
        properties.put("maxResults", integerSchema(1, MAXIMUM_SEARCH_RESULTS));
        properties.put("maxFiles", integerSchema(1, MAXIMUM_SEARCH_FILES));
        return objectSchema(properties, List.of("query"));
    }

    private static JsonObject hashSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("pattern", "^[0-9a-fA-F]{64}$");
        return schema;
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

    private static JsonObject enumSchema(String... values) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        JsonArray allowed = new JsonArray();
        for (String value : values) allowed.add(value);
        schema.add("enum", allowed);
        return schema;
    }

    private static JsonObject described(JsonObject schema, String description) {
        schema.addProperty("description", description);
        return schema;
    }

    private static String requiredString(JsonObject root, String key) throws IOException {
        String value = optionalString(root, key, "");
        if (value.isBlank()) {
            throw new IOException("Missing required argument '" + key + "'.");
        }
        return value;
    }

    private static String workspaceString(JsonObject root, String key) {
        return optionalString(root, key, "instance");
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

    private static String enumValue(JsonObject root, String key, String fallback, Set<String> allowed) throws IOException {
        String value = optionalString(root, key, fallback).toLowerCase(Locale.ROOT).strip();
        if (!allowed.contains(value)) {
            throw new IOException("Argument '" + key + "' must be one of " + allowed + ".");
        }
        return value;
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
