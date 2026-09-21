package com.spirit.koil.api.design.sprite.gameplay;

import com.spirit.koil.api.design.sprite.actor.Actor;
import com.spirit.koil.api.design.sprite.actor.ActorWorld;
import com.spirit.koil.api.design.sprite.actor.EntityActor;
import com.spirit.koil.api.design.sprite.actor.ItemActor;
import com.spirit.koil.api.design.sprite.actor.PrimedTntActor;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneEvent;
import com.spirit.koil.api.design.sprite.core.SceneEventBus;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.minecraft.MinecraftStateCodec;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import com.spirit.koil.api.design.sprite.world.FluidGrid;
import com.spirit.koil.api.design.sprite.world.FluidCell;
import net.minecraft.block.BeehiveBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.JukeboxBlock;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.CandleCakeBlock;
import net.minecraft.block.ComposterBlock;
import net.minecraft.block.Oxidizable;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.block.TntBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BoneMealItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.BucketItem;
import net.minecraft.item.EntityBucketItem;
import net.minecraft.item.FireChargeItem;
import net.minecraft.item.GlassBottleItem;
import net.minecraft.item.MusicDiscItem;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.HoneycombItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShearsItem;
import net.minecraft.item.TridentItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.registry.Registries;
import net.minecraft.potion.PotionUtil;
import net.minecraft.potion.Potions;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Detached item gameplay router.
 *
 * <p>Behavior is selected from Minecraft item classes/capabilities, not registry-id
 * text. Vanilla-family mod subclasses therefore inherit the same detached behavior.
 * Exact custom item classes can register a profile in KoilItemCapabilityRegistry.</p>
 *
 * <p>This system owns non-UI interactions only. Container screens, crafting UIs and
 * other player-screen behaviors deliberately do not live here.</p>
 */
public final class ItemInteractionSystem {
    private record ChargeUse(long startTick, float targetX, float targetY) { }

    private static final Map<Class<?>, Field> BUCKET_FLUID_FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> ENTITY_BUCKET_TYPE_FIELDS = new ConcurrentHashMap<>();
    private static volatile Field axeStrippedBlocksField;

    private final BlockGrid blocks;
    private final FluidGrid fluids;
    private final ActorWorld actors;
    private final SceneProjection projection;
    private final ProjectileSystem projectiles;
    private final JukeboxSystem jukeboxes;
    private final GameplayParticleSystem particles;
    private final SceneEventBus events;
    private final LongSupplier gameTick;
    private final Map<Long, ChargeUse> chargedUses = new ConcurrentHashMap<>();
    private final Random random = new Random(0x4B4F494C5F495445L);

    public ItemInteractionSystem(BlockGrid blocks, FluidGrid fluids,
                                     ActorWorld actors, SceneProjection projection,
                                     ProjectileSystem projectiles, JukeboxSystem jukeboxes,
                                     GameplayParticleSystem particles, SceneEventBus events,
                                     LongSupplier gameTick) {
        this.blocks = blocks;
        this.fluids = fluids;
        this.actors = actors;
        this.projection = projection;
        this.projectiles = projectiles;
        this.jukeboxes = jukeboxes;
        this.particles = particles;
        this.events = events;
        this.gameTick = gameTick;
    }

    /** Returns true when the new scene gameplay layer owns this item family.
     *  A false action result then means "not valid here", not "try legacy logic". */
    public boolean owns(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        if (GameplayAdapterRegistry.itemAdapter(item) != null) return true;
        if (item == Items.GLOWSTONE || item == Items.HONEYCOMB) return true;
        return ItemCapabilityRegistry.profile(item).sceneHandled();
    }

