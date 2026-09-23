package com.spirit.koil.api.model.install;

import com.spirit.koil.api.model.LocalModelRuntimeLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds Koil's thin stdio bridge around the pinned upstream Gigatoken crate.
 *
 * The runtime side is deliberately boring: the vendored wrapper sources ship
 * inside the mod JAR under {@code native/gigatoken-bridge/}, the managed
 * installer unpacks the hash-verified upstream crate next to them, and a
 * nightly Cargo toolchain (upstream uses portable SIMD) produces one native
 * executable. No tokenizer code is reimplemented in Koil or vendored by hand.
 */
public final class GigatokenBridgeBuildPlan implements RuntimeSourceBuildPlan {
    private static final String[] WRAPPER_RESOURCES = {
            "native/gigatoken-bridge/Cargo.toml",
            "native/gigatoken-bridge/src/main.rs"
    };
    private static final long BUILD_TIMEOUT_MS = 45L * 60L * 1000L;

    @Override
    public String runtimeId() {
        return ManagedRuntimeCatalog.GIGATOKEN_BRIDGE_RUNTIME_ID;
    }

    @Override
    public List<String> requiredTools() {
        return List.of("cargo nightly toolchain", "python3");
    }

    @Override
    public String missingToolchain() {
        Path cargo = cargoExecutable();
        if (cargo == null) {
            return "cargo not found (install rustup: https://rustup.rs; gigatoken needs the nightly toolchain)";
        }
        if (run(List.of(cargo.toString(), "+nightly", "--version"), null) == null) {
            return "Rust nightly toolchain missing; run: rustup toolchain install nightly "
                    + "(upstream gigatoken uses portable SIMD)";
        }
        if (run(List.of("python3", "--version"), null) == null) {
            return "python3 not found (required by upstream gigatoken's bindings build)";
        }
        return "";
    }

