package com.spirit.koil.api.model.install;

import com.spirit.koil.api.model.LocalModelRuntimeLog;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Private, verified CMake distribution used for Koil's Intel-macOS llama.cpp
 * Metal source build. It deliberately lives under Koil's model runtime root so
 * GPU enablement does not depend on Homebrew, MacPorts, or a global CMake
 * installation.
 *
 * <p>The archive version, download URI, and SHA-256 are pinned together. A
 * partially downloaded or extracted toolchain is never published as usable.</p>
 */
final class ManagedCmakeToolchain {
    static final String VERSION = "4.4.3";
    private static final String ARCHIVE_NAME = "cmake-" + VERSION + "-macos10.10-universal.tar.gz";
    private static final URI DOWNLOAD_URI = URI.create(
            "https://github.com/Kitware/CMake/releases/download/v" + VERSION + "/" + ARCHIVE_NAME);
    private static final String SHA256 = "217a8c7bef7b70e8f9dc3748e625b92b19732b3eb26f6d99f23ef3f2768a8665";
    private static final String INSTALL_DIRECTORY = "cmake-" + VERSION + "-macos10.10-universal";
    private static final String MARKER = ".koil-cmake-sha256";
    private static final long EXPECTED_ARCHIVE_BYTES = 86_700_547L;
    private static final long MAX_ARCHIVE_BYTES = 192L * 1024L * 1024L;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    Path ensureInstalled(
            Path runtimeRoot,
            ManagedRuntimeInstaller.ProgressListener progress,
            ManagedRuntimeInstaller.CancelSignal cancel
    ) throws IOException, InterruptedException, ManagedRuntimeInstaller.CancelledException {
        Path toolchainRoot = runtimeRoot.resolve("_toolchains").toAbsolutePath().normalize();
        Path installRoot = toolchainRoot.resolve(INSTALL_DIRECTORY);
        Path existing = installedExecutable(installRoot);
        if (existing != null) return existing;

        Files.createDirectories(toolchainRoot);
        Path workspace = Files.createTempDirectory(toolchainRoot, "cmake-install-");
        Path archive = workspace.resolve(ARCHIVE_NAME);
        Path extracted = workspace.resolve("extracted");
        try {
            progress.progress("downloading", "Downloading verified Koil CMake " + VERSION + " toolchain",
                    ARCHIVE_NAME, 0L, EXPECTED_ARCHIVE_BYTES);
            downloadVerified(archive, progress, cancel);
            checkCancelled(cancel);

            progress.progress("extracting", "Extracting verified Koil CMake " + VERSION + " toolchain",
                    ARCHIVE_NAME, -1L, -1L);
            Files.createDirectories(extracted);
            extractVerifiedArchive(archive, extracted, cancel);
            checkCancelled(cancel);

            Path payload = singleTopLevelDirectory(extracted);
            Path cmake = cmakeExecutable(payload);
            if (cmake == null) {
                throw new IOException("The verified CMake archive did not contain CMake.app/Contents/bin/cmake");
            }
            makeToolchainExecutables(payload.resolve("CMake.app").resolve("Contents").resolve("bin"));
            makeExecutable(cmake);

            String versionOutput = captureVersion(cmake);
            if (!versionOutput.toLowerCase(Locale.ROOT).contains("cmake version " + VERSION)) {
                throw new IOException("Managed CMake version verification failed: " + bounded(versionOutput));
            }

            Files.writeString(payload.resolve(MARKER), SHA256, StandardCharsets.UTF_8);
            progress.progress("extracting", "Publishing verified Koil CMake " + VERSION + " toolchain",
                    cmake.getFileName().toString(), -1L, -1L);
            publishAtomically(payload, installRoot);
            LocalModelRuntimeLog.write("llama_metal_toolchain",
                    "provisioned CMake " + VERSION + " sha256=" + SHA256 + " | path=" + installRoot);
        } finally {
            deleteRecursivelyQuietly(workspace);
        }

        Path installed = installedExecutable(installRoot);
        if (installed == null) {
            throw new IOException("Managed CMake verification failed after publishing.");
        }
        return installed;
    }

    private static Path installedExecutable(Path installRoot) {
        Path marker = installRoot.resolve(MARKER);
        Path executable = installRoot.resolve("CMake.app").resolve("Contents").resolve("bin").resolve("cmake");
        try {
            if (!Files.isRegularFile(executable) || !Files.isRegularFile(marker)) return null;
            if (!SHA256.equalsIgnoreCase(Files.readString(marker, StandardCharsets.UTF_8).trim())) return null;
            makeExecutable(executable);
            return executable;
        } catch (IOException failure) {
            return null;
        }
    }

