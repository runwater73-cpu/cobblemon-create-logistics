package dev.cobblemoncreate.logistics.hub;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.storage.pc.PCPosition;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.authlib.GameProfile;
import dev.cobblemoncreate.logistics.CobblemonCreateLogistics;
import dev.cobblemoncreate.logistics.network.HubNetwork;
import dev.cobblemoncreate.logistics.registry.LogisticsBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder(CobblemonCreateLogistics.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HubPcNavigationGameTests {
    private record Fixture(FakePlayer player, HubMenu menu, List<HubMenu.Snapshot> received) {}

    private static Fixture fixture(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(pos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(pos);
        var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "HubPcTest"));
        player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        List<HubMenu.Snapshot> received = new ArrayList<>();
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND),
                player, CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public void send(Packet<?> packet) {
                if (packet instanceof ClientboundCustomPayloadPacket payload
                        && payload.payload() instanceof HubNetwork.SyncState state) received.add(state.snapshot());
            }
        };
        HubMenu menu = new HubMenu(1, player.getInventory(), hub);
        player.containerMenu = menu;
        return new Fixture(player, menu, received);
    }

    private static HubMenu.Snapshot navigate(GameTestHelper helper, Fixture fixture, int button) {
        int before = fixture.received.size();
        helper.assertTrue(fixture.menu.clickMenuButton(fixture.player, button), "PC navigation is accepted");
        helper.assertTrue(fixture.received.size() == before + 1, "every navigation sends exactly one client snapshot");
        HubMenu.Snapshot snapshot = fixture.received.getLast();
        helper.assertTrue(snapshot.equals(fixture.menu.snapshot()), "client contents and page match the server atomically");
        return snapshot;
    }

    @GameTest(template = "empty", timeoutTicks = 40, batch = "hubPcNavigation")
    public static void emptyPagesAndBoxesAlwaysSynchronize(GameTestHelper helper) {
        Fixture f = fixture(helper);
        helper.assertTrue(f.menu.boxCount() > 1, "PC fixture has multiple boxes");
        var secondPage = navigate(helper, f, 63);
        helper.assertTrue(secondPage.boxIndex() == 0 && secondPage.pageIndex() == 1, "empty second page is acknowledged");
        helper.assertTrue(secondPage.pcEntries().stream().allMatch(e -> e.level() == 0), "empty page contains no stale Pokemon");
        var secondBox = navigate(helper, f, 61);
        helper.assertTrue(secondBox.boxIndex() == 1 && secondBox.pageIndex() == 0, "box change resets the page");
        var firstBox = navigate(helper, f, 60);
        helper.assertTrue(firstBox.boxIndex() == 0 && firstBox.pageIndex() == 0, "empty previous box is acknowledged");
        var wrapped = navigate(helper, f, 60);
        helper.assertTrue(wrapped.boxIndex() == f.menu.boxCount() - 1, "previous box wraps with matching contents");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40, batch = "hubPcNavigation")
    public static void pageAndBoxBoundariesKeepPokemonInTheirSlots(GameTestHelper helper) {
        Fixture f = fixture(helper);
        var pc = Cobblemon.INSTANCE.getStorage().getPC(f.player.getUUID(), helper.getLevel().registryAccess());
        Pokemon first = pokemon("eevee"), second = pokemon("pikachu"), third = pokemon("gastly");
        pc.set(new PCPosition(0, 14), first);
        pc.set(new PCPosition(0, 15), second);
        pc.set(new PCPosition(1, 0), third);
        f.menu.refreshFromHub();
        helper.assertTrue(f.received.getLast().pcEntries().get(14).id().equals(first.getUuid()), "slot 14 is on the first page");
        var page = navigate(helper, f, 63);
        helper.assertTrue(page.pcEntries().getFirst().id().equals(second.getUuid()), "slot 15 becomes the first slot on page two");
        var box = navigate(helper, f, 61);
        helper.assertTrue(box.pageIndex() == 0 && box.pcEntries().getFirst().id().equals(third.getUuid()), "next box uses its own first page");
        var back = navigate(helper, f, 60);
        helper.assertTrue(back.pcEntries().getFirst().level() == 0 && back.pcEntries().get(14).id().equals(first.getUuid()),
                "returning clears the previous box's first slot");
        pc.remove(first); pc.remove(second); pc.remove(third);
        helper.succeed();
    }

    private static Pokemon pokemon(String species) {
        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(PokemonSpecies.getByName(species));
        pokemon.setLevel(12);
        return pokemon;
    }
}
