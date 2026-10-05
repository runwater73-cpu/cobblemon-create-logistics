package dev.cobblemoncreate.logistics.hub;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.cobblemoncreate.logistics.CobblemonCreateLogistics;
import dev.cobblemoncreate.logistics.DeliveryState;
import dev.cobblemoncreate.logistics.DeliveryTaskManager;
import dev.cobblemoncreate.logistics.registry.LogisticsBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.List;

@GameTestHolder(CobblemonCreateLogistics.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HubAddressGameTests {
    private HubAddressGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void loadedMissingHubIsRemovedFromAddressIndex(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos missing = source.east(4);
        String address = "stale-" + UUID.randomUUID();
        HubAddressSavedData data = HubAddressSavedData.get(level.getServer());
        data.replaceHub(level.dimension(), missing, address);
        level.setBlockAndUpdate(missing, Blocks.AIR.defaultBlockState());

        helper.assertTrue(HubAddressRegistry.findDestination(
                        level.getServer(), level.dimension(), source, address) == null,
                "a loaded position without a Hub cannot dispatch a courier");
        helper.assertTrue(data.entries().stream().noneMatch(entry -> entry.pos().equals(missing)),
                "the stale address is pruned instead of being retried each second");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void staleNameIsRefreshedFromLiveHub(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos target = source.east(4);
        level.setBlockAndUpdate(target, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(target);
        String current = "current-" + UUID.randomUUID();
        String stale = "stale-" + UUID.randomUUID();
        hub.setStationAddress(current);
        HubAddressSavedData.get(level.getServer()).replaceHub(level.dimension(), target, stale);

        helper.assertTrue(HubAddressRegistry.findDestination(
                        level.getServer(), level.dimension(), source, stale) == null,
                "the saved name cannot override the live Hub address");
        helper.assertTrue(HubAddressRegistry.findDestination(
                        level.getServer(), level.dimension(), source, current) != null,
                "the live address is restored to the index");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void packageWaitsWhenOnlyDestinationWasRemoved(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos sourcePos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos oldTarget = sourcePos.east(4);
        level.setBlockAndUpdate(sourcePos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var source = (CobblemonHubBlockEntity) level.getBlockEntity(sourcePos);
        String address = "removed-" + UUID.randomUUID();
        HubAddressSavedData.get(level.getServer()).replaceHub(level.dimension(), oldTarget, address);
        ItemStack box = PackageItem.containing(List.of(new ItemStack(Items.DIAMOND)));
        PackageItem.addAddress(box, address);
        var task = source.acceptCreatePackageTask(box);
        helper.assertTrue(task != null && !task.hasCourierDestination(),
                "a missing Hub cannot become the package destination");
        DeliveryTaskManager.tick(level.getServer());
        helper.assertTrue(task.state() == DeliveryState.BUFFERED && task.worker() == null,
                "the package waits in its one source task without cycling a courier");
        helper.assertTrue(!source.collectAvailablePackage().isEmpty(),
                "the waiting package remains recoverable");
        helper.succeed();
    }
}
