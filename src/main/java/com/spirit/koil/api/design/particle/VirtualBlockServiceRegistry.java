package com.spirit.koil.api.design.particle;

import net.minecraft.block.Block;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Declares the authoritative services behind KoilVirtualBlockWorld.
 *
 * <p>The capability registry discovers what a registered block needs. This class
 * owns the second half of that contract: which runtime service is responsible for
 * a capability, how complete that service currently is for a block family, and why
 * a capability remains partial/missing. This keeps coverage accounting separate
 * from block discovery and prevents KoilGameSpriteBehavior from becoming a second
 * gameplay engine.</p>
 */
public final class VirtualBlockServiceRegistry {
    public enum Service {
        BLOCK_STATE,
        GEOMETRY,
        BLOCK_MOTION,
        SCHEDULER,
        BLOCK_ENTITY,
        INVENTORY,
        REDSTONE,
        FLUID,
        VIBRATION,
        ENTITY_CONTACT,
        MULTIBLOCK,
        SOUND,
        DAMAGE,
        EXPLOSION,
        FIRE,
        RAIL,
        TRIPWIRE,
        DISPENSER,
        GROWTH,
        PORTAL,
        BEACON,
        STRUCTURE,
        PLAYER_USE,
        PROJECTILE,
        ENTITY_WORLD,
        SERVER_CONTEXT,
        COMMAND,
        SPAWNER,
        LOOT
    }

    public record Binding(
            Service service,
            BlockCapabilityRegistry.SupportLevel support,
            String detail
    ) {
        public Binding {
            service = service == null ? Service.BLOCK_STATE : service;
            support = support == null ? BlockCapabilityRegistry.SupportLevel.MISSING : support;
            detail = detail == null ? "" : detail;
        }
    }

    public record ServiceSummary(int required, int full, int partial, int missing) { }

    private VirtualBlockServiceRegistry() { }

    public static Binding binding(Block block,
                                  BlockCapabilityRegistry.Capability capability,
                                  EnumSet<BlockCapabilityRegistry.Capability> capabilities) {
        if (capability == null) {
            return new Binding(Service.BLOCK_STATE, BlockCapabilityRegistry.SupportLevel.MISSING,
                    "No capability was supplied to the runtime service registry.");
        }
        EnumSet<BlockCapabilityRegistry.Capability> caps = capabilities == null
                ? EnumSet.noneOf(BlockCapabilityRegistry.Capability.class)
                : capabilities;
        Service service = serviceFor(capability);
        BlockCapabilityRegistry.SupportLevel level = supportFor(block, capability, caps);
        return new Binding(service, level, detailFor(capability, level));
    }

    public static Service serviceFor(BlockCapabilityRegistry.Capability capability) {
        if (capability == null) return Service.BLOCK_STATE;
        return switch (capability) {
            case STATE_PROPERTIES, FUNCTIONAL_ORIENTATION, GRID_CELL_OWNERSHIP -> Service.BLOCK_STATE;
            case COLLISION_GEOMETRY -> Service.GEOMETRY;
            case SCHEDULED_TICKS, RANDOM_TICKS, NEIGHBOR_UPDATE -> Service.SCHEDULER;
            case BLOCK_ENTITY, BLOCK_ENTITY_TICKER -> Service.BLOCK_ENTITY;
            case INVENTORY, SIDED_INVENTORY, HOPPER_TRANSFER, COOKING, BREWING, CAMPFIRE_COOKING -> Service.INVENTORY;
            case DIRECTIONAL_REDSTONE, COMPARATOR_OUTPUT, CONTACT_REDSTONE_SOURCE, REDSTONE_POWER_SOURCE -> Service.REDSTONE;
            case FLUID_FILL_DRAIN, FLUID_SIMULATION, BUBBLE_COLUMN -> Service.FLUID;
            case VIBRATION_LISTENER, VIBRATION_SENSOR, CALIBRATED_VIBRATION_SENSOR,
                    VIBRATION_SHRIEKER, SCULK_CATALYST, SCULK_PROPAGATION -> Service.VIBRATION;
            case ENTITY_CONTACT -> Service.ENTITY_CONTACT;
            case MULTIBLOCK_RELATION -> Service.MULTIBLOCK;
            case CONTEXTUAL_SOUND -> Service.SOUND;
            case ENVIRONMENT_LIGHT, ENVIRONMENT_TIME, ENVIRONMENT_WEATHER, ENVIRONMENT_DIMENSION -> Service.SERVER_CONTEXT;
            case SERVER_ENTITY_DEPENDENCY -> Service.ENTITY_WORLD;
            case DAMAGE -> Service.DAMAGE;
            case EXPLOSION, TNT_PRIMING -> Service.EXPLOSION;
            case FIRE_SPREAD -> Service.FIRE;
            case RAIL_ROUTING, RAIL_POWER -> Service.RAIL;
            case TRIPWIRE_NETWORK -> Service.TRIPWIRE;
            case DISPENSER_BEHAVIOR -> Service.DISPENSER;
            case GROWTH -> Service.GROWTH;
            case PORTAL -> Service.PORTAL;
            case BEACON_STRUCTURE -> Service.BEACON;
            case STRUCTURE_QUERY -> Service.STRUCTURE;
            case PLAYER_INTERACTION -> Service.PLAYER_USE;
            case PROJECTILE_INTERACTION -> Service.PROJECTILE;
            case COMMAND_EXECUTION -> Service.COMMAND;
            case SPAWNER -> Service.SPAWNER;
            case LOOT_TABLE -> Service.LOOT;
            case FALLING_BLOCK, PISTON -> Service.BLOCK_MOTION;
        };
    }