    /** Right-button press in empty space or over the item itself. */
    public boolean beginUse(long actorId, float targetX, float targetY) {
        ItemActor actor = itemActor(actorId);
        if (actor == null || actor.stack().isEmpty()) return false;
        ItemStack stack = actor.stack();
        GameplayAdapterRegistry.ItemAdapter custom = GameplayAdapterRegistry.itemAdapter(stack.getItem());
        if (custom != null && custom.beginUse(itemContext(actor, null, targetX, targetY))) return true;
        ItemCapabilityRegistry.Profile profile = ItemCapabilityRegistry.profile(stack.getItem());
        if (!profile.sceneHandled()) return false;

        if (profile.family() == ItemCapabilityRegistry.UseFamily.THROW_IMMEDIATE) {
            actor.setUseVisualState(false, 0.0F);
            return launchAndConsume(actor, profile.projectile(), targetX, targetY, 1.0F);
        }
        if (profile.family() == ItemCapabilityRegistry.UseFamily.CHARGE_RELEASE) {
            if (stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack)) {
                ItemStack fired = stack.copy();
                CrossbowItem.setCharged(fired, false);
                replaceStack(actor, fired);
                actor.setUseVisualState(false, 0.0F);
                sound(SoundEvents.ITEM_CROSSBOW_SHOOT, 0.42F, 1.0F);
                return launchAndConsume(actor, profile.projectile(), targetX, targetY, 1.0F);
            }
            chargedUses.put(actorId, new ChargeUse(gameTick.getAsLong(), targetX, targetY));
            actor.setUseVisualState(true, 0.0F);
            if (stack.getItem() instanceof CrossbowItem) sound(SoundEvents.ITEM_CROSSBOW_LOADING_START, 0.30F, 1.0F);
            publishInteraction(actorId, "charge_begin");
            return true;
        }
        return false;
    }

    /** Right-button release. Bows/tridents launch; crossbows finish loading and remain charged. */
    public boolean releaseUse(long actorId, float targetX, float targetY) {
        ItemActor actor = itemActor(actorId);
        ChargeUse charge = chargedUses.remove(actorId);
        if (actor == null || charge == null || actor.stack().isEmpty()) return false;
        ItemStack stack = actor.stack();
        GameplayAdapterRegistry.ItemAdapter custom = GameplayAdapterRegistry.itemAdapter(stack.getItem());
        if (custom != null && custom.releaseUse(itemContext(actor, null, targetX, targetY))) {
            actor.setUseVisualState(false, 0.0F);
            return true;
        }
        ItemCapabilityRegistry.Profile profile = ItemCapabilityRegistry.profile(stack.getItem());
        if (profile.family() != ItemCapabilityRegistry.UseFamily.CHARGE_RELEASE) {
            actor.setUseVisualState(false, 0.0F);
            return false;
        }
        long heldTicks = Math.max(1L, gameTick.getAsLong() - charge.startTick());

        if (stack.getItem() instanceof CrossbowItem) {
            int pullTime = Math.max(1, CrossbowItem.getPullTime(stack));
            if (heldTicks < pullTime) {
                actor.setUseVisualState(false, 0.0F);
                publishInteraction(actorId, "crossbow_load_cancel");
                return true;
            }
            ItemStack charged = stack.copy();
            CrossbowItem.setCharged(charged, true);
            replaceStack(actor, charged);
            actor.setUseVisualState(false, 1.0F);
            sound(SoundEvents.ITEM_CROSSBOW_LOADING_END, 0.34F, 1.0F);
            publishInteraction(actorId, "crossbow_charged");
            return true;
        }

        float strength = stack.getItem() instanceof BowItem
                ? BowItem.getPullProgress((int) Math.min(Integer.MAX_VALUE, heldTicks))
                : chargeStrength(heldTicks);
        actor.setUseVisualState(false, 0.0F);
        if (strength < 0.10F) {
            publishInteraction(actorId, "charge_cancel");
            return true;
        }
        if (stack.getItem() instanceof BowItem) sound(SoundEvents.ENTITY_ARROW_SHOOT, 0.34F, 1.0F);
        return launchAndConsume(actor, profile.projectile(), targetX, targetY, strength);
    }

    /** Explicit item use against a scene cell. Physical contact alone never calls this. */
    public boolean useOnBlock(long actorId, SceneCellPos pos) {
        ItemActor actor = itemActor(actorId);
        if (actor == null || pos == null || actor.stack().isEmpty()) return false;
        ItemStack stack = actor.stack();
        Item item = stack.getItem();
        BlockState state = blocks.getBlockState(pos);
        com.spirit.koil.api.design.sprite.world.BlockCell authoredCell = blocks.get(pos);
        if (authoredCell != null && !authoredCell.runtimeBoolean("interactive", true)) return false;
        GameplayAdapterRegistry.ItemAdapter custom = GameplayAdapterRegistry.itemAdapter(item);
        if (custom != null && custom.useOnBlock(itemContext(actor, pos,
                projection.cellCenterScreenX(pos), projection.cellCenterScreenY(pos)))) return true;

        // These exact interactions are defined by the target block/item pair and
        // are intentionally checked before broad Java item families.
        if (item == Items.GLOWSTONE && state.getBlock() instanceof RespawnAnchorBlock
                && state.contains(RespawnAnchorBlock.CHARGES)) {
            int charges = state.get(RespawnAnchorBlock.CHARGES);
            if (charges < RespawnAnchorBlock.MAX_CHARGES) {
                blocks.setSceneOwned(pos, state.with(RespawnAnchorBlock.CHARGES, charges + 1));
                consume(actor, 1);
                sound(SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.34F, 1.0F);
            }
            return true;
        }

        if (state.getBlock() instanceof ComposterBlock && tryCompost(actor, pos, state)) return true;

        if (item instanceof MusicDiscItem && state.getBlock() instanceof JukeboxBlock && jukeboxes != null) {
            if (jukeboxes.insert(pos, stack)) {
                consume(actor, 1);
                publishInteraction(actorId, "jukebox_insert");
                return true;
            }
        }

        if (item instanceof GlassBottleItem && useGlassBottle(actor, pos, state)) return true;

        if (item instanceof SpawnEggItem egg) {
            SceneCellPos spawnPos = state == null || state.isAir() || state.isReplaceable()
                    ? pos : projection.interactionOffset(pos, Direction.UP);
            return spawnFromEgg(actor, egg, spawnPos);
        }

        if (item == Items.HONEYCOMB) {
            Optional<BlockState> waxed = HoneycombItem.getWaxedState(state);
            if (waxed.isPresent()) {
                blocks.setSceneOwned(pos, waxed.get());
                consume(actor, 1);
                sound(SoundEvents.ITEM_HONEYCOMB_WAX_ON, 0.30F, 1.0F);
                emit(pos, "wax_on", 10, 7.0F, 7.0F, 0.85F);
                publishInteraction(actorId, "wax_block");
                return true;
            }
        }

        ItemCapabilityRegistry.Profile profile = ItemCapabilityRegistry.profile(item);
        return switch (profile.family()) {
            case BUCKET -> useBucket(actor, pos);
            case BLOCK_PLACEMENT -> placeBlock(actor, pos);
            case TOOL -> useTool(actor, pos, state);
            case FIRE_STARTER -> useFireStarter(actor, pos, state);
            case BONE_MEAL -> useBoneMeal(actor, pos, state);
            case SHEARS -> useShears(actor, pos, state);
            case GLASS_BOTTLE -> useGlassBottle(actor, pos, state);
            case MUSIC_DISC -> state.getBlock() instanceof JukeboxBlock && jukeboxes != null
                    && jukeboxes.insert(pos, stack);
            case SPAWN_EGG -> item instanceof SpawnEggItem egg
                    && spawnFromEgg(actor, egg, projection.interactionOffset(pos, Direction.UP));
            default -> false;
        };
    }

    /** Places/uses at an explicit empty target cell, useful when the cursor is not over a legacy proxy. */
    public boolean useAtCell(long actorId, SceneCellPos target) {
        ItemActor actor = itemActor(actorId);
        if (actor == null || target == null || actor.stack().isEmpty()) return false;
        GameplayAdapterRegistry.ItemAdapter custom = GameplayAdapterRegistry.itemAdapter(actor.stack().getItem());
        if (custom != null && custom.useAtCell(itemContext(actor, target,
                projection.cellCenterScreenX(target), projection.cellCenterScreenY(target)))) return true;
        ItemCapabilityRegistry.Profile profile = ItemCapabilityRegistry.profile(actor.stack().getItem());
        if (profile.family() == ItemCapabilityRegistry.UseFamily.BLOCK_PLACEMENT) return placeBlock(actor, target);
        if (profile.family() == ItemCapabilityRegistry.UseFamily.BUCKET) return useBucket(actor, target);
        if (profile.family() == ItemCapabilityRegistry.UseFamily.GLASS_BOTTLE) return useGlassBottle(actor, target, blocks.getBlockState(target));
        if (profile.family() == ItemCapabilityRegistry.UseFamily.SPAWN_EGG
                && actor.stack().getItem() instanceof SpawnEggItem egg) return spawnFromEgg(actor, egg, target);
        if (profile.family() == ItemCapabilityRegistry.UseFamily.FIRE_STARTER) return igniteEmptyCell(actor, target);
        return false;
    }

    private GameplayAdapterRegistry.ItemContext itemContext(ItemActor actor, SceneCellPos cell,
                                                                  float targetX, float targetY) {
        return new GameplayAdapterRegistry.ItemContext(actor, cell, targetX, targetY,
                blocks, fluids, actors, projection, events, gameTick.getAsLong());
    }

    public void minecraftTick() {
        long now = gameTick.getAsLong();
        for (Map.Entry<Long, ChargeUse> entry : Map.copyOf(chargedUses).entrySet()) {
            ItemActor actor = itemActor(entry.getKey());
            if (actor == null || actor.stack().isEmpty()) {
                chargedUses.remove(entry.getKey());
                continue;
            }
            ItemStack stack = actor.stack();
            long held = Math.max(0L, now - entry.getValue().startTick());
            float progress;
            if (stack.getItem() instanceof CrossbowItem) {
                progress = Math.min(1.0F, held / (float) Math.max(1, CrossbowItem.getPullTime(stack)));
            } else if (stack.getItem() instanceof BowItem) {
                // Vanilla's bow model predicate is linear use-time / 20. The
                // nonlinear BowItem#getPullProgress curve is launch power only.
                // Keeping those separate makes pulling_0/1/2 switch at the
                // resource pack's real predicate thresholds (0.65 / 0.9 vanilla).
                progress = Math.min(1.0F, held / 20.0F);
            } else if (stack.getItem() instanceof TridentItem) {
                progress = Math.min(1.0F, held / 10.0F);
            } else {
                progress = chargeStrength(held);
            }
            actor.setUseVisualState(true, progress);
        }
    }

    public void reset() {
        for (Long id : chargedUses.keySet()) {
            ItemActor actor = itemActor(id);
            if (actor != null) actor.setUseVisualState(false, 0.0F);
        }
        chargedUses.clear();
    }

    private boolean launchAndConsume(ItemActor actor, ItemCapabilityRegistry.ProjectileKind kind,
                                     float targetX, float targetY, float strength) {
        if (kind == null || kind == ItemCapabilityRegistry.ProjectileKind.NONE) return false;
        ItemStack source = actor.stack();
        ItemStack projectileStack;
        if (kind == ItemCapabilityRegistry.ProjectileKind.ARROW) projectileStack = new ItemStack(Items.ARROW);
        else {
            projectileStack = source.copy();
            projectileStack.setCount(1);
        }
        // The actor rotation is the visible item rotation. Derive gameplay heading
        // from that visible art plus the item's explicit semantic forward axis.
        // This keeps the projectile on the bow/crossbow/trident/rocket head rather
        // than hiding a global angle correction in gameplay.
        DirectionalItemProfile.Profile directional = DirectionalItemProfile.profile(source);
        float forwardDegrees = DirectionalItemProfile.launchHeadingDegrees(source, actor.rotation());
        double radians = Math.toRadians(forwardDegrees);
        float dirX = (float) Math.cos(radians);
        float dirY = (float) Math.sin(radians);
        // Spawn from the visible forward/head side of the rotated item rather than
        // its center. This keeps the projectile muzzle aligned with the launcher
        // texture and avoids a shot appearing to originate from the wrong side.
        float muzzle = Math.max(directional.muzzlePixels(),
                Math.max(actor.halfWidth(), actor.halfHeight()));
        float sourceX = actor.x() + dirX * muzzle;
        float sourceY = actor.y() + dirY * muzzle;
        float aimX = sourceX + dirX * 160.0F;
        float aimY = sourceY + dirY * 160.0F;
        long projectileId = projectiles.spawn(kind, projectileStack, actor.id(), sourceX, sourceY, actor.depth(), aimX, aimY);
        if (projectileId < 0L) return false;
        Actor spawned = actors.get(projectileId);
        if (spawned != null && strength < 0.999F) {
            spawned.setVelocity(spawned.velocityX() * strength, spawned.velocityY() * strength);
        }
        // Bow/crossbow are the launcher, not ammunition. Trident leaves the actor
        // stack while in flight; immediate throwables consume one.
        if (kind == ItemCapabilityRegistry.ProjectileKind.TRIDENT) {
            consume(actor, 1);
        } else if (ItemCapabilityRegistry.profile(source.getItem()).family()
                == ItemCapabilityRegistry.UseFamily.THROW_IMMEDIATE) {
            consume(actor, 1);
        }
        publishInteraction(actor.id(), "projectile_launch_" + kind.name().toLowerCase());
        return true;
    }

    private boolean placeBlock(ItemActor actor, SceneCellPos target) {
        ItemStack stack = actor.stack();
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
        BlockState existing = blocks.getBlockState(target);
        if (existing != null && !existing.isAir() && !existing.isReplaceable()) return false;
        FluidState fluid = fluids.getFluidState(target);

        BlockState placed = blockItem.getBlock().getDefaultState();
        if (placed.contains(Properties.HORIZONTAL_FACING)) {
            placed = placed.with(Properties.HORIZONTAL_FACING,
                    actor.x() <= projection.cellCenterScreenX(target) ? Direction.EAST : Direction.WEST);
        } else if (placed.contains(Properties.FACING)) {
            Direction desired = actor.x() <= projection.cellCenterScreenX(target) ? Direction.EAST : Direction.WEST;
            if (Properties.FACING.getValues().contains(desired)) placed = placed.with(Properties.FACING, desired);
        }
        if (!fluid.isEmpty() && placed.contains(Properties.WATERLOGGED)
                && fluid.getFluid().matchesType(Fluids.WATER)) {
            placed = placed.with(Properties.WATERLOGGED, true);
        }
        blocks.set(target, placed);
        consume(actor, 1);
        sound(placed.getSoundGroup().getPlaceSound(), 0.36F,
                (placed.getSoundGroup().getPitch() * 0.8F) + 0.2F);
        publishInteraction(actor.id(), "place_block:" + Registries.BLOCK.getId(placed.getBlock()));
        return true;
    }

    private boolean useBucket(ItemActor actor, SceneCellPos pos) {
        ItemStack stack = actor.stack();
        if (!(stack.getItem() instanceof BucketItem bucket)) return false;
        Fluid contained = bucketFluid(bucket);
        if (contained == null) return false;

        if (contained == Fluids.EMPTY) {
            FluidState targetFluid = fluids.getFluidState(pos);
            if (targetFluid == null || targetFluid.isEmpty() || !targetFluid.isStill()) return false;
            Item filled = targetFluid.getFluid().getBucketItem();
            if (filled == null || filled == Items.AIR) return false;
            BlockState fluidHost = blocks.getBlockState(pos);
            if (fluidHost != null && !fluidHost.isAir() && fluidHost.contains(Properties.WATERLOGGED)
                    && fluidHost.get(Properties.WATERLOGGED)) {
                blocks.setSceneOwned(pos, fluidHost.with(Properties.WATERLOGGED, false));
            }
            FluidCell fluidCell = fluids.get(pos);
            if (fluidCell != null && fluidCell.authority() == FluidCell.Authority.LEGACY_SEED
                    && fluidCell.sourceId() >= 0L) {
                events.publish(new SceneEvent.LegacyProxyWriteback(fluidCell.sourceId(), pos,
                        Blocks.AIR.getDefaultState(), true, gameTick.getAsLong()));
            }
            fluids.remove(pos);
            replaceStack(actor, new ItemStack(filled));
            SoundEvent fill = targetFluid.getFluid().getBucketFillSound().orElse(
                    targetFluid.getFluid().matchesType(Fluids.LAVA) ? SoundEvents.ITEM_BUCKET_FILL_LAVA : SoundEvents.ITEM_BUCKET_FILL);
            sound(fill, 0.34F, 1.0F);
            publishInteraction(actor.id(), "bucket_fill");
            return true;
        }

        BlockState occupant = blocks.getBlockState(pos);
        boolean water = contained.matchesType(Fluids.WATER);
        if (water && occupant != null && !occupant.isAir() && occupant.contains(Properties.WATERLOGGED)
                && !occupant.get(Properties.WATERLOGGED)) {
            blocks.setSceneOwned(pos, occupant.with(Properties.WATERLOGGED, true));
            fluids.setBlockStateFluid(pos, Fluids.WATER.getDefaultState());
        } else {
            if (occupant != null && !occupant.isAir() && !occupant.isReplaceable()) return false;
            Fluid source = contained instanceof FlowableFluid flowable ? flowable.getStill() : contained;
            fluids.set(pos, source.getDefaultState());
        }

        if (bucket instanceof EntityBucketItem entityBucket) spawnEntityBucketContents(entityBucket, actor, pos);
        replaceStack(actor, new ItemStack(Items.BUCKET));
        sound(contained.matchesType(Fluids.LAVA) ? SoundEvents.ITEM_BUCKET_EMPTY_LAVA : SoundEvents.ITEM_BUCKET_EMPTY,
                0.34F, 1.0F);
        publishInteraction(actor.id(), "bucket_empty");
        return true;
    }

    private boolean useTool(ItemActor actor, SceneCellPos pos, BlockState state) {
        Item item = actor.stack().getItem();
        if (item instanceof AxeItem) {
            Block replacement = null;
            SoundEvent sound = SoundEvents.ITEM_AXE_WAX_OFF;
            try { replacement = HoneycombItem.WAXED_TO_UNWAXED_BLOCKS.get().get(state.getBlock()); }
            catch (RuntimeException ignored) { }
            String particle = replacement != null ? "wax_off" : null;
            if (replacement == null) {
                replacement = Oxidizable.getDecreasedOxidationBlock(state.getBlock()).orElse(null);
                sound = SoundEvents.ITEM_AXE_SCRAPE;
                if (replacement != null) particle = "scrape";
            }
            if (replacement == null) {
                replacement = strippedVariant(state.getBlock());
                sound = SoundEvents.ITEM_AXE_STRIP;
            }
            if (replacement == null) return false;
            blocks.setSceneOwned(pos, transferState(state, replacement));
            damageTool(actor, 1);
            sound(sound, 0.30F, 1.0F);
            if (particle != null) emit(pos, particle, 10, 7.0F, 7.0F, 0.82F);
            publishInteraction(actor.id(), "axe_transform");
            return true;
        }

        if (item instanceof HoeItem) {
            Block replacement = null;
            if (state.isOf(Blocks.DIRT) || state.isOf(Blocks.GRASS_BLOCK) || state.isOf(Blocks.DIRT_PATH)) replacement = Blocks.FARMLAND;
            else if (state.isOf(Blocks.COARSE_DIRT) || state.isOf(Blocks.ROOTED_DIRT)) replacement = Blocks.DIRT;
            if (replacement == null) return false;
            blocks.setSceneOwned(pos, replacement.getDefaultState());
            damageTool(actor, 1);
            sound(SoundEvents.ITEM_HOE_TILL, 0.30F, 1.0F);
            publishInteraction(actor.id(), "hoe_till");
            return true;
        }

        if (item instanceof ShovelItem) {
            if (state.getBlock() instanceof CampfireBlock && state.contains(Properties.LIT) && state.get(Properties.LIT)) {
                blocks.setSceneOwned(pos, state.with(Properties.LIT, false));
                damageTool(actor, 1);
                sound(SoundEvents.BLOCK_FIRE_EXTINGUISH, 0.34F, 1.0F);
                emit(pos, "poof", 8, 7.0F, 7.0F, 0.72F);
                publishInteraction(actor.id(), "shovel_extinguish_campfire");
                return true;
            }
            if (!(state.isOf(Blocks.DIRT) || state.isOf(Blocks.GRASS_BLOCK) || state.isOf(Blocks.COARSE_DIRT)
                    || state.isOf(Blocks.PODZOL) || state.isOf(Blocks.MYCELIUM) || state.isOf(Blocks.ROOTED_DIRT))) return false;
            blocks.setSceneOwned(pos, Blocks.DIRT_PATH.getDefaultState());
            damageTool(actor, 1);
            sound(SoundEvents.ITEM_SHOVEL_FLATTEN, 0.30F, 1.0F);
            publishInteraction(actor.id(), "shovel_flatten");
            return true;
        }
        return false;
    }

    private boolean useFireStarter(ItemActor actor, SceneCellPos pos, BlockState state) {
        Item item = actor.stack().getItem();
        if (state.getBlock() instanceof TntBlock) {
            blocks.setSceneOwned(pos, Blocks.AIR.getDefaultState());
            long tntId = actors.allocateNativeId();
            PrimedTntActor primed = new PrimedTntActor(tntId, Actor.Authority.SCENE,
                    projection.cellCenterScreenX(pos), projection.cellCenterScreenY(pos) - 2.0F,
                    projection.depthCoordinate(pos), PrimedTntActor.DEFAULT_FUSE_TICKS);
            // Project vanilla TntEntity's random 0.02-block horizontal impulse
            // and +0.2-block vertical impulse into Koil's 16 px, y-down side view.
            // This keeps the actual vanilla motion scale instead of a tuned UI kick.
            double launchAngle = random.nextDouble() * Math.PI * 2.0D;
            primed.setVelocity((float) (-Math.sin(launchAngle) * 6.4D), -64.0F);
            actors.put(primed);
            events.publish(new SceneEvent.TntPrimed(tntId, primed.x(), primed.y(), primed.depth(),
                    primed.fuseTicks(), primed.sourceStack(), gameTick.getAsLong()));
            if (item instanceof FireChargeItem) consume(actor, 1); else damageTool(actor, 1);
            sound(SoundEvents.ENTITY_TNT_PRIMED, 0.42F, 1.0F);
            sound(item instanceof FireChargeItem ? SoundEvents.ITEM_FIRECHARGE_USE : SoundEvents.ITEM_FLINTANDSTEEL_USE, 0.30F, 1.0F);
            publishInteraction(actor.id(), "prime_tnt");
            return true;
        }
        if ((state.getBlock() instanceof CampfireBlock || state.getBlock() instanceof CandleBlock
                || state.getBlock() instanceof CandleCakeBlock) && state.contains(Properties.LIT)) {
            if (!state.get(Properties.LIT)) {
                blocks.setSceneOwned(pos, state.with(Properties.LIT, true));
                if (item instanceof FireChargeItem) consume(actor, 1); else damageTool(actor, 1);
                sound(item instanceof FireChargeItem ? SoundEvents.ITEM_FIRECHARGE_USE : SoundEvents.ITEM_FLINTANDSTEEL_USE, 0.30F, 1.0F);
                emit(pos, "flame", 4, 5.0F, 5.0F, 0.72F);
            }
            return true;
        }
        return false;
    }

    private boolean useBoneMeal(ItemActor actor, SceneCellPos pos, BlockState state) {
        if (!(actor.stack().getItem() instanceof BoneMealItem)) return false;
        IntProperty age = findIntegerProperty(state, "age");
        if (age == null || age.getValues().isEmpty()) return false;
        int max = age.getValues().stream().mapToInt(Integer::intValue).max().orElse(-1);
        if (max < 0 || state.get(age) >= max) return false;
        blocks.setSceneOwned(pos, state.with(age, max));
        consume(actor, 1);
        sound(SoundEvents.ITEM_BONE_MEAL_USE, 0.30F, 1.0F);
        emit(pos, "happy_villager", 15, 9.0F, 9.0F, 0.72F);
        publishInteraction(actor.id(), "bone_meal_grow");
        return true;
    }

    private boolean useShears(ItemActor actor, SceneCellPos pos, BlockState state) {
        if (!(actor.stack().getItem() instanceof ShearsItem)) return false;
        if (!(state.getBlock() instanceof BeehiveBlock) || !state.contains(Properties.HONEY_LEVEL)) return false;
        int honey = state.get(Properties.HONEY_LEVEL);
        if (honey < 5) return false;
        blocks.setSceneOwned(pos, state.with(Properties.HONEY_LEVEL, 0));
        damageTool(actor, 1);
        sound(SoundEvents.BLOCK_BEEHIVE_SHEAR, 0.30F, 1.0F);
        publishInteraction(actor.id(), "beehive_shear");
        return true;
    }

    private boolean tryCompost(ItemActor actor, SceneCellPos pos, BlockState state) {
        if (!state.contains(ComposterBlock.LEVEL)) return false;
        float chance = composterChance(actor.stack().getItem());
        if (!(chance > 0.0F)) return false;
        int level = state.get(ComposterBlock.LEVEL);
        if (level >= 7) return true;
        boolean success = random.nextFloat() < chance;
        if (success) blocks.setSceneOwned(pos, state.with(ComposterBlock.LEVEL, level + 1));
        consume(actor, 1);
        sound(success ? SoundEvents.BLOCK_COMPOSTER_FILL_SUCCESS : SoundEvents.BLOCK_COMPOSTER_FILL, 0.28F, 1.0F);
        if (success) emit(pos, "composter", 10, 6.0F, 6.0F, 0.72F);
        publishInteraction(actor.id(), success ? "composter_fill_success" : "composter_fill");
        return true;
    }

    private void spawnEntityBucketContents(EntityBucketItem item, ItemActor source, SceneCellPos pos) {
        EntityType<?> entityType = entityBucketType(item);
        if (entityType == null) return;
        long id = actors.allocateNativeId();
        Identifier entityId = Registries.ENTITY_TYPE.getId(entityType);
        EntityActor actor = new EntityActor(id, Actor.Authority.SCENE, entityId,
                projection.cellCenterScreenX(pos), projection.cellCenterScreenY(pos), pos.depth());
        actor.setBodySize(9.0F, 9.0F);
        actors.put(actor);
        events.publish(new SceneEvent.EntitySpawnRequested(entityId, id, actor.x(), actor.y(), actor.depth(),
                "entity_bucket", gameTick.getAsLong()));
    }

    private boolean useGlassBottle(ItemActor actor, SceneCellPos pos, BlockState state) {
        if (!(actor.stack().getItem() instanceof GlassBottleItem)) return false;
        if (state != null && state.getBlock() instanceof BeehiveBlock && state.contains(Properties.HONEY_LEVEL)
                && state.get(Properties.HONEY_LEVEL) >= 5) {
            blocks.setSceneOwned(pos, state.with(Properties.HONEY_LEVEL, 0));
            replaceOneContainer(actor, new ItemStack(Items.HONEY_BOTTLE));
            sound(SoundEvents.ITEM_BOTTLE_FILL, 0.34F, 1.0F);
            publishInteraction(actor.id(), "bottle_honey");
            return true;
        }
        FluidState fluid = fluids.getFluidState(pos);
        if (fluid != null && !fluid.isEmpty() && fluid.getFluid().matchesType(Fluids.WATER)) {
            ItemStack water = PotionUtil.setPotion(new ItemStack(Items.POTION), Potions.WATER);
            replaceOneContainer(actor, water);
            sound(SoundEvents.ITEM_BOTTLE_FILL, 0.34F, 1.0F);
            publishInteraction(actor.id(), "bottle_water");
            return true;
        }
        return false;
    }

    private boolean spawnFromEgg(ItemActor source, SpawnEggItem egg, SceneCellPos pos) {
        if (source == null || egg == null || pos == null) return false;
        EntityType<?> type;
        try { type = egg.getEntityType(source.stack().getNbt()); }
        catch (RuntimeException ignored) { return false; }
        if (type == null) return false;
        long id = actors.allocateNativeId();
        Identifier entityId = Registries.ENTITY_TYPE.getId(type);
        EntityActor actor = new EntityActor(id, Actor.Authority.SCENE, entityId,
                projection.cellCenterScreenX(pos), projection.cellCenterScreenY(pos) - 4.0F,
                projection.depthCoordinate(pos));
        actor.setVisualStack(source.stack());
        actors.put(actor);
        consume(source, 1);
        events.publish(new SceneEvent.EntitySpawnRequested(entityId, id, actor.x(), actor.y(), actor.depth(),
                "spawn_egg", gameTick.getAsLong()));
        publishInteraction(source.id(), "spawn_egg:" + entityId);
        return true;
    }

    private boolean igniteEmptyCell(ItemActor actor, SceneCellPos pos) {
        BlockState state = blocks.getBlockState(pos);
        if (state != null && !state.isAir() && !state.isReplaceable()) return false;
        blocks.set(pos, Blocks.FIRE.getDefaultState());
        boolean fireCharge = actor.stack().getItem() instanceof FireChargeItem;
        if (fireCharge) consume(actor, 1);
        else damageTool(actor, 1);
        sound(fireCharge ? SoundEvents.ITEM_FIRECHARGE_USE : SoundEvents.ITEM_FLINTANDSTEEL_USE, 0.30F, 1.0F);
        emit(pos, "flame", 4, 5.0F, 5.0F, 0.72F);
        publishInteraction(actor.id(), "ignite_air");
        return true;
    }

    private void emit(SceneCellPos pos, String particlePath, int count, float spreadX, float spreadY, float scale) {
        if (particles == null || pos == null || particlePath == null || particlePath.isBlank()) return;
        particles.emitAt(pos, new Identifier("minecraft", particlePath), count, spreadX, spreadY, scale);
    }

    private ItemActor itemActor(long actorId) {
        Actor raw = actors.get(actorId);
        return raw instanceof ItemActor item ? item : null;
    }

    /** Minecraft container semantics for stackable empty containers such as glass bottles. */
    private void replaceOneContainer(ItemActor actor, ItemStack result) {
        ItemStack source = actor.stack();
        if (source.isEmpty()) return;
        if (source.getCount() <= 1) {
            replaceStack(actor, result);
            return;
        }
        source.decrement(1);
        replaceStack(actor, source);
        long id = actors.allocateNativeId();
        ItemActor output = new ItemActor(id, Actor.Authority.SCENE, result,
                actor.x() + 5.0F, actor.y() - 4.0F, actor.depth());
        output.setVelocity(24.0F, -38.0F);
        actors.put(output);
    }

    private void consume(ItemActor actor, int amount) {
        ItemStack stack = actor.stack();
        if (stack.isEmpty()) return;
        stack.decrement(Math.max(1, Math.min(amount, stack.getCount())));
        replaceStack(actor, stack);
    }

    private void damageTool(ItemActor actor, int amount) {
        ItemStack stack = actor.stack();
        if (stack.isEmpty() || !stack.isDamageable()) return;
        int next = stack.getDamage() + Math.max(1, amount);
        if (next >= stack.getMaxDamage()) stack.decrement(1);
        else stack.setDamage(next);
        replaceStack(actor, stack);
    }

    private void replaceStack(ItemActor actor, ItemStack stack) {
        actor.setStack(stack);
        events.publish(new SceneEvent.ItemStackChanged(actor.id(), stack == null ? ItemStack.EMPTY : stack.copy(), gameTick.getAsLong()));
    }

    private void publishInteraction(long actorId, String action) {
        events.publish(new SceneEvent.Diagnostic("item_action", actorId + ":" + action));
    }

    private void sound(SoundEvent sound, float volume, float pitch) {
        if (sound != null) events.publish(new SceneEvent.SoundRequested(sound, volume, pitch, gameTick.getAsLong()));
    }

    private static float chargeStrength(long heldTicks) {
        float f = Math.min(1.0F, heldTicks / 20.0F);
        return Math.min(1.0F, (f * f + f * 2.0F) / 3.0F);
    }

    private static BlockState transferState(BlockState oldState, Block replacement) {
        return MinecraftStateCodec.resolve(replacement, MinecraftStateCodec.encode(oldState));
    }

    private static IntProperty findIntegerProperty(BlockState state, String name) {
        if (state == null || name == null) return null;
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntProperty integer && property.getName().equalsIgnoreCase(name)) return integer;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Block strippedVariant(Block block) {
        if (block == null) return null;
        try {
            Field field = axeStrippedBlocksField;
            if (field == null) {
                field = AxeItem.class.getDeclaredField("STRIPPED_BLOCKS");
                field.setAccessible(true);
                axeStrippedBlocksField = field;
            }
            Object value = field.get(null);
            if (value instanceof Map<?, ?> map) return (Block) map.get(block);
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return null;
    }

    private static float composterChance(Item item) {
        try { return ComposterBlock.ITEM_TO_LEVEL_INCREASE_CHANCE.getFloat(item); }
        catch (RuntimeException ignored) { return 0.0F; }
    }

    private static Fluid bucketFluid(BucketItem bucket) {
        if (bucket == null) return null;
        Field field = BUCKET_FLUID_FIELDS.computeIfAbsent(bucket.getClass(), type -> findField(type, Fluid.class));
        if (field == null) return null;
        try { return (Fluid) field.get(bucket); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    private static EntityType<?> entityBucketType(EntityBucketItem bucket) {
        if (bucket == null) return null;
        Field field = ENTITY_BUCKET_TYPE_FIELDS.computeIfAbsent(bucket.getClass(), type -> findField(type, EntityType.class));
        if (field == null) return null;
        try { return (EntityType<?>) field.get(bucket); }
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
