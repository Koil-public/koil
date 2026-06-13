package com.spirit.koil.api.f3;

import java.nio.file.Path;

public final class F3Paths {
    public static final Path ROOT = Path.of("koil", "sys", "f3_reports");
    public static final Path SNAPSHOTS = ROOT.resolve("f3_snapshots.json");
    public static final Path TARGET_REPORTS = ROOT.resolve("target_reports.json");
    public static final Path CHUNK_REPORTS = ROOT.resolve("chunk_reports.json");
    public static final Path ENTITY_REPORTS = ROOT.resolve("entity_reports.json");
    public static final Path BLOCK_REPORTS = ROOT.resolve("block_reports.json");
    public static final Path PERFORMANCE_SNAPSHOTS = ROOT.resolve("performance_snapshots.json");
    public static final Path LAYOUT_CONFIG = ROOT.resolve("f3_layout_config.json");
    public static final Path PINNED_CARDS = ROOT.resolve("f3_pinned_cards.json");

    private F3Paths() {
    }
}
