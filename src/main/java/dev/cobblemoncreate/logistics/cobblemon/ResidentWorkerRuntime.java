package dev.cobblemoncreate.logistics.cobblemon;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.CobblemonMemories;
import com.cobblemon.mod.common.api.storage.pc.PCPosition;
import com.cobblemon.mod.common.block.entity.PokemonPastureBlockEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.cobblemoncreate.logistics.WorkerLease;
import dev.cobblemoncreate.logistics.CarrierProfiles;
import dev.cobblemoncreate.logistics.CarrierProfile;
import dev.cobblemoncreate.logistics.TransportCapability;
import dev.cobblemoncreate.logistics.CobblemonCreateLogistics;
import dev.cobblemoncreate.logistics.HubCourierAccess;
import dev.cobblemoncreate.logistics.hub.CobblemonHubBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime bridge to Cobblemon's pasture-style tethering. The Hub persists only
 * the Pokemon UUID and tether UUID; the live entity remains owned by Cobblemon.
 */
public final class ResidentWorkerRuntime {
    private static final int ROAM_RADIUS = 8;
    private static final long RETURN_STUCK_TICKS = 600;
    /*
     * Long direct paths are deliberately split into small goals.  This is the
     * same bounded/segmented idea used by Baritone's pathing pipeline: a path
     * calculation only has to solve the currently loaded section, then the
     * next section is planned from the entity's actual position.  Cobblemon's
     * 1.8 OmniPathNavigation remains the executor, so this does not replace or
     * fork its movement physics.
     */
    private static final double NAVIGATION_SEGMENT_LENGTH = 12.0D;
    private static final long NAVIGATION_REPLAN_TICKS = 10L;
    private static final long NAVIGATION_STALL_TICKS = 40L;
    private static final int ROUTE_DETOUR_MARGIN = 64;
    private static final Map<UUID, ReturnSession> RETURNS = new HashMap<>();
    private static final Map<UUID, NavigationPlan> NAVIGATION = new HashMap<>();

    private ResidentWorkerRuntime() {}

    public static boolean resting(PokemonEntity entity) {
        if (entity == null) return false;
        return memoryTrue(entity, CobblemonMemories.POKEMON_DROWSY)
                || memoryTrue(entity, CobblemonMemories.POKEMON_SLEEPING);
    }

    public static boolean sleeping(PokemonEntity entity) {
        return entity != null && memoryTrue(entity, CobblemonMemories.POKEMON_SLEEPING);
    }

    private static boolean memoryTrue(PokemonEntity entity,
                                      net.minecraft.world.entity.ai.memory.MemoryModuleType<Boolean> memory) {
        try {
            return entity.getBrain().getMemory(memory).orElse(false);
        } catch (IllegalStateException ignored) {
            // Older/partial Cobblemon Brain instances may not register optional memories.
            return false;
        }
    }

