package dev.cobblemoncreate.logistics;

import dev.cobblemoncreate.logistics.cobblemon.CarrierRuntimeLease;
import dev.cobblemoncreate.logistics.cobblemon.ResidentWorkerRuntime;
import dev.cobblemoncreate.logistics.hub.CobblemonHubBlockEntity;
import dev.cobblemoncreate.logistics.hub.HubVisualState;
import dev.cobblemoncreate.logistics.hub.HubAddressRegistry;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Transaction boundary for delivery state changes. All mutations go through
 * this class so future Create port hooks cannot accidentally duplicate cargo.
 */
public final class DeliveryTaskManager {
    private DeliveryTaskManager() {}

    public static DeliveryTask submit(MinecraftServer server, ItemStack originalPackage,
                                      ResourceKey<Level> sourceDimension, BlockPos sourcePos,
                                      ResourceKey<Level> targetDimension, BlockPos targetPos,
                                      long gameTime) {
        DeliveryTask task = DeliveryTask.create(originalPackage, sourceDimension, sourcePos,
                targetDimension, targetPos, gameTime);
        DeliveryTaskSavedData.get(server).add(task);
        return task;
    }

    public static boolean claim(MinecraftServer server, UUID taskId, WorkerLease worker, long gameTime) {
        DeliveryTask task = require(server, taskId);
        if (!task.isOutgoingBuffer() || !task.hasCourierDestination()) return false;
        ServerLevel level = server.getLevel(worker.dimension());
        // A returning courier cannot accept a second package until its Hub
        // has acknowledged the physical home report. This also prevents a
        // stale nearby entity from being dispatched before it has departed.
        if (level == null || !worker.homeReported()) return false;
        if (ResidentWorkerRuntime.workerSleeping(level, worker)) return false;
        CarrierProfile profile = CarrierProfiles.resolve(level, worker);
        if (profile == null) return false;
        boolean sameDimension = task.sourceDimension().equals(task.targetDimension());
        boolean corridorLoaded = sameDimension
                && RouteChunks.loaded(level, task.sourcePos(), task.targetPos());
        if (!TransportRouting.canCarry(profile, sameDimension,
                corridorLoaded) || !canUseHubEndpoints(server, task, worker, profile)) return false;
        return claimWithProfile(server, task, worker, profile,
                TransportRouting.mode(sameDimension, corridorLoaded), gameTime, false);
    }

    /** An external Ghost pickup has already marked this worker away from its Hub. */
    static boolean claimReservedGhostPickup(MinecraftServer server, DeliveryTask task,
                                            WorkerLease worker, long gameTime) {
        if (task == null || !task.isOutgoingBuffer() || !task.hasCourierDestination()) return false;
        ServerLevel level = server.getLevel(worker.dimension());
        if (level == null) return false;
        CarrierProfile profile = CarrierProfiles.resolve(level, worker);
        if (profile == null || !profile.ghost()) return false;
        boolean sameDimension = task.sourceDimension().equals(task.targetDimension());
        boolean corridorLoaded = sameDimension
                && RouteChunks.loaded(level, task.sourcePos(), task.targetPos());
        return TransportRouting.canCarry(profile, sameDimension, corridorLoaded)
                && canUseHubEndpoints(server, task, worker, profile)
                && claimWithProfile(server, task, worker, profile,
                TransportRouting.mode(sameDimension, corridorLoaded), gameTime, true);
    }

    private static boolean claimWithProfile(MinecraftServer server, DeliveryTask task,
                                             WorkerLease worker, CarrierProfile profile,
                                             TransportMode mode, long gameTime, boolean reservedPickup) {
        ServerLevel level = server.getLevel(worker.dimension());
        if (level == null || !worker.homeReported() && !reservedPickup
                || ResidentWorkerRuntime.workerSleeping(level, worker)
                || CarrierRuntimeLease.acquire(level, task.id(), worker, profile,
                task.packageStack()).isEmpty()) return false;
        if (level.getBlockEntity(worker.anchor()) instanceof CobblemonHubBlockEntity hub) {
            hub.markWorkerAway(worker.pokemonUuid());
        }
        task.claim(worker.withHomeReported(false), mode, gameTime);
        CobblemonCreateLogistics.LOGGER.info("Delivery {} assigned to {} ({}) from {} to {} via {}",
                task.id(), worker.species(), worker.pokemonUuid(), task.sourcePos(), task.targetPos(), mode);
        DeliveryTaskSavedData.get(server).markDirtyNow();
        return true;
    }

    public static boolean startTransit(MinecraftServer server, UUID taskId, long gameTime) {
        return startTransit(server, taskId, gameTime, false);
    }

