package com.spirit.koil.api.design.sprite.debug;

import com.spirit.koil.api.design.sprite.actor.Actor;
import com.spirit.koil.api.design.sprite.actor.FallingBlockActor;
import com.spirit.koil.api.design.sprite.actor.ItemActor;
import com.spirit.koil.api.design.sprite.actor.PrimedTntActor;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import com.spirit.koil.api.design.sprite.world.Scene;
import com.spirit.koil.api.design.sprite.minecraft.MinecraftStateCodec;
import com.spirit.koil.api.design.sprite.render.MinecraftTextureFrames;
import com.spirit.koil.api.design.sprite.systems.SceneLightingSystem;
import net.minecraft.block.Blocks;
import net.minecraft.block.ConnectingBlock;
import net.minecraft.fluid.Fluids;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.StairShape;
import net.minecraft.block.enums.Thickness;
import net.minecraft.block.enums.WallShape;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic detached engine smoke tests. This never reads MinecraftClient.world.
 * It is intended for diagnostics from menus/title screens as well as development.
 */
public final class SceneSelfTest {
    public record Report(boolean passed, List<String> failures, int checks) { }

    private SceneSelfTest() { }

    public static Report run() {
        List<String> failures = new ArrayList<>();
        int checks = 0;

        checks++;
        try { testCellSeams(); } catch (RuntimeException | AssertionError error) { failures.add("cell_seams: " + error.getMessage()); }
        checks++;
        try { testFluidFlow(); } catch (RuntimeException | AssertionError error) { failures.add("fluid_flow: " + error.getMessage()); }
        checks++;
        try { testWaterloggedFluidPipeline(); } catch (RuntimeException | AssertionError error) { failures.add("waterlogged_fluid: " + error.getMessage()); }
        checks++;
        try { testFluidPhysics(); } catch (RuntimeException | AssertionError error) { failures.add("fluid_physics: " + error.getMessage()); }
        checks++;
        try { testFallingBlockRoundTrip(); } catch (RuntimeException | AssertionError error) { failures.add("falling_block: " + error.getMessage()); }
        checks++;
        try { testFallingBlockMetadataRoundTrip(); } catch (RuntimeException | AssertionError error) { failures.add("falling_metadata: " + error.getMessage()); }
        checks++;
        try { testFlintAndSteelPrimesTnt(); } catch (RuntimeException | AssertionError error) { failures.add("flint_tnt: " + error.getMessage()); }
        checks++;
        try { testAuthoredLifetime(); } catch (RuntimeException | AssertionError error) { failures.add("authored_lifetime: " + error.getMessage()); }
        checks++;
        try { testDepthIsolation(); } catch (RuntimeException | AssertionError error) { failures.add("depth_isolation: " + error.getMessage()); }
        checks++;
        try { testDetachedLighting(); } catch (RuntimeException | AssertionError error) { failures.add("detached_lighting: " + error.getMessage()); }
        checks++;
        try { testFlowFrameNormalization(); } catch (RuntimeException | AssertionError error) { failures.add("flow_frame: " + error.getMessage()); }
        checks++;
        try { testFenceConnections(); } catch (RuntimeException | AssertionError error) { failures.add("fence_connections: " + error.getMessage()); }
        checks++;
        try { testRedstoneConnections(); } catch (RuntimeException | AssertionError error) { failures.add("redstone_connections: " + error.getMessage()); }
        checks++;
        try { testStairConnections(); } catch (RuntimeException | AssertionError error) { failures.add("stair_connections: " + error.getMessage()); }
        checks++;
        try { testPaneConnections(); } catch (RuntimeException | AssertionError error) { failures.add("pane_connections: " + error.getMessage()); }
        checks++;
        try { testWallGateConnections(); } catch (RuntimeException | AssertionError error) { failures.add("wall_gate_connections: " + error.getMessage()); }
        checks++;
        try { testWallTallShape(); } catch (RuntimeException | AssertionError error) { failures.add("wall_tall_shape: " + error.getMessage()); }
        checks++;
        try { testWallAirGateSafety(); } catch (RuntimeException | AssertionError error) { failures.add("wall_air_gate_safety: " + error.getMessage()); }
        checks++;
        try { testWallToWallAndPaneConnections(); } catch (RuntimeException | AssertionError error) { failures.add("wall_native_neighbors: " + error.getMessage()); }
        checks++;
        try { testFenceWallSideViewBridge(); } catch (RuntimeException | AssertionError error) { failures.add("fence_wall_sideview_bridge: " + error.getMessage()); }
        checks++;
        try { testDoorMultipart(); } catch (RuntimeException | AssertionError error) { failures.add("door_multipart: " + error.getMessage()); }
        checks++;
        try { testTallPlantMultipart(); } catch (RuntimeException | AssertionError error) { failures.add("tall_plant_multipart: " + error.getMessage()); }
        checks++;
        try { testPitcherCropMultipartThreshold(); } catch (RuntimeException | AssertionError error) { failures.add("pitcher_crop_multipart: " + error.getMessage()); }
        checks++;
        try { testVerticalPlantBodyTips(); } catch (RuntimeException | AssertionError error) { failures.add("vertical_plant_body_tips: " + error.getMessage()); }
        checks++;
        try { testPointedDripstoneThickness(); } catch (RuntimeException | AssertionError error) { failures.add("pointed_dripstone_thickness: " + error.getMessage()); }
        checks++;
        try { testChorusConnections(); } catch (RuntimeException | AssertionError error) { failures.add("chorus_connections: " + error.getMessage()); }
        checks++;
        try { testProjectionVisibleAttachmentFaces(); } catch (RuntimeException | AssertionError error) { failures.add("projection_visible_faces: " + error.getMessage()); }
        checks++;
        try { testStairOrientationCycle(); } catch (RuntimeException | AssertionError error) { failures.add("stair_orientation: " + error.getMessage()); }
        checks++;
        try { testFluidSurfaceSlope(); } catch (RuntimeException | AssertionError error) { failures.add("fluid_surface_slope: " + error.getMessage()); }

        return new Report(failures.isEmpty(), List.copyOf(failures), checks);
    }

