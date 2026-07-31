package com.spirit.koil.api.kpak.builder;

import com.google.gson.GsonBuilder;
import com.spirit.koil.api.kpak.PackageManifest;
import com.spirit.koil.api.kpak.PackageOperation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class KPakManifestBuilder {

    private final PackageManifest manifest;

    public KPakManifestBuilder() {
        manifest = new PackageManifest();
    }

    public KPakManifestBuilder serial(String serial) {
        setField("serial", serial);
        return this;
    }

    public KPakManifestBuilder id(String id) {
        setField("id", id);
        return this;
    }

    public KPakManifestBuilder packageIdentity(String identity) {
        setField("packageIdentity", identity);
        return this;
    }

    public KPakManifestBuilder displayName(String name) {
        setField("displayName", name);
        return this;
    }

    public KPakManifestBuilder description(String description) {
        setField("description", description);
        return this;
    }

    public KPakManifestBuilder version(String version) {
        setField("packageVersion", version);
        return this;
    }

    public KPakManifestBuilder targetKoilVersion(String version) {
        setField("minKoilVersion", version);
        return this;
    }

    public KPakManifestBuilder author(String author) {
        setField("author", author);
        return this;
    }

    public KPakManifestBuilder authorId(String id) {
        setField("authorId", id);
        return this;
    }

    public KPakManifestBuilder signature(String signature) {
        setField("signature", signature);
        return this;
    }

    public KPakManifestBuilder hashAlgorithm(String algorithm) {
        setField("hashAlgorithm", algorithm);
        return this;
    }

    public KPakManifestBuilder signatureAlgorithm(String algorithm) {
        setField("signatureAlgorithm", algorithm);
        return this;
    }

    public KPakManifestBuilder operations(List<PackageOperation> operations) {
        setField("operations", operations);
        return this;
    }

    public PackageManifest build() {
        return manifest;
    }

    public void write(Path path) throws Exception {
        Files.writeString(
            path,
            new GsonBuilder()
                .setPrettyPrinting()
                .create()
                .toJson(manifest)
        );
    }

    private void setField(String name, Object value) {
        try {
            var field = PackageManifest.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(manifest, value);
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed setting manifest field " + name,
                e
            );
        }
    }
}
