package dev.cobblemoncreate.logistics;

/** Shared route eligibility for all courier species and route states. */
public final class TransportRouting {
    private TransportRouting() {}

    public static boolean canCarry(CarrierProfile profile,
                                   boolean sameDimension, boolean corridorLoaded) {
        if (profile == null) return false;
        if (!sameDimension) return true;
        return profile.canUseRemoteHandoff() || corridorLoaded;
    }

    public static TransportMode mode(boolean sameDimension, boolean corridorLoaded) {
        return sameDimension && corridorLoaded ? TransportMode.LOCAL : TransportMode.VIRTUAL;
    }
}
