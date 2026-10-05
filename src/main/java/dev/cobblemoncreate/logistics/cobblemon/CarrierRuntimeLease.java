package dev.cobblemoncreate.logistics.cobblemon;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.cobblemoncreate.logistics.CarrierProfile;
import dev.cobblemoncreate.logistics.CarrierProfiles;
import dev.cobblemoncreate.logistics.CobblemonCreateLogistics;
import dev.cobblemoncreate.logistics.RouteChunks;
import dev.cobblemoncreate.logistics.TransportCapability;
import dev.cobblemoncreate.logistics.WorkerLease;
import dev.cobblemoncreate.logistics.JourneyProgress;
import dev.cobblemoncreate.logistics.HubCourierAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Runtime-only lock and visual attachment; never serialized into task data. */
public final class CarrierRuntimeLease {
    private static final Map<UUID, RuntimeLock> ACTIVE = new HashMap<>();
    private static final Map<PokemonEntity, RuntimeLock> BY_ENTITY = new IdentityHashMap<>();
    private static final Map<UUID, RuntimeLock> BY_WORKER = new HashMap<>();
    private static final String GAP_DATA = "CobblemonLogisticsGap";

    private CarrierRuntimeLease() {}

    public static Optional<PokemonEntity> acquire(ServerLevel level, UUID taskId,
                                                   WorkerLease worker, CarrierProfile profile,
                                                   ItemStack packageStack) {
        if (ACTIVE.containsKey(taskId)) {
            return Optional.of(ACTIVE.get(taskId).entity());
        }
        if (ACTIVE.values().stream().anyMatch(lock -> lock.pokemonUuid().equals(worker.pokemonUuid()))) {
            return Optional.empty();
        }
        Optional<CobblemonCarrierAdapter.ActiveCarrier> found =
                CobblemonCarrierAdapter.findActive(level, worker, profile);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PokemonEntity entity = found.get().entity();
        Object token = new Object();
        entity.getBusyLocks().add(token);
        ItemStack previousShownItem = entity.getShownItem().copy();
        RuntimeLock lock = new RuntimeLock(taskId, worker, entity, token,
                packageStack.copy(), previousShownItem, profile.capability(), profile.ghost(),
                entity.isFlying(), entity.isNoGravity(), entity.noPhysics, entity.isInvisible());
        ACTIVE.put(taskId, lock);
        BY_ENTITY.put(entity, lock);
        BY_WORKER.put(worker.pokemonUuid(), lock);
        if (profile.ghost()) enableGhostMotion(lock);
        return Optional.of(entity);
    }

    public static boolean isHeld(UUID taskId) {
        return ACTIVE.containsKey(taskId);
    }

    public static boolean isReturning(UUID taskId) {
        RuntimeLock lock = ACTIVE.get(taskId);
        return lock != null && lock.returning;
    }

    public static boolean isWorkerActive(UUID pokemonId) {
        return BY_WORKER.containsKey(pokemonId);
    }

    /** Retain the delivery's movement lock after its package has been handed off. */
    public static boolean beginReturn(UUID taskId) {
        RuntimeLock lock = ACTIVE.get(taskId);
        if (lock == null) return false;
        lock.returning = true;
        lock.endpointWait = "";
        lock.snapshotTick = -1;
        lock.carrying = false;
        lock.navigationTarget = null;
        lock.remoteGap = null;
        lock.entity().getPersistentData().remove(GAP_DATA);
        lock.remoteWaypoint = null;
        ResidentWorkerRuntime.clearNavigation(lock.entity().getUUID());
        stopMotion(lock.entity());
        lock.entity().setInvisible(lock.previousInvisible);
        lock.entity().noPhysics = lock.ghost || lock.previousNoPhysics;
        lock.entity().setShownItem(lock.previousShownItem());
        lock.routeOrigin = lock.entity().position();
        lock.parkingEndpoint = lock.entity().blockPosition();
        lock.routeDestination = lock.worker.anchor();
        lock.journeyStartedAt = levelOf(lock).getGameTime();
        return true;
    }

