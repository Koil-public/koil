package com.spirit.koil.api.model.install;

import com.spirit.koil.api.model.catalog.LocalModelRuntimePlatform;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/** Runtime distributions are pinned independently from model metadata. */
public final class ManagedRuntimeCatalog {
    public static final String LLAMA_CPP_RUNTIME_ID = com.spirit.koil.api.model.catalog.ModelRuntimeIds.LLAMA_CPP;
    public static final String COLIBRI_RUNTIME_ID = com.spirit.koil.api.model.catalog.ModelRuntimeIds.COLIBRI;
    public static final String COLIBRI_COMMIT = "12a5c464b5c1f8292d578c62458706bc32d6ac95";
    public static final String GIGATOKEN_BRIDGE_RUNTIME_ID = com.spirit.koil.api.model.catalog.ModelRuntimeIds.GIGATOKEN_BRIDGE;
    public static final String CODEBASE_MEMORY_MCP_RUNTIME_ID = com.spirit.koil.api.model.catalog.ModelRuntimeIds.CODEBASE_MEMORY_MCP;
    public static final String GIGATOKEN_COMMIT = "fac0114b37120ec8a76362e9ee8e1c742aaafaef";

    private static final List<ManagedRuntimeArtifact> COLIBRI = List.of(
            colibriBinary("linux-x86_64", "tar.gz", 1_928_064L,
                    "19aa417039fe816d21167cfe730fea2e3028ef4fe57fb2c8ed4755ebe4c84a21"),
            colibriBinary("macos-arm64", "tar.gz", 1_750_116L,
                    "200761c7ee2dccaece942cf4ab4a42814658df8b2dc9b2464cfc1e8ee5b4776f"),
            colibriBinary("windows-x86_64", "zip", 4_485_678L,
                    "111a511e5c76de63ca504ba7bec36d85d9dd9b8b93cbaa47775af07f77209b4d"),
            colibriSource("linux-arm64"),
            colibriSource("macos-x86_64")
    );

    private ManagedRuntimeCatalog() {
    }

    public static Optional<ManagedRuntimeArtifact> current(String runtimeId) {
        String platform = LocalModelRuntimePlatform.currentId();
        if (LLAMA_CPP_RUNTIME_ID.equals(runtimeId)) {
            return LlamaCppRuntimeCatalog.currentPlatform().map(value -> new ManagedRuntimeArtifact(
                    runtimeId,
                    "llama_cpp",
                    LlamaCppRuntimeCatalog.VERSION,
                    platform,
                    value.fileName(),
                    value.downloadUri(),
                    value.sizeBytes(),
                    value.sha256(),
                    value.archiveType(),
                    platform.startsWith("windows") ? "llama-server.exe" : "llama-server",
                    "MIT",
                    false,
                    ""
            ));
        }
        if (COLIBRI_RUNTIME_ID.equals(runtimeId)) {
            return COLIBRI.stream().filter(value -> value.platformId().equals(platform)).findFirst();
        }
        if (GIGATOKEN_BRIDGE_RUNTIME_ID.equals(runtimeId)) {
            // Every platform builds the bridge from the same verified upstream
            // source: upstream Gigatoken publishes no prebuilt CLI artifacts.
            return Optional.of(gigatokenSource(platform));
        }
        if (CODEBASE_MEMORY_MCP_RUNTIME_ID.equals(runtimeId)) {
            return CODEBASE_MEMORY.stream().filter(value -> value.platformId().equals(platform)).findFirst();
        }
        return Optional.empty();
    }

