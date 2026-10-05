package dev.cobblemoncreate.logistics.hub;

import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Transient display data only. Neither this cache nor its packets own inventory. */
public final class HubVisualState {
    public static final int SNAPSHOT_TTL_TICKS = 100;
    public static final int EVENT_TTL_TICKS = 24;
    private static final int MAX_EVENTS = 8;
    public static final Snapshot EMPTY = new Snapshot(new UUID(0, 0), -1, List.of(), List.of());

    public record WorkerSignal(UUID pokemonUuid, String phase, boolean busy, boolean paused, boolean returning) {}

    public record VisiblePackage(UUID taskId, ItemStack stack) {
        public VisiblePackage {
            stack = stack.copy();
            if (stack.isEmpty()) throw new IllegalArgumentException("Empty visual package");
        }
        @Override public ItemStack stack() { return stack.copy(); }
    }

    public record Snapshot(UUID instanceId, long serverTick, List<WorkerSignal> workers,
                           List<VisiblePackage> packages) {
        public Snapshot {
            workers = List.copyOf(workers);
            packages = List.copyOf(packages);
            if (workers.size() > CobblemonHubBlockEntity.MAX_WORKERS) throw new IllegalArgumentException("Too many Hub signals");
            if (packages.size() > CobblemonHubBlockEntity.MAX_PACKAGES) throw new IllegalArgumentException("Too many visual packages");
        }

        public Snapshot(UUID instanceId, long serverTick, List<WorkerSignal> workers) {
            this(instanceId, serverTick, workers, List.of());
        }

        public void write(RegistryFriendlyByteBuf buf) {
            buf.writeUUID(instanceId);
            buf.writeVarLong(serverTick);
            buf.writeVarInt(workers.size());
            for (WorkerSignal worker : workers) {
                buf.writeUUID(worker.pokemonUuid());
                buf.writeUtf(worker.phase(), 32);
                buf.writeBoolean(worker.busy());
                buf.writeBoolean(worker.paused());
                buf.writeBoolean(worker.returning());
            }
            buf.writeVarInt(packages.size());
            for (VisiblePackage entry : packages) {
                buf.writeUUID(entry.taskId());
                ItemStack.STREAM_CODEC.encode(buf, entry.stack());
            }
        }

        public static Snapshot read(RegistryFriendlyByteBuf buf) {
            UUID instance = buf.readUUID();
            long tick = buf.readVarLong();
            int count = buf.readVarInt();
            if (count < 0 || count > CobblemonHubBlockEntity.MAX_WORKERS) throw new IllegalArgumentException("Invalid Hub signal count");
            List<WorkerSignal> workers = new ArrayList<>(count);
            for (int i = 0; i < count; i++) workers.add(new WorkerSignal(buf.readUUID(), buf.readUtf(32),
                    buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));
            int packageCount = buf.readVarInt();
            if (packageCount < 0 || packageCount > CobblemonHubBlockEntity.MAX_PACKAGES)
                throw new IllegalArgumentException("Invalid Hub package count");
            List<VisiblePackage> packages = new ArrayList<>(packageCount);
            for (int i = 0; i < packageCount; i++)
                packages.add(new VisiblePackage(buf.readUUID(), ItemStack.STREAM_CODEC.decode(buf)));
            return new Snapshot(instance, tick, workers, packages);
        }
    }

    public enum Kind { COURIER_PICKUP, COURIER_RECEIVE, PACKAGER_INPUT, PACKAGER_OUTPUT }

    public static Direction sideAt(BlockPos hub, Vec3 point) {
        if (point.y >= hub.getY() + 0.99D) return Direction.UP;
        Vec3 delta = point.subtract(Vec3.atCenterOf(hub));
        if (delta.lengthSqr() < 0.01D) return Direction.UP;
        return Direction.getNearest(delta.x, delta.y, delta.z);
    }

