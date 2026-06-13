package com.spirit.koil.api.f3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class F3ReportService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private F3ReportService() {
    }

    public static Path writeSnapshotReport(MinecraftClient client) {
        F3Snapshot snapshot = F3SnapshotService.latest(client);
        Map<String, Object> report = snapshotMap(snapshot);
        write(F3Paths.SNAPSHOTS, report);
        writeTargetReport(snapshot);
        if (snapshot.performance() != null) {
            write(F3Paths.PERFORMANCE_SNAPSHOTS, Map.of(
                    "capturedAtMillis", snapshot.capturedAtMillis(),
                    "performance", snapshot.performance()
            ));
        }
        return F3Paths.SNAPSHOTS;
    }

    public static Path writeTargetReport(F3Snapshot snapshot) {
        if (snapshot == null || snapshot.target() == null) {
            return F3Paths.TARGET_REPORTS;
        }
        Map<String, Object> report = new LinkedHashMap<>();
        F3TargetSnapshot target = snapshot.target();
        report.put("capturedAtMillis", snapshot.capturedAtMillis());
        report.put("type", target.type().name());
        report.put("title", target.title());
        report.put("registryId", target.registryId());
        report.put("modOwner", target.modOwner());
        report.put("position", target.position());
        report.put("danger", target.danger());
        report.put("lines", target.lines());
        report.put("tags", target.tags());
        report.put("actions", target.actions());
        Path path = switch (target.type()) {
            case BLOCK, CONTAINER, FLUID -> F3Paths.BLOCK_REPORTS;
            case ENTITY, PLAYER -> F3Paths.ENTITY_REPORTS;
            default -> F3Paths.TARGET_REPORTS;
        };
        write(path, report);
        return path;
    }

    public static Map<String, Object> snapshotMap(F3Snapshot snapshot) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("capturedAtMillis", snapshot.capturedAtMillis());
        report.put("mode", snapshot.mode().name());
        report.put("refreshSpeed", snapshot.refreshSpeed().name());
        report.put("frozen", snapshot.frozen());
        report.put("headline", snapshot.headline());
        report.put("status", snapshot.status());
        report.put("warnings", snapshot.warnings());
        report.put("performance", snapshot.performance());
        report.put("target", snapshot.target());
        report.put("sections", snapshot.sections());
        return report;
    }

    public static String shortTargetReport(F3Snapshot snapshot) {
        if (snapshot == null || snapshot.target() == null) {
            return "No Koil F3 target.";
        }
        F3TargetSnapshot target = snapshot.target();
        return "Target " + target.type().label()
                + " | " + target.title()
                + " | " + target.registryId()
                + " | " + target.position()
                + " | " + target.modOwner();
    }

    public static void saveLayout() {
        write(F3Paths.LAYOUT_CONFIG, Map.of(
                "mode", F3LayoutState.mode().name(),
                "refreshSpeed", F3LayoutState.refreshSpeed().name(),
                "compactOverlay", F3LayoutState.compactOverlay()
        ));
        write(F3Paths.PINNED_CARDS, Map.of("pinnedCards", List.copyOf(F3LayoutState.pinnedCards())));
    }

    private static void write(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(value), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