    public static String primaryRole(BlockCapabilityRegistry.BlockProfile profile) {
        if (profile == null) return "block";
        if (profile.has(BlockCapabilityRegistry.Capability.PISTON)) return "piston";
        if (profile.has(BlockCapabilityRegistry.Capability.HOPPER_TRANSFER)) return "hopper";
        if (profile.has(BlockCapabilityRegistry.Capability.COOKING)) return "cooking_machine";
        if (profile.has(BlockCapabilityRegistry.Capability.BREWING)) return "brewing_machine";
        if (profile.has(BlockCapabilityRegistry.Capability.CAMPFIRE_COOKING)) return "campfire";
        if (profile.has(BlockCapabilityRegistry.Capability.DISPENSER_BEHAVIOR)) return "dispenser";
        if (profile.has(BlockCapabilityRegistry.Capability.VIBRATION_LISTENER)) return "vibration_listener";
        if (profile.has(BlockCapabilityRegistry.Capability.RAIL_ROUTING)) return "rail";
        if (profile.has(BlockCapabilityRegistry.Capability.TRIPWIRE_NETWORK)) return "tripwire";
        if (profile.has(BlockCapabilityRegistry.Capability.PORTAL)) return "portal";
        if (profile.has(BlockCapabilityRegistry.Capability.INVENTORY)) return "container";
        if (profile.has(BlockCapabilityRegistry.Capability.REDSTONE_POWER_SOURCE)) return "redstone_source";
        if (profile.has(BlockCapabilityRegistry.Capability.DIRECTIONAL_REDSTONE)) return "redstone_component";
        if (profile.has(BlockCapabilityRegistry.Capability.FLUID_SIMULATION)) return "fluid";
        if (profile.has(BlockCapabilityRegistry.Capability.FALLING_BLOCK)) return "falling_block";
        return "block";
    }

    public static Map<Service, ServiceSummary> summarize(Iterable<BlockCapabilityRegistry.BlockProfile> profiles) {
        EnumMap<Service, int[]> counts = new EnumMap<>(Service.class);
        for (Service service : Service.values()) counts.put(service, new int[4]);
        if (profiles != null) {
            for (BlockCapabilityRegistry.BlockProfile profile : profiles) {
                if (profile == null) continue;
                for (BlockCapabilityRegistry.Capability capability : profile.capabilities()) {
                    Service service = serviceFor(capability);
                    int[] bucket = counts.get(service);
                    bucket[0]++;
                    switch (profile.support(capability)) {
                        case FULL -> bucket[1]++;
                        case PARTIAL -> bucket[2]++;
                        case MISSING -> bucket[3]++;
                    }
                }
            }
        }
        Map<Service, ServiceSummary> result = new LinkedHashMap<>();
        for (Service service : Service.values()) {
            int[] value = counts.get(service);
            result.put(service, new ServiceSummary(value[0], value[1], value[2], value[3]));
        }
        return result;
    }

