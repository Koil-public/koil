package com.spirit.mixin.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.spirit.client.gui.random.noise.ChunkNoiseGenerator;
import net.minecraft.client.render.*;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldRenderer.class)
public class MixinCloudRenderer {

    @Unique
    private static final int SIZE = 256;
    @Unique
    private static final boolean[][] CLOUDS = generateClouds();

    private static @Unique boolean[][] generateClouds() {
        boolean[][] map = new boolean[SIZE][SIZE];
        ChunkNoiseGenerator noise = new ChunkNoiseGenerator(2);

        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                map[x][z] = noise.sample(x / 32.0, z / 32.0) > 0.55;
            }
        }

        return map;
    }

    @Unique
    private static void addCube(BufferBuilder builder, float x, float y, float z, float size, float height, float r, float g, float b) {
        float x2 = x + size;
        float y2 = y + height;
        float z2 = z + size;

        float alpha = 0.85f;

        // Top
        vertex(builder, x, y2, z2, r, g, b, alpha, 0, 1, 0);
        vertex(builder, x2, y2, z2, r, g, b, alpha, 0, 1, 0);
        vertex(builder, x2, y2, z, r, g, b, alpha, 0, 1, 0);
        vertex(builder, x, y2, z, r, g, b, alpha, 0, 1, 0);

        // Bottom
        vertex(builder, x, y, z, r * .7f, g * .7f, b * .7f, alpha, 0, -1, 0);
        vertex(builder, x2, y, z, r * .7f, g * .7f, b * .7f, alpha, 0, -1, 0);
        vertex(builder, x2, y, z2, r * .7f, g * .7f, b * .7f, alpha, 0, -1, 0);
        vertex(builder, x, y, z2, r * .7f, g * .7f, b * .7f, alpha, 0, -1, 0);

        // North
        vertex(builder, x, y, z, r, g, b, alpha, 0, 0, -1);
        vertex(builder, x2, y, z, r, g, b, alpha, 0, 0, -1);
        vertex(builder, x2, y2, z, r, g, b, alpha, 0, 0, -1);
        vertex(builder, x, y2, z, r, g, b, alpha, 0, 0, -1);

        // South
        vertex(builder, x2, y, z2, r, g, b, alpha, 0, 0, 1);
        vertex(builder, x, y, z2, r, g, b, alpha, 0, 0, 1);
        vertex(builder, x, y2, z2, r, g, b, alpha, 0, 0, 1);
        vertex(builder, x2, y2, z2, r, g, b, alpha, 0, 0, 1);

        // West
        vertex(builder, x, y, z2, r, g, b, alpha, -1, 0, 0);
        vertex(builder, x, y, z, r, g, b, alpha, -1, 0, 0);
        vertex(builder, x, y2, z, r, g, b, alpha, -1, 0, 0);
        vertex(builder, x, y2, z2, r, g, b, alpha, -1, 0, 0);

        // East
        vertex(builder, x2, y, z, r, g, b, alpha, 1, 0, 0);
        vertex(builder, x2, y, z2, r, g, b, alpha, 1, 0, 0);
        vertex(builder, x2, y2, z2, r, g, b, alpha, 1, 0, 0);
        vertex(builder, x2, y2, z, r, g, b, alpha, 1, 0, 0);
    }

    @Unique
    private static void vertex(BufferBuilder builder, float x, float y, float z, float r, float g, float b, float a, float nx, float ny, float nz) {
        builder.vertex(x, y, z)
            .texture(0, 0)
            .color(r, g, b, a)
            .normal(nx, ny, nz)
            .next();
    }

    @Inject(
        method = "renderClouds(Lnet/minecraft/client/render/BufferBuilder;DDDLnet/minecraft/util/math/Vec3d;)Lnet/minecraft/client/render/BufferBuilder$BuiltBuffer;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void koil$renderClouds(BufferBuilder builder, double x, double y, double z, Vec3d color, CallbackInfoReturnable<BufferBuilder.BuiltBuffer> cir) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorNormalProgram);

        builder.begin(
            VertexFormat.DrawMode.QUADS,
            VertexFormats.POSITION_TEXTURE_COLOR_NORMAL
        );

        int cloudX = MathHelper.floor(x);
        int cloudZ = MathHelper.floor(z);

        float r = (float) color.x;
        float g = (float) color.y;
        float b = (float) color.z;

        for (int ax = -10; x < 10; x++) {
            for (int az = -10; z < 10; z++) {
                addCube(builder, ax * 8, 128, az * 8, 8, 4, 1, 1, 1);
            }
        }

        cir.setReturnValue(builder.end());
    }
}
