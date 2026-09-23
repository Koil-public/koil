package com.spirit.koil.api.model.install;

import com.spirit.koil.api.model.LocalModelRuntimeLog;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Installs verified managed runtimes (llama.cpp, Colibri, the Gigatoken
 * bridge) under {@code koil/sys/model/runtime/<runtimeId>}.
 *
 * Guarantees:
 * - every downloaded byte is size- and SHA-256-verified before execution;
 * - extraction cannot escape the staging directory;
 * - a failed install never destroys a working existing runtime;
 * - source builds run the pinned build plan and only publish on success.
 */
public final class ManagedRuntimeInstaller {
    private static final String MARKER = ".koil-runtime-sha256";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(20L, TimeUnit.SECONDS)
            .readTimeout(0L, TimeUnit.MILLISECONDS)
            .build();
    private static final Map<String, RuntimeSourceBuildPlan> BUILD_PLANS = Map.of(
            ManagedRuntimeCatalog.COLIBRI_RUNTIME_ID, new ColibriSourceBuildPlan(),
            ManagedRuntimeCatalog.GIGATOKEN_BRIDGE_RUNTIME_ID, new GigatokenBridgeBuildPlan()
    );

    private volatile Call activeCall;

    public interface ProgressListener {
        /** completed/total of -1 means "indeterminate". */
        void progress(String stage, String detail, String currentFile, long completedBytes, long totalBytes);
    }

    public interface CancelSignal {
        CancelSignal NEVER = () -> false;

        boolean cancelled();
    }

    public static final class CancelledException extends Exception {
    }

    /** An install attempt interrupted between downloads; the old install stays intact. */
    private record PendingInstall(Path stagingDirectory, Path backupDirectory) {
    }

    public ManagedRuntimeInstallation ensureInstalled(
            String runtimeId,
            Path runtimeRoot,
            ProgressListener progress,
            CancelSignal cancel
    ) throws IOException, CancelledException {
        ManagedRuntimeArtifact artifact = ManagedRuntimeCatalog.current(runtimeId)
                .orElseThrow(() -> new IOException("No verified runtime artifact exists for "
                        + runtimeId + " on this platform."));
        Path installRoot = runtimeRoot.resolve(runtimeId).toAbsolutePath().normalize();
        Path marker = installRoot.resolve(MARKER);
        ManagedRuntimeInstallation existing = probe(installRoot, runtimeId, artifact);
        if (existing != null) {
            if (ManagedRuntimeCatalog.COLIBRI_RUNTIME_ID.equals(runtimeId)) {
                // Runtime markers predate the tokenizer seam. Re-applying the
                // idempotent pinned integration upgrades an existing install
                // without redownloading or replacing user model data.
                ColibriRuntimeIntegrator.integrate(existing.installRoot());
            }
            return existing;
        }
        if (artifact.sourceBuild()) {
            return sourceBuild(artifact, runtimeRoot, installRoot, marker, progress, cancel);
        }

        progress.progress("downloading", "Downloading verified runtime " + runtimeId, artifact.fileName(), 0L, artifact.sizeBytes());
        Path archive = runtimeRoot.resolve(artifact.fileName() + ".part");
        Files.createDirectories(runtimeRoot);
        download(artifact, archive, progress, cancel);
        checkCancelled(cancel);
        progress.progress("extracting", "Extracting " + runtimeId, artifact.fileName(), artifact.sizeBytes(), artifact.sizeBytes());
        Path staging = Files.createTempDirectory(runtimeRoot, "runtime-staging-");
        try {
            SafeArchiveExtractor.extract(archive, staging, artifact.archiveType());
            if (ManagedRuntimeCatalog.COLIBRI_RUNTIME_ID.equals(runtimeId)) {
                ColibriRuntimeIntegrator.integrate(staging);
                makeColibriEnginesExecutable(staging);
            }
            Path executable = findExecutable(staging, artifact.executableName());
            if (executable == null) {
                throw new IOException("The verified archive did not contain " + artifact.executableName());
            }
            makeExecutable(executable);
            publish(staging, installRoot);
            Files.writeString(marker, artifact.sha256(), StandardCharsets.UTF_8);
            Files.deleteIfExists(archive);
        } finally {
            deleteRecursivelyQuietly(staging);
        }
        ManagedRuntimeInstallation installed = probe(installRoot, runtimeId, artifact);
        if (installed == null) {
            throw new IOException("Runtime installation verification failed after publishing.");
        }
        return new ManagedRuntimeInstallation(
                runtimeId, artifact.platformId(), installRoot, installed.executable(), artifact, false, false
        );
    }

