package com.spirit.koil.api.model.runtime.universal;

import java.util.Map;

/** Executable policy proof covering material absolute long-context savings. */
public final class KoilStatePrecisionPolicyProof {
    private static final long MIB = 1024L * 1024L;

    private KoilStatePrecisionPolicyProof() {}

    public static void main(String[] args) {
        KoilMemoryPressureSnapshot healthy = snapshot(7253L, 5476L, KoilMemoryPressureSnapshot.Pressure.HEALTHY);
        KoilMemoryPressureSnapshot moderate = snapshot(4669L, 2892L, KoilMemoryPressureSnapshot.Pressure.MODERATE);
        KoilMemoryPressureSnapshot constrained = snapshot(1400L, 0L, KoilMemoryPressureSnapshot.Pressure.CONSTRAINED);

        Map<String, String> lfm2Like = Map.of(
                "lfm2.block_count", "16",
                "lfm2.embedding_length", "2048",
                "lfm2.attention.head_count", "16",
                "lfm2.attention.head_count_kv", "[4,0,0,4,0,0,4,0,0,4,0,0,4,0,0,4]"
        );

        KoilStateMemoryEstimate at2k = KoilStateMemoryEstimate.fromMetadata(lfm2Like, 2048);
        KoilStateMemoryEstimate at8k = KoilStateMemoryEstimate.fromMetadata(lfm2Like, 8192);
        KoilStateMemoryEstimate at64k = KoilStateMemoryEstimate.fromMetadata(lfm2Like, 65536);
        KoilStateMemoryEstimate at128k = KoilStateMemoryEstimate.fromMetadata(lfm2Like, 131072);

        require(mib(at2k.fullPrecisionBytes()) == 24L, "LFM2-like 2K estimate should be 24 MiB");
        require(mib(at8k.fullPrecisionBytes()) == 96L, "LFM2-like 8K estimate should be 96 MiB");
        require(mib(at128k.fullPrecisionBytes()) == 1536L, "LFM2-like 128K estimate should be 1536 MiB");
        require(mib(at128k.q8KeySavingsBytes()) >= 350L, "128K Q8 K savings should be roughly 360 MiB");

        KoilStatePrecisionDecision shortContext = KoilStatePrecisionPolicy.select(
                constrained, 2048, KoilRuntimeBackend.VULKAN, at2k);
        require(shortContext.keyPrecision() == KoilStatePrecision.FP16,
                "2K must stay FP16 even under constrained memory");

        KoilStatePrecisionDecision normal8k = KoilStatePrecisionPolicy.select(
                moderate, 8192, KoilRuntimeBackend.VULKAN, at8k);
        require(normal8k.keyPrecision() == KoilStatePrecision.FP16,
                "small 8K LFM2 state should remain FP16 because absolute savings are tiny");

        KoilStatePrecisionDecision healthy64k = KoilStatePrecisionPolicy.select(
                healthy, 65536, KoilRuntimeBackend.VULKAN, at64k);
        require(healthy64k.keyPrecision() == KoilStatePrecision.FP16,
                "64K LFM2 state should remain FP16 when projected savings stay below 256 MiB");

        KoilStatePrecisionDecision healthy128k = KoilStatePrecisionPolicy.select(
                healthy, 131072, KoilRuntimeBackend.VULKAN, at128k);
        require(healthy128k.keyPrecision() == KoilStatePrecision.Q8_BLOCK,
                "128K LFM2 state with >256 MiB material absolute savings should use Q8 K");
        require(healthy128k.valuePrecision() == KoilStatePrecision.FP16,
                "conservative tier must keep V state FP16");
        require(healthy128k.estimatedSavingsBytes() == at128k.q8KeySavingsBytes(),
                "decision must carry estimated K-state savings");

        KoilStatePrecisionDecision unknownBackend = KoilStatePrecisionPolicy.select(
                healthy, 131072, KoilRuntimeBackend.UNKNOWN, at128k);
        require(unknownBackend.keyPrecision() == KoilStatePrecision.FP16,
                "unknown backend must remain conservative even at 128K");

        System.out.println("Koil state precision policy proof passed");
    }

    private static KoilMemoryPressureSnapshot snapshot(
            long availableMiB, long budgetMiB, KoilMemoryPressureSnapshot.Pressure pressure) {
        long installed = 16L * 1024L * MIB;
        long available = availableMiB * MIB;
        return new KoilMemoryPressureSnapshot(
                installed, available, 768L * MIB, 1776L * MIB, budgetMiB * MIB, pressure,
                KoilMemoryPressureSnapshot.Phase.LAUNCH, (double) available / (double) installed, java.time.Instant.now());
    }

    private static long mib(long bytes) {
        return bytes / MIB;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
