package com.spirit.koil.api.design.particle;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.GlazedTerracottaBlock;
import net.minecraft.block.PressurePlateBlock;
import net.minecraft.block.WeightedPressurePlateBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.ComposterBlock;
import net.minecraft.block.Oxidizable;
import net.minecraft.block.ChiseledBookshelfBlock;
import net.minecraft.block.ComparatorBlock;
import net.minecraft.block.DetectorRailBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.DropperBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.Waterloggable;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.block.CocoaBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.block.StemBlock;
import net.minecraft.block.SugarCaneBlock;
import net.minecraft.block.CactusBlock;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.JukeboxBlock;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.ObserverBlock;
import net.minecraft.block.RedstoneLampBlock;
import net.minecraft.block.RedstoneTorchBlock;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.RepeaterBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.TrappedChestBlock;
import net.minecraft.block.enums.Instrument;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SmokerBlockEntity;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.HoneycombItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MusicDiscItem;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.recipe.BrewingRecipeRegistry;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Small deterministic Minecraft-like block world used by Koil's screen-sprite engine.
 *
 * <p>The runtime deliberately owns world relationships rather than placing another
 * layer of one-off particle callbacks on individual blocks. Registry block sprites
 * are projected onto an integer virtual grid, then receive fixed 20 TPS scheduled
 * ticks, random ticks, entity contacts, fluid/waterlogging, neighbor, redstone,
 * inventory, cooking and piston processing. The implementation reuses
 * real Minecraft {@link BlockState}, {@link PistonBehavior}, block-entity inventory
 * shapes, sided-inventory rules, recipe data and fuel data wherever those APIs do
 * not require a real server {@code World}.</p>
 *
 * <p>The screen is the horizontal X/Z plane: east/west change X and north/south
 * change Z. Minecraft up/down uses the particle simulation-layer axis. For blocks
 * such as downward hoppers, a planar fallback can be used when no sprite exists on
 * the adjacent simulation layer so the 2D sandbox remains usable. Fluid and falling-
 * block gravity are projected toward screen south so their motion remains visible
 * in a flat sprite sandbox.</p>
 */
public final class VirtualBlockWorld {
    public static final float DEFAULT_CELL_PIXELS = 16.0F;
    public static final int TICKS_PER_SECOND = 20;
    public static final int MAX_PISTON_MOVED_BLOCKS = 12;
    private static final float TICK_SECONDS = 1.0F / TICKS_PER_SECOND;
    private static final int HOPPER_TRANSFER_COOLDOWN = 8;
    private static final int OBSERVER_PULSE_TICKS = 2;
    private static final int FALLING_BLOCK_TICK_DELAY = 1;
    private static final float FALLING_BLOCK_STEP_DURATION = TICK_SECONDS;
    private static final float DEFAULT_GRID_STEP_DURATION = 2.0F * TICK_SECONDS;

    public interface SpriteNode {
        long id();
        boolean blockNode();
        boolean itemNode();
        boolean entityNode();
        Block block();
        void block(Block block);
        Item item();
        void item(Item item);
        float x();
        float y();
        float previousX();
        float previousY();
        float halfWidth();
        float halfHeight();
        float velocityX();
        float velocityY();
        float restitution();
        float surfaceFriction();
        void position(float x, float y);
        float rotation();
        void rotation(float degrees);
        float angularVelocity();
        void angularVelocity(float degreesPerSecond);
        int simulationLayer();
        boolean dragging();
        boolean removed();
        void remove();
        boolean hasTag(String tag);
        String data(String key);
        void data(String key, Object value);
        float signal(String name);
        void signal(String name, float value);
        String blockState();
        void blockState(String state);
        void velocity(float vx, float vy);
        void gravity(float gravity);
        void playSound(SoundEvent event, float volume, float pitch);
    }

    public interface Host {
        void spawnItem(ItemStack stack, float x, float y, int simulationLayer, float vx, float vy);
        void spawnBlock(Block block, BlockState state, float x, float y, int simulationLayer);
        void spawnEntity(String entityTypeId, float x, float y, int simulationLayer, Map<String, String> data);
    }

    public record EnvironmentContext(
            int skyLight,
            int blockLight,
            long timeOfDay,
            boolean raining,
            boolean thundering,
            String dimensionId,
            float temperature,
            boolean ultraWarm
    ) {
        public static final EnvironmentContext DEFAULT = new EnvironmentContext(15, 0, 6000L, false, false,
                "minecraft:overworld", 0.8F, false);

        public EnvironmentContext {
            skyLight = Math.max(0, Math.min(15, skyLight));
            blockLight = Math.max(0, Math.min(15, blockLight));
            timeOfDay = Math.floorMod(timeOfDay, 24000L);
            dimensionId = dimensionId == null || dimensionId.isBlank() ? "minecraft:overworld" : dimensionId;
            temperature = Math.max(-2.0F, Math.min(2.0F, temperature));
        }
    }

    public record Cell(int x, int y, int z) {
        Cell offset(Direction direction) {
            return switch (direction) {
                case EAST -> new Cell(x + 1, y, z);
                case WEST -> new Cell(x - 1, y, z);
                case NORTH -> new Cell(x, y, z - 1);
                case SOUTH -> new Cell(x, y, z + 1);
                case UP -> new Cell(x, y + 1, z);
                case DOWN -> new Cell(x, y - 1, z);
            };
        }
    }

    private enum ScheduledKind {
        BLOCK, FLUID, POWER_RELEASE
    }

    private record ScheduledKey(ScheduledKind kind, long spriteId, Cell cell) { }

    private record ScheduledVirtualTick(
            long dueTick,
            long sequence,
            ScheduledKind kind,
            long spriteId,
            Cell cell,
            String reason
    ) implements Comparable<ScheduledVirtualTick> {
        @Override
        public int compareTo(ScheduledVirtualTick other) {
            int due = Long.compare(dueTick, other.dueTick);
            return due != 0 ? due : Long.compare(sequence, other.sequence);
        }
    }

    private record FluidInfo(Fluid fluid, boolean water, boolean lava, int level, boolean source, boolean falling, boolean waterlogged) {
        boolean valid() { return fluid != null && fluid != Fluids.EMPTY && level > 0; }
    }

    private record EntityContactSummary(int all, int items, int living, int players, int minecarts, int projectiles) {
        static final EntityContactSummary EMPTY = new EntityContactSummary(0, 0, 0, 0, 0, 0);
    }

    private record VirtualVibration(Cell origin, int frequency, long sourceId, String kind) { }

    private static final class RuntimeNode {
        private final long id;
        private SpriteNode sprite;
        private Cell cell;
        private Block block;
        private BlockState state;
        private String stateSignature = "";
        private String registryId = "";
        private BlockEntity blockEntityPrototype;
        private SidedInventory sidedInventoryPrototype;
        private DefaultedList<ItemStack> inventory = DefaultedList.ofSize(0, ItemStack.EMPTY);
        private int inventorySize;
        private int maxStackCount = 64;
        private int hopperCooldown;
        private int burnTime;
        private int fuelTime;
        private int cookTime;
        private int cookTimeTotal = 200;
        private int brewTime;
        private int brewFuel;
        private Item brewingIngredient = Items.AIR;
        private final int[] campfireCookTimes = new int[4];
        private final int[] campfireCookTotals = new int[4];
        private int gateOutput;
        private int gatePendingOutput;
        private int gateDelayTicks;
        private int observerPulseTicks;
        private int observerDelayTicks;
        private int lampOffDelayTicks;
        private int contactReleaseTicks;
        private String observedSignature = "";
        private long functionalAlignUntilTick;
        private float functionalRotationDegrees;
        private long lastSculkActivationTick = Long.MIN_VALUE;
        private BlockCapabilityRegistry.BlockProfile profile;
        private boolean tntPrimed;
        private boolean beaconActive;
        private int beaconLevels;
        private long lastPortalTick = Long.MIN_VALUE;
        private boolean gridMotionActive;
        private float gridMotionStartX;
        private float gridMotionStartY;
        private float gridMotionTargetX;
        private float gridMotionTargetY;
        private float gridMotionElapsed;
        private float gridMotionDuration;
        private String gridMotionKind = "";
        private boolean pistonExtended;
        private boolean previousPowered;
        private long lastSeenTick;

        private RuntimeNode(long id) {
            this.id = id;
        }
    }

