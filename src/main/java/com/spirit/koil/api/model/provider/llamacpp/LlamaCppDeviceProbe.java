package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.LocalModelRuntimeLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded wrapper around llama.cpp's own {@code --list-devices} ground truth.
 *
 * <p>The probe is intentionally diagnostic rather than authoritative. Upstream
 * backends do not all print an identical device-list shape, and older/newer
 * llama.cpp builds can emit useful accelerator evidence before the final
 * {@code Available devices:} section. Koil therefore preserves the exit code
 * and bounded raw output and lets the real model startup provide the final
 * offload verification.</p>
 */
public final class LlamaCppDeviceProbe {
    private static final Pattern DEVICE = Pattern.compile(
            "(?i)^\\s*((?:Vulkan|CUDA|ROCm|HIP|SYCL|Metal|MTL|CANN|MUSA|OpenCL)\\d*)\\s*:\\s*(.+?)\\s*$"
    );
    private static final Pattern VULKAN_ENUMERATION = Pattern.compile(
            "(?i)^\\s*ggml_vulkan\\s*:\\s*(\\d+)\\s*=\\s*(.+?)\\s*$"
    );
    private static final Pattern POSITIVE_DEVICE_COUNT = Pattern.compile(
            "(?i).*\\bfound\\s+([1-9]\\d*)\\s+(?:vulkan|cuda|hip|rocm|sycl|metal|opencl|cann|musa)\\s+devices?\\b.*"
    );
    private static final int LOG_OUTPUT_LIMIT = 1800;
    private static final int CAPTURE_LIMIT = 64 * 1024;
    private static final Duration DEFAULT_PROBE_TIMEOUT = Duration.ofMinutes(2);

    private LlamaCppDeviceProbe() {
    }

    /** Compatibility helper for callers that only need concrete device ids. */
    public static List<Device> discover(Path executable) throws IOException {
        return probe(executable).devices();
    }

    /** Compatibility helper for callers that only need concrete device ids. */
    public static List<Device> discover(Path executable, Duration timeout) throws IOException {
        return probe(executable, timeout).devices();
    }

    public static ProbeResult probe(Path executable) throws IOException {
        return probe(executable, DEFAULT_PROBE_TIMEOUT);
    }

