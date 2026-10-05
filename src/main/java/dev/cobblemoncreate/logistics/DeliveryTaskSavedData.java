package dev.cobblemoncreate.logistics;

import dev.cobblemoncreate.logistics.cobblemon.ResidentWorkerRuntime;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One global authoritative task store, attached to the Overworld data storage. */
public final class DeliveryTaskSavedData extends SavedData {
    public static final String DATA_NAME = "cobblemon_create_logistics_tasks";
    private final Map<UUID, DeliveryTask> tasks = new LinkedHashMap<>();
    private final Map<UUID, WorkerLease> pendingWorkerReturns = new LinkedHashMap<>();
    private long revision;

    public static SavedData.Factory<DeliveryTaskSavedData> factory() {
        return new SavedData.Factory<>(DeliveryTaskSavedData::new, DeliveryTaskSavedData::load, null);
    }

    private static DeliveryTaskSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        DeliveryTaskSavedData data = new DeliveryTaskSavedData();
        ListTag list = tag.getList("Tasks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                DeliveryTask task = DeliveryTask.load(registries, list.getCompound(i));
                data.tasks.put(task.id(), task);
            } catch (RuntimeException ex) {
                CobblemonCreateLogistics.LOGGER.warn("Skipping malformed delivery task {}", i, ex);
            }
        }
        ListTag returns = tag.getList("PendingWorkerReturns", Tag.TAG_COMPOUND);
        for (int i = 0; i < returns.size(); i++) {
            try {
                WorkerLease worker = WorkerLease.load(returns.getCompound(i));
                data.pendingWorkerReturns.put(worker.pokemonUuid(), worker);
            } catch (RuntimeException ex) {
                CobblemonCreateLogistics.LOGGER.warn("Skipping malformed courier return {}", i, ex);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (DeliveryTask task : tasks.values()) {
            // Terminal tasks have already committed their package outcome. Do
            // not retain a second cargo history forever; the live package or
            // rollback destination is the source of truth after this point.
            if (!task.state().terminal()) {
                list.add(task.save(registries));
            }
        }
        tag.put("Tasks", list);
        ListTag returns = new ListTag();
        for (WorkerLease worker : pendingWorkerReturns.values()) {
            returns.add(worker.save(registries));
        }
        tag.put("PendingWorkerReturns", returns);
        return tag;
    }

    public static DeliveryTaskSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public Collection<DeliveryTask> tasks() { return tasks.values(); }

    public DeliveryTask get(UUID id) { return tasks.get(id); }

    public void add(DeliveryTask task) {
        if (tasks.putIfAbsent(task.id(), task) != null) {
            throw new IllegalArgumentException("Duplicate delivery task " + task.id());
        }
        markDirtyNow();
    }

    /** Drops terminal metadata from the live index after the outcome commits. */
    public void remove(UUID id) {
        tasks.remove(id);
        markDirtyNow();
    }

    public long revision() { return revision; }

    public void markDirtyNow() {
        revision++;
        setDirty();
    }

    public void queueWorkerReturn(WorkerLease worker) {
        pendingWorkerReturns.put(worker.pokemonUuid(), worker);
        markDirtyNow();
    }

    public void retryWorkerReturns(MinecraftServer server) {
        boolean changed = false;
        for (WorkerLease worker : java.util.List.copyOf(pendingWorkerReturns.values())) {
            ServerLevel level = server.getLevel(worker.dimension());
            if (level != null && ResidentWorkerRuntime.release(worker, level)) {
                pendingWorkerReturns.remove(worker.pokemonUuid());
                changed = true;
            }
        }
        if (changed) markDirtyNow();
    }
}
