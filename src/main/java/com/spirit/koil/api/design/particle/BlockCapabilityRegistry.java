package com.spirit.koil.api.design.particle;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.Waterloggable;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Registry-wide capability audit for Koil's VirtualBlockWorld.
 *
 * <p>This class deliberately classifies every registered block, including modded
 * subclasses, before the runtime chooses an adapter. Classification is based on
 * block class/interface inheritance, state properties, BlockEntity capability,
 * inventory contracts, fluid contracts and comparator/random-tick declarations.
 * Registry ids are retained for diagnostics but are not the primary dispatch
 * mechanism.</p>
 */
public final class BlockCapabilityRegistry {
    public static final int SCHEMA_VERSION = 4;

    public enum Capability {
        STATE_PROPERTIES,
        SCHEDULED_TICKS,
        RANDOM_TICKS,
        BLOCK_ENTITY,
        INVENTORY,
        SIDED_INVENTORY,
        DIRECTIONAL_REDSTONE,
        COMPARATOR_OUTPUT,
        FLUID_FILL_DRAIN,
        VIBRATION_LISTENER,
        ENTITY_CONTACT,
        MULTIBLOCK_RELATION,
        CONTEXTUAL_SOUND,
        ENVIRONMENT_LIGHT,
        ENVIRONMENT_TIME,
        ENVIRONMENT_WEATHER,
        ENVIRONMENT_DIMENSION,
        SERVER_ENTITY_DEPENDENCY,
        DAMAGE,
        EXPLOSION,
        FIRE_SPREAD,
        RAIL_ROUTING,
        TRIPWIRE_NETWORK,
        DISPENSER_BEHAVIOR,
        GROWTH,
        PORTAL,
        BEACON_STRUCTURE,
        COMMAND_EXECUTION,
        SPAWNER,
        SCULK_PROPAGATION,
        STRUCTURE_QUERY,
        LOOT_TABLE,
        FALLING_BLOCK,
        PISTON,
        HOPPER_TRANSFER,
        COOKING,
        BREWING,
        CAMPFIRE_COOKING,
        FUNCTIONAL_ORIENTATION,
        GRID_CELL_OWNERSHIP,
        COLLISION_GEOMETRY,
        FLUID_SIMULATION,
        CONTACT_REDSTONE_SOURCE,
        VIBRATION_SENSOR,
        CALIBRATED_VIBRATION_SENSOR,
        VIBRATION_SHRIEKER,
        SCULK_CATALYST,
        TNT_PRIMING,
        RAIL_POWER,
        BUBBLE_COLUMN,
        NEIGHBOR_UPDATE,
        PLAYER_INTERACTION,
        PROJECTILE_INTERACTION,
        BLOCK_ENTITY_TICKER,
        REDSTONE_POWER_SOURCE
    }

    public enum SupportLevel {
        FULL,
        PARTIAL,
        MISSING;

        public static SupportLevel worst(SupportLevel a, SupportLevel b) {
            if (a == MISSING || b == MISSING) return MISSING;
            if (a == PARTIAL || b == PARTIAL) return PARTIAL;
            return FULL;
        }
    }

    public record BlockProfile(
            Identifier id,
            String className,
            Set<String> stateProperties,
            EnumSet<Capability> capabilities,
            EnumMap<Capability, SupportLevel> support,
            int inventorySize,
            String blockEntityClass
    ) {
        public BlockProfile {
            stateProperties = stateProperties == null
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(stateProperties));
            capabilities = capabilities == null ? EnumSet.noneOf(Capability.class) : capabilities.clone();
            support = support == null ? new EnumMap<>(Capability.class) : new EnumMap<>(support);
            className = className == null ? "" : className;
            blockEntityClass = blockEntityClass == null ? "" : blockEntityClass;
            inventorySize = Math.max(0, inventorySize);
        }

        @Override
        public EnumSet<Capability> capabilities() {
            return capabilities.clone();
        }

        @Override
        public EnumMap<Capability, SupportLevel> support() {
            return new EnumMap<>(support);
        }

        public boolean has(Capability capability) {
            return capability != null && capabilities.contains(capability);
        }

