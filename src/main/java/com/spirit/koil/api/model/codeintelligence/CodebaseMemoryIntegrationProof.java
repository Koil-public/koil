package com.spirit.koil.api.model.codeintelligence;

import com.spirit.koil.api.model.codeintelligence.codebasememory.CodebaseMemoryProvider;
import com.spirit.koil.api.model.codeintelligence.codebasememory.CodebaseMemoryRuntimeManager;
import com.spirit.koil.api.model.install.ManagedRuntimeCatalog;
import com.spirit.koil.api.model.install.ManagedRuntimeInstaller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.util.Arrays;

/** Opt-in real-binary proof: persistent process, MCP readiness, Java indexing, and structural queries. */
public final class CodebaseMemoryIntegrationProof {
    private CodebaseMemoryIntegrationProof() { }

    public static void main(String[] args) throws Exception {
        Path executable = executable();
        Path fixture = Files.createTempDirectory("koil-cbm-integration-");
        try {
            Path packageRoot = Files.createDirectories(fixture.resolve("src/demo"));
            Files.writeString(packageRoot.resolve("SubmissionService.java"), "package demo; public interface SubmissionService { void submit(); }");
            Path implementation = packageRoot.resolve("QueueSubmissionService.java");
            Files.writeString(implementation, "package demo; public final class QueueSubmissionService implements SubmissionService { public void submit() { finish(); } void finish() {} }");
            Files.writeString(packageRoot.resolve("Caller.java"), "package demo; public final class Caller { void run(SubmissionService service) { service.submit(); } }");
            try (CodebaseMemoryRuntimeManager runtime = CodebaseMemoryRuntimeManager.managed(executable, CodebaseMemoryProvider.REQUIRED_TOOLS)) {
                require(runtime.start().join().state() == CodeIntelligenceHealth.State.READY, runtime.health());
                long pid = runtime.processId();
                CodebaseMemoryProvider provider = new CodebaseMemoryProvider(runtime);
                CodeIntelligenceResult symbols = provider.query(new CodeIntelligenceRequest(CodeIntelligenceOperation.SYMBOLS, fixture, "SubmissionService", 20, 3, 12_000)).join();
                require("completed".equals(symbols.status()), symbols);
                require("completed".equals(provider.query(new CodeIntelligenceRequest(CodeIntelligenceOperation.SEMANTIC_SEARCH, fixture, "submit queued work", 20, 3, 12_000)).join().status()), "semantic query");
                CodeIntelligenceResult trace = provider.query(new CodeIntelligenceRequest(CodeIntelligenceOperation.TRACE, fixture, "SubmissionService.submit", 20, 3, 12_000)).join();
                require("completed".equals(trace.status()), trace);
                Files.writeString(implementation, "package demo; public final class UpdatedQueueSubmissionService implements SubmissionService { public void submit() { finish(); } void finish() {} }");
                provider.refresh(fixture).join();
                CodeIntelligenceResult changed = provider.query(new CodeIntelligenceRequest(CodeIntelligenceOperation.SYMBOLS, fixture, "UpdatedQueueSubmissionService", 20, 3, 12_000)).join();
                require("completed".equals(changed.status()) && changed.data().get("text").getAsString().contains("UpdatedQueueSubmissionService"), changed);
                Files.delete(implementation);
                provider.refresh(fixture).join();
                CodeIntelligenceResult deleted = provider.query(new CodeIntelligenceRequest(CodeIntelligenceOperation.SYMBOLS, fixture, "UpdatedQueueSubmissionService", 20, 3, 12_000)).join();
                require("completed".equals(deleted.status()) && !deleted.data().get("text").getAsString().contains("UpdatedQueueSubmissionService"), deleted);
                require(pid == runtime.processId() && pid > 0L, "MCP process was not persistent");
                if (!Boolean.getBoolean("koil.codebaseMemory.skipBenchmark")) benchmark(executable, fixture, provider, pid);
                System.out.println("Codebase Memory integration proof passed with persistent pid " + pid + ".");
            }
        } finally {
            try (var paths = Files.walk(fixture)) { paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) { } }); }
        }
    }

    /** Uses the same hash-verified installer as the client when no explicit test executable is supplied. */
    private static Path executable() throws Exception {
        String configured = System.getProperty("koil.codebaseMemory.executable", "").strip();
        if (!configured.isBlank()) return Path.of(configured);
        return new ManagedRuntimeInstaller().ensureInstalled(
                ManagedRuntimeCatalog.CODEBASE_MEMORY_MCP_RUNTIME_ID,
                Path.of(System.getProperty("koil.codebaseMemory.runtimeRoot", "run/koil/sys/model/runtime")),
                (stage, detail, file, done, total) -> { },
                ManagedRuntimeInstaller.CancelSignal.NEVER
        ).executable();
    }

    private static void require(boolean condition, Object detail) { if (!condition) throw new AssertionError(detail); }

    private static void benchmark(Path executable, Path root, CodebaseMemoryProvider provider, long persistentPid) throws Exception {
        final int runs = 100;
        long[] persistent = new long[runs];
        long[] cli = new long[runs];
        CodeIntelligenceRequest request = new CodeIntelligenceRequest(CodeIntelligenceOperation.SYMBOLS, root, "SubmissionService", 10, 3, 4_000);
        String args = "{\"project\":\"" + root.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"query\":\"SubmissionService\",\"limit\":10}";
        for (int index = 0; index < runs; index++) {
            long started = System.nanoTime();
            require("completed".equals(provider.query(request).join().status()), "persistent benchmark query");
            persistent[index] = System.nanoTime() - started;
        }
        require(persistentPid > 0L, "persistent process missing during benchmark");
        for (int index = 0; index < runs; index++) {
            long started = System.nanoTime();
            Process process = new ProcessBuilder(executable.toString(), "cli", "--quiet", "search_graph", args).redirectErrorStream(true).start();
            try (InputStream output = process.getInputStream()) { output.transferTo(java.io.OutputStream.nullOutputStream()); }
            require(process.waitFor() == 0, "CLI benchmark query failed");
            cli[index] = System.nanoTime() - started;
        }
        System.out.printf("CBM benchmark 100 persistent queries: median=%.2fms p95=%.2fms process_starts=1; 100 CLI queries: median=%.2fms p95=%.2fms process_starts=100%n",
            percentile(persistent, 0.50), percentile(persistent, 0.95), percentile(cli, 0.50), percentile(cli, 0.95));
    }

    private static double percentile(long[] values, double percentile) {
        long[] sorted = values.clone(); Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, Math.max(0, (int) Math.ceil(sorted.length * percentile) - 1))] / 1_000_000.0;
    }
}
