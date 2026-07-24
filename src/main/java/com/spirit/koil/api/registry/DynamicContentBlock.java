package com.spirit.koil.api.registry;

import com.spirit.koil.api.registry.definition.ContentDefinition;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Waterloggable;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.text.MutableText;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

import java.util.List;
import java.util.Objects;

/** Generic early-registered block holder for a world-scoped block definition. */
final class DynamicContentBlock extends Block implements Waterloggable {
    private static final ThreadLocal<DynamicBlockStateSchema> CONSTRUCTION_SCHEMA = new ThreadLocal<>();

    private final String contentId;
    private final WorldContentIndex.DefinitionEntry bootstrapDefinition;
    private final DynamicBlockStateSchema stateSchema;

    DynamicContentBlock(ContentDefinition definition) {
        super(prepare(definition));
        stateSchema = Objects.requireNonNull(
                CONSTRUCTION_SCHEMA.get(),
                "Dynamic blockstate construction context was lost"
        );
        CONSTRUCTION_SCHEMA.remove();
        contentId = definition.id();
        bootstrapDefinition = definition.source();
        if (getStateManager().getStates().size() != stateSchema.materializedStateCount()) {
            throw new IllegalStateException(
                    "Dynamic Content block state materialization mismatch for " + contentId
            );
        }
        setDefaultState(stateSchema.applyDefaults(getDefaultState()));
    }

    @Override
    public MutableText getName() {
        return WorldScopedContentPresentation.name(contentId, bootstrapDefinition, true);
    }

    @Override
    public ItemStack getPickStack(BlockView world, BlockPos pos, BlockState state) {
        return WorldScopedContentPresentation.isActive(contentId) ? super.getPickStack(world, pos, state) : ItemStack.EMPTY;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        DynamicBlockStateSchema schema = CONSTRUCTION_SCHEMA.get();
        if (schema != null) {
            schema.appendTo(builder);
        }
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockState state = getDefaultState();
        var facing = stateSchema.directionProperty();
        if (facing != null) {
            Direction direction;
            boolean horizontalOnly = facing.getValues().stream().allMatch(value -> value.getAxis().isHorizontal());
            if (horizontalOnly) {
                direction = context.getHorizontalPlayerFacing().getOpposite();
            } else {
                direction = context.getPlayerLookDirection().getOpposite();
            }
            if (facing.getValues().contains(direction)) {
                state = state.with(facing, direction);
            }
        }
        var axis = stateSchema.axisProperty();
        if (axis != null && axis.getValues().contains(context.getSide().getAxis())) {
            state = state.with(axis, context.getSide().getAxis());
        }
        BooleanProperty waterlogged = stateSchema.booleanProperty("waterlogged");
        if (waterlogged != null) {
            state = state.with(
                    waterlogged,
                    context.getWorld().getFluidState(context.getBlockPos()).isOf(Fluids.WATER)
            );
        }
        BooleanProperty powered = stateSchema.booleanProperty("powered");
        if (powered != null) {
            state = state.with(powered, context.getWorld().isReceivingRedstonePower(context.getBlockPos()));
        }
        return state;
    }

    @Override
    public ActionResult onUse(
            BlockState state,
            World world,
            BlockPos pos,
            PlayerEntity player,
            Hand hand,
            BlockHitResult hit
    ) {
        if (!WorldScopedContentPresentation.isActive(contentId)) {
            return ActionResult.FAIL;
        }
        List<String> cycle = DynamicContentBlockBehavior.cycleOnUse(contentId);
        if (cycle.isEmpty()) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            BlockState updated = state;
            for (String property : cycle) {
                if (!"waterlogged".equals(property) && !"powered".equals(property)) {
                    updated = stateSchema.cycle(updated, property);
                }
            }
            if (updated != state) {
                world.setBlockState(pos, updated, Block.NOTIFY_ALL);
            }
        }
        return ActionResult.success(world.isClient);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return stateSchema.rotate(state, rotation);
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return stateSchema.mirror(state, mirror);
    }

    @Override
    public boolean emitsRedstonePower(BlockState state) {
        return DynamicContentBlockBehavior.emitsPower(contentId, stateSchema, state);
    }

    @Override
    public int getWeakRedstonePower(BlockState state, BlockView world, BlockPos pos, Direction direction) {
        return DynamicContentBlockBehavior.weakPower(contentId, stateSchema, state);
    }

    @Override
    public int getStrongRedstonePower(BlockState state, BlockView world, BlockPos pos, Direction direction) {
        return DynamicContentBlockBehavior.strongPower(contentId, stateSchema, state);
    }

    int runtimeLuminance(BlockState state) {
        int bootstrapFallback = DefinitionValueReader.integer(
                DefinitionValueReader.object(bootstrapDefinition.definition(), "properties"),
                "luminance",
                0
        );
        return DynamicContentBlockBehavior.luminance(
                contentId,
                stateSchema,
                state,
                bootstrapFallback
        );
    }

    @Override
    public void neighborUpdate(
            BlockState state,
            World world,
            BlockPos pos,
            Block sourceBlock,
            BlockPos sourcePos,
            boolean notify
    ) {
        BooleanProperty powered = stateSchema.booleanProperty("powered");
        if (powered != null) {
            boolean receivingPower = world.isReceivingRedstonePower(pos);
            if (state.get(powered) != receivingPower) {
                world.setBlockState(pos, state.with(powered, receivingPower), Block.NOTIFY_ALL);
            }
        }
        super.neighborUpdate(state, world, pos, sourceBlock, sourcePos, notify);
    }

    @Override
    public BlockState getStateForNeighborUpdate(
            BlockState state,
            Direction direction,
            BlockState neighborState,
            WorldAccess world,
            BlockPos pos,
            BlockPos neighborPos
    ) {
        BooleanProperty waterlogged = stateSchema.booleanProperty("waterlogged");
        if (waterlogged != null && state.get(waterlogged)) {
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        BooleanProperty waterlogged = stateSchema.booleanProperty("waterlogged");
        return waterlogged != null && state.get(waterlogged)
                ? Fluids.WATER.getStill(false)
                : super.getFluidState(state);
    }

    @Override
    public boolean canFillWithFluid(
            BlockView world,
            BlockPos pos,
            BlockState state,
            Fluid fluid
    ) {
        return stateSchema.booleanProperty("waterlogged") != null
                && Waterloggable.super.canFillWithFluid(world, pos, state, fluid);
    }

    @Override
    public boolean tryFillWithFluid(
            WorldAccess world,
            BlockPos pos,
            BlockState state,
            FluidState fluidState
    ) {
        return stateSchema.booleanProperty("waterlogged") != null
                && Waterloggable.super.tryFillWithFluid(world, pos, state, fluidState);
    }

    @Override
    public ItemStack tryDrainFluid(WorldAccess world, BlockPos pos, BlockState state) {
        return stateSchema.booleanProperty("waterlogged") == null
                ? ItemStack.EMPTY
                : Waterloggable.super.tryDrainFluid(world, pos, state);
    }

    private static net.minecraft.block.AbstractBlock.Settings prepare(ContentDefinition definition) {
        DynamicBlockStateSchema schema = DynamicBlockStateSchema.compile(definition);
        CONSTRUCTION_SCHEMA.set(schema);
        return DefinitionValueReader.blockSettings(definition, schema);
    }
}
