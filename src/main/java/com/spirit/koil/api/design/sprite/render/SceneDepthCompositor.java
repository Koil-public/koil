package com.spirit.koil.api.design.sprite.render;

import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import com.spirit.koil.api.design.sprite.world.FluidGrid;
import com.spirit.koil.api.design.sprite.world.Scene;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CPU-side hidden-axis compositor for Koil's orthographic scene.
 *
 * <p>All authoritative layers remain in the grids, but only the exposed
 * contributions are handed to Minecraft's native model renderers. The mask is
 * revision-cached by SceneRenderer, so dense 8/16/32/64 slice scenes pay this
 * visibility walk only when block/fluid state actually changes.</p>
 */
final class SceneDepthCompositor {
    private SceneDepthCompositor() { }

    static DepthMask build(Scene scene) {
        return build(scene, null);
    }

    static DepthMask build(Scene scene, Integer depthFilter) {
        Map<Long, BlockGrid.Entry> frontAny = new HashMap<>();
        Map<Long, BlockGrid.Entry> frontOpaque = new HashMap<>();
        Map<Long, BlockGrid.Entry> frontOverlay = new HashMap<>();
        Map<Long, FluidGrid.Entry> frontFluid = new HashMap<>();
        Map<BlockState, Boolean> occlusionCache = new HashMap<>();
        if (scene == null) return new DepthMask(List.of(), List.of(), List.of(), Map.of());

        List<BlockGrid.Entry> blockEntries = scene.blocks().entries();
        for (BlockGrid.Entry entry : blockEntries) {
            if (!validBlockEntry(entry)) continue;
            int depth = scene.projection().depthCoordinate(entry.position());
            if (depthFilter != null && depth != depthFilter) continue;
            long key = projectedKey(scene.projection(), entry.position());
            putNearer(scene, frontAny, key, entry);
            BlockState state = entry.cell().blockState();
            if (occlusionCache.computeIfAbsent(state, SceneDepthCompositor::fullyOccludes)) {
                putNearer(scene, frontOpaque, key, entry);
            }
        }

        for (BlockGrid.Entry entry : blockEntries) {
            if (!validBlockEntry(entry)) continue;
            BlockState state = entry.cell().blockState();
            if (occlusionCache.computeIfAbsent(state, SceneDepthCompositor::fullyOccludes)) continue;
            int depth = scene.projection().depthCoordinate(entry.position());
            if (depthFilter != null && depth != depthFilter) continue;
            long key = projectedKey(scene.projection(), entry.position());
            BlockGrid.Entry opaque = frontOpaque.get(key);
            if (opaque == null || depth > scene.projection().depthCoordinate(opaque.position())) {
                putNearer(scene, frontOverlay, key, entry);
            }
        }

        for (FluidGrid.Entry entry : scene.fluids().entries()) {
            if (entry == null || entry.position() == null || entry.cell() == null
                    || entry.cell().state() == null || entry.cell().state().isEmpty()) continue;
            int depth = scene.projection().depthCoordinate(entry.position());
            if (depthFilter != null && depth != depthFilter) continue;
            long key = projectedKey(scene.projection(), entry.position());
            BlockGrid.Entry opaque = frontOpaque.get(key);
            if (opaque == null || depth > scene.projection().depthCoordinate(opaque.position())) {
                FluidGrid.Entry current = frontFluid.get(key);
                if (current == null || depth > scene.projection().depthCoordinate(current.position())) {
                    frontFluid.put(key, entry);
                }
            }
        }

        // A ray without a full-cube terminator still needs one visible block.
        // Prefer the nearest partial block already selected as overlay.
        for (Map.Entry<Long, BlockGrid.Entry> entry : frontAny.entrySet()) {
            if (!frontOpaque.containsKey(entry.getKey()) && !frontOverlay.containsKey(entry.getKey())) {
                frontOverlay.put(entry.getKey(), entry.getValue());
            }
        }

        Map<Long, Integer> opaqueDepths = new HashMap<>();
        for (Map.Entry<Long, BlockGrid.Entry> entry : frontOpaque.entrySet()) {
            opaqueDepths.put(entry.getKey(), scene.projection().depthCoordinate(entry.getValue().position()));
        }
        return new DepthMask(
                List.copyOf(frontOpaque.values()),
                List.copyOf(frontOverlay.values()),
                List.copyOf(frontFluid.values()),
                Map.copyOf(opaqueDepths));
    }

