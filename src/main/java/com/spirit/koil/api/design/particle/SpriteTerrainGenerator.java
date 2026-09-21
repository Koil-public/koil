package com.spirit.koil.api.design.particle;

import com.spirit.koil.api.design.sprite.SpriteEngine;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.world.Scene;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.Fluids;

import java.util.*;

/**
 * Deterministic detached terrain authoring for playground scenes.
 *
 * <p>This is intentionally not a hidden Minecraft world generator. It produces
 * scene-owned BlockState/FluidState cells directly, using correlated noise so a
 * group of depth layers reads as one coherent landscape instead of unrelated
 * random slices.</p>
 */
public final class SpriteTerrainGenerator {
    private SpriteTerrainGenerator() { }

    public enum Preset {
        PLAINS("Plains", 2.6F, 0.080F, 0.18F, 0.035F, true),
        FOREST("Forest", 4.2F, 0.067F, 0.22F, 0.050F, true),
        HILLS("Hills", 7.0F, 0.055F, 0.32F, 0.032F, true),
        MOUNTAINS("Mountains", 13.5F, 0.037F, 0.46F, 0.018F, false),
        CAVERNS("Caverns", 5.0F, 0.052F, 0.64F, 0.015F, false);

        private final String label;
        private final float amplitude;
        private final float frequency;
        private final float caveStrength;
        private final float treeChance;
        private final boolean water;

        Preset(String label, float amplitude, float frequency, float caveStrength, float treeChance, boolean water) {
            this.label = label;
            this.amplitude = amplitude;
            this.frequency = frequency;
            this.caveStrength = caveStrength;
            this.treeChance = treeChance;
            this.water = water;
        }

        public String label() { return label; }
        float amplitude() { return amplitude; }
        float frequency() { return frequency; }
        float caveStrength() { return caveStrength; }
        float treeChance() { return treeChance; }
        boolean water() { return water; }
    }

    public record Request(Preset preset, long seed, List<Integer> layers,
                          int centerX, int surfaceY, int width, boolean replaceExisting) {
        public Request {
            preset = preset == null ? Preset.HILLS : preset;
            layers = layers == null || layers.isEmpty() ? List.of(0) : List.copyOf(layers);
            width = Math.max(8, Math.min(256, width));
        }
    }

    public record Result(int blocks, int fluids, int trees, int layers, int width) {
        public String summary() {
            return "Generated " + layers + " layer" + (layers == 1 ? "" : "s") + " | "
                    + blocks + " blocks, " + fluids + " fluids, " + trees + " trees";
        }
    }

    public static Result generate(SpriteEngine spriteEngine, Request request) {
        if (spriteEngine == null || request == null) return new Result(0, 0, 0, 0, 0);
        Scene scene = spriteEngine.scene();
        List<Integer> layers = normalizedLayers(request.layers());
        if (layers.isEmpty()) layers = List.of(0);

        int width = request.width();
        int minX = request.centerX() - width / 2;
        int maxX = minX + width - 1;
        int frontLayer = layers.get(layers.size() - 1);
        int blocks = 0;
        int fluids = 0;
        int trees = 0;

        for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
            int layer = layers.get(layerIndex);
            long layerSeed = mix64(request.seed() ^ (layer * 0x9E3779B97F4A7C15L));
            float layerBlend = layers.size() <= 1 ? 0.0F
                    : (layerIndex / (float) (layers.size() - 1) - 0.5F);
            int layerSurfaceBias = Math.round(layerBlend * Math.min(4.0F, request.preset().amplitude() * 0.35F));
            int waterY = request.surfaceY() - 2 + Math.round(layerBlend);

            for (int x = minX; x <= maxX; x++) {
                float broad = fbm1D(x * request.preset().frequency(), request.seed(), 4);
                float detail = fbm1D(x * request.preset().frequency() * 2.85F, layerSeed, 3);
                float ridge = 1.0F - Math.abs(fbm1D(x * request.preset().frequency() * 0.58F,
                        request.seed() ^ 0x6A09E667F3BCC909L, 3));
                float shape = broad * 0.72F + detail * 0.22F;
                if (request.preset() == Preset.MOUNTAINS) shape += (ridge * ridge - 0.35F) * 0.92F;
                if (request.preset() == Preset.CAVERNS) shape *= 0.72F;
                int surface = request.surfaceY() + layerSurfaceBias
                        + Math.round(shape * request.preset().amplitude());

                int floor = surface - 22;
                for (int y = floor; y <= surface; y++) {
                    int below = surface - y;
                    SceneCellPos pos = new SceneCellPos(x, y, layer);
                    if (!request.replaceExisting() && scene.blocks().occupied(pos)) continue;
                    if (below > 4 && carveCave(x, y, layer, request, layerSeed)) {
                        if (request.replaceExisting()) {
                            scene.blocks().remove(pos);
                            scene.fluids().remove(pos);
                        }
                        continue;
                    }
                    BlockState state = terrainBlock(request.preset(), x, y, surface, below, layer, request.seed());
                    if (request.replaceExisting() && scene.blocks().occupied(pos)) scene.blocks().remove(pos);
                    scene.blocks().setSceneOwned(pos, state);
                    blocks++;
                }

                if (request.preset().water() && surface < waterY) {
                    for (int y = surface + 1; y <= waterY; y++) {
                        SceneCellPos pos = new SceneCellPos(x, y, layer);
                        if (!request.replaceExisting() && (scene.blocks().occupied(pos)
                                || !scene.fluids().getFluidState(pos).isEmpty())) continue;
                        if (request.replaceExisting()) {
                            if (scene.blocks().occupied(pos)) scene.blocks().remove(pos);
                            if (!scene.fluids().getFluidState(pos).isEmpty()) scene.fluids().remove(pos);
                        }
                        scene.fluidSystem().setSceneSource(pos, Fluids.WATER);
                        fluids++;
                    }
                }

                boolean vegetationLayer = layer == frontLayer || (layerIndex % 2 == 0 && layers.size() <= 3);
                if (vegetationLayer && request.preset().treeChance() > 0.0F
                        && hash01(x, layer, request.seed() ^ 0xBB67AE8584CAA73BL) < request.preset().treeChance()
                        && Math.abs(fbm1D(x * 0.17F, request.seed() ^ 0x3C6EF372FE94F82BL, 2)) < 0.78F) {
                    trees += placeTree(scene, x, surface + 1, layer, request.replaceExisting(), request.seed());
                }
            }
        }

