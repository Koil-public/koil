package com.spirit.koil.api.design.particle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.design.sprite.SpriteEngine;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.minecraft.MinecraftStateCodec;
import com.spirit.koil.api.design.sprite.world.Scene;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.color.world.FoliageColors;
import net.minecraft.client.color.world.GrassColors;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.function.ToFloatFunction;
import net.minecraft.util.math.Spline;
import net.minecraft.world.biome.source.util.VanillaTerrainParametersCreator;
import net.minecraft.world.gen.densityfunction.DensityFunctions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Projects real Minecraft/mod data-pack worldgen definitions into Koil's
 * detached 2D scene. Vanilla generators require StructureWorldAccess and a
 * ChunkGenerator, so Koil does not call those world-bound methods. Instead the
 * actual data resource chooses the content/configuration and this adapter maps
 * it into authoritative scene BlockState/FluidState cells.
 */
public final class SpriteWorldgenGenerator {
    private SpriteWorldgenGenerator() { }

    /**
     * Vanilla 1.20.1 uses continentalness, erosion, ridges/peaks-valleys and
     * splines to shape the overworld. Koil samples detached deterministic noise
     * for those inputs, then delegates the actual terrain response curves to
     * Minecraft's VanillaTerrainParametersCreator instead of hand-inventing a
     * height wave. This keeps worldgen detached while preserving vanilla terrain
     * morphology much more faithfully.
     */
    private record TerrainClimate(float continents, float erosion, float ridges, float ridgesFolded) { }

    private enum TerrainAxis implements ToFloatFunction<TerrainClimate> {
        CONTINENTS { public float apply(TerrainClimate p) { return p.continents(); } },
        EROSION { public float apply(TerrainClimate p) { return p.erosion(); } },
        RIDGES { public float apply(TerrainClimate p) { return p.ridges(); } },
        RIDGES_FOLDED { public float apply(TerrainClimate p) { return p.ridgesFolded(); } };

        @Override public float min() { return -2.0F; }
        @Override public float max() { return 2.0F; }
    }

    private static final Spline<TerrainClimate, TerrainAxis> VANILLA_OFFSET =
            VanillaTerrainParametersCreator.createOffsetSpline(
                    TerrainAxis.CONTINENTS, TerrainAxis.EROSION, TerrainAxis.RIDGES_FOLDED, false);
    private static final Spline<TerrainClimate, TerrainAxis> VANILLA_FACTOR =
            VanillaTerrainParametersCreator.createFactorSpline(
                    TerrainAxis.CONTINENTS, TerrainAxis.EROSION, TerrainAxis.RIDGES, TerrainAxis.RIDGES_FOLDED, false);
    private static final Spline<TerrainClimate, TerrainAxis> VANILLA_JAGGEDNESS =
            VanillaTerrainParametersCreator.createJaggednessSpline(
                    TerrainAxis.CONTINENTS, TerrainAxis.EROSION, TerrainAxis.RIDGES, TerrainAxis.RIDGES_FOLDED, false);

    private record TerrainSample(float continents, float erosion, float ridges, float peaksValleys,
                                 float offset, float factor, float jaggedness, float jaggedNoise) { }

    /**
     * Presentation archetypes are deliberately scene-oriented. Minecraft's biome
     * JSON tells Koil what climate/features belong to a biome, but it does not
     * contain the entire chunk-generator/router state needed to reproduce a
     * literal server chunk from the title screen. These archetypes therefore
     * fabricate a readable side-view scene while still sourcing blocks/features
     * from the actual biome/configured-feature resources. Unknown modded biomes
     * are classified from climate and their feature graph rather than namespace.
     */
    private enum BiomeSceneStyle {
        PLAINS,
        WOODLAND,
        JUNGLE,
        TAIGA,
        SAVANNA,
        ARID_DUNES,
        BADLANDS_MESA,
        SWAMP,
        MOUNTAIN,
        BEACH,
        RIVER,
        OCEAN,
        DEEP_OCEAN,
        COLD,
        MUSHROOM_ISLAND,
        LUSH_CAVE,
        DRIPSTONE_CAVE,
        DEEP_DARK,
        CAVE_GENERIC,
        NETHER_CAVERN,
        END_ISLANDS,
        GENERIC
    }

    public record Request(SpriteWorldgenCatalog.Entry entry, long seed, List<Integer> layers,
                          int centerX, int surfaceY, int width, boolean replaceExisting, int editPlane) {
        public Request(SpriteWorldgenCatalog.Entry entry, long seed, List<Integer> layers,
                       int centerX, int surfaceY, int width, boolean replaceExisting) {
            this(entry, seed, layers, centerX, surfaceY, width, replaceExisting, defaultEditPlane(layers));
        }

        public Request {
            layers = layers == null || layers.isEmpty() ? List.of(0) : List.copyOf(layers);
            width = Math.max(8, Math.min(384, width));
        }

        private static int defaultEditPlane(List<Integer> layers) {
            if (layers == null || layers.isEmpty()) return 0;
            return layers.get(layers.size() / 2);
        }
    }

    public record Result(int blocks, int fluids, int pieces, String message, SceneCellPos focus) {
        public Result(int blocks, int fluids, int pieces, String message) {
            this(blocks, fluids, pieces, message, null);
        }
    }

    public static Result generate(SpriteEngine engine, Request request) {
        if (engine == null || request == null || request.entry() == null) return new Result(0, 0, 0, "No worldgen selection");
        SpriteWorldgenCatalog.Entry entry = request.entry();
        Result result = switch (entry.kind()) {
            case STRUCTURE_TEMPLATE -> generateTemplate(engine, request, entry);
            case TEMPLATE_POOL -> generatePool(engine, request, entry);
            case PROCESSOR_LIST -> new Result(0, 0, 0,
                    "Processor lists modify structure templates; choose a structure/template to project it");
            case STRUCTURE -> generateStructure(engine, request, entry);
            case STRUCTURE_SET -> generateStructureSet(engine, request, entry);
            case CONFIGURED_FEATURE, PLACED_FEATURE -> generateFeature(engine, request, entry);
            case CONFIGURED_CARVER -> applyCarver(engine, request, entry);
            case BIOME -> generateBiome(engine, request, entry);
            case WORLD_PRESET -> generateWorldPreset(engine, request, entry);
            case FLAT_LEVEL_GENERATOR_PRESET -> generateFlatPreset(engine, request, entry);
            case MULTI_NOISE_PARAMETER_LIST -> generateBiomeSource(engine, request, entry);
            case DIMENSION, DIMENSION_TYPE, NOISE_SETTINGS, DENSITY_FUNCTION, NOISE ->
                    generateTerrainResource(engine, request, entry);
        };
        finish(engine.scene());
        return result;
    }

    private static Result generateTemplate(SpriteEngine engine, Request request, SpriteWorldgenCatalog.Entry entry) {
        return SpriteJigsawResolver.generateTemplate(engine, request, entry);
    }

    private static Result generatePool(SpriteEngine engine, Request request, SpriteWorldgenCatalog.Entry entry) {
        if (entry == null) return new Result(0, 0, 0, "Missing template pool");
        return SpriteJigsawResolver.generateFromPool(engine, request, entry.id(), 7);
    }

    private static Result generateStructure(SpriteEngine engine, Request request, SpriteWorldgenCatalog.Entry entry) {
        JsonObject json = SpriteWorldgenCatalog.readJson(entry).orElse(null);
        // Explicit editor structure generation is an authoring/test action, not
        // natural world placement. Do not reject the user's selected structure
        // because the current preview biome is different. Automatic biome
        // structures are filtered by structure-set + Structure.Config biome
        // selectors before they ever reach this method.
        if (json != null) {
            Identifier startPool = identifier(jsonString(json, "start_pool", ""));
            if (startPool != null) {
                int depth = Math.max(0, Math.min(12, jsonInt(json, "size", 7)));
                return SpriteJigsawResolver.generateFromPool(engine, request, startPool, depth);
            }
        }
        // Hard-coded structures often still ship related NBT pieces. Prefer the
        // real templates by path similarity. If no real representation is
        // available, report that instead of fabricating unrelated terrain.
        String needle = entry.id().getPath().toLowerCase(Locale.ROOT);
        String shortNeedle = needle.replace("_", "");
        List<SpriteWorldgenCatalog.Entry> candidates = new ArrayList<>();
        for (SpriteWorldgenCatalog.Entry template : SpriteWorldgenCatalog.search(SpriteWorldgenCatalog.Kind.STRUCTURE_TEMPLATE, "")) {
            String path = template.id().getPath().toLowerCase(Locale.ROOT);
            String flat = path.replace("/", "").replace("_", "");
            if (path.contains(needle) || flat.contains(shortNeedle) || relatedStructure(needle, path)) candidates.add(template);
        }
        if (!candidates.isEmpty()) {
            SpriteWorldgenCatalog.Entry chosen = candidates.get(Math.floorMod((int) request.seed(), candidates.size()));
            return generateTemplate(engine, new Request(chosen, request.seed(), request.layers(), request.centerX(),
                    request.surfaceY(), request.width(), request.replaceExisting(), request.editPlane()), chosen);
        }
        return new Result(0, 0, 0, "No detached template/jigsaw projection available for structure " + entry.id());
    }


    private static Result generateStructureSet(SpriteEngine engine, Request request, SpriteWorldgenCatalog.Entry entry) {
        JsonObject json = SpriteWorldgenCatalog.readJson(entry).orElse(null);
        if (json == null) return new Result(0, 0, 0, "Could not read structure set " + entry.id());
        // Manual structure-set generation is a playground test operation. Pick
        // any projectable member; natural biome generation uses the same helper
        // with a concrete biome id and therefore still obeys the set/structure
        // biome selectors.
        SpriteWorldgenCatalog.Entry selected = chooseBiomeStructureFromSet(
                json.get("structures"), null, request.seed());
        if (selected == null) {
            return new Result(0, 0, 0, "Structure set contains no projectable structures");
        }
        return generateStructure(engine, new Request(selected, request.seed(), request.layers(), request.centerX(),
                request.surfaceY(), request.width(), request.replaceExisting(), request.editPlane()), selected);
    }



    private static Result generateFlatPreset(SpriteEngine engine, Request request, SpriteWorldgenCatalog.Entry entry) {
        JsonObject root = SpriteWorldgenCatalog.readJson(entry).orElse(null);
        if (root == null) return new Result(0, 0, 0, "Could not read flat preset " + entry.id());
        JsonObject settings = object(root, "settings");
        if (settings == null) settings = root;
        return generateFlatSettings(engine, request, settings, "native flat preset " + entry.id());
    }

    private static Result generateFlatSettings(SpriteEngine engine, Request request, JsonObject settings, String source) {
        if (settings == null) return new Result(0, 0, 0, "Missing flat generator settings for " + source);
        JsonElement layersElement = settings.get("layers");
        if (layersElement == null || !layersElement.isJsonArray()) {
            return new Result(0, 0, 0, "Flat generator has no layer definition: " + source);
        }
        Identifier biome = identifier(jsonString(settings, "biome", ""));
        if (biome != null) engine.scene().environment().setBiomeId(biome);
        int minX = request.centerX() - request.width() / 2;
        int placed = 0;
        for (int depth : request.layers()) {
            int y = request.surfaceY();
            for (JsonElement layerElement : layersElement.getAsJsonArray()) {
                if (!layerElement.isJsonObject()) continue;
                JsonObject layer = layerElement.getAsJsonObject();
                Identifier blockId = identifier(jsonString(layer, "block", ""));
                if (blockId == null || !Registries.BLOCK.containsId(blockId)) continue;
                BlockState state = Registries.BLOCK.get(blockId).getDefaultState();
                int height = Math.max(1, Math.min(384, jsonInt(layer, "height", 1)));
                for (int dy = 0; dy < height; dy++) {
                    for (int x = minX; x < minX + request.width(); x++) {
                        if (set(engine.scene(), new SceneCellPos(x, y + dy, depth), state, request.replaceExisting())) placed++;
                    }
                }
                y += height;
            }
        }
        return new Result(placed, 0, request.layers().size(), "Projected " + source);
    }

    private static Result generateBiomeSource(SpriteEngine engine, Request request, SpriteWorldgenCatalog.Entry entry) {
        JsonObject root = SpriteWorldgenCatalog.readJson(entry).orElse(null);
        if (root == null) return generateTerrainResource(engine, request, entry);
        List<Identifier> ids = new ArrayList<>();
        collectIdentifierStrings(root, ids);
        List<SpriteWorldgenCatalog.Entry> biomes = new ArrayList<>();
        for (Identifier id : ids) {
            SpriteWorldgenCatalog.find(SpriteWorldgenCatalog.Kind.BIOME, id).ifPresent(biome -> {
                if (!biomes.contains(biome)) biomes.add(biome);
            });
        }
        if (!biomes.isEmpty()) {
            SpriteWorldgenCatalog.Entry chosen = biomes.get(Math.floorMod((int) request.seed(), biomes.size()));
            return generateBiome(engine, new Request(chosen, request.seed(), request.layers(), request.centerX(),
                    request.surfaceY(), request.width(), request.replaceExisting(), request.editPlane()), chosen);
        }
        return generateTerrainResource(engine, request, entry);
    }

    private static Result generateWorldPreset(SpriteEngine engine, Request request, SpriteWorldgenCatalog.Entry entry) {
        JsonObject json = SpriteWorldgenCatalog.readJson(entry).orElse(null);
        if (json == null) return generateTerrainResource(engine, request, entry);
        JsonObject dimensions = object(json, "dimensions");
        JsonObject dimension = null;
        if (dimensions != null) {
            if (dimensions.has("minecraft:overworld") && dimensions.get("minecraft:overworld").isJsonObject()) {
                dimension = dimensions.getAsJsonObject("minecraft:overworld");
            } else {
                for (var value : dimensions.entrySet()) if (value.getValue().isJsonObject()) { dimension = value.getValue().getAsJsonObject(); break; }
            }
        }
        JsonObject generator = object(dimension, "generator");
        String generatorType = jsonString(generator, "type", "").toLowerCase(Locale.ROOT);
        if (generator != null && generatorType.contains("flat") && generator.has("settings") && generator.get("settings").isJsonObject()) {
            return generateFlatSettings(engine, request, generator.getAsJsonObject("settings"),
                    "flat world preset " + entry.id());
        }
        Identifier settings = generator == null ? null : identifier(jsonString(generator, "settings", ""));
        if (settings != null) {
            SpriteWorldgenCatalog.Entry noise = SpriteWorldgenCatalog.find(SpriteWorldgenCatalog.Kind.NOISE_SETTINGS, settings).orElse(null);
            if (noise != null) return generateTerrainProfile(engine,
                    new Request(noise, request.seed(), request.layers(), request.centerX(), request.surfaceY(), request.width(), request.replaceExisting()),
                    settings, SpriteWorldgenCatalog.readJson(noise).orElse(null));
        }
        return generateTerrainProfile(engine, request, entry.id(), json);
    }

    private static Result applyCarver(SpriteEngine engine, Request request, SpriteWorldgenCatalog.Entry entry) {
        JsonObject json = SpriteWorldgenCatalog.readJson(entry).orElse(null);
        String type = jsonString(json, "type", entry.summary()).toLowerCase(Locale.ROOT);
        boolean canyon = type.contains("canyon");
        Scene scene = engine.scene();
        int minX = request.centerX() - request.width() / 2;
        int maxX = minX + request.width() - 1;
        int removed = 0;
        Set<Integer> layerSet = new LinkedHashSet<>(request.layers());

        /*
         * A configured carver is a 3D volume operation. Koil is projecting a
         * single readable XY slice, so it must never run a cell-random erosion
         * pass that can consume the terrain body. Build a coherent candidate
         * field, preserve a substantial surface shell, and cap removals per
         * depth layer. No nested candidate class is used here: keeping this
         * method self-contained also avoids stale inner-class failures in dev
         * hot/incremental runtimes.
         */
        for (int layer : layerSet) {
            Map<SceneCellPos, Float> candidateScores = new HashMap<>();
            int eligible = 0;

            for (var block : scene.blocks().entries()) {
                if (block == null || block.cell() == null || block.cell().isAir()) continue;
                SceneCellPos pos = block.position();
                if (pos.depth() != layer || pos.x() < minX || pos.x() > maxX) continue;
                if (!generatedCell(scene, pos)) continue;

                String role = block.cell().runtimeData().get("generated_role");
                if (!"stone".equals(role) && !"deep_stone".equals(role)) continue;

                int surfaceAirY = findTerrainSurfaceY(scene, pos.x(), layer, request.surfaceY());
                int depthBelowSurface = (surfaceAirY - 1) - pos.y();
                if (depthBelowSurface < 9) continue;
                eligible++;

                int sourceZ = parseInt(block.cell().runtimeData().get("source_slice_z"), layer);
                float broad = Math.abs(fbm3D(
                        pos.x() * (canyon ? 0.028F : 0.046F),
                        pos.y() * (canyon ? 0.024F : 0.054F),
                        sourceZ * (canyon ? 0.028F : 0.046F),
                        request.seed() ^ 0x94D049BB133111EBL,
                        canyon ? 3 : 4));
                float tunnel = Math.abs(fbm3D(
                        pos.x() * 0.020F,
                        pos.y() * 0.033F,
                        sourceZ * 0.020F,
                        request.seed() ^ 0x369DEA0F31A53F85L,
                        3));

                float score = canyon
                        ? broad * 0.86F + (1.0F - tunnel) * 0.14F
                        : broad * 0.76F + (1.0F - tunnel) * 0.24F;
                float threshold = canyon ? 0.82F : 0.85F;
                if (score >= threshold) candidateScores.put(pos, score);
            }

            if (eligible == 0 || candidateScores.isEmpty()) continue;

            // Detached biome previews should retain a strong readable mass.
            // Caves remain visible, but no configured carver may remove more
            // than a tiny fraction of one projected slice.
            int budget = Math.max(1, Math.round(eligible * (canyon ? 0.025F : 0.018F)));
            List<SceneCellPos> candidates = new ArrayList<>(candidateScores.keySet());
            candidates.sort((a, b) -> Float.compare(
                    candidateScores.getOrDefault(b, 0.0F),
                    candidateScores.getOrDefault(a, 0.0F)));

            int layerRemoved = 0;
            for (SceneCellPos pos : candidates) {
                if (layerRemoved >= budget) break;
                if (!scene.blocks().occupied(pos) || !generatedCell(scene, pos)) continue;
                scene.blocks().remove(pos);
                if (!scene.fluids().getFluidState(pos).isEmpty()) scene.fluids().remove(pos);
                layerRemoved++;
                removed++;
            }
        }

        return new Result(0, 0, 1,
                "Applied conservative native carver profile " + entry.id() + " | removed " + removed + " cells");
    }

    private enum DimensionFlavor {
        OVERWORLD, NETHER, END, UNKNOWN;

        static DimensionFlavor forBiome(Identifier id) {
            if (id == null) return UNKNOWN;
            String path = id.getPath().toLowerCase(Locale.ROOT);
            if (path.contains("nether") || path.contains("crimson_forest") || path.contains("warped_forest")
                    || path.contains("soul_sand") || path.contains("basalt_delta")) return NETHER;
            if (path.equals("the_end") || path.contains("end_highlands") || path.contains("end_midlands")
                    || path.contains("end_barrens") || path.contains("small_end_islands")) return END;
            return "minecraft".equals(id.getNamespace()) ? OVERWORLD : UNKNOWN;
        }
    }

    private static DimensionFlavor dimensionForSceneStyle(BiomeSceneStyle style, Identifier id) {
        if (style == BiomeSceneStyle.NETHER_CAVERN) return DimensionFlavor.NETHER;
        if (style == BiomeSceneStyle.END_ISLANDS) return DimensionFlavor.END;
        return DimensionFlavor.forBiome(id);
    }

    private static BiomeSceneStyle resolveBiomeSceneStyle(Identifier id, JsonObject biomeJson,
                                                           float temperature, float downfall) {
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        String signature = biomeFeatureSignature(biomeJson);
        String combined = path + " " + signature;

        if (combined.contains("deep_dark") || combined.contains("sculk_patch")
                || combined.contains("sculk_vein") || combined.contains("sculk_sensor")
                || combined.contains("sculk_shrieker")) {
            return BiomeSceneStyle.DEEP_DARK;
        }
        if (combined.contains("lush_cave") || combined.contains("moss_patch") || combined.contains("cave_vine")) {
            return BiomeSceneStyle.LUSH_CAVE;
        }
        if (combined.contains("dripstone_cave") || combined.contains("pointed_dripstone") || combined.contains("large_dripstone")) {
            return BiomeSceneStyle.DRIPSTONE_CAVE;
        }
        if (path.contains("cave") || path.contains("underground") || combined.contains("cave_vegetation")) {
            return BiomeSceneStyle.CAVE_GENERIC;
        }
        if (combined.contains("crimson") || combined.contains("warped") || combined.contains("soul_sand")
                || combined.contains("basalt_delta") || combined.contains("glowstone") || path.contains("nether")) {
            return BiomeSceneStyle.NETHER_CAVERN;
        }
        if (combined.contains("chorus") || path.contains("end_highlands") || path.contains("end_midlands")
                || path.contains("end_barrens") || path.contains("small_end_islands") || path.equals("the_end")) {
            return BiomeSceneStyle.END_ISLANDS;
        }
        if (path.contains("deep_ocean")) return BiomeSceneStyle.DEEP_OCEAN;
        if (path.contains("ocean") || combined.contains("kelp") || combined.contains("seagrass") || combined.contains("coral")) {
            return BiomeSceneStyle.OCEAN;
        }
        if (path.contains("river")) return BiomeSceneStyle.RIVER;
        if (path.contains("beach")) return BiomeSceneStyle.BEACH;
        if (path.contains("badlands") || combined.contains("terracotta")) return BiomeSceneStyle.BADLANDS_MESA;
        if (path.contains("peak") || path.contains("mountain") || path.contains("windswept")
                || path.contains("grove") || path.contains("slope")) return BiomeSceneStyle.MOUNTAIN;
        if (path.contains("swamp") || path.contains("mangrove") || combined.contains("mangrove")) return BiomeSceneStyle.SWAMP;
        if (path.contains("mushroom") || combined.contains("huge_red_mushroom") || combined.contains("huge_brown_mushroom")) {
            return BiomeSceneStyle.MUSHROOM_ISLAND;
        }
        if (path.contains("jungle") || combined.contains("bamboo") || combined.contains("jungle_tree")) return BiomeSceneStyle.JUNGLE;
        if (path.contains("taiga") || combined.contains("pine") || combined.contains("spruce")) return BiomeSceneStyle.TAIGA;
        if (path.contains("savanna") || combined.contains("acacia")) return BiomeSceneStyle.SAVANNA;
        if (path.contains("desert") || combined.contains("cactus") || combined.contains("desert_well")
                || (temperature > 1.05F && downfall < 0.22F)) return BiomeSceneStyle.ARID_DUNES;
        if (path.contains("snow") || path.contains("frozen") || path.contains("ice") || temperature < 0.18F) {
            return BiomeSceneStyle.COLD;
        }
        if (path.contains("forest") || combined.contains("tree") || downfall > 0.62F) return BiomeSceneStyle.WOODLAND;
        if (path.contains("plains") || path.contains("meadow")) return BiomeSceneStyle.PLAINS;
        return downfall > 0.48F ? BiomeSceneStyle.WOODLAND : BiomeSceneStyle.GENERIC;
    }

    private static String biomeFeatureSignature(JsonObject biomeJson) {
        if (biomeJson == null || !biomeJson.has("features")) return "";
        StringBuilder out = new StringBuilder();
        for (BiomeFeatureRef ref : collectBiomeFeatureRefs(biomeJson.get("features"))) {
            if (ref == null || ref.id() == null) continue;
            out.append(' ').append(ref.id().getPath().toLowerCase(Locale.ROOT));
            SpriteWorldgenCatalog.Entry entry = SpriteWorldgenCatalog
                    .find(SpriteWorldgenCatalog.Kind.PLACED_FEATURE, ref.id())
                    .orElseGet(() -> SpriteWorldgenCatalog.find(SpriteWorldgenCatalog.Kind.CONFIGURED_FEATURE, ref.id()).orElse(null));
            if (entry == null) continue;
            JsonObject root = SpriteWorldgenCatalog.readJson(entry).orElse(null);
            if (root == null) continue;
            if (entry.kind() == SpriteWorldgenCatalog.Kind.PLACED_FEATURE) {
                Identifier configured = identifier(jsonString(root, "feature", ""));
                SpriteWorldgenCatalog.Entry configuredEntry = configured == null ? null
                        : SpriteWorldgenCatalog.find(SpriteWorldgenCatalog.Kind.CONFIGURED_FEATURE, configured).orElse(null);
                if (configuredEntry != null) root = SpriteWorldgenCatalog.readJson(configuredEntry).orElse(root);
            }
            out.append(' ').append(compactType(jsonString(root, "type", "").toLowerCase(Locale.ROOT)));
            if (out.length() > 4096) break;
        }
        return out.toString();
    }

    private static BiomeSliceProfile adaptProfileForStyle(BiomeSliceProfile base, BiomeSceneStyle style,
                                                           float temperature) {
        if (base == null) return null;
        return switch (style) {
            case NETHER_CAVERN -> new BiomeSliceProfile(base.baseOffset(), Math.max(9.0F, base.amplitude()),
                    base.broadFrequency(), base.detailFrequency(), base.ridgeStrength(), base.caveStrength(),
                    -8, base.surface(), base.filler(), base.stone(), Fluids.LAVA);
            case END_ISLANDS -> new BiomeSliceProfile(base.baseOffset(), base.amplitude(), base.broadFrequency(),
                    base.detailFrequency(), base.ridgeStrength(), base.caveStrength(), -64,
                    Blocks.END_STONE.getDefaultState(), Blocks.END_STONE.getDefaultState(),
                    Blocks.END_STONE.getDefaultState(), Fluids.EMPTY);
            case ARID_DUNES, BEACH -> new BiomeSliceProfile(base.baseOffset(), base.amplitude(), base.broadFrequency(),
                    base.detailFrequency(), base.ridgeStrength(), base.caveStrength(), base.seaOffset(),
                    Blocks.SAND.getDefaultState(), Blocks.SANDSTONE.getDefaultState(), Blocks.STONE.getDefaultState(), Fluids.WATER);
            case BADLANDS_MESA -> new BiomeSliceProfile(base.baseOffset(), base.amplitude(), base.broadFrequency(),
                    base.detailFrequency(), base.ridgeStrength(), base.caveStrength(), -9,
                    Blocks.RED_SAND.getDefaultState(), Blocks.TERRACOTTA.getDefaultState(), Blocks.STONE.getDefaultState(), Fluids.WATER);
            case OCEAN, DEEP_OCEAN -> new BiomeSliceProfile(base.baseOffset(), base.amplitude(), base.broadFrequency(),
                    base.detailFrequency(), base.ridgeStrength(), base.caveStrength(), 0,
                    Blocks.GRAVEL.getDefaultState(), Blocks.GRAVEL.getDefaultState(), Blocks.STONE.getDefaultState(), Fluids.WATER);
            case COLD -> new BiomeSliceProfile(base.baseOffset(), base.amplitude(), base.broadFrequency(),
                    base.detailFrequency(), base.ridgeStrength(), base.caveStrength(), base.seaOffset(),
                    Blocks.SNOW_BLOCK.getDefaultState(), Blocks.DIRT.getDefaultState(), Blocks.STONE.getDefaultState(), Fluids.WATER);
            default -> base;
        };
    }

    private static BiomeSliceProfile adaptModdedProfileFromFeatures(BiomeSliceProfile base, Identifier biomeId,
                                                                      JsonObject biomeJson) {
        if (base == null || biomeId == null || "minecraft".equals(biomeId.getNamespace())
                || biomeJson == null || !biomeJson.has("features")) return base;
        for (BiomeFeatureRef ref : collectBiomeFeatureRefs(biomeJson.get("features"))) {
            SpriteWorldgenCatalog.Entry entry = SpriteWorldgenCatalog
                    .find(SpriteWorldgenCatalog.Kind.PLACED_FEATURE, ref.id())
                    .orElseGet(() -> SpriteWorldgenCatalog.find(SpriteWorldgenCatalog.Kind.CONFIGURED_FEATURE, ref.id()).orElse(null));
            if (entry == null) continue;
            JsonObject root = SpriteWorldgenCatalog.readJson(entry).orElse(null);
            if (root == null) continue;
            if (entry.kind() == SpriteWorldgenCatalog.Kind.PLACED_FEATURE) {
                Identifier configured = identifier(jsonString(root, "feature", ""));
                SpriteWorldgenCatalog.Entry configuredEntry = configured == null ? null
                        : SpriteWorldgenCatalog.find(SpriteWorldgenCatalog.Kind.CONFIGURED_FEATURE, configured).orElse(null);
                if (configuredEntry != null) root = SpriteWorldgenCatalog.readJson(configuredEntry).orElse(root);
            }
            String type = compactType(jsonString(root, "type", "").toLowerCase(Locale.ROOT));
            if (!type.contains("vegetation_patch")) continue;
            JsonObject config = object(root, "config");
            if (config == null) continue;
            BlockState ground = firstProviderState(config.get("ground_state"));
            if (ground == null || ground.isAir()) continue;
            return new BiomeSliceProfile(base.baseOffset(), base.amplitude(), base.broadFrequency(),
                    base.detailFrequency(), base.ridgeStrength(), base.caveStrength(), base.seaOffset(),
                    ground, base.filler(), base.stone(), base.fluid());
        }
        return base;
    }

    private static int scenicFeatureLayer(List<Integer> layers, String featureType, long seed) {
        if (layers == null || layers.isEmpty()) return 0;
        List<Integer> ordered = new ArrayList<>(layers);
        ordered.sort(Integer::compareTo);
        String type = featureType == null ? "" : featureType.toLowerCase(Locale.ROOT);
        boolean underground = type.contains("ore") || type.contains("geode") || type.contains("spring")
                || type.contains("multiface_growth") || type.contains("monster_room") || type.contains("lake");
        if (underground) {
            return ordered.get(Math.floorMod((int) (seed ^ (seed >>> 32)), ordered.size()));
        }

        // The compositor treats the largest selected Z as the foreground. Put
        // most visible vegetation there, with a smaller midground contribution
        // from the next two slices for a layered scene silhouette.
        int front = ordered.size() - 1;
        float roll = hash01(seed ^ 0xA24BAED4963EE407L);
        if (roll < 0.64F || ordered.size() == 1) return ordered.get(front);
        if (roll < 0.88F || ordered.size() == 2) return ordered.get(Math.max(0, front - 1));
        return ordered.get(Math.max(0, front - 2));
    }

    private record FeatureContext(DimensionFlavor dimension, Identifier biomeId, int generationStep, int sourceZ) {
        static FeatureContext generic() { return new FeatureContext(DimensionFlavor.UNKNOWN, null, -1, Integer.MIN_VALUE); }
    }