    public static String serviceDescription(Service service) {
        if (service == null) return "Unknown virtual-world service.";
        return switch (service) {
            case BLOCK_STATE -> "Block identity, BlockState persistence and function-owned orientation.";
            case GEOMETRY -> "Projected BlockState VoxelShape geometry, continuous actor sweeps and depenetration.";
            case BLOCK_MOTION -> "Scheduled falling and atomic piston/slime/honey virtual-cell movement transactions.";
            case SCHEDULER -> "Deterministic 20 TPS scheduled ticks, random ticks and neighbor invalidation.";
            case BLOCK_ENTITY -> "Persistent block-entity-shaped state and explicitly virtualized block-entity tickers.";
            case INVENTORY -> "Shared ItemStack storage, sided insertion/extraction, hopper transfer and machine processing.";
            case REDSTONE -> "Directional redstone, gates, comparator output, contact sources and delayed transitions.";
            case FLUID -> "Water/lava ownership, source flow, waterlogging, buckets, mixing and bubble columns.";
            case VIBRATION -> "Shared game-event/vibration bus, occlusion, sensor phases, resonance and sculk adapters.";
            case ENTITY_CONTACT -> "AABB indexed virtual entities and common contact queries.";
            case MULTIBLOCK -> "Logical relationships for paired or multi-cell blocks such as chests, doors, beds and tripwire lines.";
            case SOUND -> "Registered physical sounds plus state/action-specific sound dispatch.";
            case DAMAGE -> "Virtual health, hurt cooldown, death events and damage-source accounting.";
            case EXPLOSION -> "TNT priming and 2D blast propagation using block blast resistance and entity impulse/damage.";
            case FIRE -> "Fire aging, rain extinguish and spread using vanilla flammability data where accessible.";
            case RAIL -> "Rail-shape routing and powered/detector rail integration for minecart-like virtual entities.";
            case TRIPWIRE -> "Hook-to-hook wire scanning, attachment, powered state and action sounds.";
            case DISPENSER -> "Persistent dispenser/dropper inventory and item behavior dispatch.";
            case GROWTH -> "Random-tick growth and structure-producing growth adapters.";
            case PORTAL -> "Portal contact, cooldown and virtual endpoint transfer.";
            case BEACON -> "Virtual beacon-base structure scan and activation metadata.";
            case STRUCTURE -> "Virtual-cell structure and neighborhood queries used by world-dependent blocks.";
            case PLAYER_USE -> "Registered block right-click/use semantics routed into the virtual world.";
            case PROJECTILE -> "Projectile-sensitive block contact and hit dispatch.";
            case ENTITY_WORLD -> "Authoritative 20 TPS virtual entity identity, health, queries, spawning, game events and portal transfer.";
            case SERVER_CONTEXT -> "Shared server-shaped time, light, weather, dimension, difficulty, spawn and game-rule context.";
            case COMMAND -> "Explicit safe command execution bridge. Not fabricated when no bridge exists.";
            case SPAWNER -> "Virtual spawning service. Requires an entity factory before parity can be claimed.";
            case LOOT -> "Loot-table evaluation with an explicit virtual loot context.";
        };
    }