    /** Koil-owned, release-hash-pinned MCP executable; never invokes CBM's client-config installer. */
    private static final List<ManagedRuntimeArtifact> CODEBASE_MEMORY = List.of(
            codebaseMemoryBinary("macos-x86_64", "darwin-amd64", "tar.gz", 41_662_649L, "dbf1c73bfcbde64e7dde4cd1320da7afc02e2c972ee1789ae039521411f5132e"),
            codebaseMemoryBinary("macos-arm64", "darwin-arm64", "tar.gz", 41_117_691L, "4dee7f38b63740e6751d7a7ed7eb10291c1f2a3ea2415f599dc68370ca0a2d18"),
            codebaseMemoryBinary("linux-x86_64", "linux-amd64", "tar.gz", 39_859_115L, "032b33c1833919a2d1de67ff6367fa6ea46aee8689c86ef223c88fae3b6e4536"),
            codebaseMemoryBinary("linux-arm64", "linux-arm64", "tar.gz", 39_497_713L, "c0e46c87cf37e35f1ac0bd9cc7e1d8b0ca4ef40034e1008805d709fa52a4e38a"),
            codebaseMemoryBinary("windows-x86_64", "windows-amd64", "zip", 39_858_881L, "6eb6beaf261b19e419766e78baf93cbc3cf1c6338cff8fb7c0234859f96d1685"),
            codebaseMemoryBinary("windows-arm64", "windows-arm64", "zip", 39_368_514L, "52b29881214fce47d529e098308b1de77c40f6812e20a34d588ee4c25b84fd1a")
    );

    private static ManagedRuntimeArtifact codebaseMemoryBinary(String platform, String releasePlatform,
                                                                 String extension, long size, String sha256) {
        String name = "codebase-memory-mcp-" + releasePlatform + "." + extension;
        return new ManagedRuntimeArtifact(
                CODEBASE_MEMORY_MCP_RUNTIME_ID, "codebase_memory", "v0.11.0", platform, name,
                URI.create("https://github.com/DeusData/codebase-memory-mcp/releases/download/v0.11.0/" + name),
                size, sha256, extension, platform.startsWith("windows") ? "codebase-memory-mcp.exe" : "codebase-memory-mcp",
                "MIT", false, ""
        );
    }

    public static List<ManagedRuntimeArtifact> colibriArtifacts() {
        return COLIBRI;
    }

    private static ManagedRuntimeArtifact colibriBinary(
            String platform,
            String extension,
            long size,
            String sha256
    ) {
        String name = "colibri-v1.10.1-" + platform + "." + extension;
        return new ManagedRuntimeArtifact(
                COLIBRI_RUNTIME_ID,
                "colibri",
                "v1.10.1",
                platform,
                name,
                URI.create("https://github.com/JustVugg/colibri/releases/download/v1.10.1/" + name),
                size,
                sha256,
                extension,
                platform.startsWith("windows") ? "coli.cmd" : "coli",
                "Apache-2.0",
                false,
                ""
        );
    }

    /**
     * The pinned upstream Gigatoken crate, verified by exact archive size and
     * SHA-256 of the GitHub commit tarball. The wrapper crate ships inside the
     * Koil mod JAR; this artifact is the tokenizer engine the wrapper links.
     */
    private static ManagedRuntimeArtifact gigatokenSource(String platform) {
        return new ManagedRuntimeArtifact(
                GIGATOKEN_BRIDGE_RUNTIME_ID,
                "gigatoken",
                "0.10.0+" + GIGATOKEN_COMMIT.substring(0, 12),
                platform,
                "gigatoken-" + GIGATOKEN_COMMIT + ".tar.gz",
                URI.create("https://github.com/marcelroed/gigatoken/archive/" + GIGATOKEN_COMMIT + ".tar.gz"),
                1_341_568L,
                "d20aa9cf3c2b99e1f3be7616e54e778c9c85038dd3b0161133b74f0900684804",
                "tar.gz",
                platform.startsWith("windows") ? "gigatoken-bridge.exe" : "gigatoken-bridge",
                "MIT",
                true,
                ""
        );
    }

    private static ManagedRuntimeArtifact colibriSource(String platform) {
        return new ManagedRuntimeArtifact(
                COLIBRI_RUNTIME_ID,
                "colibri",
                "v1.10.1+" + COLIBRI_COMMIT.substring(0, 12),
                platform,
                "colibri-" + COLIBRI_COMMIT + ".tar.gz",
                URI.create("https://github.com/JustVugg/colibri/archive/" + COLIBRI_COMMIT + ".tar.gz"),
                5_926_183L,
                "1fddab9d583168fca915b01b033d646343cf7cec136d78fda8b72def013a6ffa",
                "tar.gz",
                "coli",
                "Apache-2.0",
                true,
                "c"
        );
    }
}
