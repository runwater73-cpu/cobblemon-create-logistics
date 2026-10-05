package dev.cobblemoncreate.logistics.network;

import dev.cobblemoncreate.logistics.CobblemonCreateLogistics;
import dev.cobblemoncreate.logistics.hub.CobblemonHubBlockEntity;
import dev.cobblemoncreate.logistics.hub.HubVisualState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.UUID;

/** World observers receive six worker signals and individual committed transfers, never the task queue. */
public final class HubWorldNetwork {
    private HubWorldNetwork() {}

    public record State(ResourceLocation dimension, BlockPos pos, HubVisualState.Snapshot snapshot) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(id("hub_visual_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
            @Override public State decode(RegistryFriendlyByteBuf buf) {
                return new State(buf.readResourceLocation(), buf.readBlockPos(), HubVisualState.Snapshot.read(buf));
            }
            @Override public void encode(RegistryFriendlyByteBuf buf, State value) {
                buf.writeResourceLocation(value.dimension());
                buf.writeBlockPos(value.pos());
                value.snapshot().write(buf);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Transfer(ResourceLocation dimension, BlockPos pos, HubVisualState.Snapshot snapshot,
                           HubVisualState.TransferEvent event) implements CustomPacketPayload {
        public static final Type<Transfer> TYPE = new Type<>(id("hub_visual_transfer"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Transfer> CODEC = new StreamCodec<>() {
            @Override public Transfer decode(RegistryFriendlyByteBuf buf) {
                return new Transfer(buf.readResourceLocation(), buf.readBlockPos(),
                        HubVisualState.Snapshot.read(buf), HubVisualState.TransferEvent.read(buf));
            }
            @Override public void encode(RegistryFriendlyByteBuf buf, Transfer value) {
                buf.writeResourceLocation(value.dimension());
                buf.writeBlockPos(value.pos());
                value.snapshot().write(buf);
                value.event().write(buf);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ClearTask(ResourceLocation dimension, BlockPos pos, UUID instanceId, UUID taskId) implements CustomPacketPayload {
        public static final Type<ClearTask> TYPE = new Type<>(id("hub_visual_clear_task"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ClearTask> CODEC = new StreamCodec<>() {
            @Override public ClearTask decode(RegistryFriendlyByteBuf buf) {
                return new ClearTask(buf.readResourceLocation(), buf.readBlockPos(), buf.readUUID(), buf.readUUID());
            }
            @Override public void encode(RegistryFriendlyByteBuf buf, ClearTask value) {
                buf.writeResourceLocation(value.dimension());
                buf.writeBlockPos(value.pos());
                buf.writeUUID(value.instanceId());
                buf.writeUUID(value.taskId());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CobblemonCreateLogistics.MOD_ID, path);
    }

    private static CobblemonHubBlockEntity clientHub(Level level, ResourceLocation dimension, BlockPos pos) {
        if (level == null || !level.isClientSide() || !level.dimension().location().equals(dimension)
                || !level.hasChunkAt(pos)) return null;
        return level.getBlockEntity(pos) instanceof CobblemonHubBlockEntity hub ? hub : null;
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(State.TYPE, State.CODEC, (packet, context) -> context.enqueueWork(() -> {
            CobblemonHubBlockEntity hub = clientHub(context.player().level(), packet.dimension(), packet.pos());
            if (hub != null) hub.acceptVisualState(packet.snapshot());
        }));
        registrar.playToClient(Transfer.TYPE, Transfer.CODEC, (packet, context) -> context.enqueueWork(() -> {
            CobblemonHubBlockEntity hub = clientHub(context.player().level(), packet.dimension(), packet.pos());
            if (hub != null) {
                hub.acceptVisualState(packet.snapshot());
                hub.acceptVisualEvent(packet.snapshot().instanceId(), packet.event());
            }
        }));
        registrar.playToClient(ClearTask.TYPE, ClearTask.CODEC, (packet, context) -> context.enqueueWork(() -> {
            CobblemonHubBlockEntity hub = clientHub(context.player().level(), packet.dimension(), packet.pos());
            if (hub != null) hub.clearVisualTask(packet.instanceId(), packet.taskId());
        }));
    }

    public static void sendVisualState(ServerLevel level, BlockPos pos, HubVisualState.Snapshot snapshot) {
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(pos), new State(level.dimension().location(), pos, snapshot));
    }

    public static void sendVisualState(ServerPlayer player, ServerLevel level, BlockPos pos, HubVisualState.Snapshot snapshot) {
        PacketDistributor.sendToPlayer(player, new State(level.dimension().location(), pos, snapshot));
    }

    public static void sendVisualEvent(ServerLevel level, BlockPos pos, HubVisualState.Snapshot snapshot, HubVisualState.TransferEvent event) {
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(pos), new Transfer(level.dimension().location(), pos, snapshot, event));
    }

    public static void clearVisualTask(ServerLevel level, BlockPos pos, UUID instanceId, UUID taskId) {
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(pos), new ClearTask(level.dimension().location(), pos, instanceId, taskId));
    }
}
