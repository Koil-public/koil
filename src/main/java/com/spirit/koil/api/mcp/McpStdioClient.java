package com.spirit.koil.api.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Persistent newline-delimited JSON-RPC transport; stderr is diagnostics, never protocol data. */
public final class McpStdioClient implements AutoCloseable {
    private static final int STDERR_TAIL_LINES = 200;

    private final InputStream stdout;
    private final InputStream stderr;
    private final Writer input;
    private final BufferedReader output;
    private final BufferedReader error;
    private final AtomicLong nextId = new AtomicLong(1L);
    private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "koil-mcp-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final ArrayDeque<String> stderrTail = new ArrayDeque<>();
    private volatile boolean closed;

    public McpStdioClient(InputStream stdout, InputStream stderr, OutputStream stdin) {
        this.stdout = stdout;
        this.stderr = stderr;
        input = new OutputStreamWriter(stdin, StandardCharsets.UTF_8);
        output = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
        error = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8));
        startReader("koil-mcp-stdout", this::readResponses);
        startReader("koil-mcp-stderr", this::readStderr);
    }

    public CompletableFuture<JsonObject> request(String method, JsonObject params, Duration timeout) {
        if (closed) return CompletableFuture.failedFuture(new McpException("mcp_stopped", "The MCP transport is stopped."));
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            JsonObject request = new JsonObject();
            request.addProperty("jsonrpc", "2.0");
            request.addProperty("id", id);
            request.addProperty("method", method == null ? "" : method);
            request.add("params", params == null ? new JsonObject() : params);
            synchronized (input) {
                input.write(request.toString());
                input.write('\n');
                input.flush();
            }
        } catch (IOException failure) {
            pending.remove(id, future);
            future.completeExceptionally(new McpException("mcp_write_failed", failure.getMessage()));
            return future;
        }
        long millis = timeout == null ? 30_000L : Math.max(1L, timeout.toMillis());
        ScheduledFuture<?> timeoutTask = timeoutExecutor.schedule(() -> {
            if (pending.remove(id, future)) future.completeExceptionally(new McpException("mcp_timeout", "MCP request timed out after " + millis + " ms."));
        }, millis, TimeUnit.MILLISECONDS);
        return future.whenComplete((ignored, failure) -> {
            timeoutTask.cancel(false);
            pending.remove(id, future);
        });
    }

    public void notify(String method, JsonObject params) {
        if (closed) return;
        try {
            JsonObject notification = new JsonObject();
            notification.addProperty("jsonrpc", "2.0");
            notification.addProperty("method", method == null ? "" : method);
            if (params != null) notification.add("params", params);
            synchronized (input) {
                input.write(notification.toString());
                input.write('\n');
                input.flush();
            }
        } catch (IOException failure) {
            failAll(new McpException("mcp_write_failed", failure.getMessage()));
        }
    }

    public synchronized List<String> stderrTail() { return List.copyOf(stderrTail); }

    private void readResponses() {
        try {
            String line;
            while ((line = output.readLine()) != null) handleResponse(line);
            failAll(new McpException("mcp_eof", "MCP stdout closed."));
        } catch (IOException failure) {
            failAll(new McpException("mcp_read_failed", failure.getMessage()));
        }
    }

    private void handleResponse(String line) {
        JsonObject response;
        try {
            JsonElement parsed = JsonParser.parseString(line);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Response is not an object.");
            response = parsed.getAsJsonObject();
        } catch (RuntimeException failure) {
            failAll(new McpException("mcp_malformed_response", "MCP stdout contained invalid JSON-RPC."));
            return;
        }
        Long id = requestId(response.get("id"));
        if (id == null) return;
        CompletableFuture<JsonObject> future = pending.remove(id);
        if (future == null) return;
        if (response.has("error")) future.completeExceptionally(new McpException("mcp_error", response.get("error").toString()));
        else if (response.has("result") && response.get("result").isJsonObject()) future.complete(response.getAsJsonObject("result"));
        else future.completeExceptionally(new McpException("mcp_malformed_response", "MCP response has no object result."));
    }

    private static Long requestId(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        try { return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsLong() : Long.parseLong(value.getAsString()); }
        catch (RuntimeException ignored) { return null; }
    }

    private void readStderr() {
        try {
            String line;
            while ((line = error.readLine()) != null) synchronized (this) {
                if (stderrTail.size() == STDERR_TAIL_LINES) stderrTail.removeFirst();
                stderrTail.addLast(line);
            }
        } catch (IOException ignored) { }
    }

    private void startReader(String name, Runnable action) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        thread.start();
    }

    private void failAll(McpException failure) {
        pending.forEach((id, future) -> { if (pending.remove(id, future)) future.completeExceptionally(failure); });
    }

    @Override public void close() {
        closed = true;
        timeoutExecutor.shutdownNow();
        failAll(new McpException("mcp_stopped", "The MCP transport is stopped."));
        try { input.close(); } catch (IOException ignored) { }
        try { stdout.close(); } catch (IOException ignored) { }
        try { stderr.close(); } catch (IOException ignored) { }
    }

    public static final class McpException extends RuntimeException {
        private final String code;
        public McpException(String code, String message) { super(message == null ? "" : message); this.code = code; }
        public String code() { return code; }
    }
}