    private static Result generateFeature(SpriteEngine engine, Request request, SpriteWorldgenCatalog.Entry entry) {
        return generateFeature(engine, request, entry, FeatureContext.generic());
    }

    private static Result generateFeature(SpriteEngine engine, Request request, SpriteWorldgenCatalog.Entry entry,
                                          FeatureContext context) {
        JsonObject sourceRoot = SpriteWorldgenCatalog.readJson(entry).orElse(null);
        if (sourceRoot == null) return new Result(0, 0, 0, "Could not read feature " + entry.id());

        JsonObject placedRoot = entry.kind() == SpriteWorldgenCatalog.Kind.PLACED_FEATURE ? sourceRoot : null;
        JsonObject root = sourceRoot;
        if (placedRoot != null) {
            Identifier configured = identifier(jsonString(placedRoot, "feature", ""));
            if (configured != null) {
                SpriteWorldgenCatalog.Entry configuredEntry = SpriteWorldgenCatalog.find(SpriteWorldgenCatalog.Kind.CONFIGURED_FEATURE, configured).orElse(null);
                if (configuredEntry != null) root = SpriteWorldgenCatalog.readJson(configuredEntry).orElse(root);
            }
        }

        String type = jsonString(root, "type", entry.summary()).toLowerCase(Locale.ROOT);
        String compact = compactType(type);
        JsonObject config = object(root, "config");
        if (config == null) config = new JsonObject();

        int instances = placedFeatureInstances(placedRoot, request.width(), request.seed());
        if (placedRoot != null && context.biomeId() != null && context.sourceZ() != Integer.MIN_VALUE
                && hasPlacementModifier(placedRoot, "in_square")) {
            instances = sliceIntersectingInstances(instances, type, context.sourceZ(), request.seed());
        }
        if (context.biomeId() != null) {
            instances = scenicBiomeFeatureBudget(instances, type, request.width());
        }
        if (instances <= 0) {
            return new Result(0, 0, 0, "Placement modifiers produced no attempts for " + entry.id());
        }

        Scene scene = engine.scene();
        int placed = 0;
        int fluids = 0;
        int actualPieces = 0;
        boolean supported = true;
        int spacing = Math.max(4, request.width() / Math.max(1, instances));

        for (int i = 0; i < instances; i++) {
            long salt = request.seed() ^ (i * 0x9E3779B97F4A7C15L);
            int minX = request.centerX() - request.width() / 2;
            int x = minX + Math.floorMod((int) (salt ^ (salt >>> 32)), Math.max(1, request.width()));
            int layer = context.biomeId() != null
                    ? scenicFeatureLayer(request.layers(), type, salt)
                    : request.layers().get(Math.floorMod((int) (salt ^ (salt >>> 32)), request.layers().size()));
            int detectedSurface = findFeatureSurfaceY(scene, x, layer, request.surfaceY(), context.dimension());
            boolean surfaceBound = type.contains("huge_fungus") || type.endsWith(":tree") || compact.equals("tree")
                    || type.contains("simple_block") || type.contains("random_patch") || type.contains("flower")
                    || type.contains("nether_forest_vegetation") || type.contains("block_pile")
                    || type.contains("vegetation_patch") || type.contains("chorus_plant") || type.contains("bamboo")
                    || type.endsWith(":vines") || type.contains("weeping_vines") || type.contains("twisting_vines");
            int y = surfaceBound ? detectedSurface
                    : placementY(scene, x, layer, detectedSurface, placedRoot, entry.id(),
                    context.generationStep(), salt, request.surfaceY(), context.dimension());
            if (context.dimension() == DimensionFlavor.NETHER && type.contains("weeping_vines")) {
                int ceilingAir = findCavernCeilingAirY(scene, x, layer, detectedSurface, request.surfaceY() + 52);
                if (ceilingAir != Integer.MIN_VALUE) y = ceilingAir;
            }
            if (hasPlacementModifier(placedRoot, "surface_water_depth_filter")
                    && !scene.fluids().getFluidState(new SceneCellPos(x, detectedSurface, layer)).isEmpty()) {
                continue;
            }

            if (type.contains("random_selector") || type.contains("simple_random_selector")
                    || type.contains("random_boolean_selector")) {
                Identifier nestedId = selectNestedFeature(config, salt);
                SpriteWorldgenCatalog.Entry nested = resolveFeatureEntry(nestedId);
                if (nested == null) { supported = false; break; }
                Result nestedResult = generateFeature(engine, new Request(nested, salt, List.of(layer),
                                x + 2, y, 8, request.replaceExisting()), nested, context);
                placed += nestedResult.blocks();
                fluids += nestedResult.fluids();
                actualPieces += Math.max(1, nestedResult.pieces());
                if (nestedResult.message().startsWith("Skipped unsupported")) supported = false;
                continue;
            } else if (type.contains("huge_fungus")) {
                BlockState stem = blockStateField(config, "stem_state");
                BlockState hat = blockStateField(config, "hat_state");
                BlockState decor = blockStateField(config, "decor_state");
                if (stem == null || hat == null) { supported = false; break; }
                placed += placeHugeFungus(scene, x, y, layer, stem, hat, decor, request.replaceExisting(), salt);
            } else if (type.endsWith(":tree") || compact.equals("tree")) {
                BlockState trunk = firstProviderState(config.get("trunk_provider"));
                BlockState foliage = firstProviderState(config.get("foliage_provider"));
                if (trunk == null || foliage == null) { supported = false; break; }
                placed += placeConfiguredTree(scene, x, y, layer, trunk, foliage,
                        request.replaceExisting(), salt, config);
            } else if (type.contains("ore")) {
                placed += placeOre(scene, x, y, layer, config, context.dimension(), request.replaceExisting(), salt);
            } else if (type.contains("geode")) {
                placed += placeGeode(scene, x, detectedSurface - 10, layer, request.replaceExisting());
            } else if (type.contains("underwater_magma")) {
                placed += placeUnderwaterMagma(scene, x, layer, request.replaceExisting(), salt, request.surfaceY());
            } else if (type.contains("block_column")) {
                placed += placeBlockColumnFeature(scene, x, detectedSurface, layer, config, request.replaceExisting(), salt);
            } else if (type.contains("kelp")) {
                placed += placeAquaticColumn(scene, x, layer, Blocks.KELP_PLANT.getDefaultState(), Blocks.KELP.getDefaultState(),
                        request.replaceExisting(), salt, request.surfaceY());
            } else if (type.contains("seagrass")) {
                placed += placeAquaticPatch(scene, x, layer, Blocks.SEAGRASS.getDefaultState(), request.replaceExisting(), salt, request.surfaceY());
            } else if (type.contains("sea_pickle")) {
                placed += placeAquaticPatch(scene, x, layer, Blocks.SEA_PICKLE.getDefaultState(), request.replaceExisting(), salt, request.surfaceY());
            } else if (type.contains("forest_rock")) {
                BlockState rock = blockStateField(config, "state");
                if (rock == null) rock = Blocks.MOSSY_COBBLESTONE.getDefaultState();
                placed += placeRock(scene, x, detectedSurface, layer, rock, request.replaceExisting(), salt);
            } else if (type.contains("huge_brown_mushroom") || type.contains("huge_red_mushroom")) {
                BlockState stem = Blocks.MUSHROOM_STEM.getDefaultState();
                BlockState cap = type.contains("brown") ? Blocks.BROWN_MUSHROOM_BLOCK.getDefaultState() : Blocks.RED_MUSHROOM_BLOCK.getDefaultState();
                placed += placeTreeLikeExact(scene, x, detectedSurface, layer, stem, cap, request.replaceExisting(), salt);
            } else if (type.contains("iceberg") || type.contains("ice_spike")) {
                placed += placeSpike(scene, x, y, layer, Blocks.PACKED_ICE.getDefaultState(), request.replaceExisting(),
                        5 + Math.floorMod((int) salt, 9));
            } else if (type.contains("basalt_columns")) {
                BlockState basalt = blockStateField(config, "state");
                if (basalt == null) basalt = Blocks.BASALT.getDefaultState();
                placed += placeSpike(scene, x, y, layer, basalt, request.replaceExisting(),
                        3 + Math.floorMod((int) salt, 7));
            } else if (type.contains("delta_feature")) {
                BlockState contents = blockStateField(config, "contents");
                BlockState rim = blockStateField(config, "rim");
                if (contents == null) contents = Blocks.BASALT.getDefaultState();
                if (rim == null) rim = Blocks.BLACKSTONE.getDefaultState();
                placed += placeDelta(scene, x, y, layer, contents, rim, request.replaceExisting(), salt);
            } else if (type.contains("glowstone_blob")) {
                int ceilingAir = findCavernCeilingAirY(scene, x, layer, detectedSurface, request.surfaceY() + 52);
                if (ceilingAir != Integer.MIN_VALUE) {
                    placed += placeGlowstone(scene, x, ceilingAir, layer, request.replaceExisting(), salt);
                }
            } else if (type.contains("spring")) {
                Fluid fluid = configuredFeatureFluid(config);
                if (fluid == Fluids.EMPTY) { supported = false; break; }
                fluids += placeEmbeddedSpring(scene, x, y, layer, fluid, request.replaceExisting());
            } else if (type.contains("lake")) {
                Fluid fluid = configuredFeatureFluid(config);
                if (fluid == Fluids.EMPTY) { supported = false; break; }
                fluids += placeLakeBasin(scene, x, y, detectedSurface, layer, fluid, request.replaceExisting(), salt);
            } else if (type.endsWith(":bamboo") || compact.equals("bamboo")) {
                float podzolChance = jsonFloat(config, "probability", 0.0F);
                placed += placeBambooPatch(scene, x, detectedSurface, layer, request.replaceExisting(), salt, podzolChance);
            } else if (type.endsWith(":vines") || compact.equals("vines")) {
                placed += placeVines(scene, x, detectedSurface, layer, request.replaceExisting(), salt);
            } else if (type.contains("multiface_growth")) {
                Identifier blockId = identifier(jsonString(config, "block", ""));
                BlockState growth = blockId != null && Registries.BLOCK.containsId(blockId)
                        ? Registries.BLOCK.get(blockId).getDefaultState() : null;
                if (growth == null || growth.isAir()) { supported = false; break; }
                placed += placeCaveGrowth(scene, x, detectedSurface, layer, growth, request.replaceExisting(), salt);
            } else if (type.contains("monster_room")) {
                placed += placeMonsterRoom(scene, x, y, layer, request.replaceExisting(), salt);
            } else if (type.contains("freeze_top_layer")) {
                // The configured feature has no payload; vanilla gates freezing by
                // biome temperature. Only cold-biome adapters should materialize it.
                if (context.biomeId() != null && isColdBiome(context.biomeId())) {
                    placed += freezeSurface(scene, x, layer, request.replaceExisting(), salt, request.surfaceY());
                }
            } else if (type.contains("fill_layer")) {
                BlockState state = blockStateField(config, "state");
                if (state == null) { supported = false; break; }
                placed += fillLine(scene, x - 5, x + 5, y, layer, state, request.replaceExisting());
            } else if (type.contains("weeping_vines")) {
                placed += placeDownwardColumn(scene, x, y, layer, Blocks.WEEPING_VINES.getDefaultState(),
                        request.replaceExisting(), 3 + Math.floorMod((int) salt, 6));
            } else if (type.contains("twisting_vines")) {
                placed += placeColumn(scene, x, y, layer, Blocks.TWISTING_VINES.getDefaultState(),
                        request.replaceExisting(), 3 + Math.floorMod((int) salt, 6));
            } else if (type.contains("chorus_plant")) {
                placed += placeChorusPlant(scene, x, y, layer, request.replaceExisting(), salt);
            } else if (type.contains("random_patch") || type.contains("flower")) {
                int nestedPlaced = placeNestedPatchFeature(engine, scene, x, layer, config, request.replaceExisting(), salt, request.surfaceY(), context);
                if (nestedPlaced >= 0) {
                    placed += nestedPlaced;
                } else {
                    List<BlockState> outputs = outputStatesForFeature(type, config);
                    if (outputs.isEmpty()) { supported = false; break; }
                    placed += placeSurfacePatch(scene, x, layer, outputs, request.replaceExisting(), salt, request.surfaceY());
                }
            } else if (type.contains("simple_block") || type.contains("nether_forest_vegetation")
                    || type.contains("block_pile") || type.contains("vegetation_patch")) {
                List<BlockState> outputs = outputStatesForFeature(type, config);
                if (outputs.isEmpty()) { supported = false; break; }
                placed += placeSurfacePatch(scene, x, layer, outputs, request.replaceExisting(), salt, request.surfaceY());
            } else if (type.endsWith(":no_op") || compact.equals("no_op")) {
                // Explicitly supported no-op. It intentionally contributes no cells.
            } else if (type.contains("disk")) {
                List<BlockState> outputs = outputStatesForFeature(type, config);
                if (outputs.isEmpty()) { supported = false; break; }
                placed += fillLine(scene, x - 3, x + 3, y - 1, layer, outputs.get(0), request.replaceExisting());
            } else if (type.contains("replace_single_block")) {
                List<BlockState> outputs = statesFromNamedSubtrees(config, Set.of("state"));
                BlockState output = firstNonBaseState(outputs, context.dimension());
                if (output == null) { supported = false; break; }
                placed += placePatch(scene, x, detectedSurface - 5, layer, List.of(output), request.replaceExisting(), salt);
            } else {
                supported = false;
                break;
            }
            actualPieces++;
        }

        if (!supported) {
            return new Result(placed, fluids, actualPieces,
                    "Skipped unsupported feature translator " + entry.id() + " | " + compact);
        }
        return new Result(placed, fluids, actualPieces,
                "Projected native feature " + entry.id() + " | " + compact);
    }


    private static int scenicBiomeFeatureBudget(int requested, String featureType, int width) {
        if (requested <= 0) return 0;
        String type = featureType == null ? "" : featureType.toLowerCase(Locale.ROOT);

        // A detached side-view is a cross-section, not a full X/Z chunk area.
        // Keep enough decoration to identify the biome while preventing 8/16/32
        // visible layers from becoming a wall of features. Counts are per feature
        // definition for the whole scene volume, not per depth slice.
        int cap;
        if (type.contains("ore") || type.contains("scattered_ore")) {
            // A 2D cross-section otherwise under-represents the many independent
            // 3D ore veins intersecting a chunk volume. Keep the configured
            // feature count authoritative but allow a few more intersections.
            cap = Math.max(2, Math.min(8, width / 48));
        } else if (type.endsWith(":tree") || compactType(type).equals("tree")
                || type.contains("huge_fungus") || type.contains("huge_mushroom")) {
            cap = Math.max(1, Math.min(6, width / 20));
        } else if (type.contains("bamboo") || type.contains("cactus") || type.contains("chorus")) {
            cap = Math.max(2, Math.min(8, width / 12));
        } else if (type.contains("random_patch") || type.contains("flower")
                || type.contains("vegetation") || type.contains("simple_block")) {
            cap = Math.max(3, Math.min(12, width / 8));
        } else if (type.contains("kelp") || type.contains("seagrass") || type.contains("sea_pickle")) {
            cap = Math.max(3, Math.min(10, width / 10));
        } else if (type.contains("spring") || type.contains("lake") || type.contains("geode")) {
            cap = Math.max(1, Math.min(3, width / 48));
        } else {
            cap = Math.max(2, Math.min(10, width / 12));
        }
        return Math.max(0, Math.min(requested, cap));
    }

    private static int placedFeatureInstances(JsonObject placedRoot, int width, long seed) {
        int chunks = Math.max(1, (width + 15) / 16);
        if (placedRoot == null || !placedRoot.has("placement") || !placedRoot.get("placement").isJsonArray()) {
            return Math.max(1, Math.min(24, chunks));
        }

        JsonElement countProvider = null;
        JsonObject noiseThreshold = null;
        JsonObject noiseBased = null;
        int rarity = 1;
        for (JsonElement element : placedRoot.getAsJsonArray("placement")) {
            if (!element.isJsonObject()) continue;
            JsonObject modifier = element.getAsJsonObject();
            String type = jsonString(modifier, "type", "").toLowerCase(Locale.ROOT);
            if (type.contains("rarity_filter")) rarity = Math.max(1, jsonInt(modifier, "chance", rarity));
            if (type.endsWith(":count") || type.contains("count_on_every_layer")) countProvider = modifier.get("count");
            if (type.contains("noise_threshold_count")) noiseThreshold = modifier;
            if (type.contains("noise_based_count")) noiseBased = modifier;
        }

        int attempts = 0;
        for (int chunk = 0; chunk < chunks; chunk++) {
            long chunkSeed = seed ^ ((chunk + 1L) * 0x9E3779B97F4A7C15L);
            int perChunk = 1;
            if (noiseBased != null) {
                float factor = Math.max(1.0F, jsonFloat(noiseBased, "noise_factor", 80.0F));
                float offset = jsonFloat(noiseBased, "noise_offset", 0.0F);
                int ratio = Math.max(1, jsonInt(noiseBased, "noise_to_count_ratio", 1));
                float sampleX = (chunk * 16.0F + (chunkSeed & 0x3FFL)) / factor;
                float n = noise1D(sampleX, chunkSeed ^ 0x94D049BB133111EBL);
                perChunk = Math.max(0, Math.min(64, Math.round(Math.max(0.0F, n + offset) * ratio)));
            } else if (noiseThreshold != null) {
                float noise = noise1D((chunk * 16.0F + (chunkSeed & 0x1FFL)) / 131.0F,
                        chunkSeed ^ 0xD1B54A32D192ED03L);
                int below = jsonInt(noiseThreshold, "below_noise", 1);
                int above = jsonInt(noiseThreshold, "above_noise", below);
                float threshold = jsonFloat(noiseThreshold, "noise_level", 0.0F);
                perChunk = Math.max(0, noise < threshold ? below : above);
            } else if (countProvider != null) {
                // Providers such as weighted_list are sampled independently for
                // every chunk. Sampling once and multiplying by chunk count made
                // trees_plains become either "no trees anywhere" or "trees in
                // every chunk", which is not how the placed feature works.
                perChunk = Math.max(0, providerInt(countProvider, 1, chunkSeed));
            }

            if (rarity <= 1) {
                attempts += perChunk;
            } else {
                for (int candidate = 0; candidate < perChunk; candidate++) {
                    long candidateSeed = chunkSeed ^ ((candidate + 1L) * 0xC2B2AE3D27D4EB4FL);
                    if (hash01(candidateSeed) < 1.0F / rarity) attempts++;
                }
            }
            if (attempts >= 160) return 160;
        }
        return Math.max(0, Math.min(160, attempts));
    }

    private static int sliceIntersectingInstances(int attempts, String featureType, int sourceZ, long seed) {
        if (attempts <= 0) return 0;
        String type = featureType == null ? "" : featureType.toLowerCase(Locale.ROOT);

        /*
         * Vanilla placed features receive concrete 3D positions after placement
         * modifiers such as COUNT/RARITY/IN_SQUARE. Koil used to treat a visual
         * feature's footprint as permission to instantiate the *entire* 2D
         * translation on every nearby depth slice. Once all layers became visible
         * that amplified trees/grass/bushes/bamboo/etc. by roughly 3-7x.
         *
         * Koil already renders the complete side-view silhouette of one tree or
         * vegetation patch in the slice that owns its placement origin, so those
         * surface features must have a zero-depth duplication radius. True
         * underground volumetric features may intersect an adjacent slice and get
         * a narrow radius instead. This keeps layer data independent while making
         * the projected feature density correspond to one 3D candidate set.
         */
        int radius = 0;
        if (type.contains("ore") || type.contains("geode")) radius = 1;
        else if (type.contains("lake") || type.contains("spring")) radius = 1;
        else if (type.contains("multiface_growth")) radius = 1;

        int target = Math.floorMod(sourceZ, 16);
        int kept = 0;
        for (int i = 0; i < attempts; i++) {
            long sample = seed ^ ((i + 1L) * 0xD6E8FEB86659FD93L);
            int z = Math.floorMod((int) (sample ^ (sample >>> 32)), 16);
            int distance = Math.abs(z - target);
            distance = Math.min(distance, 16 - distance);
            if (distance <= radius) kept++;
        }
        return kept;
    }

