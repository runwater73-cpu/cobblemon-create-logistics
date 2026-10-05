package dev.cobblemoncreate.logistics.client;

import dev.cobblemoncreate.logistics.hub.CobblemonHubBlock;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.bus.api.SubscribeEvent;

/** The Hub has a detailed model; its full-block vanilla outline is misleading. */
public final class LogisticsClientEvents {
    private LogisticsClientEvents() {}

    @SubscribeEvent
    public static void cancelHubOutline(RenderHighlightEvent.Block event) {
        if (Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getBlockState(event.getTarget().getBlockPos()).getBlock()
                instanceof CobblemonHubBlock) {
            event.setCanceled(true);
        }
    }
}
