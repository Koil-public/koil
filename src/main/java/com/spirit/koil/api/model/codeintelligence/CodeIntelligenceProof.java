package com.spirit.koil.api.model.codeintelligence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.model.LocalModelSystemPrompt;
import com.spirit.koil.api.model.LocalModelCommandBridge;
import com.spirit.koil.api.model.prompt.LocalModelAutomationPrompt;
import com.spirit.koil.api.mcp.McpStdioClient;
import com.spirit.koil.api.mcp.McpRuntimeManager;
import com.spirit.koil.api.model.codeintelligence.codebasememory.CodebaseMemoryRuntimeManager;
import com.spirit.koil.api.model.tool.DeepThoughtReadOnlyToolCoordinator;
import com.spirit.koil.api.model.tool.CodeIntelligenceModelToolRegistry;
import com.spirit.koil.api.model.tool.LocalModelToolCatalog;
import com.spirit.koil.api.model.tool.DynamicMcpToolRegistry;
import com.spirit.koil.api.model.tool.ModelWorkspaceRegistry;
import com.spirit.koil.api.model.chat.ModelToolActivityPresentation;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletionException;
import java.util.UUID;
import java.util.Map;

/** Minimal executable proof for provider truthfulness and request bounds. */
public final class CodeIntelligenceProof {
    private CodeIntelligenceProof() {
    }

    public static void main(String[] args) throws Exception {
        fallbackDoesNotInventTrace();
        requestIdsDoNotCross();
        readyRequiresNegotiationAndRequiredTools();
        missingRequiredToolsDegrade();
        unexpectedProcessExitRestartsOnce();
        dynamicMcpToolsRegisterAndUnregisterWithTheSharedRuntime();
        modelCatalogKeepsCodeToolsProviderNeutralAndReadOnly();
        malformedAndTimedOutTransportFailsClearly();
        cancellationAbandonsLateTransportResult();
        currentCodeEvidenceOutranksHistoricalMemory();
        automationUsesGraphBeforeAndAfterCodeMutation();
        provenanceIsBoundedAndSourceSafe();
        rejectedCodeCallsStillHaveProvenance();
        diagnosticStatusIsConciseAndProtocolFree();
        freshCodeRequestsPreferProjectWorkspace();
    }

    private static void readyRequiresNegotiationAndRequiredTools() {
        try (CodebaseMemoryRuntimeManager runtime = new CodebaseMemoryRuntimeManager(
            () -> new FakeMcpProcess(Set.of("search_graph", "list_projects")),
            Set.of("search_graph", "list_projects")
        )) {
            CodeIntelligenceHealth health = runtime.start().join();
            require(health.state() == CodeIntelligenceHealth.State.READY, health);
            require(runtime.tools().contains("search_graph"), runtime.tools());
        }
    }

    private static void missingRequiredToolsDegrade() {
        try (CodebaseMemoryRuntimeManager runtime = new CodebaseMemoryRuntimeManager(
            () -> new FakeMcpProcess(Set.of("list_projects")), Set.of("search_graph", "list_projects")
        )) {
            CodeIntelligenceHealth health = runtime.start().join();
            require(health.state() == CodeIntelligenceHealth.State.DEGRADED && health.detail().contains("search_graph"), health);
        }
    }

    private static void malformedAndTimedOutTransportFailsClearly() throws Exception {
        try (PipedInputStream stdout = new PipedInputStream(); PipedOutputStream server = new PipedOutputStream(stdout);
             McpStdioClient client = new McpStdioClient(stdout, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())) {
            var malformed = client.request("tools/list", new JsonObject(), Duration.ofSeconds(1));
            server.write("not-json\n".getBytes());
            server.flush();
            require("mcp_malformed_response".equals(failureCode(malformed)), "malformed stdout was not contained");
        }
        try (PipedInputStream stdout = new PipedInputStream(); PipedOutputStream server = new PipedOutputStream(stdout);
             McpStdioClient client = new McpStdioClient(stdout, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())) {
            require("mcp_timeout".equals(failureCode(client.request("tools/list", new JsonObject(), Duration.ofMillis(20)))),
                "unresponsive MCP did not time out");
        }
    }

