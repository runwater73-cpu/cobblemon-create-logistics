package dev.cobblemoncreate.logistics;

import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * One durable delivery. The package stack is the only cargo record; all
 * transport state is metadata and can be rebuilt after a restart.
 */
public final class DeliveryTask {
    private static final int FORMAT_VERSION = 1;

    private final UUID id;
    private final ItemStack packageStack;
    private final ResourceKey<Level> sourceDimension;
    private final BlockPos sourcePos;
    private ResourceKey<Level> targetDimension;
    private BlockPos targetPos;
    private TransportMode mode;
    private final long createdGameTime;
    private DeliveryState state;
    private DeliveryState pausedFrom;
    private PauseReason pauseReason = PauseReason.UNKNOWN;
    private WorkerLease worker;
    private int retryCount;
    private long lastProgressGameTime;
    private Vec3 lastObservedPosition;

    private DeliveryTask(UUID id, ItemStack packageStack,
                         ResourceKey<Level> sourceDimension, BlockPos sourcePos,
                         ResourceKey<Level> targetDimension, BlockPos targetPos,
                         long createdGameTime) {
        this.id = id;
        this.packageStack = CreatePackageAdapter.canonicalCopy(packageStack);
        this.sourceDimension = sourceDimension;
        this.sourcePos = sourcePos.immutable();
        this.targetDimension = targetDimension;
        this.targetPos = targetPos.immutable();
        this.mode = TransportMode.UNASSIGNED;
        this.createdGameTime = createdGameTime;
        this.lastProgressGameTime = createdGameTime;
        this.state = DeliveryState.BUFFERED;
    }

    public static DeliveryTask create(ItemStack packageStack,
                                       ResourceKey<Level> sourceDimension, BlockPos sourcePos,
                                       ResourceKey<Level> targetDimension, BlockPos targetPos,
                                       long gameTime) {
        return new DeliveryTask(UUID.randomUUID(), packageStack, sourceDimension, sourcePos,
                targetDimension, targetPos, gameTime);
    }

    public UUID id() { return id; }
    public ItemStack packageStack() { return packageStack; }
    public ResourceKey<Level> sourceDimension() { return sourceDimension; }
    public BlockPos sourcePos() { return sourcePos; }
    public ResourceKey<Level> targetDimension() { return targetDimension; }
    public BlockPos targetPos() { return targetPos; }
    public TransportMode mode() { return mode; }
    public long createdGameTime() { return createdGameTime; }
    public DeliveryState state() { return state; }
    public PauseReason pauseReason() { return pauseReason; }
    public WorkerLease worker() { return worker; }
    public int retryCount() { return retryCount; }
    public long lastProgressGameTime() { return lastProgressGameTime; }
    public long retryDelayTicks() {
        return pauseReason == PauseReason.NO_PROGRESS
                ? Math.min(1200L, 40L << Math.min(retryCount, 5)) : 20L;
    }
    public boolean hasCourierDestination() {
        return !sourceDimension.equals(targetDimension) || !sourcePos.equals(targetPos);
    }

    /** The only state a Hub worker may claim from its local outgoing buffer. */
    public boolean isOutgoingBuffer() {
        return state == DeliveryState.BUFFERED;
    }

    /** A committed arrival waiting for the destination-side Create handoff. */
    public boolean isIncomingBuffer() {
        return state == DeliveryState.ARRIVED_BUFFERED;
    }

    void setTarget(BlockPos targetPos) {
        setTarget(targetDimension, targetPos);
    }

    void setTarget(ResourceKey<Level> targetDimension, BlockPos targetPos) {
        requireState(DeliveryState.BUFFERED);
        this.targetDimension = targetDimension;
        this.targetPos = targetPos.immutable();
    }