    private static void testCellSeams() {
        Scene scene = new Scene();
        float size = scene.projection().cellPixels();
        float a = scene.projection().cellCenterScreenX(new SceneCellPos(0, 0, 0));
        float b = scene.projection().cellCenterScreenX(new SceneCellPos(1, 0, 0));
        assertTrue(Math.abs((b - a) - size) < 0.0001F, "adjacent centers are not exactly one cell apart");
        assertTrue(Math.abs((a + size * 0.5F) - (b - size * 0.5F)) < 0.0001F,
                "adjacent block edges do not meet exactly");
    }

    private static void testFluidFlow() {
        Scene scene = new Scene();
        scene.physics().setViewportCollision(false);
        for (int x = -3; x <= 3; x++) scene.blocks().set(new SceneCellPos(x, 0, 0), Blocks.STONE.getDefaultState());
        scene.fluidSystem().setSceneSource(new SceneCellPos(0, 3, 0), Fluids.WATER);
        advanceGameTicks(scene, 30);
        assertTrue(scene.fluids().get(new SceneCellPos(0, 2, 0)) != null, "water did not fall one cell");
        assertTrue(scene.fluids().get(new SceneCellPos(1, 1, 0)) != null, "water did not spread sideways after support");
    }

    private static void testWaterloggedFluidPipeline() {
        Scene scene = new Scene();
        SceneCellPos pos = new SceneCellPos(0, 1, 0);
        scene.environment().setWaterColor(0x1268A8);
        scene.blocks().set(pos, Blocks.OAK_SLAB.getDefaultState().with(Properties.WATERLOGGED, true));
        advanceGameTicks(scene, 1);
        assertTrue(scene.fluids().getFluidState(pos).isOf(Fluids.WATER),
                "waterlogged block did not import embedded water into FluidGrid");
        assertTrue(scene.environment().waterColor() == 0x1268A8,
                "waterlogged fluid path changed detached biome water tint");
    }

    private static void testFluidPhysics() {
        Scene scene = new Scene();
        scene.physics().setViewportCollision(false);
        scene.blocks().set(new SceneCellPos(0, -1, 0), Blocks.STONE.getDefaultState());
        scene.fluidSystem().setSceneSource(new SceneCellPos(0, 0, 0), Fluids.WATER);
        ItemActor wet = new ItemActor(1L, Actor.Authority.SCENE,
                new ItemStack(Items.IRON_INGOT), 0.0F, 0.0F, 0);
        ItemActor dry = new ItemActor(2L, Actor.Authority.SCENE,
                new ItemStack(Items.IRON_INGOT), 64.0F, 0.0F, 0);
        scene.actors().put(wet);
        scene.actors().put(dry);
        for (int i = 0; i < 10; i++) scene.advanceFrame(1.0F / 60.0F);
        assertTrue(wet.velocityY() < dry.velocityY(), "water did not alter item velocity");
    }

    private static void testFallingBlockRoundTrip() {
        Scene scene = new Scene();
        scene.physics().setViewportCollision(false);
        scene.blocks().set(new SceneCellPos(8, 0, 0), Blocks.STONE.getDefaultState());
        scene.blocks().set(new SceneCellPos(8, 3, 0), Blocks.SAND.getDefaultState());
        for (int i = 0; i < 180; i++) scene.advanceFrame(1.0F / 60.0F);
        boolean falling = scene.actors().actors().stream().anyMatch(actor -> actor instanceof FallingBlockActor);
        boolean landed = false;
        for (BlockGrid.Entry entry : scene.blocks().entries()) {
            if (entry.cell().blockState() != null && entry.cell().blockState().isOf(Blocks.SAND)) {
                landed = true;
                break;
            }
        }
        assertTrue(!falling && landed, "sand did not complete grid -> actor -> grid transition");
    }

    private static void testFallingBlockMetadataRoundTrip() {
        Scene scene = new Scene();
        scene.physics().setViewportCollision(false);
        SceneCellPos sand = new SceneCellPos(10, 3, 0);
        scene.blocks().set(new SceneCellPos(10, 0, 0), Blocks.STONE.getDefaultState());
        scene.blocks().set(sand, Blocks.SAND.getDefaultState());
        scene.blocks().putRuntimeData(sand, "mass", "2.5");
        scene.blocks().putRuntimeData(sand, "surface_friction", "0.37");
        scene.blocks().putRuntimeData(sand, "custom_probe", "preserved");
        for (int i = 0; i < 180; i++) scene.advanceFrame(1.0F / 60.0F);
        BlockGrid.Entry landed = scene.blocks().entries().stream()
                .filter(entry -> entry.cell().blockState() != null && entry.cell().blockState().isOf(Blocks.SAND))
                .findFirst().orElse(null);
        assertTrue(landed != null, "authored sand did not land");
        assertTrue("preserved".equals(landed.cell().runtime("custom_probe", "")),
                "falling block lost custom runtime data");
        assertTrue(Math.abs(landed.cell().runtimeFloat("mass", 0.0F) - 2.5F) < 0.001F,
                "falling block lost mass override");
        assertTrue(Math.abs(landed.cell().runtimeFloat("surface_friction", 0.0F) - 0.37F) < 0.001F,
                "falling block lost friction override");
    }

