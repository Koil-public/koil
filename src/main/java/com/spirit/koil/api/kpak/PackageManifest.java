package com.spirit.koil.api.kpak;

import java.util.List;

public class PackageManifest {

    private final int manifestVersion = 2;

    private String serial;

    private String id;
    private String packageIdentity;

    private String displayName;
    private String description;

    private String packageVersion;

    private String minKoilVersion;
    private String maxKoilVersion;

    private String author;
    private String authorId;

    private String signature;

    private String hashAlgorithm;
    private String signatureAlgorithm;

    private String license;
    private String created;

    private List<PackageOperation> operations;


    public int getManifestVersion() {
        return manifestVersion;
    }

    public String getSerial() {
        return serial;
    }

    public String getId() {
        return id;
    }

    public String getPackageIdentity() {
        return packageIdentity;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getPackageVersion() {
        return packageVersion;
    }

    public String getMinKoilVersion() {
        return minKoilVersion;
    }

    public String getMaxKoilVersion() {
        return maxKoilVersion;
    }

    public String getAuthor() {
        return author;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getSignature() {
        return signature;
    }

    public String getHashAlgorithm() {
        return hashAlgorithm;
    }

    public String getSignatureAlgorithm() {
        return signatureAlgorithm;
    }

    public String getLicense() {
        return license;
    }

    public String getCreated() {
        return created;
    }

    public List<PackageOperation> getOperations() {
        return operations;
    }
}