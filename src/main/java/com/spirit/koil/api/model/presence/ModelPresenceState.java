package com.spirit.koil.api.model.presence;

import com.spirit.koil.api.automation.cli.AutomationStateColors;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Privacy-minimal model activity presence shared by /ask and Automation Mode.
 * No prompt, response, path, tool argument, or free-form detail is stored.
 */
public final class ModelPresenceState {
    public static final int PROTOCOL_VERSION = 1;
    public static final long STALE_AFTER_MILLIS = 5_000L;
    public static final long TERMINAL_VISIBILITY_MILLIS = 8_000L;

    private static final Map<UUID, Snapshot> REMOTE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> REMOTE_V1_SEEN = new ConcurrentHashMap<>();
    private static volatile Snapshot localRequest = Snapshot.none();
    private static volatile Snapshot automationFallback = Snapshot.none();

    private ModelPresenceState() {
    }

    public static void updateAutomation(boolean enabled, String semanticState) {
        long now = System.currentTimeMillis();
        automationFallback = enabled
                ? new Snapshot(ActivityKind.AUTOMATION, normalize(semanticState), now, false)
                : Snapshot.noneAt(now);
    }

    public static void updateRequest(ActivityKind kind, String semanticState, boolean active) {
        ActivityKind safeKind = kind == null ? ActivityKind.NONE : kind;
        localRequest = safeKind == ActivityKind.NONE
                ? Snapshot.none()
                : new Snapshot(safeKind, normalize(semanticState), System.currentTimeMillis(), active);
    }

    public static Snapshot localSnapshot() {
        long now = System.currentTimeMillis();
        Snapshot request = localRequest;
        if (request.visibleAt(now)) {
            return request;
        }
        Snapshot fallback = automationFallback;
        return fallback.kind() == ActivityKind.AUTOMATION ? fallback : Snapshot.noneAt(now);
    }

    public static void receiveRemote(UUID playerId, Snapshot snapshot) {
        if (playerId == null || snapshot == null || snapshot.kind() == ActivityKind.NONE) {
            if (playerId != null) {
                REMOTE.remove(playerId);
            }
            return;
        }
        REMOTE.put(playerId, snapshot);
        REMOTE_V1_SEEN.put(playerId, System.currentTimeMillis());
    }

    public static void receiveLegacyRemote(
            UUID playerId,
            boolean visible,
            String semanticState,
            long updatedAtMillis
    ) {
        if (playerId == null) {
            return;
        }
        Long v1Seen = REMOTE_V1_SEEN.get(playerId);
        if (v1Seen != null && System.currentTimeMillis() - v1Seen <= STALE_AFTER_MILLIS) {
            return;
        }
        if (!visible) {
            REMOTE.remove(playerId);
            return;
        }
        String normalized = normalize(semanticState);
        REMOTE.put(playerId, new Snapshot(
                ActivityKind.AUTOMATION,
                normalized,
                updatedAtMillis,
                !"idle".equals(normalized)
        ));
    }

    public static void removeRemote(UUID playerId) {
        if (playerId != null) {
            REMOTE.remove(playerId);
            REMOTE_V1_SEEN.remove(playerId);
        }
    }

    public static Snapshot snapshotFor(PlayerEntity player) {
        return player == null ? Snapshot.none() : snapshotFor(player.getUuid());
    }

    public static Snapshot snapshotFor(UUID playerId) {
        if (playerId == null) {
            return Snapshot.none();
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null && playerId.equals(client.player.getUuid())) {
            return localSnapshot();
        }
        Snapshot remote = REMOTE.get(playerId);
        if (remote == null) {
            return Snapshot.none();
        }
        long now = System.currentTimeMillis();
        if (now - remote.updatedAtMillis() > STALE_AFTER_MILLIS || !remote.visibleAt(now)) {
            REMOTE.remove(playerId);
            REMOTE_V1_SEEN.remove(playerId);
            return Snapshot.noneAt(now);
        }
        return remote;
    }

    public static boolean visibleFor(UUID playerId) {
        return snapshotFor(playerId).kind() != ActivityKind.NONE;
    }

    public static int colorFor(UUID playerId) {
        return color(snapshotFor(playerId));
    }

    public static int colorFor(PlayerEntity player) {
        return color(snapshotFor(player));
    }

    public static int color(Snapshot snapshot) {
        if (snapshot == null || snapshot.kind() == ActivityKind.NONE) {
            return 0xFF9AA0A6;
        }
        String state = snapshot.semanticState();
        if ("completed".equals(state) || "complete".equals(state)) {
            return 0xFF55FF55;
        }
        if ("failed".equals(state) || "blocked".equals(state) || "cancelled".equals(state)) {
            return 0xFFFF5555;
        }
        if ("idle".equals(state) || "header".equals(state)) {
            return 0xFF9AA0A6;
        }
        return AutomationStateColors.color(state);
    }

    public static ActivityKind parseKind(String value) {
        if (value == null) {
            return ActivityKind.NONE;
        }
        try {
            return ActivityKind.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ActivityKind.NONE;
        }
    }

    private static String normalize(String state) {
        String normalized = AutomationStateColors.normalizeState(state);
        return normalized == null || normalized.isBlank() ? "idle" : normalized;
    }

    public enum ActivityKind {
        NONE,
        ASK,
        AUTOMATION
    }

    public record Snapshot(
            ActivityKind kind,
            String semanticState,
            long updatedAtMillis,
            boolean active
    ) {
        public Snapshot {
            kind = kind == null ? ActivityKind.NONE : kind;
            semanticState = normalize(semanticState);
            updatedAtMillis = updatedAtMillis <= 0L ? System.currentTimeMillis() : updatedAtMillis;
        }

        public static Snapshot none() {
            return noneAt(System.currentTimeMillis());
        }

        public static Snapshot noneAt(long now) {
            return new Snapshot(ActivityKind.NONE, "idle", now, false);
        }

        public boolean visibleAt(long now) {
            if (this.kind == ActivityKind.NONE) {
                return false;
            }
            if (this.active || this.kind == ActivityKind.AUTOMATION && "idle".equals(this.semanticState)) {
                return true;
            }
            return now - this.updatedAtMillis <= TERMINAL_VISIBILITY_MILLIS;
        }
    }
}