    private ManagedRuntimeInstallation sourceBuild(
            ManagedRuntimeArtifact artifact,
            Path runtimeRoot,
            Path installRoot,
            Path marker,
            ProgressListener progress,
            CancelSignal cancel
    ) throws IOException, CancelledException {
        RuntimeSourceBuildPlan plan = BUILD_PLANS.get(artifact.runtimeId());
        if (plan == null) {
            throw new IOException("No Koil source-build plan is registered for " + artifact.runtimeId());
        }
        String missing = plan.missingToolchain();
        if (!missing.isBlank()) {
            throw new IOException("Cannot build " + artifact.runtimeId() + " from source: " + missing);
        }
        progress.progress("downloading", "Downloading verified source for " + artifact.runtimeId(), artifact.fileName(), 0L, artifact.sizeBytes());
        Path archive = runtimeRoot.resolve(artifact.fileName() + ".part");
        Files.createDirectories(runtimeRoot);
        download(artifact, archive, progress, cancel);
        checkCancelled(cancel);

        Path staging = Files.createTempDirectory(runtimeRoot, "runtime-source-");
        try {
            progress.progress("extracting", "Extracting source archive", artifact.fileName(), artifact.sizeBytes(), artifact.sizeBytes());
            SafeArchiveExtractor.extract(archive, staging, artifact.archiveType());
            // The pinned GitHub source tarball contains one top-level directory.
            Path sourceRoot = staging;
            try (var children = Files.list(staging)) {
                List<Path> directories = children.filter(Files::isDirectory).toList();
                if (directories.size() == 1) {
                    sourceRoot = directories.get(0);
                }
            }
            Path buildRoot = Files.createTempDirectory(runtimeRoot, "runtime-build-");
            try {
                plan.build(sourceRoot, buildRoot, progress, cancel);
                if (ManagedRuntimeCatalog.COLIBRI_RUNTIME_ID.equals(artifact.runtimeId())) {
                    ColibriRuntimeIntegrator.integrate(buildRoot);
                    makeColibriEnginesExecutable(buildRoot);
                }
                checkCancelled(cancel);
                publish(buildRoot, installRoot);
                Files.writeString(marker, artifact.sha256(), StandardCharsets.UTF_8);
                Files.deleteIfExists(archive);
                LocalModelRuntimeLog.write("managed_runtime", "built " + artifact.runtimeId() + " from verified source");
                ManagedRuntimeInstallation installed = probe(installRoot, artifact.runtimeId(), artifact);
                if (installed == null) {
                    throw new IOException("Source build did not produce the expected runtime executable.");
                }
                return new ManagedRuntimeInstallation(
                        artifact.runtimeId(), artifact.platformId(), installRoot,
                        installed.executable(), artifact, true, false
                );
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                deleteRecursivelyQuietly(buildRoot);
            }
        } finally {
            deleteRecursivelyQuietly(staging);
        }
    }

    /** Returns the existing install if it matches the expected artifact digest, else null. */
    public ManagedRuntimeInstallation probe(Path installRoot, String runtimeId, ManagedRuntimeArtifact artifact) {
        if (!Files.isDirectory(installRoot)) {
            return null;
        }
        Path marker = installRoot.resolve(MARKER);
        try {
            String recorded = Files.readString(marker, StandardCharsets.UTF_8).trim();
            if (!artifact.sha256().equalsIgnoreCase(recorded)) {
                return null;
            }
        } catch (IOException exception) {
            return null;
        }
        Path executable = findExecutable(installRoot, artifact.executableName());
        return executable == null
                ? null
                : new ManagedRuntimeInstallation(runtimeId, artifact.platformId(), installRoot, executable, artifact, artifact.sourceBuild(), true);
    }

