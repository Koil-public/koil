package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ToolExecutionPolicy;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Bounded process/build/environment tools rooted strictly inside named workspaces. */
public final class WorkspaceExecutionModelToolRegistry {
    public static final String EXEC_TOOL_ID = "workspace.exec";
    public static final String BUILD_TOOL_ID = "workspace.build";
    public static final String ENV_TOOL_ID = "workspace.environment";
    private static final int MAX_OUTPUT_BYTES = 48 * 1024;
    private static final List<ModelToolDefinition> DEFINITIONS = definitions();

    private WorkspaceExecutionModelToolRegistry() {}
    public static String version() { return "workspace-execution-tools-v1"; }
    public static List<ModelToolDefinition> modelTools() { return DEFINITIONS; }
    public static boolean supports(String id) { return EXEC_TOOL_ID.equals(id) || BUILD_TOOL_ID.equals(id) || ENV_TOOL_ID.equals(id); }

    public static CompletableFuture<ModelToolResult> execute(UUID displayRequestId, ModelToolCall call, boolean preapproved) {
        if (call == null || !supports(call.toolId())) return CompletableFuture.completedFuture(failed(call, "unknown_tool", "Unknown workspace execution capability."));
        if (!AutomationModeController.isAutomationMode()) return CompletableFuture.completedFuture(failed(call, "automation_disabled", "Automation Mode must be enabled before executing local processes."));
        CompletableFuture<Boolean> approval;
        if (preapproved || AutomationModeController.isUnrestrictedMode()) {
            approval = CompletableFuture.completedFuture(true);
        } else if (displayRequestId == null) {
            return CompletableFuture.completedFuture(failed(call, "approval_unavailable", "Process execution requires the model-panel approval surface."));
        } else {
            approval = ModelGenerationHudState.requestApproval(displayRequestId, "Workspace execution approval", approvalDetail(call), "Run", "Deny");
        }
        return approval.thenCompose(approved -> {
            if (!approved) return CompletableFuture.completedFuture(new ModelToolResult(call.id(), call.toolId(), "rejected", new JsonObject(), "user_declined", "The player declined local process execution."));
            return CompletableFuture.supplyAsync(() -> {
                try { return executeBlocking(call); }
                catch (Exception failure) { return failed(call, "workspace_execution_failed", rootMessage(failure)); }
            });
        });
    }

    private static ModelToolResult executeBlocking(ModelToolCall call) throws Exception {
        return switch (call.toolId()) {
            case EXEC_TOOL_ID -> executeProcess(call);
            case BUILD_TOOL_ID -> executeBuild(call);
            case ENV_TOOL_ID -> environment(call);
            default -> failed(call, "unknown_tool", "Unknown workspace execution capability.");
        };
    }

    private static ModelToolResult executeProcess(ModelToolCall call) throws Exception {
        JsonObject args = args(call);
        String workspace = string(args, "workspace", "project");
        String cwdValue = string(args, "cwd", "");
        Path cwd = directory(workspace, cwdValue);
        List<String> command;
        String file = string(args, "path", "");
        String program = string(args, "program", "");
        List<String> suppliedArgs = stringArray(args, "args");
        if (!file.isBlank()) {
            ModelWorkspaceRegistry.ResolvedPath source = ModelWorkspaceRegistry.resolve(workspace, file, false);
            if (!Files.isRegularFile(source.path())) throw new IOException("Executable path is not a regular file.");
            command = commandForFile(source.path(), cwd, string(args, "runtime", "auto"), string(args, "environment", ".venv"), suppliedArgs);
        } else if (!program.isBlank()) {
            command = new ArrayList<>();
            command.add(resolveProgram(workspace, cwd, program));
            command.addAll(suppliedArgs);
        } else {
            throw new IllegalArgumentException("workspace.exec requires either path or program.");
        }
        if (bool(args, "background", false)) {
            String processId = WorkspaceProcessService.start(cwd, command, sanitizedEnvironment());
            JsonObject output = new JsonObject();
            output.addProperty("workspace", workspace);
            output.addProperty("cwd", cwd.toString());
            JsonArray argv = new JsonArray(); command.forEach(argv::add); output.add("argv", argv);
            output.addProperty("background", true);
            output.addProperty("processId", processId);
            output.addProperty("running", true);
            return completed(call, output, "Process started in the managed background process registry.");
        }
        return run(call, workspace, cwd, command, integer(args, "timeoutSeconds", 120, 1, 600), "exec");
    }

