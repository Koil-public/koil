package com.spirit.koil.api.kpak;

public class PackageOperation {

    private final String operation;
    private final String path;
    private final String sha256;
    public PackageOperation(String operation, String path, String sha256) {
        this.operation = operation;
        this.path = path;
        this.sha256 = sha256;
    }

    public String getOperation() {
        return operation;
    }

    public String getPath() {
        return path;
    }

    public String getSha256() {
        return sha256;
    }
}
