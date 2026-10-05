package dev.cobblemoncreate.logistics.client;

import dev.cobblemoncreate.logistics.DeliveryState;
import dev.cobblemoncreate.logistics.JourneyProgress;
import dev.cobblemoncreate.logistics.hub.TerminalMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/** Compact read-only station directory for the handheld terminal. */
public final class TerminalScreen extends AbstractContainerScreen<TerminalMenu> {
    private static final int WIDTH = 430;
    private static final int HEIGHT = 270;
    private static final int INK = 0xFFF4F0E5;
    private static final int MUTED = 0xFFB7C3BE;
    private static final int COPPER = 0xFFF3C47C;
    private static final int PANEL = 0xE91D2A31;
    private static final int PANEL_DARK = 0xE9142026;
    private static final ResourceLocation BACKDROP = ResourceLocation.fromNamespaceAndPath(
            "cobblemon_create_logistics", "textures/gui/terminal.png");
    private int selected;
    private int stationScroll;
    private int parcelScroll;
    private double uiScale = 1.0D;

    public TerminalScreen(TerminalMenu menu, Inventory inventory, Component title) {
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
        selected = Math.clamp(menu.active(), 0, Math.max(0, menu.stations().size() - 1));
        stationScroll = Math.max(0, selected - 7);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBg(graphics, partialTick, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(BACKDROP, leftPos, topPos, 0, 0, WIDTH, HEIGHT, WIDTH, HEIGHT);
        for (int row = 0; row < 8 && stationScroll + row < menu.stations().size(); row++) {
            int i = stationScroll + row;
            int y = topPos + 70 + row * 22;
            if (i == selected) graphics.fill(leftPos + 16, y - 3, leftPos + 151, y + 17, 0xFF3D4B4A);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Component.translatable("gui.cobblemon_create_logistics.terminal_title"), 20, 18, INK, false);
        graphics.drawString(font, Component.translatable("gui.cobblemon_create_logistics.terminal_remote_readonly"),
                20, 31, MUTED, false);
        if (menu.stations().isEmpty()) {
            graphics.drawString(font, Component.translatable("gui.cobblemon_create_logistics.terminal_no_stations"),
                    24, 76, MUTED, false);
            return;
        }
        TerminalMenu.Station station = menu.stations().get(selected);
        parcelScroll = Math.min(parcelScroll, Math.max(0, station.parcels().size() - 4));
        graphics.drawString(font, Component.translatable("gui.cobblemon_create_logistics.terminal_station_list"),
                18, 57, COPPER, false);
        graphics.drawString(font, Component.translatable("gui.cobblemon_create_logistics.terminal_details"),
                174, 57, COPPER, false);
        for (int row = 0; row < 8 && stationScroll + row < menu.stations().size(); row++) {
            int i = stationScroll + row;
            TerminalMenu.Station entry = menu.stations().get(i);
            int y = 72 + row * 22;
            String marker = menu.isActive(i) ? "* " : "  ";
            graphics.drawString(font, marker + (i + 1) + "  " + shortDimension(entry.binding().dimension().toString()),
                    20, y, i == selected ? INK : MUTED, false);
            graphics.drawString(font, fit(entry.binding().pos().toShortString(), 126), 20, y + 10, MUTED, false);
        }
        graphics.drawString(font, Component.translatable("gui.cobblemon_create_logistics.terminal_station_number", selected + 1,
                menu.stations().size()), 174, 72, INK, false);
        graphics.drawString(font, fit(Component.translatable("gui.cobblemon_create_logistics.terminal_dimension",
                station.binding().dimension().toString()).getString(), 235), 174, 87, MUTED, false);
        graphics.drawString(font, Component.translatable("gui.cobblemon_create_logistics.terminal_position",
                station.binding().pos().getX(), station.binding().pos().getY(), station.binding().pos().getZ()), 174, 101, MUTED, false);
        graphics.drawString(font, Component.translatable(station.loaded()
                ? "gui.cobblemon_create_logistics.terminal_loaded"
                : "gui.cobblemon_create_logistics.terminal_unloaded"), 174, 115,
                station.loaded() ? 0xFF8BC48D : 0xFFE5A36D, false);
        graphics.drawString(font, Component.translatable("gui.cobblemon_create_logistics.terminal_counts",
                station.queued(), station.transit(), station.ready()), 174, 132, INK, false);
        graphics.drawString(font, Component.translatable("gui.cobblemon_create_logistics.terminal_packages"),
                174, 153, COPPER, false);
        for (int row = 0; row < 4 && parcelScroll + row < station.parcels().size(); row++) {
            TerminalMenu.Parcel parcel = station.parcels().get(parcelScroll + row);
            int y = 166 + row * 19;
            graphics.drawString(font, fit(parcel.address(), 148), 174, y, INK, false);
            graphics.drawString(font, status(parcel), 325, y, MUTED, false);
            if (parcel.progress().remainingSeconds() >= 0) {
                graphics.drawString(font, Component.translatable("gui.cobblemon_create_logistics.terminal_eta",
                        parcel.progress().remainingSeconds()), 325, y + 9, COPPER, false);
            }
        }
        if (station.parcels().isEmpty()) {
            graphics.drawString(font, Component.translatable("gui.cobblemon_create_logistics.no_packages"),
                    174, 168, MUTED, false);
        }
    }

    private String fit(String value, int width) {
        if (font.width(value) <= width) return value;
        String ellipsis = "...";
        while (value.length() > 1 && font.width(value + ellipsis) > width) value = value.substring(0, value.length() - 1);
        return value + ellipsis;
    }

    private static String shortDimension(String value) {
        int slash = value.indexOf(':');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private Component status(TerminalMenu.Parcel parcel) {
        String key = switch (parcel.state()) {
            case BUFFERED -> "gui.cobblemon_create_logistics.terminal_queued";
            case CLAIMED_BY_WORKER, IN_TRANSIT, PAUSED -> "gui.cobblemon_create_logistics.terminal_transit";
            case ARRIVED_BUFFERED -> "gui.cobblemon_create_logistics.terminal_ready";
            default -> "gui.cobblemon_create_logistics.terminal_unknown";
        };
        return Component.translatable(key);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int virtualMouseX = (int) Math.round(mouseX / uiScale);
        int virtualMouseY = (int) Math.round(mouseY / uiScale);
        graphics.pose().pushPose();
        graphics.pose().scale((float) uiScale, (float) uiScale, 1.0F);
        super.render(graphics, virtualMouseX, virtualMouseY, partialTick);
        renderTooltip(graphics, virtualMouseX, virtualMouseY);
        graphics.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        mouseX /= uiScale;
        mouseY /= uiScale;
        if (button == 0) {
            int x = (int) mouseX - leftPos;
            int y = (int) mouseY - topPos;
            if (x >= 16 && x < 153 && y >= 68 && y < 68 + Math.min(8, menu.stations().size() - stationScroll) * 22) {
                selected = Math.clamp(stationScroll + (y - 68) / 22, 0, menu.stations().size() - 1);
                parcelScroll = 0;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        mouseX /= uiScale;
        mouseY /= uiScale;
        int x = (int) mouseX - leftPos;
        int y = (int) mouseY - topPos;
        if (x >= 10 && x < 157 && y >= 51 && y < HEIGHT - 10 && scrollY != 0) {
            stationScroll = Math.clamp(stationScroll - (int) Math.signum(scrollY), 0,
                    Math.max(0, menu.stations().size() - 8));
            return true;
        }
        if (x >= 166 && x < WIDTH - 10 && y >= 158 && y < HEIGHT - 10 && scrollY != 0
                && !menu.stations().isEmpty()) {
            parcelScroll = Math.clamp(parcelScroll - (int) Math.signum(scrollY), 0,
                    Math.max(0, menu.stations().get(selected).parcels().size() - 4));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
