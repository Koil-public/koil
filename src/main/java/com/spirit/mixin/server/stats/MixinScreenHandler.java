package com.spirit.mixin.server.stats;

import com.spirit.koil.api.stats.global.GlobalActivityApi;
import com.spirit.koil.api.stats.global.KoilMarketLedger;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;

@Mixin(ScreenHandler.class)
public abstract class MixinScreenHandler {
    @Shadow @Final public DefaultedList<Slot> slots;
    @Unique private static final Map<ScreenHandler, Map<Integer, ItemStack>> koil$beforeSlots = new IdentityHashMap<>();

    @Inject(method = "onSlotClick", at = @At("HEAD"))
    private void koil$captureContainerBefore(int slotIndex, int button, SlotActionType actionType, PlayerEntity player, CallbackInfo info) {
        if (!(player instanceof ServerPlayerEntity serverPlayer) || koil$ignorePlayer(serverPlayer)) {
            return;
        }

        koil$beforeSlots.put((ScreenHandler) (Object) this, koil$snapshotContainerSlots(player));
    }

    @Inject(method = "onSlotClick", at = @At("TAIL"))
    private void koil$captureContainerAfter(int slotIndex, int button, SlotActionType actionType, PlayerEntity player, CallbackInfo info) {
        if (!(player instanceof ServerPlayerEntity serverPlayer) || serverPlayer.getServer() == null || koil$ignorePlayer(serverPlayer)) {
            return;
        }

        Map<Integer, ItemStack> before = koil$beforeSlots.remove((ScreenHandler) (Object) this);

        if (before == null || before.isEmpty()) {
            return;
        }

        String handlerKey = koil$handlerKey();
        boolean stationHandler = koil$isStationHandler(handlerKey);
        boolean exactBlockEntityHandler = koil$isExactBlockEntityHandler(handlerKey);
        boolean recipeOutputTaken = koil$recipeOutputTaken(slotIndex, before);

        for (Map.Entry<Integer, ItemStack> entry : before.entrySet()) {
            int index = entry.getKey();

            if (index < 0 || index >= this.slots.size()) {
                continue;
            }

            Slot slot = this.slots.get(index);
            ItemStack oldStack = entry.getValue();
            ItemStack newStack = slot.getStack();
            koil$recordSlotTransfer(serverPlayer, actionType, handlerKey, index, oldStack, newStack);

            if (!stationHandler || exactBlockEntityHandler) {
                continue;
            }

            if (recipeOutputTaken && koil$decreased(oldStack, newStack) && koil$canInsert(slot, oldStack)) {
                int amount = koil$decreaseAmount(oldStack, newStack);
                GlobalActivityApi.recordStationActivity(serverPlayer, handlerKey, koil$stationInputAction(handlerKey), oldStack, amount);
            }
        }

        if (recipeOutputTaken && slotIndex >= 0 && slotIndex < this.slots.size()) {
            ItemStack output = before.get(slotIndex);

            if (output != null && !output.isEmpty()) {
                GlobalActivityApi.recordStationActivity(serverPlayer, handlerKey, koil$stationOutputAction(handlerKey), output, output.getCount());
            }
        }
    }

    @Unique
    private Map<Integer, ItemStack> koil$snapshotContainerSlots(PlayerEntity player) {
        Map<Integer, ItemStack> snapshot = new LinkedHashMap<>();

        for (int i = 0; i < this.slots.size(); i++) {
            Slot slot = this.slots.get(i);

            if (slot == null || slot.inventory == player.getInventory()) {
                continue;
            }

            snapshot.put(i, slot.getStack().copy());
        }

        return snapshot;
    }

    @Unique
    private void koil$recordSlotTransfer(ServerPlayerEntity player, SlotActionType actionType, String handlerKey, int slotIndex, ItemStack before, ItemStack after) {
        if (before == null) {
            before = ItemStack.EMPTY;
        }

        if (after == null) {
            after = ItemStack.EMPTY;
        }

        if (ItemStack.areItemsEqual(before, after) && before.getCount() == after.getCount()) {
            return;
        }

        if (!before.isEmpty() && (after.isEmpty() || !ItemStack.areItemsEqual(before, after) || before.getCount() > after.getCount())) {
            int amount = koil$decreaseAmount(before, after);
            koil$recordTransfer(player, "container_out", actionType, handlerKey, slotIndex, before, amount);
        }

        if (!after.isEmpty() && (before.isEmpty() || !ItemStack.areItemsEqual(before, after) || after.getCount() > before.getCount())) {
            int amount = before.isEmpty() || !ItemStack.areItemsEqual(before, after) ? after.getCount() : after.getCount() - before.getCount();
            koil$recordTransfer(player, "container_in", actionType, handlerKey, slotIndex, after, amount);
        }
    }

