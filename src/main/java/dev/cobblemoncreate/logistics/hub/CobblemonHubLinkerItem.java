package dev.cobblemoncreate.logistics.hub;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import dev.cobblemoncreate.logistics.create.CobblemonHubTarget;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/** Handheld station directory and Create package-port configuration. */
public final class CobblemonHubLinkerItem extends Item {
    private static final int MAX_STATIONS = 16;
    private static final String STATIONS = "CobblemonLogisticsStations";
    private static final String ACTIVE = "CobblemonLogisticsActiveStation";
    private static final String OLD_BINDING = "CobblemonLogisticsStation";
    private static final String DIMENSION = "Dimension";
    private static final String POS = "Pos";
    private static final String LEGACY_DIMENSION = "CobblemonLogisticsHubDimension";
    private static final String LEGACY_POS = "CobblemonLogisticsHubPos";

    public CobblemonHubLinkerItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines,
                                TooltipFlag flag) {
        TerminalData data = readData(tag(stack));
        if (data.stations().isEmpty()) {
            lines.add(Component.translatable("item.cobblemon_create_logistics.terminal_unbound")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        Binding selected = data.selected();
        lines.add(Component.translatable("item.cobblemon_create_logistics.terminal_bound",
                data.stations().size(), selected.dimension().toString(), selected.pos().toShortString())
                .withStyle(ChatFormatting.GRAY));
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        BlockEntity clicked = context.getLevel().getBlockEntity(context.getClickedPos());
        return clicked instanceof CobblemonHubBlockEntity || clicked instanceof PackagePortBlockEntity
                ? useOn(context) : InteractionResult.PASS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        BlockPos clicked = context.getClickedPos();
        if (level.getBlockEntity(clicked) instanceof CobblemonHubBlockEntity) {
            if (level.isClientSide()) return InteractionResult.SUCCESS;
            ItemStack stack = context.getItemInHand();
            TerminalData data = terminal(stack, player);
            Binding station = new Binding(level.dimension().location(), clicked);
            int index = data.stations().indexOf(station);
            if (player.isShiftKeyDown()) {
                if (index < 0) {
                    message(player, "terminal_not_registered");
                    return InteractionResult.FAIL;
                }
                List<Binding> stations = new ArrayList<>(data.stations());
                stations.remove(index);
                int active = data.active() > index ? data.active() - 1
                        : Math.min(data.active(), stations.size() - 1);
                save(stack, new TerminalData(stations, Math.max(0, active)));
                message(player, "terminal_removed");
                return InteractionResult.SUCCESS;
            }
            if (index < 0 && data.stations().size() >= MAX_STATIONS) {
                message(player, "terminal_full");
                return InteractionResult.FAIL;
            }
            List<Binding> stations = new ArrayList<>(data.stations());
            if (index < 0) {
                stations.add(station);
                index = stations.size() - 1;
            }
            save(stack, new TerminalData(stations, index));
            player.displayClientMessage(Component.translatable(
                    "message.cobblemon_create_logistics.terminal_bound", clicked.toShortString()), true);
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(clicked) instanceof PackagePortBlockEntity port)) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        Binding selected = terminal(context.getItemInHand(), player).selected();
        if (selected == null) {
            message(player, "terminal_unbound");
            return InteractionResult.FAIL;
        }
        if (!level.dimension().location().equals(selected.dimension())) {
            message(player, "terminal_wrong_dimension");
            return InteractionResult.FAIL;
        }
        if (!level.hasChunkAt(selected.pos())) {
            message(player, "terminal_station_unloaded");
            return InteractionResult.FAIL;
        }
        if (!(level.getBlockEntity(selected.pos()) instanceof CobblemonHubBlockEntity)) {
            message(player, "terminal_station_missing");
            return InteractionResult.FAIL;
        }

        if (port.target != null) port.target.deregister(port, level, clicked);
        CobblemonHubTarget target = new CobblemonHubTarget(selected.pos().subtract(clicked));
        target.setup(port, level, clicked);
        port.target = target;
        target.register(port, level, clicked);
        ((BlockEntity) port).setChanged();
        level.sendBlockUpdated(clicked, level.getBlockState(clicked), level.getBlockState(clicked), 3);
        message(player, "hub_linked");
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) return InteractionResultHolder.success(stack);
        TerminalData data = terminal(stack, player);
        if (data.stations().isEmpty()) {
            message(player, "terminal_unbound");
            return InteractionResultHolder.success(stack);
        }
        if (player.isShiftKeyDown()) {
            data = new TerminalData(data.stations(), (data.active() + 1) % data.stations().size());
            save(stack, data);
            Binding selected = data.selected();
            player.displayClientMessage(selectedStationMessage(data.active() + 1, data.stations().size(),
                    selected.dimension(), selected.pos()), true);
            return InteractionResultHolder.success(stack);
        }

        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(new MenuProvider() {
                @Override
                public Component getDisplayName() {
                    return Component.translatable("item.cobblemon_create_logistics.cobblemon_hub_linker");
                }

                @Override
                public AbstractContainerMenu createMenu(int id, Inventory inventory, Player menuPlayer) {
                    return new TerminalMenu(id, inventory, serverPlayer, stack);
                }
            }, buf -> TerminalMenu.writeOpeningData(buf, serverPlayer, stack));
        }
        return InteractionResultHolder.success(stack);
    }

    static Component selectedStationMessage(int index, int total, ResourceLocation dimension, BlockPos pos) {
        return Component.translatable("message.cobblemon_create_logistics.terminal_selected",
                index, total, dimension.toString(), pos.toShortString());
    }

    static Component stationEntryMessage(boolean active, int index, ResourceLocation dimension,
                                         BlockPos pos, int[] counts) {
        return Component.translatable("message.cobblemon_create_logistics.terminal_station_entry",
                active ? "*" : " ", index, dimension.toString(), pos.toShortString(),
                counts[0], counts[1], counts[2]);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target,
                                                  InteractionHand hand) {
        if (!(target instanceof PokemonEntity pokemonEntity) || !player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (player.level().isClientSide()) return InteractionResult.SUCCESS;
        Binding selected = terminal(stack, player).selected();
        if (selected == null || !player.level().dimension().location().equals(selected.dimension())
                || !player.level().hasChunkAt(selected.pos())
                || !(player.level().getBlockEntity(selected.pos()) instanceof CobblemonHubBlockEntity hub)) {
            return InteractionResult.FAIL;
        }
        if (!hub.hasWorker(pokemonEntity.getPokemon().getUuid())) return InteractionResult.PASS;
        if (!player.getUUID().equals(pokemonEntity.getPokemon().getOwnerUUID())) {
            message(player, "worker_not_owner");
            return InteractionResult.FAIL;
        }
        boolean removed = hub.removeWorker(pokemonEntity.getPokemon().getUuid(), player.getUUID());
        message(player, removed ? "worker_removed" : "worker_busy");
        return removed ? InteractionResult.SUCCESS : InteractionResult.FAIL;
    }

    private static void message(Player player, String key) {
        player.displayClientMessage(Component.translatable("message.cobblemon_create_logistics." + key), true);
    }

    private static CompoundTag tag(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    static TerminalData terminal(ItemStack stack, Player player) {
        CompoundTag data = tag(stack);
        if (!data.contains(STATIONS, Tag.TAG_LIST)) {
            Binding old = data.contains(OLD_BINDING, Tag.TAG_COMPOUND)
                    ? readBinding(data.getCompound(OLD_BINDING)) : null;
            CompoundTag legacy = player.getPersistentData();
            if (old == null && legacy.contains(LEGACY_DIMENSION, Tag.TAG_STRING)
                    && legacy.contains(LEGACY_POS, Tag.TAG_COMPOUND)) {
                CompoundTag previous = new CompoundTag();
                previous.putString(DIMENSION, legacy.getString(LEGACY_DIMENSION));
                previous.put(POS, legacy.getCompound(LEGACY_POS).copy());
                old = readBinding(previous);
            }
            if (old != null) save(stack, new TerminalData(List.of(old), 0));
            clearLegacy(player);
        }
        return readData(tag(stack));
    }

    private static TerminalData readData(CompoundTag data) {
        List<Binding> stations = new ArrayList<>();
        if (data.contains(STATIONS, Tag.TAG_LIST)) {
            ListTag entries = data.getList(STATIONS, Tag.TAG_COMPOUND);
            for (int i = 0; i < entries.size() && stations.size() < MAX_STATIONS; i++) {
                Binding station = readBinding(entries.getCompound(i));
                if (station != null && !stations.contains(station)) stations.add(station);
            }
        } else if (data.contains(OLD_BINDING, Tag.TAG_COMPOUND)) {
            Binding old = readBinding(data.getCompound(OLD_BINDING));
            if (old != null) stations.add(old);
        }
        int active = stations.isEmpty() ? 0 : Math.clamp(data.getInt(ACTIVE), 0, stations.size() - 1);
        return new TerminalData(List.copyOf(stations), active);
    }

    private static Binding readBinding(CompoundTag data) {
        ResourceLocation dimension = ResourceLocation.tryParse(data.getString(DIMENSION));
        BlockPos pos = NbtUtils.readBlockPos(data, POS).orElse(null);
        return dimension == null || pos == null ? null : new Binding(dimension, pos);
    }

    private static void save(ItemStack stack, TerminalData terminal) {
        CompoundTag data = tag(stack);
        ListTag stations = new ListTag();
        for (Binding station : terminal.stations()) {
            CompoundTag entry = new CompoundTag();
            entry.putString(DIMENSION, station.dimension().toString());
            entry.put(POS, NbtUtils.writeBlockPos(station.pos()));
            stations.add(entry);
        }
        data.put(STATIONS, stations);
        data.putInt(ACTIVE, terminal.active());
        data.remove(OLD_BINDING);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    private static void clearLegacy(Player player) {
        player.getPersistentData().remove(LEGACY_DIMENSION);
        player.getPersistentData().remove(LEGACY_POS);
    }

    public record Binding(ResourceLocation dimension, BlockPos pos) {}

    record TerminalData(List<Binding> stations, int active) {
        Binding selected() { return stations.isEmpty() ? null : stations.get(active); }
    }
}