    private static void cancellationAbandonsLateTransportResult() throws Exception {
        try (PipedInputStream stdout = new PipedInputStream(); PipedOutputStream server = new PipedOutputStream(stdout);
             McpStdioClient client = new McpStdioClient(stdout, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())) {
            var pending = client.request("tools/list", new JsonObject(), Duration.ofSeconds(1));
            require(pending.cancel(true), "MCP request did not accept cancellation");
            server.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}\n".getBytes());
            server.flush();
            require(pending.isCancelled(), "late MCP response revived a cancelled request");
        }
    }

    private static String failureCode(CompletableFuture<?> future) {
        try {
            future.join();
            return "";
        } catch (CompletionException failure) {
            return failure.getCause() instanceof McpStdioClient.McpException mcp ? mcp.code() : "";
        }
    }

    private static void unexpectedProcessExitRestartsOnce() throws Exception {
        List<FakeMcpProcess> processes = new CopyOnWriteArrayList<>();
        try (CodebaseMemoryRuntimeManager runtime = new CodebaseMemoryRuntimeManager(() -> {
            FakeMcpProcess process = new FakeMcpProcess(Set.of("search_graph", "list_projects"));
            processes.add(process);
            return process;
        }, Set.of("search_graph", "list_projects"))) {
            require(runtime.start().join().state() == CodeIntelligenceHealth.State.READY, runtime.health());
            processes.get(0).destroy();
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while ((processes.size() < 2 || runtime.health().state() != CodeIntelligenceHealth.State.READY)
                    && System.nanoTime() < deadline) Thread.sleep(10L);
            require(processes.size() == 2 && runtime.health().state() == CodeIntelligenceHealth.State.READY, runtime.health());
        }
    }

    private static void dynamicMcpToolsRegisterAndUnregisterWithTheSharedRuntime() {
        String provider = "proof.dynamic-provider";
        try (McpRuntimeManager runtime = new McpRuntimeManager(provider,
                () -> new FakeMcpProcess(Set.of("inspect_schema")), Set.of("inspect_schema"))) {
            String before = LocalModelToolCatalog.version();
            require(DynamicMcpToolRegistry.register(provider, runtime).join().state()
                    == com.spirit.koil.api.mcp.McpRuntimeHealth.State.READY, runtime.health());
            String toolId = DynamicMcpToolRegistry.modelTools().stream().findFirst()
                    .orElseThrow(() -> new AssertionError("shared runtime did not register a dynamic model tool")).id();
            require(LocalModelToolCatalog.definition(toolId).isPresent(), "dynamic MCP tool is absent from the central model catalog");
            require(!before.equals(LocalModelToolCatalog.version()), "dynamic tool revision did not invalidate the capability source snapshot");
            require(DynamicMcpToolRegistry.execute(new com.spirit.koil.api.model.ModelToolCall(
                    "dynamic-proof", toolId, new JsonObject())).join().status().equals("completed"),
                    "dynamic MCP tool did not execute through the shared runtime");
            DynamicMcpToolRegistry.unregister(provider, runtime);
            require(LocalModelToolCatalog.definition(toolId).isEmpty(), "removed MCP tool remained callable in the model catalog");
        }
    }

    private static void requestIdsDoNotCross() throws Exception {
        try (PipedInputStream stdout = new PipedInputStream();
             PipedOutputStream server = new PipedOutputStream(stdout);
             McpStdioClient client = new McpStdioClient(
                 stdout, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()
             )) {
            var first = client.request("tools/list", new JsonObject(), Duration.ofSeconds(1));
            var second = client.request("tools/list", new JsonObject(), Duration.ofSeconds(1));
            server.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"second\"}]}}\n".getBytes());
            server.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"first\"}]}}\n".getBytes());
            server.flush();
            require("first".equals(first.join().getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString()), first);
            require("second".equals(second.join().getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString()), second);
        }
    }

    private static void modelCatalogKeepsCodeToolsProviderNeutralAndReadOnly() {
        require(LocalModelToolCatalog.automationModeTools().stream().anyMatch(tool -> "code.trace".equals(tool.id())), "code.trace missing from model catalog");
        require(LocalModelToolCatalog.informationToolsForPrompt("trace callers for a code symbol").stream().anyMatch(tool -> "code.trace".equals(tool.id())), "code.trace missing from read-only model selection");
        require(DeepThoughtReadOnlyToolCoordinator.supports("code.trace"), "Deep Thought code trace must remain read-only");
        require("Tracing callers and dependencies".equals(ModelToolActivityPresentation.activity("code.trace", "ignored").detail()), "code trace activity presentation");
    }

    private static void currentCodeEvidenceOutranksHistoricalMemory() {
        String contract = LocalModelSystemPrompt.operatingContract();
        require(contract.contains("historical memory")
                && contract.contains("current code intelligence")
                && contract.contains("untrusted data"),
            "model operating contract lost current-code evidence precedence");
    }

    private static void automationUsesGraphBeforeAndAfterCodeMutation() {
        String rules = LocalModelAutomationPrompt.rules(false, false);
        require(rules.contains("code.architecture")
                && rules.contains("code.impact")
                && rules.contains("code.changes"),
            "Automation contract lost the code-intelligence edit workflow");
    }

    private static void provenanceIsBoundedAndSourceSafe() {
        CodeIntelligenceProvenance.clear();
        CodeIntelligenceProvenance.record(new CodeIntelligenceProvenance.Entry(
            "request-1", "session-1", "/workspace", "code.trace", "trace_path",
            "workspace=koil, queryLength=16", 10L, 18L, "completed", 2,
            false, "READY", "codebase_memory", "0.11.0"
        ));
        var entries = CodeIntelligenceProvenance.recent();
        require(entries.size() == 1 && "request-1".equals(entries.get(0).requestId())
                && !entries.get(0).argumentSummary().contains("secret source"),
            "code-intelligence provenance is missing or stores raw source");
    }

    private static void rejectedCodeCallsStillHaveProvenance() {
        CodeIntelligenceProvenance.clear();
        UUID session = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CodeIntelligenceModelToolRegistry.execute(session,
            new com.spirit.koil.api.model.ModelToolCall("request-2", "code.unknown", new JsonObject())).join();
        var entries = CodeIntelligenceProvenance.recent();
        require(entries.size() == 1 && session.toString().equals(entries.get(0).sessionId())
                && "unsupported".equals(entries.get(0).status()),
            "rejected code call bypassed provenance");
    }

    private static void diagnosticStatusIsConciseAndProtocolFree() {
        String status = LocalModelCommandBridge.codeIntelligenceStatus();
        require(status.startsWith("Code intelligence: ") && !status.toLowerCase(java.util.Locale.ROOT).contains("json-rpc"),
            "code-intelligence diagnostic status exposes protocol details or is missing");
    }

    private static void freshCodeRequestsPreferProjectWorkspace() {
        Map<String, ModelWorkspaceRegistry.Workspace> roots = Map.of(
            "instance", new ModelWorkspaceRegistry.Workspace("instance", Path.of("/instance"), true, ""),
            "project", new ModelWorkspaceRegistry.Workspace("project", Path.of("/project"), true, "")
        );
        require("project".equals(ModelWorkspaceRegistry.preferredCodeWorkspaceId("", roots))
                && "project".equals(ModelWorkspaceRegistry.preferredCodeWorkspaceId("source", roots))
                && "instance".equals(ModelWorkspaceRegistry.preferredCodeWorkspaceId("instance", roots)),
            "fresh code requests did not prefer the detected project workspace");
    }

    private static void fallbackDoesNotInventTrace() throws Exception {
        Path root = Files.createTempDirectory("koil-code-intelligence-proof-");
        try {
            CodeIntelligenceProvider provider = new BasicWorkspaceCodeProvider();
            CodeIntelligenceResult result = provider.query(new CodeIntelligenceRequest(
                CodeIntelligenceOperation.TRACE, root, "submitGeneration", 500, 99, 100_000
            )).join();
            require("unavailable".equals(result.status()), result);
            require("basic_workspace_no_call_graph".equals(result.failureCode()), result);
        } finally {
            Files.deleteIfExists(root);
        }
    }

    private static void require(boolean condition, Object detail) {
        if (!condition) throw new AssertionError(detail);
    }

    private static final class FakeMcpProcess extends Process {
        private final PipedInputStream stdout = new PipedInputStream();
        private final PipedOutputStream serverOutput;
        private final PipedInputStream serverInput = new PipedInputStream();
        private final PipedOutputStream stdin;
        private final Set<String> tools;
        private final CompletableFuture<Process> exited = new CompletableFuture<>();
        private volatile boolean alive = true;

        private FakeMcpProcess(Set<String> tools) {
            try {
                this.serverOutput = new PipedOutputStream(stdout);
                this.stdin = new PipedOutputStream(serverInput);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            this.tools = tools;
            Thread server = new Thread(this::serve, "koil-fake-mcp-server");
            server.setDaemon(true);
            server.start();
        }

        private void serve() {
            try (Reader reader = new java.io.InputStreamReader(serverInput);
                 BufferedReader lines = new BufferedReader(reader);
                 Writer writer = new OutputStreamWriter(serverOutput)) {
                String line;
                while ((line = lines.readLine()) != null) {
                    JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                    if (!request.has("id")) continue;
                    long id = request.get("id").getAsLong();
                    String method = request.get("method").getAsString();
                    String result = "initialize".equals(method)
                        ? "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fake\",\"version\":\"test\"}}"
                        : "{\"tools\":[" + tools.stream().sorted().map(name -> "{\"name\":\"" + name + "\"}").collect(java.util.stream.Collectors.joining(",")) + "]}";
                    writer.write("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}\n");
                    writer.flush();
                }
            } catch (Exception ignored) {
                // Test-process shutdown closes the pipe.
            } finally {
                alive = false;
                exited.complete(this);
            }
        }

        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return exited.join().exitValue(); }
        @Override public CompletableFuture<Process> onExit() { return exited; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { alive = false; try { stdin.close(); } catch (Exception ignored) { } }
        @Override public boolean isAlive() { return alive; }
    }
}
