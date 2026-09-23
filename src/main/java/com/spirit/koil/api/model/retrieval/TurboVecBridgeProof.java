package com.spirit.koil.api.model.retrieval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** ABI proof: missing native code degrades truthfully; a supplied bridge proves stable-ID operations. */
public final class TurboVecBridgeProof {
    private TurboVecBridgeProof() {
    }

    public static void main(String[] args) throws Exception {
        require(".dylib".equals(TurboVecNativeBridge.expectedExtensionFor("Mac OS X")),
                "macOS development launches must select dylib bridges");
        require(".dll".equals(TurboVecNativeBridge.expectedExtensionFor("Windows 11")),
                "Windows development launches must select DLL bridges");
        require(".so".equals(TurboVecNativeBridge.expectedExtensionFor("Linux")),
                "Linux development launches must select shared-object bridges");
        require(TurboVecNativeBridge.platformCompatibilityProblem(Path.of("/tmp/libkoil_turbovec.so"), "Mac OS X").contains(".dylib"),
                "a Linux bridge path must be rejected before macOS attempts JNI loading");
        require(TurboVecNativeBridge.platformCompatibilityProblem(Path.of("C:/koil/libkoil_turbovec.so"), "Windows 11").contains(".dll"),
                "a Linux bridge path must be rejected before Windows attempts JNI loading");
        Path indexPath = Files.createTempDirectory("koil-turbovec-proof-").resolve("vectors.tvim");
        try (TurboVecVectorIndex index = TurboVecVectorIndex.open(indexPath, 8, 4)) {
            if (!index.health().ready()) {
                require(!Boolean.getBoolean("koil.turbovec.required"),
                        "the configured development TurboVec bridge did not load: " + index.health().detail());
                require(index.health().state() == VectorIndexHealth.State.UNAVAILABLE,
                        "missing native bridge must be explicit and non-fatal");
                require(!index.health().detail().isBlank(), "missing native bridge must retain its diagnostic");
                System.out.println("TurboVecBridgeProof: PASS (native unavailable: " + index.health().detail() + ')');
                return;
            }
            index.add(1_001L, vector(0));
            index.add(1_002L, vector(1));
            index.add(1_001L, vector(2));
            require(index.health().entryCount() == 2L,
                    "replacing a stable ID must not create a second native vector slot");
            require(index.search(vector(2), new VectorSearchRequest(1, Set.of(1_001L), 0)).get(0).id() == 1_001L,
                    "replacing a stable ID must update its searchable vector");
            require(index.search(vector(0), new VectorSearchRequest(1, Set.of(1_001L), 0)).get(0).id() == 1_001L,
                    "bridge must return stable IdMapIndex IDs through its allowlist");
            require(index.remove(1_001L), "bridge removal must report a present ID");
            require(index.search(vector(0), new VectorSearchRequest(1, Set.of(), 0)).get(0).id() == 1_002L,
                    "removed ID must no longer be returned");
            index.sync();
            System.out.println("TurboVecBridgeProof: PASS (" + index.version() + ')');
        }
    }

    private static float[] vector(int coordinate) {
        float[] vector = new float[8];
        vector[coordinate] = 1.0F;
        return vector;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
