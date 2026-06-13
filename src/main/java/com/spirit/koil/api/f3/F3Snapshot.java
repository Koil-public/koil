package com.spirit.koil.api.f3;

import com.spirit.koil.api.performance.PerformanceSnapshot;

import java.util.List;

public record F3Snapshot(
        long capturedAtMillis,
        F3Mode mode,
        F3RefreshSpeed refreshSpeed,
        boolean frozen,
        String headline,
        String status,
        PerformanceSnapshot performance,
        F3TargetSnapshot target,
        List<F3Section> sections,
        List<String> warnings
) {
}
