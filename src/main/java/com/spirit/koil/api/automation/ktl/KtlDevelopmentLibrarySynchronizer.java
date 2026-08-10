package com.spirit.koil.api.automation.ktl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Development-only, conflict-aware synchronization between packaged KTL
 * resources and the editable run instance. Release instances never discover a
 * project root and continue to use the missing-only built-in installer.
 */
public final class KtlDevelopmentLibrarySynchronizer {
    private static final String SOURCE_RELATIVE = "src/main/resources/koil/automation";
    private static final String RUN_RELATIVE = "run/koil/automation";
    private static final String STATE_RELATIVE = ".gradle/koil/ktl-sync.properties";
    private static final String LOCK_RELATIVE = ".gradle/koil/ktl-sync.lock";
    private static final Set<String> RUNTIME_OWNED_PREFIXES = Set.of(
            "failure_types/", "feedback/", "improvements/", "language/", "validation/"
    );

    private KtlDevelopmentLibrarySynchronizer() {
    }

    public static void main(String[] args) {
        Path projectRoot = args.length == 0 ? Path.of("") : Path.of(args[0]);
        SyncResult result = sync(projectRoot);
        System.out.println("KTL development sync: copied=" + result.copied()
                + " files=" + result.files() + " source=" + result.sourceRoot()
                + " run=" + result.runRoot());
    }

    public static SyncResult syncIfDevelopmentInstance(Path activeRoot) {
        Path projectRoot = findProjectRoot(activeRoot);
        return projectRoot == null ? SyncResult.inactive() : sync(projectRoot);
    }

    public static SyncResult sync(Path projectRoot) {
        Path root = projectRoot == null ? Path.of("") : projectRoot.toAbsolutePath().normalize();
        Path sourceRoot = root.resolve(SOURCE_RELATIVE).normalize();
        Path runRoot = root.resolve(RUN_RELATIVE).normalize();
        if (!isKoilProject(root, sourceRoot)) {
            return SyncResult.inactive();
        }
        Path statePath = root.resolve(STATE_RELATIVE);
        Path lockPath = root.resolve(LOCK_RELATIVE);
        try {
            Files.createDirectories(runRoot);
            Files.createDirectories(lockPath.getParent());
            try (FileChannel channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            ); FileLock ignored = channel.lock()) {
                return synchronizeLocked(sourceRoot, runRoot, statePath);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("KTL development sync failed: " + exception.getMessage(), exception);
        }
    }

    private static SyncResult synchronizeLocked(Path sourceRoot, Path runRoot, Path statePath) throws IOException {
        Properties baseline = load(statePath);
        Set<String> relatives = new LinkedHashSet<>();
        relatives.addAll(listKtl(sourceRoot));
        relatives.addAll(listKtl(runRoot));
        List<String> ordered = relatives.stream().sorted().toList();
        List<String> conflicts = new ArrayList<>();
        int copied = 0;

        for (String relative : ordered) {
            Path source = sourceRoot.resolve(relative);
            Path run = runRoot.resolve(relative);
            boolean sourceExists = Files.isRegularFile(source);
            boolean runExists = Files.isRegularFile(run);
            if (sourceExists && !runExists) {
                copyAtomic(source, run);
                copied++;
            } else if (!sourceExists && runExists) {
                copyAtomic(run, source);
                copied++;
            } else if (sourceExists) {
                String sourceHash = hash(source);
                String runHash = hash(run);
                if (!sourceHash.equals(runHash)) {
                    String previous = baseline.getProperty(relative, "");
                    if (previous.isBlank()) {
                        // A legacy run tree may be stale despite a newer file
                        // timestamp. The packaged source is authoritative for
                        // the first baseline and cannot be back-propagated.
                        copyAtomic(source, run);
                        copied++;
                    } else if (previous.equals(sourceHash) && !previous.equals(runHash)) {
                        copyAtomic(run, source);
                        copied++;
                    } else if (previous.equals(runHash) && !previous.equals(sourceHash)) {
                        copyAtomic(source, run);
                        copied++;
                    } else {
                        conflicts.add(relative);
                    }
                }
            }
        }

        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("KTL files changed on both sides since the last sync: "
                    + String.join(", ", conflicts)
                    + ". Reconcile them explicitly; neither copy was overwritten.");
        }

        List<String> manifestEntries = listKtl(sourceRoot);
        String manifest = String.join(System.lineSeparator(), manifestEntries) + System.lineSeparator();
        copied += writeIfChanged(sourceRoot.resolve("manifest.txt"), manifest);
        copied += writeIfChanged(runRoot.resolve("manifest.txt"), manifest);

        Properties next = new Properties();
        for (String relative : manifestEntries) {
            Path source = sourceRoot.resolve(relative);
            Path run = runRoot.resolve(relative);
            if (!Files.isRegularFile(run) || !hash(source).equals(hash(run))) {
                throw new IllegalStateException("KTL sync invariant failed for " + relative);
            }
            next.setProperty(relative, hash(source));
        }
        store(statePath, next);
        return new SyncResult(true, copied, manifestEntries.size(), sourceRoot, runRoot);
    }

    private static Path findProjectRoot(Path activeRoot) {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (Path candidate : List.of(cwd, cwd.getParent())) {
            if (candidate != null && isKoilProject(candidate, candidate.resolve(SOURCE_RELATIVE))) {
                Path expectedRun = candidate.resolve(RUN_RELATIVE).normalize();
                if (activeRoot == null || activeRoot.toAbsolutePath().normalize().equals(expectedRun)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean isKoilProject(Path root, Path sourceRoot) {
        return root != null
                && Files.isDirectory(root.resolve(".git"))
                && Files.isRegularFile(root.resolve("build.gradle"))
                && Files.isDirectory(sourceRoot);
    }

    private static List<String> listKtl(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().endsWith(".ktl"))
                    .map(root::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .filter(relative -> RUNTIME_OWNED_PREFIXES.stream().noneMatch(relative::startsWith))
                    .sorted()
                    .toList();
        }
    }

    private static int writeIfChanged(Path target, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (Files.isRegularFile(target) && java.util.Arrays.equals(Files.readAllBytes(target), bytes)) {
            return 0;
        }
        writeAtomic(target, bytes);
        return 1;
    }

    private static void copyAtomic(Path source, Path target) throws IOException {
        writeAtomic(target, Files.readAllBytes(source));
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".syncing");
        Files.write(temporary, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailure) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Properties load(Path statePath) throws IOException {
        Properties properties = new Properties();
        if (Files.isRegularFile(statePath)) {
            try (InputStream input = Files.newInputStream(statePath)) {
                properties.load(input);
            }
        }
        return properties;
    }

    private static void store(Path statePath, Properties properties) throws IOException {
        Files.createDirectories(statePath.getParent());
        Path temporary = statePath.resolveSibling(statePath.getFileName() + ".syncing");
        try (OutputStream output = Files.newOutputStream(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            properties.store(output, "Koil KTL development sync hashes");
        }
        try {
            Files.move(temporary, statePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailure) {
            Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String hash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(path);
            StringBuilder value = new StringBuilder();
            for (byte item : digest.digest(bytes)) value.append(String.format("%02x", item));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record SyncResult(boolean active, int copied, int files, Path sourceRoot, Path runRoot) {
        private static SyncResult inactive() {
            return new SyncResult(false, 0, 0, null, null);
        }
    }
}