    /** Rebuilds a return lock when a Hub restores its resident after reload. */
    public static void holdReturn(WorkerLease worker, PokemonEntity entity) {
        if (isWorkerActive(worker.pokemonUuid())) return;
        TransportCapability capability = TransportCapability.GROUND;
        boolean ghost = false;
        if (entity.level() instanceof ServerLevel level) {
            CarrierProfile profile = CarrierProfiles.resolve(level, worker);
            if (profile != null) {
                capability = profile.capability();
                ghost = profile.ghost();
            }
        }
        UUID leaseId = UUID.randomUUID();
        Object token = new Object();
        entity.getBusyLocks().add(token);
        RuntimeLock lock = new RuntimeLock(leaseId, worker, entity, token,
                ItemStack.EMPTY, entity.getShownItem().copy(), capability, ghost,
                entity.isFlying(), entity.isNoGravity(), entity.noPhysics, entity.isInvisible());
        lock.returning = true;
        ACTIVE.put(leaseId, lock);
        BY_ENTITY.put(entity, lock);
        BY_WORKER.put(worker.pokemonUuid(), lock);
        if (ghost) enableGhostMotion(lock);
    }

    public static void finishReturn(UUID pokemonId) {
        for (RuntimeLock lock : java.util.List.copyOf(ACTIVE.values())) {
            if (lock.pokemonUuid().equals(pokemonId) && lock.returning) {
                detach(lock.taskId);
                return;
            }
        }
    }

    public static void startCarrying(UUID taskId) {
        RuntimeLock lock = ACTIVE.get(taskId);
        if (lock != null) {
            lock.snapshotTick = -1;
            lock.entity().setShownItem(lock.packageStack());
            lock.carrying = true;
            if (lock.remoteGap == null) {
                lock.routeOrigin = lock.entity().position();
                lock.routeDestination = null;
                lock.journeyStartedAt = levelOf(lock).getGameTime();
            }
            if (lock.capability().usesVirtualTransit()
                    && lock.entity().level() instanceof ServerLevel level) {
                sendEffect(level, lock.entity().position(), lock.capability(), 12);
            }
        }
    }

    /** Holds a courier's destination while Cobblemon's other behaviors tick. */
    public static void setNavigationTarget(UUID taskId, BlockPos target, double speed) {
        RuntimeLock lock = ACTIVE.get(taskId);
        if (lock != null) {
            lock.navigationTarget = target.immutable();
            lock.navigationSpeed = speed;
        }
    }

    public static void setRouteDestination(UUID taskId, BlockPos target) {
        RuntimeLock lock = ACTIVE.get(taskId);
        if (lock != null) {
            lock.routeDestination = target.immutable();
            lock.snapshotTick = -1;
        }
    }

    public static JourneyProgress progress(UUID taskId) {
        RuntimeLock lock = ACTIVE.get(taskId);
        return lock == null ? JourneyProgress.NONE : progress(lock);
    }

    public static JourneyProgress workerProgress(UUID pokemonId) {
        RuntimeLock lock = BY_WORKER.get(pokemonId);
        return lock == null ? JourneyProgress.NONE : progress(lock);
    }

    public static void setEndpointWait(UUID taskId, String phase) {
        RuntimeLock lock = ACTIVE.get(taskId);
        if (lock != null && !phase.equals(lock.endpointWait)) {
            lock.endpointWait = phase;
            lock.snapshotTick = -1;
            if (phase.endsWith("_space") || phase.equals("waiting_capacity")) {
                lock.navigationTarget = null;
                ResidentWorkerRuntime.clearNavigation(lock.entity().getUUID());
                stopMotion(lock.entity());
            }
        }
    }

    public static BlockPos endpointApproach(ServerLevel level, PokemonEntity entity, BlockPos hub) {
        RuntimeLock lock = BY_ENTITY.get(entity);
        if (lock == null) return HubCourierAccess.approach(level, entity, hub);
        long now = level.getGameTime();
        if (!hub.equals(lock.approachHub) || now - lock.approachTick >= 10L) {
            lock.approachHub = hub.immutable();
            lock.approachTick = now;
            lock.approach = HubCourierAccess.approach(level, entity, hub);
        }
        return lock.approach;
    }

