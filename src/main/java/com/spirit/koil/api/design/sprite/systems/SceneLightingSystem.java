package com.spirit.koil.api.design.sprite.systems;

import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneEnvironment;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.world.BlockCell;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import com.spirit.koil.api.design.sprite.world.FluidGrid;
import com.spirit.koil.api.design.sprite.world.SceneBlockView;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Terraria-inspired detached RGB lighting for Koil's authoritative scene.
 *
 * <p>The architecture intentionally mirrors the useful parts of Terraria's modern
 * tile lighting pipeline without depending on a Terraria or Minecraft world:
 * cells are scanned into an RGB light map, each destination cell is classified by
 * the medium light is travelling through, and RGB energy is blurred/propagated
 * using different decay through air, translucent blocks, solids and water. Koil
 * keeps each authored scene depth slice physically independent by default. A
 * scene may explicitly opt into cross-layer interactions through SceneProjection,
 * in which case light propagation follows the same policy.</p>
 *
 * <p>Minecraft remains the content specification. Native BlockState luminance is
 * authoritative for intensity, common vanilla emitters receive familiar color
 * temperatures, lava emits warm light, and modded emitters derive a sensible hue
 * from their native map color. Scene authors can override an individual cell
 * using runtime data: {@code light_color=#RRGGBB}, {@code light_intensity=0..15},
 * {@code light_transmission=0..1}, or {@code light_absorption=0..1}.</p>
 */
public final class SceneLightingSystem {
    private static final int MAX_LIGHT = 15;
    private static final int XY_PROPAGATION_MARGIN = 24;
    private static final int DEPTH_PROPAGATION_MARGIN = 2;
    private static final int MAX_AXIS_SPAN = 768;
    private static final float MIN_ENERGY = 0.018F;

    // Minecraft block light loses roughly one light level per travelled block.
    // Koil keeps that intensity rule while applying per-channel filters so colored
    // light can travel through water/translucent media without inventing a second
    // gameplay light level. Full opaque blocks receive face light but stop further
    // propagation. Vertical skylight through open air does not decay.
    private static final LightColor FILTER_AIR = LightColor.WHITE;
    private static final LightColor FILTER_TRANSLUCENT = new LightColor(0.96F, 0.98F, 1.0F);
    private static final LightColor FILTER_SOLID = new LightColor(0.72F, 0.72F, 0.72F);
    private static final LightColor FILTER_WATER = new LightColor(0.76F, 0.90F, 1.0F);
    private static final LightColor FILTER_LAVA = new LightColor(1.0F, 0.72F, 0.48F);
    private static final float BLOCK_LIGHT_STEP = 1.0F / 15.0F;
    private static final float TRANSLUCENT_LIGHT_STEP = 1.0F / 15.0F;
    private static final float WATER_LIGHT_STEP = 1.0F / 15.0F;
    private static final float CUTAWAY_AMBIENT_DAY = 0.46F;
    private static final float CUTAWAY_AMBIENT_NIGHT = 0.20F;

    private static final Map<Identifier, LightColor> VANILLA_EMISSION_COLORS = vanillaEmissionColors();

    private final BlockGrid blocks;
    private final FluidGrid fluids;
    private final SceneProjection projection;
    private final SceneEnvironment environment;
    private final SceneBlockView view;

    private final Map<SceneCellPos, LightColor> sky = new HashMap<>();
    private final Map<SceneCellPos, LightColor> block = new HashMap<>();
    private long blockRevision = Long.MIN_VALUE;
    private long fluidRevision = Long.MIN_VALUE;
    private int cachedSkyLight = Integer.MIN_VALUE;
    private int cachedBlockLight = Integer.MIN_VALUE;
    private long cachedTimeOfDay = Long.MIN_VALUE;
    private boolean cachedRaining;
    private boolean cachedThundering;
    private Identifier cachedDimension;
    private SceneProjection.Mode cachedProjectionMode;
    private long cachedInteractionRevision = Long.MIN_VALUE;
    private Bounds bounds = Bounds.EMPTY;

    public SceneLightingSystem(BlockGrid blocks, FluidGrid fluids,
                               SceneProjection projection, SceneEnvironment environment) {
        this.blocks = blocks;
        this.fluids = fluids;
        this.projection = projection;
        this.environment = environment;
        this.view = new SceneBlockView(blocks, fluids);
    }

    /** Combined 0..15 light level retained for Minecraft lightmap consumers. */
    public int lightLevel(SceneCellPos pos) {
        LightColor raw = rawColor(pos);
        return clampLight(Math.round(raw.maxComponent() * MAX_LIGHT));
    }

    /**
     * Final RGB multiplier used by Koil renderers. Depth/layer is not a lighting
     * cue: two otherwise identical cells on different Z slices receive identical
     * presentation light unless their own layer contents/environment differ.
     */
    public LightColor color(SceneCellPos pos) {
        LightColor raw = rawColor(pos);
        // Multiplying sRGB art by fully linear darkness becomes unreadable quickly.
        // Terraria likewise treats its light map as display color. A small floor
        // preserves silhouettes while still allowing caves to become genuinely dark.
        return new LightColor(present(raw.r()), present(raw.g()), present(raw.b())).clamp();
    }

    /** Legacy scalar brightness for callers that cannot consume RGB yet. */
    public float brightness(SceneCellPos pos) {
        return color(pos).luminance();
    }

    public LightColor skyColor(SceneCellPos pos) {
        rebuildIfNeeded();
        return sky.getOrDefault(pos, ambientSkyForEmpty(pos));
    }

    public LightColor blockColor(SceneCellPos pos) {
        rebuildIfNeeded();
        return block.getOrDefault(pos, LightColor.BLACK);
    }

    public int skyLight(SceneCellPos pos) {
        return clampLight(Math.round(skyColor(pos).maxComponent() * MAX_LIGHT));
    }

    public int blockLight(SceneCellPos pos) {
        return clampLight(Math.round(blockColor(pos).maxComponent() * MAX_LIGHT));
    }

    public String maskName(SceneCellPos pos) {
        return maskAt(pos).name().toLowerCase(Locale.ROOT);
    }

    /**
     * Terraria-style corner/sub-tile light sampling for baked block geometry.
     * Each corner averages the four neighboring light-map cells that meet there.
     * Renderers should obtain this once per block and reuse it for every baked quad.
     */
    public ProjectedCorners projectedCorners(SceneCellPos pos) {
        if (pos == null || projection == null) {
            LightColor color = color(pos);
            return new ProjectedCorners(color, color, color, color);
        }
        SceneCellPos left = projection.offset(pos, projection.screenLeftDirection());
        SceneCellPos right = projection.offset(pos, projection.screenRightDirection());
        SceneCellPos up = projection.offset(pos, projection.screenUpDirection());
        SceneCellPos down = projection.offset(pos, projection.screenDownDirection());
        SceneCellPos upLeft = projection.offset(up, projection.screenLeftDirection());
        SceneCellPos upRight = projection.offset(up, projection.screenRightDirection());
        SceneCellPos downLeft = projection.offset(down, projection.screenLeftDirection());
        SceneCellPos downRight = projection.offset(down, projection.screenRightDirection());

        LightColor center = color(pos);
        LightColor topLeft = LightColor.average(center, color(left), color(up), color(upLeft));
        LightColor topRight = LightColor.average(center, color(right), color(up), color(upRight));
        LightColor bottomLeft = LightColor.average(center, color(left), color(down), color(downLeft));
        LightColor bottomRight = LightColor.average(center, color(right), color(down), color(downRight));
        return new ProjectedCorners(topLeft, topRight, bottomRight, bottomLeft);
    }

    public LightColor sampleProjected(SceneCellPos pos, float localX, float localY) {
        return projectedCorners(pos).sample(localX, localY);
    }

    public void invalidate() {
        blockRevision = Long.MIN_VALUE;
        fluidRevision = Long.MIN_VALUE;
        cachedSkyLight = Integer.MIN_VALUE;
        cachedBlockLight = Integer.MIN_VALUE;
        cachedTimeOfDay = Long.MIN_VALUE;
        cachedDimension = null;
        cachedProjectionMode = null;
        cachedInteractionRevision = Long.MIN_VALUE;
    }

    public void reset() {
        sky.clear();
        block.clear();
        bounds = Bounds.EMPTY;
        invalidate();
    }

    private LightColor rawColor(SceneCellPos pos) {
        if (pos == null) {
            float skyLevel = environment == null ? 1.0F : environment.skyLight() / 15.0F;
            return skyTint().scale(skyLevel);
        }
        rebuildIfNeeded();
        LightColor skyColor = sky.getOrDefault(pos, ambientSkyForEmpty(pos));
        LightColor blockColor = block.getOrDefault(pos, LightColor.BLACK);
        float ambientLevel = environment == null ? 0.0F : environment.blockLight() / 15.0F;
        LightColor ambient = new LightColor(ambientLevel, ambientLevel, ambientLevel);
        LightColor cutaway = cutawayAmbient(pos);
        // Minecraft itself stores scalar sky/block channels, but Koil extends the
        // block channel to RGB. Screen-composition preserves the stronger native
        // light while allowing torch/soul/portal colors to tint daylight instead
        // of being discarded by a simple per-channel max().
        return skyColor.screen(blockColor).screen(ambient).screen(cutaway).clamp();
    }

    private void rebuildIfNeeded() {
        long br = blocks == null ? 0L : blocks.revision();
        long fr = fluids == null ? 0L : fluids.revision();
        int skyValue = environment == null ? MAX_LIGHT : environment.skyLight();
        int blockValue = environment == null ? 0 : environment.blockLight();
        long time = environment == null ? 6000L : environment.timeOfDay();
        boolean raining = environment != null && environment.raining();
        boolean thundering = environment != null && environment.thundering();
        Identifier dimension = environment == null ? null : environment.dimensionId();
        SceneProjection.Mode projectionMode = projection == null ? SceneProjection.Mode.XY_SIDE : projection.mode();
        long interactionRevision = projection == null ? 0L : projection.interactionRevision();
        if (br == blockRevision && fr == fluidRevision
                && skyValue == cachedSkyLight && blockValue == cachedBlockLight
                && time == cachedTimeOfDay && raining == cachedRaining
                && thundering == cachedThundering
                && java.util.Objects.equals(dimension, cachedDimension)
                && projectionMode == cachedProjectionMode
                && interactionRevision == cachedInteractionRevision) return;
        blockRevision = br;
        fluidRevision = fr;
        cachedSkyLight = skyValue;
        cachedBlockLight = blockValue;
        cachedTimeOfDay = time;
        cachedRaining = raining;
        cachedThundering = thundering;
        cachedDimension = dimension;
        cachedProjectionMode = projectionMode;
        cachedInteractionRevision = interactionRevision;
        rebuild();
    }

    private void rebuild() {
        sky.clear();
        block.clear();
        bounds = computeBounds();
        if (bounds.empty()) return;
        buildSkyLight();
        buildBlockLight();
    }

    private Bounds computeBounds() {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minD = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxD = Integer.MIN_VALUE;
        if (blocks != null) {
            for (BlockGrid.Entry entry : blocks.entries()) {
                if (entry == null || entry.position() == null) continue;
                SceneCellPos p = entry.position();
                minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
                minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
                minD = Math.min(minD, p.depth()); maxD = Math.max(maxD, p.depth());
            }
        }
        if (fluids != null) {
            for (FluidGrid.Entry entry : fluids.entries()) {
                if (entry == null || entry.position() == null) continue;
                SceneCellPos p = entry.position();
                minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
                minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
                minD = Math.min(minD, p.depth()); maxD = Math.max(maxD, p.depth());
            }
        }
        if (minX == Integer.MAX_VALUE) return Bounds.EMPTY;
        maxX = Math.min(maxX, minX + MAX_AXIS_SPAN);
        maxY = Math.min(maxY, minY + MAX_AXIS_SPAN);
        maxD = Math.min(maxD, minD + 128);
        return new Bounds(minX, maxX, minY, maxY, minD, maxD);
    }


    /** Tile-scan stage: seed exposed columns with time/weather tinted skylight. */
    private void buildSkyLight() {
        LightColor incomingSky = skySource();
        if (incomingSky.maxComponent() <= MIN_ENERGY) return;

        Set<Long> columns = new HashSet<>();
        if (blocks != null) for (BlockGrid.Entry entry : blocks.entries()) {
            if (entry != null && entry.position() != null) columns.add(columnKey(entry.position().x(), entry.position().depth()));
        }
        if (fluids != null) for (FluidGrid.Entry entry : fluids.entries()) {
            if (entry != null && entry.position() != null) columns.add(columnKey(entry.position().x(), entry.position().depth()));
        }

        ArrayDeque<ColorNode> queue = new ArrayDeque<>();
        for (long key : columns) {
            int x = (int) (key >> 32);
            int depth = (int) key;
            LightColor incoming = incomingSky;
            for (int y = bounds.maxY + 1; y >= bounds.minY - 1 && incoming.maxComponent() > MIN_ENERGY; y--) {
                SceneCellPos p = new SceneCellPos(x, y, depth);
                LightMask mask = maskAt(p);
                boolean changed = putMax(sky, p, incoming);
                if (changed && mask != LightMask.SOLID) queue.addLast(new ColorNode(p, incoming));
                // Vanilla skylight remains level 15 while travelling straight
                // down through open air. Only real opacity/media attenuates it.
                if (mask == LightMask.SOLID) {
                    incoming = LightColor.BLACK;
                    break;
                }
                incoming = skyTransmission(incoming, mask);
            }
        }
        // Terraria blurs the scanned light map after masks are assigned. Koil does
        // the same as a sparse flood, but SceneProjection decides whether the
        // hidden layer axis participates. It is isolated by default.
        propagate(sky, queue);
    }

    /** Tile-light stage: seed native Minecraft emitters and blur them through masks. */
    private void buildBlockLight() {
        ArrayDeque<ColorNode> queue = new ArrayDeque<>();
        if (blocks != null) {
            for (BlockGrid.Entry entry : blocks.entries()) {
                if (entry == null || entry.position() == null || entry.cell() == null) continue;
                BlockState state = entry.cell().blockState();
                int emission = emissionLevel(entry.cell(), state);
                if (emission <= 0) continue;
                LightColor source = emissionColor(entry.cell(), state, entry.position()).scale(emission / 15.0F).clamp();
                if (putMax(block, entry.position(), source)) queue.addLast(new ColorNode(entry.position(), source));
            }
        }
        if (fluids != null) {
            for (FluidGrid.Entry entry : fluids.entries()) {
                if (entry == null || entry.position() == null || entry.cell() == null) continue;
                FluidState state = entry.cell().state();
                if (state == null || state.isEmpty()) continue;
                if (state.isOf(Fluids.LAVA) || state.isOf(Fluids.FLOWING_LAVA)) {
                    LightColor source = new LightColor(1.0F, 0.34F, 0.075F);
                    if (putMax(block, entry.position(), source)) queue.addLast(new ColorNode(entry.position(), source));
                }
            }
        }
        propagate(block, queue);
    }

    private void propagate(Map<SceneCellPos, LightColor> map, ArrayDeque<ColorNode> queue) {
        while (!queue.isEmpty()) {
            ColorNode current = queue.removeFirst();
            LightColor latest = map.get(current.pos());
            if (latest == null || latest.maxComponent() > current.color().maxComponent() + 0.001F) continue;
            if (current.color().maxComponent() <= MIN_ENERGY) continue;
            for (SceneCellPos next : neighbors(current.pos())) {
                if (!withinPropagationBounds(next)) continue;
                LightMask mask = maskAt(next);
                LightColor candidate = attenuate(current.color(), mask);
                if (candidate.maxComponent() <= MIN_ENERGY) continue;
                if (putMax(map, next, candidate) && mask != LightMask.SOLID) {
                    queue.addLast(new ColorNode(next, candidate));
                }
            }
        }
    }

    private LightColor attenuate(LightColor source, LightMask mask) {
        LightColor filter = switch (mask) {
            case AIR -> FILTER_AIR;
            case TRANSLUCENT -> FILTER_TRANSLUCENT;
            case SOLID -> FILTER_SOLID;
            case WATER -> FILTER_WATER;
            case LAVA -> FILTER_LAVA;
        };
        float drop = switch (mask) {
            case AIR -> BLOCK_LIGHT_STEP;
            case TRANSLUCENT -> TRANSLUCENT_LIGHT_STEP;
            case WATER, LAVA -> WATER_LIGHT_STEP;
            case SOLID -> BLOCK_LIGHT_STEP;
        };
        LightColor filtered = source.multiply(filter);
        return new LightColor(Math.max(0.0F, filtered.r() - drop),
                Math.max(0.0F, filtered.g() - drop),
                Math.max(0.0F, filtered.b() - drop));
    }

    private LightColor skyTransmission(LightColor incoming, LightMask mask) {
        return switch (mask) {
            case AIR -> incoming;
            case TRANSLUCENT -> incoming.multiply(FILTER_TRANSLUCENT).scale(0.96F);
            case WATER -> incoming.multiply(FILTER_WATER).scale(0.93F);
            case LAVA -> incoming.multiply(FILTER_LAVA).scale(0.72F);
            case SOLID -> LightColor.BLACK;
        };
    }

    private LightMask maskAt(SceneCellPos pos) {
        if (pos == null) return LightMask.AIR;
        FluidState fluid = fluids == null ? Fluids.EMPTY.getDefaultState() : fluids.getFluidState(pos);
        if (fluid != null && !fluid.isEmpty()) {
            if (fluid.isOf(Fluids.WATER) || fluid.isOf(Fluids.FLOWING_WATER)) return LightMask.WATER;
            if (fluid.isOf(Fluids.LAVA) || fluid.isOf(Fluids.FLOWING_LAVA)) return LightMask.LAVA;
            return LightMask.TRANSLUCENT;
        }

        BlockState state = blocks == null ? Blocks.AIR.getDefaultState() : blocks.getBlockState(pos);
        if (state == null || state.isAir()) return LightMask.AIR;
        int opacity = opacityAt(pos, state);
        if (opacity >= 12) return LightMask.SOLID;
        if (opacity > 0) return LightMask.TRANSLUCENT;
        try {
            if (state.isOpaque()) return LightMask.SOLID;
        } catch (RuntimeException ignored) { }
        return LightMask.AIR;
    }

    private LightColor transmissionAt(SceneCellPos pos) {
        BlockCell cell = blocks == null ? null : blocks.get(pos);
        if (cell != null) {
            String explicitTransmission = cell.runtime("light_transmission", "");
            if (hasExplicitValue(explicitTransmission)) {
                float t = clamp(parseFloat(explicitTransmission, 1.0F), 0.0F, 1.0F);
                return new LightColor(t, t, t);
            }
            String explicitAbsorption = cell.runtime("light_absorption", "");
            if (hasExplicitValue(explicitAbsorption)) {
                float t = 1.0F - clamp(parseFloat(explicitAbsorption, 0.0F), 0.0F, 1.0F);
                return new LightColor(t, t, t);
            }
        }
        return switch (maskAt(pos)) {
            case AIR -> FILTER_AIR;
            case TRANSLUCENT -> FILTER_TRANSLUCENT;
            case SOLID -> LightColor.BLACK;
            case WATER -> FILTER_WATER;
            case LAVA -> FILTER_LAVA;
        };
    }

    private int opacityAt(SceneCellPos pos, BlockState state) {
        if (state == null || state.isAir()) return 0;
        try {
            return clampLight(state.getOpacity(view, new BlockPos(pos.x(), pos.y(), pos.depth())));
        } catch (RuntimeException ignored) {
            try { return state.isOpaque() ? 15 : 1; }
            catch (RuntimeException ignoredAgain) { return 1; }
        }
    }

    private int emissionLevel(BlockCell cell, BlockState state) {
        if (cell != null) {
            String override = cell.runtime("light_intensity", "");
            if (hasExplicitValue(override)) return clampLight(Math.round(parseFloat(override, 0.0F)));
        }
        if (state == null || state.isAir()) return 0;
        try { return clampLight(state.getLuminance()); }
        catch (RuntimeException ignored) { return 0; }
    }

    private LightColor emissionColor(BlockCell cell, BlockState state, SceneCellPos pos) {
        if (cell != null) {
            LightColor custom = parseColor(cell.runtime("light_color", ""));
            if (custom != null) return custom;
        }
        if (state == null || state.isAir()) return LightColor.WHITE;
        Identifier id;
        try { id = Registries.BLOCK.getId(state.getBlock()); }
        catch (RuntimeException ignored) { id = null; }

        LightColor exact = id == null ? null : VANILLA_EMISSION_COLORS.get(id);
        if (exact != null) return exact;

        // Modded luminant blocks stay automatic. Minecraft exposes a map color
        // for every block state, which is a better hue fallback than registry-name
        // guessing and keeps resource/mod content usable without a Koil whitelist.
        try {
            BlockPos blockPos = pos == null ? BlockPos.ORIGIN : new BlockPos(pos.x(), pos.y(), pos.depth());
            MapColor mapColor = state.getMapColor(view, blockPos);
            if (mapColor != null && mapColor != MapColor.CLEAR) {
                int rgb = mapColor.getRenderColor(MapColor.Brightness.NORMAL);
                LightColor mapped = LightColor.fromRgb(rgb);
                // Emission generally reads brighter/less saturated than a map texel.
                return mapped.lerp(LightColor.WHITE, 0.24F).normalized(0.90F);
            }
        } catch (RuntimeException ignored) { }
        return new LightColor(1.0F, 0.82F, 0.62F);
    }

    private static Map<Identifier, LightColor> vanillaEmissionColors() {
        Map<Identifier, LightColor> colors = new HashMap<>();
        LightColor warm = new LightColor(1.0F, 0.66F, 0.30F);
        LightColor fire = new LightColor(1.0F, 0.48F, 0.18F);
        LightColor soul = new LightColor(0.24F, 0.78F, 1.0F);
        LightColor redstone = new LightColor(1.0F, 0.12F, 0.08F);
        LightColor sea = new LightColor(0.58F, 0.94F, 1.0F);
        LightColor portal = new LightColor(0.72F, 0.30F, 1.0F);
        putColors(colors, warm,
                "minecraft:torch", "minecraft:wall_torch", "minecraft:lantern",
                "minecraft:glowstone", "minecraft:redstone_lamp", "minecraft:magma_block",
                "minecraft:jack_o_lantern", "minecraft:ochre_froglight",
                "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker",
                "minecraft:brown_mushroom",
                "minecraft:candle", "minecraft:white_candle", "minecraft:orange_candle",
                "minecraft:magenta_candle", "minecraft:light_blue_candle", "minecraft:yellow_candle",
                "minecraft:lime_candle", "minecraft:pink_candle", "minecraft:gray_candle",
                "minecraft:light_gray_candle", "minecraft:cyan_candle", "minecraft:purple_candle",
                "minecraft:blue_candle", "minecraft:brown_candle", "minecraft:green_candle",
                "minecraft:red_candle", "minecraft:black_candle");
        putColors(colors, fire, "minecraft:fire", "minecraft:campfire", "minecraft:lava");
        putColors(colors, soul, "minecraft:soul_fire", "minecraft:soul_torch",
                "minecraft:soul_wall_torch", "minecraft:soul_lantern", "minecraft:soul_campfire");
        putColors(colors, redstone, "minecraft:redstone_torch", "minecraft:redstone_wall_torch",
                "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore");
        putColors(colors, sea, "minecraft:sea_lantern", "minecraft:conduit", "minecraft:sea_pickle");
        putColors(colors, new LightColor(0.92F, 0.98F, 1.0F), "minecraft:beacon", "minecraft:light");
        putColors(colors, new LightColor(1.0F, 0.72F, 0.94F), "minecraft:pearlescent_froglight");
        putColors(colors, new LightColor(0.72F, 1.0F, 0.72F), "minecraft:verdant_froglight");
        putColors(colors, new LightColor(1.0F, 0.78F, 0.38F), "minecraft:ochre_froglight");
        putColors(colors, new LightColor(0.82F, 0.78F, 1.0F), "minecraft:end_rod");
        putColors(colors, portal, "minecraft:nether_portal", "minecraft:crying_obsidian", "minecraft:respawn_anchor",
                "minecraft:end_portal", "minecraft:end_gateway", "minecraft:dragon_egg");
        putColors(colors, new LightColor(0.54F, 1.0F, 0.58F), "minecraft:end_portal_frame");
        putColors(colors, new LightColor(1.0F, 0.52F, 0.22F), "minecraft:shroomlight");
        putColors(colors, new LightColor(0.14F, 0.80F, 0.92F),
                "minecraft:sculk_catalyst", "minecraft:sculk_sensor", "minecraft:calibrated_sculk_sensor");
        putColors(colors, new LightColor(0.78F, 0.48F, 1.0F),
                "minecraft:small_amethyst_bud", "minecraft:medium_amethyst_bud",
                "minecraft:large_amethyst_bud", "minecraft:amethyst_cluster");
        putColors(colors, new LightColor(0.56F, 0.78F, 0.54F), "minecraft:glow_lichen");
        putColors(colors, new LightColor(1.0F, 0.68F, 0.28F), "minecraft:cave_vines", "minecraft:cave_vines_plant");
        return Map.copyOf(colors);
    }

    private static void putColors(Map<Identifier, LightColor> map, LightColor color, String... ids) {
        if (map == null || color == null || ids == null) return;
        for (String raw : ids) {
            Identifier id = Identifier.tryParse(raw);
            if (id != null) map.put(id, color);
        }
    }

    private LightColor skySource() {
        float level = (environment == null ? MAX_LIGHT : environment.skyLight()) / 15.0F;
        if (level <= 0.0F) return LightColor.BLACK;
        float daylight = daylightFactor(environment == null ? 6000L : environment.timeOfDay());
        // Environment skyLight is still authoritative, but time supplies the
        // Terraria-style ambient color/intensity modulation needed for backgrounds.
        float timeStrength = 0.11F + 0.89F * daylight;
        float weather = 1.0F;
        if (environment != null && environment.raining()) weather *= 0.82F;
        if (environment != null && environment.thundering()) weather *= 0.68F;
        return skyTint().scale(level * timeStrength * weather).clamp();
    }

    private LightColor skyTint() {
        Identifier dimension = environment == null ? null : environment.dimensionId();
        String path = dimension == null ? "" : dimension.getPath().toLowerCase(Locale.ROOT);
        if (path.contains("nether")) return new LightColor(1.0F, 0.34F, 0.22F);
        if (path.contains("end")) return new LightColor(0.70F, 0.64F, 0.94F);

        long time = environment == null ? 6000L : environment.timeOfDay();
        float dawnDusk = dawnDuskFactor(time);
        LightColor day = new LightColor(1.0F, 0.965F, 0.90F);
        LightColor twilight = new LightColor(1.0F, 0.54F, 0.34F);
        LightColor night = new LightColor(0.30F, 0.38F, 0.62F);
        float daylight = daylightFactor(time);
        LightColor base = night.lerp(day, daylight);
        return base.lerp(twilight, dawnDusk * 0.55F).clamp();
    }

    private static float daylightFactor(long timeOfDay) {
        double angle = ((Math.floorMod(timeOfDay, 24000L) - 6000L) / 24000.0) * Math.PI * 2.0;
        return clamp((float) ((Math.cos(angle) + 1.0) * 0.5), 0.0F, 1.0F);
    }

    private static float dawnDuskFactor(long timeOfDay) {
        long t = Math.floorMod(timeOfDay, 24000L);
        float sunrise = 1.0F - Math.min(1.0F, Math.abs(t - 0L) / 1800.0F);
        float sunset = 1.0F - Math.min(1.0F, Math.abs(t - 12000L) / 1800.0F);
        return Math.max(sunrise, sunset);
    }


    /**
     * A Koil side view exposes a Minecraft cut face that would be hidden inside a
     * 3D chunk. Give only that camera-facing exposed face a soft environment
     * bounce so caves remain dark while readable terrain does not collapse to
     * near-black. This is presentation lighting, not gameplay light level.
     */
    private LightColor cutawayAmbient(SceneCellPos pos) {
        if (pos == null || blocks == null) return LightColor.BLACK;
        BlockState state = blocks.getBlockState(pos);
        if (state == null || state.isAir()) return LightColor.BLACK;
        SceneCellPos front = cameraFacingNeighbor(pos);
        if (front == null) return LightColor.BLACK;
        BlockState frontState = blocks.getBlockState(front);
        FluidState frontFluid = fluids == null ? Fluids.EMPTY.getDefaultState() : fluids.getFluidState(front);
        float frontTransmission = 1.0F;
        if (frontState != null && !frontState.isAir()) {
            int opacity = opacityAt(front, frontState);
            if (opacity >= 12) return LightColor.BLACK;
            frontTransmission *= Math.max(0.35F, 1.0F - opacity / 18.0F);
        }
        if (frontFluid != null && !frontFluid.isEmpty()) {
            if (frontFluid.isOf(Fluids.WATER) || frontFluid.isOf(Fluids.FLOWING_WATER)) frontTransmission *= 0.78F;
            else if (frontFluid.isOf(Fluids.LAVA) || frontFluid.isOf(Fluids.FLOWING_LAVA)) frontTransmission *= 0.52F;
            else frontTransmission *= 0.68F;
        }

        float daylight = daylightFactor(environment == null ? 6000L : environment.timeOfDay());
        float strength = (CUTAWAY_AMBIENT_NIGHT + (CUTAWAY_AMBIENT_DAY - CUTAWAY_AMBIENT_NIGHT) * daylight)
                * frontTransmission;
        BlockCell cell = blocks.get(pos);
        if (cell != null && cell.runtimeData().containsKey("generated_source")) strength += 0.08F;
        if (environment != null && environment.raining()) strength *= 0.90F;
        if (environment != null && environment.thundering()) strength *= 0.82F;
        return skyTint().scale(clamp(strength, 0.0F, 0.62F));
    }

    private SceneCellPos cameraFacingNeighbor(SceneCellPos pos) {
        if (projection == null || pos == null) return null;
        return switch (projection.mode()) {
            case XY_SIDE -> pos.add(0, 0, 1);
            case XZ_TOP -> pos.add(0, 1, 0);
            case ZY_SIDE -> pos.add(1, 0, 0);
        };
    }

    private LightColor ambientSkyForEmpty(SceneCellPos pos) {
        LightColor incoming = skySource();
        if (pos == null || bounds.empty() || incoming.maxComponent() <= MIN_ENERGY || pos.y() > bounds.maxY) return incoming;
        for (int y = bounds.maxY + 1; y > pos.y() && incoming.maxComponent() > MIN_ENERGY; y--) {
            incoming = incoming.multiply(transmissionAt(new SceneCellPos(pos.x(), y, pos.depth())));
        }
        return incoming.clamp();
    }

    private boolean withinPropagationBounds(SceneCellPos p) {
        if (bounds.empty()) return false;
        return p.x() >= bounds.minX - XY_PROPAGATION_MARGIN && p.x() <= bounds.maxX + XY_PROPAGATION_MARGIN
                && p.y() >= bounds.minY - XY_PROPAGATION_MARGIN && p.y() <= bounds.maxY + XY_PROPAGATION_MARGIN
                && p.depth() >= bounds.minDepth - DEPTH_PROPAGATION_MARGIN
                && p.depth() <= bounds.maxDepth + DEPTH_PROPAGATION_MARGIN;
    }

    private static boolean putMax(Map<SceneCellPos, LightColor> map, SceneCellPos pos, LightColor candidate) {
        if (pos == null || candidate == null) return false;
        LightColor previous = map.get(pos);
        LightColor merged = previous == null ? candidate.clamp() : previous.max(candidate).clamp();
        if (previous != null && merged.nearlyEquals(previous)) return false;
        map.put(pos, merged);
        return true;
    }

    private SceneCellPos[] neighbors(SceneCellPos p) {
        if (p == null) return new SceneCellPos[0];
        if (projection == null) {
            return new SceneCellPos[]{
                    p.add(1, 0, 0), p.add(-1, 0, 0), p.add(0, 1, 0), p.add(0, -1, 0),
                    p.add(0, 0, 1), p.add(0, 0, -1)
            };
        }
        java.util.ArrayList<SceneCellPos> result = new java.util.ArrayList<>(6);
        for (net.minecraft.util.math.Direction direction : net.minecraft.util.math.Direction.values()) {
            SceneCellPos next = projection.interactionOffset(p, direction);
            if (next != null) result.add(next);
        }
        return result.toArray(SceneCellPos[]::new);
    }

    private static long columnKey(int x, int depth) {
        return (((long) x) << 32) ^ (depth & 0xFFFFFFFFL);
    }

    private static float present(float value) {
        float v = clamp(value, 0.0F, 1.0F);
        float displayed = (float) Math.pow(v, 0.72);
        return 0.085F + displayed * 0.915F;
    }

    private static LightColor parseColor(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty()) return null;
        try {
            if (text.startsWith("#")) text = text.substring(1);
            if (text.startsWith("0x") || text.startsWith("0X")) text = text.substring(2);
            if (text.matches("[0-9a-fA-F]{6}")) {
                int rgb = Integer.parseInt(text, 16);
                return new LightColor(((rgb >> 16) & 0xFF) / 255.0F,
                        ((rgb >> 8) & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F);
            }
            String[] split = text.split("[, ]+");
            if (split.length >= 3) {
                float r = Float.parseFloat(split[0]);
                float g = Float.parseFloat(split[1]);
                float b = Float.parseFloat(split[2]);
                float scale = Math.max(r, Math.max(g, b)) > 1.0F ? 255.0F : 1.0F;
                return new LightColor(r / scale, g / scale, b / scale).clamp();
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static boolean hasExplicitValue(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !normalized.isEmpty() && !normalized.equals("auto") && !normalized.equals("default")
                && !normalized.equals("native");
    }

    private static float parseFloat(String value, float fallback) {
        try {
            float parsed = Float.parseFloat(value == null ? "" : value.trim());
            return Float.isFinite(parsed) ? parsed : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int clampLight(int value) { return Math.max(0, Math.min(MAX_LIGHT, value)); }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }

    private enum LightMask { AIR, TRANSLUCENT, SOLID, WATER, LAVA }

    private record ColorNode(SceneCellPos pos, LightColor color) { }

    private record Bounds(int minX, int maxX, int minY, int maxY, int minDepth, int maxDepth) {
        static final Bounds EMPTY = new Bounds(0, -1, 0, -1, 0, -1);
        boolean empty() { return maxX < minX || maxY < minY || maxDepth < minDepth; }
    }

    public record ProjectedCorners(LightColor topLeft, LightColor topRight,
                                   LightColor bottomRight, LightColor bottomLeft) {
        public LightColor sample(float localX, float localY) {
            float x = SceneLightingSystem.clamp(localX, 0.0F, 1.0F);
            float y = SceneLightingSystem.clamp(localY, 0.0F, 1.0F);
            LightColor top = topLeft.lerp(topRight, x);
            LightColor bottom = bottomLeft.lerp(bottomRight, x);
            return top.lerp(bottom, y).clamp();
        }
    }

    /** Immutable RGB light-map sample in display-linear 0..1 coordinates. */
    public record LightColor(float r, float g, float b) {
        public static final LightColor BLACK = new LightColor(0.0F, 0.0F, 0.0F);
        public static final LightColor WHITE = new LightColor(1.0F, 1.0F, 1.0F);

        public LightColor clamp() {
            return new LightColor(SceneLightingSystem.clamp(r, 0.0F, 1.0F),
                    SceneLightingSystem.clamp(g, 0.0F, 1.0F),
                    SceneLightingSystem.clamp(b, 0.0F, 1.0F));
        }

        public LightColor scale(float scale) {
            return new LightColor(r * scale, g * scale, b * scale);
        }

        public LightColor multiply(LightColor other) {
            if (other == null) return this;
            return new LightColor(r * other.r, g * other.g, b * other.b);
        }

        /** Additive-looking composition that cannot clip a daylight channel above 1. */
        public LightColor screen(LightColor other) {
            if (other == null) return this;
            LightColor a = clamp();
            LightColor b = other.clamp();
            return new LightColor(1.0F - (1.0F - a.r()) * (1.0F - b.r()),
                    1.0F - (1.0F - a.g()) * (1.0F - b.g()),
                    1.0F - (1.0F - a.b()) * (1.0F - b.b()));
        }

        public LightColor normalized(float targetMax) {
            float max = maxComponent();
            if (max <= 0.0001F) return BLACK;
            return scale(SceneLightingSystem.clamp(targetMax, 0.0F, 1.0F) / max).clamp();
        }

        public static LightColor fromRgb(int rgb) {
            return new LightColor(((rgb >> 16) & 0xFF) / 255.0F,
                    ((rgb >> 8) & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F);
        }

        public LightColor max(LightColor other) {
            if (other == null) return this;
            return new LightColor(Math.max(r, other.r), Math.max(g, other.g), Math.max(b, other.b));
        }

        public LightColor lerp(LightColor target, float amount) {
            if (target == null) return this;
            float t = SceneLightingSystem.clamp(amount, 0.0F, 1.0F);
            return new LightColor(r + (target.r - r) * t,
                    g + (target.g - g) * t,
                    b + (target.b - b) * t);
        }

        public static LightColor average(LightColor... colors) {
            if (colors == null || colors.length == 0) return BLACK;
            float rr = 0.0F, gg = 0.0F, bb = 0.0F;
            int count = 0;
            for (LightColor color : colors) {
                if (color == null) continue;
                rr += color.r; gg += color.g; bb += color.b; count++;
            }
            return count == 0 ? BLACK : new LightColor(rr / count, gg / count, bb / count).clamp();
        }

        public float maxComponent() { return Math.max(r, Math.max(g, b)); }
        public float luminance() { return SceneLightingSystem.clamp(r * 0.2126F + g * 0.7152F + b * 0.0722F, 0.0F, 1.0F); }
        public int rgb24() {
            int rr = Math.round(SceneLightingSystem.clamp(r, 0.0F, 1.0F) * 255.0F);
            int gg = Math.round(SceneLightingSystem.clamp(g, 0.0F, 1.0F) * 255.0F);
            int bb = Math.round(SceneLightingSystem.clamp(b, 0.0F, 1.0F) * 255.0F);
            return (rr << 16) | (gg << 8) | bb;
        }
        public String hex() { return String.format(Locale.ROOT, "#%06X", rgb24()); }

        private boolean nearlyEquals(LightColor other) {
            return other != null && Math.abs(r - other.r) < 0.001F
                    && Math.abs(g - other.g) < 0.001F && Math.abs(b - other.b) < 0.001F;
        }
    }
}