    /** Called only after the Ghost's real mounted-storage extraction commits. */
    static boolean startTransitAfterGhostPickup(MinecraftServer server, UUID taskId, long gameTime) {
        return startTransit(server, taskId, gameTime, true);
    }

    private static boolean startTransit(MinecraftServer server, UUID taskId, long gameTime,
                                        boolean mountedPickupCommitted) {
        DeliveryTask task = require(server, taskId);
        if (task.state() != DeliveryState.CLAIMED_BY_WORKER || !task.hasCourierDestination()) return false;
        PokemonEntity entity = CarrierRuntimeLease.entity(taskId);
        ServerLevel level = server.getLevel(task.sourceDimension());
        if (entity == null || level == null || entity.level() != level || !entity.isAlive()) return false;
        if (mountedPickupCommitted) {
            if (!CarrierRuntimeLease.isGhost(entity)) return false;
        } else {
            if (!CarrierRuntimeLease.isGhost(entity) && !hasEndpointSpace(level, entity, task.sourcePos())) {
                CarrierRuntimeLease.setEndpointWait(taskId, "pickup_space");
                task.noteEndpointWait(gameTime);
                return false;
            }
            Vec3 pickup = ResidentWorkerRuntime.physicalInteractionPosition(level, entity, task.sourcePos());
            if (entity.position().distanceToSqr(pickup) > 6.25D) return false;
            if (!HubCourierAccess.canExchange(level, entity, task.sourcePos())) {
                CarrierRuntimeLease.setEndpointWait(taskId, "pickup_obstructed");
                task.noteEndpointWait(gameTime);
                return false;
            }
        }
        CarrierRuntimeLease.setEndpointWait(taskId, "");
        CarrierRuntimeLease.setRouteDestination(taskId, task.targetPos());
        if (task.mode() == TransportMode.LOCAL) {
            ResidentWorkerRuntime.prepareRoute(level, task.worker(), entity, task.targetPos());
            CarrierRuntimeLease.startCarrying(taskId);
            CarrierRuntimeLease.setRouteDestination(taskId, task.targetPos());
            CarrierRuntimeLease.setNavigationTarget(taskId, task.targetPos(),
                    CarrierProfiles.resolve(level, task.worker()).speedMultiplier());
        } else {
            entity.getNavigation().stop();
            CarrierRuntimeLease.startCarrying(taskId);
            CarrierRuntimeLease.setRouteDestination(taskId, task.targetPos());
        }
        task.startTransit(gameTime);
        DeliveryTaskSavedData.get(server).markDirtyNow();
        if (level.hasChunkAt(task.sourcePos())
                && level.getBlockEntity(task.sourcePos()) instanceof CobblemonHubBlockEntity hub) {
            Vec3 point = mountedPickupCommitted ? entity.position()
                    : ResidentWorkerRuntime.physicalInteractionPosition(level, entity, task.sourcePos());
            hub.emitTransfer(task, HubVisualState.Kind.COURIER_PICKUP, HubVisualState.sideAt(task.sourcePos(), point), point);
        }
        return true;
    }

    public static boolean arrive(MinecraftServer server, UUID taskId, long gameTime) {
        DeliveryTask task = require(server, taskId);
        if (task.state() != DeliveryState.IN_TRANSIT) return false;
        ServerLevel targetLevel = server.getLevel(task.targetDimension());
        if (targetLevel == null || !targetLevel.hasChunkAt(task.targetPos())) return false;
        BlockEntity target = targetLevel.getBlockEntity(task.targetPos());
        if (!(target instanceof CobblemonHubBlockEntity hub) || !hub.canAcceptIncomingTask(task.id())) {
            return false;
        }
        if (task.worker() != null && task.sourceDimension().equals(task.targetDimension())) {
            PokemonEntity entity = CarrierRuntimeLease.entity(taskId);
            if (entity == null || entity.level() != targetLevel || CarrierRuntimeLease.isRemoteGapActive(taskId)
                    || entity.position().distanceToSqr(ResidentWorkerRuntime.physicalInteractionPosition(
                    targetLevel, entity, task.targetPos())) > 3.0625D
                    || !HubCourierAccess.canExchange(targetLevel, entity, task.targetPos())) return false;
        }
        PokemonEntity courier = CarrierRuntimeLease.entity(task.id());
        Vec3 interactionPoint = courier != null && courier.level() == targetLevel
                ? ResidentWorkerRuntime.physicalInteractionPosition(targetLevel, courier, task.targetPos())
                : ResidentWorkerRuntime.interactionPosition(task.targetPos());
        if (task.worker() != null && !beginCourierReturn(server, task)) return false;
        if (task.mode() == TransportMode.VIRTUAL) {
            CarrierRuntimeLease.arrivalEffect(task.id(), targetLevel,
                    ResidentWorkerRuntime.interactionPosition(task.targetPos()));
        }
        task.arrive(gameTime);
        CobblemonCreateLogistics.LOGGER.info("Delivery {} handed off at {}; courier {} is returning",
                task.id(), task.targetPos(), task.worker() == null ? "none" : task.worker().pokemonUuid());
        ServerLevel sourceLevel = server.getLevel(task.sourceDimension());
        if (sourceLevel != null && sourceLevel.hasChunkAt(task.sourcePos())
                && sourceLevel.getBlockEntity(task.sourcePos()) instanceof CobblemonHubBlockEntity sourceHub) {
            sourceHub.removeTask(task.id());
        }
        hub.acceptIncomingTask(task.id());
        DeliveryTaskSavedData.get(server).markDirtyNow();
        hub.emitTransfer(task, HubVisualState.Kind.COURIER_RECEIVE,
                HubVisualState.sideAt(task.targetPos(), interactionPoint), interactionPoint);
        return true;
    }

