package com.spirit.client.gui.mod.modconfig;

import java.io.File;

public record DiscoveredModConfigFile(File file, String relativePath, ConfigFormat format) {
}