    void resetDestination(long gameTime) {
        if (state.terminal() || state == DeliveryState.ARRIVED_BUFFERED) return;
        this.targetPos = sourcePos;
        this.targetDimension = sourceDimension;
        this.mode = TransportMode.UNASSIGNED;
        this.worker = null;
        this.pausedFrom = null;
        this.state = DeliveryState.BUFFERED;
        this.lastObservedPosition = null;
        this.lastProgressGameTime = gameTime;
    }

    public ItemStack packageCopy() {
        return packageStack.copy();
    }

    boolean applyDefaultAddress(String address) {
        if (address.isBlank() || !PackageItem.getAddress(packageStack).isBlank()) return false;
        PackageItem.addAddress(packageStack, address);
        return true;
    }

    boolean noteMovement(Vec3 position, long gameTime) {
        // Half a block from the previous progress point ignores idle jitter,
        // while still allowing a real detour around obstacles.
        if (lastObservedPosition == null || lastObservedPosition.distanceToSqr(position) > 0.25D) {
            lastObservedPosition = position;
            lastProgressGameTime = gameTime;
            return true;
        }
        return false;
    }

    /** Waiting for a physical handoff is not a failed navigation attempt. */
    void noteEndpointWait(long gameTime) {
        lastObservedPosition = null;
        lastProgressGameTime = gameTime;
    }

    void claim(WorkerLease worker, TransportMode mode, long gameTime) {
        requireState(DeliveryState.BUFFERED);
        if (mode == TransportMode.UNASSIGNED || mode == null
                || mode == TransportMode.LOCAL && !sourceDimension.equals(targetDimension)) {
            throw new IllegalArgumentException("Courier route does not match destination");
        }
        this.worker = worker;
        this.mode = mode;
        this.pausedFrom = null;
        this.state = DeliveryState.CLAIMED_BY_WORKER;
        this.lastObservedPosition = null;
        this.lastProgressGameTime = gameTime;
    }


    void startTransit(long gameTime) {
        requireState(DeliveryState.CLAIMED_BY_WORKER);
        this.pausedFrom = null;
        this.state = DeliveryState.IN_TRANSIT;
        this.lastProgressGameTime = gameTime;
        this.lastObservedPosition = null;
    }

    void switchToVirtualTransit() {
        requireState(DeliveryState.IN_TRANSIT);
        if (!sourceDimension.equals(targetDimension)) {
            throw new IllegalStateException("Only a same-dimension route can switch from local transit");
        }
        this.mode = TransportMode.VIRTUAL;
    }

    void arrive(long gameTime) {
        requireState(DeliveryState.IN_TRANSIT);
        this.pausedFrom = null;
        this.state = DeliveryState.ARRIVED_BUFFERED;
        this.lastProgressGameTime = gameTime;
    }

    void deliver(long gameTime) {
        requireState(DeliveryState.ARRIVED_BUFFERED);
        this.pausedFrom = null;
        this.state = DeliveryState.DELIVERED;
        this.lastProgressGameTime = gameTime;
    }

    void rollback(long gameTime) {
        if (state.terminal()) return;
        this.pausedFrom = null;
        this.state = DeliveryState.ROLLED_BACK;
        this.retryCount++;
        this.lastProgressGameTime = gameTime;
    }

    void pause(long gameTime, PauseReason reason) {
        if (state.terminal()) return;
        if (state != DeliveryState.PAUSED) {
            this.pausedFrom = state;
            if (reason == PauseReason.NO_PROGRESS) retryCount++;
        }
        this.state = DeliveryState.PAUSED;
        this.pauseReason = reason;
        this.lastProgressGameTime = gameTime;
    }

    void resume(long gameTime) {
        if (state != DeliveryState.PAUSED) return;
        this.state = pausedFrom == DeliveryState.IN_TRANSIT && worker != null
                && pauseReason != PauseReason.NO_PROGRESS
                ? DeliveryState.IN_TRANSIT
                : worker == null ? DeliveryState.BUFFERED : DeliveryState.CLAIMED_BY_WORKER;
        this.pausedFrom = null;
        this.pauseReason = PauseReason.UNKNOWN;
        // A worker may have respawned or moved while paused. Discard the old
        // position so a new entity session gets a full movement timeout.
        this.lastObservedPosition = null;
        this.lastProgressGameTime = gameTime;
    }