    /** Removes one arrived package from its target Hub without rebuilding it. */
    public static ItemStack collectArrivedPackage(MinecraftServer server, UUID taskId, long gameTime) {
        DeliveryTask task = require(server, taskId);
        if (task.state() != DeliveryState.ARRIVED_BUFFERED) return ItemStack.EMPTY;
        ItemStack copy = task.packageCopy();
        task.deliver(gameTime);
        DeliveryTaskSavedData data = DeliveryTaskSavedData.get(server);
        data.remove(task.id());
        clearTaskVisuals(server, task);
        return copy;
    }

    /** Returns an unroutable source package to its owner without creating another cargo stack. */
    public static ItemStack reclaimBufferedPackage(MinecraftServer server, UUID taskId, long gameTime) {
        DeliveryTask task = require(server, taskId);
        if (task.state() != DeliveryState.BUFFERED) return ItemStack.EMPTY;
        ItemStack copy = task.packageCopy();
        task.rollback(gameTime);
        DeliveryTaskSavedData.get(server).remove(task.id());
        clearTaskVisuals(server, task);
        return copy;
    }

    /** Allows the source Hub to recover a paused delivery without duplicating its visual cargo. */
    public static ItemStack reclaimPausedPackage(MinecraftServer server, UUID taskId, long gameTime) {
        DeliveryTask task = require(server, taskId);
        if (task.state() != DeliveryState.PAUSED) return ItemStack.EMPTY;
        ItemStack copy = task.packageCopy();
        task.rollback(gameTime);
        CarrierRuntimeLease.release(task.id());
        DeliveryTaskSavedData.get(server).remove(task.id());
        clearTaskVisuals(server, task);
        return copy;
    }

    /** Cargo is still at the source until pickup commits; cancel the lease and send its courier home. */
    public static ItemStack reclaimClaimedPackage(MinecraftServer server, UUID taskId, long gameTime) {
        DeliveryTask task = require(server, taskId);
        if (task.state() != DeliveryState.CLAIMED_BY_WORKER || task.worker() == null
                || !beginCourierReturn(server, task)) return ItemStack.EMPTY;
        ItemStack copy = task.packageCopy();
        task.rollback(gameTime);
        DeliveryTaskSavedData.get(server).remove(task.id());
        clearTaskVisuals(server, task);
        return copy;
    }

    public static boolean deliver(MinecraftServer server, UUID taskId, long gameTime) {
        DeliveryTask task = require(server, taskId);
        if (task.state() != DeliveryState.ARRIVED_BUFFERED) return false;
        task.deliver(gameTime);
        DeliveryTaskSavedData.get(server).remove(task.id());
        clearTaskVisuals(server, task);
        return true;
    }

    private static boolean beginCourierReturn(MinecraftServer server, DeliveryTask task) {
        ServerLevel sourceLevel = server.getLevel(task.sourceDimension());
        PokemonEntity entity = CarrierRuntimeLease.entity(task.id());
        if (sourceLevel == null || entity == null || entity.level() != sourceLevel || !entity.isAlive()) return false;
        if (!CarrierRuntimeLease.beginReturn(task.id())) return false;
        ResidentWorkerRuntime.beginReturn(sourceLevel, task.worker(), entity);
        return true;
    }

    public static boolean rollback(MinecraftServer server, UUID taskId, long gameTime) {
        DeliveryTask task = require(server, taskId);
        if (task.state().terminal()) return false;
        task.rollback(gameTime);
        CarrierRuntimeLease.release(task.id());
        DeliveryTaskSavedData.get(server).remove(task.id());
        clearTaskVisuals(server, task);
        return true;
    }

