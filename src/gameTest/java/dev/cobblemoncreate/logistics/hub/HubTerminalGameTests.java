package dev.cobblemoncreate.logistics.hub;

import dev.cobblemoncreate.logistics.CobblemonCreateLogistics;
import dev.cobblemoncreate.logistics.registry.LogisticsBlocks;
import dev.cobblemoncreate.logistics.registry.LogisticsItems;
import dev.cobblemoncreate.logistics.network.HubNetwork;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CobblemonCreateLogistics.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HubTerminalGameTests {
    private HubTerminalGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void terminalBindsViaPriorityItemHook(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos second = helper.absolutePos(new BlockPos(3, 1, 1));
        level.setBlockAndUpdate(first, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        level.setBlockAndUpdate(second, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());

        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack terminal = LogisticsItems.COBBLEMON_HUB_LINKER.get().getDefaultInstance();
        helper.assertTrue(rightClick(player, level, terminal, first) == InteractionResult.SUCCESS,
                "terminal must consume priority interaction before the Hub menu");
        helper.assertTrue(rightClick(player, level, terminal, second) == InteractionResult.SUCCESS,
                "the second station also binds through the priority interaction");
        var data = terminal.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        helper.assertTrue(data.getList("CobblemonLogisticsStations", Tag.TAG_COMPOUND).size() == 2,
                "one terminal stores both right-clicked stations");
        helper.assertTrue(data.getInt("CobblemonLogisticsActiveStation") == 1,
                "the last bound station becomes active");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void terminalMessagesEncodeForClient(GameTestHelper helper) {
        var level = helper.getLevel();
        ResourceLocation dimension = level.dimension().location();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        var selected = CobblemonHubLinkerItem.selectedStationMessage(2, 3, dimension, pos);
        var entry = CobblemonHubLinkerItem.stationEntryMessage(true, 2, dimension, pos,
                new int[] {1, 2, 3});
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            ClientboundSystemChatPacket.STREAM_CODEC.encode(buf, new ClientboundSystemChatPacket(selected, true));
            ClientboundSystemChatPacket.STREAM_CODEC.decode(buf);
            buf.clear();
            ClientboundSystemChatPacket.STREAM_CODEC.encode(buf, new ClientboundSystemChatPacket(entry, false));
            ClientboundSystemChatPacket.STREAM_CODEC.decode(buf);
            helper.succeed();
        } finally {
            buf.release();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void terminalRemoteMenuEncodesForClient(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos second = helper.absolutePos(new BlockPos(3, 1, 1));
        level.setBlockAndUpdate(first, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        level.setBlockAndUpdate(second, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            var stations = java.util.List.of(
                    new TerminalMenu.Station(new CobblemonHubLinkerItem.Binding(level.dimension().location(), first),
                            true, 2, 1, 0, java.util.List.of()),
                    new TerminalMenu.Station(new CobblemonHubLinkerItem.Binding(level.dimension().location(), second),
                            true, 0, 3, 1, java.util.List.of()));
            TerminalMenu.writeStations(buf, stations);
            buf.writeVarInt(1);
            buf.readerIndex(0);
            TerminalMenu client = new TerminalMenu(4, player.getInventory(), buf);
            helper.assertTrue(client.stations().size() == 2 && client.active() == 1,
                    "remote menu transfers both stations and the active selection");
            helper.assertTrue(client.stations().get(0).binding().pos().equals(first),
                    "remote menu preserves station position");
            buf.clear();
            HubNetwork.SyncTerminalState.CODEC.encode(buf,
                    new HubNetwork.SyncTerminalState(4, stations));
            var decoded = HubNetwork.SyncTerminalState.CODEC.decode(buf);
            helper.assertTrue(decoded.containerId() == 4 && decoded.stations().equals(stations),
                    "terminal live update round-trips across the network");
            helper.succeed();
        } finally {
            buf.release();
        }
    }

    private static InteractionResult rightClick(net.minecraft.world.entity.player.Player player,
                                                 net.minecraft.server.level.ServerLevel level,
                                                 ItemStack terminal, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        UseOnContext context = new UseOnContext(level, player, InteractionHand.MAIN_HAND, terminal, hit);
        return terminal.getItem().onItemUseFirst(terminal, context);
    }
}
