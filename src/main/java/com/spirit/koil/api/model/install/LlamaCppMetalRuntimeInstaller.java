package com.spirit.koil.api.model.install;

import com.spirit.koil.api.model.provider.llamacpp.LlamaCppDeviceProbe;
import com.spirit.koil.api.model.LocalModelRuntimeLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provisions the Intel-macOS llama.cpp runtime Koil needs for Metal offload.
 *
 * <p>Upstream's official macOS x64 release archive is intentionally built with
 * {@code GGML_METAL=OFF}. Rather than replacing Koil's verified CPU runtime,
 * this installer creates a second runtime from the exact upstream b10173 commit
 * when the user explicitly chooses GPU or hybrid compute. The checked-out git
 * commit is verified before any build output is published.</p>
 */
public final class LlamaCppMetalRuntimeInstaller {
    public static final String VERSION = "b10173";
    public static final String UPSTREAM_COMMIT = "e9fa0781f1c25fc4fe8c86be1edc6970661ad6f0";
    private static final String REPOSITORY = "https://github.com/ggml-org/llama.cpp.git";
    private static final String DIRECTORY = "llama.cpp-metal-" + VERSION + "-macos-x64";
    private static final String MARKER = ".koil-llamacpp-metal-commit";
    private static final String INTEL_AMD_METAL_PATCH_ID = "intel-amd-metal-buffer-alignment-v1";
    private static final String METAL_DEVICE_SOURCE = "ggml/src/ggml-metal/ggml-metal-device.m";
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(45);
    private static final Duration METAL_DEVICE_PROBE_TIMEOUT = Duration.ofMinutes(2);
    private static final int LOG_TAIL_LIMIT = 16 * 1024;
    private static final Pattern CMAKE_PROGRESS = Pattern.compile("\\[\\s*(\\d{1,3})%\\]");
    private final ManagedCmakeToolchain cmakeToolchain = new ManagedCmakeToolchain();

