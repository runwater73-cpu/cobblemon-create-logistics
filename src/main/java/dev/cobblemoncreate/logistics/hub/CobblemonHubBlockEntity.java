package dev.cobblemoncreate.logistics.hub;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import dev.cobblemoncreate.logistics.CreatePackageAdapter;
import dev.cobblemoncreate.logistics.DeliveryState;
import dev.cobblemoncreate.logistics.DeliveryTask;
import dev.cobblemoncreate.logistics.DeliveryTaskManager;
import dev.cobblemoncreate.logistics.DeliveryTaskSavedData;
import dev.cobblemoncreate.logistics.WorkerLease;
import dev.cobblemoncreate.logistics.RouteChunks;
import dev.cobblemoncreate.logistics.cobblemon.CarrierRuntimeLease;
import dev.cobblemoncreate.logistics.cobblemon.ResidentWorkerRuntime;
import dev.cobblemoncreate.logistics.GhostContraptionPickupRuntime;
import dev.cobblemoncreate.logistics.network.HubWorldNetwork;
import dev.cobblemoncreate.logistics.create.CobblemonPackageEndpoint;
import dev.cobblemoncreate.logistics.registry.LogisticsBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import com.cobblemon.mod.common.net.messages.client.effect.SpawnSnowstormParticlePacket;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The global task store owns cargo; a hub only indexes task and worker identities. */
public final class CobblemonHubBlockEntity extends BlockEntity implements CobblemonPackageEndpoint, MenuProvider {
    public static final int MAX_PACKAGES = 16;
    public static final int MAX_WORKERS = 6;
    /** Destination presets are a convenience list; the Hub address is stored separately. */
    public static final int MAX_ADDRESS_PRESETS = 32;
    private final List<UUID> taskIds = new ArrayList<>();
    private long countedRevision = -1;
    private int reservedCount;
    private final List<WorkerLease> workers = new ArrayList<>();
    private UUID storageUuid = UUID.randomUUID();
    private final List<String> addressPresets = new ArrayList<>();
    private int defaultDestination = -1;
    private String stationAddress = "";
    private final IItemHandler inputHandler = new HubItemHandler(true);
    private final IItemHandler outputHandler = new HubItemHandler(false);
    private final UUID visualInstanceId = UUID.randomUUID();
    private final HubVisualState visualCache = new HubVisualState();
    private List<HubVisualState.WorkerSignal> lastVisualWorkers = List.of();
    private List<HubVisualState.VisiblePackage> lastVisualPackages = List.of();
    private long lastVisualBroadcast = Long.MIN_VALUE;

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK,
                LogisticsBlockEntities.COBBLEMON_HUB.get(),
                (hub, side) -> side == Direction.UP ? hub.inputHandler : hub.outputHandler);
    }

    public CobblemonHubBlockEntity(BlockPos pos, BlockState state) {
        super(LogisticsBlockEntities.COBBLEMON_HUB.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            HubAddressRegistry.register(this);
            reconcileTaskIds(server);
        }
    }

    @Override
    public void onChunkUnloaded() {
        visualCache.clear();
        super.onChunkUnloaded();
    }

    @Override public void setRemoved() {
        visualCache.clear();
        super.setRemoved();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CobblemonHubBlockEntity hub) {
        if (level instanceof ServerLevel server && server.getGameTime() % 10 == 0) {
            hub.pullFromPackager(server);
            hub.pushArrivedToPackager(server);
            if (server.getGameTime() % 40 == 0) {
                hub.ensureResidents(server);
                GhostContraptionPickupRuntime.scanHub(server, hub);
            }
            hub.syncVisualState();
        }
    }

    private void ensureResidents(ServerLevel server) {
        List<DeliveryTask> visibleTasks = trackedTasks();
        for (WorkerLease worker : workers) {
            boolean hasActiveTask = visibleTasks.stream().anyMatch(task ->
                    task != null && (task.state() == DeliveryState.CLAIMED_BY_WORKER
                            || task.state() == DeliveryState.IN_TRANSIT) && task.worker() != null
                            && task.worker().pokemonUuid().equals(worker.pokemonUuid()));
            if (!hasActiveTask && !CarrierRuntimeLease.isWorkerActive(worker.pokemonUuid())
                    && !GhostContraptionPickupRuntime.isWorkerPickingUp(worker.pokemonUuid())
                    && ResidentWorkerRuntime.ensure(server, worker) && !worker.homeReported()) {
                ResidentWorkerRuntime.recoverReturn(server, worker);
            }
        }
    }

    private void pullFromPackager(ServerLevel server) {
        Direction[] searchOrder = {Direction.DOWN, Direction.UP, Direction.NORTH,
                Direction.SOUTH, Direction.WEST, Direction.EAST};
        for (Direction direction : searchOrder) {
            BlockPos adjacent = worldPosition.relative(direction);
            if (!(server.getBlockEntity(adjacent) instanceof PackagerBlockEntity packager)) continue;
            IItemHandler handler = packager.inventory;
            if (handler == null || handler.getSlots() == 0) continue;
            ItemStack offered = handler.extractItem(0, 1, true);
            // A neighbouring packager may be empty, animating, or temporarily
            // blocked by this Hub's capacity. Keep looking so one blocked
            // side cannot starve the other adjacent packagers.
            if (offered.isEmpty() || !canAcceptCreatePackage(offered, true)) continue;
            ItemStack extracted = handler.extractItem(0, 1, false);
            if (extracted.isEmpty()) continue;
            if (!acceptCreatePackage(extracted, server.dimension(), adjacent)) {
                net.minecraft.world.level.block.Block.popResource(server, worldPosition, extracted);
            }
            return;
        }
    }

    /** Gives only committed arrivals to Create's native unpacking path. */
    private void pushArrivedToPackager(ServerLevel server) {
        DeliveryTaskSavedData data = DeliveryTaskSavedData.get(server.getServer());
        for (UUID id : List.copyOf(taskIds)) {
            DeliveryTask task = data.get(id);
            if (task == null) {
                removeTask(id);
                continue;
            }
            if (!isIncomingTask(task)) continue;
            ItemStack box = task.packageCopy();
            for (Direction direction : Direction.values()) {
                BlockPos packagerPos = worldPosition.relative(direction);
                BlockState packagerState = server.getBlockState(packagerPos);
                if (!(server.getBlockEntity(packagerPos) instanceof PackagerBlockEntity packager)
                        || !packagerState.hasProperty(PackagerBlock.FACING)
                        || packagerState.getValue(PackagerBlock.FACING) != direction.getOpposite()) continue;
                ItemStack simulated = packager.inventory.insertItem(0, box, true);
                if (!simulated.isEmpty()) continue;
                ItemStack remainder = packager.inventory.insertItem(0, box, false);
                if (remainder.isEmpty()) {
                    DeliveryTaskManager.collectArrivedPackage(
                            server.getServer(), id, server.getServer().getTickCount());
                    removeTask(id);
                    emitTransfer(task, HubVisualState.Kind.PACKAGER_OUTPUT, direction,
                            Vec3.atCenterOf(worldPosition).add(Vec3.atLowerCornerOf(direction.getNormal()).scale(0.5D)));
                }
                return;
            }
        }
    }

    public UUID storageUuid() { return storageUuid; }

    /** Read-only renderer interface; never accesses server inventory on the client. */
    public HubVisualState.Snapshot visualState() {
        return level == null ? HubVisualState.EMPTY : visualCache.snapshot(level.getGameTime());
    }

    public List<HubVisualState.TransferEvent> visualEvents() {
        return level == null ? List.of() : visualCache.events(level.getGameTime());
    }

    public HubVisualState.Snapshot createVisualSnapshot() {
        if (!(level instanceof ServerLevel server)) return HubVisualState.EMPTY;
        List<DeliveryTask> tasks = trackedTasks();
        List<HubVisualState.WorkerSignal> signals = new ArrayList<>();
        for (WorkerLease worker : workers) {
            DeliveryTask active = tasks.stream().filter(task -> task.worker() != null && !task.state().terminal()
                    && task.state() != DeliveryState.ARRIVED_BUFFERED
                    && task.worker().pokemonUuid().equals(worker.pokemonUuid())).findFirst().orElse(null);
            boolean ghostPickup = GhostContraptionPickupRuntime.isWorkerPickingUp(worker.pokemonUuid());
            boolean busy = active != null || ghostPickup;
            boolean paused = active != null && active.state() == DeliveryState.PAUSED;
            boolean returning = !busy && !paused && !worker.homeReported();
            String phase = CarrierRuntimeLease.workerProgress(worker.pokemonUuid()).phase();
            if (ghostPickup) phase = "pickup";
            if (phase.isEmpty()) phase = paused ? "waiting" : returning ? "returning"
                    : busy ? active != null && active.state() == DeliveryState.CLAIMED_BY_WORKER ? "pickup" : "waiting" : "idle";
            signals.add(new HubVisualState.WorkerSignal(worker.pokemonUuid(), phase, busy, paused, returning));
        }
        List<HubVisualState.VisiblePackage> packages = new ArrayList<>();
        for (DeliveryTask task : tasks) {
            if (task.isOutgoingBuffer() || task.state() == DeliveryState.CLAIMED_BY_WORKER
                    || task.isIncomingBuffer()
                    || task.state() == DeliveryState.PAUSED && task.worker() == null) {
                packages.add(new HubVisualState.VisiblePackage(task.id(), task.packageCopy()));
            }
        }
        return new HubVisualState.Snapshot(visualInstanceId, server.getGameTime(), signals, packages);
    }

    private void syncVisualState() {
        if (!(level instanceof ServerLevel server)
                || server.getChunkSource().chunkMap.getPlayers(new ChunkPos(worldPosition), false).isEmpty()) return;
        HubVisualState.Snapshot snapshot = createVisualSnapshot();
        if (lastVisualBroadcast != Long.MIN_VALUE && snapshot.workers().equals(lastVisualWorkers)
                && sameVisualPackages(snapshot.packages(), lastVisualPackages)
                && server.getGameTime() - lastVisualBroadcast < 40) return;
        lastVisualWorkers = snapshot.workers();
        lastVisualPackages = snapshot.packages();
        lastVisualBroadcast = server.getGameTime();
        visualCache.accept(snapshot, server.getGameTime());
        HubWorldNetwork.sendVisualState(server, worldPosition, snapshot);
    }

    private static boolean sameVisualPackages(List<HubVisualState.VisiblePackage> a,
                                               List<HubVisualState.VisiblePackage> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).taskId().equals(b.get(i).taskId())
                    || !ItemStack.matches(a.get(i).stack(), b.get(i).stack())) return false;
        }
        return true;
    }

    public void sendVisualState(ServerPlayer player) {
        if (level instanceof ServerLevel server) HubWorldNetwork.sendVisualState(player, server, worldPosition, createVisualSnapshot());
    }

    public void acceptVisualState(HubVisualState.Snapshot snapshot) {
        if (level != null && level.isClientSide()) visualCache.accept(snapshot, level.getGameTime());
    }

    public void acceptVisualEvent(UUID instanceId, HubVisualState.TransferEvent event) {
        if (level != null && level.isClientSide()) visualCache.accept(instanceId, event, level.getGameTime());
    }

    public void clearVisualTask(UUID instanceId, UUID taskId) {
        if (level != null && level.isClientSide() && visualState().instanceId().equals(instanceId)) visualCache.clearTask(taskId);
    }

    /** Call only after the inventory or courier transaction has committed. */
    public void emitTransfer(DeliveryTask task, HubVisualState.Kind kind, Direction side, Vec3 point) {
        if (!(level instanceof ServerLevel server) || task.packageStack().isEmpty()) return;
        HubVisualState.Snapshot snapshot = createVisualSnapshot();
        visualCache.accept(snapshot, server.getGameTime());
        HubVisualState.TransferEvent event = new HubVisualState.TransferEvent(UUID.randomUUID(), task.id(),
                task.worker() == null ? null : task.worker().pokemonUuid(), kind, server.getGameTime(), side,
                task.packageCopy(), point);
        visualCache.accept(visualInstanceId, event, server.getGameTime());
        HubWorldNetwork.sendVisualEvent(server, worldPosition, snapshot, event);
        // Keep the effect cosmetic and bounded: one native spark burst per committed handoff.
        // The packet itself applies the normal Cobblemon particle distance culling.
        new SpawnSnowstormParticlePacket(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cobblemon", "metal_sparks"),
                point).sendToPlayersAround(point.x, point.y, point.z, 32.0D, server.dimension(), player -> true);
    }

    public void cancelVisualTask(UUID taskId) {
        if (level instanceof ServerLevel server && visualCache.clearTask(taskId)) {
            HubWorldNetwork.clearVisualTask(server, worldPosition, visualInstanceId, taskId);
        }
    }

    public void openFor(ServerPlayer player) {
        openFor(player, 0, 0);
    }

    public void openFor(ServerPlayer player, int boxIndex, int pageIndex) {
        player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return CobblemonHubBlockEntity.this.getDisplayName(); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player menuPlayer) {
                return new HubMenu(id, inventory, CobblemonHubBlockEntity.this, boxIndex, pageIndex);
            }
        }, buf -> HubMenu.writeOpeningData(buf, player, this, boxIndex, pageIndex));
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.cobblemon_create_logistics.cobblemon_hub");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new HubMenu(id, inventory, this);
    }

    public boolean addAddress(String value) {
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 64) return false;
        int existing = addressPresets.indexOf(normalized);
        if (existing < 0) {
            if (addressPresets.size() >= MAX_ADDRESS_PRESETS) return false;
            addressPresets.add(normalized);
            existing = addressPresets.size() - 1;
            if (defaultDestination < 0) defaultDestination = existing;
        }
        setChanged();
        return true;
    }

    public boolean removeAddress(int index) {
        if (index < 0 || index >= addressPresets.size()) return false;
        addressPresets.remove(index);
        defaultDestination = afterRemoval(defaultDestination, index, addressPresets.size());
        setChanged();
        return true;
    }

    private static int afterRemoval(int selected, int removed, int size) {
        if (size == 0) return -1;
        if (selected == removed) return Math.min(removed, size - 1);
        return selected > removed ? selected - 1 : selected;
    }

    public boolean setDefaultDestination(int index) {
        if (index < 0 || index >= addressPresets.size()) return false;
        if (defaultDestination != index) {
            defaultDestination = index;
            setChanged();
        }
        return true;
    }

    public List<String> addressPresets() { return List.copyOf(addressPresets); }
    public String stationAddress() { return stationAddress; }
    public int defaultDestination() { return defaultDestination; }
    public String defaultAddress() { return defaultDestination >= 0 && defaultDestination < addressPresets.size()
            ? addressPresets.get(defaultDestination) : ""; }

    /** The receive address is independent from the destination preset list. */
    public String address() {
        return stationAddress;
    }

    public boolean setStationAddress(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() > 64) return false;
        if (normalized.equals(stationAddress)) {
            HubAddressRegistry.refresh(this);
            return true;
        }
        stationAddress = normalized;
        setChanged();
        HubAddressRegistry.refresh(this);
        return true;
    }

    private CobblemonHubBlockEntity destination(ItemStack stack) {
        if (!(level instanceof ServerLevel server)) return null;
        String address = PackageItem.getAddress(stack);
        if (address.isBlank()) address = defaultAddress();
        return address.isBlank() ? null : HubAddressRegistry.find(server, worldPosition, address);
    }

    public BlockPos resolveDestination(ItemStack stack) {
        HubAddressRegistry.Destination target = resolveDestinationTarget(stack);
        return target == null ? null : target.pos();
    }

    public HubAddressRegistry.Destination resolveDestinationTarget(ItemStack stack) {
        if (!(level instanceof ServerLevel server)) return null;
        String address = PackageItem.getAddress(stack);
        if (address.isBlank()) address = defaultAddress();
        return address.isBlank() ? null
                : HubAddressRegistry.findDestination(server.getServer(), server.dimension(), worldPosition, address);
    }

    public boolean acceptsPackageAddress(ItemStack stack) {
        return PackageItem.matchAddress(stack, address());
    }

    @Override
    public boolean canAcceptCreatePackage(ItemStack stack, boolean simulate) {
        if (!CreatePackageAdapter.isPackage(stack) || !(level instanceof ServerLevel server)) return false;
        if (!simulate) pruneStaleTasks(server);
        return reservedTaskCount(server) < MAX_PACKAGES;
    }

    @Override
    public boolean acceptCreatePackage(ItemStack stack, ResourceKey<Level> sourceDimension, BlockPos sourcePos) {
        DeliveryTask task = acceptCreatePackageTask(stack);
        if (task == null) return false;
        Direction side = sourceDimension.equals(level.dimension()) && !sourcePos.equals(worldPosition)
                ? Direction.getNearest(sourcePos.getX() - worldPosition.getX(), sourcePos.getY() - worldPosition.getY(),
                        sourcePos.getZ() - worldPosition.getZ()) : Direction.UP;
        emitTransfer(task, HubVisualState.Kind.PACKAGER_INPUT, side,
                Vec3.atCenterOf(worldPosition).add(Vec3.atLowerCornerOf(side.getNormal()).scale(0.5D)));
        return true;
    }

    /** Returns the newly owned task for a same-tick external pickup handoff. */
    public DeliveryTask acceptCreatePackageTask(ItemStack stack) {
        if (!(level instanceof ServerLevel server)) return null;
        pruneStaleTasks(server);
        if (reservedTaskCount(server) >= MAX_PACKAGES || !CreatePackageAdapter.isPackage(stack)) return null;
        ItemStack routedPackage = CreatePackageAdapter.canonicalCopy(stack);
        if (PackageItem.getAddress(routedPackage).isBlank() && !defaultAddress().isBlank()) {
            PackageItem.addAddress(routedPackage, defaultAddress());
        }
        HubAddressRegistry.Destination destination = resolveDestinationTarget(routedPackage);
        DeliveryTask task = DeliveryTaskManager.submit(server.getServer(), routedPackage,
                server.dimension(), worldPosition,
                destination == null ? server.dimension() : destination.dimension(),
                destination == null ? worldPosition : destination.pos(),
                server.getServer().getTickCount());
        taskIds.add(task.id());
        setChanged();
        return task;
    }

    private void pruneStaleTasks(ServerLevel level) {
        DeliveryTaskSavedData data = DeliveryTaskSavedData.get(level.getServer());
        if (taskIds.removeIf(id -> {
            DeliveryTask task = data.get(id);
            return task == null || task.state().terminal() || !ownsTask(task);
        })) setChanged();
    }

    private void reconcileTaskIds(ServerLevel server) {
        DeliveryTaskSavedData data = DeliveryTaskSavedData.get(server.getServer());
        for (DeliveryTask task : data.tasks()) {
            if (!task.state().terminal() && ownsTask(task) && !taskIds.contains(task.id())) {
                taskIds.add(task.id());
                setChanged();
            }
        }
        pruneStaleTasks(server);
    }

    public boolean ownsTask(DeliveryTask task) {
        if (!(level instanceof ServerLevel server)) return false;
        if (task.isIncomingBuffer()) return isIncomingTask(task);
        return task.sourceDimension().equals(server.dimension()) && task.sourcePos().equals(worldPosition);
    }

    /** Only the destination Hub may consume an arrived package. */
    public boolean isIncomingTask(DeliveryTask task) {
        if (!(level instanceof ServerLevel server) || !task.isIncomingBuffer()) return false;
        return task.targetDimension().equals(server.dimension()) && task.targetPos().equals(worldPosition);
    }

    private int reservedTaskCount(ServerLevel level) {
        DeliveryTaskSavedData data = DeliveryTaskSavedData.get(level.getServer());
        if (countedRevision == data.revision()) return reservedCount;
        int count = 0;
        for (DeliveryTask task : data.tasks()) {
            if (task.state().terminal()) continue;
            if (task.sourceDimension().equals(level.dimension()) && task.sourcePos().equals(worldPosition)
                    && !task.isIncomingBuffer()) {
                count++;
            } else if (task.targetDimension().equals(level.dimension())
                    && task.targetPos().equals(worldPosition)
                    && (task.hasCourierDestination() || task.isIncomingBuffer())) {
                count++;
            }
        }
        reservedCount = count;
        countedRevision = data.revision();
        return count;
    }

    public List<UUID> taskIds() { return List.copyOf(taskIds); }
    public List<DeliveryTask> trackedTasks() {
        if (!(level instanceof ServerLevel server)) return List.of();
        List<DeliveryTask> visible = new ArrayList<>();
        for (DeliveryTask task : DeliveryTaskSavedData.get(server.getServer()).tasks()) {
            if (!task.state().terminal() && ownsTask(task)) {
                visible.add(task);
                if (visible.size() == MAX_PACKAGES) break;
            }
        }
        return List.copyOf(visible);
    }
    public boolean hasTask(UUID taskId) { return taskIds.contains(taskId); }
    public void removeTask(UUID taskId) {
        if (taskIds.remove(taskId)) setChanged();
    }
    public List<WorkerLease> workers() { return List.copyOf(workers); }
    public int assignWorker(WorkerLease worker) {
        if (workers.size() >= MAX_WORKERS && workers.stream().noneMatch(existing ->
                existing.pokemonUuid().equals(worker.pokemonUuid()))) return -1;
        workers.removeIf(existing -> existing.pokemonUuid().equals(worker.pokemonUuid()));
        workers.add(worker);
        setChanged();
        return workers.size();
    }

    /** Marks a resident as away as soon as this Hub commits a package to it. */
    public void markWorkerAway(UUID pokemonId) {
        for (int i = 0; i < workers.size(); i++) {
            WorkerLease worker = workers.get(i);
            if (worker.pokemonUuid().equals(pokemonId) && worker.homeReported()) {
                workers.set(i, worker.withHomeReported(false));
                setChanged();
                return;
            }
        }
    }

    /** Accepts the courier's physical return report at this Hub. */
    public boolean reportWorkerHome(UUID pokemonId) {
        for (int i = 0; i < workers.size(); i++) {
            WorkerLease worker = workers.get(i);
            if (worker.pokemonUuid().equals(pokemonId) && !worker.homeReported()) {
                workers.set(i, worker.withHomeReported(true));
                setChanged();
                return true;
            }
        }
        return false;
    }

    /** Server-side external interaction used by a courier's return animation. */
    public boolean interactWithReturningCourier(UUID pokemonId) {
        return reportWorkerHome(pokemonId);
    }

    public boolean hasWorker(UUID pokemonId) {
        return workers.stream().anyMatch(worker -> worker.pokemonUuid().equals(pokemonId));
    }

    public boolean isWorkerHomeReported(UUID pokemonId) {
        return workers.stream().anyMatch(worker -> worker.pokemonUuid().equals(pokemonId)
                && worker.homeReported());
    }

    public boolean removeWorker(UUID pokemonId, UUID ownerId) {
        if (CarrierRuntimeLease.isWorkerActive(pokemonId)
                || GhostContraptionPickupRuntime.isWorkerPickingUp(pokemonId)) return false;
        if (level instanceof ServerLevel server) {
            pruneStaleTasks(server);
            if (trackedTasks().stream().anyMatch(task ->
                    task != null && (task.state() == DeliveryState.CLAIMED_BY_WORKER
                            || task.state() == DeliveryState.IN_TRANSIT
                            || task.state() == DeliveryState.PAUSED) && task.worker() != null
                            && task.worker().pokemonUuid().equals(pokemonId))) return false;
        }
        for (WorkerLease worker : List.copyOf(workers)) {
            if (worker.pokemonUuid().equals(pokemonId) && worker.ownerUuid().equals(ownerId)) {
                if (!(level instanceof ServerLevel server) || !worker.homeReported()
                        || !ResidentWorkerRuntime.release(worker, server)) return false;
                workers.remove(worker);
                setChanged();
                return true;
            }
        }
        return false;
    }

    public void releaseWorkers(ServerLevel server) {
        GhostContraptionPickupRuntime.cancelHub(server, this);
        for (WorkerLease worker : workers) {
            if (!ResidentWorkerRuntime.release(worker, server)) {
                DeliveryTaskSavedData.get(server.getServer()).queueWorkerReturn(worker);
                dev.cobblemoncreate.logistics.CobblemonCreateLogistics.LOGGER.error(
                        "Queued courier {} for return after Hub at {} was broken", worker.pokemonUuid(), worldPosition);
            }
        }
        workers.clear();
        setChanged();
    }
    public boolean canAcceptIncomingTask(@Nullable UUID taskId) {
        if (!(level instanceof ServerLevel server)) return false;
        if (taskId != null && taskIds.contains(taskId)) return true;
        int reserved = reservedTaskCount(server);
        if (reserved < MAX_PACKAGES) return true;
        if (taskId == null || reserved > MAX_PACKAGES) return false;
        DeliveryTask task = DeliveryTaskSavedData.get(server.getServer()).get(taskId);
        return task != null && task.hasCourierDestination()
                && task.targetDimension().equals(server.dimension())
                && task.targetPos().equals(worldPosition);
    }
    public void acceptIncomingTask(UUID taskId) {
        if (!taskIds.contains(taskId)) {
            taskIds.add(taskId);
            setChanged();
        }
    }

    public ItemStack collectAvailablePackage() {
        if (!(level instanceof ServerLevel server)) return ItemStack.EMPTY;
        DeliveryTaskSavedData data = DeliveryTaskSavedData.get(server.getServer());
        DeliveryTask task = availableTask(data);
        if (task == null) return ItemStack.EMPTY;
        return collectPackage(task.id());
    }

    public ItemStack collectPackage(UUID taskId) {
        if (!(level instanceof ServerLevel server)) return ItemStack.EMPTY;
        DeliveryTask task = DeliveryTaskSavedData.get(server.getServer()).get(taskId);
        if (task == null || !ownsTask(task)) return ItemStack.EMPTY;
        boolean incoming = isIncomingTask(task);
        boolean outgoing = task.sourceDimension().equals(server.dimension())
                && task.sourcePos().equals(worldPosition);
        if (!incoming && !(outgoing && (task.state() == DeliveryState.BUFFERED
                || task.state() == DeliveryState.CLAIMED_BY_WORKER
                || task.state() == DeliveryState.PAUSED))) return ItemStack.EMPTY;
        ItemStack result = incoming
                ? DeliveryTaskManager.collectArrivedPackage(server.getServer(), task.id(), server.getServer().getTickCount())
                : task.state() == DeliveryState.PAUSED
                ? DeliveryTaskManager.reclaimPausedPackage(server.getServer(), task.id(), server.getServer().getTickCount())
                : task.state() == DeliveryState.CLAIMED_BY_WORKER
                ? DeliveryTaskManager.reclaimClaimedPackage(server.getServer(), task.id(), server.getServer().getTickCount())
                : DeliveryTaskManager.reclaimBufferedPackage(server.getServer(), task.id(), server.getServer().getTickCount());
        if (!result.isEmpty()) removeTask(task.id());
        return result;
    }

    private DeliveryTask availableTask(DeliveryTaskSavedData data) {
        DeliveryTask buffered = null;
        for (UUID id : List.copyOf(taskIds)) {
            DeliveryTask task = data.get(id);
            if (task == null) {
                removeTask(id);
                continue;
            }
            if (isIncomingTask(task)) {
                return task;
            }
            if (buffered == null && task.isOutgoingBuffer() && ownsTask(task)) buffered = task;
            if (buffered == null && task.state() == DeliveryState.CLAIMED_BY_WORKER && ownsTask(task)) buffered = task;
            if (buffered == null && task.state() == DeliveryState.PAUSED && ownsTask(task)) buffered = task;
        }
        return buffered;
    }

    private ItemStack arrivedPackage() {
        if (!(level instanceof ServerLevel server)) return ItemStack.EMPTY;
        DeliveryTaskSavedData data = DeliveryTaskSavedData.get(server.getServer());
        for (UUID id : taskIds) {
            DeliveryTask task = data.get(id);
            if (task != null && isIncomingTask(task)) {
                return task.packageCopy();
            }
        }
        return ItemStack.EMPTY;
    }

    private ItemStack collectArrivedPackage() {
        if (!(level instanceof ServerLevel server)) return ItemStack.EMPTY;
        DeliveryTaskSavedData data = DeliveryTaskSavedData.get(server.getServer());
        for (UUID id : List.copyOf(taskIds)) {
            DeliveryTask task = data.get(id);
            if (task != null && isIncomingTask(task)) {
                ItemStack result = DeliveryTaskManager.collectArrivedPackage(
                        server.getServer(), id, server.getServer().getTickCount());
                if (!result.isEmpty()) removeTask(id);
                return result;
            }
        }
        return ItemStack.EMPTY;
    }

    private final class HubItemHandler implements IItemHandler {
        private final boolean input;
        private HubItemHandler(boolean input) { this.input = input; }
        @Override public int getSlots() { return 1; }
        @Override public ItemStack getStackInSlot(int slot) {
            return slot == 0 && !input ? arrivedPackage() : ItemStack.EMPTY;
        }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (!input || slot != 0 || !canAcceptCreatePackage(stack, true)) return stack;
            if (simulate || acceptCreatePackage(stack, ((ServerLevel) level).dimension(), worldPosition)) {
                ItemStack remainder = stack.copy();
                remainder.shrink(1);
                return remainder;
            }
            return stack;
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (input || slot != 0 || amount < 1) return ItemStack.EMPTY;
            return simulate ? arrivedPackage() : collectArrivedPackage();
        }
        @Override public int getSlotLimit(int slot) { return slot == 0 ? 1 : 0; }
        @Override public boolean isItemValid(int slot, ItemStack stack) {
            return input && slot == 0 && CreatePackageAdapter.isPackage(stack);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        storageUuid = tag.hasUUID("Storage") ? tag.getUUID("Storage") : UUID.randomUUID();
        addressPresets.clear();
        ListTag addresses = tag.getList("AddressPresets", Tag.TAG_STRING);
        for (int i = 0; i < addresses.size() && addressPresets.size() < MAX_ADDRESS_PRESETS; i++) {
            String value = addresses.getString(i).strip();
            if (!value.isEmpty() && value.length() <= 64 && !addressPresets.contains(value)) {
                addressPresets.add(value);
            }
        }
        defaultDestination = addressPresets.isEmpty() ? -1
                : Math.clamp(tag.getInt("DefaultDestination"), 0, addressPresets.size() - 1);
        stationAddress = tag.contains("StationAddress", Tag.TAG_STRING)
                ? tag.getString("StationAddress").strip()
                : "";
        if (stationAddress.length() > 64) stationAddress = stationAddress.substring(0, 64);
        taskIds.clear();
        ListTag tasks = tag.getList("Tasks", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < tasks.size(); i++) taskIds.add(net.minecraft.nbt.NbtUtils.loadUUID(tasks.get(i)));
        workers.clear();
        ListTag workerList = tag.getList("Workers", Tag.TAG_COMPOUND);
        for (int i = 0; i < workerList.size(); i++) workers.add(WorkerLease.load(workerList.getCompound(i)));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag tasks = new ListTag();
        for (UUID id : taskIds) tasks.add(net.minecraft.nbt.NbtUtils.createUUID(id));
        tag.put("Tasks", tasks);
        ListTag workerList = new ListTag();
        for (WorkerLease worker : workers) workerList.add(worker.save(registries));
        tag.put("Workers", workerList);
        tag.putUUID("Storage", storageUuid);
        ListTag addresses = new ListTag();
        for (String address : addressPresets) addresses.add(StringTag.valueOf(address));
        tag.put("AddressPresets", addresses);
        tag.putInt("DefaultDestination", defaultDestination);
        tag.putString("StationAddress", stationAddress);
    }
}
