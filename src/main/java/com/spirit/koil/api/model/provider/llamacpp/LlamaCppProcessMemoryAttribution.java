package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilMemoryPressureSnapshot;
import com.spirit.koil.api.model.runtime.universal.KoilMemoryPressureStabilizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Best-effort process resident-memory attribution for the native llama.cpp process and the
 * current Minecraft/JVM process. Linux uses /proc smaps_rollup PSS when available and falls
 * back to VmRSS. Other platforms remain explicitly unknown rather than inventing attribution.
 */
final class LlamaCppProcessMemoryAttribution {
    private static final long MIB = 1024L * 1024L;

    record ProcessMemory(long rssBytes, long pssBytes, String source) {
        long preferredBytes() { return pssBytes > 0L ? pssBytes : rssBytes; }
        boolean known() { return preferredBytes() > 0L; }
    }

    record Snapshot(
            ProcessMemory nativeProcess,
            ProcessMemory jvmProcess,
            String classification,
            String detail
    ) {
        static Snapshot unknown(String detail) {
            ProcessMemory unknown = new ProcessMemory(0L, 0L, "unavailable");
            return new Snapshot(unknown, unknown, "unknown", detail == null ? "" : detail);
        }
    }

    private LlamaCppProcessMemoryAttribution() {}

    static Snapshot sample(Process nativeProcess, int contextTokens, KoilMemoryPressureStabilizer.Result observation) {
        if (observation == null || observation.effective() == null) return Snapshot.unknown("no live memory observation");
        if (contextTokens > 2048) return Snapshot.unknown("context floor not reached");
        KoilMemoryPressureSnapshot effective = observation.effective();
        if (effective.pressure() != KoilMemoryPressureSnapshot.Pressure.CRITICAL) {
            return Snapshot.unknown("context floor is not under critical pressure");
        }
        if (!isLinux()) return Snapshot.unknown("process attribution currently requires Linux /proc");
        long nativePid = nativeProcess != null && nativeProcess.isAlive() ? nativeProcess.pid() : -1L;
        ProcessMemory nativeMemory = nativePid > 0L ? processMemory(nativePid) : new ProcessMemory(0L, 0L, "no_native_process");
        ProcessMemory jvmMemory = processMemory(ProcessHandle.current().pid());
        String classification = classify(nativeMemory, jvmMemory, effective.availableBytes(), effective.safetyFloorBytes());
        String detail = "native=" + mib(nativeMemory.preferredBytes()) + "MiB"
                + " | jvm=" + mib(jvmMemory.preferredBytes()) + "MiB"
                + " | available=" + mib(effective.availableBytes()) + "MiB"
                + " | source=" + source(nativeMemory, jvmMemory);
        return new Snapshot(nativeMemory, jvmMemory, classification, detail);
    }

    private static String classify(ProcessMemory nativeMemory, ProcessMemory jvmMemory, long availableBytes, long safetyFloorBytes) {
        long nativeBytes = nativeMemory.preferredBytes();
        long jvmBytes = jvmMemory.preferredBytes();
        if (nativeBytes <= 0L && jvmBytes <= 0L) return "unknown";
        if (nativeBytes >= 768L * MIB && nativeBytes >= (long) (jvmBytes * 0.80D)) return "native_runtime_heavy";
        if (jvmBytes >= 1024L * MIB && jvmBytes >= (long) (nativeBytes * 1.35D)) return "minecraft_jvm_heavy";
        if (nativeBytes + jvmBytes >= 1536L * MIB) return "combined_runtime_pressure";
        if (availableBytes < safetyFloorBytes) return "external_or_shared_system_pressure";
        return "mixed_resident_pressure";
    }

    private static ProcessMemory processMemory(long pid) {
        long pss = readKbField(Path.of("/proc", Long.toString(pid), "smaps_rollup"), "Pss:");
        long rssFromRollup = readKbField(Path.of("/proc", Long.toString(pid), "smaps_rollup"), "Rss:");
        if (pss > 0L || rssFromRollup > 0L) return new ProcessMemory(rssFromRollup, pss, "proc_smaps_rollup");
        long rss = readKbField(Path.of("/proc", Long.toString(pid), "status"), "VmRSS:");
        return new ProcessMemory(rss, 0L, rss > 0L ? "proc_status" : "unavailable");
    }

    private static long readKbField(Path path, String key) {
        try {
            if (!Files.isRegularFile(path)) return 0L;
            List<String> lines = Files.readAllLines(path);
            for (String line : lines) {
                if (line == null || !line.startsWith(key)) continue;
                String digits = line.substring(key.length()).replaceAll("[^0-9]", "");
                if (digits.isBlank()) return 0L;
                return Long.parseLong(digits) * 1024L;
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return 0L;
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static long mib(long bytes) { return Math.max(0L, bytes) / MIB; }
    private static String source(ProcessMemory nativeMemory, ProcessMemory jvmMemory) {
        return nativeMemory.source() + "/" + jvmMemory.source();
    }
}