    @Unique
    private void koil$recordTransfer(ServerPlayerEntity player, String action, SlotActionType actionType, String handlerKey, int slotIndex, ItemStack stack, int amount) {
        if (amount <= 0 || stack == null || stack.isEmpty() || player.getServer() == null) {
            return;
        }

        Identifier id = Registries.ITEM.getId(stack.getItem());
        String type = stack.getItem() instanceof BlockItem ? "block" : "item";
        String safeAction = koil$containerAction(action);
        String category = "container_in".equals(safeAction) ? KoilMarketLedger.PRODUCTION : KoilMarketLedger.DEMAND;
        String metric = safeAction + "/" + type + "/" + handlerKey + "/" + actionType.name().toLowerCase(Locale.ROOT) + "/slot_" + slotIndex + "/" + id.getNamespace() + "/" + id.getPath();
        GlobalActivityApi.recordLedgerActivity(player.getServer(), player.getUuid(), player.getGameProfile().getName(), category, metric, amount);
    }

    @Unique
    private String koil$containerAction(String action) {
        String key = action == null ? "" : action.toLowerCase(Locale.ROOT);
        return "container_in".equals(key) ? "container_in" : "container_out";
    }

    @Unique
    private boolean koil$recipeOutputTaken(int slotIndex, Map<Integer, ItemStack> before) {
        if (slotIndex < 0 || slotIndex >= this.slots.size()) {
            return false;
        }

        ItemStack beforeStack = before.get(slotIndex);

        if (beforeStack == null || beforeStack.isEmpty()) {
            return false;
        }

        Slot slot = this.slots.get(slotIndex);
        return slot != null && !koil$canInsert(slot, beforeStack);
    }

    @Unique
    private boolean koil$canInsert(Slot slot, ItemStack stack) {
        try {
            return slot.canInsert(stack);
        } catch (Exception ignored) {
            return true;
        }
    }

    @Unique
    private boolean koil$decreased(ItemStack before, ItemStack after) {
        return koil$decreaseAmount(before, after) > 0;
    }

    @Unique
    private int koil$decreaseAmount(ItemStack before, ItemStack after) {
        if (before == null || before.isEmpty()) {
            return 0;
        }

        if (after == null || after.isEmpty()) {
            return Math.max(0, before.getCount());
        }

        if (!ItemStack.areItemsEqual(before, after)) {
            return Math.max(0, before.getCount());
        }

        return Math.max(0, before.getCount() - after.getCount());
    }

    @Unique
    private String koil$handlerKey() {
        String name = ((ScreenHandler) (Object) this).getClass().getName().toLowerCase(Locale.ROOT);
        int slash = name.lastIndexOf('.');

        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }

        return name.replace('$', '_').replace('/', '_').replace(':', '_');
    }

    @Unique
    private boolean koil$isStationHandler(String handlerKey) {
        String key = handlerKey == null ? "" : handlerKey;
        return key.contains("craft") || key.contains("smith") || key.contains("anvil") || key.contains("grind") || key.contains("stonecut") || key.contains("loom") || key.contains("cartography") || key.contains("beacon") || key.contains("enchant") || key.contains("brew") || key.contains("furnace") || key.contains("smoker") || key.contains("blast") || key.contains("crusher") || key.contains("grinder") || key.contains("macerator") || key.contains("pulverizer") || key.contains("press") || key.contains("sawmill") || key.contains("alloy") || key.contains("machine") || key.contains("processor") || key.contains("assembler") || key.contains("compressor") || key.contains("extractor") || key.contains("separator") || key.contains("mixer") || key.contains("mill");
    }

    @Unique
    private boolean koil$isExactBlockEntityHandler(String handlerKey) {
        String key = handlerKey == null ? "" : handlerKey;
        return key.contains("furnace") || key.contains("smoker") || key.contains("blast") || key.contains("brew");
    }

    @Unique
    private String koil$stationInputAction(String handlerKey) {
        String key = handlerKey == null ? "" : handlerKey;

        if (key.contains("smith")) {
            return "smith_input";
        }

        if (key.contains("stonecut")) {
            return "stonecut_input";
        }

        if (key.contains("craft")) {
            return "craft_input";
        }

        return "station_input_consumed";
    }

    @Unique
    private String koil$stationOutputAction(String handlerKey) {
        String key = handlerKey == null ? "" : handlerKey;

        if (key.contains("smith")) {
            return "smith_output";
        }

        if (key.contains("stonecut")) {
            return "stonecut_output";
        }

        if (key.contains("craft")) {
            return "craft_output";
        }

        return "station_output_taken";
    }

    @Unique
    private boolean koil$ignorePlayer(ServerPlayerEntity player) {
        try {
            return player.isCreative() || player.isSpectator();
        } catch (Exception ignored) {
            return false;
        }
    }
}
