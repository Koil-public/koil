package com.spirit.koil.api.model.install;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

public final class LlamaCppRuntimeCatalog {
    public static final String VERSION = "b10173";

    private LlamaCppRuntimeCatalog() {
    }

    public static Optional<RuntimeArtifact> currentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = normalizeArchitecture(System.getProperty("os.arch", ""));
        if (os.contains("mac")) {
            return switch (arch) {
                case "x64" -> artifact("macos-x64", "tar.gz", 11_203_396L, "610c9b2eb6dc03b280b69198268764c192f859db97f653187eed838dd36584f3");
                case "arm64" -> artifact("macos-arm64", "tar.gz", 10_924_935L, "fcbe78bae047bd36a5c87f36a456b437ab0e8ba32336fb75583127e6013f8809");
                default -> Optional.empty();
            };
        }
        if (os.contains("win")) {
            return switch (arch) {
                case "x64" -> artifact("win-cpu-x64", "zip", 18_337_911L, "5446b53737195422fca305e5f45027f46f51670a3a2062f885d78e5ec1968366");
                case "arm64" -> artifact("win-cpu-arm64", "zip", 12_182_581L, "174664ff6da77f89f3113230ab5b941f86689f451a4d346583d1aa8f87a70427");
                default -> Optional.empty();
            };
        }
        if (os.contains("linux")) {
            return switch (arch) {
                case "x64" -> artifact("ubuntu-x64", "tar.gz", 16_429_212L, "0dd14e9ffd6564263ef46b259818eee5addea453281f3572ffaa93c01949b32f");
                case "arm64" -> artifact("ubuntu-arm64", "tar.gz", 13_328_957L, "603b4c534f13dd9792d894e761e9caec8b447795bda0fd9de2b36944f6244537");
                default -> Optional.empty();
            };
        }
        return Optional.empty();
    }

    private static Optional<RuntimeArtifact> artifact(String platform, String extension, long size, String sha256) {
        String fileName = "llama-" + VERSION + "-bin-" + platform + "." + extension;
        return Optional.of(new RuntimeArtifact(
                fileName,
                URI.create("https://github.com/ggml-org/llama.cpp/releases/download/" + VERSION + "/" + fileName),
                size,
                sha256,
                extension
        ));
    }

    private static String normalizeArchitecture(String value) {
        String arch = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (arch.equals("x86_64") || arch.equals("amd64") || arch.equals("x64")) {
            return "x64";
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "arm64";
        }
        return arch;
    }

    public record RuntimeArtifact(
            String fileName,
            URI downloadUri,
            long sizeBytes,
            String sha256,
            String archiveType
    ) {
    }
}