    @Override
    public Path build(
            Path sourceRoot,
            Path installRoot,
            ManagedRuntimeInstaller.ProgressListener progress,
            ManagedRuntimeInstaller.CancelSignal cancel
    ) throws IOException, InterruptedException, ManagedRuntimeInstaller.CancelledException {
        Path cargo = cargoExecutable();
        if (cargo == null) {
            throw new IOException(missingToolchain());
        }

        // The verified upstream crate source (extracted by the installer from the
        // pinned tarball) becomes a path dependency of the wrapper crate.
        Path upstream = findUpstreamCrateDirectory(sourceRoot);
        Path wrapper = Files.createDirectories(installRoot.resolve("build/wrapper"));
        for (String resource : WRAPPER_RESOURCES) {
            String relative = resource.substring("native/gigatoken-bridge/".length());
            writeClasspathResource(resource, wrapper.resolve(relative));
        }
        Path vendor = wrapper.resolve("vendor/gigatoken");
        deleteRecursively(vendor);
        Files.createDirectories(vendor.getParent());
        copyTree(upstream, vendor);
        normalizeUpstreamManifest(vendor.resolve("Cargo.toml"));

        progress.progress("building", "Compiling the pinned Gigatoken tokenizer bridge (nightly cargo)", "", -1L, -1L);
        List<String> command = new ArrayList<>(List.of(
                cargo.toString(), "+nightly", "build", "--release",
                "--manifest-path", wrapper.resolve("Cargo.toml").toString()
        ));
        LocalModelRuntimeLog.write("gigatoken_build", "starting: " + String.join(" ", command));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder tail = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (tail.length() > 8192) {
                        tail.delete(0, tail.length() - 4096);
                    }
                    tail.append(line).append('\n');
                }
            } catch (IOException ignored) {
            }
        }, "koil-gigatoken-build-log");
        reader.setDaemon(true);
        reader.start();
        long startedAt = System.currentTimeMillis();
        while (true) {
            if (cancel.cancelled()) {
                process.destroyForcibly();
                throw new ManagedRuntimeInstaller.CancelledException();
            }
            if (System.currentTimeMillis() - startedAt > BUILD_TIMEOUT_MS) {
                process.destroyForcibly();
                throw new IOException("gigatoken bridge build exceeded the bounded build window");
            }
            if (process.waitFor(500L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (process.exitValue() != 0) {
                    throw new IOException("gigatoken bridge build failed with exit " + process.exitValue()
                            + ": " + tail.toString().trim());
                }
                break;
            }
        }

        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String binaryName = windows ? "gigatoken-bridge.exe" : "gigatoken-bridge";
        Path built = wrapper.resolve("target/release").resolve(binaryName);
        if (!Files.isRegularFile(built)) {
            throw new IOException("gigatoken bridge build finished without producing " + binaryName);
        }
        Path executable = installRoot.resolve(binaryName);
        Files.copy(built, executable, StandardCopyOption.REPLACE_EXISTING);
        makeExecutable(executable);
        deleteRecursively(wrapper.resolve("vendor"));
        deleteRecursively(wrapper.resolve("target"));
        LocalModelRuntimeLog.write("gigatoken_build", "installed " + executable.getFileName()
                + " (" + Files.size(executable) + " bytes)");
        return executable;
    }

    /** Locates the extracted upstream crate root (the directory holding its Cargo.toml). */
    private static Path findUpstreamCrateDirectory(Path sourceRoot) throws IOException {
        if (Files.isRegularFile(sourceRoot.resolve("Cargo.toml"))) {
            return sourceRoot;
        }
        try (var children = Files.list(sourceRoot)) {
            return children
                    .filter(Files::isDirectory)
                    .filter(child -> Files.isRegularFile(child.resolve("Cargo.toml")))
                    .findFirst()
                    .orElseThrow(() -> new IOException("verified gigatoken source archive did not contain a crate"));
        }
    }

    /**
     * Upstream's dev-only `[profile.profiling]` block needs a nightly cargo-features
     * opt-in when the crate is consumed as a path dependency with pinned profiles.
     * The tokenizer code itself is untouched.
     */
    private static void normalizeUpstreamManifest(Path manifest) throws IOException {
        String content = Files.readString(manifest, StandardCharsets.UTF_8);
        // The bridge uses the upstream HF loader only. The crate's direct
        // simdutf dependency is unused by that path and would require C++
        // headers on minimal SteamOS images; keep the optional benchmark
        // dev-dependency untouched.
        content = content.replaceFirst("(?m)^simdutf = \\\"0\\.7\\.0\\\"\\r?\\n", "");
        if (content.contains("profile.profiling") && !content.contains("cargo-features")) {
            Files.writeString(manifest, "cargo-features = [\"profile-rustflags\"]\n\n" + content, StandardCharsets.UTF_8);
        } else {
            Files.writeString(manifest, content, StandardCharsets.UTF_8);
        }
    }

    private static void writeClasspathResource(String resource, Path target) throws IOException {
        ClassLoader loader = GigatokenBridgeBuildPlan.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("mod JAR is missing " + resource);
            }
            Files.createDirectories(target.toAbsolutePath().normalize().getParent());
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        List<Path> paths;
        try (var walk = Files.walk(path)) {
            paths = new ArrayList<>(walk.sorted(java.util.Comparator.reverseOrder()).toList());
        }
        for (Path child : paths) {
            Files.deleteIfExists(child);
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

    private static Path cargoExecutable() {
        Map<String, String> environment = System.getenv();
        for (String candidate : new String[]{environment.get("CARGO"), environment.get("CARGO_HOME")}) {
            if (candidate != null && !candidate.isBlank()) {
                Path resolved = Path.of(candidate).resolve("bin/cargo").normalize();
                if (Files.isRegularFile(resolved)) {
                    return resolved;
                }
                resolved = Path.of(candidate).normalize();
                if (Files.isRegularFile(resolved)) {
                    return resolved;
                }
            }
        }
        Path homeCargo = Path.of(System.getProperty("user.home", ""), ".cargo", "bin", cargoName()).normalize();
        if (Files.isRegularFile(homeCargo)) {
            return homeCargo;
        }
        String path = environment.getOrDefault("PATH", "");
        for (String directory : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory).resolve(cargoName());
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String cargoName() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "cargo.exe"
                : "cargo";
    }

    private static String run(List<String> command, Path directory) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            if (directory != null) {
                builder.directory(directory.toFile());
            }
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 ? output : null;
        } catch (Exception exception) {
            return null;
        }
    }
}
