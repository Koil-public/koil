package com.spirit.koil.api.model.provider.llamacpp;

/**
 * Pure policy for the MAX "safe" context target. The normal runtime memory-fit policy remains
 * authoritative; this helper only chooses the ceiling to probe and validates the exact context
 * reported after that discovery restart.
 */
public final class LlamaCppSafeBenchmarkContextSelection {
    private static final int[] BENCHMARK_TIERS_DESC = {131072, 128000, 65536, 32768, 16384, 8192, 4096, 2048};

    private LlamaCppSafeBenchmarkContextSelection() {}

    public static int probeTargetForModelCeiling(int modelCeiling) {
        if (modelCeiling <= 0) return 0;
        for (int tier : BENCHMARK_TIERS_DESC) {
            if (tier <= modelCeiling) return tier;
        }
        return modelCeiling >= 512 ? modelCeiling : 0;
    }

    public static int acceptDiscoveredContext(int modelCeiling, int probeTarget, int actualContext) {
        if (actualContext <= 0) {
            throw new IllegalStateException("MAX safe-context discovery did not report an active runtime context.");
        }
        if (modelCeiling > 0 && actualContext > modelCeiling) {
            throw new IllegalStateException("MAX safe-context discovery exceeded the selected model ceiling: actual="
                    + actualContext + ", ceiling=" + modelCeiling);
        }
        if (probeTarget > 0 && actualContext > probeTarget) {
            throw new IllegalStateException("MAX safe-context discovery exceeded its probe target: actual="
                    + actualContext + ", probe=" + probeTarget);
        }
        if (actualContext < 512) {
            throw new IllegalStateException("MAX safe-context discovery produced an unusably small context: " + actualContext);
        }
        return actualContext;
    }
}
