package com.spirit.koil.api.model.tool;

import com.spirit.koil.api.util.file.KoilInstancePaths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves and, when allowed, provisions the PinchTab browser control-plane binary used by Browser Intelligence.
 *
 * <p>The managed runtime is deliberately pinned instead of downloading an unversioned "latest" binary. The
 * matching upstream checksums.txt is fetched from the same immutable release tag and the executable is not
 * published into Koil's runtime directory until its SHA-256 matches.</p>
 */
final class PinchTabManagedRuntime {
    static final String VERSION = "0.15.0";
    private static final String TAG = "v" + VERSION;
    private static final String RELEASE_ROOT = "https://github.com/pinchtab/pinchtab/releases/download/" + TAG + "/";
    private static final int MAX_BINARY_BYTES = 96 * 1024 * 1024;
    private static final int MAX_CHECKSUM_BYTES = 256 * 1024;
    private static final Object INSTALL_LOCK = new Object();

    private PinchTabManagedRuntime() {
    }

    static Path resolveOrInstall() throws IOException {
        String explicit = explicitBinary();
        if (!explicit.isBlank()) {
            Path configured = Path.of(explicit).toAbsolutePath().normalize();
            if (!launchable(configured)) {
                throw new IOException("Configured PinchTab executable does not exist or is not launchable: " + configured);
            }
            return configured;
        }

        Path discovered = discoverExisting();
        if (discovered != null) return discovered;

        if (!Boolean.parseBoolean(System.getProperty("koil.pinchtab.autoinstall", "true"))) {
            throw new IOException("PinchTab is not installed and automatic Browser Intelligence runtime installation is disabled. "
                    + "Set -Dkoil.pinchtab.autoinstall=true or configure -Dkoil.pinchtab.binary=/absolute/path/to/pinchtab.");
        }

        synchronized (INSTALL_LOCK) {
            discovered = discoverExisting();
            if (discovered != null) return discovered;
            return installManaged();
        }
    }

    static Path managedInstallRoot() {
        return KoilInstancePaths.modelRoot().resolve("browser/runtime/pinchtab/" + TAG).toAbsolutePath().normalize();
    }

    private static Path discoverExisting() {
        Path managed = managedInstallRoot().resolve(assetName());
        if (launchable(managed)) return managed;

        String home = System.getProperty("user.home", "").strip();
        if (!home.isBlank()) {
            Path h = Path.of(home).toAbsolutePath().normalize();
            Path npmVersioned = h.resolve(".pinchtab/bin/" + VERSION + "/" + assetName());
            if (launchable(npmVersioned)) return npmVersioned;

            Path npmVersionedTag = h.resolve(".pinchtab/bin/" + TAG + "/" + assetName());
            if (launchable(npmVersionedTag)) return npmVersionedTag;

            for (String relative : new String[]{
                    ".local/bin/pinchtab",
                    "bin/pinchtab",
                    ".local/bin/pinchtab.exe",
                    "bin/pinchtab.exe"
            }) {
                Path candidate = h.resolve(relative);
                if (launchable(candidate)) return candidate.toAbsolutePath().normalize();
            }
        }

        for (String absolute : new String[]{
                "/usr/local/bin/pinchtab",
                "/usr/bin/pinchtab",
                "/opt/homebrew/bin/pinchtab",
                "/opt/local/bin/pinchtab"
        }) {
            Path candidate = Path.of(absolute);
            if (launchable(candidate)) return candidate.toAbsolutePath().normalize();
        }
        return null;
    }

