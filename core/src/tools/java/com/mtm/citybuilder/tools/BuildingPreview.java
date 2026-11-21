package com.mtm.citybuilder.tools;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.building.BuildingLayer;
import com.mtm.citybuilder.core.building.Palette;
import com.mtm.citybuilder.core.chunk.ChunkLayer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * An isometric picture of the buildings (L8) and streets over an area, block by block in colours near the blocks',
 * to judge how they look without starting the game. Seen from the south-east corner by default; {@code view} turns it
 * a quarter at a time.
 */
final class BuildingPreview {
    private BuildingPreview() {
    }

    /** Approximate colours of the blocks the palettes use. */
    private static final Map<String, Integer> COLOURS = new HashMap<>();

    /** Draw nothing more than this many blocks over each building's base (a cut-away, to see inside); -1 for all. */
    public static int cut = -1;

    static {
        Object[][] table = {
                {"white_terracotta", 0xD1B2A1}, {"calcite", 0xDFE0DC}, {"light_gray_concrete", 0x7D7D73},
                {"gray_concrete", 0x36393D}, {"white_concrete", 0xCFD5D6}, {"birch_planks", 0xC0AF79},
                {"smooth_sandstone", 0xD8CB9B}, {"cut_sandstone", 0xD9CE9F}, {"mud_bricks", 0x89684F},
                {"packed_mud", 0x8E6B50}, {"dark_oak_planks", 0x42291A}, {"dark_oak_log", 0x3C2E1A},
                {"stripped_dark_oak_log", 0x4A3A28}, {"spruce_planks", 0x725431}, {"stripped_spruce_log", 0x745A36},
                {"oak_planks", 0xA2834F}, {"glass", 0xA8D4E6}, {"light_blue_stained_glass", 0x7FB2D8},
                {"gray_stained_glass", 0x4C4C4C}, {"light_gray_stained_glass", 0x999999}, {"black_stained_glass", 0x191919},
                {"dark_oak_fence", 0x42291A}, {"iron_bars", 0x8A8A8A}, {"dark_oak_door", 0x4C331A},
                {"spruce_door", 0x6A4E2E}, {"birch_door", 0xD3C68E}, {"iron_door", 0xC0C0C0},
                {"deepslate_tile", 0x36363A}, {"deepslate_tiles", 0x36363A}, {"polished_blackstone_brick", 0x302A30},
                {"polished_blackstone_bricks", 0x302A30}, {"dark_prismarine", 0x335B4B}, {"mud_brick", 0x89684F},
                {"smooth_stone", 0x9E9E9E}, {"stone_bricks", 0x7A7A7A}, {"polished_andesite", 0x848685},
                {"bricks", 0x976253}, {"smooth_quartz", 0xEBE5DE}, {"quartz_pillar", 0xEBE6E0},
                {"brown_terracotta", 0x4D3324}, {"light_gray_terracotta", 0x876A61}, {"polished_diorite", 0xC0C1C2},
                {"iron_block", 0xDCDCDC}, {"light_blue_concrete", 0x2489C7}, {"smooth_stone_slab", 0xA8A8A8},
                {"polished_andesite_slab", 0x848685},
                {"red_wool", 0xA12722}, {"green_wool", 0x546D1B}, {"blue_wool", 0x35399D}, {"white_wool", 0xE9ECEC},
                {"orange_wool", 0xF07613}, {"brown_wool", 0x724728}, {"cyan_wool", 0x158991},
                {"yellow_concrete", 0xF1AF15}, {"red_concrete", 0x8E2121}, {"orange_concrete", 0xE06101},
                {"lime_concrete", 0x5EA919}, {"sea_lantern", 0xACC8BE}, {"blue_concrete", 0x2C2E8F},
                {"mossy_stone_bricks", 0x737A62}, {"cobblestone", 0x7F7F7F}, {"azalea_leaves", 0x5A7B2B},
                {"bamboo_fence", 0xC9B157}, {"potted_azalea_bush", 0x6A8C32}, {"potted_fern", 0x4E7A3A},
                {"potted_red_tulip", 0xB0302A}, {"potted_oak_sapling", 0x4D8A2A}, {"potted_flowering_azalea_bush", 0xB070A8},
                {"potted_bamboo", 0x6E9A2C},
                {"spruce_log", 0x3A2717}, {"dark_oak_log", 0x3C2E1A}, {"oak_log", 0x6D5532}, {"birch_log", 0xD8D7D2},
                {"spruce_leaves", 0x3D5C3D}, {"oak_leaves", 0x4A7A28}, {"dark_oak_leaves", 0x3E6A22},
                {"cherry_leaves", 0xE7A9C5}, {"azalea", 0x5E7C30}, {"flowering_azalea", 0x8A6E9E},
                {"green_terracotta", 0x4C532A}, {"andesite_wall", 0x888888},
                {"iron_trapdoor", 0xC8C8C8}, {"lantern", 0xE8A040}, {"white_stained_glass", 0xF0F0F0},
                {"black_wool", 0x141519}, {"light_blue_wool", 0x3AAFD9},
                {"spruce_fence", 0x725431}, {"mangrove_fence", 0x763631}, {"polished_blackstone", 0x35303A},
                {"yellow_terracotta", 0xBA8523}, {"mangrove_planks", 0x773634}, {"mangrove_log", 0x6B2C2A},
                {"mangrove_door", 0x773634}, {"bamboo_trapdoor", 0xC4AA4F}, {"magenta_wool", 0xBD44B3},
                {"purple_wool", 0x7A2AAD},
                {"stripped_spruce_log", 0x8E6A40}, {"gravel", 0x837E7C}, {"spruce_slab", 0x725431},
                {"stone_brick_wall", 0x7A7A7A}, {"black_concrete", 0x080A0F}, {"orange_terracotta", 0xA05325},
                {"light_blue_terracotta", 0x716C89}, {"ochre_froglight", 0xF5E9B5}, {"pearlescent_froglight", 0xEBDCE1},
                {"verdant_froglight", 0xD4EAD0}, {"chiseled_bookshelf", 0x725431}, {"barrel", 0x86643B},
                {"bookshelf", 0x6B5839}, {"magenta_concrete", 0xA9309F}, {"pink_concrete", 0xD5658E},
                {"green_concrete", 0x495B24}, {"cyan_terracotta", 0x565B5B}, {"polished_granite", 0x9A6A59},
                {"cyan_stained_glass", 0x4C7F99}, {"brown_stained_glass", 0x664C33}, {"blue_stained_glass", 0x334CB2},
                {"polished_deepslate", 0x484848}, {"brick", 0x976253}, {"cobbled_deepslate", 0x4D4D50}, {"mossy_cobblestone", 0x6E775E}, {"water", 0x3F76E4}, {"orange_concrete", 0xE06101}, {"brown_concrete", 0x603C20}, {"yellow_concrete", 0xF0AF15},
                {"light_blue_concrete", 0x2389C7}, {"sea_lantern", 0xACC8BE}, {"polished_blackstone_wall", 0x35303B}, {"coal_block", 0x101010}, {"shroomlight", 0xF09A4D}, {"blue_concrete", 0x2D2F8F}, {"green_concrete", 0x495B24}, {"cyan_concrete", 0x157788},
                {"red_concrete", 0x8E2121}, {"green_terracotta", 0x4C532A}, {"mud_bricks", 0x89684F}, {"daylight_detector", 0x3B4A6B}, {"lightning_rod", 0xB0703F},
                {"polished_blackstone_brick_wall", 0x302A30}, {"deepslate_tile_wall", 0x36363A}, {"white_carpet", 0xE9ECEC},
                {"light_blue_carpet", 0x3AAFD9}, {"pink_carpet", 0xED8DAC}, {"yellow_carpet", 0xF8C527},
                {"stripped_bamboo_block", 0xC0AE53}, {"bookshelf", 0x6B5839}, {"chest", 0xA06F2C}, {"smoker", 0x5A5652},
                {"furnace", 0x6E6E6E}, {"cauldron", 0x3E3E3E}, {"spruce_stairs", 0x72542F}, {"polished_andesite_stairs", 0x848685}, {"stone_stairs", 0x7E7E7E},
                {"smooth_quartz_stairs", 0xEBE5DE}, {"oak_stairs", 0xA2834F},
                {"dark_oak_stairs", 0x42291A}, {"spruce", 0x72542F}, {"oak", 0xA2834F}, {"dark_oak", 0x42291A},
                {"birch", 0xC0AF79}, {"stripped_birch_log", 0xC5B077}, {"hay_block", 0xA68B0C}, {"iron_block", 0xDCDCDC}, {"shroomlight", 0xF09A4D}, {"ochre_froglight", 0xF6E9A9}, {"cut_copper", 0xBF6B50}, {"exposed_cut_copper", 0xA17E68},
                {"weathered_cut_copper", 0x6C9C73}, {"oxidized_cut_copper", 0x50997F}, {"red_nether_brick", 0x45090A},
                {"cracked_stone_bricks", 0x767676}, {"cracked_deepslate_tiles", 0x343437}, {"gray_terracotta", 0x3A2A24},
                {"red_terracotta", 0x8E3C2E}, {"yellow_concrete", 0xF0AF15}, {"diorite", 0xBDBCBD}, {"andesite", 0x888889}, {"blackstone", 0x2A2328}, {"cracked_polished_blackstone_bricks", 0x2C262B}, {"granite", 0x956755}, {"red_nether_bricks", 0x45090A}, {"sandstone", 0xD8CB9B}, {"stone", 0x7E7E7E}, {"terracotta", 0x985E43}, {"decorated_pot", 0x8C4F3C}, {"oak_sign", 0xA2834F}, {"composter", 0x7D5A33}, {"stone_brick", 0x7A7A7A}, {"pink_terracotta", 0xA14E4E},
                {"light_gray_concrete", 0x7D7D73},
        };
        for (Object[] row : table) {
            COLOURS.put("minecraft:" + row[0], (Integer) row[1]);
        }
    }

