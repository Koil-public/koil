package com.spirit.koil.api.model.install;

import com.spirit.koil.api.model.LocalModelRuntimeLog;
import com.spirit.koil.api.model.catalog.ModelRuntimeCompatibility;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Prepares the one Colibri model family that needs an upstream conversion step. */
final class ColibriModelPreparation {
    private static final String OLMOE_ENGINE = "olmoe";
    private static final String OLMOE_SCRIPT = "convert_olmoe_merged.py";
    private static final URI OLMOE_SCRIPT_URI = URI.create(
            "https://raw.githubusercontent.com/JustVugg/colibri/"
                    + ManagedRuntimeCatalog.COLIBRI_COMMIT + "/c/tools/" + OLMOE_SCRIPT);
    private static final String OLMOE_SCRIPT_SHA256 = "3e4ab4b4dd9b2a0a21925d2ce30945e059263a427773b7be17ff962de2a9c21a";
    private static final String PREPARED_MARKER = ".koil-colibri-prepared";
    private static final long OLMOE_OUTPUT_RESERVE = 8L * 1024L * 1024L * 1024L;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private ColibriModelPreparation() {
    }

    static boolean required(ModelRuntimeCompatibility compatibility) {
        return compatibility != null && "colibri".equals(compatibility.providerId())
                && OLMOE_ENGINE.equals(compatibility.engineId());
    }

    static long additionalStorageBytes(ModelRuntimeCompatibility compatibility) {
        return required(compatibility) ? OLMOE_OUTPUT_RESERVE : 0L;
    }

    static Path sourceDirectory(Path modelDirectory) {
        return modelDirectory.resolve(".koil-source");
    }

    static boolean prepared(ModelRuntimeCompatibility compatibility, Path modelDirectory) {
        if (!required(compatibility)) return true;
        try (var files = Files.list(modelDirectory)) {
            return Files.isRegularFile(modelDirectory.resolve(PREPARED_MARKER))
                    && Files.isRegularFile(modelDirectory.resolve("config.json"))
                    && files.anyMatch(path -> path.getFileName().toString().endsWith(".safetensors"));
        } catch (IOException failure) {
            return false;
        }
    }

