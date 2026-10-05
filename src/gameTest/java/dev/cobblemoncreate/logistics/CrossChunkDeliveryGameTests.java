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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Real ticking/unloaded chunks, native Pokemon physics, and both halves of the trip. */
@GameTestHolder(CobblemonCreateLogistics.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CrossChunkDeliveryGameTests {
    @GameTest(template = "empty", timeoutTicks = 3000, batch = "crossChunkRoundTrip")
    public static void charizardFliesEveryLoadedLegAndReportsHome(GameTestHelper helper) {
        new RoundTrip(helper, "charizard", 12008).start();
    }

    @GameTest(template = "empty", timeoutTicks = 4000, batch = "crossChunkRoundTrip")
    public static void gastlyCrossesEveryLoadedLegAndReportsHome(GameTestHelper helper) {
        new RoundTrip(helper, "gastly", 14008).start();
    }

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "crossChunkRoundTrip")
    public static void mewtwoKeepsItsIdentityThroughoutTheRoundTrip(GameTestHelper helper) {
        new RoundTrip(helper, "mewtwo", 16008).start();
    }

    @GameTest(template = "empty", timeoutTicks = 3000, batch = "crossChunkRoundTrip")
    public static void unloadingTheCurrentIslandDoesNotStopTheCourier(GameTestHelper helper) {
        new RoundTrip(helper, "charizard", 18008, true).start();
    }


    private static final class RoundTrip {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final String species;
        private final BlockPos sourcePos;
        private final BlockPos island;
        private final BlockPos targetPos;
        private CobblemonHubBlockEntity source;
        private CobblemonHubBlockEntity target;
        private Pokemon pokemon;
        private PokemonEntity courier;
        private WorkerLease worker;
        private UUID taskId;
        private Vec3 previous;
        private boolean previousHidden;
        private boolean delivered;
        private boolean outboundIsland;
        private boolean returnIsland;
        private boolean returnDeparture;
        private boolean outboundGap;
        private boolean returnGap;
        private int count;
        private int round;
        private final boolean unloadIsland;
        private boolean islandRemoved;

        private RoundTrip(GameTestHelper helper, String species, int x) {
            this(helper, species, x, false);
        }

        private RoundTrip(GameTestHelper helper, String species, int x, boolean unloadIsland) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.species = species;
            this.unloadIsland = unloadIsland;
            sourcePos = new BlockPos(x, 80, 12008);
            island = sourcePos.east(160);
            targetPos = sourcePos.east(320);
        }

        private void start() {
            // No tickets along the route. These are three separate loaded islands.
            force(sourcePos, true);
            force(island, true);
            force(targetPos, true);
            placeHub(sourcePos);
            placeHub(targetPos);
            source = (CobblemonHubBlockEntity) level.getBlockEntity(sourcePos);
            target = (CobblemonHubBlockEntity) level.getBlockEntity(targetPos);
            target.setStationAddress("remote-" + species);
            helper.onEachTick(this::observe);
            helper.runAfterDelay(40, () -> {
                helper.assertTrue(RouteChunks.canTravel(level, sourcePos)
                        && RouteChunks.canTravel(level, targetPos)
                        && RouteChunks.canTravel(level, island), "all three forced islands tick entities");
                helper.assertTrue(!level.hasChunkAt(sourcePos.east(80))
                        && !level.hasChunkAt(sourcePos.east(240)), "the two middle gaps really are unloaded");
                pokemon = new Pokemon();
                pokemon.setSpecies(PokemonSpecies.getByName(species));
                var pc = Cobblemon.INSTANCE.getStorage().getPC(source.storageUuid(), level.registryAccess());
                helper.assertTrue(pc.add(pokemon), "one Pokemon in the Hub PC");
                UUID tether = UUID.randomUUID();
                pokemon.setTetheringId(tether);
                worker = new WorkerLease(UUID.randomUUID(), pokemon.getUuid(),
                        pokemon.getSpecies().getResourceIdentifier(), level.dimension(), sourcePos,
                        tether, source.storageUuid());
                helper.assertTrue(source.assignWorker(worker) >= 0, "the fresh Hub has room for its courier");
                helper.assertTrue(ResidentWorkerRuntime.ensure(level, worker), "native courier spawned");
                courier = pokemon.getEntity();
                send();
            });
        }

        private void send() {
            ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
            PackageItem.addAddress(box, target.address());
            helper.assertTrue(source.acceptCreatePackage(box, level.dimension(), sourcePos), "package accepted");
            taskId = source.taskIds().get(0);
            delivered = outboundIsland = returnIsland = returnDeparture = outboundGap = returnGap = false;
            previous = null;
        }

        private void observe() {
            if (courier == null) return;
            helper.assertTrue(pokemon.getEntity() == courier && courier.isAlive()
                    && level.getEntity(courier.getUUID()) == courier,
                    "the original live courier survives the whole round trip; pos=" + courier.position());
            helper.assertTrue(courier.getPokemon().getUuid().equals(worker.pokemonUuid())
                    && courier.getPokemon().getSpecies().getResourceIdentifier().equals(worker.species()),
                    "the assigned Pokemon identity and species never change");
            if (helper.getTick() % 20 == 0) {
                int instances = 0;
                for (var entity : level.getAllEntities()) {
                    if (entity instanceof PokemonEntity p && p.getPokemon().getUuid().equals(worker.pokemonUuid())) instances++;
                }
                helper.assertTrue(instances == 1, "exactly one live entity for this Pokemon, found " + instances);
            }
            boolean hidden = CarrierRuntimeLease.isRemoteGapActive(taskId);
            if (hidden) {
                if (delivered) returnGap = true; else outboundGap = true;
            }
            Vec3 pos = courier.position();
            if (previous != null && !previousHidden && !hidden) {
                helper.assertTrue(previous.distanceToSqr(pos) < 9,
                        "loaded legs cannot teleport: " + previous + " -> " + pos);
            }
            if (!hidden && Math.abs(pos.x - island.getX()) < 8) {
                if (delivered) returnIsland = true; else outboundIsland = true;
                if (unloadIsland && !islandRemoved) {
                    islandRemoved = true;
                    force(island, false);
                }
            }
            if (hidden) helper.assertTrue(!source.isWorkerHomeReported(worker.pokemonUuid()),
                    "parking a hidden courier at home does not report its virtual journey complete");
            if (delivered && !hidden && pos.x < targetPos.getX() - 8 && pos.x > targetPos.getX() - 24) {
                returnDeparture = true;
            }
            DeliveryTask task = DeliveryTaskSavedData.get(level.getServer()).get(taskId);
            if (!delivered && task != null && task.state() == DeliveryState.ARRIVED_BUFFERED) {
                helper.assertTrue(pos.distanceToSqr(ResidentWorkerRuntime.interactionPosition(targetPos)) < 9,
                        "arrival stays outside the destination Hub; no teleport home");
                helper.assertTrue(!source.hasTask(taskId) && target.hasTask(taskId)
                        && !source.isWorkerHomeReported(worker.pokemonUuid()), "arrival transfers cargo, not home status");
                helper.assertTrue(outboundIsland && outboundGap, "outbound flight visits the forced middle island");
                helper.assertTrue(PackageItem.isPackage(target.collectAvailablePackage())
                        && target.collectAvailablePackage().isEmpty(), "one package collected exactly once");
                count++;
                delivered = true;
                helper.assertTrue(CarrierRuntimeLease.isReturning(taskId), "return survives removal of the cargo task");
            }
            if (delivered && source.isWorkerHomeReported(worker.pokemonUuid())) {
                helper.assertTrue(returnDeparture && (returnIsland || unloadIsland) && returnGap,
                        "return physically departs destination and traverses the forced middle island");
                helper.assertTrue(pos.distanceToSqr(ResidentWorkerRuntime.interactionPosition(sourcePos)) < 9
                        && !courier.isInvisible() && !CarrierRuntimeLease.isWorkerActive(worker.pokemonUuid()),
                        "home interaction releases the same courier only at the source");
                if (++round == (unloadIsland ? 1 : 2)) {
                    helper.assertTrue(count == (unloadIsland ? 1 : 2), "each delivery uses one courier and one package");
                    helper.assertTrue(!level.hasChunkAt(sourcePos.east(80))
                            && !level.hasChunkAt(sourcePos.east(240)), "transport never loaded either gap");
                    if (unloadIsland) helper.assertTrue(!level.hasChunkAt(island),
                            "transport does not keep the removed island loaded");
                    ResidentWorkerRuntime.release(worker, level);
                    force(sourcePos, false);
                    force(island, false);
                    force(targetPos, false);
                    helper.succeed();
                    return;
                }
                send();
            }
            previous = pos;
            previousHidden = hidden;
            if (helper.getTick() % 200 == 0) {
                CobblemonCreateLogistics.LOGGER.info("Cross-chunk test {} round={} delivered={} pos={} gap={} task={}",
                        species, round, delivered, pos, hidden, task == null ? "collected" : task.state());
            }
        }

        private void placeHub(BlockPos pos) {
            // The GameTest world persists across launches. Reusing a Hub's old
            // six resident slots would prevent the new fixture from dispatching.
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                level.setBlockAndUpdate(pos.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
            }
            level.setBlockAndUpdate(pos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        }

        private void force(BlockPos pos, boolean forced) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                level.setChunkForced((pos.getX() >> 4) + dx, (pos.getZ() >> 4) + dz, forced);
            }
        }
    }
}
