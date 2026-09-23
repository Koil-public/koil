package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactFormat;
import com.spirit.koil.api.model.catalog.ModelArtifactInspection;
import com.spirit.koil.api.model.catalog.ModelArtifactInspector;

import java.nio.file.Path;

/** Entry point for architecture-neutral artifact profiling. */
public final class KoilModelProfiler {
    private KoilModelProfiler() {
    }

    public static KoilModelProfile inspect(Path source) {
        return profile(ModelArtifactInspector.inspect(source));
    }

    public static KoilModelProfile inspect(Path source, ModelArtifactFormat format) {
        return profile(ModelArtifactInspector.inspect(source, format));
    }

    public static KoilModelProfile profile(ModelArtifactInspection inspection) {
        ModelArtifactInspection safe = inspection == null
                ? ModelArtifactInspection.unavailable(ModelArtifactFormat.UNKNOWN, "artifact inspection unavailable")
                : inspection;
        KoilArchitectureDescriptor architecture = KoilArchitectureRegistry.defaultRegistry().identify(safe);
        return new KoilModelProfile(
                safe.present(), safe.format(), safe.modelName(), safe.architectureId(), architecture,
                safe.tokenizerFamily(), safe.contextTokens(), safe.tensorCount(), safe.expertCount(),
                safe.activeExpertCount(), safe.declaredCapabilities(), safe.metadata(), safe.evidence()
        );
    }
}
