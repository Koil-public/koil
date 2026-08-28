package com.spirit.koil.api.model.install;

import com.spirit.koil.api.model.BinaryStorageFormatter;
import com.spirit.koil.api.model.catalog.LocalModelCatalog;
import com.spirit.koil.api.model.catalog.LocalModelCatalogEntry;
import com.spirit.koil.api.model.catalog.LocalModelSelection;
import com.spirit.koil.api.model.catalog.LocalModelSelectionStore;
import com.spirit.koil.api.model.catalog.ModelArtifact;
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
    public static final Path ROOT = Path.of("koil/sys/model");
    public static final Path RUNTIME_ROOT = ROOT.resolve("runtime");
    public static final Path MODEL_ROOT = ROOT.resolve("models");
    private static final long STORAGE_HEADROOM = 1024L * 1024L * 1024L;
    private static final LocalModelInstallationService INSTANCE = new LocalModelInstallationService();

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
        Path runtime = runtimeExecutable();
        if (runtime == null || !Files.isRegularFile(runtime)) {
            return false;
        }
        Path modelDirectory = modelDirectory(entry);
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
        Path runtime = runtimeExecutable();
        if (!installed(entry) || runtime == null) {
            return LocalModelSelection.none();
        }
        return new LocalModelSelection(
                entry.id(),
                entry.providerId(),
                entry.modelId(),
                runtime.toAbsolutePath().normalize(),
                modelDirectory(entry).resolve(entry.primaryFileName()).toAbsolutePath().normalize(),
                entry.contextTokens()
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
        long required = Math.addExact(remaining, STORAGE_HEADROOM);
        long usable;
        try {
            Files.createDirectories(ROOT);
            usable = Files.getFileStore(ROOT.toAbsolutePath().normalize()).getUsableSpace();
        } catch (IOException exception) {
            usable = 0L;
        }
        return new StoragePlan(remaining, STORAGE_HEADROOM, required, usable, usable >= required);
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
            long required = remainingDownloadBytes(entry) + STORAGE_HEADROOM;
            FileStore store = Files.getFileStore(ROOT.toAbsolutePath().normalize());
            if (store.getUsableSpace() < required) {
                throw new IOException("Not enough free storage. Need "
                        + formatBytes(required) + " including 1 gb safety headroom, but only "
                        + formatBytes(store.getUsableSpace()) + " is available.");
            }
            LlamaCppRuntimeCatalog.RuntimeArtifact runtimeArtifact = LlamaCppRuntimeCatalog.currentPlatform()
                    .orElseThrow(() -> new IOException("No verified llama.cpp runtime is available for this operating system and architecture."));
            Path runtime = runtimeExecutable();
            if (runtime == null || !Files.isRegularFile(runtime)) {
                Path archive = RUNTIME_ROOT.resolve(runtimeArtifact.fileName() + ".part");
                update(ModelInstallationState.DOWNLOADING_RUNTIME, entry.id(), "Downloading verified llama.cpp runtime.",
                        runtimeArtifact.fileName(), completed, total);
                download(runtimeArtifact.downloadUri().toString(), archive, runtimeArtifact.sizeBytes(), runtimeArtifact.sha256(),
                        entry.id(), ModelInstallationState.DOWNLOADING_RUNTIME, completed, total);
                completed += runtimeArtifact.sizeBytes();
                checkCancelled();
                update(ModelInstallationState.EXTRACTING_RUNTIME, entry.id(), "Extracting llama.cpp runtime.",
                        runtimeArtifact.fileName(), completed, total);
                Path runtimeDirectory = runtimeDirectory();
                Files.createDirectories(runtimeDirectory);
                extractArchive(archive, runtimeDirectory, runtimeArtifact.archiveType());
                Files.deleteIfExists(archive);
                runtime = findRuntimeExecutable(runtimeDirectory);
                if (runtime == null) {
                    throw new IOException("The verified runtime archive did not contain llama-server.");
                }
                makeExecutable(runtime);
                Files.writeString(runtimeMarker(), runtimeArtifact.sha256(), StandardCharsets.UTF_8);
            } else {
                completed += runtimeArtifact.sizeBytes();
            }

            Path modelDirectory = modelDirectory(entry);
            Files.createDirectories(modelDirectory);
            for (ModelArtifact artifact : entry.artifacts()) {
                Path destination = modelDirectory.resolve(artifact.fileName());
                if (validSize(destination, artifact.sizeBytes())) {
                    completed += artifact.sizeBytes();
                    continue;
                }
                Path part = modelDirectory.resolve(artifact.fileName() + ".part");
                update(ModelInstallationState.DOWNLOADING_MODEL, entry.id(), "Downloading " + entry.displayName() + ".",
                        artifact.fileName(), completed, total);
                download(artifact.downloadUri().toString(), part, artifact.sizeBytes(), artifact.sha256(),
                        entry.id(), ModelInstallationState.DOWNLOADING_MODEL, completed, total);
                moveAtomically(part, destination);
                completed += artifact.sizeBytes();
            }
            checkCancelled();
            update(ModelInstallationState.VERIFYING, entry.id(), "Verifying installed runtime and model files.", "",
                    completed, total);
            for (ModelArtifact artifact : entry.artifacts()) {
                Path model = modelDirectory.resolve(artifact.fileName());
                verify(model, artifact.sizeBytes(), artifact.sha256());
            }
            runtime = findRuntimeExecutable(runtimeDirectory());
            if (runtime == null || !Files.isRegularFile(runtime)) {
                throw new IOException("llama-server is missing after installation.");
            }
            makeExecutable(runtime);
            LocalModelSelection selection = new LocalModelSelection(
                    entry.id(),
                    entry.providerId(),
                    entry.modelId(),
                    runtime.toAbsolutePath().normalize(),
                    modelDirectory.resolve(entry.primaryFileName()).toAbsolutePath().normalize(),
                    entry.contextTokens()
            );
            LocalModelSelectionStore.save(selection);
            update(ModelInstallationState.READY, entry.id(), entry.displayName() + " is installed and selected.", "",
                    total, total);
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
        long actualBytes = Files.size(path);
        if (actualBytes != expectedBytes) {
            throw new IOException("Size verification failed for " + path.getFileName()
                    + ": expected " + expectedBytes + " bytes, got " + actualBytes + ".");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception exception) {
            throw new IOException("SHA-256 is unavailable.", exception);
        }
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            throw new IOException("SHA-256 verification failed for " + path.getFileName() + ".");
        }
    }

    private static void extractArchive(Path archive, Path output, String archiveType) throws IOException {
        if ("zip".equals(archiveType)) {
            extractZip(archive, output);
        } else if ("tar.gz".equals(archiveType)) {
            extractTarGz(archive, output);
        } else {
            throw new IOException("Unsupported runtime archive type: " + archiveType);
        }
    }

    private static void extractZip(Path archive, Path output) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = safeTarget(output, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
                        zip.transferTo(out);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void extractTarGz(Path archive, Path output) throws IOException {
        try (InputStream input = new BufferedInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
            byte[] header = new byte[512];
            while (true) {
                readFully(input, header);
                if (allZero(header)) {
                    return;
                }
                String name = text(header, 0, 100);
                String prefix = text(header, 345, 155);
                if (!prefix.isBlank()) {
                    name = prefix + "/" + name;
                }
                long size = octal(header, 124, 12);
                int type = header[156] & 0xFF;
                Path target = safeTarget(output, name);
                if (type == '5') {
                    Files.createDirectories(target);
                    skipFully(input, size);
                } else if (type == 0 || type == '0') {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
                        copyExactly(input, out, size);
                    }
                } else if (type == '2') {
                    String linkName = text(header, 157, 100);
                    Path linkTarget = Path.of(linkName);
                    if (linkTarget.isAbsolute() || target.getParent() == null
                            || !target.getParent().resolve(linkTarget).normalize().startsWith(output.toAbsolutePath().normalize())) {
                        throw new IOException("Unsafe symlink in runtime archive: " + name);
                    }
                    Files.createDirectories(target.getParent());
                    Files.deleteIfExists(target);
                    Files.createSymbolicLink(target, linkTarget);
                    skipFully(input, size);
                } else {
                    skipFully(input, size);
                }
                long padding = (512L - size % 512L) % 512L;
                skipFully(input, padding);
            }
        } catch (EOFException exception) {
            throw new IOException("Runtime archive ended unexpectedly.", exception);
        }
    }

    private static void copyExactly(InputStream input, OutputStream output, long bytes) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long remaining = bytes;
        while (remaining > 0L) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new EOFException();
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void readFully(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = input.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                throw new EOFException();
            }
            offset += read;
        }
    }

    private static void skipFully(InputStream input, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0L) {
            long skipped = input.skip(remaining);
            if (skipped <= 0L) {
                if (input.read() < 0) {
                    throw new EOFException();
                }
                skipped = 1L;
            }
            remaining -= skipped;
        }
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static String text(byte[] bytes, int offset, int length) {
        int end = offset;
        int maximum = Math.min(bytes.length, offset + length);
        while (end < maximum && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long octal(byte[] bytes, int offset, int length) {
        String value = text(bytes, offset, length).replace("\u0000", "").trim();
        return value.isEmpty() ? 0L : Long.parseLong(value, 8);
    }

    private static Path safeTarget(Path root, String entryName) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(entryName).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IOException("Unsafe path in runtime archive: " + entryName);
        }
        return target;
    }

    private static Path runtimeDirectory() {
        return RUNTIME_ROOT.resolve("llama.cpp-" + LlamaCppRuntimeCatalog.VERSION);
    }

    private static Path runtimeExecutable() {
        String expected = LlamaCppRuntimeCatalog.currentPlatform()
                .map(LlamaCppRuntimeCatalog.RuntimeArtifact::sha256)
                .orElse("");
        try {
            if (expected.isBlank()
                    || !Files.isRegularFile(runtimeMarker())
                    || !expected.equalsIgnoreCase(Files.readString(runtimeMarker(), StandardCharsets.UTF_8).trim())) {
                return null;
            }
        } catch (IOException exception) {
            return null;
        }
        return findRuntimeExecutable(runtimeDirectory());
    }

    private static Path runtimeMarker() {
        return runtimeDirectory().resolve(".koil-runtime-sha256");
    }

    private static Path findRuntimeExecutable(Path root) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        String expected = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "llama-server.exe"
                : "llama-server";
        try (var paths = Files.walk(root, 5)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(expected))
                    .findFirst()
                    .orElse(null);
        } catch (IOException exception) {
            return null;
        }
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
        long runtime = LlamaCppRuntimeCatalog.currentPlatform().map(LlamaCppRuntimeCatalog.RuntimeArtifact::sizeBytes).orElse(0L);
        return Math.addExact(runtime, entry.downloadBytes());
    }

    private long remainingDownloadBytes(LocalModelCatalogEntry entry) {
        long remaining = runtimeExecutable() == null
                ? LlamaCppRuntimeCatalog.currentPlatform().map(LlamaCppRuntimeCatalog.RuntimeArtifact::sizeBytes).orElse(0L)
                : 0L;
        for (ModelArtifact artifact : entry.artifacts()) {
            if (!validSize(modelDirectory(entry).resolve(artifact.fileName()), artifact.sizeBytes())) {
                remaining = Math.addExact(remaining, artifact.sizeBytes());
            }
        }
        return remaining;
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