    private final Map<Long, RuntimeNode> runtimes = new LinkedHashMap<>();
    private final Map<Cell, RuntimeNode> blocksByCell = new HashMap<>();
    private final Map<Cell, List<SpriteNode>> entitiesByCell = new HashMap<>();
    private final Map<Long, SpriteNode> entitiesById = new LinkedHashMap<>();
    private final Map<Long, Cell> previousCells = new HashMap<>();
    private final List<VirtualVibration> vibrationEvents = new ArrayList<>();
    private final PriorityQueue<ScheduledVirtualTick> scheduledTicks = new PriorityQueue<>();
    private final Map<ScheduledKey, Long> scheduledDueByKey = new HashMap<>();
    private final Map<Cell, Long> pendingFluidSpawns = new HashMap<>();
    private long scheduleSequence;
    private int randomTickSpeed = 3;
    private long randomSeed = 0x4B4F494C5F56574CL;
    private float accumulator;
    private float cellPixels = DEFAULT_CELL_PIXELS;
    private long worldTick;
    private boolean enabled = true;
    private boolean legacyFluidSimulationEnabled = true;
    private Map<Item, Integer> furnaceFuelTimes;
    private EnvironmentContext environment = EnvironmentContext.DEFAULT;
    private VirtualServerContext serverContext = new VirtualServerContext();
    private VirtualEntityWorld entityWorld;
    private DetachedMinecraftRuntime detachedRuntime = DetachedMinecraftRuntime.shared();
    private boolean advanceEnvironmentTime = true;
    private boolean coverageReportWritten;
    private Path coverageReportPath = Paths.get("./koil/sys/design/virtual_block_coverage.json");
    private Host activeHost;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) clear();
    }

    /**
     * Migration switch. Rev AB moves fluid propagation/contact authority into
     * KoilScene.KoilFluidSystem. When false, this legacy world no longer schedules,
     * spreads, mixes, bucket-mutates, or applies fluid contacts.
     */
    public void setLegacyFluidSimulationEnabled(boolean enabled) {
        this.legacyFluidSimulationEnabled = enabled;
        if (!enabled) {
            scheduledTicks.removeIf(tick -> tick.kind() == ScheduledKind.FLUID);
            scheduledDueByKey.entrySet().removeIf(entry -> entry.getKey().kind() == ScheduledKind.FLUID);
            pendingFluidSpawns.clear();
        }
    }

    public boolean isLegacyFluidSimulationEnabled() { return legacyFluidSimulationEnabled; }

    public float getCellPixels() {
        return cellPixels;
    }

    public void setCellPixels(float cellPixels) {
        this.cellPixels = Math.max(6.0F, Math.min(64.0F, cellPixels));
    }

    public long getWorldTick() {
        return worldTick;
    }

    /** Mirrors the vanilla randomTickSpeed game-rule default of 3. The virtual
     * world uses a 16^3 section-style approximation (speed / 4096 per eligible
     * block per game tick), which keeps growth timing stable and FPS-independent. */
    public int getRandomTickSpeed() {
        return randomTickSpeed;
    }

    public void setRandomTickSpeed(int randomTickSpeed) {
        this.randomTickSpeed = Math.max(0, Math.min(4096, randomTickSpeed));
    }

    public void setRandomSeed(long randomSeed) {
        this.randomSeed = randomSeed;
    }


    public void setServerContext(VirtualServerContext serverContext) {
        this.serverContext = serverContext == null ? new VirtualServerContext() : serverContext;
        syncServerContext();
    }

    public VirtualServerContext getServerContext() {
        return serverContext;
    }

    public void setEntityWorld(VirtualEntityWorld entityWorld) {
        this.entityWorld = entityWorld;
    }

    public VirtualEntityWorld getEntityWorld() {
        return entityWorld;
    }

    public void setDetachedRuntime(DetachedMinecraftRuntime detachedRuntime) {
        this.detachedRuntime = detachedRuntime == null ? DetachedMinecraftRuntime.shared() : detachedRuntime;
    }

    public DetachedMinecraftRuntime getDetachedRuntime() {
        return detachedRuntime;
    }

    public EnvironmentContext getEnvironment() {
        return environment;
    }

    public void setEnvironment(EnvironmentContext environment) {
        this.environment = environment == null ? EnvironmentContext.DEFAULT : environment;
    }

    public void setEnvironment(int skyLight, int blockLight, long timeOfDay, boolean raining, boolean thundering,
                               String dimensionId, float temperature, boolean ultraWarm) {
        setEnvironment(new EnvironmentContext(skyLight, blockLight, timeOfDay, raining, thundering, dimensionId, temperature, ultraWarm));
    }

    public boolean isEnvironmentTimeAdvancing() {
        return advanceEnvironmentTime;
    }

    public void setEnvironmentTimeAdvancing(boolean advanceEnvironmentTime) {
        this.advanceEnvironmentTime = advanceEnvironmentTime;
    }

    public void ensureCoverageReport() {
        writeCoverageReportOnce();
    }

    public Path getCoverageReportPath() {
        return coverageReportPath;
    }

    public void setCoverageReportPath(Path coverageReportPath) {
        if (coverageReportPath != null) this.coverageReportPath = coverageReportPath;
        this.coverageReportWritten = false;
    }

    public BlockCapabilityRegistry.CoverageSummary getCoverageSummary() {
        return BlockCapabilityRegistry.summary();
    }

    public boolean schedulePowerRelease(long spriteId, int delayTicks) {
        RuntimeNode runtime = runtimes.get(spriteId);
        if (runtime == null) return false;
        schedule(runtime, ScheduledKind.POWER_RELEASE, Math.max(1, delayTicks), "power_release");
        runtime.sprite.data("__koil_vw_power_release_tick", worldTick + Math.max(1, delayTicks));
        return true;
    }

    public boolean scheduleBlockTick(long spriteId, int delayTicks, String reason) {
        RuntimeNode runtime = runtimes.get(spriteId);
        if (runtime == null) return false;
        schedule(runtime, ScheduledKind.BLOCK, Math.max(1, delayTicks), reason != null && !reason.isBlank() ? reason : "external");
        return true;
    }

    public boolean scheduleFluidTick(long spriteId, int delayTicks) {
        RuntimeNode runtime = runtimes.get(spriteId);
        if (runtime == null || !fluidInfo(runtime).valid()) return false;
        schedule(runtime, ScheduledKind.FLUID, Math.max(1, delayTicks), "external_fluid");
        return true;
    }

    /** Requests temporary state-owned orientation without disabling free block physics. */
    public boolean requestFunctionalAlignment(long spriteId, int ticks) {
        RuntimeNode runtime = runtimes.get(spriteId);
        if (runtime == null) return false;
        requestFunctionalAlignment(runtime, Math.max(1, ticks));
        return true;
    }

    /**
     * Authoritative explicit-use entry point for registered block sprites.
     * Input intent is routed here by KoilVirtualInteractionRouter.
     */
    public boolean useBlock(long spriteId) {
        RuntimeNode runtime = runtimes.get(spriteId);
        if (runtime == null || runtime.sprite == null || runtime.profile == null) return false;
        if (!hasCapability(runtime, BlockCapabilityRegistry.Capability.PLAYER_INTERACTION)
                && runtime.inventorySize <= 0) return false;
        return useVirtualBlock(runtime);
    }

    /**
     * Authoritative explicit item-on-block interaction. Automatic physical contacts
     * such as hoppers, pressure plates and fluids remain tick-driven instead. The
     * interaction router owns intent, so this method never infers use from overlap.
     */
    public boolean interactItemWithBlock(long blockSpriteId, long itemSpriteId) {
        RuntimeNode runtime = runtimes.get(blockSpriteId);
        SpriteNode itemNode = entitiesById.get(itemSpriteId);
        if (runtime == null || itemNode == null || itemNode.removed() || !itemNode.itemNode()
                || itemNode.item() == null || itemNode.item() == Items.AIR) return false;
        return useItemOnVirtualBlock(runtime, itemNode);
    }

    /** Dispatches projectile-sensitive block behavior through the projectile service. */
    public boolean notifyProjectileHit(long blockSpriteId, long projectileSpriteId) {
        RuntimeNode runtime = runtimes.get(blockSpriteId);
        SpriteNode projectile = entitiesById.get(projectileSpriteId);
        if (runtime == null || projectile == null || runtime.profile == null
                || !hasCapability(runtime, BlockCapabilityRegistry.Capability.PROJECTILE_INTERACTION)) return false;
        return handleProjectileHit(runtime, projectile);
    }

    public int getEntityContactCount(long spriteId) {
        RuntimeNode runtime = runtimes.get(spriteId);
        return runtime == null ? 0 : summarizeContacts(runtime.cell).all();
    }

    public boolean hasInventory(long spriteId) {
        RuntimeNode runtime = runtimes.get(spriteId);
        return runtime != null && runtime.inventorySize > 0;
    }

    public int getInventorySize(long spriteId) {
        RuntimeNode runtime = runtimes.get(spriteId);
        if (runtime == null) return 0;
        int total = 0;
        for (RuntimeNode member : inventoryMembers(runtime)) total += member.inventorySize;
        return total;
    }

    public ItemStack getInventoryStack(long spriteId, int slot) {
        RuntimeNode runtime = runtimes.get(spriteId);
        InventorySlotRef ref = resolveLogicalSlot(runtime, slot);
        if (ref == null) return ItemStack.EMPTY;
        return ref.runtime.inventory.get(ref.slot).copy();
    }

    /** Direct player/use-style insertion. Hopper sided rules are intentionally not
     * applied here, but each member block entity's slot validity is still respected.
     * Paired chests expose one logical 54-slot inventory while retaining separate
     * 27-slot persistence on each sprite, matching vanilla's DoubleInventory model. */
    public int insertIntoInventory(long spriteId, ItemStack offered, int preferredSlot, int maxAmount) {
        RuntimeNode runtime = runtimes.get(spriteId);
        if (runtime == null || offered == null || offered.isEmpty() || runtime.inventorySize <= 0) return 0;
        int remainingLimit = Math.max(1, maxAmount);
        ItemStack working = offered.copy();
        working.setCount(Math.min(working.getCount(), remainingLimit));
        int before = working.getCount();
        if (preferredSlot >= 0) {
            InventorySlotRef ref = resolveLogicalSlot(runtime, preferredSlot);
            if (ref != null) insertDirectSlot(ref.runtime, ref.slot, working);
        } else {
            for (RuntimeNode member : inventoryMembers(runtime)) {
                for (int slot = 0; slot < member.inventory.size() && !working.isEmpty(); slot++) {
                    insertDirectSlot(member, slot, working);
                }
                if (working.isEmpty()) break;
            }
        }
        int inserted = before - working.getCount();
        if (inserted > 0) syncAndPersistInventoryGroup(runtime);
        return inserted;
    }

    public ItemStack extractFromInventory(long spriteId, int slot, int maxAmount) {
        RuntimeNode runtime = runtimes.get(spriteId);
        InventorySlotRef ref = resolveLogicalSlot(runtime, slot);
        if (ref == null || maxAmount <= 0) return ItemStack.EMPTY;
        ItemStack stack = ref.runtime.inventory.get(ref.slot);
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack removed = stack.split(Math.min(maxAmount, stack.getCount()));
        if (stack.isEmpty()) ref.runtime.inventory.set(ref.slot, ItemStack.EMPTY);
        if (!removed.isEmpty()) {
            if (ref.runtime.block instanceof ChiseledBookshelfBlock) {
                ref.runtime.sprite.data("__koil_vw_last_interacted_slot", ref.slot);
            }
            syncAndPersistInventoryGroup(runtime);
        }
        return removed;
    }

    public void clear() {
        runtimes.clear();
        blocksByCell.clear();
        entitiesByCell.clear();
        entitiesById.clear();
        vibrationEvents.clear();
        previousCells.clear();
        scheduledTicks.clear();
        scheduledDueByKey.clear();
        pendingFluidSpawns.clear();
        scheduleSequence = 0L;
        accumulator = 0.0F;
        worldTick = 0L;
    }

    public void tick(Collection<? extends SpriteNode> sprites, Host host, float deltaSeconds) {
        activeHost = host;
        if (!enabled || sprites == null || sprites.isEmpty()) {
            if (sprites == null || sprites.isEmpty()) clearTransientIndex();
            return;
        }

        writeCoverageReportOnce();
        syncServerContext();
        accumulator += Math.max(0.0F, Math.min(0.25F, deltaSeconds));
        reconcile(sprites);
        int safety = 0;
        while (accumulator >= TICK_SECONDS && safety++ < 5) {
            accumulator -= TICK_SECONDS;
            step(host);
        }
        advanceGridMotion(Math.max(0.0F, Math.min(0.25F, deltaSeconds)));
        enforceBlockGridInvariant();
        publishDebugState();
    }

    private void clearTransientIndex() {
        blocksByCell.clear();
        entitiesByCell.clear();
        entitiesById.clear();
    }

    private void reconcile(Collection<? extends SpriteNode> sprites) {
        worldTick = Math.max(0L, worldTick);
        blocksByCell.clear();
        entitiesByCell.clear();
        entitiesById.clear();
        Set<Long> seen = new HashSet<>();
        Set<Long> seenBlocks = new HashSet<>();

        List<SpriteNode> ordered = new ArrayList<>(sprites);
        ordered.sort(Comparator.comparingLong(SpriteNode::id));
        for (SpriteNode sprite : ordered) {
            if (sprite == null || sprite.removed()) continue;
            if (!sprite.blockNode() && !sprite.itemNode() && !sprite.entityNode()) continue;
            seen.add(sprite.id());
            Cell cell;

            if (sprite.blockNode() && sprite.block() != null && sprite.block() != Blocks.AIR) {
                seenBlocks.add(sprite.id());
                RuntimeNode runtime = runtimes.computeIfAbsent(sprite.id(), RuntimeNode::new);
                runtime.sprite = sprite;
                Cell previousCell = previousCells.get(sprite.id());
                Cell desiredCell = resolveBlockCell(sprite, runtime);
                cell = claimBlockCell(sprite, runtime, desiredCell, previousCell);
                runtime.cell = cell;
                runtime.lastSeenTick = worldTick;
                refreshBlockRuntime(runtime);
                blocksByCell.put(cell, runtime);
                sprite.data("__koil_virtual_world_managed", true);
                sprite.data("__koil_vw_grid_locked", true);
            } else {
                cell = resolveCell(sprite);
            }

            if (!sprite.blockNode() && sprite.itemNode() && sprite.item() != null && sprite.item() != Items.AIR) {
                // Item entities are indexed by their AABB below. Keep the managed
                // marker for interaction code without maintaining a second center-
                // cell item index that could disagree with entity contacts.
                sprite.data("__koil_virtual_world_managed", true);
            }

            if (sprite.entityNode()) {
                entitiesById.put(sprite.id(), sprite);
                sprite.data("__koil_vw_fluid", "");
                sprite.data("__koil_vw_submerged", false);
                sprite.data("__koil_vw_hot_contact", false);
                sprite.data("__koil_vw_on_rail", false);
                indexEntity(sprite);
                sprite.data("__koil_virtual_entity_managed", true);
            }
        }

        runtimes.entrySet().removeIf(entry -> !seenBlocks.contains(entry.getKey()) || entry.getValue().sprite == null || entry.getValue().sprite.removed());
        previousCells.keySet().removeIf(id -> !seen.contains(id));
        scheduledDueByKey.keySet().removeIf(key -> key.spriteId() > 0L && !runtimes.containsKey(key.spriteId()));
        pendingFluidSpawns.entrySet().removeIf(entry -> entry.getValue() < worldTick - 4L || blocksByCell.containsKey(entry.getKey()));
    }

    private void indexEntity(SpriteNode sprite) {
        float half = cellPixels * 0.5F;
        float hw = Math.max(0.5F, Math.min(cellPixels * 2.0F, sprite.halfWidth()));
        float hh = Math.max(0.5F, Math.min(cellPixels * 2.0F, sprite.halfHeight()));
        final float edgeEpsilon = 0.001F;
        int minX = (int) Math.floor((sprite.x() - hw + half) / cellPixels);
        int maxX = (int) Math.floor((sprite.x() + hw - edgeEpsilon + half) / cellPixels);
        int minZ = (int) Math.floor((sprite.y() - hh + half) / cellPixels);
        int maxZ = (int) Math.floor((sprite.y() + hh - edgeEpsilon + half) / cellPixels);
        int layer = sprite.simulationLayer();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                entitiesByCell.computeIfAbsent(new Cell(x, layer, z), ignored -> new ArrayList<>()).add(sprite);
            }
        }
    }

    private Cell resolveBlockCell(SpriteNode sprite, RuntimeNode runtime) {
        if (runtime != null && runtime.gridMotionActive && !sprite.dragging()) {
            sprite.rotation(0.0F);
            sprite.angularVelocity(0.0F);
            sprite.gravity(0.0F);
            return runtime.cell;
        }
        int x = Math.round(sprite.x() / cellPixels);
        int z = Math.round(sprite.y() / cellPixels);
        int y = sprite.simulationLayer();
        if (runtime != null) runtime.gridMotionActive = false;
        return new Cell(x, y, z);
    }

    /**
     * Claims one authoritative cell for a registered block. A dragged existing
     * block cannot overwrite an occupied destination. Duplicate initial spawns are
     * deterministically relocated to the nearest free planar cell instead of
     * leaving multiple sprites that disagree with the world map.
     */
    private Cell claimBlockCell(SpriteNode sprite, RuntimeNode runtime, Cell desired, Cell previous) {
        Cell chosen = desired;
        RuntimeNode occupant = desired == null ? null : blocksByCell.get(desired);
        boolean conflict = occupant != null && occupant.id != sprite.id();
        if (conflict) {
            if (sprite.dragging() && previous != null && isCellFreeFor(previous, sprite.id())) {
                chosen = previous;
            } else {
                chosen = nearestFreePlanarCell(desired, sprite.id());
            }
            sprite.data("__koil_vw_cell_conflict", true);
            sprite.data("__koil_vw_conflict_x", desired.x());
            sprite.data("__koil_vw_conflict_y", desired.y());
            sprite.data("__koil_vw_conflict_z", desired.z());
        } else {
            sprite.data("__koil_vw_cell_conflict", false);
        }
        if (chosen == null) chosen = desired;
        if (chosen == null) chosen = new Cell(0, sprite.simulationLayer(), 0);

        previousCells.put(sprite.id(), chosen);
        sprite.data("__koil_vw_x", chosen.x());
        sprite.data("__koil_vw_y", chosen.y());
        sprite.data("__koil_vw_z", chosen.z());
        sprite.data("__koil_vw_locked", true);
        sprite.data("__koil_vw_grid_locked", true);
        sprite.rotation(0.0F);
        sprite.angularVelocity(0.0F);
        sprite.gravity(0.0F);
        sprite.velocity(0.0F, 0.0F);

        // During owned grid motion the sprite is intentionally interpolating from
        // one logical cell to another. Otherwise placement snaps to the claimed cell.
        if (runtime == null || !runtime.gridMotionActive) {
            sprite.position(chosen.x() * cellPixels, chosen.z() * cellPixels);
        }
        return chosen;
    }

    private boolean isCellFreeFor(Cell cell, long spriteId) {
        if (cell == null) return false;
        RuntimeNode occupied = blocksByCell.get(cell);
        return occupied == null || occupied.id == spriteId;
    }

    private Cell nearestFreePlanarCell(Cell origin, long spriteId) {
        if (origin == null) return null;
        if (isCellFreeFor(origin, spriteId)) return origin;
        final int maxRadius = 256;
        for (int radius = 1; radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int dz = radius - Math.abs(dx);
                Cell north = new Cell(origin.x() + dx, origin.y(), origin.z() - dz);
                if (isCellFreeFor(north, spriteId)) return north;
                if (dz != 0) {
                    Cell south = new Cell(origin.x() + dx, origin.y(), origin.z() + dz);
                    if (isCellFreeFor(south, spriteId)) return south;
                }
            }
        }
        return origin;
    }

    private Cell resolveCell(SpriteNode sprite) {
        boolean locked = bool(sprite.data("__koil_vw_locked")) && !sprite.dragging();
        if (sprite.dragging() && bool(sprite.data("__koil_vw_locked"))) sprite.data("__koil_vw_locked", false);
        Cell previous = previousCells.get(sprite.id());
        int x;
        int z;
        int y;
        if (locked) {
            x = intData(sprite.data("__koil_vw_x"), Math.round(sprite.x() / cellPixels));
            z = intData(sprite.data("__koil_vw_z"), Math.round(sprite.y() / cellPixels));
            y = intData(sprite.data("__koil_vw_y"), sprite.simulationLayer());
        } else {
            x = Math.round(sprite.x() / cellPixels);
            z = Math.round(sprite.y() / cellPixels);
            y = sprite.simulationLayer();
            // Preserve the current cell while a resting sprite jitters around a border.
            if (!sprite.dragging() && previous != null) {
                float px = previous.x() * cellPixels;
                float pz = previous.z() * cellPixels;
                if (Math.abs(sprite.x() - px) < cellPixels * 0.58F) x = previous.x();
                if (Math.abs(sprite.y() - pz) < cellPixels * 0.58F) z = previous.z();
            }
        }
        Cell cell = new Cell(x, y, z);
        previousCells.put(sprite.id(), cell);
        sprite.data("__koil_vw_x", x);
        sprite.data("__koil_vw_y", y);
        sprite.data("__koil_vw_z", z);
        return cell;
    }

    private void refreshBlockRuntime(RuntimeNode runtime) {
        Block liveBlock = runtime.sprite.block();
        String signature = normalizeSignature(runtime.sprite.blockState());
        Identifier id = Registries.BLOCK.getId(liveBlock);
        String registryId = id == null ? "" : id.toString();
        boolean blockChanged = runtime.block != liveBlock || !Objects.equals(runtime.registryId, registryId);
        boolean stateChanged = blockChanged || !Objects.equals(runtime.stateSignature, signature);

        runtime.block = liveBlock;
        runtime.registryId = registryId;
        runtime.profile = BlockCapabilityRegistry.profile(liveBlock);
        if (stateChanged) {
            runtime.state = resolveBlockState(liveBlock, signature);
            runtime.stateSignature = signature;
        }
        if (blockChanged) {
            rebuildInventoryContract(runtime);
            runtime.pistonExtended = boolProperty(runtime.state, "extended", false);
            runtime.previousPowered = runtime.sprite.signal("power") > 0.0F;
            runtime.observedSignature = "";
            runtime.gateOutput = Math.round(runtime.sprite.signal("power"));
            runtime.gatePendingOutput = runtime.gateOutput;
            runtime.gateDelayTicks = 0;
            loadMachineState(runtime);
            runtime.sprite.data("__koil_vw_block_class", liveBlock.getClass().getName());
            runtime.sprite.data("__koil_vw_block_entity", runtime.blockEntityPrototype == null ? "" : runtime.blockEntityPrototype.getClass().getName());
            runtime.sprite.data("__koil_vw_piston_behavior", runtime.state.getPistonBehavior().name().toLowerCase(Locale.ROOT));
            runtime.sprite.data("__koil_vw_has_block_entity", runtime.state.hasBlockEntity());
            runtime.sprite.data("__koil_vw_emits_redstone", runtime.state.emitsRedstonePower());
            runtime.sprite.data("__koil_vw_has_comparator", runtime.state.hasComparatorOutput());
            publishCapabilityProfile(runtime);
        }
    }

    private void rebuildInventoryContract(RuntimeNode runtime) {
        DefaultedList<ItemStack> previous = runtime.inventory;
        runtime.blockEntityPrototype = null;
        runtime.sidedInventoryPrototype = null;
        int size = 0;
        int maxCount = 64;
        try {
            if (runtime.block instanceof BlockEntityProvider provider) {
                BlockEntity blockEntity = provider.createBlockEntity(BlockPos.ORIGIN, runtime.state);
                runtime.blockEntityPrototype = blockEntity;
                if (blockEntity instanceof Inventory inventory) {
                    size = Math.max(0, inventory.size());
                    maxCount = Math.max(1, inventory.getMaxCountPerStack());
                }
                if (blockEntity instanceof SidedInventory sided) runtime.sidedInventoryPrototype = sided;
            }
        } catch (RuntimeException ignored) {
            runtime.blockEntityPrototype = null;
            runtime.sidedInventoryPrototype = null;
        }

        // Campfires and lecterns own item storage that is not exposed directly
        // through Inventory on their block entity class.
        if (size == 0 && hasCapability(runtime, BlockCapabilityRegistry.Capability.CAMPFIRE_COOKING)) size = 4;
        if (size == 0 && runtime.block instanceof LecternBlock) { size = 1; maxCount = 1; }
        runtime.inventorySize = size;
        runtime.maxStackCount = maxCount;
        runtime.inventory = DefaultedList.ofSize(size, ItemStack.EMPTY);
        if (previous != null && !previous.isEmpty() && size > 0) {
            for (int i = 0; i < Math.min(previous.size(), size); i++) runtime.inventory.set(i, previous.get(i).copy());
        } else if (size > 0) {
            loadPersistedInventory(runtime);
        }
        syncInventoryState(runtime);
        runtime.sprite.data("__koil_vw_inventory_size", size);
    }

    private void step(Host host) {
        worldTick++;
        advanceEnvironment();
        rebuildIndicesFromRuntime();
        scheduleImplicitWorldTicks();
        executeDueScheduledTicks(host);
        rebuildIndicesFromRuntime();
        tickRandomBlocks(host);
        rebuildIndicesFromRuntime();
        tickFireAndExplosions(host);
        rebuildIndicesFromRuntime();
        collectVirtualVibrations();
        if (legacyFluidSimulationEnabled) tickBucketInteractions(host);
        rebuildIndicesFromRuntime();
        tickContactSources();
        tickTripwireNetworks();
        tickRailRouting();
        if (legacyFluidSimulationEnabled) {
            applyFluidEntityContacts();
            tickBubbleColumns(host);
        }
        tickDamageContacts();
        tickVibrationServices(host);
        tickObservers();
        tickRedstone();
        advanceDelayedRedstoneStates();
        syncCompoundBlocks();
        tickPistons();
        rebuildIndicesFromRuntime();
        tickHoppers(host);
        tickFurnaces();
        tickBrewingStands();
        tickCampfires(host);
        tickDroppersAndDispensers(host);
        tickPortalServices();
        tickBeaconServices();
        tickServerEntityDependentBlocks(host);
        updateComparatorData();
        applyFunctionalAlignment();
    }

    private void rebuildIndicesFromRuntime() {
        blocksByCell.clear();
        for (RuntimeNode runtime : runtimes.values()) {
            if (runtime.sprite == null || runtime.sprite.removed() || runtime.block == null || runtime.block == Blocks.AIR) continue;
            blocksByCell.put(runtime.cell, runtime);
        }
    }

    private void scheduleImplicitWorldTicks() {
        for (RuntimeNode runtime : runtimes.values()) {
            if (runtime == null || runtime.state == null || runtime.sprite == null || runtime.sprite.removed()) continue;
            if (hasCapability(runtime, BlockCapabilityRegistry.Capability.FALLING_BLOCK) && canFallInto(screenDown(runtime.cell))) {
                schedule(runtime, ScheduledKind.BLOCK, FALLING_BLOCK_TICK_DELAY, "falling_block");
            }
            FluidInfo fluid = fluidInfo(runtime);
            if (legacyFluidSimulationEnabled && fluid.valid()) {
                if (hasCapability(runtime, BlockCapabilityRegistry.Capability.FLUID_SIMULATION) && !runtime.sprite.dragging()) {
                    runtime.sprite.data("__koil_vw_locked", true);
                    runtime.sprite.position(runtime.cell.x() * cellPixels, runtime.cell.z() * cellPixels);
                    runtime.sprite.velocity(0.0F, 0.0F);
                    runtime.sprite.gravity(0.0F);
                    requestFunctionalAlignment(runtime, fluidTickRate(fluid) + 2);
                }
                schedule(runtime, ScheduledKind.FLUID, fluidTickRate(fluid), "fluid");
            } else if (legacyFluidSimulationEnabled && isWaterlogged(runtime)) {
                schedule(runtime, ScheduledKind.FLUID, 5, "waterlogged");
            }
        }
    }

    private void schedule(RuntimeNode runtime, ScheduledKind kind, int delayTicks, String reason) {
        if (runtime == null) return;
        scheduleAbsolute(runtime, kind, worldTick + Math.max(1, delayTicks), reason);
    }

    private void scheduleAbsolute(RuntimeNode runtime, ScheduledKind kind, long dueTick, String reason) {
        if (runtime == null) return;
        String normalizedReason = reason == null ? "" : reason;
        ScheduledKey key = new ScheduledKey(kind, runtime.id, null);
        Long existing = scheduledDueByKey.get(key);
        if (existing != null && existing <= dueTick) return;
        scheduledDueByKey.put(key, dueTick);
        scheduledTicks.add(new ScheduledVirtualTick(dueTick, ++scheduleSequence, kind, runtime.id, runtime.cell, normalizedReason));
    }

    private void scheduleFluidCell(Cell cell, int delayTicks, String reason) {
        if (cell == null) return;
        RuntimeNode runtime = blockAt(cell);
        if (runtime != null) {
            schedule(runtime, ScheduledKind.FLUID, delayTicks, reason);
            return;
        }
        String normalizedReason = reason == null ? "" : reason;
        ScheduledKey key = new ScheduledKey(ScheduledKind.FLUID, 0L, cell);
        long due = worldTick + Math.max(1, delayTicks);
        Long existing = scheduledDueByKey.get(key);
        if (existing != null && existing <= due) return;
        scheduledDueByKey.put(key, due);
        scheduledTicks.add(new ScheduledVirtualTick(due, ++scheduleSequence, ScheduledKind.FLUID, 0L, cell, normalizedReason));
    }

    private void executeDueScheduledTicks(Host host) {
        int safety = 0;
        while (!scheduledTicks.isEmpty() && scheduledTicks.peek().dueTick() <= worldTick && safety++ < 2048) {
            ScheduledVirtualTick tick = scheduledTicks.poll();
            ScheduledKey key = new ScheduledKey(tick.kind(), tick.spriteId(), tick.spriteId() > 0L ? null : tick.cell());
            Long activeDue = scheduledDueByKey.get(key);
            if (activeDue == null || activeDue != tick.dueTick()) continue;
            scheduledDueByKey.remove(key);
            RuntimeNode runtime = tick.spriteId() > 0L ? runtimes.get(tick.spriteId()) : blockAt(tick.cell());
            switch (tick.kind()) {
                case POWER_RELEASE -> executePowerRelease(runtime);
                case BLOCK -> executeVirtualBlockTick(runtime, tick.reason(), host);
                case FLUID -> { if (legacyFluidSimulationEnabled) tickFluidAt(runtime == null ? tick.cell() : runtime.cell, host); }
            }
        }
    }

    private void executePowerRelease(RuntimeNode runtime) {
        if (runtime == null || runtime.sprite == null || runtime.sprite.removed()) return;
        boolean wasPowered = runtime.sprite.signal("power") > 0.0F;
        runtime.sprite.signal("power", 0.0F);
        runtime.sprite.data("__koil_vw_power_release_tick", "");
        if (hasProperty(runtime.state, "powered")) {
            runtime.state = withStateValue(runtime.state, "powered", "false");
            writeState(runtime);
        }
        if (wasPowered) {
            SoundEvent sound = GameSpriteSoundResolver.toggleSound(runtime.block, false);
            if (sound != null) runtime.sprite.playSound(sound, 0.30F, 1.0F);
        }
    }

    private void executeVirtualBlockTick(RuntimeNode runtime, String reason, Host host) {
        if (runtime == null || runtime.sprite == null || runtime.sprite.removed()) return;
        runtime.sprite.data("__koil_vw_last_scheduled_tick", worldTick);
        runtime.sprite.data("__koil_vw_last_scheduled_reason", reason == null ? "" : reason);
        if ("sculk_sensor_active_end".equals(reason) && hasCapability(runtime, BlockCapabilityRegistry.Capability.VIBRATION_SENSOR)) {
            setState(runtime, withStateValue(withStateValue(runtime.state, "sculk_sensor_phase", "cooldown"), "power", "0"));
            runtime.sprite.signal("power", 0.0F);
            runtime.sprite.data("__koil_vw_comparator_override", 0);
            runtime.sprite.playSound(SoundEvents.BLOCK_SCULK_SENSOR_CLICKING_STOP, 0.34F, 1.0F);
            requestFunctionalAlignment(runtime, 12);
            schedule(runtime, ScheduledKind.BLOCK, 10, "sculk_sensor_cooldown_end");
            return;
        }
        if ("sculk_sensor_cooldown_end".equals(reason) && hasCapability(runtime, BlockCapabilityRegistry.Capability.VIBRATION_SENSOR)) {
            setState(runtime, withStateValue(withStateValue(runtime.state, "sculk_sensor_phase", "inactive"), "power", "0"));
            runtime.sprite.signal("power", 0.0F);
            requestFunctionalAlignment(runtime, 6);
            return;
        }
        if ("sculk_shrieker_stop".equals(reason) && hasCapability(runtime, BlockCapabilityRegistry.Capability.VIBRATION_SHRIEKER)) {
            setState(runtime, withStateValue(runtime.state, "shrieking", "false"));
            requestFunctionalAlignment(runtime, 8);
            return;
        }
        if ("sculk_catalyst_bloom_end".equals(reason) && hasCapability(runtime, BlockCapabilityRegistry.Capability.SCULK_CATALYST)) {
            setState(runtime, withStateValue(runtime.state, "bloom", "false"));
            requestFunctionalAlignment(runtime, 6);
            return;
        }
        if (hasCapability(runtime, BlockCapabilityRegistry.Capability.FALLING_BLOCK) && canFallInto(screenDown(runtime.cell))) {
            Cell destination = screenDown(runtime.cell);
            RuntimeNode occupant = blockAt(destination);
            if (occupant != null && occupant.state != null && occupant.state.isReplaceable()) removeRuntime(occupant);
            moveRuntime(runtime, destination);
            schedule(runtime, ScheduledKind.BLOCK, FALLING_BLOCK_TICK_DELAY, "falling_block");
            notifyCellAndNeighbors(destination);
            return;
        }
        if ("falling_block".equals(reason) && hasCapability(runtime, BlockCapabilityRegistry.Capability.FALLING_BLOCK)) {
            runtime.sprite.data("__koil_vw_last_landed_tick", worldTick);
            runtime.sprite.data("__koil_vw_grid_motion", "landed");
            runtime.sprite.data("__koil_vw_grid_motion_progress", 1.0F);
            notifyCellAndNeighbors(runtime.cell);
        }
    }

    private void tickRandomBlocks(Host host) {
        if (randomTickSpeed <= 0) return;
        double probability = Math.min(1.0D, randomTickSpeed / 4096.0D);
        for (RuntimeNode runtime : new ArrayList<>(runtimes.values())) {
            if (runtime == null || runtime.state == null || runtime.sprite == null || runtime.sprite.removed()) continue;
            if (!hasCapability(runtime, BlockCapabilityRegistry.Capability.RANDOM_TICKS)) continue;
            if (deterministicUnit(runtime.id * 31L + worldTick * 17L) >= probability) continue;
            runVirtualRandomTick(runtime, host);
        }
    }

    private void runVirtualRandomTick(RuntimeNode runtime, Host host) {
        runtime.sprite.data("__koil_vw_last_random_tick", worldTick);
        runtime.sprite.data("__koil_vw_random_tick_count", longData(runtime.sprite.data("__koil_vw_random_tick_count"), 0L) + 1L);
        Block block = runtime.block;
        if (hasCapability(runtime, BlockCapabilityRegistry.Capability.FIRE_SPREAD)) {
            tickVirtualFire(runtime, host);
            return;
        }
        if (block instanceof CropBlock crop) {
            if (environmentLightAt(runtime.cell) < 9) return;
            int age = crop.getAge(runtime.state);
            int max = crop.getMaxAge();
            if (age < max && randomChance(runtime.id, 3)) setState(runtime, crop.withAge(age + 1));
            return;
        }
        if (block instanceof NetherWartBlock || block instanceof CocoaBlock || block instanceof SweetBerryBushBlock || block instanceof StemBlock) {
            if (!(block instanceof NetherWartBlock) && environmentLightAt(runtime.cell) < 9) return;
            growAgeProperty(runtime, 5);
            return;
        }
        if (block instanceof SugarCaneBlock || block instanceof CactusBlock) {
            int age = intProperty(runtime.state, "age", 0);
            int max = maxPropertyValue(runtime.state, "age", 15);
            if (age >= max) {
                Cell above = screenUp(runtime.cell);
                if (blockAt(above) == null) {
                    host.spawnBlock(block, block.getDefaultState(), above.x() * cellPixels, above.z() * cellPixels, above.y());
                    setState(runtime, withStateValue(runtime.state, "age", "0"));
                }
            } else {
                setState(runtime, withStateValue(runtime.state, "age", String.valueOf(age + 1)));
            }
            return;
        }
        if (block instanceof FarmlandBlock) {
            tickFarmland(runtime);
            return;
        }
        if (block instanceof LeavesBlock) {
            boolean persistent = boolProperty(runtime.state, "persistent", false);
            int distance = intProperty(runtime.state, "distance", 7);
            if (!persistent && distance >= 7) removeRuntime(runtime);
        }
    }

    private void growAgeProperty(RuntimeNode runtime, int chanceDenominator) {
        if (!hasProperty(runtime.state, "age") || !randomChance(runtime.id ^ 0xA63B4C29L, chanceDenominator)) return;
        int age = intProperty(runtime.state, "age", 0);
        int max = maxPropertyValue(runtime.state, "age", age);
        if (age < max) setState(runtime, withStateValue(runtime.state, "age", String.valueOf(age + 1)));
    }

    private void tickFarmland(RuntimeNode runtime) {
        if (!hasProperty(runtime.state, "moisture")) return;
        int moisture = intProperty(runtime.state, "moisture", 0);
        boolean hydrated = hasWaterWithin(runtime.cell, 4) || (environment.raining() && !environment.ultraWarm());
        if (hydrated && moisture < 7) {
            setState(runtime, withStateValue(runtime.state, "moisture", "7"));
        } else if (!hydrated && moisture > 0) {
            setState(runtime, withStateValue(runtime.state, "moisture", String.valueOf(moisture - 1)));
        } else if (!hydrated && moisture <= 0 && !isCropAt(screenUp(runtime.cell))) {
            replaceRuntimeBlock(runtime, Blocks.DIRT, Blocks.DIRT.getDefaultState());
        }
    }

    private boolean isCropAt(Cell cell) {
        RuntimeNode node = blockAt(cell);
        return node != null && (node.block instanceof CropBlock || node.block instanceof StemBlock
                || node.block instanceof NetherWartBlock || node.block instanceof SweetBerryBushBlock);
    }

    private boolean hasWaterWithin(Cell center, int radius) {
        for (int x = center.x() - radius; x <= center.x() + radius; x++) {
            for (int z = center.z() - radius; z <= center.z() + radius; z++) {
                RuntimeNode node = blockAt(new Cell(x, center.y(), z));
                FluidInfo info = fluidInfo(node);
                if (info.water()) return true;
            }
        }
        return false;
    }

    private boolean randomChance(long salt, int denominator) {
        return denominator <= 1 || deterministicInt(salt, denominator) == 0;
    }

    private int deterministicInt(long salt, int bound) {
        if (bound <= 1) return 0;
        long bits = deterministicBits(salt);
        return (int) Long.remainderUnsigned(bits, bound);
    }

    private double deterministicUnit(long salt) {
        return (deterministicBits(salt) >>> 11) * 0x1.0p-53;
    }

    private long deterministicBits(long salt) {
        long x = randomSeed ^ salt ^ (worldTick * 0x9E3779B97F4A7C15L);
        x ^= x >>> 30;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 27;
        x *= 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    /**
     * Entity-contact redstone sources that can be represented by registry item
     * sprites are evaluated on the same virtual grid as the rest of the block
     * world. This removes frame-rate dependent relation TTLs for pressure plates
     * and detector rails. Stone-type pressure plates intentionally do not react
     * to item entities, matching their MOBS activation rule.
     */

    /**
     * Builds a compact virtual game-event stream from moving/dragged sprite entities.
     * This is the common input used by sculk/vibration-aware blocks. It intentionally
     * represents a game-event analogue rather than hard-coding sensors to collisions.
     */
    private void collectVirtualVibrations() {
        if (entityWorld == null) return;
        for (VirtualEntityWorld.GameEvent event : entityWorld.drainGameEvents()) {
            if (event == null) continue;
            Cell origin = new Cell(Math.round(event.x() / cellPixels), event.simulationLayer(), Math.round(event.y() / cellPixels));
            vibrationEvents.add(new VirtualVibration(origin, Math.max(1, Math.min(15, event.frequency())), event.sourceId(), event.kind()));
            if ("primed_tnt_expire".equals(event.kind())) {
                explodeVirtual(origin, 4.0F, event.sourceId());
            }
        }
    }

    private int vibrationFrequency(SpriteNode entity) {
        if (entity == null) return 1;
        if (entity.hasTag("entity:projectile") || entity.hasTag("projectile_item")) return 2;
        if (entity.hasTag("entity:minecart") || entity.hasTag("minecart_item")) return 6;
        if (entity.hasTag("entity:player")) return entity.dragging() ? 11 : 4;
        if (entity.hasTag("entity:living")) return 4;
        if (entity.itemNode()) return entity.dragging() ? 10 : 3;
        return 1;
    }

    private String vibrationKind(SpriteNode entity) {
        if (entity == null) return "movement";
        if (entity.hasTag("entity:projectile") || entity.hasTag("projectile_item")) return "projectile";
        if (entity.hasTag("entity:minecart") || entity.hasTag("minecart_item")) return "minecart";
        if (entity.hasTag("entity:player")) return "player_movement";
        if (entity.itemNode()) return "item_movement";
        return "entity_movement";
    }

    /** Bucket placement/drain is deliberately tied to an actively dragged bucket
     * sprite. Merely falling through water must not silently consume or place fluid. */
    private void tickBucketInteractions(Host host) {
        if (!legacyFluidSimulationEnabled) return;
        LinkedHashMap<Long, SpriteNode> unique = new LinkedHashMap<>();
        for (List<SpriteNode> list : entitiesByCell.values()) {
            if (list == null) continue;
            for (SpriteNode entity : list) if (entity != null && !entity.removed() && entity.itemNode()) unique.putIfAbsent(entity.id(), entity);
        }
        for (SpriteNode itemSprite : unique.values()) {
            if (!itemSprite.dragging()) continue;
            Item item = itemSprite.item();
            if (item != Items.BUCKET && item != Items.WATER_BUCKET && item != Items.LAVA_BUCKET) continue;
            long lastAction = longData(itemSprite.data("__koil_vw_bucket_action_tick"), Long.MIN_VALUE);
            if (worldTick - lastAction < 4L) continue;
            Cell center = new Cell(Math.round(itemSprite.x() / cellPixels), itemSprite.simulationLayer(), Math.round(itemSprite.y() / cellPixels));

            if (item == Items.BUCKET) {
                RuntimeNode target = bucketFluidTarget(center);
                if (target == null) continue;
                FluidInfo info = fluidInfo(target);
                if (!info.valid() || !info.source()) continue;
                Item filled = info.lava() ? Items.LAVA_BUCKET : info.water() ? Items.WATER_BUCKET : Items.AIR;
                if (filled == Items.AIR) continue;
                SoundEvent fillSound = info.fluid().getBucketFillSound().orElse(info.lava() ? SoundEvents.ITEM_BUCKET_FILL_LAVA : SoundEvents.ITEM_BUCKET_FILL);
                if (info.waterlogged()) {
                    setState(target, withStateValue(target.state, "waterlogged", "false"));
                } else {
                    removeRuntime(target);
                }
                itemSprite.item(filled);
                itemSprite.data("__koil_vw_bucket_action_tick", worldTick);
                itemSprite.playSound(fillSound, 0.34F, 1.0F);
                continue;
            }

            boolean water = item == Items.WATER_BUCKET;
            RuntimeNode occupant = blockAt(center);
            if (water && occupant != null && canWaterlog(occupant)) {
                setState(occupant, withStateValue(occupant.state, "waterlogged", "true"));
                requestFunctionalAlignment(occupant, 12);
                itemSprite.item(Items.BUCKET);
                itemSprite.data("__koil_vw_bucket_action_tick", worldTick);
                itemSprite.playSound(SoundEvents.ITEM_BUCKET_EMPTY, 0.34F, 1.0F);
                schedule(occupant, ScheduledKind.FLUID, 1, "bucket_waterlog");
                continue;
            }
            if (occupant == null) {
                Block fluidBlock = water ? Blocks.WATER : Blocks.LAVA;
                BlockState state = standaloneFluidState(water, 8, false);
                host.spawnBlock(fluidBlock, state, center.x() * cellPixels, center.z() * cellPixels, center.y());
                itemSprite.item(Items.BUCKET);
                itemSprite.data("__koil_vw_bucket_action_tick", worldTick);
                itemSprite.playSound(water ? SoundEvents.ITEM_BUCKET_EMPTY : SoundEvents.ITEM_BUCKET_EMPTY_LAVA, 0.34F, 1.0F);
                pendingFluidSpawns.put(center, worldTick);
            }
        }
    }

    private RuntimeNode bucketFluidTarget(Cell center) {
        RuntimeNode exact = blockAt(center);
        FluidInfo exactInfo = fluidInfo(exact);
        if (exactInfo.valid() && exactInfo.source()) return exact;
        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN)) {
            RuntimeNode candidate = blockAt(center.offset(direction));
            FluidInfo info = fluidInfo(candidate);
            if (info.valid() && info.source()) return candidate;
        }
        return null;
    }

    private void tickVibrationServices(Host host) {
        if (vibrationEvents.isEmpty()) return;
        for (RuntimeNode runtime : new ArrayList<>(runtimes.values())) {
            if (runtime == null || runtime.sprite == null || runtime.sprite.removed() || runtime.profile == null) continue;
            if (hasCapability(runtime, BlockCapabilityRegistry.Capability.VIBRATION_SENSOR)) {
                if (!"inactive".equals(stringProperty(runtime.state, "sculk_sensor_phase", "inactive"))) continue;
                boolean calibrated = hasCapability(runtime, BlockCapabilityRegistry.Capability.CALIBRATED_VIBRATION_SENSOR);
                int radius = calibrated ? 16 : 8;
                VirtualVibration vibration = nearestVibration(runtime.cell, radius);
                if (vibration == null || runtime.lastSculkActivationTick == worldTick) continue;
                if (calibrated && !calibratedSensorAccepts(runtime, vibration.frequency())) continue;
                activateSculkSensor(runtime, vibration);
            } else if (hasCapability(runtime, BlockCapabilityRegistry.Capability.VIBRATION_SHRIEKER)) {
                if (boolProperty(runtime.state, "shrieking", false)) continue;
                VirtualVibration vibration = nearestVibration(runtime.cell, 8);
                if (vibration == null) continue;
                activateSculkShrieker(runtime, vibration);
            } else if (hasCapability(runtime, BlockCapabilityRegistry.Capability.SCULK_CATALYST)) {
                VirtualVibration death = nearestVibrationOfKind(runtime.cell, 8, "entity_death", "entity_die");
                if (death != null && !boolProperty(runtime.state, "bloom", false)) activateSculkCatalyst(runtime, death, host);
            }
        }
        vibrationEvents.clear();
    }

    private VirtualVibration nearestVibration(Cell center, int radius) {
        VirtualVibration best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (VirtualVibration vibration : vibrationEvents) {
            if (vibration == null || vibration.origin() == null || vibration.origin().y() != center.y()) continue;
            int dx = vibration.origin().x() - center.x();
            int dz = vibration.origin().z() - center.z();
            int distance = Math.abs(dx) + Math.abs(dz);
            if (distance > radius || distance >= bestDistance) continue;
            if (isVibrationOccluded(vibration.origin(), center)) continue;
            best = vibration;
            bestDistance = distance;
        }
        return best;
    }

    private VirtualVibration nearestVibrationOfKind(Cell center, int radius, String... kinds) {
        Set<String> accepted = new HashSet<>();
        if (kinds != null) for (String kind : kinds) if (kind != null) accepted.add(kind.toLowerCase(Locale.ROOT));
        VirtualVibration best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (VirtualVibration vibration : vibrationEvents) {
            if (vibration == null || vibration.origin() == null || !accepted.contains(vibration.kind())) continue;
            if (vibration.origin().y() != center.y()) continue;
            int distance = Math.abs(vibration.origin().x() - center.x()) + Math.abs(vibration.origin().z() - center.z());
            if (distance > radius || distance >= bestDistance) continue;
            if (isVibrationOccluded(vibration.origin(), center)) continue;
            best = vibration;
            bestDistance = distance;
        }
        return best;
    }

    /** 2D Bresenham-style center ray using Minecraft's vibration-occlusion block tag. */
    private boolean isVibrationOccluded(Cell origin, Cell target) {
        if (origin == null || target == null || origin.y() != target.y()) return false;
        int x0 = origin.x();
        int z0 = origin.z();
        int x1 = target.x();
        int z1 = target.z();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int safety = 0;
        while (!(x0 == x1 && z0 == z1) && safety++ < 128) {
            int twice = err * 2;
            if (twice > -dz) { err -= dz; x0 += sx; }
            if (twice < dx) { err += dx; z0 += sz; }
            if ((x0 == x1 && z0 == z1) || (x0 == origin.x() && z0 == origin.z())) continue;
            RuntimeNode blocker = blockAt(new Cell(x0, origin.y(), z0));
            if (blocker != null && blocker.state != null
                    && blocker.state.isIn(BlockTags.OCCLUDES_VIBRATION_SIGNALS)) return true;
        }
        return false;
    }

    private boolean calibratedSensorAccepts(RuntimeNode sensor, int frequency) {
        Direction facing = facing(sensor.state, Direction.NORTH);
        RuntimeNode rear = blockAt(sensor.cell.offset(facing.getOpposite()));
        int filter = rear == null ? 0 : Math.round(rear.sprite.signal("power"));
        return filter <= 0 || filter == frequency;
    }

    private void activateSculkSensor(RuntimeNode runtime, VirtualVibration vibration) {
        runtime.lastSculkActivationTick = worldTick;
        runtime.sprite.data("__koil_vw_vibration_frequency", vibration.frequency());
        runtime.sprite.data("__koil_vw_vibration_kind", vibration.kind());
        runtime.sprite.data("__koil_vw_vibration_source", vibration.sourceId());
        runtime.sprite.data("__koil_vw_comparator_override", vibration.frequency());
        BlockState next = withStateValue(runtime.state, "sculk_sensor_phase", "active");
        next = withStateValue(next, "power", "15");
        setState(runtime, next);
        runtime.sprite.signal("power", 15.0F);
        if (!isWaterlogged(runtime)) runtime.sprite.playSound(SoundEvents.BLOCK_SCULK_SENSOR_CLICKING, 0.36F, 0.8F + vibration.frequency() / 30.0F);
        emitSculkResonance(runtime, vibration);
        requestFunctionalAlignment(runtime, 36);
        schedule(runtime, ScheduledKind.BLOCK, 30, "sculk_sensor_active_end");
    }

    private void emitSculkResonance(RuntimeNode sensor, VirtualVibration source) {
        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN)) {
            RuntimeNode adjacent = blockAt(sensor.cell.offset(direction));
            if (adjacent == null || adjacent.block != Blocks.AMETHYST_BLOCK) continue;
            adjacent.sprite.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 0.55F, 0.75F + source.frequency() / 30.0F);
            vibrationEvents.add(new VirtualVibration(adjacent.cell, source.frequency(), adjacent.id,
                    "resonate_" + Math.max(1, Math.min(15, source.frequency()))));
            adjacent.sprite.data("__koil_vw_resonation_frequency", source.frequency());
            adjacent.sprite.data("__koil_vw_resonation_tick", worldTick);
        }
    }

    private void activateSculkShrieker(RuntimeNode runtime, VirtualVibration vibration) {
        setState(runtime, withStateValue(runtime.state, "shrieking", "true"));
        runtime.sprite.data("__koil_vw_vibration_frequency", vibration.frequency());
        runtime.sprite.data("__koil_vw_vibration_kind", vibration.kind());
        runtime.sprite.data("__koil_vw_shriek_tick", worldTick);
        if (!isWaterlogged(runtime)) runtime.sprite.playSound(SoundEvents.BLOCK_SCULK_SHRIEKER_SHRIEK, 0.52F, 1.0F);
        requestFunctionalAlignment(runtime, 96);
        schedule(runtime, ScheduledKind.BLOCK, 90, "sculk_shrieker_stop");
    }

    private void activateSculkCatalyst(RuntimeNode runtime, VirtualVibration vibration, Host host) {
        setState(runtime, withStateValue(runtime.state, "bloom", "true"));
        runtime.sprite.data("__koil_vw_catalyst_bloom_source", vibration.sourceId());
        runtime.sprite.data("__koil_vw_catalyst_bloom_tick", worldTick);
        runtime.sprite.playSound(SoundEvents.BLOCK_SCULK_CATALYST_BLOOM, 0.46F, 1.0F);
        requestFunctionalAlignment(runtime, 12);
        schedule(runtime, ScheduledKind.BLOCK, 8, "sculk_catalyst_bloom_end");
        spreadVirtualSculk(runtime, vibration, host);
    }

    private void spreadVirtualSculk(RuntimeNode catalyst, VirtualVibration vibration, Host host) {
        if (host == null) return;
        int charge = Math.max(1, Math.min(20, intData(catalyst.sprite.data("__koil_vw_sculk_charge"), 5)));
        int attempts = Math.min(6, 1 + charge / 3);
        Direction[] directions = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (int i = 0; i < attempts; i++) {
            Direction direction = directions[deterministicInt(catalyst.id ^ vibration.sourceId() ^ (i * 0x9E37L), directions.length)];
            int distance = 1 + deterministicInt(catalyst.id + i * 31L, 3);
            Cell target = catalyst.cell;
            for (int step = 0; step < distance; step++) target = target.offset(direction);
            RuntimeNode existing = blockAt(target);
            if (existing != null) {
                if (existing.block == Blocks.SCULK || existing.block == Blocks.SCULK_CATALYST) continue;
                if (!existing.state.isReplaceable() && existing.block.getBlastResistance() > 3.0F) continue;
                replaceRuntimeBlock(existing, Blocks.SCULK, Blocks.SCULK.getDefaultState());
            } else {
                host.spawnBlock(Blocks.SCULK, Blocks.SCULK.getDefaultState(), target.x() * cellPixels, target.z() * cellPixels, target.y());
            }
        }
    }

    private void requestFunctionalAlignment(RuntimeNode runtime, int holdTicks) {
        if (runtime == null || runtime.sprite == null || runtime.sprite.dragging()) return;
        runtime.functionalRotationDegrees = functionalRotation(runtime);
        runtime.functionalAlignUntilTick = Math.max(runtime.functionalAlignUntilTick, worldTick + Math.max(1, holdTicks));
    }

    private float functionalRotation(RuntimeNode runtime) {
        // Registered block presentation is driven by BlockState, not by free sprite
        // rotation. Facing/axis properties remain in the state signature while the
        // particle matrix itself stays at zero degrees for grid/world composition.
        return 0.0F;
    }

    private void applyFunctionalAlignment() {
        enforceBlockGridInvariant();
    }

    private static float rotationForDirection(Direction direction) {
        if (direction == null) return 0.0F;
        return switch (direction) {
            case EAST -> 0.0F;
            case SOUTH -> 90.0F;
            case WEST -> 180.0F;
            case NORTH -> 270.0F;
            case UP, DOWN -> 0.0F;
        };
    }

    private void writeCoverageReportOnce() {
        if (coverageReportWritten || coverageReportPath == null) return;
        try {
            BlockCapabilityRegistry.writeReport(coverageReportPath);
            coverageReportWritten = true;
        } catch (Exception ignored) {
            // Keep gameplay alive even if the report path is unavailable/read-only.
        }
    }

    private void publishCapabilityProfile(RuntimeNode runtime) {
        if (runtime == null || runtime.sprite == null || runtime.profile == null) return;
        runtime.sprite.data("__koil_vw_capabilities", runtime.profile.capabilityCsv());
        runtime.sprite.data("__koil_vw_services", serviceCsv(runtime.profile, null));
        runtime.sprite.data("__koil_vw_coverage", runtime.profile.overallSupport().name().toLowerCase(Locale.ROOT));
        runtime.sprite.data("__koil_vw_partial_capabilities", runtime.profile.partialCsv());
        runtime.sprite.data("__koil_vw_missing_capabilities", runtime.profile.missingCsv());
        runtime.sprite.data("__koil_vw_partial_services", serviceCsv(runtime.profile, BlockCapabilityRegistry.SupportLevel.PARTIAL));
        runtime.sprite.data("__koil_vw_missing_services", serviceCsv(runtime.profile, BlockCapabilityRegistry.SupportLevel.MISSING));
    }

    private static String serviceCsv(BlockCapabilityRegistry.BlockProfile profile,
                                     BlockCapabilityRegistry.SupportLevel filter) {
        if (profile == null) return "";
        LinkedHashSet<String> services = new LinkedHashSet<>();
        for (BlockCapabilityRegistry.Capability capability : profile.capabilities()) {
            if (filter != null && profile.support(capability) != filter) continue;
            services.add(VirtualBlockServiceRegistry.serviceFor(capability).name().toLowerCase(Locale.ROOT));
        }
        return String.join(",", services);
    }

    private static boolean hasCapability(RuntimeNode runtime, BlockCapabilityRegistry.Capability capability) {
        return runtime != null && runtime.profile != null && runtime.profile.has(capability);
    }

    private boolean useVirtualBlock(RuntimeNode runtime) {
        if (runtime == null || runtime.sprite == null || runtime.block == null) return false;

        if (BlockCapabilityRegistry.isKind(runtime.block, "LeverBlock")) {
            boolean powered = !boolProperty(runtime.state, "powered", runtime.sprite.signal("power") > 0.0F);
            if (hasProperty(runtime.state, "powered")) setState(runtime, withStateValue(runtime.state, "powered", String.valueOf(powered)));
            runtime.sprite.signal("power", powered ? 15.0F : 0.0F);
            runtime.sprite.playSound(GameSpriteSoundResolver.toggleSound(runtime.block, powered), 0.30F, powered ? 1.02F : 0.96F);
            requestFunctionalAlignment(runtime, 4);
            notifyCellAndNeighbors(runtime.cell);
            return true;
        }

        if (BlockCapabilityRegistry.isKind(runtime.block, "ButtonBlock")) {
            boolean powered = boolProperty(runtime.state, "powered", runtime.sprite.signal("power") > 0.0F);
            if (powered) return true;
            if (hasProperty(runtime.state, "powered")) setState(runtime, withStateValue(runtime.state, "powered", "true"));
            runtime.sprite.signal("power", 15.0F);
            runtime.sprite.playSound(GameSpriteSoundResolver.toggleSound(runtime.block, true), 0.30F, 1.0F);
            schedule(runtime, ScheduledKind.POWER_RELEASE, GameSpriteSoundResolver.buttonPressTicks(runtime.block), "button_release");
            requestFunctionalAlignment(runtime, GameSpriteSoundResolver.buttonPressTicks(runtime.block) + 2);
            notifyCellAndNeighbors(runtime.cell);
            return true;
        }

        if (BlockCapabilityRegistry.isKind(runtime.block, "RepeaterBlock")) {
            int delay = intProperty(runtime.state, "delay", 1);
            int next = delay >= 4 ? 1 : delay + 1;
            if (hasProperty(runtime.state, "delay")) setState(runtime, withStateValue(runtime.state, "delay", String.valueOf(next)));
            runtime.sprite.playSound(SoundEvents.BLOCK_COMPARATOR_CLICK, 0.26F, 1.0F);
            requestFunctionalAlignment(runtime, 4);
            return true;
        }

        if (BlockCapabilityRegistry.isKind(runtime.block, "ComparatorBlock")) {
            String mode = stringProperty(runtime.state, "mode", "compare");
            String next = "subtract".equals(mode) ? "compare" : "subtract";
            if (hasProperty(runtime.state, "mode")) setState(runtime, withStateValue(runtime.state, "mode", next));
            runtime.sprite.playSound(SoundEvents.BLOCK_COMPARATOR_CLICK, 0.26F, "subtract".equals(next) ? 1.05F : 0.95F);
            requestFunctionalAlignment(runtime, 4);
            notifyCellAndNeighbors(runtime.cell);
            return true;
        }

        if (BlockCapabilityRegistry.isKind(runtime.block, "DaylightDetectorBlock")) {
            boolean inverted = boolProperty(runtime.state, "inverted", false);
            if (hasProperty(runtime.state, "inverted")) setState(runtime, withStateValue(runtime.state, "inverted", String.valueOf(!inverted)));
            runtime.sprite.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.22F, 1.0F);
            notifyCellAndNeighbors(runtime.cell);
            return true;
        }

        if (BlockCapabilityRegistry.isKind(runtime.block, "DoorBlock", "TrapdoorBlock", "FenceGateBlock")
                && canOpenByHand(runtime.block)) {
            boolean open = !boolProperty(runtime.state, "open", false);
            if (hasProperty(runtime.state, "open")) setState(runtime, withStateValue(runtime.state, "open", String.valueOf(open)));
            runtime.sprite.data("__koil_vw_open", open);
            runtime.sprite.playSound(GameSpriteSoundResolver.openCloseSound(runtime.block, open), 0.32F, 1.0F);
            requestFunctionalAlignment(runtime, 6);
            syncCompoundBlocks();
            notifyCellAndNeighbors(runtime.cell);
            return true;
        }

        if (BlockCapabilityRegistry.isKind(runtime.block, "NoteBlock")) {
            int note = intProperty(runtime.state, "note", 0);
            int next = (note + 1) % 25;
            if (hasProperty(runtime.state, "note")) setState(runtime, withStateValue(runtime.state, "note", String.valueOf(next)));
            playVirtualNote(runtime, next);
            return true;
        }

        if (runtime.block instanceof ChiseledBookshelfBlock && runtime.inventorySize > 0) {
            int slot = lastNonEmptySlot(runtime.inventory);
            if (slot >= 0) {
                ItemStack removed = runtime.inventory.get(slot).copy();
                removed.setCount(1);
                runtime.inventory.get(slot).decrement(1);
                if (runtime.inventory.get(slot).isEmpty()) runtime.inventory.set(slot, ItemStack.EMPTY);
                syncAndPersistInventoryGroup(runtime);
                if (activeHost != null) activeHost.spawnItem(removed, runtime.sprite.x(), runtime.sprite.y() - 8.0F,
                        runtime.sprite.simulationLayer(), 0.0F, -26.0F);
                runtime.sprite.playSound(SoundEvents.BLOCK_CHISELED_BOOKSHELF_PICKUP, 0.30F, 1.0F);
            }
            return true;
        }

        if (runtime.block instanceof JukeboxBlock && runtime.inventorySize > 0 && !runtime.inventory.get(0).isEmpty()) {
            ItemStack removed = runtime.inventory.get(0).copy();
            runtime.inventory.set(0, ItemStack.EMPTY);
            syncAndPersistInventoryGroup(runtime);
            runtime.sprite.signal("active", 0.0F);
            if (activeHost != null) activeHost.spawnItem(removed, runtime.sprite.x(), runtime.sprite.y() - 8.0F,
                    runtime.sprite.simulationLayer(), 0.0F, -28.0F);
            return true;
        }

        if (runtime.block instanceof LecternBlock && runtime.inventorySize > 0 && !runtime.inventory.get(0).isEmpty()) {
            runtime.sprite.playSound(SoundEvents.ITEM_BOOK_PAGE_TURN, 0.24F, 1.0F);
            return true;
        }

        if (BlockCapabilityRegistry.isKind(runtime.block, "BellBlock")) {
            runtime.sprite.playSound(SoundEvents.BLOCK_BELL_USE, 0.36F, 1.0F);
            vibrationEvents.add(new VirtualVibration(runtime.cell, 6, runtime.id, "bell_ring"));
            return true;
        }

        if (runtime.inventorySize > 0) {
            boolean open = !bool(runtime.sprite.data("__koil_vw_open"));
            runtime.sprite.data("__koil_vw_open", open);
            SoundEvent sound = GameSpriteSoundResolver.containerSound(runtime.block, open);
            if (sound != null) runtime.sprite.playSound(sound, 0.32F, 1.0F);
            if (runtime.block instanceof TrappedChestBlock) {
                runtime.sprite.signal("power", open ? 1.0F : 0.0F);
                notifyCellAndNeighbors(runtime.cell);
            }
            return true;
        }

        return false;
    }

    private boolean useItemOnVirtualBlock(RuntimeNode runtime, SpriteNode itemNode) {
        if (runtime == null || itemNode == null || itemNode.item() == null) return false;
        ItemStack held = stackFromSprite(itemNode);
        if (held.isEmpty()) return false;
        Item item = held.getItem();

        // Buckets are owned by the fluid service and execute from the shared
        // dragged-entity scan so they can target source/waterlogged neighbors.
        if (item == Items.BUCKET || item == Items.WATER_BUCKET || item == Items.LAVA_BUCKET) return false;

        if (hasCapability(runtime, BlockCapabilityRegistry.Capability.TNT_PRIMING)
                && (item == Items.FLINT_AND_STEEL || item == Items.FIRE_CHARGE)) {
            runtime.sprite.data("__koil_vw_ignite", true);
            if (item == Items.FIRE_CHARGE) consumeSpriteStack(itemNode, 1);
            return true;
        }

        if (runtime.block == Blocks.RESPAWN_ANCHOR && item == Items.GLOWSTONE) {
            int charges = intProperty(runtime.state, "charges", 0);
            if (charges >= 4) return true;
            setState(runtime, withStateValue(runtime.state, "charges", String.valueOf(charges + 1)));
            consumeSpriteStack(itemNode, 1);
            runtime.sprite.playSound(SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.34F, 1.0F);
            return true;
        }

        if (runtime.block instanceof ComposterBlock) {
            float chance = 0.0F;
            try { chance = ComposterBlock.ITEM_TO_LEVEL_INCREASE_CHANCE.getFloat(item); }
            catch (RuntimeException ignored) { }
            if (chance > 0.0F) {
                int level = intProperty(runtime.state, "level", 0);
                if (level < 7) {
                    boolean success = deterministicUnit(runtime.id ^ itemNode.id() ^ worldTick) < chance;
                    if (success) setState(runtime, withStateValue(runtime.state, "level", String.valueOf(level + 1)));
                    consumeSpriteStack(itemNode, 1);
                    runtime.sprite.playSound(success ? SoundEvents.BLOCK_COMPOSTER_FILL_SUCCESS : SoundEvents.BLOCK_COMPOSTER_FILL,
                            0.28F, 1.0F);
                    return true;
                }
            }
        }

        if (item == Items.BONE_MEAL && hasProperty(runtime.state, "age")) {
            int maxAge = maxPropertyValue(runtime.state, "age", -1);
            if (maxAge >= 0) {
                setState(runtime, withStateValue(runtime.state, "age", String.valueOf(maxAge)));
                consumeSpriteStack(itemNode, 1);
                runtime.sprite.playSound(SoundEvents.ITEM_BONE_MEAL_USE, 0.30F, 1.0F);
                return true;
            }
        }

        if (item == Items.HONEYCOMB) {
            Optional<BlockState> waxed = HoneycombItem.getWaxedState(runtime.state);
            if (waxed.isPresent()) {
                replaceRuntimeBlock(runtime, waxed.get().getBlock(), waxed.get());
                consumeSpriteStack(itemNode, 1);
                runtime.sprite.playSound(SoundEvents.ITEM_HONEYCOMB_WAX_ON, 0.30F, 1.0F);
                return true;
            }
        }

        if (BlockCapabilityRegistry.isKindItem(item, "AxeItem")) {
            Block target = null;
            try { target = HoneycombItem.WAXED_TO_UNWAXED_BLOCKS.get().get(runtime.block); }
            catch (RuntimeException ignored) { }
            SoundEvent sound = SoundEvents.ITEM_AXE_WAX_OFF;
            if (target == null) {
                target = Oxidizable.getDecreasedOxidationBlock(runtime.block).orElse(null);
                sound = SoundEvents.ITEM_AXE_SCRAPE;
            }
            if (target == null) {
                target = strippedVariant(runtime.block);
                sound = SoundEvents.ITEM_AXE_STRIP;
            }
            if (target != null) {
                replaceRuntimeBlock(runtime, target, target.getDefaultState());
                runtime.sprite.playSound(sound, 0.30F, 1.0F);
                return true;
            }
        }

        if (BlockCapabilityRegistry.isKindItem(item, "HoeItem")) {
            Block target = null;
            if (runtime.block == Blocks.DIRT || runtime.block == Blocks.GRASS_BLOCK || runtime.block == Blocks.DIRT_PATH) target = Blocks.FARMLAND;
            else if (runtime.block == Blocks.COARSE_DIRT || runtime.block == Blocks.ROOTED_DIRT) target = Blocks.DIRT;
            if (target != null) {
                replaceRuntimeBlock(runtime, target, target.getDefaultState());
                runtime.sprite.playSound(SoundEvents.ITEM_HOE_TILL, 0.30F, 1.0F);
                return true;
            }
        }

        if (BlockCapabilityRegistry.isKindItem(item, "ShovelItem")) {
            if (runtime.block == Blocks.DIRT || runtime.block == Blocks.GRASS_BLOCK || runtime.block == Blocks.COARSE_DIRT
                    || runtime.block == Blocks.PODZOL || runtime.block == Blocks.MYCELIUM || runtime.block == Blocks.ROOTED_DIRT) {
                replaceRuntimeBlock(runtime, Blocks.DIRT_PATH, Blocks.DIRT_PATH.getDefaultState());
                runtime.sprite.playSound(SoundEvents.ITEM_SHOVEL_FLATTEN, 0.30F, 1.0F);
                return true;
            }
        }

        if ((item == Items.FLINT_AND_STEEL || item == Items.FIRE_CHARGE)
                && hasProperty(runtime.state, "lit")
                && BlockCapabilityRegistry.isKind(runtime.block, "CampfireBlock", "CandleBlock", "CandleCakeBlock")) {
            if (!boolProperty(runtime.state, "lit", false)) {
                setState(runtime, withStateValue(runtime.state, "lit", "true"));
                if (item == Items.FIRE_CHARGE) consumeSpriteStack(itemNode, 1);
                runtime.sprite.playSound(SoundEvents.ITEM_FLINTANDSTEEL_USE, 0.30F, 1.0F);
            }
            return true;
        }

        if (BlockCapabilityRegistry.isKind(runtime.block, "BeehiveBlock")
                && BlockCapabilityRegistry.isKindItem(item, "ShearsItem") && hasProperty(runtime.state, "honey_level")) {
            int honey = intProperty(runtime.state, "honey_level", 0);
            if (honey >= 5) {
                setState(runtime, withStateValue(runtime.state, "honey_level", "0"));
                runtime.sprite.playSound(SoundEvents.BLOCK_BEEHIVE_SHEAR, 0.30F, 1.0F);
            }
            return true;
        }

        if (runtime.block instanceof JukeboxBlock && item instanceof MusicDiscItem) {
            if (runtime.inventorySize > 0 && runtime.inventory.get(0).isEmpty()) {
                ItemStack one = held.copy();
                one.setCount(1);
                runtime.inventory.set(0, one);
                consumeSpriteStack(itemNode, 1);
                syncAndPersistInventoryGroup(runtime);
                runtime.sprite.signal("active", 1.0F);
                if (hasProperty(runtime.state, "has_record")) setState(runtime, withStateValue(runtime.state, "has_record", "true"));
                SoundEvent discSound = ((MusicDiscItem) item).getSound();
                if (discSound != null) runtime.sprite.playSound(discSound, 0.34F, 1.0F);
            }
            return true;
        }

        if (runtime.block instanceof ChiseledBookshelfBlock && isBookshelfBook(held)) {
            for (int slot = 0; slot < Math.min(6, runtime.inventorySize); slot++) {
                if (!runtime.inventory.get(slot).isEmpty()) continue;
                ItemStack one = held.copy();
                one.setCount(1);
                runtime.inventory.set(slot, one);
                runtime.sprite.data("__koil_vw_last_interacted_slot", slot);
                consumeSpriteStack(itemNode, 1);
                syncAndPersistInventoryGroup(runtime);
                runtime.sprite.playSound(item == Items.ENCHANTED_BOOK
                        ? SoundEvents.BLOCK_CHISELED_BOOKSHELF_INSERT_ENCHANTED
                        : SoundEvents.BLOCK_CHISELED_BOOKSHELF_INSERT, 0.30F, 1.0F);
                break;
            }
            return true;
        }

        if (runtime.block instanceof LecternBlock && (item == Items.WRITABLE_BOOK || item == Items.WRITTEN_BOOK)) {
            if (runtime.inventorySize > 0 && runtime.inventory.get(0).isEmpty()) {
                ItemStack one = held.copy();
                one.setCount(1);
                runtime.inventory.set(0, one);
                consumeSpriteStack(itemNode, 1);
                syncAndPersistInventoryGroup(runtime);
                if (hasProperty(runtime.state, "has_book")) setState(runtime, withStateValue(runtime.state, "has_book", "true"));
                runtime.sprite.playSound(SoundEvents.ITEM_BOOK_PUT, 0.30F, 1.0F);
            }
            return true;
        }

        if (hasCapability(runtime, BlockCapabilityRegistry.Capability.COOKING) && runtime.inventorySize >= 3) {
            int slot = fuelTime(item) > 0 ? 1 : 0;
            return insertFromSprite(runtime, itemNode, slot, 1) > 0;
        }

        if (hasCapability(runtime, BlockCapabilityRegistry.Capability.BREWING) && runtime.inventorySize >= 5) {
            int slot;
            if (item == Items.BLAZE_POWDER) slot = 4;
            else if (item == Items.POTION || item == Items.SPLASH_POTION || item == Items.LINGERING_POTION || item == Items.GLASS_BOTTLE) {
                slot = firstEmptySlot(runtime.inventory, 0, 3);
            } else slot = 3;
            return slot >= 0 && insertFromSprite(runtime, itemNode, slot, 1) > 0;
        }

        if (hasCapability(runtime, BlockCapabilityRegistry.Capability.CAMPFIRE_COOKING) && runtime.inventorySize > 0) {
            int slot = firstEmptySlot(runtime.inventory, 0, Math.min(4, runtime.inventorySize));
            return slot >= 0 && insertFromSprite(runtime, itemNode, slot, 1) > 0;
        }

        if (runtime.inventorySize > 0) {
            return insertFromSprite(runtime, itemNode, -1, Math.min(held.getCount(), 64)) > 0;
        }

        return false;
    }

    private boolean handleProjectileHit(RuntimeNode runtime, SpriteNode projectile) {
        if (runtime == null || projectile == null) return false;
        if (BlockCapabilityRegistry.isKind(runtime.block, "TargetBlock")) {
            int power = targetPower(runtime, projectile);
            runtime.sprite.signal("power", power);
            runtime.sprite.data("__koil_vw_target_power", power);
            schedule(runtime, ScheduledKind.POWER_RELEASE, 8, "target_release");
            notifyCellAndNeighbors(runtime.cell);
            vibrationEvents.add(new VirtualVibration(runtime.cell, 2, projectile.id(), "projectile_hit"));
            return true;
        }
        if (BlockCapabilityRegistry.isKind(runtime.block, "BellBlock")) {
            runtime.sprite.playSound(SoundEvents.BLOCK_BELL_USE, 0.36F, 1.0F);
            vibrationEvents.add(new VirtualVibration(runtime.cell, 6, projectile.id(), "bell_ring"));
            return true;
        }
        return false;
    }

    private int targetPower(RuntimeNode target, SpriteNode projectile) {
        float dx = Math.abs(projectile.x() - target.sprite.x()) / Math.max(1.0F, target.sprite.halfWidth());
        float dz = Math.abs(projectile.y() - target.sprite.y()) / Math.max(1.0F, target.sprite.halfHeight());
        float centerDistance = Math.min(1.0F, Math.max(dx, dz));
        return Math.max(1, Math.min(15, 15 - Math.round(centerDistance * 14.0F)));
    }

    private int insertFromSprite(RuntimeNode runtime, SpriteNode itemNode, int preferredSlot, int maxAmount) {
        if (runtime == null || itemNode == null || maxAmount <= 0) return 0;
        ItemStack offered = stackFromSprite(itemNode);
        if (offered.isEmpty()) return 0;
        int before = offered.getCount();
        int amount = Math.min(before, maxAmount);
        ItemStack working = offered.copy();
        working.setCount(amount);
        if (preferredSlot >= 0) insertDirectSlot(runtime, preferredSlot, working);
        else {
            for (RuntimeNode member : inventoryMembers(runtime)) {
                for (int slot = 0; slot < member.inventorySize && !working.isEmpty(); slot++) insertDirectSlot(member, slot, working);
                if (working.isEmpty()) break;
            }
        }
        int inserted = amount - working.getCount();
        if (inserted > 0) {
            consumeSpriteStack(itemNode, inserted);
            syncAndPersistInventoryGroup(runtime);
        }
        return inserted;
    }

    private void consumeSpriteStack(SpriteNode node, int amount) {
        if (node == null || amount <= 0) return;
        ItemStack stack = stackFromSprite(node);
        if (stack.isEmpty()) return;
        stack.decrement(Math.min(amount, stack.getCount()));
        if (stack.isEmpty()) node.remove();
        else writeStackToSprite(node, stack);
    }

    private static int firstEmptySlot(List<ItemStack> stacks, int fromInclusive, int toExclusive) {
        if (stacks == null) return -1;
        int from = Math.max(0, fromInclusive);
        int to = Math.min(stacks.size(), Math.max(from, toExclusive));
        for (int i = from; i < to; i++) if (stacks.get(i).isEmpty()) return i;
        return -1;
    }

    private static int lastNonEmptySlot(List<ItemStack> stacks) {
        if (stacks == null) return -1;
        for (int i = stacks.size() - 1; i >= 0; i--) if (!stacks.get(i).isEmpty()) return i;
        return -1;
    }

    private static boolean isBookshelfBook(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        return item == Items.BOOK || item == Items.WRITABLE_BOOK || item == Items.WRITTEN_BOOK || item == Items.ENCHANTED_BOOK;
    }

    private static boolean canOpenByHand(Block block) {
        if (block == Blocks.IRON_DOOR || block == Blocks.IRON_TRAPDOOR) return false;
        Object type = reflectedField(block, "blockSetType");
        if (type != null) {
            try {
                Method method = type.getClass().getMethod("canOpenByHand");
                Object result = method.invoke(type);
                if (result instanceof Boolean value) return value;
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        return true;
    }

    private void playVirtualNote(RuntimeNode runtime, int note) {
        if (runtime == null || runtime.sprite == null) return;
        String instrumentName = stringProperty(runtime.state, "instrument", "harp");
        Instrument instrument = Instrument.HARP;
        try { instrument = Instrument.valueOf(instrumentName.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { }
        float pitch = (float) Math.pow(2.0D, (Math.max(0, Math.min(24, note)) - 12) / 12.0D);
        runtime.sprite.playSound(instrument.getSound().value(), 0.34F, pitch);
        vibrationEvents.add(new VirtualVibration(runtime.cell, 6, runtime.id, "note_block_play"));
    }

    private static Block strippedVariant(Block block) {
        Identifier id = block == null ? null : Registries.BLOCK.getId(block);
        if (id == null || id.getPath().startsWith("stripped_")) return null;
        Identifier targetId = new Identifier(id.getNamespace(), "stripped_" + id.getPath());
        if (!Registries.BLOCK.containsId(targetId)) return null;
        Block target = Registries.BLOCK.get(targetId);
        return target == Blocks.AIR ? null : target;
    }

    private void syncServerContext() {
        if (serverContext == null) serverContext = new VirtualServerContext();
        serverContext.worldTick(worldTick);
        serverContext.environment(environment.skyLight(), environment.blockLight(), environment.timeOfDay(),
                environment.raining(), environment.thundering(), environment.dimensionId(), environment.temperature(), environment.ultraWarm());
        serverContext.gameRule("randomTickSpeed", randomTickSpeed);
    }

    private void advanceEnvironment() {
        if (advanceEnvironmentTime) {
            environment = new EnvironmentContext(environment.skyLight(), environment.blockLight(),
                    environment.timeOfDay() + 1L, environment.raining(), environment.thundering(),
                    environment.dimensionId(), environment.temperature(), environment.ultraWarm());
        }
        syncServerContext();
        tickEnvironmentDrivenBlocks();
    }

    private int environmentLightAt(Cell cell) {
        return Math.max(environment.skyLight(), environment.blockLight());
    }

    private void tickEnvironmentDrivenBlocks() {
        int daylight = virtualDaylightStrength(environment.timeOfDay(), environment.skyLight());
        for (RuntimeNode runtime : runtimes.values()) {
            if (runtime == null || runtime.sprite == null || runtime.profile == null) continue;
            if (BlockCapabilityRegistry.isKind(runtime.block, "DaylightDetectorBlock")) {
                boolean inverted = reflectedBoolean(runtime.block, "inverted", false);
                int output = inverted ? 15 - daylight : daylight;
                runtime.sprite.signal("power", output);
                if (hasProperty(runtime.state, "power")) setState(runtime, withStateValue(runtime.state, "power", String.valueOf(output)));
                runtime.sprite.data("__koil_vw_environment_daylight", daylight);
            }
        }
    }

    private static int virtualDaylightStrength(long timeOfDay, int skyLight) {
        double angle = (Math.floorMod(timeOfDay, 24000L) / 24000.0D) * Math.PI * 2.0D;
        // Noon (6000) -> strongest, midnight (18000) -> weakest.
        double daylight = (Math.sin(angle) + 1.0D) * 0.5D;
        return clampPower((int) Math.round(daylight * Math.max(0, Math.min(15, skyLight))));
    }

    private void tickFireAndExplosions(Host host) {
        for (RuntimeNode runtime : new ArrayList<>(runtimes.values())) {
            if (runtime == null || runtime.sprite == null || runtime.sprite.removed()) continue;
            if (hasCapability(runtime, BlockCapabilityRegistry.Capability.TNT_PRIMING)) {
                boolean shouldPrime = runtime.sprite.signal("power") > 0.0F
                        || bool(runtime.sprite.data("__koil_vw_ignite"));
                if (shouldPrime && !runtime.tntPrimed) {
                    runtime.tntPrimed = true;
                    runtime.sprite.playSound(SoundEvents.ENTITY_TNT_PRIMED, 0.65F, 1.0F);
                    if (host != null && entityWorld != null) {
                        Map<String, String> data = new LinkedHashMap<>();
                        data.put("__koil_vw_fuse", "80");
                        data.put("__koil_vw_max_health", "1");
                        data.put("__koil_vw_source_block", String.valueOf(runtime.id));
                        host.spawnEntity("minecraft:tnt", runtime.sprite.x(), runtime.sprite.y(), runtime.cell.y(), data);
                        removeRuntime(runtime);
                    }
                }
            }
        }
    }

    private void explodeVirtual(Cell center, float power, long sourceId) {
        if (center == null || power <= 0.0F) return;
        RuntimeNode source = runtimes.get(sourceId);
        if (source != null && source.sprite != null) source.sprite.playSound(SoundEvents.ENTITY_GENERIC_EXPLODE, 0.8F, 0.95F);
        vibrationEvents.add(new VirtualVibration(center, 15, sourceId, "explosion"));

        float radius = Math.max(1.0F, power);
        for (RuntimeNode runtime : new ArrayList<>(runtimes.values())) {
            if (runtime == null || runtime.id == sourceId || runtime.sprite == null || runtime.sprite.removed()) continue;
            if (runtime.cell.y() != center.y()) continue;
            float dx = runtime.cell.x() - center.x();
            float dz = runtime.cell.z() - center.z();
            float distance = (float) Math.sqrt(dx * dx + dz * dz);
            if (distance > radius) continue;
            float exposure = 1.0F - distance / Math.max(0.001F, radius);
            float resistance = Math.max(0.0F, runtime.block.getBlastResistance());
            float available = power * (0.75F + 0.5F * (float) deterministicUnit(runtime.id ^ sourceId));
            if (resistance <= available * Math.max(0.15F, exposure)) removeRuntime(runtime);
        }

        Set<Long> damaged = new HashSet<>();
        for (int x = center.x() - (int) Math.ceil(radius); x <= center.x() + (int) Math.ceil(radius); x++) {
            for (int z = center.z() - (int) Math.ceil(radius); z <= center.z() + (int) Math.ceil(radius); z++) {
                List<SpriteNode> entities = entitiesByCell.get(new Cell(x, center.y(), z));
                if (entities == null) continue;
                for (SpriteNode entity : entities) {
                    if (entity == null || entity.removed() || !damaged.add(entity.id())) continue;
                    float dx = entity.x() / cellPixels - center.x();
                    float dz = entity.y() / cellPixels - center.z();
                    float distance = (float) Math.sqrt(dx * dx + dz * dz);
                    if (distance > radius) continue;
                    float strength = Math.max(0.0F, 1.0F - distance / radius);
                    float length = Math.max(0.001F, (float) Math.sqrt(dx * dx + dz * dz));
                    entity.velocity(entity.velocityX() + dx / length * strength * 180.0F,
                            entity.velocityY() + dz / length * strength * 180.0F);
                    applyVirtualDamage(entity, Math.max(1.0F, strength * power * 4.0F), "explosion");
                }
            }
        }
    }

    private void tickVirtualFire(RuntimeNode fire, Host host) {
        if (fire == null || host == null) return;
        if (environment.raining() && !environment.ultraWarm() && randomChance(fire.id ^ 0xF1E0L, 3)) {
            fire.sprite.playSound(SoundEvents.BLOCK_FIRE_EXTINGUISH, 0.45F, 1.0F);
            removeRuntime(fire);
            return;
        }
        if (hasProperty(fire.state, "age")) {
            int age = intProperty(fire.state, "age", 0);
            int max = maxPropertyValue(fire.state, "age", 15);
            if (age < max && randomChance(fire.id ^ 0xA6E5L, 3)) setState(fire, withStateValue(fire.state, "age", String.valueOf(age + 1)));
        }
        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            Cell target = fire.cell.offset(direction);
            RuntimeNode neighbor = blockAt(target);
            if (neighbor != null) {
                int burn = vanillaFireChance("burnChances", neighbor.block);
                if (burn > 0 && deterministicInt(fire.id ^ neighbor.id ^ worldTick, Math.max(2, 180 / Math.max(1, burn))) == 0) {
                    removeRuntime(neighbor);
                    if (randomChance(fire.id ^ neighbor.id, 3)) {
                        host.spawnBlock(Blocks.FIRE, Blocks.FIRE.getDefaultState(), target.x() * cellPixels, target.z() * cellPixels, target.y());
                    }
                }
            } else {
                int bestSpread = 0;
                for (Direction adjacentDirection : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
                    RuntimeNode adjacent = blockAt(target.offset(adjacentDirection));
                    if (adjacent != null) bestSpread = Math.max(bestSpread, vanillaFireChance("spreadChances", adjacent.block));
                }
                if (bestSpread > 0 && deterministicInt(fire.id ^ target.hashCode() ^ worldTick, Math.max(2, 220 / Math.max(1, bestSpread))) == 0) {
                    host.spawnBlock(Blocks.FIRE, Blocks.FIRE.getDefaultState(), target.x() * cellPixels, target.z() * cellPixels, target.y());
                }
            }
        }
    }

    private int vanillaFireChance(String fieldName, Block block) {
        if (block == null) return 0;
        try {
            Field field = Blocks.FIRE.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object map = field.get(Blocks.FIRE);
            Method getInt = map.getClass().getMethod("getInt", Object.class);
            Object value = getInt.invoke(map, block);
            return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }

    private void tickTripwireNetworks() {
        Set<Long> handled = new HashSet<>();
        for (RuntimeNode hook : new ArrayList<>(runtimes.values())) {
            if (hook == null || handled.contains(hook.id)
                    || !hasCapability(hook, BlockCapabilityRegistry.Capability.TRIPWIRE_NETWORK)
                    || !BlockCapabilityRegistry.isKind(hook.block, "TripwireHookBlock")) continue;
            Direction facing = facing(hook.state, Direction.NORTH);
            List<RuntimeNode> wires = new ArrayList<>();
            RuntimeNode opposite = null;
            for (int distance = 1; distance <= 42; distance++) {
                RuntimeNode node = blockAt(offset(hook.cell, facing, distance));
                if (node == null) break;
                if (BlockCapabilityRegistry.isKind(node.block, "TripwireHookBlock")) {
                    Direction otherFacing = facing(node.state, facing.getOpposite());
                    if (otherFacing == facing.getOpposite()) opposite = node;
                    break;
                }
                if (!BlockCapabilityRegistry.isKind(node.block, "TripwireBlock")) break;
                wires.add(node);
            }
            boolean attached = opposite != null && !wires.isEmpty();
            boolean powered = attached && wires.stream().anyMatch(wire -> wire.sprite.signal("power") > 0.0F
                    && !boolProperty(wire.state, "disarmed", false));
            applyTripwireHookState(hook, attached, powered);
            if (opposite != null) {
                applyTripwireHookState(opposite, attached, powered);
                handled.add(opposite.id);
            }
            for (RuntimeNode wire : wires) {
                if (hasProperty(wire.state, "attached")) setState(wire, withStateValue(wire.state, "attached", String.valueOf(attached)));
            }
            handled.add(hook.id);
        }
    }

    private void applyTripwireHookState(RuntimeNode hook, boolean attached, boolean powered) {
        boolean oldAttached = boolProperty(hook.state, "attached", false);
        boolean oldPowered = boolProperty(hook.state, "powered", false);
        BlockState next = hook.state;
        if (hasProperty(next, "attached")) next = withStateValue(next, "attached", String.valueOf(attached));
        if (hasProperty(next, "powered")) next = withStateValue(next, "powered", String.valueOf(powered));
        if (next != hook.state) setState(hook, next);
        hook.sprite.signal("power", powered ? 15.0F : 0.0F);
        if (oldAttached != attached) hook.sprite.playSound(attached ? SoundEvents.BLOCK_TRIPWIRE_ATTACH : SoundEvents.BLOCK_TRIPWIRE_DETACH, 0.35F, 1.0F);
        if (oldPowered != powered) hook.sprite.playSound(powered ? SoundEvents.BLOCK_TRIPWIRE_CLICK_ON : SoundEvents.BLOCK_TRIPWIRE_CLICK_OFF, 0.35F, 1.0F);
        requestFunctionalAlignment(hook, 4);
    }

    private static Cell offset(Cell cell, Direction direction, int distance) {
        Cell result = cell;
        for (int i = 0; i < Math.max(0, distance); i++) result = result.offset(direction);
        return result;
    }

    private void tickRailRouting() {
        Set<Long> routed = new HashSet<>();
        for (RuntimeNode rail : runtimes.values()) {
            if (rail == null || !hasCapability(rail, BlockCapabilityRegistry.Capability.RAIL_ROUTING)) continue;
            List<SpriteNode> contacts = entitiesByCell.get(rail.cell);
            if (contacts == null || contacts.isEmpty()) continue;
            String shape = stringProperty(rail.state, "shape", "north_south");
            for (SpriteNode entity : contacts) {
                if (entity == null || entity.removed() || !isMinecartItem(entity.item()) && !entity.hasTag("entity:minecart") && !entity.hasTag("minecart_item")) continue;
                if (!routed.add(entity.id())) continue;
                routeMinecartOnRail(rail, entity, shape);
            }
        }
    }

    private void routeMinecartOnRail(RuntimeNode rail, SpriteNode minecart, String shape) {
        float speed = Math.max(28.0F, (float) Math.hypot(minecart.velocityX(), minecart.velocityY()));
        float sx = minecart.velocityX() >= 0.0F ? 1.0F : -1.0F;
        float sz = minecart.velocityY() >= 0.0F ? 1.0F : -1.0F;
        float dx = 0.0F, dz = 0.0F;
        switch (shape) {
            case "east_west", "ascending_east", "ascending_west" -> dx = Math.abs(minecart.velocityX()) > 0.5F ? sx : 1.0F;
            case "south_east" -> { dx = 0.7071F; dz = 0.7071F; }
            case "south_west" -> { dx = -0.7071F; dz = 0.7071F; }
            case "north_west" -> { dx = -0.7071F; dz = -0.7071F; }
            case "north_east" -> { dx = 0.7071F; dz = -0.7071F; }
            default -> dz = Math.abs(minecart.velocityY()) > 0.5F ? sz : 1.0F;
        }
        if (hasCapability(rail, BlockCapabilityRegistry.Capability.RAIL_POWER)) {
            boolean powered = boolProperty(rail.state, "powered", false) || rail.sprite.signal("power") > 0.0F;
            speed *= powered ? 1.18F : 0.55F;
        }
        speed = Math.min(220.0F, speed);
        minecart.velocity(dx * speed, dz * speed);
        minecart.data("__koil_vw_on_rail", true);
        minecart.data("__koil_vw_rail_shape", shape);
        minecart.data("__koil_vw_rail_id", rail.id);
        requestFunctionalAlignment(rail, 2);
    }

    private void tickDamageContacts() {
        for (RuntimeNode runtime : runtimes.values()) {
            if (runtime == null || !hasCapability(runtime, BlockCapabilityRegistry.Capability.DAMAGE)) continue;
            List<SpriteNode> contacts = entitiesByCell.get(runtime.cell);
            if (contacts == null) continue;
            float damage = damageFor(runtime);
            for (SpriteNode entity : contacts) {
                if (entity == null || entity.removed() || !entity.entityNode()) continue;
                applyVirtualDamage(entity, damage, runtime.registryId);
            }
        }
    }

    private float damageFor(RuntimeNode runtime) {
        if (BlockCapabilityRegistry.isKind(runtime.block, "MagmaBlock", "CactusBlock", "SweetBerryBushBlock", "WitherRoseBlock")) return 1.0F;
        if (BlockCapabilityRegistry.isKind(runtime.block, "PointedDripstoneBlock")) return 2.0F;
        if (BlockCapabilityRegistry.isKind(runtime.block, "CampfireBlock")) return runtime.block == Blocks.SOUL_CAMPFIRE ? 2.0F : 1.0F;
        if (BlockCapabilityRegistry.isKind(runtime.block, "FireBlock", "AbstractFireBlock")) return 1.0F;
        return 1.0F;
    }

    private void applyVirtualDamage(SpriteNode entity, float damage, String source) {
        if (entity == null || damage <= 0.0F || entity.removed() || entityWorld == null) return;
        entityWorld.damage(entity.id(), damage, source == null ? "virtual_world" : source, 10);
    }

    private void tickPortalServices() {
        for (RuntimeNode portal : runtimes.values()) {
            if (portal == null || !hasCapability(portal, BlockCapabilityRegistry.Capability.PORTAL)) continue;
            List<SpriteNode> contacts = entitiesByCell.get(portal.cell);
            if (contacts == null || contacts.isEmpty()) continue;
            RuntimeNode destination = nearestPortalPeer(portal);
            for (SpriteNode entity : contacts) {
                if (entity == null || entity.removed()) continue;
                entity.data("__koil_vw_portal", portal.registryId);
                entity.data("__koil_vw_portal_dimension", serverContext.dimensionId());
                if (destination == null || entityWorld == null) continue;
                entityWorld.teleport(entity.id(), destination.sprite.x(), destination.sprite.y(), destination.cell.y(), 80,
                        String.valueOf(destination.id));
            }
        }
    }

    private RuntimeNode nearestPortalPeer(RuntimeNode source) {
        RuntimeNode best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (RuntimeNode candidate : runtimes.values()) {
            if (candidate == null || candidate == source || !hasCapability(candidate, BlockCapabilityRegistry.Capability.PORTAL)) continue;
            if (!candidate.block.getClass().equals(source.block.getClass())) continue;
            int distance = Math.abs(candidate.cell.x() - source.cell.x()) + Math.abs(candidate.cell.y() - source.cell.y())
                    + Math.abs(candidate.cell.z() - source.cell.z());
            if (distance <= 1 || distance >= bestDistance) continue;
            best = candidate;
            bestDistance = distance;
        }
        return best;
    }

    private void tickBeaconServices() {
        for (RuntimeNode beacon : runtimes.values()) {
            if (beacon == null || !hasCapability(beacon, BlockCapabilityRegistry.Capability.BEACON_STRUCTURE)) continue;
            int levels = beaconPyramidLevels(beacon.cell);
            boolean active = levels > 0 && environment.skyLight() > 0;
            if (active != beacon.beaconActive) {
                beacon.sprite.playSound(active ? SoundEvents.BLOCK_BEACON_ACTIVATE : SoundEvents.BLOCK_BEACON_DEACTIVATE, 0.45F, 1.0F);
                beacon.beaconActive = active;
            }
            beacon.beaconLevels = levels;
            beacon.sprite.data("__koil_vw_beacon_levels", levels);
            beacon.sprite.data("__koil_vw_beacon_active", active);
            if (active && entityWorld != null) {
                float radius = cellPixels * (10.0F + levels * 10.0F);
                for (VirtualEntityWorld.EntitySnapshot player : entityWorld.queryCategory(
                        beacon.sprite.x(), beacon.sprite.y(), beacon.cell.y(), radius, VirtualEntityWorld.Category.PLAYER)) {
                    entityWorld.applyStatusEffect(player.id(), "minecraft:beacon_power", 220, Math.max(0, levels - 1));
                }
            }
        }
    }

    private void tickServerEntityDependentBlocks(Host host) {
        if (entityWorld == null || host == null) return;
        for (RuntimeNode runtime : new ArrayList<>(runtimes.values())) {
            if (runtime == null || runtime.sprite == null || runtime.sprite.removed() || runtime.profile == null) continue;
            if (!hasCapability(runtime, BlockCapabilityRegistry.Capability.SERVER_ENTITY_DEPENDENCY)) continue;

            if (hasCapability(runtime, BlockCapabilityRegistry.Capability.SPAWNER)) {
                if (!serverContext.gameRuleBoolean("doMobSpawning", true)) continue;
                long nextSpawn = longData(runtime.sprite.data("__koil_vw_spawner_next_tick"), 0L);
                if (worldTick < nextSpawn) continue;
                String entityType = runtime.sprite.data("__koil_vw_spawner_entity");
                if (entityType == null || entityType.isBlank()) entityType = "minecraft:pig";
                int nearby = entityWorld.queryRadius(runtime.sprite.x(), runtime.sprite.y(), runtime.cell.y(), cellPixels * 8.0F).size();
                if (nearby < 6) {
                    Map<String, String> data = new LinkedHashMap<>();
                    data.put("__koil_vw_spawn_reason", "spawner");
                    data.put("__koil_vw_spawn_source", String.valueOf(runtime.id));
                    host.spawnEntity(entityType, runtime.sprite.x(), runtime.sprite.y() - cellPixels, runtime.cell.y(), data);
                    runtime.sprite.data("__koil_vw_spawner_last_spawn_tick", worldTick);
                }
                runtime.sprite.data("__koil_vw_spawner_next_tick", worldTick + 200L);
            }
        }
    }

    private int beaconPyramidLevels(Cell beacon) {
        int levels = 0;
        for (int level = 1; level <= 4; level++) {
            int y = beacon.y() - level;
            boolean complete = true;
            for (int x = beacon.x() - level; x <= beacon.x() + level && complete; x++) {
                for (int z = beacon.z() - level; z <= beacon.z() + level; z++) {
                    RuntimeNode base = blockAt(new Cell(x, y, z));
                    if (base == null || base.state == null || !base.state.isIn(BlockTags.BEACON_BASE_BLOCKS)) {
                        complete = false;
                        break;
                    }
                }
            }
            if (!complete) break;
            levels = level;
        }
        return levels;
    }

    private static boolean reflectedBoolean(Object owner, String fieldName, boolean fallback) {
        if (owner == null || fieldName == null) return fallback;
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(owner);
                return value instanceof Boolean bool ? bool : fallback;
            } catch (ReflectiveOperationException ignored) {
                type = type.getSuperclass();
            }
        }
        return fallback;
    }

    private static float floatData(String value, float fallback) {
        try { return value == null || value.isBlank() ? fallback : Float.parseFloat(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private void tickContactSources() {
        for (RuntimeNode runtime : runtimes.values()) {
            int desired = -1;
            int releaseRate = 20;

            EntityContactSummary contacts = summarizeContacts(runtime.cell);
            publishContactSummary(runtime, contacts);
            if (runtime.block instanceof WeightedPressurePlateBlock) {
                int entities = contacts.all();
                int weight = Math.max(1, reflectedInt(runtime.block, "weight", 15));
                desired = entities <= 0 ? 0 : clampPower((int) Math.ceil(Math.min(entities, weight) / (double) weight * 15.0D));
                releaseRate = 10;
            } else if (runtime.block instanceof PressurePlateBlock) {
                boolean itemSensitive = pressurePlateAcceptsItems(runtime.block);
                desired = (itemSensitive ? contacts.all() : contacts.living()) > 0 ? 15 : 0;
            } else if (runtime.block instanceof DetectorRailBlock) {
                desired = contacts.minecarts() > 0 ? 15 : 0;
            } else if (hasCapability(runtime, BlockCapabilityRegistry.Capability.TRIPWIRE_NETWORK)
                    && BlockCapabilityRegistry.isKind(runtime.block, "TripwireBlock")) {
                desired = !boolProperty(runtime.state, "disarmed", false) && contacts.all() > 0 ? 15 : 0;
                releaseRate = 10;
            }

            if (desired < 0) continue;
            int current = clampPower(Math.round(runtime.sprite.signal("power")));
            int next = current;
            if (desired > 0) {
                next = desired;
                runtime.contactReleaseTicks = releaseRate;
            } else if (current > 0) {
                if (runtime.contactReleaseTicks <= 0) runtime.contactReleaseTicks = releaseRate;
                if (--runtime.contactReleaseTicks <= 0) next = 0;
            } else {
                runtime.contactReleaseTicks = 0;
                next = 0;
            }

            if (next != current) {
                runtime.sprite.signal("power", next);
                if (runtime.block instanceof PressurePlateBlock || runtime.block instanceof WeightedPressurePlateBlock) {
                    SoundEvent sound = GameSpriteSoundResolver.pressurePlateSound(runtime.block, next > 0);
                    if (sound != null) runtime.sprite.playSound(sound, 0.28F, 1.0F);
                }
            }
            if (hasProperty(runtime.state, "powered")) {
                BlockState changed = withStateValue(runtime.state, "powered", String.valueOf(next > 0));
                if (changed != runtime.state) {
                    runtime.state = changed;
                    writeState(runtime);
                }
            }
            if (hasProperty(runtime.state, "power")) {
                BlockState changed = withStateValue(runtime.state, "power", String.valueOf(next));
                if (changed != runtime.state) {
                    runtime.state = changed;
                    writeState(runtime);
                }
            }
        }
    }

    private EntityContactSummary summarizeContacts(Cell cell) {
        List<SpriteNode> entities = entitiesByCell.get(cell);
        if (entities == null || entities.isEmpty()) return EntityContactSummary.EMPTY;
        int all = 0, items = 0, living = 0, players = 0, minecarts = 0, projectiles = 0;
        Set<Long> unique = new HashSet<>();
        for (SpriteNode node : entities) {
            if (node == null || node.removed() || !node.entityNode() || !unique.add(node.id())) continue;
            all++;
            if (node.itemNode()) items++;
            boolean player = node.hasTag("entity:player") || node.hasTag("player_entity");
            boolean live = player || node.hasTag("entity:living") || node.hasTag("living_entity") || node.hasTag("mob_entity");
            boolean minecart = node.hasTag("entity:minecart") || node.hasTag("minecart_item") || isMinecartItem(node.item());
            boolean projectile = node.hasTag("entity:projectile") || node.hasTag("projectile_item");
            if (player) players++;
            if (live) living++;
            if (minecart) minecarts++;
            if (projectile) projectiles++;
        }
        return new EntityContactSummary(all, items, living, players, minecarts, projectiles);
    }

    private boolean isMinecartItem(Item item) {
        if (item == null || item == Items.AIR) return false;
        Identifier id = Registries.ITEM.getId(item);
        return id != null && id.getPath().endsWith("minecart");
    }

    private void publishContactSummary(RuntimeNode runtime, EntityContactSummary summary) {
        runtime.sprite.data("__koil_vw_entity_contacts", summary.all());
        runtime.sprite.data("__koil_vw_item_contacts", summary.items());
        runtime.sprite.data("__koil_vw_living_contacts", summary.living());
        runtime.sprite.data("__koil_vw_player_contacts", summary.players());
        runtime.sprite.data("__koil_vw_minecart_contacts", summary.minecarts());
        runtime.sprite.data("__koil_vw_projectile_contacts", summary.projectiles());
    }

    private void applyFluidEntityContacts() {
        if (!legacyFluidSimulationEnabled) return;
        // A wide entity can overlap several fluid cells at once. Aggregate by entity
        // id so drag/buoyancy is applied once per virtual tick rather than once per
        // indexed cell. Lava wins over water when an AABB touches both.
        Map<Long, SpriteNode> entities = new LinkedHashMap<>();
        Map<Long, FluidInfo> contacts = new HashMap<>();
        for (Map.Entry<Cell, List<SpriteNode>> entry : entitiesByCell.entrySet()) {
            RuntimeNode runtime = blockAt(entry.getKey());
            FluidInfo info = fluidInfo(runtime);
            if (!info.valid()) continue;
            for (SpriteNode entity : entry.getValue()) {
                if (entity == null || entity.removed() || !entity.entityNode()) continue;
                entities.putIfAbsent(entity.id(), entity);
                FluidInfo previous = contacts.get(entity.id());
                if (previous == null || (!previous.lava() && info.lava()) || (previous.lava() == info.lava() && info.level() > previous.level())) {
                    contacts.put(entity.id(), info);
                }
            }
        }

        for (Map.Entry<Long, FluidInfo> entry : contacts.entrySet()) {
            SpriteNode entity = entities.get(entry.getKey());
            FluidInfo info = entry.getValue();
            if (entity == null || entity.removed()) continue;
            entity.data("__koil_vw_fluid", info.water() ? "water" : info.lava() ? "lava" : "fluid");
            entity.data("__koil_vw_submerged", true);
            if (info.water()) {
                entity.velocity(entity.velocityX() * 0.82F, entity.velocityY() * 0.82F - 2.2F);
            } else if (info.lava()) {
                entity.velocity(entity.velocityX() * 0.55F, entity.velocityY() * 0.55F - 0.8F);
                entity.data("__koil_vw_hot_contact", true);
                applyVirtualDamage(entity, 4.0F, "lava");
            }
        }
    }

    private void tickBubbleColumns(Host host) {
        if (!legacyFluidSimulationEnabled) return;
        // Minecraft bubble columns are water columns driven by soul sand or magma.
        // The virtual world projects vertical block motion onto screen north/south,
        // consistent with the existing falling/fluid projection.
        for (RuntimeNode source : new ArrayList<>(runtimes.values())) {
            if (source == null || source.sprite == null || source.sprite.removed()) continue;
            boolean soulSand = source.block == Blocks.SOUL_SAND;
            boolean magma = source.block == Blocks.MAGMA_BLOCK;
            if (!soulSand && !magma) continue;
            ensureBubbleColumnLine(source.cell, magma, host);
        }

        for (RuntimeNode runtime : new ArrayList<>(runtimes.values())) {
            if (runtime == null || !hasCapability(runtime, BlockCapabilityRegistry.Capability.BUBBLE_COLUMN)) continue;
            Cell supportCell = screenDown(runtime.cell);
            RuntimeNode support = blockAt(supportCell);
            boolean supported = support != null && (support.block == Blocks.SOUL_SAND || support.block == Blocks.MAGMA_BLOCK
                    || hasCapability(support, BlockCapabilityRegistry.Capability.BUBBLE_COLUMN));
            if (!supported) {
                replaceRuntimeBlock(runtime, Blocks.WATER, standaloneFluidState(true, 8, false));
                notifyCellAndNeighbors(runtime.cell);
                continue;
            }

            boolean drag = boolProperty(runtime.state, "drag", false);
            List<SpriteNode> contacts = entitiesByCell.get(runtime.cell);
            if (contacts == null || contacts.isEmpty()) continue;
            for (SpriteNode entity : contacts) {
                if (entity == null || entity.removed() || !entity.entityNode()) continue;
                float vx = entity.velocityX() * 0.78F;
                float vy = entity.velocityY();
                if (drag) vy = Math.min(145.0F, vy + 28.0F);
                else vy = Math.max(-165.0F, vy - 38.0F);
                entity.velocity(vx, vy);
                entity.data("__koil_vw_bubble_column", drag ? "whirlpool" : "upwards");
                entity.data("__koil_vw_bubble_drag", drag);
                long lastSound = longData(entity.data("__koil_vw_bubble_sound_tick"), Long.MIN_VALUE);
                if (lastSound == Long.MIN_VALUE || worldTick - lastSound >= 20L) {
                    entity.playSound(drag ? SoundEvents.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE
                            : SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE, 0.22F, 1.0F);
                    entity.data("__koil_vw_bubble_sound_tick", worldTick);
                }
            }
        }
    }

    private void ensureBubbleColumnLine(Cell sourceCell, boolean drag, Host host) {
        if (sourceCell == null || host == null) return;
        Cell cursor = screenUp(sourceCell);
        for (int distance = 0; distance < 64; distance++) {
            RuntimeNode node = blockAt(cursor);
            if (node == null) break;
            FluidInfo fluid = fluidInfo(node);
            boolean bubble = hasCapability(node, BlockCapabilityRegistry.Capability.BUBBLE_COLUMN);
            if (!bubble && (!fluid.water() || !fluid.source())) break;

            BlockState target = withStateValue(Blocks.BUBBLE_COLUMN.getDefaultState(), "drag", String.valueOf(drag));
            if (bubble) {
                if (boolProperty(node.state, "drag", false) != drag) setState(node, target);
            } else {
                replaceRuntimeBlock(node, Blocks.BUBBLE_COLUMN, target);
            }
            requestFunctionalAlignment(node, 6);
            node.sprite.data("__koil_vw_bubble_source", drag ? "magma_block" : "soul_sand");
            cursor = screenUp(cursor);
        }
    }

    private boolean pressurePlateAcceptsItems(Block block) {
        if (block == null) return false;
        // Vanilla PressurePlateBlock stores the activation rule on the registered block.
        // Read both mapped/decompiled field spellings instead of inferring behavior from
        // a registry path. Weighted plates intentionally accept all entity contacts.
        Object rule = reflectedField(block, "activationRule");
        if (!(rule instanceof Enum<?>)) rule = reflectedField(block, "type");
        if (rule instanceof Enum<?> value) return "everything".equalsIgnoreCase(value.name());
        return BlockCapabilityRegistry.isKind(block, "WeightedPressurePlateBlock");
    }

    private static int reflectedInt(Object owner, String fieldName, int fallback) {
        Object value = reflectedField(owner, fieldName);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static Object reflectedField(Object owner, String fieldName) {
        if (owner == null || fieldName == null || fieldName.isBlank()) return null;
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(owner);
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
            type = type.getSuperclass();
        }
        return null;
    }

    private void tickFluidAt(Cell cell, Host host) {
        if (!legacyFluidSimulationEnabled) return;
        if (cell == null) return;
        RuntimeNode runtime = blockAt(cell);
        if (runtime != null) requestFunctionalAlignment(runtime, 12);
        FluidInfo info = fluidInfo(runtime);
        if (!info.valid()) return;

        if (tryFluidMix(runtime, info)) return;

        // Waterlogged blocks hold a source-level water volume but keep their block
        // identity. Standalone fluid blocks can weaken or disappear when no longer fed.
        if (!info.source() && !info.waterlogged() && runtime != null && runtime.block instanceof FluidBlock) {
            int supported = strongestIncomingFluidLevel(cell, info);
            if (supported <= 0) {
                removeRuntime(runtime);
                notifyCellAndNeighbors(cell);
                return;
            }
            boolean falling = hasSameFluid(screenUp(cell), info);
            updateStandaloneFluid(runtime, info.water(), supported, falling);
            info = fluidInfo(runtime);
        }

        Cell down = screenDown(cell);
        if (flowFluidInto(info, down, true, host)) {
            scheduleFluidCell(down, fluidTickRate(info), "flow_down");
        } else {
            int horizontalLevel = info.source() ? 7 : Math.max(0, info.level() - 1);
            if (horizontalLevel > 0) {
                for (Direction direction : List.of(Direction.WEST, Direction.EAST)) {
                    Cell side = cell.offset(direction);
                    if (flowFluidInto(new FluidInfo(info.fluid(), info.water(), info.lava(), horizontalLevel, false, false, false), side, false, host)) {
                        scheduleFluidCell(side, fluidTickRate(info), "flow_side");
                    }
                }
            }
        }

        if (info.water() && !info.source() && runtime != null && runtime.block instanceof FluidBlock
                && countAdjacentWaterSources(cell) >= 2 && isFluidSupported(screenDown(cell))) {
            updateStandaloneFluid(runtime, true, 8, false);
        }
        scheduleFluidCell(cell, fluidTickRate(info), "fluid_continue");
    }

    private boolean flowFluidInto(FluidInfo source, Cell targetCell, boolean falling, Host host) {
        RuntimeNode target = blockAt(targetCell);
        if (target != null) {
            FluidInfo targetFluid = fluidInfo(target);
            if (targetFluid.valid() && source.lava() != targetFluid.lava()) {
                solidifyLavaContact(source, target, targetFluid);
                return true;
            }
            if (source.water() && canWaterlog(target)) {
                setState(target, withStateValue(target.state, "waterlogged", "true"));
                target.sprite.data("__koil_vw_waterlogged", true);
                notifyCellAndNeighbors(targetCell);
                return true;
            }
            if (targetFluid.valid() && sameFluid(source, targetFluid)) {
                if (!targetFluid.source() && source.level() > targetFluid.level()) {
                    updateStandaloneFluid(target, source.water(), Math.max(1, source.level()), falling);
                    return true;
                }
                return false;
            }
            if (target.state != null && target.state.isReplaceable()) {
                removeRuntime(target);
            } else {
                return false;
            }
        }

        if (pendingFluidSpawns.containsKey(targetCell)) return false;
        int level = falling ? Math.max(1, source.level()) : Math.max(1, Math.min(7, source.level()));
        Block block = source.water() ? Blocks.WATER : Blocks.LAVA;
        BlockState state = standaloneFluidState(source.water(), level, falling);
        pendingFluidSpawns.put(targetCell, worldTick);
        host.spawnBlock(block, state, targetCell.x() * cellPixels, targetCell.z() * cellPixels, targetCell.y());
        return true;
    }

    private boolean tryFluidMix(RuntimeNode runtime, FluidInfo info) {
        if (runtime == null || !info.valid()) return false;
        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            RuntimeNode neighbor = blockAt(runtime.cell.offset(direction));
            FluidInfo other = fluidInfo(neighbor);
            if (!other.valid() || info.lava() == other.lava()) continue;
            if (info.lava()) {
                Block result = info.source() ? Blocks.OBSIDIAN : Blocks.COBBLESTONE;
                replaceRuntimeBlock(runtime, result, result.getDefaultState());
                runtime.sprite.playSound(SoundEvents.BLOCK_LAVA_EXTINGUISH, 0.30F, 1.0F);
            } else if (neighbor != null && other.lava()) {
                Block result = other.source() ? Blocks.OBSIDIAN : Blocks.COBBLESTONE;
                replaceRuntimeBlock(neighbor, result, result.getDefaultState());
                neighbor.sprite.playSound(SoundEvents.BLOCK_LAVA_EXTINGUISH, 0.30F, 1.0F);
            }
            notifyCellAndNeighbors(runtime.cell);
            return true;
        }
        return false;
    }

    private void solidifyLavaContact(FluidInfo source, RuntimeNode target, FluidInfo targetFluid) {
        if (source.lava()) {
            // The incoming lava has no runtime yet, so the existing water target is
            // replaced with stone/cobblestone as the 2D flow analogue.
            Block result = source.source() ? Blocks.OBSIDIAN : Blocks.COBBLESTONE;
            replaceRuntimeBlock(target, result, result.getDefaultState());
            target.sprite.playSound(SoundEvents.BLOCK_LAVA_EXTINGUISH, 0.30F, 1.0F);
        } else if (targetFluid.lava()) {
            Block result = targetFluid.source() ? Blocks.OBSIDIAN : Blocks.COBBLESTONE;
            replaceRuntimeBlock(target, result, result.getDefaultState());
            target.sprite.playSound(SoundEvents.BLOCK_LAVA_EXTINGUISH, 0.30F, 1.0F);
        }
    }

    private int strongestIncomingFluidLevel(Cell cell, FluidInfo fluid) {
        int best = 0;
        RuntimeNode up = blockAt(screenUp(cell));
        FluidInfo upFluid = fluidInfo(up);
        if (sameFluid(fluid, upFluid)) best = Math.max(best, upFluid.source() ? 8 : upFluid.level());
        for (Direction direction : List.of(Direction.WEST, Direction.EAST)) {
            RuntimeNode neighbor = blockAt(cell.offset(direction));
            FluidInfo candidate = fluidInfo(neighbor);
            if (!sameFluid(fluid, candidate)) continue;
            int incoming = candidate.source() ? 7 : Math.max(0, candidate.level() - 1);
            best = Math.max(best, incoming);
        }
        return best;
    }

    private int countAdjacentWaterSources(Cell cell) {
        int count = 0;
        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            FluidInfo candidate = fluidInfo(blockAt(cell.offset(direction)));
            if (candidate.water() && candidate.source()) count++;
        }
        return count;
    }

    private boolean isFluidSupported(Cell cell) {
        RuntimeNode node = blockAt(cell);
        return node != null && node.state != null && !node.state.isReplaceable() && !fluidInfo(node).valid();
    }

    private boolean hasSameFluid(Cell cell, FluidInfo info) {
        return sameFluid(info, fluidInfo(blockAt(cell)));
    }

    private boolean sameFluid(FluidInfo a, FluidInfo b) {
        return a != null && b != null && a.valid() && b.valid() && ((a.water() && b.water()) || (a.lava() && b.lava()) || a.fluid() == b.fluid());
    }

    private FluidInfo fluidInfo(RuntimeNode runtime) {
        if (runtime == null || runtime.state == null) return new FluidInfo(Fluids.EMPTY, false, false, 0, false, false, false);
        try {
            FluidState state = runtime.state.getFluidState();
            if (state == null || state.isEmpty()) return new FluidInfo(Fluids.EMPTY, false, false, 0, false, false, false);
            Fluid fluid = state.getFluid();
            boolean water = state.isOf(Fluids.WATER) || state.isOf(Fluids.FLOWING_WATER);
            boolean lava = state.isOf(Fluids.LAVA) || state.isOf(Fluids.FLOWING_LAVA);
            int level = Math.max(1, state.getLevel());
            boolean source = state.isStill();
            boolean waterlogged = hasProperty(runtime.state, "waterlogged") && boolProperty(runtime.state, "waterlogged", false);
            boolean falling = runtime.block instanceof FluidBlock && intProperty(runtime.state, "level", 0) >= 8;
            return new FluidInfo(fluid, water, lava, level, source, falling, waterlogged);
        } catch (RuntimeException ignored) {
            return new FluidInfo(Fluids.EMPTY, false, false, 0, false, false, false);
        }
    }

    private boolean isWaterlogged(RuntimeNode runtime) {
        return runtime != null && runtime.state != null && hasProperty(runtime.state, "waterlogged")
                && boolProperty(runtime.state, "waterlogged", false);
    }

    private boolean canWaterlog(RuntimeNode runtime) {
        return runtime != null && runtime.block instanceof Waterloggable && hasProperty(runtime.state, "waterlogged")
                && !boolProperty(runtime.state, "waterlogged", false);
    }

    private BlockState standaloneFluidState(boolean water, int level, boolean falling) {
        Block block = water ? Blocks.WATER : Blocks.LAVA;
        int blockLevel;
        if (level >= 8 && !falling) blockLevel = 0;
        else {
            int distance = Math.max(1, 8 - Math.max(1, Math.min(7, level)));
            blockLevel = Math.min(7, distance) + (falling ? 8 : 0);
        }
        try { return block.getDefaultState().with(FluidBlock.LEVEL, Math.max(0, Math.min(15, blockLevel))); }
        catch (RuntimeException ignored) { return block.getDefaultState(); }
    }

    private void updateStandaloneFluid(RuntimeNode runtime, boolean water, int level, boolean falling) {
        if (runtime == null) return;
        Block expected = water ? Blocks.WATER : Blocks.LAVA;
        BlockState state = standaloneFluidState(water, level, falling);
        if (runtime.block != expected) replaceRuntimeBlock(runtime, expected, state);
        else setState(runtime, state);
    }

    private int fluidTickRate(FluidInfo info) {
        return info != null && info.lava() ? 30 : 5;
    }

    private Cell screenDown(Cell cell) {
        return cell.offset(Direction.SOUTH);
    }

    private Cell screenUp(Cell cell) {
        return cell.offset(Direction.NORTH);
    }

    private boolean canFallInto(Cell cell) {
        RuntimeNode node = blockAt(cell);
        return node == null || (node.state != null && node.state.isReplaceable());
    }

    private void notifyCellAndNeighbors(Cell cell) {
        if (cell == null) return;
        scheduleFluidCell(cell, 1, "neighbor_update");
        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN)) {
            Cell neighborCell = cell.offset(direction);
            RuntimeNode neighbor = blockAt(neighborCell);
            if (neighbor == null) continue;
            FluidInfo fluid = fluidInfo(neighbor);
            if (fluid.valid() || isWaterlogged(neighbor)) schedule(neighbor, ScheduledKind.FLUID, 1, "neighbor_update");
            if (hasCapability(neighbor, BlockCapabilityRegistry.Capability.FALLING_BLOCK)) schedule(neighbor, ScheduledKind.BLOCK, 2, "neighbor_update");
        }
    }

    private void setState(RuntimeNode runtime, BlockState state) {
        if (runtime == null || state == null || runtime.state == state) return;
        runtime.state = state;
        writeState(runtime);
        requestFunctionalAlignment(runtime, 6);
        notifyCellAndNeighbors(runtime.cell);
    }

    private void replaceRuntimeBlock(RuntimeNode runtime, Block block, BlockState state) {
        if (runtime == null || block == null || state == null) return;
        runtime.sprite.block(block);
        runtime.sprite.blockState(signature(state));
        runtime.block = block;
        runtime.registryId = "";
        runtime.stateSignature = "";
        refreshBlockRuntime(runtime);
        runtime.state = state;
        writeState(runtime);
        requestFunctionalAlignment(runtime, 8);
        notifyCellAndNeighbors(runtime.cell);
    }

    private void removeRuntime(RuntimeNode runtime) {
        if (runtime == null) return;
        Cell cell = runtime.cell;
        if (runtime.sprite != null) runtime.sprite.remove();
        blocksByCell.remove(cell);
        runtimes.remove(runtime.id);
        previousCells.remove(runtime.id);
        scheduledDueByKey.keySet().removeIf(key -> key.spriteId() == runtime.id);
        notifyCellAndNeighbors(cell);
    }

    private int maxPropertyValue(BlockState state, String name, int fallback) {
        if (state == null) return fallback;
        for (Property<?> property : state.getProperties()) {
            if (!property.getName().equalsIgnoreCase(name)) continue;
            int max = Integer.MIN_VALUE;
            for (Comparable<?> value : property.getValues()) {
                if (value instanceof Number number) max = Math.max(max, number.intValue());
                else {
                    try { max = Math.max(max, Integer.parseInt(String.valueOf(value))); }
                    catch (NumberFormatException ignored) { }
                }
            }
            return max == Integer.MIN_VALUE ? fallback : max;
        }
        return fallback;
    }

    private void tickObservers() {
        for (RuntimeNode runtime : runtimes.values()) {
            if (!isObserver(runtime)) continue;
            Direction facing = facing(runtime.state, directionForRotation(runtime.sprite.rotation()));
            RuntimeNode observed = blockAt(runtime.cell.offset(facing));
            String signature = observed == null ? "air" : observed.registryId + "[" + normalizeSignature(observed.sprite.blockState()) + "]@" + observed.cell;
            if (runtime.observedSignature.isEmpty()) runtime.observedSignature = signature;
            else if (!Objects.equals(runtime.observedSignature, signature)) {
                runtime.observedSignature = signature;
                runtime.observerDelayTicks = 2;
            }
            if (runtime.observerDelayTicks > 0 && --runtime.observerDelayTicks == 0) {
                runtime.observerPulseTicks = OBSERVER_PULSE_TICKS;
            }
        }
    }

    private void tickRedstone() {
        Map<Long, Integer> power = new HashMap<>();
        for (RuntimeNode runtime : runtimes.values()) {
            int source = sourcePower(runtime);
            power.put(runtime.id, source);
        }

        // Redstone dust needs iterative relaxation so an arbitrary connected line
        // attenuates one level per block without relying on particle sensor radius.
        for (int pass = 0; pass < 16; pass++) {
            boolean changed = false;
            for (RuntimeNode runtime : runtimes.values()) {
                if (!isRedstoneWire(runtime)) continue;
                int incoming = 0;
                for (Direction direction : Direction.values()) {
                    RuntimeNode neighbor = blockAt(runtime.cell.offset(direction));
                    if (neighbor == null) continue;
                    incoming = Math.max(incoming, Math.max(0, emittedPowerToward(neighbor, opposite(direction), power) - 1));
                }
                int next = Math.max(sourcePower(runtime), Math.min(15, incoming));
                Integer old = power.put(runtime.id, next);
                if (old == null || old != next) changed = true;
            }
            if (!changed) break;
        }

        // Redstone torches are stateful sources. Their attached block controls the
        // lit state, then the resulting source value participates in the gate pass.
        for (RuntimeNode runtime : runtimes.values()) {
            if (!isRedstoneTorch(runtime)) continue;
            boolean shouldLight = attachedTorchPower(runtime, power) <= 0;
            runtime.state = withStateValue(runtime.state, "lit", String.valueOf(shouldLight));
            power.put(runtime.id, shouldLight ? 15 : 0);
            writeState(runtime);
        }

        for (RuntimeNode runtime : runtimes.values()) {
            if (isRepeater(runtime)) tickRepeater(runtime, power);
            else if (isComparator(runtime)) tickComparator(runtime, power);
        }

        // Gates may have changed after the dust pass. One more pass updates nearby
        // dust/consumers without making gate delays recursive inside the same tick.
        for (RuntimeNode runtime : runtimes.values()) {
            if (isRepeater(runtime) || isComparator(runtime)) power.put(runtime.id, runtime.gateOutput);
            else if (isObserver(runtime)) power.put(runtime.id, runtime.observerPulseTicks > 0 ? 15 : 0);
        }
        for (RuntimeNode runtime : runtimes.values()) {
            int incoming = directNeighborPower(runtime, power);
            int finalPower;
            if (isRedstoneWire(runtime)) finalPower = Math.max(power.getOrDefault(runtime.id, 0), incoming > 0 ? Math.max(0, incoming - 1) : 0);
            else if (isRepeater(runtime) || isComparator(runtime)) finalPower = runtime.gateOutput;
            else if (isObserver(runtime)) finalPower = runtime.observerPulseTicks > 0 ? 15 : 0;
            // Tripwire itself carries a local powered state for hooks to observe, but
            // it is not an omnidirectional redstone source. Preserve the contact
            // signal without injecting it into neighboring redstone consumers.
            else if (isTripwire(runtime)) finalPower = clampPower(Math.round(runtime.sprite.signal("power")));
            else if (isIndependentSource(runtime)) finalPower = sourcePower(runtime);
            else finalPower = incoming;
            setRuntimePower(runtime, finalPower);
        }
    }

    private int sourcePower(RuntimeNode runtime) {
        if (runtime == null) return 0;
        if (isObserver(runtime)) return runtime.observerPulseTicks > 0 ? 15 : 0;
        if (isRepeater(runtime) || isComparator(runtime)) return runtime.gateOutput;
        if (runtime.block == Blocks.REDSTONE_BLOCK) return 15;
        if (isTrappedChest(runtime) && bool(runtime.sprite.data("__koil_open"))) return 1;
        if (isRedstoneTorch(runtime)) return boolProperty(runtime.state, "lit", true) ? 15 : 0;
        if (hasCapability(runtime, BlockCapabilityRegistry.Capability.REDSTONE_POWER_SOURCE)
                && !isRedstoneWire(runtime) && !isTripwire(runtime)) {
            if (hasProperty(runtime.state, "power")) return clampPower(intProperty(runtime.state, "power", 0));
            if (hasProperty(runtime.state, "powered")) return boolProperty(runtime.state, "powered", false) ? 15 : 0;
            if (hasProperty(runtime.state, "lit")) return boolProperty(runtime.state, "lit", false) ? 15 : 0;
            return clampPower(Math.round(runtime.sprite.signal("power")));
        }
        return 0;
    }

    private boolean isIndependentSource(RuntimeNode runtime) {
        if (runtime == null || isRedstoneWire(runtime) || isRepeater(runtime) || isComparator(runtime)
                || isObserver(runtime) || isTripwire(runtime)) return false;
        return runtime.block == Blocks.REDSTONE_BLOCK
                || isTrappedChest(runtime) || isRedstoneTorch(runtime)
                || hasCapability(runtime, BlockCapabilityRegistry.Capability.REDSTONE_POWER_SOURCE);
    }

    private boolean isTripwire(RuntimeNode runtime) {
        return runtime != null && hasCapability(runtime, BlockCapabilityRegistry.Capability.TRIPWIRE_NETWORK)
                && BlockCapabilityRegistry.isKind(runtime.block, "TripwireBlock");
    }

    private int directNeighborPower(RuntimeNode runtime, Map<Long, Integer> power) {
        int incoming = 0;
        for (Direction direction : Direction.values()) {
            RuntimeNode neighbor = blockAt(runtime.cell.offset(direction));
            if (neighbor == null) continue;
            incoming = Math.max(incoming, emittedPowerToward(neighbor, opposite(direction), power));
        }
        return clampPower(incoming);
    }

    private int emittedPowerToward(RuntimeNode emitter, Direction toward, Map<Long, Integer> power) {
        if (emitter == null) return 0;
        int value = power.getOrDefault(emitter.id, sourcePower(emitter));
        if (isRepeater(emitter) || isComparator(emitter)) {
            Direction facing = facing(emitter.state, directionForRotation(emitter.sprite.rotation()));
            return facing == toward ? value : 0;
        }
        if (isObserver(emitter)) {
            Direction facing = facing(emitter.state, directionForRotation(emitter.sprite.rotation()));
            return opposite(facing) == toward ? value : 0;
        }
        return value;
    }

    private void tickRepeater(RuntimeNode runtime, Map<Long, Integer> power) {
        Direction facing = facing(runtime.state, directionForRotation(runtime.sprite.rotation()));
        Direction rear = opposite(facing);
        int rearPower = neighborPower(runtime.cell.offset(rear), facing, power);
        int left = sideGatePower(runtime.cell.offset(rotateLeft(facing)), rotateRight(facing), power);
        int right = sideGatePower(runtime.cell.offset(rotateRight(facing)), rotateLeft(facing), power);
        boolean locked = Math.max(left, right) > 0;
        int desired = rearPower > 0 ? 15 : 0;
        runtime.sprite.data("__koil_vw_repeater_locked", locked);
        runtime.state = withStateValue(runtime.state, "locked", String.valueOf(locked));
        boolean scheduledNow = false;
        if (!locked && desired != runtime.gatePendingOutput) {
            runtime.gatePendingOutput = desired;
            if (desired == runtime.gateOutput) runtime.gateDelayTicks = 0;
            else {
                int delay = intProperty(runtime.state, "delay", 1);
                runtime.gateDelayTicks = Math.max(2, Math.min(8, delay * 2));
                scheduledNow = true;
            }
        }
        if (!locked && !scheduledNow && runtime.gateDelayTicks > 0 && --runtime.gateDelayTicks == 0) {
            runtime.gateOutput = runtime.gatePendingOutput;
        }
        runtime.state = withStateValue(runtime.state, "powered", String.valueOf(runtime.gateOutput > 0));
        writeState(runtime);
    }

    private void tickComparator(RuntimeNode runtime, Map<Long, Integer> power) {
        Direction facing = facing(runtime.state, directionForRotation(runtime.sprite.rotation()));
        Direction rear = opposite(facing);
        RuntimeNode rearNode = blockAt(runtime.cell.offset(rear));
        int rearPower = rearNode == null ? 0 : Math.max(emittedPowerToward(rearNode, facing, power), comparatorAnalogOutput(rearNode));
        int sidePower = Math.max(
                neighborPower(runtime.cell.offset(rotateLeft(facing)), rotateRight(facing), power),
                neighborPower(runtime.cell.offset(rotateRight(facing)), rotateLeft(facing), power));
        String mode = stringProperty(runtime.state, "mode", "compare");
        int desired = "subtract".equals(mode) ? Math.max(0, rearPower - sidePower) : (rearPower >= sidePower ? rearPower : 0);
        desired = clampPower(desired);
        boolean scheduledNow = false;
        if (desired != runtime.gatePendingOutput) {
            runtime.gatePendingOutput = desired;
            runtime.gateDelayTicks = desired == runtime.gateOutput ? 0 : 2;
            scheduledNow = runtime.gateDelayTicks > 0;
        }
        if (!scheduledNow && runtime.gateDelayTicks > 0 && --runtime.gateDelayTicks == 0) {
            runtime.gateOutput = runtime.gatePendingOutput;
        }
        runtime.state = withStateValue(runtime.state, "powered", String.valueOf(runtime.gateOutput > 0));
        writeState(runtime);
    }

    private int sideGatePower(Cell cell, Direction toward, Map<Long, Integer> power) {
        RuntimeNode node = blockAt(cell);
        if (node == null || (!isRepeater(node) && !isComparator(node))) return 0;
        return emittedPowerToward(node, toward, power);
    }

    private int attachedTorchPower(RuntimeNode torch, Map<Long, Integer> power) {
        Direction attachedDirection = Direction.DOWN;
        if (hasProperty(torch.state, "facing")) {
            Direction outward = facing(torch.state, directionForRotation(torch.sprite.rotation()));
            attachedDirection = opposite(outward);
        }
        RuntimeNode attached = blockAt(torch.cell.offset(attachedDirection));
        if (attached == null) return 0;
        return emittedPowerToward(attached, opposite(attachedDirection), power);
    }

    private int neighborPower(Cell cell, Direction toward, Map<Long, Integer> power) {
        RuntimeNode node = blockAt(cell);
        return node == null ? 0 : emittedPowerToward(node, toward, power);
    }

    private void setRuntimePower(RuntimeNode runtime, int power) {
        int clamped = clampPower(power);
        boolean powered = clamped > 0;
        float old = runtime.sprite.signal("power");
        if (Math.abs(old - clamped) > 0.001F) runtime.sprite.signal("power", clamped);
        runtime.sprite.data("__koil_vw_neighbor_power", clamped);
        runtime.sprite.data("powered", powered);

        BlockState state = runtime.state;
        state = withStateValue(state, "power", String.valueOf(clamped));
        state = withStateValue(state, "powered", String.valueOf(powered));
        if (runtime.block instanceof RedstoneLampBlock) {
            if (powered) {
                runtime.lampOffDelayTicks = 0;
                state = withStateValue(state, "lit", "true");
            } else if (boolProperty(state, "lit", false) && runtime.lampOffDelayTicks <= 0) {
                runtime.lampOffDelayTicks = 4;
            }
        }
        if (isHopper(runtime)) state = withStateValue(state, "enabled", String.valueOf(!powered));
        if (isPowerOpenable(runtime) && powered != runtime.previousPowered) {
            state = withStateValue(state, "open", String.valueOf(powered));
            runtime.sprite.data("__koil_open", powered);
        }
        runtime.state = state;
        writeState(runtime);
        runtime.previousPowered = powered;
    }

    private void advanceDelayedRedstoneStates() {
        for (RuntimeNode runtime : runtimes.values()) {
            if (runtime.observerPulseTicks > 0) runtime.observerPulseTicks--;
            if (runtime.lampOffDelayTicks > 0 && --runtime.lampOffDelayTicks == 0
                    && runtime.sprite.signal("power") <= 0.0F) {
                runtime.state = withStateValue(runtime.state, "lit", "false");
                writeState(runtime);
            }
        }
    }

    /**
     * Synchronizes state shared by vanilla multi-block structures that can be
     * represented by multiple sprite cells. A lone sprite still works as a compact
     * 2D representation, while explicitly spawned halves behave as one structure.
     */
    private void syncCompoundBlocks() {
        Set<Long> visited = new HashSet<>();
        for (RuntimeNode runtime : runtimes.values()) {
            if (runtime == null || runtime.sprite == null || !visited.add(runtime.id)) continue;

            RuntimeNode chest = chestPartner(runtime);
            if (chest != null) {
                visited.add(chest.id);
                boolean open = chooseLatestOpen(runtime, chest);
                runtime.sprite.data("__koil_open", open);
                chest.sprite.data("__koil_open", open);
            }

            RuntimeNode door = doorPartner(runtime);
            if (door != null) {
                visited.add(door.id);
                boolean powered = runtime.sprite.signal("power") > 0.0F || door.sprite.signal("power") > 0.0F;
                boolean open = powered || chooseLatestOpen(runtime, door);
                runtime.state = withStateValue(runtime.state, "open", String.valueOf(open));
                door.state = withStateValue(door.state, "open", String.valueOf(open));
                runtime.state = withStateValue(runtime.state, "powered", String.valueOf(powered));
                door.state = withStateValue(door.state, "powered", String.valueOf(powered));
                runtime.sprite.data("__koil_open", open);
                door.sprite.data("__koil_open", open);
                writeState(runtime);
                writeState(door);
            }

            RuntimeNode bed = bedPartner(runtime);
            if (bed != null) {
                visited.add(bed.id);
                boolean occupied = boolProperty(runtime.state, "occupied", false) || boolProperty(bed.state, "occupied", false);
                runtime.state = withStateValue(runtime.state, "occupied", String.valueOf(occupied));
                bed.state = withStateValue(bed.state, "occupied", String.valueOf(occupied));
                writeState(runtime);
                writeState(bed);
            }
        }
    }

    private boolean chooseLatestOpen(RuntimeNode a, RuntimeNode b) {
        long at = longData(a.sprite.data("__koil_open_change_ns"), Long.MIN_VALUE);
        long bt = longData(b.sprite.data("__koil_open_change_ns"), Long.MIN_VALUE);
        if (at == bt) return bool(a.sprite.data("__koil_open")) || bool(b.sprite.data("__koil_open"));
        return at > bt ? bool(a.sprite.data("__koil_open")) : bool(b.sprite.data("__koil_open"));
    }

    private RuntimeNode doorPartner(RuntimeNode runtime) {
        if (runtime == null || !hasProperty(runtime.state, "half") || !hasProperty(runtime.state, "open")) return null;
        String id = runtime.registryId;
        if (!(runtime.block instanceof DoorBlock)) return null;
        String half = stringProperty(runtime.state, "half", "lower");
        Direction vertical = "upper".equals(half) ? Direction.DOWN : Direction.UP;
        RuntimeNode candidate = blockAt(runtime.cell.offset(vertical));
        if (candidate == null || candidate.block != runtime.block) return null;
        String otherHalf = stringProperty(candidate.state, "half", "lower");
        return !Objects.equals(half, otherHalf) ? candidate : null;
    }

    private RuntimeNode bedPartner(RuntimeNode runtime) {
        if (runtime == null || !hasProperty(runtime.state, "part") || !hasProperty(runtime.state, "occupied")) return null;
        if (!(runtime.block instanceof BedBlock)) return null;
        Direction facing = facing(runtime.state, directionForRotation(runtime.sprite.rotation()));
        String part = stringProperty(runtime.state, "part", "foot");
        Direction towardOther = "head".equals(part) ? opposite(facing) : facing;
        RuntimeNode candidate = blockAt(runtime.cell.offset(towardOther));
        if (candidate == null || candidate.block != runtime.block) return null;
        String otherPart = stringProperty(candidate.state, "part", "foot");
        return !Objects.equals(part, otherPart) ? candidate : null;
    }

    private void tickPistons() {
        for (RuntimeNode piston : new ArrayList<>(runtimes.values())) {
            if (!isPiston(piston)) continue;
            boolean powered = piston.sprite.signal("power") > 0.0F;
            boolean extended = boolProperty(piston.state, "extended", piston.pistonExtended);
            if (powered && !extended) {
                if (attemptExtend(piston)) {
                    piston.pistonExtended = true;
                    piston.state = withStateValue(piston.state, "extended", "true");
                    writeState(piston);
                    piston.sprite.playSound(SoundEvents.BLOCK_PISTON_EXTEND, 0.34F, 1.0F);
                }
            } else if (!powered && extended) {
                attemptRetract(piston);
                piston.pistonExtended = false;
                piston.state = withStateValue(piston.state, "extended", "false");
                writeState(piston);
                piston.sprite.playSound(SoundEvents.BLOCK_PISTON_CONTRACT, 0.34F, 1.0F);
            }
        }
    }

    private boolean attemptExtend(RuntimeNode piston) {
        Direction requested = facing(piston.state, directionForRotation(piston.sprite.rotation()));
        Direction movement = effectiveMovementDirection(piston.cell, requested);
        Cell front = piston.cell.offset(movement);
        PushPlan plan = buildMovePlan(front, movement, false, piston);
        if (!plan.valid) return false;
        executeMovePlan(plan, movement);
        return true;
    }

    private void attemptRetract(RuntimeNode piston) {
        if (piston.block != Blocks.STICKY_PISTON) return;
        Direction requested = facing(piston.state, directionForRotation(piston.sprite.rotation()));
        Direction outward = effectiveMovementDirection(piston.cell, requested);
        Cell first = piston.cell.offset(outward);
        RuntimeNode target = blockAt(first.offset(outward));
        if (target == null) return;

        Direction pullDirection = opposite(outward);
        PushPlan plan = buildMovePlan(target.cell, pullDirection, true, piston);
        if (!plan.valid || plan.moving.isEmpty()) return;
        executeMovePlan(plan, pullDirection);
    }

    private Direction effectiveMovementDirection(Cell origin, Direction requested) {
        if (requested != Direction.UP && requested != Direction.DOWN) return requested;
        Cell exact = origin.offset(requested);
        if (blockAt(exact) != null) return requested;
        return requested == Direction.UP ? Direction.NORTH : Direction.SOUTH;
    }

    private static final class PushPlan {
        private final LinkedHashSet<RuntimeNode> moving = new LinkedHashSet<>();
        private final LinkedHashSet<RuntimeNode> destroying = new LinkedHashSet<>();
        private boolean valid = true;
    }

    /**
     * Builds the complete piston movement graph before mutating a sprite. This
     * matters for slime/honey assemblies: an attached side block may itself have
     * a block in front of it, so a simple forward-line scan is not sufficient.
     */
    private PushPlan buildMovePlan(Cell start, Direction movement, boolean pulling, RuntimeNode fixedAnchor) {
        PushPlan plan = new PushPlan();
        RuntimeNode first = blockAt(start);
        if (first == null) return plan;

        Deque<RuntimeNode> queue = new ArrayDeque<>();
        if (!addMoveCandidate(first, pulling, fixedAnchor, plan, queue)) return plan;

        Set<Long> expanded = new HashSet<>();
        while (!queue.isEmpty() && plan.valid) {
            RuntimeNode node = queue.removeFirst();
            if (!expanded.add(node.id)) continue;
            if (plan.moving.size() > MAX_PISTON_MOVED_BLOCKS) {
                plan.valid = false;
                break;
            }

            if (isStickyBlock(node)) {
                for (Direction side : perpendicularDirections(movement)) {
                    RuntimeNode neighbor = blockAt(node.cell.offset(side));
                    if (neighbor == null || !canStick(node, neighbor)) continue;
                    if (!addMoveCandidate(neighbor, pulling, fixedAnchor, plan, queue)) break;
                }
                if (!plan.valid) break;
            }

            RuntimeNode destinationOccupant = blockAt(node.cell.offset(movement));
            if (destinationOccupant != null && !plan.moving.contains(destinationOccupant)
                    && !plan.destroying.contains(destinationOccupant)) {
                if (!addMoveCandidate(destinationOccupant, pulling, fixedAnchor, plan, queue)) break;
            }
        }

        if (plan.moving.size() > MAX_PISTON_MOVED_BLOCKS) plan.valid = false;
        return plan;
    }

    private boolean addMoveCandidate(RuntimeNode node, boolean pulling, RuntimeNode fixedAnchor,
                                     PushPlan plan, Deque<RuntimeNode> queue) {
        if (node == null || plan.moving.contains(node) || plan.destroying.contains(node)) return true;
        if (node == fixedAnchor) {
            plan.valid = false;
            return false;
        }

        PistonBehavior behavior = node.state.getPistonBehavior();
        if (behavior == PistonBehavior.IGNORE) return true;
        if (behavior == PistonBehavior.BLOCK || node.state.hasBlockEntity()) {
            plan.valid = false;
            return false;
        }
        if (pulling && behavior == PistonBehavior.PUSH_ONLY) {
            plan.valid = false;
            return false;
        }
        if (behavior == PistonBehavior.DESTROY) {
            // Destroy behavior applies while pushing. A sticky piston cannot pull
            // such a block, so on retraction it simply remains where it is.
            if (!pulling) plan.destroying.add(node);
            return true;
        }

        plan.moving.add(node);
        queue.addLast(node);
        if (plan.moving.size() > MAX_PISTON_MOVED_BLOCKS) {
            plan.valid = false;
            return false;
        }
        return true;
    }

    private void executeMovePlan(PushPlan plan, Direction direction) {
        if (!plan.valid) return;

        // No mutation occurs until the complete graph validates. This prevents
        // partial destruction/movement when a later branch is immovable.
        for (RuntimeNode destroyed : new ArrayList<>(plan.destroying)) {
            destroyed.sprite.remove();
            blocksByCell.remove(destroyed.cell);
            runtimes.remove(destroyed.id);
            previousCells.remove(destroyed.id);
        }

        List<RuntimeNode> ordered = new ArrayList<>(plan.moving);
        ordered.sort((a, b) -> Integer.compare(projection(b.cell, direction), projection(a.cell, direction)));
        for (RuntimeNode node : ordered) moveRuntime(node, node.cell.offset(direction));
    }

    private void moveRuntime(RuntimeNode runtime, Cell destination) {
        if (runtime == null || runtime.sprite == null || destination == null) return;
        blocksByCell.remove(runtime.cell);
        float startX = runtime.sprite.x();
        float startY = runtime.sprite.y();
        runtime.cell = destination;
        previousCells.put(runtime.id, destination);
        runtime.sprite.data("__koil_vw_x", destination.x());
        runtime.sprite.data("__koil_vw_y", destination.y());
        runtime.sprite.data("__koil_vw_z", destination.z());
        runtime.sprite.data("__koil_vw_locked", true);
        runtime.sprite.data("__koil_vw_grid_locked", true);
        runtime.sprite.rotation(0.0F);
        runtime.sprite.angularVelocity(0.0F);
        runtime.sprite.gravity(0.0F);
        runtime.sprite.velocity(0.0F, 0.0F);
        runtime.gridMotionStartX = startX;
        runtime.gridMotionStartY = startY;
        runtime.gridMotionTargetX = destination.x() * cellPixels;
        runtime.gridMotionTargetY = destination.z() * cellPixels;
        runtime.gridMotionElapsed = 0.0F;
        runtime.gridMotionKind = hasCapability(runtime, BlockCapabilityRegistry.Capability.FALLING_BLOCK)
                ? "fall" : "cell_move";
        runtime.gridMotionDuration = "fall".equals(runtime.gridMotionKind)
                ? FALLING_BLOCK_STEP_DURATION : DEFAULT_GRID_STEP_DURATION;
        runtime.gridMotionActive = Math.abs(startX - runtime.gridMotionTargetX) > 0.001F
                || Math.abs(startY - runtime.gridMotionTargetY) > 0.001F;
        runtime.sprite.data("__koil_vw_grid_motion", runtime.gridMotionKind);
        blocksByCell.put(destination, runtime);
    }

    private void advanceGridMotion(float deltaSeconds) {
        if (deltaSeconds <= 0.0F) return;
        for (RuntimeNode runtime : runtimes.values()) {
            if (runtime == null || runtime.sprite == null || runtime.sprite.removed() || !runtime.gridMotionActive) continue;
            if (runtime.sprite.dragging()) {
                runtime.gridMotionActive = false;
                continue;
            }
            runtime.gridMotionElapsed += deltaSeconds;
            float duration = Math.max(0.001F, runtime.gridMotionDuration);
            float t = Math.max(0.0F, Math.min(1.0F, runtime.gridMotionElapsed / duration));
            float eased;
            if ("fall".equals(runtime.gridMotionKind)) {
                // Falling blocks should read like continuous gravity in a stepped
                // voxel world rather than a delayed teleport. Ease in strongly so
                // repeated one-cell moves chain together into a visible descent.
                eased = t * t;
            } else {
                // Non-falling cell motion stays crisp and readable.
                eased = t * t * (3.0F - 2.0F * t);
            }
            float x = runtime.gridMotionStartX + (runtime.gridMotionTargetX - runtime.gridMotionStartX) * eased;
            float y = runtime.gridMotionStartY + (runtime.gridMotionTargetY - runtime.gridMotionStartY) * eased;
            runtime.sprite.position(x, y);
            runtime.sprite.velocity(0.0F, 0.0F);
            runtime.sprite.rotation(0.0F);
            runtime.sprite.angularVelocity(0.0F);
            runtime.sprite.gravity(0.0F);
            runtime.sprite.data("__koil_vw_grid_motion_progress", t);
            if (t >= 1.0F) {
                runtime.gridMotionActive = false;
                runtime.sprite.position(runtime.gridMotionTargetX, runtime.gridMotionTargetY);
                runtime.sprite.data("__koil_vw_grid_motion", "");
                runtime.sprite.data("__koil_vw_grid_motion_progress", 1.0F);
            }
        }
    }

    private void enforceBlockGridInvariant() {
        for (RuntimeNode runtime : runtimes.values()) {
            if (runtime == null || runtime.sprite == null || runtime.sprite.removed()) continue;
            runtime.sprite.rotation(0.0F);
            runtime.sprite.angularVelocity(0.0F);
            runtime.sprite.gravity(0.0F);
            runtime.sprite.velocity(0.0F, 0.0F);
            runtime.sprite.data("__koil_vw_grid_locked", true);
            runtime.sprite.data("__koil_vw_function_rotation", 0.0F);
            runtime.sprite.data("__koil_vw_function_aligned", true);
            if (!runtime.gridMotionActive && !runtime.sprite.dragging()) {
                runtime.sprite.position(runtime.cell.x() * cellPixels, runtime.cell.z() * cellPixels);
            }
        }
    }

    private int projection(Cell cell, Direction direction) {
        return switch (direction) {
            case EAST -> cell.x();
            case WEST -> -cell.x();
            case SOUTH -> cell.z();
            case NORTH -> -cell.z();
            case UP -> cell.y();
            case DOWN -> -cell.y();
        };
    }

    private List<Direction> perpendicularDirections(Direction movement) {
        return switch (movement.getAxis()) {
            case X -> List.of(Direction.NORTH, Direction.SOUTH, Direction.UP, Direction.DOWN);
            case Y -> List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
            case Z -> List.of(Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN);
        };
    }

    private boolean isStickyBlock(RuntimeNode node) {
        return node != null && (node.block == Blocks.SLIME_BLOCK || node.block == Blocks.HONEY_BLOCK);
    }

    private boolean canStick(RuntimeNode a, RuntimeNode b) {
        if (a == null || b == null) return false;
        if (a.block == Blocks.SLIME_BLOCK && b.block == Blocks.HONEY_BLOCK) return false;
        if (a.block == Blocks.HONEY_BLOCK && b.block == Blocks.SLIME_BLOCK) return false;
        // Glazed terracotta is deliberately excluded from slime/honey adhesion in
        // vanilla piston handling even though a piston can still push it directly.
        if (a.block instanceof GlazedTerracottaBlock || b.block instanceof GlazedTerracottaBlock) return false;
        return true;
    }

    private void tickHoppers(Host host) {
        for (RuntimeNode hopper : runtimes.values()) {
            if (!isHopper(hopper) || hopper.inventorySize <= 0) continue;
            boolean enabled = boolProperty(hopper.state, "enabled", hopper.sprite.signal("power") <= 0.0F);
            if (!enabled) continue;
            if (hopper.hopperCooldown > 0) {
                hopper.hopperCooldown--;
                continue;
            }
            boolean moved = pushFromHopper(hopper) || pullIntoHopper(hopper);
            if (moved) hopper.hopperCooldown = HOPPER_TRANSFER_COOLDOWN;
        }
    }

    private boolean pushFromHopper(RuntimeNode hopper) {
        Direction facing = facing(hopper.state, Direction.DOWN);
        Cell destinationCell = resolvePlanarFallback(hopper.cell, facing);
        RuntimeNode destination = blockAt(destinationCell);
        if (destination == null || destination.inventorySize <= 0) return false;
        for (int slot = 0; slot < hopper.inventory.size(); slot++) {
            ItemStack stack = hopper.inventory.get(slot);
            if (stack.isEmpty()) continue;
            ItemStack one = stack.copy();
            one.setCount(1);
            if (insertStack(destination, one, opposite(facing))) {
                stack.decrement(1);
                if (stack.isEmpty()) hopper.inventory.set(slot, ItemStack.EMPTY);
                return true;
            }
        }
        return false;
    }

    private boolean pullIntoHopper(RuntimeNode hopper) {
        Cell aboveCell = hopper.cell.offset(Direction.UP);
        RuntimeNode above = blockAt(aboveCell);
        if (above == null) {
            // 2D fallback: screen-north behaves as "above" when there is no
            // simulation-layer neighbor.
            above = blockAt(new Cell(hopper.cell.x(), hopper.cell.y(), hopper.cell.z() - 1));
            aboveCell = new Cell(hopper.cell.x(), hopper.cell.y(), hopper.cell.z() - 1);
        }
        if (above != null && above.inventorySize > 0) {
            for (RuntimeNode member : inventoryMembers(above)) {
                for (int slot = 0; slot < member.inventory.size(); slot++) {
                    ItemStack stack = member.inventory.get(slot);
                    if (stack.isEmpty() || !canExtract(member, slot, stack, Direction.DOWN)) continue;
                    ItemStack one = stack.copy();
                    one.setCount(1);
                    if (insertStack(hopper, one, Direction.UP)) {
                        stack.decrement(1);
                        if (stack.isEmpty()) member.inventory.set(slot, ItemStack.EMPTY);
                        syncAndPersistInventoryGroup(above);
                        return true;
                    }
                }
            }
        }

        List<SpriteNode> itemNodes = entityItemsOverlapping(aboveCell);
        if (itemNodes.isEmpty()) itemNodes = entityItemsOverlapping(hopper.cell);
        if (itemNodes.isEmpty()) return false;
        for (SpriteNode itemNode : itemNodes) {
            if (itemNode == null || itemNode.removed() || !itemNode.itemNode()) continue;
            ItemStack stack = stackFromSprite(itemNode);
            int before = stack.getCount();
            insertStackRemainder(hopper, stack, Direction.UP);
            int inserted = before - stack.getCount();
            if (inserted > 0) {
                if (stack.isEmpty()) itemNode.remove();
                else writeStackToSprite(itemNode, stack);
                return true;
            }
        }
        return false;
    }

    private List<SpriteNode> entityItemsOverlapping(Cell cell) {
        if (cell == null) return List.of();
        List<SpriteNode> nodes = entitiesByCell.get(cell);
        if (nodes == null || nodes.isEmpty()) return List.of();
        List<SpriteNode> items = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (SpriteNode node : nodes) {
            if (node == null || node.removed() || !node.itemNode() || !seen.add(node.id())) continue;
            items.add(node);
        }
        return items;
    }

    private void tickFurnaces() {
        for (RuntimeNode furnace : runtimes.values()) {
            if (!(furnace.blockEntityPrototype instanceof AbstractFurnaceBlockEntity) || furnace.inventorySize < 3) continue;
            tickFurnace(furnace);
        }
    }

    private void tickFurnace(RuntimeNode furnace) {
        ItemStack input = furnace.inventory.get(0);
        ItemStack fuel = furnace.inventory.get(1);
        ItemStack output = furnace.inventory.get(2);
        DetachedMinecraftRuntime.CookingKind recipeKind = recipeKindForFurnace(furnace.blockEntityPrototype);
        DetachedMinecraftRuntime.CookingMatch recipe = input.isEmpty() || detachedRuntime == null
                ? null : detachedRuntime.findCooking(recipeKind, input);

        ItemStack recipeOutput = recipe == null ? ItemStack.EMPTY : recipe.output().copy();
        boolean canCook = recipe != null && !recipeOutput.isEmpty()
                && canMergeOutput(output, recipeOutput, furnace.maxStackCount);
        if (furnace.burnTime > 0) furnace.burnTime--;
        if (furnace.burnTime <= 0 && canCook && !fuel.isEmpty()) {
            int burn = fuelTime(fuel.getItem());
            if (burn > 0) {
                furnace.burnTime = burn;
                furnace.fuelTime = burn;
                consumeFuel(furnace, fuel);
            }
        }

        boolean burning = furnace.burnTime > 0;
        if (burning && canCook) {
            if (furnace.cookTimeTotal <= 0 || furnace.cookTime == 0) furnace.cookTimeTotal = Math.max(1, recipe.cookTime());
            furnace.cookTime++;
            if (furnace.cookTime >= furnace.cookTimeTotal) {
                furnace.cookTime = 0;
                furnace.cookTimeTotal = Math.max(1, recipe.cookTime());
                input.decrement(1);
                if (input.isEmpty()) furnace.inventory.set(0, ItemStack.EMPTY);
                if (output.isEmpty()) furnace.inventory.set(2, recipeOutput.copy());
                else output.increment(recipeOutput.getCount());
                furnace.sprite.data("__koil_vw_last_recipe", recipe.recipeId());
                furnace.sprite.data("__koil_vw_last_recipe_source", recipe.sourceMod());
            }
        } else if (!canCook) {
            furnace.cookTime = Math.max(0, furnace.cookTime - 2);
        }

        furnace.sprite.data("__koil_vw_burn_time", furnace.burnTime);
        furnace.sprite.data("__koil_vw_fuel_time", furnace.fuelTime);
        furnace.sprite.data("__koil_vw_cook_time", furnace.cookTime);
        furnace.sprite.data("__koil_vw_cook_time_total", furnace.cookTimeTotal);
        furnace.sprite.data("__koil_vw_runtime_mode", "detached");
        updateFurnaceState(furnace, burning);
    }

    private DetachedMinecraftRuntime.CookingKind recipeKindForFurnace(BlockEntity blockEntity) {
        if (blockEntity instanceof BlastFurnaceBlockEntity) return DetachedMinecraftRuntime.CookingKind.BLASTING;
        if (blockEntity instanceof SmokerBlockEntity) return DetachedMinecraftRuntime.CookingKind.SMOKING;
        return DetachedMinecraftRuntime.CookingKind.SMELTING;
    }

    private int fuelTime(Item item) {
        if (item == null || item == Items.AIR) return 0;
        try {
            Map<Item, Integer> fuels = furnaceFuelTimes;
            if (fuels == null) furnaceFuelTimes = fuels = AbstractFurnaceBlockEntity.createFuelTimeMap();
            return fuels.getOrDefault(item, 0);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private void consumeFuel(RuntimeNode furnace, ItemStack fuel) {
        Item item = fuel.getItem();
        fuel.decrement(1);
        if (fuel.isEmpty()) furnace.inventory.set(1, item == Items.LAVA_BUCKET ? new ItemStack(Items.BUCKET) : ItemStack.EMPTY);
    }

    private boolean canMergeOutput(ItemStack current, ItemStack result, int inventoryMax) {
        if (result.isEmpty()) return false;
        if (current.isEmpty()) return result.getCount() <= Math.min(inventoryMax, result.getMaxCount());
        if (!ItemStack.canCombine(current, result)) return false;
        int max = Math.min(inventoryMax, current.getMaxCount());
        return current.getCount() + result.getCount() <= max;
    }

    private void updateFurnaceState(RuntimeNode furnace, boolean lit) {
        BlockState next = withStateValue(furnace.state, "lit", String.valueOf(lit));
        if (lit && !boolProperty(furnace.state, "lit", false) && worldTick % 20L == 0L) {
            furnace.sprite.playSound(SoundEvents.BLOCK_FURNACE_FIRE_CRACKLE, 0.22F, 1.0F);
        }
        furnace.state = next;
        writeState(furnace);
    }

    private void tickBrewingStands() {
        for (RuntimeNode stand : runtimes.values()) {
            if (!(stand.blockEntityPrototype instanceof BrewingStandBlockEntity) || stand.inventorySize < 5) continue;
            ItemStack ingredient = stand.inventory.get(3);
            ItemStack fuel = stand.inventory.get(4);

            if (stand.brewFuel <= 0 && fuel.isOf(Items.BLAZE_POWDER)) {
                fuel.decrement(1);
                if (fuel.isEmpty()) stand.inventory.set(4, ItemStack.EMPTY);
                stand.brewFuel = 20;
            }

            boolean canBrew = canBrew(stand.inventory, ingredient);
            if (stand.brewTime > 0) {
                stand.brewTime--;
                if (!canBrew || ingredient.isEmpty() || ingredient.getItem() != stand.brewingIngredient) {
                    stand.brewTime = 0;
                } else if (stand.brewTime == 0) {
                    brew(stand);
                    stand.sprite.playSound(SoundEvents.BLOCK_BREWING_STAND_BREW, 0.30F, 1.0F);
                }
            } else if (canBrew && stand.brewFuel > 0) {
                stand.brewFuel--;
                stand.brewTime = 400;
                stand.brewingIngredient = ingredient.getItem();
            }

            stand.sprite.data("__koil_vw_brew_time", stand.brewTime);
            stand.sprite.data("__koil_vw_brew_fuel", stand.brewFuel);
            stand.sprite.signal("ready", canBrew ? 1.0F : 0.0F);
            syncInventoryState(stand);
        }
    }

    private boolean canBrew(List<ItemStack> inventory, ItemStack ingredient) {
        if (inventory == null || inventory.size() < 4 || ingredient == null || ingredient.isEmpty()) return false;
        try {
            for (int i = 0; i < 3; i++) {
                ItemStack bottle = inventory.get(i);
                if (!bottle.isEmpty() && BrewingRecipeRegistry.hasRecipe(bottle, ingredient)) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private void brew(RuntimeNode stand) {
        if (stand == null || stand.inventorySize < 5) return;
        ItemStack ingredient = stand.inventory.get(3);
        if (ingredient.isEmpty()) return;
        try {
            for (int i = 0; i < 3; i++) {
                ItemStack input = stand.inventory.get(i);
                if (input.isEmpty() || !BrewingRecipeRegistry.hasRecipe(input, ingredient)) continue;
                ItemStack output = BrewingRecipeRegistry.craft(ingredient, input);
                if (!output.isEmpty()) stand.inventory.set(i, output);
            }
            ingredient.decrement(1);
            if (ingredient.isEmpty()) stand.inventory.set(3, ItemStack.EMPTY);
        } catch (RuntimeException ignored) { }
        stand.brewingIngredient = Items.AIR;
        syncInventoryState(stand);
    }

    private void tickCampfires(Host host) {
        if (host == null) return;
        for (RuntimeNode campfire : runtimes.values()) {
            if (!isCampfire(campfire) || campfire.inventorySize < 4) continue;
            boolean lit = boolProperty(campfire.state, "lit", true);
            for (int slot = 0; slot < 4; slot++) {
                ItemStack input = campfire.inventory.get(slot);
                if (input.isEmpty()) {
                    campfire.campfireCookTimes[slot] = 0;
                    campfire.campfireCookTotals[slot] = 0;
                    continue;
                }

                DetachedMinecraftRuntime.CookingMatch recipe = campfireRecipe(input);
                if (recipe == null) {
                    campfire.campfireCookTimes[slot] = 0;
                    campfire.campfireCookTotals[slot] = 0;
                    continue;
                }
                if (campfire.campfireCookTotals[slot] <= 0) campfire.campfireCookTotals[slot] = Math.max(1, recipe.cookTime());

                if (lit) campfire.campfireCookTimes[slot]++;
                else campfire.campfireCookTimes[slot] = Math.max(0, campfire.campfireCookTimes[slot] - 2);

                if (lit && campfire.campfireCookTimes[slot] >= campfire.campfireCookTotals[slot]) {
                    ItemStack output = recipe.output().copy();
                    if (!output.isEmpty()) {
                        float sx = campfire.cell.x() * cellPixels;
                        float sy = campfire.cell.z() * cellPixels;
                        host.spawnItem(output, sx, sy - cellPixels * 0.35F, campfire.cell.y(), 0.0F, -24.0F);
                    }
                    campfire.inventory.set(slot, ItemStack.EMPTY);
                    campfire.campfireCookTimes[slot] = 0;
                    campfire.campfireCookTotals[slot] = 0;
                }
                campfire.sprite.data("__koil_vw_campfire_cook_" + slot, campfire.campfireCookTimes[slot]);
                campfire.sprite.data("__koil_vw_campfire_total_" + slot, campfire.campfireCookTotals[slot]);
            }
        }
    }

    private DetachedMinecraftRuntime.CookingMatch campfireRecipe(ItemStack input) {
        if (input == null || input.isEmpty() || detachedRuntime == null) return null;
        return detachedRuntime.findCooking(DetachedMinecraftRuntime.CookingKind.CAMPFIRE, input);
    }

    private void tickDroppersAndDispensers(Host host) {
        if (host == null) return;
        for (RuntimeNode runtime : runtimes.values()) {
            if (!isDropperOrDispenser(runtime) || runtime.inventorySize <= 0) continue;
            boolean powered = runtime.sprite.signal("power") > 0.0F;
            boolean rising = powered && !bool(runtime.sprite.data("__koil_vw_device_powered"));
            runtime.sprite.data("__koil_vw_device_powered", powered);
            if (!rising) continue;
            int slot = firstNonEmptySlot(runtime.inventory);
            if (slot < 0) continue;
            ItemStack stack = runtime.inventory.get(slot);
            Direction facing = facing(runtime.state, directionForRotation(runtime.sprite.rotation()));
            Cell destinationCell = resolvePlanarFallback(runtime.cell, facing);
            RuntimeNode destination = blockAt(destinationCell);
            ItemStack one = stack.copy();
            one.setCount(1);
            boolean inserted = runtime.block instanceof DropperBlock && destination != null && destination.inventorySize > 0
                    && insertStack(destination, one, opposite(facing));
            if (!inserted) {
                float sx = destinationCell.x() * cellPixels;
                float sy = destinationCell.z() * cellPixels;
                float vx = (destinationCell.x() - runtime.cell.x()) * 28.0F;
                float vy = (destinationCell.z() - runtime.cell.z()) * 28.0F;
                host.spawnItem(one, sx, sy, runtime.cell.y(), vx, vy);
            }
            stack.decrement(1);
            if (stack.isEmpty()) runtime.inventory.set(slot, ItemStack.EMPTY);
            runtime.sprite.playSound(SoundEvents.BLOCK_DISPENSER_DISPENSE, 0.30F, 1.0F);
            runtime.state = withStateValue(runtime.state, "triggered", "true");
            writeState(runtime);
            runtime.sprite.data("__koil_vw_trigger_reset_tick", worldTick + 2L);
        }
        for (RuntimeNode runtime : runtimes.values()) {
            long reset = longData(runtime.sprite.data("__koil_vw_trigger_reset_tick"), -1L);
            if (reset >= 0 && worldTick >= reset) {
                runtime.state = withStateValue(runtime.state, "triggered", "false");
                writeState(runtime);
                runtime.sprite.data("__koil_vw_trigger_reset_tick", "");
            }
        }
    }

    private void updateComparatorData() {
        for (RuntimeNode runtime : runtimes.values()) {
            int analog = comparatorAnalogOutput(runtime);
            runtime.sprite.data("__koil_vw_analog_output", analog);
            runtime.sprite.signal("analog_output", analog);
        }
    }

    private int comparatorAnalogOutput(RuntimeNode runtime) {
        if (runtime == null) return 0;
        if (hasCapability(runtime, BlockCapabilityRegistry.Capability.VIBRATION_SENSOR)) {
            return clampPower(intData(runtime.sprite.data("__koil_vw_comparator_override"), 0));
        }
        if (runtime.block instanceof JukeboxBlock && runtime.inventorySize > 0) {
            ItemStack record = runtime.inventory.get(0);
            return record.getItem() instanceof MusicDiscItem disc ? clampPower(disc.getComparatorOutput()) : 0;
        }
        if (runtime.block instanceof ChiseledBookshelfBlock) {
            return clampPower(intData(runtime.sprite.data("__koil_vw_last_interacted_slot"), -1) + 1);
        }
        if (runtime.block instanceof LecternBlock) {
            if (runtime.inventory.isEmpty() || runtime.inventory.get(0).isEmpty()) return 0;
            int page = Math.max(0, intData(runtime.sprite.data("__koil_lectern_page"), 0));
            int pages = Math.max(1, intData(runtime.sprite.data("__koil_lectern_page_count"), 1));
            return Math.max(1, Math.min(15, (int) Math.floor((double) page / Math.max(1, pages - 1) * 14.0D) + 1));
        }
        if (runtime.inventorySize > 0) {
            List<RuntimeNode> members = inventoryMembers(runtime);
            if (members.size() == 1) return inventoryComparatorOutput(runtime.inventory, runtime.maxStackCount);
            List<ItemStack> combined = new ArrayList<>();
            int max = 64;
            for (RuntimeNode member : members) {
                combined.addAll(member.inventory);
                max = Math.min(max, Math.max(1, member.maxStackCount));
            }
            return inventoryComparatorOutput(combined, max);
        }
        return clampPower(intData(runtime.sprite.data("analog_output"), Math.round(runtime.sprite.signal("analog_output"))));
    }

    private int inventoryComparatorOutput(List<ItemStack> inventory, int inventoryMax) {
        if (inventory == null || inventory.isEmpty()) return 0;
        float fullness = 0.0F;
        int occupied = 0;
        for (ItemStack stack : inventory) {
            if (stack == null || stack.isEmpty()) continue;
            int max = Math.max(1, Math.min(inventoryMax, stack.getMaxCount()));
            fullness += (float) stack.getCount() / (float) max;
            occupied++;
        }
        if (occupied == 0) return 0;
        fullness /= inventory.size();
        return Math.max(1, Math.min(15, (int) Math.floor(fullness * 14.0F) + 1));
    }

    private boolean insertStack(RuntimeNode destination, ItemStack stack, Direction side) {
        int before = stack.getCount();
        insertStackRemainder(destination, stack, side);
        return stack.getCount() < before;
    }

    private ItemStack insertStackRemainder(RuntimeNode destination, ItemStack stack, Direction side) {
        if (destination == null || stack == null || stack.isEmpty() || destination.inventorySize <= 0) return stack;
        for (RuntimeNode member : inventoryMembers(destination)) {
            if (!allowsAutomationInventory(member)) continue;
            int[] slots = availableSlots(member, side);
            for (int slot : slots) {
                if (slot < 0 || slot >= member.inventory.size()) continue;
                if (!canInsert(member, slot, stack, side)) continue;
                ItemStack current = member.inventory.get(slot);
                int max = Math.max(1, Math.min(member.maxStackCount, stack.getMaxCount()));
                if (current.isEmpty()) {
                    int moved = Math.min(max, stack.getCount());
                    ItemStack placed = stack.copy();
                    placed.setCount(moved);
                    member.inventory.set(slot, placed);
                    stack.decrement(moved);
                } else if (ItemStack.canCombine(current, stack)) {
                    int room = Math.max(0, Math.min(max, current.getMaxCount()) - current.getCount());
                    int moved = Math.min(room, stack.getCount());
                    if (moved > 0) {
                        current.increment(moved);
                        stack.decrement(moved);
                    }
                }
                if (stack.isEmpty()) break;
            }
            if (stack.isEmpty()) break;
        }
        return stack;
    }

    private record InventorySlotRef(RuntimeNode runtime, int slot) { }

    private List<RuntimeNode> inventoryMembers(RuntimeNode runtime) {
        if (runtime == null || runtime.inventorySize <= 0) return List.of();
        RuntimeNode partner = chestPartner(runtime);
        if (partner == null) return List.of(runtime);

        // Keep ordering deterministic regardless of which half initiated access.
        // The inventories remain physically attached to their own sprite runtimes.
        return runtime.id <= partner.id ? List.of(runtime, partner) : List.of(partner, runtime);
    }

    private InventorySlotRef resolveLogicalSlot(RuntimeNode runtime, int logicalSlot) {
        if (runtime == null || logicalSlot < 0) return null;
        int cursor = logicalSlot;
        for (RuntimeNode member : inventoryMembers(runtime)) {
            if (cursor < member.inventory.size()) return new InventorySlotRef(member, cursor);
            cursor -= member.inventory.size();
        }
        return null;
    }

    private RuntimeNode chestPartner(RuntimeNode runtime) {
        if (runtime == null || !(runtime.block instanceof ChestBlock) || runtime.inventorySize != 27) return null;
        String type = stringProperty(runtime.state, "type", "single");
        if ("single".equals(type)) return null;
        Direction facing = facing(runtime.state, directionForRotation(runtime.sprite.rotation()));
        for (Direction side : List.of(rotateLeft(facing), rotateRight(facing))) {
            RuntimeNode candidate = blockAt(runtime.cell.offset(side));
            if (candidate == null || candidate.block != runtime.block || candidate.inventorySize != 27) continue;
            if (facing(candidate.state, directionForRotation(candidate.sprite.rotation())) != facing) continue;
            String otherType = stringProperty(candidate.state, "type", "single");
            if (("left".equals(type) && "right".equals(otherType))
                    || ("right".equals(type) && "left".equals(otherType))) {
                return candidate;
            }
        }
        return null;
    }

    private void syncAndPersistInventoryGroup(RuntimeNode runtime) {
        for (RuntimeNode member : inventoryMembers(runtime)) {
            syncInventoryState(member);
            persistInventory(member);
        }
    }

    private int[] availableSlots(RuntimeNode runtime, Direction side) {
        if (runtime.sidedInventoryPrototype != null) {
            try { return runtime.sidedInventoryPrototype.getAvailableSlots(side); }
            catch (RuntimeException ignored) { }
        }
        int[] slots = new int[runtime.inventorySize];
        for (int i = 0; i < slots.length; i++) slots[i] = i;
        return slots;
    }

    private boolean canInsert(RuntimeNode runtime, int slot, ItemStack stack, Direction side) {
        if (runtime.sidedInventoryPrototype != null) {
            try { return runtime.sidedInventoryPrototype.canInsert(slot, stack, side); }
            catch (RuntimeException ignored) { }
        }
        return true;
    }

    private boolean canExtract(RuntimeNode runtime, int slot, ItemStack stack, Direction side) {
        if (!allowsAutomationInventory(runtime)) return false;
        if (runtime.sidedInventoryPrototype != null) {
            try { return runtime.sidedInventoryPrototype.canExtract(slot, stack, side); }
            catch (RuntimeException ignored) { }
        }
        return true;
    }

    private void insertDirectSlot(RuntimeNode runtime, int slot, ItemStack stack) {
        if (runtime == null || stack == null || stack.isEmpty() || slot < 0 || slot >= runtime.inventory.size()) return;
        if (!canDirectInsert(runtime, slot, stack)) return;
        ItemStack current = runtime.inventory.get(slot);
        int max = Math.max(1, Math.min(runtime.maxStackCount, stack.getMaxCount()));
        if (runtime.block instanceof LecternBlock || runtime.block instanceof JukeboxBlock
                || runtime.block instanceof ChiseledBookshelfBlock || isCampfire(runtime)) max = 1;
        if (current.isEmpty()) {
            int moved = Math.min(max, stack.getCount());
            ItemStack placed = stack.copy();
            placed.setCount(moved);
            runtime.inventory.set(slot, placed);
            stack.decrement(moved);
        } else if (ItemStack.canCombine(current, stack)) {
            int room = Math.max(0, Math.min(max, current.getMaxCount()) - current.getCount());
            int moved = Math.min(room, stack.getCount());
            if (moved > 0) { current.increment(moved); stack.decrement(moved); }
        }
        if (runtime.block instanceof ChiseledBookshelfBlock && !runtime.inventory.get(slot).isEmpty()) {
            runtime.sprite.data("__koil_vw_last_interacted_slot", slot);
        }
    }

    private boolean canDirectInsert(RuntimeNode runtime, int slot, ItemStack stack) {
        if (runtime == null || stack == null || stack.isEmpty()) return false;
        if (isCampfire(runtime)) return campfireRecipe(stack) != null;
        if (runtime.block instanceof LecternBlock) {
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            return (id.equals("writable_book") || id.equals("written_book")) && runtime.inventory.get(slot).isEmpty();
        }
        if (runtime.blockEntityPrototype instanceof Inventory inventory) {
            try { return inventory.isValid(slot, stack); }
            catch (RuntimeException ignored) { }
        }
        return true;
    }

    private boolean allowsAutomationInventory(RuntimeNode runtime) {
        if (runtime == null) return false;
        return !isCampfire(runtime) && !(runtime.block instanceof LecternBlock);
    }

    private void syncInventoryState(RuntimeNode runtime) {
        if (runtime == null || runtime.state == null) return;
        if (runtime.block instanceof JukeboxBlock && runtime.inventorySize > 0) {
            boolean hasRecord = !runtime.inventory.get(0).isEmpty();
            runtime.state = withStateValue(runtime.state, "has_record", String.valueOf(hasRecord));
            runtime.sprite.data("__koil_jukebox_disc", hasRecord ? Registries.ITEM.getId(runtime.inventory.get(0).getItem()).toString() : "");
            runtime.sprite.signal("active", hasRecord ? 1.0F : 0.0F);
        }
        if (runtime.blockEntityPrototype instanceof BrewingStandBlockEntity && runtime.inventorySize >= 3) {
            for (int i = 0; i < 3; i++) runtime.state = withStateValue(runtime.state, "has_bottle_" + i, String.valueOf(!runtime.inventory.get(i).isEmpty()));
        }
        if (runtime.block instanceof ChiseledBookshelfBlock) {
            for (int i = 0; i < Math.min(6, runtime.inventory.size()); i++) {
                runtime.state = withStateValue(runtime.state, "slot_" + i + "_occupied", String.valueOf(!runtime.inventory.get(i).isEmpty()));
            }
        }
        if (runtime.block instanceof LecternBlock && runtime.inventorySize > 0) {
            runtime.state = withStateValue(runtime.state, "has_book", String.valueOf(!runtime.inventory.get(0).isEmpty()));
        }
        writeState(runtime);
    }

    private void loadMachineState(RuntimeNode runtime) {
        if (runtime == null || runtime.sprite == null) return;
        runtime.hopperCooldown = Math.max(0, intData(runtime.sprite.data("__koil_vw_hopper_cooldown"), runtime.hopperCooldown));
        runtime.burnTime = Math.max(0, intData(runtime.sprite.data("__koil_vw_burn_time"), runtime.burnTime));
        runtime.fuelTime = Math.max(0, intData(runtime.sprite.data("__koil_vw_fuel_time"), runtime.fuelTime));
        runtime.cookTime = Math.max(0, intData(runtime.sprite.data("__koil_vw_cook_time"), runtime.cookTime));
        runtime.cookTimeTotal = Math.max(1, intData(runtime.sprite.data("__koil_vw_cook_total"), runtime.cookTimeTotal));
        runtime.brewTime = Math.max(0, intData(runtime.sprite.data("__koil_vw_brew_time"), runtime.brewTime));
        runtime.brewFuel = Math.max(0, intData(runtime.sprite.data("__koil_vw_brew_fuel"), runtime.brewFuel));
        String ingredientId = runtime.sprite.data("__koil_vw_brew_ingredient");
        if (ingredientId != null && !ingredientId.isBlank()) {
            try {
                Item item = Registries.ITEM.get(new Identifier(ingredientId));
                runtime.brewingIngredient = item == null ? Items.AIR : item;
            } catch (RuntimeException ignored) { runtime.brewingIngredient = Items.AIR; }
        }
        for (int i = 0; i < runtime.campfireCookTimes.length; i++) {
            runtime.campfireCookTimes[i] = Math.max(0, intData(runtime.sprite.data("__koil_vw_campfire_time_" + i), runtime.campfireCookTimes[i]));
            runtime.campfireCookTotals[i] = Math.max(0, intData(runtime.sprite.data("__koil_vw_campfire_total_" + i), runtime.campfireCookTotals[i]));
        }
    }

    private void persistMachineState(RuntimeNode runtime) {
        if (runtime == null || runtime.sprite == null) return;
        runtime.sprite.data("__koil_vw_hopper_cooldown", runtime.hopperCooldown);
        runtime.sprite.data("__koil_vw_burn_time", runtime.burnTime);
        runtime.sprite.data("__koil_vw_fuel_time", runtime.fuelTime);
        runtime.sprite.data("__koil_vw_cook_time", runtime.cookTime);
        runtime.sprite.data("__koil_vw_cook_total", runtime.cookTimeTotal);
        runtime.sprite.data("__koil_vw_brew_time", runtime.brewTime);
        runtime.sprite.data("__koil_vw_brew_fuel", runtime.brewFuel);
        runtime.sprite.data("__koil_vw_brew_ingredient", runtime.brewingIngredient == null || runtime.brewingIngredient == Items.AIR
                ? "" : Registries.ITEM.getId(runtime.brewingIngredient).toString());
        for (int i = 0; i < runtime.campfireCookTimes.length; i++) {
            runtime.sprite.data("__koil_vw_campfire_time_" + i, runtime.campfireCookTimes[i]);
            runtime.sprite.data("__koil_vw_campfire_total_" + i, runtime.campfireCookTotals[i]);
        }
    }

    private void persistInventory(RuntimeNode runtime) {
        if (runtime == null || runtime.sprite == null || runtime.inventory == null) return;
        for (int i = 0; i < runtime.inventory.size(); i++) {
            ItemStack stack = runtime.inventory.get(i);
            String key = "__koil_vw_slot_" + i;
            if (stack == null || stack.isEmpty()) {
                runtime.sprite.data(key, "");
                continue;
            }
            try { runtime.sprite.data(key, stack.writeNbt(new NbtCompound()).toString()); }
            catch (RuntimeException ignored) { }
        }
    }

    private void loadPersistedInventory(RuntimeNode runtime) {
        if (runtime == null || runtime.sprite == null || runtime.inventory == null) return;
        for (int i = 0; i < runtime.inventory.size(); i++) {
            String snbt = runtime.sprite.data("__koil_vw_slot_" + i);
            if (snbt == null || snbt.isBlank()) continue;
            try {
                ItemStack stack = ItemStack.fromNbt(StringNbtReader.parse(snbt));
                if (!stack.isEmpty()) runtime.inventory.set(i, stack);
            } catch (Exception ignored) { }
        }
    }

    private ItemStack stackFromSprite(SpriteNode node) {
        if (node == null || node.item() == null || node.item() == Items.AIR) return ItemStack.EMPTY;
        String snbt = node.data("__koil_stack_nbt");
        if (snbt != null && !snbt.isBlank()) {
            try {
                ItemStack parsed = ItemStack.fromNbt(StringNbtReader.parse(snbt));
                if (!parsed.isEmpty()) {
                    parsed.setCount(Math.max(1, intData(node.data("__koil_stack_count"), parsed.getCount())));
                    return parsed;
                }
            } catch (Exception ignored) { }
        }
        return new ItemStack(node.item(), Math.max(1, intData(node.data("__koil_stack_count"), 1)));
    }

    private void writeStackToSprite(SpriteNode node, ItemStack stack) {
        if (node == null || stack == null || stack.isEmpty()) return;
        node.data("__koil_stack_count", stack.getCount());
        try {
            NbtCompound nbt = new NbtCompound();
            stack.writeNbt(nbt);
            node.data("__koil_stack_nbt", nbt.asString());
        } catch (RuntimeException ignored) { }
    }

    private int firstNonEmptySlot(List<ItemStack> inventory) {
        for (int i = 0; i < inventory.size(); i++) if (!inventory.get(i).isEmpty()) return i;
        return -1;
    }

    private RuntimeNode blockAt(Cell cell) {
        return blocksByCell.get(cell);
    }

    private Cell resolvePlanarFallback(Cell origin, Direction direction) {
        Cell exact = origin.offset(direction);
        if ((direction == Direction.UP || direction == Direction.DOWN) && blockAt(exact) == null) {
            return direction == Direction.UP
                    ? new Cell(origin.x(), origin.y(), origin.z() - 1)
                    : new Cell(origin.x(), origin.y(), origin.z() + 1);
        }
        return exact;
    }

    private boolean isRedstoneWire(RuntimeNode runtime) {
        return runtime != null && (runtime.block instanceof RedstoneWireBlock
                || BlockCapabilityRegistry.isKind(runtime.block, "RedstoneWireBlock"));
    }

    private boolean isRepeater(RuntimeNode runtime) {
        return runtime != null && (runtime.block instanceof RepeaterBlock || BlockCapabilityRegistry.isKind(runtime.block, "RepeaterBlock"));
    }

    private boolean isComparator(RuntimeNode runtime) {
        return runtime != null && (runtime.block instanceof ComparatorBlock || BlockCapabilityRegistry.isKind(runtime.block, "ComparatorBlock"));
    }

    private boolean isObserver(RuntimeNode runtime) {
        return runtime != null && (runtime.block instanceof ObserverBlock
                || BlockCapabilityRegistry.isKind(runtime.block, "ObserverBlock"));
    }

    private boolean isRedstoneTorch(RuntimeNode runtime) {
        return runtime != null && (runtime.block instanceof RedstoneTorchBlock
                || BlockCapabilityRegistry.isKind(runtime.block, "RedstoneTorchBlock", "WallRedstoneTorchBlock"));
    }

    private boolean isPiston(RuntimeNode runtime) {
        return runtime != null && hasCapability(runtime, BlockCapabilityRegistry.Capability.PISTON);
    }

    private boolean isHopper(RuntimeNode runtime) {
        return runtime != null && hasCapability(runtime, BlockCapabilityRegistry.Capability.HOPPER_TRANSFER);
    }

    private boolean isCampfire(RuntimeNode runtime) {
        return runtime != null && hasCapability(runtime, BlockCapabilityRegistry.Capability.CAMPFIRE_COOKING);
    }

    private boolean isDropperOrDispenser(RuntimeNode runtime) {
        return runtime != null && hasCapability(runtime, BlockCapabilityRegistry.Capability.DISPENSER_BEHAVIOR);
    }

    private boolean isTrappedChest(RuntimeNode runtime) {
        return runtime != null && (runtime.block instanceof TrappedChestBlock || BlockCapabilityRegistry.isKind(runtime.block, "TrappedChestBlock"));
    }

    private boolean isPowerOpenable(RuntimeNode runtime) {
        if (runtime == null || runtime.state == null) return false;
        if (!hasProperty(runtime.state, "open")) return false;
        return runtime.block instanceof DoorBlock || runtime.block instanceof TrapdoorBlock
                || runtime.block instanceof FenceGateBlock;
    }

    private void publishDebugState() {
        for (RuntimeNode runtime : runtimes.values()) {
            if (runtime.sprite == null) continue;
            runtime.sprite.data("__koil_vw_tick", worldTick);
            runtime.sprite.data("__koil_vw_cell", runtime.cell.x() + "," + runtime.cell.y() + "," + runtime.cell.z());
            syncInventoryState(runtime);
            persistInventory(runtime);
            persistMachineState(runtime);
            runtime.sprite.data("__koil_vw_inventory_used", usedSlots(runtime.inventory));
            runtime.sprite.data("__koil_vw_power", Math.round(runtime.sprite.signal("power")));
            runtime.sprite.data("__koil_vw_extended", runtime.pistonExtended);
            FluidInfo fluid = fluidInfo(runtime);
            runtime.sprite.data("__koil_vw_fluid", fluid.valid() ? (fluid.water() ? "water" : fluid.lava() ? "lava" : "fluid") : "");
            runtime.sprite.data("__koil_vw_fluid_level", fluid.level());
            runtime.sprite.data("__koil_vw_waterlogged", fluid.waterlogged());
            runtime.sprite.data("__koil_vw_random_tick_speed", randomTickSpeed);
            runtime.sprite.data("__koil_vw_function_align_until", runtime.functionalAlignUntilTick);
            runtime.sprite.data("__koil_vw_environment_sky_light", environment.skyLight());
            runtime.sprite.data("__koil_vw_environment_block_light", environment.blockLight());
            runtime.sprite.data("__koil_vw_environment_time", environment.timeOfDay());
            runtime.sprite.data("__koil_vw_environment_raining", environment.raining());
            runtime.sprite.data("__koil_vw_environment_thundering", environment.thundering());
            runtime.sprite.data("__koil_vw_environment_dimension", environment.dimensionId());
            runtime.sprite.data("__koil_vw_environment_temperature", environment.temperature());
            if (detachedRuntime != null) {
                DetachedMinecraftRuntime.Diagnostics detached = detachedRuntime.peekDiagnostics();
                runtime.sprite.data("__koil_vw_runtime_mode", detached.mode());
                runtime.sprite.data("__koil_vw_runtime_requires_world", detachedRuntime.requiresGameplayWorld());
                runtime.sprite.data("__koil_vw_detached_data", detached.summary());
            }
            publishCapabilityProfile(runtime);
        }
    }

    private int usedSlots(List<ItemStack> inventory) {
        int used = 0;
        if (inventory != null) for (ItemStack stack : inventory) if (stack != null && !stack.isEmpty()) used++;
        return used;
    }

    private void writeState(RuntimeNode runtime) {
        String signature = signature(runtime.state);
        runtime.stateSignature = signature;
        runtime.sprite.blockState(signature);
    }

    private static BlockState resolveBlockState(Block block, String signature) {
        if (block == null) return Blocks.AIR.getDefaultState();
        BlockState state = block.getDefaultState();
        if (signature == null || signature.isBlank()) return state;
        Map<String, String> values = parseSignature(signature);
        for (Property<?> property : block.getStateManager().getProperties()) {
            String value = values.get(property.getName().toLowerCase(Locale.ROOT));
            if (value != null) state = withParsedProperty(state, property, value);
        }
        return state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState withParsedProperty(BlockState state, Property property, String value) {
        try {
            Optional parsed = property.parse(value);
            if (parsed.isPresent()) return state.with(property, (Comparable) parsed.get());
        } catch (RuntimeException ignored) { }
        return state;
    }

    private static BlockState withStateValue(BlockState state, String key, String value) {
        if (state == null || key == null || value == null) return state;
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equalsIgnoreCase(key)) return withParsedProperty(state, property, value);
        }
        return state;
    }

    private static boolean hasProperty(BlockState state, String name) {
        if (state == null || name == null) return false;
        for (Property<?> property : state.getProperties()) if (property.getName().equalsIgnoreCase(name)) return true;
        return false;
    }

    private static String signature(BlockState state) {
        if (state == null) return "";
        List<String> values = new ArrayList<>();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
            values.add(entry.getKey().getName().toLowerCase(Locale.ROOT) + "=" + String.valueOf(entry.getValue()).toLowerCase(Locale.ROOT));
        }
        values.sort(String::compareTo);
        return String.join(",", values);
    }

    private static String normalizeSignature(String signature) {
        Map<String, String> values = parseSignature(signature);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) parts.add(entry.getKey() + "=" + entry.getValue());
        parts.sort(String::compareTo);
        return String.join(",", parts);
    }

    private static Map<String, String> parseSignature(String signature) {
        Map<String, String> values = new LinkedHashMap<>();
        if (signature == null) return values;
        for (String token : signature.split("[,;]")) {
            int eq = token.indexOf('=');
            if (eq <= 0) continue;
            String key = token.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = token.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty() && !value.isEmpty()) values.put(key, value);
        }
        return values;
    }

    private static Direction facing(BlockState state, Direction fallback) {
        String value = stringProperty(state, "facing", fallback.getName());
        try { return Direction.byName(value) == null ? fallback : Direction.byName(value); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static String stringProperty(BlockState state, String key, String fallback) {
        if (state == null) return fallback;
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
            if (entry.getKey().getName().equalsIgnoreCase(key)) return String.valueOf(entry.getValue()).toLowerCase(Locale.ROOT);
        }
        return fallback;
    }

    private static int intProperty(BlockState state, String key, int fallback) {
        try { return Integer.parseInt(stringProperty(state, key, String.valueOf(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static boolean boolProperty(BlockState state, String key, boolean fallback) {
        String value = stringProperty(state, key, String.valueOf(fallback));
        return "true".equalsIgnoreCase(value) || (!"false".equalsIgnoreCase(value) && fallback);
    }

    private static Direction directionForRotation(float rotation) {
        int quadrant = Math.floorMod(Math.round(rotation / 90.0F), 4);
        return switch (quadrant) {
            case 0 -> Direction.EAST;
            case 1 -> Direction.SOUTH;
            case 2 -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    private static Direction rotateLeft(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
            default -> Direction.WEST;
        };
    }

    private static Direction rotateRight(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.EAST;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            case EAST -> Direction.SOUTH;
            default -> Direction.EAST;
        };
    }

    private static Direction opposite(Direction direction) {
        return direction.getOpposite();
    }

    private static int clampPower(int power) {
        return Math.max(0, Math.min(15, power));
    }

    private static boolean bool(String value) {
        return value != null && Boolean.parseBoolean(value);
    }

    private static int intData(String value, int fallback) {
        try { return value == null || value.isBlank() ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static long longData(String value, long fallback) {
        try { return value == null || value.isBlank() ? fallback : Long.parseLong(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
