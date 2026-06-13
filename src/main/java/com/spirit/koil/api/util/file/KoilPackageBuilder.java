package com.spirit.koil.api.util.file;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.Main;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.spirit.Main.PKG_SUBLOGGER;

public final class KoilPackageBuilder {
    private KoilPackageBuilder() {
    }

    public record BuildRequest(
            String packageSuffix,
            String displayName,
            String packageVersion,
            String targetKoilVersion,
            String authorId,
            String author,
            String serial,
            List<String> selectedRelativePaths
    ) {
    }

    public record BuildResult(File packageFile, int fileCount, long totalBytes, String packageId) {
    }

    public static BuildResult createPackage(BuildRequest request) throws IOException {
        if (request == null) {
            throw new IOException("Missing package request.");
        }
        if (request.author() == null || request.author().isBlank()) {
            throw new IOException("Author value is required.");
        }
        if (request.serial() == null || request.serial().isBlank()) {
            throw new IOException("Serial value is required.");
        }
        KoilPackageIdentity.GeneratedIdentity identity;
        try {
            identity = KoilPackageIdentity.generate(request.author(), request.serial());
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
        String packageId = identity.packageId();

        Path gameRoot = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        List<Path> files = collectSelectedFiles(gameRoot, request.selectedRelativePaths());
        if (files.isEmpty()) {
            throw new IOException("Select at least one file.");
        }

        Path outputDirectory = gameRoot.resolve("koil/packages/built").normalize();
        Files.createDirectories(outputDirectory);
        Path packageFile = outputDirectory.resolve(packageId + ".zip").normalize();
        Path tempPackageFile = outputDirectory.resolve(packageId + ".zip.tmp").normalize();
        Files.deleteIfExists(tempPackageFile);

        JsonArray operations = new JsonArray();
        long totalBytes = 0L;
        try (OutputStream outputStream = Files.newOutputStream(tempPackageFile);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            String root = packageId + "/";
            zipOutputStream.putNextEntry(new ZipEntry(root));
            zipOutputStream.closeEntry();

            for (Path file : files) {
                String relativePath = normalizeRelativePath(gameRoot.relativize(file).toString());
                if (shouldSkipOutputPath(relativePath)) {
                    continue;
                }
                String entryName = root + relativePath;
                ensureZipDirectories(zipOutputStream, root, relativePath);
                zipOutputStream.putNextEntry(new ZipEntry(entryName));
                Files.copy(file, zipOutputStream);
                zipOutputStream.closeEntry();

                long size = Files.size(file);
                totalBytes += size;
                JsonObject operation = new JsonObject();
                operation.addProperty("operation", "replace");
                operation.addProperty("path", relativePath);
                operation.addProperty("sha256", sha256(file));
                operations.add(operation);
            }

            JsonObject manifest = new JsonObject();
            manifest.addProperty("author", request.author().trim());
            manifest.addProperty("serial", request.serial().trim());
            manifest.addProperty("id", packageId);
            manifest.addProperty("packageIdentity", identity.packageSuffix());
            manifest.addProperty("packageAuthor", identity.decryptedAuthor());
            manifest.addProperty("displayName", blankFallback(request.displayName(), packageId));
            manifest.addProperty("packageVersion", blankFallback(request.packageVersion(), "1.0.0"));
            manifest.addProperty("targetKoilVersion", blankFallback(request.targetKoilVersion(), Main.version()));
            manifest.addProperty("authorId", blankFallback(request.authorId(), ""));
            manifest.add("operations", operations);

            zipOutputStream.putNextEntry(new ZipEntry(root + "package.json"));
            zipOutputStream.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }

        String authIssue = KoilPackageManager.authConfigurationIssue();
        Files.move(tempPackageFile, packageFile, StandardCopyOption.REPLACE_EXISTING);
        if (authIssue.isBlank()) {
            KoilPackageManager.PendingPackage preview = KoilPackageManager.inspectPackage(packageFile.toFile());
            if (!preview.valid()) {
                PKG_SUBLOGGER.logW("Package Builder", "Created package with local auth warning: " + preview.status());
            }
        } else {
            PKG_SUBLOGGER.logW("Package Builder", "Created package without local auth verification: " + authIssue);
        }
        PKG_SUBLOGGER.logI("Package Builder", "Created package: " + packageFile + " files=" + files.size());
        return new BuildResult(packageFile.toFile(), files.size(), totalBytes, packageId);
    }

    private static List<Path> collectSelectedFiles(Path gameRoot, List<String> selectedRelativePaths) throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        if (selectedRelativePaths == null) {
            return List.of();
        }
        for (String selectedPath : selectedRelativePaths) {
            String normalized = normalizeRelativePath(selectedPath);
            if (normalized.isBlank() || shouldSkipOutputPath(normalized)) {
                continue;
            }
            Path target = gameRoot.resolve(normalized).normalize();
            if (!target.startsWith(gameRoot) || !Files.exists(target)) {
                continue;
            }
            if (Files.isDirectory(target)) {
                try (var stream = Files.walk(target)) {
                    stream.filter(Files::isRegularFile).forEach(files::add);
                }
            } else if (Files.isRegularFile(target)) {
                files.add(target);
            }
        }
        List<Path> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(path -> normalizeRelativePath(gameRoot.relativize(path).toString())));
        return sorted;
    }

    private static void ensureZipDirectories(ZipOutputStream zipOutputStream, String root, String relativePath) throws IOException {
        String[] parts = relativePath.split("/");
        StringBuilder current = new StringBuilder(root);
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].isBlank()) {
                continue;
            }
            current.append(parts[i]).append('/');
            ZipEntry directoryEntry = new ZipEntry(current.toString());
            try {
                zipOutputStream.putNextEntry(directoryEntry);
                zipOutputStream.closeEntry();
            } catch (java.util.zip.ZipException ignored) {
                // Directory was already added for another file.
            }
        }
    }

    private static String normalizeRelativePath(String path) {
        String normalized = path == null ? "" : path.replace("\\", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean shouldSkipOutputPath(String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        return normalized.isBlank()
                || normalized.equals("package.json")
                || normalized.startsWith("koil/packages/built/")
                || normalized.startsWith("__MACOSX/")
                || normalized.contains("/._")
                || normalized.startsWith("._")
                || isKoilTempFileName(lastPathName(normalized));
    }

    private static boolean isKoilTempFileName(String fileName) {
        String normalizedName = fileName == null ? "" : fileName
                .toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return normalizedName.equals("koil temp file")
                || normalizedName.startsWith("koil temp file.");
    }

    private static String lastPathName(String path) {
        int index = path == null ? -1 : path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    private static String blankFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream inputStream = Files.newInputStream(file);
                 DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest)) {
                byte[] buffer = new byte[8192];
                while (digestInputStream.read(buffer) != -1) {
                    // Consume stream.
                }
            }
            byte[] hash = digest.digest();
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 unavailable", exception);
        }
    }
}
