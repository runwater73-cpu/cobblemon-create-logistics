package dev.cobblemoncreate.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Public integration surface for Create-facing adapters and future ports. */
public final class LogisticsApi {
    private LogisticsApi() {}

    public static DeliveryTask submitPackage(MinecraftServer server, ItemStack packageStack,
                                              ResourceKey<Level> sourceDimension, BlockPos sourcePos,
                                              ResourceKey<Level> targetDimension, BlockPos targetPos,
                                              long gameTime) {
        return DeliveryTaskManager.submit(server, packageStack, sourceDimension, sourcePos,
                targetDimension, targetPos, gameTime);
    }
}
