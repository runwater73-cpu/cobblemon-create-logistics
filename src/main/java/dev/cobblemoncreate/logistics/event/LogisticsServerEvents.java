package dev.cobblemoncreate.logistics.event;

import dev.cobblemoncreate.logistics.DeliveryTaskManager;
import dev.cobblemoncreate.logistics.DeliveryTaskSavedData;
import dev.cobblemoncreate.logistics.GhostContraptionPickupRuntime;
import dev.cobblemoncreate.logistics.cobblemon.CarrierRuntimeLease;
import dev.cobblemoncreate.logistics.cobblemon.ResidentWorkerRuntime;
import dev.cobblemoncreate.logistics.hub.HubAddressRegistry;
import dev.cobblemoncreate.logistics.hub.CobblemonHubBlockEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.ChunkTicketLevelUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Server-only lifecycle hooks; visuals and Pokemon entities are not persisted here. */
public final class LogisticsServerEvents {
    private LogisticsServerEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Pre event) {
        if (event.getLevel() instanceof ServerLevel level) CarrierRuntimeLease.protectRemoteJourneys(level);
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            CarrierRuntimeLease.beforeChunkUnload(level, event.getChunk().getPos());
        }
    }

    @SubscribeEvent
    public static void onChunkSent(ChunkWatchEvent.Sent event) {
        // Sent runs after the vanilla chunk packet; Watch runs too early.
        for (var blockEntity : event.getChunk().getBlockEntities().values()) {
            if (blockEntity instanceof CobblemonHubBlockEntity hub) hub.sendVisualState(event.getPlayer());
        }
    }

    @SubscribeEvent
    public static void onChunkDemotion(ChunkTicketLevelUpdatedEvent event) {
        if (event.getOldTicketLevel() <= 31 && event.getNewTicketLevel() > 31) {
            CarrierRuntimeLease.beforeChunkUnload(event.getLevel(),
                    new net.minecraft.world.level.ChunkPos(event.getChunkPos()));
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        GhostContraptionPickupRuntime.tick(event.getServer());
        ResidentWorkerRuntime.tickReturns(event.getServer());
        if (event.getServer().getTickCount() % 20 == 0) {
            DeliveryTaskManager.tick(event.getServer());
        }
        if (event.getServer().getTickCount() % 200 == 0) {
            DeliveryTaskSavedData.get(event.getServer()).retryWorkerReturns(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!event.getEntity().level().isClientSide()) {
            CarrierRuntimeLease.refreshShownPackage(event.getEntity());
            if (event.getEntity() instanceof PokemonEntity pokemon
                    && pokemon.level() instanceof ServerLevel level) {
                ResidentWorkerRuntime.tickFlightNavigation(level, pokemon);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        GhostContraptionPickupRuntime.clear();
        CarrierRuntimeLease.clear();
        ResidentWorkerRuntime.clearReturnTracking();
        HubAddressRegistry.clear();
    }

}