    private static void testFlintAndSteelPrimesTnt() {
        Scene scene = new Scene();
        SceneCellPos tnt = new SceneCellPos(0, 0, 0);
        scene.blocks().set(tnt, Blocks.TNT.getDefaultState());
        long id = scene.actors().allocateNativeId();
        ItemActor flint = new ItemActor(id, Actor.Authority.SCENE, new ItemStack(Items.FLINT_AND_STEEL),
                scene.projection().cellCenterScreenX(new SceneCellPos(-1, 0, 0)),
                scene.projection().cellCenterScreenY(new SceneCellPos(-1, 0, 0)), 0);
        scene.actors().put(flint);
        boolean handled = scene.itemInteractions().useOnBlock(id, tnt);
        assertTrue(handled, "flint and steel interaction was rejected");
        assertTrue(scene.blocks().getBlockState(tnt).isAir(), "TNT cell was not consumed when primed");
        assertTrue(scene.actors().actors().stream().anyMatch(actor -> actor instanceof PrimedTntActor),
                "primed TNT actor was not created");
    }

    private static void testAuthoredLifetime() {
        Scene scene = new Scene();
        SceneCellPos pos = new SceneCellPos(0, 0, 0);
        scene.blocks().set(pos, Blocks.STONE.getDefaultState());
        scene.blocks().putRuntimeData(pos, "lifetime", "0.25");
        scene.blocks().restartLifetime(pos);
        advanceGameTicks(scene, 6);
        assertTrue(scene.blocks().getBlockState(pos).isAir(), "authored block lifetime did not expire");
    }

    private static void testDepthIsolation() {
        Scene scene = new Scene();
        assertTrue(!scene.projection().crossLayerInteractions(),
                "cross-layer interactions must be isolated by default");

        // Neighbor-derived state must not see a block that exists only on the
        // hidden Z slice directly behind it.
        SceneCellPos frontFence = new SceneCellPos(0, 0, 0);
        SceneCellPos rearFence = new SceneCellPos(0, 0, 1);
        scene.blocks().set(frontFence, Blocks.OAK_FENCE.getDefaultState());
        scene.blocks().set(rearFence, Blocks.OAK_FENCE.getDefaultState());
        scene.neighborStates().reconcile();
        String isolated = MinecraftStateCodec.encode(scene.blocks().getBlockState(frontFence));
        assertTrue(isolated.contains("south=false"),
                "fence connected to another hidden depth layer while isolation was enabled");

        // Explicit opt-in is still available for a scene that intentionally wants
        // real 3D hidden-axis neighbor behavior. Projection revision must trigger
        // reconciliation even though the block grid itself did not change.
        scene.projection().setCrossLayerInteractions(true);
        scene.neighborStates().reconcile();
        String bridged = MinecraftStateCodec.encode(scene.blocks().getBlockState(frontFence));
        assertTrue(bridged.contains("south=true"),
                "explicit cross-layer interaction did not reconnect hidden-axis neighbors");
        scene.projection().setCrossLayerInteractions(false);
        scene.neighborStates().reconcile();
        isolated = MinecraftStateCodec.encode(scene.blocks().getBlockState(frontFence));
        assertTrue(isolated.contains("south=false"),
                "disabling cross-layer interaction did not restore isolation");

        // A support block on another depth slice must never keep falling sand in
        // place on the active slice. Physics already hashes by actor depth; this
        // additionally verifies grid->falling-actor promotion uses the same rule.
        Scene falling = new Scene();
        falling.physics().setViewportCollision(false);
        SceneCellPos sand = new SceneCellPos(4, 2, 0);
        falling.blocks().set(new SceneCellPos(4, 1, 1), Blocks.STONE.getDefaultState());
        falling.blocks().set(sand, Blocks.SAND.getDefaultState());
        advanceGameTicks(falling, 2);
        assertTrue(falling.blocks().getBlockState(sand).isAir(),
                "falling block used support from another depth layer");
        assertTrue(falling.actors().actors().stream().anyMatch(actor -> actor instanceof FallingBlockActor),
                "isolated unsupported sand was not promoted to a falling actor");

        // In a top projection Minecraft UP/DOWN is the hidden edit-layer axis.
        // Fluid must stay inside its slice until cross-layer behavior is opted in.
        Scene top = new Scene();
        top.projection().setMode(com.spirit.koil.api.design.sprite.core.SceneProjection.Mode.XZ_TOP);
        SceneCellPos source = new SceneCellPos(0, 0, 0);
        SceneCellPos belowLayer = new SceneCellPos(0, -1, 0);
        top.fluidSystem().setSceneSource(source, Fluids.WATER);
        advanceGameTicks(top, 6);
        assertTrue(top.fluids().get(belowLayer) == null,
                "fluid crossed the hidden depth axis while layers were isolated");
        top.projection().setCrossLayerInteractions(true);
        advanceGameTicks(top, 6);
        assertTrue(top.fluids().get(belowLayer) != null,
                "explicit cross-layer fluid interaction did not take effect");

        // Lighting follows the same isolation contract. A source on Z=0 must not
        // illuminate Z=1 unless the scene explicitly links its depth slices.
        Scene lighting = new Scene();
        SceneCellPos lightSource = new SceneCellPos(8, 8, 0);
        SceneCellPos rearLightCell = new SceneCellPos(8, 8, 1);
        lighting.blocks().set(lightSource, Blocks.GLOWSTONE.getDefaultState());
        assertTrue(lighting.lighting().blockLight(rearLightCell) == 0,
                "block light leaked into another isolated depth layer");
        lighting.projection().setCrossLayerInteractions(true);
        assertTrue(lighting.lighting().blockLight(rearLightCell) > 0,
                "explicit cross-layer lighting did not propagate");
    }



