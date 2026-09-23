package com.spirit.koil.api.model.install;

import com.spirit.koil.api.model.catalog.LocalModelRuntimePlatform;

import java.nio.file.Files;
import java.nio.file.Path;

/** Contract proof for exact, boot-safe managed llama.cpp runtime selection. */
public final class LlamaCppRuntimeCatalogProof {
    private LlamaCppRuntimeCatalogProof() {
    }

    public static void main(String[] args) {
        LlamaCppRuntimeCatalog.RuntimeArtifact windows = LlamaCppRuntimeCatalog.forPlatform("Windows 11", "amd64").orElseThrow();
        require(windows.fileName().equals("llama-b10173-bin-win-cpu-x64.zip"),
                "Windows x64 must default to the CPU runtime rather than requiring Vulkan at boot");
        require(windows.sizeBytes() == 18_337_911L
                        && windows.sha256().equals("5446b53737195422fca305e5f45027f46f51670a3a2062f885d78e5ec1968366"),
                "Windows CPU runtime must remain exact-size and hash pinned");
        require(LlamaCppRuntimeCatalog.forPlatform("Mac OS X", "aarch64").orElseThrow().fileName()
                        .equals("llama-b10173-bin-macos-arm64.tar.gz"),
                "macOS ARM64 runtime selection drifted");
        require(LlamaCppRuntimeCatalog.forPlatform("Linux", "x86_64").orElseThrow().fileName()
                        .equals("llama-b10173-bin-ubuntu-vulkan-x64.tar.gz"),
                "Linux runtime selection drifted");
        require(ManagedRuntimeCatalog.colibriArtifacts().stream()
                        .filter(artifact -> "windows-x86_64".equals(artifact.platformId()))
                        .findFirst().orElseThrow().executableName().equals("coli.cmd"),
                "Colibri Windows releases must launch their documented coli.cmd wrapper");
        try {
            Path windowsExecutable = Files.createTempFile("koil-windows-runtime-", ".exe");
            require(LocalModelRuntimePlatform.isLaunchable(windowsExecutable, "Windows 11"),
                    "a regular Windows .exe must be launchable without POSIX execute bits");
            require(!LocalModelRuntimePlatform.isLaunchable(windowsExecutable, "Linux"),
                    "a non-executable file must remain rejected on POSIX platforms");
            Files.deleteIfExists(windowsExecutable);
        } catch (java.io.IOException failure) {
            throw new AssertionError("could not create Windows runtime proof file", failure);
        }
        Path batch = Path.of("C:/Koil Runtime/coli.cmd");
        java.util.List<String> batchCommand = LocalModelRuntimePlatform.launchCommand(
                batch, java.util.List.of("serve", "--model", "C:/Models/Test Model"), "Windows 11");
        require(batchCommand.subList(0, 4).equals(java.util.List.of("cmd.exe", "/d", "/s", "/c"))
                        && batchCommand.get(4).contains("coli.cmd")
                        && batchCommand.get(4).endsWith("\"serve\" \"--model\" \"C:/Models/Test Model\""),
                "Windows batch launchers must retain their path and arguments as one cmd command");
        System.out.println("LlamaCppRuntimeCatalogProof: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