    /**
     * Ejects a task's single authoritative package when its Hub is broken.
     * Arrived cargo is committed as delivered; cargo still in transit is
     * rolled back first. In both cases the returned stack is the only stack
     * handed to the world.
     */
    public static ItemStack ejectToWorld(MinecraftServer server, UUID taskId, long gameTime) {
        DeliveryTask task = require(server, taskId);
        if (task.state().terminal()) return ItemStack.EMPTY;
        ItemStack copy = task.packageCopy();
        if (task.state() == DeliveryState.ARRIVED_BUFFERED) {
            task.deliver(gameTime);
        } else {
            task.rollback(gameTime);
        }
        CarrierRuntimeLease.release(task.id());
        DeliveryTaskSavedData.get(server).remove(task.id());
        clearTaskVisuals(server, task);
        return copy;
    }

    private static void clearTaskVisuals(MinecraftServer server, DeliveryTask task) {
        clearHubVisual(server.getLevel(task.sourceDimension()), task.sourcePos(), task.id());
        if (!task.targetDimension().equals(task.sourceDimension()) || !task.targetPos().equals(task.sourcePos())) {
            clearHubVisual(server.getLevel(task.targetDimension()), task.targetPos(), task.id());
        }
    }

    private static void clearHubVisual(ServerLevel level, BlockPos pos, UUID taskId) {
        if (level != null && level.hasChunkAt(pos)
                && level.getBlockEntity(pos) instanceof CobblemonHubBlockEntity hub) hub.cancelVisualTask(taskId);
    }

    /**
     * Tick hook intentionally does no chunk loading and no entity duplication.
     * Carrier navigation and Create target handoff will call the transactions
     * above when their endpoints are actually available.
     */
    public static void tick(MinecraftServer server) {
        DeliveryTaskSavedData data = DeliveryTaskSavedData.get(server);
        for (DeliveryTask task : data.tasks()) {
            if (!task.state().terminal() && task.state() != DeliveryState.ARRIVED_BUFFERED
                    && task.hasCourierDestination() && targetWasRemoved(server, task)) {
                clearTaskVisuals(server, task);
                if (task.worker() != null) beginCourierReturn(server, task);
                CarrierRuntimeLease.release(task.id());
                task.resetDestination(server.getTickCount());
                data.markDirtyNow();
            }
            if (task.isOutgoingBuffer() && !task.hasCourierDestination()) {
                resolveBufferedDestination(server, task);
            }
            if (task.state() == DeliveryState.ARRIVED_BUFFERED) {
                recoverArrivedTask(server, task);
            }
            if (task.state() == DeliveryState.PAUSED && task.worker() != null) {
                if (courierReportedHome(server, task)) {
                    task.requeueAfterCourierReturn(server.getTickCount());
                    data.markDirtyNow();
                    continue;
                }
                tryResumePausedTask(server, task);
            }
            if (task.isOutgoingBuffer() && task.hasCourierDestination()) {
                dispatchBufferedTask(server, task);
            }
            if (task.state() == DeliveryState.IN_TRANSIT) {
                if (task.mode() == TransportMode.VIRTUAL) tickVirtualTransit(server, task);
                else tickLocalTransit(server, task);
            }
            if (task.state() == DeliveryState.CLAIMED_BY_WORKER) {
                tickPickup(server, task);
            }
            if ((task.state() == DeliveryState.CLAIMED_BY_WORKER
                    || task.state() == DeliveryState.IN_TRANSIT && task.mode() == TransportMode.LOCAL)
                    && task.worker() != null && !CarrierRuntimeLease.isHeld(task.id())) {
                task.pause(server.getTickCount(), PauseReason.CARRIER_UNAVAILABLE);
                data.markDirtyNow();
            }
        }
    }

    private static boolean targetWasRemoved(MinecraftServer server, DeliveryTask task) {
        ServerLevel level = server.getLevel(task.targetDimension());
        if (level == null || !level.hasChunkAt(task.targetPos())) return false;
        return !(level.getBlockEntity(task.targetPos()) instanceof CobblemonHubBlockEntity hub)
                || !PackageItem.matchAddress(task.packageStack(), hub.address());
    }

    private static boolean courierReportedHome(MinecraftServer server, DeliveryTask task) {
        if (task.worker() == null) return false;
        ServerLevel level = server.getLevel(task.worker().dimension());
        return level != null && level.hasChunkAt(task.worker().anchor())
                && level.getBlockEntity(task.worker().anchor()) instanceof CobblemonHubBlockEntity hub
                && hub.isWorkerHomeReported(task.worker().pokemonUuid())
                && !CarrierRuntimeLease.isWorkerActive(task.worker().pokemonUuid());
    }

