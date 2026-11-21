package com.mtm.citybuilder.client.map;

import com.mtm.citybuilder.core.debug.PlanTile;
import com.mtm.citybuilder.map.CityMapNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The city map: the towns' plan round the player, drawn by the server tile by tile ({@link PlanTile}). Drag to pan,
 * scroll or +/- to zoom (about the cursor), click to inspect a spot (its plan in the panel, its coordinates copied as
 * {@code x z}), C to centre on the player, L for the legend, R to draw it all again; the key or Esc closes it.
 * Zoomed in to two blocks a pixel or closer, lots and buildings show; zoomed out, the districts.
 */
public final class CityMapScreen extends Screen {
    /** Blocks a screen pixel: 2^zoom, from an eighth to 16. */
    private static final int MIN_ZOOM = -3;
    private static final int MAX_ZOOM = 4;

    private static final Object[][] LEGEND = {
            {0x78003C, "boulevard"}, {0x0F0F0F, "arterial (old town)"}, {0x1E2878, "arterial (modern)"},
            {0x5064BE, "collector"}, {0x555555, "street"}, {0x7D7D78, "lane"}, {0x8C501E, "old road"},
            {0x28963C, "footpath"}, {0xF08214, "bridge"}, {0x00C8DC, "culvert"}, {0xDC28C8, "stairs"},
            {0x2896FF, "canal"}, {0x4682CD, "river, moat"}, {0x000000, "railway (white: at grade/elevated)"},
            {0x9628BE, "town line"}, {0x146E28, "high-speed reserve"}, {0x191919, "intercity road"},
            {0xDC2828, "downtown"}, {0xAA6E3C, "old town"}, {0xF5D778, "houses"}, {0x965ABE, "works"},
            {0x2896C8, "port"}, {0xFA821E, "station front"}, {0x3C8C3C, "grounds"},
            {0xF0A050, "shop (darker: taller)"}, {0xA080D0, "office"}, {0x80A8E0, "flats"}, {0xE8D8B0, "house"},
            {0xB08850, "machiya"}, {0xA0A0A0, "works"},
    };

    private double centerX;
    private double centerZ;
    private int zoom = 1;
    /** Whether the legend shows; off at first so the map is seen, remembered while the game runs. */
    private static boolean legend;
    private double pressX;
    private double pressY;
    private boolean dragged;