        public SupportLevel support(Capability capability) {
            if (capability == null || !capabilities.contains(capability)) return SupportLevel.FULL;
            return support.getOrDefault(capability, SupportLevel.MISSING);
        }

        public SupportLevel overallSupport() {
            SupportLevel result = SupportLevel.FULL;
            for (Capability capability : capabilities) result = SupportLevel.worst(result, support(capability));
            return result;
        }

        public Set<Capability> missingCapabilities() {
            EnumSet<Capability> result = EnumSet.noneOf(Capability.class);
            for (Capability capability : capabilities) {
                if (support(capability) == SupportLevel.MISSING) result.add(capability);
            }
            return Collections.unmodifiableSet(result);
        }

        public Set<Capability> partialCapabilities() {
            EnumSet<Capability> result = EnumSet.noneOf(Capability.class);
            for (Capability capability : capabilities) {
                if (support(capability) == SupportLevel.PARTIAL) result.add(capability);
            }
            return Collections.unmodifiableSet(result);
        }

        public String capabilityCsv() {
            return enumCsv(capabilities);
        }

        public String missingCsv() {
            return enumCsv(missingCapabilities());
        }

        public String partialCsv() {
            return enumCsv(partialCapabilities());
        }
    }

    public record CoverageSummary(
            int registryBlocks,
            int fullyCovered,
            int partiallyCovered,
            int missingCoverage,
            Map<Capability, Integer> requiredByCapability,
            Map<Capability, Integer> fullByCapability,
            Map<Capability, Integer> partialByCapability,
            Map<Capability, Integer> missingByCapability
    ) { }

    private static final Map<Block, BlockProfile> CACHE = new LinkedHashMap<>();
    private static volatile boolean registryScanned;

    private BlockCapabilityRegistry() { }

    public static synchronized BlockProfile profile(Block block) {
        if (block == null) return null;
        return CACHE.computeIfAbsent(block, BlockCapabilityRegistry::inspect);
    }

    public static synchronized Collection<BlockProfile> profiles() {
        scanRegistry();
        return Collections.unmodifiableCollection(new ArrayList<>(CACHE.values()));
    }

    public static synchronized CoverageSummary summary() {
        scanRegistry();
        EnumMap<Capability, Integer> required = zeroMap();
        EnumMap<Capability, Integer> full = zeroMap();
        EnumMap<Capability, Integer> partial = zeroMap();
        EnumMap<Capability, Integer> missing = zeroMap();
        int fullyCovered = 0;
        int partiallyCovered = 0;
        int missingCoverage = 0;

        for (BlockProfile profile : CACHE.values()) {
            SupportLevel overall = profile.overallSupport();
            if (overall == SupportLevel.FULL) fullyCovered++;
            else if (overall == SupportLevel.PARTIAL) partiallyCovered++;
            else missingCoverage++;

            for (Capability capability : profile.capabilities()) {
                required.put(capability, required.get(capability) + 1);
                switch (profile.support(capability)) {
                    case FULL -> full.put(capability, full.get(capability) + 1);
                    case PARTIAL -> partial.put(capability, partial.get(capability) + 1);
                    case MISSING -> missing.put(capability, missing.get(capability) + 1);
                }
            }
        }
        return new CoverageSummary(CACHE.size(), fullyCovered, partiallyCovered, missingCoverage,
                Collections.unmodifiableMap(required), Collections.unmodifiableMap(full),
                Collections.unmodifiableMap(partial), Collections.unmodifiableMap(missing));
    }

    public static synchronized Path writeReport(Path path) throws IOException {
        if (path == null) throw new IllegalArgumentException("path");
        scanRegistry();
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(path, toJson(), StandardCharsets.UTF_8);
        return path;
    }

    public static synchronized String toJson() {
        scanRegistry();
        CoverageSummary summary = summary();
        List<BlockProfile> ordered = new ArrayList<>(CACHE.values());
        ordered.sort(Comparator.comparing(profile -> profile.id().toString()));

        StringBuilder out = new StringBuilder(Math.max(32768, ordered.size() * 420));
        out.append("{\n");
        jsonNumber(out, "schema_version", SCHEMA_VERSION, 1, true);
        jsonNumber(out, "registry_blocks", summary.registryBlocks(), 1, true);
        jsonNumber(out, "fully_covered", summary.fullyCovered(), 1, true);
        jsonNumber(out, "partially_covered", summary.partiallyCovered(), 1, true);
        jsonNumber(out, "missing_coverage", summary.missingCoverage(), 1, true);
        out.append("  \"capability_totals\": {\n");
        int capIndex = 0;
        for (Capability capability : Capability.values()) {
            if (capIndex++ > 0) out.append(",\n");
            out.append("    \"").append(capability.name().toLowerCase(Locale.ROOT)).append("\": {")
                    .append("\"required\":").append(summary.requiredByCapability().get(capability)).append(',')
                    .append("\"full\":").append(summary.fullByCapability().get(capability)).append(',')
                    .append("\"partial\":").append(summary.partialByCapability().get(capability)).append(',')
                    .append("\"missing\":").append(summary.missingByCapability().get(capability)).append('}');
        }
        out.append("\n  },\n  \"capability_services\": {\n");
        int capabilityServiceIndex = 0;
        for (Capability capability : Capability.values()) {
            if (capabilityServiceIndex++ > 0) out.append(",\n");
            out.append("    \"").append(capability.name().toLowerCase(Locale.ROOT)).append("\": \"")
                    .append(VirtualBlockServiceRegistry.serviceFor(capability).name().toLowerCase(Locale.ROOT)).append('\"');
        }
        out.append("\n  },\n  \"service_descriptions\": {\n");
        int serviceDescriptionIndex = 0;
        for (VirtualBlockServiceRegistry.Service service : VirtualBlockServiceRegistry.Service.values()) {
            if (serviceDescriptionIndex++ > 0) out.append(",\n");
            out.append("    \"").append(service.name().toLowerCase(Locale.ROOT)).append("\": \"")
                    .append(escape(VirtualBlockServiceRegistry.serviceDescription(service))).append('\"');
        }
        out.append("\n  },\n  \"service_totals\": {");
        Map<VirtualBlockServiceRegistry.Service, VirtualBlockServiceRegistry.ServiceSummary> serviceTotals =
                VirtualBlockServiceRegistry.summarize(ordered);
        int serviceIndex = 0;
        for (VirtualBlockServiceRegistry.Service service : VirtualBlockServiceRegistry.Service.values()) {
            if (serviceIndex++ > 0) out.append(',');
            VirtualBlockServiceRegistry.ServiceSummary totals = serviceTotals.get(service);
            out.append("\n    \"").append(service.name().toLowerCase(Locale.ROOT)).append("\": {")
                    .append("\"required\":").append(totals.required()).append(',')
                    .append("\"full\":").append(totals.full()).append(',')
                    .append("\"partial\":").append(totals.partial()).append(',')
                    .append("\"missing\":").append(totals.missing()).append('}');
        }
        if (!serviceTotals.isEmpty()) out.append('\n');
        out.append("  },\n  \"namespaces\": {");
        Map<String, int[]> namespaceTotals = new LinkedHashMap<>();
        for (BlockProfile profile : ordered) {
            String namespace = profile.id().getNamespace();
            int[] counts = namespaceTotals.computeIfAbsent(namespace, ignored -> new int[4]);
            counts[0]++;
            switch (profile.overallSupport()) {
                case FULL -> counts[1]++;
                case PARTIAL -> counts[2]++;
                case MISSING -> counts[3]++;
            }
        }
        int namespaceIndex = 0;
        for (Map.Entry<String, int[]> entry : namespaceTotals.entrySet()) {
            if (namespaceIndex++ > 0) out.append(',');
            int[] counts = entry.getValue();
            out.append("\n    \"").append(escape(entry.getKey())).append("\": {")
                    .append("\"blocks\":").append(counts[0]).append(',')
                    .append("\"full\":").append(counts[1]).append(',')
                    .append("\"partial\":").append(counts[2]).append(',')
                    .append("\"missing\":").append(counts[3]).append('}');
        }
        if (!namespaceTotals.isEmpty()) out.append('\n');
        out.append("  },\n  \"blocks\": [\n");
        for (int i = 0; i < ordered.size(); i++) {
            BlockProfile profile = ordered.get(i);
            if (i > 0) out.append(",\n");
            out.append("    {\n");
            jsonString(out, "id", profile.id().toString(), 3, true);
            jsonString(out, "class", profile.className(), 3, true);
            jsonString(out, "overall", profile.overallSupport().name().toLowerCase(Locale.ROOT), 3, true);
            jsonNumber(out, "inventory_size", profile.inventorySize(), 3, true);
            jsonString(out, "block_entity", profile.blockEntityClass(), 3, true);
            jsonArray(out, "state_properties", profile.stateProperties(), 3, true);
            List<String> caps = new ArrayList<>();
            for (Capability capability : profile.capabilities()) caps.add(capability.name().toLowerCase(Locale.ROOT));
            jsonArray(out, "capabilities", caps, 3, true);
            out.append("      \"support\": {");
            int supportIndex = 0;
            for (Capability capability : profile.capabilities()) {
                if (supportIndex++ > 0) out.append(',');
                out.append('\"').append(capability.name().toLowerCase(Locale.ROOT)).append("\":\"")
                        .append(profile.support(capability).name().toLowerCase(Locale.ROOT)).append('\"');
            }
            out.append("},\n      \"services\": {");
            int serviceBindingIndex = 0;
            for (Capability capability : profile.capabilities()) {
                if (serviceBindingIndex++ > 0) out.append(',');
                out.append('\"').append(capability.name().toLowerCase(Locale.ROOT)).append("\":\"")
                        .append(VirtualBlockServiceRegistry.serviceFor(capability).name().toLowerCase(Locale.ROOT)).append('\"');
            }
            out.append("}\n    }");
        }
        out.append("\n  ]\n}\n");
        return out.toString();
    }

    private static void scanRegistry() {
        if (registryScanned) return;
        for (Block block : Registries.BLOCK) profile(block);
        registryScanned = true;
    }

    private static BlockProfile inspect(Block block) {
        Identifier id = Registries.BLOCK.getId(block);
        BlockState state = block.getDefaultState();
        EnumSet<Capability> capabilities = EnumSet.of(
                Capability.STATE_PROPERTIES,
                Capability.CONTEXTUAL_SOUND,
                Capability.FUNCTIONAL_ORIENTATION,
                Capability.GRID_CELL_OWNERSHIP,
                Capability.COLLISION_GEOMETRY
        );
        Set<String> properties = new LinkedHashSet<>();
        if (state != null) state.getEntries().keySet().forEach(property -> properties.add(property.getName().toLowerCase(Locale.ROOT)));

        BlockEntity blockEntity = null;
        int inventorySize = 0;
        String blockEntityClass = "";
        if (block instanceof BlockEntityProvider provider && state != null) {
            capabilities.add(Capability.BLOCK_ENTITY);
            try {
                blockEntity = provider.createBlockEntity(BlockPos.ORIGIN, state);
            } catch (RuntimeException ignored) { }
            if (blockEntity != null) {
                blockEntityClass = blockEntity.getClass().getName();
                if (blockEntity instanceof Inventory inventory) {
                    capabilities.add(Capability.INVENTORY);
                    inventorySize = Math.max(0, inventory.size());
                }
                if (blockEntity instanceof SidedInventory) capabilities.add(Capability.SIDED_INVENTORY);
            }
        }

        boolean anyRandomTicks = false;
        boolean anyComparatorOutput = false;
        boolean anyRedstoneSource = false;
        try {
            for (BlockState candidate : block.getStateManager().getStates()) {
                if (candidate.hasRandomTicks()) anyRandomTicks = true;
                try { if (candidate.hasComparatorOutput()) anyComparatorOutput = true; }
                catch (RuntimeException ignored) { }
                try { if (candidate.emitsRedstonePower()) anyRedstoneSource = true; }
                catch (RuntimeException ignored) { }
            }
        } catch (RuntimeException ignored) {
            if (state != null) {
                anyRandomTicks = state.hasRandomTicks();
                try { anyComparatorOutput = state.hasComparatorOutput(); } catch (RuntimeException ignoredToo) { }
                try { anyRedstoneSource = state.emitsRedstonePower(); } catch (RuntimeException ignoredToo) { }
            }
        }

        if (anyRandomTicks) capabilities.add(Capability.RANDOM_TICKS);
        if (requiresScheduledTicks(block) || overridesBlockMethod(block, "scheduledTick")) capabilities.add(Capability.SCHEDULED_TICKS);
        if (overridesBlockMethod(block, "neighborUpdate") || overridesBlockMethod(block, "getStateForNeighborUpdate")) {
            capabilities.add(Capability.NEIGHBOR_UPDATE);
        }
        if (overridesBlockMethod(block, "onUse")) capabilities.add(Capability.PLAYER_INTERACTION);
        if (overridesBlockMethod(block, "onProjectileHit")) capabilities.add(Capability.PROJECTILE_INTERACTION);
        if (overridesBlockMethod(block, "getTicker")) capabilities.add(Capability.BLOCK_ENTITY_TICKER);
        if (anyRedstoneSource) capabilities.add(Capability.REDSTONE_POWER_SOURCE);

        if (block instanceof Waterloggable || block instanceof FluidBlock || implementsInterface(block, "FluidFillable", "FluidDrainable")) {
            capabilities.add(Capability.FLUID_FILL_DRAIN);
        }
        if (block instanceof FluidBlock) capabilities.add(Capability.FLUID_SIMULATION);
        if (isKind(block, "BubbleColumnBlock")) capabilities.add(Capability.BUBBLE_COLUMN);
        if (isKind(block, "FallingBlock")) capabilities.add(Capability.FALLING_BLOCK);
        if (isKind(block, "PistonBlock")) capabilities.add(Capability.PISTON);
        if (isKind(block, "HopperBlock")) capabilities.add(Capability.HOPPER_TRANSFER);
        if (isKind(block, "AbstractFurnaceBlock")) capabilities.add(Capability.COOKING);
        if (isKind(block, "BrewingStandBlock")) capabilities.add(Capability.BREWING);
        if (isKind(block, "CampfireBlock")) capabilities.add(Capability.CAMPFIRE_COOKING);

        if (requiresDirectionalRedstone(block, properties)) capabilities.add(Capability.DIRECTIONAL_REDSTONE);
        if (anyComparatorOutput) capabilities.add(Capability.COMPARATOR_OUTPUT);
        if (requiresEntityContact(block) || overridesBlockMethod(block, "onEntityCollision")
                || overridesBlockMethod(block, "onSteppedOn") || overridesBlockMethod(block, "onLandedUpon")) {
            capabilities.add(Capability.ENTITY_CONTACT);
        }
        if (isContactRedstone(block)) capabilities.add(Capability.CONTACT_REDSTONE_SOURCE);
        if (requiresMultiblock(block)) capabilities.add(Capability.MULTIBLOCK_RELATION);

        if (isKind(block, "SculkSensorBlock", "CalibratedSculkSensorBlock")) {
            capabilities.add(Capability.VIBRATION_LISTENER);
            capabilities.add(Capability.VIBRATION_SENSOR);
            if (isKind(block, "CalibratedSculkSensorBlock")) capabilities.add(Capability.CALIBRATED_VIBRATION_SENSOR);
        }
        if (isKind(block, "SculkShriekerBlock")) {
            capabilities.add(Capability.VIBRATION_LISTENER);
            capabilities.add(Capability.VIBRATION_SHRIEKER);
        }
        if (isKind(block, "SculkCatalystBlock")) {
            capabilities.add(Capability.SCULK_CATALYST);
            capabilities.add(Capability.SCULK_PROPAGATION);
        }
        if (isKind(block, "SculkBlock", "SculkVeinBlock")) capabilities.add(Capability.SCULK_PROPAGATION);

        if (isKind(block, "AbstractRailBlock", "RailBlock", "PoweredRailBlock", "DetectorRailBlock")) {
            capabilities.add(Capability.RAIL_ROUTING);
            if (isKind(block, "PoweredRailBlock", "DetectorRailBlock")) capabilities.add(Capability.RAIL_POWER);
        }
        if (isKind(block, "TripwireBlock", "TripwireHookBlock")) capabilities.add(Capability.TRIPWIRE_NETWORK);
        if (isKind(block, "DispenserBlock", "DropperBlock")) capabilities.add(Capability.DISPENSER_BEHAVIOR);
        if (isKind(block, "FireBlock", "AbstractFireBlock", "SoulFireBlock")) capabilities.add(Capability.FIRE_SPREAD);
        if (isKind(block, "TntBlock")) {
            capabilities.add(Capability.TNT_PRIMING);
            capabilities.add(Capability.EXPLOSION);
        }
        if (isDamagingBlock(block)) capabilities.add(Capability.DAMAGE);
        if (isGrowthBlock(block)) capabilities.add(Capability.GROWTH);
        if (isPortalBlock(block)) capabilities.add(Capability.PORTAL);
        if (isKind(block, "BeaconBlock")) capabilities.add(Capability.BEACON_STRUCTURE);
        if (isKind(block, "CommandBlock")) capabilities.add(Capability.COMMAND_EXECUTION);
        if (isKind(block, "SpawnerBlock")) capabilities.add(Capability.SPAWNER);
        if (requiresStructureQuery(block)) capabilities.add(Capability.STRUCTURE_QUERY);
        if (requiresLoot(block)) capabilities.add(Capability.LOOT_TABLE);

        addEnvironmentalCapabilities(block, capabilities);
        if (requiresServerEntity(block)) capabilities.add(Capability.SERVER_ENTITY_DEPENDENCY);

        EnumMap<Capability, SupportLevel> support = new EnumMap<>(Capability.class);
        for (Capability capability : capabilities) {
            support.put(capability, VirtualBlockServiceRegistry.binding(block, capability, capabilities).support());
        }
        return new BlockProfile(
                id == null ? new Identifier("minecraft", "air") : id,
                block.getClass().getName(),
                Collections.unmodifiableSet(properties),
                capabilities.clone(),
                support,
                inventorySize,
                blockEntityClass
        );
    }

    private static boolean requiresScheduledTicks(Block block) {
        return isKind(block,
                "FallingBlock", "FluidBlock", "AbstractPressurePlateBlock", "AbstractRedstoneGateBlock",
                "ObserverBlock", "ButtonBlock", "TripwireHookBlock", "TripwireBlock", "RedstoneLampBlock",
                "SculkSensorBlock", "CalibratedSculkSensorBlock", "SculkShriekerBlock", "SculkCatalystBlock",
                "FireBlock", "TntBlock", "BubbleColumnBlock", "PointedDripstoneBlock", "ScaffoldingBlock",
                "CoralParentBlock", "ConcretePowderBlock", "LeavesBlock", "FrostedIceBlock", "SpongeBlock"
        );
    }

    private static boolean requiresDirectionalRedstone(Block block, Set<String> properties) {
        if (isKind(block,
                "AbstractRedstoneGateBlock", "RedstoneWireBlock", "RedstoneTorchBlock", "ObserverBlock",
                "PistonBlock", "LeverBlock", "ButtonBlock", "AbstractPressurePlateBlock", "TripwireHookBlock",
                "TripwireBlock", "DetectorRailBlock", "PoweredRailBlock", "LightningRodBlock", "TargetBlock",
                "RedstoneLampBlock", "DaylightDetectorBlock", "LecternBlock", "TrappedChestBlock"
        )) return true;
        return (properties.contains("powered") || properties.contains("power"))
                && (properties.contains("facing") || properties.contains("axis") || properties.contains("face"));
    }

    private static boolean requiresEntityContact(Block block) {
        return isKind(block,
                "AbstractPressurePlateBlock", "TripwireBlock", "DetectorRailBlock", "CactusBlock", "FireBlock",
                "AbstractFireBlock", "CampfireBlock", "MagmaBlock", "SweetBerryBushBlock", "CobwebBlock",
                "HoneyBlock", "SlimeBlock", "PowderSnowBlock", "PointedDripstoneBlock", "BubbleColumnBlock",
                "NetherPortalBlock", "EndPortalBlock", "EndGatewayBlock", "WitherRoseBlock", "SculkSensorBlock",
                "CalibratedSculkSensorBlock"
        );
    }

    private static boolean isContactRedstone(Block block) {
        return isKind(block, "AbstractPressurePlateBlock", "DetectorRailBlock", "TripwireBlock");
    }

    private static boolean requiresMultiblock(Block block) {
        return isKind(block,
                "ChestBlock", "DoorBlock", "BedBlock", "TallPlantBlock", "DoublePlantBlock", "TripwireBlock",
                "TripwireHookBlock", "PistonBlock", "PistonHeadBlock", "NetherPortalBlock", "EndPortalFrameBlock",
                "BigDripleafBlock", "BigDripleafStemBlock"
        );
    }

    private static boolean isDamagingBlock(Block block) {
        return isKind(block,
                "CactusBlock", "FireBlock", "AbstractFireBlock", "CampfireBlock", "MagmaBlock", "SweetBerryBushBlock",
                "PowderSnowBlock", "PointedDripstoneBlock", "WitherRoseBlock", "LavaCauldronBlock"
        );
    }

    private static boolean isGrowthBlock(Block block) {
        return isKind(block,
                "CropBlock", "StemBlock", "NetherWartBlock", "CocoaBlock", "SweetBerryBushBlock", "SugarCaneBlock",
                "CactusBlock", "SaplingBlock", "PropaguleBlock", "MushroomPlantBlock", "FungusBlock", "BambooBlock",
                "BambooShootBlock", "CaveVinesBodyBlock", "CaveVinesHeadBlock", "KelpBlock", "KelpPlantBlock",
                "TwistingVinesBlock", "TwistingVinesPlantBlock", "WeepingVinesBlock", "WeepingVinesPlantBlock",
                "ChorusFlowerBlock", "SmallDripleafBlock", "BigDripleafBlock", "MangrovePropaguleBlock"
        );
    }

    private static boolean isPortalBlock(Block block) {
        return isKind(block,
                "NetherPortalBlock", "EndPortalBlock", "EndGatewayBlock", "EndPortalFrameBlock", "RespawnAnchorBlock"
        );
    }

    private static boolean requiresStructureQuery(Block block) {
        return isKind(block,
                "BeaconBlock", "ConduitBlock", "NetherPortalBlock", "EndPortalFrameBlock", "SaplingBlock",
                "PropaguleBlock", "MushroomPlantBlock", "FungusBlock", "WitherSkullBlock", "CarvedPumpkinBlock"
        );
    }

    private static boolean requiresLoot(Block block) {
        return isKind(block, "BrushableBlock", "DecoratedPotBlock", "BeehiveBlock", "SpawnerBlock", "VaultBlock");
    }

    private static void addEnvironmentalCapabilities(Block block, EnumSet<Capability> capabilities) {
        if (isKind(block,
                "CropBlock", "SaplingBlock", "StemBlock", "MushroomPlantBlock", "FungusBlock", "GrassBlock",
                "MyceliumBlock", "SnowBlock", "IceBlock", "LeavesBlock", "CocoaBlock", "SweetBerryBushBlock"
        )) capabilities.add(Capability.ENVIRONMENT_LIGHT);

        if (isKind(block,
                "DaylightDetectorBlock", "CactusBlock", "SugarCaneBlock", "CropBlock", "SaplingBlock", "LeavesBlock",
                "FireBlock", "FrostedIceBlock", "SculkSensorBlock"
        )) capabilities.add(Capability.ENVIRONMENT_TIME);

        if (isKind(block,
                "CauldronBlock", "FarmlandBlock", "FireBlock", "CopperBlock", "OxidizableBlock", "SnowBlock",
                "PointedDripstoneBlock", "LeavesBlock", "CampfireBlock"
        )) capabilities.add(Capability.ENVIRONMENT_WEATHER);

        if (isKind(block,
                "NetherPortalBlock", "EndPortalBlock", "EndGatewayBlock", "RespawnAnchorBlock", "BedBlock",
                "WaterBlock", "FluidBlock", "SpongeBlock", "WetSpongeBlock"
        )) capabilities.add(Capability.ENVIRONMENT_DIMENSION);
    }

    private static boolean requiresServerEntity(Block block) {
        return isKind(block,
                "SpawnerBlock", "CommandBlock", "BeaconBlock", "ConduitBlock", "BeehiveBlock", "BellBlock",
                "SculkShriekerBlock", "EndPortalBlock", "EndGatewayBlock", "NetherPortalBlock", "TntBlock",
                "WitherSkullBlock", "CarvedPumpkinBlock", "DragonEggBlock"
        );
    }

    private static boolean overridesBlockMethod(Block block, String methodName) {
        if (block == null || methodName == null || methodName.isBlank()) return false;
        Class<?> type = block.getClass();
        while (type != null && type != Object.class && type != Block.class) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (methodName.equals(method.getName())) return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    public static boolean isKindItem(net.minecraft.item.Item item, String... simpleNames) {
        if (item == null || simpleNames == null) return false;
        Class<?> type = item.getClass();
        while (type != null && type != Object.class) {
            String simple = type.getSimpleName();
            for (String expected : simpleNames) {
                if (expected != null && expected.equals(simple)) return true;
            }
            for (Class<?> iface : type.getInterfaces()) {
                if (interfaceMatches(iface, simpleNames)) return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    public static boolean isKind(Block block, String... simpleNames) {
        if (block == null || simpleNames == null) return false;
        Class<?> type = block.getClass();
        while (type != null && type != Object.class) {
            String simple = type.getSimpleName();
            for (String expected : simpleNames) {
                if (expected != null && expected.equals(simple)) return true;
            }
            for (Class<?> iface : type.getInterfaces()) {
                if (interfaceMatches(iface, simpleNames)) return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static boolean implementsInterface(Block block, String... simpleNames) {
        if (block == null) return false;
        Class<?> type = block.getClass();
        while (type != null && type != Object.class) {
            for (Class<?> iface : type.getInterfaces()) if (interfaceMatches(iface, simpleNames)) return true;
            type = type.getSuperclass();
        }
        return false;
    }

    private static boolean interfaceMatches(Class<?> iface, String... simpleNames) {
        if (iface == null) return false;
        for (String expected : simpleNames) {
            if (expected != null && expected.equals(iface.getSimpleName())) return true;
        }
        for (Class<?> parent : iface.getInterfaces()) if (interfaceMatches(parent, simpleNames)) return true;
        return false;
    }

    private static EnumMap<Capability, Integer> zeroMap() {
        EnumMap<Capability, Integer> map = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) map.put(capability, 0);
        return map;
    }

    private static String enumCsv(Collection<Capability> values) {
        StringBuilder out = new StringBuilder();
        if (values == null) return "";
        for (Capability capability : values) {
            if (out.length() > 0) out.append(',');
            out.append(capability.name().toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    private static void jsonString(StringBuilder out, String key, String value, int indent, boolean comma) {
        indent(out, indent).append('\"').append(escape(key)).append("\": \"").append(escape(value)).append('\"');
        if (comma) out.append(',');
        out.append('\n');
    }

    private static void jsonNumber(StringBuilder out, String key, long value, int indent, boolean comma) {
        indent(out, indent).append('\"').append(escape(key)).append("\": ").append(value);
        if (comma) out.append(',');
        out.append('\n');
    }

    private static void jsonArray(StringBuilder out, String key, Collection<String> values, int indent, boolean comma) {
        indent(out, indent).append('\"').append(escape(key)).append("\": [");
        int index = 0;
        if (values != null) {
            for (String value : values) {
                if (index++ > 0) out.append(',');
                out.append('\"').append(escape(value)).append('\"');
            }
        }
        out.append(']');
        if (comma) out.append(',');
        out.append('\n');
    }

    private static StringBuilder indent(StringBuilder out, int indent) {
        for (int i = 0; i < indent; i++) out.append("  ");
        return out;
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
