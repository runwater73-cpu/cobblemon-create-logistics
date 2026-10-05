package dev.cobblemoncreate.logistics;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.cobblemoncreate.logistics.hub.CobblemonHubBlockEntity;
import dev.cobblemoncreate.logistics.hub.CobblemonHubBlock;
import dev.cobblemoncreate.logistics.hub.HubVisualState;
import dev.cobblemoncreate.logistics.network.HubWorldNetwork;
import dev.cobblemoncreate.logistics.registry.LogisticsBlocks;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder(CobblemonCreateLogistics.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HubVisualGameTests {
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void hubFacingRotatesWithoutReplacingItsInventory(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        var block = (CobblemonHubBlock) LogisticsBlocks.COBBLEMON_HUB.get();
        var north = block.defaultBlockState().setValue(CobblemonHubBlock.FACING, Direction.NORTH);
        level.setBlockAndUpdate(pos, north);
        var hub = level.getBlockEntity(pos);

        helper.assertTrue(block.rotate(north, Rotation.CLOCKWISE_90).getValue(CobblemonHubBlock.FACING) == Direction.EAST
                        && block.mirror(north, Mirror.LEFT_RIGHT).getValue(CobblemonHubBlock.FACING) == Direction.SOUTH,
                "vanilla rotation and mirroring preserve horizontal facing");
        var state = north;
        for (Direction expected : List.of(Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH)) {
            state = block.getRotatedBlockState(state, Direction.UP);
            helper.assertTrue(state.getValue(CobblemonHubBlock.FACING) == expected,
                    "Create wrench cycles through all four horizontal directions");
            level.setBlockAndUpdate(pos, state);
            helper.assertTrue(level.getBlockEntity(pos) == hub,
                    "turning the Hub keeps its block entity and stored packages");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void worldPayloadKeepsOriginalPackageComponents(GameTestHelper helper) {
        var level = helper.getLevel();
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND, 7)));
        PackageItem.addAddress(box, "visual-roundtrip");
        UUID worker = UUID.randomUUID();
        UUID bufferedId = UUID.randomUUID();
        var snapshot = new HubVisualState.Snapshot(UUID.randomUUID(), level.getGameTime(),
                List.of(new HubVisualState.WorkerSignal(worker, "pickup_obstructed", true, false, false)),
                List.of(new HubVisualState.VisiblePackage(bufferedId, box)));
        Vec3 point = new Vec3(2.5, 4.25, -7.5);
        var event = new HubVisualState.TransferEvent(UUID.randomUUID(), UUID.randomUUID(), worker,
                HubVisualState.Kind.COURIER_RECEIVE, level.getGameTime(), Direction.WEST, box, point);
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            var packet = new HubWorldNetwork.Transfer(level.dimension().location(), BlockPos.ZERO, snapshot, event);
            HubWorldNetwork.Transfer.CODEC.encode(buf, packet);
            var decoded = HubWorldNetwork.Transfer.CODEC.decode(buf);
            helper.assertTrue(decoded.snapshot().instanceId().equals(snapshot.instanceId())
                            && decoded.snapshot().packages().size() == 1
                            && ItemStack.isSameItemSameComponents(box, decoded.snapshot().packages().getFirst().stack())
                            && decoded.event().eventId().equals(event.eventId())
                            && decoded.event().taskId().equals(event.taskId()) && worker.equals(decoded.event().workerId())
                            && decoded.event().side() == Direction.WEST && decoded.event().interactionPosition().equals(point)
                            && ItemStack.isSameItemSameComponents(box, decoded.event().packageStack()),
                    "observer payload preserves identity, exact interaction point and all Create package components");
            ItemStack display = decoded.event().packageStack();
            display.shrink(1);
            helper.assertTrue(decoded.event().packageStack().getCount() == 1 && box.getCount() == 1,
                    "visual consumers cannot mutate cargo through the event accessor");
            HubWorldNetwork.State.CODEC.encode(buf, new HubWorldNetwork.State(level.dimension().location(), BlockPos.ZERO, snapshot));
            var decodedState = HubWorldNetwork.State.CODEC.decode(buf).snapshot();
            helper.assertTrue(decodedState.instanceId().equals(snapshot.instanceId())
                            && decodedState.packages().size() == 1
                            && decodedState.packages().getFirst().taskId().equals(bufferedId)
                            && ItemStack.isSameItemSameComponents(box, decodedState.packages().getFirst().stack()),
                    "buffered package identity and native stack round trip");
            ItemStack visualCopy = decodedState.packages().getFirst().stack();
            visualCopy.shrink(1);
            helper.assertTrue(decodedState.packages().getFirst().stack().getCount() == 1,
                    "visual package snapshots cannot mutate cargo");
            var clear = new HubWorldNetwork.ClearTask(level.dimension().location(), BlockPos.ZERO, snapshot.instanceId(), event.taskId());
            HubWorldNetwork.ClearTask.CODEC.encode(buf, clear);
            helper.assertTrue(HubWorldNetwork.ClearTask.CODEC.decode(buf).equals(clear), "visual invalidation round trips");
        } finally { buf.release(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void transientEventsExpireAndNeverReplayAfterClear(GameTestHelper helper) {
        HubVisualState cache = new HubVisualState();
        UUID instance = UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        var snapshot = new HubVisualState.Snapshot(instance, 200,
                List.of(new HubVisualState.WorkerSignal(worker, "moving", true, false, false)));
        cache.accept(snapshot, 200);
        var event = new HubVisualState.TransferEvent(UUID.randomUUID(), task, worker,
                HubVisualState.Kind.COURIER_RECEIVE, 200, Direction.UP,
                PackageItem.containing(List.of(new ItemStack(Items.DIAMOND))), Vec3.ZERO);
        helper.assertTrue(cache.accept(instance, event, 200) && !cache.accept(instance, event, 201)
                && cache.events(201).size() == 1, "duplicate event id is played only once");
        cache.clearTask(task);
        helper.assertTrue(!cache.accept(instance, event, 202) && cache.events(202).isEmpty(), "collection clears cargo without replaying it");
        var later = new HubVisualState.TransferEvent(UUID.randomUUID(), task, worker,
                HubVisualState.Kind.COURIER_RECEIVE, 205, Direction.UP, event.packageStack(), Vec3.ZERO);
        cache.accept(instance, later, 205);
        helper.assertTrue(cache.events(230).isEmpty() && cache.snapshot(301).workers().isEmpty(), "expired display data disappears");
        cache.accept(new HubVisualState.Snapshot(UUID.randomUUID(), 302, List.of()), 302);
        helper.assertTrue(!cache.accept(instance, later, 302), "a replaced Hub rejects the previous instance's events");
        cache.clear();
        helper.assertTrue(cache.snapshot(303).workers().isEmpty() && cache.events(303).isEmpty(), "unloading clears the whole transient cache");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void simulatedAndRejectedMachineInputsDoNotFlashSuccess(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(pos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(pos);
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
        helper.assertTrue(hub.canAcceptCreatePackage(box, true) && hub.visualEvents().isEmpty(), "simulation has no success event");
        helper.assertTrue(!hub.acceptCreatePackage(new ItemStack(Items.STONE), level.dimension(), pos.east())
                && hub.visualEvents().isEmpty(), "rejected input has no success event");
        helper.assertTrue(hub.acceptCreatePackage(box, level.dimension(), pos.east()), "real input commits");
        var event = hub.visualEvents().getFirst();
        helper.assertTrue(hub.visualEvents().size() == 1 && event.kind() == HubVisualState.Kind.PACKAGER_INPUT
                        && event.side() == Direction.EAST && hub.hasTask(event.taskId())
                        && ItemStack.isSameItemSameComponents(box, event.packageStack()),
                "real commit emits one directional event for the authoritative package");
        var buffered = hub.createVisualSnapshot().packages();
        helper.assertTrue(buffered.size() == 1 && buffered.getFirst().taskId().equals(event.taskId())
                        && ItemStack.isSameItemSameComponents(box, buffered.getFirst().stack()),
                "the committed Create package appears in the server-owned visual queue");
        DeliveryTaskManager.rollback(level.getServer(), event.taskId(), level.getGameTime());
        helper.assertTrue(hub.visualEvents().isEmpty() && hub.createVisualSnapshot().packages().isEmpty(),
                "rollback immediately invalidates the transfer and buffered package visuals");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void workerSnapshotUsesUiOrderAndRemapsAfterRemoval(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(pos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(pos);
        WorkerLease first = worker(level, hub, UUID.randomUUID());
        WorkerLease second = worker(level, hub, UUID.randomUUID());
        hub.assignWorker(first);
        hub.assignWorker(second);
        var signals = hub.createVisualSnapshot().workers();
        helper.assertTrue(signals.get(0).pokemonUuid().equals(first.pokemonUuid())
                        && signals.get(1).pokemonUuid().equals(second.pokemonUuid()) && signals.get(1).phase().equals("idle"),
                "world signals use worker UUIDs in the same order as the Hub UI");
        hub.markWorkerAway(second.pokemonUuid());
        helper.assertTrue(hub.createVisualSnapshot().workers().get(1).returning(), "return state comes from the physical home report");
        var cache = new HubVisualState();
        var initial = hub.createVisualSnapshot();
        cache.accept(initial, 100);
        cache.accept(new HubVisualState.Snapshot(initial.instanceId(), initial.serverTick() + 1,
                List.of(initial.workers().get(1))), 101);
        helper.assertTrue(cache.snapshot(101).workers().size() == 1
                && cache.snapshot(101).workers().getFirst().pokemonUuid().equals(second.pokemonUuid()),
                "the compressed list maps the survivor by UUID rather than retaining its old slot");
        helper.succeed();
    }

    private static WorkerLease worker(net.minecraft.server.level.ServerLevel level, CobblemonHubBlockEntity hub, UUID id) {
        return new WorkerLease(UUID.randomUUID(), id, net.minecraft.resources.ResourceLocation.parse("cobblemon:eevee"),
                level.dimension(), hub.getBlockPos(), UUID.randomUUID(), hub.storageUuid());
    }
}
