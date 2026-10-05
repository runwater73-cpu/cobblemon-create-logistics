package dev.cobblemoncreate.logistics;

/**
 * The preferred physical movement of a resident's current Cobblemon form.
 * Cross-dimension handoff is a Hub route, not a Pokemon elemental power.
 */
public enum TransportCapability {
    /** Physical, loaded-chunk, ground navigation. */
    GROUND,
    /** Physical flight in loaded chunks, endpoint handoff beyond them. */
    FLYING,
    /** Cobblemon water/lava navigation; requires fluid access at both Hubs. */
    SWIMMING;

    public boolean usesVirtualTransit() {
        return this == FLYING;
    }
}
