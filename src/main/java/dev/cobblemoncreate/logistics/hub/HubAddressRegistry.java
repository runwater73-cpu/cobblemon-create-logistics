package dev.cobblemoncreate.logistics.hub;

import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.List;

/** Address index for loaded and unloaded Hub endpoints. It stores no cargo. */
public final class HubAddressRegistry {
    private HubAddressRegistry() {}

    static void register(CobblemonHubBlockEntity hub) {
        if (hub.getLevel() instanceof ServerLevel level) {
            HubAddressSavedData.get(level.getServer()).replaceHub(level.dimension(), hub.getBlockPos(), hub.address());
        }
    }

    public static void unregister(CobblemonHubBlockEntity hub) {
        if (hub.getLevel() instanceof ServerLevel level) {
            HubAddressSavedData.get(level.getServer()).removeHub(level.dimension(), hub.getBlockPos());
        }
    }

    static void refresh(CobblemonHubBlockEntity hub) { register(hub); }

    public static void clear() { }

    public static Destination findDestination(MinecraftServer server, ResourceKey<Level> sourceDimension,
                                              BlockPos source, String address) {
        Destination best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Destination destination : List.copyOf(HubAddressSavedData.get(server).entries())) {
            if (!PackageItem.matchAddress(address, destination.address())
                    || destination.dimension().equals(sourceDimension) && destination.pos().equals(source)) continue;
            ServerLevel targetLevel = server.getLevel(destination.dimension());
            if (targetLevel != null && targetLevel.hasChunkAt(destination.pos())) {
                if (!(targetLevel.getBlockEntity(destination.pos()) instanceof CobblemonHubBlockEntity hub)) {
                    HubAddressSavedData.get(server).removeHub(destination.dimension(), destination.pos());
                    continue;
                }
                if (!hub.address().equals(destination.address())) {
                    register(hub);
                    continue;
                }
                if (!hub.canAcceptIncomingTask(null)) continue;
            }
            double distance = destination.dimension().equals(sourceDimension)
                    ? destination.pos().distSqr(source) : Double.MAX_VALUE;
            if (best == null || distance < bestDistance) {
                best = destination;
                bestDistance = distance;
            }
        }
        return best;
    }

    static CobblemonHubBlockEntity find(ServerLevel level, BlockPos source, String address) {
        Destination destination = findDestination(level.getServer(), level.dimension(), source, address);
        if (destination == null || !destination.dimension().equals(level.dimension())
                || !level.hasChunkAt(destination.pos())
                || !(level.getBlockEntity(destination.pos()) instanceof CobblemonHubBlockEntity hub)
                || !hub.canAcceptIncomingTask(null)) return null;
        return hub;
    }

    public record Destination(ResourceKey<Level> dimension, BlockPos pos, String address) {}
}
