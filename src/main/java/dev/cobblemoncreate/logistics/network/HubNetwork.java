package dev.cobblemoncreate.logistics.network;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.cobblemoncreate.logistics.CobblemonCreateLogistics;
import dev.cobblemoncreate.logistics.DeliveryTask;
import dev.cobblemoncreate.logistics.DeliveryTaskSavedData;
import dev.cobblemoncreate.logistics.hub.CobblemonHubBlockEntity;
import dev.cobblemoncreate.logistics.hub.HubMenu;
import dev.cobblemoncreate.logistics.hub.TerminalMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.UUID;
import java.util.List;

public final class HubNetwork {
    private HubNetwork() {}

    public record AddAddress(BlockPos pos, String address) implements CustomPacketPayload {
        public static final Type<AddAddress> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CobblemonCreateLogistics.MOD_ID, "add_hub_address"));
        public static final StreamCodec<net.minecraft.network.FriendlyByteBuf, AddAddress> CODEC =
                StreamCodec.composite(BlockPos.STREAM_CODEC, AddAddress::pos,
                        ByteBufCodecs.stringUtf8(64), AddAddress::address, AddAddress::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SetStationAddress(BlockPos pos, String address) implements CustomPacketPayload {
        public static final Type<SetStationAddress> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CobblemonCreateLogistics.MOD_ID, "set_hub_address"));
        public static final StreamCodec<net.minecraft.network.FriendlyByteBuf, SetStationAddress> CODEC =
                StreamCodec.composite(BlockPos.STREAM_CODEC, SetStationAddress::pos,
                        ByteBufCodecs.stringUtf8(64), SetStationAddress::address, SetStationAddress::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record HighlightCourier(BlockPos pos, UUID taskId) implements CustomPacketPayload {
        public static final Type<HighlightCourier> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CobblemonCreateLogistics.MOD_ID, "highlight_courier"));
        public static final StreamCodec<net.minecraft.network.FriendlyByteBuf, HighlightCourier> CODEC =
                new StreamCodec<>() {
                    @Override
                    public HighlightCourier decode(net.minecraft.network.FriendlyByteBuf buffer) {
                        return new HighlightCourier(buffer.readBlockPos(), buffer.readUUID());
                    }

                    @Override
                    public void encode(net.minecraft.network.FriendlyByteBuf buffer, HighlightCourier packet) {
                        buffer.writeBlockPos(packet.pos());
                        buffer.writeUUID(packet.taskId());
                    }
                };

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record CollectPackage(BlockPos pos, UUID taskId) implements CustomPacketPayload {
        public static final Type<CollectPackage> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CobblemonCreateLogistics.MOD_ID, "collect_hub_package"));
        public static final StreamCodec<net.minecraft.network.FriendlyByteBuf, CollectPackage> CODEC =
                new StreamCodec<>() {
                    @Override
                    public CollectPackage decode(net.minecraft.network.FriendlyByteBuf buffer) {
                        return new CollectPackage(buffer.readBlockPos(), buffer.readUUID());
                    }

                    @Override
                    public void encode(net.minecraft.network.FriendlyByteBuf buffer, CollectPackage packet) {
                        buffer.writeBlockPos(packet.pos());
                        buffer.writeUUID(packet.taskId());
                    }
                };

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SyncState(BlockPos pos, HubMenu.Snapshot snapshot) implements CustomPacketPayload {
        public static final Type<SyncState> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CobblemonCreateLogistics.MOD_ID, "sync_hub_state"));
        public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, SyncState> CODEC =
                new StreamCodec<>() {
                    @Override
                    public SyncState decode(net.minecraft.network.RegistryFriendlyByteBuf buffer) {
                        return new SyncState(BlockPos.STREAM_CODEC.decode(buffer), HubMenu.readSnapshot(buffer));
                    }

                    @Override
                    public void encode(net.minecraft.network.RegistryFriendlyByteBuf buffer, SyncState value) {
                        BlockPos.STREAM_CODEC.encode(buffer, value.pos());
                        HubMenu.writeSnapshot(buffer, value.snapshot());
                    }
                };

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SyncTerminalState(int containerId, List<TerminalMenu.Station> stations) implements CustomPacketPayload {
        public static final Type<SyncTerminalState> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CobblemonCreateLogistics.MOD_ID, "sync_terminal_state"));
        public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, SyncTerminalState> CODEC =
                new StreamCodec<>() {
                    @Override
                    public SyncTerminalState decode(net.minecraft.network.RegistryFriendlyByteBuf buffer) {
                        return new SyncTerminalState(buffer.readVarInt(), TerminalMenu.readStations(buffer));
                    }

                    @Override
                    public void encode(net.minecraft.network.RegistryFriendlyByteBuf buffer, SyncTerminalState value) {
                        buffer.writeVarInt(value.containerId());
                        TerminalMenu.writeStations(buffer, value.stations());
                    }
                };

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void sendState(ServerPlayer player, HubMenu menu) {
        PacketDistributor.sendToPlayer(player, new SyncState(menu.pos(), menu.snapshot()));
    }

    public static void sendTerminalState(ServerPlayer player, TerminalMenu menu) {
        PacketDistributor.sendToPlayer(player, new SyncTerminalState(menu.containerId, menu.stations()));
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("4");
        HubWorldNetwork.register(registrar);
        registrar.playToServer(AddAddress.TYPE, AddAddress.CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player)
                            || !(player.containerMenu instanceof HubMenu menu)
                            || !menu.pos().equals(packet.pos()) || !menu.stillValid(player)
                            || packet.address().length() > 64
                            || !(player.level().getBlockEntity(packet.pos()) instanceof CobblemonHubBlockEntity hub)) {
                        return;
                    }
                    if (!hub.addAddress(packet.address())) {
                        player.displayClientMessage(Component.translatable(
                                "gui.cobblemon_create_logistics.address_full"), true);
                    }
                    menu.refreshFromHub();
                }));
        registrar.playToServer(SetStationAddress.TYPE, SetStationAddress.CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player)
                            || !(player.containerMenu instanceof HubMenu menu)
                            || !menu.pos().equals(packet.pos()) || !menu.stillValid(player)
                            || packet.address().length() > 64
                            || !(player.level().getBlockEntity(packet.pos()) instanceof CobblemonHubBlockEntity hub)) {
                        return;
                    }
                    hub.setStationAddress(packet.address());
                    menu.refreshFromHub();
                }));
        registrar.playToServer(HighlightCourier.TYPE, HighlightCourier.CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player)
                            || !(player.containerMenu instanceof HubMenu menu)
                            || !menu.pos().equals(packet.pos()) || !menu.stillValid(player)
                            || !(player.level().getBlockEntity(packet.pos()) instanceof CobblemonHubBlockEntity hub)) {
                        return;
                    }
                    DeliveryTask task = DeliveryTaskSavedData.get(player.getServer()).get(packet.taskId());
                    if (task == null || task.state().terminal() || !hub.ownsTask(task)) return;
                    if (task.worker() == null) {
                        player.displayClientMessage(Component.translatable(
                                "message.cobblemon_create_logistics.courier_unassigned"), true);
                        return;
                    }
                    if (dev.cobblemoncreate.logistics.cobblemon.CarrierRuntimeLease.isRemoteGapActive(task.id())) {
                        player.displayClientMessage(Component.translatable(
                                "message.cobblemon_create_logistics.courier_out_of_sight"), true);
                        return;
                    }
                    var level = player.getServer().getLevel(task.worker().dimension());
                    PokemonEntity entity = null;
                    if (level != null) {
                        try {
                            var pc = Cobblemon.INSTANCE.getStorage().getPC(
                                    task.worker().storageUuid(), level.registryAccess());
                            var pokemon = pc.get(task.worker().pokemonUuid());
                            entity = pokemon == null ? null : pokemon.getEntity();
                        } catch (RuntimeException ignored) {
                            // Storage can be temporarily unavailable during a reload.
                        }
                    }
                    if (entity == null || !entity.isAlive() || entity.level() != level
                            || level.getEntity(entity.getId()) != entity) {
                        player.displayClientMessage(Component.translatable(
                                "message.cobblemon_create_logistics.courier_out_of_sight"), true);
                        return;
                    }
                    entity.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0, false, false, false));
                    player.displayClientMessage(Component.translatable(
                            "message.cobblemon_create_logistics.courier_highlighted"), true);
                }));
        registrar.playToServer(CollectPackage.TYPE, CollectPackage.CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player)
                            || !(player.containerMenu instanceof HubMenu menu)
                            || !menu.pos().equals(packet.pos()) || !menu.stillValid(player)
                            || !(player.level().getBlockEntity(packet.pos()) instanceof CobblemonHubBlockEntity hub)) {
                        return;
                    }
                    var stack = hub.collectPackage(packet.taskId());
                    if (!stack.isEmpty() && !player.addItem(stack)) player.drop(stack, false);
                    player.displayClientMessage(Component.translatable(stack.isEmpty()
                            ? "message.cobblemon_create_logistics.hub_empty"
                            : "message.cobblemon_create_logistics.package_collected"), true);
                    menu.refreshFromHub();
                }));
        registrar.playToClient(SyncState.TYPE, SyncState.CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    if (net.minecraft.client.Minecraft.getInstance().screen instanceof
                            dev.cobblemoncreate.logistics.client.HubScreen screen
                            && screen.getMenu().pos().equals(packet.pos())) {
                        screen.acceptSnapshot(packet.snapshot());
                    }
                }));
        registrar.playToClient(SyncTerminalState.TYPE, SyncTerminalState.CODEC, (packet, context) ->
                context.enqueueWork(() -> {
                    if (net.minecraft.client.Minecraft.getInstance().screen instanceof
                            dev.cobblemoncreate.logistics.client.TerminalScreen screen
                            && screen.getMenu().containerId == packet.containerId()) {
                        screen.getMenu().apply(packet.stations());
                    }
                }));
    }
}
