package com.spirit.koil.api.model.install;

import com.spirit.koil.api.model.BinaryStorageFormatter;
import com.spirit.koil.api.model.catalog.LocalModelCatalog;
import com.spirit.koil.api.model.catalog.LocalModelCatalogEntry;
import com.spirit.koil.api.model.catalog.LocalModelRuntimeResolver;
import com.spirit.koil.api.model.catalog.LocalModelSelection;
import com.spirit.koil.api.model.catalog.LocalModelSelectionStore;
import com.spirit.koil.api.model.catalog.ModelArtifact;
import com.spirit.koil.api.model.catalog.ModelRuntimeCompatibility;
import com.spirit.koil.api.util.file.KoilInstancePaths;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class LocalModelInstallationService {
    public static final Path ROOT = KoilInstancePaths.modelRoot();
    public static final Path RUNTIME_ROOT = ROOT.resolve("runtime");
    public static final Path MODEL_ROOT = ROOT.resolve("models");
    private static final long STORAGE_HEADROOM = 1024L * 1024L * 1024L;
    private static final LocalModelInstallationService INSTANCE = new LocalModelInstallationService();
    private final ManagedRuntimeInstaller runtimeInstaller = new ManagedRuntimeInstaller();
    private final HuggingFaceSnapshotInstaller snapshotInstaller = new HuggingFaceSnapshotInstaller();

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20L, TimeUnit.SECONDS)
            .readTimeout(0L, TimeUnit.MILLISECONDS)
            .build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "koil-model-installer");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean cancellation = new AtomicBoolean();
    private volatile ModelInstallationSnapshot snapshot = ModelInstallationSnapshot.idle();
    private volatile Call activeCall;

    private LocalModelInstallationService() {
    }

    public static LocalModelInstallationService instance() {
        return INSTANCE;
    }

    public ModelInstallationSnapshot snapshot() {
        return this.snapshot;
    }

    public boolean install(String catalogId) {
        return beginInstall(catalogId, null);
    }

    public CompletableFuture<ModelInstallationSnapshot> installWithResult(String catalogId) {
        return LocalModelCatalog.resolveForInstall(catalogId).thenCompose(resolved -> {
            CompletableFuture<ModelInstallationSnapshot> result = new CompletableFuture<>();
            LocalModelCatalogEntry requested = resolved.orElseGet(() -> LocalModelCatalog.find(catalogId).orElse(null));
            if (requested == null || !requested.runnable() || !beginInstall(requested.id(), result)) {
                result.complete(new ModelInstallationSnapshot(
                        ModelInstallationState.FAILED,
                        catalogId == null ? "" : catalogId,
                        this.snapshot.state().active()
                                ? "Another model installation operation is already active."
                                : requested != null && !requested.runnable()
                                        ? requested.canonical().unavailableReason()
                                        : "No verified local implementation could be resolved for this catalog model.",
                        "",
                        0L,
                        0L,
                        Instant.now()
                ));
            }
            return result;
        });
    }

    private synchronized boolean beginInstall(
            String catalogId,
            CompletableFuture<ModelInstallationSnapshot> result
    ) {
        LocalModelCatalogEntry entry = LocalModelCatalog.find(catalogId).orElse(null);
        if (entry == null || !entry.runnable() || this.snapshot.state().active()) {
            return false;
        }
        this.cancellation.set(false);
        update(ModelInstallationState.CHECKING, entry.id(), "Checking runtime, storage, and model files.", "", 0L, totalDownloadBytes(entry));
        this.worker.execute(() -> {
            installBlocking(entry);
            if (result != null) {
                result.complete(this.snapshot);
            }
        });
        return true;
    }

    public boolean cancel() {
        if (!this.snapshot.state().active() || !this.cancellation.compareAndSet(false, true)) {
            return false;
        }
        Call call = this.activeCall;
        if (call != null) {
            call.cancel();
        }
        return true;
    }

    public boolean installed(LocalModelCatalogEntry entry) {
        if (entry == null || !entry.runnable()) {
            return false;
        }
        LocalModelRuntimeResolver.Resolution resolution = LocalModelRuntimeResolver.resolve(entry);
        Path runtime = resolution.selectedOptional()
                .map(compat -> this.runtimeInstaller.installed(compat.runtimeId(), RUNTIME_ROOT))
                .map(ManagedRuntimeInstallation::executable)
                .orElse(null);
        if (runtime == null || !Files.isRegularFile(runtime)) {
            return false;
        }
        Path modelDirectory = modelDirectory(entry);
        ModelRuntimeCompatibility compatibility = resolution.selectedOptional().orElse(null);
        if (compatibility != null && compatibility.installsRepositorySnapshot()) {
            if (ColibriModelPreparation.required(compatibility)) {
                return ColibriModelPreparation.prepared(compatibility, modelDirectory);
            }
            return this.snapshotInstaller.installed(
                    modelDirectory,
                    compatibility.modelRepository(),
                    compatibility.modelRevision()
            );
        }
        for (ModelArtifact artifact : entry.artifacts()) {
            Path file = modelDirectory.resolve(artifact.fileName());
            try {
                if (!Files.isRegularFile(file) || Files.size(file) != artifact.sizeBytes()) {
                    return false;
                }
            } catch (IOException exception) {
                return false;
            }
        }
        return true;
    }

    public LocalModelSelection selection(LocalModelCatalogEntry entry) {
        if (!installed(entry)) {
            return LocalModelSelection.none();
        }
        LocalModelRuntimeResolver.Resolution resolution = LocalModelRuntimeResolver.resolve(entry);
        ModelRuntimeCompatibility compat = resolution.selectedOptional().orElse(null);
        ManagedRuntimeInstallation runtime = compat == null
                ? null
                : this.runtimeInstaller.installed(compat.runtimeId(), RUNTIME_ROOT);
        if (compat == null || runtime == null) {
            return LocalModelSelection.none();
        }
        Path directory = modelDirectory(entry);
        Path modelPath = compat.artifactFormat() == com.spirit.koil.api.model.catalog.ModelArtifactFormat.GGUF_FILE
                ? directory.resolve(entry.primaryFileName()).toAbsolutePath().normalize()
                : directory.toAbsolutePath().normalize();
        return new LocalModelSelection(
                entry.id(),
                compat.providerId(),
                compat.runtimeId(),
                compat.architectureId(),
                entry.modelId(),
                runtime.executable(),
                directory.toAbsolutePath().normalize(),
                modelPath,
                compat.tokenizerFamily(),
                "",
                compat.maximumContextTokens() > 0 ? compat.maximumContextTokens() : entry.contextTokens()
        );
    }

    public boolean selectInstalled(LocalModelCatalogEntry entry) {
        LocalModelSelection selection = selection(entry);
        if (!selection.complete()) {
            return false;
        }
        LocalModelSelectionStore.save(selection);
        return true;
    }

    public List<LocalModelCatalogEntry> installedEntries() {
        return LocalModelCatalog.entries().stream().filter(this::installed).toList();
    }

    public long installedBytes(LocalModelCatalogEntry entry) {
        if (entry == null) {
            return 0L;
        }
        Path directory = modelDirectory(entry).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            return 0L;
        }
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException ignored) {
                    return 0L;
                }
            }).sum();
        } catch (IOException exception) {
            return 0L;
        }
    }

    public StoragePlan storagePlan(LocalModelCatalogEntry entry) {
        if (entry == null) {
            return new StoragePlan(0L, STORAGE_HEADROOM, 0L, 0L, false);
        }
        long remaining = remainingDownloadBytes(entry);
        ModelRuntimeCompatibility compatibility = LocalModelRuntimeResolver.resolve(entry).selectedOptional().orElse(null);
        long preparation = ColibriModelPreparation.additionalStorageBytes(compatibility);
        long total = Math.addExact(remaining, preparation);
        long required = Math.addExact(total, STORAGE_HEADROOM);
        long usable;
        try {
            Files.createDirectories(ROOT);
            usable = Files.getFileStore(ROOT.toAbsolutePath().normalize()).getUsableSpace();
        } catch (IOException exception) {
            usable = 0L;
        }
        return new StoragePlan(total, STORAGE_HEADROOM, required, usable, usable >= required);
    }

    public CompletableFuture<UninstallResult> uninstall(String catalogId) {
        LocalModelCatalogEntry entry = LocalModelCatalog.find(catalogId).orElse(null);
        if (entry == null) {
            return CompletableFuture.completedFuture(new UninstallResult(false, 0L, "Unknown local model catalog id."));
        }
        synchronized (this) {
            if (this.snapshot.state().active()) {
                return CompletableFuture.completedFuture(new UninstallResult(
                        false,
                        0L,
                        "Another model installation operation is already active."
                ));
            }
            update(ModelInstallationState.UNINSTALLING, entry.id(), "Removing " + entry.displayName() + ".", "", 0L, 0L);
        }
        CompletableFuture<UninstallResult> result = new CompletableFuture<>();
        this.worker.execute(() -> {
            UninstallResult outcome;
            try {
                outcome = uninstallBlocking(entry);
                update(ModelInstallationState.IDLE, "", outcome.detail(), "", 0L, 0L);
            } catch (Exception exception) {
                outcome = new UninstallResult(false, 0L, message(exception));
                update(ModelInstallationState.FAILED, entry.id(), outcome.detail(), "", 0L, 0L);
            }
            result.complete(outcome);
        });
        return result;
    }

    private UninstallResult uninstallBlocking(LocalModelCatalogEntry entry) throws IOException {
        Path root = MODEL_ROOT.toAbsolutePath().normalize();
        Path target = modelDirectory(entry).toAbsolutePath().normalize();
        if (target.equals(root) || !target.startsWith(root)) {
            throw new IOException("Refusing to remove a model outside Koil's model directory.");
        }
        if (!Files.exists(target)) {
            return new UninstallResult(false, 0L, entry.displayName() + " is not installed.");
        }
        long removedBytes = installedBytes(entry);
        List<Path> paths;
        try (var walk = Files.walk(target)) {
            paths = new ArrayList<>(walk.sorted(Comparator.reverseOrder()).toList());
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
        LocalModelSelection selected = LocalModelSelectionStore.load();
        if (entry.id().equals(selected.catalogId())) {
            LocalModelSelectionStore.clear();
        }
        return new UninstallResult(true, removedBytes, entry.displayName() + " was uninstalled.");
    }

    private void installBlocking(LocalModelCatalogEntry entry) {
        long total = totalDownloadBytes(entry);
        long completed = 0L;
        try {
            Files.createDirectories(RUNTIME_ROOT);
            Files.createDirectories(MODEL_ROOT);
            LocalModelRuntimeResolver.Resolution resolution = LocalModelRuntimeResolver.resolve(entry);
            ModelRuntimeCompatibility compat = resolution.selectedOptional()
                    .orElseThrow(() -> new IOException("No compatible runtime for this model on "
                            + com.spirit.koil.api.model.catalog.LocalModelRuntimePlatform.currentId()
                            + ": " + resolution.evidence()));
            long runtimeBytes = ManagedRuntimeCatalog.current(compat.runtimeId())
                    .map(ManagedRuntimeArtifact::sizeBytes)
                    .orElse(0L);
            HuggingFaceSnapshotInstaller.SnapshotManifest repositorySnapshot = compat.installsRepositorySnapshot()
                    ? this.snapshotInstaller.resolve(
                            compat.modelRepository(), compat.modelRevision(), () -> this.cancellation.get())
                    : null;
            long modelBytes = repositorySnapshot == null ? entry.downloadBytes() : repositorySnapshot.totalBytes();
            long preparationBytes = ColibriModelPreparation.additionalStorageBytes(compat);
            total = Math.addExact(runtimeBytes, modelBytes);
            final long plannedTotal = total;
            long required = Math.addExact(Math.addExact(modelBytes, preparationBytes), STORAGE_HEADROOM);
            FileStore store = Files.getFileStore(ROOT.toAbsolutePath().normalize());
            if (store.getUsableSpace() < required) {
                throw new IOException("Not enough free storage. Need "
                        + formatBytes(required) + " including 1 gb safety headroom, but only "
                        + formatBytes(store.getUsableSpace()) + " is available.");
            }
            ManagedRuntimeInstallation runtime = this.runtimeInstaller.installed(compat.runtimeId(), RUNTIME_ROOT);
            if (runtime == null) {
                long completedBefore = completed;
                ManagedRuntimeInstaller.ProgressListener progress = (stage, detail, file, done, totalBytes) -> {
                    ModelInstallationState state = switch (stage) {
                        case "downloading" -> ModelInstallationState.DOWNLOADING_RUNTIME;
                        case "extracting" -> ModelInstallationState.EXTRACTING_RUNTIME;
                        case "building" -> ModelInstallationState.BUILDING_RUNTIME;
                        default -> ModelInstallationState.EXTRACTING_RUNTIME;
                    };
                    long reportedDone = done >= 0L ? completedBefore + done : completedBefore;
                    update(state, entry.id(), detail, file, reportedDone, plannedTotal);
                };
                runtime = this.runtimeInstaller.ensureInstalled(
                        compat.runtimeId(),
                        RUNTIME_ROOT,
                        progress,
                        () -> this.cancellation.get()
                );
                com.spirit.koil.api.model.LocalModelRuntimeLog.write("runtime_install", "installed managed runtime "
                        + compat.runtimeId() + (runtime.sourceBuilt() ? " (source-built)" : ""));
            }
            completed += runtimeBytes;

            Path modelDirectory = modelDirectory(entry);
            Files.createDirectories(modelDirectory);
            if (repositorySnapshot != null) {
                long completedBefore = completed;
                Path snapshotDirectory = ColibriModelPreparation.required(compat)
                        ? ColibriModelPreparation.sourceDirectory(modelDirectory) : modelDirectory;
                this.snapshotInstaller.install(repositorySnapshot, snapshotDirectory,
                        (stage, detail, file, done, ignoredTotal) -> update(
                                ModelInstallationState.DOWNLOADING_MODEL,
                                entry.id(), detail, file,
                                completedBefore + Math.max(0L, done), plannedTotal),
                        () -> this.cancellation.get());
                completed += repositorySnapshot.totalBytes();
            } else {
                for (ModelArtifact artifact : entry.artifacts()) {
                    Path destination = modelDirectory.resolve(artifact.fileName());
                    if (validSize(destination, artifact.sizeBytes())) {
                        completed += artifact.sizeBytes();
                        continue;
                    }
                    Path part = modelDirectory.resolve(artifact.fileName() + ".part");
                    update(ModelInstallationState.DOWNLOADING_MODEL, entry.id(), "Downloading " + entry.displayName() + ".",
                            artifact.fileName(), completed, plannedTotal);
                    download(artifact.downloadUri().toString(), part, artifact.sizeBytes(), artifact.sha256(),
                            entry.id(), ModelInstallationState.DOWNLOADING_MODEL, completed, plannedTotal);
                    moveAtomically(part, destination);
                    completed += artifact.sizeBytes();
                }
            }
            long completedBeforePreparation = completed;
            ColibriModelPreparation.prepare(compat, RUNTIME_ROOT,
                    ColibriModelPreparation.sourceDirectory(modelDirectory), modelDirectory,
                    (stage, detail, file, done, ignoredTotal) -> update(ModelInstallationState.VERIFYING,
                            entry.id(), detail, file, completedBeforePreparation, plannedTotal),
                    () -> this.cancellation.get());
            checkCancelled();
            update(ModelInstallationState.VERIFYING, entry.id(), "Verifying installed runtime and model files.", "",
                    completed, plannedTotal);
            if (repositorySnapshot == null) {
                for (ModelArtifact artifact : entry.artifacts()) {
                    Path model = modelDirectory.resolve(artifact.fileName());
                    verify(model, artifact.sizeBytes(), artifact.sha256());
                }
            } else {
                Path snapshotDirectory = ColibriModelPreparation.required(compat)
                        ? ColibriModelPreparation.sourceDirectory(modelDirectory) : modelDirectory;
                if (!this.snapshotInstaller.installed(snapshotDirectory, compat.modelRepository(), compat.modelRevision())) {
                    throw new IOException("Pinned Hugging Face snapshot did not publish a valid completion marker");
                }
                if (!ColibriModelPreparation.prepared(compat, modelDirectory)) {
                    throw new IOException("Colibri model preparation did not publish a runnable container");
                }
            }
            if (!Files.isRegularFile(runtime.executable())) {
                throw new IOException("The managed runtime executable is missing after installation: " + compat.runtimeId());
            }
            makeExecutable(runtime.executable());
            LocalModelSelection selection = new LocalModelSelection(
                    entry.id(),
                    compat.providerId(),
                    compat.runtimeId(),
                    compat.architectureId(),
                    entry.modelId(),
                    runtime.executable(),
                    modelDirectory.toAbsolutePath().normalize(),
                    compat.artifactFormat() == com.spirit.koil.api.model.catalog.ModelArtifactFormat.GGUF_FILE
                            ? modelDirectory.resolve(entry.primaryFileName()).toAbsolutePath().normalize()
                            : modelDirectory.toAbsolutePath().normalize(),
                    compat.tokenizerFamily(),
                    "",
                    compat.maximumContextTokens() > 0 ? compat.maximumContextTokens() : entry.contextTokens()
            );
            LocalModelSelectionStore.save(selection);
            update(ModelInstallationState.READY, entry.id(), entry.displayName() + " is installed and selected.", "",
                    plannedTotal, plannedTotal);
        } catch (ManagedRuntimeInstaller.CancelledException cancelledByInstaller) {
            update(ModelInstallationState.CANCELLED, entry.id(), "Model installation was cancelled. It can be retried safely.", "",
                    Math.min(completed, total), total);
        } catch (CancelledException exception) {
            update(ModelInstallationState.CANCELLED, entry.id(), "Model installation was cancelled. It can be retried safely.", "",
                    Math.min(completed, total), total);
        } catch (Exception exception) {
            if (this.cancellation.get()) {
                update(ModelInstallationState.CANCELLED, entry.id(), "Model installation was cancelled. It can be retried safely.", "",
                        Math.min(completed, total), total);
            } else {
                update(ModelInstallationState.FAILED, entry.id(), message(exception), "", Math.min(completed, total), total);
            }
        } finally {
            this.activeCall = null;
        }
    }

    private void download(
            String url,
            Path part,
            long expectedBytes,
            String sha256,
            String catalogId,
            ModelInstallationState state,
            long completedBefore,
            long totalBytes
    ) throws IOException, CancelledException {
        Files.createDirectories(part.toAbsolutePath().normalize().getParent());
        Files.deleteIfExists(part);
        Request request = new Request.Builder().url(url).get().build();
        Call call = this.http.newCall(request);
        this.activeCall = call;
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Download failed with HTTP " + response.code() + " for " + part.getFileName());
            }
            ResponseBody body = response.body();
            try (InputStream input = new BufferedInputStream(body.byteStream());
                 OutputStream output = new BufferedOutputStream(Files.newOutputStream(part))) {
                byte[] buffer = new byte[128 * 1024];
                long written = 0L;
                long lastUpdate = 0L;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    checkCancelled();
                    if (read == 0) {
                        continue;
                    }
                    output.write(buffer, 0, read);
                    written += read;
                    if (written - lastUpdate >= 1024L * 1024L) {
                        lastUpdate = written;
                        update(state, catalogId, this.snapshot.detail(), part.getFileName().toString(),
                                completedBefore + written, totalBytes);
                    }
                }
            }
        } finally {
            this.activeCall = null;
        }
        verify(part, expectedBytes, sha256);
    }

    private static void verify(Path path, long expectedBytes, String expectedSha256) throws IOException {
        DownloadVerification.verify(path, expectedBytes, expectedSha256);
    }

    private static void makeExecutable(Path path) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            permissions = EnumSet.copyOf(permissions);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            path.toFile().setExecutable(true, true);
        }
    }

    private static Path modelDirectory(LocalModelCatalogEntry entry) {
        return MODEL_ROOT.resolve(entry.id());
    }

    private long totalDownloadBytes(LocalModelCatalogEntry entry) {
        long runtime = resolvedRuntimeBytes(entry, true);
        return Math.addExact(runtime, entry.downloadBytes());
    }

    private long remainingDownloadBytes(LocalModelCatalogEntry entry) {
        long remaining = resolvedRuntimeBytes(entry, false);
        for (ModelArtifact artifact : entry.artifacts()) {
            if (!validSize(modelDirectory(entry).resolve(artifact.fileName()), artifact.sizeBytes())) {
                remaining = Math.addExact(remaining, artifact.sizeBytes());
            }
        }
        return remaining;
    }

    /**
     * Exact download size of the resolved managed runtime, either unconditionally
     * (for display totals) or only when it is not already installed (remaining).
     */
    private long resolvedRuntimeBytes(LocalModelCatalogEntry entry, boolean always) {
        LocalModelRuntimeResolver.Resolution resolution = LocalModelRuntimeResolver.resolve(entry);
        String runtimeId = resolution.selectedOptional().map(ModelRuntimeCompatibility::runtimeId).orElse("");
        if (runtimeId.isBlank()) {
            return 0L;
        }
        if (!always && runtimeInstaller.installed(runtimeId, RUNTIME_ROOT) != null) {
            return 0L;
        }
        return ManagedRuntimeCatalog.current(runtimeId).map(ManagedRuntimeArtifact::sizeBytes).orElse(0L);
    }

    private static boolean validSize(Path path, long expected) {
        try {
            return Files.isRegularFile(path) && Files.size(path) == expected;
        } catch (IOException exception) {
            return false;
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException unsupportedAtomicMove) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void checkCancelled() throws CancelledException {
        if (this.cancellation.get() || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
    }

    private void update(
            ModelInstallationState state,
            String catalogId,
            String detail,
            String currentFile,
            long completed,
            long total
    ) {
        this.snapshot = new ModelInstallationSnapshot(
                state, catalogId, detail, currentFile, completed, total, Instant.now()
        );
    }

    private static String message(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String value = cursor.getMessage();
        return value == null || value.isBlank() ? cursor.getClass().getSimpleName() : value;
    }

    private static String formatBytes(long bytes) {
        return BinaryStorageFormatter.format(bytes);
    }

    public record StoragePlan(
            long remainingDownloadBytes,
            long safetyHeadroomBytes,
            long requiredBytes,
            long usableBytes,
            boolean fits
    ) {
    }

    public record UninstallResult(boolean removed, long removedBytes, String detail) {
        public UninstallResult {
            detail = detail == null ? "" : detail;
        }
    }

    private static final class CancelledException extends Exception {
    }
}
