package com.spirit.koil.api.f3;

import java.util.List;

public record F3Section(
        String id,
        String title,
        String summary,
        int accentColor,
        List<F3DataLine> lines
) {
}
