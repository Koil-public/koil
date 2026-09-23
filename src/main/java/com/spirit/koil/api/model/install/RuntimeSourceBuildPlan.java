package com.spirit.koil.api.model.install;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * One runtime's truthful source-build procedure. A plan must refuse loudly when
 * its toolchain is missing; Koil never claims an unbuilt runtime is installed.
 */
public interface RuntimeSourceBuildPlan {
    String runtimeId();

    /** Tools the build requires, for truthful diagnostics (e.g. make, clang, python3). */
    List<String> requiredTools();

    /** Returns a missing-tool description, or an empty string when the toolchain is usable. */
    String missingToolchain();

    /**
     * Build inside {@code sourceRoot} (verified extracted artifact) and copy the runnable
     * result into {@code installRoot}. Returns the primary executable inside installRoot.
     */
    Path build(
            Path sourceRoot,
            Path installRoot,
            ManagedRuntimeInstaller.ProgressListener progress,
            ManagedRuntimeInstaller.CancelSignal cancel
    ) throws IOException, InterruptedException, ManagedRuntimeInstaller.CancelledException;
}