    static void prepare(
            ModelRuntimeCompatibility compatibility,
            Path runtimeRoot,
            Path sourceDirectory,
            Path modelDirectory,
            ManagedRuntimeInstaller.ProgressListener progress,
            ManagedRuntimeInstaller.CancelSignal cancel
    ) throws IOException, ManagedRuntimeInstaller.CancelledException {
        if (!required(compatibility) || prepared(compatibility, modelDirectory)) return;
        checkCancelled(cancel);
        Path source = sourceDirectory.toAbsolutePath().normalize();
        Path destination = modelDirectory.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source.resolve("config.json"))) {
            throw new IOException("OLMoE source snapshot is incomplete; config.json is missing");
        }
        Path converter = ensureConverter(runtimeRoot, cancel);
        Path python = ensurePython(runtimeRoot, progress, cancel);
        progress.progress("verifying", "Converting OLMoE to Colibri merged int8", OLMOE_SCRIPT, -1L, -1L);
        run(List.of(python.toString(), converter.toString(), "--model", source.toString(),
                        "--out", destination.toString()), destination, progress, cancel,
                "OLMoE conversion");
        if (!Files.isRegularFile(destination.resolve("config.json"))
                || !hasTensorShard(destination)) {
            throw new IOException("OLMoE conversion completed without a runnable Colibri container");
        }
        Files.writeString(destination.resolve(PREPARED_MARKER), compatibility.modelRevision(),
                StandardCharsets.UTF_8);
        LocalModelRuntimeLog.write("colibri_model_prepare", "OLMoE merged-int8 container ready at " + destination);
    }

    private static Path ensureConverter(Path runtimeRoot, ManagedRuntimeInstaller.CancelSignal cancel)
            throws IOException, ManagedRuntimeInstaller.CancelledException {
        Path tool = runtimeRoot.toAbsolutePath().normalize().resolve(ManagedRuntimeCatalog.COLIBRI_RUNTIME_ID)
                .resolve("tools").resolve(OLMOE_SCRIPT);
        if (Files.isRegularFile(tool) && OLMOE_SCRIPT_SHA256.equals(sha256(tool))) return tool;
        checkCancelled(cancel);
        Files.createDirectories(tool.getParent());
        Path part = tool.resolveSibling(OLMOE_SCRIPT + ".part");
        HttpRequest request = HttpRequest.newBuilder(OLMOE_SCRIPT_URI).timeout(Duration.ofMinutes(2)).GET().build();
        try {
            HttpResponse<java.io.InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream responseBody = response.body()) {
                if (response.statusCode() != 200) throw new IOException("OLMoE converter download failed with HTTP " + response.statusCode());
                try (BufferedInputStream input = new BufferedInputStream(responseBody);
                 OutputStream output = Files.newOutputStream(part)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        checkCancelled(cancel);
                        if (read > 0) output.write(buffer, 0, read);
                    }
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ManagedRuntimeInstaller.CancelledException();
        }
        if (!OLMOE_SCRIPT_SHA256.equals(sha256(part))) {
            Files.deleteIfExists(part);
            throw new IOException("Pinned OLMoE converter checksum did not match");
        }
        Files.move(part, tool, StandardCopyOption.REPLACE_EXISTING);
        return tool;
    }

    private static Path ensurePython(
            Path runtimeRoot,
            ManagedRuntimeInstaller.ProgressListener progress,
            ManagedRuntimeInstaller.CancelSignal cancel
    ) throws IOException, ManagedRuntimeInstaller.CancelledException {
        Path root = runtimeRoot.toAbsolutePath().normalize();
        Path environment = root.resolve("python").resolve("olmoe-converter");
        Path python = environment.resolve(windows() ? "Scripts/python.exe" : "bin/python");
        if (!Files.isRegularFile(python)) {
            progress.progress("building", "Creating isolated OLMoE converter Python environment", "python", -1L, -1L);
            List<String> command = new ArrayList<>(hostPython());
            command.addAll(List.of("-m", "venv", environment.toString()));
            run(command, root, progress, cancel,
                    "OLMoE Python environment setup");
        }
        if (!succeeds(List.of(python.toString(), "-c", "import numpy, torch, safetensors, huggingface_hub"))) {
            progress.progress("building", "Installing pinned OLMoE converter dependencies", "python", -1L, -1L);
            run(List.of(python.toString(), "-m", "pip", "install", "--disable-pip-version-check", "--upgrade",
                            "--extra-index-url", "https://download.pytorch.org/whl/cpu",
                            "numpy", "torch==2.13.0+cpu", "safetensors==0.8.0", "huggingface_hub"),
                    root, progress, cancel, "OLMoE converter dependency setup");
        }
        return python;
    }

    private static void run(
            List<String> command,
            Path directory,
            ManagedRuntimeInstaller.ProgressListener progress,
            ManagedRuntimeInstaller.CancelSignal cancel,
            String label
    ) throws IOException, ManagedRuntimeInstaller.CancelledException {
        LocalModelRuntimeLog.write("colibri_model_prepare", label + ": " + String.join(" ", command));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        StringBuilder tail = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (tail.length() > 8_192) tail.delete(0, tail.length() - 4_096);
                    tail.append(line).append('\n');
                    progress.progress("verifying", label, line, -1L, -1L);
                }
            } catch (IOException ignored) {
            }
        }, "koil-olmoe-converter-log");
        reader.setDaemon(true);
        reader.start();
        try {
            while (!process.waitFor(500L, TimeUnit.MILLISECONDS)) {
                if (cancel.cancelled()) {
                    process.destroyForcibly();
                    throw new ManagedRuntimeInstaller.CancelledException();
                }
            }
        } catch (InterruptedException failure) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ManagedRuntimeInstaller.CancelledException();
        }
        if (process.exitValue() != 0) {
            throw new IOException(label + " failed with exit " + process.exitValue() + ": " + tail.toString().trim());
        }
    }

    private static boolean hasTensorShard(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> path.getFileName().toString().endsWith(".safetensors"));
        }
    }

    private static List<String> hostPython() throws IOException {
        for (List<String> command : windows()
                ? List.of(List.of("py", "-3"), List.of("python"), List.of("python3"))
                : List.of(List.of("python3"), List.of("python"))) {
            try {
                List<String> probe = new ArrayList<>(command);
                probe.add("--version");
                Process process = new ProcessBuilder(probe).redirectErrorStream(true).start();
                if (process.waitFor(10L, TimeUnit.SECONDS) && process.exitValue() == 0) return command;
            } catch (Exception ignored) {
            }
        }
        throw new IOException("Python 3 is required to prepare the OLMoE Colibri container");
    }

    private static boolean succeeds(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            return process.waitFor(15L, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception failure) {
            return false;
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    private static void checkCancelled(ManagedRuntimeInstaller.CancelSignal cancel)
            throws ManagedRuntimeInstaller.CancelledException {
        if (cancel.cancelled() || Thread.currentThread().isInterrupted()) {
            throw new ManagedRuntimeInstaller.CancelledException();
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
