package dev.cobblemoncreate.logistics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.mojang.math.Axis;
import dev.cobblemoncreate.logistics.CobblemonCreateLogistics;
import dev.cobblemoncreate.logistics.hub.CobblemonHubBlock;
import dev.cobblemoncreate.logistics.hub.CobblemonHubBlockEntity;
import dev.cobblemoncreate.logistics.hub.HubVisualState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ModelEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Cosmetic projection of real Create packages and committed transfer events. */
public final class CobblemonHubRenderer implements BlockEntityRenderer<CobblemonHubBlockEntity> {
    private static final List<Direction> PORTS = List.of(Direction.NORTH, Direction.EAST,
            Direction.SOUTH, Direction.WEST, Direction.DOWN);
    private static final List<String> BONES = List.of("body", "eye", "pupil", "nail_head",
            "nail_left_rotation", "nail_right_rotation", "magnet_left", "magnet_right");
    private static final Vec3 ACTOR_PIVOT = new Vec3(.5, 3.5 / 16, .5);
    private static final Vec3 BODY_PIVOT = new Vec3(.5, 5.885 / 16, .5);
    private static final Vec3 LEFT_MAGNET = new Vec3((8 - 2.12) / 16, 6.15 / 16, .5);
    private static final Vec3 RIGHT_MAGNET = new Vec3((8 + 2.12) / 16, 6.15 / 16, .5);
    private static final Vec3 HEAD_NAIL = new Vec3(.5, 8.27 / 16, .5);
    private static final float PACKAGE_SCALE = .52F;
    private static final Slot[] SLOTS = {
            slot(-5.3, 3.66, -5.4, -4, false),
            slot(5.5, 3.14, -5.4, 7, false),
            slot(-5.9, 3.40, 4.8, 5, false),
            slot(5.1, 3.66, 5.6, -8, false),
            slot(-5.5, 6.30, -5.0, 11, true),
            slot(6.0, 6.90, -3.0, -6, true),
            slot(-6.0, 7.20, 1.4, -9, true),
            slot(5.9, 7.10, 3.0, 6, true),
            slot(-2.3, 5.40, 6.1, 8, true),
            slot(1.0, 6.70, 6.3, -12, true),
            slot(-6.1, 10.50, -2.2, 9, true),
            slot(6.0, 10.70, -.7, 4, true),
            slot(-4.0, 11.50, 4.8, -7, true),
            slot(3.4, 11.60, 5.8, 8, true),
            slot(-.7, 13.70, 5.5, -10, true),
            slot(5.1, 14.50, 3.3, 12, true),
    };

    private final BlockRenderDispatcher blocks;
    private final ItemRenderer items;
    private final Map<CobblemonHubBlockEntity, Map<UUID, Integer>> placements = new WeakHashMap<>();

    public CobblemonHubRenderer(BlockEntityRendererProvider.Context context) {
        blocks = context.getBlockRenderDispatcher();
        items = context.getItemRenderer();
    }

    public static void registerModels(ModelEvent.RegisterAdditional event) {
        for (Direction side : PORTS) {
            event.register(model("contact_" + side.getName()));
            event.register(model("connected_" + side.getName()));
        }
        for (String bone : BONES) event.register(model("magnemite_" + bone));
    }

    @Override
    public void render(CobblemonHubBlockEntity hub, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        if (hub.getLevel() == null || hub.isRemoved()) return;
        double now = hub.getLevel().getGameTime() + partialTick;
        HubVisualState.Snapshot snapshot = hub.visualState();
        List<HubVisualState.TransferEvent> events = hub.visualEvents();
        Map<UUID, Integer> slots = updatePlacements(hub, snapshot.packages(), events);
        for (Direction side : PORTS) {
            renderPart(hub, (isPackagerAttached(hub, side) ? "connected_" : "contact_")
                    + side.getName(), pose, buffers, light, overlay);
        }
        HubVisualState.TransferEvent action = latestAction(events, now);
        renderMagnemite(hub, action, now, pose, buffers, light, overlay);
        renderBufferedPackages(hub, snapshot.packages(), slots, events, now, pose, buffers);
        for (HubVisualState.TransferEvent event : events)
            renderTransferPackage(hub, event, slots, now, pose, buffers);
    }

