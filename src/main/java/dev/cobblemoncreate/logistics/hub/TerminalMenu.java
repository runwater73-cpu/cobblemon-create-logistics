package dev.cobblemoncreate.logistics.hub;

import dev.cobblemoncreate.logistics.DeliveryState;
import dev.cobblemoncreate.logistics.DeliveryTask;
import dev.cobblemoncreate.logistics.DeliveryTaskSavedData;
import dev.cobblemoncreate.logistics.JourneyProgress;
import dev.cobblemoncreate.logistics.registry.LogisticsMenus;
import dev.cobblemoncreate.logistics.network.HubNetwork;
import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/** Read-only remote directory opened by the handheld logistics terminal. */
public final class TerminalMenu extends AbstractContainerMenu {
    public record Parcel(String address, DeliveryState state, JourneyProgress progress) {
        private static void write(RegistryFriendlyByteBuf buf, Parcel parcel) {
            buf.writeUtf(parcel.address(), 64);
            buf.writeVarInt(parcel.state().ordinal());
            parcel.progress().write(buf);
        }

        private static Parcel read(RegistryFriendlyByteBuf buf) {
            String address = buf.readUtf(64);
            int ordinal = buf.readVarInt();
            DeliveryState[] states = DeliveryState.values();
            DeliveryState state = ordinal >= 0 && ordinal < states.length ? states[ordinal] : DeliveryState.PAUSED;
            return new Parcel(address, state, JourneyProgress.read(buf));
        }
    }

    public record Station(CobblemonHubLinkerItem.Binding binding, boolean loaded,
                          int queued, int transit, int ready, List<Parcel> parcels) {
        private static void write(RegistryFriendlyByteBuf buf, Station station) {
            buf.writeResourceLocation(station.binding().dimension());
            buf.writeBlockPos(station.binding().pos());
            buf.writeBoolean(station.loaded());
            buf.writeVarInt(station.queued());
            buf.writeVarInt(station.transit());
            buf.writeVarInt(station.ready());
            buf.writeVarInt(station.parcels().size());
            for (Parcel parcel : station.parcels()) Parcel.write(buf, parcel);
        }

        private static Station read(RegistryFriendlyByteBuf buf) {
            CobblemonHubLinkerItem.Binding binding = new CobblemonHubLinkerItem.Binding(
                    buf.readResourceLocation(), buf.readBlockPos());
            boolean loaded = buf.readBoolean();
            int queued = Math.max(0, buf.readVarInt());
            int transit = Math.max(0, buf.readVarInt());
            int ready = Math.max(0, buf.readVarInt());
            int count = buf.readVarInt();
            if (count < 0 || count > 32) throw new IllegalArgumentException("Invalid terminal parcel count " + count);
            List<Parcel> parcels = new ArrayList<>();
            for (int i = 0; i < count; i++) parcels.add(Parcel.read(buf));
            return new Station(binding,
                    loaded, queued, transit, ready, List.copyOf(parcels));
        }
    }

    private List<Station> stations;
    private final int active;
    private final ServerPlayer serverPlayer;
    private final ItemStack terminal;

    public TerminalMenu(int id, Inventory inventory, RegistryFriendlyByteBuf data) {
        super(LogisticsMenus.TERMINAL.get(), id);
        stations = readStations(data);
        active = stations.isEmpty() ? 0 : Math.clamp(data.readVarInt(), 0, stations.size() - 1);
        serverPlayer = null;
        terminal = ItemStack.EMPTY;
    }

    public TerminalMenu(int id, Inventory inventory, ServerPlayer player, ItemStack terminal) {
        super(LogisticsMenus.TERMINAL.get(), id);
        this.serverPlayer = player;
        CobblemonHubLinkerItem.TerminalData data = CobblemonHubLinkerItem.terminal(terminal, player);
        this.terminal = terminal.copy();
        this.active = data.stations().isEmpty() ? 0 : data.active();
        this.stations = snapshot(player, data.stations());
    }