    private static JourneyProgress progress(RuntimeLock lock) {
        ServerLevel level = levelOf(lock);
        long now = level.getGameTime();
        if (lock.snapshotTick == now) return lock.snapshot;
        boolean gap = lock.remoteGap != null;
        Vec3 position = gap ? lock.remoteGap.position : lock.entity().position();
        BlockPos target = lock.routeDestination == null ? lock.worker.anchor() : lock.routeDestination;
        Vec3 destination = ResidentWorkerRuntime.interactionPosition(target);
        double distance = position.distanceTo(destination);
        double total = Math.max(1.0D, lock.routeOrigin.distanceTo(destination));
        int percent = Math.clamp((int) Math.floor(100.0D * (1.0D - distance / total)), 0, 99);
        boolean waiting = gap && distance < 2.0D && !RouteChunks.canTravel(level, target);
        boolean stalled = !gap && now - lock.lastMotionAt > 100L && distance > 3;
        String phase = !lock.endpointWait.isEmpty() ? lock.endpointWait
                : waiting ? "waiting_endpoint" : stalled ? "stalled"
                : lock.returning ? gap ? "return_gap" : "returning"
                : !lock.carrying ? "pickup" : gap ? "gap" : "moving";
        double speed = gap ? 0.5D * lock.navigationSpeed : lock.observedSpeed > 0.02D ? lock.observedSpeed
                : lock.ghost ? 0.325D : lock.entity().canFly() ? 0.5D : 0.2D;
        int eta = waiting || stalled || !lock.endpointWait.isEmpty() ? -1
                : Math.max(1, (int) Math.ceil(distance / speed / 20.0D));
        lock.snapshotTick = now;
        lock.snapshot = new JourneyProgress(phase, percent, eta,
                (int) Math.max(0L, (now - lock.journeyStartedAt) / 20L),
                (int) Math.ceil(distance), BlockPos.containing(position));
        return lock.snapshot;
    }

    public static TransportCapability capability(UUID taskId) {
        RuntimeLock lock = ACTIVE.get(taskId);
        return lock == null ? null : lock.capability();
    }

    public static void enableRemoteMotion(UUID taskId) {
        RuntimeLock lock = ACTIVE.get(taskId);
        if (lock == null || lock.motionActive) return;
        lock.motionActive = true;
        // Only a form that actually advertises flight may enter the remote
        // flight presentation. Other forms keep Cobblemon's native physics;
        // they still use the durable endpoint handoff when the route is
        // virtual (for example across dimensions).
        if (lock.entity().canFly()) {
            lock.entity().setFlying(true);
            lock.entity().setNoGravity(true);
        }
    }

    /** The same physical legs and unloaded gaps are used on delivery and return. */
    public static boolean driveRoute(ServerLevel level, WorkerLease worker, PokemonEntity entity,
                                     BlockPos destination, double speed) {
        RuntimeLock lock = BY_ENTITY.get(entity);
        if (lock == null) return false;
        lock.snapshotTick = -1;
        boolean remote = entity.canFly() || lock.ghost;
        lock.routeDestination = destination.immutable();
        lock.navigationSpeed = speed;
        // Expand the native pasture bounds BEFORE re-entering at the other end.
        // Otherwise Cobblemon recalls the entity on its very next tick.
        ResidentWorkerRuntime.prepareRoute(level, worker, entity, destination);
        if (remote) enableRemoteMotion(lock.taskId);
        if (ResidentWorkerRuntime.resting(entity) && lock.remoteGap == null) {
            stopMotion(entity);
            lock.endpointWait = "resting";
            return false;
        }
        if (lock.remoteGap != null) {
            tickRemoteGap(lock.taskId, level, destination, level.getGameTime());
            return false;
        }
        if (RouteChunks.canTravel(level, destination)
                && entity.position().distanceToSqr(ResidentWorkerRuntime.physicalInteractionPosition(
                level, entity, destination)) <= 2.25D
                && HubCourierAccess.canExchange(level, entity, destination)) return true;
        if (!remote) {
            setNavigationTarget(lock.taskId, destination, speed);
            ResidentWorkerRuntime.navigatePhysicalTo(level, entity, destination, speed);
            return false;
        }
        RouteChunks.VisibleSegment segment = lock.remoteWaypoint;
        boolean refresh = segment == null || horizontalDistanceSqr(entity.position(), segment.waypoint()) <= 9.0D
                || !RouteChunks.canTravel(level, segment.waypoint());
        if (refresh || level.getGameTime() % 40 == 0) {
            segment = RouteChunks.visibleSegment(level, entity.blockPosition(), destination);
            lock.remoteWaypoint = segment;
        }
        if (segment.gapAhead() && horizontalDistanceSqr(entity.position(), segment.waypoint()) <= 9.0D) {
            beginRemoteGap(lock.taskId, segment.waypoint(), level.getGameTime());
            return false;
        }
        setNavigationTarget(lock.taskId, segment.waypoint(), speed);
        ResidentWorkerRuntime.navigatePhysicalTo(level, entity, segment.waypoint(), speed);
        return false;
    }

