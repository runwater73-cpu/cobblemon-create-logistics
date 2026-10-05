package dev.cobblemoncreate.logistics;

import net.minecraft.resources.ResourceLocation;

/**
 * Data needed to schedule a carrier. It intentionally contains no Pokemon
 * snapshot: the live Cobblemon storage/entity remains the source of truth.
 */
public record CarrierProfile(
        ResourceLocation species,
        int capacity,
        double speedMultiplier,
        boolean canWalk,
        boolean canFly,
        boolean canSwimInWater,
        boolean canSwimInLava,
        boolean avoidsLand,
        boolean ghost
) {
    public CarrierProfile {
        if (capacity < 1) {
            throw new IllegalArgumentException("Carrier capacity must be positive");
        }
        if (!Double.isFinite(speedMultiplier) || speedMultiplier <= 0) {
            throw new IllegalArgumentException("Carrier speed must be finite and positive");
        }
        if (!canWalk && !canFly && !canSwimInWater && !canSwimInLava && !ghost) {
            throw new IllegalArgumentException("Carrier has no supported movement");
        }
    }

    public boolean needsFluidHub() {
        return !canFly && !ghost && (!canWalk || avoidsLand);
    }

    public boolean canUseRemoteHandoff() {
        return canFly || ghost;
    }

    public TransportCapability capability() {
        return canFly ? TransportCapability.FLYING
                : needsFluidHub() ? TransportCapability.SWIMMING : TransportCapability.GROUND;
    }

}
