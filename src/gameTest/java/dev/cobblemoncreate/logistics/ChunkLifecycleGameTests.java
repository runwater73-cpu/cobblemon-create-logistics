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
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder(CobblemonCreateLogistics.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChunkLifecycleGameTests {
    @GameTest(template = "empty", timeoutTicks = 120, batch = "chunkLifecycle")
    public static void chunkLifecycleDefersNativeNavigationCleanup(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Separate from the other fixtures so their chunk tickets cannot mask
        // the lifecycle behavior under test.
        BlockPos sourcePos = new BlockPos(22008, 80, 14008);
        BlockPos island = sourcePos.east(64).above(10);
        force(level, sourcePos, true);
        force(level, island, true);
        level.setBlockAndUpdate(sourcePos, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(sourcePos.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(sourcePos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(sourcePos);
        Pokemon pokemon = new Pokemon();
        pokemon.setSpecies(PokemonSpecies.getByName("charizard"));
        var pc = Cobblemon.INSTANCE.getStorage().getPC(hub.storageUuid(), level.registryAccess());
        helper.assertTrue(pc.add(pokemon), "courier stored in its native PC");
        UUID tether = UUID.randomUUID();
        pokemon.setTetheringId(tether);
        WorkerLease worker = new WorkerLease(UUID.randomUUID(), pokemon.getUuid(),
                pokemon.getSpecies().getResourceIdentifier(), level.dimension(), sourcePos, tether, hub.storageUuid());
        helper.assertTrue(hub.assignWorker(worker) >= 0 && ResidentWorkerRuntime.ensure(level, worker),
                "native courier assigned and spawned");
        PokemonEntity entity = pokemon.getEntity();
        UUID taskId = UUID.randomUUID();
        helper.assertTrue(CarrierRuntimeLease.acquire(level, taskId, worker, CarrierProfiles.resolve(pokemon),
                PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)))).isPresent(), "courier acquired");
        CarrierRuntimeLease.startCarrying(taskId);
        CarrierRuntimeLease.setRouteDestination(taskId, sourcePos.east(4096));
        CarrierRuntimeLease.enableRemoteMotion(taskId);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(RouteChunks.canTravel(level, sourcePos) && RouteChunks.canTravel(level, island),
                    "source and island still report ticking during the simulated lifecycle callback");
            entity.teleportTo(island.getX() + 0.5D, island.getY(), island.getZ() + 0.5D);
            Path nativePath = new Path(List.of(new Node(island.getX() + 1, island.getY(), island.getZ())),
                    island.east(), true);
            entity.getNavigation().moveTo(nativePath, 1.0D);
            helper.assertTrue(entity.getNavigation().getPath() == nativePath, "native navigation has an active path");
            entity.setDeltaMovement(new Vec3(0.5D, 0.1D, 0.0D));
            CarrierRuntimeLease.beforeChunkUnload(level, entity.chunkPosition());
            helper.assertTrue(CarrierRuntimeLease.isRemoteGapActive(taskId) && entity.isInvisible()
                            && entity.noPhysics
                            && entity.position().distanceToSqr(ResidentWorkerRuntime.interactionPosition(sourcePos)) < 1.0D
                            && entity.getDeltaMovement().equals(Vec3.ZERO),
                    "lifecycle callback parks the same courier and clears movement");
            helper.assertTrue(entity.getNavigation().getPath() == nativePath,
                    "no terrain-reading native navigation cleanup occurs inside the island callback");
            CarrierRuntimeLease.beforeChunkUnload(level, entity.chunkPosition());
            helper.assertTrue(entity.getNavigation().getPath() == nativePath,
                    "an unloading source also defers native navigation cleanup");
            CarrierRuntimeLease.protectRemoteJourneys(level);
            helper.assertTrue(entity.getNavigation().isDone() && pokemon.getEntity() == entity && entity.isAlive(),
                    "the next safe level tick finishes navigation cleanup without replacing the Pokemon");
            helper.assertTrue(!level.hasChunkAt(sourcePos.east(4096)), "the remote destination stays unloaded");
            CarrierRuntimeLease.release(taskId);
            helper.assertTrue(!entity.noPhysics, "the parked courier no longer blocks the Hub or player");
            ResidentWorkerRuntime.release(worker, level);
            force(level, sourcePos, false);
            force(level, island, false);
            helper.succeed();
        });
    }

    private static void force(ServerLevel level, BlockPos pos, boolean forced) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            level.setChunkForced((pos.getX() >> 4) + dx, (pos.getZ() >> 4) + dz, forced);
        }
    }
}