    /** Standalone existence check for callers that must not trigger a download. */
    public ManagedRuntimeInstallation installed(String runtimeId, Path runtimeRoot) {
        ManagedRuntimeArtifact artifact = ManagedRuntimeCatalog.current(runtimeId).orElse(null);
        if (artifact == null) {
            return null;
        }
        ManagedRuntimeInstallation installation = probe(
                runtimeRoot.resolve(runtimeId).toAbsolutePath().normalize(), runtimeId, artifact);
        if (installation != null && ManagedRuntimeCatalog.COLIBRI_RUNTIME_ID.equals(runtimeId)) {
            try {
                ColibriRuntimeIntegrator.integrate(installation.installRoot());
                makeColibriEnginesExecutable(installation.installRoot());
            } catch (IOException failure) {
                LocalModelRuntimeLog.write("colibri_runtime_integration_failed", failure.getMessage());
                return null;
            }
        }
        return installation;
    }

    private void download(
            ManagedRuntimeArtifact artifact,
            Path part,
            ProgressListener progress,
            CancelSignal cancel
    ) throws IOException, CancelledException {
        Files.deleteIfExists(part);
        Request request = new Request.Builder().url(artifact.downloadUri().toString()).get().build();
        Call call = HTTP.newCall(request);
        this.activeCall = call;
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Download failed with HTTP " + response.code() + " for " + artifact.fileName());
            }
            ResponseBody body = response.body();
            try (InputStream input = new BufferedInputStream(body.byteStream());
                 OutputStream output = new BufferedOutputStream(Files.newOutputStream(part))) {
                byte[] buffer = new byte[128 * 1024];
                long written = 0L;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    checkCancelled(cancel);
                    if (read == 0) {
                        continue;
                    }
                    output.write(buffer, 0, read);
                    written += read;
                    progress.progress("downloading", "Downloading " + artifact.runtimeId(),
                            part.getFileName().toString(), written, artifact.sizeBytes());
                }
            }
        } finally {
            this.activeCall = null;
        }
        DownloadVerification.verify(part, artifact.sizeBytes(), artifact.sha256());
    }

    public void cancelActiveDownload() {
        Call call = this.activeCall;
        if (call != null) {
            call.cancel();
        }
    }

    /**
     * Publishes {@code staging} as {@code installRoot} after moving an existing
     * install aside; an interrupted or failed publish restores the old runtime.
     */
    private static void publish(Path staging, Path installRoot) throws IOException {
        installRoot = installRoot.toAbsolutePath().normalize();
        Path backup = installRoot.getParent().resolve(installRoot.getFileName() + ".previous");
        deleteRecursivelyQuietly(backup);
        boolean movedAside = false;
        try {
            if (Files.exists(installRoot)) {
                Files.move(installRoot, backup, StandardCopyOption.REPLACE_EXISTING);
                movedAside = true;
            }
            Files.createDirectories(installRoot.getParent());
            Files.move(staging, installRoot, StandardCopyOption.REPLACE_EXISTING);
            deleteRecursivelyQuietly(backup);
        } catch (IOException failure) {
            if (movedAside && Files.exists(backup) && !Files.exists(installRoot)) {
                Files.move(backup, installRoot, StandardCopyOption.REPLACE_EXISTING);
            }
            throw failure;
        }
    }

    public static Path findExecutable(Path root, String name) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (var paths = Files.walk(root, 6)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(name))
                    .findFirst()
                    .orElse(null);
        } catch (IOException exception) {
            return null;
        }
    }

    private static void makeExecutable(Path path) {
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions = java.util.EnumSet.copyOf(
                    Files.getPosixFilePermissions(path));
            permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            path.toFile().setExecutable(true, true);
        }
    }

    /** Release archives omit POSIX mode bits; Colibri launches these engine files itself. */
    private static void makeColibriEnginesExecutable(Path runtimeRoot) throws IOException {
        try (var files = Files.list(runtimeRoot)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (Files.isRegularFile(file) && !name.contains(".") && Files.size(file) > 64 * 1024L) {
                    makeExecutable(file);
                }
            }
        }
    }

    private static void checkCancelled(CancelSignal cancel) throws CancelledException {
        if (cancel.cancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
    }

    private static void deleteRecursivelyQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            List<Path> children = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path child : children) {
                Files.deleteIfExists(child);
            }
        } catch (IOException ignored) {
        }
    }
}
