package com.spirit.koil.api.model.provider.llamacpp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.util.file.KoilInstancePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Atomic per-instance persistence for the user's llama.cpp compute preference. */
public final class LlamaCppComputeSettingsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private LlamaCppComputeSettingsStore() {
    }

    public static Path path() {
        return KoilInstancePaths.modelRoot().resolve("llama-compute.json");
    }

    public static LlamaCppComputeSettings load() {
        Path path = path();
        if (!Files.isRegularFile(path)) {
            LlamaCppComputeSettings defaults = LlamaCppComputeSettings.defaults();
            save(defaults);
            return defaults;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            LlamaCppComputeSettings defaults = LlamaCppComputeSettings.defaults();
            LlamaCppComputeMode mode = LlamaCppComputeMode.parse(
                    root.has("mode") ? root.get("mode").getAsString() : "",
                    defaults.mode()
            );
            int hybridGpuLayers = root.has("hybridGpuLayers")
                    ? root.get("hybridGpuLayers").getAsInt()
                    : defaults.hybridGpuLayers();
            String device = root.has("device") ? root.get("device").getAsString() : "";
            return new LlamaCppComputeSettings(mode, hybridGpuLayers, device);
        } catch (Exception failure) {
            return LlamaCppComputeSettings.defaults();
        }
    }

    public static void save(LlamaCppComputeSettings settings) {
        LlamaCppComputeSettings safe = settings == null ? LlamaCppComputeSettings.defaults() : settings;
        Path absolute = path().toAbsolutePath().normalize();
        try {
            Files.createDirectories(absolute.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("mode", safe.mode().name().toLowerCase(java.util.Locale.ROOT));
            root.addProperty("hybridGpuLayers", safe.hybridGpuLayers());
            root.addProperty("device", safe.device());
            root.addProperty("semantics", "cpu=no accelerator; max=machine/model/runtime tuned fastest validated profile when available, otherwise bounded llama.cpp fit fallback; gpu=maximum safe fitted accelerator placement; hybrid=fixed GPU model layers with host-resident KV cache and remaining model layers on CPU");
            Path temporary = Files.createTempFile(absolute.getParent(), "llama-compute-", ".tmp");
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to save llama.cpp compute settings: " + failure.getMessage(), failure);
        }
    }
}