        scene.multipartBlocks().reconcile();
        scene.neighborStates().reconcile();
        scene.chestSystem().reconcilePairs();
        scene.neighborStates().reconcile();
        return new Result(blocks, fluids, trees, layers.size(), width);
    }

    public static List<Integer> parseLayers(String expression, int fallback) {
        if (expression == null || expression.isBlank()) return List.of(fallback);
        Set<Integer> values = new LinkedHashSet<>();
        for (String raw : expression.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) continue;
            int dots = token.indexOf("..");
            try {
                if (dots > 0) {
                    int a = Integer.parseInt(token.substring(0, dots).trim());
                    int b = Integer.parseInt(token.substring(dots + 2).trim());
                    int step = a <= b ? 1 : -1;
                    for (int value = a; ; value += step) {
                        values.add(value);
                        if (value == b || values.size() >= 32) break;
                    }
                } else {
                    values.add(Integer.parseInt(token));
                }
            } catch (NumberFormatException ignored) { }
            if (values.size() >= 32) break;
        }
        if (values.isEmpty()) values.add(fallback);
        List<Integer> result = new ArrayList<>(values);
        Collections.sort(result);
        return List.copyOf(result);
    }

    public static String normalizeLayerExpression(String expression, int fallback) {
        List<Integer> layers = parseLayers(expression, fallback);
        StringBuilder builder = new StringBuilder();
        for (int value : layers) {
            if (builder.length() > 0) builder.append(',');
            builder.append(value);
        }
        return builder.toString();
    }

    private static List<Integer> normalizedLayers(List<Integer> values) {
        Set<Integer> unique = new LinkedHashSet<>();
        if (values != null) for (Integer value : values) if (value != null) unique.add(value);
        List<Integer> result = new ArrayList<>(unique);
        Collections.sort(result);
        return result;
    }

    private static BlockState terrainBlock(Preset preset, int x, int y, int surface, int below, int layer, long seed) {
        if (below == 0) {
            if (preset == Preset.CAVERNS) return Blocks.STONE.getDefaultState();
            return Blocks.GRASS_BLOCK.getDefaultState();
        }
        if (below <= 3) return Blocks.DIRT.getDefaultState();
        if (below >= 15) {
            float ore = hash01(x, y * 17 + layer, seed ^ 0xA54FF53A5F1D36F1L);
            if (ore < 0.018F) return Blocks.REDSTONE_ORE.getDefaultState();
            if (ore < 0.040F) return Blocks.DEEPSLATE_IRON_ORE.getDefaultState();
            return Blocks.DEEPSLATE.getDefaultState();
        }
        float ore = hash01(x, y * 31 + layer, seed ^ 0x510E527FADE682D1L);
        if (ore < 0.025F) return Blocks.COAL_ORE.getDefaultState();
        if (ore < 0.042F) return Blocks.IRON_ORE.getDefaultState();
        if (ore < 0.056F) return Blocks.COPPER_ORE.getDefaultState();
        if (ore > 0.988F && below > 7) return Blocks.ANDESITE.getDefaultState();
        if (ore > 0.972F && below > 5) return Blocks.DIORITE.getDefaultState();
        return Blocks.STONE.getDefaultState();
    }

    private static boolean carveCave(int x, int y, int layer, Request request, long layerSeed) {
        float cave = fbm2D(x * 0.105F, y * 0.115F, layerSeed, 3);
        float worm = Math.abs(fbm2D(x * 0.052F + layer * 0.31F, y * 0.073F,
                request.seed() ^ 0x1F83D9ABFB41BD6BL, 2));
        float threshold = 0.72F - request.preset().caveStrength() * 0.22F;
        return cave > threshold && worm < 0.58F;
    }

    /** Returns 1 when a tree was successfully placed, otherwise 0. */
    private static int placeTree(Scene scene, int x, int baseY, int layer, boolean replace, long seed) {
        int height = 3 + (hash01(x, layer, seed ^ 0x5BE0CD19137E2179L) > 0.58F ? 1 : 0);
        for (int i = 0; i < height; i++) {
            SceneCellPos trunk = new SceneCellPos(x, baseY + i, layer);
            if (!replace && scene.blocks().occupied(trunk)) return 0;
        }
        for (int i = 0; i < height; i++) {
            SceneCellPos trunk = new SceneCellPos(x, baseY + i, layer);
            if (replace && scene.blocks().occupied(trunk)) scene.blocks().remove(trunk);
            scene.blocks().setSceneOwned(trunk, Blocks.OAK_LOG.getDefaultState());
        }
        int crownY = baseY + height;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                if (Math.abs(dx) == 2 && (dy == -1 || dy == 2)) continue;
                if (dy == 2 && Math.abs(dx) > 1) continue;
                SceneCellPos leaf = new SceneCellPos(x + dx, crownY + dy, layer);
                if (!replace && scene.blocks().occupied(leaf)) continue;
                if (replace && scene.blocks().occupied(leaf)) scene.blocks().remove(leaf);
                scene.blocks().setSceneOwned(leaf, Blocks.OAK_LEAVES.getDefaultState());
            }
        }
        return 1;
    }

    private static float fbm1D(float x, long seed, int octaves) {
        float sum = 0.0F;
        float amplitude = 0.55F;
        float frequency = 1.0F;
        float normalization = 0.0F;
        for (int i = 0; i < octaves; i++) {
            sum += valueNoise1D(x * frequency, seed + i * 0x9E3779B9L) * amplitude;
            normalization += amplitude;
            amplitude *= 0.52F;
            frequency *= 2.03F;
        }
        return normalization <= 0.0F ? 0.0F : sum / normalization;
    }

    private static float fbm2D(float x, float y, long seed, int octaves) {
        float sum = 0.0F;
        float amplitude = 0.58F;
        float frequency = 1.0F;
        float normalization = 0.0F;
        for (int i = 0; i < octaves; i++) {
            sum += valueNoise2D(x * frequency, y * frequency, seed + i * 0x85EBCA6BL) * amplitude;
            normalization += amplitude;
            amplitude *= 0.50F;
            frequency *= 2.07F;
        }
        return normalization <= 0.0F ? 0.0F : sum / normalization;
    }

    private static float valueNoise1D(float x, long seed) {
        int x0 = fastFloor(x);
        int x1 = x0 + 1;
        float t = smooth(x - x0);
        return lerp(hashSigned(x0, 0, seed), hashSigned(x1, 0, seed), t);
    }

    private static float valueNoise2D(float x, float y, long seed) {
        int x0 = fastFloor(x);
        int y0 = fastFloor(y);
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        float tx = smooth(x - x0);
        float ty = smooth(y - y0);
        float a = lerp(hashSigned(x0, y0, seed), hashSigned(x1, y0, seed), tx);
        float b = lerp(hashSigned(x0, y1, seed), hashSigned(x1, y1, seed), tx);
        return lerp(a, b, ty);
    }

    private static float hashSigned(int x, int y, long seed) { return hash01(x, y, seed) * 2.0F - 1.0F; }

    private static float hash01(int x, int y, long seed) {
        long value = seed;
        value ^= (long) x * 0x9E3779B97F4A7C15L;
        value ^= (long) y * 0xC2B2AE3D27D4EB4FL;
        value = mix64(value);
        return ((value >>> 40) & 0xFFFFFFL) / (float) 0xFFFFFF;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static int fastFloor(float value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    private static float smooth(float value) { return value * value * (3.0F - 2.0F * value); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
