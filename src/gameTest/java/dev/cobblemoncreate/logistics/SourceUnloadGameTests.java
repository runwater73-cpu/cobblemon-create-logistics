package dev.cobblemoncreate.logistics;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.cobblemoncreate.logistics.cobblemon.CarrierRuntimeLease;
import dev.cobblemoncreate.logistics.cobblemon.ResidentWorkerRuntime;
import dev.cobblemoncreate.logistics.hub.CobblemonHubBlockEntity;
import dev.cobblemoncreate.logistics.registry.LogisticsBlocks;
import net.minecraft.core.BlockPos;
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
public final class SourceUnloadGameTests {
    @GameTest(template = "empty", timeoutTicks = 6000, batch = "sourceUnload")
    public static void sourceCanUnloadWhileSameCourierDeliversAndWaitsToReturn(GameTestHelper helper) {
        new Journey(helper).start();
    }

    private static final class Journey {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos sourcePos = new BlockPos(32008, 80, 22008);
        final BlockPos targetPos = sourcePos.east(1024);
        Pokemon pokemon;
        PokemonEntity entity;
        WorkerLease worker;
        UUID taskId;
        boolean sourceReleased;
        boolean delivered;
        boolean sourceRestored;
        boolean sourceActuallyUnloaded;

        Journey(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
        }

        void start() {
            force(sourcePos, true);
            force(targetPos, true);
            place(sourcePos);
            place(targetPos);
            var source = (CobblemonHubBlockEntity) level.getBlockEntity(sourcePos);
            var target = (CobblemonHubBlockEntity) level.getBlockEntity(targetPos);
            target.setStationAddress("source-unload-test");
            helper.runAfterDelay(40, () -> {
                pokemon = new Pokemon();
                pokemon.setSpecies(PokemonSpecies.getByName("charizard"));
                helper.assertTrue(Cobblemon.INSTANCE.getStorage().getPC(source.storageUuid(), level.registryAccess()).add(pokemon), "native Pokemon stored");
                UUID tether = UUID.randomUUID();
                pokemon.setTetheringId(tether);
                worker = new WorkerLease(UUID.randomUUID(), pokemon.getUuid(), pokemon.getSpecies().getResourceIdentifier(),
                        level.dimension(), sourcePos, tether, source.storageUuid());
                helper.assertTrue(source.assignWorker(worker) >= 0 && ResidentWorkerRuntime.ensure(level, worker), "courier assigned");
                entity = pokemon.getEntity();
                ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
                PackageItem.addAddress(box, target.address());
                helper.assertTrue(source.acceptCreatePackage(box, level.dimension(), sourcePos), "package accepted");
                taskId = source.taskIds().get(0);
            });
            helper.onEachTick(this::observe);
        }

        void observe() {
            if (taskId == null) return;
            helper.assertTrue(pokemon.getEntity() == entity && entity.isAlive()
                            && level.getEntity(entity.getId()) == entity,
                    "the same native Pokemon remains present while its source unloads");
            if (!sourceReleased && CarrierRuntimeLease.isRemoteGapActive(taskId)) {
                sourceReleased = true;
                force(sourcePos, false);
            }
            if (sourceReleased && !level.hasChunkAt(sourcePos)) sourceActuallyUnloaded = true;
            DeliveryTask task = DeliveryTaskSavedData.get(level.getServer()).get(taskId);
            if (!delivered && task != null && task.state() == DeliveryState.ARRIVED_BUFFERED) {
                helper.assertTrue(sourceActuallyUnloaded && !RouteChunks.canTravel(level, sourcePos),
                        "delivery completes after the source really unloaded");
                var target = (CobblemonHubBlockEntity) level.getBlockEntity(targetPos);
                helper.assertTrue(PackageItem.isPackage(target.collectAvailablePackage())
                                && target.collectAvailablePackage().isEmpty(), "one package collected exactly once");
                delivered = true;
            }
            if (delivered && !sourceRestored && CarrierRuntimeLease.workerProgress(worker.pokemonUuid()).phase().equals("waiting_endpoint")) {
                helper.assertTrue(CarrierRuntimeLease.workerProgress(worker.pokemonUuid()).remainingSeconds() == -1
                                && !level.hasChunkAt(sourcePos) && !level.hasChunkAt(sourcePos.east(512)),
                        "return waits without loading home or the gap and without a false ETA");
                sourceRestored = true;
                force(sourcePos, true);
            }
            if (sourceRestored && RouteChunks.canTravel(level, sourcePos)
                    && level.getBlockEntity(sourcePos) instanceof CobblemonHubBlockEntity source
                    && source.isWorkerHomeReported(worker.pokemonUuid())) {
                helper.assertTrue(!entity.isInvisible() && !CarrierRuntimeLease.isWorkerActive(worker.pokemonUuid())
                                && entity.position().distanceToSqr(ResidentWorkerRuntime.physicalInteractionPosition(level, entity, sourcePos)) < 3,
                        "the original courier reports home only after home becomes available");
                ResidentWorkerRuntime.release(worker, level);
                force(sourcePos, false);
                force(targetPos, false);
                helper.succeed();
            }
        }

        void place(BlockPos pos) {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
                level.setBlockAndUpdate(pos.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y < 7; y++) level.setBlockAndUpdate(pos.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
            level.setBlockAndUpdate(pos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        }

        void force(BlockPos pos, boolean forced) {
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                level.setChunkForced((pos.getX() >> 4) + x, (pos.getZ() >> 4) + z, forced);
            }
        }
    }
}
