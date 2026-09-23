package com.spirit.koil.api.model.provider.colibri;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.model.catalog.LocalModelRuntimePlatform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs Colibri's own doctor and planner off the Minecraft thread. */
final class ColibriRuntimeInspectionService {
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(2);

    private ColibriRuntimeInspectionService() {
    }

    static Inspection inspect(Path executable, Path modelDirectory, int contextTokens, int kvSlots)
            throws IOException, InterruptedException {
        Map<String, String> environment = Map.of(
                "COLI_MODEL", modelDirectory.toAbsolutePath().normalize().toString(),
                "COLI_KV_SLOTS", Integer.toString(Math.max(1, kvSlots))
        );
        CommandResult doctor = run(executable, environment,
                List.of("doctor", "--json", "--ctx", Integer.toString(Math.max(512, contextTokens))));
        JsonObject doctorJson = parseObject(doctor.output(), "doctor");
        String doctorStatus = string(doctorJson, "status", doctor.exitCode() == 0 ? "ok" : "error");
        if (doctor.exitCode() != 0 || "error".equalsIgnoreCase(doctorStatus)
                || "fail".equalsIgnoreCase(doctorStatus)) {
            throw new IOException("COLIBRI_DOCTOR_FAILED: " + summarizeDoctor(doctorJson, doctor.output()));
        }

        CommandResult plan = run(executable, environment,
                List.of("plan", "--json", "--ctx", Integer.toString(Math.max(512, contextTokens))));
        if (plan.exitCode() != 0) {
            throw new IOException("COLIBRI_PLAN_FAILED: " + compact(plan.output()));
        }
        JsonObject planJson = parseObject(plan.output(), "plan");
        Map<String, String> diagnostics = new LinkedHashMap<>();
        diagnostics.put("doctorStatus", doctorStatus);
        add(diagnostics, "backend", first(planJson, "backend", "device", "gpu_backend"));
        add(diagnostics, "policy", first(planJson, "policy", "resource_policy"));
        add(diagnostics, "ramResident", first(planJson, "ram_gb", "ram_resident_gb"));
        add(diagnostics, "vramResident", first(planJson, "vram_gb", "vram_resident_gb"));
        add(diagnostics, "diskResident", first(planJson, "disk_gb", "disk_resident_gb"));
        add(diagnostics, "ioMode", first(planJson, "io_mode", "storage_mode"));
        add(diagnostics, "planFingerprint", first(planJson, "fingerprint", "profile_fingerprint"));
        return new Inspection(doctorJson, planJson, Map.copyOf(diagnostics));
    }

    private static CommandResult run(Path executable, Map<String, String> environment, List<String> arguments)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(LocalModelRuntimePlatform.launchCommand(executable, arguments)).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        java.util.concurrent.CompletableFuture<byte[]> output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return process.getInputStream().readAllBytes();
            } catch (IOException failure) {
                return ("read failed: " + failure.getMessage()).getBytes(StandardCharsets.UTF_8);
            }
        });
        if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("Colibri inspection exceeded " + COMMAND_TIMEOUT.toSeconds() + " seconds");
        }
        byte[] bytes;
        try {
            bytes = output.get(5L, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IOException("could not read Colibri inspection output", failure);
        }
        return new CommandResult(process.exitValue(), new String(bytes, StandardCharsets.UTF_8));
    }

    private static JsonObject parseObject(String output, String command) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(output.strip());
            if (parsed.isJsonObject()) return parsed.getAsJsonObject();
        } catch (RuntimeException ignored) {
        }
        throw new IOException("Colibri " + command + " returned invalid JSON: " + compact(output));
    }

    private static String summarizeDoctor(JsonObject root, String fallback) {
        try {
            if (root.has("checks") && root.get("checks").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("checks")) {
                    if (!element.isJsonObject()) continue;
                    JsonObject check = element.getAsJsonObject();
                    String status = string(check, "status", "");
                    if ("fail".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status)) {
                        return string(check, "id", "check") + ": " + string(check, "summary", status);
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return compact(fallback);
    }

    private static String first(JsonObject root, String... keys) {
        for (String key : keys) {
            if (root.has(key) && root.get(key).isJsonPrimitive()) return root.get(key).getAsString();
        }
        return "";
    }

    private static String string(JsonObject root, String key, String fallback) {
        try {
            return root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsString() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static void add(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    private static String compact(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return compact.length() <= 300 ? compact : compact.substring(0, 300) + "...";
    }

    record Inspection(JsonObject doctor, JsonObject plan, Map<String, String> diagnostics) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
