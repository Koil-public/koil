package com.spirit.koil.api.model.runtime.universal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.IntFunction;

/** Generates conservative, runtime-neutral execution geometries for measurement. */
public final class KoilCandidateGenerator {
    private KoilCandidateGenerator() {}

    public static List<KoilBenchmarkCandidate> baseline(
            KoilRuntimeBackend acceleratorBackend,
            String device,
            int modelLayers,
            int currentHybridLayers,
            int physicalThreads,
            int logicalThreads,
            String contextRegime,
            IntFunction<int[]> hybridBatchGeometry
    ) {
        return baseline(acceleratorBackend, device, modelLayers, currentHybridLayers, physicalThreads,
                logicalThreads, contextRegime, hybridBatchGeometry, KoilCandidateGenerationBudget.unknown());
    }

    public static List<KoilBenchmarkCandidate> baseline(
            KoilRuntimeBackend acceleratorBackend,
            String device,
            int modelLayers,
            int currentHybridLayers,
            int physicalThreads,
            int logicalThreads,
            String contextRegime,
            IntFunction<int[]> hybridBatchGeometry,
            KoilCandidateGenerationBudget generationBudget
    ) {
        KoilCandidateGenerationBudget budget = generationBudget == null
                ? KoilCandidateGenerationBudget.unknown() : generationBudget;
        int baseBatch = budget.baselineBatchSize();
        int baseUbatch = budget.baselineMicroBatchSize();
        LinkedHashMap<String, KoilBenchmarkCandidate> candidates = new LinkedHashMap<>();
        add(candidates, candidate("cpu-physical", acceleratorBackend, KoilPlacementPolicy.CPU, 0,
                physicalThreads, logicalThreads, 50, 1, baseBatch, baseUbatch,
                KoilStatePlacement.HOST, KoilOperatorPlacement.HOST,
                KoilBenchmarkCandidate.ValidationMode.EXACT, device, contextRegime, budget));
        add(candidates, candidate("cpu-logical", acceleratorBackend, KoilPlacementPolicy.CPU, 0,
                logicalThreads, logicalThreads, 50, 1, baseBatch, baseUbatch,
                KoilStatePlacement.HOST, KoilOperatorPlacement.HOST,
                KoilBenchmarkCandidate.ValidationMode.EXACT, device, contextRegime, budget));

        SortedSet<Integer> layers = hybridLayers(modelLayers, currentHybridLayers);
        for (int gpuLayers : layers) {
            int[] geometry = hybridBatchGeometry == null ? new int[]{2048, 512} : hybridBatchGeometry.apply(gpuLayers);
            int batch = geometry == null || geometry.length < 1 ? baseBatch : geometry[0];
            int ubatch = geometry == null || geometry.length < 2 ? Math.min(baseUbatch, batch) : geometry[1];
            if (!budget.allowLargeBatch()) batch = Math.min(batch, baseBatch);
            if (!budget.allowLargeMicroBatch()) ubatch = Math.min(ubatch, baseUbatch);
            ubatch = Math.min(ubatch, batch);
            add(candidates, candidate("hybrid-" + gpuLayers, acceleratorBackend, KoilPlacementPolicy.HYBRID, gpuLayers,
                    physicalThreads, logicalThreads, 50, 1, batch, ubatch,
                    KoilStatePlacement.HOST, KoilOperatorPlacement.HOST,
                    KoilBenchmarkCandidate.ValidationMode.EXACT, device, contextRegime, budget));
        }

        add(candidates, candidate("gpu-physical", acceleratorBackend, KoilPlacementPolicy.GPU, -1,
                physicalThreads, logicalThreads, 50, 1, baseBatch, baseUbatch,
                KoilStatePlacement.ACCELERATOR, KoilOperatorPlacement.ACCELERATOR,
                KoilBenchmarkCandidate.ValidationMode.ACCELERATOR_FIT, device, contextRegime, budget));
        if (budget.allowLargeBatch() || budget.allowLargeMicroBatch()) {
            add(candidates, candidate("gpu-logical-hot", acceleratorBackend, KoilPlacementPolicy.GPU, -1,
                    logicalThreads, logicalThreads, 100, 1,
                    budget.allowLargeBatch() ? 2048 : baseBatch,
                    budget.allowLargeMicroBatch() ? 512 : baseUbatch,
                    KoilStatePlacement.ACCELERATOR, KoilOperatorPlacement.ACCELERATOR,
                    KoilBenchmarkCandidate.ValidationMode.ACCELERATOR_FIT, device, contextRegime, budget));
        }
        return List.copyOf(candidates.values());
    }

    public static List<KoilBenchmarkCandidate> refinements(KoilBenchmarkCandidate base, int physicalThreads, int logicalThreads) {
        return refinements(base, physicalThreads, logicalThreads, KoilCandidateGenerationBudget.unknown());
    }

