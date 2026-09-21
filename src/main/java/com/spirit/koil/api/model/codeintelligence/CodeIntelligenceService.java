package com.spirit.koil.api.model.codeintelligence;

import com.spirit.koil.api.model.codeintelligence.codebasememory.CodebaseMemoryProvider;
import com.spirit.koil.api.model.codeintelligence.codebasememory.CodebaseMemoryRuntimeManager;
import com.spirit.koil.api.model.install.ManagedRuntimeInstallation;
import com.spirit.koil.api.model.install.ManagedRuntimeCatalog;
import com.spirit.koil.api.model.install.ManagedRuntimeInstaller;
import com.spirit.koil.api.util.file.KoilInstancePaths;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One lazy, Koil-owned preferred-provider lifecycle; fallback remains available at every state. */
public final class CodeIntelligenceService implements AutoCloseable {
    private static final CodeIntelligenceService INSTANCE = new CodeIntelligenceService();
    private final BasicWorkspaceCodeProvider fallback = new BasicWorkspaceCodeProvider();
    private final ManagedRuntimeInstaller installer = new ManagedRuntimeInstaller();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "koil-code-intelligence-install");
        thread.setDaemon(true);
        return thread;
    });
    private volatile CompletableFuture<CodebaseMemoryProvider> starting;
    private volatile CodebaseMemoryProvider preferred;
    private volatile String startupFailure = "";

    private CodeIntelligenceService() { }

    public static CodeIntelligenceService instance() { return INSTANCE; }

    /** Starts the already-installed preferred provider off the client path; failures stay truthful in health(). */
    public void warm() {
        start();
    }

    public CompletableFuture<CodeIntelligenceResult> query(CodeIntelligenceRequest request) {
        CodebaseMemoryProvider active = preferred;
        if (active != null && active.health().state() == CodeIntelligenceHealth.State.READY) return active.query(request);
        return start().thenCompose(provider -> provider.health().state() == CodeIntelligenceHealth.State.READY
            ? provider.query(request)
            : fallback.query(request));
    }


    /** Performs a live backend retest. If the preferred provider is already present, its MCP transport is renegotiated. */
    public CompletableFuture<CodeIntelligenceHealth> retest() {
        CodebaseMemoryProvider active = preferred;
        if (active != null) return active.retest();
        return start().thenCompose(provider -> provider.retest());
    }

    public CodeIntelligenceHealth health() {
        CodebaseMemoryProvider active = preferred;
        if (active != null) return active.health();
        CompletableFuture<CodebaseMemoryProvider> pending = starting;
        if (pending != null && !pending.isDone()) return new CodeIntelligenceHealth(CodeIntelligenceHealth.State.INSTALLING, CodebaseMemoryProvider.ID, "Installing verified Codebase Memory runtime.");
        if (!startupFailure.isBlank()) return new CodeIntelligenceHealth(CodeIntelligenceHealth.State.DEGRADED, CodebaseMemoryProvider.ID, startupFailure);
        return new CodeIntelligenceHealth(CodeIntelligenceHealth.State.NOT_INSTALLED, CodebaseMemoryProvider.ID, "Codebase Memory has not started.");
    }

    /** Called centrally after successful Koil workspace mutations; it never blocks that mutation. */
    public void workspaceChanged(Path workspaceRoot) {
        CodebaseMemoryProvider active = preferred;
        if (active != null) active.refresh(workspaceRoot);
    }

    private synchronized CompletableFuture<CodebaseMemoryProvider> start() {
        if (preferred != null && preferred.health().state() == CodeIntelligenceHealth.State.READY) return CompletableFuture.completedFuture(preferred);
        if (starting != null && !starting.isDone()) return starting;
        starting = CompletableFuture.supplyAsync(() -> {
            try {
                Path runtimeRoot = KoilInstancePaths.modelRoot().resolve("runtime");
                ManagedRuntimeInstallation installation = installer.ensureInstalled(
                    ManagedRuntimeCatalog.CODEBASE_MEMORY_MCP_RUNTIME_ID, runtimeRoot,
                    (stage, detail, file, done, total) -> { }, ManagedRuntimeInstaller.CancelSignal.NEVER
                );
                CodebaseMemoryProvider provider = new CodebaseMemoryProvider(
                    CodebaseMemoryRuntimeManager.managed(installation.executable(), CodebaseMemoryProvider.REQUIRED_TOOLS)
                );
                provider.start().join();
                preferred = provider;
                startupFailure = "";
                return provider;
            } catch (Exception failure) {
                startupFailure = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                return unavailableProvider(failure);
            }
        }, executor);
        return starting;
    }

    private static CodebaseMemoryProvider unavailableProvider(Exception failure) {
        return new CodebaseMemoryProvider(null);
    }

    @Override public void close() {
        CodebaseMemoryProvider active = preferred;
        if (active != null) active.close();
        fallback.close();
        executor.shutdownNow();
    }
}
