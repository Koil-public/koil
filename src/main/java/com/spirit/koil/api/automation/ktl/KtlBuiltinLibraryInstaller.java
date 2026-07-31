package com.spirit.koil.api.automation.ktl;

import com.spirit.koil.api.automation.AutomationReporter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Installs Koil's editable built-in KTL library into a fresh instance.
 *
 * Existing files are never overwritten. This keeps user-authored skill edits
 * local while making the same v2 task library available outside the developer
 * run directory.
 */
public final class KtlBuiltinLibraryInstaller {
    private static final String RESOURCE_ROOT = "/koil/automation/";
    private static final String MANIFEST = RESOURCE_ROOT + "manifest.txt";

    private KtlBuiltinLibraryInstaller() {
    }

    public static int installMissing(Path targetRoot) {
        if (targetRoot == null) {
            return 0;
        }
        try (InputStream manifestStream = KtlBuiltinLibraryInstaller.class.getResourceAsStream(MANIFEST)) {
            if (manifestStream == null) {
                return 0;
            }
            List<String> paths = new String(manifestStream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::trim)
                    .filter(path -> !path.isBlank())
                    .filter(KtlBuiltinLibraryInstaller::isSafeRelativePath)
                    .toList();
            int installed = 0;
            for (String relative : paths) {
                Path target = targetRoot.resolve(relative).normalize();
                if (!target.startsWith(targetRoot.normalize()) || Files.exists(target)) {
                    continue;
                }
                try (InputStream source = KtlBuiltinLibraryInstaller.class.getResourceAsStream(RESOURCE_ROOT + relative)) {
                    if (source == null) {
                        AutomationReporter.block("[block]", "missing built-in KTL resource " + relative);
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    Path temporary = target.resolveSibling(target.getFileName() + ".installing");
                    Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException atomicMoveFailure) {
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    installed++;
                }
            }
            if (installed > 0) {
                AutomationReporter.cache("[cache]", "installed built-in KTL files=" + installed);
            }
            return installed;
        } catch (IOException exception) {
            AutomationReporter.block("[block]", "built-in KTL install failed: " + exception.getMessage());
            return 0;
        }
    }

    private static boolean isSafeRelativePath(String value) {
        if (value.startsWith("/") || value.startsWith("\\") || value.contains("..")) {
            return false;
        }
        return value.endsWith(".ktl");
    }
}
