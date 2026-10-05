package dev.cobblemoncreate.logistics.create;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.packagePort.PackagePortTarget;
import com.simibubi.create.content.logistics.packagePort.PackagePortTargetType;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import dev.cobblemoncreate.logistics.registry.LogisticsItems;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;

/**
 * Create package-port target for a Cobblemon hub. Routing, address matching,
 * and package movement remain Create's responsibility; the endpoint only
 * performs a one-stack handoff into the durable task manager.
 */
public final class CobblemonHubTarget extends PackagePortTarget {
    public static final MapCodec<CobblemonHubTarget> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BlockPos.CODEC.fieldOf("relative_pos").forGetter(target -> target.relativePos)
    ).apply(instance, CobblemonHubTarget::new));

    public static final StreamCodec<ByteBuf, CobblemonHubTarget> STREAM_CODEC = BlockPos.STREAM_CODEC
            .map(CobblemonHubTarget::new, target -> target.relativePos);

    public CobblemonHubTarget(BlockPos relativePos) {
        super(relativePos);
    }

    @Override
    public boolean export(LevelAccessor level, BlockPos portPos, ItemStack box, boolean simulate) {
        BlockEntity endpoint = be(level, portPos);
        if (!(endpoint instanceof CobblemonPackageEndpoint hub) || !PackageItem.isPackage(box)) {
            return false;
        }
        if (simulate) {
            return hub.canAcceptCreatePackage(box, true);
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        return hub.acceptCreatePackage(box, serverLevel.dimension(), portPos);
    }

    @Override
    public Vec3 getExactTargetLocation(PackagePortBlockEntity ppbe, LevelAccessor level, BlockPos portPos) {
        return Vec3.atCenterOf(portPos.offset(relativePos));
    }

    @Override
    public ItemStack getIcon() {
        return LogisticsItems.COBBLEMON_HUB.get().getDefaultInstance();
    }

    @Override
    public boolean canSupport(BlockEntity be) {
        // Create asks whether the *source package port* supports this target;
        // the endpoint itself is checked in export().
        return be instanceof PackagePortBlockEntity;
    }

    @Override
    protected PackagePortTargetType getType() {
        return CreateTargetRegistry.COBBLEMON_HUB.value();
    }

    public static final class Type implements PackagePortTargetType {
        @Override
        public MapCodec<CobblemonHubTarget> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<ByteBuf, CobblemonHubTarget> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
