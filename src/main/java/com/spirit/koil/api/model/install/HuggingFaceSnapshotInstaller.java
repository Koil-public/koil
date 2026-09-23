package com.spirit.koil.api.model.install;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Installs one immutable Hugging Face repository snapshot into a model directory.
 *
 * <p>The repository commit is part of the catalog compatibility record. LFS files
 * are verified with their Hub SHA-256 object id; small Git files are protected by
 * the immutable commit and their advertised byte length. Publication is per-file
 * atomic and the completion marker is written last, so a cancelled download is
 * never mistaken for an installed model.</p>
 */
public final class HuggingFaceSnapshotInstaller {
    private static final String MARKER = ".koil-hf-snapshot.json";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(20L, TimeUnit.SECONDS)
            .readTimeout(0L, TimeUnit.MILLISECONDS)
            .build();

    public SnapshotManifest resolve(
            String repository,
            String revision,
            ManagedRuntimeInstaller.CancelSignal cancel
    ) throws IOException, ManagedRuntimeInstaller.CancelledException {
        checkCancelled(cancel);
        String url = "https://huggingface.co/api/models/" + encodePath(repository)
                + "/revision/" + encodeSegment(revision) + "?blobs=true";
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Hugging Face manifest request failed with HTTP " + response.code());
            }
            JsonObject root = JsonParser.parseString(response.body().string()).getAsJsonObject();
            String resolvedRevision = string(root, "sha");
            if (!revision.equalsIgnoreCase(resolvedRevision)) {
                throw new IOException("Hugging Face resolved an unexpected model revision");
            }
            JsonArray siblings = root.has("siblings") && root.get("siblings").isJsonArray()
                    ? root.getAsJsonArray("siblings") : new JsonArray();
            List<SnapshotFile> files = new ArrayList<>();
            for (JsonElement element : siblings) {
                if (!element.isJsonObject()) continue;
                JsonObject sibling = element.getAsJsonObject();
                String relative = string(sibling, "rfilename");
                if (!safeRelative(relative)) {
                    throw new IOException("Hugging Face returned an unsafe model path: " + relative);
                }
                JsonObject lfs = sibling.has("lfs") && sibling.get("lfs").isJsonObject()
                        ? sibling.getAsJsonObject("lfs") : null;
                long size = lfs == null ? number(sibling, "size") : number(lfs, "size");
                String sha256 = lfs == null ? "" : string(lfs, "sha256").toLowerCase(java.util.Locale.ROOT);
                if (size < 0L || !sha256.isEmpty() && !sha256.matches("[0-9a-f]{64}")) {
                    throw new IOException("Hugging Face returned invalid metadata for " + relative);
                }
                files.add(new SnapshotFile(relative, size, sha256));
            }
            files.sort(Comparator.comparing(SnapshotFile::relativePath));
            if (files.isEmpty() || files.stream().noneMatch(file -> file.relativePath().equals("config.json"))) {
                throw new IOException("The pinned Hugging Face snapshot has no model config.json");
            }
            return new SnapshotManifest(repository, revision, List.copyOf(files));
        }
    }

    public void install(
            SnapshotManifest manifest,
            Path targetDirectory,
            ManagedRuntimeInstaller.ProgressListener progress,
            ManagedRuntimeInstaller.CancelSignal cancel
    ) throws IOException, ManagedRuntimeInstaller.CancelledException {
        Path root = targetDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        long total = manifest.totalBytes();
        long completed = 0L;
        for (SnapshotFile file : manifest.files()) {
            checkCancelled(cancel);
            Path destination = resolveSafe(root, file.relativePath());
            if (valid(destination, file)) {
                completed += Math.max(0L, file.sizeBytes());
                continue;
            }
            Files.createDirectories(destination.getParent());
            Path part = destination.resolveSibling(destination.getFileName() + ".part");
            long existing = Files.isRegularFile(part) ? Files.size(part) : 0L;
            if (file.sizeBytes() >= 0L && existing > file.sizeBytes()) {
                Files.delete(part);
                existing = 0L;
            }
            progress.progress("downloading", "Downloading pinned Colibri model snapshot",
                    file.relativePath(), completed + existing, total);
            download(manifest, file, part, existing, completed, total, progress, cancel);
            verify(part, file);
            moveAtomically(part, destination);
            completed += Math.max(0L, file.sizeBytes());
        }
        JsonObject marker = new JsonObject();
        marker.addProperty("repository", manifest.repository());
        marker.addProperty("revision", manifest.revision());
        marker.addProperty("files", manifest.files().size());
        marker.addProperty("bytes", manifest.totalBytes());
        Path markerPart = root.resolve(MARKER + ".part");
        Files.writeString(markerPart, marker.toString(), StandardCharsets.UTF_8);
        moveAtomically(markerPart, root.resolve(MARKER));
    }

    public boolean installed(Path targetDirectory, String repository, String revision) {
        Path marker = targetDirectory.toAbsolutePath().normalize().resolve(MARKER);
        try {
            JsonObject root = JsonParser.parseString(Files.readString(marker, StandardCharsets.UTF_8)).getAsJsonObject();
            return repository.equals(string(root, "repository"))
                    && revision.equals(string(root, "revision"))
                    && Files.isRegularFile(targetDirectory.resolve("config.json"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void download(
            SnapshotManifest manifest,
            SnapshotFile file,
            Path part,
            long existing,
            long completedBefore,
            long total,
            ManagedRuntimeInstaller.ProgressListener progress,
            ManagedRuntimeInstaller.CancelSignal cancel
    ) throws IOException, ManagedRuntimeInstaller.CancelledException {
        String url = "https://huggingface.co/" + encodePath(manifest.repository())
                + "/resolve/" + encodeSegment(manifest.revision()) + "/" + encodePath(file.relativePath());
        Request.Builder request = new Request.Builder().url(url).get();
        if (existing > 0L) request.header("Range", "bytes=" + existing + "-");
        try (Response response = HTTP.newCall(request.build()).execute()) {
            if (!(response.isSuccessful() || response.code() == 206) || response.body() == null) {
                throw new IOException("Model download failed with HTTP " + response.code()
                        + " for " + file.relativePath());
            }
            boolean append = existing > 0L && response.code() == 206;
            long written = append ? existing : 0L;
            ResponseBody body = response.body();
            try (InputStream input = new BufferedInputStream(body.byteStream());
                 OutputStream output = new BufferedOutputStream(Files.newOutputStream(part,
                         StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                         append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING))) {
                byte[] buffer = new byte[256 * 1024];
                long lastReport = written;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    checkCancelled(cancel);
                    if (read == 0) continue;
                    output.write(buffer, 0, read);
                    written += read;
                    if (written - lastReport >= 4L * 1024L * 1024L) {
                        lastReport = written;
                        progress.progress("downloading", "Downloading pinned Colibri model snapshot",
                                file.relativePath(), completedBefore + written, total);
                    }
                }
            }
        }
    }

    private static void verify(Path path, SnapshotFile file) throws IOException {
        if (file.sizeBytes() >= 0L && Files.size(path) != file.sizeBytes()) {
            throw new IOException("Size verification failed for " + file.relativePath());
        }
        if (!file.sha256().isBlank()) {
            try (InputStream input = Files.newInputStream(path)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
                if (!file.sha256().equals(HexFormat.of().formatHex(digest.digest()))) {
                    throw new IOException("SHA-256 verification failed for " + file.relativePath());
                }
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IOException(impossible);
            }
        }
    }

    private static boolean valid(Path path, SnapshotFile file) {
        try {
            return Files.isRegularFile(path) && (file.sizeBytes() < 0L || Files.size(path) == file.sizeBytes());
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Path resolveSafe(Path root, String relative) throws IOException {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) throw new IOException("Model path escaped installation root");
        return resolved;
    }

    private static boolean safeRelative(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) return false;
        try {
            Path path = Path.of(value).normalize();
            return !path.isAbsolute() && !path.startsWith("..") && !value.contains("\\");
        } catch (java.nio.file.InvalidPathException invalid) {
            return false;
        }
    }

    private static String encodePath(String value) {
        return java.util.Arrays.stream(value.split("/"))
                .map(HuggingFaceSnapshotInstaller::encodeSegment)
                .collect(java.util.stream.Collectors.joining("/"));
    }

    private static String encodeSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : "";
    }

    private static long number(JsonObject object, String key) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsLong() : -1L;
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static void checkCancelled(ManagedRuntimeInstaller.CancelSignal cancel)
            throws ManagedRuntimeInstaller.CancelledException {
        if (cancel.cancelled() || Thread.currentThread().isInterrupted()) {
            throw new ManagedRuntimeInstaller.CancelledException();
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record SnapshotFile(String relativePath, long sizeBytes, String sha256) {
        public SnapshotFile {
            relativePath = relativePath == null ? "" : relativePath;
            sha256 = sha256 == null ? "" : sha256;
        }
    }

    public record SnapshotManifest(String repository, String revision, List<SnapshotFile> files) {
        public SnapshotManifest {
            repository = repository == null ? "" : repository;
            revision = revision == null ? "" : revision;
            files = files == null ? List.of() : List.copyOf(files);
        }

        public long totalBytes() {
            long total = 0L;
            for (SnapshotFile file : files) {
                if (file.sizeBytes() > 0L) total = Math.addExact(total, file.sizeBytes());
            }
            return total;
        }
    }
}
