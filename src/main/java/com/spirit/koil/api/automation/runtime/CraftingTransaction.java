package com.spirit.koil.api.automation.runtime;

import java.util.Map;

/** Guarded craft-result contract; executor-specific clicks remain outside this state object. */
public final class CraftingTransaction {
    private final String itemId;
    private final int requestedCount;
    private final int syncId;
    private final int beforeCount;

    private CraftingTransaction(String itemId, int requestedCount, int syncId, int beforeCount) {
        this.itemId = itemId;
        this.requestedCount = requestedCount;
        this.syncId = syncId;
        this.beforeCount = beforeCount;
    }

    public static Result begin(String itemId, int requestedCount, int syncId, boolean cursorEmpty, Map<String, Integer> inventory) {
        if (itemId == null || itemId.isBlank()) return Result.blocked("UNKNOWN_ITEM", "A target item is required.");
        if (syncId < 0) return Result.blocked("SCREEN_MISMATCH", "A synchronized crafting screen is required.");
        if (!cursorEmpty) return Result.blocked("CURSOR_UNSAFE", "The crafting cursor is not empty.");
        int before = inventory == null ? 0 : Math.max(0, inventory.getOrDefault(itemId, 0));
        return Result.started(new CraftingTransaction(itemId, Math.max(1, requestedCount), syncId, before));
    }

    public Result verify(int currentSyncId, boolean cursorEmpty, Map<String, Integer> inventory) {
        if (currentSyncId != syncId) return Result.blocked("SCREEN_MISMATCH", "The crafting screen changed during the transaction.");
        if (!cursorEmpty) return Result.blocked("CURSOR_UNSAFE", "The crafting cursor is not safe after the transaction.");
        int current = inventory == null ? 0 : Math.max(0, inventory.getOrDefault(itemId, 0));
        if (current - beforeCount < requestedCount) return Result.blocked("VERIFICATION_FAILED", "The requested crafted item count was not observed.");
        return Result.completed(this);
    }

    public record Result(CraftingTransaction transaction, String status, String failureCode, String detail) {
        static Result started(CraftingTransaction transaction) { return new Result(transaction, "started", "", "Crafting transaction is guarded."); }
        static Result completed(CraftingTransaction transaction) { return new Result(transaction, "completed", "", "Crafting result and cursor state were verified."); }
        static Result blocked(String code, String detail) { return new Result(null, "blocked", code, detail); }
        public boolean started() { return transaction != null && "started".equals(status); }
    }
}
