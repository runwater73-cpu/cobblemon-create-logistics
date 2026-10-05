package dev.cobblemoncreate.logistics;

/** Last server-side condition that interrupted a courier task. */
public enum PauseReason {
    UNKNOWN,
    ROUTE_UNAVAILABLE,
    CHUNKS_UNLOADED,
    CARRIER_UNAVAILABLE,
    NO_PROGRESS
}
