package com.spirit.client.gui.mod.modconfig;

import net.fabricmc.loader.api.ModContainer;

import java.util.List;

public record DiscoveredModConfigSet(ModContainer mod, List<DiscoveredModConfigFile> files) {
    public boolean hasFiles() {
        return !files.isEmpty();
    }

    public boolean hasMultipleFiles() {
        return files.size() > 1;
    }
}
