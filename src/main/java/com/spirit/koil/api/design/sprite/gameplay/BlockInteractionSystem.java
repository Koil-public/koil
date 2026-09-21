package com.spirit.koil.api.design.sprite.gameplay;

import com.spirit.koil.api.design.sprite.actor.Actor;
import com.spirit.koil.api.design.sprite.actor.ActorWorld;
import com.spirit.koil.api.design.sprite.actor.ItemActor;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneEvent;
import com.spirit.koil.api.design.sprite.core.SceneEventBus;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.systems.MultipartBlockSystem;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import net.minecraft.block.BellBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSetType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ComparatorBlock;
import net.minecraft.block.DaylightDetectorBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.ComposterBlock;
import net.minecraft.block.JukeboxBlock;
import net.minecraft.block.RepeaterBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WoodType;
import net.minecraft.block.enums.ComparatorMode;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Scene-native right-click behavior for non-UI block functions.
 *
 * <p>This intentionally does not open crafting/container screens. It owns stateful
 * physical interactions that can exist in a detached Koil scene: openables,
 * switches, buttons, redstone-gate settings, bells, note pitch and chest lids.</p>
 */
public final class BlockInteractionSystem {
    private final BlockGrid blocks;
    private final MultipartBlockSystem multipart;
    private final ActorWorld actors;
    private final ChestSystem chests;
    private final JukeboxSystem jukeboxes;
    private final BellSystem bells;
    private final SceneProjection projection;
    private final SceneEventBus events;
    private final LongSupplier gameTick;
    private final Map<SceneCellPos, Long> buttonReleaseTicks = new HashMap<>();
    private static final Map<Class<?>, Field> BLOCK_SET_FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> WOOD_TYPE_FIELDS = new ConcurrentHashMap<>();

    public BlockInteractionSystem(BlockGrid blocks, MultipartBlockSystem multipart,
                                      ActorWorld actors, ChestSystem chests, JukeboxSystem jukeboxes,
                                      BellSystem bells, SceneProjection projection, SceneEventBus events,
                                      LongSupplier gameTick) {
        this.blocks = blocks;
        this.multipart = multipart;
        this.actors = actors;
        this.chests = chests;
        this.jukeboxes = jukeboxes;
        this.bells = bells;
        this.projection = projection;
        this.events = events;
        this.gameTick = gameTick;
    }

    /** Returns true when bare-use for this block family belongs to the scene layer.
     *  UI-opening containers are intentionally excluded unless their physical state
     *  has a detached interaction here (for example chest lids and jukebox records). */
    public boolean owns(BlockState state) {
        if (state == null || state.isAir()) return false;
        Block block = state.getBlock();
        if (GameplayAdapterRegistry.blockAdapter(block) != null) return true;
        return block instanceof ChestBlock || block instanceof EnderChestBlock
                || block instanceof JukeboxBlock || block instanceof ComposterBlock
                || block instanceof DoorBlock || block instanceof TrapdoorBlock
                || block instanceof FenceGateBlock || block instanceof LeverBlock
                || block instanceof ButtonBlock || block instanceof RepeaterBlock
                || block instanceof ComparatorBlock || block instanceof DaylightDetectorBlock
                || block instanceof NoteBlock || block instanceof BellBlock;
    }

    public boolean useBlock(SceneCellPos requested) {
        return useBlock(requested, Float.NaN, Float.NaN);
    }

