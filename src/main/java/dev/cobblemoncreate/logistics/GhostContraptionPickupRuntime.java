package dev.cobblemoncreate.logistics;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.crate.CreativeCrateMountedStorage;
import dev.cobblemoncreate.logistics.cobblemon.CarrierRuntimeLease;
import dev.cobblemoncreate.logistics.cobblemon.CobblemonCarrierAdapter;
import dev.cobblemoncreate.logistics.cobblemon.ResidentWorkerRuntime;
import dev.cobblemoncreate.logistics.hub.CobblemonHubBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Transient approach state; cargo stays in Create's mounted inventory until pickup. */
public final class GhostContraptionPickupRuntime {
    private static final double SCAN_RADIUS_SQR = 8.0D * 8.0D;
    private static final double CHASE_RADIUS_SQR = 16.0D * 16.0D;
    private static final long PICKUP_TIMEOUT = 400L;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private GhostContraptionPickupRuntime() {}

    public static boolean isWorkerPickingUp(UUID pokemonId) {
        return SESSIONS.containsKey(pokemonId);
    }

    /** Called from the loaded Hub every 40 ticks; at most one new pickup starts. */
    public static void scanHub(ServerLevel level, CobblemonHubBlockEntity hub) {
        if (!level.hasChunkAt(hub.getBlockPos()) || hub.workers().isEmpty()) return;
        Vec3 hubCenter = ResidentWorkerRuntime.interactionPosition(hub.getBlockPos());
        List<AbstractContraptionEntity> nearby = level.getEntitiesOfClass(
                AbstractContraptionEntity.class, new AABB(hub.getBlockPos()).inflate(8.0D),
                entity -> entity.isAlive() && entity.getContraption() != null);
        if (nearby.isEmpty()) return;

        for (WorkerLease worker : hub.workers()) {
            if (!worker.homeReported() || isWorkerPickingUp(worker.pokemonUuid())
                    || CarrierRuntimeLease.isWorkerActive(worker.pokemonUuid())) continue;
            CarrierProfile profile = CarrierProfiles.resolve(level, worker);
            if (profile == null || !profile.ghost()) continue;
            var active = CobblemonCarrierAdapter.findActive(level, worker, profile);
            if (active.isEmpty()) continue;
            for (AbstractContraptionEntity contraption : nearby) {
                int examined = 0;
                for (var entry : contraption.getContraption().getStorage().getAllItemStorages().entrySet()) {
                    Vec3 target = target(contraption, entry.getKey());
                    if (hubCenter.distanceToSqr(target) > SCAN_RADIUS_SQR) continue;
                    MountedItemStorage storage = entry.getValue();
                    for (int slot = 0; slot < storage.getSlots() && examined++ < 128; slot++) {
                        ItemStack offered = extractablePackage(storage, slot);
                        if (offered.isEmpty()
                                || hub.resolveDestinationTarget(offered) == null
                                || !hub.canAcceptCreatePackage(offered, true)
                                || reserved(contraption.getUUID(), entry.getKey(), slot)) continue;
                        PokemonEntity entity = active.get().entity();
                        Session session = new Session(worker, entity, hub.getBlockPos(),
                                contraption.getUUID(), entry.getKey(), slot,
                                new Object(), entity.noPhysics, entity.isNoGravity(), level.getGameTime());
                        entity.getBusyLocks().add(session.token);
                        entity.noPhysics = true;
                        entity.setNoGravity(true);
                        hub.markWorkerAway(worker.pokemonUuid());
                        SESSIONS.put(worker.pokemonUuid(), session);
                        return;
                    }
                    if (examined >= 128) break;
                }
            }
        }
    }

    private static boolean reserved(UUID contraptionId, BlockPos localPos, int slot) {
        return SESSIONS.values().stream().anyMatch(session -> session.contraptionId.equals(contraptionId)
                && session.localPos.equals(localPos) && session.slot == slot);
    }

    static ItemStack extractablePackage(MountedItemStorage storage, int slot) {
        if (storage instanceof CreativeCrateMountedStorage) return ItemStack.EMPTY;
        ItemStack preview = storage.extractItem(slot, 1, true);
        return PackageItem.isPackage(preview) ? preview : ItemStack.EMPTY;
    }

    public static void tick(MinecraftServer server) {
        for (Session session : List.copyOf(SESSIONS.values())) {
            if (session.tick(server)) SESSIONS.remove(session.worker.pokemonUuid(), session);
        }
    }

    public static void cancelHub(ServerLevel level, CobblemonHubBlockEntity hub) {
        for (Session session : List.copyOf(SESSIONS.values())) {
            if (session.worker.dimension().equals(level.dimension())
                    && session.hubPos.equals(hub.getBlockPos())) {
                session.unlock();
                SESSIONS.remove(session.worker.pokemonUuid(), session);
            }
        }
    }

    public static void clear() {
        for (Session session : SESSIONS.values()) session.unlock();
        SESSIONS.clear();
    }

    private static Vec3 target(AbstractContraptionEntity contraption, BlockPos localPos) {
        return contraption.toGlobalVector(Vec3.atCenterOf(localPos), 0.0F);
    }