    private static void resolveBufferedDestination(MinecraftServer server, DeliveryTask task) {
        ServerLevel level = server.getLevel(task.sourceDimension());
        if (level == null || !level.hasChunkAt(task.sourcePos())
                || !(level.getBlockEntity(task.sourcePos()) instanceof CobblemonHubBlockEntity hub)) return;
        if (task.applyDefaultAddress(hub.defaultAddress())) {
            DeliveryTaskSavedData.get(server).markDirtyNow();
        }
        HubAddressRegistry.Destination destination = hub.resolveDestinationTarget(task.packageStack());
        if (destination != null) {
            task.setTarget(destination.dimension(), destination.pos());
            CobblemonCreateLogistics.LOGGER.info(
                    "Delivery {} resolved address '{}' to {} {}",
                    task.id(), PackageItem.getAddress(task.packageStack()),
                    destination.dimension().location(), destination.pos());
            DeliveryTaskSavedData.get(server).markDirtyNow();
        } else if (server.getTickCount() % 200 == 0) {
            CobblemonCreateLogistics.LOGGER.warn(
                    "Delivery {} is waiting for an addressed Hub (package address '{}', source default '{}')",
                    task.id(), PackageItem.getAddress(task.packageStack()), hub.defaultAddress());
        }
    }

    /** The courier is recorded before this durable endpoint handoff starts. */
    private static void tickVirtualTransit(MinecraftServer server, DeliveryTask task) {
        if (task.worker() == null || task.state() != DeliveryState.IN_TRANSIT) return;
        ServerLevel targetLevel = server.getLevel(task.targetDimension());
        boolean sameDimension = task.sourceDimension().equals(task.targetDimension());
        if (sameDimension) {
            CarrierProfile profile = CarrierProfiles.resolve(server.getLevel(task.worker().dimension()), task.worker());
            tickRemoteRoute(server, task, profile == null ? 1.3D : profile.speedMultiplier());
            return;
        }
        if (targetLevel == null || !targetLevel.hasChunkAt(task.targetPos())) return;
        BlockEntity target = targetLevel.getBlockEntity(task.targetPos());
        if (!(target instanceof CobblemonHubBlockEntity hub)
                || !hub.canAcceptIncomingTask(task.id())) return;
        CarrierProfile profile = CarrierProfiles.resolve(server.getLevel(task.worker().dimension()), task.worker());
        double speed = profile == null ? 1.0D : profile.speedMultiplier();
        if (server.getTickCount() - task.lastProgressGameTime() < Math.ceil(200.0D / speed)) return;
        CobblemonCreateLogistics.LOGGER.info("Virtual delivery {} committing at {} {}",
                task.id(), task.targetDimension().location(), task.targetPos());
        arrive(server, task.id(), server.getTickCount());
    }

    /** One bounded visible segment or gap, preserving the selected movement speed. */
    private static void tickRemoteRoute(MinecraftServer server, DeliveryTask task, double speed) {
        ServerLevel sourceLevel = server.getLevel(task.sourceDimension());
        ServerLevel targetLevel = server.getLevel(task.targetDimension());
        if (sourceLevel == null) return;
        if (!CarrierRuntimeLease.isHeld(task.id())) {
            ResidentWorkerRuntime.ensure(sourceLevel, task.worker());
            CarrierProfile profile = CarrierProfiles.resolve(sourceLevel, task.worker());
            if (profile == null || !profile.canUseRemoteHandoff()
                    || CarrierRuntimeLease.acquire(sourceLevel, task.id(), task.worker(),
                    profile, task.packageStack()).isEmpty()) return;
            CarrierRuntimeLease.startCarrying(task.id());
        }
        PokemonEntity entity = CarrierRuntimeLease.entity(task.id());
        if (entity == null || !entity.isAlive() || entity.level() != sourceLevel
                || sourceLevel.getEntity(entity.getId()) != entity) {
            CarrierRuntimeLease.release(task.id());
            return;
        }
        if (ResidentWorkerRuntime.resting(entity) && !CarrierRuntimeLease.isRemoteGapActive(task.id())) {
            entity.getNavigation().stop();
            CarrierRuntimeLease.setEndpointWait(task.id(), "resting");
            task.noteEndpointWait(server.getTickCount());
            return;
        }
        if (!CarrierRuntimeLease.isRemoteGapActive(task.id())
                && RouteChunks.canTravel(sourceLevel, task.targetPos())
                && entity.position().distanceToSqr(Vec3.atCenterOf(task.targetPos())) < 64.0D
                && !CarrierRuntimeLease.isGhost(entity)
                && !hasEndpointSpace(sourceLevel, entity, task.targetPos())) {
            CarrierRuntimeLease.setEndpointWait(task.id(), "delivery_space");
            task.noteEndpointWait(server.getTickCount());
            return;
        }
        boolean atDestination = CarrierRuntimeLease.driveRoute(sourceLevel, task.worker(), entity,
                task.targetPos(), speed);
        if (CarrierRuntimeLease.isRemoteGapActive(task.id()) && task.mode() == TransportMode.LOCAL) {
            task.switchToVirtualTransit();
            DeliveryTaskSavedData.get(server).markDirtyNow();
        }
        CarrierRuntimeLease.setEndpointWait(task.id(), "");
        if (!CarrierRuntimeLease.isRemoteGapActive(task.id())
                && RouteChunks.canTravel(sourceLevel, task.targetPos())
                && entity.position().distanceToSqr(ResidentWorkerRuntime.physicalInteractionPosition(
                sourceLevel, entity, task.targetPos())) <= 6.25D
                && !HubCourierAccess.canExchange(sourceLevel, entity, task.targetPos())) {
            CarrierRuntimeLease.setEndpointWait(task.id(), "delivery_obstructed");
        }
        if (atDestination && targetLevel != null
                && targetLevel.hasChunkAt(task.targetPos())
                && targetLevel.getBlockEntity(task.targetPos()) instanceof CobblemonHubBlockEntity hub
                && hub.canAcceptIncomingTask(task.id())) {
            arrive(server, task.id(), server.getTickCount());
        } else if (atDestination) {
            CarrierRuntimeLease.setEndpointWait(task.id(), "waiting_capacity");
        }
    }