    private static int providerInt(JsonElement element, int fallback, long seed) {
        if (element == null || element.isJsonNull()) return fallback;
        try {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) return element.getAsInt();
            if (!element.isJsonObject()) return fallback;
            JsonObject object = element.getAsJsonObject();
            if (object.has("value")) return providerInt(object.get("value"), fallback, seed);
            String type = jsonString(object, "type", "").toLowerCase(Locale.ROOT);
            if (type.contains("weighted_list") && object.has("distribution") && object.get("distribution").isJsonArray()) {
                JsonArray distribution = object.getAsJsonArray("distribution");
                int totalWeight = 0;
                for (JsonElement child : distribution) if (child.isJsonObject()) {
                    totalWeight += Math.max(0, jsonInt(child.getAsJsonObject(), "weight", 0));
                }
                if (totalWeight > 0) {
                    int pick = Math.floorMod((int) (seed ^ (seed >>> 32)), totalWeight);
                    for (JsonElement child : distribution) if (child.isJsonObject()) {
                        JsonObject weighted = child.getAsJsonObject();
                        pick -= Math.max(0, jsonInt(weighted, "weight", 0));
                        if (pick < 0) return providerInt(weighted.get("data"), fallback, seed ^ 0x9E3779B97F4A7C15L);
                    }
                }
            }
            if (object.has("min_inclusive") && object.has("max_inclusive")) {
                int min = providerInt(object.get("min_inclusive"), fallback, seed);
                int max = providerInt(object.get("max_inclusive"), min, seed);
                if (max < min) { int swap = min; min = max; max = swap; }
                int span = Math.max(1, max - min + 1);
                return min + Math.floorMod((int) (seed ^ (seed >>> 32)), span);
            }
        } catch (RuntimeException ignored) { }
        return fallback;
    }

    private static SpriteWorldgenCatalog.Entry resolveFeatureEntry(Identifier id) {
        if (id == null) return null;
        SpriteWorldgenCatalog.Entry placed = SpriteWorldgenCatalog.find(SpriteWorldgenCatalog.Kind.PLACED_FEATURE, id).orElse(null);
        if (placed != null) return placed;
        return SpriteWorldgenCatalog.find(SpriteWorldgenCatalog.Kind.CONFIGURED_FEATURE, id).orElse(null);
    }

    private static Identifier selectNestedFeature(JsonObject config, long seed) {
        if (config == null) return null;
        if (config.has("feature_true") || config.has("feature_false")) {
            JsonElement chosen = hash01(seed ^ 0xD6E8FEB86659FD93L) < 0.5F
                    ? config.get("feature_true") : config.get("feature_false");
            return featureHolderId(chosen);
        }
        if (config.has("features") && config.get("features").isJsonArray()) {
            JsonArray choices = config.getAsJsonArray("features");
            boolean chanceSelector = false;
            int chanceIndex = 0;
            // Vanilla random_selector tests each entry's chance independently
            // and in order. A single cumulative roll over-produced later entries
            // such as Bamboo Jungle's mega jungle trees.
            for (JsonElement element : choices) {
                if (!element.isJsonObject()) { chanceIndex++; continue; }
                JsonObject choice = element.getAsJsonObject();
                if (!choice.has("chance")) { chanceIndex++; continue; }
                chanceSelector = true;
                float chance = Math.max(0.0F, Math.min(1.0F, jsonFloat(choice, "chance", 0.0F)));
                long choiceSeed = seed ^ ((chanceIndex + 1L) * 0xA0761D6478BD642FL);
                if (hash01(choiceSeed) < chance) {
                    Identifier id = featureHolderId(choice.get("feature"));
                    if (id != null) return id;
                }
                chanceIndex++;
            }
            // simple_random_selector has no chance fields and selects one holder
            // uniformly. random_selector falls through to its explicit default.
            if (!chanceSelector && !choices.isEmpty()) {
                int index = Math.floorMod((int) (seed ^ (seed >>> 32)), choices.size());
                Identifier id = featureHolderId(choices.get(index));
                if (id != null) return id;
            }
        }
        return featureHolderId(config.get("default"));
    }

    private static Identifier featureHolderId(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return identifier(element.getAsString());
        }
        if (!element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        if (object.has("feature")) {
            Identifier nested = featureHolderId(object.get("feature"));
            if (nested != null) return nested;
        }
        if (object.has("default")) return featureHolderId(object.get("default"));
        return null;
    }

    private static int placementY(Scene scene, int x, int layer, int surface, JsonObject placedRoot,
                                  Identifier featureId, int generationStep, long seed,
                                  int sceneAnchorY, DimensionFlavor dimension) {
        String path = featureId == null ? "" : featureId.getPath().toLowerCase(Locale.ROOT);
        boolean heightmap = hasPlacementModifier(placedRoot, "heightmap");
        if (heightmap) return surface;

        JsonObject heightRange = placementModifier(placedRoot, "height_range");
        if (heightRange != null && heightRange.has("height")) {
            Integer worldY = sampleHeightProvider(heightRange.get("height"), seed, dimension);
            if (worldY != null) return sceneAnchorY + (worldY - 63);
        }

        // Compatibility fallback for custom modifiers Koil does not yet parse.
        if (heightRange != null) {
            float roll = hash01(seed ^ 0xE7037ED1A0B428DBL);
            int minDepth;
            int maxDepth;
            if (path.contains("spring")) { minDepth = 5; maxDepth = 19; }
            else if (path.contains("lake") && path.contains("underground")) { minDepth = 7; maxDepth = 18; }
            else if (generationStep == 6 || path.contains("ore") || path.contains("geode")) { minDepth = 12; maxDepth = 46; }
            else { minDepth = 4; maxDepth = 18; }
            return surface - minDepth - Math.round(roll * Math.max(0, maxDepth - minDepth));
        }
        if (generationStep == 6 || path.contains("ore") || path.contains("geode")) return surface - 18;
        if (generationStep == 7 || path.contains("underground")) return surface - 8;
        if (generationStep == 8 || path.contains("spring")) return surface - 8;
        return surface + Math.round(noise1D(x * 0.17F, seed) * 2.0F);
    }

    private static JsonObject placementModifier(JsonObject placedRoot, String token) {
        if (placedRoot == null || !placedRoot.has("placement") || !placedRoot.get("placement").isJsonArray()) return null;
        for (JsonElement element : placedRoot.getAsJsonArray("placement")) {
            if (!element.isJsonObject()) continue;
            JsonObject modifier = element.getAsJsonObject();
            String type = jsonString(modifier, "type", "").toLowerCase(Locale.ROOT);
            if (type.contains(token)) return modifier;
        }
        return null;
    }

    private static Integer sampleHeightProvider(JsonElement element, long seed, DimensionFlavor dimension) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) return element.getAsInt();
        if (!element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();

        if (object.has("absolute") || object.has("above_bottom") || object.has("below_top")) {
            int bottom = dimension == DimensionFlavor.NETHER || dimension == DimensionFlavor.END ? 0 : -64;
            int top = dimension == DimensionFlavor.NETHER ? 127 : (dimension == DimensionFlavor.END ? 255 : 319);
            if (object.has("absolute")) return jsonInt(object, "absolute", 63);
            if (object.has("above_bottom")) return bottom + jsonInt(object, "above_bottom", 0);
            if (object.has("below_top")) return top - jsonInt(object, "below_top", 0);
        }

        String type = jsonString(object, "type", "").toLowerCase(Locale.ROOT);
        if (type.contains("constant") && object.has("value")) {
            return sampleHeightProvider(object.get("value"), seed, dimension);
        }
        if ((type.contains("uniform") || type.contains("trapezoid") || type.contains("biased_to_bottom"))
                && object.has("min_inclusive") && object.has("max_inclusive")) {
            Integer min = sampleHeightProvider(object.get("min_inclusive"), seed ^ 0x632BE59BD9B4E019L, dimension);
            Integer max = sampleHeightProvider(object.get("max_inclusive"), seed ^ 0x9E3779B97F4A7C15L, dimension);
            if (min == null || max == null) return null;
            if (max < min) { int swap = min; min = max; max = swap; }
            float roll = hash01(seed ^ 0xD1B54A32D192ED03L);
            if (type.contains("biased_to_bottom")) roll *= roll;
            return min + Math.round(roll * Math.max(0, max - min));
        }
        if (type.contains("weighted_list") && object.has("distribution") && object.get("distribution").isJsonArray()) {
            JsonArray distribution = object.getAsJsonArray("distribution");
            int total = 0;
            for (JsonElement child : distribution) if (child.isJsonObject()) total += Math.max(0, jsonInt(child.getAsJsonObject(), "weight", 0));
            if (total <= 0) return null;
            int pick = Math.floorMod((int) (seed ^ (seed >>> 32)), total);
            for (JsonElement child : distribution) if (child.isJsonObject()) {
                JsonObject weighted = child.getAsJsonObject();
                pick -= Math.max(0, jsonInt(weighted, "weight", 0));
                if (pick < 0) return sampleHeightProvider(weighted.get("data"), seed ^ 0x94D049BB133111EBL, dimension);
            }
        }
        return null;
    }

    private static boolean hasPlacementModifier(JsonObject placedRoot, String token) {
        if (placedRoot == null || !placedRoot.has("placement") || !placedRoot.get("placement").isJsonArray()) return false;
        for (JsonElement element : placedRoot.getAsJsonArray("placement")) {
            if (!element.isJsonObject()) continue;
            String type = jsonString(element.getAsJsonObject(), "type", "").toLowerCase(Locale.ROOT);
            if (type.contains(token)) return true;
        }
        return false;
    }

    private static BlockState blockStateField(JsonObject object, String key) {
        if (object == null || !object.has(key)) return null;
        BlockState state = blockStateFromJson(object.get(key), null);
        return state == null || state.isAir() ? null : state;
    }

    private static BlockState firstProviderState(JsonElement provider) {
        List<BlockState> states = new ArrayList<>();
        collectProviderStates(provider, states);
        return states.isEmpty() ? null : states.get(0);
    }

    private static void collectProviderStates(JsonElement element, List<BlockState> out) {
        if (element == null || element.isJsonNull() || out.size() >= 24) return;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("Name") || object.has("name")) {
                BlockState state = blockStateFromJson(object, null);
                if (state != null && !state.isAir() && !out.contains(state)) out.add(state);
                return;
            }
            if (object.has("state")) collectProviderStates(object.get("state"), out);
            if (object.has("states")) collectProviderStates(object.get("states"), out);
            if (object.has("entries")) collectProviderStates(object.get("entries"), out);
            if (object.has("to_place")) collectProviderStates(object.get("to_place"), out);
            if (object.has("state_provider")) collectProviderStates(object.get("state_provider"), out);
            if (object.has("feature")) collectProviderStates(object.get("feature"), out);
            if (object.has("config")) collectProviderStates(object.get("config"), out);
            for (var child : object.entrySet()) {
                String key = child.getKey();
                if ("state".equals(key) || "states".equals(key) || "entries".equals(key) || "to_place".equals(key)
                        || "state_provider".equals(key) || "feature".equals(key) || "config".equals(key)) continue;
                if (key.contains("provider")) collectProviderStates(child.getValue(), out);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectProviderStates(child, out);
        }
    }

    private static List<BlockState> statesFromNamedSubtrees(JsonObject root, Set<String> keys) {
        List<BlockState> out = new ArrayList<>();
        collectStatesFromNamedSubtrees(root, keys, out);
        return out;
    }

    private static void collectStatesFromNamedSubtrees(JsonElement element, Set<String> keys, List<BlockState> out) {
        if (element == null || element.isJsonNull() || out.size() >= 32) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectStatesFromNamedSubtrees(child, keys, out);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        for (var child : object.entrySet()) {
            if (keys.contains(child.getKey())) collectProviderStates(child.getValue(), out);
            else collectStatesFromNamedSubtrees(child.getValue(), keys, out);
        }
    }

    private static List<BlockState> outputStatesForFeature(String type, JsonObject config) {
        List<BlockState> out = new ArrayList<>();
        if (config == null) return out;
        if (type.contains("nether_forest_vegetation")) {
            collectProviderStates(config.get("state_provider"), out);
        } else if (type.contains("simple_block")) {
            collectProviderStates(config.get("to_place"), out);
        } else if (type.contains("random_patch") || type.contains("flower")) {
            collectProviderStates(config.get("feature"), out);
            if (out.isEmpty()) collectProviderStates(config.get("to_place"), out);
        } else if (type.contains("block_pile")) {
            collectProviderStates(config.get("state_provider"), out);
        } else if (type.contains("vegetation_patch")) {
            collectProviderStates(config.get("vegetation_feature"), out);
            collectProviderStates(config.get("ground_state"), out);
        } else if (type.contains("disk")) {
            collectProviderStates(config.get("state_provider"), out);
            collectProviderStates(config.get("state"), out);
        }
        return out;
    }

    private static BlockState firstNonBaseState(List<BlockState> states, DimensionFlavor dimension) {
        if (states == null || states.isEmpty()) return null;
        for (BlockState state : states) {
            if (state == null || state.isAir()) continue;
            Block block = state.getBlock();
            if (dimension == DimensionFlavor.NETHER
                    && (block == Blocks.NETHERRACK || block == Blocks.SOUL_SAND || block == Blocks.SOUL_SOIL
                    || block == Blocks.BASALT || block == Blocks.BLACKSTONE)) continue;
            if (dimension == DimensionFlavor.END && block == Blocks.END_STONE) continue;
            if (dimension == DimensionFlavor.OVERWORLD
                    && (block == Blocks.STONE || block == Blocks.DEEPSLATE || block == Blocks.DIRT)) continue;
            return state;
        }
        return states.get(0);
    }

    private static Fluid configuredFeatureFluid(JsonObject config) {
        Identifier id = findFluidIdentifier(config);
        return id != null && Registries.FLUID.containsId(id) ? Registries.FLUID.get(id) : Fluids.EMPTY;
    }

    private static Identifier findFluidIdentifier(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            Identifier id = identifier(element.getAsString());
            return id != null && Registries.FLUID.containsId(id) ? id : null;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                Identifier found = findFluidIdentifier(child);
                if (found != null) return found;
            }
            return null;
        }
        if (!element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        if (object.has("Name") || object.has("name")) {
            String raw = object.has("Name") ? jsonString(object, "Name", "") : jsonString(object, "name", "");
            Identifier id = identifier(raw);
            if (id != null && Registries.FLUID.containsId(id)) return id;
        }
        for (String key : List.of("fluid", "state", "fluid_state", "content")) {
            if (!object.has(key)) continue;
            Identifier found = findFluidIdentifier(object.get(key));
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Projects configured trees as shallow 3D vegetation instead of a trunk with
     * a flat circular crown. The trunk remains on the feature's owning depth
     * plane while part of the canopy occupies layer+1, so cutout leaves can
     * genuinely cover upper logs in Koil's depth compositor without destroying
     * either block. Standard modded trunk/foliage placer JSON participates too.
     */
    private static int placeConfiguredTree(Scene scene, int x, int y, int layer, BlockState trunk,
                                           BlockState foliage, boolean replace, long seed, JsonObject config) {
        int height = treeHeight(config, seed);
        int radius = treeFoliageRadius(config);
        foliage = MinecraftStateCodec.withProperty(foliage, "persistent", "true");
        foliage = MinecraftStateCodec.withProperty(foliage, "distance", "1");
        JsonObject trunkPlacer = object(config, "trunk_placer");
        JsonObject foliagePlacer = object(config, "foliage_placer");
        String trunkType = jsonString(trunkPlacer, "type", "").toLowerCase(Locale.ROOT);
        String foliageType = jsonString(foliagePlacer, "type", "").toLowerCase(Locale.ROOT);

        // Minecraft trees are authored in full X/Z space. A fixed side-view was
        // making every generated tree expose the same branch/canopy face. Rotate
        // each tree by a deterministic quarter turn before projecting it into the
        // selected scene layers. This changes geometry, never leaf texture UVs.
        int quarterTurns = Math.floorMod((int) (seed ^ (seed >>> 32)), 4);
        int placed = 0;
        int localTrunkX = 0;
        int localTrunkZ = 0;
        int bendStart = Math.max(2, height - Math.max(2, height / 3));
        int bendDirection = hash01(seed ^ 0xA24BAED4963EE407L) < 0.5F ? -1 : 1;
        boolean bends = trunkType.contains("bending") || trunkType.contains("forking")
                || trunkType.contains("upwards_branching") || foliageType.contains("acacia");

        for (int dy = 0; dy < height; dy++) {
            if (bends && dy >= bendStart && dy < height - 1 && ((dy - bendStart) & 1) == 1) {
                localTrunkX += bendDirection;
            }
            SceneCellPos trunkPos = treePos(x, y + dy, layer, localTrunkX, localTrunkZ, quarterTurns);
            if (setTreeWood(scene, trunkPos, trunk, replace)) placed++;

            if ((trunkType.contains("branch") || trunkType.contains("fork") || foliageType.contains("jungle"))
                    && dy >= height / 2 && dy < height - 1
                    && hash01(seed ^ (dy * 0x9E3779B97F4A7C15L)) > 0.70F) {
                int branchDir = ((dy + (int) seed) & 1) == 0 ? -1 : 1;
                BlockState branch = MinecraftStateCodec.withProperty(trunk, "axis",
                        (quarterTurns & 1) == 0 ? "x" : "z");
                int length = 1 + (hash01(seed ^ (dy * 0x632BE59BD9B4E019L)) > 0.72F ? 1 : 0);
                for (int step = 1; step <= length; step++) {
                    SceneCellPos branchPos = treePos(x, y + dy, layer,
                            localTrunkX + branchDir * step, localTrunkZ, quarterTurns);
                    if (setTreeWood(scene, branchPos, branch, false)) placed++;
                }
            }
        }

        int crownY = y + height - 1 + configuredFoliageOffset(foliagePlacer, seed);
        int foliageHeight = configuredFoliageHeight(foliagePlacer, radius, seed);
        int minDy = -Math.max(1, foliageHeight - 1);
        int maxDy = foliageType.contains("cherry") ? 2 : 1;

        for (int dy = minDy; dy <= maxDy; dy++) {
            int rowRadius = foliageRowRadius(foliageType, radius, dy, foliageHeight);
            if (rowRadius < 0) continue;
            for (int dx = -rowRadius; dx <= rowRadius; dx++) {
                long local = seed ^ (dx * 0x9E3779B97F4A7C15L) ^ (dy * 0xC2B2AE3D27D4EB4FL);
                if (!foliageCellAllowed(foliageType, dx, dy, rowRadius, local)) continue;
                int fy = crownY + dy;

                SceneCellPos same = treePos(x, fy, layer, localTrunkX + dx, localTrunkZ, quarterTurns);
                BlockState existing = scene.blocks().getBlockState(same);
                if (existing == null || existing.isAir()) {
                    if (set(scene, same, foliage, false)) placed++;
                }

                boolean centralCanopy = Math.abs(dx) <= Math.max(1, rowRadius - 1)
                        && dy >= -Math.max(2, foliageHeight / 2);
                if (centralCanopy || hash01(local ^ 0xD1B54A32D192ED03L) > 0.68F) {
                    SceneCellPos front = treePos(x, fy, layer, localTrunkX + dx, localTrunkZ + 1, quarterTurns);
                    BlockState frontExisting = scene.blocks().getBlockState(front);
                    if (frontExisting == null || frontExisting.isAir()) {
                        if (set(scene, front, foliage, false)) placed++;
                    }
                }

                if (hash01(local ^ 0x94D049BB133111EBL) > 0.88F) {
                    SceneCellPos rear = treePos(x, fy, layer, localTrunkX + dx, localTrunkZ - 1, quarterTurns);
                    BlockState rearExisting = scene.blocks().getBlockState(rear);
                    if (rearExisting == null || rearExisting.isAir()) {
                        if (set(scene, rear, foliage, false)) placed++;
                    }
                }
            }
        }

        if (foliageType.contains("acacia")) {
            for (int dx = -radius - 1; dx <= radius + 1; dx++) {
                if (Math.abs(dx) <= 1 || hash01(seed ^ (dx * 0xDB4F0B9175AE2165L)) > 0.20F) {
                    SceneCellPos pos = treePos(x, crownY + 1, layer, localTrunkX + dx, localTrunkZ + 1, quarterTurns);
                    BlockState state = scene.blocks().getBlockState(pos);
                    if ((state == null || state.isAir()) && set(scene, pos, foliage, false)) placed++;
                }
            }
        } else if (trunkType.contains("upwards_branching") || foliageType.contains("mangrove")) {
            int lowY = crownY - Math.max(2, foliageHeight / 2);
            for (int side : new int[]{-1, 1}) {
                for (int step = 1; step <= Math.max(2, radius); step++) {
                    SceneCellPos pos = treePos(x, lowY + step / 2, layer,
                            localTrunkX + side * step, localTrunkZ + 1, quarterTurns);
                    BlockState state = scene.blocks().getBlockState(pos);
                    if ((state == null || state.isAir()) && set(scene, pos, foliage, false)) placed++;
                }
            }
        }
        return placed;
    }

    private static SceneCellPos treePos(int originX, int y, int originLayer,
                                        int localX, int localZ, int quarterTurns) {
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 1 -> new SceneCellPos(originX - localZ, y, originLayer + localX);
            case 2 -> new SceneCellPos(originX - localX, y, originLayer - localZ);
            case 3 -> new SceneCellPos(originX + localZ, y, originLayer - localX);
            default -> new SceneCellPos(originX + localX, y, originLayer + localZ);
        };
    }

    private static boolean setTreeWood(Scene scene, SceneCellPos pos, BlockState state, boolean replace) {
        BlockState existing = scene.blocks().getBlockState(pos);
        if (existing != null && !existing.isAir() && !replace && !generatedCell(scene, pos)) return false;
        return set(scene, pos, state, replace);
    }

    private static int configuredFoliageHeight(JsonObject foliagePlacer, int radius, long seed) {
        if (foliagePlacer == null) return Math.max(3, radius * 2 + 1);
        int fallback = Math.max(3, radius * 2 + 1);
        int height = providerInt(foliagePlacer.get("foliage_height"), fallback, seed ^ 0xA0761D6478BD642FL);
        if (foliagePlacer.has("height")) {
            height = providerInt(foliagePlacer.get("height"), height, seed ^ 0xE7037ED1A0B428DBL);
        }
        return Math.max(2, Math.min(12, height));
    }

    private static int configuredFoliageOffset(JsonObject foliagePlacer, long seed) {
        if (foliagePlacer == null) return 0;
        return Math.max(-4, Math.min(4, providerInt(foliagePlacer.get("offset"), 0,
                seed ^ 0x8EBC6AF09C88C6E3L)));
    }

    private static int foliageRowRadius(String type, int radius, int dy, int height) {
        String value = type == null ? "" : type;
        if (value.contains("spruce") || value.contains("pine") || value.contains("mega_pine")) {
            int fromTop = 1 - dy;
            int cone = Math.max(0, Math.min(radius, fromTop / 2));
            return dy < -height + 2 ? -1 : cone;
        }
        if (value.contains("acacia")) {
            return dy >= 0 ? radius + 1 : (dy >= -1 ? radius : Math.max(1, radius - 1));
        }
        if (value.contains("dark_oak") || value.contains("jungle") || value.contains("mega")) {
            return Math.max(1, radius + (dy >= -1 && dy <= 0 ? 1 : 0) - Math.max(0, Math.abs(dy) - 2));
        }
        if (value.contains("cherry")) {
            return Math.max(1, radius + (dy >= -1 ? 1 : 0) - Math.max(0, -dy - 2));
        }
        if (value.contains("bush")) {
            return Math.max(1, radius - Math.max(0, Math.abs(dy) - 1));
        }
        int taper = Math.max(0, Math.abs(dy) - Math.max(1, height / 3));
        return Math.max(1, radius - taper);
    }

    private static boolean foliageCellAllowed(String type, int dx, int dy, int rowRadius, long seed) {
        if (rowRadius <= 0) return dx == 0;
        float nx = Math.abs(dx) / (float) Math.max(1, rowRadius);
        float edge = nx + Math.max(0.0F, Math.abs(dy) - 1) * 0.10F;
        float holeChance = type != null && type.contains("cherry") ? 0.14F : 0.07F;
        if (edge > 1.08F) return false;
        return hash01(seed) >= holeChance || (Math.abs(dx) <= 1 && Math.abs(dy) <= 1);
    }

    private static int placeTreeLikeExact(Scene scene, int x, int y, int layer, BlockState trunk,
                                          BlockState foliage, boolean replace, long seed) {
        return placeTreeLikeExact(scene, x, y, layer, trunk, foliage, replace, seed,
                4 + Math.floorMod((int) seed, 4), 2);
    }

    private static int placeTreeLikeExact(Scene scene, int x, int y, int layer, BlockState trunk,
                                          BlockState foliage, boolean replace, long seed, int requestedHeight,
                                          int requestedRadius) {
        int height = Math.max(3, Math.min(28, requestedHeight));
        int radius = Math.max(1, Math.min(5, requestedRadius));
        int placed = placeColumn(scene, x, y, layer, trunk, replace, height);
        int crownCenter = y + height - 1;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= Math.max(1, radius - 1); dy++) {
                float normalized = Math.abs(dx) / (float) Math.max(1, radius)
                        + Math.abs(dy) / (float) Math.max(1, radius + 1);
                if (normalized > 1.55F) continue;
                if (hash01(seed ^ (dx * 31L) ^ (dy * 131L)) < 0.08F) continue;
                if (set(scene, new SceneCellPos(x + dx, crownCenter + dy, layer), foliage, replace)) placed++;
            }
        }
        return placed;
    }

    private static int treeHeight(JsonObject config, long seed) {
        JsonObject placer = object(config, "trunk_placer");
        if (placer == null) return 4 + Math.floorMod((int) seed, 4);
        int base = Math.max(1, jsonInt(placer, "base_height", 4));
        int a = Math.max(0, jsonInt(placer, "height_rand_a", 2));
        int b = Math.max(0, jsonInt(placer, "height_rand_b", 0));
        int ra = a == 0 ? 0 : Math.floorMod((int) seed, a + 1);
        int rb = b == 0 ? 0 : Math.floorMod((int) (seed >>> 17), b + 1);
        return Math.max(3, Math.min(28, base + ra + rb));
    }

    private static int treeFoliageRadius(JsonObject config) {
        JsonObject placer = object(config, "foliage_placer");
        if (placer == null) return 2;
        return Math.max(1, Math.min(5, providerInt(placer.get("radius"), 2, 0x4F9939F508L)));
    }

    private static int placeHugeFungus(Scene scene, int x, int y, int layer, BlockState stem,
                                       BlockState hat, BlockState decor, boolean replace, long seed) {
        int height = 5 + Math.floorMod((int) (seed ^ (seed >>> 32)), 5);
        int placed = placeColumn(scene, x, y, layer, stem, replace, height);
        int capY = y + height - 1;
        for (int dy = -1; dy <= 1; dy++) {
            int radius = dy == 1 ? 1 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                BlockState state = decor != null && hash01(seed ^ (dx * 31L) ^ (dy * 131L)) > 0.86F ? decor : hat;
                if (set(scene, new SceneCellPos(x + dx, capY + dy, layer), state, replace)) placed++;
            }
        }
        return placed;
    }

    private static int placeDelta(Scene scene, int x, int y, int layer, BlockState contents,
                                  BlockState rim, boolean replace, long seed) {
        int radius = 2 + Math.floorMod((int) seed, 3);
        int placed = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            int localY = y + Math.round(noise1D((x + dx) * 0.31F, seed) * 1.5F);
            BlockState state = Math.abs(dx) == radius ? rim : contents;
            if (set(scene, new SceneCellPos(x + dx, localY, layer), state, replace)) placed++;
        }
        return placed;
    }

    private static int placeDownwardColumn(Scene scene, int x, int y, int layer, BlockState state,
                                           boolean replace, int height) {
        int placed = 0;
        for (int i = 0; i < Math.max(1, height); i++) {
            SceneCellPos pos = new SceneCellPos(x, y - i, layer);
            if (scene.blocks().occupied(pos) && !replace) break;
            if (set(scene, pos, state, replace)) placed++;
        }
        return placed;
    }

    private static int placeChorusPlant(Scene scene, int x, int y, int layer, boolean replace, long seed) {
        int placed = 0;
        int height = 4 + Math.floorMod((int) (seed ^ (seed >>> 32)), 5);
        int topY = y + height - 1;
        for (int dy = 0; dy < height - 1; dy++) {
            if (set(scene, new SceneCellPos(x, y + dy, layer), Blocks.CHORUS_PLANT.getDefaultState(), replace)) placed++;
        }

        // Chorus plants branch in X/Z and terminate in flowers. Keep the shallow
        // hidden-depth branches so ConnectingBlock state has real neighbors to
        // connect to instead of rendering as a disconnected vertical pole.
        int branches = 1 + Math.floorMod((int) (seed >>> 9), 3);
        for (int i = 0; i < branches; i++) {
            int branchY = y + Math.max(1, height - 3 - i);
            int dir = Math.floorMod((int) (seed >>> (i * 7)), 4);
            int dx = dir == 0 ? 1 : dir == 1 ? -1 : 0;
            int dz = dir == 2 ? 1 : dir == 3 ? -1 : 0;
            int length = 1 + Math.floorMod((int) (seed >>> (i * 5 + 3)), 2);
            for (int step = 1; step <= length; step++) {
                SceneCellPos branch = new SceneCellPos(x + dx * step, branchY, layer + dz * step);
                BlockState state = step == length ? Blocks.CHORUS_FLOWER.getDefaultState()
                        : Blocks.CHORUS_PLANT.getDefaultState();
                if (set(scene, branch, state, replace)) placed++;
            }
        }
        if (set(scene, new SceneCellPos(x, topY, layer), Blocks.CHORUS_FLOWER.getDefaultState(), replace)) placed++;
        return placed;
    }

    private static int placeGlowstone(Scene scene, int x, int y, int layer, boolean replace, long seed) {
        int placed = 0;
        for (int i = 0; i < 9; i++) {
            int dx = Math.floorMod((int) (seed >>> (i * 4)), 5) - 2;
            int dy = Math.floorMod((int) (seed >>> (i * 3 + 5)), 4) - 3;
            if (set(scene, new SceneCellPos(x + dx, y + dy, layer), Blocks.GLOWSTONE.getDefaultState(), replace)) placed++;
        }
        return placed;
    }

    private static Result generateBiome(SpriteEngine engine, Request request, SpriteWorldgenCatalog.Entry entry) {
        JsonObject json = SpriteWorldgenCatalog.readJson(entry).orElse(null);
        Scene scene = engine.scene();
        scene.environment().setBiomeId(entry.id());
        float temperature = jsonFloat(json, "temperature", 0.8F);
        float downfall = jsonFloat(json, "downfall", 0.4F);
        scene.environment().setTemperature(temperature);
        scene.environment().setDownfall(downfall);

        // Biome effects only store foliage/grass when a biome overrides the
        // climate colormap. Reset every biome first so switching previews cannot
        // leak the previous biome's colors into the new scene.
        scene.environment().setWaterColor(0x3F76E4);

        // Vanilla biome climate temperature is not constrained to the 0..1
        // colormap domain (deserts and Nether biomes commonly use 2.0). The
        // 256x256 grass/foliage maps require both climate axes to be clamped
        // before lookup. Passing raw biome JSON values can produce a negative
        // palette index (for example temperature=2.0 -> x=-255) and crash the
        // render thread when Generate is pressed. Keep the raw climate values in
        // SceneEnvironment for simulation, but clamp only the visual lookup.
        float colorTemperature = clampBiomeColorInput(temperature, 0.8F);
        float colorDownfall = clampBiomeColorInput(downfall, 0.4F);
        scene.environment().setFoliageColor(FoliageColors.getColor(colorTemperature, colorDownfall));
        scene.environment().setGrassColor(GrassColors.getColor(colorTemperature, colorDownfall));
        scene.environment().setGrassColorModifier("none");
        if (json != null) {
            JsonObject effects = object(json, "effects");
            if (effects != null) {
                if (effects.has("water_color")) try { scene.environment().setWaterColor(effects.get("water_color").getAsInt()); } catch (RuntimeException ignored) { }
                if (effects.has("foliage_color")) try { scene.environment().setFoliageColor(effects.get("foliage_color").getAsInt()); } catch (RuntimeException ignored) { }
                if (effects.has("grass_color")) try { scene.environment().setGrassColor(effects.get("grass_color").getAsInt()); } catch (RuntimeException ignored) { }
                if (effects.has("grass_color_modifier")) {
                    try { scene.environment().setGrassColorModifier(effects.get("grass_color_modifier").getAsString()); }
                    catch (RuntimeException ignored) { }
                }
            }
        }

        BiomeSceneStyle sceneStyle = resolveBiomeSceneStyle(entry.id(), json, temperature, downfall);
        DimensionFlavor dimension = dimensionForSceneStyle(sceneStyle, entry.id());
        if (dimension == DimensionFlavor.NETHER) {
            scene.environment().setDimensionId(new Identifier("minecraft", "the_nether"));
            scene.environment().setSkyLight(0);
            // Nether has no skylight but is not visually pitch black. The detached
            // renderer has no dimension lightmap, so retain a low ambient block
            // channel while native local light sources still dominate.
            scene.environment().setBlockLight(5);
        } else if (dimension == DimensionFlavor.END) {
            scene.environment().setDimensionId(new Identifier("minecraft", "the_end"));
            scene.environment().setSkyLight(0);
            // Same detached-lightmap issue in the End. A neutral ambient channel
            // keeps end stone's native yellow texture readable rather than black.
            scene.environment().setBlockLight(10);
        } else {
            scene.environment().setDimensionId(new Identifier("minecraft", "overworld"));
            scene.environment().setSkyLight(15);
            scene.environment().setBlockLight(0);
        }
        BiomeSliceProfile profile = adaptProfileForStyle(
                BiomeSliceProfile.resolve(entry.id(), temperature, downfall), sceneStyle, temperature);
        profile = adaptModdedProfileFromFeatures(profile, entry.id(), json);
        List<Identifier> biomeCarvers = json != null && json.has("carvers")
                ? collectBiomeCarverRefs(json.get("carvers")) : List.of();
        Result terrain = generateBiomeSlices(engine, request, entry.id(), profile, biomeCarvers.isEmpty(), sceneStyle);
        int baseTerrainCells = countGeneratedTerrainCells(scene, request);
        int blocks = terrain.blocks();
        int fluids = terrain.fluids();
        int pieces = terrain.pieces();

        int carverRefs = biomeCarvers.size();
        int carversApplied = 0;
        int undergroundBiomeRegions = 0;
        int undergroundBiomeFeatures = 0;
        int aquiferPools = 0;
        int undergroundStructures = 0;

        // Surface-biome caves are one coherent density field. Do not run every
        // configured carver as an independent destructive pass and do not author
        // oval rooms connected by corridors. Vanilla 1.20.1 composes multiple
        // cave density families (entrances, cheese, spaghetti, noodle, pillars);
        // Koil mirrors that architecture in a detached deterministic field.
        if ((dimension == DimensionFlavor.OVERWORLD || dimension == DimensionFlavor.UNKNOWN)
                && !isUndergroundBiomeStyle(sceneStyle)) {
            Result caves = carveVanillaInspiredCaves(scene, request, entry.id(), profile, biomeCarvers);
            pieces += caves.pieces();
            carversApplied = caves.pieces() > 0 ? 1 : 0;

            Result aquifers = seedUndergroundPools(scene, request);
            fluids += aquifers.fluids();
            pieces += aquifers.pieces();
            aquiferPools = aquifers.pieces();

            Result underground = decorateDensityCaveBiomes(engine, request, entry, json, profile);
            blocks += underground.blocks();
            fluids += underground.fluids();
            pieces += underground.pieces();
            undergroundBiomeRegions = parseMetric(underground.message(), "regions=");
            undergroundBiomeFeatures = parseMetric(underground.message(), "features=");

            Result undergroundStructure = maybeGenerateUndergroundStructure(engine, request, entry.id());
            blocks += undergroundStructure.blocks();
            fluids += undergroundStructure.fluids();
            pieces += undergroundStructure.pieces();
            undergroundStructures = undergroundStructure.pieces();
        }

        int featureRefs = 0;
        int featureAttempts = 0;
        int featureApplied = 0;
        int featureNoPlacement = 0;
        int featureSkipped = 0;
        List<String> skippedFeatureIds = new ArrayList<>();

        // Biome JSON already stores feature holders in vanilla generation-step
        // order. Run the complete list rather than truncating the biome after an
        // arbitrary first 24 entries. Unsupported feature types are skipped and
        // reported instead of being approximated with unrelated leaves/water.
        if (json != null && json.has("features")) {
            List<BiomeFeatureRef> features = collectBiomeFeatureRefs(json.get("features"));
            featureRefs = features.size();
            int featureIndex = 0;
            for (BiomeFeatureRef ref : features) {
                SpriteWorldgenCatalog.Entry feature = SpriteWorldgenCatalog.find(SpriteWorldgenCatalog.Kind.PLACED_FEATURE, ref.id())
                        .orElseGet(() -> SpriteWorldgenCatalog.find(SpriteWorldgenCatalog.Kind.CONFIGURED_FEATURE, ref.id()).orElse(null));
                if (feature == null) {
                    featureSkipped++;
                    if (skippedFeatureIds.size() < 4) skippedFeatureIds.add(ref.id().toString());
                    featureIndex++;
                    continue;
                }
                if (unsafeDetachedBiomeFeature(feature)) {
                    featureSkipped++;
                    if (skippedFeatureIds.size() < 4) skippedFeatureIds.add(ref.id().toString());
                    featureIndex++;
                    continue;
                }
                boolean refApplied = false;
                boolean refSkipped = false;
                boolean refNoPlacement = true;

                // One biome feature definition owns one candidate set for the
                // entire authored depth volume. The old implementation reran the
                // feature once per visible Z slice, which exploded decoration and
                // ore density when the user selected 8/16/32 layers. Distribute
                // that one candidate set across the requested layers instead.
                long featureSeed = request.seed()
                        ^ ((featureIndex + 1L) * 0xC2B2AE3D27D4EB4FL)
                        ^ ((long) ref.id().hashCode() * 0x9E3779B97F4A7C15L);
                int anchorLayer = request.layers().get(Math.max(0, request.layers().size() / 2));
                int anchorY = featureAnchorY(scene, request, anchorLayer, ref.step(), feature.id());
                Result featureResult = generateFeature(engine, new Request(feature, featureSeed,
                                request.layers(), request.centerX(), anchorY, request.width(), false),
                        feature, new FeatureContext(dimension, entry.id(), ref.step(), Integer.MIN_VALUE));
                blocks += featureResult.blocks();
                fluids += featureResult.fluids();
                pieces += featureResult.pieces();
                featureAttempts++;
                if (featureResult.message().startsWith("Skipped unsupported")) refSkipped = true;
                if (!featureResult.message().startsWith("Placement modifiers produced no attempts")) refNoPlacement = false;
                if (featureResult.blocks() > 0 || featureResult.fluids() > 0 || featureResult.pieces() > 0) refApplied = true;
                if (refApplied) featureApplied++;
                else if (refSkipped) {
                    featureSkipped++;
                    if (skippedFeatureIds.size() < 4) skippedFeatureIds.add(ref.id().toString());
                } else if (refNoPlacement) {
                    featureNoPlacement++;
                }
                featureIndex++;
            }
        }

        // Structures are projected after biome decoration so their authored
        // solid blocks and interior air volume remain authoritative. This keeps
        // trees/grass/patch features from subsequently growing through houses,
        // ruins, temples, or modded jigsaw interiors in the detached scene.
        Result biomeStructure = maybeGenerateBiomeStructure(engine, request, entry.id());
        blocks += biomeStructure.blocks();
        fluids += biomeStructure.fluids();
        pieces += biomeStructure.pieces();
        int biomeStructures = biomeStructure.blocks() > 0 ? 1 : 0;

        String dimensionLabel = dimension.name().toLowerCase(Locale.ROOT);
        String skippedSummary = skippedFeatureIds.isEmpty() ? ""
                : " | skipped examples " + String.join(", ", skippedFeatureIds);
        int unexpectedGrass = countUnexpectedSurfaceGrass(scene, request, entry.id());
        String grassCheck = entry.id() != null && entry.id().getPath().contains("plains")
                ? " | plains buried grass=" + unexpectedGrass : "";
        int bambooBlocks = entry.id() != null && entry.id().getPath().contains("bamboo_jungle")
                ? countBlockInGeneration(scene, request, Blocks.BAMBOO) : -1;
        String bambooCheck = bambooBlocks >= 0 ? " | bamboo=" + bambooBlocks : "";
        SceneCellPos focus = recommendBiomeFocus(scene, request, entry.id());
        int finalTerrainCells = countGeneratedTerrainCells(scene, request);
        return new Result(blocks, fluids, pieces,
                "Biome " + entry.id() + " [" + dimensionLabel + "] | terrain " + baseTerrainCells + " -> " + finalTerrainCells
                        + " | slices " + request.layers().size()
                        + " | carvers " + carversApplied + "/" + carverRefs
                        + " | scene " + sceneStyle.name().toLowerCase(Locale.ROOT)
                        + " | underground regions " + undergroundBiomeRegions + ", cave features " + undergroundBiomeFeatures
                        + " | aquifer pools " + aquiferPools
                        + " | structures " + biomeStructures + " surface + " + undergroundStructures + " underground"
                        + " | feature refs " + featureRefs + ", applied " + featureApplied
                        + ", no-placement " + featureNoPlacement + ", unsupported " + featureSkipped + ", layer passes " + featureAttempts + grassCheck + bambooCheck + skippedSummary, focus);
    }


    private static Result maybeGenerateBiomeStructure(SpriteEngine engine, Request request, Identifier biomeId) {
        if (engine == null || request == null || biomeId == null || request.width() < 48) {
            return new Result(0, 0, 0, "structure=none");
        }

        Scene scene = engine.scene();
        int plane = request.editPlane();
        int ordinal = 0;
        for (SpriteWorldgenCatalog.Entry setEntry
                : SpriteWorldgenCatalog.search(SpriteWorldgenCatalog.Kind.STRUCTURE_SET, "")) {
            JsonObject setJson = SpriteWorldgenCatalog.readJson(setEntry).orElse(null);
            if (setJson == null) { ordinal++; continue; }
            JsonObject placement = object(setJson, "placement");
            Integer anchorX = structureSetAnchorX(request, placement, setEntry.id(), ordinal);
            if (anchorX == null) { ordinal++; continue; }

            SpriteWorldgenCatalog.Entry structure = chooseBiomeStructureFromSet(
                    setJson.get("structures"), biomeId,
                    request.seed() ^ ((ordinal + 1L) * 0xD1B54A32D192ED03L));
            if (structure == null) { ordinal++; continue; }

            int y = findTerrainSurfaceY(scene, anchorX, plane, request.surfaceY());
            long structureSeed = request.seed()
                    ^ ((long) setEntry.id().hashCode() * 0x9E3779B97F4A7C15L)
                    ^ ((long) structure.id().hashCode() * 0xC2B2AE3D27D4EB4FL);
            Result generated = generateStructure(engine, new Request(structure, structureSeed, List.of(plane),
                    anchorX, y, request.width(), true, plane), structure);
            if (generated.blocks() > 0) {
                return new Result(generated.blocks(), generated.fluids(), generated.pieces(),
                        "structure=" + structure.id(), generated.focus());
            }
            ordinal++;
        }
        return new Result(0, 0, 0, "structure=none");
    }

    private static SpriteWorldgenCatalog.Entry chooseBiomeStructureFromSet(JsonElement structures,
                                                                            Identifier biomeId,
                                                                            long seed) {
        if (structures == null || !structures.isJsonArray()) return null;
        List<SpriteWorldgenCatalog.Entry> choices = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int total = 0;
        for (JsonElement element : structures.getAsJsonArray()) {
            Identifier id = null;
            int weight = 1;
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                id = identifier(element.getAsString());
            } else if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                id = identifier(jsonString(object, "structure", ""));
                if (id == null) id = identifier(jsonString(object, "id", ""));
                weight = Math.max(1, jsonInt(object, "weight", 1));
            }
            if (id == null || isUndergroundStructureId(id)) continue;
            SpriteWorldgenCatalog.Entry structure = SpriteWorldgenCatalog.find(
                    SpriteWorldgenCatalog.Kind.STRUCTURE, id).orElse(null);
            if (!structureAllowedInBiome(structure, biomeId) || !projectableStructure(structure)) continue;
            choices.add(structure);
            weights.add(weight);
            total += weight;
        }
        if (choices.isEmpty() || total <= 0) return null;
        int pick = Math.floorMod((int) (seed ^ (seed >>> 32)), total);
        for (int i = 0; i < choices.size(); i++) {
            pick -= weights.get(i);
            if (pick < 0) return choices.get(i);
        }
        return choices.get(choices.size() - 1);
    }

    private static boolean isUndergroundStructureId(Identifier id) {
        if (id == null) return false;
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("mineshaft") || path.contains("ancient_city") || path.contains("stronghold");
    }

    private static boolean structureAllowedInBiome(SpriteWorldgenCatalog.Entry structure, Identifier biomeId) {
        if (structure == null) return false;
        if (biomeId == null) return true;
        JsonObject json = SpriteWorldgenCatalog.readJson(structure).orElse(null);
        if (json == null || !json.has("biomes")) return false;
        return SpriteWorldgenCatalog.biomeSelectorContains(json.get("biomes"), biomeId);
    }

    private static Integer structureSetAnchorX(Request request, JsonObject placement,
                                               Identifier setId, int setOrdinal) {
        if (request == null || placement == null) return null;
        String type = compactType(jsonString(placement, "type", ""));
        // Strongholds/concentric rings are world-scale placement systems. Keep
        // automatic local previews to random-spread sets such as villages,
        // outposts, ruins, temples, and compatible modded sets.
        if (!type.contains("random_spread")) return null;

        int spacing = Math.max(1, jsonInt(placement, "spacing", 32));
        int separation = Math.max(0, Math.min(spacing - 1,
                jsonInt(placement, "separation", Math.max(0, spacing / 3))));
        int bound = Math.max(1, spacing - separation);
        int salt = jsonInt(placement, "salt", setId == null ? 0 : setId.hashCode());
        float frequency = Math.max(0.0F, Math.min(1.0F, jsonFloat(placement, "frequency", 1.0F)));
        boolean triangular = jsonString(placement, "spread_type", "linear")
                .toLowerCase(Locale.ROOT).contains("triangular");

        int minBlockX = request.centerX() - request.width() / 2;
        int maxBlockX = minBlockX + request.width() - 1;
        int minChunkX = Math.floorDiv(minBlockX, 16);
        int maxChunkX = Math.floorDiv(maxBlockX, 16);
        int minRegionX = Math.floorDiv(minChunkX, spacing);
        int maxRegionX = Math.floorDiv(maxChunkX, spacing);

        // Terrain slice presets are real sampled Minecraft Z coordinates. Use
        // those samples to test structure-set regions, while the actual structure
        // volume is still anchored around request.editPlane().
        LinkedHashSet<Integer> sourceChunksZ = new LinkedHashSet<>();
        for (int z : request.layers()) sourceChunksZ.add(Math.floorDiv(z, 16));
        if (sourceChunksZ.isEmpty()) sourceChunksZ.add(Math.floorDiv(request.editPlane(), 16));

        for (int chunkZ : sourceChunksZ) {
            int regionZ = Math.floorDiv(chunkZ, spacing);
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                long regionSeed = request.seed()
                        ^ ((long) salt * 0x9E3779B97F4A7C15L)
                        ^ ((long) regionX * 0x632BE59BD9B4E019L)
                        ^ ((long) regionZ * 0x8CB92BA72F3D8DD7L)
                        ^ ((long) (setOrdinal + 1) * 0xD6E8FEB86659FD93L);
                int offsetX = structureSpreadOffset(regionSeed, bound, triangular);
                int offsetZ = structureSpreadOffset(regionSeed ^ 0xA0761D6478BD642FL, bound, triangular);
                int candidateChunkX = regionX * spacing + offsetX;
                int candidateChunkZ = regionZ * spacing + offsetZ;
                if (candidateChunkX < minChunkX || candidateChunkX > maxChunkX || candidateChunkZ != chunkZ) continue;
                if (frequency < 1.0F && hash01(regionSeed ^ 0xE7037ED1A0B428DBL) >= frequency) continue;
                int blockX = candidateChunkX * 16 + 8;
                return Math.max(minBlockX + 4, Math.min(maxBlockX - 4, blockX));
            }
        }
        return null;
    }

    private static int structureSpreadOffset(long seed, int bound, boolean triangular) {
        if (bound <= 1) return 0;
        int a = Math.floorMod((int) (seed ^ (seed >>> 32)), bound);
        if (!triangular) return a;
        long secondSeed = seed ^ 0x94D049BB133111EBL;
        int b = Math.floorMod((int) (secondSeed ^ (secondSeed >>> 32)), bound);
        return (a + b) / 2;
    }

    private static boolean projectableStructure(SpriteWorldgenCatalog.Entry structure) {
        if (structure == null || structure.id() == null) return false;
        JsonObject json = SpriteWorldgenCatalog.readJson(structure).orElse(null);
        if (json != null && identifier(jsonString(json, "start_pool", "")) != null) return true;
        String needle = structure.id().getPath().toLowerCase(Locale.ROOT);
        String shortNeedle = needle.replace("_", "");
        for (SpriteWorldgenCatalog.Entry template
                : SpriteWorldgenCatalog.search(SpriteWorldgenCatalog.Kind.STRUCTURE_TEMPLATE, "")) {
            String path = template.id().getPath().toLowerCase(Locale.ROOT);
            String flat = path.replace("/", "").replace("_", "");
            if (path.contains(needle) || flat.contains(shortNeedle) || relatedStructure(needle, path)) return true;
        }
        return false;
    }


    private record UndergroundBiomeDefinition(SpriteWorldgenCatalog.Entry entry, JsonObject json,
                                               BiomeSceneStyle style, BlockState groundState) { }

