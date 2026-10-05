package dev.cobblemoncreate.logistics.test;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageStyles;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import io.netty.buffer.Unpooled;
import dev.cobblemoncreate.logistics.CobblemonCreateLogistics;
import dev.cobblemoncreate.logistics.DeliveryState;
import dev.cobblemoncreate.logistics.DeliveryTask;
import dev.cobblemoncreate.logistics.DeliveryTaskSavedData;
import dev.cobblemoncreate.logistics.PauseReason;
import dev.cobblemoncreate.logistics.hub.CobblemonHubBlockEntity;
import dev.cobblemoncreate.logistics.hub.HubMenu;
import dev.cobblemoncreate.logistics.registry.LogisticsBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(CobblemonCreateLogistics.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HubPackageGameTests {
    private static final BlockPos HUB = new BlockPos(1, 1, 1);

    private HubPackageGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void emptyPackagerDoesNotBlockAnotherSide(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos hubPos = helper.absolutePos(HUB);
        level.setBlockAndUpdate(hubPos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var packager = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:packager"));
        level.setBlockAndUpdate(hubPos.below(), packager.defaultBlockState());
        level.setBlockAndUpdate(hubPos.above(), packager.defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(hubPos);
        var empty = (PackagerBlockEntity) level.getBlockEntity(hubPos.below());
        var full = (PackagerBlockEntity) level.getBlockEntity(hubPos.above());
        ItemStack box = PackageStyles.getDefaultBox();
        PackageItem.addAddress(box, "unmatched-test-address");
        full.inventory.setStackInSlot(0, box);

        helper.runAfterDelay(15, () -> {
            var data = DeliveryTaskSavedData.get(level.getServer());
            helper.assertTrue(empty.heldBox.isEmpty() && full.heldBox.isEmpty(),
                    "the source package was extracted despite the empty first side");
            helper.assertTrue(hub.taskIds().size() == 1, "one task owns the extracted package");
            DeliveryTask task = data.get(hub.taskIds().get(0));
            helper.assertTrue(task != null && PackageItem.getAddress(task.packageStack()).equals("unmatched-test-address"),
                    "the original Create address is retained");
            ItemStack returned = hub.collectAvailablePackage();
            helper.assertTrue(ItemStack.isSameItemSameComponents(box, returned)
                    && data.get(task.id()) == null && hub.taskIds().isEmpty()
                    && hub.collectAvailablePackage().isEmpty(), "reclaim transfers the one package exactly once");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void addresslessPackageUsesDefaultAddress(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos hubPos = helper.absolutePos(HUB);
        level.setBlockAndUpdate(hubPos, LogisticsBlocks.COBBLEMON_HUB.get().defaultBlockState());
        var hub = (CobblemonHubBlockEntity) level.getBlockEntity(hubPos);
        helper.assertTrue(hub.addAddress("default-test-address"), "default address can be configured");
        helper.assertTrue(hub.acceptCreatePackage(PackageStyles.getDefaultBox(), level.dimension(), hubPos),
                "native Create package accepted");
        var data = DeliveryTaskSavedData.get(level.getServer());
        DeliveryTask task = data.get(hub.taskIds().get(0));
        helper.assertTrue(PackageItem.getAddress(task.packageStack()).equals("default-test-address"),
                "addressless package receives the Hub default");
        helper.assertTrue(!hub.collectAvailablePackage().isEmpty() && data.get(task.id()) == null,
                "the unrouteable package can be reclaimed without duplication");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void pausedCourierStatusSurvivesMenuSnapshot(GameTestHelper helper) {
        UUID workerId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        var worker = new HubMenu.Entry(workerId, "Eevee", 1, true, true, false, true,
                ResourceLocation.parse("cobblemon:eevee"), Set.of());
        var task = new HubMenu.TaskEntry(taskId, "B", DeliveryState.PAUSED,
                PauseReason.NO_PROGRESS, ResourceLocation.parse("create:cardboard_package_12x12"), "Eevee", false, true, false,
                new dev.cobblemoncreate.logistics.JourneyProgress("gap", 37, 143, 91, 1430, new BlockPos(1200, 82, -71)));
        var snapshot = new HubMenu.Snapshot("A", List.of("B"), 0, 0, 0, 1,
                List.of(), List.of(worker), List.of(task), 1, 0, 0);
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            HubMenu.writeSnapshot(buf, snapshot);
            helper.assertTrue(snapshot.equals(HubMenu.readSnapshot(buf)),
                    "paused worker and cause survive the real menu packet codec");
            helper.succeed();
        } finally {
            buf.release();
        }
    }
}