    public static boolean workerSleeping(ServerLevel level, WorkerLease worker) {
        try {
            Pokemon pokemon = Cobblemon.INSTANCE.getStorage()
                    .getPC(worker.storageUuid(), level.registryAccess()).get(worker.pokemonUuid());
            return pokemon != null && sleeping(pokemon.getEntity());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static WorkerLease bindFromPc(ServerPlayer player, BlockPos hubPos, UUID storageUuid,
                                         PCPosition position) {
        ServerLevel level = player.serverLevel();
        var storage = Cobblemon.INSTANCE.getStorage();
        var ownerPc = storage.getPC(player.getUUID(), level.registryAccess());
        var hubPc = storage.getPC(storageUuid, level.registryAccess());
        Pokemon pokemon = ownerPc.get(position);

        if (pokemon == null || pokemon.getTetheringId() != null
                || !player.getUUID().equals(pokemon.getOwnerUUID())
                || hubPc.getFirstAvailablePosition() == null) return null;
        CarrierProfile profile = CarrierProfiles.resolve(pokemon);
        if (profile == null || !CarrierProfiles.canAccessHub(level, hubPos, profile, pokemon)) return null;
        if (!ownerPc.remove(pokemon)) return null;
        if (!hubPc.add(pokemon)) {
            ownerPc.set(position, pokemon);
            return null;
        }

        UUID tetheringId = UUID.randomUUID();
        pokemon.setTetheringId(tetheringId);
        if (sendOut(level, hubPos, pokemon, player, tetheringId, hubPc.getUuid()) == null) {
            pokemon.setTetheringId(null);
            if (hubPc.remove(pokemon)) ownerPc.set(position, pokemon);
            return null;
        }
        return new WorkerLease(player.getUUID(), pokemon.getUuid(),
                pokemon.getSpecies().getResourceIdentifier(), level.dimension(), hubPos, tetheringId, storageUuid);
    }

    public static boolean ensure(ServerLevel level, WorkerLease worker) {
        if (!level.dimension().equals(worker.dimension())) return false;
        if (!level.hasChunkAt(worker.anchor())) return false;
        try {
            var pc = Cobblemon.INSTANCE.getStorage().getPC(worker.storageUuid(), level.registryAccess());
            Pokemon pokemon = pc.get(worker.pokemonUuid());
            if (pokemon == null || !worker.tetheringId().equals(pokemon.getTetheringId())) return false;
            PokemonEntity entity = pokemon.getEntity();
            if (entity != null && entity.isAlive() && entity.level() != level) return false;
            if (entity != null && level.getEntity(entity.getId()) != entity) {
                // Unloaded is not lost. The native entity remains in the chunk save.
                return false;
            }
            if (entity == null || !entity.isAlive()) {
                if (!worker.homeReported()) return false;
                RETURNS.remove(worker.pokemonUuid());
                entity = sendOut(level, worker.anchor(), pokemon, level.getServer().getPlayerList()
                        .getPlayer(worker.ownerUuid()), pokemon.getTetheringId(), pc.getUuid());
            }
            if (entity == null) return false;
            if (!worker.homeReported()) {
                prepareRoute(level, worker, entity, entity.blockPosition());
            } else if (entity.getTethering() == null
                    || !worker.tetheringId().equals(entity.getTethering().getTetheringId())) {
                entity.setTethering(tethering(worker, entity, pc.getUuid(),
                        playerName(level, worker.ownerUuid())));
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static void prepareRoute(ServerLevel level, WorkerLease worker, PokemonEntity entity,
                                    BlockPos targetPos) {
        var existing = entity.getTethering();
        if (existing != null && worker.tetheringId().equals(existing.getTetheringId())
                && existing.getBox().contains(entity.position())
                && existing.getBox().contains(Vec3.atCenterOf(targetPos.offset(16, 0, 16)))
                && existing.getBox().contains(Vec3.atCenterOf(targetPos.offset(-16, 0, -16)))) return;
        int x = entity.blockPosition().getX();
        int z = entity.blockPosition().getZ();
        BlockPos min = new BlockPos(Math.min(Math.min(worker.anchor().getX(), targetPos.getX()), x)
                - ROUTE_DETOUR_MARGIN, level.getMinBuildHeight(),
                Math.min(Math.min(worker.anchor().getZ(), targetPos.getZ()), z) - ROUTE_DETOUR_MARGIN);
        BlockPos max = new BlockPos(Math.max(Math.max(worker.anchor().getX(), targetPos.getX()), x)
                + ROUTE_DETOUR_MARGIN, level.getMaxBuildHeight() - 1,
                Math.max(Math.max(worker.anchor().getZ(), targetPos.getZ()), z) + ROUTE_DETOUR_MARGIN);
        entity.setTethering(new PokemonPastureBlockEntity.Tethering(min, max,
                worker.ownerUuid(), playerName(level, worker.ownerUuid()),
                worker.tetheringId(), worker.pokemonUuid(), worker.storageUuid(), entity.getId(), worker.anchor()));
    }

    /**
     * The Hub is an interaction surface, not a destination volume. Courier
     * actions use the block's top face so the entity never paths into its
     * collision box. A future interaction animation can use this same point.
     */
    public static Vec3 interactionPosition(BlockPos hubPos) {
        return Vec3.atBottomCenterOf(hubPos.above());
    }

    public static Vec3 physicalInteractionPosition(ServerLevel level, PokemonEntity entity,
                                                    BlockPos hubPos) {
        return Vec3.atBottomCenterOf(physicalTarget(level, entity, hubPos));
    }

    public static BlockPos physicalTarget(ServerLevel level, PokemonEntity entity, BlockPos hubPos) {
        if (entity != null && !entity.canFly() && !CarrierRuntimeLease.isGhost(entity)
                && (!entity.canWalk() || entity.getPokemon().getForm().getBehaviour()
                .getMoving().getWalk().getAvoidsLand())) {
            BlockPos entrance = CarrierProfiles.swimmingEntrance(level, hubPos,
                    entity.canSwimInWater(), entity.canSwimInLava());
            if (entrance != null) return entrance;
        }
        // Waypoints along a route are not Hub endpoints.
        if (entity != null && level.hasChunkAt(hubPos)
                && level.getBlockEntity(hubPos) instanceof CobblemonHubBlockEntity) {
            BlockPos approach = CarrierRuntimeLease.endpointApproach(level, entity, hubPos);
            if (approach != null) return approach;
        }
        return hubPos.above();
    }

    public static double interactionRadius(PokemonEntity entity) {
        return Math.max(2.5D, entity.getBbWidth() + 2.0D);
    }

    /** Moves the existing Cobblemon entity through loaded air using its own move controller. */
    public static void navigateAirTo(ServerLevel level, PokemonEntity entity, BlockPos hubPos,
                                     double speed) {
        if (entity == null || !entity.isAlive() || !level.hasChunkAt(entity.blockPosition())) return;
        if (entity.canFly()) {
            NavigationPlan plan = NAVIGATION.get(entity.getUUID());
            if (plan == null || !plan.target().equals(hubPos) || !plan.flight) {
                plan = new NavigationPlan(hubPos, level.getGameTime());
                plan.flight = true;
                NAVIGATION.put(entity.getUUID(), plan);
                entity.getNavigation().stop();
            }
            plan.speed = speed;
            entity.setFlying(true);
            driveFlight(level, entity, plan);
            return;
        }
        navigateTo(level, entity, physicalTarget(level, entity, hubPos), speed);
    }

    /** Selects Cobblemon's native navigation from the current form at runtime. */
    public static boolean navigatePhysicalTo(ServerLevel level, PokemonEntity entity,
                                             BlockPos hubPos, double speed) {
        if (entity == null || !entity.isAlive() || entity.level() != level) return false;
        if (CarrierRuntimeLease.isGhost(entity)) {
            return navigateGhostTo(level, entity, interactionPosition(hubPos), speed);
        }
        if (entity.canFly()) {
            navigateAirTo(level, entity, hubPos, speed);
            return true;
        }
        return navigateTo(level, entity, physicalTarget(level, entity, hubPos), speed);
    }

    /** Phase movement is used only while a Ghost courier has an active assignment. */
    public static boolean navigateGhostTo(ServerLevel level, PokemonEntity entity,
                                          Vec3 destination, double speed) {
        if (entity == null || !entity.isAlive() || entity.level() != level) return false;
        Vec3 delta = destination.subtract(entity.position());
        if (delta.lengthSqr() < 0.01D) return true;
        double step = Math.min(delta.length(), Math.min(0.45D, 0.25D * speed));
        Vec3 next = entity.position().add(delta.normalize().scale(step));
        if (!level.hasChunkAt(BlockPos.containing(next))) return false;
        entity.getNavigation().stop();
        entity.setPos(next.x(), next.y(), next.z());
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D);
        entity.setYRot(yaw);
        entity.setYHeadRot(yaw);
        entity.yBodyRot = yaw;
        entity.setDeltaMovement(Vec3.ZERO);
        return true;
    }

    /** Keeps a planned flight active between the Hub's slower task updates. */
    public static void tickFlightNavigation(ServerLevel level, PokemonEntity entity) {
        NavigationPlan plan = NAVIGATION.get(entity.getUUID());
        if (plan != null && plan.flight && entity.isAlive()) {
            if (resting(entity)) {
                entity.getMoveControl().setWantedPosition(entity.getX(), entity.getY(), entity.getZ(), 0.0D);
                return;
            }
            if (!entity.canFly()) {
                NAVIGATION.remove(entity.getUUID(), plan);
                entity.getMoveControl().setWantedPosition(entity.getX(), entity.getY(), entity.getZ(), 0.0D);
                return;
            }
            driveFlight(level, entity, plan);
        }
    }

    private static void driveFlight(ServerLevel level, PokemonEntity entity, NavigationPlan plan) {
        Vec3 target = physicalInteractionPosition(level, entity, plan.target);
        Vec3 origin = entity.position();
        double horizontal = Math.hypot(target.x() - origin.x(), target.z() - origin.z());
        double interactionRadius = interactionRadius(entity);
        double approachRadius = Math.max(interactionRadius, 4.5D);
        if (horizontal <= approachRadius || horizontal <= NAVIGATION_SEGMENT_LENGTH
                && canFlyDirectly(level, entity, origin, target)) {
            commandFlight(level, entity, plan, target);
            return;
        }
        double length = Math.min(NAVIGATION_SEGMENT_LENGTH, horizontal);
        double ux = horizontal < 0.01D ? 0 : (target.x() - origin.x()) / horizontal;
        double uz = horizontal < 0.01D ? 0 : (target.z() - origin.z()) / horizontal;
        int clearance = (int) Math.ceil(entity.getBbHeight()) + 1;
        long now = level.getGameTime();
        if (plan.lastFlightSample < 0 || now - plan.lastFlightSample >= 10
                || plan.flightSampleOrigin.distanceToSqr(origin) > 16.0D) {
            int cruise = (int) Math.ceil(Math.max(origin.y(), target.y() + 3));
            for (int i = 0; i <= (int) Math.ceil(length); i++) {
                BlockPos sample = BlockPos.containing(origin.x() + ux * i, origin.y(), origin.z() + uz * i);
                if (!level.hasChunkAt(sample)) {
                    entity.getMoveControl().setWantedPosition(origin.x(), origin.y(), origin.z(), 0);
                    return;
                }
                cruise = Math.max(cruise, level.getHeight(Heightmap.Types.MOTION_BLOCKING,
                        sample.getX(), sample.getZ()) + clearance);
            }
            plan.flightCruiseY = Math.min(cruise, level.getMaxBuildHeight() - clearance);
            plan.lastFlightSample = now;
            plan.flightSampleOrigin = origin;
        }
        int cruise = plan.flightCruiseY;
        entity.setFlying(true);
        if (origin.y() < cruise - 0.75D && horizontal > approachRadius) {
            commandFlight(level, entity, plan, new Vec3(origin.x(), cruise, origin.z()));
            return;
        }
        if (horizontal > approachRadius) {
            commandFlight(level, entity, plan,
                    new Vec3(origin.x() + ux * length, cruise, origin.z() + uz * length));
        } else {
            commandFlight(level, entity, plan, target);
        }
    }

    private static boolean canFlyDirectly(ServerLevel level, PokemonEntity entity,
                                          Vec3 origin, Vec3 target) {
        Vec3 delta = target.subtract(origin);
        int samples = Math.max(1, (int) Math.ceil(delta.length()));
        for (int i = 0; i <= samples; i++) {
            if (!level.hasChunkAt(BlockPos.containing(origin.add(delta.scale((double) i / samples))))) {
                return false;
            }
        }
        Vec3 offset = new Vec3(0, entity.getBbHeight() * 0.5D, 0);
        return level.clip(new ClipContext(origin.add(offset), target.add(offset),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)).getType() == HitResult.Type.MISS;
    }

    private static void commandFlight(ServerLevel level, PokemonEntity entity,
                                      NavigationPlan plan, Vec3 desired) {
        entity.getMoveControl().setWantedPosition(desired.x(), desired.y(), desired.z(), plan.speed);
        long now = level.getGameTime();
        if (plan.lastFlightPosition == null
                || plan.lastFlightPosition.distanceToSqr(entity.position()) > 0.01D) {
            plan.lastFlightPosition = entity.position();
            plan.lastFlightProgress = now;
            return;
        }
        if (now - plan.lastFlightProgress >= 12L && desired.distanceToSqr(entity.position()) > 0.25D) {
            Vec3 delta = desired.subtract(entity.position());
            entity.setDeltaMovement(delta.normalize().scale(Math.min(0.35D, delta.length())));
            plan.lastFlightProgress = now;
        }
    }

    /**
     * Drives one bounded route segment at a time.  A far-away Hub is never
     * handed to Minecraft as one giant path request: the next segment is
     * recalculated from the courier's live position, which lets navigation
     * recover after a tree, cliff edge, entity collision, or chunk boundary.
     */
    public static boolean navigateTo(ServerLevel level, PokemonEntity entity,
                                     BlockPos targetPos, double speed) {
        if (entity == null || !entity.isAlive() || entity.level() != level) return false;
        var navigation = entity.getNavigation();
        long now = level.getGameTime();
        NavigationPlan plan = NAVIGATION.get(entity.getUUID());
        if (plan == null || !plan.target().equals(targetPos) || plan.flight) {
            plan = new NavigationPlan(targetPos, now);
            NAVIGATION.put(entity.getUUID(), plan);
        }

        Path current = navigation.getPath();
        if (current != null && current.getNextNodeIndex() > plan.lastNodeIndex) {
            plan.lastNodeIndex = current.getNextNodeIndex();
            plan.lastProgress = now;
        }
        if (plan.waypoint != null) {
            double remaining = entity.position().distanceToSqr(plan.waypoint);
            if (remaining < plan.bestDistance - 0.25D) {
                plan.bestDistance = remaining;
                plan.lastProgress = now;
            }
        }
        boolean reachedSegment = plan.waypoint != null
                && entity.position().distanceToSqr(plan.waypoint) <= 4.0D;
        boolean stalled = now - plan.lastProgress >= NAVIGATION_STALL_TICKS;
        boolean refresh = plan.waypoint == null || reachedSegment || current == null
                || current.isDone() || stalled;
        if (refresh) {
            if (now - plan.lastPlan < NAVIGATION_REPLAN_TICKS && !reachedSegment && !stalled) {
                return current != null && !current.isDone();
            }
            if (stalled && plan.waypoint != null) plan.avoid(plan.waypoint, now);
            PathStep step = createSegmentPath(level, entity, navigation, targetPos, plan, now);
            if (step != null && navigation.moveTo(step.path(), speed)) {
                plan.waypoint = step.waypoint();
                plan.lastPlan = now;
                plan.lastProgress = now;
                plan.lastNodeIndex = -1;
                plan.bestDistance = entity.position().distanceToSqr(step.waypoint());
                plan.failures = 0;
                return true;
            }
            navigation.stop();
            entity.getMoveControl().setWantedPosition(entity.getX(), entity.getY(),
                    entity.getZ(), 0.0D);
            plan.lastPlan = now;
            plan.failures++;
            if (now - plan.lastFailureLog >= 100L) {
                plan.lastFailureLog = now;
                CobblemonCreateLogistics.LOGGER.warn(
                        "Courier {} could not path from {} toward {} (segment {}, failures {}); waiting for a new route",
                        entity.getUUID(), entity.blockPosition(), targetPos,
                        plan.waypoint == null ? "none" : plan.waypoint, plan.failures);
            }
            return false;
        }
        return true;
    }

    private static PathStep createSegmentPath(ServerLevel level, PokemonEntity entity,
                                               net.minecraft.world.entity.ai.navigation.PathNavigation navigation,
                                               BlockPos targetPos, NavigationPlan plan, long now) {
        BlockPos finalTarget = targetPos;
        Vec3 origin = entity.position();
        Vec3 target = Vec3.atBottomCenterOf(finalTarget);
        double distance = origin.distanceTo(target);
        List<BlockPos> candidates = new ArrayList<>();

        // Cobblemon can return a useful partial A* path even when the full
        // destination is beyond its search budget or around a cliff.
        if (distance <= 48.0D) candidates.add(finalTarget);

        double horizontal = Math.sqrt(Math.max(0.0D,
                target.x() - origin.x()) * (target.x() - origin.x())
                + Math.max(0.0D, target.z() - origin.z()) * (target.z() - origin.z()));
        double dx = target.x() - origin.x();
        double dz = target.z() - origin.z();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001D) {
            dx = 0.0D;
            dz = 1.0D;
            length = 1.0D;
        }
        dx /= length;
        dz /= length;
        double px = -dz;
        double pz = dx;
        double segment = Math.min(NAVIGATION_SEGMENT_LENGTH, Math.max(6.0D, horizontal));

        // Explore around the courier, including lateral and backward steps.
        // A cliff detour may need to move away from the destination first.
        double[][] directions = {{1, 0}, {1, 0.7}, {1, -0.7}, {0.4, 1},
                {0.4, -1}, {0, 1}, {0, -1}, {-0.6, 1}, {-0.6, -1}};
        for (double[] direction : directions) {
            double x = origin.x() + (dx * direction[0] + px * direction[1]) * segment;
            double z = origin.z() + (dz * direction[0] + pz * direction[1]) * segment;
            BlockPos candidate = BlockPos.containing(x, origin.y(), z);
            if (!level.hasChunkAt(candidate)) continue;
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    candidate.getX(), candidate.getZ());
            candidates.add(new BlockPos(candidate.getX(), surface, candidate.getZ()));
            candidates.add(candidate);
        }

        PathStep best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (BlockPos candidate : candidates) {
            if (!level.hasChunkAt(candidate)) continue;
            Path path = navigation.createPath(candidate, 0);
            if (path == null || path.isDone() || path.getEndNode() == null) continue;
            BlockPos endpoint = path.getEndNode().asBlockPos();
            Vec3 waypoint = Vec3.atBottomCenterOf(endpoint);
            if (origin.distanceToSqr(waypoint) < 9.0D) continue;
            if (candidate.equals(finalTarget) && path.canReach()
                    && plan.avoidance(endpoint, now) == 0.0D) return new PathStep(path, waypoint);
            double score = waypoint.distanceTo(target) + plan.avoidance(endpoint, now);
            if (score < bestScore) {
                bestScore = score;
                best = new PathStep(path, waypoint);
            }
        }
        return best;
    }

    public static void clearNavigation(UUID pokemonId) {
        NAVIGATION.remove(pokemonId);
    }

    public static void beginReturn(ServerLevel level, WorkerLease worker, PokemonEntity entity) {
        if (entity == null || !entity.isAlive() || entity.level() != level) return;
        ReturnSession existing = RETURNS.get(worker.pokemonUuid());
        if (existing == null || existing.entity != entity) {
            RETURNS.put(worker.pokemonUuid(),
                    new ReturnSession(worker, entity, entity.position(), level.getGameTime()));
            CobblemonCreateLogistics.LOGGER.info("Courier {} returning from {} to Hub {}",
                    worker.pokemonUuid(), entity.blockPosition(), worker.anchor());
        }
        CarrierRuntimeLease.driveRoute(level, worker, entity, worker.anchor(), 1.0D);
    }

    /** Restores a non-reported courier's return session after reload. */
    public static void recoverReturn(ServerLevel level, WorkerLease worker) {
        var pc = Cobblemon.INSTANCE.getStorage().getPC(worker.storageUuid(), level.registryAccess());
        Pokemon pokemon = pc.get(worker.pokemonUuid());
        PokemonEntity entity = pokemon == null ? null : pokemon.getEntity();
        if (entity == null || !entity.isAlive() || entity.level() != level) {
            RETURNS.remove(worker.pokemonUuid());
            return;
        }
        CarrierRuntimeLease.holdReturn(worker, entity);
        beginReturn(level, worker, entity);
    }

    /** Drives the return independently of either Hub's block entity tick. */
    public static void tickReturns(MinecraftServer server) {
        if (server.getTickCount() % 10 != 0) return;
        for (ReturnSession session : java.util.List.copyOf(RETURNS.values())) {
            WorkerLease worker = session.worker;
            ServerLevel level = server.getLevel(worker.dimension());
            PokemonEntity entity = session.entity;
            if (level == null || entity == null || !entity.isAlive() || entity.level() != level) {
                RETURNS.remove(worker.pokemonUuid(), session);
                CarrierRuntimeLease.finishReturn(worker.pokemonUuid());
                CobblemonCreateLogistics.LOGGER.warn("Courier {} return entity unavailable; waiting for its native entity to load",
                        worker.pokemonUuid());
                continue;
            }
            if (!CarrierRuntimeLease.isWorkerInRemoteGap(worker.pokemonUuid())
                    && isAtReportPoint(level, worker, entity)
                    && level.getBlockEntity(worker.anchor()) instanceof CobblemonHubBlockEntity hub
                    && hub.hasWorker(worker.pokemonUuid())) {
                if (!hub.interactWithReturningCourier(worker.pokemonUuid())) {
                    continue;
                }
                entity.getNavigation().stop();
                entity.setTethering(tethering(worker, entity, worker.storageUuid(),
                        playerName(level, worker.ownerUuid())));
                RETURNS.remove(worker.pokemonUuid(), session);
                CarrierRuntimeLease.finishReturn(worker.pokemonUuid());
                CobblemonCreateLogistics.LOGGER.info("Courier {} reported home to Hub {}",
                        worker.pokemonUuid(), worker.anchor());
                continue;
            }
            long now = level.getGameTime();
            if (session.lastPosition.distanceToSqr(entity.position()) > 0.25D) {
                session.lastPosition = entity.position();
                session.lastProgressTime = now;
            } else if (now - session.lastProgressTime >= RETURN_STUCK_TICKS) {
                entity.getNavigation().stop();
                session.lastProgressTime = now;
                CobblemonCreateLogistics.LOGGER.warn("Courier {} return path stalled at {}; retrying Hub {}",
                        worker.pokemonUuid(), entity.blockPosition(), worker.anchor());
            }
            CarrierRuntimeLease.driveRoute(level, worker, entity, worker.anchor(), 1.0D);
        }
    }

    /** A courier has reported back only after its live entity reaches its Hub. */
    public static boolean isHome(ServerLevel level, WorkerLease worker) {
        if (!worker.homeReported()) return false;
        if (!level.dimension().equals(worker.dimension()) || !level.hasChunkAt(worker.anchor())) return false;
        try {
            var pc = Cobblemon.INSTANCE.getStorage().getPC(worker.storageUuid(), level.registryAccess());
            Pokemon pokemon = pc.get(worker.pokemonUuid());
            PokemonEntity entity = pokemon == null ? null : pokemon.getEntity();
            return entity != null && entity.isAlive() && entity.level() == level
                    && entity.position().distanceToSqr(physicalInteractionPosition(level, entity, worker.anchor())) <= 2.25D
                    && HubCourierAccess.canExchange(level, entity, worker.anchor());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** True only at the Hub's report position, not merely in its roaming area. */
    private static boolean isAtReportPoint(ServerLevel level, WorkerLease worker, PokemonEntity entity) {
        if (!level.hasChunkAt(worker.anchor())
                || !(level.getBlockEntity(worker.anchor()) instanceof CobblemonHubBlockEntity)) return false;
        return entity.position().distanceToSqr(physicalInteractionPosition(level, entity, worker.anchor())) <= 2.25D
                && HubCourierAccess.canExchange(level, entity, worker.anchor());
    }

    public static boolean release(WorkerLease worker, ServerLevel level) {
        try {
            var storage = Cobblemon.INSTANCE.getStorage();
            var pc = storage.getPC(worker.storageUuid(), level.registryAccess());
            Pokemon pokemon = pc.get(worker.pokemonUuid());
            if (pokemon == null) return false;
            // Cobblemon can clear a pasture tether as part of block removal
            // before this cleanup callback runs. The Hub storage UUID and
            // Pokemon UUID are the durable ownership proof, so a null live
            // tether is still safe to return. A different non-null tether is
            // left untouched because another system may own the Pokemon.
            UUID actualTetheringId = pokemon.getTetheringId();
            if (actualTetheringId != null && !worker.tetheringId().equals(actualTetheringId)) return false;
            var ownerPc = storage.getPC(worker.ownerUuid(), level.registryAccess());
            if (ownerPc.getFirstAvailablePosition() == null) return false;
            pokemon.setTetheringId(null);
            PokemonEntity entity = pokemon.getEntity();
            if (entity != null) {
                entity.setTethering(null);
                pokemon.recall();
            }
            if (!pc.remove(pokemon)) {
                pokemon.setTetheringId(worker.tetheringId());
                return false;
            }
            if (!ownerPc.add(pokemon)) {
                pc.add(pokemon);
                pokemon.setTetheringId(worker.tetheringId());
                return false;
            }
            RETURNS.remove(worker.pokemonUuid());
            CarrierRuntimeLease.finishReturn(worker.pokemonUuid());
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Clears per-server movement baselines; no Pokemon or cargo is mutated. */
    public static void clearReturnTracking() {
        RETURNS.clear();
        NAVIGATION.clear();
    }

    private static final class ReturnSession {
        private final WorkerLease worker;
        private final PokemonEntity entity;
        private Vec3 lastPosition;
        private long lastProgressTime;

        private ReturnSession(WorkerLease worker, PokemonEntity entity, Vec3 position, long time) {
            this.worker = worker;
            this.entity = entity;
            this.lastPosition = position;
            this.lastProgressTime = time;
        }
    }

    private static final class NavigationPlan {
        private final BlockPos target;
        private final Map<BlockPos, Long> recentWaypoints = new HashMap<>();
        private Vec3 waypoint;
        private long lastPlan;
        private long lastProgress;
        private long lastFailureLog;
        private int failures;
        private boolean flight;
        private double speed = 1.0D;
        private int lastNodeIndex = -1;
        private double bestDistance = Double.POSITIVE_INFINITY;
        private long lastFlightSample = -1;
        private Vec3 flightSampleOrigin;
        private int flightCruiseY;
        private Vec3 lastFlightPosition;
        private long lastFlightProgress;

        private NavigationPlan(BlockPos target, long now) {
            this.target = target.immutable();
            this.lastPlan = now - NAVIGATION_REPLAN_TICKS;
            this.lastProgress = now;
        }

        private BlockPos target() { return target; }

        private void avoid(Vec3 waypoint, long now) {
            BlockPos pos = BlockPos.containing(waypoint);
            recentWaypoints.entrySet().removeIf(entry -> now - entry.getValue() > 600L);
            recentWaypoints.put(pos, now);
        }

        private double avoidance(BlockPos waypoint, long now) {
            Long visited = recentWaypoints.get(waypoint);
            return visited != null && now - visited < 600L ? 32.0D : 0.0D;
        }
    }

    private record PathStep(Path path, Vec3 waypoint) {}

    private static PokemonEntity sendOut(ServerLevel level, BlockPos anchor, Pokemon pokemon,
                                         ServerPlayer player, UUID tetherId, UUID pcId) {
        String playerName = player == null ? "" : player.getGameProfile().getName();
        CarrierProfile profile = CarrierProfiles.resolve(pokemon);
        Vec3 spawn = interactionPosition(anchor);
        if (profile != null && profile.needsFluidHub()) {
            BlockPos entrance = CarrierProfiles.swimmingEntrance(level, anchor,
                    profile.canSwimInWater(), profile.canSwimInLava());
            if (entrance == null) return null;
            spawn = Vec3.atBottomCenterOf(entrance);
        }
        return pokemon.sendOut(level, spawn, null, entity ->
        {
            entity.setTethering(tethering(new WorkerLease(
                        pokemon.getOwnerUUID(), pokemon.getUuid(),
                        pokemon.getSpecies().getResourceIdentifier(), level.dimension(), anchor, tetherId, pcId),
                        entity, pcId, playerName));
            return kotlin.Unit.INSTANCE;
        });
    }

    private static PokemonPastureBlockEntity.Tethering tethering(WorkerLease worker,
                                                                  PokemonEntity entity, UUID pcId,
                                                                  String playerName) {
        BlockPos min = worker.anchor().offset(-ROAM_RADIUS, -ROAM_RADIUS, -ROAM_RADIUS);
        BlockPos max = worker.anchor().offset(ROAM_RADIUS, ROAM_RADIUS, ROAM_RADIUS);
        return new PokemonPastureBlockEntity.Tethering(min, max, worker.ownerUuid(), playerName,
                worker.tetheringId(), worker.pokemonUuid(), pcId, entity.getId(), worker.anchor());
    }

    private static String playerName(ServerLevel level, UUID playerId) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        return player == null ? "" : player.getGameProfile().getName();
    }
}