    private static BlockCapabilityRegistry.SupportLevel supportFor(
            Block block,
            BlockCapabilityRegistry.Capability capability,
            EnumSet<BlockCapabilityRegistry.Capability> capabilities) {
        return switch (capability) {
            case STATE_PROPERTIES, INVENTORY, SIDED_INVENTORY,
                    FLUID_FILL_DRAIN, ENTITY_CONTACT, FALLING_BLOCK, PISTON,
                    HOPPER_TRANSFER, COOKING, BREWING, CAMPFIRE_COOKING,
                    FUNCTIONAL_ORIENTATION, GRID_CELL_OWNERSHIP, COLLISION_GEOMETRY, FLUID_SIMULATION, CONTACT_REDSTONE_SOURCE,
                    VIBRATION_SENSOR, CALIBRATED_VIBRATION_SENSOR, VIBRATION_SHRIEKER,
                    TRIPWIRE_NETWORK, TNT_PRIMING -> BlockCapabilityRegistry.SupportLevel.FULL;
            case SCHEDULED_TICKS -> scheduledTicksFullySupported(block)
                    ? BlockCapabilityRegistry.SupportLevel.FULL
                    : BlockCapabilityRegistry.SupportLevel.PARTIAL;
            case RANDOM_TICKS -> randomTickFullySupported(block)
                    ? BlockCapabilityRegistry.SupportLevel.FULL
                    : BlockCapabilityRegistry.SupportLevel.PARTIAL;
            case CONTEXTUAL_SOUND -> contextualSoundSupport(block, capabilities);
            case BLOCK_ENTITY -> capabilities.contains(BlockCapabilityRegistry.Capability.INVENTORY)
                    ? BlockCapabilityRegistry.SupportLevel.FULL
                    : BlockCapabilityRegistry.SupportLevel.PARTIAL;
            case DIRECTIONAL_REDSTONE -> directionalRedstoneFullySupported(block)
                    ? BlockCapabilityRegistry.SupportLevel.FULL
                    : BlockCapabilityRegistry.SupportLevel.PARTIAL;
            case COMPARATOR_OUTPUT -> comparatorFullySupported(block, capabilities)
                    ? BlockCapabilityRegistry.SupportLevel.FULL
                    : BlockCapabilityRegistry.SupportLevel.PARTIAL;
            case VIBRATION_LISTENER -> BlockCapabilityRegistry.SupportLevel.FULL;
            case MULTIBLOCK_RELATION -> multiblockFullySupported(block)
                    ? BlockCapabilityRegistry.SupportLevel.FULL
                    : BlockCapabilityRegistry.SupportLevel.PARTIAL;
            case ENVIRONMENT_LIGHT, ENVIRONMENT_TIME, ENVIRONMENT_WEATHER, ENVIRONMENT_DIMENSION ->
                    BlockCapabilityRegistry.SupportLevel.PARTIAL;
            case DAMAGE, EXPLOSION, FIRE_SPREAD, RAIL_ROUTING, DISPENSER_BEHAVIOR,
                    GROWTH, PORTAL, BEACON_STRUCTURE, SPAWNER, SCULK_PROPAGATION,
                    STRUCTURE_QUERY, LOOT_TABLE, SCULK_CATALYST, RAIL_POWER, BUBBLE_COLUMN,
                    PROJECTILE_INTERACTION -> BlockCapabilityRegistry.SupportLevel.PARTIAL;
            case NEIGHBOR_UPDATE -> neighborUpdatesFullySupported(block, capabilities)
                    ? BlockCapabilityRegistry.SupportLevel.FULL
                    : BlockCapabilityRegistry.SupportLevel.PARTIAL;
            case PLAYER_INTERACTION -> playerInteractionFullySupported(block, capabilities)
                    ? BlockCapabilityRegistry.SupportLevel.FULL
                    : BlockCapabilityRegistry.SupportLevel.PARTIAL;
            case BLOCK_ENTITY_TICKER -> blockEntityTickerSupport(capabilities);
            case REDSTONE_POWER_SOURCE -> directionalRedstoneFullySupported(block)
                    ? BlockCapabilityRegistry.SupportLevel.FULL
                    : BlockCapabilityRegistry.SupportLevel.PARTIAL;
            case SERVER_ENTITY_DEPENDENCY -> BlockCapabilityRegistry.SupportLevel.PARTIAL;
            case COMMAND_EXECUTION -> BlockCapabilityRegistry.SupportLevel.MISSING;
        };
    }


    private static boolean scheduledTicksFullySupported(Block block) {
        return BlockCapabilityRegistry.isKind(block,
                "FallingBlock", "FluidBlock", "AbstractPressurePlateBlock", "AbstractRedstoneGateBlock",
                "ObserverBlock", "ButtonBlock", "TripwireHookBlock", "TripwireBlock", "RedstoneLampBlock",
                "SculkSensorBlock", "CalibratedSculkSensorBlock", "SculkShriekerBlock",
                "TntBlock", "BubbleColumnBlock", "FireBlock", "AbstractFireBlock");
    }

    private static boolean randomTickFullySupported(Block block) {
        return BlockCapabilityRegistry.isKind(block,
                "CropBlock", "NetherWartBlock", "CocoaBlock", "SweetBerryBushBlock", "StemBlock",
                "SugarCaneBlock", "CactusBlock", "FarmlandBlock", "LeavesBlock");
    }