    private static void testDetachedLighting() {
        Scene scene = new Scene();
        scene.environment().setSkyLight(15);
        scene.environment().setBlockLight(0);
        SceneCellPos lamp = new SceneCellPos(0, 0, 0);
        SceneCellPos neighbor = new SceneCellPos(1, 0, 0);
        scene.blocks().set(lamp, Blocks.GLOWSTONE.getDefaultState());
        assertTrue(scene.lighting().blockLight(lamp) == 15, "glowstone did not emit light 15");
        assertTrue(scene.lighting().blockLight(neighbor) >= 13, "block light did not propagate to neighboring cell");
        SceneLightingSystem.LightColor glow = scene.lighting().blockColor(lamp);
        assertTrue(glow.r() > glow.b(), "glowstone source was not warm RGB light");

        Scene soul = new Scene();
        SceneCellPos soulLamp = new SceneCellPos(0, 0, 0);
        soul.blocks().set(soulLamp, Blocks.SOUL_LANTERN.getDefaultState());
        SceneLightingSystem.LightColor soulColor = soul.lighting().blockColor(soulLamp);
        assertTrue(soulColor.b() > soulColor.r(), "soul lantern did not emit blue/cyan RGB light");

        Scene custom = new Scene();
        SceneCellPos customLamp = new SceneCellPos(0, 0, 0);
        custom.blocks().set(customLamp, Blocks.GLOWSTONE.getDefaultState());
        custom.blocks().putRuntimeData(customLamp, "light_color", "#00FF40");
        SceneLightingSystem.LightColor customColor = custom.lighting().blockColor(customLamp);
        assertTrue(customColor.g() > customColor.r() && customColor.g() > customColor.b(),
                "authored light_color override was ignored");

        Scene air = new Scene();
        air.blocks().set(new SceneCellPos(0, 0, 0), Blocks.GLOWSTONE.getDefaultState());
        SceneLightingSystem.LightColor airTwo = air.lighting().blockColor(new SceneCellPos(2, 0, 0));

        Scene filtered = new Scene();
        filtered.blocks().set(new SceneCellPos(0, 0, 0), Blocks.GLOWSTONE.getDefaultState());
        filtered.fluidSystem().setSceneSource(new SceneCellPos(1, 0, 0), Fluids.WATER);
        SceneLightingSystem.LightColor waterTwo = filtered.lighting().blockColor(new SceneCellPos(2, 0, 0));
        float airRatio = airTwo.r() <= 0.0001F ? 0.0F : airTwo.b() / airTwo.r();
        float waterRatio = waterTwo.r() <= 0.0001F ? 0.0F : waterTwo.b() / waterTwo.r();
        assertTrue(waterRatio > airRatio, "water did not filter RGB light toward blue");

        Scene blocked = new Scene();
        blocked.blocks().set(new SceneCellPos(0, 0, 0), Blocks.GLOWSTONE.getDefaultState());
        blocked.blocks().set(new SceneCellPos(1, 0, 0), Blocks.STONE.getDefaultState());
        SceneLightingSystem.LightColor behindStone = blocked.lighting().blockColor(new SceneCellPos(2, 0, 0));
        assertTrue(behindStone.maxComponent() < airTwo.maxComponent(),
                "solid mask did not attenuate light more than air");

        Scene shadow = new Scene();
        shadow.environment().setSkyLight(15);
        shadow.blocks().set(new SceneCellPos(0, 2, 0), Blocks.STONE.getDefaultState());
        int underRoof = shadow.lighting().skyLight(new SceneCellPos(0, 1, 0));
        int openSky = shadow.lighting().skyLight(new SceneCellPos(1, 1, 0));
        assertTrue(underRoof < openSky, "opaque roof did not attenuate detached sky light");

        Scene depth = new Scene();
        depth.blocks().set(new SceneCellPos(0, 0, 0), Blocks.STONE.getDefaultState());
        depth.blocks().set(new SceneCellPos(0, 0, 4), Blocks.STONE.getDefaultState());
        float near = depth.lighting().brightness(new SceneCellPos(0, 0, 4));
        float far = depth.lighting().brightness(new SceneCellPos(0, 0, 0));
        assertTrue(Math.abs(near - far) < 0.02F,
                "scene depth incorrectly changed lighting for otherwise identical isolated slices");

        Scene redstone = new Scene();
        SceneCellPos redLampPos = new SceneCellPos(0, 0, 0);
        redstone.blocks().set(redLampPos, Blocks.REDSTONE_LAMP.getDefaultState().with(Properties.LIT, true));
        assertTrue(redstone.lighting().blockLight(redLampPos) == 15,
                "lit redstone lamp did not use native BlockState luminance");
        SceneLightingSystem.LightColor redLamp = redstone.lighting().blockColor(redLampPos);
        assertTrue(redLamp.r() > redLamp.b(), "redstone lamp did not resolve a warm emission color");

        Scene openSkyScene = new Scene();
        openSkyScene.environment().setSkyLight(15);
        openSkyScene.blocks().set(new SceneCellPos(0, 0, 0), Blocks.STONE.getDefaultState());
        assertTrue(openSkyScene.lighting().skyLight(new SceneCellPos(0, 8, 0)) == 15,
                "vertical skylight decayed through open air");
    }

    private static void testFlowFrameNormalization() {
        MinecraftTextureFrames.View flow = new MinecraftTextureFrames.View(0, 64, 32, 32, 32, 1024, 0, 96, 0.5F, true);
        MinecraftTextureFrames.View still = new MinecraftTextureFrames.View(0, 16, 16, 16, 16, 512, 0, 32, 0.5F, true);
        MinecraftTextureFrames.View normalized = MinecraftTextureFrames.normalizeToReference(flow, still);
        assertTrue(normalized.width() == 16 && normalized.height() == 16, "32px flow frame was not normalized to one 16px tile");
        assertTrue(normalized.x() == 8 && normalized.y() == 72, "flow tile crop is not centered inside source frame");
    }

