package dev.cobblemoncreate.logistics.registry;

import dev.cobblemoncreate.logistics.CobblemonCreateLogistics;
import dev.cobblemoncreate.logistics.hub.HubMenu;
import dev.cobblemoncreate.logistics.hub.TerminalMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class LogisticsMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, CobblemonCreateLogistics.MOD_ID);
    public static final Supplier<MenuType<HubMenu>> HUB =
            MENUS.register("hub", () -> IMenuTypeExtension.create(HubMenu::new));
    public static final Supplier<MenuType<TerminalMenu>> TERMINAL =
            MENUS.register("terminal", () -> IMenuTypeExtension.create(TerminalMenu::new));

    private LogisticsMenus() {}
}