    private static void tryResumePausedTask(MinecraftServer server, DeliveryTask task) {
        // A paused physical trip is resumed only after its loaded route can
        // be navigated again. Virtual trips are durable and do not pause for
        // intermediate chunks.
        if (task.mode() != TransportMode.LOCAL
                || !task.sourceDimension().equals(task.targetDimension())) {
            return;
        }
        if (CarrierRuntimeLease.isReturning(task.id())
                || server.getTickCount() - task.lastProgressGameTime() < task.retryDelayTicks()) return;
        ServerLevel level = server.getLevel(task.worker().dimension());
        if (level == null || !RouteChunks.loaded(level, task.sourcePos(), task.targetPos())) return;
        ResidentWorkerRuntime.ensure(level, task.worker());
        task.resume(server.getTickCount());
        if (task.state() != DeliveryState.CLAIMED_BY_WORKER
                && task.state() != DeliveryState.IN_TRANSIT) return;
        CarrierProfile profile = CarrierProfiles.resolve(level, task.worker());
        if (profile != null && CarrierRuntimeLease.acquire(level, task.id(), task.worker(),
                profile, task.packageStack()).isPresent()) {
            if (task.state() == DeliveryState.IN_TRANSIT) {
                PokemonEntity entity = CarrierRuntimeLease.entity(task.id());
                ResidentWorkerRuntime.prepareRoute(level, task.worker(), entity, task.targetPos());
                CarrierRuntimeLease.startCarrying(task.id());
            }
            DeliveryTaskSavedData.get(server).markDirtyNow();
        } else {
            task.pause(server.getTickCount(), PauseReason.CARRIER_UNAVAILABLE);
            DeliveryTaskSavedData.get(server).markDirtyNow();
        }
    }

    private static void dispatchBufferedTask(MinecraftServer server, DeliveryTask task) {
        ServerLevel sourceLevel = server.getLevel(task.sourceDimension());
        if (sourceLevel == null || !sourceLevel.hasChunkAt(task.sourcePos())) return;
        BlockEntity source = sourceLevel.getBlockEntity(task.sourcePos());
        if (!(source instanceof CobblemonHubBlockEntity hub)) return;
        boolean sameDimension = task.sourceDimension().equals(task.targetDimension());
        Boolean corridorLoaded = null;
        for (WorkerLease worker : hub.workers()) {
            CarrierProfile profile = CarrierProfiles.resolve(sourceLevel, worker);
            if (profile == null) continue;
            if (sameDimension && corridorLoaded == null) {
                corridorLoaded = RouteChunks.loaded(sourceLevel, task.sourcePos(), task.targetPos());
            }
            if (!TransportRouting.canCarry(profile, sameDimension,
                    Boolean.TRUE.equals(corridorLoaded))
                    || !canUseHubEndpoints(server, task, worker, profile)) continue;
            TransportMode mode = TransportRouting.mode(sameDimension, Boolean.TRUE.equals(corridorLoaded));
            if (claimWithProfile(server, task, worker, profile, mode, server.getTickCount(), false)) {
                break;
            }
        }
    }