    private static double horizontalDistanceSqr(Vec3 position, BlockPos waypoint) {
        double dx = position.x - (waypoint.getX() + 0.5D);
        double dz = position.z - (waypoint.getZ() + 0.5D);
        return dx * dx + dz * dz;
    }

    private static void stopMotion(PokemonEntity entity) {
        stopMotion(entity, true);
    }

    private static void stopMotion(PokemonEntity entity, boolean stopNavigation) {
        if (stopNavigation && entity.level() instanceof ServerLevel level
                && RouteChunks.canTravel(level, entity.blockPosition()) && !entity.getNavigation().isDone()) {
            entity.getNavigation().stop();
        }
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.getMoveControl().setWantedPosition(entity.getX(), entity.getY(), entity.getZ(), 0.0D);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.setXxa(0);
        entity.setYya(0);
        entity.setZza(0);
    }

    public static boolean isRemoteGapActive(UUID taskId) {
        RuntimeLock lock = ACTIVE.get(taskId);
        return lock != null && lock.remoteGap != null;
    }

    public static boolean isWorkerInRemoteGap(UUID pokemonId) {
        RuntimeLock lock = BY_WORKER.get(pokemonId);
        return lock != null && lock.remoteGap != null;
    }

    public static void beginRemoteGap(UUID taskId, BlockPos origin, long now) {
        RuntimeLock lock = ACTIVE.get(taskId);
        if (lock == null || lock.remoteGap != null) return;
        enterRemoteGap(lock, origin, now);
        parkGap(levelOf(lock), lock);
    }

    /** Records the journey and clears movement without accessing terrain. */
    private static void enterRemoteGap(RuntimeLock lock, BlockPos origin, long now) {
        lock.remoteGap = new RemoteGap(origin, now);
        lock.snapshotTick = -1;
        lock.remoteGap.leftLoaded = !RouteChunks.canTravel(levelOf(lock), origin);
        lock.remoteWaypoint = null;
        lock.navigationTarget = null;
        ResidentWorkerRuntime.clearNavigation(lock.entity().getUUID());
        stopMotion(lock.entity(), false);
        lock.entity().setInvisible(true);
        lock.entity().noPhysics = true;
        saveGap(lock);
        CobblemonCreateLogistics.LOGGER.debug("Courier {} entering unloaded {} leg at {}",
                lock.pokemonUuid, lock.returning ? "return" : "delivery", origin);
    }

