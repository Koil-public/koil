package com.spirit.koil.api.kpak.backup;

import java.util.List;

public record KPakBackupMetadata(String packageId, String version, String created, List<FileBackupEntry> files) {


    public record FileBackupEntry(String path, String hash) {

    }
}
