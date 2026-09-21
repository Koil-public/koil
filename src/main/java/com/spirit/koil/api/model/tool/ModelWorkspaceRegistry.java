package com.spirit.koil.api.model.tool;

import com.spirit.koil.api.util.file.KoilInstancePaths;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Named, bounded filesystem roots exposed to registered local-model tools.
 * Request payloads never select an arbitrary absolute path.
 */
public final class ModelWorkspaceRegistry {
    private ModelWorkspaceRegistry() {
    }

    public static Map<String, Workspace> workspaces() {
        Path runRoot = runDirectory();
        Map<String, Workspace> roots = new LinkedHashMap<>();
        roots.put("instance", new Workspace(
                "instance",
                runRoot.toAbsolutePath().normalize(),
                true,
                "Minecraft instance root (the separate koil/ child contains Koil-owned data)"
        ));
        roots.put("automation", new Workspace(
                "automation",
                KoilInstancePaths.automationRoot(),
                true,
                "Active KTL automation files for this instance"
        ));
        roots.put("koil", new Workspace(
                "koil",
                runRoot.resolve("koil").toAbsolutePath().normalize(),
                true,
                "Koil-owned data directory inside the Minecraft instance (not the default workspace root)"
        ));
        Path project = developmentProjectRoot(runRoot);
        if (project != null) {
            roots.put("project", new Workspace(
                    "project",
                    project,
                    true,
                    "Koil development source workspace"
            ));
        }
        return Map.copyOf(roots);
    }

    public static ResolvedPath resolve(String workspaceId, String relativePath, boolean forWrite) throws IOException {
        return resolveInternal(workspaceId, relativePath, forWrite, true);
    }

    /**
     * Side-effect-free path resolution for tool preflight. Unlike {@link #resolve},
     * this method never creates a missing workspace root.
     */
    public static ResolvedPath inspect(String workspaceId, String relativePath, boolean forWrite) throws IOException {
        return resolveInternal(workspaceId, relativePath, forWrite, false);
    }

    private static ResolvedPath resolveInternal(
            String workspaceId,
            String relativePath,
            boolean forWrite,
            boolean createWorkspaceRoot
    ) throws IOException {
        Map<String, Workspace> available = workspaces();
        Workspace workspace = available.get(canonicalWorkspaceId(workspaceId, available));
        if (workspace == null) {
            throw new IOException("Unknown workspace '" + cleanId(workspaceId)
                    + "'. Available named roots: " + String.join(", ", available.keySet()) + ".");
        }
        if (forWrite && !workspace.writable()) {
            throw new IOException("Workspace '" + workspace.id() + "' is read-only.");
        }
        String value = relativePath == null ? "" : relativePath.strip().replace('\\', '/');
        if (value.indexOf('\0') >= 0 || value.contains("\n") || value.contains("\r")) {
            throw new IOException("Path contains unsupported control characters.");
        }
        Path relative;
        try {
            relative = value.isBlank() ? Path.of("") : Path.of(value);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid workspace path.", invalid);
        }
        if (relative.isAbsolute()) {
            throw new IOException("Absolute paths are not accepted; choose a named workspace and relative path.");
        }
        Path root = workspace.root().toAbsolutePath().normalize();
        if (createWorkspaceRoot) {
            Files.createDirectories(root);
        } else if (!Files.isDirectory(root)) {
            throw new IOException("Workspace root is unavailable: " + workspace.id());
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Path escapes workspace '" + workspace.id() + "'.");
        }
        rejectSensitive(relative);
        verifyExistingPath(root, target);
        return new ResolvedPath(workspace, target, root.relativize(target).toString().replace('\\', '/'));
    }

    private static void verifyExistingPath(Path root, Path target) throws IOException {
        Path realRoot = root.toRealPath();
        if (Files.exists(target)) {
            if (!target.toRealPath().startsWith(realRoot)) {
                throw new IOException("Path resolves outside its workspace.");
            }
            return;
        }
        Path parent = target.getParent();
        while (parent != null && !Files.exists(parent)) {
            parent = parent.getParent();
        }
        if (parent == null || !parent.toRealPath().startsWith(realRoot)) {
            throw new IOException("Path parent resolves outside its workspace.");
        }
    }

