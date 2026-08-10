package com.spirit.koil.api.model.tool;

import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
                runRoot.resolve("koil/automation").toAbsolutePath().normalize(),
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
        Files.createDirectories(root);
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

    private static Path runDirectory() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.runDirectory != null) {
            return client.runDirectory.toPath().toAbsolutePath().normalize();
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private static Path developmentProjectRoot(Path runRoot) {
        if (runRoot == null || runRoot.getFileName() == null
                || !"run".equalsIgnoreCase(runRoot.getFileName().toString())) {
            return null;
        }
        Path parent = runRoot.getParent();
        if (parent == null
                || !Files.isDirectory(parent.resolve("src/main/java"))
                || !Files.exists(parent.resolve("build.gradle"))
                && !Files.exists(parent.resolve("build.gradle.kts"))) {
            return null;
        }
        return parent.toAbsolutePath().normalize();
    }

    public record Workspace(String id, Path root, boolean writable, String description) {
    }

    public record ResolvedPath(Workspace workspace, Path path, String relativePath) {
    }
}