    private static Path installManaged() throws IOException {
        String asset = assetName();
        Path root = managedInstallRoot();
        Path destination = root.resolve(asset).normalize();
        if (!destination.startsWith(root)) throw new IOException("Invalid managed PinchTab runtime path.");
        Files.createDirectories(root);

        byte[] checksums = download(RELEASE_ROOT + "checksums.txt", MAX_CHECKSUM_BYTES);
        String expected = checksumFor(new String(checksums, StandardCharsets.UTF_8), asset);
        if (expected.isBlank()) {
            throw new IOException("PinchTab " + TAG + " checksum manifest does not contain the expected asset " + asset + ".");
        }

        Path partial = root.resolve(asset + ".part");
        Files.deleteIfExists(partial);
        byte[] binary = download(RELEASE_ROOT + asset, MAX_BINARY_BYTES);
        String actual = sha256(binary);
        if (!expected.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(partial);
            throw new IOException("PinchTab " + TAG + " SHA-256 verification failed for " + asset
                    + ": expected " + expected + " but downloaded " + actual + ".");
        }

        Files.write(partial, binary);
        makeExecutable(partial);
        try {
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        makeExecutable(destination);

        if (!launchable(destination)) {
            Files.deleteIfExists(destination);
            throw new IOException("Managed PinchTab runtime was downloaded and verified but is not launchable: " + destination);
        }
        return destination;
    }

    private static byte[] download(String url, int maximum) throws IOException {
        URI current = URI.create(url);
        for (int redirects = 0; redirects < 8; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
            try {
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(60_000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/octet-stream,text/plain,*/*");
                connection.setRequestProperty("User-Agent", "Koil-Browser-Intelligence/PinchTab-Runtime");
                connection.setRequestProperty("Connection", "close");
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("PinchTab runtime download redirected without a Location header (HTTP " + status + ").");
                    }
                    current = current.resolve(location);
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("PinchTab runtime download failed with HTTP " + status + " for " + current);
                }
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16 * 1024];
                    int total = 0;
                    for (int read; (read = in.read(buffer)) >= 0;) {
                        if (read == 0) continue;
                        total += read;
                        if (total > maximum) throw new IOException("PinchTab runtime download exceeded the configured size bound.");
                        out.write(buffer, 0, read);
                    }
                    return out.toByteArray();
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("PinchTab runtime download exceeded the redirect limit.");
    }

    private static String checksumFor(String manifest, String asset) {
        for (String line : manifest.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isBlank()) continue;
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length != 2) continue;
            String name = parts[1].strip();
            if (name.startsWith("*")) name = name.substring(1);
            if (asset.equals(name)) return parts[0].strip();
        }
        return "";
    }

    private static String assetName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String cpu;
        if (arch.equals("x86_64") || arch.equals("amd64") || arch.equals("x64")) cpu = "amd64";
        else if (arch.equals("aarch64") || arch.equals("arm64")) cpu = "arm64";
        else throw new IllegalStateException("PinchTab does not provide a managed Koil binary for CPU architecture: " + arch);

        if (os.contains("linux")) return "pinchtab-linux-" + cpu;
        if (os.contains("mac") || os.contains("darwin")) return "pinchtab-darwin-" + cpu;
        if (os.contains("win")) return "pinchtab-windows-" + cpu + ".exe";
        throw new IllegalStateException("PinchTab does not provide a managed Koil binary for operating system: " + os);
    }

    private static String explicitBinary() {
        String property = System.getProperty("koil.pinchtab.binary", "").strip();
        if (!property.isBlank()) return property;
        String environment = System.getenv("PINCHTAB_BIN");
        return environment == null ? "" : environment.strip();
    }

    private static boolean launchable(Path path) {
        if (path == null || !Files.isRegularFile(path)) return false;
        return isWindows() || Files.isExecutable(path);
    }

    private static void makeExecutable(Path path) throws IOException {
        if (isWindows()) return;
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            EnumSet<PosixFilePermission> updated = permissions.isEmpty()
                    ? EnumSet.noneOf(PosixFilePermission.class)
                    : EnumSet.copyOf(permissions);
            updated.add(PosixFilePermission.OWNER_READ);
            updated.add(PosixFilePermission.OWNER_WRITE);
            updated.add(PosixFilePermission.OWNER_EXECUTE);
            updated.add(PosixFilePermission.GROUP_READ);
            updated.add(PosixFilePermission.GROUP_EXECUTE);
            updated.add(PosixFilePermission.OTHERS_READ);
            updated.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, updated);
        } catch (UnsupportedOperationException ignored) {
            if (!path.toFile().setExecutable(true, false)) {
                throw new IOException("Could not mark managed PinchTab runtime executable: " + path);
            }
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) out.append(String.format("%02x", value));
            return out.toString();
        } catch (Exception failure) {
            throw new IOException("SHA-256 is unavailable while verifying the PinchTab runtime.", failure);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
