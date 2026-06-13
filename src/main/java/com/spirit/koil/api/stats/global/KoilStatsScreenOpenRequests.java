package com.spirit.koil.api.stats.global;

import java.util.concurrent.atomic.AtomicBoolean;

public final class KoilStatsScreenOpenRequests {
    private static final AtomicBoolean OPEN_GLOBAL_ON_NEXT_STATS_SCREEN = new AtomicBoolean(false);

    private KoilStatsScreenOpenRequests() {
    }

    public static void requestGlobalPage() {
        OPEN_GLOBAL_ON_NEXT_STATS_SCREEN.set(true);
    }

    public static boolean consumeGlobalPageRequest() {
        return OPEN_GLOBAL_ON_NEXT_STATS_SCREEN.getAndSet(false);
    }
}