    /** Search a bounded number of positions per tick; only move the live entity into a loaded chunk. */
    public static void tickRemoteGap(UUID taskId, ServerLevel level, BlockPos target, long now) {
        RuntimeLock lock = ACTIVE.get(taskId);
        if (lock == null || lock.remoteGap == null) return;
        stopMotion(lock.entity());
        RemoteGap gap = lock.remoteGap;
        if (ResidentWorkerRuntime.resting(lock.entity())) {
            gap.lastAdvancedAt = now;
            lock.endpointWait = "resting";
            saveGap(lock);
            return;
        }
        lock.endpointWait = "";
        lock.snapshotTick = -1;
        parkGap(level, lock);
        // Logical position advances even with no entity ticking along the route.
        // Small samples detect loaded islands; there is no full-route scan or ticket.
        double budget = Math.max(0L, now - gap.lastAdvancedAt) * 0.5D * lock.navigationSpeed;
        Vec3 destination = Vec3.atCenterOf(target);
        BlockPos entry = null;
        for (int i = 0; i < 128; i++) {
            Vec3 delta = destination.subtract(gap.position);
            if (delta.lengthSqr() < 0.01D) {
                if (RouteChunks.canTravel(level, target)) entry = target;
                gap.lastAdvancedAt = now;
                break;
            }
            if (budget < 0.01D) break;
            double step = Math.min(4.0D, Math.min(budget, delta.length()));
            gap.position = gap.position.add(delta.normalize().scale(step));
            budget -= step;
            gap.lastAdvancedAt += Math.max(1L, (long) Math.ceil(step * 2.0D / lock.navigationSpeed));
            BlockPos sample = BlockPos.containing(gap.position);
            boolean loaded = RouteChunks.canTravel(level, sample);
            if (!gap.leftLoaded) {
                if (!loaded) gap.leftLoaded = true;
                continue;
            }
            if (loaded) {
                entry = sample;
                break;
            }
        }
        saveGap(lock);
        if (entry == null) return;
        int y = lock.ghost ? entry.getY() + 1
                : Math.max(entry.getY() + 2,
                level.getHeight(Heightmap.Types.MOTION_BLOCKING, entry.getX(), entry.getZ()) + 2);
        lock.entity().teleportTo(entry.getX() + 0.5D, y, entry.getZ() + 0.5D);
        lock.entity().setDeltaMovement(Vec3.ZERO);
        lock.samplePosition = lock.entity().position();
        lock.sampleAt = lock.lastMotionAt = level.getGameTime();
        lock.entity().setInvisible(lock.previousInvisible);
        lock.entity().noPhysics = lock.ghost || lock.previousNoPhysics;
        lock.remoteGap = null;
        lock.remoteWaypoint = null;
        lock.entity().getPersistentData().remove(GAP_DATA);
        CobblemonCreateLogistics.LOGGER.debug("Courier {} re-entered loaded {} leg at {}",
                lock.pokemonUuid, lock.returning ? "return" : "delivery", entry);
    }

    private static ServerLevel levelOf(RuntimeLock lock) {
        return (ServerLevel) lock.entity().level();
    }

    /** The hidden entity stays in an existing ticking endpoint, never in the gap. */
    private static void parkGap(ServerLevel level, RuntimeLock lock) {
        BlockPos endpoint = parkingEndpoint(level, lock, null);
        if (endpoint == null) return;
        Vec3 parking = ResidentWorkerRuntime.interactionPosition(endpoint);
        if (lock.entity().position().distanceToSqr(parking) > 0.01D) {
            lock.entity().teleportTo(parking.x, parking.y, parking.z);
        }
        stopMotion(lock.entity());
    }

    /** Prefer home, then an existing loaded far endpoint; never add a ticket. */
    private static BlockPos parkingEndpoint(ServerLevel level, RuntimeLock lock,
                                           net.minecraft.world.level.ChunkPos unloading) {
        BlockPos[] candidates = {lock.worker.anchor(), lock.routeDestination, lock.parkingEndpoint};
        for (BlockPos candidate : candidates) {
            if (candidate != null && (unloading == null
                    || !new net.minecraft.world.level.ChunkPos(candidate).equals(unloading))
                    && RouteChunks.canTravel(level, candidate)) {
                lock.parkingEndpoint = candidate;
                return candidate;
            }
        }
        return null;
    }

    private static void saveGap(RuntimeLock lock) {
        if (lock.remoteGap == null) return;
        CompoundTag tag = new CompoundTag();
        tag.put("Origin", NbtUtils.writeBlockPos(lock.remoteGap.origin));
        tag.putLong("Started", lock.remoteGap.startedAt);
        tag.putDouble("X", lock.remoteGap.position.x);
        tag.putDouble("Y", lock.remoteGap.position.y);
        tag.putDouble("Z", lock.remoteGap.position.z);
        tag.putLong("Advanced", lock.remoteGap.lastAdvancedAt);
        tag.putBoolean("LeftLoaded", lock.remoteGap.leftLoaded);
        tag.putDouble("StartX", lock.routeOrigin.x);
        tag.putDouble("StartY", lock.routeOrigin.y);
        tag.putDouble("StartZ", lock.routeOrigin.z);
        tag.putLong("JourneyStarted", lock.journeyStartedAt);
        if (lock.routeDestination != null) tag.put("Destination", NbtUtils.writeBlockPos(lock.routeDestination));
        if (lock.parkingEndpoint != null) tag.put("Parking", NbtUtils.writeBlockPos(lock.parkingEndpoint));
        tag.putBoolean("PreviousInvisible", lock.previousInvisible);
        tag.putBoolean("PreviousNoPhysics", lock.previousNoPhysics);
        tag.putBoolean("PreviousNoGravity", lock.previousNoGravity);
        tag.putBoolean("PreviousFlying", lock.previousFlying);
        lock.entity().getPersistentData().put(GAP_DATA, tag);
    }