    public boolean requiredForCurrentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = normalizeArchitecture(System.getProperty("os.arch", ""));
        return os.contains("mac") && "x64".equals(arch);
    }

    public ManagedRuntimeInstallation installed(Path runtimeRoot) {
        if (!requiredForCurrentPlatform()) return null;
        Path installRoot = runtimeRoot.resolve(DIRECTORY).toAbsolutePath().normalize();
        Path executable = installRoot.resolve("llama-server");
        Path marker = installRoot.resolve(MARKER);
        try {
            if (!Files.isRegularFile(executable) || !Files.isRegularFile(marker)) return null;
            List<String> identity = Files.readAllLines(marker, StandardCharsets.UTF_8);
            if (identity.size() < 2) return null;
            if (!UPSTREAM_COMMIT.equalsIgnoreCase(identity.get(0).trim())) return null;
            if (!INTEL_AMD_METAL_PATCH_ID.equals(identity.get(1).trim())) return null;
        } catch (IOException failure) {
            return null;
        }
        ManagedRuntimeArtifact identity = identityArtifact();
        return new ManagedRuntimeInstallation(
                ManagedRuntimeCatalog.LLAMA_CPP_RUNTIME_ID,
                "macos-x86_64-metal-local",
                installRoot,
                executable,
                identity,
                true,
                true
        );
    }

    public ManagedRuntimeInstallation ensureInstalled(
            Path runtimeRoot,
            ManagedRuntimeInstaller.ProgressListener progress,
            ManagedRuntimeInstaller.CancelSignal cancel
    ) throws IOException, InterruptedException, ManagedRuntimeInstaller.CancelledException {
        if (!requiredForCurrentPlatform()) {
            throw new IOException("The local Metal llama.cpp runtime is only needed on Intel macOS.");
        }
        ManagedRuntimeInstallation existing = installed(runtimeRoot);
        if (existing != null) return existing;

        String missing = missingToolchain();
        if (!missing.isBlank()) {
            throw new IOException(
                    "Intel macOS GPU/hybrid inference requires Apple's native compiler toolchain plus Git; " + missing
                            + ". Koil provisions CMake itself, so no global CMake installation is required."
            );
        }

        Files.createDirectories(runtimeRoot);
        Path cmake = this.cmakeToolchain.ensureInstalled(runtimeRoot, progress, cancel);
        Path installRoot = runtimeRoot.resolve(DIRECTORY).toAbsolutePath().normalize();
        Path workspace = Files.createTempDirectory(runtimeRoot, "llama-metal-build-");
        Path source = workspace.resolve("source");
        Path build = workspace.resolve("build");
        Path publish = workspace.resolve("publish");
        try {
            progress.progress("building", "Fetching pinned llama.cpp " + VERSION + " source for Intel macOS Metal", "source", 5L, 100L);
            Files.createDirectories(source);
            run(List.of("git", "init", source.toString()), workspace, cancel, Duration.ofMinutes(2), "git init");
            run(List.of("git", "-C", source.toString(), "remote", "add", "origin", REPOSITORY), workspace,
                    cancel, Duration.ofMinutes(1), "git remote add");
            run(List.of("git", "-C", source.toString(), "fetch", "--depth=1", "origin",
                            "refs/tags/" + VERSION + ":refs/tags/" + VERSION), workspace,
                    cancel, Duration.ofMinutes(10), "git fetch pinned llama.cpp tag");
            run(List.of("git", "-C", source.toString(), "checkout", "--detach", VERSION), workspace,
                    cancel, Duration.ofMinutes(2), "git checkout pinned llama.cpp tag");

            String actualCommit = capture(List.of("git", "-C", source.toString(), "rev-parse", "HEAD"), workspace,
                    cancel, Duration.ofMinutes(1), "verify llama.cpp commit").strip();
            if (!UPSTREAM_COMMIT.equalsIgnoreCase(actualCommit)) {
                throw new IOException("Pinned llama.cpp source verification failed: expected " + UPSTREAM_COMMIT
                        + " but git produced " + actualCommit);
            }
            run(List.of("git", "-C", source.toString(), "diff", "--quiet", "HEAD", "--"), workspace,
                    cancel, Duration.ofMinutes(1), "verify clean llama.cpp source");

            progress.progress("building", "Applying Intel AMD Metal buffer-alignment compatibility patch", "metal", 32L, 100L);
            applyIntelAmdMetalBufferAlignmentPatch(source);
            run(List.of("git", "-C", source.toString(), "diff", "--check"), workspace,
                    cancel, Duration.ofMinutes(1), "verify Koil Metal compatibility patch");
            LocalModelRuntimeLog.write("llama_metal_patch",
                    "applied " + INTEL_AMD_METAL_PATCH_ID + " to " + VERSION + " commit=" + UPSTREAM_COMMIT);

            progress.progress("building", "Configuring Metal-enabled llama.cpp " + VERSION, "cmake", 35L, 100L);
            List<String> configure = new ArrayList<>();
            configure.add(cmake.toString());
            configure.add("-S");
            configure.add(source.toString());
            configure.add("-B");
            configure.add(build.toString());
            configure.add("-DCMAKE_BUILD_TYPE=Release");
            configure.add("-DCMAKE_INSTALL_RPATH=@loader_path");
            configure.add("-DCMAKE_BUILD_WITH_INSTALL_RPATH=ON");
            configure.add("-DGGML_METAL=ON");
            configure.add("-DGGML_METAL_EMBED_LIBRARY=ON");
            configure.add("-DLLAMA_BUILD_EXAMPLES=OFF");
            configure.add("-DLLAMA_BUILD_TESTS=OFF");
            configure.add("-DLLAMA_BUILD_TOOLS=ON");
            configure.add("-DLLAMA_BUILD_SERVER=ON");
            run(configure, workspace, cancel, Duration.ofMinutes(10), "configure Metal-enabled llama.cpp");

            int jobs = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
            progress.progress("building", "Compiling Metal-enabled llama.cpp (" + jobs + " jobs)", "llama-server", 45L, 100L);
            String compileDetail = "Compiling Metal-enabled llama.cpp (" + jobs + " jobs)";
            run(List.of(
                    cmake.toString(), "--build", build.toString(), "--config", "Release",
                    "--target", "llama-server", "-j", Integer.toString(jobs)
            ), workspace, cancel, BUILD_TIMEOUT, "build Metal-enabled llama.cpp", line -> {
                Matcher matcher = CMAKE_PROGRESS.matcher(line);
                if (!matcher.find()) return;
                try {
                    int cmakePercent = Math.max(0, Math.min(100, Integer.parseInt(matcher.group(1))));
                    long phasePercent = 45L + Math.round(cmakePercent * 0.45D);
                    progress.progress("building", compileDetail, "llama-server", phasePercent, 100L);
                } catch (NumberFormatException ignored) {
                }
            });

            Path bin = build.resolve("bin");
            Path builtServer = bin.resolve("llama-server");
            if (!Files.isRegularFile(builtServer)) {
                throw new IOException("The Metal-enabled llama.cpp build did not produce build/bin/llama-server");
            }

            // The source checkout above is the authoritative identity proof. Running llama-server --version
            // is intentionally avoided here because a Metal-enabled llama.cpp process can initialize GPU
            // backends before completing even metadata-only commands on macOS. The build occurs in a fresh
            // workspace after the exact 40-character commit has been verified, and the published marker
            // records that same verified commit.
            progress.progress("building", "Verifying Metal device registration", "llama-server", 94L, 100L);
            String deviceOutput = captureUntilEvidence(
                    List.of(builtServer.toString(), "--list-devices"),
                    bin,
                    cancel,
                    METAL_DEVICE_PROBE_TIMEOUT,
                    "verify Metal device registration",
                    LlamaCppMetalRuntimeInstaller::isMetalDeviceEvidence
            );
            if (!containsMetalDeviceEvidence(deviceOutput)) {
                throw new IOException("The local " + VERSION + " build completed, but Metal did not register a usable accelerator device. "
                        + "llama.cpp reported: " + bounded(deviceOutput));
            }

            Files.createDirectories(publish);
            copyTree(bin, publish);
            makeExecutable(publish.resolve("llama-server"));
            Files.writeString(
                    publish.resolve(MARKER),
                    UPSTREAM_COMMIT + "\n" + INTEL_AMD_METAL_PATCH_ID + "\n",
                    StandardCharsets.UTF_8
            );

            progress.progress("extracting", "Publishing verified Metal-enabled llama.cpp runtime", "llama-server", 98L, 100L);
            publishAtomically(publish, installRoot);
            LocalModelRuntimeLog.write("llama_metal_runtime",
                    "built " + VERSION + " commit=" + UPSTREAM_COMMIT + " | path=" + installRoot);
        } finally {
            deleteRecursivelyQuietly(workspace);
        }

        ManagedRuntimeInstallation installed = installed(runtimeRoot);
        if (installed == null) {
            throw new IOException("Metal-enabled llama.cpp runtime verification failed after publishing.");
        }
        return installed;
    }

    public String missingToolchain() {
        List<String> missing = new ArrayList<>();
        if (!commandWorks(List.of("git", "--version"))) missing.add("git");
        if (!commandWorks(List.of("xcrun", "--find", "clang"))) missing.add("Xcode Command Line Tools/clang");
        return missing.isEmpty() ? "" : "missing host tools: " + String.join(", ", missing);
    }

    private static boolean containsMetalDeviceEvidence(String output) {
        if (output == null || output.isBlank()) return false;
        for (String line : output.split("\\R")) {
            if (isMetalDeviceEvidence(line)) return true;
        }
        return false;
    }

    private static boolean isMetalDeviceEvidence(String line) {
        if (line == null) return false;
        String trimmed = line.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) return false;

        // --list-devices emits the native device identifier followed by a colon.
        // Intel-macOS Metal builds use ids such as "MTL0", while other builds can
        // use "Metal" / "Metal0". Delegate to the shared classifier so installer
        // verification and runtime device discovery cannot disagree.
        int colon = trimmed.indexOf(':');
        if (colon <= 0) return false;
        String id = trimmed.substring(0, colon).trim();
        return LlamaCppDeviceProbe.isMetalDeviceId(id);
    }

    private static ManagedRuntimeArtifact identityArtifact() {
        return ManagedRuntimeCatalog.current(ManagedRuntimeCatalog.LLAMA_CPP_RUNTIME_ID)
                .orElseThrow(() -> new IllegalStateException("No canonical llama.cpp runtime metadata exists for this platform."));
    }

    private static void run(
            List<String> command,
            Path directory,
            ManagedRuntimeInstaller.CancelSignal cancel,
            Duration timeout,
            String stage
    ) throws IOException, InterruptedException, ManagedRuntimeInstaller.CancelledException {
        capture(command, directory, cancel, timeout, stage, null);
    }

    private static void run(
            List<String> command,
            Path directory,
            ManagedRuntimeInstaller.CancelSignal cancel,
            Duration timeout,
            String stage,
            Consumer<String> lineObserver
    ) throws IOException, InterruptedException, ManagedRuntimeInstaller.CancelledException {
        capture(command, directory, cancel, timeout, stage, lineObserver);
    }

    private static String capture(
            List<String> command,
            Path directory,
            ManagedRuntimeInstaller.CancelSignal cancel,
            Duration timeout,
            String stage
    ) throws IOException, InterruptedException, ManagedRuntimeInstaller.CancelledException {
        return capture(command, directory, cancel, timeout, stage, null);
    }

    private static String capture(
            List<String> command,
            Path directory,
            ManagedRuntimeInstaller.CancelSignal cancel,
            Duration timeout,
            String stage,
            Consumer<String> lineObserver
    ) throws IOException, InterruptedException, ManagedRuntimeInstaller.CancelledException {
        return captureProcess(command, directory, cancel, timeout, stage, lineObserver, null);
    }

    private static String captureUntilEvidence(
            List<String> command,
            Path directory,
            ManagedRuntimeInstaller.CancelSignal cancel,
            Duration timeout,
            String stage,
            Predicate<String> successEvidence
    ) throws IOException, InterruptedException, ManagedRuntimeInstaller.CancelledException {
        return captureProcess(command, directory, cancel, timeout, stage, null, successEvidence);
    }

    private static String captureProcess(
            List<String> command,
            Path directory,
            ManagedRuntimeInstaller.CancelSignal cancel,
            Duration timeout,
            String stage,
            Consumer<String> lineObserver,
            Predicate<String> successEvidence
    ) throws IOException, InterruptedException, ManagedRuntimeInstaller.CancelledException {
        LocalModelRuntimeLog.write("llama_metal_build", stage + ": " + String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (directory != null) builder.directory(directory.toFile());
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        AtomicBoolean evidenceSeen = new AtomicBoolean(false);
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (output) {
                        if (output.length() > LOG_TAIL_LIMIT) {
                            output.delete(0, output.length() - (LOG_TAIL_LIMIT / 2));
                        }
                        output.append(line).append('\n');
                    }
                    if (lineObserver != null) {
                        try {
                            lineObserver.accept(line);
                        } catch (RuntimeException ignored) {
                        }
                    }
                    if (successEvidence != null) {
                        try {
                            if (successEvidence.test(line)) evidenceSeen.set(true);
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }, "koil-llama-metal-build-log");
        reader.setDaemon(true);
        reader.start();

        long timeoutMillis = Math.max(1000L, timeout == null ? BUILD_TIMEOUT.toMillis() : timeout.toMillis());
        long start = System.currentTimeMillis();
        boolean acceptedEarly = false;
        while (true) {
            if (cancel != null && cancel.cancelled()) {
                process.destroyForcibly();
                throw new ManagedRuntimeInstaller.CancelledException();
            }
            if (evidenceSeen.get()) {
                acceptedEarly = true;
                process.destroy();
                if (!process.waitFor(2L, TimeUnit.SECONDS)) process.destroyForcibly();
                break;
            }
            if (System.currentTimeMillis() - start > timeoutMillis) {
                process.destroyForcibly();
                String partial;
                synchronized (output) {
                    partial = output.toString().trim();
                }
                throw new IOException(stage + " exceeded its bounded time window"
                        + (partial.isBlank() ? "" : ": " + bounded(partial)));
            }
            if (process.waitFor(250L, TimeUnit.MILLISECONDS)) break;
        }
        reader.join(1000L);
        String text;
        synchronized (output) {
            text = output.toString().trim();
        }
        if (!acceptedEarly && process.exitValue() != 0) {
            throw new IOException(stage + " failed with exit " + process.exitValue()
                    + (text.isBlank() ? "" : ": " + text));
        }
        return text;
    }

    private static boolean commandWorks(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return process.waitFor(8L, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception failure) {
            return false;
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                Path destination = target.resolve(relative.toString());
                Files.createDirectories(destination.getParent());
                if (Files.isSymbolicLink(file)) {
                    Files.deleteIfExists(destination);
                    Files.createSymbolicLink(destination, Files.readSymbolicLink(file));
                } else {
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void publishAtomically(Path staged, Path installRoot) throws IOException {
        Path backup = installRoot.getParent().resolve(installRoot.getFileName() + ".previous");
        deleteRecursivelyQuietly(backup);
        boolean movedAside = false;
        try {
            if (Files.exists(installRoot)) {
                Files.move(installRoot, backup, StandardCopyOption.REPLACE_EXISTING);
                movedAside = true;
            }
            Files.move(staged, installRoot, StandardCopyOption.REPLACE_EXISTING);
            deleteRecursivelyQuietly(backup);
        } catch (IOException failure) {
            if (movedAside && Files.exists(backup) && !Files.exists(installRoot)) {
                Files.move(backup, installRoot, StandardCopyOption.REPLACE_EXISTING);
            }
            throw failure;
        }
    }

    private static void makeExecutable(Path path) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.copyOf(Files.getPosixFilePermissions(path));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            path.toFile().setExecutable(true, true);
        }
    }

    private static void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static void applyIntelAmdMetalBufferAlignmentPatch(Path sourceRoot) throws IOException {
        Path metalSource = sourceRoot.resolve(METAL_DEVICE_SOURCE).toAbsolutePath().normalize();
        if (!Files.isRegularFile(metalSource)) {
            throw new IOException("Pinned llama.cpp source is missing " + METAL_DEVICE_SOURCE);
        }

        String source = Files.readString(metalSource, StandardCharsets.UTF_8);
        String setBefore = String.join("\n",
                "        // src",
                "        void * data_ptr = (void *)(uintptr_t) data; // \"const cast\" the src data",
                "        id<MTLBuffer> buf_src = [buf->dev->mtl_device newBufferWithBytesNoCopy:data_ptr",
                "                                                               length:size",
                "                                                              options:MTLResourceStorageModeShared",
                "                                                          deallocator:nil];",
                "",
                "        GGML_ASSERT(buf_src);",
                "");
        String setAfter = String.join("\n",
                "        // src",
                "        // Intel Macs with discrete AMD GPUs require page-aligned pointers for",
                "        // newBufferWithBytesNoCopy. GGUF tensor data is not guaranteed to be",
                "        // page-aligned, so allocate a Metal-owned shared buffer and copy into it.",
                "        id<MTLBuffer> buf_src = [buf->dev->mtl_device newBufferWithLength:size",
                "                                                              options:MTLResourceStorageModeShared];",
                "",
                "        GGML_ASSERT(buf_src);",
                "        memcpy([buf_src contents], data, size);",
                "");

        String getBefore = String.join("\n",
                "        // dst",
                "        id<MTLBuffer> buf_dst = [buf->dev->mtl_device newBufferWithBytesNoCopy:data",
                "                                                               length:size",
                "                                                              options:MTLResourceStorageModeShared",
                "                                                          deallocator:nil];",
                "",
                "        GGML_ASSERT(buf_dst);",
                "");
        String getAfter = String.join("\n",
                "        // dst",
                "        // See the matching set_tensor path above. Use Metal-owned storage so",
                "        // arbitrary destination pointers do not have to satisfy Intel page alignment.",
                "        id<MTLBuffer> buf_dst = [buf->dev->mtl_device newBufferWithLength:size",
                "                                                              options:MTLResourceStorageModeShared];",
                "",
                "        GGML_ASSERT(buf_dst);",
                "");

        String waitBefore = String.join("\n",
                "        [cmd_buf commit];",
                "        [cmd_buf waitUntilCompleted];",
                "    }",
                "}",
                "",
                "bool ggml_metal_buffer_cpy_tensor(");
        String waitAfter = String.join("\n",
                "        [cmd_buf commit];",
                "        [cmd_buf waitUntilCompleted];",
                "        memcpy(data, [buf_dst contents], size);",
                "    }",
                "}",
                "",
                "bool ggml_metal_buffer_cpy_tensor(");

        source = replaceExactlyOnce(source, setBefore, setAfter, "Metal set_tensor alignment path");
        source = replaceExactlyOnce(source, getBefore, getAfter, "Metal get_tensor alignment path");
        source = replaceExactlyOnce(source, waitBefore, waitAfter, "Metal get_tensor copy-back ordering");
        Files.writeString(metalSource, source, StandardCharsets.UTF_8);
    }

    private static String replaceExactlyOnce(String source, String before, String after, String description) throws IOException {
        int first = source.indexOf(before);
        if (first < 0) {
            throw new IOException("Pinned llama.cpp source no longer matches expected " + description + "; refusing an unverified patch.");
        }
        if (source.indexOf(before, first + before.length()) >= 0) {
            throw new IOException("Pinned llama.cpp source contains multiple matches for " + description + "; refusing an ambiguous patch.");
        }
        return source.substring(0, first) + after + source.substring(first + before.length());
    }

    private static String bounded(String value) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').strip();
        return text.length() <= 1200 ? text : text.substring(0, 1200) + "...";
    }

    private static String normalizeArchitecture(String value) {
        String arch = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (arch.equals("x86_64") || arch.equals("amd64") || arch.equals("x64")) return "x64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "arm64";
        return arch;
    }
}