    public static void writeOpeningData(RegistryFriendlyByteBuf buf, ServerPlayer player, ItemStack terminal) {
        CobblemonHubLinkerItem.TerminalData data = CobblemonHubLinkerItem.terminal(terminal, player);
        List<Station> stations = snapshot(player, data.stations());
        writeStations(buf, stations);
        buf.writeVarInt(data.active());
    }

    public static void writeStations(RegistryFriendlyByteBuf buf, List<Station> stations) {
        buf.writeVarInt(stations.size());
        for (Station station : stations) Station.write(buf, station);
    }

    public static List<Station> readStations(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > 16) throw new IllegalArgumentException("Invalid terminal station count " + count);
        List<Station> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) entries.add(Station.read(buf));
        return List.copyOf(entries);
    }

    public void apply(List<Station> updated) {
        stations = List.copyOf(updated);
    }

    private static List<Station> snapshot(ServerPlayer player, List<CobblemonHubLinkerItem.Binding> bindings) {
        List<Station> result = new ArrayList<>();
        DeliveryTaskSavedData saved = DeliveryTaskSavedData.get(player.getServer());
        for (CobblemonHubLinkerItem.Binding binding : bindings) {
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, binding.dimension());
            ServerLevel level = player.getServer().getLevel(dimension);
            boolean loaded = level != null && level.hasChunkAt(binding.pos());
            int queued = 0;
            int transit = 0;
            int ready = 0;
            List<Parcel> parcels = new ArrayList<>();
            for (DeliveryTask task : saved.tasks()) {
                if (task.state().terminal()) continue;
                CobblemonHubLinkerItem.Binding source = new CobblemonHubLinkerItem.Binding(
                        task.sourceDimension().location(), task.sourcePos());
                CobblemonHubLinkerItem.Binding target = new CobblemonHubLinkerItem.Binding(
                        task.targetDimension().location(), task.targetPos());
                if (binding.equals(target) && task.state() == DeliveryState.ARRIVED_BUFFERED) ready++;
                else if (binding.equals(source) && task.state() == DeliveryState.BUFFERED) queued++;
                else if ((binding.equals(source) || binding.equals(target))
                        && task.state() != DeliveryState.ARRIVED_BUFFERED) transit++;
                if ((binding.equals(source) || binding.equals(target)) && parcels.size() < 32) {
                    String address = PackageItem.getAddress(task.packageStack());
                    if (address.isBlank()) address = task.targetDimension().location() + " @ "
                            + task.targetPos().toShortString();
                    JourneyProgress progress = HubMenu.taskProgress(player, task);
                    parcels.add(new Parcel(address, task.state(), progress));
                }
            }
            result.add(new Station(binding, loaded, queued, transit, ready, List.copyOf(parcels)));
        }
        return List.copyOf(result);
    }

    public List<Station> stations() { return stations; }
    public int active() { return active; }
    public boolean isActive(int index) { return index == active; }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (serverPlayer != null && serverPlayer.getServer().getTickCount() % 20 == 0 && stillValid(serverPlayer)) {
            List<Station> fresh = snapshot(serverPlayer,
                    CobblemonHubLinkerItem.terminal(terminal, serverPlayer).stations());
            if (!fresh.equals(stations)) {
                stations = fresh;
                HubNetwork.sendTerminalState(serverPlayer, this);
            }
        }
    }

    @Override
    public boolean stillValid(Player player) {
        if (serverPlayer == null) return true;
        return hasTerminal(player, terminal);
    }

    private static boolean hasTerminal(Player player, ItemStack expected) {
        return containsTerminal(player.getMainHandItem(), expected) || containsTerminal(player.getOffhandItem(), expected);
    }

    private static boolean containsTerminal(ItemStack actual, ItemStack expected) {
        return !actual.isEmpty() && actual.is(expected.getItem())
                && ItemStack.isSameItemSameComponents(actual, expected);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
}
