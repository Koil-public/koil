package com.spirit.koil.api.design.sprite.gameplay;

import com.spirit.koil.api.design.sprite.actor.Actor;
import com.spirit.koil.api.design.sprite.actor.ActorWorld;
import com.spirit.koil.api.design.sprite.actor.ItemActor;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneEvent;
import com.spirit.koil.api.design.sprite.core.SceneEventBus;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import net.minecraft.block.BlockState;
import net.minecraft.block.JukeboxBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MusicDiscItem;
import net.minecraft.state.property.Properties;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Detached jukebox runtime. Record storage is block-entity style scene state,
 * while HAS_RECORD remains authoritative in the Minecraft BlockState.
 */
public final class JukeboxSystem {
    private final BlockGrid blocks;
    private final ActorWorld actors;
    private final SceneProjection projection;
    private final SceneEventBus events;
    private final LongSupplier gameTick;
    private final Map<SceneCellPos, ItemStack> records = new HashMap<>();

    public JukeboxSystem(BlockGrid blocks, ActorWorld actors, SceneProjection projection,
                             SceneEventBus events, LongSupplier gameTick) {
        this.blocks = blocks;
        this.actors = actors;
        this.projection = projection;
        this.events = events;
        this.gameTick = gameTick;
    }

    public boolean insert(SceneCellPos pos, ItemStack source) {
        if (pos == null || source == null || source.isEmpty() || !(source.getItem() instanceof MusicDiscItem disc)) return false;
        BlockState state = blocks.getBlockState(pos);
        if (!(state.getBlock() instanceof JukeboxBlock) || !state.contains(Properties.HAS_RECORD)
                || state.get(Properties.HAS_RECORD)) return false;

        ItemStack stored = source.copy();
        stored.setCount(1);
        records.put(pos, stored);
        blocks.setSceneOwned(pos, state.with(Properties.HAS_RECORD, true));
        if (events != null) {
            events.publish(new SceneEvent.SoundRequested(disc.getSound(), 0.42F, 1.0F, gameTick.getAsLong()));
            events.publish(new SceneEvent.BlockInteraction(pos, "jukebox_insert", gameTick.getAsLong()));
        }
        return true;
    }

    public boolean eject(SceneCellPos pos) {
        if (pos == null) return false;
        BlockState state = blocks.getBlockState(pos);
        if (!(state.getBlock() instanceof JukeboxBlock) || !state.contains(Properties.HAS_RECORD)
                || !state.get(Properties.HAS_RECORD)) return false;

        ItemStack stored = records.remove(pos);
        blocks.setSceneOwned(pos, state.with(Properties.HAS_RECORD, false));
        if (stored != null && !stored.isEmpty()) {
            long id = actors.allocateNativeId();
            ItemActor item = new ItemActor(id, Actor.Authority.SCENE, stored,
                    projection.cellCenterScreenX(pos), projection.cellCenterScreenY(pos) - 5.0F,
                    projection.depthCoordinate(pos));
            item.setVelocity(0.0F, -72.0F);
            item.setAngularVelocity(120.0F);
            actors.put(item);
            if (stored.getItem() instanceof MusicDiscItem disc && events != null) {
                events.publish(new SceneEvent.SoundStopRequested(disc.getSound(), gameTick.getAsLong()));
            }
        }
        if (events != null) events.publish(new SceneEvent.BlockInteraction(pos, "jukebox_eject", gameTick.getAsLong()));
        return true;
    }

    public ItemStack record(SceneCellPos pos) {
        ItemStack stack = records.get(pos);
        return stack == null ? ItemStack.EMPTY : stack.copy();
    }

    /** Drops stale runtime records when the jukebox was removed externally. */
    public void minecraftTick() {
        for (SceneCellPos pos : java.util.List.copyOf(records.keySet())) {
            BlockState state = blocks.getBlockState(pos);
            if (!(state.getBlock() instanceof JukeboxBlock) || !state.contains(Properties.HAS_RECORD)
                    || !state.get(Properties.HAS_RECORD)) {
                ItemStack stale = records.remove(pos);
                stopRecord(stale);
            }
        }
    }

    public void reset() {
        for (ItemStack stack : java.util.List.copyOf(records.values())) stopRecord(stack);
        records.clear();
    }

    private void stopRecord(ItemStack stack) {
        if (stack == null || stack.isEmpty() || events == null) return;
        if (stack.getItem() instanceof MusicDiscItem disc) {
            events.publish(new SceneEvent.SoundStopRequested(disc.getSound(), gameTick.getAsLong()));
        }
    }
}