    public CityMapScreen() {
        super(Component.translatable("screen.citybuilder.map"));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            centerX = mc.player.getX();
            centerZ = mc.player.getZ();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private double guiScale() {
        return Minecraft.getInstance().getWindow().getGuiScale();
    }

    /** Blocks a physical pixel. */
    private double blocksPerPixel() {
        return Math.pow(2.0, zoom);
    }

    /** The scale tiles are asked for at: blocks a pixel, a power of two between 1 and 16. */
    private int tileScale() {
        return 1 << Math.max(0, Math.min(4, zoom));
    }

    private double worldX(double mouseX) {
        return centerX + (mouseX - width / 2.0) * guiScale() * blocksPerPixel();
    }

    private double worldZ(double mouseY) {
        return centerZ + (mouseY - height / 2.0) * guiScale() * blocksPerPixel();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xFF181818);
        double gs = guiScale();
        double bpp = blocksPerPixel();
        int scale = tileScale();
        double physW = width * gs;
        double physH = height * gs;
        double left = centerX - physW / 2.0 * bpp;
        double top = centerZ - physH / 2.0 * bpp;
        double span = (double) PlanTile.SIZE * scale;
        int tx0 = (int) Math.floor(left / span);
        int tz0 = (int) Math.floor(top / span);
        int tx1 = (int) Math.floor((left + physW * bpp) / span);
        int tz1 = (int) Math.floor((top + physH * bpp) / span);
        long now = System.currentTimeMillis();

        g.pose().pushPose();
        g.pose().scale((float) (1.0 / gs), (float) (1.0 / gs), 1.0f);
        List<int[]> missing = new ArrayList<>();
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int tz = tz0; tz <= tz1; tz++) {
                int x0 = (int) Math.round((tx * span - left) / bpp);
                int x1 = (int) Math.round(((tx + 1) * span - left) / bpp);
                int y0 = (int) Math.round((tz * span - top) / bpp);
                int y1 = (int) Math.round(((tz + 1) * span - top) / bpp);
                CityMapClient.Tile tile = CityMapClient.tile(scale, tx, tz);
                if (tile != null && tile.texture != null) {
                    g.blit(tile.texture, x0, y0, x1 - x0, y1 - y0, 0, 0, PlanTile.SIZE, PlanTile.SIZE, PlanTile.SIZE,
                            PlanTile.SIZE);
                } else if (!coarser(g, scale, tx, tz, x0, y0, x1, y1)) {
                    boolean far = tile != null && tile.status == CityMapNet.FAR;
                    g.fill(x0, y0, x1, y1, far ? 0xFF262626 : 0xFF303436);
                }
                if (tile == null || tile.status != CityMapNet.OK) {
                    double cx = (tx + 0.5) * span - centerX;
                    double cz = (tz + 0.5) * span - centerZ;
                    missing.add(new int[]{tx, tz, (int) Math.min(Integer.MAX_VALUE, cx * cx + cz * cz)});
                } else {
                    tile.drawn = now;
                }
            }
        }
        // Nearest the middle first
        missing.sort((a, b) -> Integer.compare(a[2], b[2]));
        for (int[] m : missing) {
            CityMapClient.want(scale, m[0], m[1], now);
        }
        player(g, left, top, bpp);
        g.pose().popPose();

        // Readouts
        int line = font.lineHeight + 2;
        double wx = worldX(mouseX);
        double wz = worldZ(mouseY);
        int[] counts = CityMapClient.counts();
        String head = String.format(Locale.ROOT, "City map  1 px = %s blocks   cursor %d %d", bpp >= 1
                ? String.valueOf((int) bpp) : "1/" + (int) Math.round(1 / bpp), (int) Math.floor(wx), (int) Math.floor(wz));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            head += String.format(Locale.ROOT, "   you %d %d %d", mc.player.getBlockX(), mc.player.getBlockY(),
                    mc.player.getBlockZ());
        }
        if (CityMapClient.inFlight() > 0) {
            head += "   drawing " + CityMapClient.inFlight() + "...";
        }
        g.fill(0, 0, width, line + 3, 0xC0000000);
        g.drawString(font, head, 4, 3, 0xFFFFFF);
        String help = "drag: pan  scroll, +/-: zoom  click: inspect, copy x z  C: centre  L: legend  R: redraw  " +
                CityMapClient.KEY.getTranslatedKeyMessage().getString() + ", Esc: close";
        g.fill(0, height - line - 3, width, height, 0xC0000000);
        g.drawString(font, help, 4, height - line, 0xC0C0C0);

        // The probe's answer
        List<String> probe = CityMapClient.probing ? List.of("asking...") : CityMapClient.probe;
        if (!probe.isEmpty()) {
            int w = 0;
            for (String s : probe) {
                w = Math.max(w, font.width(s));
            }
            // Bottom right, above the help line, clear of the legend at the top left
            int x = width - w - 10;
            int y = height - line - 8 - probe.size() * line;
            g.fill(x - 4, y - 4, width - 4, y + probe.size() * line, 0xD0000000);
            for (int i = 0; i < probe.size(); i++) {
                g.drawString(font, probe.get(i), x, y + i * line, i == 0 ? 0xFFFF80 : 0xFFFFFF);
            }
            // Its spot
            int px = (int) Math.round(width / 2.0 + (CityMapClient.probeX - centerX) / (gs * bpp));
            int pz = (int) Math.round(height / 2.0 + (CityMapClient.probeZ - centerZ) / (gs * bpp));
            g.fill(px - 3, pz, px + 4, pz + 1, 0xFFFFFF00);
            g.fill(px, pz - 3, px + 1, pz + 4, 0xFFFFFF00);
        }
        if (legend) {
            int rows = LEGEND.length;
            int columns = Math.max(1, (rows * line + height - 2 * line - 20) / Math.max(1, height - 2 * line - 20));
            int perColumn = (rows + columns - 1) / columns;
            int colW = 0;
            for (Object[] entry : LEGEND) {
                colW = Math.max(colW, font.width((String) entry[1]) + 14);
            }
            int x = 4;
            int y = line + 8;
            g.fill(x - 2, y - 3, x + columns * colW + 2, y + perColumn * line, 0xB0000000);
            for (int i = 0; i < rows; i++) {
                int cx = x + (i / perColumn) * colW;
                int cy = y + (i % perColumn) * line;
                g.fill(cx, cy, cx + 9, cy + 8, 0xFF000000 | (Integer) LEGEND[i][0]);
                g.drawString(font, (String) LEGEND[i][1], cx + 12, cy, 0xE0E0E0);
            }
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** Draws a coarser tile's part in place of a missing one; whether one was kept. */
    private boolean coarser(GuiGraphics g, int scale, int tx, int tz, int x0, int y0, int x1, int y1) {
        for (int n = 1; n <= 4 && (scale << n) <= 16; n++) {
            int parts = 1 << n;
            CityMapClient.Tile parent = CityMapClient.tile(scale << n, Math.floorDiv(tx, parts), Math.floorDiv(tz, parts));
            if (parent != null && parent.texture != null) {
                int size = PlanTile.SIZE / parts;
                g.blit(parent.texture, x0, y0, x1 - x0, y1 - y0, Math.floorMod(tx, parts) * size,
                        Math.floorMod(tz, parts) * size, size, size, PlanTile.SIZE, PlanTile.SIZE);
                return true;
            }
        }
        return false;
    }

    /** The player: a white square with a black edge, a tick toward where they face. */
    private void player(GuiGraphics g, double left, double top, double bpp) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        int px = (int) Math.round((mc.player.getX() - left) / bpp);
        int py = (int) Math.round((mc.player.getZ() - top) / bpp);
        int r = (int) Math.max(4, Math.round(3 * guiScale()));
        double yaw = Math.toRadians(mc.player.getYRot());
        double dx = -Math.sin(yaw);
        double dz = Math.cos(yaw);
        for (int i = 0; i <= 3 * r; i++) {
            int x = (int) Math.round(px + dx * i);
            int y = (int) Math.round(py + dz * i);
            g.fill(x - 2, y - 2, x + 3, y + 3, 0xFF000000);
        }
        for (int i = 0; i <= 3 * r; i++) {
            int x = (int) Math.round(px + dx * i);
            int y = (int) Math.round(py + dz * i);
            g.fill(x - 1, y - 1, x + 2, y + 2, 0xFFFFFFFF);
        }
        g.fill(px - r - 1, py - r - 1, px + r + 2, py + r + 2, 0xFF000000);
        g.fill(px - r, py - r, px + r + 1, py + r + 1, 0xFFFFFFFF);
    }

    private void zoomAbout(int by, double mouseX, double mouseY) {
        int next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + by));
        if (next == zoom) {
            return;
        }
        // Keep the point under the cursor where it is
        double wx = worldX(mouseX);
        double wz = worldZ(mouseY);
        zoom = next;
        centerX = wx - (mouseX - width / 2.0) * guiScale() * blocksPerPixel();
        centerZ = wz - (mouseY - height / 2.0) * guiScale() * blocksPerPixel();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            pressX = mouseX;
            pressY = mouseY;
            dragged = false;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0) {
            centerX -= dragX * guiScale() * blocksPerPixel();
            centerZ -= dragY * guiScale() * blocksPerPixel();
            if (Math.hypot(mouseX - pressX, mouseY - pressY) > 3) {
                dragged = true;
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && !dragged) {
            double x = Math.floor(worldX(mouseX)) + 0.5;
            double z = Math.floor(worldZ(mouseY)) + 0.5;
            CityMapClient.askProbe(x, z);
            Minecraft.getInstance().keyboardHandler.setClipboard((int) Math.floor(x) + " " + (int) Math.floor(z));
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta != 0) {
            zoomAbout(delta > 0 ? -1 : 1, mouseX, mouseY);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (CityMapClient.KEY.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> zoomAbout(-1, width / 2.0, height / 2.0);
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> zoomAbout(1, width / 2.0, height / 2.0);
            case GLFW.GLFW_KEY_C -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    centerX = mc.player.getX();
                    centerZ = mc.player.getZ();
                }
            }
            case GLFW.GLFW_KEY_L -> legend = !legend;
            case GLFW.GLFW_KEY_R -> CityMapClient.clear();
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return true;
    }

    /** For the tests: centre and zoom. */
    public void view(double x, double z, int zoom) {
        centerX = x;
        centerZ = z;
        this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }
}