    /** Explicit block use with optional projected pointer coordinates. */
    public boolean useBlock(SceneCellPos requested, float pointerX, float pointerY) {
        if (requested == null) return false;
        SceneCellPos pos = multipart == null ? requested : multipart.anchor(requested);
        BlockState state = blocks.getBlockState(pos);
        if (state == null || state.isAir()) return false;
        com.spirit.koil.api.design.sprite.world.BlockCell authoredCell = blocks.get(pos);
        if (authoredCell != null && !authoredCell.runtimeBoolean("interactive", true)) return false;
        Block block = state.getBlock();
        GameplayAdapterRegistry.BlockAdapter custom = GameplayAdapterRegistry.blockAdapter(block);
        if (custom != null && custom.use(new GameplayAdapterRegistry.BlockContext(
                pos, state, blocks, projection, events, gameTick.getAsLong()))) return true;

        if (chests != null && chests.toggle(requested)) return true;
        if (block instanceof JukeboxBlock && jukeboxes != null && jukeboxes.eject(pos)) return true;

        // A full composter ejects one bone meal item without opening a UI.
        if (block instanceof ComposterBlock && state.contains(ComposterBlock.LEVEL)
                && state.get(ComposterBlock.LEVEL) == 8) {
            setState(pos, state.with(ComposterBlock.LEVEL, 0), "composter_empty");
            if (actors != null) {
                long id = actors.allocateNativeId();
                ItemActor drop = new ItemActor(id, Actor.Authority.SCENE, new ItemStack(Items.BONE_MEAL),
                        projection.cellCenterScreenX(pos), projection.cellCenterScreenY(pos) - 6.0F,
                        projection.depthCoordinate(pos));
                drop.setVelocity(0.0F, -74.0F);
                actors.put(drop);
            }
            play(SoundEvents.BLOCK_COMPOSTER_EMPTY, 0.30F, 1.0F);
            return true;
        }

        if (block instanceof DoorBlock) {
            if (!DoorBlock.canOpenByHand(state) || !state.contains(Properties.OPEN)) return false;
            boolean open = !state.get(Properties.OPEN);
            setState(pos, state.with(Properties.OPEN, open), "door_" + (open ? "open" : "close"));
            if (multipart != null) multipart.synchronizeDoorNow(pos);
            BlockSetType type = ((DoorBlock) block).getBlockSetType();
            play(open ? type.doorOpen() : type.doorClose(), 0.32F, 1.0F);
            return true;
        }

        if (block instanceof TrapdoorBlock) {
            BlockSetType type = blockSetType(block);
            if (type != null && !type.canOpenByHand()) return false;
            if (!state.contains(Properties.OPEN)) return false;
            boolean open = !state.get(Properties.OPEN);
            setState(pos, state.with(Properties.OPEN, open), "trapdoor_" + (open ? "open" : "close"));
            play(type == null ? null : (open ? type.trapdoorOpen() : type.trapdoorClose()), 0.32F, 1.0F);
            return true;
        }

        if (block instanceof FenceGateBlock) {
            if (!state.contains(Properties.OPEN)) return false;
            boolean open = !state.get(Properties.OPEN);
            setState(pos, state.with(Properties.OPEN, open), "fence_gate_" + (open ? "open" : "close"));
            WoodType type = woodType(block);
            play(type == null ? null : (open ? type.fenceGateOpen() : type.fenceGateClose()), 0.32F, 1.0F);
            return true;
        }

        if (block instanceof LeverBlock && state.contains(Properties.POWERED)) {
            boolean powered = !state.get(Properties.POWERED);
            setState(pos, state.with(Properties.POWERED, powered), "lever_" + (powered ? "on" : "off"));
            play(SoundEvents.BLOCK_LEVER_CLICK, 0.30F, powered ? 0.6F : 0.5F);
            return true;
        }

        if (block instanceof ButtonBlock && state.contains(Properties.POWERED)) {
            if (state.get(Properties.POWERED)) return true;
            setState(pos, state.with(Properties.POWERED, true), "button_press");
            int ticks = buttonPressTicks(block);
            buttonReleaseTicks.put(pos, gameTick.getAsLong() + ticks);
            BlockSetType type = blockSetType(block);
            play(type == null ? null : type.buttonClickOn(), 0.30F, 1.0F);
            return true;
        }

        if (block instanceof RepeaterBlock && state.contains(Properties.DELAY)) {
            int delay = state.get(Properties.DELAY);
            int next = delay >= 4 ? 1 : delay + 1;
            setState(pos, state.with(Properties.DELAY, next), "repeater_delay_" + next);
            play(SoundEvents.BLOCK_COMPARATOR_CLICK, 0.26F, 1.0F);
            return true;
        }

        if (block instanceof ComparatorBlock && state.contains(Properties.COMPARATOR_MODE)) {
            ComparatorMode mode = state.get(Properties.COMPARATOR_MODE);
            ComparatorMode next = mode == ComparatorMode.SUBTRACT ? ComparatorMode.COMPARE : ComparatorMode.SUBTRACT;
            setState(pos, state.with(Properties.COMPARATOR_MODE, next), "comparator_" + next.asString());
            play(SoundEvents.BLOCK_COMPARATOR_CLICK, 0.26F, next == ComparatorMode.SUBTRACT ? 1.05F : 0.95F);
            return true;
        }

        if (block instanceof DaylightDetectorBlock && state.contains(Properties.INVERTED)) {
            boolean inverted = !state.get(Properties.INVERTED);
            setState(pos, state.with(Properties.INVERTED, inverted), "daylight_detector_" + (inverted ? "inverted" : "normal"));
            return true;
        }

        if (block instanceof NoteBlock && state.contains(Properties.NOTE)) {
            int note = state.get(Properties.NOTE);
            int next = (note + 1) % 25;
            setState(pos, state.with(Properties.NOTE, next), "note_" + next);
            // Instrument selection depends on the support block and eventually
            // belongs to the dedicated sound/game-event layer. Do not invent an
            // incorrect instrument here; the state change itself is authoritative.
            return true;
        }

        if (block instanceof BellBlock) {
            if (bells != null) bells.ring(pos, projectedBellHitSide(pos, pointerX));
            play(SoundEvents.BLOCK_BELL_USE, 0.36F, 1.0F);
            publishInteraction(pos, "bell_ring");
            return true;
        }
        return false;
    }

