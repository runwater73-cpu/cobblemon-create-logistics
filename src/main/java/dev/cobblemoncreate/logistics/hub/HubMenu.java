package dev.cobblemoncreate.logistics.hub;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.pc.PCPosition;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.cobblemoncreate.logistics.DeliveryState;
import dev.cobblemoncreate.logistics.DeliveryTask;
import dev.cobblemoncreate.logistics.DeliveryTaskSavedData;
import dev.cobblemoncreate.logistics.PauseReason;
import dev.cobblemoncreate.logistics.WorkerLease;
import dev.cobblemoncreate.logistics.JourneyProgress;
import dev.cobblemoncreate.logistics.CarrierProfile;
import dev.cobblemoncreate.logistics.CarrierProfiles;
import dev.cobblemoncreate.logistics.cobblemon.CarrierRuntimeLease;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.cobblemoncreate.logistics.cobblemon.ResidentWorkerRuntime;
import dev.cobblemoncreate.logistics.network.HubNetwork;
import dev.cobblemoncreate.logistics.registry.LogisticsMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class HubMenu extends AbstractContainerMenu {
    public record Entry(UUID id, String name, int level, boolean busy, boolean paused,
                        boolean returning, boolean owned,
                        ResourceLocation species, Set<String> aspects, JourneyProgress progress) {
        public Entry(UUID id, String name, int level, boolean busy, boolean paused,
                     boolean returning, boolean owned, ResourceLocation species, Set<String> aspects) {
            this(id, name, level, busy, paused, returning, owned, species, aspects, JourneyProgress.NONE);
        }
        private static void write(RegistryFriendlyByteBuf buf, Entry entry) {
            buf.writeUUID(entry.id);
            buf.writeUtf(entry.name, 80);
            buf.writeVarInt(entry.level);
            buf.writeBoolean(entry.busy);
            buf.writeBoolean(entry.paused);
            buf.writeBoolean(entry.returning);
            buf.writeBoolean(entry.owned);
            buf.writeResourceLocation(entry.species);
            buf.writeVarInt(entry.aspects.size());
            for (String aspect : entry.aspects) buf.writeUtf(aspect, 64);
            entry.progress.write(buf);
        }

        private static Entry read(RegistryFriendlyByteBuf buf) {
            UUID id = buf.readUUID();
            String name = buf.readUtf(80);
            int level = buf.readVarInt();
            boolean busy = buf.readBoolean();
            boolean paused = buf.readBoolean();
            boolean returning = buf.readBoolean();
            boolean owned = buf.readBoolean();
            ResourceLocation species = buf.readResourceLocation();
            int rawCount = buf.readVarInt();
            if (rawCount < 0 || rawCount > 256) {
                throw new IllegalArgumentException("Invalid Cobblemon aspect count " + rawCount);
            }
            Set<String> aspects = new java.util.HashSet<>();
            for (int i = 0; i < rawCount; i++) {
                String aspect = buf.readUtf(64);
                if (i < 32) aspects.add(aspect);
            }
            return new Entry(id, name, level, busy, paused, returning, owned, species, Set.copyOf(aspects),
                    JourneyProgress.read(buf));
        }
    }

    public record TaskEntry(UUID id, String target, DeliveryState state,
                            PauseReason pauseReason, ResourceLocation packageItem,
                            String courierName, boolean remoteTransit, boolean routeResolved,
                            boolean reclaimable, JourneyProgress progress) {
        public TaskEntry(UUID id, String target, DeliveryState state, PauseReason pauseReason,
                         ResourceLocation packageItem, String courierName, boolean remoteTransit,
                         boolean routeResolved, boolean reclaimable) {
            this(id, target, state, pauseReason, packageItem, courierName, remoteTransit,
                    routeResolved, reclaimable, JourneyProgress.NONE);
        }
        private static void write(RegistryFriendlyByteBuf buf, TaskEntry entry) {
            buf.writeUUID(entry.id);
            buf.writeUtf(entry.target, 64);
            buf.writeVarInt(entry.state.ordinal());
            buf.writeVarInt(entry.pauseReason.ordinal());
            buf.writeResourceLocation(entry.packageItem);
            buf.writeUtf(entry.courierName, 80);
            buf.writeBoolean(entry.remoteTransit);
            buf.writeBoolean(entry.routeResolved);
            buf.writeBoolean(entry.reclaimable);
            entry.progress.write(buf);
        }

        private static TaskEntry read(RegistryFriendlyByteBuf buf) {
            UUID id = buf.readUUID();
            String target = buf.readUtf(64);
            int ordinal = buf.readVarInt();
            DeliveryState[] states = DeliveryState.values();
            DeliveryState state = ordinal >= 0 && ordinal < states.length
                    ? states[ordinal] : DeliveryState.PAUSED;
            int reasonOrdinal = buf.readVarInt();
            PauseReason[] reasons = PauseReason.values();
            PauseReason reason = reasonOrdinal >= 0 && reasonOrdinal < reasons.length
                    ? reasons[reasonOrdinal] : PauseReason.UNKNOWN;
            return new TaskEntry(id, target, state, reason, buf.readResourceLocation(),
                    buf.readUtf(80), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                    JourneyProgress.read(buf));
        }
    }

    public record Snapshot(String stationAddress, List<String> addresses, int defaultDestination,
                           int boxIndex, int pageIndex, int boxCount,
                           List<Entry> pcEntries, List<Entry> workers, List<TaskEntry> tasks,
                           int buffered, int arriving, int reclaimable) {}

    private final BlockPos pos;
    private List<String> addresses;
    private String stationAddress;
    private int defaultDestination;
    private List<Entry> pcEntries;
    private List<Entry> workers;
    private List<TaskEntry> tasks;
    private int boxIndex;
    private int pageIndex;
    private int boxCount;
    private int buffered;
    private int arriving;
    private int reclaimable;
    private final ServerPlayer serverPlayer;

    public HubMenu(int id, Inventory inventory, RegistryFriendlyByteBuf data) {
        super(LogisticsMenus.HUB.get(), id);
        pos = data.readBlockPos();
        serverPlayer = null;
        apply(readSnapshot(data));
    }

    public HubMenu(int id, Inventory inventory, CobblemonHubBlockEntity hub,
                   int requestedBox, int requestedPage) {
        super(LogisticsMenus.HUB.get(), id);
        pos = hub.getBlockPos();
        serverPlayer = (ServerPlayer) inventory.player;
        addresses = hub.addressPresets();
        stationAddress = hub.stationAddress();
        defaultDestination = hub.defaultDestination();
        var ownerPc = Cobblemon.INSTANCE.getStorage().getPC(inventory.player.getUUID(),
                inventory.player.registryAccess());
        boxCount = Math.max(1, ownerPc.getBoxes().size());
        boxIndex = Math.clamp(requestedBox, 0, boxCount - 1);
        pageIndex = Math.clamp(requestedPage, 0, 1);
        pcEntries = pcEntries((ServerPlayer) inventory.player, boxIndex, pageIndex);
        workers = workerEntries((ServerPlayer) inventory.player, hub);
        tasks = taskEntries((ServerPlayer) inventory.player, hub);
        int queued = 0;
        int delivered = 0;
        int reclaimableCount = 0;
        for (TaskEntry task : tasks) {
            if (task.state() == DeliveryState.ARRIVED_BUFFERED) {
                delivered++;
                if (task.reclaimable()) reclaimableCount++;
            } else {
                queued++;
                if (task.reclaimable()) reclaimableCount++;
            }
        }
        buffered = queued;
        arriving = delivered;
        reclaimable = reclaimableCount;
    }

    public HubMenu(int id, Inventory inventory, CobblemonHubBlockEntity hub) {
        this(id, inventory, hub, 0, 0);
    }

    public static void writeOpeningData(RegistryFriendlyByteBuf buf, ServerPlayer player,
                                        CobblemonHubBlockEntity hub, int boxIndex, int pageIndex) {
        HubMenu snapshot = new HubMenu(0, player.getInventory(), hub, boxIndex, pageIndex);
        buf.writeBlockPos(snapshot.pos);
        writeSnapshot(buf, snapshot.snapshot());
    }

    public Snapshot snapshot() {
        return new Snapshot(stationAddress, addresses, defaultDestination,
                boxIndex, pageIndex, boxCount, pcEntries, workers,
                tasks, buffered, arriving, reclaimable);
    }

    public void apply(Snapshot snapshot) {
        addresses = snapshot.addresses();
        stationAddress = snapshot.stationAddress();
        defaultDestination = snapshot.defaultDestination();
        boxIndex = snapshot.boxIndex();
        pageIndex = snapshot.pageIndex();
        boxCount = snapshot.boxCount();
        pcEntries = snapshot.pcEntries();
        workers = snapshot.workers();
        tasks = snapshot.tasks();
        buffered = snapshot.buffered();
        arriving = snapshot.arriving();
        reclaimable = snapshot.reclaimable();
    }

    public static void writeSnapshot(RegistryFriendlyByteBuf buf, Snapshot snapshot) {
        buf.writeUtf(snapshot.stationAddress(), 64);
        buf.writeVarInt(snapshot.addresses().size());
        for (String address : snapshot.addresses()) buf.writeUtf(address, 64);
        buf.writeVarInt(snapshot.defaultDestination());
        buf.writeVarInt(snapshot.boxIndex());
        buf.writeVarInt(snapshot.pageIndex());
        buf.writeVarInt(snapshot.boxCount());
        writeEntries(buf, snapshot.pcEntries());
        writeEntries(buf, snapshot.workers());
        writeTasks(buf, snapshot.tasks());
        buf.writeVarInt(snapshot.buffered());
        buf.writeVarInt(snapshot.arriving());
        buf.writeVarInt(snapshot.reclaimable());
    }

    public static Snapshot readSnapshot(RegistryFriendlyByteBuf buf) {
        String stationAddress = buf.readUtf(64);
        int rawCount = buf.readVarInt();
        if (rawCount < 0 || rawCount > 256) {
            throw new IllegalArgumentException("Invalid Hub address count " + rawCount);
        }
        List<String> addresses = new ArrayList<>();
        for (int i = 0; i < rawCount; i++) {
            String address = buf.readUtf(64);
            if (i < CobblemonHubBlockEntity.MAX_ADDRESS_PRESETS) addresses.add(address);
        }
        return new Snapshot(stationAddress, List.copyOf(addresses), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(),
                readEntries(buf, 15), readEntries(buf, 6), readTasks(buf),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public void refreshFromHub() {
        refreshFromHub(boxIndex, pageIndex, false);
    }

    private void refreshFromHub(int requestedBox, int requestedPage, boolean forceSync) {
        if (serverPlayer == null || !stillValid(serverPlayer)
                || !(serverPlayer.level().getBlockEntity(pos) instanceof CobblemonHubBlockEntity hub)) return;
        // Compare against the previous page before changing its indices. Two empty
        // pages have equal contents but still require a new page number on the client.
        Snapshot fresh = new HubMenu(0, serverPlayer.getInventory(), hub, requestedBox, requestedPage).snapshot();
        Snapshot previous = snapshot();
        boxIndex = fresh.boxIndex();
        pageIndex = fresh.pageIndex();
        if (forceSync || !fresh.equals(previous)) {
            apply(fresh);
            HubNetwork.sendState(serverPlayer, this);
        }
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (serverPlayer != null && serverPlayer.getServer().getTickCount() % 20 == 0) {
            refreshFromHub();
        }
    }

    private static List<Entry> pcEntries(ServerPlayer player, int boxIndex, int pageIndex) {
        var pc = Cobblemon.INSTANCE.getStorage().getPC(player.getUUID(), player.registryAccess());
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            Pokemon pokemon = pc.get(new PCPosition(boxIndex, pageIndex * 15 + i));
            entries.add(pokemon == null ? new Entry(new UUID(0, 0), "", 0, false, false, false, true,
                    ResourceLocation.parse("cobblemon:eevee"), Set.of())
                    : new Entry(pokemon.getUuid(), pokemon.getDisplayName(false).getString(),
                    pokemon.getLevel(), false, false, false, true,
                    pokemon.getSpecies().getResourceIdentifier(), Set.copyOf(pokemon.getAspects())));
        }
        return entries;
    }

    private static List<Entry> workerEntries(ServerPlayer player, CobblemonHubBlockEntity hub) {
        List<Entry> entries = new ArrayList<>();
        var storage = Cobblemon.INSTANCE.getStorage().getPC(hub.storageUuid(), player.registryAccess());
        var signals = hub.createVisualSnapshot().workers();
        int signalIndex = 0;
        for (WorkerLease worker : hub.workers()) {
            Pokemon pokemon = storage.get(worker.pokemonUuid());
            var signal = signals.get(signalIndex++);
            entries.add(new Entry(worker.pokemonUuid(), pokemon == null ? worker.species().getPath()
                    : pokemon.getDisplayName(false).getString(), pokemon == null ? 0 : pokemon.getLevel(),
                    signal.busy(), signal.paused(), signal.returning(), worker.ownerUuid().equals(player.getUUID()), worker.species(),
                    pokemon == null ? Set.of() : Set.copyOf(pokemon.getAspects()),
                    CarrierRuntimeLease.workerProgress(worker.pokemonUuid())));
        }
        return entries;
    }

    private static void writeEntries(RegistryFriendlyByteBuf buf, List<Entry> entries) {
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) Entry.write(buf, entry);
    }

    private static List<Entry> readEntries(RegistryFriendlyByteBuf buf, int limit) {
        int rawCount = buf.readVarInt();
        if (rawCount < 0 || rawCount > 256) {
            throw new IllegalArgumentException("Invalid Hub entry count " + rawCount);
        }
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < rawCount; i++) {
            Entry entry = Entry.read(buf);
            if (i < limit) result.add(entry);
        }
        return List.copyOf(result);
    }

    private static void writeTasks(RegistryFriendlyByteBuf buf, List<TaskEntry> entries) {
        buf.writeVarInt(entries.size());
        for (TaskEntry entry : entries) TaskEntry.write(buf, entry);
    }

    private static List<TaskEntry> readTasks(RegistryFriendlyByteBuf buf) {
        int rawCount = buf.readVarInt();
        if (rawCount < 0 || rawCount > 256) {
            throw new IllegalArgumentException("Invalid Hub task count " + rawCount);
        }
        List<TaskEntry> result = new ArrayList<>();
        for (int i = 0; i < rawCount; i++) {
            TaskEntry entry = TaskEntry.read(buf);
            if (i < CobblemonHubBlockEntity.MAX_PACKAGES) result.add(entry);
        }
        return List.copyOf(result);
    }

    private static List<TaskEntry> taskEntries(ServerPlayer player, CobblemonHubBlockEntity hub) {
        List<TaskEntry> entries = new ArrayList<>();
        for (DeliveryTask task : hub.trackedTasks()) {
            String target = PackageItem.getAddress(task.packageStack());
            if (target.isBlank()) target = hub.defaultAddress();
            if (task.targetDimension().equals(player.level().dimension())
                    && player.level().hasChunkAt(task.targetPos())
                    && player.level().getBlockEntity(task.targetPos()) instanceof CobblemonHubBlockEntity targetHub
                    && !targetHub.address().isBlank()) {
                target = targetHub.address();
            } else if (!task.targetDimension().equals(player.level().dimension())) {
                target = task.targetDimension().location().toString() + " @ "
                        + task.targetPos().getX() + "," + task.targetPos().getZ();
            }
            String courierName = "";
            if (task.worker() != null) {
                courierName = task.worker().species().getPath();
                try {
                    var pc = Cobblemon.INSTANCE.getStorage().getPC(
                            task.worker().storageUuid(), player.registryAccess());
                    Pokemon courier = pc.get(task.worker().pokemonUuid());
                    if (courier != null) courierName = courier.getDisplayName(false).getString();
                } catch (RuntimeException ignored) {
                    // The saved worker identity remains available while PC storage is reloading.
                }
            }
            boolean reclaimable = hub.isIncomingTask(task) || task.state() == DeliveryState.PAUSED
                    || task.state() == DeliveryState.BUFFERED || task.state() == DeliveryState.CLAIMED_BY_WORKER;
            boolean remoteTransit = task.mode() == dev.cobblemoncreate.logistics.TransportMode.VIRTUAL
                    && task.state() == DeliveryState.IN_TRANSIT
                    && (!task.sourceDimension().equals(task.targetDimension())
                    || dev.cobblemoncreate.logistics.cobblemon.CarrierRuntimeLease.isRemoteGapActive(task.id()));
            entries.add(new TaskEntry(task.id(), target, task.state(), task.pauseReason(),
                    BuiltInRegistries.ITEM.getKey(task.packageStack().getItem()), courierName,
                    remoteTransit, task.hasCourierDestination(), reclaimable, taskProgress(player, task)));
        }
        return List.copyOf(entries);
    }

    static JourneyProgress taskProgress(ServerPlayer player, DeliveryTask task) {
        if (task.state() == DeliveryState.ARRIVED_BUFFERED) {
            return new JourneyProgress("arrived", 100, 0, 0, 0, task.targetPos());
        }
        if (task.state() != DeliveryState.IN_TRANSIT && task.state() != DeliveryState.CLAIMED_BY_WORKER) {
            return JourneyProgress.NONE;
        }
        if (task.sourceDimension().equals(task.targetDimension())) return CarrierRuntimeLease.progress(task.id());
        long elapsed = Math.max(0L, player.getServer().getTickCount() - task.lastProgressGameTime());
        var courierLevel = task.worker() == null ? null : player.getServer().getLevel(task.worker().dimension());
        CarrierProfile profile = courierLevel == null ? null : CarrierProfiles.resolve(courierLevel, task.worker());
        long duration = (long) Math.ceil(200.0D / (profile == null ? 1.0D : profile.speedMultiplier()));
        var targetLevel = player.getServer().getLevel(task.targetDimension());
        boolean available = targetLevel != null && targetLevel.hasChunkAt(task.targetPos());
        return new JourneyProgress(available ? "gap" : "waiting_endpoint",
                Math.clamp((int) (elapsed * 100L / duration), 0, 99),
                available ? (int) Math.max(1L, (duration - elapsed + 19L) / 20L) : -1,
                (int) (elapsed / 20L), 0, task.sourcePos());
    }

    public BlockPos pos() { return pos; }
    public String address() { return stationAddress; }
    public String stationAddress() { return stationAddress; }
    public List<String> addresses() { return addresses; }
    public int defaultDestination() { return defaultDestination; }
    public List<Entry> pcEntries() { return pcEntries; }
    public int boxIndex() { return boxIndex; }
    public int pageIndex() { return pageIndex; }
    public int boxCount() { return boxCount; }
    public List<Entry> workers() { return workers; }
    public List<TaskEntry> tasks() { return tasks; }
    public int buffered() { return buffered; }
    public int arriving() { return arriving; }
    public int reclaimable() { return reclaimable; }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64
                && player.level().getBlockEntity(pos) instanceof CobblemonHubBlockEntity;
    }

    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (!(player instanceof ServerPlayer serverPlayer) || !stillValid(player)
                || !(player.level().getBlockEntity(pos) instanceof CobblemonHubBlockEntity hub)) return false;
        int nextBox = boxIndex;
        int nextPage = pageIndex;
        if (button >= 30 && button < 45) {
            if (hub.workers().size() < CobblemonHubBlockEntity.MAX_WORKERS) {
                WorkerLease worker = ResidentWorkerRuntime.bindFromPc(serverPlayer, pos,
                        hub.storageUuid(), new PCPosition(boxIndex, pageIndex * 15 + button - 30));
                if (worker != null) {
                    hub.assignWorker(worker);
                    serverPlayer.displayClientMessage(Component.translatable(
                            "message.cobblemon_create_logistics.worker_bound", hub.workers().size()), true);
                } else {
                    serverPlayer.displayClientMessage(Component.translatable(
                            "message.cobblemon_create_logistics.worker_bind_failed"), true);
                }
            } else {
                serverPlayer.displayClientMessage(Component.translatable(
                        "message.cobblemon_create_logistics.worker_bind_failed"), true);
            }
        } else if (button == 60) {
            nextBox = Math.floorMod(boxIndex - 1, boxCount);
            nextPage = 0;
        } else if (button == 61) {
            nextBox = Math.floorMod(boxIndex + 1, boxCount);
            nextPage = 0;
        } else if (button == 62) {
            nextPage = Math.floorMod(pageIndex - 1, 2);
        } else if (button == 63) {
            nextPage = Math.floorMod(pageIndex + 1, 2);
        } else if (button >= 10 && button < 16) {
            int index = button - 10;
            if (index < hub.workers().size()) {
                boolean removed = hub.removeWorker(hub.workers().get(index).pokemonUuid(), player.getUUID());
                serverPlayer.displayClientMessage(Component.translatable(removed
                        ? "message.cobblemon_create_logistics.worker_removed"
                        : "message.cobblemon_create_logistics.worker_busy"), true);
            }
        } else if (button == 20) {
            ItemStack stack = hub.collectAvailablePackage();
            if (!stack.isEmpty() && !player.addItem(stack)) player.drop(stack, false);
            serverPlayer.displayClientMessage(Component.translatable(stack.isEmpty()
                    ? "message.cobblemon_create_logistics.hub_empty"
                    : "message.cobblemon_create_logistics.package_collected"), true);
        } else if (button >= 800 && button < 800 + CobblemonHubBlockEntity.MAX_ADDRESS_PRESETS) {
            hub.removeAddress(button - 800);
        } else if (button >= 900 && button < 900 + CobblemonHubBlockEntity.MAX_ADDRESS_PRESETS) {
            hub.setDefaultDestination(button - 900);
        } else if (button != 21) return false;
        // Navigation always acknowledges the request, including a one-box wrap.
        refreshFromHub(nextBox, nextPage, button >= 60 && button <= 63);
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
}
