package com.spirit.koil.api.automation.cli;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AutomationPresenceState {
    private static final long STALE_AFTER_MS = 5_000L;
    private static final Map<UUID, PresenceSnapshot> REMOTE = new ConcurrentHashMap<>();
    private static volatile String localState = "idle";
    private static volatile String localDetail = "";
    private static volatile long localUpdatedAt;
    private static volatile boolean localAutomationMode;

    private AutomationPresenceState() {
    }

    public static void updateLocal(String state, String detail) {
        localState = normalize(state);
        localDetail = clean(detail);
        localUpdatedAt = System.currentTimeMillis();
    }

    public static void updateLocalMode(boolean enabled) {
        localAutomationMode = enabled;
        localUpdatedAt = System.currentTimeMillis();
    }

    public static void receiveRemote(UUID uuid, boolean automationMode, String state, String detail, long updatedAt) {
        if (uuid == null) {
            return;
        }
        String normalized = normalize(state);
        if (!automationMode) {
            REMOTE.remove(uuid);
            return;
        }
        REMOTE.put(uuid, new PresenceSnapshot(automationMode, normalized, clean(detail), updatedAt <= 0L ? System.currentTimeMillis() : updatedAt));
    }

    public static void removeRemote(UUID uuid) {
        if (uuid != null) {
            REMOTE.remove(uuid);
        }
    }

    public static String stateFor(PlayerEntity player) {
        PresenceSnapshot snapshot = snapshotFor(player);
        return snapshot == null ? "idle" : snapshot.state();
    }

    public static String detailFor(PlayerEntity player) {
        PresenceSnapshot snapshot = snapshotFor(player);
        return snapshot == null ? "" : snapshot.detail();
    }

    public static boolean hasActiveState(PlayerEntity player) {
        PresenceSnapshot snapshot = snapshotFor(player);
        return snapshot != null && !"idle".equals(snapshot.state());
    }

    public static boolean automationModeFor(PlayerEntity player) {
        PresenceSnapshot snapshot = snapshotFor(player);
        return snapshot != null && snapshot.automationMode();
    }

    public static int colorFor(PlayerEntity player) {
        PresenceSnapshot snapshot = snapshotFor(player);
        if (snapshot == null || !snapshot.automationMode()) {
            return AutomationStateColors.color("idle");
        }
        if ("idle".equals(snapshot.state()) || "header".equals(snapshot.state())) {
            return 0xFF9AA0A6;
        }
        return AutomationStateColors.color(snapshot.state());
    }

    public static String localState() {
        return localState;
    }

    public static String localDetail() {
        return localDetail;
    }

    public static long localUpdatedAt() {
        return localUpdatedAt;
    }

    public static boolean localAutomationMode() {
        return localAutomationMode;
    }

    private static PresenceSnapshot snapshotFor(PlayerEntity player) {
        if (player == null) {
            return null;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null && client.player.getUuid().equals(player.getUuid())) {
            return localAutomationMode ? new PresenceSnapshot(true, localState, localDetail, localUpdatedAt) : null;
        }
        PresenceSnapshot remote = REMOTE.get(player.getUuid());
        if (remote == null) {
            return null;
        }
        if (System.currentTimeMillis() - remote.updatedAt() > STALE_AFTER_MS) {
            REMOTE.remove(player.getUuid());
            return null;
        }
        return remote;
    }

    private static String normalize(String state) {
        return AutomationStateColors.normalizeState(state);
    }

    private static String clean(String detail) {
        return detail == null ? "" : detail.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
    }

    private record PresenceSnapshot(boolean automationMode, String state, String detail, long updatedAt) {
    }
}
