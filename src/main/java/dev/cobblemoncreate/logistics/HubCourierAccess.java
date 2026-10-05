package dev.cobblemoncreate.logistics;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.cobblemoncreate.logistics.cobblemon.CarrierRuntimeLease;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Small endpoint checks only; never probes terrain along a remote journey. */
public final class HubCourierAccess {
    private HubCourierAccess() {}

    public static BlockPos approach(ServerLevel level, PokemonEntity entity, BlockPos hub) {
        if (CarrierRuntimeLease.isGhost(entity)) {
            BlockPos nearest = hub.above();
            double distance = entity.position().distanceToSqr(Vec3.atBottomCenterOf(nearest));
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos candidate = hub.relative(side);
                double candidateDistance = entity.position().distanceToSqr(Vec3.atBottomCenterOf(candidate));
                if (candidateDistance < distance) {
                    nearest = candidate;
                    distance = candidateDistance;
                }
            }
            BlockPos below = hub.below(Math.max(1, (int) Math.ceil(entity.getBbHeight() + 0.01D)));
            if (entity.position().distanceToSqr(Vec3.atBottomCenterOf(below)) < distance) nearest = below;
            return nearest;
        }
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        // A flying courier can use the top. Walking couriers prefer supported
        // side entrances instead of being required to climb onto the Hub.
        if (entity.canFly() && hasSpace(level, entity, hub.above())
                && clearLine(level, entity, Vec3.atBottomCenterOf(hub.above())
                .add(0, entity.getEyeHeight(), 0), hub, Direction.UP)) {
            best = hub.above();
            bestDistance = entity.position().distanceToSqr(Vec3.atBottomCenterOf(best));
        }
        int offset = Math.max(1, (int) Math.ceil(entity.getBbWidth() / 2.0D + 0.5D));
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = hub.relative(side, offset);
            if (!hasSpace(level, entity, candidate) || !clearLine(level, entity,
                    Vec3.atBottomCenterOf(candidate).add(0, entity.getEyeHeight(), 0), hub, side)) continue;
            if (!entity.canFly() && (!level.hasChunkAt(candidate.below())
                    || level.getBlockState(candidate.below()).getCollisionShape(level, candidate.below()).isEmpty())) continue;
            double distance = entity.position().distanceToSqr(Vec3.atBottomCenterOf(candidate));
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        int belowOffset = Math.max(1, (int) Math.ceil(entity.getBbHeight() + 0.01D));
        BlockPos below = hub.below(belowOffset);
        if (hasSpace(level, entity, below) && clearLine(level, entity,
                Vec3.atBottomCenterOf(below).add(0, entity.getEyeHeight(), 0), hub, Direction.DOWN)
                && (entity.canFly() || level.hasChunkAt(below.below())
                && !level.getBlockState(below.below()).getCollisionShape(level, below.below()).isEmpty())) {
            double distance = entity.position().distanceToSqr(Vec3.atBottomCenterOf(below));
            if (distance < bestDistance) best = below;
        }
        if (best == null && hasSpace(level, entity, hub.above())) best = hub.above();
        return best;
    }

    public static boolean hasSpace(ServerLevel level, PokemonEntity entity, BlockPos feet) {
        double half = entity.getBbWidth() / 2.0D;
        AABB box = new AABB(feet.getX() + 0.5D - half, feet.getY() + 0.01D,
                feet.getZ() + 0.5D - half, feet.getX() + 0.5D + half,
                feet.getY() + entity.getBbHeight(), feet.getZ() + 0.5D + half);
        for (int x = (int) Math.floor(box.minX) >> 4; x <= (int) Math.floor(box.maxX) >> 4; x++) {
            for (int z = (int) Math.floor(box.minZ) >> 4; z <= (int) Math.floor(box.maxZ) >> 4; z++) {
                if (!RouteChunks.canTravel(level, new BlockPos(x << 4, feet.getY(), z << 4))) return false;
            }
        }
        return !level.getBlockCollisions(entity, box).iterator().hasNext();
    }

    public static boolean canExchange(ServerLevel level, PokemonEntity entity, BlockPos hub) {
        if (CarrierRuntimeLease.isGhost(entity)) return true;
        BlockPos approach = CarrierRuntimeLease.endpointApproach(level, entity, hub);
        if (approach == null) return false;
        Direction face = Direction.getNearest(approach.getX() - hub.getX(),
                approach.getY() - hub.getY(), approach.getZ() - hub.getZ());
        return clearLine(level, entity, entity.getEyePosition(), hub, face);
    }

    private static boolean clearLine(ServerLevel level, PokemonEntity entity, Vec3 from,
                                     BlockPos hub, Direction face) {
        Vec3 surface = switch (face) {
            case UP -> Vec3.atBottomCenterOf(hub.above()).add(0, 0.01D, 0);
            case DOWN -> Vec3.atBottomCenterOf(hub).add(0, -0.01D, 0);
            default -> Vec3.atCenterOf(hub)
                    .add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.51D));
        };
        if (!RouteChunks.loaded(level, BlockPos.containing(from), BlockPos.containing(surface))) return false;
        BlockHitResult hit = level.clip(new ClipContext(from, surface, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, entity));
        // A small walking courier may look at the Hub's side rather than over
        // its top. The Hub itself is the interaction object, not an obstacle.
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(hub);
    }
}
