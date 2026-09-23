package com.spirit.koil.api.model.codeintelligence;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Minimal non-structural fallback while a native graph provider is unavailable. */
public final class BasicWorkspaceCodeProvider implements CodeIntelligenceProvider {
    private static final String ID = "basic_workspace";

    @Override
    public CompletableFuture<CodeIntelligenceResult> query(CodeIntelligenceRequest request) {
        return CompletableFuture.supplyAsync(() -> queryBlocking(request));
    }

    @Override
    public CodeIntelligenceHealth health() {
        return new CodeIntelligenceHealth(CodeIntelligenceHealth.State.DEGRADED, ID,
            "Structural code intelligence is unavailable; bounded workspace text fallback is active.");
    }

    private static CodeIntelligenceResult queryBlocking(CodeIntelligenceRequest request) {
        if (request == null) {
            return CodeIntelligenceResult.unavailable(ID, "invalid_request", "A code intelligence request is required.");
        }
        if (request.operation() != CodeIntelligenceOperation.SEARCH && request.operation() != CodeIntelligenceOperation.SNIPPET) {
            String capability = request.operation() == CodeIntelligenceOperation.TRACE
                ? "call_graph"
                : request.operation().name().toLowerCase();
            return CodeIntelligenceResult.unavailable(ID, "basic_workspace_no_" + capability,
                "The workspace fallback does not provide structural code intelligence.");
        }
        if (!Files.isDirectory(request.workspaceRoot())) {
            return CodeIntelligenceResult.unavailable(ID, "workspace_unavailable", "The workspace root is unavailable.");
        }
        return search(request);
    }

    private static CodeIntelligenceResult search(CodeIntelligenceRequest request) {
        JsonArray matches = new JsonArray();
        int[] characters = {0};
        try (var paths = Files.walk(request.workspaceRoot(), request.depth())) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (matches.size() >= request.limit() || characters[0] >= request.maximumCharacters()) break;
                String text;
                try {
                    text = Files.readString(path, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    continue;
                }
                int index = request.query().isBlank() ? 0 : text.indexOf(request.query());
                if (index < 0) continue;
                int end = Math.min(text.length(), index + Math.min(512, request.maximumCharacters() - characters[0]));
                JsonObject match = new JsonObject();
                match.addProperty("path", request.workspaceRoot().relativize(path).toString().replace('\\', '/'));
                match.addProperty("text", text.substring(index, end));
                matches.add(match);
                characters[0] += end - index;
            }
        } catch (IOException failure) {
            return CodeIntelligenceResult.unavailable(ID, "workspace_search_failed", failure.getMessage());
        }
        JsonObject data = new JsonObject();
        data.add("matches", matches);
        data.addProperty("count", matches.size());
        return new CodeIntelligenceResult("completed", data, ID, "", "Workspace text fallback result.",
            matches.size() >= request.limit() || characters[0] >= request.maximumCharacters());
    }
}
