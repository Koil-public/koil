package com.spirit.koil.api.model.codeintelligence.codebasememory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.model.codeintelligence.CodeIntelligenceHealth;
import com.spirit.koil.api.mcp.McpStdioClient;
import com.spirit.koil.api.model.codeintelligence.CodeIntelligenceOperation;
import com.spirit.koil.api.model.codeintelligence.CodeIntelligenceProvider;
import com.spirit.koil.api.model.codeintelligence.CodeIntelligenceRequest;
import com.spirit.koil.api.model.codeintelligence.CodeIntelligenceResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Translates stable Koil operations to the discovered, pinned Codebase Memory MCP surface. */
public final class CodebaseMemoryProvider implements CodeIntelligenceProvider {
    public static final String ID = "codebase_memory";
    public static final Set<String> REQUIRED_TOOLS = Set.of(
        "index_repository", "list_projects", "search_graph", "search_code", "trace_path",
        "detect_changes", "query_graph", "get_graph_schema", "get_code_snippet", "get_architecture"
    );
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration INDEX_TIMEOUT = Duration.ofMinutes(10);

    private final CodebaseMemoryRuntimeManager runtime;
    private final Set<Path> indexedRoots = ConcurrentHashMap.newKeySet();
    private final Map<Path, String> projectNames = new ConcurrentHashMap<>();

    public CodebaseMemoryProvider(CodebaseMemoryRuntimeManager runtime) {
        this.runtime = runtime;
    }

    public CompletableFuture<CodeIntelligenceHealth> start() {
        return runtime == null
            ? CompletableFuture.completedFuture(health())
            : runtime.start();
    }

    public CompletableFuture<CodeIntelligenceHealth> retest() {
        return runtime == null
            ? CompletableFuture.completedFuture(health())
            : runtime.retest();
    }

    @Override
    public CompletableFuture<CodeIntelligenceResult> query(CodeIntelligenceRequest request) {
        if (request == null) return CompletableFuture.completedFuture(CodeIntelligenceResult.unavailable(ID, "invalid_request", "Code intelligence request is missing."));
        if (runtime == null || runtime.health().state() != CodeIntelligenceHealth.State.READY) {
            return CompletableFuture.completedFuture(CodeIntelligenceResult.unavailable(ID, "provider_not_ready", runtime == null ? "Codebase Memory is unavailable." : runtime.health().detail()));
        }
        Path root;
        try {
            root = canonicalRoot(request.workspaceRoot());
        } catch (IOException failure) {
            return CompletableFuture.completedFuture(CodeIntelligenceResult.unavailable(ID, "workspace_unavailable", failure.getMessage()));
        }
        return ensureIndexed(root).thenCompose(project -> call(root, project, request));
    }

