package com.spirit.koil.api.model.provider.colibri;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public record ColibriInstallationCheck(boolean compatible, List<String> failures) {
    public ColibriInstallationCheck {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public static ColibriInstallationCheck inspect(ColibriConfiguration configuration, boolean allowExistingRuntime) {
        List<String> failures = new ArrayList<>();
        if (configuration == null || !configuration.enabled()) {
            failures.add("local model integration is disabled");
            return new ColibriInstallationCheck(false, failures);
        }
        if (!configuration.localhostOnly()) {
            failures.add("Colibri host must resolve to the local machine");
        }
        if (configuration.apiKey().isBlank()) {
            failures.add("local API authentication key is missing");
        }
        if (!allowExistingRuntime) {
            if (configuration.executable() == null || !Files.isRegularFile(configuration.executable())) {
                failures.add("Colibri executable is missing");
            } else if (!Files.isExecutable(configuration.executable())) {
                failures.add("Colibri executable is not executable");
            }
            if (configuration.modelDirectory() == null || !Files.isDirectory(configuration.modelDirectory())) {
                failures.add("model directory is missing");
            }
        }
        return new ColibriInstallationCheck(failures.isEmpty(), failures);
    }
}
