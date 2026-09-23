package com.spirit.koil.api.design.sprite.render;

import com.spirit.koil.api.design.sprite.core.SceneProjection;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Orthographic 2D projection of Minecraft's already-baked BlockState model.
 *
 * <p>Koil deliberately has two projection policies:</p>
 * <ul>
 *   <li><b>front surface</b> for translucent volume blocks such as glass, where
 *       drawing both front/back surfaces in a 2D layer would double alpha;</li>
 *   <li><b>depth composite</b> for opaque/cutout multipart geometry such as
 *       fence gates, doors, stairs, walls and fences. Every baked face with
 *       visible projected area participates and is ordered by hidden-axis depth.
 *       This is the 2D equivalent of an orthographic Minecraft camera.</li>
 * </ul>
 *
 * <p>All original baked vertex coordinates and atlas UVs are preserved.</p>
 */
public final class MinecraftBakedModel2DResolver {
    private static final float AREA_EPSILON = 0.00008F;
    private static final float FACING_EPSILON = 0.0001F;

    public record Vertex(float modelX, float modelY, float modelZ,
                         float screenX, float screenY,
                         float u, float v) { }

    public record Part(Sprite sprite, List<Vertex> vertices,
                       int tintIndex, boolean shade, Direction face,
                       float normalX, float normalY, float normalZ,
                       float projectedArea, float hiddenDepth, float viewFacing) {
        public boolean edgeOn() { return projectedArea <= AREA_EPSILON; }
        public boolean facesAwayFromCamera() { return viewFacing < -FACING_EPSILON; }
    }


    /**
     * Fence gates are one-cell-thick 3D structures. In an XY side view, an
     * east/west-facing gate lies primarily on Minecraft Z and would collapse
     * into a narrow center strip if projected strictly along Z. For UI-world
     * readability we unfold only the gate's own hidden span into the visible
     * axis while keeping its real baked model, UVs and BlockState.
     */
    public List<Part> resolveFenceGate(BlockState state, SceneProjection.Mode projection, long seed) {
        if (state == null || state.isAir() || !state.contains(Properties.HORIZONTAL_FACING)) return List.of();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getBlockRenderManager() == null) return List.of();
        BakedModel model = client.getBlockRenderManager().getModel(state);
        if (model == null) return List.of();

        Direction facing = state.get(Properties.HORIZONTAL_FACING);
        SceneProjection.Mode mode = projection == null ? SceneProjection.Mode.XY_SIDE : projection;
        List<BakedQuad> quads = new ArrayList<>();
        Random random = Random.create(seed);
        quads.addAll(model.getQuads(state, null, random));
        for (Direction direction : Direction.values()) {
            random.setSeed(seed);
            quads.addAll(model.getQuads(state, direction, random));
        }