    private static boolean directionalRedstoneFullySupported(Block block) {
        return BlockCapabilityRegistry.isKind(block,
                "AbstractRedstoneGateBlock", "ComparatorBlock", "RepeaterBlock", "RedstoneWireBlock",
                "RedstoneTorchBlock", "WallRedstoneTorchBlock", "ObserverBlock", "PistonBlock", "LeverBlock",
                "ButtonBlock", "AbstractPressurePlateBlock", "DetectorRailBlock", "TripwireBlock",
                "TripwireHookBlock", "RedstoneLampBlock", "TrappedChestBlock");
    }

    private static boolean comparatorFullySupported(Block block,
                                                     EnumSet<BlockCapabilityRegistry.Capability> capabilities) {
        return capabilities.contains(BlockCapabilityRegistry.Capability.INVENTORY)
                || BlockCapabilityRegistry.isKind(block,
                "SculkSensorBlock", "CalibratedSculkSensorBlock", "JukeboxBlock",
                "ChiseledBookshelfBlock", "LecternBlock", "EndPortalFrameBlock");
    }

    private static boolean multiblockFullySupported(Block block) {
        return BlockCapabilityRegistry.isKind(block,
                "ChestBlock", "DoorBlock", "BedBlock", "TripwireBlock", "TripwireHookBlock");
    }

    private static boolean neighborUpdatesFullySupported(
            Block block,
            EnumSet<BlockCapabilityRegistry.Capability> capabilities) {
        return capabilities.contains(BlockCapabilityRegistry.Capability.FLUID_SIMULATION)
                || capabilities.contains(BlockCapabilityRegistry.Capability.FLUID_FILL_DRAIN)
                || capabilities.contains(BlockCapabilityRegistry.Capability.DIRECTIONAL_REDSTONE)
                || capabilities.contains(BlockCapabilityRegistry.Capability.PISTON)
                || capabilities.contains(BlockCapabilityRegistry.Capability.FALLING_BLOCK)
                || capabilities.contains(BlockCapabilityRegistry.Capability.TRIPWIRE_NETWORK)
                || capabilities.contains(BlockCapabilityRegistry.Capability.BUBBLE_COLUMN)
                || BlockCapabilityRegistry.isKind(block, "DoorBlock", "BedBlock", "ChestBlock", "LeavesBlock");
    }

    private static boolean playerInteractionFullySupported(
            Block block,
            EnumSet<BlockCapabilityRegistry.Capability> capabilities) {
        if (capabilities.contains(BlockCapabilityRegistry.Capability.INVENTORY)) return true;
        return BlockCapabilityRegistry.isKind(block,
                "LeverBlock", "ButtonBlock", "DoorBlock", "TrapdoorBlock", "FenceGateBlock",
                "RepeaterBlock", "ComparatorBlock", "DaylightDetectorBlock", "JukeboxBlock",
                "LecternBlock", "ChiseledBookshelfBlock", "NoteBlock", "BellBlock");
    }

    private static BlockCapabilityRegistry.SupportLevel blockEntityTickerSupport(
            EnumSet<BlockCapabilityRegistry.Capability> capabilities) {
        if (capabilities.contains(BlockCapabilityRegistry.Capability.COOKING)
                || capabilities.contains(BlockCapabilityRegistry.Capability.BREWING)
                || capabilities.contains(BlockCapabilityRegistry.Capability.HOPPER_TRANSFER)
                || capabilities.contains(BlockCapabilityRegistry.Capability.CAMPFIRE_COOKING)) {
            return BlockCapabilityRegistry.SupportLevel.FULL;
        }
        if (capabilities.contains(BlockCapabilityRegistry.Capability.COMMAND_EXECUTION)) {
            return BlockCapabilityRegistry.SupportLevel.MISSING;
        }
        if (capabilities.contains(BlockCapabilityRegistry.Capability.SPAWNER)) {
            return BlockCapabilityRegistry.SupportLevel.PARTIAL;
        }
        return BlockCapabilityRegistry.SupportLevel.PARTIAL;
    }