    private static ModelToolResult executeBuild(ModelToolCall call) throws Exception {
        JsonObject args = args(call);
        String workspace = string(args, "workspace", "project");
        Path cwd = directory(workspace, string(args, "cwd", ""));
        String system = string(args, "system", "auto").toLowerCase(Locale.ROOT);
        List<String> tasks = stringArray(args, "tasks");
        List<String> command = buildCommand(cwd, system, tasks);
        return run(call, workspace, cwd, command, integer(args, "timeoutSeconds", 300, 1, 900), "build");
    }

    private static ModelToolResult environment(ModelToolCall call) throws Exception {
        JsonObject args = args(call);
        String workspace = string(args, "workspace", "project");
        String operation = string(args, "operation", "inspect").toLowerCase(Locale.ROOT);
        String kind = string(args, "kind", "python_venv").toLowerCase(Locale.ROOT);
        if (!"python_venv".equals(kind)) return failed(call, "unsupported_environment", "Only python_venv is currently a managed environment kind; workspace.exec can run other installed runtimes directly.");
        String pathValue = string(args, "path", ".venv");
        ModelWorkspaceRegistry.ResolvedPath env = ModelWorkspaceRegistry.resolve(workspace, pathValue, true);
        JsonObject output = new JsonObject();
        output.addProperty("workspace", env.workspace().id());
        output.addProperty("path", env.relativePath());
        output.addProperty("kind", kind);
        if ("inspect".equals(operation)) {
            boolean exists = Files.isDirectory(env.path());
            output.addProperty("exists", exists);
            Path python = venvPython(env.path());
            output.addProperty("pythonAvailable", Files.isRegularFile(python));
            if (Files.isRegularFile(python)) output.addProperty("python", env.path().relativize(python).toString());
            return completed(call, output, "Inspected managed execution environment.");
        }
        if (!"create".equals(operation)) return failed(call, "unsupported_operation", "workspace.environment operation must be inspect or create.");
        if (Files.exists(env.path())) {
            if (!Files.isDirectory(env.path())) return failed(call, "environment_path_conflict", "Environment path exists but is not a directory.");
            output.addProperty("exists", true);
            output.addProperty("alreadyPresent", true);
            return completed(call, output, "Environment directory already exists; no process was started.");
        }
        Path parent = env.path().getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("Environment parent directory must already exist.");
        String python = string(args, "python", "python3");
        List<String> command = List.of(python, "-m", "venv", env.path().toAbsolutePath().toString());
        ModelToolResult result = run(call, workspace, parent, command, integer(args, "timeoutSeconds", 180, 1, 600), "environment_create");
        if ("completed".equals(result.status())) {
            result.output().addProperty("environmentPath", env.relativePath());
            result.output().addProperty("kind", kind);
        }
        return result;
    }

    private static List<String> buildCommand(Path cwd, String system, List<String> tasks) throws IOException {
        String selected = system;
        if ("auto".equals(selected)) {
            if (Files.isRegularFile(cwd.resolve("gradlew"))) selected = "gradle";
            else if (Files.isRegularFile(cwd.resolve("mvnw"))) selected = "maven";
            else if (Files.isRegularFile(cwd.resolve("package.json"))) selected = "npm";
            else if (Files.isRegularFile(cwd.resolve("Cargo.toml"))) selected = "cargo";
            else if (Files.isRegularFile(cwd.resolve("go.mod"))) selected = "go";
            else if (Files.isRegularFile(cwd.resolve("Makefile")) || Files.isRegularFile(cwd.resolve("makefile"))) selected = "make";
            else if (Files.isRegularFile(cwd.resolve("build.gradle")) || Files.isRegularFile(cwd.resolve("build.gradle.kts"))) selected = "gradle";
            else if (Files.isRegularFile(cwd.resolve("pom.xml"))) selected = "maven";
            else if (containsProjectSuffix(cwd, ".sln", ".csproj")) selected = "dotnet";
            else throw new IOException("No supported build system was detected in the requested cwd.");
        }
        ArrayList<String> command = new ArrayList<>();
        switch (selected) {
            case "gradle" -> {
                if (Files.isRegularFile(cwd.resolve("gradlew"))) { command.add("bash"); command.add(cwd.resolve("gradlew").toString()); }
                else command.add("gradle");
                command.addAll(tasks.isEmpty() ? List.of("build") : tasks);
            }
            case "maven" -> {
                if (Files.isRegularFile(cwd.resolve("mvnw"))) { command.add("bash"); command.add(cwd.resolve("mvnw").toString()); }
                else command.add("mvn");
                command.addAll(tasks.isEmpty() ? List.of("test") : tasks);
            }
            case "npm" -> {
                command.add("npm"); command.add("run"); command.addAll(tasks.isEmpty() ? List.of("build") : tasks);
            }
            case "cargo" -> {
                command.add("cargo"); command.addAll(tasks.isEmpty() ? List.of("build") : tasks);
            }
            case "go" -> {
                command.add("go"); command.addAll(tasks.isEmpty() ? List.of("build", "./...") : tasks);
            }
            case "make" -> {
                command.add("make"); command.addAll(tasks);
            }
            case "dotnet" -> {
                command.add("dotnet"); command.addAll(tasks.isEmpty() ? List.of("build") : tasks);
            }
            default -> throw new IllegalArgumentException("system must be auto, gradle, maven, npm, cargo, go, make, or dotnet.");
        }
        return List.copyOf(command);
    }