    private static void testFenceConnections() {
        Scene scene = new Scene();
        SceneCellPos a = new SceneCellPos(0, 0, 0);
        SceneCellPos b = new SceneCellPos(1, 0, 0);
        scene.blocks().set(a, Blocks.OAK_FENCE.getDefaultState());
        scene.blocks().set(b, Blocks.OAK_FENCE.getDefaultState());
        scene.neighborStates().reconcile();
        assertTrue(MinecraftStateCodec.encode(scene.blocks().getBlockState(a)).contains("east=true"), "fence did not connect east");
        assertTrue(MinecraftStateCodec.encode(scene.blocks().getBlockState(b)).contains("west=true"), "fence did not connect west");
        scene.blocks().remove(b);
        scene.neighborStates().reconcile();
        assertTrue(MinecraftStateCodec.encode(scene.blocks().getBlockState(a)).contains("east=false"), "fence did not disconnect after neighbor removal");
    }

    private static void testRedstoneConnections() {
        Scene scene = new Scene();
        SceneCellPos a = new SceneCellPos(0, 0, 0);
        SceneCellPos b = new SceneCellPos(1, 0, 0);
        scene.blocks().set(a, Blocks.REDSTONE_WIRE.getDefaultState());
        scene.blocks().set(b, Blocks.REDSTONE_WIRE.getDefaultState());
        scene.neighborStates().reconcile();
        assertTrue(MinecraftStateCodec.encode(scene.blocks().getBlockState(a)).contains("east=side"), "redstone did not connect east");
        scene.blocks().remove(b);
        scene.neighborStates().reconcile();
        assertTrue(MinecraftStateCodec.encode(scene.blocks().getBlockState(a)).contains("east=none"), "redstone did not disconnect after neighbor removal");
    }