    private static boolean canUseHubEndpoints(MinecraftServer server, DeliveryTask task,
                                              WorkerLease worker, CarrierProfile profile) {
        // Cross-dimension delivery is an endpoint handoff. The courier never
        // swims through an unloaded dimension, so only a same-dimension
        // physical swimming route needs water at both Hubs.
        if (!profile.needsFluidHub()
                || !task.sourceDimension().equals(task.targetDimension())) return true;
        ServerLevel source = server.getLevel(task.sourceDimension());
        ServerLevel target = server.getLevel(task.targetDimension());
        return source != null && target != null
                && CarrierProfiles.canAccessHub(source, task.sourcePos(), profile, worker)
                && CarrierProfiles.canAccessHub(target, task.targetPos(), profile, worker);
    }

    private static void tickPickup(MinecraftServer server, DeliveryTask task) {
        ServerLevel level = server.getLevel(task.sourceDimension());
        PokemonEntity entity = CarrierRuntimeLease.entity(task.id());
        if (level == null || entity == null || entity.level() != level || !entity.isAlive()) {
            task.pause(server.getTickCount(), PauseReason.CARRIER_UNAVAILABLE);
            CarrierRuntimeLease.release(task.id());
            DeliveryTaskSavedData.get(server).markDirtyNow();
            return;
        }
        if (ResidentWorkerRuntime.resting(entity)) {
            entity.getNavigation().stop();
            CarrierRuntimeLease.setEndpointWait(task.id(), "resting");
            task.noteEndpointWait(server.getTickCount());
            return;
        }
        Vec3 pickupPoint = ResidentWorkerRuntime.physicalInteractionPosition(level, entity, task.sourcePos());
        double distance = entity.position().distanceTo(pickupPoint);
        CarrierRuntimeLease.setEndpointWait(task.id(), "");
        if (!CarrierRuntimeLease.isGhost(entity) && !hasEndpointSpace(level, entity, task.sourcePos())) {
            CarrierRuntimeLease.setEndpointWait(task.id(), "pickup_space");
            task.noteEndpointWait(server.getTickCount());
            return;
        }
        task.noteMovement(entity.position(), server.getTickCount());
        if (distance <= 2.5D && HubCourierAccess.canExchange(level, entity, task.sourcePos())) {
            startTransit(server, task.id(), server.getTickCount());
            return;
        }
        CarrierRuntimeLease.setNavigationTarget(task.id(), task.sourcePos(),
                CarrierProfiles.resolve(level, task.worker()).speedMultiplier());
        ResidentWorkerRuntime.navigatePhysicalTo(level, entity, task.sourcePos(),
                CarrierProfiles.resolve(level, task.worker()).speedMultiplier());
        if (distance <= 2.5D) {
            CarrierRuntimeLease.setEndpointWait(task.id(), "pickup_obstructed");
            task.noteEndpointWait(server.getTickCount());
            // A closed endpoint is retried in place, not requeued as lost cargo.
            return;
        }
        if (server.getTickCount() - task.lastProgressGameTime() > 1200) {
            task.pause(server.getTickCount(), PauseReason.NO_PROGRESS);
            if (!beginCourierReturn(server, task)) CarrierRuntimeLease.release(task.id());
            DeliveryTaskSavedData.get(server).markDirtyNow();
        }
    }

