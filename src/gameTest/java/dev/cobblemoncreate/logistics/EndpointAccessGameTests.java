package dev.cobblemoncreate.logistics;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.cobblemoncreate.logistics.cobblemon.CarrierRuntimeLease;
import dev.cobblemoncreate.logistics.cobblemon.ResidentWorkerRuntime;
import dev.cobblemoncreate.logistics.hub.CobblemonHubBlockEntity;
import dev.cobblemoncreate.logistics.hub.HubVisualState;
import dev.cobblemoncreate.logistics.registry.LogisticsBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder(CobblemonCreateLogistics.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EndpointAccessGameTests {
    @GameTest(template = "empty", timeoutTicks = 120, batch = "endpointAccess")
    public static void walkingCourierCannotTakeNearbyCargoThroughWall(GameTestHelper helper) {
        Fixture f = new Fixture(helper, "eevee", 34008);
        helper.runAfterDelay(40, () -> {
            f.claim();
            for (int y = 0; y < 4; y++) f.level.setBlockAndUpdate(f.sourcePos.west().above(y), Blocks.STONE.defaultBlockState());
            f.entity.teleportTo(f.sourcePos.getX() - 1.5D, f.sourcePos.getY(), f.sourcePos.getZ() + 0.5D);
            helper.assertTrue(f.entity.position().distanceTo(ResidentWorkerRuntime.interactionPosition(f.sourcePos)) < 2.5D,
                    "courier is close enough for the old distance-only pickup");
            helper.assertTrue(!DeliveryTaskManager.startTransit(f.level.getServer(), f.task.id(), f.level.getGameTime()),
                    "the real pickup transaction itself rejects contact through a wall");
            helper.assertTrue(f.source.visualEvents().stream().noneMatch(event -> event.kind() == HubVisualState.Kind.COURIER_PICKUP),
                    "blocked pickup cannot produce a success animation event");
            DeliveryTaskManager.tick(f.level.getServer());
            helper.assertTrue(f.task.state() == DeliveryState.CLAIMED_BY_WORKER
                            && CarrierRuntimeLease.progress(f.task.id()).phase().equals("pickup_obstructed"),
                    "actual package contact is blocked even when another entrance is open");
            f.cleanup();
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300, batch = "endpointAccess")
    public static void walkingPickupResumesWhenPlayerOpensSideEntrance(GameTestHelper helper) {
        Fixture f = new Fixture(helper, "eevee", 26008);
        helper.runAfterDelay(40, () -> {
            f.claim();
            f.seal();
            f.entity.teleportTo(f.sourcePos.getX() - 1.5D, f.sourcePos.getY(), f.sourcePos.getZ() + 0.5D);
            DeliveryTaskManager.tick(f.level.getServer());
            helper.assertTrue(f.task.state() == DeliveryState.CLAIMED_BY_WORKER
                            && CarrierRuntimeLease.progress(f.task.id()).phase().equals("pickup_space")
                            && CarrierRuntimeLease.progress(f.task.id()).remainingSeconds() == -1,
                    "a sealed pickup waits with a precise reason and no false countdown");
            helper.runAfterDelay(100, () -> {
                helper.assertTrue(f.task.state() == DeliveryState.CLAIMED_BY_WORKER
                                && ItemStack.isSameItemSameComponents(f.box, f.task.packageStack()),
                        "waiting preserves the original authoritative package");
                for (int y = 0; y < 4; y++) f.level.setBlockAndUpdate(f.sourcePos.west().above(y), Blocks.AIR.defaultBlockState());
                // Reopening only the side works even though the top is still covered.
                helper.runAfterDelay(20, () -> {
                    DeliveryTaskManager.tick(f.level.getServer());
                    helper.assertTrue(f.task.state() == DeliveryState.IN_TRANSIT
                                    && f.pokemon.getEntity() == f.entity
                                    && !CarrierRuntimeLease.progress(f.task.id()).phase().startsWith("pickup_"),
                            "an accessible side entrance resumes the same courier automatically; state="
                                    + f.task.state() + ", phase=" + CarrierRuntimeLease.progress(f.task.id()).phase()
                                    + ", courier=" + f.entity.position() + ", approach="
                                    + CarrierRuntimeLease.endpointApproach(f.level, f.entity, f.sourcePos)
                                    + ", exchange=" + HubCourierAccess.canExchange(f.level, f.entity, f.sourcePos));
                    helper.assertTrue(f.source.visualEvents().stream().filter(event -> event.kind() == HubVisualState.Kind.COURIER_PICKUP).count() == 1,
                            "opening the real passage commits one pickup event");
                    f.cleanup();
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "endpointAccess")
    public static void ghostCanPickUpThroughTheSealedPort(GameTestHelper helper) {
        Fixture f = new Fixture(helper, "gastly", 28008);
        helper.runAfterDelay(40, () -> {
            f.claim();
            f.seal();
            f.entity.teleportTo(f.sourcePos.getX() - 1.5D, f.sourcePos.getY() + 1, f.sourcePos.getZ() + 0.5D);
            helper.assertTrue(HubCourierAccess.canExchange(f.level, f.entity, f.sourcePos), "Ghost retains its phasing advantage");
            DeliveryTaskManager.tick(f.level.getServer());
            helper.assertTrue(f.task.state() == DeliveryState.IN_TRANSIT
                            && ItemStack.isSameItemSameComponents(f.box, f.task.packageStack()),
                    "Ghost takes the original cargo through a wall");
            helper.assertTrue(f.source.visualEvents().stream().anyMatch(event ->
                            event.kind() == HubVisualState.Kind.COURIER_PICKUP && event.side() == Direction.WEST),
                    "the Ghost's pickup animation faces its actual side even while phasing");
            f.cleanup();
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100, batch = "endpointAccess")
    public static void flyingCourierCanPickUpFromOpenBottom(GameTestHelper helper) {
        Fixture f = new Fixture(helper, "pidgey", 36008);
        helper.runAfterDelay(40, () -> {
            f.claim();
            int belowOffset = Math.max(1, (int) Math.ceil(f.entity.getBbHeight() + 0.01D));
            for (int depth = 1; depth <= belowOffset; depth++)
                f.level.setBlockAndUpdate(f.sourcePos.below(depth), Blocks.AIR.defaultBlockState());
            BlockPos below = f.sourcePos.below(belowOffset);
            f.entity.teleportTo(below.getX() + 0.5D, below.getY(), below.getZ() + 0.5D);
            helper.assertTrue(below.equals(CarrierRuntimeLease.endpointApproach(f.level, f.entity, f.sourcePos)),
                    "an open bottom is the nearest accessible face for a flying courier");
            helper.assertTrue(DeliveryTaskManager.startTransit(f.level.getServer(), f.task.id(), f.level.getGameTime())
                            && f.task.state() == DeliveryState.IN_TRANSIT,
                    "the real package can be collected from the underside");
            helper.assertTrue(f.source.visualEvents().stream().anyMatch(event ->
                            event.kind() == HubVisualState.Kind.COURIER_PICKUP && event.side() == Direction.DOWN),
                    "the bottom pickup animation follows the committed contact face");
            f.cleanup();
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 140, batch = "endpointAccess")
    public static void fullDestinationWaitsAndCommitsExactlyOnceAfterCollection(GameTestHelper helper) {
        Fixture f = new Fixture(helper, "charizard", 30008);
        helper.runAfterDelay(40, () -> {
            for (int i = 0; i < 16; i++) helper.assertTrue(f.target.acceptCreatePackage(
                    PackageItem.containing(List.of(new ItemStack(Items.IRON_INGOT))),
                    f.level.dimension(), f.targetPos), "fill destination buffer");
            f.claim();
            helper.assertTrue(DeliveryTaskManager.startTransit(f.level.getServer(), f.task.id(), f.level.getGameTime()), "begin trip");
            f.entity.teleportTo(f.targetPos.getX() + 0.5D, f.targetPos.getY() + 1, f.targetPos.getZ() + 0.5D);
            DeliveryTaskManager.tick(f.level.getServer());
            JourneyProgress waiting = CarrierRuntimeLease.progress(f.task.id());
            helper.assertTrue(f.task.state() == DeliveryState.IN_TRANSIT
                            && waiting.phase().equals("waiting_capacity") && waiting.remainingSeconds() == -1,
                    "full destination does not claim arrival or a one-second ETA");
            helper.assertTrue(waiting == CarrierRuntimeLease.workerProgress(f.worker.pokemonUuid()),
                    "worker and task readers share one per-tick progress snapshot");
            helper.assertTrue(PackageItem.isPackage(f.target.collectAvailablePackage()), "player frees one buffer slot");
            DeliveryTaskManager.tick(f.level.getServer());
            helper.assertTrue(f.task.state() == DeliveryState.ARRIVED_BUFFERED, "delivery commits when capacity returns");
            helper.assertTrue(f.target.visualEvents().stream().filter(event -> event.kind() == HubVisualState.Kind.COURIER_RECEIVE).count() == 1
                            && !DeliveryTaskManager.arrive(f.level.getServer(), f.task.id(), f.level.getGameTime()),
                    "arrival success is emitted once and the transaction cannot replay");
            ItemStack delivered = DeliveryTaskManager.collectArrivedPackage(f.level.getServer(), f.task.id(), f.level.getGameTime());
            helper.assertTrue(ItemStack.isSameItemSameComponents(f.box, delivered)
                            && DeliveryTaskSavedData.get(f.level.getServer()).get(f.task.id()) == null,
                    "the original Create package is collected exactly once");
            helper.assertTrue(f.target.visualEvents().stream().noneMatch(event -> event.taskId().equals(f.task.id())),
                    "collection immediately clears this package's destination display without clearing unrelated events");
            f.cleanup();
            helper.succeed();
        });
    }

    private static final class Fixture {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos sourcePos;
        final BlockPos targetPos;
        final CobblemonHubBlockEntity source;
        final CobblemonHubBlockEntity target;
        final Pokemon pokemon;
        final WorkerLease worker;
        final ItemStack box;
        PokemonEntity entity;
        DeliveryTask task;

        Fixture(GameTestHelper helper, String species, int x) {
            this.helper = helper;
            level = helper.getLevel();
            sourcePos = new BlockPos(x, 80, 18008);
            targetPos = sourcePos.east(12);
            force(true);
            for (int dx = -4; dx <= 16; dx++) for (int dz = -4; dz <= 4; dz++) {
                level.setBlockAndUpdate(sourcePos.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
                for (int y = 0; y < 6; y++) level.setBlockAndUpdate(sourcePos.offset(dx, y, dz), Blocks.AIR.defaultBlockState());
            }
            level.setBlockAndUpdate(sourcePos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
            level.setBlockAndUpdate(targetPos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
            source = (CobblemonHubBlockEntity) level.getBlockEntity(sourcePos);
            target = (CobblemonHubBlockEntity) level.getBlockEntity(targetPos);
            target.setStationAddress("access-" + x);
            pokemon = new Pokemon();
            pokemon.setSpecies(PokemonSpecies.getByName(species));
            helper.assertTrue(Cobblemon.INSTANCE.getStorage().getPC(source.storageUuid(), level.registryAccess()).add(pokemon), "native PC stores worker");
            UUID tether = UUID.randomUUID();
            pokemon.setTetheringId(tether);
            worker = new WorkerLease(UUID.randomUUID(), pokemon.getUuid(), pokemon.getSpecies().getResourceIdentifier(),
                    level.dimension(), sourcePos, tether, source.storageUuid());
            helper.assertTrue(source.assignWorker(worker) >= 0, "worker assigned");
            box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
            PackageItem.addAddress(box, target.address());
        }

        void claim() {
            helper.assertTrue(ResidentWorkerRuntime.ensure(level, worker), "native courier spawns");
            entity = pokemon.getEntity();
            helper.assertTrue(source.acceptCreatePackage(box, level.dimension(), sourcePos), "package accepted");
            task = DeliveryTaskSavedData.get(level.getServer()).get(source.taskIds().get(0));
            // A restored route keeps its previously resolved destination even
            // when that destination is now full. It must wait, not lose cargo.
            if (!task.hasCourierDestination()) task.setTarget(level.dimension(), targetPos);
            helper.assertTrue(DeliveryTaskManager.claim(level.getServer(), task.id(), worker, level.getGameTime()), "courier claims package");
        }

        void seal() {
            level.setBlockAndUpdate(sourcePos.above(), Blocks.STONE.defaultBlockState());
            for (Direction side : Direction.Plane.HORIZONTAL) for (int y = 0; y < 4; y++) {
                level.setBlockAndUpdate(sourcePos.relative(side).above(y), Blocks.STONE.defaultBlockState());
            }
        }

        void cleanup() {
            if (task != null && DeliveryTaskSavedData.get(level.getServer()).get(task.id()) != null) {
                DeliveryTaskManager.rollback(level.getServer(), task.id(), level.getGameTime());
            }
            ResidentWorkerRuntime.release(worker, level);
            for (UUID id : List.copyOf(target.taskIds())) {
                if (DeliveryTaskSavedData.get(level.getServer()).get(id) != null) DeliveryTaskManager.rollback(level.getServer(), id, level.getGameTime());
            }
            force(false);
        }

        void force(boolean forced) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                level.setChunkForced((sourcePos.getX() >> 4) + dx, (sourcePos.getZ() >> 4) + dz, forced);
            }
        }
    }
}