    private static int colour(String id) {
        if (id.endsWith("_pane")) {
            id = id.substring(0, id.length() - 5);
        }
        if (id.endsWith("_wall_banner")) {
            id = id.substring(0, id.length() - 12) + "_wool";
        }
        String key = id.endsWith("_slab") ? id.substring(0, id.length() - 5) : id;
        Integer c = COLOURS.get(key);
        if (c == null) {
            c = COLOURS.get(id);
        }
        return c == null ? 0xFF00FF : c;
    }

    /** A block to draw: its colour, its height (1 or a slab's half) and where in the block it starts. */
    private record Voxel(int rgb, double height, double from) {
    }

    private static Voxel voxel(BuildingLayer.Part part, Palette p, com.mtm.citybuilder.core.building.Building building,
                               long position, int y) {
        String id = p.id(part, building, position, y);
        if (id == null || id.endsWith(":air") || id.endsWith(":light")) {
            return null;
        }
        return switch (part) {
            case ROOF_LOW -> new Voxel(colour(id), 0.5, 0);
            case ROOF_HIGH -> new Voxel(colour(id), 0.5, 0.5);
            case PLANT, SHRUB, STAND -> new Voxel(colour(id), 0.5, 0);
            case CARPORT -> new Voxel(colour(id), 0.5, 0.5);
            case HOOD, LANTERN, ENGAWA, TORO_CAP, TORO_LIGHT, TABLE, DESK -> new Voxel(colour(id), 0.5, 0);
            case SOLAR -> new Voxel(colour(id), 0.375, 0);
            case FUTON -> new Voxel(colour(id), 0.125, 0);
            default -> new Voxel(colour(id), 1, 0);
        };
    }