        List<Part> out = new ArrayList<>();
        for (BakedQuad quad : quads) {
            Part part = decodeFenceGate(quad, mode, facing);
            if (part == null) continue;
            // The gate must be filtered AFTER hidden-axis unfolding. Rev AJ
            // filtered in the strict 3D projection first, permanently discarding
            // gate rails/posts that only acquire visible area after unfolding.
            if (part.projectedArea() > AREA_EPSILON) out.add(part);
        }
        out.sort(Comparator.comparingDouble(Part::hiddenDepth));
        return List.copyOf(out);
    }

    private static Part decodeFenceGate(BakedQuad quad, SceneProjection.Mode mode, Direction facing) {
        if (quad == null || quad.getSprite() == null || facing == null) return null;
        int[] data = quad.getVertexData();
        if (data == null || data.length < 24 || data.length % 4 != 0) return null;
        int stride = data.length / 4;
        if (stride < 6) return null;

        Direction spanDirection = facing.rotateYClockwise();
        Direction.Axis spanAxis = spanDirection.getAxis();
        List<Vertex> vertices = new ArrayList<>(4);
        float[] xyz = new float[12];
        float hiddenDepth = 0.0F;
        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            float x = Float.intBitsToFloat(data[base]);
            float y = Float.intBitsToFloat(data[base + 1]);
            float z = Float.intBitsToFloat(data[base + 2]);
            float u = Float.intBitsToFloat(data[base + 4]);
            float v = Float.intBitsToFloat(data[base + 5]);
            float sx;
            float sy;
            float hidden;
            switch (mode) {
                case XY_SIDE -> {
                    // A fence gate's readable width is the axis perpendicular to
                    // FACING. If that width lives on hidden Z, unfold Z into X.
                    sx = spanAxis == Direction.Axis.Z ? z : x;
                    if (spanDirection.getDirection() == Direction.AxisDirection.NEGATIVE) sx = 1.0F - sx;
                    sy = 1.0F - y;
                    hidden = facing.getAxis() == Direction.Axis.X ? x : z;
                }
                case ZY_SIDE -> {
                    sx = spanAxis == Direction.Axis.X ? x : z;
                    if (spanDirection.getDirection() == Direction.AxisDirection.NEGATIVE) sx = 1.0F - sx;
                    sy = 1.0F - y;
                    hidden = facing.getAxis() == Direction.Axis.X ? x : z;
                }
                case XZ_TOP -> {
                    sx = x;
                    sy = z;
                    hidden = y;
                }
                default -> { sx = x; sy = 1.0F - y; hidden = z; }
            }
            xyz[i * 3] = x; xyz[i * 3 + 1] = y; xyz[i * 3 + 2] = z;
            hiddenDepth += hidden;
            vertices.add(new Vertex(x, y, z, sx, sy, u, v));
        }
        hiddenDepth *= 0.25F;

        float ax = xyz[3] - xyz[0], ay = xyz[4] - xyz[1], az = xyz[5] - xyz[2];
        float bx = xyz[6] - xyz[0], by = xyz[7] - xyz[1], bz = xyz[8] - xyz[2];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 0.000001F) { nx /= length; ny /= length; nz /= length; }
        else if (quad.getFace() != null) {
            nx = quad.getFace().getOffsetX(); ny = quad.getFace().getOffsetY(); nz = quad.getFace().getOffsetZ();
        }
        float area = projectedQuadArea(vertices);
        float viewFacing = dotView(nx, ny, nz, mode);
        return new Part(quad.getSprite(), List.copyOf(vertices),
                quad.hasColor() ? quad.getColorIndex() : -1, quad.hasShade(), quad.getFace(),
                nx, ny, nz, area, hiddenDepth, viewFacing);
    }

    /** Front-facing surface projection. Best for translucent volume blocks. */
    public List<Part> resolve(BlockState state, SceneProjection.Mode projection, long seed) {
        return resolveInternal(state, projection, seed, false, false, false);
    }

    /**
     * Full depth-composited projection for ordinary opaque/cutout block models.
     * Back/side faces are not discarded merely because they face away from the
     * synthetic camera; instead they are depth ordered, allowing multipart thin
     * geometry (gates, doors, stairs, walls) to retain its complete silhouette.
     */
    public List<Part> resolveDepthComposite(BlockState state, SceneProjection.Mode projection, long seed) {
        return resolveInternal(state, projection, seed, true, false, false);
    }

    /**
     * Full depth-composited projection including native surfaces that are exactly
     * edge-on in the active 2D plane. Used by rails and redstone wire.
     */
    public List<Part> resolveWithEdgeSurfaces(BlockState state, SceneProjection.Mode projection, long seed) {
        return resolveInternal(state, projection, seed, true, true, false);
    }

    /**
     * Full depth composite plus both horizontal edge surfaces. Stairs need this
     * because their tread/underside planes are mathematically edge-on in an XY
     * side view; retaining them as thin projected strips makes the native stair
     * profile readable without replacing its texture/model.
     */
    public List<Part> resolveWithProfileEdges(BlockState state, SceneProjection.Mode projection, long seed) {
        return resolveInternal(state, projection, seed, true, true, true);
    }

    private List<Part> resolveInternal(BlockState state, SceneProjection.Mode projection, long seed,
                                       boolean depthComposite, boolean includeEdgeSurfaces,
                                       boolean includeBothProfileEdges) {
        if (state == null || state.isAir()) return List.of();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getBlockRenderManager() == null) return List.of();
        BakedModel model = client.getBlockRenderManager().getModel(state);
        if (model == null) return List.of();

        SceneProjection.Mode mode = projection == null ? SceneProjection.Mode.XY_SIDE : projection;
        Direction viewFace = viewFace(mode);
        List<BakedQuad> quads = new ArrayList<>();
        Random random = Random.create(seed);

        // Non-cullface quads contain internal/multipart geometry.
        quads.addAll(model.getQuads(state, null, random));

        if (depthComposite) {
            // A 2D orthographic composite needs every cull-face bucket, because a
            // thin/multipart model can contain distinct screen-visible geometry on
            // different hidden-axis depths. The renderer sorts these back-to-front.
            for (Direction direction : Direction.values()) {
                random.setSeed(seed);
                quads.addAll(model.getQuads(state, direction, random));
            }
        } else {
            // Translucent front-surface mode deliberately keeps only the face toward
            // the synthetic camera to avoid double-alpha glass/ice rendering.
            random.setSeed(seed);
            quads.addAll(model.getQuads(state, viewFace, random));
        }

        List<Part> result = new ArrayList<>();
        for (BakedQuad quad : quads) {
            Part part = decode(quad, mode);
            if (part == null) continue;

            if (part.projectedArea() > AREA_EPSILON) {
                if (depthComposite) {
                    result.add(part);
                } else {
                    float facing = dotView(part.normalX(), part.normalY(), part.normalZ(), mode);
                    if (facing > FACING_EPSILON || part.face() == viewFace) result.add(part);
                }
            } else if (includeEdgeSurfaces && isHorizontalSurfaceForProjection(part.face(), mode)) {
                if (includeBothProfileEdges) {
                    // Profile geometry such as stairs needs both tread and underside
                    // edges to remain legible in side view, including upside-down
                    // states. They render as thin native-texture strips later.
                    result.add(part);
                } else if (part.face() == upwardSurface(mode)) {
                    // Redstone/rails only need one member of an opposite pair.
                    result.add(part);
                }
            }
        }

        if (depthComposite) {
            // Camera is on the positive hidden axis for each projection mode.
            // Smaller hidden coordinate is farther away and must render first.
            result.sort(Comparator.comparingDouble(Part::hiddenDepth));
        }
        return List.copyOf(result);
    }

    private static Part decode(BakedQuad quad, SceneProjection.Mode mode) {
        if (quad == null || quad.getSprite() == null) return null;
        int[] data = quad.getVertexData();
        if (data == null || data.length < 24 || data.length % 4 != 0) return null;
        int stride = data.length / 4;
        if (stride < 6) return null;

        List<Vertex> vertices = new ArrayList<>(4);
        float[] xyz = new float[12];
        float hiddenDepth = 0.0F;
        for (int i = 0; i < 4; i++) {
            int base = i * stride;
            float x = Float.intBitsToFloat(data[base]);
            float y = Float.intBitsToFloat(data[base + 1]);
            float z = Float.intBitsToFloat(data[base + 2]);
            float u = Float.intBitsToFloat(data[base + 4]);
            float v = Float.intBitsToFloat(data[base + 5]);
            float sx;
            float sy;
            float hidden;
            switch (mode) {
                case XY_SIDE -> { sx = x; sy = 1.0F - y; hidden = z; }
                case ZY_SIDE -> { sx = z; sy = 1.0F - y; hidden = x; }
                case XZ_TOP -> { sx = x; sy = z; hidden = y; }
                default -> { sx = x; sy = 1.0F - y; hidden = z; }
            }
            xyz[i * 3] = x;
            xyz[i * 3 + 1] = y;
            xyz[i * 3 + 2] = z;
            hiddenDepth += hidden;
            vertices.add(new Vertex(x, y, z, sx, sy, u, v));
        }
        hiddenDepth *= 0.25F;

        float ax = xyz[3] - xyz[0];
        float ay = xyz[4] - xyz[1];
        float az = xyz[5] - xyz[2];
        float bx = xyz[6] - xyz[0];
        float by = xyz[7] - xyz[1];
        float bz = xyz[8] - xyz[2];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 0.000001F) { nx /= length; ny /= length; nz /= length; }
        else {
            Direction face = quad.getFace();
            if (face != null) {
                nx = face.getOffsetX(); ny = face.getOffsetY(); nz = face.getOffsetZ();
            }
        }

        float area = projectedQuadArea(vertices);
        float viewFacing = dotView(nx, ny, nz, mode);
        return new Part(quad.getSprite(), List.copyOf(vertices),
                quad.hasColor() ? quad.getColorIndex() : -1,
                quad.hasShade(), quad.getFace(), nx, ny, nz, area, hiddenDepth, viewFacing);
    }

    private static float projectedQuadArea(List<Vertex> v) {
        if (v.size() != 4) return 0.0F;
        float sum = 0.0F;
        for (int i = 0; i < 4; i++) {
            Vertex a = v.get(i);
            Vertex b = v.get((i + 1) & 3);
            sum += a.screenX() * b.screenY() - b.screenX() * a.screenY();
        }
        return Math.abs(sum) * 0.5F;
    }

    private static Direction viewFace(SceneProjection.Mode mode) {
        return switch (mode) {
            case XY_SIDE -> Direction.SOUTH;
            case ZY_SIDE -> Direction.EAST;
            case XZ_TOP -> Direction.UP;
        };
    }

    private static Direction upwardSurface(SceneProjection.Mode mode) {
        return switch (mode) {
            case XY_SIDE, ZY_SIDE -> Direction.UP;
            case XZ_TOP -> Direction.SOUTH;
        };
    }

    private static boolean isHorizontalSurfaceForProjection(Direction face, SceneProjection.Mode mode) {
        if (face == null) return false;
        return switch (mode) {
            case XY_SIDE, ZY_SIDE -> face == Direction.UP || face == Direction.DOWN;
            case XZ_TOP -> face == Direction.NORTH || face == Direction.SOUTH;
        };
    }

    private static float dotView(float nx, float ny, float nz, SceneProjection.Mode mode) {
        return switch (mode) {
            case XY_SIDE -> nz;
            case ZY_SIDE -> nx;
            case XZ_TOP -> ny;
        };
    }
}
