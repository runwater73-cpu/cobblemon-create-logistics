package dev.cobblemoncreate.logistics.hub;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import dev.cobblemoncreate.logistics.DeliveryTaskManager;
import dev.cobblemoncreate.logistics.DeliveryTaskSavedData;
import dev.cobblemoncreate.logistics.registry.LogisticsBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.StateDefinition;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.CollisionContext;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;

/** Endpoint for Create packages; cargo itself stays in DeliveryTaskSavedData. */
public final class CobblemonHubBlock extends Block implements EntityBlock, IWrenchable {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape PLATFORM = Block.box(0, 0, 0, 16, 2.25, 16);

    public CobblemonHubBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockState getRotatedBlockState(BlockState state, Direction targetedFace) {
        if (targetedFace.getAxis().isVertical()) {
            return state.setValue(FACING, state.getValue(FACING).getClockWise(targetedFace.getAxis()));
        }
        return state.setValue(FACING, state.getValue(FACING).getClockWise());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return PLATFORM;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CobblemonHubBlockEntity(pos, state);
    }

    @Override
    protected void onRemove(BlockState state, net.minecraft.world.level.Level level,
                            BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())
                && level instanceof net.minecraft.server.level.ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof CobblemonHubBlockEntity hub) {
            var data = DeliveryTaskSavedData.get(serverLevel.getServer());
            for (var task : java.util.List.copyOf(data.tasks())) {
                try {
                    if (!hub.ownsTask(task)) continue;
                    ItemStack packageStack = DeliveryTaskManager.ejectToWorld(
                            serverLevel.getServer(), task.id(), serverLevel.getServer().getTickCount());
                    if (!packageStack.isEmpty()) Block.popResource(level, pos, packageStack);
                } catch (IllegalArgumentException ignored) {
                    // A terminal task may already have been compacted.
                }
            }
            hub.releaseWorkers(serverLevel);
            HubAddressRegistry.unregister(hub);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, net.minecraft.world.level.Level level,
                                               BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof CobblemonHubBlockEntity hub)) {
            return InteractionResult.PASS;
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) hub.openFor(serverPlayer);
        return InteractionResult.SUCCESS;
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            net.minecraft.world.level.Level level, BlockState state, BlockEntityType<T> type) {
        return type == LogisticsBlockEntities.COBBLEMON_HUB.get()
                ? (level.isClientSide() ? null : (l, p, s, be) ->
                CobblemonHubBlockEntity.serverTick(l, p, s, (CobblemonHubBlockEntity) be)) : null;
    }
}