    private Map<UUID, Integer> updatePlacements(CobblemonHubBlockEntity hub,
                                                 List<HubVisualState.VisiblePackage> packages,
                                                 List<HubVisualState.TransferEvent> events) {
        Map<UUID, Integer> current = placements.computeIfAbsent(hub, ignored -> new HashMap<>());
        Set<UUID> active = new HashSet<>();
        for (HubVisualState.VisiblePackage entry : packages) active.add(entry.taskId());
        for (HubVisualState.TransferEvent event : events) active.add(event.taskId());
        current.keySet().retainAll(active);
        Set<Integer> occupied = new HashSet<>(current.values());
        for (HubVisualState.VisiblePackage entry : packages) {
            if (current.containsKey(entry.taskId())) continue;
            ItemStack stack = entry.stack();
            double size = PackageItem.getWidth(stack) * PackageItem.getHeight(stack);
            int chosen = -1;
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < SLOTS.length; i++) {
                if (occupied.contains(i)) continue;
                // Larger actual Create packages prefer the low, open positions.
                double score = i + (size > .4 && SLOTS[i].floating ? 8 : 0)
                        + Math.floorMod(entry.taskId().hashCode() ^ i * 71, 17) / 100.0;
                if (score < best) {
                    best = score;
                    chosen = i;
                }
            }
            if (chosen >= 0) {
                current.put(entry.taskId(), chosen);
                occupied.add(chosen);
            }
        }
        return current;
    }

    private void renderBufferedPackages(CobblemonHubBlockEntity hub,
                                        List<HubVisualState.VisiblePackage> packages,
                                        Map<UUID, Integer> slots,
                                        List<HubVisualState.TransferEvent> events,
                                        double now, PoseStack pose, MultiBufferSource buffers) {
        for (HubVisualState.VisiblePackage entry : packages) {
            Integer index = slots.get(entry.taskId());
            if (index == null || hiddenByTransfer(entry.taskId(), events, now)) continue;
            Slot slot = SLOTS[index];
            double bob = slot.floating
                    ? Math.sin(now * .075 + index * 1.7) * .010 : 0;
            renderPackage(hub, entry.stack(), entry.taskId(), slot.position.add(0, bob, 0),
                    slot.yaw, PACKAGE_SCALE, pose, buffers);
        }
    }

    private static boolean hiddenByTransfer(UUID taskId, List<HubVisualState.TransferEvent> events,
                                             double now) {
        for (HubVisualState.TransferEvent event : events) {
            if (!event.taskId().equals(taskId)) continue;
            double age = now - event.serverTick();
            if (age < 0) continue;
            if (event.kind() == HubVisualState.Kind.PACKAGER_INPUT && age < 10
                    || event.kind() == HubVisualState.Kind.COURIER_RECEIVE && age < 15
                    || event.kind() == HubVisualState.Kind.COURIER_PICKUP && age >= 5 && age < 20)
                return true;
        }
        return false;
    }

    private void renderTransferPackage(CobblemonHubBlockEntity hub,
                                       HubVisualState.TransferEvent event,
                                       Map<UUID, Integer> slots,
                                       double now, PoseStack pose, MultiBufferSource buffers) {
        ItemStack stack = event.packageStack();
        if (!PackageItem.isPackage(stack)) return;
        double age = now - event.serverTick();
        if (age < 0) return;
        Vec3 boundary = event.interactionPosition().subtract(Vec3.atLowerCornerOf(hub.getBlockPos()));
        Integer index = slots.get(event.taskId());
        if (index == null) index = reserveTransferSlot(event.taskId(), slots);
        Vec3 parked = index == null ? new Vec3(.5, .34, .5) : SLOTS[index].position;
        Vec3 point;
        double progress;
        switch (event.kind()) {
            case PACKAGER_INPUT -> {
                if (age >= 10) return;
                progress = smooth((age - 2) / 8);
                point = boundary.add(Vec3.atLowerCornerOf(event.side().getNormal()).scale(.16))
                        .lerp(parked, progress);
            }
            case PACKAGER_OUTPUT -> {
                if (age >= 10) return;
                progress = smooth((age - 2) / 8);
                point = parked.lerp(boundary, progress);
            }
            case COURIER_PICKUP -> {
                if (age >= 16) return;
                progress = smooth((age - 5) / 11);
                point = parked.lerp(boundary, progress);
            }
            case COURIER_RECEIVE -> {
                if (age >= 15) return;
                progress = smooth((age - 2) / 13);
                point = boundary.lerp(parked, progress);
            }
            default -> { return; }
        }
        renderPackage(hub, stack, event.taskId(), point, 0, PACKAGE_SCALE, pose, buffers);
    }

    /**
     * A pickup/output event is sent after the authoritative task leaves the
     * visual snapshot. Reserve the same deterministic free point for the
     * short-lived trajectory so the package starts at its actual parked spot.
     */
    private static Integer reserveTransferSlot(UUID taskId, Map<UUID, Integer> slots) {
        Set<Integer> occupied = new HashSet<>(slots.values());
        int start = Math.floorMod(taskId.hashCode(), SLOTS.length);
        for (int offset = 0; offset < SLOTS.length; offset++) {
            int index = (start + offset) % SLOTS.length;
            if (!occupied.contains(index)) {
                slots.put(taskId, index);
                return index;
            }
        }
        return start;
    }

    private void renderMagnemite(CobblemonHubBlockEntity hub, HubVisualState.TransferEvent action,
                                 double now, PoseStack pose, MultiBufferSource buffers,
                                 int light, int overlay) {
        int actorLight = LevelRenderer.getLightColor(hub.getLevel(), hub.getBlockPos().above());
        double bob = Math.sin(now * .08) * .009;
        float facing = 0;
        float spread = 0;
        float pitch = 0;
        float restingFacing = yaw(hub.getBlockState().getValue(CobblemonHubBlock.FACING));
        facing = restingFacing;
        if (action != null) {
            double age = now - action.serverTick();
            double strength = smooth(age / 5) * (1 - smooth((age - 15) / 7));
            float targetFacing = action.side().getAxis().isHorizontal() ? yaw(action.side()) : restingFacing;
            facing = restingFacing + angleDelta(restingFacing, targetFacing) * (float) strength;
            spread = (float)(26 * strength);
            pitch = (float)((action.side() == Direction.DOWN ? 14 : -9) * strength);
            bob += Math.sin(Math.PI * Mth.clamp(age / 22, 0, 1)) * .018;
        } else if (hub.visualState().packages().size() > 4) {
            facing += (float)(Math.sin(now * .035) * 13);
            spread = 4;
        }
        pose.pushPose();
        pose.translate(ACTOR_PIVOT.x, ACTOR_PIVOT.y + bob, ACTOR_PIVOT.z);
        pose.mulPose(Axis.YP.rotationDegrees(facing));
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.translate(-ACTOR_PIVOT.x, -ACTOR_PIVOT.y, -ACTOR_PIVOT.z);

        pose.pushPose();
        rotateAround(pose, BODY_PIVOT, Axis.ZP, (float)(Math.sin(now * .04) * 3));
        renderPart(hub, "magnemite_body", pose, buffers, actorLight, overlay);
        renderPart(hub, "magnemite_eye", pose, buffers, actorLight, overlay);
        if (Math.floorMod((long)now, 82) < 3) {
            pose.pushPose();
            pose.translate(0, 6.15 / 16, 0);
            pose.scale(1, .16F, 1);
            pose.translate(0, -6.15 / 16, 0);
            renderPart(hub, "magnemite_pupil", pose, buffers, actorLight, overlay);
            pose.popPose();
        } else renderPart(hub, "magnemite_pupil", pose, buffers, actorLight, overlay);
        rotateAround(pose, HEAD_NAIL, Axis.YP, (float)(-now * 13.5));
        renderPart(hub, "magnemite_nail_head", pose, buffers, actorLight, overlay);
        pose.popPose();

        renderPart(hub, "magnemite_nail_left_rotation", pose, buffers, actorLight, overlay);
        renderPart(hub, "magnemite_nail_right_rotation", pose, buffers, actorLight, overlay);
        renderMagnet(hub, "magnemite_magnet_left", LEFT_MAGNET, -spread,
                (float)(now * 13.5), pose, buffers, actorLight, overlay);
        renderMagnet(hub, "magnemite_magnet_right", RIGHT_MAGNET, spread,
                (float)(-now * 13.5), pose, buffers, actorLight, overlay);
        pose.popPose();
    }

    private void renderMagnet(CobblemonHubBlockEntity hub, String part, Vec3 pivot,
                              float spread, float spin, PoseStack pose,
                              MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        rotateAround(pose, pivot, Axis.ZP, spread);
        rotateAround(pose, pivot, Axis.XP, spin);
        renderPart(hub, part, pose, buffers, light, overlay);
        pose.popPose();
    }

    private static void rotateAround(PoseStack pose, Vec3 point, Axis axis, float degrees) {
        pose.translate(point.x, point.y, point.z);
        pose.mulPose(axis.rotationDegrees(degrees));
        pose.translate(-point.x, -point.y, -point.z);
    }

    private void renderPackage(CobblemonHubBlockEntity hub, ItemStack stack, UUID taskId,
                               Vec3 point, float yaw, float scale,
                               PoseStack pose, MultiBufferSource buffers) {
        pose.pushPose();
        pose.translate(point.x, point.y, point.z);
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.scale(scale, scale, scale);
        Vec3 world = point.add(Vec3.atLowerCornerOf(hub.getBlockPos()));
        int packageLight = LevelRenderer.getLightColor(hub.getLevel(), BlockPos.containing(world).above());
        items.renderStatic(stack, ItemDisplayContext.FIXED, packageLight, OverlayTexture.NO_OVERLAY,
                pose, buffers, hub.getLevel(), taskId.hashCode());
        pose.popPose();
    }

    private void renderPart(CobblemonHubBlockEntity hub, String part, PoseStack pose,
                            MultiBufferSource buffers, int light, int overlay) {
        var baked = Minecraft.getInstance().getModelManager().getModel(model(part));
        blocks.getModelRenderer().renderModel(pose.last(), buffers.getBuffer(RenderType.cutoutMipped()),
                hub.getBlockState(), baked, 1, 1, 1, light, overlay);
    }

    private static HubVisualState.TransferEvent latestAction(List<HubVisualState.TransferEvent> events,
                                                               double now) {
        HubVisualState.TransferEvent latest = null;
        for (HubVisualState.TransferEvent event : events) {
            double age = now - event.serverTick();
            if (age >= 0 && age < 22
                    && (latest == null || event.serverTick() > latest.serverTick())) latest = event;
        }
        return latest;
    }

    private static boolean isPackagerAttached(CobblemonHubBlockEntity hub, Direction side) {
        BlockPos adjacent = hub.getBlockPos().relative(side);
        if (!(hub.getLevel().getBlockEntity(adjacent) instanceof PackagerBlockEntity)) return false;
        var state = hub.getLevel().getBlockState(adjacent);
        return state.hasProperty(PackagerBlock.FACING)
                && state.getValue(PackagerBlock.FACING) == side.getOpposite();
    }

    private static float yaw(Direction side) {
        return switch (side) {
            case EAST -> -90;
            case SOUTH -> 180;
            case WEST -> 90;
            default -> 0;
        };
    }

    private static float angleDelta(float from, float to) {
        float delta = (to - from) % 360.0F;
        if (delta > 180.0F) delta -= 360.0F;
        if (delta < -180.0F) delta += 360.0F;
        return delta;
    }

    private static double smooth(double value) {
        double x = Mth.clamp(value, 0, 1);
        return x * x * (3 - 2 * x);
    }

    private static Slot slot(double x, double y, double z, float yaw, boolean floating) {
        return new Slot(new Vec3(.5 + x / 16, y / 16, .5 + z / 16), yaw, floating);
    }

    private record Slot(Vec3 position, float yaw, boolean floating) {}

    @Override
    public AABB getRenderBoundingBox(CobblemonHubBlockEntity hub) {
        return new AABB(hub.getBlockPos()).inflate(2);
    }

    private static ModelResourceLocation model(String part) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(
                CobblemonCreateLogistics.MOD_ID, "block/hub/" + part));
    }
}
