package com.spirit.koil.api.model.codeintelligence.codebasememory;

import com.google.gson.JsonObject;
import com.spirit.koil.api.mcp.McpRuntimeHealth;
import com.spirit.koil.api.mcp.McpRuntimeManager;
import com.spirit.koil.api.model.codeintelligence.CodeIntelligenceHealth;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Codebase Memory adapter over Koil's shared persistent MCP runtime. */
public final class CodebaseMemoryRuntimeManager implements AutoCloseable {
    private final McpRuntimeManager runtime;
    private volatile CodeIntelligenceHealth probeHealth = new CodeIntelligenceHealth(
            CodeIntelligenceHealth.State.NOT_INSTALLED, "codebase_memory", "Codebase Memory has not started.");
    private volatile CompletableFuture<CodeIntelligenceHealth> starting;

    public CodebaseMemoryRuntimeManager(Supplier<Process> processStarter, Set<String> requiredTools) {
        runtime = new McpRuntimeManager("codebase_memory", processStarter, requiredTools);
    }

    /** Launches only Koil's verified executable directly; no shell or upstream installer is involved. */
    public static CodebaseMemoryRuntimeManager managed(Path executable, Set<String> requiredTools) {
        Path command = executable == null ? null : executable.toAbsolutePath().normalize();
        return new CodebaseMemoryRuntimeManager(() -> {
            if (command == null) throw new IllegalStateException("Codebase Memory executable is unavailable.");
            try { return new ProcessBuilder(command.toString()).start(); }
            catch (java.io.IOException failure) {
                throw new IllegalStateException("Could not start Codebase Memory: " + failure.getMessage(), failure);
            }
        }, requiredTools);
    }

    public synchronized CompletableFuture<CodeIntelligenceHealth> start() {
        if (starting != null && !starting.isDone()) return starting;
        starting = runtime.start().thenCompose(ignored -> probe(false));
        return starting;
    }

    /**
     * Re-runs a real tool probe on the existing persistent MCP session first.
     * A clean renegotiation is performed only when that live probe fails or the
     * runtime is already degraded. This avoids turning diagnostics themselves
     * into an unnecessary cold-start workload.
     */
    public synchronized CompletableFuture<CodeIntelligenceHealth> retest() {
        if (starting != null && !starting.isDone()) return starting;
        starting = probe(false);
        return starting;
    }

    private CompletableFuture<CodeIntelligenceHealth> probe(boolean forceRecovery) {
        CompletableFuture<McpRuntimeHealth> ready = forceRecovery || runtime.health().state() != McpRuntimeHealth.State.READY
                ? runtime.forceRestart("Revalidating Codebase Memory MCP transport.")
                : CompletableFuture.completedFuture(runtime.health());
        return ready.thenCompose(health -> {
            if (health.state() != McpRuntimeHealth.State.READY) {
                probeHealth = map(health);
                return CompletableFuture.completedFuture(probeHealth);
            }
            return probeOnce().thenCompose(first -> {
                if (first.state() == CodeIntelligenceHealth.State.READY) return CompletableFuture.completedFuture(first);
                return runtime.forceRestart("Codebase Memory health probe failed; performing one bounded recovery.")
                        .thenCompose(recovered -> recovered.state() == McpRuntimeHealth.State.READY
                                ? probeOnce()
                                : CompletableFuture.completedFuture(map(recovered)));
            });
        });
    }

    private CompletableFuture<CodeIntelligenceHealth> probeOnce() {
        return runtime.callTool("list_projects", new JsonObject(), probeTimeout()).handle((result, failure) -> {
            if (failure != null || result == null || result.has("isError") && result.get("isError").getAsBoolean()) {
                probeHealth = new CodeIntelligenceHealth(CodeIntelligenceHealth.State.DEGRADED, "codebase_memory",
                        failure == null ? "Codebase Memory list_projects probe returned an error." : message(failure));
            } else {
                probeHealth = new CodeIntelligenceHealth(CodeIntelligenceHealth.State.READY, "codebase_memory", "Codebase Memory MCP probe passed.");
            }
            return probeHealth;
        });
    }

    public CodeIntelligenceHealth health() {
        McpRuntimeHealth current = runtime.health();
        return current.state() == McpRuntimeHealth.State.READY ? probeHealth : map(current);
    }

    public Set<String> tools() { return runtime.tools(); }
    public boolean supports(String tool) { return runtime.supports(tool) && health().state() == CodeIntelligenceHealth.State.READY; }
    public CompletableFuture<JsonObject> callTool(String tool, JsonObject arguments, Duration timeout) { return runtime.callTool(tool, arguments, timeout); }
    public List<String> stderrTail() { return runtime.stderrTail(); }
    public long processId() { return runtime.processId(); }
    public String serverVersion() { return runtime.serverVersion(); }

    private static Duration probeTimeout() {
        long seconds = 30L;
        String configured = System.getProperty("koil.codeIntelligence.probeTimeoutSeconds", "").strip();
        if (!configured.isBlank()) {
            try { seconds = Long.parseLong(configured); }
            catch (NumberFormatException ignored) { seconds = 30L; }
        }
        return Duration.ofSeconds(Math.max(5L, Math.min(300L, seconds)));
    }

    private static CodeIntelligenceHealth map(McpRuntimeHealth health) {
        CodeIntelligenceHealth.State state = switch (health.state()) {
            case NOT_INSTALLED -> CodeIntelligenceHealth.State.NOT_INSTALLED;
            case STARTING, INSTALLING -> CodeIntelligenceHealth.State.STARTING;
            case NEGOTIATING -> CodeIntelligenceHealth.State.NEGOTIATING;
            case READY -> CodeIntelligenceHealth.State.READY;
            case STOPPED -> CodeIntelligenceHealth.State.STOPPED;
            case FAILED -> CodeIntelligenceHealth.State.FAILED;
            case DEGRADED -> CodeIntelligenceHealth.State.DEGRADED;
        };
        return new CodeIntelligenceHealth(state, "codebase_memory", health.detail());
    }

    private static String message(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @Override public void close() { runtime.close(); }
}
