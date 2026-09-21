package com.spirit.koil.api.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.Writer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/** Executable transport/lifecycle proof for Koil's shared MCP runtime. */
public final class McpRuntimeProof {
    private McpRuntimeProof() { }

    public static void main(String[] args) throws Exception {
        everyDiscoveredToolExecutes();
        sequentialMultiToolChainExecutes();
        timeoutForcesCleanRecovery();
    }

    private static void everyDiscoveredToolExecutes() {
        Set<String> tools = Set.of("alpha", "beta", "gamma");
        try (McpRuntimeManager runtime = new McpRuntimeManager("proof-all-tools", () -> new FakeProcess(tools, false), tools)) {
            require(runtime.start().join().state() == McpRuntimeHealth.State.READY, runtime.health());
            for (String tool : tools) {
                JsonObject result = runtime.callTool(tool, new JsonObject(), Duration.ofSeconds(1)).join();
                require(tool.equals(result.get("tool").getAsString()), result);
            }
        }
    }

    private static void sequentialMultiToolChainExecutes() {
        Set<String> tools = Set.of("first", "second", "third");
        try (McpRuntimeManager runtime = new McpRuntimeManager("proof-chain", () -> new FakeProcess(tools, false), tools)) {
            runtime.start().join();
            JsonObject result = runtime.callTool("first", new JsonObject(), Duration.ofSeconds(1))
                    .thenCompose(first -> runtime.callTool("second", first, Duration.ofSeconds(1)))
                    .thenCompose(second -> runtime.callTool("third", second, Duration.ofSeconds(1)))
                    .join();
            require("third".equals(result.get("tool").getAsString()), result);
        }
    }

    private static void timeoutForcesCleanRecovery() throws Exception {
        List<FakeProcess> processes = new CopyOnWriteArrayList<>();
        try (McpRuntimeManager runtime = new McpRuntimeManager("proof-recovery", () -> {
            FakeProcess process = new FakeProcess(Set.of("probe"), processes.isEmpty());
            processes.add(process);
            return process;
        }, Set.of("probe"))) {
            require(runtime.start().join().state() == McpRuntimeHealth.State.READY, runtime.health());
            try {
                runtime.callTool("probe", new JsonObject(), Duration.ofMillis(25)).join();
                throw new AssertionError("first probe unexpectedly completed");
            } catch (java.util.concurrent.CompletionException expected) {
                require(root(expected) instanceof McpStdioClient.McpException mcp && "mcp_timeout".equals(mcp.code()), expected);
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while ((processes.size() < 2 || runtime.health().state() != McpRuntimeHealth.State.READY) && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            require(processes.size() >= 2, "timed-out MCP process was not replaced");
            require(runtime.health().state() == McpRuntimeHealth.State.READY, runtime.health());
            JsonObject recovered = runtime.callTool("probe", new JsonObject(), Duration.ofSeconds(1)).join();
            require("probe".equals(recovered.get("tool").getAsString()), recovered);
        }
    }

    private static Throwable root(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }

    private static void require(boolean value, Object detail) {
        if (!value) throw new AssertionError(detail);
    }

    private static final class FakeProcess extends Process {
        private final PipedInputStream stdout = new PipedInputStream();
        private final PipedOutputStream serverOutput;
        private final PipedInputStream serverInput = new PipedInputStream();
        private final PipedOutputStream stdin;
        private final Set<String> tools;
        private final boolean stallCalls;
        private final CompletableFuture<Process> exited = new CompletableFuture<>();
        private volatile boolean alive = true;

        private FakeProcess(Set<String> tools, boolean stallCalls) {
            this.tools = tools;
            this.stallCalls = stallCalls;
            try {
                serverOutput = new PipedOutputStream(stdout);
                stdin = new PipedOutputStream(serverInput);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            Thread thread = new Thread(this::serve, "koil-mcp-proof-server");
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(serverInput));
                 Writer writer = new OutputStreamWriter(serverOutput)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                    if (!request.has("id")) continue;
                    long id = request.get("id").getAsLong();
                    String method = request.get("method").getAsString();
                    if ("initialize".equals(method)) {
                        respond(writer, id, "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"proof\",\"version\":\"1\"}}");
                    } else if ("tools/list".equals(method)) {
                        String listed = tools.stream().sorted().map(name -> "{\"name\":\"" + name + "\",\"inputSchema\":{\"type\":\"object\"}}")
                                .collect(java.util.stream.Collectors.joining(","));
                        respond(writer, id, "{\"tools\":[" + listed + "]}");
                    } else if ("tools/call".equals(method)) {
                        if (stallCalls) continue;
                        String tool = request.getAsJsonObject("params").get("name").getAsString();
                        respond(writer, id, "{\"tool\":\"" + tool + "\"}");
                    }
                }
            } catch (Exception ignored) {
            } finally {
                alive = false;
                exited.complete(this);
            }
        }

        private static void respond(Writer writer, long id, String result) throws Exception {
            writer.write("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}\n");
            writer.flush();
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