    /** Requeues one package after its courier physically reports back home. */
    void requeueAfterCourierReturn(long gameTime) {
        if (state != DeliveryState.PAUSED || worker == null) return;
        this.worker = null;
        this.mode = TransportMode.UNASSIGNED;
        this.pausedFrom = null;
        this.pauseReason = PauseReason.UNKNOWN;
        this.state = DeliveryState.BUFFERED;
        this.lastObservedPosition = null;
        this.lastProgressGameTime = gameTime;
    }

    private void requireState(DeliveryState expected) {
        if (state != expected) {
            throw new IllegalStateException("Task " + id + " is " + state + ", expected " + expected);
        }
    }

    CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Format", FORMAT_VERSION);
        tag.putUUID("Id", id);
        tag.put("Package", packageStack.saveOptional(registries));
        writeDimension(tag, "SourceDimension", sourceDimension);
        tag.put("SourcePos", NbtUtils.writeBlockPos(sourcePos));
        writeDimension(tag, "TargetDimension", targetDimension);
        tag.put("TargetPos", NbtUtils.writeBlockPos(targetPos));
        tag.putByte("Mode", (byte) mode.ordinal());
        tag.putLong("Created", createdGameTime);
        tag.putByte("State", (byte) state.ordinal());
        tag.putByte("PausedFrom", (byte) (pausedFrom == null ? -1 : pausedFrom.ordinal()));
        tag.putByte("PauseReason", (byte) pauseReason.ordinal());
        tag.putInt("Retries", retryCount);
        tag.putLong("LastProgress", lastProgressGameTime);
        if (worker != null) tag.put("Worker", worker.save(registries));
        return tag;
    }

    static DeliveryTask load(HolderLookup.Provider registries, CompoundTag tag) {
        int format = tag.getInt("Format");
        if (format != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported delivery task format " + format);
        }
        ItemStack packageStack = ItemStack.parseOptional(registries, tag.getCompound("Package"));
        if (!CreatePackageAdapter.isPackage(packageStack)) {
            throw new IllegalArgumentException("Saved task does not contain a Create package");
        }
        DeliveryTask task = new DeliveryTask(
                tag.getUUID("Id"), packageStack,
                readDimension(tag, "SourceDimension"),
                NbtUtils.readBlockPos(tag, "SourcePos").orElse(BlockPos.ZERO),
                readDimension(tag, "TargetDimension"),
                NbtUtils.readBlockPos(tag, "TargetPos").orElse(BlockPos.ZERO),
                tag.getLong("Created"));
        task.mode = enumValue(TransportMode.values(), tag.getByte("Mode"), TransportMode.UNASSIGNED);
        task.state = enumValue(DeliveryState.values(), tag.getByte("State"), DeliveryState.BUFFERED);
        task.pausedFrom = enumValue(DeliveryState.values(), tag.getByte("PausedFrom"), null);
        task.pauseReason = enumValue(PauseReason.values(), tag.getByte("PauseReason"), PauseReason.UNKNOWN);
        task.retryCount = tag.getInt("Retries");
        task.lastProgressGameTime = tag.getLong("LastProgress");
        task.worker = tag.contains("Worker") ? WorkerLease.load(tag.getCompound("Worker")) : null;
        return task;
    }

    private static void writeDimension(CompoundTag tag, String key, ResourceKey<Level> dimension) {
        tag.putString(key, dimension.location().toString());
    }

    private static ResourceKey<Level> readDimension(CompoundTag tag, String key) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString(key)));
    }

    private static <T> T enumValue(T[] values, byte ordinal, T fallback) {
        int index = ordinal;
        return index >= 0 && index < values.length ? values[index] : fallback;
    }
}
