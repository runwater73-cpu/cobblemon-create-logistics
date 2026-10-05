package dev.cobblemoncreate.logistics.registry;

import dev.cobblemoncreate.logistics.CobblemonCreateLogistics;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class LogisticsCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CobblemonCreateLogistics.MOD_ID);

    public static final Supplier<CreativeModeTab> LOGISTICS = TABS.register("logistics", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.cobblemon_create_logistics"))
                    .icon(() -> LogisticsItems.COBBLEMON_HUB.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(LogisticsItems.COBBLEMON_HUB.get());
                        output.accept(LogisticsItems.COBBLEMON_HUB_LINKER.get());
                    })
                    .build());

    private LogisticsCreativeTabs() {}
}
