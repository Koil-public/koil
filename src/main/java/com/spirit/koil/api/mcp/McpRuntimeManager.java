package com.spirit.koil.api.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.LocalModelOwnedProcessRegistry;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.function.Consumer;

/**
 * Reusable persistent local stdio MCP lifecycle. It owns protocol negotiation,
 * tool discovery, bounded calls, process cleanup, and bounded restart.
 */
public final class McpRuntimeManager implements AutoCloseable {
    private static final Duration INITIALIZE_TIMEOUT = configuredTimeout("koil.mcp.initializeTimeoutSeconds", 30);
    private static final Duration TOOLS_LIST_TIMEOUT = configuredTimeout("koil.mcp.toolsListTimeoutSeconds", 15);
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    private final String serverId;
    private final Supplier<Process> processStarter;
    private final Set<String> requiredTools;
    private final ExecutorService executor;
    private final Semaphore callSlots;
    private final List<Consumer<McpRuntimeHealth>> healthListeners = new CopyOnWriteArrayList<>();
    private volatile McpRuntimeHealth health;
    private volatile Set<String> tools = Set.of();
    private volatile Map<String, JsonObject> toolDescriptors = Map.of();
    private volatile Process process;
    private volatile McpStdioClient client;
    private volatile CompletableFuture<McpRuntimeHealth> starting;
    private volatile String serverVersion = "";
    private volatile boolean stopped;
    private volatile int restartAttempts;
    private volatile CompletableFuture<McpRuntimeHealth> recovering;