    private static void rejectSensitive(Path relative) throws IOException {
        for (Path part : relative) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.equals(".git")
                    || name.equals(".gradle")
                    || name.equals(".env")
                    || name.equals("servers.dat")
                    || name.endsWith(".pem")
                    || name.endsWith(".p12")
                    || name.endsWith(".key")
                    || name.contains("credential")
                    || name.contains("password")
                    || name.contains("secret")) {
                throw new IOException("That path is excluded from model workspaces.");
            }
        }
        String normalized = relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.endsWith("koil/sys/model/colibri.json")
                || normalized.endsWith("sys/model/colibri.json")) {
            throw new IOException("Runtime authentication configuration is not exposed to model tools.");
        }
    }

    private static String cleanId(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    static String canonicalWorkspaceId(String value, Map<String, Workspace> available) {
        String id = cleanId(value);
        if (id.isBlank() || id.equals("default") || id.equals("current") || id.equals("workspace")
                || id.equals("root") || id.equals("instance_root")) {
            return "instance";
        }
        if (id.equals("repo") || id.equals("repository") || id.equals("source") || id.equals("code")) {
            return available.containsKey("project") ? "project" : id;
        }
        if (id.equals("ktl") || id.equals("automation_files")) {
            return "automation";
        }
        return id;
    }

    /** Code investigation defaults to the development source root when it is available. */
    public static String preferredCodeWorkspaceId(String value, Map<String, Workspace> available) {
        String requested = cleanId(value);
        if ((requested.isBlank() || requested.equals("default") || requested.equals("current")
                || requested.equals("workspace") || requested.equals("root") || requested.equals("instance_root"))
                && available != null && available.containsKey("project")) {
            return "project";
        }
        return canonicalWorkspaceId(requested, available == null ? Map.of() : available);
    }

    private static Path runDirectory() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.runDirectory != null) {
            return client.runDirectory.toPath().toAbsolutePath().normalize();
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    /**
     * Resolve the live Koil development checkout without assuming Loom's run
     * directory is literally {@code <project>/run}. Launchers, IDEs, custom
     * runDir settings, and Gradle invocations can all place Minecraft
     * elsewhere. The lookup is deliberately bounded and only accepts a root
     * that looks like Koil source, so an unrelated parent Gradle project is
     * never exposed as the model's source workspace.
     */
    private static Path developmentProjectRoot(Path runRoot) {
        LinkedHashSet<Path> starts = new LinkedHashSet<>();
        addCandidate(starts, runRoot);
        addCandidate(starts, systemPath("user.dir"));
        addCandidate(starts, codeLocation());

        // In a development environment Fabric's Koil metadata normally lives
        // under src/main/resources or build/resources/main. That gives us one
        // more deterministic anchor when the Minecraft run directory was
        // customized. This remains best-effort and never affects installed
        // production jars.
        try {
            FabricLoader.getInstance().getModContainer("koil")
                    .flatMap(container -> container.findPath("fabric.mod.json"))
                    .ifPresent(path -> addCandidate(starts, path));
        } catch (RuntimeException ignored) {
            // Workspace discovery must never make model startup depend on the
            // Fabric loader lifecycle being fully initialized.
        }

        for (Path start : starts) {
            Path root = findKoilProjectAncestor(start, 10);
            if (root != null) return root;
        }
        return null;
    }

    private static void addCandidate(LinkedHashSet<Path> candidates, Path path) {
        if (candidates == null || path == null) return;
        try {
            Path normalized = path.toAbsolutePath().normalize();
            candidates.add(Files.isRegularFile(normalized) ? normalized.getParent() : normalized);
        } catch (RuntimeException ignored) {
        }
    }

    private static Path systemPath(String property) {
        try {
            String value = System.getProperty(property, "").strip();
            return value.isBlank() ? null : Path.of(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Path codeLocation() {
        try {
            var domain = ModelWorkspaceRegistry.class.getProtectionDomain();
            var source = domain == null ? null : domain.getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            return Path.of(source.getLocation().toURI());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path findKoilProjectAncestor(Path start, int maximumDepth) {
        Path current = start;
        for (int depth = 0; current != null && depth <= maximumDepth; depth++, current = current.getParent()) {
            if (looksLikeKoilProject(current)) {
                return current.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static boolean looksLikeKoilProject(Path root) {
        if (root == null || !Files.isDirectory(root.resolve("src/main/java"))) return false;
        boolean build = Files.exists(root.resolve("build.gradle"))
                || Files.exists(root.resolve("build.gradle.kts"));
        if (!build) return false;

        // The source package is the strongest stable marker available in the
        // checkout. Also accept the Fabric metadata in case source packages are
        // temporarily being refactored.
        return Files.isDirectory(root.resolve("src/main/java/com/spirit"))
                || Files.exists(root.resolve("src/main/resources/fabric.mod.json"));
    }

    public record Workspace(String id, Path root, boolean writable, String description) {
    }

    public record ResolvedPath(Workspace workspace, Path path, String relativePath) {
    }
}