    private static BlockCapabilityRegistry.SupportLevel contextualSoundSupport(
            Block block,
            EnumSet<BlockCapabilityRegistry.Capability> capabilities) {
        if (!capabilities.contains(BlockCapabilityRegistry.Capability.PLAYER_INTERACTION)
                && !capabilities.contains(BlockCapabilityRegistry.Capability.VIBRATION_LISTENER)
                && !capabilities.contains(BlockCapabilityRegistry.Capability.TNT_PRIMING)
                && !capabilities.contains(BlockCapabilityRegistry.Capability.TRIPWIRE_NETWORK)
                && !capabilities.contains(BlockCapabilityRegistry.Capability.BUBBLE_COLUMN)) {
            return BlockCapabilityRegistry.SupportLevel.FULL;
        }
        if (playerInteractionFullySupported(block, capabilities)
                || capabilities.contains(BlockCapabilityRegistry.Capability.VIBRATION_LISTENER)
                || capabilities.contains(BlockCapabilityRegistry.Capability.TNT_PRIMING)
                || capabilities.contains(BlockCapabilityRegistry.Capability.TRIPWIRE_NETWORK)
                || capabilities.contains(BlockCapabilityRegistry.Capability.BUBBLE_COLUMN)) {
            return BlockCapabilityRegistry.SupportLevel.FULL;
        }
        return BlockCapabilityRegistry.SupportLevel.PARTIAL;
    }

