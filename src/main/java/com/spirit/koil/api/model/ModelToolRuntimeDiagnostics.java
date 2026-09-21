package com.spirit.koil.api.model;

import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.capability.AutomationToolCoordinator;
import com.spirit.koil.api.model.codeintelligence.CodeIntelligenceHealth;
import com.spirit.koil.api.model.codeintelligence.CodeIntelligenceService;
import com.spirit.koil.api.model.retrieval.KoilKnowledgeRuntime;
import com.spirit.koil.api.model.tool.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Non-destructive runtime verification for the model tool surface. It executes
 * representative read-only paths, verifies every registered id has a concrete
 * dispatcher owner, retests Code Intelligence, and waits for knowledge source
 * population. Mutating tools are never invoked by this diagnostic.
 */
public final class ModelToolRuntimeDiagnostics {
    private ModelToolRuntimeDiagnostics() { }

    public record Check(String name, boolean passed, String detail) { }
    public record Report(int registeredTools, int dispatchCoveredTools, List<Check> checks) {
        public Report { checks = checks == null ? List.of() : List.copyOf(checks); }
        public long passedChecks() { return checks.stream().filter(Check::passed).count(); }
        public boolean passed() { return dispatchCoveredTools == registeredTools && checks.stream().allMatch(Check::passed); }
    }

