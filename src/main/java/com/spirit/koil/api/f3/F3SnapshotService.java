package com.spirit.koil.api.f3;

import com.spirit.Main;
import com.spirit.koil.api.performance.PerformanceMonitor;
import com.spirit.koil.api.performance.PerformanceSnapshot;
import com.spirit.mixin.debug.DebugHudAccessor;
import com.spirit.mixin.debug.InGameHudAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class F3SnapshotService {
    private static F3Snapshot latestSnapshot;
    private static F3Snapshot frozenSnapshot;
    private static long lastCaptureMillis;

    private F3SnapshotService() {
    }

    public static void tick(MinecraftClient client) {
        if (F3LayoutState.frozen()) {
            if (frozenSnapshot == null) {
                frozenSnapshot = capture(client, F3LayoutState.mode());
            }
            return;
        }
        frozenSnapshot = null;
        long now = System.currentTimeMillis();
        if (Main.preciseStat() || latestSnapshot == null || now - lastCaptureMillis >= F3LayoutState.refreshSpeed().intervalMillis()) {
            latestSnapshot = capture(client, F3LayoutState.mode());
            lastCaptureMillis = now;
        }
    }

    public static F3Snapshot latest(MinecraftClient client) {
        if (F3LayoutState.frozen() && frozenSnapshot != null) {
            return frozenSnapshot;
        }
        if (Main.preciseStat() || latestSnapshot == null) {
            latestSnapshot = capture(client, F3LayoutState.mode());
            lastCaptureMillis = System.currentTimeMillis();
        }
        return latestSnapshot;
    }

    public static F3Snapshot fresh(MinecraftClient client, F3Mode mode) {
        latestSnapshot = capture(client, mode == null ? F3LayoutState.mode() : mode);
        lastCaptureMillis = System.currentTimeMillis();
        if (F3LayoutState.frozen()) {
            frozenSnapshot = latestSnapshot;
        }
        return latestSnapshot;
    }

    private static F3Snapshot capture(MinecraftClient client, F3Mode mode) {
        F3Mode effectiveMode = mode == null ? F3Mode.NORMAL : mode;
        PerformanceSnapshot performance = PerformanceMonitor.latestSnapshot(client);
        F3TargetSnapshot target = F3TargetInspector.inspect(client, effectiveMode);
        List<F3Section> sections = new ArrayList<>();
        sections.add(performanceSection(performance));
        sections.add(playerSection(client, effectiveMode, target));
        sections.add(worldSection(client, effectiveMode));
        sections.add(chunkSection(client));
        sections.add(renderSection(client, performance));
        sections.add(systemSection(client, effectiveMode));
        sections.add(networkSection(client));
        sections.add(targetSection(target));
        sections.add(vanillaDebugSection(client, "vanilla_left", "Vanilla F3 Left", true, effectiveMode, target, performance));
        sections.add(vanillaDebugSection(client, "vanilla_right", "Vanilla F3 Right", false, effectiveMode, target, performance));
        List<String> warnings = warnings(client, performance, target);
        String headline = client == null || client.world == null ? "Client diagnostics" : "World intelligence";
        String status = warnings.isEmpty() ? "Stable. No critical local warning in the current snapshot." : warnings.get(0);
        return new F3Snapshot(
                System.currentTimeMillis(),
                effectiveMode,
                F3LayoutState.refreshSpeed(),
                F3LayoutState.frozen(),
                headline,
                status,
                performance,
                target,
                sections,
                warnings
        );
    }

    private static F3Section performanceSection(PerformanceSnapshot snapshot) {
        List<F3DataLine> lines = new ArrayList<>();
        lines.add(F3DataLine.state("FPS", String.valueOf(snapshot.fps()), fpsState(snapshot.fps()), fpsColor(snapshot.fps()), "Current Minecraft FPS from the client."));
        lines.add(F3DataLine.state("1% Low", String.valueOf(snapshot.onePercentLowFps()), fpsState((int) snapshot.onePercentLowFps()), fpsColor((int) snapshot.onePercentLowFps()), "Low-end frame rate from recent samples."));
        lines.add(F3DataLine.state("Frame Time", snapshot.frameTimeMs() + " ms", snapshot.maxFrameTimeMs() > 75.0D ? "spike" : "stable", snapshot.maxFrameTimeMs() > 75.0D ? 0xFFE06A21 : 0xFF2DA700, "Frame time sampled by Koil's performance monitor."));
        lines.add(F3DataLine.state("Memory", snapshot.usedMemoryMb() + "/" + snapshot.maxMemoryMb() + " MB", pressureState(snapshot.memoryPressure()), pressureColor(snapshot.memoryPressure()), "Java heap currently used compared to the configured maximum."));
        lines.add(F3DataLine.state("Bottleneck", snapshot.primaryBottleneck().label(), "diagnosis", snapshot.primaryBottleneck().color(), snapshot.likelyCause()));
        lines.add(F3DataLine.of("Likely Cause", snapshot.likelyCause()));
        lines.add(F3DataLine.of("Client Entities", String.valueOf(snapshot.entityCount())));
        lines.add(F3DataLine.of("Render/Sim", snapshot.renderDistance() + " / " + snapshot.simulationDistance()));
        return new F3Section("performance", "Performance", snapshot.primaryBottleneck().label(), snapshot.primaryBottleneck().color(), lines);
    }

    private static F3Section playerSection(MinecraftClient client, F3Mode mode, F3TargetSnapshot target) {
        List<F3DataLine> lines = new ArrayList<>();
        if (client == null || client.player == null) {
            lines.add(F3DataLine.state("Player", "not loaded", "inactive", 0xFF8D8D8D, "No local player is loaded."));
            return new F3Section("player", "Player", "No player", 0xFF7FC8C2, lines);
        }
        BlockPos pos = client.player.getBlockPos();
        ChunkPos chunk = new ChunkPos(pos);
        lines.add(F3DataLine.state("Name", client.player.getName().getString(), "live", 0xFF7FC8C2, "Local player profile name."));
        lines.add(F3DataLine.of("XYZ", format(client.player.getX()) + " / " + format(client.player.getY()) + " / " + format(client.player.getZ())));
        lines.add(F3DataLine.of("Block", pos.toShortString()));
        lines.add(F3DataLine.of("Chunk", chunk.x + ", " + chunk.z + " | local " + Math.floorMod(pos.getX(), 16) + ", " + Math.floorMod(pos.getY(), 16) + ", " + Math.floorMod(pos.getZ(), 16)));
        lines.add(F3DataLine.of("Region", Math.floorDiv(chunk.x, 32) + ", " + Math.floorDiv(chunk.z, 32)));
        lines.add(F3DataLine.of("Facing", client.player.getHorizontalFacing().asString() + " | yaw " + format(client.player.getYaw()) + " pitch " + format(client.player.getPitch())));
        lines.add(F3DataLine.of("Velocity", format(client.player.getVelocity().x) + ", " + format(client.player.getVelocity().y) + ", " + format(client.player.getVelocity().z)));
        lines.add(F3DataLine.of("Health", format(client.player.getHealth()) + "/" + format(client.player.getMaxHealth())));
        lines.add(F3DataLine.of("Hunger", client.player.getHungerManager().getFoodLevel() + " | sat " + format(client.player.getHungerManager().getSaturationLevel())));
        lines.add(F3DataLine.of("Armor", String.valueOf(client.player.getArmor())));
        lines.add(F3DataLine.of("Air", client.player.getAir() + "/" + client.player.getMaxAir()));
        ItemStack selected = client.player.getMainHandStack();
        lines.add(F3DataLine.of("Selected", selected.isEmpty() ? "empty" : selected.getName().getString()));
        lines.add(F3DataLine.state("Tool Match", selected.isEmpty() ? "empty hand" : targetToolStatus(selected, target), "target", target.accentColor(), "Compares held item against Minecraft's client-visible target/tool suitability data."));
        lines.add(F3DataLine.of("Ground", String.valueOf(client.player.isOnGround())));
        lines.add(F3DataLine.of("Sprinting", String.valueOf(client.player.isSprinting())));
        lines.add(F3DataLine.of("Sneaking", String.valueOf(client.player.isSneaking())));
        lines.add(F3DataLine.of("Swimming", String.valueOf(client.player.isSwimming())));
        if (!client.player.getStatusEffects().isEmpty()) {
            lines.add(F3DataLine.of("Effects", effectSummary(client.player.getStatusEffects())));
        }
        if (mode.developerDataEnabled()) {
            lines.add(F3DataLine.of("UUID", client.player.getUuidAsString()));
            lines.add(F3DataLine.of("Class", client.player.getClass().getName()));
            lines.add(F3DataLine.of("Entity Id", String.valueOf(client.player.getId())));
            lines.add(F3DataLine.of("Game Mode", client.interactionManager == null ? "unknown" : String.valueOf(client.interactionManager.getCurrentGameMode())));
            lines.add(F3DataLine.of("Abilities Fly", String.valueOf(client.player.getAbilities().allowFlying)));
            lines.add(F3DataLine.of("Flying", String.valueOf(client.player.getAbilities().flying)));
            lines.add(F3DataLine.of("Walk Speed", format(client.player.getAbilities().getWalkSpeed())));
            lines.add(F3DataLine.of("Fly Speed", format(client.player.getAbilities().getFlySpeed())));
            lines.add(F3DataLine.of("Fire", client.player.getFireTicks() + " ticks"));
            lines.add(F3DataLine.of("Fall Distance", format(client.player.fallDistance)));
            lines.add(F3DataLine.of("Bounding Box", format(client.player.getBoundingBox().getXLength()) + " x " + format(client.player.getBoundingBox().getYLength()) + " x " + format(client.player.getBoundingBox().getZLength())));
        }
        return new F3Section("player", "Player", pos.toShortString(), 0xFF7FC8C2, lines);
    }

    private static F3Section worldSection(MinecraftClient client, F3Mode mode) {
        List<F3DataLine> lines = new ArrayList<>();
        if (client == null || client.world == null || client.player == null) {
            lines.add(F3DataLine.state("World", "not loaded", "inactive", 0xFF8D8D8D, "No world or server is active."));
            return new F3Section("world", "World", "Inactive", 0xFFE3B735, lines);
        }
        BlockPos pos = client.player.getBlockPos();
        RegistryEntry<Biome> biome = client.world.getBiome(pos);
        String biomeId = biome.getKey().map(key -> key.getValue().toString()).orElse("unknown");
        lines.add(F3DataLine.state("Dimension", client.world.getRegistryKey().getValue().toString(), "world", 0xFFE3B735, "Current dimension registry id."));
        lines.add(F3DataLine.of("Biome", biomeId));
        lines.add(F3DataLine.of("Temperature", format(biome.value().getTemperature())));
        lines.add(F3DataLine.of("Precipitation", biome.value().getPrecipitation(pos).toString().toLowerCase(Locale.ROOT)));
        lines.add(F3DataLine.of("Time", String.valueOf(client.world.getTimeOfDay())));
        lines.add(F3DataLine.of("Day", String.valueOf(client.world.getTimeOfDay() / 24000L)));
        lines.add(F3DataLine.of("Weather", client.world.isThundering() ? "thunder" : client.world.isRaining() ? "rain" : "clear"));
        lines.add(F3DataLine.of("Difficulty", client.world.getDifficulty().getName()));
        lines.add(F3DataLine.of("Moon Phase", String.valueOf(client.world.getMoonPhase())));
        lines.add(F3DataLine.of("Sky Angle", format(client.world.getSkyAngle(1.0F))));
        lines.add(F3DataLine.of("Bottom/Top Y", client.world.getBottomY() + " / " + client.world.getTopY()));
        lines.add(F3DataLine.of("Coord Scale", format(client.world.getDimension().coordinateScale())));
        BlockPos spawn = client.world.getSpawnPos();
        lines.add(F3DataLine.of("Spawn", spawn.toShortString()));
        lines.add(F3DataLine.of("Spawn Distance", format(distance(pos, spawn))));
        lines.add(F3DataLine.state("Block Light", String.valueOf(client.world.getLightLevel(LightType.BLOCK, pos)), lightState(client.world.getLightLevel(LightType.BLOCK, pos)), lightColor(client.world.getLightLevel(LightType.BLOCK, pos)), "Local block light level."));
        lines.add(F3DataLine.state("Sky Light", String.valueOf(client.world.getLightLevel(LightType.SKY, pos)), "sky", 0xFF8FC5FF, "Local sky light level."));
        lines.add(F3DataLine.of("World Type", client.isInSingleplayer() ? "singleplayer" : client.getCurrentServerEntry() != null ? "server" : "client"));
        IntegratedServer server = client.getServer();
        if (mode.developerDataEnabled() && server != null) {
            lines.add(F3DataLine.of("Integrated Server", server.getSaveProperties().getLevelName()));
            lines.add(F3DataLine.of("Hardcore", String.valueOf(server.getSaveProperties().isHardcore())));
            lines.add(F3DataLine.of("Commands", String.valueOf(server.getSaveProperties().areCommandsAllowed())));
        }
        return new F3Section("world", "World", biomeId, 0xFFE3B735, lines);
    }

    private static F3Section chunkSection(MinecraftClient client) {
        List<F3DataLine> lines = new ArrayList<>();
        if (client == null || client.world == null || client.player == null) {
            lines.add(F3DataLine.state("Chunk", "not loaded", "inactive", 0xFF8D8D8D, "Chunk data is only available in a world."));
            return new F3Section("chunk", "Chunk", "Inactive", 0xFFC8A24A, lines);
        }
        BlockPos pos = client.player.getBlockPos();
        ChunkPos chunkPos = new ChunkPos(pos);
        lines.add(F3DataLine.state("Chunk", chunkPos.x + ", " + chunkPos.z, "world", 0xFFC8A24A, "Chunk containing the player."));
        lines.add(F3DataLine.of("Local Block", Math.floorMod(pos.getX(), 16) + ", " + Math.floorMod(pos.getY(), 16) + ", " + Math.floorMod(pos.getZ(), 16)));
        lines.add(F3DataLine.of("Section Y", String.valueOf(Math.floorDiv(pos.getY(), 16))));
        lines.add(F3DataLine.of("Section Index", String.valueOf(Math.floorDiv(pos.getY() - client.world.getBottomY(), 16))));
        lines.add(F3DataLine.of("World Height", client.world.getBottomY() + ".." + client.world.getTopY()));
        lines.add(F3DataLine.of("Chunk Start", chunkPos.getStartX() + ", " + chunkPos.getStartZ()));
        lines.add(F3DataLine.of("Chunk End", chunkPos.getEndX() + ", " + chunkPos.getEndZ()));
        lines.add(F3DataLine.of("Region", Math.floorDiv(chunkPos.x, 32) + ", " + Math.floorDiv(chunkPos.z, 32)));
        lines.add(F3DataLine.of("Region Local", Math.floorMod(chunkPos.x, 32) + ", " + Math.floorMod(chunkPos.z, 32)));
        lines.add(F3DataLine.of("Slime Chunk", slimeChunkText(client, chunkPos)));
        lines.add(F3DataLine.of("Region File", "r." + Math.floorDiv(chunkPos.x, 32) + "." + Math.floorDiv(chunkPos.z, 32) + ".mca"));
        try {
            Chunk chunk = client.world.getChunk(pos);
            lines.add(F3DataLine.of("Loaded", "true"));
            lines.add(F3DataLine.of("Block Entities", chunk instanceof WorldChunk worldChunk ? String.valueOf(worldChunk.getBlockEntities().size()) : "unavailable"));
            lines.add(F3DataLine.of("Inhabited Time", String.valueOf(chunk.getInhabitedTime())));
            lines.add(F3DataLine.of("Needs Saving", String.valueOf(chunk.needsSaving())));
            lines.add(F3DataLine.of("Section Count", String.valueOf(chunk.getSectionArray().length)));
        } catch (Throwable throwable) {
            lines.add(F3DataLine.state("Loaded", "unknown", "unknown", 0xFF8D8D8D, "Chunk object was not available through the client world."));
        }
        int clientEntities = 0;
        int chunkEntities = 0;
        for (var entity : client.world.getEntities()) {
            clientEntities++;
            if (entity.getChunkPos().equals(chunkPos)) {
                chunkEntities++;
            }
        }
        lines.add(F3DataLine.of("Chunk Entities", String.valueOf(chunkEntities)));
        lines.add(F3DataLine.of("Client Entities", String.valueOf(clientEntities)));
        return new F3Section("chunk", "Chunk", chunkPos.x + ", " + chunkPos.z, 0xFFC8A24A, lines);
    }

    private static F3Section renderSection(MinecraftClient client, PerformanceSnapshot snapshot) {
        List<F3DataLine> lines = new ArrayList<>();
        lines.add(F3DataLine.state("Render Distance", String.valueOf(snapshot.renderDistance()), "render", 0xFF7400A4, "Current render distance option."));
        lines.add(F3DataLine.of("Simulation Distance", String.valueOf(snapshot.simulationDistance())));
        lines.add(F3DataLine.of("Graphics", snapshot.graphicsMode()));
        lines.add(F3DataLine.of("Clouds", snapshot.cloudsMode()));
        lines.add(F3DataLine.of("Particles", snapshot.particlesMode()));
        lines.add(F3DataLine.of("Mipmap", String.valueOf(snapshot.mipmapLevels())));
        lines.add(F3DataLine.of("Entity Shadows", String.valueOf(snapshot.entityShadows())));
        lines.add(F3DataLine.of("VSync", String.valueOf(snapshot.vsync())));
        lines.add(F3DataLine.of("Shader Mod", String.valueOf(snapshot.shaderModInstalled())));
        if (client != null && client.getWindow() != null) {
            lines.add(F3DataLine.of("Window", client.getWindow().getFramebufferWidth() + "x" + client.getWindow().getFramebufferHeight()));
            lines.add(F3DataLine.of("Scaled Window", client.getWindow().getScaledWidth() + "x" + client.getWindow().getScaledHeight()));
            lines.add(F3DataLine.of("Scale Factor", format(client.getWindow().getScaleFactor())));
        }
        lines.add(F3DataLine.of("Resource Packs", String.valueOf(snapshot.resourcePackCount())));
        return new F3Section("render", "Renderer", snapshot.graphicsMode(), 0xFF7400A4, lines);
    }

    private static F3Section systemSection(MinecraftClient client, F3Mode mode) {
        Runtime runtime = Runtime.getRuntime();
        RuntimeMXBean runtimeMx = ManagementFactory.getRuntimeMXBean();
        List<F3DataLine> lines = new ArrayList<>();
        lines.add(F3DataLine.of("Java", System.getProperty("java.version", "unknown")));
        lines.add(F3DataLine.of("JVM", runtimeMx.getVmName()));
        lines.add(F3DataLine.of("OS", System.getProperty("os.name", "unknown") + " " + System.getProperty("os.arch", "")));
        lines.add(F3DataLine.of("CPU Threads", String.valueOf(runtime.availableProcessors())));
        lines.add(F3DataLine.of("Heap Used", (runtime.totalMemory() - runtime.freeMemory()) / 1024L / 1024L + " MB"));
        lines.add(F3DataLine.of("Heap Total", runtime.totalMemory() / 1024L / 1024L + " MB"));
        lines.add(F3DataLine.of("Heap Max", runtime.maxMemory() / 1024L / 1024L + " MB"));
        lines.add(F3DataLine.of("Mods", String.valueOf(FabricLoader.getInstance().getAllMods().size())));
        lines.add(F3DataLine.of("Minecraft", client == null ? "unknown" : client.getGameVersion()));
        if (mode.developerDataEnabled()) {
            lines.add(F3DataLine.of("Uptime", runtimeMx.getUptime() + " ms"));
            lines.add(F3DataLine.of("Input Args", String.valueOf(runtimeMx.getInputArguments().size())));
            lines.add(F3DataLine.of("Class Path Entries", String.valueOf(runtimeMx.getClassPath().split(java.io.File.pathSeparator).length)));
            lines.add(F3DataLine.of("Fabric Env", FabricLoader.getInstance().getEnvironmentType().name()));
            lines.add(F3DataLine.of("Game Dir", FabricLoader.getInstance().getGameDir().toString()));
        }
        return new F3Section("system", "Java/System", System.getProperty("os.name", "unknown"), 0xFF8FC5FF, lines);
    }

    private static F3Section networkSection(MinecraftClient client) {
        List<F3DataLine> lines = new ArrayList<>();
        if (client == null || client.player == null || client.getNetworkHandler() == null) {
            lines.add(F3DataLine.state("Network", "not connected", "inactive", 0xFF8D8D8D, "No active network handler."));
            return new F3Section("network", "Server/Network", "Offline", 0xFF8D8D8D, lines);
        }
        PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
        lines.add(F3DataLine.state("Connection", client.getCurrentServerEntry() == null ? "integrated/local" : client.getCurrentServerEntry().address, "network", 0xFF8FC5FF, "Current server connection as visible to the client."));
        lines.add(F3DataLine.of("Ping", entry == null ? "unknown" : entry.getLatency() + " ms"));
        lines.add(F3DataLine.of("Players Listed", String.valueOf(client.getNetworkHandler().getPlayerList().size())));
        lines.add(F3DataLine.of("Server Brand", safeNetworkBrand(client)));
        return new F3Section("network", "Server/Network", entry == null ? "unknown ping" : entry.getLatency() + " ms", 0xFF8FC5FF, lines);
    }

    private static F3Section targetSection(F3TargetSnapshot target) {
        List<F3DataLine> lines = new ArrayList<>(target.lines());
        if (!target.tags().isEmpty()) {
            lines.add(F3DataLine.of("Tags", String.valueOf(target.tags().size())));
            for (String tag : target.tags()) {
                lines.add(F3DataLine.of("Tag", tag));
            }
        }
        lines.add(F3DataLine.of("Actions", String.join(", ", target.actions())));
        return new F3Section("target", "Target", target.title(), target.accentColor(), lines);
    }

    private static F3Section vanillaDebugSection(MinecraftClient client, String id, String title, boolean left, F3Mode mode, F3TargetSnapshot target, PerformanceSnapshot performance) {
        List<F3DataLine> lines = new ArrayList<>();
        if (client == null || client.world == null || client.inGameHud == null) {
            lines.add(F3DataLine.state("Vanilla F3", "world not loaded", "inactive", 0xFF8D8D8D, "Vanilla F3 text is available after a world or server is loaded."));
            return new F3Section(id, title, "Inactive", 0xFF8D8D8D, lines);
        }
        try {
            DebugHudAccessor accessor = (DebugHudAccessor) ((InGameHudAccessor) client.inGameHud).koil$getDebugHud();
            List<String> vanillaLines = left ? accessor.koil$getLeftText() : accessor.koil$getRightText();
            for (int i = 0; i < vanillaLines.size(); i++) {
                lines.add(vanillaLine(vanillaLines.get(i), i));
            }
        } catch (Throwable throwable) {
            lines.addAll(vanillaFallbackLines(client, left, mode, target, performance));
        }
        if (lines.isEmpty()) {
            lines.addAll(vanillaFallbackLines(client, left, mode, target, performance));
        }
        return new F3Section(id, title, lines.size() + " lines", left ? 0xFF7FC8C2 : 0xFF8FC5FF, lines);
    }

    private static List<F3DataLine> vanillaFallbackLines(MinecraftClient client, boolean left, F3Mode mode, F3TargetSnapshot target, PerformanceSnapshot performance) {
        List<F3DataLine> lines = new ArrayList<>();
        if (left) {
            addSectionLines(lines, playerSection(client, mode, target));
            addSectionLines(lines, worldSection(client, mode));
            addSectionLines(lines, chunkSection(client));
        } else {
            addSectionLines(lines, systemSection(client, mode));
            addSectionLines(lines, renderSection(client, performance));
            addSectionLines(lines, networkSection(client));
        }
        return lines;
    }

    private static void addSectionLines(List<F3DataLine> out, F3Section section) {
        for (F3DataLine line : section.lines()) {
            out.add(line);
        }
    }

    private static String slimeChunkText(MinecraftClient client, ChunkPos chunkPos) {
        if (client == null || client.getServer() == null) {
            return "unknown seed";
        }
        long seed = client.getServer().getOverworld().getSeed();
        return String.valueOf(isSlimeChunk(chunkPos.x, chunkPos.z, seed));
    }

    private static boolean isSlimeChunk(int chunkX, int chunkZ, long seed) {
        Random random = new Random(seed + (long) (chunkX * chunkX * 4987142) + (long) (chunkX * 5947611) + (long) (chunkZ * chunkZ) * 4392871L + (long) (chunkZ * 389711) ^ 987234911L);
        return random.nextInt(10) == 0;
    }

    private static F3DataLine vanillaLine(String raw, int index) {
        if (raw == null || raw.isBlank()) {
            return F3DataLine.state("", "", "separator", 0xFF8D8D8D, "Vanilla F3 separator.");
        }
        String label = raw;
        String value = "";
        int split = raw.indexOf(": ");
        if (split < 0) {
            split = raw.indexOf(':');
        }
        if (split > 0) {
            label = raw.substring(0, split).trim();
            value = raw.substring(Math.min(raw.length(), split + 1)).trim();
        } else if (raw.length() > 34) {
            label = "#" + (index + 1);
            value = raw;
        }
        return F3DataLine.state(label, value, vanillaState(raw), vanillaColor(raw), raw);
    }

    private static String vanillaState(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT);
        if (normalized.contains("fps") || normalized.contains("ms ticks") || normalized.contains("chunk")) return "performance";
        if (normalized.contains("minecraft") || normalized.contains("java") || normalized.contains("cpu") || normalized.contains("display")) return "system";
        if (normalized.contains("gpu") || normalized.contains("opengl") || normalized.contains("renderer")) return "render";
        if (normalized.contains("mem") || normalized.contains("allocated")) return "memory";
        if (normalized.contains("xyz") || normalized.contains("block") || normalized.contains("facing") || normalized.contains("biome") || normalized.contains("light")) return "world";
        if (normalized.contains("targeted")) return "target";
        return "vanilla";
    }

    private static int vanillaColor(String raw) {
        return switch (vanillaState(raw)) {
            case "performance" -> 0xFF2DA700;
            case "system" -> 0xFF8FC5FF;
            case "render" -> 0xFFB47CFF;
            case "memory" -> 0xFFE56A9A;
            case "world" -> 0xFFE3B735;
            case "target" -> 0xFF7FC8C2;
            default -> 0xFFE6EDF5;
        };
    }

    private static List<String> warnings(MinecraftClient client, PerformanceSnapshot performance, F3TargetSnapshot target) {
        List<String> warnings = new ArrayList<>();
        if (performance.memoryPressure() > 0.90D) {
            warnings.add("Memory pressure is critical. Save work and reduce texture/resource pressure.");
        }
        if (performance.maxFrameTimeMs() > 100.0D) {
            warnings.add("Severe frame spike detected. Check chunk/render/entity graphs.");
        }
        if (client != null && client.world != null && performance.chunkStress() > 0.70D) {
            warnings.add("Chunk loading pressure is high near this location.");
        }
        if ("High".equalsIgnoreCase(target.danger())) {
            warnings.add("Target danger is high: " + target.title());
        }
        return warnings;
    }

    private static String targetToolStatus(ItemStack selected, F3TargetSnapshot target) {
        if (target == null || target.type() != F3TargetType.BLOCK) {
            return "no block target";
        }
        String hardness = targetLine(target, "Hardness");
        if ("unbreakable".equalsIgnoreCase(hardness) || hardness.startsWith("-")) {
            return "unbreakable target";
        }
        String suitable = targetLine(target, "Tool Suitable");
        String required = targetLine(target, "Tool Required");
        String item = selected.getName().getString();
        if ("true".equalsIgnoreCase(suitable)) {
            return "suitable: " + item;
        }
        if ("true".equalsIgnoreCase(required)) {
            return "wrong tool: " + item;
        }
        if ("false".equalsIgnoreCase(required)) {
            return "usable: " + item;
        }
        return "unknown: " + item;
    }

    private static String targetLine(F3TargetSnapshot target, String label) {
        if (target == null) {
            return "unknown";
        }
        for (F3DataLine line : target.lines()) {
            if (line.label().equals(label)) {
                return line.value() == null || line.value().isBlank() ? "unknown" : line.value();
            }
        }
        return "unknown";
    }

    private static String effectSummary(Iterable<StatusEffectInstance> effects) {
        List<String> values = new ArrayList<>();
        for (StatusEffectInstance effect : effects) {
            values.add(effect.getEffectType().getName().getString());
        }
        return String.join(", ", values);
    }

    private static String fpsState(int fps) {
        if (fps >= 60) return "stable";
        if (fps >= 45) return "caution";
        if (fps >= 30) return "pressure";
        return "critical";
    }

    private static int fpsColor(int fps) {
        if (fps >= 60) return 0xFF2DA700;
        if (fps >= 45) return 0xFFE3B735;
        if (fps >= 30) return 0xFFE06A21;
        return 0xFFA7003A;
    }

    private static String pressureState(double pressure) {
        if (pressure < 0.70D) return "stable";
        if (pressure < 0.85D) return "warning";
        if (pressure < 0.93D) return "pressure";
        return "critical";
    }

    private static int pressureColor(double pressure) {
        if (pressure < 0.70D) return 0xFF2DA700;
        if (pressure < 0.85D) return 0xFFE3B735;
        if (pressure < 0.93D) return 0xFFE06A21;
        return 0xFFA7003A;
    }

    private static String lightState(int light) {
        if (light >= 12) return "safe";
        if (light >= 8) return "caution";
        return "danger";
    }

    private static int lightColor(int light) {
        if (light >= 12) return 0xFF2DA700;
        if (light >= 8) return 0xFFE3B735;
        return 0xFFA7003A;
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String safeNetworkBrand(MinecraftClient client) {
        try {
            Object value = client.getNetworkHandler().getClass().getMethod("getBrand").invoke(client.getNetworkHandler());
            return value == null ? "unknown" : value.toString();
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }
}