private record CaveOpening(int floorAirY, int ceilingAirY) {
        boolean valid() { return floorAirY < ceilingAirY; }
    }

    private static boolean isUndergroundBiomeStyle(BiomeSceneStyle style) {
        return style == BiomeSceneStyle.LUSH_CAVE
                || style == BiomeSceneStyle.DRIPSTONE_CAVE
                || style == BiomeSceneStyle.DEEP_DARK
                || style == BiomeSceneStyle.CAVE_GENERIC;
    }


    /**
     * Detached approximation of Minecraft 1.20.1's cave density composition.
     * The important invariant is architectural: caves are a continuous 3D
     * density field sampled by every Koil Z slice, not hand-authored rooms.
     */
    private static Result carveVanillaInspiredCaves(Scene scene, Request request,
                                                     Identifier biomeId,
                                                     BiomeSliceProfile profile,
                                                     List<Identifier> configuredCarvers) {
        if (scene == null || request == null) return new Result(0, 0, 0, "caves=0");
        boolean canyonConfigured = false;
        if (configuredCarvers != null) {
            for (Identifier id : configuredCarvers) {
                if (id != null && id.getPath().toLowerCase(Locale.ROOT).contains("canyon")) {
                    canyonConfigured = true;
                    break;
                }
            }
        }

        int minX = request.centerX() - request.width() / 2;
        int maxX = minX + request.width() - 1;
        Map<SceneCellPos, CaveDensityDecision> rawMask = new HashMap<>();

        // Build the full mask first. Applying each noise hit immediately made
        // isolated one-cell voids impossible to distinguish from coherent caves.
        for (int layer : request.layers()) {
            int sourceZ = biomeSourceZ(request.layers(), layer);
            for (int x = minX; x <= maxX; x++) {
                int surfaceAir = findTerrainSurfaceY(scene, x, layer, request.surfaceY());
                int surfaceY = surfaceAir - 1;
                int bottom = Math.max(request.surfaceY() - 127, surfaceY - 150);
                for (int y = surfaceY; y >= bottom; y--) {
                    SceneCellPos pos = new SceneCellPos(x, y, layer);
                    if (!carvableGeneratedTerrain(scene, pos)) continue;
                    int depthBelowSurface = surfaceY - y;
                    CaveDensityDecision decision = caveDensityDecision(x, y, sourceZ,
                            depthBelowSurface, surfaceY, request, profile, canyonConfigured);
                    if (!decision.carve()) continue;
                    rawMask.put(pos, decision);
                }
            }
        }

        // Close one-cell stone plugs that split an otherwise continuous tunnel or
        // cavern. Horizontal and vertical bridges are deliberately limited to one
        // missing cell, so this cannot invent long artificial corridors.
        Map<SceneCellPos, CaveDensityDecision> bridged = new HashMap<>(rawMask);
        for (SceneCellPos pos : new ArrayList<>(rawMask.keySet())) {
            bridgeCaveGap(scene, rawMask, bridged, pos, 2, 0);
            bridgeCaveGap(scene, rawMask, bridged, pos, 0, 2);
        }

        // Remove detached specks. A valid cave cell must participate in a visible
        // local run. Strong surface entrances are allowed with one neighbor so a
        // narrow mouth is not sealed by cleanup.
        Map<SceneCellPos, CaveDensityDecision> finalMask = new HashMap<>();
        for (Map.Entry<SceneCellPos, CaveDensityDecision> entry : bridged.entrySet()) {
            SceneCellPos pos = entry.getKey();
            CaveDensityDecision decision = entry.getValue();
            int neighbors = caveMaskNeighbors(bridged, pos);
            int minimum = decision.entrance() ? 1 : 2;
            if (neighbors >= minimum) finalMask.put(pos, decision);
        }

        int removed = 0;
        int entranceCells = 0;
        int tunnelCells = 0;
        int cavernCells = 0;
        for (Map.Entry<SceneCellPos, CaveDensityDecision> entry : finalMask.entrySet()) {
            SceneCellPos pos = entry.getKey();
            if (!carvableGeneratedTerrain(scene, pos)) continue;
            clearCell(scene, pos);
            removed++;
            CaveDensityDecision decision = entry.getValue();
            if (decision.entrance()) entranceCells++;
            else if (decision.cavern()) cavernCells++;
            else tunnelCells++;
        }
        int pieces = removed > 0 ? 1 : 0;
        return new Result(0, 0, pieces,
                "density caves removed " + removed + " | entrances " + entranceCells
                        + " | caverns " + cavernCells + " | tunnels " + tunnelCells);
    }

    private static boolean carvableGeneratedTerrain(Scene scene, SceneCellPos pos) {
        if (scene == null || pos == null || !generatedCell(scene, pos)) return false;
        var cell = scene.blocks().get(pos);
        if (cell == null || cell.blockState() == null || cell.blockState().isAir()
                || cell.blockState().isOf(Blocks.BEDROCK)) return false;
        String role = cell.runtimeData().get("generated_role");
        return "stone".equals(role) || "deep_stone".equals(role)
                || "terrain".equals(role) || "filler".equals(role) || "surface".equals(role);
    }

    private static void bridgeCaveGap(Scene scene, Map<SceneCellPos, CaveDensityDecision> source,
                                      Map<SceneCellPos, CaveDensityDecision> target, SceneCellPos start,
                                      int dx, int dy) {
        SceneCellPos far = new SceneCellPos(start.x() + dx, start.y() + dy, start.depth());
        if (!source.containsKey(far)) return;
        SceneCellPos middle = new SceneCellPos(start.x() + dx / 2, start.y() + dy / 2, start.depth());
        if (source.containsKey(middle) || !carvableGeneratedTerrain(scene, middle)) return;
        target.put(middle, new CaveDensityDecision(true, false,
                source.get(start).cavern() || source.get(far).cavern()));
    }

    private static int caveMaskNeighbors(Map<SceneCellPos, CaveDensityDecision> mask, SceneCellPos pos) {
        if (mask == null || pos == null) return 0;
        int count = 0;
        if (mask.containsKey(new SceneCellPos(pos.x() - 1, pos.y(), pos.depth()))) count++;
        if (mask.containsKey(new SceneCellPos(pos.x() + 1, pos.y(), pos.depth()))) count++;
        if (mask.containsKey(new SceneCellPos(pos.x(), pos.y() - 1, pos.depth()))) count++;
        if (mask.containsKey(new SceneCellPos(pos.x(), pos.y() + 1, pos.depth()))) count++;
        if (mask.containsKey(new SceneCellPos(pos.x(), pos.y(), pos.depth() - 1))) count++;
        if (mask.containsKey(new SceneCellPos(pos.x(), pos.y(), pos.depth() + 1))) count++;
        return count;
    }

    private record CaveDensityDecision(boolean carve, boolean entrance, boolean cavern) { }

    private static CaveDensityDecision caveDensityDecision(int x, int y, int sourceZ,
                                                            int depthBelowSurface, int surfaceY,
                                                            Request request, BiomeSliceProfile profile,
                                                            boolean canyonConfigured) {
        if (depthBelowSurface < 0) return new CaveDensityDecision(false, false, false);
        long seed = request.seed();
        int worldY = 63 + (y - request.surfaceY());

        // Broad cheese caverns. Threshold rises near the surface so large rooms
        // remain predominantly underground, while deep terrain can open up.
        float cheese = fbm3D(x * 0.018F, y * 0.020F, sourceZ * 0.018F,
                seed ^ 0xDB4F0B9175AE2165L, 4);
        float cheeseWarp = fbm3D(x * 0.041F, y * 0.035F, sourceZ * 0.041F,
                seed ^ 0xBBE0563303A4615FL, 2) * 0.13F;
        float cheeseThreshold = depthBelowSurface < 14 ? 0.69F : worldY < 0 ? 0.47F : 0.53F;
        boolean cavern = cheese + cheeseWarp > cheeseThreshold;

        // Spaghetti uses two signed fields whose near-zero intersection creates
        // long twisting tunnels. A roughness field varies thickness locally.
        float spaghettiA = Math.abs(fbm3D(x * 0.050F, y * 0.046F, sourceZ * 0.050F,
                seed ^ 0x94D049BB133111EBL, 3));
        float spaghettiB = Math.abs(fbm3D(x * 0.047F, y * 0.052F, sourceZ * 0.047F,
                seed ^ 0x369DEA0F31A53F85L, 3));
        float roughness = fbm3D(x * 0.026F, y * 0.031F, sourceZ * 0.026F,
                seed ^ 0xD6E8FEB86659FD93L, 2);
        float spaghettiThickness = 0.135F + Math.max(0.0F, roughness) * 0.055F;
        boolean spaghetti = depthBelowSurface > 7
                && (spaghettiA + spaghettiB) < spaghettiThickness;

        // Noodle caves are thinner and prefer deeper bands. An activation field
        // prevents the entire world from becoming a uniform lattice.
        float noodleGate = fbm3D(x * 0.013F, y * 0.018F, sourceZ * 0.013F,
                seed ^ 0xA24BAED4963EE407L, 2);
        float noodleA = Math.abs(fbm3D(x * 0.082F, y * 0.071F, sourceZ * 0.082F,
                seed ^ 0xC6BC279692B5C323L, 2));
        float noodleB = Math.abs(fbm3D(x * 0.079F, y * 0.085F, sourceZ * 0.079F,
                seed ^ 0x9E3779B97F4A7C15L, 2));
        boolean noodle = depthBelowSurface > 18 && worldY < 48 && noodleGate > 0.08F
                && noodleA < 0.105F && noodleB < 0.105F;

        // Entrances are low-frequency cuts allowed to meet the actual surface.
        // There is deliberately no viewport-edge falloff, so an entrance/tunnel
        // may continue cleanly out of either side of the playground.
        float entranceCore = Math.abs(fbm3D(x * 0.025F, y * 0.033F, sourceZ * 0.025F,
                seed ^ 0x68E31DA4B5297A4DL, 3));
        float entranceShape = fbm2D(x * 0.011F, sourceZ * 0.011F,
                seed ^ 0x1B03738712FAD5C9L, 3);
        float entranceLimit = 0.105F + Math.max(0.0F, entranceShape) * 0.055F;
        boolean entrance = depthBelowSurface <= 28 && entranceCore < entranceLimit;

        // Configured canyon presence contributes broad vertical fissures rather
        // than invoking a second cell-random destructive carver pass.
        boolean canyon = false;
        if (canyonConfigured && depthBelowSurface > 5) {
            float canyonAxis = Math.abs(fbm2D(x * 0.012F, sourceZ * 0.012F,
                    seed ^ 0x632BE59BD9B4E019L, 4));
            float canyonWall = fbm3D(x * 0.030F, y * 0.018F, sourceZ * 0.030F,
                    seed ^ 0xE7037ED1A0B428DBL, 2);
            canyon = canyonAxis < 0.075F && canyonWall > -0.22F;
        }

        // Pillars are positive density islands inside large caves. They keep
        // cheese caverns from reading as featureless empty blobs.
        float pillarRarity = fbm3D(x * 0.020F, y * 0.024F, sourceZ * 0.020F,
                seed ^ 0x8CB92BA72F3D8DD7L, 2);
        float pillarThickness = Math.abs(fbm3D(x * 0.070F, y * 0.030F, sourceZ * 0.070F,
                seed ^ 0xA0761D6478BD642FL, 2));
        boolean pillar = pillarRarity > 0.52F && pillarThickness < 0.115F;

        boolean carve = (cavern || spaghetti || noodle || entrance || canyon) && !pillar;
        // Keep the immediate terrain skin intact unless this is a strong cave
        // entrance. Very few entrance rays are allowed to remove the actual
        // surface block, which gives caves real mouths without swiss-cheesing
        // every hill.
        if (depthBelowSurface < 2) {
            carve = entrance && entranceCore < entranceLimit * 0.72F;
        } else if (depthBelowSurface < 5 && !entrance) {
            carve = false;
        }
        return new CaveDensityDecision(carve, entrance, cavern);
    }

    /** Seeds low cave floors with detached aquifer pockets after the cave mask is
     * stable. Water dominates ordinary underground cavities while very deep
     * cavities can become lava, matching the visual role of vanilla aquifers
     * without invoking a ServerWorld fluid picker. */
    private static Result seedUndergroundPools(Scene scene, Request request) {
        if (scene == null || request == null) return new Result(0, 0, 0, "aquifers=0");
        int minX = request.centerX() - request.width() / 2;
        int maxX = minX + request.width() - 1;
        int fluidCells = 0;
        int pools = 0;

        for (int layer : request.layers()) {
            for (int x = minX + 3; x <= maxX - 3; x += 7) {
                int surfaceAir = findTerrainSurfaceY(scene, x, layer, request.surfaceY());
                int minY = Math.max(request.surfaceY() - 124, surfaceAir - 142);
                List<CaveOpening> openings = findCaveOpenings(scene, x, layer, minY, surfaceAir - 5);
                for (CaveOpening opening : openings) {
                    int height = opening.ceilingAirY() - opening.floorAirY() + 1;
                    if (height < 4) continue;
                    long salt = request.seed() ^ ((long) x * 0x9E3779B97F4A7C15L)
                            ^ ((long) layer * 0xD1B54A32D192ED03L)
                            ^ ((long) opening.floorAirY() * 0x94D049BB133111EBL);
                    if (hash01(salt) < 0.79F) continue;

                    int worldY = 63 + (opening.floorAirY() - request.surfaceY());
                    Fluid fluid = worldY <= -48 && hash01(salt ^ 0xA0761D6478BD642FL) > 0.36F
                            ? Fluids.LAVA : Fluids.WATER;
                    int radius = hash01(salt ^ 0xE7037ED1A0B428DBL) > 0.72F ? 2 : 1;
                    int level = opening.floorAirY() + (height >= 7 ? 1 : 0);
                    int before = fluidCells;
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int fy = opening.floorAirY(); fy <= level; fy++) {
                            SceneCellPos pos = new SceneCellPos(x + dx, fy, layer);
                            if (scene.blocks().occupied(pos) || !scene.fluids().getFluidState(pos).isEmpty()) continue;
                            if (fy == opening.floorAirY() && !solidAt(scene, x + dx, fy - 1, layer)) continue;
                            scene.fluidSystem().setSceneSource(pos, fluid);
                            fluidCells++;
                        }
                    }
                    if (fluidCells > before) pools++;
                    break; // one local pool per sampled column
                }
            }
        }
        return new Result(0, fluidCells, pools, "aquifers=" + pools);
    }

    /** Adds an underground structure pass distinct from surface structure sets.
     * The catalog still decides whether the current biome owns a mineshaft
     * structure; Koil only supplies a detached side-view projection of it. */
    private static Result maybeGenerateUndergroundStructure(SpriteEngine engine, Request request, Identifier biomeId) {
        if (engine == null || request == null || biomeId == null || request.width() < 72) {
            return new Result(0, 0, 0, "underground_structure=none");
        }
        SpriteWorldgenCatalog.Entry mineshaft = null;
        for (SpriteWorldgenCatalog.Entry candidate
                : SpriteWorldgenCatalog.search(SpriteWorldgenCatalog.Kind.STRUCTURE, "mineshaft")) {
            if (structureAllowedInBiome(candidate, biomeId)) {
                mineshaft = candidate;
                break;
            }
        }
        if (mineshaft == null) return new Result(0, 0, 0, "underground_structure=none");

        long seed = request.seed() ^ ((long) biomeId.hashCode() * 0x9E3779B97F4A7C15L);
        if (hash01(seed ^ 0xC6BC279692B5C323L) < 0.38F) {
            return new Result(0, 0, 0, "underground_structure=none");
        }
        int plane = request.editPlane();
        int minX = request.centerX() - request.width() / 2 + 10;
        int span = Math.max(1, request.width() - 20);
        int centerX = minX + Math.floorMod((int) (seed ^ (seed >>> 32)), span);
        int surface = findTerrainSurfaceY(engine.scene(), centerX, plane, request.surfaceY());
        int centerY = surface - 24 - Math.floorMod((int) (seed >>> 11), 30);
        int length = Math.max(18, Math.min(46, request.width() / 2));
        int blocks = placeMineshaftProjection(engine.scene(), centerX, centerY, plane, length, seed, request.layers());
        return new Result(blocks, 0, blocks > 0 ? 1 : 0,
                blocks > 0 ? "underground_structure=" + mineshaft.id() : "underground_structure=none",
                blocks > 0 ? new SceneCellPos(centerX, centerY + 1, plane) : null);
    }

    private static int placeMineshaftProjection(Scene scene, int centerX, int floorY, int layer,
                                                int length, long seed, List<Integer> selectedLayers) {
        int placed = 0;
        int half = Math.max(8, length / 2);
        for (int x = centerX - half; x <= centerX + half; x++) {
            // Carve a four-block-high gallery through generated geology.
            for (int y = floorY; y <= floorY + 3; y++) {
                SceneCellPos air = new SceneCellPos(x, y, layer);
                if (generatedCell(scene, air) || !scene.fluids().getFluidState(air).isEmpty()) clearCell(scene, air);
            }

            SceneCellPos railPos = new SceneCellPos(x, floorY, layer);
            if (solidAt(scene, x, floorY - 1, layer) && ((x - centerX) & 3) != 1) {
                if (set(scene, railPos, Blocks.RAIL.getDefaultState(), false)) placed++;
            }

            if (Math.floorMod(x - (centerX - half), 5) == 0) {
                // Ceiling beam is visible in side view without blocking the tunnel.
                for (int bx = x - 1; bx <= x + 1; bx++) {
                    if (set(scene, new SceneCellPos(bx, floorY + 3, layer),
                            Blocks.OAK_PLANKS.getDefaultState(), true)) placed++;
                }
                // Fence supports live on adjacent selected depth where available,
                // reproducing the shallow 3D support frame without sealing passage.
                for (int supportDepth : new int[]{layer - 1, layer + 1}) {
                    if (selectedLayers == null || !selectedLayers.contains(supportDepth)) continue;
                    for (int y = floorY; y <= floorY + 2; y++) {
                        if (set(scene, new SceneCellPos(x, y, supportDepth),
                                Blocks.OAK_FENCE.getDefaultState(), false)) placed++;
                    }
                }
            } else if (hash01(seed ^ ((long) x * 0xDB4F0B9175AE2165L)) > 0.90F) {
                SceneCellPos web = new SceneCellPos(x, floorY + 2, layer);
                if (set(scene, web, Blocks.COBWEB.getDefaultState(), false)) placed++;
            }
        }
        return placed;
    }

    /**
     * Paints cave-biome surfaces and features onto openings produced by the
     * density field. It never carves geometry itself, so biome classification
     * cannot regress the cave shape into circles/corridors.
     */
    private static Result decorateDensityCaveBiomes(SpriteEngine engine, Request request,
                                                    SpriteWorldgenCatalog.Entry surfaceBiome,
                                                    JsonObject surfaceBiomeJson,
                                                    BiomeSliceProfile profile) {
        List<UndergroundBiomeDefinition> candidates = discoverUndergroundBiomes(surfaceBiome);
        if (candidates.isEmpty()) return new Result(0, 0, 0, "regions=0 features=0");
        Scene scene = engine.scene();
        float downfall = jsonFloat(surfaceBiomeJson, "downfall", 0.4F);
        int minX = request.centerX() - request.width() / 2;
        int maxX = minX + request.width() - 1;
        int surfacePaint = 0;
        int featureBlocks = 0;
        int fluids = 0;
        int classified = 0;
        Set<String> regionKeys = new HashSet<>();

        Set<Identifier> surfaceFeatures = new HashSet<>();
        if (surfaceBiomeJson != null && surfaceBiomeJson.has("features")) {
            for (BiomeFeatureRef ref : collectBiomeFeatureRefs(surfaceBiomeJson.get("features"))) {
                if (ref != null && ref.id() != null) surfaceFeatures.add(ref.id());
            }
        }

        for (int layer : request.layers()) {
            int sourceZ = biomeSourceZ(request.layers(), layer);
            for (int x = minX; x <= maxX; x++) {
                int surfaceAir = findTerrainSurfaceY(scene, x, layer, request.surfaceY());
                int minY = Math.max(request.surfaceY() - 126, surfaceAir - 145);
                List<CaveOpening> openings = findCaveOpenings(scene, x, layer, minY, surfaceAir - 3);
                for (CaveOpening opening : openings) {
                    if (!opening.valid() || opening.ceilingAirY() - opening.floorAirY() < 4) continue;
                    int midY = (opening.floorAirY() + opening.ceilingAirY()) / 2;
                    UndergroundBiomeDefinition biome = selectDensityCaveBiome(candidates,
                            surfaceBiome, x, sourceZ, midY, surfaceAir, request, downfall);
                    if (biome == null) continue;
                    classified++;
                    regionKeys.add(biome.entry().id() + ":" + Math.floorDiv(x, 18) + ":" + layer);

                    surfacePaint += paintDensityCaveSurfaces(scene, biome, x, layer,
                            sourceZ, opening, request);

                    // Sparse, deterministic cave-biome decoration. Features are
                    // sourced from the biome data, but shared surface/ore passes
                    // are excluded to avoid multiplying geology by depth count.
                    if (Math.floorMod(x - minX, 6) == 0) {
                        Result feature = placeDensityCaveFeature(scene, biome, surfaceFeatures,
                                x, layer, opening, request.seed() ^ ((long) sourceZ << 32));
                        featureBlocks += feature.blocks();
                        fluids += feature.fluids();
                    }
                }
            }
        }
        return new Result(surfacePaint + featureBlocks, fluids, classified > 0 ? 1 : 0,
                "regions=" + regionKeys.size() + " features=" + featureBlocks);
    }

    private static List<CaveOpening> findCaveOpenings(Scene scene, int x, int layer,
                                                       int minY, int maxY) {
        List<CaveOpening> out = new ArrayList<>();
        int y = minY;
        while (y <= maxY) {
            while (y <= maxY && solidAt(scene, x, y, layer)) y++;
            if (y > maxY) break;
            int start = y;
            while (y <= maxY && !solidAt(scene, x, y, layer)) y++;
            int end = y - 1;
            if (start <= end && solidAt(scene, x, start - 1, layer)
                    && solidAt(scene, x, end + 1, layer)) {
                out.add(new CaveOpening(start, end));
            }
        }
        return out;
    }

    private static UndergroundBiomeDefinition selectDensityCaveBiome(
            List<UndergroundBiomeDefinition> candidates,
            SpriteWorldgenCatalog.Entry surfaceBiome,
            int x, int sourceZ, int y, int surfaceAir,
            Request request, float downfall) {
        if (candidates == null || candidates.isEmpty()) return null;
        String surfacePath = surfaceBiome == null || surfaceBiome.id() == null
                ? "" : surfaceBiome.id().getPath().toLowerCase(Locale.ROOT);
        int macroX = Math.floorDiv(x, 18) * 18 + 9;
        TerrainSample terrain = sampleVanillaTerrain(macroX, sourceZ, request.seed(), surfacePath);
        int worldY = 63 + (y - request.surfaceY());
        int surfaceHeight = surfaceAir - request.surfaceY();
        float humidityNoise = fbm2D(macroX * 0.007F, sourceZ * 0.007F,
                request.seed() ^ 0xB5297A4D5C6B36A1L, 4);
        float humidity = Math.max(0.0F, Math.min(1.0F,
                downfall * 0.68F + (humidityNoise * 0.5F + 0.5F) * 0.32F));

        UndergroundBiomeDefinition deepDark = caveCandidate(candidates, BiomeSceneStyle.DEEP_DARK);
        UndergroundBiomeDefinition dripstone = caveCandidate(candidates, BiomeSceneStyle.DRIPSTONE_CAVE);
        UndergroundBiomeDefinition lush = caveCandidate(candidates, BiomeSceneStyle.LUSH_CAVE);

        // Vanilla's Deep Dark selector is low erosion, which correlates strongly
        // with mountain terrain. Require both low erosion and a raised surface so
        // flat plains cannot receive arbitrary Deep Dark patches.
        if (deepDark != null && worldY <= 8 && terrain.erosion() < -0.38F
                && surfaceHeight >= 8) return deepDark;
        if (dripstone != null && worldY <= 48 && terrain.continents() > 0.42F) return dripstone;
        if (lush != null && worldY <= 48 && humidity > 0.67F) return lush;

        // Modded cave biomes participate as coherent macro-regions, not a random
        // choice per block. Vanilla generic stone caves remain unclassified.
        List<UndergroundBiomeDefinition> generic = new ArrayList<>();
        for (UndergroundBiomeDefinition candidate : candidates) {
            if (candidate.style() == BiomeSceneStyle.CAVE_GENERIC) generic.add(candidate);
        }
        if (!generic.isEmpty()) {
            float gate = hash01(request.seed() ^ ((long) Math.floorDiv(x, 24) * 0x9E3779B97F4A7C15L)
                    ^ ((long) sourceZ * 0xD1B54A32D192ED03L));
            if (gate > 0.82F) return generic.get(Math.floorMod((int) (gate * 100000.0F), generic.size()));
        }
        return null;
    }

    private static UndergroundBiomeDefinition caveCandidate(List<UndergroundBiomeDefinition> candidates,
                                                             BiomeSceneStyle style) {
        for (UndergroundBiomeDefinition candidate : candidates) if (candidate.style() == style) return candidate;
        return null;
    }

    private static int paintDensityCaveSurfaces(Scene scene, UndergroundBiomeDefinition biome,
                                                int x, int layer, int sourceZ,
                                                CaveOpening opening, Request request) {
        int placed = 0;
        long seed = request.seed() ^ ((long) x * 0x632BE59BD9B4E019L)
                ^ ((long) sourceZ * 0x9E3779B97F4A7C15L);
        SceneCellPos floorPos = new SceneCellPos(x, opening.floorAirY() - 1, layer);
        SceneCellPos ceilPos = new SceneCellPos(x, opening.ceilingAirY() + 1, layer);
        BlockState floor = scene.blocks().getBlockState(floorPos);
        BlockState ceiling = scene.blocks().getBlockState(ceilPos);
        int worldY = 63 + (((opening.floorAirY() + opening.ceilingAirY()) / 2) - request.surfaceY());
        float pick = hash01(seed);

        BlockState floorState = floor;
        BlockState ceilingState = ceiling;
        switch (biome.style()) {
            case LUSH_CAVE -> {
                floorState = pick < 0.14F ? Blocks.CLAY.getDefaultState()
                        : pick < 0.78F ? Blocks.MOSS_BLOCK.getDefaultState() : floor;
                if (hash01(seed ^ 0x94D049BB133111EBL) > 0.70F) ceilingState = Blocks.MOSS_BLOCK.getDefaultState();
            }
            case DRIPSTONE_CAVE -> {
                if (pick < 0.58F) floorState = Blocks.DRIPSTONE_BLOCK.getDefaultState();
                if (hash01(seed ^ 0x369DEA0F31A53F85L) < 0.58F) ceilingState = Blocks.DRIPSTONE_BLOCK.getDefaultState();
            }
            case DEEP_DARK -> {
                BlockState deepBase = worldY < 0 ? Blocks.DEEPSLATE.getDefaultState() : floor;
                floorState = pick < 0.56F ? Blocks.SCULK.getDefaultState() : deepBase;
                ceilingState = worldY < 0 ? Blocks.DEEPSLATE.getDefaultState() : ceiling;
            }
            case CAVE_GENERIC -> {
                BlockState configured = biome.groundState();
                if (configured != null && !configured.isAir() && pick < 0.72F) floorState = configured;
            }
            default -> { }
        }
        if (floorState != null && !floorState.isAir() && generatedCell(scene, floorPos)) {
            scene.blocks().setSceneOwned(floorPos, floorState);
            scene.blocks().putRuntimeData(floorPos, "generated_sub_biome", biome.entry().id().toString());
            scene.blocks().putRuntimeData(floorPos, "generated_role", "cave_floor");
            placed++;
        }
        if (ceilingState != null && !ceilingState.isAir() && generatedCell(scene, ceilPos)) {
            scene.blocks().setSceneOwned(ceilPos, ceilingState);
            scene.blocks().putRuntimeData(ceilPos, "generated_sub_biome", biome.entry().id().toString());
            scene.blocks().putRuntimeData(ceilPos, "generated_role", "cave_ceiling");
            placed++;
        }
        return placed;
    }

    private static Result placeDensityCaveFeature(Scene scene, UndergroundBiomeDefinition biome,
                                                  Set<Identifier> surfaceFeatures,
                                                  int x, int layer, CaveOpening opening,
                                                  long seed) {
        if (biome == null || biome.json() == null || !biome.json().has("features")) {
            return new Result(0, 0, 0, "features=0");
        }
        List<BiomeFeatureRef> refs = collectBiomeFeatureRefs(biome.json().get("features"));
        List<BiomeFeatureRef> eligible = new ArrayList<>();
        for (BiomeFeatureRef ref : refs) {
            if (ref == null || ref.id() == null) continue;
            if (surfaceFeatures != null && surfaceFeatures.contains(ref.id())) continue;
            String path = ref.id().getPath().toLowerCase(Locale.ROOT);
            if (path.contains("ore") || path.contains("geode") || path.contains("lake")
                    || path.contains("monster_room")) continue;
            if (path.contains("cave") || path.contains("dripstone") || path.contains("sculk")
                    || path.contains("moss") || path.contains("lichen") || path.contains("spore")
                    || path.contains("vine") || path.contains("clay")) eligible.add(ref);
        }
        if (eligible.isEmpty()) return new Result(0, 0, 0, "features=0");
        BiomeFeatureRef ref = eligible.get(Math.floorMod((int) (seed ^ (seed >>> 32)), eligible.size()));
        SpriteWorldgenCatalog.Entry feature = resolveFeatureEntry(ref.id());
        if (feature == null) return new Result(0, 0, 0, "features=0");
        JsonObject root = SpriteWorldgenCatalog.readJson(feature).orElse(null);
        if (root == null) return new Result(0, 0, 0, "features=0");
        if (feature.kind() == SpriteWorldgenCatalog.Kind.PLACED_FEATURE) {
            Identifier configured = identifier(jsonString(root, "feature", ""));
            SpriteWorldgenCatalog.Entry configuredEntry = resolveFeatureEntry(configured);
            if (configuredEntry != null) root = SpriteWorldgenCatalog.readJson(configuredEntry).orElse(root);
        }
        String type = jsonString(root, "type", feature.summary()).toLowerCase(Locale.ROOT);
        JsonObject config = object(root, "config");
        if (config == null) config = new JsonObject();
        return placeUndergroundFeature(scene, ref.id(), type, config, x, layer, opening, seed);
    }

    /**
     * Builds underground biome pockets inside a generated overworld body.
     *
     * The selected surface biome remains the scene/environment biome. Cave
     * biomes are local authored regions inside stone/deepslate, matching the
     * fact that Minecraft biomes are 3D rather than a single biome per column.
     * Koil remains detached from ChunkGenerator/WorldChunk ownership: the
     * loaded biome resources select the underground content and feature graph,
     * while this method fabricates a readable 2D cross-section of it.
     */

    private static List<UndergroundBiomeDefinition> discoverUndergroundBiomes(
            SpriteWorldgenCatalog.Entry surfaceBiome) {
        List<UndergroundBiomeDefinition> out = new ArrayList<>();
        Set<Identifier> seen = new LinkedHashSet<>();

        // Always seed the three vanilla cave biomes explicitly when they are
        // present in the loaded catalog. The generic catalog search remains the
        // mod-compatibility path below, but these anchors guarantee that a
        // normal surface scene can actually contain lush, dripstone and deep
        // dark terrain instead of whichever cave-like resource happened to be
        // encountered first.
        addUndergroundBiomeCandidate(out, seen, surfaceBiome, new Identifier("minecraft", "lush_caves"));
        addUndergroundBiomeCandidate(out, seen, surfaceBiome, new Identifier("minecraft", "dripstone_caves"));
        addUndergroundBiomeCandidate(out, seen, surfaceBiome, new Identifier("minecraft", "deep_dark"));

        for (SpriteWorldgenCatalog.Entry candidate
                : SpriteWorldgenCatalog.search(SpriteWorldgenCatalog.Kind.BIOME, "")) {
            if (candidate == null || candidate.id() == null || seen.contains(candidate.id())) continue;
            if (surfaceBiome != null && candidate.id().equals(surfaceBiome.id())) continue;

            UndergroundBiomeDefinition definition = undergroundBiomeDefinition(candidate);
            if (definition == null) continue;
            out.add(definition);
            seen.add(candidate.id());
            if (out.size() >= 96) break;
        }

        // Keep the vanilla cave family stable at the front of the list, then
        // deterministic ID ordering for modded/generic cave biomes.
        out.sort((a, b) -> {
            int ar = undergroundBiomeSortRank(a);
            int br = undergroundBiomeSortRank(b);
            if (ar != br) return Integer.compare(ar, br);
            return a.entry().id().toString().compareTo(b.entry().id().toString());
        });
        return out;
    }

    private static void addUndergroundBiomeCandidate(List<UndergroundBiomeDefinition> out,
                                                     Set<Identifier> seen,
                                                     SpriteWorldgenCatalog.Entry surfaceBiome,
                                                     Identifier id) {
        if (id == null || seen.contains(id)) return;
        SpriteWorldgenCatalog.Entry entry = SpriteWorldgenCatalog
                .find(SpriteWorldgenCatalog.Kind.BIOME, id).orElse(null);
        if (entry == null || (surfaceBiome != null && id.equals(surfaceBiome.id()))) return;
        UndergroundBiomeDefinition definition = undergroundBiomeDefinition(entry);
        if (definition == null) return;
        out.add(definition);
        seen.add(id);
    }

    private static UndergroundBiomeDefinition undergroundBiomeDefinition(
            SpriteWorldgenCatalog.Entry candidate) {
        if (candidate == null || candidate.id() == null) return null;
        JsonObject json = SpriteWorldgenCatalog.readJson(candidate).orElse(null);
        if (json == null) return null;
        float temperature = jsonFloat(json, "temperature", 0.5F);
        float downfall = jsonFloat(json, "downfall", 0.5F);
        BiomeSceneStyle style = resolveBiomeSceneStyle(candidate.id(), json, temperature, downfall);
        if (!isUndergroundBiomeStyle(style)) return null;

        BiomeSliceProfile base = BiomeSliceProfile.resolve(candidate.id(), temperature, downfall);
        BiomeSliceProfile adapted = adaptModdedProfileFromFeatures(base, candidate.id(), json);
        BlockState ground = undergroundGroundState(style,
                adapted == null ? Blocks.STONE.getDefaultState() : adapted.surface(), json);
        return new UndergroundBiomeDefinition(candidate, json, style, ground);
    }

    private static int undergroundBiomeSortRank(UndergroundBiomeDefinition biome) {
        if (biome == null) return 99;
        return switch (biome.style()) {
            case LUSH_CAVE -> 0;
            case DRIPSTONE_CAVE -> 1;
            case DEEP_DARK -> 2;
            case CAVE_GENERIC -> 3;
            default -> 10;
        };
    }




    private static BlockState undergroundGroundState(BiomeSceneStyle style,
                                                     BlockState configured,
                                                     JsonObject biomeJson) {
        return switch (style) {
            case LUSH_CAVE -> Blocks.MOSS_BLOCK.getDefaultState();
            case DRIPSTONE_CAVE -> Blocks.DRIPSTONE_BLOCK.getDefaultState();
            case DEEP_DARK -> Blocks.SCULK.getDefaultState();
            case CAVE_GENERIC -> {
                BlockState fromFeatures = undergroundGroundFromFeatureGraph(biomeJson);
                yield fromFeatures == null || fromFeatures.isAir()
                        ? (configured == null || configured.isAir()
                        ? Blocks.STONE.getDefaultState() : configured)
                        : fromFeatures;
            }
            default -> configured == null ? Blocks.STONE.getDefaultState() : configured;
        };
    }

    private static BlockState undergroundGroundFromFeatureGraph(JsonObject biomeJson) {
        if (biomeJson == null || !biomeJson.has("features")) return null;
        for (BiomeFeatureRef ref : collectBiomeFeatureRefs(biomeJson.get("features"))) {
            SpriteWorldgenCatalog.Entry entry = resolveFeatureEntry(ref.id());
            if (entry == null) continue;
            JsonObject root = SpriteWorldgenCatalog.readJson(entry).orElse(null);
            if (root == null) continue;
            if (entry.kind() == SpriteWorldgenCatalog.Kind.PLACED_FEATURE) {
                Identifier configured = identifier(jsonString(root, "feature", ""));
                SpriteWorldgenCatalog.Entry configuredEntry = resolveFeatureEntry(configured);
                if (configuredEntry != null) {
                    root = SpriteWorldgenCatalog.readJson(configuredEntry).orElse(root);
                }
            }
            String type = compactType(jsonString(root, "type", "").toLowerCase(Locale.ROOT));
            JsonObject config = object(root, "config");
            if (config == null) continue;
            if (type.contains("vegetation_patch")) {
                BlockState ground = firstProviderState(config.get("ground_state"));
                if (ground != null && !ground.isAir()) return ground;
            }
        }
        return null;
    }




    /**
     * Carves a broad connector between two authored cave-biome regions. The
     * connector keeps the region-specific floor/ceiling treatments on each side
     * so transitions read like one continuous cave system rather than a hallway
     * punched between unrelated bubbles.
     */






    private static Result placeUndergroundFeature(Scene scene,
                                                  Identifier featureId, String type,
                                                  JsonObject config, int x, int layer,
                                                  CaveOpening opening, long seed) {
        String key = ((featureId == null ? "" : featureId.getPath()) + " " + type)
                .toLowerCase(Locale.ROOT);
        int placed = 0;
        int fluids = 0;

        if (key.contains("dripstone")) {
            placed += placeCaveDripstone(scene, x, layer, opening, seed,
                    key.contains("large_dripstone"));
            return new Result(placed, 0, 1, "dripstone");
        }

        if (key.contains("sculk")) {
            placed += placeCaveSculk(scene, x, layer, opening, config, seed);
            return new Result(placed, 0, 1, "sculk");
        }

        if (key.contains("cave_vine") || key.contains("weeping_vine")
                || key.contains("spore_blossom")) {
            List<BlockState> states = caveFeatureStates(type, config);
            BlockState state = firstUsefulCaveState(states);
            if (state == null) {
                state = key.contains("spore_blossom")
                        ? Blocks.SPORE_BLOSSOM.getDefaultState()
                        : Blocks.CAVE_VINES.getDefaultState();
            }
            placed += placeHangingCaveFeature(scene, x, layer, opening,
                    state, seed, key.contains("cave_vine"));
            return new Result(placed, 0, 1, "hanging");
        }

        if (type.contains("multiface_growth")) {
            Identifier blockId = identifier(jsonString(config, "block", ""));
            BlockState growth = blockId != null && Registries.BLOCK.containsId(blockId)
                    ? Registries.BLOCK.get(blockId).getDefaultState() : firstUsefulCaveState(caveFeatureStates(type, config));
            if (growth != null && !growth.isAir()) {
                placed += placeBoundedCaveGrowth(scene, x, layer, opening, growth, seed);
            }
            return new Result(placed, 0, 1, "growth");
        }

        if (type.contains("block_column")) {
            placed += placeBlockColumnFeature(scene, x, opening.floorAirY(), layer,
                    config, false, seed);
            return new Result(placed, 0, 1, "column");
        }

        List<BlockState> states = caveFeatureStates(type, config);
        BlockState state = firstUsefulCaveState(states);
        if (state != null && !state.isAir()) {
            boolean ceiling = isCeilingCaveDecoration(state, key);
            SceneCellPos pos = new SceneCellPos(x,
                    ceiling ? opening.ceilingAirY() : opening.floorAirY(), layer);
            if (!scene.blocks().occupied(pos)) {
                placed += set(scene, pos, state, false) ? 1 : 0;
            }
        }

        return new Result(placed, fluids, 1, "generic cave feature");
    }

    private static List<BlockState> caveFeatureStates(String type, JsonObject config) {
        List<BlockState> out = outputStatesForFeature(type, config);
        if (out.isEmpty()) collectProviderStates(config, out);
        List<BlockState> filtered = new ArrayList<>();
        for (BlockState state : out) {
            if (state == null || state.isAir()) continue;
            Block block = state.getBlock();
            if (block == Blocks.STONE || block == Blocks.DEEPSLATE || block == Blocks.DIRT
                    || block == Blocks.GRASS_BLOCK || block == Blocks.NETHERRACK
                    || block == Blocks.END_STONE) continue;
            if (!filtered.contains(state)) filtered.add(state);
            if (filtered.size() >= 16) break;
        }
        return filtered;
    }

    private static BlockState firstUsefulCaveState(List<BlockState> states) {
        if (states == null || states.isEmpty()) return null;
        for (BlockState state : states) if (state != null && !state.isAir()) return state;
        return null;
    }

    private static boolean isCeilingCaveDecoration(BlockState state, String key) {
        if (key.contains("spore") || key.contains("vine") || key.contains("hanging")) return true;
        Block block = state == null ? Blocks.AIR : state.getBlock();
        return block == Blocks.SPORE_BLOSSOM || block == Blocks.CAVE_VINES
                || block == Blocks.CAVE_VINES_PLANT;
    }

    private static int placeCaveDripstone(Scene scene, int x, int layer,
                                          CaveOpening opening, long seed,
                                          boolean large) {
        int placed = 0;
        int roomHeight = opening.ceilingAirY() - opening.floorAirY() + 1;
        int maxLength = Math.max(1, Math.min(large ? 7 : 4, roomHeight / 2 - 1));
        int floorLength = 1 + Math.floorMod((int) seed, Math.max(1, maxLength));
        int ceilingLength = 1 + Math.floorMod((int) (seed >>> 17), Math.max(1, maxLength));

        for (int i = 0; i < floorLength; i++) {
            BlockState state = pointedDripstoneState("up", i == floorLength - 1);
            SceneCellPos pos = new SceneCellPos(x, opening.floorAirY() + i, layer);
            if (!scene.blocks().occupied(pos) && set(scene, pos, state, false)) placed++;
        }
        for (int i = 0; i < ceilingLength; i++) {
            BlockState state = pointedDripstoneState("down", i == ceilingLength - 1);
            SceneCellPos pos = new SceneCellPos(x, opening.ceilingAirY() - i, layer);
            if (!scene.blocks().occupied(pos) && set(scene, pos, state, false)) placed++;
        }
        return placed;
    }

    private static BlockState pointedDripstoneState(String direction, boolean tip) {
        BlockState state = Blocks.POINTED_DRIPSTONE.getDefaultState();
        state = MinecraftStateCodec.withProperty(state, "vertical_direction", direction);
        state = MinecraftStateCodec.withProperty(state, "thickness", tip ? "tip" : "frustum");
        return state;
    }

    private static int placeCaveSculk(Scene scene, int x, int layer,
                                      CaveOpening opening, JsonObject config,
                                      long seed) {
        int placed = 0;
        for (int dx = -2; dx <= 2; dx++) {
            if (hash01(seed ^ (dx * 0x9E3779B97F4A7C15L)) < 0.28F) continue;
            SceneCellPos floor = new SceneCellPos(x + dx, opening.floorAirY() - 1, layer);
            if (generatedCell(scene, floor) || scene.blocks().occupied(floor)) {
                if (setOre(scene, floor, Blocks.SCULK.getDefaultState(), true)) placed++;
            }
        }
        float special = hash01(seed ^ 0x94D049BB133111EBL);
        BlockState accent = special > 0.92F ? Blocks.SCULK_SHRIEKER.getDefaultState()
                : special > 0.78F ? Blocks.SCULK_SENSOR.getDefaultState()
                : Blocks.SCULK_VEIN.getDefaultState();
        SceneCellPos pos = new SceneCellPos(x, opening.floorAirY(), layer);
        if (!scene.blocks().occupied(pos) && set(scene, pos, accent, false)) placed++;
        return placed;
    }

    private static int placeHangingCaveFeature(Scene scene, int x, int layer,
                                               CaveOpening opening, BlockState state,
                                               long seed, boolean growColumn) {
        int placed = 0;
        int length = growColumn ? 1 + Math.floorMod((int) seed, 4) : 1;
        for (int i = 0; i < length; i++) {
            int y = opening.ceilingAirY() - i;
            if (y <= opening.floorAirY()) break;
            BlockState current = state;
            if (growColumn && state.getBlock() == Blocks.CAVE_VINES) {
                current = i == length - 1 ? Blocks.CAVE_VINES.getDefaultState()
                        : Blocks.CAVE_VINES_PLANT.getDefaultState();
            }
            SceneCellPos pos = new SceneCellPos(x, y, layer);
            if (!scene.blocks().occupied(pos) && set(scene, pos, current, false)) placed++;
        }
        return placed;
    }

    private static int placeBoundedCaveGrowth(Scene scene, int x, int layer,
                                              CaveOpening opening, BlockState growth,
                                              long seed) {
        int placed = 0;
        BlockState visible = MinecraftStateCodec.withProperty(growth, "east", "true");
        visible = MinecraftStateCodec.withProperty(visible, "west", "true");
        int roomHeight = opening.ceilingAirY() - opening.floorAirY();
        for (int i = 0; i < 4; i++) {
            int y = opening.floorAirY() + 1
                    + Math.floorMod((int) (seed >>> (i * 7)), Math.max(1, roomHeight - 1));
            SceneCellPos pos = new SceneCellPos(x + (i % 2 == 0 ? -1 : 1), y, layer);
            if (scene.blocks().occupied(pos)) continue;
            if (!(solidAt(scene, pos.x() - 1, pos.y(), layer)
                    || solidAt(scene, pos.x() + 1, pos.y(), layer)
                    || solidAt(scene, pos.x(), pos.y() - 1, layer)
                    || solidAt(scene, pos.x(), pos.y() + 1, layer))) continue;
            if (set(scene, pos, visible, false)) placed++;
        }
        return placed;
    }

    private static int parseMetric(String message, String key) {
        if (message == null || key == null) return 0;
        int start = message.indexOf(key);
        if (start < 0) return 0;
        start += key.length();
        int end = start;
        while (end < message.length() && Character.isDigit(message.charAt(end))) end++;
        if (end <= start) return 0;
        try {
            return Integer.parseInt(message.substring(start, end));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private record BiomeFeatureRef(int step, Identifier id) { }

    /**
     * Large carving/replacement features depend heavily on true chunk/heightmap
     * context. Their detached approximations remain available when selected
     * directly in the worldgen browser, but are excluded from automatic biome
     * decoration so a preview cannot destroy its own terrain body.
     */
    private static boolean unsafeDetachedBiomeFeature(SpriteWorldgenCatalog.Entry entry) {
        if (entry == null) return false;
        JsonObject root = SpriteWorldgenCatalog.readJson(entry).orElse(null);
        if (root == null) return false;
        if (entry.kind() == SpriteWorldgenCatalog.Kind.PLACED_FEATURE) {
            Identifier configured = identifier(jsonString(root, "feature", ""));
            if (configured != null) {
                SpriteWorldgenCatalog.Entry configuredEntry = SpriteWorldgenCatalog
                        .find(SpriteWorldgenCatalog.Kind.CONFIGURED_FEATURE, configured).orElse(null);
                if (configuredEntry != null) root = SpriteWorldgenCatalog.readJson(configuredEntry).orElse(root);
            }
        }
        String type = compactType(jsonString(root, "type", "").toLowerCase(Locale.ROOT));
        return type.contains("lake")
                || type.contains("geode")
                || type.equals("fill_layer");
    }

    private record BiomeSliceProfile(float baseOffset, float amplitude, float broadFrequency,
                                     float detailFrequency, float ridgeStrength, float caveStrength,
                                     int seaOffset, BlockState surface, BlockState filler,
                                     BlockState stone, Fluid fluid) {
        static BiomeSliceProfile resolve(Identifier id, float temperature, float downfall) {
            String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
            boolean nether = path.contains("nether") || path.contains("crimson") || path.contains("warped") || path.contains("soul") || path.contains("basalt");
            boolean end = path.contains("end") || path.contains("void");
            if (path.contains("crimson")) return new BiomeSliceProfile(2.0F, 10.0F, 0.034F, 0.092F, 0.38F, 0.62F,
                    -8, Blocks.CRIMSON_NYLIUM.getDefaultState(), Blocks.NETHERRACK.getDefaultState(), Blocks.NETHERRACK.getDefaultState(), Fluids.LAVA);
            if (path.contains("warped")) return new BiomeSliceProfile(2.0F, 10.0F, 0.034F, 0.092F, 0.38F, 0.62F,
                    -8, Blocks.WARPED_NYLIUM.getDefaultState(), Blocks.NETHERRACK.getDefaultState(), Blocks.NETHERRACK.getDefaultState(), Fluids.LAVA);
            if (path.contains("soul")) return new BiomeSliceProfile(0.0F, 8.0F, 0.038F, 0.086F, 0.30F, 0.70F,
                    -8, Blocks.SOUL_SAND.getDefaultState(), Blocks.SOUL_SOIL.getDefaultState(), Blocks.NETHERRACK.getDefaultState(), Fluids.LAVA);
            if (path.contains("basalt")) return new BiomeSliceProfile(3.0F, 13.0F, 0.030F, 0.080F, 0.72F, 0.64F,
                    -8, Blocks.BASALT.getDefaultState(), Blocks.BLACKSTONE.getDefaultState(), Blocks.NETHERRACK.getDefaultState(), Fluids.LAVA);
            if (nether) return new BiomeSliceProfile(2.0F, 11.0F, 0.034F, 0.092F, 0.42F, 0.68F,
                    -8, Blocks.NETHERRACK.getDefaultState(), Blocks.NETHERRACK.getDefaultState(), Blocks.NETHERRACK.getDefaultState(), Fluids.LAVA);
            if (end) return new BiomeSliceProfile(3.0F, 9.0F, 0.031F, 0.078F, 0.62F, 0.22F,
                    -64, Blocks.END_STONE.getDefaultState(), Blocks.END_STONE.getDefaultState(), Blocks.END_STONE.getDefaultState(), Fluids.EMPTY);
            if (path.contains("ocean") || path.contains("deep_")) return new BiomeSliceProfile(-10.0F, 5.2F, 0.027F, 0.072F, 0.14F, 0.34F,
                    0, Blocks.GRAVEL.getDefaultState(), Blocks.GRAVEL.getDefaultState(), Blocks.STONE.getDefaultState(), Fluids.WATER);
            if (path.contains("river")) return new BiomeSliceProfile(-4.0F, 3.5F, 0.043F, 0.11F, 0.08F, 0.26F,
                    0, Blocks.SAND.getDefaultState(), Blocks.DIRT.getDefaultState(), Blocks.STONE.getDefaultState(), Fluids.WATER);
            if (path.contains("peak") || path.contains("mountain") || path.contains("windswept") || path.contains("grove")) return new BiomeSliceProfile(8.0F, 19.0F, 0.021F, 0.066F, 1.08F, 0.37F,
                    -12, temperature < 0.25F ? Blocks.SNOW_BLOCK.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState(), Blocks.STONE.getDefaultState(), Blocks.STONE.getDefaultState(), Fluids.WATER);
            if (path.contains("badlands")) return new BiomeSliceProfile(3.0F, 8.5F, 0.032F, 0.094F, 0.38F, 0.30F,
                    -9, Blocks.RED_SAND.getDefaultState(), Blocks.TERRACOTTA.getDefaultState(), Blocks.STONE.getDefaultState(), Fluids.WATER);
            if (path.contains("desert") || path.contains("beach")) return new BiomeSliceProfile(0.0F, 5.5F, 0.035F, 0.10F, 0.18F, 0.24F,
                    path.contains("beach") ? -1 : -7, Blocks.SAND.getDefaultState(), Blocks.SANDSTONE.getDefaultState(), Blocks.STONE.getDefaultState(), Fluids.WATER);
            if (path.contains("swamp") || path.contains("mangrove")) return new BiomeSliceProfile(-2.0F, 2.8F, 0.040F, 0.12F, 0.08F, 0.28F,
                    1, Blocks.GRASS_BLOCK.getDefaultState(), Blocks.DIRT.getDefaultState(), Blocks.STONE.getDefaultState(), Fluids.WATER);
            if (path.contains("mushroom")) return new BiomeSliceProfile(1.0F, 4.2F, 0.034F, 0.095F, 0.12F, 0.20F,
                    -4, Blocks.MYCELIUM.getDefaultState(), Blocks.DIRT.getDefaultState(), Blocks.STONE.getDefaultState(), Fluids.WATER);
            float humidityRoughness = Math.max(0.0F, Math.min(1.0F, downfall));
            float temperatureLift = Math.max(-1.0F, Math.min(1.0F, temperature - 0.8F));
            return new BiomeSliceProfile(temperatureLift, 5.5F + humidityRoughness * 2.4F, 0.032F, 0.095F,
                    path.contains("jungle") || path.contains("forest") || path.contains("taiga") ? 0.24F : 0.15F,
                    0.24F + humidityRoughness * 0.10F, -7,
                    temperature < 0.15F ? Blocks.SNOW_BLOCK.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState(),
                    Blocks.DIRT.getDefaultState(), Blocks.STONE.getDefaultState(), Fluids.WATER);
        }
    }

    private static Result generateBiomeSlices(SpriteEngine engine, Request request, Identifier biomeId,
                                              BiomeSliceProfile profile, boolean fallbackCaves,
                                              BiomeSceneStyle sceneStyle) {
        if (isUndergroundBiomeStyle(sceneStyle)) {
            return generateCaveBiomeSlices(engine, request, biomeId, profile, sceneStyle);
        }
        if (sceneStyle == BiomeSceneStyle.END_ISLANDS) {
            return generateEndIslandSlices(engine, request, biomeId, profile);
        }
        if (sceneStyle == BiomeSceneStyle.NETHER_CAVERN) {
            return generateNetherCavernSlices(engine, request, biomeId, profile);
        }

        Scene scene = engine.scene();
        int minX = request.centerX() - request.width() / 2;
        int blocks = 0;
        int fluids = 0;
        String biomePath = biomeId == null ? "" : biomeId.getPath().toLowerCase(Locale.ROOT);
        DimensionFlavor dimension = DimensionFlavor.forBiome(biomeId);

        for (int targetLayer : request.layers()) {
            int sourceZ = biomeSourceZ(request.layers(), targetLayer);

            for (int i = 0; i < request.width(); i++) {
                int x = minX + i;
                int surface = dimension == DimensionFlavor.OVERWORLD || dimension == DimensionFlavor.UNKNOWN
                        ? fabricatedSurfaceY(x, sourceZ, request, biomePath, profile, sceneStyle)
                        : nonOverworldSurfaceY(x, sourceZ, request, biomePath, profile);

                int bodyDepth = terrainBodyDepth(biomePath, surface, request.surfaceY());
                int floor = surface - bodyDepth;
                int seaY = request.surfaceY() + profile.seaOffset();

                for (int y = floor; y <= surface; y++) {
                    int below = surface - y;
                    SceneCellPos pos = new SceneCellPos(x, y, targetLayer);
                    if (!request.replaceExisting() && scene.blocks().occupied(pos)) continue;

                    // Configured carvers remain authoritative when present. The
                    // fallback field exists only for biomes with no native carver
                    // references and starts deep enough to preserve a clean shell.
                    if (fallbackCaves && below > 13
                            && biomeCave(x, y, sourceZ, request.seed(), profile.caveStrength() * 0.36F)) {
                        if (request.replaceExisting()) clearCell(scene, pos);
                        continue;
                    }

                    BlockState state = biomeTerrainState(biomePath, profile, x, y, sourceZ, below, request.seed());
                    state = applyDeepStoneState(state, profile, x, y, sourceZ, request.surfaceY(), below, request.seed());
                    if (request.replaceExisting()) clearCell(scene, pos);
                    scene.blocks().setSceneOwned(pos, state);
                    scene.blocks().putRuntimeData(pos, "generated_source", biomeId == null ? "biome" : biomeId.toString());
                    scene.blocks().putRuntimeData(pos, "source_slice_z", Integer.toString(sourceZ));
                    scene.blocks().putRuntimeData(pos, "generated_role", generatedRole(state, below));
                    scene.blocks().putRuntimeData(pos, "terrain_continuity", "scene_fabricated");
                    blocks++;
                }

                if (profile.fluid() != Fluids.EMPTY && surface < seaY) {
                    for (int y = surface + 1; y <= seaY; y++) {
                        SceneCellPos pos = new SceneCellPos(x, y, targetLayer);
                        if (!request.replaceExisting() && (scene.blocks().occupied(pos) || !scene.fluids().getFluidState(pos).isEmpty())) continue;
                        if (request.replaceExisting()) clearCell(scene, pos);
                        scene.fluidSystem().setSceneSource(pos, profile.fluid());
                        fluids++;
                    }
                }
            }
        }
        return new Result(blocks, fluids, request.layers().size(),
                "Fabricated biome scene from biome climate, features, and terrain data");
    }

    private static int fabricatedSurfaceY(int x, int sourceZ, Request request, String biomePath,
                                          BiomeSliceProfile profile, BiomeSceneStyle style) {
        int minX = request.centerX() - request.width() / 2;
        float local = request.width() <= 1 ? 0.5F : (x - minX) / (float) (request.width() - 1);
        float center = request.centerX();
        float macro = fbm2D(x * 0.0105F, sourceZ * 0.008F,
                request.seed() ^ 0x632BE59BD9B4E019L, 4);
        float detail = fbm2D(x * 0.031F, sourceZ * 0.021F,
                request.seed() ^ 0x9E3779B97F4A7C15L, 3);
        float ridgeNoise = fbm2D(x * 0.018F, sourceZ * 0.014F,
                request.seed() ^ 0xD1B54A32D192ED03L, 4);
        float ridged = 1.0F - Math.abs(ridgeNoise);
        int vanilla = vanillaLikeSurfaceY(x, sourceZ, request, biomePath, profile);

        return switch (style) {
            case PLAINS -> request.surfaceY() + Math.round(macro * 2.2F + detail * 1.1F
                    + scenicBump(x, center + request.width() * 0.27F, request.width() * 0.20F, 2.5F));
            case WOODLAND -> request.surfaceY() + 1 + Math.round(macro * 4.5F + detail * 2.3F
                    + ridged * 2.0F);
            case JUNGLE -> request.surfaceY() + 2 + Math.round(macro * 5.5F + detail * 2.8F
                    + ridged * ridged * 5.0F);
            case TAIGA, COLD -> request.surfaceY() + 2 + Math.round(macro * 4.0F + detail * 1.9F
                    + ridged * 2.8F);
            case SAVANNA -> request.surfaceY() + Math.round(macro * 3.2F + detail * 1.2F
                    + plateauBump(x, center - request.width() * 0.20F, request.width() * 0.17F, 5.0F)
                    + plateauBump(x, center + request.width() * 0.27F, request.width() * 0.13F, 3.5F));
            case ARID_DUNES -> request.surfaceY() + Math.round(
                    (ridged * ridged - 0.32F) * 5.5F + macro * 1.8F
                    + scenicBump(x, center - request.width() * 0.24F, request.width() * 0.20F, 3.0F));
            case BADLANDS_MESA -> request.surfaceY() + Math.round(macro * 1.6F
                    + Math.max(
                    plateauBump(x, center - request.width() * 0.24F + sourceZ * 0.12F,
                            request.width() * 0.18F, 14.0F),
                    plateauBump(x, center + request.width() * 0.23F - sourceZ * 0.09F,
                            request.width() * 0.15F, 10.0F))
                    + plateauBump(x, center + request.width() * 0.02F,
                            request.width() * 0.08F, 5.0F));
            case SWAMP -> request.surfaceY() - 1 + Math.round(macro * 0.9F + detail * 0.55F)
                    - (hash01(request.seed() ^ ((long) (x / 9) * 0x94D049BB133111EBL)) > 0.76F ? 1 : 0);
            case MOUNTAIN -> {
                float peakA = mountainBump(x, center - request.width() * 0.23F + sourceZ * 0.10F,
                        request.width() * 0.27F, 25.0F);
                float peakB = mountainBump(x, center + request.width() * 0.18F - sourceZ * 0.08F,
                        request.width() * 0.22F, 19.0F);
                float shoulder = plateauBump(x, center + request.width() * 0.37F,
                        request.width() * 0.12F, 8.0F);
                int shaped = request.surfaceY() + 2 + Math.round(Math.max(peakA, peakB) + shoulder
                        + macro * 3.0F + ridged * 3.8F + detail * 1.8F);
                yield Math.max(shaped, Math.round(vanilla * 0.72F + shaped * 0.28F));
            }
            case BEACH -> request.surfaceY() - 1 + Math.round((local - 0.48F) * 5.0F + macro * 1.1F);
            case RIVER -> request.surfaceY() + Math.round(macro * 2.2F + detail)
                    - Math.round(scenicBump(x, center + request.width() * 0.04F,
                    request.width() * 0.15F, 7.0F));
            case OCEAN -> request.surfaceY() - 6 - Math.round(
                    scenicBump(x, center, request.width() * 0.47F, 7.0F) - macro * 1.8F);
            case DEEP_OCEAN -> request.surfaceY() - 13 - Math.round(
                    scenicBump(x, center, request.width() * 0.46F, 11.0F) - macro * 2.5F);
            case MUSHROOM_ISLAND -> request.surfaceY() - 2 + Math.round(
                    scenicBump(x, center, request.width() * 0.38F, 10.0F) + macro * 2.0F);
            case GENERIC -> Math.round(vanilla * 0.55F
                    + (request.surfaceY() + profile.baseOffset() + macro * 4.0F + detail * 1.8F) * 0.45F);
            default -> vanilla;
        };
    }

    private static float scenicBump(float x, float center, float halfWidth, float height) {
        if (halfWidth <= 0.001F) return 0.0F;
        float d = Math.abs(x - center) / halfWidth;
        if (d >= 1.0F) return 0.0F;
        float t = 1.0F - d;
        float smooth = t * t * (3.0F - 2.0F * t);
        return smooth * height;
    }

    private static float plateauBump(float x, float center, float halfWidth, float height) {
        if (halfWidth <= 0.001F) return 0.0F;
        float d = Math.abs(x - center) / halfWidth;
        if (d >= 1.0F) return 0.0F;
        if (d <= 0.52F) return height;
        float edge = (1.0F - d) / 0.48F;
        edge = Math.max(0.0F, Math.min(1.0F, edge));
        return height * edge * edge * (3.0F - 2.0F * edge);
    }

    private static float mountainBump(float x, float center, float halfWidth, float height) {
        if (halfWidth <= 0.001F) return 0.0F;
        float d = Math.abs(x - center) / halfWidth;
        if (d >= 1.0F) return 0.0F;
        float t = 1.0F - d;
        return (float) (height * Math.pow(t, 1.35));
    }

    private static Result generateCaveBiomeSlices(SpriteEngine engine, Request request, Identifier biomeId,
                                                  BiomeSliceProfile profile, BiomeSceneStyle style) {
        Scene scene = engine.scene();
        int minX = request.centerX() - request.width() / 2;
        int blocks = 0;
        int fluids = 0;

        for (int targetLayer : request.layers()) {
            int sourceZ = biomeSourceZ(request.layers(), targetLayer);
            for (int i = 0; i < request.width(); i++) {
                int x = minX + i;

                // Standalone cave-biome previews should be full cave landscapes,
                // not two nearly-flat noisy lines. Use independent large-scale
                // floor/ceiling fields plus ledges and constrictions. There is
                // deliberately no edge falloff, so the cave remains open through
                // both sides of the generated scene.
                float floorMacro = fbm2D(x * 0.016F, sourceZ * 0.014F,
                        request.seed() ^ 0xA24BAED4963EE407L, 4);
                float floorDetail = fbm2D(x * 0.049F, sourceZ * 0.031F,
                        request.seed() ^ 0x632BE59BD9B4E019L, 3);
                float ceilingMacro = fbm2D(x * 0.014F, sourceZ * 0.012F,
                        request.seed() ^ 0xDB4F0B9175AE2165L, 4);
                float ceilingDetail = fbm2D(x * 0.043F, sourceZ * 0.037F,
                        request.seed() ^ 0x94D049BB133111EBL, 3);
                float chamberNoise = fbm2D(x * 0.009F, sourceZ * 0.008F,
                        request.seed() ^ 0xD1B54A32D192ED03L, 3);

                int floor = request.surfaceY() - 13
                        + Math.round(floorMacro * 5.0F + floorDetail * 2.4F);
                int gap = 20 + Math.round((chamberNoise + 1.0F) * 5.0F);
                int ceiling = floor + gap
                        + Math.round(ceilingMacro * 5.0F + ceilingDetail * 2.2F);
                ceiling = Math.max(floor + 9, ceiling);

                // Occasional stone shelf or low ceiling creates readable cave
                // rooms without sealing the cross-section.
                float shelf = hash01(request.seed()
                        ^ ((long) (x / 11) * 0x9E3779B97F4A7C15L)
                        ^ ((long) sourceZ * 0xC2B2AE3D27D4EB4FL));
                if (shelf > 0.90F) {
                    if ((x / 11 & 1) == 0) floor += 2;
                    else ceiling -= 2;
                }

                int bottom = floor - 58;
                int top = ceiling + 18;

                for (int y = bottom; y <= floor; y++) {
                    int below = floor - y;
                    BlockState state;
                    if (below == 0 && style == BiomeSceneStyle.LUSH_CAVE) {
                        state = Blocks.MOSS_BLOCK.getDefaultState();
                    } else if (below == 0 && style == BiomeSceneStyle.DRIPSTONE_CAVE) {
                        state = Blocks.DRIPSTONE_BLOCK.getDefaultState();
                    } else if (below == 0 && style == BiomeSceneStyle.DEEP_DARK) {
                        state = Blocks.SCULK.getDefaultState();
                    } else if (below == 0 && style == BiomeSceneStyle.CAVE_GENERIC
                            && profile.surface() != null && !profile.surface().isAir()) {
                        state = profile.surface();
                    } else if (below <= 2 && style == BiomeSceneStyle.LUSH_CAVE
                            && hash01(request.seed() ^ ((long) x * 31L) ^ ((long) y * 17L)) > 0.58F) {
                        state = Blocks.ROOTED_DIRT.getDefaultState();
                    } else if (below <= 3 && style == BiomeSceneStyle.DRIPSTONE_CAVE) {
                        state = Blocks.DRIPSTONE_BLOCK.getDefaultState();
                    } else {
                        state = y < request.surfaceY() - 38
                                ? Blocks.DEEPSLATE.getDefaultState()
                                : Blocks.STONE.getDefaultState();
                    }

                    SceneCellPos pos = new SceneCellPos(x, y, targetLayer);
                    if (request.replaceExisting()) clearCell(scene, pos);
                    if (!request.replaceExisting() && scene.blocks().occupied(pos)) continue;
                    scene.blocks().setSceneOwned(pos, state);
                    scene.blocks().putRuntimeData(pos, "generated_source",
                            biomeId == null ? "biome" : biomeId.toString());
                    scene.blocks().putRuntimeData(pos, "source_slice_z", Integer.toString(sourceZ));
                    scene.blocks().putRuntimeData(pos, "generated_role",
                            below == 0 ? "cave_floor" : "stone");
                    blocks++;
                }

                for (int y = ceiling; y <= top; y++) {
                    int above = y - ceiling;
                    SceneCellPos pos = new SceneCellPos(x, y, targetLayer);
                    if (request.replaceExisting()) clearCell(scene, pos);
                    if (!request.replaceExisting() && scene.blocks().occupied(pos)) continue;

                    BlockState state;
                    if (above == 0 && style == BiomeSceneStyle.DRIPSTONE_CAVE) {
                        state = Blocks.DRIPSTONE_BLOCK.getDefaultState();
                    } else if (above == 0 && style == BiomeSceneStyle.LUSH_CAVE
                            && hash01(request.seed() ^ ((long) x * 43L) ^ sourceZ) > 0.66F) {
                        state = Blocks.MOSS_BLOCK.getDefaultState();
                    } else if (above <= 2 && style == BiomeSceneStyle.DRIPSTONE_CAVE) {
                        state = Blocks.DRIPSTONE_BLOCK.getDefaultState();
                    } else {
                        state = y < request.surfaceY() - 30
                                ? Blocks.DEEPSLATE.getDefaultState()
                                : Blocks.STONE.getDefaultState();
                    }

                    scene.blocks().setSceneOwned(pos, state);
                    scene.blocks().putRuntimeData(pos, "generated_source",
                            biomeId == null ? "biome" : biomeId.toString());
                    scene.blocks().putRuntimeData(pos, "source_slice_z", Integer.toString(sourceZ));
                    scene.blocks().putRuntimeData(pos, "generated_role",
                            above == 0 ? "cave_ceiling" : "stone");
                    blocks++;
                }

                if (style == BiomeSceneStyle.LUSH_CAVE
                        && hash01(request.seed() ^ ((long) x * 0x9E3779B97F4A7C15L) ^ sourceZ) > 0.83F) {
                    SceneCellPos water = new SceneCellPos(x, floor + 1, targetLayer);
                    if (scene.blocks().get(water) == null) {
                        scene.fluidSystem().setSceneSource(water, Fluids.WATER);
                        fluids++;
                    }
                }
            }
        }

        String label = switch (style) {
            case LUSH_CAVE -> "Fabricated edge-open lush-cave landscape";
            case DRIPSTONE_CAVE -> "Fabricated edge-open dripstone-cave landscape";
            case DEEP_DARK -> "Fabricated edge-open deep-dark landscape";
            case CAVE_GENERIC -> "Fabricated edge-open underground biome landscape";
            default -> "Fabricated edge-open cave landscape";
        };
        return new Result(blocks, fluids, request.layers().size(), label);
    }

    private static Result generateNetherCavernSlices(SpriteEngine engine, Request request, Identifier biomeId,
                                                     BiomeSliceProfile profile) {
        Scene scene = engine.scene();
        int minX = request.centerX() - request.width() / 2;
        int blocks = 0;
        int fluids = 0;
        for (int targetLayer : request.layers()) {
            int sourceZ = biomeSourceZ(request.layers(), targetLayer);
            for (int i = 0; i < request.width(); i++) {
                int x = minX + i;
                int floor = request.surfaceY() - 8 + Math.round(fbm2D(x * 0.030F, sourceZ * 0.025F,
                        request.seed() ^ 0x632BE59BD9B4E019L, 4) * 6.0F);
                int ceiling = request.surfaceY() + 18 + Math.round(fbm2D(x * 0.024F, sourceZ * 0.020F,
                        request.seed() ^ 0xD1B54A32D192ED03L, 4) * 7.0F);
                for (int y = floor - 44; y <= floor; y++) {
                    SceneCellPos pos = new SceneCellPos(x, y, targetLayer);
                    if (request.replaceExisting()) clearCell(scene, pos);
                    if (!request.replaceExisting() && scene.blocks().occupied(pos)) continue;
                    BlockState state = y == floor ? profile.surface() : profile.stone();
                    scene.blocks().setSceneOwned(pos, state);
                    scene.blocks().putRuntimeData(pos, "generated_source", biomeId == null ? "biome" : biomeId.toString());
                    scene.blocks().putRuntimeData(pos, "source_slice_z", Integer.toString(sourceZ));
                    scene.blocks().putRuntimeData(pos, "generated_role", y == floor ? "surface" : "stone");
                    blocks++;
                }
                for (int y = ceiling; y <= ceiling + 15; y++) {
                    SceneCellPos pos = new SceneCellPos(x, y, targetLayer);
                    if (request.replaceExisting()) clearCell(scene, pos);
                    if (!request.replaceExisting() && scene.blocks().occupied(pos)) continue;
                    scene.blocks().setSceneOwned(pos, profile.stone());
                    scene.blocks().putRuntimeData(pos, "generated_source", biomeId == null ? "biome" : biomeId.toString());
                    scene.blocks().putRuntimeData(pos, "source_slice_z", Integer.toString(sourceZ));
                    scene.blocks().putRuntimeData(pos, "generated_role", "cave_ceiling");
                    blocks++;
                }
                int lavaY = request.surfaceY() - 16;
                if (floor < lavaY) {
                    for (int y = floor + 1; y <= lavaY; y++) {
                        SceneCellPos pos = new SceneCellPos(x, y, targetLayer);
                        if (scene.blocks().get(pos) != null) continue;
                        scene.fluidSystem().setSceneSource(pos, Fluids.LAVA);
                        fluids++;
                    }
                }
            }
        }
        return new Result(blocks, fluids, request.layers().size(), "Fabricated Nether cavern scene");
    }

    private static Result generateEndIslandSlices(SpriteEngine engine, Request request, Identifier biomeId,
                                                  BiomeSliceProfile profile) {
        Scene scene = engine.scene();
        int minX = request.centerX() - request.width() / 2;
        int blocks = 0;
        float mainCenter = request.centerX() - request.width() * 0.10F;
        float sideCenter = request.centerX() + request.width() * 0.33F;
        for (int targetLayer : request.layers()) {
            int sourceZ = biomeSourceZ(request.layers(), targetLayer);
            for (int i = 0; i < request.width(); i++) {
                int x = minX + i;
                float main = scenicBump(x, mainCenter + sourceZ * 0.08F, request.width() * 0.34F, 13.0F);
                float side = scenicBump(x, sideCenter - sourceZ * 0.05F, request.width() * 0.12F, 5.5F);
                float island = Math.max(main, side);
                if (island <= 0.35F) continue;
                int surface = request.surfaceY() + Math.round(island * 0.30F);
                int thickness = Math.max(3, Math.round(island * 0.75F));
                for (int y = surface - thickness; y <= surface; y++) {
                    SceneCellPos pos = new SceneCellPos(x, y, targetLayer);
                    if (request.replaceExisting()) clearCell(scene, pos);
                    if (!request.replaceExisting() && scene.blocks().occupied(pos)) continue;
                    scene.blocks().setSceneOwned(pos, Blocks.END_STONE.getDefaultState());
                    scene.blocks().putRuntimeData(pos, "generated_source", biomeId == null ? "biome" : biomeId.toString());
                    scene.blocks().putRuntimeData(pos, "source_slice_z", Integer.toString(sourceZ));
                    scene.blocks().putRuntimeData(pos, "generated_role", y == surface ? "surface" : "stone");
                    blocks++;
                }
            }
        }
        return new Result(blocks, 0, request.layers().size(), "Fabricated End island scene");
    }

    private static int vanillaLikeSurfaceY(int x, int sourceZ, Request request,
                                           String biomePath, BiomeSliceProfile profile) {
        int scanBottom = request.surfaceY() - 58;
        int scanTop = request.surfaceY() + (isMountainBiome(biomePath) ? 86 : 58);
        TerrainSample sample = sampleVanillaTerrain(x, sourceZ, request.seed(), biomePath);
        for (int y = scanTop; y >= scanBottom; y--) {
            if (terrainDensity(sample, x, y, sourceZ, request, profile) > 0.0F) return y;
        }
        return scanBottom;
    }

    private static TerrainSample sampleVanillaTerrain(int x, int sourceZ, long seed,
                                                      String biomePath) {
        // Spatial scales are compressed relative to a full Minecraft world so an
        // editor-width scene can contain recognizable continental transitions,
        // while the response splines themselves are exactly vanilla 1.20.1.
        float continents = fbm2D(x * 0.0048F, sourceZ * 0.0048F,
                seed ^ 0x632BE59BD9B4E019L, 5);
        float erosion = fbm2D(x * 0.0085F, sourceZ * 0.0085F,
                seed ^ 0x9E3779B97F4A7C15L, 5);
        float ridges = fbm2D(x * 0.0115F, sourceZ * 0.0115F,
                seed ^ 0xD1B54A32D192ED03L, 4);

        float[] biased = biomeClimateBias(biomePath, continents, erosion, ridges);
        continents = biased[0];
        erosion = biased[1];
        ridges = biased[2];
        float folded = DensityFunctions.getPeaksValleysNoise(ridges);
        TerrainClimate climate = new TerrainClimate(continents, erosion, ridges, folded);
        float offset = VANILLA_OFFSET.apply(climate);
        float factor = Math.max(0.35F, VANILLA_FACTOR.apply(climate));
        float jaggedness = Math.max(0.0F, VANILLA_JAGGEDNESS.apply(climate));
        float jaggedNoise = halfNegative(fbm2D(x * 0.027F, sourceZ * 0.027F,
                seed ^ 0x94D049BB133111EBL, 3));
        return new TerrainSample(continents, erosion, ridges, folded, offset, factor, jaggedness, jaggedNoise);
    }

    private static float terrainDensity(TerrainSample sample, int x, int y, int sourceZ,
                                        Request request, BiomeSliceProfile profile) {
        // Vanilla's terrain router uses a very broad vertical depth gradient.
        // Koil compresses that gradient into the editor's practical vertical span
        // while retaining vanilla's offset/factor/jaggedness response splines.
        float verticalDepth = (request.surfaceY() - y) / 64.0F;
        float biomeBias = profile.baseOffset() / 28.0F;
        float depth = verticalDepth + sample.offset() + biomeBias;
        float jagged = sample.jaggedness() * sample.jaggedNoise() * Math.max(0.35F, profile.ridgeStrength());
        float sloped = 4.0F * quarterNegative((depth + jagged) * sample.factor());
        float base3d = fbm3D(x * 0.030F, y * 0.026F, sourceZ * 0.030F,
                request.seed() ^ 0xDB4F0B9175AE2165L, 4) * 0.72F;
        float fine = fbm3D(x * 0.074F, y * 0.061F, sourceZ * 0.074F,
                request.seed() ^ 0xBBE0563303A4615FL, 2) * 0.13F;
        return sloped + base3d + fine;
    }

    private static float[] biomeClimateBias(String path, float continents, float erosion, float ridges) {
        float cTarget = 0.18F, eTarget = 0.20F, rTarget = 0.0F;
        float cBlend = 0.28F, eBlend = 0.28F, rBlend = 0.12F;
        if (path.contains("deep_ocean")) { cTarget = -0.78F; cBlend = 0.82F; eTarget = 0.15F; eBlend = 0.35F; }
        else if (path.contains("ocean")) { cTarget = -0.62F; cBlend = 0.78F; eTarget = 0.22F; eBlend = 0.35F; }
        else if (path.contains("river")) { cTarget = -0.10F; cBlend = 0.58F; eTarget = 0.55F; eBlend = 0.62F; }
        else if (isMountainBiome(path)) { cTarget = 0.68F; cBlend = 0.72F; eTarget = -0.72F; eBlend = 0.76F; rTarget = -0.58F; rBlend = 0.32F; }
        else if (path.contains("badlands")) { cTarget = 0.42F; cBlend = 0.58F; eTarget = -0.18F; eBlend = 0.48F; rTarget = 0.12F; rBlend = 0.20F; }
        else if (path.contains("swamp") || path.contains("mangrove")) { cTarget = 0.04F; cBlend = 0.56F; eTarget = 0.72F; eBlend = 0.72F; }
        else if (path.contains("plains") || path.contains("meadow")) { cTarget = 0.24F; cBlend = 0.48F; eTarget = 0.62F; eBlend = 0.66F; }
        else if (path.contains("desert") || path.contains("savanna")) { cTarget = 0.30F; cBlend = 0.48F; eTarget = 0.38F; eBlend = 0.46F; }
        else if (path.contains("forest") || path.contains("jungle") || path.contains("taiga")) { cTarget = 0.30F; cBlend = 0.46F; eTarget = 0.10F; eBlend = 0.34F; }

        return new float[]{
                clampNoise(lerp(continents, cTarget, cBlend)),
                clampNoise(lerp(erosion, eTarget, eBlend)),
                clampNoise(lerp(ridges, rTarget, rBlend))
        };
    }

    private static int nonOverworldSurfaceY(int x, int sourceZ, Request request, String biomePath,
                                            BiomeSliceProfile profile) {
        float broad = fbm2D(x * profile.broadFrequency(), sourceZ * profile.broadFrequency(), request.seed(), 5);
        float detail = fbm2D(x * profile.detailFrequency(), sourceZ * profile.detailFrequency(),
                request.seed() ^ 0x9E3779B97F4A7C15L, 4);
        float ridgeNoise = fbm2D(x * profile.broadFrequency() * 0.63F,
                sourceZ * profile.broadFrequency() * 0.63F, request.seed() ^ 0xD1B54A32D192ED03L, 4);
        float ridge = 1.0F - Math.abs(ridgeNoise);
        float shape = broad * 0.72F + detail * 0.20F + (ridge * ridge - 0.38F) * profile.ridgeStrength();
        return request.surfaceY() + Math.round(profile.baseOffset() + shape * profile.amplitude());
    }

    private static int terrainBodyDepth(String biomePath, int surface, int anchor) {
        if (biomePath.contains("nether") || biomePath.contains("crimson") || biomePath.contains("warped")
                || biomePath.contains("soul") || biomePath.contains("basalt")) return 96;

        // The editor's anchor corresponds to approximately overworld sea level Y=63.
        // Carry every overworld column down to Minecraft's -64 floor instead of
        // generating a shallow facade. Because surface can rise above the anchor,
        // the requested depth grows with it and all columns share the same world
        // bottom. Hidden rear cells are culled by the depth compositor, so this
        // adds real caves/deepslate/background without making every cell render.
        int surfaceWorldY = 63 + (surface - anchor);
        return Math.max(96, surfaceWorldY + 64);
    }

    private static BlockState applyDeepStoneState(BlockState original, BiomeSliceProfile profile,
                                                   int x, int sceneY, int sourceZ, int anchorY,
                                                   int belowSurface, long seed) {
        if (original == null || original.isAir()) return original;
        if (profile.stone().getBlock() != Blocks.STONE) return original;
        int worldY = 63 + (sceneY - anchorY);
        if (belowSurface > 5 && worldY <= -64) return Blocks.BEDROCK.getDefaultState();
        if (belowSurface > 5 && worldY <= -60) {
            // Vanilla's bottom bedrock is a noisy five-block transition rather
            // than a perfectly flat slab. Keep Y=-64 guaranteed and taper the
            // probability toward Y=-60 using the scene seed and literal Z slice.
            float bedrockChance = (-59 - worldY) / 5.0F;
            long salt = seed ^ ((long) x * 0x9E3779B97F4A7C15L)
                    ^ ((long) sourceZ * 0xD1B54A32D192ED03L)
                    ^ ((long) worldY * 0x94D049BB133111EBL);
            if (hash01(salt) < bedrockChance) return Blocks.BEDROCK.getDefaultState();
        }
        if (belowSurface > 5 && worldY <= 0) return Blocks.DEEPSLATE.getDefaultState();
        if (belowSurface > 5 && worldY < 8) {
            // Vanilla transitions from stone at Y=8 to effectively all deepslate
            // at Y=0. Use a spatially coherent noise perturbation plus a per-cell
            // sample so the boundary reads as geology instead of a flat stripe.
            float vertical = (8.0F - worldY) / 8.0F;
            float geology = fbm3D(x * 0.19F, worldY * 0.13F, sourceZ * 0.19F,
                    seed ^ 0xF1357AEA2E62A9C5L, 2) * 0.18F;
            float chance = Math.max(0.0F, Math.min(1.0F, vertical + geology));
            long salt = seed ^ ((long) x * 0xA24BAED4963EE407L)
                    ^ ((long) sourceZ * 0x9FB21C651E98DF25L)
                    ^ ((long) worldY * 0xD1B54A32D192ED03L);
            if (hash01(salt) < chance) return Blocks.DEEPSLATE.getDefaultState();
        }
        return original;
    }

    private static String generatedRole(BlockState state, int below) {
        if (below == 0) return "surface";
        if (below <= 4) return "filler";
        if (state != null && (state.isOf(Blocks.DEEPSLATE) || state.isOf(Blocks.BEDROCK))) return "deep_stone";
        return "stone";
    }

    private static boolean isMountainBiome(String path) {
        return path.contains("peak") || path.contains("mountain") || path.contains("windswept")
                || path.contains("grove") || path.contains("slope");
    }

    private static float quarterNegative(float value) { return value < 0.0F ? value * 0.25F : value; }
    private static float halfNegative(float value) { return value < 0.0F ? value * 0.5F : value; }
    /**
     * Minecraft's grass/foliage colormaps are addressed on a normalized 0..1
     * climate plane even though biome temperature itself may legally exceed that
     * range. Keep this guard local to color lookup so worldgen still sees the
     * biome's authored raw climate values.
     */
    private static float clampBiomeColorInput(float value, float fallback) {
        if (!Float.isFinite(value)) value = fallback;
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static float clampNoise(float value) { return Math.max(-1.0F, Math.min(1.0F, value)); }

    private static BlockState biomeTerrainState(String biomePath, BiomeSliceProfile profile,
                                                int x, int y, int sourceZ, int below, long seed) {
        if (biomePath != null && biomePath.contains("badlands")) {
            if (below == 0) return Blocks.RED_SAND.getDefaultState();
            if (below <= 3) return Blocks.ORANGE_TERRACOTTA.getDefaultState();
            if (below <= 22) return badlandsBandState(x, y, sourceZ, seed);
            return profile.stone();
        }
        if (below == 0) return profile.surface();
        if (below <= 4) return profile.filler();
        return profile.stone();
    }

    /**
     * Compact detached equivalent of Minecraft's seeded terracotta band array.
     * The palette deliberately uses the same characteristic badlands colors and
     * broad horizontal bands, with only a small X/Z offset so the strata stay
     * readable in Koil's side view.
     */
    private static BlockState badlandsBandState(int x, int y, int sourceZ, long seed) {
        int offset = Math.round(noise1D(x * 0.018F + sourceZ * 0.071F,
                seed ^ 0xA24BAED4963EE407L) * 3.0F);
        int band = Math.floorMod(y + offset, 32);
        if (band == 4 || band == 5) return Blocks.BROWN_TERRACOTTA.getDefaultState();
        if (band == 9) return Blocks.RED_TERRACOTTA.getDefaultState();
        if (band == 14) return Blocks.YELLOW_TERRACOTTA.getDefaultState();
        if (band == 20) return Blocks.RED_TERRACOTTA.getDefaultState();
        if (band == 25) return Blocks.WHITE_TERRACOTTA.getDefaultState();
        if (band == 24 || band == 26) return Blocks.LIGHT_GRAY_TERRACOTTA.getDefaultState();
        if (band == 29) return Blocks.ORANGE_TERRACOTTA.getDefaultState();
        return Blocks.TERRACOTTA.getDefaultState();
    }

    private static int biomeSourceZ(List<Integer> targetLayers, int targetLayer) {
        if (targetLayers == null || targetLayers.isEmpty()) return targetLayer;
        List<Integer> ordered = new ArrayList<>(targetLayers);
        ordered.sort(Integer::compareTo);
        int anchor = ordered.get(ordered.size() / 2);
        // A Koil depth layer is a literal Minecraft Z slice. Explicit gaps stay
        // meaningful without exaggerating them: 0,2,5 samples relative Z -2,0,3
        // rather than the old -24,0,36. This keeps neighboring biome slices
        // spatially coherent while still letting intentionally skipped layers
        // reveal more separated cross-sections. Empty Koil layers remain untouched.
        return targetLayer - anchor;
    }

    private static int countGeneratedTerrainCells(Scene scene, Request request) {
        if (scene == null || request == null) return 0;
        int minX = request.centerX() - request.width() / 2;
        int maxX = minX + request.width() - 1;
        Set<Integer> layers = new HashSet<>(request.layers());
        int count = 0;
        for (var entry : scene.blocks().entries()) {
            if (entry == null || entry.position() == null || entry.cell() == null) continue;
            SceneCellPos pos = entry.position();
            if (pos.x() < minX || pos.x() > maxX || !layers.contains(pos.depth())) continue;
            if (entry.cell().runtimeData().containsKey("generated_source")) count++;
        }
        return count;
    }

    private static int countBlockInGeneration(Scene scene, Request request, Block block) {
        if (scene == null || request == null || block == null) return 0;
        int minX = request.centerX() - request.width() / 2;
        int maxX = minX + request.width() - 1;
        Set<Integer> layers = new HashSet<>(request.layers());
        int count = 0;
        for (var entry : scene.blocks().entries()) {
            if (entry == null || entry.cell() == null || entry.cell().blockState() == null) continue;
            SceneCellPos pos = entry.position();
            if (pos.x() < minX || pos.x() > maxX || !layers.contains(pos.depth())) continue;
            if (entry.cell().blockState().getBlock() == block) count++;
        }
        return count;
    }

    private static int countUnexpectedSurfaceGrass(Scene scene, Request request, Identifier biomeId) {
        if (scene == null || request == null || biomeId == null || !biomeId.getPath().contains("plains")) return 0;
        int minX = request.centerX() - request.width() / 2;
        int maxX = minX + request.width() - 1;
        Set<Integer> layers = new HashSet<>(request.layers());
        int unexpected = 0;
        for (var entry : scene.blocks().entries()) {
            SceneCellPos pos = entry.position();
            if (pos.x() < minX || pos.x() > maxX || !layers.contains(pos.depth())) continue;
            if (entry.cell() == null || entry.cell().blockState() == null
                    || entry.cell().blockState().getBlock() != Blocks.GRASS_BLOCK) continue;
            if (!"surface".equals(entry.cell().runtimeData().get("generated_role"))) unexpected++;
        }
        return unexpected;
    }

    private static boolean biomeCave(int x, int y, int sourceZ, long seed, float strength) {
        float cave = Math.abs(fbm3D(x * 0.080F, y * 0.092F, sourceZ * 0.080F, seed ^ 0x94D049BB133111EBL, 3));
        float tunnel = Math.abs(fbm3D(x * 0.036F, y * 0.051F, sourceZ * 0.036F, seed ^ 0x369DEA0F31A53F85L, 2));
        float threshold = 0.78F - Math.max(0.0F, Math.min(1.0F, strength)) * 0.24F;
        return cave > threshold && tunnel < 0.62F;
    }

    /** Finds the surface relevant to a feature rather than blindly using the
     * topmost generated terrain. Nether scenes have a real roof, so surface-bound
     * fungal features must anchor to the cavern floor below the open volume. */
    private static int findFeatureSurfaceY(Scene scene, int x, int layer, int fallback, DimensionFlavor dimension) {
        if (dimension == DimensionFlavor.NETHER) {
            int floor = findCavernFloorAirY(scene, x, layer, fallback);
            if (floor != Integer.MIN_VALUE) return floor;
        }
        return findTerrainSurfaceY(scene, x, layer, fallback);
    }

    private static DimensionFlavor dimensionFromScene(Scene scene) {
        if (scene == null || scene.environment() == null || scene.environment().dimensionId() == null) {
            return DimensionFlavor.UNKNOWN;
        }
        String path = scene.environment().dimensionId().getPath().toLowerCase(Locale.ROOT);
        if (path.contains("nether")) return DimensionFlavor.NETHER;
        if (path.contains("end")) return DimensionFlavor.END;
        if (path.contains("overworld")) return DimensionFlavor.OVERWORLD;
        return DimensionFlavor.UNKNOWN;
    }

    private static int findCavernFloorAirY(Scene scene, int x, int layer, int fallback) {
        int top = fallback + 16;
        int bottom = fallback - 40;
        // Start inside the expected open cavern and walk downward until the first
        // solid floor. This deliberately ignores the Nether roof above.
        for (int y = top; y >= bottom; y--) {
            if (!solidAt(scene, x, y, layer) && solidAt(scene, x, y - 1, layer)) return y;
        }
        return Integer.MIN_VALUE;
    }

    private static int findCavernCeilingAirY(Scene scene, int x, int layer, int floorAirY, int maxY) {
        int start = Math.max(floorAirY + 2, floorAirY);
        for (int y = start; y <= maxY; y++) {
            if (solidAt(scene, x, y, layer) && !solidAt(scene, x, y - 1, layer)) return y - 1;
        }
        return Integer.MIN_VALUE;
    }

    private static int findTerrainSurfaceY(Scene scene, int x, int layer, int fallback) {
        int top = fallback + 48;
        int bottom = fallback - 80;
        for (int y = top; y >= bottom; y--) {
            SceneCellPos pos = new SceneCellPos(x, y, layer);
            var cell = scene.blocks().get(pos);
            if (cell == null || cell.blockState() == null || cell.blockState().isAir()) continue;
            if ("surface".equals(cell.runtimeData().get("generated_role"))) return y + 1;
        }
        // Non-biome/generated scenes may not carry terrain-role metadata. Fall
        // back to the old top-solid lookup so individual features remain useful
        // on hand-authored scenes and older saved playground files.
        return findSurfaceY(scene, x, layer, fallback);
    }

    private static int findSurfaceY(Scene scene, int x, int layer, int fallback) {
        int top = fallback + 48;
        int bottom = fallback - 80;
        for (int y = top; y >= bottom; y--) {
            BlockState state = scene.blocks().getBlockState(new SceneCellPos(x, y, layer));
            if (state != null && !state.isAir()) return y + 1;
        }
        return fallback;
    }

    private static int featureAnchorY(Scene scene, Request request, int layer, int step, Identifier featureId) {
        String path = featureId == null ? "" : featureId.getPath().toLowerCase(Locale.ROOT);
        int surface = findTerrainSurfaceY(scene, request.centerX(), layer, request.surfaceY());
        if (step <= 1 || path.contains("lake")) return surface - 1;
        if (step == 6 || path.contains("ore") || path.contains("geode")) return surface - 10;
        if (step == 7 || path.contains("underground")) return surface - 7;
        if (step == 8 || path.contains("spring")) return surface - 4;
        return surface;
    }

    private static SceneCellPos recommendBiomeFocus(Scene scene, Request request, Identifier biomeId) {
        if (scene == null || request == null) return null;
        List<Integer> layers = new ArrayList<>(request.layers());
        layers.sort(Integer::compareTo);
        int layer = layers.isEmpty() ? 0 : layers.get(layers.size() - 1);
        String path = biomeId == null ? "" : biomeId.getPath().toLowerCase(Locale.ROOT);
        boolean caveLike = path.contains("cave") || path.contains("deep_dark") || path.contains("dripstone");
        int minX = request.centerX() - request.width() / 2;
        int maxX = minX + request.width() - 1;

        if (caveLike) {
            SceneCellPos best = null;
            int bestScore = Integer.MAX_VALUE;
            for (int x = minX; x <= maxX; x++) {
                int surface = findTerrainSurfaceY(scene, x, layer, request.surfaceY());
                for (int y = surface - 5; y >= surface - 24; y--) {
                    SceneCellPos air = new SceneCellPos(x, y, layer);
                    if (scene.blocks().occupied(air) || !scene.fluids().getFluidState(air).isEmpty()) continue;
                    boolean floor = solidAt(scene, x, y - 1, layer);
                    boolean ceiling = false;
                    for (int dy = 2; dy <= 7; dy++) if (solidAt(scene, x, y + dy, layer)) { ceiling = true; break; }
                    if (!floor || !ceiling) continue;
                    int score = Math.abs(x - request.centerX()) * 2 + Math.abs((surface - 12) - y);
                    if (score < bestScore) { bestScore = score; best = air; }
                }
            }
            if (best != null) return best;
        }

        // For normal biomes frame the actual generated surface nearest the
        // generation center, slightly above the ground so the canvas shows both
        // skyline/features and the underground cross-section.
        for (int radius = 0; radius <= request.width() / 2; radius++) {
            for (int sign : new int[]{1, -1}) {
                int x = request.centerX() + radius * sign;
                if (x < minX || x > maxX) continue;
                int surfaceAir = findTerrainSurfaceY(scene, x, layer, request.surfaceY());
                SceneCellPos ground = new SceneCellPos(x, surfaceAir - 1, layer);
                if (scene.blocks().occupied(ground)) return new SceneCellPos(x, surfaceAir + 3, layer);
            }
        }
        return new SceneCellPos(request.centerX(), request.surfaceY(), layer);
    }

    private static List<BiomeFeatureRef> collectBiomeFeatureRefs(JsonElement element) {
        List<BiomeFeatureRef> result = new ArrayList<>();
        if (element == null || !element.isJsonArray()) return result;
        JsonArray steps = element.getAsJsonArray();
        for (int step = 0; step < steps.size(); step++) {
            JsonElement value = steps.get(step);
            if (value.isJsonArray()) {
                for (JsonElement child : value.getAsJsonArray()) {
                    if (!child.isJsonPrimitive() || !child.getAsJsonPrimitive().isString()) continue;
                    Identifier id = identifier(child.getAsString());
                    if (id != null) result.add(new BiomeFeatureRef(step, id));
                }
            } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                Identifier id = identifier(value.getAsString());
                if (id != null) result.add(new BiomeFeatureRef(step, id));
            }
        }
        return result;
    }

    private static List<Identifier> collectBiomeCarverRefs(JsonElement element) {
        List<Identifier> result = new ArrayList<>();
        if (element == null || element.isJsonNull()) return result;
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value == null) continue;
                if (value.isJsonArray()) {
                    for (JsonElement child : value.getAsJsonArray()) {
                        if (!child.isJsonPrimitive() || !child.getAsJsonPrimitive().isString()) continue;
                        Identifier id = identifier(child.getAsString());
                        if (id != null && !result.contains(id)) result.add(id);
                    }
                } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    Identifier id = identifier(value.getAsString());
                    if (id != null && !result.contains(id)) result.add(id);
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (!child.isJsonPrimitive() || !child.getAsJsonPrimitive().isString()) continue;
                Identifier id = identifier(child.getAsString());
                if (id != null && !result.contains(id)) result.add(id);
            }
        }
        return result;
    }


    private static Result generateTerrainResource(SpriteEngine engine, Request request, SpriteWorldgenCatalog.Entry entry) {
        JsonObject json = SpriteWorldgenCatalog.readJson(entry).orElse(null);
        if (entry.kind() == SpriteWorldgenCatalog.Kind.DIMENSION && json != null) {
            Identifier dimensionType = identifier(jsonString(json, "type", ""));
            if (dimensionType != null) engine.scene().environment().setDimensionId(entry.id());
            JsonObject generator = object(json, "generator");
            if (generator != null) {
                String generatorType = jsonString(generator, "type", "").toLowerCase(Locale.ROOT);
                if (generatorType.contains("flat") && generator.has("settings") && generator.get("settings").isJsonObject()) {
                    return generateFlatSettings(engine, request, generator.getAsJsonObject("settings"),
                            "flat dimension " + entry.id());
                }
                Identifier settings = identifier(jsonString(generator, "settings", ""));
                if (settings != null) {
                    SpriteWorldgenCatalog.Entry noise = SpriteWorldgenCatalog.find(SpriteWorldgenCatalog.Kind.NOISE_SETTINGS, settings).orElse(null);
                    if (noise != null) return generateTerrainProfile(engine, new Request(noise, request.seed(), request.layers(), request.centerX(), request.surfaceY(), request.width(), request.replaceExisting()), settings, SpriteWorldgenCatalog.readJson(noise).orElse(null));
                }
            }
        }
        return generateTerrainProfile(engine, request, entry.id(), json);
    }

    private static Result generateTerrainProfile(SpriteEngine engine, Request request, Identifier sourceId, JsonObject json) {
        Scene scene = engine.scene();
        BlockState defaultBlock = blockStateFromJson(json == null ? null : json.get("default_block"), surfaceBlockFor(sourceId));
        Fluid defaultFluid = fluidFromJson(json == null ? null : json.get("default_fluid"), fluidFor(sourceId));
        int seaLevel = jsonInt(json, "sea_level", dimensionSeaLevel(sourceId));
        String path = sourceId == null ? "" : sourceId.getPath().toLowerCase(Locale.ROOT);
        BlockState top = surfaceBlockFor(sourceId);
        BlockState filler = fillerBlockFor(sourceId);
        int amplitude = path.contains("amplified") || path.contains("mountain") || path.contains("peak") ? 18 : path.contains("flat") ? 1 : 8;
        if (path.contains("nether") || path.contains("caves")) amplitude = 11;
        int minX = request.centerX() - request.width() / 2;
        int blocks = 0;
        int fluids = 0;
        for (int layerIndex = 0; layerIndex < request.layers().size(); layerIndex++) {
            int layer = request.layers().get(layerIndex);
            float layerPhase = layer * 0.37F;
            int layerBias = Math.round(noise1D(layer * 0.81F, request.seed() ^ 0xC2B2AE3D27D4EB4FL) * 4.0F);
            for (int i = 0; i < request.width(); i++) {
                int x = minX + i;
                float broad = noise1D(x * 0.045F + layerPhase, request.seed());
                float detail = noise1D(x * 0.13F - layerPhase * 0.7F, request.seed() ^ 0x9E3779B97F4A7C15L);
                int surface = request.surfaceY() + layerBias + Math.round((broad * 0.74F + detail * 0.26F) * amplitude);
                int floor = surface - (path.contains("nether") ? 18 : 24);
                for (int y = floor; y <= surface; y++) {
                    int below = surface - y;
                    SceneCellPos pos = new SceneCellPos(x, y, layer);
                    if (!request.replaceExisting() && scene.blocks().occupied(pos)) continue;
                    if (below > 4 && shouldCave(x, y, layer, request.seed(), path)) {
                        if (request.replaceExisting()) clearCell(scene, pos);
                        continue;
                    }
                    BlockState state = below == 0 ? top : below <= 3 ? filler : defaultBlock;
                    if (path.contains("badlands") && below < 12) state = (below % 5 == 0 ? Blocks.ORANGE_TERRACOTTA : Blocks.TERRACOTTA).getDefaultState();
                    if (request.replaceExisting()) clearCell(scene, pos);
                    scene.blocks().setSceneOwned(pos, state);
                    blocks++;
                }
                int localSea = request.surfaceY() + Math.max(-4, Math.min(4, seaLevel - 63));
                if (defaultFluid != Fluids.EMPTY && surface < localSea) {
                    for (int y = surface + 1; y <= localSea; y++) {
                        SceneCellPos pos = new SceneCellPos(x, y, layer);
                        if (!request.replaceExisting() && (scene.blocks().occupied(pos) || !scene.fluids().getFluidState(pos).isEmpty())) continue;
                        if (request.replaceExisting()) clearCell(scene, pos);
                        scene.fluidSystem().setSceneSource(pos, defaultFluid);
                        fluids++;
                    }
                }
            }
        }
        return new Result(blocks, fluids, request.layers().size(), "Generated terrain from native resource " + sourceId);
    }

    private static BlockState stateFromPalette(NbtCompound nbt) {
        if (nbt == null) return Blocks.AIR.getDefaultState();
        Identifier id = identifier(nbt.getString("Name"));
        if (id == null || !Registries.BLOCK.containsId(id)) return Blocks.AIR.getDefaultState();
        BlockState state = Registries.BLOCK.get(id).getDefaultState();
        if (nbt.contains("Properties", NbtElement.COMPOUND_TYPE)) {
            NbtCompound props = nbt.getCompound("Properties");
            for (String key : props.getKeys()) state = MinecraftStateCodec.withProperty(state, key, props.getString(key));
        }
        return state;
    }

    private static int mappedLayer(List<Integer> layers, int localDepth, int sizeDepth) {
        if (layers == null || layers.isEmpty()) return localDepth;
        // Layer selection is an explicit authoring boundary. A single selected
        // layer flattens the template's hidden Z depth into that layer instead
        // of leaking blocks into unselected neighboring depths.
        if (layers.size() == 1) return layers.get(0);
        if (sizeDepth <= 1) return layers.get(layers.size() / 2);
        float ratio = Math.max(0.0F, Math.min(1.0F, localDepth / (float) (sizeDepth - 1)));
        int index = Math.round(ratio * (layers.size() - 1));
        return layers.get(Math.max(0, Math.min(layers.size() - 1, index)));
    }

    private static void clearCell(Scene scene, SceneCellPos pos) {
        if (scene.blocks().occupied(pos)) scene.blocks().remove(pos);
        if (!scene.fluids().getFluidState(pos).isEmpty()) scene.fluids().remove(pos);
    }

    private static void finish(Scene scene) {
        scene.multipartBlocks().reconcile();
        scene.neighborStates().reconcile();
        scene.chestSystem().reconcilePairs();
        scene.neighborStates().reconcile();
    }

    private static int placeOre(Scene scene, int x, int y, int layer, JsonObject config,
                                DimensionFlavor dimension, boolean replace, long seed) {
        if (config == null) return 0;
        int configuredSize = Math.max(1, Math.min(64, jsonInt(config, "size", 8)));
        float discardOnAir = Math.max(0.0F, Math.min(1.0F,
                jsonFloat(config, "discard_chance_on_air_exposure", 0.0F)));

        // The configured size describes a 3D vein volume. A single Koil Z layer
        // should display only the cross-section of that volume, not every ore
        // block in the full vein. sqrt(size) gives a compact, readable slice.
        int projectedCells = Math.max(1, Math.min(7,
                (int) Math.ceil(Math.sqrt(configuredSize) * 0.90)));
        int placed = 0;
        int cx = x;
        int cy = y;
        for (int i = 0; i < projectedCells; i++) {
            SceneCellPos pos = new SceneCellPos(cx, cy, layer);
            var existingCell = scene.blocks().get(pos);
            BlockState existing = existingCell == null ? null : existingCell.blockState();
            BlockState ore = oreStateForHost(config, existing, dimension);
            if (ore != null && !ore.isAir()) {
                long cellSeed = seed ^ ((i + 1L) * 0x9E3779B97F4A7C15L)
                        ^ ((long) cx * 0xD1B54A32D192ED03L)
                        ^ ((long) cy * 0x94D049BB133111EBL);
                boolean discard = discardOnAir > 0.0F && oreExposedToAir(scene, pos)
                        && hash01(cellSeed) < discardOnAir;
                if (!discard && setOre(scene, pos, ore, replace)) placed++;
            }

            long step = seed ^ ((i + 1L) * 0xA0761D6478BD642FL);
            cx += ((step >>> 11) & 1L) == 0L ? 1 : -1;
            int vertical = Math.floorMod((int) (step ^ (step >>> 32)), 5);
            if (vertical == 0) cy--;
            else if (vertical == 1) cy++;
        }
        return placed;
    }

    private static BlockState oreStateForHost(JsonObject config, BlockState existing, DimensionFlavor dimension) {
        if (existing == null || existing.isAir()) return null;
        JsonElement targetsElement = config.get("targets");
        if (targetsElement != null && targetsElement.isJsonArray()) {
            for (JsonElement element : targetsElement.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject target = element.getAsJsonObject();
                JsonObject rule = object(target, "target");
                if (rule == null || !matchesOreRule(existing, rule)) continue;
                BlockState output = blockStateFromJson(target.get("state"), null);
                if (output != null && !output.isAir()) return output;
            }
            return null;
        }

        // Safe compatibility fallback for unusual/modded ore definitions that do
        // not expose vanilla target arrays. Never replace surface/filler blocks.
        if (!genericOreHost(existing, dimension)) return null;
        List<BlockState> outputs = statesFromNamedSubtrees(config, Set.of("state"));
        return firstNonBaseState(outputs, dimension);
    }

    private static boolean matchesOreRule(BlockState state, JsonObject rule) {
        if (state == null || rule == null) return false;
        String type = jsonString(rule, "predicate_type", jsonString(rule, "type", ""))
                .toLowerCase(Locale.ROOT);
        if (type.contains("always_true")) return true;

        String tagRaw = jsonString(rule, "tag", "");
        if (!tagRaw.isBlank() || type.contains("tag_match")) {
            Identifier tagId = identifier(tagRaw);
            if (tagId == null) return false;
            try {
                return state.isIn(TagKey.of(RegistryKeys.BLOCK, tagId));
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        String blockRaw = jsonString(rule, "block", "");
        if (!blockRaw.isBlank() || type.contains("block_match") || type.contains("blockstate_match")) {
            Identifier blockId = identifier(blockRaw);
            if (blockId == null && rule.has("block_state")) {
                BlockState wanted = blockStateFromJson(rule.get("block_state"), null);
                return wanted != null && state.getBlock() == wanted.getBlock();
            }
            return blockId != null && Registries.BLOCK.containsId(blockId)
                    && state.isOf(Registries.BLOCK.get(blockId));
        }
        return false;
    }

    private static boolean genericOreHost(BlockState state, DimensionFlavor dimension) {
        if (state == null || state.isAir()) return false;
        if (dimension == DimensionFlavor.NETHER) {
            return state.isOf(Blocks.NETHERRACK) || state.isOf(Blocks.BASALT) || state.isOf(Blocks.BLACKSTONE);
        }
        if (dimension == DimensionFlavor.END) return state.isOf(Blocks.END_STONE);
        return state.isOf(Blocks.STONE) || state.isOf(Blocks.DEEPSLATE)
                || state.isIn(net.minecraft.registry.tag.BlockTags.STONE_ORE_REPLACEABLES)
                || state.isIn(net.minecraft.registry.tag.BlockTags.DEEPSLATE_ORE_REPLACEABLES);
    }

    private static boolean oreExposedToAir(Scene scene, SceneCellPos pos) {
        int x = pos.x();
        int y = pos.y();
        int z = pos.depth();
        for (SceneCellPos neighbor : List.of(
                new SceneCellPos(x - 1, y, z), new SceneCellPos(x + 1, y, z),
                new SceneCellPos(x, y - 1, z), new SceneCellPos(x, y + 1, z))) {
            var cell = scene.blocks().get(neighbor);
            if (cell == null || cell.blockState() == null || cell.blockState().isAir()) return true;
        }
        return false;
    }

    private static boolean setOre(Scene scene, SceneCellPos pos, BlockState state, boolean replace) {
        if (state == null || state.isAir()) return false;

        // Ores replace host terrain. They must never be authored into a carver's
        // empty cells or on top of the scene surface.
        var existing = scene.blocks().get(pos);
        if (existing == null || existing.blockState() == null || existing.blockState().isAir()) return false;
        if (!replace && !generatedCell(scene, pos)) return false;

        String generatedSource = existing.runtimeData().get("generated_source");
        String sourceSliceZ = existing.runtimeData().get("source_slice_z");
        clearCell(scene, pos);
        scene.blocks().setSceneOwned(pos, state);
        if (generatedSource != null) scene.blocks().putRuntimeData(pos, "generated_source", generatedSource);
        if (sourceSliceZ != null) scene.blocks().putRuntimeData(pos, "source_slice_z", sourceSliceZ);
        if (generatedSource != null) scene.blocks().putRuntimeData(pos, "generated_role", "ore");
        return true;
    }

    private static int placeGeode(Scene scene, int x, int y, int layer, boolean replace) {
        int placed = 0;
        for (int dx = -4; dx <= 4; dx++) for (int dy = -4; dy <= 4; dy++) {
            double d = Math.sqrt(dx * dx + dy * dy);
            BlockState state = d > 3.3 ? Blocks.SMOOTH_BASALT.getDefaultState()
                    : d > 2.4 ? Blocks.CALCITE.getDefaultState()
                    : d > 1.5 ? Blocks.AMETHYST_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState();
            SceneCellPos pos = new SceneCellPos(x + dx, y + dy, layer);
            if (state.isAir()) { if (replace || generatedCell(scene, pos)) clearCell(scene, pos); }
            else if (set(scene, pos, state, replace)) placed++;
        }
        return placed;
    }

    private static int placeSpike(Scene scene, int x, int y, int layer, BlockState state, boolean replace, int height) {
        int placed = 0;
        for (int h = 0; h < height; h++) {
            int radius = Math.max(0, (height - h) / 4);
            for (int dx = -radius; dx <= radius; dx++) if (set(scene, new SceneCellPos(x + dx, y + h, layer), state, replace)) placed++;
        }
        return placed;
    }

    private static int placeUnderwaterMagma(Scene scene, int x, int layer, boolean replace, long seed, int fallback) {
        int placed = 0;
        for (int dx = -3; dx <= 3; dx++) {
            int bx = x + dx;
            int surface = findTerrainSurfaceY(scene, bx, layer, fallback);
            for (int y = surface - 12; y <= surface + 2; y++) {
                SceneCellPos water = new SceneCellPos(bx, y, layer);
                if (scene.fluids().getFluidState(water).isEmpty()) continue;
                SceneCellPos floor = new SceneCellPos(bx, y - 1, layer);
                if (!solidAt(scene, bx, y - 1, layer)) continue;
                if (hash01(seed ^ (bx * 31L) ^ (y * 131L)) < 0.45F) continue;
                if (set(scene, floor, Blocks.MAGMA_BLOCK.getDefaultState(), replace)) placed++;
                break;
            }
        }
        return placed;
    }

    private static int placeBlockColumnFeature(Scene scene, int x, int surfaceAirY, int layer, JsonObject config,
                                               boolean replace, long seed) {
        if (config == null || !config.has("layers") || !config.get("layers").isJsonArray()) return 0;
        int placed = 0;
        int y = surfaceAirY;
        int layerIndex = 0;
        for (JsonElement element : config.getAsJsonArray("layers")) {
            if (!element.isJsonObject()) continue;
            JsonObject layerConfig = element.getAsJsonObject();
            BlockState state = firstProviderState(layerConfig.get("provider"));
            if (state == null) continue;
            int height = Math.max(1, Math.min(16, providerInt(layerConfig.get("height"), 1, seed ^ (layerIndex * 31L))));
            for (int h = 0; h < height; h++) {
                SceneCellPos pos = new SceneCellPos(x, y++, layer);
                if (!scene.blocks().occupied(pos) && set(scene, pos, state, replace)) placed++;
            }
            layerIndex++;
        }
        return placed;
    }

    private static int placeNestedPatchFeature(SpriteEngine engine, Scene scene, int x, int layer, JsonObject config,
                                               boolean replace, long seed, int fallbackSurface, FeatureContext context) {
        JsonObject nested = inlineConfiguredFeature(config == null ? null : config.get("feature"));
        if (nested == null) return -1;
        String nestedType = jsonString(nested, "type", "").toLowerCase(Locale.ROOT);
        JsonObject nestedConfig = object(nested, "config");
        if (nestedConfig == null) nestedConfig = new JsonObject();
        int surface = findTerrainSurfaceY(scene, x, layer, fallbackSurface);
        if (nestedType.contains("block_column")) {
            if (requiresAdjacentWater(config.get("feature")) && !hasAdjacentFluid(scene, x, surface - 1, layer, Fluids.WATER)) return 0;
            return placeBlockColumnFeature(scene, x, surface, layer, nestedConfig, replace, seed);
        }
        if (nestedType.contains("simple_block")) {
            List<BlockState> states = new ArrayList<>();
            collectProviderStates(nestedConfig.get("to_place"), states);
            if (states.isEmpty()) return -1;
            return placeSurfacePatch(scene, x, layer, states, replace, seed, fallbackSurface);
        }
        if (nestedType.contains("bamboo")) {
            return placeBambooPatch(scene, x, surface, layer, replace, seed, jsonFloat(nestedConfig, "probability", 0.0F));
        }
        // A random patch may hold a registry-backed configured/placed feature.
        Identifier id = featureHolderId(config.get("feature"));
        SpriteWorldgenCatalog.Entry entry = resolveFeatureEntry(id);
        if (entry != null) {
            Result nestedResult = generateFeature(engine, new Request(entry, seed, List.of(layer), x + 2, surface, 8, replace), entry, context);
            return nestedResult.blocks();
        }
        return -1;
    }

    private static boolean requiresAdjacentWater(JsonElement element) {
        if (element == null || element.isJsonNull()) return false;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return element.getAsString().contains("water");
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) if (requiresAdjacentWater(child)) return true;
            return false;
        }
        if (!element.isJsonObject()) return false;
        JsonObject object = element.getAsJsonObject();
        String type = jsonString(object, "type", "").toLowerCase(Locale.ROOT);
        if (type.contains("matching_fluids")) {
            if (object.has("fluids") && requiresAdjacentWater(object.get("fluids"))) return true;
        }
        for (var child : object.entrySet()) if (requiresAdjacentWater(child.getValue())) return true;
        return false;
    }

    private static boolean hasAdjacentFluid(Scene scene, int x, int y, int layer, Fluid fluid) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (Math.abs(dx) + Math.abs(dy) != 1) continue;
                var state = scene.fluids().getFluidState(new SceneCellPos(x + dx, y + dy, layer));
                if (state != null && !state.isEmpty() && state.getFluid().matchesType(fluid)) return true;
            }
        }
        return false;
    }

    private static JsonObject inlineConfiguredFeature(JsonElement holder) {
        if (holder == null || holder.isJsonNull() || !holder.isJsonObject()) return null;
        JsonObject object = holder.getAsJsonObject();
        JsonElement feature = object.get("feature");
        if (feature != null && feature.isJsonObject()) {
            JsonObject nested = feature.getAsJsonObject();
            if (nested.has("type")) return nested;
            return inlineConfiguredFeature(nested);
        }
        if (object.has("type")) return object;
        return null;
    }

    private static int placeAquaticColumn(Scene scene, int x, int layer, BlockState body, BlockState tip,
                                          boolean replace, long seed, int fallback) {
        int placed = 0;
        for (int dx = -2; dx <= 2; dx++) {
            int bx = x + dx;
            int surface = findTerrainSurfaceY(scene, bx, layer, fallback);
            int firstFluid = Integer.MIN_VALUE;
            for (int y = surface - 10; y <= surface + 3; y++) {
                if (!scene.fluids().getFluidState(new SceneCellPos(bx, y, layer)).isEmpty()) { firstFluid = y; break; }
            }
            if (firstFluid == Integer.MIN_VALUE) continue;
            int height = 2 + Math.floorMod((int) (seed ^ (bx * 17L)), 5);
            for (int h = 0; h < height; h++) {
                SceneCellPos pos = new SceneCellPos(bx, firstFluid + h, layer);
                if (scene.fluids().getFluidState(pos).isEmpty()) break;
                BlockState state = h == height - 1 ? tip : body;
                if (!scene.blocks().occupied(pos) && set(scene, pos, state, replace)) placed++;
            }
        }
        return placed;
    }

    private static int placeAquaticPatch(Scene scene, int x, int layer, BlockState state, boolean replace,
                                         long seed, int fallback) {
        int placed = 0;
        for (int dx = -3; dx <= 3; dx++) {
            int bx = x + dx;
            int surface = findTerrainSurfaceY(scene, bx, layer, fallback);
            for (int y = surface - 8; y <= surface + 2; y++) {
                SceneCellPos pos = new SceneCellPos(bx, y, layer);
                if (scene.fluids().getFluidState(pos).isEmpty() || scene.blocks().occupied(pos)) continue;
                if (hash01(seed ^ (bx * 43L) ^ (y * 17L)) < 0.55F) continue;
                if (set(scene, pos, state, replace)) placed++;
                break;
            }
        }
        return placed;
    }

    private static int placeRock(Scene scene, int x, int surfaceAirY, int layer, BlockState state, boolean replace, long seed) {
        int placed = 0;
        int radius = 1 + Math.floorMod((int) seed, 2);
        for (int dx = -radius; dx <= radius; dx++) {
            int base = findTerrainSurfaceY(scene, x + dx, layer, surfaceAirY);
            for (int dy = 0; dy <= radius; dy++) {
                if (Math.abs(dx) + dy > radius + 1) continue;
                if (set(scene, new SceneCellPos(x + dx, base + dy, layer), state, replace)) placed++;
            }
        }
        return placed;
    }

    private static int placeEmbeddedSpring(Scene scene, int x, int requestedY, int layer, Fluid fluid, boolean replace) {
        if (fluid == null || fluid == Fluids.EMPTY) return 0;
        int y = requestedY;
        // Find a solid host near the placement sample. Springs are embedded in
        // rock/dirt cavities, never stacked as free-floating source columns.
        SceneCellPos target = null;
        for (int dy = 0; dy <= 6 && target == null; dy++) {
            for (int sign : new int[]{0, -1, 1}) {
                int yy = y + dy * sign;
                SceneCellPos candidate = new SceneCellPos(x, yy, layer);
                BlockState state = scene.blocks().getBlockState(candidate);
                if (state == null || state.isAir()) continue;
                if (solidAt(scene, x - 1, yy, layer) && solidAt(scene, x + 1, yy, layer)
                        && solidAt(scene, x, yy - 1, layer)) target = candidate;
                if (target != null) break;
            }
        }
        if (target == null) return 0;
        if (!replace && !generatedCell(scene, target)) return 0;
        clearCell(scene, target);
        scene.fluidSystem().setSceneSource(target, fluid);
        return 1;
    }

    private static int placeLakeBasin(Scene scene, int x, int requestedY, int surfaceAirY, int layer,
                                      Fluid fluid, boolean replace, long seed) {
        if (fluid == null || fluid == Fluids.EMPTY) return 0;
        boolean underground = requestedY < surfaceAirY - 3;
        int fluidSurfaceY = underground ? requestedY : surfaceAirY - 1;
        int radius = underground ? 3 : 2 + Math.floorMod((int) seed, 2);
        int fluids = 0;

        // Carve a real 2D basin and fill its complete interior down to a retained
        // solid floor. A one-row fluid lid over carved air creates suspended source
        // cells, while a free-standing two-source column creates the exact artifact
        // reported in the playground. Both are wrong for a lake cross-section.
        for (int dx = -radius; dx <= radius; dx++) {
            int depth = Math.max(1, radius - Math.abs(dx));
            for (int d = 0; d < depth; d++) {
                SceneCellPos fluidPos = new SceneCellPos(x + dx, fluidSurfaceY - d, layer);
                if (!replace && scene.blocks().occupied(fluidPos) && !generatedCell(scene, fluidPos)) continue;
                clearCell(scene, fluidPos);
                scene.fluidSystem().setSceneSource(fluidPos, fluid);
                fluids++;
            }
            // Keep the cell immediately below the basin as its floor. If terrain
            // generation left a cave there, seal only generated content so the
            // pool cannot immediately dump into an accidental detached cavity.
            SceneCellPos floor = new SceneCellPos(x + dx, fluidSurfaceY - depth, layer);
            if (!solidAt(scene, floor.x(), floor.y(), floor.depth()) && (replace || generatedCell(scene, floor))) {
                scene.blocks().setSceneOwned(floor, Blocks.STONE.getDefaultState());
            }
        }
        return fluids;
    }

    private static int placeBambooPatch(Scene scene, int x, int surfaceAirY, int layer, boolean replace,
                                        long seed, float podzolChance) {
        int placed = 0;
        int radius = 2 + Math.floorMod((int) (seed >>> 7), 3);
        for (int dx = -radius; dx <= radius; dx++) {
            long local = seed ^ (dx * 0x9E3779B97F4A7C15L);
            if (hash01(local) < 0.32F) continue;
            int bx = x + dx;
            int baseY = findTerrainSurfaceY(scene, bx, layer, surfaceAirY);
            int height = 5 + Math.floorMod((int) (local ^ (local >>> 32)), 8);
            if (podzolChance > 0.0F && hash01(local ^ 0xD1B54A32D192ED03L) < podzolChance) {
                SceneCellPos ground = new SceneCellPos(bx, baseY - 1, layer);
                if (replace || generatedCell(scene, ground)) {
                    clearCell(scene, ground);
                    scene.blocks().setSceneOwned(ground, Blocks.PODZOL.getDefaultState());
                }
            }
            for (int h = 0; h < height; h++) {
                BlockState bamboo = Blocks.BAMBOO.getDefaultState();
                bamboo = MinecraftStateCodec.withProperty(bamboo, "age", h > 1 ? "1" : "0");
                bamboo = MinecraftStateCodec.withProperty(bamboo, "stage", h == height - 1 ? "1" : "0");
                String leaves = h == height - 1 ? "large" : h == height - 2 ? "small" : "none";
                bamboo = MinecraftStateCodec.withProperty(bamboo, "leaves", leaves);
                SceneCellPos pos = new SceneCellPos(bx, baseY + h, layer);
                if (!scene.blocks().occupied(pos) && set(scene, pos, bamboo, replace)) placed++;
            }
        }
        return placed;
    }

    private static int placeVines(Scene scene, int x, int surfaceAirY, int layer, boolean replace, long seed) {
        int placed = 0;
        BlockState vine = Blocks.VINE.getDefaultState();
        vine = MinecraftStateCodec.withProperty(vine, "east", "true");
        for (int dx = -2; dx <= 2; dx++) {
            if (hash01(seed ^ (dx * 31L)) < 0.45F) continue;
            int bx = x + dx;
            int base = findTerrainSurfaceY(scene, bx, layer, surfaceAirY);
            int height = 2 + Math.floorMod((int) (seed + dx * 17L), 4);
            for (int h = 0; h < height; h++) {
                SceneCellPos pos = new SceneCellPos(bx, base + h, layer);
                if (!scene.blocks().occupied(pos) && set(scene, pos, vine, replace)) placed++;
            }
        }
        return placed;
    }

    private static int placeCaveGrowth(Scene scene, int x, int surfaceAirY, int layer, BlockState growth,
                                       boolean replace, long seed) {
        int placed = 0;
        BlockState visible = MinecraftStateCodec.withProperty(growth, "east", "true");
        visible = MinecraftStateCodec.withProperty(visible, "west", "true");
        for (int dx = -4; dx <= 4; dx++) {
            int bx = x + dx;
            int surface = findTerrainSurfaceY(scene, bx, layer, surfaceAirY);
            for (int y = surface - 18; y <= surface - 4; y++) {
                SceneCellPos pos = new SceneCellPos(bx, y, layer);
                if (scene.blocks().occupied(pos)) continue;
                if (!(solidAt(scene, bx - 1, y, layer) || solidAt(scene, bx + 1, y, layer)
                        || solidAt(scene, bx, y - 1, layer) || solidAt(scene, bx, y + 1, layer))) continue;
                if (hash01(seed ^ (bx * 31L) ^ (y * 131L)) < 0.78F) continue;
                if (set(scene, pos, visible, replace)) placed++;
                break;
            }
        }
        return placed;
    }

    private static int placeMonsterRoom(Scene scene, int x, int y, int layer, boolean replace, long seed) {
        int placed = 0;
        int centerY = y;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                SceneCellPos pos = new SceneCellPos(x + dx, centerY + dy, layer);
                boolean shell = Math.abs(dx) == 4 || Math.abs(dy) == 2;
                if (!shell) {
                    if (replace || generatedCell(scene, pos)) clearCell(scene, pos);
                    continue;
                }
                BlockState wall = hash01(seed ^ (dx * 43L) ^ (dy * 101L)) < 0.28F
                        ? Blocks.MOSSY_COBBLESTONE.getDefaultState() : Blocks.COBBLESTONE.getDefaultState();
                if (set(scene, pos, wall, replace)) placed++;
            }
        }
        SceneCellPos spawner = new SceneCellPos(x, centerY - 1, layer);
        if (replace || generatedCell(scene, spawner) || !scene.blocks().occupied(spawner)) {
            clearCell(scene, spawner);
            scene.blocks().setSceneOwned(spawner, Blocks.SPAWNER.getDefaultState());
            placed++;
        }
        return placed;
    }

    private static int freezeSurface(Scene scene, int x, int layer, boolean replace, long seed, int fallback) {
        int placed = 0;
        for (int dx = -3; dx <= 3; dx++) {
            int bx = x + dx;
            int airY = findTerrainSurfaceY(scene, bx, layer, fallback);
            SceneCellPos fluidPos = new SceneCellPos(bx, airY - 1, layer);
            if (!scene.fluids().getFluidState(fluidPos).isEmpty()) {
                scene.fluids().remove(fluidPos);
                scene.blocks().setSceneOwned(fluidPos, Blocks.ICE.getDefaultState());
                placed++;
                continue;
            }
            SceneCellPos snow = new SceneCellPos(bx, airY, layer);
            if (!scene.blocks().occupied(snow) && hash01(seed ^ (bx * 17L)) > 0.25F) {
                if (set(scene, snow, Blocks.SNOW.getDefaultState(), replace)) placed++;
            }
        }
        return placed;
    }

    private static boolean isColdBiome(Identifier biomeId) {
        if (biomeId == null) return false;
        String path = biomeId.getPath().toLowerCase(Locale.ROOT);
        return path.contains("snow") || path.contains("frozen") || path.contains("ice")
                || path.contains("grove") || path.contains("jagged_peaks") || path.contains("frozen_peaks");
    }

    private static boolean solidAt(Scene scene, int x, int y, int layer) {
        BlockState state = scene.blocks().getBlockState(new SceneCellPos(x, y, layer));
        return state != null && !state.isAir();
    }

    private static boolean generatedCell(Scene scene, SceneCellPos pos) {
        var cell = scene.blocks().get(pos);
        return cell != null && cell.runtimeData().containsKey("generated_source");
    }

    private static int placePool(Scene scene, int x, int y, int layer, Fluid fluid, boolean replace) {
        int fluids = 0;
        for (int dx = -3; dx <= 3; dx++) for (int dy = 0; dy <= 1; dy++) {
            if (Math.abs(dx) == 3 && dy == 1) continue;
            SceneCellPos pos = new SceneCellPos(x + dx, y + dy, layer);
            if (!replace && (scene.blocks().occupied(pos) || !scene.fluids().getFluidState(pos).isEmpty())) continue;
            if (replace) clearCell(scene, pos);
            scene.fluidSystem().setSceneSource(pos, fluid);
            fluids++;
        }
        return fluids;
    }

    private static int fillLine(Scene scene, int minX, int maxX, int y, int layer, BlockState state, boolean replace) {
        int placed = 0;
        for (int x = minX; x <= maxX; x++) if (set(scene, new SceneCellPos(x, y, layer), state, replace)) placed++;
        return placed;
    }

    private static int placeColumn(Scene scene, int x, int y, int layer, BlockState state, boolean replace, int height) {
        int placed = 0;
        for (int h = 0; h < height; h++) if (set(scene, new SceneCellPos(x, y + h, layer), state, replace)) placed++;
        return placed;
    }

    private static int placeSurfacePatch(Scene scene, int x, int layer, List<BlockState> states,
                                         boolean replace, long seed, int fallbackSurface) {
        if (states == null || states.isEmpty()) return 0;
        int placed = 0;
        for (int dx = -2; dx <= 2; dx++) {
            long localSeed = seed ^ (dx * 0x9E3779B97F4A7C15L);
            if (hash01(localSeed) < 0.38F) continue;
            int targetX = x + dx;
            int targetY = findFeatureSurfaceY(scene, targetX, layer, fallbackSurface, dimensionFromScene(scene));
            BlockState state = states.get(Math.floorMod((int) (seed + dx * 17L), states.size()));
            SceneCellPos pos = new SceneCellPos(targetX, targetY, layer);
            // Surface vegetation belongs in the first air cell above terrain. Do
            // not jitter it vertically; that produced floating/embedded patches on
            // sloped biome slices and made plains look like corrupted terrain.
            // Leaf-based shrubs receive shallow depth so their cutout canopy can
            // overlap itself and rear terrain just like the configured tree path.
            if (isLeafLike(state)) {
                placed += placeBushCluster(scene, targetX, targetY, layer, state, replace, localSeed);
            } else if (set(scene, pos, state, replace)) {
                placed++;
            }
        }
        return placed;
    }


    private static boolean isLeafLike(BlockState state) {
        if (state == null || state.isAir()) return false;
        try {
            TagKey<Block> leaves = TagKey.of(RegistryKeys.BLOCK, new Identifier("minecraft", "leaves"));
            return state.isIn(leaves);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static int placeBushCluster(Scene scene, int x, int y, int layer, BlockState foliage,
                                        boolean replace, long seed) {
        foliage = MinecraftStateCodec.withProperty(foliage, "persistent", "true");
        foliage = MinecraftStateCodec.withProperty(foliage, "distance", "1");
        int placed = 0;
        int radius = hash01(seed ^ 0xD6E8FEB86659FD93L) > 0.58F ? 2 : 1;
        for (int dx = -radius; dx <= radius; dx++) {
            int height = 1 + ((Math.abs(dx) < radius && hash01(seed ^ (dx * 0x9E3779B97F4A7C15L)) > 0.35F) ? 1 : 0);
            for (int dy = 0; dy < height; dy++) {
                if (Math.abs(dx) == radius && dy > 0) continue;
                long local = seed ^ (dx * 0xC2B2AE3D27D4EB4FL) ^ (dy * 0x632BE59BD9B4E019L);
                if (hash01(local) < 0.14F) continue;
                SceneCellPos same = new SceneCellPos(x + dx, y + dy, layer);
                BlockState existing = scene.blocks().getBlockState(same);
                if ((existing == null || existing.isAir()) && set(scene, same, foliage, replace)) placed++;

                // A foreground leaf cell gives bushes actual visual volume and
                // lets the depth compositor expose solid terrain through the
                // leaf texture's alpha holes instead of drawing a flat wall.
                if (Math.abs(dx) < radius || hash01(local ^ 0xA0761D6478BD642FL) > 0.72F) {
                    SceneCellPos front = new SceneCellPos(x + dx, y + dy, layer + 1);
                    BlockState frontExisting = scene.blocks().getBlockState(front);
                    if ((frontExisting == null || frontExisting.isAir()) && set(scene, front, foliage, false)) placed++;
                }
            }
        }
        return placed;
    }

    private static int placePatch(Scene scene, int x, int y, int layer, List<BlockState> states, boolean replace, long seed) {
        int placed = 0;
        for (int dx = -2; dx <= 2; dx++) {
            if (hash01(seed ^ (dx * 0x9E3779B97F4A7C15L)) < 0.38F) continue;
            BlockState state = states.get(Math.floorMod((int) (seed + dx * 17L), states.size()));
            if (set(scene, new SceneCellPos(x + dx, y + Math.floorMod((int) (seed >>> (dx + 3 & 15)), 2), layer), state, replace)) placed++;
        }
        return placed;
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean set(Scene scene, SceneCellPos pos, BlockState state, boolean replace) {
        if (state == null || state.isAir()) return false;
        if (scene.blocks().occupied(pos)) {
            if (!replace) {
                if (!generatedCell(scene, pos)) return false;
                var existingCell = scene.blocks().get(pos);
                if (existingCell != null && "structure".equals(existingCell.runtimeData().get("generated_role"))) {
                    return false;
                }
            }
            clearCell(scene, pos);
        } else if (replace) {
            clearCell(scene, pos);
        }
        scene.blocks().setSceneOwned(pos, state);
        return true;
    }

    private static BlockState surfaceBlockFor(Identifier id) {
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        if (path.contains("desert") || path.contains("beach")) return Blocks.SAND.getDefaultState();
        if (path.contains("badlands")) return Blocks.RED_SAND.getDefaultState();
        if (path.contains("snow") || path.contains("frozen") || path.contains("ice")) return Blocks.SNOW_BLOCK.getDefaultState();
        if (path.contains("mushroom")) return Blocks.MYCELIUM.getDefaultState();
        if (path.contains("nether") || path.contains("crimson") || path.contains("warped") || path.contains("soul")) return Blocks.NETHERRACK.getDefaultState();
        if (path.contains("end")) return Blocks.END_STONE.getDefaultState();
        return Blocks.GRASS_BLOCK.getDefaultState();
    }

    private static BlockState fillerBlockFor(Identifier id) {
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        if (path.contains("desert") || path.contains("beach")) return Blocks.SANDSTONE.getDefaultState();
        if (path.contains("badlands")) return Blocks.TERRACOTTA.getDefaultState();
        if (path.contains("nether") || path.contains("crimson") || path.contains("warped") || path.contains("soul")) return Blocks.NETHERRACK.getDefaultState();
        if (path.contains("end")) return Blocks.END_STONE.getDefaultState();
        return Blocks.DIRT.getDefaultState();
    }

    private static Fluid fluidFor(Identifier id) {
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("nether") ? Fluids.LAVA : path.contains("end") ? Fluids.EMPTY : Fluids.WATER;
    }

    private static int dimensionSeaLevel(Identifier id) {
        String path = id == null ? "" : id.getPath().toLowerCase(Locale.ROOT);
        return path.contains("nether") ? 32 : path.contains("end") ? -64 : 63;
    }

    private static BlockState blockStateFromJson(JsonElement element, BlockState fallback) {
        if (element == null || element.isJsonNull()) return fallback;
        if (element.isJsonPrimitive()) {
            Identifier id = identifier(element.getAsString());
            return id != null && Registries.BLOCK.containsId(id) ? Registries.BLOCK.get(id).getDefaultState() : fallback;
        }
        if (!element.isJsonObject()) return fallback;
        JsonObject object = element.getAsJsonObject();
        Identifier id = identifier(jsonString(object, "Name", jsonString(object, "name", "")));
        if (id == null || !Registries.BLOCK.containsId(id)) return fallback;
        BlockState state = Registries.BLOCK.get(id).getDefaultState();
        JsonObject props = object(object, "Properties");
        if (props == null) props = object(object, "properties");
        if (props != null) for (var entry : props.entrySet()) if (entry.getValue().isJsonPrimitive()) {
            state = MinecraftStateCodec.withProperty(state, entry.getKey(), entry.getValue().getAsString());
        }
        return state;
    }

    private static Fluid fluidFromJson(JsonElement element, Fluid fallback) {
        if (element == null || element.isJsonNull()) return fallback;
        String value = element.isJsonPrimitive() ? element.getAsString()
                : element.isJsonObject() ? jsonString(element.getAsJsonObject(), "Name", jsonString(element.getAsJsonObject(), "name", "")) : "";
        Identifier id = identifier(value);
        return id != null && Registries.FLUID.containsId(id) ? Registries.FLUID.get(id) : fallback;
    }

    private static void collectIdentifierStrings(JsonElement element, List<Identifier> out) {
        if (element == null || element.isJsonNull() || out.size() >= 64) return;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            Identifier id = identifier(element.getAsString());
            if (id != null) out.add(id);
            return;
        }
        if (element.isJsonArray()) for (JsonElement child : element.getAsJsonArray()) collectIdentifierStrings(child, out);
        else if (element.isJsonObject()) for (var child : element.getAsJsonObject().entrySet()) collectIdentifierStrings(child.getValue(), out);
    }

    private static void collectTemplateLocations(JsonElement element, List<Identifier> out) {
        if (element == null || element.isJsonNull() || out.size() >= 64) return;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("location") && object.get("location").isJsonPrimitive()) {
                Identifier id = identifier(object.get("location").getAsString());
                if (id != null && !out.contains(id)) out.add(id);
            }
            for (var child : object.entrySet()) collectTemplateLocations(child.getValue(), out);
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectTemplateLocations(child, out);
        }
    }

    private static int estimateTemplateWidth(SpriteWorldgenCatalog.Entry template) {
        NbtCompound nbt = SpriteWorldgenCatalog.readStructure(template).orElse(null);
        int[] size = structureSize(nbt);
        return size.length > 0 ? Math.max(1, size[0]) : 8;
    }

    private static int[] structureSize(NbtCompound nbt) {
        if (nbt == null) return new int[0];
        int[] packed = nbt.getIntArray("size");
        if (packed.length >= 3) return packed;
        NbtList listed = nbt.getList("size", NbtElement.INT_TYPE);
        if (listed.size() >= 3) {
            return new int[]{listed.getInt(0), listed.getInt(1), listed.getInt(2)};
        }
        return packed;
    }

    private static boolean relatedStructure(String structure, String template) {
        if (structure.contains("village") && template.contains("village")) return true;
        if (structure.contains("bastion") && template.contains("bastion")) return true;
        if (structure.contains("end_city") && template.contains("end_city")) return true;
        if (structure.contains("ocean_ruin") && template.contains("underwater_ruin")) return true;
        if (structure.contains("ruined_portal") && template.contains("ruined_portal")) return true;
        if (structure.contains("shipwreck") && template.contains("shipwreck")) return true;
        if (structure.contains("igloo") && template.contains("igloo")) return true;
        if (structure.contains("ancient_city") && template.contains("ancient_city")) return true;
        if (structure.contains("trail_ruins") && template.contains("trail_ruins")) return true;
        return structure.contains("pillager") && template.contains("pillager");
    }

    private static boolean shouldCave(int x, int y, int layer, long seed, String path) {
        float threshold = path.contains("cave") || path.contains("nether") ? 0.52F : 0.69F;
        float n = Math.abs(noise1D(x * 0.12F + y * 0.071F + layer * 0.23F, seed ^ (y * 31L)));
        return n > threshold;
    }

    private static float fbm1D(float x, long seed, int octaves) {
        float sum = 0.0F;
        float amplitude = 0.58F;
        float frequency = 1.0F;
        float normalization = 0.0F;
        for (int i = 0; i < octaves; i++) {
            sum += noise1D(x * frequency, seed + i * 0x9E3779B97F4A7C15L) * amplitude;
            normalization += amplitude;
            amplitude *= 0.50F;
            frequency *= 2.03F;
        }
        return normalization <= 0.0F ? 0.0F : sum / normalization;
    }

    private static float fbm2D(float x, float z, long seed, int octaves) {
        float sum = 0.0F;
        float amplitude = 0.58F;
        float frequency = 1.0F;
        float normalization = 0.0F;
        for (int i = 0; i < octaves; i++) {
            sum += valueNoise2D(x * frequency, z * frequency, seed + i * 0x9E3779B97F4A7C15L) * amplitude;
            normalization += amplitude;
            amplitude *= 0.50F;
            frequency *= 2.03F;
        }
        return normalization <= 0.0F ? 0.0F : sum / normalization;
    }

    private static float fbm3D(float x, float y, float z, long seed, int octaves) {
        float sum = 0.0F;
        float amplitude = 0.60F;
        float frequency = 1.0F;
        float normalization = 0.0F;
        for (int i = 0; i < octaves; i++) {
            sum += valueNoise3D(x * frequency, y * frequency, z * frequency, seed + i * 0xC2B2AE3D27D4EB4FL) * amplitude;
            normalization += amplitude;
            amplitude *= 0.50F;
            frequency *= 2.01F;
        }
        return normalization <= 0.0F ? 0.0F : sum / normalization;
    }

    private static float valueNoise2D(float x, float z, long seed) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        int x1 = x0 + 1;
        int z1 = z0 + 1;
        float tx = smooth(x - x0);
        float tz = smooth(z - z0);
        float a = lerp(hashLattice(seed, x0, 0, z0), hashLattice(seed, x1, 0, z0), tx);
        float b = lerp(hashLattice(seed, x0, 0, z1), hashLattice(seed, x1, 0, z1), tx);
        return lerp(a, b, tz);
    }

    private static float valueNoise3D(float x, float y, float z, long seed) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
        int x1 = x0 + 1, y1 = y0 + 1, z1 = z0 + 1;
        float tx = smooth(x - x0), ty = smooth(y - y0), tz = smooth(z - z0);
        float x00 = lerp(hashLattice(seed, x0, y0, z0), hashLattice(seed, x1, y0, z0), tx);
        float x10 = lerp(hashLattice(seed, x0, y1, z0), hashLattice(seed, x1, y1, z0), tx);
        float x01 = lerp(hashLattice(seed, x0, y0, z1), hashLattice(seed, x1, y0, z1), tx);
        float x11 = lerp(hashLattice(seed, x0, y1, z1), hashLattice(seed, x1, y1, z1), tx);
        return lerp(lerp(x00, x10, ty), lerp(x01, x11, ty), tz);
    }

    private static float hashLattice(long seed, int x, int y, int z) {
        long value = seed;
        value ^= x * 0x9E3779B97F4A7C15L;
        value ^= y * 0xC2B2AE3D27D4EB4FL;
        value ^= z * 0x165667B19E3779F9L;
        return hashSigned(value);
    }

    private static float smooth(float value) { return value * value * (3.0F - 2.0F * value); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float noise1D(float x, long seed) {
        int x0 = (int) Math.floor(x);
        int x1 = x0 + 1;
        float t = x - x0;
        t = t * t * (3.0F - 2.0F * t);
        float a = hashSigned(seed ^ (x0 * 0x9E3779B97F4A7C15L));
        float b = hashSigned(seed ^ (x1 * 0x9E3779B97F4A7C15L));
        return a + (b - a) * t;
    }

    private static float hashSigned(long value) { return hash01(value) * 2.0F - 1.0F; }
    private static float hash01(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return (float) ((value >>> 40) & 0xFFFFFFL) / 16777215.0F;
    }

    private static String jsonString(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return fallback;
        try { return object.get(key).getAsString(); } catch (RuntimeException ignored) { return fallback; }
    }

    private static float jsonFloat(JsonObject object, String key, float fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return fallback;
        try { return object.get(key).getAsFloat(); } catch (RuntimeException ignored) { return fallback; }
    }

    private static int jsonInt(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return fallback;
        try { return object.get(key).getAsInt(); } catch (RuntimeException ignored) { return fallback; }
    }

    private static JsonObject object(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null;
    }

    private static Identifier identifier(String value) {
        if (value == null || value.isBlank() || value.startsWith("#")) return null;
        return Identifier.tryParse(value);
    }

    private static String compactType(String value) {
        int colon = value.lastIndexOf(':');
        return colon >= 0 ? value.substring(colon + 1) : value;
    }
}