    private static void tickLocalTransit(MinecraftServer server, DeliveryTask task) {
        if (task.worker() == null) {
            return;
        }
        if (task.mode() != TransportMode.LOCAL
                || !task.sourceDimension().equals(task.targetDimension())) {
            task.pause(server.getTickCount(), PauseReason.ROUTE_UNAVAILABLE);
            CarrierRuntimeLease.release(task.id());
            DeliveryTaskSavedData.get(server).markDirtyNow();
            return;
        }
        ServerLevel level = server.getLevel(task.worker().dimension());
        CarrierProfile profile = level == null ? null : CarrierProfiles.resolve(level, task.worker());
        if (profile != null && profile.canUseRemoteHandoff()) {
            tickRemoteRoute(server, task, profile.speedMultiplier());
            return;
        }
        boolean routeLost = level == null || CarrierRuntimeLease.isRemoteGapActive(task.id())
                || !level.hasChunkAt(task.targetPos())
                || server.getTickCount() % 100 == 0
                && !RouteChunks.loaded(level, task.sourcePos(), task.targetPos());
        if (routeLost) {
            task.pause(server.getTickCount(), PauseReason.CHUNKS_UNLOADED);
            if (!beginCourierReturn(server, task)) CarrierRuntimeLease.release(task.id());
            DeliveryTaskSavedData.get(server).markDirtyNow();
            return;
        }
        PokemonEntity entity = CarrierRuntimeLease.entity(task.id());
        if (entity == null || entity.level() != level || !entity.isAlive()
                || level.getEntity(entity.getId()) != entity) {
            task.pause(server.getTickCount(), PauseReason.CARRIER_UNAVAILABLE);
            CarrierRuntimeLease.release(task.id());
            DeliveryTaskSavedData.get(server).markDirtyNow();
            return;
        }

        if (ResidentWorkerRuntime.resting(entity)) {
            entity.getNavigation().stop();
            CarrierRuntimeLease.setEndpointWait(task.id(), "resting");
            task.noteEndpointWait(server.getTickCount());
            return;
        }
        Vec3 deliveryPoint = ResidentWorkerRuntime.physicalInteractionPosition(level, entity, task.targetPos());
        double distance = entity.position().distanceTo(deliveryPoint);
        CarrierRuntimeLease.setEndpointWait(task.id(), "");
        if (!CarrierRuntimeLease.isGhost(entity) && !hasEndpointSpace(level, entity, task.targetPos())) {
            CarrierRuntimeLease.setEndpointWait(task.id(), "delivery_space");
            task.noteEndpointWait(server.getTickCount());
            return;
        }
        if (distance <= 1.75D && HubCourierAccess.canExchange(level, entity, task.targetPos())) {
            if (!arrive(server, task.id(), server.getTickCount())) {
                CarrierRuntimeLease.setEndpointWait(task.id(), "waiting_capacity");
            }
            return;
        }
        ResidentWorkerRuntime.prepareRoute(level, task.worker(), entity, task.targetPos());
        task.noteMovement(entity.position(), server.getTickCount());
        if (distance <= 1.75D) {
            CarrierRuntimeLease.setEndpointWait(task.id(), "delivery_obstructed");
            task.noteEndpointWait(server.getTickCount());
            ResidentWorkerRuntime.navigatePhysicalTo(level, entity, task.targetPos(),
                    CarrierProfiles.resolve(level, task.worker()).speedMultiplier());
            return;
        }
        if (server.getTickCount() - task.lastProgressGameTime() > 1200) {
            task.pause(server.getTickCount(), PauseReason.NO_PROGRESS);
            if (!beginCourierReturn(server, task)) CarrierRuntimeLease.release(task.id());
            DeliveryTaskSavedData.get(server).markDirtyNow();
            return;
        }
        ResidentWorkerRuntime.navigatePhysicalTo(level, entity, task.targetPos(),
                CarrierProfiles.resolve(level, task.worker()).speedMultiplier());
        CarrierRuntimeLease.setNavigationTarget(task.id(), task.targetPos(),
                CarrierProfiles.resolve(level, task.worker()).speedMultiplier());
    }

    private static boolean hasEndpointSpace(ServerLevel level, PokemonEntity entity, BlockPos hub) {
        if (!entity.canFly() && (!entity.canWalk() || entity.getPokemon().getForm().getBehaviour()
                .getMoving().getWalk().getAvoidsLand())) {
            return CarrierProfiles.swimmingEntrance(level, hub,
                    entity.canSwimInWater(), entity.canSwimInLava()) != null;
        }
        return CarrierRuntimeLease.endpointApproach(level, entity, hub) != null;
    }

    /**
     * Reconnects the durable task to its Hub after a save/load boundary. The
     * Hub stores only the UUID, so this is safe to retry and never recreates a
     * second package stack.
     */
    private static void recoverArrivedTask(MinecraftServer server, DeliveryTask task) {
        ServerLevel targetLevel = server.getLevel(task.targetDimension());
        if (targetLevel == null || !targetLevel.hasChunkAt(task.targetPos())) return;
        BlockEntity target = targetLevel.getBlockEntity(task.targetPos());
        if (target instanceof CobblemonHubBlockEntity hub
                && hub.canAcceptIncomingTask(task.id())
                && !hub.hasTask(task.id())) {
            ServerLevel sourceLevel = server.getLevel(task.sourceDimension());
            if (sourceLevel != null && sourceLevel.hasChunkAt(task.sourcePos())
                    && sourceLevel.getBlockEntity(task.sourcePos()) instanceof CobblemonHubBlockEntity sourceHub) {
                sourceHub.removeTask(task.id());
            }
            hub.acceptIncomingTask(task.id());
            DeliveryTaskSavedData.get(server).markDirtyNow();
        }
    }

    private static DeliveryTask require(MinecraftServer server, UUID taskId) {
        DeliveryTask task = DeliveryTaskSavedData.get(server).get(taskId);
        if (task == null) throw new IllegalArgumentException("Unknown delivery task " + taskId);
        return task;
    }
}
