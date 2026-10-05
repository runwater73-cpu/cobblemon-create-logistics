package dev.cobblemoncreate.logistics.registry;

import dev.cobblemoncreate.logistics.CobblemonCreateLogistics;
import dev.cobblemoncreate.logistics.hub.CobblemonHubLinkerItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class LogisticsItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, CobblemonCreateLogistics.MOD_ID);

    public static final Supplier<Item> COBBLEMON_HUB = ITEMS.register(
            "cobblemon_hub",
            () -> new BlockItem(LogisticsBlocks.COBBLEMON_HUB.get(), new Item.Properties())
    );

    public static final Supplier<Item> COBBLEMON_HUB_LINKER = ITEMS.register(
            "cobblemon_hub_linker",
            () -> new CobblemonHubLinkerItem(new Item.Properties())
    );

    private LogisticsItems() {}
}