    private static void downloadVerified(
            Path destination,
            ManagedRuntimeInstaller.ProgressListener progress,
            ManagedRuntimeInstaller.CancelSignal cancel
    ) throws IOException, InterruptedException, ManagedRuntimeInstaller.CancelledException {
        Files.deleteIfExists(destination);
        HttpRequest request = HttpRequest.newBuilder(DOWNLOAD_URI)
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream ignored = response.body()) {
                // Closing the body is enough; response diagnostics are deliberately bounded.
            }
            throw new IOException("CMake toolchain download failed with HTTP " + response.statusCode());
        }

        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (declared > 0L && declared != EXPECTED_ARCHIVE_BYTES) {
            try (InputStream ignored = response.body()) {
            }
            throw new IOException("CMake toolchain download size metadata changed: expected "
                    + EXPECTED_ARCHIVE_BYTES + " bytes but server reported " + declared);
        }
        if (declared > MAX_ARCHIVE_BYTES) {
            try (InputStream ignored = response.body()) {
            }
            throw new IOException("CMake toolchain download exceeded the expected size bound.");
        }

        MessageDigest digest = sha256Digest();
        long written = 0L;
        try (InputStream input = new BufferedInputStream(response.body());
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(destination))) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                checkCancelled(cancel);
                if (read == 0) continue;
                written += read;
                if (written > MAX_ARCHIVE_BYTES) {
                    throw new IOException("CMake toolchain download exceeded the expected size bound.");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
                progress.progress("downloading", "Downloading verified Koil CMake " + VERSION + " toolchain",
                        ARCHIVE_NAME, written, EXPECTED_ARCHIVE_BYTES);
            }
        } catch (IOException | ManagedRuntimeInstaller.CancelledException failure) {
            Files.deleteIfExists(destination);
            throw failure;
        }

        if (written != EXPECTED_ARCHIVE_BYTES) {
            Files.deleteIfExists(destination);
            throw new IOException("CMake toolchain download size verification failed: expected "
                    + EXPECTED_ARCHIVE_BYTES + " bytes but downloaded " + written);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!SHA256.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(destination);
            throw new IOException("CMake toolchain SHA-256 verification failed: expected " + SHA256
                    + " but downloaded " + actual);
        }
    }


    private static void extractVerifiedArchive(
            Path archive,
            Path output,
            ManagedRuntimeInstaller.CancelSignal cancel
    ) throws IOException, InterruptedException, ManagedRuntimeInstaller.CancelledException {
        checkCancelled(cancel);
        Path tar = Path.of("/usr/bin/tar");
        if (!Files.isExecutable(tar)) {
            throw new IOException("macOS system tar is unavailable at /usr/bin/tar");
        }
        Process process = new ProcessBuilder(
                tar.toString(), "-xzf", archive.toString(), "-C", output.toString()
        ).redirectErrorStream(true).start();
        StringBuilder diagnostics = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0 && diagnostics.length() < 16 * 1024) {
                        int remaining = 16 * 1024 - diagnostics.length();
                        diagnostics.append(new String(buffer, 0, Math.min(read, remaining), StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) {
            }
        }, "koil-cmake-extract-log");
        reader.setDaemon(true);
        reader.start();

        long start = System.currentTimeMillis();
        while (true) {
            if (cancel != null && cancel.cancelled()) {
                process.destroyForcibly();
                throw new ManagedRuntimeInstaller.CancelledException();
            }
            if (System.currentTimeMillis() - start > Duration.ofMinutes(5).toMillis()) {
                process.destroyForcibly();
                throw new IOException("Managed CMake extraction timed out.");
            }
            if (process.waitFor(200L, TimeUnit.MILLISECONDS)) break;
        }
        reader.join(1000L);
        if (process.exitValue() != 0) {
            throw new IOException("Managed CMake extraction failed with exit " + process.exitValue()
                    + (diagnostics.isEmpty() ? "" : ": " + bounded(diagnostics.toString())));
        }
    }

    private static Path singleTopLevelDirectory(Path extracted) throws IOException {
        try (var children = Files.list(extracted)) {
            var entries = children.toList();
            if (entries.size() != 1 || !Files.isDirectory(entries.get(0))) {
                throw new IOException("The verified CMake archive did not contain one canonical top-level directory.");
            }
            return entries.get(0);
        }
    }

    private static Path cmakeExecutable(Path payload) {
        Path expected = payload.resolve("CMake.app").resolve("Contents").resolve("bin").resolve("cmake");
        return Files.isRegularFile(expected) ? expected : null;
    }

    private static String captureVersion(Path cmake) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(cmake.toString(), "--version").redirectErrorStream(true).start();
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(input.readNBytes(16 * 1024), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(20L, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Managed CMake version probe timed out.");
        }
        if (process.exitValue() != 0) {
            throw new IOException("Managed CMake version probe failed with exit " + process.exitValue()
                    + (output.isBlank() ? "" : ": " + bounded(output)));
        }
        return output;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void checkCancelled(ManagedRuntimeInstaller.CancelSignal cancel)
            throws ManagedRuntimeInstaller.CancelledException {
        if (cancel != null && cancel.cancelled()) {
            throw new ManagedRuntimeInstaller.CancelledException();
        }
    }

    private static void makeToolchainExecutables(Path bin) throws IOException {
        if (!Files.isDirectory(bin)) return;
        try (var paths = Files.list(bin)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                makeExecutable(path);
            }
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

    private static void publishAtomically(Path staged, Path installRoot) throws IOException {
        Path backup = installRoot.getParent().resolve(installRoot.getFileName() + ".previous");
        deleteRecursivelyQuietly(backup);
        boolean movedAside = false;
        try {
            if (Files.exists(installRoot)) {
                Files.move(installRoot, backup, StandardCopyOption.REPLACE_EXISTING);
                movedAside = true;
            }
            Files.createDirectories(installRoot.getParent());
            Files.move(staged, installRoot, StandardCopyOption.REPLACE_EXISTING);
            deleteRecursivelyQuietly(backup);
        } catch (IOException failure) {
            if (movedAside && Files.exists(backup) && !Files.exists(installRoot)) {
                Files.move(backup, installRoot, StandardCopyOption.REPLACE_EXISTING);
            }
            throw failure;
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

    private static String bounded(String value) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').strip();
        return text.length() <= 1200 ? text : text.substring(0, 1200) + "...";
    }
}