    private Direction projectedBellHitSide(SceneCellPos pos, float pointerX) {
        if (projection == null || pos == null || !Float.isFinite(pointerX)) {
            return projection == null ? Direction.EAST : projection.screenRightDirection();
        }
        float centerX = projection.cellCenterScreenX(pos);
        return pointerX < centerX ? projection.screenLeftDirection() : projection.screenRightDirection();
    }

    public void minecraftTick() {
        if (buttonReleaseTicks.isEmpty()) return;
        long tick = gameTick.getAsLong();
        for (Map.Entry<SceneCellPos, Long> entry : Map.copyOf(buttonReleaseTicks).entrySet()) {
            if (tick < entry.getValue()) continue;
            SceneCellPos pos = entry.getKey();
            buttonReleaseTicks.remove(pos);
            BlockState state = blocks.getBlockState(pos);
            if (!(state.getBlock() instanceof ButtonBlock) || !state.contains(Properties.POWERED)
                    || !state.get(Properties.POWERED)) continue;
            setState(pos, state.with(Properties.POWERED, false), "button_release");
            BlockSetType type = blockSetType(state.getBlock());
            play(type == null ? null : type.buttonClickOff(), 0.30F, 1.0F);
        }
    }

    public void reset() { buttonReleaseTicks.clear(); }

    private void setState(SceneCellPos pos, BlockState state, String action) {
        blocks.setSceneOwned(pos, state);
        publishInteraction(pos, action);
    }

    private void publishInteraction(SceneCellPos pos, String action) {
        if (events != null) events.publish(new SceneEvent.BlockInteraction(pos, action, gameTick.getAsLong()));
    }

    private void play(SoundEvent sound, float volume, float pitch) {
        if (events != null && sound != null) {
            events.publish(new SceneEvent.SoundRequested(sound, volume, pitch, gameTick.getAsLong()));
        }
    }

    private static int buttonPressTicks(Block block) {
        if (!(block instanceof ButtonBlock)) return 20;
        Class<?> type = block.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField("pressTicks");
                field.setAccessible(true);
                return Math.max(1, field.getInt(block));
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
            type = type.getSuperclass();
        }
        return 20;
    }

    private static BlockSetType blockSetType(Block block) {
        if (block == null) return null;
        Field cached = BLOCK_SET_FIELDS.computeIfAbsent(block.getClass(), type -> findField(type, BlockSetType.class));
        if (cached == null) return null;
        try { return (BlockSetType) cached.get(block); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    private static WoodType woodType(Block block) {
        if (block == null) return null;
        Field cached = WOOD_TYPE_FIELDS.computeIfAbsent(block.getClass(), type -> findField(type, WoodType.class));
        if (cached == null) return null;
        try { return (WoodType) cached.get(block); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    private static Field findField(Class<?> root, Class<?> wanted) {
        Class<?> type = root;
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (!wanted.isAssignableFrom(field.getType())) continue;
                try { field.setAccessible(true); } catch (RuntimeException ignored) { }
                return field;
            }
            type = type.getSuperclass();
        }
        return null;
    }
}