    public McpRuntimeManager(String serverId, Supplier<Process> processStarter, Set<String> requiredTools) {
        this.serverId = serverId == null || serverId.isBlank() ? "mcp" : serverId.strip();
        this.processStarter = processStarter;
        this.requiredTools = requiredTools == null ? Set.of() : Set.copyOf(requiredTools);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "koil-mcp-runtime-" + this.serverId);
            thread.setDaemon(true);
            return thread;
        });
        callSlots = new Semaphore(4);
        health = new McpRuntimeHealth(McpRuntimeHealth.State.NOT_INSTALLED, this.serverId, "MCP runtime has not started.");
    }

    public synchronized CompletableFuture<McpRuntimeHealth> start() {
        if (stopped || health.state() == McpRuntimeHealth.State.STOPPED) return CompletableFuture.completedFuture(health);
        if (health.state() == McpRuntimeHealth.State.READY) return CompletableFuture.completedFuture(health);
        if (recovering != null && !recovering.isDone()) return recovering;
        if (starting != null && !starting.isDone()) return starting;
        starting = CompletableFuture.supplyAsync(this::startInternal, executor);
        return starting;
    }

    private McpRuntimeHealth startInternal() {
        updateHealth(McpRuntimeHealth.State.STARTING, "Starting persistent MCP process.");
        try {
            Process launched = processStarter == null ? null : processStarter.get();
            if (launched == null) throw new IllegalStateException("No MCP process is available.");
            process = launched;
            LocalModelOwnedProcessRegistry.register(launched);
            launched.onExit().thenAccept(this::onProcessExit);
            client = new McpStdioClient(launched.getInputStream(), launched.getErrorStream(), launched.getOutputStream());
            updateHealth(McpRuntimeHealth.State.NEGOTIATING, "Negotiating MCP protocol.");
            JsonObject initialized = client.request("initialize", initializeParameters(), INITIALIZE_TIMEOUT).join();
            String protocol = string(initialized, "protocolVersion");
            if (!SUPPORTED_PROTOCOLS.contains(protocol)) throw new IllegalStateException("Unsupported MCP protocol: " + protocol);
            serverVersion = initialized.has("serverInfo") && initialized.get("serverInfo").isJsonObject()
                    ? string(initialized.getAsJsonObject("serverInfo"), "version") : "";
            client.notify("notifications/initialized", new JsonObject());
            LinkedHashMap<String, JsonObject> discovered = new LinkedHashMap<>();
            discoverTools(null, discovered);
            if (!discovered.keySet().containsAll(requiredTools)) {
                LinkedHashSet<String> missing = new LinkedHashSet<>(requiredTools);
                missing.removeAll(discovered.keySet());
                throw new IllegalStateException("Required MCP tools are unavailable: " + String.join(", ", missing));
            }
            tools = Set.copyOf(discovered.keySet());
            toolDescriptors = Map.copyOf(discovered);
            updateHealth(McpRuntimeHealth.State.READY, "MCP initialize and tools/list succeeded.");
        } catch (RuntimeException failure) {
            closeProcess();
            updateHealth(McpRuntimeHealth.State.DEGRADED, message(failure));
        }
        return health;
    }

    public McpRuntimeHealth health() { return health; }
    /** Listeners are notified after every lifecycle transition; no protocol data is exposed here. */
    public void addHealthListener(Consumer<McpRuntimeHealth> listener) {
        if (listener == null) return;
        healthListeners.add(listener);
        listener.accept(health);
    }
    public Set<String> tools() { return tools; }
    public Map<String, JsonObject> toolDescriptors() { return Map.copyOf(toolDescriptors); }
    public String serverVersion() { return serverVersion; }
    public long processId() { Process current = process; return current == null ? -1L : current.pid(); }
    public List<String> stderrTail() { McpStdioClient current = client; return current == null ? List.of() : current.stderrTail(); }

    /**
     * Koil-owned lifecycle diagnostics suitable for activity/tool evidence. This is the
     * native equivalent of host hook/tail visibility: it reports the runtime transition
     * and bounded stderr tail without introducing a foreign hook runtime.
     */
    public JsonObject lifecycleSnapshot() {
        JsonObject output = new JsonObject();
        McpRuntimeHealth current = health;
        output.addProperty("hook", "MCP " + serverId + " / " + current.state().name().toLowerCase(java.util.Locale.ROOT));
        output.addProperty("lifecycleOutput", current.detail());
        output.addProperty("processId", processId());
        if (!serverVersion.isBlank()) output.addProperty("serverVersion", serverVersion);
        JsonArray tail = new JsonArray();
        List<String> lines = stderrTail();
        int start = Math.max(0, lines.size() - 12);
        for (int index = start; index < lines.size(); index++) tail.add(lines.get(index));
        output.add("stderrTail", tail);
        return output;
    }
    public boolean supports(String tool) { return tool != null && tools.contains(tool) && health.state() == McpRuntimeHealth.State.READY; }

    public CompletableFuture<JsonObject> callTool(String tool, JsonObject arguments, Duration timeout) {
        McpStdioClient active = client;
        if (!supports(tool) || active == null) return CompletableFuture.failedFuture(new McpStdioClient.McpException("mcp_unavailable", "MCP is not ready for " + tool + "."));
        if (!callSlots.tryAcquire()) return CompletableFuture.failedFuture(new McpStdioClient.McpException("mcp_concurrency_limited", "MCP concurrency limit reached."));
        JsonObject parameters = new JsonObject();
        parameters.addProperty("name", tool);
        parameters.add("arguments", arguments == null ? new JsonObject() : arguments);
        return active.request("tools/call", parameters, timeout).whenComplete((ignored, failure) -> {
            callSlots.release();
            if (failure != null) handleTransportFailure(failure);
            else restartAttempts = 0; // A real successful call proves the recovered transport is stable.
        });
    }

    /**
     * Forces a clean stdio/process renegotiation. This is used by provider probes
     * after a transport timeout so a wedged child cannot remain falsely READY.
     */
    public synchronized CompletableFuture<McpRuntimeHealth> forceRestart(String reason) {
        if (stopped) return CompletableFuture.completedFuture(health);
        if (recovering != null && !recovering.isDone()) return recovering;
        String detail = reason == null || reason.isBlank() ? "MCP recovery requested." : reason;
        recovering = CompletableFuture.supplyAsync(() -> recoverInternal(detail), executor);
        return recovering;
    }

    private void handleTransportFailure(Throwable failure) {
        McpStdioClient.McpException transport = transportFailure(failure);
        if (transport == null || !isRecoverableTransportCode(transport.code()) || stopped) return;
        updateHealth(McpRuntimeHealth.State.DEGRADED, "MCP transport " + transport.code() + "; restarting cleanly.");
        forceRestart("Recovering after " + transport.code() + ".");
    }

    private McpRuntimeHealth recoverInternal(String reason) {
        if (stopped) return health;
        updateHealth(McpRuntimeHealth.State.DEGRADED, reason == null || reason.isBlank() ? "Restarting MCP runtime." : reason);
        closeProcess();
        return startInternal();
    }

    private static McpStdioClient.McpException transportFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof McpStdioClient.McpException mcp) return mcp;
            current = current.getCause();
        }
        return null;
    }

    private static boolean isRecoverableTransportCode(String code) {
        return "mcp_timeout".equals(code)
                || "mcp_eof".equals(code)
                || "mcp_read_failed".equals(code)
                || "mcp_write_failed".equals(code)
                || "mcp_malformed_response".equals(code);
    }

    private void discoverTools(String cursor, Map<String, JsonObject> discovered) {
        JsonObject parameters = new JsonObject();
        if (cursor != null && !cursor.isBlank()) parameters.addProperty("cursor", cursor);
        JsonObject result = client.request("tools/list", parameters, TOOLS_LIST_TIMEOUT).join();
        JsonArray listed = result.has("tools") && result.get("tools").isJsonArray() ? result.getAsJsonArray("tools") : new JsonArray();
        for (JsonElement element : listed) if (element.isJsonObject()) {
            String name = string(element.getAsJsonObject(), "name");
            if (!name.isBlank()) discovered.put(name, element.getAsJsonObject().deepCopy());
        }
        String next = string(result, "nextCursor");
        if (!next.isBlank()) discoverTools(next, discovered);
    }

    private void onProcessExit(Process exited) {
        if (stopped || process != exited) return;
        closeProcess();
        if (restartAttempts++ >= 2) {
            updateHealth(McpRuntimeHealth.State.FAILED, "MCP process exited repeatedly.");
            return;
        }
        updateHealth(McpRuntimeHealth.State.DEGRADED, "MCP process exited; retrying startup.");
        forceRestart("MCP process exited unexpectedly; renegotiating.");
    }

    private static Duration configuredTimeout(String property, long fallbackSeconds) {
        long seconds = fallbackSeconds;
        String configured = System.getProperty(property, "").strip();
        if (!configured.isBlank()) {
            try { seconds = Long.parseLong(configured); }
            catch (NumberFormatException ignored) { seconds = fallbackSeconds; }
        }
        return Duration.ofSeconds(Math.max(1L, Math.min(300L, seconds)));
    }

    private static JsonObject initializeParameters() {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("protocolVersion", "2025-11-25");
        parameters.add("capabilities", new JsonObject());
        JsonObject info = new JsonObject();
        info.addProperty("name", "Koil");
        info.addProperty("version", "development");
        parameters.add("clientInfo", info);
        return parameters;
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static String message(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String detail = current.getMessage();
        return detail == null || detail.isBlank() ? current.getClass().getSimpleName() : detail;
    }

    private void updateHealth(McpRuntimeHealth.State state, String detail) {
        McpRuntimeHealth updated = new McpRuntimeHealth(state, serverId, detail);
        health = updated;
        for (Consumer<McpRuntimeHealth> listener : healthListeners) {
            try { listener.accept(updated); } catch (RuntimeException ignored) { }
        }
    }

    private void closeProcess() {
        // Detach first so an intentional destroy cannot race its onExit callback
        // into the unexpected-exit restart path.
        McpStdioClient currentClient = client;
        Process current = process;
        client = null;
        process = null;
        tools = Set.of();
        toolDescriptors = Map.of();
        if (currentClient != null) currentClient.close();
        if (current != null) {
            LocalModelOwnedProcessRegistry.unregister(current);
            if (current.isAlive()) current.destroy();
        }
    }

    @Override public void close() {
        stopped = true;
        closeProcess();
        executor.shutdownNow();
        updateHealth(McpRuntimeHealth.State.STOPPED, "MCP runtime stopped.");
    }
}
