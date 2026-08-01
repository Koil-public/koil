package com.spirit.client.gui.content.mod.modconfig;

import java.io.File;

public record DiscoveredModConfigFile(File file, String relativePath, ConfigFormat format) {
}
