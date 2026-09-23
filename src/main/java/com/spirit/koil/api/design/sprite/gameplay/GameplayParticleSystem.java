package com.spirit.koil.api.design.sprite.gameplay;

import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneEvent;
import com.spirit.koil.api.design.sprite.core.SceneEventBus;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.CandleCakeBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;

import java.util.function.LongSupplier;

/**
 * Visual consequence service for detached Minecraft gameplay.
 *
 * <p>The service only publishes typed particle requests. It never mutates block,
 * item, projectile or redstone state. This keeps Minecraft gameplay authoritative
 * in KoilScene while allowing the UI FX layer to reuse every registered vanilla
 * or modded particle adapter.</p>
 */
public final class GameplayParticleSystem {
    private static final Identifier FLAME = id("flame");
    private static final Identifier SOUL_FIRE_FLAME = id("soul_fire_flame");
    private static final Identifier SMOKE = id("smoke");
    private static final Identifier CAMPFIRE_COSY_SMOKE = id("campfire_cosy_smoke");
    private static final Identifier CAMPFIRE_SIGNAL_SMOKE = id("campfire_signal_smoke");

    private final BlockGrid blocks;
    private final SceneProjection projection;
    private final SceneEventBus events;
    private final LongSupplier gameTick;

    public GameplayParticleSystem(BlockGrid blocks, SceneProjection projection,
                                      SceneEventBus events, LongSupplier gameTick) {
        this.blocks = blocks;
        this.projection = projection;
        this.events = events;
        this.gameTick = gameTick;
        if (events != null) {
            events.subscribe(event -> {
                if (event instanceof SceneEvent.ProjectileImpact impact) emitProjectileImpact(impact);
            });
        }
    }

    public void minecraftTick() {
        long tick = gameTick.getAsLong();
        if (events == null || blocks.blockCount() == 0) return;
        for (BlockGrid.Entry entry : blocks.entries()) {
            if (entry == null || entry.cell() == null) continue;
            BlockState state = entry.cell().blockState();
            if (state == null || state.isAir()) continue;
            SceneCellPos pos = entry.position();
            int phase = Math.floorMod(pos.x() * 31 + pos.y() * 17 + pos.depth() * 13, 20);

            if (state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)) {
                if (Math.floorMod((int) tick + phase, 3) == 0) {
                    emitAt(pos, state.isOf(Blocks.SOUL_FIRE) ? SOUL_FIRE_FLAME : FLAME, 2, 5.0F, 6.0F, 0.85F);
                }
                if (Math.floorMod((int) tick + phase, 7) == 0) emitAt(pos, SMOKE, 1, 4.0F, 5.0F, 0.72F);
                continue;
            }

            if (state.getBlock() instanceof CampfireBlock && state.contains(Properties.LIT) && state.get(Properties.LIT)) {
                if (Math.floorMod((int) tick + phase, 8) == 0) {
                    boolean signal = state.contains(CampfireBlock.SIGNAL_FIRE) && state.get(CampfireBlock.SIGNAL_FIRE);
                    emitAt(pos, signal ? CAMPFIRE_SIGNAL_SMOKE : CAMPFIRE_COSY_SMOKE,
                            1, 4.0F, 4.0F, signal ? 1.10F : 0.90F);
                }
                if (Math.floorMod((int) tick + phase, 5) == 0) emitAt(pos, FLAME, 1, 5.0F, 4.0F, 0.72F);
                continue;
            }

            if ((state.getBlock() instanceof CandleBlock || state.getBlock() instanceof CandleCakeBlock)
                    && state.contains(Properties.LIT) && state.get(Properties.LIT)) {
                if (Math.floorMod((int) tick + phase, 6) == 0) emitAt(pos, FLAME, 1, 2.0F, 3.0F, 0.54F);
                if (Math.floorMod((int) tick + phase, 13) == 0) emitAt(pos, SMOKE, 1, 2.0F, 3.0F, 0.48F);
            }
        }
    }


    public void emitItem(ItemStack stack, float x, float y, int depth, int count,
                         float spreadX, float spreadY, float visualScale) {
        if (stack == null || stack.isEmpty() || events == null) return;
        events.publish(new SceneEvent.ParticleRequested(id("item"), x, y, depth,
                Math.max(1, Math.min(64, count)), Math.max(0.0F, spreadX), Math.max(0.0F, spreadY),
                Math.max(0.1F, visualScale), stack, null, gameTick.getAsLong()));
    }

    public void emitBlock(BlockState state, float x, float y, int depth, int count,
                          float spreadX, float spreadY, float visualScale) {
        if (state == null || state.isAir() || events == null) return;
        events.publish(new SceneEvent.ParticleRequested(id("block"), x, y, depth,
                Math.max(1, Math.min(64, count)), Math.max(0.0F, spreadX), Math.max(0.0F, spreadY),
                Math.max(0.1F, visualScale), ItemStack.EMPTY, state, gameTick.getAsLong()));
    }

    private void emitProjectileImpact(SceneEvent.ProjectileImpact impact) {
        if (impact == null || impact.projectileKind() == null) return;
        String kind = impact.projectileKind().toLowerCase(java.util.Locale.ROOT);
        switch (kind) {
            case "snowball" -> emitItem(new ItemStack(Items.SNOWBALL), impact.x(), impact.y(), impact.depth(), 8, 8.0F, 8.0F, 0.52F);
            case "egg" -> emitItem(new ItemStack(Items.EGG), impact.x(), impact.y(), impact.depth(), 8, 8.0F, 8.0F, 0.52F);
            case "experience_bottle" -> {
                emitItem(new ItemStack(Items.EXPERIENCE_BOTTLE), impact.x(), impact.y(), impact.depth(), 8, 8.0F, 8.0F, 0.48F);
                emit(id("effect"), impact.x(), impact.y(), impact.depth(), 8, 12.0F, 12.0F, 0.62F);
            }
            case "potion" -> emit(id("effect"), impact.x(), impact.y(), impact.depth(), 14, 14.0F, 14.0F, 0.62F);
            case "ender_pearl" -> emit(id("portal"), impact.x(), impact.y(), impact.depth(), 18, 12.0F, 12.0F, 0.68F);
            case "firework" -> emit(id("firework"), impact.x(), impact.y(), impact.depth(), 28, 18.0F, 18.0F, 0.75F);
            default -> { }
        }
    }

    public void emitAt(SceneCellPos pos, Identifier particle, int count,
                       float spreadX, float spreadY, float visualScale) {
        if (pos == null || particle == null || events == null) return;
        emit(particle, projection.cellCenterScreenX(pos), projection.cellCenterScreenY(pos),
                projection.depthCoordinate(pos), count, spreadX, spreadY, visualScale);
    }

    public void emit(Identifier particle, float x, float y, int depth, int count,
                     float spreadX, float spreadY, float visualScale) {
        if (particle == null || events == null) return;
        events.publish(new SceneEvent.ParticleRequested(particle, x, y, depth,
                Math.max(1, Math.min(64, count)), Math.max(0.0F, spreadX), Math.max(0.0F, spreadY),
                Math.max(0.1F, visualScale), gameTick.getAsLong()));
    }

    private static Identifier id(String path) { return new Identifier("minecraft", path); }
}
