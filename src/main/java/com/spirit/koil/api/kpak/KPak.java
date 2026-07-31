package com.spirit.koil.api.kpak;

import com.google.gson.Gson;

import java.io.*;
import java.nio.file.Path;
import java.util.zip.ZipFile;

public class KPak {
    private final Path file;
    private PackageManifest manifest;

    public KPak(Path file) {
        this.file = file;
    }

    public Path getFile() {
        return file;
    }

    public String getName() {
        return file.getFileName().toString();
    }

    public ZipFile open() throws IOException {
        return new ZipFile(file.toFile());
    }

    public PackageManifest getManifest() throws IOException {
        if (manifest != null) {
            return manifest;
        }

        try (ZipFile zip = open()) {
            var entry = zip.getEntry("package.json");

            if (entry == null) {
                throw new FileNotFoundException(
                        "Missing package.json"
                );
            }

            try (InputStream input = zip.getInputStream(entry); Reader reader = new InputStreamReader(input)) {
                manifest = new Gson().fromJson(
                        reader,
                        PackageManifest.class
                );
            }
        }

        return manifest;
    }
}