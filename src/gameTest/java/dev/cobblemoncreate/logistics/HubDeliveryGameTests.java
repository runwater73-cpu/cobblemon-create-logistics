package dev.cobblemoncreate.logistics;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.types.ElementalTypes;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.crate.CreativeCrateMountedStorage;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.api.contraption.storage.item.simple.SimpleMountedStorage;
import dev.cobblemoncreate.logistics.hub.CobblemonHubBlockEntity;
import dev.cobblemoncreate.logistics.hub.HubAddressRegistry;
import dev.cobblemoncreate.logistics.hub.HubVisualState;
import dev.cobblemoncreate.logistics.cobblemon.ResidentWorkerRuntime;
import dev.cobblemoncreate.logistics.cobblemon.CarrierRuntimeLease;
import dev.cobblemoncreate.logistics.registry.LogisticsBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@GameTestHolder(CobblemonCreateLogistics.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HubDeliveryGameTests {
    private HubDeliveryGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void speedStatsAffectCourierMultiplier(GameTestHelper helper) {
        Pokemon charizard = new Pokemon();
        charizard.setSpecies(PokemonSpecies.getByName("charizard"));
        charizard.setLevel(50);
        charizard.getIvs().set(Stats.SPEED, 0);
        charizard.getEvs().set(Stats.SPEED, 0);
        double slow = CarrierProfiles.resolve(charizard).speedMultiplier();
        charizard.getIvs().set(Stats.SPEED, 31);
        charizard.getEvs().set(Stats.SPEED, 252);
        double trained = CarrierProfiles.resolve(charizard).speedMultiplier();

        Pokemon pidgey = new Pokemon();
        pidgey.setSpecies(PokemonSpecies.getByName("pidgey"));
        pidgey.setLevel(50);
        pidgey.getIvs().set(Stats.SPEED, 31);
        pidgey.getEvs().set(Stats.SPEED, 252);
        double otherSpecies = CarrierProfiles.resolve(pidgey).speedMultiplier();
        helper.assertTrue(trained > slow && trained > otherSpecies,
                "IVs, EVs, and the species base speed affect transport speed");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void remoteRouteKeepsBothLoadedLegsVisible(GameTestHelper helper) {
        BlockPos source = BlockPos.ZERO;
        BlockPos target = source.east(128);
        var loaded = (java.util.function.Predicate<BlockPos>) pos ->
                (pos.getX() >> 4) <= 1 || (pos.getX() >> 4) >= 6;
        RouteChunks.VisibleSegment first = RouteChunks.visibleSegment(source, target, loaded);
        helper.assertTrue(first.gapAhead() && first.waypoint().getX() >= 28
                        && first.waypoint().getX() < 32,
                "the courier travels through the source-side loaded chunks");
        BlockPos reentry = RouteChunks.nextLoaded(first.waypoint(), target, 1, 128, loaded);
        helper.assertTrue(reentry != null && reentry.getX() >= 96,
                "the unloaded gap ends at the next loaded chunk");
        RouteChunks.VisibleSegment last = RouteChunks.visibleSegment(reentry, target, loaded);
        helper.assertTrue(last.destinationReached() && !last.gapAhead(),
                "the target-side loaded chunks remain a physical leg");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void loadedRouteCanBecomeRemoteWithoutRequeue(GameTestHelper helper) {
        BlockPos source = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos target = source.east(64);
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
        DeliveryTask task = DeliveryTask.create(box, helper.getLevel().dimension(), source,
                helper.getLevel().dimension(), target, helper.getLevel().getGameTime());
        WorkerLease worker = new WorkerLease(UUID.randomUUID(), UUID.randomUUID(),
                ResourceLocation.parse("cobblemon:gastly"), helper.getLevel().dimension(), source,
                UUID.randomUUID(), UUID.randomUUID());
        task.claim(worker, TransportMode.LOCAL, helper.getLevel().getGameTime());
        task.startTransit(helper.getLevel().getGameTime());
        task.switchToVirtualTransit();
        helper.assertTrue(task.mode() == TransportMode.VIRTUAL
                        && task.state() == DeliveryState.IN_TRANSIT
                        && task.worker().pokemonUuid().equals(worker.pokemonUuid())
                        && ItemStack.isSameItemSameComponents(box, task.packageStack()),
                "a disappearing route keeps the same task, courier, and cargo in transit");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void ghostPickupOnlyUsesFiniteMountedCargo(GameTestHelper helper) {
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
        var inventory = new ItemStackHandler(1);
        inventory.setStackInSlot(0, box.copy());
        var mounted = new SimpleMountedStorage(inventory);
        helper.assertTrue(PackageItem.isPackage(
                        GhostContraptionPickupRuntime.extractablePackage(mounted, 0))
                        && PackageItem.isPackage(mounted.getStackInSlot(0)),
                "simulating pickup leaves the mounted package in place");
        ItemStack extracted = mounted.extractItem(0, 1, false);
        helper.assertTrue(PackageItem.isPackage(extracted)
                        && mounted.getStackInSlot(0).isEmpty()
                        && GhostContraptionPickupRuntime.extractablePackage(mounted, 0).isEmpty(),
                "a finite mounted inventory gives the package exactly once");
        var creative = new CreativeCrateMountedStorage(box);
        helper.assertTrue(GhostContraptionPickupRuntime.extractablePackage(creative, 0).isEmpty(),
                "an infinite Create crate cannot duplicate courier cargo");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void arrivedPackageUsesUnpoweredCreatePackager(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos hubPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos packagerPos = hubPos.east();
        BlockPos chestPos = packagerPos.east();
        level.setBlockAndUpdate(hubPos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var packager = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:packager"));
        level.setBlockAndUpdate(packagerPos,
                packager.defaultBlockState().setValue(PackagerBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(hubPos);
        var chest = (ChestBlockEntity) level.getBlockEntity(chestPos);

        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND, 3)));
        PackageItem.addAddress(box, "B");
        DeliveryTask task = DeliveryTask.create(box, level.dimension(), hubPos.west(2),
                level.dimension(), hubPos, level.getGameTime());
        WorkerLease worker = new WorkerLease(UUID.randomUUID(), UUID.randomUUID(),
                ResourceLocation.parse("cobblemon:eevee"), level.dimension(), hubPos.west(2),
                UUID.randomUUID(), UUID.randomUUID());
        task.claim(worker, TransportMode.LOCAL, level.getGameTime());
        task.startTransit(level.getGameTime());
        task.arrive(level.getGameTime());
        DeliveryTaskSavedData data = DeliveryTaskSavedData.get(level.getServer());
        data.add(task);
        hub.acceptIncomingTask(task.id());

        CobblemonHubBlockEntity.serverTick(level, hubPos, level.getBlockState(hubPos), hub);
        helper.assertTrue(data.get(task.id()) != null && chest.isEmpty(),
                "wrong-facing Packager cannot consume an arrived package");
        helper.assertTrue(hub.visualEvents().isEmpty(), "rejected native machine insertion cannot flash success");

        level.setBlockAndUpdate(packagerPos,
                packager.defaultBlockState().setValue(PackagerBlock.FACING, Direction.WEST));
        helper.runAfterDelay(15, () -> {
            helper.assertTrue(data.get(task.id()) == null && hub.taskIds().isEmpty(),
                    "native Packager accepted the one arrived package");
            helper.assertTrue(chest.countItem(Items.DIAMOND) == 3,
                    "unpowered Packager unpacked into its rear chest");
            helper.assertTrue(hub.visualEvents().stream().anyMatch(event -> event.kind() == HubVisualState.Kind.PACKAGER_OUTPUT
                            && event.side() == Direction.EAST && event.taskId().equals(task.id())),
                    "only a successful real native insertion emits the matching side's event");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void hubPullsPackageFromPackagerBelow(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos hubPos = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos packagerPos = hubPos.below();
        level.setBlockAndUpdate(hubPos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var packagerBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:packager"));
        level.setBlockAndUpdate(packagerPos,
                packagerBlock.defaultBlockState().setValue(PackagerBlock.FACING, Direction.UP));
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(hubPos);
        var packager = (PackagerBlockEntity) level.getBlockEntity(packagerPos);
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.IRON_INGOT, 2)));
        PackageItem.addAddress(box, "bottom-input-unmatched");
        packager.inventory.setStackInSlot(0, box.copy());

        helper.runAfterDelay(15, () -> {
            helper.assertTrue(packager.inventory.getStackInSlot(0).isEmpty() && hub.taskIds().size() == 1,
                    "Hub above an upward-facing Packager pulls exactly one package from below");
            DeliveryTask task = DeliveryTaskSavedData.get(level.getServer()).get(hub.taskIds().getFirst());
            helper.assertTrue(task != null && task.sourcePos().equals(hubPos)
                            && ItemStack.isSameItemSameComponents(box, task.packageStack()),
                    "the Hub owns the one original package with its Create components intact");
            helper.assertTrue(hub.visualEvents().stream().anyMatch(event ->
                            event.kind() == HubVisualState.Kind.PACKAGER_INPUT
                                    && event.side() == Direction.DOWN && event.taskId().equals(task.id())),
                    "bottom input emits a downward transfer event");
            helper.assertTrue(ItemStack.isSameItemSameComponents(box, hub.collectAvailablePackage())
                            && DeliveryTaskSavedData.get(level.getServer()).get(task.id()) == null,
                    "the test package can be reclaimed once without leaving a global task");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void hubSendsArrivalToUpwardPackagerBelow(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos hubPos = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos packagerPos = hubPos.below();
        BlockPos chestPos = packagerPos.below();
        level.setBlockAndUpdate(hubPos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var packagerBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:packager"));
        level.setBlockAndUpdate(packagerPos,
                packagerBlock.defaultBlockState().setValue(PackagerBlock.FACING, Direction.DOWN));
        level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(hubPos);
        var chest = (ChestBlockEntity) level.getBlockEntity(chestPos);
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND, 3)));
        PackageItem.addAddress(box, "bottom-output");
        DeliveryTask task = DeliveryTask.create(box, level.dimension(), hubPos.east(8),
                level.dimension(), hubPos, level.getGameTime());
        WorkerLease worker = new WorkerLease(UUID.randomUUID(), UUID.randomUUID(),
                ResourceLocation.parse("cobblemon:eevee"), level.dimension(), hubPos.east(8),
                UUID.randomUUID(), UUID.randomUUID());
        task.claim(worker, TransportMode.LOCAL, level.getGameTime());
        task.startTransit(level.getGameTime());
        task.arrive(level.getGameTime());
        DeliveryTaskSavedData data = DeliveryTaskSavedData.get(level.getServer());
        data.add(task);
        hub.acceptIncomingTask(task.id());

        CobblemonHubBlockEntity.serverTick(level, hubPos, level.getBlockState(hubPos), hub);
        helper.assertTrue(data.get(task.id()) != null && chest.isEmpty(),
                "downward-facing Packager below the Hub cannot consume the arrival");
        level.setBlockAndUpdate(packagerPos,
                packagerBlock.defaultBlockState().setValue(PackagerBlock.FACING, Direction.UP));
        helper.runAfterDelay(15, () -> {
            helper.assertTrue(data.get(task.id()) == null && hub.taskIds().isEmpty(),
                    "upward-facing Packager below the Hub accepts the one arrived package");
            helper.assertTrue(chest.countItem(Items.DIAMOND) == 3,
                    "Packager below the Hub unpacks into the chest beneath it");
            helper.assertTrue(hub.visualEvents().stream().anyMatch(event ->
                            event.kind() == HubVisualState.Kind.PACKAGER_OUTPUT
                                    && event.side() == Direction.DOWN && event.taskId().equals(task.id())),
                    "bottom output emits a downward transfer event after the real insertion");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void oneHubSeparatesOutgoingAndIncomingBuffers(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos hubPos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(hubPos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(hubPos);
        var data = DeliveryTaskSavedData.get(level.getServer());
        BlockPos packagerPos = hubPos.east();
        BlockPos chestPos = packagerPos.east();
        var packager = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:packager"));
        level.setBlockAndUpdate(packagerPos,
                packager.defaultBlockState().setValue(PackagerBlock.FACING, Direction.WEST));
        level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        var chest = (ChestBlockEntity) level.getBlockEntity(chestPos);

        ItemStack outgoingBox = PackageItem.containing(List.of(new ItemStack(Items.IRON_INGOT, 1)));
        PackageItem.addAddress(outgoingBox, "outgoing");
        helper.assertTrue(hub.acceptCreatePackage(outgoingBox, level.dimension(), hubPos),
                "Hub accepts a new outgoing package");
        DeliveryTask outgoing = data.get(hub.taskIds().get(0));

        ItemStack incomingBox = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND, 1)));
        PackageItem.addAddress(incomingBox, "incoming");
        DeliveryTask incoming = DeliveryTask.create(incomingBox, level.dimension(), hubPos.east(8),
                level.dimension(), hubPos, level.getGameTime());
        WorkerLease worker = new WorkerLease(UUID.randomUUID(), UUID.randomUUID(),
                ResourceLocation.parse("cobblemon:eevee"), level.dimension(), hubPos,
                UUID.randomUUID(), UUID.randomUUID());
        incoming.claim(worker, TransportMode.LOCAL, level.getGameTime());
        incoming.startTransit(level.getGameTime());
        incoming.arrive(level.getGameTime());
        data.add(incoming);
        hub.acceptIncomingTask(incoming.id());

        helper.assertTrue(outgoing.isOutgoingBuffer(),
                "a newly accepted package stays in the outgoing buffer");
        helper.assertTrue(incoming.isIncomingBuffer(),
                "a delivered package stays in the incoming buffer");
        helper.assertTrue(!DeliveryTaskManager.claim(level.getServer(), incoming.id(), worker,
                        level.getGameTime()),
                "a courier cannot claim an incoming buffer task");
        helper.assertTrue(data.get(outgoing.id()) != null && data.get(incoming.id()) != null,
                "the two buffers keep their single authoritative task records");
        helper.runAfterDelay(15, () -> {
            helper.assertTrue(data.get(incoming.id()) == null && chest.countItem(Items.DIAMOND) == 1,
                    "the incoming buffer unpacks through the native Create Packager");
            helper.assertTrue(data.get(outgoing.id()) == outgoing && hub.hasTask(outgoing.id())
                            && outgoing.isOutgoingBuffer() && !hub.hasTask(incoming.id()),
                    "the outgoing buffer is unaffected by incoming unpacking");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void sourceTracksPackageUntilDestinationAcceptsIt(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos sourcePos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos targetPos = sourcePos.east(2);
        level.setBlockAndUpdate(sourcePos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        level.setBlockAndUpdate(targetPos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var source = (CobblemonHubBlockEntity) level.getBlockEntity(sourcePos);
        var target = (CobblemonHubBlockEntity) level.getBlockEntity(targetPos);

        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND, 1)));
        DeliveryTask task = DeliveryTask.create(box, level.dimension(), sourcePos,
                level.dimension(), targetPos, level.getGameTime());
        var data = DeliveryTaskSavedData.get(level.getServer());
        data.add(task);
        source.acceptIncomingTask(task.id());
        WorkerLease worker = new WorkerLease(UUID.randomUUID(), UUID.randomUUID(),
                ResourceLocation.parse("cobblemon:eevee"), level.dimension(), sourcePos,
                UUID.randomUUID(), UUID.randomUUID());
        task.claim(worker, TransportMode.LOCAL, level.getGameTime());
        task.startTransit(level.getGameTime());
        helper.assertTrue(source.trackedTasks().contains(task) && !target.trackedTasks().contains(task),
                "source Hub tracks the in-transit package from the authoritative task store");
        task.arrive(level.getGameTime());
        source.removeTask(task.id());
        target.acceptIncomingTask(task.id());
        helper.assertTrue(!source.trackedTasks().contains(task) && target.trackedTasks().contains(task),
                "tracking and cargo ownership transfer only on arrival");
        helper.assertTrue(source.collectAvailablePackage().isEmpty(),
                "source Hub cannot extract a package owned by the destination");
        helper.assertTrue(ItemStack.isSameItemSameComponents(box, target.collectAvailablePackage())
                        && data.get(task.id()) == null,
                "destination collects the one original package exactly once");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void liveArrivalRequiresCourierEntity(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos sourcePos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos targetPos = sourcePos.east(2);
        level.setBlockAndUpdate(targetPos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var target = (CobblemonHubBlockEntity) level.getBlockEntity(targetPos);
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
        DeliveryTask task = DeliveryTask.create(box, level.dimension(), sourcePos,
                level.dimension(), targetPos, level.getGameTime());
        WorkerLease worker = new WorkerLease(UUID.randomUUID(), UUID.randomUUID(),
                ResourceLocation.parse("cobblemon:eevee"), level.dimension(), sourcePos,
                UUID.randomUUID(), UUID.randomUUID());
        DeliveryTaskSavedData.get(level.getServer()).add(task);
        task.claim(worker, TransportMode.LOCAL, level.getGameTime());
        task.startTransit(level.getGameTime());

        helper.assertTrue(!DeliveryTaskManager.arrive(level.getServer(), task.id(), level.getGameTime()),
                "a live delivery cannot finish without its courier entity");
        helper.assertTrue(task.state() == DeliveryState.IN_TRANSIT && !target.hasTask(task.id()),
                "the package remains in transit until its courier can return");
        task.rollback(level.getGameTime());
        DeliveryTaskSavedData.get(level.getServer()).remove(task.id());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void unresolvedPackageCannotDispatchCourier(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos hubPos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(hubPos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(hubPos);
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
        helper.assertTrue(hub.acceptCreatePackage(box, level.dimension(), hubPos),
                "Hub buffers a package without an address");
        DeliveryTask task = DeliveryTaskSavedData.get(level.getServer()).get(hub.taskIds().get(0));
        WorkerLease worker = new WorkerLease(UUID.randomUUID(), UUID.randomUUID(),
                ResourceLocation.parse("cobblemon:eevee"), level.dimension(), hubPos,
                UUID.randomUUID(), UUID.randomUUID());
        helper.assertTrue(!task.hasCourierDestination(),
                "an unresolved package has no courier destination");
        helper.assertTrue(!DeliveryTaskManager.claim(level.getServer(), task.id(), worker,
                        level.getGameTime()),
                "an unresolved package cannot dispatch a courier");
        helper.assertTrue(task.state() == DeliveryState.BUFFERED && hub.hasTask(task.id()),
                "the unresolved package remains in the source Hub buffer");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void pausedPackageCanBeReclaimedExactlyOnce(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos sourcePos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(sourcePos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(sourcePos);
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.EMERALD, 2)));
        DeliveryTask task = DeliveryTask.create(box, level.dimension(), sourcePos,
                level.dimension(), sourcePos.east(8), level.getGameTime());
        task.pause(level.getGameTime(), PauseReason.CHUNKS_UNLOADED);
        var data = DeliveryTaskSavedData.get(level.getServer());
        data.add(task);
        hub.acceptIncomingTask(task.id());

        ItemStack reclaimed = hub.collectAvailablePackage();
        helper.assertTrue(ItemStack.isSameItemSameComponents(box, reclaimed),
                "a paused task returns its original Create package");
        helper.assertTrue(data.get(task.id()) == null && hub.taskIds().isEmpty()
                        && hub.collectAvailablePackage().isEmpty(),
                "reclaim removes the task and cannot return a second stack");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void selectedPackageCanBeCollectedWithoutTakingAnother(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos sourcePos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(sourcePos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(sourcePos);
        ItemStack firstBox = PackageItem.containing(List.of(new ItemStack(Items.EMERALD)));
        ItemStack secondBox = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
        var data = DeliveryTaskSavedData.get(level.getServer());
        DeliveryTask first = DeliveryTask.create(firstBox, level.dimension(), sourcePos,
                level.dimension(), sourcePos.east(8), level.getGameTime());
        DeliveryTask second = DeliveryTask.create(secondBox, level.dimension(), sourcePos,
                level.dimension(), sourcePos.east(8), level.getGameTime());
        data.add(first);
        data.add(second);
        hub.acceptIncomingTask(first.id());
        hub.acceptIncomingTask(second.id());
        helper.assertTrue(ItemStack.isSameItemSameComponents(secondBox, hub.collectPackage(second.id()))
                        && hub.collectPackage(second.id()).isEmpty() && data.get(first.id()) == first,
                "the selected buffered package is returned once without changing its neighbor");

        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(PokemonSpecies.getByName("charizard"));
        var pc = Cobblemon.INSTANCE.getStorage().getPC(hub.storageUuid(), level.registryAccess());
        helper.assertTrue(pc.add(pokemon), "courier stored in the Hub PC");
        UUID tether = UUID.randomUUID();
        pokemon.setTetheringId(tether);
        WorkerLease worker = new WorkerLease(UUID.randomUUID(), pokemon.getUuid(),
                pokemon.getSpecies().getResourceIdentifier(), level.dimension(), sourcePos,
                tether, hub.storageUuid());
        helper.assertTrue(hub.assignWorker(worker) >= 0 && ResidentWorkerRuntime.ensure(level, worker),
                "one courier is available for pickup");
        helper.assertTrue(CarrierRuntimeLease.acquire(level, first.id(), worker,
                CarrierProfiles.resolve(pokemon), firstBox).isPresent(), "the courier is leased to the first package");
        first.claim(worker, TransportMode.LOCAL, level.getGameTime());
        helper.assertTrue(ItemStack.isSameItemSameComponents(firstBox, hub.collectPackage(first.id()))
                        && hub.collectPackage(first.id()).isEmpty() && data.get(first.id()) == null
                        && CarrierRuntimeLease.isReturning(first.id()),
                "claimed cargo is reclaimed once before pickup and its courier starts returning");
        CarrierRuntimeLease.finishReturn(worker.pokemonUuid());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void crossDimensionAddressWaitsForTargetWithoutCourierEntity(GameTestHelper helper) {
        var sourceLevel = helper.getLevel();
        var targetLevel = sourceLevel.getServer().getLevel(Level.NETHER);
        helper.assertTrue(targetLevel != null, "the test server exposes the Nether");
        BlockPos sourcePos = helper.absolutePos(new BlockPos(1, 1, 1));
        int targetX = 128 + Math.floorMod(UUID.randomUUID().hashCode(), 100_000);
        BlockPos targetPos = new BlockPos(targetX, 80, 1);
        sourceLevel.setBlockAndUpdate(sourcePos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        targetLevel.setBlockAndUpdate(targetPos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var source = (CobblemonHubBlockEntity) sourceLevel.getBlockEntity(sourcePos);
        var target = (CobblemonHubBlockEntity) targetLevel.getBlockEntity(targetPos);
        String address = "nether-dock-" + UUID.randomUUID();
        target.setStationAddress(address);
        helper.assertTrue(address.equals(target.address()), "target Hub stores its receive address");
        helper.assertTrue(HubAddressRegistry.findDestination(sourceLevel.getServer(), sourceLevel.dimension(),
                        sourcePos, address) != null,
                "the addressed Nether Hub is registered");

        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.GOLD_INGOT, 1)));
        PackageItem.addAddress(box, address);
        helper.assertTrue(source.acceptCreatePackage(box, sourceLevel.dimension(), sourcePos),
                "the source accepts a package addressed to another dimension");
        DeliveryTask task = DeliveryTaskSavedData.get(sourceLevel.getServer()).get(source.taskIds().get(0));
        helper.assertTrue(task.mode() == TransportMode.UNASSIGNED
                        && task.targetDimension().equals(Level.NETHER),
                "cross-dimension package waits for a courier before choosing a route");
        DeliveryTaskManager.tick(sourceLevel.getServer());
        helper.assertTrue(task.state() == DeliveryState.BUFFERED && source.hasTask(task.id())
                        && !target.hasTask(task.id()),
                "a cross-dimension package waits for a courier instead of bypassing the Hub");
        helper.assertTrue(target.collectAvailablePackage().isEmpty(),
                "the destination cannot consume a package before a courier is assigned");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void movementCapabilitiesDetermineRoutes(GameTestHelper helper) {
        CarrierProfile walker = new CarrierProfile(ResourceLocation.parse("cobblemon:walker"),
                1, 1, true, false, true, false, false, false);
        CarrierProfile flyer = new CarrierProfile(ResourceLocation.parse("cobblemon:flyer"),
                1, 1.35, true, true, true, false, false, false);
        CarrierProfile ghost = new CarrierProfile(ResourceLocation.parse("cobblemon:ghost"),
                1, 1, true, false, true, false, false, true);
        helper.assertTrue(TransportRouting.canCarry(walker, true, true),
                "walkers use loaded same-dimension routes");
        helper.assertTrue(!TransportRouting.canCarry(walker, true, false),
                "walkers wait for unloaded terrain");
        helper.assertTrue(TransportRouting.canCarry(flyer, true, false),
                "flyers can use same-dimension endpoint handoff");
        helper.assertTrue(TransportRouting.canCarry(ghost, true, false) && ghost.canWalk()
                        && ghost.canSwimInWater() && ghost.ghost(),
                "ghost phase is an additional trait, not a replacement for movement abilities");
        helper.assertTrue(TransportRouting.canCarry(walker, false, false),
                "cross-dimension Hub handoff is not tied to elemental type");
        helper.assertTrue(TransportRouting.mode(true, true) == TransportMode.LOCAL
                        && TransportRouting.mode(true, false) == TransportMode.VIRTUAL
                        && TransportRouting.mode(false, false) == TransportMode.VIRTUAL,
                "route mode follows loaded terrain and dimension");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void courierChoosesRouteOnlyWhenClaimingPackage(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(1, 1, 1));
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
        DeliveryTask task = DeliveryTask.create(box, level.dimension(), source,
                level.dimension(), source.east(8), level.getGameTime());
        helper.assertTrue(task.mode() == TransportMode.UNASSIGNED && task.worker() == null,
                "a buffered package does not choose a transport route");

        WorkerLease worker = new WorkerLease(UUID.randomUUID(), UUID.randomUUID(),
                ResourceLocation.parse("cobblemon:pidgey"), level.dimension(), source,
                UUID.randomUUID(), UUID.randomUUID());
        var species = PokemonSpecies.getByName("pidgey");
        helper.assertTrue(species != null, "Cobblemon loaded Pidgey");
        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(species);
        CarrierProfile profile = CarrierProfiles.resolve(pokemon);
        helper.assertTrue(profile != null && TransportRouting.canCarry(profile, true, false),
                "the chosen courier can carry across unloaded chunks when it can fly");
        task.claim(worker, TransportRouting.mode(true, false), level.getGameTime());
        helper.assertTrue(task.worker().pokemonUuid().equals(worker.pokemonUuid())
                        && task.mode() == TransportMode.VIRTUAL,
                "the assigned courier determines the route after claiming the package");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void stalledCourierReturnsBeforePackageCanBeReassigned(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(1, 1, 1));
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.EMERALD)));
        DeliveryTask task = DeliveryTask.create(box, level.dimension(), source,
                level.dimension(), source.east(8), level.getGameTime());
        WorkerLease worker = new WorkerLease(UUID.randomUUID(), UUID.randomUUID(),
                ResourceLocation.parse("cobblemon:eevee"), level.dimension(), source,
                UUID.randomUUID(), UUID.randomUUID());
        task.claim(worker, TransportMode.LOCAL, level.getGameTime());
        task.startTransit(level.getGameTime());
        task.pause(level.getGameTime(), PauseReason.NO_PROGRESS);
        helper.assertTrue(task.state() == DeliveryState.PAUSED && task.worker() != null
                        && ItemStack.isSameItemSameComponents(box, task.packageCopy()),
                "a stalled route keeps its courier and single package record while returning");
        task.requeueAfterCourierReturn(level.getGameTime());
        helper.assertTrue(task.isOutgoingBuffer() && task.worker() == null
                        && task.mode() == TransportMode.UNASSIGNED
                        && ItemStack.isSameItemSameComponents(box, task.packageCopy()),
                "only after the return report can another courier take the same package");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void cobblemonFormsKeepAllMovementCapabilities(GameTestHelper helper) {
        assertMovementMatchesForm(helper, "eevee");
        assertMovementMatchesForm(helper, "pidgey");
        assertMovementMatchesForm(helper, "doduo");
        assertMovementMatchesForm(helper, "gastly");
        assertMovementMatchesForm(helper, "abra");
        assertMovementMatchesForm(helper, "magikarp");
        var water = PokemonSpecies.getByName("magikarp");
        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(water);
        helper.assertTrue(CarrierProfiles.resolve(pokemon) != null,
                "Water-type Pokemon are eligible through their movement behaviour");
        boolean flyingTypeCannotFly = false;
        boolean otherTypeCanFly = false;
        boolean walksAndSwims = false;
        for (var candidate : PokemonSpecies.getSpecies()) {
            var form = candidate.getStandardForm();
            var moving = form.getBehaviour().getMoving();
            boolean flyingType = form.getPrimaryType() == ElementalTypes.FLYING
                    || form.getSecondaryType() == ElementalTypes.FLYING;
            flyingTypeCannotFly |= flyingType && !moving.getFly().getCanFly();
            otherTypeCanFly |= !flyingType && moving.getFly().getCanFly();
            walksAndSwims |= moving.getWalk().getCanWalk()
                    && moving.getSwim().getCanSwimInWater();
        }
        helper.assertTrue(flyingTypeCannotFly && otherTypeCanFly && walksAndSwims,
                "loaded Cobblemon forms include all three mixed movement cases");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void receiveAndDefaultDestinationAddressesStayIndependent(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(pos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(pos);
        hub.setStationAddress("my-station");
        hub.addAddress("destination-a");
        hub.addAddress("destination-b");
        hub.setDefaultDestination(1);
        helper.assertTrue("my-station".equals(hub.stationAddress())
                        && "destination-b".equals(hub.defaultAddress()),
                "receiving address and default outbound destination are separate");
        hub.removeAddress(0);
        helper.assertTrue("my-station".equals(hub.stationAddress())
                        && "destination-b".equals(hub.defaultAddress()),
                "removing a preset does not rename this Hub or lose the selected default");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void idleCourierCanReturnToOwnerPc(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(pos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(pos);
        UUID owner = UUID.randomUUID();
        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(PokemonSpecies.getByName("eevee"));
        var storage = Cobblemon.INSTANCE.getStorage();
        var hubPc = storage.getPC(hub.storageUuid(), level.registryAccess());
        var ownerPc = storage.getPC(owner, level.registryAccess());
        helper.assertTrue(hubPc.add(pokemon), "the courier is stored in the Hub PC");
        UUID tether = UUID.randomUUID();
        pokemon.setTetheringId(tether);
        WorkerLease worker = new WorkerLease(owner, pokemon.getUuid(),
                pokemon.getSpecies().getResourceIdentifier(), level.dimension(), pos,
                tether, hub.storageUuid());
        hub.assignWorker(worker);
        helper.assertTrue(ResidentWorkerRuntime.ensure(level, worker),
                "the idle courier is roaming outside the Hub");
        helper.assertTrue(hub.removeWorker(pokemon.getUuid(), owner),
                "an idle courier can be removed while roaming");
        helper.assertTrue(hub.workers().isEmpty() && hubPc.get(pokemon.getUuid()) == null
                        && ownerPc.get(pokemon.getUuid()) != null,
                "removal transfers the same Pokemon back to its owner's PC");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "localFlyingDelivery")
    public static void flyingCourierDeliversOnlyAfterReachingHub(GameTestHelper helper) {
        assertAirCourierDelivers(helper, "pidgey", "flight-test-destination");
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "localGhostDelivery")
    public static void ghostCourierDeliversOnlyAfterReachingHub(GameTestHelper helper) {
        assertAirCourierDelivers(helper, "gastly", "ghost-test-destination");
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void ghostCourierRestoresCobblemonMotionAfterTask(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(pos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(pos);
        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(PokemonSpecies.getByName("gastly"));
        var pc = Cobblemon.INSTANCE.getStorage().getPC(hub.storageUuid(), level.registryAccess());
        helper.assertTrue(pc.add(pokemon), "Gastly is stored in the Hub PC");
        UUID tether = UUID.randomUUID();
        pokemon.setTetheringId(tether);
        WorkerLease worker = new WorkerLease(UUID.randomUUID(), pokemon.getUuid(),
                pokemon.getSpecies().getResourceIdentifier(), level.dimension(), pos,
                tether, hub.storageUuid());
        hub.assignWorker(worker);
        helper.assertTrue(ResidentWorkerRuntime.ensure(level, worker), "Gastly is present");
        PokemonEntity entity = pokemon.getEntity();
        CarrierProfile profile = CarrierProfiles.resolve(pokemon);
        helper.assertTrue(entity != null && profile != null && profile.ghost(),
                "the current form has the additional Ghost trait");
        boolean previousPhysics = entity.noPhysics;
        boolean previousGravity = entity.isNoGravity();
        boolean previousFlying = entity.isFlying();
        UUID taskId = UUID.randomUUID();
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
        helper.assertTrue(CarrierRuntimeLease.acquire(level, taskId, worker, profile, box).isPresent()
                        && entity.noPhysics,
                "the Ghost courier phases while assigned to a package");
        CarrierRuntimeLease.release(taskId);
        helper.assertTrue(entity.noPhysics == previousPhysics
                        && entity.isNoGravity() == previousGravity
                        && entity.isFlying() == previousFlying,
                "the courier's original Cobblemon motion is restored");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void remoteGapRestoresTheSameCourierEntity(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(pos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(pos);
        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(PokemonSpecies.getByName("gastly"));
        var pc = Cobblemon.INSTANCE.getStorage().getPC(hub.storageUuid(), level.registryAccess());
        helper.assertTrue(pc.add(pokemon), "Gastly is stored in the Hub PC");
        UUID tether = UUID.randomUUID();
        pokemon.setTetheringId(tether);
        WorkerLease worker = new WorkerLease(UUID.randomUUID(), pokemon.getUuid(),
                pokemon.getSpecies().getResourceIdentifier(), level.dimension(), pos,
                tether, hub.storageUuid());
        hub.assignWorker(worker);
        helper.assertTrue(ResidentWorkerRuntime.ensure(level, worker), "the courier is present");
        PokemonEntity entity = pokemon.getEntity();
        UUID taskId = UUID.randomUUID();
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
        helper.assertTrue(entity != null && CarrierRuntimeLease.acquire(level, taskId, worker,
                CarrierProfiles.resolve(pokemon), box).isPresent(), "the courier owns the task");
        Vec3 before = entity.position();
        CarrierRuntimeLease.beginRemoteGap(taskId, pos, level.getGameTime());
        helper.assertTrue(entity.isInvisible() && CarrierRuntimeLease.isRemoteGapActive(taskId),
                "the existing courier is hidden only during the unloaded gap");
        // A neighboring FULL border chunk need not tick entities. Keep this
        // reentry test inside the test's ticking chunk regardless of layout.
        BlockPos reentry = (pos.getX() & 15) < 8 ? pos.east(8) : pos.west(8);
        CarrierRuntimeLease.tickRemoteGap(taskId, level, reentry, level.getGameTime() + 40);
        helper.assertTrue(!entity.isInvisible() && !CarrierRuntimeLease.isRemoteGapActive(taskId)
                        && entity.position().distanceToSqr(before) > 4.0D,
                "the same courier reappears in the next loaded leg");
        CarrierRuntimeLease.release(taskId);
        helper.assertTrue(!entity.isInvisible(), "the courier's visibility is restored after release");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void veryLongGapAdvancesWithoutLoadingTerrain(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(pos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(pos);
        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(PokemonSpecies.getByName("charizard"));
        var pc = Cobblemon.INSTANCE.getStorage().getPC(hub.storageUuid(), level.registryAccess());
        helper.assertTrue(pc.add(pokemon), "courier stored in native Hub PC");
        UUID tether = UUID.randomUUID();
        pokemon.setTetheringId(tether);
        WorkerLease worker = new WorkerLease(UUID.randomUUID(), pokemon.getUuid(),
                pokemon.getSpecies().getResourceIdentifier(), level.dimension(), pos, tether, hub.storageUuid());
        helper.assertTrue(hub.assignWorker(worker) >= 0, "courier has a resident slot");
        helper.assertTrue(ResidentWorkerRuntime.ensure(level, worker), "one native courier spawned");
        PokemonEntity entity = pokemon.getEntity();
        UUID taskId = UUID.randomUUID();
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
        helper.assertTrue(CarrierRuntimeLease.acquire(level, taskId, worker, CarrierProfiles.resolve(pokemon), box).isPresent(),
                "courier acquired");
        BlockPos origin = pos.east(4096);
        BlockPos target = origin.east(50000);
        long now = level.getGameTime();
        CarrierRuntimeLease.startCarrying(taskId);
        CarrierRuntimeLease.setRouteDestination(taskId, target);
        CarrierRuntimeLease.beginRemoteGap(taskId, origin, now);
        JourneyProgress before = CarrierRuntimeLease.progress(taskId);
        for (int tick = 20; tick <= 2000; tick += 20) {
            CarrierRuntimeLease.tickRemoteGap(taskId, level, target, now + tick);
        }
        JourneyProgress after = CarrierRuntimeLease.progress(taskId);
        helper.assertTrue(CarrierRuntimeLease.isRemoteGapActive(taskId)
                        && after.position().getX() >= origin.getX() + 995
                        && after.position().getX() <= origin.getX() + 1005
                        && after.remainingSeconds() < before.remainingSeconds(),
                "a long trip advances 1000 blocks in 2000 ticks and its countdown decreases");
        helper.assertTrue(!level.hasChunkAt(origin) && !level.hasChunkAt(after.position())
                        && !level.hasChunkAt(target) && pokemon.getEntity() == entity && entity.isAlive(),
                "no virtual position requests terrain or creates a second Pokemon");
        helper.assertTrue(entity.position().distanceToSqr(ResidentWorkerRuntime.interactionPosition(pos)) < 1,
                "the hidden native entity stays in the existing endpoint");
        // Model a runtime lease rebuild from the native entity's saved metadata.
        var saved = entity.getPersistentData().getCompound("CobblemonLogisticsGap").copy();
        CarrierRuntimeLease.release(taskId);
        entity.getPersistentData().put("CobblemonLogisticsGap", saved);
        helper.assertTrue(CarrierRuntimeLease.acquire(level, taskId, worker, CarrierProfiles.resolve(pokemon), box).isPresent(),
                "the same entity restores its remote lease");
        CarrierRuntimeLease.startCarrying(taskId);
        helper.assertTrue(CarrierRuntimeLease.progress(taskId).position().equals(after.position())
                        && CarrierRuntimeLease.progress(taskId).remainingSeconds() == after.remainingSeconds(),
                "virtual coordinates and remaining time survive a lease rebuild");
        CarrierRuntimeLease.release(taskId);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void flyingGhostPhasesThroughSolidBlocks(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(pos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(pos);
        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(PokemonSpecies.getByName("gastly"));
        var pc = Cobblemon.INSTANCE.getStorage().getPC(hub.storageUuid(), level.registryAccess());
        helper.assertTrue(pc.add(pokemon), "Gastly is stored in the Hub PC");
        UUID tether = UUID.randomUUID();
        pokemon.setTetheringId(tether);
        WorkerLease worker = new WorkerLease(UUID.randomUUID(), pokemon.getUuid(),
                pokemon.getSpecies().getResourceIdentifier(), level.dimension(), pos,
                tether, hub.storageUuid());
        hub.assignWorker(worker);
        helper.assertTrue(ResidentWorkerRuntime.ensure(level, worker), "Gastly is present");
        PokemonEntity entity = pokemon.getEntity();
        CarrierProfile profile = CarrierProfiles.resolve(pokemon);
        helper.assertTrue(entity != null && profile != null && profile.ghost() && entity.canFly(),
                "the courier combines flight with the Ghost trait");
        entity.setPos(ResidentWorkerRuntime.interactionPosition(pos));
        BlockPos wall = pos.east(2).above();
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        UUID taskId = UUID.randomUUID();
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
        helper.assertTrue(CarrierRuntimeLease.acquire(level, taskId, worker, profile, box).isPresent(),
                "the Ghost courier accepted the route");
        for (int i = 0; i < 20; i++) {
            ResidentWorkerRuntime.navigatePhysicalTo(level, entity, pos.east(4), 1.0D);
        }
        helper.assertTrue(entity.noPhysics && entity.getX() > wall.getX() + 1.0D,
                "Ghost phase movement passes the wall even when the form can fly");
        CarrierRuntimeLease.release(taskId);
        helper.assertTrue(ResidentWorkerRuntime.release(worker, level),
                "the test courier returns to its PC after phasing");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void psychicCourierDeliversOnlyAfterReachingHub(GameTestHelper helper) {
        assertAirCourierDelivers(helper, "abra", "psychic-test-destination");
    }

    @GameTest(template = "empty", timeoutTicks = 480, batch = "groundObstruction")
    public static void groundCourierRoutesAroundWall(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos sourcePos = helper.absolutePos(new BlockPos(1, 1, 6));
        BlockPos targetPos = sourcePos.east(12);
        setRouteChunksForced(level, sourcePos, targetPos, true);
        for (int x = -2; x <= 14; x++) {
            for (int z = -6; z <= 6; z++) {
                level.setBlockAndUpdate(sourcePos.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(sourcePos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        level.setBlockAndUpdate(targetPos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var source = (CobblemonHubBlockEntity) level.getBlockEntity(sourcePos);
        var target = (CobblemonHubBlockEntity) level.getBlockEntity(targetPos);
        target.setStationAddress("ground-wall-" + UUID.randomUUID());
        for (int x = 5; x <= 6; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 0; y <= 3; y++) {
                    level.setBlockAndUpdate(sourcePos.offset(x, y, z), Blocks.STONE.defaultBlockState());
                }
            }
        }
        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(PokemonSpecies.getByName("eevee"));
        var pc = Cobblemon.INSTANCE.getStorage().getPC(source.storageUuid(), level.registryAccess());
        helper.assertTrue(pc.add(pokemon), "Eevee is stored in the Hub PC");
        UUID tether = UUID.randomUUID();
        pokemon.setTetheringId(tether);
        WorkerLease worker = new WorkerLease(UUID.randomUUID(), pokemon.getUuid(),
                pokemon.getSpecies().getResourceIdentifier(), level.dimension(), sourcePos,
                tether, source.storageUuid());
        source.assignWorker(worker);
        helper.assertTrue(ResidentWorkerRuntime.ensure(level, worker), "Eevee is present");
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
        PackageItem.addAddress(box, target.stationAddress());
        helper.assertTrue(source.acceptCreatePackage(box, level.dimension(), sourcePos),
                "the source accepted the package");
        UUID taskId = source.taskIds().get(0);
        helper.runAfterDelay(420, () -> {
            DeliveryTask task = DeliveryTaskSavedData.get(level.getServer()).get(taskId);
            PokemonEntity entity = pokemon.getEntity();
            setRouteChunksForced(level, sourcePos, targetPos, false);
            helper.assertTrue(task != null && task.state() == DeliveryState.ARRIVED_BUFFERED
                            && target.hasTask(taskId),
                    "Eevee must route around the stone wall; task="
                            + (task == null ? "missing" : task.state() + "/" + task.pauseReason())
                            + ", courier=" + (entity == null ? "missing" : entity.position()));
            helper.succeed();
        });
    }

    private static void setRouteChunksForced(net.minecraft.server.level.ServerLevel level,
                                             BlockPos source, BlockPos target, boolean forced) {
        for (int cx = (source.getX() - 8) >> 4; cx <= (target.getX() + 8) >> 4; cx++) {
            for (int cz = (source.getZ() - 8) >> 4; cz <= (source.getZ() + 8) >> 4; cz++) {
                level.setChunkForced(cx, cz, forced);
            }
        }
    }

    @GameTest(template = "empty", timeoutTicks = 420, batch = "flightObstruction")
    public static void flyingCourierClearsLeafWall(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos sourcePos = helper.absolutePos(new BlockPos(1, 1, 6));
        BlockPos targetPos = sourcePos.east(12);
        setRouteChunksForced(level, sourcePos, targetPos, true);
        level.setBlockAndUpdate(sourcePos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        level.setBlockAndUpdate(targetPos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var source = (CobblemonHubBlockEntity) level.getBlockEntity(sourcePos);
        var target = (CobblemonHubBlockEntity) level.getBlockEntity(targetPos);
        target.setStationAddress("leaf-wall-target");
        for (int x = 4; x <= 6; x++) {
            for (int z = -4; z <= 4; z++) {
                for (int y = 1; y <= 7; y++) {
                    level.setBlockAndUpdate(sourcePos.offset(x, y, z), Blocks.OAK_LEAVES.defaultBlockState());
                }
            }
        }

        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(PokemonSpecies.getByName("charizard"));
        var pc = Cobblemon.INSTANCE.getStorage().getPC(source.storageUuid(), level.registryAccess());
        helper.assertTrue(pc.add(pokemon), "Charizard is stored in the Hub PC");
        UUID tether = UUID.randomUUID();
        pokemon.setTetheringId(tether);
        WorkerLease worker = new WorkerLease(UUID.randomUUID(), pokemon.getUuid(),
                pokemon.getSpecies().getResourceIdentifier(), level.dimension(), sourcePos,
                tether, source.storageUuid());
        source.assignWorker(worker);
        helper.assertTrue(ResidentWorkerRuntime.ensure(level, worker), "Charizard is present");
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
        PackageItem.addAddress(box, "leaf-wall-target");
        helper.assertTrue(source.acceptCreatePackage(box, level.dimension(), sourcePos),
                "the source accepted the package");
        UUID taskId = source.taskIds().get(0);
        AtomicReference<Double> maxY = new AtomicReference<>(Double.NEGATIVE_INFINITY);
        AtomicReference<Double> maxDetour = new AtomicReference<>(0.0D);
        for (int tick = 20; tick <= 340; tick += 20) {
            helper.runAfterDelay(tick, () -> {
                PokemonEntity entity = pokemon.getEntity();
                if (entity != null) {
                    maxY.accumulateAndGet(entity.getY(), Math::max);
                    maxDetour.accumulateAndGet(Math.abs(entity.getZ() - sourcePos.getZ() - 0.5D), Math::max);
                }
            });
        }
        helper.runAfterDelay(360, () -> {
            DeliveryTask task = DeliveryTaskSavedData.get(level.getServer()).get(taskId);
            PokemonEntity entity = pokemon.getEntity();
            setRouteChunksForced(level, sourcePos, targetPos, false);
            helper.assertTrue(task != null && task.state() == DeliveryState.ARRIVED_BUFFERED
                            && target.hasTask(taskId) && !source.hasTask(taskId)
                            && (maxY.get() > sourcePos.getY() + 7.0D || maxDetour.get() > 4.0D),
                    "Charizard must clear the leaves and deliver; task="
                            + (task == null ? "missing" : task.state() + "/" + task.pauseReason())
                            + ", courier=" + (entity == null ? "missing" : entity.position())
                            + ", tickCount=" + (entity == null ? -1 : entity.tickCount)
                            + ", chunkLoaded=" + (entity != null && level.hasChunkAt(entity.blockPosition()))
                            + ", peakY=" + maxY.get() + ", detour=" + maxDetour.get());
            helper.succeed();
        });
    }

    private static void assertAirCourierDelivers(GameTestHelper helper, String speciesName,
                                                 String address) {
        var level = helper.getLevel();
        BlockPos sourcePos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos targetPos = sourcePos.east(10);
        setRouteChunksForced(level, sourcePos, targetPos, true);
        level.setBlockAndUpdate(sourcePos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        level.setBlockAndUpdate(targetPos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var source = (CobblemonHubBlockEntity) level.getBlockEntity(sourcePos);
        var target = (CobblemonHubBlockEntity) level.getBlockEntity(targetPos);
        target.setStationAddress(address);

        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(PokemonSpecies.getByName(speciesName));
        var pc = Cobblemon.INSTANCE.getStorage().getPC(source.storageUuid(), level.registryAccess());
        helper.assertTrue(pc.add(pokemon), "Courier is stored in the Hub PC");
        UUID tether = UUID.randomUUID();
        pokemon.setTetheringId(tether);
        source.assignWorker(new WorkerLease(UUID.randomUUID(), pokemon.getUuid(),
                pokemon.getSpecies().getResourceIdentifier(), level.dimension(), sourcePos,
                tether, source.storageUuid()));
        helper.assertTrue(ResidentWorkerRuntime.ensure(level, source.workers().get(0)),
                "the real Cobblemon courier is present");

        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
        PackageItem.addAddress(box, address);
        helper.assertTrue(source.acceptCreatePackage(box, level.dimension(), sourcePos),
                "source Hub accepts the addressed package");
        UUID taskId = source.taskIds().get(0);
        helper.succeedWhen(() -> {
            DeliveryTask task = DeliveryTaskSavedData.get(level.getServer()).get(taskId);
            PokemonEntity courier = pokemon.getEntity();
            helper.assertTrue(task != null && task.state() == DeliveryState.ARRIVED_BUFFERED
                            && target.hasTask(taskId) && !source.hasTask(taskId),
                    "the courier physically reached the target before package ownership moved; task="
                            + (task == null ? "missing" : task.state() + "/" + task.pauseReason()
                            + "/" + task.mode() + " -> " + task.targetPos())
                            + ", courier=" + (courier == null ? "missing" : courier.position())
                            + ", target=" + targetPos
                            + ", phase=" + CarrierRuntimeLease.progress(taskId).phase()
                            + ", approach=" + (courier == null ? "missing"
                            : CarrierRuntimeLease.endpointApproach(level, courier, sourcePos))
                            + ", exchange=" + (courier != null
                            && HubCourierAccess.canExchange(level, courier, sourcePos)));
            setRouteChunksForced(level, sourcePos, targetPos, false);
        });
    }

    private static void assertMovementMatchesForm(GameTestHelper helper, String speciesName) {
        var species = PokemonSpecies.getByName(speciesName);
        helper.assertTrue(species != null, "Cobblemon loaded " + speciesName);
        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(species);
        CarrierProfile profile = CarrierProfiles.resolve(pokemon);
        var moving = pokemon.getForm().getBehaviour().getMoving();
        helper.assertTrue(profile != null
                        && profile.canWalk() == moving.getWalk().getCanWalk()
                        && profile.canFly() == moving.getFly().getCanFly()
                        && profile.canSwimInWater() == moving.getSwim().getCanSwimInWater()
                        && profile.canSwimInLava() == moving.getSwim().getCanSwimInLava()
                        && profile.avoidsLand() == moving.getWalk().getAvoidsLand()
                        && profile.ghost() == CarrierProfiles.hasGhostTrait(pokemon),
                speciesName + " keeps every live form movement trait");
    }
}