    private static String detailFor(BlockCapabilityRegistry.Capability capability,
                                    BlockCapabilityRegistry.SupportLevel level) {
        String status = level.name().toLowerCase(Locale.ROOT);
        return switch (capability) {
            case STATE_PROPERTIES -> "BlockState properties are parsed, persisted and rebound atomically (" + status + ").";
            case SCHEDULED_TICKS -> "Deterministic 20 TPS scheduling is authoritative; unsupported World callbacks require explicit adapters (" + status + ").";
            case RANDOM_TICKS -> "All random-ticking states are discovered; only modeled random-tick families claim full parity (" + status + ").";
            case BLOCK_ENTITY -> "Block-entity identity is discovered; arbitrary server ticker logic is never executed blindly (" + status + ").";
            case INVENTORY -> "Persistent ItemStack storage is shared by use, hoppers and comparators (" + status + ").";
            case SIDED_INVENTORY -> "Insertion/extraction honors the discovered SidedInventory contract (" + status + ").";
            case DIRECTIONAL_REDSTONE -> "Directional power and delayed gate state are handled by the virtual redstone network (" + status + ").";
            case COMPARATOR_OUTPUT -> "Analog output uses shared inventory/state adapters; specialized overrides are audited independently (" + status + ").";
            case FLUID_FILL_DRAIN -> "Bucket fill/drain and waterlogging use the virtual fluid service (" + status + ").";
            case VIBRATION_LISTENER -> "Game events use the shared vibration bus with occlusion and frequency data (" + status + ").";
            case ENTITY_CONTACT -> "Contacts come from the common AABB virtual-entity index (" + status + ").";
            case MULTIBLOCK_RELATION -> "Paired/multi-cell relationships are synchronized by the multiblock service (" + status + ").";
            case CONTEXTUAL_SOUND -> "Physical sounds are registry-derived; stateful action sounds require an owned service path (" + status + ").";
            case ENVIRONMENT_LIGHT -> "Virtual light context exists; server light-engine propagation is approximated (" + status + ").";
            case ENVIRONMENT_TIME -> "A deterministic 24000-tick day context is available (" + status + ").";
            case ENVIRONMENT_WEATHER -> "Rain/thunder context exists; biome/weather side effects remain adapter-driven (" + status + ").";
            case ENVIRONMENT_DIMENSION -> "Dimension and ultrawarm context exist; real ServerWorld transfer is not fabricated (" + status + ").";
            case SERVER_ENTITY_DEPENDENCY -> "Uses the shared KoilVirtualEntityWorld and KoilVirtualServerContext foundation; family-specific server/entity semantics remain partial (" + status + ").";
            case DAMAGE -> "Virtual health, hurt cooldown and death events exist; exact vanilla entity damage rules remain partial (" + status + ").";
            case EXPLOSION -> "2D blast propagation uses blast resistance and entity impulse/damage (" + status + ").";
            case FIRE_SPREAD -> "Fire aging/spread uses vanilla flammability data where accessible (" + status + ").";
            case RAIL_ROUTING -> "Rail state routes minecart-like entities; full minecart dynamics remain incomplete (" + status + ").";
            case TRIPWIRE_NETWORK -> "Hook-to-hook line scan and attached/powered propagation are implemented (" + status + ").";
            case DISPENSER_BEHAVIOR -> "Dispenser/dropper inventory and trigger plumbing exist; every item world behavior is not yet complete (" + status + ").";
            case GROWTH -> "Shared random-tick growth exists; configured-feature/tree/world growth remains incomplete (" + status + ").";
            case PORTAL -> "Portal contact/cooldown and virtual endpoint transfer exist; real dimension transfer requires server integration (" + status + ").";
            case BEACON_STRUCTURE -> "Beacon-base pyramid scanning exists; beam/effect semantics remain incomplete (" + status + ").";
            case COMMAND_EXECUTION -> "No command is fabricated without an explicit safe command bridge (" + status + ").";
            case SPAWNER -> "Spawner blocks are detected but need a virtual entity factory to create mobs (" + status + ").";
            case SCULK_PROPAGATION -> "Vibration/resonance plumbing exists; exact charge-cursor propagation remains incomplete (" + status + ").";
            case STRUCTURE_QUERY -> "Virtual-cell structure queries exist; worldgen/template semantics remain incomplete (" + status + ").";
            case LOOT_TABLE -> "Loot-table dependence is tracked; a complete loot context is not fabricated (" + status + ").";
            case FALLING_BLOCK -> "Falling blocks change logical cells on scheduled ticks while render position interpolates smoothly between grid cells (" + status + ").";
            case PISTON -> "Pistons use atomic 12-block transactions with slime/honey and PistonBehavior rules (" + status + ").";
            case HOPPER_TRANSFER -> "Hoppers use persistent inventory transfer and AABB loose-item pickup (" + status + ").";
            case COOKING -> "Furnace-family processing uses detached Minecraft/mod cooking data plus native fuel times; no gameplay world is required (" + status + ").";
            case BREWING -> "Brewing uses persistent slots, fuel and Minecraft brewing recipes (" + status + ").";
            case CAMPFIRE_COOKING -> "Campfires use four scheduled cooking slots backed by detached cooking data; no gameplay world is required (" + status + ").";
            case FUNCTIONAL_ORIENTATION -> "Directional and axis presentation comes from BlockState; registered block sprites do not free-rotate (" + status + ").";
            case GRID_CELL_OWNERSHIP -> "Every registered block occupies a deterministic virtual grid cell and cannot drift or accumulate rigid-body rotation (" + status + ").";
            case COLLISION_GEOMETRY -> "Live BlockState collision VoxelShapes are projected into the shared screen-world collision solver (" + status + ").";
            case FLUID_SIMULATION -> "Water/lava flow, source ownership, mixing and waterlogging are scheduled by the fluid service (" + status + ").";
            case CONTACT_REDSTONE_SOURCE -> "Contact blocks publish deterministic redstone output from the AABB entity index (" + status + ").";
            case VIBRATION_SENSOR -> "Sensor active/cooldown phases and analog frequency output are virtualized (" + status + ").";
            case CALIBRATED_VIBRATION_SENSOR -> "Rear-power frequency filtering is applied on the shared vibration bus (" + status + ").";
            case VIBRATION_SHRIEKER -> "Shriek phase scheduling and waterlogged sound suppression are virtualized (" + status + ").";
            case SCULK_CATALYST -> "Death-event bloom is implemented; complete charge propagation remains partial (" + status + ").";
            case TNT_PRIMING -> "Priming converts TNT from a block cell into a VirtualEntityWorld primed-TNT actor with an 80-tick fuse (" + status + ").";
            case RAIL_POWER -> "Powered/detector rail state participates in the rail/redstone services (" + status + ").";
            case BUBBLE_COLUMN -> "Bubble-column formation and entity motion exist; exact 3D boat/entity cases remain partial (" + status + ").";
            case NEIGHBOR_UPDATE -> "Virtual neighbor invalidation feeds owned redstone/fluid/multiblock/tick services (" + status + ").";
            case PLAYER_INTERACTION -> "Right-click/use semantics are routed into the virtual-world player-use service (" + status + ").";
            case PROJECTILE_INTERACTION -> "Projectile-sensitive blocks are discovered; exact hit semantics are adapter-specific (" + status + ").";
            case BLOCK_ENTITY_TICKER -> "Known machine tickers are virtualized; arbitrary server tickers are not executed blindly (" + status + ").";
            case REDSTONE_POWER_SOURCE -> "Power-emitting states are discovered across the full StateManager and routed through redstone services (" + status + ").";
        };
    }
}
