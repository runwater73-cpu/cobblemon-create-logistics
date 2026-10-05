package dev.cobblemoncreate.logistics.create;

import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Implemented by a Cobblemon logistics hub that can receive a Create package. */
public interface CobblemonPackageEndpoint {
    boolean canAcceptCreatePackage(ItemStack packageStack, boolean simulate);

    boolean acceptCreatePackage(ItemStack packageStack, ResourceKey<Level> sourceDimension, BlockPos sourcePos);
}
