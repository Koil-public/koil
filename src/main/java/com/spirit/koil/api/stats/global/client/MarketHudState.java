package com.spirit.koil.api.stats.global.client;

import com.spirit.koil.api.stats.global.KoilMarketHudSnapshot;

public final class MarketHudState {
    private static KoilMarketHudSnapshot snapshot;
    private static long shownAt;

    private MarketHudState() {
    }

    public static synchronized void show(KoilMarketHudSnapshot next) {
        snapshot = next;
        shownAt = System.currentTimeMillis();
    }

    public static synchronized void hide() {
        snapshot = null;
        shownAt = 0L;
    }

    public static synchronized KoilMarketHudSnapshot snapshot() {
        if (snapshot == null) {
            return null;
        }

        if (isSticky(snapshot)) {
            return snapshot;
        }

        long now = System.currentTimeMillis();

        if (now - shownAt > 14000L) {
            hide();
            return null;
        }

        return snapshot;
    }

    private static boolean isSticky(KoilMarketHudSnapshot value) {
        String mode = value == null || value.mode() == null ? "" : value.mode().toLowerCase();
        return mode.contains("catch");
    }
}
