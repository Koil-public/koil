package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilMemoryPressureSnapshot;
import com.spirit.koil.api.model.runtime.universal.KoilMemoryPressureStabilizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class LlamaCppRuntimeMemoryEnvelopeStoreProof {
    private static final long MIB = 1024L * 1024L;

    private LlamaCppRuntimeMemoryEnvelopeStoreProof() {}

    public static void main(String[] args) throws Exception {
        Path model = Files.createTempFile("koil-memory-envelope-proof", ".gguf");
        try {
            var critical = new KoilMemoryPressureSnapshot(
                    16L * 1024L * MIB, 400L * MIB, 768L * MIB, 1776L * MIB,
                    0L, KoilMemoryPressureSnapshot.Pressure.CRITICAL,
                    KoilMemoryPressureSnapshot.Phase.RUNTIME, 0.025D, Instant.now());
            var result = new KoilMemoryPressureStabilizer.Result(
                    critical, critical, KoilMemoryPressureStabilizer.Trend.FALLING_FAST, 0);
            for (int i = 0; i < 3; i++) {
                LlamaCppRuntimeMemoryEnvelopeStore.record(
                        model, "proof-hw", "k=fp16,v=fp16", 8192, 4L * 1024L * MIB, result);
            }
            var recommendation = LlamaCppRuntimeMemoryEnvelopeStore.recommend(
                    model, "proof-hw", "k=fp16,v=fp16", 8192, 4L * 1024L * MIB);
            require(recommendation.learnedCapApplied(), "critical evidence did not produce a learned cap");
            require(recommendation.contextTokens() == 4096, "8K should step down to 4K after sustained critical residency");

            var q8 = LlamaCppRuntimeMemoryEnvelopeStore.recommend(
                    model, "proof-hw", "k=q8_block,v=fp16", 8192, 4L * 1024L * MIB);
            require(!q8.learnedCapApplied(), "FP16 evidence leaked into Q8 regime");

            var moreHeadroom = LlamaCppRuntimeMemoryEnvelopeStore.recommend(
                    model, "proof-hw", "k=fp16,v=fp16", 8192, 6L * 1024L * MIB);
            require(!moreHeadroom.learnedCapApplied(), "substantially higher launch headroom should permit retest");

            var floor = LlamaCppRuntimeMemoryEnvelopeStore.recommend(
                    model, "proof-hw", "k=fp16,v=fp16", 2048, 400L * MIB);
            require(floor.contextTokens() == 2048, "resident learning must not reduce below the 2K floor");
            System.out.println("llama.cpp runtime memory envelope proof passed");
        } finally {
            Files.deleteIfExists(model);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
