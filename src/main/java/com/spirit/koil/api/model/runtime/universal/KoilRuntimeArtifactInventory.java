package com.spirit.koil.api.model.runtime.universal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** Bounded inspection of an installed native runtime. This reports compiled capabilities, not hardware presence. */
public record KoilRuntimeArtifactInventory(
        String runtimeId,
        Path root,
        Set<KoilRuntimeBackend> compiledBackends,
        boolean benchmarkToolPresent,
        String evidence
) {
    public KoilRuntimeArtifactInventory {
        runtimeId = runtimeId == null ? "" : runtimeId.strip();
        root = root == null ? null : root.toAbsolutePath().normalize();
        compiledBackends = compiledBackends == null ? Set.of() : Set.copyOf(compiledBackends);
        evidence = evidence == null ? "" : evidence.strip();
    }

    public static KoilRuntimeArtifactInventory inspect(String runtimeId, Path executable) {
        Path root = executable == null ? null : executable.toAbsolutePath().normalize().getParent();
        if (root == null || !Files.isDirectory(root)) {
            return new KoilRuntimeArtifactInventory(runtimeId, root, Set.of(), false, "runtime directory unavailable");
        }
        EnumSet<KoilRuntimeBackend> backends = EnumSet.of(KoilRuntimeBackend.CPU);
        boolean bench = false;
        int scanned = 0;
        try (Stream<Path> paths = Files.walk(root, 2)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (++scanned > 4096) break;
                if (!Files.isRegularFile(path)) continue;
                String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.contains("vulkan")) backends.add(KoilRuntimeBackend.VULKAN);
                if (n.contains("cuda") || n.contains("cublas")) backends.add(KoilRuntimeBackend.CUDA);
                if (n.contains("metal")) backends.add(KoilRuntimeBackend.METAL);
                if (n.contains("rocm")) backends.add(KoilRuntimeBackend.ROCM);
                if (n.contains("hip")) backends.add(KoilRuntimeBackend.HIP);
                if (n.contains("sycl")) backends.add(KoilRuntimeBackend.SYCL);
                if (n.contains("opencl")) backends.add(KoilRuntimeBackend.OPENCL);
                if (n.equals("llama-bench") || n.equals("llama-bench.exe")) bench = true;
            }
        } catch (IOException ignored) {
        }
        return new KoilRuntimeArtifactInventory(runtimeId, root, backends, bench,
                "inspected " + Math.min(scanned, 4096) + " runtime entries");
    }

    public boolean hasAcceleratorBackend() {
        return compiledBackends.stream().anyMatch(b -> b != KoilRuntimeBackend.CPU && b != KoilRuntimeBackend.UNKNOWN);
    }
}
