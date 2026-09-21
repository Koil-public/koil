package com.spirit.koil.api.model.provider.llamacpp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class LlamaCppDeviceProbeProof {
    private LlamaCppDeviceProbeProof() {
    }

    public static void main(String[] args) {
        List<LlamaCppDeviceProbe.Device> canonical = LlamaCppDeviceProbe.parse("""
                ggml_vulkan: Found 1 Vulkan devices:
                load_backend: loaded Vulkan backend
                Available devices:
                  Vulkan0: AMD Radeon Graphics (RADV) (16384 MiB, 12000 MiB free)
                """);
        require(canonical.size() == 1, "canonical device count was not parsed");
        require("Vulkan0".equals(canonical.get(0).id()), "canonical device id was not parsed");
        require(canonical.get(0).detail().contains("AMD Radeon Graphics"), "canonical device label was not parsed");

        List<LlamaCppDeviceProbe.Device> deckEnumeration = LlamaCppDeviceProbe.parse("""
                ggml_vulkan: Found 1 Vulkan devices:
                ggml_vulkan: 0 = AMD Custom GPU 0932 (RADV VANGOGH) | uma: 1
                """);
        require(deckEnumeration.size() == 1, "Steam Deck backend enumeration was not parsed");
        require("Vulkan0".equals(deckEnumeration.get(0).id()), "Steam Deck Vulkan id was not synthesized");

        List<LlamaCppDeviceProbe.Device> metal = LlamaCppDeviceProbe.parse("""
                Available devices:
                  Metal: Apple M3 Pro
                """);
        require(metal.size() == 1 && "Metal".equals(metal.get(0).id()), "non-numeric Metal id was not parsed");

        List<LlamaCppDeviceProbe.Device> intelMacMetal = LlamaCppDeviceProbe.parse("""
                Available devices:
                  MTL0: AMD Radeon Pro 580 (8192 MiB, 8191 MiB free)
                  BLAS: Accelerate (0 MiB, 0 MiB free)
                """);
        require(intelMacMetal.size() == 1, "Intel macOS MTL device count was not parsed");
        require("MTL0".equals(intelMacMetal.get(0).id()), "Intel macOS MTL device id was not preserved");
        require(intelMacMetal.get(0).detail().contains("AMD Radeon Pro 580"), "Intel macOS Metal device detail was not parsed");
        require(LlamaCppDeviceProbe.isMetalDeviceId("MTL0"), "MTL0 was not classified as Metal");
        require(LlamaCppDeviceProbe.isMetalDeviceId("Metal0"), "Metal0 was not classified as Metal");
        require(LlamaCppDeviceProbe.isMetalDeviceId("Metal"), "Metal was not classified as Metal");
        require(!LlamaCppDeviceProbe.isMetalDeviceId("BLAS"), "CPU BLAS backend was misclassified as Metal");

        LlamaCppDeviceProbe.ProbeResult available = LlamaCppDeviceProbe.inspect(0, """
                ggml_vulkan: Found 1 Vulkan devices:
                ggml_vulkan: 0 = AMD Radeon Graphics (RADV VANGOGH)
                """);
        require(available.status() == LlamaCppDeviceProbe.Status.AVAILABLE, "positive backend evidence was rejected");

        LlamaCppDeviceProbe.ProbeResult none = LlamaCppDeviceProbe.inspect(0, "Available devices:\n");
        require(none.status() == LlamaCppDeviceProbe.Status.NONE, "empty canonical list was not classified as none");

        LlamaCppDeviceProbe.ProbeResult failed = LlamaCppDeviceProbe.inspect(127,
                "llama-server: error while loading shared libraries: libggml-vulkan.so: cannot open shared object file");
        require(failed.status() == LlamaCppDeviceProbe.Status.FAILED, "native loader failure was not preserved");
        require(failed.summary().contains("libggml-vulkan.so"), "native loader diagnostic was discarded");

        proveEarlyDeviceCompletion();

        System.out.println("llama.cpp device probe proof passed.");
    }

    private static void proveEarlyDeviceCompletion() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        Path script = null;
        try {
            script = Files.createTempFile("koil-llama-device-probe-", ".sh");
            Files.writeString(script, "#!/bin/sh\nprintf 'Available devices:\\n  MTL0: AMD Radeon Pro 580 (8192 MiB, 8191 MiB free)\\n'\nsleep 30\n", StandardCharsets.UTF_8);
            script.toFile().setExecutable(true);
            long started = System.nanoTime();
            LlamaCppDeviceProbe.ProbeResult result = LlamaCppDeviceProbe.probe(script, Duration.ofSeconds(10));
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            require(result.status() == LlamaCppDeviceProbe.Status.AVAILABLE, "streaming probe rejected MTL0");
            require(result.devices().stream().anyMatch(device -> "MTL0".equals(device.id())), "streaming probe lost MTL0");
            require(elapsedMillis < 5000L, "streaming probe waited for helper exit instead of completing from device evidence: " + elapsedMillis + " ms");
        } catch (Exception failure) {
            throw new IllegalStateException("streaming device-probe proof failed", failure);
        } finally {
            if (script != null) {
                try { Files.deleteIfExists(script); } catch (Exception ignored) { }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
