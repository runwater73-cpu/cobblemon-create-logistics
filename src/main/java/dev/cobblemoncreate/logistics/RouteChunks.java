package dev.cobblemoncreate.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Checks the loaded chunks on the direct route without creating chunk tickets. */
public final class RouteChunks {
    private static final int VISIBLE_SEGMENT_BLOCKS = 128;
    private static final int ROUTE_SAMPLE_BLOCKS = 4;
    private RouteChunks() {}

    public record VisibleSegment(BlockPos waypoint, boolean gapAhead, boolean destinationReached) {}

    /** One bounded loaded leg; a missing chunk is never requested or force-loaded. */
    public static VisibleSegment visibleSegment(ServerLevel level, BlockPos from, BlockPos to) {
        return visibleSegment(from, to, pos -> canTravel(level, pos));
    }

    /** A FULL border chunk can be present while all its entities are frozen. */
    public static boolean canTravel(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.isPositionEntityTicking(pos);
    }

    static VisibleSegment visibleSegment(BlockPos from, BlockPos to, Predicate<BlockPos> loaded) {
        double distance = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        if (distance < ROUTE_SAMPLE_BLOCKS) {
            return loaded.test(to) ? new VisibleSegment(to, false, true)
                    : new VisibleSegment(from, true, false);
        }
        int steps = Math.max(1, (int) Math.ceil(Math.min(distance, VISIBLE_SEGMENT_BLOCKS)
                / ROUTE_SAMPLE_BLOCKS));
        BlockPos last = from;
        for (int i = 1; i <= steps; i++) {
            double traveled = Math.min(distance, i * ROUTE_SAMPLE_BLOCKS);
            BlockPos sample = interpolate(from, to, traveled / distance);
            if (!loaded.test(sample)) return new VisibleSegment(last, true, false);
            last = sample;
        }
        boolean reached = steps * ROUTE_SAMPLE_BLOCKS >= distance;
        return new VisibleSegment(reached ? to : last, false, reached);
    }

    /** Incremental search for the next loaded leg beyond an unloaded gap. */
    public static BlockPos nextLoaded(BlockPos from, BlockPos to, int firstSample, int sampleCount,
                                      Predicate<BlockPos> loaded) {
        double distance = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        int total = Math.max(1, (int) Math.ceil(distance / ROUTE_SAMPLE_BLOCKS));
        for (int i = firstSample; i < Math.min(total + 1, firstSample + sampleCount); i++) {
            BlockPos point = interpolate(from, to,
                    Math.min(1.0D, i * ROUTE_SAMPLE_BLOCKS / Math.max(distance, 1.0D)));
            if (loaded.test(point)) return point;
        }
        return null;
    }

    public static int routeSamples(BlockPos from, BlockPos to) {
        return Math.max(1, (int) Math.ceil(Math.hypot(to.getX() - from.getX(),
                to.getZ() - from.getZ()) / ROUTE_SAMPLE_BLOCKS));
    }

    private static BlockPos interpolate(BlockPos from, BlockPos to, double fraction) {
        return new BlockPos((int) Math.round(from.getX() + (to.getX() - from.getX()) * fraction),
                (int) Math.round(from.getY() + (to.getY() - from.getY()) * fraction),
                (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * fraction));
    }

    public static boolean loaded(ServerLevel level, BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz)) / 8 + 1;
        Set<Long> visited = new HashSet<>();
        for (int i = 0; i <= steps; i++) {
            int x = from.getX() + (int) Math.round((double) dx * i / steps);
            int z = from.getZ() + (int) Math.round((double) dz * i / steps);
            int cx = x >> 4;
            int cz = z >> 4;
            if (visited.add(ChunkPos.asLong(cx, cz))
                    && !canTravel(level, new BlockPos(cx << 4, from.getY(), cz << 4))) {
                return false;
            }
        }
        return true;
    }
}
