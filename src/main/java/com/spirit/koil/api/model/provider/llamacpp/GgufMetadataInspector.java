package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.catalog.ModelArtifactInspection;
import com.spirit.koil.api.model.catalog.ModelArtifactInspector;

import java.nio.file.Path;
import java.util.Map;

/**
 * Compatibility facade retained for provider-local proofs written before
 * artifact inspection moved into the runtime-neutral catalog layer.
 */
final class GgufMetadataInspector {
    private GgufMetadataInspector() {
    }

    static Snapshot inspect(Path file) {
        ModelArtifactInspection inspection = ModelArtifactInspector.inspect(file);
        return new Snapshot(inspection);
    }

    record Snapshot(ModelArtifactInspection inspection) {
        Snapshot {
            inspection = inspection == null ? ModelArtifactInspection.unavailable(null, "inspection unavailable") : inspection;
        }

        int version() { return inspection.formatVersion(); }
        long tensorCount() { return inspection.tensorCount(); }
        Map<String, String> metadata() { return inspection.metadata(); }
        boolean present() { return inspection.present(); }
        String value(String key) { return inspection.metadata().getOrDefault(key, ""); }
        String architecture() { return inspection.architectureId(); }
        String chatTemplate() { return inspection.chatTemplate(); }
        String tokenizerModel() { return inspection.tokenizerFamily(); }
        int contextLength() { return inspection.contextTokens(); }
        int expertCount() { return inspection.expertCount(); }
        int activeExpertCount() { return inspection.activeExpertCount(); }
    }
}