    private static boolean containsProjectSuffix(Path cwd, String... suffixes) throws IOException {
        try (var paths = Files.list(cwd)) {
            return paths.anyMatch(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                for (String suffix : suffixes) if (name.endsWith(suffix.toLowerCase(Locale.ROOT))) return true;
                return false;
            });
        }
    }

    private static List<String> commandForFile(Path source, Path cwd, String runtime, String environment, List<String> args) {
        String selected = runtime == null || runtime.isBlank() ? "auto" : runtime.toLowerCase(Locale.ROOT);
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if ("auto".equals(selected)) {
            if (name.endsWith(".py")) selected = "python";
            else if (name.endsWith(".sh")) selected = "bash";
            else if (name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs")) selected = "node";
            else if (name.endsWith(".jar")) selected = "java_jar";
            else if (name.endsWith(".java")) selected = "java_source";
            else if (name.endsWith(".rb")) selected = "ruby";
            else if (name.endsWith(".php")) selected = "php";
            else if (name.endsWith(".pl")) selected = "perl";
            else if (name.endsWith(".lua")) selected = "lua";
            else selected = "direct";
        }
        ArrayList<String> command = new ArrayList<>();
        switch (selected) {
            case "python" -> {
                Path env = cwd.resolve(environment == null || environment.isBlank() ? ".venv" : environment).normalize();
                Path python = venvPython(env);
                command.add(Files.isRegularFile(python) ? python.toString() : "python3");
                command.add(source.toString());
            }
            case "bash" -> { command.add("bash"); command.add(source.toString()); }
            case "node" -> { command.add("node"); command.add(source.toString()); }
            case "java_jar" -> { command.add("java"); command.add("-jar"); command.add(source.toString()); }
            case "java_source" -> { command.add("java"); command.add(source.toString()); }
            case "ruby" -> { command.add("ruby"); command.add(source.toString()); }
            case "php" -> { command.add("php"); command.add(source.toString()); }
            case "perl" -> { command.add("perl"); command.add(source.toString()); }
            case "lua" -> { command.add("lua"); command.add(source.toString()); }
            case "direct" -> command.add(source.toString());
            default -> throw new IllegalArgumentException("runtime must be auto, python, bash, node, java_jar, java_source, ruby, php, perl, lua, or direct.");
        }
        command.addAll(args);
        return List.copyOf(command);
    }

    private static String resolveProgram(String workspace, Path cwd, String program) throws IOException {
        if (program.contains("/") || program.contains("\\")) {
            Path candidate = cwd.resolve(program).normalize();
            Path root = ModelWorkspaceRegistry.resolve(workspace, "", false).path();
            if (!candidate.startsWith(root)) throw new IOException("Program path escapes the selected workspace.");
            if (!Files.isRegularFile(candidate)) throw new IOException("Program path does not exist inside the workspace.");
            return candidate.toString();
        }
        return program;
    }

    private static Path directory(String workspace, String cwdValue) throws IOException {
        ModelWorkspaceRegistry.ResolvedPath resolved = ModelWorkspaceRegistry.resolve(workspace, cwdValue, false);
        if (!Files.isDirectory(resolved.path())) throw new IOException("cwd is not a directory inside the selected workspace.");
        return resolved.path();
    }

    private static ModelToolResult run(ModelToolCall call, String workspace, Path cwd, List<String> command, int timeoutSeconds, String operation) throws Exception {
        long started = System.currentTimeMillis();
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        // Avoid leaking common parent-process secrets into child tools. Keep PATH/JAVA_HOME/etc.
        builder.environment().keySet().removeIf(WorkspaceExecutionModelToolRegistry::sensitiveEnvironmentName);
        Process process = builder.start();
        try { process.getOutputStream().close(); } catch (IOException ignored) {}
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> readBounded(process.getInputStream()));
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readBounded(process.getErrorStream()));
        boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) {
            process.destroy();
            if (!process.waitFor(750, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        }
        String out = stdout.get(2, TimeUnit.SECONDS);
        String err = stderr.get(2, TimeUnit.SECONDS);
        JsonObject output = new JsonObject();
        output.addProperty("operation", operation);
        output.addProperty("workspace", workspace);
        output.addProperty("cwd", cwd.toString());
        JsonArray argv = new JsonArray(); command.forEach(argv::add); output.add("argv", argv);
        output.addProperty("stdout", out);
        output.addProperty("stderr", err);
        output.addProperty("timedOut", !exited);
        output.addProperty("durationMs", Math.max(0L, System.currentTimeMillis() - started));
        if (!exited) return new ModelToolResult(call.id(), call.toolId(), "failed", output, "process_timeout", "Process exceeded the bounded timeout and was terminated.");
        int exit = process.exitValue();
        output.addProperty("exitCode", exit);
        if (exit != 0) return new ModelToolResult(call.id(), call.toolId(), "failed", output, "process_exit_nonzero", "Process exited with code " + exit + ".");
        return completed(call, output, "Process completed successfully.");
    }

    private static String readBounded(InputStream input) {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            while (true) {
                int read = in.read(buffer);
                if (read < 0) break;
                int accepted = Math.min(read, Math.max(0, MAX_OUTPUT_BYTES - total));
                if (accepted > 0) out.write(buffer, 0, accepted);
                total += read;
            }
            String value = out.toString(StandardCharsets.UTF_8);
            return total > MAX_OUTPUT_BYTES ? value + "\n[output truncated by Koil]" : value;
        } catch (IOException failure) {
            return "[stream read failed: " + rootMessage(failure) + "]";
        }
    }


    private static java.util.Map<String,String> sanitizedEnvironment() {
        java.util.HashMap<String,String> env = new java.util.HashMap<>(System.getenv());
        env.keySet().removeIf(WorkspaceExecutionModelToolRegistry::sensitiveEnvironmentName);
        return env;
    }

    private static boolean sensitiveEnvironmentName(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return value.contains("token") || value.contains("secret") || value.contains("password")
                || value.contains("credential") || value.contains("apikey") || value.contains("api_key")
                || value.equals("aws_access_key_id") || value.equals("aws_secret_access_key")
                || value.equals("github_token") || value.equals("gh_token");
    }

    private static Path venvPython(Path root) {
        Path unix = root.resolve("bin/python");
        if (Files.isRegularFile(unix)) return unix;
        return root.resolve("Scripts/python.exe");
    }

    private static List<ModelToolDefinition> definitions() {
        ArrayList<ModelToolDefinition> tools = new ArrayList<>();
        JsonObject exec = object(); JsonObject ep = props(exec);
        ep.add("workspace", stringSchema()); ep.add("cwd", stringSchema()); ep.add("path", stringSchema()); ep.add("program", stringSchema());
        ep.add("runtime", enumSchema("auto", "python", "bash", "node", "java_jar", "java_source", "ruby", "php", "perl", "lua", "direct")); ep.add("environment", stringSchema()); ep.add("args", stringArraySchema()); ep.add("timeoutSeconds", integerSchema(1, 600)); ep.add("background", booleanSchema());
        tools.add(definition(EXEC_TOOL_ID,
                "Run a code/script/binary file or installed executable inside a named workspace without implicit shell parsing. runtime=auto recognizes Python, shell scripts, JavaScript, JAR, Java source-file mode, Ruby, PHP, Perl, Lua, and direct executables; program+args supports other installed runtimes/CLIs. Set background=true for dev servers or long-running jobs, then use process.status/process.output/process.stop. Foreground output and runtime are bounded.", exec));

        JsonObject build = object(); JsonObject bp = props(build);
        bp.add("workspace", stringSchema()); bp.add("cwd", stringSchema()); bp.add("system", enumSchema("auto", "gradle", "maven", "npm", "cargo", "go", "make", "dotnet")); bp.add("tasks", stringArraySchema()); bp.add("timeoutSeconds", integerSchema(1, 900));
        tools.add(definition(BUILD_TOOL_ID,
                "Build or test a project inside a named workspace. system=auto detects Gradle/gradlew, Maven/mvnw, npm, Cargo, Go, Make, or .NET. Use tasks for exact build tasks such as compileJava, test, build, or custom targets.", build));

        JsonObject env = object(); JsonObject vp = props(env);
        vp.add("workspace", stringSchema()); vp.add("operation", enumSchema("inspect", "create")); vp.add("kind", enumSchema("python_venv")); vp.add("path", stringSchema()); vp.add("python", stringSchema()); vp.add("timeoutSeconds", integerSchema(1, 600));
        tools.add(definition(ENV_TOOL_ID,
                "Inspect or create an isolated Python virtual environment inside a named workspace. After creation, workspace.exec runtime=python automatically prefers the requested .venv when present.", env));
        return List.copyOf(tools);
    }

    private static ModelToolDefinition definition(String id, String description, JsonObject schema) {
        return new ModelToolDefinition(id, description, schema,
                List.of("automation_mode_enabled", "path_inside_named_workspace"), Set.of("executes_local_process"), false,
                Duration.ofMinutes(15), true, true, Set.of("completed", "failed", "rejected"),
                ToolExecutionPolicy.validateOnlyMutation(ToolExecutionPolicy.CostClass.EXPENSIVE));
    }

    private static String approvalDetail(ModelToolCall call) {
        JsonObject args = args(call);
        return call.toolId() + " in workspace=" + string(args, "workspace", "project")
                + " cwd=" + string(args, "cwd", "")
                + "\nThe process is bounded to that workspace for file-path arguments, receives no stdin, has bounded output/time, and common secret-bearing environment variables are removed.";
    }

    private static JsonObject args(ModelToolCall call) { return call.arguments() == null ? new JsonObject() : call.arguments(); }
    private static JsonObject object() { JsonObject schema = new JsonObject(); schema.addProperty("type", "object"); schema.addProperty("additionalProperties", false); schema.add("properties", new JsonObject()); return schema; }
    private static JsonObject props(JsonObject schema) { return schema.getAsJsonObject("properties"); }
    private static JsonObject booleanSchema() { JsonObject value = new JsonObject(); value.addProperty("type", "boolean"); return value; }
    private static boolean bool(JsonObject args, String key, boolean fallback) { return args != null && args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsBoolean() : fallback; }
    private static JsonObject stringSchema() { JsonObject value = new JsonObject(); value.addProperty("type", "string"); return value; }
    private static JsonObject integerSchema(int min, int max) { JsonObject value = new JsonObject(); value.addProperty("type", "integer"); value.addProperty("minimum", min); value.addProperty("maximum", max); return value; }
    private static JsonObject enumSchema(String... values) { JsonObject value = stringSchema(); JsonArray allowed = new JsonArray(); for (String item : values) allowed.add(item); value.add("enum", allowed); return value; }
    private static JsonObject stringArraySchema() { JsonObject value = new JsonObject(); value.addProperty("type", "array"); value.add("items", stringSchema()); value.addProperty("maxItems", 64); return value; }
    private static String string(JsonObject args, String key, String fallback) { return args != null && args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString().strip() : fallback; }
    private static int integer(JsonObject args, String key, int fallback, int min, int max) { return args != null && args.has(key) ? Math.max(min, Math.min(max, args.get(key).getAsInt())) : fallback; }
    private static List<String> stringArray(JsonObject args, String key) { if (args == null || !args.has(key) || !args.get(key).isJsonArray()) return List.of(); ArrayList<String> values = new ArrayList<>(); for (JsonElement item : args.getAsJsonArray(key)) if (!item.isJsonNull()) values.add(item.getAsString()); return List.copyOf(values); }
    private static ModelToolResult completed(ModelToolCall call, JsonObject output, String detail) { return new ModelToolResult(call.id(), call.toolId(), "completed", output, "", detail); }
    private static ModelToolResult failed(ModelToolCall call, String code, String detail) { return new ModelToolResult(call == null ? "" : call.id(), call == null ? "" : call.toolId(), "failed", new JsonObject(), code, detail == null ? "" : detail); }
    private static String rootMessage(Throwable failure) { Throwable cause = failure; while (cause.getCause() != null) cause = cause.getCause(); return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(); }
}