    /**
     * Renders {@code area} × {@code area} blocks round a centre, {@code px} pixels to a block's edge, turned {@code view}
     * quarters.
     */
    static BufferedImage render(CityLogic logic, double centerX, double centerZ, int area, int px, int view) {
        int x0 = (int) Math.floor(centerX - area / 2.0);
        int z0 = (int) Math.floor(centerZ - area / 2.0);
        BuildingLayer.Column[][] columns = new BuildingLayer.Column[area][area];
        ChunkLayer.Column[][] streets = new ChunkLayer.Column[area][area];
        Map<Long, BuildingLayer.Column[]> chunks = new HashMap<>();
        Map<Long, ChunkLayer.Column[]> streetChunks = new HashMap<>();
        int[][] ground = new int[area][area];
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int i = 0; i < area; i++) {
            for (int k = 0; k < area; k++) {
                int x = x0 + i;
                int z = z0 + k;
                int cx = Math.floorDiv(x, 16);
                int cz = Math.floorDiv(z, 16);
                long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
                BuildingLayer.Column[] cs = chunks.computeIfAbsent(key, kk -> logic.buildings().columns(cx, cz));
                ChunkLayer.Column[] ss = streetChunks.computeIfAbsent(key, kk -> logic.chunks().columns(cx, cz));
                int c = Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16);
                columns[i][k] = cs[c];
                streets[i][k] = ss[c];
                ground[i][k] = (int) Math.floor(logic.terrain().surfaceHeight(x, z));
                minY = Math.min(minY, ground[i][k]);
                if (cs[c].building() != null) {
                    maxY = Math.max(maxY, BuildingLayer.top(cs[c]));
                }
                maxY = Math.max(maxY, ground[i][k]);
            }
        }
        int floorY = minY - 2;
        int h = maxY - floorY + 2;
        int w = area * px * 2;
        int height = area * px + h * px + 20;
        BufferedImage image = new BufferedImage(w, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(new Color(0xBFD8EA));
        g.fillRect(0, 0, w, height);
        int originX = w / 2;
        int originY = h * px + 10;
        // Painter's order: from the back (low screen depth) to the front, bottom to top
        for (int depth = 0; depth < 2 * area - 1; depth++) {
            for (int a = 0; a < area; a++) {
                int b = depth - a;
                if (b < 0 || b >= area) {
                    continue;
                }
                // Screen axes (a, b) to the area's (i, k), turned
                int i;
                int k;
                switch (Math.floorMod(view, 4)) {
                    case 1 -> { i = area - 1 - b; k = a; }
                    case 2 -> { i = area - 1 - a; k = area - 1 - b; }
                    case 3 -> { i = b; k = area - 1 - a; }
                    default -> { i = a; k = b; }
                }
                BuildingLayer.Column column = columns[i][k];
                int gy = ground[i][k];
                int groundColour = streets[i][k].surface() != ChunkLayer.Surface.NONE
                        ? (ChunkLayer.paving(streets[i][k]) == ChunkLayer.Paving.SIDEWALK ? 0xA9A39A : 0x4A4A4E) : 0x6E9A4E;
                if (column.building() == null) {
                    cube(g, originX, originY, px, a, b, gy - floorY, 1, 0, groundColour);
                    continue;
                }
                Palette palette = Palette.get(column.building().palette());
                int from = Math.min(gy + 1, BuildingLayer.bottom(column));
                cube(g, originX, originY, px, a, b, Math.min(gy, from - 1) - floorY, 1, 0, groundColour);
                int last = cut >= 0 ? Math.min(BuildingLayer.top(column), column.building().base() + cut) : BuildingLayer.top(column);
                for (int y = from; y <= last; y++) {
                    BuildingLayer.Part part = BuildingLayer.part(column, y);
                    if (part == BuildingLayer.Part.FOUNDATION && y <= gy) {
                        continue;
                    }
                    Voxel v = voxel(part, palette, column.building(), (long) i * 7919 + k, y);
                    if (v != null) {
                        cube(g, originX, originY, px, a, b, y - floorY, v.height, v.from, v.rgb);
                    }
                }
            }
        }
        g.dispose();
        return image;
    }

    private static int shade(int rgb, double f) {
        int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * f);
        int gr = (int) Math.min(255, ((rgb >> 8) & 0xFF) * f);
        int b = (int) Math.min(255, (rgb & 0xFF) * f);
        return (r << 16) | (gr << 8) | b;
    }

    /** One block at screen cell (a, b) and height y: its top and two front faces, shaded. */
    private static void cube(Graphics2D g, int ox, int oy, int px, int a, int b, double y, double height, double from, int rgb) {
        double sx = ox + (a - b) * px;
        double sy = oy + (a + b) * px * 0.5 - (y + from) * px;
        double top = sy - height * px;
        // Top rhombus
        Polygon topFace = new Polygon(
                new int[]{(int) sx, (int) (sx + px), (int) sx, (int) (sx - px)},
                new int[]{(int) (top - 0.0), (int) (top + px * 0.5), (int) (top + px), (int) (top + px * 0.5)}, 4);
        Polygon left = new Polygon(
                new int[]{(int) (sx - px), (int) sx, (int) sx, (int) (sx - px)},
                new int[]{(int) (top + px * 0.5), (int) (top + px), (int) (sy + px), (int) (sy + px * 0.5)}, 4);
        Polygon right = new Polygon(
                new int[]{(int) sx, (int) (sx + px), (int) (sx + px), (int) sx},
                new int[]{(int) (top + px), (int) (top + px * 0.5), (int) (sy + px * 0.5), (int) (sy + px)}, 4);
        g.setColor(new Color(shade(rgb, 0.78)));
        g.fillPolygon(left);
        g.setColor(new Color(shade(rgb, 0.6)));
        g.fillPolygon(right);
        g.setColor(new Color(rgb));
        g.fillPolygon(topFace);
    }
}