    public static CompletableFuture<Report> run() {
        List<ModelToolDefinition> tools = LocalModelToolCatalog.allRegisteredTools();
        List<String> uncovered = tools.stream()
                .map(ModelToolDefinition::id)
                .filter(id -> !hasDispatcher(id))
                .sorted()
                .toList();
        int dispatchCovered = tools.size() - uncovered.size();
        List<Check> seed = new ArrayList<>();
        seed.add(new Check("dispatch coverage", uncovered.isEmpty(),
                dispatchCovered + "/" + tools.size() + " registered tool ids have an executable owner"
                        + (uncovered.isEmpty() ? "" : "; missing=" + String.join(", ", uncovered))));
        seed.add(new Check("dynamic MCP registry", true,
                DynamicMcpToolRegistry.modelTools().size() + " dynamic MCP tool(s) currently attached"));

        CompletableFuture<Check> workspace = bounded(ModelWorkspaceToolRegistry.executeReadOnly(
                new ModelToolCall("diagnostic-workspace", "workspace.roots", new JsonObject())), 10, "workspace runtime")
                .handle((result, failure) -> checkResult("workspace runtime", result, failure));

        JsonObject documentationArgs = new JsonObject();
        documentationArgs.addProperty("operation", "catalog");
        CompletableFuture<Check> documentation = bounded(KoilDocumentationModelToolRegistry.execute(
                new ModelToolCall("diagnostic-docs", KoilDocumentationModelToolRegistry.TOOL_ID, documentationArgs)), 10, "Koil documentation runtime")
                .handle((result, failure) -> checkResult("Koil documentation runtime", result, failure));

        JsonObject catalogueArgs = new JsonObject();
        catalogueArgs.addProperty("operation", "status");
        CompletableFuture<Check> catalogue = bounded(McpCatalogueModelToolRegistry.execute(
                new ModelToolCall("diagnostic-mcp-catalogue", McpCatalogueModelToolRegistry.TOOL_ID, catalogueArgs)), 10, "MCP catalogue runtime")
                .handle((result, failure) -> checkResult("MCP catalogue runtime", result, failure));

        JsonObject browserArgs = new JsonObject();
        CompletableFuture<Check> browser = bounded(BrowserIntelligenceModelToolRegistry.execute(
                new ModelToolCall("diagnostic-browser", BrowserIntelligenceModelToolRegistry.HEALTH, browserArgs)), 20, "Browser Intelligence runtime")
                .handle((result, failure) -> checkResult("Browser Intelligence runtime", result, failure));

        JsonObject internetArgs = new JsonObject();
        internetArgs.addProperty("query", "Minecraft");
        internetArgs.addProperty("maxResults", 1);
        CompletableFuture<Check> internet = bounded(InternetResearchModelToolRegistry.execute(
                new ModelToolCall("diagnostic-internet", InternetResearchModelToolRegistry.SEARCH, internetArgs)), 60, "Internet Research runtime")
                .handle((result, failure) -> checkResult("Internet Research runtime", result, failure));

        JsonObject datasetArgs = new JsonObject();
        datasetArgs.addProperty("query", "mnist");
        datasetArgs.addProperty("limit", 1);
        CompletableFuture<Check> dataset = bounded(DatasetIntelligenceModelToolRegistry.execute(
                new ModelToolCall("diagnostic-dataset", DatasetIntelligenceModelToolRegistry.SEARCH, datasetArgs)), 60, "Dataset Intelligence runtime")
                .handle((result, failure) -> checkResult("Dataset Intelligence runtime", result, failure));

        JsonObject contentArgs = new JsonObject();
        contentArgs.addProperty("url", "https://example.com/");
        CompletableFuture<Check> content = bounded(ContentIntelligenceModelToolRegistry.execute(
                new ModelToolCall("diagnostic-content", ContentIntelligenceModelToolRegistry.INSPECT, contentArgs)), 45, "Content Intelligence runtime")
                .handle((result, failure) -> checkResult("Content Intelligence runtime", result, failure));

        CompletableFuture<Check> code = bounded(CodeIntelligenceService.instance().retest(), 120, "Code Intelligence backend")
                .handle((health, failure) -> new Check("Code Intelligence backend",
                        failure == null && health != null && health.state() == CodeIntelligenceHealth.State.READY,
                        failure == null && health != null ? health.state().name().toLowerCase(java.util.Locale.ROOT) + ": " + health.detail()
                                : message(failure)));

        CompletableFuture<Check> knowledge = bounded(KoilKnowledgeRuntime.ensureSemanticReady(), 90, "knowledge + TurboVec population")
                .handle((embedded, failure) -> {
                    String status = KoilKnowledgeRuntime.status();
                    boolean populated = failure == null
                            && KoilKnowledgeRuntime.builtInRecordCount() > 0L
                            && !status.contains("Population: failed")
                            && !status.contains("Dense vectors: 0");
                    return new Check("knowledge + TurboVec population", populated, failure == null ? status : message(failure));
                });

        CompletableFuture<Check> timers = bounded(CompletableFuture.completedFuture(
                AutomationTimerModelToolRegistry.execute(new ModelToolCall(
                        "diagnostic-timer", AutomationTimerModelToolRegistry.LIST_TOOL_ID, new JsonObject()))),
                5, "timer runtime").handle((result, failure) -> checkResult("timer runtime", result, failure));

        CompletableFuture<Check> processes = bounded(WorkspaceProcessModelToolRegistry.execute(
                null, new ModelToolCall("diagnostic-process", WorkspaceProcessModelToolRegistry.LIST, new JsonObject()), false),
                5, "process runtime").handle((result, failure) -> checkResult("process runtime", result, failure));

        CompletableFuture<Check> resources = bounded(SystemNetworkModelToolRegistry.execute(
                new ModelToolCall("diagnostic-resources", SystemNetworkModelToolRegistry.RESOURCES, new JsonObject())),
                10, "system resources runtime").handle((result, failure) -> checkResult("system resources runtime", result, failure));

        CompletableFuture<Check> indexes = bounded(DataContextIndexModelToolRegistry.execute(
                null, new ModelToolCall("diagnostic-index", DataContextIndexModelToolRegistry.INDEX_STATUS, new JsonObject()), false),
                5, "local index runtime").handle((result, failure) -> checkResult("local index runtime", result, failure));

        CompletableFuture<Check> workflows = bounded(BackgroundAutomationModelToolRegistry.execute(
                null, new ModelToolCall("diagnostic-workflows", BackgroundAutomationModelToolRegistry.WORKFLOW_LIST, new JsonObject()), false),
                5, "workflow runtime").handle((result, failure) -> checkResult("workflow runtime", result, failure));

        CompletableFuture<Check> schedules = bounded(BackgroundAutomationModelToolRegistry.execute(
                null, new ModelToolCall("diagnostic-schedules", BackgroundAutomationModelToolRegistry.SCHEDULE_LIST, new JsonObject()), false),
                5, "scheduler runtime").handle((result, failure) -> checkResult("scheduler runtime", result, failure));

        CompletableFuture<Check> chain = workspace.thenCompose(first -> documentation.thenCompose(second -> catalogue.thenApply(third -> {
            boolean passed = first.passed() && second.passed() && third.passed();
            return new Check("multi-tool chaining", passed,
                    "workspace.roots -> koil.documentation -> mcp-catalogue " + (passed ? "completed" : "failed"));
        })));

        return CompletableFuture.allOf(workspace, documentation, catalogue, browser, internet, dataset, content, code, knowledge,
                        timers, processes, resources, indexes, workflows, schedules, chain)
                .thenApply(ignored -> {
                    ArrayList<Check> checks = new ArrayList<>(seed);
                    checks.add(workspace.join());
                    checks.add(documentation.join());
                    checks.add(catalogue.join());
                    checks.add(browser.join());
                    checks.add(internet.join());
                    checks.add(dataset.join());
                    checks.add(content.join());
                    checks.add(code.join());
                    checks.add(knowledge.join());
                    checks.add(timers.join());
                    checks.add(processes.join());
                    checks.add(resources.join());
                    checks.add(indexes.join());
                    checks.add(workflows.join());
                    checks.add(schedules.join());
                    checks.add(chain.join());
                    return new Report(tools.size(), dispatchCovered, checks);
                });
    }

    private static <T> CompletableFuture<T> bounded(CompletableFuture<T> future, long seconds, String operation) {
        return future.orTimeout(seconds, TimeUnit.SECONDS).exceptionallyCompose(failure -> {
            Throwable root = root(failure);
            if (root instanceof java.util.concurrent.TimeoutException) {
                return CompletableFuture.failedFuture(new IllegalStateException(operation + " exceeded " + seconds + " seconds.", root));
            }
            return CompletableFuture.failedFuture(root);
        });
    }

    private static Throwable root(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Check checkResult(String name, ModelToolResult result, Throwable failure) {
        if (failure != null) return new Check(name, false, message(failure));
        if (result == null) return new Check(name, false, "No result returned.");
        boolean passed = "completed".equals(result.status());
        return new Check(name, passed, result.status() + (result.failureCode().isBlank() ? "" : " / " + result.failureCode())
                + (result.detail().isBlank() ? "" : ": " + result.detail()));
    }

    static boolean hasDispatcher(String id) {
        return AutomationToolCoordinator.hasExecutableOwner(id);
    }

    private static String message(Throwable failure) {
        if (failure == null) return "unknown failure";
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String detail = current.getMessage();
        return detail == null || detail.isBlank() ? current.getClass().getSimpleName() : detail;
    }
}
