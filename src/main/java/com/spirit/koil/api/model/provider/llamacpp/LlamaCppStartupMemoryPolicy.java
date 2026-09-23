package com.spirit.koil.api.model.provider.llamacpp;

/**
 * Conservative startup-context sizing for memory-constrained hosts.
 *
 * <p>llama.cpp allocates more than model weights while loading a server. On UMA
 * handhelds, a large model plus a large KV context can push the compositor and
 * Minecraft into external memory-pressure termination before the HTTP server
 * becomes ready. This policy never changes the selected model or compute mode;
 * it only reduces the initial per-slot context when current host headroom is
 * insufficient. A later restart with more available memory can select a larger
 * context automatically.</p>
 */
final class LlamaCppStartupMemoryPolicy {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private LlamaCppStartupMemoryPolicy() {}

    static Profile select(int configuredPerSlotContext, int slots, long modelBytes, long availableBytes) {
        int configured = Math.max(512, configuredPerSlotContext);
        int safeSlots = Math.max(1, slots);
        if (availableBytes <= 0L) {
            return new Profile(configured, "configured", 0L, Math.max(0L, modelBytes));
        }

        int target = configured;
        String reason = "configured";
        long availableMiB = availableBytes / MIB;
        boolean largeRelativeModel = modelBytes > 0L && modelBytes * 5L >= availableBytes * 2L; // >= 40% of MemAvailable

        if (availableBytes < 2500L * MIB) {
            target = Math.min(target, 2048);
            reason = "critical_low_memory";
        } else if (availableBytes < 3600L * MIB) {
            target = Math.min(target, 4096);
            reason = largeRelativeModel ? "low_memory_large_model" : "low_memory";
        } else if (largeRelativeModel && availableBytes < 5L * GIB) {
            target = Math.min(target, 4096);
            reason = "low_memory_large_model";
        } else if (availableBytes < 5200L * MIB) {
            target = Math.min(target, 8192);
            reason = "low_memory";
        } else if (availableBytes < 7L * GIB) {
            target = Math.min(target, 16384);
            reason = "moderate_memory";
        }

        // Multiple parallel slots multiply KV/cache pressure. Keep at least a
        // useful 2k context, but reduce each slot before allowing aggregate
        // startup context to become the dominant resident allocation.
        if (safeSlots > 1 && target > 2048) {
            int slotAdjusted = Math.max(2048, target / Math.min(4, safeSlots));
            if (slotAdjusted < target) {
                target = slotAdjusted;
                reason = reason.equals("configured") ? "parallel_slot_headroom" : reason + "+parallel_slots";
            }
        }

        return new Profile(Math.max(512, target), reason, availableBytes, Math.max(0L, modelBytes));
    }

    static Profile recovery(Profile prior, int configuredPerSlotContext, int slots, long modelBytes, long availableBytes) {
        Profile fresh = select(configuredPerSlotContext, slots, modelBytes, availableBytes);
        int priorContext = prior == null ? Math.max(512, configuredPerSlotContext) : prior.contextTokens();
        int recovered = Math.max(1024, Math.min(fresh.contextTokens(), priorContext / 2));
        if (recovered >= priorContext && priorContext > 1024) recovered = Math.max(1024, priorContext / 2);
        return new Profile(recovered, "sigterm_low_memory_retry", availableBytes, Math.max(0L, modelBytes));
    }

    record Profile(int contextTokens, String reason, long availableBytes, long modelBytes) {
        Profile {
            contextTokens = Math.max(512, contextTokens);
            reason = reason == null || reason.isBlank() ? "configured" : reason;
            availableBytes = Math.max(0L, availableBytes);
            modelBytes = Math.max(0L, modelBytes);
        }

        long availableMiB() { return availableBytes / MIB; }
        long modelMiB() { return modelBytes / MIB; }
        boolean reducedFrom(int configured) { return contextTokens < Math.max(512, configured); }
    }
}
