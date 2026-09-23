package com.spirit.koil.api.model.install;

import com.spirit.koil.api.model.LocalModelRuntimeLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the pinned Colibri engines with the host C toolchain (the upstream
 * `Makefile` already degrades OpenMP gracefully) and stages the same flat
 * layout the signed release archives publish: the `coli` launcher plus one
 * native engine binary per supported model family.
 */
public final class ColibriSourceBuildPlan implements RuntimeSourceBuildPlan {
    private static final String[] ENGINE_BINARIES = {
            "colibri", "glm53", "inkling", "kimi_k3", "olmoe", "qwen36", "qwen38", "deepseek_v4"
    };
    private static final String[] PYTHON_MODULES = {
            "autotune.py", "cluster.py", "doctor.py", "family_registry.py",
            "openai_server.py", "resource_plan.py", "v4_dsml.py", "version.py"
    };
    private static final String[] TOOL_MODULES = {
            "mirror_plan.py", "eval_glm.py", "qwen38_image.py", "fetch_benchmarks.py",
            "k3_tokenizer.py", "glm53_image.py", "convert_fp8_to_int4.py"
    };
    private static final long BUILD_TIMEOUT_MS = 45L * 60L * 1000L;

    @Override
    public String runtimeId() {
        return ManagedRuntimeCatalog.COLIBRI_RUNTIME_ID;
    }

    @Override
    public List<String> requiredTools() {
        return List.of("make", "cc/clang", "python3");
    }

    @Override
    public String missingToolchain() {
        List<String> missing = new ArrayList<>();
        if (probe("make", "--version") == null) {
            missing.add("make");
        }
        if (probe("cc", "-dumpmachine") == null && probe("clang", "--version") == null && probe("gcc", "--version") == null) {
            missing.add("cc/clang");
        }
        if (probe("python3", "--version") == null) {
            missing.add("python3");
        }
        return missing.isEmpty() ? "" : "missing build tools: " + String.join(", ", missing);
    }

    @Override
    public Path build(
            Path sourceRoot,
            Path installRoot,
            ManagedRuntimeInstaller.ProgressListener progress,
            ManagedRuntimeInstaller.CancelSignal cancel
    ) throws IOException, InterruptedException, ManagedRuntimeInstaller.CancelledException {
        Path cDirectory = sourceRoot.resolve("c");
        if (!Files.isRegularFile(cDirectory.resolve("Makefile"))) {
            throw new IOException("Colibri source tree is missing c/Makefile");
        }
        int jobs = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        List<String> command = new ArrayList<>();
        command.add("make");
        command.add("-C");
        command.add(cDirectory.toAbsolutePath().normalize().toString());
        command.add("-j" + jobs);
        for (String engine : ENGINE_BINARIES) {
            command.add(engine);
        }
        progress.progress("building", "Compiling Colibri engines from the pinned source (" + jobs + " jobs)", "", -1L, -1L);
        LocalModelRuntimeLog.write("colibri_build", "starting managed source build: " + command);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder tail = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (tail.length() > 8192) {
                        tail.delete(0, tail.length() - 4096);
                    }
                    tail.append(line).append('\n');
                }
            } catch (IOException ignored) {
            }
        }, "koil-colibri-build-log");
        reader.setDaemon(true);
        reader.start();
        long startMillis = System.currentTimeMillis();
        long started = System.nanoTime();
        while (true) {
            if (cancel.cancelled()) {
                process.destroyForcibly();
                throw new ManagedRuntimeInstaller.CancelledException();
            }
            if (System.currentTimeMillis() - startMillis > BUILD_TIMEOUT_MS) {
                process.destroyForcibly();
                throw new IOException("Colibri source build exceeded the bounded build window");
            }
            boolean finished = process.waitFor(500L, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (finished) {
                int code = process.exitValue();
                if (code != 0) {
                    throw new IOException("Colibri source build failed with exit " + code + ": " + tail.toString().trim());
                }
                break;
            }
        }
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        LocalModelRuntimeLog.write("colibri_build", "source build finished in " + seconds + "s");

        // Stage the release layout: launcher, engine binaries, control plane, tools.
        copyExecutable(cDirectory.resolve("coli"), installRoot.resolve("coli"));
        List<String> built = new ArrayList<>();
        for (String engine : ENGINE_BINARIES) {
            Path binary = cDirectory.resolve(engine);
            if (Files.isRegularFile(binary)) {
                copyExecutable(binary, installRoot.resolve(engine));
                built.add(engine);
            } else if ("deepseek_v4".equals(engine)) {
                LocalModelRuntimeLog.write("colibri_build", "deepseek_v4 target not produced; engine optional on this platform");
            } else {
                throw new IOException("Colibri build did not produce the " + engine + " engine");
            }
        }
        for (String module : PYTHON_MODULES) {
            copyRegular(cDirectory.resolve(module), installRoot.resolve(module));
        }
        Path toolsTarget = installRoot.resolve("tools");
        Files.createDirectories(toolsTarget);
        for (String module : TOOL_MODULES) {
            copyRegular(cDirectory.resolve("tools").resolve(module), toolsTarget.resolve(module));
        }
        copyRegular(cDirectory.resolve("..").normalize().resolve("LICENSE"), installRoot.resolve("LICENSE"));
        LocalModelRuntimeLog.write("colibri_build", "staged engines: " + String.join(", ", built));
        return installRoot.resolve("coli");
    }

    private static void copyExecutable(Path source, Path target) throws IOException {
        copyRegular(source, target);
        try {
            Set<PosixFilePermission> permissions = EnumSet.copyOf(Files.getPosixFilePermissions(target));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(target, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            target.toFile().setExecutable(true, true);
        }
    }

    private static void copyRegular(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("expected file missing from Colibri source tree: " + source.getFileName());
        }
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String probe(String command, String argument) {
        try {
            Process process = new ProcessBuilder(command, argument).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 ? output : null;
        } catch (Exception exception) {
            return null;
        }
    }
}
