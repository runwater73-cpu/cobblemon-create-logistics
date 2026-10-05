package dev.cobblemoncreate.logistics.registry;

import dev.cobblemoncreate.logistics.CobblemonCreateLogistics;
import dev.cobblemoncreate.logistics.hub.CobblemonHubBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class LogisticsBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CobblemonCreateLogistics.MOD_ID);

    public static final Supplier<BlockEntityType<CobblemonHubBlockEntity>> COBBLEMON_HUB =
            BLOCK_ENTITIES.register("cobblemon_hub", () -> BlockEntityType.Builder.of(
                    CobblemonHubBlockEntity::new,
                    LogisticsBlocks.COBBLEMON_HUB.get()
            ).build(null));

    private LogisticsBlockEntities() {}
}