    public record TransferEvent(UUID eventId, UUID taskId, @Nullable UUID workerId, Kind kind,
                                long serverTick, Direction side, ItemStack packageStack, Vec3 interactionPosition) {
        public TransferEvent {
            packageStack = packageStack.copy();
        }

        @Override public ItemStack packageStack() { return packageStack.copy(); }

        public void write(RegistryFriendlyByteBuf buf) {
            buf.writeUUID(eventId);
            buf.writeUUID(taskId);
            buf.writeBoolean(workerId != null);
            if (workerId != null) buf.writeUUID(workerId);
            buf.writeEnum(kind);
            buf.writeVarLong(serverTick);
            buf.writeEnum(side);
            ItemStack.STREAM_CODEC.encode(buf, packageStack);
            buf.writeDouble(interactionPosition.x);
            buf.writeDouble(interactionPosition.y);
            buf.writeDouble(interactionPosition.z);
        }

        public static TransferEvent read(RegistryFriendlyByteBuf buf) {
            UUID event = buf.readUUID();
            UUID task = buf.readUUID();
            UUID worker = buf.readBoolean() ? buf.readUUID() : null;
            Kind kind = buf.readEnum(Kind.class);
            long tick = buf.readVarLong();
            Direction side = buf.readEnum(Direction.class);
            ItemStack stack = ItemStack.STREAM_CODEC.decode(buf);
            Vec3 point = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
            if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) {
                throw new IllegalArgumentException("Invalid Hub interaction position");
            }
            return new TransferEvent(event, task, worker, kind, tick, side, stack, point);
        }
    }

    private Snapshot snapshot = EMPTY;
    private long receivedTick = Long.MIN_VALUE;
    private final LinkedHashMap<UUID, TransferEvent> events = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Long> seenEvents = new LinkedHashMap<>();

    public Snapshot snapshot(long now) {
        return receivedTick == Long.MIN_VALUE || now - receivedTick > SNAPSHOT_TTL_TICKS ? EMPTY : snapshot;
    }

    public void accept(Snapshot value, long now) {
        if (value.serverTick() < snapshot.serverTick() || now - value.serverTick() > SNAPSHOT_TTL_TICKS) return;
        if (!snapshot.instanceId().equals(value.instanceId())) clear();
        snapshot = value;
        receivedTick = now;
    }

    public boolean accept(UUID instanceId, TransferEvent event, long now) {
        if (!snapshot.instanceId().equals(instanceId) || now - event.serverTick() > EVENT_TTL_TICKS
                || event.serverTick() - now > SNAPSHOT_TTL_TICKS) return false;
        prune(now);
        if (seenEvents.containsKey(event.eventId())) return false;
        seenEvents.put(event.eventId(), now);
        while (seenEvents.size() > 64) seenEvents.remove(seenEvents.keySet().iterator().next());
        if (event.kind() == Kind.PACKAGER_OUTPUT) clearTask(event.taskId());
        events.put(event.eventId(), event);
        while (events.size() > MAX_EVENTS) events.remove(events.keySet().iterator().next());
        return true;
    }

    public List<TransferEvent> events(long now) {
        prune(now);
        return List.copyOf(events.values());
    }

    public boolean clearTask(UUID taskId) {
        boolean changed = events.values().removeIf(event -> event.taskId().equals(taskId));
        List<VisiblePackage> remaining = snapshot.packages().stream()
                .filter(entry -> !entry.taskId().equals(taskId)).toList();
        if (remaining.size() != snapshot.packages().size()) {
            snapshot = new Snapshot(snapshot.instanceId(), snapshot.serverTick(),
                    snapshot.workers(), remaining);
            changed = true;
        }
        return changed;
    }

    private void prune(long now) {
        events.values().removeIf(event -> now - event.serverTick() > EVENT_TTL_TICKS);
        seenEvents.values().removeIf(tick -> now - tick > SNAPSHOT_TTL_TICKS);
    }

    public void clear() {
        snapshot = EMPTY;
        receivedTick = Long.MIN_VALUE;
        events.clear();
        seenEvents.clear();
    }
}
