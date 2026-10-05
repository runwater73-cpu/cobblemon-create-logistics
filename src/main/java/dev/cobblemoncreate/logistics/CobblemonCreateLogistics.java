package dev.cobblemoncreate.logistics;

import com.mojang.logging.LogUtils;
import dev.cobblemoncreate.logistics.create.CreateTargetRegistry;
import dev.cobblemoncreate.logistics.client.CourierPackageRenderer;
import dev.cobblemoncreate.logistics.client.CobblemonHubRenderer;
import dev.cobblemoncreate.logistics.event.LogisticsServerEvents;
import dev.cobblemoncreate.logistics.hub.CobblemonHubBlockEntity;
import dev.cobblemoncreate.logistics.registry.LogisticsBlockEntities;
import dev.cobblemoncreate.logistics.registry.LogisticsBlocks;
import dev.cobblemoncreate.logistics.registry.LogisticsItems;
import dev.cobblemoncreate.logistics.registry.LogisticsMenus;
import dev.cobblemoncreate.logistics.registry.LogisticsCreativeTabs;
import dev.cobblemoncreate.logistics.client.LogisticsClientEvents;
import dev.cobblemoncreate.logistics.client.HubScreen;
import dev.cobblemoncreate.logistics.client.TerminalScreen;
import dev.cobblemoncreate.logistics.network.HubNetwork;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Entry point for the Cobblemon/Create logistics integration.
 *
 * <p>The mod deliberately has no second item transport system. A task owns
 * the original Create package stack and only stores a small carrier lease.</p>
 */
@Mod(CobblemonCreateLogistics.MOD_ID)
public final class CobblemonCreateLogistics {
    public static final String MOD_ID = "cobblemon_create_logistics";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CobblemonCreateLogistics(IEventBus modEventBus) {
        CreateTargetRegistry.register(modEventBus);
        LogisticsBlocks.BLOCKS.register(modEventBus);
        LogisticsBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        LogisticsItems.ITEMS.register(modEventBus);
        LogisticsMenus.MENUS.register(modEventBus);
        LogisticsCreativeTabs.TABS.register(modEventBus);
        modEventBus.addListener(CobblemonHubBlockEntity::registerCapabilities);
        modEventBus.addListener(HubNetwork::register);
        NeoForge.EVENT_BUS.register(LogisticsServerEvents.class);
        if (FMLEnvironment.dist.isClient()) {
            NeoForge.EVENT_BUS.register(CourierPackageRenderer.class);
            NeoForge.EVENT_BUS.register(LogisticsClientEvents.class);
            modEventBus.addListener(CobblemonHubRenderer::registerModels);
            modEventBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                    event.registerBlockEntityRenderer(LogisticsBlockEntities.COBBLEMON_HUB.get(), CobblemonHubRenderer::new));
            modEventBus.addListener((RegisterMenuScreensEvent event) ->
                    {
                        event.register(LogisticsMenus.HUB.get(), HubScreen::new);
                        event.register(LogisticsMenus.TERMINAL.get(), TerminalScreen::new);
                    });
        }
        LOGGER.info("Cobblemon Create Logistics core initialized");
    }
}
