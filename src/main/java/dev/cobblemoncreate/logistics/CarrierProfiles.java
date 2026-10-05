package dev.cobblemoncreate.logistics;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.types.ElementalTypes;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;

/** Derives one transport profile for any live Cobblemon form. */
public final class CarrierProfiles {
    private CarrierProfiles() {}

    /** Cobblemon's current form owns movement data; elemental types do not. */
    public static CarrierProfile resolve(Pokemon pokemon) {
        if (pokemon == null || pokemon.getSpecies() == null) return null;
        var moving = pokemon.getForm().getBehaviour().getMoving();
        boolean walk = moving.getWalk().getCanWalk();
        boolean fly = moving.getFly().getCanFly();
        boolean water = moving.getSwim().getCanSwimInWater();
        boolean lava = moving.getSwim().getCanSwimInLava();
        boolean ghost = hasGhostTrait(pokemon);
        if (!walk && !fly && !water && !lava && !ghost) return null;
        double statMultiplier = Math.clamp(Math.sqrt(Math.max(1, pokemon.getSpeed()) / 100.0D), 0.75D, 1.5D);
        return new CarrierProfile(pokemon.getSpecies().getResourceIdentifier(), 1,
                (fly ? 1.35D : 1.0D) * statMultiplier, walk, fly, water, lava,
                moving.getWalk().getAvoidsLand(), ghost);
    }

    public static boolean hasGhostTrait(Pokemon pokemon) {
        if (pokemon == null) return false;
        for (var type : pokemon.getTypes()) {
            if (type == ElementalTypes.GHOST) return true;
        }
        return false;
    }

    /** Resolves from the authoritative Hub PC without copying Pokémon data. */
    public static CarrierProfile resolve(ServerLevel level, WorkerLease worker) {
        try {
            Pokemon pokemon = Cobblemon.INSTANCE.getStorage()
                    .getPC(worker.storageUuid(), level.registryAccess()).get(worker.pokemonUuid());
            return resolve(pokemon);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** A swimmer can exchange cargo at a Hub only from adjacent swimmable fluid. */
    public static boolean canAccessHub(ServerLevel level, BlockPos hubPos, CarrierProfile profile,
                                       Pokemon pokemon) {
        if (!profile.needsFluidHub()) return true;
        if (level == null || pokemon == null) return false;
        return swimmingEntrance(level, hubPos, profile.canSwimInWater(),
                profile.canSwimInLava()) != null;
    }

    public static BlockPos swimmingEntrance(ServerLevel level, BlockPos hubPos,
                                            boolean water, boolean lava) {
        if (!level.hasChunkAt(hubPos)) return null;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos entrance = hubPos.relative(side);
            if (!level.hasChunkAt(entrance)) continue;
            for (int y = 0; y <= 1; y++) {
                var fluid = level.getFluidState(entrance.above(y));
                if (water && fluid.is(FluidTags.WATER)
                        || lava && fluid.is(FluidTags.LAVA)) return entrance.above(y);
            }
        }
        return null;
    }

    public static boolean canAccessHub(ServerLevel level, BlockPos hubPos, CarrierProfile profile,
                                       WorkerLease worker) {
        try {
            Pokemon pokemon = Cobblemon.INSTANCE.getStorage()
                    .getPC(worker.storageUuid(), level.registryAccess()).get(worker.pokemonUuid());
            return canAccessHub(level, hubPos, profile, pokemon);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