    public static ProbeResult probe(Path executable, Duration timeout) throws IOException {
        if (executable == null || !Files.isRegularFile(executable)) {
            throw new IOException("llama.cpp executable is unavailable for device probing");
        }
        Path normalized = executable.toAbsolutePath().normalize();
        ProcessBuilder builder = new ProcessBuilder(normalized.toString(), "--list-devices");
        Path parent = normalized.getParent();
        if (parent != null && Files.isDirectory(parent)) {
            builder.directory(parent.toFile());
        }
        builder.redirectErrorStream(true);
        Process process = builder.start();

        ProbeOutputCollector collector = new ProbeOutputCollector(process);
        CompletableFuture<Void> reader = CompletableFuture.runAsync(collector::read);
        long waitMillis = Math.max(1000L, timeout == null ? DEFAULT_PROBE_TIMEOUT.toMillis() : timeout.toMillis());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        try {
            while (true) {
                if (collector.concreteDeviceSeen()) {
                    stopProbeProcess(process);
                    awaitReader(reader);
                    ProbeResult result = inspect(0, collector.output(), false);
                    log(result);
                    return result;
                }
                if (process.waitFor(100L, TimeUnit.MILLISECONDS)) {
                    awaitReader(reader);
                    ProbeResult result = inspect(process.exitValue(), collector.output(), false);
                    log(result);
                    return result;
                }
                if (System.nanoTime() >= deadline) {
                    stopProbeProcess(process);
                    awaitReader(reader);
                    ProbeResult timedOut = inspect(-1, collector.output(), true);
                    log(timedOut);
                    throw new IOException("llama.cpp device probe timed out; " + timedOut.summary());
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stopProbeProcess(process);
            throw new IOException("llama.cpp device probe was interrupted", interrupted);
        }
    }

    private static void stopProbeProcess(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(500L, TimeUnit.MILLISECONDS) && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(500L, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void awaitReader(CompletableFuture<Void> reader) {
        try {
            reader.get(1500L, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // The process may be inside native backend teardown. Captured output is
            // already sufficient once a concrete accelerator line has been seen.
        }
    }

    private static boolean isConcreteDeviceLine(String line) {
        if (line == null || line.isBlank()) return false;
        return DEVICE.matcher(line).matches() || VULKAN_ENUMERATION.matcher(line).matches();
    }

    private static final class ProbeOutputCollector {
        private final Process process;
        private final StringBuilder output = new StringBuilder();
        private final AtomicBoolean concreteDeviceSeen = new AtomicBoolean();

        private ProbeOutputCollector(Process process) {
            this.process = process;
        }

        private void read() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    append(line);
                    if (isConcreteDeviceLine(line)) {
                        concreteDeviceSeen.set(true);
                    }
                }
            } catch (IOException ignored) {
                // Native probe teardown can close the merged output stream while
                // the reader is blocked. Preserve whatever was captured.
            }
        }

        private synchronized void append(String line) {
            if (output.length() >= CAPTURE_LIMIT) return;
            int remaining = CAPTURE_LIMIT - output.length();
            String chunk = line + System.lineSeparator();
            output.append(chunk, 0, Math.min(remaining, chunk.length()));
        }

        private boolean concreteDeviceSeen() {
            return concreteDeviceSeen.get();
        }

        private synchronized String output() {
            return output.toString();
        }
    }

    /** Package-private seam used by the proof without starting a native binary. */
    static ProbeResult inspect(int exitCode, String output) {
        return inspect(exitCode, output, false);
    }

    private static ProbeResult inspect(int exitCode, String output, boolean timedOut) {
        String text = output == null ? "" : output;
        List<Device> devices = parse(text);
        boolean positiveEvidence = !devices.isEmpty() || POSITIVE_DEVICE_COUNT.matcher(text).find();
        boolean availableHeader = text.lines().anyMatch(line -> line.trim().equalsIgnoreCase("Available devices:"));
        Status status;
        if (positiveEvidence) {
            status = Status.AVAILABLE;
        } else if (timedOut || exitCode != 0) {
            status = Status.FAILED;
        } else if (availableHeader) {
            status = Status.NONE;
        } else {
            status = Status.INCONCLUSIVE;
        }
        return new ProbeResult(status, exitCode, devices, bounded(text), timedOut);
    }

    static List<Device> parse(String output) {
        Map<String, Device> devices = new LinkedHashMap<>();
        for (String line : (output == null ? "" : output).split("\\R")) {
            Matcher direct = DEVICE.matcher(line);
            if (direct.matches()) {
                putDevice(devices, direct.group(1), direct.group(2));
                continue;
            }
            Matcher vulkan = VULKAN_ENUMERATION.matcher(line);
            if (vulkan.matches()) {
                putDevice(devices, "Vulkan" + vulkan.group(1), vulkan.group(2));
            }
        }
        return List.copyOf(new ArrayList<>(devices.values()));
    }

    /**
     * Returns whether a native llama.cpp device identifier denotes a Metal accelerator.
     *
     * <p>Current llama.cpp builds use both human-readable ids such as {@code Metal}
     * / {@code Metal0} and the native macOS backend form {@code MTL0}. Preserve the
     * original id for {@code --device}; this helper only classifies it.</p>
     */
    public static boolean isMetalDeviceId(String id) {
        if (id == null) return false;
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("metal")) return true;
        if (normalized.startsWith("metal") && normalized.length() > 5) {
            return normalized.substring(5).chars().allMatch(Character::isDigit);
        }
        if (normalized.startsWith("mtl") && normalized.length() > 3) {
            return normalized.substring(3).chars().allMatch(Character::isDigit);
        }
        return false;
    }

    private static void putDevice(Map<String, Device> devices, String id, String detail) {
        Device candidate = new Device(id, detail);
        if (candidate.id().isBlank()) return;
        String key = candidate.id().toLowerCase(Locale.ROOT);
        Device existing = devices.get(key);
        // Prefer the canonical Available-devices line when both an early backend
        // enumeration and the final list name the same device.
        if (existing == null || candidate.detail().length() >= existing.detail().length()) {
            devices.put(key, candidate);
        }
    }

    private static String bounded(String output) {
        String compact = output == null ? "" : output.strip();
        if (compact.length() <= LOG_OUTPUT_LIMIT) return compact;
        return compact.substring(0, LOG_OUTPUT_LIMIT) + "...";
    }

    private static void log(ProbeResult result) {
        LocalModelRuntimeLog.write("llama_devices", result.summary());
    }

    public enum Status {
        AVAILABLE,
        NONE,
        INCONCLUSIVE,
        FAILED
    }

    public record ProbeResult(
            Status status,
            int exitCode,
            List<Device> devices,
            String output,
            boolean timedOut
    ) {
        public ProbeResult {
            status = status == null ? Status.INCONCLUSIVE : status;
            devices = devices == null ? List.of() : List.copyOf(devices);
            output = output == null ? "" : output.strip();
        }

        public boolean hasAcceleratorEvidence() {
            return status == Status.AVAILABLE;
        }

        public String summary() {
            String ids = devices.isEmpty()
                    ? "none parsed"
                    : devices.stream().map(Device::id).reduce((left, right) -> left + "," + right).orElse("none parsed");
            String raw = output.isBlank() ? "no probe output" : output.replace('\n', ' ').replace('\r', ' ').strip();
            if (raw.length() > 420) raw = raw.substring(0, 420) + "...";
            return "status=" + status.name().toLowerCase(Locale.ROOT)
                    + " | exit=" + exitCode
                    + " | devices=" + ids
                    + (timedOut ? " | timed_out=true" : "")
                    + " | output=" + raw;
        }
    }

    public record Device(String id, String detail) {
        public Device {
            id = id == null ? "" : id.trim();
            detail = detail == null ? "" : detail.trim();
        }
    }
}
