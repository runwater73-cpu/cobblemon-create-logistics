package dev.cobblemoncreate.logistics;

/** Authoritative lifecycle of one Create package delivery. */
public enum DeliveryState {
    BUFFERED,
    CLAIMED_BY_WORKER,
    IN_TRANSIT,
    ARRIVED_BUFFERED,
    DELIVERED,
    ROLLED_BACK,
    FAILED,
    PAUSED,
    CANCELLED;

    public boolean terminal() {
        return this == DELIVERED || this == ROLLED_BACK || this == FAILED || this == CANCELLED;
    }
}