    private static void testStairConnections() {
        Scene scene = new Scene();
        SceneCellPos base = new SceneCellPos(0, 0, 0);
        SceneCellPos front = new SceneCellPos(1, 0, 0);
        var east = Blocks.OAK_STAIRS.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.EAST)
                .with(Properties.BLOCK_HALF, BlockHalf.BOTTOM)
                .with(Properties.STAIR_SHAPE, StairShape.STRAIGHT);
        var north = Blocks.OAK_STAIRS.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.NORTH)
                .with(Properties.BLOCK_HALF, BlockHalf.BOTTOM)
                .with(Properties.STAIR_SHAPE, StairShape.STRAIGHT);
        scene.blocks().set(base, east);
        scene.blocks().set(front, north);
        scene.neighborStates().reconcile();
        assertTrue(scene.blocks().getBlockState(base).get(Properties.STAIR_SHAPE) != StairShape.STRAIGHT,
                "stairs did not form a corner");
        scene.blocks().remove(front);
        scene.neighborStates().reconcile();
        assertTrue(scene.blocks().getBlockState(base).get(Properties.STAIR_SHAPE) == StairShape.STRAIGHT,
                "stairs did not return to straight after neighbor removal");
    }

    private static void testPaneConnections() {
        Scene scene = new Scene();
        SceneCellPos a = new SceneCellPos(0, 0, 0);
        SceneCellPos b = new SceneCellPos(1, 0, 0);
        scene.blocks().set(a, Blocks.GLASS_PANE.getDefaultState());
        scene.blocks().set(b, Blocks.GLASS_PANE.getDefaultState());
        scene.neighborStates().reconcile();
        assertTrue(MinecraftStateCodec.encode(scene.blocks().getBlockState(a)).contains("east=true"),
                "pane did not use native east connection");
        scene.blocks().remove(b);
        scene.neighborStates().reconcile();
        assertTrue(MinecraftStateCodec.encode(scene.blocks().getBlockState(a)).contains("east=false"),
                "pane did not disconnect after neighbor removal");
    }

    private static void testWallGateConnections() {
        Scene scene = new Scene();
        SceneCellPos westWall = new SceneCellPos(0, 0, 0);
        SceneCellPos gate = new SceneCellPos(1, 0, 0);
        SceneCellPos eastWall = new SceneCellPos(2, 0, 0);
        scene.blocks().set(westWall, Blocks.COBBLESTONE_WALL.getDefaultState());
        scene.blocks().set(gate, Blocks.OAK_FENCE_GATE.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.NORTH));
        scene.blocks().set(eastWall, Blocks.COBBLESTONE_WALL.getDefaultState());
        scene.neighborStates().reconcile();
        String west = MinecraftStateCodec.encode(scene.blocks().getBlockState(westWall));
        String gateState = MinecraftStateCodec.encode(scene.blocks().getBlockState(gate));
        assertTrue(west.contains("east=low") || west.contains("east=tall"),
                "wall did not connect to a compatible fence gate");
        assertTrue(gateState.contains("in_wall=true"), "fence gate did not enter in_wall state");
    }

    private static void testWallTallShape() {
        Scene scene = new Scene();
        SceneCellPos wall = new SceneCellPos(0, 0, 0);
        scene.blocks().set(wall, Blocks.COBBLESTONE_WALL.getDefaultState());
        scene.blocks().set(new SceneCellPos(1, 0, 0), Blocks.COBBLESTONE_WALL.getDefaultState());
        scene.blocks().set(new SceneCellPos(0, 1, 0), Blocks.STONE.getDefaultState());
        scene.neighborStates().reconcile();
        String encoded = MinecraftStateCodec.encode(scene.blocks().getBlockState(wall));
        assertTrue(encoded.contains("east=tall"), "solid block above did not promote connected wall arm to tall");
    }


    /** Regression for Rev AD crash: wall reconciliation must never pass AIR or an
     * unrelated state into FenceGateBlock.canWallConnect. Also verifies the gate
     * connection disappears and returns as the gate is removed/re-added. */
    private static void testWallAirGateSafety() {
        Scene scene = new Scene();
        SceneCellPos wall = new SceneCellPos(0, 0, 0);
        SceneCellPos gate = new SceneCellPos(1, 0, 0);
        scene.blocks().set(wall, Blocks.COBBLESTONE_WALL.getDefaultState());

        // Three sides are AIR. This was sufficient to crash Rev AD.
        scene.neighborStates().reconcile();
        String isolated = MinecraftStateCodec.encode(scene.blocks().getBlockState(wall));
        assertTrue(isolated.contains("east=none"), "isolated wall incorrectly connected through air");

        scene.blocks().set(gate, Blocks.OAK_FENCE_GATE.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.NORTH));
        scene.neighborStates().reconcile();
        String connected = MinecraftStateCodec.encode(scene.blocks().getBlockState(wall));
        assertTrue(connected.contains("east=low") || connected.contains("east=tall"),
                "wall did not connect to compatible gate");

        scene.blocks().remove(gate);
        scene.neighborStates().reconcile();
        String removed = MinecraftStateCodec.encode(scene.blocks().getBlockState(wall));
        assertTrue(removed.contains("east=none"), "wall kept stale gate connection after removal");

        scene.blocks().set(gate, Blocks.OAK_FENCE_GATE.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.NORTH));
        scene.neighborStates().reconcile();
        String readded = MinecraftStateCodec.encode(scene.blocks().getBlockState(wall));
        assertTrue(readded.contains("east=low") || readded.contains("east=tall"),
                "wall did not reconnect after gate re-addition");
        assertTrue(scene.neighborStates().statistics().nativeContractFailures() == 0L,
                "neighbor reconciliation recorded a native state-contract failure: "
                        + scene.neighborStates().statistics().lastNativeContractFailure());
    }


    /** Verifies native wall families that are supposed to connect horizontally. */
    private static void testWallToWallAndPaneConnections() {
        Scene scene = new Scene();
        SceneCellPos center = new SceneCellPos(0, 0, 0);
        SceneCellPos east = new SceneCellPos(1, 0, 0);
        scene.blocks().set(center, Blocks.COBBLESTONE_WALL.getDefaultState());
        scene.blocks().set(east, Blocks.STONE_BRICK_WALL.getDefaultState());
        scene.neighborStates().reconcile();
        var wallState = scene.blocks().getBlockState(center);
        assertTrue(wallState.contains(net.minecraft.block.WallBlock.EAST_SHAPE)
                        && wallState.get(net.minecraft.block.WallBlock.EAST_SHAPE) != WallShape.NONE,
                "wall did not connect horizontally to another wall");

        scene.blocks().remove(east);
        scene.blocks().set(east, Blocks.GLASS_PANE.getDefaultState());
        scene.neighborStates().reconcile();
        wallState = scene.blocks().getBlockState(center);
        assertTrue(wallState.get(net.minecraft.block.WallBlock.EAST_SHAPE) != WallShape.NONE,
                "wall did not connect horizontally to pane/iron-bar family");
    }

    /** Koil side-view extension: wall/fence families bridge the one-cell UI gap. */
    private static void testFenceWallSideViewBridge() {
        Scene scene = new Scene();
        SceneCellPos wall = new SceneCellPos(0, 0, 0);
        SceneCellPos fence = new SceneCellPos(1, 0, 0);
        scene.blocks().set(wall, Blocks.COBBLESTONE_WALL.getDefaultState());
        scene.blocks().set(fence, Blocks.OAK_FENCE.getDefaultState());
        scene.neighborStates().reconcile();
        var wallState = scene.blocks().getBlockState(wall);
        var fenceState = scene.blocks().getBlockState(fence);
        assertTrue(wallState.get(net.minecraft.block.WallBlock.EAST_SHAPE) != WallShape.NONE,
                "Koil side-view wall did not bridge to adjacent fence");
        assertTrue(fenceState.contains(Properties.WEST) && fenceState.get(Properties.WEST),
                "Koil side-view fence did not bridge to adjacent wall");
    }

    private static void testDoorMultipart() {
        Scene scene = new Scene();
        SceneCellPos lowerPos = new SceneCellPos(0, 0, 0);
        var lower = Blocks.OAK_DOOR.getDefaultState()
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                .with(net.minecraft.block.DoorBlock.FACING, Direction.EAST);
        scene.blocks().set(lowerPos, lower);
        scene.multipartBlocks().reconcile();
        var upper = scene.blocks().getBlockState(new SceneCellPos(0, 1, 0));
        assertTrue(upper != null && upper.isOf(Blocks.OAK_DOOR), "door upper half was not created");
        assertTrue(upper.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER,
                "derived door half is not upper");
        assertTrue(upper.get(net.minecraft.block.DoorBlock.FACING) == Direction.EAST,
                "door upper half did not inherit facing");
    }


    private static void testTallPlantMultipart() {
        Scene scene = new Scene();
        SceneCellPos lowerPos = new SceneCellPos(0, 0, 0);
        SceneCellPos upperPos = new SceneCellPos(0, 1, 0);
        scene.blocks().set(lowerPos, Blocks.TALL_GRASS.getDefaultState()
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        scene.multipartBlocks().reconcile();

        var upper = scene.blocks().getBlockState(upperPos);
        assertTrue(upper != null && upper.isOf(Blocks.TALL_GRASS),
                "tall grass upper half was not materialized");
        assertTrue(upper.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER,
                "tall grass derived cell did not use the native upper-half state");

        scene.blocks().remove(lowerPos);
        scene.multipartBlocks().reconcile();
        assertTrue(scene.blocks().getBlockState(upperPos).isAir(),
                "derived tall-grass upper half survived after its lower half was removed");

        // An occupied upper cell is authoritative and must never be overwritten.
        scene.blocks().set(upperPos, Blocks.STONE.getDefaultState());
        scene.blocks().set(lowerPos, Blocks.LARGE_FERN.getDefaultState()
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        scene.multipartBlocks().reconcile();
        assertTrue(scene.blocks().getBlockState(upperPos).isOf(Blocks.STONE),
                "multipart reconciliation overwrote an occupied upper cell");
    }

    private static void testPitcherCropMultipartThreshold() {
        Scene scene = new Scene();
        SceneCellPos lowerPos = new SceneCellPos(0, 0, 0);
        SceneCellPos upperPos = new SceneCellPos(0, 1, 0);
        var ageTwo = MinecraftStateCodec.withProperty(Blocks.PITCHER_CROP.getDefaultState(), "age", "2");
        ageTwo = MinecraftStateCodec.withProperty(ageTwo, "half", "lower");
        scene.blocks().set(lowerPos, ageTwo);
        scene.multipartBlocks().reconcile();
        assertTrue(scene.blocks().getBlockState(upperPos).isAir(),
                "pitcher crop became double tall before vanilla age 3");

        var ageThree = MinecraftStateCodec.withProperty(ageTwo, "age", "3");
        scene.blocks().updateState(lowerPos, ageThree);
        scene.multipartBlocks().reconcile();
        var upper = scene.blocks().getBlockState(upperPos);
        assertTrue(upper != null && upper.isOf(Blocks.PITCHER_CROP),
                "age-3 pitcher crop did not materialize its upper half");
        assertTrue(MinecraftStateCodec.encode(upper).contains("half=upper"),
                "pitcher crop upper cell does not carry the upper-half state");
        assertTrue(MinecraftStateCodec.encode(upper).contains("age=3"),
                "pitcher crop upper cell did not inherit growth age");
    }

    private static void testVerticalPlantBodyTips() {
        Scene twisting = new Scene();
        for (int y = 0; y < 3; y++) {
            twisting.blocks().set(new SceneCellPos(0, y, 0), Blocks.TWISTING_VINES.getDefaultState());
        }
        twisting.neighborStates().reconcile();
        assertTrue(twisting.blocks().getBlockState(new SceneCellPos(0, 0, 0)).isOf(Blocks.TWISTING_VINES_PLANT),
                "lower twisting-vines segment did not become native plant body");
        assertTrue(twisting.blocks().getBlockState(new SceneCellPos(0, 1, 0)).isOf(Blocks.TWISTING_VINES_PLANT),
                "middle twisting-vines segment did not become native plant body");
        assertTrue(twisting.blocks().getBlockState(new SceneCellPos(0, 2, 0)).isOf(Blocks.TWISTING_VINES),
                "terminal twisting-vines segment did not remain the native head");

        twisting.blocks().remove(new SceneCellPos(0, 2, 0));
        twisting.neighborStates().reconcile();
        assertTrue(twisting.blocks().getBlockState(new SceneCellPos(0, 1, 0)).isOf(Blocks.TWISTING_VINES),
                "new twisting-vines terminal did not convert back to the native head");

        Scene weeping = new Scene();
        for (int y = 0; y < 3; y++) {
            weeping.blocks().set(new SceneCellPos(0, y, 0), Blocks.WEEPING_VINES.getDefaultState());
        }
        weeping.neighborStates().reconcile();
        assertTrue(weeping.blocks().getBlockState(new SceneCellPos(0, 0, 0)).isOf(Blocks.WEEPING_VINES),
                "bottom weeping-vines segment should be the downward-growing head");
        assertTrue(weeping.blocks().getBlockState(new SceneCellPos(0, 1, 0)).isOf(Blocks.WEEPING_VINES_PLANT)
                        && weeping.blocks().getBlockState(new SceneCellPos(0, 2, 0)).isOf(Blocks.WEEPING_VINES_PLANT),
                "weeping-vines body segments did not use the native plant block");
    }

    private static void testPointedDripstoneThickness() {
        Scene scene = new Scene();
        for (int y = 0; y < 4; y++) {
            scene.blocks().set(new SceneCellPos(0, y, 0), Blocks.POINTED_DRIPSTONE.getDefaultState()
                    .with(net.minecraft.block.PointedDripstoneBlock.VERTICAL_DIRECTION, Direction.UP));
        }
        scene.neighborStates().reconcile();
        assertTrue(scene.blocks().getBlockState(new SceneCellPos(0, 0, 0))
                        .get(net.minecraft.block.PointedDripstoneBlock.THICKNESS) == Thickness.BASE,
                "dripstone support segment did not resolve to BASE");
        assertTrue(scene.blocks().getBlockState(new SceneCellPos(0, 1, 0))
                        .get(net.minecraft.block.PointedDripstoneBlock.THICKNESS) == Thickness.MIDDLE,
                "dripstone inner segment did not resolve to MIDDLE");
        assertTrue(scene.blocks().getBlockState(new SceneCellPos(0, 2, 0))
                        .get(net.minecraft.block.PointedDripstoneBlock.THICKNESS) == Thickness.FRUSTUM,
                "dripstone pre-tip segment did not resolve to FRUSTUM");
        assertTrue(scene.blocks().getBlockState(new SceneCellPos(0, 3, 0))
                        .get(net.minecraft.block.PointedDripstoneBlock.THICKNESS) == Thickness.TIP,
                "dripstone terminal segment did not resolve to TIP");

        Scene merged = new Scene();
        merged.blocks().set(new SceneCellPos(0, 0, 0), Blocks.POINTED_DRIPSTONE.getDefaultState()
                .with(net.minecraft.block.PointedDripstoneBlock.VERTICAL_DIRECTION, Direction.UP));
        merged.blocks().set(new SceneCellPos(0, 1, 0), Blocks.POINTED_DRIPSTONE.getDefaultState()
                .with(net.minecraft.block.PointedDripstoneBlock.VERTICAL_DIRECTION, Direction.DOWN));
        merged.neighborStates().reconcile();
        assertTrue(merged.blocks().getBlockState(new SceneCellPos(0, 0, 0))
                        .get(net.minecraft.block.PointedDripstoneBlock.THICKNESS) == Thickness.TIP_MERGE
                        && merged.blocks().getBlockState(new SceneCellPos(0, 1, 0))
                        .get(net.minecraft.block.PointedDripstoneBlock.THICKNESS) == Thickness.TIP_MERGE,
                "opposing dripstone tips did not resolve to TIP_MERGE");
    }

    private static void testChorusConnections() {
        Scene scene = new Scene();
        SceneCellPos root = new SceneCellPos(0, 1, 0);
        SceneCellPos upper = new SceneCellPos(0, 2, 0);
        SceneCellPos branch = new SceneCellPos(1, 2, 0);
        scene.blocks().set(new SceneCellPos(0, 0, 0), Blocks.END_STONE.getDefaultState());
        scene.blocks().set(root, Blocks.CHORUS_PLANT.getDefaultState());
        scene.blocks().set(upper, Blocks.CHORUS_PLANT.getDefaultState());
        scene.blocks().set(branch, Blocks.CHORUS_FLOWER.getDefaultState());
        scene.neighborStates().reconcile();

        var rootState = scene.blocks().getBlockState(root);
        var upperState = scene.blocks().getBlockState(upper);
        assertTrue(rootState.get(ConnectingBlock.DOWN), "chorus root did not connect to end stone below");
        assertTrue(rootState.get(ConnectingBlock.UP), "chorus root did not connect to plant above");
        assertTrue(upperState.get(ConnectingBlock.DOWN), "upper chorus segment did not connect downward");
        assertTrue(upperState.get(ConnectingBlock.EAST), "chorus segment did not connect to flower branch");
    }

    private static void testProjectionVisibleAttachmentFaces() {
        Scene vineScene = new Scene();
        SceneCellPos vinePos = new SceneCellPos(0, 0, 0);
        var edgeOnVine = MinecraftStateCodec.withProperty(Blocks.VINE.getDefaultState(), "east", "true");
        vineScene.blocks().set(vinePos, edgeOnVine);
        vineScene.neighborStates().reconcile();
        String vine = MinecraftStateCodec.encode(vineScene.blocks().getBlockState(vinePos));
        assertTrue(vine.contains("east=true"),
                "projection bridge cleared an authored vine attachment");
        assertTrue(vine.contains("south=true"),
                "XY-side vine did not gain a camera-plane native face");

        Scene multifaceScene = new Scene();
        SceneCellPos lichenPos = new SceneCellPos(0, 0, 0);
        var edgeOnLichen = MinecraftStateCodec.withProperty(Blocks.GLOW_LICHEN.getDefaultState(), "east", "true");
        multifaceScene.blocks().set(lichenPos, edgeOnLichen);
        multifaceScene.neighborStates().reconcile();
        String lichen = MinecraftStateCodec.encode(multifaceScene.blocks().getBlockState(lichenPos));
        assertTrue(lichen.contains("east=true"),
                "projection bridge cleared an authored multiface attachment");
        assertTrue(lichen.contains("south=true"),
                "XY-side multiface block did not gain a visible native face");
    }

    private static void testStairOrientationCycle() {
        Scene scene = new Scene();
        SceneCellPos pos = new SceneCellPos(0, 0, 0);
        var stair = Blocks.OAK_STAIRS.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, Direction.EAST)
                .with(Properties.BLOCK_HALF, BlockHalf.BOTTOM)
                .with(Properties.STAIR_SHAPE, StairShape.STRAIGHT);
        scene.blocks().set(pos, stair);
        assertTrue(scene.blockOrientation().cycle(pos, 4), "stair orientation controller did not change state");
        scene.neighborStates().reconcile();
        var rotated = scene.blocks().getBlockState(pos);
        assertTrue(rotated.get(Properties.HORIZONTAL_FACING) == Direction.EAST,
                "four stair wheel steps should preserve horizontal facing");
        assertTrue(rotated.get(Properties.BLOCK_HALF) == BlockHalf.TOP,
                "four stair wheel steps did not produce an upside-down stair");
    }

    private static void testFluidSurfaceSlope() {
        Scene scene = new Scene();
        SceneCellPos left = new SceneCellPos(0, 0, 0);
        SceneCellPos right = new SceneCellPos(1, 0, 0);
        FlowableFluid water = (FlowableFluid) Fluids.FLOWING_WATER;
        scene.fluids().set(left, water.getFlowing(7, false));
        scene.fluids().set(right, water.getFlowing(2, false));
        var profile = scene.fluidSystem().surfaceProfile(left);
        assertTrue(profile.sloped(), "neighboring unequal water levels did not create a sloped surface");
        assertTrue(Math.abs(profile.leftHeight() - profile.rightHeight()) > 0.015625F,
                "fluid surface edge heights are effectively flat");
        var falling = water.getFlowing(7, true);
        scene.fluids().set(left, falling);
        profile = scene.fluidSystem().surfaceProfile(left);
        assertTrue(Math.abs(profile.leftHeight() - 1.0F) < 0.0001F
                        && Math.abs(profile.rightHeight() - 1.0F) < 0.0001F,
                "falling liquid must stay a full-height vertical column");
    }

    private static void advanceGameTicks(Scene scene, int ticks) {
        for (int i = 0; i < ticks; i++) scene.advanceFrame(1.0F / 20.0F);
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
