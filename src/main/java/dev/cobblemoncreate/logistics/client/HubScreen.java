package dev.cobblemoncreate.logistics.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.cobblemoncreate.logistics.DeliveryState;
import dev.cobblemoncreate.logistics.PauseReason;
import dev.cobblemoncreate.logistics.JourneyProgress;
import dev.cobblemoncreate.logistics.hub.CobblemonHubBlockEntity;
import dev.cobblemoncreate.logistics.hub.HubMenu;
import dev.cobblemoncreate.logistics.network.HubNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class HubScreen extends AbstractContainerScreen<HubMenu> {
    private static final int WIDTH = 512;
    private static final int HEIGHT = 360;
    private static final int INK = 0xFFF4F0E5;
    private static final int MUTED = 0xFFB7C3BE;
    private static final int COPPER = 0xFFF3C47C;
    private static final ResourceLocation BACKDROP = ResourceLocation.fromNamespaceAndPath(
            "cobblemon_create_logistics", "textures/gui/hub.png");
    private static final ResourceLocation NATIVE_BALL = ResourceLocation.fromNamespaceAndPath(
            "cobblemon", "textures/item/poke_balls/poke_ball.png");
    private static final ResourceLocation ARROW_PREVIOUS = ResourceLocation.fromNamespaceAndPath(
            "cobblemon", "textures/gui/pc/pc_arrow_previous.png");
    private static final ResourceLocation ARROW_NEXT = ResourceLocation.fromNamespaceAndPath(
            "cobblemon", "textures/gui/pc/pc_arrow_next.png");
    private static final ItemStack NATIVE_COG = new ItemStack(BuiltInRegistries.ITEM.get(
            ResourceLocation.fromNamespaceAndPath("create", "cogwheel")));
    private static final ItemStack NATIVE_PACKAGE = new ItemStack(BuiltInRegistries.ITEM.get(
            ResourceLocation.fromNamespaceAndPath("create", "cardboard_package_12x12")));

    private EditBox addressBox;
    private EditBox stationAddressBox;
    private double uiScale = 1.0D;
    private int selectedPc = -1;
    private UUID selectedPokemon;
    private UUID selectedTask;
    private int displayedBox = -1;
    private int displayedPage = -1;
    private boolean awaitingPcPage;
    private int expectedBox;
    private int expectedPage;
    private int taskScroll;
    private int addressScroll;
    private final HubPokemonPortraits portraits = new HubPokemonPortraits();
    private final Map<ResourceLocation, ItemStack> packageIcons = new HashMap<>();

    public HubScreen(HubMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        inventoryLabelY = HEIGHT + 1;
    }

    @Override
    protected void init() {
        super.init();
        uiScale = Math.min(1.0D, Math.min((width - 12.0D) / WIDTH, (height - 12.0D) / HEIGHT));
        leftPos = (int) Math.round((width / uiScale - WIDTH) / 2.0D);
        topPos = (int) Math.round((height / uiScale - HEIGHT) / 2.0D);
        addressBox = new EditBox(font, leftPos + 327, topPos + 278, 132, 12,
                Component.translatable("gui.cobblemon_create_logistics.destination_address_hint"));
        addressBox.setMaxLength(64);
        addressBox.setBordered(false);
        addressBox.setTextColor(INK);
        addressBox.setHint(Component.literal(fit(Component.translatable(
                "gui.cobblemon_create_logistics.destination_address_hint").getString(), addressBox.getWidth())));
        addRenderableWidget(addressBox);
        stationAddressBox = new EditBox(font, leftPos + 384, topPos + 243, 101, 12,
                Component.translatable("gui.cobblemon_create_logistics.station_address"));
        stationAddressBox.setMaxLength(64);
        stationAddressBox.setBordered(false);
        stationAddressBox.setTextColor(INK);
        stationAddressBox.setHint(Component.literal(fit(Component.translatable(
                "gui.cobblemon_create_logistics.station_address_hint").getString(), stationAddressBox.getWidth())));
        stationAddressBox.setValue(menu.stationAddress());
        addRenderableWidget(stationAddressBox);
        rebuildForSync();
    }

    public void rebuildForSync() {
        if (stationAddressBox != null && !stationAddressBox.isFocused()
                && !stationAddressBox.getValue().equals(menu.stationAddress())) {
            stationAddressBox.setValue(menu.stationAddress());
        }
        if (displayedBox != menu.boxIndex() || displayedPage != menu.pageIndex()) {
            selectedPokemon = null;
            displayedBox = menu.boxIndex();
            displayedPage = menu.pageIndex();
        }
        selectedPc = -1;
        for (int i = 0; selectedPokemon != null && i < menu.pcEntries().size(); i++) {
            HubMenu.Entry entry = menu.pcEntries().get(i);
            if (entry.level() > 0 && selectedPokemon.equals(entry.id())) { selectedPc = i; break; }
        }
        if (selectedPc < 0) selectedPokemon = null;
        if (selectedTask != null && menu.tasks().stream().noneMatch(task -> task.id().equals(selectedTask))) {
            selectedTask = null;
        }
        taskScroll = Math.min(taskScroll, Math.max(0, menu.tasks().size() - 4));
        addressScroll = Math.min(addressScroll, Math.max(0, menu.addresses().size() - 3));
        Set<UUID> visible = new HashSet<>();
        for (HubMenu.Entry entry : menu.pcEntries()) if (entry.level() > 0) visible.add(entry.id());
        for (HubMenu.Entry entry : menu.workers()) if (entry.level() > 0) visible.add(entry.id());
        portraits.retain(visible);
    }

    private int taskStart() { return Math.max(0, Math.min(taskScroll, Math.max(0, menu.tasks().size() - 4))); }
    private int addressStart() { return Math.max(0, Math.min(addressScroll, Math.max(0, menu.addresses().size() - 3))); }

    private void sendStationAddress() {
        if (stationAddressBox == null) return;
        String value = stationAddressBox.getValue().strip();
        if (value.length() <= 64 && !value.equals(menu.stationAddress())) {
            PacketDistributor.sendToServer(new HubNetwork.SetStationAddress(menu.pos(), value));
        }
    }

    private void click(int button) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
        }
    }

    public void acceptSnapshot(HubMenu.Snapshot snapshot) {
        menu.apply(snapshot);
        if (snapshot.boxIndex() == expectedBox && snapshot.pageIndex() == expectedPage) awaitingPcPage = false;
        rebuildForSync();
    }

    private void navigatePc(int button) {
        if (awaitingPcPage) return;
        selectedPc = -1;
        selectedPokemon = null;
        expectedBox = button == 60 ? Math.floorMod(menu.boxIndex() - 1, menu.boxCount())
                : button == 61 ? Math.floorMod(menu.boxIndex() + 1, menu.boxCount()) : menu.boxIndex();
        expectedPage = button == 60 || button == 61 ? 0 : Math.floorMod(menu.pageIndex() + (button == 62 ? -1 : 1), 2);
        awaitingPcPage = true;
        click(button);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Keep the world visible instead of applying the container's dark full-screen curtain.
        renderBg(graphics, partialTick, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.blit(BACKDROP, leftPos, topPos, 0, 0, WIDTH, HEIGHT, WIDTH, HEIGHT);
        graphics.blit(NATIVE_BALL, leftPos + 19, topPos + 12, 32, 32, 0, 0, 16, 16, 16, 16);
        graphics.blit(NATIVE_BALL, leftPos + 24, topPos + 63, 0, 0, 16, 16, 16, 16);
        RenderSystem.disableBlend();
        graphics.renderItem(NATIVE_PACKAGE, leftPos + 325, topPos + 62);
        graphics.renderItem(NATIVE_COG, leftPos + 322, topPos + 238);
        nativeArrow(graphics, ARROW_PREVIOUS, 231, 240, mouseX, mouseY);
        nativeArrow(graphics, ARROW_NEXT, 284, 240, mouseX, mouseY);
        nativeArrow(graphics, ARROW_PREVIOUS, 236, 324, mouseX, mouseY);
        nativeArrow(graphics, ARROW_NEXT, 283, 324, mouseX, mouseY);
        for (int row = 0; row < 4; row++) {
            int index = taskStart() + row;
            if (index >= menu.tasks().size()) break;
            HubMenu.TaskEntry task = menu.tasks().get(index);
            int y = topPos + 91 + row * 31;
            if (task.id().equals(selectedTask)) {
                graphics.renderOutline(leftPos + 323, topPos + 85 + row * 31, 166, 29, COPPER);
            }
            graphics.renderItem(packageIcons.computeIfAbsent(task.packageItem(), id ->
                    new ItemStack(BuiltInRegistries.ITEM.get(id))), leftPos + 330, y);
            graphics.fill(leftPos + 351, y, leftPos + 354, y + 17,
                    stateColor(task.state(), task.remoteTransit()));
            if (!task.progress().phase().isEmpty()) {
                graphics.fill(leftPos + 358, y + 21, leftPos + 481, y + 23, 0xFF263D48);
                graphics.fill(leftPos + 358, y + 21,
                        leftPos + 358 + task.progress().percent() * 123 / 100, y + 23, COPPER);
            }
        }
        for (int i = 0; i < menu.workers().size() && i < 6; i++) {
            HubMenu.Entry entry = menu.workers().get(i);
            int x = leftPos + 24 + (i % 3) * 94;
            int y = topPos + 86 + (i / 3) * 68;
            if (entry.level() > 0) {
                portraits.render(graphics, entry.id(), entry.species(), entry.aspects(),
                        x + 6, y + 4, 38, partialTick, uiScale);
            }
            graphics.fill(x + 7, y + 53, x + 83, y + 61,
                    entry.paused() ? 0xFFC47768 : entry.busy() ? 0xFF6295AA
                            : entry.returning() ? 0xFFE0AC62 : 0xFF78A36B);
        }
        if (!awaitingPcPage && selectedPc >= 0 && selectedPc < menu.pcEntries().size()) {
            HubMenu.Entry selected = menu.pcEntries().get(selectedPc);
            portraits.render(graphics, selected.id(), selected.species(), selected.aspects(),
                    leftPos + 36, topPos + 260, 48, partialTick, uiScale);
        }
        for (int i = 0; i < menu.pcEntries().size(); i++) {
            if (awaitingPcPage) break;
            HubMenu.Entry entry = menu.pcEntries().get(i);
            if (entry.level() == 0) continue;
            int x = leftPos + 154 + (i % 5) * 28;
            int y = topPos + 259 + (i / 5) * 20;
            if (i == selectedPc) {
                graphics.fill(x, y, x + 25, y + 1, COPPER);
                graphics.fill(x, y + 17, x + 25, y + 18, COPPER);
            }
            portraits.render(graphics, entry.id(), entry.species(), entry.aspects(),
                    x + 4, y + 1, 16, partialTick, uiScale);
        }
        actionFeedback(graphics, 323, 212, 166, 12, selectedTaskReclaimable(), mouseX, mouseY);
        actionFeedback(graphics, 24, 324, 164, 14, !awaitingPcPage && selectedPc >= 0 && menu.workers().size() < 6, mouseX, mouseY);
        actionFeedback(graphics, 466, 273, 23, 19, canAddAddress(), mouseX, mouseY);
    }

    private void nativeArrow(GuiGraphics graphics, ResourceLocation texture, int x, int y, int mouseX, int mouseY) {
        boolean hovered = inside(mouseX, mouseY, leftPos + x, topPos + y, 16, 14);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(texture, leftPos + x + 1, topPos + y, 0, hovered ? 14 : 0, 14, 14, 14, 28);
        RenderSystem.disableBlend();
    }

    private void actionFeedback(GuiGraphics graphics, int x, int y, int width, int height,
                                boolean enabled, int mouseX, int mouseY) {
        if (!enabled) {
            graphics.fill(leftPos + x + 2, topPos + y + 2, leftPos + x + width - 2,
                    topPos + y + height - 2, 0x88203440);
        } else if (inside(mouseX, mouseY, leftPos + x, topPos + y, width, height)) {
            graphics.renderOutline(leftPos + x, topPos + y, width, height, COPPER);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        centeredLabel(graphics, Component.translatable("gui.cobblemon_create_logistics.title").getString(),
                WIDTH / 2, 25, 288, INK);
        graphics.drawString(font, fit(Component.translatable("gui.cobblemon_create_logistics.workers",
                menu.workers().size(), CobblemonHubBlockEntity.MAX_WORKERS).getString(), 252), 46, 67, INK, false);
        graphics.drawString(font, fit(Component.translatable("gui.cobblemon_create_logistics.packages").getString(), 103),
                345, 67, INK, false);
        graphics.drawString(font, menu.tasks().size() + "/" + CobblemonHubBlockEntity.MAX_PACKAGES,
                458, 67, MUTED, false);
        graphics.drawString(font, fit(Component.translatable("gui.cobblemon_create_logistics.player_pc").getString(), 177),
                48, 244, INK, false);
        graphics.drawString(font, fit(Component.translatable("gui.cobblemon_create_logistics.station_short").getString(), 34),
                343, 244, INK, false);
        graphics.drawString(font, fit(Component.translatable("gui.cobblemon_create_logistics.destination_presets",
                menu.addresses().size()).getString(), 164), 324, 261, MUTED, false);
        for (int row = 0; row < 4; row++) {
            int index = taskStart() + row;
            if (index >= menu.tasks().size()) break;
            HubMenu.TaskEntry task = menu.tasks().get(index);
            int y = 91 + row * 31;
            graphics.drawString(font, fit(task.target(), 111), 358, y, INK, false);
            String detail = task.progress().phase().isEmpty()
                    ? stateLabel(task.state(), task.remoteTransit(), task.routeResolved())
                    : progressLabel(task.progress());
            graphics.drawString(font, fit(detail, 111), 358, y + 11,
                    stateColor(task.state(), task.remoteTransit()), false);
            if (!task.courierName().isBlank()) {
                graphics.drawCenteredString(font, "*", 480, y + 5, COPPER);
            }
        }
        if (menu.tasks().isEmpty()) {
            centeredLabel(graphics, Component.translatable("gui.cobblemon_create_logistics.no_packages").getString(),
                    406, 132, 148, MUTED);
        }
        centeredLabel(graphics, Component.translatable("gui.cobblemon_create_logistics.collect").getString(),
                406, 214, 154, selectedTaskReclaimable() ? 0xFF261B10 : MUTED);
        for (int i = 0; i < 6; i++) {
            int x = 24 + (i % 3) * 94;
            int y = 86 + (i / 3) * 68;
            if (i >= menu.workers().size()) {
                centeredLabel(graphics, Component.translatable("gui.cobblemon_create_logistics.empty").getString(),
                        x + 45, y + 53, 74, MUTED);
                continue;
            }
            HubMenu.Entry entry = menu.workers().get(i);
            centeredLabel(graphics, entry.name(), x + 45, y + 43, 78, INK);
            String workerState = Component.translatable(entry.paused()
                    ? "gui.cobblemon_create_logistics.paused"
                    : entry.busy() ? "gui.cobblemon_create_logistics.in_transit"
                    : entry.returning() ? "gui.cobblemon_create_logistics.returning"
                    : "gui.cobblemon_create_logistics.idle").getString();
            if (!entry.progress().phase().isEmpty()) {
                workerState = progressLabel(entry.progress());
                if (font.width(workerState) > 74 && entry.progress().remainingSeconds() >= 0) {
                    workerState = "~" + formatTime(entry.progress().remainingSeconds());
                }
            }
            centeredLabel(graphics, workerState, x + 45, y + 53, 74, 0xFF101819);
            if (entry.owned() && !entry.busy() && !entry.paused() && !entry.returning()) {
                graphics.fill(x + 73, y + 5, x + 85, y + 17, 0xFF263237);
                graphics.drawCenteredString(font, "×", x + 79, y + 7, COPPER);
            }
        }
        centeredLabel(graphics, (menu.boxIndex() + 1) + "/" + menu.boxCount(), 265, 243, 34, INK);
        centeredLabel(graphics, (menu.pageIndex() + 1) + "/2", 267, 327, 28, INK);
        if (selectedPc >= 0 && selectedPc < menu.pcEntries().size()) {
            HubMenu.Entry selected = menu.pcEntries().get(selectedPc);
            centeredLabel(graphics, selected.name(), 60, 311, 64, INK);
        }
        if (awaitingPcPage) centeredLabel(graphics, "...", 224, 283, 130, MUTED);
        centeredLabel(graphics, Component.translatable("gui.cobblemon_create_logistics.assign").getString(),
                106, 327, 152, !awaitingPcPage && selectedPc >= 0 && menu.workers().size() < 6 ? 0xFF2D2014 : MUTED);
        graphics.drawCenteredString(font, "+", 478, 279, canAddAddress() ? 0xFF2D2014 : 0xFF655443);
        int addressStart = addressStart();
        for (int row = 0; row < 3; row++) {
            int i = addressStart + row;
            if (i >= menu.addresses().size()) break;
            int y = 296 + row * 14;
            if (i == menu.defaultDestination()) {
                graphics.fill(326, y + 3, 328, y + 11, COPPER);
            }
            graphics.drawString(font, fit(menu.addresses().get(i), 113), 331, y + 3,
                    i == menu.defaultDestination() ? COPPER : INK, false);
            graphics.drawCenteredString(font, i == menu.defaultDestination() ? "*" : ">", 460, y + 3,
                    i == menu.defaultDestination() ? COPPER : MUTED);
            graphics.drawCenteredString(font, "x", 479, y + 3, MUTED);
        }
    }

    private String stateLabel(DeliveryState state, boolean remoteTransit, boolean routeResolved) {
        if (remoteTransit) return Component.translatable("gui.cobblemon_create_logistics.remote_transit").getString();
        if (state == DeliveryState.BUFFERED && !routeResolved) {
            return Component.translatable("gui.cobblemon_create_logistics.address_unmatched").getString();
        }
        return Component.translatable(switch (state) {
            case BUFFERED -> "gui.cobblemon_create_logistics.waiting";
            case CLAIMED_BY_WORKER -> "gui.cobblemon_create_logistics.pickup";
            case IN_TRANSIT -> "gui.cobblemon_create_logistics.in_transit";
            case ARRIVED_BUFFERED -> "gui.cobblemon_create_logistics.arrived";
            case PAUSED -> "gui.cobblemon_create_logistics.paused";
            default -> "gui.cobblemon_create_logistics.waiting";
        }).getString();
    }

    private String progressLabel(JourneyProgress progress) {
        String phase = Component.translatable("gui.cobblemon_create_logistics.journey." + progress.phase()).getString();
        return phase + (progress.remainingSeconds() < 0 ? ""
                : " ~" + formatTime(progress.remainingSeconds()));
    }

    private static String formatTime(int seconds) {
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private java.util.List<Component> progressTooltip(Component first, JourneyProgress progress) {
        java.util.List<Component> lines = new java.util.ArrayList<>();
        lines.add(first);
        if (progress.phase().isEmpty()) return lines;
        lines.add(Component.literal(progressLabel(progress)));
        if (progress.phase().equals("arrived")) return lines;
        lines.add(Component.translatable("gui.cobblemon_create_logistics.journey.detail",
                progress.percent(), progress.remainingBlocks(), formatTime(progress.elapsedSeconds())));
        var position = progress.position();
        boolean virtual = progress.phase().equals("gap") || progress.phase().equals("return_gap")
                || progress.phase().equals("waiting_endpoint");
        lines.add(Component.translatable(virtual ? "gui.cobblemon_create_logistics.journey.virtual_position"
                        : "gui.cobblemon_create_logistics.journey.position",
                position.getX(), position.getY(), position.getZ()));
        return lines;
    }

    private static int stateColor(DeliveryState state, boolean remoteTransit) {
        if (remoteTransit) return 0xFFB98AD0;
        return switch (state) {
            case IN_TRANSIT -> 0xFF80BBDC;
            case ARRIVED_BUFFERED -> 0xFFF0C45D;
            case PAUSED -> 0xFFE58070;
            default -> 0xFF8AC97A;
        };
    }

    private String fit(String value, int width) {
        if (font.width(value) <= width) return value;
        int codePoints = value.codePointCount(0, value.length());
        while (codePoints > 0) {
            String prefix = value.substring(0, value.offsetByCodePoints(0, --codePoints));
            if (font.width(prefix + "...") <= width) return prefix + "...";
        }
        return "...";
    }

    private void centeredLabel(GuiGraphics graphics, String value, int x, int y, int width, int color) {
        String fitted = fit(value, width);
        graphics.drawString(font, fitted, x - font.width(fitted) / 2, y, color, false);
    }

    private boolean canAddAddress() {
        String value = addressBox == null ? "" : addressBox.getValue().strip();
        return !value.isEmpty() && value.length() <= 64
                && (menu.addresses().contains(value)
                || menu.addresses().size() < CobblemonHubBlockEntity.MAX_ADDRESS_PRESETS);
    }

    private boolean selectedTaskReclaimable() {
        return selectedTask != null && menu.tasks().stream()
                .anyMatch(task -> task.id().equals(selectedTask) && task.reclaimable());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int virtualMouseX = (int) Math.round(mouseX / uiScale);
        int virtualMouseY = (int) Math.round(mouseY / uiScale);
        graphics.pose().pushPose();
        graphics.pose().scale((float) uiScale, (float) uiScale, 1.0F);
        super.render(graphics, virtualMouseX, virtualMouseY, partialTick);
        for (int row = 0; row < 4; row++) {
            int index = taskStart() + row;
            if (index >= menu.tasks().size()) break;
            HubMenu.TaskEntry task = menu.tasks().get(index);
            if (inside(virtualMouseX, virtualMouseY,
                    leftPos + 323, topPos + 85 + row * 31, 166, 29)) {
                if (!task.courierName().isBlank() && inside(virtualMouseX, virtualMouseY,
                        leftPos + 473, topPos + 85 + row * 31, 16, 29)) {
                    graphics.renderTooltip(font, Component.translatable(
                            "gui.cobblemon_create_logistics.highlight_courier"), virtualMouseX, virtualMouseY);
                    break;
                }
                Component tooltip = task.courierName().isBlank()
                        ? Component.translatable("gui.cobblemon_create_logistics.courier_waiting")
                        : Component.translatable("gui.cobblemon_create_logistics.courier", task.courierName());
                if (task.state() == DeliveryState.PAUSED) {
                    tooltip = tooltip.copy().append(" · ").append(
                            Component.translatable(pauseReasonKey(task.pauseReason())));
                }
                var lines = progressTooltip(tooltip, task.progress());
                lines.add(0, Component.literal(task.target()));
                graphics.renderComponentTooltip(font, lines, virtualMouseX, virtualMouseY);
                break;
            }
        }
        for (int i = 0; !awaitingPcPage && i < menu.pcEntries().size(); i++) {
            int x = leftPos + 154 + (i % 5) * 28;
            int y = topPos + 259 + (i / 5) * 20;
            HubMenu.Entry entry = menu.pcEntries().get(i);
            if (entry.level() > 0 && inside(virtualMouseX, virtualMouseY, x, y, 25, 18)) {
                graphics.renderTooltip(font, Component.literal(entry.name() + " Lv." + entry.level()),
                        virtualMouseX, virtualMouseY);
            }
        }
        for (int i = 0; i < menu.workers().size() && i < 6; i++) {
            HubMenu.Entry entry = menu.workers().get(i);
            int x = leftPos + 24 + (i % 3) * 94;
            int y = topPos + 86 + (i / 3) * 68;
            if (inside(virtualMouseX, virtualMouseY, x, y, 90, 64)
                    && !inside(virtualMouseX, virtualMouseY, x + 73, y + 5, 12, 12)) {
                graphics.renderComponentTooltip(font, progressTooltip(
                        Component.literal(entry.name() + " Lv." + entry.level()), entry.progress()),
                        virtualMouseX, virtualMouseY);
            }
            if (entry.owned() && !entry.busy() && !entry.paused() && !entry.returning()
                    && inside(virtualMouseX, virtualMouseY, x + 73, y + 5, 12, 12)) {
                graphics.renderTooltip(font, Component.translatable(
                        "gui.cobblemon_create_logistics.remove_worker"), virtualMouseX, virtualMouseY);
            }
        }
        if (selectedPc >= 0 && inside(virtualMouseX, virtualMouseY, leftPos + 24, topPos + 259, 72, 61)) {
            HubMenu.Entry entry = menu.pcEntries().get(selectedPc);
            graphics.renderTooltip(font, Component.literal(entry.name() + " Lv." + entry.level()),
                    virtualMouseX, virtualMouseY);
        }
        if (inside(virtualMouseX, virtualMouseY, leftPos + 466, topPos + 273, 23, 19)
                && !canAddAddress()
                && menu.addresses().size() >= CobblemonHubBlockEntity.MAX_ADDRESS_PRESETS) {
            graphics.renderTooltip(font, Component.translatable("gui.cobblemon_create_logistics.address_full"),
                    virtualMouseX, virtualMouseY);
        }
        int addressStart = addressStart();
        for (int row = 0; row < 3; row++) {
            int i = addressStart + row;
            if (i >= menu.addresses().size()) break;
            int y = topPos + 296 + row * 14;
            if (inside(virtualMouseX, virtualMouseY, leftPos + 323, y, 127, 13)) {
                graphics.renderTooltip(font, Component.literal(menu.addresses().get(i)), virtualMouseX, virtualMouseY);
                break;
            }
            String key = inside(virtualMouseX, virtualMouseY, leftPos + 470, y, 19, 13)
                    ? "gui.cobblemon_create_logistics.delete_address"
                    : inside(virtualMouseX, virtualMouseY, leftPos + 450, y, 20, 13)
                    ? "gui.cobblemon_create_logistics.default_destination" : null;
            if (key != null) {
                graphics.renderTooltip(font, Component.translatable(key), virtualMouseX, virtualMouseY);
                break;
            }
        }
        renderTooltip(graphics, virtualMouseX, virtualMouseY);
        graphics.pose().popPose();
    }

    private boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static String pauseReasonKey(PauseReason reason) {
        return switch (reason) {
            case ROUTE_UNAVAILABLE -> "gui.cobblemon_create_logistics.pause_route";
            case CHUNKS_UNLOADED -> "gui.cobblemon_create_logistics.pause_chunks";
            case CARRIER_UNAVAILABLE -> "gui.cobblemon_create_logistics.pause_carrier";
            case NO_PROGRESS -> "gui.cobblemon_create_logistics.pause_progress";
            case UNKNOWN -> "gui.cobblemon_create_logistics.pause_unknown";
        };
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        mouseX /= uiScale;
        mouseY /= uiScale;
        if (stationAddressBox != null && stationAddressBox.isFocused()
                && !inside(mouseX, mouseY, leftPos + 380, topPos + 239, 109, 17)) {
            sendStationAddress();
            stationAddressBox.setFocused(false);
        }
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int x = leftPos;
        int y = topPos;
        if (inside(mouseX, mouseY, x + 466, y + 273, 23, 19)) {
            if (canAddAddress()) {
                PacketDistributor.sendToServer(new HubNetwork.AddAddress(menu.pos(), addressBox.getValue()));
                addressBox.setValue("");
                addressBox.setFocused(false);
            }
            return true;
        }
        int addressStart = addressStart();
        for (int row = 0; row < 3; row++) {
            int i = addressStart + row;
            if (i >= menu.addresses().size()) break;
            int rowY = y + 296 + row * 14;
            if (inside(mouseX, mouseY, x + 470, rowY, 19, 13)) {
                click(800 + i);
                return true;
            }
            if (inside(mouseX, mouseY, x + 450, rowY, 20, 13)) {
                click(900 + i);
                return true;
            }
            if (inside(mouseX, mouseY, x + 323, rowY, 127, 13)) return true;
        }
        if (inside(mouseX, mouseY, x + 323, y + 212, 166, 12) && selectedTaskReclaimable()) {
            PacketDistributor.sendToServer(new HubNetwork.CollectPackage(menu.pos(), selectedTask));
            return true;
        }
        for (int row = 0; row < 4; row++) {
            int index = taskStart() + row;
            if (index >= menu.tasks().size()) break;
            if (inside(mouseX, mouseY, x + 323, y + 85 + row * 31, 166, 29)) {
                HubMenu.TaskEntry task = menu.tasks().get(index);
                if (!task.courierName().isBlank() && inside(mouseX, mouseY,
                        x + 473, y + 85 + row * 31, 16, 29)) {
                    PacketDistributor.sendToServer(new HubNetwork.HighlightCourier(menu.pos(), task.id()));
                } else {
                    selectedTask = task.id();
                }
                return true;
            }
        }
        if (inside(mouseX, mouseY, x + 24, y + 324, 164, 14)
                && !awaitingPcPage && selectedPc >= 0 && menu.workers().size() < CobblemonHubBlockEntity.MAX_WORKERS) {
            click(30 + selectedPc);
            selectedPc = -1;
            selectedPokemon = null;
            return true;
        }
        // Hit boxes match the new PC navigation buttons.
        if (inside(mouseX, mouseY, x + 231, y + 240, 16, 14)) { navigatePc(60); return true; }
        if (inside(mouseX, mouseY, x + 284, y + 240, 16, 14)) { navigatePc(61); return true; }
        if (inside(mouseX, mouseY, x + 236, y + 324, 16, 14)) { navigatePc(62); return true; }
        if (inside(mouseX, mouseY, x + 283, y + 324, 16, 14)) { navigatePc(63); return true; }
        for (int i = 0; !awaitingPcPage && i < menu.pcEntries().size(); i++) {
            int sx = x + 154 + (i % 5) * 28;
            int sy = y + 259 + (i / 5) * 20;
            if (inside(mouseX, mouseY, sx, sy, 25, 18) && menu.pcEntries().get(i).level() > 0) {
                selectedPc = i;
                selectedPokemon = menu.pcEntries().get(i).id();
                return true;
            }
        }
        for (int i = 0; i < menu.workers().size() && i < 6; i++) {
            HubMenu.Entry entry = menu.workers().get(i);
            int sx = x + 24 + (i % 3) * 94;
            int sy = y + 86 + (i / 3) * 68;
            if (entry.owned() && !entry.busy() && !entry.paused() && !entry.returning()
                    && inside(mouseX, mouseY, sx + 73, sy + 5, 12, 12)) {
                click(10 + i);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        mouseX /= uiScale;
        mouseY /= uiScale;
        if (scrollY == 0) return false;
        if (inside(mouseX, mouseY, leftPos + 323, topPos + 85, 166, 124)) {
            int max = Math.max(0, menu.tasks().size() - 4);
            taskScroll = (int) Math.max(0, Math.min(max, taskScroll - Math.signum(scrollY)));
            return true;
        }
        if (inside(mouseX, mouseY, leftPos + 323, topPos + 296, 166, 42)) {
            int max = Math.max(0, menu.addresses().size() - 3);
            addressScroll = (int) Math.max(0, Math.min(max, addressScroll - Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (stationAddressBox != null && stationAddressBox.isFocused()
                && (keyCode == 257 || keyCode == 335)) {
            sendStationAddress();
            stationAddressBox.setFocused(false);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return super.mouseReleased(mouseX / uiScale, mouseY / uiScale, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return super.mouseDragged(mouseX / uiScale, mouseY / uiScale, button,
                dragX / uiScale, dragY / uiScale);
    }
}
