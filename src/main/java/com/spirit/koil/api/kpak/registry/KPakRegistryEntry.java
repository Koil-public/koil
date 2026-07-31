package com.spirit.koil.api.kpak.registry;

import java.util.List;

public record KPakRegistryEntry(String id, String version, String authorId, String installedAt, String backupPath,
                                List<String> installedFiles) {

}