    /** Requests a bounded incremental sync after a Koil-owned successful mutation. */
    public CompletableFuture<Void> refresh(Path workspaceRoot) {
        if (runtime == null || runtime.health().state() != CodeIntelligenceHealth.State.READY) return CompletableFuture.completedFuture(null);
        try {
            Path root = canonicalRoot(workspaceRoot);
            if (!indexedRoots.contains(root)) return CompletableFuture.completedFuture(null);
            JsonObject args = new JsonObject();
            args.addProperty("repo_path", root.toString());
            args.addProperty("mode", "fast");
            args.addProperty("persistence", true);
            return runtime.callTool("index_repository", args, INDEX_TIMEOUT).handle((result, failure) -> null);
        } catch (IOException ignored) {
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public CodeIntelligenceHealth health() {
        return runtime == null ? new CodeIntelligenceHealth(CodeIntelligenceHealth.State.NOT_INSTALLED, ID, "Codebase Memory runtime is unavailable.") : runtime.health();
    }

    @Override
    public void close() {
        if (runtime != null) runtime.close();
    }

    private CompletableFuture<String> ensureIndexed(Path root) {
        String known = projectNames.get(root);
        if (indexedRoots.contains(root) && known != null && !known.isBlank()) return CompletableFuture.completedFuture(known);
        JsonObject args = new JsonObject();
        args.addProperty("repo_path", root.toString());
        args.addProperty("mode", "moderate");
        args.addProperty("persistence", true);
        return runtime.callTool("index_repository", args, INDEX_TIMEOUT).thenCompose(result -> {
            if (toolError(result)) throw new McpStdioClient.McpException("index_failed", text(result, 24_000));
            String project = projectFromIndexResult(result);
            CompletableFuture<String> resolved = project.isBlank() ? resolveProjectName(root) : CompletableFuture.completedFuture(project);
            return resolved.thenApply(name -> {
                if (name == null || name.isBlank()) throw new McpStdioClient.McpException(
                        "project_resolution_failed", "Codebase Memory indexed the workspace but did not expose a project name for it.");
                indexedRoots.add(root);
                projectNames.put(root, name);
                return name;
            });
        });
    }

    private CompletableFuture<String> resolveProjectName(Path root) {
        return runtime.callTool("list_projects", new JsonObject(), QUERY_TIMEOUT).thenApply(result -> {
            String content = text(result, 64_000);
            try {
                JsonObject parsed = JsonParser.parseString(content).getAsJsonObject();
                if (parsed.has("projects") && parsed.get("projects").isJsonArray()) {
                    for (JsonElement element : parsed.getAsJsonArray("projects")) {
                        if (!element.isJsonObject()) continue;
                        JsonObject project = element.getAsJsonObject();
                        String path = project.has("root_path") ? project.get("root_path").getAsString() : "";
                        if (!path.isBlank()) {
                            try {
                                if (Path.of(path).toAbsolutePath().normalize().equals(root)) {
                                    return project.has("name") ? project.get("name").getAsString() : "";
                                }
                            } catch (RuntimeException ignored) { }
                        }
                    }
                }
            } catch (RuntimeException ignored) { }
            Matcher match = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"[^}]*\"root_path\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
            while (match.find()) {
                try {
                    if (Path.of(match.group(2)).toAbsolutePath().normalize().equals(root)) return match.group(1);
                } catch (RuntimeException ignored) { }
            }
            return "";
        });
    }

    private static String projectFromIndexResult(JsonObject result) {
        String content = text(result, 16_000);
        try {
            JsonObject parsed = JsonParser.parseString(content).getAsJsonObject();
            return parsed.has("project") && parsed.get("project").isJsonPrimitive() ? parsed.get("project").getAsString() : "";
        } catch (RuntimeException ignored) {
            Matcher match = Pattern.compile("\"project\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
            return match.find() ? match.group(1) : "";
        }
    }

    private CompletableFuture<CodeIntelligenceResult> call(Path root, String project, CodeIntelligenceRequest request) {
        String tool = toolFor(request.operation());
        if (!runtime.supports(tool)) return CompletableFuture.completedFuture(CodeIntelligenceResult.unavailable(ID, "mcp_tool_unavailable", "Codebase Memory does not expose " + tool + "."));
        JsonObject args = arguments(project, request);
        Duration timeout = request.operation() == CodeIntelligenceOperation.IMPACT || request.operation() == CodeIntelligenceOperation.CHANGES
            ? Duration.ofSeconds(45) : QUERY_TIMEOUT;
        CompletableFuture<JsonObject> response = request.operation() == CodeIntelligenceOperation.TRACE
            ? resolveTraceTarget(project, request).thenCompose(name -> { args.addProperty("function_name", name); return runtime.callTool(tool, args, timeout); })
            : runtime.callTool(tool, args, timeout);
        return response.handle((result, failure) -> {
            if (failure != null) return CodeIntelligenceResult.unavailable(ID, errorCode(failure), message(failure));
            String text = text(result, request.maximumCharacters());
            JsonObject data = new JsonObject();
            data.addProperty("workspace", root.toString());
            data.addProperty("project", project);
            data.addProperty("externalTool", tool);
            data.addProperty("providerVersion", runtime.serverVersion());
            data.addProperty("text", text);
            data.addProperty("resultCount", result != null && result.has("content") && result.get("content").isJsonArray()
                ? result.getAsJsonArray("content").size() : 0);
            data.addProperty("sourceIsUntrustedEvidence", true);
            data.addProperty("externalError", toolError(result));
            boolean truncated = text.length() >= request.maximumCharacters();
            return new CodeIntelligenceResult(toolError(result) ? "failed" : "completed", data, ID,
                toolError(result) ? "codebase_memory_tool_error" : "", toolError(result) ? text : "Codebase Memory query completed.", truncated);
        });
    }

    /** CBM trace_path wants the fully qualified graph name; resolve a short model-facing symbol first. */
    private CompletableFuture<String> resolveTraceTarget(String project, CodeIntelligenceRequest request) {
        JsonObject lookup = new JsonObject();
        lookup.addProperty("project", project);
        lookup.addProperty("query", request.query());
        lookup.addProperty("limit", 5);
        lookup.addProperty("max_output_tokens", 1_000);
        return runtime.callTool("search_graph", lookup, QUERY_TIMEOUT).thenApply(result -> {
            String output = text(result, 4_000);
            Matcher match = Pattern.compile("(?m)^\\s*([^\\s]+)\\s+(?:Method|Function)\\s+").matcher(output);
            return match.find() ? match.group(1) : request.query();
        });
    }

    private static JsonObject arguments(String project, CodeIntelligenceRequest request) {
        JsonObject args = new JsonObject();
        args.addProperty("project", project);
        args.addProperty("limit", request.limit());
        args.addProperty("max_output_tokens", Math.max(128, request.maximumCharacters() / 4));
        switch (request.operation()) {
            case ARCHITECTURE -> { JsonArray aspects = new JsonArray(); aspects.add("overview"); aspects.add("packages"); aspects.add("entry_points"); aspects.add("hotspots"); aspects.add("boundaries"); aspects.add("clusters"); args.add("aspects", aspects); }
            // search_graph's BM25 path is portable across CBM regex engines and ranks exact symbols above broad semantic matches.
            case SYMBOLS -> args.addProperty("query", request.query());
            case SEARCH -> { args.addProperty("pattern", request.query()); args.addProperty("regex", false); args.addProperty("result_limit", request.limit()); }
            case SEMANTIC_SEARCH -> { JsonArray semantic = new JsonArray(); semantic.add(request.query()); args.add("semantic_query", semantic); args.addProperty("semantic_limit", request.limit()); }
            case TRACE -> { args.addProperty("function_name", request.query()); args.addProperty("direction", "both"); args.addProperty("depth", request.depth()); }
            case IMPACT -> { args.addProperty("scope", "branch"); args.addProperty("base_branch", request.query()); args.addProperty("depth", request.depth()); }
            case CHANGES -> { args.addProperty("scope", "all"); args.addProperty("depth", request.depth()); }
            case SNIPPET -> { args.addProperty("qualified_name", request.query()); args.addProperty("source_mode", "auto"); args.addProperty("max_lines", Math.min(500, Math.max(20, request.maximumCharacters() / 80))); }
            case SCHEMA -> { }
            case QUERY -> { requireReadOnlyQuery(request.query()); args.addProperty("query", request.query()); args.addProperty("max_rows", request.limit()); }
        }
        return args;
    }

    private static void requireReadOnlyQuery(String query) {
        String value = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        if (value.isBlank() || !value.matches("(?s)^\\s*(match|with|return)\\b.*") || value.matches("(?s).*\\b(create|merge|delete|set|remove|drop|load|call)\\b.*")) {
            throw new IllegalArgumentException("code.query accepts only read-only MATCH/WITH/RETURN graph queries.");
        }
    }

    private static String toolFor(CodeIntelligenceOperation operation) {
        return switch (operation) {
            case ARCHITECTURE -> "get_architecture";
            case SYMBOLS, SEMANTIC_SEARCH -> "search_graph";
            case SEARCH -> "search_code";
            case TRACE -> "trace_path";
            case IMPACT, CHANGES -> "detect_changes";
            case SNIPPET -> "get_code_snippet";
            case SCHEMA -> "get_graph_schema";
            case QUERY -> "query_graph";
        };
    }

    private static Path canonicalRoot(Path root) throws IOException {
        if (root == null || !Files.isDirectory(root)) throw new IOException("Workspace root is not an accessible directory.");
        return root.toRealPath();
    }

    private static boolean toolError(JsonObject result) {
        return result != null && result.has("isError") && result.get("isError").getAsBoolean();
    }

    private static String text(JsonObject result, int maximum) {
        StringBuilder value = new StringBuilder();
        if (result != null && result.has("content") && result.get("content").isJsonArray()) {
            for (JsonElement entry : result.getAsJsonArray("content")) {
                if (!entry.isJsonObject() || !entry.getAsJsonObject().has("text")) continue;
                String next = entry.getAsJsonObject().get("text").getAsString();
                if (value.length() + next.length() > maximum) { value.append(next, 0, Math.max(0, maximum - value.length())); break; }
                value.append(next);
            }
        }
        return value.toString();
    }

    private static String errorCode(Throwable failure) {
        Throwable current = failure.getCause() == null ? failure : failure.getCause();
        return current instanceof McpStdioClient.McpException mcp ? mcp.code() : "codebase_memory_failed";
    }

    private static String message(Throwable failure) {
        Throwable current = failure.getCause() == null ? failure : failure.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