    private static boolean validBlockEntry(BlockGrid.Entry entry) {
        return entry != null && entry.position() != null && entry.cell() != null
                && entry.cell().blockState() != null && !entry.cell().blockState().isAir();
    }

    private static void putNearer(Scene scene, Map<Long, BlockGrid.Entry> map,
                                  long key, BlockGrid.Entry candidate) {
        BlockGrid.Entry current = map.get(key);
        int depth = scene.projection().depthCoordinate(candidate.position());
        if (current == null || depth > scene.projection().depthCoordinate(current.position())) {
            map.put(key, candidate);
        }
    }

    static boolean isTranslucent(BlockState state) {
        if (state == null || state.isAir()) return false;
        try {
            return RenderLayers.getBlockLayer(state) == RenderLayer.getTranslucent();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * A hidden layer terminates the depth ray only when Minecraft itself treats
     * the state as a solid, fully opaque cube. Partial/cutout/translucent models
     * remain overlays so terrain behind their actual holes can be seen.
     */
    static boolean fullyOccludes(BlockState state) {
        if (state == null || state.isAir()) return false;
        try {
            if (RenderLayers.getBlockLayer(state) != RenderLayer.getSolid()) return false;
            return state.isOpaqueFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static long projectedKey(SceneProjection projection, SceneCellPos pos) {
        int a;
        int b;
        switch (projection.mode()) {
            case XY_SIDE -> { a = pos.x(); b = pos.y(); }
            case XZ_TOP -> { a = pos.x(); b = pos.depth(); }
            case ZY_SIDE -> { a = pos.depth(); b = pos.y(); }
            default -> { a = pos.x(); b = pos.y(); }
        }
        return (((long) a) << 32) ^ (b & 0xFFFFFFFFL);
    }

    static final class DepthMask {
        private final List<BlockGrid.Entry> opaqueEntries;
        private final List<BlockGrid.Entry> overlayEntries;
        private final List<FluidGrid.Entry> fluidEntries;
        private final Map<Long, Integer> frontOpaqueDepth;

        private DepthMask(List<BlockGrid.Entry> opaqueEntries,
                          List<BlockGrid.Entry> overlayEntries,
                          List<FluidGrid.Entry> fluidEntries,
                          Map<Long, Integer> frontOpaqueDepth) {
            this.opaqueEntries = opaqueEntries;
            this.overlayEntries = overlayEntries;
            this.fluidEntries = fluidEntries;
            this.frontOpaqueDepth = frontOpaqueDepth;
        }

        List<BlockGrid.Entry> opaqueEntries() { return opaqueEntries; }
        List<BlockGrid.Entry> overlayEntries() { return overlayEntries; }
        List<FluidGrid.Entry> fluidEntries() { return fluidEntries; }

        boolean renderBlock(Scene scene, SceneCellPos pos, BlockState state) {
            if (scene == null || pos == null || state == null || state.isAir()) return false;
            List<BlockGrid.Entry> entries = fullyOccludes(state) ? opaqueEntries : overlayEntries;
            for (BlockGrid.Entry entry : entries) if (entry.position().equals(pos)) return true;
            return false;
        }

        boolean renderOpaqueSurfaceBlock(Scene scene, SceneCellPos pos, BlockState state) {
            if (!fullyOccludes(state)) return false;
            for (BlockGrid.Entry entry : opaqueEntries) if (entry.position().equals(pos)) return true;
            return false;
        }

        boolean renderOverlayBlock(Scene scene, SceneCellPos pos, BlockState state) {
            if (state == null || state.isAir() || fullyOccludes(state)) return false;
            for (BlockGrid.Entry entry : overlayEntries) if (entry.position().equals(pos)) return true;
            return false;
        }

        boolean renderFluid(Scene scene, SceneCellPos pos) {
            if (scene == null || pos == null) return false;
            for (FluidGrid.Entry entry : fluidEntries) if (entry.position().equals(pos)) return true;
            return false;
        }

        boolean renderActor(Scene scene, float screenX, float screenY, int depth) {
            if (scene == null || !Float.isFinite(screenX) || !Float.isFinite(screenY)) return false;
            SceneCellPos projected = scene.projection().screenToCellAtDepth(screenX, screenY, depth);
            Integer opaque = frontOpaqueDepth.get(projectedKey(scene.projection(), projected));
            return opaque == null || depth > opaque;
        }
    }
}