    private static final class Session {
        private final WorkerLease worker;
        private final PokemonEntity entity;
        private final BlockPos hubPos;
        private final UUID contraptionId;
        private final BlockPos localPos;
        private final int slot;
        private final Object token;
        private final boolean previousNoPhysics;
        private final boolean previousNoGravity;
        private final long startedAt;
        private boolean locked = true;

        private Session(WorkerLease worker, PokemonEntity entity, BlockPos hubPos,
                        UUID contraptionId, BlockPos localPos, int slot, Object token,
                        boolean previousNoPhysics, boolean previousNoGravity, long startedAt) {
            this.worker = worker;
            this.entity = entity;
            this.hubPos = hubPos.immutable();
            this.contraptionId = contraptionId;
            this.localPos = localPos.immutable();
            this.slot = slot;
            this.token = token;
            this.previousNoPhysics = previousNoPhysics;
            this.previousNoGravity = previousNoGravity;
            this.startedAt = startedAt;
        }

        private boolean tick(MinecraftServer server) {
            ServerLevel level = server.getLevel(worker.dimension());
            if (level == null || !level.hasChunkAt(hubPos)
                    || !(level.getBlockEntity(hubPos) instanceof CobblemonHubBlockEntity hub)
                    || !hub.hasWorker(worker.pokemonUuid()) || !entity.isAlive() || entity.level() != level
                    || level.getGameTime() - startedAt > PICKUP_TIMEOUT) {
                cancel(level);
                return true;
            }
            if (!(level.getEntity(contraptionId) instanceof AbstractContraptionEntity contraption)
                    || contraption.getContraption() == null || !contraption.isAlive()) {
                cancel(level);
                return true;
            }
            MountedItemStorage storage = contraption.getContraption().getStorage()
                    .getAllItemStorages().get(localPos);
            if (storage == null || slot >= storage.getSlots()) {
                cancel(level);
                return true;
            }
            Vec3 goal = target(contraption, localPos);
            if (goal.distanceToSqr(ResidentWorkerRuntime.interactionPosition(hubPos)) > CHASE_RADIUS_SQR
                    || !level.hasChunkAt(BlockPos.containing(goal))) {
                cancel(level);
                return true;
            }
            ItemStack offered = extractablePackage(storage, slot);
            if (offered.isEmpty() || hub.resolveDestinationTarget(offered) == null) {
                cancel(level);
                return true;
            }
            if (entity.position().distanceToSqr(goal) > 3.0D * 3.0D) {
                ResidentWorkerRuntime.navigateGhostTo(level, entity, goal, 1.35D);
                return false;
            }
            if (!hub.canAcceptCreatePackage(offered, true)) {
                cancel(level);
                return true;
            }
            ItemStack extracted = storage.extractItem(slot, 1, false);
            if (extracted.isEmpty()) {
                cancel(level);
                return true;
            }
            unlock();
            if (!ItemStack.isSameItemSameComponents(offered, extracted)) {
                restorePackage(level, storage, goal, extracted);
                cancel(level);
                return true;
            }
            DeliveryTask task = hub.acceptCreatePackageTask(extracted);
            if (task != null && task.hasCourierDestination()
                    && DeliveryTaskManager.claimReservedGhostPickup(server, task,
                    worker.withHomeReported(false), server.getTickCount())
                    && DeliveryTaskManager.startTransitAfterGhostPickup(server, task.id(), server.getTickCount())) {
                return true;
            }
            if (task != null) {
                DeliveryTaskManager.rollback(server, task.id(), server.getTickCount());
                hub.removeTask(task.id());
            }
            restorePackage(level, storage, goal, extracted);
            cancel(level);
            return true;
        }

        private void restorePackage(ServerLevel level, MountedItemStorage storage,
                                    Vec3 goal, ItemStack packageStack) {
            ItemStack remainder = storage.insertItem(slot, packageStack, false);
            if (!remainder.isEmpty()) {
                Block.popResource(level, BlockPos.containing(goal), remainder);
            }
        }

        private void unlock() {
            if (!locked) return;
            locked = false;
            entity.getBusyLocks().remove(token);
            if (entity.isAlive()) {
                entity.noPhysics = previousNoPhysics;
                entity.setNoGravity(previousNoGravity);
            }
        }

        private void cancel(ServerLevel level) {
            unlock();
            if (level == null) return;
            if (level.hasChunkAt(hubPos)
                    && (!(level.getBlockEntity(hubPos) instanceof CobblemonHubBlockEntity hub)
                    || !hub.hasWorker(worker.pokemonUuid()))) {
                if (!ResidentWorkerRuntime.release(worker, level)) {
                    DeliveryTaskSavedData.get(level.getServer()).queueWorkerReturn(worker);
                }
                return;
            }
            if (!entity.isAlive() || entity.level() != level) return;
            try {
                WorkerLease away = worker.withHomeReported(false);
                CarrierRuntimeLease.holdReturn(away, entity);
                ResidentWorkerRuntime.beginReturn(level, away, entity);
            } catch (RuntimeException ignored) {
                // The Hub restores an unreported resident when storage reloads.
            }
        }
    }
}