    /** Runs before chunk/entity unloading, independently of an entity's tick. */
    public static void protectRemoteJourneys(ServerLevel level) {
        for (RuntimeLock lock : java.util.List.copyOf(ACTIVE.values())) {
            PokemonEntity entity = lock.entity();
            if (entity.level() != level || entity.isRemoved()
                    || !entity.canFly() && !lock.ghost || lock.routeDestination == null) continue;
            if (lock.remoteGap == null && !RouteChunks.canTravel(level, entity.blockPosition())) {
                beginRemoteGap(lock.taskId, entity.blockPosition(), level.getGameTime());
            }
            if (lock.remoteGap != null) parkGap(level, lock);
        }
    }

    /** Chunk demotion can occur inside the level tick after its pre hook. */
    public static void beforeChunkUnload(ServerLevel level, net.minecraft.world.level.ChunkPos chunk) {
        for (RuntimeLock lock : java.util.List.copyOf(ACTIVE.values())) {
            PokemonEntity entity = lock.entity();
            if (entity.level() != level || entity.isRemoved() || !entity.chunkPosition().equals(chunk)
                    || !entity.canFly() && !lock.ghost || lock.routeDestination == null) continue;
            // These callbacks can run inside DistanceManager updates, before
            // ticking/loaded queries reflect the demotion. Cobblemon's stop()
            // reads terrain and would recursively add a ticket for the old
            // chunk. Defer it to protectRemoteJourneys on the next level tick.
            if (lock.remoteGap == null) enterRemoteGap(lock, entity.blockPosition(), level.getGameTime());
            // hasChunkAt may still report the unloading chunk in this callback.
            BlockPos endpoint = parkingEndpoint(level, lock, chunk);
            if (endpoint != null) {
                Vec3 parking = ResidentWorkerRuntime.interactionPosition(endpoint);
                entity.teleportTo(parking.x, parking.y, parking.z);
                stopMotion(entity, false);
            }
        }
    }

    private static void enableGhostMotion(RuntimeLock lock) {
        lock.entity().noPhysics = true;
        lock.entity().setNoGravity(true);
    }

    public static boolean isGhost(UUID taskId) {
        RuntimeLock lock = ACTIVE.get(taskId);
        return lock != null && lock.ghost;
    }

    public static boolean isGhost(PokemonEntity entity) {
        RuntimeLock lock = BY_ENTITY.get(entity);
        return lock != null && lock.ghost;
    }

    public static void arrivalEffect(UUID taskId, ServerLevel level, Vec3 position) {
        RuntimeLock lock = ACTIVE.get(taskId);
        if (lock != null && lock.capability().usesVirtualTransit()) {
            sendEffect(level, position, lock.capability(), 18);
        }
    }

    public static PokemonEntity entity(UUID taskId) {
        RuntimeLock lock = ACTIVE.get(taskId);
        return lock == null ? null : lock.entity();
    }

