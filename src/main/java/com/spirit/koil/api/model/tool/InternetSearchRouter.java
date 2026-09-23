package com.spirit.koil.api.model.tool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Selects healthy public-search providers deterministically and fails over
 * without exposing provider-specific schemas to the model.
 */
public final class InternetSearchRouter {
    private final List<InternetSearchProvider> providers;
    private final Map<String, ProviderHealth> health = new ConcurrentHashMap<>();

    public InternetSearchRouter(List<InternetSearchProvider> providers) {
        this.providers = providers == null ? List.of() : providers.stream()
                .filter(Objects::nonNull)
                .toList();
        for (InternetSearchProvider provider : this.providers) {
            health.put(provider.id(), new ProviderHealth(InternetProviderState.READY, 0L, "", Instant.EPOCH, Instant.EPOCH));
        }
    }

    public SearchResponse search(String query, int maximum) throws Exception {
        if (providers.isEmpty()) throw new IllegalStateException("No public search provider is configured.");
        Exception lastFailure = null;
        for (InternetSearchProvider provider : providers) {
            ProviderHealth previous = health.get(provider.id());
            if (previous != null && (previous.state() == InternetProviderState.AUTH_REQUIRED
                    || previous.state() == InternetProviderState.RATE_LIMITED)) continue;
            long started = System.nanoTime();
            try {
                List<InternetSearchResult> response = provider.search(query, maximum);
                long latency = (System.nanoTime() - started) / 1_000_000L;
                health.put(provider.id(), new ProviderHealth(InternetProviderState.READY, latency, "", Instant.now(),
                        previous == null ? Instant.EPOCH : previous.lastFailure()));
                return new SearchResponse(provider.id(), deduplicate(response, maximum));
            } catch (Exception failure) {
                lastFailure = failure;
                InternetProviderState state = stateFor(failure);
                health.put(provider.id(), new ProviderHealth(state, (System.nanoTime() - started) / 1_000_000L,
                        message(failure), previous == null ? Instant.EPOCH : previous.lastSuccess(), Instant.now()));
            }
        }
        throw new IllegalStateException(lastFailure == null ? "All public search providers are unavailable."
                : "All public search providers failed: " + message(lastFailure), lastFailure);
    }

    public Map<String, ProviderHealth> health() {
        return Map.copyOf(new LinkedHashMap<>(health));
    }

    private static List<InternetSearchResult> deduplicate(List<InternetSearchResult> candidates, int maximum) {
        LinkedHashMap<String, InternetSearchResult> unique = new LinkedHashMap<>();
        if (candidates != null) for (InternetSearchResult result : candidates) {
            if (result == null || result.url().isBlank()) continue;
            unique.putIfAbsent(result.url().strip().toLowerCase(Locale.ROOT), result);
            if (unique.size() >= maximum) break;
        }
        return List.copyOf(new ArrayList<>(unique.values()));
    }

    private static InternetProviderState stateFor(Exception failure) {
        String detail = message(failure).toLowerCase(Locale.ROOT);
        if (detail.contains("401") || detail.contains("403") || detail.contains("auth")) return InternetProviderState.AUTH_REQUIRED;
        if (detail.contains("429") || detail.contains("rate")) return InternetProviderState.RATE_LIMITED;
        return InternetProviderState.DEGRADED;
    }

    private static String message(Throwable failure) {
        String value = failure == null ? "" : failure.getMessage();
        return value == null || value.isBlank() ? failure.getClass().getSimpleName() : value;
    }

    public record SearchResponse(String providerId, List<InternetSearchResult> results) {
        public SearchResponse {
            providerId = providerId == null ? "" : providerId;
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    public record ProviderHealth(
            InternetProviderState state,
            long latencyMillis,
            String failureReason,
            Instant lastSuccess,
            Instant lastFailure
    ) {
        public ProviderHealth {
            state = state == null ? InternetProviderState.UNAVAILABLE : state;
            latencyMillis = Math.max(0L, latencyMillis);
            failureReason = failureReason == null ? "" : failureReason;
            lastSuccess = lastSuccess == null ? Instant.EPOCH : lastSuccess;
            lastFailure = lastFailure == null ? Instant.EPOCH : lastFailure;
        }
    }
}
