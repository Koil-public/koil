package com.spirit.client.gui.debug;

import com.spirit.koil.api.f3.*;
import com.spirit.koil.api.performance.PerformanceMonitor;
import com.spirit.koil.api.performance.PerformanceSnapshot;
import com.spirit.Main;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.spirit.koil.api.design.KoilScreenBackgrounds.uiRedesignEnabled;

@Environment(EnvType.CLIENT)
public final class F3OverlayRenderer {
    private static final int PANEL_BG = 0x8A050609;
    private static final int PANEL_BG_SOFT = 0x62050609;
    private static GhostTerrainField ghostTerrainFieldCache;
    private static double ghostProjectionRange = 8.0D;
    private static boolean jumpPreviewLocked = false;
    private static double jumpPreviewHorizontal = 0.0D;
    private static double jumpPreviewVertical = 0.42D;
    private static long jumpPreviewStartMs = 0L;
    private static boolean jumpPreviewWasGrounded = true;

    private F3OverlayRenderer() {
    }

    public static void render(DrawContext context, MinecraftClient client) {
        if (!uiRedesignEnabled() || Main.vanillaF3Design() || !F3LayoutState.overlayVisible() || !F3Controller.isPlayableDebugContext(client) || client.options.hudHidden) {
            return;
        }
        client.options.debugEnabled = false;
        F3Snapshot snapshot = F3SnapshotService.latest(client);
        TextRenderer text = client.textRenderer;
        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
        F3Mode mode = snapshot.mode();
        if (mode == F3Mode.COMPACT) {
            renderCompact(context, text, snapshot, width, height);
            return;
        }
        F3LayoutState.beginOverlayFrame();
        renderDebugCrosshairAxes(context, text, client, snapshot, width, height, mode);
        PanelBounds left = renderLeftCore(context, text, snapshot, width, height, mode, mode != F3Mode.PERFORMANCE);
        renderRightPerformance(context, text, snapshot, width, height, mode);
        PanelBounds target = renderTarget(context, text, snapshot.target(), snapshot, height, mode, left);
        renderSelfPanel(context, text, snapshot, height, mode, left, target);
        if (modeShowsGraphRail(mode)) {
            renderGraphRail(context, text, snapshot, width, height);
        }
        F3LayoutState.finishOverlayFrame();
    }

    private static boolean modeShowsGraphRail(F3Mode mode) {
        return mode == F3Mode.GRAPHS || mode == F3Mode.PLAYER || mode == F3Mode.WORLD || mode == F3Mode.TARGET || mode == F3Mode.CREATOR || mode == F3Mode.FULL || mode == F3Mode.INSPECTOR;
    }

    private static boolean targetPanelVisible(F3Mode mode) {
        return mode == F3Mode.NORMAL || mode == F3Mode.ADVANCED || mode == F3Mode.DEVELOPER || mode == F3Mode.TARGET || mode == F3Mode.CREATOR || mode == F3Mode.GRAPHS || mode == F3Mode.INSPECTOR || mode == F3Mode.MODPACK || mode == F3Mode.FULL;
    }

    public static boolean shouldHideVanillaCrosshair(MinecraftClient client) {
        return F3Controller.isPlayableDebugContext(client)
                && uiRedesignEnabled()
                && !Main.vanillaF3Design()
                && F3LayoutState.overlayVisible()
                && !client.options.hudHidden;
    }

    private static boolean crosshairMotionWidgetsVisible(F3Mode mode) {
        return false;
    }

    private static boolean crosshairBlockWidgetsVisible(F3Mode mode) {
        return false;
    }

    private static boolean crosshairThreatWidgetsVisible(F3Mode mode) {
        return false;
    }

    private static void renderDebugCrosshairAxes(DrawContext context, TextRenderer text, MinecraftClient client, F3Snapshot snapshot, int width, int height, F3Mode mode) {
        if (client == null || client.player == null || mode == F3Mode.PERFORMANCE) {
            return;
        }
        int cx = width / 2;
        int cy = height / 2;
        int scale = mode == F3Mode.GRAPHS ? 30 : 26;
        double yaw = client.player.getYaw();
        double pitch = client.player.getPitch();
        AxisProjection x = projectAxis(1.0D, 0.0D, 0.0D, yaw, pitch, scale);
        AxisProjection y = projectAxis(0.0D, 1.0D, 0.0D, yaw, pitch, scale);
        AxisProjection z = projectAxis(0.0D, 0.0D, 1.0D, yaw, pitch, scale);
        AxisProjection nx = projectAxis(-1.0D, 0.0D, 0.0D, yaw, pitch, scale);
        AxisProjection ny = projectAxis(0.0D, -1.0D, 0.0D, yaw, pitch, scale);
        AxisProjection nz = projectAxis(0.0D, 0.0D, -1.0D, yaw, pitch, scale);
        renderWorldLockedGhostGrid(context, text, client, snapshot, cx, cy, mode);
        context.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xCC050609);
        drawAxis(context, text, cx, cy, nx, "-X", 0xFFFF5555, true);
        drawAxis(context, text, cx, cy, ny, "-Y", 0xFF55FF55, true);
        drawAxis(context, text, cx, cy, nz, "-Z", 0xFF5555FF, true);
        drawAxis(context, text, cx, cy, x, "X", 0xFFFF5555, false);
        drawAxis(context, text, cx, cy, y, "Y", 0xFF55FF55, false);
        drawAxis(context, text, cx, cy, z, "Z", 0xFF5555FF, false);
        context.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFE6EDF5);
    }

    private static void renderWorldLockedGhostGrid(DrawContext context, TextRenderer text, MinecraftClient client, F3Snapshot snapshot, int cx, int cy, F3Mode mode) {
        if (client == null || client.player == null || client.world == null || mode == F3Mode.SIMPLE || mode == F3Mode.PERFORMANCE || !ghostMiniWorldEnabled()) {
            return;
        }
        Vec3d eye = client.player.getCameraPosVec(1.0F);
        double yaw = client.player.getYaw();
        double pitch = client.player.getPitch();
        int floorY = (int) Math.floor(client.player.getY()) - 5;
        BlockPos center = BlockPos.ofFloored(client.player.getX(), floorY, client.player.getZ());
        int radius = ghostGridRadius(mode);
        int scale = ghostGridScale(mode, radius);
        int bound = Math.max(74, scale + ghostGridPadding(scale));
        double previousRange = ghostProjectionRange;
        ghostProjectionRange = Math.max(8.0D, radius + 3.0D);
        GhostTerrainField terrain = ghostTerrainField(client, center, floorY, radius, ghostGridScanHeight(mode));
        context.enableScissor(cx - bound, cy - bound, cx + bound + 1, cy + bound + 1);
        drawGhostDepthHeightPlanes(context, eye, terrain, center, yaw, pitch, cx, cy, scale, radius);
        drawGhostTerrainOutline(context, client, terrain, eye, center, yaw, pitch, cx, cy, scale, radius, floorY);
        drawGhostMiniWorldSignals(context, client, terrain, eye, center, yaw, pitch, cx, cy, scale, radius, floorY);
        drawGhostCaveMouthContours(context, client, eye, center, yaw, pitch, cx, cy, scale, radius, floorY);
        drawGhostPlayerOriginMarker(context, client, eye, yaw, pitch, cx, cy, scale, floorY);
        drawGhostLocalChunkReadout(context, text, client, snapshot, cx, cy, scale, floorY);
        context.disableScissor();
        ghostProjectionRange = previousRange;
    }

    private static int ghostGridRadius(F3Mode mode) {
        return switch (mode) {
            case GRAPHS, FULL, INSPECTOR -> 8;
            case DEVELOPER, TARGET, CREATOR, WORLD -> 7;
            default -> 6;
        };
    }

    private static int ghostGridScale(F3Mode mode, int radius) {
        int base = switch (mode) {
            case GRAPHS, FULL, INSPECTOR -> 82;
            case NORMAL -> 62;
            default -> 70;
        };
        return Math.max(54, base - Math.max(0, radius - 6) * 3);
    }

    private static int ghostGridPadding(int scale) {
        return Math.max(18, Math.min(34, scale / 3));
    }

    private static int ghostGridScanHeight(F3Mode mode) {
        return switch (mode) {
            case GRAPHS, FULL, INSPECTOR -> 14;
            case NORMAL -> 10;
            default -> 12;
        };
    }

    private static void drawGhostPlayerFloorSlice(DrawContext context, Vec3d eye, BlockPos center, double yaw, double pitch, int cx, int cy, int scale, int radius) {
    }

    private static void drawGhostDepthHeightPlanes(DrawContext context, Vec3d eye, GhostTerrainField terrain, BlockPos center, double yaw, double pitch, int cx, int cy, int scale, int radius) {
        int range = Math.max(8, radius + 2);
        int step = 2;
        int grid = 0x18E3B735;
        int soft = 0x10E3B735;
        for (int i = -range; i <= range; i += step) {
            drawGhostAxisFinLine(context, yaw, pitch, cx, cy, scale, i, 0.0D, -range, i, 0.0D, range, grid);
            drawGhostAxisFinLine(context, yaw, pitch, cx, cy, scale, -range, 0.0D, i, range, 0.0D, i, grid);
            drawGhostAxisFinLine(context, yaw, pitch, cx, cy, scale, 0.0D, -range, i, 0.0D, range, i, soft);
            drawGhostAxisFinLine(context, yaw, pitch, cx, cy, scale, 0.0D, i, -range, 0.0D, i, range, soft);
            drawGhostAxisFinLine(context, yaw, pitch, cx, cy, scale, i, -range, 0.0D, i, range, 0.0D, soft);
            drawGhostAxisFinLine(context, yaw, pitch, cx, cy, scale, -range, i, 0.0D, range, i, 0.0D, soft);
        }
        drawGhostChunkFinLines(context, center, yaw, pitch, cx, cy, scale, range);
        drawGhostAxisFinLine(context, yaw, pitch, cx, cy, scale, -range, 0.0D, 0.0D, range, 0.0D, 0.0D, 0x38FFFF55);
        drawGhostAxisFinLine(context, yaw, pitch, cx, cy, scale, 0.0D, -range, 0.0D, 0.0D, range, 0.0D, 0x3855FF55);
        drawGhostAxisFinLine(context, yaw, pitch, cx, cy, scale, 0.0D, 0.0D, -range, 0.0D, 0.0D, range, 0x385555FF);
    }

    private static void drawGhostTerrainOutline(DrawContext context, MinecraftClient client, GhostTerrainField terrain, Vec3d eye, BlockPos center, double yaw, double pitch, int cx, int cy, int scale, int radius, int floorY) {
        int side = terrain.side();
        int stride = terrain.radius() > 6 ? 2 : 1;
        for (int ix = 0; ix < side; ix += stride) {
            for (int iz = 0; iz < side; iz += stride) {
                TerrainColumn column = terrain.columns()[ix][iz];
                int wx = terrain.minX() + ix;
                int wz = terrain.minZ() + iz;
                if (column.y() != Integer.MIN_VALUE) {
                    int color = withAlpha(softText(column.color()), stride > 1 ? 58 : 78);
                    drawGhostTerrainExposedEdge(context, eye, terrain, ix, iz, stride, wx, wz, column.y(), yaw, pitch, cx, cy, scale, color);
                }
                if (column.passableY() != Integer.MIN_VALUE) {
                    drawGhostPassablePlantSignal(context, eye, wx, column.passableY(), wz, column.passableHeight(), yaw, pitch, cx, cy, scale, withAlpha(column.passableColor(), stride > 1 ? 62 : 88));
                }
                if (column.underY() != Integer.MIN_VALUE) {
                    int underColor = withAlpha(0xFFB8C4D2, stride > 1 ? 38 : 52);
                    drawGhostTerrainUndersideOutline(context, eye, wx, wz, column.underY(), yaw, pitch, cx, cy, scale, underColor);
                }
                if (column.roofY() != Integer.MIN_VALUE && column.roofY() != column.y() && column.roofY() != column.underY()) {
                    int roofColor = withAlpha(0xFF8FC5FF, stride > 1 ? 26 : 36);
                    drawGhostTerrainExposedEdge(context, eye, terrain, ix, iz, stride, wx, wz, column.roofY(), yaw, pitch, cx, cy, scale, roofColor);
                }
                if (column.waterY() != Integer.MIN_VALUE) {
                    drawGhostWaterSurface(context, eye, terrain, ix, iz, stride, wx, wz, column.waterY(), yaw, pitch, cx, cy, scale, withAlpha(0xFF0085A4, 88));
                }
            }
        }
    }

    private static int ghostTerrainPlaneClipY(GhostTerrainField terrain, int x, int z, int minY) {
        TerrainColumn column = ghostTerrainColumnFromField(terrain, x, z);
        int y = column.y();
        if (column.roofY() != Integer.MIN_VALUE && column.roofY() > y) {
            y = column.roofY();
        }
        return y == Integer.MIN_VALUE ? minY : Math.max(minY, y);
    }

    private static void drawGhostChunkFinLines(DrawContext context, BlockPos center, double yaw, double pitch, int cx, int cy, int scale, int range) {
        int minX = center.getX() - range;
        int maxX = center.getX() + range;
        int minZ = center.getZ() - range;
        int maxZ = center.getZ() + range;
        int firstChunkX = Math.floorDiv(minX, 16) * 16;
        int firstChunkZ = Math.floorDiv(minZ, 16) * 16;
        for (int chunkX = firstChunkX; chunkX <= maxX + 16; chunkX += 16) {
            int localX = chunkX - center.getX();
            if (localX < -range || localX > range) {
                continue;
            }
            drawGhostAxisFinLine(context, yaw, pitch, cx, cy, scale, localX, 0.0D, -range, localX, 0.0D, range, 0x58E3B735);
            drawGhostAxisFinLine(context, yaw, pitch, cx, cy, scale, localX, -range, 0.0D, localX, range, 0.0D, 0x38E3B735);
        }
        for (int chunkZ = firstChunkZ; chunkZ <= maxZ + 16; chunkZ += 16) {
            int localZ = chunkZ - center.getZ();
            if (localZ < -range || localZ > range) {
                continue;
            }
            drawGhostAxisFinLine(context, yaw, pitch, cx, cy, scale, -range, 0.0D, localZ, range, 0.0D, localZ, 0x58E3B735);
            drawGhostAxisFinLine(context, yaw, pitch, cx, cy, scale, 0.0D, -range, localZ, 0.0D, range, localZ, 0x38E3B735);
        }
    }

    private static void drawGhostPassablePlantSignal(DrawContext context, Vec3d eye, int x, int y, int z, double height, double yaw, double pitch, int cx, int cy, int scale, int color) {
        double h = clampDouble(height, 0.18D, 1.2D);
        double bx = x + 0.5D;
        double bz = z + 0.5D;
        double base = y + 0.04D;
        double top = y + h;
        drawWorldLine(context, eye, bx, base, bz, bx, top, bz, yaw, pitch, cx, cy, withAlpha(color, 135), scale);
        drawWorldLine(context, eye, bx - 0.18D, base + h * 0.45D, bz, bx + 0.18D, base + h * 0.68D, bz, yaw, pitch, cx, cy, withAlpha(color, 92), scale);
        drawWorldLine(context, eye, bx, base + h * 0.52D, bz - 0.18D, bx, base + h * 0.76D, bz + 0.18D, yaw, pitch, cx, cy, withAlpha(color, 72), scale);
    }

    private static void drawGhostWaterLine(DrawContext context, Vec3d eye, int x, int z, int y, double yaw, double pitch, int cx, int cy, int scale, int color) {
        double yy = y + 0.94D;
        drawWorldLine(context, eye, x + 0.16D, yy, z + 0.16D, x + 0.84D, yy, z + 0.16D, yaw, pitch, cx, cy, withAlpha(color, 120), scale);
        drawWorldLine(context, eye, x + 0.84D, yy, z + 0.16D, x + 0.84D, yy, z + 0.84D, yaw, pitch, cx, cy, withAlpha(color, 90), scale);
        drawWorldLine(context, eye, x + 0.84D, yy, z + 0.84D, x + 0.16D, yy, z + 0.84D, yaw, pitch, cx, cy, withAlpha(color, 120), scale);
        drawWorldLine(context, eye, x + 0.16D, yy, z + 0.84D, x + 0.16D, yy, z + 0.16D, yaw, pitch, cx, cy, withAlpha(color, 90), scale);
    }

    private static void drawGhostWaterSurface(DrawContext context, Vec3d eye, GhostTerrainField terrain, int ix, int iz, int stride, int wx, int wz, int y, double yaw, double pitch, int cx, int cy, int scale, int color) {
        int side = terrain.side();
        if (ix <= stride || iz <= stride || ix + stride >= side || iz + stride >= side) {
            return;
        }
        boolean transition = ghostWaterTransition(terrain, wx, wz - stride, y) || ghostWaterTransition(terrain, wx, wz + stride, y) || ghostWaterTransition(terrain, wx + stride, wz, y) || ghostWaterTransition(terrain, wx - stride, wz, y);
        int alpha = transition ? 92 : 44;
        drawGhostWaterLine(context, eye, wx, wz, y, yaw, pitch, cx, cy, scale, withAlpha(color, alpha));
        if (transition) {
            double yy = y + 0.945D;
            drawWorldLine(context, eye, wx + 0.50D, yy, wz + 0.18D, wx + 0.50D, yy, wz + 0.82D, yaw, pitch, cx, cy, withAlpha(0xFF0085A4, 48), scale);
            drawWorldLine(context, eye, wx + 0.18D, yy, wz + 0.50D, wx + 0.82D, yy, wz + 0.50D, yaw, pitch, cx, cy, withAlpha(0xFF0085A4, 48), scale);
        }
    }

    private static boolean ghostWaterTransition(GhostTerrainField terrain, int x, int z, int y) {
        TerrainColumn neighbor = ghostTerrainColumnFromField(terrain, x, z);
        return neighbor.waterY() == Integer.MIN_VALUE || Math.abs(neighbor.waterY() - y) >= 1;
    }

    private static void drawGhostTerrainGridOverlay(DrawContext context, Vec3d eye, GhostTerrainField terrain, double yaw, double pitch, int cx, int cy, int scale) {
    }

    private static void drawGhostTerrainGridCell(DrawContext context, Vec3d eye, int x, int z, int y, int stride, double yaw, double pitch, int cx, int cy, int scale, int color) {
    }

    private static void drawGhostTerrainVerticalCore(DrawContext context, Vec3d eye, int x, int z, int floorY, int y, double yaw, double pitch, int cx, int cy, int scale, int color) {
        double px = x + 0.5D;
        double pz = z + 0.5D;
        double bottom = Math.max(floorY - 2.0D, y - 7.0D);
        drawWorldLine(context, eye, px, bottom, pz, px, y + 1.0D, pz, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, px - 0.18D, y + 0.5D, pz, px + 0.18D, y + 0.5D, pz, yaw, pitch, cx, cy, withAlpha(color, 120), scale);
        drawWorldLine(context, eye, px, y + 0.5D, pz - 0.18D, px, y + 0.5D, pz + 0.18D, yaw, pitch, cx, cy, withAlpha(color, 120), scale);
    }

    private static void drawGhostTerrainUndersideOutline(DrawContext context, Vec3d eye, int x, int z, int y, double yaw, double pitch, int cx, int cy, int scale, int color) {
        double yy = y + 0.015D;
        int c = withAlpha(color, Math.min(48, alpha(color)));
        drawWorldLine(context, eye, x + 0.10D, yy, z + 0.10D, x + 0.90D, yy, z + 0.10D, yaw, pitch, cx, cy, c, scale);
        drawWorldLine(context, eye, x + 0.90D, yy, z + 0.10D, x + 0.90D, yy, z + 0.90D, yaw, pitch, cx, cy, c, scale);
        drawWorldLine(context, eye, x + 0.90D, yy, z + 0.90D, x + 0.10D, yy, z + 0.90D, yaw, pitch, cx, cy, c, scale);
        drawWorldLine(context, eye, x + 0.10D, yy, z + 0.90D, x + 0.10D, yy, z + 0.10D, yaw, pitch, cx, cy, c, scale);
    }

    private static void drawGhostTopFaceHint(DrawContext context, Vec3d eye, int x, double y, int z, double yaw, double pitch, int cx, int cy, int scale, int color) {
        drawWorldLine(context, eye, x + 0.08D, y, z + 0.08D, x + 0.92D, y, z + 0.08D, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, x + 0.92D, y, z + 0.08D, x + 0.92D, y, z + 0.92D, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, x + 0.92D, y, z + 0.92D, x + 0.08D, y, z + 0.92D, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, x + 0.08D, y, z + 0.92D, x + 0.08D, y, z + 0.08D, yaw, pitch, cx, cy, color, scale);
    }

    private static void drawGhostTerrainConnection(DrawContext context, Vec3d eye, int x1, int z1, int y1, int x2, int z2, int y2, double yaw, double pitch, int cx, int cy, int scale, int colorA, int colorB) {
        int color = withAlpha(blendColor(colorA, colorB), Math.abs(y1 - y2) > 1 ? 118 : 72);
        double ax = x1 + 0.5D;
        double az = z1 + 0.5D;
        double bx = x2 + 0.5D;
        double bz = z2 + 0.5D;
        drawWorldLine(context, eye, ax, y1 + 1.08D, az, bx, y2 + 1.08D, bz, yaw, pitch, cx, cy, color, scale);
        if (Math.abs(y1 - y2) > 0) {
            double vx = (ax + bx) * 0.5D;
            double vz = (az + bz) * 0.5D;
            drawWorldLine(context, eye, vx, Math.min(y1, y2) + 1.08D, vz, vx, Math.max(y1, y2) + 1.08D, vz, yaw, pitch, cx, cy, withAlpha(color, 132), scale);
        }
    }

    private static void drawGhostTerrainExposedEdge(DrawContext context, Vec3d eye, GhostTerrainField terrain, int ix, int iz, int stride, int wx, int wz, int y, double yaw, double pitch, int cx, int cy, int scale, int color) {
        int side = terrain.side();
        double top = y + 1.015D;
        TerrainColumn north = ghostTerrainColumnFromField(terrain, wx, wz - stride);
        TerrainColumn south = ghostTerrainColumnFromField(terrain, wx, wz + stride);
        TerrainColumn east = ghostTerrainColumnFromField(terrain, wx + stride, wz);
        TerrainColumn west = ghostTerrainColumnFromField(terrain, wx - stride, wz);
        if (iz == 0 || ghostTerrainEdgeVisible(north, y)) {
            drawWorldLine(context, eye, wx + 0.06D, top, wz + 0.06D, wx + 0.94D, top, wz + 0.06D, yaw, pitch, cx, cy, color, scale);
        }
        if (iz + stride >= side || ghostTerrainEdgeVisible(south, y)) {
            drawWorldLine(context, eye, wx + 0.94D, top, wz + 0.94D, wx + 0.06D, top, wz + 0.94D, yaw, pitch, cx, cy, color, scale);
        }
        if (ix + stride >= side || ghostTerrainEdgeVisible(east, y)) {
            drawWorldLine(context, eye, wx + 0.94D, top, wz + 0.06D, wx + 0.94D, top, wz + 0.94D, yaw, pitch, cx, cy, color, scale);
        }
        if (ix == 0 || ghostTerrainEdgeVisible(west, y)) {
            drawWorldLine(context, eye, wx + 0.06D, top, wz + 0.94D, wx + 0.06D, top, wz + 0.06D, yaw, pitch, cx, cy, color, scale);
        }
        drawGhostTerrainDropLine(context, eye, wx + 0.06D, wz + 0.06D, y, north, west, yaw, pitch, cx, cy, scale, color);
        drawGhostTerrainDropLine(context, eye, wx + 0.94D, wz + 0.06D, y, north, east, yaw, pitch, cx, cy, scale, color);
        drawGhostTerrainDropLine(context, eye, wx + 0.94D, wz + 0.94D, y, south, east, yaw, pitch, cx, cy, scale, color);
        drawGhostTerrainDropLine(context, eye, wx + 0.06D, wz + 0.94D, y, south, west, yaw, pitch, cx, cy, scale, color);
    }

    private static boolean ghostTerrainEdgeVisible(TerrainColumn neighbor, int y) {
        return neighbor.y() == Integer.MIN_VALUE || Math.abs(neighbor.y() - y) >= 1;
    }

    private static void drawGhostTerrainDropLine(DrawContext context, Vec3d eye, double x, double z, int y, TerrainColumn a, TerrainColumn b, double yaw, double pitch, int cx, int cy, int scale, int color) {
        int low = Math.max(a.y() == Integer.MIN_VALUE ? y - 4 : a.y(), b.y() == Integer.MIN_VALUE ? y - 4 : b.y());
        if (y - low >= 3) {
            drawWorldLine(context, eye, x, low + 1.05D, z, x, y + 1.02D, z, yaw, pitch, cx, cy, withAlpha(color, 32), scale);
        }
    }

    private static GhostTerrainField ghostTerrainField(MinecraftClient client, BlockPos center, int floorY, int radius, int scanHeight) {
        int safeRadius = Math.max(2, Math.min(radius, 8));
        int side = safeRadius * 2 + 1;
        long bucket = client.world.getTime() / 5L;
        if (ghostTerrainFieldCache != null && ghostTerrainFieldCache.bucket() == bucket && ghostTerrainFieldCache.centerX() == center.getX() && ghostTerrainFieldCache.centerZ() == center.getZ() && ghostTerrainFieldCache.floorY() == floorY && ghostTerrainFieldCache.radius() == safeRadius && ghostTerrainFieldCache.scanHeight() == scanHeight) {
            return ghostTerrainFieldCache;
        }
        int minX = center.getX() - safeRadius;
        int minZ = center.getZ() - safeRadius;
        TerrainColumn[][] columns = new TerrainColumn[side][side];
        for (int ix = 0; ix < side; ix++) {
            for (int iz = 0; iz < side; iz++) {
                columns[ix][iz] = ghostTerrainColumnUncached(client, minX + ix, minZ + iz, floorY, scanHeight);
            }
        }
        ghostTerrainFieldCache = new GhostTerrainField(bucket, center.getX(), center.getZ(), floorY, safeRadius, scanHeight, minX, minZ, side, columns);
        return ghostTerrainFieldCache;
    }

    private static TerrainColumn ghostTerrainColumnFromField(GhostTerrainField field, int x, int z) {
        int ix = x - field.minX();
        int iz = z - field.minZ();
        if (ix < 0 || iz < 0 || ix >= field.side() || iz >= field.side()) {
            return new TerrainColumn(Integer.MIN_VALUE, 0x00000000, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, 0.0D, 0x00000000);
        }
        return field.columns()[ix][iz];
    }

    private static TerrainColumn ghostTerrainColumn(MinecraftClient client, int x, int z, int floorY, int scanHeight) {
        if (ghostTerrainFieldCache != null && ghostTerrainFieldCache.floorY() == floorY && ghostTerrainFieldCache.scanHeight() == scanHeight) {
            TerrainColumn cached = ghostTerrainColumnFromField(ghostTerrainFieldCache, x, z);
            if (cached.y() != Integer.MIN_VALUE || cached.color() != 0x00000000) {
                return cached;
            }
        }
        return ghostTerrainColumnUncached(client, x, z, floorY, scanHeight);
    }

    private static TerrainColumn ghostTerrainColumnUncached(MinecraftClient client, int x, int z, int floorY, int scanHeight) {
        int bestY = Integer.MIN_VALUE;
        int bestColor = 0x338FA0B8;
        int roofY = Integer.MIN_VALUE;
        int waterY = Integer.MIN_VALUE;
        int undersideY = Integer.MIN_VALUE;
        int trunkY = Integer.MIN_VALUE;
        int passableY = Integer.MIN_VALUE;
        double passableHeight = 0.0D;
        int passableColor = 0x00000000;
        int undersideScore = 999999;
        int top = floorY + scanHeight;
        int bottom = floorY - 8;
        boolean seenOpenPocket = false;
        boolean seenSolidAboveOpen = false;
        for (int y = top; y >= bottom; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = client.world.getBlockState(pos);
            FluidState fluid = state.getFluidState();
            String key = ghostBlockKey(state);
            if (ghostIsWaterLike(state, key) && waterY == Integer.MIN_VALUE) {
                BlockState above = client.world.getBlockState(pos.up());
                if (!ghostIsWaterLike(above, ghostBlockKey(above))) {
                    waterY = y;
                }
            }
            boolean passable = ghostPassableVegetationBlock(state, key);
            if (passable && passableY == Integer.MIN_VALUE) {
                passableY = y;
                passableHeight = ghostPassableVisualHeight(state, key);
                passableColor = ghostPassableBlockColor(state, key);
            }
            boolean empty = state.isAir() || !fluid.isEmpty() || state.getCollisionShape(client.world, pos).isEmpty();
            if (empty || passable) {
                seenOpenPocket = true;
                continue;
            }
            boolean structureCore = ghostStructureBlockKey(key);
            if (structureCore && trunkY == Integer.MIN_VALUE) {
                trunkY = y;
            }
            if (bestY == Integer.MIN_VALUE) {
                bestY = y;
                int light = state.getLuminance();
                if (ghostIsHazardBlock(state, key)) {
                    bestColor = 0xFFE06A21;
                } else if (ghostIsPoweredLikeBlock(state, key)) {
                    bestColor = 0xFFA7003A;
                } else if (light > 0) {
                    bestColor = 0xFF8FC5FF;
                } else if (structureCore) {
                    bestColor = 0xFF7FC8C2;
                } else if (state.isSolidBlock(client.world, pos)) {
                    bestColor = ghostIsModdedBlock(state) ? 0x667FC8C2 : 0x668FC5FF;
                } else {
                    bestColor = 0x667FC8C2;
                }
            }
            BlockPos belowPos = pos.down();
            BlockState below = client.world.getBlockState(belowPos);
            boolean openBelow = below.isAir() || !below.getFluidState().isEmpty() || below.getCollisionShape(client.world, belowPos).isEmpty();
            if (openBelow) {
                int score = Math.abs(y - (floorY + 4));
                if (structureCore) {
                    score -= 4;
                }
                if (score < undersideScore) {
                    undersideScore = score;
                    undersideY = y;
                }
            }
            if (seenOpenPocket && roofY == Integer.MIN_VALUE && y >= floorY - 2) {
                roofY = y;
                seenSolidAboveOpen = true;
            }
        }
        if (roofY == Integer.MIN_VALUE && undersideY != Integer.MIN_VALUE) {
            roofY = undersideY;
        }
        if (seenSolidAboveOpen && undersideY == Integer.MIN_VALUE && roofY != Integer.MIN_VALUE) {
            undersideY = roofY;
        }
        if (waterY != Integer.MIN_VALUE && bestY == Integer.MIN_VALUE) {
            bestColor = 0xFF0085A4;
        }
        return new TerrainColumn(bestY, bestColor, roofY, waterY, undersideY, trunkY, passableY, passableHeight, passableColor);
    }


    private static void drawGhostAltitudeRibbons(DrawContext context, TextRenderer text, MinecraftClient client, Vec3d eye, BlockPos center, double yaw, double pitch, int cx, int cy, int scale, int radius, int floorY) {
    }

    private static void drawGhostAltitudeRibbon(DrawContext context, TextRenderer text, Vec3d eye, BlockPos center, double yaw, double pitch, int cx, int cy, int scale, int radius, int y, String label, int color) {
        if (Math.abs(y - center.getY()) > radius + 8) {
            return;
        }
        int minX = center.getX() - radius;
        int maxX = center.getX() + radius + 1;
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius + 1;
        double yy = y + 0.05D;
        drawWorldLine(context, eye, minX, yy, minZ, maxX, yy, minZ, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, maxX, yy, minZ, maxX, yy, maxZ, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, maxX, yy, maxZ, minX, yy, maxZ, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, minX, yy, maxZ, minX, yy, minZ, yaw, pitch, cx, cy, color, scale);
        ProjectedPoint p = projectWorldPoint(eye, minX + 0.2D, yy, minZ + 0.2D, yaw, pitch, scale);
        if (Math.abs(p.x()) <= scale + 20 && Math.abs(p.y()) <= scale + 20) {
            drawText(context, text, label, cx + p.x(), cy + p.y(), softText(color));
        }
    }

    private static void drawGhostSolidVolumeShadowing(DrawContext context, MinecraftClient client, GhostTerrainField terrain, Vec3d eye, BlockPos center, double yaw, double pitch, int cx, int cy, int scale, int radius, int floorY) {
        int stride = terrain.radius() > 5 ? 2 : 1;
        for (int ix = 0; ix < terrain.side(); ix += stride) {
            for (int iz = 0; iz < terrain.side(); iz += stride) {
                TerrainColumn column = terrain.columns()[ix][iz];
                if (column.y() == Integer.MIN_VALUE) {
                    continue;
                }
                int x = terrain.minX() + ix;
                int z = terrain.minZ() + iz;
                int height = Math.max(0, column.y() - floorY + 1);
                int alpha = clamp(10 + height * 9, 12, 72);
                int color = column.color() == 0xFF0085A4 ? withAlpha(0xFF0085A4, alpha) : withAlpha(0xFF050609, alpha);
                drawGhostProjectedCell(context, eye, x, floorY + 0.015D, z, yaw, pitch, cx, cy, scale, color, stride > 1 ? 1.42D : 0.92D);
            }
        }
    }

    private static void drawGhostTerrainSilhouetteShell(DrawContext context, MinecraftClient client, GhostTerrainField terrain, Vec3d eye, BlockPos center, double yaw, double pitch, int cx, int cy, int scale, int radius, int floorY) {
        int side = terrain.side();
        for (int ix = 0; ix < side; ix++) {
            for (int iz = 0; iz < side; iz++) {
                TerrainColumn column = terrain.columns()[ix][iz];
                if (column.y() == Integer.MIN_VALUE) {
                    continue;
                }
                int x = terrain.minX() + ix;
                int z = terrain.minZ() + iz;
                int color = withAlpha(softText(column.color()), 128);
                if (ix + 1 < side) {
                    drawGhostTerrainEdgeIfNeeded(context, eye, terrain.columns()[ix + 1][iz], x, z, x + 1, z, column.y(), floorY, yaw, pitch, cx, cy, scale, color);
                }
                if (iz + 1 < side) {
                    drawGhostTerrainEdgeIfNeeded(context, eye, terrain.columns()[ix][iz + 1], x, z, x, z + 1, column.y(), floorY, yaw, pitch, cx, cy, scale, color);
                }
            }
        }
    }

    private static void drawGhostTerrainEdgeIfNeeded(DrawContext context, Vec3d eye, TerrainColumn other, int x, int z, int nx, int nz, int y, int floorY, double yaw, double pitch, int cx, int cy, int scale, int color) {
        if (other.y() == Integer.MIN_VALUE || Math.abs(other.y() - y) >= 2) {
            double x0 = x + 0.5D;
            double z0 = z + 0.5D;
            double x1 = nx + 0.5D;
            double z1 = nz + 0.5D;
            drawWorldLine(context, eye, x0, y + 1.08D, z0, x1, y + 1.08D, z1, yaw, pitch, cx, cy, color, scale);
            if (Math.abs((other.y() == Integer.MIN_VALUE ? floorY : other.y()) - y) >= 3) {
                drawWorldLine(context, eye, x0, floorY + 0.1D, z0, x0, y + 1.08D, z0, yaw, pitch, cx, cy, withAlpha(color, 82), scale);
            }
        }
    }

    private static void drawGhostMiniWorldSignals(DrawContext context, MinecraftClient client, GhostTerrainField terrain, Vec3d eye, BlockPos center, double yaw, double pitch, int cx, int cy, int scale, int radius, int floorY) {
        drawGhostMiniWorldBlockSignals(context, client, terrain, eye, center, yaw, pitch, cx, cy, scale, radius, floorY);
        drawGhostMiniWorldEntities(context, client, eye, center, yaw, pitch, cx, cy, scale, radius, floorY);
    }

    private static void drawGhostMiniWorldEntities(DrawContext context, MinecraftClient client, Vec3d eye, BlockPos center, double yaw, double pitch, int cx, int cy, int scale, int radius, int floorY) {
        int max = radius > 5 ? 20 : 14;
        int drawn = 0;
        Box box = new Box(center.getX() - radius - 3, floorY - 4, center.getZ() - radius - 3, center.getX() + radius + 4, floorY + 13, center.getZ() + radius + 4);
        List<Entity> entities = new ArrayList<>(client.world.getEntitiesByClass(Entity.class, box, entity -> entity != client.player && entity.isAlive()));
        entities.sort(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(client.player)));
        for (Entity entity : entities) {
            if (drawn >= max) {
                break;
            }
            if (!ghostEntityWorthDrawing(entity, drawn, radius)) {
                drawn++;
                continue;
            }
            drawGhostMiniWorldEntityObject(context, client, eye, entity, yaw, pitch, cx, cy, scale, floorY, radius, drawn < 8);
            drawn++;
        }
    }

    private static int ghostEntitySignalColor(Entity entity) {
        if (entity instanceof ItemEntity) {
            return 0xFFE6EDF5;
        }
        if (entity instanceof PlayerEntity) {
            return 0xFF55C4FF;
        }
        if (entity instanceof MobEntity mob && mob.getTarget() instanceof PlayerEntity) {
            return 0xFFE06A21;
        }
        if (entity instanceof MobEntity) {
            return 0xFFA7003A;
        }
        if (entity instanceof LivingEntity) {
            return 0xFF55FF55;
        }
        return 0xFFB47CFF;
    }

    private static boolean ghostEntityWorthDrawing(Entity entity, int index, int radius) {
        if (index < 12) {
            return true;
        }
        if (entity instanceof ItemEntity) {
            return radius > 5 && index < 18;
        }
        if (entity instanceof MobEntity mob && mob.getTarget() instanceof PlayerEntity) {
            return true;
        }
        return index < (radius > 5 ? 24 : 16);
    }

    private static void drawGhostMiniWorldEntityObject(DrawContext context, MinecraftClient client, Vec3d eye, Entity entity, double yaw, double pitch, int cx, int cy, int scale, int floorY, int radius, boolean detailed) {
        Vec3d pos = entity.getPos();
        Vec3d velocity = entity.getVelocity();
        int color = ghostEntitySignalColor(entity);
        double clampedY = clampDouble(pos.y, floorY - 4.0D, floorY + 13.0D);
        double relation = clampedY - (floorY + 0.04D);
        if (Math.abs(relation) > 0.85D) {
            drawWorldLine(context, eye, pos.x, floorY + 0.06D, pos.z, pos.x, clampedY, pos.z, yaw, pitch, cx, cy, withAlpha(color, relation > 0.0D ? 58 : 42), scale);
        }
        if (velocity.lengthSquared() > 0.0001D) {
            double tail = entity instanceof ItemEntity ? 5.0D : 3.5D;
            drawWorldLine(context, eye, pos.x - velocity.x * tail, clampedY - velocity.y * tail * 0.45D, pos.z - velocity.z * tail, pos.x, clampedY + entity.getHeight() * 0.35D, pos.z, yaw, pitch, cx, cy, withAlpha(color, detailed ? 118 : 78), scale);
        }
        if (entity instanceof ItemEntity item) {
            drawGhostMiniWorldItemObject(context, eye, item, pos.x, clampedY + 0.08D, pos.z, yaw, pitch, cx, cy, scale, color, detailed);
            return;
        }
        double half = clampDouble(entity.getWidth() * 0.5D, 0.12D, 0.82D);
        double height = clampDouble(entity.getHeight(), 0.22D, 2.45D);
        int boxColor = withAlpha(color, detailed ? 145 : 95);
        drawGhostWorldBox(context, eye, pos.x - half, clampedY, pos.z - half, pos.x + half, clampedY + height, pos.z + half, yaw, pitch, cx, cy, boxColor, scale);
        if (entity instanceof LivingEntity) {
            double eyeY = clampedY + height * 0.82D;
            drawWorldLine(context, eye, pos.x - half * 0.80D, eyeY, pos.z + half, pos.x + half * 0.80D, eyeY, pos.z + half, yaw, pitch, cx, cy, withAlpha(0xFFE3B735, detailed ? 112 : 74), scale);
            Vec3d look = entity.getRotationVec(1.0F);
            drawWorldLine(context, eye, pos.x, eyeY, pos.z, pos.x + look.x * 0.72D, eyeY + look.y * 0.14D, pos.z + look.z * 0.72D, yaw, pitch, cx, cy, withAlpha(color, detailed ? 128 : 76), scale);
        }
        if (entity instanceof MobEntity mob && mob.getTarget() != null) {
            Vec3d look = entity.getRotationVec(1.0F);
            drawWorldLine(context, eye, pos.x, clampedY + height * 0.58D, pos.z, pos.x + look.x * 0.50D, clampedY + height * 0.58D, pos.z + look.z * 0.50D, yaw, pitch, cx, cy, 0x88E06A21, scale);
        }
        if (!entity.isOnGround() || Math.abs(velocity.y) > 0.04D) {
            drawWorldLine(context, eye, pos.x + half + 0.08D, clampedY, pos.z, pos.x + half + 0.08D, clampedY + clampDouble(velocity.y * 4.0D, -0.9D, 1.2D), pos.z, yaw, pitch, cx, cy, withAlpha(0xFF8FC5FF, detailed ? 112 : 72), scale);
        }
        if (entity.isTouchingWater()) {
            double waveY = clampedY + Math.min(0.22D, height * 0.14D);
            drawWorldLine(context, eye, pos.x - half * 0.70D, waveY, pos.z, pos.x + half * 0.70D, waveY, pos.z, yaw, pitch, cx, cy, withAlpha(0xFF0085A4, detailed ? 84 : 48), scale);
        }
        if (entity.isOnFire()) {
            drawWorldLine(context, eye, pos.x - half * 0.35D, clampedY + height, pos.z, pos.x, clampedY + height + 0.28D, pos.z, yaw, pitch, cx, cy, withAlpha(0xFFE06A21, detailed ? 140 : 82), scale);
            drawWorldLine(context, eye, pos.x + half * 0.35D, clampedY + height, pos.z, pos.x, clampedY + height + 0.28D, pos.z, yaw, pitch, cx, cy, withAlpha(0xFFE06A21, detailed ? 110 : 66), scale);
        }
    }

    private static void drawGhostMiniWorldItemObject(DrawContext context, Vec3d eye, ItemEntity item, double x, double y, double z, double yaw, double pitch, int cx, int cy, int scale, int color, boolean detailed) {
        int count = clamp(item.getStack().getCount(), 1, 64);
        int layers = clamp((count + 15) / 16, 1, detailed ? 4 : 3);
        double w = 0.20D;
        double h = 0.08D;
        for (int i = 0; i < layers; i++) {
            double lift = i * 0.075D;
            double offset = (i - (layers - 1) * 0.5D) * 0.055D;
            int layerColor = withAlpha(color, 120 + i * 26);
            drawGhostWorldBox(context, eye, x - w + offset, y + lift, z - w - offset, x + w + offset, y + lift + h, z + w - offset, yaw, pitch, cx, cy, layerColor, scale);
        }
        int edge = withAlpha(ghostItemSignalColor(item), 150);
        drawWorldLine(context, eye, x - 0.28D, y + layers * 0.08D + 0.04D, z, x + 0.28D, y + layers * 0.08D + 0.04D, z, yaw, pitch, cx, cy, edge, scale);
    }

    private static void drawGhostMiniWorldBlockObject(DrawContext context, MinecraftClient client, Vec3d eye, BlockPos pos, BlockState state, double yaw, double pitch, int cx, int cy, int scale, int color, int particle) {
        if (color != 0) {
            String key = ghostBlockKey(state);
            if (ghostPassableVegetationBlock(state, key)) {
                drawGhostPassablePlantSignal(context, eye, pos.getX(), pos.getY(), pos.getZ(), ghostPassableVisualHeight(state, key), yaw, pitch, cx, cy, scale, withAlpha(color, 105));
            } else if (ghostIsPoweredLikeBlock(state, key)) {
                drawGhostCircuitSignal(context, eye, pos, yaw, pitch, cx, cy, scale, color);
            } else if (ghostIsLightLikeBlock(state, key)) {
                drawGhostLightSignal(context, eye, pos, yaw, pitch, cx, cy, scale, color);
            } else if (state.hasBlockEntity() || client.world.getBlockEntity(pos) != null || ghostIsDataMachineLikeBlock(state, key) || ghostIsHazardBlock(state, key)) {
                drawGhostMiniWorldBlockMarker(context, eye, pos.getX() + 0.5D, pos.getY() + 0.58D, pos.getZ() + 0.5D, yaw, pitch, cx, cy, scale, color, ghostMiniWorldBlockMarkerSize(client, pos, state));
            }
        }
        if (particle != 0) {
            drawGhostParticleWisp(context, eye, pos.getX() + 0.5D, pos.getY() + 0.8D, pos.getZ() + 0.5D, yaw, pitch, cx, cy, scale, particle);
        }
    }

    private static int ghostMiniWorldBlockMarkerSize(MinecraftClient client, BlockPos pos, BlockState state) {
        if (state.hasBlockEntity() || client.world.getBlockEntity(pos) != null) {
            return 3;
        }
        if (state.emitsRedstonePower() || state.getLuminance() > 0) {
            return 2;
        }
        return 1;
    }

    private static void drawGhostMiniWorldBlockMarker(DrawContext context, Vec3d eye, double x, double y, double z, double yaw, double pitch, int cx, int cy, int scale, int color, int size) {
        ProjectedPoint p = projectWorldPoint(eye, x, y, z, yaw, pitch, scale);
        if (Math.abs(p.x()) > scale + 22 || Math.abs(p.y()) > scale + 22) {
            return;
        }
        int px = cx + clamp(p.x(), -scale - 22, scale + 22);
        int py = cy + clamp(p.y(), -scale - 22, scale + 22);
        context.fill(px - size, py - size, px + size + 1, py + size + 1, withAlpha(color, 135));
        context.fill(px - size, py - size, px + size + 1, py - size + 1, withAlpha(softText(color), 130));
    }

    private static void drawGhostWorldBox(DrawContext context, Vec3d eye, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double yaw, double pitch, int cx, int cy, int color, int scale) {
        drawWorldLine(context, eye, minX, minY, minZ, maxX, minY, minZ, yaw, pitch, cx, cy, withAlpha(color, 150), scale);
        drawWorldLine(context, eye, maxX, minY, minZ, maxX, minY, maxZ, yaw, pitch, cx, cy, withAlpha(color, 150), scale);
        drawWorldLine(context, eye, maxX, minY, maxZ, minX, minY, maxZ, yaw, pitch, cx, cy, withAlpha(color, 150), scale);
        drawWorldLine(context, eye, minX, minY, maxZ, minX, minY, minZ, yaw, pitch, cx, cy, withAlpha(color, 150), scale);
        drawWorldLine(context, eye, minX, maxY, minZ, maxX, maxY, minZ, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, maxX, maxY, minZ, maxX, maxY, maxZ, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, maxX, maxY, maxZ, minX, maxY, maxZ, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, minX, maxY, maxZ, minX, maxY, minZ, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, minX, minY, minZ, minX, maxY, minZ, yaw, pitch, cx, cy, withAlpha(color, 120), scale);
        drawWorldLine(context, eye, maxX, minY, minZ, maxX, maxY, minZ, yaw, pitch, cx, cy, withAlpha(color, 120), scale);
        drawWorldLine(context, eye, maxX, minY, maxZ, maxX, maxY, maxZ, yaw, pitch, cx, cy, withAlpha(color, 120), scale);
        drawWorldLine(context, eye, minX, minY, maxZ, minX, maxY, maxZ, yaw, pitch, cx, cy, withAlpha(color, 120), scale);
    }


    private static String ghostBlockKey(BlockState state) {
        return (String.valueOf(Registries.BLOCK.getId(state.getBlock())) + " " + state.getBlock().getTranslationKey() + " " + state).toLowerCase(Locale.ROOT);
    }

    private static String ghostEntityKey(Entity entity) {
        return (String.valueOf(Registries.ENTITY_TYPE.getId(entity.getType())) + " " + entity.getType().getTranslationKey()).toLowerCase(Locale.ROOT);
    }

    private static String ghostItemKey(ItemEntity item) {
        return (String.valueOf(Registries.ITEM.getId(item.getStack().getItem())) + " " + item.getStack().getItem().getTranslationKey()).toLowerCase(Locale.ROOT);
    }

    private static boolean ghostHasAny(String key, String... terms) {
        String safe = safeValue(key).toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (safe.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ghostIsModdedBlock(BlockState state) {
        return !"minecraft".equals(Registries.BLOCK.getId(state.getBlock()).getNamespace());
    }

    private static boolean ghostIsWaterLike(BlockState state, String key) {
        return !state.getFluidState().isEmpty() && !ghostHasAny(key, "lava", "acid", "toxic", "oil", "fuel", "sludge", "plasma");
    }

    private static boolean ghostIsHazardBlock(BlockState state, String key) {
        return ghostHasAny(key, "lava", "acid", "toxic", "poison", "radiation", "radioactive", "fire", "magma", "cactus", "berry", "thorn", "spike", "barbed", "powder_snow", "campfire", "hazard", "damage", "void", "electric", "charged") || (!state.getFluidState().isEmpty() && !ghostIsWaterLike(state, key));
    }

    private static boolean ghostIsPoweredLikeBlock(BlockState state, String key) {
        return state.emitsRedstonePower() || ghostHasAny(key, "redstone", "powered", "power", "wire", "cable", "signal", "logic", "circuit", "relay", "repeater", "comparator", "observer", "sensor", "button", "lever", "pressure_plate");
    }

    private static boolean ghostIsDataMachineLikeBlock(BlockState state, String key) {
        return ghostHasAny(key, "chest", "barrel", "hopper", "furnace", "smoker", "blast_furnace", "spawner", "container", "crate", "drawer", "shelf", "tank", "pipe", "duct", "conduit", "cable", "wire", "machine", "generator", "engine", "battery", "reactor", "controller", "terminal", "interface", "bus", "network", "storage", "processor", "crusher", "pulverizer", "press", "mixer", "pump", "valve", "piston", "observer", "dispenser", "dropper");
    }

    private static boolean ghostIsLightLikeBlock(BlockState state, String key) {
        return state.getLuminance() > 0 || ghostHasAny(key, "torch", "lantern", "lamp", "light", "glow", "lumen", "candle", "campfire", "beacon", "shroomlight", "sea_lantern");
    }

    private static int ghostItemSignalColor(ItemEntity item) {
        String key = ghostItemKey(item);
        if (ghostHasAny(key, "redstone", "circuit", "wire", "cable")) {
            return 0xFFA7003A;
        }
        if (ghostHasAny(key, "sword", "axe", "pickaxe", "shovel", "hoe", "bow", "crossbow", "trident", "wand", "gun", "weapon", "tool")) {
            return 0xFF8FC5FF;
        }
        if (ghostHasAny(key, "apple", "bread", "food", "meat", "stew", "soup", "berry", "carrot", "potato")) {
            return 0xFF55FF55;
        }
        if (ghostHasAny(key, "ingot", "gem", "crystal", "diamond", "emerald", "ore", "dust", "nugget")) {
            return 0xFFB47CFF;
        }
        if (ghostHasAny(key, "block", "plank", "stone", "brick", "log", "wood")) {
            return 0xFFB8C4D2;
        }
        return 0xFFE6EDF5;
    }

    private static void drawGhostMiniWorldBlockSignals(DrawContext context, MinecraftClient client, GhostTerrainField terrain, Vec3d eye, BlockPos center, double yaw, double pitch, int cx, int cy, int scale, int radius, int floorY) {
        int drawn = 0;
        int max = radius > 6 ? 34 : 24;
        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;
        int minY = floorY - 3;
        int maxY = floorY + (radius > 6 ? 13 : 10);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                TerrainColumn column = ghostTerrainColumnFromField(terrain, x, z);
                int baseY = column.y() == Integer.MIN_VALUE ? floorY : column.y();
                int startY = Math.max(minY, Math.min(baseY - 2, floorY - 2));
                for (int y = startY; y <= maxY; y += 2) {
                    if (drawn >= max) {
                        return;
                    }
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = client.world.getBlockState(pos);
                    int color = ghostMiniWorldBlockSignalColor(client, pos, state);
                    int particle = ghostParticleSourceColor(state);
                    if (color == 0 && particle == 0) {
                        continue;
                    }
                    drawn++;
                    drawGhostMiniWorldBlockObject(context, client, eye, pos, state, yaw, pitch, cx, cy, scale, color, particle);
                    if (!ghostStructureBlock(state) && !ghostPassableVegetationBlock(state, ghostBlockKey(state)) && particle == 0 && !state.hasBlockEntity() && client.world.getBlockEntity(pos) == null && !state.emitsRedstonePower() && state.getLuminance() <= 0) {
                        y += 2;
                    }
                }
            }
        }
    }

    private static boolean ghostStructureBlock(BlockState state) {
        return ghostStructureBlockKey(ghostBlockKey(state));
    }

    private static boolean ghostStructureBlockKey(String key) {
        return ghostHasAny(key, "log", "stem", "wood", "hypha", "bark", "trunk", "branch", "leaves", "leaf", "canopy", "foliage", "plank", "fence", "wall", "rail", "track", "chain", "ladder", "rope", "scaffold", "support", "beam", "pillar", "post", "frame", "mineshaft", "bridge", "catwalk", "strut", "girder");
    }

    private static boolean ghostPassableVegetationBlock(BlockState state, String key) {
        if (state.isAir()) {
            return false;
        }
        boolean waterPlant = ghostHasAny(key, "kelp", "seagrass", "algae", "coral", "sea_pickle", "bubble_column", "water_plant", "waterlogged", "aquatic", "lily");
        if (!state.getFluidState().isEmpty()) {
            return waterPlant;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) {
            return false;
        }
        boolean noCollision = state.getCollisionShape(client.world, BlockPos.ORIGIN).isEmpty();
        if (!noCollision) {
            return false;
        }
        return waterPlant || ghostHasAny(key, "grass", "fern", "vine", "liana", "moss", "sapling", "flower", "root", "bush", "crop", "wheat", "carrot", "potato", "beetroot", "cane", "reed", "bamboo", "web", "petal", "sprout", "fungus", "mushroom", "leaf_litter", "clover", "herb", "weed");
    }

    private static double ghostPassableVisualHeight(BlockState state, String key) {
        if (ghostHasAny(key, "kelp", "bamboo", "cane", "reed", "vine", "liana", "tall", "hanging")) {
            return 1.0D;
        }
        if (ghostHasAny(key, "seagrass", "coral", "bush", "sapling", "mushroom", "fungus")) {
            return 0.72D;
        }
        if (ghostHasAny(key, "grass", "fern", "flower", "root", "crop", "wheat", "carrot", "potato", "beetroot", "sprout", "clover", "herb", "weed")) {
            return 0.52D;
        }
        if (ghostHasAny(key, "petal", "moss", "leaf_litter")) {
            return 0.22D;
        }
        return 0.38D;
    }

    private static int ghostPassableBlockColor(BlockState state, String key) {
        if (ghostHasAny(key, "kelp", "seagrass", "algae", "coral")) {
            return 0xFF0085A4;
        }
        if (ghostHasAny(key, "vine", "liana", "moss", "grass", "fern", "leaves", "leaf", "sapling", "sprout", "clover", "herb", "weed")) {
            return 0xFF2DA700;
        }
        if (ghostHasAny(key, "flower", "petal", "crop", "wheat", "carrot", "potato", "beetroot", "mushroom", "fungus")) {
            return 0xFF7FC8C2;
        }
        if (ghostHasAny(key, "web")) {
            return 0xFFB8C4D2;
        }
        return ghostIsModdedBlock(state) ? 0xFF55C4FF : 0xFF55FF55;
    }

    private static void drawGhostAxisFinLine(DrawContext context, double yaw, double pitch, int cx, int cy, int scale, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
        double range = Math.max(6.0D, ghostProjectionRange);
        ProjectedPoint a = projectCameraWorldVector(x1 / range, y1 / range, z1 / range, yaw, pitch, scale);
        ProjectedPoint b = projectCameraWorldVector(x2 / range, y2 / range, z2 / range, yaw, pitch, scale);
        if (Math.abs(a.x()) > scale + 22 && Math.abs(b.x()) > scale + 22) {
            return;
        }
        if (Math.abs(a.y()) > scale + 22 && Math.abs(b.y()) > scale + 22) {
            return;
        }
        drawLine(context, cx + clamp(a.x(), -scale - 22, scale + 22), cy + clamp(a.y(), -scale - 22, scale + 22), cx + clamp(b.x(), -scale - 22, scale + 22), cy + clamp(b.y(), -scale - 22, scale + 22), color);
    }

    private static void drawGhostBlockShapeModel(DrawContext context, MinecraftClient client, Vec3d eye, BlockPos pos, BlockState state, double yaw, double pitch, int cx, int cy, int scale, int color, int maxBoxes) {
        if (client == null || client.world == null || state == null || state.isAir()) {
            return;
        }
        List<Box> boxes = new ArrayList<>(state.getOutlineShape(client.world, pos).getBoundingBoxes());
        if (boxes.isEmpty()) {
            boxes = new ArrayList<>(state.getCollisionShape(client.world, pos).getBoundingBoxes());
        }
        if (boxes.isEmpty()) {
            double h = ghostPassableVisualHeight(state, ghostBlockKey(state));
            boxes.add(new Box(0.28D, 0.0D, 0.28D, 0.72D, h, 0.72D));
        }
        int limit = Math.max(1, Math.min(maxBoxes, boxes.size()));
        for (int i = 0; i < limit; i++) {
            Box box = boxes.get(i);
            double minX = pos.getX() + clampDouble(box.minX, 0.0D, 1.0D);
            double minY = pos.getY() + clampDouble(box.minY, 0.0D, 1.5D);
            double minZ = pos.getZ() + clampDouble(box.minZ, 0.0D, 1.0D);
            double maxX = pos.getX() + clampDouble(box.maxX, 0.0D, 1.0D);
            double maxY = pos.getY() + clampDouble(box.maxY, 0.0D, 1.5D);
            double maxZ = pos.getZ() + clampDouble(box.maxZ, 0.0D, 1.0D);
            if (maxX - minX < 0.02D || maxY - minY < 0.02D || maxZ - minZ < 0.02D) {
                continue;
            }
            int alpha = i == 0 ? 150 : 92;
            drawGhostWorldBox(context, eye, minX, minY, minZ, maxX, maxY, maxZ, yaw, pitch, cx, cy, withAlpha(color, alpha), scale);
        }
    }

    private static void drawGhostCircuitSignal(DrawContext context, Vec3d eye, BlockPos pos, double yaw, double pitch, int cx, int cy, int scale, int color) {
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.52D;
        double z = pos.getZ() + 0.5D;
        drawWorldLine(context, eye, x - 0.36D, y, z, x + 0.36D, y, z, yaw, pitch, cx, cy, withAlpha(color, 135), scale);
        drawWorldLine(context, eye, x, y, z - 0.36D, x, y, z + 0.36D, yaw, pitch, cx, cy, withAlpha(color, 100), scale);
    }

    private static void drawGhostLightSignal(DrawContext context, Vec3d eye, BlockPos pos, double yaw, double pitch, int cx, int cy, int scale, int color) {
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.62D;
        double z = pos.getZ() + 0.5D;
        drawWorldLine(context, eye, x - 0.24D, y, z, x + 0.24D, y, z, yaw, pitch, cx, cy, withAlpha(color, 92), scale);
        drawWorldLine(context, eye, x, y - 0.24D, z, x, y + 0.24D, z, yaw, pitch, cx, cy, withAlpha(color, 70), scale);
    }

    private static void drawGhostPassableBlockOutline(DrawContext context, Vec3d eye, int x, int z, int y, double height, double yaw, double pitch, int cx, int cy, int scale, int color) {
    }

    private static int ghostMiniWorldBlockSignalColor(MinecraftClient client, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return 0;
        }
        String key = ghostBlockKey(state);
        if (ghostPassableVegetationBlock(state, key)) {
            return withAlpha(ghostPassableBlockColor(state, key), ghostIsModdedBlock(state) ? 104 : 92);
        }
        if (ghostIsWaterLike(state, key)) {
            return 0;
        }
        if (state.hasBlockEntity() || client.world.getBlockEntity(pos) != null) {
            return withAlpha(ghostMachineColor(client, pos, state), 132);
        }
        if (ghostIsPoweredLikeBlock(state, key)) {
            return withAlpha(0xFFA7003A, 126);
        }
        if (ghostIsHazardBlock(state, key)) {
            return withAlpha(0xFFE06A21, 118);
        }
        if (state.getLuminance() > 0 || ghostIsLightLikeBlock(state, key)) {
            return withAlpha(0xFFE3B735, 82);
        }
        if (ghostIsDataMachineLikeBlock(state, key)) {
            return withAlpha(0xFFC8A24A, 120);
        }
        if (ghostStructureBlockKey(key)) {
            return withAlpha(ghostIsModdedBlock(state) ? 0xFF55C4FF : 0xFF7FC8C2, 48);
        }
        return 0;
    }

    private static int ghostParticleSourceColor(BlockState state) {
        if (!state.getFluidState().isEmpty()) {
            return 0;
        }
        String key = ghostBlockKey(state);
        if (ghostHasAny(key, "fire", "campfire", "torch", "lantern", "flame", "spark", "ember", "smoke")) {
            return withAlpha(0xFFE06A21, 104);
        }
        if (ghostHasAny(key, "spore", "cherry", "drip", "amethyst", "crystal", "portal", "glow", "particle", "mystic", "magic", "aura")) {
            return withAlpha(0xFFB47CFF, 86);
        }
        return 0;
    }

    private static void drawGhostParticleWisp(DrawContext context, Vec3d eye, double x, double y, double z, double yaw, double pitch, int cx, int cy, int scale, int color) {
        drawWorldLine(context, eye, x, y, z, x + 0.08D, y + 0.32D, z - 0.04D, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, x - 0.06D, y + 0.10D, z + 0.04D, x + 0.02D, y + 0.42D, z + 0.08D, yaw, pitch, cx, cy, withAlpha(color, 90), scale);
    }

    private static void drawGhostProjectedDiamond(DrawContext context, Vec3d eye, double x, double y, double z, double yaw, double pitch, int cx, int cy, int scale, int color, int size) {
        ProjectedPoint p = projectWorldPoint(eye, x, y, z, yaw, pitch, scale);
        int px = cx + clamp(p.x(), -scale - 18, scale + 18);
        int py = cy + clamp(p.y(), -scale - 18, scale + 18);
        drawLine(context, px, py - size, px + size, py, color);
        drawLine(context, px + size, py, px, py + size, color);
        drawLine(context, px, py + size, px - size, py, color);
        drawLine(context, px - size, py, px, py - size, color);
    }

    private static void drawGhostCaveMouthContours(DrawContext context, MinecraftClient client, Vec3d eye, BlockPos center, double yaw, double pitch, int cx, int cy, int scale, int radius, int floorY) {
        int scanRadius = Math.min(radius, 4);
        int drawn = 0;
        for (int x = center.getX() - scanRadius; x <= center.getX() + scanRadius && drawn < 28; x++) {
            for (int y = floorY - 1; y <= floorY + 7 && drawn < 28; y += 3) {
                for (int z = center.getZ() - scanRadius; z <= center.getZ() + scanRadius && drawn < 28; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!client.world.getBlockState(pos).isAir()) {
                        continue;
                    }
                    int solidSides = ghostSolidNeighbor(client, pos.east()) + ghostSolidNeighbor(client, pos.west()) + ghostSolidNeighbor(client, pos.north()) + ghostSolidNeighbor(client, pos.south()) + ghostSolidNeighbor(client, pos.down());
                    if (solidSides < 3) {
                        continue;
                    }
                    int color = y < floorY + 2 ? 0x72B47CFF : 0x508FC5FF;
                    drawGhostCaveCell(context, eye, x, y, z, yaw, pitch, cx, cy, scale, color);
                    drawn++;
                }
            }
        }
    }

    private static int ghostSolidNeighbor(MinecraftClient client, BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        return state.isAir() || !state.getFluidState().isEmpty() ? 0 : 1;
    }

    private static void drawGhostCaveCell(DrawContext context, Vec3d eye, int x, int y, int z, double yaw, double pitch, int cx, int cy, int scale, int color) {
        double yy = y + 0.5D;
        drawWorldLine(context, eye, x + 0.15D, yy, z + 0.15D, x + 0.85D, yy, z + 0.15D, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, x + 0.85D, yy, z + 0.15D, x + 0.85D, yy, z + 0.85D, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, x + 0.85D, yy, z + 0.85D, x + 0.15D, yy, z + 0.85D, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, x + 0.15D, yy, z + 0.85D, x + 0.15D, yy, z + 0.15D, yaw, pitch, cx, cy, color, scale);
    }

    private static void drawGhostWorldEventStreaks(DrawContext context, MinecraftClient client, Vec3d eye, double yaw, double pitch, int cx, int cy, int scale, F3Mode mode) {
        int range = mode == F3Mode.NORMAL ? 8 : 12;
        List<Entity> entities = new ArrayList<>(client.world.getEntitiesByClass(Entity.class, client.player.getBoundingBox().expand(range), entity -> entity != client.player && entity.isAlive()));
        entities.sort(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(client.player)));
        int shown = 0;
        int limit = mode == F3Mode.NORMAL ? 5 : 10;
        for (Entity entity : entities) {
            if (shown >= limit) {
                break;
            }
            Vec3d pos = entity.getPos();
            Vec3d velocity = entity.getVelocity();
            double distance = Math.sqrt(entity.squaredDistanceTo(client.player));
            int color = crosshairEntityColor(entity, distance, range, client.targetedEntity == entity);
            double lift = entity.getHeight() * 0.45D;
            double tailScale = entity instanceof ItemEntity ? 7.0D : 5.0D;
            drawWorldLine(context, eye, pos.x - velocity.x * tailScale, pos.y + lift - velocity.y * tailScale, pos.z - velocity.z * tailScale, pos.x, pos.y + lift, pos.z, yaw, pitch, cx, cy, withAlpha(color, 150), scale + 2);
            ProjectedPoint head = projectWorldPoint(eye, pos.x, pos.y + lift, pos.z, yaw, pitch, scale + 2);
            if (Math.abs(head.x()) <= scale + 22 && Math.abs(head.y()) <= scale + 22) {
                int px = cx + head.x();
                int py = cy + head.y();
                if (entity instanceof ItemEntity) {
                    context.fill(px - 2, py, px + 1, py + 3, withAlpha(color, 170));
                    context.fill(px, py - 2, px + 3, py + 1, withAlpha(color, 170));
                } else {
                    context.fill(px - 2, py - 2, px + 3, py + 3, withAlpha(color, 155));
                    context.fill(px - 1, py + 3, px + 2, py + 4, withAlpha(softText(color), 190));
                }
            }
            shown++;
        }
    }

    private static void drawGhostRedstoneBlockEntityVeins(DrawContext context, MinecraftClient client, Vec3d eye, BlockPos center, double yaw, double pitch, int cx, int cy, int scale, int radius, int floorY) {
        List<BlockPos> nodes = new ArrayList<>();
        int scanRadius = Math.min(radius, 5);
        for (int x = center.getX() - scanRadius; x <= center.getX() + scanRadius && nodes.size() < 32; x++) {
            for (int z = center.getZ() - scanRadius; z <= center.getZ() + scanRadius && nodes.size() < 32; z++) {
                for (int y = floorY - 1; y <= floorY + 7 && nodes.size() < 32; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = client.world.getBlockState(pos);
                    if (ghostIsDataMachineBlock(client, pos, state)) {
                        nodes.add(pos);
                        int color = ghostMachineColor(client, pos, state);
                        drawGhostProjectedCell(context, eye, x, y + 0.55D, z, yaw, pitch, cx, cy, scale, withAlpha(color, 68), 0.54D);
                    }
                }
            }
        }
        for (int i = 0; i < nodes.size(); i++) {
            BlockPos a = nodes.get(i);
            for (int j = i + 1; j < nodes.size(); j++) {
                BlockPos b = nodes.get(j);
                int manhattan = Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
                if (manhattan == 1) {
                    int color = ghostMachineColor(client, a, client.world.getBlockState(a));
                    drawWorldLine(context, eye, a.getX() + 0.5D, a.getY() + 0.55D, a.getZ() + 0.5D, b.getX() + 0.5D, b.getY() + 0.55D, b.getZ() + 0.5D, yaw, pitch, cx, cy, withAlpha(color, 150), scale + 1);
                }
            }
        }
    }

    private static boolean ghostIsDataMachineBlock(MinecraftClient client, BlockPos pos, BlockState state) {
        if (client.world.getBlockEntity(pos) != null || state.emitsRedstonePower()) {
            return true;
        }
        return ghostIsDataMachineLikeBlock(state, ghostBlockKey(state));
    }

    private static int ghostMachineColor(MinecraftClient client, BlockPos pos, BlockState state) {
        String key = ghostBlockKey(state);
        if (client.world.getBlockEntity(pos) != null) {
            if (ghostHasAny(key, "energy", "power", "generator", "battery", "reactor", "engine")) {
                return 0xFF8FC5FF;
            }
            if (ghostHasAny(key, "tank", "fluid", "pipe", "duct", "conduit")) {
                return 0xFF0085A4;
            }
            return 0xFFB47CFF;
        }
        if (ghostIsPoweredLikeBlock(state, key)) {
            return 0xFFA7003A;
        }
        if (ghostIsDataMachineLikeBlock(state, key)) {
            return 0xFFC8A24A;
        }
        return 0xFF7FC8C2;
    }

    private static void drawGhostDangerVolumeFog(DrawContext context, MinecraftClient client, Vec3d eye, BlockPos center, double yaw, double pitch, int cx, int cy, int scale, int radius, int floorY) {
        int scanRadius = Math.min(radius, 5);
        int drawn = 0;
        for (int x = center.getX() - scanRadius; x <= center.getX() + scanRadius && drawn < 36; x++) {
            for (int z = center.getZ() - scanRadius; z <= center.getZ() + scanRadius && drawn < 36; z++) {
                for (int y = floorY - 2; y <= floorY + 7 && drawn < 36; y += 2) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = client.world.getBlockState(pos);
                    int danger = ghostDangerColor(state);
                    if (danger != 0) {
                        drawGhostProjectedCell(context, eye, x, y + 0.08D, z, yaw, pitch, cx, cy, scale, withAlpha(danger, 34), 0.94D);
                        drawWorldLine(context, eye, x + 0.5D, y + 0.08D, z + 0.12D, x + 0.5D, y + 0.85D, z + 0.12D, yaw, pitch, cx, cy, withAlpha(danger, 104), scale);
                        drawn++;
                    }
                }
            }
        }
        List<Entity> hostile = new ArrayList<>(client.world.getEntitiesByClass(Entity.class, client.player.getBoundingBox().expand(scanRadius), entity -> entity instanceof MobEntity mob && (mob.getTarget() == client.player || mob.getType().getSpawnGroup() == SpawnGroup.MONSTER || ghostHasAny(ghostEntityKey(mob), "monster", "hostile", "boss", "raider", "illager", "demon", "undead", "beast"))));
        int limit = Math.min(8, hostile.size());
        for (int i = 0; i < limit; i++) {
            Entity entity = hostile.get(i);
            Vec3d pos = entity.getPos();
            drawGhostProjectedCell(context, eye, (int) Math.floor(pos.x), pos.y + 0.05D, (int) Math.floor(pos.z), yaw, pitch, cx, cy, scale, 0x34A7003A, 1.18D);
        }
    }

    private static int ghostDangerColor(BlockState state) {
        String key = ghostBlockKey(state);
        if (ghostIsHazardBlock(state, key)) {
            return 0xFFE06A21;
        }
        return 0;
    }

    private static void drawGhostChunkPressureField(DrawContext context, MinecraftClient client, Vec3d eye, BlockPos center, double yaw, double pitch, int cx, int cy, int scale, int radius, int floorY) {
        int[] entityQuadrants = new int[4];
        for (Entity entity : client.world.getEntitiesByClass(Entity.class, client.player.getBoundingBox().expand(radius + 6), entity -> entity != client.player && entity.isAlive())) {
            Vec3d pos = entity.getPos();
            int q = (pos.x >= client.player.getX() ? 1 : 0) + (pos.z >= client.player.getZ() ? 2 : 0);
            entityQuadrants[q]++;
        }
        int[] dataQuadrants = new int[4];
        int scanRadius = Math.min(radius, 5);
        for (int x = center.getX() - scanRadius; x <= center.getX() + scanRadius; x++) {
            for (int z = center.getZ() - scanRadius; z <= center.getZ() + scanRadius; z++) {
                for (int y = floorY - 1; y <= floorY + 7; y += 2) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (client.world.getBlockEntity(pos) != null) {
                        int q = (x >= center.getX() ? 1 : 0) + (z >= center.getZ() ? 2 : 0);
                        dataQuadrants[q]++;
                    }
                }
            }
        }
        drawGhostPressureSpoke(context, eye, center, yaw, pitch, cx, cy, scale, radius, floorY, -1, -1, entityQuadrants[0], dataQuadrants[0]);
        drawGhostPressureSpoke(context, eye, center, yaw, pitch, cx, cy, scale, radius, floorY, 1, -1, entityQuadrants[1], dataQuadrants[1]);
        drawGhostPressureSpoke(context, eye, center, yaw, pitch, cx, cy, scale, radius, floorY, -1, 1, entityQuadrants[2], dataQuadrants[2]);
        drawGhostPressureSpoke(context, eye, center, yaw, pitch, cx, cy, scale, radius, floorY, 1, 1, entityQuadrants[3], dataQuadrants[3]);
    }

    private static void drawGhostPressureSpoke(DrawContext context, Vec3d eye, BlockPos center, double yaw, double pitch, int cx, int cy, int scale, int radius, int floorY, int sx, int sz, int entityCount, int dataCount) {
        double pressure = clampRatio((entityCount / 8.0D) + (dataCount / 12.0D));
        if (pressure <= 0.0D) {
            return;
        }
        int color = dataCount > entityCount ? 0xFFB47CFF : 0xFFE06A21;
        double x0 = center.getX() + 0.5D;
        double z0 = center.getZ() + 0.5D;
        double x1 = x0 + sx * radius * (0.35D + 0.55D * pressure);
        double z1 = z0 + sz * radius * (0.35D + 0.55D * pressure);
        drawWorldLine(context, eye, x0, floorY + 0.18D, z0, x1, floorY + 0.18D, z1, yaw, pitch, cx, cy, withAlpha(color, 90 + (int) Math.round(95.0D * pressure)), scale + 2);
        drawGhostProjectedCell(context, eye, (int) Math.floor(x1), floorY + 0.16D, (int) Math.floor(z1), yaw, pitch, cx, cy, scale, withAlpha(color, 24 + (int) Math.round(44.0D * pressure)), 1.4D);
    }

    private static void drawGhostDepthSliceModeBand(DrawContext context, TextRenderer text, MinecraftClient client, Vec3d eye, BlockPos center, double yaw, double pitch, int cx, int cy, int scale, int radius, int floorY, F3Mode mode) {
    }

    private static int ghostDepthSliceIndex(MinecraftClient client, F3Mode mode) {
        if (mode == F3Mode.PLAYER) {
            return 1;
        }
        if (mode == F3Mode.TARGET || mode == F3Mode.CREATOR || mode == F3Mode.INSPECTOR) {
            return 2;
        }
        if (client != null && client.player != null && mode == F3Mode.GRAPHS) {
            return (client.player.age / 120) % 3;
        }
        return 0;
    }

    private static void drawGhostProjectedCell(DrawContext context, Vec3d eye, double x, double y, double z, double yaw, double pitch, int cx, int cy, int scale, int color, double size) {
        double pad = (1.0D - clampDouble(size, 0.15D, 1.6D)) / 2.0D;
        double x0 = x + pad;
        double z0 = z + pad;
        double x1 = x + 1.0D - pad;
        double z1 = z + 1.0D - pad;
        ProjectedPoint a = projectWorldPoint(eye, x0, y, z0, yaw, pitch, scale);
        ProjectedPoint b = projectWorldPoint(eye, x1, y, z0, yaw, pitch, scale);
        ProjectedPoint c = projectWorldPoint(eye, x1, y, z1, yaw, pitch, scale);
        ProjectedPoint d = projectWorldPoint(eye, x0, y, z1, yaw, pitch, scale);
        if (Math.abs(a.x()) > scale + 24 && Math.abs(b.x()) > scale + 24 && Math.abs(c.x()) > scale + 24 && Math.abs(d.x()) > scale + 24) {
            return;
        }
        if (Math.abs(a.y()) > scale + 24 && Math.abs(b.y()) > scale + 24 && Math.abs(c.y()) > scale + 24 && Math.abs(d.y()) > scale + 24) {
            return;
        }
        int ax = cx + clamp(a.x(), -scale - 24, scale + 24);
        int ay = cy + clamp(a.y(), -scale - 24, scale + 24);
        int bx = cx + clamp(b.x(), -scale - 24, scale + 24);
        int by = cy + clamp(b.y(), -scale - 24, scale + 24);
        int cxp = cx + clamp(c.x(), -scale - 24, scale + 24);
        int cyp = cy + clamp(c.y(), -scale - 24, scale + 24);
        int dx = cx + clamp(d.x(), -scale - 24, scale + 24);
        int dy = cy + clamp(d.y(), -scale - 24, scale + 24);
        int lineColor = withAlpha(color, Math.min(82, Math.max(28, alpha(color))));
        drawLine(context, ax, ay, bx, by, lineColor);
        drawLine(context, bx, by, cxp, cyp, lineColor);
        drawLine(context, cxp, cyp, dx, dy, lineColor);
        drawLine(context, dx, dy, ax, ay, lineColor);
    }

    private static void drawGhostPlayerOriginMarker(DrawContext context, MinecraftClient client, Vec3d eye, double yaw, double pitch, int cx, int cy, int scale, int floorY) {
        Vec3d pos = client.player.getPos();
        double markerY = floorY + 0.12D;
        ProjectedPoint point = projectWorldPoint(eye, pos.x, markerY, pos.z, yaw, pitch, scale + 6);
        int px = cx + clamp(point.x(), -scale - 18, scale + 18);
        int py = cy + clamp(point.y(), -scale - 18, scale + 18);
        context.fill(px - 2, py - 2, px + 3, py + 3, 0xFFE6EDF5);
        double radians = Math.toRadians(client.player.getYaw());
        double fx = -Math.sin(radians);
        double fz = Math.cos(radians);
        drawWorldLine(context, eye, pos.x, markerY, pos.z, pos.x + fx * 1.35D, markerY, pos.z + fz * 1.35D, yaw, pitch, cx, cy, 0xFFE6EDF5, scale + 6);
    }

    private static void drawGhostEyeRayFloorProjection(DrawContext context, Vec3d eye, Vec3d ray, double yaw, double pitch, int cx, int cy, int scale, int floorY) {
        Vec3d flat = new Vec3d(ray.x, 0.0D, ray.z);
        double len = Math.sqrt(flat.x * flat.x + flat.z * flat.z);
        if (len < 0.001D) {
            return;
        }
        flat = flat.multiply(1.0D / len);
        double y = floorY + 0.16D;
        drawWorldLine(context, eye, eye.x, y, eye.z, eye.x + flat.x * 8.0D, y, eye.z + flat.z * 8.0D, yaw, pitch, cx, cy, 0x88E6EDF5, scale + 5);
    }

    private static void drawGhostFloorDistanceTicks(DrawContext context, Vec3d eye, Vec3d ray, double yaw, double pitch, int cx, int cy, int scale, int floorY) {
        Vec3d flat = new Vec3d(ray.x, 0.0D, ray.z);
        double len = Math.sqrt(flat.x * flat.x + flat.z * flat.z);
        if (len < 0.001D) {
            return;
        }
        flat = flat.multiply(1.0D / len);
        double y = floorY + 0.19D;
        for (int i = 1; i <= 8; i++) {
            Vec3d p = new Vec3d(eye.x + flat.x * i, y, eye.z + flat.z * i);
            ProjectedPoint pp = projectWorldPoint(eye, p.x, p.y, p.z, yaw, pitch, scale + 5);
            if (Math.abs(pp.x()) <= scale + 18 && Math.abs(pp.y()) <= scale + 18) {
                int color = i % 4 == 0 ? 0xA0E6EDF5 : i % 2 == 0 ? 0x708FC5FF : 0x608FA0B8;
                context.fill(cx + pp.x() - 1, cy + pp.y() - 1, cx + pp.x() + 2, cy + pp.y() + 2, color);
            }
        }
    }

    private static void drawGhostLocalChunkReadout(DrawContext context, TextRenderer text, MinecraftClient client, F3Snapshot snapshot, int cx, int cy, int scale, int floorY) {
        int lx = Math.floorMod(client.player.getBlockX(), 16);
        int lz = Math.floorMod(client.player.getBlockZ(), 16);
        String label = "local " + lx + ":" + lz + " y-5 " + floorY;
        int x = cx - scale - 24;
        int y = cy + scale + 14;
        context.fill(x - 3, y - 2, x + Math.min(scale * 2, text.getWidth(label) + 6), y + 10, 0x44050609);
        drawText(context, text, trimToWidth(text, label, scale * 2 - 4), x, y, 0x998FA0B8);
    }

    private static boolean ghostMiniWorldEnabled() {
        boolean enabled = ghostMiniWorldConfigValue(Path.of("./koil/sys/config.json"), true);
        enabled = ghostMiniWorldConfigValue(Path.of("./config.json"), enabled);
        return enabled;
    }

    private static boolean ghostMiniWorldConfigValue(Path path, boolean fallback) {
        try {
            if (!Files.exists(path)) {
                return fallback;
            }
            String json = Files.readString(path, StandardCharsets.UTF_8);
            String key = "\"renderF3MiniDevWorld\"";
            int keyIndex = json.indexOf(key);
            if (keyIndex < 0) {
                return fallback;
            }
            int colon = json.indexOf(':', keyIndex + key.length());
            if (colon < 0) {
                return fallback;
            }
            int comma = json.indexOf(',', colon + 1);
            int brace = json.indexOf('}', colon + 1);
            int end = comma < 0 ? brace : brace < 0 ? comma : Math.min(comma, brace);
            if (end < 0) {
                end = json.length();
            }
            String value = json.substring(colon + 1, end).trim().toLowerCase(Locale.ROOT);
            if (value.startsWith("false")) {
                return false;
            }
            if (value.startsWith("true")) {
                return true;
            }
        } catch (Exception ignored) {
            return fallback;
        }
        return fallback;
    }

    private static int alpha(int color) {
        return (color >>> 24) & 255;
    }

    private static int blendColor(int a, int b) {
        int ar = (a >> 16) & 255;
        int ag = (a >> 8) & 255;
        int ab = a & 255;
        int br = (b >> 16) & 255;
        int bg = (b >> 8) & 255;
        int bb = b & 255;
        return 0xFF000000 | (((ar + br) / 2) << 16) | (((ag + bg) / 2) << 8) | ((ab + bb) / 2);
    }

    private static void drawGhostGridPlane(DrawContext context, Vec3d eye, BlockPos anchor, double yaw, double pitch, int cx, int cy, int scale, F3Mode mode) {
        int span = mode == F3Mode.NORMAL ? 2 : 3;
        double y = anchor.getY();
        for (int gx = anchor.getX() - span; gx <= anchor.getX() + span + 1; gx++) {
            int color = Math.floorMod(gx, 16) == 0 ? 0x70E3B735 : 0x248FC5FF;
            drawWorldLine(context, eye, gx, y, anchor.getZ() - span, gx, y, anchor.getZ() + span + 1, yaw, pitch, cx, cy, color, scale);
        }
        for (int gz = anchor.getZ() - span; gz <= anchor.getZ() + span + 1; gz++) {
            int color = Math.floorMod(gz, 16) == 0 ? 0x70E3B735 : 0x247FC8C2;
            drawWorldLine(context, eye, anchor.getX() - span, y, gz, anchor.getX() + span + 1, y, gz, yaw, pitch, cx, cy, color, scale);
        }
        if (mode != F3Mode.NORMAL) {
            double top = y + 1.0D;
            for (int gx = anchor.getX() - span; gx <= anchor.getX() + span + 1; gx++) {
                drawWorldLine(context, eye, gx, top, anchor.getZ() - span, gx, top, anchor.getZ() + span + 1, yaw, pitch, cx, cy, 0x138FC5FF, scale);
            }
            for (int gz = anchor.getZ() - span; gz <= anchor.getZ() + span + 1; gz++) {
                drawWorldLine(context, eye, anchor.getX() - span, top, gz, anchor.getX() + span + 1, top, gz, yaw, pitch, cx, cy, 0x137FC8C2, scale);
            }
        }
    }

    private static void drawGhostChunkBorders(DrawContext context, Vec3d eye, BlockPos anchor, double yaw, double pitch, int cx, int cy, int scale, F3Mode mode) {
        int minChunkX = Math.floorDiv(anchor.getX() - 4, 16) * 16;
        int maxChunkX = Math.floorDiv(anchor.getX() + 4, 16) * 16 + 16;
        int minChunkZ = Math.floorDiv(anchor.getZ() - 4, 16) * 16;
        int maxChunkZ = Math.floorDiv(anchor.getZ() + 4, 16) * 16 + 16;
        double y = anchor.getY();
        for (int gx = minChunkX; gx <= maxChunkX; gx += 16) {
            if (Math.abs(gx - anchor.getX()) <= 4) {
                drawWorldLine(context, eye, gx, y, anchor.getZ() - 4, gx, y, anchor.getZ() + 4, yaw, pitch, cx, cy, 0x99E3B735, scale);
            }
        }
        for (int gz = minChunkZ; gz <= maxChunkZ; gz += 16) {
            if (Math.abs(gz - anchor.getZ()) <= 4) {
                drawWorldLine(context, eye, anchor.getX() - 4, y, gz, anchor.getX() + 4, y, gz, yaw, pitch, cx, cy, 0x99E3B735, scale);
            }
        }
        if (mode != F3Mode.NORMAL) {
            ProjectedPoint chunkCorner = projectWorldPoint(eye, Math.floorDiv(anchor.getX(), 16) * 16, y, Math.floorDiv(anchor.getZ(), 16) * 16, yaw, pitch, scale);
            int px = cx + clamp(chunkCorner.x(), -scale - 16, scale + 16);
            int py = cy + clamp(chunkCorner.y(), -scale - 16, scale + 16);
            context.fill(px - 2, py - 2, px + 3, py + 3, 0x998FA0B8);
        }
    }

    private static void drawGhostEyeRay(DrawContext context, Vec3d eye, Vec3d ray, double yaw, double pitch, int cx, int cy, int scale) {
        drawWorldLine(context, eye, eye.x, eye.y, eye.z, eye.x + ray.x * 7.0D, eye.y + ray.y * 7.0D, eye.z + ray.z * 7.0D, yaw, pitch, cx, cy, 0x86E6EDF5, scale + 4);
    }

    private static void drawGhostDistanceTicks(DrawContext context, Vec3d eye, Vec3d ray, double yaw, double pitch, int cx, int cy, int scale) {
        for (int i = 1; i <= 7; i++) {
            Vec3d point = eye.add(ray.multiply(i));
            ProjectedPoint pp = projectWorldPoint(eye, point.x, point.y, point.z, yaw, pitch, scale + 4);
            if (Math.abs(pp.x()) <= scale + 18 && Math.abs(pp.y()) <= scale + 18) {
                int color = i % 2 == 0 ? 0xA8E6EDF5 : 0x778FA0B8;
                context.fill(cx + pp.x() - 1, cy + pp.y() - 1, cx + pp.x() + 2, cy + pp.y() + 2, color);
            }
        }
    }

    private static void drawGhostBlockBoundary(DrawContext context, Vec3d eye, BlockPos pos, double yaw, double pitch, int cx, int cy, int scale, int color) {
        double x0 = pos.getX();
        double y0 = pos.getY();
        double z0 = pos.getZ();
        double x1 = x0 + 1.0D;
        double y1 = y0 + 1.0D;
        double z1 = z0 + 1.0D;
        drawWorldLine(context, eye, x0, y0, z0, x1, y0, z0, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, x1, y0, z0, x1, y0, z1, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, x1, y0, z1, x0, y0, z1, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, x0, y0, z1, x0, y0, z0, yaw, pitch, cx, cy, color, scale);
        drawWorldLine(context, eye, x0, y1, z0, x1, y1, z0, yaw, pitch, cx, cy, withAlpha(color, 150), scale);
        drawWorldLine(context, eye, x1, y1, z0, x1, y1, z1, yaw, pitch, cx, cy, withAlpha(color, 150), scale);
        drawWorldLine(context, eye, x1, y1, z1, x0, y1, z1, yaw, pitch, cx, cy, withAlpha(color, 150), scale);
        drawWorldLine(context, eye, x0, y1, z1, x0, y1, z0, yaw, pitch, cx, cy, withAlpha(color, 150), scale);
        drawWorldLine(context, eye, x0, y0, z0, x0, y1, z0, yaw, pitch, cx, cy, withAlpha(color, 135), scale);
        drawWorldLine(context, eye, x1, y0, z0, x1, y1, z0, yaw, pitch, cx, cy, withAlpha(color, 135), scale);
        drawWorldLine(context, eye, x1, y0, z1, x1, y1, z1, yaw, pitch, cx, cy, withAlpha(color, 135), scale);
        drawWorldLine(context, eye, x0, y0, z1, x0, y1, z1, yaw, pitch, cx, cy, withAlpha(color, 135), scale);
    }

    private static void drawGhostTargetFace(DrawContext context, Vec3d eye, BlockHitResult blockHit, double yaw, double pitch, int cx, int cy, int scale) {
        drawTargetBlockFaceGrid(context, eye, blockHit, yaw, pitch, cx, cy, scale + 6);
    }

    private static void drawGhostFaceNormal(DrawContext context, Vec3d eye, BlockHitResult blockHit, double yaw, double pitch, int cx, int cy, int scale) {
        Direction side = blockHit.getSide();
        Vec3d center = blockHit.getPos();
        Vec3d normal = new Vec3d(side.getOffsetX(), side.getOffsetY(), side.getOffsetZ());
        drawWorldLine(context, eye, center.x, center.y, center.z, center.x + normal.x * 0.9D, center.y + normal.y * 0.9D, center.z + normal.z * 0.9D, yaw, pitch, cx, cy, 0xFF8FC5FF, scale + 8);
        ProjectedPoint end = projectWorldPoint(eye, center.x + normal.x * 0.9D, center.y + normal.y * 0.9D, center.z + normal.z * 0.9D, yaw, pitch, scale + 8);
        if (Math.abs(end.x()) <= scale + 20 && Math.abs(end.y()) <= scale + 20) {
            context.fill(cx + end.x() - 2, cy + end.y() - 2, cx + end.x() + 3, cy + end.y() + 3, 0xFF8FC5FF);
        }
    }

    private static void drawGhostWorldEvents(DrawContext context, MinecraftClient client, Vec3d eye, double yaw, double pitch, int cx, int cy, int scale, F3Mode mode) {
        int range = mode == F3Mode.NORMAL ? 7 : 10;
        List<Entity> entities = new ArrayList<>(client.world.getEntitiesByClass(Entity.class, client.player.getBoundingBox().expand(range), entity -> entity != client.player && entity.isAlive()));
        entities.sort(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(client.player)));
        int limit = mode == F3Mode.NORMAL ? 3 : 6;
        int shown = 0;
        for (Entity entity : entities) {
            if (shown >= limit) {
                break;
            }
            Vec3d pos = entity.getPos();
            ProjectedPoint pp = projectWorldPoint(eye, pos.x, pos.y + entity.getHeight() * 0.5D, pos.z, yaw, pitch, scale);
            if (Math.abs(pp.x()) > scale + 20 || Math.abs(pp.y()) > scale + 20) {
                continue;
            }
            double distance = Math.sqrt(entity.squaredDistanceTo(client.player));
            int color = crosshairEntityColor(entity, distance, range, client.targetedEntity == entity);
            int px = cx + pp.x();
            int py = cy + pp.y();
            if (entity instanceof ItemEntity) {
                context.fill(px - 1, py, px + 2, py + 3, withAlpha(color, 150));
                context.fill(px, py - 1, px + 3, py + 2, withAlpha(color, 150));
            } else {
                context.fill(px - 2, py - 2, px + 3, py + 3, withAlpha(color, 135));
                context.fill(px - 1, py + 3, px + 2, py + 4, withAlpha(color, 185));
            }
            shown++;
        }
    }

    private static void renderDebugCrosshairTargetPreview(DrawContext context, TextRenderer text, MinecraftClient client, F3TargetSnapshot target, int cx, int cy, F3Mode mode) {
        if (target == null || client == null || client.player == null || mode == F3Mode.SIMPLE) {
            return;
        }
        boolean compact = mode == F3Mode.NORMAL || mode == F3Mode.WORLD;
        int px = cx + (compact ? 32 : 42);
        int py = cy + (compact ? 24 : 32);
        int color = target.accentColor();
        drawTargetTypeGlyph(context, px - 6, py - 6, target);
        context.fill(px - 9, py + 8, px + 10, py + 10, withAlpha(color, compact ? 110 : 160));
        if (!compact) {
            String label = trimToWidth(text, target.type().label(), 40);
            drawText(context, text, label, px - Math.min(20, text.getWidth(label) / 2), py + 13, softText(color));
        }
    }

    private static void renderDebugCrosshairWorldScope(DrawContext context, TextRenderer text, MinecraftClient client, int cx, int cy, F3Mode mode) {
        if (client == null || client.player == null || client.world == null || mode == F3Mode.SIMPLE || mode == F3Mode.PERFORMANCE) {
            return;
        }
        int radius = mode == F3Mode.NORMAL ? 31 : mode == F3Mode.GRAPHS ? 54 : 44;
        int range = mode == F3Mode.NORMAL ? 10 : mode == F3Mode.GRAPHS ? 18 : 14;
        drawCrosshairScopeRings(context, text, cx, cy, radius, range, mode != F3Mode.NORMAL);
        List<Entity> entities = new ArrayList<>(client.world.getEntitiesByClass(Entity.class, client.player.getBoundingBox().expand(range), entity -> entity != client.player && entity.isAlive()));
        entities.sort(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(client.player)));
        int shown = 0;
        int limit = mode == F3Mode.NORMAL ? 4 : mode == F3Mode.GRAPHS ? 10 : 7;
        Vec3d playerPos = client.player.getPos();
        for (Entity entity : entities) {
            if (shown >= limit) {
                break;
            }
            Vec3d delta = entity.getPos().subtract(playerPos);
            if (Math.abs(delta.x) > range || Math.abs(delta.y) > range || Math.abs(delta.z) > range) {
                continue;
            }
            ProjectedPoint point = projectWorldOffset(delta.x, delta.y, delta.z, client.player.getYaw(), client.player.getPitch(), radius, range);
            int bx = cx + clamp(point.x(), -radius, radius);
            int by = cy + clamp(point.y(), -radius, radius);
            double distance = Math.sqrt(entity.squaredDistanceTo(client.player));
            boolean targeted = client.targetedEntity == entity;
            int color = crosshairEntityColor(entity, distance, range, targeted);
            drawCrosshairEntityBlip(context, bx, by, entity, distance, range, point.depth(), color, targeted);
            shown++;
        }
    }

    private static void drawCrosshairScopeRings(DrawContext context, TextRenderer text, int cx, int cy, int radius, int range, boolean labels) {
        int half = Math.max(22, radius);
        context.fill(cx - half, cy, cx + half + 1, cy + 1, 0x228FA0B8);
        context.fill(cx, cy - half, cx + 1, cy + half + 1, 0x228FA0B8);
        if (labels) {
            drawText(context, text, range + "m", cx + half - text.getWidth(range + "m"), cy + half + 2, 0x668FA0B8);
        }
    }

    private static void drawCrosshairEntityBlip(DrawContext context, int x, int y, Entity entity, double distance, int range, double depth, int color, boolean targeted) {
        int size = crosshairEntitySize(entity, distance, range);
        int alpha = targeted ? 235 : clamp(95 + (int) Math.round((1.0D - clampRatio(distance / Math.max(1.0D, range))) * 105.0D), 90, 210);
        int shade = depth > 0.0D ? withAlpha(color, alpha) : withAlpha(softText(color), Math.max(75, alpha - 40));
        context.fill(x - size - 1, y - size - 1, x + size + 2, y + size + 2, withAlpha(0xFF050609, targeted ? 180 : 95));
        context.fill(x - size, y - size, x + size + 1, y + size + 1, shade);
        context.fill(x - size, y - size, x + size + 1, y - size + 1, withAlpha(softText(color), alpha));
        context.fill(x - size, y + size, x + size + 1, y + size + 1, withAlpha(color, Math.min(255, alpha + 20)));
        if (entity instanceof LivingEntity living) {
            double health = living.getMaxHealth() <= 0.0F ? 0.0D : clampRatio(living.getHealth() / living.getMaxHealth());
            int bar = clamp((int) Math.round((size * 2 + 1) * health), 0, size * 2 + 1);
            context.fill(x - size, y + size + 2, x + size + 1, y + size + 3, 0x66050609);
            context.fill(x - size, y + size + 2, x - size + bar, y + size + 3, health > 0.5D ? 0xFF2DA700 : health > 0.25D ? 0xFFE3B735 : 0xFFE06A21);
        }
        int stem = clamp((int) Math.round((1.0D - clampRatio(distance / Math.max(1.0D, range))) * 7.0D), 1, 7);
        drawLine(context, x, y, x, y - stem, withAlpha(color, 150));
        if (targeted) {
            drawCross(context, x, y, 0xFF8FC5FF);
            context.drawBorder(x - size - 3, y - size - 3, size * 2 + 7, size * 2 + 7, 0xFF8FC5FF);
        }
    }

    private static int crosshairEntityColor(Entity entity, double distance, int range, boolean targeted) {
        if (targeted) {
            return 0xFFE06A21;
        }
        String key = ghostEntityKey(entity);
        if (entity instanceof PlayerEntity) {
            return 0xFF55C4FF;
        }
        if (entity instanceof ItemEntity item) {
            return ghostItemSignalColor(item);
        }
        if (entity instanceof MobEntity mob) {
            SpawnGroup group = mob.getType().getSpawnGroup();
            boolean hostile = mob.getTarget() != null || group == SpawnGroup.MONSTER || ghostHasAny(key, "monster", "hostile", "boss", "raider", "illager", "demon", "undead", "beast");
            if (hostile) {
                return distance < range * 0.35D ? 0xFFA7003A : 0xFFE06A21;
            }
            if (ghostHasAny(key, "fish", "squid", "dolphin", "aquatic", "water", "jelly")) {
                return 0xFF0085A4;
            }
            return 0xFF2DA700;
        }
        if (ghostHasAny(key, "arrow", "projectile", "bolt", "trident", "fireball", "snowball", "egg")) {
            return 0xFFE3B735;
        }
        if (ghostHasAny(key, "boat", "minecart", "vehicle", "cart")) {
            return 0xFFC8A24A;
        }
        if (entity instanceof LivingEntity living) {
            double health = living.getMaxHealth() <= 0.0F ? 0.0D : clampRatio(living.getHealth() / living.getMaxHealth());
            if (health < 0.25D) {
                return 0xFFA7003A;
            }
            if (health < 0.55D) {
                return 0xFFE3B735;
            }
            return 0xFF2DA700;
        }
        return distance < range * 0.35D ? 0xFFB47CFF : 0xFF8FC5FF;
    }

    private static int crosshairEntitySize(Entity entity, double distance, int range) {
        double distanceRatio = 1.0D - clampRatio(distance / Math.max(1.0D, range));
        double body = clampDouble((entity.getWidth() + entity.getHeight()) / 3.0D, 0.2D, 1.4D);
        return clamp(2 + (int) Math.round(distanceRatio * 3.0D + body), 2, 6);
    }

    private static ProjectedPoint projectCameraWorldVector(double x, double y, double z, double yawDegrees, double pitchDegrees, int scale) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(clampDouble(pitchDegrees, -89.5D, 89.5D));
        double sinYaw = Math.sin(yaw);
        double cosYaw = Math.cos(yaw);
        double sinPitch = Math.sin(pitch);
        double cosPitch = Math.cos(pitch);
        double rightX = -cosYaw;
        double rightY = 0.0D;
        double rightZ = -sinYaw;
        double upX = -sinYaw * sinPitch;
        double upY = cosPitch;
        double upZ = cosYaw * sinPitch;
        double forwardX = -sinYaw * cosPitch;
        double forwardY = -sinPitch;
        double forwardZ = cosYaw * cosPitch;
        double screenX = x * rightX + y * rightY + z * rightZ;
        double screenY = -(x * upX + y * upY + z * upZ);
        double depth = x * forwardX + y * forwardY + z * forwardZ;
        int sx = (int) Math.round(screenX * scale);
        int sy = (int) Math.round(screenY * scale);
        return new ProjectedPoint(sx, sy, depth);
    }

    private static ProjectedPoint projectPlayerRadarOffset(double dx, double dy, double dz, double yawDegrees, double pitchDegrees, int scale, double range) {
        double safeRange = Math.max(0.1D, range);
        ProjectedPoint point = projectCameraWorldVector(dx / safeRange, dy / safeRange, dz / safeRange, yawDegrees, pitchDegrees, scale);
        return new ProjectedPoint(clamp(point.x(), -scale, scale), clamp(point.y(), -scale, scale), point.depth());
    }

    private static ProjectedPoint projectWorldOffset(double dx, double dy, double dz, double yawDegrees, double pitchDegrees, int scale, double range) {
        double safeRange = Math.max(0.1D, range);
        ProjectedPoint point = projectCameraWorldVector(dx / safeRange, dy / safeRange, dz / safeRange, yawDegrees, pitchDegrees, scale);
        return new ProjectedPoint(clamp(point.x(), -scale, scale), clamp(point.y(), -scale, scale), point.depth());
    }

    private static AxisProjection projectAxis(double x, double y, double z, double yawDegrees, double pitchDegrees, int scale) {
        ProjectedPoint point = projectCameraWorldVector(x, y, z, yawDegrees, pitchDegrees, scale);
        return new AxisProjection(point.x(), point.y(), point.depth());
    }

    private static void drawAxis(DrawContext context, TextRenderer text, int cx, int cy, AxisProjection axis, String label, int color, boolean negative) {
        int alpha = negative ? 95 : axis.depth() > 0.55D ? 125 : 235;
        int axisColor = withAlpha(color, alpha);
        int x2 = cx + axis.x();
        int y2 = cy + axis.y();
        drawLine(context, cx, cy, x2, y2, axisColor);
        if (!negative) {
            drawArrow(context, cx, cy, x2, y2, color);
            drawText(context, text, label, x2 + (axis.x() >= 0 ? 3 : -text.getWidth(label) - 3), y2 + (axis.y() >= 0 ? 2 : -8), color);
        } else {
            context.fill(x2 - 1, y2 - 1, x2 + 2, y2 + 2, axisColor);
        }
    }





    private static void renderCompact(DrawContext context, TextRenderer text, F3Snapshot snapshot, int width, int height) {
        PerformanceSnapshot perf = snapshot.performance();
        int panelW = 214;
        int panelH = 48;
        int x = 8;
        int y = 8;
        panel(context, x, y, panelW, panelH, 0xFF7FC8C2);
        drawText(context, text, "Debug Compact", x + 8, y + 7, 0xFFE6EDF5);
        drawText(context, text, perf.fps() + " fps  " + perf.frameTimeMs() + " ms  " + perf.usedMemoryMb() + "/" + perf.maxMemoryMb() + " MB", x + 8, y + 21, fpsColor(perf.fps()));
        drawText(context, text, trimToWidth(text, snapshot.target().title(), panelW - 16), x + 8, y + 34, snapshot.target().accentColor());
    }

    private static PanelBounds renderLeftCore(DrawContext context, TextRenderer text, F3Snapshot snapshot, int width, int height, F3Mode mode, boolean reserveTarget) {
        List<F3DataLine> visible = new ArrayList<>(sectionLines(snapshot, "vanilla_left"));
        if (visible.isEmpty() || isCaptureUnavailable(visible)) {
            visible.clear();
            List<F3DataLine> lines = sectionLines(snapshot, "player");
            List<F3DataLine> world = sectionLines(snapshot, "world");
            List<F3DataLine> chunk = sectionLines(snapshot, "chunk");
            addMatching(visible, lines, "XYZ", "Block", "Chunk", "Facing", "Velocity", "Health", "Hunger", "Selected");
            addMatching(visible, world, "Dimension", "Biome", "Weather", "Block Light", "Sky Light", "Time");
            if (mode != F3Mode.SIMPLE) {
                addMatching(visible, chunk, "Region File", "Block Entities", "Chunk Entities", "Client Entities");
            }
        }
        int baseMax = switch (mode) {
            case SIMPLE -> 12;
            case NORMAL, WORLD, INSPECTOR, PERFORMANCE -> 20;
            default -> 31;
        };
        int reservedTargetHeight = reserveTarget ? leftLowerReserveHeight(height, mode) : 0;
        int maxPanelHeight = Math.max(78, height - 16 - reservedTargetHeight - (reserveTarget ? 6 : 0));
        int miniH = leftVisualStripHeight(mode);
        int maxByHeight = Math.max(5, (maxPanelHeight - 27 - miniH) / 10);
        int max = Math.max(3, Math.min(baseMax, maxByHeight));
        List<F3DataLine> rows = visiblePanelLines(visible, max, F3LayoutState.ScrollPanel.LEFT);
        int maxPanelW = Math.max(168, Math.min(mode.developerDataEnabled() || mode == F3Mode.FULL ? 390 : 354, width / 2 - 18));
        int panelW = panelWidth(text, rows.isEmpty() ? visible : rows, List.of("F3 Left", scrollStatusLeft(visible, max)), 150, maxPanelW, true);
        int panelH = 27 + miniH + rows.size() * 10;
        int x = 8;
        int y = 8;
        F3LayoutState.registerPanel(F3LayoutState.ScrollPanel.LEFT, x, y, panelW, panelH, visible.size(), max);
        panel(context, x, y, panelW, panelH, 0xFF7FC8C2);
        drawText(context, text, "F3 Left", x + 8, y + 8, 0xFFE6EDF5);
        drawText(context, text, scrollStatusLeft(visible, max), x + 76, y + 8, 0xFF8FA0B8);
        int rowY = y + 23;
        if (miniH > 0) {
            drawLeftVisualStrip(context, text, snapshot, x + 8, rowY, panelW - 16, miniH, mode);
            rowY += miniH;
        }
        for (F3DataLine line : rows) {
            rowCompact(context, text, x + 8, rowY, panelW - 16, line);
            rowY += 10;
        }
        return new PanelBounds(x, y, panelW, panelH);
    }

    private static void renderRightPerformance(DrawContext context, TextRenderer text, F3Snapshot snapshot, int width, int height, F3Mode mode) {
        if (mode != F3Mode.PERFORMANCE) {
            renderVanillaRight(context, text, snapshot, width, height, mode);
            return;
        }
        PerformanceSnapshot perf = snapshot.performance();
        int panelW = 286;
        int panelH = 174;
        int x = width - panelW - 8;
        int y = 8;
        panel(context, x, y, panelW, panelH, perf.primaryBottleneck().color());
        drawText(context, text, "Performance", x + 8, y + 8, 0xFFE6EDF5);
        drawText(context, text, perf.primaryBottleneck().label(), x + 92, y + 8, perf.primaryBottleneck().color());
        drawMetric(context, text, x + 8, y + 24, "FPS", String.valueOf(perf.fps()), fpsColor(perf.fps()));
        drawMetric(context, text, x + 80, y + 24, "1% Low", String.valueOf(perf.onePercentLowFps()), fpsColor((int) perf.onePercentLowFps()));
        drawMetric(context, text, x + 164, y + 24, "Frame", perf.frameTimeMs() + "ms", perf.maxFrameTimeMs() > 75 ? 0xFFE06A21 : 0xFF2DA700);
        drawSpark(context, x + 8, y + 50, panelW - 16, 28, Chart.FPS);
        drawSpark(context, x + 8, y + 84, panelW - 16, 28, Chart.FRAME);
        drawSpark(context, x + 8, y + 118, (panelW - 22) / 2, 28, Chart.MEMORY);
        drawSpark(context, x + 14 + (panelW - 22) / 2, y + 118, (panelW - 22) / 2, 28, Chart.WORLD);
        drawText(context, text, trim(perf.likelyCause(), (panelW - 16) / 6), x + 8, y + 154, 0xFFB8C4D2);
    }

    private static void renderVanillaRight(DrawContext context, TextRenderer text, F3Snapshot snapshot, int width, int height, F3Mode mode) {
        List<F3DataLine> visible = new ArrayList<>(sectionLines(snapshot, "vanilla_right"));
        if (visible.isEmpty() || isCaptureUnavailable(visible)) {
            visible.clear();
            addMatching(visible, sectionLines(snapshot, "system"), "Java", "JVM", "OS", "CPU Threads", "Heap Used", "Heap Max");
            addMatching(visible, sectionLines(snapshot, "render"), "Window", "Graphics", "Clouds", "Particles", "Mipmap", "Shader Mod");
            addMatching(visible, sectionLines(snapshot, "network"), "Connection", "Ping", "Players Listed");
        }
        List<F3DataLine> rightRows = compactRightRows(snapshot, visible, mode);
        int miniH = rightSummaryMeterHeight(mode);
        int baseMax = switch (mode) {
            case SIMPLE -> 10;
            case NORMAL, WORLD, INSPECTOR -> 18;
            default -> 42;
        };
        int maxPanelHeight = Math.max(78, height - 16);
        int maxByHeight = Math.max(5, (maxPanelHeight - 27 - miniH) / 10);
        int max = Math.max(3, Math.min(baseMax, maxByHeight));
        int rightEdge = rightColumnEdge(width, mode);
        int maxPanelW = rightPanelMaxWidth(width, mode);
        int prePanelW = panelWidth(text, rightRows, List.of("F3 Right", scrollStatusRight(rightRows, max)), 176, maxPanelW, true);
        List<F3DataLine> wrappedRightRows = developerDataMode(mode) ? wrapRowsToWidth(text, rightRows, prePanelW - 16, true) : rightRows;
        List<F3DataLine> rows = visiblePanelLines(wrappedRightRows, max, F3LayoutState.ScrollPanel.RIGHT);
        int panelW = panelWidth(text, rows.isEmpty() ? wrappedRightRows : rows, List.of("F3 Right", scrollStatusRight(wrappedRightRows, max)), 176, maxPanelW, true);
        int panelH = 27 + miniH + rows.size() * 10;
        int x = rightEdge - panelW;
        int y = 8;
        F3LayoutState.registerPanel(F3LayoutState.ScrollPanel.RIGHT, x, y, panelW, panelH, wrappedRightRows.size(), max);
        panel(context, x, y, panelW, panelH, 0xFF8FC5FF);
        drawText(context, text, "F3 Right", x + 8, y + 8, 0xFFE6EDF5);
        drawText(context, text, scrollStatusRight(wrappedRightRows, max), x + 80, y + 8, 0xFF8FA0B8);
        int rowY = y + 23;
        if (miniH > 0) {
            drawRightSummaryMeters(context, text, x + 8, rowY, panelW - 16, snapshot, mode);
            rowY += miniH;
        }
        for (F3DataLine line : rows) {
            rowCompact(context, text, x + 8, rowY, panelW - 16, line);
            rowY += 10;
        }
    }


    private static void renderGraphRail(DrawContext context, TextRenderer text, F3Snapshot snapshot, int width, int height) {
        int railW = graphRailWidth(width);
        int x = width - railW - 8;
        int y = 8;
        int available = Math.max(120, height - 16);
        List<GraphCard> cards = graphCards(snapshot, available);
        int totalHeight = graphCardsHeight(cards);
        int visibleUnits = Math.max(1, available / 12);
        int totalUnits = Math.max(visibleUnits, (totalHeight + 11) / 12);
        F3LayoutState.registerPanel(F3LayoutState.ScrollPanel.GRAPH, x, y, railW, available, totalUnits, visibleUnits);
        int scrollPx = F3LayoutState.overlayGraphLineOffset() * 12;
        context.enableScissor(x, y, x + railW, y + available);
        int cursor = y - scrollPx;
        for (GraphCard card : cards) {
            if (cursor + card.height() >= y && cursor <= y + available) {
                int clipTop = Math.max(y, cursor);
                int clipBottom = Math.min(y + available, cursor + card.height());
                if (clipBottom > clipTop) {
                    context.enableScissor(x, clipTop, x + railW, clipBottom);
                    renderGraphCard(context, text, snapshot, x, cursor, railW, card);
                    context.disableScissor();
                }
            }
            cursor += card.height() + 4;
        }
        context.disableScissor();
        String status = graphScrollStatus(totalUnits, visibleUnits);
        int statusW = Math.min(railW - 10, text.getWidth(status) + 8);
        context.fill(x + railW - statusW - 4, y + 4, x + railW - 4, y + 16, 0xAA050609);
        drawText(context, text, status, x + railW - statusW, y + 6, 0xFF8FA0B8);
    }

    private static List<GraphCard> graphCards(F3Snapshot snapshot, int available) {
        List<GraphCard> cards = new ArrayList<>();
        F3Mode mode = snapshot.mode();
        int pieH = clamp(available / 4, 116, 174);
        int renderH = clamp(available / 7, 84, 104);
        if (mode == F3Mode.PLAYER) {
            cards.add(new GraphCard("player", 60));
            cards.add(new GraphCard("player_motion", 214));
            cards.add(new GraphCard("orientation", 158));
            cards.add(new GraphCard("coords", 76));
            cards.add(new GraphCard("frame_timeline", 62));
            cards.add(new GraphCard("network", 62));
            return cards;
        }
        if (mode == F3Mode.WORLD) {
            cards.add(new GraphCard("world", 54));
            cards.add(new GraphCard("rhythm", 72));
            cards.add(new GraphCard("light", 50));
            cards.add(new GraphCard("terrain", 66));
            cards.add(new GraphCard("chunk", 76));
            cards.add(new GraphCard("chunk_health", 108));
            cards.add(new GraphCard("chunk_sections", 94));
            cards.add(new GraphCard("coords", 76));
            return cards;
        }
        if (mode == F3Mode.TARGET || mode == F3Mode.CREATOR || mode == F3Mode.INSPECTOR) {
            appendTargetPresetCards(cards, snapshot.target(), mode);
            cards.add(new GraphCard("frame_timeline", 62));
            if (mode == F3Mode.CREATOR) {
                cards.add(new GraphCard("render", renderH));
                cards.add(new GraphCard("memory", 54));
            }
            return cards;
        }
        cards.add(new GraphCard("pie", pieH));
        cards.add(new GraphCard("frame_timeline", 62));
        cards.add(new GraphCard("render", renderH));
        cards.add(new GraphCard("frame", 58));
        cards.add(new GraphCard("fps", 54));
        cards.add(new GraphCard("memory", 54));
        cards.add(new GraphCard("world", 54));
        cards.add(new GraphCard("terrain", 66));
        cards.add(new GraphCard("light", 50));
        cards.add(new GraphCard("rhythm", 72));
        cards.add(new GraphCard("chunk", 76));
        cards.add(new GraphCard("chunk_health", 108));
        cards.add(new GraphCard("chunk_sections", 94));
        cards.add(new GraphCard("player", 60));
        cards.add(new GraphCard("player_motion", 204));
        cards.add(new GraphCard("network", 62));
        cards.add(new GraphCard("coords", 76));
        cards.add(new GraphCard("orientation", 154));
        appendTargetPresetCards(cards, snapshot.target(), mode);
        return cards;
    }

    private static void appendTargetPresetCards(List<GraphCard> cards, F3TargetSnapshot target, F3Mode mode) {
        F3TargetType type = target.type();
        if (type == F3TargetType.BLOCK || type == F3TargetType.CONTAINER || type == F3TargetType.FLUID) {
            cards.add(new GraphCard("block_shape", 104));
            cards.add(new GraphCard("block_face_state", 132));
            cards.add(new GraphCard("block_neighbors", 116));
            cards.add(new GraphCard("block_profile", 72));
            cards.add(new GraphCard("block_structure", 74));
            cards.add(new GraphCard("block_flags", 68));
            cards.add(new GraphCard("block_tags", mode == F3Mode.CREATOR || mode == F3Mode.INSPECTOR || mode == F3Mode.FULL ? 70 : 58));
            return;
        }
        if (type == F3TargetType.ENTITY || type == F3TargetType.PLAYER) {
            cards.add(new GraphCard("entity_model", 120));
            cards.add(new GraphCard("entity_anatomy", 116));
            cards.add(new GraphCard("entity_threat", 96));
            cards.add(new GraphCard("mob_vitals", 70));
            cards.add(new GraphCard("mob_motion", 68));
            cards.add(new GraphCard("mob_goal_stack", 86));
            cards.add(new GraphCard("mob_brain", 70));
            return;
        }
        if (type == F3TargetType.ITEM) {
            cards.add(new GraphCard("item_stack", 96));
        }
    }

    private static int graphCardsHeight(List<GraphCard> cards) {
        int total = 0;
        for (GraphCard card : cards) {
            total += card.height() + 4;
        }
        return Math.max(0, total - 4);
    }

    private static void renderGraphCard(DrawContext context, TextRenderer text, F3Snapshot snapshot, int x, int y, int w, GraphCard card) {
        switch (card.id()) {
            case "pie" -> graphPieCard(context, text, x, y, w, card.height(), snapshot);
            case "frame" -> graphSparkCard(context, text, x, y, w, card.height(), "Frame Flow", Chart.FRAME, snapshot.performance().frameTimeMs() + " ms", snapshot.performance().maxFrameTimeMs() > 75.0D ? 0xFFE06A21 : 0xFF7FC8C2);
            case "frame_timeline" -> frameTimelineCard(context, text, x, y, w, card.height());
            case "fps" -> graphSparkCard(context, text, x, y, w, card.height(), "FPS Flow", Chart.FPS, snapshot.performance().fps() + " fps", fpsColor(snapshot.performance().fps()));
            case "memory" -> graphSparkCard(context, text, x, y, w, card.height(), "Heap Flow", Chart.MEMORY, snapshot.performance().usedMemoryMb() + "/" + snapshot.performance().maxMemoryMb() + " MB", pressureColor(snapshot.performance().memoryPressure()));
            case "world" -> graphSparkCard(context, text, x, y, w, card.height(), "Client Load", Chart.WORLD, snapshot.performance().entityCount() + " ent", pressureColor(Math.max(snapshot.performance().chunkStress(), snapshot.performance().entityCount() / 300.0D)));
            case "terrain" -> terrainNoiseCard(context, text, x, y, w, card.height(), snapshot);
            case "light" -> lightGraphCard(context, text, x, y, w, card.height(), snapshot);
            case "rhythm" -> worldRhythmCard(context, text, x, y, w, card.height(), snapshot);
            case "chunk" -> chunkHeatCard(context, text, x, y, w, card.height(), snapshot);
            case "chunk_health" -> chunkHealthCard(context, text, x, y, w, card.height(), snapshot);
            case "chunk_sections" -> chunkSectionsCard(context, text, x, y, w, card.height(), snapshot);
            case "player" -> playerVitalsCard(context, text, x, y, w, card.height(), snapshot);
            case "player_motion" -> playerMotionCard(context, text, x, y, w, card.height(), snapshot);
            case "network" -> networkPulseCard(context, text, x, y, w, card.height(), snapshot);
            case "render" -> renderStackCard(context, text, x, y, w, card.height(), snapshot);
            case "coords" -> coordinateMapCard(context, text, x, y, w, card.height(), snapshot);
            case "orientation" -> orientationCard(context, text, x, y, w, card.height(), snapshot);
            case "block_profile" -> targetBlockProfileCard(context, text, x, y, w, card.height(), snapshot.target());
            case "block_structure" -> targetBlockStructureCard(context, text, x, y, w, card.height(), snapshot.target());
            case "block_shape" -> targetBlockShapeCard(context, text, x, y, w, card.height(), snapshot.target());
            case "block_face_state" -> targetBlockFaceStateCard(context, text, x, y, w, card.height(), snapshot.target());
            case "block_neighbors" -> targetBlockNeighborPanelCard(context, text, x, y, w, card.height(), snapshot.target());
            case "block_flags" -> targetBlockFlagsCard(context, text, x, y, w, card.height(), snapshot.target());
            case "block_tags" -> targetTagCloudCard(context, text, x, y, w, card.height(), snapshot.target());
            case "mob_vitals" -> targetMobVitalsCard(context, text, x, y, w, card.height(), snapshot.target());
            case "mob_motion" -> targetMobMotionCard(context, text, x, y, w, card.height(), snapshot.target());
            case "entity_model" -> targetEntityModelCard(context, text, x, y, w, card.height(), snapshot.target());
            case "entity_anatomy" -> targetEntityAnatomyCard(context, text, x, y, w, card.height(), snapshot.target());
            case "entity_threat" -> targetEntityThreatCard(context, text, x, y, w, card.height(), snapshot.target());
            case "mob_brain" -> targetMobBrainCard(context, text, x, y, w, card.height(), snapshot.target());
            case "mob_goal_stack" -> targetMobGoalStackCard(context, text, x, y, w, card.height(), snapshot.target());
            case "item_stack" -> targetItemStackCard(context, text, x, y, w, card.height(), snapshot.target());
            default -> graphSparkCard(context, text, x, y, w, card.height(), "Debug", Chart.FRAME, "unknown", 0xFF8D8D8D);
        }
    }

    private static String graphScrollStatus(int totalUnits, int visibleUnits) {
        if (totalUnits <= visibleUnits) {
            return totalUnits + " units";
        }
        int offset = Math.min(F3LayoutState.overlayGraphLineOffset(), Math.max(0, totalUnits - visibleUnits));
        int end = Math.min(totalUnits, offset + visibleUnits);
        return (offset + 1) + "-" + end + "/" + totalUnits;
    }

    private static void lightGraphCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3Snapshot snapshot) {
        panel(context, x, y, w, h, 0xFF8FC5FF);
        drawText(context, text, "Light Stack", x + 8, y + 7, 0xFFE6EDF5);
        int rowY = y + 20;
        miniBarRow(context, text, x + 8, rowY, w - 16, "block", value(snapshot, "world", "Block Light"), parseRatio(value(snapshot, "world", "Block Light"), 15.0D), 0xFF8FC5FF);
        rowY += 11;
        miniBarRow(context, text, x + 8, rowY, w - 16, "sky", value(snapshot, "world", "Sky Light"), parseRatio(value(snapshot, "world", "Sky Light"), 15.0D), 0xFF8FC5FF);
    }

    private static void chunkSectionTowerCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3Snapshot snapshot) {
        panel(context, x, y, w, h, 0xFFC8A24A);
        drawText(context, text, "Section Tower", x + 8, y + 7, 0xFFE6EDF5);
        int towerX = x + 18;
        int towerY = y + 20;
        int towerH = Math.max(50, h - 34);
        int towerW = Math.max(18, Math.min(28, w / 8));
        int sections = Math.max(1, (int) parseDouble(value(snapshot, "chunk", "Section Count"), 24.0D));
        int current = clamp((int) parseDouble(value(snapshot, "chunk", "Section Index"), 0.0D), 0, sections - 1);
        boolean slime = safeValue(value(snapshot, "chunk", "Slime Chunk")).equalsIgnoreCase("true");
        context.fill(towerX - 2, towerY - 2, towerX + towerW + 2, towerY + towerH + 2, 0x66050609);
        for (int i = 0; i < sections; i++) {
            int top = towerY + towerH - (int) Math.round((i + 1) * (towerH / (double) sections));
            int bottom = towerY + towerH - (int) Math.round(i * (towerH / (double) sections));
            int color = chunkSectionColor(i, current, clampRatio((i + 1) / (double) sections), slime);
            context.fill(towerX, top, towerX + towerW, bottom - 1, color);
            context.fill(towerX, top, towerX + towerW, Math.min(bottom - 1, top + 1), withAlpha(softText(color), 170));
            if (i == current) {
                context.drawBorder(towerX - 1, top - 1, towerW + 2, Math.max(2, bottom - top), 0xFFE6EDF5);
            }
        }
        int rowX = x + 54;
        int rowW = Math.max(34, w - 62);
        int rowY = y + 21;
        miniBarRow(context, text, rowX, rowY, rowW, "current", value(snapshot, "chunk", "Section Y"), clampRatio((current + 1) / (double) sections), 0xFFC8A24A);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "count", String.valueOf(sections), clampRatio(sections / 24.0D), 0xFF8FC5FF);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "slime", value(snapshot, "chunk", "Slime Chunk"), slime ? 1.0D : 0.0D, slime ? 0xFF2DA700 : 0xFF8FA0B8);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "region", value(snapshot, "chunk", "Region Local"), parseRatio(value(snapshot, "chunk", "Region Local"), 32.0D), 0xFFB47CFF);
        if (rowY + 20 < y + h) {
            drawText(context, text, trimToWidth(text, value(snapshot, "chunk", "World Height"), rowW), rowX, y + h - 11, 0xFF8FA0B8);
        }
    }

    private static void playerVitalsCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3Snapshot snapshot) {
        panel(context, x, y, w, h, 0xFF7FC8C2);
        drawText(context, text, "Self Signals", x + 8, y + 7, 0xFFE6EDF5);
        int rowY = y + 20;
        miniBarRow(context, text, x + 8, rowY, w - 16, "health", value(snapshot, "player", "Health"), parseFraction(value(snapshot, "player", "Health")), 0xFF2DA700);
        rowY += 11;
        miniBarRow(context, text, x + 8, rowY, w - 16, "food", value(snapshot, "player", "Hunger"), parseRatio(value(snapshot, "player", "Hunger"), 20.0D), 0xFF8FC5FF);
        rowY += 11;
        miniBarRow(context, text, x + 8, rowY, w - 16, "speed", formatRatio(vectorMagnitude(value(snapshot, "player", "Velocity"))), clampRatio(vectorMagnitude(value(snapshot, "player", "Velocity")) / 0.75D), 0xFF8FC5FF);
        if (rowY + 22 < y + h) {
            rowY += 11;
            miniBarRow(context, text, x + 8, rowY, w - 16, "pitch", formatRatio(Math.abs(extractPitch(value(snapshot, "player", "Facing"))) / 90.0D), clampRatio(Math.abs(extractPitch(value(snapshot, "player", "Facing"))) / 90.0D), 0xFFB47CFF);
        }
    }

    private static void networkPulseCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3Snapshot snapshot) {
        panel(context, x, y, w, h, 0xFF8FC5FF);
        drawText(context, text, "Network Pulse", x + 8, y + 7, 0xFFE6EDF5);
        double pingRatio = clampRatio(parseDouble(value(snapshot, "network", "Ping"), 0.0D) / 350.0D);
        double playerRatio = clampRatio(parseDouble(value(snapshot, "network", "Players Listed"), 0.0D) / 80.0D);
        int pulseX = x + 29;
        int pulseY = y + Math.max(35, h / 2 + 6);
        int pulseR = Math.max(12, Math.min(20, h / 3));
        drawPulseRings(context, pulseX, pulseY, pulseR, pingRatio, 0xFF8FC5FF);
        int barX = x + 58;
        int barW = Math.max(36, w - 66);
        miniBarRow(context, text, barX, y + 21, barW, "ping", value(snapshot, "network", "Ping"), pingRatio, pingRatio > 0.7D ? 0xFFE06A21 : 0xFF8FC5FF);
        miniBarRow(context, text, barX, y + 33, barW, "players", value(snapshot, "network", "Players Listed"), playerRatio, 0xFF7FC8C2);
        if (y + 57 < y + h) {
            miniBarRow(context, text, barX, y + 45, barW, "type", trim(value(snapshot, "network", "Connection"), 16), "server".equals(value(snapshot, "world", "World Type")) ? 1.0D : 0.35D, 0xFFB47CFF);
        }
        drawText(context, text, trimToWidth(text, firstKnown(value(snapshot, "network", "Server Brand"), value(snapshot, "network", "Brand")), w - 16), x + 8, y + h - 11, 0xFF8FA0B8);
    }

    private static void renderStackCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3Snapshot snapshot) {
        panel(context, x, y, w, h, 0xFFB47CFF);
        drawText(context, text, "Render Stack", x + 8, y + 7, 0xFFE6EDF5);
        double render = parseRatio(value(snapshot, "render", "Render Distance"), 64.0D);
        double sim = parseRatio(value(snapshot, "render", "Simulation Distance"), 32.0D);
        double packs = parseRatio(value(snapshot, "render", "Resource Packs"), 64.0D);
        double scale = parseRatio(value(snapshot, "render", "GUI Scale"), 8.0D);
        int stackX = x + 8;
        int stackY = y + 22;
        int stackW = Math.max(58, w - 16);
        int layerH = 9;
        drawStackLayer(context, text, stackX, stackY, stackW, layerH, "render", value(snapshot, "render", "Render Distance"), render, 0xFFB47CFF);
        drawStackLayer(context, text, stackX + 5, stackY + 13, stackW - 10, layerH, "sim", value(snapshot, "render", "Simulation Distance"), sim, 0xFF8FC5FF);
        drawStackLayer(context, text, stackX + 10, stackY + 26, stackW - 20, layerH, "packs", value(snapshot, "render", "Resource Packs"), packs, 0xFF8FC5FF);
        drawStackLayer(context, text, stackX + 15, stackY + 39, stackW - 30, layerH, "scale", value(snapshot, "render", "GUI Scale"), scale, 0xFF7FC8C2);
        int rowY = stackY + 55;
        if (rowY + 8 < y + h) {
            drawText(context, text, trimToWidth(text, "graphics " + value(snapshot, "render", "Graphics") + " | clouds " + value(snapshot, "render", "Clouds"), w - 16), x + 8, rowY, 0xFFB8C4D2);
        }
        if (rowY + 19 < y + h) {
            drawText(context, text, trimToWidth(text, "mip " + value(snapshot, "render", "Mipmap") + " | vsync " + value(snapshot, "render", "VSync"), w - 16), x + 8, rowY + 10, 0xFF8FA0B8);
        }
    }

    private static void graphPieCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3Snapshot snapshot) {
        List<PieSlice> slices = debugPieSlices(snapshot);
        panel(context, x, y, w, h, 0xFF8FC5FF);
        drawText(context, text, "Debug Pie", x + 8, y + 7, 0xFFE6EDF5);
        PieSlice top = slices.isEmpty() ? new PieSlice("root", 1.0D, 0xFF8D8D8D) : slices.get(0);
        drawText(context, text, trimToWidth(text, top.label() + " " + percent(top.ratio()), w - 82), x + 74, y + 7, top.color());
        int radius = Math.max(20, Math.min(34, Math.min(w / 5, (h - 42) / 2)));
        int cx = x + 10 + radius;
        int cy = y + 18 + radius;
        drawDebugPie(context, cx, cy, radius, slices);
        drawPieHub(context, text, cx, cy, radius, top);
        int legendX = cx + radius + 8;
        int legendY = y + 23;
        int shown = Math.min(slices.size(), Math.max(3, Math.min(10, (h - 56) / 10)));
        for (int i = 0; i < shown; i++) {
            PieSlice slice = slices.get(i);
            int rowY = legendY + i * 10;
            context.fill(legendX, rowY + 2, legendX + 5, rowY + 7, slice.color());
            drawText(context, text, trimToWidth(text, vanillaPieIndex(i) + " " + slice.label(), Math.max(18, w - (legendX - x) - 36)), legendX + 8, rowY, i == 0 ? 0xFFE6EDF5 : 0xFFB8C4D2);
            drawText(context, text, percent(slice.ratio()), x + w - 28, rowY, slice.color());
        }
        int detailY = legendY + shown * 10 + 4;
        int detailBottom = y + h - 31;
        if (detailY + 10 <= detailBottom) {
            drawPieExtraRows(context, text, slices, x + 8, detailY, w - 16, detailBottom - detailY);
        }
        drawPiePressureStrip(context, text, slices, x + 8, y + h - 28, w - 16);
        drawText(context, text, trimToWidth(text, "style: vanilla profiler | data: client estimates", w - 16), x + 8, y + h - 11, 0xFF8FA0B8);
    }

    private static void drawPieExtraRows(DrawContext context, TextRenderer text, List<PieSlice> slices, int x, int y, int w, int h) {
        int rows = Math.min(slices.size(), Math.max(0, h / 10));
        for (int i = 0; i < rows; i++) {
            PieSlice slice = slices.get(i);
            int rowY = y + i * 10;
            int fill = clamp((int) Math.round(w * clampRatio(slice.ratio())), 1, w);
            context.fill(x, rowY + 7, x + w, rowY + 9, 0x552A3038);
            context.fill(x, rowY + 7, x + fill, rowY + 9, withAlpha(slice.color(), 210));
            drawText(context, text, trimToWidth(text, slice.label(), Math.max(24, w - 42)), x, rowY, softText(slice.color()));
            drawText(context, text, percent(slice.ratio()), x + w - 30, rowY, slice.color());
        }
    }

    private static void graphSparkCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, String title, Chart chart, String value, int accent) {
        panel(context, x, y, w, h, accent);
        drawText(context, text, title, x + 8, y + 7, 0xFFE6EDF5);
        drawText(context, text, trimToWidth(text, value, 48), x + w - Math.min(52, text.getWidth(value) + 4), y + 7, accent);
        int chartY = y + 20;
        int chartH = Math.max(16, h - 28);
        drawSpark(context, x + 8, chartY, w - 16, chartH, chart);
        drawGraphGrid(context, x + 8, chartY, w - 16, chartH);
    }

    private static void frameTimelineCard(DrawContext context, TextRenderer text, int x, int y, int w, int h) {
        panel(context, x, y, w, h, 0xFF8FC5FF);
        drawText(context, text, "Frame Timeline", x + 8, y + 7, 0xFFE6EDF5);
        List<PerformanceMonitor.Sample> samples = new ArrayList<>(PerformanceMonitor.samples());
        int baseX = x + 8;
        int baseY = y + 21;
        int chartW = Math.max(28, w - 16);
        int chartH = Math.max(18, h - 25);
        context.fill(baseX, baseY, baseX + chartW, baseY + chartH, PANEL_BG_SOFT);
        context.drawBorder(baseX, baseY, chartW, chartH, 0x552A3038);
        if (samples.isEmpty()) {
            drawText(context, text, "collecting", baseX + 4, baseY + 5, 0xFF8FA0B8);
            return;
        }
        int maxBars = Math.min(samples.size(), Math.max(12, chartW / 3));
        int start = Math.max(0, samples.size() - maxBars);
        int barW = Math.max(2, chartW / Math.max(1, maxBars));
        for (int i = start; i < samples.size(); i++) {
            PerformanceMonitor.Sample sample = samples.get(i);
            double frame = sample.frameTimeMs();
            double ratio = clampRatio(frame / 120.0D);
            int px = baseX + (i - start) * barW;
            int ph = Math.max(2, (int) Math.round((chartH - 4) * ratio));
            int color = frame > 120.0D ? 0xFFA7003A : frame > 75.0D ? 0xFFE06A21 : frame > 40.0D ? 0xFFE3B735 : 0xFF2DA700;
            context.fill(px, baseY + chartH - ph - 1, px + Math.max(1, barW - 1), baseY + chartH - 1, color);
            if (frame > 75.0D) {
                context.fill(px, baseY + 1, px + Math.max(1, barW - 1), baseY + 3, withAlpha(color, 220));
            }
        }
    }

    private static void terrainNoiseCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3Snapshot snapshot) {
        panel(context, x, y, w, h, 0xFF7FC8C2);
        drawText(context, text, "Environment Signals", x + 8, y + 7, 0xFFE6EDF5);
        List<GraphSignal> signals = expandedTerrainSignals(snapshot);
        int rowY = y + 20;
        int limit = Math.min(signals.size(), Math.max(3, (h - 24) / 10));
        for (int i = 0; i < limit; i++) {
            GraphSignal signal = signals.get(i);
            miniBarRow(context, text, x + 8, rowY, w - 16, signal.label(), signal.value(), signal.ratio(), signal.color());
            rowY += 10;
        }
    }

    private static void worldRhythmCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3Snapshot snapshot) {
        panel(context, x, y, w, h, 0xFF8FC5FF);
        drawText(context, text, "World Rhythm", x + 8, y + 7, 0xFFE6EDF5);
        int cx = x + 28;
        int cy = y + Math.max(39, Math.min(h - 17, 44));
        int r = Math.max(9, Math.min(15, Math.min((h - 31) / 2, 16)));
        drawClockRing(context, cx, cy, r, timeRatio(value(snapshot, "world", "Time")), 0xFF8FC5FF);
        int textX = x + 52;
        int textW = Math.max(32, w - 60);
        drawText(context, text, "day " + safeValue(value(snapshot, "world", "Day")), textX, y + 22, 0xFFB8C4D2);
        drawText(context, text, trimToWidth(text, value(snapshot, "world", "Weather"), textW), textX, y + 33, 0xFF8FA0B8);
        if (y + 50 < y + h - 11) {
            drawText(context, text, trimToWidth(text, "moon " + value(snapshot, "world", "Moon Phase"), textW), textX, y + 44, 0xFFC8A24A);
        }
        drawMeter(context, x + 52, y + h - 10, w - 60, 3, parseRatio(value(snapshot, "world", "Sky Light"), 15.0D), 0xFF8FC5FF);
    }

    private static void chunkHeatCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3Snapshot snapshot) {
        panel(context, x, y, w, h, 0xFFC8A24A);
        drawText(context, text, "Chunk Heat", x + 8, y + 7, 0xFFE6EDF5);
        double entities = parseRatio(firstKnown(value(snapshot, "chunk", "Chunk Entities"), value(snapshot, "chunk", "Client Entities")), 300.0D);
        double blockEntities = parseRatio(value(snapshot, "chunk", "Block Entities"), 128.0D);
        double sections = parseRatio(value(snapshot, "chunk", "Section Count"), 24.0D);
        double age = parseRatio(value(snapshot, "chunk", "Inhabited Time"), 24000.0D);
        int gridW = Math.min(w - 16, 96);
        int gridX = x + 8;
        int gridY = y + 20;
        drawHeatGrid(context, gridX, gridY, Math.max(6, Math.min(12, gridW / 8)), new double[]{entities, blockEntities, sections, age}, new int[]{0xFFE06A21, 0xFFB47CFF, 0xFF8FC5FF, 0xFF7FC8C2});
        int rowY = gridY + 2;
        int textX = gridX + Math.max(52, Math.min(80, gridW)) + 8;
        int textW = Math.max(28, w - (textX - x) - 8);
        drawText(context, text, trimToWidth(text, "ent " + firstKnown(value(snapshot, "chunk", "Chunk Entities"), value(snapshot, "chunk", "Client Entities")), textW), textX, rowY, 0xFFB8C4D2);
        drawText(context, text, trimToWidth(text, "be " + value(snapshot, "chunk", "Block Entities"), textW), textX, rowY + 10, 0xFF8FA0B8);
        drawText(context, text, trimToWidth(text, "sec " + value(snapshot, "chunk", "Section Count"), textW), textX, rowY + 20, 0xFF8FC5FF);
        drawText(context, text, trimToWidth(text, "age " + value(snapshot, "chunk", "Inhabited Time"), textW), textX, rowY + 30, 0xFF7FC8C2);
        context.fill(x + 5, gridY, x + 6, Math.min(y + h - 8, gridY + Math.max(10, (int) Math.round((h - 28) * Math.max(Math.max(entities, blockEntities), Math.max(sections, age))))), 0x99E06A21);
    }


    private static void chunkHealthCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3Snapshot snapshot) {
        panel(context, x, y, w, h, 0xFFC8A24A);
        drawText(context, text, "Chunk Health", x + 8, y + 7, 0xFFE6EDF5);
        double entities = parseRatio(firstKnown(value(snapshot, "chunk", "Chunk Entities"), value(snapshot, "chunk", "Client Entities")), 220.0D);
        double blockEntities = parseRatio(value(snapshot, "chunk", "Block Entities"), 96.0D);
        double sections = parseRatio(value(snapshot, "chunk", "Section Count"), 24.0D);
        double light = Math.max(parseRatio(value(snapshot, "world", "Block Light"), 15.0D), parseRatio(value(snapshot, "world", "Sky Light"), 15.0D));
        double age = parseRatio(value(snapshot, "chunk", "Inhabited Time"), 72000.0D);
        boolean slime = safeValue(value(snapshot, "chunk", "Slime Chunk")).equalsIgnoreCase("true");
        double danger = clampRatio(entities * 0.30D + blockEntities * 0.28D + sections * 0.12D + light * 0.10D + age * 0.12D + (slime ? 0.08D : 0.0D));
        String label = chunkHealthLabel(entities, blockEntities, danger);
        int cx = x + 35;
        int cy = y + 55;
        drawRadialSignals(context, cx, cy, Math.max(15, Math.min(24, h / 4)), new double[]{entities, blockEntities, sections, light, age, danger}, new int[]{0xFFE06A21, 0xFFB47CFF, 0xFF8FC5FF, 0xFFE3B735, 0xFF7FC8C2, chunkHealthColor(danger)});
        drawText(context, text, trimToWidth(text, label, 58), x + 9, y + h - 12, chunkHealthColor(danger));
        int rowX = x + 70;
        int rowW = Math.max(38, w - 78);
        int rowY = y + 21;
        miniBarRow(context, text, rowX, rowY, rowW, "entities", firstKnown(value(snapshot, "chunk", "Chunk Entities"), value(snapshot, "chunk", "Client Entities")), entities, 0xFFE06A21);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "tile", value(snapshot, "chunk", "Block Entities"), blockEntities, 0xFFB47CFF);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "sections", value(snapshot, "chunk", "Section Count"), sections, 0xFF8FC5FF);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "light", percent(light), light, 0xFF8FC5FF);
        rowY += 10;
        if (rowY + 8 < y + h) {
            miniBarRow(context, text, rowX, rowY, rowW, "region", value(snapshot, "chunk", "Region Local"), parseRatio(value(snapshot, "chunk", "Region Local"), 32.0D), 0xFFC8A24A);
        }
    }

    private static void chunkSectionsCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3Snapshot snapshot) {
        panel(context, x, y, w, h, 0xFFC8A24A);
        drawText(context, text, "Chunk Sections", x + 8, y + 7, 0xFFE6EDF5);
        double sectionIndex = parseDouble(value(snapshot, "chunk", "Section Index"), 0.0D);
        double sectionCount = Math.max(1.0D, parseDouble(value(snapshot, "chunk", "Section Count"), 24.0D));
        boolean slime = safeValue(value(snapshot, "chunk", "Slime Chunk")).equalsIgnoreCase("true");
        int cols = 4;
        int rows = 6;
        int bottomBarY = y + h - 16;
        int availableGridH = Math.max(24, bottomBarY - (y + 24) - 5);
        int cell = Math.max(5, Math.min(Math.max(5, (w - 96) / cols), Math.max(5, availableGridH / rows)));
        int gridX = x + 8;
        int gridY = y + 23;
        int current = clamp((int) Math.round(sectionIndex), 0, (int) sectionCount - 1);
        context.fill(gridX - 2, gridY - 2, gridX + cols * cell + 2, gridY + rows * cell + 2, 0x66050609);
        for (int i = 0; i < cols * rows; i++) {
            int px = gridX + (i % cols) * cell;
            int py = gridY + (rows - 1 - i / cols) * cell;
            double ratio = clampRatio((i + 1) / sectionCount);
            int color = chunkSectionColor(i, current, ratio, slime);
            context.fill(px, py, px + cell - 1, py + cell - 1, color);
            context.fill(px, py, px + cell - 1, py + 1, withAlpha(softText(color), i == current ? 210 : 90));
            context.fill(px, py, px + 1, py + cell - 1, withAlpha(softText(color), i == current ? 190 : 70));
            if (i == current) {
                context.drawBorder(px - 1, py - 1, cell + 1, cell + 1, 0xFFE6EDF5);
                context.fill(px + Math.max(1, cell / 2), py + 1, px + Math.max(2, cell / 2 + 1), py + cell - 2, 0xAAE6EDF5);
            }
        }
        int textX = gridX + cols * cell + 10;
        int textW = Math.max(28, w - (textX - x) - 8);
        drawText(context, text, trimToWidth(text, "chunk " + value(snapshot, "chunk", "Chunk"), textW), textX, gridY, 0xFFB8C4D2);
        drawText(context, text, trimToWidth(text, "local " + value(snapshot, "chunk", "Local Block"), textW), textX, gridY + 10, 0xFF8FA0B8);
        drawText(context, text, trimToWidth(text, "slime " + value(snapshot, "chunk", "Slime Chunk"), textW), textX, gridY + 20, slimeChunkColor(value(snapshot, "chunk", "Slime Chunk")));
        drawText(context, text, trimToWidth(text, "region " + value(snapshot, "chunk", "Region Local"), textW), textX, gridY + 30, 0xFFC8A24A);
        int barY = Math.max(gridY + rows * cell + 5, bottomBarY);
        if (barY + 10 <= y + h - 3) {
            miniBarRow(context, text, x + 8, barY, w - 16, "entities", firstKnown(value(snapshot, "chunk", "Chunk Entities"), value(snapshot, "chunk", "Client Entities")), parseRatio(firstKnown(value(snapshot, "chunk", "Chunk Entities"), value(snapshot, "chunk", "Client Entities")), 300.0D), 0xFFE06A21);
        }
    }


    private static void playerMotionCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3Snapshot snapshot) {
        panel(context, x, y, w, h, 0xFF7FC8C2);
        drawText(context, text, "Motion / Legality", x + 8, y + 7, 0xFFE6EDF5);
        MinecraftClient client = MinecraftClient.getInstance();
        double[] velocity = firstThreeNumbers(value(snapshot, "player", "Velocity"));
        double horizontal = Math.sqrt(velocity[0] * velocity[0] + velocity[2] * velocity[2]);
        double headroom = playerHeadroomClear(client) ? 1.0D : 0.0D;
        double gap = nextGapClearance(client);
        double sprint = booleanRatio(value(snapshot, "player", "Sprinting"));
        double ground = booleanRatio(value(snapshot, "player", "Ground"));
        double friction = currentFrictionEstimate(client);
        double movement = Math.max(targetRatio(snapshot.target(), "Velocity Mult", 1.0D), targetRatio(snapshot.target(), "Jump Mult", 1.0D));
        int graphX = x + 8;
        int graphY = y + 22;
        int graphW = Math.max(56, w - 16);
        int graphH = Math.min(48, Math.max(34, h / 4));
        int midX = graphX + graphW / 2;
        int midY = graphY + graphH / 2;
        context.fill(graphX, graphY, graphX + graphW, graphY + graphH, PANEL_BG_SOFT);
        context.drawBorder(graphX, graphY, graphW, graphH, 0x552A3038);
        context.fill(midX, graphY + 2, midX + 1, graphY + graphH - 2, 0x332A3038);
        context.fill(graphX + 2, midY, graphX + graphW - 2, midY + 1, 0x332A3038);
        int gapCursor = graphX + clamp((int) Math.round(graphW * clampRatio(gap)), 0, graphW - 1);
        context.fill(gapCursor, graphY + 1, gapCursor + 1, graphY + graphH - 1, gap > 0.65D ? 0x992DA700 : gap > 0.35D ? 0x99E3B735 : 0x99E06A21);
        context.enableScissor(graphX + 1, graphY + 1, graphX + graphW - 1, graphY + graphH - 1);
        drawArrow(context, midX, midY, midX + clamp((int) Math.round(velocity[0] * (graphW / 2.8D)), -graphW / 2 + 4, graphW / 2 - 4), midY - clamp((int) Math.round(velocity[2] * (graphH / 1.2D)), -graphH / 2 + 4, graphH / 2 - 4), 0xFF8FC5FF);
        drawArrow(context, midX, midY, midX, midY - clamp((int) Math.round(velocity[1] * 22.0D), -12, 12), velocity[1] >= 0.0D ? 0xFF55FF55 : 0xFFFF5555);
        context.disableScissor();
        int arcBaseX = graphX + 7;
        int arcBaseY = graphY + graphH + 23;
        if (arcBaseY + 3 < y + h) {
            drawJumpArcPreview(context, arcBaseX, arcBaseY, Math.max(34, graphW - 14), 18, horizontal, velocity[1], value(snapshot, "player", "Ground"));
        }
        int rowY = arcBaseY + 9;
        if (rowY + 10 <= y + h - 4) {
            miniBarRow(context, text, x + 8, rowY, w - 16, "speed", formatRatio(vectorMagnitude(value(snapshot, "player", "Velocity"))), clampRatio(vectorMagnitude(value(snapshot, "player", "Velocity")) / 0.75D), 0xFF8FC5FF);
            rowY += 11;
        }
        if (rowY + 10 <= y + h - 4) {
            miniBarRow(context, text, x + 8, rowY, w - 16, "gap", gap > 0.65D ? "clear" : gap > 0.35D ? "tight" : "blocked", gap, gap > 0.65D ? 0xFF2DA700 : gap > 0.35D ? 0xFFE3B735 : 0xFFE06A21);
            rowY += 11;
        }
        if (rowY + 10 <= y + h - 4) {
            miniBarRow(context, text, x + 8, rowY, w - 16, "headroom", headroom > 0.5D ? "clear" : "blocked", headroom, headroom > 0.5D ? 0xFF2DA700 : 0xFFE06A21);
            rowY += 11;
        }
        if (rowY + 10 <= y + h - 4) {
            miniBarRow(context, text, x + 8, rowY, w - 16, "friction", formatRatio(friction), clampRatio(friction), 0xFF8FC5FF);
            rowY += 11;
        }
        if (rowY + 10 <= y + h - 4) {
            miniBarRow(context, text, x + 8, rowY, w - 16, "move mult", formatRatio(movement), clampRatio(movement), 0xFFB47CFF);
            rowY += 12;
        }
        if (rowY + 14 <= y + h - 4) {
            int cellW = Math.max(24, (w - 20) / 4);
            drawFlagCell(context, text, x + 8, rowY, cellW, "ground", value(snapshot, "player", "Ground"), ground > 0.5D ? 0xFF2DA700 : 0xFF8FA0B8);
            drawFlagCell(context, text, x + 12 + cellW, rowY, cellW, "sprint", value(snapshot, "player", "Sprinting"), sprint > 0.5D ? 0xFFE3B735 : 0xFF8FA0B8);
            drawFlagCell(context, text, x + 16 + cellW * 2, rowY, cellW, "sneak", value(snapshot, "player", "Sneaking"), 0xFFB47CFF);
            drawFlagCell(context, text, x + 20 + cellW * 3, rowY, cellW, "swim", value(snapshot, "player", "Swimming"), 0xFF8FC5FF);
        }
    }



    private static void targetSignalsCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3Snapshot snapshot) {
        F3TargetSnapshot target = snapshot.target();
        panel(context, x, y, w, h, target.accentColor());
        drawText(context, text, "Target Signals", x + 8, y + 7, 0xFFE6EDF5);
        int rowY = y + 20;
        miniBarRow(context, text, x + 8, rowY, w - 16, "rows", String.valueOf(target.lines().size()), clampRatio(target.lines().size() / 48.0D), target.accentColor());
        rowY += 10;
        miniBarRow(context, text, x + 8, rowY, w - 16, "tags", String.valueOf(target.tags().size()), clampRatio(target.tags().size() / 160.0D), 0xFF8FC5FF);
        rowY += 10;
        miniBarRow(context, text, x + 8, rowY, w - 16, "actions", String.valueOf(target.actions().size()), clampRatio(target.actions().size() / 12.0D), 0xFF8FC5FF);
        if (rowY + 18 < y + h) {
            rowY += 10;
            miniBarRow(context, text, x + 8, rowY, w - 16, "danger", target.danger(), dangerRatio(target.danger()), 0xFFE06A21);
        }
        if (rowY + 26 < y + h) {
            drawTargetTypeGlyph(context, x + 10, y + h - 17, target);
            drawText(context, text, trimToWidth(text, target.type().label() + " | " + target.modOwner(), w - 34), x + 32, y + h - 16, 0xFFB8C4D2);
        }
    }

    private static void targetBlockProfileCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3TargetSnapshot target) {
        panel(context, x, y, w, h, target.accentColor());
        drawText(context, text, "Block Profile", x + 8, y + 7, 0xFFE6EDF5);
        int cx = x + 28;
        int cy = y + 43;
        drawRadialSignals(context, cx, cy, Math.max(14, Math.min(22, h / 3)), new double[]{targetRatio(target, "Hardness", 50.0D), targetRatio(target, "Blast Resistance", 1200.0D), targetRatio(target, "Luminance", 15.0D), targetRatio(target, "Redstone Power", 15.0D)}, new int[]{0xFFE3B735, 0xFFE06A21, 0xFF8FC5FF, 0xFFA7003A});
        int rowY = y + 21;
        int rowX = x + 58;
        miniBarRow(context, text, rowX, rowY, w - 66, "hard", targetLine(target, "Hardness"), targetRatio(target, "Hardness", 50.0D), 0xFF8FC5FF);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, w - 66, "blast", targetLine(target, "Blast Resistance"), targetRatio(target, "Blast Resistance", 1200.0D), 0xFFE06A21);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, w - 66, "light", firstKnown(targetLine(target, "Luminance"), targetLine(target, "Client Light")), Math.max(targetRatio(target, "Luminance", 15.0D), targetRatio(target, "Client Light", 15.0D)), 0xFF8FC5FF);
        rowY += 10;
        if (rowY + 8 < y + h) {
            miniBarRow(context, text, rowX, rowY, w - 66, "power", targetLine(target, "Redstone Power"), targetRatio(target, "Redstone Power", 15.0D), 0xFFA7003A);
        }
    }

    private static void targetBlockStructureCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3TargetSnapshot target) {
        panel(context, x, y, w, h, 0xFFC8A24A);
        drawText(context, text, "Block Structure", x + 8, y + 7, 0xFFE6EDF5);
        double props = targetRatio(target, "Properties", 32.0D);
        double tags = clampRatio(target.tags().size() / 160.0D);
        double collision = targetRatio(target, "Collision Boxes", 16.0D);
        double outline = targetRatio(target, "Outline Boxes", 16.0D);
        drawHeatGrid(context, x + 8, y + 22, Math.max(5, Math.min(8, (h - 28) / 4)), new double[]{props, tags, collision, outline}, new int[]{0xFFE3B735, 0xFF7FC8C2, 0xFFE06A21, 0xFFB47CFF});
        int rowX = x + 48;
        int rowY = y + 21;
        miniBarRow(context, text, rowX, rowY, w - 56, "props", targetLine(target, "Properties"), props, 0xFF8FC5FF);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, w - 56, "tags", String.valueOf(target.tags().size()), tags, 0xFF7FC8C2);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, w - 56, "shape", targetLine(target, "Collision Boxes") + "/" + targetLine(target, "Outline Boxes"), Math.max(collision, outline), 0xFFB47CFF);
        rowY += 10;
        if (rowY + 8 < y + h) {
            miniBarRow(context, text, rowX, rowY, w - 56, "mine est", targetLine(target, "Mining Rate Est"), targetRatio(target, "Mining Rate Est", 10.0D), 0xFF2DA700);
        }
    }


    private static void targetBlockShapeCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3TargetSnapshot target) {
        panel(context, x, y, w, h, 0xFFB47CFF);
        drawText(context, text, "Block Shape", x + 8, y + 7, 0xFFE6EDF5);
        drawMinecraftTargetIcon(context, x + w - 18, y + 5, target);
        int cubeX = x + 31;
        int size = Math.max(16, Math.min(25, h / 3));
        int cubeY = safeSidePanelBoxBaseY(y, h, size, 30, 16);
        BlockVisualData visual = blockVisualData(target);
        drawBlockDataModel(context, cubeX, cubeY, size, target, visual);
        int rowX = x + 66;
        int rowY = y + 21;
        miniBarRow(context, text, rowX, rowY, w - 74, "collision", targetLine(target, "Collision Boxes"), targetRatio(target, "Collision Boxes", 16.0D), 0xFFE06A21);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, w - 74, "outline", targetLine(target, "Outline Boxes"), targetRatio(target, "Outline Boxes", 16.0D), 0xFF8FC5FF);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, w - 74, "height", formatRatio(visual.collisionHeight()) + "/" + formatRatio(visual.outlineHeight()), Math.max(visual.collisionHeight(), visual.outlineHeight()), 0xFF8FC5FF);
        rowY += 10;
        if (rowY + 8 < y + h) {
            miniBarRow(context, text, rowX, rowY, w - 74, "state", blockVisualStateSummary(target), visual.statePressure(), visual.primaryStateColor());
        }
    }


    private static void targetBlockFlagsCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3TargetSnapshot target) {
        panel(context, x, y, w, h, 0xFF8FC5FF);
        drawText(context, text, "Block Flags", x + 8, y + 7, 0xFFE6EDF5);
        int cellW = Math.max(24, (w - 20) / 4);
        int rowY = y + 22;
        drawFlagCell(context, text, x + 8, rowY, cellW, "opaque", targetLine(target, "Opaque"), 0xFF8FC5FF);
        drawFlagCell(context, text, x + 12 + cellW, rowY, cellW, "solid", targetLine(target, "Solid Block"), 0xFF2DA700);
        drawFlagCell(context, text, x + 16 + cellW * 2, rowY, cellW, "replace", targetLine(target, "Replaceable"), 0xFFB47CFF);
        drawFlagCell(context, text, x + 20 + cellW * 3, rowY, cellW, "tool", targetLine(target, "Tool Required"), 0xFFE06A21);
        rowY += 17;
        drawFlagCell(context, text, x + 8, rowY, cellW, "burn", targetLine(target, "Burnable"), 0xFFE06A21);
        drawFlagCell(context, text, x + 12 + cellW, rowY, cellW, "tick", targetLine(target, "Random Ticks"), 0xFF8FC5FF);
        drawFlagCell(context, text, x + 16 + cellW * 2, rowY, cellW, "red", targetLine(target, "Emits Redstone"), 0xFFA7003A);
        drawFlagCell(context, text, x + 20 + cellW * 3, rowY, cellW, "fluid", targetLine(target, "Waterlogged/Fluid"), 0xFF0085A4);
        if (rowY + 24 < y + h) {
            rowY += 18;
            miniBarRow(context, text, x + 8, rowY, w - 16, "move", targetLine(target, "Velocity Mult") + " / jump " + targetLine(target, "Jump Mult"), Math.max(targetRatio(target, "Velocity Mult", 1.0D), targetRatio(target, "Jump Mult", 1.0D)), 0xFF7FC8C2);
        }
    }

    private static void targetBlockFaceStateCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3TargetSnapshot target) {
        panel(context, x, y, w, h, 0xFFB47CFF);
        drawText(context, text, "Block Face State", x + 8, y + 7, 0xFFE6EDF5);
        drawMinecraftTargetIcon(context, x + w - 18, y + 5, target);
        int cx = x + 34;
        int size = Math.max(12, Math.min(18, h / 5));
        int cy = safeSidePanelBoxBaseY(y, h, size, 33, 16);
        drawBlockDataModel(context, cx, cy, size, target, blockVisualData(target));
        int listX = x + 66;
        int listY = y + 21;
        int listW = Math.max(40, w - 74);
        drawText(context, text, "state values", listX, listY, 0xFF8FA0B8);
        listY += 11;
        List<GraphSignal> states = blockStateSignals(target);
        int limit = Math.min(states.size(), Math.max(3, (h - 36) / 10));
        for (int i = 0; i < limit; i++) {
            GraphSignal signal = states.get(i);
            drawStateListRow(context, text, listX, listY, listW, signal);
            listY += 10;
        }
        if (limit == 0) {
            drawText(context, text, "no visible states", listX, listY, 0xFF8FA0B8);
        }
        if (listY + 10 < y + h) {
            String footer = compactValues(targetLine(target, "Shape Detail"), targetLine(target, "Type Detail"), targetLine(target, "Mode State"));
            drawText(context, text, trimToWidth(text, footer.isBlank() ? targetLine(target, "State") : footer, w - 16), x + 8, y + h - 11, 0xFF8FA0B8);
        }
    }

    private static void targetBlockNeighborPanelCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3TargetSnapshot target) {
        panel(context, x, y, w, h, 0xFFC8A24A);
        drawText(context, text, "Neighbor Map", x + 8, y + 7, 0xFFE6EDF5);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.crosshairTarget == null || client.crosshairTarget.getType() != HitResult.Type.BLOCK || !(client.crosshairTarget instanceof BlockHitResult blockHit)) {
            drawText(context, text, "look at a block", x + 8, y + 24, 0xFF8FA0B8);
            return;
        }
        BlockPos center = blockHit.getBlockPos();
        int cell = Math.max(6, Math.min(10, (w - 34) / 11));
        int gap = 2;
        int layerW = cell * 3 + gap * 2;
        int startX = x + 8;
        int layerY = y + 25;
        int[] layers = new int[]{1, 0, -1};
        String[] labels = new String[]{"up", "mid", "down"};
        for (int li = 0; li < layers.length; li++) {
            int layerX = startX + li * (layerW + 8);
            drawText(context, text, labels[li], layerX, layerY - 11, li == 1 ? 0xFFE6EDF5 : 0xFF8FA0B8);
            context.drawBorder(layerX - 1, layerY - 1, layerW + 2, layerW + 2, li == 1 ? 0x66E6EDF5 : 0x448FA0B8);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    BlockPos pos = center.add(dx, layers[li], dz);
                    BlockState state = client.world.getBlockState(pos);
                    int color = neighborProfileColor(client, state, pos);
                    int px = layerX + (dx + 1) * (cell + gap);
                    int py = layerY + (dz + 1) * (cell + gap);
                    int alpha = dx == 0 && dz == 0 && layers[li] == 0 ? 235 : 165;
                    context.fill(px, py, px + cell, py + cell, withAlpha(color, alpha));
                    if (dx == 0 && dz == 0 && layers[li] == 0) {
                        context.drawBorder(px - 1, py - 1, cell + 2, cell + 2, 0xFFE6EDF5);
                    }
                    if (state.hasBlockEntity()) {
                        context.fill(px + cell - 2, py, px + cell, py + 2, 0xFFB47CFF);
                    }
                    if (!client.world.getOtherEntities(null, new Box(pos)).isEmpty()) {
                        context.fill(px, py + cell - 2, px + 2, py + cell, 0xFFE06A21);
                    }
                }
            }
        }
        int legendY = layerY + layerW + 11;
        drawNeighborLegendChip(context, text, x + 8, legendY, "air", 0x665A636E);
        drawNeighborLegendChip(context, text, x + 34, legendY, "solid", 0xFF8FC5FF);
        drawNeighborLegendChip(context, text, x + 72, legendY, "fluid", 0xFF0085A4);
        if (legendY + 12 < y + h) {
            drawNeighborLegendChip(context, text, x + 113, legendY, "data", 0xFFB47CFF);
        }
    }

    private static void targetTagCloudCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3TargetSnapshot target) {
        panel(context, x, y, w, h, 0xFF7FC8C2);
        drawText(context, text, "Tag Map", x + 8, y + 7, 0xFFE6EDF5);
        drawText(context, text, target.tags().size() + " tags", x + w - Math.min(w - 8, text.getWidth(target.tags().size() + " tags") + 8), y + 7, 0xFF8FC5FF);
        int rowY = y + 21;
        List<GraphSignal> namespaces = tagNamespaceSignals(target);
        int limit = Math.min(namespaces.size(), Math.max(2, (h - 24) / 10));
        for (int i = 0; i < limit; i++) {
            GraphSignal signal = namespaces.get(i);
            miniBarRow(context, text, x + 8, rowY, w - 16, signal.label(), signal.value(), signal.ratio(), signal.color());
            rowY += 10;
        }
        if (limit == 0) {
            drawText(context, text, "no tags", x + 8, rowY, 0xFF8FA0B8);
        }
    }

    private static void targetMobVitalsCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3TargetSnapshot target) {
        panel(context, x, y, w, h, target.accentColor());
        drawText(context, text, "Mob Vitals", x + 8, y + 7, 0xFFE6EDF5);
        int cx = x + 28;
        int cy = y + 43;
        drawRadialSignals(context, cx, cy, Math.max(14, Math.min(22, h / 3)), new double[]{parseFraction(targetLine(target, "Health")), targetRatio(target, "Armor", 30.0D), targetRatio(target, "Attack Damage", 20.0D), targetRatio(target, "Follow Range", 64.0D)}, new int[]{0xFF2DA700, 0xFF8FC5FF, 0xFFE06A21, 0xFFB47CFF});
        int rowX = x + 58;
        int rowY = y + 21;
        miniBarRow(context, text, rowX, rowY, w - 66, "hp", targetLine(target, "Health"), parseFraction(targetLine(target, "Health")), 0xFF2DA700);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, w - 66, "armor", targetLine(target, "Armor"), targetRatio(target, "Armor", 30.0D), 0xFF8FC5FF);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, w - 66, "attack", targetLine(target, "Attack Damage"), targetRatio(target, "Attack Damage", 20.0D), 0xFFE06A21);
        rowY += 10;
        if (rowY + 8 < y + h) {
            miniBarRow(context, text, rowX, rowY, w - 66, "range", targetLine(target, "Follow Range"), targetRatio(target, "Follow Range", 64.0D), 0xFFB47CFF);
        }
    }

    private static void targetMobMotionCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3TargetSnapshot target) {
        panel(context, x, y, w, h, 0xFF8FC5FF);
        drawText(context, text, "Mob Motion", x + 8, y + 7, 0xFFE6EDF5);
        int rowY = y + 21;
        miniBarRow(context, text, x + 8, rowY, w - 16, "distance", targetLine(target, "Distance"), targetRatio(target, "Distance", 64.0D), 0xFF7FC8C2);
        rowY += 10;
        miniBarRow(context, text, x + 8, rowY, w - 16, "speed", targetLine(target, "Speed Magnitude"), targetRatio(target, "Speed Magnitude", 1.0D), 0xFF8FC5FF);
        rowY += 10;
        miniBarRow(context, text, x + 8, rowY, w - 16, "air", targetLine(target, "Air"), parseFraction(targetLine(target, "Air")), 0xFF7FC8C2);
        rowY += 10;
        if (rowY + 8 < y + h) {
            miniBarRow(context, text, x + 8, rowY, w - 16, "danger", target.danger(), dangerRatio(target.danger()), 0xFFE06A21);
        }
    }


    private static void targetEntityModelCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3TargetSnapshot target) {
        panel(context, x, y, w, h, 0xFF8FC5FF);
        drawText(context, text, "Entity Model", x + 8, y + 7, 0xFFE6EDF5);
        double[] hitbox = firstThreeNumbers(targetLine(target, "Hitbox"));
        double width = clampDouble(hitbox[0] <= 0.0D ? 0.6D : hitbox[0], 0.25D, 2.8D);
        double entityHeight = clampDouble(hitbox[1] <= 0.0D ? 1.8D : hitbox[1], 0.25D, 4.5D);
        int modelX = x + 43;
        int modelSize = Math.max(23, Math.min(36, h / 3));
        int modelY = safeSidePanelBoxBaseY(y, h, modelSize, 26, 10);
        drawEntityDataModel(context, modelX, modelY, modelSize, target, width, entityHeight);
        int rowX = x + 86;
        int rowY = y + 21;
        int rowW = Math.max(40, w - 94);
        miniBarRow(context, text, rowX, rowY, rowW, "hitbox", targetLine(target, "Hitbox"), clampRatio(entityHeight / 3.0D), target.accentColor());
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "eye", targetLine(target, "Eye Height"), clampRatio(parseDouble(targetLine(target, "Eye Height"), entityHeight * 0.85D) / Math.max(0.1D, entityHeight)), 0xFF8FC5FF);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "hp", targetLine(target, "Health"), parseFraction(targetLine(target, "Health")), 0xFF2DA700);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "move", targetLine(target, "Speed Magnitude"), targetRatio(target, "Speed Magnitude", 1.0D), 0xFF7FC8C2);
        rowY += 11;
        drawEntityBehaviorStrip(context, text, rowX, rowY, rowW, target);
    }


    private static void targetEntityAnatomyCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3TargetSnapshot target) {
        panel(context, x, y, w, h, 0xFF8FC5FF);
        drawText(context, text, "Hitbox Anatomy", x + 8, y + 7, 0xFFE6EDF5);
        double[] hitbox = firstThreeNumbers(targetLine(target, "Hitbox"));
        double width = clampDouble(hitbox[0] <= 0.0D ? 0.6D : hitbox[0], 0.25D, 2.8D);
        double entityHeight = clampDouble(hitbox[1] <= 0.0D ? 1.8D : hitbox[1], 0.25D, 4.5D);
        int cx = x + 34;
        int baseY = safeSidePanelBoxBaseY(y, h, 20, 30, 18);
        int scale = Math.max(15, Math.min(22, h / 4));
        drawEntityDataModel(context, cx, baseY, scale, target, width, entityHeight);
        double reach = Math.max(targetRatio(target, "Attack Reach", 6.0D), targetRatio(target, "Follow Range", 64.0D) * 0.45D);
        drawProjectedSegment(context, cx, baseY, scale, 0.0D, entityHeight / 2.8D, width / 2.0D, 0.0D, entityHeight / 2.8D, width / 2.0D + reach, 0xFFE06A21);
        drawProjectedMarker(context, cx, baseY, scale, 0.0D, entityHeight / 2.8D, width / 2.0D + reach, 0xFFE06A21, 1);
        if (!targetLine(target, "Passenger").equals("unknown") || !targetLine(target, "Passengers").equals("unknown")) {
            drawProjectedMarker(context, cx, baseY, scale, 0.0D, entityHeight / 2.8D + 0.16D, 0.0D, 0xFFB47CFF, 2);
        }
        int rowX = x + 70;
        int rowW = Math.max(38, w - 78);
        int rowY = y + 21;
        miniBarRow(context, text, rowX, rowY, rowW, "full", targetLine(target, "Hitbox"), clampRatio(entityHeight / 3.0D), target.accentColor());
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "eye", targetLine(target, "Eye Height"), clampRatio(parseDouble(targetLine(target, "Eye Height"), entityHeight * 0.85D) / Math.max(0.1D, entityHeight)), 0xFF8FC5FF);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "reach", firstKnown(targetLine(target, "Attack Reach"), targetLine(target, "Follow Range")), reach, 0xFFE06A21);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "mount", firstKnown(targetLine(target, "Vehicle"), targetLine(target, "Passengers")), targetLine(target, "Vehicle").equals("unknown") && targetLine(target, "Passengers").equals("unknown") ? 0.0D : 1.0D, 0xFFB47CFF);
        rowY += 10;
        if (rowY + 8 < y + h) {
            miniBarRow(context, text, rowX, rowY, rowW, "push", targetLine(target, "Pushable"), booleanRatio(targetLine(target, "Pushable")), 0xFF7FC8C2);
        }
    }

    private static void targetMobGoalStackCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3TargetSnapshot target) {
        panel(context, x, y, w, h, 0xFFE06A21);
        drawText(context, text, "Goal Stack", x + 8, y + 7, 0xFFE6EDF5);
        int railX = x + 12;
        int railY = y + 24;
        int step = Math.max(11, (h - 38) / 4);
        drawLine(context, railX + 10, railY + 8, railX + 10, railY + step * 3 + 8, 0x552A3038);
        drawGoalNode(context, text, railX, railY, "AI", !booleanValue(targetLine(target, "AI Disabled")), 0xFF2DA700);
        drawGoalNode(context, text, railX, railY + step, "NAV", !booleanValue(targetLine(target, "Navigation Idle")), 0xFF8FC5FF);
        drawGoalNode(context, text, railX, railY + step * 2, "PATH", booleanValue(targetLine(target, "Path Active")), 0xFFB47CFF);
        drawGoalNode(context, text, railX, railY + step * 3, "TGT", !targetLine(target, "Target").equals("none") && !targetLine(target, "Target").equals("unknown"), 0xFFE06A21);
        int rowX = x + 68;
        int rowY = y + 21;
        int rowW = Math.max(42, w - 76);
        miniBarRow(context, text, rowX, rowY, rowW, "path", firstKnown(targetLine(target, "Path Node"), targetLine(target, "Path Length")), targetRatio(target, "Path Length", 32.0D), 0xFFB47CFF);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "target", targetLine(target, "Target"), !targetLine(target, "Target").equals("none") && !targetLine(target, "Target").equals("unknown") ? 1.0D : 0.0D, 0xFFE06A21);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "nav", trim(targetLine(target, "Navigation Type"), 18), booleanValue(targetLine(target, "Navigation Idle")) ? 0.15D : 0.85D, 0xFF8FC5FF);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "brain", trim(targetLine(target, "Brain"), 18), targetLine(target, "Brain").equals("unknown") ? 0.0D : 1.0D, 0xFF8FC5FF);
        if (rowY + 20 < y + h) {
            drawText(context, text, trimToWidth(text, "goals: " + targetLine(target, "Goal Visibility"), w - 16), x + 8, y + h - 11, 0xFF8FA0B8);
        }
    }


    private static void targetEntityThreatCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3TargetSnapshot target) {
        panel(context, x, y, w, h, 0xFFE06A21);
        drawText(context, text, "Intent / Threat", x + 8, y + 7, 0xFFE6EDF5);
        int cx = x + 36;
        int cy = y + 54;
        int r = Math.max(14, Math.min(22, h / 4));
        double threat = dangerRatio(firstKnown(targetLine(target, "Threat Level"), target.danger()));
        drawThreatRing(context, cx, cy, r, threat, entityThreatColor(target), firstKnown(targetLine(target, "Intent"), target.danger()));
        int rowX = x + 72;
        int rowW = Math.max(32, w - 80);
        int rowY = y + 21;
        miniBarRow(context, text, rowX, rowY, rowW, "intent", firstKnown(targetLine(target, "Intent"), "observe"), threat, 0xFFE06A21);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "threat", firstKnown(targetLine(target, "Threat Level"), target.danger()), threat, entityThreatColor(target));
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "target", firstKnown(targetLine(target, "Target"), "none"), !targetLine(target, "Target").equals("none") && !targetLine(target, "Target").equals("unknown") ? 1.0D : 0.0D, 0xFFB47CFF);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "range", targetLine(target, "Follow Range"), targetRatio(target, "Follow Range", 64.0D), 0xFF8FC5FF);
        if (rowY + 20 < y + h) {
            drawText(context, text, trimToWidth(text, compactValues(targetLine(target, "Navigation Type"), targetLine(target, "Move Control"), targetLine(target, "Look Control")), w - 16), x + 8, y + h - 11, 0xFF8FA0B8);
        }
    }

    private static void targetMobBrainCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3TargetSnapshot target) {
        panel(context, x, y, w, h, 0xFFE06A21);
        drawText(context, text, "Mob Brain", x + 8, y + 7, 0xFFE6EDF5);
        int rowY = y + 21;
        miniBarRow(context, text, x + 8, rowY, w - 16, "effects", targetLine(target, "Effects"), targetRatio(target, "Effects", 12.0D), 0xFFB47CFF);
        rowY += 10;
        miniBarRow(context, text, x + 8, rowY, w - 16, "fire", targetLine(target, "Fire"), targetRatio(target, "Fire", 200.0D), 0xFFE06A21);
        rowY += 10;
        miniBarRow(context, text, x + 8, rowY, w - 16, "frozen", targetLine(target, "Frozen"), targetRatio(target, "Frozen", 140.0D), 0xFF8FC5FF);
        rowY += 10;
        if (rowY + 8 < y + h) {
            drawText(context, text, trimToWidth(text, "target " + firstKnown(targetLine(target, "Target"), "none"), w - 16), x + 8, rowY, 0xFFB8C4D2);
        }
    }

    private static void targetItemStackCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3TargetSnapshot target) {
        panel(context, x, y, w, h, target.accentColor());
        drawText(context, text, "Item Model", x + 8, y + 7, 0xFFE6EDF5);
        drawMinecraftTargetIcon(context, x + w - 18, y + 5, target);
        int modelX = x + 34;
        int modelSize = Math.max(15, Math.min(24, h / 4));
        int modelY = safeSidePanelBoxBaseY(y, h, modelSize, 32, 20);
        drawItemDataModel(context, modelX, modelY, modelSize, target);
        int rowX = x + 72;
        int rowY = y + 21;
        int rowW = Math.max(40, w - 80);
        miniBarRow(context, text, rowX, rowY, rowW, "count", targetLine(target, "Count"), targetRatio(target, "Count", 64.0D), 0xFF8FC5FF);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "durability", targetLine(target, "Durability"), parseFraction(targetLine(target, "Durability")), 0xFF2DA700);
        rowY += 10;
        miniBarRow(context, text, rowX, rowY, rowW, "enchants", targetLine(target, "Enchantments"), targetRatio(target, "Enchantments", 8.0D), 0xFFB47CFF);
        rowY += 10;
        if (rowY + 8 < y + h) {
            miniBarRow(context, text, rowX, rowY, rowW, "age", firstKnown(targetLine(target, "Age"), targetLine(target, "Pickup Delay")), targetRatio(target, "Age", 6000.0D), 0xFFC8A24A);
        }
    }


    private static void drawEntityDataModel(DrawContext context, int cx, int baseY, int scale, F3TargetSnapshot target, double width, double entityHeight) {
        double normalizedWidth = clampDouble(width / 1.8D, 0.18D, 1.0D);
        double normalizedHeight = clampDouble(entityHeight / 2.8D, 0.16D, 1.25D);
        int color = target.accentColor();
        drawHeadProjectedBox(context, cx, baseY, scale, normalizedWidth, normalizedHeight, normalizedWidth, color, 0xFF8FC5FF);
        double eyeHeight = parseDouble(targetLine(target, "Eye Height"), entityHeight * 0.85D);
        double eyeRatio = clampRatio(eyeHeight / Math.max(0.1D, entityHeight));
        double eyeY = normalizedHeight * eyeRatio;
        drawProjectedSegment(context, cx, baseY, scale, -normalizedWidth * 0.45D, eyeY, normalizedWidth * 0.5D, normalizedWidth * 0.45D, eyeY, normalizedWidth * 0.5D, 0xFF8FC5FF);
        double health = parseFraction(targetLine(target, "Health"));
        if (health > 0.0D) {
            drawProjectedBarOnEdge(context, cx, baseY, scale, -normalizedWidth * 0.62D, 0.02D, -normalizedWidth * 0.58D, -normalizedWidth * 0.62D, normalizedHeight * health, -normalizedWidth * 0.58D, health > 0.5D ? 0xFF2DA700 : health > 0.25D ? 0xFFE3B735 : 0xFFE06A21);
        }
        double armor = targetRatio(target, "Armor", 30.0D);
        if (armor > 0.0D) {
            drawProjectedBarOnEdge(context, cx, baseY, scale, normalizedWidth * 0.58D, 0.02D, -normalizedWidth * 0.58D, normalizedWidth * 0.58D, normalizedHeight * armor, -normalizedWidth * 0.58D, 0xFF8FC5FF);
        }
        double speed = targetRatio(target, "Speed Magnitude", 1.0D);
        if (speed > 0.0D) {
            ProjectedPoint anchor = projectModelPoint(normalizedWidth * 0.55D, normalizedHeight * 0.18D, normalizedWidth * 0.55D, scale);
            int len = Math.max(4, (int) Math.round(scale * clampRatio(speed) * 0.55D));
            drawArrow(context, cx + anchor.x(), baseY + anchor.y(), cx + anchor.x() + len, baseY + anchor.y() - Math.max(2, len / 2), 0xFF7FC8C2);
        }
        if (booleanValue(targetLine(target, "Fire"))) {
            drawProjectedMarker(context, cx, baseY, scale, 0.0D, normalizedHeight + 0.08D, 0.0D, 0xFFE06A21, 2);
            drawProjectedMarker(context, cx, baseY, scale, -normalizedWidth * 0.25D, normalizedHeight + 0.02D, normalizedWidth * 0.12D, 0xFFE06A21, 1);
            drawProjectedMarker(context, cx, baseY, scale, normalizedWidth * 0.25D, normalizedHeight + 0.02D, -normalizedWidth * 0.12D, 0xFFE06A21, 1);
        }
        if (booleanValue(targetLine(target, "Frozen"))) {
            drawProjectedSegment(context, cx, baseY, scale, -normalizedWidth * 0.35D, 0.06D, normalizedWidth * 0.2D, normalizedWidth * 0.35D, 0.06D, normalizedWidth * 0.2D, 0xFF8FC5FF);
        }
        String pose = safeValue(targetLine(target, "Pose")).toLowerCase(Locale.ROOT);
        if (pose.contains("sneak") || pose.contains("crouch")) {
            drawProjectedMarker(context, cx, baseY, scale, -normalizedWidth * 0.7D, normalizedHeight * 0.45D, 0.0D, 0xFFB47CFF, 2);
        }
        if (pose.contains("swim") || pose.contains("fall")) {
            drawProjectedMarker(context, cx, baseY, scale, normalizedWidth * 0.7D, normalizedHeight * 0.45D, 0.0D, 0xFF55C4FF, 2);
        }
    }

    private static void drawItemDataModel(DrawContext context, int cx, int baseY, int scale, F3TargetSnapshot target) {
        double count = parseDouble(targetLine(target, "Count"), 1.0D);
        double durability = parseFraction(targetLine(target, "Durability"));
        if (durability <= 0.0D && !safeValue(targetLine(target, "Durability")).equals("unknown")) {
            durability = 0.04D;
        }
        double enchants = targetRatio(target, "Enchantments", 8.0D);
        int layers = clamp((int) Math.ceil(Math.max(1.0D, count) / 16.0D), 1, 4);
        double itemWidth = 0.75D;
        double itemHeight = 0.16D;
        double itemDepth = 0.75D;
        for (int i = layers - 1; i >= 0; i--) {
            double lift = i * 0.08D;
            double offset = (i - (layers - 1) / 2.0D) * 0.06D;
            int color = i == layers - 1 ? target.accentColor() : withAlpha(target.accentColor(), 120 + i * 28);
            int layerCx = cx + (int) Math.round(offset * scale * 1.6D);
            int layerBaseY = baseY - (int) Math.round(lift * scale * 1.2D);
            drawHeadProjectedBox(context, layerCx, layerBaseY, Math.max(8, scale - 2), itemWidth, itemHeight, itemDepth, color, 0xFF8FC5FF);
            if (i < layers - 1) {
                drawProjectedMarker(context, cx, baseY, Math.max(8, scale - 2), offset, lift + itemHeight + 0.02D, -offset, withAlpha(color, 180), 1);
            }
        }
        if (durability > 0.0D) {
            drawProjectedBarOnEdge(context, cx, baseY, Math.max(8, scale - 2), -itemWidth * 0.45D, 0.03D, itemDepth * 0.52D, -itemWidth * 0.45D + itemWidth * 0.9D * durability, 0.03D, itemDepth * 0.52D, durability > 0.55D ? 0xFF2DA700 : durability > 0.25D ? 0xFFE3B735 : 0xFFE06A21);
        }
        if (enchants > 0.0D) {
            int glow = withAlpha(0xFFB47CFF, 120 + (int) Math.round(90.0D * clampRatio(enchants)));
            drawProjectedMarker(context, cx, baseY, Math.max(8, scale - 2), -itemWidth * 0.5D, itemHeight + 0.04D, -itemDepth * 0.5D, glow, 2);
            drawProjectedMarker(context, cx, baseY, Math.max(8, scale - 2), itemWidth * 0.5D, itemHeight + 0.04D, itemDepth * 0.5D, glow, 2);
        }
        double countRatio = clampRatio(count / 64.0D);
        drawProjectedBarOnEdge(context, cx, baseY, Math.max(8, scale - 2), itemWidth * 0.58D, 0.03D, -itemDepth * 0.58D, itemWidth * 0.58D, itemHeight + countRatio * 0.35D, -itemDepth * 0.58D, 0xFF8FC5FF);
    }

    private static void drawItemDiamond(DrawContext context, int cx, int cy, int size, int color) {
        int w = Math.max(5, size / 2);
        int h = Math.max(5, size / 3);
        fillQuad(context, cx, cy - h, cx + w, cy, cx, cy + h, cx - w, cy, withAlpha(color, 160));
        drawLine(context, cx, cy - h, cx + w, cy, softText(color));
        drawLine(context, cx + w, cy, cx, cy + h, color);
        drawLine(context, cx, cy + h, cx - w, cy, withAlpha(color, 210));
        drawLine(context, cx - w, cy, cx, cy - h, withAlpha(softText(color), 190));
        context.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFE6EDF5);
    }

    private static double entityPoseRatio(F3TargetSnapshot target) {
        String pose = safeValue(targetLine(target, "Pose")).toLowerCase(Locale.ROOT);
        if (pose.contains("swim") || pose.contains("fall")) {
            return 1.0D;
        }
        if (pose.contains("sneak") || pose.contains("crouch")) {
            return 0.66D;
        }
        if (booleanValue(targetLine(target, "On Ground"))) {
            return 0.35D;
        }
        return 0.12D;
    }

    private static void miniBarRow(DrawContext context, TextRenderer text, int x, int y, int w, String label, String value, double ratio, int color) {
        String safeLabel = safeValue(label);
        String safeValue = safeValue(value);
        int valueMaxW = Math.max(18, Math.min(Math.max(18, w / 2), w - 38));
        String shownValue = trimToWidth(text, safeValue, valueMaxW);
        int valueW = text.getWidth(shownValue);
        int labelW = Math.max(12, w - valueW - 6);
        drawText(context, text, trimToWidth(text, safeLabel, labelW), x, y, 0xFF8FA0B8);
        drawText(context, text, shownValue, x + w - valueW, y, color);
        drawMeter(context, x, y + 8, w, 2, ratio, color);
    }

    private static List<PieSlice> debugPieSlices(F3Snapshot snapshot) {
        PerformanceSnapshot perf = snapshot.performance();
        double renderer = Math.max(0.04D, clampRatio(perf.frameTimeMs() / 55.0D + parseDouble(value(snapshot, "render", "Render Distance"), perf.renderDistance()) / 96.0D + parseDouble(value(snapshot, "render", "Resource Packs"), 0.0D) / 180.0D));
        double tick = Math.max(0.04D, clampRatio(perf.frameTimeMs() / 80.0D + perf.entityCount() / 900.0D));
        double level = Math.max(0.04D, clampRatio(perf.chunkStress() + parseDouble(firstKnown(value(snapshot, "chunk", "Chunk Entities"), value(snapshot, "chunk", "Client Entities")), 0.0D) / 500.0D + parseDouble(value(snapshot, "chunk", "Block Entities"), 0.0D) / 280.0D));
        double memory = Math.max(0.04D, clampRatio(perf.memoryPressure()));
        double network = Math.max(0.03D, clampRatio(parseDouble(value(snapshot, "network", "Ping"), 0.0D) / 350.0D + parseDouble(value(snapshot, "network", "Players Listed"), 0.0D) / 250.0D));
        double terrain = Math.max(0.03D, averageTerrainSignal(snapshot));
        double target = Math.max(0.03D, clampRatio((snapshot.target().tags().size() + snapshot.target().lines().size() + snapshot.target().actions().size()) / 180.0D));
        double entities = Math.max(0.03D, clampRatio(parseDouble(firstKnown(value(snapshot, "chunk", "Chunk Entities"), value(snapshot, "chunk", "Client Entities")), perf.entityCount()) / 300.0D));
        double blockEntities = Math.max(0.03D, clampRatio(parseDouble(value(snapshot, "chunk", "Block Entities"), 0.0D) / 128.0D));
        double light = Math.max(0.03D, Math.max(parseRatio(value(snapshot, "world", "Block Light"), 15.0D), parseRatio(value(snapshot, "world", "Sky Light"), 15.0D)));
        double motion = Math.max(0.03D, clampRatio(vectorMagnitude(value(snapshot, "player", "Velocity")) / 0.75D));
        double sections = Math.max(0.03D, parseRatio(value(snapshot, "chunk", "Section Count"), 24.0D));
        List<PieSlice> slices = new ArrayList<>();
        slices.add(new PieSlice("gameRenderer", renderer, 0xFFFF5555));
        slices.add(new PieSlice("tick", tick, 0xFF55FF55));
        slices.add(new PieSlice("level", level, 0xFFFFFF55));
        slices.add(new PieSlice("memory", memory, 0xFFFF55FF));
        slices.add(new PieSlice("network", network, 0xFF55FFFF));
        slices.add(new PieSlice("terrain", terrain, 0xFFFFAA00));
        slices.add(new PieSlice("target", target, 0xFFAAAAAA));
        slices.add(new PieSlice("entities", entities, 0xFFE06A21));
        slices.add(new PieSlice("blockEntities", blockEntities, 0xFFB47CFF));
        slices.add(new PieSlice("light", light, 0xFFE3B735));
        slices.add(new PieSlice("motion", motion, 0xFF7FC8C2));
        slices.add(new PieSlice("sections", sections, 0xFFC8A24A));
        return normalizedSlices(slices);
    }

    private static String vanillaPieIndex(int index) {
        return "[" + (index + 1) + "]";
    }

    private static List<PieSlice> normalizedSlices(List<PieSlice> slices) {
        double total = 0.0D;
        for (PieSlice slice : slices) {
            total += Math.max(0.0D, slice.ratio());
        }
        if (total <= 0.0D) {
            return List.of(new PieSlice("idle", 1.0D, 0xFF8D8D8D));
        }
        List<PieSlice> normalized = new ArrayList<>();
        for (PieSlice slice : slices) {
            normalized.add(new PieSlice(slice.label(), Math.max(0.0D, slice.ratio()) / total, slice.color()));
        }
        normalized.sort((a, b) -> Double.compare(b.ratio(), a.ratio()));
        return normalized;
    }

    private static void drawDebugPie(DrawContext context, int cx, int cy, int radius, List<PieSlice> slices) {
        int inner = Math.max(5, radius / 3);
        for (int py = cy - radius; py <= cy + radius; py++) {
            for (int px = cx - radius; px <= cx + radius; px++) {
                int dx = px - cx;
                int dy = py - cy;
                int d2 = dx * dx + dy * dy;
                if (d2 > radius * radius || d2 < inner * inner) {
                    continue;
                }
                double angle = Math.atan2(dy, dx) + Math.PI / 2.0D;
                if (angle < 0.0D) {
                    angle += Math.PI * 2.0D;
                }
                double point = angle / (Math.PI * 2.0D);
                int base = pieColor(point, slices);
                int edge = d2 > (radius - 2) * (radius - 2) || d2 < (inner + 1) * (inner + 1) ? withAlpha(softText(base), 230) : base;
                context.fill(px, py, px + 1, py + 1, edge);
            }
        }
        double cursor = 0.0D;
        for (PieSlice slice : slices) {
            drawPieSeparator(context, cx, cy, inner, radius, cursor, 0xCC050609);
            cursor += slice.ratio();
        }
        drawPieSeparator(context, cx, cy, inner, radius, 0.0D, 0xFFE6EDF5);
    }

    private static void drawPieSeparator(DrawContext context, int cx, int cy, int inner, int radius, double ratio, int color) {
        double angle = ratio * Math.PI * 2.0D - Math.PI / 2.0D;
        int x1 = cx + (int) Math.round(Math.cos(angle) * inner);
        int y1 = cy + (int) Math.round(Math.sin(angle) * inner);
        int x2 = cx + (int) Math.round(Math.cos(angle) * radius);
        int y2 = cy + (int) Math.round(Math.sin(angle) * radius);
        drawLine(context, x1, y1, x2, y2, color);
    }

    private static void drawPiePressureStrip(DrawContext context, TextRenderer text, List<PieSlice> slices, int x, int y, int w) {
        context.fill(x, y, x + w, y + 12, PANEL_BG_SOFT);
        int cursor = x;
        int end = x + w;
        for (int i = 0; i < slices.size(); i++) {
            PieSlice slice = slices.get(i);
            int next = i == slices.size() - 1 ? end : Math.min(end, cursor + Math.max(2, (int) Math.round(w * slice.ratio())));
            context.fill(cursor, y, next, y + 4, slice.color());
            cursor = next;
        }
        int limit = Math.min(3, slices.size());
        int labelW = Math.max(30, (w - 8) / Math.max(1, limit));
        for (int i = 0; i < limit; i++) {
            PieSlice slice = slices.get(i);
            String label = trimToWidth(text, slice.label().replace(" est", "") + " " + percent(slice.ratio()), labelW - 3);
            drawText(context, text, label, x + 3 + i * labelW, y + 5, i == 0 ? 0xFFE6EDF5 : softText(slice.color()));
        }
    }

    private static void drawPieHub(DrawContext context, TextRenderer text, int cx, int cy, int radius, PieSlice top) {
        int inner = Math.max(5, radius / 3);
        context.fill(cx - inner + 1, cy - inner + 1, cx + inner, cy + inner, 0x99050609);
        String value = String.valueOf(Math.round(top.ratio() * 100.0D));
        drawText(context, text, value, cx - text.getWidth(value) / 2, cy - 4, top.color());
    }

    private static int pieColor(double point, List<PieSlice> slices) {
        double cursor = 0.0D;
        for (PieSlice slice : slices) {
            cursor += slice.ratio();
            if (point <= cursor) {
                return slice.color();
            }
        }
        return slices.isEmpty() ? 0xFF8D8D8D : slices.get(slices.size() - 1).color();
    }

    private static int graphRailWidth(int width) {
        if (width < 720) {
            return 142;
        }
        if (width < 1000) {
            return 168;
        }
        return 188;
    }

    private static int rightColumnEdge(int width, F3Mode mode) {
        return modeShowsGraphRail(mode) ? width - graphRailWidth(width) - 14 : width - 8;
    }

    private static int rightPanelMaxWidth(int width, F3Mode mode) {
        if (modeShowsGraphRail(mode)) {
            return Math.max(176, Math.min(250, width / 3));
        }
        return Math.max(188, Math.min(mode.developerDataEnabled() || mode == F3Mode.FULL ? 430 : 386, width / 2 - 18));
    }


    private static List<F3DataLine> compactRightRows(F3Snapshot snapshot, List<F3DataLine> vanilla, F3Mode mode) {
        PerformanceSnapshot perf = snapshot.performance();
        List<F3DataLine> rows = new ArrayList<>();
        rows.add(F3DataLine.state("Performance", perf.fps() + " fps | " + perf.frameTimeMs() + " ms | low " + (int) perf.onePercentLowFps(), "performance", fpsColor(perf.fps()), "FPS, current frame time, and 1% low in one row."));
        rows.add(F3DataLine.state("Heap", perf.usedMemoryMb() + "/" + perf.maxMemoryMb() + " MB | " + percent(perf.memoryPressure()), pressureState(perf.memoryPressure()), pressureColor(perf.memoryPressure()), "Heap memory compacted from memory and heap rows."));
        rows.add(F3DataLine.state("Client Load", perf.entityCount() + " ent | chunk " + percent(perf.chunkStress()) + " | " + perf.renderDistance() + "/" + perf.simulationDistance(), "world", pressureColor(Math.max(perf.chunkStress(), perf.entityCount() / 300.0D)), "Client-loaded entities, estimated chunk pressure, render distance, and simulation distance."));
        addCompactRow(rows, "Instance", compactValues(value(snapshot, "system", "Minecraft"), suffixKnown(value(snapshot, "system", "Mods"), " mods"), value(snapshot, "system", "Uptime")), 0xFF8FC5FF);
        addCompactRow(rows, "Runtime", compactValues(prefixKnown("Java ", value(snapshot, "system", "Java")), value(snapshot, "system", "JVM"), suffixKnown(value(snapshot, "system", "CPU Threads"), " threads")), 0xFF8FC5FF);
        addCompactRow(rows, "System", compactValues(value(snapshot, "system", "OS"), value(snapshot, "system", "Fabric Env")), 0xFF8FC5FF);
        addCompactRow(rows, "Renderer", compactValues(value(snapshot, "render", "Graphics"), value(snapshot, "render", "Window"), suffixKnown(value(snapshot, "render", "Resource Packs"), " packs")), 0xFFB47CFF);
        addCompactRow(rows, "Visuals", compactValues(prefixKnown("clouds ", value(snapshot, "render", "Clouds")), prefixKnown("particles ", value(snapshot, "render", "Particles")), prefixKnown("mipmap ", value(snapshot, "render", "Mipmap"))), 0xFFB47CFF);
        addCompactRow(rows, "Toggles", compactValues(prefixKnown("shadows ", value(snapshot, "render", "Entity Shadows")), prefixKnown("vsync ", value(snapshot, "render", "VSync")), prefixKnown("shader ", value(snapshot, "render", "Shader Mod"))), 0xFFB47CFF);
        addCompactRow(rows, "Network", compactValues(value(snapshot, "network", "Connection"), value(snapshot, "network", "Ping"), suffixKnown(value(snapshot, "network", "Players Listed"), " players")), 0xFF8FC5FF);
        if (mode == F3Mode.PLAYER || mode == F3Mode.WORLD || mode == F3Mode.TARGET) {
            return rows;
        }
        if (mode == F3Mode.CREATOR) {
            appendDeveloperSectionRows(rows, "Target", sectionLines(snapshot, "target"), snapshot.target().accentColor());
            return rows;
        }
        if (developerDataMode(mode)) {
            appendDeveloperSectionRows(rows, "System", sectionLines(snapshot, "system"), 0xFFE6EDF5);
            appendDeveloperSectionRows(rows, "Renderer", sectionLines(snapshot, "render"), 0xFFB47CFF);
            appendDeveloperSectionRows(rows, "Network", sectionLines(snapshot, "network"), 0xFF8FC5FF);
        }
        if (!vanilla.isEmpty()) {
            rows.add(new F3DataLine("", "", "divider", 0xFF8FC5FF, ""));
            rows.add(F3DataLine.state("Vanilla F3 Right", vanilla.size() + " rows", "source", 0xFF8FC5FF, "Minecraft's original right-side F3 debug text, captured before Koil renders the redesigned overlay."));
            rows.addAll(vanilla);
        }
        return rows;
    }

    private static void appendDeveloperSectionRows(List<F3DataLine> rows, String title, List<F3DataLine> lines, int color) {
        if (lines.isEmpty()) {
            return;
        }
        rows.add(new F3DataLine("", "", "divider", color, ""));
        rows.add(F3DataLine.state(title, lines.size() + " rows", "header", color, "Complete developer section with no filtered labels."));
        for (F3DataLine line : lines) {
            rows.add(line);
        }
    }

    private static void addCompactRow(List<F3DataLine> rows, String label, String value, int color) {
        if (!value.isBlank() && !value.equals("unknown") && !value.equals("unknown | unknown") && !value.equals("unknown | unknown | unknown")) {
            rows.add(F3DataLine.state(label, value, "compact", color, "Compacted from multiple F3 lines."));
        }
    }

    private static int rightSummaryMeterHeight(F3Mode mode) {
        if (mode == F3Mode.SIMPLE) {
            return 0;
        }
        return modeShowsGraphRail(mode) ? 39 : 25;
    }

    private static void drawRightSummaryMeters(DrawContext context, TextRenderer text, int x, int y, int w, F3Snapshot snapshot, F3Mode mode) {
        PerformanceSnapshot perf = snapshot.performance();
        int gap = 4;
        int each = Math.max(32, (w - gap * 2) / 3);
        miniMeter(context, text, x, y, each, "FPS", perf.fps() + "", Math.min(1.0D, perf.fps() / 120.0D), fpsColor(perf.fps()));
        miniMeter(context, text, x + each + gap, y, each, "MEM", percent(perf.memoryPressure()), perf.memoryPressure(), pressureColor(perf.memoryPressure()));
        double worldPressure = Math.max(perf.chunkStress(), perf.entityCount() / 300.0D);
        miniMeter(context, text, x + (each + gap) * 2, y, each, "WORLD", percent(worldPressure), worldPressure, pressureColor(worldPressure));
        if (modeShowsGraphRail(mode)) {
            drawTinySpark(context, x, y + 20, w, 11, Chart.MEMORY, pressureColor(perf.memoryPressure()));
        }
    }

    private static void drawLeftSnapshotStrip(DrawContext context, TextRenderer text, F3Snapshot snapshot, int x, int y, int w) {
        double health = parseFraction(value(snapshot, "player", "Health"));
        double hunger = parseRatio(value(snapshot, "player", "Hunger"), 20.0D);
        double velocity = clampRatio(vectorMagnitude(value(snapshot, "player", "Velocity")) / 0.75D);
        int gap = 4;
        int each = Math.max(24, (w - gap * 2) / 3);
        miniMeter(context, text, x, y, each, "HP", percent(health), health, health > 0.5D ? 0xFF2DA700 : 0xFFE06A21);
        miniMeter(context, text, x + each + gap, y, each, "FOOD", percent(hunger), hunger, 0xFF8FC5FF);
        miniMeter(context, text, x + (each + gap) * 2, y, each, "VEL", formatRatio(velocity), velocity, 0xFF7FC8C2);
    }
    private static int leftVisualStripHeight(F3Mode mode) {
        if (mode == F3Mode.SIMPLE) {
            return 0;
        }
        if (mode == F3Mode.NORMAL || mode == F3Mode.WORLD) {
            return 56;
        }
        if (mode == F3Mode.GRAPHS || mode == F3Mode.INSPECTOR || mode.developerDataEnabled() || mode == F3Mode.FULL) {
            return 78;
        }
        return 64;
    }

    private static void drawLeftVisualStrip(DrawContext context, TextRenderer text, F3Snapshot snapshot, int x, int y, int w, int h, F3Mode mode) {
        if (h <= 22 || w < 138) {
            drawLeftSnapshotStrip(context, text, snapshot, x, y, w);
            return;
        }
        int compassH = h >= 70 ? 22 : 18;
        drawLeftCompassRibbon(context, text, snapshot, x, y, w, compassH);
        int moduleY = y + compassH + 4;
        int moduleH = Math.max(18, h - compassH - 4);
        int gap = 4;
        if (h >= 70 && w >= 210) {
            int modelW = Math.max(42, Math.min(64, w / 4));
            int remaining = Math.max(48, w - modelW - gap);
            int each = Math.max(38, (remaining - gap * 2) / 3);
            drawLeftVitalsConstellation(context, text, snapshot, x, moduleY, each, moduleH);
            drawLeftAltitudeStrata(context, text, snapshot, x + each + gap, moduleY, each, moduleH);
            drawLeftChunkTile(context, text, snapshot, x + (each + gap) * 2, moduleY, each, moduleH);
            drawLeftSpatialModel(context, text, snapshot, x + remaining + gap, moduleY, modelW, moduleH);
        } else {
            int each = Math.max(42, (w - gap * 2) / 3);
            drawLeftVitalsConstellation(context, text, snapshot, x, moduleY, each, moduleH);
            drawLeftAltitudeStrata(context, text, snapshot, x + each + gap, moduleY, each, moduleH);
            drawLeftChunkTile(context, text, snapshot, x + (each + gap) * 2, moduleY, Math.max(32, w - (each + gap) * 2), moduleH);
        }
    }

    private static void drawLeftCompassRibbon(DrawContext context, TextRenderer text, F3Snapshot snapshot, int x, int y, int w, int h) {
        String facing = value(snapshot, "player", "Facing");
        double yaw = yawRatio(facing) * 360.0D;
        context.fill(x, y, x + w, y + h, PANEL_BG_SOFT);
        context.fill(x, y, x + w, y + 1, 0x448FC5FF);
        context.fill(x, y + h - 1, x + w, y + h, 0x337FC8C2);
        int center = x + w / 2;
        int window = Math.max(96, w / 2);
        String[] labels = new String[]{"S", "W", "N", "E", "S"};
        int[] angles = new int[]{0, 90, 180, 270, 360};
        for (int tick = -180; tick <= 180; tick += 15) {
            int px = center + (int) Math.round(tick / 180.0D * window);
            if (px >= x + 1 && px <= x + w - 2) {
                boolean major = tick % 90 == 0;
                boolean mid = tick % 45 == 0;
                int alpha = major ? 130 : mid ? 92 : 48;
                context.fill(px, y + 1, px + 1, y + h - 1, withAlpha(0xFF8FA0B8, alpha));
                if (!major) {
                    context.fill(px - 1, y + h / 2, px + 2, y + h / 2 + 1, withAlpha(0xFF8FA0B8, alpha + 18));
                }
            }
        }
        for (int i = 0; i < labels.length; i++) {
            double delta = normalizeDegrees(angles[i] - yaw);
            if (delta < -180.0D) {
                delta += 360.0D;
            }
            if (delta > 180.0D) {
                delta -= 360.0D;
            }
            int px = center + (int) Math.round(delta / 180.0D * window);
            if (px >= x + 1 && px <= x + w - 2) {
                int color = labels[i].equals("N") ? 0xFFFF5555 : labels[i].equals("E") ? 0xFF55FF55 : labels[i].equals("W") ? 0xFF5555FF : 0xFFE3B735;
                context.fill(px, y, px + 1, y + h, withAlpha(color, 195));
                context.fill(px - 1, y, px + 2, y + 2, color);
                context.fill(px - 1, y + h - 2, px + 2, y + h, color);
                drawCompassCardinalLabel(context, text, labels[i], px, x, x + w, y + h - 10, color);
            }
        }
        drawArrow(context, center, y + h - 2, center, y + 2, 0xFFE6EDF5);
        drawMinecraftCompassNeedle(context, center, y + h / 2, 0xFFE6EDF5);
        String label = trimToWidth(text, yawLabel(facing) + " " + cardinalLabel(yaw), Math.max(20, w / 3));
        int labelW = text.getWidth(label);
        context.fill(x + 2, y + 3, x + Math.min(w - 2, labelW + 8), y + 13, 0xAA050609);
        drawText(context, text, label, x + 4, y + 4, 0xFFB8C4D2);
    }

    private static void drawCompassCardinalLabel(DrawContext context, TextRenderer text, String label, int centerX, int minX, int maxX, int y, int color) {
        int textW = text.getWidth(label);
        int labelX = clamp(centerX - textW / 2, minX + 3, maxX - textW - 3);
        context.fill(labelX - 2, y - 1, labelX + textW + 2, y + 9, 0xCC050609);
        context.fill(labelX - 2, y + 8, labelX + textW + 2, y + 9, withAlpha(color, 150));
        drawText(context, text, label, labelX, y, color);
    }

    private static void drawLeftVitalsConstellation(DrawContext context, TextRenderer text, F3Snapshot snapshot, int x, int y, int w, int h) {
        context.fill(x, y, x + w, y + h, 0x44050609);
        double hp = parseFraction(value(snapshot, "player", "Health"));
        double food = parseRatio(value(snapshot, "player", "Hunger"), 20.0D);
        double speed = clampRatio(vectorMagnitude(value(snapshot, "player", "Velocity")) / 0.75D);
        int cx = x + w / 2;
        int top = y + 8;
        int left = x + Math.max(7, w / 5);
        int right = x + w - Math.max(7, w / 5);
        int bottom = y + h - 7;
        drawLine(context, cx, top, left, bottom, withAlpha(vitalColor(hp, 0xFF2DA700), 120));
        drawLine(context, left, bottom, right, bottom, withAlpha(0xFFE3B735, 120));
        drawLine(context, right, bottom, cx, top, withAlpha(0xFF8FC5FF, 120));
        drawSignalNode(context, cx, top, hp, vitalColor(hp, 0xFF2DA700));
        drawSignalNode(context, left, bottom, food, food < 0.35D ? 0xFFE06A21 : 0xFF8FC5FF);
        drawSignalNode(context, right, bottom, speed, 0xFF8FC5FF);
        if (w > 56) {
            drawText(context, text, "body", x + 3, y + 2, 0xFF8FA0B8);
            drawText(context, text, percent(Math.max(hp, Math.max(food, speed))), x + w - Math.min(w - 4, text.getWidth(percent(Math.max(hp, Math.max(food, speed)))) + 3), y + 2, 0xFFE6EDF5);
        }
    }

    private static void drawLeftAltitudeStrata(DrawContext context, TextRenderer text, F3Snapshot snapshot, int x, int y, int w, int h) {
        context.fill(x, y, x + w, y + h, 0x44050609);
        double[] coords = firstThreeNumbers(value(snapshot, "player", "XYZ"));
        double[] bottomTop = firstThreeNumbers(value(snapshot, "world", "Bottom/Top Y"));
        double bottom = bottomTop[0] == 0.0D && bottomTop[1] == 0.0D ? -64.0D : bottomTop[0];
        double top = bottomTop[1] == 0.0D ? 320.0D : bottomTop[1];
        double yRatio = clampRatio((coords[1] - bottom) / Math.max(1.0D, top - bottom));
        int barX = x + Math.max(7, w / 2 - 4);
        int barW = Math.min(8, Math.max(4, w / 6));
        int barTop = y + 5;
        int barBottom = y + h - 5;
        context.fill(barX, barTop, barX + barW, barBottom, 0x662A3038);
        int seaY = barBottom - (int) Math.round((barBottom - barTop) * clampRatio((63.0D - bottom) / Math.max(1.0D, top - bottom)));
        context.fill(x + 3, seaY, x + w - 3, seaY + 1, 0x668FC5FF);
        int py = barBottom - (int) Math.round((barBottom - barTop) * yRatio);
        context.fill(barX - 3, py - 1, barX + barW + 4, py + 2, altitudeColor(yRatio));
        int section = (int) Math.floor(coords[1] / 16.0D);
        for (int i = 1; i < 4; i++) {
            int ly = barTop + (barBottom - barTop) * i / 4;
            context.fill(barX - 2, ly, barX + barW + 2, ly + 1, 0x332A3038);
        }
        if (w > 48) {
            drawText(context, text, "Y", x + 3, y + 2, 0xFF8FA0B8);
            drawText(context, text, trimToWidth(text, String.valueOf(Math.round(coords[1])), w - 6), x + 3, y + h - 10, altitudeColor(yRatio));
            drawText(context, text, trimToWidth(text, "s" + section, Math.max(12, w / 2)), x + w - Math.min(w / 2, text.getWidth("s" + section) + 3), y + 2, 0xFFB8C4D2);
        }
    }

    private static void drawLeftChunkTile(DrawContext context, TextRenderer text, F3Snapshot snapshot, int x, int y, int w, int h) {
        context.fill(x, y, x + w, y + h, 0x44050609);
        double[] local = firstThreeNumbers(value(snapshot, "chunk", "Local Block"));
        boolean slime = safeValue(value(snapshot, "chunk", "Slime Chunk")).equalsIgnoreCase("true");
        int size = Math.max(16, Math.min(Math.min(w - 8, h - 8), 28));
        int gx = x + Math.max(3, (w - size) / 2);
        int gy = y + Math.max(4, (h - size) / 2);
        context.fill(gx - 1, gy - 1, gx + size + 1, gy + size + 1, slime ? 0x882DA700 : 0x662A3038);
        int cell = Math.max(1, size / 16);
        for (int i = 4; i < 16; i += 4) {
            int px = gx + i * size / 16;
            int py = gy + i * size / 16;
            context.fill(px, gy, px + 1, gy + size, 0x332A3038);
            context.fill(gx, py, gx + size, py + 1, 0x332A3038);
        }
        int lx = gx + clamp((int) Math.round(local[0] / 15.0D * (size - 2)), 0, size - 2);
        int lz = gy + clamp((int) Math.round(local[2] / 15.0D * (size - 2)), 0, size - 2);
        context.fill(lx, lz, lx + Math.max(2, cell + 1), lz + Math.max(2, cell + 1), slime ? 0xFF55FF55 : 0xFFE6EDF5);
        drawCross(context, lx + 1, lz + 1, 0xFF8FC5FF);
        if (w > 50) {
            drawText(context, text, slime ? "slime" : "chunk", x + 3, y + 2, slime ? 0xFF55FF55 : 0xFF8FA0B8);
            drawText(context, text, trimToWidth(text, Math.round(local[0]) + ":" + Math.round(local[2]), w - 6), x + 3, y + h - 10, 0xFFB8C4D2);
        }
    }

    private static void drawLeftSpatialModel(DrawContext context, TextRenderer text, F3Snapshot snapshot, int x, int y, int w, int h) {
        context.fill(x, y, x + w, y + h, 0x44050609);
        F3TargetSnapshot target = snapshot.target();
        int cx = x + w / 2;
        int modelScale = Math.max(11, Math.min(18, h / 2));
        int baseY = safeSidePanelBoxBaseY(y, h, modelScale, 18, 11);
        int color = target == null ? 0xFF8FC5FF : target.accentColor();
        double heightRatio = 1.0D;
        double widthRatio = 0.72D;
        if (target != null && (target.type() == F3TargetType.ENTITY || target.type() == F3TargetType.PLAYER)) {
            double[] hitbox = firstThreeNumbers(targetLine(target, "Hitbox"));
            widthRatio = clampDouble((hitbox[0] <= 0.0D ? 0.6D : hitbox[0]) / 1.8D, 0.22D, 1.0D);
            heightRatio = clampDouble((hitbox[1] <= 0.0D ? 1.8D : hitbox[1]) / 2.8D, 0.18D, 1.18D);
        } else if (target != null && (target.type() == F3TargetType.BLOCK || target.type() == F3TargetType.CONTAINER || target.type() == F3TargetType.FLUID)) {
            heightRatio = Math.max(0.08D, ratioOrDefault(targetLine(target, "Outline Height"), 1.0D, 1.0D));
            widthRatio = 1.0D;
        }
        drawHeadProjectedBox(context, cx, baseY, modelScale, widthRatio, heightRatio, widthRatio, color, 0xFF8FC5FF);
        if (w > 44) {
            drawText(context, text, "3D", x + 3, y + 2, 0xFF8FA0B8);
            drawText(context, text, trimToWidth(text, target == null ? "none" : target.type().label(), w - 6), x + 3, y + h - 10, softText(color));
        }
    }

    private static void drawSignalNode(DrawContext context, int x, int y, double ratio, int color) {
        int r = 2 + clamp((int) Math.round(clampRatio(ratio) * 3.0D), 0, 3);
        context.fill(x - r, y - r, x + r + 1, y + r + 1, withAlpha(color, 70));
        context.fill(x - 1, y - 1, x + 2, y + 2, color);
    }

    private static int vitalColor(double ratio, int color) {
        if (ratio < 0.25D) {
            return 0xFFA7003A;
        }
        if (ratio < 0.5D) {
            return 0xFFE06A21;
        }
        return color;
    }

    private static int altitudeColor(double ratio) {
        if (ratio < 0.22D) {
            return 0xFFB47CFF;
        }
        if (ratio < 0.42D) {
            return 0xFF7FC8C2;
        }
        if (ratio < 0.68D) {
            return 0xFFE3B735;
        }
        return 0xFF8FC5FF;
    }

    private static double normalizeDegrees(double degrees) {
        double out = degrees % 360.0D;
        if (out < 0.0D) {
            out += 360.0D;
        }
        return out;
    }

    private static String cardinalLabel(double yaw) {
        double normalized = normalizeDegrees(yaw);
        if (normalized >= 315.0D || normalized < 45.0D) {
            return "S";
        }
        if (normalized < 135.0D) {
            return "W";
        }
        if (normalized < 225.0D) {
            return "N";
        }
        return "E";
    }


    private static int targetVisualStripHeight(F3TargetSnapshot target, F3Mode mode) {
        if (mode == F3Mode.SIMPLE) {
            return 0;
        }
        if (target.type() == F3TargetType.BLOCK || target.type() == F3TargetType.CONTAINER || target.type() == F3TargetType.FLUID || target.type() == F3TargetType.ENTITY || target.type() == F3TargetType.PLAYER) {
            return 51;
        }
        if (mode == F3Mode.GRAPHS || mode == F3Mode.INSPECTOR || target.type() == F3TargetType.ITEM) {
            return 34;
        }
        return 17;
    }

    private static void drawTargetMiniStrip(DrawContext context, TextRenderer text, F3TargetSnapshot target, int x, int y, int w, F3Mode mode) {
        int gap = 4;
        int each = Math.max(24, (w - gap * 2) / 3);
        miniMeter(context, text, x, y, each, "DATA", String.valueOf(target.lines().size()), clampRatio(target.lines().size() / 96.0D), target.accentColor());
        miniMeter(context, text, x + each + gap, y, each, "TAGS", String.valueOf(target.tags().size()), clampRatio(target.tags().size() / 192.0D), 0xFF8FC5FF);
        miniMeter(context, text, x + (each + gap) * 2, y, each, "RISK", target.danger(), dangerRatio(target.danger()), 0xFFE06A21);
        y += 17;
        if (target.type() == F3TargetType.BLOCK || target.type() == F3TargetType.CONTAINER || target.type() == F3TargetType.FLUID) {
            miniMeter(context, text, x, y, each, "HARD", firstKnown(targetLine(target, "Hardness"), "fluid"), targetRatio(target, "Hardness", 50.0D), 0xFF8FC5FF);
            miniMeter(context, text, x + each + gap, y, each, "LIGHT", firstKnown(targetLine(target, "Luminance"), targetLine(target, "Client Light")), Math.max(targetRatio(target, "Luminance", 15.0D), targetRatio(target, "Client Light", 15.0D)), 0xFF8FC5FF);
            miniMeter(context, text, x + (each + gap) * 2, y, each, "POWER", targetLine(target, "Redstone Power"), targetRatio(target, "Redstone Power", 15.0D), 0xFFA7003A);
            y += 17;
            miniMeter(context, text, x, y, each, "SHAPE", targetLine(target, "Collision Boxes"), targetRatio(target, "Collision Boxes", 16.0D), 0xFFB47CFF);
            miniMeter(context, text, x + each + gap, y, each, "MINE", targetLine(target, "Mining Rate Est"), targetRatio(target, "Mining Rate Est", 10.0D), 0xFF2DA700);
            miniMeter(context, text, x + (each + gap) * 2, y, each, "FLUID", targetLine(target, "Fluid Level"), targetRatio(target, "Fluid Level", 8.0D), 0xFF0085A4);
            return;
        }
        if (target.type() == F3TargetType.ENTITY || target.type() == F3TargetType.PLAYER) {
            miniMeter(context, text, x, y, each, "HP", targetLine(target, "Health"), parseFraction(targetLine(target, "Health")), 0xFF2DA700);
            miniMeter(context, text, x + each + gap, y, each, "ARMOR", targetLine(target, "Armor"), targetRatio(target, "Armor", 30.0D), 0xFF8FC5FF);
            miniMeter(context, text, x + (each + gap) * 2, y, each, "ATK", targetLine(target, "Attack Damage"), targetRatio(target, "Attack Damage", 20.0D), 0xFFE06A21);
            y += 17;
            miniMeter(context, text, x, y, each, "SPEED", targetLine(target, "Speed Magnitude"), targetRatio(target, "Speed Magnitude", 1.0D), 0xFF7FC8C2);
            miniMeter(context, text, x + each + gap, y, each, "AIR", targetLine(target, "Air"), parseFraction(targetLine(target, "Air")), 0xFF8FC5FF);
            miniMeter(context, text, x + (each + gap) * 2, y, each, "FIRE", targetLine(target, "Fire"), targetRatio(target, "Fire", 200.0D), 0xFFE06A21);
            return;
        }
        if (target.type() == F3TargetType.ITEM) {
            miniMeter(context, text, x, y, each, "COUNT", targetLine(target, "Count"), targetRatio(target, "Count", 64.0D), 0xFF8FC5FF);
            miniMeter(context, text, x + each + gap, y, each, "DURA", targetLine(target, "Durability"), parseFraction(targetLine(target, "Durability")), 0xFF2DA700);
            miniMeter(context, text, x + (each + gap) * 2, y, each, "ENCH", targetLine(target, "Enchantments"), targetRatio(target, "Enchantments", 8.0D), 0xFFB47CFF);
        }
    }

    private static void miniMeter(DrawContext context, TextRenderer text, int x, int y, int w, String label, String value, double ratio, int color) {
        String shown = trimToWidth(text, value, Math.max(10, w - text.getWidth(label) - 4));
        drawText(context, text, label, x, y, 0xFF8FA0B8);
        drawText(context, text, shown, x + w - text.getWidth(shown), y, color);
        drawMeter(context, x, y + 10, w, 5, ratio, color);
    }

    private static void drawMeter(DrawContext context, int x, int y, int w, int h, double ratio, int color) {
        int filled = clamp((int) Math.round(w * clampRatio(ratio)), 0, w);
        context.fill(x, y, x + w, y + h, PANEL_BG_SOFT);
        context.fill(x, y, x + filled, y + h, withAlpha(color, 210));
        context.fill(x, y, x + 1, y + h, withAlpha(softText(color), 210));
        if (filled > 2) {
            context.fill(x + filled - 1, y, x + filled, y + h, softText(color));
        }
    }


    private static PanelBounds renderTarget(DrawContext context, TextRenderer text, F3TargetSnapshot target, F3Snapshot snapshot, int height, F3Mode mode, PanelBounds left) {
        if (mode == F3Mode.PERFORMANCE || !targetPanelVisible(mode) || left == null) {
            return null;
        }
        int baseMax = switch (mode) {
            case SIMPLE -> 5;
            case NORMAL, WORLD -> 9;
            case INSPECTOR -> Integer.MAX_VALUE;
            default -> 22;
        };
        int x = left.x();
        int y = left.y() + left.h() + 6;
        int selfReserve = selfPanelVisible(mode) ? selfReserveHeight(height, mode) + 6 : 0;
        int availableH = Math.max(0, height - y - 8 - selfReserve);
        if (availableH < 38) {
            return null;
        }
        List<F3DataLine> allRows = targetRows(target, mode, snapshot);
        int miniH = targetVisualStripHeight(target, mode);
        int maxByHeight = Math.max(1, (availableH - 40 - miniH) / 12);
        int max = Math.max(1, Math.min(baseMax, maxByHeight));
        int maxPanelW = Math.max(left.w(), Math.min(520, Math.max(left.w(), MinecraftClient.getInstance().getWindow().getScaledWidth() / 2 - 18)));
        int prePanelW = mode == F3Mode.INSPECTOR ? panelWidth(text, allRows, List.of("Target " + target.type().label(), scrollStatusPanel(allRows, max, F3LayoutState.ScrollPanel.TARGET)), left.w(), maxPanelW, false) : left.w();
        List<F3DataLine> wrappedRows = mode == F3Mode.INSPECTOR ? wrapRowsToWidth(text, allRows, prePanelW - 16, false) : allRows;
        List<F3DataLine> rows = visiblePanelLines(wrappedRows, max, F3LayoutState.ScrollPanel.TARGET);
        int panelW = mode == F3Mode.INSPECTOR ? panelWidth(text, rows.isEmpty() ? wrappedRows : rows, List.of("Target " + target.type().label(), scrollStatusPanel(wrappedRows, max, F3LayoutState.ScrollPanel.TARGET)), left.w(), maxPanelW, false) : left.w();
        int panelH = Math.min(availableH, 40 + miniH + rows.size() * 12);
        F3LayoutState.registerPanel(F3LayoutState.ScrollPanel.TARGET, x, y, panelW, panelH, wrappedRows.size(), max);
        panel(context, x, y, panelW, panelH, target.accentColor());
        drawText(context, text, "Target " + target.type().label(), x + 8, y + 8, softText(target.accentColor()));
        drawText(context, text, mode == F3Mode.INSPECTOR ? scrollStatusPanel(wrappedRows, max, F3LayoutState.ScrollPanel.TARGET) : trimToWidth(text, target.registryId(), Math.max(32, panelW - 120)), x + 112, y + 8, 0xFFB8C4D2);
        drawText(context, text, trimToWidth(text, target.title(), panelW - 16), x + 8, y + 22, 0xFFE6EDF5);
        int rowY = y + 40;
        if (miniH > 0) {
            drawTargetMiniStrip(context, text, target, x + 8, rowY, panelW - 16, mode);
            rowY += miniH;
        }
        for (F3DataLine line : rows) {
            if (rowY + 12 > y + panelH - 8) {
                break;
            }
            row(context, text, x + 8, rowY, panelW - 16, line);
            rowY += 12;
        }
        return new PanelBounds(x, y, panelW, panelH);
    }

    private static void renderSelfPanel(DrawContext context, TextRenderer text, F3Snapshot snapshot, int height, F3Mode mode, PanelBounds left, PanelBounds target) {
        if (!selfPanelVisible(mode) || left == null || target == null) {
            return;
        }
        int x = left.x();
        int y = target.y() + target.h() + 6;
        int availableH = Math.max(0, height - y - 8);
        if (availableH < 42) {
            return;
        }
        List<F3DataLine> allRows = inspectorSelfRows(snapshot);
        int miniH = 28;
        int maxByHeight = Math.max(1, (availableH - 40 - miniH) / 12);
        int max = Math.max(1, Math.min(Integer.MAX_VALUE, maxByHeight));
        int maxPanelW = Math.max(left.w(), Math.min(520, Math.max(left.w(), MinecraftClient.getInstance().getWindow().getScaledWidth() / 2 - 18)));
        int prePanelW = panelWidth(text, allRows, List.of("Self Data", scrollStatusPanel(allRows, max, F3LayoutState.ScrollPanel.SELF)), left.w(), maxPanelW, false);
        List<F3DataLine> wrappedRows = wrapRowsToWidth(text, allRows, prePanelW - 16, false);
        List<F3DataLine> rows = visiblePanelLines(wrappedRows, max, F3LayoutState.ScrollPanel.SELF);
        int panelW = panelWidth(text, rows.isEmpty() ? wrappedRows : rows, List.of("Self Data", scrollStatusPanel(wrappedRows, max, F3LayoutState.ScrollPanel.SELF)), left.w(), maxPanelW, false);
        int panelH = Math.min(availableH, 40 + miniH + rows.size() * 12);
        F3LayoutState.registerPanel(F3LayoutState.ScrollPanel.SELF, x, y, panelW, panelH, wrappedRows.size(), max);
        panel(context, x, y, panelW, panelH, 0xFF7FC8C2);
        drawText(context, text, "Self Data", x + 8, y + 8, 0xFFE6EDF5);
        drawText(context, text, scrollStatusPanel(wrappedRows, max, F3LayoutState.ScrollPanel.SELF), x + 84, y + 8, 0xFF8FA0B8);
        drawText(context, text, trimToWidth(text, compactValues(value(snapshot, "player", "XYZ"), value(snapshot, "player", "Facing")), panelW - 16), x + 8, y + 22, 0xFFB8C4D2);
        int rowY = y + 40;
        drawSelfMiniStrip(context, text, snapshot, x + 8, rowY, panelW - 16);
        rowY += miniH;
        for (F3DataLine line : rows) {
            if (rowY + 12 > y + panelH - 8) {
                break;
            }
            row(context, text, x + 8, rowY, panelW - 16, line);
            rowY += 12;
        }
    }

    private static boolean selfPanelVisible(F3Mode mode) {
        return mode == F3Mode.INSPECTOR || mode == F3Mode.GRAPHS;
    }

    private static int leftLowerReserveHeight(int height, F3Mode mode) {
        if (mode == F3Mode.PERFORMANCE) {
            return 0;
        }
        int target = targetPanelVisible(mode) ? targetReserveHeight(height, mode) : 0;
        int self = selfPanelVisible(mode) ? selfReserveHeight(height, mode) + 6 : 0;
        return target + self;
    }

    private static int selfReserveHeight(int height, F3Mode mode) {
        if (mode == F3Mode.INSPECTOR) {
            return clamp(height / 4, 86, Math.max(86, height / 2));
        }
        if (mode == F3Mode.GRAPHS) {
            return clamp(height / 5, 72, Math.max(72, height / 3));
        }
        return 0;
    }

    private static void drawSelfMiniStrip(DrawContext context, TextRenderer text, F3Snapshot snapshot, int x, int y, int w) {
        int gap = 5;
        int each = Math.max(34, (w - gap * 4) / 5);
        miniMeter(context, text, x, y, each, "HP", value(snapshot, "player", "Health"), parseFraction(value(snapshot, "player", "Health")), 0xFF2DA700);
        miniMeter(context, text, x + each + gap, y, each, "FOOD", value(snapshot, "player", "Hunger"), parseRatio(value(snapshot, "player", "Hunger"), 20.0D), 0xFF8FC5FF);
        miniMeter(context, text, x + (each + gap) * 2, y, each, "VEL", formatRatio(vectorMagnitude(value(snapshot, "player", "Velocity"))), clampRatio(vectorMagnitude(value(snapshot, "player", "Velocity")) / 0.75D), 0xFF8FC5FF);
        miniMeter(context, text, x + (each + gap) * 3, y, each, "YAW", yawLabel(value(snapshot, "player", "Facing")), yawRatio(value(snapshot, "player", "Facing")), 0xFF7FC8C2);
        miniMeter(context, text, x + (each + gap) * 4, y, each, "PITCH", pitchLabel(value(snapshot, "player", "Facing")), clampRatio((extractPitch(value(snapshot, "player", "Facing")) + 90.0D) / 180.0D), 0xFFB47CFF);
    }

    private static List<F3DataLine> inspectorSelfRows(F3Snapshot snapshot) {
        List<F3DataLine> rows = new ArrayList<>();
        appendInspectorSectionRows(rows, "Self Player", sectionLines(snapshot, "player"), 0xFF7FC8C2);
        appendInspectorSectionRows(rows, "Self World", sectionLines(snapshot, "world"), 0xFF8FC5FF);
        appendInspectorSectionRows(rows, "Self Chunk", sectionLines(snapshot, "chunk"), 0xFFC8A24A);
        appendInspectorSectionRows(rows, "Self Performance", sectionLines(snapshot, "performance"), snapshot.performance().primaryBottleneck().color());
        return rows;
    }

    private static List<F3DataLine> developerLines(F3Snapshot snapshot) {
        List<F3DataLine> visible = new ArrayList<>();
        visible.addAll(sectionLines(snapshot, "system"));
        visible.addAll(sectionLines(snapshot, "render"));
        visible.addAll(sectionLines(snapshot, "network"));
        return visible;
    }

    private static boolean developerDataMode(F3Mode mode) {
        return mode == F3Mode.DEVELOPER || mode == F3Mode.ADVANCED || mode == F3Mode.FULL || mode == F3Mode.MODPACK;
    }

    private static int targetReserveHeight(int height, F3Mode mode) {
        if (!targetPanelVisible(mode)) {
            return 0;
        }
        if (mode == F3Mode.INSPECTOR) {
            return clamp(height / 2, 118, Math.max(118, height - 172));
        }
        if (mode == F3Mode.GRAPHS) {
            return clamp(height / 3, 86, Math.max(86, height / 2));
        }
        return Math.min(132, Math.max(74, height / 4));
    }

    private static List<F3DataLine> targetRows(F3TargetSnapshot target, F3Mode mode, F3Snapshot snapshot) {
        List<F3DataLine> rows = new ArrayList<>(target.lines());
        if (!target.tags().isEmpty() && mode != F3Mode.SIMPLE) {
            if (mode == F3Mode.INSPECTOR) {
                rows.add(new F3DataLine("", "", "divider", target.accentColor(), ""));
                rows.add(new F3DataLine("Tags", target.tags().size() + " found", "header", 0xFFE3B735, "All target tags without a hard cap."));
                for (String tag : target.tags()) {
                    rows.add(F3DataLine.of("Tag", tag));
                }
            } else {
                rows.add(F3DataLine.of("Tags", String.join(", ", target.tags())));
            }
        }
        if (mode == F3Mode.INSPECTOR && !target.actions().isEmpty()) {
            rows.add(new F3DataLine("", "", "divider", target.accentColor(), ""));
            rows.add(F3DataLine.of("Actions", String.join(", ", target.actions())));
        }
        return rows;
    }

    private static void appendInspectorSectionRows(List<F3DataLine> rows, String title, List<F3DataLine> lines, int color) {
        if (lines.isEmpty()) {
            return;
        }
        rows.add(new F3DataLine("", "", "divider", color, ""));
        rows.add(F3DataLine.state(title, lines.size() + " rows", "header", color, "Inspector self data section."));
        for (F3DataLine line : lines) {
            rows.add(line);
        }
    }

    private static void panel(DrawContext context, int x, int y, int w, int h, int accent) {
        context.fill(x, y, x + w, y + h, PANEL_BG);
        context.fill(x, y, x + 3, y + h, withAlpha(accent, 205));
    }

    private static void drawMetric(DrawContext context, TextRenderer text, int x, int y, String label, String value, int color) {
        drawText(context, text, label, x, y, 0xFF8FA0B8);
        drawText(context, text, value, x, y + 10, color);
    }

    private static void row(DrawContext context, TextRenderer text, int x, int y, int width, F3DataLine line) {
        int labelW = Math.min(90, Math.max(48, width / 3));
        drawText(context, text, trimToWidth(text, line.label(), labelW - 4), x, y, 0xFF8FA0B8);
        drawText(context, text, trimToWidth(text, line.value(), Math.max(8, width - labelW - 4)), x + labelW, y, line.color());
    }

    private static void rowCompact(DrawContext context, TextRenderer text, int x, int y, int width, F3DataLine line) {
        if (line.label().isBlank() && line.value().isBlank()) {
            context.fill(x, y + 4, x + Math.min(width, 72), y + 5, 0x442A3038);
            return;
        }
        if (line.value().isBlank()) {
            drawText(context, text, trimToWidth(text, line.label(), width), x, y, softText(line.color()));
            return;
        }
        int labelW = Math.min(112, Math.max(54, width / 3));
        drawText(context, text, trimToWidth(text, line.label(), labelW - 4), x, y, 0xFF8FA0B8);
        drawText(context, text, trimToWidth(text, line.value(), Math.max(8, width - labelW - 4)), x + labelW, y, line.color());
    }

    private static List<F3DataLine> wrapRowsToWidth(TextRenderer text, List<F3DataLine> lines, int width, boolean compact) {
        List<F3DataLine> wrapped = new ArrayList<>();
        for (F3DataLine line : lines) {
            if (line.label().isBlank() || line.value().isBlank()) {
                wrapped.add(line);
                continue;
            }
            int labelW = compact ? Math.min(112, Math.max(54, width / 3)) : Math.min(90, Math.max(48, width / 3));
            int valueW = Math.max(8, width - labelW - 4);
            if (text.getWidth(line.value()) <= valueW) {
                wrapped.add(line);
                continue;
            }
            List<String> parts = splitTextToWidth(text, line.value(), valueW);
            for (int i = 0; i < parts.size(); i++) {
                wrapped.add(new F3DataLine(i == 0 ? line.label() : ">", parts.get(i), line.state(), line.color(), line.tooltip()));
            }
        }
        return wrapped;
    }

    private static List<String> splitTextToWidth(TextRenderer text, String value, int maxWidth) {
        List<String> parts = new ArrayList<>();
        String safe = value == null ? "" : value;
        int start = 0;
        while (start < safe.length()) {
            int end = start + 1;
            int best = end;
            while (end <= safe.length()) {
                String part = safe.substring(start, end);
                if (text.getWidth(part) > maxWidth) {
                    break;
                }
                best = end;
                end++;
            }
            if (best <= start) {
                best = Math.min(safe.length(), start + 1);
            }
            parts.add(safe.substring(start, best));
            start = best;
        }
        if (parts.isEmpty()) {
            parts.add("");
        }
        return parts;
    }

    private static int panelWidth(TextRenderer text, List<F3DataLine> lines, List<String> headers, int min, int max, boolean compact) {
        int content = 0;
        for (String header : headers) {
            content = Math.max(content, text.getWidth(header));
        }
        for (F3DataLine line : lines) {
            content = Math.max(content, lineWidth(text, line, compact));
        }
        return clamp(content + 18, min, max);
    }

    private static int lineWidth(TextRenderer text, F3DataLine line, boolean compact) {
        if (line.label().isBlank() && line.value().isBlank()) {
            return 72;
        }
        if (line.value().isBlank()) {
            return text.getWidth(line.label());
        }
        int labelW = compact ? Math.min(112, Math.max(54, text.getWidth(line.label()) + 8)) : Math.min(90, Math.max(48, text.getWidth(line.label()) + 8));
        return labelW + 4 + text.getWidth(line.value());
    }

    private static boolean isCaptureUnavailable(List<F3DataLine> lines) {
        if (lines.size() != 1) {
            return false;
        }
        F3DataLine line = lines.get(0);
        return line.label().equals("Vanilla F3") && line.value().toLowerCase(Locale.ROOT).contains("capture unavailable");
    }


    private static String value(F3Snapshot snapshot, String sectionId, String label) {
        for (F3DataLine line : sectionLines(snapshot, sectionId)) {
            if (line.label().equals(label)) {
                return safeValue(line.value());
            }
        }
        return "unknown";
    }

    private static String targetLine(F3TargetSnapshot target, String label) {
        for (F3DataLine line : target.lines()) {
            if (line.label().equals(label)) {
                return safeValue(line.value());
            }
        }
        return "unknown";
    }

    private static double targetRatio(F3TargetSnapshot target, String label, double divisor) {
        return parseRatio(targetLine(target, label), divisor);
    }

    private static String firstKnown(String first, String second) {
        String safeFirst = safeValue(first);
        if (!safeFirst.equals("unknown") && !safeFirst.equals("none")) {
            return safeFirst;
        }
        return safeValue(second);
    }

    private static String firstKnown(String first, String second, String third) {
        String firstResult = firstKnown(first, second);
        if (!firstResult.equals("unknown") && !firstResult.equals("none")) {
            return firstResult;
        }
        return safeValue(third);
    }

    private static List<GraphSignal> tagNamespaceSignals(F3TargetSnapshot target) {
        List<String> namespaces = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (String tag : target.tags()) {
            String namespace = tag.contains(":") ? tag.substring(0, tag.indexOf(':')) : "minecraft";
            int index = namespaces.indexOf(namespace);
            if (index < 0) {
                namespaces.add(namespace);
                counts.add(1);
            } else {
                counts.set(index, counts.get(index) + 1);
            }
        }
        List<GraphSignal> signals = new ArrayList<>();
        int total = Math.max(1, target.tags().size());
        int[] colors = new int[]{0xFF7FC8C2, 0xFFE3B735, 0xFFB47CFF, 0xFF8FC5FF, 0xFFE06A21};
        while (!namespaces.isEmpty()) {
            int best = 0;
            for (int i = 1; i < counts.size(); i++) {
                if (counts.get(i) > counts.get(best)) {
                    best = i;
                }
            }
            int count = counts.remove(best);
            String namespace = namespaces.remove(best);
            signals.add(new GraphSignal(namespace, String.valueOf(count), count / (double) total, colors[signals.size() % colors.length]));
        }
        return signals;
    }

    private static String prefixKnown(String prefix, String value) {
        String safe = safeValue(value);
        return safe.equals("unknown") ? "unknown" : prefix + safe;
    }

    private static String suffixKnown(String value, String suffix) {
        String safe = safeValue(value);
        return safe.equals("unknown") ? "unknown" : safe + suffix;
    }

    private static String compactValues(String first, String second) {
        List<String> values = new ArrayList<>();
        addKnown(values, first);
        addKnown(values, second);
        return String.join(" | ", values);
    }

    private static String compactValues(String first, String second, String third) {
        List<String> values = new ArrayList<>();
        addKnown(values, first);
        addKnown(values, second);
        addKnown(values, third);
        return String.join(" | ", values);
    }

    private static void addKnown(List<String> values, String value) {
        String safe = safeValue(value);
        if (!safe.equals("unknown") && !safe.isBlank()) {
            values.add(safe);
        }
    }

    private static String safeValue(String value) {
        if (value == null || value.isBlank() || value.equals("null")) {
            return "unknown";
        }
        return value;
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        StringBuilder number = new StringBuilder();
        boolean started = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-') {
                number.append(c);
                started = true;
            } else if (started) {
                break;
            }
        }
        if (number.isEmpty() || number.toString().equals("-") || number.toString().equals(".")) {
            return fallback;
        }
        try {
            return Double.parseDouble(number.toString());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static double parseRatio(String value, double divisor) {
        if (divisor <= 0.0D) {
            return 0.0D;
        }
        return clampRatio(parseDouble(value, 0.0D) / divisor);
    }

    private static double timeRatio(String value) {
        double time = parseDouble(value, 0.0D);
        return clampRatio((time % 24000.0D) / 24000.0D);
    }

    private static double dangerRatio(String danger) {
        String safe = safeValue(danger).toLowerCase(Locale.ROOT);
        if (safe.contains("high") || safe.contains("danger")) {
            return 1.0D;
        }
        if (safe.contains("medium") || safe.contains("warn")) {
            return 0.66D;
        }
        if (safe.contains("low")) {
            return 0.33D;
        }
        return 0.08D;
    }

    private static String percent(double value) {
        return Math.round(clampRatio(value) * 100.0D) + "%";
    }

    private static double clampRatio(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static String pressureState(double pressure) {
        if (pressure < 0.70D) return "stable";
        if (pressure < 0.85D) return "warning";
        if (pressure < 0.93D) return "pressure";
        return "critical";
    }



    private static void coordinateMapCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3Snapshot snapshot) {
        panel(context, x, y, w, h, 0xFF8FC5FF);
        drawText(context, text, "Coordinate Map", x + 8, y + 7, 0xFFE6EDF5);
        String xyz = value(snapshot, "player", "XYZ");
        double[] coords = firstThreeNumbers(xyz);
        double[] spawn = firstThreeNumbers(value(snapshot, "world", "Spawn"));
        String spawnDistance = value(snapshot, "world", "Spawn Distance");
        int mapX = x + 8;
        int mapY = y + 21;
        int mapW = Math.max(56, w - 16);
        int mapH = Math.max(24, h - 31);
        context.fill(mapX, mapY, mapX + mapW, mapY + mapH, PANEL_BG_SOFT);
        context.fill(mapX + mapW / 2, mapY, mapX + mapW / 2 + 1, mapY + mapH, 0x552A3038);
        context.fill(mapX, mapY + mapH / 2, mapX + mapW, mapY + mapH / 2 + 1, 0x552A3038);
        int px = mapX + clamp((int) Math.round(Math.floorMod((long) Math.round(coords[0]), 512L) / 511.0D * mapW), 0, mapW - 2);
        int pz = mapY + clamp((int) Math.round(Math.floorMod((long) Math.round(coords[2]), 512L) / 511.0D * mapH), 0, mapH - 2);
        int sx = mapX + clamp((int) Math.round(Math.floorMod((long) Math.round(spawn[0]), 512L) / 511.0D * mapW), 0, mapW - 2);
        int sz = mapY + clamp((int) Math.round(Math.floorMod((long) Math.round(spawn[2]), 512L) / 511.0D * mapH), 0, mapH - 2);
        drawLine(context, px, pz, sx, sz, 0x66E3B735);
        drawCross(context, sx, sz, 0xFF8FC5FF);
        context.fill(px - 1, pz - 1, px + 2, pz + 2, 0xFFE6EDF5);
        drawText(context, text, trimToWidth(text, "x " + formatRatio(coords[0]), mapW / 2 - 3), mapX + 3, mapY + 3, 0xFF8FC5FF);
        drawText(context, text, trimToWidth(text, "z " + formatRatio(coords[2]), mapW / 2 - 3), mapX + mapW / 2 + 3, mapY + 3, 0xFF7FC8C2);
        drawText(context, text, trimToWidth(text, "y " + formatRatio(coords[1]), mapW - 6), mapX + 3, mapY + mapH - 10, 0xFF8FC5FF);
        drawText(context, text, trimToWidth(text, "spawn " + Math.round(spawn[0]) + "," + Math.round(spawn[1]) + "," + Math.round(spawn[2]), mapW - 8), mapX + 3, mapY + Math.max(13, mapH - 21), 0xFF8FC5FF);
        drawText(context, text, trimToWidth(text, "dist " + safeValue(spawnDistance), mapW / 2), mapX + mapW - Math.min(mapW / 2, text.getWidth("dist " + safeValue(spawnDistance)) + 3), mapY + mapH - 10, 0xFFB8C4D2);
    }

    private static void orientationCard(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3Snapshot snapshot) {
        panel(context, x, y, w, h, 0xFFB47CFF);
        drawText(context, text, "Yaw / Pitch", x + 8, y + 7, 0xFFE6EDF5);
        String facing = value(snapshot, "player", "Facing");
        double pitch = extractPitch(facing);
        double yawRatio = yawRatio(facing);
        int cx = x + 34;
        int cy = y + 49;
        int r = Math.max(13, Math.min(20, Math.max(13, h / 7)));
        drawClockRing(context, cx, cy, r, yawRatio, 0xFF7FC8C2);
        double yawAngle = yawRatio * Math.PI * 2.0D - Math.PI / 2.0D;
        int lookLen = Math.max(6, (int) Math.round((r - 3) * (1.0D - Math.min(0.42D, Math.abs(pitch) / 180.0D))));
        int lookX = cx + (int) Math.round(Math.cos(yawAngle) * lookLen);
        int lookY = cy + (int) Math.round(Math.sin(yawAngle) * lookLen);
        drawArrow(context, cx, cy, lookX, lookY, 0xFFE6EDF5);
        int pitchY = cy + clamp((int) Math.round((pitch / 90.0D) * (r - 5)), -(r - 5), r - 5);
        drawLine(context, cx - 6, pitchY, cx + 6, pitchY, 0xFFB47CFF);
        drawArrow(context, cx, cy, cx, pitchY, 0xFFB47CFF);
        String tiltText = pitch < -10.0D ? "up" : pitch > 10.0D ? "down" : "level";
        int tiltY = Math.min(y + h - 11, cy + r + 4);
        drawText(context, text, tiltText, cx - Math.min(r, text.getWidth(tiltText) / 2 + 2), tiltY, 0xFF8FA0B8);
        int barX = x + 68;
        int barW = Math.max(28, w - 76);
        miniBarRow(context, text, barX, y + 24, barW, "yaw", yawLabel(facing), yawRatio, 0xFF7FC8C2);
        miniBarRow(context, text, barX, y + 38, barW, "pitch", pitchLabel(facing), clampRatio((pitch + 90.0D) / 180.0D), 0xFFB47CFF);
        miniBarRow(context, text, barX, y + 52, barW, "tilt", tiltText, clampRatio(Math.abs(pitch) / 90.0D), 0xFF8FC5FF);
        int detailY = Math.max(y + 82, cy + r + 16);
        int detailX = x + 8;
        int detailW = w - 16;
        if (detailY + 8 <= y + h - 4) {
            orientationDetailRow(context, text, detailX, detailY, detailW, "facing", cardinalLabel(yawRatio * 360.0D), 0xFFE6EDF5);
            detailY += 10;
        }
        if (detailY + 8 <= y + h - 4) {
            orientationDetailRow(context, text, detailX, detailY, detailW, "yaw", yawLabel(facing), 0xFF7FC8C2);
            detailY += 10;
        }
        if (detailY + 8 <= y + h - 4) {
            orientationDetailRow(context, text, detailX, detailY, detailW, "pitch", pitchLabel(facing), 0xFFB47CFF);
            detailY += 10;
        }
        if (detailY + 8 <= y + h - 4) {
            orientationDetailRow(context, text, detailX, detailY, detailW, "tilt", pitch < -10.0D ? "looking up" : pitch > 10.0D ? "looking down" : "level", 0xFF8FC5FF);
            detailY += 10;
        }
        List<String> parts = splitTextToWidth(text, safeValue(facing), Math.max(18, detailW - 50));
        for (String part : parts) {
            if (detailY + 8 > y + h - 4) {
                break;
            }
            orientationDetailRow(context, text, detailX, detailY, detailW, "raw", part, 0xFFB8C4D2);
            detailY += 10;
        }
    }

    private static void orientationDetailRow(DrawContext context, TextRenderer text, int x, int y, int w, String label, String value, int color) {
        int labelW = Math.min(50, Math.max(34, text.getWidth(label) + 8));
        drawText(context, text, trimToWidth(text, label, labelW - 4), x, y, 0xFF8FA0B8);
        drawText(context, text, trimToWidth(text, safeValue(value), Math.max(8, w - labelW - 4)), x + labelW, y, color);
    }

    private static List<GraphSignal> terrainSignals(F3Snapshot snapshot) {
        List<GraphSignal> signals = new ArrayList<>();
        addTerrainSignal(signals, snapshot, "continentalness", 0xFF7FC8C2);
        addTerrainSignal(signals, snapshot, "erosion", 0xFFC8A24A);
        addTerrainSignal(signals, snapshot, "weirdness", 0xFFB47CFF);
        addTerrainSignal(signals, snapshot, "depth", 0xFF8FC5FF);
        addTerrainSignal(signals, snapshot, "ridges", 0xFF8FC5FF);
        addTerrainSignal(signals, snapshot, "temperature", 0xFFE06A21);
        addTerrainSignal(signals, snapshot, "humidity", 0xFF2DA700);
        return signals;
    }


    private static List<GraphSignal> expandedTerrainSignals(F3Snapshot snapshot) {
        List<GraphSignal> signals = new ArrayList<>(terrainSignals(snapshot));
        addSignalIfMissing(signals, "temp", formatRatio(parseDouble(value(snapshot, "world", "Temperature"), 0.0D)), clampRatio(parseDouble(value(snapshot, "world", "Temperature"), 0.0D) / 2.0D), 0xFFE06A21);
        addSignalIfMissing(signals, "sky", value(snapshot, "world", "Sky Light"), parseRatio(value(snapshot, "world", "Sky Light"), 15.0D), 0xFF8FC5FF);
        addSignalIfMissing(signals, "block", value(snapshot, "world", "Block Light"), parseRatio(value(snapshot, "world", "Block Light"), 15.0D), 0xFF8FC5FF);
        addSignalIfMissing(signals, "entities", firstKnown(value(snapshot, "chunk", "Chunk Entities"), value(snapshot, "chunk", "Client Entities")), parseRatio(firstKnown(value(snapshot, "chunk", "Chunk Entities"), value(snapshot, "chunk", "Client Entities")), 300.0D), 0xFFE06A21);
        addSignalIfMissing(signals, "sections", value(snapshot, "chunk", "Section Count"), parseRatio(value(snapshot, "chunk", "Section Count"), 24.0D), 0xFF7FC8C2);
        return signals;
    }

    private static void addSignalIfMissing(List<GraphSignal> signals, String label, String value, double ratio, int color) {
        for (GraphSignal signal : signals) {
            if (signal.label().equals(label)) {
                return;
            }
        }
        signals.add(new GraphSignal(label, safeValue(value), ratio, color));
    }

    private static void addTerrainSignal(List<GraphSignal> signals, F3Snapshot snapshot, String key, int color) {
        String found = findLineContaining(snapshot, key);
        if (!found.equals("unknown")) {
            double value = parseDouble(found, 0.0D);
            signals.add(new GraphSignal(terrainLabel(key), trim(found, 18), clampRatio((value + 1.0D) / 2.0D), color));
        }
    }

    private static String terrainLabel(String key) {
        return switch (key) {
            case "continentalness" -> "cont";
            case "erosion" -> "eros";
            case "weirdness" -> "weird";
            case "temperature" -> "temp";
            case "humidity" -> "humid";
            case "ridges" -> "ridge";
            default -> key.length() > 6 ? key.substring(0, 6) : key;
        };
    }

    private static String findLineContaining(F3Snapshot snapshot, String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (F3Section section : snapshot.sections()) {
            for (F3DataLine line : section.lines()) {
                String joined = (line.label() + " " + line.value()).toLowerCase(Locale.ROOT);
                if (joined.contains(lower)) {
                    return safeValue(line.value().isBlank() ? line.label() : line.value());
                }
            }
        }
        return "unknown";
    }

    private static double averageTerrainSignal(F3Snapshot snapshot) {
        List<GraphSignal> signals = terrainSignals(snapshot);
        if (signals.isEmpty()) {
            return clampRatio(parseDouble(value(snapshot, "world", "Temperature"), 0.0D) / 2.0D);
        }
        double total = 0.0D;
        for (GraphSignal signal : signals) {
            total += signal.ratio();
        }
        return clampRatio(total / signals.size());
    }

    private static void drawGraphGrid(DrawContext context, int x, int y, int w, int h) {
        int color = 0x252A3038;
        context.fill(x, y + h / 2, x + w, y + h / 2 + 1, color);
        context.fill(x + w / 2, y, x + w / 2 + 1, y + h, color);
    }

    private static void drawTinySpark(DrawContext context, int x, int y, int w, int h, Chart chart, int accent) {
        context.fill(x, y, x + w, y + h, 0x32050609);
        List<PerformanceMonitor.Sample> samples = new ArrayList<>(PerformanceMonitor.samples());
        if (samples.size() < 2) {
            context.fill(x, y + h / 2, x + w, y + h / 2 + 1, withAlpha(accent, 130));
            return;
        }
        int maxPoints = Math.min(samples.size(), Math.max(8, w));
        int start = samples.size() - maxPoints;
        double maxValue = maxChartValue(samples, start, chart);
        int lastX = x;
        int lastY = valueY(samples.get(start), chart, y + 1, y + h - 2, maxValue);
        for (int i = start + 1; i < samples.size(); i++) {
            int px = x + (int) ((w - 1) * ((i - start) / (double) Math.max(1, maxPoints - 1)));
            int py = valueY(samples.get(i), chart, y + 1, y + h - 2, maxValue);
            drawLine(context, lastX, lastY, px, py, chartColor(samples.get(i), chart));
            lastX = px;
            lastY = py;
        }
    }

    private static void drawRadialSignals(DrawContext context, int cx, int cy, int r, double[] values, int[] colors) {
        context.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFE6EDF5);
        int rings = 3;
        for (int ring = 1; ring <= rings; ring++) {
            int rr = Math.max(2, r * ring / rings);
            for (int a = 0; a < 360; a += 12) {
                double angle = Math.toRadians(a);
                int px = cx + (int) Math.round(Math.cos(angle) * rr);
                int py = cy + (int) Math.round(Math.sin(angle) * rr);
                context.fill(px, py, px + 1, py + 1, 0x442A3038);
            }
        }
        int count = Math.min(values.length, colors.length);
        if (count <= 0) {
            return;
        }
        int lastX = 0;
        int lastY = 0;
        int firstX = 0;
        int firstY = 0;
        for (int i = 0; i < count; i++) {
            double angle = (Math.PI * 2.0D * i / count) - Math.PI / 2.0D;
            int endX = cx + (int) Math.round(Math.cos(angle) * r);
            int endY = cy + (int) Math.round(Math.sin(angle) * r);
            drawLine(context, cx, cy, endX, endY, 0x442A3038);
            int px = cx + (int) Math.round(Math.cos(angle) * r * clampRatio(values[i]));
            int py = cy + (int) Math.round(Math.sin(angle) * r * clampRatio(values[i]));
            context.fill(px - 1, py - 1, px + 2, py + 2, colors[i]);
            drawLine(context, cx, cy, px, py, withAlpha(colors[i], 155));
            if (i == 0) {
                firstX = px;
                firstY = py;
            } else {
                drawLine(context, lastX, lastY, px, py, withAlpha(colors[i], 205));
            }
            lastX = px;
            lastY = py;
        }
        if (count > 2) {
            drawLine(context, lastX, lastY, firstX, firstY, withAlpha(colors[0], 205));
        }
    }

    private static void drawClockRing(DrawContext context, int cx, int cy, int r, double ratio, int color) {
        for (int py = cy - r; py <= cy + r; py++) {
            for (int px = cx - r; px <= cx + r; px++) {
                int dx = px - cx;
                int dy = py - cy;
                int d2 = dx * dx + dy * dy;
                if (d2 <= r * r && d2 >= (r - 3) * (r - 3)) {
                    double angle = Math.atan2(dy, dx) + Math.PI / 2.0D;
                    if (angle < 0.0D) {
                        angle += Math.PI * 2.0D;
                    }
                    double point = angle / (Math.PI * 2.0D);
                    context.fill(px, py, px + 1, py + 1, point <= clampRatio(ratio) ? color : 0x662A3038);
                }
            }
        }
        int hx = cx + (int) Math.round(Math.cos(clampRatio(ratio) * Math.PI * 2.0D - Math.PI / 2.0D) * (r - 3));
        int hy = cy + (int) Math.round(Math.sin(clampRatio(ratio) * Math.PI * 2.0D - Math.PI / 2.0D) * (r - 3));
        drawLine(context, cx, cy, hx, hy, 0xFFE6EDF5);
    }

    private static void drawPulseRings(DrawContext context, int cx, int cy, int r, double pressure, int color) {
        int active = clamp((int) Math.round(clampRatio(pressure) * 3.0D), 0, 3);
        for (int ring = 1; ring <= 3; ring++) {
            int rr = Math.max(3, r * ring / 3);
            int ringColor = ring <= active ? withAlpha(color, 170) : 0x552A3038;
            for (int a = 0; a < 360; a += 18) {
                double angle = Math.toRadians(a);
                int px = cx + (int) Math.round(Math.cos(angle) * rr);
                int py = cy + (int) Math.round(Math.sin(angle) * rr);
                context.fill(px, py, px + 1, py + 1, ringColor);
            }
        }
        context.fill(cx - 2, cy - 2, cx + 3, cy + 3, pressure > 0.70D ? 0xFFE06A21 : 0xFF8FC5FF);
        drawLine(context, cx, cy, cx + r, cy, withAlpha(color, 150));
    }

    private static void drawStackLayer(DrawContext context, TextRenderer text, int x, int y, int w, int h, String label, String value, double ratio, int color) {
        int fill = clamp((int) Math.round(w * clampRatio(ratio)), 0, w);
        context.fill(x, y, x + w, y + h, PANEL_BG_SOFT);
        context.fill(x, y, x + fill, y + h, withAlpha(color, 160));
        context.fill(x, y, x + 1, y + h, withAlpha(softText(color), 185));
        drawText(context, text, label, x + 3, y + 1, 0xFFE6EDF5);
        String shown = trimToWidth(text, safeValue(value), Math.max(12, w / 3));
        drawText(context, text, shown, x + w - text.getWidth(shown) - 3, y + 1, softText(color));
    }

    private static void drawCross(DrawContext context, int x, int y, int color) {
        drawLine(context, x - 3, y, x + 3, y, color);
        drawLine(context, x, y - 3, x, y + 3, color);
    }

    private static void drawArrow(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        drawLine(context, x1, y1, x2, y2, color);
        double angle = Math.atan2(y2 - y1, x2 - x1);
        double left = angle + Math.PI * 0.78D;
        double right = angle - Math.PI * 0.78D;
        int len = 4;
        drawLine(context, x2, y2, x2 + (int) Math.round(Math.cos(left) * len), y2 + (int) Math.round(Math.sin(left) * len), color);
        drawLine(context, x2, y2, x2 + (int) Math.round(Math.cos(right) * len), y2 + (int) Math.round(Math.sin(right) * len), color);
    }

    private static void drawHeatGrid(DrawContext context, int x, int y, int cell, double[] values, int[] colors) {
        int size = cell * 4;
        context.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0x66050609);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int index = Math.min(values.length - 1, Math.abs(row * 2 + col * 3) % values.length);
                double neighbor = values[Math.min(values.length - 1, Math.abs(row + col + 1) % values.length)];
                double mixed = clampRatio(values[index] * 0.72D + neighbor * 0.28D);
                int alpha = 64 + (int) Math.round(mixed * 176.0D);
                int px = x + col * cell;
                int py = y + row * cell;
                context.fill(px, py, px + cell - 1, py + cell - 1, withAlpha(colors[index], alpha));
                if (mixed > 0.66D && cell > 5) {
                    context.fill(px + cell / 2, py + 1, px + cell / 2 + 1, py + cell - 2, withAlpha(softText(colors[index]), 160));
                }
            }
        }
        for (int i = 1; i < 4; i++) {
            context.fill(x + i * cell - 1, y, x + i * cell, y + size, 0x552A3038);
            context.fill(x, y + i * cell - 1, x + size, y + i * cell, 0x552A3038);
        }
    }

    private static void drawTargetTypeGlyph(DrawContext context, int x, int y, F3TargetSnapshot target) {
        drawMinecraftTargetIcon(context, x, y, target);
    }


    private static void drawMinecraftTargetIcon(DrawContext context, int x, int y, F3TargetSnapshot target) {
        int color = target == null ? 0xFF8FA0B8 : target.accentColor();
        if (target == null) {
            context.fill(x + 3, y + 3, x + 10, y + 10, withAlpha(color, 160));
            context.drawBorder(x + 2, y + 2, 9, 9, color);
            return;
        }
        F3TargetType type = target.type();
        if (type == F3TargetType.BLOCK || type == F3TargetType.CONTAINER || type == F3TargetType.FLUID) {
            drawMinecraftBlockIcon(context, x, y, color);
            return;
        }
        if (type == F3TargetType.ITEM) {
            drawMinecraftItemIcon(context, x, y, color);
            return;
        }
        if (type == F3TargetType.ENTITY || type == F3TargetType.PLAYER) {
            drawMinecraftEntityIcon(context, x, y, color);
            return;
        }
        context.fill(x + 3, y + 3, x + 10, y + 10, withAlpha(color, 160));
        context.drawBorder(x + 2, y + 2, 9, 9, color);
    }

    private static void drawMinecraftBlockIcon(DrawContext context, int x, int y, int color) {
        context.fill(x + 3, y + 4, x + 11, y + 12, withAlpha(color, 155));
        context.fill(x + 2, y + 3, x + 10, y + 5, softText(color));
        context.fill(x + 10, y + 5, x + 12, y + 11, withAlpha(color, 210));
        context.fill(x + 4, y + 11, x + 11, y + 13, withAlpha(0xFF050609, 100));
        context.fill(x + 4, y + 6, x + 6, y + 8, withAlpha(0xFFE6EDF5, 115));
    }

    private static void drawMinecraftItemIcon(DrawContext context, int x, int y, int color) {
        fillQuad(context, x + 7, y + 2, x + 12, y + 7, x + 7, y + 12, x + 2, y + 7, withAlpha(color, 160));
        drawLine(context, x + 7, y + 2, x + 12, y + 7, softText(color));
        drawLine(context, x + 12, y + 7, x + 7, y + 12, color);
        drawLine(context, x + 7, y + 12, x + 2, y + 7, withAlpha(color, 210));
        drawLine(context, x + 2, y + 7, x + 7, y + 2, withAlpha(softText(color), 190));
        context.fill(x + 6, y + 6, x + 9, y + 9, 0xFFE6EDF5);
    }

    private static void drawMinecraftEntityIcon(DrawContext context, int x, int y, int color) {
        context.fill(x + 4, y + 2, x + 10, y + 8, withAlpha(color, 170));
        context.fill(x + 3, y + 8, x + 11, y + 13, withAlpha(color, 130));
        context.fill(x + 5, y + 4, x + 6, y + 5, 0xFFE6EDF5);
        context.fill(x + 8, y + 4, x + 9, y + 5, 0xFFE6EDF5);
        context.fill(x + 5, y + 10, x + 7, y + 13, withAlpha(softText(color), 180));
        context.fill(x + 8, y + 10, x + 10, y + 13, withAlpha(softText(color), 180));
    }

    private static void drawMinecraftCompassNeedle(DrawContext context, int cx, int cy, int color) {
        context.fill(cx - 1, cy - 5, cx + 2, cy + 6, withAlpha(0xFF050609, 120));
        context.fill(cx, cy - 6, cx + 1, cy, color);
        context.fill(cx, cy, cx + 1, cy + 6, 0xFFFF5555);
        context.fill(cx - 3, cy, cx + 4, cy + 1, withAlpha(color, 120));
    }

    private static void drawEntityModelIconRail(DrawContext context, TextRenderer text, int x, int y, int w, int h, F3TargetSnapshot target) {
        int gap = 3;
        int cell = Math.max(10, Math.min(15, (w - gap * 2) / 3));
        drawEntityRailIcon(context, x, y, cell, parseFraction(targetLine(target, "Health")), 0xFF2DA700, "heart");
        drawEntityRailIcon(context, x + cell + gap, y, cell, targetRatio(target, "Armor", 30.0D), 0xFF8FC5FF, "armor");
        drawEntityRailIcon(context, x + (cell + gap) * 2, y, cell, targetRatio(target, "Speed Magnitude", 1.0D), 0xFF7FC8C2, "boot");
        String shortPose = trimToWidth(text, firstKnown(targetLine(target, "Pose"), targetLine(target, "On Ground")), Math.max(22, w));
        drawText(context, text, shortPose, x, y + h - 8, 0xFF8FA0B8);
    }

    private static void drawEntityRailIcon(DrawContext context, int x, int y, int size, double ratio, int color, String kind) {
        context.fill(x, y, x + size, y + size, 0x44050609);
        int fill = clamp((int) Math.round((size - 2) * clampRatio(ratio)), 0, size - 2);
        context.fill(x + 1, y + size - 1 - fill, x + size - 1, y + size - 1, withAlpha(color, 120));
        if (kind.equals("heart")) {
            context.fill(x + 3, y + 3, x + 5, y + 5, color);
            context.fill(x + size - 5, y + 3, x + size - 3, y + 5, color);
            context.fill(x + 2, y + 5, x + size - 2, y + 8, color);
            context.fill(x + 4, y + 8, x + size - 4, y + 10, color);
            return;
        }
        if (kind.equals("armor")) {
            context.fill(x + 3, y + 2, x + size - 3, y + 5, color);
            context.fill(x + 2, y + 5, x + size - 2, y + 9, withAlpha(color, 190));
            context.fill(x + 4, y + 9, x + size - 4, y + 11, color);
            return;
        }
        context.fill(x + 3, y + 3, x + 6, y + size - 3, color);
        context.fill(x + 5, y + size - 5, x + size - 2, y + size - 2, color);
        context.fill(x + 2, y + size - 3, x + size - 1, y + size - 1, withAlpha(color, 160));
    }

    private static int slimeChunkColor(String value) {
        String safe = safeValue(value).toLowerCase(Locale.ROOT);
        if (safe.equals("true")) {
            return 0xFF2DA700;
        }
        if (safe.equals("false")) {
            return 0xFF8FA0B8;
        }
        return 0xFFE3B735;
    }

    private static boolean booleanValue(String value) {
        String safe = safeValue(value).toLowerCase(Locale.ROOT);
        return safe.equals("true") || safe.equals("yes") || safe.equals("active") || safe.equals("loaded") || (!safe.equals("false") && !safe.equals("none") && !safe.equals("unknown") && !safe.equals("0") && !safe.isBlank() && !safe.contains("none"));
    }

    private static double booleanRatio(String value) {
        return booleanValue(value) ? 1.0D : 0.0D;
    }

    private static void drawMotionBar(DrawContext context, int midX, int y, int halfW, double value, int color) {
        int amount = clamp((int) Math.round(Math.abs(value) * halfW * 8.0D), value == 0.0D ? 0 : 1, halfW);
        context.fill(midX - halfW, y + 1, midX + halfW, y + 2, 0x442A3038);
        if (amount <= 0) {
            context.fill(midX - 1, y, midX + 1, y + 3, 0x668FA0B8);
            return;
        }
        if (value >= 0.0D) {
            context.fill(midX, y, midX + amount, y + 4, withAlpha(color, 205));
            context.fill(midX + amount - 1, y - 1, midX + amount + 1, y + 5, softText(color));
        } else {
            context.fill(midX - amount, y, midX, y + 4, withAlpha(color, 205));
            context.fill(midX - amount - 1, y - 1, midX - amount + 1, y + 5, softText(color));
        }
    }


    private static void drawFlagCell(DrawContext context, TextRenderer text, int x, int y, int w, String label, String value, int color) {
        boolean active = booleanValue(value);
        int body = active ? withAlpha(color, 145) : 0x552A3038;
        int stripe = active ? withAlpha(color, 225) : 0x662A3038;
        context.fill(x, y, x + w, y + 14, PANEL_BG_SOFT);
        context.fill(x, y, x + w, y + 1, active ? withAlpha(softText(color), 135) : 0x442A3038);
        context.fill(x, y + 10, x + w, y + 14, body);
        context.fill(x, y, x + 2, y + 14, stripe);
        context.fill(x + w - 1, y + 1, x + w, y + 14, active ? withAlpha(color, 110) : 0x332A3038);
        drawText(context, text, trimToWidth(text, label, w - 5), x + 3, y + 2, active ? 0xFFE6EDF5 : 0xFF8FA0B8);
    }


    private static void drawIsoBlock(DrawContext context, int cx, int cy, int size, double heightRatio, int color) {
        drawHeadProjectedBox(context, cx, cy, size, 1.0D, Math.max(0.08D, heightRatio), 1.0D, color, softText(color));
    }


    private static void drawEntityModelGlyph(DrawContext context, int cx, int baseY, int w, int h, double eyeRatio, int color) {
        double width = clampDouble(w / 24.0D, 0.18D, 1.0D);
        double height = clampDouble(h / 42.0D, 0.18D, 1.25D);
        drawHeadProjectedBox(context, cx, baseY, 18, width, height, width, color, 0xFF8FC5FF);
        int eyeY = baseY - clamp((int) Math.round(18.0D * height * clampRatio(eyeRatio)), 2, Math.max(3, (int) Math.round(18.0D * height)) + 3);
        drawLine(context, cx - Math.max(5, w / 2), eyeY, cx + Math.max(5, w / 2), eyeY, 0xFF8FC5FF);
    }


    private static void drawGoalNode(DrawContext context, TextRenderer text, int x, int y, String label, boolean active, int color) {
        int w = 38;
        int fill = active ? withAlpha(color, 175) : 0x552A3038;
        context.fill(x, y, x + w, y + 10, fill);
        context.fill(x, y, x + w, y + 1, active ? withAlpha(softText(color), 165) : 0x442A3038);
        context.fill(x, y + 8, x + w, y + 10, active ? withAlpha(color, 230) : 0x662A3038);
        context.drawBorder(x, y, w, 10, active ? color : 0x662A3038);
        drawText(context, text, trimToWidth(text, label, w - 5), x + 3, y + 1, active ? 0xFFE6EDF5 : 0xFF8FA0B8);
    }




    private static int safeSidePanelBoxBaseY(int y, int h, int scale, int topPadding, int bottomPadding) {
        int top = y + Math.max(16, topPadding);
        int bottom = y + h - Math.max(12, bottomPadding);
        int preferred = y + h - Math.max(bottomPadding + 8, scale + bottomPadding + 6);
        int minBase = top + Math.max(8, scale / 2);
        int maxBase = bottom - Math.max(4, scale / 5);
        if (maxBase < minBase) {
            return y + Math.max(16, h / 2);
        }
        return clamp(preferred, minBase, maxBase);
    }

    private static ProjectedPoint projectModelPoint(double x, double y, double z, int scale) {
        return projectBoxPoint(x, y, z, playerYaw(), playerPitch(), scale);
    }

    private static void drawProjectedMarker(DrawContext context, int cx, int baseY, int scale, double x, double y, double z, int color, int radius) {
        ProjectedPoint p = projectModelPoint(x, y, z, scale);
        context.fill(cx + p.x() - radius, baseY + p.y() - radius, cx + p.x() + radius + 1, baseY + p.y() + radius + 1, color);
    }

    private static void drawProjectedSegment(DrawContext context, int cx, int baseY, int scale, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
        ProjectedPoint a = projectModelPoint(x1, y1, z1, scale);
        ProjectedPoint b = projectModelPoint(x2, y2, z2, scale);
        drawLine(context, cx + a.x(), baseY + a.y(), cx + b.x(), baseY + b.y(), color);
    }

    private static void drawProjectedBarOnEdge(DrawContext context, int cx, int baseY, int scale, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
        ProjectedPoint a = projectModelPoint(x1, y1, z1, scale);
        ProjectedPoint b = projectModelPoint(x2, y2, z2, scale);
        drawLine(context, cx + a.x(), baseY + a.y(), cx + b.x(), baseY + b.y(), withAlpha(color, 215));
        context.fill(cx + b.x() - 1, baseY + b.y() - 1, cx + b.x() + 2, baseY + b.y() + 2, color);
    }

    private static void drawHeadProjectedBox(DrawContext context, int cx, int baseY, int scale, double width, double height, double depth, int color, int secondaryColor) {
        double yaw = playerYaw();
        double pitch = playerPitch();
        double hw = clampDouble(width, 0.05D, 1.4D) / 2.0D;
        double hd = clampDouble(depth, 0.05D, 1.4D) / 2.0D;
        double hh = clampDouble(height, 0.03D, 1.5D);
        ProjectedPoint p0 = projectBoxPoint(-hw, 0.0D, -hd, yaw, pitch, scale);
        ProjectedPoint p1 = projectBoxPoint(hw, 0.0D, -hd, yaw, pitch, scale);
        ProjectedPoint p2 = projectBoxPoint(hw, 0.0D, hd, yaw, pitch, scale);
        ProjectedPoint p3 = projectBoxPoint(-hw, 0.0D, hd, yaw, pitch, scale);
        ProjectedPoint p4 = projectBoxPoint(-hw, hh, -hd, yaw, pitch, scale);
        ProjectedPoint p5 = projectBoxPoint(hw, hh, -hd, yaw, pitch, scale);
        ProjectedPoint p6 = projectBoxPoint(hw, hh, hd, yaw, pitch, scale);
        ProjectedPoint p7 = projectBoxPoint(-hw, hh, hd, yaw, pitch, scale);
        fillQuad(context, cx + p4.x(), baseY + p4.y(), cx + p5.x(), baseY + p5.y(), cx + p6.x(), baseY + p6.y(), cx + p7.x(), baseY + p7.y(), withAlpha(softText(color), 42));
        fillQuad(context, cx + p1.x(), baseY + p1.y(), cx + p2.x(), baseY + p2.y(), cx + p6.x(), baseY + p6.y(), cx + p5.x(), baseY + p5.y(), withAlpha(secondaryColor, 34));
        fillQuad(context, cx + p3.x(), baseY + p3.y(), cx + p0.x(), baseY + p0.y(), cx + p4.x(), baseY + p4.y(), cx + p7.x(), baseY + p7.y(), withAlpha(color, 28));
        drawProjectedEdge(context, cx, baseY, p0, p1, withAlpha(secondaryColor, 170));
        drawProjectedEdge(context, cx, baseY, p1, p2, withAlpha(secondaryColor, 170));
        drawProjectedEdge(context, cx, baseY, p2, p3, withAlpha(secondaryColor, 170));
        drawProjectedEdge(context, cx, baseY, p3, p0, withAlpha(secondaryColor, 170));
        drawProjectedEdge(context, cx, baseY, p4, p5, color);
        drawProjectedEdge(context, cx, baseY, p5, p6, color);
        drawProjectedEdge(context, cx, baseY, p6, p7, color);
        drawProjectedEdge(context, cx, baseY, p7, p4, color);
        drawProjectedEdge(context, cx, baseY, p0, p4, withAlpha(color, 205));
        drawProjectedEdge(context, cx, baseY, p1, p5, withAlpha(color, 205));
        drawProjectedEdge(context, cx, baseY, p2, p6, withAlpha(color, 205));
        drawProjectedEdge(context, cx, baseY, p3, p7, withAlpha(color, 205));
    }

    private static void drawProjectedEdge(DrawContext context, int cx, int baseY, ProjectedPoint a, ProjectedPoint b, int color) {
        drawLine(context, cx + a.x(), baseY + a.y(), cx + b.x(), baseY + b.y(), color);
    }

    private static ProjectedPoint projectBoxPoint(double x, double y, double z, double yawDegrees, double pitchDegrees, int scale) {
        AxisProjection projection = projectAxis(x, y, z, yawDegrees, pitchDegrees, scale);
        return new ProjectedPoint(projection.x(), projection.y(), projection.depth());
    }

    private static ProjectedPoint projectPoint(double x, double y, double z, double yawDegrees, double pitchDegrees, int scale) {
        return projectCameraWorldVector(x, y, z, yawDegrees, pitchDegrees, scale);
    }

    private static void fillQuad(DrawContext context, int x1, int y1, int x2, int y2, int x3, int y3, int x4, int y4, int color) {
        int minY = Math.min(Math.min(y1, y2), Math.min(y3, y4));
        int maxY = Math.max(Math.max(y1, y2), Math.max(y3, y4));
        for (int py = minY; py <= maxY; py++) {
            int count = 0;
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int v = edgeIntersectionX(py, x1, y1, x2, y2);
            if (v != Integer.MIN_VALUE) {
                minX = Math.min(minX, v);
                maxX = Math.max(maxX, v);
                count++;
            }
            v = edgeIntersectionX(py, x2, y2, x3, y3);
            if (v != Integer.MIN_VALUE) {
                minX = Math.min(minX, v);
                maxX = Math.max(maxX, v);
                count++;
            }
            v = edgeIntersectionX(py, x3, y3, x4, y4);
            if (v != Integer.MIN_VALUE) {
                minX = Math.min(minX, v);
                maxX = Math.max(maxX, v);
                count++;
            }
            v = edgeIntersectionX(py, x4, y4, x1, y1);
            if (v != Integer.MIN_VALUE) {
                minX = Math.min(minX, v);
                maxX = Math.max(maxX, v);
                count++;
            }
            if (count >= 2 && minX <= maxX) {
                context.fill(minX, py, maxX + 1, py + 1, color);
            }
        }
    }


    private static int edgeIntersectionX(int py, int x1, int y1, int x2, int y2) {
        if (y1 == y2 || py < Math.min(y1, y2) || py >= Math.max(y1, y2)) {
            return Integer.MIN_VALUE;
        }
        double t = (py - y1) / (double) (y2 - y1);
        return (int) Math.round(x1 + (x2 - x1) * t);
    }

    private static int chunkSectionColor(int index, int current, double ratio, boolean slime) {
        if (index == current) {
            return slime ? 0xFF55FF55 : 0xFFE3B735;
        }
        if (slime && index % 3 == 0) {
            return withAlpha(0xFF2DA700, 70 + (int) Math.round(ratio * 95.0D));
        }
        if (ratio < 0.33D) {
            return withAlpha(0xFFB47CFF, 50 + (int) Math.round(ratio * 120.0D));
        }
        if (ratio < 0.66D) {
            return withAlpha(0xFF8FC5FF, 55 + (int) Math.round(ratio * 110.0D));
        }
        return withAlpha(0xFFE3B735, 60 + (int) Math.round(ratio * 105.0D));
    }

    private static double ratioOrDefault(String value, double divisor, double fallback) {
        String safe = safeValue(value).toLowerCase(Locale.ROOT);
        if (safe.equals("unknown") || safe.equals("none") || safe.equals("false")) {
            return clampRatio(fallback);
        }
        return parseRatio(value, divisor);
    }

    private static double clampDouble(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double playerYaw() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return 0.0D;
        }
        return client.player.getYaw();
    }

    private static double playerPitch() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return 0.0D;
        }
        return client.player.getPitch();
    }

    private static double[] firstThreeNumbers(String value) {
        double[] out = new double[]{0.0D, 0.0D, 0.0D};
        String safe = safeValue(value).replaceAll("[^0-9+\\-., ]", " ");
        String[] parts = safe.split("[, ]+");
        int index = 0;
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            out[index++] = parseDouble(part, 0.0D);
            if (index >= out.length) {
                break;
            }
        }
        return out;
    }

    private static double extractYaw(String facing) {
        String lower = safeValue(facing).toLowerCase(Locale.ROOT);
        int yawIndex = lower.indexOf("yaw");
        if (yawIndex >= 0) {
            return parseDouble(lower.substring(yawIndex + 3), 0.0D);
        }
        double[] values = firstThreeNumbers(facing);
        return values[0];
    }

    private static double extractPitch(String facing) {
        String lower = safeValue(facing).toLowerCase(Locale.ROOT);
        int pitchIndex = lower.indexOf("pitch");
        if (pitchIndex >= 0) {
            return parseDouble(lower.substring(pitchIndex + 5), 0.0D);
        }
        double[] values = firstThreeNumbers(facing);
        return values.length > 1 ? values[1] : 0.0D;
    }

    private static double yawRatio(String facing) {
        double yaw = extractYaw(facing) % 360.0D;
        if (yaw < 0.0D) {
            yaw += 360.0D;
        }
        return clampRatio(yaw / 360.0D);
    }

    private static String yawLabel(String facing) {
        return Math.round(yawRatio(facing) * 360.0D) + "°";
    }

    private static String pitchLabel(String facing) {
        return Math.round(extractPitch(facing)) + "°";
    }

    private static double parseFraction(String value) {
        String safe = safeValue(value);
        int slash = safe.indexOf('/');
        if (slash > 0) {
            double current = parseDouble(safe.substring(0, slash), 0.0D);
            double max = parseDouble(safe.substring(slash + 1), 0.0D);
            return max <= 0.0D ? 0.0D : clampRatio(current / max);
        }
        return parseRatio(safe, 20.0D);
    }

    private static double vectorMagnitude(String value) {
        String safe = safeValue(value);
        String[] parts = safe.split(",");
        double total = 0.0D;
        int count = 0;
        for (String part : parts) {
            double v = parseDouble(part, Double.NaN);
            if (!Double.isNaN(v)) {
                total += v * v;
                count++;
            }
        }
        return count == 0 ? 0.0D : Math.sqrt(total);
    }

    private static String formatRatio(double value) {
        return String.valueOf(Math.round(value * 100.0D) / 100.0D);
    }

    private static void drawSpark(DrawContext context, int x, int y, int w, int h, Chart chart) {
        context.fill(x, y, x + w, y + h, PANEL_BG_SOFT);
        context.drawBorder(x, y, w, h, 0x662A3038);
        List<PerformanceMonitor.Sample> samples = new ArrayList<>(PerformanceMonitor.samples());
        if (samples.size() < 2) {
            drawText(context, MinecraftClient.getInstance().textRenderer, "collecting", x + 6, y + 10, 0xFF8D8D8D);
            return;
        }
        int maxPoints = Math.min(samples.size(), Math.max(10, w - 10));
        int start = samples.size() - maxPoints;
        double maxValue = maxChartValue(samples, start, chart);
        int left = x + 5;
        int top = y + 5;
        int right = x + w - 5;
        int bottom = y + h - 5;
        int lastX = left;
        int lastY = valueY(samples.get(start), chart, top, bottom, maxValue);
        for (int i = start + 1; i < samples.size(); i++) {
            int px = left + (int) ((right - left) * ((i - start) / (double) Math.max(1, maxPoints - 1)));
            int py = valueY(samples.get(i), chart, top, bottom, maxValue);
            drawLine(context, lastX, lastY, px, py, chartColor(samples.get(i), chart));
            lastX = px;
            lastY = py;
        }
    }

    private static List<F3DataLine> sectionLines(F3Snapshot snapshot, String id) {
        for (F3Section section : snapshot.sections()) {
            if (section.id().equals(id)) {
                return section.lines();
            }
        }
        return List.of();
    }

    private static void addMatching(List<F3DataLine> out, List<F3DataLine> lines, String... labels) {
        for (String label : labels) {
            for (F3DataLine line : lines) {
                if (line.label().equals(label)) {
                    out.add(line);
                    break;
                }
            }
        }
    }

    private static List<F3DataLine> visibleLeftLines(List<F3DataLine> lines, int limit) {
        return visiblePanelLines(lines, limit, F3LayoutState.ScrollPanel.LEFT);
    }

    private static List<F3DataLine> visibleRightLines(List<F3DataLine> lines, int limit) {
        return visiblePanelLines(lines, limit, F3LayoutState.ScrollPanel.RIGHT);
    }

    private static List<F3DataLine> visiblePanelLines(List<F3DataLine> lines, int limit, F3LayoutState.ScrollPanel panel) {
        return visibleLines(lines, limit, F3LayoutState.offset(panel));
    }

    private static List<F3DataLine> visibleLines(List<F3DataLine> lines, int limit, int requestedOffset) {
        int offset = lines.size() <= limit ? 0 : Math.min(requestedOffset, Math.max(0, lines.size() - limit));
        int end = Math.min(lines.size(), offset + limit);
        if (offset >= end) {
            return List.of();
        }
        return lines.subList(offset, end);
    }

    private static String scrollStatusLeft(List<F3DataLine> lines, int limit) {
        return scrollStatusPanel(lines, limit, F3LayoutState.ScrollPanel.LEFT);
    }

    private static String scrollStatusRight(List<F3DataLine> lines, int limit) {
        return scrollStatusPanel(lines, limit, F3LayoutState.ScrollPanel.RIGHT);
    }

    private static String scrollStatusPanel(List<F3DataLine> lines, int limit, F3LayoutState.ScrollPanel panel) {
        return scrollStatus(lines, limit, F3LayoutState.offset(panel));
    }

    private static String scrollStatus(List<F3DataLine> lines, int limit, int requestedOffset) {
        if (lines.size() <= limit) {
            return lines.size() + " rows";
        }
        int offset = Math.min(requestedOffset, Math.max(0, lines.size() - limit));
        int end = Math.min(lines.size(), offset + limit);
        return (offset + 1) + "-" + end + "/" + lines.size();
    }

    private static double sampleValue(PerformanceMonitor.Sample sample, Chart chart) {
        return switch (chart) {
            case FPS -> sample.fps();
            case FRAME -> sample.frameTimeMs();
            case MEMORY -> sample.maxMemoryMb() <= 0 ? 0.0D : sample.usedMemoryMb() / (double) sample.maxMemoryMb() * 100.0D;
            case WORLD -> Math.max(sample.chunkStress() * 100.0D, sample.entityCount() / 3.0D);
        };
    }

    private static double maxChartValue(List<PerformanceMonitor.Sample> samples, int start, Chart chart) {
        double max = switch (chart) {
            case FPS -> 120.0D;
            case FRAME -> 120.0D;
            case MEMORY, WORLD -> 100.0D;
        };
        for (int i = Math.max(0, start); i < samples.size(); i++) {
            max = Math.max(max, sampleValue(samples.get(i), chart));
        }
        return Math.max(1.0D, max);
    }

    private static int valueY(PerformanceMonitor.Sample sample, Chart chart, int top, int bottom, double maxValue) {
        double value = Math.max(0.0D, Math.min(maxValue, sampleValue(sample, chart)));
        return bottom - (int) ((bottom - top) * (value / maxValue));
    }

    private static int chartColor(PerformanceMonitor.Sample sample, Chart chart) {
        return switch (chart) {
            case FPS -> fpsColor(sample.fps());
            case FRAME -> sample.frameTimeMs() > 80.0D ? 0xFFA7003A : sample.frameTimeMs() > 40.0D ? 0xFFE06A21 : 0xFF2DA700;
            case MEMORY -> pressureColor(sample.maxMemoryMb() <= 0 ? 0.0D : sample.usedMemoryMb() / (double) sample.maxMemoryMb());
            case WORLD -> "menu".equals(sample.worldType()) ? 0xFF8D8D8D : pressureColor(Math.max(sample.chunkStress(), sample.entityCount() / 300.0D));
        };
    }

    private static void drawLine(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;
        int x = x1;
        int y = y1;
        while (true) {
            context.fill(x, y, x + 1, y + 1, color);
            if (x == x2 && y == y2) {
                break;
            }
            int e2 = err * 2;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private static void drawText(DrawContext context, TextRenderer text, String value, int x, int y, int color) {
        context.drawText(text, value, x, y, color, true);
    }

    private static int fpsColor(int fps) {
        if (fps >= 60) return 0xFF2DA700;
        if (fps >= 45) return 0xFFE3B735;
        if (fps >= 30) return 0xFFE06A21;
        return 0xFFA7003A;
    }

    private static int pressureColor(double pressure) {
        if (pressure < 0.70D) return 0xFF2DA700;
        if (pressure < 0.85D) return 0xFFE3B735;
        if (pressure < 0.93D) return 0xFFE06A21;
        return 0xFFA7003A;
    }

    private static int softText(int color) {
        Color c = new Color(color, true);
        return new Color(Math.min(255, c.getRed() + 40), Math.min(255, c.getGreen() + 40), Math.min(255, c.getBlue() + 40), 255).getRGB();
    }

    private static int withAlpha(int color, int alpha) {
        Color c = new Color(color, true);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha).getRGB();
    }

    private static String trimToWidth(TextRenderer text, String value, int maxWidth) {
        if (value == null || maxWidth <= 0) {
            return "";
        }
        if (text.getWidth(value) <= maxWidth) {
            return value;
        }
        String suffix = "...";
        int suffixW = text.getWidth(suffix);
        int end = value.length();
        while (end > 0 && text.getWidth(value.substring(0, end)) + suffixW > maxWidth) {
            end--;
        }
        return value.substring(0, Math.max(0, end)) + suffix;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String trim(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private static List<GraphSignal> blockStateSignals(F3TargetSnapshot target) {
        List<GraphSignal> signals = new ArrayList<>();
        addStateSignal(signals, "facing", firstKnown(targetLine(target, "Facing Dir"), targetLine(target, "facing")), directionRatio(firstKnown(targetLine(target, "Facing Dir"), targetLine(target, "facing"))), 0xFFFF5555);
        addStateSignal(signals, "axis", targetLine(target, "Axis"), axisRatio(targetLine(target, "Axis")), 0xFF55FF55);
        addStateSignal(signals, "half", targetLine(target, "Half"), halfRatio(targetLine(target, "Half")), 0xFF8FC5FF);
        addStateSignal(signals, "shape", targetLine(target, "Shape Detail"), shapeStateRatio(targetLine(target, "Shape Detail")), 0xFFB47CFF);
        addStateSignal(signals, "type", targetLine(target, "Type Detail"), shapeStateRatio(targetLine(target, "Type Detail")), 0xFF7FC8C2);
        addStateSignal(signals, "open", targetLine(target, "Open State"), booleanRatio(targetLine(target, "Open State")), 0xFF8FC5FF);
        addStateSignal(signals, "powered", targetLine(target, "Powered State"), booleanRatio(targetLine(target, "Powered State")), 0xFFA7003A);
        addStateSignal(signals, "power", firstKnown(targetLine(target, "Power Level"), targetLine(target, "Redstone Power")), Math.max(targetRatio(target, "Power Level", 15.0D), targetRatio(target, "Redstone Power", 15.0D)), 0xFFA7003A);
        addStateSignal(signals, "water", firstKnown(targetLine(target, "Waterlogged State"), targetLine(target, "Waterlogged/Fluid")), fluidStateRatio(target), 0xFF0085A4);
        addStateSignal(signals, "extend", targetLine(target, "Extended State"), booleanRatio(targetLine(target, "Extended State")), 0xFF8FC5FF);
        addStateSignal(signals, "enable", targetLine(target, "Enabled State"), booleanRatio(targetLine(target, "Enabled State")), 0xFF2DA700);
        addStateSignal(signals, "lock", targetLine(target, "Locked State"), booleanRatio(targetLine(target, "Locked State")), 0xFFE06A21);
        addStateSignal(signals, "trigger", targetLine(target, "Triggered State"), booleanRatio(targetLine(target, "Triggered State")), 0xFFE06A21);
        addStateSignal(signals, "mode", targetLine(target, "Mode State"), shapeStateRatio(targetLine(target, "Mode State")), 0xFF8FC5FF);
        return signals;
    }

    private static void addStateSignal(List<GraphSignal> signals, String label, String value, double ratio, int color) {
        String safe = safeValue(value);
        if (!safe.equals("unknown") && !safe.equals("none") && !safe.isBlank()) {
            signals.add(new GraphSignal(label, safe, ratio, color));
        }
    }

    private static void drawStateListRow(DrawContext context, TextRenderer text, int x, int y, int w, GraphSignal signal) {
        int dot = clamp((int) Math.round(2.0D + clampRatio(signal.ratio()) * 3.0D), 2, 5);
        context.fill(x, y + 3, x + dot, y + 3 + dot, signal.color());
        context.fill(x + 7, y + 8, x + Math.max(8, w - 2), y + 9, 0x332A3038);
        int fill = clamp((int) Math.round((w - 9) * clampRatio(signal.ratio())), 0, Math.max(0, w - 9));
        context.fill(x + 7, y + 8, x + 7 + fill, y + 9, withAlpha(signal.color(), 185));
        int labelW = Math.max(18, Math.min(42, w / 3));
        drawText(context, text, trimToWidth(text, signal.label(), labelW), x + 8, y, 0xFF8FA0B8);
        drawText(context, text, trimToWidth(text, signal.value(), Math.max(8, w - labelW - 12)), x + labelW + 12, y, signal.color());
    }

    private static double shapeStateRatio(String value) {
        String safe = safeValue(value).toLowerCase(Locale.ROOT);
        if (safe.equals("unknown") || safe.equals("none")) {
            return 0.0D;
        }
        int hash = Math.abs(safe.hashCode());
        return 0.18D + (hash % 82) / 100.0D;
    }

    private static double fluidStateRatio(F3TargetSnapshot target) {
        if (booleanValue(targetLine(target, "Waterlogged State"))) {
            return 1.0D;
        }
        String fluid = safeValue(targetLine(target, "Waterlogged/Fluid"));
        return fluid.equals("unknown") || fluid.equals("none") ? 0.0D : 1.0D;
    }

    private static void renderCrosshairMovementVector(DrawContext context, TextRenderer text, MinecraftClient client, int cx, int cy, F3Mode mode) {
        if (client == null || client.player == null || !crosshairMotionWidgetsVisible(mode)) {
            return;
        }
        Vec3d velocity = client.player.getVelocity();
        int boxW = 44;
        int boxH = 32;
        int boxX = cx - 58;
        int boxY = cy + 18;
        int originX = boxX + boxW / 2;
        int originY = boxY + 13;
        context.enableScissor(boxX, boxY, boxX + boxW, boxY + boxH);
        context.fill(originX - 1, boxY + 3, originX, boxY + boxH - 5, 0x448FA0B8);
        context.fill(boxX + 4, originY, boxX + boxW - 4, originY + 1, 0x448FA0B8);
        drawArrow(context, originX, originY, originX + clamp((int) Math.round(velocity.x * 18.0D), -15, 15), originY - clamp((int) Math.round(velocity.z * 18.0D), -9, 9), 0xFF8FC5FF);
        drawArrow(context, originX, originY, originX, originY - clamp((int) Math.round(velocity.y * 15.0D), -10, 10), velocity.y >= 0.0D ? 0xFF55FF55 : 0xFFFF5555);
        context.disableScissor();
    }

    private static void renderCrosshairJumpArc(DrawContext context, TextRenderer text, MinecraftClient client, int cx, int cy, F3Mode mode) {
        if (client == null || client.player == null || !crosshairMotionWidgetsVisible(mode)) {
            return;
        }
        int baseX = cx - 18;
        int baseY = cy + 48;
        int width = mode == F3Mode.GRAPHS || mode == F3Mode.FULL ? 40 : 34;
        drawJumpArcPreview(context, baseX, baseY, width, 13, client.player.getVelocity().horizontalLength(), client.player.getVelocity().y, String.valueOf(client.player.isOnGround()));
    }

    private static void drawJumpArcPreview(DrawContext context, int x, int y, int w, int h, double horizontal, double vertical, String groundValue) {
        JumpPreviewState preview = jumpPreviewState(horizontal, vertical, groundValue);
        double previewHorizontal = preview.horizontal();
        double previewVertical = preview.vertical();
        int focusX = x + w - 2;
        double speedRatio = clampRatio(previewHorizontal / 0.65D);
        int arcW = clamp((int) Math.round(w * (0.34D + speedRatio * 0.66D)), 8, Math.max(8, w));
        int startX = focusX - arcW;
        double launch = previewVertical;
        int points = Math.max(10, w / 2);
        context.enableScissor(x, y - h, x + w, y + 3);
        context.fill(x, y, x + w, y + 1, preview.locked() ? 0x668FC5FF : 0x332A3038);
        int lastX = 0;
        int lastY = 0;
        boolean hasLast = false;
        int endpointY = y;
        for (int i = 0; i < points; i++) {
            double t = i / (double) Math.max(1, points - 1);
            double simT = t * 8.0D;
            int px = (int) Math.round(startX + arcW * t);
            double rawY = y - ((launch * simT) - (0.08D * simT * simT / 2.0D)) * 18.0D - previewHorizontal * 5.0D;
            int py = (int) Math.round(rawY);
            if (i == points - 1) {
                endpointY = py;
            }
            if (px < x || px > x + w) {
                hasLast = false;
                continue;
            }
            int color = preview.locked() ? 0xFF8FC5FF : py < y - h + 2 ? 0xFFE3B735 : py < y - 2 ? 0xFF2DA700 : 0xFFE06A21;
            if (hasLast) {
                drawLine(context, lastX, lastY, px, py, color);
            }
            lastX = px;
            lastY = py;
            hasLast = true;
        }
        context.disableScissor();
        int clampedEndpointY = clamp(endpointY, y - h, y + 2);
        context.fill(focusX - 1, y - h, focusX + 1, y + 2, preview.locked() ? 0xAA8FC5FF : 0x99E6EDF5);
        context.fill(focusX - 2, clampedEndpointY - 2, focusX + 3, clampedEndpointY + 3, preview.locked() ? 0xCC8FC5FF : 0xAA8FC5FF);
        context.fill(focusX - 1, clampedEndpointY - 1, focusX + 2, clampedEndpointY + 2, 0xFFE6EDF5);
        context.fill(Math.max(x, startX), y, focusX, y + 1, preview.locked() ? 0x668FC5FF : 0x668FA0B8);
    }

    private static JumpPreviewState jumpPreviewState(double horizontal, double vertical, String groundValue) {
        MinecraftClient client = MinecraftClient.getInstance();
        boolean grounded = booleanValue(groundValue);
        boolean jumpPressed = client != null && client.options != null && client.options.jumpKey != null && client.options.jumpKey.isPressed();
        long now = System.currentTimeMillis();
        boolean startSignal = !jumpPreviewLocked && ((jumpPressed && grounded) || (!grounded && vertical > 0.05D));
        if (startSignal) {
            jumpPreviewLocked = true;
            jumpPreviewHorizontal = horizontal;
            jumpPreviewVertical = grounded ? 0.42D : Math.max(0.18D, vertical);
            jumpPreviewStartMs = now;
        }
        boolean justLanded = jumpPreviewLocked && grounded && !jumpPreviewWasGrounded && now - jumpPreviewStartMs > 120L;
        boolean stalePrediction = jumpPreviewLocked && now - jumpPreviewStartMs > 2200L;
        if (justLanded || stalePrediction) {
            jumpPreviewLocked = false;
        }
        jumpPreviewWasGrounded = grounded;
        if (jumpPreviewLocked) {
            return new JumpPreviewState(jumpPreviewHorizontal, jumpPreviewVertical, true);
        }
        double launch = grounded && vertical <= 0.03D ? 0.42D : vertical;
        return new JumpPreviewState(horizontal, launch, false);
    }

    private static void drawCrosshairMiniWorldGrid(DrawContext context, TextRenderer text, MinecraftClient client, F3Snapshot snapshot, int cx, int cy, int scale, F3Mode mode) {
        return;
    }

    private static void drawMiniWorldEvents(DrawContext context, MinecraftClient client, int cx, int cy, int radius, F3Mode mode) {
        int range = mode == F3Mode.NORMAL ? 8 : 12;
        Vec3d playerPos = client.player.getPos();
        List<Entity> entities = new ArrayList<>(client.world.getEntitiesByClass(Entity.class, client.player.getBoundingBox().expand(range), entity -> entity != client.player && entity.isAlive()));
        entities.sort(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(client.player)));
        int limit = mode == F3Mode.NORMAL ? 4 : 8;
        int shown = 0;
        for (Entity entity : entities) {
            if (shown >= limit) {
                break;
            }
            Vec3d delta = entity.getPos().subtract(playerPos);
            ProjectedPoint point = projectWorldOffset(delta.x, delta.y, delta.z, client.player.getYaw(), client.player.getPitch(), radius - 4, range);
            if (Math.abs(point.x()) > radius || Math.abs(point.y()) > radius) {
                continue;
            }
            int color = crosshairEntityColor(entity, Math.sqrt(entity.squaredDistanceTo(client.player)), range, client.targetedEntity == entity);
            int px = cx + point.x();
            int py = cy + point.y();
            if (entity instanceof ItemEntity) {
                context.fill(px - 2, py, px + 1, py + 3, withAlpha(color, 185));
                context.fill(px, py - 2, px + 3, py + 1, withAlpha(color, 185));
            } else if (entity instanceof LivingEntity) {
                context.fill(px - 2, py - 2, px + 3, py + 3, withAlpha(color, 180));
                context.fill(px - 1, py + 3, px + 2, py + 4, withAlpha(color, 210));
            } else {
                context.fill(px - 1, py - 1, px + 2, py + 2, withAlpha(color, 170));
            }
            shown++;
        }
    }

    private static void drawMiniWorldChunkCompass(DrawContext context, TextRenderer text, MinecraftClient client, F3Snapshot snapshot, int cx, int cy, int radius) {
        double[] local = firstThreeNumbers(value(snapshot, "chunk", "Local Block"));
        int lx = clamp((int) Math.round((local[0] / 15.0D - 0.5D) * radius * 1.6D), -radius + 4, radius - 4);
        int lz = clamp((int) Math.round((local[2] / 15.0D - 0.5D) * radius * 1.6D), -radius + 4, radius - 4);
        context.fill(cx + lx - 2, cy + lz - 2, cx + lx + 3, cy + lz + 3, 0x66E3B735);
        if (local[0] <= 1.0D || local[0] >= 14.0D) {
            context.fill(cx + lx - 1, cy - radius, cx + lx + 2, cy + radius + 1, 0x44E3B735);
        }
        if (local[2] <= 1.0D || local[2] >= 14.0D) {
            context.fill(cx - radius, cy + lz - 1, cx + radius + 1, cy + lz + 2, 0x44E3B735);
        }
        String section = trimToWidth(text, "c " + value(snapshot, "chunk", "Section Y"), Math.max(24, radius));
        drawText(context, text, section, cx - radius + 3, cy - radius + 3, 0x668FA0B8);
    }

    private static void drawCompactTargetFaceMiniGrid(DrawContext context, Vec3d eye, BlockHitResult blockHit, double yaw, double pitch, int cx, int cy, int scale) {
        BlockPos pos = blockHit.getBlockPos();
        Direction side = blockHit.getSide();
        double minX = pos.getX();
        double minY = pos.getY();
        double minZ = pos.getZ();
        double maxX = minX + 1.0D;
        double maxY = minY + 1.0D;
        double maxZ = minZ + 1.0D;
        int edge = 0x66E3B735;
        int soft = 0x22E3B735;
        if (side == Direction.UP || side == Direction.DOWN) {
            double fy = side == Direction.UP ? maxY : minY;
            drawWorldLine(context, eye, minX, fy, minZ, maxX, fy, minZ, yaw, pitch, cx, cy, edge, scale);
            drawWorldLine(context, eye, maxX, fy, minZ, maxX, fy, maxZ, yaw, pitch, cx, cy, edge, scale);
            drawWorldLine(context, eye, maxX, fy, maxZ, minX, fy, maxZ, yaw, pitch, cx, cy, edge, scale);
            drawWorldLine(context, eye, minX, fy, maxZ, minX, fy, minZ, yaw, pitch, cx, cy, edge, scale);
            drawWorldLine(context, eye, minX + 0.5D, fy, minZ, minX + 0.5D, fy, maxZ, yaw, pitch, cx, cy, soft, scale);
            drawWorldLine(context, eye, minX, fy, minZ + 0.5D, maxX, fy, minZ + 0.5D, yaw, pitch, cx, cy, soft, scale);
        } else if (side == Direction.NORTH || side == Direction.SOUTH) {
            double fz = side == Direction.SOUTH ? maxZ : minZ;
            drawWorldLine(context, eye, minX, minY, fz, maxX, minY, fz, yaw, pitch, cx, cy, edge, scale);
            drawWorldLine(context, eye, maxX, minY, fz, maxX, maxY, fz, yaw, pitch, cx, cy, edge, scale);
            drawWorldLine(context, eye, maxX, maxY, fz, minX, maxY, fz, yaw, pitch, cx, cy, edge, scale);
            drawWorldLine(context, eye, minX, maxY, fz, minX, minY, fz, yaw, pitch, cx, cy, edge, scale);
            drawWorldLine(context, eye, minX + 0.5D, minY, fz, minX + 0.5D, maxY, fz, yaw, pitch, cx, cy, soft, scale);
            drawWorldLine(context, eye, minX, minY + 0.5D, fz, maxX, minY + 0.5D, fz, yaw, pitch, cx, cy, soft, scale);
        } else {
            double fx = side == Direction.EAST ? maxX : minX;
            drawWorldLine(context, eye, fx, minY, minZ, fx, minY, maxZ, yaw, pitch, cx, cy, edge, scale);
            drawWorldLine(context, eye, fx, minY, maxZ, fx, maxY, maxZ, yaw, pitch, cx, cy, edge, scale);
            drawWorldLine(context, eye, fx, maxY, maxZ, fx, maxY, minZ, yaw, pitch, cx, cy, edge, scale);
            drawWorldLine(context, eye, fx, maxY, minZ, fx, minY, minZ, yaw, pitch, cx, cy, edge, scale);
            drawWorldLine(context, eye, fx, minY + 0.5D, minZ, fx, minY + 0.5D, maxZ, yaw, pitch, cx, cy, soft, scale);
            drawWorldLine(context, eye, fx, minY, minZ + 0.5D, fx, maxY, minZ + 0.5D, yaw, pitch, cx, cy, soft, scale);
        }
    }

    private static void drawMiniWorldLabel(DrawContext context, TextRenderer text, String label, int x, int y, int color) {
        context.fill(x - 2, y - 1, x + text.getWidth(label) + 2, y + 9, 0x55050609);
        drawText(context, text, label, x, y, color);
    }

    private static void drawTargetBlockFaceGrid(DrawContext context, Vec3d eye, BlockHitResult blockHit, double yaw, double pitch, int cx, int cy, int scale) {
        BlockPos pos = blockHit.getBlockPos();
        Direction side = blockHit.getSide();
        double minX = pos.getX();
        double minY = pos.getY();
        double minZ = pos.getZ();
        double maxX = minX + 1.0D;
        double maxY = minY + 1.0D;
        double maxZ = minZ + 1.0D;
        int faceColor = 0x88E3B735;
        if (side == Direction.UP || side == Direction.DOWN) {
            double fy = side == Direction.UP ? maxY : minY;
            drawWorldLine(context, eye, minX, fy, minZ, maxX, fy, minZ, yaw, pitch, cx, cy, faceColor, scale);
            drawWorldLine(context, eye, maxX, fy, minZ, maxX, fy, maxZ, yaw, pitch, cx, cy, faceColor, scale);
            drawWorldLine(context, eye, maxX, fy, maxZ, minX, fy, maxZ, yaw, pitch, cx, cy, faceColor, scale);
            drawWorldLine(context, eye, minX, fy, maxZ, minX, fy, minZ, yaw, pitch, cx, cy, faceColor, scale);
            for (int i = 1; i < 4; i++) {
                double t = i / 4.0D;
                drawWorldLine(context, eye, minX + t, fy, minZ, minX + t, fy, maxZ, yaw, pitch, cx, cy, 0x33E3B735, scale);
                drawWorldLine(context, eye, minX, fy, minZ + t, maxX, fy, minZ + t, yaw, pitch, cx, cy, 0x33E3B735, scale);
            }
        } else if (side == Direction.NORTH || side == Direction.SOUTH) {
            double fz = side == Direction.SOUTH ? maxZ : minZ;
            drawWorldLine(context, eye, minX, minY, fz, maxX, minY, fz, yaw, pitch, cx, cy, faceColor, scale);
            drawWorldLine(context, eye, maxX, minY, fz, maxX, maxY, fz, yaw, pitch, cx, cy, faceColor, scale);
            drawWorldLine(context, eye, maxX, maxY, fz, minX, maxY, fz, yaw, pitch, cx, cy, faceColor, scale);
            drawWorldLine(context, eye, minX, maxY, fz, minX, minY, fz, yaw, pitch, cx, cy, faceColor, scale);
            for (int i = 1; i < 4; i++) {
                double t = i / 4.0D;
                drawWorldLine(context, eye, minX + t, minY, fz, minX + t, maxY, fz, yaw, pitch, cx, cy, 0x33E3B735, scale);
                drawWorldLine(context, eye, minX, minY + t, fz, maxX, minY + t, fz, yaw, pitch, cx, cy, 0x33E3B735, scale);
            }
        } else {
            double fx = side == Direction.EAST ? maxX : minX;
            drawWorldLine(context, eye, fx, minY, minZ, fx, minY, maxZ, yaw, pitch, cx, cy, faceColor, scale);
            drawWorldLine(context, eye, fx, minY, maxZ, fx, maxY, maxZ, yaw, pitch, cx, cy, faceColor, scale);
            drawWorldLine(context, eye, fx, maxY, maxZ, fx, maxY, minZ, yaw, pitch, cx, cy, faceColor, scale);
            drawWorldLine(context, eye, fx, maxY, minZ, fx, minY, minZ, yaw, pitch, cx, cy, faceColor, scale);
            for (int i = 1; i < 4; i++) {
                double t = i / 4.0D;
                drawWorldLine(context, eye, fx, minY, minZ + t, fx, maxY, minZ + t, yaw, pitch, cx, cy, 0x33E3B735, scale);
                drawWorldLine(context, eye, fx, minY + t, minZ, fx, minY + t, maxZ, yaw, pitch, cx, cy, 0x33E3B735, scale);
            }
        }
        Vec3d center = new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        Vec3d normal = new Vec3d(side.getOffsetX(), side.getOffsetY(), side.getOffsetZ());
        drawWorldLine(context, eye, center.x, center.y, center.z, center.x + normal.x * 0.75D, center.y + normal.y * 0.75D, center.z + normal.z * 0.75D, yaw, pitch, cx, cy, 0xFFE3B735, scale);
    }

    private static void renderCrosshairBlockNeighborScan(DrawContext context, TextRenderer text, MinecraftClient client, F3TargetSnapshot target, int cx, int cy, F3Mode mode) {
        if (client == null || client.world == null || client.player == null || client.crosshairTarget == null || !(client.crosshairTarget instanceof BlockHitResult blockHit) || mode == F3Mode.SIMPLE) {
            return;
        }
        if (client.crosshairTarget.getType() != HitResult.Type.BLOCK) {
            return;
        }
        int cell = mode == F3Mode.NORMAL ? 4 : 5;
        int gap = 2;
        int layerW = cell * 3 + gap * 2;
        int ox = cx + 58;
        int oy = cy - 34;
        BlockPos center = blockHit.getBlockPos();
        int[] layers = new int[]{1, 0, -1};
        String[] labels = new String[]{"up", "mid", "down"};
        for (int li = 0; li < layers.length; li++) {
            int layerX = ox + li * (layerW + 5);
            int layerY = oy;
            context.drawBorder(layerX - 1, layerY - 1, layerW + 2, layerW + 2, li == 1 ? 0x55E6EDF5 : 0x338FA0B8);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    BlockPos pos = center.add(dx, layers[li], dz);
                    BlockState state = client.world.getBlockState(pos);
                    int color = neighborProfileColor(client, state, pos);
                    int px = layerX + (dx + 1) * (cell + gap);
                    int py = layerY + (dz + 1) * (cell + gap);
                    int alpha = dx == 0 && dz == 0 && layers[li] == 0 ? 230 : 150;
                    context.fill(px, py, px + cell, py + cell, withAlpha(color, alpha));
                    if (dx == 0 && dz == 0 && layers[li] == 0) {
                        context.drawBorder(px - 1, py - 1, cell + 2, cell + 2, 0xFFE6EDF5);
                    }
                    if (state.hasBlockEntity()) {
                        context.fill(px + cell - 1, py, px + cell, py + 1, 0xFFB47CFF);
                    }
                    if (!client.world.getOtherEntities(null, new Box(pos)).isEmpty()) {
                        context.fill(px, py + cell - 1, px + 1, py + cell, 0xFFE06A21);
                    }
                }
            }
            if (mode != F3Mode.NORMAL) {
                drawText(context, text, labels[li], layerX, layerY + layerW + 3, li == 1 ? 0xFFE6EDF5 : 0xFF8FA0B8);
            }
        }
        if (mode != F3Mode.NORMAL) {
            int legendY = oy + layerW + 14;
            drawNeighborLegendChip(context, text, ox, legendY, "air", 0x665A636E);
            drawNeighborLegendChip(context, text, ox + 26, legendY, "solid", 0xFF8FC5FF);
            drawNeighborLegendChip(context, text, ox + 60, legendY, "data", 0xFFB47CFF);
        }
    }

    private static void drawNeighborLegendChip(DrawContext context, TextRenderer text, int x, int y, String label, int color) {
        context.fill(x, y + 2, x + 4, y + 6, color);
        drawText(context, text, label, x + 6, y, softText(color));
    }

    private static void renderNeighborBox(DrawContext context, BlockState state, int ox, int oy, int size, double dx, double dy, double dz, boolean center) {
        ProjectedPoint point = projectPlayerRadarOffset(dx, dy, dz, playerYaw(), playerPitch(), size * 3, 1.35D);
        int color = blockScanColor(state);
        int edge = center ? softText(color) : 0xFF8FC5FF;
        double height = state.isAir() ? 0.08D : clampDouble(state.getOutlineShape(MinecraftClient.getInstance().world, BlockPos.ORIGIN).isEmpty() ? 0.15D : state.getOutlineShape(MinecraftClient.getInstance().world, BlockPos.ORIGIN).getMax(Direction.Axis.Y), 0.08D, 1.0D);
        drawHeadProjectedBox(context, ox + point.x(), oy + point.y(), size, 0.72D, height, 0.72D, withAlpha(color, center ? 220 : 165), edge);
    }

    private static void renderNeighborProfilerBox(DrawContext context, MinecraftClient client, BlockState state, BlockPos pos, int ox, int oy, int size, double dx, double dy, double dz, boolean center) {
        ProjectedPoint point = projectPlayerRadarOffset(dx, dy, dz, playerYaw(), playerPitch(), size * 4, 1.75D);
        int color = neighborProfileColor(client, state, pos);
        int alpha = center ? 230 : dy > 0.0D ? 150 : dy < 0.0D ? 115 : 180;
        int edge = center ? 0xFFE6EDF5 : withAlpha(softText(color), 170);
        double height = state.isAir() ? 0.08D : clampDouble(state.getOutlineShape(client.world, pos).isEmpty() ? 0.15D : state.getOutlineShape(client.world, pos).getMax(Direction.Axis.Y), 0.08D, 1.0D);
        drawHeadProjectedBox(context, ox + point.x(), oy + point.y(), size, 0.58D, height, 0.58D, withAlpha(color, alpha), edge);
        if (state.hasBlockEntity()) {
            drawProjectedMarker(context, ox + point.x(), oy + point.y(), size, 0.0D, height + 0.06D, 0.0D, 0xFFB47CFF, 1);
        }
        if (!client.world.getOtherEntities(null, new Box(pos)).isEmpty()) {
            drawProjectedMarker(context, ox + point.x(), oy + point.y(), size, 0.0D, height * 0.5D, 0.0D, 0xFFE06A21, 1);
        }
    }

    private static int blockScanColor(BlockState state) {
        if (state.isAir()) {
            return 0x665A636E;
        }
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            return fluid.isStill() ? 0xFF0085A4 : 0xFF55C4FF;
        }
        if (state.emitsRedstonePower() || state.hasComparatorOutput()) {
            return 0xFFA7003A;
        }
        if (state.getLuminance() > 0) {
            return 0xFFE3B735;
        }
        if (!state.getCollisionShape(MinecraftClient.getInstance().world, BlockPos.ORIGIN).isEmpty()) {
            return 0xFF7FC8C2;
        }
        return 0xFFB47CFF;
    }

    private static void renderCrosshairEntityThreatScope(DrawContext context, TextRenderer text, MinecraftClient client, F3TargetSnapshot target, int cx, int cy, F3Mode mode) {
    }

    private static void drawThreatRing(DrawContext context, int cx, int cy, int r, double ratio, int color, String intent) {
        double safeRatio = clampRatio(ratio);
        int filled = clamp((int) Math.round(safeRatio * 360.0D), 0, 360);
        for (int a = 0; a <= filled; a += 10) {
            double rad = Math.toRadians(a - 90);
            int px = cx + (int) Math.round(Math.cos(rad) * r);
            int py = cy + (int) Math.round(Math.sin(rad) * r);
            context.fill(px, py, px + 1, py + 1, withAlpha(color, 210));
        }
        double needleRad = Math.toRadians(filled - 90);
        int nx = cx + (int) Math.round(Math.cos(needleRad) * Math.max(3, r - 2));
        int ny = cy + (int) Math.round(Math.sin(needleRad) * Math.max(3, r - 2));
        drawLine(context, cx, cy, nx, ny, color);
        context.fill(cx - 1, cy - 1, cx + 2, cy + 2, color);
    }

    private static int entityThreatColor(F3TargetSnapshot target) {
        String level = firstKnown(targetLine(target, "Threat Level"), target.danger()).toLowerCase(Locale.ROOT);
        if (level.contains("immediate") || level.contains("high")) {
            return 0xFFA7003A;
        }
        if (level.contains("threat") || level.contains("medium")) {
            return 0xFFE06A21;
        }
        if (level.contains("watch")) {
            return 0xFFE3B735;
        }
        return 0xFF2DA700;
    }

    private static void drawBlockDataModel(DrawContext context, int cx, int cy, int size, F3TargetSnapshot target, BlockVisualData visual) {
        int outlineColor = visual.fluid() > 0.0D ? 0xFF0085A4 : visual.light() > 0.0D ? 0xFFE3B735 : 0xFF8FC5FF;
        drawHeadProjectedBox(context, cx, cy, size, visual.outlineWidth(), visual.outlineHeight(), visual.outlineDepth(), outlineColor, 0xFFB47CFF);
        if (!visual.collisionEmpty()) {
            int collisionScale = Math.max(9, size - 5);
            drawHeadProjectedBox(context, cx, cy, collisionScale, visual.collisionWidth(), visual.collisionHeight(), visual.collisionDepth(), visual.primaryStateColor(), 0xFF8FC5FF);
        } else {
            drawEmptyCollisionGlyph(context, cx, cy, size);
        }
        drawBlockStateFaceHints(context, cx, cy, size, target, visual);
        drawBlockDataOverlays(context, cx, cy, size, target, visual);
        drawBlockShapeComplexity(context, cx, cy, size, target, visual);
    }

    private static BlockVisualData blockVisualData(F3TargetSnapshot target) {
        double outlineHeight = blockVisualHeight(target, "Outline Height", booleanValue(targetLine(target, "Outline Empty")) ? 0.05D : 1.0D);
        double collisionHeight = blockVisualHeight(target, "Collision Height", booleanValue(targetLine(target, "Collision Empty")) ? 0.05D : outlineHeight);
        double outlineWidth = blockVisualFootprint(target);
        double outlineDepth = blockVisualDepth(target);
        double collisionWidth = booleanValue(targetLine(target, "Side Solid North")) || booleanValue(targetLine(target, "Side Solid South")) ? outlineWidth : Math.max(0.28D, outlineWidth * 0.82D);
        double collisionDepth = booleanValue(targetLine(target, "Side Solid East")) || booleanValue(targetLine(target, "Side Solid West")) ? outlineDepth : Math.max(0.28D, outlineDepth * 0.82D);
        boolean empty = booleanValue(targetLine(target, "Collision Empty"));
        double power = Math.max(targetRatio(target, "Power Level", 15.0D), targetRatio(target, "Redstone Power", 15.0D));
        double light = Math.max(targetRatio(target, "Luminance", 15.0D), targetRatio(target, "Client Light", 15.0D));
        double fluid = booleanValue(firstKnown(targetLine(target, "Waterlogged State"), targetLine(target, "waterlogged"))) || !targetLine(target, "Waterlogged/Fluid").equals("none") ? 1.0D : targetRatio(target, "Fluid Level", 8.0D);
        double statePressure = Math.max(Math.max(power, light), fluid);
        int primary = power > 0.0D ? 0xFFA7003A : fluid > 0.0D ? 0xFF0085A4 : light > 0.0D ? 0xFFE3B735 : 0xFFB47CFF;
        return new BlockVisualData(outlineWidth, outlineDepth, outlineHeight, collisionWidth, collisionDepth, collisionHeight, empty, power, light, fluid, statePressure, primary);
    }

    private static double blockVisualHeight(F3TargetSnapshot target, String label, double fallback) {
        String raw = targetLine(target, label);
        double parsed = ratioOrDefault(raw, 1.0D, fallback);
        String data = blockVisualKey(target);
        if (safeValue(raw).equals("unknown") || safeValue(raw).equals("none")) {
            if (data.contains("slab") || data.contains("half=top") || data.contains("half=bottom") || data.contains("top") || data.contains("bottom")) {
                return 0.5D;
            }
            if (data.contains("trapdoor")) {
                return booleanValue(firstKnown(targetLine(target, "Open State"), targetLine(target, "open"))) ? 1.0D : 0.1875D;
            }
            if (data.contains("carpet") || data.contains("pressure_plate")) {
                return 0.08D;
            }
            if (data.contains("snow")) {
                return 0.125D;
            }
        }
        return clampDouble(parsed, 0.04D, 1.35D);
    }

    private static double blockVisualFootprint(F3TargetSnapshot target) {
        String data = blockVisualKey(target);
        if (data.contains("fence") || data.contains("wall")) {
            return 0.52D;
        }
        if (data.contains("pane") || data.contains("bars") || data.contains("chain")) {
            return 0.28D;
        }
        if (data.contains("torch") || data.contains("button") || data.contains("lever") || data.contains("pressure_plate")) {
            return 0.32D;
        }
        if (data.contains("door") && !data.contains("trapdoor")) {
            return 0.22D;
        }
        if (data.contains("bed")) {
            return 1.0D;
        }
        return 1.0D;
    }

    private static double blockVisualDepth(F3TargetSnapshot target) {
        String data = blockVisualKey(target);
        if (data.contains("pane") || data.contains("bars")) {
            return 0.28D;
        }
        if (data.contains("door") && !data.contains("trapdoor")) {
            return 0.95D;
        }
        if (data.contains("button") || data.contains("lever")) {
            return 0.20D;
        }
        if (data.contains("bed")) {
            return 1.35D;
        }
        return blockVisualFootprint(target);
    }

    private static String blockVisualKey(F3TargetSnapshot target) {
        return (safeValue(target.registryId()) + " " + safeValue(target.title()) + " " + safeValue(targetLine(target, "State")) + " " + safeValue(targetLine(target, "Shape Detail")) + " " + safeValue(targetLine(target, "Type Detail")) + " " + safeValue(targetLine(target, "Half"))).toLowerCase(Locale.ROOT);
    }

    private static String blockVisualStateSummary(F3TargetSnapshot target) {
        List<String> values = new ArrayList<>();
        addKnown(values, firstKnown(targetLine(target, "Facing Dir"), targetLine(target, "Axis")));
        addKnown(values, firstKnown(targetLine(target, "Half"), targetLine(target, "Type Detail")));
        if (booleanValue(firstKnown(targetLine(target, "Powered State"), targetLine(target, "powered"))) || targetRatio(target, "Power Level", 15.0D) > 0.0D || targetRatio(target, "Redstone Power", 15.0D) > 0.0D) {
            values.add("power");
        }
        if (booleanValue(firstKnown(targetLine(target, "Waterlogged State"), targetLine(target, "waterlogged"))) || !targetLine(target, "Waterlogged/Fluid").equals("none")) {
            values.add("fluid");
        }
        if (values.isEmpty()) {
            return "shape data";
        }
        return trim(String.join(" ", values), 18);
    }

    private static void drawBlockDataOverlays(DrawContext context, int cx, int cy, int size, F3TargetSnapshot target, BlockVisualData visual) {
        String half = firstKnown(targetLine(target, "Half"), targetLine(target, "half")).toLowerCase(Locale.ROOT);
        if (half.contains("top") || half.contains("upper")) {
            drawProjectedSegment(context, cx, cy, size, -visual.outlineWidth() * 0.42D, visual.outlineHeight() * 0.78D, visual.outlineDepth() * 0.52D, visual.outlineWidth() * 0.42D, visual.outlineHeight() * 0.78D, visual.outlineDepth() * 0.52D, 0x998FA0B8);
        } else if (half.contains("bottom") || half.contains("lower")) {
            drawProjectedSegment(context, cx, cy, size, -visual.outlineWidth() * 0.42D, visual.outlineHeight() * 0.18D, visual.outlineDepth() * 0.52D, visual.outlineWidth() * 0.42D, visual.outlineHeight() * 0.18D, visual.outlineDepth() * 0.52D, 0x99C8A24A);
        }
        String axis = firstKnown(targetLine(target, "Axis"), targetLine(target, "axis")).toLowerCase(Locale.ROOT);
        if (axis.equals("x")) {
            drawProjectedMarker(context, cx, cy, size, -visual.outlineWidth() * 0.62D, visual.outlineHeight() * 0.5D, 0.0D, 0xFFFF5555, 2);
        } else if (axis.equals("y")) {
            drawProjectedMarker(context, cx, cy, size, 0.0D, visual.outlineHeight() + 0.08D, 0.0D, 0xFF55FF55, 2);
        } else if (axis.equals("z")) {
            drawProjectedMarker(context, cx, cy, size, 0.0D, visual.outlineHeight() * 0.5D, visual.outlineDepth() * 0.62D, 0xFF5555FF, 2);
        }
        if (visual.power() > 0.0D || booleanValue(firstKnown(targetLine(target, "Powered State"), targetLine(target, "powered")))) {
            drawProjectedBarOnEdge(context, cx, cy, size, -visual.outlineWidth() * 0.62D, 0.03D, -visual.outlineDepth() * 0.58D, -visual.outlineWidth() * 0.62D, visual.outlineHeight() * Math.max(visual.power(), 0.35D), -visual.outlineDepth() * 0.58D, 0xFFA7003A);
        }
        if (visual.light() > 0.0D) {
            int glow = withAlpha(0xFFE3B735, 90 + (int) Math.round(120.0D * visual.light()));
            drawProjectedMarker(context, cx, cy, size, 0.0D, visual.outlineHeight() + 0.06D, 0.0D, glow, 2);
            drawProjectedMarker(context, cx, cy, size, -visual.outlineWidth() * 0.28D, visual.outlineHeight() + 0.02D, visual.outlineDepth() * 0.2D, withAlpha(glow, 180), 1);
            drawProjectedMarker(context, cx, cy, size, visual.outlineWidth() * 0.28D, visual.outlineHeight() + 0.02D, -visual.outlineDepth() * 0.2D, withAlpha(glow, 180), 1);
        }
        if (visual.fluid() > 0.0D) {
            double fluidTop = clampDouble(0.12D + visual.fluid() * 0.45D, 0.18D, Math.max(0.18D, visual.outlineHeight()));
            drawProjectedSegment(context, cx, cy, size, -visual.outlineWidth() * 0.42D, fluidTop, visual.outlineDepth() * 0.52D, visual.outlineWidth() * 0.42D, fluidTop, visual.outlineDepth() * 0.52D, withAlpha(0xFF0085A4, 190));
        }
        if (booleanValue(firstKnown(targetLine(target, "Open State"), targetLine(target, "open")))) {
            drawProjectedSegment(context, cx, cy, size, visual.outlineWidth() * 0.58D, visual.outlineHeight() * 0.18D, visual.outlineDepth() * 0.12D, visual.outlineWidth() * 0.58D, visual.outlineHeight() * 0.78D, visual.outlineDepth() * 0.12D, 0xFF8FC5FF);
        }
        drawSideSolidTicks(context, cx, cy, size, target, visual);
    }

    private static void drawEmptyCollisionGlyph(DrawContext context, int cx, int cy, int size) {
        context.fill(cx - size / 2, cy + 3, cx + size / 2, cy + 5, 0x99E06A21);
        context.fill(cx - size / 2 - 1, cy + 2, cx - size / 2 + 2, cy + 6, 0x99E06A21);
        context.fill(cx + size / 2 - 2, cy + 2, cx + size / 2 + 1, cy + 6, 0x99E06A21);
    }

    private static void drawAxisTag(DrawContext context, int x, int y, int color) {
        context.fill(x - 1, y - 1, x + 2, y + 2, color);
        context.fill(x - 2, y, x + 3, y + 1, withAlpha(color, 160));
    }

    private static void drawBlockShapeComplexity(DrawContext context, int cx, int cy, int size, F3TargetSnapshot target, BlockVisualData visual) {
        int collisionBoxes = clamp((int) Math.round(parseDouble(targetLine(target, "Collision Boxes"), visual.collisionEmpty() ? 0.0D : 1.0D)), 0, 8);
        int outlineBoxes = clamp((int) Math.round(parseDouble(targetLine(target, "Outline Boxes"), 1.0D)), 0, 8);
        for (int i = 0; i < outlineBoxes; i++) {
            double t = outlineBoxes <= 1 ? 0.5D : i / (double) (outlineBoxes - 1);
            drawProjectedMarker(context, cx, cy, size, -visual.outlineWidth() * 0.45D + visual.outlineWidth() * 0.9D * t, 0.02D, visual.outlineDepth() * 0.56D, 0xFF8FC5FF, 1);
        }
        for (int i = 0; i < collisionBoxes; i++) {
            double t = collisionBoxes <= 1 ? 0.5D : i / (double) (collisionBoxes - 1);
            drawProjectedMarker(context, cx, cy, size, -visual.outlineWidth() * 0.45D + visual.outlineWidth() * 0.9D * t, 0.12D, visual.outlineDepth() * 0.56D, 0xFFE06A21, 1);
        }
        double pressure = clampRatio((collisionBoxes + outlineBoxes) / 12.0D + visual.statePressure() * 0.35D);
        drawProjectedBarOnEdge(context, cx, cy, size, visual.outlineWidth() * 0.65D, 0.02D, -visual.outlineDepth() * 0.62D, visual.outlineWidth() * 0.65D, pressure * visual.outlineHeight(), -visual.outlineDepth() * 0.62D, visual.primaryStateColor());
    }

    private static void drawSideSolidTicks(DrawContext context, int cx, int cy, int size, F3TargetSnapshot target, BlockVisualData visual) {
        if (booleanValue(targetLine(target, "Side Solid North"))) {
            drawProjectedSegment(context, cx, cy, size, -visual.outlineWidth() * 0.42D, visual.outlineHeight() * 0.5D, -visual.outlineDepth() * 0.54D, visual.outlineWidth() * 0.42D, visual.outlineHeight() * 0.5D, -visual.outlineDepth() * 0.54D, 0x778FC5FF);
        }
        if (booleanValue(targetLine(target, "Side Solid South"))) {
            drawProjectedSegment(context, cx, cy, size, -visual.outlineWidth() * 0.42D, visual.outlineHeight() * 0.5D, visual.outlineDepth() * 0.54D, visual.outlineWidth() * 0.42D, visual.outlineHeight() * 0.5D, visual.outlineDepth() * 0.54D, 0x778FC5FF);
        }
        if (booleanValue(targetLine(target, "Side Solid East"))) {
            drawProjectedSegment(context, cx, cy, size, visual.outlineWidth() * 0.54D, visual.outlineHeight() * 0.12D, -visual.outlineDepth() * 0.42D, visual.outlineWidth() * 0.54D, visual.outlineHeight() * 0.82D, -visual.outlineDepth() * 0.42D, 0x777FC8C2);
        }
        if (booleanValue(targetLine(target, "Side Solid West"))) {
            drawProjectedSegment(context, cx, cy, size, -visual.outlineWidth() * 0.54D, visual.outlineHeight() * 0.12D, visual.outlineDepth() * 0.42D, -visual.outlineWidth() * 0.54D, visual.outlineHeight() * 0.82D, visual.outlineDepth() * 0.42D, 0x777FC8C2);
        }
        if (booleanValue(targetLine(target, "Side Solid Up"))) {
            drawProjectedSegment(context, cx, cy, size, -visual.outlineWidth() * 0.22D, visual.outlineHeight() + 0.02D, 0.0D, visual.outlineWidth() * 0.22D, visual.outlineHeight() + 0.02D, 0.0D, 0x77E3B735);
        }
    }

    private static void drawBlockStateFaceHints(DrawContext context, int cx, int cy, int size, F3TargetSnapshot target, BlockVisualData visual) {
        String facing = firstKnown(targetLine(target, "Facing Dir"), targetLine(target, "facing"));
        if (!facing.equals("unknown") && !facing.isBlank()) {
            String lower = facing.toLowerCase(Locale.ROOT);
            int color = switch (lower) {
                case "north" -> 0xFF5555FF;
                case "south" -> 0xFFFF5555;
                case "east" -> 0xFF55FF55;
                case "west" -> 0xFFE3B735;
                case "up" -> 0xFF7FC8C2;
                case "down" -> 0xFFB47CFF;
                default -> 0xFFE6EDF5;
            };
            double mx = 0.0D;
            double my = visual.outlineHeight() * 0.52D;
            double mz = visual.outlineDepth() * 0.6D;
            if (lower.equals("east")) {
                mx = visual.outlineWidth() * 0.62D;
                mz = -visual.outlineDepth() * 0.16D;
            } else if (lower.equals("west")) {
                mx = -visual.outlineWidth() * 0.62D;
                mz = visual.outlineDepth() * 0.16D;
            } else if (lower.equals("north")) {
                mz = -visual.outlineDepth() * 0.62D;
            } else if (lower.equals("south")) {
                mz = visual.outlineDepth() * 0.62D;
            } else if (lower.equals("up")) {
                my = visual.outlineHeight() + 0.08D;
                mz = 0.0D;
            } else if (lower.equals("down")) {
                my = 0.02D;
                mz = 0.0D;
            }
            drawProjectedMarker(context, cx, cy, size, mx, my, mz, color, 2);
        }
        if (booleanValue(firstKnown(targetLine(target, "Open State"), targetLine(target, "open")))) {
            drawProjectedMarker(context, cx, cy, size, visual.outlineWidth() * 0.62D, visual.outlineHeight() * 0.5D, 0.0D, 0xFFE3B735, 2);
        }
        if (booleanValue(firstKnown(targetLine(target, "Powered State"), targetLine(target, "powered")))) {
            drawProjectedMarker(context, cx, cy, size, -visual.outlineWidth() * 0.62D, visual.outlineHeight() * 0.5D, 0.0D, 0xFFA7003A, 2);
        }
        if (booleanValue(firstKnown(targetLine(target, "Waterlogged State"), targetLine(target, "waterlogged"))) || !targetLine(target, "Waterlogged/Fluid").equals("none")) {
            drawProjectedMarker(context, cx, cy, size, 0.0D, 0.08D, visual.outlineDepth() * 0.62D, 0xFF0085A4, 2);
        }
    }

    private static void drawBlockStateFaceHints(DrawContext context, int cx, int cy, int size, F3TargetSnapshot target) {
        drawBlockStateFaceHints(context, cx, cy, size, target, blockVisualData(target));
    }

    private static double blockWidthScale(F3TargetSnapshot target) {
        return blockVisualFootprint(target);
    }

    private static double directionRatio(String value) {
        String lower = safeValue(value).toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "north" -> 0.16D;
            case "east" -> 0.33D;
            case "south" -> 0.50D;
            case "west" -> 0.66D;
            case "up" -> 0.83D;
            case "down" -> 1.0D;
            default -> 0.0D;
        };
    }

    private static double axisRatio(String value) {
        String lower = safeValue(value).toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "x" -> 0.33D;
            case "y" -> 0.66D;
            case "z" -> 1.0D;
            default -> 0.0D;
        };
    }

    private static double halfRatio(String value) {
        String lower = safeValue(value).toLowerCase(Locale.ROOT);
        if (lower.contains("upper") || lower.contains("top")) {
            return 1.0D;
        }
        if (lower.contains("lower") || lower.contains("bottom")) {
            return 0.45D;
        }
        return 0.0D;
    }

    private static ProjectedPoint projectWorldPoint(Vec3d eye, double x, double y, double z, double yaw, double pitch, int scale) {
        return projectWorldOffset(x - eye.x, y - eye.y, z - eye.z, yaw, pitch, scale, Math.max(8.0D, ghostProjectionRange));
    }

    private static void drawWorldLine(DrawContext context, Vec3d eye, double x1, double y1, double z1, double x2, double y2, double z2, double yaw, double pitch, int cx, int cy, int color, int scale) {
        ProjectedPoint a = projectWorldPoint(eye, x1, y1, z1, yaw, pitch, scale);
        ProjectedPoint b = projectWorldPoint(eye, x2, y2, z2, yaw, pitch, scale);
        if (Math.abs(a.x()) > scale + 18 && Math.abs(b.x()) > scale + 18) {
            return;
        }
        if (Math.abs(a.y()) > scale + 18 && Math.abs(b.y()) > scale + 18) {
            return;
        }
        drawLine(context, cx + clamp(a.x(), -scale - 18, scale + 18), cy + clamp(a.y(), -scale - 18, scale + 18), cx + clamp(b.x(), -scale - 18, scale + 18), cy + clamp(b.y(), -scale - 18, scale + 18), color);
    }

    private static boolean playerHeadroomClear(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return false;
        }
        BlockPos head = BlockPos.ofFloored(client.player.getX(), client.player.getY() + client.player.getHeight() + 0.12D, client.player.getZ());
        return client.world.getBlockState(head).getCollisionShape(client.world, head).isEmpty();
    }

    private static double nextGapClearance(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return 0.0D;
        }
        Vec3d look = client.player.getRotationVec(1.0F);
        double score = 0.0D;
        for (int i = 1; i <= 3; i++) {
            BlockPos foot = BlockPos.ofFloored(client.player.getX() + look.x * i, client.player.getY() - 0.08D, client.player.getZ() + look.z * i);
            boolean support = !client.world.getBlockState(foot.down()).getCollisionShape(client.world, foot.down()).isEmpty();
            boolean space = client.world.getBlockState(foot).getCollisionShape(client.world, foot).isEmpty();
            if (support && space) {
                score += 1.0D / 3.0D;
            }
        }
        return clampRatio(score);
    }

    private static double currentFrictionEstimate(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return 0.6D;
        }
        BlockPos foot = BlockPos.ofFloored(client.player.getX(), client.player.getY() - 0.08D, client.player.getZ());
        BlockState state = client.world.getBlockState(foot.down());
        String key = ghostBlockKey(state);
        double slipperiness = clampDouble(state.getBlock().getSlipperiness(), 0.05D, 1.25D);
        if (slipperiness != 0.6D) {
            return slipperiness;
        }
        if (ghostHasAny(key, "ice", "slippery", "frozen", "packed_ice", "blue_ice")) {
            return 0.98D;
        }
        if (ghostHasAny(key, "slime", "bounce", "rubber")) {
            return 0.80D;
        }
        if (ghostHasAny(key, "honey", "sticky", "glue", "resin")) {
            return 0.40D;
        }
        if (ghostHasAny(key, "soul_sand", "mud", "slow", "quicksand", "tar")) {
            return 0.45D;
        }
        return 0.60D;
    }

    private static String chunkHealthLabel(double entities, double blockEntities, double danger) {
        if (blockEntities > 0.62D) {
            return "tile-heavy";
        }
        if (entities > 0.62D) {
            return "entity-heavy";
        }
        if (danger > 0.72D) {
            return "render-heavy";
        }
        if (danger > 0.38D) {
            return "busy";
        }
        return "quiet";
    }

    private static int chunkHealthColor(double danger) {
        if (danger > 0.72D) {
            return 0xFFA7003A;
        }
        if (danger > 0.48D) {
            return 0xFFE06A21;
        }
        if (danger > 0.28D) {
            return 0xFFE3B735;
        }
        return 0xFF2DA700;
    }

    private static int neighborProfileColor(MinecraftClient client, BlockState state, BlockPos pos) {
        if (client != null && client.world != null && !client.world.getOtherEntities(null, new Box(pos)).isEmpty()) {
            return 0xFFE06A21;
        }
        if (state.isAir()) {
            return 0x665A636E;
        }
        if (!state.getFluidState().isEmpty()) {
            return state.getFluidState().isStill() ? 0xFF0085A4 : 0xFF55C4FF;
        }
        if (state.emitsRedstonePower() || state.hasComparatorOutput()) {
            return 0xFFA7003A;
        }
        if (state.hasBlockEntity()) {
            return 0xFFB47CFF;
        }
        if (state.getLuminance() > 0) {
            return 0xFFE3B735;
        }
        if (state.getCollisionShape(client.world, pos).isEmpty()) {
            return 0xFF7FC8C2;
        }
        return 0xFF8FC5FF;
    }

    private static void drawEntityBehaviorStrip(DrawContext context, TextRenderer text, int x, int y, int w, F3TargetSnapshot target) {
        BehaviorDot[] dots = entityBehaviorDots(target);
        int active = 0;
        for (BehaviorDot dot : dots) {
            if (dot.active()) {
                active++;
            }
        }
        int cursor = x;
        int maxX = x + w;
        for (BehaviorDot dot : dots) {
            if (!dot.active()) {
                continue;
            }
            String label = dot.label();
            int chipW = Math.min(28, Math.max(12, text.getWidth(label) + 7));
            if (cursor + chipW > maxX) {
                break;
            }
            context.fill(cursor, y, cursor + chipW, y + 9, withAlpha(dot.color(), 120));
            context.fill(cursor, y + 7, cursor + chipW, y + 9, withAlpha(dot.color(), 220));
            drawText(context, text, label, cursor + 3, y + 1, 0xFFE6EDF5);
            cursor += chipW + 3;
        }
        if (active == 0) {
            drawText(context, text, "idle stable", x, y + 1, 0xFF8FA0B8);
        }
    }

    private static void drawEntityBehaviorOrbit(DrawContext context, TextRenderer text, int cx, int cy, int r, F3TargetSnapshot target) {
        BehaviorDot[] dots = entityBehaviorDots(target);
        for (int i = 0; i < dots.length; i++) {
            BehaviorDot dot = dots[i];
            double angle = Math.PI * 2.0D * i / Math.max(1, dots.length) - Math.PI / 2.0D;
            int px = cx + (int) Math.round(Math.cos(angle) * r);
            int py = cy + (int) Math.round(Math.sin(angle) * r);
            int color = dot.active() ? dot.color() : withAlpha(0xFF8FA0B8, 70);
            context.fill(px - 2, py - 2, px + 3, py + 3, withAlpha(color, dot.active() ? 210 : 90));
            context.fill(px - 1, py - 1, px + 2, py + 2, color);
            if (dot.active() && r > 18) {
                drawText(context, text, dot.label(), px + 3, py - 3, color);
            }
        }
    }

    private static BehaviorDot[] entityBehaviorDots(F3TargetSnapshot target) {
        boolean hasTarget = !targetLine(target, "Target").equals("none") && !targetLine(target, "Target").equals("unknown");
        boolean path = booleanValue(targetLine(target, "Path Active")) || !targetLine(target, "Path Length").equals("unknown");
        boolean attacking = hasTarget && targetRatio(target, "Attack Damage", 20.0D) > 0.0D;
        boolean fleeing = safeValue(targetLine(target, "Intent")).toLowerCase(Locale.ROOT).contains("flee");
        boolean idle = booleanValue(targetLine(target, "Navigation Idle"));
        boolean looking = !targetLine(target, "Look Control").equals("unknown");
        boolean jumping = !booleanValue(targetLine(target, "On Ground"));
        boolean swimming = booleanValue(targetLine(target, "Swimming")) || booleanValue(targetLine(target, "Touching Water"));
        boolean burning = booleanValue(targetLine(target, "Fire")) || booleanValue(targetLine(target, "Frozen"));
        return new BehaviorDot[]{
                new BehaviorDot("T", hasTarget, 0xFFE06A21),
                new BehaviorDot("P", path, 0xFFB47CFF),
                new BehaviorDot("A", attacking, 0xFFA7003A),
                new BehaviorDot("F", fleeing, 0xFFE3B735),
                new BehaviorDot("I", idle, 0xFF8FA0B8),
                new BehaviorDot("L", looking, 0xFF8FC5FF),
                new BehaviorDot("J", jumping, 0xFF7FC8C2),
                new BehaviorDot("S", swimming, 0xFF0085A4),
                new BehaviorDot("B", burning, 0xFFE06A21)
        };
    }

    private record BlockVisualData(double outlineWidth, double outlineDepth, double outlineHeight, double collisionWidth, double collisionDepth, double collisionHeight, boolean collisionEmpty, double power, double light, double fluid, double statePressure, int primaryStateColor) {
    }

    private record JumpPreviewState(double horizontal, double vertical, boolean locked) {
    }

    private record PanelBounds(int x, int y, int w, int h) {
    }

    private record PieSlice(String label, double ratio, int color) {
    }

    private record GraphSignal(String label, String value, double ratio, int color) {
    }

    private record GraphCard(String id, int height) {
    }

    private record BehaviorDot(String label, boolean active, int color) {
    }

    private record TerrainColumn(int y, int color, int roofY, int waterY, int underY, int trunkY, int passableY, double passableHeight, int passableColor) {
    }

    private record GhostTerrainField(long bucket, int centerX, int centerZ, int floorY, int radius, int scanHeight, int minX, int minZ, int side, TerrainColumn[][] columns) {
    }

    private record AxisProjection(int x, int y, double depth) {
    }

    private record ProjectedPoint(int x, int y, double depth) {
    }

    private enum Chart {
        FPS,
        FRAME,
        MEMORY,
        WORLD
    }
}
