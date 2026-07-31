package com.spirit.koil.api.automation.runtime;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Correlates high-level automation submissions with their final executor result.
 * Callers register before routing a request, while the executor remains the only
 * authority that publishes completion.
 */
public final class AutomationExecutionResults {
    private static final Map<UUID, CompletableFuture<AutomationExecutionResult>> WAITERS = new ConcurrentHashMap<>();

    private AutomationExecutionResults() {
    }

    public static CompletableFuture<AutomationExecutionResult> register(UUID executionId) {
        if (executionId == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("execution id is required"));
        }
        CompletableFuture<AutomationExecutionResult> future = new CompletableFuture<>();
        CompletableFuture<AutomationExecutionResult> existing = WAITERS.putIfAbsent(executionId, future);
        if (existing != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("execution id is already registered"));
        }
        future.whenComplete((result, failure) -> WAITERS.remove(executionId, future));
        return future;
    }

    public static void publish(AutomationExecutionResult result) {
        if (result == null) {
            return;
        }
        CompletableFuture<AutomationExecutionResult> waiter = WAITERS.remove(result.executionId());
        if (waiter != null) {
            waiter.complete(result);
        }
    }

    public static int pendingCount() {
        return WAITERS.size();
    }
}