    public static void refreshShownPackage(Entity candidate) {
        if (!(candidate instanceof PokemonEntity pokemon)) return;
        RuntimeLock lock = BY_ENTITY.get(pokemon);
        if (lock != null && lock.remoteGap == null) {
            long now = levelOf(lock).getGameTime();
            Vec3 travel = pokemon.position().subtract(lock.lastFacingPosition);
            lock.lastFacingPosition = pokemon.position();
            double horizontal = Math.hypot(travel.x, travel.z);
            if (horizontal > 0.035D && horizontal < 1.5D) {
                float yaw = (float) (Math.toDegrees(Math.atan2(travel.z, travel.x)) - 90.0D);
                pokemon.setYRot(yaw);
                pokemon.setYHeadRot(yaw);
                pokemon.yBodyRot = yaw;
            }
            double moved = pokemon.position().distanceTo(lock.samplePosition);
            if (moved > 0.1D) lock.lastMotionAt = now;
            long elapsed = now - lock.sampleAt;
            if (elapsed >= 20L) {
                double measured = moved / elapsed;
                if (measured > 0.02D && measured < 2.0D) {
                    lock.observedSpeed = lock.observedSpeed == 0 ? measured
                            : lock.observedSpeed * 0.7D + measured * 0.3D;
                }
                lock.samplePosition = pokemon.position();
                lock.sampleAt = now;
            }
        }
        if (lock != null && lock.remoteWaypoint != null && lock.remoteWaypoint.gapAhead()
                && horizontalDistanceSqr(pokemon.position(), lock.remoteWaypoint.waypoint()) <= 9.0D
                && pokemon.level() instanceof ServerLevel level) {
            beginRemoteGap(lock.taskId, lock.remoteWaypoint.waypoint(), level.getGameTime());
        }
        if (lock != null && lock.remoteGap != null) {
            stopMotion(pokemon);
            return;
        }
        if (lock != null && lock.ghost) {
            enableGhostMotion(lock);
            stopMotion(pokemon);
        }
        if (lock != null && lock.navigationTarget != null && pokemon.isAlive()
                && pokemon.level() instanceof ServerLevel level) {
            if (!ResidentWorkerRuntime.resting(pokemon)) {
                pokemon.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                ResidentWorkerRuntime.navigatePhysicalTo(level, pokemon, lock.navigationTarget,
                        lock.navigationSpeed);
            }
        }
        if (lock != null && lock.carrying) {
            pokemon.setShownItem(lock.packageStack());
            if (lock.capability().usesVirtualTransit() && pokemon.tickCount % 10 == 0
                    && pokemon.level() instanceof ServerLevel level) {
                sendEffect(level, pokemon.position().add(0, pokemon.getBbHeight() * 0.7D, 0),
                        lock.capability(), 2);
            }
        }
    }

    private static void sendEffect(ServerLevel level, Vec3 position,
                                   TransportCapability capability, int count) {
        ParticleOptions particle = switch (capability) {
            case FLYING -> ParticleTypes.CLOUD;
            case SWIMMING -> ParticleTypes.BUBBLE;
            case GROUND -> ParticleTypes.CLOUD;
        };
        level.sendParticles(particle, position.x(), position.y(), position.z(),
                count, 0.25D, 0.2D, 0.25D, 0.015D);
    }

    public static void release(UUID taskId) {
        RuntimeLock lock = ACTIVE.get(taskId);
        if (lock != null && lock.returning) {
            return;
        }
        detach(taskId);
    }

    private static void detach(UUID taskId) {
        RuntimeLock lock = ACTIVE.remove(taskId);
        if (lock == null) return;
        BY_ENTITY.remove(lock.entity(), lock);
        BY_WORKER.remove(lock.pokemonUuid(), lock);
        ResidentWorkerRuntime.clearNavigation(lock.entity().getUUID());
        lock.entity().getBusyLocks().remove(lock.token());
        lock.entity().setShownItem(lock.previousShownItem());
        lock.entity().setInvisible(lock.previousInvisible);
        lock.entity().getPersistentData().remove(GAP_DATA);
        restoreMotion(lock);
    }

    private static void restoreMotion(RuntimeLock lock) {
        if ((!lock.motionActive && !lock.ghost && lock.remoteGap == null) || !lock.entity().isAlive()) return;
        lock.entity().noPhysics = lock.previousNoPhysics;
        lock.entity().setNoGravity(lock.previousNoGravity);
        lock.entity().setFlying(lock.previousFlying);
        lock.entity().setDeltaMovement(Vec3.ZERO);
    }

    /** Clears transient locks when an integrated or dedicated server stops. */
    public static void clear() {
        for (RuntimeLock lock : ACTIVE.values()) {
            lock.entity().getBusyLocks().remove(lock.token());
            lock.entity().setShownItem(lock.previousShownItem());
            lock.entity().setInvisible(lock.previousInvisible);
            // Keep route metadata across a server stop; Cobblemon saves the same entity.
            restoreMotion(lock);
        }
        ACTIVE.clear();
        BY_ENTITY.clear();
        BY_WORKER.clear();
    }