    public static List<KoilBenchmarkCandidate> refinements(
            KoilBenchmarkCandidate base,
            int physicalThreads,
            int logicalThreads,
            KoilCandidateGenerationBudget generationBudget
    ) {
        if (base == null) return List.of();
        KoilCandidateGenerationBudget budget = generationBudget == null
                ? KoilCandidateGenerationBudget.unknown() : generationBudget;
        LinkedHashMap<String, KoilBenchmarkCandidate> result = new LinkedHashMap<>();
        int[] threads = physicalThreads == logicalThreads
                ? new int[]{Math.max(1, physicalThreads)}
                : new int[]{Math.max(1, physicalThreads), Math.max(1, logicalThreads)};
        for (int threadCount : threads) {
            for (int poll : new int[]{0, 50, 100}) {
                add(result, copy(base, "refine-t" + threadCount + "-p" + poll,
                        threadCount, logicalThreads, poll, poll == 0 ? 0 : 1,
                        budget.baselineBatchSize(), budget.baselineMicroBatchSize()));
            }
        }
        add(result, copy(base, "refine-b1024-ub256", base.settings().generationThreads(), logicalThreads,
                base.settings().pollPercent(), base.settings().batchPollMode(), 1024, 256));
        if (budget.allowLargeBatch()) {
            add(result, copy(base, "refine-b2048-ub256", base.settings().generationThreads(), logicalThreads,
                    base.settings().pollPercent(), base.settings().batchPollMode(), 2048, 256));
        }
        if (budget.allowLargeMicroBatch()) {
            add(result, copy(base, "refine-b2048-ub512", base.settings().generationThreads(), logicalThreads,
                    base.settings().pollPercent(), base.settings().batchPollMode(), 2048, 512));
        }
        return List.copyOf(result.values());
    }

    private static KoilBenchmarkCandidate copy(KoilBenchmarkCandidate base, String label, int threads, int batchThreads,
                                                int poll, int batchPoll, int batch, int ubatch) {
        KoilExecutionSettings s = base.settings();
        return new KoilBenchmarkCandidate(label, label, base.backend(),
                new KoilExecutionSettings(s.placement(), s.device(), s.gpuLayers(), threads, batchThreads,
                        batch, ubatch, poll, batchPoll, s.statePlacement(), s.operatorPlacement(),
                        s.flashAttention(), s.repack(), s.memoryBudgetBytes()),
                base.validationMode(), base.contextRegime(), base.attributes());
    }

    private static KoilBenchmarkCandidate candidate(String label, KoilRuntimeBackend backend, KoilPlacementPolicy placement,
                                                     int gpuLayers, int threads, int batchThreads, int poll, int batchPoll,
                                                     int batch, int ubatch, KoilStatePlacement state, KoilOperatorPlacement operators,
                                                     KoilBenchmarkCandidate.ValidationMode validation, String device, String context,
                                                     KoilCandidateGenerationBudget generationBudget) {
        KoilRuntimeBackend resolvedBackend = placement == KoilPlacementPolicy.CPU ? KoilRuntimeBackend.CPU
                : (backend == null ? KoilRuntimeBackend.UNKNOWN : backend);
        KoilCandidateGenerationBudget budget = generationBudget == null
                ? KoilCandidateGenerationBudget.unknown() : generationBudget;
        KoilExecutionSettings settings = new KoilExecutionSettings(placement, device, gpuLayers, threads, batchThreads,
                batch, ubatch, poll, batchPoll, state, operators, KoilFeatureMode.AUTO, true,
                budget.discretionaryBudgetBytes());
        return new KoilBenchmarkCandidate(label, label, resolvedBackend, settings, validation, context, budget.attributes());
    }

    private static SortedSet<Integer> hybridLayers(int modelLayers, int currentHybridLayers) {
        SortedSet<Integer> layers = new TreeSet<>();
        if (modelLayers > 1) {
            layers.add(Math.max(1, modelLayers / 4));
            layers.add(Math.max(1, modelLayers / 2));
            layers.add(Math.max(1, (modelLayers * 3) / 4));
            layers.add(Math.max(1, modelLayers - 1));
        } else {
            for (int value : new int[]{1, 2, 4, 8, 16}) layers.add(value);
        }
        if (currentHybridLayers > 0 && (modelLayers <= 1 || currentHybridLayers < modelLayers)) layers.add(currentHybridLayers);
        if (modelLayers > 1) layers.removeIf(value -> value >= modelLayers);
        return layers;
    }

    private static void add(LinkedHashMap<String, KoilBenchmarkCandidate> target, KoilBenchmarkCandidate candidate) {
        target.putIfAbsent(candidate.geometryKey(), candidate);
    }
}