    private static final class RuntimeLock {
        private final UUID taskId;
        private final UUID pokemonUuid;
        private final WorkerLease worker;
        private final PokemonEntity entity;
        private final Object token;
        private final ItemStack packageStack;
        private final ItemStack previousShownItem;
        private final TransportCapability capability;
        private final boolean ghost;
        private final boolean previousFlying;
        private final boolean previousNoGravity;
        private final boolean previousNoPhysics;
        private final boolean previousInvisible;
        private boolean carrying;
        private boolean returning;
        private boolean motionActive;
        private BlockPos navigationTarget;
        private double navigationSpeed = 1.0D;
        private RouteChunks.VisibleSegment remoteWaypoint;
        private RemoteGap remoteGap;
        private BlockPos routeDestination;
        private Vec3 routeOrigin;
        private Vec3 samplePosition;
        private Vec3 lastFacingPosition;
        private long sampleAt;
        private long lastMotionAt;
        private long journeyStartedAt;
        private double observedSpeed;
        private BlockPos parkingEndpoint;
        private String endpointWait = "";
        private long snapshotTick = -1;
        private JourneyProgress snapshot = JourneyProgress.NONE;
        private BlockPos approachHub;
        private BlockPos approach;
        private long approachTick;

        private RuntimeLock(UUID taskId, WorkerLease worker, PokemonEntity entity, Object token,
                            ItemStack packageStack, ItemStack previousShownItem,
                            TransportCapability capability, boolean ghost, boolean previousFlying,
                            boolean previousNoGravity, boolean previousNoPhysics,
                            boolean previousInvisible) {
            this.taskId = taskId;
            this.pokemonUuid = worker.pokemonUuid();
            this.worker = worker;
            this.entity = entity;
            this.token = token;
            this.packageStack = packageStack;
            this.previousShownItem = previousShownItem;
            this.capability = capability;
            this.ghost = ghost;
            CompoundTag savedGap = entity.getPersistentData().getCompound(GAP_DATA);
            this.previousFlying = savedGap.contains("PreviousFlying") ? savedGap.getBoolean("PreviousFlying") : previousFlying;
            this.previousNoGravity = savedGap.contains("PreviousNoGravity") ? savedGap.getBoolean("PreviousNoGravity") : previousNoGravity;
            this.previousNoPhysics = savedGap.contains("PreviousNoPhysics") ? savedGap.getBoolean("PreviousNoPhysics") : previousNoPhysics;
            this.routeOrigin = this.samplePosition = this.lastFacingPosition = entity.position();
            this.journeyStartedAt = this.sampleAt = this.lastMotionAt = ((ServerLevel) entity.level()).getGameTime();
            this.previousInvisible = savedGap.isEmpty() ? previousInvisible
                    : savedGap.getBoolean("PreviousInvisible");
            NbtUtils.readBlockPos(savedGap, "Origin").ifPresent(origin -> {
                this.remoteGap = new RemoteGap(origin, savedGap.getLong("Started"));
                if (savedGap.contains("Advanced")) {
                    this.remoteGap.position = new Vec3(savedGap.getDouble("X"), savedGap.getDouble("Y"), savedGap.getDouble("Z"));
                    this.remoteGap.lastAdvancedAt = savedGap.getLong("Advanced");
                    this.remoteGap.leftLoaded = savedGap.getBoolean("LeftLoaded");
                }
                if (savedGap.contains("JourneyStarted")) {
                    this.routeOrigin = new Vec3(savedGap.getDouble("StartX"), savedGap.getDouble("StartY"), savedGap.getDouble("StartZ"));
                    this.journeyStartedAt = savedGap.getLong("JourneyStarted");
                    this.routeDestination = NbtUtils.readBlockPos(savedGap, "Destination").orElse(null);
                    this.parkingEndpoint = NbtUtils.readBlockPos(savedGap, "Parking").orElse(null);
                }
                entity.setInvisible(true);
                entity.noPhysics = true;
            });
        }

        UUID pokemonUuid() { return pokemonUuid; }
        PokemonEntity entity() { return entity; }
        Object token() { return token; }
        ItemStack packageStack() { return packageStack; }
        ItemStack previousShownItem() { return previousShownItem; }
        TransportCapability capability() { return capability; }
    }

    private static final class RemoteGap {
        private final BlockPos origin;
        private final long startedAt;
        private Vec3 position;
        private long lastAdvancedAt;
        private boolean leftLoaded;

        private RemoteGap(BlockPos origin, long startedAt) {
            this.origin = origin;
            this.startedAt = startedAt;
            this.position = Vec3.atCenterOf(origin);
            this.lastAdvancedAt = startedAt;
        }
    }
}
